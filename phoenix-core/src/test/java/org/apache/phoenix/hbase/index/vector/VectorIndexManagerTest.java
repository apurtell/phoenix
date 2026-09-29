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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.junit.Test;

public class VectorIndexManagerTest {

  @Test
  public void testFactoryCreateIvf() {
    PTable table = mock(PTable.class);
    RegionCoprocessorEnvironment env = mock(RegionCoprocessorEnvironment.class);
    when(env.getConfiguration()).thenReturn(new Configuration());

    VectorIndexManager manager = VectorIndexManager.create(VectorIndexType.IVF, env, table);
    assertNotNull(manager);
    assertTrue(manager instanceof IvfIndexManager);
    assertEquals(VectorIndexType.IVF, manager.getType());
    assertFalse(manager.isInitialized());
    assertFalse(manager.isClosed());
  }

  @Test
  public void testFactoryCreateHnsw() {
    PTable table = mock(PTable.class);
    PName tableName = mock(PName.class);
    when(tableName.getString()).thenReturn("MY_HNSW_INDEX");
    when(table.getName()).thenReturn(tableName);

    RegionCoprocessorEnvironment env = mock(RegionCoprocessorEnvironment.class);
    Region region = mock(Region.class);
    RegionInfo regionInfo = mock(RegionInfo.class);
    TableDescriptor tableDesc = mock(TableDescriptor.class);
    when(regionInfo.getEncodedNameAsBytes()).thenReturn(Bytes.toBytes("enc-1"));
    when(region.getRegionInfo()).thenReturn(regionInfo);
    when(region.getTableDescriptor()).thenReturn(tableDesc);
    when(tableDesc.getColumnFamilies())
      .thenReturn(new org.apache.hadoop.hbase.client.ColumnFamilyDescriptor[0]);
    when(env.getRegion()).thenReturn(region);
    when(env.getConfiguration()).thenReturn(new Configuration());

    VectorIndexManager manager = VectorIndexManager.create(VectorIndexType.HNSW, env, table);
    assertNotNull(manager);
    assertTrue(manager instanceof HnswIndexManager);
    assertEquals(VectorIndexType.HNSW, manager.getType());
    assertFalse(manager.isInitialized());
    assertFalse(manager.isClosed());
  }

  @Test
  public void testFactoryNullTypeThrows() {
    try {
      VectorIndexManager.create(null, null, null);
      fail("Null VectorIndexType should throw IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // Expected
    }
  }

  @Test
  public void testOverloadedOnMutationDelegation() {
    final boolean[] invoked = new boolean[] { false };
    VectorIndexManager manager = new VectorIndexManager() {
      @Override
      public void open() {
      }

      @Override
      public void close() {
      }

      @Override
      public VectorIndexType getType() {
        return VectorIndexType.IVF;
      }

      @Override
      public boolean isInitialized() {
        return true;
      }

      @Override
      public boolean isClosed() {
        return false;
      }

      @Override
      public void onMutation(IndexMaintainer indexMaintainer, Put currentDataRowState,
        Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut,
        byte[] indexRowKeyForCurrentDataRow, boolean isVectorUnchanged, long ts) {
        invoked[0] = true;
        assertNotNull(nextDataRowVG);
      }
    };

    Put nextPut = new Put(Bytes.toBytes("row1"));
    manager.onMutation(null, null, nextPut, null, null, false, 1000L);
    assertTrue("7-arg onMutation must delegate to 8-arg onMutation", invoked[0]);
  }
}
