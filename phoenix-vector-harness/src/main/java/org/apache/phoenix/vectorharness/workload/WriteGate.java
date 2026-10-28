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
 * Concurrency gate coordinating write operations with quiesced sampling windows. Writers acquire
 * read locks during mutations. Quiesced sampling acquires write locks to temporarily pause ingest
 * and ensure stable database state during evaluation.
 */
public final class WriteGate {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final AtomicLong closedNanos = new AtomicLong();
  private final AtomicLong closures = new AtomicLong();
  private volatile long closedAt;
  private volatile long lastOpenedNanos = Long.MIN_VALUE;

  /** Enters the write gate; returns false if interrupted. */
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

  /** Closes write gate with timeout, waiting for active writes to finish. */
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
    // Update before unlocking to ensure subsequent writers observe latest timestamp
    lastOpenedNanos = now;
    lock.writeLock().unlock();
  }

  /**
   * Returns true if gate reopened after the specified timestamp.
   */
  public boolean reopenedAfter(long nanos) {
    long opened = lastOpenedNanos;
    return opened != Long.MIN_VALUE && opened - nanos > 0;
  }

  /** Returns timestamp in nanoseconds of most recent reopening, or Long.MIN_VALUE. */
  public long lastOpenedNanos() {
    return lastOpenedNanos;
  }

  /** Returns cumulative nanoseconds that the gate was held closed. */
  public long closedNanos() {
    return closedNanos.get();
  }

  public long closures() {
    return closures.get();
  }
}
