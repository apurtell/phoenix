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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies the run failure propagation rules: a single failing slot causes the entire run to fail.
 * If the failure occurs before any slot starts, the run transitions to the ABORTED state;
 * otherwise, it transitions to the FAILED state. All remaining participating agents receive either
 * a cancel or a release command, ensuring that every agent returns to an idle and free state.
 */
public class FailureRuleTest {
  private static final String[] AGENTS = { "a1", "a2", "a3" };

  private static Simulation fleet(String... ids) {
    Simulation simulation = new Simulation();
    for (String id : ids) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    return simulation;
  }

  /**
   * Collects the sequence of control reply types delivered to a single agent in chronological
   * order. Consecutive identical commands repeated when a hold expires are collapsed into a single
   * entry because the control stream operates as a level-triggered channel.
   */
  private static List<String> replies(Simulation simulation, String agent) throws Exception {
    List<String> types = new ArrayList<>();
    for (InProcessNetwork.Event event : simulation.network.events()) {
      if (
        event.agent.equals(agent) && event.kind.equals(InProcessNetwork.REPLY)
          && event.stream.equals(Protocol.CONTROL)
      ) {
        Message reply = Codec.decode(event.json);
        String type = reply.type().wire();
        if (
          !type.equals("wait") && (types.isEmpty() || !types.get(types.size() - 1).equals(type))
        ) {
          types.add(type);
        }
      }
    }
    return types;
  }

  private static void allIdleAndFree(Simulation simulation, String... ids) {
    for (String id : ids) {
      assertEquals(id, AgentView.State.IDLE, simulation.view(id).getState());
    }
    String next = simulation.controller.submit(Simulation.spec(ids.length));
    simulation.runFor(15000);
    assertEquals(RunView.State.COMPLETE, simulation.run(next).getState());
  }

