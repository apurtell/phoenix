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
 * Execution path classifications for nearest neighbor query plans.
 */
public enum PlanClass {
  /** IVF index scan that probes posting lists of nearest centroids. */
  IVF_PROBE("centroid-probing index scan"),
  /** Full IVF index scan without centroid ranges. */
  IVF_FULL_SCAN("full IVF index scan"),
  /** Server-side HNSW graph search within data table regions. */
  HNSW_SEARCH("server HNSW search"),
  /** Exact top-N over rows selected by prior relational filter or key range. */
  FILTER_FIRST("filter-first relational scan"),
  /** Distributed exact brute-force top-N scan over data table. */
  EXACT("exact brute-force top-N"),
  /** Unclassified or fallback execution plan. */
  OTHER("other");

  private final String description;

  PlanClass(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }

  /** Returns true if the plan computes exact distances for candidate rows. */
  public boolean exact() {
    return this == EXACT || this == FILTER_FIRST;
  }
}
