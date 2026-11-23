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
package org.apache.phoenix.pherf.distributed.controller;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.distributed.protocol.Share;

/**
 * Partitions the aggregate workload of a step across its execution slots. A single rule divides
 * each total using the largest remainder method with exact integer arithmetic: the allocated shares
 * sum to the exact total, every share equals either the floor or the ceiling of its exact quota,
 * remainder ties are broken in favor of the lower slot index, and the result is determined solely
 * by the inputs.
 */
public final class Split {
  private Split() {
  }

  /**
   * Divides an aggregate total across slots in proportion to the specified weights.
   * @throws IllegalArgumentException if the total is negative, no weights are provided, or any
   *                                  weight is less than 1.
   */
  public static long[] shares(long total, long[] weights) {
    if (total < 0) {
      throw new IllegalArgumentException("total " + total + " is negative");
    }
    if (weights.length == 0) {
      throw new IllegalArgumentException("no weight");
    }
    BigInteger sum = BigInteger.ZERO;
    for (long weight : weights) {
      if (weight < 1) {
        throw new IllegalArgumentException("weight " + weight + " is below 1");
      }
      sum = sum.add(BigInteger.valueOf(weight));
    }
    int k = weights.length;
    long[] shares = new long[k];
    BigInteger[] remainders = new BigInteger[k];
    long left = total;
    for (int i = 0; i < k; i++) {
      BigInteger[] quota =
        BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
      shares[i] = quota[0].longValueExact();
      remainders[i] = quota[1];
      left -= shares[i];
    }
    // Fewer than k units remain unallocated; distribute one unit to each slot in descending order
    // of its remainder.
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < k; i++) {
      order.add(i);
    }
    Collections.sort(order, (a, b) -> {
      int byRemainder = remainders[b].compareTo(remainders[a]);
      return byRemainder != 0 ? byRemainder : Integer.compare(a, b);
    });
    for (int i = 0; i < left; i++) {
      shares[order.get(i)]++;
    }
    return shares;
  }

  /** Encapsulates the allocated workload share and starting row offset for a single slot. */
  public static final class Slice {
    private final Share share;
    private final long offset;

    Slice(Share share, long offset) {
      this.share = share;
      this.offset = offset;
    }

    public Share getShare() {
      return share;
    }

    /**
     * Returns the starting global row index for the slot, computed as the sum of the row shares
     * allocated to all lower slots.
     */
    public long getOffset() {
      return offset;
    }
  }

  /**
   * Computes the workload slices for a step, producing one slice per slot. The step divides its
   * rate, threads, and rows across slots according to its weights while assigning the full step
   * duration to each slot; any quantity not specified by the step remains null.
   */
  public static List<Slice> slices(StepSpec step) {
    long[] weights = step.getWeights();
    long[] rates = split(step.getRate(), weights);
    long[] threads = split(step.getThreads(), weights);
    long[] rows = split(step.getRows(), weights);
    List<Slice> slices = new ArrayList<>();
    long offset = 0;
    for (int i = 0; i < weights.length; i++) {
      slices.add(new Slice(
        new Share(step.getDurationMs(), at(threads, i), at(rates, i), at(rows, i)), offset));
      if (rows != null) {
        offset += rows[i];
      }
    }
    return slices;
  }

  private static long[] split(Long total, long[] weights) {
    return total == null ? null : shares(total, weights);
  }

  private static Long at(long[] shares, int i) {
    return shares == null ? null : shares[i];
  }
}
