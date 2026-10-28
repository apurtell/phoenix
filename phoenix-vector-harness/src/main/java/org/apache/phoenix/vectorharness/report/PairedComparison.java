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
package org.apache.phoenix.vectorharness.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.result.CsvTable;

/**
 * Paired per-query differences between two runs over the same panel. Within each group of layout,
 * condition family, and k, each run contributes the per-query results of its operating point, and a
 * pair is one panel query under one condition measured by both. Paired differences cancel the
 * variation between queries, so they resolve smaller effects than a comparison of means.
 * Differences are the other run minus the base run.
 */
public final class PairedComparison {

  /** The paired differences of one group. */
  public static final class Group {
    private final String group;
    private final String baseSweep;
    private final String otherSweep;
    private final int pairs;
    private final int unpaired;
    private final double[] latencyDiff;
    private final double[] latencyCi;
    private final double latencyMedian;
    private final double[] recallDiff;
    private final double[] recallCi;
    private final double fasterShare;
    private final double recallHigherShare;
    private final double recallLowerShare;

    Group(String group, String baseSweep, String otherSweep, int pairs, int unpaired,
      double[] latencyDiff, double[] latencyCi, double latencyMedian, double[] recallDiff,
      double[] recallCi, double fasterShare, double recallHigherShare, double recallLowerShare) {
      this.group = group;
      this.baseSweep = baseSweep;
      this.otherSweep = otherSweep;
      this.pairs = pairs;
      this.unpaired = unpaired;
      this.latencyDiff = latencyDiff;
      this.latencyCi = latencyCi;
      this.latencyMedian = latencyMedian;
      this.recallDiff = recallDiff;
      this.recallCi = recallCi;
      this.fasterShare = fasterShare;
      this.recallHigherShare = recallHigherShare;
      this.recallLowerShare = recallLowerShare;
    }

    public String group() {
      return group;
    }

    public String baseSweep() {
      return baseSweep;
    }

    public String otherSweep() {
      return otherSweep;
    }

    /** Returns the number of paired queries. */
    public int pairs() {
      return pairs;
    }

    /** Returns the queries measured by only one run, or failed in either. */
    public int unpaired() {
      return unpaired;
    }

    /** Returns the mean latency difference in milliseconds. */
    public double latencyMeanDiff() {
      return latencyDiff[0];
    }

    public double latencyLow() {
      return latencyCi[0];
    }

    public double latencyHigh() {
      return latencyCi[1];
    }

    public double latencyMedianDiff() {
      return latencyMedian;
    }

    public double recallMeanDiff() {
      return recallDiff[0];
    }

    public double recallLow() {
      return recallCi[0];
    }

    public double recallHigh() {
      return recallCi[1];
    }

    /** Returns the share of pairs in which the other run was faster. */
    public double fasterShare() {
      return fasterShare;
    }

    public double recallHigherShare() {
      return recallHigherShare;
    }

    public double recallLowerShare() {
      return recallLowerShare;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("group", group);
      m.put("base_sweep", baseSweep);
      m.put("other_sweep", otherSweep);
      m.put("pairs", pairs);
      m.put("unpaired", unpaired);
      m.put("latency_mean_diff_ms", latencyMeanDiff());
      m.put("latency_low_ms", latencyLow());
      m.put("latency_high_ms", latencyHigh());
      m.put("latency_median_diff_ms", latencyMedian);
      m.put("recall_mean_diff", recallMeanDiff());
      m.put("recall_low", recallLow());
      m.put("recall_high", recallHigh());
      m.put("faster_share", fasterShare);
      m.put("recall_higher_share", recallHigherShare);
      m.put("recall_lower_share", recallLowerShare);
      return m;
    }
  }

  /** The operating points of one group in the two runs. */
  public static final class Selection {
    final String layout;
    final String family;
    final int k;
    final String baseSweep;
    final String otherSweep;

    public Selection(String layout, String family, int k, String baseSweep, String otherSweep) {
      this.layout = layout;
      this.family = family;
      this.k = k;
      this.baseSweep = baseSweep;
      this.otherSweep = otherSweep;
    }

    String group() {
      return layout + " " + family + " k=" + k;
    }
  }

  private static final class Sample {
    final double latency;
    final double recall;
    final boolean failed;

    Sample(double latency, double recall, boolean failed) {
      this.latency = latency;
      this.recall = recall;
      this.failed = failed;
    }
  }

  private final int resamples;
  private final long seed;

  public PairedComparison(int resamples, long seed) {
    this.resamples = resamples;
    this.seed = seed;
  }

  /** The stage whose per-query results are paired: the quiesced frontier sweep. */
  static final String STAGE = "frontier";

