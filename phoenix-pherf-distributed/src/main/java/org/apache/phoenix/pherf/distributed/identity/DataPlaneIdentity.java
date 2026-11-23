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
package org.apache.phoenix.pherf.distributed.identity;

import java.util.Locale;
import org.apache.phoenix.pherf.rules.IndexHash;

/**
 * Represents the deterministic data-plane identity assigned to a workload slot. The controller
 * derives this identity from the plan seed, the slot index, and the configured stride so that a
 * given plan always produces identical slot identities. Within the workload, the derived seed
 * initializes the seeded data generators, the offset shifts the starting row index, and the prefix
 * forms the leading component of generated keys. Because every prefix is formatted to a uniform
 * width, no slot prefix can ever be a leading substring of another.
 */
public final class DataPlaneIdentity {
  /** Valid slot indices range from 0 through MAX_SLOTS - 1, inclusive. */
  public static final int MAX_SLOTS = 1000;

  private final int slot;
  private final long seed;
  private final long offset;
  private final String prefix;

  private DataPlaneIdentity(int slot, long seed, long offset, String prefix) {
    this.slot = slot;
    this.seed = seed;
    this.offset = offset;
    this.prefix = prefix;
  }

  /**
   * Computes the identity for a single slot where seed = mix64(planSeed + (slot + 1) * GAMMA),
   * offset = slot * stride, and prefix = p000 to p999.
   */
  public static DataPlaneIdentity derive(long planSeed, int slot, long stride) {
    if (slot < 0 || slot >= MAX_SLOTS) {
      throw new IllegalArgumentException("slot " + slot + " is outside 0 to " + (MAX_SLOTS - 1));
    }
    if (stride < 0) {
      throw new IllegalArgumentException("stride " + stride + " is negative");
    }
    long seed = IndexHash.mix64(planSeed + (slot + 1) * IndexHash.GAMMA);
    return new DataPlaneIdentity(slot, seed, Math.multiplyExact((long) slot, stride),
      String.format(Locale.ROOT, "p%03d", slot));
  }

  public int getSlot() {
    return slot;
  }

  public long getSeed() {
    return seed;
  }

  public long getOffset() {
    return offset;
  }

  public String getPrefix() {
    return prefix;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof DataPlaneIdentity)) {
      return false;
    }
    DataPlaneIdentity that = (DataPlaneIdentity) other;
    return slot == that.slot && seed == that.seed && offset == that.offset
      && prefix.equals(that.prefix);
  }

  @Override
  public int hashCode() {
    return Long.hashCode(seed) * 31 + slot;
  }

  @Override
  public String toString() {
    return "slot " + slot + " (seed " + seed + ", offset " + offset + ", prefix " + prefix + ")";
  }
}
