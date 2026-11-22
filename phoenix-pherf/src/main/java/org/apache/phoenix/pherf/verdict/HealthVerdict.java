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
package org.apache.phoenix.pherf.verdict;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.workload.WorkloadExecutor;
import org.apache.phoenix.pherf.workload.openloop.OpenLoopState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and persists the health verdict for a single Pherf execution. The evaluation consists of
 * five checks, each implemented as a pure function of the collected run data, and emits a harness
 * verdict stream to &lt;pherf.default.results.dir&gt;_HDR/VERDICT_&lt;label&gt;.tsv. Generating a
 * verdict never alters the process exit code of the run. The specification in
 * phoenix-pherf/VERDICT.md defines the governing contract.
 * <p>
 * During execution, Pherf.run() invokes {@link #begin(VerdictSettings)} once the latency recorders
 * have started, calls {@link #startReads()} when the measured workload begins, and invokes
 * {@link #finish(LatencyRecorders.Recording, WorkloadExecutor)} after the recorders have stopped.
 * All check methods are public so that external callers can evaluate merged run data directly.
 */
public final class HealthVerdict {
  /** Identifies the origin component recorded in the source column of every verdict row. */
  public static final String SOURCE = "pherf";
  public static final String ATTAINMENT = "pherf.health.attainment";
  public static final String SATURATION = "pherf.health.saturation";
  public static final String CLIENT_POOL = "pherf.health.client_pool";
  public static final String TAIL_LATENCY = "pherf.health.tail_latency";
  public static final String READ_SKEW = "pherf.health.read_skew";
  /** Lists the canonical check keys in the order they are written to the output file. */
  public static final List<String> CHECKS = Collections
    .unmodifiableList(Arrays.asList(ATTAINMENT, SATURATION, CLIENT_POOL, TAIL_LATENCY, READ_SKEW));
  /**
   * Defines the harness evidence classifications; enabling the noisy host flag demotes only a FAIL
   * result that belongs to the duration class.
   */
  public static final String COUNT = "count";
  public static final String SHARE = "share";
  public static final String DURATION = "duration";
  /**
   * Specifies the minimum observation window that the read skew check will grade. Because a
   * RegionServer JMX metric can be approximately 15 s stale, grading a shorter window could report
   * artificial skew that did not actually occur.
   */
  public static final long MIN_READ_WINDOW_MS = 60_000;
  private static final Logger LOGGER = LoggerFactory.getLogger(HealthVerdict.class);
  private static final List<String> TIMED_KINDS =
    Arrays.asList("query", "scan", "delete", "response", "operation");

  /**
   * Defines the harness severity lattice in ascending order, where the highest severity observed
   * across a run determines the overall outcome.
   */
  public enum Severity {
    PASS,
    SKIP,
    ADVISORY,
    FAIL,
    ERROR
  }

  private final VerdictSettings settings;
  private final ServerReads reads;
  private final File dir;
  private final String label;

  private HealthVerdict(VerdictSettings settings, File dir, String label) {
    this.settings = settings;
    this.reads = new ServerReads(settings.getServerJmx());
    this.dir = dir;
    this.label = label;
  }

  /**
   * Represents a single verdict row containing the check identifier, evaluated severity, evidence
   * class, and diagnostic detail, with the source fixed to {@link #SOURCE}.
   */
  public static final class Row {
    private final String check;
    private final Severity severity;
    private final String evidence;
    private final String detail;

    public Row(String check, Severity severity, String evidence, String detail) {
      this.check = check;
      this.severity = severity;
      this.evidence = evidence;
      this.detail = detail;
    }

    public String getCheck() {
      return check;
    }

    public Severity getSeverity() {
      return severity;
    }

    public String getEvidence() {
      return evidence;
    }

    public String getDetail() {
      return detail;
    }

    /**
     * Formats this row as a single line of the verdict stream without a trailing line ending.
     */
    @Override
    public String toString() {
      return field(check) + '\t' + severity + '\t' + evidence + '\t' + SOURCE + '\t'
        + field(detail);
    }

    // Ensures that a serialized field never contains tab characters or line endings.
    private static String field(String text) {
      return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
  }

  /**
   * Captures the final execution state of an individual open-loop schedule; elapsedMs is 0 if the
   * schedule never started.
   */
  public static final class Schedule {
    private final String name;
    private final int concurrency;
    private final long durationMs;
    private final long elapsedMs;
    private final long scheduled;
    private final long completed;
    private final long late;
    private final long dropped;
    private final int maxQueueDepth;
    private final int maxInFlight;
    private final long maxDriftUs;

    /**
     * @param concurrency the maximum number of workers, which also serves as the queue capacity
     *                    bound.
     * @param durationMs  the declared execution window duration in milliseconds.
     * @param elapsedMs   the duration from the schedule start to the last worker take of an
     *                    operation, which is at least 1 when started and 0 if the schedule did not
     *                    start; the service time of the final operations is excluded from this
     *                    interval.
     */
    public Schedule(String name, int concurrency, long durationMs, long elapsedMs, long scheduled,
      long completed, long late, long dropped, int maxQueueDepth, int maxInFlight,
      long maxDriftUs) {
      this.name = name;
      this.concurrency = concurrency;
      this.durationMs = durationMs;
      this.elapsedMs = elapsedMs;
      this.scheduled = scheduled;
      this.completed = completed;
      this.late = late;
      this.dropped = dropped;
      this.maxQueueDepth = maxQueueDepth;
      this.maxInFlight = maxInFlight;
      this.maxDriftUs = maxDriftUs;
    }

    /** Constructs a schedule summary from the final state of an open-loop query set. */
    public static Schedule of(OpenLoopState state) {
      long elapsedMs =
        state.getStartMs() > 0 ? Math.max(1, state.getLastTakeMs() - state.getStartMs()) : 0;
      return new Schedule("scenario " + state.getScenario() + " query set " + state.getQuerySet(),
        state.getConcurrency(), state.getDurationMs(), elapsedMs, state.getScheduled(),
        state.getCompleted(), state.getLate(), state.getDropped(), state.getMaxQueueDepth(),
        state.getMaxInFlight(), state.getMaxDriftUs());
    }

    private boolean ran() {
      return scheduled > 0 && elapsedMs > 0;
    }
  }

  /** Aggregates the task execution and contention counts for the Pherf thread pool. */
  public static final class Pool {
    private final int threads;
    private final long tasks;
    private final long waited;
    private final long peak;

    /**
     * @param waited the number of tasks that had to wait for an available thread upon submission.
     * @param peak   the maximum number of submitted tasks that were concurrently unfinished at any
     *               single point in time.
     */
    public Pool(int threads, long tasks, long waited, long peak) {
      this.threads = threads;
      this.tasks = tasks;
      this.waited = waited;
      this.peak = peak;
    }

    public static Pool of(WorkloadExecutor executor) {
      return new Pool(executor.getPoolSize(), executor.getSubmittedTasks(),
        executor.getWaitedTasks(), executor.getPeakTasks());
    }
  }

  /**
   * Encapsulates the per-server read counts accumulated over the run, or records the reason why the
   * measurements are inapplicable or could not be obtained.
   */
  public static final class Reads {
    private final Map<String, Long> deltas;
    private final String skip;
    private final String error;

    private Reads(Map<String, Long> deltas, String skip, String error) {
      this.deltas = deltas;
      this.skip = skip;
      this.error = error;
    }

    /** Creates a read summary containing the read counts for each server, in server order. */
    public static Reads of(Map<String, Long> deltas) {
      return new Reads(Collections.unmodifiableMap(new LinkedHashMap<>(deltas)), null, null);
    }

    /** Records that the read skew check does not apply for the specified reason. */
    public static Reads skip(String reason) {
      return new Reads(null, reason, null);
    }

    /** Records that a read metric source could not be queried for the specified reason. */
    public static Reads error(String reason) {
      return new Reads(null, null, reason);
    }

    /**
     * Computes the per-server read deltas between two snapshots of the same servers separated by
     * windowMs. An observation window shorter than {@link #MIN_READ_WINDOW_MS} or a counter that
     * moved backward produces a SKIP result.
     */
    public static Reads between(Map<String, Long> begin, Map<String, Long> end, long windowMs) {
      if (windowMs < MIN_READ_WINDOW_MS) {
        return skip("the read window of " + windowMs + " ms is shorter than " + MIN_READ_WINDOW_MS
          + " ms: a RegionServer JMX value can be about 15 s old");
      }
      Map<String, Long> deltas = new LinkedHashMap<>();
      for (Map.Entry<String, Long> server : end.entrySet()) {
        long delta = server.getValue() - begin.get(server.getKey());
        if (delta < 0) {
          return skip(
            "the read counter of " + server.getKey() + " went back (a region move or a restart)");
        }
        deltas.put(server.getKey(), delta);
      }
      return of(deltas);
    }
  }

  /**
   * Evaluates throughput attainment across every executed schedule using the formula completed /
   * scheduled x min(1, window / elapsed), where the elapsed interval ends at the final worker take.
   * The check returns FAIL if the lowest attainment falls below minPct, and returns SKIP if no
   * open-loop schedule ran.
   */
  public static Row attainment(List<Schedule> schedules, double minPct) {
    Schedule worst = null;
    double worstPct = 0;
    for (Schedule schedule : schedules) {
      if (!schedule.ran()) {
        continue;
      }
      double pct = 100.0 * schedule.completed / schedule.scheduled
        * Math.min(1.0, (double) schedule.durationMs / schedule.elapsedMs);
      if (worst == null || pct < worstPct) {
        worst = schedule;
        worstPct = pct;
      }
    }
    if (worst == null) {
      return new Row(ATTAINMENT, Severity.SKIP, DURATION, noSchedule());
    }
    boolean fail = worstPct < minPct;
    return new Row(ATTAINMENT, fail ? Severity.FAIL : Severity.PASS, DURATION,
      String.format(Locale.ROOT,
        "lowest attainment %.1f%% (%s: %d of %d completed, taken in %d ms of a %d ms window)"
          + "; %s %s%%",
        worstPct, worst.name, worst.completed, worst.scheduled, worst.elapsedMs, worst.durationMs,
        fail ? "below the minimum" : "minimum", number(minPct)));
  }

  /**
   * Evaluates schedule lag and queue saturation, returning FAIL when the highest peak lag observed
   * at worker take exceeds lagMaxUs. The diagnostic detail reports the peak queue depth and peak
   * in-flight operations for that schedule relative to its concurrency limit, and attributes the
   * cause to a full queue, busy workers, or a late dispatcher. The check returns SKIP when no
   * open-loop schedule ran.
   */
  public static Row saturation(List<Schedule> schedules, long lagMaxUs) {
    Schedule worst = null;
    for (Schedule schedule : schedules) {
      if (schedule.ran() && (worst == null || schedule.maxDriftUs > worst.maxDriftUs)) {
        worst = schedule;
      }
    }
    if (worst == null) {
      return new Row(SATURATION, Severity.SKIP, DURATION, noSchedule());
    }
    boolean fail = worst.maxDriftUs > lagMaxUs;
    boolean full = worst.maxQueueDepth >= worst.concurrency;
    // When lag occurs while queue capacity remains available, fully busy workers indicate a
    // cluster bottleneck whereas an idle worker indicates client-side scheduling delay.
    String cause = full ? ": the workers could not keep up"
      : !fail ? ""
      : worst.maxInFlight >= worst.concurrency ? ": the workers were busy"
      : ": the dispatcher was late";
    return new Row(SATURATION, fail ? Severity.FAIL : Severity.PASS, DURATION,
      "peak lag " + ms(worst.maxDriftUs) + " (" + worst.name + "), " + (fail ? "above" : "within")
        + " " + ms(lagMaxUs) + "; queue peak " + worst.maxQueueDepth + " of " + worst.concurrency
        + ", " + (full ? "at" : "below") + " its bound; in-flight peak " + worst.maxInFlight
        + " of " + worst.concurrency + cause + "; late " + worst.late + ", dropped "
        + worst.dropped);
  }

  /**
   * Evaluates client thread pool exhaustion, returning FAIL if any task had to wait for an
   * available thread in the Pherf pool and therefore prevented the run from reaching its declared
   * concurrency. The check returns SKIP when no task ran.
   */
  public static Row clientPool(Pool pool) {
    if (pool.tasks == 0) {
      return new Row(CLIENT_POOL, Severity.SKIP, COUNT, "no task ran on the Pherf pool");
    }
    if (pool.waited > 0) {
      return new Row(CLIENT_POOL, Severity.FAIL, COUNT,
        pool.waited + " of " + pool.tasks + " tasks waited for a thread of the Pherf pool of "
          + pool.threads + " threads, with a peak demand of " + pool.peak
          + " tasks: the declared concurrency was not reached; set pherf.default.threadpool to at"
          + " least " + pool.peak);
    }
    return new Row(CLIENT_POOL, Severity.PASS, COUNT, "no task waited: tasks " + pool.tasks
      + ", peak demand " + pool.peak + " of " + pool.threads + " threads");
  }

  /**
   * Evaluates tail latency by inspecting the p99 and p99.9 percentiles of each steady-phase run
   * total across the query, scan, delete, response, and operation categories, matching the JSON
   * latency report. The check returns FAIL if any percentile exceeds its configured bound, and
   * returns SKIP if no matching key exists.
   * @param totals the run total histograms, each carrying its log tag of operation and phase, in
   *               report order.
   */
  public static Row tailLatency(List<Histogram> totals, long p99MaxUs, long p999MaxUs) {
    String worst = null;
    double worstRatio = -1;
    long worstP99 = 0;
    long worstP999 = 0;
    int keys = 0;
    int over = 0;
    for (Histogram total : totals) {
      String[] key = LatencyReport.parseTag(total.getTag());
      int colon = key[0].indexOf(':');
      if (
        !LatencyRecorders.STEADY.equals(key[1]) || colon < 0
          || !TIMED_KINDS.contains(key[0].substring(0, colon))
      ) {
        continue;
      }
      keys++;
      long p99 = total.getValueAtPercentile(99);
      long p999 = total.getValueAtPercentile(99.9);
      if (p99 > p99MaxUs || p999 > p999MaxUs) {
        over++;
      }
      double ratio = Math.max((double) p99 / p99MaxUs, (double) p999 / p999MaxUs);
      if (ratio > worstRatio) {
        worst = key[0];
        worstRatio = ratio;
        worstP99 = p99;
        worstP999 = p999;
      }
    }
    if (keys == 0) {
      return new Row(TAIL_LATENCY, Severity.SKIP, DURATION,
        "no steady query, scan, delete, response or operation key in the latency report");
    }
    String bounds = "bounds p99 " + ms(p99MaxUs) + ", p99.9 " + ms(p999MaxUs);
    String highest = worst + " p99 " + ms(worstP99) + ", p99.9 " + ms(worstP999);
    if (over > 0) {
      return new Row(TAIL_LATENCY, Severity.FAIL, DURATION,
        over + " of " + keys + " keys over the " + bounds + "; worst " + highest);
    }
    return new Row(TAIL_LATENCY, Severity.PASS, DURATION,
      "all " + keys + " keys within the " + bounds + "; highest " + highest);
  }

  /**
   * Evaluates server read skew as the ratio of the busiest server's read count to the mean reads
   * per server across all servers, including any server with zero reads. The check returns FAIL
   * when this ratio exceeds max, returns SKIP when no source is configured, fewer than 2 servers
   * are present, no reads occurred, or a counter moved backward, and returns ERROR when a source
   * cannot be read.
   */
  public static Row readSkew(Reads reads, double max) {
    if (reads.error != null) {
      return new Row(READ_SKEW, Severity.ERROR, SHARE,
        "a read count source failed: " + reads.error);
    }
    if (reads.skip != null) {
      return new Row(READ_SKEW, Severity.SKIP, SHARE, reads.skip);
    }
    if (reads.deltas.size() < 2) {
      return new Row(READ_SKEW, Severity.SKIP, SHARE,
        "fewer than 2 servers: " + reads.deltas.size());
    }
    String busiest = null;
    long busiestReads = -1;
    long total = 0;
    for (Map.Entry<String, Long> server : reads.deltas.entrySet()) {
      total += server.getValue();
      if (server.getValue() > busiestReads) {
        busiest = server.getKey();
        busiestReads = server.getValue();
      }
    }
    int servers = reads.deltas.size();
    if (total == 0) {
      return new Row(READ_SKEW, Severity.SKIP, SHARE,
        "no read of a user table on the " + servers + " servers");
    }
    double skew = (double) busiestReads * servers / total;
    boolean fail = skew > max;
    return new Row(READ_SKEW, fail ? Severity.FAIL : Severity.PASS, SHARE, String.format(
      Locale.ROOT, "skew %.2f (busiest / mean), %s %s: %s has %d of %d reads on %d servers", skew,
      fail ? "above the maximum" : "maximum", number(max), busiest, busiestReads, total, servers));
  }

  /**
   * Evaluates all five health checks and returns their verdict rows in {@link #CHECKS} order. When
   * running on a noisy host, any FAIL verdict of the duration evidence class is demoted to
   * ADVISORY, while all other rows remain unchanged.
   */
  public static List<Row> evaluate(List<Schedule> schedules, Pool pool, List<Histogram> totals,
    Reads reads, VerdictSettings settings) {
    List<Row> rows = Arrays.asList(attainment(schedules, settings.getAttainmentMinPct()),
      saturation(schedules, settings.getLagMaxUs()), clientPool(pool),
      tailLatency(totals, settings.getP99MaxUs(), settings.getP999MaxUs()),
      readSkew(reads, settings.getReadSkewMax()));
    if (!settings.isNoisyHost()) {
      return rows;
    }
    List<Row> demoted = new ArrayList<>();
    for (Row row : rows) {
      demoted
        .add(row.severity == Severity.FAIL && DURATION.equals(row.evidence)
          ? new Row(row.check, Severity.ADVISORY, row.evidence,
            "noisy host (" + VerdictSettings.NOISY_HOST + "): FAIL reads as ADVISORY; "
              + row.detail)
          : row);
    }
    return demoted;
  }

  /** Returns the canonical verdict file name for the given run label. */
  public static String fileName(String label) {
    return "VERDICT_" + label + ".tsv";
  }

  /**
   * Writes the evaluated rows to VERDICT_&lt;label&gt;.tsv in the specified directory using UTF-8
   * encoding with LF line endings. The file is published via an atomic move so that concurrent
   * readers never observe a partially written file.
   */
  public static Path write(File dir, String label, List<Row> rows) throws IOException {
    StringBuilder text = new StringBuilder();
    for (Row row : rows) {
      text.append(row).append('\n');
    }
    Files.createDirectories(dir.toPath());
    Path target = new File(dir, fileName(label)).toPath();
    Path temporary = new File(dir, fileName(label) + ".tmp").toPath();
    Files.write(temporary, text.toString().getBytes(StandardCharsets.UTF_8));
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /**
   * Initializes health verdict tracking for a run using the output directory and label of the
   * latency report. Any existing verdict file from an earlier run with the same label is deleted so
   * that stale results cannot be paired with a new latency report; deletion failures are logged
   * without interrupting execution.
   */
  public static HealthVerdict begin(VerdictSettings settings) {
    HealthVerdict verdict =
      new HealthVerdict(settings, LatencyRecorders.outputDir(), LatencyRecorders.outputLabel());
    try {
      Files.deleteIfExists(new File(verdict.dir, fileName(verdict.label)).toPath());
    } catch (IOException e) {
      LOGGER.error("Cannot delete the earlier health verdict", e);
    }
    return verdict;
  }

  /** Captures the initial baseline sample of server read counts when measured work begins. */
  public void startReads() {
    reads.begin();
  }

  /**
   * Captures the final sample of server read counts, evaluates all health checks for the run, and
   * writes the verdict file. Any write failure is recorded in the log only, ensuring that verdict
   * generation never alters the process exit code.
   */
  public void finish(LatencyRecorders.Recording recording, WorkloadExecutor executor) {
    List<Schedule> schedules = new ArrayList<>();
    for (Object entry : recording.getSchedules()) {
      if (entry instanceof OpenLoopState) {
        schedules.add(Schedule.of((OpenLoopState) entry));
      }
    }
    List<Row> rows =
      evaluate(schedules, Pool.of(executor), recording.getTotals(), reads.finish(), settings);
    try {
      write(dir, label, rows);
    } catch (IOException e) {
      LOGGER.error("Cannot write the health verdict", e);
    }
  }

  private static String noSchedule() {
    return "no open loop schedule ran (a closed loop, a -mt run or no arrival)";
  }

  private static String ms(long micros) {
    return BigDecimal.valueOf(micros, 3).stripTrailingZeros().toPlainString() + " ms";
  }

  private static String number(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
