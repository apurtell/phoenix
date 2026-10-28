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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NeighborListTest {

  private static NeighborList list(boolean complete, long... ordinals) {
    double[] d = new double[ordinals.length];
    for (int i = 0; i < d.length; i++) {
      d[i] = 0.5 * (i + 1);
    }
    return new NeighborList(ordinals, d, complete);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRejectsArraysOfDifferentLength() {
    new NeighborList(new long[2], new double[3], false);
  }

  @Test
  public void testAccessors() {
    NeighborList l = list(true, 7, 3, 9);
    assertEquals(3, l.size());
    assertEquals(7, l.ordinal(0));
    assertEquals(9, l.ordinal(2));
    assertEquals(0.5, l.distance(0), 0.0);
    assertEquals(1.5, l.distance(2), 0.0);
    assertTrue(l.complete());
    assertFalse(list(false, 1).complete());
  }

  @Test
  public void testEmpty() {
    NeighborList e = NeighborList.empty();
    assertEquals(0, e.size());
    assertTrue(e.complete());
    assertSame(e, NeighborList.empty());
    assertSame(e, e.head(10));
    assertEquals("[]", e.toString());
  }

  @Test
  public void testHeadShorterThanListIsIncomplete() {
    NeighborList l = list(true, 4, 5, 6, 7, 8);
    NeighborList h = l.head(3);
    assertEquals(3, h.size());
    assertEquals(4, h.ordinal(0));
    assertEquals(6, h.ordinal(2));
    assertEquals(1.5, h.distance(2), 0.0);
    // The head of a complete list omits passing rows, so it is not complete.
    assertFalse(h.complete());
    NeighborList zero = l.head(0);
    assertEquals(0, zero.size());
    assertFalse(zero.complete());
  }

  @Test
  public void testHeadCoveringListIsTheList() {
    NeighborList complete = list(true, 4, 5, 6);
    assertSame(complete, complete.head(3));
    assertSame(complete, complete.head(100));
    assertTrue(complete.head(3).complete());
    NeighborList partial = list(false, 4, 5, 6);
    assertSame(partial, partial.head(3));
    assertFalse(partial.head(10).complete());
  }

  @Test
  public void testCompareOrdersByDistanceThenOrdinal() {
    assertTrue(NeighborList.compare(1.0, 9, 2.0, 1) < 0);
    assertTrue(NeighborList.compare(2.0, 1, 1.0, 9) > 0);
    assertTrue(NeighborList.compare(1.0, 1, 1.0, 2) < 0);
    assertTrue(NeighborList.compare(1.0, 2, 1.0, 1) > 0);
    assertEquals(0, NeighborList.compare(1.0, 2, 1.0, 2));
    assertTrue(NeighborList.compare(-3.0, 5, -1.0, 1) < 0);
    assertTrue(NeighborList.compare(Double.NEGATIVE_INFINITY, Long.MIN_VALUE, -1e300, 0) < 0);
    assertTrue(NeighborList.compare(1e300, Long.MAX_VALUE, Double.POSITIVE_INFINITY, 0) < 0);
  }

  @Test
  public void testToStringAbbreviatesLongLists() {
    // The digits follow the default locale, so only the structure is checked.
    String shortList = list(true, 7, 3).toString();
    assertTrue(shortList, shortList.startsWith("[7@"));
    assertTrue(shortList, shortList.contains(", 3@"));
    assertTrue(shortList, shortList.endsWith("]"));
    assertFalse(shortList, shortList.contains("..."));
    String s = list(false, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10).toString();
    assertTrue(s, s.startsWith("[1@"));
    assertTrue(s, s.contains(", 8@"));
    assertTrue(s, s.endsWith(", ...]"));
    assertFalse(s, s.contains("9@"));
  }
}
