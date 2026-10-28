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
package org.apache.phoenix.vectorharness.observe;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.index.fsck.Finding;
import org.apache.phoenix.mapreduce.index.fsck.IndexFsckContext;
import org.apache.phoenix.mapreduce.index.fsck.IndexFsckProviders;
import org.apache.phoenix.mapreduce.index.fsck.Report;
import org.apache.phoenix.mapreduce.index.fsck.RowKeyFormatter.KeyFormat;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.vectorharness.HarnessContext;

/**
 * Runs indexfsck commands in process through the provider API of the tool, so that the harness
 * consumes the structured report instead of parsing output. The inspect commands are read only:
 * {@code list}, {@code coverage}, and {@code delta} for HNSW, and {@code generations} and
 * {@code scorecard} for IVF. Each inspect of an HNSW index reads the V# family of every region, so
 * the harness runs it at stage boundaries by default.
 */
public final class IndexFsck {

  private IndexFsck() {
  }

  /**
   * Runs one inspect subcommand and returns the report as a map with the keys command, findings,
   * and inspection.
   */
  public static Map<String, Object> inspect(Connection conn, String fullDataTable, String fullIndex,
    String subcommand, List<String> args) throws Exception {
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable data = pconn.getTableNoCache(fullDataTable);
    PTable index = pconn.getTableNoCache(fullIndex);
    Configuration conf = new Configuration(HarnessContext.configuration(conn));
    IndexFsckContext ctx =
      new IndexFsckContext(conn, conf, data, index, null, null, null, KeyFormat.DECODED, false);
    Report report = IndexFsckProviders.forIndex(index).inspect(ctx, subcommand,
      args == null ? Collections.emptyList() : args);
    return toMap(report);
  }

  /** Runs the fsck command, which checks the index without MapReduce. */
  public static Map<String, Object> fsck(Connection conn, String fullDataTable, String fullIndex)
    throws Exception {
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable data = pconn.getTableNoCache(fullDataTable);
    PTable index = pconn.getTableNoCache(fullIndex);
    Configuration conf = new Configuration(HarnessContext.configuration(conn));
    IndexFsckContext ctx =
      new IndexFsckContext(conn, conf, data, index, null, null, null, KeyFormat.DECODED, false);
    return toMap(IndexFsckProviders.forIndex(index).fsck(ctx));
  }

  static Map<String, Object> toMap(Report report) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("command", report.getCommand());
    m.put("index", report.getIndexTable());
    m.put("errors", report.hasErrors());
    List<String> findings = new ArrayList<>();
    for (Finding f : report.getFindings()) {
      findings.add(f.toString());
    }
    m.put("findings", findings);
    m.put("inspection", report.getInspection());
    return m;
  }

  /**
   * Summarizes an HNSW {@code list} inspection: the number of base and delta segments, the deepest
   * stack, and the payload bytes.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> summarizeList(Map<String, Object> listReport) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> inspection = (Map<String, Object>) listReport.get("inspection");
    List<Map<String, Object>> segments =
      inspection == null ? null : (List<Map<String, Object>>) inspection.get("segments");
    if (segments == null) {
      return out;
    }
    int bases = 0;
    int deltas = 0;
    long payload = 0;
    long rows = 0;
    Map<String, Integer> stack = new LinkedHashMap<>();
    for (Map<String, Object> s : segments) {
      if ("delta".equals(s.get("kind"))) {
        deltas++;
      } else {
        bases++;
        Object r = s.get("rows");
        if (r instanceof Number) {
          rows += ((Number) r).longValue();
        }
      }
      Object p = s.get("payloadBytes");
      if (p instanceof Number) {
        payload += ((Number) p).longValue();
      }
      stack.merge(String.valueOf(s.get("region")), 1, Integer::sum);
    }
    out.put("segments", segments.size());
    out.put("base_segments", bases);
    out.put("delta_segments", deltas);
    out.put("base_rows", rows);
    out.put("payload_bytes", payload);
    out.put("max_stack_depth", stack.values().stream().mapToInt(Integer::intValue).max().orElse(0));
    return out;
  }

  /** Summarizes an HNSW {@code coverage} inspection: covered and exact region counts. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> summarizeCoverage(Map<String, Object> coverageReport) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> inspection = (Map<String, Object>) coverageReport.get("inspection");
    List<Map<String, Object>> regions =
      inspection == null ? null : (List<Map<String, Object>>) inspection.get("regions");
    if (regions == null) {
      return out;
    }
    int covered = 0;
    int exact = 0;
    for (Map<String, Object> r : regions) {
      if (Boolean.TRUE.equals(r.get("covered"))) {
        covered++;
      }
      if (Boolean.TRUE.equals(r.get("exact"))) {
        exact++;
      }
    }
    out.put("regions", regions.size());
    out.put("covered", covered);
    out.put("exact", exact);
    Object retirable = inspection.get("retirable");
    out.put("retirable", retirable instanceof List ? ((List<?>) retirable).size() : 0);
    return out;
  }

  /** Summarizes an HNSW {@code delta} inspection: overflowed regions and changed rows. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> summarizeDelta(Map<String, Object> deltaReport) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> inspection = (Map<String, Object>) deltaReport.get("inspection");
    List<Map<String, Object>> regions =
      inspection == null ? null : (List<Map<String, Object>>) inspection.get("regions");
    if (regions == null) {
      return out;
    }
    int overflowed = 0;
    long changed = 0;
    for (Map<String, Object> r : regions) {
      if (Boolean.TRUE.equals(r.get("overflowed"))) {
        overflowed++;
      }
      Object c = r.get("changedRows");
      if (c instanceof Number) {
        changed += ((Number) c).longValue();
      }
    }
    out.put("regions", regions.size());
    out.put("overflowed", overflowed);
    out.put("changed_rows", changed);
    return out;
  }
}
