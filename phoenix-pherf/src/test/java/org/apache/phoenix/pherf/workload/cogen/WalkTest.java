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
package org.apache.phoenix.pherf.workload.cogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.junit.Test;

/**
 * Verifies the walk invariants defined by RQ-178, RQ-179, RQ-185, and G14. These tests confirm that
 * the walk partitions the committed window across participating workers, resumes seamlessly from
 * its saved state, and visits every index once during a pass using a step that is coprime to the
 * window by construction.
 */
public class WalkTest {
  private static final long[] SEEDS = { 0L, 8070L, -4242424242L };

  /**
   * Verifies RQ-185 by confirming that a single pass of M draws visits every index in the range [0,
   * M) exactly once under both walk strategies.
   */
  @Test
  public void testOnePassVisitsEachIndexOnce() {
    long[] windows = { 1, 2, 3, 4, 7, 8, 30, 210, 997, 1000, 1024, 30030, 65536, 100003 };
    for (WalkStrategy strategy : WalkStrategy.values()) {
      for (long seed : SEEDS) {
        for (long m : windows) {
          Walk walk = new Walk(seed, strategy, 0, 1);
          BitSet seen = new BitSet();
          for (long p = 0; p < m; p++) {
            long i = walk.next(m);
            assertTrue(strategy + " M=" + m + " index " + i, i >= 0 && i < m);
            assertTrue(strategy + " M=" + m + " repeats " + i, !seen.get((int) i));
            seen.set((int) i);
            if (strategy == WalkStrategy.SEQUENTIAL) {
              assertEquals(p, i);
            }
          }
          assertEquals(m, seen.cardinality());
        }
      }
    }
  }

  /**
   * Verifies that the multiplicative walk strategy permutes traversal order rather than producing
   * the sequential identity order.
   */
  @Test
  public void testMultiplicativeIsNotSequential() {
    Walk walk = new Walk(8070L, WalkStrategy.MULTIPLICATIVE, 0, 1);
    int inPlace = 0;
    for (long p = 0; p < 1000; p++) {
      if (walk.next(1000) == p) {
        inPlace++;
      }
    }
    assertTrue("in place " + inPlace, inPlace < 10);
  }

  /**
   * Verifies RQ-178 by ensuring that W workers operating over the same window draw mutually
   * disjoint index sets that collectively cover the entire window, and that two independent runs
   * deterministically produce identical sequences of draws.
   */
  @Test
  public void testPartitionAcrossWorkers() {
    for (long m : new long[] { 1000, 1001 }) {
      for (int workers : new int[] { 2, 4 }) {
        List<List<Long>> first = drawShares(m, workers);
        assertEquals(first, drawShares(m, workers));
        BitSet seen = new BitSet();
        for (int w = 0; w < workers; w++) {
          for (long i : first.get(w)) {
            assertEquals("worker " + w + " of " + workers, w, i % workers);
            assertTrue("M=" + m + " W=" + workers + " repeats " + i, !seen.get((int) i));
            seen.set((int) i);
          }
        }
        assertEquals(m, seen.cardinality());
        assertEquals(m, seen.nextClearBit(0));
      }
    }
  }

  /**
   * Verifies that a worker with no assigned share in the current window receives -1, and begins
   * receiving rows once a later, larger window includes its share.
   */
  @Test
  public void testEmptyShare() {
    Walk walk = new Walk(1L, WalkStrategy.MULTIPLICATIVE, 3, 4);
    assertEquals(-1, walk.next(3));
    assertEquals(3, walk.next(4));
  }

