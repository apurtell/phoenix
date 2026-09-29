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

import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.metrics.MetricsIndexerSourceFactory;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.index.vector.ScorecardAccumulator;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.util.IndexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Region-scoped manager for IVF vector index state. Coordinates centroid statistics and scorecard
 * accumulation for IVF vector indexes during region mutation processing.
 */
public class IvfIndexManager extends VectorIndexManager {

  private static final Logger LOG = LoggerFactory.getLogger(IvfIndexManager.class);

  private final RegionCoprocessorEnvironment env;
  private final PTable table;
  private volatile boolean initialized = false;
  private volatile boolean closed = false;

  public IvfIndexManager(RegionCoprocessorEnvironment env, PTable table) {
    this.env = env;
    this.table = table;
  }

  @Override
  public synchronized void open() throws IOException {
    if (closed) {
      throw new IllegalStateException("IvfIndexManager is closed");
    }
    if (initialized) {
      return;
    }
    Configuration conf = env != null ? env.getConfiguration() : new Configuration();
    ScorecardAccumulator.getInstance(conf);
    this.initialized = true;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    this.closed = true;
    try {
      ScorecardAccumulator.getInstance().flush();
    } catch (Exception ex) {
      LOG.warn("Failed to flush scorecard accumulator on close: {}", ex.getMessage());
    }
  }

  @Override
  public VectorIndexType getType() {
    return VectorIndexType.IVF;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void onMutation(IndexMaintainer indexMaintainer, Put currentDataRowState,
    Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut,
    byte[] indexRowKeyForCurrentDataRow, boolean isVectorUnchanged, long ts) {
    if (closed) {
      return;
    }
    updateVectorScorecardSafely(indexMaintainer, currentDataRowState, nextDataRowState,
      nextDataRowVG, indexPut, isVectorUnchanged, indexRowKeyForCurrentDataRow, ts);
  }

  /**
   * Best-effort scorecard update that catches exceptions to avoid failing the primary index
   * mutation.
   */
  public static void updateVectorScorecardSafely(IndexMaintainer indexMaintainer,
    Put currentDataRowState, Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut,
    boolean isVectorUnchanged, byte[] indexRowKeyForCurrentDataRow, long ts) {
    try {
      updateVectorScorecard(indexMaintainer, currentDataRowState, nextDataRowState, nextDataRowVG,
        indexPut, isVectorUnchanged, indexRowKeyForCurrentDataRow, ts);
    } catch (Throwable t) {
      String indexName = "UNKNOWN";
      try {
        indexName = indexMaintainer != null ? indexMaintainer.getLogicalIndexName() : "UNKNOWN";
      } catch (Throwable ignored) {
      }
      LOG.warn("Vector scorecard maintenance failed for index {}; counters will be corrected by "
        + "reconciliation.", indexName, t);
    }
  }

  /** Accumulates centroid statistics and updates scorecard for IVF vector indexes. */
  public static void updateVectorScorecard(IndexMaintainer indexMaintainer, Put currentDataRowState,
    Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut, boolean isVectorUnchanged,
    byte[] indexRowKeyForCurrentDataRow, long ts) {
    if (indexMaintainer == null || !indexMaintainer.isVectorIndex()) {
      return;
    }
    String indexName = indexMaintainer.getLogicalIndexName();
    Long genLong = indexMaintainer.getVectorCentroidGeneration();
    long generationId = genLong != null ? genLong : 1L;

    if (nextDataRowState != null && currentDataRowState == null) {
      // Insert: increment cluster size on assigned centroid
      if (indexPut != null) {
        Integer centroidId = indexMaintainer.extractCentroidId(indexPut.getRow());
        if (centroidId == null && nextDataRowVG != null) {
          centroidId = indexMaintainer.getCentroidId(nextDataRowVG, ts);
        }
        if (centroidId != null) {
          ScorecardAccumulator.getInstance().accumulate(indexName, generationId, centroidId, 1L,
            0L);
          MetricsIndexerSourceFactory.getInstance().getMetricsVectorIndexSource()
            .incrementVectorCentroidAssignments(indexName);
        }
      }
    } else if (nextDataRowState != null && currentDataRowState != null) {
      if (isVectorUnchanged) {
        // Update: vector unchanged
        return;
      }
      if (indexPut != null && indexRowKeyForCurrentDataRow != null) {
        if (Bytes.compareTo(indexPut.getRow(), indexRowKeyForCurrentDataRow) != 0) {
          // Update: centroid changed; update cluster sizes and increment reassign count
          Integer priorCentroidId = indexMaintainer.extractCentroidId(indexRowKeyForCurrentDataRow);
          if (priorCentroidId == null) {
            priorCentroidId = indexMaintainer
              .getCentroidId(new IndexUtil.SimpleValueGetter(currentDataRowState), ts);
          }
          Integer arrivingCentroidId = indexMaintainer.extractCentroidId(indexPut.getRow());
          if (arrivingCentroidId == null && nextDataRowVG != null) {
            arrivingCentroidId = indexMaintainer.getCentroidId(nextDataRowVG, ts);
          }
          if (priorCentroidId != null) {
            ScorecardAccumulator.getInstance().accumulate(indexName, generationId, priorCentroidId,
              -1L, 0L);
          }
          if (arrivingCentroidId != null) {
            ScorecardAccumulator.getInstance().accumulate(indexName, generationId,
              arrivingCentroidId, 1L, 1L);
            MetricsIndexerSourceFactory.getInstance().getMetricsVectorIndexSource()
              .incrementVectorCentroidAssignments(indexName);
            MetricsIndexerSourceFactory.getInstance().getMetricsVectorIndexSource()
              .incrementVectorCentroidReassignments(indexName);
          }
        }
        // Update: vector changed within same centroid
      } else if (indexPut != null && indexRowKeyForCurrentDataRow == null) {
        // Insert: vector added to existing row
        Integer arrivingCentroidId = indexMaintainer.extractCentroidId(indexPut.getRow());
        if (arrivingCentroidId == null && nextDataRowVG != null) {
          arrivingCentroidId = indexMaintainer.getCentroidId(nextDataRowVG, ts);
        }
        if (arrivingCentroidId != null) {
          ScorecardAccumulator.getInstance().accumulate(indexName, generationId, arrivingCentroidId,
            1L, 0L);
          MetricsIndexerSourceFactory.getInstance().getMetricsVectorIndexSource()
            .incrementVectorCentroidAssignments(indexName);
        }
      }
      // Vector removals that emit no index mutation are reconciled during periodic sweeps.
    } else if (nextDataRowState == null && currentDataRowState != null) {
      // Delete: decrement cluster size on prior centroid
      Integer priorCentroidId = null;
      if (indexRowKeyForCurrentDataRow != null) {
        priorCentroidId = indexMaintainer.extractCentroidId(indexRowKeyForCurrentDataRow);
      }
      if (priorCentroidId == null) {
        priorCentroidId =
          indexMaintainer.getCentroidId(new IndexUtil.SimpleValueGetter(currentDataRowState), ts);
      }
      if (priorCentroidId != null) {
        ScorecardAccumulator.getInstance().accumulate(indexName, generationId, priorCentroidId, -1L,
          0L);
      }
    }
  }

  public RegionCoprocessorEnvironment getEnvironment() {
    return env;
  }

  public PTable getTable() {
    return table;
  }
}
