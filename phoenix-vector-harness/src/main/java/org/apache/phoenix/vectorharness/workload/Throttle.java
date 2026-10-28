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
package org.apache.phoenix.vectorharness.workload;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed rate rate limiter shared across concurrent workers to schedule operations. Supports
 * open-loop dispatch timestamping to prevent coordinated omission in latency measurements.
 */
public final class Throttle {

  private final double ratePerSec;
  private final long intervalNanos;
  private long nextNanos;

  public Throttle(double ratePerSec) {
    this.ratePerSec = ratePerSec;
    this.intervalNanos = ratePerSec > 0 ? Math.max(1L, (long) (1e9 / ratePerSec)) : 0L;
    this.nextNanos = System.nanoTime();
  }

  public boolean unthrottled() {
    return intervalNanos == 0;
  }

  public double ratePerSec() {
    return ratePerSec;
  }

  /** Claims the next scheduled time slot in nanoseconds. */
  public synchronized long claim() {
    if (intervalNanos == 0) {
      return System.nanoTime();
    }
    long slot = nextNanos;
    nextNanos += intervalNanos;
    return slot;
  }

  /** Claims n consecutive slots and returns scheduled timestamp of the first. */
  public synchronized long claim(int n) {
    if (intervalNanos == 0) {
      return System.nanoTime();
    }
    long slot = nextNanos;
    nextNanos += intervalNanos * n;
    return slot;
  }

  /** Claims next slot and parks calling thread until scheduled timestamp. */
  public long next() throws InterruptedException {
    long slot = claim();
    awaitSlot(slot);
    return slot;
  }

  /** Parks calling thread until specified target timestamp is reached. */
  public static void awaitSlot(long slot) throws InterruptedException {
    while (true) {
      long wait = slot - System.nanoTime();
      if (wait <= 0) {
        return;
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      LockSupport.parkNanos(Math.min(wait, TimeUnit.MILLISECONDS.toNanos(100)));
    }
  }

  /** Resets schedule timeline to current time, discarding elapsed slots. */
  public synchronized void reset() {
    nextNanos = Math.max(nextNanos, System.nanoTime());
  }
}
