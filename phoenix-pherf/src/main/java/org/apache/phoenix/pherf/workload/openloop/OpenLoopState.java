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
package org.apache.phoenix.pherf.workload.openloop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.HdrHistogram.Histogram;

/**
 * Represents a point-in-time snapshot of an open-loop scheduler. Because the scheduler captures
 * this state while holding its internal lock, all values reflect the same instant and the invariant
 * scheduled = completed + dropped + aborted + pending always holds. The late count represents a
 * subset of the executed operations, while pending equals queueDepth + inFlight and drops to 0
 * after the scheduler stops.
 * <p>
 * The final snapshot of each run is recorded as an entry in the "schedules" list of the JSON
 * latency report. The service and response histograms are omitted from that entry because the keyed
 * histograms in the report already hold the same samples.
 */
public final class OpenLoopState {
  /** Identifies the phase during which warmup operations run before the schedule starts. */
  public static final String WARMUP = "warmup";
  /** Identifies the active execution phase of the schedule. */
  public static final String STEADY = "steady";
  /** Identifies the terminal phase after the scheduler stops. */
  public static final String DONE = "done";
  /** Indicates that the scheduler stopped because the schedule completed and the queue emptied. */
  public static final String DURATION = "duration";
  /** Indicates that the scheduler stopped because the clock reached the configured stop time. */
  public static final String STOP_TIME = "stopTime";
  /** Indicates that the scheduler stopped because the stop predicate evaluated to true. */
  public static final String PREDICATE = "predicate";
  /** Indicates that the scheduler stopped because a caller cancelled the run. */
  public static final String CANCEL = "cancel";
  /** Indicates that the scheduler stopped because the dispatcher thread was interrupted. */
  public static final String INTERRUPT = "interrupt";
  /** Indicates that the scheduler stopped because an operation failed. */
  public static final String ERROR = "error";

  private final String scenario;
  private final int querySet;
  private final List<String> operations;
  private final int concurrency;
  private final String phase;
  private final String stopReason;
  private final long startMs;
  private final long endMs;
  private final double targetRate;
  private final int queueDepth;
  private final int inFlight;
  private final long driftUs;
  private final long scheduled;
  private final long completed;
  private final long late;
  private final long dropped;
  private final long aborted;
  private final long pending;
  private final int maxQueueDepth;
  private final int maxInFlight;
  private final long maxDriftUs;
  private final transient Histogram service;
  private final transient Histogram response;

  /**
   * Captures a defensive copy of the current scheduler state. The caller must hold the scheduler
   * lock when invoking this constructor.
   */
  OpenLoopState(OpenLoopScheduler s, long nowUs) {
    scenario = s.scenario;
    querySet = s.querySet;
    operations = Collections.unmodifiableList(new ArrayList<>(s.operations));
    concurrency = s.concurrency;
    phase = s.phase;
    stopReason = s.stopReason;
    startMs = s.startMs;
    endMs = s.endMs;
    targetRate = s.targetRate(nowUs);
    queueDepth = s.queueDepth();
    inFlight = s.inFlight;
    driftUs = s.driftUs(nowUs);
    scheduled = s.scheduled;
    completed = s.completed;
    late = s.late;
    dropped = s.dropped;
    aborted = s.aborted;
    pending = queueDepth + inFlight;
    maxQueueDepth = s.maxQueueDepth;
    maxInFlight = s.maxInFlight;
    maxDriftUs = s.maxDriftUs;
    service = s.service.copy();
    response = s.response.copy();
  }

  /** Returns the name of the scenario associated with this scheduler run. */
  public String getScenario() {
    return scenario;
  }

  /** Returns the 1-based position of the query set within its enclosing scenario. */
  public int getQuerySet() {
    return querySet;
  }

  /** Returns the service-time keys for the queries in their order of arrival. */
  public List<String> getOperations() {
    return operations;
  }

  /** Returns the maximum number of concurrent workers, which also bounds the queue capacity. */
  public int getConcurrency() {
    return concurrency;
  }

  /**
   * Returns the current execution phase, which is {@link #WARMUP}, {@link #STEADY}, or
   * {@link #DONE}.
   */
  public String getPhase() {
    return phase;
  }

  /**
   * Returns the reason the scheduler stopped, or null if the scheduler has not yet stopped; once
   * stopped, the value matches one of the stop reason constants.
   */
  public String getStopReason() {
    return stopReason;
  }

  /**
   * Returns the timestamp when the schedule started, in milliseconds since the epoch, or 0 before
   * the schedule starts.
   */
  public long getStartMs() {
    return startMs;
  }

  /**
   * Returns the timestamp when the scheduler stopped, in milliseconds since the epoch, or 0 before
   * the scheduler stops.
   */
  public long getEndMs() {
    return endMs;
  }

  /**
   * Returns the currently configured target arrival rate in operations per second, or 0 when
   * outside the active schedule.
   */
  public double getTargetRate() {
    return targetRate;
  }

  /** Returns the number of operations currently waiting in the queue. */
  public int getQueueDepth() {
    return queueDepth;
  }

  /** Returns the number of operations currently being executed by workers. */
  public int getInFlight() {
    return inFlight;
  }

  /**
   * Returns the scheduling lag in microseconds of the oldest due operation that has not yet started
   * execution, or 0 when no due operation is waiting.
   */
  public long getDriftUs() {
    return driftUs;
  }

  /** Returns the total number of operations that have been placed into the queue. */
  public long getScheduled() {
    return scheduled;
  }

  /** Returns the total number of operations that have completed execution successfully. */
  public long getCompleted() {
    return completed;
  }

  /**
   * Returns the total number of executed operations whose scheduling lag exceeded the configured
   * threshold (the delayed operations).
   */
  public long getLate() {
    return late;
  }

  /**
   * Returns the total number of operations that were skipped without execution because their
   * scheduling lag exceeded the configured limit.
   */
  public long getDropped() {
    return dropped;
  }

  /**
   * Returns the total number of operations that failed during execution plus any queued operations
   * that were discarded when the scheduler halted.
   */
  public long getAborted() {
    return aborted;
  }

  /** Returns the total number of pending operations, defined as queueDepth + inFlight. */
  public long getPending() {
    return pending;
  }

  /** Returns the peak queue depth observed during the run. */
  public int getMaxQueueDepth() {
    return maxQueueDepth;
  }

  /** Returns the peak number of concurrent in-flight operations observed during the run. */
  public int getMaxInFlight() {
    return maxInFlight;
  }

  /**
   * Returns the maximum scheduling lag in microseconds observed across all operations at the moment
   * a worker dequeued them for execution.
   */
  public long getMaxDriftUs() {
    return maxDriftUs;
  }

  /**
   * Returns a defensive copy of the histogram recording service times for completed operations, in
   * microseconds.
   */
  public Histogram getService() {
    return service;
  }

  /**
   * Returns a defensive copy of the histogram recording total response times (scheduling lag plus
   * service time) for completed operations, in microseconds.
   */
  public Histogram getResponse() {
    return response;
  }
}
