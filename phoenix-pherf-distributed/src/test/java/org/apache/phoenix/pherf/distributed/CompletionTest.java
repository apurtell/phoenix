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
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies the two-phase completion protocol specified by RQ-236. An agent transmits its final
 * interval before reporting DONE, and the run remains in the RUNNING state with no bundle written
 * until the controller holds the final interval from every agent. The merged count then includes
 * the final interval.
 */
public class CompletionTest {
  private static final long FINAL_SEQ = 3;
  private static final long DELAY_MS = 3000;

  private static boolean holdsFinal(Object message) {
    if (!(message instanceof Intervals)) {
      return false;
    }
    for (IntervalFrame frame : ((Intervals) message).getFrames()) {
      if (frame.getSeq() == FINAL_SEQ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the index of the first recorded request event from the given agent that matches the
   * specified stream and payload text, or -1 if no matching event exists.
   */
  private static int first(List<InProcessNetwork.Event> events, String agent, String stream,
    String text) {
    for (int i = 0; i < events.size(); i++) {
      InProcessNetwork.Event event = events.get(i);
      if (
        event.agent.equals(agent) && event.kind.equals(InProcessNetwork.REQUEST)
          && event.stream.equals(stream) && event.json.contains(text)
      ) {
        return i;
      }
    }
    return -1;
  }

  @Test
  public void runCompletesOnlyWhenEveryFinalIntervalIsHeld() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    long[] delayedAt = { -1 };
    simulation.network.policy((agent, stream, direction, message) -> {
      if (
        agent.equals("a2") && stream.equals(Protocol.TELEMETRY)
          && direction.equals(InProcessNetwork.REQUEST) && holdsFinal(message) && delayedAt[0] < 0
      ) {
        delayedAt[0] = simulation.clock.millis();
        return DELAY_MS;
      }
      return 0;
    });
    String runId = simulation.controller.submit(Simulation.spec(2));
    File manifest = new File(simulation.bundle(runId), RunBundle.MANIFEST);
    while (delayedAt[0] < 0) {
      simulation.runFor(100);
      assertTrue(simulation.clock.millis() < Simulation.START_MS + 30000);
    }
    simulation.runTo(delayedAt[0] + DELAY_MS - 1);
    // Agent a1 has finished, whereas a2 is still waiting for the ack of its final interval.
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.RUNNING, run.getState());
    assertEquals(RunView.SlotState.DONE, run.getSteps().get(0).getSlots().get(0).getState());
    assertEquals(RunView.SlotState.RUNNING, run.getSteps().get(0).getSlots().get(1).getState());
    assertEquals(FINAL_SEQ - 1, run.getSteps().get(0).getSlots().get(1).getHeldSeq());
    assertEquals(-1,
      first(simulation.network.events(), "a2", Protocol.CONTROL, "\"phase\":\"DONE\""));
    assertFalse(manifest.exists());

    simulation.runFor(3000);
    run = simulation.run(runId);
    assertEquals(RunView.State.COMPLETE, run.getState());
    assertTrue(manifest.isFile());
    long emitted =
      simulation.workload("a1").emittedCount() + simulation.workload("a2").emittedCount();
    long merged = 0;
    for (long count : Bundles.counts(Bundles.report(simulation.bundle(runId))).values()) {
      merged += count;
    }
    assertEquals(emitted, merged);
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      assertEquals(FINAL_SEQ, slot.getHeldSeq());
      assertEquals(FINAL_SEQ, slot.getFinalSeq());
      // Ensure that the telemetry batch containing the final interval precedes the first DONE
      // heartbeat reported by each agent.
      List<InProcessNetwork.Event> events = simulation.network.events();
      int batch = first(events, slot.getAgent(), Protocol.TELEMETRY, "\"seq\":" + FINAL_SEQ + ",");
      int done = first(events, slot.getAgent(), Protocol.CONTROL, "\"phase\":\"DONE\"");
      assertTrue(slot.getAgent(), batch >= 0 && done > batch);
      assertTrue(slot.getAgent(), events.get(done).json.contains("\"report\":{"));
    }
  }
}
