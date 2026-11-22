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

/**
 * Determines the execution order of an operation mix by mapping each arrival in an open-loop
 * schedule to a specific query. Because the mapping is a pure function of the arrival sequence, the
 * resulting order is independent of worker threads and the clock.
 * <p>
 * The weights are first reduced by dividing by their greatest common divisor. A single cycle spans
 * W positions, where W is the sum of these reduced weights. A smooth weighted round-robin algorithm
 * populates the cycle by assigning each position to the kind that is furthest behind its share
 * (breaking ties in favor of the kind declared first), so that kind i receives w_i positions
 * distributed across the cycle. Arrival k receives the kind at position k mod W. Its ordinal within
 * that kind is (k / W) * w_i plus the rank of the position among the positions of that kind, and it
 * executes query (ordinal mod m) among the m queries of that kind in declared order.
 * <p>
 * Each full cycle matches the weight proportions exactly. After N arrivals, the execution count of
 * each kind is within 1 of N * w_i / W.
 */
public final class MixOrder {
  /**
   * Defines the maximum allowed sum of weights, which bounds the maximum cycle length. This value
   * is an upper bound rather than a configurable setting.
   */
  public static final int MAX_TOTAL_WEIGHT = 10_000;

  private final int[] weights;
  private final int[][] queries;
  private final int[] kinds;
  private final int[] ranks;

  /**
   * Constructs a mix order schedule from validated weights and query index lists. The mix
   * validation in the query executor verifies the arguments beforehand: at least one kind must be
   * present, each kind must have a weight of at least 1 and at least one query, and the sum of the
   * weights must not exceed {@link #MAX_TOTAL_WEIGHT}.
   * @param weights the weight assigned to each operation kind.
   * @param queries the query indexes for each operation kind, in declared order.
   */
  public MixOrder(int[] weights, int[][] queries) {
    int gcd = 0;
    for (int weight : weights) {
      gcd = gcd(gcd, weight);
    }
    this.weights = new int[weights.length];
    int cycle = 0;
    for (int i = 0; i < weights.length; i++) {
      this.weights[i] = weights[i] / gcd;
      cycle += this.weights[i];
    }
    this.queries = queries.clone();
    this.kinds = new int[cycle];
    this.ranks = new int[cycle];
    int[] current = new int[weights.length];
    int[] used = new int[weights.length];
    for (int p = 0; p < cycle; p++) {
      int best = 0;
      for (int i = 0; i < current.length; i++) {
        current[i] += this.weights[i];
        if (current[i] > current[best]) {
          best = i;
        }
      }
      current[best] -= cycle;
      kinds[p] = best;
      ranks[p] = used[best]++;
    }
  }

  private static int gcd(int a, int b) {
    return b == 0 ? a : gcd(b, a % b);
  }

  /**
   * Returns the operation kind assigned to the arrival at the given sequence number, expressed as
   * an index into the weights array.
   */
  public int kind(long sequence) {
    return kinds[(int) (sequence % kinds.length)];
  }

  /**
   * Returns the query index selected for the arrival at the given sequence number from the query
   * list of its assigned kind.
   */
  public int query(long sequence) {
    int position = (int) (sequence % kinds.length);
    int kind = kinds[position];
    long ordinal = sequence / kinds.length * weights[kind] + ranks[position];
    int[] list = queries[kind];
    return list[(int) (ordinal % list.length)];
  }
}
