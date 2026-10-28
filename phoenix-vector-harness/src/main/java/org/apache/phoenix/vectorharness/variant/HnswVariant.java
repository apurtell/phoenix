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

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.hnsw.HnswSegment;
import org.apache.phoenix.hbase.index.hnsw.HnswSegmentStore;
import org.apache.phoenix.index.vector.CentroidManager;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.apache.phoenix.vectorharness.workload.TableNames;

/**
 * The region-local HNSW index. Its graph segments live in the hidden V# family of the data table,
 * with payloads in MOB files, so it has no index table and no INCLUDE columns. The DDL never builds
 * the index: IndexTool asks every region to build a base segment and makes the index ACTIVE once
 * every region has one. The build then waits until V# holds exactly one segment per region, so that
 * superseded segments are retired before measurement starts. The sweep varies HNSW_EF_SEARCH; each
 * region searches max(ef_search, LIMIT + OFFSET) candidates.
 */
public final class HnswVariant implements IndexVariant {

  /** The engine default of ef_search, used when the profile leaves it unset. */
  public static final int DEFAULT_EF_SEARCH = 64;

  @Override
  public String id() {
    return "hnsw";
  }

  @Override
  public List<String> validate(HarnessContext ctx, int dim, long prebuildRows) {
    HarnessConfig c = ctx.config();
    List<String> problems = new ArrayList<>(CommonRules.validate(ctx));
    if (ctx.metric() == Metric.INNER_PRODUCT) {
      problems.add(Setting.INDEX_METRIC.key() + " INNER_PRODUCT is not supported: HNSW indexes"
        + " support the L2 and COSINE metrics");
    }
    if (c.hnswM() < 4 || c.hnswM() > 64) {
      problems.add(Setting.HNSW_M.key() + " must be between 4 and 64");
    }
    if (c.hnswEfConstruction() < 16 || c.hnswEfConstruction() > 512) {
      problems.add(Setting.HNSW_EF_CONSTRUCTION.key() + " must be between 16 and 512");
    }
    if (c.hnswAlpha() < 1.0 || c.hnswAlpha() > 2.0) {
      problems.add(Setting.HNSW_ALPHA.key() + " must be between 1.0 and 2.0");
    }
    String q = c.hnswQuantization();
    if (!q.equals("NONE") && !q.equals("SQ8") && !q.equals("PQ")) {
      problems.add(Setting.HNSW_QUANTIZATION.key() + " must be NONE, SQ8, or PQ");
    }
    int pq = c.hnswPqSegments();
    if (q.equals("PQ")) {
      if (pq < 1 || pq > 256) {
        problems.add(Setting.HNSW_PQ_SEGMENTS.key() + " between 1 and 256 is required for PQ");
      } else if (dim % pq != 0) {
        problems
          .add(Setting.HNSW_PQ_SEGMENTS.key() + " " + pq + " must divide the dimension " + dim);
      }
    } else if (pq != 0) {
      problems.add(Setting.HNSW_PQ_SEGMENTS.key() + " applies only to quantization PQ");
    }
    for (int ef : c.hnswEfValues()) {
      if (ef > 10_000) {
        problems.add("HNSW_EF_SEARCH above 10000 makes every region scan unrestricted");
        break;
      }
    }
    return problems;
  }

  @Override
  public List<String> warnings(HarnessContext ctx, int dim, long prebuildRows) {
    List<String> out = new ArrayList<>();
    HarnessConfig c = ctx.config();
    if (c.hnswQuantization().equals("PQ") && prebuildRows / Math.max(1, c.regions()) < 256) {
      out.add("PQ needs 256 vectors per segment; smaller segments store full vectors");
    }
    return out;
  }

