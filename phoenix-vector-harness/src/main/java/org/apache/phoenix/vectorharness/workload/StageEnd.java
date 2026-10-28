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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Decides when the workload of a steady window stops. Usually the workload stops at the planned
 * window end. A disruption that is in flight at the window end extends the stage for reads until
 * the disruption and its recovery wait end. The recovery of a disruption can need the reads, for
 * example queries that open the HNSW indexes of moved regions. No disruption starts after the
 * window end. A stop request ends the stage at once. Without a stop request, all work that is due
 * before the window end runs, also when it runs late. No write that is due at or after the window
 * end runs, because the supply of new vectors of a window fits its planned length.
 */
final class StageEnd {

  private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private final long windowEndNanos;
  private final List<String> skipped = new ArrayList<>();
  /** The window end is seen with no disruption in flight. */
  private volatile boolean closed;
  /** An abort or the extension limit stops all work at once. */
  private volatile boolean stopped;
  private String inFlight;
  private String extendedBy;
  private long extendedUntilNanos;
  private String overrunBy;

  StageEnd(long windowEndNanos) {
    this.windowEndNanos = windowEndNanos;
  }

  /**
   * Starts a disruption if the window is open.
   * @return false if the window ended; the stage then records the disruption as skipped
   */
  synchronized boolean begin(String disruption, long nowNanos) {
    if (closed || stopped || nowNanos - windowEndNanos >= 0) {
      skipped.add(disruption);
      return false;
    }
    inFlight = disruption;
    return true;
  }

  /**
   * Ends the disruption in flight. A disruption that ends after the window end extends the stage. A
   * disruption that ends after a stop request does not change the extension.
   */
  synchronized void finish(long nowNanos) {
    if (inFlight != null && !stopped && nowNanos - windowEndNanos > 0) {
      extendedBy = inFlight;
      extendedUntilNanos = nowNanos;
    }
    inFlight = null;
  }

  /** Records a disruption that does not start because the stage stops. */
  synchronized void skip(String disruption) {
    skipped.add(disruption);
  }

  /**
   * Returns true if the reads stop at the given time. After the first true result, the stage is
   * closed and the result stays true.
   */
  boolean ended(long nowNanos) {
    if (closed || stopped) {
      return true;
    }
    if (nowNanos - windowEndNanos < 0) {
      return false;
    }
    synchronized (this) {
      if (inFlight != null) {
        return false;
      }
      closed = true;
      return true;
    }
  }

  /**
   * Returns true if the reads stop before a slot. A slot before the window end always runs, unless
   * a stop request ends the stage. A slot after the window end can belong to an extended stage.
   * This method then waits for the window end, because a disruption can still start before it.
   */
  boolean reached(long slotNanos) throws InterruptedException {
    if (stopped) {
      return true;
    }
    if (slotNanos - windowEndNanos < 0) {
      return false;
    }
    long wait;
    while (!closed && !stopped && (wait = windowEndNanos - System.nanoTime()) > 0) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      LockSupport.parkNanos(Math.min(wait, PARK_NANOS));
    }
    return ended(System.nanoTime());
  }

  /**
   * Returns true if the writes stop before a slot. A slot before the window end always runs, unless
   * a stop request ends the stage. No slot at or after the window end runs, also in an extension.
   * Thus this method never waits.
   */
  boolean writesReached(long slotNanos) {
    return stopped || slotNanos - windowEndNanos >= 0;
  }

  /** Stops the stage at once, also while a disruption is in flight. */
  void stop() {
    stopped = true;
  }

  /**
   * Stops the stage because the disruption in flight passes the extension limit. The extension then
   * ends now, also if the disruption ends later.
   * @return the disruption in flight, or null if no disruption is in flight
   */
  synchronized String overrun(long nowNanos) {
    if (inFlight != null) {
      extendedBy = inFlight;
      extendedUntilNanos = nowNanos;
      overrunBy = inFlight;
    }
    stopped = true;
    return inFlight;
  }

  /**
   * Returns the time until which the harness waits for the workload threads. The time moves while a
   * disruption is in flight, but the extension has a limit. A disruption that hangs thus cannot
   * keep the stage open without end.
   */
  synchronized long joinDeadline(long nowNanos, long graceNanos, long maxExtensionNanos) {
    long from = inFlight != null ? nowNanos : windowEndNanos;
    if (extendedBy != null && extendedUntilNanos - from > 0) {
      from = extendedUntilNanos;
    }
    long limit = windowEndNanos + maxExtensionNanos;
    if (from - limit > 0) {
      from = limit;
    }
    return from + graceNanos;
  }

  /** Returns the disruption in flight, or null. */
  synchronized String inFlight() {
    return inFlight;
  }

  /** Returns the time in seconds that the stage runs after the window end for a disruption. */
  synchronized double extensionSeconds() {
    return extendedBy == null ? 0 : (extendedUntilNanos - windowEndNanos) / 1e9;
  }

  /** Returns the disruption that extends the stage, or null. */
  synchronized String extendedBy() {
    return extendedBy;
  }

  /** Returns the disruption that passes the extension limit, or null. */
  synchronized String overrunBy() {
    return overrunBy;
  }

  /** Returns the scheduled disruptions that do not start. */
  synchronized List<String> skipped() {
    return new ArrayList<>(skipped);
  }
}
