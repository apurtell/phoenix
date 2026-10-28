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
package org.apache.phoenix.vectorharness.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.result.CsvTable;

/**
 * Produces the reports of the harness: the text summary of one run, the comparison of several runs
 * over the same corpus and panel, and the CSV datasets behind both. A comparison centers on the
 * efficiency frontiers of recall against cost: the cost each run pays to reach each recall target,
 * the recall each run reaches within each p99 latency budget, and the paired per-query differences
 * at matched operating points. It also sets side by side the build duration, the storage footprint,
 * the write overhead, the selectivity crossover of filtered queries, the degradation of global
 * scans against tenant-scoped ones, and the behavior over lifecycle events.
 */
public final class ReportGenerator {

  public static final double[] DEFAULT_RECALL_TARGETS = { 0.90, 0.95, 0.99 };
  public static final double[] DEFAULT_LATENCY_BUDGETS_MS = { 10 };
  public static final int DEFAULT_RESAMPLES = 1000;
  static final long PAIRED_SEED = 0x5eed_9a12_ed00_0001L;

  /**
   * Prefixes of the settings that comparable runs may vary: the variant and its tuning, names and
   * locations, the number of repetitions, timeouts, ingest parallelism, observation, and server or
   * client properties. All other settings, among them the corpus, the panel, the query shapes and
   * rates, the mutation workload, and the deterministic mode, which changes the mutation workers
   * and the sampling, must be identical.
   */
  static final List<String> EXEMPT_PREFIXES = Collections
    .unmodifiableList(Arrays.asList("run.id", "run.output.dir", "run.profile.name", "run.variant",
      "run.repetitions", "jdbc.url", "corpus.path", "corpus.name", "table.schema", "table.suffix",
      "table.snapshot", "table.update_statistics", "table.layouts", "index.build.timeout", "ivf.",
      "hnsw.", "query.ivf.", "query.hnsw.", "query.timeout", "ingest.threads", "ingest.batch.size",
      "observe.", "report.", "rebuild.", "recall.bootstrap.resamples", "phoenix.", "hbase."));

  private final double[] targets;
  private final double[] budgets;
  private final int resamples;

  public ReportGenerator() {
    this(DEFAULT_RECALL_TARGETS, DEFAULT_LATENCY_BUDGETS_MS, DEFAULT_RESAMPLES);
  }

  public ReportGenerator(double[] recallTargets, double[] latencyBudgetsMillis, int resamples) {
    this.targets = recallTargets.clone();
    this.budgets = latencyBudgetsMillis.clone();
    Arrays.sort(this.targets);
    Arrays.sort(this.budgets);
    this.resamples = resamples;
  }

  /** Returns a generator with the targets, budgets, and resamples of a profile. */
  public static ReportGenerator of(HarnessConfig c) {
    return new ReportGenerator(c.reportRecallTargets(), c.reportLatencyBudgetsMillis(),
      c.bootstrapResamples());
  }

  /** Returns the recall target at which matched comparisons are made: 0.95 by default. */
  double primaryTarget() {
    return targets.length == 0 ? 0.95 : targets[(targets.length - 1) / 2];
  }

  // Single run

  /** Returns the text summary of the run in a directory. */
  public String summary(Path runDir) throws IOException {
    return summary(RunResult.read(runDir));
  }

  /** Returns the text summary of one run. */
  public String summary(RunResult r) {
    StringBuilder sb = new StringBuilder();
    List<RunResult> runs = Collections.singletonList(r);
    header(sb, r);
    setup(sb, runs);
    validity(sb, runs);
    frontier(sb, r);
    costAtTargets(sb, r);
    recallWithinBudgets(sb, runs);
    selectivity(sb, runs);
    tenancy(sb, runs);
    steady(sb, r);
    lifecycle(sb, runs);
    flags(sb, runs);
    return sb.toString();
  }

  private void header(StringBuilder sb, RunResult r) {
    sb.append("Run ").append(r.id()).append('\n');
    sb.append(String.format(Locale.ROOT, "  variant %s, profile %s, metrics mode %s%n", r.variant(),
      r.profile(), str(r.get("run.metrics_mode"))));
    sb.append(
      String.format(Locale.ROOT, "  corpus %s rows=%s dim=%s metric=%s fingerprint=%s panel=%s%n",
        str(r.get("corpus.source")), str(r.get("corpus.rows")), str(r.get("corpus.dim")),
        str(r.get("corpus.metric")), shortHash(r.fingerprint()), shortHash(r.panelDigest())));
    sb.append(String.format(Locale.ROOT, "  cluster HBase %s, %s RegionServers; java %s%s%n",
      str(r.get("cluster.hbase_version")), str(r.get("cluster.region_servers")),
      str(r.get("run.java")),
      Boolean.TRUE.equals(r.get("run.simd_vector_api")) ? " with the vector API" : ""));
  }

  private void setup(StringBuilder sb, List<RunResult> runs) {
    sb.append("\nLoad, build, and storage\n");
    TextTable t = new TextTable("run", "rows", "load rows/s", "stream rows/s", "build s",
      "load bytes", "built bytes", "index bytes", "end bytes", "plan check");
    for (RunResult r : runs) {
      double load = r.footprintBytes("load_footprint");
      double built = r.footprintBytes("build.footprint");
      t.row(label(r, runs), r.get("load.rows_loaded"), number(r.get("ingest.rows_per_second")),
        number(r.get("stream.rows_per_second")), number(r.get("build.build.seconds")),
        TextTable.bytes(load), TextTable.bytes(built), TextTable.bytes(built - load),
        TextTable.bytes(r.footprintBytes("footprint")), planCheck(r));
    }
    sb.append(t.render("  "));
  }

