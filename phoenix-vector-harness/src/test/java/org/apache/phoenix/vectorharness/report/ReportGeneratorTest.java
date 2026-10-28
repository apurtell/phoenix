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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.result.Json;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReportGeneratorTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  /** Writes a run manifest to disk and reloads it as a {@link RunResult}. */
  private RunResult run(String id, String deterministic, Long rowsIndexed, boolean streamed,
    String... extraSettings) throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", id);
    m.put("run", run);
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("run.id", id);
    settings.put("run.variant", id);
    settings.put("run.deterministic", deterministic);
    settings.put("mutation.threads", "4");
    for (int i = 0; i < extraSettings.length; i += 2) {
      settings.put(extraSettings[i], extraSettings[i + 1]);
    }
    m.put("settings", settings);
    Map<String, Object> corpus = new LinkedHashMap<>();
    corpus.put("fingerprint", "fp");
    corpus.put("panel_digest", "panel");
    m.put("corpus", corpus);
    if (rowsIndexed != null) {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("rows_indexed", rowsIndexed);
      Map<String, Object> build = new LinkedHashMap<>();
      build.put("build", b);
      m.put("build", build);
    }
    if (streamed) {
      Map<String, Object> stream = new LinkedHashMap<>();
      stream.put("rows", 1600000L);
      m.put("stream", stream);
    }
    Path dir = tmp.getRoot().toPath().resolve(id);
    Json.write(dir.resolve("manifest.json"), m);
    return RunResult.read(dir);
  }

  @Test
  public void testIndexRegionSettingsAreNotExempt() {
    assertTrue(ReportGenerator.exempt("ivf.lists"));
    assertFalse(ReportGenerator.exempt("ivf.regions"));
    assertFalse(ReportGenerator.exempt("ivf.salt_buckets"));
  }

  @Test
  public void testDeterministicModeIsAnExecutionParameter() throws IOException {
    assertFalse(ReportGenerator.exempt("run.deterministic"));
    for (String key : new String[] { "run.id", "run.variant", "run.output.dir", "run.profile.name",
      "run.repetitions" }) {
      assertTrue(key, ReportGenerator.exempt(key));
    }
    ReportGenerator gen = new ReportGenerator();
    List<RunResult> runs =
      Arrays.asList(run("ivf", "true", 400000L, true), run("hnsw", "false", 400000L, true));
    List<String> problems = gen.comparability(runs);
    assertEquals(problems.toString(), 1, problems.size());
    assertTrue(problems.get(0), problems.get(0).contains("run.deterministic=false"));
    assertTrue(gen
      .comparability(
        Arrays.asList(run("a", "false", 400000L, true), run("b", "false", 400000L, true)))
      .isEmpty());
  }

  @Test
  public void testCacheFrequencyComparesInCanonicalForm() throws IOException {
    String key = "table.update_cache_frequency";
    assertFalse(ReportGenerator.exempt(key));
    assertEquals("0", ReportGenerator.canonical(key, ""));
    assertEquals("0", ReportGenerator.canonical(key, " always "));
    assertEquals("0", ReportGenerator.canonical(key, "0"));
    assertEquals("60000", ReportGenerator.canonical(key, "60_000"));
    assertEquals("NEVER", ReportGenerator.canonical(key, " NEVER "));
    assertEquals("ALWAYS", ReportGenerator.canonical("table.other", " ALWAYS "));
    ReportGenerator gen = new ReportGenerator();
    // A run from before the setting lacks the key. It used ALWAYS.
    assertTrue(gen.comparability(Arrays.asList(run("old", "false", 1L, false),
      run("always", "false", 1L, false, key, "ALWAYS"))).isEmpty());
    List<String> problems = gen.comparability(Arrays.asList(run("older", "false", 1L, false),
      run("cached", "false", 1L, false, key, "60000")));
    assertEquals(problems.toString(), 1, problems.size());
    assertTrue(problems.get(0), problems.get(0).contains(key + "=60000"));
  }

  @Test
  public void testStatisticsModeLabelsComparableRuns() throws IOException {
    String key = "table.statistics";
    assertTrue(ReportGenerator.exempt(key));
    assertTrue(ReportGenerator.exempt("table.update_statistics"));
    RunResult legacy = run("legacy", "false", 1L, false, "table.update_statistics", "true");
    RunResult off = run("off", "false", 1L, false, key, "off");
    RunResult on = run("on", "false", 1L, false, key, " ON ");
    assertEquals(RunResult.LEGACY_STATISTICS, legacy.statistics());
    assertEquals("off", off.statistics());
    assertEquals("on", on.statistics());
    // The modes are an A/B variable, so the runs stay comparable and the report labels each one.
    assertTrue(new ReportGenerator().comparability(Arrays.asList(legacy, off, on)).isEmpty());
  }

  @Test
  public void testRunsThatIndexedOtherRowsAreNotComparable() throws IOException {
    ReportGenerator gen = new ReportGenerator();
    // Comparison between run with prebuild+streaming vs run with rebuild over full dataset without
    // streaming
    List<String> problems = gen.comparability(
      Arrays.asList(run("r1", "false", 400000L, true), run("r2", "false", 2000000L, false)));
    assertEquals(problems.toString(), 2, problems.size());
    assertTrue(problems.get(0), problems.get(0).contains("indexed 2000000 rows"));
    assertTrue(problems.get(1), problems.get(1).contains("streamed no rows"));
    // Null row counts (e.g. from legacy manifests) bypass comparison check
    assertTrue(gen
      .comparability(
        Arrays.asList(run("x", "false", null, false), run("y", "false", 400000L, false)))
      .isEmpty());
  }

  /** Writes a run whose frontier.csv holds the given lines after the header. */
  private RunResult frontierRun(String id, String header, String... lines) throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", id);
    run.put("variant", id);
    m.put("run", run);
    Path dir = tmp.getRoot().toPath().resolve(id);
    Json.write(dir.resolve("manifest.json"), m);
    StringBuilder csv = new StringBuilder(header).append('\n');
    for (String l : lines) {
      csv.append(l).append('\n');
    }
    Files.write(dir.resolve("frontier.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
    return RunResult.read(dir);
  }

  @Test
  public void testCoverageTagShowsTheRealCoverageOfEachRun() throws IOException {
    String header = "stage,layout,family,coverage,k,sweep,recall,p50_ms,p99_ms";
    RunResult ivf =
      frontierRun("ivf", header, "frontier,corpus,covered_filter@0.1,covered,10,probes=1,0.95,1,2",
        "frontier,profile,covered_filter@0.1,uncovered,10,probes=1,0.95,1,2",
        "frontier,corpus,unfiltered,none,10,probes=1,0.95,1,2");
    RunResult hnsw =
      frontierRun("hnsw", header, "frontier,corpus,covered_filter@0.1,local,10,ef=16,0.95,1,2",
        "frontier,profile,covered_filter@0.1,local,10,ef=16,0.95,1,2");
    RunResult legacy = frontierRun("legacy", "stage,layout,family,k,sweep,recall,p50_ms,p99_ms",
      "frontier,profile,covered_filter@0.1,10,probes=1,0.95,1,2");
    List<RunResult> one = Arrays.asList(ivf);
    assertEquals(" [uncovered]",
      ReportGenerator.coverageTag(one, "profile covered_filter@0.1 k=10"));
    assertEquals(" [covered]", ReportGenerator.coverageTag(one, "corpus covered_filter@0.1 k=10"));
    assertEquals("", ReportGenerator.coverageTag(one, "corpus unfiltered k=10"));
    assertEquals(" [ivf:uncovered, hnsw:local]",
      ReportGenerator.coverageTag(Arrays.asList(ivf, hnsw), "profile covered_filter@0.1 k=10"));
    assertEquals(" [local]",
      ReportGenerator.coverageTag(Arrays.asList(hnsw), "corpus covered_filter@0.1 k=10"));
    assertEquals("",
      ReportGenerator.coverageTag(Arrays.asList(legacy), "profile covered_filter@0.1 k=10"));
    // The group key stays the same, so the runs still match by group.
    assertEquals(ReportGenerator.groups(Arrays.asList(ivf)).subList(0, 2),
      ReportGenerator.groups(Arrays.asList(hnsw)));
    String report = new ReportGenerator().summary(ivf);
    assertTrue(report, report.contains("profile covered_filter@0.1 k=10 [uncovered]"));
    assertTrue(report, report.contains("corpus covered_filter@0.1 k=10 [covered]"));
    assertFalse(report, report.contains("corpus unfiltered k=10 ["));
  }

  private static Map<String, Object> change(String trigger, String phase, Long generation,
    Map<String, Object> result) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("layout", "corpus");
    c.put("trigger", trigger);
    c.put("phase", phase);
    c.put("from_generation", 1L);
    c.put("generation", generation);
    c.put("start_offset_ms", 61_000L);
    c.put("end_offset_ms", 125_500L);
    c.put("result", result);
    return c;
  }

  @Test
  public void testLayoutChangesMarkTheirWindows() {
    Map<String, Object> balance = new LinkedHashMap<>();
    balance.put("balanced", false);
    Map<String, Object> promoted = new LinkedHashMap<>();
    promoted.put("index_regions", 4);
    promoted.put("balance", balance);
    Map<String, Object> midkey = new LinkedHashMap<>();
    midkey.put("outcome", "timed out");
    Map<String, Object> auto = new LinkedHashMap<>();
    auto.put("midkey", midkey);
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("error", "java.io.IOException: gone");
    Map<String, Object> w = new LinkedHashMap<>();
    w.put("stage", "steady@20");
    w.put("layout_changes",
      Arrays.asList(change("explicit", "before-rebuild", null, new LinkedHashMap<>()),
        change("explicit", "after-promotion", 2L, promoted),
        change("auto", "auto-promotion", 3L, auto), change("auto", "auto-promotion", 4L, error)));
    Map<String, Object> quiet = new LinkedHashMap<>();
    quiet.put("stage", "steady@40");
    quiet.put("layout_changes", Arrays.asList());
    List<Map<String, Object>> windows = Arrays.asList(w, quiet);
    TextTable t = ReportGenerator.layoutChanges(windows);
    assertFalse(t.isEmpty());
    String out = t.render("");
    String[] lines = out.split("\n");
    assertEquals(out, 5, lines.length);
    assertTrue(out, lines[1].contains("before-rebuild") && lines[1].contains("done")
      && lines[1].contains("61.000") && lines[1].contains("125.5"));
    // A change without a new generation shows the generation before the rebuild.
    assertTrue(out, lines[1].matches(".*before-rebuild\\s+1\\s.*"));
    assertTrue(out, lines[2].contains("unbalanced") && lines[2].matches(".*\\s4\\s.*"));
    assertTrue(out, lines[3].contains("timed out"));
    assertTrue(out, lines[4].contains("error"));
    assertTrue(ReportGenerator.layoutChanges(Arrays.asList(quiet)).isEmpty());
  }

  @Test
  public void testExtensionsShowTheRealLengthOfTheWindows() {
    Map<String, Object> extended = new LinkedHashMap<>();
    extended.put("stage", "steady@10");
    extended.put("planned_seconds", 30.0);
    extended.put("extension_seconds", 52.5);
    extended.put("extended_by", "4s:restart");
    extended.put("skipped_disruptions", Arrays.asList("20s:move", "25s:split"));
    Map<String, Object> planned = new LinkedHashMap<>();
    planned.put("stage", "steady@20");
    planned.put("planned_seconds", 30.0);
    planned.put("extension_seconds", 0.0);
    planned.put("skipped_disruptions", Arrays.asList());
    String out = ReportGenerator.extensions(Arrays.asList(extended, planned));
    String[] lines = out.split("\n");
    assertEquals(out, 2, lines.length);
    assertTrue(out,
      lines[0].contains("steady@10") && lines[0].contains("52.5") && lines[0].contains("30")
        && lines[0].contains("until 4s:restart and its recovery end")
        && lines[0].contains("no write due after the planned end runs"));
    assertTrue(out, lines[1].contains("steady@10") && lines[1].contains("20s:move 25s:split"));
    assertEquals("", ReportGenerator.extensions(Arrays.asList(planned)));
  }

  @Test
  public void testExtensionsTellHowTheDisruptionEnds() {
    Map<String, Object> failed = new LinkedHashMap<>();
    failed.put("disruption", "4s:restart");
    failed.put("error", "java.io.IOException: drain failed");
    Map<String, Object> extended = new LinkedHashMap<>();
    extended.put("stage", "steady@10");
    extended.put("planned_seconds", 30.0);
    extended.put("extension_seconds", 52.5);
    extended.put("extended_by", "4s:restart");
    extended.put("disruptions", Arrays.asList(failed));
    String out = ReportGenerator.extensions(Arrays.asList(extended));
    assertTrue(out,
      out.contains("until 4s:restart ends (error java.io.IOException: drain failed)"));
    Map<String, Object> unrecovered = new LinkedHashMap<>();
    unrecovered.put("disruption", "4s:restart");
    unrecovered.put("recovery_timed_out", true);
    extended.put("disruptions", Arrays.asList(unrecovered));
    out = ReportGenerator.extensions(Arrays.asList(extended));
    assertTrue(out, out.contains("until the recovery wait of 4s:restart times out"));
    // A disruption that passes the extension limit has no record yet, or a record of the stop.
    extended.put("overrun_by", "4s:restart");
    extended.put("disruptions", Arrays.asList());
    out = ReportGenerator.extensions(Arrays.asList(extended));
    assertTrue(out, out.contains("until 4s:restart passes the extension limit"));
  }
}
