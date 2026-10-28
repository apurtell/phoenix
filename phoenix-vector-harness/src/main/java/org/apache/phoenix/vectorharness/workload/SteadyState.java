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

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.Disruption;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.QueryMode;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.config.VariantKind;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.LatencyRecorder;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One steady-state window: concurrent query workers at an offered rate (open loop, latency from the
 * scheduled dispatch time) or back to back (closed loop), mutation workers at the mutation rate, a
 * recall sampler at the sampling interval, a freshness probe, the timed disruptions with their
 * recovery, and an optional explicit IVF rebuild. Measurements start after the warm-up. Every
 * second a ticker writes the throughput and latency of the last second, so that recall samples,
 * latency windows, and lifecycle events align on one time axis.
 */
public final class SteadyState {

  private static final Logger LOG = LoggerFactory.getLogger(SteadyState.class);
  static final long RECOVERY_TIMEOUT_MS = 600_000;
  /**
   * How long the HTTP JMX servlet of a RegionServer may serve cached values: the Hadoop metrics
   * period, 10 s by default, and a second of margin.
   */
  static final long JMX_CACHE_TTL_MS = 11_000;
  /**
   * The norm factor of INNER_PRODUCT freshness probe rows, so that a probe row is the nearest
   * neighbor of its own vector while the corpus rows are shorter than this many query norms.
   */
  static final double INNER_PRODUCT_PROBE_SCALE = 1000.0;

  private final RunContext rc;
  private final Mutator mutator;
  private final SweepPoint sweep;
  private final CsvTable steadyCsv;
  private final CsvTable throughputCsv;
  private final RecallSampler sampler;

  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong querySeq = new AtomicLong();
  private final AtomicLong mutationSeq = new AtomicLong();
  private final AtomicLong queriesOk = new AtomicLong();
  private final AtomicLong queriesFailed = new AtomicLong();
  private final AtomicLong mutationsOk = new AtomicLong();
  private final AtomicLong mutationsFailed = new AtomicLong();
  private final AtomicLong freshnessProbes = new AtomicLong();
  private final AtomicLong freshnessFound = new AtomicLong();
  private final AtomicLong freshnessErrors = new AtomicLong();
  private final Map<Mutator.Kind, AtomicLong> measuredKinds = new EnumMap<>(Mutator.Kind.class);
  private final AtomicLong measuredSkipped = new AtomicLong();
  private final AtomicLong measuredFailed = new AtomicLong();
  private final LatencyRecorder window = new LatencyRecorder();
  private final LatencyRecorder mutationLatency = new LatencyRecorder();
  private final LatencyRecorder freshnessLatency = new LatencyRecorder();
  private final Map<String, Measurement> measurements = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> disruptions = new ArrayList<>();
  private volatile long warmupEndNanos;
  private long gateClosedAtStart;
  private long recomputesAtStart;

  public SteadyState(RunContext rc, Mutator mutator, SweepPoint sweep, CsvTable steadyCsv,
    CsvTable throughputCsv, CsvTable recallCsv) {
    this.rc = rc;
    this.mutator = mutator;
    this.sweep = sweep;
    this.steadyCsv = steadyCsv;
    this.throughputCsv = throughputCsv;
    this.sampler = new RecallSampler(rc, recallCsv, sweep);
    for (Mutator.Kind k : Mutator.Kind.values()) {
      measuredKinds.put(k, new AtomicLong());
    }
  }

