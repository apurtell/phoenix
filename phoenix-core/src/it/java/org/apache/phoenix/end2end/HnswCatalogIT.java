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
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableRef;
import org.apache.phoenix.schema.SerializedPTableRef;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDouble;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PVarchar;
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

      // 2. REGION_START_KEY VARBINARY_ENCODED NOT NULL
      assertEquals(REGION_START_KEY, rsmd.getColumnName(2));
      assertEquals(PDataType.VARBINARY_ENCODED_TYPE, rsmd.getColumnType(2));
      assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(2));

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
}
