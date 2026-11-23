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
package org.apache.phoenix.pherf.distributed.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.apache.phoenix.pherf.analysis.Replicate;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.verdict.HealthVerdict;

/**
 * Assembles the result bundle for a single run under &lt;PHERF_RUN_DIR&gt;/&lt;runId&gt;/,
 * following the run-bundle directory layout consumed by offline analysis:
 * <ul>
 * <li>{@link #MANIFEST}: records the run id, class, result, state and reason, seed and times, and
 * contamination tags. Because this file is written last, any run directory lacking a manifest
 * represents an interrupted run.</li>
 * <li>{@link #RESULTS}/LATENCY_run.json: contains the fleet report, formatted as a version 1 P4
 * JSON report augmented with a fleet member.</li>
 * <li>{@link #RESULTS}/LATENCY_run.hlog: stores the merged run log, containing one aggregate
 * histogram per key.</li>
 * <li>{@link #RESULTS}/VERDICT_run.tsv: captures the evaluated fleet verdict.</li>
 * <li>{@link #RESULTS}/slots/&lt;step&gt;-&lt;slot&gt;.hlog: preserves the raw interval log for
 * each slot, with one line per held frame recorded against the agent clock.</li>
 * </ul>
 * While a run is active, the bundle maintains a single fleet histogram in memory for each key
 * (operation and phase) using the pinned configuration, while streaming every held frame directly
 * to the raw log for its slot. This streaming design ensures that the memory footprint of the
 * bundle does not grow with the duration of the run. All timestamps reported in the fleet report
 * are normalized to the controller clock by taking the agent time minus the clock offset of the
 * slot.
 */
public final class RunBundle {
  public static final String MANIFEST = "manifest.json";
  /**
   * Defines the subdirectory within a run bundle where Pherf results are written for offline
   * analysis to read.
   */
  public static final String RESULTS = new File(Replicate.BUNDLE_JSON).getParent();
  /** Identifies the contamination tag applied to a run when pherf.verdict.noisyHost=true. */
  static final String NOISY_HOST = "noisy-host";
  private static final String LABEL = "run";
  private static final String JSON = new File(Replicate.BUNDLE_JSON).getName();
  private static final String HLOG = "LATENCY_" + LABEL + ".hlog";
  private static final String SLOTS = "slots";
  /** Specifies the initial header comment line written to a Pherf latency log (LATENCY.md). */
  private static final String COMMENT =
    "[Pherf latency log " + LatencyReport.VERSION + ": microseconds, lowest "
      + LatencyRecorders.LOWEST_US + ", highest " + LatencyRecorders.HIGHEST_US + ", "
      + LatencyRecorders.DIGITS + " significant digits, Tag=OPERATION|PHASE]";
  private static final Gson GSON =
    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  /**
   * Defines the canonical ordering of keys in a Pherf report, sorting first by operation and then
   * by phase.
   */
  private static final Comparator<Histogram> ORDER =
    Comparator.comparing((Histogram h) -> LatencyReport.parseTag(h.getTag())[0])
      .thenComparing(h -> LatencyReport.parseTag(h.getTag())[1]);

  private final File dir;
  private final File results;
  /** Holds the aggregated fleet histogram for each key, indexed by its log tag. */
  private final Map<String, Histogram> totals = new HashMap<>();
  /**
   * Records the first I/O failure encountered while writing a raw log, which subsequently causes
   * writing the bundle to fail.
   */
  private IOException failure;

  RunBundle(File dir) {
    this.dir = dir;
    this.results = new File(dir, RESULTS);
  }

  /**
   * Merges a held interval frame into the aggregate fleet histogram for its key and appends the
   * frame to the corresponding slot log.
   */
  void add(RunRecord.SlotRecord slot, IntervalFrame frame, Histogram histogram) {
    merge(frame.getOperation(), frame.getPhase(), histogram);
    if (failure != null) {
      return;
    }
    try {
      if (slot.log == null) {
        slot.log = new SlotLog(slot, frame.getStartMs());
      }
      slot.log.write(frame, histogram);
    } catch (IOException e) {
      failure = e;
    }
  }

  /**
   * Accumulates a histogram recorded with the pinned configuration into the aggregate fleet
   * histogram for its key.
   */
  void merge(String operation, String phase, Histogram histogram) {
    String tag = LatencyReport.tag(operation, phase);
    totals.computeIfAbsent(tag, k -> {
      Histogram total = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      total.setTag(k);
      return total;
    }).add(histogram);
  }

  /**
   * Returns the aggregated fleet histograms, each carrying its tag, sorted by operation and then by
   * phase.
   */
  List<Histogram> totals() {
    List<Histogram> sorted = new ArrayList<>(totals.values());
    sorted.sort(ORDER);
    return sorted;
  }

