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

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.CENTROID_ID;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_VECTOR_CENTROID_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.HConstants;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.ConnectionQueryServices.Feature;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.SaltingUtil;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for HNSW graph-based vector index DDL compilation, schema generation, and
 * initial build state dispatch.
 */
@Category(ParallelStatsDisabledTest.class)
public class HnswIndexDdlIT extends ParallelStatsDisabledIT {

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    setUpTestDriver(ReadOnlyProps.EMPTY_PROPS);
  }

  @Test
  public void testHnswIndexPrimaryKeySchemaStandardTable() throws Exception {
    String tableName = "T_HNSW_STD_" + generateUniqueName();
    String indexName = "IDX_HNSW_STD_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Index table must exist in client catalog", indexTable);

      assertNotNull(indexTable.getVectorIndex());
      assertEquals("HNSW", indexTable.getVectorIndex().getAlgorithm());
      assertEquals(VectorIndexType.HNSW, indexTable.getVectorIndex().getType());
      assertTrue("Table must be recognized as a vector index", indexTable.isVectorIndex());

      // Assert no centroid column exists in all table columns
      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        String colName = col.getName().getString();
        assertFalse("HNSW index must not contain centroid column in columns: " + colName,
          centroidCol.equals(colName) || CENTROID_ID.equals(colName));
      }

      // Assert primary key columns strictly match base table PK columns [base table PK columns]
      List<PColumn> pkCols = indexTable.getPKColumns();
      assertEquals("HNSW index PK should consist solely of the base table PK column", 1,
        pkCols.size());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID"), pkCols.get(0).getName().getString());
    }
  }

  @Test
  public void testHnswIndexPrimaryKeySchemaCompositeKey() throws Exception {
    String tableName = "T_HNSW_COMPOSITE_" + generateUniqueName();
    String indexName = "IDX_HNSW_COMPOSITE_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName
        + " (ID1 VARCHAR NOT NULL, ID2 BIGINT NOT NULL, V VECTOR(FLOAT, 4), "
        + "CONSTRAINT PK PRIMARY KEY (ID1, ID2))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'L2', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull(indexTable);

      assertNotNull(indexTable.getVectorIndex());
      assertEquals(VectorIndexType.HNSW, indexTable.getVectorIndex().getType());

      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        assertFalse("HNSW index must not contain centroid column: " + col.getName().getString(),
          centroidCol.equals(col.getName().getString())
            || CENTROID_ID.equals(col.getName().getString()));
      }

      List<PColumn> pkCols = indexTable.getPKColumns();
      assertEquals(2, pkCols.size());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID1"), pkCols.get(0).getName().getString());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID2"), pkCols.get(1).getName().getString());
    }
  }

  @Test
  public void testHnswIndexPrimaryKeySchemaSaltedTable() throws Exception {
    String tableName = "T_HNSW_SALTED_" + generateUniqueName();
    String indexName = "IDX_HNSW_SALTED_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName
        + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4)) SALT_BUCKETS = 4");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull(indexTable);

      assertNotNull(indexTable.getVectorIndex());
      assertEquals(VectorIndexType.HNSW, indexTable.getVectorIndex().getType());

      // Assert no centroid column
      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        assertFalse(
          "HNSW salted index must not contain centroid column: " + col.getName().getString(),
          centroidCol.equals(col.getName().getString())
            || CENTROID_ID.equals(col.getName().getString()));
      }

      // Assert primary key layout: [salt][base table PK column]
      List<PColumn> pkCols = indexTable.getPKColumns();
      assertEquals("Salted HNSW index PK must contain salt column and base PK column", 2,
        pkCols.size());
      assertEquals(SaltingUtil.SALTING_COLUMN_NAME, pkCols.get(0).getName().getString());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID"), pkCols.get(1).getName().getString());
    }
  }

  @Test
  public void testHnswIndexPrimaryKeySchemaMultiTenantTable() throws Exception {
    String tableName = "T_HNSW_MT_" + generateUniqueName();
    String indexName = "IDX_HNSW_MT_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName
        + " (TENANT_ID VARCHAR NOT NULL, ID VARCHAR NOT NULL, V VECTOR(FLOAT, 4), "
        + "CONSTRAINT PK PRIMARY KEY (TENANT_ID, ID)) MULTI_TENANT = true");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'INNER_PRODUCT', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull(indexTable);

      assertNotNull(indexTable.getVectorIndex());
      assertEquals(VectorIndexType.HNSW, indexTable.getVectorIndex().getType());

      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        assertFalse("HNSW multi-tenant index must not contain centroid column",
          centroidCol.equals(col.getName().getString())
            || CENTROID_ID.equals(col.getName().getString()));
      }

      // Assert primary key layout: [tenant][base table PK column]
      List<PColumn> pkCols = indexTable.getPKColumns();
      assertEquals(2, pkCols.size());
      assertEquals(IndexUtil.getIndexColumnName(null, "TENANT_ID"),
        pkCols.get(0).getName().getString());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID"), pkCols.get(1).getName().getString());
    }
  }

  @Test
  public void testIvfIndexNonRegressionCentroidColumnPresent() throws Exception {
    String tableName = "T_IVF_REG_" + generateUniqueName();
    String indexName = "IDX_IVF_REG_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'IVF', metric = 'L2', dimension = 4, lists = 2, sample_size = 10)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull(indexTable);

      assertNotNull(indexTable.getVectorIndex());
      assertEquals("IVF", indexTable.getVectorIndex().getAlgorithm());
      assertEquals(VectorIndexType.IVF, indexTable.getVectorIndex().getType());
      assertTrue(indexTable.isVectorIndex());

      // Assert centroid column DOES exist in IVF index table columns
      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      boolean foundCentroid = false;
      for (PColumn col : indexTable.getColumns()) {
        if (centroidCol.equals(col.getName().getString())) {
          foundCentroid = true;
          break;
        }
      }
      assertTrue("IVF index table must contain centroid column " + centroidCol, foundCentroid);

      // Assert primary key layout prepends centroid column: [centroid_id][base table PK columns]
      List<PColumn> pkCols = indexTable.getPKColumns();
      assertEquals(2, pkCols.size());
      assertEquals(centroidCol, pkCols.get(0).getName().getString());
      assertEquals(IndexUtil.getIndexColumnName(null, "ID"), pkCols.get(1).getName().getString());
    }
  }

  @Test
  public void testHnswIndexInitialBuildStateWithoutCentroids() throws Exception {
    String tableName = "T_HNSW_BUILD_" + generateUniqueName();
    String indexName = "IDX_HNSW_BUILD_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
        stmt.execute("UPSERT INTO " + tableName + " VALUES ('r1', ARRAY[1.0, 0.0, 0.0, 0.0])");
        stmt.execute("UPSERT INTO " + tableName + " VALUES ('r2', ARRAY[0.0, 1.0, 0.0, 0.0])");
        conn.commit();

        // Create HNSW vector index synchronously
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");
      }

      // Verify index is in BUILDING state in PTable
      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertEquals("HNSW index must be initialized in BUILDING state", PIndexState.BUILDING,
        indexTable.getIndexState());

      // Verify index is in BUILDING state in SYSTEM.CATALOG
      try (Statement stmt = conn.createStatement();
        ResultSet rs =
          stmt.executeQuery("SELECT INDEX_STATE FROM SYSTEM.CATALOG WHERE TABLE_NAME = '"
            + indexName + "' AND TABLE_SCHEM IS NULL AND TENANT_ID IS NULL")) {
        assertTrue("SYSTEM.CATALOG row must exist for index", rs.next());
        assertEquals(PIndexState.BUILDING.getSerializedValue(), rs.getString("INDEX_STATE"));
      }

      // Verify no centroid rows exist in SYSTEM.VECTOR_CENTROID for this HNSW index
      String countCentroidSql =
        "SELECT COUNT(*) FROM " + SYSTEM_VECTOR_CENTROID_NAME + " WHERE " + INDEX_NAME + " = ?";
      try (PreparedStatement ps = conn.prepareStatement(countCentroidSql)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals("HNSW index must not populate SYSTEM.VECTOR_CENTROID", 0L, rs.getLong(1));
        }
      }
    }
  }

  @Test
  public void testHnswParameterRangeEnforcement() throws Exception {
    String tableName = "T_HNSW_RANGE_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");

      // M=2 (below 4)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, M = 2)");
        fail("Should have failed with INVALID_VECTOR_INDEX_PARAMS for M=2");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getSQLState(), e.getSQLState());
      }

      // M=100 (above 64)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, M = 100)");
        fail("Should have failed with INVALID_VECTOR_INDEX_PARAMS for M=100");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getSQLState(), e.getSQLState());
      }

      // ef_construction=8 (below 16)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, ef_construction = 8)");
        fail("Should have failed with INVALID_VECTOR_INDEX_PARAMS for ef_construction=8");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getSQLState(), e.getSQLState());
      }

      // alpha=0.5 (below 1.0)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, alpha = 0.5)");
        fail("Should have failed with INVALID_VECTOR_INDEX_PARAMS for alpha=0.5");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getSQLState(), e.getSQLState());
      }

      // alpha=3.0 (above 2.0)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, alpha = 3.0)");
        fail("Should have failed with INVALID_VECTOR_INDEX_PARAMS for alpha=3.0");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getSQLState(), e.getSQLState());
      }
    }
  }

  @Test
  public void testHnswQuantizationConstraints() throws Exception {
    String tableFloat = "T_HNSW_Q_FLT_" + generateUniqueName();
    String tableDouble = "T_HNSW_Q_DBL_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableFloat + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 768))");
      stmt.execute("CREATE TABLE " + tableDouble
        + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(DOUBLE, 128))");

      // quantization='PQ' with pq_segments=100 on 768-dim column (not evenly divisible)
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableFloat + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 768, quantization = 'PQ', pq_segments = 100)");
        fail("Should have failed with VECTOR_QUANTIZATION_DIMENSION_MISMATCH");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.VECTOR_QUANTIZATION_DIMENSION_MISMATCH.getErrorCode(),
          e.getErrorCode());
        assertEquals(SQLExceptionCode.VECTOR_QUANTIZATION_DIMENSION_MISMATCH.getSQLState(),
          e.getSQLState());
      }

      // quantization='SQ8' on VECTOR(DOUBLE, 128) must be rejected
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableDouble + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 128, quantization = 'SQ8')");
        fail(
          "Should have failed with UNSUPPORTED_VECTOR_QUANTIZATION_TYPE for SQ8 on DOUBLE vector");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE.getErrorCode(),
          e.getErrorCode());
        assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE.getSQLState(),
          e.getSQLState());
      }

      // Invalid quantization codec
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableFloat + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 768, quantization = 'INVALID_CODEC')");
        fail("Should have failed with UNSUPPORTED_VECTOR_QUANTIZATION_TYPE for invalid codec");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE.getErrorCode(),
          e.getErrorCode());
        assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE.getSQLState(),
          e.getSQLState());
      }
    }
  }

  @Test
  public void testCrossAlgorithmIsolation() throws Exception {
    String tableName = "T_CROSS_ALGO_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");

      // lists=16 with algorithm='HNSW'
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, lists = 16)");
        fail("Should have failed with VECTOR_ALGORITHM_PARAM_MISMATCH for lists on HNSW");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
          e.getErrorCode());
        assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getSQLState(),
          e.getSQLState());
      }

      // M=16 with algorithm='IVF'
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'IVF', metric = 'L2', dimension = 4, lists = 2, sample_size = 10, M = 16)");
        fail("Should have failed with VECTOR_ALGORITHM_PARAM_MISMATCH for M on IVF");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
          e.getErrorCode());
        assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getSQLState(),
          e.getSQLState());
      }
    }
  }

  @Test
  public void testHnswCoveringClauseRejection() throws Exception {
    String tableName = "T_COVERING_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName
        + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4), C1 VARCHAR)");

      // INCLUDE (C1) with algorithm='HNSW' must be rejected
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "INCLUDE (C1) WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");
        fail("Should have failed with HNSW_INCLUDE_NOT_SUPPORTED");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.HNSW_INCLUDE_NOT_SUPPORTED.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.HNSW_INCLUDE_NOT_SUPPORTED.getSQLState(), e.getSQLState());
      }

      // Confirm that INCLUDE remains valid for IVF indexes
      String ivfIndexName = "IDX_IVF_INC_" + generateUniqueName();
      stmt.execute("CREATE VECTOR INDEX " + ivfIndexName + " ON " + tableName + " (V) "
        + "INCLUDE (C1) WITH (algorithm = 'IVF', metric = 'L2', dimension = 4, lists = 2, sample_size = 10)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable ivfTable = pconn.getTable(new PTableKey(null, ivfIndexName));
      assertNotNull("IVF index table must be created with INCLUDE column", ivfTable);
      assertNotNull(ivfTable.getColumnForColumnName(
        IndexUtil.getIndexColumnName(QueryConstants.DEFAULT_COLUMN_FAMILY, "C1")));
    }
  }

  @Test
  public void testDefaultParameterApplication() throws Exception {
    String tableName = "T_DEFAULT_ALPHA_" + generateUniqueName();
    String indexName = "IDX_DEFAULT_ALPHA_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");

      // Create an HNSW index omitting alpha
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Index table must exist", indexTable);
      assertNotNull("VectorIndex metadata must exist", indexTable.getVectorIndex());
      assertEquals("Default alpha should be 1.2", Double.valueOf(1.2),
        indexTable.getVectorIndex().getHnswAlpha());

      // Verify default alpha persisted to SYSTEM.CATALOG
      try (PreparedStatement ps = conn.prepareStatement(
        "SELECT VECTOR_HNSW_ALPHA FROM SYSTEM.CATALOG WHERE TABLE_NAME = ? AND TABLE_SCHEM IS NULL AND TENANT_ID IS NULL")) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Catalog row must exist", rs.next());
          assertEquals(1.2, rs.getDouble("VECTOR_HNSW_ALPHA"), 0.001);
        }
      }

      // Verify survives cache eviction
      pconn.getQueryServices().clearCache();
      PTable reloadedTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Reloaded table must exist", reloadedTable);
      assertNotNull("Reloaded VectorIndex must exist", reloadedTable.getVectorIndex());
      assertEquals("Default alpha should survive cache eviction", Double.valueOf(1.2),
        reloadedTable.getVectorIndex().getHnswAlpha());
    }
  }

  @Test
  public void testHnswCatalogPersistenceAndMetadataEndpointRoundTrip() throws Exception {
    String tableName = "T_HNSW_PERSIST_" + generateUniqueName();
    String indexName = "IDX_HNSW_PERSIST_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");

      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, "
        + "M = 32, ef_construction = 64, alpha = 1.5, quantization = 'PQ', pq_segments = 2)");

      // 1. Verify direct query on SYSTEM.CATALOG
      String catalogQuery =
        "SELECT VECTOR_INDEX_ALGORITHM, VECTOR_DISTANCE_METRIC, VECTOR_DIMENSION, "
          + "VECTOR_HNSW_M, VECTOR_HNSW_EF_CONSTRUCTION, VECTOR_HNSW_ALPHA, VECTOR_QUANTIZATION_TYPE, VECTOR_PQ_SEGMENTS "
          + "FROM SYSTEM.CATALOG WHERE TABLE_NAME = ? AND TABLE_SCHEM IS NULL AND TENANT_ID IS NULL";
      try (PreparedStatement ps = conn.prepareStatement(catalogQuery)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Index metadata must exist in SYSTEM.CATALOG", rs.next());
          assertEquals("HNSW", rs.getString("VECTOR_INDEX_ALGORITHM"));
          assertEquals("COSINE", rs.getString("VECTOR_DISTANCE_METRIC"));
          assertEquals(4, rs.getInt("VECTOR_DIMENSION"));
          assertEquals(32, rs.getInt("VECTOR_HNSW_M"));
          assertEquals(64, rs.getInt("VECTOR_HNSW_EF_CONSTRUCTION"));
          assertEquals(1.5, rs.getDouble("VECTOR_HNSW_ALPHA"), 0.001);
          assertEquals("PQ", rs.getString("VECTOR_QUANTIZATION_TYPE"));
          assertEquals(2, rs.getInt("VECTOR_PQ_SEGMENTS"));
        }
      }

      // 2. Clear client-side table cache so MetaDataEndpointImpl must reconstruct PTable from
      // SYSTEM.CATALOG
      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.getQueryServices().clearCache();

      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Index table must be re-loaded from catalog", indexTable);
      assertNotNull("VectorIndex metadata must exist after reload", indexTable.getVectorIndex());
      assertEquals("HNSW", indexTable.getVectorIndex().getAlgorithm());
      assertEquals("COSINE", indexTable.getVectorIndex().getDistanceMetric());
      assertEquals(Integer.valueOf(4), indexTable.getVectorIndex().getDimension());
      assertEquals(Integer.valueOf(32), indexTable.getVectorIndex().getHnswM());
      assertEquals(Integer.valueOf(64), indexTable.getVectorIndex().getHnswEfConstruction());
      assertEquals(Double.valueOf(1.5), indexTable.getVectorIndex().getHnswAlpha());
      assertEquals("PQ", indexTable.getVectorIndex().getQuantizationType());
      assertEquals(Integer.valueOf(2), indexTable.getVectorIndex().getPqSegments());
    }
  }

  @Test
  public void testFeatureCompatibility() throws Exception {
    String tableName = "T_COMPAT_HNSW_" + generateUniqueName();
    String indexName = "IDX_COMPAT_HNSW_" + generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      conn.createStatement().execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable origSysCat =
        pconn.getTable(new PTableKey(null, PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME));
      List<PColumn> mockedCols = new ArrayList<>();
      int pos = 0;
      for (PColumn col : origSysCat.getColumns()) {
        if (!col.getName().getString().equals(PhoenixDatabaseMetaData.VECTOR_HNSW_M)) {
          mockedCols.add(new PColumnImpl(col, pos++));
        }
      }
      PTable mockSysCat = PTableImpl.builderWithColumns(origSysCat, mockedCols).build();
      pconn.addTable(mockSysCat, HConstants.LATEST_TIMESTAMP);
      try {
        // Assert that feature gating reports HNSW as unsupported when VECTOR_HNSW_M is absent
        assertFalse("HNSW must not be supported when VECTOR_HNSW_M column is absent",
          MetaDataUtil.supportsVectorIndex(pconn.getQueryServices(), VectorIndexType.HNSW));
        assertFalse("HNSW algorithm must not be supported when VECTOR_HNSW_M column is absent",
          MetaDataUtil.supportsVectorAlgorithm(pconn.getQueryServices(), "HNSW"));
        assertTrue(
          "Generic VECTOR_INDEX must still be supported when VECTOR_INDEX_ALGORITHM is present",
          pconn.getQueryServices().supportsFeature(Feature.VECTOR_INDEX));

        // Attempting to create an HNSW index throws INCOMPATIBLE_CLIENT_SERVER_JAR
        try {
          pconn.createStatement().execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName
            + " (V) WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");
          fail(
            "Expected CREATE VECTOR INDEX to fail with INCOMPATIBLE_CLIENT_SERVER_JAR when server lacks HNSW support");
        } catch (SQLException e) {
          assertEquals(SQLExceptionCode.INCOMPATIBLE_CLIENT_SERVER_JAR.getErrorCode(),
            e.getErrorCode());
          assertEquals(SQLExceptionCode.INCOMPATIBLE_CLIENT_SERVER_JAR.getSQLState(),
            e.getSQLState());
        }

        // IVF backward compatibility: IVF index creation continues to succeed against the same
        // catalog configuration
        String ivfIndexName = "IDX_COMPAT_IVF_" + generateUniqueName();
        pconn.createStatement().execute("CREATE VECTOR INDEX " + ivfIndexName + " ON " + tableName
          + " (V) WITH (algorithm = 'IVF', metric = 'L2', dimension = 4, lists = 2, sample_size = 10)");
        assertNotNull("IVF index must be created successfully",
          pconn.getTable(new PTableKey(null, ivfIndexName)));
      } finally {
        pconn.addTable(origSysCat, HConstants.LATEST_TIMESTAMP);
      }
    }
  }

  @Test
  public void testSupportsVectorIndexHnsw() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      assertTrue("Cluster must support Feature.VECTOR_INDEX",
        pconn.getQueryServices().supportsFeature(Feature.VECTOR_INDEX));
      assertTrue("Cluster must support HNSW via MetaDataUtil.supportsVectorIndex",
        MetaDataUtil.supportsVectorIndex(pconn.getQueryServices(), VectorIndexType.HNSW));
      assertTrue("Cluster must support HNSW via MetaDataUtil.supportsVectorAlgorithm",
        MetaDataUtil.supportsVectorAlgorithm(pconn.getQueryServices(), "HNSW"));
    }
  }
}
