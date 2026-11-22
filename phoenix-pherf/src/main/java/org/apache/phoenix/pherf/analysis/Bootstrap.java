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
package org.apache.phoenix.pherf.analysis;

import java.util.Arrays;
import java.util.Random;
import org.apache.phoenix.pherf.rules.IndexHash;

/**
 * Computes median statistics and percentile bootstrap confidence intervals for offline analysis
 * using a fixed resample count and confidence level. Random sampling relies on a {@link Random}
 * stream initialized with a provided seed; because the JDK specifies the generator algorithm,
 * identical input values and seeds produce deterministic intervals across all JVM implementations.
 */
public final class Bootstrap {
  public static final int RESAMPLES = 10_000;
  public static final int CONFIDENCE_PCT = 95;
  // Defines the 1-based nearest-rank indices corresponding to the 2.5 and 97.5 percentiles across
  // RESAMPLES sorted bootstrap statistics.
  private static final int LOW_RANK = 250;
  private static final int HIGH_RANK = 9_750;

  private Bootstrap() {
  }

  /**
   * Computes the median of the provided sample, returning the middle element for an odd number of
   * values or the arithmetic mean of the two central elements for an even count.
   */
  public static double median(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  /**
   * Estimates the 95% bootstrap confidence interval for the sample median, returning the low and
   * high bounds. A single input value yields a point interval.
   */
  public static double[] ci(double[] values, long seed) {
    Random random = new Random(seed);
    double[] statistics = new double[RESAMPLES];
    double[] resample = new double[values.length];
    for (int i = 0; i < RESAMPLES; i++) {
      draw(values, resample, random);
      statistics[i] = median(resample);
    }
    return ranks(statistics);
  }

  /**
   * Computes the 95% bootstrap confidence interval for the median difference, defined as
   * median(candidate) - median(baseline). During each resampling iteration, the baseline sample and
   * then the candidate sample are drawn independently in sequence from the same random stream.
   */
  public static double[] deltaCi(double[] baseline, double[] candidate, long seed) {
    Random random = new Random(seed);
    double[] statistics = new double[RESAMPLES];
    double[] a = new double[baseline.length];
    double[] b = new double[candidate.length];
    for (int i = 0; i < RESAMPLES; i++) {
      draw(baseline, a, random);
      draw(candidate, b, random);
      statistics[i] = median(b) - median(a);
    }
    return ranks(statistics);
  }

  /**
   * Derives a deterministic random stream seed for an individual metric as a function solely of the
   * base seed and the metric key.
   */
  public static long streamSeed(long seed, String key) {
    return IndexHash.mix64(seed ^ IndexHash.fnv1a64(key));
  }

  private static void draw(double[] values, double[] resample, Random random) {
    for (int j = 0; j < resample.length; j++) {
      resample[j] = values[random.nextInt(values.length)];
    }
  }

  private static double[] ranks(double[] statistics) {
    Arrays.sort(statistics);
    return new double[] { statistics[LOW_RANK - 1], statistics[HIGH_RANK - 1] };
  }
}
