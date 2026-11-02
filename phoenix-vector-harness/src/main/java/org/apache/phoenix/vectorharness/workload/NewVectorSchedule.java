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
package org.apache.phoenix.vectorharness.workload;

import java.util.Arrays;
import java.util.function.LongPredicate;

/**
 * Gives each operation that writes a new vector a fixed draw index. The index depends only on the
 * steady window and the sequence number of the operation. It never depends on thread timing, so
 * paired runs with the same seed write the same new vectors in the same operations.
 * <p>
 * Each window owns its own range of draw indexes. The range of window {@code w} starts at {@code w}
 * times the range size, and the ranges do not overlap. In its range, a window gives the indexes in
 * sequence order to the operations that write a new vector. A skipped or failed operation keeps its
 * index, so later operations keep theirs. A window that needs more indexes than its range holds
 * fails, because the next index belongs to the next window.
 */
final class NewVectorSchedule {

  /** The largest sequence number that the rank table can hold. */
  private static final int MAX_SEQ = Integer.MAX_VALUE - 16;

  private final long perWindow;
  private final int windows;
  private int window = -1;
  private LongPredicate draws;
  // ranks[s] is the count of operations below sequence number s that write a new vector.
  private int[] ranks = new int[1];
  private int known;

  /**
   * @param perWindow the size of the range of draw indexes of each window
   * @param windows   the count of steady windows in the run
   */
  NewVectorSchedule(long perWindow, int windows) {
    if (perWindow < 0 || windows < 0) {
      throw new IllegalArgumentException(
        "Invalid schedule of " + windows + " windows with " + perWindow + " new vectors each");
    }
    this.perWindow = perWindow;
    this.windows = windows;
  }

  /**
   * Starts the next steady window.
   * @param draws tells, for each sequence number of the window, if the operation writes a new
   *              vector; the answer must depend only on the sequence number
   */
  synchronized void beginWindow(LongPredicate draws) {
    window++;
    this.draws = draws;
    ranks = new int[1];
    known = 0;
  }

  /** Returns the index of the current window, or -1 before the first window. */
  synchronized int window() {
    return window;
  }

  /**
   * Returns the draw index of the operation with sequence number {@code seq} in the current window.
   * @throws IllegalStateException if no window is open, if the operation writes no new vector, or
   *                               if the operation is past the range of its window
   */
  synchronized long draw(long seq) {
    if (window < 0 || draws == null) {
      throw new IllegalStateException("New vector " + seq + " has no steady window");
    }
    if (window >= windows) {
      throw new IllegalStateException("Steady window " + window + " is past the " + windows
        + " planned windows, so it has no range of new vectors");
    }
    if (seq < 0 || seq > MAX_SEQ) {
      throw new IllegalStateException("Sequence number " + seq + " is out of range");
    }
    if (!draws.test(seq)) {
      throw new IllegalStateException("Operation " + seq + " writes no new vector");
    }
    long rank = rank((int) seq);
    if (rank >= perWindow) {
      throw new IllegalStateException("Steady window " + window + " needs more than its "
        + perWindow + " planned new vectors at operation " + seq
        + "; the mutation volume is larger than planned");
    }
    return window * perWindow + rank;
  }

  /** Returns the count of operations below {@code seq} that write a new vector. */
  private int rank(int seq) {
    if (seq >= ranks.length) {
      ranks =
        Arrays.copyOf(ranks, (int) Math.min(MAX_SEQ + 1L, Math.max(seq + 1L, 2L * ranks.length)));
    }
    while (known < seq) {
      ranks[known + 1] = ranks[known] + (draws.test(known) ? 1 : 0);
      known++;
    }
    return ranks[seq];
  }
}
