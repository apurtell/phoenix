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
package org.apache.phoenix.vectorharness.variant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.util.PhoenixRuntime;

/** The catalog facts of a built vector index, read without the client metadata cache. */
public final class IndexInfo {

  private final String name;
  private final boolean exists;
  private final PIndexState state;
  private final String algorithm;
  private final String metric;
  private final Integer dimension;
  private final Integer ivfLists;
  private final Long activeGeneration;
  private final Long buildingGeneration;
  private final boolean rebuildInProgress;

  private IndexInfo(String name, PTable t) {
    this.name = name;
    this.exists = t != null;
    this.state = t == null ? null : t.getIndexState();
    PTable.VectorIndex v = t == null ? null : t.getVectorIndex();
    this.algorithm = v == null ? null : v.getAlgorithm();
    this.metric = v == null ? null : v.getDistanceMetric();
    this.dimension = v == null ? null : v.getDimension();
    this.ivfLists = t == null ? null : t.getVectorIvfLists();
    this.activeGeneration = t == null ? null : t.getVectorCentroidGeneration();
    this.buildingGeneration = t == null ? null : t.getVectorBuildingGeneration();
    this.rebuildInProgress = t != null && t.isVectorRebuildInProgress();
  }

  /** Reads an index; a missing index gives an info whose exists() is false. */
  public static IndexInfo read(Connection conn, String fullIndexName) throws SQLException {
    try {
      return new IndexInfo(fullIndexName, PhoenixRuntime.getTableNoCache(conn, fullIndexName));
    } catch (TableNotFoundException e) {
      return new IndexInfo(fullIndexName, null);
    }
  }

  public String name() {
    return name;
  }

  public boolean exists() {
    return exists;
  }

  public PIndexState state() {
    return state;
  }

  public boolean active() {
    return state == PIndexState.ACTIVE;
  }

  public String algorithm() {
    return algorithm;
  }

  public String metric() {
    return metric;
  }

  public Integer dimension() {
    return dimension;
  }

  /** Returns the effective IVF centroid count, which can exceed the requested lists. */
  public Integer ivfLists() {
    return ivfLists;
  }

  public Long activeGeneration() {
    return activeGeneration;
  }

  public Long buildingGeneration() {
    return buildingGeneration;
  }

  public boolean rebuildInProgress() {
    return rebuildInProgress;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("exists", exists);
    m.put("state", state == null ? null : state.name());
    m.put("algorithm", algorithm);
    m.put("metric", metric);
    m.put("dimension", dimension);
    m.put("ivf_lists", ivfLists);
    m.put("active_generation", activeGeneration);
    m.put("building_generation", buildingGeneration);
    m.put("rebuild_in_progress", rebuildInProgress);
    return m;
  }
}
