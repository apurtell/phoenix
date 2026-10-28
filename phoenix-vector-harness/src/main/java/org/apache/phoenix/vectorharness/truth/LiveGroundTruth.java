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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;

/**
 * Exact top-k answers that follow a corpus under continuous mutation, without a full scan at each
 * sample. For each tracked panel query and condition the truth keeps a candidate window of
 * {@code k * c} rows and a bound: every passing row outside the window orders after the bound, and
 * every row in the window orders at or before it. Inserts and updates that order before the bound
 * enter the window, evicting its last row and lowering the bound when it overflows; deletes and
 * updates remove a row from the window and leave the bound in place. The first k rows of the window
 * are the exact answer while the window holds at least k rows, or every passing row. When a window
 * falls below k rows it is recomputed by a scan of the live corpus.
 * <p>
 * Mutation workers and the streaming loader call {@link #apply} after each commit, on the timed
 * write path, and the recall sampler reads answers between them. Apply computes distances and
 * predicates outside the monitor, so that writers run it in parallel, and holds the monitor only to
 * edit the windows; a count of the windows that hold each row lets it skip the removal scan for
 * rows in no window, such as streamed base rows and fresh inserts. The other methods are
 * synchronized.
 */
public final class LiveGroundTruth {

  private final Metric metric;
  private final float[][] queries;
  private final QueryCondition[][] conditions;
  private final Window[][] windows;
  private final int capacity;
  private final LiveCorpus corpus;
  private final BruteForceKnn scanner;
  private final AtomicLong recomputes = new AtomicLong();
  private final AtomicLong updates = new AtomicLong();
  /** The number of windows that hold each row, guarded by this. */
  private final Map<Long, Integer> inWindows = new HashMap<>();

  /**
   * Creates live truth from exact lists of the initial state.
   * @param queries    the tracked panel queries
   * @param conditions the conditions of each tracked query
   * @param initial    exact lists of the initial live corpus, by query position and key; lists may
   *                   be longer than the capacity
   * @param capacity   the window capacity, k times the margin c
   */
  public LiveGroundTruth(Metric metric, List<PanelQuery> queries,
    List<List<QueryCondition>> conditions, GroundTruthLookup initial, int capacity,
    LiveCorpus corpus, int scanThreads) {
    this.metric = metric;
    this.capacity = capacity;
    this.corpus = corpus;
    this.scanner = new BruteForceKnn(metric, scanThreads);
    int q = queries.size();
    this.queries = new float[q][];
    this.conditions = new QueryCondition[q][];
    this.windows = new Window[q][];
    for (int i = 0; i < q; i++) {
      this.queries[i] = queries.get(i).vector();
      this.conditions[i] = conditions.get(i).toArray(new QueryCondition[0]);
      this.windows[i] = new Window[this.conditions[i].length];
      for (int c = 0; c < this.conditions[i].length; c++) {
        NeighborList l = initial.get(queries.get(i).index(), this.conditions[i][c].key());
        if (l == null) {
          l = scanner.single(this.queries[i], this.conditions[i][c], corpus, capacity);
          recomputes.incrementAndGet();
        }
        windows[i][c] = new Window(l, capacity);
        enter(windows[i][c]);
      }
    }
  }

  /** Looks up initial lists by panel index and condition key. */
  public interface GroundTruthLookup {
    NeighborList get(int panelIndex, String conditionKey);
  }

  /**
   * Applies one committed mutation. An insert has no prior state; a delete has no new state.
   * @param ordinal the row
   * @param vector  the new vector, or null for a delete
   * @param attrs   the new attributes, or null for a delete
   */
  public void apply(long ordinal, float[] vector, DocAttributes attrs) {
    updates.incrementAndGet();
    // The queries and conditions are immutable, so the per-row work needs no lock.
    double[] distances = null;
    boolean[][] passes = null;
    if (vector != null) {
      distances = new double[queries.length];
      passes = new boolean[queries.length][];
      for (int i = 0; i < queries.length; i++) {
        double d = Double.NaN;
        boolean[] p = new boolean[conditions[i].length];
        for (int c = 0; c < p.length; c++) {
          QueryCondition cond = conditions[i][c];
          if (!cond.test(attrs)) {
            continue;
          }
          if (Double.isNaN(d)) {
            d = metric.distance(queries[i], vector);
          }
          p[c] = cond.withinRadius(d);
        }
        distances[i] = d;
        passes[i] = p;
      }
    }
    synchronized (this) {
      if (inWindows.containsKey(ordinal)) {
        for (Window[] ws : windows) {
          for (Window w : ws) {
            if (w.remove(ordinal)) {
              leave(ordinal);
            }
          }
        }
      }
      if (vector == null) {
        return;
      }
      for (int i = 0; i < queries.length; i++) {
        for (int c = 0; c < passes[i].length; c++) {
          if (!passes[i][c]) {
            continue;
          }
          long evicted = windows[i][c].insert(ordinal, distances[i]);
          if (evicted != Window.NOT_INSERTED) {
            enter(ordinal);
            if (evicted != Window.NONE_EVICTED) {
              leave(evicted);
            }
          }
        }
      }
    }
  }

  /** Counts the rows of a new window. */
  private void enter(Window w) {
    for (int i = 0; i < w.size; i++) {
      enter(w.ordinals[i]);
    }
  }

