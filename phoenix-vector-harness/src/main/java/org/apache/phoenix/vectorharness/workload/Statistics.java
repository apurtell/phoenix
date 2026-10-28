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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.StatisticsMode;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.observe.JmxReaders;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;

/**
 * Support for the statistics modes. The harness and the cluster must run the same mode. The off
 * mode collects no statistics and is the production mode. The on mode collects statistics, and the
 * harness updates them after each index build and each promotion that it sees.
 */
public final class Statistics {

  /** Client property to make UPDATE STATISTICS synchronous. */
  public static final String ASYNC = "phoenix.update.stats.command.async";
  /** Name of the deploy setting of the docker cluster. */
  static final String DEPLOY_SETTING = "VH_STATISTICS";

  private Statistics() {
  }

  /** Returns true if the Phoenix client of the connection reads statistics. */
  public static boolean clientCollects(Connection conn) throws SQLException {
    return conn.unwrap(PhoenixConnection.class).getQueryServices().getProps().getBoolean(
      QueryServices.STATS_COLLECTION_ENABLED,
      QueryServicesOptions.DEFAULT_STATS_COLLECTION_ENABLED);
  }

  /** Returns the collection switch of a server value. A missing value gives the Phoenix default. */
  static boolean serverCollects(String value) {
    return value == null
      ? QueryServicesOptions.DEFAULT_STATS_COLLECTION_ENABLED
      : Boolean.parseBoolean(value.trim());
  }

  /** Returns the advice that fixes a disagreement with the mode. */
  static String advice(StatisticsMode mode) {
    return "deploy the cluster with " + DEPLOY_SETTING + "=" + mode.label() + ", or set "
      + QueryServices.STATS_COLLECTION_ENABLED + "=" + (mode == StatisticsMode.ON)
      + " on the servers and the client";
  }

  /**
   * Lists the disagreements between the mode and the collection switch of the client and of each
   * server.
   * @param servers the server values for each server URL; a null value means that the key is unset
   */
  static List<String> problems(StatisticsMode mode, boolean client, Map<String, String> servers) {
    boolean want = mode == StatisticsMode.ON;
    String setting = Setting.TABLE_STATISTICS.key() + "=" + mode.label();
    List<String> out = new ArrayList<>();
    if (client != want) {
      out.add("the client sets " + QueryServices.STATS_COLLECTION_ENABLED + "=" + client
        + ", but the harness runs " + setting);
    }
    for (Map.Entry<String, String> e : servers.entrySet()) {
      if (serverCollects(e.getValue()) != want) {
        String v = e.getValue() == null ? "unset, which means true" : e.getValue().trim();
        out.add("RegionServer " + e.getKey() + " sets " + QueryServices.STATS_COLLECTION_ENABLED
          + " " + v + ", but the harness runs " + setting);
      }
    }
    return out;
  }

  /**
   * Checks that the client and the servers run the statistics mode of the profile. The server check
   * reads the live configuration from the info port of each RegionServer. Without the info ports,
   * only the client check applies.
   * @return the record of the check for the run outputs
   * @throws IllegalStateException if the client or a server does not run the mode
   */
  public static Map<String, Object> check(HarnessConfig c, Connection conn) throws SQLException {
    StatisticsMode mode = c.statisticsMode();
    boolean client = clientCollects(conn);
    Map<String, String> servers =
      JmxReaders.serverConf(c, conn, QueryServices.STATS_COLLECTION_ENABLED);
    List<String> problems = problems(mode, client, servers);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("The statistics mode does not match the cluster:\n  "
        + String.join("\n  ", problems) + "\nTo fix it, " + advice(mode));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", mode.label());
    out.put("client_collection", client);
    Map<String, Object> server = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : servers.entrySet()) {
      server.put(e.getKey(), serverCollects(e.getValue()));
    }
    out.put("servers_checked", servers.size());
    out.put("server_collection", server);
    return out;
  }

  /** Returns the statement that updates the statistics of a table, or only of its indexes. */
  static String statement(String table, boolean indexesOnly) {
    return "UPDATE STATISTICS " + table + (indexesOnly ? " INDEX" : " ALL");
  }

  /**
   * Updates the statistics of each table and of all its indexes, or only of the indexes. A
   * promotion changes only the index, so a steady state update does not scan the data table. The
   * update runs on a connection of its own and returns when the statistics are written.
   */
  public static void update(HarnessContext ctx, List<String> tables, boolean indexesOnly)
    throws SQLException {
    Properties p = new Properties();
    p.setProperty(ASYNC, "false");
    try (Connection conn = ctx.connection(p)) {
      for (String table : tables) {
        try (Statement s = conn.createStatement()) {
          s.execute(statement(table, indexesOnly));
        } catch (SQLException e) {
          throw disabledOnServer(e)
            ? new SQLException(
              "The cluster does not collect statistics," + " but the harness runs "
                + Setting.TABLE_STATISTICS.key() + "=on; " + advice(StatisticsMode.ON),
              e.getSQLState(), e.getErrorCode(), e)
            : e;
        }
      }
    }
  }

  /** Returns true if the failure comes from a server that does not collect statistics. */
  static boolean disabledOnServer(Throwable t) {
    SQLExceptionCode code = SQLExceptionCode.STATS_COLLECTION_DISABLED_ON_SERVER;
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      if (c instanceof SQLException && ((SQLException) c).getErrorCode() == code.getErrorCode()) {
        return true;
      }
      String m = c.getMessage();
      if (
        m != null && m.contains("ERROR " + code.getErrorCode() + " (" + code.getSQLState() + ")")
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Counts the statistics rows of a table. A region smaller than the guidepost width has one row
   * without a key. This empty marker gives no estimate, so the count of real guideposts excludes
   * it.
   * @return the row count, the real guideposts, and the empty markers
   */
  public static Map<String, Object> guideposts(Connection conn, String fullName)
    throws SQLException {
    String physical = StorageFootprint.physicalName(conn, fullName).getNameAsString();
    String sql = "SELECT COUNT(*), COUNT(" + PhoenixDatabaseMetaData.GUIDE_POST_KEY + ") FROM "
      + PhoenixDatabaseMetaData.SYSTEM_STATS_NAME + " WHERE "
      + PhoenixDatabaseMetaData.PHYSICAL_NAME + " = ?";
    long rows = 0;
    long keyed = 0;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, physical);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          rows = rs.getLong(1);
          keyed = rs.getLong(2);
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("physical_name", physical);
    out.put("rows", rows);
    out.put("guideposts", keyed);
    out.put("empty_markers", rows - keyed);
    return out;
  }

  /**
   * Counts the statistics rows of each data table and of each IVF index. An HNSW index shares the
   * HBase table of its data table, so its rows are the rows of the data table.
   */
  public static Map<String, Object> guideposts(Connection conn, TableNames names,
    Collection<TableLayout> layouts, VariantKind variant) throws SQLException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (TableLayout layout : layouts) {
      String table = names.fullTable(layout);
      out.put(table, guideposts(conn, table));
      if (variant == VariantKind.IVF) {
        String index = names.fullIndex(layout);
        out.put(index, guideposts(conn, index));
      }
    }
    return out;
  }
}
