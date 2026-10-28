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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TableNames;

/**
 * The global IVF index. Its index table is keyed by centroid and data key; DOC_CORPUS indexes cover
 * CATEGORY, ACL_TAG, and CREATED_AT so that covered and uncovered plans can be compared. A
 * synchronous CREATE trains the first generation and populates the index before it returns; an
 * asynchronous CREATE is populated by IndexTool. The probe sweep pins MAX_PROBE_LIMIT, so that
 * adaptive expansion does not blur the probe count; the value all probes every centroid of the
 * effective count, which k-means can make larger than the requested lists.
 */
public final class IvfVariant implements IndexVariant {

  /** The covered columns of the DOC_CORPUS index. */
  public static final String COVERED = "CATEGORY, ACL_TAG, CREATED_AT";

  /** The client setting of the probe count of unhinted queries; 0 means the square root rule. */
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
    // A synchronous build sleeps between its passes; benchmark builds do not.
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
    return m;
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
   * Returns the probe count of an unhinted query as the engine chooses it: the configured count,
   * capped at the centroid count, or the rounded square root of the centroid count when the
   * configured count is absent or not positive.
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
      // A global connection on a multi-tenant index gets no centroid ranges and scans the whole
      // index. The unfiltered and distance-bounded shapes carry no tenant, so on a multi-tenant
      // table they run on a global connection.
      return PlanClass.IVF_FULL_SCAN;
    }
    // A key range runs on a tenant connection. The index key is the tenant, _CENTROID_ID, then
    // the data key, so a DOC_ID range cannot bind past _CENTROID_ID: it stays a server filter
    // and the plan probes the centroid ranges under the tenant prefix.
    return PlanClass.IVF_PROBE;
  }
}
