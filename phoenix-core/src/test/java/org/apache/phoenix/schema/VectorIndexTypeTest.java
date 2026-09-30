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
package org.apache.phoenix.schema;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.coprocessor.generated.PTableProtos;
import org.apache.phoenix.coprocessor.generated.ServerCachingProtos;
import org.apache.phoenix.hbase.index.covered.update.ColumnReference;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.IndexUtil;
import org.junit.Test;

/**
 * Unit tests for {@link PTable.IndexType#VECTOR_GLOBAL} serialization, conversion, and protobuf
 * representation.
 */
public class VectorIndexTypeTest {

  @Test
  public void testAllIndexTypesRoundTrip() {
    for (IndexType type : IndexType.values()) {
      assertEquals(type, IndexType.fromSerializedValue(type.getSerializedValue()));
    }
  }

  @Test
  public void testLegacyRejectionUnknownSerializedValue() {
    try {
      IndexType.fromSerializedValue((byte) 99);
      fail("Expected IllegalArgumentException for unknown serialized value 99");
    } catch (IllegalArgumentException e) {
      assertTrue("Exception message should mention invalid value", e.getMessage().contains("99"));
    }
  }

  @Test
  public void testBoundaryAndInvalidValuesRejection() {
    try {
      IndexType.fromSerializedValue((byte) 0);
      fail("Expected IllegalArgumentException for invalid serialized value 0");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("0"));
    }