  @Test
  public void prepareErrorAborts() {
    Simulation simulation = fleet(AGENTS);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.prepareError.2", "cogen.walk s.xml querySet 1: no committed block");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 3, 3));
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    // During preparation, slot 2 of step 0 also validates slot 2 of step 1, which inherits the
    // same step properties.
    assertEquals("agent a3: cogen.walk s.xml querySet 1: no committed block; step 1 slot 2:"
      + " cogen.walk s.xml querySet 1: no committed block", run.getReason());
    assertEquals(Arrays.asList(RunView.SlotState.CANCELLED, RunView.SlotState.CANCELLED,
      RunView.SlotState.FAILED), states(run));
    assertEquals(RunView.StepState.PENDING, run.getSteps().get(1).getState());
    assertEquals(0, simulation.count(InProcessNetwork.REPLY, "start"));
    for (String id : AGENTS) {
      assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime(id).state());
      assertTrue(simulation.workload(id).started.isEmpty());
    }
    assertEquals(Arrays.asList(runId, runId), Arrays.asList(
      simulation.workload("a1").cancelled.get(0), simulation.workload("a2").cancelled.get(0)));
    allIdleAndFree(simulation, AGENTS);
  }

  /**
   * Verifies that step 0 validates the slices of every subsequent step during preparation, causing
   * an invalid slice in step 1 to abort the run before any execution starts. Validation for slot k
   * of step 1 is routed to slot k mod 2 of step 0.
   */
  @Test
  public void laterStepSliceAbortsBeforeTheFirstStart() throws Exception {
    Simulation simulation = fleet(AGENTS);
    String runId =
      simulation.controller
        .submit(
          Plans.plan(7).step(Plans.step(2).scenario("scenario.xml", "<datamodel name=\"fake\"/>"))
            .step(Plans.step(3).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
              .property("fake.prepareError.2", "cogen.walk s.xml querySet 1: worker 1 of 2"))
            .spec());
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals("agent a1: step 1 slot 2: cogen.walk s.xml querySet 1: worker 1 of 2",
      run.getReason());
    assertEquals(Arrays.asList(RunView.SlotState.FAILED, RunView.SlotState.CANCELLED), states(run));
    assertEquals(RunView.StepState.PENDING, run.getSteps().get(1).getState());
    assertEquals(0, simulation.count(InProcessNetwork.REPLY, "start"));
    assertEquals(Arrays.asList("1.0", "1.2"),
      validated(simulation.workload("a1").prepared.get(0).getValidations()));
    assertEquals(Collections.singletonList("1.1"),
      validated(simulation.workload("a2").prepared.get(0).getValidations()));
    assertTrue(simulation.workload("a3").prepared.isEmpty());
    for (String id : AGENTS) {
      assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime(id).state());
      assertTrue(simulation.workload(id).started.isEmpty());
    }
    allIdleAndFree(simulation, AGENTS);
  }

  /** Formats the step and slot identifiers for each validated assignment slice. */
  private static List<String> validated(List<Assignment> validations) {
    List<String> slices = new ArrayList<>();
    for (Assignment slice : validations) {
      slices.add(slice.getStep() + "." + slice.getIdentity().getSlot());
    }
    return slices;
  }

  /**
   * Verifies that every failed slot retains its own distinct error message while the overall run
   * failure reason records the first error encountered.
   */
  @Test
  public void everyFailedSlotKeepsItsError() {
    Simulation simulation = fleet("a1", "a2");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.prepareError.0", "error of slot 0");
    properties.put("fake.prepareError.1", "error of slot 1");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 2));
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals(Arrays.asList(RunView.SlotState.FAILED, RunView.SlotState.FAILED), states(run));
    List<String> errors = new ArrayList<>();
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      errors.add(slot.getError());
    }
    assertEquals(Arrays.asList("agent a1: error of slot 0", "agent a2: error of slot 1"), errors);
    assertTrue(run.getReason(), errors.contains(run.getReason()));
    allIdleAndFree(simulation, "a1", "a2");
  }

  /**
   * Verifies that when a slot fails after execution has started, any slots still in the RUNNING
   * state receive a cancel command while slots already in the DONE state receive a release command.
   */
  @Test
  public void startFailureFailsTheRun() throws Exception {
    Simulation simulation = fleet(AGENTS);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals.0", "1");
    properties.put("fake.intervals.1", "100");
    properties.put("fake.fail.1", "4");
    properties.put("fake.intervals.2", "100");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 3));
    simulation.runFor(2500);
    RunView running = simulation.run(runId);
    assertEquals(
      Arrays.asList(RunView.SlotState.DONE, RunView.SlotState.RUNNING, RunView.SlotState.RUNNING),
      states(running));
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a2: fake failure after 4 intervals", run.getReason());
    assertEquals(
      Arrays.asList(RunView.SlotState.DONE, RunView.SlotState.FAILED, RunView.SlotState.CANCELLED),
      states(run));
    assertEquals(Arrays.asList("registered", "assign", "start", "release"),
      replies(simulation, "a1"));
    assertEquals(Arrays.asList("registered", "assign", "start", "release"),
      replies(simulation, "a2"));
    assertEquals(Arrays.asList("registered", "assign", "start", "cancel"),
      replies(simulation, "a3"));
    assertEquals(Collections.singletonList(runId), simulation.workload("a3").cancelled);
    for (String id : AGENTS) {
      assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime(id).state());
    }
    allIdleAndFree(simulation, AGENTS);
  }

  private static List<RunView.SlotState> states(RunView run) {
    List<RunView.SlotState> states = new ArrayList<>();
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      states.add(slot.getState());
    }
    return states;
  }

  @Test
  public void evictionBeforeTheStartAborts() {
    Simulation simulation = fleet("a1", "a2");
    simulation.network.freeze("a2");
    String runId = simulation.controller.submit(Simulation.spec(2));
    simulation.runFor(Protocol.DEADLINE_MS + 3 * Protocol.REAP_PERIOD_MS);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals("agent a2 missed its heartbeat deadline", run.getReason());
    assertEquals(0, simulation.count(InProcessNetwork.REPLY, "start"));
    assertEquals(Collections.singletonList(runId), simulation.workload("a1").cancelled);
    assertTrue(simulation.workload("a1").started.isEmpty());
    assertEquals(AgentView.State.IDLE, simulation.view("a1").getState());
  }

  @Test
  public void evictionAfterTheStartFails() {
    Simulation simulation = fleet("a1", "a2");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "100");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 2));
    simulation.runFor(3000);
    simulation.network.freeze("a2");
    simulation.runFor(Protocol.DEADLINE_MS + 3 * Protocol.REAP_PERIOD_MS);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a2 missed its heartbeat deadline", run.getReason());
    assertEquals(Collections.singletonList(runId), simulation.workload("a1").cancelled);
    assertEquals(AgentView.State.IDLE, simulation.view("a1").getState());
    // Because the network-partitioned agent receives no controller traffic for longer than the
    // heartbeat deadline, it autonomously terminates its own active work.
    assertEquals(Collections.singletonList(runId), simulation.workload("a2").cancelled);
    assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime("a2").state());
  }
}
