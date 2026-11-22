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
package org.apache.phoenix.pherf.analysis;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.phoenix.pherf.analysis.Replicate.Key;
import org.apache.phoenix.pherf.exception.AnalysisException;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Severity;

/**
 * Performs offline analysis of replicate runs according to the contract defined in
 * phoenix-pherf/ANALYSIS.md. Evaluating a single set of replicates produces the median and
 * bootstrap interval for each metric along with the count parity across the set. Evaluating two
 * sets, consisting of a baseline and a candidate, additionally produces a comparison for every
 * metric present in both sets. Because the generated ANALYSIS_label.json report omits timestamps,
 * identical inputs always produce identical output bytes.
 */
public final class Analysis {
  public static final String FORMAT = "pherf-analysis";
  /** This version increments only when a change cannot be read by an existing reader. */
  public static final int VERSION = 1;
  /**
   * Exit code returned when analysis is refused because the replicates do not all share the same
   * run class; no report is written in this case.
   */
  public static final int EXIT_CLASS_MISMATCH = 66;
  /** Exit code returned after the report is written when it contains a FAIL row. */
  public static final int EXIT_FAIL = 67;
  /**
   * The fixed base seed used to initialize every bootstrap stream, which is also recorded in the
   * generated report.
   */
  public static final long SEED = 8070;
  /**
   * A metric change is classified as a regression or an improvement only when its magnitude exceeds
   * this percentage share of the baseline median.
   */
  public static final double REGRESSION_THRESHOLD_PCT = 10;
  /**
   * A regression yields a FAIL verdict only when both sets contain at least this many values for
   * the metric. When fewer values are available, two sets drawn from the same distribution often
   * produce an interval that excludes zero by chance.
   */
  public static final int FAIL_MIN_REPLICATES = 3;
  public static final String COUNT_PARITY = "pherf.analysis.count_parity";
  public static final String REGRESSION = "pherf.analysis.regression";
  private static final String SET = "set";
  private static final String BASELINE = "baseline";
  private static final String CANDIDATE = "candidate";
  private static final String REGRESSED = "regression";
  private static final String IMPROVED = "improvement";
  private static final String TIMED = "co-generated, but a closed-loop thread stopped on time";

  private Analysis() {
  }

