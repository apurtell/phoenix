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
package org.apache.phoenix.pherf.distributed.protocol;

import java.util.Objects;

/**
 * Represents the portion of the step-level workload totals allocated to a single slot. A null value
 * indicates a quantity that the step does not specify, causing the slot to fall back to the value
 * defined in its documents. Unlike other quantities, the run duration is not divided across slots;
 * each slot executes for the full duration of the step.
 */
public final class Share {
  private Long durationMs;
  private Long threads;
  private Long rate;
  private Long rows;

  private Share() {
  }

  public Share(Long durationMs, Long threads, Long rate, Long rows) {
    this.durationMs = durationMs;
    this.threads = threads;
    this.rate = rate;
    this.rows = rows;
  }

  /** Returns the run duration in milliseconds (ms) passed to the -runDurationMs option. */
  public Long getDurationMs() {
    return durationMs;
  }

  /** Returns the concurrency level for each query set passed to the -threadCount option. */
  public Long getThreads() {
    return threads;
  }

  /** Returns the peak rate in operations per second passed to the -targetRate option. */
  public Long getRate() {
    return rate;
  }

  /** Returns the row count passed to the -rowCountOverride option. */
  public Long getRows() {
    return rows;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Share)) {
      return false;
    }
    Share that = (Share) other;
    return Objects.equals(durationMs, that.durationMs) && Objects.equals(threads, that.threads)
      && Objects.equals(rate, that.rate) && Objects.equals(rows, that.rows);
  }

  @Override
  public int hashCode() {
    return Objects.hash(durationMs, threads, rate, rows);
  }

  @Override
  public String toString() {
    return "share (durationMs " + durationMs + ", threads " + threads + ", rate " + rate + ", rows "
      + rows + ")";
  }
}
