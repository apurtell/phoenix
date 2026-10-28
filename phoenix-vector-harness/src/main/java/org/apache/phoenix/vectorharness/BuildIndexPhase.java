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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.observe.IndexFsck;
import org.apache.phoenix.vectorharness.observe.IvfCentroidObserver;
import org.apache.phoenix.vectorharness.observe.JmxReader;
import org.apache.phoenix.vectorharness.observe.JmxReaders;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.observe.PlanClassifier;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IndexVariant;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.apache.phoenix.vectorharness.workload.QueryExecutor;
import org.apache.phoenix.vectorharness.workload.QueryFactory;
import org.apache.phoenix.vectorharness.workload.QuerySpec;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.Statistics;
import org.apache.phoenix.vectorharness.workload.TruthManager;

/**
 * Executes the build index phase, creating vector indexes for configured table layouts, awaiting
 * index activation, collecting build metrics and storage footprints, and validating query execution
 * plans.
 */
public final class BuildIndexPhase {

  /** Polling interval when awaiting HNSW coverage. */
  static final long COVERAGE_POLL_MS = 100;

  private final HarnessContext ctx;
  private final PrintStream out;

  public BuildIndexPhase(HarnessContext ctx, PrintStream out) {
    this.ctx = ctx;
    this.out = out;
  }

