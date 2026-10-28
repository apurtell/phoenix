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

import java.util.Arrays;

/**
 * Immutable ordered list of nearest neighbors sorted by distance, with ties broken by row ordinal.
 * The {@code complete} flag indicates whether the list contains all matching rows in the dataset.
 */
public final class NeighborList {

  private static final NeighborList EMPTY = new NeighborList(new long[0], new double[0], true);

  private final long[] ordinals;
  private final double[] distances;
  private final boolean complete;

  public NeighborList(long[] ordinals, double[] distances, boolean complete) {
    if (ordinals.length != distances.length) {
      throw new IllegalArgumentException("ordinals and distances differ in length");
    }
    this.ordinals = ordinals;
    this.distances = distances;
    this.complete = complete;
  }

  public static NeighborList empty() {
    return EMPTY;
  }

  public int size() {
    return ordinals.length;
  }

  public long ordinal(int i) {
    return ordinals[i];
  }

  public double distance(int i) {
    return distances[i];
  }

  /**
   * Returns true if this list contains all matching rows satisfying the query condition.
   */
  public boolean complete() {
    return complete;
  }

  /** Returns a new {@link NeighborList} truncated to at most k entries. */
  public NeighborList head(int k) {
    if (k >= ordinals.length) {
      return this;
    }
    return new NeighborList(Arrays.copyOf(ordinals, k), Arrays.copyOf(distances, k), false);
  }

  /** Package-private accessor for the underlying ordinals array. */
  long[] ordinalsArray() {
    return ordinals;
  }

  /** Package-private accessor for the underlying distances array. */
  double[] distancesArray() {
    return distances;
  }

  /** Compares two neighbor entries by distance, breaking ties by row ordinal. */
  public static int compare(double d1, long o1, double d2, long o2) {
    int c = Double.compare(d1, d2);
    return c != 0 ? c : Long.compare(o1, o2);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < Math.min(ordinals.length, 8); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(ordinals[i]).append('@').append(String.format("%.4g", distances[i]));
    }
    if (ordinals.length > 8) {
      sb.append(", ...");
    }
    return sb.append(']').toString();
  }
}
