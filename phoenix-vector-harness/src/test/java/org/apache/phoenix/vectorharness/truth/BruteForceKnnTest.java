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

import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.assertListEquals;
import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.naive;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.truth.QueryCondition.Attribute;
import org.junit.Test;

public class BruteForceKnnTest {

  private static final int ROWS = 3000;
  private static final int DIM = 16;
  private static final int CAPACITY = 30;

  private static List<float[]> vectors(List<PanelQuery> panel) {
    List<float[]> out = new ArrayList<>();
    for (PanelQuery q : panel) {
      out.add(q.vector());
    }
    return out;
  }

  /**
   * The conditions of one query: every relational predicate alone and combined, a radius, a radius
   * under a filter, one that few rows pass, and one that no row passes.
   */
  private static List<QueryCondition> conditions(Metric metric, Corpus corpus, PanelQuery q) {
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    QueryCondition unfiltered = QueryCondition.builder(QueryShape.UNFILTERED).build();
    NeighborList near = naive(metric, q.vector(), unfiltered, rows, 41);
    double radius = 0.5 * (near.distance(19) + near.distance(20));
    double wideRadius = 0.5 * (near.distance(39) + near.distance(40));
    int t = q.index() % 4;
    int c0 = q.index() % 10;
    List<QueryCondition> out = new ArrayList<>();
    out.add(unfiltered);
    out.add(QueryCondition.builder(QueryShape.COVERED_FILTER).range(Attribute.CATEGORY, c0, c0 + 8)
      .build());
    out.add(
      QueryCondition.builder(QueryShape.UNCOVERED_FILTER).range(Attribute.SOURCE, 5, 25).build());
    out.add(QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(t).build());
    out.add(QueryCondition.builder(QueryShape.PK_RANGE)
      .docIds(AttributeModel.docIdBound(0.2), AttributeModel.docIdBound(0.6)).build());
    out.add(QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(t)
      .range(Attribute.CATEGORY, 0, 30).docIds(AttributeModel.docIdBound(0.1), null).build());
    out.add(QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(radius, 40).build());
    out.add(
      QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(wideRadius, 80).tenant(t).build());
    // About 3000 / 40 / 4 = 19 rows pass, fewer than the capacity.
    out.add(QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(t)
      .range(Attribute.CATEGORY, 3, 4).build());
    out.add(QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(99).build());
    return out;
  }

  private static void checkAgainstNaive(Metric metric) {
    Corpus corpus = TruthTestSupport.synthetic(ROWS, DIM, metric);
    List<PanelQuery> panel = corpus.panel(6);
    List<List<QueryCondition>> conditions = new ArrayList<>();
    for (PanelQuery q : panel) {
      conditions.add(conditions(metric, corpus, q));
    }
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    List<Map<String, NeighborList>> one =
      new BruteForceKnn(metric, 1).compute(vectors(panel), conditions, rows, CAPACITY, null);
    List<Map<String, NeighborList>> four =
      new BruteForceKnn(metric, 4).compute(vectors(panel), conditions, rows, CAPACITY, null);
    assertEquals(panel.size(), one.size());
    assertEquals(panel.size(), four.size());
    boolean sawFewRows = false;
    boolean sawRadius = false;
    for (int i = 0; i < panel.size(); i++) {
      PanelQuery q = panel.get(i);
      assertEquals(new ArrayList<>(one.get(i).keySet()), new ArrayList<>(four.get(i).keySet()));
      assertEquals(conditions.get(i).size(), one.get(i).size());
      for (QueryCondition c : conditions.get(i)) {
        String msg = metric + " query " + i + " " + c.key();
        NeighborList expected = naive(metric, q.vector(), c, rows, CAPACITY);
        NeighborList actual = one.get(i).get(c.key());
        assertNotNull(msg, actual);
        assertListEquals(msg, expected, actual);
        assertListEquals(msg + " (4 threads)", actual, four.get(i).get(c.key()));
        for (int r = 0; r < actual.size(); r++) {
          assertTrue(msg, c.withinRadius(actual.distance(r)));
          assertTrue(msg, c.test(corpus.attributes(actual.ordinal(r))));
          assertEquals(msg, metric.distance(q.vector(), corpus.vector(actual.ordinal(r))),
            actual.distance(r), 0.0);
        }
        if (actual.complete() && actual.size() > 0 && !c.hasRadius()) {
          sawFewRows = true;
        }
        if (c.hasRadius()) {
          sawRadius = true;
          assertTrue(msg + " radius rows fit in the list", actual.complete());
        }
      }
      NeighborList none = one.get(i).get(conditions.get(i).get(conditions.get(i).size() - 1).key());
      assertEquals(0, none.size());
      assertTrue(none.complete());
      assertFalse(one.get(i).get("unfiltered").complete());
      assertEquals(CAPACITY, one.get(i).get("unfiltered").size());
    }
    assertTrue("some filter should pass fewer rows than the capacity", sawFewRows);
    assertTrue(sawRadius);
  }

  @Test
  public void testMatchesNaiveSortForL2() {
    checkAgainstNaive(Metric.L2);
  }

  @Test
  public void testMatchesNaiveSortForCosine() {
    checkAgainstNaive(Metric.COSINE);
  }

  @Test
  public void testMatchesNaiveSortForInnerProduct() {
    checkAgainstNaive(Metric.INNER_PRODUCT);
  }

