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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Register;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies RQ-233 by ensuring that the telemetry stream recovers and resumes cleanly after a fault.
 * The agent retransmits every unacknowledged frame, and the controller retains each sequence
 * exactly once without any gaps, ensuring that the retained sample count matches the total recorded
 * by the agent. Any fault that permanently loses frames causes the run to fail and prevents it from
 * ever completing.
 */
public class TelemetryResumeTest {
  private static final String[] AGENTS = { "a1", "a2", "a3" };
  private static final String TELEMETRY = Protocol.TELEMETRY;

  private static Simulation fleet(String... ids) {
    Simulation simulation = new Simulation();
    for (String id : ids) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    return simulation;
  }

  private static String submit(Simulation simulation, int agents, long intervals) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", Long.toString(intervals));
    return simulation.controller.submit(Simulation.spec(7, properties, agents));
  }

  /**
   * Asserts that each slot retains every frame emitted by its assigned agent exactly once, along
   * with the full sample count across all frames.
   */
  private static void heldExactlyOnce(Simulation simulation, RunView run) {
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      FakeWorkload workload = simulation.workload(slot.getAgent());
      assertEquals(RunView.SlotState.DONE, slot.getState());
      assertEquals(workload.emitted.size(), slot.getHeldSeq());
      assertEquals(slot.getHeldSeq(), slot.getFinalSeq());
      assertEquals(slot.getHeldSeq(), slot.getFrames());
      assertEquals(slot.getAgent(), workload.emittedCount(), slot.getSamples());
    }
  }

  /**
   * Collects the ordered frame sequence numbers for each telemetry event of the specified kind
   * associated with the given agent.
   */
  private static List<List<Long>> sequences(Simulation simulation, String agent, String kind)
    throws Exception {
    List<List<Long>> batches = new ArrayList<>();
    for (InProcessNetwork.Event event : simulation.network.events()) {
      if (event.agent.equals(agent) && event.stream.equals(TELEMETRY) && event.kind.equals(kind)) {
        Message message = Codec.decode(event.json);
        if (message instanceof Intervals) {
          List<Long> seqs = new ArrayList<>();
          for (IntervalFrame frame : ((Intervals) message).getFrames()) {
            seqs.add(frame.getSeq());
          }
          batches.add(seqs);
        }
      }
    }
    return batches;
  }

  /**
   * Flattens and returns the frame sequences across all telemetry events of the specified kind for
   * the given agent.
   */
  private static List<Long> all(Simulation simulation, String agent, String kind) throws Exception {
    List<Long> seqs = new ArrayList<>();
    for (List<Long> batch : sequences(simulation, agent, kind)) {
      seqs.addAll(batch);
    }
    return seqs;
  }

  /**
   * Determines whether at least one frame from a failed telemetry call subsequently reached the
   * controller in a later request.
   */
  private static boolean resentAfterFailure(Simulation simulation, String agent) throws Exception {
    List<Long> requested = all(simulation, agent, InProcessNetwork.REQUEST);
    for (Long seq : all(simulation, agent, InProcessNetwork.FAIL)) {
      if (requested.contains(seq)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines whether any frame sequence reached the controller across two separate requests.
   */
  private static boolean requestedTwice(Simulation simulation, String agent) throws Exception {
    List<Long> requested = all(simulation, agent, InProcessNetwork.REQUEST);
    for (Long seq : requested) {
      if (requested.indexOf(seq) != requested.lastIndexOf(seq)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void breakLostReplyAndCopiesHoldEachSequenceOnce() throws Exception {
    Simulation simulation = fleet(AGENTS);
    int[] acks = new int[1];
    simulation.network.policy(new InProcessNetwork.Policy() {
      @Override
      public long delay(String agent, String stream, String direction, Message message) {
        // Drop the first reply for agent a2 that acknowledges frame 5, even though the controller
        // already retains those frames.
        if (
          agent.equals("a2") && message instanceof Ack && ((Ack) message).getHeldSeq() >= 5
            && acks[0]++ == 0
        ) {
          return DROP;
        }
        return 0;
      }

      @Override
      public long[] copies(String agent, String stream, Message request) {
        // For agent a3, inject an immediate duplicate copy alongside a delayed copy that arrives
        // after the subsequent request.
        return agent.equals("a3") && stream.equals(TELEMETRY)
          ? new long[] { 0, 1500 }
          : new long[0];
      }
    });
    String runId = submit(simulation, 3, 20);
    simulation.runFor(10000);
    assertEquals(RunView.State.RUNNING, simulation.run(runId).getState());
    simulation.network.breakStream("a1", TELEMETRY);
    simulation.runFor(5000);
    simulation.network.restore("a1", TELEMETRY);
    simulation.runFor(30000);
    heldExactlyOnce(simulation, simulation.run(runId));
    assertEquals(20, simulation.workload("a1").emitted.size());
    // Verify that the injected faults actually took effect: agent a1 retransmitted frames from
    // failed calls, agent a2 retransmitted frames that were already retained, and duplicate
    // copies of requests from agent a3 reached the controller.
    assertTrue(resentAfterFailure(simulation, "a1"));
    assertEquals(1, simulation.count(InProcessNetwork.DROP, "ack"));
    assertTrue(requestedTwice(simulation, "a2"));
    assertTrue(sequences(simulation, "a3", InProcessNetwork.COPY).size()
        >= 2 * sequences(simulation, "a3", InProcessNetwork.REQUEST).size() - 2);
  }

  /**
   * Exercises the telemetry streams under deterministic, seeded chaos comprising message losses,
   * duplicate copies, and variable transport delays.
   */
  @Test
  public void chaosHoldsEachSequenceOnce() throws Exception {
    Simulation simulation = fleet(AGENTS);
    Random random = new Random(233);
    simulation.network.policy(new InProcessNetwork.Policy() {
      @Override
      public long delay(String agent, String stream, String direction, Message message) {
        if (!stream.equals(TELEMETRY)) {
          return 0;
        }
        return random.nextInt(10) < 3 ? RESET : random.nextInt(401);
      }

      @Override
      public long[] copies(String agent, String stream, Message request) {
        return stream.equals(TELEMETRY) && random.nextInt(10) < 2
          ? new long[] { random.nextInt(401) }
          : new long[0];
      }
    });
    String runId = submit(simulation, 3, 20);
    simulation.runFor(60000);
    heldExactlyOnce(simulation, simulation.run(runId));
    assertTrue(simulation.count(InProcessNetwork.DROP, "intervals") > 3);
    assertTrue(simulation.count(InProcessNetwork.DROP, "ack") > 3);
    assertTrue(simulation.count(InProcessNetwork.COPY, "intervals") > 3);
  }

  /**
   * Verifies that a telemetry stream failing continuously beyond the allowed deadline causes the
   * entire run to fail.
   */
  @Test
  public void deadTelemetryFailsTheRun() {
    Simulation simulation = fleet(AGENTS);
    String runId = submit(simulation, 3, 100);
    simulation.runFor(8000);
    simulation.network.breakStream("a1", TELEMETRY);
    simulation.runFor(20000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a1: telemetry stream failed for more than 10000 ms", run.getReason());
    assertEquals(RunView.SlotState.FAILED, run.getSteps().get(0).getSlots().get(0).getState());
    // Confirm that the agent has terminated its workload and cleared its queued frames so that no
    // further telemetry is transmitted.
    assertEquals(1, simulation.workload("a1").cancelled.size());
    assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime("a1").state());
    int failed = simulation.count(InProcessNetwork.FAIL, "intervals");
    simulation.runFor(10000);
    assertEquals(failed, simulation.count(InProcessNetwork.FAIL, "intervals"));
    assertEquals(AgentView.State.IDLE, simulation.view("a1").getState());
  }

  /**
   * Verifies that a telemetry failure arms the timeout deadline strictly for its own active work.
   * When the initial run fails on another slot, agent a1 discards its pending frames; in the
   * subsequent run, the first acknowledgment for agent a1 arrives after the previous deadline would
   * have expired, and the new run still completes successfully.
   */
  @Test
  public void deadlineEndsWithItsWork() {
    Simulation simulation = fleet("a1", "a2");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "100");
    properties.put("fake.fail.1", "3");
    String first = simulation.controller.submit(Simulation.spec(7, properties, 2));
    simulation.runFor(3500);
    simulation.network.breakStream("a1", TELEMETRY);
    simulation.runFor(4000);
    assertEquals(RunView.State.FAILED, simulation.run(first).getState());
    assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime("a1").state());
    simulation.network.restore("a1", TELEMETRY);
    simulation.network.policy((agent, stream, direction,
      message) -> agent.equals("a1") && stream.equals(TELEMETRY) ? 2400 : 0);
    simulation.runFor(1000);
    String next = submit(simulation, 2, 20);
    simulation.runFor(40000);
    RunView run = simulation.run(next);
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      assertEquals(20, slot.getHeldSeq());
      assertEquals(20, slot.getFrames());
    }
  }

  /**
   * Verifies that receiving an acknowledgment sequence lower than the previously acknowledged
   * sequence indicates frame loss on the controller and causes the slot to fail.
   */
  @Test
  public void lowAckFailsTheSlot() {
    Simulation simulation = fleet("a1");
    int[] acks = new int[1];
    simulation.network.policy(new InProcessNetwork.Policy() {
      @Override
      public long delay(String agent, String stream, String direction, Message message) {
        return 0;
      }

      @Override
      public Message reply(String agent, String stream, Message reply) {
        if (reply instanceof Ack && ++acks[0] == 4) {
          Ack ack = (Ack) reply;
          Ack low = new Ack(ack.getRunId(), ack.getStep(), 1);
          low.setTrace(ack.getTrace());
          return low;
        }
        return reply;
      }
    });
    String runId = submit(simulation, 1, 100);
    simulation.runFor(20000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a1: telemetry ack 1 is below the acked sequence 3", run.getReason());
    int requests = simulation.count(InProcessNetwork.REQUEST, "intervals");
    simulation.runFor(10000);
    assertEquals(requests, simulation.count(InProcessNetwork.REQUEST, "intervals"));
    assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime("a1").state());
  }

  /**
   * Verifies that restarting the controller discards the active run without double-counting frames.
   * The replacement controller acknowledges incoming frames from the prior run without retaining
   * them, prompting the agents to discard those frames and re-register so that the subsequent run
   * accounts strictly for its own frames.
   */
  @Test
  public void controllerRestartCountsNothingTwice() throws Exception {
    Simulation simulation = fleet("a1", "a2");
    String runId = submit(simulation, 2, 100);
    simulation.runFor(8000);
    Controller old = simulation.controller;
    simulation.network.freeze("a1", Protocol.CONTROL);
    simulation.restartController();
    int before = simulation.network.events().size();
    simulation.runFor(3000);
    // Confirm that the new controller received stale frames from agent a1 and acknowledged the
    // highest sequence number in each batch.
    int batches = 0;
    List<InProcessNetwork.Event> events = simulation.network.events();
    for (int i = before; i < events.size(); i++) {
      InProcessNetwork.Event event = events.get(i);
      if (
        event.agent.equals("a1") && event.kind.equals(InProcessNetwork.REPLY)
          && event.stream.equals(TELEMETRY)
      ) {
        batches++;
      }
    }
    assertTrue(batches >= 2);
    simulation.network.thaw("a1", Protocol.CONTROL);
    simulation.runFor(5000);
    assertNull(simulation.run(runId));
    assertEquals(RunView.State.RUNNING, old.run(runId).getState());
    for (String id : new String[] { "a1", "a2" }) {
      assertEquals(AgentView.State.IDLE, simulation.view(id).getState());
      assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime(id).state());
    }
    Map<String, Integer> emittedBefore = new HashMap<>();
    for (String id : new String[] { "a1", "a2" }) {
      emittedBefore.put(id, simulation.workload(id).emitted.size());
    }
    String next = submit(simulation, 2, 3);
    simulation.runFor(15000);
    RunView run = simulation.run(next);
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      FakeWorkload workload = simulation.workload(slot.getAgent());
      List<Long> own =
        workload.emitted.subList(emittedBefore.get(slot.getAgent()), workload.emitted.size());
      assertEquals(3, own.size());
      assertEquals(3, slot.getHeldSeq());
      assertEquals(3, slot.getFrames());
      assertEquals(own.get(0) + own.get(1) + own.get(2), slot.getSamples());
    }
  }

  /**
   * Verifies that telemetry frames arriving from a superseded agent incarnation are acknowledged by
   * the controller but never retained.
   */
  @Test
  public void staleIncarnationFramesNotHeld() throws Exception {
    Simulation simulation = fleet("a1");
    String runId = submit(simulation, 1, 100);
    simulation.runFor(8000);
    // Freeze the control stream so the prior agent process hears nothing on it (not even its
    // rejection) and continues transmitting telemetry frames.
    simulation.network.freeze("a1", Protocol.CONTROL);
    long held = simulation.run(runId).getSteps().get(0).getSlots().get(0).getHeldSeq();
    assertTrue(held > 0);
    Register register = new Register();
    register.setAgent("a1");
    register.setIncarnation("i-new");
    register.setTrace("a1/control/1");
    Message[] registered = new Message[1];
    simulation.controller.control(register, new Callback<Message>() {
      @Override
      public void completed(Message reply) {
        registered[0] = reply;
      }

      @Override
      public void failed(IOException e) {
        throw new AssertionError(e);
      }
    });
    assertEquals(MessageType.REGISTERED, registered[0].type());
    int before = simulation.network.events().size();
    simulation.runFor(4000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("agent a1 restarted", run.getReason());
    RunView.SlotView slot = run.getSteps().get(0).getSlots().get(0);
    assertEquals(held, slot.getHeldSeq());
    assertEquals(held, slot.getFrames());
    int acked = 0;
    List<InProcessNetwork.Event> events = simulation.network.events();
    for (int i = before; i < events.size(); i++) {
      InProcessNetwork.Event event = events.get(i);
      if (event.kind.equals(InProcessNetwork.REPLY) && event.stream.equals(TELEMETRY)) {
        Ack ack = (Ack) Codec.decode(event.json);
        assertTrue(ack.getHeldSeq() > held);
        acked++;
      }
    }
    assertTrue(acked >= 2);
  }
}
