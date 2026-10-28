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
package org.apache.phoenix.vectorharness.corpus;

/** Evaluation query vector in the benchmark query panel. */
public final class PanelQuery {

  /** Origin or distribution category of a panel query. */
  public enum Kind {
    /** Held out sample drawn from the data distribution. */
    IN_DISTRIBUTION,
    /** Interpolated boundary point between cluster centers. */
    BOUNDARY,
    /** Out-of-distribution sample drawn outside cluster subspaces. */
    OUT_OF_DISTRIBUTION,
    /** Query vector from a published benchmark dataset. */
    PUBLISHED
  }

  private final int index;
  private final float[] vector;
  private final Kind kind;
  private final int tenant;
  private final int cluster;

  public PanelQuery(int index, float[] vector, Kind kind, int tenant, int cluster) {
    this.index = index;
    this.vector = vector;
    this.kind = kind;
    this.tenant = tenant;
    this.cluster = cluster;
  }

  /** Returns the query's zero-based panel index. */
  public int index() {
    return index;
  }

  /** Returns the query vector float array (callers must not modify). */
  public float[] vector() {
    return vector;
  }

  public Kind kind() {
    return kind;
  }

  /** Returns the tenant ordinal targeted by tenant scoped variations of this query. */
  public int tenant() {
    return tenant;
  }

  /** Returns the source cluster ID, or -1 if unclustered or out of distribution. */
  public int cluster() {
    return cluster;
  }
}
