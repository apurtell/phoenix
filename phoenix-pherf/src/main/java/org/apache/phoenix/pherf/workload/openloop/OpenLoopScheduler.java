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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.Micros;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches and executes workload operations according to the target arrival timestamps defined by
 * an {@link ArrivalSchedule}, rather than pacing execution by worker availability.
 * <p>
 * The thread invoking {@link #run()} serves as the dispatcher and enqueues each operation at its
 * intended time. The scheduler maintains dedicated daemon worker threads, allocating one thread per
 * unit of concurrency to consume operations from a queue whose capacity is bounded by that same
 * concurrency limit. When the queue reaches capacity, the dispatcher waits for room rather than
 * spilling operations into a secondary buffer, and any arrivals that come due during that wait
 * accumulate lag (drift).
 * <p>
 * Operation lag is defined as the elapsed time between an operation's intended dispatch timestamp
 * and the moment a worker dequeues it; because this interval includes any time the dispatcher spent
 * blocked on a full queue, latency measurements avoid coordinated omission. If an operation's lag
 * exceeds the configured limit, the DROP policy discards the operation without executing it,
 * whereas the LATE policy executes the operation and increments the late counter. Total response
 * time is the sum of lag and service time, so subtracting service time from response time yields
 * the client-side queueing delay. Completed response times are recorded under the
 * {@link Task#responseOperation(long)} latency key in the steady phase.
 * <p>
 * A single lock synchronizes access to the dispatch queue and all operational counters, ensuring
 * that every snapshot returned by {@link #state()} is exact.
 * <p>
 * Termination modes:
 * <ul>
 * <li>duration: All arrivals within the schedule window are released, the remaining queue drains
 * through the active lag policy, and all in-flight operations run to completion.</li>
 * <li>stopTime, predicate, cancel, interrupt, and error act as immediate halts: no new arrivals are
 * released, queued operations are cleared and marked as aborted, and in-flight operations finish.
 * Any arrivals that were already due but held back by a full queue are counted as both scheduled
 * and aborted. In addition, cancel and interrupt actively interrupt the worker threads.</li>
 * </ul>
 * Before {@link #run()} returns or throws an exception, every stop mode waits to join all worker
 * threads and publishes the final scheduler state to the latency report.
 */
public final class OpenLoopScheduler {
  /** Thread name prefix assigned to the daemon worker threads spawned by the scheduler. */
  public static final String THREAD_PREFIX = "pherf-open-loop-";
  /** Maximum time in seconds that {@link #cancel()} waits for {@link #run()} to terminate. */
  static final long CANCEL_GRACE_SECONDS = 30;
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenLoopScheduler.class);
  private static final long NONE = Long.MAX_VALUE;

  /** Monotonic microsecond time source and condition awaiting abstraction for the scheduler. */
  public interface Clock {
    /** Production clock backed by {@link Micros#now()}, which tracks the EnvironmentEdge. */
    Clock MICROS = new Clock() {
      @Override
      public long nowMicros() {
        return Micros.now();
      }

      @Override
      public void await(Condition condition, long deadlineMicros) throws InterruptedException {
        if (deadlineMicros == Long.MAX_VALUE) {
          condition.await();
          return;
        }
        long leftUs = deadlineMicros - Micros.now();
        if (leftUs > 0) {
          condition.awaitNanos(TimeUnit.MICROSECONDS.toNanos(leftUs));
        }
      }
    };

    /**
     * Returns the current clock reading in microseconds; only differences between readings carry
     * meaning.
     */
    long nowMicros();

    /**
     * Blocks on the specified condition, whose associated lock must be held by the caller, until
     * either signaled or the microsecond deadline is reached. A deadline of Long.MAX_VALUE waits
     * indefinitely for a signal, and the caller must re-read the clock upon waking.
     */
    void await(Condition condition, long deadlineMicros) throws InterruptedException;
  }

  /**
   * Represents the unit of work for a single operation, invoked concurrently by multiple worker
   * threads.
   */
  public interface Task {
    /**
     * Executes a single operation and returns its measured service time in microseconds (us).
     * Warmup operations execute outside the timed arrival schedule, with sequence numbers counting
     * independently from 0 within each worker thread.
     */
    long execute(long sequence, boolean warmup) throws Exception;

    /** Returns the latency recorder key used to report the response time of an operation. */
    String responseOperation(long sequence);
  }

  private static final class Operation {
    private final long sequence;
    private final long intendedUs;

    Operation(long sequence, long intendedUs) {
      this.sequence = sequence;
      this.intendedUs = intendedUs;
    }
  }

  private final ArrivalSchedule schedule;
  final int concurrency;
  private final long maxLagUs;
  private final boolean drop;
  private final long warmupPerWorker;
  private final Task task;
  final String scenario;
  final int querySet;
  final List<String> operations;
  private final Clock clock;
  private long stopAtUs = Long.MAX_VALUE;
  private BooleanSupplier stopWhen;

  private final ReentrantLock lock = new ReentrantLock();
  // Worker threads block on the work condition, while the dispatcher thread blocks on wake.
  private final Condition work = lock.newCondition();
  private final Condition wake = lock.newCondition();
  private final CountDownLatch done = new CountDownLatch(1);
  private final List<Thread> workers = new ArrayList<>();

  // All fields declared below are guarded by the scheduler lock.
  private final ArrayDeque<Operation> queue;
  String phase = OpenLoopState.WARMUP;
  String stopReason;
  long startMs;
  long endMs;
  long lastTakeMs;
  int inFlight;
  long scheduled;
  long completed;
  long late;
  long dropped;
  long aborted;
  int maxQueueDepth;
  int maxInFlight;
  long maxDriftUs;
  final Histogram service = newHistogram();
  final Histogram response = newHistogram();
  private long t0;
  private long nextDueUs = NONE;
  private boolean started;
  private boolean cancelled;
  private boolean halted;
  private boolean closed;
  private int warming;
  private Throwable failure;
  private long passes;

  /**
   * Constructs a scheduler from a configuration validated by QueryExecutor.checkOpenLoop. When
   * maxLagMs is omitted, no lag limit is enforced; when lagPolicy is omitted, the policy defaults
   * to LATE.
   * @param warmupPerWorker the number of unscheduled warmup operations each worker executes before
   *                        the arrival schedule begins.
   * @param querySet        the 1-based ordinal position of the query set within its scenario.
   * @param operations      the service-time latency keys included in the schedule report.
   */
  OpenLoopScheduler(OpenLoop openLoop, int concurrency, long warmupPerWorker, Task task,
    String scenario, int querySet, List<String> operations, Clock clock) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("The concurrency is less than 1: " + concurrency);
    }
    this.schedule = ArrivalSchedule.of(openLoop);
    this.concurrency = concurrency;
    this.maxLagUs = openLoop.getMaxLagMs() == null ? Long.MAX_VALUE : openLoop.getMaxLagMs() * 1000;
    this.drop = openLoop.getLagPolicy() == OpenLoop.LagPolicy.DROP;
    this.warmupPerWorker = warmupPerWorker;
    this.task = task;
    this.scenario = scenario;
    this.querySet = querySet;
    this.operations = operations;
    this.clock = clock;
    this.queue = new ArrayDeque<>(concurrency);
  }

  /** Creates an open-loop scheduler backed by the default production microsecond clock. */
  public static OpenLoopScheduler of(OpenLoop openLoop, int concurrency, long warmupPerWorker,
    Task task, String scenario, int querySet, List<String> operations) {
    return new OpenLoopScheduler(openLoop, concurrency, warmupPerWorker, task, scenario, querySet,
      operations, Clock.MICROS);
  }

  /** Returns the declared duration window of the arrival schedule in milliseconds (ms). */
  long durationMs() {
    return schedule.durationUs() / 1000;
  }

  /**
   * Configures the scheduler to halt the run once the clock reaches the specified reading in
   * microseconds (us). This method must be invoked before calling {@link #run()}.
   */
  public OpenLoopScheduler stopAt(long clockMicros) {
    this.stopAtUs = clockMicros;
    return this;
  }

  /**
   * Configures the scheduler to halt when the supplied predicate evaluates to true. The dispatcher
   * evaluates this predicate outside the scheduler lock after each wake cycle. Must be invoked
   * before calling {@link #run()}.
   */
  public OpenLoopScheduler stopWhen(BooleanSupplier predicate) {
    this.stopWhen = predicate;
    return this;
  }

  /**
   * Executes the worker warmup phase followed by the timed arrival schedule on the calling thread
   * until a stop condition occurs. If {@link #cancel()} has already been called prior to startup,
   * the run halts immediately.
   * @return the final scheduler state when stopping due to duration, stopTime, predicate, or
   *         cancel.
   * @throws InterruptedException if the calling dispatcher thread is interrupted during execution.
   * @throws Exception            the first exception thrown by a failed operation when stopping due
   *                              to an error.
   */
  public OpenLoopState run() throws Exception {
    lock.lock();
    try {
      if (started) {
        throw new IllegalStateException("The scheduler has already run");
      }
      started = true;
      if (cancelled) {
        halt(OpenLoopState.CANCEL, false);
      } else {
        warming = concurrency;
        try {
          for (int i = 0; i < concurrency; i++) {
            Thread worker = new Thread(this::work, THREAD_PREFIX + (i + 1));
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
          }
        } catch (Throwable t) {
          // Recording the failure halts any workers already started so the teardown path can join
          // them cleanly.
          keepFailure(t);
        }
      }
    } finally {
      lock.unlock();
    }
    boolean interrupted = false;
    try {
      dispatch();
    } catch (InterruptedException e) {
      interrupted = true;
      haltWithLock(OpenLoopState.INTERRUPT, true);
    } catch (RuntimeException | Error e) {
      lock.lock();
      try {
        keepFailure(e);
      } finally {
        lock.unlock();
      }
    }
    interrupted |= joinWorkers();
    OpenLoopState last;
    Throwable error;
    lock.lock();
    try {
      if (stopReason == null) {
        stopReason = OpenLoopState.DURATION;
      }
      phase = OpenLoopState.DONE;
      endMs = EnvironmentEdgeManager.currentTimeMillis();
      nextDueUs = NONE;
      last = new OpenLoopState(this, clock.nowMicros());
      error = OpenLoopState.ERROR.equals(stopReason) ? failure : null;
    } finally {
      lock.unlock();
    }
    try {
      LatencyRecorders.addSchedule(last);
    } finally {
      done.countDown();
    }
    if (OpenLoopState.INTERRUPT.equals(last.getStopReason())) {
      throw new InterruptedException("The open loop schedule of query set " + querySet
        + " of scenario " + scenario + " was interrupted");
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (error instanceof Exception) {
      throw (Exception) error;
    }
    if (error != null) {
      throw (Error) error;
    }
    return last;
  }

  /**
   * Halts the schedule and interrupts all active worker threads. This method blocks until
   * {@link #run()} terminates and records its final state in the latency report, or returns
   * immediately if run() has not yet started. Because an in-flight operation that ignores thread
   * interruption could otherwise prevent run() from stopping, the wait is bounded by
   * {@link #CANCEL_GRACE_SECONDS}, after which teardown proceeds.
   */
  public void cancel() {
    lock.lock();
    try {
      cancelled = true;
      if (!started) {
        return;
      }
      halt(OpenLoopState.CANCEL, true);
    } finally {
      lock.unlock();
    }
    boolean interrupted = false;
    while (true) {
      try {
        if (!done.await(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)) {
          LOGGER.warn("The open loop schedule of query set " + querySet + " of scenario " + scenario
            + " did not stop in " + CANCEL_GRACE_SECONDS + " s after the cancel");
        }
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Captures and returns an exact snapshot of the current scheduler state; may be called from any
   * thread.
   */
  public OpenLoopState state() {
    lock.lock();
    try {
      return new OpenLoopState(this, clock.nowMicros());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Signals the dispatcher thread to wake and returns the number of dispatch passes completed prior
   * to this signal, for use in tests.
   */
  long wake() {
    lock.lock();
    try {
      wake.signalAll();
      return passes;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the total number of dispatcher loop passes, each of which reads the clock, for use in
   * tests.
   */
  long passes() {
    lock.lock();
    try {
      return passes;
    } finally {
      lock.unlock();
    }
  }

  /** Returns true once run() has fully terminated and published its final report. */
  boolean isDone() {
    return done.getCount() == 0;
  }

  // Methods below that do not acquire the lock themselves require the caller to hold the lock.

  double targetRate(long nowUs) {
    return OpenLoopState.STEADY.equals(phase) ? schedule.rateAt(nowUs - t0) : 0;
  }

  int queueDepth() {
    return queue.size();
  }

  long driftUs(long nowUs) {
    if (!queue.isEmpty()) {
      return nowUs - queue.peekFirst().intendedUs;
    }
    return nextDueUs <= nowUs ? nowUs - nextDueUs : 0;
  }

  private void dispatch() throws InterruptedException {
    lock.lock();
    try {
      while (warming > 0 && !halted) {
        passes++;
        clock.await(wake, Long.MAX_VALUE);
      }
      if (halted) {
        return;
      }
      // The timed arrival schedule begins only after all worker warmup operations have completed.
      t0 = clock.nowMicros();
      startMs = EnvironmentEdgeManager.currentTimeMillis();
      phase = OpenLoopState.STEADY;
    } finally {
      lock.unlock();
    }
    ArrivalSchedule.Cursor cursor = schedule.cursor();
    long next = cursor.next();
    long sequence = 0;
    long endUs = t0 + schedule.durationUs();
    try {
      while (true) {
        if (stopWhen != null && stopWhen.getAsBoolean()) {
          haltWithLock(OpenLoopState.PREDICATE, false);
          return;
        }
        lock.lock();
        try {
          passes++;
          if (halted) {
            return;
          }
          long now = clock.nowMicros();
          if (now >= stopAtUs) {
            halt(OpenLoopState.STOP_TIME, false);
            return;
          }
          while (next != ArrivalSchedule.END && t0 + next <= now && queue.size() < concurrency) {
            queue.addLast(new Operation(sequence++, t0 + next));
            scheduled++;
            maxQueueDepth = Math.max(maxQueueDepth, queue.size());
            work.signal();
            next = cursor.next();
          }
          nextDueUs = next == ArrivalSchedule.END ? NONE : t0 + next;
          long deadline;
          if (next == ArrivalSchedule.END) {
            if (now >= endUs) {
              // Mark the queue closed so workers drain any remaining operations and then exit.
              closed = true;
              work.signalAll();
              return;
            }
            deadline = endUs;
          } else if (nextDueUs <= now) {
            // The dispatch queue is full; wait until a worker dequeues an operation and signals.
            deadline = Long.MAX_VALUE;
          } else {
            deadline = nextDueUs;
          }
          clock.await(wake, Math.min(deadline, stopAtUs));
        } finally {
          lock.unlock();
        }
      }
    } finally {
      // Following a halt, any arrivals that had already come due while blocked by a full queue are
      // accounted for as both scheduled and aborted. On a normal duration stop, no unscheduled
      // arrivals remain.
      lock.lock();
      try {
        long dueUs = Math.min(clock.nowMicros(), stopAtUs - 1);
        while (next != ArrivalSchedule.END && t0 + next <= dueUs) {
          scheduled++;
          aborted++;
          next = cursor.next();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private void work() {
    for (long i = 0; i < warmupPerWorker; i++) {
      lock.lock();
      try {
        if (halted) {
          break;
        }
      } finally {
        lock.unlock();
      }
      try {
        task.execute(i, true);
      } catch (Throwable t) {
        lock.lock();
        try {
          keepFailure(t);
        } finally {
          lock.unlock();
        }
        break;
      }
    }
    lock.lock();
    try {
      warming--;
      wake.signalAll();
    } finally {
      lock.unlock();
    }
    while (true) {
      Operation operation;
      long driftUs;
      lock.lock();
      try {
        while (queue.isEmpty() && !halted && !closed) {
          work.awaitUninterruptibly();
        }
        if (halted || queue.isEmpty()) {
          return;
        }
        operation = queue.pollFirst();
        lastTakeMs = EnvironmentEdgeManager.currentTimeMillis();
        driftUs = clock.nowMicros() - operation.intendedUs;
        maxDriftUs = Math.max(maxDriftUs, driftUs);
        // Notify the dispatcher that capacity is now available in the queue.
        wake.signalAll();
        if (driftUs > maxLagUs) {
          if (drop) {
            dropped++;
            continue;
          }
          late++;
        }
        inFlight++;
        maxInFlight = Math.max(maxInFlight, inFlight);
      } finally {
        lock.unlock();
      }
      long serviceUs = 0;
      Throwable error = null;
      try {
        serviceUs = task.execute(operation.sequence, false);
      } catch (Throwable t) {
        error = t;
      }
      long responseUs = driftUs + serviceUs;
      lock.lock();
      try {
        inFlight--;
        if (error == null) {
          completed++;
          service.recordValue(clamp(serviceUs));
          response.recordValue(clamp(responseUs));
        } else {
          aborted++;
          keepFailure(error);
        }
        wake.signalAll();
      } finally {
        lock.unlock();
      }
      if (error == null) {
        LatencyRecorders.record(task.responseOperation(operation.sequence), LatencyRecorders.STEADY,
          responseUs);
      }
    }
  }

  /**
   * Records the first encountered failure and initiates an error halt unless a stop reason has
   * already been established.
   */
  private void keepFailure(Throwable t) {
    if (failure == null) {
      failure = t;
      halt(OpenLoopState.ERROR, false);
    }
  }

  private void haltWithLock(String reason, boolean interruptWorkers) {
    lock.lock();
    try {
      halt(reason, interruptWorkers);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops further arrival releases, aborts all currently queued operations, and wakes all waiting
   * threads.
   */
  private void halt(String reason, boolean interruptWorkers) {
    if (stopReason == null) {
      stopReason = reason;
    }
    if (!halted) {
      halted = true;
      aborted += queue.size();
      queue.clear();
      nextDueUs = NONE;
      work.signalAll();
      wake.signalAll();
    }
    if (interruptWorkers) {
      for (Thread worker : workers) {
        worker.interrupt();
      }
    }
  }

  /**
   * Waits for all worker threads to terminate; if interrupted while waiting, halts the run and
   * continues joining until every worker exits.
   */
  private boolean joinWorkers() {
    boolean interrupted = false;
    for (Thread worker : workers) {
      while (true) {
        try {
          worker.join();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
          haltWithLock(OpenLoopState.INTERRUPT, true);
        }
      }
    }
    return interrupted;
  }

  private static long clamp(long micros) {
    return Math.max(0, Math.min(LatencyRecorders.HIGHEST_US, micros));
  }

  private static Histogram newHistogram() {
    return new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
  }
}
