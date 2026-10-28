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

/**
 * A bounded accumulator of the k nearest neighbors: a binary max-heap of (distance, ordinal) whose
 * root is the farthest kept neighbor. The order is total, so the result does not depend on the
 * order of the offers, and accumulators of disjoint row sets merge into the same result as one
 * accumulator over their union. Not thread safe.
 */
public final class TopK {

  private final int capacity;
  private final long[] ordinals;
  private final double[] distances;
  private int size;
  private long offered;

  public TopK(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.ordinals = new long[capacity];
    this.distances = new double[capacity];
  }

  public int capacity() {
    return capacity;
  }

  public int size() {
    return size;
  }

  /** Returns the number of rows offered, which is the number of rows that passed. */
  public long offered() {
    return offered;
  }

  /** Returns the distance a candidate must beat to enter a full accumulator. */
  public double bound() {
    return size < capacity ? Double.POSITIVE_INFINITY : distances[0];
  }

  /** Offers one neighbor. */
  public void offer(long ordinal, double distance) {
    offered++;
    if (size < capacity) {
      ordinals[size] = ordinal;
      distances[size] = distance;
      siftUp(size);
      size++;
    } else if (NeighborList.compare(distance, ordinal, distances[0], ordinals[0]) < 0) {
      ordinals[0] = ordinal;
      distances[0] = distance;
      siftDown(0);
    }
  }

  /** Merges another accumulator into this one. */
  public void merge(TopK other) {
    for (int i = 0; i < other.size; i++) {
      offer(other.ordinals[i], other.distances[i]);
    }
    // offer counted the other accumulator's kept entries; count its passing rows instead.
    offered += other.offered - other.size;
  }

  /** Returns the kept neighbors in list order. */
  public NeighborList toList() {
    long[] o = new long[size];
    double[] d = new double[size];
    System.arraycopy(ordinals, 0, o, 0, size);
    System.arraycopy(distances, 0, d, 0, size);
    sort(o, d);
    return new NeighborList(o, d, offered <= capacity);
  }

  private boolean greater(int i, int j) {
    return NeighborList.compare(distances[i], ordinals[i], distances[j], ordinals[j]) > 0;
  }

  private void swap(int i, int j) {
    long o = ordinals[i];
    ordinals[i] = ordinals[j];
    ordinals[j] = o;
    double d = distances[i];
    distances[i] = distances[j];
    distances[j] = d;
  }

  private void siftUp(int i) {
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (!greater(i, parent)) {
        break;
      }
      swap(i, parent);
      i = parent;
    }
  }

  private void siftDown(int i) {
    while (true) {
      int left = 2 * i + 1;
      if (left >= size) {
        break;
      }
      int largest = left;
      int right = left + 1;
      if (right < size && greater(right, left)) {
        largest = right;
      }
      if (!greater(largest, i)) {
        break;
      }
      swap(i, largest);
      i = largest;
    }
  }

  /** Sorts parallel arrays in list order. */
  static void sort(long[] o, double[] d) {
    // Insertion sort is fine for the small lists of the harness; fall back to an index sort.
    int n = o.length;
    if (n <= 64) {
      for (int i = 1; i < n; i++) {
        long oi = o[i];
        double di = d[i];
        int j = i - 1;
        while (j >= 0 && NeighborList.compare(d[j], o[j], di, oi) > 0) {
          o[j + 1] = o[j];
          d[j + 1] = d[j];
          j--;
        }
        o[j + 1] = oi;
        d[j + 1] = di;
      }
      return;
    }
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) {
      idx[i] = i;
    }
    java.util.Arrays.sort(idx, (a, b) -> NeighborList.compare(d[a], o[a], d[b], o[b]));
    long[] o2 = new long[n];
    double[] d2 = new double[n];
    for (int i = 0; i < n; i++) {
      o2[i] = o[idx[i]];
      d2[i] = d[idx[i]];
    }
    System.arraycopy(o2, 0, o, 0, n);
    System.arraycopy(d2, 0, d, 0, n);
  }
}
