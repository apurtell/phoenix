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
import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.RandomStreams;
import org.apache.phoenix.vectorharness.corpus.Vocabulary;
import org.junit.Test;

public class ConditionPlannerTest {

  private static AttributeModel attributes(HarnessConfig config) {
    return CorpusFactory.attributeModel(config, new RandomStreams(config.seed()));
  }

  private static ConditionPlanner planner(HarnessConfig config) {
    return new ConditionPlanner(config, attributes(config));
  }

  private static PanelQuery query(int index, int tenant) {
    return new PanelQuery(index, new float[] { 1f, 0f }, PanelQuery.Kind.IN_DISTRIBUTION, tenant,
      -1);
  }

  private static List<QueryCondition> ofShape(List<QueryCondition> conditions, QueryShape s) {
    List<QueryCondition> out = new ArrayList<>();
    for (QueryCondition c : conditions) {
      if (c.shape() == s) {
        out.add(c);
      }
    }
    return out;
  }

  @Test
  public void testActiveShapesOfASingleTenantTable() {
    List<QueryShape> shapes = planner(config()).activeShapes();
    assertEquals(Arrays.asList(QueryShape.UNFILTERED, QueryShape.COVERED_FILTER,
      QueryShape.UNCOVERED_FILTER, QueryShape.PK_RANGE, QueryShape.DISTANCE_BOUND), shapes);
  }

  @Test
  public void testActiveShapesOfAMultiTenantTable() {
    List<QueryShape> shapes = planner(config("table.multi_tenant", "true")).activeShapes();
    assertEquals(Arrays.asList(QueryShape.values()), shapes);
  }

  @Test
  public void testTenantScopedNeedsSeveralTenantsOrAMultiTenantTable() {
    List<QueryShape> shapes = planner(config("corpus.tenants", "4")).activeShapes();
    assertTrue(shapes.contains(QueryShape.TENANT_SCOPED));
    assertFalse("GLOBAL needs a multi-tenant table", shapes.contains(QueryShape.GLOBAL));
    shapes = planner(config("corpus.tenants", "1", "table.multi_tenant", "false")).activeShapes();
    assertFalse(shapes.contains(QueryShape.TENANT_SCOPED));
    shapes = planner(config("corpus.tenants", "1", "table.multi_tenant", "true")).activeShapes();
    assertTrue(shapes.contains(QueryShape.TENANT_SCOPED));
    assertTrue(shapes.contains(QueryShape.GLOBAL));
  }

  @Test
  public void testActiveShapesFollowTheProfileSubset() {
    ConditionPlanner p = planner(config("query.shapes", "pk_range,unfiltered"));
    assertEquals(Arrays.asList(QueryShape.UNFILTERED, QueryShape.PK_RANGE), p.activeShapes());
    assertFalse(p.distanceBounded());
    assertTrue(planner(config("query.shapes", "global")).activeShapes().isEmpty());
    assertTrue(planner(config("query.shapes", "distance_bound")).distanceBounded());
  }

  @Test
  public void testTruthCapacity() {
    assertEquals(201, ConditionPlanner.truthCapacity(config()));
    assertEquals(31, ConditionPlanner
      .truthCapacity(config("query.k.values", "5,20", "recall.truth.margin", "1.5")));
    assertEquals(11,
      ConditionPlanner.truthCapacity(config("query.k.values", "10", "recall.truth.margin", "1")));
  }

