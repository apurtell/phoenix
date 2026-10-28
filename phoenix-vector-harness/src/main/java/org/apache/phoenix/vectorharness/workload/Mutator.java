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

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.Embedding;
import org.apache.phoenix.vectorharness.corpus.RandomStreams;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.LiveGroundTruth;

/**
 * Applies one mutation at a time through a {@link RowWriter}: inserts of new rows, updates, and
 * deletes, mixed by the configured shares. Updates are attribute-only changes, local re-embeddings
 * that stay in the row's cluster, or cross-cluster moves, whose share drift can raise. Each
 * mutation marks its row in flight, writes and commits it, then records the committed state in the
 * client-side corpus and the live ground truth, all inside the write gate, so that truth always
 * reflects every committed write. A failed write is rolled back, so that none of its statements
 * rides along with a later commit. A commit that fails may still have reached some or all of the
 * tables, so its row is unresolved: it stays marked in flight, which keeps later mutations and
 * online scoring away from it, until the run phase rewrites it at restore. Targets and vectors are
 * deterministic functions of the window and the mutation sequence number, given a single worker and
 * no freshness probes: the drift clock follows the schedule of the mutation rate, not the wall
 * clock.
 */
public final class Mutator {

  /** The kinds of mutations. */
  public enum Kind {
    INSERT,
    UPDATE_ATTRIBUTES,
    UPDATE_LOCAL,
    UPDATE_MOVE,
    DELETE
  }

  private static final long CHOOSE = 0x43484F53L;

  private final Corpus corpus;
  private final LiveCorpus live;
  private final LiveGroundTruth truth;
  private final WriteGate gate;
  private final RandomStreams streams;
  private final double[] mix;
  private final double attributeFraction;
  private final double moveFraction;
  private final LongPredicate hotspot;
  private final long intervalNanos;
  private final Map<Kind, AtomicLong> applied = new EnumMap<>(Kind.class);
  private final Map<Kind, AtomicLong> failed = new EnumMap<>(Kind.class);
  private final AtomicLong skipped = new AtomicLong();
  private final AtomicLong probeInserts = new AtomicLong();
  private final AtomicLong probeDeletes = new AtomicLong();
  private final AtomicLong probeFailed = new AtomicLong();
  private final Set<Long> unresolved = ConcurrentHashMap.newKeySet();
  private volatile long windowOffsetNanos;
  private volatile long windowLengthNanos;
  private volatile boolean windowed;

  /**
   * @param hotspot accepts the row ordinals that writes may target, or null for all rows
   */
  public Mutator(HarnessConfig config, Corpus corpus, LiveCorpus live, LiveGroundTruth truth,
    WriteGate gate, LongPredicate hotspot) {
    this.corpus = corpus;
    this.live = live;
    this.truth = truth;
    this.gate = gate;
    this.streams = new RandomStreams(config.seed());
    this.mix = config.mutationMix();
    this.attributeFraction = config.attributeFraction();
    this.moveFraction = config.moveFraction();
    this.hotspot = hotspot;
    double rate = config.mutationRate();
    this.intervalNanos = rate > 0 ? Math.max(1L, (long) (1e9 / rate)) : 0L;
    for (Kind k : Kind.values()) {
      applied.put(k, new AtomicLong());
      failed.put(k, new AtomicLong());
    }
  }

  /**
   * Starts a steady-state window, whose mutation sequence numbers start again at zero; the drift
   * clock goes on from the configured length of the earlier windows.
   * @param lengthNanos the configured length of the window: its warm-up and its duration
   */
  public synchronized void beginWindow(long lengthNanos) {
    if (windowed) {
      windowOffsetNanos += windowLengthNanos;
    }
    windowLengthNanos = lengthNanos;
    windowed = true;
  }

  /**
   * Returns the drift clock of mutation number seq of the current window: the minutes of steady
   * state at which the schedule of the mutation rate places it. It does not depend on when the
   * mutation actually ran, on gate pauses, or on backlog.
   */
  double minutes(long seq) {
    return (windowOffsetNanos + (double) seq * intervalNanos) / 60e9;
  }

