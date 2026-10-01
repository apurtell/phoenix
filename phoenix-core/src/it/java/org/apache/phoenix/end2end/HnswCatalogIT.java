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
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.CONSTRUCTION_TIME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.GENERATION_ID;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.KEY_SEQ;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.NODE_COUNT;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REBUILD_STATE;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REBUILD_STATE_ACTIVE;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REBUILD_STATE_COMPLETE;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REGION_ENCODED_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REGION_END_KEY;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.REGION_START_KEY;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SEGMENT_ROW_KEY;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CATALOG_SCHEMA;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_CATALOG_TABLE;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_VECTOR_CENTROID_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_TABLE;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VECTOR_HNSW_EF_CONSTRUCTION;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VECTOR_HNSW_M;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VECTOR_PQ_SEGMENTS;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VECTOR_QUANTIZATION_TYPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.MobCompactPartitionPolicy;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.coprocessor.generated.PTableProtos;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableRef;
import org.apache.phoenix.schema.SaltingUtil;
import org.apache.phoenix.schema.SerializedPTableRef;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDouble;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(ParallelStatsDisabledTest.class)
public class HnswCatalogIT extends ParallelStatsDisabledIT {

  private final List<String> createdIndexNames = new ArrayList<>();

  private String uniqueIndex(String prefix) {
    String name = prefix + generateUniqueName();
    createdIndexNames.add(name);
    return name;
  }

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    setUpTestDriver(ReadOnlyProps.EMPTY_PROPS);
  }

  @After
  public void cleanUpVectorState() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      VectorIndexTestUtil.deleteGraphSegmentRows(conn, createdIndexNames);
      VectorIndexTestUtil.deleteCentroidRows(conn, createdIndexNames);
    } finally {
      createdIndexNames.clear();
      VectorIndexTestUtil.resetSharedVectorState();
    }
  }

  @Test
  public void testSystemVectorGraphSegmentTableSchema() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement(); ResultSet rs =
        stmt.executeQuery("SELECT * FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE 1=0")) {
      ResultSetMetaData rsmd = rs.getMetaData();
      assertEquals(9, rsmd.getColumnCount());

      // 1. INDEX_NAME VARCHAR NOT NULL
      assertEquals(INDEX_NAME, rsmd.getColumnName(1));
      assertEquals(Types.VARCHAR, rsmd.getColumnType(1));
      assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(1));

      // 2. REGION_START_KEY VARBINARY_ENCODED
      assertEquals(REGION_START_KEY, rsmd.getColumnName(2));
      assertEquals(PDataType.VARBINARY_ENCODED_TYPE, rsmd.getColumnType(2));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(2));

      // 3. GENERATION_ID BIGINT NOT NULL
      assertEquals(GENERATION_ID, rsmd.getColumnName(3));
      assertEquals(Types.BIGINT, rsmd.getColumnType(3));
      assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(3));

      // 4. REGION_END_KEY VARBINARY
      assertEquals(REGION_END_KEY, rsmd.getColumnName(4));
      assertEquals(Types.VARBINARY, rsmd.getColumnType(4));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(4));

      // 5. REGION_ENCODED_NAME VARCHAR
      assertEquals(REGION_ENCODED_NAME, rsmd.getColumnName(5));
      assertEquals(Types.VARCHAR, rsmd.getColumnType(5));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(5));

      // 6. SEGMENT_ROW_KEY VARBINARY
      assertEquals(SEGMENT_ROW_KEY, rsmd.getColumnName(6));
      assertEquals(Types.VARBINARY, rsmd.getColumnType(6));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(6));

      // 7. NODE_COUNT BIGINT
      assertEquals(NODE_COUNT, rsmd.getColumnName(7));
      assertEquals(Types.BIGINT, rsmd.getColumnType(7));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(7));

      // 8. CONSTRUCTION_TIME BIGINT
      assertEquals(CONSTRUCTION_TIME, rsmd.getColumnName(8));
      assertEquals(Types.BIGINT, rsmd.getColumnType(8));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(8));

      // 9. REBUILD_STATE CHAR(1)
      assertEquals(REBUILD_STATE, rsmd.getColumnName(9));
      assertEquals(Types.CHAR, rsmd.getColumnType(9));
      assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(9));
    }
  }

  @Test
  public void testSystemVectorGraphSegmentPrimaryKeyShape() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      DatabaseMetaData dbmd = conn.getMetaData();
      Map<String, Short> pkColToSeq = new HashMap<>();
      try (ResultSet rs =
        dbmd.getPrimaryKeys(null, SYSTEM_CATALOG_SCHEMA, SYSTEM_VECTOR_GRAPH_SEGMENT_TABLE)) {
        while (rs.next()) {
          pkColToSeq.put(rs.getString(COLUMN_NAME), rs.getShort(KEY_SEQ));
        }
      }
      assertEquals(3, pkColToSeq.size());
      assertEquals(Short.valueOf((short) 1), pkColToSeq.get(INDEX_NAME));
      assertEquals(Short.valueOf((short) 2), pkColToSeq.get(REGION_START_KEY));
      assertEquals(Short.valueOf((short) 3), pkColToSeq.get(GENERATION_ID));
    }
  }

  @Test
  public void testSegmentMetadataUpsertAndRetrieval() throws Exception {
    String indexName = uniqueIndex("IDX_SEG_TEST_");
    byte[] regionStartKey = Bytes.toBytes("region_start_01");
    byte[] regionEndKey = Bytes.toBytes("region_end_01");
    byte[] segmentRowKey = Bytes.toBytes("seg_mob_row_key_01");
    String encodedRegionName = "encoded_reg_12345";
    long generationId = 0L;
    long nodeCount = 10000L;
    long constructionTime = 1700000000000L;
    String rebuildState = REBUILD_STATE_ACTIVE;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String upsertSql = "UPSERT INTO " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " (" + INDEX_NAME
        + ", " + REGION_START_KEY + ", " + GENERATION_ID + ", " + REGION_END_KEY + ", "
        + REGION_ENCODED_NAME + ", " + SEGMENT_ROW_KEY + ", " + NODE_COUNT + ", "
        + CONSTRUCTION_TIME + ", " + REBUILD_STATE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, regionStartKey);
        ps.setLong(3, generationId);
        ps.setBytes(4, regionEndKey);
        ps.setString(5, encodedRegionName);
        ps.setBytes(6, segmentRowKey);
        ps.setLong(7, nodeCount);
        ps.setLong(8, constructionTime);
        ps.setString(9, rebuildState);
        ps.executeUpdate();
      }
      conn.commit();

      String querySql = "SELECT " + REGION_END_KEY + ", " + REGION_ENCODED_NAME + ", "
        + SEGMENT_ROW_KEY + ", " + NODE_COUNT + ", " + CONSTRUCTION_TIME + ", " + REBUILD_STATE
        + " FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + INDEX_NAME + " = ? AND "
        + REGION_START_KEY + " = ? AND " + GENERATION_ID + " = ?";
      try (PreparedStatement ps = conn.prepareStatement(querySql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, regionStartKey);
        ps.setLong(3, generationId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertArrayEquals(regionEndKey, rs.getBytes(1));
          assertEquals(encodedRegionName, rs.getString(2));
          assertArrayEquals(segmentRowKey, rs.getBytes(3));
          assertEquals(nodeCount, rs.getLong(4));
          assertEquals(constructionTime, rs.getLong(5));
          assertEquals(rebuildState, rs.getString(6));
          assertFalse(rs.next());
        }
      }
    }
  }

  @Test
  public void testSegmentMetadataWithNullRegionStartKey() throws Exception {
    String indexName = uniqueIndex("IDX_SEG_NULL_START_");
    byte[] regionEndKey = Bytes.toBytes("key_b");
    byte[] segmentRowKey = Bytes.toBytes("seg_mob_null_start");
    String encodedRegionName = "encoded_reg_null_start";
    long generationId = 0L;
    long nodeCount = 5000L;
    long constructionTime = 1700000000000L;
    String rebuildState = REBUILD_STATE_ACTIVE;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String upsertSql = "UPSERT INTO " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " (" + INDEX_NAME
        + ", " + REGION_START_KEY + ", " + GENERATION_ID + ", " + REGION_END_KEY + ", "
        + REGION_ENCODED_NAME + ", " + SEGMENT_ROW_KEY + ", " + NODE_COUNT + ", "
        + CONSTRUCTION_TIME + ", " + REBUILD_STATE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, null);
        ps.setLong(3, generationId);
        ps.setBytes(4, regionEndKey);
        ps.setString(5, encodedRegionName);
        ps.setBytes(6, segmentRowKey);
        ps.setLong(7, nodeCount);
        ps.setLong(8, constructionTime);
        ps.setString(9, rebuildState);
        ps.executeUpdate();
      }
      conn.commit();

      String querySql =
        "SELECT " + REGION_START_KEY + ", " + REGION_END_KEY + ", " + REGION_ENCODED_NAME + ", "
          + SEGMENT_ROW_KEY + ", " + NODE_COUNT + ", " + CONSTRUCTION_TIME + ", " + REBUILD_STATE
          + " FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + INDEX_NAME + " = ? AND "
          + REGION_START_KEY + " IS NULL AND " + GENERATION_ID + " = ?";
      try (PreparedStatement ps = conn.prepareStatement(querySql)) {
        ps.setString(1, indexName);
        ps.setLong(2, generationId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertNull(rs.getBytes(1));
          assertArrayEquals(regionEndKey, rs.getBytes(2));
          assertEquals(encodedRegionName, rs.getString(3));
          assertArrayEquals(segmentRowKey, rs.getBytes(4));
          assertEquals(nodeCount, rs.getLong(5));
          assertEquals(constructionTime, rs.getLong(6));
          assertEquals(rebuildState, rs.getString(7));
          assertFalse(rs.next());
        }
      }
    }
  }

  @Test
  public void testSegmentMetadataUpdate() throws Exception {
    String indexName = uniqueIndex("IDX_SEG_UPDATE_");
    byte[] regionStartKey = Bytes.toBytes("key_a");
    long generationId = 1L;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String upsertSql = "UPSERT INTO " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " (" + INDEX_NAME
        + ", " + REGION_START_KEY + ", " + GENERATION_ID + ", " + NODE_COUNT + ", " + REBUILD_STATE
        + ") VALUES (?, ?, ?, ?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, regionStartKey);
        ps.setLong(3, generationId);
        ps.setLong(4, 500L);
        ps.setString(5, REBUILD_STATE_ACTIVE);
        ps.executeUpdate();
      }
      conn.commit();

      // Update state to Complete with updated node count
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, regionStartKey);
        ps.setLong(3, generationId);
        ps.setLong(4, 1200L);
        ps.setString(5, REBUILD_STATE_COMPLETE);
        ps.executeUpdate();
      }
      conn.commit();

      String querySql = "SELECT " + NODE_COUNT + ", " + REBUILD_STATE + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + INDEX_NAME + " = ? AND " + REGION_START_KEY
        + " = ? AND " + GENERATION_ID + " = ?";
      try (PreparedStatement ps = conn.prepareStatement(querySql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, regionStartKey);
        ps.setLong(3, generationId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1200L, rs.getLong(1));
          assertEquals(REBUILD_STATE_COMPLETE, rs.getString(2));
          assertFalse(rs.next());
        }
      }
    }
  }

  @Test
  public void testDaughterRegionParentSegmentDiscoveryRangeScan() throws Exception {
    String indexName = uniqueIndex("IDX_RANGE_SCAN_");
    byte[] parentStartKey = Bytes.toBytes("100");
    byte[] parentEndKey = Bytes.toBytes("500");
    byte[] parentSegmentRowKey = Bytes.toBytes("seg_parent_row");

    byte[] precedingStartKey = Bytes.toBytes("000");
    byte[] precedingEndKey = Bytes.toBytes("100");
    byte[] precedingSegmentRowKey = Bytes.toBytes("seg_preceding_row");

    byte[] succeedingStartKey = Bytes.toBytes("500");
    byte[] succeedingEndKey = Bytes.toBytes("900");
    byte[] succeedingSegmentRowKey = Bytes.toBytes("seg_succeeding_row");

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String upsertSql = "UPSERT INTO " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " (" + INDEX_NAME
        + ", " + REGION_START_KEY + ", " + GENERATION_ID + ", " + REGION_END_KEY + ", "
        + SEGMENT_ROW_KEY + ") VALUES (?, ?, ?, ?, ?)";

      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        // Preceding segment: [000, 100)
        ps.setString(1, indexName);
        ps.setBytes(2, precedingStartKey);
        ps.setLong(3, 0L);
        ps.setBytes(4, precedingEndKey);
        ps.setBytes(5, precedingSegmentRowKey);
        ps.executeUpdate();

        // Parent segment: [100, 500)
        ps.setString(1, indexName);
        ps.setBytes(2, parentStartKey);
        ps.setLong(3, 0L);
        ps.setBytes(4, parentEndKey);
        ps.setBytes(5, parentSegmentRowKey);
        ps.executeUpdate();

        // Succeeding segment: [500, 900)
        ps.setString(1, indexName);
        ps.setBytes(2, succeedingStartKey);
        ps.setLong(3, 0L);
        ps.setBytes(4, succeedingEndKey);
        ps.setBytes(5, succeedingSegmentRowKey);
        ps.executeUpdate();
      }
      conn.commit();

      // Daughter A split boundaries: [100, 300)
      // For half-open intervals [start, end), a segment [reg_start, reg_end) overlaps
      // with daughter [d_start, d_end) when reg_start < d_end AND reg_end > d_start.
      byte[] daughterAStartKey = Bytes.toBytes("100");
      byte[] daughterAEndKey = Bytes.toBytes("300");

      String daughterQuerySql =
        "SELECT " + SEGMENT_ROW_KEY + " FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE "
          + INDEX_NAME + " = ? AND " + REGION_START_KEY + " < ? AND " + REGION_END_KEY + " > ?";

      try (PreparedStatement ps = conn.prepareStatement(daughterQuerySql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, daughterAEndKey);
        ps.setBytes(3, daughterAStartKey);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Daughter A must find parent segment", rs.next());
          assertArrayEquals(parentSegmentRowKey, rs.getBytes(1));
          assertFalse("Daughter A must not see succeeding or preceding segments", rs.next());
        }
      }

      // Daughter B split boundaries: [300, 500)
      byte[] daughterBStartKey = Bytes.toBytes("300");
      byte[] daughterBEndKey = Bytes.toBytes("500");

      try (PreparedStatement ps = conn.prepareStatement(daughterQuerySql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, daughterBEndKey);
        ps.setBytes(3, daughterBStartKey);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Daughter B must find parent segment", rs.next());
          assertArrayEquals(parentSegmentRowKey, rs.getBytes(1));
          assertFalse("Daughter B must not see succeeding or preceding segments", rs.next());
        }
      }
    }
  }

  @Test
  public void testProtobufAndMetadataRoundTrip() throws Exception {
    String tableName = "T_CAT_TEST_" + generateUniqueName();
    String indexName = uniqueIndex("IDX_CAT_TEST_");

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 96))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 96, "
        + "M = 16, ef_construction = 200, alpha = 1.2, quantization = 'PQ', pq_segments = 96)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Index table must exist in catalog", indexTable);

      PTable.VectorIndex vi = indexTable.getVectorIndex();
      assertNotNull("VectorIndex metadata must be encapsulated", vi);
      assertEquals("HNSW", vi.getAlgorithm());
      assertEquals(VectorIndexType.HNSW, vi.getType());
      assertEquals("COSINE", vi.getDistanceMetric());
      assertEquals(Integer.valueOf(96), vi.getDimension());
      assertEquals(Integer.valueOf(16), vi.getHnswM());
      assertEquals(Integer.valueOf(200), vi.getHnswEfConstruction());
      assertEquals(Double.valueOf(1.2), vi.getHnswAlpha());
      assertEquals("PQ", vi.getQuantizationType());
      assertEquals(Integer.valueOf(96), vi.getPqSegments());

      // Protobuf serialization round-trip
      PTableProtos.PTable proto = PTableImpl.toProto(indexTable);
      PTable deserialized = PTableImpl.createFromProto(proto);

      assertNotNull("Deserialized table must not be null", deserialized);
      assertTrue("Deserialized table must be a vector index", deserialized.isVectorIndex());
      PTable.VectorIndex deserializedVi = deserialized.getVectorIndex();
      assertNotNull("Deserialized VectorIndex must not be null", deserializedVi);
      assertEquals(vi, deserializedVi);
      assertEquals(Integer.valueOf(16), deserializedVi.getHnswM());
      assertEquals(Integer.valueOf(200), deserializedVi.getHnswEfConstruction());
      assertEquals(Double.valueOf(1.2), deserializedVi.getHnswAlpha());
      assertEquals("PQ", deserializedVi.getQuantizationType());
      assertEquals(Integer.valueOf(96), deserializedVi.getPqSegments());

      // SerializedPTableRef round-trip
      PTableRef pTableRef =
        new SerializedPTableRef(proto.toByteArray(), 0L, 0L, indexTable.getEstimatedSize());
      assertTrue("Must produce SerializedPTableRef", pTableRef instanceof SerializedPTableRef);
      PTable tableFromRef = pTableRef.getTable();
      assertNotNull(tableFromRef);
      assertTrue(tableFromRef.isVectorIndex());
      assertEquals(vi, tableFromRef.getVectorIndex());
      assertEquals(Integer.valueOf(16), tableFromRef.getVectorIndex().getHnswM());
      assertEquals(Integer.valueOf(200), tableFromRef.getVectorIndex().getHnswEfConstruction());
      assertEquals(Double.valueOf(1.2), tableFromRef.getVectorIndex().getHnswAlpha());
      assertEquals("PQ", tableFromRef.getVectorIndex().getQuantizationType());
      assertEquals(Integer.valueOf(96), tableFromRef.getVectorIndex().getPqSegments());
    }
  }

  @Test
  public void testCatalogMigrationColumns() throws Exception {
    Map<String, String> expectedColumns = new HashMap<>();
    expectedColumns.put(VECTOR_HNSW_M, PInteger.INSTANCE.getSqlTypeName());
    expectedColumns.put(VECTOR_HNSW_EF_CONSTRUCTION, PInteger.INSTANCE.getSqlTypeName());
    expectedColumns.put(VECTOR_HNSW_ALPHA, PDouble.INSTANCE.getSqlTypeName());
    expectedColumns.put(VECTOR_QUANTIZATION_TYPE, PVarchar.INSTANCE.getSqlTypeName());
    expectedColumns.put(VECTOR_PQ_SEGMENTS, PInteger.INSTANCE.getSqlTypeName());

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      DatabaseMetaData dbmd = conn.getMetaData();
      Map<String, String> actualColumns = new HashMap<>();
      try (
        ResultSet rs = dbmd.getColumns(null, SYSTEM_CATALOG_SCHEMA, SYSTEM_CATALOG_TABLE, null)) {
        while (rs.next()) {
          String colName = rs.getString(COLUMN_NAME);
          if (expectedColumns.containsKey(colName)) {
            actualColumns.put(colName, rs.getString("TYPE_NAME"));
          }
        }
      }
      for (Map.Entry<String, String> entry : expectedColumns.entrySet()) {
        assertTrue("Catalog must contain column: " + entry.getKey(),
          actualColumns.containsKey(entry.getKey()));
        assertEquals("Column " + entry.getKey() + " type mismatch", entry.getValue(),
          actualColumns.get(entry.getKey()));
      }
    }
  }

  @Test
  public void testHnswIndexMobAttributes() throws Exception {
    String tableName = "T_MOB_ATTR_" + generateUniqueName();
    String indexName = uniqueIndex("IDX_MOB_ATTR_");

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      byte[] familyName = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : org.apache.phoenix.query.QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        TableDescriptor td = admin.getDescriptor(
          org.apache.hadoop.hbase.TableName.valueOf(indexTable.getPhysicalName().getBytes()));
        ColumnFamilyDescriptor cfd = td.getColumnFamily(familyName);
        assertNotNull("Column family must exist on HBase table", cfd);
        assertTrue("HNSW index segment column family must have MOB enabled", cfd.isMobEnabled());
        assertEquals("HNSW index segment column family MOB threshold must be 0", 0L,
          cfd.getMobThreshold());
        assertEquals("HNSW index segment MOB compaction policy must be MONTHLY",
          MobCompactPartitionPolicy.MONTHLY, cfd.getMobCompactPartitionPolicy());
      }
    }
  }

  @Test
  public void testDropIndexCleansUpGraphSegments() throws Exception {
    String tableName = "T_DROP_CLEANUP_" + generateUniqueName();
    String indexName = uniqueIndex("IDX_DROP_CLEANUP_");

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");

      // Insert simulated graph segment for this index
      String upsertSql = "UPSERT INTO " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " (" + INDEX_NAME
        + ", " + REGION_START_KEY + ", " + GENERATION_ID + ", " + REGION_END_KEY + ", "
        + SEGMENT_ROW_KEY + ") VALUES (?, ?, ?, ?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        ps.setString(1, indexName);
        ps.setBytes(2, Bytes.toBytes("reg_start"));
        ps.setLong(3, 0L);
        ps.setBytes(4, Bytes.toBytes("reg_end"));
        ps.setBytes(5, Bytes.toBytes("mob_row_key"));
        ps.executeUpdate();
      }
      conn.commit();

      // Verify row exists
      try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + INDEX_NAME + " = ?")) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1L, rs.getLong(1));
        }
      }

      // Drop the index
      stmt.execute("DROP INDEX " + indexName + " ON " + tableName);

      // Verify row was deleted
      try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + INDEX_NAME + " = ?")) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(0L, rs.getLong(1));
        }
      }
    }
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

      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        String colName = col.getName().getString();
        assertFalse("HNSW index must not contain centroid column in columns: " + colName,
          centroidCol.equals(colName) || CENTROID_ID.equals(colName));
      }

      // Verify PK columns match base table PK columns
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

      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      for (PColumn col : indexTable.getColumns()) {
        assertFalse(
          "HNSW salted index must not contain centroid column: " + col.getName().getString(),
          centroidCol.equals(col.getName().getString())
            || CENTROID_ID.equals(col.getName().getString()));
      }

      // Verify primary key layout: [salt][base table PK column]
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

      // Verify primary key layout: [tenant][base table PK column]
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

      // Verify centroid column exists in IVF index table columns
      String centroidCol = IndexUtil.getIndexColumnName(null, CENTROID_ID);
      boolean foundCentroid = false;
      for (PColumn col : indexTable.getColumns()) {
        if (centroidCol.equals(col.getName().getString())) {
          foundCentroid = true;
          break;
        }
      }
      assertTrue("IVF index table must contain centroid column " + centroidCol, foundCentroid);

      // Verify primary key layout prepends centroid column: [centroid_id][base table PK columns]
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

      // INCLUDE clause is rejected for HNSW
      try {
        stmt.execute("CREATE VECTOR INDEX " + generateUniqueName() + " ON " + tableName + " (V) "
          + "INCLUDE (C1) WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4)");
        fail("Should have failed with HNSW_INCLUDE_NOT_SUPPORTED");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.HNSW_INCLUDE_NOT_SUPPORTED.getErrorCode(), e.getErrorCode());
        assertEquals(SQLExceptionCode.HNSW_INCLUDE_NOT_SUPPORTED.getSQLState(), e.getSQLState());
      }

      // Verify INCLUDE remains valid for IVF indexes
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

      // Verify direct query on SYSTEM.CATALOG
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

      // Clear cache and reload table from SYSTEM.CATALOG
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
  public void testServerSideValidationOnGetTableAndDropTable() throws Exception {
    String tableName = "T_SRV_VAL_" + generateUniqueName();
    String indexName = "IDX_SRV_VAL_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, M = 16)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);

      // Verify corrupting VECTOR_HNSW_M directly in SYSTEM.CATALOG is caught by getTable
      String corruptSql =
        "UPSERT INTO SYSTEM.CATALOG (TENANT_ID, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, COLUMN_FAMILY, VECTOR_HNSW_M) "
          + "VALUES (NULL, NULL, ?, NULL, NULL, 100)";
      try (PreparedStatement ps = conn.prepareStatement(corruptSql)) {
        ps.setString(1, indexName);
        ps.executeUpdate();
      }
      conn.commit();

      pconn.getQueryServices().clearCache();

      try {
        pconn.getTable(new PTableKey(null, indexName));
        fail("Server-side getTable must fail with INVALID_VECTOR_INDEX_PARAMS for corrupted M=100");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
      }

      // Verify dropTable succeeds with corrupted metadata
      stmt.execute("DROP INDEX " + indexName + " ON " + tableName);
    }
  }

  @Test
  public void testServerSideCrossContaminationValidation() throws Exception {
    String tableName = "T_SRV_CROSS_" + generateUniqueName();
    String indexName = "IDX_SRV_CROSS_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, M = 16)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);

      // Corrupt catalog by adding IVF lists to an HNSW index
      String corruptSql =
        "UPSERT INTO SYSTEM.CATALOG (TENANT_ID, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, COLUMN_FAMILY, VECTOR_IVF_LISTS) "
          + "VALUES (NULL, NULL, ?, NULL, NULL, 10)";
      try (PreparedStatement ps = conn.prepareStatement(corruptSql)) {
        ps.setString(1, indexName);
        ps.executeUpdate();
      }
      conn.commit();

      pconn.getQueryServices().clearCache();

      try {
        pconn.getTable(new PTableKey(null, indexName));
        fail(
          "Server-side getTable must fail with VECTOR_ALGORITHM_PARAM_MISMATCH for HNSW with IVF lists");
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
          e.getErrorCode());
      }
    }
  }
}
