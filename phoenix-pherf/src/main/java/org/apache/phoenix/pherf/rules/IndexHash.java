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
package org.apache.phoenix.pherf.rules;

import java.nio.charset.StandardCharsets;

/**
 * Provides deterministic hashing primitives for co-generation. Each generated value is computed as
 * a pure function of the row index, the column name, and the scenario seed, ensuring identical
 * results across every JVM and process. These functions do not rely on the system clock, object
 * identity hashes, or a shared random number generator.
 * <p>
 * The mix64 function implements the finalizer from java.util.SplittableRandom (Steele, Lea and
 * Flood 2014, Stafford variant 13), and GAMMA represents its golden ratio increment. Each row
 * defines an ordered stream of draws in which draw 0 governs the null decision and draws 1, 2, ...
 * produce the value in a fixed order.
 */
public final class IndexHash {
  /** Defines the odd increment used to advance the draw stream. */
  public static final long GAMMA = 0x9E3779B97F4A7C15L;

  private static final long FNV_BASIS = 0xCBF29CE484222325L;
  private static final long FNV_PRIME = 0x100000001B3L;

  private IndexHash() {
  }

  /** Applies the SplitMix64 finalizer mixing function to the given 64-bit value. */
  public static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Computes the 64-bit FNV-1a hash over the UTF-8 byte representation of the given name. */
  public static long fnv1a64(String name) {
    long hash = FNV_BASIS;
    for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
      hash = (hash ^ (b & 0xff)) * FNV_PRIME;
    }
    return hash;
  }

  /** Derives the deterministic seed for a single column from the scenario seed and column name. */
  public static long columnSeed(long seed, String name) {
    return mix64(seed ^ fnv1a64(name));
  }

  /** Derives the draw stream seed for a specific row index within a single column. */
  public static long rowSeed(long columnSeed, long index) {
    return mix64(columnSeed ^ mix64(index));
  }

  /** Computes draw k from the draw stream initialized with the given row seed. */
  public static long draw(long rowSeed, int k) {
    return mix64(rowSeed + (k + 1) * GAMMA);
  }

  /**
   * Maps a hash value into the range [0, bound) for a bound of 1 or more, with a modulo bias of
   * less than bound / 2^64.
   */
  public static long below(long hash, long bound) {
    return Long.remainderUnsigned(hash, bound);
  }

  /** Converts a hash value into a uniform double in [0, 1) using its high 53 bits. */
  public static double unit(long hash) {
    return (hash >>> 11) * 0x1.0p-53;
  }
}