  @SuppressWarnings("unchecked")
  private static String planCheck(RunResult r) {
    Object pc = r.get("build.plan_check");
    if (!(pc instanceof Map)) {
      return "-";
    }
    long matching = 0;
    long total = 0;
    for (Object v : ((Map<String, Object>) pc).values()) {
      if (v instanceof Map) {
        Object m = ((Map<String, Object>) v).get("matching");
        Object n = ((Map<String, Object>) v).get("total");
        if (m instanceof Number && n instanceof Number) {
          matching += ((Number) m).longValue();
          total += ((Number) n).longValue();
        }
      }
    }
    return total == 0 ? "-" : matching + "/" + total;
  }

  private void validity(StringBuilder sb, List<RunResult> runs) {
    sb.append("\nValidity\n");
    TextTable t = new TextTable("run", "exact recall", "exact queries", "freshness");
    for (RunResult r : runs) {
      t.row(label(r, runs), number(r.get("validity.exact_recall")), r.get("validity.exact_queries"),
        number(r.get("validity.freshness")));
    }
    sb.append(t.render("  "));
  }

  private void frontier(StringBuilder sb, RunResult r) {
    sb.append("\nEfficiency frontier (quiesced sweep; * marks Pareto points of recall and p99)\n");
    TextTable t = new TextTable("group", "sweep", "recall", "95% CI", "p50 ms", "p99 ms", "qps",
      "rows/result", "rpcs/q", "plan", "");
    for (Map.Entry<String, List<FrontierPoint>> g : Frontiers.byGroup(r.frontier()).entrySet()) {
      Set<FrontierPoint> pareto =
        new LinkedHashSet<>(Frontiers.pareto(g.getValue(), Frontiers.Cost.P99));
      for (FrontierPoint p : g.getValue()) {
        t.row(g.getKey(), p.sweep(), p.recall(), ci(p.recallLow(), p.recallHigh()), p.p50(),
          p.p99(), p.qps(), p.readAmplification(), p.rpcsPerQuery(),
          p.planClass()
            + (p.planDiscrepancy() ? " (expected " + p.column("expected_plan") + ")" : ""),
          pareto.contains(p) ? "*" : "");
      }
    }
    sb.append(t.isEmpty() ? "  no frontier measurements\n" : t.render("  "));
  }

  private void costAtTargets(StringBuilder sb, RunResult r) {
    sb.append("\nCost at recall targets (each cost minimized over the points that reach the"
      + " target)\n");
    TextTable t = new TextTable("group", "target", "min p50 ms", "min p99 ms", "max qps",
      "min rows/result", "min rpcs/q", "sweep at min p99");
    for (Map.Entry<String, List<FrontierPoint>> g : Frontiers.byGroup(r.frontier()).entrySet()) {
      for (double target : targets) {
        FrontierPoint p99 = Frontiers.cheapestAtRecall(g.getValue(), target, Frontiers.Cost.P99);
        if (p99 == null) {
          t.row(g.getKey(), target, null, null, null, null, null, "unreached");
          continue;
        }
        t.row(g.getKey(), target, costValue(g.getValue(), target, Frontiers.Cost.P50), p99.p99(),
          costValue(g.getValue(), target, Frontiers.Cost.THROUGHPUT),
          costValue(g.getValue(), target, Frontiers.Cost.READ_AMPLIFICATION),
          costValue(g.getValue(), target, Frontiers.Cost.RPCS), p99.sweep());
      }
    }
    sb.append(t.isEmpty() ? "  no frontier measurements\n" : t.render("  "));
  }

  private static Double costValue(List<FrontierPoint> group, double target, Frontiers.Cost c) {
    FrontierPoint p = Frontiers.cheapestAtRecall(group, target, c);
    return p == null ? null : c.value(p);
  }

  private void recallWithinBudgets(StringBuilder sb, List<RunResult> runs) {
    sb.append("\nRecall within p99 latency budgets\n");
    List<String> header = new ArrayList<>(Arrays.asList("group", "budget ms"));
    for (RunResult r : runs) {
      header.add(label(r, runs));
    }
    if (runs.size() > 1) {
      header.add("best");
    }
    TextTable t = new TextTable(header.toArray(new String[0]));
    for (String group : groups(runs)) {
      for (double budget : budgets) {
        List<Object> row = new ArrayList<>(Arrays.asList(group, budget));
        double best = Double.NEGATIVE_INFINITY;
        String winner = "-";
        for (RunResult r : runs) {
          FrontierPoint p = Frontiers.bestWithinBudget(group(r, group), budget);
          row.add(p == null ? "none" : TextTable.num(p.recall()) + " @" + p.sweep());
          if (p != null && p.recall() > best + Frontiers.RECALL_TOLERANCE) {
            best = p.recall();
            winner = label(r, runs);
          }
        }
        if (runs.size() > 1) {
          row.add(winner);
        }
        t.row(row.toArray());
      }
    }
    sb.append(t.isEmpty() ? "  no frontier measurements\n" : t.render("  "));
  }

