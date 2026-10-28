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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The gate through which every write passes, so that quiesced sampling can stop writes. Writers
 * hold the shared side for the length of one mutation, from before its write to after its commit
 * and its truth update; the sampler takes the exclusive side, which waits for writes in flight to
 * commit and holds new ones until it reopens the gate. The time the gate stays closed is the gap in
 * write throughput that quiesced sampling costs.
 */
public final class WriteGate {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final AtomicLong closedNanos = new AtomicLong();
  private final AtomicLong closures = new AtomicLong();
  private volatile long closedAt;
  private volatile long lastOpenedNanos = Long.MIN_VALUE;

  /** Enters the gate for one write; returns false if interrupted. */
  public boolean enter() {
    try {
      lock.readLock().lockInterruptibly();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public void exit() {
    lock.readLock().unlock();
  }

  /** Closes the gate, waiting for writes in flight; returns false on timeout. */
  public boolean close(long timeout, TimeUnit unit) throws InterruptedException {
    if (!lock.writeLock().tryLock(timeout, unit)) {
      return false;
    }
    closedAt = System.nanoTime();
    closures.incrementAndGet();
    return true;
  }

  public void open() {
    long now = System.nanoTime();
    closedNanos.addAndGet(now - closedAt);
    // Set before the unlock, so that a writer the reopening admits sees it.
    lastOpenedNanos = now;
    lock.writeLock().unlock();
  }

  /**
   * Returns true if the gate reopened after an instant, so that a write scheduled at that instant
   * may have waited for the gate rather than for the system under test.
   */
  public boolean reopenedAfter(long nanos) {
    long opened = lastOpenedNanos;
    return opened != Long.MIN_VALUE && opened - nanos > 0;
  }

  /** Returns the System.nanoTime of the last reopening, or Long.MIN_VALUE if never closed. */
  public long lastOpenedNanos() {
    return lastOpenedNanos;
  }

  /** Returns the total time the gate was closed. */
  public long closedNanos() {
    return closedNanos.get();
  }

  public long closures() {
    return closures.get();
  }
}
