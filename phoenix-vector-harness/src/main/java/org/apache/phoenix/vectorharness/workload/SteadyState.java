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
import java.util.concurrent.locks.ReentrantLock;
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
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IvfRegionLayout;
import org.apache.phoenix.vectorharness.variant.IvfVariant;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a steady state measurement window: concurrent queries (open or closed loop), background
 * mutations, periodic recall sampling, freshness probes, scheduled disruptions, and optional index
 * rebuilds. Second-by-second throughput and latency are recorded to CSV.
 */
public final class SteadyState {

  private static final Logger LOG = LoggerFactory.getLogger(SteadyState.class);
  static final long RECOVERY_TIMEOUT_MS = 600_000;
  /** Time that the harness waits for the threads after the end of the stage. */
  static final long JOIN_GRACE_NANOS = TimeUnit.MINUTES.toNanos(10);
  /**
   * Time that a disruption in flight has to end after the window end. A graceful restart has six
   * waits with a step limit each, and the recovery wait follows. The join grace comes after this
   * limit, so the reads can run for both.
   */
  static final long MAX_EXTENSION_NANOS =
    TimeUnit.MILLISECONDS.toNanos(6 * GracefulRestart.STEP_TIMEOUT_MS + RECOVERY_TIMEOUT_MS);
  /** Shortest wait for the layout lock that the disruption record gives. */
  static final long LOCK_WAIT_RECORD_NANOS = TimeUnit.SECONDS.toNanos(1);
  /** JMX cache TTL margin in milliseconds for HTTP metrics reader. */
  static final long JMX_CACHE_TTL_MS = 11_000;
  /** Scale factor applied to INNER_PRODUCT freshness probe vectors. */
  static final double INNER_PRODUCT_PROBE_SCALE = 1000.0;
  /** Interval of the check for a generation that an automatic rebuild promotes. */
  static final long GENERATION_POLL_MS = 10_000;
  /** Attempts at the layout of one generation before the harness gives up on it. */
  static final int MAX_LAYOUT_ATTEMPTS = 3;

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
  /** Event offset at which the last writer thread stops, or -1 if no writer stops. */
  private final AtomicLong writesEndOffsetMillis = new AtomicLong(-1);
  private final Map<Mutator.Kind, AtomicLong> measuredKinds = new EnumMap<>(Mutator.Kind.class);
  private final AtomicLong measuredSkipped = new AtomicLong();
  private final AtomicLong measuredFailed = new AtomicLong();
  private final LatencyRecorder window = new LatencyRecorder();
  private final LatencyRecorder mutationLatency = new LatencyRecorder();
  private final LatencyRecorder freshnessLatency = new LatencyRecorder();
  private final Map<String, Measurement> measurements = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> disruptions = new ArrayList<>();
  private final List<Map<String, Object>> layoutChanges = new ArrayList<>();
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

