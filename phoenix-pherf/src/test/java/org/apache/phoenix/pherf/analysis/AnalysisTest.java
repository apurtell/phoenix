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

import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.QUERY;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.QUERY_KEY;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.SCENARIO;
import static org.apache.phoenix.pherf.analysis.AnalysisFixtures.replica;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.phoenix.pherf.exception.AnalysisException;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirements RQ-209, RQ-210, RQ-211, RQ-213, and RQ-214 in process against fixture
 * replicates.
 */
public class AnalysisTest {
  private static final String LABEL = "t";

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private File out;
  private int exitCode;
  private String message;
  private String stdout;

  @Before
  public void setUp() throws Exception {
    out = folder.newFolder("out");
  }

  /**
   * Builds a co-generated replicate containing a single query key with the given execution count
   * and row counts.
   */
  private static AnalysisFixtures.Replica cogen(String name, long count, long... rows) {
    return replica(name).coGenerated().latency(QUERY_KEY, count, 1000).query(QUERY, null, rows);
  }

  /**
   * Builds a replicate containing a single query key with the specified execution count, p99
   * latency value, and a constant row count.
   */
  private static AnalysisFixtures.Replica timed(String name, long count, long p99) {
    return replica(name).coGenerated().latency(QUERY_KEY, "steady", count, 500, 800, p99, p99)
      .query(QUERY, null, 10);
  }

  private File set(String name, AnalysisFixtures.Replica... replicas) throws Exception {
    File dir = folder.newFolder(name);
    for (AnalysisFixtures.Replica replica : replicas) {
      replica.writeBundle(dir);
    }
    return dir;
  }

  private File bareSet(String name, AnalysisFixtures.Replica... replicas) throws Exception {
    File dir = folder.newFolder(name);
    for (AnalysisFixtures.Replica replica : replicas) {
      replica.writeBare(dir, "lbl");
    }
    return dir;
  }

