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

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.agent.AgentMain;
import org.apache.phoenix.pherf.distributed.controller.ControllerMain;
import org.junit.Test;

/**
 * Verifies RQ-216: delivering SIGTERM cleanly terminates both the agent process and the controller
 * process with exit status 0. In this single smoke test mandated by RQ-216, the two child JVMs
 * communicate over real HTTP while the test JVM itself opens no network connections. The RQ-225
 * constraint prohibiting sockets and sleeps applies to the protocol, state machine, and identity
 * test suites, which execute against the virtual clock and the in-process network.
 */
public class AgentProcessTest {
  private static final long TIMEOUT_MS = 60000;
  private static final String LISTENING = "agent channel listening on port ";

  @Test
  public void sigtermStopsAgentAndControllerWithZero() throws Exception {
    Map<String, String> controllerEnv = new HashMap<>();
    controllerEnv.put("PHERF_AGENT_PORT", "0");
    controllerEnv.put("PHERF_RUN_DIR", Simulation.runDir().getPath());
    try (ChildProcess controller =
      ChildProcess.start("controller", ControllerMain.class, controllerEnv)) {
      String line = controller.awaitLine(LISTENING, TIMEOUT_MS);
      int port =
        Integer.parseInt(line.substring(line.indexOf(LISTENING) + LISTENING.length()).trim());
      Map<String, String> agentEnv = new HashMap<>();
      agentEnv.put("PHERF_CONTROLLER", "http://127.0.0.1:" + port);
      agentEnv.put("PHERF_AGENT_ID", "smoke-1");
      agentEnv.put("PHERF_ZOOKEEPER", "smoke-zk");
      try (ChildProcess agent = ChildProcess.start("agent", AgentMain.class, agentEnv)) {
        agent.awaitLine("agent smoke-1 started", TIMEOUT_MS);
        agent.awaitLine("agent smoke-1 registered", TIMEOUT_MS);
        controller.awaitLine("agent smoke-1 registered", TIMEOUT_MS);
        agent.terminate();
        assertEquals(agent.output(), 0, agent.waitFor(TIMEOUT_MS));
        controller.awaitLine("agent smoke-1 said goodbye", TIMEOUT_MS);
      }
      controller.terminate();
      assertEquals(controller.output(), 0, controller.waitFor(TIMEOUT_MS));
    }
  }

  /**
   * Verifies that when PHERF_ZOOKEEPER is omitted, the agent detects an invalid configuration and
   * terminates with exit status 2.
   */
  @Test
  public void agentWithoutZookeeperExitsTwo() throws Exception {
    Map<String, String> agentEnv = new HashMap<>();
    agentEnv.put("PHERF_CONTROLLER", "http://127.0.0.1:1");
    agentEnv.put("PHERF_AGENT_ID", "smoke-2");
    try (ChildProcess agent = ChildProcess.start("agent", AgentMain.class, agentEnv)) {
      assertEquals(agent.output(), 2, agent.waitFor(TIMEOUT_MS));
      agent.awaitLine("PHERF_ZOOKEEPER is not set", TIMEOUT_MS);
    }
  }
}