  @Test
  public void testOneConditionPerShapeAndSelectivity() {
    HarnessConfig config = config("table.multi_tenant", "true", "corpus.tenants", "5",
      "query.filter.selectivity", "0.001,0.01,0.1,1.0");
    ConditionPlanner p = planner(config);
    for (int q = 0; q < 50; q++) {
      List<QueryCondition> conditions = p.relational(query(q, q % 5));
      assertEquals(1, ofShape(conditions, QueryShape.UNFILTERED).size());
      assertEquals(1, ofShape(conditions, QueryShape.GLOBAL).size());
      assertEquals(4, ofShape(conditions, QueryShape.COVERED_FILTER).size());
      assertEquals(4, ofShape(conditions, QueryShape.UNCOVERED_FILTER).size());
      assertEquals(1, ofShape(conditions, QueryShape.TENANT_SCOPED).size());
      assertEquals(1, ofShape(conditions, QueryShape.PK_RANGE).size());
      // Distance-bounded conditions depend on distances and are not relational.
      assertEquals(0, ofShape(conditions, QueryShape.DISTANCE_BOUND).size());
      assertEquals(12, conditions.size());
      Set<String> keys = new HashSet<>();
      for (QueryCondition c : conditions) {
        assertTrue("duplicate key " + c.key(), keys.add(c.key()));
        assertFalse(c.hasRadius());
      }
      for (QueryCondition c : ofShape(conditions, QueryShape.COVERED_FILTER)) {
        assertEquals(QueryCondition.Attribute.CATEGORY, c.attribute());
      }
      for (QueryCondition c : ofShape(conditions, QueryShape.UNCOVERED_FILTER)) {
        assertEquals(QueryCondition.Attribute.SOURCE, c.attribute());
      }
      assertTrue(ofShape(conditions, QueryShape.UNFILTERED).get(0).isUnconstrained());
      assertTrue(ofShape(conditions, QueryShape.GLOBAL).get(0).isUnconstrained());
    }
  }

  @Test
  public void testSelectivityOfUniformVocabulariesIsExact() {
    HarnessConfig config = config("corpus.filter.categories", "1000", "corpus.filter.sources",
      "1000", "query.filter.selectivity", "0.001,0.01,0.1,1.0");
    ConditionPlanner p = planner(config);
    double[] targets = config.filterSelectivities();
    for (int q = 0; q < 40; q++) {
      List<QueryCondition> covered = ofShape(p.relational(query(q, 0)), QueryShape.COVERED_FILTER);
      for (int i = 0; i < targets.length; i++) {
        QueryCondition c = covered.get(i);
        assertEquals(c.key(), targets[i], c.selectivity(), 1e-9);
        assertEquals(c.key(), Math.round(targets[i] * 1000), c.termTo() - c.termFrom());
        assertTrue(c.key(), c.termFrom() >= 0 && c.termTo() <= 1000);
      }
    }
  }

  @Test
  public void testSelectivityOfSkewedVocabulariesIsCloseToTheTarget() {
    HarnessConfig config = config("corpus.filter.categories", "1000", "corpus.filter.sources",
      "500", "corpus.filter.skew", "1.0", "query.filter.selectivity", "0.001,0.01,0.1,0.5");
    AttributeModel attrs = attributes(config);
    ConditionPlanner p = new ConditionPlanner(config, attrs);
    double[] targets = config.filterSelectivities();
    for (int q = 0; q < 40; q++) {
      List<QueryCondition> conditions = p.relational(query(q, 0));
      List<QueryCondition> covered = ofShape(conditions, QueryShape.COVERED_FILTER);
      List<QueryCondition> uncovered = ofShape(conditions, QueryShape.UNCOVERED_FILTER);
      for (int i = 0; i < targets.length; i++) {
        checkRange(covered.get(i), attrs.categories(), targets[i]);
        checkRange(uncovered.get(i), attrs.sources(), targets[i]);
      }
    }
  }

  private static void checkRange(QueryCondition c, Vocabulary vocab, double target) {
    // The analytic selectivity is the mass of the chosen range, and that mass is near the target.
    assertEquals(c.key(), vocab.rangeProbability(c.termFrom(), c.termTo()), c.selectivity(), 1e-12);
    assertEquals(c.key() + " target " + target, target, c.selectivity(), 0.05 * target);
  }

  @Test
  public void testAnalyticSelectivityMatchesTheRows() {
    HarnessConfig config = config("corpus.tenants", "4", "corpus.filter.categories", "200",
      "corpus.filter.sources", "200", "corpus.filter.skew", "0.8", "corpus.body.bytes", "0",
      "query.filter.selectivity", "0.05,0.2", "query.pk_range.fraction", "0.3");
    AttributeModel attrs = attributes(config);
    ConditionPlanner p = new ConditionPlanner(config, attrs);
    int rows = 20000;
    List<DocAttributes> docs = new ArrayList<>(rows);
    for (long o = 0; o < rows; o++) {
      docs.add(attrs.attributes(o));
    }
    for (int q = 0; q < 6; q++) {
      for (QueryCondition c : p.relational(query(q, q % 4))) {
        int passing = 0;
        for (DocAttributes a : docs) {
          if (c.test(a)) {
            passing++;
          }
        }
        double s = c.selectivity();
        double observed = (double) passing / rows;
        double tolerance = 5 * Math.sqrt(s * (1 - s) / rows) + 1e-9;
        assertEquals(c.key(), s, observed, tolerance);
      }
    }
  }

