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

import java.util.List;
import java.util.Random;
import org.apache.phoenix.pherf.configuration.OpenLoop;

/**
 * Immutable schedule computing the intended dispatch offsets of an open-loop workload, expressed in
 * microseconds from the start of the schedule.
 * <p>
 * The configured stages define a piecewise-linear rate curve r(t), whose integral L(t) gives the
 * expected cumulative arrival count up to time t. An arrival with cumulative target u is assigned
 * the dispatch offset L^-1(u):
 * <ul>
 * <li>UNIFORM: arrival k uses u = k, placing the first arrival at offset 0 and producing equal
 * intervals of 1/rate across a flat stage.</li>
 * <li>POISSON: u is the sum of k unit exponential variates drawn from java.util.Random(seed). This
 * time rescaling yields a Poisson process governed by the rate curve, where a given seed produces a
 * deterministic sequence.</li>
 * </ul>
 * The schedule window spans [0, duration). Any target at or beyond the total expected arrival count
 * has no offset, and any stage with a rate of 0 receives no arrivals.
 */
public final class ArrivalSchedule {
  /** Sentinel value returned by a cursor after the final arrival has been consumed. */
  public static final long END = -1;
  private static final double US = 1_000_000.0;

  private final long[] startUs;
  private final long[] lengthUs;
  private final double[] startRate;
  private final double[] endRate;
  // Entry expected[i] holds the cumulative expected arrival count at the start of stage i, while
  // the final entry holds the total expected arrival count across all stages.
  private final double[] expected;
  private final long durationUs;
  private final boolean poisson;
  private final long seed;

  private ArrivalSchedule(List<OpenLoop.Stage> stages, boolean poisson, long seed) {
    int n = stages.size();
    startUs = new long[n];
    lengthUs = new long[n];
    startRate = new double[n];
    endRate = new double[n];
    expected = new double[n + 1];
    long time = 0;
    for (int i = 0; i < n; i++) {
      OpenLoop.Stage stage = stages.get(i);
      startUs[i] = time;
      lengthUs[i] = stage.getDurationMs() * 1000;
      startRate[i] = stage.getRate();
      endRate[i] = stage.getEndRate() == null ? stage.getRate() : stage.getEndRate();
      expected[i + 1] = expected[i] + (startRate[i] + endRate[i]) / 2 * lengthUs[i] / US;
      time += lengthUs[i];
    }
    this.durationUs = time;
    this.poisson = poisson;
    this.seed = seed;
  }

  /**
   * Constructs an arrival schedule from an open-loop configuration validated by
   * QueryExecutor.checkOpenLoop. When the arrival distribution is unspecified, it defaults to
   * UNIFORM.
   */
  public static ArrivalSchedule of(OpenLoop openLoop) {
    boolean poisson = openLoop.getArrival() == OpenLoop.Arrival.POISSON;
    return new ArrivalSchedule(openLoop.getStage(), poisson, poisson ? openLoop.getSeed() : 0);
  }

  /** Returns the total duration of the schedule window in microseconds. */
  public long durationUs() {
    return durationUs;
  }

  /**
   * Returns the configured arrival rate at the specified microsecond offset in operations per
   * second, or 0 if the offset falls outside the schedule window.
   */
  public double rateAt(long offsetUs) {
    for (int i = 0; i < startUs.length; i++) {
      if (offsetUs >= startUs[i] && offsetUs < startUs[i] + lengthUs[i]) {
        return startRate[i]
          + (endRate[i] - startRate[i]) * (offsetUs - startUs[i]) / (double) lengthUs[i];
      }
    }
    return 0;
  }

  /**
   * Returns a new cursor positioned before the first arrival. Cursors are not thread-safe and must
   * be confined to a single thread.
   */
  public Cursor cursor() {
    return new Cursor();
  }

  /**
   * Computes the dispatch offset in microseconds for the cumulative arrival target u, or returns
   * {@link #END} if the target is at or beyond the total expected arrival count.
   */
  long offsetOf(double u) {
    for (int i = 0; i < startUs.length; i++) {
      // Use a strict inequality so that stages with a rate of 0 are skipped and a target falling on
      // a stage boundary advances to the subsequent stage.
      if (expected[i + 1] > u) {
        double v = u - expected[i];
        double length = lengthUs[i] / US;
        double r0 = startRate[i];
        // Solve r0 t + (r1 - r0) t^2 / 2D = v using the rationalized quadratic formula so that the
        // root remains numerically stable across flat, rising, and falling stages as well as when
        // r0 = 0.
        double t = v == 0
          ? 0
          : 2 * v / (r0 + Math.sqrt(Math.max(0, r0 * r0 + 2 * v * (endRate[i] - r0) / length)));
        long offset = Math.round(t * US);
        // Clamp the rounded offset to the stage bounds so floating-point rounding cannot push an
        // arrival outside its stage.
        return startUs[i] + Math.min(Math.max(0, offset), lengthUs[i] - 1);
      }
    }
    return END;
  }

  /** Sequential iterator over the arrival offsets of this schedule in chronological order. */
  public final class Cursor {
    private final Random random = poisson ? new Random(seed) : null;
    private long count;
    private double target;
    private boolean ended;

    private Cursor() {
    }

    /**
     * Advances to and returns the microsecond offset of the next arrival, or returns {@link #END}
     * once all arrivals in the schedule have been exhausted.
     */
    public long next() {
      if (ended) {
        return END;
      }
      if (poisson) {
        target -= Math.log(1 - random.nextDouble());
      } else {
        target = count;
      }
      count++;
      long offset = offsetOf(target);
      ended = offset == END;
      return offset;
    }
  }
}
