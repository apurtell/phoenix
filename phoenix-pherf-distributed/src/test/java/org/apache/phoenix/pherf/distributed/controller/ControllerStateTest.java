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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.clock.VirtualClock;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assign;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Phase;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.distributed.protocol.Register;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.apache.phoenix.pherf.distributed.transport.ChannelRequests;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

/**
 * Verifies the controller state machine when driven by direct channel invocations and by the
 * simulation.
 */
public class ControllerStateTest {
  private final VirtualClock clock = new VirtualClock(Simulation.START_MS);
  private final Controller controller = new Controller(clock);
  private int traces;

  /**
   * Captures the reply for a single control request, remaining null while the controller holds the
   * poll.
   */
  private static final class Reply implements Callback<Message> {
    Message value;

    @Override
    public void completed(Message reply) {
      value = reply;
    }

    @Override
    public void failed(IOException e) {
      throw new AssertionError(e);
    }
  }

  private <T extends AgentMessage> T from(String agent, String incarnation, T message) {
    message.setAgent(agent);
    message.setIncarnation(incarnation);
    message.setTrace(agent + "/control/" + ++traces);
    return message;
  }

  private Reply control(AgentMessage message) {
    Reply reply = new Reply();
    controller.control(message, reply);
    return reply;
  }

  private Reply heartbeat(String agent, Phase phase, String runId, int step, long finalSeq) {
    return control(from(agent, "i-" + agent,
      new Heartbeat(traces + 1, new Status(phase, runId, step, finalSeq, null, 0), null)));
  }

  private Reply heartbeat(String agent, Phase phase, String runId) {
    return heartbeat(agent, phase, runId, 0, 0);
  }

  private void register(String agent) {
    assertEquals(MessageType.REGISTERED,
      control(from(agent, "i-" + agent, new Register())).value.type());
  }

  private static MessageType type(Reply reply) {
    return reply.value == null ? null : reply.value.type();
  }

  private Ack telemetry(String agent, String runId, long... seqs) {
    return (Ack) telemetryReply(agent, runId, seqs);
  }

  /**
   * Sends a telemetry batch containing one interval frame for each sequence number, where frame seq
   * records seq latency samples.
   */
  private Message telemetryReply(String agent, String runId, long... seqs) {
    List<IntervalFrame> frames = new ArrayList<>();
    for (long seq : seqs) {
      Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      histogram.recordValueWithCount(10, seq);
      frames.add(new IntervalFrame(seq, "query:s:q", LatencyRecorders.STEADY, 0, 1,
        RecorderConfig.of(histogram), HistogramBlob.encode(histogram)));
    }
    Intervals batch = from(agent, "i-" + agent, new Intervals(runId, 0, frames));
    return controller.telemetry(batch);
  }

  /**
   * Registers agent a1 and advances a single-slot run through assignment, launch, and start into
   * the RUNNING phase.
   */
  private String running() {
    register("a1");
    String runId = controller.submit(Simulation.spec(1));
    assertEquals(MessageType.ASSIGN, type(heartbeat("a1", Phase.IDLE, null)));
    assertEquals(MessageType.LAUNCH, type(heartbeat("a1", Phase.READY, runId)));
    assertEquals(MessageType.START, type(heartbeat("a1", Phase.ARMED, runId)));
    assertNull(type(heartbeat("a1", Phase.RUNNING, runId)));
    return runId;
  }

  @Test
  public void doneWaitsForTheHeldSequence() {
    register("a1");
    String runId = controller.submit(Simulation.spec(1));
    assertEquals("20260101T000000000Z-1", runId);
    Reply assign = heartbeat("a1", Phase.IDLE, null);
    assertEquals(MessageType.ASSIGN, type(assign));
    assertEquals("a1/control/2", assign.value.getTrace());
    assertEquals("p000", ((Assign) assign.value).getAssignment().getIdentity().getPrefix());
    assertNull(type(heartbeat("a1", Phase.PREPARING, runId)));
    assertEquals(MessageType.LAUNCH, type(heartbeat("a1", Phase.READY, runId)));
    assertNull(type(heartbeat("a1", Phase.LAUNCHED, runId)));
    assertEquals(MessageType.START, type(heartbeat("a1", Phase.ARMED, runId)));
    assertNull(type(heartbeat("a1", Phase.RUNNING, runId)));
    Reply done = heartbeat("a1", Phase.DONE, runId, 0, 2);
    assertNull(type(done));
    assertEquals(RunView.State.RUNNING, controller.run(runId).getState());
    assertEquals(1, telemetry("a1", runId, 1).getHeldSeq());
    // Frames arriving after a sequence gap are not acknowledged, and duplicate frames are skipped.
    assertEquals(1, telemetry("a1", runId, 3).getHeldSeq());
    assertNull(type(done));
    assertEquals(2, telemetry("a1", runId, 1, 2).getHeldSeq());
    assertEquals(MessageType.RELEASE, type(done));
    RunView run = controller.run(runId);
    assertEquals(RunView.State.COMPLETE, run.getState());
    RunView.SlotView slot = run.getSteps().get(0).getSlots().get(0);
    assertEquals(2, slot.getHeldSeq());
    assertEquals(2, slot.getFrames());
    assertEquals(RunView.SlotState.DONE, slot.getState());
    // When the controller does not recognize the run, the acknowledgment reports the highest
    // sequence number in the batch.
    assertEquals(9, telemetry("a1", "unknown", 4, 9).getHeldSeq());
  }

