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

import java.math.BigInteger;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.rules.IndexHash;

/**
 * Defines the traversal order for a single reader worker across committed rows. Worker w out of W
 * total workers exclusively reads local indexes i where i mod W = w, partitioning the dataset into
 * disjoint shares that require no shared state across workers.
 * <p>
 * At the beginning of each pass, the traversal captures its active window from the current commit
 * watermark C: the worker's share contains M = (C - 1 - w) / W + 1 indexes when C > w, and none
 * otherwise. For draw p of the pass, across p = 0 .. M - 1, the walk yields the local index w + W *
 * ((a + p * s) mod M). Because the stride step s is constructed to be coprime to M, a complete pass
 * visits every index in the worker's share exactly once. Moreover, because every pass over the same
 * window reproduces the identical permutation, any M consecutive draws also visit each index once.
 * Every index produced by this formula is strictly less than C and is therefore guaranteed to be
 * committed.
 * <p>
 * The MULTIPLICATIVE strategy derives the step s and offset a by hashing the walk seed with the
 * window size M, whereas the SEQUENTIAL strategy sets s = 1 and a = 0. The walk state records the
 * active window and the current draw position within the pass, so a walk reconstructed from a
 * captured state resumes the sequence from that exact point.
 */
public final class Walk {
  // For any modulus up to this threshold, the product of two residues fits within a 64-bit long.
  private static final long DIRECT_PRODUCT_LIMIT = 3037000499L;

  /**
   * Captures the progress of a walk as the active pass window size and the index of the next draw
   * within that pass.
   */
  public static final class State {
    public final long window;
    public final long position;

    public State(long window, long position) {
      this.window = window;
      this.position = position;
    }
  }

  private final long walkSeed;
  private final WalkStrategy strategy;
  private final int worker;
  private final int workers;
  private long window;
  private long position;
  private long step;
  private long start;

  public Walk(long walkSeed, WalkStrategy strategy, int worker, int workers) {
    this(walkSeed, strategy, worker, workers, new State(0, 0));
  }

  public Walk(long walkSeed, WalkStrategy strategy, int worker, int workers, State state) {
    if (workers < 1 || worker < 0 || worker >= workers) {
      throw new IllegalArgumentException("worker " + worker + " of " + workers);
    }
    this.walkSeed = walkSeed;
    this.strategy = strategy;
    this.worker = worker;
    this.workers = workers;
    begin(state.window);
    this.position = state.position;
  }

  /**
   * Returns the next local index drawn from the window of the active pass. Once the current pass
   * completes, a new pass is initialized with a window derived from the current committed count.
   * @param committed the commit watermark, representing the total number of committed local
   *                  indexes.
   * @return the next local index to read, or -1 if this worker's share of the window is empty.
   */
  public synchronized long next(long committed) {
    if (position >= window) {
      begin(committed > worker ? (committed - 1 - worker) / workers + 1 : 0);
      if (window == 0) {
        return -1;
      }
    }
    long p = position++;
    return worker + workers * addmod(start, mulmod(p, step, window), window);
  }

  public synchronized State state() {
    return new State(window, position);
  }

  private void begin(long newWindow) {
    window = newWindow;
    position = 0;
    boolean hashed = strategy != WalkStrategy.SEQUENTIAL && window > 1;
    step = hashed ? step(walkSeed, window) : 1;
    start = hashed ? start(walkSeed, window) : 0;
  }

  /**
   * Computes a coprime stride step for a window of M indexes. The search starts from a hashed
   * candidate in [1, M), advances upward, and wraps around to 1, selecting the first value s that
   * satisfies gcd(s, M) = 1. Any window of size 2 or less always returns a step of 1.
   */
  public static long step(long walkSeed, long window) {
    if (window <= 2) {
      return 1;
    }
    long s = 1 + IndexHash.below(IndexHash.mix64(walkSeed ^ window), window - 1);
    while (gcd(s, window) != 1) {
      s = s == window - 1 ? 1 : s + 1;
    }
    return s;
  }

  /**
   * Computes the deterministic hashed starting offset within [0, M) for a window of M indexes.
   */
  public static long start(long walkSeed, long window) {
    long h = IndexHash.mix64(walkSeed ^ window);
    return IndexHash.below(IndexHash.mix64(h + IndexHash.GAMMA), window);
  }

  public static long gcd(long a, long b) {
    while (b != 0) {
      long t = a % b;
      a = b;
      b = t;
    }
    return Math.abs(a);
  }

  /**
   * Computes (a * b) mod m for operands a and b in [0, m) without 64-bit integer overflow.
   */
  static long mulmod(long a, long b, long m) {
    if (m <= DIRECT_PRODUCT_LIMIT) {
      return a * b % m;
    }
    return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).mod(BigInteger.valueOf(m))
      .longValue();
  }

  /**
   * Computes (a + b) mod m for operands a and b in [0, m) without 64-bit integer overflow.
   */
  private static long addmod(long a, long b, long m) {
    return a >= m - b ? a - (m - b) : a + b;
  }
}
