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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.apache.phoenix.vectorharness.config.HarnessConfig.DriftMode;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.RandomStreams;
import org.apache.phoenix.vectorharness.corpus.SyntheticCorpus;
import org.apache.phoenix.vectorharness.truth.BruteForceKnn;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.junit.Before;
import org.junit.Test;

public class ResultScoringTest {

  private static final int ROWS = 600;
  private static final int K = 10;

  private SyntheticCorpus corpus;
  private LiveCorpus live;
  private ResultScoring scoring;
  private PanelQuery query;
  private final BruteForceKnn knn = new BruteForceKnn(Metric.L2, 1);
  private final QueryCondition unfiltered = QueryCondition.builder(QueryShape.UNFILTERED).build();

  @Before
  public void setUp() {
    RandomStreams streams = new RandomStreams(5L);
    AttributeModel attrs = new AttributeModel(streams, 2, 0.0, 10, 10, 4, 0.0, 8);
    corpus = new SyntheticCorpus(5L, ROWS, 4, 4, 0.0, 1.0, 4, 0.05, false, 0.0, 0.0, DriftMode.NONE,
      0.0, Metric.L2, attrs);
    live = new LiveCorpus(corpus, ROWS);
    scoring = new ResultScoring(Metric.L2, 1e-6, live);
    query = corpus.panel(1).get(0);
  }

  private QuerySpec spec(QueryCondition c) {
    return new QuerySpec("SELECT", Collections.emptyList(), null, TableLayout.CORPUS, query, c, K,
      null);
  }

  private static QueryExecutor.Result result(long... ordinals) {
    return new QueryExecutor.Result(ordinals, new double[ordinals.length], 0, 0, 0, 0, 0, 0, 0,
      null);
  }

  private NeighborList exact(QueryCondition c, int n) {
    return knn.single(query.vector(), c, live, n);
  }

  private static long[] ordinals(NeighborList l) {
    long[] o = new long[l.size()];
    for (int i = 0; i < o.length; i++) {
      o[i] = l.ordinal(i);
    }
    return o;
  }

  /** Scores the unfiltered answer, which ignores the predicate, against a condition's truth. */
  private void assertPredicateIsChecked(QueryCondition c) {
    long[] ignored = ordinals(exact(unfiltered, K));
    int passing = 0;
    for (long o : ignored) {
      if (c.test(corpus.attributes(o))) {
        passing++;
      }
    }
    assertTrue("most nearest rows fail " + c.key(), passing < K / 2);
    QueryScore s = scoring.score(spec(c), result(ignored), exact(c, K), K, 0);
    // Every failing row is a miss, though it lies nearer than the k-th true row.
    assertEquals(c.key(), (double) passing / K, s.recall(), 1e-12);
    assertEquals(c.key(), (double) passing / K, s.precision(), 1e-12);
    assertEquals(c.key(), K - passing, s.violations());
    // The correct answer scores perfectly.
    QueryScore right = scoring.score(spec(c), result(ordinals(exact(c, K))), exact(c, K), K, 0);
    assertEquals(1.0, right.recall(), 0.0);
    assertEquals(1.0, right.precision(), 0.0);
    assertEquals(1.0, right.recallAt1(), 0.0);
    assertEquals(0, right.violations());
  }

  @Test
  public void testRowsThatFailTheFilterAreMisses() {
    assertPredicateIsChecked(QueryCondition.builder(QueryShape.COVERED_FILTER)
      .range(QueryCondition.Attribute.CATEGORY, 3, 4).build());
    assertPredicateIsChecked(QueryCondition.builder(QueryShape.UNCOVERED_FILTER)
      .range(QueryCondition.Attribute.SOURCE, 0, 1).tenant(0).build());
  }

  @Test
  public void testRowsOfAnotherTenantAreMisses() {
    QueryCondition tenant = QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(1).build();
    long[] both = ordinals(exact(unfiltered, K));
    int passing = 0;
    for (long o : both) {
      if (corpus.attributes(o).tenant() == 1) {
        passing++;
      }
    }
    QueryScore s = scoring.score(spec(tenant), result(both), exact(tenant, K), K, 0);
    assertEquals((double) passing / K, s.recall(), 1e-12);
    assertEquals(K - passing, s.violations());
  }

  @Test
  public void testRowsOutsideTheRadiusAreMisses() {
    NeighborList u = exact(unfiltered, 2 * K);
    double radius = 0.5 * (u.distance(4) + u.distance(5));
    QueryCondition bounded =
      QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(radius, K).build();
    QueryScore s = scoring.score(spec(bounded), result(ordinals(u.head(K))), u.head(5), K, 0);
    assertEquals(5, s.violations());
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals(0.5, s.precision(), 1e-12);
  }

  @Test
  public void testOnlineSamplingAfterADeleteTheEngineSaw() {
    NeighborList before = exact(unfiltered, 2 * K);
    long[] answer = ordinals(before.head(K));
    long since = System.nanoTime();
    live.delete(answer[4]);
    QueryScore s =
      scoring.score(spec(unfiltered), result(answer), exact(unfiltered, 2 * K), K, since);
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals(1.0, s.ndcg(), 1e-12);
    assertEquals(K - 1, s.returned());
    assertEquals(K - 1, s.truthSize());
    assertEquals(1, s.excluded());
    assertEquals(1, s.excludedReturned());
    // An engine that read after the delete returns the next row, and scores perfectly too.
    long[] later = ordinals(exact(unfiltered, K));
    s = scoring.score(spec(unfiltered), result(later), exact(unfiltered, 2 * K), K, since);
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals(0, s.excludedReturned());
  }

  @Test
  public void testOnlineSamplingAfterAnInsert() {
    long[] answer = ordinals(exact(unfiltered, K));
    long since = System.nanoTime();
    long inserted = live.allocateOrdinal();
    float[] near = query.vector().clone();
    near[0] += 1e-4f;
    live.put(inserted, near, corpus.attributes(0), -1);
    NeighborList window = exact(unfiltered, 2 * K);
    assertEquals(inserted, window.ordinal(0));
    // The engine saw the insert: it took the first place of the answer.
    long[] seen = new long[K];
    seen[0] = inserted;
    System.arraycopy(answer, 0, seen, 1, K - 1);
    QueryScore s = scoring.score(spec(unfiltered), result(seen), window, K, since);
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals("one changed row counts once", 1, s.excluded());
    assertEquals(1, s.excludedReturned());
    assertEquals(K - 1, s.truthSize());
    // The engine did not see it.
    s = scoring.score(spec(unfiltered), result(answer), window, K, since);
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals(1, s.excluded());
    assertEquals(0, s.excludedReturned());
    assertEquals(K, s.truthSize());
  }
}