  /** Chooses the kind of mutation number seq. */
  public Kind choose(long seq) {
    SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, CHOOSE, seq);
    double u = rnd.nextDouble();
    if (u < mix[0]) {
      return Kind.INSERT;
    }
    if (u >= mix[0] + mix[1]) {
      return Kind.DELETE;
    }
    double v = rnd.nextDouble();
    if (v < attributeFraction) {
      return Kind.UPDATE_ATTRIBUTES;
    }
    double move = corpus.mutations().moveProbability(moveFraction, minutes(seq));
    double w = (v - attributeFraction) / Math.max(1e-12, 1.0 - attributeFraction);
    return w < move ? Kind.UPDATE_MOVE : Kind.UPDATE_LOCAL;
  }

  /** Applies mutation number seq of a chosen kind; returns the row ordinal, or -1 if skipped. */
  public long apply(RowWriter writer, Kind kind, long seq) throws SQLException {
    SplitMix64 rnd = streams.at(RandomStreams.MUTATIONS, 0x54415247L, seq);
    long ordinal;
    if (kind == Kind.INSERT) {
      ordinal = live.allocateOrdinal();
      for (int i = 0; hotspot != null && !hotspot.test(ordinal) && i < 100_000; i++) {
        ordinal = live.allocateOrdinal();
      }
    } else {
      ordinal = live.randomLive(rnd, hotspot);
    }
    if (ordinal < 0 || !live.beginWrite(ordinal)) {
      skipped.incrementAndGet();
      return -1;
    }
    if (!gate.enter()) {
      live.endWrite(ordinal);
      return -1;
    }
    try {
      double minutes = minutes(seq);
      float[] vector;
      DocAttributes attrs;
      int cluster;
      LiveCorpus.RowState current = kind == Kind.INSERT ? null : live.get(ordinal);
      if (kind != Kind.INSERT && current == null) {
        skipped.incrementAndGet();
        return -1;
      }
      try {
        switch (kind) {
          case INSERT: {
            Embedding e = corpus.mutations().insert(ordinal, minutes);
            vector = e.vector();
            cluster = e.cluster();
            attrs = corpus.attributes(ordinal);
            writer.upsert(attrs, vector);
            break;
          }
          case UPDATE_ATTRIBUTES:
            vector = current.vector();
            cluster = current.cluster();
            attrs = corpus.attributeModel().updatedTerms(current.attributes(), seq);
            writer.updateAttributes(attrs, vector);
            break;
          case UPDATE_LOCAL: {
            Embedding e = corpus.mutations().reembedLocal(ordinal, current.cluster(),
              current.vector(), seq, minutes);
            vector = e.vector();
            cluster = e.cluster();
            attrs = current.attributes();
            writer.upsert(attrs, vector);
            break;
          }
          case UPDATE_MOVE: {
            Embedding e = corpus.mutations().move(ordinal, current.cluster(), seq, minutes);
            vector = e.vector();
            cluster = e.cluster();
            attrs = current.attributes();
            writer.upsert(attrs, vector);
            break;
          }
          default:
            vector = null;
            cluster = -1;
            attrs = current.attributes();
            writer.delete(attrs);
            break;
        }
        commit(writer, ordinal);
      } catch (SQLException e) {
        failed.get(kind).incrementAndGet();
        rollback(writer, e);
        throw e;
      }
      if (vector == null) {
        live.delete(ordinal);
      } else {
        live.put(ordinal, vector, attrs, cluster);
      }
      if (truth != null) {
        truth.apply(ordinal, vector, vector == null ? null : attrs);
      }
      applied.get(kind).incrementAndGet();
      return ordinal;
    } finally {
      gate.exit();
      endWrite(ordinal);
    }
  }

  /**
   * Inserts a row with a given vector, for freshness probes, and returns its ordinal, or -1 if the
   * row was not written. The row stays marked in flight, so that the mutation workers do not update
   * or delete it while the probe queries for it, until {@link #deleteProbe} deletes it.
   */
  public long insertProbe(RowWriter writer, float[] vector) throws SQLException {
    long ordinal = live.allocateOrdinal();
    if (!live.beginWrite(ordinal)) {
      return -1;
    }
    if (!gate.enter()) {
      live.endWrite(ordinal);
      return -1;
    }
    boolean held = false;
    try {
      DocAttributes attrs = corpus.attributes(ordinal);
      try {
        writer.upsert(attrs, vector);
        commit(writer, ordinal);
      } catch (SQLException e) {
        probeFailed.incrementAndGet();
        rollback(writer, e);
        throw e;
      }
      live.put(ordinal, vector, attrs, -1);
      if (truth != null) {
        truth.apply(ordinal, vector, attrs);
      }
      probeInserts.incrementAndGet();
      held = true;
      return ordinal;
    } finally {
      gate.exit();
      if (!held) {
        endWrite(ordinal);
      }
    }
  }

  /**
   * Deletes a row that {@link #insertProbe} inserted, and releases it to the mutation workers, also
   * when the delete fails, unless its commit failed, which keeps it in flight until restore.
   */
  public void deleteProbe(RowWriter writer, long ordinal) throws SQLException {
    if (!gate.enter()) {
      endWrite(ordinal);
      return;
    }
    try {
      LiveCorpus.RowState s = live.get(ordinal);
      if (s == null) {
        return;
      }
      try {
        writer.delete(s.attributes());
        commit(writer, ordinal);
      } catch (SQLException e) {
        probeFailed.incrementAndGet();
        rollback(writer, e);
        throw e;
      }
      live.delete(ordinal);
      if (truth != null) {
        truth.apply(ordinal, null, null);
      }
      probeDeletes.incrementAndGet();
    } finally {
      gate.exit();
      endWrite(ordinal);
    }
  }

  /**
   * Commits the statements of one mutation. A commit that throws may have reached some or all of
   * the tables, since a commit sends each table on its own, so the row becomes unresolved.
   */
  private void commit(RowWriter writer, long ordinal) throws SQLException {
    try {
      writer.commit();
    } catch (SQLException e) {
      unresolved.add(ordinal);
      throw e;
    }
  }

  /** Discards the statements of a failed mutation that the connection still buffers. */
  private static void rollback(RowWriter writer, SQLException e) {
    try {
      writer.connection().rollback();
    } catch (SQLException rollback) {
      e.addSuppressed(rollback);
    }
  }

  /** Ends the write of a row, unless its commit failed, which keeps it in flight until restore. */
  private void endWrite(long ordinal) {
    if (!unresolved.contains(ordinal)) {
      live.endWrite(ordinal);
    }
  }

  /**
   * Returns the rows whose commit failed, so whose state in the tables is unknown. They stay marked
   * in flight in the live corpus until the run phase rewrites them at restore.
   */
  public Set<Long> unresolved() {
    return unresolved;
  }

  /** Forgets that a row is unresolved, after restore rewrote it, and ends its write. */
  public void resolve(long ordinal) {
    if (unresolved.remove(ordinal)) {
      live.endWrite(ordinal);
    }
  }

  public long applied(Kind kind) {
    return applied.get(kind).get();
  }

  public long totalApplied() {
    long n = 0;
    for (AtomicLong a : applied.values()) {
      n += a.get();
    }
    return n;
  }

  /**
   * Returns the counts of the mutations so far: applied ones by kind, failed and skipped ones, and,
   * apart from them, the inserts and deletes of freshness probe rows.
   */
  public Map<String, Object> counts() {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Kind k : Kind.values()) {
      m.put(k.name().toLowerCase(java.util.Locale.ROOT), applied.get(k).get());
    }
    long f = 0;
    for (AtomicLong a : failed.values()) {
      f += a.get();
    }
    m.put("failed", f);
    m.put("skipped", skipped.get());
    m.put("probe_inserts", probeInserts.get());
    m.put("probe_deletes", probeDeletes.get());
    m.put("probe_failed", probeFailed.get());
    return m;
  }
}
