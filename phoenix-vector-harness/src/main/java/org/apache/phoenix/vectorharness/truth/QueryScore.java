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

/**
 * The accuracy of one query result against its ground truth. Values that are undefined for the
 * query are NaN: precision with no returned rows, the distance error ratio with no comparable
 * ranks, and the range metrics of a query without a radius.
 */
public final class QueryScore {

  /** The names of the scored metrics, in {@link #values()} order. */
  public static final String[] NAMES = { "recall", "precision", "completeness", "recall1_at_k",
    "mrr", "ndcg", "distance_ratio", "range_precision", "range_recall" };

  private final double recall;
  private final double precision;
  private final double completeness;
  private final double recallAt1;
  private final double reciprocalRank;
  private final double ndcg;
  private final double distanceRatio;
  private final double rangePrecision;
  private final double rangeRecall;
  private final int returned;
  private final int truthSize;
  private final int excluded;
  private final int excludedReturned;
  private final int violations;

  /** Creates a score whose exclusions were all returned rows and whose rows all passed. */
  public QueryScore(double recall, double precision, double completeness, double recallAt1,
    double reciprocalRank, double ndcg, double distanceRatio, double rangePrecision,
    double rangeRecall, int returned, int truthSize, int excluded) {
    this(recall, precision, completeness, recallAt1, reciprocalRank, ndcg, distanceRatio,
      rangePrecision, rangeRecall, returned, truthSize, excluded, excluded, 0);
  }

  /**
   * @param excluded         the distinct rows excluded by online sampling, from the result or the
   *                         truth
   * @param excludedReturned the distinct returned rows among them
   * @param violations       the returned rows that fail the query's predicates
   */
  public QueryScore(double recall, double precision, double completeness, double recallAt1,
    double reciprocalRank, double ndcg, double distanceRatio, double rangePrecision,
    double rangeRecall, int returned, int truthSize, int excluded, int excludedReturned,
    int violations) {
    this.recall = recall;
    this.precision = precision;
    this.completeness = completeness;
    this.recallAt1 = recallAt1;
    this.reciprocalRank = reciprocalRank;
    this.ndcg = ndcg;
    this.distanceRatio = distanceRatio;
    this.rangePrecision = rangePrecision;
    this.rangeRecall = rangeRecall;
    this.returned = returned;
    this.truthSize = truthSize;
    this.excluded = excluded;
    this.excludedReturned = excludedReturned;
    this.violations = violations;
  }

  /** |A intersect G| / |G|, with tie tolerance; 1 when G is empty. */
  public double recall() {
    return recall;
  }

  /** |A intersect G| / |A|; NaN when A is empty. */
  public double precision() {
    return precision;
  }

  /** |A| / min(k, |passing rows|); 1 when no row passes. */
  public double completeness() {
    return completeness;
  }

  /** 1 if the true nearest neighbor is anywhere in A, else 0. */
  public double recallAt1() {
    return recallAt1;
  }

  /** The reciprocal rank in A of the true nearest neighbor, or 0. */
  public double reciprocalRank() {
    return reciprocalRank;
  }

  public double ndcg() {
    return ndcg;
  }

  /** The mean ratio of the retrieved to the true distance by rank. */
  public double distanceRatio() {
    return distanceRatio;
  }

  public double rangePrecision() {
    return rangePrecision;
  }

  public double rangeRecall() {
    return rangeRecall;
  }

  /** Returns the number of rows the engine returned, after exclusions. */
  public int returned() {
    return returned;
  }

  /** Returns |G|, after exclusions. */
  public int truthSize() {
    return truthSize;
  }

  /** Returns the number of distinct rows excluded by online sampling, from A or from G. */
  public int excluded() {
    return excluded;
  }

  /** Returns the number of distinct returned rows excluded by online sampling. */
  public int excludedReturned() {
    return excludedReturned;
  }

  /**
   * Returns the number of returned rows that fail the query's predicates by their client-side
   * record: a filter, a tenant, a key range, or a radius. They count as misses.
   */
  public int violations() {
    return violations;
  }

  public double[] values() {
    return new double[] { recall, precision, completeness, recallAt1, reciprocalRank, ndcg,
      distanceRatio, rangePrecision, rangeRecall };
  }
}
