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
package org.apache.phoenix.pherf.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.pherf.analysis.Analysis;
import org.apache.phoenix.pherf.analysis.AnalysisFixtures;
import org.apache.phoenix.pherf.analysis.Replicate;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises the -analyze mode through Pherf.main in isolated child JVMs. Three child processes
 * execute the relational reference smoke workload (-l -q, using the real clock and the fake
 * driver), and the test packages each run into a run bundle matching the layout written by the
 * harness. Additional child processes then run -analyze with a connection provider that records
 * each connection open attempt and immediately fails it.
 */
public class AnalyzeRunTest {
  private static final String[] LABELS = { "r1", "r2", "r3" };

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static File set;
  private static ChildJvm analyze;
  private static int analyzeExit;
  private static ChildJvm mismatch;
  private static int mismatchExit;
  private static ChildJvm regression;
  private static int regressionExit;

  @BeforeClass
  public static void runPherf() throws Exception {
    // Spawn a dedicated child process for each run because the legacy XML handlers retain the
    // file name generated during the first run within a JVM.
    set = FOLDER.newFolder("set");
    for (String label : LABELS) {
      ChildJvm smoke = new ChildJvm(FOLDER.newFolder("smoke-" + label));
      smoke.register(ConnectionProvider.class, KeyedStatementRecorder.class);
      smoke.systemProperty(ChildRun.REAL_CLOCK, "true");
      List<List<String>> invocations = new ArrayList<>();
      invocations.add(ChildJvm.args("-l", "-q", "-z", "reference", "-label", label,
        "-writerThreadSize", "2", "-disableSchemaApply", "-scenarioFile",
        ReferenceScenarios.RELATIONAL.scenarioFile().getPath()));
      smoke.runCli(invocations);

      // Package the completed run output as a single bundle using the file naming convention of
      // the harness run verb.
      File bundle = new File(set, label);
      AnalysisFixtures.writeManifest(bundle, AnalysisFixtures.runId(label), Replicate.MEASUREMENT,
        "PASS");
      File hdr = new File(bundle, "drivers/pherf/results_HDR");
      File results = new File(bundle, "drivers/pherf/results");
      Files.createDirectories(hdr.toPath());
      Files.createDirectories(results.toPath());
      for (String suffix : new String[] { ".json", ".hlog" }) {
        copy(new File(smoke.work(), "RESULTS_HDR/LATENCY_" + label + suffix),
          new File(hdr, "LATENCY_run" + suffix));
      }
      copy(new File(smoke.work(), "RESULTS_HDR/VERDICT_" + label + ".tsv"),
        new File(hdr, "VERDICT_run.tsv"));
      String model = ReferenceScenarios.RELATIONAL.dataModel;
      copy(new File(smoke.work(), "RESULTS/RESULT_" + model + "_" + label + ".xml"),
        new File(results, "RESULT_" + model + "_run.xml"));
    }
    analyze = child("analyze");
    analyzeExit = analyze.runMain(ChildJvm.args("-analyze", set.getPath(), "-label", "rq212"));

    File base = FOLDER.newFolder("base");
    File cand = FOLDER.newFolder("cand");
    for (String name : LABELS) {
      AnalysisFixtures.replica(name).latency(AnalysisFixtures.QUERY_KEY, 100, 1000)
        .writeBundle(base);
    }
    AnalysisFixtures.replica("c1").runClass(Replicate.VALIDATION)
      .latency(AnalysisFixtures.QUERY_KEY, 100, 1000).writeBundle(cand);
    mismatch = child("mismatch");
    mismatchExit = mismatch
      .runMain(ChildJvm.args("-analyze", base.getPath(), cand.getPath(), "-label", "mismatch"));

    File slow = FOLDER.newFolder("slow");
    for (String name : LABELS) {
      AnalysisFixtures.replica(name).latency(AnalysisFixtures.QUERY_KEY, 100, 1500)
        .writeBundle(slow);
    }
    regression = child("regression");
    regressionExit = regression
      .runMain(ChildJvm.args("-analyze", base.getPath(), slow.getPath(), "-label", "regression"));
  }

