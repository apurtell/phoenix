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
package org.apache.phoenix.vectorharness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.report.FrontierPoint;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.Json;
import org.apache.phoenix.vectorharness.result.RunStore;

/**
 * Helpers of the harness scenario integration tests: a compact profile with short windows built on
 * the shared integration test profile, a driver that runs every phase of a scenario through the
 * command line, and readers of the run results, CSV time series, and table state that the scenarios
 * assert on.
 */
final class HarnessScenarioSupport {

  private HarnessScenarioSupport() {
  }

  /** The output of the phases of one scenario, and the results of its run. */
  static final class Scenario {
    final HarnessItSupport.Invocation load;
    final HarnessItSupport.Invocation build;
    final HarnessItSupport.Invocation truth;
    final HarnessItSupport.Invocation run;
    final RunResult result;

    Scenario(HarnessItSupport.Invocation load, HarnessItSupport.Invocation build,
      HarnessItSupport.Invocation truth, HarnessItSupport.Invocation run, RunResult result) {
      this.load = load;
      this.build = build;
      this.truth = truth;
      this.run = run;
      this.result = result;
    }

    /** Returns the console output of every phase, for failure messages. */
    String out() {
      return load.out + build.out + (truth == null ? "" : truth.out) + run.out;
    }
  }

  /**
   * Returns the shared integration test profile with a short steady state: one second of warm-up
   * and four measured seconds, recall samples every two seconds.
   */
  static Properties profile(String url, Path output, String variant, String suffix, String runId) {
    Properties p = HarnessItSupport.profile(url, output, variant, suffix);
    p.setProperty("run.id", runId);
    p.setProperty("run.profile.name", runId);
    p.setProperty("steady_state.warmup", "1s");
    p.setProperty("steady_state.duration", "4s");
    p.setProperty("recall.sample.interval", "2s");
    return p;
  }

  /** Runs load, build-index, truth, and run with a profile; returns their output and results. */
  static Scenario runAll(Path output, Properties p) throws Exception {
    String runId = p.getProperty("run.id");
    Path profile = HarnessItSupport.write(p, output, runId);
    HarnessItSupport.Invocation load = HarnessItSupport.phase("load", profile);
    HarnessItSupport.Invocation build = HarnessItSupport.phase("build-index", profile);
    HarnessItSupport.Invocation truth = HarnessItSupport.phase("truth", profile);
    HarnessItSupport.Invocation run = HarnessItSupport.phase("run", profile);
    System.out.println(load.out + build.out + truth.out + run.out);
    RunResult r = RunResult.read(new RunStore(output).runDir(runId));
    return new Scenario(load, build, truth, run, r);
  }

  /** Asserts what every healthy run shows: exact validity, freshness, and error-free queries. */
  static void assertValid(Scenario s) {
    RunResult r = s.result;
    assertEquals(s.out(), 1.0, r.getDouble("validity.exact_recall"), 1e-9);
    assertEquals(s.out(), 1.0, r.getDouble("validity.freshness"), 1e-9);
    assertTrue(s.out(), s.run.out.contains("[validity] exact recall 1.000; freshness 1.000"));
    assertFalse("no frontier", r.frontier().isEmpty());
    for (FrontierPoint p : r.frontier()) {
      assertEquals(p.group() + " " + p.sweep() + " errors", "0", p.column("errors"));
    }
    assertTrue(Boolean.TRUE.equals(r.get("restore.complete")));
  }

  /** Asserts that every family of every layout took the plan its variant expects. */
  static void assertPlansMatch(Scenario s, String... layouts) {
    RunResult r = s.result;
    for (String layout : layouts) {
      double matching = number(r.get("build.plan_check." + layout + ".matching"));
      double total = number(r.get("build.plan_check." + layout + ".total"));
      assertTrue(layout + " plan check: " + s.build.out, total > 0);
      assertEquals(layout + " plan check: " + s.build.out, total, matching, 0.0);
    }
    for (FrontierPoint p : r.frontier()) {
      assertFalse(p.group() + " " + p.sweep() + " ran " + p.planClass() + ", expected "
        + p.column("expected_plan"), p.planDiscrepancy());
    }
    assertEquals("plan discrepancies", null, r.get("plan_discrepancies"));
  }

