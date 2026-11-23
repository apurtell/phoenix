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
 * A synthetic test workload driven by the virtual clock. It emits one interval histogram per
 * configured interval before completing. Any configuration property key suffixed with
 * {@code .<slot>} is scoped exclusively to that individual slot:
 * <ul>
 * <li>fake.intervals: configures the total number of intervals to execute, defaulting to 3.</li>
 * <li>fake.intervalMs: configures the duration of each interval, defaulting to 1000 ms.</li>
 * <li>fake.fail: causes the workload to fail after this many intervals have completed.</li>
 * <li>fake.prepareError: causes the prepare phase to report this error. Any later slice validated
 * during preparation reports its own configured value prefixed with "step &lt;step&gt; slot
 * &lt;slot&gt;: ", matching PherfWorkload.</li>
 * <li>fake.prepareThrows: causes the prepare phase to throw an IllegalStateException with this
 * message.</li>
 * </ul>
 * Once cancel is requested, the workload stops at the end of its currently active interval.
 */
public final class FakeWorkload implements Workload {
  public static final String OPERATION = "query:fake:q1";

  public final List<Assignment> prepared = new ArrayList<>();
  public final List<Assignment> started = new ArrayList<>();
  public final List<String> cancelled = new ArrayList<>();
  /** Stores the total count of each emitted interval histogram in emission order. */
  public final List<Long> emitted = new ArrayList<>();

  private final Clock clock;
  private Clock.Timer timer;
  private boolean cancelling;

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
  public void start(Assignment assignment, long startAtMs, Listener listener) {
    started.add(assignment);
    cancelling = false;
    long intervals = number(assignment, "fake.intervals", 3);
    long intervalMs = number(assignment, "fake.intervalMs", 1000);
    long failAfter = number(assignment, "fake.fail", -1);
    tick(assignment, listener, 1, intervals, intervalMs, failAfter);
  }

  private void tick(Assignment assignment, Listener listener, long n, long intervals,
    long intervalMs, long failAfter) {
    long startMs = clock.millis();
    timer = clock.schedule(intervalMs, () -> {
      timer = null;
      if (cancelling) {
        listener.finished();
        return;
      }
      Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      for (int i = 0; i < n; i++) {
        histogram.recordValue(100 * n + 10 * assignment.getIdentity().getSlot() + i);
      }
      emitted.add(histogram.getTotalCount());
      listener.interval(OPERATION, LatencyRecorders.STEADY, startMs, clock.millis(), histogram);
      if (n == failAfter) {
        listener.failed("fake failure after " + n + " intervals");
      } else if (n == intervals) {
        listener.finished();
      } else {
        tick(assignment, listener, n + 1, intervals, intervalMs, failAfter);
      }
    });
  }

  @Override
  public void cancel(String runId) {
    cancelled.add(runId);
    if (timer != null) {
      cancelling = true;
    }
  }

  /** Returns true while an interval timer is currently armed and awaiting execution. */
  public boolean active() {
    return timer != null;
  }
}