  @Test
  public void testRangesOfOneSelectivityMoveAcrossQueries() {
    HarnessConfig uniform = config("query.filter.selectivity", "0.01");
    HarnessConfig skewed = config("query.filter.selectivity", "0.01", "corpus.filter.skew", "1");
    for (HarnessConfig config : new HarnessConfig[] { uniform, skewed }) {
      ConditionPlanner p = planner(config);
      Set<Integer> starts = new HashSet<>();
      for (int q = 0; q < 20; q++) {
        starts.add(ofShape(p.relational(query(q, 0)), QueryShape.COVERED_FILTER).get(0).termFrom());
      }
      assertTrue("starts " + starts, starts.size() >= 10);
    }
  }

  @Test
  public void testDeterministicAcrossPlannerInstances() {
    String[] settings = { "table.multi_tenant", "true", "corpus.tenants", "7", "corpus.seed", "11",
      "query.tenant_scoped.fraction", "0.5", "corpus.filter.skew", "0.7" };
    ConditionPlanner a = planner(config(settings));
    ConditionPlanner b = planner(config(settings));
    ConditionPlanner otherSeed = planner(config(settings).with("corpus.seed", "12"));
    boolean seedMatters = false;
    for (int q = 0; q < 50; q++) {
      List<QueryCondition> ca = a.relational(query(q, q % 7));
      List<QueryCondition> cb = b.relational(query(q, q % 7));
      assertEquals(ca.size(), cb.size());
      for (int i = 0; i < ca.size(); i++) {
        assertEquals(ca.get(i).key(), cb.get(i).key());
        assertEquals(ca.get(i).selectivity(), cb.get(i).selectivity(), 0.0);
      }
      // Calling again on the same planner gives the same conditions.
      assertEquals(ca, a.relational(query(q, q % 7)));
      if (!ca.equals(otherSeed.relational(query(q, q % 7)))) {
        seedMatters = true;
      }
    }
    assertTrue("the seed should change some PK ranges or scopes", seedMatters);
  }

  @Test
  public void testTenantScoping() {
    // On a multi-tenant table every filtered condition is scoped when the fraction is 1.
    ConditionPlanner scoped = planner(config("table.multi_tenant", "true", "corpus.tenants", "3",
      "query.tenant_scoped.fraction", "1.0"));
    // At fraction 0, only the TENANT_SCOPED and PK_RANGE shapes name a tenant.
    ConditionPlanner unscoped = planner(config("table.multi_tenant", "true", "corpus.tenants", "3",
      "query.tenant_scoped.fraction", "0.0"));
    // A single-tenant table with several tenants never scopes the filtered shapes.
    ConditionPlanner single =
      planner(config("corpus.tenants", "3", "query.tenant_scoped.fraction", "1.0"));
    for (int q = 0; q < 30; q++) {
      PanelQuery pq = query(q, q % 3);
      for (QueryCondition c : scoped.relational(pq)) {
        boolean expectsTenant =
          c.shape() != QueryShape.UNFILTERED && c.shape() != QueryShape.GLOBAL;
        assertEquals(c.key(), expectsTenant ? pq.tenant() : -1, c.tenant());
      }
      // PK_RANGE always binds the query's tenant, so that its DOC_ID bounds are a key range.
      for (QueryCondition c : unscoped.relational(pq)) {
        boolean expectsTenant =
          c.shape() == QueryShape.TENANT_SCOPED || c.shape() == QueryShape.PK_RANGE;
        assertEquals(c.key(), expectsTenant ? pq.tenant() : -1, c.tenant());
      }
      for (QueryCondition c : single.relational(pq)) {
        boolean expectsTenant =
          c.shape() == QueryShape.TENANT_SCOPED || c.shape() == QueryShape.PK_RANGE;
        assertEquals(c.key(), expectsTenant ? pq.tenant() : -1, c.tenant());
      }
    }
  }

