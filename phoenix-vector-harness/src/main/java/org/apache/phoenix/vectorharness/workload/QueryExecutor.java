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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.monitoring.MetricType;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs queries for one worker thread, over cached connections (one global connection and one per
 * tenant) and cached prepared statements. Each execution records the latency, the returned rows,
 * the distances the engine reported, and the Phoenix request metrics of the result set: rows
 * scanned, RPCs, and bytes. Not thread safe; each worker owns one executor.
 */
public final class QueryExecutor implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(QueryExecutor.class);
  static final int MAX_TENANT_CONNECTIONS = 64;

  /** The outcome of one execution. */
  public static final class Result {
    private final long[] ordinals;
    private final double[] distances;
    private final long latencyNanos;
    private final long serviceNanos;
    private final long rowsScanned;
    private final long rpcs;
    private final long remoteRpcs;
    private final long scanBytes;
    private final long regions;
    private final String error;

    Result(long[] ordinals, double[] distances, long latencyNanos, long serviceNanos,
      long rowsScanned, long rpcs, long remoteRpcs, long scanBytes, long regions, String error) {
      this.ordinals = ordinals;
      this.distances = distances;
      this.latencyNanos = latencyNanos;
      this.serviceNanos = serviceNanos;
      this.rowsScanned = rowsScanned;
      this.rpcs = rpcs;
      this.remoteRpcs = remoteRpcs;
      this.scanBytes = scanBytes;
      this.regions = regions;
      this.error = error;
    }

    static Result failed(long latencyNanos, String error) {
      return new Result(new long[0], new double[0], latencyNanos, latencyNanos, 0, 0, 0, 0, 0,
        error);
    }

    /** Returns the ordinals of the returned rows, in result order. */
    public long[] ordinals() {
      return ordinals;
    }

    /** Returns the distances the engine reported, in result order. */
    public double[] distances() {
      return distances;
    }

    /** Returns the latency from the scheduled dispatch time to the last row. */
    public long latencyNanos() {
      return latencyNanos;
    }

    /** Returns the time from submission to the last row. */
    public long serviceNanos() {
      return serviceNanos;
    }

    public long rowsScanned() {
      return rowsScanned;
    }

    public long rpcs() {
      return rpcs;
    }

    public long remoteRpcs() {
      return remoteRpcs;
    }

    public long scanBytes() {
      return scanBytes;
    }

    public long regions() {
      return regions;
    }

    /** Returns the error message of a failed query, or null. */
    public String error() {
      return error;
    }

    public boolean failed() {
      return error != null;
    }
  }

  private final HarnessContext ctx;
  private final int timeoutSeconds;
  private Connection global;
  private final Map<String, Connection> tenants =
    new LinkedHashMap<String, Connection>(16, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Connection> eldest) {
        if (size() > MAX_TENANT_CONNECTIONS) {
          statements.remove(eldest.getValue());
          closeQuietly(eldest.getValue());
          return true;
        }
        return false;
      }
    };
  private final Map<Connection, Map<String, PreparedStatement>> statements = new HashMap<>();

  public QueryExecutor(HarnessContext ctx) {
    this.ctx = ctx;
    this.timeoutSeconds = (int) Math.max(1, ctx.config().queryTimeout().getSeconds());
  }

  static Properties queryProperties() {
    Properties p = new Properties();
    p.setProperty(QueryServices.COLLECT_REQUEST_LEVEL_METRICS, "true");
    return p;
  }

  /** Returns the connection that runs a query, opening it on first use. */
  public Connection connectionFor(QuerySpec q) throws SQLException {
    if (q.tenantId() == null) {
      if (global == null) {
        global = ctx.connection(queryProperties());
      }
      return global;
    }
    Connection c = tenants.get(q.tenantId());
    if (c == null) {
      Properties p = queryProperties();
      p.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, q.tenantId());
      c = ctx.connection(p);
      tenants.put(q.tenantId(), c);
    }
    return c;
  }

  private PreparedStatement statement(Connection c, String sql) throws SQLException {
    Map<String, PreparedStatement> m = statements.computeIfAbsent(c, k -> new HashMap<>());
    PreparedStatement ps = m.get(sql);
    if (ps == null || ps.isClosed()) {
      ps = c.prepareStatement(sql);
      ps.setQueryTimeout(timeoutSeconds);
      m.put(sql, ps);
    }
    return ps;
  }

  static void bind(Connection c, PreparedStatement ps, List<Object> binds) throws SQLException {
    for (int i = 0; i < binds.size(); i++) {
      Object v = binds.get(i);
      if (v instanceof float[]) {
        ps.setArray(i + 1, RowWriter.vectorArray(c, (float[]) v));
      } else if (v instanceof Double) {
        ps.setDouble(i + 1, (Double) v);
      } else if (v instanceof Long) {
        ps.setLong(i + 1, (Long) v);
      } else {
        ps.setString(i + 1, String.valueOf(v));
      }
    }
  }

  /**
   * Runs a query.
   * @param scheduledNanos the System.nanoTime at which the query was due, which open-loop workers
   *                       pass to measure latency without coordinated omission; 0 means now
   */
  public Result execute(QuerySpec q, long scheduledNanos) {
    long start = System.nanoTime();
    long due = scheduledNanos == 0 ? start : scheduledNanos;
    try {
      Connection c = connectionFor(q);
      PreparedStatement ps = statement(c, q.sql());
      bind(c, ps, q.binds());
      List<Long> ordinals = new ArrayList<>(q.k());
      List<Double> distances = new ArrayList<>(q.k());
      long rowsScanned = 0;
      long rpcs = 0;
      long remote = 0;
      long bytes = 0;
      long regions = 0;
      ResultSet rs = ps.executeQuery();
      try {
        while (rs.next()) {
          ordinals.add(QueryFactory.ordinalOf(rs.getString(1)));
          double d = rs.getDouble(2);
          distances.add(rs.wasNull() ? Double.NaN : d);
        }
      } finally {
        // Closing the result set closes its scanners, which publishes their HBase scan metrics.
        rs.close();
      }
      long end = System.nanoTime();
      Map<String, Map<MetricType, Long>> metrics = PhoenixRuntime.getRequestReadMetricInfo(rs);
      for (Map<MetricType, Long> m : metrics.values()) {
        rowsScanned += value(m, MetricType.COUNT_ROWS_SCANNED);
        rpcs += value(m, MetricType.COUNT_RPC_CALLS);
        remote += value(m, MetricType.COUNT_REMOTE_RPC_CALLS);
        bytes += value(m, MetricType.SCAN_BYTES);
        regions += value(m, MetricType.COUNT_SCANNED_REGIONS);
      }
      long[] o = new long[ordinals.size()];
      double[] d = new double[distances.size()];
      for (int i = 0; i < o.length; i++) {
        o[i] = ordinals.get(i);
        d[i] = distances.get(i);
      }
      return new Result(o, d, end - due, end - start, rowsScanned, rpcs, remote, bytes, regions,
        null);
    } catch (SQLException | RuntimeException e) {
      LOG.debug("Query failed: {}", q.sql(), e);
      long end = System.nanoTime();
      return Result.failed(end - due, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static long value(Map<MetricType, Long> m, MetricType t) {
    Long v = m.get(t);
    return v == null ? 0 : v;
  }

  /** Returns the EXPLAIN text of a query. */
  public String explain(QuerySpec q) throws SQLException {
    Connection c = connectionFor(q);
    try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + q.sql())) {
      bind(c, ps, q.binds());
      try (ResultSet rs = ps.executeQuery()) {
        return QueryUtil.getExplainPlan(rs);
      }
    }
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException e) {
      LOG.debug("Closing a connection", e);
    }
  }

  @Override
  public void close() {
    for (Map<String, PreparedStatement> m : statements.values()) {
      for (PreparedStatement ps : m.values()) {
        try {
          ps.close();
        } catch (SQLException e) {
          LOG.debug("Closing a statement", e);
        }
      }
    }
    statements.clear();
    for (Connection c : tenants.values()) {
      closeQuietly(c);
    }
    tenants.clear();
    if (global != null) {
      closeQuietly(global);
      global = null;
    }
  }
}