  /** Executes steady state window at specified query rate. */
  public Map<String, Object> run(double rate, String stage) throws Exception {
    HarnessConfig c = rc.ctx().config();
    rc.events().stage(stage);
    running.set(true);
    long start = System.nanoTime();
    warmupEndNanos = start + c.warmup().toNanos();
    final long endNanos = warmupEndNanos + c.duration().toNanos();
    StageEnd end = new StageEnd(endNanos);
    // Track deltas relative to window start for run counters
    gateClosedAtStart = rc.gate().closedNanos();
    recomputesAtStart = rc.liveTruth() == null ? 0 : rc.liveTruth().recomputes();
    mutator.beginWindow(c.warmup().toNanos() + c.duration().toNanos());
    MetricSnapshot before = rc.metrics() == null ? null : rc.metrics().snapshot(stage + "-start");
    List<Thread> threads = new ArrayList<>();
    boolean open = c.queryMode() == QueryMode.OPEN;
    Throttle queryThrottle = new Throttle(open ? rate : 0);
    for (int i = 0; i < c.queryThreads(); i++) {
      threads.add(thread("vh-query-" + i, () -> queryLoop(queryThrottle, end, stage)));
    }
    if (c.mutationRate() > 0) {
      Throttle mutationThrottle = new Throttle(c.mutationRate());
      for (int i = 0; i < c.mutationThreads(); i++) {
        threads.add(thread("vh-mutate-" + i, () -> mutationLoop(mutationThrottle, end)));
      }
    }
    // Worker threads finish before waiting on background tasks
    long workEnd = endNanos;
    long endOffsetMillis = 0;
    try (QueryPool samplerPool = new QueryPool(rc.ctx(), c.queryThreads(), "vh-sample")) {
      threads.add(thread("vh-sampler", () -> samplerLoop(samplerPool, end)));
      if (c.freshnessRate() > 0) {
        threads.add(thread("vh-fresh", () -> freshnessLoop(c.freshnessRate(), end)));
      }
      if (!c.disruptions().isEmpty()) {
        threads.add(thread("vh-chaos", () -> chaosLoop(c.disruptions(), end)));
      }
      if (c.rebuildExplicitAt() != null) {
        threads.add(thread("vh-rebuild", () -> rebuildAt(c.rebuildExplicitAt().toNanos())));
      }
      IvfRegionLayout.Plan plan = c.variant() == VariantKind.IVF ? IvfRegionLayout.plan(c) : null;
      if (plan != null && c.rebuildAutoEnabled() && (relayouts(plan) || c.statisticsOn())) {
        threads.add(thread("vh-generations", () -> generationLoop(plan, endNanos)));
      }
      threads.add(thread("vh-ticker", () -> tickerLoop(end, stage)));
      for (Thread t : threads) {
        t.start();
      }
      try {
        for (int i = 0; i < threads.size(); i++) {
          await(threads.get(i), end);
          if (i + 1 == c.queryThreads()) {
            // The query threads come first. The query rate uses the real length of the reads, with
            // any extension. A late write does not change it.
            workEnd = Math.max(endNanos, System.nanoTime());
            endOffsetMillis = rc.events().offset();
          }
        }
      } catch (InterruptedException e) {
        // An abort stops the workload and the disruption at once.
        stop(end, threads);
        throw e;
      }
      String overrun = end.overrun(System.nanoTime());
      if (overrun != null) {
        // The disruption passes the extension limit. The workload and the extension stop now, and
        // the run is degraded.
        LOG.warn("Disruption {} did not end within the extension limit", overrun);
        rc.events().event("disruption-overrun", overrun + ": the steady window stops");
        rc.manifest()
          .degrade("disruption " + overrun + " did not end within "
            + TimeUnit.NANOSECONDS.toSeconds(MAX_EXTENSION_NANOS + JOIN_GRACE_NANOS)
            + " s after the window end");
      }
      running.set(false);
      end.stop();
      for (Thread t : threads) {
        t.join(60_000);
        if (t.isAlive()) {
          // Mark the run as degraded before the harness writes the manifest. A late failure of
          // the thread then cannot change the result after the outputs are on disk.
          LOG.warn("Thread {} did not stop", t.getName());
          rc.events().event("thread-stuck", t.getName());
          rc.manifest().degrade("thread " + t.getName() + " did not stop");
          t.interrupt();
        }
      }
      sampler.sample(samplerPool);
    }
    double measured = (workEnd - warmupEndNanos) / 1e9;
    MetricSnapshot after = rc.metrics() == null ? null : rc.metrics().snapshot(stage + "-end");
    return summarize(rate, stage, measured, endOffsetMillis, end, before, after);
  }

  /**
   * Waits for a thread. The wait moves with a disruption in flight, up to the extension limit.
   */
  private static void await(Thread t, StageEnd end) throws InterruptedException {
    long left;
    while (
      t.isAlive()
        && (left = end.joinDeadline(System.nanoTime(), JOIN_GRACE_NANOS, MAX_EXTENSION_NANOS)
          - System.nanoTime()) > 0
    ) {
      t.join(Math.max(1, Math.min(left / 1_000_000, 1000)));
    }
  }

  private void stop(StageEnd end, List<Thread> threads) {
    running.set(false);
    end.stop();
    for (Thread t : threads) {
      t.interrupt();
    }
  }

