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
package org.apache.phoenix.vectorharness.variant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.index.vector.CentroidManager;
import org.apache.phoenix.index.vector.GenerationSummary;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TableNames;

/**
 * Global IVF vector index variant. Keyed by centroid ID and base table data key. Supports
 * synchronous or asynchronous builds, covered columns, and probe sweeps.
 */
public final class IvfVariant implements IndexVariant {

  /** Covered columns for DOC_CORPUS index layout. */
  public static final String COVERED = "CATEGORY, ACL_TAG, CREATED_AT";

  /** Client property configuring probe count for unhinted queries. */
  static final String PROBE_COUNT_KEY = "phoenix.vector.probe.count";

  @Override
  public String id() {
    return "ivf";
  }

  @Override
  public List<String> validate(HarnessContext ctx, int dim, long prebuildRows) {
    HarnessConfig c = ctx.config();
    List<String> problems = new ArrayList<>(CommonRules.validate(ctx));
    if (c.ivfLists() <= 0) {
      problems.add(Setting.IVF_LISTS.key() + " must be positive");
    }
    if (c.ivfSampleSize() < c.ivfLists()) {
      problems.add(Setting.IVF_SAMPLE_SIZE.key() + " must be at least " + Setting.IVF_LISTS.key());
    }
    if (prebuildRows < c.ivfLists()) {
      problems.add("IVF training needs at least " + c.ivfLists() + " rows before the build, but"
        + " ingest.prebuild.fraction loads " + prebuildRows);
    }
    if (c.ivfMaxProbeLimit() <= 0) {
      problems.add(Setting.QUERY_IVF_MAX_PROBE_LIMIT.key() + " must be positive");
    }
    return problems;
  }

  @Override
  public List<String> warnings(HarnessContext ctx, int dim, long prebuildRows) {
    List<String> out = new ArrayList<>();
    if (prebuildRows < ctx.config().ivfSampleSize()) {
      out.add("IVF trains on " + prebuildRows + " prebuild rows, fewer than sample_size "
        + ctx.config().ivfSampleSize());
    }
    out.addAll(IvfRegionLayout.plan(ctx.config()).notes());
    return out;
  }

  @Override
  public String createIndexDdl(HarnessContext ctx, Schema schema, TableLayout layout) {
    HarnessConfig c = ctx.config();
    TableNames n = ctx.tables();
    Map<String, String> options = new LinkedHashMap<>();
    CommonRules.option(options, "algorithm", "IVF");
    CommonRules.option(options, "metric", ctx.metric().indexOption());
    CommonRules.option(options, "lists", c.ivfLists());
    CommonRules.option(options, "sample_size", c.ivfSampleSize());
    Integer salt = c.ivfSaltBuckets();
    if (salt != null) {
      CommonRules.option(options, "SALT_BUCKETS", salt);
    }
    StringBuilder sb = new StringBuilder("CREATE VECTOR INDEX IF NOT EXISTS ");
    sb.append(n.index(layout)).append(" ON ").append(n.fullTable(layout)).append(" (")
      .append(schema.vectorExpression(layout)).append(')');
    if (layout == TableLayout.CORPUS) {
      sb.append(" INCLUDE (").append(COVERED).append(')');
    }
    sb.append(CommonRules.withClause(options));
    if (c.ivfAsync()) {
      sb.append(" ASYNC");
    }
    IvfRegionLayout.Plan plan = IvfRegionLayout.plan(c);
    if (plan.mode() == IvfRegionLayout.Mode.SPLIT_ON && !plan.splitIds().isEmpty()) {
      sb.append(" SPLIT ON (");
      for (int i = 0; i < plan.splitIds().size(); i++) {
        sb.append(i == 0 ? "" : ", ").append(plan.splitIds().get(i));
      }
      sb.append(')');
    }
    return sb.toString();
  }

