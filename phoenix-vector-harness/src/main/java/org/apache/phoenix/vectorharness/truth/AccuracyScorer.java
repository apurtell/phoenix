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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Scores a query result A, the ordered rows returned over JDBC, against ground truth G, the exact
 * nearest k rows that satisfy every predicate. On discretized corpora many rows tie in distance, so
 * a returned row also matches when its true distance is within a relative tolerance of the k-th
 * true distance: {@code d(q, a) <= d(q, g_k) + epsilon * |d(q, g_k)|}. Matches are capped at |G|.
 * <p>
 * The true distances of the returned rows are computed by the caller from the client-side corpus
 * record, so the scores do not depend on the distances that the engine reports.
 */
public final class AccuracyScorer {

  private static final double ABSOLUTE_TOLERANCE = 1e-12;
  private static final double LN2 = Math.log(2.0);

  private final double epsilon;

  public AccuracyScorer(double epsilon) {
    this.epsilon = epsilon;
  }

  public double epsilon() {
    return epsilon;
  }

  /**
   * Scores one result.
   * @param returned          the returned row ordinals in result order, at most k
   * @param returnedDistances the true distances of the returned rows, in the same order; NaN for a
   *                          row that does not exist in the live corpus
   * @param truth             the exact neighbors, at most k
   * @param radius            the radius of a distance-bounded query, or NaN
   * @param excluded          the number of rows excluded by online sampling, for the record
   */
  public QueryScore score(long[] returned, double[] returnedDistances, NeighborList truth,
    double radius, int excluded) {
    return score(returned, returnedDistances, truth, radius, excluded, excluded, 0);
  }

  /**
   * Scores one result. A returned row that fails the query's predicates must come with a NaN
   * distance, so that the tie tolerance, which absorbs ties among valid answers, cannot admit it.
   * @param returnedDistances the true distances of the returned rows, in the same order; NaN for a
   *                          row that does not exist in the live corpus or fails the predicates
   * @param excluded          the distinct rows excluded by online sampling, for the record
   * @param excludedReturned  the distinct returned rows among them, for the record
   * @param violations        the returned rows that fail the predicates, for the record
   */
  public QueryScore score(long[] returned, double[] returnedDistances, NeighborList truth,
    double radius, int excluded, int excludedReturned, int violations) {
    int g = truth.size();
    // Deduplicate the returned rows; a row counts once.
    Set<Long> seen = new HashSet<>();
    int n = 0;
    long[] a = new long[returned.length];
    double[] ad = new double[returned.length];
    for (int i = 0; i < returned.length; i++) {
      if (seen.add(returned[i])) {
        a[n] = returned[i];
        ad[n] = returnedDistances[i];
        n++;
      }
    }
    Set<Long> truthSet = new HashSet<>();
    for (int i = 0; i < g; i++) {
      truthSet.add(truth.ordinal(i));
    }
    double kthBound = g == 0 ? Double.NEGATIVE_INFINITY : tolerantBound(truth.distance(g - 1));
    double firstBound = g == 0 ? Double.NEGATIVE_INFINITY : tolerantBound(truth.distance(0));

    boolean[] hit = new boolean[n];
    int hits = 0;
    for (int i = 0; i < n; i++) {
      hit[i] = truthSet.contains(a[i]) || (!Double.isNaN(ad[i]) && ad[i] <= kthBound);
      if (hit[i]) {
        hits++;
      }
    }
    hits = Math.min(hits, g);

    double recall = g == 0 ? 1.0 : (double) hits / g;
    double precision = n == 0 ? Double.NaN : (double) hits / n;
    double completeness = g == 0 ? 1.0 : Math.min(1.0, (double) n / g);

    double recallAt1 = 0.0;
    double rr = 0.0;
    if (g == 0) {
      recallAt1 = 1.0;
      rr = 1.0;
    } else {
      long first = truth.ordinal(0);
      for (int i = 0; i < n; i++) {
        if (a[i] == first || (!Double.isNaN(ad[i]) && ad[i] <= firstBound)) {
          recallAt1 = 1.0;
          rr = 1.0 / (i + 1);
          break;
        }
      }
    }

    double ndcg;
    if (g == 0) {
      ndcg = n == 0 ? 1.0 : 0.0;
    } else {
      double dcg = 0.0;
      int counted = 0;
      for (int i = 0; i < n && counted < g; i++) {
        if (hit[i]) {
          dcg += LN2 / Math.log(i + 2.0);
          counted++;
        }
      }
      double idcg = 0.0;
      for (int i = 0; i < g; i++) {
        idcg += LN2 / Math.log(i + 2.0);
      }
      ndcg = dcg / idcg;
    }

    double[] sortedA = new double[n];
    int valid = 0;
    for (int i = 0; i < n; i++) {
      if (!Double.isNaN(ad[i])) {
        sortedA[valid++] = ad[i];
      }
    }
    sortedA = Arrays.copyOf(sortedA, valid);
    Arrays.sort(sortedA);
    double ratioSum = 0.0;
    int ratioCount = 0;
    for (int i = 0; i < Math.min(valid, g); i++) {
      double dg = truth.distance(i);
      double da = sortedA[i];
      if (Math.abs(dg) > ABSOLUTE_TOLERANCE) {
        ratioSum += 1.0 + (da - dg) / Math.abs(dg);
        ratioCount++;
      } else if (da - dg <= ABSOLUTE_TOLERANCE) {
        ratioSum += 1.0;
        ratioCount++;
      }
    }
    double ratio = ratioCount == 0 ? Double.NaN : ratioSum / ratioCount;

    double rangePrecision = Double.NaN;
    double rangeRecall = Double.NaN;
    if (!Double.isNaN(radius)) {
      int within = 0;
      for (int i = 0; i < n; i++) {
        if (!Double.isNaN(ad[i]) && ad[i] < radius) {
          within++;
        }
      }
      rangePrecision = n == 0 ? Double.NaN : (double) within / n;
      rangeRecall = g == 0 ? 1.0 : Math.min(1.0, (double) within / g);
    }
    return new QueryScore(recall, precision, completeness, recallAt1, rr, ndcg, ratio,
      rangePrecision, rangeRecall, n, g, excluded, excludedReturned, violations);
  }

  private double tolerantBound(double d) {
    return d + Math.max(epsilon * Math.abs(d), ABSOLUTE_TOLERANCE);
  }
}
