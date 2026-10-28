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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.truth.QueryCondition.Attribute;
import org.junit.Test;

public class QueryConditionTest {

  private static DocAttributes doc(int tenant, String docId, int source, int category) {
    return new DocAttributes(1L, tenant, "t" + tenant, docId, source, category, 0, 0L, "");
  }

  private static DocAttributes doc(int tenant, int source, int category) {
    return doc(tenant, "00000000000000000001", source, category);
  }

  @Test
  public void testUnconstrained() {
    QueryCondition c = QueryCondition.builder(QueryShape.UNFILTERED).build();
    assertTrue(c.isUnconstrained());
    assertEquals(QueryShape.UNFILTERED, c.shape());
    assertEquals(-1, c.tenant());
    assertEquals(Attribute.NONE, c.attribute());
    assertNull(c.docIdLow());
    assertNull(c.docIdHigh());
    assertFalse(c.hasRadius());
    assertTrue(Double.isNaN(c.radius()));
    assertEquals(0, c.k());
    assertEquals(1.0, c.selectivity(), 0.0);
    assertTrue(c.test(doc(0, 0, 0)));
    assertTrue(c.test(doc(3, 999, 999)));
    assertTrue(c.withinRadius(Double.MAX_VALUE));
    assertTrue(c.withinRadius(Double.POSITIVE_INFINITY));
    assertTrue(QueryCondition.builder(QueryShape.GLOBAL).build().isUnconstrained());
    assertTrue(
      QueryCondition.builder(QueryShape.PK_RANGE).docIds(null, null).build().isUnconstrained());
  }

  @Test(expected = NullPointerException.class)
  public void testBuilderRequiresShape() {
    QueryCondition.builder(null);
  }

  @Test
  public void testTenant() {
    QueryCondition c = QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(2).build();
    assertFalse(c.isUnconstrained());
    assertEquals(2, c.tenant());
    assertTrue(c.test(doc(2, 5, 5)));
    assertFalse(c.test(doc(1, 5, 5)));
    assertFalse(c.test(doc(3, 5, 5)));
    // Tenant 0 is a real tenant, not the absence of a predicate.
    QueryCondition zero = QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(0).build();
    assertTrue(zero.test(doc(0, 5, 5)));
    assertFalse(zero.test(doc(1, 5, 5)));
  }

  @Test
  public void testCategoryRangeIsHalfOpen() {
    QueryCondition c =
      QueryCondition.builder(QueryShape.COVERED_FILTER).range(Attribute.CATEGORY, 10, 20).build();
    assertFalse(c.isUnconstrained());
    assertEquals(Attribute.CATEGORY, c.attribute());
    assertEquals(10, c.termFrom());
    assertEquals(20, c.termTo());
    assertFalse(c.test(doc(0, 15, 9)));
    assertTrue(c.test(doc(0, 15, 10)));
    assertTrue(c.test(doc(0, 15, 19)));
    assertFalse(c.test(doc(0, 15, 20)));
    // The source is not constrained by a category range.
    assertTrue(c.test(doc(0, 999, 12)));
  }

  @Test
  public void testSourceRangeIsHalfOpen() {
    QueryCondition c =
      QueryCondition.builder(QueryShape.UNCOVERED_FILTER).range(Attribute.SOURCE, 0, 3).build();
    assertTrue(c.test(doc(0, 0, 50)));
    assertTrue(c.test(doc(0, 2, 50)));
    assertFalse(c.test(doc(0, 3, 50)));
    // The category is not constrained by a source range.
    assertTrue(c.test(doc(0, 1, 999)));
  }

  @Test
  public void testEmptyRangePassesNothing() {
    QueryCondition c =
      QueryCondition.builder(QueryShape.COVERED_FILTER).range(Attribute.CATEGORY, 5, 5).build();
    for (int cat = 0; cat < 10; cat++) {
      assertFalse(c.test(doc(0, 0, cat)));
    }
  }

