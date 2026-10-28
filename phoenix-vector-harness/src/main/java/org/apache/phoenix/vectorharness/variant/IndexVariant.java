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
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.workload.Schema;

/**
 * Abstraction encapsulating variant specific behaviors: configuration validation, index DDL
 * generation, build orchestration, sweep point definitions, and expected query plans.
 */
public interface IndexVariant {

  /** Returns variant identifier (e.g. "exact", "ivf", or "hnsw"). */
  String id();

  /**
   * Validates configuration parameters specific to this variant before execution.
   * @param dim          corpus vector dimension
   * @param prebuildRows row count loaded prior to index creation
   * @return validation failure messages, or empty list if valid
   */
  List<String> validate(HarnessContext ctx, int dim, long prebuildRows);

  /** Returns non-fatal warnings discovered during validation. */
  List<String> warnings(HarnessContext ctx, int dim, long prebuildRows);

  /** Generates CREATE VECTOR INDEX DDL statement for the layout, or null if unindexed. */
  String createIndexDdl(HarnessContext ctx, Schema schema, TableLayout layout);

  /**
   * Builds the vector index for a layout and waits until fully active.
   * @return build metrics and metadata for inclusion in run manifest
   */
  Map<String, Object> build(HarnessContext ctx, Connection conn, Schema schema, TableLayout layout,
    EventLog events) throws Exception;

  /** Returns query breadth sweep points for parameter sweeps. */
  List<SweepPoint> sweep(HarnessContext ctx, IndexInfo index);

  /** Returns engine default breadth configuration for steady-state and baseline checks. */
  SweepPoint defaultPoint(HarnessContext ctx, IndexInfo index);

  /** Returns expected execution plan class for a given query shape. */
  PlanClass expectedPlan(QueryShape shape, boolean multiTenant);

  /**
   * Returns how the index for a layout serves a filter on an attribute. The value follows the real
   * index definition of the layout, so expectations stay correct when the index includes no
   * columns.
   */
  FilterCoverage coverage(TableLayout layout, QueryCondition.Attribute attribute);
}
