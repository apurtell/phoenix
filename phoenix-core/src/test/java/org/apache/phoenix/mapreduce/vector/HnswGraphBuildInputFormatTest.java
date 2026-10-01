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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.mapreduce.RegionSizeCalculator;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.TableRef;
import org.junit.Before;
import org.junit.Test;

public class HnswGraphBuildInputFormatTest {

  private QueryPlan mockQueryPlan;
  private TableRef mockTableRef;
  private PTable mockPTable;
  private PName mockPName;
  private Connection mockConnection;
  private RegionLocator mockRegionLocator;
  private Admin mockAdmin;
  private RegionSizeCalculator mockSizeCalculator;
  private Configuration configuration;

  @Before
  public void setUp() throws Exception {
    mockQueryPlan = mock(QueryPlan.class);
    mockTableRef = mock(TableRef.class);
    mockPTable = mock(PTable.class);
    mockPName = mock(PName.class);
    mockConnection = mock(Connection.class);
    mockRegionLocator = mock(RegionLocator.class);
    mockAdmin = mock(Admin.class);
    mockSizeCalculator = mock(RegionSizeCalculator.class);
    configuration = new Configuration();

    when(mockQueryPlan.getTableRef()).thenReturn(mockTableRef);
    when(mockTableRef.getTable()).thenReturn(mockPTable);
    when(mockPTable.getPhysicalName()).thenReturn(mockPName);
    when(mockPName.toString()).thenReturn("TEST_PHYSICAL_TABLE");
    when(mockConnection.getRegionLocator(any(TableName.class))).thenReturn(mockRegionLocator);
    when(mockConnection.getAdmin()).thenReturn(mockAdmin);
  }

  private HRegionLocation createMockRegionLocation(byte[] startKey, byte[] endKey,
    String encodedName, String hostname) {
    HRegionLocation location = mock(HRegionLocation.class);
    RegionInfo regionInfo = mock(RegionInfo.class);
    byte[] regionName = Bytes.toBytes("test_region_" + encodedName);

    when(location.getRegion()).thenReturn(regionInfo);
    when(location.getHostname()).thenReturn(hostname);
    when(regionInfo.getStartKey()).thenReturn(startKey);
    when(regionInfo.getEndKey()).thenReturn(endKey);
    when(regionInfo.getEncodedName()).thenReturn(encodedName);
    when(regionInfo.getRegionName()).thenReturn(regionName);
    when(mockSizeCalculator.getRegionSize(regionName)).thenReturn(1024L);

    return location;
  }

  private HnswGraphBuildInputFormat createTestInputFormat() {
    return new HnswGraphBuildInputFormat() {
      @Override
      protected void setupParallelScansFromQueryPlan(QueryPlan queryPlan) {
        // No-op for test since mockQueryPlan.getScans() is pre-configured
      }

      @Override
      protected Connection createConnection(Configuration config) throws IOException {
        return mockConnection;
      }

      @Override
      protected RegionSizeCalculator createRegionSizeCalculator(RegionLocator regionLocator,
        Admin admin) throws IOException {
        return mockSizeCalculator;
      }
    };
  }

