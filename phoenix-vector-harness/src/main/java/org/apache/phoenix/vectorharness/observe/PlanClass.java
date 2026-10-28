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
package org.apache.phoenix.vectorharness.observe;

/**
 * The execution path that a nearest neighbor query plan takes. Measurements are recorded under the
 * class of the plan that ran, which may differ from the class the profile expects.
 */
public enum PlanClass {
  /** An IVF index scan that probes the posting lists of the nearest centroids. */
  IVF_PROBE("centroid-probing index scan"),
  /** An IVF index scan without centroid ranges, such as a global query on a multi-tenant index. */
  IVF_FULL_SCAN("full IVF index scan"),
  /** A server-side HNSW graph search in the data table regions. */
  HNSW_SEARCH("server HNSW search"),
  /** An exact top-N over rows that a selective relational filter or key range chose first. */
  FILTER_FIRST("filter-first relational scan"),
  /** An exact distributed top-N over the data table. */
  EXACT("exact brute-force top-N"),
  /** Anything else, such as a full sort without the vector top-N. */
  OTHER("other");

  private final String description;

  PlanClass(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }

  /** Returns true if the plan computes exact distances for every row that passes its filters. */
  public boolean exact() {
    return this == EXACT || this == FILTER_FIRST;
  }
}