  @Test
  public void testTenantScopedSelectivityIsTheTenantShare() {
    HarnessConfig config = config("corpus.tenants", "5", "corpus.tenant.skew", "1.0");
    AttributeModel attrs = attributes(config);
    ConditionPlanner p = new ConditionPlanner(config, attrs);
    for (int t = 0; t < 5; t++) {
      QueryCondition c = ofShape(p.relational(query(t, t)), QueryShape.TENANT_SCOPED).get(0);
      assertEquals(t, c.tenant());
      assertEquals(attrs.tenants().probability(t), c.selectivity(), 0.0);
    }
  }

  @Test
  public void testPkRangeWidthIsTheConfiguredFraction() {
    for (double w : new double[] { 0.01, 0.25, 0.6 }) {
      ConditionPlanner p = planner(config("query.pk_range.fraction", Double.toString(w)));
      Set<String> lows = new HashSet<>();
      for (int q = 0; q < 50; q++) {
        QueryCondition c = ofShape(p.relational(query(q, 0)), QueryShape.PK_RANGE).get(0);
        assertEquals(w, c.selectivity(), 0.0);
        double low = hashFraction(c.docIdLow());
        double high = hashFraction(c.docIdHigh());
        assertTrue(c.key(), low >= 0.0 && high <= 1.0);
        assertEquals(c.key(), w, high - low, 2.0 / 4294967296.0);
        lows.add(c.docIdLow());
      }
      assertTrue("PK ranges should start at different keys", lows.size() > 40);
    }
  }

  @Test
  public void testPkRangeOfTheWholeSpacePassesEveryRow() {
    HarnessConfig config = config("query.pk_range.fraction", "1.0");
    AttributeModel attrs = attributes(config);
    QueryCondition c =
      ofShape(new ConditionPlanner(config, attrs).relational(query(3, 0)), QueryShape.PK_RANGE)
        .get(0);
    for (long o = 0; o < 5000; o++) {
      assertTrue(c.test(attrs.attributes(o)));
    }
  }

  private static double hashFraction(String bound) {
    if (bound.equals("~")) {
      return 1.0;
    }
    return Long.parseLong(bound, 16) / 4294967296.0;
  }

