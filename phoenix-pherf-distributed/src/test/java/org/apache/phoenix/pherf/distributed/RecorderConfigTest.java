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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

/**
 * Verifies requirement RQ-234: the controller retains an interval frame only when both its header
 * and its underlying histogram conform to the pinned recorder configuration of 1 us to 600 s with 3
 * digits of precision. Receiving a frame with any other configuration evicts the originating agent
 * as misconfigured, transitions the run to a failed state, and leaves the aggregated fleet
 * histograms unchanged.
 */
public class RecorderConfigTest {
  private static final long SEED = 234;
  private static final int SAMPLES = 50;

  private static Simulation fleet() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    return simulation;
  }

  /**
   * Submits a run in which agent a1 emits pinned frames every second while agent a2 emits its first
   * frame after slot1IntervalMs.
   */
  private static String submit(Simulation simulation, String recorder, long slot1IntervalMs) {
    Plans.Step step = Plans.step(2).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
      .property("fake.seed", Long.toString(SEED))
      .property("fake.samples", Integer.toString(SAMPLES)).property("fake.intervals", "10")
      .property("fake.intervalMs.1", Long.toString(slot1IntervalMs));
    if (recorder != null) {
      step.property("fake.recorder.1", recorder);
    }
    return simulation.controller.submit(Plans.plan(1).step(step).spec());
  }

  /**
   * Reconstructs a reference histogram containing the sample values from all frames of slot 0
   * currently held by the controller.
   */
  private static Histogram heldOfSlot0(RunView run) {
    Histogram reference = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
    long held = run.getSteps().get(0).getSlots().get(0).getHeldSeq();
    for (long n = 1; n <= held; n++) {
      for (long value : FakeWorkload.values(SEED, 0, LatencyRecorders.STEADY, n, SAMPLES)) {
        reference.recordValue(value);
      }
    }
    return reference;
  }

  private static void assertMisconfigured(Simulation simulation, String runId, String parameter)
    throws Exception {
    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.FAILED, run.getState());
    RunView.SlotView slot = run.getSteps().get(0).getSlots().get(1);
    assertEquals(RunView.SlotState.FAILED, slot.getState());
    assertTrue(slot.getError(), slot.getError().startsWith("agent a2 is misconfigured: frame 1 "));
    assertTrue(slot.getError(), slot.getError().contains(parameter));
    assertEquals(0, slot.getHeldSeq());
    assertEquals(Collections.emptyMap(), slot.getCounters());
    // Verify that the offending agent has been removed from the fleet and terminated with exit
    // status 2.
    assertEquals(Collections.singletonList("a1"), simulation.agentIds());
    assertEquals(Integer.valueOf(2), simulation.agentRuntime("a2").exitStatus());
    assertEquals(AgentRuntime.State.STOPPED, simulation.agentRuntime("a2").state());
    // Verify that the aggregated fleet histograms reflect only the frames contributed by agent a1.
    assertTrue(run.getSteps().get(0).getSlots().get(0).getHeldSeq() >= 2);
    Histogram reference = heldOfSlot0(run);
    JsonArray histograms = Bundles.report(simulation.bundle(runId)).getAsJsonArray("histograms");
    assertEquals(1, histograms.size());
    JsonObject entry = histograms.get(0).getAsJsonObject();
    assertEquals(reference.getTotalCount(), entry.get("count").getAsLong());
    assertEquals(reference.getValueAtPercentile(50), entry.get("p50Us").getAsLong());
    assertEquals(reference.getValueAtPercentile(99), entry.get("p99Us").getAsLong());
    assertEquals(reference.getValueAtPercentile(99.9), entry.get("p999Us").getAsLong());
    assertEquals(reference.getMaxValue(), entry.get("maxUs").getAsLong());
    String[] agents = Bundles.row(simulation.bundle(runId), "pherf.fleet.agents");
    assertEquals("FAIL", agents[1]);
    assertTrue(agents[4], agents[4].contains("misconfigured"));
  }

  @Test
  public void pinnedFramesAreHeldAndMerged() throws Exception {
    Simulation simulation = fleet();
    String runId = submit(simulation, null, 1000);
    simulation.runFor(4000);
    RunView.SlotView slot = simulation.run(runId).getSteps().get(0).getSlots().get(0);
    assertTrue(slot.getHeldSeq() >= 1);
    long before = slot.getCounters().get("query:fake:q1|steady");
    simulation.runFor(30000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.COMPLETE, run.getState());
    slot = run.getSteps().get(0).getSlots().get(0);
    assertTrue(slot.getCounters().get("query:fake:q1|steady") > before);
    JsonObject entry = Bundles.report(simulation.bundle(runId)).getAsJsonArray("histograms").get(0)
      .getAsJsonObject();
    assertEquals(2 * 10 * SAMPLES, entry.get("count").getAsLong());
  }

  /**
   * Verifies that the controller refuses any frame header whose recorder parameters deviate from
   * the pinned configuration and explicitly names the mismatched parameter.
   */
  @Test
  public void otherHeaderIsRefused() throws Exception {
    String[][] cases = { { "10,600000000,3", "lowestUs 10, not 1" },
      { "1,3600000000,3", "highestUs 3600000000, not 600000000" },
      { "1,600000000,2", "significantDigits 2, not 3" } };
    for (String[] c : cases) {
      Simulation simulation = fleet();
      String runId = submit(simulation, c[0], 2500);
      simulation.runFor(6000);
      // Verify that the controller responds to the telemetry frame transmission with a rejection
      // reply.
      boolean rejected = false;
      for (InProcessNetwork.Event event : simulation.network.events()) {
        if (
          event.agent.equals("a2") && event.stream.equals(Protocol.TELEMETRY)
            && event.kind.equals(InProcessNetwork.REPLY)
        ) {
          assertFalse(event.json, rejected);
          rejected = event.json.contains("\"type\":\"rejected\"");
        }
      }
      assertTrue(c[0], rejected);
      assertMisconfigured(simulation, runId, c[1]);
    }
  }

  /**
   * Verifies that the controller also refuses a frame that pairs a valid pinned header with an
   * encoded histogram built using a different recorder configuration.
   */
  @Test
  public void histogramOfAnotherConfigurationIsRefused() throws Exception {
    Simulation simulation = fleet();
    String runId = submit(simulation, null, 20000);
    simulation.runFor(6000);
    assertEquals(0, simulation.run(runId).getSteps().get(0).getSlots().get(1).getHeldSeq());
    Histogram other = new Histogram(1, 3600000000L, 3);
    other.recordValue(5);
    Intervals batch =
      new Intervals(runId, 0, Collections.singletonList(new IntervalFrame(1, FakeWorkload.OPERATION,
        LatencyRecorders.STEADY, 0, 1, 1, RecorderConfig.PINNED, HistogramBlob.encode(other))));
    batch.setAgent("a2");
    batch.setIncarnation(simulation.agentRuntime("a2").identity().getIncarnation());
    batch.setTrace("a2/telemetry/99");
    Message reply = simulation.controller.telemetry(batch);
    assertEquals(MessageType.REJECTED, reply.type());
    assertTrue(((Rejected) reply).getReason(),
      ((Rejected) reply).getReason()
        .equals("agent a2 is misconfigured: frame 1 has a histogram with highestUs 3600000000,"
          + " not 600000000"));
    simulation.runFor(1000);
    assertMisconfigured(simulation, runId, "a histogram with highestUs 3600000000");
  }
}