  @Test
  public void testDocIdRange() {
    String low = "40000000";
    String high = "80000000";
    QueryCondition c = QueryCondition.builder(QueryShape.PK_RANGE).docIds(low, high).build();
    assertFalse(c.isUnconstrained());
    assertEquals(low, c.docIdLow());
    assertEquals(high, c.docIdHigh());
    assertFalse(c.test(doc(0, "3fffffff000000000001", 0, 0)));
    // The lower bound is inclusive: a DOC_ID equal to it, or extending it, passes.
    assertTrue(c.test(doc(0, "40000000", 0, 0)));
    assertTrue(c.test(doc(0, "40000000000000000001", 0, 0)));
    assertTrue(c.test(doc(0, "7fffffff999999999999", 0, 0)));
    // The upper bound is exclusive; a DOC_ID extending it orders after it.
    assertFalse(c.test(doc(0, "80000000", 0, 0)));
    assertFalse(c.test(doc(0, "80000000000000000001", 0, 0)));

    QueryCondition lowOnly = QueryCondition.builder(QueryShape.PK_RANGE).docIds(low, null).build();
    assertTrue(lowOnly.test(doc(0, "ffffffff000000000001", 0, 0)));
    assertFalse(lowOnly.test(doc(0, "00000000000000000001", 0, 0)));
    QueryCondition highOnly =
      QueryCondition.builder(QueryShape.PK_RANGE).docIds(null, high).build();
    assertTrue(highOnly.test(doc(0, "00000000000000000001", 0, 0)));
    assertFalse(highOnly.test(doc(0, "ffffffff000000000001", 0, 0)));
  }

  @Test
  public void testDocIdBoundsOfTheWholeSpacePassEveryRow() {
    QueryCondition all = QueryCondition.builder(QueryShape.PK_RANGE)
      .docIds(AttributeModel.docIdBound(0.0), AttributeModel.docIdBound(1.0)).build();
    for (long o = 0; o < 2000; o++) {
      DocAttributes a = new DocAttributes(o, 0, "t0", AttributeModel.docId(o), 0, 0, 0, 0L, "");
      assertTrue(a.docId(), all.test(a));
    }
  }

  @Test
  public void testCombinationIsAConjunction() {
    QueryCondition c = QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(1)
      .range(Attribute.CATEGORY, 10, 20).docIds("4", "8").selectivity(0.01).build();
    assertEquals(0.01, c.selectivity(), 0.0);
    assertTrue(c.test(doc(1, "5000", 0, 15)));
    assertFalse("tenant", c.test(doc(2, "5000", 0, 15)));
    assertFalse("category", c.test(doc(1, "5000", 0, 25)));
    assertFalse("doc id low", c.test(doc(1, "3000", 0, 15)));
    assertFalse("doc id high", c.test(doc(1, "9000", 0, 15)));
  }

  @Test
  public void testRadiusIsStrictAndSeparateFromTest() {
    QueryCondition c =
      QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(1.0, 10).selectivity(0.5).build();
    assertTrue(c.hasRadius());
    assertFalse(c.isUnconstrained());
    assertEquals(1.0, c.radius(), 0.0);
    assertEquals(10, c.k());
    assertTrue(c.withinRadius(0.0));
    assertTrue(c.withinRadius(Math.nextDown(1.0)));
    assertFalse(c.withinRadius(1.0));
    assertFalse(c.withinRadius(1.5));
    assertFalse(c.withinRadius(Double.NaN));
    // The relational test ignores the radius.
    assertTrue(c.test(doc(3, 7, 7)));

    // Inner product distances are negative; the radius is still a strict upper bound.
    QueryCondition ip = QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(-2.0, 10).build();
    assertTrue(ip.withinRadius(-3.0));
    assertFalse(ip.withinRadius(-2.0));
    assertFalse(ip.withinRadius(-1.0));
  }

