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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.LatencyRecorder;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.truth.ScoreSummary;

/**
 * The measurements of one group of queries that share a layout, a condition family, a k, and a
 * sweep point: the latency distribution, the accuracy scores, the cost counters, and the plan the
 * group ran. Safe for concurrent recording.
 */
public final class Measurement {

  private final String stage;
  private final String layout;
  private final String family;
  private final int k;
  private final String sweep;
  private final int sweepValue;
  private final LatencyRecorder latency = new LatencyRecorder();
  private final List<QueryScore> scores = new ArrayList<>();
  private final AtomicLong queries = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong rowsReturned = new AtomicLong();
  private final AtomicLong rowsScanned = new AtomicLong();
  private final AtomicLong rpcs = new AtomicLong();
  private final AtomicLong remoteRpcs = new AtomicLong();
  private final AtomicLong scanBytes = new AtomicLong();
  private final AtomicLong regions = new AtomicLong();
  private volatile double probeRowsRead = Double.NaN;
  private volatile boolean probeRowsExpected;
  private volatile double wallSeconds = Double.NaN;
  private volatile String plan;
  private volatile PlanClass planClass;
  private volatile PlanClass expected;
  private volatile String lastError;

  public Measurement(String stage, String layout, String family, int k, String sweep,
    int sweepValue) {
    this.stage = stage;
    this.layout = layout;
    this.family = family;
    this.k = k;
    this.sweep = sweep;
    this.sweepValue = sweepValue;
  }

  public void record(QueryExecutor.Result r, QueryScore score) {
    queries.incrementAndGet();
    latency.recordNanos(r.latencyNanos());
    if (r.failed()) {
      errors.incrementAndGet();
      lastError = r.error();
      return;
    }
    rowsReturned.addAndGet(r.ordinals().length);
    rowsScanned.addAndGet(r.rowsScanned());
    rpcs.addAndGet(r.rpcs());
    remoteRpcs.addAndGet(r.remoteRpcs());
    scanBytes.addAndGet(r.scanBytes());
    regions.addAndGet(r.regions());
    if (score != null) {
      synchronized (scores) {
        scores.add(score);
      }
    }
  }

  public void plan(String text, PlanClass actual, PlanClass expectedClass) {
    this.plan = text;
    this.planClass = actual;
    this.expected = expectedClass;
  }

  /** Sets the HNSW candidate probe rows read during the group, from the metrics observer. */
  /**
   * Declares that the rows this group reads include HNSW probe reads, which the request metrics do
   * not count, so that read amplification is unknown rather than understated when they are.
   */
  public void expectProbeRows() {
    this.probeRowsExpected = true;
  }

  public void probeRowsRead(double rows) {
    this.probeRowsRead = rows;
  }

  public void wallSeconds(double s) {
    this.wallSeconds = s;
  }

  public String stage() {
    return stage;
  }

  public String layout() {
    return layout;
  }

  public String family() {
    return family;
  }

  public int k() {
    return k;
  }

  public String sweep() {
    return sweep;
  }

  public int sweepValue() {
    return sweepValue;
  }

  public PlanClass planClass() {
    return planClass;
  }

  public PlanClass expectedPlan() {
    return expected;
  }

  public boolean planDiscrepancy() {
    return planClass != null && expected != null && planClass != expected;
  }

  public long queries() {
    return queries.get();
  }

  public long errors() {
    return errors.get();
  }

  public List<QueryScore> scores() {
    synchronized (scores) {
      return new ArrayList<>(scores);
    }
  }

  public Histogram latencyHistogram() {
    return latency.total();
  }

  /** Returns rows read per row returned, counting HNSW probe reads that client metrics miss. */
  public double readAmplification() {
    long returned = rowsReturned.get();
    if (returned == 0) {
      return Double.NaN;
    }
    if (Double.isNaN(probeRowsRead) && probeRowsExpected) {
      return Double.NaN;
    }
    double probe = Double.isNaN(probeRowsRead) ? 0.0 : probeRowsRead;
    return (rowsScanned.get() + probe) / returned;
  }

  /** Returns RPCs per row returned. */
  public double rpcFanOut() {
    long returned = rowsReturned.get();
    return returned == 0 ? Double.NaN : (double) rpcs.get() / returned;
  }

