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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Comparator;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.junit.Test;

public class TopKTest {

  @Test
  public void testRejectsNonPositiveCapacity() {
    for (int capacity : new int[] { 0, -1 }) {
      try {
        new TopK(capacity);
        fail("capacity " + capacity + " was accepted");
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  @Test
  public void testEmptyAccumulator() {
    TopK t = new TopK(3);
    assertEquals(3, t.capacity());
    assertEquals(0, t.size());
    assertEquals(0, t.offered());
    assertEquals(Double.POSITIVE_INFINITY, t.bound(), 0.0);
    NeighborList l = t.toList();
    assertEquals(0, l.size());
    assertTrue(l.complete());
  }

  @Test
  public void testKeepsSmallestForRandomInputs() {
    SplitMix64 rnd = new SplitMix64(0x70504BL);
    for (int trial = 0; trial < 400; trial++) {
      int n = rnd.nextInt(300);
      // Large capacities exercise the index sort of lists longer than 64.
      int capacity = 1 + rnd.nextInt(trial % 3 == 0 ? 160 : 24);
      long[] ordinals = distinctOrdinals(rnd, n);
      double[] distances = randomDistances(rnd, n, trial % 2 == 0);
      TopK t = new TopK(capacity);
      for (int i = 0; i < n; i++) {
        t.offer(ordinals[i], distances[i]);
      }
      String msg = "trial " + trial + " n=" + n + " capacity=" + capacity;
      assertListEquals(msg, reference(ordinals, distances, capacity), t.toList());
      assertEquals(msg, n, t.offered());
      assertEquals(msg, Math.min(n, capacity), t.size());
    }
  }

  @Test
  public void testResultDoesNotDependOnOfferOrder() {
    SplitMix64 rnd = new SplitMix64(17);
    int n = 500;
    long[] ordinals = distinctOrdinals(rnd, n);
    double[] distances = randomDistances(rnd, n, true);
    TopK forward = new TopK(37);
    TopK backward = new TopK(37);
    for (int i = 0; i < n; i++) {
      forward.offer(ordinals[i], distances[i]);
      backward.offer(ordinals[n - 1 - i], distances[n - 1 - i]);
    }
    assertListEquals("order", forward.toList(), backward.toList());
  }

  @Test
  public void testTiesBrokenByOrdinal() {
    TopK t = new TopK(3);
    t.offer(9, 1.0);
    t.offer(4, 1.0);
    t.offer(7, 0.5);
    t.offer(2, 1.0);
    t.offer(5, 1.0);
    NeighborList l = t.toList();
    assertArrayEquals(new long[] { 7, 2, 4 }, ordinals(l));
    assertArrayEquals(new double[] { 0.5, 1.0, 1.0 }, distances(l), 0.0);
    assertFalse(l.complete());
  }

  @Test
  public void testNegativeDistancesOrderAscending() {
    // Inner product distances are negated dot products; the most similar row is the most negative.
    TopK t = new TopK(2);
    t.offer(1, -0.5);
    t.offer(2, -3.0);
    t.offer(3, 2.0);
    t.offer(4, -1.0);
    assertArrayEquals(new long[] { 2, 4 }, ordinals(t.toList()));
  }

  @Test
  public void testMergeOfDisjointInputsEqualsUnion() {
    SplitMix64 rnd = new SplitMix64(99);
    for (int trial = 0; trial < 300; trial++) {
      int n = rnd.nextInt(200);
      int capacity = 1 + rnd.nextInt(trial % 4 == 0 ? 100 : 16);
      long[] ordinals = distinctOrdinals(rnd, n);
      double[] distances = randomDistances(rnd, n, trial % 2 == 1);
      TopK union = new TopK(capacity);
      TopK a = new TopK(capacity);
      TopK b = new TopK(capacity);
      // Skewed splits leave one side empty or under capacity in some trials.
      double share = rnd.nextDouble();
      for (int i = 0; i < n; i++) {
        union.offer(ordinals[i], distances[i]);
        (rnd.nextDouble() < share ? a : b).offer(ordinals[i], distances[i]);
      }
      a.merge(b);
      String msg = "trial " + trial + " n=" + n + " capacity=" + capacity;
      assertListEquals(msg, union.toList(), a.toList());
      assertEquals(msg, union.offered(), a.offered());
      assertEquals(msg, union.size(), a.size());
      assertEquals(msg, union.bound(), a.bound(), 0.0);
    }
  }

  @Test
  public void testMergeCountsPassingRowsNotKeptRows() {
    TopK a = new TopK(2);
    TopK b = new TopK(2);
    for (int i = 0; i < 10; i++) {
      b.offer(i, i);
    }
    a.merge(b);
    assertEquals(10, a.offered());
    assertEquals(2, a.size());
    assertFalse(a.toList().complete());
    assertArrayEquals(new long[] { 0, 1 }, ordinals(a.toList()));

    // Merging an empty accumulator changes nothing.
    TopK c = new TopK(2);
    a.merge(c);
    assertEquals(10, a.offered());
    assertArrayEquals(new long[] { 0, 1 }, ordinals(a.toList()));
  }

  @Test
  public void testMergeOfTwoCompleteAccumulatorsMayOverflow() {
    TopK a = new TopK(5);
    TopK b = new TopK(5);
    for (int i = 0; i < 3; i++) {
      a.offer(i, i);
      b.offer(10 + i, 0.5 + i);
    }
    assertTrue(a.toList().complete());
    assertTrue(b.toList().complete());
    a.merge(b);
    assertEquals(6, a.offered());
    NeighborList l = a.toList();
    assertFalse(l.complete());
    assertArrayEquals(new long[] { 0, 10, 1, 11, 2 }, ordinals(l));
  }

  @Test
  public void testCompleteIffOfferedAtMostCapacity() {
    TopK t = new TopK(4);
    for (int i = 0; i < 4; i++) {
      t.offer(i, 10 - i);
      assertEquals(i + 1, t.offered());
      assertTrue("after " + (i + 1) + " offers", t.toList().complete());
    }
    // A rejected offer still counts as a passing row.
    t.offer(100, 1000.0);
    assertEquals(5, t.offered());
    assertEquals(4, t.size());
    assertFalse(t.toList().complete());
    assertArrayEquals(new long[] { 3, 2, 1, 0 }, ordinals(t.toList()));
  }

  @Test
  public void testBound() {
    TopK t = new TopK(3);
    t.offer(1, 5.0);
    assertEquals(Double.POSITIVE_INFINITY, t.bound(), 0.0);
    t.offer(2, 3.0);
    assertEquals(Double.POSITIVE_INFINITY, t.bound(), 0.0);
    t.offer(3, 4.0);
    assertEquals(5.0, t.bound(), 0.0);
    t.offer(4, 6.0);
    assertEquals(5.0, t.bound(), 0.0);
    t.offer(5, 1.0);
    assertEquals(4.0, t.bound(), 0.0);
    // A tie at the bound enters only with a smaller ordinal than the kept row.
    t.offer(6, 4.0);
    assertArrayEquals(new long[] { 5, 2, 3 }, ordinals(t.toList()));
    t.offer(0, 4.0);
    assertArrayEquals(new long[] { 5, 2, 0 }, ordinals(t.toList()));
    assertEquals(4.0, t.bound(), 0.0);
    assertEquals(7, t.offered());
  }

  @Test
  public void testToListLeavesAccumulatorUsable() {
    SplitMix64 rnd = new SplitMix64(5);
    int n = 300;
    long[] ordinals = distinctOrdinals(rnd, n);
    double[] distances = randomDistances(rnd, n, false);
    TopK t = new TopK(80);
    for (int i = 0; i < n; i++) {
      t.offer(ordinals[i], distances[i]);
      if (i % 37 == 0) {
        assertListEquals("prefix " + i,
          reference(Arrays.copyOf(ordinals, i + 1), Arrays.copyOf(distances, i + 1), 80),
          t.toList());
      }
    }
    assertListEquals("all", reference(ordinals, distances, 80), t.toList());
  }

  @Test
  public void testSortOrdersParallelArrays() {
    SplitMix64 rnd = new SplitMix64(23);
    for (int n : new int[] { 0, 1, 2, 64, 65, 500 }) {
      long[] o = distinctOrdinals(rnd, n);
      double[] d = randomDistances(rnd, n, true);
      NeighborList expected = reference(o, d, n);
      TopK.sort(o, d);
      assertArrayEquals("n=" + n, ordinals(expected), o);
      assertArrayEquals("n=" + n, distances(expected), d, 0.0);
    }
  }

  /** Returns n distinct ordinals in random order, spread over a sparse range. */
  static long[] distinctOrdinals(SplitMix64 rnd, int n) {
    long[] o = new long[n];
    for (int i = 0; i < n; i++) {
      o[i] = 3L * i + rnd.nextInt(3);
    }
    for (int i = n - 1; i > 0; i--) {
      int j = rnd.nextInt(i + 1);
      long t = o[i];
      o[i] = o[j];
      o[j] = t;
    }
    return o;
  }

  /** Returns random distances: a few discrete values, which tie often, or continuous values. */
  static double[] randomDistances(SplitMix64 rnd, int n, boolean discrete) {
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      d[i] = discrete ? (rnd.nextInt(8) - 3) * 0.25 : rnd.nextGaussian();
    }
    return d;
  }

  /** The first k of the inputs sorted by distance, then ordinal. */
  static NeighborList reference(long[] ordinals, double[] distances, int k) {
    Integer[] idx = new Integer[ordinals.length];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = i;
    }
    Arrays.sort(idx,
      Comparator.<Integer> comparingDouble(i -> distances[i]).thenComparingLong(i -> ordinals[i]));
    int n = Math.min(k, idx.length);
    long[] o = new long[n];
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      o[i] = ordinals[idx[i]];
      d[i] = distances[idx[i]];
    }
    return new NeighborList(o, d, ordinals.length <= k);
  }

  static long[] ordinals(NeighborList l) {
    long[] o = new long[l.size()];
    for (int i = 0; i < o.length; i++) {
      o[i] = l.ordinal(i);
    }
    return o;
  }

  static double[] distances(NeighborList l) {
    double[] d = new double[l.size()];
    for (int i = 0; i < d.length; i++) {
      d[i] = l.distance(i);
    }
    return d;
  }
}