  /** Closes the raw interval logs for all slots across every step of a run. */
  void close(RunRecord run) {
    for (RunRecord.StepRecord step : run.steps) {
      close(step);
    }
  }

  /**
   * Closes the raw interval logs for all slots belonging to a step, since a step that has ended
   * receives no further frames.
   */
  void close(RunRecord.StepRecord step) {
    for (RunRecord.SlotRecord slot : step.slots) {
      if (slot.log != null) {
        try {
          slot.log.close();
        } catch (IOException e) {
          if (failure == null) {
            failure = e;
          }
        }
        slot.log = null;
      }
    }
  }

  /**
   * Writes the complete result bundle for a finished run, producing the run log, the fleet report,
   * the fleet verdict, and finally the manifest last. Every JSON file and the verdict file are
   * committed into place using an atomic move.
   */
  void write(RunRecord run, long endedMs) throws IOException {
    close(run);
    if (failure != null) {
      throw failure;
    }
    Files.createDirectories(results.toPath());
    RunView view = run.view();
    List<FleetVerdict.Slot> slots = new ArrayList<>();
    List<JsonObject> schedules = new ArrayList<>();
    long startMs = Long.MAX_VALUE;
    long endMs = Long.MIN_VALUE;
    JsonArray steps = new JsonArray();
    for (RunRecord.StepRecord step : run.steps) {
      long stepStartMs = Long.MAX_VALUE;
      long stepEndMs = Long.MIN_VALUE;
      long firstDispatchMs = Long.MAX_VALUE;
      for (RunRecord.SlotRecord slot : step.slots) {
        if (slot.dispatchMs != null) {
          firstDispatchMs = Math.min(firstDispatchMs, slot.dispatchMs);
        }
      }
      JsonArray slotMembers = new JsonArray();
      for (RunRecord.SlotRecord slot : step.slots) {
        RunView.SlotView slotView =
          view.getSteps().get(step.index).getSlots().get(slot.assignment.getIdentity().getSlot());
        slots.add(new FleetVerdict.Slot(step.index, slotView, slot.report, slot.lostTelemetry));
        long[] window = window(slot);
        if (window != null) {
          stepStartMs = Math.min(stepStartMs, window[0]);
          stepEndMs = Math.max(stepEndMs, window[1]);
        }
        if (slot.report != null) {
          for (JsonObject entry : slot.report.getSchedules()) {
            schedules.add(schedule(slot, entry));
          }
        }
        slotMembers.add(slotMember(slot, firstDispatchMs));
      }
      JsonObject stepMember = new JsonObject();
      stepMember.addProperty("step", step.index);
      stepMember.addProperty("state", step.state.name());
      stepMember.addProperty("startAtMs", step.startAtMs);
      stepMember.addProperty("skewMs", step.skewMs);
      if (stepStartMs <= stepEndMs) {
        stepMember.addProperty("startMs", stepStartMs);
        stepMember.addProperty("endMs", stepEndMs);
        startMs = Math.min(startMs, stepStartMs);
        endMs = Math.max(endMs, stepEndMs);
      }
      stepMember.add("slots", slotMembers);
      steps.add(stepMember);
    }
    if (startMs > endMs) {
      // When no slot recorded any activity, the window is empty so offline analysis reports no
      // throughput.
      startMs = endedMs;
      endMs = endedMs;
    }
    boolean contaminated = run.spec.getVerdictSettings().isNoisyHost();
    JsonObject fleet = new JsonObject();
    fleet.addProperty("runId", run.runId);
    fleet.addProperty("seed", run.spec.getSeed());
    fleet.addProperty("runClass", run.spec.getRunClass());
    fleet.addProperty("contaminated", contaminated);
    fleet.addProperty("state", run.state.name());
    fleet.addProperty("reason", run.reason);
    fleet.add("steps", steps);
    List<Histogram> sorted = totals();
    writeLatency(results, startMs, endMs, sorted, schedules, fleet);
    List<HealthVerdict.Row> rows = FleetVerdict.evaluate(run.spec, view, slots, sorted);
    HealthVerdict.write(results, LABEL, rows);

    JsonObject runMember = new JsonObject();
    runMember.addProperty("id", run.runId);
    runMember.addProperty("class", run.spec.getRunClass());
    runMember.addProperty("result", FleetVerdict.result(rows).name());
    runMember.addProperty("state", run.state.name());
    runMember.addProperty("reason", run.reason);
    runMember.addProperty("seed", run.spec.getSeed());
    runMember.addProperty("started_ms", run.startedMs);
    runMember.addProperty("ended_ms", endedMs);
    JsonArray tags = new JsonArray();
    if (contaminated) {
      tags.add(NOISY_HOST);
    }
    JsonObject quietHost = new JsonObject();
    quietHost.add("tags", tags);
    JsonObject manifest = new JsonObject();
    manifest.addProperty("bundle_format_version", 1);
    manifest.add("run", runMember);
    manifest.add("quiet_host", quietHost);
    writeAtomically(new File(dir, MANIFEST), GSON.toJson(manifest) + "\n");
  }

