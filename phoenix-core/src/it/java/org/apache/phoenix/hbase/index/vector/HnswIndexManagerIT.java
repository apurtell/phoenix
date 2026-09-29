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
package org.apache.phoenix.hbase.index.vector;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.NodesIterator;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category(ParallelStatsDisabledTest.class)
public class HnswIndexManagerIT extends ParallelStatsDisabledIT {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private static final VectorTypeSupport VTS =
    VectorizationProvider.getInstance().getVectorTypeSupport();

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    setUpTestDriver(ReadOnlyProps.EMPTY_PROPS);
  }

  private byte[] createSerializedGraph(File file, int numNodes, int dimension) throws IOException {
    Random rand = new Random(42);
    List<VectorFloat<?>> vectors = new ArrayList<>();
    for (int i = 0; i < numNodes; i++) {
      float[] raw = new float[dimension];
      for (int d = 0; d < dimension; d++) {
        raw[d] = rand.nextFloat();
      }
      vectors.add(VTS.createFloatVector(raw));
    }

    ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vectors, dimension);
    GraphIndexBuilder builder =
      new GraphIndexBuilder(ravv, VectorSimilarityFunction.COSINE, 8, 30, 1.2f, 1.4f, false);
    ImmutableGraphIndex onHeapGraph = builder.build(ravv);

    Path path = file.toPath();
    OnDiskGraphIndex.write(onHeapGraph, ravv, path);
    return Files.readAllBytes(path);
  }

  /**
   * Verifies writing a graph segment to a MOB-enabled family ({@code MOB_THRESHOLD=0}), flushing to
   * force MOB store file generation, and reading via standard {@code Get} to confirm HBase
   * transparently resolves the MOB cell and retrieves the complete binary payload.
   */
  @Test
  public void testTransparentMobStorageAndRetrieval() throws Exception {
    String tableBaseName = "HNSW_MOB_STORAGE_" + generateUniqueName();
    TableName tableName = TableName.valueOf(tableBaseName);
    byte[] family = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
    byte[] qualifier = Bytes.toBytes("_G");

    PhoenixConnection pconn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
    Configuration conf = pconn.getQueryServices().getConfiguration();

    // 1. Create table with MOB enabled and threshold=0 so all cells are stored as MOB
    ColumnFamilyDescriptor cfd = ColumnFamilyDescriptorBuilder.newBuilder(family)
      .setMobEnabled(true).setMobThreshold(0L).build();
    TableDescriptor td = TableDescriptorBuilder.newBuilder(tableName).setColumnFamily(cfd).build();

    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      admin.createTable(td);

      TableDescriptor desc = admin.getDescriptor(tableName);
      assertNotNull(desc.getColumnFamily(family));
      assertTrue("Column family must have MOB enabled",
        desc.getColumnFamily(family).isMobEnabled());
      assertEquals(0L, desc.getColumnFamily(family).getMobThreshold());
    }

    // 2. Build serialized HNSW graph segment
    int numNodes = 40;
    int dimension = 4;
    File tempFile = tempFolder.newFile("test_mob_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);
    assertTrue(graphBytes.length > 0);

    // 3. Write segment using HnswIndexManager
    byte[] segmentRowKey = Bytes.toBytes("SEG_ROW_001");
    HnswIndexManager manager = new HnswIndexManager(tableBaseName, Bytes.toBytes("reg-1"), conf,
      dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier);
    manager.open();

    try (Table table = pconn.getQueryServices().getTable(Bytes.toBytes(tableBaseName))) {
      manager.writeSegment(table, segmentRowKey, graphBytes);

      // Flush to disk to force MOB file creation and ensure cell is a true MOB reference
      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        admin.flush(tableName);
      }

      // 4. Read back segment payload via standard Get; HBase transparently resolves MOB cell
      ByteBuffer directBuffer = manager.readSegmentPayload(table, segmentRowKey);
      assertNotNull("Retrieved buffer must not be null", directBuffer);
      assertTrue("Buffer must be off-heap direct", directBuffer.isDirect());
      assertEquals("Payload length must match original graph bytes", graphBytes.length,
        directBuffer.remaining());

      byte[] retrievedBytes = new byte[directBuffer.remaining()];
      directBuffer.get(retrievedBytes);
      assertArrayEquals("Payload read from MOB must match original serialized bytes byte-for-byte",
        graphBytes, retrievedBytes);

      // 5. Load segment directly from Table into manager
      manager.loadSegment(table, segmentRowKey);
      assertNotNull("Active segment buffer must be set", manager.getSegmentBuffer());
      assertTrue("Active segment buffer must be direct", manager.getSegmentBuffer().isDirect());
      assertNotNull("ReaderSupplier must be initialized", manager.getReaderSupplier());
      assertFalse("ReaderSupplier must not be closed", manager.getReaderSupplier().isClosed());

      OnDiskGraphIndex onDiskIndex = manager.getOnDiskGraphIndex();
      assertNotNull("OnDiskGraphIndex must be loaded", onDiskIndex);
      assertEquals(numNodes, onDiskIndex.size());
      assertEquals(dimension, onDiskIndex.getDimension());
    } finally {
      manager.close();
    }
  }

  /**
   * Verifies direct local {@link HRegion} MOB cell retrieval as performed inside coprocessors.
   */
  @Test
  public void testRegionTransparentMobRetrieval() throws Exception {
    String tableBaseName = "HNSW_MOB_REG_" + generateUniqueName();
    TableName tableName = TableName.valueOf(tableBaseName);
    byte[] family = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
    byte[] qualifier = Bytes.toBytes("_G");

    PhoenixConnection pconn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
    Configuration conf = pconn.getQueryServices().getConfiguration();

    ColumnFamilyDescriptor cfd = ColumnFamilyDescriptorBuilder.newBuilder(family)
      .setMobEnabled(true).setMobThreshold(0L).build();
    TableDescriptor td = TableDescriptorBuilder.newBuilder(tableName).setColumnFamily(cfd).build();

    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      admin.createTable(td);
    }

    int numNodes = 30;
    int dimension = 4;
    File tempFile = tempFolder.newFile("test_mob_reg_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);

    List<HRegion> regions = getUtility().getHBaseCluster().getRegions(tableName);
    assertFalse("Regions must not be empty", regions.isEmpty());
    HRegion region = regions.get(0);

    byte[] segmentRowKey = Bytes.toBytes("SEG_REG_001");
    HnswIndexManager manager =
      new HnswIndexManager(tableBaseName, region.getRegionInfo().getEncodedNameAsBytes(), conf,
        dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier);
    manager.open();

    try {
      // Write segment via region
      manager.writeSegment(region, segmentRowKey, graphBytes);

      // Flush region to trigger MOB compaction into MOB store files
      region.flush(true);

      // Read segment payload directly via region.get; HBase resolves MOB cell transparently
      ByteBuffer directBuffer = manager.readSegmentPayload(region, segmentRowKey);
      assertNotNull("Region read payload must not be null", directBuffer);
      assertTrue("Region read buffer must be direct", directBuffer.isDirect());
      assertEquals(graphBytes.length, directBuffer.remaining());

      byte[] readBytes = new byte[directBuffer.remaining()];
      directBuffer.get(readBytes);
      assertArrayEquals(graphBytes, readBytes);

      // Load segment directly from Region
      manager.loadSegment(region, segmentRowKey);
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());
    } finally {
      manager.close();
    }
  }

  /**
   * Verifies the full round-trip: writes graph segment to MOB-enabled HBase table, retrieves it
   * into a direct buffer, instantiates {@link PhoenixMobReaderSupplier}, and validates concurrent
   * neighbor traversal with {@link OnDiskGraphIndex#getView}.
   */
  @Test
  public void testMobReaderSupplierRoundTripAndConcurrency() throws Exception {
    String tableBaseName = "HNSW_MOB_CONC_" + generateUniqueName();
    TableName tableName = TableName.valueOf(tableBaseName);
    byte[] family = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
    byte[] qualifier = Bytes.toBytes("_G");

    PhoenixConnection pconn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
    Configuration conf = pconn.getQueryServices().getConfiguration();

    ColumnFamilyDescriptor cfd = ColumnFamilyDescriptorBuilder.newBuilder(family)
      .setMobEnabled(true).setMobThreshold(0L).build();
    TableDescriptor td = TableDescriptorBuilder.newBuilder(tableName).setColumnFamily(cfd).build();

    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      admin.createTable(td);
    }

    int numNodes = 60;
    int dimension = 4;
    File tempFile = tempFolder.newFile("test_mob_conc.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);

    byte[] segmentRowKey = Bytes.toBytes("SEG_CONC_001");
    HnswIndexManager manager = new HnswIndexManager(tableBaseName, Bytes.toBytes("reg-1"), conf,
      dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier);
    manager.open();

    try (Table table = pconn.getQueryServices().getTable(Bytes.toBytes(tableBaseName))) {
      manager.writeSegment(table, segmentRowKey, graphBytes);

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        admin.flush(tableName);
      }

      manager.loadSegment(table, segmentRowKey);
      PhoenixMobReaderSupplier supplier = manager.getReaderSupplier();
      assertNotNull(supplier);

      // Verify supplier creates independent reader instances
      try (RandomAccessReader r1 = supplier.get(); RandomAccessReader r2 = supplier.get()) {
        assertNotNull(r1);
        assertNotNull(r2);
        r1.seek(0);
        r2.seek(0);
        assertEquals(r1.readInt(), r2.readInt());
      }

      OnDiskGraphIndex onDiskIndex = manager.getOnDiskGraphIndex();
      assertNotNull(onDiskIndex);
      assertEquals(numNodes, onDiskIndex.size());

      // Concurrent neighbor traversal across multiple threads
      int numThreads = 8;
      int queriesPerThread = 50;
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);
      List<Callable<Void>> tasks = new ArrayList<>();

      for (int t = 0; t < numThreads; t++) {
        final int seed = 200 + t;
        tasks.add(() -> {
          Random rand = new Random(seed);
          try (OnDiskGraphIndex.View view = onDiskIndex.getView()) {
            for (int q = 0; q < queriesPerThread; q++) {
              int queryNode = rand.nextInt(numNodes);
              NodesIterator neighbors = view.getNeighborsIterator(0, queryNode);
              assertNotNull(neighbors);
              while (neighbors.hasNext()) {
                int neighbor = neighbors.nextInt();
                assertTrue("Neighbor node must be valid ordinal",
                  neighbor >= 0 && neighbor < numNodes);
              }
            }
          }
          return null;
        });
      }

      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    } finally {
      manager.close();
    }
  }

  /**
   * Verifies Step 3.5: allocating beyond the off-heap budget triggers LRU eviction of dormant
   * segments from off-heap memory, and subsequent queries transparently re-materialize the evicted
   * segments on demand from HBase MOB storage.
   */
  @Test
  public void testOffHeapEvictionAndOnDemandReloadingFromMob() throws Exception {
    String tableBaseName = "HNSW_MOB_EVICT_" + generateUniqueName();
    TableName tableName = TableName.valueOf(tableBaseName);
    byte[] family = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
    byte[] qualifier = Bytes.toBytes("_G");

    PhoenixConnection pconn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);
    Configuration conf = pconn.getQueryServices().getConfiguration();

    // 1. Create table with MOB enabled and threshold=0
    ColumnFamilyDescriptor cfd = ColumnFamilyDescriptorBuilder.newBuilder(family)
      .setMobEnabled(true).setMobThreshold(0L).build();
    TableDescriptor td = TableDescriptorBuilder.newBuilder(tableName).setColumnFamily(cfd).build();

    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      admin.createTable(td);
    }

    // 2. Build two distinct serialized graph segments
    int dim = 4;
    int nodes1 = 20;
    int nodes2 = 30;
    File tempFile1 = tempFolder.newFile("seg1_mob.bin");
    File tempFile2 = tempFolder.newFile("seg2_mob.bin");
    byte[] graph1Bytes = createSerializedGraph(tempFile1, nodes1, dim);
    byte[] graph2Bytes = createSerializedGraph(tempFile2, nodes2, dim);

    // Bounded budget: enough for one segment, but not both
    long budget = Math.max(graph1Bytes.length, graph2Bytes.length) + 128;
    HnswOffheapAllocator allocator = new HnswOffheapAllocator(budget);

    byte[] segRowKey1 = Bytes.toBytes("SEG_EVICT_001");
    byte[] segRowKey2 = Bytes.toBytes("SEG_EVICT_002");

    HnswIndexManager mgr1 = new HnswIndexManager(tableBaseName, Bytes.toBytes("reg-1"), conf, dim,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr1.open();

    HnswIndexManager mgr2 = new HnswIndexManager(tableBaseName, Bytes.toBytes("reg-2"), conf, dim,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr2.open();

    try (Table table = pconn.getQueryServices().getTable(Bytes.toBytes(tableBaseName))) {
      // 3. Write both segments to HBase MOB storage
      mgr1.writeSegment(table, segRowKey1, graph1Bytes);
      mgr2.writeSegment(table, segRowKey2, graph2Bytes);

      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        admin.flush(tableName);
      }

      // 4. Load Segment 1 into Manager 1
      mgr1.loadSegment(table, segRowKey1);
      assertNotNull("Manager 1 OnDiskGraphIndex must be loaded", mgr1.getOnDiskGraphIndex());
      assertEquals(nodes1, mgr1.getOnDiskGraphIndex().size());
      assertFalse("Manager 1 must not be evicted", mgr1.isEvicted());
      assertNotNull(mgr1.getRawSegmentBuffer());
      assertEquals(1, allocator.getTrackedSegmentCount());
      assertEquals(graph1Bytes.length, allocator.getAllocatedBytes());
      assertEquals(0L, allocator.getEvictionCount());

      // 5. Load Segment 2 into Manager 2 -> Exceeds budget, triggers LRU eviction of Segment 1
      mgr2.loadSegment(table, segRowKey2);
      assertNotNull("Manager 2 OnDiskGraphIndex must be loaded", mgr2.getOnDiskGraphIndex());
      assertEquals(nodes2, mgr2.getOnDiskGraphIndex().size());
      assertFalse("Manager 2 must not be evicted", mgr2.isEvicted());
      assertNotNull(mgr2.getRawSegmentBuffer());

      // Segment 1 in Manager 1 must now be evicted
      assertTrue("Manager 1 must be evicted due to budget constraint", mgr1.isEvicted());
      assertNull("Manager 1 raw off-heap buffer must be released", mgr1.getRawSegmentBuffer());
      assertEquals(1L, allocator.getEvictionCount());
      assertEquals(1, allocator.getTrackedSegmentCount());
      assertEquals(graph2Bytes.length, allocator.getAllocatedBytes());

      // 6. Query Manager 1 -> Triggers transparent on-demand re-materialization from MOB storage
      OnDiskGraphIndex reloadedIndex1 = mgr1.getOnDiskGraphIndex();
      assertNotNull("Manager 1 OnDiskGraphIndex must be re-materialized", reloadedIndex1);
      assertEquals(nodes1, reloadedIndex1.size());
      assertFalse("Manager 1 must no longer be evicted", mgr1.isEvicted());
      assertNotNull("Manager 1 off-heap buffer must be restored", mgr1.getRawSegmentBuffer());

      // Neighbor traversal on reloaded segment
      try (OnDiskGraphIndex.View view = reloadedIndex1.getView()) {
        NodesIterator it = view.getNeighborsIterator(0, 0);
        assertNotNull(it);
      }

      // Manager 2 was dormant, so it must now be evicted
      assertTrue("Manager 2 must be evicted after Manager 1 reloaded", mgr2.isEvicted());
      assertNull("Manager 2 raw buffer must be released", mgr2.getRawSegmentBuffer());
      assertEquals(2L, allocator.getEvictionCount());

      // 7. Query Manager 2 -> Re-materializes Manager 2 from MOB storage
      OnDiskGraphIndex reloadedIndex2 = mgr2.getOnDiskGraphIndex();
      assertNotNull("Manager 2 OnDiskGraphIndex must be re-materialized", reloadedIndex2);
      assertEquals(nodes2, reloadedIndex2.size());
      assertFalse(mgr2.isEvicted());
      assertTrue("Manager 1 must be evicted again", mgr1.isEvicted());
      assertEquals(3L, allocator.getEvictionCount());
    } finally {
      mgr1.close();
      mgr2.close();
      allocator.close();
    }
  }
}
