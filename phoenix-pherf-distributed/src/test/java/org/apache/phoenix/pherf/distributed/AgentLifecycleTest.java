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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies RQ-216: agents remain resident across runs until an explicit shutdown command or a
 * signal terminates them.
 */
public class AgentLifecycleTest {
  private static final String REQUEST = InProcessNetwork.REQUEST;
  private static final String REPLY = InProcessNetwork.REPLY;

  private static String complete(Simulation simulation, int... agents) {
    String runId = simulation.controller.submit(Simulation.spec(agents));
    simulation.runFor(15000);
    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    return runId;
  }

  @Test
  public void twoRunsOneRegistration() {
    Simulation simulation = new Simulation();
    AgentRuntime agent = simulation.agent("a1");
    simulation.runFor(1000);
    AgentView before = simulation.view("a1");
    String first = complete(simulation, 1);
    assertEquals(AgentRuntime.State.IDLE, agent.state());
    String second = complete(simulation, 1);
    assertTrue(!first.equals(second));
    AgentView after = simulation.view("a1");
    assertEquals(1, simulation.count(REQUEST, "register"));
    assertEquals(before.getRegisteredMs(), after.getRegisteredMs());
    assertEquals(before.getIncarnation(), after.getIncarnation());
    assertEquals(2, simulation.workload("a1").started.size());
    assertEquals(AgentView.State.IDLE, after.getState());
    assertEquals(AgentRuntime.State.IDLE, agent.state());
  }

  @Test
  public void twoStepRunReusesTheAgent() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.runFor(1000);
    String runId = complete(simulation, 1, 1);
    RunView run = simulation.run(runId);
    assertEquals(2, run.getSteps().size());
    for (RunView.StepView step : run.getSteps()) {
      assertEquals(RunView.StepState.COMPLETE, step.getState());
      assertEquals("a1", step.getSlots().get(0).getAgent());
      assertEquals(3, step.getSlots().get(0).getHeldSeq());
    }
    assertEquals(1, simulation.count(REQUEST, "register"));
    assertEquals(2, simulation.count(REPLY, "release"));
    assertEquals(Arrays.asList(0, 1),
      Arrays.asList(simulation.workload("a1").started.get(0).getStep(),
        simulation.workload("a1").started.get(1).getStep()));
  }

  @Test
  public void shutdownCommandExitsZero() {
    Simulation simulation = new Simulation();
    AgentRuntime agent = simulation.agent("a1");
    AgentRuntime other = simulation.agent("a2");
    simulation.runFor(1500);
    assertEquals(Arrays.asList("a1", "a2"), simulation.agentIds());
    assertTrue(simulation.controller.shutdownAgent("a1"));
    // Because the held poll for a1 responds immediately, neither a reaper period nor a hold
    // duration passes.
    long now = simulation.clock.millis();
    simulation.network.drain();
    assertEquals(now, simulation.clock.millis());
    // One shutdown reply delivers the command, while the second responds to the goodbye request.
    assertEquals(2, simulation.count(REPLY, "shutdown"));
    assertEquals(1, simulation.count(REQUEST, "goodbye"));
    assertEquals(Collections.singletonList("a2"), simulation.agentIds());
    assertEquals(AgentRuntime.State.STOPPED, agent.state());
    assertEquals(Integer.valueOf(0), agent.exitStatus());
    assertNull(other.exitStatus());
    simulation.runFor(Protocol.DEADLINE_MS * 2);
    assertEquals(Collections.singletonList("a2"), simulation.agentIds());
  }

  @Test
  public void shutdownDuringRunCancelsWork() {
    Simulation simulation = new Simulation();
    AgentRuntime agent = simulation.agent("a1");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "100");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 1));
    simulation.runFor(3000);
    assertEquals(AgentRuntime.State.RUNNING, agent.state());
    simulation.controller.shutdownAgent("a1");
    simulation.runFor(Protocol.HOLD_MS);
    assertEquals(Collections.singletonList(runId), simulation.workload("a1").cancelled);
    assertEquals(AgentRuntime.State.STOPPED, agent.state());
    assertEquals(Integer.valueOf(0), agent.exitStatus());
    assertEquals(Collections.emptyList(), simulation.agentIds());
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a1 left the fleet", run.getReason());
  }

  /**
   * When a workload throws an exception, its assigned slot fails while the agent transitions back
   * to the idle state and remains available to execute the next run.
   */
  @Test
  public void workloadExceptionFailsTheSlotAndTheAgentGoesOn() {
    Simulation simulation = new Simulation();
    AgentRuntime agent = simulation.agent("a1");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.prepareThrows", "broken scenario");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 1));
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals("agent a1: prepare failed: java.lang.IllegalStateException: broken scenario",
      run.getReason());
    assertEquals(RunView.SlotState.FAILED, run.getSteps().get(0).getSlots().get(0).getState());
    assertEquals(AgentRuntime.State.IDLE, agent.state());
    assertEquals(AgentView.State.IDLE, simulation.view("a1").getState());
    complete(simulation, 1);
    assertEquals(1, simulation.count(REQUEST, "register"));
  }

  /**
   * Enforces two-phase completion on the agent side, ensuring that DONE is sent only after the ack
   * holds the last frame.
   */
  @Test
  public void doneWaitsForTheLastAck() {
    Simulation simulation = new Simulation();
    AgentRuntime agent = simulation.agent("a1");
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Simulation.spec(1));
    simulation.runFor(2500);
    simulation.network.freeze("a1", Protocol.TELEMETRY);
    simulation.runFor(5000);
    assertEquals(3, simulation.workload("a1").emitted.size());
    assertEquals(AgentRuntime.State.RUNNING, agent.state());
    assertEquals(RunView.State.RUNNING, simulation.run(runId).getState());
    for (InProcessNetwork.Event event : simulation.network.events()) {
      assertTrue(event.toString(), event.json == null || !event.json.contains("\"DONE\""));
    }
    simulation.network.thaw("a1", Protocol.TELEMETRY);
    simulation.runFor(3000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    assertEquals(AgentRuntime.State.IDLE, agent.state());
  }
}
