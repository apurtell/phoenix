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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.apache.phoenix.vectorharness.truth.ConditionPlanner;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.LiveGroundTruth;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IndexVariant;

/**
 * The shared state of the stages of one run: the context, the variant and its built indexes, the
 * client-side corpus record and its live ground truth, the panel and its conditions, the query
 * factory, the observers, the outputs, and the write gate that quiesced sampling closes.
 */
public final class RunContext implements Closeable {

  private final HarnessContext ctx;
  private final IndexVariant variant;
  private final Map<TableLayout, IndexInfo> indexes = new EnumMap<>(TableLayout.class);
  private final Set<TableLayout> layouts;
  private final EventLog events;
  private final Manifest manifest;
  private final Path runDir;
  private final LiveCorpus live;
  private final List<PanelQuery> panel;
  private final List<List<QueryCondition>> conditions;
  private final QueryFactory queries;
  private final ResultScoring scoring;
  private final VectorIndexMetricsObserver metrics;
  private final WriteGate gate = new WriteGate();
  private final CsvTable perQuery;
  private volatile GroundTruth staticTruth;
  private volatile LiveGroundTruth liveTruth;

  public RunContext(HarnessContext ctx, IndexVariant variant, Set<TableLayout> layouts,
    EventLog events, Manifest manifest, Path runDir, LiveCorpus live, List<PanelQuery> panel,
    List<List<QueryCondition>> conditions, QueryFactory queries, VectorIndexMetricsObserver metrics)
    throws IOException {
    this.ctx = ctx;
    this.variant = variant;
    this.layouts = layouts;
    this.events = events;
    this.manifest = manifest;
    this.runDir = runDir;
    this.live = live;
    this.panel = panel;
    this.conditions = conditions;
    this.queries = queries;
    this.scoring = new ResultScoring(ctx.metric(), ctx.config().tieEpsilon(), live);
    this.metrics = metrics;
    this.perQuery = runDir == null
      ? null
      : CsvTable.create(runDir.resolve("per_query.csv"), "stage", "layout", "family", "k", "sweep",
        "query", "condition", "latency_ms", "rows", "recall", "precision", "completeness", "ndcg",
        "distance_ratio", "rows_scanned", "rpcs", "excluded", "error");
  }

  public HarnessContext ctx() {
    return ctx;
  }

  public IndexVariant variant() {
    return variant;
  }

  public Map<TableLayout, IndexInfo> indexes() {
    return indexes;
  }

  public Set<TableLayout> layouts() {
    return layouts;
  }

  public EventLog events() {
    return events;
  }

  public Manifest manifest() {
    return manifest;
  }

  public Path runDir() {
    return runDir;
  }

  public LiveCorpus live() {
    return live;
  }

  public List<PanelQuery> panel() {
    return panel;
  }

  public List<List<QueryCondition>> conditions() {
    return conditions;
  }

  public QueryFactory queries() {
    return queries;
  }

  public ResultScoring scoring() {
    return scoring;
  }

  public VectorIndexMetricsObserver metrics() {
    return metrics;
  }

  public WriteGate gate() {
    return gate;
  }

  public GroundTruth staticTruth() {
    return staticTruth;
  }

  public void staticTruth(GroundTruth t) {
    this.staticTruth = t;
  }

  public LiveGroundTruth liveTruth() {
    return liveTruth;
  }

  public void liveTruth(LiveGroundTruth t) {
    this.liveTruth = t;
  }

  /** Returns the index names of the layouts, for metrics. */
  public List<String> indexNames() {
    List<String> out = new ArrayList<>();
    for (TableLayout l : layouts) {
      out.add(ctx.tables().fullIndex(l));
    }
    return out;
  }

  /**
   * Returns the exact neighbors of a panel query under a condition, from the live truth when it
   * tracks the query, else from the static truth while the live rows equal the base rows. Returns
   * null if neither applies.
   */
  public NeighborList truth(int panelIndex, QueryCondition c, int k) {
    LiveGroundTruth lt = liveTruth;
    if (lt != null && panelIndex < lt.trackedQueries()) {
      for (int ci = 0; ci < lt.conditionCount(panelIndex); ci++) {
        if (lt.condition(panelIndex, ci).key().equals(c.key())) {
          // Ensure the window holds k rows, then return all of it, so that online sampling can
          // refill the truth after exclusions.
          NeighborList head = lt.topK(panelIndex, ci, k);
          NeighborList window = lt.window(panelIndex, ci);
          return window.size() >= head.size() ? window : head;
        }
      }
    }
    GroundTruth st = staticTruth;
    if (st != null && !live.divergesFromBase() && st.rows() == live.loaded()) {
      NeighborList t = st.get(panelIndex, c.key());
      if (t == null && c.hasRadius()) {
        // The radius of a condition comes from the truth it was planned on, so the truth of
        // other rows, such as the whole corpus after a stream, has no list under its key. With
        // no other predicate, its truth is the part of the exact unfiltered list within it.
        NeighborList unfiltered = st.get(panelIndex, TruthManager.UNFILTERED_KEY);
        if (unfiltered != null) {
          t = ConditionPlanner.boundedPrefix(unfiltered, c);
        }
      }
      return t;
    }
    return null;
  }

  /** Returns the HNSW probe rows read since a snapshot, for the index of a layout, or NaN. */
  public double probeRowsSince(MetricSnapshot before, TableLayout layout) {
    if (metrics == null || before == null) {
      return Double.NaN;
    }
    MetricSnapshot now = metrics.sample(null);
    Double d = now.delta(before, "vectorHnswProbeRowsRead." + ctx.tables().fullIndex(layout));
    return d == null ? Double.NaN : d;
  }

  /** Records one scored query of a measurement. */
  public void perQuery(Measurement m, QuerySpec q, QueryExecutor.Result r,
    org.apache.phoenix.vectorharness.truth.QueryScore s) {
    if (perQuery == null) {
      return;
    }
    perQuery.row(m.stage(), m.layout(), m.family(), m.k(), m.sweep(), q.query().index(),
      q.condition().key(), r.latencyNanos() / 1e6, r.ordinals().length,
      s == null ? null : s.recall(), s == null ? null : s.precision(),
      s == null ? null : s.completeness(), s == null ? null : s.ndcg(),
      s == null ? null : s.distanceRatio(), r.rowsScanned(), r.rpcs(),
      s == null ? null : s.excluded(), r.error());
  }

  @Override
  public void close() {
    if (perQuery != null) {
      perQuery.close();
    }
  }
}
