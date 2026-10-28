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
 * An immutable list of neighbors in ascending order of distance, ties broken by ascending row
 * ordinal. The list may be shorter than requested when fewer rows pass a predicate; the
 * {@code complete} flag then says that the list holds every passing row.
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
   * Returns true if the list holds every row that passes its predicate, which is the case when
   * fewer rows pass than the list could hold.
   */
  public boolean complete() {
    return complete;
  }

  /** Returns the first k neighbors. */
  public NeighborList head(int k) {
    if (k >= ordinals.length) {
      return this;
    }
    return new NeighborList(Arrays.copyOf(ordinals, k), Arrays.copyOf(distances, k), false);
  }

  /** Returns the ordinals, which callers must not modify. */
  long[] ordinalsArray() {
    return ordinals;
  }

  /** Returns the distances, which callers must not modify. */
  double[] distancesArray() {
    return distances;
  }

  /** Compares two neighbors in list order. */
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
