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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.truth.BruteForceKnn;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.RowSource;
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IndexVariant;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.apache.phoenix.vectorharness.workload.QueryFactory;
import org.apache.phoenix.vectorharness.workload.QuerySpec;
import org.apache.phoenix.vectorharness.workload.RowWriter;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TableNames;

/**
 * One DOC_CORPUS table with a vector index, built from harness components without the command line:
 * the profile opens a synthetic corpus, {@link Schema} creates the pre-split table,
 * {@link RowWriter} loads every base row through JDBC, and the variant issues its DDL and waits
 * until the index is ACTIVE and built. Queries are rendered by {@link QueryFactory}, and ground
 * truth comes from {@link BruteForceKnn} over the client-side corpus, so test failures point at the
 * index rather than at the phases of the harness.
 */
final class VectorIndexFixture implements AutoCloseable {

  /** Rows of every fixture: two regions of about 600 rows. */
  static final int ROWS = 1200;
  /** Dimension of every fixture. */
  static final int DIM = 16;
  /** The k of the queries. */
  static final int K = 10;

  final String name;
  final HarnessContext ctx;
  final IndexVariant variant;
  final TableNames names;
  final Schema schema;
  final Corpus corpus;
  final Metric metric;
  final QueryFactory queries;
  final EventLog events;
  final Map<String, Object> build;

  private VectorIndexFixture(String name, HarnessContext ctx, EventLog events,
    Map<String, Object> build) {
    this.name = name;
    this.ctx = ctx;
    this.variant = Variants.of(ctx.config().variant());
    this.names = ctx.tables();
    this.corpus = ctx.corpus();
    this.metric = ctx.metric();
    this.schema = new Schema(ctx.config(), names, corpus.dim());
    this.queries =
      new QueryFactory(names, schema, metric, corpus.attributeModel(), ctx.config().multiTenant());
    this.events = events;
    this.build = build;
  }

  /**
   * Returns a profile of a small synthetic corpus: 1200 rows of dimension 16 in eight clusters,
   * twenty CATEGORY and SOURCE terms, and two tenants, in a table of two regions.
   */
  static Properties profile(String url, Path outputDir, String variant, String metric,
    String suffix) {
    Properties p = new Properties();
    p.setProperty("jdbc.url", url);
    p.setProperty("run.output.dir", outputDir.toString());
    p.setProperty("run.variant", variant);
    p.setProperty("run.id", "fn-" + suffix.toLowerCase(java.util.Locale.ROOT));
    p.setProperty("table.suffix", suffix);
    p.setProperty("table.layouts", "corpus");
    p.setProperty("table.regions", "2");
    p.setProperty("corpus.source", "synthetic");
    p.setProperty("corpus.seed", "11");
    p.setProperty("corpus.rows", Integer.toString(ROWS));
    p.setProperty("corpus.dim", Integer.toString(DIM));
    p.setProperty("corpus.clusters", "8");
    p.setProperty("corpus.intrinsic_dim", "6");
    p.setProperty("corpus.filter.categories", "20");
    p.setProperty("corpus.filter.sources", "20");
    p.setProperty("corpus.tenants", "2");
    p.setProperty("corpus.body.bytes", "16");
    p.setProperty("index.metric", metric);
    p.setProperty("index.build.timeout", "5m");
    p.setProperty("ivf.lists", "8");
    p.setProperty("ivf.sample_size", "600");
    p.setProperty("hnsw.m", "16");
    p.setProperty("hnsw.ef_construction", "64");
    p.setProperty("query.k.values", Integer.toString(K));
    p.setProperty("query.ivf.probe.values", "1,2,all");
    p.setProperty("query.ivf.max_probe_limit", "64");
    p.setProperty("query.hnsw.ef.values", "16,64,256");
    p.setProperty("query.filter.selectivity", "0.25");
    p.setProperty("query.shapes",
      "unfiltered,covered_filter,uncovered_filter,tenant_scoped,pk_range,distance_bound");
    p.setProperty("query.timeout", "60s");
    p.setProperty("recall.panel.size", "24");
    p.setProperty("phoenix.index.population.wait.time", "0");
    return p;
  }

  /** Opens the context of a profile without touching the cluster. */
  static HarnessContext open(Properties profile) throws Exception {
    return HarnessContext.open(HarnessConfig.of("fn", profile));
  }

