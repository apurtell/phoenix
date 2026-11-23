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

import java.util.List;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;

/**
 * Defines the asynchronous execution contract for work assigned to an agent. Every method returns
 * immediately, delivering its results to the supplied listener on any thread, including
 * synchronously from within the call itself. Following a cancel request, a launched workload must
 * still invoke finished or failed once it stops, and a workload in prepare must still invoke
 * prepared. Any runtime exception thrown from a method call immediately fails the work of the
 * agent.
 */
public interface Workload {
  /**
   * Validates the assignment and initializes the resources required to execute it, using assignment
   * documents and properties that have already been resolved.
   */
  void prepare(Assignment assignment, Listener listener);

  /**
   * Launches the previously prepared assignment immediately. The workload warms up and invokes
   * armed when it holds at its start gate prior to its first steady dispatch; a workload without a
   * start gate never invokes armed.
   */
  void launch(Assignment assignment, Listener listener);

  /**
   * Opens the start gate of the armed run at the specified startAtMs timestamp as measured by the
   * agent clock.
   */
  void start(String runId, long startAtMs);

  /** Requests cancellation and halts all active work associated with the specified run. */
  void cancel(String runId);

  /** Receives lifecycle callbacks and telemetry results produced by a single assignment. */
  interface Listener {
    /**
     * Reports the outcome of the preparation phase, where an empty error list indicates that the
     * assignment is valid and ready to run.
     */
    void prepared(List<String> errors);

    /** Signals that the launched workload has reached and is holding at its start gate. */
    void armed();

    /** Signals that the start gate opened at the specified timestamp on the agent clock. */
    void started(long startedMs);

    /** Delivers a single interval histogram recorded for a given operation and phase. */
    void interval(String operation, String phase, long startMs, long endMs, Histogram histogram);

    /**
     * Signals that the workload has completed execution after emitting its final interval and
     * delivers its final report. The report may be null only following a cancellation; otherwise, a
     * null report causes the workload to fail.
     */
    void finished(FinalReport report);

    /** Signals that workload execution terminated abnormally due to an error. */
    void failed(String reason);
  }
}