  @Test
  public void staleRunIsCancelledOrReleased() {
    register("a1");
    Reply running = heartbeat("a1", Phase.RUNNING, "old");
    assertEquals(MessageType.CANCEL, type(running));
    // The cancel command is not reissued immediately because the agent continues to report its
    // active phase until its running work stops.
    assertNull(type(heartbeat("a1", Phase.RUNNING, "old")));
    assertEquals(MessageType.RELEASE, type(heartbeat("a1", Phase.DONE, "old")));
    // A newer heartbeat that still requests the previous command indicates that the earlier reply
    // was lost, so the controller reissues the command immediately once; any subsequent repeat
    // waits for one hold interval.
    assertEquals(MessageType.RELEASE, type(heartbeat("a1", Phase.FAILED, "old")));
    Reply again = heartbeat("a1", Phase.FAILED, "old");
    assertNull(type(again));
    clock.advance(1000);
    assertEquals(MessageType.RELEASE, type(again));
    // An agent that is still executing a stale run is not considered free.
    heartbeat("a1", Phase.RUNNING, "old");
    try {
      controller.submit(Simulation.spec(1));
      fail("submitted to a busy agent");
    } catch (IllegalStateException e) {
      assertEquals("step 0 needs 1 agents but 0 are free", e.getMessage());
    }
  }

  @Test
  public void unknownAgentMustRegisterAgain() {
    assertEquals(MessageType.REREGISTER, type(heartbeat("zz", Phase.IDLE, null)));
    assertTrue(controller.agents().isEmpty());
    register("zz");
    assertNull(type(heartbeat("zz", Phase.IDLE, null)));
    assertEquals(1, controller.agents().size());
  }

  @Test
  public void newIncarnationReplacesTheRecord() {
    register("a1");
    register("a2");
    String runId = controller.submit(Simulation.spec(2));
    assertEquals(MessageType.ASSIGN, type(heartbeat("a1", Phase.IDLE, null)));
    Reply held = heartbeat("a1", Phase.PREPARING, runId);
    assertNull(type(held));
    long registered = controller.agents().get(0).getRegisteredMs();
    clock.advance(500);
    assertEquals(MessageType.REGISTERED, control(from("a1", "i-new", new Register())).value.type());
    assertEquals(MessageType.REJECTED, type(held));
    assertEquals("replaced", ((Rejected) held.value).getReason());
    AgentView view = controller.agents().get(0);
    assertEquals("i-new", view.getIncarnation());
    assertEquals(registered + 500, view.getRegisteredMs());
    RunView run = controller.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals("agent a1 restarted", run.getReason());
    // The previous agent process is rejected on its next poll.
    assertEquals(MessageType.REJECTED, type(heartbeat("a1", Phase.PREPARING, runId)));
    // The remaining slot is cancelled.
    assertEquals(MessageType.CANCEL, type(heartbeat("a2", Phase.PREPARING, runId)));
  }

  @Test
  public void tooFewAgentsAreRefusedOrFailTheRun() {
    register("a1");
    register("a2");
    try {
      controller.submit(Simulation.spec(3));
      fail("submitted with too few agents");
    } catch (IllegalStateException e) {
      assertEquals("step 0 needs 3 agents but 2 are free", e.getMessage());
    }
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Simulation.spec(1, 3));
    simulation.runFor(15000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    assertEquals("step 1 needs 3 agents but 2 are free", run.getReason());
    assertEquals(RunView.StepState.COMPLETE, run.getSteps().get(0).getState());
    assertEquals(RunView.StepState.FAILED, run.getSteps().get(1).getState());
    assertEquals("20260101T000001000Z-1", runId);
  }

