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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.disk.feature.FeatureId;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.hbase.index.IndexRegionObserver;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.hbase.index.table.HTableInterfaceReference;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.hbase.index.vector.HnswIndexManager.SegmentMetadata;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PVectorDouble;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import org.apache.phoenix.thirdparty.com.google.common.collect.ArrayListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.ListMultimap;

/**
 * Unit tests for {@link HnswIndexManager} and its lifecycle integration into
 * {@link IndexRegionObserver}:
 * <ul>
 * <li>Region open and close lifecycle with resource cleanup.</li>
 * <li>Incremental mutable buffer operations (upsert, update, delete, search).</li>
 * <li>Bidirectional mapping between primary keys and JVector ordinals.</li>
 * <li>Off-heap segment buffer management and {@link PhoenixMobReaderSupplier} binding.</li>
 * <li>Concurrent incremental mutations and graph querying.</li>
 * <li>Lifecycle coordination in {@link IndexRegionObserver} for HNSW indexes vs bypassing
 * standard/IVF indexes.</li>
 * </ul>
 */
public class HnswIndexManagerTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @After
  public void tearDown() {
    HnswOffheapAllocator.resetInstance();
  }

  private static final VectorTypeSupport VTS =
    VectorizationProvider.getInstance().getVectorTypeSupport();

  @Test
  public void testLifecycleOpenAndClose() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_HNSW_TABLE", Bytes.toBytes("region-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    assertEquals(VectorIndexType.HNSW, manager.getType());
    assertFalse("Manager should not be open before open()", manager.isInitialized());
    assertFalse("Manager should not be closed initially", manager.isClosed());

    manager.open();
    assertTrue("Manager should be open after open()", manager.isInitialized());
    assertFalse("Manager should not be closed after open()", manager.isClosed());

    // Idempotent open
    manager.open();
    assertTrue(manager.isInitialized());

    manager.close();
    assertTrue("Manager should be closed after close()", manager.isClosed());

    // Operations after close should fail with IllegalStateException
    try {
      manager.upsert(Bytes.toBytes("row1"), new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      fail("upsert() after close() should throw IllegalStateException");
    } catch (IllegalStateException expected) {
      // Expected
    }

    try {
      manager.open();
      fail("open() after close() should throw IllegalStateException");
    } catch (IllegalStateException expected) {
      // Expected
    }
  }

  @Test
  public void testMutableBufferOperations() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_TABLE", Bytes.toBytes("region-1"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    byte[] row1 = Bytes.toBytes("row_key_1");
    byte[] row2 = Bytes.toBytes("row_key_2");
    float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
    float[] v2 = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };

    // 1. Insert two vectors
    manager.upsert(row1, v1);
    manager.upsert(row2, v2);

    assertEquals(2, manager.getMutableNodeCount());
    Integer ord1 = manager.getOrdinalForRowKey(row1);
    Integer ord2 = manager.getOrdinalForRowKey(row2);
    assertNotNull(ord1);
    assertNotNull(ord2);
    assertFalse(ord1.equals(ord2));

    assertArrayEquals(row1, manager.getRowKeyForOrdinal(ord1));
    assertArrayEquals(row2, manager.getRowKeyForOrdinal(ord2));

    // 2. Update row1 with a new vector (replaces ordinal)
    float[] v1Updated = new float[] { 0.707f, 0.707f, 0.0f, 0.0f };
    manager.upsert(row1, v1Updated);

    Integer newOrd1 = manager.getOrdinalForRowKey(row1);
    assertNotNull(newOrd1);
    assertFalse("Updating row key should assign a new ordinal", ord1.equals(newOrd1));
    assertArrayEquals(row1, manager.getRowKeyForOrdinal(newOrd1));
    assertNull("Old ordinal should no longer map to row key", manager.getRowKeyForOrdinal(ord1));

    // 3. Upsert via Phoenix serialized bytes
    byte[] row3 = Bytes.toBytes("row_key_3");
    float[] v3 = new float[] { 0.0f, 0.0f, 1.0f, 0.0f };
    byte[] v3Bytes = PVectorFloat.INSTANCE.toBytes(v3);
    manager.upsert(row3, v3Bytes);

    Integer ord3 = manager.getOrdinalForRowKey(row3);
    assertNotNull(ord3);
    assertArrayEquals(row3, manager.getRowKeyForOrdinal(ord3));

    // 4. Delete row2
    manager.delete(row2);
    assertNull("Deleted row should have no ordinal", manager.getOrdinalForRowKey(row2));
    assertNull("Deleted ordinal should not map to key", manager.getRowKeyForOrdinal(ord2));

    manager.close();
  }

  @Test
  public void testMutableBufferSearch() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_SEARCH", Bytes.toBytes("reg-1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    byte[] r1 = Bytes.toBytes("row_east");
    byte[] r2 = Bytes.toBytes("row_north");
    byte[] r3 = Bytes.toBytes("row_west");

    manager.upsert(r1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
    manager.upsert(r2, new float[] { 0.0f, 1.0f, 0.0f, 0.0f });
    manager.upsert(r3, new float[] { -1.0f, 0.0f, 0.0f, 0.0f });

    // Query close to row_east [1, 0, 0, 0]
    VectorFloat<?> query = VTS.createFloatVector(new float[] { 0.9f, 0.1f, 0.0f, 0.0f });
    SearchResult result = manager.searchMutable(query, 2);

    assertNotNull(result);
    SearchResult.NodeScore[] nodes = result.getNodes();
    assertTrue("Should find at least 2 neighbors", nodes.length >= 2);

    // Top result should be row_east
    byte[] topKey = manager.getRowKeyForOrdinal(nodes[0].node);
    assertArrayEquals("Top neighbor should be row_east", r1, topKey);

    manager.close();
  }

  @Test
  public void testImmutableSegmentLoadingAndMobReaderSupplier() throws Exception {
    File tempFile = tempFolder.newFile("test_hnsw_segment.bin");

    // 1. Build a synthetic OnDiskGraphIndex on disk using JVector
    int dimension = 4;
    int numNodes = 20;
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

    Path path = tempFile.toPath();
    OnDiskGraphIndex.write(onHeapGraph, ravv, path);

    // 2. Read the file into a direct ByteBuffer simulating HBase MOB cell payload
    long fileSize = Files.size(path);
    ByteBuffer directBuffer = ByteBuffer.allocateDirect((int) fileSize);
    try (FileChannel channel = FileChannel.open(path)) {
      while (directBuffer.hasRemaining()) {
        channel.read(directBuffer);
      }
    }
    directBuffer.flip();

    // 3. Test HnswIndexManager loading segment
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_MOB_TABLE", Bytes.toBytes("reg-mob"),
      conf, dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    manager.loadSegment(directBuffer);

    assertNotNull("Segment buffer must be set", manager.getSegmentBuffer());
    PhoenixMobReaderSupplier supplier = manager.getReaderSupplier();
    assertNotNull("ReaderSupplier must be initialized", supplier);
    assertFalse("ReaderSupplier must not be closed", supplier.isClosed());

    OnDiskGraphIndex loadedIndex = manager.getOnDiskGraphIndex();
    assertNotNull("OnDiskGraphIndex must be loaded", loadedIndex);
    assertEquals(numNodes, loadedIndex.size());

    // 4. Verify supplier can create independent readers for concurrent access
    io.github.jbellis.jvector.disk.RandomAccessReader reader1 = supplier.get();
    io.github.jbellis.jvector.disk.RandomAccessReader reader2 = supplier.get();
    assertNotNull(reader1);
    assertNotNull(reader2);

    // Read magic/header bytes concurrently without interference
    reader1.seek(0);
    reader2.seek(0);
    assertEquals(reader1.readInt(), reader2.readInt());

    reader1.close();
    reader2.close();

    // 5. Closing manager should close on-disk index and reader supplier
    manager.close();
    assertTrue(supplier.isClosed());
    assertNull(manager.getSegmentBuffer());
    assertNull(manager.getReaderSupplier());
    assertNull(manager.getOnDiskGraphIndex());
  }

  @Test
  public void testConcurrentUpsertsAndSearches() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("CONCURRENT_TABLE", Bytes.toBytes("reg-conc"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    int numThreads = 4;
    int opsPerThread = 50;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Future<Void>> futures = new ArrayList<>();

    for (int t = 0; t < numThreads; t++) {
      final int threadId = t;
      futures.add(executor.submit(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          Random r = new Random(threadId * 1000L);
          for (int i = 0; i < opsPerThread; i++) {
            byte[] key = Bytes.toBytes("key_" + threadId + "_" + i);
            float[] vec =
              new float[] { r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat() };
            manager.upsert(key, vec);

            // Periodically perform searches concurrent with writes
            if (i % 10 == 0) {
              VectorFloat<?> query = VTS.createFloatVector(vec);
              SearchResult sr = manager.searchMutable(query, 3);
              assertNotNull(sr);
            }
          }
          return null;
        }
      }));
    }

    for (Future<Void> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(numThreads * opsPerThread, manager.getMutableNodeCount());
    manager.close();
  }

  @Test
  public void testIndexRegionObserverLifecycle() throws Exception {
    // 1. Test HNSW vector index table
    PTable hnswTable = mock(PTable.class);
    PTable.VectorIndex hnswVi = mock(PTable.VectorIndex.class);
    when(hnswVi.getAlgorithm()).thenReturn("HNSW");
    when(hnswVi.getDimension()).thenReturn(4);
    when(hnswVi.getDistanceMetric()).thenReturn("COSINE");
    when(hnswVi.getHnswM()).thenReturn(16);
    when(hnswVi.getHnswEfConstruction()).thenReturn(100);
    when(hnswVi.getHnswAlpha()).thenReturn(1.2);
    when(hnswTable.getVectorIndex()).thenReturn(hnswVi);
    when(hnswTable.getVectorIndexAlgorithm()).thenReturn("HNSW");
    PName tableName = mock(PName.class);
    when(tableName.getString()).thenReturn("MY_HNSW_INDEX");
    when(hnswTable.getName()).thenReturn(tableName);

    assertEquals(hnswTable, IndexRegionObserver.getHnswIndexTable(hnswTable));
    assertEquals(hnswTable, IndexRegionObserver.getVectorIndexTable(hnswTable));

    // 2. Test standard global secondary index table: bypassed
    PTable standardIndexTable = mock(PTable.class);
    when(standardIndexTable.getVectorIndex()).thenReturn(null);
    when(standardIndexTable.getVectorIndexAlgorithm()).thenReturn(null);
    assertNull(IndexRegionObserver.getHnswIndexTable(standardIndexTable));
    assertNull(IndexRegionObserver.getVectorIndexTable(standardIndexTable));

    // 3. Test IVF vector index table
    PTable ivfTable = mock(PTable.class);
    PTable.VectorIndex ivfVi = mock(PTable.VectorIndex.class);
    when(ivfVi.getAlgorithm()).thenReturn("IVF");
    when(ivfTable.getVectorIndex()).thenReturn(ivfVi);
    when(ivfTable.getVectorIndexAlgorithm()).thenReturn("IVF");
    assertNull(IndexRegionObserver.getHnswIndexTable(ivfTable));
    assertEquals(ivfTable, IndexRegionObserver.getVectorIndexTable(ivfTable));

    // 4. Test data table with an HNSW index in getIndexes()
    PTable dataTableWithHnsw = mock(PTable.class);
    when(dataTableWithHnsw.getVectorIndexAlgorithm()).thenReturn(null);
    when(dataTableWithHnsw.getIndexes()).thenReturn(Collections.singletonList(hnswTable));
    assertEquals(hnswTable, IndexRegionObserver.getHnswIndexTable(dataTableWithHnsw));
    assertEquals(hnswTable, IndexRegionObserver.getVectorIndexTable(dataTableWithHnsw));

    // 5. Test data table with IVF index in getIndexes()
    PTable dataTableWithIvf = mock(PTable.class);
    when(dataTableWithIvf.getVectorIndexAlgorithm()).thenReturn(null);
    when(dataTableWithIvf.getIndexes()).thenReturn(Collections.singletonList(ivfTable));
    assertNull(IndexRegionObserver.getHnswIndexTable(dataTableWithIvf));
    assertEquals(ivfTable, IndexRegionObserver.getVectorIndexTable(dataTableWithIvf));

    // 6. Test IndexRegionObserver start() and stop() lifecycle
    TestableIndexRegionObserver observer = new TestableIndexRegionObserver(hnswTable);
    RegionCoprocessorEnvironment env = mock(RegionCoprocessorEnvironment.class);
    Region region = mock(Region.class);
    RegionInfo regionInfo = mock(RegionInfo.class);
    TableDescriptor tableDesc = mock(TableDescriptor.class);
    when(regionInfo.getEncodedNameAsBytes()).thenReturn(Bytes.toBytes("enc-reg-1"));
    when(regionInfo.getTable())
      .thenReturn(org.apache.hadoop.hbase.TableName.valueOf("MY_HNSW_INDEX"));
    when(region.getRegionInfo()).thenReturn(regionInfo);
    when(region.getTableDescriptor()).thenReturn(tableDesc);
    when(tableDesc.getColumnFamilies())
      .thenReturn(new org.apache.hadoop.hbase.client.ColumnFamilyDescriptor[0]);
    when(env.getRegion()).thenReturn(region);
    when(env.getRegionInfo()).thenReturn(regionInfo);
    when(env.getConfiguration()).thenReturn(new Configuration());
    when(env.getServerName())
      .thenReturn(org.apache.hadoop.hbase.ServerName.valueOf("localhost", 16000, 1L));

    assertNull("VectorIndexManager must be null before start()", observer.getVectorIndexManager());

    // Call start() - will resolve hnswTable and instantiate HnswIndexManager
    observer.start(env);
    VectorIndexManager initializedMgr = observer.getVectorIndexManager();
    assertNotNull("VectorIndexManager must be instantiated during start()", initializedMgr);
    assertTrue("HnswIndexManager must be initialized", initializedMgr.isInitialized());
    assertFalse("HnswIndexManager must not be closed", initializedMgr.isClosed());
    assertEquals(VectorIndexType.HNSW, initializedMgr.getType());
    assertNotNull("getHnswIndexManager() must return non-null", observer.getHnswIndexManager());

    // Call stop() - will close manager
    observer.stop(env);
    assertTrue("HnswIndexManager must be closed on stop()", initializedMgr.isClosed());
  }

  @Test
  public void testIndexRegionObserverBypassesStandardTable() throws Exception {
    PTable standardTable = mock(PTable.class);
    when(standardTable.getVectorIndex()).thenReturn(null);
    when(standardTable.getVectorIndexAlgorithm()).thenReturn(null);
    when(standardTable.getIndexes()).thenReturn(Collections.emptyList());

    TestableIndexRegionObserver observer = new TestableIndexRegionObserver(standardTable);
    RegionCoprocessorEnvironment env = mock(RegionCoprocessorEnvironment.class);
    Region region = mock(Region.class);
    RegionInfo regionInfo = mock(RegionInfo.class);
    TableDescriptor tableDesc = mock(TableDescriptor.class);
    when(regionInfo.getEncodedNameAsBytes()).thenReturn(Bytes.toBytes("enc-reg-std"));
    when(regionInfo.getTable())
      .thenReturn(org.apache.hadoop.hbase.TableName.valueOf("STANDARD_TABLE"));
    when(region.getRegionInfo()).thenReturn(regionInfo);
    when(region.getTableDescriptor()).thenReturn(tableDesc);
    when(tableDesc.getColumnFamilies())
      .thenReturn(new org.apache.hadoop.hbase.client.ColumnFamilyDescriptor[0]);
    when(env.getRegion()).thenReturn(region);
    when(env.getRegionInfo()).thenReturn(regionInfo);
    when(env.getConfiguration()).thenReturn(new Configuration());
    when(env.getServerName())
      .thenReturn(org.apache.hadoop.hbase.ServerName.valueOf("localhost", 16000, 1L));

    observer.start(env);
    assertNull("VectorIndexManager must be null for non-vector table",
      observer.getVectorIndexManager());

    observer.stop(env);
    assertNull("VectorIndexManager must remain null", observer.getVectorIndexManager());
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

  @Test
  public void testCreateSegmentPutAndGet() {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_TABLE", Bytes.toBytes("reg-1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    byte[] rowKey = Bytes.toBytes("segment_row_1");
    byte[] payload = new byte[] { 1, 2, 3, 4, 5 };

    Put put = manager.createSegmentPut(rowKey, payload);
    assertArrayEquals(rowKey, put.getRow());
    assertTrue(put.has(manager.getSegmentFamily(), manager.getSegmentQualifier()));

    Get get = manager.createSegmentGet(rowKey);
    assertArrayEquals(rowKey, get.getRow());
    assertEquals(1, get.numFamilies());

    // Custom family and qualifier
    byte[] customFam = Bytes.toBytes("cf_custom");
    byte[] customQual = Bytes.toBytes("cq_custom");
    Put customPut = HnswIndexManager.createSegmentPut(rowKey, customFam, customQual, payload);
    assertArrayEquals(rowKey, customPut.getRow());
    assertTrue(customPut.has(customFam, customQual));

    // ByteBuffer Put
    ByteBuffer buf = ByteBuffer.wrap(payload);
    Put bufPut = HnswIndexManager.createSegmentPut(rowKey, customFam, customQual, buf);
    assertArrayEquals(rowKey, bufPut.getRow());
    assertTrue(bufPut.has(customFam, customQual));
  }

  @Test
  public void testCopyCellAndPayloadToDirectByteBuffer() {
    byte[] rowKey = Bytes.toBytes("seg_key");
    byte[] family = Bytes.toBytes("0");
    byte[] qual = Bytes.toBytes("_G");
    byte[] payload = new byte[] { 10, 20, 30, 40, 50, 60 };

    // 1. Copy payload
    ByteBuffer direct1 = HnswIndexManager.copyPayloadToDirectByteBuffer(payload);
    assertNotNull(direct1);
    assertTrue(direct1.isDirect());
    assertEquals(0, direct1.position());
    assertEquals(payload.length, direct1.limit());
    byte[] read1 = new byte[direct1.remaining()];
    direct1.get(read1);
    assertArrayEquals(payload, read1);

    // 2. Copy Cell
    KeyValue kv = new KeyValue(rowKey, family, qual, payload);
    ByteBuffer direct2 = HnswIndexManager.copyCellToDirectByteBuffer(kv);
    assertNotNull(direct2);
    assertTrue(direct2.isDirect());
    assertEquals(0, direct2.position());
    assertEquals(payload.length, direct2.limit());
    byte[] read2 = new byte[direct2.remaining()];
    direct2.get(read2);
    assertArrayEquals(payload, read2);

    assertNull(HnswIndexManager.copyPayloadToDirectByteBuffer(null));
    assertNull(HnswIndexManager.copyCellToDirectByteBuffer(null));
  }

  @Test
  public void testWriteAndReadSegmentWithTableMock() throws Exception {
    File tempFile = tempFolder.newFile("test_mob_table.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, 20, 4);

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("MOCK_TABLE", Bytes.toBytes("reg-mock"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    Table mockTable = mock(Table.class);
    byte[] rowKey = Bytes.toBytes("seg_1");

    // 1. Write segment
    manager.writeSegment(mockTable, rowKey, graphBytes);
    verify(mockTable).put(any(Put.class));

    // 2. Read segment
    KeyValue kv =
      new KeyValue(rowKey, manager.getSegmentFamily(), manager.getSegmentQualifier(), graphBytes);
    Result result = Result.create(new Cell[] { kv });
    when(mockTable.get(any(Get.class))).thenReturn(result);

    ByteBuffer readPayload = manager.readSegmentPayload(mockTable, rowKey);
    assertNotNull(readPayload);
    assertTrue(readPayload.isDirect());
    assertEquals(graphBytes.length, readPayload.remaining());

    // 3. Load segment directly from Table
    manager.loadSegment(mockTable, rowKey);
    assertNotNull(manager.getSegmentBuffer());
    assertTrue(manager.getSegmentBuffer().isDirect());
    assertNotNull(manager.getReaderSupplier());
    assertNotNull(manager.getOnDiskGraphIndex());
    assertEquals(20, manager.getOnDiskGraphIndex().size());

    manager.close();
  }

  @Test
  public void testWriteAndReadSegmentWithRegionMock() throws Exception {
    File tempFile = tempFolder.newFile("test_mob_reg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, 15, 4);

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("MOCK_REG_TABLE", Bytes.toBytes("reg-mock2"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    Region mockRegion = mock(Region.class);
    byte[] rowKey = Bytes.toBytes("seg_reg_1");

    // 1. Write segment
    manager.writeSegment(mockRegion, rowKey, graphBytes);
    verify(mockRegion).put(any(Put.class));

    // 2. Read segment & load
    KeyValue kv =
      new KeyValue(rowKey, manager.getSegmentFamily(), manager.getSegmentQualifier(), graphBytes);
    Result result = Result.create(new Cell[] { kv });
    when(mockRegion.get(any(Get.class))).thenReturn(result);

    manager.loadSegment(mockRegion, rowKey);
    assertNotNull(manager.getSegmentBuffer());
    assertTrue(manager.getSegmentBuffer().isDirect());
    assertEquals(15, manager.getOnDiskGraphIndex().size());

    manager.close();
  }

  @Test
  public void testHnswIndexManagerOffheapEvictionAndOnDemandReload() throws Exception {
    File tempFile1 = tempFolder.newFile("test_evict_1.bin");
    byte[] graphBytes1 = createSerializedGraph(tempFile1, 20, 4);

    File tempFile2 = tempFolder.newFile("test_evict_2.bin");
    byte[] graphBytes2 = createSerializedGraph(tempFile2, 25, 4);

    long budget = Math.max(graphBytes1.length, graphBytes2.length) + 100;
    HnswOffheapAllocator allocator = new HnswOffheapAllocator(budget);

    Configuration conf = new Configuration();
    byte[] family = Bytes.toBytes("0");
    byte[] qualifier = Bytes.toBytes("_G");

    Table mockTable1 = mock(Table.class);
    byte[] rowKey1 = Bytes.toBytes("seg_1");
    KeyValue kv1 = new KeyValue(rowKey1, family, qualifier, graphBytes1);
    Result res1 = Result.create(new Cell[] { kv1 });
    when(mockTable1.get(any(Get.class))).thenReturn(res1);

    Table mockTable2 = mock(Table.class);
    byte[] rowKey2 = Bytes.toBytes("seg_2");
    KeyValue kv2 = new KeyValue(rowKey2, family, qualifier, graphBytes2);
    Result res2 = Result.create(new Cell[] { kv2 });
    when(mockTable2.get(any(Get.class))).thenReturn(res2);

    HnswIndexManager mgr1 = new HnswIndexManager("TBL1", Bytes.toBytes("reg1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr1.open();

    HnswIndexManager mgr2 = new HnswIndexManager("TBL2", Bytes.toBytes("reg2"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr2.open();

    try {
      // 1. Load segment into manager 1
      mgr1.loadSegment(mockTable1, rowKey1);
      assertNotNull(mgr1.getOnDiskGraphIndex());
      assertEquals(20, mgr1.getOnDiskGraphIndex().size());
      assertFalse(mgr1.isEvicted());
      assertEquals(1, allocator.getTrackedSegmentCount());
      assertEquals(graphBytes1.length, allocator.getAllocatedBytes());

      // 2. Load segment into manager 2 -> exceeds budget, mgr1 is evicted
      mgr2.loadSegment(mockTable2, rowKey2);
      assertNotNull(mgr2.getOnDiskGraphIndex());
      assertEquals(25, mgr2.getOnDiskGraphIndex().size());
      assertFalse(mgr2.isEvicted());

      // mgr1 must now be marked evicted and its raw off-heap buffer null
      assertTrue("Manager 1 segment must be evicted", mgr1.isEvicted());
      assertNull("Manager 1 segment buffer must be null while evicted", mgr1.getRawSegmentBuffer());
      assertEquals(1, allocator.getEvictionCount());
      assertEquals(1, allocator.getTrackedSegmentCount());
      assertEquals(graphBytes2.length, allocator.getAllocatedBytes());

      // 3. Query manager 1 via getOnDiskGraphIndex() -> triggers on-demand re-materialization
      OnDiskGraphIndex reloaded = mgr1.getOnDiskGraphIndex();
      assertNotNull("Manager 1 must re-materialize on query", reloaded);
      assertEquals(20, reloaded.size());
      assertFalse("Manager 1 must no longer be evicted", mgr1.isEvicted());
      assertNotNull("Manager 1 buffer must be restored", mgr1.getRawSegmentBuffer());

      // Manager 2 was least recently queried, so manager 2 must now be evicted
      assertTrue("Manager 2 must be evicted after manager 1 re-materialization", mgr2.isEvicted());
      assertNull(mgr2.getRawSegmentBuffer());
      assertEquals(2, allocator.getEvictionCount());
    } finally {
      mgr1.close();
      mgr2.close();
    }

    assertEquals(0L, allocator.getAllocatedBytes());
    assertEquals(0, allocator.getTrackedSegmentCount());
  }

  @Test
  public void testHnswIndexManagerSearchTriggersReload() throws Exception {
    File tempFile1 = tempFolder.newFile("test_search_evict_1.bin");
    byte[] graphBytes1 = createSerializedGraph(tempFile1, 30, 4);

    File tempFile2 = tempFolder.newFile("test_search_evict_2.bin");
    byte[] graphBytes2 = createSerializedGraph(tempFile2, 30, 4);

    long budget = graphBytes1.length + 100;
    HnswOffheapAllocator allocator = new HnswOffheapAllocator(budget);

    Configuration conf = new Configuration();
    byte[] family = Bytes.toBytes("0");
    byte[] qualifier = Bytes.toBytes("_G");

    Table mockTable1 = mock(Table.class);
    byte[] rowKey1 = Bytes.toBytes("seg_1");
    KeyValue kv1 = new KeyValue(rowKey1, family, qualifier, graphBytes1);
    when(mockTable1.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kv1 }));

    Table mockTable2 = mock(Table.class);
    byte[] rowKey2 = Bytes.toBytes("seg_2");
    KeyValue kv2 = new KeyValue(rowKey2, family, qualifier, graphBytes2);
    when(mockTable2.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kv2 }));

    HnswIndexManager mgr1 = new HnswIndexManager("TBL1", Bytes.toBytes("reg1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr1.open();

    HnswIndexManager mgr2 = new HnswIndexManager("TBL2", Bytes.toBytes("reg2"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, family, qualifier, allocator);
    mgr2.open();

    try {
      mgr1.loadSegment(mockTable1, rowKey1);
      // Evict mgr1 by loading mgr2
      mgr2.loadSegment(mockTable2, rowKey2);
      assertTrue(mgr1.isEvicted());

      // Search on mgr1 must transparently re-materialize
      VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
      VectorFloat<?> query = vts.createFloatVector(new float[] { 0.5f, 0.5f, 0.5f, 0.5f });
      SearchResult result = mgr1.search(query, 5);

      assertNotNull("Search result must not be null", result);
      assertTrue("Must return results", result.getNodes().length > 0);
      assertFalse("Manager 1 must no longer be evicted", mgr1.isEvicted());
      assertTrue("Manager 2 must now be evicted", mgr2.isEvicted());
    } finally {
      mgr1.close();
      mgr2.close();
    }
  }

  @Test
  public void testOrdinalMappingSerializationRoundTrip() throws Exception {
    byte[] dummyGraphBytes = new byte[256];
    for (int i = 0; i < dummyGraphBytes.length; i++) {
      dummyGraphBytes[i] = (byte) (i & 0xFF);
    }

    Map<Integer, byte[]> ordinalToKey = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      ordinalToKey.put(i, Bytes.toBytes("pk_row_key_" + i));
    }

    // 1. Test serialize and deserialize
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(ordinalToKey);
    assertNotNull(mappingBytes);
    assertTrue(mappingBytes.length > 0);

    Map<Integer, byte[]> deserialized = HnswIndexManager.deserializeOrdinalMapping(mappingBytes);
    assertEquals(100, deserialized.size());
    for (int i = 0; i < 100; i++) {
      assertArrayEquals(ordinalToKey.get(i), deserialized.get(i));
    }

    // 2. Test combine and split
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(dummyGraphBytes, mappingBytes);
    assertEquals(dummyGraphBytes.length + mappingBytes.length + 8, combined.length);

    Pair<byte[], Map<Integer, byte[]>> split = HnswIndexManager.splitSegmentAndMapping(combined);
    assertArrayEquals(dummyGraphBytes, split.getFirst());
    assertEquals(100, split.getSecond().size());
    for (int i = 0; i < 100; i++) {
      assertArrayEquals(ordinalToKey.get(i), split.getSecond().get(i));
    }

    // 3. Test invalid trailer cases
    try {
      byte[] corruptMagic = Arrays.copyOf(combined, combined.length);
      Bytes.putInt(corruptMagic, corruptMagic.length - 4, 0x12345678);
      HnswIndexManager.splitSegmentAndMapping(corruptMagic);
      fail("Should fail on invalid magic");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Invalid ordinal mapping magic"));
    }

    try {
      byte[] corruptLen = Arrays.copyOf(combined, combined.length);
      Bytes.putInt(corruptLen, corruptLen.length - 8, -1);
      HnswIndexManager.splitSegmentAndMapping(corruptLen);
      fail("Should fail on corrupt length");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Invalid ordinal mapping length"));
    }

    try {
      HnswIndexManager.splitSegmentAndMapping(new byte[4]);
      fail("Should fail on buffer too small");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Combined segment buffer too small"));
    }

    // 4. Test loading a segment with trailer into HnswIndexManager
    File tempFile = tempFolder.newFile("test_trailer_segment.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, 20, 4);
    byte[] combinedSegment = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    ByteBuffer directBuffer = ByteBuffer.allocateDirect(combinedSegment.length);
    directBuffer.put(combinedSegment);
    directBuffer.flip();

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_TRAILER_TABLE",
      Bytes.toBytes("reg-trailer"), conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      manager.loadSegment(directBuffer);
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(20, manager.getOnDiskGraphIndex().size());
      for (int i = 0; i < 100; i++) {
        assertArrayEquals(Bytes.toBytes("pk_row_key_" + i), manager.getRowKeyForOrdinal(i));
        assertEquals(Integer.valueOf(i),
          manager.getOrdinalForRowKey(Bytes.toBytes("pk_row_key_" + i)));
      }
    } finally {
      manager.close();
    }
  }

  @Test
  public void testLoadSegmentWithEmbeddedOrdinalMappingTrailer() throws Exception {
    File tempFile = tempFolder.newFile("test_embedded_trailer.bin");
    int numNodes = 30;
    int dimension = 4;
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);

    Map<Integer, byte[]> ordinalToKey = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      ordinalToKey.put(i, Bytes.toBytes("pk_entity_" + i));
    }
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(ordinalToKey);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    ByteBuffer directBuffer = ByteBuffer.allocateDirect(combined.length);
    directBuffer.put(combined);
    directBuffer.flip();

    Configuration conf = new Configuration();
    HnswIndexManager manager =
      new HnswIndexManager("TEST_EMBEDDED_TABLE", Bytes.toBytes("reg-embedded"), conf, dimension,
        VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      manager.loadSegment(directBuffer);
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());

      for (int i = 0; i < numNodes; i++) {
        byte[] expectedKey = Bytes.toBytes("pk_entity_" + i);
        assertArrayEquals("Primary key mismatch for ordinal " + i, expectedKey,
          manager.getRowKeyForOrdinal(i));
        assertEquals("Ordinal mismatch for row key", Integer.valueOf(i),
          manager.getOrdinalForRowKey(expectedKey));
      }

      assertEquals("Next ordinal must be max(ordinals) + 1", numNodes, manager.getNextOrdinal());

      // Upserting a new row should allocate ordinal max + 1 (i.e. numNodes)
      byte[] newKey = Bytes.toBytes("pk_entity_new");
      manager.upsert(newKey, new float[] { 0.1f, 0.2f, 0.3f, 0.4f });
      assertEquals("Newly upserted row must receive nextOrdinal", Integer.valueOf(numNodes),
        manager.getOrdinalForRowKey(newKey));
      assertArrayEquals("Row key for new ordinal must match", newKey,
        manager.getRowKeyForOrdinal(numNodes));
      assertEquals("Next ordinal must increment to numNodes + 1", numNodes + 1,
        manager.getNextOrdinal());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testLoadSegmentWithCorruptTrailerLengthThrowsIOException() throws Exception {
    File tempFile = tempFolder.newFile("test_corrupt_trailer_len.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, 10, 4);

    Map<Integer, byte[]> ordinalToKey = new HashMap<>();
    ordinalToKey.put(0, Bytes.toBytes("row_0"));
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(ordinalToKey);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    // Corrupt length to be negative (-1)
    byte[] corruptCombined = Arrays.copyOf(combined, combined.length);
    Bytes.putInt(corruptCombined, corruptCombined.length - 8, -1);

    ByteBuffer directBuffer = ByteBuffer.allocateDirect(corruptCombined.length);
    directBuffer.put(corruptCombined);
    directBuffer.flip();

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_CORRUPT_TABLE",
      Bytes.toBytes("reg-corrupt"), conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      manager.loadSegment(directBuffer);
      fail("Should throw IOException on corrupt trailer length");
    } catch (IOException expected) {
      assertTrue(
        "Error message should mention invalid ordinal mapping length: " + expected.getMessage(),
        expected.getMessage().contains("Invalid ordinal mapping length"));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testLoadSegmentViaTableAndRegionWithEmbeddedTrailer() throws Exception {
    File tempFile = tempFolder.newFile("test_table_region_trailer.bin");
    int numNodes = 12;
    int dimension = 4;
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);

    Map<Integer, byte[]> ordinalToKey = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      ordinalToKey.put(i, Bytes.toBytes("pk_tr_" + i));
    }
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(ordinalToKey);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("MOCK_TR_TABLE", Bytes.toBytes("reg-tr"), conf,
      dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();

    try {
      // 1. Test loadSegment via Table mock
      Table mockTable = mock(Table.class);
      byte[] tableRowKey = Bytes.toBytes("seg_table_tr_1");
      KeyValue kvTable = new KeyValue(tableRowKey, manager.getSegmentFamily(),
        manager.getSegmentQualifier(), combined);
      when(mockTable.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kvTable }));

      manager.loadSegment(mockTable, tableRowKey);
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());
      for (int i = 0; i < numNodes; i++) {
        assertArrayEquals(Bytes.toBytes("pk_tr_" + i), manager.getRowKeyForOrdinal(i));
      }
      assertEquals(numNodes, manager.getNextOrdinal());

      // 2. Test loadSegment via Region mock
      Region mockRegion = mock(Region.class);
      byte[] regionRowKey = Bytes.toBytes("seg_region_tr_1");
      KeyValue kvRegion = new KeyValue(regionRowKey, manager.getSegmentFamily(),
        manager.getSegmentQualifier(), combined);
      when(mockRegion.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kvRegion }));

      manager.loadSegment(mockRegion, regionRowKey);
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());
      for (int i = 0; i < numNodes; i++) {
        assertArrayEquals(Bytes.toBytes("pk_tr_" + i), manager.getRowKeyForOrdinal(i));
      }
      assertEquals(numNodes, manager.getNextOrdinal());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testMaterializeOnOpenWithExactSegmentMetadata() throws Exception {
    int dimension = 4;
    int numNodes = 15;
    Map<Integer, byte[]> mapping = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      mapping.put(i, Bytes.toBytes("pk_exact_" + i));
    }
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(mapping);
    File tempFile = tempFolder.newFile("test_open_exact_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    String indexName = "IDX_OPEN_EXACT";
    byte[] regionStartKey = Bytes.toBytes("100");
    byte[] regionEndKey = Bytes.toBytes("200");
    byte[] segRowKey = Bytes.toBytes("seg_open_exact_row");
    long generationId = 2L;
    long constructionTime = 123456789L;

    Connection mockConn = mock(Connection.class);
    PreparedStatement mockPs = mock(PreparedStatement.class);
    ResultSet mockRs = mock(ResultSet.class);
    when(mockConn.prepareStatement(any(String.class))).thenReturn(mockPs);
    when(mockPs.executeQuery()).thenReturn(mockRs);
    when(mockRs.next()).thenReturn(true, false);
    when(mockRs.getString(1)).thenReturn(indexName);
    when(mockRs.getBytes(2)).thenReturn(regionStartKey);
    when(mockRs.getLong(3)).thenReturn(generationId);
    when(mockRs.getBytes(4)).thenReturn(regionEndKey);
    when(mockRs.getString(5)).thenReturn("enc_region_1");
    when(mockRs.getBytes(6)).thenReturn(segRowKey);
    when(mockRs.getLong(7)).thenReturn((long) numNodes);
    when(mockRs.getLong(8)).thenReturn(constructionTime);
    when(mockRs.getString(9)).thenReturn("C");

    Table mockTable = mock(Table.class);
    KeyValue kv = new KeyValue(segRowKey, HnswIndexManager.DEFAULT_SEGMENT_FAMILY,
      HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER, combined);
    when(mockTable.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kv }));

    Configuration conf = new Configuration();
    HnswIndexManager manager =
      new HnswIndexManager(indexName, Bytes.toBytes("reg-open-1"), regionStartKey, regionEndKey,
        conf, dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.setConnectionSupplier(() -> mockConn);
    manager.setTableSupplier(() -> mockTable);

    assertNull("activeSegmentMetadata must be null before open()",
      manager.getActiveSegmentMetadata());
    assertNull("onDiskGraphIndex must be null before open()", manager.getOnDiskGraphIndex());

    manager.open();

    try {
      assertTrue("Manager must be initialized", manager.isInitialized());
      assertNotNull("Active segment metadata must be populated on open()",
        manager.getActiveSegmentMetadata());
      SegmentMetadata meta = manager.getActiveSegmentMetadata();
      assertEquals(indexName, meta.getIndexName());
      assertArrayEquals(regionStartKey, meta.getRegionStartKey());
      assertEquals(generationId, meta.getGenerationId());
      assertArrayEquals(segRowKey, meta.getSegmentRowKey());
      assertEquals((long) numNodes, meta.getNodeCount());
      assertEquals(constructionTime, meta.getConstructionTime());

      assertNotNull("OnDiskGraphIndex must be materialized off-heap on open()",
        manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());

      for (int i = 0; i < numNodes; i++) {
        assertArrayEquals(Bytes.toBytes("pk_exact_" + i), manager.getRowKeyForOrdinal(i));
      }

      VectorFloat<?> qVec = VTS.createFloatVector(new float[] { 0.5f, 0.5f, 0.5f, 0.5f });
      SearchResult sr = manager.search(qVec, 5, 20);
      assertNotNull("Search result must not be null", sr);
      assertTrue("Search should return nodes", sr.getNodes().length > 0);
    } finally {
      manager.close();
    }
  }

  @Test
  public void testMaterializeOnOpenWithRangeDiscoveryForDaughterRegion() throws Exception {
    int dimension = 4;
    int numNodes = 10;
    Map<Integer, byte[]> mapping = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      mapping.put(i, Bytes.toBytes("pk_daughter_" + i));
    }
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(mapping);
    File tempFile = tempFolder.newFile("test_open_daughter_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    String indexName = "IDX_OPEN_DAUGHTER";
    byte[] parentStartKey = Bytes.toBytes("100");
    byte[] parentEndKey = Bytes.toBytes("500");
    byte[] daughterStartKey = Bytes.toBytes("100");
    byte[] daughterEndKey = Bytes.toBytes("300");
    byte[] segRowKey = Bytes.toBytes("seg_parent_row_key");
    long generationId = 1L;
    long constructionTime = 987654321L;

    Connection mockConn = mock(Connection.class);
    PreparedStatement exactPs = mock(PreparedStatement.class);
    ResultSet exactRs = mock(ResultSet.class);
    when(exactRs.next()).thenReturn(false);
    when(exactPs.executeQuery()).thenReturn(exactRs);

    PreparedStatement rangePs = mock(PreparedStatement.class);
    ResultSet rangeRs = mock(ResultSet.class);
    when(rangeRs.next()).thenReturn(true, false);
    when(rangeRs.getString(1)).thenReturn(indexName);
    when(rangeRs.getBytes(2)).thenReturn(parentStartKey);
    when(rangeRs.getLong(3)).thenReturn(generationId);
    when(rangeRs.getBytes(4)).thenReturn(parentEndKey);
    when(rangeRs.getString(5)).thenReturn("enc_parent_reg");
    when(rangeRs.getBytes(6)).thenReturn(segRowKey);
    when(rangeRs.getLong(7)).thenReturn((long) numNodes);
    when(rangeRs.getLong(8)).thenReturn(constructionTime);
    when(rangeRs.getString(9)).thenReturn("C");
    when(rangePs.executeQuery()).thenReturn(rangeRs);

    when(mockConn.prepareStatement(
      org.mockito.ArgumentMatchers.contains("ORDER BY GENERATION_ID DESC LIMIT 1")))
        .thenReturn(exactPs);
    when(mockConn
      .prepareStatement(org.mockito.ArgumentMatchers.contains("ORDER BY GENERATION_ID DESC")))
        .thenReturn(rangePs);

    Table mockTable = mock(Table.class);
    KeyValue kv = new KeyValue(segRowKey, HnswIndexManager.DEFAULT_SEGMENT_FAMILY,
      HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER, combined);
    when(mockTable.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kv }));

    Configuration conf = new Configuration();
    HnswIndexManager manager =
      new HnswIndexManager(indexName, Bytes.toBytes("reg-daughter-1"), daughterStartKey,
        daughterEndKey, conf, dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.setConnectionSupplier(() -> mockConn);
    manager.setTableSupplier(() -> mockTable);

    manager.open();

    try {
      assertTrue("Manager must be initialized", manager.isInitialized());
      assertNotNull("Active segment metadata must be found via range scan",
        manager.getActiveSegmentMetadata());
      SegmentMetadata meta = manager.getActiveSegmentMetadata();
      assertArrayEquals("Parent segment row key must be discovered", segRowKey,
        meta.getSegmentRowKey());
      assertEquals(generationId, meta.getGenerationId());
      assertNotNull("Parent segment must be materialized off-heap for daughter",
        manager.getOnDiskGraphIndex());
      assertEquals(numNodes, manager.getOnDiskGraphIndex().size());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testMaterializeOnOpenGracefulWhenNoSegmentFound() throws Exception {
    Connection mockConn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(mockConn.prepareStatement(any(String.class))).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("IDX_NO_SEGS", Bytes.toBytes("reg-empty"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.setConnectionSupplier(() -> mockConn);

    manager.open();

    try {
      assertTrue(manager.isInitialized());
      assertNull("No segment metadata should be found", manager.getActiveSegmentMetadata());
      assertNull("No on-disk graph index should be loaded", manager.getOnDiskGraphIndex());
      assertNotNull("Mutable builder must still be initialized", manager.getMutableBuilder());

      float[] v = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      manager.upsert(Bytes.toBytes("pk-1"), v);
      assertEquals(1, manager.getMutableNodeCount());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testIntervalCoveringLogic() {
    byte[] k000 = Bytes.toBytes("000");
    byte[] k100 = Bytes.toBytes("100");
    byte[] k200 = Bytes.toBytes("200");
    byte[] k300 = Bytes.toBytes("300");
    byte[] k400 = Bytes.toBytes("400");
    byte[] k500 = Bytes.toBytes("500");
    byte[] k800 = Bytes.toBytes("800");
    byte[] k850 = Bytes.toBytes("850");
    byte[] k900 = Bytes.toBytes("900");
    byte[] empty = HConstants.EMPTY_BYTE_ARRAY;

    // Full table bounds [null, null)
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(null, null, null, null));
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(empty, empty, empty, empty));

    // Full parent segment [null, null) covers sub-region [100, 200)
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(null, null, k100, k200));

    // Split daughters [100, 300) and [300, 500) overlap parent [100, 500)
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(k100, k500, k100, k300));
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(k100, k500, k300, k500));
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(k100, k500, k200, k400));

    // Disjoint intervals do NOT overlap
    assertFalse(HnswIndexManager.isCoveringOrOverlapping(k100, k500, k500, k900));
    assertFalse(HnswIndexManager.isCoveringOrOverlapping(k100, k500, k000, k100));

    // First region [null, 200) covers daughter [null, 100)
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(null, k200, null, k100));
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(empty, k200, empty, k100));
    assertFalse(HnswIndexManager.isCoveringOrOverlapping(null, k200, k200, k300));

    // Last region [800, null) covers daughter [850, null)
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(k800, null, k850, null));
    assertTrue(HnswIndexManager.isCoveringOrOverlapping(k800, empty, k850, empty));
    assertFalse(HnswIndexManager.isCoveringOrOverlapping(k800, null, k500, k800));
  }

  @Test
  public void testEvictionAndOnDemandRematerializationAfterOpen() throws Exception {
    int dimension = 4;
    int numNodes = 10;
    Map<Integer, byte[]> mapping = new HashMap<>();
    for (int i = 0; i < numNodes; i++) {
      mapping.put(i, Bytes.toBytes("pk_remat_" + i));
    }
    byte[] mappingBytes = HnswIndexManager.serializeOrdinalMapping(mapping);
    File tempFile = tempFolder.newFile("test_open_remat_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);
    byte[] combined = HnswIndexManager.combineSegmentAndMapping(graphBytes, mappingBytes);

    String indexName = "IDX_OPEN_REMAT";
    byte[] regionStartKey = Bytes.toBytes("100");
    byte[] regionEndKey = Bytes.toBytes("200");
    byte[] segRowKey = Bytes.toBytes("seg_open_remat_row");

    Connection mockConn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(mockConn.prepareStatement(any(String.class))).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn(indexName);
    when(rs.getBytes(2)).thenReturn(regionStartKey);
    when(rs.getLong(3)).thenReturn(1L);
    when(rs.getBytes(4)).thenReturn(regionEndKey);
    when(rs.getString(5)).thenReturn("enc_region_1");
    when(rs.getBytes(6)).thenReturn(segRowKey);
    when(rs.getLong(7)).thenReturn((long) numNodes);
    when(rs.getLong(8)).thenReturn(100L);
    when(rs.getString(9)).thenReturn("C");

    Table mockTable = mock(Table.class);
    KeyValue kv = new KeyValue(segRowKey, HnswIndexManager.DEFAULT_SEGMENT_FAMILY,
      HnswIndexManager.DEFAULT_SEGMENT_QUALIFIER, combined);
    when(mockTable.get(any(Get.class))).thenReturn(Result.create(new Cell[] { kv }));

    Configuration conf = new Configuration();
    HnswIndexManager manager =
      new HnswIndexManager(indexName, Bytes.toBytes("reg-open-remat"), regionStartKey, regionEndKey,
        conf, dimension, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.setConnectionSupplier(() -> mockConn);
    manager.setTableSupplier(() -> mockTable);

    manager.open();
    try {
      assertNotNull("Segment must be loaded after open", manager.getOnDiskGraphIndex());
      assertFalse("Segment must not be evicted initially", manager.isEvicted());

      manager.evictSegment();
      assertTrue("Segment must be evicted", manager.isEvicted());
      assertNull("Raw segment buffer must be null when evicted", manager.getRawSegmentBuffer());

      // Accessing graph index triggers on-demand re-materialization
      OnDiskGraphIndex reloaded = manager.getOnDiskGraphIndex();
      assertNotNull("Graph index must be re-materialized", reloaded);
      assertFalse("Segment must no longer be evicted", manager.isEvicted());
      assertNotNull("Raw segment buffer must be restored", manager.getRawSegmentBuffer());
      assertEquals(numNodes, reloaded.size());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationInsert() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_MUTATION_INSERT", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] rowKey = Bytes.toBytes("pk-insert-1");
      float[] vec = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      byte[] vecBytes = PVectorFloat.INSTANCE.toBytes(vec);

      Put nextPut = new Put(rowKey);
      nextPut.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"), vecBytes);

      manager.onMutation(null, null, nextPut, null, null, null, false, 1000L);

      assertEquals(1, manager.getMutableNodeCount());
      Integer ord = manager.getOrdinalForRowKey(rowKey);
      assertNotNull("Ordinal must be allocated for inserted row", ord);
      assertArrayEquals("Mapped row key must match", rowKey, manager.getRowKeyForOrdinal(ord));

      VectorFloat<?> query = VTS.createFloatVector(new float[] { 0.99f, 0.01f, 0.0f, 0.0f });
      SearchResult sr = manager.searchMutable(query, 1);
      assertNotNull(sr);
      assertTrue(sr.getNodes().length > 0);
      assertEquals((int) ord, sr.getNodes()[0].node);
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationUpdateReplacesOrdinal() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_MUTATION_UPDATE", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] rowKey = Bytes.toBytes("pk-update-1");
      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      Put insertPut = new Put(rowKey);
      insertPut.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"),
        PVectorFloat.INSTANCE.toBytes(v1));

      // 1. Initial insert
      manager.onMutation(null, null, insertPut, null, null, null, false, 1000L);
      Integer initialOrd = manager.getOrdinalForRowKey(rowKey);
      assertNotNull(initialOrd);

      // 2. Update with new vector
      float[] v2 = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };
      Put updatePut = new Put(rowKey);
      updatePut.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"),
        PVectorFloat.INSTANCE.toBytes(v2));

      manager.onMutation(null, insertPut, updatePut, null, null, null, false, 2000L);

      Integer updatedOrd = manager.getOrdinalForRowKey(rowKey);
      assertNotNull("New ordinal must be allocated on update", updatedOrd);
      assertFalse("Old ordinal and new ordinal must differ", initialOrd.equals(updatedOrd));
      assertNull("Old ordinal mapping must be cleared", manager.getRowKeyForOrdinal(initialOrd));
      assertArrayEquals("New ordinal must map to row key", rowKey,
        manager.getRowKeyForOrdinal(updatedOrd));

      // Query close to v2
      VectorFloat<?> queryV2 = VTS.createFloatVector(new float[] { 0.0f, 0.99f, 0.01f, 0.0f });
      SearchResult sr = manager.searchMutable(queryV2, 1);
      assertNotNull(sr);
      assertTrue(sr.getNodes().length > 0);
      assertEquals((int) updatedOrd, sr.getNodes()[0].node);
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationVectorUnchangedSkipped() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_UNCHANGED", Bytes.toBytes("reg-1"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] rowKey = Bytes.toBytes("pk-unchanged-1");
      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      Put put = new Put(rowKey);
      put.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"), PVectorFloat.INSTANCE.toBytes(v1));

      manager.onMutation(null, null, put, null, null, null, false, 1000L);
      Integer initialOrd = manager.getOrdinalForRowKey(rowKey);
      assertNotNull(initialOrd);
      assertEquals(1, manager.getMutableNodeCount());

      // Mutation with isVectorUnchanged = true
      manager.onMutation(null, put, put, null, null, null, true, 2000L);

      // Verify no changes occurred
      assertEquals("Ordinal must not change when vector is unchanged", initialOrd,
        manager.getOrdinalForRowKey(rowKey));
      assertEquals("Node count must remain unchanged", 1, manager.getMutableNodeCount());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationDeleteRemovesOrdinal() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_MUTATION_DELETE", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] rowKey = Bytes.toBytes("pk-delete-1");
      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      Put put = new Put(rowKey);
      put.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"), PVectorFloat.INSTANCE.toBytes(v1));

      manager.onMutation(null, null, put, null, null, null, false, 1000L);
      Integer ord = manager.getOrdinalForRowKey(rowKey);
      assertNotNull(ord);

      // Delete mutation: nextDataRowState is null
      manager.onMutation(null, put, null, null, null, null, false, 2000L);

      assertNull("Deleted row key must have no ordinal", manager.getOrdinalForRowKey(rowKey));
      assertNull("Deleted ordinal must not map to row key", manager.getRowKeyForOrdinal(ord));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationWithIndexMaintainerFloatAndDouble() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_MAINTAINER", Bytes.toBytes("reg-1"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      // 1. Float vector via IndexMaintainer
      IndexMaintainer floatMaintainer = mock(IndexMaintainer.class);
      when(floatMaintainer.isVectorIndex()).thenReturn(true);
      when(floatMaintainer.getVectorSortOrder()).thenReturn(SortOrder.ASC);
      when(floatMaintainer.isDoubleVector(any())).thenReturn(false);

      byte[] row1 = Bytes.toBytes("pk-maintainer-float");
      float[] fVec = new float[] { 0.0f, 0.0f, 1.0f, 0.0f };
      byte[] fBytes = PVectorFloat.INSTANCE.toBytes(fVec);
      when(floatMaintainer.getVectorValue(any(ValueGetter.class), anyLong()))
        .thenReturn(new ImmutableBytesWritable(fBytes));

      Put put1 = new Put(row1);
      manager.onMutation(floatMaintainer, null, put1, null, null, null, false, 1000L);

      Integer ord1 = manager.getOrdinalForRowKey(row1);
      assertNotNull("Ordinal must be allocated via float maintainer", ord1);
      assertArrayEquals(row1, manager.getRowKeyForOrdinal(ord1));

      // 2. Double vector via IndexMaintainer
      IndexMaintainer doubleMaintainer = mock(IndexMaintainer.class);
      when(doubleMaintainer.isVectorIndex()).thenReturn(true);
      when(doubleMaintainer.getVectorSortOrder()).thenReturn(SortOrder.ASC);
      when(doubleMaintainer.isDoubleVector(any())).thenReturn(true);

      byte[] row2 = Bytes.toBytes("pk-maintainer-double");
      double[] dVec = new double[] { 0.0, 0.0, 0.0, 1.0 };
      byte[] dBytes = PVectorDouble.INSTANCE.toBytes(dVec);
      when(doubleMaintainer.getVectorValue(any(ValueGetter.class), anyLong()))
        .thenReturn(new ImmutableBytesWritable(dBytes));

      Put put2 = new Put(row2);
      manager.onMutation(doubleMaintainer, null, put2, null, null, null, false, 2000L);

      Integer ord2 = manager.getOrdinalForRowKey(row2);
      assertNotNull("Ordinal must be allocated via double maintainer", ord2);
      assertArrayEquals(row2, manager.getRowKeyForOrdinal(ord2));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationSafelyCatchesExceptions() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_SAFE_EX", Bytes.toBytes("reg-1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      IndexMaintainer brokenMaintainer = mock(IndexMaintainer.class);
      when(brokenMaintainer.isVectorIndex()).thenReturn(true);
      when(brokenMaintainer.getVectorValue(any(), anyLong()))
        .thenThrow(new RuntimeException("Simulated error in vector extraction"));

      Put put = new Put(Bytes.toBytes("pk-err"));
      try {
        manager.onMutation(brokenMaintainer, null, put, null, null, null, false, 1000L);
      } catch (Throwable t) {
        fail("onMutation must not throw exception when vector extraction fails: " + t.getMessage());
      }
    } finally {
      manager.close();
    }
  }

  @Test
  public void testOnMutationWhenClosedOrUninitialized() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_CLOSED_UNINIT", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    byte[] rowKey = Bytes.toBytes("pk-noop");
    Put put = new Put(rowKey);
    put.addColumn(Bytes.toBytes("0"), Bytes.toBytes("V"),
      PVectorFloat.INSTANCE.toBytes(new float[] { 1.0f, 0.0f, 0.0f, 0.0f }));

    // Uninitialized -> no-op
    manager.onMutation(null, null, put, null, null, null, false, 1000L);
    assertNull("No ordinal should be allocated when uninitialized",
      manager.getOrdinalForRowKey(rowKey));

    manager.open();
    manager.close();

    // Closed -> no-op
    manager.onMutation(null, null, put, null, null, null, false, 1000L);
    assertNull("No ordinal should be allocated when closed", manager.getOrdinalForRowKey(rowKey));
  }

  @Test
  public void testIndexRegionObserverIncrementalMutationPath() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_IRO_MUTATION", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      IndexMaintainer maintainer = mock(IndexMaintainer.class);
      when(maintainer.isVectorIndex()).thenReturn(true);
      when(maintainer.getVectorDistanceMetric()).thenReturn("COSINE");
      when(maintainer.getVectorDimension()).thenReturn(4);
      when(maintainer.shouldPrepareIndexMutations(any(Put.class))).thenReturn(true);
      when(maintainer.getVectorSortOrder()).thenReturn(SortOrder.ASC);
      when(maintainer.getEmptyKeyValueFamily())
        .thenReturn(new ImmutableBytesPtr(Bytes.toBytes("0")));
      when(maintainer.getEmptyKeyValueQualifier()).thenReturn(Bytes.toBytes("_0"));

      byte[] rowKey = Bytes.toBytes("data_row_iro");
      float[] vec = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      byte[] vecBytes = PVectorFloat.INSTANCE.toBytes(vec);
      when(maintainer.getVectorValue(any(ValueGetter.class), anyLong()))
        .thenReturn(new ImmutableBytesWritable(vecBytes));

      Put indexPut = new Put(Bytes.toBytes("index_row_iro"));
      when(maintainer.buildUpdateMutation(any(), any(), any(), anyLong(), any(), any(),
        anyBoolean(), any(), anyBoolean())).thenReturn(indexPut);

      HTableInterfaceReference hTableRef =
        new HTableInterfaceReference(new ImmutableBytesPtr(Bytes.toBytes("INDEX_TABLE")));
      List<Pair<IndexMaintainer, HTableInterfaceReference>> indexTables =
        Collections.singletonList(new Pair<>(maintainer, hTableRef));
      ListMultimap<HTableInterfaceReference, Mutation> indexUpdates = ArrayListMultimap.create();

      Put nextDataRowState = new Put(rowKey);
      IndexRegionObserver.generateIndexMutationsForRow(new ImmutableBytesPtr(rowKey), null,
        nextDataRowState, 1000L, Bytes.toBytes("enc-reg"), QueryConstants.VERIFIED_BYTES,
        indexTables, indexUpdates, manager);

      assertEquals("Manager must receive mutation and insert node", 1,
        manager.getMutableNodeCount());
      Integer ord = manager.getOrdinalForRowKey(rowKey);
      assertNotNull("Ordinal must be allocated for row", ord);
      assertArrayEquals("Mapped row key must match", rowKey, manager.getRowKeyForOrdinal(ord));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testDeletionsAndSearchMutableIntegrity() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_DELETE_INTEGRITY", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();

      byte[] row1 = Bytes.toBytes("row-1");
      byte[] row2 = Bytes.toBytes("row-2");
      byte[] row3 = Bytes.toBytes("row-3");

      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      float[] v2 = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };
      float[] v3 = new float[] { 0.0f, 0.0f, 1.0f, 0.0f };

      manager.upsert(row1, v1);
      manager.upsert(row2, v2);
      manager.upsert(row3, v3);

      assertEquals(3, manager.getMutableNodeCount());

      SearchResult res1 = manager.searchMutable(vts.createFloatVector(v1), 3);
      assertEquals(3, res1.getNodes().length);

      // Delete the first node inserted (original entry point node)
      manager.delete(row1);
      assertEquals(2, manager.getLiveNodeCount());
      assertNull(manager.getOrdinalForRowKey(row1));

      // Search remaining nodes: row1 must not appear, but row2 and row3 must be found
      SearchResult res2 = manager.searchMutable(vts.createFloatVector(v2), 3);
      assertEquals(2, res2.getNodes().length);
      for (SearchResult.NodeScore ns : res2.getNodes()) {
        assertTrue("Deleted node 0 must not be returned", ns.node != 0);
      }

      // Delete all remaining nodes
      manager.delete(row2);
      manager.delete(row3);
      assertEquals(0, manager.getLiveNodeCount());

      // Search empty index returns 0 results cleanly
      SearchResult resEmpty = manager.searchMutable(vts.createFloatVector(v1), 3);
      assertEquals(0, resEmpty.getNodes().length);
    } finally {
      manager.close();
    }
  }

  /**
   * A node inserted after every node in the mutable graph has been deleted must still be
   * searchable. JVector keeps a deleted node as the graph entry point until deleted nodes are
   * removed at flush time, and an insert links itself only to nodes the entry point search accepts
   * -- which excludes deleted ones. Without resetting the fully deleted graph the new node gets no
   * neighbors, never takes over as entry point, and stays invisible to search until the next flush.
   */
  @Test
  public void testUpsertAfterAllNodesDeletedIsSearchable() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_DELETE_ALL_REINSERT",
      Bytes.toBytes("reg-1"), conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();

      byte[] row1 = Bytes.toBytes("row-1");
      byte[] row2 = Bytes.toBytes("row-2");
      byte[] row3 = Bytes.toBytes("row-3");
      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      float[] v2 = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };
      float[] v3 = new float[] { 0.0f, 0.0f, 1.0f, 0.0f };

      manager.upsert(row1, v1);
      manager.upsert(row2, v2);
      manager.delete(row1);
      manager.delete(row2);
      assertEquals(0, manager.getLiveNodeCount());
      // The fully deleted graph is dropped, so the next insert starts a fresh entry point
      assertEquals(0, manager.getMutableNodeCount());

      manager.upsert(row3, v3);
      assertEquals(1, manager.getLiveNodeCount());

      SearchResult mutable = manager.searchMutable(vts.createFloatVector(v3), 3);
      assertEquals(1, mutable.getNodes().length);
      assertArrayEquals(row3, manager.getRowKeyForOrdinal(mutable.getNodes()[0].node));

      SearchResult merged = manager.search(vts.createFloatVector(v3), 3);
      assertNotNull(merged);
      assertEquals(1, merged.getNodes().length);
      assertArrayEquals(row3, manager.getRowKeyForOrdinal(merged.getNodes()[0].node));
    } finally {
      manager.close();
    }
  }

  /**
   * Deleting and re-inserting the same primary key, the single-row form of
   * {@link #testUpsertAfterAllNodesDeletedIsSearchable()}, must leave the new vector searchable and
   * the old one gone.
   */
  @Test
  public void testDeleteThenReinsertSameRowKeyIsSearchable() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_REINSERT_SAME_KEY",
      Bytes.toBytes("reg-1"), conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();

      byte[] row = Bytes.toBytes("row-reinsert");
      float[] oldVector = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      float[] newVector = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };

      manager.upsert(row, oldVector);
      manager.delete(row);
      manager.upsert(row, newVector);

      SearchResult result = manager.searchMutable(vts.createFloatVector(newVector), 3);
      assertEquals(1, result.getNodes().length);
      assertArrayEquals(row, manager.getRowKeyForOrdinal(result.getNodes()[0].node));

      // The replaced vector must no longer be indexed under any ordinal
      assertEquals(1, manager.getLiveNodeCount());
      assertEquals(1, manager.getMutableNodeCount());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testFlushWithoutPriorSegment() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_INIT", Bytes.toBytes("reg-1"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] r1 = Bytes.toBytes("row-1");
      byte[] r2 = Bytes.toBytes("row-2");
      byte[] r3 = Bytes.toBytes("row-3");
      float[] v1 = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
      float[] v2 = new float[] { 0.0f, 1.0f, 0.0f, 0.0f };
      float[] v3 = new float[] { 0.0f, 0.0f, 1.0f, 0.0f };

      manager.upsert(r1, v1);
      manager.upsert(r2, v2);
      manager.upsert(r3, v3);

      assertEquals(3, manager.getMutableNodeCount());
      assertNull(manager.getOnDiskGraphIndex());
      assertNull(manager.getActiveSegmentMetadata());

      boolean flushed = manager.flush();
      assertTrue("Flush should return true when mutable vectors exist", flushed);

      // Verify post-flush state: mutable buffer cleared, segment materialized
      assertEquals(0, manager.getMutableNodeCount());
      assertNotNull(manager.getOnDiskGraphIndex());
      assertEquals(3, manager.getOnDiskGraphIndex().size());
      assertNotNull(manager.getActiveSegmentMetadata());
      assertEquals(1L, manager.getActiveSegmentMetadata().getGenerationId());
      assertEquals(3L, manager.getActiveSegmentMetadata().getNodeCount());
      assertNotNull(manager.getSegmentBuffer());
      assertTrue(manager.getSegmentBuffer().isDirect());

      // Verify search recall on materialized segment
      SearchResult res =
        manager.search(VTS.createFloatVector(new float[] { 0.95f, 0.05f, 0.0f, 0.0f }), 2);
      assertNotNull(res);
      assertTrue(res.getNodes().length >= 1);
      byte[] topKey = manager.getRowKeyForOrdinal(res.getNodes()[0].node);
      assertArrayEquals(r1, topKey);
    } finally {
      manager.close();
    }
  }

  @Test
  public void testFlushIncrementalMergeWithExistingSegment() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_MERGE", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] r1 = Bytes.toBytes("row-1");
      byte[] r2 = Bytes.toBytes("row-2");
      manager.upsert(r1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      manager.upsert(r2, new float[] { 0.0f, 1.0f, 0.0f, 0.0f });

      // First flush -> Generation 1
      assertTrue(manager.flush());
      assertEquals(1L, manager.getActiveSegmentMetadata().getGenerationId());
      assertEquals(2, manager.getOnDiskGraphIndex().size());

      // Add new nodes into mutable buffer
      byte[] r3 = Bytes.toBytes("row-3");
      byte[] r4 = Bytes.toBytes("row-4");
      manager.upsert(r3, new float[] { 0.0f, 0.0f, 1.0f, 0.0f });
      manager.upsert(r4, new float[] { 0.0f, 0.0f, 0.0f, 1.0f });
      assertEquals(2, manager.getMutableNodeCount());

      // Second flush -> Incremental merge via buildAndMergeNewNodes -> Generation 2
      assertTrue(manager.flush());
      assertEquals(2L, manager.getActiveSegmentMetadata().getGenerationId());
      assertEquals(4L, manager.getActiveSegmentMetadata().getNodeCount());
      assertEquals(4, manager.getOnDiskGraphIndex().size());
      assertEquals(0, manager.getMutableNodeCount());

      // Verify all 4 keys are preserved and searchable across generations
      SearchResult res1 =
        manager.search(VTS.createFloatVector(new float[] { 0.9f, 0.1f, 0.0f, 0.0f }), 1);
      assertNotNull(res1);
      assertArrayEquals(r1, manager.getRowKeyForOrdinal(res1.getNodes()[0].node));

      SearchResult res4 =
        manager.search(VTS.createFloatVector(new float[] { 0.0f, 0.0f, 0.1f, 0.9f }), 1);
      assertNotNull(res4);
      assertArrayEquals(r4, manager.getRowKeyForOrdinal(res4.getNodes()[0].node));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testFlushDeletionPruning() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_PRUNE", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      byte[] r1 = Bytes.toBytes("row-1");
      byte[] r2 = Bytes.toBytes("row-2");
      byte[] r3 = Bytes.toBytes("row-3");
      manager.upsert(r1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      manager.upsert(r2, new float[] { 0.0f, 1.0f, 0.0f, 0.0f });
      manager.upsert(r3, new float[] { 0.0f, 0.0f, 1.0f, 0.0f });

      // Delete r2 before flush
      manager.delete(r2);
      assertEquals(2, manager.getLiveNodeCount());

      // Flush should prune deleted node r2
      assertTrue(manager.flush());
      assertEquals(2, manager.getOnDiskGraphIndex().size());
      assertEquals(2L, manager.getActiveSegmentMetadata().getNodeCount());

      // Search r2 vector: top result should NOT be r2
      SearchResult res =
        manager.search(VTS.createFloatVector(new float[] { 0.0f, 1.0f, 0.0f, 0.0f }), 3);
      for (SearchResult.NodeScore ns : res.getNodes()) {
        byte[] key = manager.getRowKeyForOrdinal(ns.node);
        assertFalse("Deleted node r2 must not appear in search results", Bytes.equals(r2, key));
      }
    } finally {
      manager.close();
    }
  }

  @Test
  public void testFlushNoOpWhenEmpty() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_NOOP", Bytes.toBytes("reg-1"), conf,
      4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      // Flush on empty manager -> false
      assertFalse(manager.flush());

      manager.upsert(Bytes.toBytes("r1"), new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      assertTrue(manager.flush());

      // Second flush without new mutations -> false
      assertFalse(manager.flush());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testFlushThresholdAutoTrigger() throws Exception {
    Configuration conf = new Configuration();
    conf.setInt(QueryServices.HNSW_FLUSH_THRESHOLD_ATTRIB, 3);
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_THRESH", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      manager.upsert(Bytes.toBytes("r1"), new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      manager.upsert(Bytes.toBytes("r2"), new float[] { 0.0f, 1.0f, 0.0f, 0.0f });
      // Not yet at threshold (2 < 3)
      assertNull(manager.getActiveSegmentMetadata());

      // 3rd upsert reaches threshold -> triggers async flush
      manager.upsert(Bytes.toBytes("r3"), new float[] { 0.0f, 0.0f, 1.0f, 0.0f });

      // Wait up to 5s for async flush to complete
      long start = System.currentTimeMillis();
      while (
        manager.getActiveSegmentMetadata() == null && System.currentTimeMillis() - start < 5000
      ) {
        Thread.sleep(50);
      }

      assertNotNull("Segment metadata should be set by auto-triggered flush",
        manager.getActiveSegmentMetadata());
      assertEquals(1L, manager.getActiveSegmentMetadata().getGenerationId());
      assertEquals(3L, manager.getActiveSegmentMetadata().getNodeCount());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testScheduledPeriodicFlush() throws Exception {
    Configuration conf = new Configuration();
    conf.setLong(QueryServices.HNSW_FLUSH_INTERVAL_MS_ATTRIB, 200L);
    HnswIndexManager manager = new HnswIndexManager("TEST_FLUSH_SCHED", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);
    manager.open();
    try {
      manager.upsert(Bytes.toBytes("r1"), new float[] { 1.0f, 0.0f, 0.0f, 0.0f });

      // Wait for periodic flush task to trigger (configured at 200ms)
      long start = System.currentTimeMillis();
      while (
        manager.getActiveSegmentMetadata() == null && System.currentTimeMillis() - start < 5000
      ) {
        Thread.sleep(50);
      }

      assertNotNull("Periodic task should have executed flush", manager.getActiveSegmentMetadata());
      assertEquals(1L, manager.getActiveSegmentMetadata().getGenerationId());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testQuantizationFlushModes() throws Exception {
    Configuration conf = new Configuration();

    // 1. SQ8 mode
    HnswIndexManager sq8Manager = new HnswIndexManager("TEST_SQ8", Bytes.toBytes("reg-1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES,
      Bytes.toBytes("_G"), "SQ8");
    sq8Manager.open();
    try {
      sq8Manager.upsert(Bytes.toBytes("sq1"), new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
      sq8Manager.upsert(Bytes.toBytes("sq2"), new float[] { 0.0f, 1.0f, 0.0f, 0.0f });
      assertTrue(sq8Manager.flush());

      OnDiskGraphIndex sq8Graph = sq8Manager.getOnDiskGraphIndex();
      assertNotNull(sq8Graph);
      assertTrue("SQ8 index must have NVQ feature",
        sq8Graph.getFeatureSet().contains(FeatureId.NVQ_VECTORS));
      assertTrue("SQ8 index must preserve InlineVectors for exact rerank and compaction",
        sq8Graph.getFeatureSet().contains(FeatureId.INLINE_VECTORS));

      SearchResult res =
        sq8Manager.search(VTS.createFloatVector(new float[] { 0.99f, 0.01f, 0.0f, 0.0f }), 1);
      assertNotNull(res);
      assertArrayEquals(Bytes.toBytes("sq1"),
        sq8Manager.getRowKeyForOrdinal(res.getNodes()[0].node));
    } finally {
      sq8Manager.close();
    }

    // 2. PQ mode
    HnswIndexManager pqManager = new HnswIndexManager("TEST_PQ", Bytes.toBytes("reg-1"), conf, 4,
      VectorSimilarityFunction.COSINE, 16, 100, 1.2f, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES,
      Bytes.toBytes("_G"), "PQ");
    pqManager.open();
    try {
      // Need at least 256 vectors to train 256-cluster PQ codebook required by FusedPQ
      for (int i = 0; i < 260; i++) {
        float[] v = new float[] { (float) Math.sin(i), (float) Math.cos(i), (float) Math.sin(i * 2),
          (float) Math.cos(i * 2) };
        pqManager.upsert(Bytes.toBytes("pq_" + i), v);
      }
      assertTrue(pqManager.flush());

      OnDiskGraphIndex pqGraph = pqManager.getOnDiskGraphIndex();
      assertNotNull(pqGraph);
      assertTrue("PQ index must have FUSED_PQ feature",
        pqGraph.getFeatureSet().contains(FeatureId.FUSED_PQ));
      assertTrue("PQ index must have NVQ feature",
        pqGraph.getFeatureSet().contains(FeatureId.NVQ_VECTORS));
      assertTrue("PQ index must preserve InlineVectors",
        pqGraph.getFeatureSet().contains(FeatureId.INLINE_VECTORS));

      SearchResult res =
        pqManager.search(VTS.createFloatVector(new float[] { 0.0f, 1.0f, 0.0f, 1.0f }), 3);
      assertNotNull(res);
      assertTrue(res.getNodes().length > 0);
    } finally {
      pqManager.close();
    }
  }

  @Test
  public void testConvertOnDiskGraphToOnHeapBytesRoundTrip() throws Exception {
    File tempFile = tempFolder.newFile("test_convert_ondisk.bin");
    createSerializedGraph(tempFile, 15, 4);

    byte[] graphBytes = Files.readAllBytes(tempFile.toPath());
    ByteBuffer directBuffer = ByteBuffer.allocateDirect(graphBytes.length);
    directBuffer.put(graphBytes);
    directBuffer.flip();
    PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuffer);
    OnDiskGraphIndex onDiskGraph = OnDiskGraphIndex.load(supplier);
    try {
      byte[] onHeapBytes = HnswIndexManager.convertOnDiskGraphToOnHeapBytes(onDiskGraph);
      assertNotNull(onHeapBytes);
      assertTrue(onHeapBytes.length > 20);

      // Verify on-heap header magic 0x75EC4012 and version 4
      ByteBuffer buf = ByteBuffer.wrap(onHeapBytes);
      assertEquals(0x75EC4012, buf.getInt());
      assertEquals(4, buf.getInt());
      assertEquals(1, buf.getInt()); // layerCount
      assertEquals(onDiskGraph.maxDegree(), buf.getInt());
    } finally {
      supplier.close();
    }
  }

  @Test
  public void testCatchUpRecoveryReplaysUpsertsIntoMutableBuffer() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_UPSERT", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    Region mockRegion = mock(Region.class);
    RegionScanner mockScanner = mock(RegionScanner.class);

    byte[] r1 = Bytes.toBytes("row1");
    byte[] r2 = Bytes.toBytes("row2");
    byte[] v1Bytes = PVectorFloat.INSTANCE.toBytes(new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
    byte[] v2Bytes = PVectorFloat.INSTANCE.toBytes(new float[] { 0.0f, 1.0f, 0.0f, 0.0f });

    KeyValue kv1 = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      1000L, KeyValue.Type.Put, v1Bytes);
    KeyValue kv2 = new KeyValue(r2, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      1005L, KeyValue.Type.Put, v2Bytes);

    when(mockRegion.getScanner(any(Scan.class))).thenReturn(mockScanner);

    AtomicInteger step = new AtomicInteger(0);
    org.mockito.stubbing.Answer<Boolean> answer = invocation -> {
      List<Cell> out = invocation.getArgument(0);
      int s = step.getAndIncrement();
      if (s == 0) {
        out.add(kv1);
        return true;
      } else if (s == 1) {
        out.add(kv2);
        return false;
      }
      return false;
    };
    when(mockScanner.nextRaw(any())).thenAnswer(answer);
    when(mockScanner.next(any())).thenAnswer(answer);

    manager.setRegion(mockRegion);
    manager.open();
    try {
      assertTrue(manager.isInitialized());
      assertEquals(2, manager.getLastRecoveredRows());
      assertEquals(2, manager.getLastRecoveredUpserts());
      assertEquals(0, manager.getLastRecoveredDeletes());
      assertEquals(2, manager.getMutableNodeCount());

      assertNotNull(manager.getOrdinalForRowKey(r1));
      assertNotNull(manager.getOrdinalForRowKey(r2));
      assertArrayEquals(r1, manager.getRowKeyForOrdinal(manager.getOrdinalForRowKey(r1)));
      assertArrayEquals(r2, manager.getRowKeyForOrdinal(manager.getOrdinalForRowKey(r2)));

      // Search immediately recovers row1 as closest to query [1, 0, 0, 0]
      SearchResult sr =
        manager.search(VTS.createFloatVector(new float[] { 1.0f, 0.0f, 0.0f, 0.0f }), 1);
      assertNotNull(sr);
      assertEquals(1, sr.getNodes().length);
      assertArrayEquals(r1, manager.getRowKeyForOrdinal(sr.getNodes()[0].node));
    } finally {
      manager.close();
    }
  }

  @Test
  public void testCatchUpRecoveryBoundsScanToConstructionTime() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_TIME", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    Region mockRegion = mock(Region.class);
    RegionScanner mockScanner = mock(RegionScanner.class);

    long constructionTime = 123456789L;
    HnswIndexManager.SegmentMetadata metadata =
      new HnswIndexManager.SegmentMetadata("TEST_RECOVERY_TIME", HConstants.EMPTY_START_ROW, 0L,
        HConstants.EMPTY_END_ROW, "reg-1", Bytes.toBytes("seg-0"), 10L, constructionTime, "C");
    manager.setActiveSegmentMetadata(metadata);

    ArgumentCaptor<Scan> scanCaptor = ArgumentCaptor.forClass(Scan.class);
    when(mockRegion.getScanner(scanCaptor.capture())).thenReturn(mockScanner);
    when(mockScanner.nextRaw(any())).thenReturn(false);
    when(mockScanner.next(any())).thenReturn(false);

    manager.setRegion(mockRegion);
    manager.open();
    try {
      Scan capturedScan = scanCaptor.getValue();
      assertNotNull("Scan must have been passed to getScanner", capturedScan);
      assertEquals("Scan min timestamp must match segment constructionTime", constructionTime,
        capturedScan.getTimeRange().getMin());
      assertEquals("Scan max timestamp must be LATEST_TIMESTAMP", HConstants.LATEST_TIMESTAMP,
        capturedScan.getTimeRange().getMax());
      assertTrue("Scan must be raw to include delete markers", capturedScan.isRaw());
    } finally {
      manager.close();
    }
  }

  @Test
  public void testCatchUpRecoveryReplaysDeletes() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_DEL", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    byte[] r1 = Bytes.toBytes("row1");
    manager.upsert(r1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
    assertNotNull(manager.getOrdinalForRowKey(r1));

    Region mockRegion = mock(Region.class);
    RegionScanner mockScanner = mock(RegionScanner.class);

    KeyValue delKv = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES,
      Bytes.toBytes("V"), 2000L, KeyValue.Type.Delete);

    when(mockRegion.getScanner(any(Scan.class))).thenReturn(mockScanner);
    AtomicInteger step = new AtomicInteger(0);
    org.mockito.stubbing.Answer<Boolean> answer = invocation -> {
      List<Cell> out = invocation.getArgument(0);
      if (step.getAndIncrement() == 0) {
        out.add(delKv);
        return false;
      }
      return false;
    };
    when(mockScanner.nextRaw(any())).thenAnswer(answer);
    when(mockScanner.next(any())).thenAnswer(answer);

    manager.setRegion(mockRegion);
    manager.runCatchUpRecovery();

    assertEquals(1, manager.getLastRecoveredRows());
    assertEquals(1, manager.getLastRecoveredDeletes());
    assertEquals(0, manager.getLastRecoveredUpserts());
    assertNull("Deleted row should no longer have an ordinal mapping",
      manager.getOrdinalForRowKey(r1));
  }

  @Test
  public void testCatchUpRecoveryChronologicalReplayOrder() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_ORDER", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    Region mockRegion = mock(Region.class);
    RegionScanner mockScanner = mock(RegionScanner.class);

    byte[] r1 = Bytes.toBytes("row_order");
    byte[] v1Bytes = PVectorFloat.INSTANCE.toBytes(new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
    byte[] v2Bytes = PVectorFloat.INSTANCE.toBytes(new float[] { 0.0f, 1.0f, 0.0f, 0.0f });

    // Cell at t=100: Upsert V1
    KeyValue kv1 = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      100L, KeyValue.Type.Put, v1Bytes);
    // Cell at t=200: Delete
    KeyValue kv2 = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      200L, KeyValue.Type.Delete);
    // Cell at t=300: Upsert V2
    KeyValue kv3 = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      300L, KeyValue.Type.Put, v2Bytes);

    List<Cell> cells = Arrays.asList(kv3, kv1, kv2);

    when(mockRegion.getScanner(any(Scan.class))).thenReturn(mockScanner);
    AtomicInteger step = new AtomicInteger(0);
    org.mockito.stubbing.Answer<Boolean> answer = invocation -> {
      List<Cell> out = invocation.getArgument(0);
      if (step.getAndIncrement() == 0) {
        out.addAll(cells);
        return false;
      }
      return false;
    };
    when(mockScanner.nextRaw(any())).thenAnswer(answer);
    when(mockScanner.next(any())).thenAnswer(answer);

    manager.setRegion(mockRegion);
    manager.runCatchUpRecovery();

    // Final state should be V2
    assertEquals(1, manager.getLastRecoveredRows());
    assertEquals(2, manager.getLastRecoveredUpserts());
    assertEquals(1, manager.getLastRecoveredDeletes());
    SearchResult sr =
      manager.search(VTS.createFloatVector(new float[] { 0.0f, 1.0f, 0.0f, 0.0f }), 1);
    assertNotNull(sr);
    assertEquals(1, sr.getNodes().length);
    assertArrayEquals(r1, manager.getRowKeyForOrdinal(sr.getNodes()[0].node));
  }

  @Test
  public void testCatchUpRecoveryViaTableSupplier() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_TABLE", Bytes.toBytes("reg-1"),
      conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    Table mockTable = mock(Table.class);
    ResultScanner mockScanner = mock(ResultScanner.class);

    byte[] r1 = Bytes.toBytes("row_tbl_1");
    byte[] v1Bytes = PVectorFloat.INSTANCE.toBytes(new float[] { 0.5f, 0.5f, 0.5f, 0.5f });
    KeyValue kv = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, Bytes.toBytes("V"),
      500L, KeyValue.Type.Put, v1Bytes);
    Result res = Result.create(new Cell[] { kv });

    when(mockTable.getScanner(any(Scan.class))).thenReturn(mockScanner);
    when(mockScanner.iterator()).thenReturn(Collections.singletonList(res).iterator());

    manager.setBaseTableSupplier(() -> mockTable);
    manager.runCatchUpRecovery();

    assertEquals(1, manager.getLastRecoveredRows());
    assertEquals(1, manager.getLastRecoveredUpserts());
    assertNotNull(manager.getOrdinalForRowKey(r1));
  }

  @Test
  public void testCatchUpRecoveryIgnoresUnrelatedColumns() throws Exception {
    Configuration conf = new Configuration();
    HnswIndexManager manager = new HnswIndexManager("TEST_RECOVERY_UNRELATED",
      Bytes.toBytes("reg-1"), conf, 4, VectorSimilarityFunction.COSINE, 16, 100, 1.2f);

    Region mockRegion = mock(Region.class);
    RegionScanner mockScanner = mock(RegionScanner.class);

    byte[] r1 = Bytes.toBytes("row_unrelated");
    // Non-vector text value that does not match expected vector byte length
    KeyValue textKv = new KeyValue(r1, QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES,
      Bytes.toBytes("NAME"), 1000L, KeyValue.Type.Put, Bytes.toBytes("non-vector text value"));

    when(mockRegion.getScanner(any(Scan.class))).thenReturn(mockScanner);
    AtomicInteger step = new AtomicInteger(0);
    org.mockito.stubbing.Answer<Boolean> answer = invocation -> {
      List<Cell> out = invocation.getArgument(0);
      if (step.getAndIncrement() == 0) {
        out.add(textKv);
        return false;
      }
      return false;
    };
    when(mockScanner.nextRaw(any())).thenAnswer(answer);
    when(mockScanner.next(any())).thenAnswer(answer);

    manager.setRegion(mockRegion);
    manager.runCatchUpRecovery();

    assertEquals(1, manager.getLastRecoveredRows());
    assertEquals(0, manager.getLastRecoveredUpserts());
    assertEquals(0, manager.getLastRecoveredDeletes());
    assertNull(manager.getOrdinalForRowKey(r1));
  }

  /**
   * Subclass of IndexRegionObserver overriding resolvePTable for deterministic testing without live
   * HBase cluster.
   */
  private static class TestableIndexRegionObserver extends IndexRegionObserver {
    private final PTable testTable;

    public TestableIndexRegionObserver(PTable testTable) {
      this.testTable = testTable;
    }

    @Override
    public void start(CoprocessorEnvironment e) throws IOException {
      initializeVectorIndexManager((RegionCoprocessorEnvironment) e);
    }

    @Override
    protected PTable resolvePTable(RegionCoprocessorEnvironment env) {
      return testTable;
    }
  }
}
