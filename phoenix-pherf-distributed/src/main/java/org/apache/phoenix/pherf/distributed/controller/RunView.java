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

/** Represents an immutable status snapshot of a single run, its steps, and their slots. */
public final class RunView {
  /** Defines the lifecycle states of a run. */
  public enum State {
    RUNNING,
    COMPLETE,
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
    RUNNING,
    DONE,
    FAILED
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

  /** Returns the failure reason when the run is in the FAILED state, or null otherwise. */
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
    private final List<SlotView> slots;

    StepView(int index, StepState state, long startAtMs, List<SlotView> slots) {
      this.index = index;
      this.state = state;
      this.startAtMs = startAtMs;
      this.slots = Collections.unmodifiableList(slots);
    }

    public int getIndex() {
      return index;
    }

    public StepState getState() {
      return state;
    }

    /** Returns the start epoch timestamp of the step in milliseconds, or 0 before it starts. */
    public long getStartAtMs() {
      return startAtMs;
    }

    public List<SlotView> getSlots() {
      return slots;
    }
  }

  /** Represents an immutable status snapshot of a single slot within a step. */
  public static final class SlotView {
    private final int slot;
    private final String agent;
    private final SlotState state;
    private final String error;
    private final String prefix;
    private final long heldSeq;
    private final long finalSeq;
    private final int frames;

    SlotView(int slot, String agent, SlotState state, String error, String prefix, long heldSeq,
      long finalSeq, int frames) {
      this.slot = slot;
      this.agent = agent;
      this.state = state;
      this.error = error;
      this.prefix = prefix;
      this.heldSeq = heldSeq;
      this.finalSeq = finalSeq;
      this.frames = frames;
    }

    public int getSlot() {
      return slot;
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

    public String getPrefix() {
      return prefix;
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
  }
}
