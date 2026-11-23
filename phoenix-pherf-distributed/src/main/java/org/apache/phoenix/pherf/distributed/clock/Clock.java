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
package org.apache.phoenix.pherf.distributed.clock;

/**
 * Provides the time source and timer scheduling abstraction for both the controller and the agent.
 * Production deployments use {@link SystemClock}, whereas tests inject a virtual clock that
 * advances only when explicitly driven by the test.
 */
public interface Clock {
  /**
   * Returns the current epoch time in milliseconds (ms), which is used for run ids, registration
   * times, and start epochs.
   */
  long millis();

  /**
   * Returns a monotonic timestamp in milliseconds (ms) measured from an arbitrary origin whose
   * absolute value has no external meaning. Internal deadlines rely on this monotonic clock so that
   * wall-clock adjustments or steps do not prematurely evict an agent.
   */
  long monotonicMs();

  /** Schedules the given action to execute once after the specified delay has elapsed. */
  Timer schedule(long delayMs, Runnable action);

  /** Represents a handle to a scheduled timer task that has not yet executed. */
  interface Timer {
    /** Cancels the scheduled timer so that its action will not run if it has not yet started. */
    void cancel();
  }
}
