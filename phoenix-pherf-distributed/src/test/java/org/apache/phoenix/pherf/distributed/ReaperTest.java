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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Validates requirement RQ-217 by verifying that the controller reaper mechanism automatically
 * evicts any registered agent that fails to communicate before its heartbeat deadline expires.
 */
public class ReaperTest {

  private static Map<String, String> longRun() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "100");
    return properties;
  }

  @Test
  public void silentAgentIsEvicted() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(2500);
    simulation.network.freeze("a1");
    simulation.runFor(500);
    long lastSeen = simulation.view("a1").getLastSeenMs();
    assertTrue(lastSeen <= simulation.clock.millis());
    simulation.runTo(lastSeen + Protocol.DEADLINE_MS);
    assertEquals(Arrays.asList("a1", "a2"), simulation.agentIds());
    assertEquals(lastSeen, simulation.view("a1").getLastSeenMs());
    simulation.runFor(Protocol.REAP_PERIOD_MS);
    assertEquals(Collections.singletonList("a2"), simulation.agentIds());
    assertTrue(
      simulation.clock.millis() - simulation.view("a2").getLastSeenMs() <= Protocol.HOLD_MS);
  }

  @Test
  public void evictionFailsItsRunAndFreesTheOthers() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    AgentRuntime a2 = simulation.agent("a2");
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Simulation.spec(7, longRun(), 2));
    simulation.runFor(3000);
    assertEquals(AgentRuntime.State.RUNNING, a2.state());
    simulation.network.freeze("a1");
    simulation.runFor(Protocol.DEADLINE_MS + 5 * Protocol.REAP_PERIOD_MS);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a1 missed its heartbeat deadline", run.getReason());
    assertEquals(Collections.singletonList("a2"), simulation.agentIds());
    assertEquals(Collections.singletonList(runId), simulation.workload("a2").cancelled);
    assertEquals(AgentRuntime.State.IDLE, a2.state());
    assertEquals(AgentView.State.IDLE, simulation.view("a2").getState());
    // Verify that the freed agent returns to service and executes the next submitted run.
    String next = simulation.controller.submit(Simulation.spec(1));
    simulation.runFor(10000);
    assertEquals(RunView.State.COMPLETE, simulation.run(next).getState());
  }

  @Test
  public void evictedAgentRegistersAgain() {
    Simulation simulation = new Simulation();
    AgentRuntime a1 = simulation.agent("a1");
    simulation.runFor(1000);
    String incarnation = simulation.view("a1").getIncarnation();
    long registered = simulation.view("a1").getRegisteredMs();
    String runId = simulation.controller.submit(Simulation.spec(7, longRun(), 1));
    simulation.runFor(3000);
    simulation.network.freeze("a1");
    simulation.runFor(Protocol.DEADLINE_MS + 5 * Protocol.REAP_PERIOD_MS);
    assertNull(simulation.view("a1"));
    assertEquals(RunView.State.FAILED, simulation.run(runId).getState());
    simulation.network.thaw("a1");
    simulation.runFor(5000);
    AgentView view = simulation.view("a1");
    assertEquals(incarnation, view.getIncarnation());
    assertNotEquals(registered, view.getRegisteredMs());
    assertEquals(1, simulation.count("reply", "reregister"));
    assertEquals(2, simulation.count("request", "register"));
    assertEquals(Collections.singletonList(runId), simulation.workload("a1").cancelled);
    assertEquals(AgentRuntime.State.IDLE, a1.state());
    assertEquals(AgentView.State.IDLE, view.getState());
  }

  /**
   * Verifies the control stream keepalive mechanism, in which the held poll acts as the keepalive
   * signal for the control stream. If a single reply to a held poll is lost, the request times out
   * after {@link Protocol#IO_TIMEOUT_MS} and the subsequent poll reaches the controller before the
   * deadline on either side expires, ensuring that the idle agent remains registered.
   */
  @Test
  public void lostHeldPollReplyDoesNotEvict() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.runFor(3000);
    String incarnation = simulation.view("a1").getIncarnation();
    boolean[] lost = new boolean[1];
    simulation.network.policy((agent, stream, direction, message) -> {
      if (
        direction.equals(InProcessNetwork.REPLY) && message.type() == MessageType.WAIT && !lost[0]
      ) {
        lost[0] = true;
        return InProcessNetwork.Policy.DROP;
      }
      return 0;
    });
    simulation.runFor(3 * Protocol.DEADLINE_MS);
    assertEquals(1, simulation.count(InProcessNetwork.DROP, "wait"));
    assertEquals(1, simulation.count(InProcessNetwork.FAIL, "heartbeat"));
    assertEquals(incarnation, simulation.view("a1").getIncarnation());
    assertEquals(1, simulation.count("request", "register"));
    assertEquals(0, simulation.count("reply", "reregister"));
  }
}