  /** Returns the frontier points of a layout and family whose sweep label starts with a prefix. */
  static List<FrontierPoint> points(RunResult r, String layout, String family, String sweepPrefix) {
    List<FrontierPoint> out = new ArrayList<>();
    for (FrontierPoint p : r.frontier()) {
      if (
        (layout == null || p.layout().equals(layout))
          && (family == null || p.family().equals(family))
          && (sweepPrefix == null || p.sweep().startsWith(sweepPrefix))
      ) {
        out.add(p);
      }
    }
    return out;
  }

  /** Returns the single frontier point of a layout, family, and sweep prefix. */
  static FrontierPoint point(RunResult r, String layout, String family, String sweepPrefix) {
    List<FrontierPoint> l = points(r, layout, family, sweepPrefix);
    assertEquals(layout + " " + family + " " + sweepPrefix + " in " + families(r), 1, l.size());
    return l.get(0);
  }

  /** Returns the distinct families of the frontier, for messages. */
  static List<String> families(RunResult r) {
    List<String> out = new ArrayList<>();
    for (FrontierPoint p : r.frontier()) {
      String f = p.layout() + "/" + p.family();
      if (!out.contains(f)) {
        out.add(f);
      }
    }
    return out;
  }

  /** Asserts that every point of the frontier with a sweep prefix has recall 1. */
  static void assertExhaustive(RunResult r, String sweepPrefix, String... layouts) {
    for (String layout : layouts) {
      List<FrontierPoint> l = points(r, layout, null, sweepPrefix);
      assertFalse(layout + " " + sweepPrefix + " in " + families(r), l.isEmpty());
      for (FrontierPoint p : l) {
        assertEquals(p.group() + " " + p.sweep(), 1.0, p.recall(), 1e-9);
      }
    }
  }

  /** Reads a CSV file of a run. */
  static List<Map<String, String>> csv(RunResult r, String file) throws IOException {
    return CsvTable.read(r.dir().resolve(file));
  }

  /** Returns the rows of a CSV whose column has a value. */
  static List<Map<String, String>> rows(List<Map<String, String>> rows, String column,
    String value) {
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, String> row : rows) {
      if (value.equals(row.get(column))) {
        out.add(row);
      }
    }
    return out;
  }

  /** Returns the events of a run of one kind, from events.csv. */
  static List<Map<String, String>> events(RunResult r, String kind) {
    return rows(r.events(), "kind", kind);
  }

  /** Reads the load state of a table suffix. */
  static Map<String, Object> loadState(Path output, String suffix) throws IOException {
    return Json.readMap(new RunStore(output).loadState(suffix.toUpperCase(Locale.ROOT)));
  }

  /** Returns the only steady-state window of a run. */
  static Map<String, Object> window(RunResult r) {
    List<Map<String, Object>> w = r.steadyWindows();
    assertEquals(1, w.size());
    return w.get(0);
  }

  /** Returns the metric deltas of a steady-state window. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> metricDeltas(Map<String, Object> window) {
    Object d = window.get("metric_deltas");
    assertTrue("metric_deltas " + d, d instanceof Map);
    return (Map<String, Object>) d;
  }

  /** Returns the disruption records of a steady-state window. */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> disruptions(Map<String, Object> window) {
    Object d = window.get("disruptions");
    assertTrue("disruptions " + d, d instanceof List);
    return (List<Map<String, Object>>) d;
  }

  static double number(Object o) {
    if (o instanceof Number) {
      return ((Number) o).doubleValue();
    }
    if (o instanceof String && !((String) o).isEmpty()) {
      try {
        return Double.parseDouble((String) o);
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }
}
