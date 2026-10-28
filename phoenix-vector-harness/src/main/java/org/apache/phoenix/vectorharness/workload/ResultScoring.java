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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.truth.AccuracyScorer;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;

/**
 * Evaluates query accuracy against ground truth using client side vector representations. Validates
 * relational predicates and distance radii, flags violations, and handles exclusions during online
 * sampling for rows mutated concurrently.
 */
public final class ResultScoring {

  private final Metric metric;
  private final AccuracyScorer scorer;
  private final LiveCorpus live;

  public ResultScoring(Metric metric, double tieEpsilon, LiveCorpus live) {
    this.metric = metric;
    this.scorer = new AccuracyScorer(tieEpsilon);
    this.live = live;
  }

  /**
   * Scores query results against ground truth.
   * @param truth      ground truth neighbor list
   * @param k          target k nearest neighbors
   * @param sinceNanos timestamp marking start of query for online exclusion filtering, or 0
   */
  public QueryScore score(QuerySpec q, QueryExecutor.Result r, NeighborList truth, int k,
    long sinceNanos) {
    QueryCondition c = q.condition();
    List<Long> returned = new ArrayList<>(r.ordinals().length);
    List<Double> distances = new ArrayList<>(r.ordinals().length);
    Set<Long> excluded = new HashSet<>();
    int excludedReturned = 0;
    int violations = 0;
    for (long o : r.ordinals()) {
      if (sinceNanos != 0 && live.changedSince(o, sinceNanos)) {
        if (excluded.add(o)) {
          excludedReturned++;
        }
        continue;
      }
      returned.add(o);
      LiveCorpus.RowState s = live.get(o);
      double d = s == null ? Double.NaN : metric.distance(q.query().vector(), s.vector());
      if (s != null && !(c.test(s.attributes()) && c.withinRadius(d))) {
        // Disqualify invalid row from matching ground truth
        violations++;
        d = Double.NaN;
      }
      distances.add(d);
    }
    NeighborList g = truth;
    if (sinceNanos != 0) {
      // Select first k - m unchanged rows where m is number of excluded returned rows
      int limit = Math.max(0, k - excludedReturned);
      List<Long> to = new ArrayList<>();
      List<Double> td = new ArrayList<>();
      for (int i = 0; i < truth.size() && to.size() < limit; i++) {
        if (live.changedSince(truth.ordinal(i), sinceNanos)) {
          excluded.add(truth.ordinal(i));
          continue;
        }
        to.add(truth.ordinal(i));
        td.add(truth.distance(i));
      }
      long[] o = new long[to.size()];
      double[] d = new double[td.size()];
      for (int i = 0; i < o.length; i++) {
        o[i] = to.get(i);
        d[i] = td.get(i);
      }
      g = new NeighborList(o, d, truth.complete());
    } else {
      g = truth.head(k);
    }
    long[] a = new long[returned.size()];
    double[] ad = new double[returned.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = returned.get(i);
      ad[i] = distances.get(i);
    }
    return scorer.score(a, ad, g, c.hasRadius() ? c.radius() : Double.NaN, excluded.size(),
      excludedReturned, violations);
  }
}
