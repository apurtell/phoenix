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

import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.assertExhaustive;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.assertPlansMatch;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.assertValid;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.number;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.point;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.points;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Properties;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.vectorharness.HarnessScenarioSupport.Scenario;
import org.apache.phoenix.vectorharness.report.FrontierPoint;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Drives the harness command line end to end over the table configurations that the design calls
 * for: the BSON DOC_PROFILE layout beside DOC_CORPUS, multi-tenant tables with tenant-scoped and
 * global queries, salted tables, the COSINE and INNER_PRODUCT metrics, and the bulk load path. Each
 * scenario loads its own tables, builds the variant, checks the ground truth against the exact
 * plan, runs, and asserts correctness from the manifest, the frontier, and the output: exact
 * validity, exhaustive IVF probing at recall 1, a wide HNSW beam close to it, and plans that match
 * the variant's expectation.
 */
@Category(ParallelStatsDisabledTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VectorHarnessLayoutsIT extends ParallelStatsDisabledIT {

  /** The recall that the widest HNSW beam of the sweep must reach on the small corpus. */
  static final double WIDE_BEAM_RECALL = 0.9;

  private static Path output;

  @BeforeClass
  public static synchronized void setUpOutput() throws Exception {
    output = tmpFolder.newFolder("vh-layouts").toPath();
    HarnessContext.setClusterConfiguration(getUtility().getConfiguration());
  }

  @AfterClass
  public static synchronized void resetClusterConfiguration() {
    HarnessContext.setClusterConfiguration(null);
  }

  private static Properties profile(String variant, String suffix, String runId) {
    return HarnessScenarioSupport.profile(getUrl(), output, variant, suffix, runId);
  }

  /** Asserts that ef=256 reaches a high recall on every family of a layout. */
  private static void assertWideBeam(RunResult r, String layout) {
    for (FrontierPoint p : points(r, layout, null, "ef=256")) {
      assertTrue(p.group() + " ef=256 recall " + p.recall(), p.recall() >= WIDE_BEAM_RECALL);
      assertEquals(p.group(), p.column("expected_plan"), p.planClass());
    }
    assertEquals("HNSW_SEARCH", point(r, layout, "unfiltered", "ef=256").planClass());
    FrontierPoint narrow = point(r, layout, "unfiltered", "ef=16");
    FrontierPoint wide = point(r, layout, "unfiltered", "ef=256");
    assertTrue(narrow.recall() <= wide.recall() + 1e-9);
  }

  @Test
  public void test01IvfCorpusAndProfile() throws Exception {
    Properties p = profile("ivf", "LY_IVF_BOTH", "ly-ivf-both");
    p.setProperty("table.layouts", "corpus,profile");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertTrue(s.load.out, s.load.out.contains("loaded VH.DOC_PROFILE_LY_IVF_BOTH: 1200 rows"));
    assertTrue(s.build.out, s.build.out
      .contains("VH.DOC_PROFILE_LY_IVF_BOTH_VIDX on" + " VH.DOC_PROFILE_LY_IVF_BOTH ACTIVE"));
    assertTrue(s.truth.out, s.truth.out.contains("exact plan recall on corpus 1.0000"));
    assertTrue(s.truth.out, s.truth.out.contains("exact plan recall on profile 1.0000"));
    assertValid(s);
    assertPlansMatch(s, "corpus", "profile");
    RunResult r = s.result;
    assertTrue(String.valueOf(r.get("build.indexes.profile.ddl")),
      String.valueOf(r.get("build.indexes.profile.ddl"))
        .contains("BSON_VECTOR_VALUE(PROFILE, 'search.embedding', 16)"));
    // Probing every list is exhaustive over either index, whatever the layout of the vector.
    assertExhaustive(r, "probes=all", "corpus", "profile");
    for (String layout : new String[] { "corpus", "profile" }) {
      FrontierPoint one = point(r, layout, "unfiltered", "probes=1");
      assertEquals("IVF_PROBE", one.planClass());
      assertTrue(one.recall() <= 1.0);
    }
  }

  @Test
  public void test02HnswCorpusAndProfile() throws Exception {
    Properties p = profile("hnsw", "LY_HNSW_BOTH", "ly-hnsw-both");
    p.setProperty("table.layouts", "corpus,profile");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertTrue(s.truth.out, s.truth.out.contains("exact plan recall on profile 1.0000"));
    assertValid(s);
    assertPlansMatch(s, "corpus", "profile");
    RunResult r = s.result;
    assertWideBeam(r, "corpus");
    assertWideBeam(r, "profile");
    // The two layouts index the same vectors, so a wide beam finds about the same neighbors.
    double corpus = point(r, "corpus", "unfiltered", "ef=256").recall();
    double profile = point(r, "profile", "unfiltered", "ef=256").recall();
    assertEquals("ef=256 recall, corpus " + corpus + " profile " + profile, corpus, profile, 0.1);
  }

  /** The profile of a multi-tenant table of four tenants, with tenant-scoped and global shapes. */
  private static Properties multiTenant(String variant, String suffix, String runId) {
    Properties p = profile(variant, suffix, runId);
    p.setProperty("table.multi_tenant", "true");
    p.setProperty("corpus.tenants", "4");
    p.setProperty("query.shapes", "unfiltered,covered_filter,tenant_scoped,global,distance_bound");
    return p;
  }

  @Test
  public void test03IvfMultiTenant() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, multiTenant("ivf", "LY_IVF_MT", "ly-ivf-mt"));
    assertValid(s);
    RunResult r = s.result;
    assertTrue(String.valueOf(r.get("settings")),
      "true".equals(r.settings().get("table.multi_tenant")));
    // A tenant connection probes the centroid ranges under its tenant prefix.
    for (FrontierPoint p : points(r, "corpus", "tenant_scoped", null)) {
      assertEquals(p.sweep(), "IVF_PROBE", p.planClass());
      assertFalse(p.sweep(), p.planDiscrepancy());
    }
    for (FrontierPoint p : points(r, "corpus", "covered_filter@0.25", null)) {
      assertEquals(p.sweep(), "IVF_PROBE", p.planClass());
    }
    // A global connection has no tenant prefix, so it scans the whole index, and so do the
    // shapes that carry no tenant; the whole-index scan is exact at every probe count.
    for (String family : new String[] { "global", "unfiltered", "distance_bound@k=10" }) {
      assertFalse(family, points(r, "corpus", family, null).isEmpty());
      for (FrontierPoint p : points(r, "corpus", family, null)) {
        assertEquals(p.group() + " " + p.sweep(), "IVF_FULL_SCAN", p.planClass());
        assertFalse(p.sweep(), p.planDiscrepancy());
        assertEquals(p.group() + " " + p.sweep(), 1.0, p.recall(), 1e-9);
      }
    }
    assertExhaustive(r, "probes=all", "corpus");
    assertPlansMatch(s, "corpus");
  }

  @Test
  public void test04ExactMultiTenant() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, multiTenant("exact", "LY_EXACT_MT", "ly-exact-mt"));
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    assertFalse(points(r, "corpus", "tenant_scoped", null).isEmpty());
    assertFalse(points(r, "corpus", "global", null).isEmpty());
    for (FrontierPoint p : r.frontier()) {
      assertEquals(p.group(), 1.0, p.recall(), 1e-9);
      assertEquals(p.group(), "EXACT", p.planClass());
    }
  }

  @Test
  public void test05ExactSalted() throws Exception {
    Properties p = profile("exact", "LY_EXACT_SALT", "ly-exact-salt");
    p.setProperty("table.salt_buckets", "4");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    assertEquals(4.0, number(r.get("load.salt_buckets")), 0.0);
    assertEquals(0.0, number(r.get("load.split_points")), 0.0);
    for (FrontierPoint f : r.frontier()) {
      assertEquals(f.group(), 1.0, f.recall(), 1e-9);
    }
  }

  @Test
  public void test06HnswSalted() throws Exception {
    Properties p = profile("hnsw", "LY_HNSW_SALT", "ly-hnsw-salt");
    p.setProperty("table.salt_buckets", "4");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    // One base segment per salt bucket region.
    assertEquals(4.0, number(r.get("build.indexes.corpus.segments.regions")), 0.0);
    assertEquals(4.0, number(r.get("build.indexes.corpus.segments.base_segments")), 0.0);
    assertWideBeam(r, "corpus");
  }

  @Test
  public void test07IvfCosine() throws Exception {
    Properties p = profile("ivf", "LY_IVF_COS", "ly-ivf-cos");
    p.setProperty("index.metric", "COSINE");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    assertEquals("COSINE", r.getString("corpus.metric"));
    assertTrue(r.getString("build.indexes.corpus.ddl"),
      r.getString("build.indexes.corpus.ddl").contains("metric = 'COSINE'"));
    assertExhaustive(r, "probes=all", "corpus");
  }

  @Test
  public void test08IvfInnerProduct() throws Exception {
    Properties p = profile("ivf", "LY_IVF_IP", "ly-ivf-ip");
    p.setProperty("index.metric", "INNER_PRODUCT");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    assertEquals("INNER_PRODUCT", r.getString("corpus.metric"));
    assertTrue(r.getString("build.indexes.corpus.ddl"),
      r.getString("build.indexes.corpus.ddl").contains("metric = 'INNER_PRODUCT'"));
    assertExhaustive(r, "probes=all", "corpus");
  }

  @Test
  public void test09HnswCosine() throws Exception {
    Properties p = profile("hnsw", "LY_HNSW_COS", "ly-hnsw-cos");
    p.setProperty("index.metric", "COSINE");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    assertPlansMatch(s, "corpus");
    RunResult r = s.result;
    assertEquals("COSINE", r.getString("corpus.metric"));
    assertTrue(r.getString("build.indexes.corpus.ddl"),
      r.getString("build.indexes.corpus.ddl").contains("metric = 'COSINE'"));
    assertWideBeam(r, "corpus");
  }

  @Test
  public void test10HnswRefusesInnerProduct() throws Exception {
    Properties p = profile("hnsw", "LY_HNSW_IP", "ly-hnsw-ip");
    p.setProperty("index.metric", "INNER_PRODUCT");
    Path profile = HarnessItSupport.write(p, output, "ly-hnsw-ip");
    HarnessItSupport.Invocation r =
      HarnessItSupport.harness("load", "--config", profile.toString());
    assertEquals(r.toString(), VectorHarness.USAGE, r.status);
    assertTrue(r.err, r.err.contains("index.metric"));
    assertTrue(r.err, r.err.contains("INNER_PRODUCT"));
    // The profile is refused before any DDL, so no table and no load state exist.
    assertFalse(java.nio.file.Files.exists(
      new org.apache.phoenix.vectorharness.result.RunStore(output).loadState("LY_HNSW_IP")));
    // The same refusal through a command line override of an IVF profile.
    Path ivf = HarnessItSupport.write(profile("ivf", "LY_HNSW_IP", "ly-hnsw-ip-ivf"), output,
      "ly-hnsw-ip-ivf");
    HarnessItSupport.Invocation o = HarnessItSupport.harness("load", "--config", ivf.toString(),
      "-Drun.variant=hnsw", "-Dindex.metric=INNER_PRODUCT");
    assertEquals(o.toString(), VectorHarness.USAGE, o.status);
    assertTrue(o.err, o.err.contains("index.metric"));
  }

  @Test
  public void test11BulkLoad() throws Exception {
    Properties p = profile("hnsw", "LY_HNSW_BULK", "ly-hnsw-bulk");
    p.setProperty("table.layouts", "corpus,profile");
    p.setProperty("ingest.mode", "bulkload");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    RunResult r = s.result;
    assertEquals("bulkload", r.getString("ingest.mode"));
    // DOC_PROFILE goes through CsvBulkLoadTool; DOC_CORPUS, whose vector column the bulk load
    // tools cannot read, falls back to JDBC.
    assertEquals(0.0, number(r.get("ingest.DOC_PROFILE.tool_status")), 0.0);
    assertEquals(1200.0, number(r.get("ingest.DOC_PROFILE.rows")), 0.0);
    assertEquals(1200.0, number(r.get("ingest.DOC_CORPUS.rows")), 0.0);
    assertTrue(s.truth.out, s.truth.out.contains("exact plan recall on profile 1.0000"));
    // The exact plan agrees with the client-side truth, so every bulk loaded document holds its
    // vector and attributes.
    assertValid(s);
    assertPlansMatch(s, "corpus", "profile");
    assertEquals(2.0, number(r.get("build.indexes.profile.segments.base_segments")), 0.0);
    assertWideBeam(r, "profile");
    assertWideBeam(r, "corpus");
  }
}
