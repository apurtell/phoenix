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

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SYSTEM_VECTOR_GRAPH_SEGMENT_NAME;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.jbellis.jvector.graph.NodesIterator;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.disk.feature.FeatureId;
import io.github.jbellis.jvector.graph.disk.feature.FusedPQ;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.MobCompactPartitionPolicy;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.hbase.index.IndexRegionObserver;
import org.apache.phoenix.hbase.index.vector.HnswIndexManager;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.mapreduce.index.IndexTool;
import org.apache.phoenix.mapreduce.vector.HnswGraphBuildMapper;
import org.apache.phoenix.mapreduce.vector.HnswPqCodebookTrainer;
import org.apache.phoenix.query.ConnectionQueryServices.Feature;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for HNSW graph-based vector index DDL compilation, schema generation, IndexTool
 * initial bulk population, segment metadata recording, transparent MOB storage, and search recall.
 */
@Category(ParallelStatsDisabledTest.class)
public class HnswIndexIT extends ParallelStatsDisabledIT {

  private static final Logger LOGGER = LoggerFactory.getLogger(HnswIndexIT.class);

  private static final VectorTypeSupport VTS =
    VectorizationProvider.getInstance().getVectorTypeSupport();

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    setUpTestDriver(ReadOnlyProps.EMPTY_PROPS);
  }

  @After
  public void resetVectorState() {
    VectorIndexTestUtil.resetSharedVectorState();
  }

  private int runIndexTool(String dataTable, String indexTable, IndexTool[] capturedTool)
    throws Exception {
    return runIndexTool(dataTable, indexTable, capturedTool, null);
  }

  private int runIndexTool(String dataTable, String indexTable, IndexTool[] capturedTool,
    String[] capturedOutputPath) throws Exception {
    IndexTool indexingTool = new IndexTool();
    Configuration conf = new Configuration(getUtility().getConfiguration());
    indexingTool.setConf(conf);
    String outputPath =
      getUtility().getDataTestDirOnTestFS().toString() + "/it_" + generateUniqueName();
    if (capturedOutputPath != null && capturedOutputPath.length > 0) {
      capturedOutputPath[0] = outputPath;
    }
    String[] args =
      new String[] { "-dt", dataTable, "-it", indexTable, "-op", outputPath, "-runfg" };
    int status = indexingTool.run(args);
    if (capturedTool != null && capturedTool.length > 0) {
      capturedTool[0] = indexingTool;
    }
    return status;
  }

  private void upsertVector(Connection conn, String tableName, int id, float[] vec)
    throws SQLException {
    String sql = "UPSERT INTO " + tableName + " (ID, V) VALUES (?, ?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, id);
      Float[] boxed = new Float[vec.length];
      for (int i = 0; i < vec.length; i++) {
        boxed[i] = vec[i];
      }
      ps.setArray(2, conn.createArrayOf("FLOAT", boxed));
      ps.executeUpdate();
    }
  }

  private void deleteRow(Connection conn, String tableName, int id) throws SQLException {
    String sql = "DELETE FROM " + tableName + " WHERE ID = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, id);
      ps.executeUpdate();
    }
  }

  /** Resolves a search result's nodes to base-table ID values. */
  private List<Integer> resolveResultIds(HnswIndexManager mgr, SearchResult sr, String what) {
    assertNotNull(what + " must return a non-null result", sr);
    assertNotNull(what + " must return a non-null node array", sr.getNodes());
    assertTrue(what + " must return at least one node", sr.getNodes().length > 0);
    List<Integer> ids = new ArrayList<>();
    for (SearchResult.NodeScore ns : sr.getNodes()) {
      byte[] rk = mgr.getRowKeyForOrdinal(ns.node);
      if (rk != null) {
        ids.add((Integer) PInteger.INSTANCE.toObject(rk));
      }
    }
    assertFalse(what + " must resolve at least one node to a row key", ids.isEmpty());
    return ids;
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

  @Test
  public void testHnswIndexColumnFamilyMobConfiguration() throws Exception {
    String tableName = "T_HNSW_MOB_" + generateUniqueName();
    String indexName = "IDX_HNSW_MOB_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4, M = 16)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Index table must exist in client catalog", indexTable);

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        TableDescriptor desc = admin.getDescriptor(
          org.apache.hadoop.hbase.TableName.valueOf(indexTable.getPhysicalName().getBytes()));
        assertNotNull("HBase TableDescriptor must exist", desc);

        byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
          ? indexTable.getDefaultFamilyName().getBytes()
          : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
        ColumnFamilyDescriptor cfd = desc.getColumnFamily(segmentFamily);
        assertNotNull("Segment column family descriptor must exist", cfd);
        assertTrue("HNSW index segment column family must have MOB enabled", cfd.isMobEnabled());
        assertEquals("HNSW index segment column family must have MOB threshold 0", 0L,
          cfd.getMobThreshold());
        assertEquals("HNSW index segment column family must have monthly compact partition policy",
          MobCompactPartitionPolicy.MONTHLY, cfd.getMobCompactPartitionPolicy());
      }
    }
  }

  @Test
  public void testHnswIndexColumnFamilyMobConfigurationAsync() throws Exception {
    String tableName = "T_HNSW_MOB_ASYNC_" + generateUniqueName();
    String indexName = "IDX_HNSW_MOB_ASYNC_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute(
        "CREATE TABLE " + tableName + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4))");
      stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) ASYNC "
        + "WITH (algorithm = 'HNSW', metric = 'L2', dimension = 4, M = 32)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTable(new PTableKey(null, indexName));
      assertNotNull("Async index table must exist in client catalog", indexTable);

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        TableDescriptor desc = admin.getDescriptor(
          org.apache.hadoop.hbase.TableName.valueOf(indexTable.getPhysicalName().getBytes()));
        assertNotNull("HBase TableDescriptor must exist", desc);

        byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
          ? indexTable.getDefaultFamilyName().getBytes()
          : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
        ColumnFamilyDescriptor cfd = desc.getColumnFamily(segmentFamily);
        assertNotNull("Segment column family descriptor must exist", cfd);
        assertTrue("Async HNSW index segment column family must have MOB enabled",
          cfd.isMobEnabled());
        assertEquals("Async HNSW index segment column family must have MOB threshold 0", 0L,
          cfd.getMobThreshold());
        assertEquals(
          "Async HNSW index segment column family must have monthly compact partition policy",
          MobCompactPartitionPolicy.MONTHLY, cfd.getMobCompactPartitionPolicy());
      }
    }
  }

  @Test
  public void testNonHnswIndexDoesNotEnableMob() throws Exception {
    String tableName = "T_NON_HNSW_" + generateUniqueName();
    String stdIndexName = "IDX_STD_" + generateUniqueName();
    String ivfIndexName = "IDX_IVF_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName
        + " (ID VARCHAR NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4), K VARCHAR)");
      stmt.execute("CREATE INDEX " + stdIndexName + " ON " + tableName + " (K)");
      stmt.execute("CREATE VECTOR INDEX " + ivfIndexName + " ON " + tableName + " (V) "
        + "WITH (algorithm = 'IVF', metric = 'L2', dimension = 4, lists = 4, sample_size = 100)");

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        PTable stdTable = pconn.getTable(new PTableKey(null, stdIndexName));
        TableDescriptor stdDesc = admin.getDescriptor(
          org.apache.hadoop.hbase.TableName.valueOf(stdTable.getPhysicalName().getBytes()));
        byte[] stdFamily = stdTable.getDefaultFamilyName() != null
          ? stdTable.getDefaultFamilyName().getBytes()
          : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
        ColumnFamilyDescriptor stdCfd = stdDesc.getColumnFamily(stdFamily);
        assertNotNull(stdCfd);
        assertFalse("Standard secondary index must not have MOB enabled", stdCfd.isMobEnabled());

        PTable ivfTable = pconn.getTable(new PTableKey(null, ivfIndexName));
        TableDescriptor ivfDesc = admin.getDescriptor(
          org.apache.hadoop.hbase.TableName.valueOf(ivfTable.getPhysicalName().getBytes()));
        byte[] ivfFamily = ivfTable.getDefaultFamilyName() != null
          ? ivfTable.getDefaultFamilyName().getBytes()
          : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
        ColumnFamilyDescriptor ivfCfd = ivfDesc.getColumnFamily(ivfFamily);
        assertNotNull(ivfCfd);
        assertFalse("IVF vector index must not have MOB enabled", ivfCfd.isMobEnabled());
      }
    }
  }

  @Test
  public void testBulkBuildSegmentsAndRecall() throws Exception {
    String tableName = "T_HNSW_BULK_" + generateUniqueName();
    String indexName = "IDX_HNSW_BULK_" + generateUniqueName();
    int numVectors = 1000;
    int dimension = 128;
    int k = 10;
    int efSearch = 64;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, "
            + dimension + ")) " + "SPLIT ON (250, 500, 750)");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);
      assertEquals(numVectors, allVectors.size());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      // Verify IndexTool completed with HnswGraphBuildMapper
      IndexTool[] capturedTool = new IndexTool[1];
      int status = runIndexTool(tableName, indexName, capturedTool);
      assertEquals("IndexTool job must succeed", 0, status);
      assertNotNull(capturedTool[0]);
      assertEquals("Mapper class must be HnswGraphBuildMapper", HnswGraphBuildMapper.class,
        capturedTool[0].getJob().getMapperClass());

      // Verify index state is ACTIVE
      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals("Index state must be ACTIVE", PIndexState.ACTIVE, indexTable.getIndexState());

      // Verify segment metadata in SYSTEM.VECTOR_GRAPH_SEGMENT
      String querySeg =
        "SELECT REGION_START_KEY, SEGMENT_ROW_KEY, GENERATION_ID, REBUILD_STATE, NODE_COUNT "
          + "FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE "
          + PhoenixDatabaseMetaData.INDEX_NAME + " = ?";
      List<byte[]> segmentStartKeys = new ArrayList<>();
      List<byte[]> segmentRowKeys = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          int segmentCount = 0;
          int totalNodes = 0;
          while (rs.next()) {
            segmentCount++;
            byte[] startKey = rs.getBytes("REGION_START_KEY");
            segmentStartKeys.add(startKey != null ? startKey : HConstants.EMPTY_BYTE_ARRAY);
            segmentRowKeys.add(rs.getBytes("SEGMENT_ROW_KEY"));
            assertEquals("GENERATION_ID must be 0", 0L, rs.getLong("GENERATION_ID"));
            assertEquals("REBUILD_STATE must be C", "C", rs.getString("REBUILD_STATE"));
            totalNodes += rs.getInt("NODE_COUNT");
          }
          assertEquals("Must have exactly 4 segment rows for 4 regions", 4, segmentCount);
          assertEquals("Total nodes across segments must equal 1000", 1000, totalNodes);
        }
      }

      // Verify segment region start keys match base table regions
      PTable dataTable = pconn.getTableNoCache(tableName);
      List<byte[]> baseRegionStartKeys = new ArrayList<>();
      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        List<RegionInfo> regions =
          admin.getRegions(TableName.valueOf(dataTable.getPhysicalName().getBytes()));
        assertEquals(4, regions.size());
        for (RegionInfo ri : regions) {
          baseRegionStartKeys.add(ri.getStartKey());
        }
      }
      assertEquals("Segment count must equal non-empty base table region count",
        baseRegionStartKeys.size(), segmentStartKeys.size());
      for (byte[] segStartKey : segmentStartKeys) {
        boolean matchFound = false;
        for (byte[] regStartKey : baseRegionStartKeys) {
          if (Bytes.equals(segStartKey, regStartKey)) {
            matchFound = true;
            break;
          }
        }
        assertTrue("Segment REGION_START_KEY must match a base table region boundary: "
          + Bytes.toStringBinary(segStartKey), matchFound);
      }

      // Verify recall across segments
      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
      Configuration conf = pconn.getQueryServices().getConfiguration();

      List<HnswIndexManager> managers = new ArrayList<>();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        for (byte[] segRowKey : segmentRowKeys) {
          HnswIndexManager mgr = new HnswIndexManager(indexName, segRowKey, conf, dimension,
            VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
            HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
          mgr.open();
          mgr.loadSegment(hIndexTable, segRowKey);
          assertNotNull("Segment must be loaded", mgr.getOnDiskGraphIndex());
          managers.add(mgr);
        }

        int[] queryIndices = new int[] { 42, 187, 350, 620, 899 };
        double totalRecall = 0.0;

        for (int queryIdx : queryIndices) {
          float[] query = allVectors.get(String.valueOf(queryIdx));
          assertNotNull("Query vector must exist", query);

          // Brute force top-k baseline
          List<String> expectedTopK =
            VectorIndexTestUtil.bruteForceTopK(allVectors, query, "COSINE", k);
          assertEquals(k, expectedTopK.size());

          // Query each region's HNSW segment
          VectorFloat<?> qVec = VTS.createFloatVector(query);
          List<Map.Entry<String, Double>> candidates = new ArrayList<>();

          for (HnswIndexManager mgr : managers) {
            SearchResult sr = mgr.search(qVec, Math.max(k * 2, 20), efSearch);
            if (sr != null && sr.getNodes() != null) {
              for (SearchResult.NodeScore ns : sr.getNodes()) {
                byte[] pkBytes = mgr.getRowKeyForOrdinal(ns.node);
                assertNotNull("Primary key must not be null for ordinal " + ns.node, pkBytes);
                int id = (Integer) PInteger.INSTANCE.toObject(pkBytes);
                String idStr = String.valueOf(id);
                double dist = VectorIndexTestUtil.dist("COSINE", query, allVectors.get(idStr));
                candidates.add(new AbstractMap.SimpleEntry<>(idStr, dist));
              }
            }
          }

          // Sort candidates by exact distance ascending
          candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
          List<String> actualTopK = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          for (Map.Entry<String, Double> entry : candidates) {
            if (seen.add(entry.getKey())) {
              actualTopK.add(entry.getKey());
              if (actualTopK.size() == k) {
                break;
              }
            }
          }

          int hits = 0;
          Set<String> expectedSet = new HashSet<>(expectedTopK);
          for (String act : actualTopK) {
            if (expectedSet.contains(act)) {
              hits++;
            }
          }
          double recall = (double) hits / k;
          totalRecall += recall;
        }

        double avgRecall = totalRecall / queryIndices.length;
        LOGGER.info("Average recall (unquantized) = {}", avgRecall);
        assertTrue("Average recall across queries must be >= 0.95, got " + avgRecall,
          avgRecall >= 0.95);
      } finally {
        for (HnswIndexManager mgr : managers) {
          try {
            mgr.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  @Test
  public void testIncrementalMutationVisibilityAndFlushCutover() throws Exception {
    String tableName = "T_HNSW_MUT_FLUSH_" + generateUniqueName();
    String indexName = "IDX_HNSW_MUT_FLUSH_" + generateUniqueName();
    int dimension = 16;
    int initialVectors = 60;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, " + dimension + "))");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, initialVectors, dimension);
      assertEquals(initialVectors, allVectors.size());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals("IndexTool job must succeed", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable dataTable = pconn.getTableNoCache(tableName);

      // Resolve region-side manager through coprocessor
      List<HRegion> regions = getUtility().getHBaseCluster()
        .getRegions(TableName.valueOf(dataTable.getPhysicalName().getBytes()));
      assertEquals("Single region fixture must have exactly 1 region", 1, regions.size());
      IndexRegionObserver iro = (IndexRegionObserver) regions.get(0).getCoprocessorHost()
        .findCoprocessor(IndexRegionObserver.class.getName());
      assertNotNull("IndexRegionObserver must be present on region", iro);

      HnswIndexManager regionMgr = iro.getHnswIndexManager();
      assertNotNull("Region HnswIndexManager must not be null", regionMgr);
      assertTrue("Region HnswIndexManager must be initialized", regionMgr.isInitialized());
      assertNotNull("On-disk graph index must be present", regionMgr.getOnDiskGraphIndex());
      assertEquals(initialVectors, regionMgr.getOnDiskGraphIndex().size());
      int baseline = regionMgr.getLiveNodeCount();
      assertEquals("Baseline live node count should equal initial vector count", initialVectors,
        baseline);

      // Verify insert visibility before flush
      float[] v1000 = new float[dimension];
      v1000[0] = 1.0f;
      float[] v1001 = new float[dimension];
      v1001[1] = 1.0f;
      float[] v1002 = new float[dimension];
      v1002[2] = 1.0f;

      upsertVector(conn, tableName, 1000, v1000);
      upsertVector(conn, tableName, 1001, v1001);
      upsertVector(conn, tableName, 1002, v1002);
      conn.commit();

      byte[] pk1000 = PInteger.INSTANCE.toBytes(1000);
      byte[] pk1001 = PInteger.INSTANCE.toBytes(1001);
      byte[] pk1002 = PInteger.INSTANCE.toBytes(1002);

      assertNotNull("Ordinal must be non-null for id 1000", regionMgr.getOrdinalForRowKey(pk1000));
      assertNotNull("Ordinal must be non-null for id 1001", regionMgr.getOrdinalForRowKey(pk1001));
      assertNotNull("Ordinal must be non-null for id 1002", regionMgr.getOrdinalForRowKey(pk1002));
      assertEquals("Live node count must increase by 3", baseline + 3,
        regionMgr.getLiveNodeCount());

      SearchResult sr1000 = regionMgr.searchMutable(VTS.createFloatVector(v1000), 1);
      assertNotNull("searchMutable result must not be null", sr1000);
      assertTrue("searchMutable must return at least 1 node",
        sr1000.getNodes() != null && sr1000.getNodes().length > 0);
      byte[] rk1000 = regionMgr.getRowKeyForOrdinal(sr1000.getNodes()[0].node);
      assertNotNull("Matched row key must not be null", rk1000);
      assertEquals(1000, (int) PInteger.INSTANCE.toObject(rk1000));
      assertEquals("Mutable buffer must contain 3 rows", 3, regionMgr.getMutableNodeCount());

      // Verify update replaces earlier embedding
      int oldOrd1000 = regionMgr.getOrdinalForRowKey(pk1000);
      float[] v1000Updated = new float[dimension];
      v1000Updated[3] = 1.0f; // orthogonal to v1000[0]=1.0
      upsertVector(conn, tableName, 1000, v1000Updated);
      conn.commit();

      Integer newOrd1000 = regionMgr.getOrdinalForRowKey(pk1000);
      assertNotNull("Updated row key must resolve to ordinal", newOrd1000);
      assertTrue("Update must assign a different ordinal", oldOrd1000 != newOrd1000.intValue());

      SearchResult srUpdated = regionMgr.searchMutable(VTS.createFloatVector(v1000Updated), 1);
      assertNotNull(srUpdated);
      assertTrue(srUpdated.getNodes() != null && srUpdated.getNodes().length > 0);
      byte[] rkUpdated = regionMgr.getRowKeyForOrdinal(srUpdated.getNodes()[0].node);
      assertNotNull(rkUpdated);
      assertEquals(1000, (int) PInteger.INSTANCE.toObject(rkUpdated));

      List<Integer> origIds =
        resolveResultIds(regionMgr, regionMgr.searchMutable(VTS.createFloatVector(v1000), 1),
          "searchMutable on id 1000's original vector");
      assertNotEquals("id 1000 must no longer rank first for its original vector",
        Integer.valueOf(1000), origIds.get(0));
      assertEquals("Live node count must be unchanged after update", baseline + 3,
        regionMgr.getLiveNodeCount());

      // Verify delete removes vector
      deleteRow(conn, tableName, 1001);
      conn.commit();

      assertNull("Deleted row key must have null ordinal", regionMgr.getOrdinalForRowKey(pk1001));
      assertEquals("Live node count must reflect delete", baseline + 2,
        regionMgr.getLiveNodeCount());

      List<Integer> delIds =
        resolveResultIds(regionMgr, regionMgr.searchMutable(VTS.createFloatVector(v1001), 3),
          "searchMutable on deleted id 1001's vector");
      assertFalse("Deleted id 1001 must not be returned in mutable search results: " + delIds,
        delIds.contains(1001));

      // Capture pre-flush recall for 5 queries
      int[] queryIds = new int[] { 5, 15, 25, 1000, 1002 };
      Map<Integer, byte[]> preFlushTop1 = new HashMap<>();
      for (int qId : queryIds) {
        float[] qVec =
          qId == 1000 ? v1000Updated : (qId == 1002 ? v1002 : allVectors.get(String.valueOf(qId)));
        assertNotNull("Query vector must exist for id " + qId, qVec);
        SearchResult sr = regionMgr.search(VTS.createFloatVector(qVec), 1);
        assertNotNull("Pre-flush search result must not be null", sr);
        assertTrue("Pre-flush search must return top-1",
          sr.getNodes() != null && sr.getNodes().length > 0);
        byte[] topKey = regionMgr.getRowKeyForOrdinal(sr.getNodes()[0].node);
        assertNotNull("Top key must not be null for query id " + qId, topKey);
        preFlushTop1.put(qId, topKey);
      }

      assertTrue("flush must report work done", regionMgr.flush());

      assertEquals("Mutable buffer must be cleared after flush", 0,
        regionMgr.getMutableNodeCount());

      String querySeg = "SELECT GENERATION_ID, NODE_COUNT FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME
        + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME + " = ? AND "
        + PhoenixDatabaseMetaData.GENERATION_ID + " = 1";
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Generation 1 segment row must exist in SYSTEM.VECTOR_GRAPH_SEGMENT",
            rs.next());
          assertEquals("Node count in segment metadata must be 62", 62L, rs.getLong("NODE_COUNT"));
          assertFalse("Must have only one generation 1 segment row", rs.next());
        }
      }

      assertNotNull("OnDiskGraphIndex must be present after flush",
        regionMgr.getOnDiskGraphIndex());
      assertEquals("Merged on-disk graph size must be 62 (60 baseline + 3 new - 1 deleted)", 62,
        regionMgr.getOnDiskGraphIndex().size());

      // Verify recall continuity after flush cutover
      for (int qId : queryIds) {
        float[] qVec =
          qId == 1000 ? v1000Updated : (qId == 1002 ? v1002 : allVectors.get(String.valueOf(qId)));
        SearchResult sr = regionMgr.search(VTS.createFloatVector(qVec), 1);
        assertNotNull("Post-flush search result must not be null", sr);
        assertTrue("Post-flush search must return top-1",
          sr.getNodes() != null && sr.getNodes().length > 0);
        byte[] postFlushKey = regionMgr.getRowKeyForOrdinal(sr.getNodes()[0].node);
        assertNotNull("Post-flush top key must not be null for query id " + qId, postFlushKey);
        assertArrayEquals("Top-1 result for query id " + qId + " must match across flush cutover",
          preFlushTop1.get(qId), postFlushKey);
      }

      assertNull("Deleted id 1001 ordinal must remain null after flush cutover",
        regionMgr.getOrdinalForRowKey(pk1001));

      List<Integer> delIdsAfter =
        resolveResultIds(regionMgr, regionMgr.search(VTS.createFloatVector(v1001), 5),
          "post-flush search on deleted id 1001's vector");
      assertFalse(
        "Deleted id 1001 must not be in search results after the flush cutover: " + delIdsAfter,
        delIdsAfter.contains(1001));
    }
  }

  /**
   * Tests crash-recovery catch-up replay across region close/reopen.
   */
  @Test
  public void testCrashRecoveryCatchUpReplay() throws Exception {
    String tableName = "T_HNSW_RECOV_" + generateUniqueName();
    String indexName = "IDX_HNSW_RECOV_" + generateUniqueName();
    int dimension = 16;
    int initialVectors = 60;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, " + dimension + "))");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, initialVectors, dimension);
      assertEquals(initialVectors, allVectors.size());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals("IndexTool job must succeed", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable dataTable = pconn.getTableNoCache(tableName);

      // Record construction time boundary
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.CONSTRUCTION_TIME + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ? AND " + PhoenixDatabaseMetaData.GENERATION_ID + " = 0";
      long constructionTime = 0L;
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Generation 0 segment row must exist", rs.next());
          constructionTime = rs.getLong(1);
          assertTrue("CONSTRUCTION_TIME must be > 0, got " + constructionTime,
            constructionTime > 0);
        }
      }

      // Create un-flushed state
      List<HRegion> regions = getUtility().getHBaseCluster()
        .getRegions(TableName.valueOf(dataTable.getPhysicalName().getBytes()));
      assertEquals(1, regions.size());
      IndexRegionObserver oldIro = (IndexRegionObserver) regions.get(0).getCoprocessorHost()
        .findCoprocessor(IndexRegionObserver.class.getName());
      assertNotNull("IndexRegionObserver must be present", oldIro);
      HnswIndexManager oldRegionMgr = oldIro.getHnswIndexManager();
      assertNotNull("Old HnswIndexManager must not be null", oldRegionMgr);

      int baseline = oldRegionMgr.getLiveNodeCount();
      assertEquals(initialVectors, baseline);

      float[] v2000 = new float[dimension];
      v2000[0] = 0.9f;
      v2000[1] = 0.1f;
      float[] v2001 = new float[dimension];
      v2001[2] = 0.9f;
      v2001[3] = 0.1f;
      float[] v2002 = new float[dimension];
      v2002[4] = 0.9f;
      v2002[5] = 0.1f;
      float[] v2003 = new float[dimension];
      v2003[6] = 0.9f;
      v2003[7] = 0.1f;

      upsertVector(conn, tableName, 2000, v2000);
      upsertVector(conn, tableName, 2001, v2001);
      upsertVector(conn, tableName, 2002, v2002);
      upsertVector(conn, tableName, 2003, v2003);
      deleteRow(conn, tableName, 10);
      conn.commit();

      byte[] pk10 = PInteger.INSTANCE.toBytes(10);
      byte[] pk2000 = PInteger.INSTANCE.toBytes(2000);
      byte[] pk2001 = PInteger.INSTANCE.toBytes(2001);
      byte[] pk2002 = PInteger.INSTANCE.toBytes(2002);
      byte[] pk2003 = PInteger.INSTANCE.toBytes(2003);

      assertNull("Deleted id 10 must have null ordinal before crash",
        oldRegionMgr.getOrdinalForRowKey(pk10));
      assertNotNull("Id 2000 must resolve ordinal before crash",
        oldRegionMgr.getOrdinalForRowKey(pk2000));
      assertNotNull("Id 2001 must resolve ordinal before crash",
        oldRegionMgr.getOrdinalForRowKey(pk2001));
      assertNotNull("Id 2002 must resolve ordinal before crash",
        oldRegionMgr.getOrdinalForRowKey(pk2002));
      assertNotNull("Id 2003 must resolve ordinal before crash",
        oldRegionMgr.getOrdinalForRowKey(pk2003));
      assertEquals("Live node count must be baseline + 4 - 1 = 63 before crash", baseline + 3,
        oldRegionMgr.getLiveNodeCount());

      // Close and reopen the region to simulate crash
      TableName hbaseTableName = TableName.valueOf(dataTable.getPhysicalName().getBytes());
      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        List<RegionInfo> tableRegions = admin.getRegions(hbaseTableName);
        assertEquals(1, tableRegions.size());
        RegionInfo ri = tableRegions.get(0);
        // Wait for unassign to complete before reassigning
        admin.unassign(ri.getEncodedNameAsBytes(), true);
        long closeDeadline = System.currentTimeMillis() + 60000L;
        while (
          System.currentTimeMillis() < closeDeadline
            && !getUtility().getHBaseCluster().getRegions(hbaseTableName).isEmpty()
        ) {
          Thread.sleep(100);
        }
        assertTrue("Timed out waiting for the region to close",
          getUtility().getHBaseCluster().getRegions(hbaseTableName).isEmpty());
        admin.assign(ri.getEncodedNameAsBytes());
      }

      IndexRegionObserver newIro = null;
      long deadline = System.currentTimeMillis() + 60000L;
      while (System.currentTimeMillis() < deadline) {
        List<HRegion> onlineRegions = getUtility().getHBaseCluster().getRegions(hbaseTableName);
        if (!onlineRegions.isEmpty()) {
          HRegion r = onlineRegions.get(0);
          if (r.isAvailable()) {
            IndexRegionObserver candidate = (IndexRegionObserver) r.getCoprocessorHost()
              .findCoprocessor(IndexRegionObserver.class.getName());
            if (candidate != null && candidate != oldIro) {
              newIro = candidate;
              break;
            }
          }
        }
        Thread.sleep(100);
      }
      assertNotNull(
        "Timed out waiting for region to reopen with a new IndexRegionObserver instance", newIro);

      // Verify replay
      HnswIndexManager newRegionMgr = newIro.getHnswIndexManager();
      assertNotNull("New HnswIndexManager must not be null", newRegionMgr);
      assertTrue("New HnswIndexManager must be initialized", newRegionMgr.isInitialized());
      assertNotNull("On-disk graph index must be reloaded", newRegionMgr.getOnDiskGraphIndex());
      assertEquals(initialVectors, newRegionMgr.getOnDiskGraphIndex().size());

      assertTrue("getLastRecoveredRows() must be > 0, was " + newRegionMgr.getLastRecoveredRows(),
        newRegionMgr.getLastRecoveredRows() > 0);
      assertTrue(
        "getLastRecoveredUpserts() must be >= 4, was " + newRegionMgr.getLastRecoveredUpserts(),
        newRegionMgr.getLastRecoveredUpserts() >= 4);

      int[] unFlushedIds = new int[] { 2000, 2001, 2002, 2003 };
      float[][] unFlushedVectors = new float[][] { v2000, v2001, v2002, v2003 };
      for (int i = 0; i < unFlushedIds.length; i++) {
        int uId = unFlushedIds[i];
        float[] uVec = unFlushedVectors[i];
        byte[] uPk = PInteger.INSTANCE.toBytes(uId);
        assertNotNull("Un-flushed id " + uId + " must resolve via getOrdinalForRowKey",
          newRegionMgr.getOrdinalForRowKey(uPk));

        SearchResult sr = newRegionMgr.searchMutable(VTS.createFloatVector(uVec), 1);
        assertNotNull("searchMutable result for id " + uId + " must not be null", sr);
        assertTrue("searchMutable must return at least 1 node",
          sr.getNodes() != null && sr.getNodes().length > 0);
        byte[] retKey = newRegionMgr.getRowKeyForOrdinal(sr.getNodes()[0].node);
        assertNotNull("Matched row key must not be null for id " + uId, retKey);
        assertEquals("searchMutable must return id " + uId, uId,
          (int) PInteger.INSTANCE.toObject(retKey));
      }

      assertNull("Deleted id 10 must still be absent", newRegionMgr.getOrdinalForRowKey(pk10));
      assertTrue(
        "getLastRecoveredDeletes() must be >= 1, was " + newRegionMgr.getLastRecoveredDeletes(),
        newRegionMgr.getLastRecoveredDeletes() >= 1);

      assertTrue(
        "Mutable node count must be well below 60 (~4), was " + newRegionMgr.getMutableNodeCount(),
        newRegionMgr.getMutableNodeCount() < 20);
    }
  }

  @Test
  public void testSegmentMobStorage() throws Exception {
    String tableName = "T_HNSW_MOBSTORE_" + generateUniqueName();
    String indexName = "IDX_HNSW_MOBSTORE_" + generateUniqueName();
    int dimension = 8;
    int numVectors = 40;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, " + dimension + "))");
      }

      VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals(0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTableNoCache(indexName);

      // 1. Inspect HBase TableDescriptor for MOB settings
      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        TableDescriptor desc =
          admin.getDescriptor(TableName.valueOf(indexTable.getPhysicalName().getBytes()));
        assertNotNull("HBase TableDescriptor must exist", desc);

        byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
          ? indexTable.getDefaultFamilyName().getBytes()
          : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
        ColumnFamilyDescriptor cfd = desc.getColumnFamily(segmentFamily);
        assertNotNull("Segment column family descriptor must exist", cfd);
        assertTrue("HNSW index column family must have MOB enabled", cfd.isMobEnabled());
        assertEquals("HNSW index column family must have MOB threshold 0", 0L,
          cfd.getMobThreshold());
      }

      // 2. Query SYSTEM.VECTOR_GRAPH_SEGMENT for segment row key
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ?";
      byte[] segmentRowKey = null;
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          segmentRowKey = rs.getBytes(1);
        }
      }
      assertNotNull(segmentRowKey);

      // 3. Retrieve segment cell via direct HBase Get
      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
      try (
        Table hTable = pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        Get get = new Get(segmentRowKey);
        get.addFamily(segmentFamily);
        Result r = hTable.get(get);
        assertFalse("HBase Get result must not be empty", r.isEmpty());
        byte[] payload = r.getValue(segmentFamily, HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
        assertNotNull("Segment MOB cell payload must not be null", payload);
        assertTrue("Segment MOB cell payload must be non-empty", payload.length > 0);

        // Verify payload splits into valid graph and mapping
        Pair<byte[], Map<Integer, byte[]>> split = HnswIndexManager.splitSegmentAndMapping(payload);
        assertNotNull("Graph bytes must not be null", split.getFirst());
        assertTrue("Graph bytes must be non-empty", split.getFirst().length > 0);
        assertNotNull("Mapping must not be null", split.getSecond());
        assertEquals("Mapping count must equal numVectors", numVectors, split.getSecond().size());
      }
    }
  }

  @Test
  public void testIdempotentRebuild() throws Exception {
    String tableName = "T_HNSW_IDEMP_" + generateUniqueName();
    String indexName = "IDX_HNSW_IDEMP_" + generateUniqueName();
    int numVectors = 200;
    int dimension = 16;
    int k = 5;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, "
            + dimension + ")) SPLIT ON (100)");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      // First run
      int status1 = runIndexTool(tableName, indexName, null);
      assertEquals(0, status1);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable1 = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable1.getIndexState());

      // Query initial construction times
      String querySeg =
        "SELECT REGION_START_KEY, CONSTRUCTION_TIME FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME
          + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME + " = ? AND GENERATION_ID = 0";
      Map<String, Long> initialTimes = new java.util.HashMap<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            byte[] rsk = rs.getBytes(1);
            initialTimes.put(Bytes.toStringBinary(rsk != null ? rsk : HConstants.EMPTY_BYTE_ARRAY),
              rs.getLong(2));
          }
        }
      }
      assertEquals(2, initialTimes.size());

      Thread.sleep(50);

      // Second run: rebuild should succeed and overwrite metadata
      int status2 = runIndexTool(tableName, indexName, null);
      assertEquals("Second IndexTool run must succeed", 0, status2);

      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable2 = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable2.getIndexState());

      // Verify metadata is intact and updated
      Map<String, Long> reloadedTimes = new java.util.HashMap<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            byte[] rsk = rs.getBytes(1);
            reloadedTimes.put(Bytes.toStringBinary(rsk != null ? rsk : HConstants.EMPTY_BYTE_ARRAY),
              rs.getLong(2));
          }
        }
      }
      assertEquals(2, reloadedTimes.size());
      for (Map.Entry<String, Long> entry : reloadedTimes.entrySet()) {
        Long initialTs = initialTimes.get(entry.getKey());
        assertNotNull(initialTs);
        assertTrue("Construction time should be updated on rebuild: " + entry.getValue() + " >= "
          + initialTs, entry.getValue() >= initialTs);
      }

      // Verify search recall after second build
      byte[] segmentFamily = indexTable2.getDefaultFamilyName() != null
        ? indexTable2.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
      Configuration conf = pconn.getQueryServices().getConfiguration();

      String queryRowKeys = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ? AND GENERATION_ID = 0";
      List<byte[]> segmentRowKeys = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(queryRowKeys)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            segmentRowKeys.add(rs.getBytes(1));
          }
        }
      }

      List<HnswIndexManager> managers = new ArrayList<>();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable2.getPhysicalName().getBytes())) {
        for (byte[] segRowKey : segmentRowKeys) {
          HnswIndexManager mgr = new HnswIndexManager(indexName, segRowKey, conf, dimension,
            VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
            HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
          mgr.open();
          mgr.loadSegment(hIndexTable, segRowKey);
          managers.add(mgr);
        }

        float[] query = allVectors.get("42");
        List<String> expectedTopK =
          VectorIndexTestUtil.bruteForceTopK(allVectors, query, "COSINE", k);
        VectorFloat<?> qVec = VTS.createFloatVector(query);

        List<Map.Entry<String, Double>> candidates = new ArrayList<>();
        for (HnswIndexManager mgr : managers) {
          SearchResult sr = mgr.search(qVec, Math.max(k * 2, 20), 64);
          if (sr != null && sr.getNodes() != null) {
            for (SearchResult.NodeScore ns : sr.getNodes()) {
              byte[] pkBytes = mgr.getRowKeyForOrdinal(ns.node);
              int id = (Integer) PInteger.INSTANCE.toObject(pkBytes);
              String idStr = String.valueOf(id);
              double dist = VectorIndexTestUtil.dist("COSINE", query, allVectors.get(idStr));
              candidates.add(new AbstractMap.SimpleEntry<>(idStr, dist));
            }
          }
        }
        candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<String> actualTopK = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Double> entry : candidates) {
          if (seen.add(entry.getKey())) {
            actualTopK.add(entry.getKey());
            if (actualTopK.size() == k) break;
          }
        }
        int hits = 0;
        for (String act : actualTopK) {
          if (expectedTopK.contains(act)) hits++;
        }
        double recall = (double) hits / k;
        assertTrue("Recall after rebuild must be >= 0.95, got " + recall, recall >= 0.95);
      } finally {
        for (HnswIndexManager mgr : managers) {
          try {
            mgr.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  @Test
  public void testEmptyRegion() throws Exception {
    String tableName = "T_HNSW_EMPTY_" + generateUniqueName();
    String indexName = "IDX_HNSW_EMPTY_" + generateUniqueName();

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, 4)) SPLIT ON (100, 200)");
      }

      // Insert vectors in region 1 ([min, 100)) and region 3 ([200, max))
      // Leave region 2 ([100, 200)) completely empty
      Random rng = new Random(42);
      String upsertSql = "UPSERT INTO " + tableName + " (ID, V) VALUES (?, ?)";
      try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
        // Region 1: IDs 0..29
        for (int i = 0; i < 30; i++) {
          ps.setInt(1, i);
          Float[] v =
            new Float[] { rng.nextFloat(), rng.nextFloat(), rng.nextFloat(), rng.nextFloat() };
          ps.setArray(2, conn.createArrayOf("FLOAT", v));
          ps.executeUpdate();
        }
        // Region 3: IDs 210..239
        for (int i = 210; i < 240; i++) {
          ps.setInt(1, i);
          Float[] v =
            new Float[] { rng.nextFloat(), rng.nextFloat(), rng.nextFloat(), rng.nextFloat() };
          ps.setArray(2, conn.createArrayOf("FLOAT", v));
          ps.executeUpdate();
        }
      }
      conn.commit();

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = 4) ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals("IndexTool must succeed with empty region present", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable.getIndexState());

      // Region 2 start key is PInteger 100
      byte[] emptyRegionStartKey = PInteger.INSTANCE.toBytes(100);

      String querySeg =
        "SELECT REGION_START_KEY, NODE_COUNT FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE "
          + PhoenixDatabaseMetaData.INDEX_NAME + " = ?";
      int nonNullSegments = 0;
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            nonNullSegments++;
            byte[] startKey = rs.getBytes(1);
            assertFalse(
              "Empty region [100, 200) must not have a metadata row in SYSTEM.VECTOR_GRAPH_SEGMENT",
              Bytes.equals(emptyRegionStartKey, startKey));
          }
        }
      }
      assertEquals("Exactly 2 segments must exist for the 2 non-empty regions", 2, nonNullSegments);
    }
  }

  @Test
  public void testBuildWithCustomParameters() throws Exception {
    String tableName = "T_HNSW_CUSTOM_" + generateUniqueName();
    String indexName = "IDX_HNSW_CUSTOM_" + generateUniqueName();
    int dimension = 16;
    int numVectors = 50;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, " + dimension + "))");
      }

      VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension
          + ", M = 32, ef_construction = 256, alpha = 1.5) ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals(0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable.getIndexState());

      // Verify custom parameters in PTable.VectorIndex
      assertNotNull(indexTable.getVectorIndex());
      assertEquals(Integer.valueOf(32), indexTable.getVectorIndex().getHnswM());
      assertEquals(Integer.valueOf(256), indexTable.getVectorIndex().getHnswEfConstruction());
      assertEquals(Double.valueOf(1.5), indexTable.getVectorIndex().getHnswAlpha());

      // Retrieve segment row key
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ?";
      byte[] segmentRowKey = null;
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Segment metadata row must exist", rs.next());
          segmentRowKey = rs.getBytes(1);
          assertFalse("Only one segment expected for un-split table", rs.next());
        }
      }
      assertNotNull("Segment row key must not be null", segmentRowKey);

      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
      Configuration conf = pconn.getQueryServices().getConfiguration();

      HnswIndexManager mgr = new HnswIndexManager(indexName, segmentRowKey, conf, dimension,
        VectorSimilarityFunction.COSINE, 32, 256, 1.5f, segmentFamily,
        HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
      mgr.open();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        mgr.loadSegment(hIndexTable, segmentRowKey);

        OnDiskGraphIndex onDiskIndex = mgr.getOnDiskGraphIndex();
        assertNotNull("OnDiskGraphIndex must be loaded", onDiskIndex);
        assertEquals(numVectors, onDiskIndex.size());
        assertEquals(dimension, onDiskIndex.getDimension());
        assertEquals(numVectors, mgr.getNextOrdinal());

        // Validate graph connectivity
        try (OnDiskGraphIndex.View view = onDiskIndex.getView()) {
          NodesIterator it = view.getNeighborsIterator(0, 0);
          assertNotNull(it);
          assertTrue("Node 0 must have neighbors", it.hasNext());
        }
      } finally {
        mgr.close();
      }
    }
  }

  @Test
  public void testSQ8Quantization() throws Exception {
    String tableName = "T_HNSW_SQ8_" + generateUniqueName();
    String indexName = "IDX_HNSW_SQ8_" + generateUniqueName();
    int numVectors = 500;
    int dimension = 128;
    int k = 10;
    int efSearch = 64;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, "
            + dimension + ")) SPLIT ON (250)");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);
      assertEquals(numVectors, allVectors.size());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension
          + ", quantization = 'SQ8') ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals("IndexTool job must succeed", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable.getIndexState());

      // Query segment row keys from SYSTEM.VECTOR_GRAPH_SEGMENT
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ? AND " + PhoenixDatabaseMetaData.GENERATION_ID + " = 0";
      List<byte[]> segmentRowKeys = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            segmentRowKeys.add(rs.getBytes(1));
          }
        }
      }
      assertEquals("Must have 2 segments for 2 regions", 2, segmentRowKeys.size());

      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
      Configuration conf = pconn.getQueryServices().getConfiguration();

      List<HnswIndexManager> managers = new ArrayList<>();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        for (byte[] segRowKey : segmentRowKeys) {
          HnswIndexManager mgr = new HnswIndexManager(indexName, segRowKey, conf, dimension,
            VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
            HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
          mgr.open();
          mgr.loadSegment(hIndexTable, segRowKey);
          assertNotNull("Segment must be loaded", mgr.getOnDiskGraphIndex());
          assertTrue("Segment must contain NVQ_VECTORS feature",
            mgr.getOnDiskGraphIndex().getFeatureSet().contains(FeatureId.NVQ_VECTORS));
          assertFalse("Segment must not contain FUSED_PQ feature",
            mgr.getOnDiskGraphIndex().getFeatureSet().contains(FeatureId.FUSED_PQ));
          managers.add(mgr);
        }

        int[] queryIndices = new int[] { 25, 120, 250, 380, 475 };
        double totalRecall = 0.0;

        for (int queryIdx : queryIndices) {
          float[] query = allVectors.get(String.valueOf(queryIdx));
          assertNotNull("Query vector must exist", query);

          List<String> expectedTopK =
            VectorIndexTestUtil.bruteForceTopK(allVectors, query, "COSINE", k);
          assertEquals(k, expectedTopK.size());

          VectorFloat<?> qVec = VTS.createFloatVector(query);
          List<Map.Entry<String, Double>> candidates = new ArrayList<>();

          for (HnswIndexManager mgr : managers) {
            SearchResult sr = mgr.search(qVec, Math.max(k * 2, 20), efSearch);
            if (sr != null && sr.getNodes() != null) {
              for (SearchResult.NodeScore ns : sr.getNodes()) {
                byte[] pkBytes = mgr.getRowKeyForOrdinal(ns.node);
                assertNotNull("Primary key must not be null for ordinal " + ns.node, pkBytes);
                int id = (Integer) PInteger.INSTANCE.toObject(pkBytes);
                String idStr = String.valueOf(id);
                double dist = VectorIndexTestUtil.dist("COSINE", query, allVectors.get(idStr));
                candidates.add(new AbstractMap.SimpleEntry<>(idStr, dist));
              }
            }
          }

          candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
          List<String> actualTopK = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          for (Map.Entry<String, Double> entry : candidates) {
            if (seen.add(entry.getKey())) {
              actualTopK.add(entry.getKey());
              if (actualTopK.size() == k) {
                break;
              }
            }
          }

          int hits = 0;
          Set<String> expectedSet = new HashSet<>(expectedTopK);
          for (String act : actualTopK) {
            if (expectedSet.contains(act)) {
              hits++;
            }
          }
          double recall = (double) hits / k;
          totalRecall += recall;
        }

        double avgRecall = totalRecall / queryIndices.length;
        LOGGER.info("Average recall (SQ8) = {}", avgRecall);
        assertTrue("Average recall for SQ8 must be >= 0.85, got " + avgRecall, avgRecall >= 0.85);
      } finally {
        for (HnswIndexManager mgr : managers) {
          try {
            mgr.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  @Test
  public void testPQQuantization() throws Exception {
    String tableName = "T_HNSW_PQ_" + generateUniqueName();
    String indexName = "IDX_HNSW_PQ_" + generateUniqueName();
    int numVectors = 500;
    int dimension = 128;
    int pqSegments = 16;
    int k = 10;
    int efSearch = 64;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, "
            + dimension + ")) SPLIT ON (250)");
      }

      Map<String, float[]> allVectors =
        VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);
      assertEquals(numVectors, allVectors.size());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension
          + ", quantization = 'PQ', pq_segments = " + pqSegments + ") ASYNC");
      }

      String[] capturedOutputPath = new String[1];
      int status = runIndexTool(tableName, indexName, null, capturedOutputPath);
      assertEquals("IndexTool job must succeed", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      Configuration conf = pconn.getQueryServices().getConfiguration();
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable.getIndexState());

      // (a) Assert codebook was written to HDFS
      assertNotNull("Output path must be captured", capturedOutputPath[0]);
      Path codebookDir =
        new Path(capturedOutputPath[0], indexTable.getPhysicalName().getString() + "_pq_codebook");
      Path codebookPath = new Path(codebookDir, "_pq_codebook");
      FileSystem fs = getUtility().getTestFileSystem();
      assertTrue("PQ codebook file must exist on HDFS at " + codebookPath, fs.exists(codebookPath));
      assertTrue("PQ codebook file must have non-zero length",
        fs.getFileStatus(codebookPath).getLen() > 0);

      // Query segment row keys from SYSTEM.VECTOR_GRAPH_SEGMENT
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ? AND " + PhoenixDatabaseMetaData.GENERATION_ID + " = 0";
      List<byte[]> segmentRowKeys = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            segmentRowKeys.add(rs.getBytes(1));
          }
        }
      }
      assertEquals("Must have 2 segments for 2 regions", 2, segmentRowKeys.size());

      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;

      List<HnswIndexManager> managers = new ArrayList<>();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        for (byte[] segRowKey : segmentRowKeys) {
          HnswIndexManager mgr = new HnswIndexManager(indexName, segRowKey, conf, dimension,
            VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
            HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
          mgr.open();
          mgr.loadSegment(hIndexTable, segRowKey);
          assertNotNull("Segment must be loaded", mgr.getOnDiskGraphIndex());
          assertTrue("Segment must contain FUSED_PQ feature",
            mgr.getOnDiskGraphIndex().getFeatureSet().contains(FeatureId.FUSED_PQ));
          assertTrue("Segment must contain NVQ_VECTORS feature",
            mgr.getOnDiskGraphIndex().getFeatureSet().contains(FeatureId.NVQ_VECTORS));
          managers.add(mgr);
        }

        // (b) Assert recall >= 0.85
        int[] queryIndices = new int[] { 25, 120, 250, 380, 475 };
        double totalRecall = 0.0;

        for (int queryIdx : queryIndices) {
          float[] query = allVectors.get(String.valueOf(queryIdx));
          assertNotNull("Query vector must exist", query);

          List<String> expectedTopK =
            VectorIndexTestUtil.bruteForceTopK(allVectors, query, "COSINE", k);
          assertEquals(k, expectedTopK.size());

          VectorFloat<?> qVec = VTS.createFloatVector(query);
          List<Map.Entry<String, Double>> candidates = new ArrayList<>();

          for (HnswIndexManager mgr : managers) {
            SearchResult sr = mgr.search(qVec, Math.max(k * 2, 20), efSearch);
            if (sr != null && sr.getNodes() != null) {
              for (SearchResult.NodeScore ns : sr.getNodes()) {
                byte[] pkBytes = mgr.getRowKeyForOrdinal(ns.node);
                assertNotNull("Primary key must not be null for ordinal " + ns.node, pkBytes);
                int id = (Integer) PInteger.INSTANCE.toObject(pkBytes);
                String idStr = String.valueOf(id);
                double dist = VectorIndexTestUtil.dist("COSINE", query, allVectors.get(idStr));
                candidates.add(new AbstractMap.SimpleEntry<>(idStr, dist));
              }
            }
          }

          candidates.sort(Comparator.comparingDouble(Map.Entry::getValue));
          List<String> actualTopK = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          for (Map.Entry<String, Double> entry : candidates) {
            if (seen.add(entry.getKey())) {
              actualTopK.add(entry.getKey());
              if (actualTopK.size() == k) {
                break;
              }
            }
          }

          int hits = 0;
          Set<String> expectedSet = new HashSet<>(expectedTopK);
          for (String act : actualTopK) {
            if (expectedSet.contains(act)) {
              hits++;
            }
          }
          double recall = (double) hits / k;
          totalRecall += recall;
        }

        double avgRecall = totalRecall / queryIndices.length;
        LOGGER.info("Average recall (PQ) = {}", avgRecall);
        assertTrue("Average recall for PQ must be >= 0.85, got " + avgRecall, avgRecall >= 0.85);
      } finally {
        for (HnswIndexManager mgr : managers) {
          try {
            mgr.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  @Test
  public void testGlobalCodebookConsistency() throws Exception {
    String tableName = "T_HNSW_PQ_CONSIST_" + generateUniqueName();
    String indexName = "IDX_HNSW_PQ_CONSIST_" + generateUniqueName();
    int numVectors = 600;
    int dimension = 128;
    int pqSegments = 16;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
          "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, "
            + dimension + ")) SPLIT ON (200, 400)");
      }

      VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension
          + ", quantization = 'PQ', pq_segments = " + pqSegments + ") ASYNC");
      }

      String[] capturedOutputPath = new String[1];
      int status = runIndexTool(tableName, indexName, null, capturedOutputPath);
      assertEquals("IndexTool job must succeed", 0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      Configuration conf = pconn.getQueryServices().getConfiguration();
      pconn.removeTable(pconn.getTenantId(), indexName, null, HConstants.LATEST_TIMESTAMP);
      PTable indexTable = pconn.getTableNoCache(indexName);
      assertEquals(PIndexState.ACTIVE, indexTable.getIndexState());

      // Query segment row keys from SYSTEM.VECTOR_GRAPH_SEGMENT
      String querySeg = "SELECT " + PhoenixDatabaseMetaData.SEGMENT_ROW_KEY + " FROM "
        + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME
        + " = ? AND " + PhoenixDatabaseMetaData.GENERATION_ID + " = 0";
      List<byte[]> segmentRowKeys = new ArrayList<>();
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            segmentRowKeys.add(rs.getBytes(1));
          }
        }
      }
      assertEquals("Must have 3 segments for 3 regions", 3, segmentRowKeys.size());

      // Read HDFS codebook bytes
      Path codebookDir =
        new Path(capturedOutputPath[0], indexTable.getPhysicalName().getString() + "_pq_codebook");
      Path codebookPath = new Path(codebookDir, "_pq_codebook");
      FileSystem fs = getUtility().getTestFileSystem();
      assertTrue("HDFS codebook file must exist at " + codebookPath, fs.exists(codebookPath));
      byte[] hdfsCodebookBytes;
      try (FSDataInputStream in = fs.open(codebookPath)) {
        ByteArrayOutputStream hdfsBaos = new ByteArrayOutputStream();
        IOUtils.copy(in, hdfsBaos);
        hdfsCodebookBytes = hdfsBaos.toByteArray();
      }
      assertTrue("HDFS codebook must not be empty", hdfsCodebookBytes.length > 0);

      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;

      List<byte[]> serializedSegmentCodebooks = new ArrayList<>();
      List<HnswIndexManager> managers = new ArrayList<>();
      try (Table hIndexTable =
        pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes())) {
        for (byte[] segRowKey : segmentRowKeys) {
          HnswIndexManager mgr = new HnswIndexManager(indexName, segRowKey, conf, dimension,
            VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
            HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
          mgr.open();
          mgr.loadSegment(hIndexTable, segRowKey);
          OnDiskGraphIndex onDiskIndex = mgr.getOnDiskGraphIndex();
          assertNotNull("Segment must be loaded", onDiskIndex);
          assertTrue("Segment must contain FUSED_PQ",
            onDiskIndex.getFeatureSet().contains(FeatureId.FUSED_PQ));

          FusedPQ fusedPq = (FusedPQ) onDiskIndex.getFeatures().get(FeatureId.FUSED_PQ);
          assertNotNull("FusedPQ feature must not be null", fusedPq);
          ProductQuantization pq = fusedPq.getPQ();
          assertNotNull("ProductQuantization codebook must not be null", pq);

          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          try (DataOutputStream dos = new DataOutputStream(baos)) {
            HnswPqCodebookTrainer.serializeCodebook(pq, dos);
          }
          byte[] segCodebookBytes = baos.toByteArray();
          assertTrue("Serialized segment codebook must not be empty", segCodebookBytes.length > 0);
          serializedSegmentCodebooks.add(segCodebookBytes);
          managers.add(mgr);
        }
      } finally {
        for (HnswIndexManager mgr : managers) {
          try {
            mgr.close();
          } catch (Exception ignored) {
          }
        }
      }

      assertEquals(3, serializedSegmentCodebooks.size());
      byte[] firstCodebook = serializedSegmentCodebooks.get(0);
      for (int i = 1; i < serializedSegmentCodebooks.size(); i++) {
        assertArrayEquals("Codebook of segment " + i + " must match segment 0 byte-for-byte",
          firstCodebook, serializedSegmentCodebooks.get(i));
      }
      assertArrayEquals("Segment codebook must match HDFS codebook byte-for-byte",
        hdfsCodebookBytes, firstCodebook);
    }
  }

  @Test
  public void testMaterializeOnRegionOpenFromSegmentMetadata() throws Exception {
    String tableName = "T_HNSW_MAT_OPEN_" + generateUniqueName();
    String indexName = "IDX_HNSW_MAT_OPEN_" + generateUniqueName();
    int numVectors = 60;
    int dimension = 16;

    try (Connection conn = DriverManager.getConnection(getUrl())) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE " + tableName
          + " (ID INTEGER NOT NULL PRIMARY KEY, V VECTOR(FLOAT, " + dimension + "))");
      }

      VectorIndexTestUtil.insertDeterministicVectors(conn, tableName, numVectors, dimension);

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE VECTOR INDEX " + indexName + " ON " + tableName + " (V) "
          + "WITH (algorithm = 'HNSW', metric = 'COSINE', dimension = " + dimension + ") ASYNC");
      }

      int status = runIndexTool(tableName, indexName, null);
      assertEquals(0, status);

      PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
      PTable indexTable = pconn.getTableNoCache(indexName);
      PTable dataTable = pconn.getTableNoCache(tableName);

      // Verify SYSTEM.VECTOR_GRAPH_SEGMENT contains the segment metadata
      String querySeg =
        "SELECT SEGMENT_ROW_KEY, GENERATION_ID, NODE_COUNT FROM " + SYSTEM_VECTOR_GRAPH_SEGMENT_NAME
          + " WHERE " + PhoenixDatabaseMetaData.INDEX_NAME + " = ?";
      byte[] segRowKeyFromMeta = null;
      long genId = -1;
      long nodeCount = -1;
      try (PreparedStatement ps = conn.prepareStatement(querySeg)) {
        ps.setString(1, indexName);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue("Segment metadata must exist in SYSTEM.VECTOR_GRAPH_SEGMENT", rs.next());
          segRowKeyFromMeta = rs.getBytes(1);
          genId = rs.getLong(2);
          nodeCount = rs.getLong(3);
        }
      }
      assertNotNull("Segment row key in metadata must not be null", segRowKeyFromMeta);
      assertEquals(numVectors, nodeCount);

      byte[] segmentFamily = indexTable.getDefaultFamilyName() != null
        ? indexTable.getDefaultFamilyName().getBytes()
        : QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;

      // 1. Verify HnswIndexManager.open() automatically queries SYSTEM.VECTOR_GRAPH_SEGMENT,
      // materializes the segment off-heap, loads OnDiskGraphIndex, and initializes mutable builder.
      HnswIndexManager manager = new HnswIndexManager(indexName, Bytes.toBytes("reg-open-it"),
        HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW,
        pconn.getQueryServices().getConfiguration(), dimension, VectorSimilarityFunction.COSINE, 16,
        100, 1.2f, segmentFamily, HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
      manager.setConnectionSupplier(() -> DriverManager.getConnection(getUrl()));
      manager.setTableSupplier(
        () -> pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes()));

      assertFalse("Manager must not be initialized before open", manager.isInitialized());
      assertNull("Buffer must be null before open", manager.getRawSegmentBuffer());
      assertNull("Metadata must be null before open", manager.getActiveSegmentMetadata());

      manager.open();
      try {
        assertTrue("Manager must be initialized after open", manager.isInitialized());
        assertNotNull("Active segment metadata must be recorded",
          manager.getActiveSegmentMetadata());
        assertArrayEquals("Segment row key must match metadata", segRowKeyFromMeta,
          manager.getActiveSegmentMetadata().getSegmentRowKey());
        assertNotNull("Off-heap segment buffer must be materialized",
          manager.getRawSegmentBuffer());
        assertTrue("Buffer must be off-heap direct", manager.getRawSegmentBuffer().isDirect());
        assertNotNull("OnDiskGraphIndex must be materialized", manager.getOnDiskGraphIndex());
        assertEquals(numVectors, manager.getOnDiskGraphIndex().size());
        assertNotNull("Mutable builder must be initialized", manager.getMutableBuilder());

        // Perform vector search on materialized index
        float[] queryVec = new float[dimension];
        for (int d = 0; d < dimension; d++) {
          queryVec[d] = 0.5f;
        }
        SearchResult sr = manager.search(VTS.createFloatVector(queryVec), 5);
        assertNotNull("Search result must not be null", sr);
        assertTrue("Must return results", sr.getNodes().length > 0);

        // Verify ordinal-to-PK mapping was unpacked
        for (SearchResult.NodeScore ns : sr.getNodes()) {
          byte[] pk = manager.getRowKeyForOrdinal(ns.node);
          assertNotNull("Primary key mapping must exist for ordinal " + ns.node, pk);
          int id = (Integer) PInteger.INSTANCE.toObject(pk);
          assertTrue("ID must be in range 1..numVectors", id >= 1 && id <= numVectors);
        }

        // 2. Test off-heap eviction and transparent on-demand re-materialization
        manager.evictSegment();
        assertTrue("Manager must be evicted", manager.isEvicted());
        assertNull("Off-heap buffer must be released upon eviction", manager.getRawSegmentBuffer());

        // Search automatically triggers re-materialization via MobSegmentLoader
        SearchResult srAfterReload = manager.search(VTS.createFloatVector(queryVec), 5);
        assertNotNull(srAfterReload);
        assertFalse("Manager must no longer be evicted after search", manager.isEvicted());
        assertNotNull("Off-heap buffer must be restored", manager.getRawSegmentBuffer());
        assertEquals(numVectors, manager.getOnDiskGraphIndex().size());
      } finally {
        manager.close();
      }

      // 3. Test daughter region key range discovery covering [30, +inf)
      byte[] daughterStartKey = Bytes.toBytes(30);
      HnswIndexManager daughterMgr = new HnswIndexManager(indexName, Bytes.toBytes("reg-daughter"),
        daughterStartKey, HConstants.EMPTY_END_ROW, pconn.getQueryServices().getConfiguration(),
        dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f, segmentFamily,
        HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER);
      daughterMgr.setConnectionSupplier(() -> DriverManager.getConnection(getUrl()));
      daughterMgr.setTableSupplier(
        () -> pconn.getQueryServices().getTable(indexTable.getPhysicalName().getBytes()));

      daughterMgr.open();
      try {
        assertTrue("Daughter manager must be initialized", daughterMgr.isInitialized());
        assertNotNull("Daughter manager must discover covering parent segment",
          daughterMgr.getActiveSegmentMetadata());
        assertNotNull("Daughter manager must materialize graph index",
          daughterMgr.getOnDiskGraphIndex());
        assertEquals(numVectors, daughterMgr.getOnDiskGraphIndex().size());
      } finally {
        daughterMgr.close();
      }

      // 4. Test IndexRegionObserver postOpen / getHnswIndexManager integration on HBase region
      List<HRegion> regions = getUtility().getHBaseCluster()
        .getRegions(TableName.valueOf(dataTable.getPhysicalName().getBytes()));
      assertFalse("Base table regions must not be empty", regions.isEmpty());
      for (HRegion region : regions) {
        IndexRegionObserver iro = (IndexRegionObserver) region.getCoprocessorHost()
          .findCoprocessor(IndexRegionObserver.class.getName());
        if (iro != null) {
          HnswIndexManager iroMgr = iro.getHnswIndexManager();
          assertNotNull("HnswIndexManager on IndexRegionObserver must be initialized", iroMgr);
          assertTrue("HnswIndexManager must be open and initialized", iroMgr.isInitialized());
          assertNotNull("Graph index must be loaded on region open", iroMgr.getOnDiskGraphIndex());
          assertEquals(numVectors, iroMgr.getOnDiskGraphIndex().size());
        }
      }
    }
  }
}
