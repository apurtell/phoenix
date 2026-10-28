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
package org.apache.phoenix.vectorharness.corpus;

/**
 * A small, fast pseudorandom generator with a fixed algorithm (SplitMix64), so that every value the
 * harness generates is the same on every JDK. It is not thread safe; each element of a stream gets
 * its own instance from {@link RandomStreams}.
 */
public final class SplitMix64 {

  private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
  private static final double DOUBLE_UNIT = 0x1.0p-53;

  private long state;
  private double spareGaussian;
  private boolean hasSpare;

  public SplitMix64(long seed) {
    this.state = seed;
  }

  /** The SplitMix64 finalizer, a bijective 64-bit mixing function. */
  public static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Mixes two values into one, for deriving the seed of a stream element. */
  public static long combine(long a, long b) {
    return mix64(a ^ mix64(b + GOLDEN_GAMMA));
  }

  public long nextLong() {
    state += GOLDEN_GAMMA;
    return mix64(state);
  }

  /** Returns a uniform double in [0, 1). */
  public double nextDouble() {
    return (nextLong() >>> 11) * DOUBLE_UNIT;
  }

  /** Returns a uniform double in (0, 1], safe for logarithms. */
  public double nextOpenDouble() {
    return ((nextLong() >>> 11) + 1) * DOUBLE_UNIT;
  }

  /** Returns a uniform integer in [0, bound). */
  public int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }
    // Lemire's multiply-shift reduction of the high 32 bits, with rejection of the biased range.
    long r = nextLong() >>> 32;
    long m = r * bound;
    long low = m & 0xFFFFFFFFL;
    if (low < bound) {
      long threshold = (0x100000000L - bound) % bound;
      while (low < threshold) {
        r = nextLong() >>> 32;
        m = r * bound;
        low = m & 0xFFFFFFFFL;
      }
    }
    return (int) (m >>> 32);
  }

  /** Returns a uniform long in [0, bound). */
  public long nextLong(long bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }
    long mask = bound - 1;
    long r = nextLong();
    if ((bound & mask) == 0L) {
      return r & mask;
    }
    long u = r >>> 1;
    while (u + mask - (r = u % bound) < 0L) {
      u = nextLong() >>> 1;
    }
    return r;
  }

  /** Returns a standard normal deviate, by the polar Box-Muller method. */
  public double nextGaussian() {
    if (hasSpare) {
      hasSpare = false;
      return spareGaussian;
    }
    double u;
    double v;
    double s;
    do {
      u = 2.0 * nextDouble() - 1.0;
      v = 2.0 * nextDouble() - 1.0;
      s = u * u + v * v;
    } while (s >= 1.0 || s == 0.0);
    double mul = Math.sqrt(-2.0 * Math.log(s) / s);
    spareGaussian = v * mul;
    hasSpare = true;
    return u * mul;
  }
}
