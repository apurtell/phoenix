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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.RunSpec;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Share;
import org.junit.Test;

/**
 * Verifies RQ-230 and RQ-226 by testing that the controller binds idle agents to workload slots and
 * assigns deterministic data-plane identities.
 */
public class AssignmentTest {
  private static final String[] AGENTS = { "a1", "a2", "a3", "a4", "a5" };

  private static String plan(long seed) {
    return Plans.plan(seed)
      .step(Plans.step(3).weights(1, 2, 1)
        .scenario("s.xml", "<c seed=\"${pherf.slot.seed}\" offset=\"${pherf.slot.offset}\"/>")
        .rate(101).threads(10).rows(1001).durationMs(2000))
      .json();
  }

  private static Simulation fleet() {
    Simulation simulation = new Simulation();
    for (String id : AGENTS) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    return simulation;
  }

  /** Formats the (slot, seed, offset, prefix) identity tuple for each slot in step 0. */
  private static List<String> identities(RunView run) {
    List<String> identities = new ArrayList<>();
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      DataPlaneIdentity identity = slot.getIdentity();
      identities.add(identity.getSlot() + " " + identity.getSeed() + " " + identity.getOffset()
        + " " + identity.getPrefix());
    }
    return identities;
  }

  @Test
  public void threeOfFiveIdleAgentsAreAssignedInIdOrder() {
    Simulation simulation = fleet();
    String runId = simulation.controller.submit(RunSpec.parse(plan(8070)));
    List<AgentView.State> states = new ArrayList<>();
    for (String id : AGENTS) {
      states.add(simulation.view(id).getState());
    }
    assertEquals(Arrays.asList(AgentView.State.ASSIGNED, AgentView.State.ASSIGNED,
      AgentView.State.ASSIGNED, AgentView.State.IDLE, AgentView.State.IDLE), states);
    RunView run = simulation.run(runId);
    List<String> agents = new ArrayList<>();
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      agents.add(slot.getSlot() + "=" + slot.getAgent());
    }
    assertEquals(Arrays.asList("0=a1", "1=a2", "2=a3"), agents);
    simulation.runFor(10000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    for (String idle : new String[] { "a4", "a5" }) {
      assertEquals(0, simulation.workload(idle).prepared.size());
    }
  }

  @Test
  public void sharesInTheAssignmentsSumToTheStepTotals() {
    Simulation simulation = fleet();
    String runId = simulation.controller.submit(RunSpec.parse(plan(8070)));
    simulation.runFor(10000);
    long rate = 0;
    long threads = 0;
    long rows = 0;
    long nextOffset = 0;
    List<Share> shares = new ArrayList<>();
    for (int slot = 0; slot < 3; slot++) {
      Assignment assignment = simulation.workload(AGENTS[slot]).prepared.get(0);
      Share share = assignment.getShare();
      shares.add(share);
      assertEquals(Long.valueOf(2000), share.getDurationMs());
      assertEquals(nextOffset, assignment.getIdentity().getOffset());
      nextOffset += share.getRows();
      rate += share.getRate();
      threads += share.getThreads();
      rows += share.getRows();
      RunView.SlotView view = simulation.run(runId).getSteps().get(0).getSlots().get(slot);
      assertEquals(share, view.getShare());
      assertEquals(assignment.getIdentity(), view.getIdentity());
    }
    assertEquals(101, rate);
    assertEquals(10, threads);
    assertEquals(1001, rows);
    // Dividing 101 across a 1:2:1 weight ratio yields 25.25, 50.5, and 25.25, so the remainder is
    // allocated to the slot with the largest fractional part.
    assertEquals(Arrays.asList(new Share(2000L, 3L, 25L, 250L), new Share(2000L, 5L, 51L, 501L),
      new Share(2000L, 2L, 25L, 250L)), shares);
  }

  @Test
  public void theSamePlanGivesTheSameIdentities() {
    Simulation simulation = fleet();
    String first = simulation.controller.submit(RunSpec.parse(plan(8070)));
    simulation.runFor(10000);
    assertEquals(RunView.State.COMPLETE, simulation.run(first).getState());
    String second = simulation.controller.submit(RunSpec.parse(plan(8070)));
    simulation.runFor(10000);
    assertEquals(RunView.State.COMPLETE, simulation.run(second).getState());
    assertNotEquals(first, second);
    List<String> identities = identities(simulation.run(first));
    assertEquals(identities, identities(simulation.run(second)));
    assertEquals(Arrays.asList("p000", "p001", "p002"),
      Arrays.asList(identities.get(0).split(" ")[3], identities.get(1).split(" ")[3],
        identities.get(2).split(" ")[3]));

    // Submitting a plan with a different seed changes only the derived per-slot seeds.
    String other = simulation.controller.submit(RunSpec.parse(plan(8071)));
    simulation.runFor(10000);
    List<String> changed = identities(simulation.run(other));
    for (int slot = 0; slot < 3; slot++) {
      String[] was = identities.get(slot).split(" ");
      String[] now = changed.get(slot).split(" ");
      assertEquals(was[0], now[0]);
      assertNotEquals(was[1], now[1]);
      assertEquals(was[2], now[2]);
      assertEquals(was[3], now[3]);
    }
  }
}
