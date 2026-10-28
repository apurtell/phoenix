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
import org.apache.phoenix.vectorharness.workload.Schema;

/**
 * The behavior that differs between the evaluated variants, behind one abstraction: profile
 * validation, index DDL, the build and the wait for ACTIVE, the sweep of query breadth and its
 * hints, and the plan that each query shape is expected to take. Workload definitions, ground
 * truth, scoring, and metrics collection are shared by all variants.
 */
public interface IndexVariant {

  /** Returns the variant name: exact, ivf, or hnsw. */
  String id();

  /**
   * Checks the profile against the rules of the variant before any DDL runs.
   * @param dim          the corpus dimension
   * @param prebuildRows the rows loaded before the index exists
   * @return the problems; empty when valid
   */
  List<String> validate(HarnessContext ctx, int dim, long prebuildRows);

  /** Returns warnings that do not stop the run, such as a training sample below sample_size. */
  List<String> warnings(HarnessContext ctx, int dim, long prebuildRows);

  /** Returns the CREATE VECTOR INDEX statement of a layout, or null if the variant has none. */
  String createIndexDdl(HarnessContext ctx, Schema schema, TableLayout layout);

  /**
   * Builds the index of a layout: issues the DDL, runs IndexTool when the variant needs it, and
   * waits until the index is ACTIVE and fully built.
   * @return facts about the build, for the build manifest
   */
  Map<String, Object> build(HarnessContext ctx, Connection conn, Schema schema, TableLayout layout,
    EventLog events) throws Exception;

  /** Returns the query breadth sweep, given the built index. */
  List<SweepPoint> sweep(HarnessContext ctx, IndexInfo index);

  /** Returns the engine default breadth, used by the steady state and the validity checks. */
  SweepPoint defaultPoint(HarnessContext ctx, IndexInfo index);

  /** Returns the plan class the profile expects for a query shape. */
  PlanClass expectedPlan(QueryShape shape, boolean multiTenant);
}
