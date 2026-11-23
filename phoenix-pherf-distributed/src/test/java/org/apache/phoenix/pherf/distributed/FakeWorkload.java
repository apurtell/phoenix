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
package org.apache.phoenix.pherf.distributed;

import java.util.ArrayList;
import java.util.List;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.agent.Workload;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.latency.LatencyRecorders;

/**
 * A synthetic test workload driven by the virtual clock or by the skewed clock of its host. After
 * launch, it emits its warmup intervals, waits until it is ready, and arms. At the scheduled start
 * time, it dispatches by emitting one steady interval histogram for each configured interval before
 * finishing. Any configuration property key suffixed with {@code .<slot>} is scoped exclusively to
 * that individual slot:
 * <ul>
 * <li>fake.intervals: configures the total number of steady intervals to execute, defaulting to
 * 3.</li>
 * <li>fake.intervalMs: configures the duration of each interval, defaulting to 1000 ms.</li>
 * <li>fake.warmup: configures the total number of warmup intervals to execute, defaulting to
 * 0.</li>
 * <li>fake.readyMs: configures the delay from the last warmup interval until the workload arms,
 * defaulting to 0.</li>
 * <li>fake.gate: set to false for a workload without a start gate, such as a -l load; the workload
 * never arms and instead dispatches immediately at launch.</li>
 * <li>fake.fail: causes the workload to fail after this many steady intervals have completed.</li>
 * <li>fake.prepareError: causes the prepare phase to report this error. Any later slice validated
 * during preparation reports its own configured value prefixed with "step &lt;step&gt; slot
 * &lt;slot&gt;: ", matching PherfWorkload.</li>
 * <li>fake.prepareThrows: causes the prepare phase to throw an IllegalStateException with this
 * message.</li>
 * </ul>
 * Once cancel is requested, the workload stops at the end of its currently active interval, or
 * terminates immediately if it is currently armed.
 */
public final class FakeWorkload implements Workload {
  public static final String OPERATION = "query:fake:q1";

  public final List<Assignment> prepared = new ArrayList<>();
  public final List<Assignment> launched = new ArrayList<>();
  /** Records the local start timestamp received for each start command. */
  public final List<Long> startAtMs = new ArrayList<>();
  /** Records the local timestamp at which each initial steady interval is dispatched. */
  public final List<Long> dispatchedMs = new ArrayList<>();
  public final List<String> cancelled = new ArrayList<>();
  /**
   * Stores the total count of each emitted warmup and steady interval histogram in emission order.
   */
  public final List<Long> emitted = new ArrayList<>();

  private final Clock clock;
  private Clock.Timer timer;
  private boolean cancelling;
  private boolean armed;
  private Assignment assignment;
  private Listener listener;

  public FakeWorkload(Clock clock) {
    this.clock = clock;
  }

  private static String property(Assignment assignment, String key) {
    String slot = assignment.getProperties().get(key + "." + assignment.getIdentity().getSlot());
    return slot != null ? slot : assignment.getProperties().get(key);
  }

  private static long number(Assignment assignment, String key, long absent) {
    String text = property(assignment, key);
    return text == null ? absent : Long.parseLong(text);
  }

  @Override
  public void prepare(Assignment assignment, Listener listener) {
    prepared.add(assignment);
    String thrown = property(assignment, "fake.prepareThrows");
    if (thrown != null) {
      throw new IllegalStateException(thrown);
    }
    List<String> errors = new ArrayList<>();
    String error = property(assignment, "fake.prepareError");
    if (error != null) {
      errors.add(error);
    }
    for (Assignment later : assignment.getValidations()) {
      error = property(later, "fake.prepareError");
      if (error != null) {
        errors
          .add("step " + later.getStep() + " slot " + later.getIdentity().getSlot() + ": " + error);
      }
    }
    listener.prepared(errors);
  }

  @Override
  public void launch(Assignment assignment, Listener listener) {
    launched.add(assignment);
    this.assignment = assignment;
    this.listener = listener;
    cancelling = false;
    armed = false;
    if ("false".equals(property(assignment, "fake.gate"))) {
      steady(1);
    } else {
      warmup(1);
    }
  }

  private long intervalMs() {
    return number(assignment, "fake.intervalMs", 1000);
  }

  private void warmup(long n) {
    if (n > number(assignment, "fake.warmup", 0)) {
      timer = clock.schedule(number(assignment, "fake.readyMs", 0), () -> {
        timer = null;
        if (cancelling) {
          listener.finished();
          return;
        }
        armed = true;
        listener.armed();
      });
      return;
    }
    long startMs = clock.millis();
    timer = clock.schedule(intervalMs(), () -> {
      timer = null;
      if (cancelling) {
        listener.finished();
        return;
      }
      emit(LatencyRecorders.WARMUP, n, startMs);
      warmup(n + 1);
    });
  }

  @Override
  public void start(String runId, long startAtMs) {
    this.startAtMs.add(startAtMs);
    armed = false;
    timer = clock.schedule(startAtMs - clock.millis(), () -> {
      timer = null;
      if (cancelling) {
        listener.finished();
        return;
      }
      dispatchedMs.add(clock.millis());
      listener.started(clock.millis());
      steady(1);
    });
  }

  private void steady(long n) {
    long failAfter = number(assignment, "fake.fail", -1);
    long startMs = clock.millis();
    timer = clock.schedule(intervalMs(), () -> {
      timer = null;
      if (cancelling) {
        listener.finished();
        return;
      }
      emit(LatencyRecorders.STEADY, n, startMs);
      if (n == failAfter) {
        listener.failed("fake failure after " + n + " intervals");
      } else if (n == number(assignment, "fake.intervals", 3)) {
        listener.finished();
      } else {
        steady(n + 1);
      }
    });
  }

  private void emit(String phase, long n, long startMs) {
    Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
    for (int i = 0; i < n; i++) {
      histogram.recordValue(100 * n + 10 * assignment.getIdentity().getSlot() + i);
    }
    emitted.add(histogram.getTotalCount());
    listener.interval(OPERATION, phase, startMs, clock.millis(), histogram);
  }

  @Override
  public void cancel(String runId) {
    cancelled.add(runId);
    if (timer != null) {
      cancelling = true;
    } else if (armed) {
      armed = false;
      cancelling = true;
      timer = clock.schedule(0, () -> {
        timer = null;
        listener.finished();
      });
    }
  }

  /** Returns true while a workload timer is currently armed and awaiting execution. */
  public boolean active() {
    return timer != null;
  }

  /** Computes and returns the cumulative sum of all emitted interval histogram counts. */
  public long emittedCount() {
    long sum = 0;
    for (long count : emitted) {
      sum += count;
    }
    return sum;
  }
}
