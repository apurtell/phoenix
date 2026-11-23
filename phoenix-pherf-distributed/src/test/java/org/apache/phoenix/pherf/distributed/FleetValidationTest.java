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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.agent.Children;
import org.apache.phoenix.pherf.distributed.agent.PherfWorkload;
import org.apache.phoenix.pherf.distributed.agent.RecordingConnectionProvider;
import org.apache.phoenix.pherf.distributed.agent.SyncLauncher;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirement RQ-229: every agent executes Pherf's built-in offline validator against its
 * assigned workload slice before generating any traffic, and a single validation finding on any
 * agent aborts the entire run. Like AgentProcessTest, this test spawns real -validate child
 * processes as a documented exception to the standard unit-test rule against spawning external
 * processes.
 */
public class FleetValidationTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private SyncLauncher launcher;
  private File[] work;

  /** Creates a simulated fleet of agents configured to launch real -validate child processes. */
  private Simulation fleet(int agents) throws Exception {
    Simulation simulation = new Simulation();
    launcher = new SyncLauncher(Children.launcher("fleet", folder.newFolder("overlay")));
    work = new File[agents];
    for (int i = 0; i < agents; i++) {
      work[i] = folder.newFolder("a" + (i + 1));
      simulation.agent("a" + (i + 1),
        new PherfWorkload(work[i], "fleet-zk", Children.shipped(), launcher, simulation.clock));
    }
    simulation.runFor(1000);
    return simulation;
  }

  private static Plans.Step closedLoop(int agents) throws Exception {
    return Plans.step(agents)
      .scenario("closed_loop_scenario.xml", Plans.resource("/fleet/closed_loop_scenario.xml"))
      .args("-q", "-disableSchemaApply");
  }

  /**
   * Asserts that an aborted run never reaches the start phase, launches only -validate child
   * processes, and performs no JDBC operations.
   */
  private void noTraffic(Simulation simulation) {
    assertEquals(0, simulation.count(InProcessNetwork.REPLY, "start"));
    for (List<String> command : launcher.commands()) {
      assertTrue(command.toString(), command.contains("-validate"));
    }
    for (File dir : work) {
      assertFalse(new File(dir, RecordingConnectionProvider.LOG).exists());
    }
  }

  @Test
  public void oneInvalidSliceAbortsTheRun() throws Exception {
    Simulation simulation = fleet(2);
    // With a 999:1 weight distribution across 1000 rows, slot 1 receives only 1 row, leaving
    // worker 1 of the 2 closed-loop readers with no rows to process and triggering a cogen.walk
    // finding from Pherf's validator on that slice alone.
    String runId = simulation.controller
      .submit(Plans.plan(8070).step(closedLoop(2).weights(999, 1).rows(1000)).spec());
    simulation.runFor(5000);

    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.ABORTED, run.getState());
    assertTrue(run.getReason(), run.getReason().startsWith("agent a2: cogen.walk "));
    assertTrue(run.getReason(), run.getReason().contains("worker 1 of 2"));
    List<RunView.SlotView> slots = run.getSteps().get(0).getSlots();
    assertEquals(RunView.SlotState.CANCELLED, slots.get(0).getState());
    assertEquals(RunView.SlotState.FAILED, slots.get(1).getState());
    assertEquals(Long.valueOf(999), slots.get(0).getShare().getRows());
    assertEquals(Long.valueOf(1), slots.get(1).getShare().getRows());
    assertEquals(2, launcher.commands().size());
    noTraffic(simulation);
    for (String id : new String[] { "a1", "a2" }) {
      assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime(id).state());
      assertEquals(AgentView.State.IDLE, simulation.view(id).getState());
    }
    // Both agents return to an idle state and are immediately available to be bound together by
    // the next submitted run.
    simulation.controller
      .submit(Plans.plan(1).step(Plans.step(2).scenario("s.xml", "<s/>")).spec());
    for (String id : new String[] { "a1", "a2" }) {
      assertEquals(AgentView.State.ASSIGNED, simulation.view(id).getState());
    }
  }

  /**
   * Verifies that validation evaluates each slice using the run's allocated share options rather
   * than the scenario document defaults. Splitting 200 rows and 8 threads across two slots assigns
   * each slot 100 rows (2 committed blocks of 50) and 4 readers, leaving reader 3 of 4 with no
   * block to scan, even though the 2 readers defined in the document would pass.
   */
  @Test
  public void theThreadShareIsValidated() throws Exception {
    Simulation simulation = fleet(2);
    String runId = simulation.controller
      .submit(Plans.plan(8070).step(closedLoop(2).rows(200).threads(8)).spec());
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.ABORTED, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      assertEquals(RunView.SlotState.FAILED, slot.getState());
      assertTrue(slot.getError(), slot.getError().contains("cogen.walk "));
      assertTrue(slot.getError(),
        slot.getError().contains("SCAN: worker 3 of 4 has no committed block"));
    }
    noTraffic(simulation);
  }

  /**
   * Verifies that specifying a target rate on a closed-loop scenario is reported as a pre-flight
   * validation finding rather than failing during run execution.
   */
  @Test
  public void aRateOnAClosedLoopIsAFinding() throws Exception {
    Simulation simulation = fleet(1);
    String runId =
      simulation.controller.submit(Plans.plan(8070).step(closedLoop(1).rows(1000).rate(5)).spec());
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.ABORTED, run.getState());
    assertTrue(run.getReason(), run.getReason().startsWith("agent a1: openloop.rate "));
    assertTrue(run.getReason(), run.getReason().contains("-targetRate changes only open loop"));
    noTraffic(simulation);
  }
}
