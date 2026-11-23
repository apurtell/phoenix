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

import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Share;

/** Represents an immutable status snapshot of a single run, its steps, and their slots. */
public final class RunView {
  /** Defines the lifecycle states of a run. */
  public enum State {
    RUNNING,
    COMPLETE,
    /**
     * Indicates that the run failed before any of its steps were launched, ensuring that it sent no
     * database traffic.
     */
    ABORTED,
    /** Indicates that the run failed after at least one step had already been launched. */
    FAILED
  }

  /** Defines the lifecycle states of an individual step within a run. */
  public enum StepState {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED
  }

  /** Defines the lifecycle states of an assigned slot within a step. */
  public enum SlotState {
    ASSIGNED,
    READY,
    /** Indicates that the step was launched and the run child process is warming up. */
    LAUNCHED,
    /** Indicates that the run child process is holding at its start gate. */
    ARMED,
    /** Indicates that the slot has received its target start time. */
    RUNNING,
    DONE,
    FAILED,
    /**
     * Indicates that the run failed on another slot and this slot received a cancel or release
     * command.
     */
    CANCELLED;

    /**
     * Returns true while the slot's run child process may be active, specifically in the LAUNCHED,
     * ARMED, or RUNNING states.
     */
    public boolean launched() {
      return this == LAUNCHED || this == ARMED || this == RUNNING;
    }
  }

  private final String runId;
  private final State state;
  private final String reason;
  private final int step;
  private final List<StepView> steps;

  RunView(String runId, State state, String reason, int step, List<StepView> steps) {
    this.runId = runId;
    this.state = state;
    this.reason = reason;
    this.step = step;
    this.steps = Collections.unmodifiableList(steps);
  }

  public String getRunId() {
    return runId;
  }

  public State getState() {
    return state;
  }

  /**
   * Returns the failure reason when the run is in the ABORTED or FAILED state, or null otherwise.
   */
  public String getReason() {
    return reason;
  }

  /** Returns the index of the step that is currently active in the run. */
  public int getStep() {
    return step;
  }

  public List<StepView> getSteps() {
    return steps;
  }

  @Override
  public String toString() {
    return runId + " " + state + (reason == null ? "" : ": " + reason) + " at step " + step;
  }

  /** Represents an immutable status snapshot of a single step within a run. */
  public static final class StepView {
    private final int index;
    private final StepState state;
    private final long startAtMs;
    private final Long skewMs;
    private final List<SlotView> slots;

    StepView(int index, StepState state, long startAtMs, Long skewMs, List<SlotView> slots) {
      this.index = index;
      this.state = state;
      this.startAtMs = startAtMs;
      this.skewMs = skewMs;
      this.slots = Collections.unmodifiableList(slots);
    }

    public int getIndex() {
      return index;
    }

    public StepState getState() {
      return state;
    }

    /**
     * Returns the start epoch timestamp of the step on the controller clock in milliseconds, or 0
     * before the epoch is established.
     */
    public long getStartAtMs() {
      return startAtMs;
    }

    /**
     * Returns an upper bound on the start skew across the slots that received a start command once
     * every such slot has reported a dispatch timestamp, computed as the latest minus the earliest
     * dispatch timestamp plus the two largest error bounds (each equal to half the round-trip delay
     * plus 2 ms). Returns null when any slot lacks a clock sample or when the step has no start
     * gate.
     */
    public Long getSkewMs() {
      return skewMs;
    }

    public List<SlotView> getSlots() {
      return slots;
    }
  }

  /** Represents an immutable status snapshot of a single slot within a step. */
  public static final class SlotView {
    private final DataPlaneIdentity identity;
    private final String agent;
    private final SlotState state;
    private final String error;
    private final Share share;
    private final long heldSeq;
    private final long finalSeq;
    private final int frames;
    private final long samples;
    private final Long offsetMs;
    private final Long delayMs;
    private final Long startAtMs;
    private final Long dispatchMs;

    SlotView(DataPlaneIdentity identity, String agent, SlotState state, String error, Share share,
      long heldSeq, long finalSeq, int frames, long samples, Long offsetMs, Long delayMs,
      Long startAtMs, Long dispatchMs) {
      this.identity = identity;
      this.agent = agent;
      this.state = state;
      this.error = error;
      this.share = share;
      this.heldSeq = heldSeq;
      this.finalSeq = finalSeq;
      this.frames = frames;
      this.samples = samples;
      this.offsetMs = offsetMs;
      this.delayMs = delayMs;
      this.startAtMs = startAtMs;
      this.dispatchMs = dispatchMs;
    }

    public int getSlot() {
      return identity.getSlot();
    }

    /**
     * Returns the data-plane identity assigned to the slot, comprising the slot index, its seed,
     * its row offset, and its key prefix.
     */
    public DataPlaneIdentity getIdentity() {
      return identity;
    }

    public String getAgent() {
      return agent;
    }

    public SlotState getState() {
      return state;
    }

    public String getError() {
      return error;
    }

    /** Returns the portion of the step workload totals allocated for this slot to execute. */
    public Share getShare() {
      return share;
    }

    /** Returns the highest frame sequence number held by the controller with no gap below it. */
    public long getHeldSeq() {
      return heldSeq;
    }

    /** Returns the final sequence number reported by the agent with DONE, or 0 if not reported. */
    public long getFinalSeq() {
      return finalSeq;
    }

    /** Returns the total number of interval frames currently held for this slot. */
    public int getFrames() {
      return frames;
    }

    /** Returns the cumulative sample count across all currently held frames for this slot. */
    public long getSamples() {
      return samples;
    }

    /**
     * Returns the estimated clock offset of the agent when the step start epoch was established, or
     * null if not set.
     */
    public Long getOffsetMs() {
      return offsetMs;
    }

    /**
     * Returns the round-trip delay associated with the agent's clock offset estimate, or null if
     * unavailable; the dispatch timestamp has an error bound of at most delay / 2 + 2 ms.
     */
    public Long getDelayMs() {
      return delayMs;
    }

    /**
     * Returns the target start timestamp delivered to the agent on its own clock, or null if not
     * set.
     */
    public Long getStartAtMs() {
      return startAtMs;
    }

    /**
     * Returns the timestamp when the agent's start gate opened on the controller clock, or null if
     * not yet reported.
     */
    public Long getDispatchMs() {
      return dispatchMs;
    }
  }
}
