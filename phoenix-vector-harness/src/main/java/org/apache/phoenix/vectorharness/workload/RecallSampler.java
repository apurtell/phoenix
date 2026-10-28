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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.SampleMode;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.observe.PlanClassifier;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.truth.ScoreSummary;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores the tracked panel queries against the live ground truth, once per sample, which yields the
 * recall time series of the run. Quiesced sampling closes the write gate, so that the database
 * state is fixed while the panel runs, and reopens it after; online sampling leaves writes running
 * and excludes from both the result and the truth every row whose state may have changed since the
 * query started. Recall is over the answered queries; the queries that failed are counted apart, so
 * that a sample during unavailability shows how few queries it scored. Each sample also re-checks
 * the plan of each condition family and records plan transitions as events.
 */
public final class RecallSampler {

  private static final Logger LOG = LoggerFactory.getLogger(RecallSampler.class);
  static final long GATE_TIMEOUT_SECONDS = 120;

  private final RunContext rc;
  private final CsvTable csv;
  private final SweepPoint sweep;
  private final int k;
  private final Map<String, PlanClass> plans = new HashMap<>();
  private final List<Map<String, Object>> samples = new ArrayList<>();

  public RecallSampler(RunContext rc, CsvTable csv, SweepPoint sweep) {
    this.rc = rc;
    this.csv = csv;
    this.sweep = sweep;
    this.k = rc.ctx().config().kValues()[0];
  }

  public static String[] csvColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "layout", "family", "k", "queries",
      "query_errors", "recall", "recall_low", "recall_high", "ndcg", "completeness", "excluded",
      "exclusion_ratio", "gate_closed_ms", "plan_class" };
  }

  /** Takes one sample; returns its summary. */
  public Map<String, Object> sample(QueryPool pool) throws InterruptedException {
    HarnessConfig c = rc.ctx().config();
    boolean quiesced = c.sampleMode() == SampleMode.QUIESCED;
    long gateStart = 0;
    if (quiesced) {
      if (!rc.gate().close(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("Writes did not quiesce within {} s; sampling online", GATE_TIMEOUT_SECONDS);
        quiesced = false;
      } else {
        gateStart = System.nanoTime();
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    long wall = System.currentTimeMillis();
    try {
      int tracked = rc.liveTruth() == null ? 0 : rc.liveTruth().trackedQueries();
      for (TableLayout layout : rc.layouts()) {
        Map<String, List<QuerySpec>> groups = new LinkedHashMap<>();
        for (int i = 0; i < tracked; i++) {
          for (QueryCondition cond : rc.conditions().get(i)) {
            if (cond.hasRadius() && cond.k() != k) {
              continue;
            }
            groups.computeIfAbsent(cond.family(), f -> new ArrayList<>())
              .add(rc.queries().build(layout, rc.panel().get(i), cond, k, sweep));
          }
        }
        for (Map.Entry<String, List<QuerySpec>> g : groups.entrySet()) {
          String planClass = checkPlan(pool, layout, g.getKey(), g.getValue().get(0));
          List<QueryScore> scores = new ArrayList<>();
          AtomicInteger errors = new AtomicInteger();
          final boolean online = !quiesced;
          pool.runAll(g.getValue(), (q, r) -> {
            if (r.failed()) {
              errors.incrementAndGet();
              return;
            }
            long since = online ? System.nanoTime() - r.serviceNanos() : 0;
            NeighborList truth = rc.truth(q.query().index(), q.condition(), k);
            if (truth == null) {
              return;
            }
            QueryScore s = rc.scoring().score(q, r, truth, k, since);
            synchronized (scores) {
              scores.add(s);
            }
          });
          ScoreSummary summary = ScoreSummary.of(scores, c.bootstrapResamples(), c.seed());
          long gateMs = quiesced ? (System.nanoTime() - gateStart) / 1_000_000 : 0;
          if (csv != null) {
            csv.row(wall, rc.events().offset(), rc.events().stage(), layout.id(), g.getKey(), k,
              scores.size(), errors.get(), summary.recall().mean(), summary.recall().low(),
              summary.recall().high(), summary.get("ndcg").mean(),
              summary.get("completeness").mean(), summary.excluded(),
              summary.get("exclusion_ratio").mean(), gateMs, planClass);
          }
          Map<String, Object> f = new LinkedHashMap<>();
          f.put("queries", scores.size());
          f.put("query_errors", errors.get());
          f.put("recall", summary.recall().mean());
          f.put("excluded", summary.excluded());
          out.put(layout.id() + "/" + g.getKey(), f);
        }
      }
      if (csv != null) {
        csv.flush();
      }
    } finally {
      if (quiesced) {
        rc.gate().open();
      }
    }
    out.put("wall_ms", wall);
    synchronized (samples) {
      samples.add(out);
    }
    return out;
  }

  private String checkPlan(QueryPool pool, TableLayout layout, String family, QuerySpec q) {
    try {
      PlanClassifier.Classification cls = PlanClassifier.classify(pool.executor().explain(q));
      String key = layout.id() + "/" + family;
      PlanClass previous = plans.put(key, cls.planClass());
      if (previous != null && previous != cls.planClass()) {
        rc.events().event("plan-transition", key + " " + previous + " -> " + cls.planClass());
      }
      return cls.planClass().name();
    } catch (Exception e) {
      LOG.debug("EXPLAIN failed during sampling", e);
      return "";
    }
  }

  public List<Map<String, Object>> samples() {
    synchronized (samples) {
      return new ArrayList<>(samples);
    }
  }
}
