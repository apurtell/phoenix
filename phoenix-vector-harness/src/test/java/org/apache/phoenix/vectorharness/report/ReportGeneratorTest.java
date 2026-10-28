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

  /** Writes the manifest of a run and reads it back. */
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
    // A repetition that rebuilt over the streamed rows and streamed none.
    List<String> problems = gen.comparability(
      Arrays.asList(run("r1", "false", 400000L, true), run("r2", "false", 2000000L, false)));
    assertEquals(problems.toString(), 2, problems.size());
    assertTrue(problems.get(0), problems.get(0).contains("indexed 2000000 rows"));
    assertTrue(problems.get(1), problems.get(1).contains("streamed no rows"));
    // Build states of earlier versions do not record the rows.
    assertTrue(gen
      .comparability(
        Arrays.asList(run("x", "false", null, false), run("y", "false", 400000L, false)))
      .isEmpty());
  }
}