  public Manifest run() throws Exception {
    HarnessConfig c = ctx.config();
    Manifest load = readLoadState();
    IndexVariant variant = Variants.of(c.variant());
    long prebuild = ((Number) load.get("load.prebuild_rows")).longValue();
    List<String> problems = variant.validate(ctx, ctx.corpus().dim(), prebuild);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
        "Invalid profile for variant " + variant.id() + ":\n  " + String.join("\n  ", problems));
    }
    boolean restore = c.snapshot() && load.get("load.snapshots") != null;
    long loadedNow = ((Number) load.get("load.rows_loaded")).longValue();
    if (!restore && loadedNow != prebuild) {
      throw new IllegalStateException("The tables hold " + loadedNow + " rows, but the index is"
        + " built over the " + prebuild + " rows loaded before the build, and the rest arrive"
        + " through the stream of a run; reload, or set " + Setting.TABLE_SNAPSHOT.key()
        + "=true and reload");
    }
    Manifest state = new Manifest();
    state.put("build", "variant", variant.id());
    state.put("build", "profile", c.profileName());
    state.put("build", "fingerprint", load.getString("load.fingerprint"));
    state.put("build", "rows_indexed", prebuild);
    state.put("build", "warnings", variant.warnings(ctx, ctx.corpus().dim(), prebuild));
    state.put("build", "statistics", c.statisticsMode().label());
    Schema schema = new Schema(c, ctx.tables(), ctx.corpus().dim());
    try (Connection conn = ctx.connection()) {
      // A cluster in the other mode fails here, before the build removes the last build state.
      state.putSection("statistics", Statistics.check(c, conn));
      Files.deleteIfExists(ctx.store().buildState(ctx.tables().suffix()));
      if (restore) {
        restoreSnapshots(conn, state);
      } else {
        for (TableLayout layout : c.layouts()) {
          dropIndexIfExists(conn, layout);
        }
      }
      List<String> indexes = new ArrayList<>();
      for (TableLayout layout : c.layouts()) {
        indexes.add(ctx.tables().fullIndex(layout));
      }
      try (JmxReader reader = JmxReaders.create(c, conn)) {
        VectorIndexMetricsObserver metrics =
          new VectorIndexMetricsObserver(reader, indexes, null, null);
        MetricSnapshot before = metrics.snapshot("build-start");
        Map<String, Object> builds = new LinkedHashMap<>();
        long start = System.nanoTime();
        // The region layout is harness work that a naturally split index does not do at build
        // time. Thus the build time excludes it, and the layout time has its own field.
        double layoutSeconds = 0;
        boolean laidOut = false;
        for (TableLayout layout : c.layouts()) {
          Map<String, Object> b = variant.build(ctx, conn, schema, layout, null);
          builds.put(layout.id(), b);
          Object secs = b.get("seconds");
          out.printf(Locale.ROOT, "%s %s on %s ACTIVE in %.1f s%n", variant.id(),
            ctx.tables().fullIndex(layout), ctx.tables().fullTable(layout),
            secs instanceof Number ? ((Number) secs).doubleValue() : 0.0);
          Object regions = b.get("regions");
          if (regions instanceof Map) {
            Map<?, ?> r = (Map<?, ?>) regions;
            Object ls = r.get("seconds");
            double layoutSecs = ls instanceof Number ? ((Number) ls).doubleValue() : 0.0;
            layoutSeconds += layoutSecs;
            laidOut = true;
            out.printf(Locale.ROOT,
              "%s index regions %s, populated %s, size cv %s, per server %s, layout in %.1f s%n",
              ctx.tables().fullIndex(layout), r.get("index_regions"), r.get("populated_regions"),
              r.get("size_cv"), r.get("regions_per_server"), layoutSecs);
          }
        }
        if (
          c.variant() == VariantKind.HNSW
            && !awaitCoverage(metrics, indexes, c.buildTimeout().toMillis())
        ) {
          state.put("build", "coverage_timed_out", true);
          out.println(
            "warning: the metrics still show uncovered HNSW regions after " + c.buildTimeout());
        }
        state.put("build", "seconds", (System.nanoTime() - start) / 1e9 - layoutSeconds);
        if (laidOut) {
          state.put("build", "layout_seconds", layoutSeconds);
        }
        state.putSection("indexes", builds);
        MetricSnapshot after = metrics.snapshot("build-end");
        state.put("build", "metrics_mode", reader.mode());
        state.putSection("build_metrics", after.deltas(before));
        state.put("build", "metrics_end", after.toMap());
      }
      if (c.variant() == VariantKind.HNSW && c.fsckEnabled()) {
        Map<String, Object> fsck = new LinkedHashMap<>();
        for (TableLayout layout : c.layouts()) {
          Map<String, Object> list = IndexFsck.inspect(conn, ctx.tables().fullTable(layout),
            ctx.tables().fullIndex(layout), "list", null);
          fsck.put(layout.id(), IndexFsck.summarizeList(list));
          Files.createDirectories(ctx.store().tableDir(ctx.tables().suffix()));
          org.apache.phoenix.vectorharness.result.Json.write(ctx.store()
            .tableDir(ctx.tables().suffix()).resolve("fsck-build-" + layout.id() + "-list.json"),
            list);
        }
        state.putSection("segments", fsck);
      }
      if (c.variant() == VariantKind.IVF) {
        List<String> names = new ArrayList<>();
        for (TableLayout layout : c.layouts()) {
          names.add(ctx.tables().fullIndex(layout));
        }
        IvfCentroidObserver ivf = new IvfCentroidObserver(ctx::connection, names, null, null);
        state.putSection("centroids", ivf.sample(true));
      }
      // The statistics come after the index layout, so each final region has its guideposts.
      if (c.statisticsOn()) {
        List<String> tables = new ArrayList<>();
        for (TableLayout layout : c.layouts()) {
          tables.add(ctx.tables().fullTable(layout));
        }
        long t0 = System.nanoTime();
        Statistics.update(ctx, tables, false);
        state.put("build", "statistics_seconds", (System.nanoTime() - t0) / 1e9);
        state.put("build", "statistics_updated", true);
      } else {
        state.put("build", "statistics_updated", false);
      }
      // Both modes record the statistics rows. The off mode must have none.
      state.putSection("guideposts",
        Statistics.guideposts(conn, ctx.tables(), c.layouts(), c.variant()));
      state.putSection("plan_check", planCheck(variant));
      Map<String, Object> footprint = new LinkedHashMap<>();
      for (TableLayout layout : c.layouts()) {
        String table = ctx.tables().fullTable(layout);
        StorageFootprint.flush(conn, table);
        footprint.put(table, StorageFootprint.measure(conn, table));
        if (c.variant() == VariantKind.IVF) {
          String index = ctx.tables().fullIndex(layout);
          StorageFootprint.flush(conn, index);
          footprint.put(index, StorageFootprint.measure(conn, index));
        }
      }
      state.putSection("footprint", footprint);
    }
    state.put("build", "completed_ms", System.currentTimeMillis());
    state.write(ctx.store().buildState(ctx.tables().suffix()));
    return state;
  }

  /** Waits until metrics indicate all regions for each HNSW index are covered. */
  static boolean awaitCoverage(VectorIndexMetricsObserver metrics, List<String> indexes,
    long timeoutMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (true) {
      MetricSnapshot s = metrics.sample(null);
      boolean covered = true;
      for (String index : indexes) {
        Double uncovered = s.get("vectorHnswRegionsUncovered." + index);
        if (uncovered != null && uncovered > 0) {
          covered = false;
        }
      }
      if (covered) {
        return true;
      }
      if (System.currentTimeMillis() >= deadline) {
        return false;
      }
      Thread.sleep(COVERAGE_POLL_MS);
    }
  }

  Manifest readLoadState() throws IOException {
    java.nio.file.Path p = ctx.store().loadState(ctx.tables().suffix());
    if (!Files.exists(p)) {
      throw new IllegalStateException("No load state at " + p + "; run the load phase first");
    }
    Manifest load = Manifest.read(p);
    String fp = load.getString("load.fingerprint");
    if (fp == null || !fp.equals(ctx.corpus().fingerprint())) {
      throw new IllegalStateException("The loaded tables hold corpus " + fp
        + " but the profile describes corpus " + ctx.corpus().fingerprint() + "; reload");
    }
    if (load.get("load.completed_ms") == null) {
      throw new IllegalStateException("The last load did not complete; reload");
    }
    String mode = ctx.config().statisticsMode().label();
    String loaded = loadStatistics(load);
    if (!loaded.equals(mode)) {
      throw new IllegalStateException(
        "The tables were loaded with " + Setting.TABLE_STATISTICS.key() + "=" + loaded
          + ", but the profile sets " + mode + "; reload");
    }
    if (Boolean.FALSE.equals(load.get("truth.published_agrees"))) {
      throw new IllegalStateException("The static ground truth of the loaded tables disagrees"
        + " with the published ground truth; see " + p);
    }
    return load;
  }

  /**
   * Returns the statistics mode of a load. A load from before the statistics modes has no mode. Its
   * DDL has no statistics properties, so it counts as the off mode.
   */
  static String loadStatistics(Manifest load) {
    String v = load.getString("load.statistics");
    return v == null ? HarnessConfig.StatisticsMode.OFF.label() : v;
  }

  private void dropIndexIfExists(Connection conn, TableLayout layout) throws Exception {
    if (IndexInfo.read(conn, ctx.tables().fullIndex(layout)).exists()) {
      conn.createStatement().execute("DROP INDEX IF EXISTS " + ctx.tables().index(layout) + " ON "
        + ctx.tables().fullTable(layout));
    }
  }

  /** Drops existing indexes and restores data tables from snapshots. */
  private void restoreSnapshots(Connection conn, Manifest state) throws Exception {
    List<String> restored = new ArrayList<>();
    for (TableLayout layout : ctx.config().layouts()) {
      dropIndexIfExists(conn, layout);
      TableName tn = StorageFootprint.physicalName(conn, ctx.tables().fullTable(layout));
      try (Admin admin = HarnessContext.admin(conn)) {
        if (admin.isTableEnabled(tn)) {
          admin.disableTable(tn);
        }
        admin.restoreSnapshot(ctx.tables().snapshot(layout));
        admin.enableTable(tn);
      }
      restored.add(ctx.tables().snapshot(layout));
    }
    state.put("build", "restored_snapshots", restored);
    Manifest load = Manifest.read(ctx.store().loadState(ctx.tables().suffix()));
    load.put("load", "dirty", false);
    load.section("load").remove("dirty_by");
    load.put("load", "rows_loaded", load.get("load.prebuild_rows"));
    load.write(ctx.store().loadState(ctx.tables().suffix()));
    out.println("restored snapshots " + restored);
  }

  /** Validates query execution plans against expected plans for each condition family. */
  Map<String, Object> planCheck(IndexVariant variant) throws Exception {
    HarnessConfig c = ctx.config();
    Map<String, Object> out = new LinkedHashMap<>();
    TruthManager tm = new TruthManager(ctx);
    GroundTruth truth = tm.truth(((Number) readLoadState().get("load.rows_loaded")).longValue());
    List<List<QueryCondition>> conditions = tm.conditions(truth);
    PanelQuery q = tm.panel().get(0);
    QueryFactory qf =
      new QueryFactory(ctx.tables(), new Schema(c, ctx.tables(), ctx.corpus().dim()), ctx.metric(),
        ctx.corpus().attributeModel(), c.multiTenant());
    int k = c.kValues()[0];
    try (Connection conn = ctx.connection(); QueryExecutor exec = new QueryExecutor(ctx)) {
      for (TableLayout layout : c.layouts()) {
        IndexInfo info = IndexInfo.read(conn, ctx.tables().fullIndex(layout));
        SweepPoint point = variant.defaultPoint(ctx, info);
        int matching = 0;
        int total = 0;
        List<String> others = new ArrayList<>();
        Map<String, Object> families = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (QueryCondition cond : conditions.get(0)) {
          if (!seen.add(cond.family()) || (cond.hasRadius() && cond.k() != k)) {
            continue;
          }
          QuerySpec spec = qf.build(layout, q, cond, k, point);
          PlanClassifier.Classification cls = PlanClassifier.classify(exec.explain(spec));
          PlanClass expected = variant.expectedPlan(cond.shape(), c.multiTenant());
          total++;
          // An HNSW plan for a radius query must send the distance bound. Without it, each region
          // searches without a limit
          boolean unbounded = expected == PlanClass.HNSW_SEARCH && cond.hasRadius()
            && cls.planClass() == expected && !cls.distanceBound();
          if (cls.planClass() != expected) {
            others.add(cond.family() + " uses " + cls.planClass().description());
          } else if (unbounded) {
            others.add(cond.family() + " sends no distance bound");
          } else {
            matching++;
          }
          Map<String, Object> f = new LinkedHashMap<>();
          f.put("plan", cls.toString());
          f.put("expected", expected.name());
          families.put(cond.family(), f);
        }
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("families", families);
        l.put("matching", matching);
        l.put("total", total);
        l.put("discrepancies", others.isEmpty() ? Collections.emptyList() : others);
        out.put(layout.id(), l);
        this.out.printf(Locale.ROOT, "plan check %s: %d/%d shapes take the expected plan%s%n",
          layout.id(), matching, total, others.isEmpty() ? "" : "; " + String.join("; ", others));
      }
    }
    return out;
  }
}
