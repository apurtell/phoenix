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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.identity.SlotTokens;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assign;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Cancel;
import org.apache.phoenix.pherf.distributed.protocol.Goodbye;
import org.apache.phoenix.pherf.distributed.protocol.Heartbeat;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Phase;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.distributed.protocol.Register;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.protocol.Release;
import org.apache.phoenix.pherf.distributed.protocol.Start;
import org.apache.phoenix.pherf.distributed.protocol.Status;
import org.apache.phoenix.pherf.distributed.transport.AgentTransport;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.apache.phoenix.pherf.distributed.transport.RejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the event-driven state machine for the agent runtime. Replies, acknowledgments,
 * timers, and workload results each execute a single transition under one monitor so that the
 * identical implementation runs in both production and simulation environments.
 * <p>
 * The control loop dispatches the next request as soon as a reply arrives: it sends registration
 * requests until the controller accepts the agent, follows with heartbeats carrying the complete
 * agent status, and transmits a goodbye message when the agent stops. In parallel, the telemetry
 * loop transmits unacknowledged frames starting from the lowest sequence number, batching at most
 * {@link Protocol#MAX_FRAMES} per request. Any failed request is retried after
 * {@link Protocol#RETRY_MS}.
 * <p>
 * Workload completion proceeds in two phases so that the agent reports DONE only after receiving an
 * acknowledgment that covers the final frame. Incoming commands are idempotent for each run and
 * step. Following a cancellation, the agent continues to report its active phase until the
 * underlying workload halts, guaranteeing that two runs never overlap within the same process. If
 * the workload throws a runtime exception, the current work transitions to a failed state while the
 * communication loops continue operating.
 */
public final class AgentRuntime {
  private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

  /** Represents the externally observable lifecycle and execution state of the agent process. */
  public enum State {
    UNREGISTERED,
    IDLE,
    PREPARING,
    READY,
    RUNNING,
    DONE,
    FAILED,
    STOPPING,
    STOPPED
  }

  private enum Life {
    UNREGISTERED,
    REGISTERED,
    STOPPING,
    STOPPED
  }

  private final OperationalIdentity identity;
  private final Clock clock;
  private final AgentTransport transport;
  private final Workload workload;
  private final CountDownLatch stopped = new CountDownLatch(1);

  private Life life = Life.UNREGISTERED;
  private boolean started;
  private Work work;
  private boolean controlOut;
  private Clock.Timer controlRetry;
  private boolean telemetryOut;
  private Clock.Timer telemetryRetry;
  private long controlCount;
  private long telemetryCount;
  private int stopStatus;
  private Clock.Timer stopGrace;
  private Integer exitStatus;

  public AgentRuntime(OperationalIdentity identity, Clock clock, AgentTransport transport,
    Workload workload) {
    this.identity = identity;
    this.clock = clock;
    this.transport = transport;
    this.workload = workload;
  }

  public OperationalIdentity identity() {
    return identity;
  }

  /**
   * Starts the agent runtime and initiates the control loop by sending the first registration
   * request.
   */
  public void start() {
    call(() -> {
      if (started || life == Life.STOPPED) {
        return;
      }
      started = true;
      LOG.info("agent {} started", identity.getId());
      sendControl();
    });
  }

  /**
   * Initiates an orderly shutdown by cancelling active work, transmitting a goodbye message, and
   * terminating with exit status 0.
   */
  public void stop() {
    call(() -> beginStop(0));
  }

  public synchronized State state() {
    switch (life) {
      case UNREGISTERED:
        return State.UNREGISTERED;
      case STOPPING:
        return State.STOPPING;
      case STOPPED:
        return State.STOPPED;
      default:
        return work == null ? State.IDLE : State.valueOf(work.phase.name());
    }
  }

  /** Returns the terminal exit status of the agent, or null if the agent has not yet stopped. */
  public synchronized Integer exitStatus() {
    return exitStatus;
  }

  /**
   * Blocks until the agent reaches the stopped state, or returns false if the specified timeout
   * elapses first.
   */
  public boolean awaitStopped(long timeoutMs) throws InterruptedException {
    return stopped.await(timeoutMs, TimeUnit.MILLISECONDS);
  }

  /** Returns a snapshot of the current execution status to be reported in the next heartbeat. */
  public synchronized Status status() {
    if (work == null) {
      return Status.IDLE;
    }
    Assignment assignment = work.assignment;
    return new Status(work.phase, assignment.getRunId(), assignment.getStep(),
      work.phase == Phase.DONE ? work.finalSeq : 0, work.error);
  }

  /**
   * Executes a single state machine transition under the instance monitor with the operational
   * identity bound to the logging context.
   */
  private void call(Runnable step) {
    Protocol.withAgent(identity.getId(), () -> {
      synchronized (this) {
        step.run();
      }
    });
  }

  private void stamp(Message message, String stream, long n) {
    if (message instanceof AgentMessage) {
      ((AgentMessage) message).setAgent(identity.getId());
      ((AgentMessage) message).setIncarnation(identity.getIncarnation());
    }
    message.setTrace(identity.getId() + "/" + stream + "/" + n);
  }

  private void sendControl() {
    if (!started || controlOut || controlRetry != null || life == Life.STOPPED) {
      return;
    }
    long n = ++controlCount;
    Message request;
    if (life == Life.STOPPING) {
      request = new Goodbye();
    } else if (life == Life.UNREGISTERED) {
      request = new Register();
    } else {
      request = new Heartbeat(n, status());
    }
    stamp(request, Protocol.CONTROL, n);
    controlOut = true;
    transport.control(request, new Callback<Message>() {
      @Override
      public void completed(Message reply) {
        call(() -> controlReplied(request, reply));
      }

      @Override
      public void failed(IOException e) {
        call(() -> controlFailed(request, e));
      }
    });
  }

  private void controlReplied(Message request, Message reply) {
    controlOut = false;
    if (life == Life.STOPPED) {
      return;
    }
    if (request instanceof Goodbye) {
      finish(stopStatus);
      return;
    }
    try {
      handle(reply);
    } finally {
      // Ensure that an unexpected failure during a single transition does not halt the control
      // loop.
      sendControl();
    }
  }

  private void controlFailed(Message request, IOException e) {
    controlOut = false;
    if (life == Life.STOPPED) {
      return;
    }
    if (e instanceof RejectedException) {
      reject(((RejectedException) e).reason());
    } else if (request instanceof Goodbye) {
      LOG.warn("agent {} goodbye failed: {}", identity.getId(), e.toString());
      finish(stopStatus);
    } else {
      LOG.warn("agent {} control request {} failed: {}; again in {} ms", identity.getId(),
        request.getTrace(), e.toString(), Protocol.RETRY_MS);
      controlRetry = clock.schedule(Protocol.RETRY_MS, () -> call(() -> {
        controlRetry = null;
        sendControl();
      }));
    }
  }

  private void handle(Message reply) {
    switch (reply.type()) {
      case REGISTERED:
        if (life == Life.UNREGISTERED) {
          life = Life.REGISTERED;
          LOG.info("agent {} registered", identity.getId());
        }
        break;
      case ASSIGN:
        if (life == Life.REGISTERED) {
          assign(((Assign) reply).getAssignment());
        }
        break;
      case START:
        if (life == Life.REGISTERED) {
          start((Start) reply);
        }
        break;
      case CANCEL:
        if (work != null && work.assignment.getRunId().equals(((Cancel) reply).getRunId())) {
          cancelWork();
        }
        break;
      case RELEASE:
        if (
          work != null && work.assignment.getRunId().equals(((Release) reply).getRunId())
            && !work.phase.active()
        ) {
          idle();
        }
        break;
      case SHUTDOWN:
        LOG.info("agent {} shutdown requested", identity.getId());
        beginStop(0);
        break;
      case REREGISTER:
        if (life == Life.REGISTERED) {
          LOG.info("agent {} is unknown to the controller and registers again", identity.getId());
          life = Life.UNREGISTERED;
          cancelWork();
        }
        break;
      case REJECTED:
        reject(((Rejected) reply).getReason());
        break;
      default:
        break;
    }
  }

  private void assign(Assignment assignment) {
    if (work != null) {
      return;
    }
    Assignment resolved;
    try {
      resolved =
        new Assignment(assignment.getRunId(), assignment.getStep(), assignment.getIdentity(),
          SlotTokens.resolve(assignment.getDocuments(), assignment.getIdentity()),
          SlotTokens.resolve(assignment.getProperties(), assignment.getIdentity()));
    } catch (IllegalArgumentException e) {
      work = new Work(assignment);
      work.phase = Phase.FAILED;
      work.error = e.getMessage();
      LOG.warn("agent {} cannot resolve run {} step {}: {}", identity.getId(),
        assignment.getRunId(), assignment.getStep(), e.getMessage());
      return;
    }
    Work created = new Work(resolved);
    work = created;
    LOG.info("agent {} prepares run {} step {} as {}", identity.getId(), resolved.getRunId(),
      resolved.getStep(), resolved.getIdentity());
    safely(created, "prepare", () -> workload.prepare(resolved, created));
  }

  private void start(Start command) {
    Work current = work;
    if (
      current == null || current.phase != Phase.READY || current.cancelling
        || !current.matches(command.getRunId(), command.getStep())
    ) {
      return;
    }
    current.phase = Phase.RUNNING;
    LOG.info("agent {} starts run {} step {}", identity.getId(), command.getRunId(),
      command.getStep());
    safely(current, "start",
      () -> workload.start(current.assignment, command.getStartAtMs(), current));
  }

  /**
   * Invokes a workload operation and isolates any runtime exception by transitioning the current
   * work to a failed state so that the control loop continues and reports the failure to the
   * controller.
   */
  private void safely(Work current, String call, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      LOG.warn("agent {} workload {} failed for run {} step {}", identity.getId(), call,
        current.assignment.getRunId(), current.assignment.getStep(), e);
      current.phase = Phase.FAILED;
      current.error = call + " failed: " + e;
      current.unacked.clear();
    }
  }

  /**
   * Cancels the active workload assignment and transitions the agent back to the idle state once no
   * residual work remains.
   */
  private void cancelWork() {
    Work current = work;
    if (current == null) {
      return;
    }
    current.unacked.clear();
    if (current.phase == Phase.PREPARING || current.phase == Phase.RUNNING && !current.finishing) {
      if (!current.cancelling) {
        current.cancelling = true;
        LOG.info("agent {} cancels run {}", identity.getId(), current.assignment.getRunId());
        safely(current, "cancel", () -> workload.cancel(current.assignment.getRunId()));
      }
      return;
    }
    if (current.phase == Phase.READY) {
      safely(current, "cancel", () -> workload.cancel(current.assignment.getRunId()));
    }
    idle();
  }

  private void idle() {
    if (work != null) {
      LOG.info("agent {} is idle after run {} step {}", identity.getId(),
        work.assignment.getRunId(), work.assignment.getStep());
      work = null;
    }
  }

  private void pumpTelemetry() {
    Work current = work;
    if (
      telemetryOut || telemetryRetry != null || current == null || current.unacked.isEmpty()
        || life == Life.STOPPED
    ) {
      return;
    }
    List<IntervalFrame> frames = new ArrayList<>();
    for (IntervalFrame frame : current.unacked) {
      if (frames.size() == Protocol.MAX_FRAMES) {
        break;
      }
      frames.add(frame);
    }
    Intervals batch =
      new Intervals(current.assignment.getRunId(), current.assignment.getStep(), frames);
    stamp(batch, Protocol.TELEMETRY, ++telemetryCount);
    telemetryOut = true;
    transport.telemetry(batch, new Callback<Ack>() {
      @Override
      public void completed(Ack ack) {
        call(() -> acked(ack));
      }

      @Override
      public void failed(IOException e) {
        call(() -> telemetryFailed(batch, e));
      }
    });
  }

  private void acked(Ack ack) {
    telemetryOut = false;
    if (life == Life.STOPPED) {
      return;
    }
    try {
      Work current = work;
      if (current != null && current.matches(ack.getRunId(), ack.getStep())) {
        current.ackedSeq = Math.max(current.ackedSeq, ack.getHeldSeq());
        while (!current.unacked.isEmpty() && current.unacked.peek().getSeq() <= current.ackedSeq) {
          current.unacked.poll();
        }
        current.checkDone();
      }
    } finally {
      pumpTelemetry();
    }
  }

  private void telemetryFailed(Intervals batch, IOException e) {
    telemetryOut = false;
    if (life == Life.STOPPED) {
      return;
    }
    if (e instanceof RejectedException) {
      reject(((RejectedException) e).reason());
      return;
    }
    LOG.warn("agent {} telemetry request {} failed: {}; again in {} ms", identity.getId(),
      batch.getTrace(), e.toString(), Protocol.RETRY_MS);
    telemetryRetry = clock.schedule(Protocol.RETRY_MS, () -> call(() -> {
      telemetryRetry = null;
      pumpTelemetry();
    }));
  }

  private void beginStop(int status) {
    if (life == Life.STOPPING || life == Life.STOPPED) {
      return;
    }
    LOG.info("agent {} stopping", identity.getId());
    stopWork();
    life = Life.STOPPING;
    stopStatus = status;
    if (controlRetry != null) {
      controlRetry.cancel();
      controlRetry = null;
    }
    if (!started) {
      finish(status);
      return;
    }
    stopGrace = clock.schedule(Protocol.STOP_GRACE_MS, () -> call(() -> finish(stopStatus)));
    sendControl();
  }

  /** Requests cancellation of the current workload if it is still in an active phase. */
  private void stopWork() {
    Work current = work;
    if (current != null && current.phase.active() && !current.cancelling && !current.finishing) {
      current.cancelling = true;
      safely(current, "cancel", () -> workload.cancel(current.assignment.getRunId()));
    }
  }

  private void reject(String reason) {
    LOG.error("agent {} rejected by the controller: {}", identity.getId(), reason);
    stopWork();
    finish(2);
  }

  private void finish(int status) {
    if (life == Life.STOPPED) {
      return;
    }
    life = Life.STOPPED;
    exitStatus = status;
    for (Clock.Timer timer : new Clock.Timer[] { stopGrace, controlRetry, telemetryRetry }) {
      if (timer != null) {
        timer.cancel();
      }
    }
    transport.close();
    LOG.info("agent {} stopped with exit status {}", identity.getId(), status);
    stopped.countDown();
  }

  /** Encapsulates the execution state and collected results for a single workload assignment. */
  private final class Work implements Workload.Listener {
    final Assignment assignment;
    final ArrayDeque<IntervalFrame> unacked = new ArrayDeque<>();
    Phase phase = Phase.PREPARING;
    String error;
    boolean cancelling;
    boolean finishing;
    long nextSeq = 1;
    long ackedSeq;
    long finalSeq;

    Work(Assignment assignment) {
      this.assignment = assignment;
    }

    boolean matches(String runId, int step) {
      return assignment.getRunId().equals(runId) && assignment.getStep() == step;
    }

    /**
     * Transitions the assignment to DONE once workload execution has finished and the controller
     * acknowledgment covers the final telemetry frame.
     */
    void checkDone() {
      if (finishing && phase == Phase.RUNNING && ackedSeq >= finalSeq) {
        phase = Phase.DONE;
        LOG.info("agent {} finished run {} step {} with {} frames", identity.getId(),
          assignment.getRunId(), assignment.getStep(), finalSeq);
      }
    }

    /**
     * Returns false when callbacks arrive from a superseded assignment or after the agent has
     * stopped.
     */
    private boolean current() {
      return work == this && life != Life.STOPPED;
    }

    @Override
    public void prepared(List<String> errors) {
      call(() -> {
        if (!current() || phase != Phase.PREPARING) {
          return;
        }
        if (cancelling) {
          idle();
        } else if (errors.isEmpty()) {
          phase = Phase.READY;
        } else {
          phase = Phase.FAILED;
          error = String.join("; ", errors);
          LOG.warn("agent {} cannot run run {} step {}: {}", identity.getId(),
            assignment.getRunId(), assignment.getStep(), error);
        }
      });
    }

    @Override
    public void interval(String operation, String measurePhase, long startMs, long endMs,
      Histogram histogram) {
      String blob = HistogramBlob.encode(histogram);
      RecorderConfig recorder = RecorderConfig.of(histogram);
      call(() -> {
        if (!current() || phase != Phase.RUNNING || cancelling || finishing) {
          return;
        }
        unacked.add(
          new IntervalFrame(nextSeq++, operation, measurePhase, startMs, endMs, recorder, blob));
        pumpTelemetry();
      });
    }

    @Override
    public void finished() {
      call(() -> {
        if (!current()) {
          return;
        }
        if (cancelling) {
          idle();
        } else if (phase == Phase.RUNNING && !finishing) {
          finishing = true;
          finalSeq = nextSeq - 1;
          checkDone();
        }
      });
    }

    @Override
    public void failed(String reason) {
      call(() -> {
        if (!current()) {
          return;
        }
        if (cancelling) {
          idle();
        } else if (phase.active()) {
          phase = Phase.FAILED;
          error = reason;
          unacked.clear();
          LOG.warn("agent {} failed run {} step {}: {}", identity.getId(), assignment.getRunId(),
            assignment.getStep(), reason);
        }
      });
    }
  }
}
