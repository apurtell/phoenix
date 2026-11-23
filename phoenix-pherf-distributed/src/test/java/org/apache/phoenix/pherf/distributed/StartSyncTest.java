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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Start;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

/**
 * Verifies the two-phase start synchronization protocol (RQ-232). In this protocol, agents launch
 * and complete their warm-up phase, hold at their respective start gates, and then receive a single
 * shared future epoch translated to each agent's local clock. The test environment models agent
 * hosts with skewed clocks, agents that become ready at widely varying times, and network jitter
 * during the delivery of start commands.
 */
public class StartSyncTest {
  private static final String[] AGENTS = { "a1", "a2", "a3" };
  private static final long[] SKEWS = { -4321, 0, 98765 };
  /**
   * Defines the duration from the completion of the last warm-up interval to the start gate for
   * each slot.
   */
  private static final long[] READY_MS = { 0, 3000, 25000 };

  private static final class Fleet {
    final Simulation simulation = new Simulation();
    final String runId;
    final RunView run;

    Fleet(long[] startDelayMs, Map<String, String> properties) {
      this(startDelayMs, new long[AGENTS.length], properties);
    }

    /**
     * Configures the simulated fleet network where startDelayMs specifies the delivery delay for
     * the start reply sent to each agent, with any simulated packet loss (DROP or RESET) applying
     * only to the first start message, and upMs specifies an additional delay applied to every
     * control request from each agent.
     */
    Fleet(long[] startDelayMs, long[] upMs, Map<String, String> properties) {
      Random random = new Random(232);
      Map<String, Long> startDelays = new HashMap<>();
      Map<String, Long> ups = new HashMap<>();
      for (int i = 0; i < AGENTS.length; i++) {
        startDelays.put(AGENTS[i], startDelayMs[i]);
        ups.put(AGENTS[i], upMs[i]);
      }
      simulation.network.policy((agent, stream, direction, message) -> {
        if (!stream.equals(Protocol.CONTROL)) {
          return 0;
        }
        if (message.type() == MessageType.START) {
          long delay = startDelays.get(agent);
          if (delay < 0) {
            startDelays.put(agent, 0L);
          }
          return delay;
        }
        // Apply random jitter of at most 10 ms in each direction, and ensure the upstream delay is
        // never 0 so that no message exchange is exact.
        return direction.equals(InProcessNetwork.REQUEST)
          ? ups.get(agent) + 1 + random.nextInt(10)
          : random.nextInt(11);
      });
      for (int i = 0; i < AGENTS.length; i++) {
        simulation.skewedAgent(AGENTS[i], SKEWS[i]);
      }
      simulation.runFor(3000);
      runId = simulation.controller.submit(Simulation.spec(7, properties, 3));
      simulation.runFor(60000);
      run = simulation.run(runId);
    }

    RunView.SlotView slot(String agent) {
      for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
        if (slot.getAgent().equals(agent)) {
          return slot;
        }
      }
      throw new AssertionError("no slot for " + agent);
    }

