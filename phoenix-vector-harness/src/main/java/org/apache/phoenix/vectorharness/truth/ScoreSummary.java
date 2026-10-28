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
package org.apache.phoenix.vectorharness.truth;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;

/**
 * The mean of each accuracy metric over a set of queries, with a percentile bootstrap confidence
 * interval. Resampling draws queries with replacement from a deterministic stream, so the same
 * scores give the same interval. Undefined (NaN) values are left out of their metric.
 */
public final class ScoreSummary {

  /** The mean and the 95% interval of one metric. */
  public static final class Estimate {
    private final double mean;
    private final double low;
    private final double high;
    private final int count;

    Estimate(double mean, double low, double high, int count) {
      this.mean = mean;
      this.low = low;
      this.high = high;
      this.count = count;
    }

    public double mean() {
      return mean;
    }

    public double low() {
      return low;
    }

    public double high() {
      return high;
    }

    /** Returns the number of queries with a defined value. */
    public int count() {
      return count;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "%.4f [%.4f,%.4f]", mean, low, high);
    }
  }

  private final Map<String, Estimate> estimates;
  private final int queries;
  private final long excluded;
  private final long violations;

  private ScoreSummary(Map<String, Estimate> estimates, int queries, long excluded,
    long violations) {
    this.estimates = estimates;
    this.queries = queries;
    this.excluded = excluded;
    this.violations = violations;
  }

  /**
   * Summarizes scores. Besides the metrics of {@link QueryScore#NAMES}, the summary holds the
   * exclusion ratio, the share of the rows the engine returned that online sampling excluded, and
   * the violation ratio, the share of the scored returned rows that fail the query's predicates.
   */
  public static ScoreSummary of(List<QueryScore> scores, int resamples, long seed) {
    Map<String, Estimate> out = new LinkedHashMap<>();
    int n = scores.size();
    double[][] values = new double[QueryScore.NAMES.length][n];
    long excluded = 0;
    long excludedReturned = 0;
    long returned = 0;
    long violations = 0;
    for (int i = 0; i < n; i++) {
      double[] v = scores.get(i).values();
      for (int m = 0; m < v.length; m++) {
        values[m][i] = v[m];
      }
      excluded += scores.get(i).excluded();
      excludedReturned += scores.get(i).excludedReturned();
      returned += scores.get(i).returned();
      violations += scores.get(i).violations();
    }
    for (int m = 0; m < QueryScore.NAMES.length; m++) {
      out.put(QueryScore.NAMES[m], estimate(values[m], resamples, seed + m));
    }
    double exclusionRatio = returned + excludedReturned == 0
      ? 0.0
      : (double) excludedReturned / (returned + excludedReturned);
    out.put("exclusion_ratio", new Estimate(exclusionRatio, exclusionRatio, exclusionRatio, n));
    double violationRatio = returned == 0 ? 0.0 : (double) violations / returned;
    out.put("violation_ratio", new Estimate(violationRatio, violationRatio, violationRatio, n));
    return new ScoreSummary(out, n, excluded, violations);
  }

  static Estimate estimate(double[] values, int resamples, long seed) {
    double[] defined = Arrays.stream(values).filter(v -> !Double.isNaN(v)).toArray();
    int n = defined.length;
    if (n == 0) {
      return new Estimate(Double.NaN, Double.NaN, Double.NaN, 0);
    }
    double sum = 0.0;
    for (double v : defined) {
      sum += v;
    }
    double mean = sum / n;
    if (resamples <= 0 || n == 1) {
      return new Estimate(mean, mean, mean, n);
    }
    double[] means = new double[resamples];
    SplitMix64 rnd = new SplitMix64(SplitMix64.combine(seed, n));
    for (int r = 0; r < resamples; r++) {
      double s = 0.0;
      for (int i = 0; i < n; i++) {
        s += defined[rnd.nextInt(n)];
      }
      means[r] = s / n;
    }
    Arrays.sort(means);
    double low = means[(int) Math.floor(0.025 * (resamples - 1))];
    double high = means[(int) Math.ceil(0.975 * (resamples - 1))];
    return new Estimate(mean, low, high, n);
  }

  public Estimate get(String metric) {
    return estimates.get(metric);
  }

  public Estimate recall() {
    return estimates.get("recall");
  }

  public Map<String, Estimate> estimates() {
    return estimates;
  }

  public int queries() {
    return queries;
  }

  public long excluded() {
    return excluded;
  }

  /** Returns the number of returned rows that fail their query's predicates. */
  public long violations() {
    return violations;
  }
}
