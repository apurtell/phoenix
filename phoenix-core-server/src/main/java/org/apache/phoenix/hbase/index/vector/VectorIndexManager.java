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

import java.io.Closeable;
import java.io.IOException;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.util.IndexUtil;

/**
 * Region-scoped manager for vector index state. Each region hosting a vector index (or a data table
 * with vector indexes) obtains an instance via the factory method
 * {@link #create(VectorIndexType, RegionCoprocessorEnvironment, PTable)}.
 */
public abstract class VectorIndexManager implements Closeable {

  /**
   * Initializes region-scoped state (e.g. segment loading, recovery replay, cache priming).
   * @throws IOException if initialization fails
   */
  public abstract void open() throws IOException;

  /**
   * Releases region-scoped resources (e.g. buffers, background executors, flushing pending
   * updates).
   * @throws IOException if release fails
   */
  @Override
  public abstract void close() throws IOException;

  /**
   * Returns the vector index algorithm type supported by this manager.
   * @return the {@link VectorIndexType}
   */
  public abstract VectorIndexType getType();

  /**
   * Returns true if the manager has been initialized and is ready for use.
   */
  public abstract boolean isInitialized();

  /**
   * Returns true if the manager has been closed.
   */
  public abstract boolean isClosed();

  /**
   * Called after index mutations are generated for a single row. Implementations perform
   * algorithm-specific bookkeeping (e.g. scorecard updates for IVF, in-memory graph builder
   * upserts/deletes for HNSW).
   * @param indexMaintainer              the maintainer for this specific index
   * @param currentDataRowState          the current (pre-mutation) data row, or null for inserts
   * @param nextDataRowState             the next (post-mutation) data row, or null for deletes
   * @param nextDataRowVG                the value getter for the next data row, or null
   * @param indexPut                     the generated index Put mutation, or null if no Put was
   *                                     generated
   * @param indexRowKeyForCurrentDataRow the prior index row key, or null
   * @param isVectorUnchanged            true if the vector column value did not change
   * @param ts                           the mutation timestamp
   */
  public abstract void onMutation(IndexMaintainer indexMaintainer, Put currentDataRowState,
    Put nextDataRowState, ValueGetter nextDataRowVG, Put indexPut,
    byte[] indexRowKeyForCurrentDataRow, boolean isVectorUnchanged, long ts);

  /** Convenience overload when nextDataRowVG is not already constructed. */
  public void onMutation(IndexMaintainer indexMaintainer, Put currentDataRowState,
    Put nextDataRowState, Put indexPut, byte[] indexRowKeyForCurrentDataRow,
    boolean isVectorUnchanged, long ts) {
    ValueGetter nextDataRowVG =
      nextDataRowState != null ? new IndexUtil.SimpleValueGetter(nextDataRowState) : null;
    onMutation(indexMaintainer, currentDataRowState, nextDataRowState, nextDataRowVG, indexPut,
      indexRowKeyForCurrentDataRow, isVectorUnchanged, ts);
  }

  /**
   * Factory method to create the appropriate {@link VectorIndexManager} for the given algorithm.
   * @param type  the vector index algorithm type
   * @param env   the region coprocessor environment
   * @param table the PTable metadata for the vector index
   * @return the created {@link VectorIndexManager}
   */
  public static VectorIndexManager create(VectorIndexType type, RegionCoprocessorEnvironment env,
    PTable table) {
    if (type == null) {
      throw new IllegalArgumentException("VectorIndexType cannot be null");
    }
    switch (type) {
      case IVF:
        return new IvfIndexManager(env, table);
      case HNSW:
        return new HnswIndexManager(env, table);
      default:
        throw new IllegalArgumentException("Unsupported vector index type: " + type);
    }
  }
}
