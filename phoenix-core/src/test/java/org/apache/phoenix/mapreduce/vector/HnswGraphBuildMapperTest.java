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
package org.apache.phoenix.mapreduce.vector;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.disk.feature.FeatureId;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.phoenix.hbase.index.hnsw.PhoenixMobReaderSupplier;
import org.apache.phoenix.hbase.index.vector.HnswIndexManager;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.mapreduce.PhoenixJobCounters;
import org.apache.phoenix.mapreduce.index.PhoenixIndexDBWritable;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.junit.Test;

/**
 * Unit tests for {@link HnswGraphBuildMapper} verifying split invariants, input vector filtering,
 * deterministic row key generation, graph segment serialization, and output.
 */
public class HnswGraphBuildMapperTest {

  @Test
  public void testCoalescedSplitRejection() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);

    Configuration conf = new Configuration();
    conf.setBoolean("phoenix.hnsw.mapper.test.mode", true);
    when(mockContext.getConfiguration()).thenReturn(conf);

    // Create a coalesced PhoenixInputSplit containing 2 regional scans
    Scan scan1 = new Scan(Bytes.toBytes("a"), Bytes.toBytes("b"));
    Scan scan2 = new Scan(Bytes.toBytes("b"), Bytes.toBytes("c"));
    PhoenixInputSplit coalescedSplit = new PhoenixInputSplit(Arrays.asList(scan1, scan2));
    assertTrue("Split must be coalesced", coalescedSplit.isCoalesced());
    when(mockContext.getInputSplit()).thenReturn(coalescedSplit);

    try {
      mapper.setup(mockContext);
      fail("setup() should have thrown IllegalStateException for coalesced split");
    } catch (IllegalStateException e) {
      assertTrue("Exception message should indicate coalesced split rejection: " + e.getMessage(),
        e.getMessage().contains("coalesced split"));
    }
  }

  @Test
  public void testNullVectorSkipped() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    mapper.initForTesting(8, 4, 16, 1.2f, "COSINE", "IDX_TEST", Bytes.toBytes("r1"),
      Bytes.toBytes("r2"), "enc1");
    mapper.setVectorColumnIndex(0);

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    Counter mockCounter = mock(Counter.class);
    when(mockContext.getCounter(PhoenixJobCounters.INPUT_RECORDS)).thenReturn(mockCounter);

    PhoenixIndexDBWritable record = new PhoenixIndexDBWritable();
    List<Object> values = Arrays.asList(null, Bytes.toBytes("pk1"));
    record.setValues(values);

    mapper.map(NullWritable.get(), record, mockContext);

    assertEquals("No rows should be processed for null vector", 0, mapper.getTotalRowCount());
    assertTrue("Ordinal mapping should remain empty", mapper.getOrdinalToKey().isEmpty());
    assertEquals("Vector values store should remain empty", 0, mapper.getVectorValues().size());
    verify(mockCounter).increment(1);
  }

  @Test
  public void testDimensionMismatchSkipped() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    int expectedDim = 8;
    mapper.initForTesting(expectedDim, 4, 16, 1.2f, "COSINE", "IDX_TEST", Bytes.toBytes("r1"),
      Bytes.toBytes("r2"), "enc1");
    mapper.setVectorColumnIndex(0);

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    Counter mockCounter = mock(Counter.class);
    when(mockContext.getCounter(PhoenixJobCounters.INPUT_RECORDS)).thenReturn(mockCounter);

    // Vector with 4 dimensions instead of expected 8
    float[] underDimVector = new float[] { 1.0f, 2.0f, 3.0f, 4.0f };
    PhoenixIndexDBWritable recordUnder = new PhoenixIndexDBWritable();
    recordUnder.setValues(Arrays.asList(underDimVector, Bytes.toBytes("pk_under")));

    mapper.map(NullWritable.get(), recordUnder, mockContext);
    assertEquals("Under-dimensioned vector must be skipped", 0, mapper.getTotalRowCount());
    assertTrue("Ordinal mapping should remain empty", mapper.getOrdinalToKey().isEmpty());

    // Vector with 10 dimensions instead of expected 8
    float[] overDimVector = new float[] { 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f };
    PhoenixIndexDBWritable recordOver = new PhoenixIndexDBWritable();
    recordOver.setValues(Arrays.asList(overDimVector, Bytes.toBytes("pk_over")));

    mapper.map(NullWritable.get(), recordOver, mockContext);
    assertEquals("Over-dimensioned vector must be skipped", 0, mapper.getTotalRowCount());
    assertTrue("Ordinal mapping should remain empty", mapper.getOrdinalToKey().isEmpty());

    // Valid vector with exact dimension 8
    float[] validVector = new float[] { 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f };
    PhoenixIndexDBWritable recordValid = new PhoenixIndexDBWritable();
    recordValid.setValues(Arrays.asList(validVector, Bytes.toBytes("pk_valid")));

    mapper.map(NullWritable.get(), recordValid, mockContext);
    assertEquals("Valid vector must be accepted", 1, mapper.getTotalRowCount());
    assertEquals("Ordinal mapping must contain 1 entry", 1, mapper.getOrdinalToKey().size());
    assertArrayEquals("Primary key must match", Bytes.toBytes("pk_valid"),
      mapper.getOrdinalToKey().get(0));
  }

  @Test
  public void testSegmentSerializationRoundTrip() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    int dim = 8;
    int m = 4;
    int efConstruction = 16;
    float alpha = 1.2f;
    int numVectors = 50;

    mapper.initForTesting(dim, m, efConstruction, alpha, "COSINE", "IDX_TEST",
      Bytes.toBytes("startKey"), Bytes.toBytes("endKey"), "encodedRegionName");
    mapper.setVectorColumnIndex(0);

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    Counter mockCounter = mock(Counter.class);
    when(mockContext.getCounter(PhoenixJobCounters.INPUT_RECORDS)).thenReturn(mockCounter);

    Random rand = new Random(42);
    for (int i = 0; i < numVectors; i++) {
      float[] vec = new float[dim];
      for (int d = 0; d < dim; d++) {
        vec[d] = rand.nextFloat();
      }
      byte[] pk = Bytes.toBytes("pk_row_" + i);
      PhoenixIndexDBWritable record = new PhoenixIndexDBWritable();
      record.setValues(Arrays.asList(vec, pk));
      mapper.map(NullWritable.get(), record, mockContext);
    }

    assertEquals("Total row count must be 50", numVectors, mapper.getTotalRowCount());
    assertEquals("Ordinal mapping size must be 50", numVectors, mapper.getOrdinalToKey().size());

    // Finalize the in-memory graph builder before serialization
    mapper.getGraphBuilder().cleanup();

    // Serialize graph segment with embedded ordinal mapping trailer
    byte[] segmentBytes = mapper.serializeGraphSegment();
    assertNotNull("Serialized segment bytes must not be null", segmentBytes);
    assertTrue("Serialized segment bytes must be non-empty", segmentBytes.length > 0);

    // Split segment bytes into graph payload and deserialized ordinal mapping
    Pair<byte[], Map<Integer, byte[]>> split =
      HnswIndexManager.splitSegmentAndMapping(segmentBytes);
    assertNotNull("Split result must not be null", split);

    byte[] graphBytes = split.getFirst();
    Map<Integer, byte[]> deserializedMapping = split.getSecond();
    assertNotNull("Graph bytes must not be null", graphBytes);
    assertEquals("Deserialized mapping must contain 50 entries", numVectors,
      deserializedMapping.size());

    for (int i = 0; i < numVectors; i++) {
      assertArrayEquals("Deserialized primary key for ordinal " + i + " must match",
        Bytes.toBytes("pk_row_" + i), deserializedMapping.get(i));
    }

    // Load OnDiskGraphIndex via PhoenixMobReaderSupplier over an off-heap direct buffer
    ByteBuffer directBuffer = ByteBuffer.allocateDirect(graphBytes.length);
    directBuffer.put(graphBytes);
    directBuffer.flip();

    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuffer);
      OnDiskGraphIndex onDiskIndex = OnDiskGraphIndex.load(supplier)) {
      assertEquals("OnDiskGraphIndex dimension mismatch", dim, onDiskIndex.getDimension());
      assertEquals("OnDiskGraphIndex size must equal 50", numVectors, onDiskIndex.size());
    }
  }

  @Test
  public void testSegmentSerializationSQ8() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    int dim = 16;
    int m = 4;
    int efConstruction = 16;
    float alpha = 1.2f;
    int numVectors = 50;

    mapper.initForTesting(dim, m, efConstruction, alpha, "COSINE", "IDX_TEST_SQ8",
      Bytes.toBytes("startKey"), Bytes.toBytes("endKey"), "encRegion", "SQ8", null);
    mapper.setVectorColumnIndex(0);

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    when(mockContext.getCounter(PhoenixJobCounters.INPUT_RECORDS)).thenReturn(mock(Counter.class));

    Random rand = new Random(42);
    for (int i = 0; i < numVectors; i++) {
      float[] vec = new float[dim];
      for (int d = 0; d < dim; d++) {
        vec[d] = rand.nextFloat();
      }
      PhoenixIndexDBWritable record = new PhoenixIndexDBWritable();
      record.setValues(Arrays.asList(vec, Bytes.toBytes("pk_" + i)));
      mapper.map(NullWritable.get(), record, mockContext);
    }

    mapper.getGraphBuilder().cleanup();
    byte[] segmentBytes = mapper.serializeGraphSegment();
    assertNotNull(segmentBytes);

    Pair<byte[], Map<Integer, byte[]>> split =
      HnswIndexManager.splitSegmentAndMapping(segmentBytes);
    byte[] graphBytes = split.getFirst();
    ByteBuffer directBuf = ByteBuffer.allocateDirect(graphBytes.length);
    directBuf.put(graphBytes);
    directBuf.flip();

    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuf);
      OnDiskGraphIndex onDiskIndex = OnDiskGraphIndex.load(supplier)) {
      assertTrue("Must contain NVQ_VECTORS feature",
        onDiskIndex.getFeatureSet().contains(FeatureId.NVQ_VECTORS));
      assertEquals(numVectors, onDiskIndex.size());
    }

    // Verify search via HnswIndexManager
    Configuration conf = new Configuration();
    HnswIndexManager mgr = new HnswIndexManager("IDX_TEST_SQ8", Bytes.toBytes("encRegion"), conf,
      dim, VectorSimilarityFunction.COSINE, m, efConstruction, alpha);
    mgr.open();
    ByteBuffer segBuf = ByteBuffer.allocateDirect(segmentBytes.length);
    segBuf.put(segmentBytes);
    segBuf.flip();
    mgr.loadSegment(segBuf);

    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    float[] q = new float[dim];
    Arrays.fill(q, 0.5f);
    SearchResult sr = mgr.search(vts.createFloatVector(q), 5);
    assertNotNull("Search result must not be null", sr);
    assertNotNull("Search nodes must not be null", sr.getNodes());
    assertTrue("Search result must return candidates", sr.getNodes().length > 0);
    mgr.close();
  }

  @Test
  public void testSegmentSerializationPQ() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    int dim = 16;
    int m = 4;
    int efConstruction = 16;
    float alpha = 1.2f;
    int numVectors = 300;
    int pqSegments = 4;

    Random rand = new Random(42);
    List<float[]> samples = new java.util.ArrayList<>();
    for (int i = 0; i < numVectors; i++) {
      float[] vec = new float[dim];
      for (int d = 0; d < dim; d++) {
        vec[d] = rand.nextFloat();
      }
      samples.add(vec);
    }
    ProductQuantization codebook = HnswPqCodebookTrainer.trainCodebook(samples, dim, pqSegments);

    mapper.initForTesting(dim, m, efConstruction, alpha, "COSINE", "IDX_TEST_PQ",
      Bytes.toBytes("startKey"), Bytes.toBytes("endKey"), "encRegion", "PQ", codebook);
    mapper.setVectorColumnIndex(0);

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    when(mockContext.getCounter(PhoenixJobCounters.INPUT_RECORDS)).thenReturn(mock(Counter.class));

    for (int i = 0; i < numVectors; i++) {
      PhoenixIndexDBWritable record = new PhoenixIndexDBWritable();
      record.setValues(Arrays.asList(samples.get(i), Bytes.toBytes("pk_" + i)));
      mapper.map(NullWritable.get(), record, mockContext);
    }

    mapper.getGraphBuilder().cleanup();
    byte[] segmentBytes = mapper.serializeGraphSegment();
    assertNotNull(segmentBytes);

    Pair<byte[], Map<Integer, byte[]>> split =
      HnswIndexManager.splitSegmentAndMapping(segmentBytes);
    byte[] graphBytes = split.getFirst();
    ByteBuffer directBuf = ByteBuffer.allocateDirect(graphBytes.length);
    directBuf.put(graphBytes);
    directBuf.flip();

    try (PhoenixMobReaderSupplier supplier = new PhoenixMobReaderSupplier(directBuf);
      OnDiskGraphIndex onDiskIndex = OnDiskGraphIndex.load(supplier)) {
      assertTrue("Must contain FUSED_PQ feature",
        onDiskIndex.getFeatureSet().contains(FeatureId.FUSED_PQ));
      assertEquals(numVectors, onDiskIndex.size());
    }

    // Verify search via HnswIndexManager
    Configuration conf = new Configuration();
    HnswIndexManager mgr = new HnswIndexManager("IDX_TEST_PQ", Bytes.toBytes("encRegion"), conf,
      dim, VectorSimilarityFunction.COSINE, m, efConstruction, alpha);
    mgr.open();
    ByteBuffer segBuf = ByteBuffer.allocateDirect(segmentBytes.length);
    segBuf.put(segmentBytes);
    segBuf.flip();
    mgr.loadSegment(segBuf);

    VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    float[] q = samples.get(0);
    SearchResult sr = mgr.search(vts.createFloatVector(q), 5);
    assertNotNull("Search result must not be null", sr);
    assertNotNull("Search nodes must not be null", sr.getNodes());
    assertTrue("Search result must return candidates", sr.getNodes().length > 0);
    mgr.close();
  }

  @Test
  public void testGenerateSegmentRowKey() {
    String indexName = "SCHEMA.MY_HNSW_INDEX";
    byte[] startKey = Bytes.toBytes("region_start_001");
    long generationId = 0L;

    byte[] rowKey = HnswGraphBuildMapper.generateSegmentRowKey(indexName, startKey, generationId);
    assertNotNull("Generated row key must not be null", rowKey);

    // Verify expected structure: [indexName][0x00][startKey][0x00][generationId]
    byte[] indexBytes = Bytes.toBytes(indexName);
    byte[] genBytes = Bytes.toBytes(generationId);
    int expectedLen = indexBytes.length + 1 + startKey.length + 1 + genBytes.length;
    assertEquals("Row key length mismatch", expectedLen, rowKey.length);

    // Verify delimiters at expected positions
    assertEquals("First delimiter byte must be 0x00", 0x00, rowKey[indexBytes.length]);
    assertEquals("Second delimiter byte must be 0x00", 0x00,
      rowKey[indexBytes.length + 1 + startKey.length]);

    // Test with null start key
    byte[] rowKeyNullStart = HnswGraphBuildMapper.generateSegmentRowKey(indexName, null, 1L);
    int expectedNullStartLen = indexBytes.length + 1 + 1 + Bytes.toBytes(1L).length;
    assertEquals("Row key length for null start key mismatch", expectedNullStartLen,
      rowKeyNullStart.length);
  }

  @Test
  public void testExtractFloatVector() throws Exception {
    assertNull("Null input must return null", HnswGraphBuildMapper.extractFloatVector(null));

    // float[]
    float[] floats = new float[] { 1.5f, -2.5f, 3.0f };
    assertArrayEquals(floats, HnswGraphBuildMapper.extractFloatVector(floats), 0.0001f);

    // Float[]
    Float[] floatObjs = new Float[] { 1.5f, -2.5f, null };
    float[] expectedFromFloatObjs = new float[] { 1.5f, -2.5f, 0f };
    assertArrayEquals(expectedFromFloatObjs, HnswGraphBuildMapper.extractFloatVector(floatObjs),
      0.0001f);

    // double[]
    double[] doubles = new double[] { 1.1, 2.2, 3.3 };
    float[] expectedFromDoubles = new float[] { 1.1f, 2.2f, 3.3f };
    assertArrayEquals(expectedFromDoubles, HnswGraphBuildMapper.extractFloatVector(doubles),
      0.0001f);

    // Double[]
    Double[] doubleObjs = new Double[] { 1.1, null, 3.3 };
    float[] expectedFromDoubleObjs = new float[] { 1.1f, 0f, 3.3f };
    assertArrayEquals(expectedFromDoubleObjs, HnswGraphBuildMapper.extractFloatVector(doubleObjs),
      0.0001f);

    // PVectorFloat encoded byte[]
    byte[] pvectorBytes = PVectorFloat.INSTANCE.toBytes(floats);
    assertArrayEquals(floats, HnswGraphBuildMapper.extractFloatVector(pvectorBytes), 0.0001f);
  }

  @Test
  public void testCleanupEmptyRegionEmitsDummyOutput() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    mapper.initForTesting(8, 4, 16, 1.2f, "COSINE", "IDX_TEST", Bytes.toBytes("r1"),
      Bytes.toBytes("r2"), "enc1");

    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);
    assertEquals("Mapper total row count must be 0", 0, mapper.getTotalRowCount());

    mapper.cleanup(mockContext);

    // Context should receive exactly 1 write call emitting dummy IntWritable(0)
    verify(mockContext, times(1)).write(any(), any(IntWritable.class));
  }

  @Test
  public void testEmitDummyOutputOnlyOnce() throws Exception {
    HnswGraphBuildMapper mapper = new HnswGraphBuildMapper();
    HnswGraphBuildMapper.Context mockContext = mock(HnswGraphBuildMapper.Context.class);

    mapper.emitDummyOutput(mockContext);
    mapper.emitDummyOutput(mockContext);

    // Verify written only once despite two invocations
    verify(mockContext, times(1)).write(any(), any(IntWritable.class));
  }
}