  /**
   * Verifies that plan validation during submission rejects an invalid plan before any execution
   * slot is bound.
   */
  @Test
  public void refusedPlanBindsNothing() {
    register("a1");
    register("a2");
    register("a3");
    String offset = "<c offset=\"${pherf.slot.offset}\"/>";
    for (Plans plan : new Plans[] { Plans.plan(7).step(Plans.step(3).scenario("s.xml", offset)),
      Plans.plan(7).step(Plans.step(3).scenario("s.xml", "<s/>").rows(2)) }) {
      try {
        controller.submit(plan.spec());
        fail("submitted " + plan.json());
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().startsWith("steps[0]"));
      }
    }
    // Because no slots are bound, every agent remains free.
    assertNull(type(heartbeat("a3", Phase.IDLE, null)));
    controller.submit(Simulation.spec(3));
  }

  /**
   * Verifies that a delayed copy of an older heartbeat receives a wait response and does not fail
   * the run.
   */
  @Test
  public void staleHeartbeatIsIgnored() throws Exception {
    register("a1");
    String runId = controller.submit(Simulation.spec(1));
    Heartbeat late = from("a1", "i-a1", new Heartbeat(100, Status.IDLE, null));
    Heartbeat ready =
      from("a1", "i-a1", new Heartbeat(101, new Status(Phase.READY, runId, 0, 0, null, 0), null));
    assertEquals(MessageType.ASSIGN, type(heartbeat("a1", Phase.IDLE, null)));
    // Heartbeats traverse the serialized wire format, requiring the codec to preserve the seq
    // field across encoding and decoding.
    assertEquals(MessageType.LAUNCH, type(control(ChannelRequests.control(Codec.encode(ready)))));
    assertEquals(MessageType.WAIT, type(control(ChannelRequests.control(Codec.encode(late)))));
    RunView run = controller.run(runId);
    assertEquals(RunView.State.RUNNING, run.getState());
    assertEquals(RunView.SlotState.LAUNCHED, run.getSteps().get(0).getSlots().get(0).getState());
  }

  @Test
  public void prepareFailureCancelsTheOtherSlots() {
    Simulation simulation = new Simulation();
    AgentRuntime a1 = simulation.agent("a1");
    AgentRuntime a2 = simulation.agent("a2");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.prepareError.1", "bad slice");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 2));
    simulation.runFor(5000);
    RunView run = simulation.run(runId);
    assertEquals(RunView.State.ABORTED, run.getState());
    assertEquals("agent a2: bad slice", run.getReason());
    assertEquals(Arrays.asList(RunView.SlotState.CANCELLED, RunView.SlotState.FAILED),
      Arrays.asList(run.getSteps().get(0).getSlots().get(0).getState(),
        run.getSteps().get(0).getSlots().get(1).getState()));
    assertEquals(0, simulation.count("reply", "launch"));
    assertTrue(simulation.workload("a1").launched.isEmpty());
    assertEquals(Arrays.asList(runId), simulation.workload("a1").cancelled);
    assertEquals(AgentRuntime.State.IDLE, a1.state());
    assertEquals(AgentRuntime.State.IDLE, a2.state());
    for (AgentView view : simulation.controller.agents()) {
      assertEquals(AgentView.State.IDLE, view.getState());
    }
  }

  /**
   * Verifies RQ-233: the controller decodes each held interval frame exactly once upon ingestion
   * and accumulates its sample count.
   */
  @Test
  public void samplesAreCountedOnceAtIngest() {
    String runId = running();
    assertEquals(2, telemetry("a1", runId, 1, 2, 4).getHeldSeq());
    assertEquals(4, telemetry("a1", runId, 1, 2, 3, 4).getHeldSeq());
    assertEquals(4, telemetry("a1", runId, 3, 4).getHeldSeq());
    RunView.SlotView slot = controller.run(runId).getSteps().get(0).getSlots().get(0);
    assertEquals(4, slot.getFrames());
    assertEquals(1 + 2 + 3 + 4, slot.getSamples());
  }

  /**
   * Verifies that an interval frame whose histogram fails to decode transitions its slot to the
   * failed state, returns an ack that holds no additional frames, and leaves the agent registered.
   */
  @Test
  public void badHistogramFailsTheSlot() {
    String runId = running();
    assertEquals(1, telemetry("a1", runId, 1).getHeldSeq());
    List<IntervalFrame> frames = new ArrayList<>();
    frames.add(new IntervalFrame(2, "query:s:q", LatencyRecorders.STEADY, 0, 1,
      RecorderConfig.PINNED, "not-a-histogram"));
    Message reply = controller.telemetry(from("a1", "i-a1", new Intervals(runId, 0, frames)));
    assertEquals(MessageType.ACK, reply.type());
    assertEquals(1, ((Ack) reply).getHeldSeq());
    RunView run = controller.run(runId);
    assertEquals(RunView.State.FAILED, run.getState());
    RunView.SlotView slot = run.getSteps().get(0).getSlots().get(0);
    assertEquals(RunView.SlotState.FAILED, slot.getState());
    assertTrue(slot.getError(), slot.getError()
      .startsWith("agent a1: frame 2 of run " + runId + " step 0 has a bad histogram: "));
    assertEquals(1, slot.getHeldSeq());
    assertEquals(1, slot.getSamples());
    assertEquals("a1", controller.agents().get(0).getId());
  }
}
