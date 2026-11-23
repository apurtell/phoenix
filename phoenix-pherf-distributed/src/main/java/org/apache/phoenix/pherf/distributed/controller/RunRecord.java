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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;

/**
 * Tracks the controller state for a single run, its constituent steps, and their slots. While the
 * run is active, its bundle holds the merged fleet histograms and the raw interval logs; once the
 * run finishes, the record retains only the state required to build its view.
 */
final class RunRecord {
  final String runId;
  final RunSpec spec;
  /** Holds the controller clock timestamp when the run was submitted. */
  final long startedMs;
  final List<StepRecord> steps = new ArrayList<>();
  /**
   * Holds the active merge state and output files for the run until the bundle is finalized and
   * written to disk, after which this reference is cleared to null.
   */
  RunBundle bundle;
  RunView.State state = RunView.State.RUNNING;
  String reason;
  /** Holds the index of the current step in the run sequence. */
  int current;
  /**
   * True once at least one step has been launched, indicating that the run may have sent database
   * traffic.
   */
  boolean started;

  RunRecord(String runId, RunSpec spec, long startedMs, RunBundle bundle) {
    this.runId = runId;
    this.spec = spec;
    this.startedMs = startedMs;
    this.bundle = bundle;
    for (int i = 0; i < spec.getSteps().size(); i++) {
      steps.add(new StepRecord(this, i));
    }
  }

  RunView view() {
    List<RunView.StepView> stepViews = new ArrayList<>();
    for (StepRecord step : steps) {
      List<RunView.SlotView> slotViews = new ArrayList<>();
      for (SlotRecord slot : step.slots) {
        slotViews.add(slot.view());
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
   * sample counters and time bounds of the interval frames that the controller holds for it. The
   * frames themselves are written directly to the bundle and are not retained in memory.
   */
  static final class SlotRecord {
    final StepRecord step;
    final String agent;
    /** Holds the incarnation identifier reported by the agent when the slot was bound. */
    final String incarnation;
    final Assignment assignment;
    /** Holds the cumulative sample count held for each key, indexed by log tag. */
    final Map<String, Long> counts = new LinkedHashMap<>();
    RunView.SlotState state = RunView.SlotState.ASSIGNED;
    String error;
    /**
     * Records the frame total mismatch that caused the slot to fail (indicating a lost or
     * duplicated frame), or null if no mismatch occurred.
     */
    String lostTelemetry;
    long heldSeq;
    long finalSeq;
    /** Holds the cumulative sample count across all currently held frames. */
    long samples;
    /**
     * Holds the start timestamp of the first held frame and the end timestamp of the last held
     * frame, both measured on the agent clock.
     */
    Long firstStartMs;
    Long lastEndMs;
    /**
     * Holds the final report received with the DONE transition until the run bundle is written to
     * disk.
     */
    FinalReport report;
    /** Holds the raw interval log writer for the slot while the log remains open. */
    RunBundle.SlotLog log;
    /**
     * Holds the clock estimate of the agent captured when the start epoch was set, together with
     * the target start timestamp on the agent's clock.
     */
    Long offsetMs;
    Long delayMs;
    Long startAtMs;
    /**
     * Holds the timestamp when the agent's start gate opened, translated to the controller clock.
     */
    Long dispatchMs;

    SlotRecord(StepRecord step, String agent, String incarnation, Assignment assignment) {
      this.step = step;
      this.agent = agent;
      this.incarnation = incarnation;
      this.assignment = assignment;
    }

    RunView.SlotView view() {
      return new RunView.SlotView(assignment.getIdentity(), agent, state, error,
        assignment.getShare(), heldSeq, finalSeq, samples, counts, offsetMs, delayMs, startAtMs,
        dispatchMs);
    }

    /**
     * Translates an agent timestamp to the controller clock by subtracting the clock offset of the
     * slot, using an offset of 0 when the slot has no clock estimate.
     */
    long controllerMs(long agentMs) {
      return agentMs - (offsetMs == null ? 0 : offsetMs);
    }

    boolean matches(String runId, int stepIndex) {
      return step.run.runId.equals(runId) && step.index == stepIndex;
    }
  }
}
