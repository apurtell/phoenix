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

import java.util.BitSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;

/**
 * The client-side record of the committed state of the data table: the base rows that the generator
 * reproduces, overlaid with the rows that mutations inserted, updated, or deleted. A base row is
 * present once it is committed, by the load phase or by streaming ingest, and is regenerated from
 * the corpus on demand; inserted rows have ordinals from the corpus row count upward. The record
 * also tracks rows with writes in flight, the time of the last committed write of each overlaid
 * row, and the time each range of base rows was streamed in after the record was created, for
 * online recall sampling.
 */
public final class LiveCorpus implements RowSource {

  /** The state of one row. */
  public static final class RowState {
    private final float[] vector;
    private final DocAttributes attributes;
    private final int cluster;
    private final boolean deleted;
    private final long committedNanos;

    public RowState(float[] vector, DocAttributes attributes, int cluster, boolean deleted,
      long committedNanos) {
      this.vector = vector;
      this.attributes = attributes;
      this.cluster = cluster;
      this.deleted = deleted;
      this.committedNanos = committedNanos;
    }

    public float[] vector() {
      return vector;
    }

    public DocAttributes attributes() {
      return attributes;
    }

    public int cluster() {
      return cluster;
    }

    public boolean deleted() {
      return deleted;
    }

    /** Returns the System.nanoTime of the commit that wrote this state, or 0 for base rows. */
    public long committedNanos() {
      return committedNanos;
    }
  }

  private final Corpus corpus;
  private final BitSet present = new BitSet();
  private final AtomicLong nextOrdinal;
  private final ConcurrentSkipListMap<Long, RowState> overlay = new ConcurrentSkipListMap<>();
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  /** Ranges of base rows committed after creation: range start to {end, System.nanoTime}. */
  private final ConcurrentSkipListMap<Long, long[]> loadedAt = new ConcurrentSkipListMap<>();
  private final AtomicLong liveCount = new AtomicLong();
  private volatile long presentCount;

  /** Creates the record of a table that holds base rows [0, loaded). */
  public LiveCorpus(Corpus corpus, long loaded) {
    this.corpus = corpus;
    this.nextOrdinal = new AtomicLong(corpus.rows());
    setPresent(0, Math.min(loaded, corpus.rows()));
  }

  public Corpus corpus() {
    return corpus;
  }

  /** Returns the number of base rows committed. */
  public long loaded() {
    return presentCount;
  }

  /**
   * Records that the base rows [from, to) are committed, now. Online sampling treats them as
   * changed at this time, since a query that started before may or may not have seen them.
   */
  public void markLoaded(long from, long to) {
    long end = Math.min(to, corpus.rows());
    if (end <= from) {
      return;
    }
    // Stamp the rows before they become present, so that no reader sees them unstamped.
    loadedAt.merge(Math.max(0, from), new long[] { end, System.nanoTime() },
      (a, b) -> new long[] { Math.max(a[0], b[0]), Math.max(a[1], b[1]) });
    setPresent(from, end);
  }

  private void setPresent(long from, long end) {
    if (end <= from) {
      return;
    }
    if (end > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Corpora of more than 2^31 rows are not supported");
    }
    synchronized (present) {
      int start = (int) Math.max(0, from);
      BitSet before = present.get(start, (int) end);
      present.set(start, (int) end);
      presentCount = present.cardinality();
      // Count the rows that become present, except overlaid rows, which were counted when
      // their overlay was written.
      long added = 0;
      for (int i = before.nextClearBit(0); i < end - start; i = before.nextClearBit(i + 1)) {
        if (!overlay.containsKey((long) start + i)) {
          added++;
        }
      }
      liveCount.addAndGet(added);
    }
  }

  private boolean basePresent(long ordinal) {
    if (ordinal < 0 || ordinal >= corpus.rows()) {
      return false;
    }
    synchronized (present) {
      return present.get((int) ordinal);
    }
  }

  /** Allocates the ordinal of a row that a mutation inserts. */
  public long allocateOrdinal() {
    return nextOrdinal.getAndIncrement();
  }

  /** Returns the current number of live rows. */
  public long liveCount() {
    return liveCount.get();
  }

  /** Returns the number of overlaid rows: rows that mutations inserted, updated, or deleted. */
  public int overlaidCount() {
    return overlay.size();
  }

  /**
   * Returns true if the live rows differ from the committed base rows: some base row was updated or
   * deleted, or some inserted row is still live. Static ground truth over the base rows is exact
   * only while this is false.
   */
  public boolean divergesFromBase() {
    for (Map.Entry<Long, RowState> e : overlay.entrySet()) {
      long o = e.getKey();
      if (o < corpus.rows()) {
        if (basePresent(o)) {
          return true;
        }
      } else if (!e.getValue().deleted) {
        return true;
      }
    }
    return false;
  }

  /** Returns the overlaid ordinals and their states, in ordinal order. */
  public Map<Long, RowState> overlays() {
    return overlay;
  }

