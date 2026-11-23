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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.analysis.Replicate;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Row;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Severity;
import org.apache.phoenix.pherf.verdict.VerdictSettings;

/**
 * Evaluates the fleet verdict for a run as eight rows in the Pherf health verdict format
 * (VERDICT.md), where each row is a pure function of the run data. The five standard Pherf checks
 * are evaluated first, in their defined order:
 * <ul>
 * <li>attainment and saturation are evaluated over the schedules of all slots, so that the worst
 * schedule governs the result;</li>
 * <li>client_pool and read_skew select the worst row across the agents, because the evidence for
 * these checks exists only on each agent host;</li>
 * <li>tail_latency is evaluated over the merged histograms.</li>
 * </ul>
 * These are followed by three fleet checks: the start skew of each gated step against
 * {@link Protocol#MAX_START_SKEW_MS}, the held counts of each completed slot against its final
 * report, and the overall state of the run and its slots. Any FAIL of evidence class duration is
 * demoted to ADVISORY in a validation run or a contaminated run, while no other row changes.
 */
final class FleetVerdict {
  static final String START_SKEW = "pherf.fleet.start_skew";
  static final String TELEMETRY = "pherf.fleet.telemetry";
  static final String AGENTS = "pherf.fleet.agents";
  private static final String VALIDATION_PREFIX = "validation run: FAIL reads as ADVISORY; ";
  private static final String NOISY_PREFIX =
    "noisy host (" + VerdictSettings.NOISY_HOST + "): FAIL reads as ADVISORY; ";

  /**
   * Captures the verdict inputs for a single slot of the run, comprising its step index, its view,
   * its final report (or null), and the total mismatch description that failed it (or null).
   */
  static final class Slot {
    final int step;
    final RunView.SlotView view;
    final FinalReport report;
    final String lostTelemetry;

    Slot(int step, RunView.SlotView view, FinalReport report, String lostTelemetry) {
      this.step = step;
      this.view = view;
      this.report = report;
      this.lostTelemetry = lostTelemetry;
    }

    Slot(int step, RunView.SlotView view, FinalReport report) {
      this(step, view, report, null);
    }

    String name() {
      return "step " + step + " slot " + view.getSlot() + " (agent " + view.getAgent() + ")";
    }
  }

  private FleetVerdict() {
  }

  /**
   * Computes the eight verdict rows for the run and applies any severity demotions required by the
   * run class or host contamination status.
   */
  static List<Row> evaluate(RunSpec spec, RunView run, List<Slot> slots, List<Histogram> totals) {
    VerdictSettings settings = spec.getVerdictSettings();
    List<HealthVerdict.Schedule> schedules = schedules(slots);
    List<Row> rows =
      Arrays.asList(HealthVerdict.attainment(schedules, settings.getAttainmentMinPct()),
        HealthVerdict.saturation(schedules, settings.getLagMaxUs()),
        worst(HealthVerdict.CLIENT_POOL, HealthVerdict.COUNT, slots),
        HealthVerdict.tailLatency(totals, settings.getP99MaxUs(), settings.getP999MaxUs()),
        worst(HealthVerdict.READ_SKEW, HealthVerdict.SHARE, slots), startSkew(run.getSteps()),
        telemetry(slots), agents(run.getState(), run.getReason(), slots));
    return demote(rows, Replicate.VALIDATION.equals(spec.getRunClass()), settings.isNoisyHost());
  }

  /**
   * Reconstructs the schedule metrics for every schedule entry across all slots, matching the final
   * state produced by Schedule.of for an open-loop query set and including the slot identity in
   * each schedule name.
   */
  static List<HealthVerdict.Schedule> schedules(List<Slot> slots) {
    List<HealthVerdict.Schedule> schedules = new ArrayList<>();
    for (Slot slot : slots) {
      if (slot.report == null) {
        continue;
      }
      for (JsonObject entry : slot.report.getSchedules()) {
        long startMs = number(entry, "startMs");
        long elapsedMs = startMs > 0 ? Math.max(1, number(entry, "lastTakeMs") - startMs) : 0;
        String name = slot.name() + " scenario "
          + (entry.has("scenario") ? entry.get("scenario").getAsString() : "") + " query set "
          + number(entry, "querySet");
        schedules.add(new HealthVerdict.Schedule(name, (int) number(entry, "concurrency"),
          number(entry, "durationMs"), elapsedMs, number(entry, "scheduled"),
          number(entry, "completed"), number(entry, "late"), number(entry, "dropped"),
          (int) number(entry, "maxQueueDepth"), (int) number(entry, "maxInFlight"),
          number(entry, "maxDriftUs")));
      }
    }
    return schedules;
  }

