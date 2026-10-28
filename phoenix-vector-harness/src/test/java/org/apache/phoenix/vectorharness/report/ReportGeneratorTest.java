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
  private RunResult run(String id, String deterministic, Long rowsIndexed, boolean streamed)
    throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", id);
    m.put("run", run);
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("run.id", id);
    settings.put("run.variant", id);
    settings.put("run.deterministic", deterministic);
    settings.put("mutation.threads", "4");
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