  /**
   * Computes the recording window for a slot converted to the controller clock, derived from its
   * final report when present or otherwise from its held frames. Returns null when the slot has
   * neither.
   */
  private static long[] window(RunRecord.SlotRecord slot) {
    if (slot.report != null) {
      return new long[] { slot.controllerMs(slot.report.getStartMs()),
        slot.controllerMs(slot.report.getEndMs()) };
    }
    if (slot.firstStartMs != null) {
      return new long[] { slot.controllerMs(slot.firstStartMs), slot.controllerMs(slot.lastEndMs) };
    }
    return null;
  }

  /**
   * Produces a schedule entry for a slot with its timestamps converted to the controller clock and
   * annotated with its slot details.
   */
  private static JsonObject schedule(RunRecord.SlotRecord slot, JsonObject entry) {
    JsonObject schedule = entry.deepCopy();
    for (String time : new String[] { "startMs", "endMs", "lastTakeMs" }) {
      JsonElement value = schedule.get(time);
      // A timestamp value of 0 indicates that no such event occurred, matching the Pherf report
      // convention.
      if (value != null && value.isJsonPrimitive() && value.getAsLong() > 0) {
        schedule.addProperty(time, slot.controllerMs(value.getAsLong()));
      }
    }
    schedule.addProperty("step", slot.step.index);
    schedule.addProperty("slot", slot.assignment.getIdentity().getSlot());
    schedule.addProperty("agent", slot.agent);
    return schedule;
  }

  /**
   * Assembles the fleet metadata for a single slot, covering its identities, share, clock, start
   * timing, and held telemetry.
   */
  private static JsonObject slotMember(RunRecord.SlotRecord slot, long firstDispatchMs) {
    JsonObject member = new JsonObject();
    member.addProperty("slot", slot.assignment.getIdentity().getSlot());
    member.addProperty("agent", slot.agent);
    member.addProperty("incarnation", slot.incarnation);
    member.addProperty("seed", slot.assignment.getIdentity().getSeed());
    member.addProperty("offset", slot.assignment.getIdentity().getOffset());
    member.addProperty("prefix", slot.assignment.getIdentity().getPrefix());
    member.add("share", GSON.toJsonTree(slot.assignment.getShare()));
    member.addProperty("state", slot.state.name());
    member.addProperty("error", slot.error);
    member.addProperty("offsetMs", slot.offsetMs);
    member.addProperty("delayMs", slot.delayMs);
    member.addProperty("startAtMs", slot.startAtMs);
    member.addProperty("dispatchMs", slot.dispatchMs);
    member.addProperty("startLagMs",
      slot.dispatchMs == null ? null : slot.dispatchMs - firstDispatchMs);
    member.addProperty("frames", slot.heldSeq);
    member.addProperty("samples", slot.samples);
    JsonObject counts = new JsonObject();
    for (Map.Entry<String, Long> count : slot.counts.entrySet()) {
      counts.addProperty(count.getKey(), count.getValue());
    }
    member.add("counts", counts);
    if (slot.firstStartMs != null) {
      member.addProperty("intervals", SLOTS + "/" + logName(slot));
    }
    return member;
  }

  private static String logName(RunRecord.SlotRecord slot) {
    return slot.step.index + "-" + slot.assignment.getIdentity().getSlot() + ".hlog";
  }

  /**
   * Writes the merged run log and a version 1 JSON report (LATENCY.md) to the target directory,
   * following the Pherf latency report structure: the run label, the pinned recorder metadata, no
   * interval log, the histogram summary entries for the totals, the schedule entries when any are
   * present, and the fleet member. The JSON report is written last using an atomic move.
   * @param totals the aggregate histograms, each carrying its tag and sorted in report order.
   */
  public static void writeLatency(File dir, long startMs, long endMs, List<Histogram> totals,
    List<JsonObject> schedules, JsonObject fleet) throws IOException {
    Log log = new Log(new File(dir, HLOG), startMs, null);
    try {
      for (Histogram total : totals) {
        log.write(startMs, endMs, total);
      }
    } finally {
      log.close();
    }
    Report report = new Report();
    report.startMs = startMs;
    report.endMs = endMs;
    for (Histogram total : totals) {
      report.histograms.add(new Summary(total));
    }
    report.schedules = schedules.isEmpty() ? null : schedules;
    report.fleet = fleet;
    writeAtomically(new File(dir, JSON), GSON.toJson(report) + "\n");
  }