  /**
   * Analyzes one or two replicate sets and writes the resulting ANALYSIS_label.json report to the
   * output directory. Each set is a directory whose subdirectories represent individual replicates
   * that are loaded in name order. A summary of the analysis is printed to the provided print
   * stream.
   * @return the path to the generated report file.
   * @throws AnalysisException with exit code 1 when the input is unusable, with
   *                           {@link #EXIT_CLASS_MISMATCH} when the run classes differ across
   *                           replicates (in both cases without writing a report), or with
   *                           {@link #EXIT_FAIL} after writing the report when it contains a FAIL
   *                           row.
   */
  public static Path run(String[] dirs, File outDir, String label, PrintStream out)
    throws AnalysisException, IOException {
    String name = "ANALYSIS_" + label + ".json";
    Path target = new File(outDir, name).toPath();
    // Because a refused analysis must not leave a report behind, any pre-existing report from an
    // earlier run with the same label is deleted upfront.
    Files.deleteIfExists(target);
    if (dirs == null || dirs.length < 1 || dirs.length > 2) {
      throw new AnalysisException(1, "-analyze takes one set directory, or a baseline and a "
        + "candidate directory, not " + (dirs == null ? 0 : dirs.length));
    }
    List<ReplicateSet> sets = new ArrayList<>();
    for (int i = 0; i < dirs.length; i++) {
      sets.add(ReplicateSet.load(dirs.length == 1 ? SET : i == 0 ? BASELINE : CANDIDATE,
        new File(dirs[i])));
    }
    checkClasses(sets);

    Report report = new Report();
    report.label = label;
    String reason = timingReason(sets);
    report.timingAuthoritative = reason == null;
    for (ReplicateSet set : sets) {
      report.sets.add(set.summarize());
    }
    report.verdicts.add(countParity(report.sets));
    // Evaluating a single set performs no cross-set comparison and therefore emits no regression
    // verdict row.
    if (sets.size() == 2) {
      compare(sets.get(0), sets.get(1), report);
      report.verdicts.add(regression(report, reason));
    }
    Severity result = Severity.PASS;
    for (Verdict verdict : report.verdicts) {
      result = verdict.severity.compareTo(result) > 0 ? verdict.severity : result;
    }
    report.result = result;

    String text =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report) + "\n";
    Path temporary = new File(outDir, name + ".tmp").toPath();
    Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
    print(report, out, target);
    if (result == Severity.FAIL) {
      throw new AnalysisException(EXIT_FAIL, "The analysis has a FAIL row; see " + target);
    }
    return target;
  }

  // RQ-211: Every replicate included in the analysis must belong to the same run class; otherwise,
  // no comparison is performed.
  private static void checkClasses(List<ReplicateSet> sets) throws AnalysisException {
    Set<String> classes = new TreeSet<>();
    StringBuilder text = new StringBuilder();
    for (ReplicateSet set : sets) {
      text.append(text.length() == 0 ? "" : "; ").append(set.role).append(':');
      for (Replicate replicate : set.replicates) {
        classes.add(replicate.getRunClass());
        text.append(' ').append(replicate.getName()).append('=').append(replicate.getRunClass());
      }
    }
    if (classes.size() > 1) {
      throw new AnalysisException(EXIT_CLASS_MISMATCH,
        "Refused: the analysis uses replicates of one run class only, and these have the classes "
          + classes + ": " + text);
    }
  }

  // Returns null when the timing evidence is authoritative, or an explanatory reason when it is
  // not.
  private static String timingReason(List<ReplicateSet> sets) {
    String runClass = sets.get(0).replicates.get(0).getRunClass();
    if (!Replicate.MEASUREMENT.equals(runClass)) {
      return "timing is not authoritative: run class " + runClass;
    }
    List<String> contaminated = new ArrayList<>();
    for (ReplicateSet set : sets) {
      for (Replicate replicate : set.replicates) {
        if (!replicate.getContamination().isEmpty()) {
          contaminated
            .add(set.role + "/" + replicate.getName() + " " + replicate.getContamination());
        }
      }
    }
    return contaminated.isEmpty()
      ? null
      : "timing is not authoritative: contaminated replicates " + contaminated;
  }

  // RQ-209, RQ-210: Operation counts must be equal across all replicates within every set that
  // contains two or more replicates.
  private static Verdict countParity(List<SetReport> sets) {
    int checked = 0;
    int keys = 0;
    int exactDiffer = 0;
    int advisoryDiffer = 0;
    String exactFirst = null;
    String advisoryFirst = null;
    for (SetReport set : sets) {
      if (set.replicates.size() < 2) {
        continue;
      }
      checked++;
      for (CountReport count : set.counts) {
        keys++;
        if (count.equal) {
          continue;
        }
        String detail =
          describe(set, count) + (count.reason == null ? "" : " (" + count.reason + ")");
        if (count.exact) {
          exactDiffer++;
          exactFirst = exactFirst == null ? detail : exactFirst;
        } else {
          advisoryDiffer++;
          advisoryFirst = advisoryFirst == null ? detail : advisoryFirst;
        }
      }
    }
    if (checked == 0) {
      return new Verdict(COUNT_PARITY, Severity.SKIP, HealthVerdict.COUNT,
        "no set has two or more replicates");
    }
    if (keys == 0) {
      return new Verdict(COUNT_PARITY, Severity.SKIP, HealthVerdict.COUNT, "no count key");
    }
    if (exactDiffer > 0) {
      return new Verdict(COUNT_PARITY, Severity.FAIL, HealthVerdict.COUNT, exactDiffer
        + " co-generated key(s) differ, " + advisoryDiffer + " rule-based; first: " + exactFirst);
    }
    if (advisoryDiffer > 0) {
      return new Verdict(COUNT_PARITY, Severity.ADVISORY, HealthVerdict.COUNT,
        advisoryDiffer + " rule-based key(s) differ, which is advisory; first: " + advisoryFirst);
    }
    return new Verdict(COUNT_PARITY, Severity.PASS, HealthVerdict.COUNT,
      keys + " count key(s) equal in " + checked + " set(s)");
  }

  private static String describe(SetReport set, CountReport count) {
    StringBuilder text = new StringBuilder(set.role).append(' ').append(count.operation).append(' ')
      .append(count.phase).append(' ').append(count.metric).append(':');
    for (int i = 0; i < count.values.size(); i++) {
      text.append(' ').append(set.replicates.get(i).getName()).append('=')
        .append(count.values.get(i) == null ? "absent" : count.values.get(i));
    }
    return text.toString();
  }

  // RQ-214: Computes the comparison for every metric present in both sets and records any metrics
  // that appear in only one set.
  private static void compare(ReplicateSet baseline, ReplicateSet candidate, Report report) {
    Set<Key> keys = new TreeSet<>(baseline.metricValues.keySet());
    keys.addAll(candidate.metricValues.keySet());
    for (Key key : keys) {
      double[] a = baseline.metricValues.get(key);
      double[] b = candidate.metricValues.get(key);
      if (a == null || b == null) {
        Unpaired unpaired = new Unpaired();
        unpaired.operation = key.getOperation();
        unpaired.phase = key.getPhase();
        unpaired.metric = key.getMetric();
        unpaired.set = a == null ? CANDIDATE : BASELINE;
        report.unpaired.add(unpaired);
        continue;
      }
      Comparison comparison = new Comparison();
      comparison.n = Math.min(a.length, b.length);
      comparison.operation = key.getOperation();
      comparison.phase = key.getPhase();
      comparison.metric = key.getMetric();
      comparison.baselineMedian = Bootstrap.median(a);
      comparison.candidateMedian = Bootstrap.median(b);
      comparison.delta = comparison.candidateMedian - comparison.baselineMedian;
      if (comparison.baselineMedian == 0) {
        comparison.outcome = "skip";
      } else {
        double[] ci = Bootstrap.deltaCi(a, b, Bootstrap.streamSeed(SEED, "comparison|" + key));
        double scale = 100 / comparison.baselineMedian;
        comparison.deltaPct = comparison.delta * scale;
        comparison.ciLowPct = ci[0] * scale;
        comparison.ciHighPct = ci[1] * scale;
        // Higher values indicate worse performance for latency metrics, whereas lower values
        // indicate worse performance for throughput.
        double sign = Replicate.OPS_PER_SEC.equals(key.getMetric()) ? -1 : 1;
        double worse = sign * comparison.deltaPct;
        boolean excludesZero = comparison.ciLowPct > 0 || comparison.ciHighPct < 0;
        comparison.outcome = Math.abs(worse) > REGRESSION_THRESHOLD_PCT && excludesZero
          ? (worse > 0 ? REGRESSED : IMPROVED)
          : "unchanged";
      }
      report.comparisons.add(comparison);
    }
  }

  private static Verdict regression(Report report, String reason) {
    Comparison worst = null;
    // Tracks the most severe regression that has sufficient values in both sets to trigger a FAIL
    // verdict.
    Comparison worstFail = null;
    int regressions = 0;
    int improvements = 0;
    int compared = 0;
    for (Comparison comparison : report.comparisons) {
      if (comparison.deltaPct == null) {
        continue;
      }
      compared++;
      if (IMPROVED.equals(comparison.outcome)) {
        improvements++;
      } else if (REGRESSED.equals(comparison.outcome)) {
        regressions++;
        worst = worse(worst, comparison);
        if (comparison.n >= FAIL_MIN_REPLICATES) {
          worstFail = worse(worstFail, comparison);
        }
      }
    }
    if (compared == 0) {
      return new Verdict(REGRESSION, Severity.SKIP, HealthVerdict.DURATION,
        "no metric with a nonzero baseline median in both sets");
    }
    if (worst == null) {
      return new Verdict(REGRESSION, Severity.PASS, HealthVerdict.DURATION,
        compared + " metric(s) compared, no regression, " + improvements + " improvement(s)");
    }
    String detail = regressions + " regression(s) of " + compared + " metric(s); worst: ";
    if (reason == null && worstFail != null) {
      return new Verdict(REGRESSION, Severity.FAIL, HealthVerdict.DURATION,
        detail + describe(worstFail));
    }
    detail += describe(worst) + (reason == null ? "" : "; " + reason);
    if (worstFail == null) {
      detail += "; fewer than " + FAIL_MIN_REPLICATES + " replicates in a set";
    }
    return new Verdict(REGRESSION, Severity.ADVISORY, HealthVerdict.DURATION, detail);
  }

  private static Comparison worse(Comparison worst, Comparison comparison) {
    return worst == null || Math.abs(comparison.deltaPct) > Math.abs(worst.deltaPct)
      ? comparison
      : worst;
  }

  private static String describe(Comparison comparison) {
    return String.format(Locale.ROOT, "%s %s %s %+.1f%% [%+.1f%%, %+.1f%%]", comparison.operation,
      comparison.phase, comparison.metric, comparison.deltaPct, comparison.ciLowPct,
      comparison.ciHighPct);
  }

  private static void print(Report report, PrintStream out, Path target) {
    for (SetReport set : report.sets) {
      out.println("Set " + set.role + " " + set.dir + ": run class " + set.runClass);
      for (Replicate replicate : set.replicates) {
        out.println("  " + replicate.getName() + " " + replicate.getLayout()
          + (replicate.getRunId() == null ? "" : " " + replicate.getRunId()) + " class "
          + replicate.getRunClass() + " contamination "
          + (replicate.getContamination().isEmpty() ? "none" : replicate.getContamination())
          + (replicate.getResult() == null ? "" : " result " + replicate.getResult()));
      }
    }
    for (Comparison comparison : report.comparisons) {
      if (REGRESSED.equals(comparison.outcome) || IMPROVED.equals(comparison.outcome)) {
        out.println((REGRESSED.equals(comparison.outcome) ? "Regression " : "Improvement ")
          + describe(comparison));
      }
    }
    for (Verdict verdict : report.verdicts) {
      out.println(verdict.check + "\t" + verdict.severity + "\t" + verdict.evidence + "\t"
        + verdict.source + "\t" + verdict.detail);
    }
    out.println("Analysis: " + report.result + ". Report: " + target);
  }

  /** Holds the replicates of a single set and their aggregated values indexed by key. */
  private static final class ReplicateSet {
    private final String role;
    private final File dir;
    private final List<Replicate> replicates = new ArrayList<>();
    // Stores the values recorded by the replicates that report each metric, preserved in replicate
    // order.
    private final Map<Key, double[]> metricValues = new TreeMap<>();

    private ReplicateSet(String role, File dir) {
      this.role = role;
      this.dir = dir;
    }

    static ReplicateSet load(String role, File dir) throws AnalysisException {
      File[] children = dir.listFiles();
      if (!dir.isDirectory() || children == null) {
        throw new AnalysisException(1, "Cannot analyze " + dir + ": not a directory");
      }
      Arrays.sort(children);
      ReplicateSet set = new ReplicateSet(role, dir);
      for (File child : children) {
        if (child.isDirectory()) {
          set.replicates.add(Replicate.load(child));
        }
      }
      if (set.replicates.isEmpty()) {
        throw new AnalysisException(1, "Cannot analyze " + dir + ": the set has no replicate");
      }
      Map<Key, List<Double>> values = new TreeMap<>();
      for (Replicate replicate : set.replicates) {
        for (Map.Entry<Key, Double> entry : replicate.getMetrics().entrySet()) {
          values.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
      }
      for (Map.Entry<Key, List<Double>> entry : values.entrySet()) {
        set.metricValues.put(entry.getKey(),
          entry.getValue().stream().mapToDouble(Double::doubleValue).toArray());
      }
      return set;
    }

    SetReport summarize() {
      SetReport report = new SetReport();
      report.role = role;
      report.dir = dir.getPath();
      report.runClass = replicates.get(0).getRunClass();
      Set<Key> metricKeys = new TreeSet<>();
      Set<Key> countKeys = new TreeSet<>();
      Set<String> exact = new LinkedHashSet<>();
      Set<String> timed = new LinkedHashSet<>();
      report.replicates.addAll(replicates);
      for (Replicate replicate : replicates) {
        metricKeys.addAll(replicate.getMetrics().keySet());
        countKeys.addAll(replicate.getCounts().keySet());
        exact.addAll(replicate.getExactOperations());
        timed.addAll(replicate.getTimedOperations());
      }
      for (Key key : metricKeys) {
        MetricReport metric = new MetricReport();
        metric.operation = key.getOperation();
        metric.phase = key.getPhase();
        metric.metric = key.getMetric();
        for (Replicate replicate : replicates) {
          metric.values.add(replicate.getMetrics().get(key));
        }
        double[] values = metricValues.get(key);
        metric.n = values.length;
        metric.median = Bootstrap.median(values);
        double[] ci = Bootstrap.ci(values, Bootstrap.streamSeed(SEED, key.toString()));
        metric.ciLow = ci[0];
        metric.ciHigh = ci[1];
        report.metrics.add(metric);
      }
      for (Key key : countKeys) {
        CountReport count = new CountReport();
        count.operation = key.getOperation();
        count.phase = key.getPhase();
        count.metric = key.getMetric();
        Set<Long> distinct = new LinkedHashSet<>();
        for (Replicate replicate : replicates) {
          Long value = replicate.getCounts().get(key);
          count.values.add(value);
          distinct.add(value);
        }
        // An absent value counts as a difference, meaning a key that is missing from any replicate
        // is never considered equal. A co-generated key is treated as advisory in the set whenever
        // a replicate stopped it on time.
        boolean stopped = timed.contains(key.getOperation());
        count.exact = exact.contains(key.getOperation()) && !stopped;
        count.reason = stopped ? TIMED : null;
        count.equal = distinct.size() == 1 && !distinct.contains(null);
        report.counts.add(count);
      }
      return report;
    }
  }

  // Report beans for version 1 of the schema. Gson serializes the fields in this declared order
  // and omits any field whose value is null.

  @SuppressWarnings("unused")
  private static final class Report {
    private final String format = FORMAT;
    private final int version = VERSION;
    private String label;
    private final Settings bootstrap = new Settings();
    private final double regressionThresholdPct = REGRESSION_THRESHOLD_PCT;
    private boolean timingAuthoritative;
    private final List<SetReport> sets = new ArrayList<>();
    private final List<Comparison> comparisons = new ArrayList<>();
    private final List<Unpaired> unpaired = new ArrayList<>();
    private final List<Verdict> verdicts = new ArrayList<>();
    private Severity result;
  }

  @SuppressWarnings("unused")
  private static final class Settings {
    private final int resamples = Bootstrap.RESAMPLES;
    private final int confidencePct = Bootstrap.CONFIDENCE_PCT;
    private final long seed = SEED;
  }

  @SuppressWarnings("unused")
  private static final class SetReport {
    private String role;
    private String dir;
    private String runClass;
    private final List<Replicate> replicates = new ArrayList<>();
    private final List<MetricReport> metrics = new ArrayList<>();
    private final List<CountReport> counts = new ArrayList<>();
  }

  @SuppressWarnings("unused")
  private static final class MetricReport {
    private String operation;
    private String phase;
    private String metric;
    private final List<Double> values = new ArrayList<>();
    private int n;
    private double median;
    private double ciLow;
    private double ciHigh;
  }

  @SuppressWarnings("unused")
  private static final class CountReport {
    private String operation;
    private String phase;
    private String metric;
    private final List<Long> values = new ArrayList<>();
    private boolean exact;
    private boolean equal;
    private String reason;
  }

  @SuppressWarnings("unused")
  private static final class Comparison {
    private String operation;
    private String phase;
    private String metric;
    private double baselineMedian;
    private double candidateMedian;
    private double delta;
    private Double deltaPct;
    private Double ciLowPct;
    private Double ciHighPct;
    private String outcome;
    // Holds the smaller count of values between the two sets; this field is not written to the
    // report.
    private transient int n;
  }

  @SuppressWarnings("unused")
  private static final class Unpaired {
    private String operation;
    private String phase;
    private String metric;
    private String set;
  }

  /** Represents a single verdict row formatted with the fields of the harness verdict stream. */
  @SuppressWarnings("unused")
  private static final class Verdict {
    private final String check;
    private final Severity severity;
    @SerializedName("class")
    private final String evidence;
    private final String source = HealthVerdict.SOURCE;
    private final String detail;

    Verdict(String check, Severity severity, String evidence, String detail) {
      this.check = check;
      this.severity = severity;
      this.evidence = evidence;
      this.detail = detail;
    }
  }
}
