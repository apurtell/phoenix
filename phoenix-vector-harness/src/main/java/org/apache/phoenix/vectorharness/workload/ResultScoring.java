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
 * Scores a query result against ground truth. The true distance of every returned row is computed
 * from the client-side corpus record, independent of the distance the engine reported, and the
 * record is checked against the query's predicates: a row that fails them is a miss however near it
 * is, so that a dropped filter or a leaked tenant cannot pass as a tie. Under online sampling, rows
 * whose state may have changed since the query started are excluded from both the result and the
 * truth, and the truth is refilled from the candidate window to k rows less the changed rows the
 * engine returned, which took places of the answer at the engine's read point.
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
   * Scores a result.
   * @param truth      the exact neighbors, a window of at least k rows when exclusions apply
   * @param k          the k of the query
   * @param sinceNanos for online sampling, the start of the query: rows changed at or after it are
   *                   excluded; 0 for no exclusion
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
        // Not an answer of this query at all; no tie tolerance may admit it.
        violations++;
        d = Double.NaN;
      }
      distances.add(d);
    }
    NeighborList g = truth;
    if (sinceNanos != 0) {
      // Unchanged rows order alike at the engine's read point and now, so the exact answer at the
      // read point, less its changed rows, is the first k - m unchanged rows of the window, where
      // m is the number of changed rows the engine returned.
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
