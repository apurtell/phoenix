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
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource.HnswMetric;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.observe.MetricCatalog;
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
 * The quiesced accuracy and cost sweep that establishes efficiency frontiers. For each layout,
 * sweep point of query breadth, k, and condition family, it runs the sweep panel closed loop on the
 * query threads with no concurrent writes, scores every result against exact truth, and records
 * latency percentiles, throughput, read amplification (counting HNSW probe reads), RPC fan-out, and
 * the plan class of the group, checked with EXPLAIN against the plan the variant expects. Each
 * group is one row of frontier.csv.
 * <p>
 * The HNSW probe reads of a group are the change of a RegionServer counter over the group. The http
 * and local readers see it through the metrics2 JMX cache of each server, which refreshes once per
 * metrics period, much longer than most groups last. So the sweep then waits one cache period after
 * each group, with the cluster quiet, before it reads the counter, and that reading is also the
 * start of the next group; without a known period the probe reads are unknown.
 */
public final class FrontierSweep {

  private static final Logger LOG = LoggerFactory.getLogger(FrontierSweep.class);

  private final RunContext rc;
  private final CsvTable csv;
  private final String stage;
  private QuietSampler quiet;

  public FrontierSweep(RunContext rc, CsvTable csv, String stage) {
    this.rc = rc;
    this.csv = csv;
    this.stage = stage;
  }

  /**
   * Samples the vector index metrics only after the cluster stayed quiet for one JMX cache period,
   * so that every server's cached values postdate the work before the sample. A sample serves as
   * the start of the next measurement while the cluster stays quiet.
   */
  static final class QuietSampler {
    private final VectorIndexMetricsObserver metrics;
    private final long ttlMillis;
    private MetricSnapshot last;

    QuietSampler(VectorIndexMetricsObserver metrics, long ttlMillis) {
      this.metrics = metrics;
      this.ttlMillis = ttlMillis;
    }

    /** Returns the sample that starts a measurement: the last one, or a new one. */
    MetricSnapshot before() throws InterruptedException {
      if (last == null) {
        last = await();
      }
      return last;
    }

    /** Returns the sample that ends a measurement, which also starts the next one. */
    MetricSnapshot after() throws InterruptedException {
      last = await();
      return last;
    }

    /** Forgets the last sample, after work that the sampler did not see end. */
    void reset() {
      last = null;
    }

    private MetricSnapshot await() throws InterruptedException {
      Thread.sleep(ttlMillis);
      return metrics.sample(null);
    }
  }

  /**
   * Returns true if the probe reads of the sweep go through cached JMX values: an HNSW index read
   * by the http or local reader.
   */
  private boolean cachedProbeReads() {
    VectorIndexMetricsObserver metrics = rc.metrics();
    return metrics != null && rc.ctx().config().variant() == VariantKind.HNSW
      && (metrics.mode().equals("http") || metrics.mode().equals("local"));
  }

  /** The column header of the frontier CSV. */
  public static String[] csvColumns() {
    return Measurement.csvColumns();
  }

  public List<Map<String, Object>> run() throws InterruptedException {
    HarnessConfig c = rc.ctx().config();
    int panelSize =
      c.sweepPanelSize() > 0 ? Math.min(c.sweepPanelSize(), rc.panel().size()) : rc.panel().size();
    List<Map<String, Object>> out = new ArrayList<>();
    long ttl = c.jmxCacheTtl().toMillis();
    quiet = null;
    if (cachedProbeReads() && ttl > 0) {
      LOG.info("[{}] JMX values are cached for up to {} ms; each frontier group waits that long"
        + " for its HNSW probe reads", stage, ttl);
      quiet = new QuietSampler(rc.metrics(), ttl);
    } else if (cachedProbeReads()) {
      LOG.warn("[{}] {} is 0, so the HNSW probe reads of the {} JMX reader are unknown", stage,
        Setting.OBSERVE_JMX_CACHE_TTL.key(), rc.metrics().mode());
    }
    try (QueryPool pool = new QueryPool(rc.ctx(), c.queryThreads(), "vh-sweep")) {
      for (TableLayout layout : rc.layouts()) {
        if (quiet != null) {
          quiet.reset();
        }
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

  /** Builds the queries of each condition family for the first panelSize queries. */
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
    if (rc.ctx().config().variant() == VariantKind.HNSW) {
      m.expectProbeRows();
    }
    QuerySpec first = specs.get(0);
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
    MetricSnapshot before;
    if (quiet != null) {
      before = quiet.before();
    } else {
      before = rc.metrics() == null || cached ? null : rc.metrics().sample(null);
    }
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
    if (quiet != null) {
      MetricSnapshot after = quiet.after();
      Double d = after.delta(before, MetricCatalog.hnsw(HnswMetric.PROBE_ROWS_READ)
        .scopedName(rc.ctx().tables().fullIndex(layout)));
      m.probeRowsRead(d == null ? Double.NaN : d);
    } else if (cached) {
      // A cached value within its refresh period may predate the group or hold other groups.
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
