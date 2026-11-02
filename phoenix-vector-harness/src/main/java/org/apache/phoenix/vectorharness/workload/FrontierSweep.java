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
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.observe.PlanClassifier;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quiesced parameter sweep benchmark measuring accuracy and cost frontiers across layouts, sweep
 * points, k values, and condition families without concurrent writes. Records latency percentiles,
 * throughput, read amplification, RPC fan-out, and plan classifications.
 */
public final class FrontierSweep {

  private static final Logger LOG = LoggerFactory.getLogger(FrontierSweep.class);

  private final RunContext rc;
  private final CsvTable csv;
  private final String stage;

  public FrontierSweep(RunContext rc, CsvTable csv, String stage) {
    this.rc = rc;
    this.csv = csv;
    this.stage = stage;
  }

  /**
   * Returns true if the JMX reader serves cached HNSW probe counters. The http and local readers
   * cache values, so a per-group delta is not reliable.
   */
  private boolean cachedProbeReads() {
    VectorIndexMetricsObserver metrics = rc.metrics();
    return metrics != null && rc.ctx().config().variant() == VariantKind.HNSW
      && (metrics.mode().equals("http") || metrics.mode().equals("local"));
  }

  /** Returns frontier CSV column headers. */
  public static String[] csvColumns() {
    return Measurement.csvColumns();
  }

  public List<Map<String, Object>> run() throws InterruptedException {
    HarnessConfig c = rc.ctx().config();
    int panelSize =
      c.sweepPanelSize() > 0 ? Math.min(c.sweepPanelSize(), rc.panel().size()) : rc.panel().size();
    List<Map<String, Object>> out = new ArrayList<>();
    if (cachedProbeReads()) {
      LOG.info("[{}] The {} JMX reader caches values, so the frontier does not report HNSW probe"
        + " rows from JMX; rows_scanned includes them", stage, rc.metrics().mode());
    }
    try (QueryPool pool = new QueryPool(rc.ctx(), c.queryThreads(), "vh-sweep")) {
      for (TableLayout layout : rc.layouts()) {
        List<SweepPoint> points = rc.variant().sweep(rc.ctx(), rc.indexes().get(layout));
        for (SweepPoint sweep : points) {
          for (int k : c.kValues()) {
            Map<String, List<QuerySpec>> groups = groups(layout, sweep, k, panelSize);
            for (Map.Entry<String, List<QuerySpec>> g : groups.entrySet()) {
              Map<String, Object> s = measure(pool, layout, sweep, k, g.getKey(), g.getValue());
              out.add(s);
              if (csv != null) {
                csv.row(Measurement.csvRow(s));
                csv.flush();
              }
            }
          }
        }
      }
    }
    return out;
  }

  /** Partitions queries into groups by condition family up to panelSize queries. */
  Map<String, List<QuerySpec>> groups(TableLayout layout, SweepPoint sweep, int k, int panelSize) {
    Map<String, List<QuerySpec>> groups = new LinkedHashMap<>();
    for (int i = 0; i < panelSize; i++) {
      for (QueryCondition cond : rc.conditions().get(i)) {
        if (cond.hasRadius() && cond.k() != k) {
          continue;
        }
        groups.computeIfAbsent(cond.family(), f -> new ArrayList<>())
          .add(rc.queries().build(layout, rc.panel().get(i), cond, k, sweep));
      }
    }
    return groups;
  }

  Map<String, Object> measure(QueryPool pool, TableLayout layout, SweepPoint sweep, int k,
    String family, List<QuerySpec> specs) throws InterruptedException {
    Measurement m = new Measurement(stage, layout.id(), family, k, sweep.label(), sweep.value());
    QuerySpec first = specs.get(0);
    m.coverage(rc.variant().coverage(layout, first.condition().attribute()).id());
    PlanClass expected =
      rc.variant().expectedPlan(first.condition().shape(), rc.ctx().config().multiTenant());
    try {
      String plan = pool.executor().explain(first);
      PlanClassifier.Classification cls = PlanClassifier.classify(plan);
      m.plan(cls.toString(), cls.planClass(), expected);
    } catch (Exception e) {
      LOG.warn("EXPLAIN failed for {}: {}", first.sql(), e.getMessage());
      m.plan("explain failed: " + e.getMessage(), PlanClass.OTHER, expected);
    }
    boolean cached = cachedProbeReads();
    MetricSnapshot before = rc.metrics() == null || cached ? null : rc.metrics().sample(null);
    long start = System.nanoTime();
    final int[] skipped = { 0 };
    pool.runAll(specs, (q, r) -> {
      NeighborList truth = rc.truth(q.query().index(), q.condition(), k);
      QueryScore score = null;
      if (truth == null) {
        synchronized (skipped) {
          skipped[0]++;
        }
      } else if (!r.failed()) {
        score = rc.scoring().score(q, r, truth, k, 0);
      }
      m.record(r, score);
      rc.perQuery(m, q, r, score);
    });
    m.wallSeconds((System.nanoTime() - start) / 1e9);
    if (cached) {
      // A cached JMX value can predate this group. The scan metrics count the probe rows instead.
      m.probeRowsRead(Double.NaN);
    } else {
      m.probeRowsRead(rc.probeRowsSince(before, layout));
    }
    Map<String, Object> s =
      m.summary(rc.ctx().config().bootstrapResamples(), rc.ctx().config().seed());
    if (skipped[0] > 0) {
      s.put("unscored", skipped[0]);
    }
    if (m.planDiscrepancy()) {
      rc.manifest().append("plan_discrepancies", "frontier", layout.id() + " " + family + " k=" + k
        + " " + sweep.label() + ": expected " + expected + ", ran " + m.planClass());
    }
    LOG.info("[{}] {} {} k={} {}: recall={} p99={}ms plan={}", stage, layout.id(), family, k,
      sweep.label(), String.format("%.4f", recall(s)), String.format("%.2f", p99(s)),
      m.planClass());
    return s;
  }

  @SuppressWarnings("unchecked")
  static double recall(Map<String, Object> s) {
    Object v = Measurement.est((Map<String, Object>) s.get("accuracy"), "recall", "mean");
    return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
  }

  @SuppressWarnings("unchecked")
  static double p99(Map<String, Object> s) {
    Object v = ((Map<String, Object>) s.get("latency")).get("p99_ms");
    return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
  }
}
