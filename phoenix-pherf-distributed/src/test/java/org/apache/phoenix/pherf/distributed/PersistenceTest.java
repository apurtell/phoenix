/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.pherf.distributed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.distributed.transport.http.RestClient;
import org.junit.Test;

/**
 * Verifies RQ-241: restarting a controller on the same run directory preserves all tests and ended
 * runs without loss. For any ended run, the frozen report, the run status, and the bundle contents
 * remain identical after the restart. Any run that was in flight at the time of the restart
 * transitions to FAILED and is never resumed, while leftover files from a prior crash do not
 * prevent state restoration.
 */
public class PersistenceTest {
  private static final String INTERRUPTED = "the controller stopped during the run";

  private static Simulation fleet(String... ids) {
    Simulation simulation = new Simulation();
    for (String id : ids) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    return simulation;
  }

  private static String plan(int agents, long intervals) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", Long.toString(intervals));
    return Plans.plan(7).step(Plans.step(agents)
      .scenario("scenario.xml", "<datamodel name=\"fake\"/>").properties(properties)).json();
  }

  private static String runs(String test) {
    return "/v1/tests/" + test + "/runs";
  }

  private static String run(String test, String run, String action) {
    return runs(test) + "/" + run + "/" + action;
  }

  private static String createTest(RestClient rest, String plan) throws Exception {
    return rest.post("/v1/tests", plan).json(201).get("id").getAsString();
  }

  private static String createRun(RestClient rest, String test) throws Exception {
    return rest.post(runs(test), "").json(201).get("runId").getAsString();
  }

  private static String awaitEnd(Simulation simulation, RestClient rest, String test, String run)
    throws Exception {
    long deadline = simulation.clock.millis() + 60000;
    while (true) {
      simulation.runFor(250);
      String state = rest.get(run(test, run, "status")).json(200).get("state").getAsString();
      if (!"RUNNING".equals(state)) {
        return state;
      }
      assertTrue(simulation.clock.millis() < deadline);
    }
  }

  /** Parses and returns the numeric suffix following the last '-' delimiter in an identifier. */
  private static long suffix(String id) {
    return Long.parseLong(id.substring(id.lastIndexOf('-') + 1));
  }

  private static void restart(Simulation simulation, RestClient rest) throws Exception {
    simulation.restartController();
    rest.serve();
  }

  @Test
  public void frozenReportSurvivesRestart() throws Exception {
    Simulation simulation = fleet("a1", "a2");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(2, 3));
      String runId = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, runId));
      byte[] report = rest.get(run(test, runId, "report")).body;
      String status = rest.get(run(test, runId, "status")).text();
      String listing = rest.get(run(test, runId, "bundle")).text();
      byte[] manifest = rest.get(run(test, runId, "bundle/manifest.json")).body;

      restart(simulation, rest);
      RestClient.Response after = rest.get(run(test, runId, "report"));
      assertEquals(after.text(), 200, after.code);
      assertArrayEquals(report, after.body);
      assertEquals(JsonParser.parseString(status), rest.get(run(test, runId, "status")).json(200));
      assertEquals(listing, rest.get(run(test, runId, "bundle")).text());
      assertArrayEquals(manifest, rest.get(run(test, runId, "bundle/manifest.json")).body);

      // Allow the agents to re-register and verify that the restored test executes a new run
      // using the plan reloaded from plan.json.
      simulation.runFor(Protocol.DEADLINE_MS);
      assertEquals(2, rest.get("/v1/agents").json(200).getAsJsonArray("agents").size());
      String next = createRun(rest, test);
      assertNotEquals(runId, next);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, next));
      assertArrayEquals(report, rest.get(run(test, runId, "report")).body);
    }
  }

  @Test
  public void runInFlightFailsAtRestart() throws Exception {
    Simulation simulation = fleet("a1", "a2");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(2, 30));
      String runId = createRun(rest, test);
      simulation.runFor(8000);
      assertEquals("RUNNING",
        rest.get(run(test, runId, "status")).json(200).get("state").getAsString());

      restart(simulation, rest);
      JsonObject status = rest.get(run(test, runId, "status")).json(200);
      assertEquals("FAILED", status.get("state").getAsString());
      assertEquals(INTERRUPTED, status.get("reason").getAsString());
      assertEquals(0, status.getAsJsonArray("steps").size());
      rest.get(run(test, runId, "report")).error(404, "not_found");
      rest.get(run(test, runId, "bundle")).error(404, "not_found");
      File bundle = simulation.bundle(runId);
      assertFalse(new File(bundle, RunBundle.MANIFEST).exists());
      assertEquals("FAILED",
        Bundles.json(new File(bundle, RunBundle.STATUS)).get("state").getAsString());

      // Verify that the agents receive a reregister reply, cancel their in-flight work, and
      // return to the IDLE state.
      simulation.runFor(Protocol.DEADLINE_MS);
      assertTrue(simulation.count(InProcessNetwork.REPLY, "reregister") >= 2);
      for (String id : new String[] { "a1", "a2" }) {
        assertTrue(id, simulation.workload(id).cancelled.contains(runId));
        assertEquals(id, "IDLE", simulation.view(id).getState().name());
      }
      String next = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, next));
    }
  }

  @Test
  public void endedTestStaysEnded() throws Exception {
    Simulation simulation = fleet("a1");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(1, 3));
      JsonObject ended = rest.post("/v1/tests/" + test + "/end", "").json(200);
      assertEquals("ENDED", ended.get("state").getAsString());
      restart(simulation, rest);
      simulation.runFor(Protocol.DEADLINE_MS);
      assertEquals(ended, rest.post("/v1/tests/" + test + "/end", "").json(200));
      assertEquals("test " + test + " has ended", rest.post(runs(test), "").error(409, "conflict"));
    }
  }

  @Test
  public void crashLeftoversAreIgnored() throws Exception {
    Simulation simulation = fleet("a1");
    CapturingAppender log = CapturingAppender.attach();
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(1, 3));
      String kept = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, kept));
      String uncommitted = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, uncommitted));
      String broken = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, broken));
      File runDir = simulation.runDir;

      // Simulate a crash prior to the atomic rename, which leaves a .tmp staging file behind
      // while the previously committed file remains in place.
      Files.write(new File(simulation.bundle(kept), "status.json.tmp").toPath(), new byte[] { 1 });
      File tests = new File(runDir, "tests");
      Files.write(new File(new File(tests, test), "test.json.tmp").toPath(), new byte[] { 1 });
      // Simulate a crash occurring after status.json is written but before manifest.json is
      // created, leaving the completion of the run uncommitted.
      Files.delete(new File(simulation.bundle(uncommitted), RunBundle.MANIFEST).toPath());
      // Write a malformed status file containing invalid JSON that cannot be parsed.
      File brokenStatus = new File(simulation.bundle(broken), RunBundle.STATUS);
      Files.write(brokenStatus.toPath(), "{".getBytes(StandardCharsets.UTF_8));
      // Simulate a crash during test creation before test.json is written, representing an
      // incomplete submission where the client never received a 201 response.
      File orphan = new File(tests, "t-20260101T000000000Z-99");
      Files.createDirectories(orphan.toPath());
      Files.write(new File(orphan, "plan.json").toPath(),
        plan(1, 3).getBytes(StandardCharsets.UTF_8));

      restart(simulation, rest);
      assertEquals("COMPLETE",
        rest.get(run(test, kept, "status")).json(200).get("state").getAsString());
      assertEquals(200, rest.get(run(test, kept, "report")).code);
      assertFalse(rest.get(run(test, kept, "bundle")).text().contains(".tmp"));

      JsonObject interrupted = rest.get(run(test, uncommitted, "status")).json(200);
      assertEquals("FAILED", interrupted.get("state").getAsString());
      assertEquals(INTERRUPTED, interrupted.get("reason").getAsString());
      rest.get(run(test, uncommitted, "report")).error(404, "not_found");
      assertFalse(new File(simulation.bundle(uncommitted), RunBundle.MANIFEST).exists());
      assertEquals(INTERRUPTED,
        Bundles.json(new File(simulation.bundle(uncommitted), RunBundle.STATUS)).get("reason")
          .getAsString());

      rest.get(run(test, broken, "status")).error(404, "not_found");
      boolean warned = false;
      for (LogEvent event : log.events("org.apache.phoenix.pherf.distributed")) {
        if (
          event.getLevel() == Level.WARN
            && event.getMessage().getFormattedMessage().contains(brokenStatus.getPath())
        ) {
          warned = true;
        }
      }
      assertTrue(warned);

      // Verify that the restored test remains operational despite the crash leftovers and that
      // subsequent test identifiers advance past the sequence number of the orphan directory.
      simulation.runFor(Protocol.DEADLINE_MS);
      String next = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, next));
      assertEquals(100, suffix(createTest(rest, plan(1, 3))));
    } finally {
      log.detach();
    }
  }

  /**
   * Verifies that because a run is reachable only through a test that records it in test.json, a
   * failure to persist that file causes the submission to return an HTTP 500 error and cancel the
   * run, keeping the in-memory state consistent with on-disk state both before and after a
   * controller restart.
   */
  @Test
  public void unlistedRunIsCancelled() throws Exception {
    Simulation simulation = fleet("a1");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(1, 30));
      File testJson = new File(new File(new File(simulation.runDir, "tests"), test), "test.json");
      byte[] listed = Files.readAllBytes(testJson.toPath());
      // Moving test.json.tmp into place fails because it cannot overwrite a non-empty directory.
      Files.delete(testJson.toPath());
      Files.createDirectories(new File(testJson, "block").toPath());
      String message = rest.post(runs(test), "").error(500, "internal");
      Matcher id = Pattern.compile("run (" + Controller.RUN_ID.pattern() + ") is cancelled: ")
        .matcher(message);
      assertTrue(message, id.lookingAt());
      String unlisted = id.group(1);
      assertEquals(RunView.State.CANCELLED, simulation.run(unlisted).getState());
      rest.get(run(test, unlisted, "status")).error(404, "not_found");
      simulation.runFor(Protocol.HOLD_MS);
      assertEquals("IDLE", simulation.view("a1").getState().name());

      Files.delete(new File(testJson, "block").toPath());
      Files.delete(testJson.toPath());
      Files.write(testJson.toPath(), listed);
      restart(simulation, rest);
      rest.get(run(test, unlisted, "status")).error(404, "not_found");
    }
  }

  @Test
  public void idsDoNotRepeatAfterRestart() throws Exception {
    Simulation simulation = fleet("a1");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(1, 3));
      String first = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, first));
      restart(simulation, rest);
      simulation.runFor(Protocol.DEADLINE_MS);
      String nextTest = createTest(rest, plan(1, 3));
      assertNotEquals(test, nextTest);
      assertEquals(suffix(test) + 1, suffix(nextTest));
      String next = createRun(rest, nextTest);
      assertEquals(suffix(first) + 1, suffix(next));
      assertEquals("COMPLETE", awaitEnd(simulation, rest, nextTest, next));
    }
  }
}