  @Override
  public String createIndexDdl(HarnessContext ctx, Schema schema, TableLayout layout) {
    HarnessConfig c = ctx.config();
    TableNames n = ctx.tables();
    Map<String, String> options = new LinkedHashMap<>();
    CommonRules.option(options, "algorithm", "HNSW");
    CommonRules.option(options, "metric", ctx.metric().indexOption());
    CommonRules.option(options, "M", c.hnswM());
    CommonRules.option(options, "ef_construction", c.hnswEfConstruction());
    options.put("alpha", String.format(Locale.ROOT, "%s", c.hnswAlpha()));
    CommonRules.option(options, "quantization", c.hnswQuantization());
    if (c.hnswQuantization().equals("PQ")) {
      CommonRules.option(options, "pq_segments", c.hnswPqSegments());
    }
    return "CREATE VECTOR INDEX IF NOT EXISTS " + n.index(layout) + " ON " + n.fullTable(layout)
      + " (" + schema.vectorExpression(layout) + ")" + CommonRules.withClause(options) + " ASYNC";
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
    conn.createStatement().execute(ddl);
    m.put("ddl_seconds", (System.nanoTime() - start) / 1e9);
    long toolStart = System.nanoTime();
    int status = CommonRules.runIndexTool(conn, n.schema(), n.table(layout), n.index(layout));
    m.put("index_tool_status", status);
    m.put("index_tool_seconds", (System.nanoTime() - toolStart) / 1e9);
    if (status != 0) {
      throw new SQLException(
        "IndexTool failed with status " + status + " for " + n.fullIndex(layout));
    }
    String fullIndex = n.fullIndex(layout);
    String fullTable = n.fullTable(layout);
    boolean ready = CommonRules.await(() -> {
      try {
        if (!IndexInfo.read(conn, fullIndex).active()) {
          return false;
        }
        SegmentCount sc = segments(conn, fullTable, fullIndex);
        return sc.bases == sc.regions && sc.total == sc.regions;
      } catch (SQLException | IOException e) {
        return false;
      }
    }, c.buildTimeout().toMillis(), 500);
    SegmentCount sc = segments(conn, fullTable, fullIndex);
    m.put("seconds", (System.nanoTime() - start) / 1e9);
    m.put("index", IndexInfo.read(conn, fullIndex).toMap());
    m.put("segments", sc.toMap());
    if (!ready) {
      throw new SQLException("HNSW index " + fullIndex + " has no single base segment per region"
        + " after " + c.buildTimeout() + ": " + sc.toMap());
    }
    if (events != null) {
      events.event("index-active",
        fullIndex + " " + sc.regions + " regions, " + sc.total + " segments");
    }
    return m;
  }

  /** The segments of an index in V#, against the regions of its data table. */
  public static final class SegmentCount {
    final int regions;
    final int bases;
    final int deltas;
    final int total;
    final long rows;

    SegmentCount(int regions, int bases, int deltas, long rows) {
      this.regions = regions;
      this.bases = bases;
      this.deltas = deltas;
      this.total = bases + deltas;
      this.rows = rows;
    }

    public int regions() {
      return regions;
    }

    public int bases() {
      return bases;
    }

    public int deltas() {
      return deltas;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("regions", regions);
      m.put("base_segments", bases);
      m.put("delta_segments", deltas);
      m.put("segments", total);
      m.put("base_rows", rows);
      return m;
    }
  }

  /** Lists the segments of an index from V# with a client-side reader. */
  public static SegmentCount segments(Connection conn, String fullTable, String fullIndex)
    throws SQLException, IOException {
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    byte[] physical = pconn.getTableNoCache(fullTable).getPhysicalName().getBytes();
    byte[] indexKey = Bytes.toBytes(CentroidManager.getCentroidIndexName(fullIndex));
    List<HnswSegment.Descriptor> list;
    try (Table t = pconn.getQueryServices().getTable(physical)) {
      list = new HnswSegmentStore.TableStore(t).list(indexKey);
    }
    int regions;
    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      regions = admin.getRegions(TableName.valueOf(physical)).size();
    }
    int bases = 0;
    int deltas = 0;
    long rows = 0;
    for (HnswSegment.Descriptor d : list) {
      if (d.isDelta()) {
        deltas++;
      } else {
        bases++;
        rows += d.count;
      }
    }
    return new SegmentCount(regions, bases, deltas, rows);
  }

  @Override
  public List<SweepPoint> sweep(HarnessContext ctx, IndexInfo index) {
    List<SweepPoint> out = new ArrayList<>();
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (int ef : ctx.config().hnswEfValues()) {
      if (seen.add(ef)) {
        out.add(new SweepPoint("ef_search", ef, "ef=" + ef, "HNSW_EF_SEARCH(" + ef + ")"));
      }
    }
    return CommonRules.withDefault(out, defaultPoint(ctx, index));
  }

  @Override
  public SweepPoint defaultPoint(HarnessContext ctx, IndexInfo index) {
    String v = ctx.config().get("phoenix.vector.hnsw.ef_search.default");
    int ef = v == null ? DEFAULT_EF_SEARCH : Integer.parseInt(v.trim());
    return new SweepPoint("ef_search", ef, "ef=default(" + ef + ")", "");
  }

  @Override
  public PlanClass expectedPlan(QueryShape shape, boolean multiTenant) {
    PlanClass keyBound = CommonRules.keyBoundPlan(shape, multiTenant);
    return keyBound != null ? keyBound : PlanClass.HNSW_SEARCH;
  }
}
