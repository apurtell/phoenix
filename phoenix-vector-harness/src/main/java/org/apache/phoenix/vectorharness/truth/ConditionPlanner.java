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
 * Derives the conditions of each panel query from the profile: one per query shape, and one per
 * selectivity for the shapes that sweep selectivity. Every choice is a deterministic function of
 * the panel query index, so the truth phase and the run phase derive the same conditions. Ranges of
 * the same selectivity start at different vocabulary terms for different queries, so that the panel
 * covers the vocabulary.
 * <p>
 * Distance-bounded conditions depend on distances: the radius of query q at k lies midway between
 * the true unfiltered distances of ranks r and r + 1, where r is the configured fraction of k, so
 * that the expected answer has r rows and no row lies near the radius. Their truth is a prefix of
 * the unfiltered truth.
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

  /** Returns the shapes that this profile runs, without those that do not apply to the table. */
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

  /** Returns the length of the ground truth lists that the run needs. */
  public static int truthCapacity(HarnessConfig config) {
    int kMax = Arrays.stream(config.kValues()).max().orElse(10);
    return (int) Math.ceil(kMax * config.truthMargin()) + 1;
  }

  /** Returns the conditions of a panel query that do not depend on distances. */
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

  /** Returns the family name of a selectivity sweep condition, such as covered_filter@0.01. */
  public static String family(QueryShape shape, double selectivity) {
    return shape.id() + "@" + trim(selectivity);
  }

  static String trim(double d) {
    String s = Double.toString(d);
    return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
  }

  /** Returns true if the profile runs distance-bounded queries. */
  public boolean distanceBounded() {
    return shapes.contains(QueryShape.DISTANCE_BOUND);
  }

  /**
   * Returns the distance-bounded conditions of a panel query, given its exact unfiltered neighbors.
   * A k whose radius cannot be placed, because the list is too short, is skipped.
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

  /** Returns the truth of a distance-bounded condition as a prefix of the unfiltered truth. */
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
