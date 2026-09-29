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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.IndexRegionObserver;
import org.apache.phoenix.hbase.index.hnsw.HnswOffheapAllocator;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
