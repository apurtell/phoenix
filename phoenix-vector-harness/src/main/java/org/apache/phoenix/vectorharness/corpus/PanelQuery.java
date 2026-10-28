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

/** One query vector of the evaluation panel. */
public final class PanelQuery {

  /** How a panel query was drawn. */
  public enum Kind {
    /** A held-out sample of the mixture. */
    IN_DISTRIBUTION,
    /** A point interpolated between two cluster centers. */
    BOUNDARY,
    /** A point from outside the mixture. */
    OUT_OF_DISTRIBUTION,
    /** A query of a published corpus. */
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

  /** Returns the position of the query in the panel. */
  public int index() {
    return index;
  }

  /** Returns the query vector. Callers must not modify it. */
  public float[] vector() {
    return vector;
  }

  public Kind kind() {
    return kind;
  }

  /** Returns the tenant ordinal that tenant-scoped forms of this query target. */
  public int tenant() {
    return tenant;
  }

  /** Returns the mixture component the query was drawn from, or -1. */
  public int cluster() {
    return cluster;
  }
}