  /**
   * Creates a worker thread. An unchecked exception or an error stops the work of the thread too
   * early. The harness then records an event and marks the run as degraded. A virtual machine error
   * continues to propagate after the record.
   */
  private Thread thread(String name, Runnable r) {
    Thread t = new Thread(() -> {
      try {
        r.run();
      } catch (RuntimeException | Error e) {
        LOG.error("{} failed", name, e);
        rc.events().event("thread-failed", name + ": " + e);
        rc.manifest().degrade(name + " failed: " + e);
        if (e instanceof VirtualMachineError) {
          throw (VirtualMachineError) e;
        }
      }
    }, name);
    t.setDaemon(true);
    return t;
  }

  private boolean measuring(long nanos) {
    return nanos - warmupEndNanos >= 0;
  }

  /** Selects query configuration for a given sequence number. */
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

  private void queryLoop(Throttle throttle, StageEnd end, String stage) {
    try (QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      while (running.get()) {
        long slot = throttle.claim();
        if (end.reached(slot)) {
          return;
        }
        Throttle.awaitSlot(slot);
        QuerySpec q = pick(querySeq.getAndIncrement());
        QueryExecutor.Result r = exec.execute(q, throttle.unthrottled() ? 0 : slot);
        if (!measuring(slot)) {
          continue;
        }
        String key = q.layout().id() + "|" + q.condition().family() + "|" + q.k();
        Measurement m = measurements.computeIfAbsent(key, x -> {
          Measurement created = new Measurement(stage, q.layout().id(), q.condition().family(),
            q.k(), sweep.label(), sweep.value());
          created.coverage(rc.variant().coverage(q.layout(), q.condition().attribute()).id());
          return created;
        });
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

  private void mutationLoop(Throttle throttle, StageEnd end) {
    try (Connection conn = rc.ctx().connection()) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        while (running.get()) {
          long slot = throttle.claim();
          if (end.writesReached(slot)) {
            return;
          }
          Throttle.awaitSlot(slot);
          long seq = mutationSeq.getAndIncrement();
          Mutator.Kind kind = mutator.choose(seq);
          long t0 = System.nanoTime();
          try {
            long ordinal = mutator.apply(writer, kind, seq);
            // Exclude pause duration during quiesced sampling from write latency
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
    } finally {
      writesEnded();
    }
  }

  /** Records the time at which a writer thread stops. */
  private void writesEnded() {
    long offset = rc.events().offset();
    writesEndOffsetMillis.accumulateAndGet(offset, Math::max);
  }

  private void samplerLoop(QueryPool pool, StageEnd end) {
    HarnessConfig c = rc.ctx().config();
    long interval = c.sampleInterval().toNanos();
    long next = warmupEndNanos;
    try {
      while (running.get()) {
        Throttle.awaitSlot(next);
        if (end.ended(System.nanoTime())) {
          return;
        }
        sampler.sample(pool);
        next += interval;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void freshnessLoop(double rate, StageEnd end) {
    Throttle throttle = new Throttle(rate);
    long probeSeq = 0;
    try (Connection conn = rc.ctx().connection();
      QueryExecutor exec = new QueryExecutor(rc.ctx())) {
      conn.setAutoCommit(false);
      try (RowWriter writer =
        new RowWriter(conn, rc.ctx().tables(), rc.layouts(), rc.ctx().corpus().attributeModel())) {
        while (running.get()) {
          long slot = throttle.claim();
          // A probe writes a row, so no probe that is due after the planned end runs.
          if (end.writesReached(slot)) {
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
            // Probe failure does not abort steady-state window
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
    } finally {
      writesEnded();
    }
  }

  /** Outcome status for freshness probe operations. */
  enum Probe {
    /** Row was found in query results across all layouts. */
    FOUND,
    /** Row was not returned by at least one layout. */
    STALE,
    /** Probe query failed or threw an exception. */
    FAILED
  }

  /**
   * Evaluates freshness by inserting a probe row, immediately querying for it, and then deleting
   * it.
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
          // Re-query immediately to check if row was delayed versus dropped
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

  /** Formats query result or error for diagnostic logging. */
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
   * Generates probe vector for freshness evaluation, scaled for INNER_PRODUCT if necessary.
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
   * Executes standalone freshness verification outside the measurement window.
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

  /** Returns count of failed freshness probe operations. */
  public long freshnessErrors() {
    return freshnessErrors.get();
  }

  /**
   * Returns latency start timestamp for mutation, adjusting for gate closure duration if
   * applicable.
   */
  static long latencyStart(long slot, WriteGate gate) {
    long opened = gate.lastOpenedNanos();
    return opened != Long.MIN_VALUE && opened - slot > 0 ? opened : slot;
  }

  /**
   * Applies the scheduled disruptions. A disruption that is in flight at the window end extends the
   * reads of the stage until its recovery wait ends. The later disruptions then do not start, and
   * the window records them as skipped. An interrupt ends the disruption in flight and the loop.
   */
  private void chaosLoop(List<Disruption> schedule, StageEnd end) {
    int n = 0;
    String table = rc.ctx().tables().fullTable(rc.layouts().iterator().next());
    try (Connection conn = rc.ctx().connection()) {
      for (int i = 0; i < schedule.size(); i++) {
        Disruption d = schedule.get(i);
        Throttle.awaitSlot(warmupEndNanos + d.at().toNanos());
        if (!running.get()) {
          return;
        }
        // A restart holds the layout lock, because a layout move can put regions on the drained
        // server. During the restart, the midkey layout skips its polls, and the other layouts
        // wait.
        ReentrantLock lock = d.kind() == Disruption.Kind.RESTART ? rc.relayoutLock() : null;
        long lockWait = lock == null ? 0 : lockUntilWindowEnd(lock, end);
        boolean started = lockWait >= 0 && end.begin(d.toString(), System.nanoTime());
        if (!started) {
          String reason = "the steady window ended";
          if (lockWait < 0) {
            end.skip(d.toString());
            reason = "the layout lock stayed busy until the window end";
          } else if (lock != null) {
            lock.unlock();
          }
          for (Disruption later : schedule.subList(i + 1, schedule.size())) {
            end.skip(later.toString());
          }
          rc.events().event("disruption-skipped", String.join(" ", end.skipped()) + ": " + reason);
          return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("disruption", d.toString());
        // Every failure after the start ends the disruption, so the stage cannot stay open.
        try {
          MetricSnapshot pre;
          try {
            pre = rc.metrics() == null ? null : rc.metrics().sample(null);
            record.put("offset_ms", rc.events().offset());
            if (lockWait >= LOCK_WAIT_RECORD_NANOS) {
              record.put("lock_wait_seconds", lockWait / 1e9);
            }
            record.put("action", ChaosInjector.apply(d, n++, conn, table, rc.events(), record));
          } finally {
            if (lock != null) {
              lock.unlock();
            }
          }
          record.put("mttr_seconds",
            awaitRecovery(pre, conn, table, System.currentTimeMillis(), record));
        } catch (InterruptedException e) {
          interrupted(record);
          return;
        } catch (Exception e) {
          if (!running.get() || Thread.currentThread().isInterrupted()) {
            // A client call can turn an interrupt into an I/O error and clear the interrupt flag.
            // A failure after the stage stops thus counts as an interrupt.
            LOG.debug("Disruption {} stops with the stage", d, e);
            interrupted(record);
            return;
          }
          LOG.warn("Disruption {} failed", d, e);
          record.put("error", e.toString());
          rc.manifest().flag("disruption " + d + " failed: " + e.getMessage());
        } finally {
          end.finish(System.nanoTime());
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
   * Takes the layout lock for a restart. The wait ends at the window end, because no disruption
   * starts after it.
   * @return the wait in nanoseconds, or -1 if the lock stays busy until the window end
   */
  static long lockUntilWindowEnd(ReentrantLock lock, StageEnd end) throws InterruptedException {
    long start = System.nanoTime();
    if (!lock.tryLock(Math.max(0L, end.windowEndNanos() - start), TimeUnit.NANOSECONDS)) {
      return -1;
    }
    return System.nanoTime() - start;
  }

  /**
   * Awaits cluster recovery following a disruption by monitoring region counts, unrestricted read
   * metrics, and rebuild queues. A wait that times out marks the disruption record.
   */
  private Double awaitRecovery(MetricSnapshot pre, Connection conn, String table,
    long appliedMillis, Map<String, Object> record) throws InterruptedException {
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
    // Allow disruption effects to register in metrics before checking recovery
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
    record.put("recovery_timed_out", true);
    return null;
  }

  /** Records a disruption that an interrupt ends, and keeps the interrupt for the caller. */
  private void interrupted(Map<String, Object> record) {
    record.put("error", "interrupted");
    synchronized (disruptions) {
      disruptions.add(record);
    }
    Thread.currentThread().interrupt();
  }

  /** Returns table region count via Admin API, or -1 on error. */
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
   * Evaluates whether metric snapshot indicates recovery from disruption.
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
    // Verify region manager availability matches current table region count
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
      IvfRegionLayout.Plan plan = IvfRegionLayout.plan(rc.ctx().config());
      for (TableLayout layout : rc.layouts()) {
        rc.explicitRebuilds().add(layout);
        try {
          rebuild(layout, plan);
        } finally {
          rc.explicitRebuilds().remove(layout);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Rebuilds the index of one layout. Before the rebuild, the empty ID range of the new generation
   * gets its splits. After the promotion, the populated range gets its final layout.
   */
  private void rebuild(TableLayout layout, IvfRegionLayout.Plan plan) throws InterruptedException {
    String index = rc.ctx().tables().fullIndex(layout);
    String sql = "ALTER INDEX " + rc.ctx().tables().index(layout) + " ON "
      + rc.ctx().tables().fullTable(layout) + " REBUILD";
    try (Connection conn = rc.ctx().connection()) {
      Long before = activeGeneration(conn, index);
      int target = plan.regions();
      if (before != null && plan.mode() == IvfRegionLayout.Mode.SPLIT_ON) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> pre;
        try {
          pre = IvfVariant.layoutBeforeRebuild(conn, index, plan, before, rc.events());
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception e) {
          pre = failure(index, IvfRegionLayout.PHASE_BEFORE_REBUILD, e);
        }
        Object t = pre == null ? null : pre.get("target_regions");
        if (t instanceof Number) {
          target = ((Number) t).intValue();
        }
        recordLayoutChange(layout, "explicit", IvfRegionLayout.PHASE_BEFORE_REBUILD, before, null,
          t0, pre);
      }
      rc.events().event("explicit-rebuild-start", sql);
      long t0 = System.nanoTime();
      try {
        conn.createStatement().execute(sql);
        rc.events().event("explicit-rebuild-end",
          String.format("%s in %.1f s", layout.id(), (System.nanoTime() - t0) / 1e9));
      } catch (SQLException e) {
        rc.events().event("explicit-rebuild-failed", e.getMessage());
        return;
      } catch (RuntimeException e) {
        // An unchecked exception shows a defect, not a refused rebuild. Mark the run as degraded.
        LOG.error("The explicit rebuild of {} failed", index, e);
        rc.events().event("explicit-rebuild-failed", e.toString());
        rc.manifest().degrade("the explicit rebuild of " + index + " failed: " + e);
        return;
      }
      Long after = activeGeneration(conn, index);
      if (relayouts(plan) && IvfRegionLayout.promoted(before, after)) {
        relayoutAfterPromotion(conn, layout, plan, before, after, target, false, "explicit",
          IvfRegionLayout.PHASE_AFTER_PROMOTION, Long.MAX_VALUE);
      }
      if (
        IvfRegionLayout.promoted(before, after) && statisticsAfterExplicitRebuild(relayouts(plan),
          rc.laidOutGeneration(layout), after, rc.ctx().config().rebuildAutoEnabled())
      ) {
        updateStatistics(layout, after, "explicit", true);
      }
    } catch (SQLException e) {
      rc.events().event("explicit-rebuild-failed", e.getMessage());
    }
  }

  /**
   * Checks at intervals for a generation that an automatic rebuild promotes. The new generation
   * fills the regions that hold its ID range. Natural growth splits these regions by size under
   * load, so the harness splits them at their midkeys. A promotion between windows shows at the
   * first check of the next window.
   */
  private void generationLoop(IvfRegionLayout.Plan plan, long endNanos) {
    try (Connection conn = rc.ctx().connection()) {
      while (running.get() && System.nanoTime() - endNanos < 0) {
        for (TableLayout layout : rc.layouts()) {
          if (!running.get()) {
            return;
          }
          if (rc.explicitRebuilds().contains(layout)) {
            continue;
          }
          String index = rc.ctx().tables().fullIndex(layout);
          Long active;
          try {
            active = activeGeneration(conn, index);
          } catch (SQLException e) {
            LOG.debug("Reading the generation of {} failed", index, e);
            continue;
          }
          Long known = rc.laidOutGeneration(layout);
          if (!relayouts(plan)) {
            known = active;
          } else if (known == null && active != null) {
            rc.laidOutGeneration(layout, active);
            known = active;
          } else if (IvfRegionLayout.promoted(known, active)) {
            long stopMillis =
              System.currentTimeMillis() + Math.max(0, (endNanos - System.nanoTime()) / 1_000_000);
            relayoutAfterPromotion(conn, layout, plan, known, active, plan.regions(), true, "auto",
              IvfRegionLayout.PHASE_AUTO_PROMOTION, stopMillis);
            known = rc.laidOutGeneration(layout);
          }
          // The statistics wait for the layout, so they describe the final regions.
          if (active != null && known != null && known.longValue() == active.longValue()) {
            Long described = rc.statisticsGeneration(layout);
            if (described == null) {
              rc.statisticsGeneration(layout, active);
            } else if (IvfRegionLayout.promoted(described, active)) {
              updateStatistics(layout, active, "auto", false);
            }
          }
        }
        long left = (endNanos - System.nanoTime()) / 1_000_000;
        if (left > 0) {
          Thread.sleep(Math.min(GENERATION_POLL_MS, left));
        }
      }
    } catch (SQLException e) {
      LOG.warn("The generation check failed", e);
      rc.manifest().flag("generation check failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Updates the statistics after a promotion in the on mode. The rows of the new generation replace
   * the rows that the old guideposts describe. Each generation gets one update. The update holds
   * the layout lock, so it does not overlap a layout change. A failure raises a flag and does not
   * stop the run.
   * @param wait true to wait for the lock, or false to skip this check if another thread holds it
   */
  private void updateStatistics(TableLayout layout, long generation, String trigger, boolean wait)
    throws InterruptedException {
    if (!rc.ctx().config().statisticsOn()) {
      return;
    }
    ReentrantLock lock = rc.relayoutLock();
    if (wait) {
      lock.lockInterruptibly();
    } else if (!lock.tryLock()) {
      return;
    }
    try {
      Long described = rc.statisticsGeneration(layout);
      if (described != null && described == generation) {
        return;
      }
      String table = rc.ctx().tables().fullTable(layout);
      long origin = rc.events().startMillis();
      long start = System.currentTimeMillis();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("layout", layout.id());
      m.put("table", table);
      m.put("trigger", trigger);
      m.put("generation", generation);
      m.put("start_offset_ms", start - origin);
      rc.events().event("statistics-update-start", String.format(java.util.Locale.ROOT,
        "%s: %s rebuild, generation %d", table, trigger, generation));
      try {
        Statistics.update(rc.ctx(), java.util.Collections.singletonList(table), true);
        m.put("outcome", "updated");
      } catch (SQLException e) {
        LOG.warn("Updating the statistics of {} failed", table, e);
        m.put("outcome", "failed");
        m.put("error", e.getMessage());
        rc.manifest().flag("the statistics update of " + table + " after generation " + generation
          + " failed: " + e.getMessage());
      }
      // The count of the rows is a record, so a failed read does not fail a good update.
      if ("updated".equals(m.get("outcome"))) {
        try (Connection conn = rc.ctx().connection()) {
          m.put("index_guideposts",
            Statistics.guideposts(conn, rc.ctx().tables().fullIndex(layout)));
        } catch (SQLException e) {
          LOG.warn("Reading the statistics of {} failed", table, e);
          m.put("guideposts_error", e.getMessage());
        }
      }
      // A failed update counts too, so the next checks do not repeat it.
      rc.statisticsGeneration(layout, generation);
      long end = System.currentTimeMillis();
      m.put("end_offset_ms", end - origin);
      m.put("seconds", (end - start) / 1000.0);
      rc.manifest().append("statistics", "updates", m);
      rc.events().event(
        "failed".equals(m.get("outcome")) ? "statistics-update-failed" : "statistics-update-end",
        String.format(java.util.Locale.ROOT, "%s: generation %d in %.1f s", table, generation,
          m.get("seconds")));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns true if an explicit rebuild updates the statistics now. The statistics wait for the
   * final regions. When the layout stays incomplete, the generation loop completes it and then
   * updates the statistics. Without that loop, no later layout comes, so the update runs now.
   */
  static boolean statisticsAfterExplicitRebuild(boolean relayouts, Long laidOut, long generation,
    boolean generationLoop) {
    return !relayouts || (laidOut != null && laidOut == generation) || !generationLoop;
  }

  /** Returns true if the layout of the plan changes when a generation changes. */
  static boolean relayouts(IvfRegionLayout.Plan plan) {
    return plan.mode() == IvfRegionLayout.Mode.SPLIT_ON
      || plan.mode() == IvfRegionLayout.Mode.MIDKEY;
  }

  private static Long activeGeneration(Connection conn, String index) throws SQLException {
    return IndexInfo.read(conn, index).activeGeneration();
  }

  /**
   * Gives a promoted generation its region layout. Only one thread changes the layout at a time,
   * also across windows. The explicit rebuild waits for the lock. The generation check skips a poll
   * while another thread holds it. Each generation gets its layout once. A failed or partial layout
   * leaves the generation open, so a later poll tries again. After {@link #MAX_LAYOUT_ATTEMPTS}
   * failures the harness gives up on the generation. A layout that stops at the end of its window
   * does not count as a failure. An interrupt records the partial change before it ends the layout.
   * @param stopMillis the wall time at which the layout stops
   */
  private void relayoutAfterPromotion(Connection conn, TableLayout layout,
    IvfRegionLayout.Plan plan, Long from, long generation, int target, boolean byMidkey,
    String trigger, String phase, long stopMillis) throws InterruptedException {
    ReentrantLock lock = rc.relayoutLock();
    if (byMidkey) {
      if (!lock.tryLock()) {
        return;
      }
    } else {
      lock.lockInterruptibly();
    }
    try {
      Long known = rc.laidOutGeneration(layout);
      if (known != null && known == generation) {
        return;
      }
      String index = rc.ctx().tables().fullIndex(layout);
      long start = System.currentTimeMillis();
      Map<String, Object> result = null;
      try {
        Long promoted = IvfVariant.promotionMillis(conn, index, generation);
        if (promoted != null && promoted > rc.events().startMillis() && promoted < start) {
          start = promoted;
        }
        result = IvfVariant.layoutAfterPromotion(conn, index, plan, generation, target, byMidkey,
          phase, stopMillis, rc.events());
      } catch (InterruptedException e) {
        result = new LinkedHashMap<>();
        result.put("interrupted", true);
        recordLayoutChange(layout, trigger, phase, from, generation, start, result);
        throw e;
      } catch (Exception e) {
        result = failure(index, phase, e);
      }
      recordLayoutChange(layout, trigger, phase, from, generation, start, result);
      if (complete(result)) {
        rc.laidOutGeneration(layout, generation);
      } else if (!windowEnded(result) && rc.layoutAttempt(layout) >= MAX_LAYOUT_ATTEMPTS) {
        rc.laidOutGeneration(layout, generation);
        rc.manifest().flag("the " + phase + " layout of " + index + " generation " + generation
          + " is incomplete after " + MAX_LAYOUT_ATTEMPTS + " attempts");
      }
    } finally {
      lock.unlock();
    }
  }

  /** Returns true if a layout result has no error, no stalled midkey split, and no failed split. */
  static boolean complete(Map<String, Object> result) {
    if (result == null) {
      return true;
    }
    if (result.containsKey("error") || result.containsKey("interrupted")) {
      return false;
    }
    Object midkey = result.get("midkey");
    if (midkey instanceof Map && !"done".equals(((Map<?, ?>) midkey).get("outcome"))) {
      return false;
    }
    Object topUp = result.get("top_up");
    if (topUp instanceof Map) {
      Object failed = ((Map<?, ?>) topUp).get("failed");
      return !(failed instanceof List) || ((List<?>) failed).isEmpty();
    }
    return true;
  }

  /** Returns true if the midkey splits of a layout result stopped at the end of the window. */
  static boolean windowEnded(Map<String, Object> result) {
    Object midkey = result == null ? null : result.get("midkey");
    return midkey instanceof Map && "window ended".equals(((Map<?, ?>) midkey).get("outcome"));
  }

  private Map<String, Object> failure(String index, String phase, Exception e) {
    LOG.warn("The {} layout of {} failed", phase, index, e);
    rc.events().event(IvfRegionLayout.EVENT, phase + " failed " + index + ": " + e);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("error", e.toString());
    return m;
  }

  /**
   * Records a change of the index layout. The interval from the start to the end is not a
   * steady-state layout, and the report marks it.
   */
  private void recordLayoutChange(TableLayout layout, String trigger, String phase, Long from,
    Long generation, long startMillis, Map<String, Object> result) {
    long end = System.currentTimeMillis();
    long origin = rc.events().startMillis();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("layout", layout.id());
    m.put("index", rc.ctx().tables().fullIndex(layout));
    m.put("trigger", trigger);
    m.put("phase", phase);
    m.put("from_generation", from);
    m.put("generation", generation);
    m.put("start_offset_ms", startMillis - origin);
    m.put("end_offset_ms", end - origin);
    m.put("seconds", (end - startMillis) / 1000.0);
    m.put("result", result);
    synchronized (layoutChanges) {
      layoutChanges.add(m);
    }
    Object regions = result == null ? null : result.get("index_regions");
    rc.events().event(IvfRegionLayout.EVENT,
      String.format(java.util.Locale.ROOT, "%s done %s: %s rebuild, generation %s, %.1f s%s", phase,
        m.get("index"), trigger, generation == null ? from : generation, m.get("seconds"),
        regions == null ? "" : ", " + regions + " regions"));
  }

  private void tickerLoop(StageEnd end, String stage) {
    long next = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    long qOk = 0;
    long qErr = 0;
    long mOk = 0;
    long mErr = 0;
    try {
      while (running.get() && !end.ended(System.nanoTime())) {
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
    long endOffsetMillis, StageEnd end, MetricSnapshot before, MetricSnapshot after) {
    HarnessConfig c = rc.ctx().config();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("stage", stage);
    m.put("mode", c.queryMode().name().toLowerCase(java.util.Locale.ROOT));
    m.put("offered_rate", c.queryMode() == QueryMode.OPEN ? rate : null);
    m.put("sweep", sweep.label());
    m.put("sweep_parameter", sweep.parameter());
    m.put("sweep_value", sweep.value());
    double plannedSeconds = c.duration().toNanos() / 1e9;
    m.put("planned_seconds", plannedSeconds);
    // The reads can run past the planned end for a disruption. No write that is due after the
    // planned end runs.
    m.put("measured_seconds", measuredSeconds);
    m.put("write_seconds", plannedSeconds);
    m.put("end_offset_ms", endOffsetMillis);
    long writesEnd = writesEndOffsetMillis.get();
    m.put("writes_end_offset_ms", writesEnd < 0 ? null : writesEnd);
    m.put("extension_seconds", end.extensionSeconds());
    m.put("extended_by", end.extendedBy());
    m.put("overrun_by", end.overrunBy());
    m.put("skipped_disruptions", end.skipped());
    long ok = queriesOk.get();
    m.put("queries", ok);
    m.put("query_errors", queriesFailed.get());
    m.put("achieved_qps", measuredSeconds > 0 ? ok / measuredSeconds : Double.NaN);
    m.put("query_latency", LatencyRecorder.summarize(window.total()));
    m.put("mutations", mutationsOk.get());
    m.put("mutation_errors", mutationsFailed.get());
    m.put("achieved_mutations_per_second",
      plannedSeconds > 0 ? mutationsOk.get() / plannedSeconds : Double.NaN);
    m.put("mutation_latency", LatencyRecorder.summarize(mutationLatency.total()));
    // Aggregate mutation counts by kind during active measurement window
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
    synchronized (layoutChanges) {
      m.put("layout_changes", new ArrayList<>(layoutChanges));
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

  /** Verifies index health metrics and flags anomalies in the manifest. */
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
