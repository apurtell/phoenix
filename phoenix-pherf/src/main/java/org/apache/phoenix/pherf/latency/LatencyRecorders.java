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
package org.apache.phoenix.pherf.latency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.result.ResultUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the HDR latency recorders for a single Pherf execution run, indexed by operation and
 * phase. Every recorder operates with a fixed configuration: a lowest trackable value of 1 us, a
 * highest trackable value of 600 s, and 3 significant digits of precision. Because merging
 * histograms produces a result equivalent to a single recorder only when their configurations are
 * identical, these parameters are not tunable.
 * <p>
 * Pherf.run() invokes {@link #begin(Properties)} and {@link #finish()} to bound the run lifecycle.
 * When no run is active, {@link #record(String, String, long)} performs no action. Generated
 * outputs are written to the &lt;pherf.default.results.dir&gt;_HDR directory, in the formats
 * defined by {@link LatencyReport}.
 */
public final class LatencyRecorders {
  private static final Logger LOGGER = LoggerFactory.getLogger(LatencyRecorders.class);
  /** Identifies the execution phase for measured steady-state operations. */
  public static final String STEADY = "steady";
  /** Identifies the execution phase for warmup operations executed before measured operations. */
  public static final String WARMUP = "warmup";
  /**
   * The pherf.properties configuration key specifying the interval export period in ms. Interval
   * export is disabled when this property is absent or set to 0.
   */
  public static final String INTERVAL_PROPERTY = "pherf.default.hdr.intervalMs";
  public static final long LOWEST_US = 1;
  public static final long HIGHEST_US = 600_000_000L;
  public static final int DIGITS = 3;
  private static final String RESULTS_DIR_PROPERTY = "pherf.default.results.dir";
  private static final String DIR_SUFFIX = "_HDR";
  private static final Comparator<Entry> ORDER =
    Comparator.comparing((Entry e) -> e.operation).thenComparing(e -> e.phase);

  private static volatile LatencyRecorders current;

  private final File dir;
  private final String label;
  private final long startMs;
  // Indexed by the log tag, which uniquely identifies each operation and phase pair.
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicBoolean clampLogged = new AtomicBoolean();
  private final List<Object> schedules = new CopyOnWriteArrayList<>();
  // Remains null when periodic interval export is disabled.
  private final LatencyReport.Log intervals;
  private final ScheduledExecutorService roller;
  private long rolledMs;
  private boolean closed;

  private LatencyRecorders(File dir, String label, long intervalMs) throws IOException {
    this.dir = dir;
    this.label = label;
    this.startMs = EnvironmentEdgeManager.currentTimeMillis();
    this.rolledMs = startMs;
    Files.createDirectories(dir.toPath());
    // Executing a run with an existing label overwrites the output files from the prior run. The
    // previous JSON report is removed first so that consumers cannot pair stale summary data with
    // the newly generated logs of this run.
    Files.deleteIfExists(new File(dir, LatencyReport.jsonName(label)).toPath());
    Files.deleteIfExists(new File(dir, LatencyReport.hlogName(label)).toPath());
    File intervalsFile = new File(dir, LatencyReport.intervalsName(label));
    if (intervalMs > 0) {
      intervals = new LatencyReport.Log(intervalsFile, startMs);
      roller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "pherf-latency-intervals");
        thread.setDaemon(true);
        return thread;
      });
      roller.scheduleAtFixedRate(this::rollAndLog, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    } else {
      Files.deleteIfExists(intervalsFile.toPath());
      intervals = null;
      roller = null;
    }
  }

  /**
   * Initializes and starts the latency recorders for a new run. The run label is derived from the
   * result file suffix, and the {@link #INTERVAL_PROPERTY} key in the supplied properties enables
   * periodic interval export.
   */
  public static void begin(Properties properties) throws IOException {
    long intervalMs = intervalMs(properties.getProperty(INTERVAL_PROPERTY));
    begin(outputDir(), outputLabel(), intervalMs);
  }

  /**
   * Returns the output directory used when a latency recording run is initialized from the Pherf
   * properties.
   */
  public static File outputDir() {
    // Read the same base directory property used by ResultUtil so that this directory relocates
    // alongside the standard Pherf result files.
    String results = PherfConstants.create().getProperty(RESULTS_DIR_PROPERTY);
    return new File(results + DIR_SUFFIX);
  }

  /**
   * Returns the run label used when a latency recording run is initialized from the Pherf
   * properties, consisting of the result file suffix without its leading "_" character.
   */
  public static String outputLabel() {
    return new ResultUtil().getSuffix().substring(1);
  }

  static synchronized void begin(File dir, String label, long intervalMs) throws IOException {
    if (current != null) {
      throw new IllegalStateException("A latency run is already active");
    }
    current = new LatencyRecorders(dir, label, intervalMs);
  }

  /**
   * Terminates the active run recorders and writes the final JSON report and cumulative run log to
   * disk. If no run is currently active, this method performs no action.
   * @return the completed recording data for the run, or null when no run is active
   */
  public static Recording finish() throws IOException {
    LatencyRecorders run;
    synchronized (LatencyRecorders.class) {
      run = current;
      current = null;
    }
    return run == null ? null : run.close();
  }

  /**
   * Records the latency of a single successful operation. Measurements outside the [0, 600 s] range
   * are clamped to those bounds. Multiple threads may record measurements concurrently without
   * locking.
   */
  public static void record(String operation, String phase, long micros) {
    LatencyRecorders run = current;
    if (run != null) {
      run.add(operation, phase, micros);
    }
  }

  /**
   * Appends a schedule summary entry to the "schedules" array of the JSON report in invocation
   * order. Gson serializes the fields of the supplied entry directly. When no latency recording run
   * is active, this method performs no action.
   */
  public static void addSchedule(Object entry) {
    LatencyRecorders run = current;
    if (run != null) {
      run.schedules.add(entry);
    }
  }

  static LatencyRecorders current() {
    return current;
  }

  static long intervalMs(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    long intervalMs;
    try {
      intervalMs = Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(INTERVAL_PROPERTY + " is not a number: " + value, e);
    }
    if (intervalMs < 0) {
      throw new IllegalArgumentException(INTERVAL_PROPERTY + " is negative: " + value);
    }
    return intervalMs;
  }

  private void add(String operation, String phase, long micros) {
    long value = Math.max(0, Math.min(HIGHEST_US, micros));
    if (value != micros && clampLogged.compareAndSet(false, true)) {
      LOGGER.warn("Latency " + micros + " us of " + operation + " is outside [0, " + HIGHEST_US
        + "] us and is clamped. Later clamps are not logged.");
    }
    entry(operation, phase).recorder.recordValue(value);
  }

  private Entry entry(String operation, String phase) {
    return entries.computeIfAbsent(LatencyReport.tag(operation, phase),
      k -> new Entry(operation, phase, k));
  }

  private List<Entry> sortedEntries() {
    List<Entry> sorted = new ArrayList<>(entries.values());
    sorted.sort(ORDER);
    return sorted;
  }

  /**
   * Drains the samples accumulated by each recorder since the previous roll into its cumulative run
   * total. When interval export is enabled, this method also writes every non-empty interval to the
   * interval log. Because all keys share the same interval time boundaries, the interval sample
   * counts for each key sum to its overall run total.
   */
  synchronized void roll() throws IOException {
    if (closed) {
      return;
    }
    long endMs = EnvironmentEdgeManager.currentTimeMillis();
    for (Entry entry : sortedEntries()) {
      entry.interval = entry.recorder.getIntervalHistogram(entry.interval);
      entry.total.add(entry.interval);
      if (intervals != null && entry.interval.getTotalCount() > 0) {
        entry.interval.setTag(entry.tag);
        intervals.write(rolledMs, endMs, entry.interval);
      }
    }
    rolledMs = endMs;
  }

  private void rollAndLog() {
    try {
      roll();
    } catch (Exception e) {
      LOGGER.error("Cannot write the latency intervals", e);
    }
  }

  /** Returns a defensive copy of the cumulative run total histogram for the specified key. */
  synchronized Histogram total(String operation, String phase) {
    return entry(operation, phase).total.copy();
  }

  private Recording close() throws IOException {
    if (roller != null) {
      roller.shutdownNow();
    }
    List<Histogram> totals = new ArrayList<>();
    synchronized (this) {
      try {
        roll();
      } finally {
        closed = true;
        if (intervals != null) {
          intervals.close();
        }
      }
      for (Entry entry : sortedEntries()) {
        totals.add(entry.total);
      }
    }
    List<Object> entries = new ArrayList<>(schedules);
    LatencyReport.write(dir, label, startMs, rolledMs, intervals != null, totals, entries);
    return new Recording(totals, entries);
  }

  private static Histogram newHistogram() {
    return new Histogram(LOWEST_US, HIGHEST_US, DIGITS);
  }

  /**
   * Encapsulates the completed latency histograms and schedule summaries of a finished run for
   * consumption by the health verdict evaluator.
   */
  public static final class Recording {
    private final List<Histogram> totals;
    private final List<Object> schedules;

    private Recording(List<Histogram> totals, List<Object> schedules) {
      this.totals = totals;
      this.schedules = schedules;
    }

    /**
     * Returns the cumulative run-total histogram for each recorded key, tagged with its log tag and
     * ordered by operation and then by phase.
     */
    public List<Histogram> getTotals() {
      return totals;
    }

    /**
     * Returns the schedule summary entries written to the "schedules" array of the JSON report, in
     * execution order.
     */
    public List<Object> getSchedules() {
      return schedules;
    }
  }

  /** Holds the active recorder and the cumulative run-total histogram for a single key. */
  private static final class Entry {
    private final String operation;
    private final String phase;
    private final String tag;
    private final Recorder recorder = new Recorder(LOWEST_US, HIGHEST_US, DIGITS);
    private final Histogram total = newHistogram();
    private Histogram interval;

    Entry(String operation, String phase, String tag) {
      this.operation = operation;
      this.phase = phase;
      this.tag = tag;
      total.setTag(tag);
    }
  }
}