  /** Summarizes the group for the frontier CSV and the manifest. */
  public Map<String, Object> summary(int resamples, long seed) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("stage", stage);
    m.put("layout", layout);
    m.put("family", family);
    m.put("k", k);
    m.put("sweep", sweep);
    m.put("sweep_value", sweepValue);
    long n = queries.get();
    m.put("queries", n);
    m.put("errors", errors.get());
    m.put("latency", LatencyRecorder.summarize(latencyHistogram()));
    if (!Double.isNaN(wallSeconds) && wallSeconds > 0) {
      m.put("throughput_qps", n / wallSeconds);
    }
    ScoreSummary s = ScoreSummary.of(scores(), resamples, seed);
    Map<String, Object> acc = new LinkedHashMap<>();
    for (Map.Entry<String, ScoreSummary.Estimate> e : s.estimates().entrySet()) {
      Map<String, Object> est = new LinkedHashMap<>();
      est.put("mean", e.getValue().mean());
      est.put("low", e.getValue().low());
      est.put("high", e.getValue().high());
      est.put("n", e.getValue().count());
      acc.put(e.getKey(), est);
    }
    m.put("accuracy", acc);
    m.put("rows_returned", rowsReturned.get());
    m.put("rows_scanned", rowsScanned.get());
    m.put("hnsw_probe_rows_read", Double.isNaN(probeRowsRead) ? null : probeRowsRead);
    m.put("read_amplification", readAmplification());
    m.put("rpcs", rpcs.get());
    m.put("remote_rpcs", remoteRpcs.get());
    m.put("rpc_fan_out", rpcFanOut());
    m.put("rpcs_per_query", n == 0 ? Double.NaN : (double) rpcs.get() / n);
    m.put("scan_bytes", scanBytes.get());
    m.put("regions_scanned", regions.get());
    m.put("plan_class", planClass == null ? null : planClass.name());
    m.put("expected_plan", expected == null ? null : expected.name());
    m.put("plan_discrepancy", planDiscrepancy());
    m.put("plan", plan);
    if (lastError != null) {
      m.put("last_error", lastError);
    }
    return m;
  }

  /** The columns of the frontier CSV. */
  public static String[] csvColumns() {
    return new String[] { "stage", "layout", "family", "k", "sweep", "sweep_value", "queries",
      "errors", "p50_ms", "p95_ms", "p99_ms", "p999_ms", "mean_ms", "throughput_qps", "recall",
      "recall_low", "recall_high", "precision", "completeness", "recall1_at_k", "mrr", "ndcg",
      "distance_ratio", "range_precision", "range_recall", "read_amplification", "rpc_fan_out",
      "rpcs_per_query", "rows_scanned", "hnsw_probe_rows_read", "plan_class", "expected_plan",
      "plan_discrepancy" };
  }

  /** Returns the frontier CSV row of a summary. */
  @SuppressWarnings("unchecked")
  public static Object[] csvRow(Map<String, Object> s) {
    Map<String, Object> lat = (Map<String, Object>) s.get("latency");
    Map<String, Object> acc = (Map<String, Object>) s.get("accuracy");
    return new Object[] { s.get("stage"), s.get("layout"), s.get("family"), s.get("k"),
      s.get("sweep"), s.get("sweep_value"), s.get("queries"), s.get("errors"), lat.get("p50_ms"),
      lat.get("p95_ms"), lat.get("p99_ms"), lat.get("p999_ms"), lat.get("mean_ms"),
      s.get("throughput_qps"), est(acc, "recall", "mean"), est(acc, "recall", "low"),
      est(acc, "recall", "high"), est(acc, "precision", "mean"), est(acc, "completeness", "mean"),
      est(acc, "recall1_at_k", "mean"), est(acc, "mrr", "mean"), est(acc, "ndcg", "mean"),
      est(acc, "distance_ratio", "mean"), est(acc, "range_precision", "mean"),
      est(acc, "range_recall", "mean"), s.get("read_amplification"), s.get("rpc_fan_out"),
      s.get("rpcs_per_query"), s.get("rows_scanned"), s.get("hnsw_probe_rows_read"),
      s.get("plan_class"), s.get("expected_plan"), s.get("plan_discrepancy") };
  }

  @SuppressWarnings("unchecked")
  static Object est(Map<String, Object> acc, String metric, String field) {
    Object e = acc.get(metric);
    return e instanceof Map ? ((Map<String, Object>) e).get(field) : null;
  }
}
