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
 * Applies individual mutations via {@link RowWriter}: inserts, updates, and deletes according to
 * configured workload ratios. Coordinates in-flight tracking with {@link LiveCorpus}, updates
 * {@link LiveGroundTruth}, and synchronizes writes with {@link WriteGate}. Failed commits are
 * marked unresolved to prevent concurrent access until restored.
 */
public final class Mutator {

  /** Mutation operation types. */
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
   * Initializes steady state window and advances drift clock according to prior window duration.
   * @param lengthNanos configured duration of the window (warm-up plus active measurement)
   */
  public synchronized void beginWindow(long lengthNanos) {
    if (windowed) {
      windowOffsetNanos += windowLengthNanos;
    }
    windowLengthNanos = lengthNanos;
    windowed = true;
  }

  /**
   * Returns elapsed minutes in the steady state schedule corresponding to mutation sequence number.
   */
  double minutes(long seq) {
    return (windowOffsetNanos + (double) seq * intervalNanos) / 60e9;
  }

  /** Deterministically selects mutation operation type for sequence number. */
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

  /** Executes mutation operation for sequence number; returns row ordinal, or -1 if skipped. */
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
   * Inserts row with given vector for freshness probing and returns ordinal, or -1 on failure.
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
   * Deletes probe row previously inserted by {@link #insertProbe}.
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
   * Commits pending row mutations; marks ordinal unresolved if commit throws SQLException.
   */
  private void commit(RowWriter writer, long ordinal) throws SQLException {
    try {
      writer.commit();
    } catch (SQLException e) {
      unresolved.add(ordinal);
      throw e;
    }
  }

  /** Rolls back buffered statements following mutation failure. */
  private static void rollback(RowWriter writer, SQLException e) {
    try {
      writer.connection().rollback();
    } catch (SQLException rollback) {
      e.addSuppressed(rollback);
    }
  }

  /** Concludes write on row ordinal unless marked unresolved. */
  private void endWrite(long ordinal) {
    if (!unresolved.contains(ordinal)) {
      live.endWrite(ordinal);
    }
  }

  /**
   * Returns set of row ordinals whose commits failed, leaving cluster state unresolved.
   */
  public Set<Long> unresolved() {
    return unresolved;
  }

  /** Clears unresolved status for row ordinal and releases in-flight write lock. */
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
   * Returns mutation operation counts by kind, including failures, skips, and probe operations.
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