  public static String[] throughputColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "queries", "query_errors", "mutations",
      "mutation_errors", "p50_ms", "p99_ms", "max_ms" };
  }

  public RecallSampler sampler() {
    return sampler;
  }

  /** Runs one window at an offered query rate. */
  public Map<String, Object> run(double rate, String stage) throws Exception {
    HarnessConfig c = rc.ctx().config();
    rc.events().stage(stage);
    running.set(true);
    long start = System.nanoTime();
    warmupEndNanos = start + c.warmup().toNanos();
    final long endNanos = warmupEndNanos + c.duration().toNanos();
    // The window's share of run-wide counters is their change from here.
    gateClosedAtStart = rc.gate().closedNanos();
    recomputesAtStart = rc.liveTruth() == null ? 0 : rc.liveTruth().recomputes();
    mutator.beginWindow(c.warmup().toNanos() + c.duration().toNanos());
    MetricSnapshot before = rc.metrics() == null ? null : rc.metrics().snapshot(stage + "-start");
    List<Thread> threads = new ArrayList<>();
    boolean open = c.queryMode() == QueryMode.OPEN;
    Throttle queryThrottle = new Throttle(open ? rate : 0);
    for (int i = 0; i < c.queryThreads(); i++) {
      threads.add(thread("vh-query-" + i, () -> queryLoop(queryThrottle, endNanos, stage)));
    }
    if (c.mutationRate() > 0) {
      Throttle mutationThrottle = new Throttle(c.mutationRate());
      for (int i = 0; i < c.mutationThreads(); i++) {
        threads.add(thread("vh-mutate-" + i, () -> mutationLoop(mutationThrottle, endNanos)));
      }
    }
    // The query and mutation workers come first: the counted work ends when they end.
    int workers = threads.size();
    long workEnd = endNanos;
    try (QueryPool samplerPool = new QueryPool(rc.ctx(), c.queryThreads(), "vh-sample")) {
      threads.add(thread("vh-sampler", () -> samplerLoop(samplerPool, endNanos)));
      if (c.freshnessRate() > 0) {
        threads.add(thread("vh-fresh", () -> freshnessLoop(c.freshnessRate(), endNanos)));
      }
      if (!c.disruptions().isEmpty()) {
        threads.add(thread("vh-chaos", () -> chaosLoop(c.disruptions(), endNanos)));
      }
      if (c.rebuildExplicitAt() != null) {
        threads.add(thread("vh-rebuild", () -> rebuildAt(c.rebuildExplicitAt().toNanos())));
      }
      threads.add(thread("vh-ticker", () -> tickerLoop(endNanos, stage)));
      for (Thread t : threads) {
        t.start();
      }
      long joinDeadline = endNanos + TimeUnit.MINUTES.toNanos(10);
      for (int i = 0; i < threads.size(); i++) {
        long wait = Math.max(1, (joinDeadline - System.nanoTime()) / 1_000_000);
        threads.get(i).join(wait);
        if (i + 1 == workers) {
          // Throughput divides by the time of the counted work, which may drain a backlog past
          // the end, but not by the waits for recovery, a rebuild, or the final sample.
          workEnd = Math.max(endNanos, System.nanoTime());
        }
      }
      running.set(false);
      for (Thread t : threads) {
        t.join(60_000);
        if (t.isAlive()) {
          LOG.warn("Thread {} did not stop", t.getName());
          t.interrupt();
        }
      }
      sampler.sample(samplerPool);
    }
    double measured = (workEnd - warmupEndNanos) / 1e9;
    MetricSnapshot after = rc.metrics() == null ? null : rc.metrics().snapshot(stage + "-end");
    return summarize(rate, stage, measured, before, after);
  }

  private Thread thread(String name, Runnable r) {
    Thread t = new Thread(() -> {
      try {
        r.run();
      } catch (RuntimeException e) {
        LOG.error("{} failed", name, e);
        rc.manifest().flag(name + " failed: " + e);
      }
    }, name);
    t.setDaemon(true);
    return t;
  }

  private boolean measuring(long nanos) {
    return nanos - warmupEndNanos >= 0;
  }

  /** Chooses the query of a sequence number: a panel query, one of its conditions, and a k. */
  QuerySpec pick(long seq) {
    HarnessConfig c = rc.ctx().config();
    SplitMix64 rnd = new SplitMix64(SplitMix64.combine(c.seed() ^ 0x51554552L, seq));
    List<PanelQuery> panel = rc.panel();
    int qi = rnd.nextInt(panel.size());
    int[] ks = c.kValues();
    int k = ks[rnd.nextInt(ks.length)];
    List<QueryCondition> eligible = new ArrayList<>();
    for (QueryCondition cond : rc.conditions().get(qi)) {
      if (!cond.hasRadius() || cond.k() == k) {
        eligible.add(cond);
      }
    }
    QueryCondition cond = eligible.get(rnd.nextInt(eligible.size()));
    List<TableLayout> layouts = new ArrayList<>(rc.layouts());
    TableLayout layout = layouts.get(rnd.nextInt(layouts.size()));
    return rc.queries().build(layout, panel.get(qi), cond, k, sweep);
  }

  private void queryLoop(Throttle throttle, long endNanos, String stage) {
    try (QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      while (running.get()) {
        long slot = throttle.claim();
        if (slot - endNanos >= 0) {
          return;
        }
        Throttle.awaitSlot(slot);
        QuerySpec q = pick(querySeq.getAndIncrement());
        QueryExecutor.Result r = exec.execute(q, throttle.unthrottled() ? 0 : slot);
        if (!measuring(slot)) {
          continue;
        }
        String key = q.layout().id() + "|" + q.condition().family() + "|" + q.k();
        Measurement m = measurements.computeIfAbsent(key, x -> new Measurement(stage,
          q.layout().id(), q.condition().family(), q.k(), sweep.label(), sweep.value()));
        m.record(r, null);
        window.recordNanos(r.latencyNanos());
        if (r.failed()) {
          queriesFailed.incrementAndGet();
        } else {
          queriesOk.incrementAndGet();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void mutationLoop(Throttle throttle, long endNanos) {
    try (Connection conn = rc.ctx().connection()) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        while (running.get()) {
          long slot = throttle.claim();
          if (slot - endNanos >= 0) {
            return;
          }
          Throttle.awaitSlot(slot);
          long seq = mutationSeq.getAndIncrement();
          Mutator.Kind kind = mutator.choose(seq);
          long t0 = System.nanoTime();
          try {
            long ordinal = mutator.apply(writer, kind, seq);
            // A mutation that waited for a quiesced sample counts from the reopening of the
            // gate, and the slots the closure missed are dropped rather than replayed at once.
            long from = latencyStart(slot, rc.gate());
            if (from != slot) {
              throttle.reset();
            }
            if (measuring(slot)) {
              if (ordinal >= 0) {
                mutationsOk.incrementAndGet();
                measuredKinds.get(kind).incrementAndGet();
                mutationLatency.recordNanos(System.nanoTime() - from);
              } else {
                measuredSkipped.incrementAndGet();
              }
            }
          } catch (SQLException e) {
            mutationsFailed.incrementAndGet();
            if (measuring(slot)) {
              measuredFailed.incrementAndGet();
            }
            LOG.debug("Mutation {} {} failed", seq, kind, e);
            if (System.nanoTime() - t0 > 0) {
              Thread.sleep(100);
            }
          }
        }
      }
    } catch (SQLException e) {
      LOG.error("Mutation worker failed", e);
      rc.manifest().flag("mutation worker failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void samplerLoop(QueryPool pool, long endNanos) {
    HarnessConfig c = rc.ctx().config();
    long interval = c.sampleInterval().toNanos();
    long next = warmupEndNanos;
    try {
      while (running.get()) {
        Throttle.awaitSlot(next);
        if (System.nanoTime() - endNanos >= 0) {
          return;
        }
        sampler.sample(pool);
        next += interval;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void freshnessLoop(double rate, long endNanos) {
    Throttle throttle = new Throttle(rate);
    long probeSeq = 0;
    try (Connection conn = rc.ctx().connection();
      QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        while (running.get()) {
          long slot = throttle.claim();
          if (slot - endNanos >= 0) {
            return;
          }
          Throttle.awaitSlot(slot);
          if (!measuring(slot)) {
            continue;
          }
          long seq = probeSeq++;
          try {
            probe(writer, exec, seq);
          } catch (SQLException e) {
            // A failed probe write ends neither the probing of the window nor its freshness.
            freshnessErrors.incrementAndGet();
            LOG.debug("Freshness probe {} failed", seq, e);
            rc.events().event("freshness-error", String.format(java.util.Locale.ROOT,
              "probe %d at %.3f s: %s", seq, (System.nanoTime() - warmupEndNanos) / 1e9, e));
            Thread.sleep(100);
          }
        }
      }
    } catch (SQLException e) {
      LOG.warn("Freshness probing failed", e);
      rc.manifest().flag("freshness worker failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The outcome of a freshness probe. */
  enum Probe {
    /** Every layout found the committed row. */
    FOUND,
    /** Some layout answered without the committed row. */
    STALE,
    /** The probe got no answer, so it says nothing about freshness. */
    FAILED
  }

  /**
   * Inserts a row and queries for it at once; the row is fresh if the query finds it. The probe row
   * is deleted after. The mutation workers leave the row alone until it is deleted, so that a miss
   * means that the query did not return a row that was committed and unchanged. A probe whose
   * insert did not happen or whose query failed counts as an error, not as a stale row; a probe
   * write that throws is left to the caller to count. Each miss is recorded as a freshness-miss
   * event, and each failed query as a freshness-error event, so that they can be placed against
   * disruptions.
   */
  Probe probe(RowWriter writer, QueryExecutor exec, long seq) throws SQLException {
    List<PanelQuery> panel = rc.panel();
    PanelQuery base = panel.get((int) (seq % panel.size()));
    float[] v = probeVector(base.vector(), seq, rc.ctx().metric());
    long ordinal = mutator.insertProbe(writer, v);
    if (ordinal < 0) {
      freshnessErrors.incrementAndGet();
      return Probe.FAILED;
    }
    try {
      long committed = System.nanoTime();
      PanelQuery probeQuery = new PanelQuery(-1, v, PanelQuery.Kind.PUBLISHED, 0, -1);
      QueryCondition unfiltered = QueryCondition
        .builder(org.apache.phoenix.vectorharness.config.QueryShape.UNFILTERED).build();
      boolean found = true;
      String failure = null;
      List<String> misses = new ArrayList<>();
      for (TableLayout layout : rc.layouts()) {
        QuerySpec q = rc.queries().build(layout, probeQuery, unfiltered, 10, sweep);
        QueryExecutor.Result r = exec.execute(q, 0);
        if (r.failed()) {
          failure = layout.id() + ": " + r.error();
          break;
        }
        boolean hit = contains(r, ordinal);
        if (!hit) {
          // Query again at once: a row that is found now was late; one that is not was lost.
          QueryExecutor.Result again = exec.execute(q, 0);
          misses.add(layout.id() + " returned " + describe(r) + ", again "
            + (contains(again, ordinal) ? "found" : "missing " + describe(again)));
        }
        found = found && hit;
      }
      String at = running.get()
        ? String.format(java.util.Locale.ROOT, " at %.3f s", (committed - warmupEndNanos) / 1e9)
        : "";
      if (failure != null) {
        freshnessErrors.incrementAndGet();
        rc.events().event("freshness-error", String.format(java.util.Locale.ROOT,
          "probe %d ordinal %d%s: query failed on %s", seq, ordinal, at, failure));
        return Probe.FAILED;
      }
      freshnessProbes.incrementAndGet();
      if (found) {
        freshnessFound.incrementAndGet();
        freshnessLatency.recordNanos(System.nanoTime() - committed);
        return Probe.FOUND;
      }
      rc.events().event("freshness-miss",
        String.format(java.util.Locale.ROOT,
          "probe %d ordinal %d%s, %.1f ms after the commit:" + " %s", seq, ordinal, at,
          (System.nanoTime() - committed) / 1e6, String.join("; ", misses)));
      return Probe.STALE;
    } finally {
      mutator.deleteProbe(writer, ordinal);
    }
  }

  private static boolean contains(QueryExecutor.Result r, long ordinal) {
    for (long o : r.ordinals()) {
      if (o == ordinal) {
        return true;
      }
    }
    return false;
  }

  /** Describes the result of a query: its error, or its ordinals with their distances. */
  static String describe(QueryExecutor.Result r) {
    if (r.failed()) {
      return "error " + r.error();
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < r.ordinals().length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(r.ordinals()[i]).append('@')
        .append(String.format(java.util.Locale.ROOT, "%.4g", r.distances()[i]));
    }
    return sb.append(']').toString();
  }

  /**
   * Returns the vector of freshness probe number seq: a panel query vector, slightly perturbed,
   * which the probe inserts and then queries for. Under L2 and COSINE a vector is its own nearest
   * neighbor. Under INNER_PRODUCT the nearest neighbor of a query q is the row x of the largest
   * q.x, so the probe row is scaled until q.x of any other row, at most |q| |x|, falls below it.
   */
  static float[] probeVector(float[] base, long seq, Metric metric) {
    float[] v = base.clone();
    SplitMix64 rnd = new SplitMix64(SplitMix64.combine(0x46524553L, seq));
    double scale = metric == Metric.INNER_PRODUCT ? INNER_PRODUCT_PROBE_SCALE : 1.0;
    for (int i = 0; i < v.length; i++) {
      v[i] = (float) (scale * v[i] * (1.0 + 1e-3 * rnd.nextGaussian()));
    }
    return v;
  }

  /**
   * Runs the validity freshness check: a few probes outside any window. Returns the share of the
   * answered probes that found their row, or NaN if none was answered; {@link #freshnessErrors()}
   * then counts the probes that failed.
   */
  public double freshnessCheck(int probes) throws SQLException {
    if (probes <= 0) {
      return Double.NaN;
    }
    int found = 0;
    int answered = 0;
    try (Connection conn = rc.ctx().connection();
      QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        for (int i = 0; i < probes; i++) {
          Probe p;
          try {
            p = probe(writer, exec, 1_000_000L + i);
          } catch (SQLException e) {
            freshnessErrors.incrementAndGet();
            LOG.warn("Validity freshness probe {} failed", i, e);
            continue;
          }
          if (p != Probe.FAILED) {
            answered++;
          }
          if (p == Probe.FOUND) {
            found++;
          }
        }
      }
    }
    freshnessProbes.set(0);
    freshnessFound.set(0);
    return answered == 0 ? Double.NaN : (double) found / answered;
  }

  /** Returns the freshness probes that failed: a write that threw, or a query that failed. */
  public long freshnessErrors() {
    return freshnessErrors.get();
  }

  /**
   * Returns the start of the latency of a mutation scheduled at a slot: the slot, or the last
   * reopening of the write gate if the gate reopened after the slot, so that the time a quiesced
   * sample held the mutation does not count as write latency.
   */
  static long latencyStart(long slot, WriteGate gate) {
    long opened = gate.lastOpenedNanos();
    return opened != Long.MIN_VALUE && opened - slot > 0 ? opened : slot;
  }

  private void chaosLoop(List<Disruption> schedule, long endNanos) {
    int n = 0;
    String table = rc.ctx().tables().fullTable(rc.layouts().iterator().next());
    try (Connection conn = rc.ctx().connection()) {
      for (Disruption d : schedule) {
        long at = warmupEndNanos + d.at().toNanos();
        Throttle.awaitSlot(at);
        if (!running.get() || System.nanoTime() - endNanos >= 0) {
          return;
        }
        MetricSnapshot pre = rc.metrics() == null ? null : rc.metrics().sample(null);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("disruption", d.toString());
        record.put("offset_ms", rc.events().offset());
        try {
          record.put("action", ChaosInjector.apply(d, n++, conn, table, rc.events()));
          record.put("mttr_seconds", awaitRecovery(pre, conn, table, System.currentTimeMillis()));
        } catch (Exception e) {
          LOG.warn("Disruption {} failed", d, e);
          record.put("error", e.toString());
          rc.manifest().flag("disruption " + d + " failed: " + e.getMessage());
        }
        synchronized (disruptions) {
          disruptions.add(record);
        }
      }
    } catch (SQLException e) {
      LOG.warn("Chaos connection failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for the index to recover from a disruption: for HNSW, until every region of the disrupted
   * table has an open index manager again, the regions of its index that read without candidate
   * restriction return to their number before the disruption, the rebuild queue drains, and no more
   * servers are missing from the metrics than before. With the HTTP reader a sample counts only
   * once the servlet cache can no longer hold values from before the disruption. Returns the
   * seconds it took, or null when it does not recover in time or metrics are unknown.
   */
  private Double awaitRecovery(MetricSnapshot pre, Connection conn, String table,
    long appliedMillis) throws InterruptedException {
    if (rc.metrics() == null || pre == null || rc.ctx().config().variant() != VariantKind.HNSW) {
      return null;
    }
    long start = System.nanoTime();
    String index = rc.ctx().tables().fullIndex(rc.layouts().iterator().next());
    String unrestrictedKey = "vectorHnswRegionsUnrestricted." + index;
    if (pre.get(unrestrictedKey) == null) {
      unrestrictedKey = "vectorHnswRegionsUnrestricted";
    }
    String regionsKey = "vectorHnswRegions." + index;
    long notBefore = "http".equals(rc.manifest().getString("run.metrics_mode"))
      ? appliedMillis + JMX_CACHE_TTL_MS
      : 0;
    // Give the disruption time to show in the metrics before testing for recovery.
    Thread.sleep(2000);
    Admin admin = null;
    TableName tn = null;
    try {
      tn = StorageFootprint.physicalName(conn, table);
      admin = HarnessContext.admin(conn);
    } catch (SQLException e) {
      LOG.debug("The regions of {} are unknown; recovery ignores them", table, e);
    }
    try {
      while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(RECOVERY_TIMEOUT_MS)) {
        MetricSnapshot s = rc.metrics().sample(null);
        if (s.get(unrestrictedKey) == null || pre.get(unrestrictedKey) == null) {
          return null;
        }
        if (recovered(pre, s, unrestrictedKey, regionsKey, regionCount(admin, tn), notBefore)) {
          double secs = (System.nanoTime() - start) / 1e9;
          rc.events().event("disruption-recovered", String.format("after %.1f s", secs));
          return secs;
        }
        Thread.sleep(1000);
      }
    } finally {
      if (admin != null) {
        try {
          admin.close();
        } catch (IOException e) {
          LOG.debug("Closing the admin failed", e);
        }
      }
    }
    rc.events().event("disruption-unrecovered", "no recovery within the timeout");
    return null;
  }

  /** Returns the number of regions of a table, or -1 if unknown. */
  private static int regionCount(Admin admin, TableName tn) {
    if (admin == null || tn == null) {
      return -1;
    }
    try {
      return admin.getRegions(tn).size();
    } catch (IOException e) {
      LOG.debug("Listing the regions of {} failed", tn, e);
      return -1;
    }
  }

  /**
   * Returns true if a metric sample shows the index recovered from a disruption, against the sample
   * taken before it.
   * @param unrestrictedKey the key of the regions that read without candidate restriction
   * @param regionsKey      the key of the regions of the index that have an open manager
   * @param tableRegions    the current number of regions of the table, or -1 if unknown
   * @param notBeforeMillis the earliest wall time of a sample that can show the disruption
   */
  static boolean recovered(MetricSnapshot pre, MetricSnapshot s, String unrestrictedKey,
    String regionsKey, int tableRegions, long notBeforeMillis) {
    if (s.wallMillis() < notBeforeMillis || s.serversMissing() > pre.serversMissing()) {
      return false;
    }
    Double baseline = pre.get(unrestrictedKey);
    Double unrestricted = s.get(unrestrictedKey);
    if (baseline == null || unrestricted == null || unrestricted > baseline) {
      return false;
    }
    Double queue = s.get("vectorHnswRebuildQueue");
    if (queue != null && queue != 0) {
      return false;
    }
    // Regions that are closed, offline, or not yet reopened after a move, a split, a merge, or a
    // restart have no open manager, and a cached sample from before a split or a merge shows the
    // old count. The count is not compared with the one before: a merge leaves fewer regions.
    Double regions = s.get(regionsKey);
    return tableRegions < 0 || regions == null || regions.intValue() == tableRegions;
  }

  private void rebuildAt(long offsetNanos) {
    try {
      Throttle.awaitSlot(warmupEndNanos + offsetNanos);
      if (!running.get()) {
        return;
      }
      if (rc.ctx().config().variant() != VariantKind.IVF) {
        rc.events().event("explicit-rebuild-skipped", "rebuild.explicit.at applies to IVF indexes");
        return;
      }
      for (TableLayout layout : rc.layouts()) {
        String sql = "ALTER INDEX " + rc.ctx().tables().index(layout) + " ON "
          + rc.ctx().tables().fullTable(layout) + " REBUILD";
        rc.events().event("explicit-rebuild-start", sql);
        long t0 = System.nanoTime();
        try (Connection conn = rc.ctx().connection()) {
          conn.createStatement().execute(sql);
          rc.events().event("explicit-rebuild-end",
            String.format("%s in %.1f s", layout.id(), (System.nanoTime() - t0) / 1e9));
        } catch (SQLException e) {
          rc.events().event("explicit-rebuild-failed", e.getMessage());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void tickerLoop(long endNanos, String stage) {
    long next = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    long qOk = 0;
    long qErr = 0;
    long mOk = 0;
    long mErr = 0;
    try {
      while (running.get() && System.nanoTime() - endNanos < 0) {
        Throttle.awaitSlot(next);
        next += TimeUnit.SECONDS.toNanos(1);
        Histogram h = window.interval();
        long a = queriesOk.get();
        long b = queriesFailed.get();
        long cc = mutationsOk.get();
        long d = mutationsFailed.get();
        if (throughputCsv != null && measuring(System.nanoTime())) {
          throughputCsv.row(System.currentTimeMillis(), rc.events().offset(), stage, a - qOk,
            b - qErr, cc - mOk, d - mErr, LatencyRecorder.percentileMillis(h, 50),
            LatencyRecorder.percentileMillis(h, 99),
            h.getTotalCount() == 0 ? Double.NaN : h.getMaxValue() / 1000.0);
          throughputCsv.flush();
        }
        qOk = a;
        qErr = b;
        mOk = cc;
        mErr = d;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Map<String, Object> summarize(double rate, String stage, double measuredSeconds,
    MetricSnapshot before, MetricSnapshot after) {
    HarnessConfig c = rc.ctx().config();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("stage", stage);
    m.put("mode", c.queryMode().name().toLowerCase(java.util.Locale.ROOT));
    m.put("offered_rate", c.queryMode() == QueryMode.OPEN ? rate : null);
    m.put("sweep", sweep.label());
    m.put("sweep_parameter", sweep.parameter());
    m.put("sweep_value", sweep.value());
    m.put("measured_seconds", measuredSeconds);
    long ok = queriesOk.get();
    m.put("queries", ok);
    m.put("query_errors", queriesFailed.get());
    m.put("achieved_qps", measuredSeconds > 0 ? ok / measuredSeconds : Double.NaN);
    m.put("query_latency", LatencyRecorder.summarize(window.total()));
    m.put("mutations", mutationsOk.get());
    m.put("mutation_errors", mutationsFailed.get());
    m.put("achieved_mutations_per_second",
      measuredSeconds > 0 ? mutationsOk.get() / measuredSeconds : Double.NaN);
    m.put("mutation_latency", LatencyRecorder.summarize(mutationLatency.total()));
    // The mix of the measured mutations, without warm-up and freshness probe rows.
    Map<String, Object> counts = new LinkedHashMap<>();
    for (Map.Entry<Mutator.Kind, AtomicLong> e : measuredKinds.entrySet()) {
      counts.put(e.getKey().name().toLowerCase(java.util.Locale.ROOT), e.getValue().get());
    }
    counts.put("failed", measuredFailed.get());
    counts.put("skipped", measuredSkipped.get());
    m.put("mutation_counts", counts);
    m.put("gate_closed_ms", (rc.gate().closedNanos() - gateClosedAtStart) / 1e6);
    long probes = freshnessProbes.get();
    m.put("freshness_probes", probes);
    m.put("freshness", probes == 0 ? Double.NaN : (double) freshnessFound.get() / probes);
    m.put("freshness_errors", freshnessErrors.get());
    m.put("freshness_latency", LatencyRecorder.summarize(freshnessLatency.total()));
    m.put("recall_samples", sampler.samples().size());
    if (rc.liveTruth() != null) {
      m.put("live_truth_recomputes", rc.liveTruth().recomputes() - recomputesAtStart);
    }
    synchronized (disruptions) {
      m.put("disruptions", new ArrayList<>(disruptions));
    }
    List<Map<String, Object>> groups = new ArrayList<>();
    for (Measurement meas : measurements.values()) {
      Map<String, Object> s = meas.summary(0, c.seed());
      s.remove("accuracy");
      groups.add(s);
      if (steadyCsv != null) {
        steadyCsv.row(Measurement.csvRow(withEmptyAccuracy(s)));
      }
    }
    if (steadyCsv != null) {
      steadyCsv.flush();
    }
    m.put("groups", groups);
    if (before != null && after != null) {
      m.put("metric_deltas", after.deltas(before));
      validity(m, before, after);
    }
    return m;
  }

  private static Map<String, Object> withEmptyAccuracy(Map<String, Object> s) {
    Map<String, Object> copy = new LinkedHashMap<>(s);
    copy.put("accuracy", new LinkedHashMap<String, Object>());
    return copy;
  }

  /** Flags the run when HNSW maintenance stalled or managers failed to open during the window. */
  private void validity(Map<String, Object> m, MetricSnapshot before, MetricSnapshot after) {
    for (String metric : new String[] { "vectorHnswMaintenanceStalled",
      "vectorHnswOpenFailures" }) {
      Double d = after.delta(before, metric);
      if (d != null && d > 0) {
        String flag = metric + " rose by " + d.longValue() + " during " + m.get("stage")
          + "; measurements no longer describe a healthy index";
        rc.manifest().flag(flag);
        rc.events().event("validity", flag);
      }
    }
  }
}