    try {
      IndexType.fromSerializedValue((byte) -1);
      fail("Expected IllegalArgumentException for invalid serialized value -1");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("-1"));
    }

    try {
      IndexType.fromSerializedValue((byte) (IndexType.values().length + 1));
      fail("Expected IllegalArgumentException for out-of-bounds serialized value");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testFromToken() {
    assertEquals(IndexType.VECTOR_GLOBAL, IndexType.fromToken("VECTOR_GLOBAL"));
    assertEquals(IndexType.VECTOR_GLOBAL, IndexType.fromToken(" vector_global "));
    assertEquals(IndexType.GLOBAL, IndexType.fromToken("GLOBAL"));
    assertEquals(IndexType.LOCAL, IndexType.fromToken("LOCAL"));
    assertEquals(IndexType.UNCOVERED_GLOBAL, IndexType.fromToken("UNCOVERED_GLOBAL"));
  }

  @Test
  public void testGetBytes() {
    assertArrayEquals(Bytes.toBytes("VECTOR_GLOBAL"), IndexType.VECTOR_GLOBAL.getBytes());
    assertArrayEquals(Bytes.toBytes("GLOBAL"), IndexType.GLOBAL.getBytes());
    assertArrayEquals(Bytes.toBytes("LOCAL"), IndexType.LOCAL.getBytes());
    assertArrayEquals(Bytes.toBytes("UNCOVERED_GLOBAL"), IndexType.UNCOVERED_GLOBAL.getBytes());
  }

  @Test
  public void testPTableImplSerializationRoundTripWithVectorGlobal() throws Exception {
    PTable table = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_VEC_GLOBAL"))
      .setTableName(PNameFactory.newName("IDX_VEC_GLOBAL"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("IVF")
      .vectorDistanceMetric("COSINE").vectorDimension(128).vectorIvfLists(64)
      .vectorIvfSampleSize(2048).vectorCentroidGeneration(101L).build();

    assertEquals(IndexType.VECTOR_GLOBAL, table.getIndexType());

    PTableProtos.PTable proto = PTableImpl.toProto(table);
    assertNotNull(proto);
    assertTrue(proto.hasIndexType());
    assertEquals((byte) 4, proto.getIndexType().toByteArray()[0]);

    PTable deserialized = PTableImpl.fromProto(proto);
    assertNotNull(deserialized);
    assertEquals(IndexType.VECTOR_GLOBAL, deserialized.getIndexType());
    assertNotNull(deserialized.getVectorIndex());
    assertEquals("IVF", deserialized.getVectorIndex().getAlgorithm());
  }

  @Test
  public void testPTableImplSerializationRoundTripWithHnswVectorGlobal() throws Exception {
    PTable table = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_HNSW_GLOBAL"))
      .setTableName(PNameFactory.newName("IDX_HNSW_GLOBAL"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("HNSW")
      .vectorDistanceMetric("COSINE").vectorDimension(384).vectorHnswM(32)
      .vectorHnswEfConstruction(128).vectorHnswAlpha(1.2d).vectorQuantizationType("PQ")
      .vectorPqSegments(48).build();

    assertEquals(IndexType.VECTOR_GLOBAL, table.getIndexType());
    assertTrue(table.isVectorIndex());
    assertNotNull(table.getVectorIndex());

    PTableProtos.PTable proto = PTableImpl.toProto(table);
    assertNotNull(proto);
    assertTrue(proto.hasIndexType());
    assertEquals((byte) 4, proto.getIndexType().toByteArray()[0]);

    // Verify raw protobuf fields 65-69
    assertTrue(proto.hasVectorHnswM());
    assertEquals(32, proto.getVectorHnswM());
    assertTrue(proto.hasVectorHnswEfConstruction());
    assertEquals(128, proto.getVectorHnswEfConstruction());
    assertTrue(proto.hasVectorHnswAlpha());
    assertEquals(1.2d, proto.getVectorHnswAlpha(), 1e-6);
    assertTrue(proto.hasVectorQuantizationType());
    assertEquals("PQ", proto.getVectorQuantizationType());
    assertTrue(proto.hasVectorPqSegments());
    assertEquals(48, proto.getVectorPqSegments());

    // Verify common vector fields in proto
    assertTrue(proto.hasVectorIndexAlgorithm());
    assertEquals("HNSW", proto.getVectorIndexAlgorithm());
    assertTrue(proto.hasVectorDistanceMetric());
    assertEquals("COSINE", proto.getVectorDistanceMetric());
    assertTrue(proto.hasVectorDimension());
    assertEquals(384, proto.getVectorDimension());

    // Deserialize and verify state encapsulation via PTable.VectorIndex
    PTable deserialized = PTableImpl.fromProto(proto);
    assertNotNull(deserialized);
    assertEquals(IndexType.VECTOR_GLOBAL, deserialized.getIndexType());
    assertTrue(deserialized.isVectorIndex());
    PTable.VectorIndex vi = deserialized.getVectorIndex();
    assertNotNull(vi);
    assertEquals("HNSW", vi.getAlgorithm());
    assertEquals(VectorIndexType.HNSW, vi.getType());
    assertEquals("COSINE", vi.getDistanceMetric());
    assertEquals(Integer.valueOf(384), vi.getDimension());
    assertEquals(Integer.valueOf(32), vi.getHnswM());
    assertEquals(Integer.valueOf(128), vi.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.2d), vi.getHnswAlpha());
    assertEquals("PQ", vi.getQuantizationType());
    assertEquals(Integer.valueOf(48), vi.getPqSegments());
    assertNull(vi.getIvfLists());
    assertNull(vi.getIvfSampleSize());
    assertNull(vi.getCentroidGeneration());
  }

  @Test
  public void testSerializedPTableRefRoundTripWithHnswVectorIndex() throws Exception {
    PTable.VectorIndex vi = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("L2").setDimension(1536).setHnswM(64).setHnswEfConstruction(200)
      .setHnswAlpha(1.5d).setQuantizationType("SQ8").setPqSegments(16).build();

    PTable original = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_HNSW_REF"))
      .setTableName(PNameFactory.newName("IDX_HNSW_REF"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).setVectorIndex(vi).build();

    PTableRef pTableRef = SerializedPTableRefFactory.getFactory().makePTableRef(original, 0L, 0L);
    assertTrue("Factory must produce SerializedPTableRef",
      pTableRef instanceof SerializedPTableRef);

    PTable tableFromRef = pTableRef.getTable();
    assertNotNull(tableFromRef);
    assertTrue(tableFromRef.isVectorIndex());
    assertEquals(original.getVectorIndex(), tableFromRef.getVectorIndex());
    assertEquals(Integer.valueOf(64), tableFromRef.getVectorIndex().getHnswM());
    assertEquals(Integer.valueOf(200), tableFromRef.getVectorIndex().getHnswEfConstruction());
    assertEquals(Double.valueOf(1.5d), tableFromRef.getVectorIndex().getHnswAlpha());
    assertEquals("SQ8", tableFromRef.getVectorIndex().getQuantizationType());
    assertEquals(Integer.valueOf(16), tableFromRef.getVectorIndex().getPqSegments());
  }

  @Test
  public void testPTableSerializationRoundTripHnswDefaultsAndNulls() throws Exception {
    PTable table = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_HNSW_MINIMAL"))
      .setTableName(PNameFactory.newName("IDX_HNSW_MINIMAL"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("HNSW")
      .vectorDistanceMetric("L2").vectorDimension(128).vectorHnswM(16).vectorHnswEfConstruction(64)
      .build();

    PTableProtos.PTable proto = PTableImpl.toProto(table);
    assertNotNull(proto);
    assertTrue(proto.hasVectorHnswM());
    assertEquals(16, proto.getVectorHnswM());
    assertTrue(proto.hasVectorHnswEfConstruction());
    assertEquals(64, proto.getVectorHnswEfConstruction());
    assertFalse(proto.hasVectorHnswAlpha());
    assertFalse(proto.hasVectorQuantizationType());
    assertFalse(proto.hasVectorPqSegments());

    PTable deserialized = PTableImpl.fromProto(proto);
    assertNotNull(deserialized);
    PTable.VectorIndex vi = deserialized.getVectorIndex();
    assertNotNull(vi);
    assertEquals("HNSW", vi.getAlgorithm());
    assertEquals(Integer.valueOf(16), vi.getHnswM());
    assertEquals(Integer.valueOf(64), vi.getHnswEfConstruction());
    assertNull(vi.getHnswAlpha());
    assertNull(vi.getQuantizationType());
    assertNull(vi.getPqSegments());
  }

  @Test
  public void testPTableBuilderFromExistingHnsw() throws Exception {
    PTable original = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_HNSW_CLONE"))
      .setTableName(PNameFactory.newName("IDX_HNSW_CLONE"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("HNSW")
      .vectorDistanceMetric("INNER_PRODUCT").vectorDimension(256).vectorHnswM(32)
      .vectorHnswEfConstruction(128).vectorHnswAlpha(1.1d).vectorQuantizationType("FLAT")
      .vectorPqSegments(8).build();

    PTable cloned = PTableImpl.builderFromExisting(original).build();
    assertTrue(cloned.isVectorIndex());
    assertNotNull(cloned.getVectorIndex());
    assertEquals(original.getVectorIndex(), cloned.getVectorIndex());
    assertEquals("HNSW", cloned.getVectorIndex().getAlgorithm());
    assertEquals("INNER_PRODUCT", cloned.getVectorIndex().getDistanceMetric());
    assertEquals(Integer.valueOf(256), cloned.getVectorIndex().getDimension());
    assertEquals(Integer.valueOf(32), cloned.getVectorIndex().getHnswM());
    assertEquals(Integer.valueOf(128), cloned.getVectorIndex().getHnswEfConstruction());
    assertEquals(Double.valueOf(1.1d), cloned.getVectorIndex().getHnswAlpha());
    assertEquals("FLAT", cloned.getVectorIndex().getQuantizationType());
    assertEquals(Integer.valueOf(8), cloned.getVectorIndex().getPqSegments());
  }

  @Test
  public void testPTableVectorIndexInnerClassHnsw() throws Exception {
    PTable.VectorIndex vi1 =
      new PTable.VectorIndex("HNSW", "L2", 1536, null, null, null, 64, 200, 1.5d, "SQ8", 16);
    assertEquals("HNSW", vi1.getAlgorithm());
    assertEquals(VectorIndexType.HNSW, vi1.getType());
    assertEquals("L2", vi1.getDistanceMetric());
    assertEquals(Integer.valueOf(1536), vi1.getDimension());
    assertEquals(Integer.valueOf(64), vi1.getHnswM());
    assertEquals(Integer.valueOf(200), vi1.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.5d), vi1.getHnswAlpha());
    assertEquals("SQ8", vi1.getQuantizationType());
    assertEquals(Integer.valueOf(16), vi1.getPqSegments());

    PTable.VectorIndex vi2 = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("L2").setDimension(1536).setHnswM(64).setHnswEfConstruction(200)
      .setHnswAlpha(1.5d).setQuantizationType("SQ8").setPqSegments(16).build();
    assertEquals(vi1, vi2);
    assertEquals(vi1.hashCode(), vi2.hashCode());
    assertEquals(vi1.toString(), vi2.toString());

    // Test mergeWith
    PTable.VectorIndex serverVi = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("L2").setDimension(1536).setHnswM(16).setHnswEfConstruction(100)
      .setHnswAlpha(1.0d).setQuantizationType("FLAT").build();
    PTable.VectorIndex clientVi = new PTable.VectorIndex.Builder().setHnswM(32).build();
    PTable.VectorIndex merged = clientVi.mergeWith(serverVi);
    assertEquals("HNSW", merged.getAlgorithm());
    assertEquals("L2", merged.getDistanceMetric());
    assertEquals(Integer.valueOf(1536), merged.getDimension());
    assertEquals(Integer.valueOf(32), merged.getHnswM());
    assertEquals(Integer.valueOf(100), merged.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.0d), merged.getHnswAlpha());
    assertEquals("FLAT", merged.getQuantizationType());
  }

  @Test
  public void testDelegateTableWithVectorGlobal() throws Exception {
    PTable inner = new PTableImpl.Builder().setIndexType(IndexType.VECTOR_GLOBAL).build();

    DelegateTable delegate = new DelegateTable(inner);
    assertEquals(IndexType.VECTOR_GLOBAL, delegate.getIndexType());
  }

  @Test
  public void testIndexMaintainerWritableBackwardCompatibilityNonVectorIndex() throws Exception {
    RowKeySchema schema = new RowKeySchema.RowKeySchemaBuilder(0).build();
    IndexMaintainer maintainer = new IndexMaintainer(schema, false);
    assertFalse(maintainer.isVectorIndex());

    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    DataOutput output = new DataOutputStream(outStream);
    maintainer.write(output);

    byte[] bytes = outStream.toByteArray();
    IndexMaintainer deserialized = new IndexMaintainer(schema, false);
    DataInput input = new DataInputStream(new ByteArrayInputStream(bytes));
    deserialized.readFields(input);

    assertFalse("Non-vector index maintainer must have isVectorIndex == false",
      deserialized.isVectorIndex());
    assertNull(deserialized.getVectorAlgorithm());
    assertNull(deserialized.getVectorDimension());
    assertNull(deserialized.getDistanceMetric());
    assertNull(deserialized.getCentroidGeneration());
  }

  @Test
  public void testIndexMaintainerProtoRoundTripVectorFields() throws Exception {
    RowKeySchema schema = new RowKeySchema.RowKeySchemaBuilder(0).build();
    IndexMaintainer maintainer = new IndexMaintainer(schema, false);
    maintainer.setVectorAlgorithm("IVF");
    maintainer.setVectorDimension(128);
    maintainer.setDistanceMetric("L2");
    maintainer.setCentroidGeneration(1L);

    ServerCachingProtos.IndexMaintainer proto = IndexMaintainer.toProto(maintainer);
    assertNotNull(proto);
    assertTrue(proto.hasVectorAlgorithm());
    assertEquals("IVF", proto.getVectorAlgorithm());
    assertTrue(proto.hasVectorDimension());
    assertEquals(128, proto.getVectorDimension());
    assertTrue(proto.hasDistanceMetric());
    assertEquals("L2", proto.getDistanceMetric());
    assertTrue(proto.hasCentroidGeneration());
    assertEquals(1L, proto.getCentroidGeneration());

    IndexMaintainer deserialized = IndexMaintainer.fromProto(proto, schema, false);
    assertNotNull(deserialized);
    assertTrue(deserialized.isVectorIndex());
    assertEquals("IVF", deserialized.getVectorAlgorithm());
    assertEquals(Integer.valueOf(128), deserialized.getVectorDimension());
    assertEquals("L2", deserialized.getDistanceMetric());
    assertEquals(Long.valueOf(1L), deserialized.getCentroidGeneration());
  }

  @Test
  public void testClientVersionCheckOnIndexRead() throws Exception {
    PTable table = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_VEC_COMPAT"))
      .setTableName(PNameFactory.newName("IDX_VEC_COMPAT"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("IVF")
      .vectorDistanceMetric("L2").vectorDimension(128).build();

    assertEquals(IndexType.VECTOR_GLOBAL, table.getIndexType());

    PTableProtos.PTable proto = PTableImpl.toProto(table);
    assertNotNull(proto);
    assertTrue(proto.hasIndexType());
    byte serializedIndexType = proto.getIndexType().toByteArray()[0];
    assertEquals((byte) 4, serializedIndexType);

    // Verify that legacy clients lacking VECTOR_GLOBAL fail fast during index type deserialization.
    try {
      simulateLegacyClientIndexTypeDeserialization(serializedIndexType);
      fail("Expected IllegalArgumentException on legacy client without VECTOR_GLOBAL");
    } catch (IllegalArgumentException e) {
      assertTrue("Exception message should be descriptive: " + e.getMessage(),
        e.getMessage().contains("4") && e.getMessage().contains("IndexType"));
    }
  }

  private static IndexType simulateLegacyClientIndexTypeDeserialization(byte serializedValue) {
    // Legacy clients only recognize the first three IndexType enum ordinals.
    int legacyCount = 3;
    if (serializedValue < 1 || serializedValue > legacyCount) {
      throw new IllegalArgumentException("Invalid IndexType " + serializedValue
        + ". A client upgrade is required to support this index type.");
    }
    return IndexType.values()[serializedValue - 1];
  }

  @Test
  public void testIndexMaintainerFromProtoRejectsCentroidColumnWithoutVectorAlgorithm()
    throws Exception {
    RowKeySchema schema = new RowKeySchema.RowKeySchemaBuilder(0).build();
    IndexMaintainer maintainer = new IndexMaintainer(schema, false);
    ColumnReference centroidRef = new ColumnReference(ByteUtil.EMPTY_BYTE_ARRAY,
      Bytes.toBytes(IndexUtil.getIndexColumnName(null, PhoenixDatabaseMetaData.CENTROID_ID)));
    maintainer.setIndexedColumnsForTesting(Collections.singleton(centroidRef));

    ServerCachingProtos.IndexMaintainer proto = IndexMaintainer.toProto(maintainer);
    assertFalse("Proto must not have vectorAlgorithm", proto.hasVectorAlgorithm());

    try {
      IndexMaintainer.fromProto(proto, schema, false);
      fail(
        "Expected DoNotRetryIOException when proto has centroid column but lacks vectorAlgorithm");
    } catch (DoNotRetryIOException e) {
      assertTrue("Exception message must indicate server upgrade is required: " + e.getMessage(),
        e.getMessage().contains("Server upgrade is required")
          && e.getMessage().contains("vector maintainer fields"));
    }
  }

  @Test
  public void testPTableVectorIndexType() throws Exception {
    PTable ivfTable =
      new PTableImpl.Builder().setType(PTableType.INDEX).setIndexType(IndexType.VECTOR_GLOBAL)
        .setName(PNameFactory.newName("IDX_IVF")).setTableName(PNameFactory.newName("IDX_IVF"))
        .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
        .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
        .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("IVF").build();
    assertNotNull(ivfTable.getVectorIndex());
    assertEquals("IVF", ivfTable.getVectorIndex().getAlgorithm());
    assertEquals(VectorIndexType.IVF, ivfTable.getVectorIndex().getType());
    assertTrue(ivfTable.isVectorIndex());

    PTable hnswTable =
      new PTableImpl.Builder().setType(PTableType.INDEX).setIndexType(IndexType.VECTOR_GLOBAL)
        .setName(PNameFactory.newName("IDX_HNSW")).setTableName(PNameFactory.newName("IDX_HNSW"))
        .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
        .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
        .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("HNSW").build();
    assertNotNull(hnswTable.getVectorIndex());
    assertEquals("HNSW", hnswTable.getVectorIndex().getAlgorithm());
    assertEquals(VectorIndexType.HNSW, hnswTable.getVectorIndex().getType());
    assertTrue(hnswTable.isVectorIndex());

    PTable nonVectorTable = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.GLOBAL).setName(PNameFactory.newName("IDX_REGULAR"))
      .setTableName(PNameFactory.newName("IDX_REGULAR"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).build();
    assertNull(nonVectorTable.getVectorIndex());
    assertFalse(nonVectorTable.isVectorIndex());
  }

  @Test
  public void testIndexMaintainerAndPhoenixIndexMetaDataVectorIndexType() throws Exception {
    RowKeySchema schema = new RowKeySchema.RowKeySchemaBuilder(0).build();

    IndexMaintainer ivfMaintainer = new IndexMaintainer(schema, false);
    ivfMaintainer.setVectorAlgorithm("IVF");
    assertEquals(VectorIndexType.IVF, ivfMaintainer.getVectorIndexType());
    assertTrue(ivfMaintainer.isVectorIndex());

    IndexMaintainer hnswMaintainer = new IndexMaintainer(schema, false);
    hnswMaintainer.setVectorAlgorithm("HNSW");
    assertEquals(VectorIndexType.HNSW, hnswMaintainer.getVectorIndexType());
    assertTrue(hnswMaintainer.isVectorIndex());

    IndexMaintainer nonVectorMaintainer = new IndexMaintainer(schema, false);
    assertNull(nonVectorMaintainer.getVectorIndexType());
    assertFalse(nonVectorMaintainer.isVectorIndex());

    org.apache.phoenix.cache.IndexMetaDataCache ivfCache =
      new org.apache.phoenix.cache.IndexMetaDataCache() {
        @Override
        public void close() throws java.io.IOException {
        }

        @Override
        public List<IndexMaintainer> getIndexMaintainers() {
          return Collections.singletonList(ivfMaintainer);
        }

        @Override
        public org.apache.phoenix.transaction.PhoenixTransactionContext getTransactionContext() {
          return null;
        }

        @Override
        public int getClientVersion() {
          return 0;
        }
      };
    org.apache.phoenix.index.PhoenixIndexMetaData ivfMeta =
      new org.apache.phoenix.index.PhoenixIndexMetaData(ivfCache, Collections.emptyMap());
    assertEquals(VectorIndexType.IVF, ivfMeta.getVectorIndexType());
    assertTrue(ivfMeta.isVectorIndex());

    org.apache.phoenix.cache.IndexMetaDataCache hnswCache =
      new org.apache.phoenix.cache.IndexMetaDataCache() {
        @Override
        public void close() throws java.io.IOException {
        }

        @Override
        public List<IndexMaintainer> getIndexMaintainers() {
          return Collections.singletonList(hnswMaintainer);
        }

        @Override
        public org.apache.phoenix.transaction.PhoenixTransactionContext getTransactionContext() {
          return null;
        }

        @Override
        public int getClientVersion() {
          return 0;
        }
      };
    org.apache.phoenix.index.PhoenixIndexMetaData hnswMeta =
      new org.apache.phoenix.index.PhoenixIndexMetaData(hnswCache, Collections.emptyMap());
    assertEquals(VectorIndexType.HNSW, hnswMeta.getVectorIndexType());
    assertTrue(hnswMeta.isVectorIndex());

    org.apache.phoenix.cache.IndexMetaDataCache nonVectorCache =
      new org.apache.phoenix.cache.IndexMetaDataCache() {
        @Override
        public void close() throws java.io.IOException {
        }

        @Override
        public List<IndexMaintainer> getIndexMaintainers() {
          return Collections.singletonList(nonVectorMaintainer);
        }

        @Override
        public org.apache.phoenix.transaction.PhoenixTransactionContext getTransactionContext() {
          return null;
        }

        @Override
        public int getClientVersion() {
          return 0;
        }
      };
    org.apache.phoenix.index.PhoenixIndexMetaData nonVectorMeta =
      new org.apache.phoenix.index.PhoenixIndexMetaData(nonVectorCache, Collections.emptyMap());
    assertNull(nonVectorMeta.getVectorIndexType());
    assertFalse(nonVectorMeta.isVectorIndex());
  }

  @Test
  public void testPTableVectorIndexInnerClass() throws Exception {
    PTable.VectorIndex vi1 = new PTable.VectorIndex("IVF", "COSINE", 128, 64, 1000, 2L);
    assertEquals("IVF", vi1.getAlgorithm());
    assertEquals(VectorIndexType.IVF, vi1.getType());
    assertEquals("COSINE", vi1.getDistanceMetric());
    assertEquals(Integer.valueOf(128), vi1.getDimension());
    assertEquals(Integer.valueOf(64), vi1.getIvfLists());
    assertEquals(Integer.valueOf(1000), vi1.getIvfSampleSize());
    assertEquals(Long.valueOf(2L), vi1.getCentroidGeneration());

    PTable.VectorIndex vi2 =
      new PTable.VectorIndex.Builder().setAlgorithm("IVF").setDistanceMetric("COSINE")
        .setDimension(128).setIvfLists(64).setIvfSampleSize(1000).setCentroidGeneration(2L).build();
    assertEquals(vi1, vi2);
    assertEquals(vi1.hashCode(), vi2.hashCode());
    assertEquals(vi1.toString(), vi2.toString());

    PTable table =
      new PTableImpl.Builder().setType(PTableType.INDEX).setIndexType(IndexType.VECTOR_GLOBAL)
        .setName(PNameFactory.newName("IDX_TEST")).setTableName(PNameFactory.newName("IDX_TEST"))
        .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
        .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
        .setPhysicalNames(Collections.emptyList()).setVectorIndex(vi1).build();
    assertEquals(vi1, table.getVectorIndex());
    assertTrue(table.isVectorIndex());
  }

  @Test
  public void testFromAlgorithm() {
    assertEquals(VectorIndexType.IVF, VectorIndexType.fromAlgorithm("IVF"));
    assertEquals(VectorIndexType.IVF, VectorIndexType.fromAlgorithm("ivf"));
    assertEquals(VectorIndexType.IVF, VectorIndexType.fromAlgorithm("  IVF  "));
    assertEquals(VectorIndexType.HNSW, VectorIndexType.fromAlgorithm("HNSW"));
    assertEquals(VectorIndexType.HNSW, VectorIndexType.fromAlgorithm("hnsw"));
    assertEquals(VectorIndexType.HNSW, VectorIndexType.fromAlgorithm("  Hnsw \t"));
    assertNull(VectorIndexType.fromAlgorithm(null));
    assertNull(VectorIndexType.fromAlgorithm(""));
    assertNull(VectorIndexType.fromAlgorithm("UNKNOWN"));
  }

  @Test
  public void testDelegateTableVectorIndexDelegation() throws Exception {
    // 1. Non-vector table
    PTable nonVec = new PTableImpl.Builder().setIndexType(IndexType.GLOBAL).build();
    DelegateTable nonVecDelegate = new DelegateTable(nonVec);
    assertNull(nonVecDelegate.getVectorIndex());
    assertFalse(nonVecDelegate.isVectorIndex());
    assertNull(nonVecDelegate.getVectorIndexAlgorithm());

    // 2. HNSW vector table
    PTable.VectorIndex hnswVi = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("COSINE").setDimension(384).setHnswM(32).setHnswEfConstruction(128)
      .setHnswAlpha(1.2d).setQuantizationType("PQ").setPqSegments(48).build();
    PTable hnswTable = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setVectorIndex(hnswVi).build();
    DelegateTable hnswDelegate = new DelegateTable(hnswTable);
    assertEquals(IndexType.VECTOR_GLOBAL, hnswDelegate.getIndexType());
    assertTrue(hnswDelegate.isVectorIndex());
    assertEquals(hnswVi, hnswDelegate.getVectorIndex());
    assertEquals("HNSW", hnswDelegate.getVectorIndexAlgorithm());
    assertEquals(Integer.valueOf(32), hnswDelegate.getVectorIndex().getHnswM());
    assertEquals(Integer.valueOf(128), hnswDelegate.getVectorIndex().getHnswEfConstruction());
    assertEquals(Double.valueOf(1.2d), hnswDelegate.getVectorIndex().getHnswAlpha());
    assertEquals("PQ", hnswDelegate.getVectorIndex().getQuantizationType());
    assertEquals(Integer.valueOf(48), hnswDelegate.getVectorIndex().getPqSegments());

    // 3. IVF vector table
    PTable.VectorIndex ivfVi =
      new PTable.VectorIndex.Builder().setAlgorithm("IVF").setDistanceMetric("L2").setDimension(128)
        .setIvfLists(64).setIvfSampleSize(1000).setCentroidGeneration(5L).build();
    PTable ivfTable = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setVectorIndex(ivfVi).build();
    DelegateTable ivfDelegate = new DelegateTable(ivfTable);
    assertTrue(ivfDelegate.isVectorIndex());
    assertEquals(ivfVi, ivfDelegate.getVectorIndex());
    assertEquals("IVF", ivfDelegate.getVectorIndexAlgorithm());
    assertEquals(Integer.valueOf(64), ivfDelegate.getVectorIndex().getIvfLists());
    assertEquals(Long.valueOf(5L), ivfDelegate.getVectorIndex().getCentroidGeneration());
  }

  @Test
  public void testPTableVectorIndexBuilderAndAccessors() {
    // Empty builder
    assertNull(new PTable.VectorIndex.Builder().build());

    // Fluent builder with setter and alias methods
    PTable.VectorIndex.Builder builder =
      new PTable.VectorIndex.Builder().algorithm("HNSW").distanceMetric("COSINE").dimension(768)
        .hnswM(48).hnswEfConstruction(200).hnswAlpha(1.3d).quantizationType("SQ8").pqSegments(24)
        .ivfLists(100).ivfSampleSize(5000).centroidGeneration(42L);

    // Verify builder getters
    assertEquals("HNSW", builder.getAlgorithm());
    assertEquals("COSINE", builder.getDistanceMetric());
    assertEquals(Integer.valueOf(768), builder.getDimension());
    assertEquals(Integer.valueOf(48), builder.getHnswM());
    assertEquals(Integer.valueOf(200), builder.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.3d), builder.getHnswAlpha());
    assertEquals("SQ8", builder.getQuantizationType());
    assertEquals(Integer.valueOf(24), builder.getPqSegments());
    assertEquals(Integer.valueOf(100), builder.getIvfLists());
    assertEquals(Integer.valueOf(5000), builder.getIvfSampleSize());
    assertEquals(Long.valueOf(42L), builder.getCentroidGeneration());

    PTable.VectorIndex vi = builder.build();
    assertNotNull(vi);
    assertEquals("HNSW", vi.getAlgorithm());
    assertEquals(VectorIndexType.HNSW, vi.getType());
    assertEquals("COSINE", vi.getDistanceMetric());
    assertEquals(Integer.valueOf(768), vi.getDimension());
    assertEquals(Integer.valueOf(48), vi.getHnswM());
    assertEquals(Integer.valueOf(200), vi.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.3d), vi.getHnswAlpha());
    assertEquals("SQ8", vi.getQuantizationType());
    assertEquals(Integer.valueOf(24), vi.getPqSegments());
    assertEquals(Integer.valueOf(100), vi.getIvfLists());
    assertEquals(Integer.valueOf(5000), vi.getIvfSampleSize());
    assertEquals(Long.valueOf(42L), vi.getCentroidGeneration());

    // Copy constructor preserves all state
    PTable.VectorIndex.Builder copyBuilder = new PTable.VectorIndex.Builder(vi);
    PTable.VectorIndex viCopy = copyBuilder.build();
    assertEquals(vi, viCopy);
    assertEquals(vi.hashCode(), viCopy.hashCode());

    // Modifying copy builder does not affect original instance
    copyBuilder.hnswM(64);
    assertNotEquals(copyBuilder.build(), vi);
    assertEquals(Integer.valueOf(48), vi.getHnswM());
  }

  @Test
  public void testPTableVectorIndexFromTableProps() {
    assertNull(PTable.VectorIndex.fromTableProps(null));
    assertNull(PTable.VectorIndex.fromTableProps(Collections.emptyMap()));

    // Props with Number values
    Map<String, Object> numberProps = new HashMap<>();
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_INDEX_ALGORITHM, "HNSW");
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_DISTANCE_METRIC, "L2");
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_DIMENSION, 128);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_M, 16);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_EF_CONSTRUCTION, 64);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA, 1.15d);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_QUANTIZATION_TYPE, "FLAT");
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_PQ_SEGMENTS, 8);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_LISTS, 32);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_SAMPLE_SIZE, 1024);
    numberProps.put(PhoenixDatabaseMetaData.VECTOR_CENTROID_GENERATION, 7L);

    PTable.VectorIndex fromNum = PTable.VectorIndex.fromTableProps(numberProps);
    assertNotNull(fromNum);
    assertEquals("HNSW", fromNum.getAlgorithm());
    assertEquals(VectorIndexType.HNSW, fromNum.getType());
    assertEquals("L2", fromNum.getDistanceMetric());
    assertEquals(Integer.valueOf(128), fromNum.getDimension());
    assertEquals(Integer.valueOf(16), fromNum.getHnswM());
    assertEquals(Integer.valueOf(64), fromNum.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.15d), fromNum.getHnswAlpha());
    assertEquals("FLAT", fromNum.getQuantizationType());
    assertEquals(Integer.valueOf(8), fromNum.getPqSegments());
    assertEquals(Integer.valueOf(32), fromNum.getIvfLists());
    assertEquals(Integer.valueOf(1024), fromNum.getIvfSampleSize());
    assertEquals(Long.valueOf(7L), fromNum.getCentroidGeneration());

    // Props with String values
    Map<String, Object> stringProps = new HashMap<>();
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_INDEX_ALGORITHM, "HNSW");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_DISTANCE_METRIC, "COSINE");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_DIMENSION, "256");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_M, "32");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_EF_CONSTRUCTION, "128");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA, "1.5");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_QUANTIZATION_TYPE, "PQ");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_PQ_SEGMENTS, "16");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_LISTS, "64");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_IVF_SAMPLE_SIZE, "2048");
    stringProps.put(PhoenixDatabaseMetaData.VECTOR_CENTROID_GENERATION, "99");

    PTable.VectorIndex fromStr = PTable.VectorIndex.fromTableProps(stringProps);
    assertNotNull(fromStr);
    assertEquals("HNSW", fromStr.getAlgorithm());
    assertEquals(Integer.valueOf(256), fromStr.getDimension());
    assertEquals(Integer.valueOf(32), fromStr.getHnswM());
    assertEquals(Integer.valueOf(128), fromStr.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.5d), fromStr.getHnswAlpha());
    assertEquals("PQ", fromStr.getQuantizationType());
    assertEquals(Integer.valueOf(16), fromStr.getPqSegments());
    assertEquals(Integer.valueOf(64), fromStr.getIvfLists());
    assertEquals(Integer.valueOf(2048), fromStr.getIvfSampleSize());
    assertEquals(Long.valueOf(99L), fromStr.getCentroidGeneration());

    // Props with invalid strings are handled gracefully
    Map<String, Object> badProps = new HashMap<>();
    badProps.put(PhoenixDatabaseMetaData.VECTOR_DIMENSION, "invalid_num");
    badProps.put(PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA, "not_a_double");
    assertNull(PTable.VectorIndex.fromTableProps(badProps));
  }

  @Test
  public void testPTableVectorIndexEqualsAndHashCodeComprehensive() {
    PTable.VectorIndex base = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("COSINE").setDimension(384).setIvfLists(10).setIvfSampleSize(100)
      .setCentroidGeneration(1L).setHnswM(32).setHnswEfConstruction(128).setHnswAlpha(1.2d)
      .setQuantizationType("PQ").setPqSegments(48).build();

    PTable.VectorIndex same = new PTable.VectorIndex.Builder(base).build();
    assertEquals(base, base);
    assertEquals(base, same);
    assertEquals(base.hashCode(), same.hashCode());

    assertFalse(base.equals(null));
    assertFalse(base.equals("some_string"));

    // Check each field distinction
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setAlgorithm("IVF").build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setDistanceMetric("L2").build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setDimension(512).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setIvfLists(20).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setIvfSampleSize(200).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setCentroidGeneration(2L).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setHnswM(16).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setHnswEfConstruction(64).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setHnswAlpha(1.0d).build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setQuantizationType("FLAT").build());
    assertNotEquals(base, new PTable.VectorIndex.Builder(base).setPqSegments(12).build());
  }

  @Test
  public void testPTableImplBuilderVectorDelegationAndMutation() throws Exception {
    // 1. Build via individual fluent helper methods
    PTable table1 = new PTableImpl.Builder().setType(PTableType.INDEX)
      .setIndexType(IndexType.VECTOR_GLOBAL).setName(PNameFactory.newName("IDX_T1"))
      .setTableName(PNameFactory.newName("IDX_T1"))
      .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
      .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
      .setPhysicalNames(Collections.emptyList()).vectorIndexAlgorithm("HNSW")
      .vectorDistanceMetric("L2").vectorDimension(128).vectorHnswM(16).vectorHnswEfConstruction(64)
      .vectorHnswAlpha(1.1d).vectorQuantizationType("SQ8").vectorPqSegments(8).build();

    assertTrue(table1.isVectorIndex());
    assertEquals("HNSW", table1.getVectorIndexAlgorithm());
    PTable.VectorIndex vi1 = table1.getVectorIndex();
    assertNotNull(vi1);
    assertEquals("HNSW", vi1.getAlgorithm());
    assertEquals("L2", vi1.getDistanceMetric());
    assertEquals(Integer.valueOf(128), vi1.getDimension());
    assertEquals(Integer.valueOf(16), vi1.getHnswM());
    assertEquals(Integer.valueOf(64), vi1.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.1d), vi1.getHnswAlpha());
    assertEquals("SQ8", vi1.getQuantizationType());
    assertEquals(Integer.valueOf(8), vi1.getPqSegments());

    // 2. Set VectorIndex then mutate an individual property (e.g. vectorHnswM)
    PTable table2 =
      new PTableImpl.Builder().setType(PTableType.INDEX).setIndexType(IndexType.VECTOR_GLOBAL)
        .setName(PNameFactory.newName("IDX_T2")).setTableName(PNameFactory.newName("IDX_T2"))
        .setParentTableName(PNameFactory.newName("DATA_TBL")).setAllColumns(Collections.emptyList())
        .setPkColumns(Collections.emptyList()).setIndexes(Collections.emptyList())
        .setPhysicalNames(Collections.emptyList()).setVectorIndex(vi1).vectorHnswM(64) // overrides
                                                                                       // M from vi1
        .vectorHnswAlpha(1.5d) // overrides alpha from vi1
        .build();

    PTable.VectorIndex vi2 = table2.getVectorIndex();
    assertNotNull(vi2);
    assertEquals(Integer.valueOf(64), vi2.getHnswM());
    assertEquals(Double.valueOf(1.5d), vi2.getHnswAlpha());
    assertEquals("L2", vi2.getDistanceMetric());
    assertEquals(Integer.valueOf(128), vi2.getDimension());
    assertEquals(Integer.valueOf(64), vi2.getHnswEfConstruction());
    assertEquals("SQ8", vi2.getQuantizationType());
    assertEquals(Integer.valueOf(8), vi2.getPqSegments());

    // 3. Mutate via builderFromExisting
    PTable table3 = PTableImpl.builderFromExisting(table2).vectorHnswEfConstruction(256)
      .vectorQuantizationType("PQ").build();

    PTable.VectorIndex vi3 = table3.getVectorIndex();
    assertNotNull(vi3);
    assertEquals(Integer.valueOf(64), vi3.getHnswM());
    assertEquals(Integer.valueOf(256), vi3.getHnswEfConstruction());
    assertEquals("PQ", vi3.getQuantizationType());
    assertEquals(Double.valueOf(1.5d), vi3.getHnswAlpha());
  }

  @Test
  public void testPTableContractNoAlgorithmSpecificGetters() {
    // Verify that PTable and PTableImpl remain lean with no algorithm-specific getters exposed
    // directly on PTable or PTableImpl, adhering to vector metadata encapsulation.
    for (Method method : PTable.class.getMethods()) {
      String name = method.getName();
      assertFalse("PTable must not expose algorithm-specific getter: " + name,
        name.startsWith("getVectorHnsw") || name.startsWith("getVectorIvf")
          || name.equals("getVectorDimension") || name.equals("getVectorDistanceMetric"));
    }

    for (Method method : PTableImpl.class.getDeclaredMethods()) {
      String name = method.getName();
      if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        assertFalse("PTableImpl must not expose algorithm-specific public getter: " + name,
          name.startsWith("getVectorHnsw") || name.startsWith("getVectorIvf")
            || name.equals("getVectorDimension") || name.equals("getVectorDistanceMetric"));
      }
    }
  }

}
