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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.controller.ControllerMain;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.controller.TestView;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assign;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Register;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.apache.phoenix.pherf.distributed.transport.http.RestClient;
import org.junit.Test;

/**
 * Verifies RQ-241 and RQ-243 using real ControllerMain processes. The REST API and the agent
 * channel operate as two distinct listeners backed by a single shared controller state, and each
 * listener serves only its own paths. A restarted process serves the frozen report of a run that a
 * previous process completed, while a process terminated by SIGKILL leaves its in-flight run in the
 * FAILED state with its lock released and free.
 */
public class ListenerTest {
  private static final long TIMEOUT_MS = 60000;
  private static final String REST = "REST API listening on port ";
  private static final String AGENT = "agent channel listening on port ";

  private static int port(ChildProcess controller, String prefix) throws InterruptedException {
    String line = controller.awaitLine(prefix, TIMEOUT_MS);
    return Integer.parseInt(line.substring(line.indexOf(prefix) + prefix.length()).trim());
  }

  private static Map<String, String> env(File runDir, boolean ephemeral) {
    Map<String, String> env = new HashMap<>();
    env.put("PHERF_RUN_DIR", runDir.getPath());
    if (ephemeral) {
      env.put("PHERF_REST_PORT", "0");
      env.put("PHERF_AGENT_PORT", "0");
    }
    return env;
  }

