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
 * Simulates the clock of an individual host by applying a fixed wall-clock skew to the shared
 * virtual clock and anchoring monotonic time to a host-specific origin. Timer scheduling delegates
 * directly to the underlying virtual clock because relative delays are unaffected by wall-clock
 * skew.
 */
public final class SkewedClock implements Clock {
  private final VirtualClock base;
  private final long skewMs;
  private final long monotonicOriginMs;

  /**
   * Constructs a skewed host clock backed by the given virtual clock.
   * @param skewMs            the offset of this host's wall clock relative to the virtual wall
   *                          clock, defined as this wall clock minus the virtual wall clock.
   * @param monotonicOriginMs the monotonic time reading of this host when virtual time is 0.
   */
  public SkewedClock(VirtualClock base, long skewMs, long monotonicOriginMs) {
    this.base = base;
    this.skewMs = skewMs;
    this.monotonicOriginMs = monotonicOriginMs;
  }

  public long skewMs() {
    return skewMs;
  }

  @Override
  public long millis() {
    return base.millis() + skewMs;
  }

  @Override
  public long monotonicMs() {
    return base.monotonicMs() + monotonicOriginMs;
  }

  @Override
  public Timer schedule(long delayMs, Runnable action) {
    return base.schedule(delayMs, action);
  }
}
