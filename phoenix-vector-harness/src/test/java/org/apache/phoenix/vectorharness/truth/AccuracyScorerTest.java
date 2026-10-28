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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccuracyScorerTest {

  private static final double EPS = 1e-12;
  private static final double NAN = Double.NaN;
  private static final AccuracyScorer SCORER = new AccuracyScorer(1e-6);

  private static NeighborList truth(long[] o, double[] d) {
    return new NeighborList(o, d, false);
  }

  /** Returns the discounted gain of a hit at a zero-based rank. */
  private static double gain(int rank) {
    return 1.0 / (Math.log(rank + 2.0) / Math.log(2.0));
  }

  private static double idealGain(int n) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) {
      sum += gain(i);
    }
    return sum;
  }

  private static QueryScore score(long[] returned, double[] distances, NeighborList truth) {
    return SCORER.score(returned, distances, truth, NAN, 0);
  }

  private static final NeighborList FIVE =
    truth(new long[] { 1, 2, 3, 4, 5 }, new double[] { 1.0, 2.0, 3.0, 4.0, 5.0 });

  @Test
  public void testEpsilon() {
    assertEquals(1e-6, SCORER.epsilon(), 0.0);
    assertEquals(0.01, new AccuracyScorer(0.01).epsilon(), 0.0);
  }

  @Test
  public void testPerfectResult() {
    QueryScore s = SCORER.score(new long[] { 1, 2, 3, 4, 5 },
      new double[] { 1.0, 2.0, 3.0, 4.0, 5.0 }, FIVE, NAN, 3);
    assertEquals(1.0, s.recall(), EPS);
    assertEquals(1.0, s.precision(), EPS);
    assertEquals(1.0, s.completeness(), EPS);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(1.0, s.reciprocalRank(), EPS);
    assertEquals(1.0, s.ndcg(), EPS);
    assertEquals(1.0, s.distanceRatio(), EPS);
    assertTrue(Double.isNaN(s.rangePrecision()));
    assertTrue(Double.isNaN(s.rangeRecall()));
    assertEquals(5, s.returned());
    assertEquals(5, s.truthSize());
    assertEquals(3, s.excluded());
  }

  @Test
  public void testPermutedPerfectResult() {
    QueryScore s =
      score(new long[] { 2, 1, 3, 5, 4 }, new double[] { 2.0, 1.0, 3.0, 5.0, 4.0 }, FIVE);
    assertEquals(1.0, s.recall(), EPS);
    assertEquals(1.0, s.precision(), EPS);
    // Every rank holds a true neighbor, so the gain is ideal despite the order.
    assertEquals(1.0, s.ndcg(), EPS);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(0.5, s.reciprocalRank(), EPS);
    // The ratio compares sorted distances, so the order does not matter.
    assertEquals(1.0, s.distanceRatio(), EPS);
  }

  @Test
  public void testPartialOverlap() {
    // Rows 9 and 8 are not true neighbors and are farther than the fifth true neighbor.
    QueryScore s = score(new long[] { 1, 9, 3, 8 }, new double[] { 1.0, 7.0, 3.0, 6.0 }, FIVE);
    assertEquals(2.0 / 5, s.recall(), EPS);
    assertEquals(2.0 / 4, s.precision(), EPS);
    assertEquals(4.0 / 5, s.completeness(), EPS);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(1.0, s.reciprocalRank(), EPS);
    assertEquals((gain(0) + gain(2)) / idealGain(5), s.ndcg(), EPS);
    assertEquals(1.5 / 2.948459118879392, s.ndcg(), 1e-9);
    // Sorted returned distances 1, 3, 6, 7 against true 1, 2, 3, 4.
    double ratio = (1.0 + (1.0 + 1.0 / 2) + (1.0 + 3.0 / 3) + (1.0 + 3.0 / 4)) / 4;
    assertEquals(1.5625, ratio, EPS);
    assertEquals(ratio, s.distanceRatio(), EPS);
    assertEquals(4, s.returned());
    assertEquals(5, s.truthSize());
  }

  @Test
  public void testTiesWithTheKthNeighborAreHits() {
    NeighborList three = truth(new long[] { 1, 2, 3 }, new double[] { 1.0, 2.0, 3.0 });
    // Row 7 is not in the truth but ties with the third neighbor.
    QueryScore s = score(new long[] { 1, 2, 7 }, new double[] { 1.0, 2.0, 3.0 }, three);
    assertEquals(1.0, s.recall(), EPS);
    assertEquals(1.0, s.precision(), EPS);
    assertEquals(1.0, s.ndcg(), EPS);

    // Four hits by tolerance, but hits are capped at |G| = 3.
    s = score(new long[] { 7, 8, 9, 1 }, new double[] { 3.0, 2.5, 3.0 * (1 + 0.5e-6), 1.0 }, three);
    assertEquals(1.0, s.recall(), EPS);
    assertEquals(3.0 / 4, s.precision(), EPS);
    assertEquals(1.0, s.completeness(), EPS);
    assertEquals(1.0, s.ndcg(), EPS);
    // Only row 1 matches the first neighbor; it is at rank 4.
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(0.25, s.reciprocalRank(), EPS);

    // Beyond the relative tolerance, a row is a miss.
    s = score(new long[] { 7 }, new double[] { 3.0 * (1 + 2e-6) }, three);
    assertEquals(0.0, s.recall(), EPS);
    assertEquals(0.0, s.precision(), EPS);
    assertEquals(0.0, s.ndcg(), EPS);

    // A wider tolerance admits it.
    s = new AccuracyScorer(1e-3).score(new long[] { 7 }, new double[] { 3.0 * (1 + 2e-6) }, three,
      NAN, 0);
    assertEquals(1.0 / 3, s.recall(), EPS);
  }

  @Test
  public void testAbsoluteToleranceAtZeroDistance() {
    // Duplicates of the query lie at distance zero, where a relative tolerance is empty.
    NeighborList zeros = truth(new long[] { 1, 2 }, new double[] { 0.0, 0.0 });
    QueryScore s = score(new long[] { 5, 6 }, new double[] { 5e-13, 1e-11 }, zeros);
    assertEquals(0.5, s.recall(), EPS);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(1.0, s.reciprocalRank(), EPS);
  }

  @Test
  public void testEmptyTruth() {
    NeighborList none = NeighborList.empty();
    QueryScore s = score(new long[0], new double[0], none);
    assertEquals(1.0, s.recall(), EPS);
    assertTrue(Double.isNaN(s.precision()));
    assertEquals(1.0, s.completeness(), EPS);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(1.0, s.reciprocalRank(), EPS);
    assertEquals(1.0, s.ndcg(), EPS);
    assertTrue(Double.isNaN(s.distanceRatio()));
    assertEquals(0, s.truthSize());

    // No row passes, but the engine returned rows anyway: none of them can be right.
    s = SCORER.score(new long[] { 4, 5 }, new double[] { 1.0, 2.0 }, none, 1.5, 0);
    assertEquals(1.0, s.recall(), EPS);
    assertEquals(0.0, s.precision(), EPS);
    assertEquals(1.0, s.completeness(), EPS);
    assertEquals(0.0, s.ndcg(), EPS);
    assertTrue(Double.isNaN(s.distanceRatio()));
    assertEquals(0.5, s.rangePrecision(), EPS);
    assertEquals(1.0, s.rangeRecall(), EPS);
  }

  @Test
  public void testEmptyResult() {
    QueryScore s = score(new long[0], new double[0], FIVE);
    assertEquals(0.0, s.recall(), EPS);
    assertTrue(Double.isNaN(s.precision()));
    assertEquals(0.0, s.completeness(), EPS);
    assertEquals(0.0, s.recallAt1(), EPS);
    assertEquals(0.0, s.reciprocalRank(), EPS);
    assertEquals(0.0, s.ndcg(), EPS);
    assertTrue(Double.isNaN(s.distanceRatio()));
    assertEquals(0, s.returned());
    assertEquals(5, s.truthSize());
    s = SCORER.score(new long[0], new double[0], FIVE, 2.5, 0);
    assertTrue(Double.isNaN(s.rangePrecision()));
    assertEquals(0.0, s.rangeRecall(), EPS);
  }

  @Test
  public void testRankOfTheFirstTrueNeighborDrivesReciprocalRank() {
    NeighborList three = truth(new long[] { 10, 20, 30 }, new double[] { 1.0, 2.0, 3.0 });
    QueryScore s = score(new long[] { 40, 50, 10 }, new double[] { 5.0, 6.0, 1.0 }, three);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(1.0 / 3, s.reciprocalRank(), EPS);
    assertEquals(1.0 / 3, s.recall(), EPS);

    // A row that ties with the first neighbor counts as finding it.
    s = score(new long[] { 40, 41 }, new double[] { 5.0, 1.0 }, three);
    assertEquals(1.0, s.recallAt1(), EPS);
    assertEquals(0.5, s.reciprocalRank(), EPS);

    // Other true neighbors do not stand in for the first.
    s = score(new long[] { 20, 30 }, new double[] { 2.0, 3.0 }, three);
    assertEquals(0.0, s.recallAt1(), EPS);
    assertEquals(0.0, s.reciprocalRank(), EPS);
    assertEquals(2.0 / 3, s.recall(), EPS);
  }

  @Test
  public void testNdcgRewardsEarlyHits() {
    QueryScore early =
      score(new long[] { 1, 90, 91, 92, 93 }, new double[] { 1.0, 9.0, 9.0, 9.0, 9.0 }, FIVE);
    QueryScore late =
      score(new long[] { 90, 91, 92, 93, 1 }, new double[] { 9.0, 9.0, 9.0, 9.0, 1.0 }, FIVE);
    assertEquals(early.recall(), late.recall(), EPS);
    assertEquals(early.precision(), late.precision(), EPS);
    assertEquals(gain(0) / idealGain(5), early.ndcg(), EPS);
    assertEquals(gain(4) / idealGain(5), late.ndcg(), EPS);
    assertTrue(early.ndcg() > late.ndcg());
    assertEquals(1.0, early.reciprocalRank(), EPS);
    assertEquals(0.2, late.reciprocalRank(), EPS);
  }

  @Test
  public void testDistanceRatioOfNegativeDistances() {
    // Inner product distances are negated dot products, so the true distances are negative.
    NeighborList ip = truth(new long[] { 1, 2, 3 }, new double[] { -10.0, -8.0, -6.0 });
    QueryScore perfect = score(new long[] { 1, 2, 3 }, new double[] { -10.0, -8.0, -6.0 }, ip);
    assertEquals(1.0, perfect.distanceRatio(), EPS);
    QueryScore worse = score(new long[] { 5, 2, 4 }, new double[] { -5.0, -8.0, -9.0 }, ip);
    // Sorted -9, -8, -5 against -10, -8, -6: worse results give ratios above 1.
    double expected = ((1.0 + 1.0 / 10) + 1.0 + (1.0 + 1.0 / 6)) / 3;
    assertEquals(expected, worse.distanceRatio(), EPS);
    assertTrue(worse.distanceRatio() > 1.0);
  }

  @Test
  public void testDistanceRatioWithZeroAndUnknownDistances() {
    NeighborList zeroFirst = truth(new long[] { 1, 2 }, new double[] { 0.0, 1.0 });
    QueryScore s = score(new long[] { 1, 3 }, new double[] { 0.0, 2.0 }, zeroFirst);
    assertEquals((1.0 + 2.0) / 2, s.distanceRatio(), EPS);

    // A returned row missing from the live corpus has no true distance and no ratio.
    NeighborList three = truth(new long[] { 1, 2, 3 }, new double[] { 1.0, 2.0, 4.0 });
    s = score(new long[] { 1, 99 }, new double[] { 1.0, NAN }, three);
    assertEquals(1.0, s.distanceRatio(), EPS);
    assertEquals(1.0 / 3, s.recall(), EPS);
    assertEquals(0.5, s.precision(), EPS);
    // A true neighbor still counts as a hit when its distance is unknown.
    s = score(new long[] { 2 }, new double[] { NAN }, three);
    assertEquals(1.0 / 3, s.recall(), EPS);
    assertTrue(Double.isNaN(s.distanceRatio()));
  }

  @Test
  public void testRangeMetrics() {
    NeighborList three = truth(new long[] { 1, 2, 3 }, new double[] { 1.0, 2.0, 3.0 });
    QueryScore s =
      SCORER.score(new long[] { 1, 2, 9, 10 }, new double[] { 1.0, 2.0, 3.4, 4.0 }, three, 3.5, 0);
    assertEquals(3.0 / 4, s.rangePrecision(), EPS);
    assertEquals(1.0, s.rangeRecall(), EPS);

    // The radius is strict: a row at the radius is outside the range.
    s = SCORER.score(new long[] { 1, 2, 11 }, new double[] { 1.0, 2.0, 3.5 }, three, 3.5, 0);
    assertEquals(2.0 / 3, s.rangePrecision(), EPS);
    assertEquals(2.0 / 3, s.rangeRecall(), EPS);

    // Range recall is capped at 1 and ignores rows of unknown distance.
    NeighborList one = truth(new long[] { 1 }, new double[] { 1.0 });
    s = SCORER.score(new long[] { 1, 2, 3, 4 }, new double[] { 1.0, 2.0, 3.0, NAN }, one, 10.0, 0);
    assertEquals(3.0 / 4, s.rangePrecision(), EPS);
    assertEquals(1.0, s.rangeRecall(), EPS);
  }

  @Test
  public void testDuplicateRowsCountOnce() {
    NeighborList three = truth(new long[] { 1, 2, 3 }, new double[] { 1.0, 2.0, 3.0 });
    QueryScore s = score(new long[] { 1, 1, 2, 2 }, new double[] { 1.0, 1.0, 2.0, 2.0 }, three);
    assertEquals(2, s.returned());
    assertEquals(2.0 / 3, s.recall(), EPS);
    assertEquals(1.0, s.precision(), EPS);
    assertEquals(2.0 / 3, s.completeness(), EPS);
    assertEquals((gain(0) + gain(1)) / idealGain(3), s.ndcg(), EPS);
    assertEquals(1.0, s.distanceRatio(), EPS);

    // A repeated tie cannot fill the truth by itself.
    s = score(new long[] { 7, 7, 7 }, new double[] { 3.0, 3.0, 3.0 }, three);
    assertEquals(1, s.returned());
    assertEquals(1.0 / 3, s.recall(), EPS);
    assertEquals(1.0, s.precision(), EPS);
  }

  @Test
  public void testScoresOfAComputedResult() {
    // An engine answer that misses one of the ten true neighbors and returns the eleventh.
    TopK top = new TopK(11);
    for (int i = 0; i < 50; i++) {
      top.offer(100 + i, 0.5 + 0.1 * i);
    }
    NeighborList eleven = top.toList();
    NeighborList ten = eleven.head(10);
    long[] returned = new long[10];
    double[] distances = new double[10];
    for (int i = 0, j = 0; i < 11; i++) {
      if (i != 3) {
        returned[j] = eleven.ordinal(i);
        distances[j] = eleven.distance(i);
        j++;
      }
    }
    QueryScore s = SCORER.score(returned, distances, ten, NAN, 0);
    assertEquals(0.9, s.recall(), EPS);
    assertEquals(0.9, s.precision(), EPS);
    assertEquals(1.0, s.completeness(), EPS);
    assertEquals(1.0, s.reciprocalRank(), EPS);
    assertEquals(idealGain(9) / idealGain(10), s.ndcg(), EPS);
    assertTrue(s.distanceRatio() > 1.0);
  }
}
