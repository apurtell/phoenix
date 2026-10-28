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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.hadoop.conf.Configuration;
import org.apache.phoenix.mapreduce.index.IndexTool;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.Setting;

/** Rules and helpers that every variant shares. */
final class CommonRules {

  /** The client property of the sleep between the two passes of a synchronous index build. */
  static final String POPULATION_WAIT = "phoenix.index.population.wait.time";

  private CommonRules() {
  }

  /** Checks the rules that apply to every variant. */
  static List<String> validate(HarnessContext ctx) {
    HarnessConfig c = ctx.config();
    List<String> problems = new ArrayList<>();
    if (
      c.storageScheme().equals("SINGLE_CELL_ARRAY_WITH_OFFSETS") && c.mutationRate() > 0
        && c.mutationMix()[1] > 0
    ) {
      problems.add(Setting.TABLE_STORAGE_SCHEME.key() + "=SINGLE_CELL_ARRAY_WITH_OFFSETS makes the"
        + " tables immutable, so mutation.mix must have no update share");
    }
    if (
      c.layouts().contains(org.apache.phoenix.vectorharness.config.TableLayout.PROFILE)
        && ctx.corpus().dim() > 65536
    ) {
      problems.add("BSON vectors are limited to 65536 dimensions");
    }
    return problems;
  }

  /**
   * Returns the expected plan of the shapes that bind the leading key: on a table that is not
   * multi-tenant, TENANT_ID is the first key column, so a tenant equality or a key range is a
   * selective filter that the planner serves first with an exact scan. Returns null otherwise.
   */
  static org.apache.phoenix.vectorharness.observe.PlanClass keyBoundPlan(QueryShape shape,
    boolean multiTenant) {
    if (!multiTenant && (shape == QueryShape.TENANT_SCOPED || shape == QueryShape.PK_RANGE)) {
      return org.apache.phoenix.vectorharness.observe.PlanClass.FILTER_FIRST;
    }
    return null;
  }

  /**
   * Returns a sweep with the default breadth in it, in order of breadth, unless the sweep already
   * holds that breadth. The steady state runs at the default breadth, so the frontier measures it
   * too: without writes, it is the baseline of the latency the writes add.
   */
  static List<SweepPoint> withDefault(List<SweepPoint> sweep, SweepPoint def) {
    int at = 0;
    for (SweepPoint p : sweep) {
      if (p.value() == def.value()) {
        return sweep;
      }
      if (p.value() < def.value()) {
        at++;
      }
    }
    List<SweepPoint> out = new ArrayList<>(sweep);
    out.add(Math.min(at, out.size()), def);
    return out;
  }

  /** Runs IndexTool for an index in this JVM and returns its exit status. */
  static int runIndexTool(Connection conn, String schema, String table, String index)
    throws Exception {
    IndexTool tool = new IndexTool();
    tool.setConf(new Configuration(HarnessContext.configuration(conn)));
    List<String> args = new ArrayList<>();
    args.add("--schema=" + schema);
    args.add("--data-table=" + table);
    args.add("--index-table=" + index);
    args.add("-v");
    args.add(IndexTool.IndexVerifyType.NONE.getValue());
    args.add("-runfg");
    return tool.run(args.toArray(new String[0]));
  }

  /** Polls a condition every interval until it holds or the timeout passes. */
  static boolean await(BooleanSupplier condition, long timeoutMillis, long intervalMillis)
    throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.currentTimeMillis() >= deadline) {
        return false;
      }
      Thread.sleep(intervalMillis);
    }
  }

  /** Adds a profile value to a WITH option list, quoting strings. */
  static void option(Map<String, String> options, String name, Object value) {
    if (value instanceof Number) {
      options.put(name, value.toString());
    } else {
      options.put(name, "'" + value + "'");
    }
  }

  /** Renders a WITH clause from options. */
  static String withClause(Map<String, String> options) {
    StringBuilder sb = new StringBuilder(" WITH (");
    boolean first = true;
    for (Map.Entry<String, String> e : options.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(e.getKey()).append(" = ").append(e.getValue());
    }
    return sb.append(')').toString();
  }

  /** Returns true if a SQL exception is a timeout or a transient error worth retrying. */
  static boolean transientFailure(SQLException e) {
    String m = String.valueOf(e.getMessage());
    return m.contains("timeout") || m.contains("Timeout");
  }
}
