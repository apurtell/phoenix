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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.index.vector.ScorecardAccumulator;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.junit.Before;
import org.junit.Test;

public class IvfIndexManagerTest {

  private Configuration conf;
  private RegionCoprocessorEnvironment env;
  private PTable table;

  @Before
  public void setup() {
    conf = new Configuration();
    env = mock(RegionCoprocessorEnvironment.class);
    when(env.getConfiguration()).thenReturn(conf);
    table = mock(PTable.class);
    ScorecardAccumulator.getInstance(conf);
  }

  @Test
  public void testLifecycle() throws Exception {
    IvfIndexManager manager = new IvfIndexManager(env, table);

    assertEquals(VectorIndexType.IVF, manager.getType());
    assertFalse(manager.isInitialized());
    assertFalse(manager.isClosed());

    manager.open();
    assertTrue(manager.isInitialized());
    assertFalse(manager.isClosed());

    // Idempotent open
    manager.open();
    assertTrue(manager.isInitialized());

    manager.close();
    assertTrue(manager.isClosed());

    try {
      manager.open();
      fail("open() after close() should throw IllegalStateException");
    } catch (IllegalStateException expected) {
      // Expected
    }
  }

  @Test
  public void testOnMutationInsertAccumulatesClusterSize() throws Exception {
    String indexName = "IVF_IDX_INSERT";
    long generationId = 1L;
    int centroidId = 5;

    IndexMaintainer maintainer = mock(IndexMaintainer.class);
    when(maintainer.isVectorIndex()).thenReturn(true);
    when(maintainer.getLogicalIndexName()).thenReturn(indexName);
    when(maintainer.getVectorCentroidGeneration()).thenReturn(generationId);

    byte[] indexRow = Bytes.toBytes("index_row_c5");
    Put indexPut = new Put(indexRow);
    when(maintainer.extractCentroidId(indexRow)).thenReturn(centroidId);

    Put nextDataRowState = new Put(Bytes.toBytes("data_row_1"));

    IvfIndexManager manager = new IvfIndexManager(env, table);
    manager.open();

    long initialSize =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);

    // Insert mutation: currentDataRowState is null, nextDataRowState is non-null
    manager.onMutation(maintainer, null, nextDataRowState, null, indexPut, null, false, 1000L);