  private static ChildJvm child(String name) throws Exception {
    ChildJvm child = new ChildJvm(FOLDER.newFolder(name));
    child.register(ConnectionProvider.class, ValidateRunTest.ThrowingProvider.class);
    return child;
  }

  private static void copy(File from, File to) throws Exception {
    assertTrue("No output " + from, from.isFile());
    Files.copy(from.toPath(), to.toPath());
  }

  private static JsonObject read(File file) throws Exception {
    return JsonParser
      .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }

  /**
   * Verifies RQ-212 and RQ-152 by confirming that analysis produces a single report naming all
   * three bundles without opening any database connections.
   */
  @Test
  public void testAnalyzeNamesAllThreeBundles() throws Exception {
    List<String> out = Records.read(analyze.out());
    assertEquals(out.toString(), 0, analyzeExit);
    assertEquals(new ArrayList<String>(), Records.read(analyze.record(Records.PROVIDER)));
    File[] reports = new File(analyze.work(), "RESULTS").listFiles();
    assertEquals(1, reports.length);
    assertEquals("ANALYSIS_rq212.json", reports[0].getName());
    JsonObject report = read(reports[0]);
    JsonArray sets = report.getAsJsonArray("sets");
    assertEquals(1, sets.size());
    JsonArray replicates = sets.get(0).getAsJsonObject().getAsJsonArray("replicates");
    assertEquals(3, replicates.size());
    String text = String.join("\n", out);
    for (int i = 0; i < LABELS.length; i++) {
      JsonObject replicate = replicates.get(i).getAsJsonObject();
      assertEquals(LABELS[i], replicate.get("name").getAsString());
      assertEquals(AnalysisFixtures.runId(LABELS[i]), replicate.get("runId").getAsString());
      assertEquals(ReferenceScenarios.RELATIONAL.scenario,
        replicate.getAsJsonArray("coGenerated").get(0).getAsString());
      assertTrue(text, text.contains(LABELS[i]));
      assertTrue(text, text.contains(AnalysisFixtures.runId(LABELS[i])));
    }
    // Confirm that the co-generated query keys are marked as exact and that all three runs
    // produce equal operation counts.
    String scenario = ReferenceScenarios.RELATIONAL.scenario;
    List<String> exact = new ArrayList<>();
    for (JsonElement element : sets.get(0).getAsJsonObject().getAsJsonArray("counts")) {
      JsonObject count = element.getAsJsonObject();
      assertTrue(count.toString(), count.get("equal").getAsBoolean());
      if (count.get("exact").getAsBoolean()) {
        exact.add(count.get("operation").getAsString() + " " + count.get("metric").getAsString());
      }
    }
    for (String key : new String[] { "query:" + scenario + ":lookup count",
      "scan:" + scenario + ":rangeScan count", "delete:" + scenario + ":rangeDelete count",
      "response:" + scenario + ":lookup count", "query:" + scenario + ":lookup rows" }) {
      assertTrue(exact.toString(), exact.contains(key));
    }
    JsonObject parity = report.getAsJsonArray("verdicts").get(0).getAsJsonObject();
    assertEquals(Analysis.COUNT_PARITY, parity.get("check").getAsString());
    assertEquals("PASS", parity.get("severity").getAsString());
  }

  /**
   * Verifies RQ-211 by confirming that comparing a validation candidate against a measurement
   * baseline terminates with exit code 66 and writes no analysis report.
   */
  @Test
  public void testClassMismatchExits66() throws Exception {
    assertEquals(Analysis.EXIT_CLASS_MISMATCH, mismatchExit);
    assertFalse(new File(mismatch.work(), "RESULTS/ANALYSIS_mismatch.json").exists());
    assertEquals(new ArrayList<String>(), Records.read(mismatch.record(Records.PROVIDER)));
  }

  /**
   * Verifies RQ-214 by confirming that a detected performance regression terminates with exit code
   * 67 after writing the analysis report.
   */
  @Test
  public void testRegressionExits67() throws Exception {
    assertEquals(Analysis.EXIT_FAIL, regressionExit);
    JsonObject report = read(new File(regression.work(), "RESULTS/ANALYSIS_regression.json"));
    assertEquals("FAIL", report.get("result").getAsString());
    assertEquals(new ArrayList<String>(), Records.read(regression.record(Records.PROVIDER)));
  }
}
