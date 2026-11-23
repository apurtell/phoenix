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
package org.apache.phoenix.pherf.distributed.transport.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.Bundles;
import org.apache.phoenix.pherf.distributed.FakeWorkload;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.controller.TestTable;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

/**
 * Verifies RQ-240 and RQ-242 by exercising the REST API over real HTTP with a controller and
 * multiple agents running inside a single JVM driven by the virtual clock. The eight endpoints
 * defined in section 3.5 advance a run through to a frozen report, cancellation returns the agents
 * assigned to a run back to the idle pool, a reset returns an individual faulted agent while the
 * test remains open, and every error response carries a stable {"error","message"} payload.
 */
public class RestApiTest {
  private static final String REPORT = "drivers/pherf/results_HDR/LATENCY_run.json";
  private static final String VERDICT = "drivers/pherf/results_HDR/VERDICT_run.tsv";
  private static final String HLOG = "drivers/pherf/results_HDR/LATENCY_run.hlog";

  static Simulation fleet(String... ids) {
    Simulation simulation = new Simulation();
    for (String id : ids) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    return simulation;
  }

  /** Builds a single-step test plan configured with the given FakeWorkload properties. */
  static String plan(int agents, Map<String, String> properties) {
    return Plans.plan(7).step(Plans.step(agents)
      .scenario("scenario.xml", "<datamodel name=\"fake\"/>").properties(properties)).json();
  }

