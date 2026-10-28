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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.junit.Test;

public class VectorHarnessTest {

  private static HarnessConfig config(String... kv) {
    Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return HarnessConfig.of("t", p);
  }

  @Test
  public void testStreamingRepetitionsNeedASnapshot() {
    assertNull(VectorHarness.repetitionProblem(config("run.repetitions", "3")));
    assertNull(VectorHarness.repetitionProblem(config("ingest.prebuild.fraction", "0.2")));
    String problem = VectorHarness
      .repetitionProblem(config("run.repetitions", "3", "ingest.prebuild.fraction", "0.2"));
    assertNotNull(problem);
    assertTrue(problem, problem.contains("table.snapshot=true"));
    assertNull(VectorHarness.repetitionProblem(
      config("run.repetitions", "3", "ingest.prebuild.fraction", "0.2", "table.snapshot", "true")));
  }

  @Test
  public void testDegradedRunFailsTheRunPhase() throws Exception {
    Manifest clean = new Manifest().put("run", "id", "r1");
    clean.flag("a warning only");
    Manifest degraded = new Manifest().put("run", "id", "r2");
    degraded.degrade("vh-rebuild failed: java.lang.IllegalArgumentException");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream err = new PrintStream(bytes, true, "UTF-8")) {
      assertEquals(VectorHarness.OK, VectorHarness.exitCode(Arrays.asList(clean), err));
      assertEquals(0, bytes.size());
      assertEquals(VectorHarness.FAILED,
        VectorHarness.exitCode(Arrays.asList(clean, degraded), err));
    }
    String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(text, text.contains("r2 is degraded: vh-rebuild failed"));
    assertTrue(degraded.flags().contains("vh-rebuild failed: java.lang.IllegalArgumentException"));
  }

  @Test
  public void testReportWithoutProfileUsesTheSettingsOfTheRun() {
    Map<String, String> recorded = new HashMap<>();
    recorded.put("report.latency.budgets", "50ms,100ms");
    recorded.put("report.recall.targets", "0.8,0.85");
    recorded.put("recall.bootstrap.resamples", "200");
    HarnessConfig c = ReportPhase.reportConfig(config(), recorded);
    assertArrayEquals(new double[] { 50, 100 }, c.reportLatencyBudgetsMillis(), 0.0);
    assertArrayEquals(new double[] { 0.8, 0.85 }, c.reportRecallTargets(), 0.0);
    assertEquals(200, c.bootstrapResamples());
    // Command line override takes precedence.
    c = ReportPhase.reportConfig(config("report.latency.budgets", "5ms"), recorded);
    assertArrayEquals(new double[] { 5 }, c.reportLatencyBudgetsMillis(), 0.0);
    assertArrayEquals(new double[] { 0.8, 0.85 }, c.reportRecallTargets(), 0.0);
    // Runs without recorded settings use defaults.
    c = ReportPhase.reportConfig(config(), new HashMap<>());
    assertArrayEquals(new double[] { 10 }, c.reportLatencyBudgetsMillis(), 0.0);
  }
}
