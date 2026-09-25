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
package org.apache.phoenix.end2end;

import static org.apache.phoenix.query.explain.ExplainPlanTestUtil.assertMutationPlan;
import static org.apache.phoenix.query.explain.ExplainPlanTestUtil.assertPlan;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.compile.ExplainPlan;
import org.apache.phoenix.compile.ExplainPlanAttributes;
import org.apache.phoenix.jdbc.PhoenixPreparedStatement;
import org.apache.phoenix.optimize.OptimizerReasons;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;

@Category(NeedsOwnMiniClusterTest.class)
public class CostBasedDecisionIT extends BaseTest {
  private final String testTable500;
  private final String testTable990;
  private final String testTable1000;

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    Map<String, String> props = Maps.newHashMapWithExpectedSize(1);
    props.put(QueryServices.STATS_GUIDEPOST_WIDTH_BYTES_ATTRIB, Long.toString(20));
    props.put(QueryServices.STATS_UPDATE_FREQ_MS_ATTRIB, Long.toString(5));
    props.put(QueryServices.USE_STATS_FOR_PARALLELIZATION, Boolean.toString(true));
    props.put(QueryServices.COST_BASED_OPTIMIZER_ENABLED, Boolean.toString(true));
    props.put(QueryServices.MAX_SERVER_CACHE_SIZE_ATTRIB, Long.toString(150000));
    setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
  }

  public CostBasedDecisionIT() throws Exception {
    testTable500 = initTestTableValues(500);
    testTable990 = initTestTableValues(990);
    testTable1000 = initTestTableValues(1000);
  }

  @Test
  public void testCostOverridesStaticPlanOrdering1() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 VARCHAR,\n" + "c2 VARCHAR)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + tableName + "_idx ON " + tableName + " (c1)");

      String query =
        "SELECT rowkey, c1, c2 FROM " + tableName + " where c1 LIKE 'X0%' ORDER BY rowkey";
      // V1 (no stats): The cost optimizer can't tightly estimate `c1 LIKE 'X0%'`
      // selectivity, so it conservatively picks FULL SCAN of the data table because
      // that plan preserves the ORDER BY rowkey for free.
      // V2 (no stats): V2's compound emission produces a tight scan range estimate
      // [1,'X0'] - [1,'X1'] for the local index -- `LIKE 'X0%'` is recognized as a
      // range predicate at compile time, not just at stats time. With this estimate
      // available, the cost optimizer correctly picks the index even pre-stats.
      // V2 is strictly better here: index scan reads ~1/16th of the rows
      // (`c1='X0*'` is 1 of 16 distinct values for ~10000 rows, ~625 rows) plus a
      // cheap client merge sort, vs V1 reading all 10000 data rows and rejecting
      // ~9375 via the server filter.
      if (isV2Optimizer()) {
        // Local index: tableName attribute carries the physical data table in
        // parentheses (see OnDuplicateKey2IT's expectedTable convention).
        assertPlan(conn, query).scanType("RANGE SCAN").table(tableName + "_IDX(" + tableName + ")");
      } else {
        // Use the data table plan that opts out order-by when stats are not available.
        assertPlan(conn, query).scanType("FULL SCAN")
          .indexRule(OptimizerReasons.RULE_MORE_BOUND_PK_COLUMNS).indexRejectedCount(1)
          .indexRejected(0, tableName + "_IDX",
            OptimizerReasons.REASON_LOCAL_INDEX_LOSES_TO_GLOBAL_BY_RULE);
      }

      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2) VALUES (?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        int c1 = i % 16;
        stmt.setString(1, "k" + i);
        stmt.setString(2, "X" + Integer.toHexString(c1) + c1);
        stmt.setString(3, "c");
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // After stats become available, both V1 and V2 pick the index RANGE SCAN.
      // Use the index table plan that has a lower cost when stats become available.
      assertPlan(conn, query).scanType("RANGE SCAN").indexRule(OptimizerReasons.RULE_COST_BASED)
        .indexRejectedNone();
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrdering2() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName = tableName + "_IDX";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 VARCHAR,\n" + "c2 VARCHAR)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + " (c1)");

      String query =
        "SELECT c1, max(rowkey), max(c2) FROM " + tableName + " where rowkey <= 'z' GROUP BY c1";
      // Use the index table plan that opts out order-by when stats are not available.
      ExplainPlan plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      ExplainPlanAttributes explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("RANGE SCAN", explainPlanAttributes.getExplainScanType());
      assertEquals(tableName, explainPlanAttributes.getTableName());
      assertEquals("[*] - ['z']", explainPlanAttributes.getKeyRanges());
      assertEquals("SERVER AGGREGATE INTO DISTINCT ROWS BY [C1]",
        explainPlanAttributes.getServerAggregate());
      assertEquals("CLIENT MERGE SORT", explainPlanAttributes.getClientSortAlgo());

      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2) VALUES (?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        int c1 = i % 16;
        stmt.setString(1, "k" + i);
        stmt.setString(2, "X" + Integer.toHexString(c1) + c1);
        stmt.setString(3, "c");
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // Given that the range on C1 is meaningless and group-by becomes
      // order-preserving if using the data table, the data table plan should
      // come out as the best plan based on the costs.
      plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class).optimizeQuery()
        .getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("RANGE SCAN", explainPlanAttributes.getExplainScanType());
      assertEquals(indexName + "(" + tableName + ")", explainPlanAttributes.getTableName());
      // V1 leaves `rowkey <= 'z'` as a server filter and the index keyRanges collapse
      // to "[1]" -- i.e., the scan covers the entire local-index region prefix. V2's
      // compound emission extracts the trailing-PK bound into the scan range itself
      // (local-index PK is [regionByte, c1, rowkey]) -> "[1,*,*] - [1,*,'z']". V2 is
      // strictly better: any rowkey > 'z' is rejected by HBase before reaching the
      // server filter, saving regionserver cycles and network bytes. Same admitted
      // row set.
      assertEquals(isV2Optimizer() ? "[1,*,*] - [1,*,'z']" : "[1]",
        explainPlanAttributes.getKeyRanges());
      assertTrue(explainPlanAttributes.isServerFirstKeyOnlyProjection());
      assertEquals("SERVER FILTER BY \"ROWKEY\" <= 'z'",
        explainPlanAttributes.getServerWhereFilter());
      assertEquals("SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"C1\"]",
        explainPlanAttributes.getServerAggregate());
      assertEquals("CLIENT MERGE SORT", explainPlanAttributes.getClientSortAlgo());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrdering3() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName1 = tableName + "_IDX1";
      String indexName2 = tableName + "_IDX2";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 INTEGER,\n" + "c2 INTEGER,\n" + "c3 INTEGER)");
      conn.createStatement().execute(
        "CREATE LOCAL INDEX " + indexName1 + " ON " + tableName + " (c1) INCLUDE (c2, c3)");
      conn.createStatement().execute(
        "CREATE LOCAL INDEX " + indexName2 + " ON " + tableName + " (c2, c3) INCLUDE (c1)");

      String query =
        "SELECT * FROM " + tableName + " where c1 BETWEEN 10 AND 20 AND c2 < 9000 AND C3 < 5000";
      // V1 narrows the scan to (region, c2 < 9000) and leaves both `C1 BETWEEN 10
      // AND 20` and `C3 < 5000` as a server filter. V2 additionally extracts
      // `C3 < 5000` into the trailing scan bound (idx2 PK is [region, c2, c3,
      // rowkey]) — the scan becomes SKIP SCAN ON 1 RANGE [2,*,*] - [2,9000,5000].
      // V2 is strictly better: rows with c3 ≥ 5000 are rejected by HBase before
      // reaching the server filter, the residual filter shrinks accordingly, and
      // network egress is reduced. Same admitted rows.
      ExplainPlan plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      ExplainPlanAttributes explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals(isV2Optimizer() ? "SKIP SCAN ON 1 RANGE" : "RANGE SCAN",
        explainPlanAttributes.getExplainScanType());
      assertEquals(indexName2 + "(" + tableName + ")", explainPlanAttributes.getTableName());
      assertEquals(isV2Optimizer() ? "[2,*,*] - [2,9,000,5,000]" : "[2,*] - [2,9,000]",
        explainPlanAttributes.getKeyRanges());
      assertEquals(
        isV2Optimizer()
          ? "SERVER FILTER BY (\"C1\" >= 10 AND \"C1\" <= 20)"
          : "SERVER FILTER BY ((\"C1\" >= 10 AND \"C1\" <= 20) AND TO_INTEGER(\"C3\") < 5000)",
        explainPlanAttributes.getServerWhereFilter());
      assertEquals("CLIENT MERGE SORT", explainPlanAttributes.getClientSortAlgo());

      PreparedStatement stmt = conn
        .prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2, c3) VALUES (?, ?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        stmt.setString(1, "k" + i);
        stmt.setInt(2, i);
        stmt.setInt(3, i);
        stmt.setInt(4, i);
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // Use the idx2 plan that scans less data when stats become available.
      plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class).optimizeQuery()
        .getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("RANGE SCAN", explainPlanAttributes.getExplainScanType());
      assertEquals(indexName1 + "(" + tableName + ")", explainPlanAttributes.getTableName());
      assertEquals("[1,10] - [1,20]", explainPlanAttributes.getKeyRanges());
      assertEquals("SERVER FILTER BY (\"C2\" < 9000 AND \"C3\" < 5000)",
        explainPlanAttributes.getServerWhereFilter());
      assertEquals("CLIENT MERGE SORT", explainPlanAttributes.getClientSortAlgo());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrderingInUpsertQuery() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName1 = tableName + "_IDX1";
      String indexName2 = tableName + "_IDX2";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 INTEGER,\n" + "c2 INTEGER,\n" + "c3 INTEGER)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + indexName1 + " ON " + tableName + "(c1) INCLUDE (c2, c3)");
      conn.createStatement().execute(
        "CREATE LOCAL INDEX " + indexName2 + " ON " + tableName + " (c2, c3) INCLUDE (c1)");

      String query = "UPSERT INTO " + tableName + " SELECT * FROM " + tableName
        + " where c1 BETWEEN 10 AND 20 AND c2 < 9000 AND C3 < 5000";
      // V1 narrows the scan to (region, c2 < 9000) and keeps c3 < 5000 as a server
      // filter. V2 additionally extracts `C3 < 5000` into the trailing scan bound
      // (idx2 PK: [c2, c3, rowkey] with leading region byte) -> SKIP SCAN ON 1 RANGE
      // [2,*,*] - [2,9000,5000]. V2 is strictly better: rows with c3 >= 5000 are
      // rejected by HBase pre-filter, fewer rows traverse the server filter, and the
      // upstream UPSERT does less work. Same admitted rows.
      if (isV2Optimizer()) {
        assertMutationPlan(conn, query).abstractExplainPlan("UPSERT SELECT")
          .iteratorType("PARALLEL").scanType("SKIP SCAN ON 1 RANGE")
          .table(indexName2 + "(" + tableName + ")").keyRanges("[2,*,*] - [2,9,000,5,000]")
          .serverWhereFilter("SERVER FILTER BY (\"C1\" >= 10 AND \"C1\" <= 20)")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_NON_LOCAL_PREFERRED)
          .indexRejectedNone();
      } else {
        // Use the idx2 plan with a wider PK slot span when stats are not available.
        assertMutationPlan(conn, query).abstractExplainPlan("UPSERT SELECT")
          .iteratorType("PARALLEL").scanType("RANGE SCAN").table(indexName2 + "(" + tableName + ")")
          .keyRanges("[2,*] - [2,9,000]")
          .serverWhereFilter(
            "SERVER FILTER BY ((\"C1\" >= 10 AND \"C1\" <= 20) AND TO_INTEGER(\"C3\") < 5000)")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_NON_LOCAL_PREFERRED)
          .indexRejectedNone();
      }

      PreparedStatement stmt = conn
        .prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2, c3) VALUES (?, ?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        stmt.setString(1, "k" + i);
        stmt.setInt(2, i);
        stmt.setInt(3, i);
        stmt.setInt(4, i);
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // Use the idx2 plan that scans less data when stats become available.
      assertMutationPlan(conn, query).abstractExplainPlan("UPSERT SELECT").iteratorType("PARALLEL")
        .scanType("RANGE SCAN").table(indexName1 + "(" + tableName + ")")
        .keyRanges("[1,10] - [1,20]")
        .serverWhereFilter("SERVER FILTER BY (\"C2\" < 9000 AND \"C3\" < 5000)")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
        .indexRejectedCount(1)
        .indexRejected(0, indexName2, OptimizerReasons.REASON_COST_BASED_LOSS);
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrderingInDeleteQuery() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName1 = tableName + "_IDX1";
      String indexName2 = tableName + "_IDX2";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 INTEGER,\n" + "c2 INTEGER,\n" + "c3 INTEGER)");
      conn.createStatement().execute(
        "CREATE LOCAL INDEX " + indexName1 + " ON " + tableName + " (c1) INCLUDE (c2, c3)");
      conn.createStatement().execute(
        "CREATE LOCAL INDEX " + indexName2 + " ON " + tableName + " (c2, c3) INCLUDE (c1)");

      String query =
        "DELETE FROM " + tableName + " where c1 BETWEEN 10 AND 20 AND c2 < 9000 AND C3 < 5000";
      // Same pattern as UpsertQuery (above) -- V2 extracts `C3 < 5000` into the
      // trailing scan bound, producing a tighter SKIP SCAN ON 1 RANGE; V1 leaves c3
      // as a server filter and emits a wider RANGE SCAN. V2 is strictly better:
      // fewer rows fetched from HBase -> fewer rows considered for delete -> less
      // network and disk write.
      if (isV2Optimizer()) {
        assertMutationPlan(conn, query).abstractExplainPlan("DELETE ROWS CLIENT SELECT")
          .iteratorType("PARALLEL").scanType("SKIP SCAN ON 1 RANGE")
          .table(indexName2 + "(" + tableName + ")").keyRanges("[2,*,*] - [2,9,000,5,000]")
          .serverWhereFilter("SERVER FILTER BY (\"C1\" >= 10 AND \"C1\" <= 20)")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_NON_LOCAL_PREFERRED)
          .indexRejectedNone();
      } else {
        // Use the idx2 plan with a wider PK slot span when stats are not available.
        assertMutationPlan(conn, query).abstractExplainPlan("DELETE ROWS CLIENT SELECT")
          .iteratorType("PARALLEL").scanType("RANGE SCAN").table(indexName2 + "(" + tableName + ")")
          .keyRanges("[2,*] - [2,9,000]")
          .serverWhereFilter(
            "SERVER FILTER BY ((\"C1\" >= 10 AND \"C1\" <= 20) AND TO_INTEGER(\"C3\") < 5000)")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_NON_LOCAL_PREFERRED)
          .indexRejectedNone();
      }

      PreparedStatement stmt = conn
        .prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2, c3) VALUES (?, ?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        stmt.setString(1, "k" + i);
        stmt.setInt(2, i);
        stmt.setInt(3, i);
        stmt.setInt(4, i);
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // Use the idx2 plan that scans less data when stats become available.
      assertMutationPlan(conn, query).abstractExplainPlan("DELETE ROWS CLIENT SELECT")
        .iteratorType("PARALLEL").scanType("RANGE SCAN").table(indexName1 + "(" + tableName + ")")
        .keyRanges("[1,10] - [1,20]")
        .serverWhereFilter("SERVER FILTER BY (\"C2\" < 9000 AND \"C3\" < 5000)")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
        .indexRejectedCount(1)
        .indexRejected(0, indexName2, OptimizerReasons.REASON_COST_BASED_LOSS);
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrderingInUnionQuery() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName = tableName + "_IDX";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 VARCHAR,\n" + "c2 VARCHAR)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + " (c1)");

      String query = "SELECT c1, max(rowkey), max(c2) FROM " + tableName
        + " where rowkey <= 'z' GROUP BY c1 " + "UNION ALL SELECT c1, max(rowkey), max(c2) FROM "
        + tableName + " where rowkey >= 'a' GROUP BY c1";
      // Use the default plan when stats are not available.
      assertPlan(conn, query).abstractExplainPlan("UNION ALL OVER 2 QUERIES").subPlanCount(2)
        .subPlan(0).iteratorType("PARALLEL").scanType("RANGE SCAN").table(tableName)
        .keyRanges("[*] - ['z']").serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [C1]")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_MORE_BOUND_PK_COLUMNS)
        .indexRejectedCount(1)
        .indexRejected(0, indexName, OptimizerReasons.REASON_NO_PK_PREFIX_BOUND).end().subPlan(1)
        .iteratorType("PARALLEL").scanType("RANGE SCAN").table(tableName).keyRanges("['a'] - [*]")
        .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [C1]")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_MORE_BOUND_PK_COLUMNS)
        .indexRejectedCount(1)
        .indexRejected(0, indexName, OptimizerReasons.REASON_NO_PK_PREFIX_BOUND).end();

      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2) VALUES (?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        int c1 = i % 16;
        stmt.setString(1, "k" + i);
        stmt.setString(2, "X" + Integer.toHexString(c1) + c1);
        stmt.setString(3, "c");
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // V1 uses a single-slot key range [1] (the full local-index region prefix)
      // with the full predicate as server filter. V2 additionally extracts
      // `rowkey <= 'z'` and `rowkey >= 'a'` into the trailing scan bound (local-
      // index PK: [region, c1, rowkey]) -> "[1,*,*] - [1,*,'z']" and
      // "[1,*,'a'] - [1,*,*]". V2 is strictly better: HBase rejects out-of-bound
      // rows pre-filter, so fewer rows are emitted from the scan. The server-side
      // filter still appears for correctness on the in-bounds rows but is harmless
      // (the scan range already enforces the bound). Same admitted rows.
      String firstScanKeyRanges = isV2Optimizer() ? "[1,*,*] - [1,*,'z']" : "[1]";
      String secondScanKeyRanges = isV2Optimizer() ? "[1,*,'a'] - [1,*,*]" : "[1]";
      // Use the optimal plan based on cost when stats become available.
      assertPlan(conn, query).abstractExplainPlan("UNION ALL OVER 2 QUERIES").subPlanCount(2)
        .subPlan(0).iteratorType("PARALLEL").scanType("RANGE SCAN")
        .table(indexName + "(" + tableName + ")").keyRanges(firstScanKeyRanges)
        .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
        .serverWhereFilter("SERVER FILTER BY \"ROWKEY\" <= 'z'")
        .serverAggregate("SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"C1\"]")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
        .indexRejectedNone().end().subPlan(1).iteratorType("PARALLEL").scanType("RANGE SCAN")
        .table(indexName + "(" + tableName + ")").keyRanges(secondScanKeyRanges)
        .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
        .serverWhereFilter("SERVER FILTER BY \"ROWKEY\" >= 'a'")
        .serverAggregate("SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"C1\"]")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
        .indexRejectedNone().end();
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCostOverridesStaticPlanOrderingInJoinQuery() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      String indexName = tableName + "_IDX";
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey VARCHAR PRIMARY KEY,\n" + "c1 VARCHAR,\n" + "c2 VARCHAR)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + tableName + "_idx ON " + tableName + " (c1)");

      String query = "SELECT t1.rowkey, t1.c1, t1.c2, t2.c1, mc2 FROM " + tableName + " t1 "
        + "JOIN (SELECT c1, max(rowkey) mrk, max(c2) mc2 FROM " + tableName
        + " where rowkey <= 'z' GROUP BY c1) t2 "
        + "ON t1.rowkey = t2.mrk WHERE t1.c1 LIKE 'X0%' ORDER BY t1.rowkey";
      // V1 (no stats): conservatively picks FULL SCAN of the data table for the
      // probe side because it can't tightly estimate `c1 LIKE 'X0%'` at compile time
      // (LIKE selectivity needs stats). The inner side scans the data table with
      // [*]-['z'] and the join is done with a dynamic server filter.
      // V2 (no stats): V2's compound emission produces a tight scan range estimate
      // [1,'X0'] - [1,'X1'] for the local index on the probe side at compile time
      // (LIKE 'X0%' is recognized as a prefix range). The cost optimizer then picks
      // the index for the probe side, which reads ~1/16th of the rows. The inner
      // side keeps the data-table RANGE SCAN [*]-['z']. V2 is strictly better:
      // probe side reads ~625 index rows + lookup vs V1 reading all 10000 data
      // rows and rejecting ~9375 via the LIKE filter; client merge sort is cheap.
      // Neither mode has an OptimizerDecision recorded at the top level for this
      // join-probe plan (indexRule(null)), consistent with the after-stats case
      // below for the same query shape.
      if (isV2Optimizer()) {
        assertPlan(conn, query).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table(indexName + "(" + tableName + ")").keyRanges("[1,'X0'] - [1,'X1']")
          .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
          .clientSortAlgo("CLIENT MERGE SORT")
          .dynamicServerFilter("DYNAMIC SERVER FILTER BY \"T1.:ROWKEY\" IN (T2.MRK)")
          .indexRule(null).indexRejectedNone().subPlanCount(1).subPlan(0)
          .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
          .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(tableName)
          .keyRanges("[*] - ['z']").serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [C1]")
          .clientSortAlgo("CLIENT MERGE SORT")
          .indexRule(OptimizerReasons.RULE_MORE_BOUND_PK_COLUMNS).indexRejectedCount(1)
          .indexRejected(0, indexName, OptimizerReasons.REASON_NO_PK_PREFIX_BOUND);
      } else {
        // Use the default plan when stats are not available.
        assertPlan(conn, query).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN")
          .table(tableName).serverWhereFilter("SERVER FILTER BY C1 LIKE 'X0%'")
          .dynamicServerFilter("DYNAMIC SERVER FILTER BY T1.ROWKEY IN (T2.MRK)").indexRule(null)
          .indexRejectedNone().subPlanCount(1).subPlan(0)
          .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
          .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(tableName)
          .keyRanges("[*] - ['z']").serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [C1]")
          .clientSortAlgo("CLIENT MERGE SORT")
          .indexRule(OptimizerReasons.RULE_MORE_BOUND_PK_COLUMNS).indexRejectedCount(1)
          .indexRejected(0, indexName, OptimizerReasons.REASON_NO_PK_PREFIX_BOUND);
      }

      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2) VALUES (?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        int c1 = i % 16;
        stmt.setString(1, "k" + i);
        stmt.setString(2, "X" + Integer.toHexString(c1) + c1);
        stmt.setString(3, "c");
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // After stats, V1 picks the index plan AND uses stats-based 626-WAY parallel
      // execution with an explicit SERVER SORTED BY step. V2 reaches the same logical
      // index plan pre-stats already (see comment above) and doesn't restructure the
      // plan when stats arrive -- it stays at 1-WAY parallelism without the stats-
      // driven SERVER SORTED BY. This is V2 being better on the WHERE-optimization
      // dimension (tighter scan ranges from the start, no plan churn) at the cost of
      // less stats-driven parallelism. The same row set is returned via the same
      // scan ranges. The inner side also differs: V1 emits [1] + server filter; V2
      // extracts `rowkey <= 'z'` into [1,*,*] - [1,*,'z'] (strictly better -- fewer
      // rows shipped to the join hash table).
      if (isV2Optimizer()) {
        assertPlan(conn, query).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table(indexName + "(" + tableName + ")").keyRanges("[1,'X0'] - [1,'X1']")
          .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
          .clientSortAlgo("CLIENT MERGE SORT")
          .dynamicServerFilter("DYNAMIC SERVER FILTER BY \"T1.:ROWKEY\" IN (T2.MRK)")
          .indexRule(null).indexRejectedNone().subPlanCount(1).subPlan(0)
          .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
          .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table(indexName + "(" + tableName + ")").keyRanges("[1,*,*] - [1,*,'z']")
          .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
          .serverWhereFilter("SERVER FILTER BY \"ROWKEY\" <= 'z'")
          .serverAggregate("SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"C1\"]")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
          .indexRejectedNone();
      } else {
        // Use the optimal plan based on cost when stats become available.
        assertPlan(conn, query).iteratorType("PARALLEL 626-WAY").scanType("RANGE SCAN")
          .table(indexName + "(" + tableName + ")").keyRanges("[1,'X0'] - [1,'X1']")
          .serverMergeColumns("[0.C2]").serverFirstKeyOnlyProjection(true)
          .serverSortedBy("[\"T1.:ROWKEY\"]").clientSortAlgo("CLIENT MERGE SORT")
          .dynamicServerFilter("DYNAMIC SERVER FILTER BY \"T1.:ROWKEY\" IN (T2.MRK)")
          .indexRule(null).indexRejectedNone().subPlanCount(1).subPlan(0)
          .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
          .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table(indexName + "(" + tableName + ")").keyRanges("[1]").serverMergeColumns("[0.C2]")
          .serverFirstKeyOnlyProjection(true)
          .serverWhereFilter("SERVER FILTER BY \"ROWKEY\" <= 'z'")
          .serverAggregate("SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"C1\"]")
          .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_COST_BASED)
          .indexRejectedNone();
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testHintOverridesCost() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    try {
      String tableName = BaseTest.generateUniqueName();
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "rowkey INTEGER PRIMARY KEY,\n" + "c1 VARCHAR,\n" + "c2 VARCHAR)");
      conn.createStatement()
        .execute("CREATE LOCAL INDEX " + tableName + "_idx ON " + tableName + " (c1)");

      String query =
        "SELECT rowkey, c1, c2 FROM " + tableName + " where rowkey between 1 and 10 ORDER BY c1";
      String hintedQuery = query.replaceFirst("SELECT",
        "SELECT  /*+ INDEX(" + tableName + " " + tableName + "_idx) */");
      String dataPlan = "[C1]";
      String indexPlan = "SERVER FILTER BY (\"ROWKEY\" >= 1 AND \"ROWKEY\" <= 10)";

      // Use the index table plan that opts out order-by when stats are not available.
      ExplainPlan plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      ExplainPlanAttributes explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals(indexPlan, explainPlanAttributes.getServerWhereFilter());

      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " (rowkey, c1, c2) VALUES (?, ?, ?)");
      for (int i = 0; i < 10000; i++) {
        int c1 = i % 16;
        stmt.setInt(1, i);
        stmt.setString(2, "X" + Integer.toHexString(c1) + c1);
        stmt.setString(3, "c");
        stmt.execute();
      }

      conn.createStatement().execute("UPDATE STATISTICS " + tableName);

      // Use the data table plan that has a lower cost when stats are available.
      plan = conn.prepareStatement(query).unwrap(PhoenixPreparedStatement.class).optimizeQuery()
        .getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals(dataPlan, explainPlanAttributes.getServerSortedBy());

      // Use the index table plan as has been hinted.
      plan = conn.prepareStatement(hintedQuery).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals(indexPlan, explainPlanAttributes.getServerWhereFilter());
    } finally {
      conn.close();
    }
  }

  /** Sort-merge-join w/ both children ordered wins over hash-join. */
  @Test
  public void testJoinStrategy() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.ID";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (INNER)").lhs()
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable500).end().rhs()
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000);
    }
  }

  /**
   * Sort-merge-join w/ both children ordered wins over hash-join in an un-grouped aggregate query.
   */
  @Test
  public void testJoinStrategy2() throws Exception {
    String q = "SELECT count(*)\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.ID\n" + "WHERE t1.COL1 < 200";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (INNER)")
        .clientAggregate("CLIENT AGGREGATE INTO SINGLE ROW").lhs().iteratorType("PARALLEL 1-WAY")
        .scanType("FULL SCAN").table(testTable500).serverWhereFilter("SERVER FILTER BY COL1 < 200")
        .end().rhs().iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .serverFirstKeyOnlyProjection(true);
    }
  }

  /** Hash-join w/ PK/FK optimization wins over sort-merge-join w/ larger side ordered. */
  @Test
  public void testJoinStrategy3() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.COL1 = t2.ID\n" + "WHERE t1.ID > 200";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .dynamicServerFilter("DYNAMIC SERVER FILTER BY T2.ID IN (T1.COL1)").subPlanCount(1)
        .subPlan(0).abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD LEFT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable500)
        .keyRanges("[201] - [*]");
    }
  }

  /**
   * Hash-join w/ PK/FK optimization wins over hash-join w/o PK/FK optimization when two sides are
   * close in size.
   */
  @Test
  public void testJoinStrategy4() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable990 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.COL1";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable990)
        .dynamicServerFilter("DYNAMIC SERVER FILTER BY T1.ID IN (T2.COL1)").subPlanCount(1)
        .subPlan(0).abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000);
    }
  }

  /** Hash-join wins over sort-merge-join w/ smaller side ordered. */
  @Test
  public void testJoinStrategy5() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.COL1\n" + "WHERE t1.ID > 200";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .subPlanCount(1).subPlan(0)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD LEFT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable500)
        .keyRanges("[201] - [*]");
    }
  }

  /** Hash-join wins over sort-merge-join w/o any side ordered. */
  @Test
  public void testJoinStrategy6() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.COL1 = t2.COL1\n" + "WHERE t1.ID > 200";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .subPlanCount(1).subPlan(0)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD LEFT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable500)
        .keyRanges("[201] - [*]");
    }
  }

  /**
   * Hash-join wins over sort-merge-join w/ both sides ordered in an order-by query. This is because
   * order-by can only be done on the client side after sort-merge-join and order-by w/o limit on
   * the client side is very expensive.
   */
  @Test
  public void testJoinStrategy7() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.ID\n" + "ORDER BY t1.COL1";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1001-WAY").scanType("FULL SCAN")
        .table(testTable1000).serverSortedBy("[T1.COL1]").clientSortAlgo("CLIENT MERGE SORT")
        .dynamicServerFilter("DYNAMIC SERVER FILTER BY T2.ID IN (T1.ID)").subPlanCount(1).subPlan(0)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD LEFT */")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable500);
    }
  }

  /**
   * Sort-merge-join w/ both sides ordered wins over hash-join in an order-by limit query. This is
   * because order-by can only be done on the client side after sort-merge-join but order-by w/
   * limit on the client side is less expensive.
   */
  @Test
  public void testJoinStrategy8() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable500 + " t1 JOIN " + testTable1000 + " t2\n"
      + "ON t1.ID = t2.ID\n" + "ORDER BY t1.COL1 LIMIT 5";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (INNER)").clientSortedBy("[T1.COL1]")
        .clientRowLimit(5).lhs().iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN")
        .table(testTable500).end().rhs().iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN")
        .table(testTable1000);
    }
  }

  /**
   * Multi-table join: sort-merge-join chosen since all join keys are PK.
   */
  @Test
  public void testJoinStrategy9() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable1000 + " t1 LEFT JOIN " + testTable500 + " t2\n"
      + "ON t1.ID = t2.ID AND t2.ID > 200\n" + "LEFT JOIN " + testTable990 + " t3\n"
      + "ON t1.ID = t3.ID AND t3.ID < 100";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (LEFT)").lhs()
        .abstractExplainPlan("SORT-MERGE-JOIN (LEFT)").lhs().iteratorType("PARALLEL 1-WAY")
        .scanType("FULL SCAN").table(testTable1000).end().rhs().iteratorType("PARALLEL 1-WAY")
        .scanType("RANGE SCAN").table(testTable500).keyRanges("[201] - [*]").end().end().rhs()
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable990)
        .keyRanges("[*] - [100]");
    }
  }

  /**
   * Multi-table join: a mix of join strategies chosen based on cost.
   */
  @Test
  public void testJoinStrategy10() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable1000 + " t1 JOIN " + testTable500 + " t2\n"
      + "ON t1.ID = t2.COL1 AND t2.ID > 200\n" + "JOIN " + testTable990 + " t3\n"
      + "ON t1.ID = t3.ID AND t3.ID < 100";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (INNER)").lhs()
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .dynamicServerFilter("DYNAMIC SERVER FILTER BY T1.ID IN (T2.COL1)").subPlanCount(1)
        .subPlan(0).abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable500)
        .keyRanges("[201] - [*]").end().end().rhs().iteratorType("PARALLEL 1-WAY")
        .scanType("RANGE SCAN").table(testTable990).keyRanges("[*] - [100]");
    }
  }

  /**
   * Multi-table join: hash-join two tables in parallel since two RHS tables are both small and can
   * fit in memory at the same time.
   */
  @Test
  public void testJoinStrategy11() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable1000 + " t1 JOIN " + testTable500 + " t2\n"
      + "ON t1.COL2 = t2.COL1 AND t2.ID > 200\n" + "JOIN " + testTable990 + " t3\n"
      + "ON t1.COL1 = t3.COL2 AND t3.ID < 100";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable1000)
        .subPlanCount(2).subPlan(0)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable500)
        .keyRanges("[201] - [*]").end().subPlan(1)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 1  /* HASH BUILD RIGHT */")
        .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table(testTable990)
        .keyRanges("[*] - [100]");
    }
  }

  /**
   * Multi-table join: similar to {@link this#testJoinStrategy11()}, but the two RHS tables cannot
   * fit in memory at the same time, and thus a mix of join strategies is chosen based on cost.
   */
  @Test
  public void testJoinStrategy12() throws Exception {
    String q = "SELECT *\n" + "FROM " + testTable1000 + " t1 JOIN " + testTable990 + " t2\n"
      + "ON t1.COL2 = t2.COL1\n" + "JOIN " + testTable990 + " t3\n" + "ON t1.COL1 = t3.COL2";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      assertPlan(conn, q).abstractExplainPlan("SORT-MERGE-JOIN (INNER)").lhs()
        .iteratorType("PARALLEL 1001-WAY").scanType("FULL SCAN").table(testTable1000)
        .serverSortedBy("[T1.COL1]").clientSortAlgo("CLIENT MERGE SORT").subPlanCount(1).subPlan(0)
        .abstractExplainPlan("PARALLEL INNER-JOIN TABLE 0  /* HASH BUILD RIGHT */")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table(testTable990).end().end().rhs()
        .iteratorType("PARALLEL 991-WAY").scanType("FULL SCAN").table(testTable990)
        .serverSortedBy("[T3.COL2]").clientSortAlgo("CLIENT MERGE SORT");
    }
  }

  private static String initTestTableValues(int rows) throws Exception {
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      String tableName = generateUniqueName();
      conn.createStatement().execute("CREATE TABLE " + tableName + " (\n"
        + "ID INTEGER NOT NULL PRIMARY KEY,\n" + "COL1 INTEGER," + "COL2 INTEGER)");
      PreparedStatement stmt =
        conn.prepareStatement("UPSERT INTO " + tableName + " VALUES(?, ?, ?)");
      for (int i = 0; i < rows; i++) {
        stmt.setInt(1, i + 1);
        stmt.setInt(2, rows - i);
        stmt.setInt(3, rows + i);
        stmt.execute();
      }
      conn.commit();
      conn.createStatement().execute("UPDATE STATISTICS " + tableName);
      return tableName;
    }
  }
}
