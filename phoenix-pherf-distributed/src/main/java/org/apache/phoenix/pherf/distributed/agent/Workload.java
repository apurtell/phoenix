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

/**
 * Defines the asynchronous execution contract for work assigned to an agent. Every method returns
 * immediately, delivering its results to the supplied listener on any thread, including
 * synchronously from within the call itself. Following a cancel request, a started workload must
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
   * Executes the previously prepared assignment beginning at the scheduled startAtMs timestamp.
   */
  void start(Assignment assignment, long startAtMs, Listener listener);

  /** Requests cancellation and halts all active work associated with the specified run. */
  void cancel(String runId);

  /** Receives lifecycle callbacks and telemetry results produced by a single assignment. */
  interface Listener {
    /**
     * Reports the outcome of the preparation phase, where an empty error list indicates that the
     * assignment is valid and ready to run.
     */
    void prepared(List<String> errors);

    /** Delivers a single interval histogram recorded for a given operation and phase. */
    void interval(String operation, String phase, long startMs, long endMs, Histogram histogram);

    /** Signals that the workload has completed execution after emitting its final interval. */
    void finished();

    /** Signals that workload execution terminated abnormally due to an error. */
    void failed(String reason);
  }
}
