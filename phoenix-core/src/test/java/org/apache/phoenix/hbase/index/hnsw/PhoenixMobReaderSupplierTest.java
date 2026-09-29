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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link PhoenixMobReaderSupplier} verifying:
 * <ul>
 * <li>Independent positioning and limit isolation across duplicated readers.</li>
 * <li>Multi-threaded read concurrency without position corruption.</li>
 * <li>Lifecycle management (close behavior).</li>
 * <li>Round-trip serialization/deserialization and concurrent querying with
 * {@link OnDiskGraphIndex}.</li>
 * </ul>
 */
public class PhoenixMobReaderSupplierTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test(expected = NullPointerException.class)
  public void testSupplierRejectsNullBuffer() {
    new PhoenixMobReaderSupplier(null);
  }

  @Test
  public void testReaderSupplierIndependentDuplicates() throws Exception {
    ByteBuffer buffer = ByteBuffer.allocateDirect(256);
    for (int i = 0; i < 64; i++) {
      buffer.putInt(i * 10);
    }
    buffer.flip();

    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(buffer)) {
      RandomAccessReader reader1 = supplier.get();
      RandomAccessReader reader2 = supplier.get();

      // Seek in reader1, reader2 must remain at position 0
      reader1.seek(40);
      assertEquals(40, reader1.getPosition());
      assertEquals(0, reader2.getPosition());

      // Read from reader1
      int val1 = reader1.readInt();
      assertEquals(100, val1);
      assertEquals(44, reader1.getPosition());
      assertEquals(0, reader2.getPosition());

      // Read from reader2
      int val2 = reader2.readInt();
      assertEquals(0, val2);
      assertEquals(4, reader2.getPosition());

      reader1.close();
      reader2.close();
    }
  }

  @Test
  public void testConcurrentReadersNoPositionCorruption() throws Exception {
    int numRecords = 2000;
    ByteBuffer buffer = ByteBuffer.allocateDirect(numRecords * Integer.BYTES);
    for (int i = 0; i < numRecords; i++) {
      buffer.putInt(i * 17 + 3);
    }
    buffer.flip();

    int numThreads = 16;
    int readsPerThread = 2000;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(buffer)) {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int t = 0; t < numThreads; t++) {
        final int seed = t;
        tasks.add(() -> {
          Random rand = new Random(seed);
          try (RandomAccessReader reader = supplier.get()) {
            for (int r = 0; r < readsPerThread; r++) {
              int recordIndex = rand.nextInt(numRecords);
              long offset = (long) recordIndex * Integer.BYTES;
              reader.seek(offset);
              int readVal = reader.readInt();
              int expectedVal = recordIndex * 17 + 3;
              if (readVal != expectedVal) {
                throw new AssertionError(
                  String.format("Position corruption detected: record=%d expected=%d actual=%d",
                    recordIndex, expectedVal, readVal));
              }
            }
          }
          return null;
        });
      }

      List<Future<Void>> futures = executor.invokeAll(tasks);
      for (Future<Void> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void testCloseBehavior() throws Exception {
    ByteBuffer buffer = ByteBuffer.allocateDirect(64);
    buffer.putInt(1);
    buffer.flip();

    PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(buffer);
    assertFalse(supplier.isClosed());

    // Can get reader before close
    RandomAccessReader reader = supplier.get();
    assertNotNull(reader);
    reader.close();

    // Close supplier
    supplier.close();
    assertTrue(supplier.isClosed());

    // Subsequent get() must throw IOException
    try {
      supplier.get();
      fail("Expected IOException after closing supplier");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("closed"));
    }
  }

  @Test
  public void testOnDiskGraphIndexRoundTripAndConcurrency() throws Exception {
    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    int dimension = 4;
    int numVectors = 100;

    List<VectorFloat<?>> vectors = new ArrayList<>(numVectors);
    Random rand = new Random(42);
    for (int i = 0; i < numVectors; i++) {
      float[] vec = new float[dimension];
      for (int d = 0; d < dimension; d++) {
        vec[d] = rand.nextFloat();
      }
      vectors.add(vts.createFloatVector(vec));
    }

    ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vectors, dimension);
    GraphIndexBuilder builder =
      new GraphIndexBuilder(ravv, VectorSimilarityFunction.COSINE, 16, 100, 1.2f, 1.4f, true);
    ImmutableGraphIndex builtGraph = builder.build(ravv);

    // Serialize graph index to a temporary file
    File tempFile = tempFolder.newFile("test_hnsw_graph.bin");
    Path tempPath = tempFile.toPath();
    OnDiskGraphIndex.write(builtGraph, ravv, tempPath);

    long fileLength = Files.size(tempPath);
    assertTrue("Serialized graph file should not be empty", fileLength > 0);

    // Load into off-heap direct ByteBuffer (simulating Phoenix MOB cell retrieval)
    ByteBuffer directBuffer = ByteBuffer.allocateDirect((int) fileLength);
    try (RandomAccessFile raf = new RandomAccessFile(tempFile, "r");
      FileChannel channel = raf.getChannel()) {
      while (directBuffer.hasRemaining()) {
        channel.read(directBuffer);
      }
    }
    directBuffer.flip();

    // Instantiate PhoenixMobReaderSupplier over the off-heap buffer
    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuffer);
      OnDiskGraphIndex onDiskIndex = OnDiskGraphIndex.load(supplier)) {

      assertEquals("OnDiskGraphIndex dimension mismatch", dimension, onDiskIndex.getDimension());
      assertEquals("OnDiskGraphIndex size mismatch", numVectors, onDiskIndex.size());

      // Validate concurrent queries using duplicated readers
      int numThreads = 8;
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);
      try {
        List<Callable<Void>> queryTasks = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
          final int threadId = t;
          queryTasks.add(() -> {
            Random threadRand = new Random(100 + threadId);
            // Each thread gets its own view (backed by independent ByteBufferReader)
            try (OnDiskGraphIndex.View view = onDiskIndex.getView()) {
              for (int q = 0; q < 50; q++) {
                int queryNode = threadRand.nextInt(numVectors);
                NodesIterator neighbors = view.getNeighborsIterator(0, queryNode);
                assertNotNull(neighbors);
                while (neighbors.hasNext()) {
                  int neighbor = neighbors.nextInt();
                  assertTrue("Neighbor node out of bounds: " + neighbor,
                    neighbor >= 0 && neighbor < numVectors);
                }
              }
            }
            return null;
          });
        }

        List<Future<Void>> futures = executor.invokeAll(queryTasks);
        for (Future<Void> future : futures) {
          future.get();
        }
      } finally {
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }
}