  @Override
  public Map<String, Object> build(HarnessContext ctx, Connection conn, Schema schema,
    TableLayout layout, EventLog events) throws Exception {
    HarnessConfig c = ctx.config();
    TableNames n = ctx.tables();
    String ddl = createIndexDdl(ctx, schema, layout);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ddl", ddl);
    long start = System.nanoTime();
    // Eliminate inter-pass sleep during benchmark index build
    Properties props = new Properties();
    if (c.get(CommonRules.POPULATION_WAIT) == null) {
      props.setProperty(CommonRules.POPULATION_WAIT, "0");
    }
    try (Connection ddlConn = ctx.connection(props)) {
      ddlConn.createStatement().execute(ddl);
    }
    m.put("ddl_seconds", (System.nanoTime() - start) / 1e9);
    if (c.ivfAsync()) {
      long toolStart = System.nanoTime();
      int status = CommonRules.runIndexTool(conn, n.schema(), n.table(layout), n.index(layout));
      m.put("index_tool_status", status);
      m.put("index_tool_seconds", (System.nanoTime() - toolStart) / 1e9);
      if (status != 0) {
        throw new SQLException(
          "IndexTool failed with status " + status + " for " + n.fullIndex(layout));
      }
    }
    String full = n.fullIndex(layout);
    boolean ready = CommonRules.await(() -> {
      try {
        IndexInfo info = IndexInfo.read(conn, full);
        return info.active() && info.activeGeneration() != null;
      } catch (SQLException e) {
        return false;
      }
    }, c.buildTimeout().toMillis(), 1000);
    IndexInfo info = IndexInfo.read(conn, full);
    m.put("seconds", (System.nanoTime() - start) / 1e9);
    m.put("index", info.toMap());
    m.put("requested_lists", c.ivfLists());
    m.put("effective_lists", info.ivfLists());
    if (!ready) {
      throw new SQLException("IVF index " + full + " is not ACTIVE and trained after "
        + c.buildTimeout() + "; state " + info.state() + ", generation " + info.activeGeneration()
        + " (training defers when fewer than lists rows exist)");
    }
    if (events != null) {
      events.event("index-active",
        full + " lists=" + info.ivfLists() + " generation=" + info.activeGeneration());
    }
    m.put("regions", layoutAfterBuild(conn, full, IvfRegionLayout.plan(c), c.ivfLists(),
      info.activeGeneration(), events));
    return m;
  }

  /**
   * Completes the region layout of the first generation and measures it. The SPLIT ON points assume
   * the requested list count from the first ID. Training can return more lists, so the populated
   * range can need more points. A multi-tenant index splits at region midkeys. After any split, the
   * regions must spread across the region servers before the measured stages. The record holds the
   * time of this work, because the build time of the index does not include it.
   */
  static Map<String, Object> layoutAfterBuild(Connection conn, String index,
    IvfRegionLayout.Plan plan, int requestedLists, long generation, EventLog events)
    throws Exception {
    long start = System.nanoTime();
    CentroidManager.Model model = CentroidManager.loadModel(conn, index, generation);
    int firstId = model != null ? model.getFirstId() : IvfRegionLayout.FIRST_GENERATION_FIRST_ID;
    int lists = model != null ? model.getCentroids().size() : requestedLists;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("layout", plan.toMap());
    m.put("first_id", firstId);
    m.put("lists", lists);
    try (IvfRegionLayout layout =
      IvfRegionLayout.open(conn, index, IvfRegionLayout.PHASE_FIRST_BUILD, events)) {
      if (plan.mode() == IvfRegionLayout.Mode.SPLIT_ON) {
        List<Integer> missing =
          IvfRegionLayout.missingSplitIds(layout.boundaryIds(), firstId, lists, plan.regions());
        if (!missing.isEmpty()) {
          m.put("top_up", layout.splitAt(missing));
        }
      } else if (plan.mode() == IvfRegionLayout.Mode.MIDKEY) {
        m.put("midkey", layout.splitLargest(plan.regions()));
      }
      if (layout.regions().size() > 1) {
        m.put("balance",
          plan.centroidKeys()
            ? layout.awaitBalanced(firstId, lists)
            : layout.awaitBalanced(null, null));
      }
      m.putAll(layout.stats(plan.centroidKeys(), firstId, lists));
      m.put("events", layout.events());
    }
    m.put("seconds", (System.nanoTime() - start) / 1e9);
    return m;
  }

