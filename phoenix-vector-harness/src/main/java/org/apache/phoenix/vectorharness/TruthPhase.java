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
package org.apache.phoenix.vectorharness;

import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.PublishedTruthValidator;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.truth.ScoreSummary;
import org.apache.phoenix.vectorharness.variant.ExactVariant;
import org.apache.phoenix.vectorharness.workload.QueryExecutor;
import org.apache.phoenix.vectorharness.workload.QueryFactory;
import org.apache.phoenix.vectorharness.workload.QuerySpec;
import org.apache.phoenix.vectorharness.workload.ResultScoring;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TruthManager;

/**
 * The truth phase: computes, or loads from the cache, the static and filtered ground truth of the
 * panel over the loaded rows, validates it against the published ground truth of a benchmark, and,
 * when the tables are loaded, cross-validates it against the engine's exact distributed top-N path:
 * an exact query must agree with the client-side truth, so the recall of the exact plan is reported
 * and must be 1. The phase writes its results to truth.json in the directory of the tables, and
 * fails when an exact query fails or disagrees.
 */
public final class TruthPhase {

  /** Panel queries that the exact cross-check runs per condition family. */
  static final int EXACT_CHECK_QUERIES = 20;
  static final String TRUTH_STATE = "truth.json";

  private final HarnessContext ctx;
  private final PrintStream out;

  public TruthPhase(HarnessContext ctx, PrintStream out) {
    this.ctx = ctx;
    this.out = out;
  }

  public Manifest run() throws Exception {
    Manifest m = new Manifest();
    TruthManager tm = new TruthManager(ctx);
    java.nio.file.Path loadPath = ctx.store().loadState(ctx.tables().suffix());
    Manifest load = Files.exists(loadPath) ? Manifest.read(loadPath) : null;
    long rows = load != null && load.get("load.rows_loaded") instanceof Number
      ? ((Number) load.get("load.rows_loaded")).longValue()
      : ctx.corpus().rows();
    long t0 = System.nanoTime();
    GroundTruth truth = tm.truth(rows);
    m.put("truth", "rows", rows);
    m.put("truth", "queries", tm.panel().size());
    m.put("truth", "capacity", tm.capacity());
    m.put("truth", "seconds", (System.nanoTime() - t0) / 1e9);
    out.printf(Locale.ROOT, "truth: %d queries over %d rows (capacity %d) in %.1f s%n",
      tm.panel().size(), rows, tm.capacity(), (System.nanoTime() - t0) / 1e9);
    // Published ground truth describes the whole base set, also before a run streams the rest.
    GroundTruth published = rows < ctx.corpus().rows() ? tm.truth(ctx.corpus().rows()) : truth;
    PublishedTruthValidator.Result v = tm.validatePublished(published);
    if (v.queries() > 0) {
      m.put("truth", "published_agrees", v.agrees());
      m.put("truth", "published_mismatches", v.mismatches());
      out.printf(Locale.ROOT, "truth: published ground truth %s (%d queries, %d ranks)%n",
        v.agrees() ? "agrees" : "DISAGREES", v.queries(), v.ranks());
      if (!v.agrees()) {
        throw new IllegalStateException("Static ground truth disagrees with the published ground"
          + " truth:\n  " + String.join("\n  ", v.mismatches()));
      }
    }
    if (
      load != null && load.get("load.completed_ms") != null
        && !Boolean.TRUE.equals(load.get("load.dirty"))
    ) {
      Map<String, Object> check = exactCheck(tm, truth, rows);
      m.putSection("exact_check", check);
      // Written before the verdict, so that the details of a failure are on disk.
      java.nio.file.Path dir = ctx.store().tableDir(ctx.tables().suffix());
      Files.createDirectories(dir);
      m.write(dir.resolve(TRUTH_STATE));
      List<String> problems = exactProblems(check);
      if (!problems.isEmpty()) {
        throw new IllegalStateException("The exact plan disagrees with the client-side ground"
          + " truth:\n  " + String.join("\n  ", problems));
      }
    }
    return m;
  }

  /**
   * Returns the layouts whose exact plan did not agree with the truth: a family below recall 1, or
   * failed queries. A layout whose queries all failed reports recall 1 over no family, so the
   * failures count on their own.
   */
  static List<String> exactProblems(Map<String, Object> check) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Object> e : check.entrySet()) {
      Map<?, ?> l = (Map<?, ?>) e.getValue();
      double min = ((Number) l.get("min_recall")).doubleValue();
      long failures = ((Number) l.get("failures")).longValue();
      Object v = l.get("violations");
      long violations = v instanceof Number ? ((Number) v).longValue() : 0;
      if (failures > 0 || !(min >= 1.0 - 1e-9)) {
        problems.add(e.getKey() + ": min recall " + min + ", " + failures + " failed queries");
      }
      if (violations > 0) {
        problems.add(e.getKey() + ": the exact plan returned " + violations
          + " rows that fail their query's predicate");
      }
    }
    return problems;
  }

  /**
   * Runs the exact plan for the first panel queries of every condition family and scores it against
   * the client-side truth.
   */
  Map<String, Object> exactCheck(TruthManager tm, GroundTruth truth, long rows) {
    Map<String, Object> out = new LinkedHashMap<>();
    LiveCorpus live = new LiveCorpus(ctx.corpus(), rows);
    ResultScoring scoring = new ResultScoring(ctx.metric(), ctx.config().tieEpsilon(), live);
    QueryFactory qf =
      new QueryFactory(ctx.tables(), new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim()),
        ctx.metric(), ctx.corpus().attributeModel(), ctx.config().multiTenant());
    List<List<QueryCondition>> conditions = tm.conditions(truth);
    int k = ctx.config().kValues()[0];
    int n = Math.min(EXACT_CHECK_QUERIES, tm.panel().size());
    try (QueryExecutor exec = new QueryExecutor(ctx)) {
      for (TableLayout layout : ctx.config().layouts()) {
        Map<String, List<QueryScore>> byFamily = new LinkedHashMap<>();
        long failures = 0;
        long unscored = 0;
        for (int i = 0; i < n; i++) {
          for (QueryCondition c : conditions.get(i)) {
            if (c.hasRadius() && c.k() != k) {
              continue;
            }
            NeighborList g = truth.get(i, c.key());
            if (g == null) {
              unscored++;
              continue;
            }
            QuerySpec q = qf.build(layout, tm.panel().get(i), c, k, null, ExactVariant.NO_INDEX);
            QueryExecutor.Result r = exec.execute(q, 0);
            if (r.failed()) {
              failures++;
              continue;
            }
            byFamily.computeIfAbsent(c.family(), f -> new ArrayList<>())
              .add(scoring.score(q, r, g, k, 0));
          }
        }
        Map<String, Object> l = new LinkedHashMap<>();
        double worst = 1.0;
        long violations = 0;
        for (Map.Entry<String, List<QueryScore>> e : byFamily.entrySet()) {
          ScoreSummary summary = ScoreSummary.of(e.getValue(), 0, 0);
          double recall = summary.recall().mean();
          l.put(e.getKey(), recall);
          worst = Math.min(worst, recall);
          violations += summary.violations();
        }
        l.put("failures", failures);
        l.put("unscored", unscored);
        l.put("violations", violations);
        l.put("min_recall", worst);
        out.put(layout.id(), l);
        this.out.printf(Locale.ROOT, "truth: exact plan recall on %s %.4f (min over families)%s%n",
          layout.id(), worst, failures > 0 ? ", " + failures + " failed queries" : "");
      }
    }
    return out;
  }
}
