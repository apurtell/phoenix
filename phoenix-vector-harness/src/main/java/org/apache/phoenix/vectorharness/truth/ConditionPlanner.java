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
package org.apache.phoenix.vectorharness.truth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.corpus.Vocabulary;

/**
 * Derives relational and distance bounded query conditions for panel queries from configuration.
 * Generates deterministic conditions per query index to ensure parity between truth computation and
 * query execution.
 */
public final class ConditionPlanner {

  private final Set<QueryShape> shapes;
  private final double[] selectivities;
  private final int[] kValues;
  private final boolean multiTenant;
  private final boolean tenantScoping;
  private final double tenantScopedFraction;
  private final double pkRangeFraction;
  private final double distanceBoundRank;
  private final AttributeModel attributes;
  private final long seed;

  public ConditionPlanner(HarnessConfig config, AttributeModel attributes) {
    this.shapes = config.shapes();
    this.selectivities = config.filterSelectivities();
    this.kValues = config.kValues();
    this.multiTenant = config.multiTenant();
    this.tenantScoping = config.multiTenant() || config.tenants() > 1;
    this.tenantScopedFraction = config.tenantScopedFraction();
    this.pkRangeFraction = config.pkRangeFraction();
    this.distanceBoundRank = config.distanceBoundRank();
    this.attributes = attributes;
    this.seed = config.seed();
  }

  /** Returns active query shapes applicable to the configured table layout. */
  public List<QueryShape> activeShapes() {
    List<QueryShape> out = new ArrayList<>();
    for (QueryShape s : QueryShape.values()) {
      if (!shapes.contains(s)) {
        continue;
      }
      if (s == QueryShape.GLOBAL && !multiTenant) {
        continue;
      }
      if (s == QueryShape.TENANT_SCOPED && !tenantScoping) {
        continue;
      }
      out.add(s);
    }
    return out;
  }

  /** Calculates required ground truth list capacity including safety margins. */
  public static int truthCapacity(HarnessConfig config) {
    int kMax = Arrays.stream(config.kValues()).max().orElse(10);
    return (int) Math.ceil(kMax * config.truthMargin()) + 1;
  }

  /** Generates non-distance-dependent relational query conditions for a panel query. */
  public List<QueryCondition> relational(PanelQuery q) {
    List<QueryCondition> out = new ArrayList<>();
    SplitMix64 rnd = new SplitMix64(SplitMix64.combine(seed ^ 0x434F4E44L, q.index()));
    boolean scoped = multiTenant && rnd.nextDouble() < tenantScopedFraction;
    int scopeTenant = scoped ? q.tenant() : -1;
    for (QueryShape s : activeShapes()) {
      switch (s) {
        case UNFILTERED:
          out.add(QueryCondition.builder(s).build());
          break;
        case GLOBAL:
          out.add(QueryCondition.builder(s).build());
          break;
        case COVERED_FILTER:
          for (double sel : selectivities) {
            out.add(range(s, QueryCondition.Attribute.CATEGORY, attributes.categories(), sel,
              q.index(), scopeTenant));
          }
          break;
        case UNCOVERED_FILTER:
          for (double sel : selectivities) {
            out.add(range(s, QueryCondition.Attribute.SOURCE, attributes.sources(), sel, q.index(),
              scopeTenant));
          }
          break;
        case TENANT_SCOPED:
          out.add(QueryCondition.builder(s).tenant(q.tenant())
            .selectivity(attributes.tenants().probability(q.tenant())).build());
          break;
        case PK_RANGE: {
          double w = pkRangeFraction;
          double start = w >= 1.0 ? 0.0 : rnd.nextDouble() * (1.0 - w);
          String low = AttributeModel.docIdBound(start);
          String high = AttributeModel.docIdBound(Math.min(1.0, start + w));
          // The tenant equality makes the DOC_ID bounds a range of the row key.
          out.add(QueryCondition.builder(s).docIds(low, high).tenant(q.tenant())
            .selectivity(w * attributes.tenants().probability(q.tenant())).build());
          break;
        }
        default:
          break;
      }
    }
    return out;
  }

  private static QueryCondition range(QueryShape shape, QueryCondition.Attribute attr,
    Vocabulary vocab, double selectivity, int queryIndex, int tenant) {
    int[] r = vocab.rangeForSelectivity(selectivity, queryIndex * 7919);
    return QueryCondition.builder(shape).range(attr, r[0], r[1]).tenant(tenant)
      .selectivity(vocab.rangeProbability(r[0], r[1])).family(family(shape, selectivity)).build();
  }

  /** Returns the formatted family identifier for a selectivity sweep condition. */
  public static String family(QueryShape shape, double selectivity) {
    return shape.id() + "@" + trim(selectivity);
  }

  static String trim(double d) {
    String s = Double.toString(d);
    return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
  }

  /** Returns true if distance bounded queries are enabled. */
  public boolean distanceBounded() {
    return shapes.contains(QueryShape.DISTANCE_BOUND);
  }

  /**
   * Derives distance bounded query conditions from unfiltered ground truth neighbor distances.
   */
  public List<QueryCondition> distanceBound(NeighborList unfiltered) {
    List<QueryCondition> out = new ArrayList<>();
    if (!distanceBounded()) {
      return out;
    }
    for (int k : kValues) {
      int r = Math.max(1, (int) Math.ceil(distanceBoundRank * k));
      if (unfiltered.size() < r + 1) {
        continue;
      }
      double radius = 0.5 * (unfiltered.distance(r - 1) + unfiltered.distance(r));
      out.add(QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(radius, k)
        .selectivity((double) r / Math.max(1, k)).family(QueryShape.DISTANCE_BOUND.id() + "@k=" + k)
        .build());
    }
    return out;
  }

  /** Extracts the ground truth sublist satisfying a distance bound radius predicate. */
  public static NeighborList boundedPrefix(NeighborList unfiltered, QueryCondition bounded) {
    int n = 0;
    while (n < unfiltered.size() && unfiltered.distance(n) < bounded.radius()) {
      n++;
    }
    boolean complete = n < unfiltered.size() || unfiltered.complete();
    long[] o = new long[n];
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      o[i] = unfiltered.ordinal(i);
      d[i] = unfiltered.distance(i);
    }
    return new NeighborList(o, d, complete);
  }
}
