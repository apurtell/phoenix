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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.observe.ClusterObserver;
import org.apache.phoenix.vectorharness.observe.HnswRegionObserver;
import org.apache.phoenix.vectorharness.observe.IvfCentroidObserver;
import org.apache.phoenix.vectorharness.observe.JmxReader;
import org.apache.phoenix.vectorharness.observe.JmxReaders;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.apache.phoenix.vectorharness.truth.ConditionPlanner;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.LiveGroundTruth;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.truth.ScoreSummary;
import org.apache.phoenix.vectorharness.variant.ExactVariant;
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IndexVariant;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.apache.phoenix.vectorharness.workload.FrontierSweep;
import org.apache.phoenix.vectorharness.workload.Loader;
import org.apache.phoenix.vectorharness.workload.Measurement;
import org.apache.phoenix.vectorharness.workload.Mutator;
import org.apache.phoenix.vectorharness.workload.QueryExecutor;
import org.apache.phoenix.vectorharness.workload.QueryFactory;
import org.apache.phoenix.vectorharness.workload.QueryPool;
import org.apache.phoenix.vectorharness.workload.QuerySpec;
import org.apache.phoenix.vectorharness.workload.RecallSampler;
import org.apache.phoenix.vectorharness.workload.RowWriter;
import org.apache.phoenix.vectorharness.workload.RunContext;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.SteadyState;
import org.apache.phoenix.vectorharness.workload.TruthManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The run phase: one measured run of one variant over loaded and indexed tables. It checks the
 * validity of the setup (the exact plan must reach recall 1 and a committed row must be visible at
 * once), streams the rows loaded after the build through the live write paths, sweeps query breadth
 * for the efficiency frontier, runs the steady-state windows at each offered rate with mutations,
 * recall sampling, freshness probes, and disruptions, and finally records the metrics, the
 * lifecycle state, and the storage footprint. Mutations that the run committed are reverted at the
 * end, also when a stage fails, so the next run starts from the loaded corpus; the load state marks
 * the tables dirty from the first committed write until the revert completes, so that a run that
 * dies before it leaves tables the next run refuses. Everything lands in the run directory.
 */
public final class RunPhase {

  private static final Logger LOG = LoggerFactory.getLogger(RunPhase.class);
  static final int VALIDITY_QUERIES = 20;
  static final int VALIDITY_FRESHNESS_PROBES = 5;
  static final String SUMMARY = "summary.txt";

  private final HarnessContext ctx;
  private final PrintStream out;
  private final String runIdSuffix;

  public RunPhase(HarnessContext ctx, PrintStream out, String runIdSuffix) {
    this.ctx = ctx;
    this.out = out;
    this.runIdSuffix = runIdSuffix == null ? "" : runIdSuffix;
  }