  private static String plan(int agents, long intervals) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", Long.toString(intervals));
    return Plans.plan(7).step(Plans.step(agents)
      .scenario("scenario.xml", "<datamodel name=\"fake\"/>").properties(properties)).json();
  }

  /** Populates an agent control request with the specified agent id and incarnation i-id. */
  private static <T extends AgentMessage> T as(String id, T request) {
    request.setAgent(id);
    request.setIncarnation("i-" + id);
    request.setTrace(id + "/control/1");
    return request;
  }

  /** Sends a single raw agent control request in the format transmitted by AgentRuntime. */
  private static RestClient.Response control(int port, Message request) throws IOException {
    return RestClient.call("http://127.0.0.1:" + port, "POST", Protocol.CONTROL_PATH,
      Codec.encode(request));
  }

  private static String base(int port) {
    return "http://127.0.0.1:" + port;
  }

  @Test
  public void restartedProcessServesTheFrozenReport() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    TestView test = simulation.tests.create(plan(2, 3));
    String runId = simulation.tests.submit(test.getId()).getRunId();
    while (simulation.run(runId).getState() == RunView.State.RUNNING) {
      simulation.runFor(250);
    }
    RunView ended = simulation.run(runId);
    assertEquals(RunView.State.COMPLETE, ended.getState());
    simulation.controller.close();
    File bundle = simulation.bundle(runId);

    try (ChildProcess controller =
      ChildProcess.start("controller", ControllerMain.class, env(simulation.runDir, true))) {
      String rest = base(port(controller, REST));
      String prefix = "/v1/tests/" + test.getId() + "/runs/" + runId;
      RestClient.Response report = RestClient.call(rest, "GET", prefix + "/report", null);
      assertEquals(report.text(), 200, report.code);
      assertArrayEquals(
        Files.readAllBytes(new File(Bundles.results(bundle), "LATENCY_run.json").toPath()),
        report.body);
      assertEquals(new Gson().toJsonTree(ended),
        RestClient.call(rest, "GET", prefix + "/status", null).json(200));
      assertArrayEquals(Files.readAllBytes(new File(bundle, RunBundle.MANIFEST).toPath()),
        RestClient.call(rest, "GET", prefix + "/bundle/" + RunBundle.MANIFEST, null).body);
      controller.terminate();
      assertEquals(controller.output(), 0, controller.waitFor(TIMEOUT_MS));
    }
  }

  @Test
  public void killedControllerFailsTheRunInFlight() throws Exception {
    File runDir = Simulation.runDir();
    String test;
    String runId;
    try (ChildProcess first =
      ChildProcess.start("controller-1", ControllerMain.class, env(runDir, true))) {
      String rest = base(port(first, REST));
      int agentPort = port(first, AGENT);
      assertEquals("registered",
        Codec.decode(control(agentPort, as("raw-1", new Register())).text()).type().wire());
      test =
        RestClient.call(rest, "POST", "/v1/tests", plan(1, 100)).json(201).get("id").getAsString();
      runId = RestClient.call(rest, "POST", "/v1/tests/" + test + "/runs", "").json(201)
        .get("runId").getAsString();
      first.kill();
      assertNotEquals(0, first.waitFor(TIMEOUT_MS));
    }
    try (ChildProcess second =
      ChildProcess.start("controller-2", ControllerMain.class, env(runDir, true))) {
      // The second controller process acquires and holds the lock formerly held by the killed
      // process.
      String rest = base(port(second, REST));
      JsonObject status = RestClient
        .call(rest, "GET", "/v1/tests/" + test + "/runs/" + runId + "/status", null).json(200);
      assertEquals("FAILED", status.get("state").getAsString());
      assertEquals("the controller stopped during the run", status.get("reason").getAsString());
      // The test remains in the OPEN state, while the in-memory agent table is not restored across
      // restarts.
      assertEquals("step 0 needs 1 agents but 0 are free",
        RestClient.call(rest, "POST", "/v1/tests/" + test + "/runs", "").error(409, "conflict"));
      // A third controller instance targeting the same run directory blocks waiting for the lock
      // while the second controller remains active, modeling how a replacement Kubernetes pod
      // overlaps with the pod it replaces, and proceeds with startup once the second controller
      // terminates.
      try (ChildProcess third =
        ChildProcess.start("controller-3", ControllerMain.class, env(runDir, true))) {
        third.awaitLine("waiting up to", TIMEOUT_MS);
        second.terminate();
        assertEquals(second.output(), 0, second.waitFor(TIMEOUT_MS));
        String next = base(port(third, REST));
        assertEquals("FAILED",
          RestClient.call(next, "GET", "/v1/tests/" + test + "/runs/" + runId + "/status", null)
            .json(200).get("state").getAsString());
        int waits = 0;
        for (String line : third.output().split("\n")) {
          waits += line.contains("waiting up to") ? 1 : 0;
        }
        assertEquals(third.output(), 1, waits);
        third.terminate();
        assertEquals(third.output(), 0, third.waitFor(TIMEOUT_MS));
      }
    }
  }

  @Test
  public void defaultPortsServeSeparateListeners() throws Exception {
    for (int port : new int[] { Protocol.REST_PORT, Protocol.AGENT_PORT }) {
      try (ServerSocket probe = new ServerSocket(port)) {
        probe.getLocalPort();
      } catch (IOException e) {
        assumeTrue("port " + port + " is in use: " + e, false);
      }
    }
    try (ChildProcess controller =
      ChildProcess.start("controller", ControllerMain.class, env(Simulation.runDir(), false))) {
      assertEquals(Protocol.REST_PORT, port(controller, REST));
      assertEquals(Protocol.AGENT_PORT, port(controller, AGENT));
      String rest = base(Protocol.REST_PORT);
      String agent = base(Protocol.AGENT_PORT);

      // Verify that REST requests succeed on port 8780 and receive the default JDK 404 response on
      // port 8790.
      assertEquals(0,
        RestClient.call(rest, "GET", "/v1/agents", null).json(200).getAsJsonArray("agents").size());
      RestClient.Response wrong = RestClient.call(agent, "GET", "/v1/agents", null);
      assertEquals(404, wrong.code);
      assertNotEquals("application/json; charset=utf-8", wrong.contentType);

      // Verify that agent channel requests succeed on port 8790 and receive a JSON 404 response on
      // port 8780.
      assertEquals("registered", Codec
        .decode(control(Protocol.AGENT_PORT, as("raw-1", new Register())).text()).type().wire());
      control(Protocol.REST_PORT, as("raw-2", new Register())).error(404, "not_found");

      // Verify that both listeners observe and mutate the same shared controller state.
      JsonElement agents =
        RestClient.call(rest, "GET", "/v1/agents", null).json(200).getAsJsonArray("agents");
      assertEquals(1, agents.getAsJsonArray().size());
      JsonObject registered = agents.getAsJsonArray().get(0).getAsJsonObject();
      assertEquals("raw-1", registered.get("id").getAsString());
      assertEquals("IDLE", registered.get("state").getAsString());
      String test =
        RestClient.call(rest, "POST", "/v1/tests", plan(1, 3)).json(201).get("id").getAsString();
      String runId = RestClient.call(rest, "POST", "/v1/tests/" + test + "/runs", "").json(201)
        .get("runId").getAsString();
      Message reply = Codec.decode(
        control(Protocol.AGENT_PORT, as("raw-1", new Heartbeat(1, Status.IDLE, null))).text());
      assertEquals("assign", reply.type().wire());
      assertEquals(runId, ((Assign) reply).getAssignment().getRunId());
      assertEquals("ASSIGNED",
        JsonParser
          .parseString(RestClient
            .call(rest, "GET", "/v1/tests/" + test + "/runs/" + runId + "/status", null).text())
          .getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject()
          .getAsJsonArray("slots").get(0).getAsJsonObject().get("state").getAsString());

      controller.terminate();
      assertEquals(controller.output(), 0, controller.waitFor(TIMEOUT_MS));
    }
  }
}
