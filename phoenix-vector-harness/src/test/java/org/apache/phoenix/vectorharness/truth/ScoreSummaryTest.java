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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.junit.Test;

public class ScoreSummaryTest {

  private static final double NAN = Double.NaN;

  /** A score with the given recall and precision; the other metrics are fixed. */
  private static QueryScore score(double recall, double precision, int returned, int excluded) {
    return new QueryScore(recall, precision, 1.0, 1.0, 0.5, 0.75, 1.0, NAN, NAN, returned, 10,
      excluded);
  }

  private static List<QueryScore> randomScores(int n, long seed) {
    SplitMix64 rnd = new SplitMix64(seed);
    List<QueryScore> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      // Recall of a query of k = 10 is a multiple of 0.1, mostly high.
      double recall = Math.min(10, 6 + rnd.nextInt(5) + rnd.nextInt(2)) / 10.0;
      double precision = rnd.nextDouble() < 0.1 ? NAN : rnd.nextDouble();
      out.add(score(recall, precision, 10, 0));
    }
    return out;
  }

  private static double mean(List<QueryScore> scores, int metric) {
    double sum = 0.0;
    int n = 0;
    for (QueryScore s : scores) {
      double v = s.values()[metric];
      if (!Double.isNaN(v)) {
        sum += v;
        n++;
      }
    }
    return sum / n;
  }

  @Test
  public void testMeans() {
    List<QueryScore> scores = Arrays.asList(score(1.0, 1.0, 10, 0), score(0.5, NAN, 10, 0),
      score(0.0, 0.5, 10, 0), score(0.5, NAN, 10, 0));
    ScoreSummary s = ScoreSummary.of(scores, 200, 1);
    assertEquals(4, s.queries());
    assertEquals(0.5, s.recall().mean(), 1e-12);
    assertEquals(4, s.recall().count());
    assertSame(s.recall(), s.get("recall"));
    // NaN values are left out of their metric.
    assertEquals(0.75, s.get("precision").mean(), 1e-12);
    assertEquals(2, s.get("precision").count());
    assertEquals(1.0, s.get("completeness").mean(), 0.0);
    assertEquals(0.5, s.get("mrr").mean(), 0.0);
    assertEquals(0.75, s.get("ndcg").mean(), 0.0);
    // A metric with no defined value is NaN throughout.
    ScoreSummary.Estimate range = s.get("range_recall");
    assertTrue(Double.isNaN(range.mean()));
    assertTrue(Double.isNaN(range.low()));
    assertTrue(Double.isNaN(range.high()));
    assertEquals(0, range.count());
  }

  @Test
  public void testEstimatesCoverEveryMetricInOrder() {
    ScoreSummary s = ScoreSummary.of(randomScores(10, 3), 100, 1);
    List<String> expected = new ArrayList<>(Arrays.asList(QueryScore.NAMES));
    expected.add("exclusion_ratio");
    expected.add("violation_ratio");
    assertEquals(expected, new ArrayList<>(s.estimates().keySet()));
  }

  @Test
  public void testBootstrapIsDeterministic() {
    List<QueryScore> scores = randomScores(60, 9);
    ScoreSummary a = ScoreSummary.of(scores, 500, 42);
    ScoreSummary b = ScoreSummary.of(new ArrayList<>(scores), 500, 42);
    ScoreSummary other = ScoreSummary.of(scores, 500, 43);
    boolean seedMatters = false;
    for (String name : QueryScore.NAMES) {
      ScoreSummary.Estimate ea = a.get(name);
      ScoreSummary.Estimate eb = b.get(name);
      assertEquals(name, ea.mean(), eb.mean(), 0.0);
      assertEquals(name, ea.low(), eb.low(), 0.0);
      assertEquals(name, ea.high(), eb.high(), 0.0);
      assertEquals(name, ea.mean(), other.get(name).mean(), 0.0);
      if (ea.low() != other.get(name).low() || ea.high() != other.get(name).high()) {
        seedMatters = true;
      }
    }
    assertTrue("another seed should draw other resamples", seedMatters);
  }

  @Test
  public void testIntervalContainsTheMeanAndNarrowsWithMoreQueries() {
    List<QueryScore> few = randomScores(20, 5);
    List<QueryScore> many = randomScores(2000, 5);
    ScoreSummary small = ScoreSummary.of(few, 1000, 7);
    ScoreSummary large = ScoreSummary.of(many, 1000, 7);
    for (String name : new String[] { "recall", "precision" }) {
      int m = Arrays.asList(QueryScore.NAMES).indexOf(name);
      for (ScoreSummary s : new ScoreSummary[] { small, large }) {
        ScoreSummary.Estimate e = s.get(name);
        assertEquals(name, s == small ? mean(few, m) : mean(many, m), e.mean(), 1e-12);
        assertTrue(name + " " + e, e.low() <= e.mean() && e.mean() <= e.high());
        assertTrue(name + " " + e, e.low() < e.high());
      }
      double narrow = large.get(name).high() - large.get(name).low();
      double wide = small.get(name).high() - small.get(name).low();
      // The width shrinks like 1 / sqrt(n): a hundred times the queries, a tenth of the width.
      assertTrue(name + ": " + narrow + " vs " + wide, narrow < 0.3 * wide);
    }
    // A metric that is the same for every query has no spread.
    ScoreSummary.Estimate completeness = large.get("completeness");
    assertEquals(1.0, completeness.low(), 0.0);
    assertEquals(1.0, completeness.high(), 0.0);
  }

  @Test
  public void testIntervalOfKnownValues() {
    // Half zeros and half ones: the bootstrap mean has a standard error of 0.5 / sqrt(n).
    double[] values = new double[400];
    for (int i = 0; i < values.length; i++) {
      values[i] = i % 2;
    }
    ScoreSummary.Estimate e = ScoreSummary.estimate(values, 2000, 11);
    assertEquals(0.5, e.mean(), 1e-12);
    assertEquals(400, e.count());
    double halfWidth = 1.96 * 0.5 / Math.sqrt(values.length);
    assertEquals(0.5 - halfWidth, e.low(), 0.01);
    assertEquals(0.5 + halfWidth, e.high(), 0.01);
  }

  @Test
  public void testDegenerateInputs() {
    ScoreSummary.Estimate one = ScoreSummary.estimate(new double[] { 0.3 }, 1000, 1);
    assertEquals(0.3, one.mean(), 0.0);
    assertEquals(0.3, one.low(), 0.0);
    assertEquals(0.3, one.high(), 0.0);
    assertEquals(1, one.count());
    ScoreSummary.Estimate noResamples = ScoreSummary.estimate(new double[] { 0.0, 1.0, NAN }, 0, 1);
    assertEquals(0.5, noResamples.mean(), 0.0);
    assertEquals(0.5, noResamples.low(), 0.0);
    assertEquals(0.5, noResamples.high(), 0.0);
    assertEquals(2, noResamples.count());
    ScoreSummary.Estimate allNaN = ScoreSummary.estimate(new double[] { NAN, NAN }, 1000, 1);
    assertTrue(Double.isNaN(allNaN.mean()));
    assertEquals(0, allNaN.count());

    ScoreSummary empty = ScoreSummary.of(Collections.emptyList(), 1000, 1);
    assertEquals(0, empty.queries());
    assertEquals(0, empty.excluded());
    assertTrue(Double.isNaN(empty.recall().mean()));
    assertEquals(0.0, empty.get("exclusion_ratio").mean(), 0.0);
  }

  @Test
  public void testExclusionRatio() {
    List<QueryScore> scores =
      Arrays.asList(score(1.0, 1.0, 8, 2), score(1.0, 1.0, 10, 0), score(1.0, NAN, 0, 0));
    ScoreSummary s = ScoreSummary.of(scores, 100, 1);
    assertEquals(2, s.excluded());
    assertEquals(3, s.queries());
    ScoreSummary.Estimate ratio = s.get("exclusion_ratio");
    assertEquals(2.0 / 20, ratio.mean(), 1e-12);
    assertEquals(ratio.mean(), ratio.low(), 0.0);
    assertEquals(ratio.mean(), ratio.high(), 0.0);
    assertEquals(3, ratio.count());

    ScoreSummary none = ScoreSummary.of(Collections.singletonList(score(1.0, NAN, 0, 0)), 100, 1);
    assertEquals(0.0, none.get("exclusion_ratio").mean(), 0.0);
  }

  @Test
  public void testExclusionRatioCountsReturnedRowsOnly() {
    // Four rows excluded in all, of which one was returned; three were truth rows only.
    QueryScore a = new QueryScore(1, 1, 1, 1, 1, 1, 1, NAN, NAN, 9, 9, 4, 1, 0);
    QueryScore b = new QueryScore(1, 1, 1, 1, 1, 1, 1, NAN, NAN, 10, 10, 0, 0, 0);
    ScoreSummary s = ScoreSummary.of(Arrays.asList(a, b), 100, 1);
    assertEquals(4, s.excluded());
    assertEquals(1.0 / 20, s.get("exclusion_ratio").mean(), 1e-12);
  }

  @Test
  public void testViolationRatio() {
    QueryScore a = new QueryScore(0.5, 0.5, 1, 1, 1, 1, 1, NAN, NAN, 10, 10, 0, 0, 5);
    QueryScore b = new QueryScore(1, 1, 1, 1, 1, 1, 1, NAN, NAN, 10, 10, 0, 0, 0);
    ScoreSummary s = ScoreSummary.of(Arrays.asList(a, b), 100, 1);
    assertEquals(5, s.violations());
    assertEquals(5.0 / 20, s.get("violation_ratio").mean(), 1e-12);
    assertEquals(0.0,
      ScoreSummary.of(Collections.singletonList(b), 100, 1).get("violation_ratio").mean(), 0.0);
    assertEquals(0.0,
      ScoreSummary.of(Collections.emptyList(), 100, 1).get("violation_ratio").mean(), 0.0);
  }

  @Test
  public void testEstimateToString() {
    assertEquals("0.5000 [0.2500,0.7500]",
      new ScoreSummary.Estimate(0.5, 0.25, 0.75, 4).toString());
  }
}