  public Manifest run() throws Exception {
    HarnessConfig c = ctx.config();
    RunStore store = ctx.store();
    Path loadPath = store.loadState(ctx.tables().suffix());
    if (!Files.exists(loadPath)) {
      throw new IllegalStateException("No load state at " + loadPath + "; run load first");
    }
    Manifest load = Manifest.read(loadPath);
    if (!ctx.corpus().fingerprint().equals(load.getString("load.fingerprint"))) {
      throw new IllegalStateException("The loaded tables hold another corpus; reload");
    }
    if (load.get("load.completed_ms") == null) {
      throw new IllegalStateException("The last load did not complete; reload");
    }
    if (Boolean.FALSE.equals(load.get("truth.published_agrees"))) {
      throw new IllegalStateException("The static ground truth of the loaded tables disagrees"
        + " with the published ground truth; see " + loadPath);
    }
    if (Boolean.TRUE.equals(load.get("load.dirty"))) {
      throw new IllegalStateException("The tables hold mutations of an earlier run that were not"
        + " reverted; reload them, or restore the snapshot with build-index");
    }
    IndexVariant variant = Variants.of(c.variant());
    Path buildPath = store.buildState(ctx.tables().suffix());
    Manifest build = Files.exists(buildPath) ? Manifest.read(buildPath) : null;
    if (build != null && !describesLoad(build, load)) {
      if (c.variant() != VariantKind.EXACT) {
        throw new IllegalStateException("The build state at " + buildPath
          + " describes an earlier build or load; run build-index");
      }
      build = null;
    }
    if (build == null && c.variant() != VariantKind.EXACT) {
      throw new IllegalStateException("No build state at " + buildPath + "; run build-index");
    }
    String runId = (c.runId() != null
      ? c.runId()
      : RunStore.newRunId(variant.id(), c.profileName(), Instant.now())) + runIdSuffix;
    Path runDir = store.runDir(runId);
    Files.createDirectories(runDir);
    // A reused run identifier: until this run completes, the directory is not a finished run, so
    // that its partial CSVs never pair with the manifest of an earlier run.
    Files.deleteIfExists(runDir.resolve(RunStore.MANIFEST));
    Files.deleteIfExists(runDir.resolve(SUMMARY));
    Manifest manifest = new Manifest();
    manifest.put("run", "id", runId);
    manifest.put("run", "variant", variant.id());
    manifest.put("run", "profile", c.profileName());
    manifest.put("run", "start_ms", System.currentTimeMillis());
    manifest.put("run", "java", System.getProperty("java.version"));
    manifest.put("run", "simd_vector_api", simdAvailable());
    manifest.put("run", "deterministic", c.deterministic());
    manifest.putSection("settings", c.effectiveSettings());
    manifest.putSection("corpus", ctx.corpus().parameters());
    manifest.put("corpus", "fingerprint", ctx.corpus().fingerprint());
    manifest.put("corpus", "panel_digest", ctx.corpus().panelDigest(c.panelSize()));
    manifest.put("corpus", "metric", ctx.metric().name());
    manifest.putSection("load", load.section("load"));
    manifest.putSection("ingest", load.section("ingest"));
    manifest.putSection("load_footprint", load.section("footprint"));
    if (build != null) {
      manifest.putSection("build", build.asMap());
    }
    long startMillis = System.currentTimeMillis();
    try (EventLog events = new EventLog(runDir.resolve("events.csv"), startMillis);
      Connection conn = ctx.connection()) {
      manifest.putSection("cluster", clusterInfo(conn));
      Map<TableLayout, IndexInfo> indexes = new LinkedHashMap<>();
      for (TableLayout layout : c.layouts()) {
        IndexInfo info = IndexInfo.read(conn, ctx.tables().fullIndex(layout));
        if (c.variant() != VariantKind.EXACT && !info.active()) {
          throw new IllegalStateException(
            "Index " + info.name() + " is not ACTIVE: " + info.state());
        }
        indexes.put(layout, info);
      }
      TruthManager tm = new TruthManager(ctx);
      long loaded = ((Number) load.get("load.rows_loaded")).longValue();
      events.stage("truth");
      GroundTruth staticTruth = tm.truth(loaded);
      List<List<QueryCondition>> conditions = tm.conditions(staticTruth);
      LiveCorpus live = new LiveCorpus(ctx.corpus(), loaded);
      QueryFactory queries =
        new QueryFactory(ctx.tables(), new Schema(c, ctx.tables(), ctx.corpus().dim()),
          ctx.metric(), ctx.corpus().attributeModel(), c.multiTenant());
      List<String> indexNames = new ArrayList<>();
      Map<String, String> indexToTable = new LinkedHashMap<>();
      for (TableLayout layout : c.layouts()) {
        indexNames.add(ctx.tables().fullIndex(layout));
        indexToTable.put(ctx.tables().fullIndex(layout), ctx.tables().fullTable(layout));
      }
      try (JmxReader reader = JmxReaders.create(c, conn);
        CsvTable metricsCsv =
          CsvTable.create(runDir.resolve("metrics.csv"), VectorIndexMetricsObserver.csvColumns());
        CsvTable clusterCsv =
          CsvTable.create(runDir.resolve("cluster.csv"), ClusterObserver.csvColumns());
        CsvTable frontierCsv =
          CsvTable.create(runDir.resolve("frontier.csv"), FrontierSweep.csvColumns());
        CsvTable steadyCsv =
          CsvTable.create(runDir.resolve("steady.csv"), Measurement.csvColumns());
        CsvTable throughputCsv =
          CsvTable.create(runDir.resolve("throughput.csv"), SteadyState.throughputColumns());
        CsvTable recallCsv =
          CsvTable.create(runDir.resolve("recall.csv"), RecallSampler.csvColumns());
        CsvTable lifecycleCsv = CsvTable.create(runDir.resolve("lifecycle.csv"),
          c.variant() == VariantKind.HNSW
            ? HnswRegionObserver.csvColumns()
            : IvfCentroidObserver.csvColumns());
        VectorIndexMetricsObserver metrics =
          new VectorIndexMetricsObserver(reader, indexNames, metricsCsv, events);
        ClusterObserver cluster = new ClusterObserver(reader, clusterCsv, events);
        IvfCentroidObserver ivf = c.variant() == VariantKind.IVF
          ? new IvfCentroidObserver(ctx::connection, indexNames, lifecycleCsv, events)
          : null;
        HnswRegionObserver hnsw = c.variant() == VariantKind.HNSW
          ? new HnswRegionObserver(ctx::connection, indexToTable, runDir, lifecycleCsv, events)
          : null;
        RunContext rc = new RunContext(ctx, variant, c.layouts(), events, manifest, runDir, live,
          tm.panel(), conditions, queries, metrics)) {
        rc.indexes().putAll(indexes);
        rc.staticTruth(staticTruth);
        manifest.put("run", "metrics_mode", reader.mode());
        metrics.start(c.observeInterval());
        cluster.start(c.observeInterval());
        if (ivf != null) {
          ivf.start(c.observeInterval());
        }
        if (hnsw != null) {
          hnsw.start(c.observeInterval(), c.fsckInterval());
        }
        metrics.snapshot("run-start");
        boundary(rc, hnsw, ivf, "run-start");

        int tracked = c.sampleQueries() <= 0
          ? tm.panel().size()
          : Math.min(c.sampleQueries(), tm.panel().size());
        int capacity =
          (int) Math.ceil(Arrays.stream(c.kValues()).max().orElse(10) * c.truthMargin());
        final GroundTruth initial = staticTruth;
        LiveGroundTruth liveTruth = new LiveGroundTruth(ctx.metric(),
          tm.panel().subList(0, tracked), conditions.subList(0, tracked), initial::get, capacity,
          live, Math.max(1, Runtime.getRuntime().availableProcessors()));
        rc.liveTruth(liveTruth);
        manifest.put("run", "tracked_queries", tracked);

        SweepPoint defaultPoint = variant.defaultPoint(ctx, indexes.values().iterator().next());
        List<Mutator> mutators = new ArrayList<>();
        Mutator validityMutator = new Mutator(c, ctx.corpus(), live, liveTruth, rc.gate(), null);
        mutators.add(validityMutator);
        // The validity probes commit the first writes. From here until the revert completes the
        // tables hold mutations that only this process knows, so a run that dies in between must
        // leave them marked dirty.
        load.put("load", "dirty", true);
        load.put("load", "dirty_by", runId);
        load.write(loadPath);
        boolean completed = false;
        boolean streamPending = false;
        try {
          validity(rc, validityMutator, defaultPoint);

          if (loaded < ctx.corpus().rows()) {
            // A stream that stops part way leaves rows that rows_loaded does not count.
            streamPending = true;
            stream(rc, defaultPoint, recallCsv, loaded);
            load.put("load", "rows_loaded", ctx.corpus().rows());
            load.put("load", "streamed_by", runId);
            load.write(loadPath);
            streamPending = false;
            GroundTruth full = tm.truth(ctx.corpus().rows());
            rc.staticTruth(full);
          }

          events.stage("frontier");
          boundary(rc, hnsw, ivf, "frontier-start");
          List<Map<String, Object>> frontier = new FrontierSweep(rc, frontierCsv, "frontier").run();
          manifest.put("stages", "frontier", frontier);

          LongPredicate hotspot = hotspot(c);
          Mutator mutator = new Mutator(c, ctx.corpus(), live, liveTruth, rc.gate(), hotspot);
          mutators.add(mutator);
          List<Map<String, Object>> windows = new ArrayList<>();
          for (double rate : c.queryRates()) {
            String stage = String.format(Locale.ROOT, "steady@%s", trim(rate));
            boundary(rc, hnsw, ivf, stage + "-start");
            SteadyState steady =
              new SteadyState(rc, mutator, defaultPoint, steadyCsv, throughputCsv, recallCsv);
            Map<String, Object> w = steady.run(rate, stage);
            windows.add(w);
            printWindow(w);
          }
          manifest.put("stages", "steady", windows);

          events.stage("final");
          metrics.snapshot("run-end");
          boundary(rc, hnsw, ivf, "run-end");
          manifest.putSection("metric_snapshots", snapshotsMap(metrics));
          manifest.putSection("cluster_summary", cluster.summary());
          if (ivf != null) {
            manifest.put("lifecycle", "ivf_migrations", ivf.migrations());
          }
          if (hnsw != null) {
            manifest.put("lifecycle", "hnsw_inspections", hnsw.inspections());
          }
          manifest.put("lifecycle", "live_truth_recomputes", liveTruth.recomputes());
          manifest.put("lifecycle", "live_truth_updates", liveTruth.updates());
          manifest.putSection("footprint", footprint(conn, c));
          metrics.close();
          cluster.close();
          if (ivf != null) {
            ivf.close();
          }
          if (hnsw != null) {
            hnsw.close();
          }
          completed = true;
        } finally {
          // Revert the mutations also after a failed stage, so that a failure in process leaves
          // clean tables where it can; the load state says whether it could.
          events.stage("restore");
          Map<String, Object> restore = restore(rc, mutators);
          manifest.putSection("restore", restore);
          boolean clean = Boolean.TRUE.equals(restore.get("complete")) && !streamPending;
          load.put("load", "dirty", !clean);
          if (clean) {
            load.section("load").remove("dirty_by");
          }
          try {
            load.write(loadPath);
          } catch (IOException e) {
            if (completed) {
              throw e;
            }
            LOG.error("Writing the load state after a failed run failed", e);
          }
        }
      }
      List<Map<String, Object>> ev = new ArrayList<>();
      for (EventLog.Event e : events.events()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("offset_ms", e.offsetMillis());
        m.put("stage", e.stage());
        m.put("kind", e.kind());
        m.put("detail", e.detail());
        ev.add(m);
      }
      manifest.put("events", "count", ev.size());
    }
    manifest.put("run", "end_ms", System.currentTimeMillis());
    manifest.write(runDir.resolve(RunStore.MANIFEST));
    String summary = org.apache.phoenix.vectorharness.report.ReportGenerator.of(c).summary(runDir);
    Files.write(runDir.resolve(SUMMARY), summary.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.println("run " + runId + " written to " + runDir);
    return manifest;
  }

  /** Checks the exact plan against the truth and the freshness of committed writes. */
  private void validity(RunContext rc, Mutator mutator, SweepPoint point) throws Exception {
    rc.events().stage("validity");
    HarnessConfig c = rc.ctx().config();
    int k = c.kValues()[0];
    int n = Math.min(VALIDITY_QUERIES, rc.panel().size());
    List<QueryScore> scores = new ArrayList<>();
    long attempted = 0;
    long failures = 0;
    long missingTruth = 0;
    try (QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      for (TableLayout layout : rc.layouts()) {
        for (int i = 0; i < n; i++) {
          // Every condition of the query, so that the exact plan also checks the predicates of
          // the filtered, tenant-scoped, key-range, and distance-bounded shapes.
          List<QueryCondition> conds = new ArrayList<>();
          for (QueryCondition cond : rc.conditions().get(i)) {
            if (!cond.hasRadius() || cond.k() == k) {
              conds.add(cond);
            }
          }
          if (conds.isEmpty()) {
            conds.add(QueryCondition
              .builder(org.apache.phoenix.vectorharness.config.QueryShape.UNFILTERED).build());
          }
          for (QueryCondition cond : conds) {
            attempted++;
            QuerySpec q =
              rc.queries().build(layout, rc.panel().get(i), cond, k, point, ExactVariant.NO_INDEX);
            QueryExecutor.Result r = exec.execute(q, 0);
            NeighborList truth = rc.truth(i, cond, k);
            if (truth == null && cond.isUnconstrained()) {
              truth = rc.staticTruth().get(i, "unfiltered");
            }
            if (r.failed()) {
              failures++;
            } else if (truth == null) {
              missingTruth++;
            } else {
              scores.add(rc.scoring().score(q, r, truth, k, 0));
            }
          }
        }
      }
    }
    ScoreSummary exact = ScoreSummary.of(scores, 0, 0);
    double exactRecall = exact.recall().mean();
    long violations = exact.violations();
    SteadyState probe = new SteadyState(rc, mutator, point, null, null, null);
    double freshness = probe.freshnessCheck(VALIDITY_FRESHNESS_PROBES);
    long freshnessErrors = probe.freshnessErrors();
    rc.manifest().put("validity", "exact_recall", exactRecall);
    rc.manifest().put("validity", "exact_queries", scores.size());
    rc.manifest().put("validity", "exact_attempted", attempted);
    rc.manifest().put("validity", "exact_failures", failures);
    rc.manifest().put("validity", "exact_violations", violations);
    rc.manifest().put("validity", "freshness", freshness);
    rc.manifest().put("validity", "freshness_errors", freshnessErrors);
    out.printf(Locale.ROOT, "[validity] exact recall %.3f; freshness %.3f%s%n", exactRecall,
      freshness,
      failures + missingTruth + freshnessErrors == 0
        ? ""
        : String.format(Locale.ROOT,
          " (%d of %d exact queries failed, %d without truth; %d" + " freshness probes failed)",
          failures, attempted, missingTruth, freshnessErrors));
    for (String flag : validityFlags(exactRecall, scores.size(), attempted, failures, missingTruth,
      freshness, freshnessErrors, violations)) {
      rc.manifest().flag(flag);
    }
  }

  /**
   * Returns the flags of the validity check. A check that could not run is not a pass: exact
   * queries that failed or had no truth, and freshness probes that failed, are flagged, and an
   * exact recall that is NaN because nothing was scored is flagged too. Freshness counts only the
   * probes that got an answer.
   */
  static List<String> validityFlags(double exactRecall, long scored, long attempted, long failures,
    long missingTruth, double freshness, long freshnessErrors) {
    return validityFlags(exactRecall, scored, attempted, failures, missingTruth, freshness,
      freshnessErrors, 0);
  }

  static List<String> validityFlags(double exactRecall, long scored, long attempted, long failures,
    long missingTruth, double freshness, long freshnessErrors, long violations) {
    List<String> flags = new ArrayList<>();
    if (violations > 0) {
      flags
        .add("the exact plan returned " + violations + " rows that fail their query's predicate");
    }
    if (!(exactRecall >= 1.0 - 1e-9)) {
      flags.add("the exact plan reached recall " + exactRecall + " over " + scored + " of "
        + attempted + " queries against the client-side ground truth");
    }
    if (failures > 0 || missingTruth > 0) {
      flags.add(failures + " of " + attempted + " exact validity queries failed"
        + (missingTruth > 0 ? " and " + missingTruth + " had no ground truth" : ""));
    }
    if (freshness < 1.0) {
      flags.add("freshness " + freshness + ": committed rows were not visible at once");
    }
    if (freshnessErrors > 0) {
      flags.add(
        freshnessErrors + " of " + VALIDITY_FRESHNESS_PROBES + " validity freshness probes failed");
    }
    return flags;
  }

  /** Streams the rows loaded after the build through the live index write paths. */
  private void stream(RunContext rc, SweepPoint point, CsvTable recallCsv, long from)
    throws Exception {
    HarnessConfig c = rc.ctx().config();
    rc.events().stage("stream");
    long to = rc.ctx().corpus().rows();
    LiveGroundTruth lt = rc.liveTruth();
    Loader loader =
      new Loader(rc.ctx(), rc.layouts(), c.ingestThreads(), c.ingestBatchSize(), c.streamRate())
        .withGate(rc.gate());
    AtomicReference<Exception> failure = new AtomicReference<>();
    final Loader.Result[] result = new Loader.Result[1];
    Thread t = new Thread(() -> {
      try {
        result[0] = loader.load(from, to, new Loader.BatchListener() {
          @Override
          public void starting(long a, long b) {
            // The rows of a batch become visible while it commits, before they are in the
            // truth: online sampling excludes them while they are in flight.
            for (long o = a; o < b; o++) {
              rc.live().beginWrite(o);
            }
          }

          @Override
          public void committed(long a, long b) {
            rc.live().markLoaded(a, b);
            for (long o = a; o < b; o++) {
              DocAttributes attrs = rc.ctx().corpus().attributes(o);
              lt.apply(o, rc.ctx().corpus().vector(o), attrs);
            }
          }

          @Override
          public void finished(long a, long b) {
            for (long o = a; o < b; o++) {
              rc.live().endWrite(o);
            }
          }
        });
      } catch (Exception e) {
        failure.set(e);
      }
    }, "vh-stream");
    t.start();
    RecallSampler sampler = new RecallSampler(rc, recallCsv, point);
    try (QueryPool pool = new QueryPool(rc.ctx(), c.queryThreads(), "vh-stream-sample")) {
      long next = System.nanoTime() + c.sampleInterval().toNanos();
      while (t.isAlive()) {
        t.join(Math.max(1, (next - System.nanoTime()) / 1_000_000));
        if (t.isAlive() && System.nanoTime() >= next) {
          sampler.sample(pool);
          next += c.sampleInterval().toNanos();
        }
      }
      sampler.sample(pool);
    }
    if (failure.get() != null) {
      throw failure.get();
    }
    Map<String, Object> m = new LinkedHashMap<>(result[0].toMap());
    m.put("from", from);
    m.put("to", to);
    m.put("recall_samples", sampler.samples().size());
    rc.manifest().putSection("stream", m);
    out.printf(Locale.ROOT, "[stream] %d rows in %.1f s (%.0f rows/s)%n", to - from,
      result[0].seconds(), result[0].rowsPerSecond());
  }

  /** Takes the stage-boundary observations of the supplementary observers. */
  private void boundary(RunContext rc, HnswRegionObserver hnsw, IvfCentroidObserver ivf,
    String label) {
    if (rc.metrics() != null) {
      rc.metrics().snapshot(label);
    }
    try {
      if (hnsw != null) {
        hnsw.sampleRegions();
        if (rc.ctx().config().fsckEnabled()) {
          hnsw.inspect(label);
        }
      }
      if (ivf != null) {
        rc.manifest().put("lifecycle", "ivf_" + label, ivf.sample(true));
      }
    } catch (Exception e) {
      LOG.warn("Stage boundary observation {} failed", label, e);
    }
  }

  /** Returns the hotspot filter of mutations: rows in the first n regions of the key space. */
  private LongPredicate hotspot(HarnessConfig c) {
    int n = c.hotspotRegions();
    if (n <= 0) {
      return null;
    }
    if (c.saltBuckets() > 0) {
      LOG.warn("mutation.hotspot.regions is ignored on salted tables");
      return null;
    }
    List<byte[]> splits =
      new Schema(c, ctx.tables(), ctx.corpus().dim()).splitPoints(ctx.corpus(), c.regions());
    if (splits.isEmpty() || n >= splits.size() + 1) {
      return null;
    }
    byte[] bound = splits.get(n - 1);
    return ordinal -> Bytes.compareTo(Schema.rowKey(ctx.corpus().attributes(ordinal)), bound) < 0;
  }

  /**
   * Returns true if a build state describes the index over the loaded tables: it records the
   * fingerprint of the load and completed after it.
   */
  static boolean describesLoad(Manifest build, Manifest load) {
    Object built = build.get("build.completed_ms");
    Object loadedAt = load.get("load.completed_ms");
    String fp = load.getString("load.fingerprint");
    return fp != null && fp.equals(build.getString("build.fingerprint")) && built instanceof Number
      && loadedAt instanceof Number
      && ((Number) built).longValue() >= ((Number) loadedAt).longValue();
  }

  /**
   * Reverts the mutations of the run: restores updated and deleted base rows, deletes inserts. Rows
   * whose commit failed are rewritten too, since the failed commit may have reached the tables: an
   * unresolved base row gets its base state again, and an unresolved inserted row is deleted by its
   * key. A failure of the revert is recorded, not thrown, so that the run can mark the tables
   * dirty.
   */
  private Map<String, Object> restore(RunContext rc, List<Mutator> mutators) {
    LiveCorpus live = rc.live();
    Map<String, Object> m = new LinkedHashMap<>();
    long restored = 0;
    long deleted = 0;
    long t0 = System.nanoTime();
    TreeSet<Long> unresolved = new TreeSet<>();
    for (Mutator mutator : mutators) {
      unresolved.addAll(mutator.unresolved());
    }
    TreeSet<Long> ordinals = new TreeSet<>(live.overlays().keySet());
    ordinals.addAll(unresolved);
    long rows = rc.ctx().corpus().rows();
    try (Connection conn = rc.ctx().connection()) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        int pending = 0;
        for (long o : ordinals) {
          LiveCorpus.RowState s = live.overlaid(o);
          if (o < rows) {
            writer.upsert(rc.ctx().corpus().attributes(o), rc.ctx().corpus().vector(o));
            restored++;
          } else if (s == null || !s.deleted() || unresolved.contains(o)) {
            // Inserted rows are keyed by the attributes of their ordinal; deleting a row that is
            // absent does no harm.
            writer
              .delete(s != null && !s.deleted() ? s.attributes() : rc.ctx().corpus().attributes(o));
            deleted++;
          }
          if (++pending >= rc.ctx().config().ingestBatchSize()) {
            writer.commit();
            pending = 0;
          }
        }
        writer.commit();
      }
      for (long o : ordinals) {
        live.forget(o);
      }
      for (Mutator mutator : mutators) {
        for (long o : unresolved) {
          mutator.resolve(o);
        }
      }
      m.put("complete", true);
    } catch (SQLException | RuntimeException e) {
      LOG.error("Reverting the mutations of the run failed; the tables are left dirty", e);
      m.put("complete", false);
      m.put("error", e.getMessage());
    }
    m.put("restored_base_rows", restored);
    m.put("deleted_inserted_rows", deleted);
    m.put("unresolved_rows", unresolved.size());
    m.put("seconds", (System.nanoTime() - t0) / 1e9);
    return m;
  }

  private Map<String, Object> footprint(Connection conn, HarnessConfig c) {
    Map<String, Object> f = new LinkedHashMap<>();
    try {
      for (TableLayout layout : c.layouts()) {
        String table = ctx.tables().fullTable(layout);
        StorageFootprint.flush(conn, table);
        f.put(table, StorageFootprint.measure(conn, table));
        if (c.variant() == VariantKind.IVF) {
          String index = ctx.tables().fullIndex(layout);
          StorageFootprint.flush(conn, index);
          f.put(index, StorageFootprint.measure(conn, index));
        }
      }
    } catch (SQLException | IOException e) {
      LOG.warn("Measuring the footprint failed", e);
      f.put("error", e.getMessage());
    }
    return f;
  }

  private Map<String, Object> clusterInfo(Connection conn) {
    Map<String, Object> m = new LinkedHashMap<>();
    try (Admin admin = HarnessContext.admin(conn)) {
      org.apache.hadoop.hbase.ClusterMetrics cm = admin.getClusterMetrics();
      m.put("hbase_version", cm.getHBaseVersion());
      m.put("region_servers", cm.getLiveServerMetrics().size());
      m.put("cluster_id", cm.getClusterId());
    } catch (IOException | SQLException e) {
      m.put("error", e.getMessage());
    }
    return m;
  }

  private static Map<String, Object> snapshotsMap(VectorIndexMetricsObserver metrics) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Map.Entry<String, org.apache.phoenix.vectorharness.observe.MetricSnapshot> e : metrics
      .labeledSnapshots().entrySet()) {
      m.put(e.getKey(), e.getValue().toMap());
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private void printWindow(Map<String, Object> w) {
    Map<String, Object> lat = (Map<String, Object>) w.get("query_latency");
    out.printf(Locale.ROOT, "[%s] %s qps=%.1f p50=%sms p99=%sms mutations/s=%.1f freshness=%s%n",
      w.get("stage"), w.get("sweep"), num(w.get("achieved_qps")), fmt(lat.get("p50_ms")),
      fmt(lat.get("p99_ms")), num(w.get("achieved_mutations_per_second")), fmt(w.get("freshness")));
  }

  private static double num(Object o) {
    return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN;
  }

  private static String fmt(Object o) {
    return o instanceof Number
      ? String.format(Locale.ROOT, "%.3f", ((Number) o).doubleValue())
      : "n/a";
  }

  static String trim(double d) {
    return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
  }

  static boolean simdAvailable() {
    try {
      Class.forName("jdk.incubator.vector.FloatVector");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  /** Returns the truth capacity of a profile, for reports. */
  static int capacity(HarnessConfig c) {
    return ConditionPlanner.truthCapacity(c);
  }
}
