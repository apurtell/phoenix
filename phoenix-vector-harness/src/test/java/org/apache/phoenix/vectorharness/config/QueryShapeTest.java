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
package org.apache.phoenix.vectorharness.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public class QueryShapeTest {

  @Test
  public void testIdsRoundTrip() {
    for (QueryShape s : QueryShape.values()) {
      assertEquals(s.name().toLowerCase(Locale.ROOT), s.id());
      assertEquals(s, QueryShape.parse(s.id()));
      assertEquals(s, QueryShape.parse(s.id().replace('_', '-')));
      assertEquals(s, QueryShape.parse(" " + s.name() + " "));
    }
  }

  @Test
  public void testSweepsSelectivity() {
    Set<QueryShape> selective = EnumSet.noneOf(QueryShape.class);
    for (QueryShape s : QueryShape.values()) {
      if (s.sweepsSelectivity()) {
        selective.add(s);
      }
    }
    assertEquals(EnumSet.of(QueryShape.COVERED_FILTER, QueryShape.UNCOVERED_FILTER), selective);
  }

  @Test
  public void testParseList() {
    assertEquals(EnumSet.allOf(QueryShape.class), QueryShape.parseList("all"));
    assertEquals(EnumSet.allOf(QueryShape.class), QueryShape.parseList(" ALL "));
    assertEquals(EnumSet.allOf(QueryShape.class), QueryShape.parseList(""));
    assertEquals(EnumSet.of(QueryShape.UNFILTERED, QueryShape.COVERED_FILTER),
      QueryShape.parseList("unfiltered, covered-filter"));
    assertEquals(EnumSet.of(QueryShape.DISTANCE_BOUND),
      QueryShape.parseList("distance_bound,,DISTANCE_BOUND"));
    assertEquals(
      EnumSet.of(QueryShape.TENANT_SCOPED, QueryShape.GLOBAL, QueryShape.PK_RANGE,
        QueryShape.UNCOVERED_FILTER),
      QueryShape.parseList("pk_range,global,tenant_scoped,uncovered_filter"));
  }

  @Test
  public void testParseUnknown() {
    try {
      QueryShape.parseList("unfiltered,knn");
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("knn"));
    }
    try {
      QueryShape.parse("all");
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("all"));
    }
  }
}
