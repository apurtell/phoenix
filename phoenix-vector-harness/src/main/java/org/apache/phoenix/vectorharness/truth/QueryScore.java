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
 * Accuracy metrics for a single query execution against ground truth. Undefined values (e.g.
 * precision with zero returned rows) are represented as {@link Double#NaN}.
 */
public final class QueryScore {

  /** Names of the scored metrics, in {@link #values()} order. */
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

  /** Creates a score instance where all exclusions were returned rows and all rows were valid. */
  public QueryScore(double recall, double precision, double completeness, double recallAt1,
    double reciprocalRank, double ndcg, double distanceRatio, double rangePrecision,
    double rangeRecall, int returned, int truthSize, int excluded) {
    this(recall, precision, completeness, recallAt1, reciprocalRank, ndcg, distanceRatio,
      rangePrecision, rangeRecall, returned, truthSize, excluded, excluded, 0);
  }

  /**
   * @param excluded         distinct rows excluded by online sampling from result or truth
   * @param excludedReturned distinct returned rows among exclusions
   * @param violations       returned rows that violate query predicates
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

  /** Returns recall: |A ∩ G| / |G| (with tie tolerance, or 1.0 when G is empty). */
  public double recall() {
    return recall;
  }

  /** Returns precision: |A ∩ G| / |A| (or NaN when A is empty). */
  public double precision() {
    return precision;
  }

  /** Returns completeness: |A| / min(k, |matching rows|) (or 1.0 when no rows match). */
  public double completeness() {
    return completeness;
  }

  /** Returns 1.0 if true nearest neighbor is in result set A, otherwise 0.0. */
  public double recallAt1() {
    return recallAt1;
  }

  /** Returns reciprocal rank of true nearest neighbor in result set A, or 0.0. */
  public double reciprocalRank() {
    return reciprocalRank;
  }

  public double ndcg() {
    return ndcg;
  }

  /** Returns mean ratio of retrieved distance to true distance by rank. */
  public double distanceRatio() {
    return distanceRatio;
  }

  public double rangePrecision() {
    return rangePrecision;
  }

  public double rangeRecall() {
    return rangeRecall;
  }

  /** Returns number of returned rows after exclusions. */
  public int returned() {
    return returned;
  }

  /** Returns ground truth size (|G|) after exclusions. */
  public int truthSize() {
    return truthSize;
  }

  /** Returns count of distinct rows excluded by online sampling. */
  public int excluded() {
    return excluded;
  }

  /** Returns count of distinct returned rows excluded by online sampling. */
  public int excludedReturned() {
    return excludedReturned;
  }

  /** Returns count of returned rows that violate query predicates. */
  public int violations() {
    return violations;
  }

  public double[] values() {
    return new double[] { recall, precision, completeness, recallAt1, reciprocalRank, ndcg,
      distanceRatio, rangePrecision, rangeRecall };
  }
}