  /**
   * Executes the analysis, records the resulting exit code and failure message, and returns the
   * parsed report or null if no report was produced.
   */
  private JsonObject analyze(File... sets) throws Exception {
    String[] dirs = new String[sets.length];
    for (int i = 0; i < sets.length; i++) {
      dirs[i] = sets[i].getPath();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    exitCode = 0;
    message = null;
    try (PrintStream print = new PrintStream(bytes, true, "UTF-8")) {
      Analysis.run(dirs, out, LABEL, print);
    } catch (AnalysisException e) {
      exitCode = e.getExitCode();
      message = e.getMessage();
    }
    stdout = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    File report = report();
    if (!report.exists()) {
      return null;
    }
    return JsonParser
      .parseString(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }

  private File report() {
    return new File(out, "ANALYSIS_" + LABEL + ".json");
  }

  private static JsonObject verdict(JsonObject report, String check) {
    for (JsonElement row : report.getAsJsonArray("verdicts")) {
      if (row.getAsJsonObject().get("check").getAsString().equals(check)) {
        return row.getAsJsonObject();
      }
    }
    throw new AssertionError("No verdict " + check + " in " + report);
  }

  private static String severity(JsonObject report, String check) {
    return verdict(report, check).get("severity").getAsString();
  }

  private static JsonObject entry(JsonArray entries, String operation, String phase,
    String metric) {
    for (JsonElement element : entries) {
      JsonObject entry = element.getAsJsonObject();
      if (
        entry.get("operation").getAsString().equals(operation)
          && entry.get("phase").getAsString().equals(phase)
          && entry.get("metric").getAsString().equals(metric)
      ) {
        return entry;
      }
    }
    return null;
  }

  private static JsonObject set(JsonObject report, int index) {
    return report.getAsJsonArray("sets").get(index).getAsJsonObject();
  }

  private static JsonObject count(JsonObject report, int set, String operation, String metric) {
    JsonObject entry =
      entry(set(report, set).getAsJsonArray("counts"), operation, "steady", metric);
    assertNotNull(operation + " " + metric, entry);
    return entry;
  }

  private static JsonObject comparison(JsonObject report, String operation, String metric) {
    JsonObject entry = entry(report.getAsJsonArray("comparisons"), operation, "steady", metric);
    assertNotNull(operation + " " + metric + " in " + report.get("comparisons"), entry);
    return entry;
  }

  // These tests verify requirement RQ-209 for exact execution and row count parity.

  @Test
  public void testCoGeneratedEqualCountsPass() throws Exception {
    JsonObject report = analyze(set("a", cogen("r1", 100, 250, 250), cogen("r2", 100, 250, 250)));
    assertEquals(stdout, 0, exitCode);
    assertEquals("PASS", severity(report, Analysis.COUNT_PARITY));
    assertEquals("count", verdict(report, Analysis.COUNT_PARITY).get("class").getAsString());
    assertEquals("pherf", verdict(report, Analysis.COUNT_PARITY).get("source").getAsString());
    for (String metric : new String[] { "count", "rows" }) {
      JsonObject entry = count(report, 0, QUERY_KEY, metric);
      assertTrue(entry.toString(), entry.get("exact").getAsBoolean());
      assertTrue(entry.toString(), entry.get("equal").getAsBoolean());
    }
    assertEquals(500,
      count(report, 0, QUERY_KEY, "rows").getAsJsonArray("values").get(0).getAsLong());
    assertEquals("PASS", report.get("result").getAsString());
  }

  @Test
  public void testCoGeneratedRowDifferenceFails() throws Exception {
    JsonObject report = analyze(set("a", cogen("r1", 100, 250, 250), cogen("r2", 100, 250, 249)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertNotNull("The report is written before the exit", report);
    assertEquals("FAIL", severity(report, Analysis.COUNT_PARITY));
    String detail = verdict(report, Analysis.COUNT_PARITY).get("detail").getAsString();
    for (String part : new String[] { QUERY_KEY, "rows", "r1", "r2", "500", "499" }) {
      assertTrue(detail, detail.contains(part));
    }
    assertFalse(count(report, 0, QUERY_KEY, "rows").get("equal").getAsBoolean());
    assertEquals("FAIL", report.get("result").getAsString());
    assertTrue(stdout, stdout.contains("FAIL"));

    // Count parity failures are never demoted, so the validation run class fails in the same way.
    report = analyze(set("v", cogen("r1", 100, 250, 250).runClass(Replicate.VALIDATION),
      cogen("r2", 100, 250, 249).runClass(Replicate.VALIDATION)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertEquals("FAIL", severity(report, Analysis.COUNT_PARITY));
  }

  @Test
  public void testCoGeneratedCountDifferenceFails() throws Exception {
    JsonObject report = analyze(set("a", cogen("r1", 100, 5), cogen("r2", 101, 5)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertEquals("FAIL", severity(report, Analysis.COUNT_PARITY));
    String detail = verdict(report, Analysis.COUNT_PARITY).get("detail").getAsString();
    assertTrue(detail, detail.contains("count") && detail.contains("101"));
  }

  @Test
  public void testMissingRowsOfExactKeyFails() throws Exception {
    // A replicate without XML results lacks row counts, which triggers a mismatch on an exact key.
    JsonObject report = analyze(set("a", cogen("r1", 100, 5), cogen("r2", 100, 5).noXml()));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    JsonObject rows = count(report, 0, QUERY_KEY, "rows");
    assertTrue(rows.get("exact").getAsBoolean());
    assertTrue(rows.getAsJsonArray("values").get(1).isJsonNull());
  }

  // These tests verify requirement RQ-210 for non-exact advisory count parity and single-replicate
  // skips.

  @Test
  public void testCoGeneratedQueryStoppedOnTimeIsAdvisory() throws Exception {
    // Each thread records 2 run times out of 1000 configured executions, indicating that the
    // thread stopped because its duration expired.
    JsonObject report = analyze(
      set("a", cogen("r1", 398, 5, 5).executions(1000), cogen("r2", 403, 5, 5).executions(1000)));
    assertEquals(message, 0, exitCode);
    assertEquals("ADVISORY", severity(report, Analysis.COUNT_PARITY));
    JsonObject entry = count(report, 0, QUERY_KEY, "count");
    assertFalse(entry.get("exact").getAsBoolean());
    assertTrue(entry.toString(), entry.get("reason").getAsString().contains("stopped on time"));
    String detail = verdict(report, Analysis.COUNT_PARITY).get("detail").getAsString();
    assertTrue(detail, detail.contains("stopped on time"));
    // The scenario data load operation remains subject to exact count parity.
    report =
      analyze(set("b", cogen("r1", 398, 5, 5).executions(1000).latency("load:S:PHERF.T", 9, 5),
        cogen("r2", 398, 5, 5).executions(1000).latency("load:S:PHERF.T", 8, 5)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertTrue(count(report, 0, "load:S:PHERF.T", "count").get("exact").getAsBoolean());
    // An open-loop query set has no execution count limit, so its operation keys remain exact.
    report = analyze(set("c", cogen("r1", 100, 5).openLoop().executions(1000),
      cogen("r2", 101, 5).openLoop().executions(1000)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertTrue(count(report, 0, QUERY_KEY, "count").get("exact").getAsBoolean());
    assertNull(count(report, 0, QUERY_KEY, "count").get("reason"));
  }

  @Test
  public void testRuleBasedCountDifferenceIsAdvisory() throws Exception {
    JsonObject report =
      analyze(set("a", replica("r1").latency(QUERY_KEY, 100, 1000).query(QUERY, null, 5),
        replica("r2").latency(QUERY_KEY, 101, 1000).query(QUERY, null, 6)));
    assertEquals(message, 0, exitCode);
    assertEquals("ADVISORY", severity(report, Analysis.COUNT_PARITY));
    assertEquals("ADVISORY", report.get("result").getAsString());
    JsonObject entry = count(report, 0, QUERY_KEY, "count");
    assertFalse(entry.get("exact").getAsBoolean());
    assertFalse(entry.get("equal").getAsBoolean());
  }

  @Test
  public void testBareCountDifferenceIsAdvisory() throws Exception {
    JsonObject report = analyze(bareSet("a", replica("b1").latency(QUERY_KEY, 100, 1000).noXml(),
      replica("b2").latency(QUERY_KEY, 99, 1000).noXml()));
    assertEquals(message, 0, exitCode);
    assertEquals("ADVISORY", severity(report, Analysis.COUNT_PARITY));
    assertEquals(Replicate.UNDECLARED, set(report, 0).get("runClass").getAsString());
  }

  @Test
  public void testSingleReplicatesSkipParity() throws Exception {
    JsonObject report = analyze(set("a", cogen("r1", 100, 5)));
    assertEquals(0, exitCode);
    assertEquals("SKIP", severity(report, Analysis.COUNT_PARITY));
    // Analyzing a single set produces no comparison metrics and no regression verdict row.
    assertEquals(1, report.getAsJsonArray("verdicts").size());
    assertEquals("SKIP", report.get("result").getAsString());
    JsonObject metric =
      entry(set(report, 0).getAsJsonArray("metrics"), QUERY_KEY, "steady", "p99Us");
    // A single replicate yields a degenerate point confidence interval.
    assertEquals(1000, metric.get("ciLow").getAsDouble(), 0);
    assertEquals(1000, metric.get("ciHigh").getAsDouble(), 0);
  }

  // These tests verify requirement RQ-211 for run class compatibility and timing authority.

  @Test
  public void testMeasurementBaselineValidationCandidateRefused() throws Exception {
    File baseline = set("base", timed("r1", 100, 1000), timed("r2", 100, 1000));
    File candidate = set("cand", timed("c1", 100, 1500).runClass(Replicate.VALIDATION));
    Files.write(report().toPath(), "{}".getBytes(StandardCharsets.UTF_8));
    assertNull("No comparison output, and the stale report is gone", analyze(baseline, candidate));
    assertEquals(Analysis.EXIT_CLASS_MISMATCH, exitCode);
    assertTrue(exitCode != 0 && exitCode != 1 && exitCode != 65);
    for (String part : new String[] { "measurement", "validation", "baseline", "candidate",
      "c1" }) {
      assertTrue(message, message.contains(part));
    }
    assertFalse(stdout, stdout.contains("Regression"));
  }

  @Test
  public void testMixedClassesInOneSetRefused() throws Exception {
    assertNull(analyze(
      set("a", timed("r1", 100, 1000), timed("r2", 100, 1000).runClass(Replicate.VALIDATION))));
    assertEquals(Analysis.EXIT_CLASS_MISMATCH, exitCode);
  }

  @Test
  public void testBareAgainstBundleRefused() throws Exception {
    assertNull(analyze(bareSet("a", timed("b1", 100, 1000)), set("b", timed("r1", 100, 1000))));
    assertEquals(Analysis.EXIT_CLASS_MISMATCH, exitCode);
    assertTrue(message, message.contains(Replicate.UNDECLARED));
  }

  @Test
  public void testTwoMeasurementSetsCompare() throws Exception {
    JsonObject report = analyze(set("base", timed("r1", 100, 1000), timed("r2", 100, 1000)),
      set("cand", timed("c1", 100, 1000), timed("c2", 100, 1000)));
    assertEquals(message, 0, exitCode);
    assertTrue(report.get("timingAuthoritative").getAsBoolean());
    assertTrue(report.getAsJsonArray("comparisons").size() > 0);
    assertEquals("baseline", set(report, 0).get("role").getAsString());
    assertEquals("candidate", set(report, 1).get("role").getAsString());
    assertEquals("PASS", severity(report, Analysis.REGRESSION));
  }

  @Test
  public void testRegressionIsAdvisoryWithoutAuthority() throws Exception {
    // Replicates in the validation run class lack timing authority and produce advisory verdicts.
    JsonObject report = analyze(
      set("vb", timed("r1", 100, 1000).runClass(Replicate.VALIDATION),
        timed("r2", 100, 1010).runClass(Replicate.VALIDATION)),
      set("vc", timed("c1", 100, 1500).runClass(Replicate.VALIDATION),
        timed("c2", 100, 1510).runClass(Replicate.VALIDATION)));
    assertAdvisoryRegression(report, "validation");
    // A contaminated measurement replicate loses timing authority and demotes regressions to
    // advisory status.
    report = analyze(set("mb", timed("r1", 100, 1000), timed("r2", 100, 1010)),
      set("mc", timed("c1", 100, 1500).tags("sizing"), timed("c2", 100, 1510)));
    assertAdvisoryRegression(report, "c1");
    assertTrue(verdict(report, Analysis.REGRESSION).get("detail").getAsString().contains("sizing"));
    // Bare result files do not have a declared run class and therefore lack timing authority.
    report = analyze(bareSet("bb", timed("b1", 100, 1000), timed("b2", 100, 1010)),
      bareSet("bc", timed("d1", 100, 1500), timed("d2", 100, 1510)));
    assertAdvisoryRegression(report, Replicate.UNDECLARED);
  }

  private void assertAdvisoryRegression(JsonObject report, String reason) {
    assertEquals(message, 0, exitCode);
    assertFalse(report.get("timingAuthoritative").getAsBoolean());
    assertEquals("regression", comparison(report, QUERY_KEY, "p99Us").get("outcome").getAsString());
    assertEquals("ADVISORY", severity(report, Analysis.REGRESSION));
    assertEquals("duration", verdict(report, Analysis.REGRESSION).get("class").getAsString());
    String detail = verdict(report, Analysis.REGRESSION).get("detail").getAsString();
    assertTrue(detail, detail.contains(reason));
    assertEquals("ADVISORY", report.get("result").getAsString());
  }

  // These tests verify requirement RQ-212 in process, whereas AnalyzeRunTest exercises real
  // bundles through Pherf.main.

  @Test
  public void testFewerThanThreeReplicatesIsAdvisory() throws Exception {
    // When each set contains only one replicate, the confidence interval collapses to a point and
    // only the relative delta threshold applies.
    JsonObject report =
      analyze(set("one", timed("r1", 100, 1000)), set("other", timed("c1", 100, 1150)));
    assertEquals(message, 0, exitCode);
    assertTrue(report.get("timingAuthoritative").getAsBoolean());
    assertEquals("regression", comparison(report, QUERY_KEY, "p99Us").get("outcome").getAsString());
    assertEquals("ADVISORY", severity(report, Analysis.REGRESSION));
    String detail = verdict(report, Analysis.REGRESSION).get("detail").getAsString();
    assertTrue(detail, detail.contains("fewer than 3 replicates in a set"));
    // Comparing three baseline replicates against two candidate replicates remains advisory
    // because the candidate set has fewer than three replicates.
    report =
      analyze(set("three", timed("r1", 100, 1000), timed("r2", 100, 1010), timed("r3", 100, 990)),
        set("two", timed("c1", 100, 1500), timed("c2", 100, 1510)));
    assertEquals(message, 0, exitCode);
    assertEquals("ADVISORY", severity(report, Analysis.REGRESSION));
  }

  @Test
  public void testFewSamplesGiveNoTailPercentile() throws Exception {
    // With only 10 executions, both p99Us and p999Us resolve to the single slowest sample, meaning
    // a single pause determines both percentiles.
    File baseline = set("base", tail("r1", 1100), tail("r2", 1200), tail("r3", 1300));
    JsonObject report =
      analyze(baseline, set("cand", tail("c1", 1400), tail("c2", 1450), tail("c3", 1500)));
    assertEquals(message, 0, exitCode);
    for (String metric : new String[] { "p99Us", "p999Us" }) {
      assertNull(metric, entry(report.getAsJsonArray("comparisons"), QUERY_KEY, "steady", metric));
      assertNull(metric, entry(report.getAsJsonArray("unpaired"), QUERY_KEY, "steady", metric));
      assertNull(metric,
        entry(set(report, 0).getAsJsonArray("metrics"), QUERY_KEY, "steady", metric));
    }
    assertEquals("unchanged", comparison(report, QUERY_KEY, "p90Us").get("outcome").getAsString());
    assertEquals("unchanged", comparison(report, QUERY_KEY, "p50Us").get("outcome").getAsString());
    assertEquals("PASS", severity(report, Analysis.REGRESSION));
  }

  /**
   * Builds a replicate with 10 steady-state query executions where both tail percentiles equal the
   * slowest sample.
   */
  private static AnalysisFixtures.Replica tail(String name, long max) {
    return replica(name).latency(QUERY_KEY, "steady", 10, 500, 800, max, max);
  }

  @Test
  public void testSetIntervalsDoNotDependOnRole() throws Exception {
    File a = set("A", timed("r1", 100, 900), timed("r2", 100, 1000), timed("r3", 100, 1200),
      timed("r4", 100, 1500));
    JsonObject alone =
      entry(set(analyze(a), 0).getAsJsonArray("metrics"), QUERY_KEY, "steady", "p99Us");
    JsonObject baseline =
      entry(set(analyze(a, set("B", timed("c1", 100, 1000))), 0).getAsJsonArray("metrics"),
        QUERY_KEY, "steady", "p99Us");
    assertTrue(alone.toString(),
      alone.get("ciLow").getAsDouble() < alone.get("ciHigh").getAsDouble());
    assertEquals(alone.get("ciLow"), baseline.get("ciLow"));
    assertEquals(alone.get("ciHigh"), baseline.get("ciHigh"));
  }

  @Test
  public void testReportNamesEveryReplicate() throws Exception {
    JsonObject report =
      analyze(set("a", cogen("r1", 100, 5), cogen("r2", 100, 5), cogen("r3", 100, 5)));
    assertEquals("pherf-analysis", report.get("format").getAsString());
    assertEquals(1, report.get("version").getAsInt());
    assertEquals(LABEL, report.get("label").getAsString());
    JsonArray replicates = set(report, 0).getAsJsonArray("replicates");
    assertEquals(3, replicates.size());
    for (int i = 0; i < 3; i++) {
      JsonObject replicate = replicates.get(i).getAsJsonObject();
      assertEquals("r" + (i + 1), replicate.get("name").getAsString());
      assertEquals(AnalysisFixtures.runId("r" + (i + 1)), replicate.get("runId").getAsString());
      assertEquals(Replicate.BUNDLE, replicate.get("layout").getAsString());
      assertEquals(SCENARIO, replicate.getAsJsonArray("coGenerated").get(0).getAsString());
      assertTrue(stdout, stdout.contains("r" + (i + 1)));
    }
    assertEquals("set", set(report, 0).get("role").getAsString());
    JsonObject bootstrap = report.getAsJsonObject("bootstrap");
    assertEquals(10000, bootstrap.get("resamples").getAsInt());
    assertEquals(95, bootstrap.get("confidencePct").getAsInt());
    assertEquals(Analysis.SEED, bootstrap.get("seed").getAsLong());
  }

  // These tests verify requirement RQ-213 for replicate median aggregation and deterministic
  // bootstrap reporting.

  @Test
  public void testMedianOfReplicates() throws Exception {
    // Executing 100, 120, and 110 steady-state queries over a 10 s window yields throughput rates
    // of 10, 12, and 11 operations per second.
    JsonObject report =
      analyze(set("a", timed("r1", 100, 900), timed("r2", 120, 1200), timed("r3", 110, 1000)));
    JsonArray metrics = set(report, 0).getAsJsonArray("metrics");
    JsonObject p99 = entry(metrics, QUERY_KEY, "steady", "p99Us");
    assertEquals(1000, p99.get("median").getAsDouble(), 0);
    assertEquals(3, p99.get("n").getAsInt());
    assertEquals(900, p99.getAsJsonArray("values").get(0).getAsDouble(), 0);
    assertTrue(p99.get("ciLow").getAsDouble() <= 1000 && p99.get("ciHigh").getAsDouble() >= 1000);
    JsonObject ops = entry(metrics, "run", "steady", "opsPerSec");
    assertNotNull(metrics.toString(), ops);
    assertEquals(11.0, ops.get("median").getAsDouble(), 1e-12);
    // Data load and response latency entries are excluded from the aggregate run throughput.
    report = analyze(set("b",
      timed("r1", 100, 900).latency("load:S:PHERF.T", 999, 5).latency("response:S:q", 100, 5)));
    assertEquals(10.0, entry(set(report, 0).getAsJsonArray("metrics"), "run", "steady", "opsPerSec")
      .get("median").getAsDouble(), 1e-12);
  }

  @Test
  public void testWarmupIsNotAMetric() throws Exception {
    JsonObject report =
      analyze(set("a", timed("r1", 100, 900).latency(QUERY_KEY, "warmup", 7, 50, 50, 50, 50)));
    JsonObject set = set(report, 0);
    assertNull(entry(set.getAsJsonArray("metrics"), QUERY_KEY, "warmup", "p99Us"));
    assertNotNull(entry(set.getAsJsonArray("counts"), QUERY_KEY, "warmup", "count"));
  }

  @Test
  public void testSameInputGivesIdenticalReport() throws Exception {
    File baseline =
      set("base", timed("r1", 100, 900), timed("r2", 120, 1200), timed("r3", 110, 1000));
    File candidate = set("cand", timed("c1", 100, 950), timed("c2", 100, 1250));
    analyze(baseline, candidate);
    byte[] first = Files.readAllBytes(report().toPath());
    analyze(baseline, candidate);
    assertArrayEquals(first, Files.readAllBytes(report().toPath()));
    JsonObject metric = entry(
      set(JsonParser.parseString(new String(first, StandardCharsets.UTF_8)).getAsJsonObject(), 0)
        .getAsJsonArray("metrics"),
      QUERY_KEY, "steady", "p99Us");
    assertTrue(metric.toString(),
      metric.get("ciLow").getAsDouble() < metric.get("ciHigh").getAsDouble());
  }

  // These tests verify requirement RQ-214 for baseline-to-candidate comparison and regression
  // detection.

  @Test
  public void testBaselineRegressionDetected() throws Exception {
    File baseline =
      set("base", timed("r1", 100, 1000), timed("r2", 100, 1010), timed("r3", 100, 990));
    JsonObject report = analyze(baseline,
      set("cand", timed("c1", 100, 1500), timed("c2", 100, 1510), timed("c3", 100, 1490)));
    assertEquals(Analysis.EXIT_FAIL, exitCode);
    assertNotNull(report);
    assertTrue(report.get("timingAuthoritative").getAsBoolean());
    JsonObject p99 = comparison(report, QUERY_KEY, "p99Us");
    assertEquals("regression", p99.get("outcome").getAsString());
    assertEquals(1000, p99.get("baselineMedian").getAsDouble(), 0);
    assertEquals(1500, p99.get("candidateMedian").getAsDouble(), 0);
    assertEquals(500, p99.get("delta").getAsDouble(), 0);
    assertEquals(50, p99.get("deltaPct").getAsDouble(), 1e-9);
    assertTrue(p99.toString(), p99.get("ciLowPct").getAsDouble() >= 48);
    assertEquals("FAIL", severity(report, Analysis.REGRESSION));
    assertTrue(
      verdict(report, Analysis.REGRESSION).get("detail").getAsString().contains(QUERY_KEY));
    assertEquals("unchanged", comparison(report, QUERY_KEY, "p50Us").get("outcome").getAsString());
    assertTrue(stdout, stdout.contains("Regression"));

    // A candidate set with unchanged performance passes the regression check.
    report = analyze(baseline,
      set("same", timed("s1", 100, 1000), timed("s2", 100, 1010), timed("s3", 100, 990)));
    assertEquals(message, 0, exitCode);
    assertEquals("PASS", severity(report, Analysis.REGRESSION));
    assertEquals("unchanged", comparison(report, QUERY_KEY, "p99Us").get("outcome").getAsString());
    assertEquals("PASS", report.get("result").getAsString());
  }

  @Test
  public void testRegressionNeedsBothClauses() throws Exception {
    // A 20% median delta whose confidence interval spans 0 is classified as unchanged.
    JsonObject report =
      analyze(set("wideA", timed("r1", 100, 800), timed("r2", 100, 1000), timed("r3", 100, 1400)),
        set("wideB", timed("c1", 100, 700), timed("c2", 100, 1200), timed("c3", 100, 1500)));
    JsonObject p99 = comparison(report, QUERY_KEY, "p99Us");
    assertEquals(20, p99.get("deltaPct").getAsDouble(), 1e-9);
    assertTrue(p99.toString(), p99.get("ciLowPct").getAsDouble() < 0);
    assertEquals("unchanged", p99.get("outcome").getAsString());
    assertEquals(0, exitCode);
    // A 5% median delta with a tight confidence interval remains below the threshold and is
    // classified as unchanged.
    report = analyze(set("tightA", timed("r1", 100, 1000), timed("r2", 100, 1000)),
      set("tightB", timed("c1", 100, 1050), timed("c2", 100, 1050)));
    p99 = comparison(report, QUERY_KEY, "p99Us");
    assertEquals(5, p99.get("deltaPct").getAsDouble(), 1e-9);
    assertEquals(5, p99.get("ciLowPct").getAsDouble(), 1e-9);
    assertEquals("unchanged", p99.get("outcome").getAsString());
    assertEquals(0, exitCode);
  }

  @Test
  public void testAbComparisonPairsMetrics() throws Exception {
    // Replicate sets with constant values produce exact deltas and confidence intervals, and set B
    // executes twice as many queries within the same time window.
    JsonObject report = analyze(
      set("A", timed("a1", 100, 1000).latency("load:S:A", 5, 70),
        timed("a2", 100, 1000).latency("load:S:A", 5, 70)),
      set("B", timed("b1", 200, 900).latency("load:S:B", 5, 70),
        timed("b2", 200, 900).latency("load:S:B", 5, 70)));
    assertEquals(message, 0, exitCode);
    JsonObject p99 = comparison(report, QUERY_KEY, "p99Us");
    assertEquals(-100, p99.get("delta").getAsDouble(), 0);
    assertEquals(-10, p99.get("deltaPct").getAsDouble(), 0);
    assertEquals(-10, p99.get("ciLowPct").getAsDouble(), 0);
    assertEquals(-10, p99.get("ciHighPct").getAsDouble(), 0);
    // A change of exactly 10% does not exceed the strict threshold boundary.
    assertEquals("unchanged", p99.get("outcome").getAsString());
    JsonObject ops = comparison(report, "run", "opsPerSec");
    assertEquals(10, ops.get("baselineMedian").getAsDouble(), 0);
    assertEquals(20, ops.get("candidateMedian").getAsDouble(), 0);
    assertEquals(100, ops.get("deltaPct").getAsDouble(), 0);
    assertEquals(100, ops.get("ciLowPct").getAsDouble(), 0);
    assertEquals("improvement", ops.get("outcome").getAsString());
    // Each load operation key has only 5 samples, so p50Us is the only emitted percentile metric.
    assertNull(entry(report.getAsJsonArray("comparisons"), "load:S:A", "steady", "p50Us"));
    assertNull(entry(report.getAsJsonArray("unpaired"), "load:S:A", "steady", "p90Us"));
    JsonObject onlyA = entry(report.getAsJsonArray("unpaired"), "load:S:A", "steady", "p50Us");
    JsonObject onlyB = entry(report.getAsJsonArray("unpaired"), "load:S:B", "steady", "p50Us");
    assertEquals("baseline", onlyA.get("set").getAsString());
    assertEquals("candidate", onlyB.get("set").getAsString());
    assertEquals("PASS", severity(report, Analysis.REGRESSION));
    assertTrue(stdout, stdout.contains("Improvement"));
  }

  @Test
  public void testZeroBaselineMedianSkips() throws Exception {
    JsonObject report = analyze(set("A", timed("a1", 100, 0)), set("B", timed("b1", 100, 10)));
    JsonObject p99 = comparison(report, QUERY_KEY, "p99Us");
    assertEquals("skip", p99.get("outcome").getAsString());
    assertNull(p99.get("deltaPct"));
  }

  @Test
  public void testCountParityIsWithinEachSet() throws Exception {
    // An A/B comparison where the two sets have different internal counts does not trigger a count
    // parity failure when each set is internally consistent.
    JsonObject report = analyze(set("A", cogen("a1", 100, 5), cogen("a2", 100, 5)),
      set("B", cogen("b1", 200, 7), cogen("b2", 200, 7)));
    assertEquals(message, 0, exitCode);
    assertEquals("PASS", severity(report, Analysis.COUNT_PARITY));
  }

  @Test
  public void testRefusalLeavesNoReportForBadInput() throws Exception {
    File set = set("a", cogen("r1", 100, 5));
    Files.write(report().toPath(), "{}".getBytes(StandardCharsets.UTF_8));
    new File(set, "r1/manifest.json").delete();
    assertNull(analyze(set));
    assertEquals(1, exitCode);
  }
}
