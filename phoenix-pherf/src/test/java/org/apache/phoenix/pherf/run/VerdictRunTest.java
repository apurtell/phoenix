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

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.VerdictSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirements RQ-201 to RQ-206 end to end by confirming that every Pherf run with a
 * scenario file writes the verdict file alongside the latency report and that the verdict never
 * alters the outcome of the run. The child JVMs execute against the fake driver, while the tail and
 * noisy-host runs use the scripted microsecond clock.
 */
public class VerdictRunTest {
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private static String scenario(String name) {
    return new File(ChildJvm.scenarioDir(), name).getPath();
  }

  /**
   * Reads and returns the rows of the generated verdict file, indexed by check name in file order.
   */
  private static Map<String, String[]> verdict(ChildJvm child, String label) throws Exception {
    File file = new File(new File(child.work(), "RESULTS_HDR"), "VERDICT_" + label + ".tsv");
    assertTrue("No verdict " + file, file.exists());
    Map<String, String[]> rows = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
      String[] fields = line.split("\t", -1);
      assertEquals(line, 5, fields.length);
      assertEquals(line, HealthVerdict.SOURCE, fields[3]);
      rows.put(fields[0], fields);
    }
    assertEquals(HealthVerdict.CHECKS, new ArrayList<>(rows.keySet()));
    return rows;
  }

  private static void assertRow(Map<String, String[]> rows, String check, String severity) {
    assertEquals(Arrays.toString(rows.get(check)), severity, rows.get(check)[1]);
  }

  private static void assertRan(ChildJvm child) throws Exception {
    assertEquals(Collections.singletonList(ChildRun.RAN),
      Records.read(child.record(Records.OUTCOME)));
  }

  /**
   * Verifies requirement RQ-202: executing the closed-loop run from DefaultRunGoldenTest produces
   * five verdict rows in which attainment and saturation report SKIP because no open-loop schedule
   * is present, read skew reports SKIP because no read-skew source is present, and no additional
   * files are created in the results directory.
   */
  @Test
  public void testClosedLoopVerdictFile() throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder("closed"));
    child.runCli(Collections.singletonList(
      ChildJvm.args("-l", "-q", "-z", "golden", "-label", "golden", "-writerThreadSize", "2",
        "-disableSchemaApply", "-scenarioFile", scenario("test_tenant_binding.xml"))));
    assertRan(child);
    Map<String, String[]> rows = verdict(child, "golden");
    assertRow(rows, HealthVerdict.ATTAINMENT, "SKIP");
    assertRow(rows, HealthVerdict.SATURATION, "SKIP");
    assertRow(rows, HealthVerdict.CLIENT_POOL, "PASS");
    assertRow(rows, HealthVerdict.TAIL_LATENCY, "PASS");
    assertRow(rows, HealthVerdict.READ_SKEW, "SKIP");

    String[] names = new File(child.work(), "RESULTS").list();
    Arrays.sort(names);
    assertEquals(Golden.lines("default-run/files.golden"), Arrays.asList(names));
  }

  /**
   * Verifies requirement RQ-201: when 3 closed-loop runners and the query executor collectively
   * require 4 threads from a thread pool of size 2, the run still completes normally while the
   * client pool health check reports a failure.
   */
  @Test
  public void testClosedLoopPoolExhaustion() throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder("pool"));
    child.pherfProperty("pherf.default.threadpool", "2");
    child.runCli(
      Collections.singletonList(ChildJvm.args("-q", "-z", "pool", "-label", "pool", "-threadCount",
        "3", "-disableSchemaApply", "-scenarioFile", scenario("test_tenant_binding.xml"))));
    assertRan(child);
    Map<String, String[]> rows = verdict(child, "pool");
    assertRow(rows, HealthVerdict.CLIENT_POOL, "FAIL");
    String detail = rows.get(HealthVerdict.CLIENT_POOL)[4];
    assertTrue(detail, detail.contains("pherf.default.threadpool"));
    assertRow(rows, HealthVerdict.SATURATION, "SKIP");
  }

  /**
   * Verifies requirements RQ-202 and RQ-203 by executing an open-loop workload against the real
   * clock configured with loose threshold bounds.
   */
  @Test
  public void testOpenLoopVerdict() throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder("open"));
    child.systemProperty(ChildRun.REAL_CLOCK, "true");
    child.pherfProperty(VerdictSettings.MIN_PCT, "1");
    child.pherfProperty(VerdictSettings.LAG_MAX_MS, "600000");
    child.pherfProperty(VerdictSettings.P99_MAX_MS, "600000");
    child.pherfProperty(VerdictSettings.P999_MAX_MS, "600000");
    String fixture =
      new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/openloop/open_loop.xml")
        .getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "openloop", "-label", "o",
      "-disableSchemaApply", "-scenarioFile", fixture)));
    assertRan(child);
    Map<String, String[]> rows = verdict(child, "o");
    assertRow(rows, HealthVerdict.ATTAINMENT, "PASS");
    assertRow(rows, HealthVerdict.SATURATION, "PASS");
    for (String check : new String[] { HealthVerdict.ATTAINMENT, HealthVerdict.SATURATION }) {
      String detail = rows.get(check)[4];
      assertTrue(detail, detail.contains("scenario openLoop query set 1"));
    }
    JsonObject report =
      HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_o.json"));
    assertEquals(50,
      report.getAsJsonArray("schedules").get(0).getAsJsonObject().get("durationMs").getAsLong());
  }

  /**
   * Verifies requirements RQ-204 and RQ-206: when query q1 takes 2,600 us and exceeds the 1 ms p99
   * latency bound, the tail latency check fails, whereas repeating the same run with the noisy-host
   * flag enabled downgrades the result to ADVISORY.
   */
  @Test
  public void testTailVerdictAndNoisyHost() throws Exception {
    String[] severities = new String[2];
    String[] noisy = { "false", "true" };
    for (int i = 0; i < noisy.length; i++) {
      ChildJvm child = new ChildJvm(folder.newFolder("tail" + i));
      child.systemProperty(ChildRun.ELAPSED_MICROS, "2600,1234,300");
      child.pherfProperty(VerdictSettings.P99_MAX_MS, "1");
      child.pherfProperty(VerdictSettings.NOISY_HOST, noisy[i]);
      child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "golden", "-label", "us",
        "-disableSchemaApply", "-scenarioFile", scenario("test_tenant_binding.xml"))));
      assertRan(child);
      String[] row = verdict(child, "us").get(HealthVerdict.TAIL_LATENCY);
      severities[i] = row[1];
      assertTrue(row[4], row[4].contains("query:testTenantBinding:q1"));
    }
    assertEquals(Arrays.asList("FAIL", "ADVISORY"), Arrays.asList(severities));
  }

  /**
   * Verifies that a malformed verdict property value aborts the run before any connection is opened
   * and produces an error message that explicitly names the invalid property key.
   */
  @Test
  public void testInvalidVerdictPropertyStopsTheRun() throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder("invalid"));
    child.register(ConnectionProvider.class, ValidateRunTest.ThrowingProvider.class);
    child.pherfProperty(VerdictSettings.P99_MAX_MS, "abc");
    int exit = child.runMain(ChildJvm.args("-drop", "all", "-l", "-q", "-label", "bad",
      "-disableSchemaApply", "-scenarioFile", scenario("test_tenant_binding.xml")));
    assertEquals(1, exit);
    assertEquals(new ArrayList<String>(), Records.read(child.record(Records.PROVIDER)));
    String err =
      new String(Files.readAllBytes(new File(child.out().getParentFile(), "child.err").toPath()),
        StandardCharsets.UTF_8);
    assertTrue(err, err.contains(VerdictSettings.P99_MAX_MS));
    assertFalse(new File(child.work(), "RESULTS_HDR/VERDICT_bad.tsv").exists());
  }
}
