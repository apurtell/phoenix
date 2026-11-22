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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A deterministic scheduler clock that advances only when explicitly driven by a test. Thread waits
 * complete exclusively upon receiving an explicit signal, and {@link #advanceTo(long)} wakes the
 * dispatcher thread and returns only after the dispatcher has observed the updated timestamp. This
 * utility also provides deterministic synchronization helpers for tests, where each wait terminates
 * at an exact scheduler state and the {@link #GUARD_MS} timeout guard serves solely to fail a test
 * that hangs.
 */
final class FakeClock implements OpenLoopScheduler.Clock {
  static final long GUARD_MS = 30_000;
  private volatile long now;
  private OpenLoopScheduler scheduler;

  FakeClock(long start) {
    this.now = start;
  }

  /** Binds the scheduler instance that {@link #advanceTo(long)} wakes upon time advancement. */
  void attach(OpenLoopScheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public long nowMicros() {
    return now;
  }

  @Override
  public void await(Condition condition, long deadlineMicros) throws InterruptedException {
    condition.await();
  }

  /**
   * Advances the clock to the specified timestamp and blocks until the dispatcher completes a pass
   * that observes the new time, or until the scheduler stops.
   */
  void advanceTo(long micros) {
    now = micros;
    long passes = scheduler.wake();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_MS);
    while (scheduler.passes() <= passes && !scheduler.isDone()) {
      pause(deadline, "a dispatcher pass at " + micros);
    }
  }

  /**
   * Polls scheduler state snapshots until the target predicate matches, validating every observed
   * snapshot against the invariant check before returning the matching state.
   */
  static OpenLoopState awaitState(OpenLoopScheduler scheduler, Predicate<OpenLoopState> match,
    Consumer<OpenLoopState> check) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_MS);
    while (true) {
      OpenLoopState state = scheduler.state();
      check.accept(state);
      if (match.test(state)) {
        return state;
      }
      pause(deadline, "a state; last " + describe(state));
    }
  }

  static OpenLoopState awaitState(OpenLoopScheduler scheduler, Predicate<OpenLoopState> match) {
    return awaitState(scheduler, match, s -> assertIdentity(s));
  }

  /**
   * Verifies the accounting conservation identities across scheduler state counters: scheduled =
   * completed + dropped + aborted + pending, and pending = queue + in flight.
   */
  static void assertIdentity(OpenLoopState s) {
    assertEquals(describe(s), s.getScheduled(),
      s.getCompleted() + s.getDropped() + s.getAborted() + s.getPending());
    assertEquals(describe(s), s.getQueueDepth() + s.getInFlight(), s.getPending());
  }

  static String describe(OpenLoopState s) {
    return "phase=" + s.getPhase() + " stop=" + s.getStopReason() + " scheduled=" + s.getScheduled()
      + " completed=" + s.getCompleted() + " late=" + s.getLate() + " dropped=" + s.getDropped()
      + " aborted=" + s.getAborted() + " queue=" + s.getQueueDepth() + " inFlight="
      + s.getInFlight() + " driftUs=" + s.getDriftUs();
  }

  /** Verifies that no worker thread belonging to any open-loop scheduler remains alive. */
  static void assertNoWorkers() {
    List<String> live = new ArrayList<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().startsWith(OpenLoopScheduler.THREAD_PREFIX)) {
        live.add(thread.getName());
      }
    }
    assertEquals("Live workers: " + live, 0, live.size());
  }

  private static void pause(long deadline, String what) {
    if (System.nanoTime() > deadline) {
      fail("No " + what + " in " + GUARD_MS + " ms");
    }
    try {
      Thread.sleep(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while waiting for " + what);
    }
  }

  /** Executes {@link OpenLoopScheduler#run()} asynchronously on a dedicated dispatcher thread. */
  static final class Run {
    private final Thread thread;
    private volatile OpenLoopState result;
    private volatile Throwable error;

    Run(OpenLoopScheduler scheduler) {
      thread = new Thread(() -> {
        try {
          result = scheduler.run();
        } catch (Throwable t) {
          error = t;
        }
      }, "test-dispatcher");
      thread.setDaemon(true);
      thread.start();
    }

    void interrupt() {
      thread.interrupt();
    }

    /**
     * Awaits the completion of run() and returns its final state, failing the test if run()
     * terminated by throwing an exception.
     */
    OpenLoopState result() throws InterruptedException {
      join();
      if (error != null) {
        throw new AssertionError("run() threw", error);
      }
      return result;
    }

    /**
     * Awaits the completion of run() and returns the Throwable it threw, failing the test if run()
     * returned normally without throwing.
     */
    Throwable error() throws InterruptedException {
      join();
      if (error == null) {
        fail("run() returned " + describe(result));
      }
      return error;
    }

    private void join() throws InterruptedException {
      thread.join(GUARD_MS);
      assertFalse("run() did not end in " + GUARD_MS + " ms", thread.isAlive());
    }
  }
}
