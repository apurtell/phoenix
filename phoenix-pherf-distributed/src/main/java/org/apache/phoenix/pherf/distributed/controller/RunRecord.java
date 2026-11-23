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

import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;

/** Tracks the controller state for a single run, its constituent steps, and their slots. */
final class RunRecord {
  final String runId;
  final RunSpec spec;
  final List<StepRecord> steps = new ArrayList<>();
  RunView.State state = RunView.State.RUNNING;
  String reason;
  /** Holds the index of the current step in the run sequence. */
  int current;
  /**
   * True once at least one step has been launched, indicating that the run may have sent database
   * traffic.
   */
  boolean started;

  RunRecord(String runId, RunSpec spec) {
    this.runId = runId;
    this.spec = spec;
    for (int i = 0; i < spec.getSteps().size(); i++) {
      steps.add(new StepRecord(this, i));
    }
  }

  RunView view() {
    List<RunView.StepView> stepViews = new ArrayList<>();
    for (StepRecord step : steps) {
      List<RunView.SlotView> slotViews = new ArrayList<>();
      for (SlotRecord slot : step.slots) {
        slotViews.add(new RunView.SlotView(slot.assignment.getIdentity(), slot.agent, slot.state,
          slot.error, slot.assignment.getShare(), slot.heldSeq, slot.finalSeq, slot.frames.size(),
          slot.samples, slot.offsetMs, slot.delayMs, slot.startAtMs, slot.dispatchMs));
      }
      stepViews
        .add(new RunView.StepView(step.index, step.state, step.startAtMs, step.skewMs, slotViews));
    }
    return new RunView(runId, state, reason, current, stepViews);
  }

  /** Represents a single step, whose slots are populated once the step has started to assign. */
  static final class StepRecord {
    final RunRecord run;
    final int index;
    final List<SlotRecord> slots = new ArrayList<>();
    RunView.StepState state = RunView.StepState.PENDING;
    /** Holds the step start epoch on the controller clock, or 0 before the epoch is set. */
    long startAtMs;
    /**
     * Holds the upper bound on the start skew across started slots once every started slot has
     * reported a dispatch timestamp.
     */
    Long skewMs;

    StepRecord(RunRecord run, int index) {
      this.run = run;
      this.index = index;
    }
  }

  /**
   * Represents a single execution slot, capturing the assignment for one agent along with the
   * interval frames the controller currently holds for it.
   */
  static final class SlotRecord {
    final StepRecord step;
    final String agent;
    final Assignment assignment;
    final List<IntervalFrame> frames = new ArrayList<>();
    RunView.SlotState state = RunView.SlotState.ASSIGNED;
    String error;
    long heldSeq;
    long finalSeq;
    /** Holds the cumulative sample count across all currently held frames. */
    long samples;
    /**
     * Holds the agent's clock offset and delay estimate captured when the start epoch was set,
     * along with the resulting start time on the agent's clock.
     */
    Long offsetMs;
    Long delayMs;
    Long startAtMs;
    /**
     * Holds the timestamp when the agent's start gate opened, translated to the controller clock.
     */
    Long dispatchMs;

    SlotRecord(StepRecord step, String agent, Assignment assignment) {
      this.step = step;
      this.agent = agent;
      this.assignment = assignment;
    }

    boolean matches(String runId, int stepIndex) {
      return step.run.runId.equals(runId) && step.index == stepIndex;
    }
  }
}
