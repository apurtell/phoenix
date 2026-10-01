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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.mapreduce.RegionSizeCalculator;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.mapreduce.PhoenixInputFormat;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.mapreduce.index.PhoenixIndexDBWritable;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.query.HBaseFactoryProvider;
import org.apache.phoenix.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * {@link PhoenixInputFormat} for bulk HNSW vector index construction.
 * <p>
 * Enforces strict 1:1 alignment between input splits and base table regions, ensuring each mapper
 * builds exactly one HNSW graph segment covering that region's key space.
 * <p>
 * In addition, this class attaches region start key, end key, and encoded name as scan attributes
 * on each scan in the split, allowing the mapper to record segment metadata in
 * {@code SYSTEM.VECTOR_GRAPH_SEGMENT} without direct access to HBase cluster metadata.
 */
public class HnswGraphBuildInputFormat extends PhoenixInputFormat<PhoenixIndexDBWritable> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphBuildInputFormat.class);

  // Scan attribute keys
  public static final String HNSW_REGION_START_KEY_ATTR = "phoenix.hnsw.region.startkey";
  public static final String HNSW_REGION_END_KEY_ATTR = "phoenix.hnsw.region.endkey";
  public static final String HNSW_REGION_ENCODED_NAME_ATTR = "phoenix.hnsw.region.encoded";

  /**
   * Instantiated by MapReduce framework via reflection.
   */
  public HnswGraphBuildInputFormat() {
    super();
  }

  @Override
  protected List<InputSplit> generateSplits(QueryPlan qplan, Configuration config)
    throws IOException {
    setupParallelScansFromQueryPlan(qplan);
    List<InputSplit> splits = new ArrayList<>();

    try (Connection hConn = createConnection(config)) {
      RegionLocator regionLocator = hConn.getRegionLocator(
        TableName.valueOf(qplan.getTableRef().getTable().getPhysicalName().toString()));
      RegionSizeCalculator sizeCalc = createRegionSizeCalculator(regionLocator, hConn.getAdmin());

      for (List<Scan> regionScans : qplan.getScans()) {
        if (regionScans == null || regionScans.isEmpty()) {
          continue;
        }
        // Each List<Scan> corresponds to one region.
        // Force each into its own InputSplit — never coalesce.
        byte[] scanStartRow = regionScans.get(0).getStartRow();
        if (scanStartRow == null) {
          scanStartRow = HConstants.EMPTY_START_ROW;
        }
        HRegionLocation location = regionLocator.getRegionLocation(scanStartRow, false);
        Preconditions.checkNotNull(location, "Failed to get region location for scan start row");
        long regionSize = sizeCalc.getRegionSize(location.getRegion().getRegionName());

        // Set the region start/end keys as scan attributes so the mapper
        // can identify the region boundaries for segment metadata.
        byte[] regionStartKey = location.getRegion().getStartKey();
        byte[] regionEndKey = location.getRegion().getEndKey();
        String regionEncodedName = location.getRegion().getEncodedName();

        byte[] endKeyAttr = regionEndKey != null ? regionEndKey : HConstants.EMPTY_END_ROW;
        byte[] encodedNameAttr =
          regionEncodedName != null ? Bytes.toBytes(regionEncodedName) : ByteUtil.EMPTY_BYTE_ARRAY;

        for (Scan scan : regionScans) {
          if (regionStartKey != null) {
            scan.setAttribute(HNSW_REGION_START_KEY_ATTR, regionStartKey);
          }
          scan.setAttribute(HNSW_REGION_END_KEY_ATTR, endKeyAttr);
          scan.setAttribute(HNSW_REGION_ENCODED_NAME_ATTR, encodedNameAttr);
        }

        splits.add(new PhoenixInputSplit(regionScans, regionSize, location.getHostname()));
      }
    }

    if (PhoenixConfigurationUtil.isMRRandomizeMapperExecutionOrder(config)) {
      randomizeSplitLength(splits);
    }

    return splits;
  }

  protected Connection createConnection(Configuration config) throws IOException {
    return HBaseFactoryProvider.getHConnectionFactory().createConnection(config);
  }

  protected RegionSizeCalculator createRegionSizeCalculator(RegionLocator regionLocator,
    Admin admin) throws IOException {
    return new RegionSizeCalculator(regionLocator, admin);
  }
}
