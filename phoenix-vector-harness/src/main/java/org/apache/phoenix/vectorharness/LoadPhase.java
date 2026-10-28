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
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.IngestMode;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.PublishedTruthValidator;
import org.apache.phoenix.vectorharness.variant.IndexVariant;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.apache.phoenix.vectorharness.workload.BulkLoader;
import org.apache.phoenix.vectorharness.workload.Loader;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TruthManager;

/**
 * The load phase: creates the tables of the configured layouts, pre-split at row key quantiles,
 * ingests the rows that exist before the index (all of them for the bulk build path, a prefix for
 * streaming), computes the static ground truth of the loaded rows and checks it against the
 * published ground truth of a benchmark corpus, optionally snapshots the unindexed tables, and
 * writes the load state that the later phases read.
 */
public final class LoadPhase {

  private final HarnessContext ctx;
  private final PrintStream out;

  public LoadPhase(HarnessContext ctx, PrintStream out) {
    this.ctx = ctx;
    this.out = out;
  }

  public Manifest run() throws Exception {
    HarnessConfig c = ctx.config();
    IndexVariant variant = Variants.of(c.variant());
    long prebuild = c.prebuildRows(ctx.corpus().rows());
    List<String> problems = variant.validate(ctx, ctx.corpus().dim(), prebuild);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
        "Invalid profile for variant " + variant.id() + ":\n  " + String.join("\n  ", problems));
    }
    long t0 = System.nanoTime();
    String fingerprint = ctx.corpus().fingerprint();
    out.printf(Locale.ROOT, "corpus %s fingerprint=%s regions=%d%n", ctx.corpus().description(),
      fingerprint.substring(0, 12) + "...", c.regions());
    Manifest state = new Manifest();
    state.put("load", "profile", c.profileName());
    state.put("load", "variant", variant.id());
    state.put("load", "fingerprint", fingerprint);
    state.put("load", "fingerprint_seconds", (System.nanoTime() - t0) / 1e9);
    state.putSection("corpus", ctx.corpus().parameters());
    state.put("corpus", "description", ctx.corpus().description());
    state.put("corpus", "metric", ctx.metric().name());
    state.putSection("settings", c.effectiveSettings());
    Schema schema = new Schema(c, ctx.tables(), ctx.corpus().dim());
    List<String> tables = new ArrayList<>();
    try (Connection conn = ctx.connection()) {
      List<byte[]> splits = schema.splitPoints(ctx.corpus(), c.regions());
      // The tables are about to be dropped, so the states of the earlier load and build describe
      // nothing any more; a load that fails part way must not leave them for the later phases.
      Files.deleteIfExists(ctx.store().loadState(ctx.tables().suffix()));
      Files.deleteIfExists(ctx.store().buildState(ctx.tables().suffix()));
      for (TableLayout layout : c.layouts()) {
        schema.drop(conn, layout);
        schema.create(conn, layout, splits);
        tables.add(ctx.tables().fullTable(layout));
      }
      state.put("load", "tables", tables);
      state.put("load", "split_points", splits.size());
      state.put("load", "salt_buckets", c.saltBuckets());
      long start = System.nanoTime();
      Map<String, Object> ingest = new LinkedHashMap<>();
      if (c.ingestMode() == IngestMode.BULKLOAD) {
        ingest.putAll(new BulkLoader(ctx).load(0, prebuild));
      } else {
        Loader.Result r = new Loader(ctx, c.layouts(), c.ingestThreads(), c.ingestBatchSize(), 0)
          .load(0, prebuild, null);
        ingest.putAll(r.toMap());
      }
      double seconds = (System.nanoTime() - start) / 1e9;
      ingest.put("seconds", seconds);
      state.putSection("ingest", ingest);
      state.put("load", "rows_loaded", prebuild);
      state.put("load", "prebuild_rows", prebuild);
      state.put("load", "corpus_rows", ctx.corpus().rows());
      for (TableLayout layout : c.layouts()) {
        out.printf(Locale.ROOT, "loaded %s: %d rows in %.1f s (%.0f rows/s)%n",
          ctx.tables().fullTable(layout), prebuild, seconds, prebuild / Math.max(seconds, 1e-9));
      }
      Map<String, Object> footprint = new LinkedHashMap<>();
      for (TableLayout layout : c.layouts()) {
        String t = ctx.tables().fullTable(layout);
        StorageFootprint.flush(conn, t);
        footprint.put(t, StorageFootprint.measure(conn, t));
      }
      state.putSection("footprint", footprint);
      if (c.snapshot()) {
        List<String> snapshots = new ArrayList<>();
        try (Admin admin = HarnessContext.admin(conn)) {
          for (TableLayout layout : c.layouts()) {
            String name = ctx.tables().snapshot(layout);
            TableName tn = StorageFootprint.physicalName(conn, ctx.tables().fullTable(layout));
            if (admin.listSnapshots().stream().anyMatch(s -> s.getName().equals(name))) {
              admin.deleteSnapshot(name);
            }
            admin.snapshot(name, tn);
            snapshots.add(name);
          }
        }
        state.put("load", "snapshots", snapshots);
        out.println("snapshots " + snapshots);
      }
    }
    TruthManager truth = new TruthManager(ctx);
    long ts = System.nanoTime();
    GroundTruth gt = truth.truth(prebuild);
    state.put("truth", "rows", prebuild);
    state.put("truth", "queries", truth.panel().size());
    state.put("truth", "seconds", (System.nanoTime() - ts) / 1e9);
    GroundTruth full = gt;
    if (prebuild < ctx.corpus().rows()) {
      // Streaming runs also need the truth of the full corpus, after the stream completes.
      full = truth.truth(ctx.corpus().rows());
    }
    // Published ground truth describes the whole base set, not the rows loaded before the build.
    PublishedTruthValidator.Result v = truth.validatePublished(full);
    if (v.queries() > 0) {
      state.put("truth", "published_queries", v.queries());
      state.put("truth", "published_agrees", v.agrees());
      if (!v.agrees()) {
        // The state is written for diagnosis; without completed_ms the later phases refuse it.
        state.put("truth", "published_mismatches", v.mismatches());
        Files.createDirectories(ctx.store().tableDir(ctx.tables().suffix()));
        state.write(ctx.store().loadState(ctx.tables().suffix()));
        throw new IllegalStateException(
          "Static ground truth disagrees with the published ground" + " truth of "
            + ctx.corpus().sourceName() + ":\n  " + String.join("\n  ", v.mismatches()));
      }
      out.printf(Locale.ROOT, "truth: static ground truth agrees with the published ground"
        + " truth (%d queries, %d ranks)%n", v.queries(), v.ranks());
    }
    state.put("load", "dirty", false);
    state.put("load", "completed_ms", System.currentTimeMillis());
    state.write(ctx.store().loadState(ctx.tables().suffix()));
    return state;
  }
}
