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
package org.apache.phoenix.hbase.index.hnsw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test verifying {@link PhoenixMobReaderSupplier} over HBase MOB storage:
 * <ul>
 * <li>Writes a serialized HNSW graph segment via {@link Put} to a MOB-enabled family
 * (MOB_THRESHOLD=0).</li>
 * <li>Flushes the table to force MOB store file generation.</li>
 * <li>Retrieves the segment via {@link Get}, resolving the {@code MobCell}.</li>
 * <li>Copies the cell payload into an off-heap {@link java.nio.ByteBuffer#allocateDirect}.</li>
 * <li>Wraps the buffer in {@link PhoenixMobReaderSupplier}.</li>
 * <li>Loads {@link OnDiskGraphIndex} and validates concurrent multi-threaded neighbor traversal
 * without position corruption.</li>
 * </ul>
 */
@Category(ParallelStatsDisabledTest.class)
public class PhoenixMobReaderSupplierIT extends ParallelStatsDisabledIT {

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

  @Test
  public void testMobReaderSupplierRoundTripFromActualMobCell() throws Exception {
    String tableBaseName = "MOB_SUPPLIER_IT_" + generateUniqueName();
    TableName tableName = TableName.valueOf(tableBaseName);
    byte[] family = QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES;
    byte[] qualifier = Bytes.toBytes("_G");

    PhoenixConnection pconn = DriverManager.getConnection(getUrl()).unwrap(PhoenixConnection.class);

    // 1. Create table with MOB enabled and threshold=0 so all cells are stored in MOB files
    ColumnFamilyDescriptor cfd = ColumnFamilyDescriptorBuilder.newBuilder(family)
      .setMobEnabled(true).setMobThreshold(0L).build();
    TableDescriptor td = TableDescriptorBuilder.newBuilder(tableName).setColumnFamily(cfd).build();

    try (Admin admin = pconn.getQueryServices().getAdmin()) {
      admin.createTable(td);
    }

    // 2. Generate serialized graph segment
    int numNodes = 50;
    int dimension = 4;
    File tempFile = tempFolder.newFile("test_mob_supplier_seg.bin");
    byte[] graphBytes = createSerializedGraph(tempFile, numNodes, dimension);

    byte[] segmentRowKey = Bytes.toBytes("SEG_MOB_SUPPLIER_001");

    try (Table table = pconn.getQueryServices().getTable(Bytes.toBytes(tableBaseName))) {
      // 3. Write serialized graph segment via Put
      Put put = new Put(segmentRowKey);
      put.addColumn(family, qualifier, graphBytes);
      table.put(put);

      // Flush table to force MOB file generation and MOB reference cell in store
      try (Admin admin = pconn.getQueryServices().getAdmin()) {
        admin.flush(tableName);
      }

      // 4. Retrieve via Get, resolving MobCell transparently
      Get get = new Get(segmentRowKey);
      get.addColumn(family, qualifier);
      Result result = table.get(get);
      Cell cell = result.getColumnLatestCell(family, qualifier);
      assertNotNull("Retrieved cell must not be null", cell);
      assertEquals("Payload length must match serialized graph", graphBytes.length,
        cell.getValueLength());

      // 5. Copy cell payload into off-heap direct ByteBuffer
      ByteBuffer directBuffer = ByteBuffer.allocateDirect(cell.getValueLength());
      directBuffer.put(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
      directBuffer.flip();

      byte[] verifyBytes = new byte[directBuffer.remaining()];
      directBuffer.duplicate().get(verifyBytes);
      assertArrayEquals("Payload read from MobCell must match original bytes", graphBytes,
        verifyBytes);

      // 6. Wrap in PhoenixMobReaderSupplier and load OnDiskGraphIndex
      try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuffer);
        OnDiskGraphIndex onDiskIndex = OnDiskGraphIndex.load(supplier)) {

        assertEquals(dimension, onDiskIndex.getDimension());
        assertEquals(numNodes, onDiskIndex.size());

        // Verify independent reader duplicate positioning
        try (RandomAccessReader r1 = supplier.get(); RandomAccessReader r2 = supplier.get()) {
          assertNotNull(r1);
          assertNotNull(r2);
          r1.seek(4);
          assertEquals(4, r1.getPosition());
          assertEquals(0, r2.getPosition());
        }

        // 7. Validate multi-threaded concurrent neighbor traversal
        int numThreads = 8;
        int queriesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int t = 0; t < numThreads; t++) {
          final int seed = 300 + t;
          tasks.add(() -> {
            Random rand = new Random(seed);
            try (OnDiskGraphIndex.View view = onDiskIndex.getView()) {
              for (int q = 0; q < queriesPerThread; q++) {
                int queryNode = rand.nextInt(numNodes);
                NodesIterator neighbors = view.getNeighborsIterator(0, queryNode);
                assertNotNull(neighbors);
                while (neighbors.hasNext()) {
                  int neighbor = neighbors.nextInt();
                  assertTrue("Neighbor ordinal out of bounds: " + neighbor,
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
      }
    }
  }
}