  @Test
  public void testKeysAreStable() {
    // Keys name cached ground truth lists, so their text must not change.
    assertEquals("unfiltered", QueryCondition.builder(QueryShape.UNFILTERED).build().key());
    assertEquals("global", QueryCondition.builder(QueryShape.GLOBAL).build().key());
    assertEquals("covered_filter/category[10,20)", QueryCondition.builder(QueryShape.COVERED_FILTER)
      .range(Attribute.CATEGORY, 10, 20).build().key());
    assertEquals("uncovered_filter/tenant=2/source[3,4)", QueryCondition
      .builder(QueryShape.UNCOVERED_FILTER).range(Attribute.SOURCE, 3, 4).tenant(2).build().key());
    assertEquals("tenant_scoped/tenant=0",
      QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(0).build().key());
    assertEquals("pk_range/doc[40000000,80000000)",
      QueryCondition.builder(QueryShape.PK_RANGE).docIds("40000000", "80000000").build().key());
    assertEquals("pk_range/doc[,80000000)",
      QueryCondition.builder(QueryShape.PK_RANGE).docIds(null, "80000000").build().key());
    assertEquals("pk_range/doc[40000000,)",
      QueryCondition.builder(QueryShape.PK_RANGE).docIds("40000000", null).build().key());
    assertEquals("distance_bound/radius=" + Double.doubleToLongBits(0.75) + "/k=10",
      QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(0.75, 10).build().key());
    QueryCondition c = QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(1)
      .range(Attribute.CATEGORY, 1, 2).docIds("a", "b").build();
    assertEquals("covered_filter/tenant=1/category[1,2)/doc[a,b)", c.key());
    assertEquals(c.key(), c.toString());
  }

  @Test
  public void testEqualPredicatesGiveEqualConditions() {
    QueryCondition a = QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(1)
      .range(Attribute.CATEGORY, 1, 9).selectivity(0.2).build();
    QueryCondition b = QueryCondition.builder(QueryShape.COVERED_FILTER)
      .range(Attribute.CATEGORY, 1, 9).tenant(1).selectivity(0.2).build();
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals(a.key(), b.key());
    assertNotEquals(a, QueryCondition.builder(QueryShape.UNCOVERED_FILTER).tenant(1)
      .range(Attribute.SOURCE, 1, 9).build());
    assertNotEquals(a, "covered_filter/tenant=1/category[1,9)");
  }

  @Test
  public void testDistinctPredicatesGiveDistinctKeys() {
    int[] tenants = { -1, 0, 1, 12 };
    int[][] ranges = { null, { 0, 5 }, { 0, 6 }, { 1, 6 }, { 10, 100 }, { 1, 10 }, { 11, 0 } };
    Attribute[] attrs = { Attribute.CATEGORY, Attribute.SOURCE };
    String[][] docs =
      { null, { "1", "2" }, { "1", null }, { null, "2" }, { "12", "2" }, { "1", "22" } };
    double[] radii = { Double.NaN, 0.5, 0.25, -0.5, 1.0 / 3.0 };
    int[] ks = { 10, 100 };
    List<QueryCondition> all = new ArrayList<>();
    for (QueryShape shape : QueryShape.values()) {
      for (int t : tenants) {
        for (int[] r : ranges) {
          for (Attribute attr : attrs) {
            if (r == null && attr == Attribute.SOURCE) {
              continue;
            }
            for (String[] d : docs) {
              for (double radius : radii) {
                for (int k : ks) {
                  if (Double.isNaN(radius) && k != ks[0]) {
                    continue;
                  }
                  QueryCondition.Builder b = QueryCondition.builder(shape).tenant(t);
                  if (r != null) {
                    b.range(attr, r[0], r[1]);
                  }
                  if (d != null) {
                    b.docIds(d[0], d[1]);
                  }
                  if (!Double.isNaN(radius)) {
                    b.radius(radius, k);
                  }
                  all.add(b.build());
                }
              }
            }
          }
        }
      }
    }
    Set<String> keys = new HashSet<>();
    for (QueryCondition c : all) {
      assertTrue("duplicate key " + c.key(), keys.add(c.key()));
    }
    assertEquals(all.size(), keys.size());
  }
}
