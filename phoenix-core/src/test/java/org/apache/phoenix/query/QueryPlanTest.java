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
package org.apache.phoenix.query;

import static org.apache.phoenix.query.explain.ExplainPlanTestUtil.assertPlan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import org.apache.phoenix.optimize.OptimizerReasons;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.junit.Test;

/** Verifies query plan details via {@link org.apache.phoenix.compile.ExplainPlanAttributes}. */
public class QueryPlanTest extends BaseConnectionlessQueryTest {

  /**
   * True when the V2 WHERE optimizer is enabled. Tests whose explain output differs between V1 and
   * V2 (V2 typically retains a residual server filter where V1 fully consumes the predicate into
   * the scan range) branch on this helper.
   */
  protected static boolean isV2Optimizer() {
    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(getUrl())) {
      return conn.unwrap(org.apache.phoenix.jdbc.PhoenixConnection.class).getQueryServices()
        .getConfiguration()
        .getBoolean(org.apache.phoenix.query.QueryServices.WHERE_OPTIMIZER_V2_ENABLED,
          org.apache.phoenix.query.QueryServicesOptions.DEFAULT_WHERE_OPTIMIZER_V2_ENABLED);
    } catch (java.sql.SQLException e) {
      return false;
    }
  }

  @Test
  public void testExplainPlan() throws Exception {
    Properties props = new Properties();
    // Override date format so we don't have a bunch of zeros
    props.setProperty(QueryServices.DATE_FORMAT_ATTRIB, "yyyy-MM-dd");
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      // Pair #1 (V1 marginally cheaper, V2 correct): V1 fully consumes the RVC <= bound
      // into the [..,'003'] - [..,'005'] scan range and emits no server filter. V2
      // produces the identical scan range but defensively retains the lex-expanded RVC
      // predicate as a server filter. The residual is redundant (the scan range
      // already enforces it) and only costs one extra byte-comparison per scanned row.
      String query1 = "SELECT a_string,b_string FROM atable WHERE organization_id = "
        + "'000000000000001' AND entity_id > '000000000000002' AND entity_id < "
        + "'000000000000008' AND (organization_id,entity_id) <= "
        + "('000000000000001','000000000000005') ";
      if (isV2Optimizer()) {
        assertPlan(conn, query1).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000003'] - ['000000000000001','000000000000005']")
          .serverWhereFilter("SERVER FILTER BY (ORGANIZATION_ID < TO_CHAR('000000000000001') OR "
            + "(ORGANIZATION_ID = TO_CHAR('000000000000001') AND ENTITY_ID <= "
            + "TO_CHAR('000000000000005')))");
      } else {
        assertPlan(conn, query1).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000003'] - ['000000000000001','000000000000005']")
          .serverWhereFilter(null);
      }

      // Pair #2 (V2 strictly better): V1 narrows to (inst=null, host=not-null) and
      // leaves DATE >= ... in a server filter that has to evaluate against every row
      // returned by the scan. V2 promotes the DATE lower bound into the scan range
      // itself as a SKIP SCAN with all 3 PK dims compound-encoded.
      String query2 = "SELECT host FROM PTSDB WHERE inst IS NULL AND host IS NOT NULL AND "
        + "\"DATE\" >= to_date('2013-01-01')";
      if (isV2Optimizer()) {
        assertPlan(conn, query2).iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 1 RANGE")
          .table("PTSDB").keyRanges("[null,not null,'2013-01-01'] - [null,not null,*]")
          .serverFirstKeyOnlyProjection(true);
      } else {
        assertPlan(conn, query2).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("PTSDB").keyRanges("[null,not null]").serverFirstKeyOnlyProjection(true)
          .serverWhereFilter("SERVER FILTER BY \"DATE\" >= DATE '2013-01-01 00:00:00.000'");
      }

      // Pair #3 (V1 marginally cheaper, V2 correct): same scan range, V2 retains a
      // redundant residual filter that the scan range already enforces. See pair #1.
      String query3 = "SELECT a_string,b_string FROM atable WHERE organization_id = "
        + "'000000000000001' AND entity_id > '000000000000002' AND entity_id < "
        + "'000000000000008' AND (organization_id,entity_id) >= "
        + "('000000000000001','000000000000005') ";
      if (isV2Optimizer()) {
        assertPlan(conn, query3).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000005'] - ['000000000000001','000000000000008']")
          .serverWhereFilter("SERVER FILTER BY (ORGANIZATION_ID > TO_CHAR('000000000000001') OR "
            + "(ORGANIZATION_ID = TO_CHAR('000000000000001') AND ENTITY_ID >= "
            + "TO_CHAR('000000000000005')))");
      } else {
        assertPlan(conn, query3).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000005'] - ['000000000000001','000000000000008']")
          .serverWhereFilter(null);
      }

      assertPlan(conn, "SELECT host FROM PTSDB3 WHERE host IN ('na1', 'na2','na3')")
        .iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 3 KEYS").table("PTSDB3")
        .keyRanges("[~'na3'] - [~'na1']").serverFirstKeyOnlyProjection(true);

      assertPlan(conn, "SELECT /*+ SMALL*/ host FROM PTSDB3 WHERE host IN ('na1', 'na2','na3')")
        .iteratorType("PARALLEL 1-WAY").hint("SMALL").scanType("SKIP SCAN ON 3 KEYS")
        .table("PTSDB3").keyRanges("[~'na3'] - [~'na1']").serverFirstKeyOnlyProjection(true);

      assertPlan(conn,
        "SELECT inst,\"DATE\" FROM PTSDB2 WHERE inst = 'na1' ORDER BY inst DESC, "
          + "\"DATE\" DESC").iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table("PTSDB2")
            .keyRanges("['na1']").clientSortedBy("REVERSE").serverFirstKeyOnlyProjection(true);

      // Pair #7 (V2 strictly better): V1 stops compound emission at the IS NOT NULL
      // on `inst` and leaves both `HOST IS NULL` AND `DATE >=` as server filters. V2
      // continues compound emission into a 3-dim SKIP SCAN.
      String query7 = "SELECT host FROM PTSDB WHERE inst IS NOT NULL AND host IS NULL AND "
        + "\"DATE\" >= to_date('2013-01-01')";
      if (isV2Optimizer()) {
        assertPlan(conn, query7).iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 1 RANGE")
          .table("PTSDB").keyRanges("[not null,null,'2013-01-01'] - [not null,null,*]")
          .serverFirstKeyOnlyProjection(true);
      } else {
        assertPlan(conn, query7).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("PTSDB").keyRanges("[not null]").serverFirstKeyOnlyProjection(true)
          .serverWhereFilter(
            "SERVER FILTER BY (HOST IS NULL AND \"DATE\" >= DATE " + "'2013-01-01 00:00:00.000')");
      }

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id = '000000000000001' AND "
          + "entity_id = '000000000000002' AND x_integer = 2 AND a_integer < 5 ")
            .iteratorType("PARALLEL 1-WAY").scanType("POINT LOOKUP ON 1 KEY").table("ATABLE")
            .serverWhereFilter("SERVER FILTER BY (X_INTEGER = 2 AND A_INTEGER < 5)");

      // Pair #9 (equivalent scan, divergent explain string): V1 fuses `org_id > '001'`
      // and `(org_id, entity_id) >= ('003','005')` into a single RANGE SCAN with a
      // compound start row plus a server filter for the entity-id range. V2 emits a
      // SKIP SCAN ON 2 RANGES whose two slots correspond to the lex-expanded RVC.
      String query9 = "SELECT a_string,b_string FROM atable WHERE organization_id > "
        + "'000000000000001' AND entity_id > '000000000000002' AND entity_id < "
        + "'000000000000008' AND (organization_id,entity_id) >= "
        + "('000000000000003','000000000000005') ";
      if (isV2Optimizer()) {
        assertPlan(conn, query9).iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 2 RANGES")
          .table("ATABLE").keyRanges("['000000000000003'] - [*]")
          .serverWhereFilter("SERVER FILTER BY (ORGANIZATION_ID > TO_CHAR('000000000000003') OR "
            + "(ORGANIZATION_ID = TO_CHAR('000000000000003') AND ENTITY_ID >= "
            + "TO_CHAR('000000000000005')))");
      } else {
        assertPlan(conn, query9).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE").keyRanges("['000000000000003000000000000005'] - [*]")
          .serverWhereFilter("SERVER FILTER BY (ENTITY_ID > '000000000000002' AND ENTITY_ID < "
            + "'000000000000008')");
      }

      // Pair #10 (V1 marginally cheaper, V2 correct): same scan range. V1 recognizes
      // the RVC >= ('000','005') is dominated by org_id='001' AND entity_id>='002'
      // and drops it. V2 retains it as a residual filter. See pair #1.
      String query10 = "SELECT a_string,b_string FROM atable WHERE organization_id = "
        + "'000000000000001' AND entity_id >= '000000000000002' AND entity_id < "
        + "'000000000000008' AND (organization_id,entity_id) >= "
        + "('000000000000000','000000000000005') ";
      if (isV2Optimizer()) {
        assertPlan(conn, query10).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000002'] - ['000000000000001','000000000000008']")
          .serverWhereFilter("SERVER FILTER BY (ORGANIZATION_ID > TO_CHAR('000000000000000') OR "
            + "(ORGANIZATION_ID = TO_CHAR('000000000000000') AND ENTITY_ID >= "
            + "TO_CHAR('000000000000005')))");
      } else {
        assertPlan(conn, query10).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("ATABLE")
          .keyRanges(
            "['000000000000001','000000000000002'] - ['000000000000001','000000000000008']")
          .serverWhereFilter(null);
      }

      assertPlan(conn, "SELECT * FROM atable").iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN")
        .table("ATABLE");

      assertPlan(conn,
        "SELECT inst,host FROM PTSDB WHERE inst IN ('na1', 'na2','na3') AND host IN ('a','b') "
          + "AND \"DATE\" >= to_date('2013-01-01') AND \"DATE\" < to_date('2013-01-02')")
            .iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 6 RANGES").table("PTSDB")
            .keyRanges("['na1','a','2013-01-01'] - ['na3','b','2013-01-02']")
            .serverFirstKeyOnlyProjection(true);

      assertPlan(conn,
        "SELECT inst,host FROM PTSDB WHERE inst LIKE 'na%' AND host IN ('a','b') AND "
          + "\"DATE\" >= to_date('2013-01-01') AND \"DATE\" < to_date('2013-01-02')")
            .iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 2 RANGES").table("PTSDB")
            .keyRanges("['na','a','2013-01-01'] - ['nb','b','2013-01-02']")
            .serverFirstKeyOnlyProjection(true);

      assertPlan(conn, "SELECT count(*) FROM atable").iteratorType("PARALLEL 1-WAY")
        .scanType("FULL SCAN").table("ATABLE").serverFirstKeyOnlyProjection(true)
        .serverAggregate("SERVER AGGREGATE INTO SINGLE ROW");

      assertPlan(conn,
        "SELECT count(*) FROM atable WHERE organization_id='000000000000001' AND "
          + "SUBSTR(entity_id,1,3) > '002' AND SUBSTR(entity_id,1,3) <= '003'")
            .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table("ATABLE")
            .keyRanges(
              "['000000000000001','003            '] - ['000000000000001','004            ']")
            .serverFirstKeyOnlyProjection(true).serverAggregate("SERVER AGGREGATE INTO SINGLE ROW");

      assertPlan(conn,
        "SELECT a_string FROM atable WHERE organization_id='000000000000001' AND "
          + "SUBSTR(entity_id,1,3) > '002' AND SUBSTR(entity_id,1,3) <= '003'")
            .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table("ATABLE").keyRanges(
              "['000000000000001','003            '] - ['000000000000001','004            ']");

      assertPlan(conn, "SELECT count(1) FROM atable GROUP BY a_string")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table("ATABLE")
        .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [A_STRING]")
        .clientSortAlgo("CLIENT MERGE SORT");

      assertPlan(conn, "SELECT count(1) FROM atable GROUP BY a_string LIMIT 5")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table("ATABLE")
        .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [A_STRING]")
        .clientSortAlgo("CLIENT MERGE SORT").clientRowLimit(5);

      assertPlan(conn, "SELECT a_string FROM atable ORDER BY a_string DESC LIMIT 3")
        .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table("ATABLE").serverRowLimit(3L)
        .serverSortedBy("[A_STRING DESC]").clientSortAlgo("CLIENT MERGE SORT").clientRowLimit(3);

      assertPlan(conn,
        "SELECT count(1) FROM atable GROUP BY a_string,b_string HAVING max(a_string) = 'a'")
          .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table("ATABLE")
          .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [A_STRING, B_STRING]")
          .clientSortAlgo("CLIENT MERGE SORT").clientFilterBy("MAX(A_STRING) = 'a'");

      assertPlan(conn,
        "SELECT count(1) FROM atable WHERE a_integer = 1 GROUP BY "
          + "ROUND(a_time,'HOUR',2),entity_id HAVING max(a_string) = 'a'")
            .iteratorType("PARALLEL 1-WAY").scanType("FULL SCAN").table("ATABLE")
            .serverWhereFilter("SERVER FILTER BY A_INTEGER = 1")
            .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [ENTITY_ID, ROUND(A_TIME)]")
            .clientSortAlgo("CLIENT MERGE SORT").clientFilterBy("MAX(A_STRING) = 'a'");

      assertPlan(conn,
        "SELECT count(1) FROM atable WHERE a_integer = 1 GROUP BY a_string,b_string HAVING "
          + "max(a_string) = 'a' ORDER BY b_string").iteratorType("PARALLEL 1-WAY")
            .scanType("FULL SCAN").table("ATABLE")
            .serverWhereFilter("SERVER FILTER BY A_INTEGER = 1")
            .serverAggregate("SERVER AGGREGATE INTO DISTINCT ROWS BY [A_STRING, B_STRING]")
            .clientSortAlgo("CLIENT MERGE SORT").clientFilterBy("MAX(A_STRING) = 'a'")
            .clientSortedBy("[B_STRING]");

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id = '000000000000001' AND "
          + "entity_id != '000000000000002' AND x_integer = 2 AND a_integer < 5 LIMIT 10")
            .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table("ATABLE")
            .keyRanges("['000000000000001']")
            .serverWhereFilter("SERVER FILTER BY (ENTITY_ID != '000000000000002' AND X_INTEGER = 2 "
              + "AND A_INTEGER < 5)")
            .serverRowLimit(10L).clientRowLimit(10);

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id = '000000000000001' "
          + "ORDER BY a_string ASC NULLS FIRST LIMIT 10").iteratorType("PARALLEL 1-WAY")
            .scanType("RANGE SCAN").table("ATABLE").keyRanges("['000000000000001']")
            .serverRowLimit(10L).serverSortedBy("[A_STRING]").clientSortAlgo("CLIENT MERGE SORT")
            .clientRowLimit(10);

      assertPlan(conn,
        "SELECT max(a_integer) FROM atable WHERE organization_id = '000000000000001' GROUP BY "
          + "organization_id,entity_id,ROUND(a_date,'HOUR') ORDER BY entity_id NULLS LAST LIMIT 10")
            .iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN").table("ATABLE")
            .keyRanges("['000000000000001']")
            .serverAggregate(
              "SERVER AGGREGATE INTO DISTINCT ROWS BY [ORGANIZATION_ID, ENTITY_ID, ROUND(A_DATE)]")
            .clientSortAlgo("CLIENT MERGE SORT").clientRowLimit(10);

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id = '000000000000001' "
          + "ORDER BY a_string DESC NULLS LAST LIMIT 10").iteratorType("PARALLEL 1-WAY")
            .scanType("RANGE SCAN").table("ATABLE").keyRanges("['000000000000001']")
            .serverRowLimit(10L).serverSortedBy("[A_STRING DESC NULLS LAST]")
            .clientSortAlgo("CLIENT MERGE SORT").clientRowLimit(10);

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id IN "
          + "('000000000000001', '000000000000005')").iteratorType("PARALLEL 1-WAY")
            .scanType("SKIP SCAN ON 2 KEYS").table("ATABLE")
            .keyRanges("['000000000000001'] - ['000000000000005']");

      assertPlan(conn,
        "SELECT a_string,b_string FROM atable WHERE organization_id IN "
          + "('00D000000000001', '00D000000000005') AND entity_id IN"
          + "('00E00000000000X','00E00000000000Z')").iteratorType("PARALLEL 1-WAY")
            .scanType("POINT LOOKUP ON 4 KEYS").table("ATABLE");

      // Pair #29 (equivalent scan, divergent explain string): V1 promotes the
      // REGEXP_SUBSTR IN-list into SKIP SCAN ON 3 RANGES. V2 coalesces the three
      // contiguous sub-ranges into a single RANGE SCAN over the same byte range.
      String query29 = "SELECT inst,host FROM PTSDB WHERE REGEXP_SUBSTR(INST, '[^-]+', 1) IN "
        + "('na1', 'na2','na3')";
      String filter29 = "SERVER FILTER BY REGEXP_SUBSTR(INST, '[^-]+', 1) IN ('na1','na2','na3')";
      if (isV2Optimizer()) {
        assertPlan(conn, query29).iteratorType("PARALLEL 1-WAY").scanType("RANGE SCAN")
          .table("PTSDB").keyRanges("['na1'] - ['na4']").serverFirstKeyOnlyProjection(true)
          .serverWhereFilter(filter29);
      } else {
        assertPlan(conn, query29).iteratorType("PARALLEL 1-WAY").scanType("SKIP SCAN ON 3 RANGES")
          .table("PTSDB").keyRanges("['na1'] - ['na4']").serverFirstKeyOnlyProjection(true)
          .serverWhereFilter(filter29);
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testTenantSpecificConnWithLimit() throws Exception {
    String baseTableDDL =
      "CREATE TABLE BASE_MULTI_TENANT_TABLE(\n " + "  tenant_id VARCHAR(5) NOT NULL,\n"
        + "  userid INTEGER NOT NULL,\n" + "  username VARCHAR NOT NULL,\n" + "  col VARCHAR\n "
        + "  CONSTRAINT pk PRIMARY KEY (tenant_id, userid, username)) MULTI_TENANT=true";
    Connection conn = DriverManager.getConnection(getUrl());
    conn.createStatement().execute(baseTableDDL);
    conn.close();

    String tenantId = "tenantId";
    String tenantViewDDL = "CREATE VIEW TENANT_VIEW AS SELECT * FROM BASE_MULTI_TENANT_TABLE";
    Properties tenantProps = new Properties();
    tenantProps.put(PhoenixRuntime.TENANT_ID_ATTRIB, tenantId);
    conn = DriverManager.getConnection(getUrl(), tenantProps);
    conn.createStatement().execute(tenantViewDDL);

    // LIMIT 1 uses a SERIAL iterator and pushes the limit to both server and client.
    assertPlan(conn, "SELECT * FROM TENANT_VIEW LIMIT 1").iteratorType("SERIAL")
      .scanType("RANGE SCAN").table("BASE_MULTI_TENANT_TABLE").keyRanges("['tenantId']")
      .serverRowLimit(1L).clientRowLimit(1).indexRule(OptimizerReasons.RULE_DATA_TABLE)
      .indexRejectedNone();

    // A very large limit falls back to a PARALLEL iterator.
    assertPlan(conn, "SELECT * FROM TENANT_VIEW LIMIT " + Integer.MAX_VALUE)
      .iteratorType("PARALLEL").scanType("RANGE SCAN").table("BASE_MULTI_TENANT_TABLE")
      .keyRanges("['tenantId']").serverRowLimit((long) Integer.MAX_VALUE)
      .clientRowLimit(Integer.MAX_VALUE).indexRule(OptimizerReasons.RULE_DATA_TABLE)
      .indexRejectedNone();

    // V2 strictly better: V1 narrows only on the tenant prefix and leaves
    // `USERNAME = 'Joe'` as a server filter that must reject every non-'Joe'
    // username row. V2 promotes the equality on the trailing PK column into the
    // scan range itself (with the unconstrained `userid` middle dim emitted as a
    // wildcard) -> ['tenantId',*,'Joe']. HBase rejects non-'Joe' rows pre-filter,
    // so the LIMIT 1 is satisfied with far fewer rows scanned. The redundant
    // residual filter is a small CPU cost on the (presumably few) matching rows.
    String usernameKeyRanges = isV2Optimizer() ? "['tenantId',*,'Joe']" : "['tenantId']";
    assertPlan(conn, "SELECT * FROM TENANT_VIEW WHERE username = 'Joe' LIMIT 1")
      .scanType("RANGE SCAN").table("BASE_MULTI_TENANT_TABLE").keyRanges(usernameKeyRanges)
      .serverWhereFilter("SERVER FILTER BY USERNAME = 'Joe'").serverRowLimit(1L).clientRowLimit(1)
      .indexRule(OptimizerReasons.RULE_DATA_TABLE).indexRejectedNone();

    assertPlan(conn, "SELECT * FROM TENANT_VIEW WHERE col = 'Joe' LIMIT 1").scanType("RANGE SCAN")
      .table("BASE_MULTI_TENANT_TABLE").keyRanges("['tenantId']")
      .serverWhereFilter("SERVER FILTER BY COL = 'Joe'").serverRowLimit(1L).clientRowLimit(1)
      .indexRule(OptimizerReasons.RULE_DATA_TABLE).indexRejectedNone();
  }

  @Test
  public void testDescTimestampAtBoundary() throws Exception {
    Properties props = PropertiesUtil.deepCopy(new Properties());
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement()
        .execute("CREATE TABLE FOO(\n" + "                a VARCHAR NOT NULL,\n"
          + "                b TIMESTAMP NOT NULL,\n" + "                c VARCHAR,\n"
          + "                CONSTRAINT pk PRIMARY KEY (a, b DESC, c)\n"
          + "              ) IMMUTABLE_ROWS=true\n" + "                ,SALT_BUCKETS=20");
      String query = "select * from foo where a = 'a' and b >= timestamp '2016-01-28 00:00:00'"
        + " and b < timestamp '2016-01-29 00:00:00'";
      // The salient detail is the DESC-timestamp key range.
      assertPlan(conn, query).scanType("RANGE SCAN").table("FOO")
        .keyRanges(
          "[X'00','a',~'2016-01-28 23:59:59.999'] -" + " [X'13','a',~'2016-01-28 00:00:00.000']")
        .serverFirstKeyOnlyProjection(true).clientSortAlgo("CLIENT MERGE SORT")
        .indexRule(OptimizerReasons.RULE_DATA_TABLE).indexRejectedNone();
    } finally {
      conn.close();
    }
  }

  @Test
  public void testUseOfRoundRobinIteratorSurfaced() throws Exception {
    Properties props = PropertiesUtil.deepCopy(new Properties());
    props.put(QueryServices.FORCE_ROW_KEY_ORDER_ATTRIB, Boolean.toString(false));
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = "testUseOfRoundRobinIteratorSurfaced".toUpperCase();
    try {
      conn.createStatement()
        .execute("CREATE TABLE " + tableName + "(\n" + "                a VARCHAR NOT NULL,\n"
          + "                b TIMESTAMP NOT NULL,\n" + "                c VARCHAR,\n"
          + "                CONSTRAINT pk PRIMARY KEY (a, b DESC, c)\n"
          + "              ) IMMUTABLE_ROWS=true\n" + "                ,SALT_BUCKETS=20");
      String query =
        "select * from " + tableName + " where a = 'a' and b >= timestamp '2016-01-28 00:00:00'"
          + " and b < timestamp '2016-01-29 00:00:00'";
      // The round-robin iterator is surfaced as a dedicated attribute.
      assertPlan(conn, query).useRoundRobinIterator(true).scanType("RANGE SCAN").table(tableName)
        .keyRanges(
          "[X'00','a',~'2016-01-28 23:59:59.999'] -" + " [X'13','a',~'2016-01-28 00:00:00.000']")
        .serverFirstKeyOnlyProjection(true).indexRule(OptimizerReasons.RULE_DATA_TABLE)
        .indexRejectedNone();
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSerialHintIgnoredForNonRowkeyOrderBy() throws Exception {
    Properties props = PropertiesUtil.deepCopy(new Properties());
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement()
        .execute("CREATE TABLE FOO(\n" + "                a VARCHAR NOT NULL,\n"
          + "                b TIMESTAMP NOT NULL,\n" + "                c VARCHAR,\n"
          + "                CONSTRAINT pk PRIMARY KEY (a, b DESC, c)\n" + "              )");
      String query = "select /*+ SERIAL*/ * from foo where a = 'a' ORDER BY b, c";
      // The SERIAL hint is ignored for a non-rowkey ORDER BY, so the iterator stays PARALLEL and a
      // server sort + client merge sort are planned.
      assertPlan(conn, query).iteratorType("PARALLEL").scanType("RANGE SCAN").table("FOO")
        .keyRanges("['a']").serverFirstKeyOnlyProjection(true).serverSortedBy("[B, C]")
        .clientSortAlgo("CLIENT MERGE SORT").indexRule(OptimizerReasons.RULE_DATA_TABLE)
        .indexRejectedNone();
    } finally {
      conn.close();
    }
  }
}