  /**
   * Splits the empty ID range of the next generation before an explicit rebuild. The range starts
   * after the IDs of the active generation, as in the product. The first split separates the range
   * from the active generation. The later splits divide empty regions, so they move no data. Thus
   * the rebuild writes into regions that already spread across the servers. The range gets at least
   * as many regions as the active generation fills. The splits assume the list count of the active
   * generation if training returned more lists than requested.
   * @return the record of the splits, or null if the layout does not split at centroid IDs
   */
  public static Map<String, Object> layoutBeforeRebuild(Connection conn, String index,
    IvfRegionLayout.Plan plan, long active, EventLog events) throws Exception {
    if (plan.mode() != IvfRegionLayout.Mode.SPLIT_ON) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("generation", active);
    CentroidManager.Model model = CentroidManager.loadModel(conn, index, active);
    try (IvfRegionLayout layout =
      IvfRegionLayout.open(conn, index, IvfRegionLayout.PHASE_BEFORE_REBUILD, events)) {
      if (model == null || model.getCentroids().isEmpty()) {
        layout.event("skipped", "generation " + active + " has no centroids");
        m.put("skipped", "no centroids");
        return m;
      }
      int outgoingFirst = model.getFirstId();
      int outgoingLists = model.getCentroids().size();
      int outgoingRegions = layout.populatedRegions(outgoingFirst, outgoingLists);
      int target = IvfRegionLayout.rebuildTarget(plan.regions(), outgoingRegions);
      int requested = rebuildLists(conn, index, active, outgoingLists);
      int lists = IvfRegionLayout.expectedLists(requested, outgoingLists);
      Integer firstId = IvfRegionLayout.nextFirstId(outgoingFirst, outgoingLists);
      m.put("outgoing_first_id", outgoingFirst);
      m.put("outgoing_lists", outgoingLists);
      m.put("outgoing_regions", outgoingRegions);
      m.put("first_id", firstId);
      m.put("requested_lists", requested);
      m.put("expected_lists", lists);
      m.put("target_regions", target);
      if (firstId == null) {
        layout.event("skipped", "the next ID range is out of the INTEGER range");
        m.put("skipped", "ID overflow");
        return m;
      }
      m.put("split", layout.splitAt(IvfRegionLayout.futureRangeSplitIds(firstId, lists, target)));
      m.put("balance", layout.awaitBalanced(firstId, lists));
      m.put("events", layout.events());
    }
    return m;
  }

  /**
   * Completes the region layout of a promoted generation and measures it. For an index keyed by
   * centroid ID, the populated range of the generation gets the target count of regions. After an
   * explicit rebuild, the empty range already has split points. Training can return more lists, so
   * the harness adds points as after the first build. After an automatic rebuild, the harness
   * splits the regions of the range at their midkeys under load, as natural growth does. A
   * multi-tenant index always splits at midkeys.
   * @param byMidkey   true to split at midkeys, false to split at centroid IDs
   * @param stopMillis the wall time at which the layout stops, so that the next window continues it
   */
  public static Map<String, Object> layoutAfterPromotion(Connection conn, String index,
    IvfRegionLayout.Plan plan, long generation, int targetRegions, boolean byMidkey, String phase,
    long stopMillis, EventLog events) throws Exception {
    CentroidManager.Model model = CentroidManager.loadModel(conn, index, generation);
    Integer firstId = model != null ? Integer.valueOf(model.getFirstId()) : null;
    Integer lists = model != null ? Integer.valueOf(model.getCentroids().size()) : null;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("generation", generation);
    m.put("first_id", firstId);
    m.put("lists", lists);
    m.put("target_regions", targetRegions);
    try (IvfRegionLayout layout =
      IvfRegionLayout.open(conn, index, phase, events).stopAt(stopMillis)) {
      if (plan.mode() == IvfRegionLayout.Mode.SPLIT_ON) {
        if (firstId == null) {
          layout.event("skipped", "generation " + generation + " has no centroids");
        } else if (byMidkey) {
          m.put("midkey", layout.splitLargest(targetRegions, firstId, lists));
        } else {
          List<Integer> ids = IvfRegionLayout.topUpSplitIds(layout.boundaryIds(), firstId, lists,
            targetRegions, firstId > IvfRegionLayout.FIRST_GENERATION_FIRST_ID);
          if (!ids.isEmpty()) {
            m.put("top_up", layout.splitAt(ids));
          }
        }
      } else if (plan.mode() == IvfRegionLayout.Mode.MIDKEY) {
        // The tenant ID leads the row key, so both generations share the regions of each tenant.
        m.put("midkey", layout.splitLargest(targetRegions));
      }
      if (layout.regions().size() > 1) {
        m.put("balance",
          plan.centroidKeys() && firstId != null
            ? layout.awaitBalanced(firstId, lists)
            : layout.awaitBalanced(null, null));
      }
      m.putAll(layout.stats(plan.centroidKeys(), firstId, lists));
      m.put("events", layout.events());
    }
    return m;
  }

