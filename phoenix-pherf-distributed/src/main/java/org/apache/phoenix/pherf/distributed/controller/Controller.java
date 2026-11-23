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
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assign;
import org.apache.phoenix.pherf.distributed.protocol.Cancel;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Launch;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Phase;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
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
 * the held polls. An identical command is dispatched at most once per hold cycle. Because an agent
 * keeps a single control request in flight, a newer heartbeat that still requires the previous
 * command indicates that its reply was lost, and the controller immediately retransmits that
 * command once. Cancel is the exception to this rule because the agent continues to report its
 * active phase until the workload stops. Any heartbeat that is not newer than the last one applied
 * receives a wait reply, ensuring that a delayed duplicate cannot revert a more recent status
 * update.
 * <p>
 * A background reaper evicts any agent that remains silent for longer than
 * {@link Protocol#DEADLINE_MS} of monotonic time. When an agent is evicted or departs, its assigned
 * slot fails, causing the corresponding run to fail as well.
 * <p>
 * Every control reply carries the controller timestamps for when the request arrived and when the
 * reply was sent, and every heartbeat echoes the timestamps from the previous exchange so that the
 * controller can estimate the clock offset of each agent. A step initiates execution in two phases:
 * once all slots reach READY, each agent receives a launch command so its run child process warms
 * up and holds at its start gate (ARMED). Once no slot remains in LAUNCHED, the controller
 * establishes a single future start epoch for the step and delivers it to each agent as a start
 * command translated to that agent's clock. The step skew represents an upper bound on the actual
 * spread of the dispatch times, calculated as the measured spread plus the two largest error bounds
 * across the slots.
 * <p>
 * The controller retains each frame sequence of a slot exactly once without gaps, decoding each
 * retained frame once to accumulate its sample count.
 */
public final class Controller implements AgentChannel {
  private static final Logger LOG = LoggerFactory.getLogger(Controller.class);
  private static final DateTimeFormatter RUN_ID =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

  /**
   * Defines the maximum single-slot clock error bound in milliseconds that still guarantees two
   * slots remain within the 50 ms start-skew bound.
   */
  private static final long MAX_PROVEN_ERROR_MS = 25;

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
   * Initiates a new run across available free agents and returns its assigned run identifier,
   * assuming that {@link RunSpec#parse} has already validated the execution plan.
   * @throws IllegalStateException when fewer free agents are available than step 0 requires.
   */
  public synchronized String submit(RunSpec spec) {
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
    long rxMs = clock.millis();
    Protocol.withAgent(request.getAgent(), () -> {
      switch (request.type()) {
        case REGISTER:
          register(request, reply, rxMs);
          break;
        case HEARTBEAT:
          heartbeat((Heartbeat) request, reply, rxMs);
          break;
        case GOODBYE:
          goodbye(request, reply, rxMs);
          break;
        default:
          throw new IllegalStateException("not a control request: " + request.type().wire());
      }
    });
  }

  private void register(AgentMessage request, Callback<Message> reply, long rxMs) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent != null && agent.incarnation.equals(request.getIncarnation())) {
      agent.seen(clock);
      answerHeld(agent, new Wait());
      send(reply, request.getTrace(), new Registered(), rxMs);
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
    send(reply, request.getTrace(), new Registered(), rxMs);
  }

  private void heartbeat(Heartbeat request, Callback<Message> reply, long rxMs) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent == null) {
      send(reply, request.getTrace(), new Reregister(), rxMs);
      return;
    }
    if (!agent.incarnation.equals(request.getIncarnation())) {
      send(reply, request.getTrace(),
        new Rejected("incarnation " + request.getIncarnation() + " is stale"), rxMs);
      return;
    }
    if (request.getSeq() <= agent.heartbeatSeq) {
      // Ignore delayed duplicates of older heartbeats so they cannot revert a newer agent status.
      send(reply, request.getTrace(), new Wait(), rxMs);
      return;
    }
    agent.heartbeatSeq = request.getSeq();
    agent.seen(clock);
    if (request.getClock() != null) {
      agent.clockFilter.add(request.getClock());
    }
    answerHeld(agent, new Wait());
    agent.reported = request.getStatus();
    apply(agent);
    answerHeldPolls();
    respond(agent, reply, request.getTrace(), rxMs);
  }

  private void goodbye(AgentMessage request, Callback<Message> reply, long rxMs) {
    AgentRecord agent = agents.get(request.getAgent());
    if (agent != null && agent.incarnation.equals(request.getIncarnation())) {
      LOG.info("agent {} said goodbye", agent.id);
      remove(agent, "agent " + agent.id + " left the fleet", new Wait());
    }
    send(reply, request.getTrace(), new Shutdown(), rxMs);
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
        long count;
        try {
          count = HistogramBlob.decode(frame.getHistogram()).getTotalCount();
        } catch (ProtocolException e) {
          String reason = "frame " + frame.getSeq() + " of run " + batch.getRunId() + " step "
            + batch.getStep() + " has a bad histogram: " + e.getMessage();
          LOG.warn("agent {}: {}", agent.id, reason);
          // Keep the agent registered in the fleet; the acknowledgment stops advancing held frames
          // and a subsequent cancel command terminates the active workload.
          fail(slot, "agent " + agent.id + ": " + reason);
          answerHeldPolls();
          return ack(batch, slot.heldSeq);
        }
        slot.frames.add(frame);
        slot.samples += count;
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
      cancelledSlotFailed(agent, status);
      return;
    }
    if (!slot.matches(status.getRunId(), status.getStep())) {
      if (status.getPhase() == Phase.IDLE && slot.state != RunView.SlotState.ASSIGNED) {
        fail(slot, "agent " + agent.id + " went idle during its slot");
      }
      return;
    }
    if (status.getStartedMs() > 0 && slot.startAtMs != null && slot.dispatchMs == null) {
      dispatched(slot, status.getStartedMs());
    }
    switch (status.getPhase()) {
      case READY:
        if (slot.state == RunView.SlotState.ASSIGNED) {
          slot.state = RunView.SlotState.READY;
          launchIfReady(slot.step);
        }
        break;
      case ARMED:
        if (slot.state == RunView.SlotState.LAUNCHED) {
          slot.state = RunView.SlotState.ARMED;
          startIfArmed(slot.step);
        }
        break;
      case DONE:
        if (slot.state.launched()) {
          slot.finalSeq = status.getFinalSeq();
          if (slot.heldSeq >= slot.finalSeq) {
            // A slot without a start gate transitions directly from LAUNCHED to DONE, unblocking
            // the remaining armed slots to start.
            boolean gated = slot.state != RunView.SlotState.LAUNCHED;
            slot.state = RunView.SlotState.DONE;
            if (!gated) {
              startIfArmed(slot.step);
            }
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
   * Records a subsequent failure reported for a slot that was already cancelled due to the failure
   * of its run, ensuring that the run view preserves the error details of every failed slot while
   * the overall run failure reason retains the initial error.
   */
  private void cancelledSlotFailed(AgentRecord agent, Status status) {
    RunRecord run = status.getRunId() == null ? null : runs.get(status.getRunId());
    if (
      status.getPhase() != Phase.FAILED || run == null || status.getStep() < 0
        || status.getStep() >= run.steps.size()
    ) {
      return;
    }
    for (RunRecord.SlotRecord slot : run.steps.get(status.getStep()).slots) {
      if (slot.agent.equals(agent.id) && slot.state == RunView.SlotState.CANCELLED) {
        slot.state = RunView.SlotState.FAILED;
        slot.error = "agent " + agent.id + ": " + status.getError();
      }
    }
  }

  /**
   * Determines the control command for an agent, or returns null when the agent has no pending
   * command.
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
    if (slot.state == RunView.SlotState.LAUNCHED && status.getPhase() == Phase.READY) {
      return new Launch(slot.step.run.runId, slot.step.index);
    }
    if (slot.state == RunView.SlotState.RUNNING && status.getPhase() == Phase.ARMED) {
      return new Start(slot.step.run.runId, slot.step.index, slot.startAtMs);
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
    agent.resent = false;
    return command;
  }

  /**
   * Returns the previous control command for a single retransmission when a newer heartbeat still
   * requires it, or null otherwise, ensuring that a lost reply incurs only one retry without
   * holding the poll.
   */
  private Message lost(AgentRecord agent) {
    Message command = command(agent);
    if (command == null || command instanceof Cancel || agent.resent) {
      return null;
    }
    agent.resent = true;
    return command;
  }

  /**
   * Dispatches a fresh or retransmitted lost command immediately if one is available, or holds the
   * poll request open awaiting a future state transition.
   */
  private void respond(AgentRecord agent, Callback<Message> reply, String trace, long rxMs) {
    Message command = fresh(agent);
    if (command == null) {
      command = lost(agent);
    }
    if (command != null) {
      send(reply, trace, command, rxMs);
      return;
    }
    agent.held = reply;
    agent.heldTrace = trace;
    agent.heldRxMs = rxMs;
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
    Protocol.withAgent(agent.id, () -> send(held, trace, message, agent.heldRxMs));
  }

  /**
   * Completes a control reply stamped with the request arrival timestamp and the reply transmission
   * timestamp.
   */
  private void send(Callback<Message> reply, String trace, Message message, long rxMs) {
    message.setTrace(trace);
    message.setStamps(rxMs, clock.millis());
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
        agent.binding =
          new RunRecord.SlotRecord(step, agent.id, run.spec.assignment(run.runId, index, slot));
        step.slots.add(agent.binding);
        slot++;
      }
    }
    LOG.info("run {} step {} assigned to {} agents", run.runId, index, spec.getAgents());
  }

  /**
   * Transitions all slots of the step to the LAUNCHED state once every slot has reported READY,
   * allowing the run child processes to begin generating database traffic from this point onward.
   */
  private void launchIfReady(RunRecord.StepRecord step) {
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.state != RunView.SlotState.READY) {
        return;
      }
    }
    step.run.started = true;
    for (RunRecord.SlotRecord slot : step.slots) {
      slot.state = RunView.SlotState.LAUNCHED;
    }
    LOG.info("run {} step {} launched", step.run.runId, step.index);
  }

  /**
   * Establishes the step start epoch once no slot remains in LAUNCHED and at least one slot is
   * ARMED, computing the epoch as the current time plus {@link Protocol#START_LEAD_MS} plus the
   * maximum round-trip delay among the armed agents. Each armed slot records its agent's clock
   * estimate and receives the start epoch translated to that agent's clock.
   */
  private void startIfArmed(RunRecord.StepRecord step) {
    List<RunRecord.SlotRecord> armed = new ArrayList<>();
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.state == RunView.SlotState.LAUNCHED) {
        return;
      }
      if (slot.state == RunView.SlotState.ARMED) {
        armed.add(slot);
      }
    }
    if (armed.isEmpty()) {
      return;
    }
    long maxDelayMs = 0;
    for (RunRecord.SlotRecord slot : armed) {
      long[] best = agents.get(slot.agent).clockFilter.best();
      if (best != null) {
        slot.offsetMs = best[0];
        slot.delayMs = best[1];
        maxDelayMs = Math.max(maxDelayMs, slot.delayMs);
      } else {
        slot.offsetMs = 0L;
      }
    }
    step.startAtMs = clock.millis() + Protocol.START_LEAD_MS + maxDelayMs;
    for (RunRecord.SlotRecord slot : armed) {
      slot.startAtMs = step.startAtMs + slot.offsetMs;
      slot.state = RunView.SlotState.RUNNING;
      if (slot.delayMs == null || errorBoundMs(slot.delayMs) > MAX_PROVEN_ERROR_MS) {
        LOG.warn(
          "run {} step {}: the clock error of agent {} is not known to be within {} ms"
            + " (round trip delay {} ms), so the 50 ms start bound is not proven for it",
          step.run.runId, step.index, slot.agent, MAX_PROVEN_ERROR_MS, slot.delayMs);
      }
    }
    LOG.info("run {} step {} starts at {} on the controller clock", step.run.runId, step.index,
      step.startAtMs);
  }

  /**
   * Computes the error bound of a dispatch timestamp on the controller clock as half the round-trip
   * delay of the clock sample plus 2 ms to account for millisecond clock resolution.
   */
  private static long errorBoundMs(long delayMs) {
    return delayMs / 2 + 2;
  }

  /**
   * Records the timestamp when the agent's start gate opened, translated to the controller clock.
   * Once every slot that received a start command has reported its dispatch timestamp, the step
   * computes its start skew as the spread of those dispatch timestamps plus the two largest error
   * bounds. Because translating the dispatch timestamp subtracts the same estimated offset that was
   * added to the target start time, the measured spread does not reflect offset estimation error;
   * only the error bounds account for it. If any slot lacks a clock sample, it has no error bound
   * and the step records no skew.
   */
  private void dispatched(RunRecord.SlotRecord slot, long startedMs) {
    slot.dispatchMs = startedMs - slot.offsetMs;
    RunRecord.StepRecord step = slot.step;
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    long largest = 0;
    long next = 0;
    int started = 0;
    for (RunRecord.SlotRecord other : step.slots) {
      if (other.startAtMs == null) {
        continue;
      }
      if (other.dispatchMs == null || other.delayMs == null) {
        return;
      }
      min = Math.min(min, other.dispatchMs);
      max = Math.max(max, other.dispatchMs);
      long bound = errorBoundMs(other.delayMs);
      next = Math.max(next, Math.min(largest, bound));
      largest = Math.max(largest, bound);
      started++;
    }
    step.skewMs = max - min + (started > 1 ? largest + next : 0);
    LOG.info("run {} step {} start skew at most {} ms", step.run.runId, step.index, step.skewMs);
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
   * Terminates the run and unbinds the agents assigned to its current step so that they receive a
   * cancel or release command. The run transitions to ABORTED if no step has been launched, or to
   * FAILED otherwise, and every slot that is not already DONE or FAILED is marked CANCELLED. No
   * slot is retried or bound again, and subsequent steps do not run.
   */
  private void failRun(RunRecord run, String reason) {
    if (run.state != RunView.State.RUNNING) {
      return;
    }
    run.state = run.started ? RunView.State.FAILED : RunView.State.ABORTED;
    run.reason = reason;
    RunRecord.StepRecord step = run.steps.get(run.current);
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.state != RunView.SlotState.DONE && slot.state != RunView.SlotState.FAILED) {
        slot.state = RunView.SlotState.CANCELLED;
      }
    }
    unbind(step);
    LOG.warn("run {} {}: {}", run.runId, run.state, reason);
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