  /**
   * The selectivity curves of the filtered shapes: the cost at the primary target as the filter
   * narrows, with the plan class, which shows where the optimizer turns to filter-first scans and,
   * across runs, where the winner changes.
   */
  private void selectivity(StringBuilder sb, List<RunResult> runs) {
    double target = primaryTarget();
    List<String> header = new ArrayList<>(Arrays.asList("layout", "shape", "k", "selectivity"));
    for (RunResult r : runs) {
      header.add(label(r, runs) + " p50 ms");
      header.add(label(r, runs) + " plan");
    }
    if (runs.size() > 1) {
      header.add("fastest");
    }
    TextTable t = new TextTable(header.toArray(new String[0]));
    Map<String, TreeMap<Double, String>> curves = new LinkedHashMap<>();
    for (String group : groups(runs)) {
      String[] parts = parseGroup(group);
      String[] fam = parts[1].split("@", 2);
      QueryShape shape = shape(fam[0]);
      if (shape == null || !shape.sweepsSelectivity() || fam.length < 2) {
        continue;
      }
      double sel = FrontierPoint.number(fam[1]);
      curves.computeIfAbsent(parts[0] + " " + fam[0] + " " + parts[2], c -> new TreeMap<>())
        .put(sel, group);
    }
    for (Map.Entry<String, TreeMap<Double, String>> c : curves.entrySet()) {
      String previous = null;
      for (Map.Entry<Double, String> e : c.getValue().descendingMap().entrySet()) {
        String[] parts = parseGroup(e.getValue());
        List<Object> row = new ArrayList<>(
          Arrays.asList(parts[0], parts[1].split("@", 2)[0], parts[2].substring(2), e.getKey()));
        double best = Double.POSITIVE_INFINITY;
        String winner = "-";
        for (RunResult r : runs) {
          FrontierPoint p = Frontiers.operatingPoint(group(r, e.getValue()), target);
          boolean reached = p != null && p.recall() >= target - Frontiers.RECALL_TOLERANCE;
          row.add(p == null ? null : (reached ? (Object) p.p50() : "unreached"));
          row.add(p == null ? "-" : p.planClass());
          if (reached && p.p50() < best) {
            best = p.p50();
            winner = label(r, runs);
          }
        }
        if (runs.size() > 1) {
          boolean crossover = previous != null && !winner.equals(previous) && !"-".equals(winner);
          row.add(winner + (crossover ? " (crossover)" : ""));
          previous = winner;
        }
        t.row(row.toArray());
      }
    }
    if (!t.isEmpty()) {
      sb.append(String.format(Locale.ROOT,
        "%nSelectivity curves (p50 at recall %s; plan class of the operating point)%n",
        TextTable.num(target)));
      sb.append(t.render("  "));
    }
  }

  /** Tenant-scoped against global queries of multi-tenant tables, at the primary target. */
  private void tenancy(StringBuilder sb, List<RunResult> runs) {
    double target = primaryTarget();
    TextTable t = new TextTable("run", "layout", "k", "unfiltered p50", "tenant p50", "global p50",
      "global/tenant");
    for (RunResult r : runs) {
      Map<String, List<FrontierPoint>> groups = Frontiers.byGroup(r.frontier());
      Map<String, Map<String, FrontierPoint>> byLayoutK = new LinkedHashMap<>();
      for (Map.Entry<String, List<FrontierPoint>> g : groups.entrySet()) {
        String[] parts = parseGroup(g.getKey());
        String shape = parts[1].split("@", 2)[0];
        if (
          shape.equals(QueryShape.UNFILTERED.id()) || shape.equals(QueryShape.TENANT_SCOPED.id())
            || shape.equals(QueryShape.GLOBAL.id())
        ) {
          FrontierPoint p = Frontiers.operatingPoint(g.getValue(), target);
          if (p != null) {
            byLayoutK.computeIfAbsent(parts[0] + " " + parts[2], x -> new LinkedHashMap<>())
              .put(shape, p);
          }
        }
      }
      for (Map.Entry<String, Map<String, FrontierPoint>> e : byLayoutK.entrySet()) {
        FrontierPoint tenant = e.getValue().get(QueryShape.TENANT_SCOPED.id());
        FrontierPoint global = e.getValue().get(QueryShape.GLOBAL.id());
        if (tenant == null || global == null) {
          continue;
        }
        FrontierPoint unf = e.getValue().get(QueryShape.UNFILTERED.id());
        String[] lk = e.getKey().split(" ");
        t.row(label(r, runs), lk[0], lk[1].substring(2), unf == null ? null : unf.p50(),
          tenant.p50(), global.p50(), global.p50() / tenant.p50());
      }
    }
    if (!t.isEmpty()) {
      sb.append(String.format(Locale.ROOT,
        "%nMulti-tenant against global scans (p50 ms at the recall %s operating point)%n",
        TextTable.num(target)));
      sb.append(t.render("  "));
    }
  }