  /** Creates the table of a profile, loads every base row, and builds the index. */
  static VectorIndexFixture create(String name, Properties profile) throws Exception {
    HarnessContext ctx = open(profile);
    try {
      EventLog events = EventLog.inMemory(System.currentTimeMillis());
      Schema schema = new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim());
      Map<String, Object> build;
      try (Connection conn = ctx.connection()) {
        schema.drop(conn, TableLayout.CORPUS);
        schema.create(conn, TableLayout.CORPUS,
          schema.splitPoints(ctx.corpus(), ctx.config().regions()));
        load(ctx, conn, ctx.corpus().rows());
        build =
          Variants.of(ctx.config().variant()).build(ctx, conn, schema, TableLayout.CORPUS, events);
      }
      return new VectorIndexFixture(name, ctx, events, build);
    } catch (Exception | Error e) {
      ctx.close();
      throw e;
    }
  }

  /** Writes the base rows [0, rows) through the live write path, in commits of 200. */
  static void load(HarnessContext ctx, Connection conn, long rows) throws SQLException {
    boolean auto = conn.getAutoCommit();
    conn.setAutoCommit(false);
    try (RowWriter w = new RowWriter(conn, ctx.tables(), EnumSet.of(TableLayout.CORPUS),
      ctx.corpus().attributeModel())) {
      for (long i = 0; i < rows; i++) {
        w.upsert(ctx.corpus().attributes(i), ctx.corpus().vector(i));
        if (i % 200 == 199) {
          w.commit();
        }
      }
      w.commit();
    } finally {
      conn.setAutoCommit(auto);
    }
  }

  String fullTable() {
    return names.fullTable(TableLayout.CORPUS);
  }

  String fullIndex() {
    return names.fullIndex(TableLayout.CORPUS);
  }

  String index() {
    return names.index(TableLayout.CORPUS);
  }

  long rows() {
    return corpus.rows();
  }

  IndexInfo info() throws SQLException {
    try (Connection conn = ctx.connection()) {
      return IndexInfo.read(conn, fullIndex());
    }
  }

  List<PanelQuery> panel(int n) {
    return corpus.panel(n);
  }

  /** Returns the sweep point whose label starts with a prefix, such as probes=all or ef=256. */
  SweepPoint point(String labelPrefix) throws SQLException {
    for (SweepPoint p : variant.sweep(ctx, info())) {
      if (p.label().startsWith(labelPrefix)) {
        return p;
      }
    }
    throw new AssertionError("No sweep point " + labelPrefix + " in " + variant.sweep(ctx, info()));
  }

  /** Returns the engine default breadth of the variant. */
  SweepPoint defaultPoint() throws SQLException {
    return variant.defaultPoint(ctx, info());
  }

  QuerySpec spec(PanelQuery q, QueryCondition c, SweepPoint p) {
    return queries.build(TableLayout.CORPUS, q, c, K, p);
  }

  /** Returns the exact neighbors of each query under one condition. */
  List<NeighborList> truth(List<PanelQuery> qs, QueryCondition c, int capacity) {
    List<List<QueryCondition>> conds = new ArrayList<>();
    for (int i = 0; i < qs.size(); i++) {
      conds.add(Collections.singletonList(c));
    }
    List<NeighborList> out = new ArrayList<>();
    for (Map<String, NeighborList> m : truth(qs, conds, capacity)) {
      out.add(m.get(c.key()));
    }
    return out;
  }

  /** Returns the exact neighbors of each query under each of its conditions, by key. */
  List<Map<String, NeighborList>> truth(List<PanelQuery> qs, List<List<QueryCondition>> conds,
    int capacity) {
    List<float[]> vectors = new ArrayList<>();
    for (PanelQuery q : qs) {
      vectors.add(q.vector());
    }
    return new BruteForceKnn(metric, 2).compute(vectors, conds,
      RowSource.prefix(corpus, corpus.rows()), capacity, null);
  }

  /** Returns the client-side distances of returned rows; NaN for a row outside the corpus. */
  double[] trueDistances(float[] q, long[] ordinals) {
    double[] d = new double[ordinals.length];
    for (int i = 0; i < ordinals.length; i++) {
      d[i] = ordinals[i] >= 0 && ordinals[i] < corpus.rows()
        ? metric.distance(q, corpus.vector(ordinals[i]))
        : Double.NaN;
    }
    return d;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public void close() {
    ctx.close();
  }
}