  private void enter(long ordinal) {
    inWindows.merge(ordinal, 1, Integer::sum);
  }

  private void leave(long ordinal) {
    inWindows.computeIfPresent(ordinal, (o, n) -> n == 1 ? null : n - 1);
  }

  /**
   * Returns the exact first k neighbors of a tracked query under a condition, recomputing the
   * window by a scan if it fell below k rows.
   * @param query     the position of the query among the tracked queries
   * @param condition the position of the condition among the query's conditions
   */
  public synchronized NeighborList topK(int query, int condition, int k) {
    Window w = windows[query][condition];
    if (w.size < k && !w.complete) {
      NeighborList l =
        scanner.single(queries[query], conditions[query][condition], corpus, capacity);
      for (int i = 0; i < w.size; i++) {
        leave(w.ordinals[i]);
      }
      windows[query][condition] = w = new Window(l, capacity);
      enter(w);
      recomputes.incrementAndGet();
    }
    return w.head(k);
  }

  /** Returns a copy of the whole window of a tracked query under a condition. */
  public synchronized NeighborList window(int query, int condition) {
    return windows[query][condition].head(capacity);
  }

  public int trackedQueries() {
    return queries.length;
  }

  public QueryCondition condition(int query, int condition) {
    return conditions[query][condition];
  }

  public int conditionCount(int query) {
    return conditions[query].length;
  }

  /** Returns the number of window recomputations by full scan. */
  public long recomputes() {
    return recomputes.get();
  }

  /** Returns the number of mutations applied. */
  public long updates() {
    return updates.get();
  }

  /** Returns true if the count of the windows that hold each row matches the windows. */
  synchronized boolean countsMatchWindows() {
    Map<Long, Integer> actual = new HashMap<>();
    for (Window[] ws : windows) {
      for (Window w : ws) {
        for (int i = 0; i < w.size; i++) {
          actual.merge(w.ordinals[i], 1, Integer::sum);
        }
      }
    }
    return actual.equals(inWindows);
  }

  /** A candidate window: sorted parallel arrays and the bound of the rows outside. */
  static final class Window {
    /** What {@link #insert} returns for a row that orders after the bound. */
    static final long NOT_INSERTED = -2;
    /** What {@link #insert} returns for an inserted row that evicted none. */
    static final long NONE_EVICTED = -1;

    long[] ordinals;
    double[] distances;
    int size;
    final int capacity;
    // Every passing row outside the window orders after (boundDistance, boundOrdinal).
    double boundDistance;
    long boundOrdinal;
    boolean complete;

    Window(NeighborList initial, int capacity) {
      this.capacity = capacity;
      int n = Math.min(initial.size(), capacity);
      ordinals = new long[capacity + 1];
      distances = new double[capacity + 1];
      for (int i = 0; i < n; i++) {
        ordinals[i] = initial.ordinal(i);
        distances[i] = initial.distance(i);
      }
      size = n;
      complete = initial.complete() && initial.size() <= capacity;
      if (complete) {
        boundDistance = Double.POSITIVE_INFINITY;
        boundOrdinal = Long.MAX_VALUE;
      } else if (n > 0) {
        boundDistance = distances[n - 1];
        boundOrdinal = ordinals[n - 1];
      } else {
        boundDistance = Double.NEGATIVE_INFINITY;
        boundOrdinal = Long.MIN_VALUE;
      }
    }

    /** Removes a row; returns true if the window held it. */
    boolean remove(long ordinal) {
      for (int i = 0; i < size; i++) {
        if (ordinals[i] == ordinal) {
          System.arraycopy(ordinals, i + 1, ordinals, i, size - i - 1);
          System.arraycopy(distances, i + 1, distances, i, size - i - 1);
          size--;
          return true;
        }
      }
      return false;
    }

    /**
     * Inserts a row that the window does not hold. Returns {@link #NOT_INSERTED} if the row orders
     * after the bound, else the row that the insert evicted, which may be the row itself, or
     * {@link #NONE_EVICTED}.
     */
    long insert(long ordinal, double distance) {
      if (NeighborList.compare(distance, ordinal, boundDistance, boundOrdinal) > 0) {
        // Orders after the bound: an outside row, which the bound already accounts for.
        return NOT_INSERTED;
      }
      int pos = size;
      while (
        pos > 0
          && NeighborList.compare(distances[pos - 1], ordinals[pos - 1], distance, ordinal) > 0
      ) {
        pos--;
      }
      System.arraycopy(ordinals, pos, ordinals, pos + 1, size - pos);
      System.arraycopy(distances, pos, distances, pos + 1, size - pos);
      ordinals[pos] = ordinal;
      distances[pos] = distance;
      size++;
      if (size > capacity) {
        // Evict the last row; the new last row becomes the bound.
        size--;
        boundDistance = distances[size - 1];
        boundOrdinal = ordinals[size - 1];
        complete = false;
        return ordinals[size];
      }
      return NONE_EVICTED;
    }

    NeighborList head(int k) {
      int n = Math.min(k, size);
      return new NeighborList(Arrays.copyOf(ordinals, n), Arrays.copyOf(distances, n),
        complete && n == size);
    }
  }
}
