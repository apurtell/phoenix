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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assign;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Cancel;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Phase;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Registered;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.protocol.Release;
import org.apache.phoenix.pherf.distributed.protocol.Reregister;
import org.apache.phoenix.pherf.distributed.protocol.Shutdown;
import org.apache.phoenix.pherf.distributed.protocol.Start;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.apache.phoenix.pherf.distributed.protocol.Wait;
import org.apache.phoenix.pherf.distributed.transport.AgentChannel;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains the central controller state, encompassing the agent table, the runs, and the held
 * long-poll requests, all synchronized behind a single monitor.
 * <p>
 * The control stream operates on a level-triggered model. Every heartbeat reports the complete
 * status of the agent, and every reply delivers a single command. Because the controller derives
 * the command from the desired state and the reported state, a repeated request produces no
 * additional side effects. When no command is pending, the controller holds the poll open for at
 * most {@link Protocol#HOLD_MS}, and any transition in the desired state re-evaluates and answers
 * the held polls. An identical command is dispatched at most once per hold cycle. Any heartbeat
 * that is not newer than the last one applied receives a wait reply, ensuring that a delayed
 * duplicate cannot revert a more recent status update.
 * <p>
 * A background reaper evicts any agent that remains silent for longer than
 * {@link Protocol#DEADLINE_MS} of monotonic time. When an agent is evicted or departs, its assigned
 * slot fails, causing the corresponding run to fail as well.
 */
public final class Controller implements AgentChannel {
  private static final Logger LOG = LoggerFactory.getLogger(Controller.class);
  private static final DateTimeFormatter RUN_ID =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

  private final Clock clock;
  private final TreeMap<String, AgentRecord> agents = new TreeMap<>();
  private final Map<String, RunRecord> runs = new LinkedHashMap<>();
  private Clock.Timer reaper;
  private long runCount;
  private boolean closed;

  public Controller(Clock clock) {
    this.clock = clock;
  }

  /** Starts the periodic reaper task that evicts unresponsive agents. */
  public synchronized void start() {
    if (reaper == null && !closed) {
      reaper = clock.schedule(Protocol.REAP_PERIOD_MS, this::reap);
    }
  }

  /** Stops the periodic reaper task and answers every currently held poll. */
  public synchronized void close() {
    closed = true;
    if (reaper != null) {
      reaper.cancel();
    }
    for (AgentRecord agent : agents.values()) {
      answerHeld(agent, new Wait());
    }
  }

  /** Returns a snapshot of the registered agents ordered by their operational identifier. */
  public synchronized List<AgentView> agents() {
    List<AgentView> views = new ArrayList<>();
    for (AgentRecord agent : agents.values()) {
      views.add(agent.view());
    }
    return views;
  }

  /** Returns a view of the requested run, or null if the run is unknown to the controller. */
  public synchronized RunView run(String runId) {
    RunRecord run = runs.get(runId);
    return run == null ? null : run.view();
  }

  /**
   * Validates the specification, initiates a new run across available free agents, and returns the
   * assigned run identifier.
   * @throws IllegalArgumentException When the run specification is invalid.
   * @throws IllegalStateException    When fewer free agents are available than step 0 requires.
   */
  public synchronized String submit(RunSpec spec) {
    if (spec.getSteps().isEmpty()) {
      throw new IllegalArgumentException("a run needs at least one step");
    }
    for (int i = 0; i < spec.getSteps().size(); i++) {
      StepSpec step = spec.getSteps().get(i);
      if (step.getAgents() < 1 || step.getAgents() > DataPlaneIdentity.MAX_SLOTS) {
        throw new IllegalArgumentException("step " + i + " asks for " + step.getAgents()
          + " agents; a step takes 1 to " + DataPlaneIdentity.MAX_SLOTS);
      }
      if (step.getStride() < 0) {
        throw new IllegalArgumentException("step " + i + " has a negative stride");
      }
      try {
        Math.multiplyExact((long) step.getAgents() - 1, step.getStride());
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
          "step " + i + " stride " + step.getStride() + " overflows the slot offset");
      }
    }
    String shortage = shortage(spec, 0);
    if (shortage != null) {
      throw new IllegalStateException(shortage);
    }
    String runId = RUN_ID.format(Instant.ofEpochMilli(clock.millis())) + "-" + ++runCount;
    RunRecord run = new RunRecord(runId, spec);
    runs.put(runId, run);
    LOG.info("run {} submitted with {} steps", runId, spec.getSteps().size());
    bindStep(run, 0);
    answerHeldPolls();
    return runId;
  }

  /**
   * Requests an orderly shutdown of the specified agent, returning false if the agent is unknown to
   * the controller.
   */
  public synchronized boolean shutdownAgent(String id) {
    AgentRecord agent = agents.get(id);
    if (agent == null) {
      return false;
    }
    agent.leaving = true;
    LOG.info("agent {} asked to shut down", id);
    answerHeldPolls();
    return true;
  }

  @Override
  public synchronized void control(AgentMessage request, Callback<Message> reply) {
    Protocol.withAgent(request.getAgent(), () -> {
      switch (request.type()) {
        case REGISTER:
          register(request, reply);
          break;
        case HEARTBEAT:
          heartbeat((Heartbeat) request, reply);
          break;
        case GOODBYE:
          goodbye(request, reply);
          break;
        default:
          throw new IllegalStateException("not a control request: " + request.type().wire());
      }
    });
  }

  private void register(AgentMessage request, Callback<Message> reply) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent != null && agent.incarnation.equals(request.getIncarnation())) {
      agent.seen(clock);
      answerHeld(agent, new Wait());
      send(reply, request.getTrace(), new Registered());
      return;
    }
    if (agent != null) {
      LOG.info("agent {} restarted: incarnation {} replaces {}", agent.id, request.getIncarnation(),
        agent.incarnation);
      remove(agent, "agent " + agent.id + " restarted", new Rejected("replaced"));
    }
    agents.put(request.getAgent(),
      new AgentRecord(request.getAgent(), request.getIncarnation(), clock));
    LOG.info("agent {} registered", request.getAgent());
    send(reply, request.getTrace(), new Registered());
  }

  private void heartbeat(Heartbeat request, Callback<Message> reply) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent == null) {
      send(reply, request.getTrace(), new Reregister());
      return;
    }
    if (!agent.incarnation.equals(request.getIncarnation())) {
      send(reply, request.getTrace(),
        new Rejected("incarnation " + request.getIncarnation() + " is stale"));
      return;
    }
    if (request.getSeq() <= agent.heartbeatSeq) {
      // Ignore delayed duplicates of older heartbeats so they cannot revert a newer agent status.
      send(reply, request.getTrace(), new Wait());
      return;
    }
    agent.heartbeatSeq = request.getSeq();
    agent.seen(clock);
    answerHeld(agent, new Wait());
    agent.reported = request.getStatus();
    apply(agent);
    answerHeldPolls();
    respond(agent, reply, request.getTrace());
  }

  private void goodbye(AgentMessage request, Callback<Message> reply) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent != null && agent.incarnation.equals(request.getIncarnation())) {
      LOG.info("agent {} said goodbye", agent.id);
      remove(agent, "agent " + agent.id + " left the fleet", new Wait());
    }
    send(reply, request.getTrace(), new Shutdown());
  }

  @Override
  public synchronized Message telemetry(Intervals batch) {
    long highest = 0;
    for (IntervalFrame frame : batch.getFrames()) {
      highest = Math.max(highest, frame.getSeq());
    }
    AgentRecord agent = agents.get(batch.getAgent());
    if (agent == null || !agent.incarnation.equals(batch.getIncarnation())) {
      return ack(batch, highest);
    }
    agent.seen(clock);
    RunRecord.SlotRecord slot = agent.binding;
    if (slot == null || !slot.matches(batch.getRunId(), batch.getStep())) {
      return ack(batch, highest);
    }
    long before = slot.heldSeq;
    // Telemetry frames arrive in sequence order; append the next contiguous frame, skip any
    // duplicates, and stop processing as soon as a sequence gap is encountered.
    for (IntervalFrame frame : batch.getFrames()) {
      if (frame.getSeq() == slot.heldSeq + 1) {
        slot.frames.add(frame);
        slot.heldSeq++;
      } else if (frame.getSeq() > slot.heldSeq + 1) {
        break;
      }
    }
    if (slot.heldSeq != before) {
      apply(agent);
      answerHeldPolls();
    }
    return ack(batch, slot.heldSeq);
  }

  private static Ack ack(Intervals batch, long heldSeq) {
    Ack ack = new Ack(batch.getRunId(), batch.getStep(), heldSeq);
    ack.setTrace(batch.getTrace());
    return ack;
  }

  /** Reconciles the status reported by an agent against the state of its bound execution slot. */
  private void apply(AgentRecord agent) {
    Status status = agent.reported;
    RunRecord.SlotRecord slot = agent.binding;
    if (slot == null) {
      return;
    }
    if (!slot.matches(status.getRunId(), status.getStep())) {
      if (status.getPhase() == Phase.IDLE && slot.state != RunView.SlotState.ASSIGNED) {
        fail(slot, "agent " + agent.id + " went idle during its slot");
      }
      return;
    }
    switch (status.getPhase()) {
      case READY:
        if (slot.state == RunView.SlotState.ASSIGNED) {
          slot.state = RunView.SlotState.READY;
          startIfReady(slot.step);
        }
        break;
      case DONE:
        if (slot.state == RunView.SlotState.RUNNING) {
          slot.finalSeq = status.getFinalSeq();
          if (slot.heldSeq >= slot.finalSeq) {
            slot.state = RunView.SlotState.DONE;
            completeIfDone(slot.step);
          }
        }
        break;
      case FAILED:
        fail(slot, "agent " + agent.id + ": " + status.getError());
        break;
      default:
        break;
    }
  }

  /**
   * Computes the next control command required to drive the agent toward its desired state, or
   * returns null if no action is currently needed.
   */
  private Message command(AgentRecord agent) {
    if (agent.leaving) {
      return new Shutdown();
    }
    Status status = agent.reported;
    RunRecord.SlotRecord slot = agent.binding;
    if (
      status.getRunId() != null
        && (slot == null || !slot.matches(status.getRunId(), status.getStep()))
    ) {
      return status.getPhase().active()
        ? new Cancel(status.getRunId())
        : new Release(status.getRunId());
    }
    if (slot == null) {
      return null;
    }
    if (slot.state == RunView.SlotState.ASSIGNED && status.getPhase() == Phase.IDLE) {
      return new Assign(slot.assignment);
    }
    if (slot.state == RunView.SlotState.RUNNING && status.getPhase() == Phase.READY) {
      return new Start(slot.step.run.runId, slot.step.index, slot.step.startAtMs);
    }
    return null;
  }

  /**
   * Returns the next control command for the agent if it differs from the last command sent during
   * the current hold cycle, or null otherwise.
   */
  private Message fresh(AgentRecord agent) {
    Message command = command(agent);
    if (command == null) {
      return null;
    }
    String encoded = Codec.encode(command);
    if (encoded.equals(agent.lastCommand)) {
      return null;
    }
    agent.lastCommand = encoded;
    return command;
  }

  /**
   * Dispatches a fresh command immediately if one is available, or holds the poll request open
   * awaiting a future state change.
   */
  private void respond(AgentRecord agent, Callback<Message> reply, String trace) {
    Message command = fresh(agent);
    if (command != null) {
      send(reply, trace, command);
      return;
    }
    agent.held = reply;
    agent.heldTrace = trace;
    agent.holdTimer = clock.schedule(Protocol.HOLD_MS, () -> holdEnded(agent));
  }

  /**
   * Completes an expired long-poll hold by sending the current command even if it repeats the
   * previously sent command, or by replying with a wait message when no command applies.
   */
  private synchronized void holdEnded(AgentRecord agent) {
    if (agent.held == null || agents.get(agent.id) != agent) {
      return;
    }
    Message command = command(agent);
    agent.lastCommand = command == null ? null : Codec.encode(command);
    answerHeld(agent, command == null ? new Wait() : command);
  }

  /** Evaluates all currently held polls and completes each one that has a new command to send. */
  private void answerHeldPolls() {
    for (AgentRecord agent : agents.values()) {
      if (agent.held == null) {
        continue;
      }
      Message command = fresh(agent);
      if (command != null) {
        answerHeld(agent, command);
      }
    }
  }

  private void answerHeld(AgentRecord agent, Message message) {
    if (agent.held == null) {
      return;
    }
    Callback<Message> held = agent.held;
    String trace = agent.heldTrace;
    agent.held = null;
    agent.holdTimer.cancel();
    Protocol.withAgent(agent.id, () -> send(held, trace, message));
  }

  private static void send(Callback<Message> reply, String trace, Message message) {
    message.setTrace(trace);
    reply.completed(message);
  }

  /**
   * Removes an agent record from the registry, completes its held poll with the given reply, and
   * fails any execution slot currently bound to the agent.
   */
  private void remove(AgentRecord agent, String reason, Message heldReply) {
    agents.remove(agent.id);
    answerHeld(agent, heldReply);
    if (agent.binding != null) {
      fail(agent.binding, reason);
    }
    answerHeldPolls();
  }

  private void reap() {
    synchronized (this) {
      if (closed) {
        return;
      }
      long now = clock.monotonicMs();
      for (AgentRecord agent : new ArrayList<>(agents.values())) {
        long silentMs = now - agent.lastSeenMonoMs;
        if (silentMs > Protocol.DEADLINE_MS) {
          Protocol.withAgent(agent.id, () -> {
            LOG.warn("agent {} evicted: no request for {} ms", agent.id, silentMs);
            remove(agent, "agent " + agent.id + " missed its heartbeat deadline", new Reregister());
          });
        }
      }
      reaper = clock.schedule(Protocol.REAP_PERIOD_MS, this::reap);
    }
  }

  /**
   * Returns a diagnostic message explaining why the specified step cannot allocate its required
   * free agents right now, or null if sufficient free agents are available.
   */
  private String shortage(RunSpec spec, int index) {
    int free = 0;
    for (AgentRecord agent : agents.values()) {
      if (agent.free()) {
        free++;
      }
    }
    int needed = spec.getSteps().get(index).getAgents();
    return free >= needed
      ? null
      : "step " + index + " needs " + needed + " agents but " + free + " are free";
  }

  /**
   * Allocates free agents in operational identifier order and binds them to the execution slots of
   * the specified step.
   */
  private void bindStep(RunRecord run, int index) {
    String shortage = shortage(run.spec, index);
    if (shortage != null) {
      run.current = index;
      run.steps.get(index).state = RunView.StepState.FAILED;
      failRun(run, shortage);
      return;
    }
    StepSpec spec = run.spec.getSteps().get(index);
    RunRecord.StepRecord step = run.steps.get(index);
    run.current = index;
    step.state = RunView.StepState.RUNNING;
    int slot = 0;
    for (AgentRecord agent : agents.values()) {
      if (slot == spec.getAgents()) {
        break;
      }
      if (agent.free()) {
        Assignment assignment = new Assignment(run.runId, index,
          DataPlaneIdentity.derive(run.spec.getSeed(), slot, spec.getStride()), spec.getDocuments(),
          spec.getProperties());
        agent.binding = new RunRecord.SlotRecord(step, agent.id, assignment);
        step.slots.add(agent.binding);
        slot++;
      }
    }
    LOG.info("run {} step {} assigned to {} agents", run.runId, index, spec.getAgents());
  }

  /** Transitions all slots of the step to the running state once every slot has reported READY. */
  private void startIfReady(RunRecord.StepRecord step) {
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.state != RunView.SlotState.READY) {
        return;
      }
    }
    step.startAtMs = clock.millis();
    for (RunRecord.SlotRecord slot : step.slots) {
      slot.state = RunView.SlotState.RUNNING;
    }
    LOG.info("run {} step {} started", step.run.runId, step.index);
  }

  /**
   * Marks the step complete once every bound slot reaches the DONE state, and either binds the
   * subsequent step or finishes the run.
   */
  private void completeIfDone(RunRecord.StepRecord step) {
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.state != RunView.SlotState.DONE) {
        return;
      }
    }
    step.state = RunView.StepState.COMPLETE;
    unbind(step);
    RunRecord run = step.run;
    LOG.info("run {} step {} complete", run.runId, step.index);
    if (step.index + 1 < run.steps.size()) {
      bindStep(run, step.index + 1);
    } else {
      run.state = RunView.State.COMPLETE;
      LOG.info("run {} complete", run.runId);
    }
  }

  private void fail(RunRecord.SlotRecord slot, String reason) {
    slot.state = RunView.SlotState.FAILED;
    slot.error = reason;
    slot.step.state = RunView.StepState.FAILED;
    failRun(slot.step.run, reason);
  }

  /**
   * Transitions the run to the failed state and unbinds the agents assigned to its current step so
   * that they receive a cancel or release command on their next poll.
   */
  private void failRun(RunRecord run, String reason) {
    if (run.state != RunView.State.RUNNING) {
      return;
    }
    run.state = RunView.State.FAILED;
    run.reason = reason;
    unbind(run.steps.get(run.current));
    LOG.warn("run {} failed: {}", run.runId, reason);
  }

  private void unbind(RunRecord.StepRecord step) {
    for (RunRecord.SlotRecord slot : step.slots) {
      AgentRecord agent = agents.get(slot.agent);
      if (agent != null && agent.binding == slot) {
        agent.binding = null;
      }
    }
  }

}