  /**
   * Returns the state of a live row, or null if the row does not exist or is deleted. Base rows are
   * regenerated from the corpus.
   */
  public RowState get(long ordinal) {
    RowState s = overlay.get(ordinal);
    if (s != null) {
      return s.deleted ? null : s;
    }
    if (basePresent(ordinal)) {
      return new RowState(corpus.vector(ordinal), corpus.attributes(ordinal),
        corpus.clusterOf(ordinal), false, 0L);
    }
    return null;
  }

  /** Returns the overlaid state of a row, deleted or not, or null if the row is not overlaid. */
  public RowState overlaid(long ordinal) {
    return overlay.get(ordinal);
  }

  /** Records a committed insert or update. */
  public void put(long ordinal, float[] vector, DocAttributes attributes, int cluster) {
    RowState before =
      overlay.put(ordinal, new RowState(vector, attributes, cluster, false, System.nanoTime()));
    boolean wasLive = before != null ? !before.deleted : basePresent(ordinal);
    if (!wasLive) {
      liveCount.incrementAndGet();
    }
  }

  /** Records a committed delete. */
  public void delete(long ordinal) {
    RowState before = overlay.put(ordinal, new RowState(null, null, -1, true, System.nanoTime()));
    boolean wasLive = before != null ? !before.deleted : basePresent(ordinal);
    if (wasLive) {
      liveCount.decrementAndGet();
    }
  }

  /** Forgets the overlay of a row, after the row was restored to its base state. */
  public void forget(long ordinal) {
    RowState before = overlay.remove(ordinal);
    if (before == null) {
      return;
    }
    boolean wasLive = !before.deleted;
    boolean nowLive = basePresent(ordinal);
    if (wasLive && !nowLive) {
      liveCount.decrementAndGet();
    } else if (!wasLive && nowLive) {
      liveCount.incrementAndGet();
    }
  }

  /** Marks a row as having a write in flight; returns false if it already has one. */
  public boolean beginWrite(long ordinal) {
    return inFlight.add(ordinal);
  }

  public void endWrite(long ordinal) {
    inFlight.remove(ordinal);
  }

  public boolean writeInFlight(long ordinal) {
    return inFlight.contains(ordinal);
  }

  public int inFlightCount() {
    return inFlight.size();
  }

  /**
   * Returns true if a row's state may differ from its state at a past instant: it has a write in
   * flight, its last write committed at or after that instant, or it is a base row streamed in at
   * or after that instant.
   */
  public boolean changedSince(long ordinal, long sinceNanos) {
    if (inFlight.contains(ordinal)) {
      return true;
    }
    RowState s = overlay.get(ordinal);
    if (s != null && s.committedNanos - sinceNanos >= 0) {
      return true;
    }
    Map.Entry<Long, long[]> range = loadedAt.floorEntry(ordinal);
    return range != null && ordinal < range.getValue()[0] && range.getValue()[1] - sinceNanos >= 0;
  }

  /**
   * Draws a live row uniformly at random among the rows that pass a filter, or returns -1 if none
   * is found. Rejection sampling over the ordinal space, so it is fast while most ordinals are
   * live.
   */
  public long randomLive(SplitMix64 rnd, LongPredicate accept) {
    long limit = ordinalLimit();
    if (limit <= 0 || liveCount.get() == 0) {
      return -1;
    }
    for (int attempt = 0; attempt < 4096; attempt++) {
      long o = rnd.nextLong(limit);
      if (isLive(o) && (accept == null || accept.test(o))) {
        return o;
      }
    }
    return -1;
  }

  public boolean isLive(long ordinal) {
    RowState s = overlay.get(ordinal);
    if (s != null) {
      return !s.deleted;
    }
    return basePresent(ordinal);
  }

  @Override
  public long ordinalLimit() {
    Map.Entry<Long, RowState> last = overlay.lastEntry();
    long overlayTop = last == null ? 0 : last.getKey() + 1;
    long baseTop;
    synchronized (present) {
      baseTop = present.length();
    }
    return Math.max(baseTop, overlayTop);
  }

  @Override
  public void scan(long from, long to, RowVisitor visitor) {
    long baseEnd = Math.min(to, corpus.rows());
    long start = Math.max(0, from);
    if (start < baseEnd) {
      BitSet slice;
      synchronized (present) {
        slice = present.get((int) start, (int) baseEnd);
      }
      for (int i = slice.nextSetBit(0); i >= 0; i = slice.nextSetBit(i + 1)) {
        long ordinal = start + i;
        if (overlay.containsKey(ordinal)) {
          continue;
        }
        visitor.visit(ordinal, corpus.vector(ordinal), corpus.attributes(ordinal));
      }
    }
    for (Map.Entry<Long, RowState> e : overlay.subMap(from, true, to, false).entrySet()) {
      RowState s = e.getValue();
      if (!s.deleted) {
        visitor.visit(e.getKey(), s.vector, s.attributes);
      }
    }
  }
}