  private void steady(StringBuilder sb, RunResult r) {
    List<Map<String, Object>> windows = r.steadyWindows();
    sb.append("\nSteady state\n");
    if (windows.isEmpty()) {
      sb.append("  no steady-state windows\n");
      return;
    }
    TextTable t =
      new TextTable("stage", "sweep", "offered", "qps", "p50 ms", "p99 ms", "p999 ms", "errors",
        "mutations/s", "mut p99 ms", "freshness", "recall mean", "recall min", "p99/quiesced");
    for (Map<String, Object> w : windows) {
      double[] recall = recallOfStage(r, str(w.get("stage")));
      t.row(w.get("stage"), w.get("sweep"), number(w.get("offered_rate")),
        number(w.get("achieved_qps")), number(path(w, "query_latency.p50_ms")),
        number(path(w, "query_latency.p99_ms")), number(path(w, "query_latency.p999_ms")),
        w.get("query_errors"), number(w.get("achieved_mutations_per_second")),
        number(path(w, "mutation_latency.p99_ms")), number(w.get("freshness")), recall[0],
        recall[1], latencyInflation(r, w));
    }
    sb.append(t.render("  "));
    Map<String, Double> deltas = new TreeMap<>();
    for (Map<String, Object> w : windows) {
      Object d = w.get("metric_deltas");
      if (d instanceof Map) {
        for (Map.Entry<?, ?> e : ((Map<?, ?>) d).entrySet()) {
          if (e.getValue() instanceof Number && ((Number) e.getValue()).doubleValue() != 0) {
            deltas.merge(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue(),
              Double::sum);
          }
        }
      }
    }
    if (!deltas.isEmpty()) {
      sb.append("  vector index metric changes over the windows:\n");
      TextTable m = new TextTable("metric", "delta");
      for (Map.Entry<String, Double> e : deltas.entrySet()) {
        m.row(e.getKey(), e.getValue());
      }
      sb.append(m.render("    "));
    }
  }

  /** Returns the mean and the minimum recall of the samples of a stage, or NaNs. */
  static double[] recallOfStage(RunResult r, String stage) {
    double sum = 0;
    double min = Double.NaN;
    int n = 0;
    for (Map<String, String> s : r.recallSamples()) {
      if (!stage.equals(s.get("stage"))) {
        continue;
      }
      double v = FrontierPoint.number(s.get("recall"));
      if (Double.isNaN(v)) {
        continue;
      }
      sum += v;
      min = Double.isNaN(min) ? v : Math.min(min, v);
      n++;
    }
    return new double[] { n == 0 ? Double.NaN : sum / n, min };
  }

  /**
   * Returns the ratio of a window's p99 latency to the quiesced p99 at the same sweep point, the
   * latter averaged over the frontier groups: the latency cost of concurrent writes and load.
   */
  static double latencyInflation(RunResult r, Map<String, Object> w) {
    double p99 = number(path(w, "query_latency.p99_ms"));
    String sweep = str(w.get("sweep"));
    double value = number(w.get("sweep_value"));
    double sum = 0;
    int n = 0;
    for (FrontierPoint p : r.frontier()) {
      if (Double.isNaN(p.p99())) {
        continue;
      }
      // A default sweep point is labeled apart from the swept points; match it by its value.
      boolean same = sweep.equals(p.sweep())
        || (!Double.isNaN(value) && value == p.sweepValue() && sameParameter(sweep, p.sweep()));
      if (same) {
        sum += p.p99();
        n++;
      }
    }
    return n == 0 || Double.isNaN(p99) || sum == 0 ? Double.NaN : p99 / (sum / n);
  }

  /** Returns true if two sweep labels, such as ef=64 and ef=default(64), name one parameter. */
  static boolean sameParameter(String a, String b) {
    int i = a.indexOf('=');
    int j = b.indexOf('=');
    return i > 0 && j > 0 && a.substring(0, i).equals(b.substring(0, j));
  }

  private void lifecycle(StringBuilder sb, List<RunResult> runs) {
    sb.append("\nLifecycle events\n");
    TextTable t = new TextTable("run", "kind", "count", "first s", "last s", "stages");
    for (RunResult r : runs) {
      Map<String, long[]> kinds = new TreeMap<>();
      Map<String, Set<String>> stages = new TreeMap<>();
      for (Map<String, String> e : r.events()) {
        String kind = e.get("kind");
        if (kind == null || kind.equals("stage")) {
          continue;
        }
        long off = (long) FrontierPoint.number(e.get("offset_ms"));
        long[] k = kinds.computeIfAbsent(kind, x -> new long[] { 0, Long.MAX_VALUE, 0 });
        k[0]++;
        k[1] = Math.min(k[1], off);
        k[2] = Math.max(k[2], off);
        stages.computeIfAbsent(kind, x -> new LinkedHashSet<>()).add(e.get("stage"));
      }
      for (Map.Entry<String, long[]> e : kinds.entrySet()) {
        t.row(label(r, runs), e.getKey(), e.getValue()[0], e.getValue()[1] / 1000.0,
          e.getValue()[2] / 1000.0, String.join(",", stages.get(e.getKey())));
      }
    }
    sb.append(t.isEmpty() ? "  none\n" : t.render("  "));
  }

  private void flags(StringBuilder sb, List<RunResult> runs) {
    boolean any = false;
    for (RunResult r : runs) {
      for (String f : r.flags()) {
        if (!any) {
          sb.append("\nFlags (measurements that may not describe a healthy index)\n");
          any = true;
        }
        sb.append("  ").append(runs.size() > 1 ? label(r, runs) + ": " : "").append(f).append('\n');
      }
    }
    if (!any) {
      sb.append("\nFlags: none\n");
    }
  }