  /**
   * Returns the list count that a rebuild trains. The product takes the requested count of the
   * active generation, or else the count of its lists.
   */
  static int rebuildLists(Connection conn, String index, long active, int activeLists)
    throws SQLException {
    GenerationSummary s = CentroidManager.loadGenerationSummary(conn, index, active);
    Integer requested = s != null ? s.getRequestedLists() : null;
    return requested != null && requested > 0 ? requested : activeLists;
  }

  /**
   * Returns the wall time of the promotion of a generation, or null if the catalog does not record
   * it.
   */
  public static Long promotionMillis(Connection conn, String index, long generation)
    throws SQLException {
    GenerationSummary s = CentroidManager.loadGenerationSummary(conn, index, generation);
    return s != null ? s.getLastRebuildTime() : null;
  }

  @Override
  public List<SweepPoint> sweep(HarnessContext ctx, IndexInfo index) {
    HarnessConfig c = ctx.config();
    int lists = index != null && index.ivfLists() != null ? index.ivfLists() : c.ivfLists();
    int limit = c.ivfMaxProbeLimit();
    List<SweepPoint> out = new ArrayList<>();
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (int p : c.ivfProbeValues()) {
      int probes = p == HarnessConfig.PROBE_ALL ? lists : Math.min(p, lists);
      if (!seen.add(probes)) {
        continue;
      }
      String label =
        p == HarnessConfig.PROBE_ALL ? "probes=all(" + lists + ")" : "probes=" + probes;
      out.add(new SweepPoint("probes", probes, label,
        "VECTOR_PROBE_COUNT(" + probes + ") MAX_PROBE_LIMIT(" + limit + ")"));
    }
    return CommonRules.withDefault(out, defaultPoint(ctx, index));
  }

  @Override
  public SweepPoint defaultPoint(HarnessContext ctx, IndexInfo index) {
    HarnessConfig c = ctx.config();
    int lists = index != null && index.ivfLists() != null ? index.ivfLists() : c.ivfLists();
    int probes = defaultProbes(c.get(PROBE_COUNT_KEY), lists);
    return new SweepPoint("probes", probes, "probes=default(" + probes + ")",
      "MAX_PROBE_LIMIT(" + c.ivfMaxProbeLimit() + ")");
  }

  /**
   * Returns default probe count for unhinted queries based on configuration or square-root rule of
   * total centroid count.
   */
  static int defaultProbes(String configured, int lists) {
    int n =
      configured == null || configured.trim().isEmpty() ? 0 : Integer.parseInt(configured.trim());
    return n > 0 ? Math.min(n, lists) : Math.max(1, (int) Math.round(Math.sqrt(lists)));
  }

  @Override
  public PlanClass expectedPlan(QueryShape shape, boolean multiTenant) {
    PlanClass keyBound = CommonRules.keyBoundPlan(shape, multiTenant);
    if (keyBound != null) {
      return keyBound;
    }
    if (
      multiTenant && (shape == QueryShape.GLOBAL || shape == QueryShape.UNFILTERED
        || shape == QueryShape.DISTANCE_BOUND)
    ) {
      // Global connections on multi-tenant indexes cannot bind centroid ranges; scans entire index.
      return PlanClass.IVF_FULL_SCAN;
    }
    // Tenant connections probe centroid ranges under tenant prefix; DOC_ID predicates remain server
    // filters.
    return PlanClass.IVF_PROBE;
  }

  /**
   * Only the index of the corpus layout includes columns. A filter on an included column is
   * covered.
   */
  @Override
  public FilterCoverage coverage(TableLayout layout, QueryCondition.Attribute attribute) {
    if (attribute == QueryCondition.Attribute.NONE) {
      return FilterCoverage.NONE;
    }
    boolean included = layout == TableLayout.CORPUS
      && Arrays.asList(COVERED.split(",\\s*")).contains(attribute.name());
    return included ? FilterCoverage.COVERED : FilterCoverage.UNCOVERED;
  }
}