  private static void writeAtomically(File target, String text) throws IOException {
    Path temporary = new File(target.getPath() + ".tmp").toPath();
    Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
    Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Manages the raw interval log for a single slot, using the start of its first frame as the base
   * time.
   */
  final class SlotLog {
    private final Log log;

    SlotLog(RunRecord.SlotRecord slot, long baseMs) throws IOException {
      File file = new File(new File(results, SLOTS), logName(slot));
      Files.createDirectories(file.getParentFile().toPath());
      this.log = new Log(file, baseMs,
        "pherf-distributed run " + slot.step.run.runId + " step " + slot.step.index + " slot "
          + slot.assignment.getIdentity().getSlot() + " agent " + slot.agent + ", agent clock");
    }

    void write(IntervalFrame frame, Histogram histogram) throws IOException {
      histogram.setTag(LatencyReport.tag(frame.getOperation(), frame.getPhase()));
      log.write(frame.getStartMs(), frame.getEndMs(), histogram);
    }

    void close() throws IOException {
      log.close();
    }
  }

  /**
   * Encapsulates a histogram log recorded in microseconds with the standard Pherf header, matching
   * the format of a Pherf latency log.
   */
  private static final class Log {
    private final File file;
    private final PrintStream out;
    private final HistogramLogWriter writer;
    private final long baseMs;

    Log(File file, long baseMs, String comment) throws IOException {
      this.file = file;
      this.baseMs = baseMs;
      this.out = new PrintStream(new BufferedOutputStream(new FileOutputStream(file)), false,
        StandardCharsets.UTF_8.name());
      this.writer = new HistogramLogWriter(out);
      writer.outputComment(COMMENT);
      if (comment != null) {
        writer.outputComment("[" + comment + "]");
      }
      writer.outputLogFormatVersion();
      writer.outputStartTime(baseMs);
      writer.outputBaseTime(baseMs);
      writer.outputLegend();
      check();
    }

    /** Writes a single interval histogram covering the time range [startMs, endMs]. */
    void write(long startMs, long endMs, Histogram histogram) throws IOException {
      histogram.setStartTimeStamp(startMs);
      histogram.setEndTimeStamp(endMs);
      writer.outputIntervalHistogram((startMs - baseMs) / 1000.0, (endMs - baseMs) / 1000.0,
        histogram, 1.0);
      check();
    }

    void close() throws IOException {
      writer.close();
      check();
    }

    private void check() throws IOException {
      if (out.checkError()) {
        throw new IOException("cannot write " + file);
      }
    }
  }

  // Mirrors the version 1 JSON report structure in the exact field order of the Pherf report,
  // relying on Gson to omit any null field. Maintaining this local copy of the private
  // LatencyReport model leaves phoenix-pherf unchanged (RQ-152), while FleetReportParityTest
  // verifies that both representations remain identical.
  @SuppressWarnings("unused")
  private static final class Report {
    private final String format = LatencyReport.FORMAT;
    private final int version = LatencyReport.VERSION;
    private final String label = LABEL;
    private long startMs;
    private long endMs;
    private final Recorder recorder = new Recorder();
    private final String hlog = HLOG;
    private final List<Summary> histograms = new ArrayList<>();
    private List<JsonObject> schedules;
    private JsonObject fleet;
  }

  @SuppressWarnings("unused")
  private static final class Recorder {
    private final long lowestUs = LatencyRecorders.LOWEST_US;
    private final long highestUs = LatencyRecorders.HIGHEST_US;
    private final int significantDigits = LatencyRecorders.DIGITS;
  }

  @SuppressWarnings("unused")
  private static final class Summary {
    private final String operation;
    private final String phase;
    private final long count;
    private final long minUs;
    private final long maxUs;
    private final double meanUs;
    private final long p50Us;
    private final long p90Us;
    private final long p99Us;
    private final long p999Us;

    Summary(Histogram histogram) {
      String[] key = LatencyReport.parseTag(histogram.getTag());
      operation = key[0];
      phase = key[1];
      count = histogram.getTotalCount();
      minUs = histogram.getMinValue();
      maxUs = histogram.getMaxValue();
      meanUs = histogram.getMean();
      p50Us = histogram.getValueAtPercentile(50);
      p90Us = histogram.getValueAtPercentile(90);
      p99Us = histogram.getValueAtPercentile(99);
      p999Us = histogram.getValueAtPercentile(99.9);
    }
  }
}