  @Test
  public void testIndependentOfThreadCount() {
    Corpus corpus = TruthTestSupport.synthetic(11000, 8, Metric.L2);
    List<PanelQuery> panel = corpus.panel(4);
    List<List<QueryCondition>> conditions = new ArrayList<>();
    for (PanelQuery q : panel) {
      conditions.add(conditions(Metric.L2, corpus, q));
    }
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    List<Map<String, NeighborList>> reference =
      new BruteForceKnn(Metric.L2, 1).compute(vectors(panel), conditions, rows, 100, null);
    for (int threads : new int[] { 0, 2, 3, 4, 8, 64 }) {
      List<Map<String, NeighborList>> other =
        new BruteForceKnn(Metric.L2, threads).compute(vectors(panel), conditions, rows, 100, null);
      for (int i = 0; i < panel.size(); i++) {
        for (QueryCondition c : conditions.get(i)) {
          assertListEquals(threads + " threads, query " + i + " " + c.key(),
            reference.get(i).get(c.key()), other.get(i).get(c.key()));
        }
      }
    }
  }

  @Test
  public void testReportsProgressPerBlock() {
    Corpus corpus = TruthTestSupport.synthetic(5000, 4, Metric.L2);
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    ConcurrentLinkedQueue<Long> seen = new ConcurrentLinkedQueue<>();
    List<List<QueryCondition>> conditions = Collections.singletonList(
      Collections.singletonList(QueryCondition.builder(QueryShape.UNFILTERED).build()));
    new BruteForceKnn(Metric.L2, 2).compute(vectors(corpus.panel(1)), conditions, rows, 5,
      seen::add);
    // 5000 rows are three blocks of at most 2048 rows.
    assertEquals(3, seen.size());
    long max = 0;
    for (long s : seen) {
      assertTrue(s > 0 && s <= 5000);
      max = Math.max(max, s);
    }
    assertEquals(5000, max);
  }

  @Test
  public void testSingleMatchesCompute() {
    Corpus corpus = TruthTestSupport.synthetic(ROWS, DIM, Metric.COSINE);
    PanelQuery q = corpus.panel(3).get(2);
    List<QueryCondition> cs = conditions(Metric.COSINE, corpus, q);
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    BruteForceKnn knn = new BruteForceKnn(Metric.COSINE, 3);
    Map<String, NeighborList> all = knn.compute(Collections.singletonList(q.vector()),
      Collections.singletonList(cs), rows, CAPACITY, null).get(0);
    for (QueryCondition c : cs) {
      assertListEquals(c.key(), all.get(c.key()), knn.single(q.vector(), c, rows, CAPACITY));
    }
  }

  @Test
  public void testQueriesWithoutConditionsAndEmptySources() {
    Corpus corpus = TruthTestSupport.synthetic(100, 4, Metric.L2);
    BruteForceKnn knn = new BruteForceKnn(Metric.L2, 4);
    List<float[]> queries = vectors(corpus.panel(2));
    List<List<QueryCondition>> conditions = new ArrayList<>();
    conditions.add(Collections.emptyList());
    conditions
      .add(Collections.singletonList(QueryCondition.builder(QueryShape.UNFILTERED).build()));
    List<Map<String, NeighborList>> out =
      knn.compute(queries, conditions, RowSource.prefix(corpus, corpus.rows()), 10, null);
    assertTrue(out.get(0).isEmpty());
    assertEquals(10, out.get(1).get("unfiltered").size());

    RowSource empty = RowSource.prefix(corpus, 0);
    out = knn.compute(queries, conditions, empty, 10, null);
    NeighborList l = out.get(1).get("unfiltered");
    assertEquals(0, l.size());
    assertTrue(l.complete());
    assertTrue(knn.compute(Collections.emptyList(), Collections.emptyList(),
      RowSource.prefix(corpus, corpus.rows()), 10, null).isEmpty());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRejectsMismatchedConditions() {
    Corpus corpus = TruthTestSupport.synthetic(100, 4, Metric.L2);
    new BruteForceKnn(Metric.L2, 1).compute(vectors(corpus.panel(2)),
      Collections.singletonList(Collections.emptyList()), RowSource.prefix(corpus, 100), 10, null);
  }

  @Test
  public void testScanFailureIsReported() {
    final RuntimeException boom = new RuntimeException("boom");
    RowSource failing = new RowSource() {
      @Override
      public long ordinalLimit() {
        return 10000;
      }

      @Override
      public void scan(long from, long to, RowVisitor visitor) {
        throw boom;
      }
    };
    try {
      new BruteForceKnn(Metric.L2, 3).single(new float[] { 1f },
        QueryCondition.builder(QueryShape.UNFILTERED).build(), failing, 5);
      fail("the scan failure was swallowed");
    } catch (IllegalStateException e) {
      assertSame(boom, e.getCause());
    }
  }

  @Test
  public void testScansAPrefixOfTheCorpus() {
    Corpus corpus = TruthTestSupport.synthetic(ROWS, DIM, Metric.L2);
    PanelQuery q = corpus.panel(1).get(0);
    QueryCondition unfiltered = QueryCondition.builder(QueryShape.UNFILTERED).build();
    RowSource prefix = RowSource.prefix(corpus, 1000);
    NeighborList l = new BruteForceKnn(Metric.L2, 2).single(q.vector(), unfiltered, prefix, 50);
    assertListEquals("prefix", naive(Metric.L2, q.vector(), unfiltered, prefix, 50), l);
    for (int i = 0; i < l.size(); i++) {
      assertTrue(l.ordinal(i) < 1000);
    }
    // A capacity beyond the prefix holds every row.
    NeighborList all = new BruteForceKnn(Metric.L2, 2).single(q.vector(), unfiltered, prefix, 1000);
    assertEquals(1000, all.size());
    assertTrue(all.complete());
    NeighborList over = new BruteForceKnn(Metric.L2, 2).single(q.vector(), unfiltered, prefix, 999);
    assertFalse(over.complete());
  }
}