  @Test
  public void testGenerateSplitsStrictOneToOneRegionAlignment() throws Exception {
    byte[] r1Start = HConstants.EMPTY_START_ROW;
    byte[] r1End = Bytes.toBytes("100");
    byte[] r2Start = Bytes.toBytes("100");
    byte[] r2End = Bytes.toBytes("200");
    byte[] r3Start = Bytes.toBytes("200");
    byte[] r3End = HConstants.EMPTY_END_ROW;

    HRegionLocation loc1 = createMockRegionLocation(r1Start, r1End, "enc_reg_1", "host1");
    HRegionLocation loc2 = createMockRegionLocation(r2Start, r2End, "enc_reg_2", "host2");
    HRegionLocation loc3 = createMockRegionLocation(r3Start, r3End, "enc_reg_3", "host3");

    when(mockRegionLocator.getRegionLocation(r1Start, false)).thenReturn(loc1);
    when(mockRegionLocator.getRegionLocation(r2Start, false)).thenReturn(loc2);
    when(mockRegionLocator.getRegionLocation(r3Start, false)).thenReturn(loc3);

    Scan s1 = new Scan().withStartRow(r1Start).withStopRow(r1End);
    Scan s2 = new Scan().withStartRow(r2Start).withStopRow(r2End);
    Scan s3 = new Scan().withStartRow(r3Start).withStopRow(r3End);

    List<List<Scan>> scans = new ArrayList<>();
    scans.add(Collections.singletonList(s1));
    scans.add(Collections.singletonList(s2));
    scans.add(Collections.singletonList(s3));
    when(mockQueryPlan.getScans()).thenReturn(scans);

    HnswGraphBuildInputFormat inputFormat = createTestInputFormat();
    List<InputSplit> splits = inputFormat.generateSplits(mockQueryPlan, configuration);

    assertNotNull(splits);
    assertEquals("Must produce exactly 3 splits for 3 regions", 3, splits.size());

    // Verify Region 1 Split
    PhoenixInputSplit split1 = (PhoenixInputSplit) splits.get(0);
    assertEquals(1, split1.getScans().size());
    assertFalse("Split must not be coalesced", split1.isCoalesced());
    Scan scan1 = split1.getScans().get(0);
    assertArrayEquals(r1Start,
      scan1.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR));
    assertArrayEquals(r1End,
      scan1.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR));
    assertEquals("enc_reg_1",
      Bytes.toString(scan1.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR)));

    // Verify Region 2 Split
    PhoenixInputSplit split2 = (PhoenixInputSplit) splits.get(1);
    assertEquals(1, split2.getScans().size());
    assertFalse("Split must not be coalesced", split2.isCoalesced());
    Scan scan2 = split2.getScans().get(0);
    assertArrayEquals(r2Start,
      scan2.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR));
    assertArrayEquals(r2End,
      scan2.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR));
    assertEquals("enc_reg_2",
      Bytes.toString(scan2.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR)));

    // Verify Region 3 Split
    PhoenixInputSplit split3 = (PhoenixInputSplit) splits.get(2);
    assertEquals(1, split3.getScans().size());
    assertFalse("Split must not be coalesced", split3.isCoalesced());
    Scan scan3 = split3.getScans().get(0);
    assertArrayEquals(r3Start,
      scan3.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR));
    assertArrayEquals(r3End,
      scan3.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR));
    assertEquals("enc_reg_3",
      Bytes.toString(scan3.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR)));
  }

  @Test
  public void testMultipleScansWithinSingleRegionAllReceiveAttributes() throws Exception {
    byte[] rStart = Bytes.toBytes("100");
    byte[] rEnd = Bytes.toBytes("200");
    HRegionLocation loc = createMockRegionLocation(rStart, rEnd, "enc_multi", "host1");
    when(mockRegionLocator.getRegionLocation(rStart, false)).thenReturn(loc);

    Scan subScan1 = new Scan().withStartRow(Bytes.toBytes("100")).withStopRow(Bytes.toBytes("150"));
    Scan subScan2 = new Scan().withStartRow(Bytes.toBytes("150")).withStopRow(Bytes.toBytes("200"));

    List<List<Scan>> scans = new ArrayList<>();
    scans.add(Arrays.asList(subScan1, subScan2));
    when(mockQueryPlan.getScans()).thenReturn(scans);

    HnswGraphBuildInputFormat inputFormat = createTestInputFormat();
    List<InputSplit> splits = inputFormat.generateSplits(mockQueryPlan, configuration);

    assertEquals(1, splits.size());
    PhoenixInputSplit split = (PhoenixInputSplit) splits.get(0);
    assertEquals(2, split.getScans().size());

    for (Scan scan : split.getScans()) {
      assertArrayEquals(rStart,
        scan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR));
      assertArrayEquals(rEnd,
        scan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR));
      assertEquals("enc_multi",
        Bytes.toString(scan.getAttribute(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR)));
    }
  }

  @Test
  public void testEmptyScansListSkipped() throws Exception {
    List<List<Scan>> scans = new ArrayList<>();
    scans.add(Collections.emptyList());
    when(mockQueryPlan.getScans()).thenReturn(scans);

    HnswGraphBuildInputFormat inputFormat = createTestInputFormat();
    List<InputSplit> splits = inputFormat.generateSplits(mockQueryPlan, configuration);

    assertNotNull(splits);
    assertEquals("Empty scans list must produce 0 splits", 0, splits.size());
  }

  @Test(expected = NullPointerException.class)
  public void testMissingRegionLocationThrows() throws Exception {
    byte[] rStart = Bytes.toBytes("100");
    Scan scan = new Scan().withStartRow(rStart);

    List<List<Scan>> scans = new ArrayList<>();
    scans.add(Collections.singletonList(scan));
    when(mockQueryPlan.getScans()).thenReturn(scans);
    when(mockRegionLocator.getRegionLocation(rStart, false)).thenReturn(null);

    HnswGraphBuildInputFormat inputFormat = createTestInputFormat();
    inputFormat.generateSplits(mockQueryPlan, configuration);
  }

  @Test
  public void testNullStartKeyAndNullStartRowHandling() throws Exception {
    byte[] rEnd = Bytes.toBytes("100");
    HRegionLocation loc = createMockRegionLocation(null, rEnd, "enc_first", "host1");
    when(mockRegionLocator.getRegionLocation(HConstants.EMPTY_START_ROW, false)).thenReturn(loc);

    Scan scan = mock(Scan.class);
    when(scan.getStartRow()).thenReturn(null);

    List<List<Scan>> scans = new ArrayList<>();
    scans.add(Collections.singletonList(scan));
    when(mockQueryPlan.getScans()).thenReturn(scans);

    HnswGraphBuildInputFormat inputFormat = createTestInputFormat();
    List<InputSplit> splits = inputFormat.generateSplits(mockQueryPlan, configuration);

    assertEquals(1, splits.size());
    PhoenixInputSplit split = (PhoenixInputSplit) splits.get(0);
    assertEquals(1, split.getScans().size());

    // Verify HNSW_REGION_START_KEY_ATTR was not set on the scan (remains null)
    verify(scan, never()).setAttribute(eq(HnswGraphBuildInputFormat.HNSW_REGION_START_KEY_ATTR),
      any(byte[].class));
    verify(scan).setAttribute(eq(HnswGraphBuildInputFormat.HNSW_REGION_END_KEY_ATTR), eq(rEnd));
    verify(scan).setAttribute(eq(HnswGraphBuildInputFormat.HNSW_REGION_ENCODED_NAME_ATTR),
      eq(Bytes.toBytes("enc_first")));
  }
}
