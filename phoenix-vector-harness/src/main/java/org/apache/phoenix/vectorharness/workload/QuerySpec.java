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
package org.apache.phoenix.vectorharness.workload;

import java.util.Collections;
import java.util.List;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.variant.SweepPoint;

/**
 * One nearest neighbor query, ready to run: its SQL, its bind values in order, the tenant whose
 * connection must run it (or null for a global connection), and what it was built from. The query
 * vector is a bind value of type float[].
 */
public final class QuerySpec {

  private final String sql;
  private final List<Object> binds;
  private final String tenantId;
  private final TableLayout layout;
  private final PanelQuery query;
  private final QueryCondition condition;
  private final int k;
  private final SweepPoint sweep;

  public QuerySpec(String sql, List<Object> binds, String tenantId, TableLayout layout,
    PanelQuery query, QueryCondition condition, int k, SweepPoint sweep) {
    this.sql = sql;
    this.binds = Collections.unmodifiableList(binds);
    this.tenantId = tenantId;
    this.layout = layout;
    this.query = query;
    this.condition = condition;
    this.k = k;
    this.sweep = sweep;
  }

  public String sql() {
    return sql;
  }

  public List<Object> binds() {
    return binds;
  }

  /** Returns the tenant of a tenant connection, or null for a global connection. */
  public String tenantId() {
    return tenantId;
  }

  public TableLayout layout() {
    return layout;
  }

  public PanelQuery query() {
    return query;
  }

  public QueryCondition condition() {
    return condition;
  }

  public int k() {
    return k;
  }

  public SweepPoint sweep() {
    return sweep;
  }

  @Override
  public String toString() {
    return sql;
  }
}