    /**
     * Returns all control replies matching the specified message type in order, along with the
     * associated agent for each reply.
     */
    List<Object[]> replies(MessageType type) throws Exception {
      List<Object[]> replies = new ArrayList<>();
      List<InProcessNetwork.Event> events = simulation.network.events();
      for (int i = 0; i < events.size(); i++) {
        InProcessNetwork.Event event = events.get(i);
        if (event.kind.equals(InProcessNetwork.REPLY) && event.stream.equals(Protocol.CONTROL)) {
          Message reply = Codec.decode(event.json);
          if (reply.type() == type) {
            replies.add(new Object[] { event.agent, reply, i });
          }
        }
      }
      return replies;
    }
  }

  private static Map<String, String> properties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.warmup", "2");
    properties.put("fake.intervals", "5");
    for (int slot = 0; slot < READY_MS.length; slot++) {
      properties.put("fake.readyMs." + slot, Long.toString(READY_MS[slot]));
    }
    return properties;
  }

  @Test
  public void agentsDispatchWithinFiftyMilliseconds() throws Exception {
    Fleet fleet = new Fleet(new long[] { 0, 900, 1700 }, properties());
    RunView run = fleet.run;
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    RunView.StepView step = run.getSteps().get(0);
    long epoch = step.getStartAtMs();

    // Verify that the shared start epoch lies in the future when each start command leaves the
    // controller, and that each agent receives the timestamp translated to its own local clock.
    List<Object[]> starts = fleet.replies(MessageType.START);
    assertEquals(AGENTS.length, starts.size());
    for (Object[] start : starts) {
      Start command = (Start) start[1];
      assertTrue(command.getTxMs() + " >= " + epoch, command.getTxMs() < epoch);
      RunView.SlotView slot = fleet.slot((String) start[0]);
      assertEquals(epoch + slot.getOffsetMs(), command.getStartAtMs());
      assertEquals(Long.valueOf(command.getStartAtMs()), slot.getStartAtMs());
    }
    // Ensure that every agent receives its launch command before any agent receives a start
    // command.
    List<Object[]> launches = fleet.replies(MessageType.LAUNCH);
    for (String agent : AGENTS) {
      int first = Integer.MAX_VALUE;
      for (Object[] launch : launches) {
        if (launch[0].equals(agent)) {
          first = Math.min(first, (Integer) launch[2]);
        }
      }
      assertTrue(agent, first < (Integer) starts.get(0)[2]);
    }

    // Verify that the true dispatch times across all agents, when normalized to the controller
    // clock, differ by less than 50 ms, and that the reported dispatch times remain within the
    // error bound of the clock offset estimate.
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (int i = 0; i < AGENTS.length; i++) {
      FakeWorkload workload = fleet.simulation.workload(AGENTS[i]);
      assertEquals(1, workload.dispatchedMs.size());
      long trueMs = workload.dispatchedMs.get(0) - SKEWS[i];
      min = Math.min(min, trueMs);
      max = Math.max(max, trueMs);
      RunView.SlotView slot = fleet.slot(AGENTS[i]);
      assertTrue(AGENTS[i] + " " + slot.getDispatchMs() + " vs " + trueMs,
        Math.abs(slot.getDispatchMs() - trueMs) <= slot.getDelayMs() / 2 + 1);
      assertEquals(RunView.SlotState.DONE, slot.getState());
      assertEquals(7, slot.getHeldSeq());
    }
    assertTrue("spread " + (max - min), max - min < 50);
    assertNotNull(step.getSkewMs());
    assertTrue("skew " + step.getSkewMs(), step.getSkewMs() < 50);
    assertTrue("skew " + step.getSkewMs(), step.getSkewMs() >= max - min);

    // Confirm that all warm-up telemetry frames complete before the start gate, and that no
    // steady-state frame begins prior to the gate timestamp.
    for (int i = 0; i < AGENTS.length; i++) {
      long localStart = fleet.simulation.workload(AGENTS[i]).startAtMs.get(0);
      int warmup = 0;
      for (IntervalFrame frame : frames(fleet.simulation, AGENTS[i])) {
        if (frame.getPhase().equals(LatencyRecorders.WARMUP)) {
          warmup++;
          assertTrue(frame.getEndMs() <= localStart);
        } else {
          assertTrue(AGENTS[i] + " " + frame.getStartMs() + " < " + localStart,
            frame.getStartMs() >= localStart);
        }
      }
      assertEquals(2, warmup);
    }
  }

  /**
   * Computes the spread across the true dispatch times of all agents as measured on the controller
   * clock.
   */
  private static long trueSpread(Fleet fleet) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (int i = 0; i < AGENTS.length; i++) {
      long trueMs = fleet.simulation.workload(AGENTS[i]).dispatchedMs.get(0) - SKEWS[i];
      min = Math.min(min, trueMs);
      max = Math.max(max, trueMs);
    }
    return max - min;
  }

  /**
   * Verifies recovery from a reset start reply. Because the subsequent retried heartbeat continues
   * to report the ARMED state, the controller detects the delivery loss and re-transmits the start
   * command immediately rather than waiting for the end of a hold period, ensuring that the command
   * arrives well before the scheduled start epoch.
   */
  @Test
  public void lostStartReplyIsSentAgainAtOnce() throws Exception {
    Fleet fleet = new Fleet(new long[] { InProcessNetwork.Policy.RESET, 0, 0 }, properties());
    RunView run = fleet.run;
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    RunView.StepView step = run.getSteps().get(0);
    assertEquals(1, fleet.simulation.count(InProcessNetwork.DROP, "start"));
    List<Object[]> starts = fleet.replies(MessageType.START);
    assertEquals(AGENTS.length, starts.size());
    for (Object[] start : starts) {
      Start command = (Start) start[1];
      if (start[0].equals("a1")) {
        assertTrue("held " + (command.getTxMs() - command.getRxMs()),
          command.getTxMs() - command.getRxMs() < Protocol.HOLD_MS);
      }
      assertTrue(start[0] + " " + command.getTxMs() + " vs " + step.getStartAtMs(),
        command.getTxMs() < step.getStartAtMs() - Protocol.RETRY_MS / 2);
    }
    long spread = trueSpread(fleet);
    assertTrue("spread " + spread, spread < 50);
    assertTrue("skew " + step.getSkewMs(), step.getSkewMs() < 50);
    assertTrue("skew " + step.getSkewMs(), step.getSkewMs() >= spread);
  }

  /**
   * Verifies that step skew bounds the clock offset estimation error under asymmetric network
   * latency. An asymmetric path introduces an offset error of up to half the delay, which cannot be
   * observed directly from the reported dispatch times on the controller clock. Because the skew
   * calculation incorporates the error bounds of the slots, it remains an upper bound on the true
   * dispatch spread.
   */
  @Test
  public void skewBoundsTheOffsetError() {
    Fleet fleet = new Fleet(new long[] { 0, 0, 0 }, new long[] { 0, 0, 60 }, properties());
    RunView run = fleet.run;
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    long spread = trueSpread(fleet);
    assertTrue("spread " + spread, spread >= 25);
    // The apparent spread of the reported dispatch times on the controller clock does not reflect
    // the underlying clock offset estimation error.
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      min = Math.min(min, slot.getDispatchMs());
      max = Math.max(max, slot.getDispatchMs());
    }
    assertTrue("measured " + (max - min) + " spread " + spread, max - min < spread);
    long skew = run.getSteps().get(0).getSkewMs();
    assertTrue("skew " + skew + " spread " + spread, skew >= spread);
  }

  /**
   * Returns all telemetry frames transmitted by the specified agent that reached the controller.
   */
  private static List<IntervalFrame> frames(Simulation simulation, String agent) throws Exception {
    List<IntervalFrame> frames = new ArrayList<>();
    for (InProcessNetwork.Event event : simulation.network.events()) {
      if (
        event.agent.equals(agent) && event.kind.equals(InProcessNetwork.REQUEST)
          && event.stream.equals(Protocol.TELEMETRY)
      ) {
        frames.addAll(((Intervals) Codec.decode(event.json)).getFrames());
      }
    }
    return frames;
  }

  /**
   * Verifies that a start command arriving after its scheduled epoch opens the start gate
   * immediately. The affected slot dispatches late, the resulting delay is reflected in the
   * measured step skew, and the run completes without aborting.
   */
  @Test
  public void lateStartIsMeasured() {
    Fleet fleet = new Fleet(new long[] { 0, 0, 3500 }, properties());
    RunView run = fleet.run;
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    RunView.StepView step = run.getSteps().get(0);
    long skew = step.getSkewMs();
    assertTrue("skew " + skew, skew > 1400 && skew < 1600);
    RunView.SlotView late = fleet.slot("a3");
    assertTrue(late.getDispatchMs() - step.getStartAtMs() > 1400);
  }

  /**
   * Verifies that an unsynchronized step configured without a start gate, such as a -l data load,
   * transitions its slots directly from LAUNCHED to DONE without issuing a start command.
   */
  @Test
  public void stepWithNoGateIsNotSynchronized() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.gate", "false");
    Fleet fleet = new Fleet(new long[] { 0, 0, 0 }, properties);
    RunView run = fleet.run;
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    assertEquals(0, fleet.simulation.count(InProcessNetwork.REPLY, "start"));
    assertEquals(3, fleet.simulation.count(InProcessNetwork.REPLY, "launch"));
    RunView.StepView step = run.getSteps().get(0);
    assertNull(step.getSkewMs());
    assertEquals(0, step.getStartAtMs());
    for (RunView.SlotView slot : step.getSlots()) {
      assertEquals(RunView.SlotState.DONE, slot.getState());
      assertNull(slot.getDispatchMs());
      assertEquals(3, slot.getHeldSeq());
    }
  }
}
