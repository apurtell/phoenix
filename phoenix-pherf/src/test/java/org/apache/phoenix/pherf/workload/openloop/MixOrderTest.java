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
package org.apache.phoenix.pherf.workload.openloop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the workload mix scheduling guarantees required by RQ-190, where the execution order of
 * a mix follows a smooth weighted round-robin schedule over the reduced weights. Each cycle of W
 * arrivals (where W is the sum of the reduced weights) matches the target proportions exactly, and
 * every prefix of N arrivals stays within 1 of N * w / W for each kind. The resulting order depends
 * solely on the arrival sequence.
 */
public class MixOrderTest {

  /** Constructs a one-to-one mapping with one query per kind so that query i belongs to kind i. */
  private static int[][] oneEach(int kinds) {
    int[][] queries = new int[kinds][];
    for (int i = 0; i < kinds; i++) {
      queries[i] = new int[] { i };
    }
    return queries;
  }

  private static long[] counts(MixOrder order, int kinds, long arrivals) {
    long[] counts = new long[kinds];
    for (long k = 0; k < arrivals; k++) {
      counts[order.kind(k)]++;
    }
    return counts;
  }

  @Test
  public void testSeventyTwentyTenIsExact() {
    MixOrder order = new MixOrder(new int[] { 70, 20, 10 }, oneEach(3));
    assertArrayEquals(new long[] { 700, 200, 100 }, counts(order, 3, 1000));
    assertArrayEquals(new long[] { 875, 250, 125 }, counts(order, 3, 1250));
    // Because the weights reduce to 7, 2, and 1, a single cycle consists of 10 arrivals with the
    // kinds distributed smoothly across the cycle.
    int[] cycle = new int[10];
    for (int k = 0; k < 10; k++) {
      cycle[k] = order.kind(k);
    }
    assertArrayEquals(new int[] { 0, 0, 1, 0, 0, 2, 0, 0, 1, 0 }, cycle);
  }

  /**
   * Asserts that the cumulative dispatch discrepancy satisfies |count - N * w / W| < 1 for each
   * kind across every prefix length N up to 10 full cycles.
   */
  private static void assertPrefixBound(int[] weights) {
    int gcd = 0;
    for (int w : weights) {
      gcd = gcd(gcd, w);
    }
    long total = 0;
    for (int w : weights) {
      total += w / gcd;
    }
    MixOrder order = new MixOrder(weights, oneEach(weights.length));
    long[] counts = new long[weights.length];
    for (long n = 1; n <= 10 * total; n++) {
      counts[order.kind(n - 1)]++;
      for (int i = 0; i < weights.length; i++) {
        // Compute the scaled discrepancy count * W - N * w so that the bound check remains exact in
        // integer arithmetic.
        long error = counts[i] * total - n * (weights[i] / gcd);
        assertTrue("weights " + weights[0] + "/" + weights[1] + "/" + weights[2] + " N=" + n,
          Math.abs(error) < total);
      }
      if (n % total == 0) {
        for (int i = 0; i < weights.length; i++) {
          assertEquals(n / total * (weights[i] / gcd), counts[i]);
        }
      }
    }
  }

  private static int gcd(int a, int b) {
    return b == 0 ? a : gcd(b, a % b);
  }

  @Test
  public void testEveryPrefixIsWithinOne() {
    assertPrefixBound(new int[] { 70, 20, 10 });
    for (int a = 1; a <= 20; a++) {
      for (int b = 1; b <= 20; b++) {
        for (int c = 1; c <= 20; c++) {
          assertPrefixBound(new int[] { a, b, c });
        }
      }
    }
  }

  /**
   * Verifies that multiple queries assigned to the same kind execute in turn according to their
   * declared order.
   */
  @Test
  public void testQueriesOfAKindRoundRobin() {
    // Configure kind 0 with queries 0, 2, and 3, while kind 1 is assigned only query 1.
    MixOrder order = new MixOrder(new int[] { 3, 1 }, new int[][] { { 0, 2, 3 }, { 1 } });
    int[] queries = new int[12];
    for (int k = 0; k < queries.length; k++) {
      queries[k] = order.query(k);
    }
    // For weights 3 and 1, each cycle selects the kinds in the sequence 0, 0, 1, 0.
    assertArrayEquals(new int[] { 0, 2, 1, 3, 0, 2, 1, 3, 0, 2, 1, 3 }, queries);
    // When kind 0 has two queries and weight 3, the per-kind ordinal of kind 0 continues advancing
    // seamlessly across cycle boundaries.
    MixOrder two = new MixOrder(new int[] { 3, 1 }, new int[][] { { 0, 2 }, { 1 } });
    int[] alternating = new int[8];
    for (int k = 0; k < alternating.length; k++) {
      alternating[k] = two.query(k);
    }
    assertArrayEquals(new int[] { 0, 2, 1, 0, 2, 0, 1, 2 }, alternating);
    // Verify indexing far into the arrival sequence: for c = (Long.MAX_VALUE / 2) / 4, arrival
    // index k = 4c + 3 corresponds to rank 2 of kind 0 within its cycle, yielding an overall kind 0
    // ordinal of 3c + 2 and selecting query list[(3c + 2) mod 3] = list[2] = 3.
    assertEquals(3, order.query(Long.MAX_VALUE / 2));
  }
}
