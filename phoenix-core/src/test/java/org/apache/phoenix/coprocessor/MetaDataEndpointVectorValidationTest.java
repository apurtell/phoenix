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
package org.apache.phoenix.coprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.schema.PTable;
import org.junit.Test;

public class MetaDataEndpointVectorValidationTest {

  @Test
  public void testValidHnswMetadata() throws Exception {
    // Valid defaults
    MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null, null,
      null, null, null);
    // Valid custom ranges
    MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "L2", 128, null, null, 16, 64, 1.2,
      "NONE", null);
    MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "INNER_PRODUCT", 768, null, null, 64,
      512, 2.0, "SQ8", null);
    MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 768, null, null, 4, 16, 1.0,
      "PQ", 16);
  }

  @Test
  public void testValidIvfMetadata() throws Exception {
    MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, null, null, null,
      null, null);
  }

  @Test
  public void testUnsupportedAlgorithm() {
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata(null, "COSINE", 128, null, null, null, null,
        null, null, null);
      fail("Should have failed for null algorithm");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_INDEX_ALGORITHM.getErrorCode(),
        e.getErrorCode());
    }

    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("SCANN", "COSINE", 128, null, null, null,
        null, null, null, null);
      fail("Should have failed for unsupported algorithm SCANN");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_INDEX_ALGORITHM.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testUnsupportedMetric() {
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", null, 128, null, null, null, null,
        null, null, null);
      fail("Should have failed for null metric");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_DISTANCE_METRIC.getErrorCode(),
        e.getErrorCode());
    }

    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "MANHATTAN", 128, null, null, null,
        null, null, null, null);
      fail("Should have failed for unsupported metric MANHATTAN");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_DISTANCE_METRIC.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testInvalidDimension() {
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 0, null, null, null, null,
        null, null, null);
      fail("Should have failed for dimension 0");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", -10, null, null, null,
        null, null, null, null);
      fail("Should have failed for negative dimension");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }
  }

  @Test
  public void testCrossAlgorithmHnswWithIvfParams() {
    // HNSW with lists
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, 10, null, null, null,
        null, null, null);
      fail("Should have failed for lists with HNSW");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }

    // HNSW with sample_size
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, 100, null, null,
        null, null, null);
      fail("Should have failed for sample_size with HNSW");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testCrossAlgorithmIvfWithHnswParams() {
    // IVF with M
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, 16, null, null,
        null, null);
      fail("Should have failed for M with IVF");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }

    // IVF with ef_construction
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, null, 64, null,
        null, null);
      fail("Should have failed for ef_construction with IVF");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }

    // IVF with alpha
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, null, null, 1.2,
        null, null);
      fail("Should have failed for alpha with IVF");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }

    // IVF with quantization
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, null, null, null,
        "SQ8", null);
      fail("Should have failed for quantization with IVF");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }

    // IVF with pq_segments
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("IVF", "L2", 128, 10, 100, null, null, null,
        null, 4);
      fail("Should have failed for pq_segments with IVF");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_ALGORITHM_PARAM_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testHnswMRangeValidation() {
    // M = 2 (< 4)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, 2, null,
        null, null, null);
      fail("Should have failed for M=2");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    // M = 100 (> 64)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, 100, null,
        null, null, null);
      fail("Should have failed for M=100");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }
  }

  @Test
  public void testHnswEfConstructionRangeValidation() {
    // ef_construction = 8 (< 16)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null, 8,
        null, null, null);
      fail("Should have failed for ef_construction=8");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    // ef_construction = 1000 (> 512)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        1000, null, null, null);
      fail("Should have failed for ef_construction=1000");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }
  }

  @Test
  public void testHnswAlphaRangeValidation() {
    // alpha = 0.5 (< 1.0)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, 0.5, null, null);
      fail("Should have failed for alpha=0.5");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    // alpha = 3.0 (> 2.0)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, 3.0, null, null);
      fail("Should have failed for alpha=3.0");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }
  }

  @Test
  public void testHnswQuantizationTypeValidation() {
    // Invalid quantization codec
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, null, "INVALID_CODEC", null);
      fail("Should have failed for invalid quantization codec");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testHnswPqSegmentsValidation() {
    // pq_segments = 0 (< 1)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, null, null, 0);
      fail("Should have failed for pq_segments=0");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    // pq_segments = 300 (> 256)
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, null, null, 300);
      fail("Should have failed for pq_segments=300");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }

    // Dimension not divisible by pq_segments: dim=128, pq=10
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata("HNSW", "COSINE", 128, null, null, null,
        null, null, null, 10);
      fail("Should have failed for dimension not divisible by pq_segments");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VECTOR_QUANTIZATION_DIMENSION_MISMATCH.getErrorCode(),
        e.getErrorCode());
    }
  }

  @Test
  public void testVectorIndexEncapsulationDelegation() throws Exception {
    PTable.VectorIndex validVi = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("COSINE").setDimension(128).setHnswM(16).setHnswEfConstruction(64)
      .setHnswAlpha(1.2).setQuantizationType("PQ").setPqSegments(16).build();
    MetaDataEndpointImpl.validateVectorIndexMetadata(validVi);

    PTable.VectorIndex invalidVi = new PTable.VectorIndex.Builder().setAlgorithm("HNSW")
      .setDistanceMetric("COSINE").setDimension(128).setHnswM(100).build();
    try {
      MetaDataEndpointImpl.validateVectorIndexMetadata(invalidVi);
      fail("Should have failed for invalid M in VectorIndex encapsulation");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS.getErrorCode(), e.getErrorCode());
    }
  }
}