  /**
   * Verifies RQ-179 by confirming that a walk halted mid-pass and restored from its captured state
   * resumes the traversal seamlessly so that the combined sequence matches an uninterrupted pass
   * without repeating or skipping any index. This guarantee is also verified when resuming at a
   * pass boundary and when the committed window expands while the walk is stopped.
   */
  @Test
  public void testResumeFromState() {
    for (int workers : new int[] { 1, 3 }) {
      for (WalkStrategy strategy : WalkStrategy.values()) {
        long share = (2000 - 1) / workers + 1;
        List<Long> uninterrupted = draw(new Walk(9L, strategy, 0, workers), 2000, 2 * share);
        for (long stop : new long[] { 337, share }) {
          Walk walk = new Walk(9L, strategy, 0, workers);
          List<Long> joined = draw(walk, 2000, stop);
          Walk.State state = walk.state();
          joined.addAll(draw(new Walk(9L, strategy, 0, workers, state), 2000, 2 * share - stop));
          assertEquals(workers + " " + strategy + " stop " + stop, uninterrupted, joined);
        }
        // If the window expands while the walk is stopped, the active pass still completes against
        // its original window bound before adopting the expanded window.
        Walk walk = new Walk(9L, strategy, 0, workers);
        List<Long> joined = draw(walk, 2000, 337);
        Walk resumed = new Walk(9L, strategy, 0, workers, walk.state());
        joined.addAll(draw(resumed, 3000, share - 337));
        assertEquals(uninterrupted.subList(0, (int) share), joined);
        List<Long> next = draw(resumed, 3000, (3000 - 1) / workers + 1);
        assertEquals(draw(new Walk(9L, strategy, 0, workers), 3000, next.size()), next);
      }
    }
  }

  /**
   * Verifies the G14 invariants by checking that the computed step is coprime to the window by
   * construction and falls within [1, M), that the starting offset lies within [0, M), and that the
   * overflow-safe modular multiplication matches exact BigInteger arithmetic.
   */
  @Test
  public void testStepIsCoprime() {
    for (long m = 1; m <= 20000; m++) {
      for (long seed : SEEDS) {
        assertStep(seed, m);
      }
    }
    long[] large = { 1L << 40, (1L << 62) + 57, 614889782588491410L, Long.MAX_VALUE };
    Random random = new Random(8070);
    for (long m : large) {
      for (long seed : SEEDS) {
        assertStep(seed, m);
      }
      for (int k = 0; k < 2000; k++) {
        long a = Math.floorMod(random.nextLong(), m);
        long b = Math.floorMod(random.nextLong(), m);
        long want = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).mod(BigInteger.valueOf(m))
          .longValueExact();
        assertEquals(a + " * " + b + " mod " + m, want, Walk.mulmod(a, b, m));
      }
      assertEquals(m - 1, Walk.mulmod(m - 1, 1, m));
      assertEquals(1, Walk.mulmod(m - 1, m - 1, m));
    }
    assertEquals(6, Walk.gcd(12, 18));
    assertEquals(1, Walk.gcd(614889782588491410L, 53));
    assertNotEquals(Walk.step(1L, 1000), Walk.step(2L, 1000));
  }

  /**
   * Verifies that traversing a large window consistently draws distinct indexes that remain within
   * the valid window bounds.
   */
  @Test
  public void testLargeWindowDrawsInRange() {
    long m = (1L << 62) + 57;
    Walk walk = new Walk(5L, WalkStrategy.MULTIPLICATIVE, 0, 1);
    java.util.Set<Long> seen = new java.util.HashSet<>();
    for (int k = 0; k < 10000; k++) {
      long i = walk.next(m);
      assertTrue(i >= 0 && i < m);
      assertTrue(seen.add(i));
    }
  }

  private static void assertStep(long seed, long m) {
    long step = Walk.step(seed, m);
    assertEquals("M=" + m + " step " + step, 1, Walk.gcd(step, m));
    assertTrue("M=" + m + " step " + step, step >= 1 && (m == 1 || step < m));
    long start = Walk.start(seed, m);
    assertTrue("M=" + m + " start " + start, start >= 0 && start < m);
  }

  private static List<List<Long>> drawShares(long m, int workers) {
    List<List<Long>> shares = new ArrayList<>();
    for (int w = 0; w < workers; w++) {
      shares.add(
        draw(new Walk(77L, WalkStrategy.MULTIPLICATIVE, w, workers), m, (m - 1 - w) / workers + 1));
    }
    return shares;
  }

  private static List<Long> draw(Walk walk, long committed, long count) {
    List<Long> draws = new ArrayList<>();
    for (long k = 0; k < count; k++) {
      draws.add(walk.next(committed));
    }
    return draws;
  }
}
