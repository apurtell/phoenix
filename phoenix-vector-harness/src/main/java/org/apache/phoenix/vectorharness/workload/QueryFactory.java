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

import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.Vocabulary;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.variant.SweepPoint;

/**
 * Renders nearest neighbor queries as SQL. Every query projects DOC_ID, which carries the row
 * ordinal, and the distance, and orders by the projected distance by position, so that the query
 * vector is bound once and the planner sees the distance function as the only ORDER BY item.
 * Conditions become predicates: tenant equality (or a tenant connection on a multi-tenant table), a
 * half-open vocabulary range on CATEGORY or SOURCE (BSON_VALUE paths on DOC_PROFILE), a half-open
 * DOC_ID range, and a strict distance radius, which binds the query vector a second time.
 */
public final class QueryFactory {

  private final TableNames names;
  private final Schema schema;
  private final Metric metric;
  private final AttributeModel attributes;
  private final boolean multiTenant;

  public QueryFactory(TableNames names, Schema schema, Metric metric, AttributeModel attributes,
    boolean multiTenant) {
    this.names = names;
    this.schema = schema;
    this.metric = metric;
    this.attributes = attributes;
    this.multiTenant = multiTenant;
  }

  /** Returns the distance expression of a layout with one bind for the query vector. */
  public String distance(TableLayout layout) {
    return metric.sqlFunction() + "(" + schema.vectorExpression(layout) + ", ?)";
  }

  /** Builds a query with an explicit hint, such as NO_INDEX; the sweep is kept for the record. */
  public QuerySpec build(TableLayout layout, PanelQuery q, QueryCondition c, int k,
    SweepPoint sweep, String hintOverride) {
    String hint = hintOverride != null ? hintOverride : sweep == null ? "" : sweep.hint();
    List<Object> binds = new ArrayList<>();
    StringBuilder sql = new StringBuilder("SELECT ");
    if (!hint.isEmpty()) {
      sql.append("/*+ ").append(hint).append(" */ ");
    }
    String dist = distance(layout);
    sql.append("DOC_ID, ").append(dist).append(" FROM ").append(names.fullTable(layout));
    binds.add(q.vector());
    List<String> where = new ArrayList<>();
    String tenantId = null;
    if (c.tenant() >= 0) {
      String t = attributes.tenants().term(c.tenant());
      if (multiTenant) {
        tenantId = t;
      } else {
        where.add("TENANT_ID = ?");
        binds.add(t);
      }
    }
    if (c.attribute() != QueryCondition.Attribute.NONE) {
      String column = c.attribute() == QueryCondition.Attribute.CATEGORY ? "CATEGORY" : "SOURCE";
      Vocabulary v = c.attribute() == QueryCondition.Attribute.CATEGORY
        ? attributes.categories()
        : attributes.sources();
      String expr = schema.attributeExpression(layout, column);
      where.add(expr + " >= ?");
      binds.add(v.term(c.termFrom()));
      if (c.termTo() < v.size()) {
        where.add(expr + " < ?");
        binds.add(v.term(c.termTo()));
      }
    }
    if (c.docIdLow() != null && !c.docIdLow().equals(AttributeModel.docIdBound(0.0))) {
      where.add("DOC_ID >= ?");
      binds.add(c.docIdLow());
    }
    if (c.docIdHigh() != null && !c.docIdHigh().equals(AttributeModel.docIdBound(1.0))) {
      where.add("DOC_ID < ?");
      binds.add(c.docIdHigh());
    }
    if (c.hasRadius()) {
      where.add(dist + " < ?");
      binds.add(q.vector());
      binds.add(c.radius());
    }
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    sql.append(" ORDER BY 2 LIMIT ").append(k);
    return new QuerySpec(sql.toString(), binds, tenantId, layout, q, c, k, sweep);
  }

  public QuerySpec build(TableLayout layout, PanelQuery q, QueryCondition c, int k,
    SweepPoint sweep) {
    return build(layout, q, c, k, sweep, null);
  }

  /** Returns the ordinal of a row from its DOC_ID, or -1 if the DOC_ID is not the harness's. */
  public static long ordinalOf(String docId) {
    if (docId == null || docId.length() != AttributeModel.DOC_ID_HASH_DIGITS + 12) {
      return -1;
    }
    try {
      return Long.parseLong(docId.substring(AttributeModel.DOC_ID_HASH_DIGITS));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
