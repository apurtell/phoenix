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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

/**
 * Verifies RQ-238, ensuring that every frame carries the agent's cumulative count for its key and
 * that the run view exposes the cumulative counters held by the controller for each slot. These
 * counters increase monotonically throughout execution and match the agent's final counts upon
 * completion. Any cumulative total that diverges from the controller's held count indicates a lost
 * or duplicated frame and causes the slot to fail.
 */
public class CountersTest {
  private static final String STEADY = FakeWorkload.OPERATION + "|" + LatencyRecorders.STEADY;
  private static final String WARMUP = FakeWorkload.OPERATION + "|" + LatencyRecorders.WARMUP;

  @Test
  public void countersRiseAndEqualTheFinalCounts() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "5");
    properties.put("fake.warmup", "2");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 2));
    Map<String, Map<String, Long>> last = new HashMap<>();
    Map<String, Integer> rises = new HashMap<>();
    RunView run;
    do {
      simulation.runFor(250);
      run = simulation.run(runId);
      for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
        Map<String, Long> before = last.getOrDefault(slot.getAgent(), Collections.emptyMap());
        Map<String, Long> now = slot.getCounters();
        for (Map.Entry<String, Long> key : before.entrySet()) {
          assertTrue(slot.getAgent() + " " + key, now.get(key.getKey()) >= key.getValue());
        }
        if (!now.equals(before) && slot.getState() == RunView.SlotState.RUNNING) {
          rises.merge(slot.getAgent(), 1, Integer::sum);
        }
        last.put(slot.getAgent(), now);
      }
      assertTrue(simulation.clock.millis() < Simulation.START_MS + 60000);
    } while (run.getState() == RunView.State.RUNNING);
    assertEquals(RunView.State.COMPLETE, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      assertTrue(slot.getAgent() + " " + rises, rises.getOrDefault(slot.getAgent(), 0) >= 4);
      FakeWorkload workload = simulation.workload(slot.getAgent());
      assertEquals(1, workload.reports.size());
      assertEquals(workload.reports.get(0).getCounts(), slot.getCounters());
      long samples = 0;
      for (long count : slot.getCounters().values()) {
        samples += count;
      }
      assertEquals(slot.getSamples(), samples);
      assertEquals(workload.emittedCount(), samples);
      assertEquals(2, slot.getCounters().size());
      assertEquals(1 + 2, (long) slot.getCounters().get(WARMUP));
      assertEquals(1 + 2 + 3 + 4 + 5, (long) slot.getCounters().get(STEADY));
    }
  }

  /**
   * Verifies that receiving a frame whose cumulative total does not equal the controller's held
   * count plus the frame's own sample count causes the slot to fail.
   */
  @Test
  public void wrongTotalFailsTheSlot() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "20");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 1));
    simulation.runFor(6000);
    RunView.SlotView slot = simulation.run(runId).getSteps().get(0).getSlots().get(0);
    long held = slot.getHeldSeq();
    assertTrue(held >= 2);
    long total = slot.getCounters().get(STEADY);
    Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
    histogram.recordValueWithCount(10, 4);
    // Simulate a frame preceded by a lost frame by reporting a cumulative total that exceeds the
    // controller's held count by 7 samples.
    Intervals batch = new Intervals(runId, 0,
      Collections
        .singletonList(new IntervalFrame(held + 1, FakeWorkload.OPERATION, LatencyRecorders.STEADY,
          0, 1, total + 7, RecorderConfig.PINNED, HistogramBlob.encode(histogram))));
    batch.setAgent("a1");
    batch.setIncarnation(simulation.agentRuntime("a1").identity().getIncarnation());
    batch.setTrace("a1/telemetry/99");
    Message reply = simulation.controller.telemetry(batch);
    // The agent remains registered in the fleet while the acknowledgment refuses to advance the
    // held sequence and cancellation terminates the active workload.
    assertEquals(held, ((Ack) reply).getHeldSeq());
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    slot = run.getSteps().get(0).getSlots().get(0);
    assertEquals(RunView.SlotState.FAILED, slot.getState());
    assertEquals(
      "agent a1: lost or duplicated telemetry: frame " + (held + 1) + " of " + STEADY
        + " has the total " + (total + 7) + ", but the controller holds " + (total + 4),
      slot.getError());
    assertEquals(total, (long) slot.getCounters().get(STEADY));
    assertEquals(held, slot.getHeldSeq());
    List<String> ids = simulation.agentIds();
    assertEquals(Collections.singletonList("a1"), ids);
    // Confirm that the fleet verdict identifies the offending frame within its telemetry row.
    String[] row = Bundles.row(simulation.bundle(runId), "pherf.fleet.telemetry");
    assertEquals("FAIL", row[1]);
    assertEquals(
      "step 0 slot 0 (agent a1): lost or duplicated telemetry: frame " + (held + 1) + " of "
        + STEADY + " has the total " + (total + 7) + ", but the controller holds " + (total + 4),
      row[4]);
  }
}