  private static long number(JsonObject entry, String field) {
    JsonElement value = entry.get(field);
    return value == null || !value.isJsonPrimitive() ? 0 : value.getAsLong();
  }

  /**
   * Selects the agent verdict row with the highest severity for the specified check and prepends
   * the originating slot name to its detail message. Ties are broken in favor of the lowest step
   * and slot, and a SKIP row is returned when no slot reported the check.
   */
  static Row worst(String check, String evidence, List<Slot> slots) {
    Row worst = null;
    Slot of = null;
    for (Slot slot : slots) {
      if (slot.report == null) {
        continue;
      }
      for (String line : slot.report.getVerdict()) {
        Row row = parse(line);
        if (
          row != null && row.getCheck().equals(check)
            && (worst == null || row.getSeverity().compareTo(worst.getSeverity()) > 0)
        ) {
          worst = row;
          of = slot;
        }
      }
    }
    if (worst == null) {
      return new Row(check, Severity.SKIP, evidence, "no slot reported a verdict");
    }
    return new Row(check, worst.getSeverity(), worst.getEvidence(),
      of.name() + ": " + worst.getDetail());
  }

  /**
   * Parses a single tab-delimited line from a Pherf verdict file, or returns null if the line does
   * not represent a valid verdict row.
   */
  private static Row parse(String line) {
    String[] fields = line.split("\t", -1);
    if (fields.length != 5 || !HealthVerdict.SOURCE.equals(fields[3])) {
      return null;
    }
    try {
      return new Row(fields[0], Severity.valueOf(fields[1]), fields[2], fields[4]);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Evaluates the start skew check across all steps. Returns FAIL when the skew bound of any step
   * exceeds {@link Protocol#MAX_START_SKEW_MS}. Otherwise, returns SKIP when any gated step has no
   * skew bound (due to a slot with no clock sample or no dispatch, leaving the bound of that step
   * unproven), or when no step has a start gate.
   */
  static Row startSkew(List<RunView.StepView> steps) {
    RunView.StepView highest = null;
    int over = 0;
    int skewed = 0;
    List<String> unproven = new ArrayList<>();
    for (RunView.StepView step : steps) {
      Long skewMs = step.getSkewMs();
      if (skewMs == null) {
        if (step.getStartAtMs() > 0) {
          unproven.add("step " + step.getIndex());
        }
        continue;
      }
      skewed++;
      if (skewMs > Protocol.MAX_START_SKEW_MS) {
        over++;
      }
      if (highest == null || skewMs > highest.getSkewMs()) {
        highest = step;
      }
    }
    String bound = Protocol.MAX_START_SKEW_MS + " ms bound";
    if (over > 0) {
      return new Row(START_SKEW, Severity.FAIL, HealthVerdict.DURATION,
        (over > 1 ? over + " of " + skewed + " steps above the " + bound + "; highest " : "")
          + "step " + highest.getIndex() + " start skew at most " + highest.getSkewMs() + " ms"
          + (over > 1 ? "" : ", above the " + bound));
    }
    if (!unproven.isEmpty()) {
      return new Row(START_SKEW, Severity.SKIP, HealthVerdict.DURATION,
        String.join(", ", unproven) + ": start skew not proven, a slot with no clock sample or no"
          + " dispatch"
          + (highest == null
            ? ""
            : "; highest proven start skew at most " + highest.getSkewMs() + " ms (step "
              + highest.getIndex() + ")"));
    }
    if (highest == null) {
      return new Row(START_SKEW, Severity.SKIP, HealthVerdict.DURATION, "no step has a start gate");
    }
    return new Row(START_SKEW, Severity.PASS, HealthVerdict.DURATION, "highest start skew at most "
      + highest.getSkewMs() + " ms (step " + highest.getIndex() + "), within the " + bound);
  }

  /**
   * Verifies telemetry integrity across all slots by confirming that no slot failed due to a frame
   * total mismatch and that, for each DONE slot, the count held by the controller for every key
   * equals the count in the agent's final report. Returns FAIL naming the first mismatch or count
   * difference, or SKIP when no slot is DONE.
   */
  static Row telemetry(List<Slot> slots) {
    for (Slot slot : slots) {
      if (slot.lostTelemetry != null) {
        return new Row(TELEMETRY, Severity.FAIL, HealthVerdict.COUNT,
          slot.name() + ": " + slot.lostTelemetry);
      }
    }
    int done = 0;
    for (Slot slot : slots) {
      if (slot.view.getState() != RunView.SlotState.DONE || slot.report == null) {
        continue;
      }
      done++;
      Map<String, Long> held = slot.view.getCounters();
      Map<String, Long> reported = slot.report.getCounts();
      Set<String> keys = new LinkedHashSet<>(held.keySet());
      keys.addAll(reported.keySet());
      for (String key : keys) {
        long h = held.getOrDefault(key, 0L);
        long r = reported.getOrDefault(key, 0L);
        if (h != r) {
          return new Row(TELEMETRY, Severity.FAIL, HealthVerdict.COUNT,
            slot.name() + ": the controller holds " + h + " samples of " + key
              + ", and the agent reported " + r);
        }
      }
    }
    if (done == 0) {
      return new Row(TELEMETRY, Severity.SKIP, HealthVerdict.COUNT, "no slot completed");
    }
    return new Row(TELEMETRY, Severity.PASS, HealthVerdict.COUNT, "the held counts of " + done
      + " completed slots equal the counts that their agents reported");
  }

  /**
   * Evaluates overall agent and run completion. Returns PASS when the run is COMPLETE and every
   * slot is DONE; otherwise returns FAIL detailing the run state, the reason, and each failed slot
   * (for example, an agent that is misconfigured).
   */
  static Row agents(RunView.State state, String reason, List<Slot> slots) {
    List<String> failed = new ArrayList<>();
    boolean allDone = true;
    for (Slot slot : slots) {
      allDone &= slot.view.getState() == RunView.SlotState.DONE;
      if (slot.view.getState() == RunView.SlotState.FAILED) {
        failed.add(slot.name() + ": " + slot.view.getError());
      }
    }
    if (state == RunView.State.COMPLETE && allDone) {
      return new Row(AGENTS, Severity.PASS, HealthVerdict.COUNT,
        "run COMPLETE with " + slots.size() + " slots done");
    }
    return new Row(AGENTS, Severity.FAIL, HealthVerdict.COUNT,
      "run " + state + (reason == null ? "" : ": " + reason) + "; failed slots: "
        + (failed.isEmpty() ? "none" : String.join("; ", failed)));
  }

  /**
   * Demotes any FAIL row of evidence class duration to ADVISORY in a validation run or a
   * contaminated run, prepending the cause to its detail message. All other rows remain the same
   * objects.
   */
  static List<Row> demote(List<Row> rows, boolean validation, boolean contaminated) {
    List<Row> demoted = new ArrayList<>();
    for (Row row : rows) {
      boolean duration =
        row.getSeverity() == Severity.FAIL && HealthVerdict.DURATION.equals(row.getEvidence());
      demoted.add(duration && (validation || contaminated)
        ? new Row(row.getCheck(), Severity.ADVISORY, row.getEvidence(),
          (validation ? VALIDATION_PREFIX : NOISY_PREFIX) + row.getDetail())
        : row);
    }
    return demoted;
  }

  /** Returns the overall result of the run, defined as the highest severity across all rows. */
  static Severity result(List<Row> rows) {
    Severity result = Severity.PASS;
    for (Row row : rows) {
      if (row.getSeverity().compareTo(result) > 0) {
        result = row.getSeverity();
      }
    }
    return result;
  }
}