  private static NeighborList ramp(int n, boolean complete) {
    long[] o = new long[n];
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      o[i] = 1000 - i;
      d[i] = 0.1 * (i + 1);
    }
    return new NeighborList(o, d, complete);
  }

  @Test
  public void testDistanceBoundRadiusLiesBetweenRanks() {
    ConditionPlanner p =
      planner(config("query.k.values", "7,10,100", "query.distance_bound.rank", "0.5"));
    NeighborList unfiltered = ramp(201, false);
    List<QueryCondition> bounded = p.distanceBound(unfiltered);
    assertEquals(3, bounded.size());
    int[] ks = { 7, 10, 100 };
    int[] ranks = { 4, 5, 50 };
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < ks.length; i++) {
      QueryCondition c = bounded.get(i);
      int r = ranks[i];
      assertEquals(QueryShape.DISTANCE_BOUND, c.shape());
      assertTrue(c.hasRadius());
      assertEquals(ks[i], c.k());
      assertEquals((double) r / ks[i], c.selectivity(), 1e-12);
      assertTrue(c.key(), unfiltered.distance(r - 1) < c.radius());
      assertTrue(c.key(), c.radius() < unfiltered.distance(r));
      assertEquals(0.5 * (unfiltered.distance(r - 1) + unfiltered.distance(r)), c.radius(), 0.0);
      assertTrue(keys.add(c.key()));
      NeighborList prefix = ConditionPlanner.boundedPrefix(unfiltered, c);
      assertEquals(r, prefix.size());
      assertTrue(prefix.complete());
      for (int j = 0; j < r; j++) {
        assertEquals(unfiltered.ordinal(j), prefix.ordinal(j));
        assertEquals(unfiltered.distance(j), prefix.distance(j), 0.0);
      }
    }
  }

  @Test
  public void testDistanceBoundSkipsShortListsAndUsesAtLeastRankOne() {
    ConditionPlanner p =
      planner(config("query.k.values", "10,100", "query.distance_bound.rank", "0.5"));
    // Rank 50 of k=100 needs 51 neighbors.
    List<QueryCondition> bounded = p.distanceBound(ramp(50, false));
    assertEquals(1, bounded.size());
    assertEquals(10, bounded.get(0).k());
    assertEquals(2, p.distanceBound(ramp(51, false)).size());
    assertTrue(p.distanceBound(ramp(5, true)).isEmpty());
    assertTrue(p.distanceBound(NeighborList.empty()).isEmpty());

    ConditionPlanner tiny =
      planner(config("query.k.values", "10", "query.distance_bound.rank", "0.01"));
    QueryCondition c = tiny.distanceBound(ramp(20, false)).get(0);
    assertEquals(0.15, c.radius(), 1e-12);
    assertEquals(1, ConditionPlanner.boundedPrefix(ramp(20, false), c).size());
  }

  @Test
  public void testNoDistanceBoundWithoutTheShape() {
    ConditionPlanner p = planner(config("query.shapes", "unfiltered,pk_range"));
    assertTrue(p.distanceBound(ramp(300, false)).isEmpty());
  }

  @Test
  public void testBoundedPrefixCompleteness() {
    QueryCondition wide = QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(1e9, 10).build();
    // Every listed row lies within the radius, so the prefix is as complete as the list.
    NeighborList partial = ConditionPlanner.boundedPrefix(ramp(30, false), wide);
    assertEquals(30, partial.size());
    assertFalse(partial.complete());
    NeighborList all = ConditionPlanner.boundedPrefix(ramp(30, true), wide);
    assertEquals(30, all.size());
    assertTrue(all.complete());
    // The radius is strict: a row exactly at the radius is outside.
    QueryCondition exact = QueryCondition.builder(QueryShape.DISTANCE_BOUND)
      .radius(ramp(30, false).distance(3), 10).build();
    NeighborList strict = ConditionPlanner.boundedPrefix(ramp(30, false), exact);
    assertEquals(3, strict.size());
    assertTrue(strict.complete());
    QueryCondition none = QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(0.0, 10).build();
    NeighborList empty = ConditionPlanner.boundedPrefix(ramp(30, false), none);
    assertEquals(0, empty.size());
    assertTrue(empty.complete());
  }

  @Test
  public void testBoundedPrefixEqualsAScanWithTheRadius() {
    for (Metric metric : Metric.values()) {
      HarnessConfig config = TruthTestSupport.syntheticConfig(3000, 16, metric, "query.k.values",
        "4,10,40", "query.distance_bound.rank", "0.5");
      Corpus corpus = TruthTestSupport.open(config);
      ConditionPlanner p = new ConditionPlanner(config, corpus.attributeModel());
      RowSource rows = RowSource.prefix(corpus, corpus.rows());
      BruteForceKnn knn = new BruteForceKnn(metric, 2);
      int capacity = ConditionPlanner.truthCapacity(config);
      QueryCondition unfilteredCondition = QueryCondition.builder(QueryShape.UNFILTERED).build();
      for (PanelQuery q : corpus.panel(5)) {
        NeighborList unfiltered = knn.single(q.vector(), unfilteredCondition, rows, capacity);
        List<QueryCondition> bounded = p.distanceBound(unfiltered);
        assertEquals(3, bounded.size());
        for (QueryCondition c : bounded) {
          String msg = metric + " query " + q.index() + " " + c.key();
          NeighborList prefix = ConditionPlanner.boundedPrefix(unfiltered, c);
          assertListEquals(msg, knn.single(q.vector(), c, rows, capacity), prefix);
          assertListEquals(msg, TruthTestSupport.naive(metric, q.vector(), c, rows, capacity),
            prefix);
          int r = (int) Math.ceil(0.5 * c.k());
          if (unfiltered.distance(r - 1) != unfiltered.distance(r)) {
            assertEquals(msg, r, prefix.size());
          }
          for (int i = 0; i < prefix.size(); i++) {
            assertTrue(msg, prefix.distance(i) < c.radius());
          }
          assertNotEquals(msg, unfilteredCondition.key(), c.key());
        }
      }
    }
  }
}
