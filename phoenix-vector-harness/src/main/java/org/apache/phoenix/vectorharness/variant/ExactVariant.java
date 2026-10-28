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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.workload.Schema;

/**
 * The exact variant builds no vector index. Every query runs the exact distributed top-N plan: each
 * region ranks its rows server side and the client merges the per-region heaps. It is the baseline
 * that approximate indexes must improve on, and the cluster-side check of the ground truth: its
 * recall must be 1.
 */
public final class ExactVariant implements IndexVariant {

  /** The hint that keeps the planner off any index. */
  public static final String NO_INDEX = "NO_INDEX";

  @Override
  public String id() {
    return "exact";
  }

  @Override
  public List<String> validate(HarnessContext ctx, int dim, long prebuildRows) {
    return new ArrayList<>(CommonRules.validate(ctx));
  }

  @Override
  public List<String> warnings(HarnessContext ctx, int dim, long prebuildRows) {
    return Collections.emptyList();
  }

  @Override
  public String createIndexDdl(HarnessContext ctx, Schema schema, TableLayout layout) {
    return null;
  }

  @Override
  public Map<String, Object> build(HarnessContext ctx, Connection conn, Schema schema,
    TableLayout layout, EventLog events) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("index", "none");
    m.put("seconds", 0.0);
    return m;
  }

  @Override
  public List<SweepPoint> sweep(HarnessContext ctx, IndexInfo index) {
    return Collections.singletonList(defaultPoint(ctx, index));
  }

  @Override
  public SweepPoint defaultPoint(HarnessContext ctx, IndexInfo index) {
    return new SweepPoint("exact", 0, "exact", NO_INDEX);
  }

  @Override
  public PlanClass expectedPlan(QueryShape shape, boolean multiTenant) {
    return PlanClass.EXACT;
  }
}