    long newSize =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);
    assertEquals(initialSize + 1L, newSize);

    manager.close();
  }

  @Test
  public void testOnMutationDeleteDecrementsClusterSize() throws Exception {
    String indexName = "IVF_IDX_DELETE";
    long generationId = 1L;
    int centroidId = 8;

    IndexMaintainer maintainer = mock(IndexMaintainer.class);
    when(maintainer.isVectorIndex()).thenReturn(true);
    when(maintainer.getLogicalIndexName()).thenReturn(indexName);
    when(maintainer.getVectorCentroidGeneration()).thenReturn(generationId);

    byte[] priorIndexRowKey = Bytes.toBytes("prior_index_row_c8");
    when(maintainer.extractCentroidId(priorIndexRowKey)).thenReturn(centroidId);

    Put currentDataRowState = new Put(Bytes.toBytes("data_row_2"));

    IvfIndexManager manager = new IvfIndexManager(env, table);
    manager.open();

    long initialSize =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);

    // Delete mutation: nextDataRowState is null, currentDataRowState is non-null
    manager.onMutation(maintainer, currentDataRowState, null, null, null, priorIndexRowKey, false,
      1000L);

    long newSize =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);
    assertEquals(initialSize - 1L, newSize);

    manager.close();
  }

  @Test
  public void testOnMutationCentroidReassignment() throws Exception {
    String indexName = "IVF_IDX_REASSIGN";
    long generationId = 1L;
    int priorCentroidId = 3;
    int arrivingCentroidId = 7;

    IndexMaintainer maintainer = mock(IndexMaintainer.class);
    when(maintainer.isVectorIndex()).thenReturn(true);
    when(maintainer.getLogicalIndexName()).thenReturn(indexName);
    when(maintainer.getVectorCentroidGeneration()).thenReturn(generationId);

    byte[] priorRowKey = Bytes.toBytes("prior_row_c3");
    byte[] arrivingRowKey = Bytes.toBytes("arriving_row_c7");
    Put indexPut = new Put(arrivingRowKey);

    when(maintainer.extractCentroidId(priorRowKey)).thenReturn(priorCentroidId);
    when(maintainer.extractCentroidId(arrivingRowKey)).thenReturn(arrivingCentroidId);

    Put currentDataRowState = new Put(Bytes.toBytes("data_row_3"));
    Put nextDataRowState = new Put(Bytes.toBytes("data_row_3"));

    IvfIndexManager manager = new IvfIndexManager(env, table);
    manager.open();

    long initialPriorSize = ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName,
      generationId, priorCentroidId);
    long initialArrivingSize = ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName,
      generationId, arrivingCentroidId);
    long initialReassign = ScorecardAccumulator.getInstance().getReassignCountDelta(indexName,
      generationId, arrivingCentroidId);

    // Update with changed centroid
    manager.onMutation(maintainer, currentDataRowState, nextDataRowState, null, indexPut,
      priorRowKey, false, 2000L);

    assertEquals(initialPriorSize - 1L, ScorecardAccumulator.getInstance()
      .getClusterSizeDelta(indexName, generationId, priorCentroidId));
    assertEquals(initialArrivingSize + 1L, ScorecardAccumulator.getInstance()
      .getClusterSizeDelta(indexName, generationId, arrivingCentroidId));
    assertEquals(initialReassign + 1L, ScorecardAccumulator.getInstance()
      .getReassignCountDelta(indexName, generationId, arrivingCentroidId));

    manager.close();
  }

  @Test
  public void testOnMutationSafelyCatchesExceptions() {
    IndexMaintainer maintainer = mock(IndexMaintainer.class);
    when(maintainer.isVectorIndex()).thenReturn(true);
    when(maintainer.getLogicalIndexName()).thenThrow(new RuntimeException("Simulated error"));

    IvfIndexManager manager = new IvfIndexManager(env, table);
    Put put = new Put(Bytes.toBytes("row"));

    // Should catch exception safely without throwing
    try {
      manager.onMutation(maintainer, null, put, put, null, false, 1000L);
    } catch (Throwable t) {
      fail("onMutation should catch exceptions safely: " + t.getMessage());
    }
  }

  @Test
  public void testOnMutationBypassesNonVectorIndex() throws Exception {
    String indexName = "IVF_IDX_BYPASS";
    long generationId = 1L;
    int centroidId = 10;

    IndexMaintainer maintainer = mock(IndexMaintainer.class);
    when(maintainer.isVectorIndex()).thenReturn(false);
    when(maintainer.getLogicalIndexName()).thenReturn(indexName);
    when(maintainer.getVectorCentroidGeneration()).thenReturn(generationId);

    byte[] indexRow = Bytes.toBytes("bypass_row_c10");
    Put indexPut = new Put(indexRow);
    when(maintainer.extractCentroidId(indexRow)).thenReturn(centroidId);

    Put nextDataRowState = new Put(Bytes.toBytes("data_row_bypass"));

    IvfIndexManager manager = new IvfIndexManager(env, table);
    manager.open();

    long sizeBefore =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);
    long reassignBefore =
      ScorecardAccumulator.getInstance().getReassignCountDelta(indexName, generationId, centroidId);

    // Non-vector index mutation — scorecard must NOT be updated
    manager.onMutation(maintainer, null, nextDataRowState, null, indexPut, null, false, 1000L);

    long sizeAfter =
      ScorecardAccumulator.getInstance().getClusterSizeDelta(indexName, generationId, centroidId);
    long reassignAfter =
      ScorecardAccumulator.getInstance().getReassignCountDelta(indexName, generationId, centroidId);
    assertEquals("Scorecard cluster size must be unchanged for non-vector index", sizeBefore,
      sizeAfter);
    assertEquals("Scorecard reassign count must be unchanged for non-vector index", reassignBefore,
      reassignAfter);

    manager.close();
  }

}