  private static Map<String, Map<String, Sample>> read(Path file, Map<String, String> sweeps)
    throws IOException {
    Map<String, Map<String, Sample>> out = new HashMap<>();
    if (file == null) {
      return out;
    }
    CsvTable.forEach(file, row -> {
      if (!STAGE.equals(row.get("stage"))) {
        return;
      }
      String group = row.get("layout") + " " + row.get("family") + " k=" + row.get("k");
      String sweep = sweeps.get(group);
      if (sweep == null || !sweep.equals(row.get("sweep"))) {
        return;
      }
      String error = row.get("error");
      double recall = FrontierPoint.number(row.get("recall"));
      boolean failed = (error != null && !error.isEmpty()) || Double.isNaN(recall);
      out.computeIfAbsent(group, g -> new HashMap<>()).put(
        row.get("query") + "|" + row.get("condition"),
        new Sample(FrontierPoint.number(row.get("latency_ms")), recall, failed));
    });
    return out;
  }

  /** Pairs the per-query results of two runs at the selected operating points. */
  public List<Group> compare(RunResult base, RunResult other, List<Selection> selections)
    throws IOException {
    Map<String, String> baseSweeps = new LinkedHashMap<>();
    Map<String, String> otherSweeps = new LinkedHashMap<>();
    for (Selection s : selections) {
      baseSweeps.put(s.group(), s.baseSweep);
      otherSweeps.put(s.group(), s.otherSweep);
    }
    Map<String, Map<String, Sample>> a = read(base.perQueryFile(), baseSweeps);
    Map<String, Map<String, Sample>> b = read(other.perQueryFile(), otherSweeps);
    List<Group> out = new ArrayList<>();
    for (Selection s : selections) {
      Map<String, Sample> ga = a.getOrDefault(s.group(), new HashMap<>());
      Map<String, Sample> gb = b.getOrDefault(s.group(), new HashMap<>());
      List<double[]> pairs = new ArrayList<>();
      int unpaired = 0;
      for (Map.Entry<String, Sample> e : ga.entrySet()) {
        Sample y = gb.get(e.getKey());
        Sample x = e.getValue();
        if (y == null || x.failed || y.failed) {
          unpaired++;
          continue;
        }
        pairs.add(new double[] { y.latency - x.latency, y.recall - x.recall });
      }
      for (String key : gb.keySet()) {
        if (!ga.containsKey(key)) {
          unpaired++;
        }
      }
      if (pairs.isEmpty()) {
        continue;
      }
      out.add(summarize(s, pairs, unpaired));
    }
    return out;
  }

  Group summarize(Selection s, List<double[]> pairs, int unpaired) {
    int n = pairs.size();
    double[] lat = new double[n];
    double[] rec = new double[n];
    int faster = 0;
    int higher = 0;
    int lower = 0;
    for (int i = 0; i < n; i++) {
      lat[i] = pairs.get(i)[0];
      rec[i] = pairs.get(i)[1];
      if (lat[i] < 0) {
        faster++;
      }
      if (rec[i] > Frontiers.RECALL_TOLERANCE) {
        higher++;
      } else if (rec[i] < -Frontiers.RECALL_TOLERANCE) {
        lower++;
      }
    }
    SplitMix64 rnd = new SplitMix64(SplitMix64.combine(seed, s.group().hashCode()));
    double[] latCi = bootstrap(lat, rnd);
    double[] recCi = bootstrap(rec, rnd);
    double[] sorted = lat.clone();
    Arrays.sort(sorted);
    double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    return new Group(s.group(), s.baseSweep, s.otherSweep, n, unpaired, new double[] { mean(lat) },
      latCi, median, new double[] { mean(rec) }, recCi, (double) faster / n, (double) higher / n,
      (double) lower / n);
  }

  static double mean(double[] v) {
    double sum = 0;
    for (double d : v) {
      sum += d;
    }
    return v.length == 0 ? Double.NaN : sum / v.length;
  }

  /** Returns the 95% percentile bootstrap interval of the mean, or NaNs without resamples. */
  double[] bootstrap(double[] v, SplitMix64 rnd) {
    if (resamples <= 0 || v.length < 2) {
      return new double[] { Double.NaN, Double.NaN };
    }
    double[] means = new double[resamples];
    for (int r = 0; r < resamples; r++) {
      double sum = 0;
      for (int i = 0; i < v.length; i++) {
        sum += v[rnd.nextInt(v.length)];
      }
      means[r] = sum / v.length;
    }
    Arrays.sort(means);
    int lo = (int) Math.floor(0.025 * (resamples - 1));
    int hi = (int) Math.ceil(0.975 * (resamples - 1));
    return new double[] { means[lo], means[hi] };
  }
}