  // Comparison

  /**
   * Returns the reasons that runs are not comparable: different corpora or panels, or different
   * settings outside the variant and its tuning. Empty if they are comparable.
   */
  public List<String> comparability(List<RunResult> runs) {
    List<String> problems = new ArrayList<>();
    if (runs.size() < 2) {
      return problems;
    }
    RunResult base = runs.get(0);
    Map<String, String> bs = base.settings();
    for (RunResult r : runs.subList(1, runs.size())) {
      if (!eq(base.fingerprint(), r.fingerprint())) {
        problems.add(r.id() + " has corpus fingerprint " + shortHash(r.fingerprint()) + ", not "
          + shortHash(base.fingerprint()));
      }
      if (!eq(base.panelDigest(), r.panelDigest())) {
        problems.add(r.id() + " has query panel " + shortHash(r.panelDigest()) + ", not "
          + shortHash(base.panelDigest()));
      }
      // The same settings can still index other rows: a run on tables that an earlier run
      // streamed into builds over, or queries, every row and streams none.
      double ia = number(base.get("build.build.rows_indexed"));
      double ib = number(r.get("build.build.rows_indexed"));
      if (!Double.isNaN(ia) && !Double.isNaN(ib) && ia != ib) {
        problems.add(r.id() + " indexed " + (long) ib + " rows at the build, not " + (long) ia);
      }
      boolean sa = base.get("stream") != null;
      boolean sb = r.get("stream") != null;
      if (sa != sb) {
        problems.add(r.id() + (sb ? " streamed" : " streamed no") + " rows after the build, "
          + base.id() + (sa ? " did" : " did not"));
      }
      Map<String, String> rs = r.settings();
      Set<String> keys = new java.util.TreeSet<>(bs.keySet());
      keys.addAll(rs.keySet());
      for (String key : keys) {
        if (exempt(key)) {
          continue;
        }
        String a = bs.getOrDefault(key, "");
        String b = rs.getOrDefault(key, "");
        if (!a.trim().equals(b.trim())) {
          problems.add(r.id() + " sets " + key + "=" + b + ", not " + a);
        }
      }
    }
    return problems;
  }

