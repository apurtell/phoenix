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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.vectorharness.report.FrontierPoint;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Runs every phase of the harness through its command line against the shared minicluster, for each
 * index variant over the same corpus and panel, and then compares the three runs. The test checks
 * the harness and the indexes together: the exact plan must agree with the client-side ground
 * truth, full IVF probing must find the true neighbors, a wide HNSW beam must find most of them,
 * every plan must match the variant's expectation, and the report must pair the runs.
 */
@Category(ParallelStatsDisabledTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VectorHarnessEndToEndIT extends ParallelStatsDisabledIT {

  private static Path output;

  @BeforeClass
  public static synchronized void setUpOutput() throws Exception {
    output = tmpFolder.newFolder("vh-e2e").toPath();
    HarnessContext.setClusterConfiguration(getUtility().getConfiguration());
  }

  @AfterClass
  public static synchronized void resetClusterConfiguration() {
    HarnessContext.setClusterConfiguration(null);
  }

  private static Path profile(String variant) throws Exception {
    Properties p = HarnessItSupport.profile(getUrl(), output, variant,
      "E2E_" + variant.toUpperCase(java.util.Locale.ROOT));
    p.setProperty("run.id", "e2e-" + variant);
    p.setProperty("run.profile.name", "e2e");
    return HarnessItSupport.write(p, output, "e2e-" + variant);
  }

  /** Runs load, build-index, truth, and run; returns the results of the run. */
  private static RunResult runAll(String variant) throws Exception {
    Path profile = profile(variant);
    HarnessItSupport.Invocation load = HarnessItSupport.phase("load", profile);
    assertTrue(load.out, load.out.contains("loaded VH.DOC_CORPUS_E2E_"));
    HarnessItSupport.Invocation build = HarnessItSupport.phase("build-index", profile);
    assertTrue(build.out, build.out.contains("ACTIVE"));
    assertTrue(build.out, build.out.matches("(?s).*plan check corpus: (\\d+)/\\1 shapes.*"));
    HarnessItSupport.Invocation truth = HarnessItSupport.phase("truth", profile);
    assertTrue(truth.out, truth.out.contains("exact plan recall on corpus 1.0000"));
    HarnessItSupport.Invocation run = HarnessItSupport.phase("run", profile);
    System.out.println(load.out + build.out + truth.out + run.out);
    assertTrue(run.out, run.out.contains("[validity] exact recall 1.000; freshness 1.000"));
    RunResult r = RunResult.read(new RunStore(output).runDir("e2e-" + variant));
    assertEquals(variant, r.variant());
    assertEquals(1.0, r.getDouble("validity.exact_recall"), 1e-9);
    assertEquals(1.0, r.getDouble("validity.freshness"), 1e-9);
    assertEquals("registry", r.getString("run.metrics_mode"));
    assertTrue(r.flags().toString(), r.flags().isEmpty());
    assertFalse(r.frontier().isEmpty());
    for (FrontierPoint p : r.frontier()) {
      assertFalse(p.group() + " " + p.sweep() + " discrepancy: " + p.planClass(),
        p.planDiscrepancy());
      assertTrue(p.group() + " " + p.sweep() + " recall " + p.recall(),
        p.recall() >= 0 && p.recall() <= 1);
      assertEquals(p.group() + " " + p.sweep() + " errors", "0", p.column("errors"));
    }
    List<Map<String, Object>> windows = r.steadyWindows();
    assertEquals(1, windows.size());
    Map<String, Object> w = windows.get(0);
    assertTrue(((Number) w.get("queries")).longValue() > 0);
    assertEquals(0L, ((Number) w.get("query_errors")).longValue());
    assertTrue(((Number) w.get("mutations")).longValue() > 0);
    assertEquals(0L, ((Number) w.get("mutation_errors")).longValue());
    assertTrue(Boolean.TRUE.equals(r.get("restore.complete")));
    assertTrue("footprint", r.footprintBytes("footprint") > 0);
    assertTrue("load footprint", r.footprintBytes("load_footprint") > 0);
    for (String f : new String[] { "frontier.csv", "steady.csv", "throughput.csv", "recall.csv",
      "metrics.csv", "events.csv", "per_query.csv", "summary.txt" }) {
      assertTrue(f, Files.size(r.dir().resolve(f)) > 0);
    }
    return r;
  }

  private static List<FrontierPoint> points(RunResult r, String family, String sweep) {
    List<FrontierPoint> out = new ArrayList<>();
    for (FrontierPoint p : r.frontier()) {
      if (p.family().equals(family) && (sweep == null || p.sweep().equals(sweep))) {
        out.add(p);
      }
    }
    return out;
  }

  @Test
  public void test1Exact() throws Exception {
    RunResult r = runAll("exact");
    for (FrontierPoint p : r.frontier()) {
      assertEquals(p.group(), 1.0, p.recall(), 1e-9);
      assertEquals(p.group(), "EXACT", p.planClass());
    }
  }

  @Test
  public void test2Ivf() throws Exception {
    RunResult r = runAll("ivf");
    List<FrontierPoint> all = new ArrayList<>();
    for (FrontierPoint p : r.frontier()) {
      if (p.sweep().startsWith("probes=all")) {
        all.add(p);
      }
    }
    assertFalse(all.isEmpty());
    for (FrontierPoint p : all) {
      // Probing every list is exhaustive over the index, so it finds the true neighbors.
      assertEquals(p.group(), 1.0, p.recall(), 1e-9);
    }
    FrontierPoint one = points(r, "unfiltered", "probes=1").get(0);
    FrontierPoint full = points(r, "unfiltered", all.get(0).sweep()).get(0);
    assertEquals("IVF_PROBE", one.planClass());
    assertTrue(one.recall() <= full.recall());
    assertTrue(
      "probing one list reads less: " + one.readAmplification() + " vs " + full.readAmplification(),
      one.readAmplification() < full.readAmplification());
    assertNotNull(r.get("build.centroids"));
  }

  @Test
  public void test3Hnsw() throws Exception {
    RunResult r = runAll("hnsw");
    FrontierPoint narrow = points(r, "unfiltered", "ef=16").get(0);
    FrontierPoint wide = points(r, "unfiltered", "ef=256").get(0);
    assertEquals("HNSW_SEARCH", wide.planClass());
    assertTrue("ef=256 recall " + wide.recall(), wide.recall() >= 0.9);
    assertTrue(narrow.recall() <= wide.recall() + 1e-9);
    assertNotNull(r.get("build.segments"));
  }

  @Test
  public void test4Compare() throws Exception {
    HarnessItSupport.Invocation report = HarnessItSupport.harness("report", "--run-id", "e2e-hnsw",
      "--compare", "e2e-ivf", "e2e-exact", "-Drun.output.dir=" + output);
    assertEquals(report.toString(), VectorHarness.OK, report.status);
    System.out.println(report.out);
    assertTrue(report.out, report.out.contains("comparable: identical corpus, panel"));
    assertTrue(report.out, report.out.contains("Paired per-query differences, ivf minus hnsw"));
    assertTrue(report.out, report.out.contains("Cost at recall targets: min p99_ms"));
    Path dir = output.resolve("reports").resolve("e2e-hnsw_vs_e2e-ivf_vs_e2e-exact");
    for (String f : new String[] { "report.txt", "runs.csv", "frontier.csv", "pareto.csv",
      "cost_at_recall.csv", "recall_at_budget.csv", "steady.csv", "paired.csv" }) {
      assertTrue(f, Files.size(dir.resolve(f)) > 0);
    }
    assertTrue(Files.readAllLines(dir.resolve("paired.csv")).size() > 1);
  }

  @Test
  public void test5CompareRefusesOtherCorpus() throws Exception {
    Properties p = HarnessItSupport.profile(getUrl(), output, "exact", "E2E_OTHER");
    p.setProperty("corpus.seed", "8");
    p.setProperty("run.id", "e2e-other");
    Path profile = HarnessItSupport.write(p, output, "e2e-other");
    HarnessItSupport.phase("load", profile);
    HarnessItSupport.phase("build-index", profile);
    HarnessItSupport.phase("run", profile);
    HarnessItSupport.Invocation refused = HarnessItSupport.harness("report", "--run-id",
      "e2e-exact", "--compare", "e2e-other", "-Drun.output.dir=" + output);
    assertEquals(VectorHarness.USAGE, refused.status);
    assertTrue(refused.err, refused.err.contains("corpus fingerprint"));
    HarnessItSupport.Invocation forced = HarnessItSupport.harness("report", "--run-id", "e2e-exact",
      "--compare", "e2e-other", "--force", "-Drun.output.dir=" + output);
    assertEquals(forced.toString(), VectorHarness.OK, forced.status);
    assertTrue(forced.out.contains("NOT COMPARABLE (forced)"));
  }
}