  /** Builds a single-step test plan that runs for the specified number of steady intervals. */
  static String plan(int agents, long intervals) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", Long.toString(intervals));
    return plan(agents, properties);
  }

  static String runs(String test) {
    return "/v1/tests/" + test + "/runs";
  }

  static String run(String test, String run, String action) {
    return runs(test) + "/" + run + "/" + action;
  }

  static String createTest(RestClient rest, String plan) throws Exception {
    JsonObject test = rest.post("/v1/tests", plan).json(201);
    return test.get("id").getAsString();
  }

  static String createRun(RestClient rest, String test) throws Exception {
    JsonObject run = rest.post(runs(test), "").json(201);
    assertEquals("RUNNING", run.get("state").getAsString());
    return run.get("runId").getAsString();
  }

  /** Advances the simulation until the run ends and returns its final status snapshot. */
  static JsonObject awaitEnd(Simulation simulation, RestClient rest, String test, String run)
    throws Exception {
    long deadline = simulation.clock.millis() + 60000;
    while (true) {
      simulation.runFor(250);
      JsonObject status = rest.get(run(test, run, "status")).json(200);
      if (!"RUNNING".equals(status.get("state").getAsString())) {
        return status;
      }
      assertTrue(status.toString(), simulation.clock.millis() < deadline);
    }
  }

  static JsonArray slots(JsonObject status) {
    return status.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("slots");
  }

  /** Retrieves the agent views from GET /v1/agents indexed by agent identifier. */
  static Map<String, JsonObject> agents(RestClient rest) throws Exception {
    Map<String, JsonObject> agents = new LinkedHashMap<>();
    for (JsonElement element : rest.get("/v1/agents").json(200).getAsJsonArray("agents")) {
      JsonObject agent = element.getAsJsonObject();
      agents.put(agent.get("id").getAsString(), agent);
    }
    return agents;
  }

  static void allIdle(RestClient rest, String... ids) throws Exception {
    Map<String, JsonObject> agents = agents(rest);
    assertEquals(Arrays.asList(ids), new ArrayList<>(agents.keySet()));
    for (JsonObject agent : agents.values()) {
      assertEquals(agent.toString(), "IDLE", agent.get("state").getAsString());
      assertFalse(agent.toString(), agent.has("fault"));
    }
  }

  @Test
  public void eightEndpointsDriveARunToAFrozenReport() throws Exception {
    Simulation simulation = fleet("a1", "a2", "a3");
    try (RestClient rest = new RestClient(simulation)) {
      // Query the agent inventory via GET /v1/agents.
      RestClient.Response listed = rest.get("/v1/agents");
      assertEquals(ChannelHandler.JSON, listed.contentType);
      // When the client sends "Connection: close", the response echoes that header so that no
      // client attempts to reuse a connection that the server is closing.
      assertEquals("close", listed.connection);
      assertEquals("close",
        rest.call(rest.agentPort(), "GET", Protocol.CONTROL_PATH, null).connection);
      allIdle(rest, "a1", "a2", "a3");

      // Create a new test session via POST /v1/tests.
      JsonObject created = rest.post("/v1/tests", plan(3, 5)).json(201);
      String test = created.get("id").getAsString();
      assertTrue(test, TestTable.ID.matcher(test).matches());
      assertEquals("OPEN", created.get("state").getAsString());
      assertEquals(Simulation.START_MS + 1000, created.get("createdMs").getAsLong());
      assertEquals(new JsonArray(), created.getAsJsonArray("runs"));
      assertFalse(created.has("endedMs"));

      // Submit a new run under the test via POST /v1/tests/{id}/runs.
      JsonObject submitted = rest.post(runs(test), "").json(201);
      String runId = submitted.get("runId").getAsString();
      assertEquals("RUNNING", submitted.get("state").getAsString());
      for (JsonElement slot : slots(submitted)) {
        assertEquals("ASSIGNED", slot.getAsJsonObject().get("state").getAsString());
      }

      // Both GET .../report and the bundle endpoint remain unavailable until the run finishes.
      assertTrue(rest.get(run(test, runId, "report")).error(409, "conflict")
        .startsWith("run " + runId + " is RUNNING"));
      rest.get(run(test, runId, "bundle")).error(409, "conflict");

      // Poll GET .../status across virtual time as the run and its slots progress.
      List<String> runStates = new ArrayList<>();
      List<String> slotStates = new ArrayList<>();
      runStates.add("RUNNING");
      slotStates.add("ASSIGNED");
      JsonObject status;
      do {
        simulation.runFor(250);
        status = rest.get(run(test, runId, "status")).json(200);
        String runState = status.get("state").getAsString();
        if (!runState.equals(runStates.get(runStates.size() - 1))) {
          runStates.add(runState);
        }
        String slotState = slots(status).get(0).getAsJsonObject().get("state").getAsString();
        if (!slotState.equals(slotStates.get(slotStates.size() - 1))) {
          slotStates.add(slotState);
        }
        assertTrue(status.toString(), simulation.clock.millis() < Simulation.START_MS + 60000);
      } while ("RUNNING".equals(status.get("state").getAsString()));
      assertEquals(Arrays.asList("RUNNING", "COMPLETE"), runStates);
      assertEquals("DONE", slotStates.get(slotStates.size() - 1));
      assertTrue(slotStates.toString(), slotStates.contains("RUNNING"));
      int last = -1;
      for (String state : slotStates) {
        int ordinal = RunView.SlotState.valueOf(state).ordinal();
        assertTrue(slotStates.toString(), ordinal > last);
        last = ordinal;
      }
      // The status payload matches the Gson serialization of the controller's run view.
      assertEquals(new Gson().toJsonTree(simulation.run(runId)), status);

      // Verify that GET .../report returns the frozen fleet report byte for byte.
      File bundle = simulation.bundle(runId);
      RestClient.Response report = rest.get(run(test, runId, "report"));
      assertEquals(report.text(), 200, report.code);
      assertEquals(ChannelHandler.JSON, report.contentType);
      assertArrayEquals(Files.readAllBytes(new File(bundle, REPORT).toPath()), report.body);

      // Verify bundle retrieval by fetching the sorted file listing and then downloading each
      // individual artifact file in a separate request.
      JsonArray files = rest.get(run(test, runId, "bundle")).json(200).getAsJsonArray("files");
      List<String> paths = new ArrayList<>();
      for (JsonElement element : files) {
        JsonObject file = element.getAsJsonObject();
        String path = file.get("path").getAsString();
        paths.add(path);
        assertEquals(path, new File(bundle, path).length(), file.get("bytes").getAsLong());
      }
      List<String> sorted = new ArrayList<>(paths);
      Collections.sort(sorted);
      assertEquals(sorted, paths);
      for (String path : new String[] { "manifest.json", "status.json", REPORT, VERDICT, HLOG,
        "drivers/pherf/results_HDR/slots/0-0.hlog" }) {
        assertTrue(path + " in " + paths, paths.contains(path));
      }
      RestClient.Response verdict = rest.get(run(test, runId, "bundle/" + VERDICT));
      assertEquals(200, verdict.code);
      assertEquals("text/tab-separated-values", verdict.contentType);
      assertArrayEquals(Files.readAllBytes(new File(bundle, VERDICT).toPath()), verdict.body);
      RestClient.Response hlog = rest.get(run(test, runId, "bundle/" + HLOG));
      assertEquals("text/plain", hlog.contentType);
      assertArrayEquals(Files.readAllBytes(new File(bundle, HLOG).toPath()), hlog.body);
      assertEquals("COMPLETE",
        JsonParser.parseString(rest.get(run(test, runId, "bundle/manifest.json")).text())
          .getAsJsonObject().getAsJsonObject("run").get("state").getAsString());

      // Attempting POST .../cancel against a run that is already COMPLETE is rejected.
      assertEquals("run " + runId + " is COMPLETE, not RUNNING",
        rest.post(run(test, runId, "cancel"), "").error(409, "conflict"));

      // Invoking POST /v1/agents/{agentId}/reset on an already idle agent acts as a no-op.
      JsonObject reset = rest.post("/v1/agents/a1/reset", "").json(200);
      assertEquals("a1", reset.get("id").getAsString());
      assertEquals("IDLE", reset.get("state").getAsString());

      // Close the test session via POST /v1/tests/{id}/end and verify that a subsequent run
      // submission to the ended test is rejected.
      JsonObject ended = rest.post("/v1/tests/" + test + "/end", "").json(200);
      assertEquals("ENDED", ended.get("state").getAsString());
      assertEquals(simulation.clock.millis(), ended.get("endedMs").getAsLong());
      JsonArray expected = new JsonArray();
      expected.add(runId);
      assertEquals(expected, ended.getAsJsonArray("runs"));
      assertEquals("test " + test + " has ended", rest.post(runs(test), "").error(409, "conflict"));
    }
  }

  @Test
  public void errorContract() throws Exception {
    Simulation simulation = fleet("a1", "a2", "a3");
    try (RestClient rest = new RestClient(simulation)) {
      // HTTP 400 responses: a test plan rejected by RunSpec identifies the offending JSON path,
      // and a run submission request does not accept a non-empty payload body.
      String refused = rest
        .post("/v1/tests",
          Plans.plan(7).step(Plans.step(0).scenario("s.xml", "<datamodel/>")).json())
        .error(400, "bad_request");
      assertTrue(refused, refused.startsWith("steps[0].agents: "));
      rest.post("/v1/tests", "not json").error(400, "bad_request");
      String test = createTest(rest, plan(3, 20));
      assertEquals("the body of this request must be empty or {}",
        rest.post(runs(test), "{\"agents\":1}").error(400, "bad_request"));

      // HTTP 404 responses are returned for unknown paths, identifiers that do not match their
      // required pattern, and unknown resources.
      assertEquals("no resource /v1/nothing", rest.get("/v1/nothing").error(404, "not_found"));
      rest.get("/v2/agents").error(404, "not_found");
      rest.get("/v1/agents/").error(404, "not_found");
      rest.post(Protocol.CONTROL_PATH, "{}").error(404, "not_found");
      String unknownTest = "t-20260101T000000000Z-99";
      assertEquals("no test " + unknownTest,
        rest.post(runs(unknownTest), "").error(404, "not_found"));
      assertEquals("no test bad", rest.post(runs("bad"), "").error(404, "not_found"));
      assertEquals("no test " + unknownTest,
        rest.post("/v1/tests/" + unknownTest + "/end", "").error(404, "not_found"));
      assertEquals("no agent nosuch",
        rest.post("/v1/agents/nosuch/reset", "").error(404, "not_found"));
      rest.post("/v1/agents/a%2F1/reset", "").error(404, "not_found");

      // Unsupported HTTP methods return 405 accompanied by an Allow header.
      RestClient.Response wrong = rest.get("/v1/tests");
      wrong.error(405, "method_not_allowed");
      assertEquals("POST", wrong.allow);
      wrong = rest.post("/v1/agents", "");
      wrong.error(405, "method_not_allowed");
      assertEquals("GET", wrong.allow);

      // Conflicting operations issued while a run is active return HTTP 409.
      String runId = createRun(rest, test);
      wrong = rest.call("DELETE", run(test, runId, "status"), null);
      wrong.error(405, "method_not_allowed");
      assertEquals("GET", wrong.allow);
      assertEquals("test " + test + " has an active run " + runId,
        rest.post(runs(test), "").error(409, "conflict"));
      String other = createTest(rest, plan(1, 3));
      assertEquals("step 0 needs 1 agents but 0 are free",
        rest.post(runs(other), "").error(409, "conflict"));
      assertEquals("run " + runId + " is RUNNING; its report exists when the run ends",
        rest.get(run(test, runId, "report")).error(409, "conflict"));
      rest.get(run(test, runId, "bundle")).error(409, "conflict");
      rest.get(run(test, runId, "bundle/manifest.json")).error(409, "conflict");
      assertEquals("agent a1 has a slot of run " + runId,
        rest.post("/v1/agents/a1/reset", "").error(409, "conflict"));

      // Querying a run belonging to another test or an unknown run identifier returns HTTP 404.
      assertEquals("no run " + runId + " in test " + other,
        rest.get(run(other, runId, "status")).error(404, "not_found"));
      rest.get(run(test, "20260101T000000000Z-99", "status")).error(404, "not_found");
      rest.post(run(test, "20260101T000000000Z-99", "cancel"), "").error(404, "not_found");

      awaitEnd(simulation, rest, test, runId);
      assertEquals("run " + runId + " is COMPLETE, not RUNNING",
        rest.post(run(test, runId, "cancel"), "").error(409, "conflict"));

      // The bundle endpoint serves only regular files contained within the run directory.
      File bundle = simulation.bundle(runId);
      File outside = new File(simulation.runDir, "outside.json");
      Files.write(outside.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
      Files.createSymbolicLink(new File(bundle, "link.json").toPath(), outside.toPath());
      Files.write(new File(bundle, "manifest.json.tmp").toPath(), new byte[1]);
      String listing = rest.get(run(test, runId, "bundle")).text();
      assertFalse(listing, listing.contains("link.json"));
      assertFalse(listing, listing.contains(".tmp"));
      for (String path : new String[] { "../outside.json", "%2e%2e/outside.json", "link.json",
        "manifest.json.tmp", "drivers", "drivers/pherf/..", "nofile.json", "a%20b",
        "drivers//pherf" }) {
        rest.get(run(test, runId, "bundle/" + path)).error(404, "not_found");
      }
      assertEquals(200, rest.get(run(test, runId, "bundle/manifest.json")).code);

      // Ending a test is idempotent, and an ended test rejects any new run submissions.
      JsonObject ended = rest.post("/v1/tests/" + test + "/end", "{}").json(200);
      assertEquals(ended, rest.post("/v1/tests/" + test + "/end", "").json(200));
      assertEquals("test " + test + " has ended", rest.post(runs(test), "").error(409, "conflict"));

      // Verify HTTP 413 handling directly against the handler using an in-memory exchange,
      // because a live HTTP client could not read the response while still transmitting the
      // oversized request body.
      FakeExchange large =
        new FakeExchange("POST", "/v1/tests", new byte[Protocol.MAX_BODY_BYTES + 1]);
      new RestHandler(simulation.controller, simulation.tests).handle(large);
      assertEquals(413, large.getResponseCode());
      JsonObject body = JsonParser.parseString(large.responseText()).getAsJsonObject();
      assertEquals("too_large", body.get("error").getAsString());
      assertEquals(2, body.size());
      assertTrue(large.closed());
    }
  }

  @Test
  public void cancelReturnsAgentsToIdle() throws Exception {
    Simulation simulation = fleet("a1", "a2", "a3");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(3, 20));
      String runId = createRun(rest, test);
      simulation.runFor(6000);
      JsonObject running = rest.get(run(test, runId, "status")).json(200);
      for (JsonElement slot : slots(running)) {
        assertEquals("RUNNING", slot.getAsJsonObject().get("state").getAsString());
      }

      JsonObject cancelled = rest.post(run(test, runId, "cancel"), "").json(200);
      assertEquals("CANCELLED", cancelled.get("state").getAsString());
      assertEquals("cancelled", cancelled.get("reason").getAsString());
      for (JsonElement slot : slots(cancelled)) {
        assertEquals("CANCELLED", slot.getAsJsonObject().get("state").getAsString());
      }
      simulation.runFor(Protocol.HOLD_MS);
      allIdle(rest, "a1", "a2", "a3");
      for (String id : new String[] { "a1", "a2", "a3" }) {
        assertEquals(Collections.singletonList(runId), simulation.workload(id).cancelled);
      }
      // Issuing a second cancel request is idempotent and returns the same cancelled state.
      assertEquals("CANCELLED",
        rest.post(run(test, runId, "cancel"), "").json(200).get("state").getAsString());
      File bundle = simulation.bundle(runId);
      assertEquals("CANCELLED",
        Bundles.manifest(bundle).getAsJsonObject("run").get("state").getAsString());
      assertEquals("FAIL", Bundles.row(bundle, "pherf.fleet.agents")[1]);
      assertEquals(200, rest.get(run(test, runId, "report")).code);

      // The test remains open after cancellation, and a subsequent run completes using the same
      // agents.
      String next = createRun(rest, test);
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, next).get("state").getAsString());

      // Ending a test automatically cancels its active RUNNING run.
      String last = createRun(rest, test);
      simulation.runFor(6000);
      assertEquals("ENDED",
        rest.post("/v1/tests/" + test + "/end", "").json(200).get("state").getAsString());
      JsonObject ended = rest.get(run(test, last, "status")).json(200);
      assertEquals("CANCELLED", ended.get("state").getAsString());
      assertEquals("cancelled: the test ended", ended.get("reason").getAsString());
      simulation.runFor(Protocol.HOLD_MS);
      allIdle(rest, "a1", "a2", "a3");
    }
  }

  @Test
  public void resetReturnsAFaultedAgent() throws Exception {
    Simulation simulation = fleet("a1", "a2", "a3");
    try (RestClient rest = new RestClient(simulation)) {
      String test = createTest(rest, plan(3, 20));
      String runId = createRun(rest, test);
      simulation.runFor(6000);
      RunView.SlotView slot = simulation.run(runId).getSteps().get(0).getSlots().get(1);
      assertEquals("a2", slot.getAgent());
      assertEquals(RunView.SlotState.RUNNING, slot.getState());
      // Inject a telemetry frame from agent a2 after launch whose cumulative total indicates a
      // lost frame, following the same fault scenario as CountersTest.
      String steady = FakeWorkload.OPERATION + "|" + LatencyRecorders.STEADY;
      long total = slot.getCounters().get(steady);
      Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      histogram.recordValueWithCount(10, 4);
      Intervals batch =
        new Intervals(runId, 0,
          Collections.singletonList(new IntervalFrame(slot.getHeldSeq() + 1, FakeWorkload.OPERATION,
            LatencyRecorders.STEADY, 0, 1, total + 7, RecorderConfig.PINNED,
            HistogramBlob.encode(histogram))));
      batch.setAgent("a2");
      batch.setIncarnation(simulation.agentRuntime("a2").identity().getIncarnation());
      batch.setTrace("a2/telemetry/99");
      simulation.controller.telemetry(batch);
      simulation.runFor(Protocol.HOLD_MS);

      JsonObject failed = rest.get(run(test, runId, "status")).json(200);
      assertEquals("FAILED", failed.get("state").getAsString());
      String reason = failed.get("reason").getAsString();
      assertTrue(reason, reason.startsWith("agent a2: lost or duplicated telemetry: "));
      Map<String, JsonObject> agents = agents(rest);
      assertEquals("FAULTED", agents.get("a2").get("state").getAsString());
      assertEquals(reason, agents.get("a2").get("fault").getAsString());
      for (String id : new String[] { "a1", "a3" }) {
        assertEquals("IDLE", agents.get(id).get("state").getAsString());
        assertFalse(agents.get(id).has("fault"));
      }
      // Although the test remains open, the fleet lacks sufficient healthy capacity while the
      // faulted agent is unavailable.
      assertEquals("step 0 needs 3 agents but 2 are free; faulted: a2",
        rest.post(runs(test), "").error(409, "conflict"));

      JsonObject reset = rest.post("/v1/agents/a2/reset", "").json(200);
      assertEquals("IDLE", reset.get("state").getAsString());
      assertFalse(reset.has("fault"));
      assertEquals(reset.get("state"), rest.post("/v1/agents/a2/reset", "").json(200).get("state"));
      allIdle(rest, "a1", "a2", "a3");

      String next = createRun(rest, test);
      assertEquals("agent a2 has a slot of run " + next,
        rest.post("/v1/agents/a2/reset", "").error(409, "conflict"));
      assertEquals("COMPLETE", awaitEnd(simulation, rest, test, next).get("state").getAsString());
      assertEquals("no agent a9", rest.post("/v1/agents/a9/reset", "").error(404, "not_found"));

      // A failure occurring prior to launch is treated as a plan error and does not fault any
      // agent.
      Map<String, String> properties = new LinkedHashMap<>();
      properties.put("fake.prepareError.1", "cogen.walk s.xml querySet 1: no committed block");
      String bad = createTest(rest, plan(3, properties));
      String aborted = createRun(rest, bad);
      assertEquals("ABORTED", awaitEnd(simulation, rest, bad, aborted).get("state").getAsString());
      simulation.runFor(Protocol.HOLD_MS);
      allIdle(rest, "a1", "a2", "a3");
      assertNull(simulation.view("a2").getFault());
    }
  }
}