  /** Returns true if comparable runs may differ in a setting. */
  public static boolean exempt(String key) {
    for (String p : EXEMPT_PREFIXES) {
      if (key.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the comparison of runs, the first being the base of paired differences.
   * @throws IllegalArgumentException if the runs are not comparable and force is false
   */
  public String compare(List<RunResult> runs, boolean force) throws IOException {
    if (runs.size() < 2) {
      throw new IllegalArgumentException("A comparison needs at least two runs");
    }
    List<String> problems = comparability(runs);
    if (!problems.isEmpty() && !force) {
      throw new IllegalArgumentException("The runs are not comparable:\n  "
        + String.join("\n  ", problems) + "\nPass --force to compare them anyway");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Comparison of ").append(runs.size()).append(" runs\n");
    TextTable legend = new TextTable("label", "run", "variant", "profile");
    for (RunResult r : runs) {
      legend.row(label(r, runs), r.id(), r.variant(), r.profile());
    }
    sb.append(legend.render("  "));
    RunResult base = runs.get(0);
    sb.append(String.format(Locale.ROOT, "  corpus fingerprint %s, panel %s, metric %s%n",
      shortHash(base.fingerprint()), shortHash(base.panelDigest()),
      str(base.get("corpus.metric"))));
    if (problems.isEmpty()) {
      sb.append("  comparable: identical corpus, panel, and execution parameters\n");
    } else {
      sb.append("  NOT COMPARABLE (forced):\n");
      for (String p : problems) {
        sb.append("    ").append(p).append('\n');
      }
    }
    setup(sb, runs);
    validity(sb, runs);
    costComparison(sb, runs);
    recallWithinBudgets(sb, runs);
    paired(sb, runs, pairedGroups(runs));
    writeOverhead(sb, runs);
    selectivity(sb, runs);
    tenancy(sb, runs);
    lifecycle(sb, runs);
    flags(sb, runs);
    return sb.toString();
  }

  private void costComparison(StringBuilder sb, List<RunResult> runs) {
    Frontiers.Cost[] costs = { Frontiers.Cost.P50, Frontiers.Cost.P99, Frontiers.Cost.THROUGHPUT,
      Frontiers.Cost.READ_AMPLIFICATION, Frontiers.Cost.RPCS };
    for (Frontiers.Cost cost : costs) {
      sb.append(String.format(Locale.ROOT, "%nCost at recall targets: %s%n",
        cost == Frontiers.Cost.THROUGHPUT ? "max qps" : "min " + cost.id()));
      List<String> header = new ArrayList<>(Arrays.asList("group", "target"));
      for (RunResult r : runs) {
        header.add(label(r, runs));
      }
      header.add("best");
      header.add("best/next");
      TextTable t = new TextTable(header.toArray(new String[0]));
      for (String group : groups(runs)) {
        for (double target : targets) {
          List<Object> row = new ArrayList<>(Arrays.asList(group, target));
          List<double[]> values = new ArrayList<>();
          for (int i = 0; i < runs.size(); i++) {
            FrontierPoint p = Frontiers.cheapestAtRecall(group(runs.get(i), group), target, cost);
            if (p == null) {
              row.add("unreached");
            } else {
              row.add(TextTable.num(cost.value(p)) + " @" + p.sweep());
              values.add(new double[] { cost.of(p), i });
            }
          }
          values.sort((a, b) -> Double.compare(a[0], b[0]));
          if (values.isEmpty()) {
            row.add("-");
            row.add(null);
          } else {
            row.add(label(runs.get((int) values.get(0)[1]), runs));
            row.add(values.size() < 2 ? null : ratio(cost, values.get(0)[0], values.get(1)[0]));
          }
          t.row(row.toArray());
        }
      }
      sb.append(t.isEmpty() ? "  no frontier measurements\n" : t.render("  "));
    }
  }

  /** Returns the advantage of the best over the next: a ratio above one favors the best. */
  private static Double ratio(Frontiers.Cost cost, double best, double next) {
    if (cost == Frontiers.Cost.THROUGHPUT) {
      return next == 0 ? null : best / next;
    }
    return best == 0 ? null : next / best;
  }

  /** The paired differences of each other run against the base run. */
  static final class PairedResult {
    final RunResult base;
    final RunResult other;
    final double target;
    final List<PairedComparison.Group> groups;

    PairedResult(RunResult base, RunResult other, double target,
      List<PairedComparison.Group> groups) {
      this.base = base;
      this.other = other;
      this.target = target;
      this.groups = groups;
    }
  }

  /**
   * Pairs each other run with the base run. In each group common to both, each run is taken at its
   * operating point for the highest recall target that both reach, or at its most accurate point if
   * they share none.
   */
  List<PairedResult> pairedGroups(List<RunResult> runs) throws IOException {
    List<PairedResult> out = new ArrayList<>();
    RunResult base = runs.get(0);
    PairedComparison pc = new PairedComparison(resamples, PAIRED_SEED);
    for (RunResult other : runs.subList(1, runs.size())) {
      List<PairedComparison.Selection> sel = new ArrayList<>();
      for (String group : groups(Arrays.asList(base, other))) {
        List<FrontierPoint> a = group(base, group);
        List<FrontierPoint> b = group(other, group);
        if (a.isEmpty() || b.isEmpty()) {
          continue;
        }
        FrontierPoint pa = null;
        FrontierPoint pb = null;
        for (int i = targets.length - 1; i >= 0 && pa == null; i--) {
          FrontierPoint x = Frontiers.cheapestAtRecall(a, targets[i], Frontiers.Cost.P50);
          FrontierPoint y = Frontiers.cheapestAtRecall(b, targets[i], Frontiers.Cost.P50);
          if (x != null && y != null) {
            pa = x;
            pb = y;
          }
        }
        if (pa == null) {
          pa = Frontiers.mostAccurate(a);
          pb = Frontiers.mostAccurate(b);
        }
        if (pa == null || pb == null) {
          continue;
        }
        sel.add(
          new PairedComparison.Selection(pa.layout(), pa.family(), pa.k(), pa.sweep(), pb.sweep()));
      }
      out.add(new PairedResult(base, other, primaryTarget(), pc.compare(base, other, sel)));
    }
    return out;
  }

  private void paired(StringBuilder sb, List<RunResult> runs, List<PairedResult> paired) {
    for (PairedResult p : paired) {
      sb.append(String.format(Locale.ROOT,
        "%nPaired per-query differences, %s minus %s"
          + " (operating points at the highest recall target both reach)%n",
        label(p.other, runs), label(p.base, runs)));
      TextTable t = new TextTable("group", "sweeps", "pairs", "latency ms", "95% CI", "median ms",
        "faster share", "recall", "95% CI", "recall up/down");
      for (PairedComparison.Group g : p.groups) {
        t.row(g.group(), g.baseSweep() + " vs " + g.otherSweep(), g.pairs(), g.latencyMeanDiff(),
          ci(g.latencyLow(), g.latencyHigh()), g.latencyMedianDiff(), g.fasterShare(),
          g.recallMeanDiff(), ci(g.recallLow(), g.recallHigh()),
          TextTable.num(g.recallHigherShare()) + "/" + TextTable.num(g.recallLowerShare()));
      }
      sb.append(
        t.isEmpty() ? "  no paired queries (per_query.csv missing or disjoint)\n" : t.render("  "));
    }
  }

  /** Ingest and mutation throughput and latency, and the query latency cost of writes. */
  private void writeOverhead(StringBuilder sb, List<RunResult> runs) {
    sb.append("\nWrite overhead\n");
    TextTable t = new TextTable("run", "load rows/s", "stream rows/s", "stage", "mutations/s",
      "mut p50 ms", "mut p99 ms", "query p99 ms", "p99/quiesced");
    for (RunResult r : runs) {
      List<Map<String, Object>> windows = r.steadyWindows();
      if (windows.isEmpty()) {
        t.row(label(r, runs), number(r.get("ingest.rows_per_second")),
          number(r.get("stream.rows_per_second")));
      }
      for (Map<String, Object> w : windows) {
        t.row(label(r, runs), number(r.get("ingest.rows_per_second")),
          number(r.get("stream.rows_per_second")), w.get("stage"),
          number(w.get("achieved_mutations_per_second")),
          number(path(w, "mutation_latency.p50_ms")), number(path(w, "mutation_latency.p99_ms")),
          number(path(w, "query_latency.p99_ms")), latencyInflation(r, w));
      }
    }
    sb.append(t.render("  "));
  }

  // CSV exports

  /** Writes the CSV datasets of one run or a comparison into a directory. */
  public void export(List<RunResult> runs, Path outDir) throws IOException {
    Files.createDirectories(outDir);
    exportRuns(runs, outDir.resolve("runs.csv"));
    exportFrontier(runs, outDir.resolve("frontier.csv"));
    exportPareto(runs, outDir.resolve("pareto.csv"));
    exportCost(runs, outDir.resolve("cost_at_recall.csv"));
    exportBudget(runs, outDir.resolve("recall_at_budget.csv"));
    exportSteady(runs, outDir.resolve("steady.csv"));
    exportRecall(runs, outDir.resolve("recall_timeseries.csv"));
    if (runs.size() > 1) {
      exportPaired(pairedGroups(runs), outDir.resolve("paired.csv"));
    }
  }

  private void exportRuns(List<RunResult> runs, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "run", "variant", "profile", "fingerprint",
      "panel_digest", "rows_loaded", "load_rows_per_second", "stream_rows_per_second",
      "build_seconds", "load_bytes", "built_bytes", "index_bytes", "end_bytes", "exact_recall",
      "freshness", "flags")) {
      for (RunResult r : runs) {
        double load = r.footprintBytes("load_footprint");
        double built = r.footprintBytes("build.footprint");
        t.row(r.id(), r.variant(), r.profile(), r.fingerprint(), r.panelDigest(),
          r.get("load.rows_loaded"), r.get("ingest.rows_per_second"),
          r.get("stream.rows_per_second"), r.get("build.build.seconds"), load, built, built - load,
          r.footprintBytes("footprint"), r.get("validity.exact_recall"),
          r.get("validity.freshness"), String.join("; ", r.flags()));
      }
    }
  }

  private void exportFrontier(List<RunResult> runs, Path file) throws IOException {
    List<String> columns = new ArrayList<>(Arrays.asList("run", "variant"));
    for (RunResult r : runs) {
      for (FrontierPoint p : r.frontier()) {
        for (String c : p.row().keySet()) {
          if (!columns.contains(c)) {
            columns.add(c);
          }
        }
      }
    }
    try (CsvTable t = CsvTable.create(file, columns.toArray(new String[0]))) {
      for (RunResult r : runs) {
        for (FrontierPoint p : r.frontier()) {
          Object[] row = new Object[columns.size()];
          row[0] = r.id();
          row[1] = r.variant();
          for (int i = 2; i < row.length; i++) {
            row[i] = p.column(columns.get(i));
          }
          t.row(row);
        }
      }
    }
  }

  private void exportPareto(List<RunResult> runs, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "run", "variant", "cost", "layout", "family", "k",
      "sweep", "recall", "value")) {
      for (RunResult r : runs) {
        for (List<FrontierPoint> g : Frontiers.byGroup(r.frontier()).values()) {
          for (Frontiers.Cost cost : Frontiers.Cost.values()) {
            for (FrontierPoint p : Frontiers.pareto(g, cost)) {
              t.row(r.id(), r.variant(), cost.id(), p.layout(), p.family(), p.k(), p.sweep(),
                p.recall(), cost.value(p));
            }
          }
        }
      }
    }
  }

  private void exportCost(List<RunResult> runs, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "run", "variant", "layout", "family", "k", "target",
      "cost", "value", "sweep", "recall")) {
      for (RunResult r : runs) {
        for (List<FrontierPoint> g : Frontiers.byGroup(r.frontier()).values()) {
          FrontierPoint first = g.get(0);
          for (double target : targets) {
            for (Frontiers.Cost cost : Frontiers.Cost.values()) {
              FrontierPoint p = Frontiers.cheapestAtRecall(g, target, cost);
              t.row(r.id(), r.variant(), first.layout(), first.family(), first.k(), target,
                cost.id(), p == null ? null : cost.value(p), p == null ? null : p.sweep(),
                p == null ? null : p.recall());
            }
          }
        }
      }
    }
  }

  private void exportBudget(List<RunResult> runs, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "run", "variant", "layout", "family", "k", "budget_ms",
      "recall", "sweep", "p99_ms")) {
      for (RunResult r : runs) {
        for (List<FrontierPoint> g : Frontiers.byGroup(r.frontier()).values()) {
          FrontierPoint first = g.get(0);
          for (double budget : budgets) {
            FrontierPoint p = Frontiers.bestWithinBudget(g, budget);
            t.row(r.id(), r.variant(), first.layout(), first.family(), first.k(), budget,
              p == null ? null : p.recall(), p == null ? null : p.sweep(),
              p == null ? null : p.p99());
          }
        }
      }
    }
  }

  private void exportSteady(List<RunResult> runs, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "run", "variant", "stage", "mode", "offered_rate",
      "sweep", "measured_seconds", "queries", "query_errors", "achieved_qps", "p50_ms", "p95_ms",
      "p99_ms", "p999_ms", "mutations", "mutation_errors", "achieved_mutations_per_second",
      "mutation_p50_ms", "mutation_p99_ms", "freshness_probes", "freshness", "freshness_p99_ms",
      "recall_samples", "recall_mean", "recall_min", "p99_over_quiesced")) {
      for (RunResult r : runs) {
        for (Map<String, Object> w : r.steadyWindows()) {
          double[] recall = recallOfStage(r, str(w.get("stage")));
          t.row(r.id(), r.variant(), w.get("stage"), w.get("mode"), w.get("offered_rate"),
            w.get("sweep"), w.get("measured_seconds"), w.get("queries"), w.get("query_errors"),
            w.get("achieved_qps"), path(w, "query_latency.p50_ms"), path(w, "query_latency.p95_ms"),
            path(w, "query_latency.p99_ms"), path(w, "query_latency.p999_ms"), w.get("mutations"),
            w.get("mutation_errors"), w.get("achieved_mutations_per_second"),
            path(w, "mutation_latency.p50_ms"), path(w, "mutation_latency.p99_ms"),
            w.get("freshness_probes"), w.get("freshness"), path(w, "freshness_latency.p99_ms"),
            w.get("recall_samples"), recall[0], recall[1], latencyInflation(r, w));
        }
      }
    }
  }

  private void exportRecall(List<RunResult> runs, Path file) throws IOException {
    List<String> columns = new ArrayList<>(Arrays.asList("run", "variant"));
    for (RunResult r : runs) {
      for (Map<String, String> s : r.recallSamples()) {
        for (String c : s.keySet()) {
          if (!columns.contains(c)) {
            columns.add(c);
          }
        }
      }
    }
    try (CsvTable t = CsvTable.create(file, columns.toArray(new String[0]))) {
      for (RunResult r : runs) {
        for (Map<String, String> s : r.recallSamples()) {
          Object[] row = new Object[columns.size()];
          row[0] = r.id();
          row[1] = r.variant();
          for (int i = 2; i < row.length; i++) {
            row[i] = s.get(columns.get(i));
          }
          t.row(row);
        }
      }
    }
  }

  private void exportPaired(List<PairedResult> paired, Path file) throws IOException {
    try (CsvTable t = CsvTable.create(file, "base_run", "other_run", "group", "base_sweep",
      "other_sweep", "pairs", "unpaired", "latency_mean_diff_ms", "latency_low_ms",
      "latency_high_ms", "latency_median_diff_ms", "faster_share", "recall_mean_diff", "recall_low",
      "recall_high", "recall_higher_share", "recall_lower_share")) {
      for (PairedResult p : paired) {
        for (PairedComparison.Group g : p.groups) {
          t.row(p.base.id(), p.other.id(), g.group(), g.baseSweep(), g.otherSweep(), g.pairs(),
            g.unpaired(), g.latencyMeanDiff(), g.latencyLow(), g.latencyHigh(),
            g.latencyMedianDiff(), g.fasterShare(), g.recallMeanDiff(), g.recallLow(),
            g.recallHigh(), g.recallHigherShare(), g.recallLowerShare());
        }
      }
    }
  }

  // Helpers

  /** Returns the groups of the runs, in order of first appearance. */
  static List<String> groups(List<RunResult> runs) {
    Set<String> out = new LinkedHashSet<>();
    for (RunResult r : runs) {
      for (FrontierPoint p : r.frontier()) {
        out.add(p.group());
      }
    }
    return new ArrayList<>(out);
  }

  static List<FrontierPoint> group(RunResult r, String group) {
    List<FrontierPoint> out = new ArrayList<>();
    for (FrontierPoint p : r.frontier()) {
      if (p.group().equals(group)) {
        out.add(p);
      }
    }
    return out;
  }

  /** Splits a group key into its layout, family, and "k=n" parts. */
  static String[] parseGroup(String group) {
    String[] parts = group.split(" ");
    if (parts.length < 3) {
      return new String[] { group, "", "k=0" };
    }
    return new String[] { parts[0], parts[1], parts[2] };
  }

  private static QueryShape shape(String id) {
    for (QueryShape s : QueryShape.values()) {
      if (s.id().equals(id)) {
        return s;
      }
    }
    return null;
  }

  /** Returns the label of a run in a report: its variant, made unique by position. */
  static String label(RunResult r, List<RunResult> runs) {
    int same = 0;
    for (RunResult o : runs) {
      if (eq(o.variant(), r.variant())) {
        same++;
      }
    }
    if (same <= 1) {
      return r.variant() == null ? r.id() : r.variant();
    }
    return r.variant() + "#" + (runs.indexOf(r) + 1);
  }

  private static boolean eq(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }

  private static String str(Object o) {
    return o == null ? "-" : o.toString();
  }

  private static String shortHash(String h) {
    return h == null ? "-" : h.length() > 16 ? h.substring(0, 16) : h;
  }

  private static String ci(double low, double high) {
    if (Double.isNaN(low) || Double.isNaN(high)) {
      return "-";
    }
    return "[" + TextTable.num(low) + ", " + TextTable.num(high) + "]";
  }

  /** Returns a value as a number for display; strings that hold numbers are parsed. */
  static double number(Object o) {
    if (o instanceof Number) {
      return ((Number) o).doubleValue();
    }
    return o == null ? Double.NaN : FrontierPoint.number(o.toString());
  }

  @SuppressWarnings("unchecked")
  static Object path(Map<String, Object> m, String dotted) {
    Object cur = m;
    for (String part : dotted.split("\\.")) {
      if (!(cur instanceof Map)) {
        return null;
      }
      cur = ((Map<String, Object>) cur).get(part);
    }
    return cur;
  }
}
