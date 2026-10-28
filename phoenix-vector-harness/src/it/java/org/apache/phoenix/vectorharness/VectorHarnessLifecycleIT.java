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
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.csv;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.disruptions;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.events;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.loadState;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.metricDeltas;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.number;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.point;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.points;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.rows;
import static org.apache.phoenix.vectorharness.HarnessScenarioSupport.window;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.vectorharness.HarnessScenarioSupport.Scenario;
import org.apache.phoenix.vectorharness.observe.MetricCatalog;
import org.apache.phoenix.vectorharness.report.FrontierPoint;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.apache.phoenix.vectorharness.variant.HnswVariant;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Drives the run-phase lifecycles of the harness command line end to end: streaming ingest after
 * the build, snapshot restores across repeated build-and-run cycles, vector index metrics read from
 * the registry, online and deterministic recall sampling under mutations, disruptions that a single
 * RegionServer allows (region splits and major compactions) during a steady window, the failure
 * paths of the command line, and reports of the latest run. Correctness is asserted from manifests,
 * table state, CSV time series, and the console output, never from latency.
 */
@Category(ParallelStatsDisabledTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VectorHarnessLifecycleIT extends ParallelStatsDisabledIT {

  /** The recall that steady-state samples at the engine's default breadth must reach. */
  static final double SAMPLE_RECALL = 0.8;
  /** The recall that the widest HNSW beam of the sweep must reach on the small corpus. */
  static final double WIDE_BEAM_RECALL = 0.9;

  private static Path output;

  @BeforeClass
  public static synchronized void setUpOutput() throws Exception {
    output = tmpFolder.newFolder("vh-lifecycle").toPath();
    HarnessContext.setClusterConfiguration(getUtility().getConfiguration());
  }

  @AfterClass
  public static synchronized void resetClusterConfiguration() {
    HarnessContext.setClusterConfiguration(null);
  }

  private static Properties profile(String variant, String suffix, String runId) {
    return HarnessScenarioSupport.profile(getUrl(), output, variant, suffix, runId);
  }

  /** Returns the steady-state recall samples of a run, from recall.csv. */
  private static List<Map<String, String>> steadySamples(RunResult r) throws Exception {
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, String> row : csv(r, "recall.csv")) {
      if (row.get("stage").startsWith("steady@")) {
        out.add(row);
      }
    }
    assertFalse("no steady-state recall samples", out.isEmpty());
    return out;
  }

  /** Asserts that every recall sample of the unfiltered family after an offset is high. */
  private static void assertSamplesHigh(List<Map<String, String>> samples, long afterOffset) {
    int n = 0;
    for (Map<String, String> row : samples) {
      if (!"unfiltered".equals(row.get("family")) || number(row.get("offset_ms")) < afterOffset) {
        continue;
      }
      n++;
      assertTrue(row.toString(), number(row.get("queries")) > 0);
      assertTrue(row.toString(), number(row.get("recall")) >= SAMPLE_RECALL);
    }
    assertTrue("no unfiltered samples after " + afterOffset + " in " + samples, n > 0);
  }

  /** The profile of a run that loads half the corpus before the build and streams the rest. */
  private static Properties streaming(String variant, String suffix, String runId) {
    Properties p = profile(variant, suffix, runId);
    p.setProperty("ingest.prebuild.fraction", "0.5");
    p.setProperty("recall.sample.interval", "1s");
    // A throttled stream lasts long enough for the sampler to score it while it runs.
    p.setProperty("ingest.stream.rate.per_sec", "400");
    return p;
  }

  /** Checks a streaming run: the stream section, the load state, and samples during the stream. */
  private static Scenario assertStreamed(Properties p, String suffix) throws Exception {
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    String runId = p.getProperty("run.id");
    assertTrue(s.load.out, s.load.out.contains("_" + suffix + ": 600 rows"));
    assertTrue(s.truth.out, s.truth.out.contains("over 600 rows"));
    assertValid(s);
    RunResult r = s.result;
    assertEquals(600.0, number(r.get("load.prebuild_rows")), 0.0);
    assertEquals(1200.0, number(r.get("load.corpus_rows")), 0.0);
    assertEquals(600.0, number(r.get("stream.from")), 0.0);
    assertEquals(1200.0, number(r.get("stream.to")), 0.0);
    assertEquals(600.0, number(r.get("stream.rows")), 0.0);
    assertTrue(String.valueOf(r.get("stream")), number(r.get("stream.recall_samples")) >= 2);
    assertTrue(s.run.out, s.run.out.contains("[stream] 600 rows"));
    List<Map<String, String>> streamed = rows(csv(r, "recall.csv"), "stage", "stream");
    assertFalse("recall samples during the stream", streamed.isEmpty());
    // The load state now describes the whole corpus, and the run reverted its mutations.
    Map<String, Object> state = loadState(output, suffix);
    assertEquals(1200.0, number(HarnessItSupport.get(state, "load.rows_loaded")), 0.0);
    assertEquals(runId, HarnessItSupport.get(state, "load.streamed_by"));
    assertEquals(Boolean.FALSE, HarnessItSupport.get(state, "load.dirty"));
    return s;
  }

  @Test
  public void test01IvfStreaming() throws Exception {
    Scenario s =
      assertStreamed(streaming("ivf", "LC_IVF_STREAM", "lc-ivf-stream"), "LC_IVF_STREAM");
    // The frontier runs after the stream against the truth of the whole corpus: the streamed
    // rows are in the index, so probing every list finds the true neighbors.
    assertExhaustive(s.result, "probes=all", "corpus");
    assertPlansMatch(s, "corpus");
  }

  @Test
  public void test02HnswStreaming() throws Exception {
    Scenario s =
      assertStreamed(streaming("hnsw", "LC_HNSW_STREAM", "lc-hnsw-stream"), "LC_HNSW_STREAM");
    RunResult r = s.result;
    for (FrontierPoint p : points(r, "corpus", null, "ef=256")) {
      assertTrue(p.group() + " ef=256 recall " + p.recall(), p.recall() >= WIDE_BEAM_RECALL);
    }
    assertEquals("HNSW_SEARCH", point(r, "corpus", "unfiltered", "ef=256").planClass());
    assertPlansMatch(s, "corpus");
  }

  @Test
  public void test03SnapshotRepetitions() throws Exception {
    Properties p = profile("hnsw", "LC_SNAP", "lc-snap");
    p.setProperty("table.snapshot", "true");
    p.setProperty("run.repetitions", "2");
    Path profile = HarnessItSupport.write(p, output, "lc-snap");
    HarnessItSupport.Invocation load = HarnessItSupport.phase("load", profile);
    String snapshot = "vh_vh_doc_corpus_lc_snap_base";
    assertTrue(load.out, load.out.contains("snapshots [" + snapshot + "]"));
    HarnessItSupport.Invocation build = HarnessItSupport.phase("build-index", profile);
    assertTrue(build.out, build.out.contains("restored snapshots [" + snapshot + "]"));
    HarnessItSupport.Invocation run = HarnessItSupport.phase("run", profile);
    System.out.println(load.out + build.out + run.out);
    assertTrue(run.out, run.out.contains("repetition 2 of 2: rebuilding the index"));
    assertTrue(run.out, run.out.contains("repetitions: lc-snap-r1 lc-snap-r2"));
    RunStore store = new RunStore(output);
    RunResult r1 = RunResult.read(store.runDir("lc-snap-r1"));
    RunResult r2 = RunResult.read(store.runDir("lc-snap-r2"));
    assertFalse(Files.exists(store.runDir("lc-snap").resolve(RunStore.MANIFEST)));
    for (RunResult r : new RunResult[] { r1, r2 }) {
      assertEquals(1.0, r.getDouble("validity.exact_recall"), 1e-9);
      assertEquals(1.0, r.getDouble("validity.freshness"), 1e-9);
      assertTrue(r.id(), Boolean.TRUE.equals(r.get("restore.complete")));
      assertTrue(r.id(), number(window(r).get("mutations")) > 0);
      // Each repetition built the index over the restored snapshot.
      assertEquals(r.id(), "[" + snapshot + "]",
        String.valueOf(r.get("build.build.restored_snapshots")));
      assertEquals(1200.0, number(r.get("load.rows_loaded")), 0.0);
    }
    assertEquals("lc-snap-r1", r1.id());
    assertEquals("lc-snap-r2", r2.id());
    assertEquals(r1.fingerprint(), r2.fingerprint());
    assertEquals(r1.panelDigest(), r2.panelDigest());
    // The second build is a new build, not the first one's state.
    assertTrue(
      number(r2.get("build.build.completed_ms")) > number(r1.get("build.build.completed_ms")));
    Map<String, Object> state = loadState(output, "LC_SNAP");
    assertEquals(Boolean.FALSE, HarnessItSupport.get(state, "load.dirty"));
    assertEquals(1200.0, number(HarnessItSupport.get(state, "load.rows_loaded")), 0.0);
    HarnessItSupport.Invocation report = HarnessItSupport.harness("report", "--run-id",
      "lc-snap-r2", "--compare", "lc-snap-r1", "-Drun.output.dir=" + output);
    assertEquals(report.toString(), VectorHarness.OK, report.status);
    assertTrue(report.out, report.out.contains("comparable: identical corpus, panel"));
  }

  /** Returns the vector index metric names that metrics.csv recorded for an index. */
  private static List<String> indexMetrics(RunResult r, String index) throws Exception {
    List<String> out = new ArrayList<>();
    for (Map<String, String> row : csv(r, "metrics.csv")) {
      String a = row.get("attribute");
      if (a.contains("." + index) && !out.contains(a)) {
        out.add(a);
      }
    }
    return out;
  }

  /** Returns the delta of a window for a metric of an index, or NaN. */
  private static double delta(Map<String, Object> deltas, String metric, String index) {
    return number(deltas.get(metric + "." + index));
  }

  @Test
  public void test04IvfMetrics() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, profile("ivf", "LC_IVF_METRICS", "lc-ivf-metrics"));
    assertValid(s);
    RunResult r = s.result;
    assertEquals("registry", r.getString("run.metrics_mode"));
    String index = "VH.DOC_CORPUS_LC_IVF_METRICS_VIDX";
    List<String> recorded = indexMetrics(r, index);
    assertTrue(recorded.toString(), recorded.contains("vectorCentroidAssignments." + index));
    for (String a : recorded) {
      String base = a.substring(0, a.indexOf('.'));
      MetricCatalog.Def def = MetricCatalog.get(base);
      if (def == null) {
        def = MetricCatalog.get(Character.toLowerCase(base.charAt(0)) + base.substring(1));
      }
      assertNotNull(a, def);
      assertEquals(a, MetricCatalog.FAMILY_IVF, def.family());
    }
    Map<String, Object> deltas = metricDeltas(window(r));
    assertFalse("metric_deltas", deltas.isEmpty());
    // Inserts and re-embeddings of the window assign centroids.
    assertTrue(deltas.toString(), delta(deltas, "vectorCentroidAssignments", index) > 0);
    assertTrue(deltas.toString(), number(deltas.get("vectorCentroidAssignments"))
        >= delta(deltas, "vectorCentroidAssignments", index));
    // The build is attributed separately; the steady window starts after it.
    metricDeltasOf(r.get("build.build_metrics"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> metricDeltasOf(Object o) {
    assertTrue(String.valueOf(o), o instanceof Map);
    return (Map<String, Object>) o;
  }

  @Test
  public void test05HnswMetrics() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, profile("hnsw", "LC_HNSW_METRICS", "lc-hnsw-metrics"));
    assertValid(s);
    RunResult r = s.result;
    assertEquals("registry", r.getString("run.metrics_mode"));
    String index = "VH.DOC_CORPUS_LC_HNSW_METRICS_VIDX";
    List<String> recorded = indexMetrics(r, index);
    assertTrue(recorded.toString(), recorded.contains("vectorHnswRegions." + index));
    assertTrue(recorded.toString(), recorded.contains("vectorHnswSearches." + index));
    for (String a : recorded) {
      assertTrue(a, a.startsWith("vectorHnsw") || a.startsWith("VectorHnsw"));
    }
    Map<String, Object> deltas = metricDeltas(window(r));
    assertFalse("metric_deltas", deltas.isEmpty());
    // The window's queries search the graphs and its mutations change them.
    assertTrue(deltas.toString(), delta(deltas, "vectorHnswSearches", index) > 0);
    assertTrue(deltas.toString(), delta(deltas, "vectorHnswChangesApplied", index) > 0);
    // Every region of the index is covered at the end of the run.
    Object end = r.get("metric_snapshots.run-end.values");
    assertTrue(String.valueOf(end), end instanceof Map);
    Map<?, ?> values = (Map<?, ?>) end;
    assertEquals(2.0, number(values.get("vectorHnswRegions." + index)), 0.0);
    assertEquals(0.0, number(values.get("vectorHnswRegionsUncovered." + index)), 0.0);
    // The build is attributed to the index although its metrics did not exist before the build:
    // every region built a base segment.
    Map<String, Object> build = metricDeltasOf(r.get("build.build_metrics"));
    assertTrue(build.toString(), delta(build, "vectorHnswRebuilds", index) >= 2);
    assertTrue(build.toString(), delta(build, "vectorHnswSegmentBytesWritten", index) > 0);
  }

  @Test
  public void test06OnlineSamplingWithMutations() throws Exception {
    Properties p = profile("hnsw", "LC_ONLINE", "lc-online");
    p.setProperty("recall.sample.mode", "online");
    p.setProperty("recall.sample.interval", "1s");
    p.setProperty("mutation.rate.per_sec", "20");
    p.setProperty("mutation.threads", "2");
    p.setProperty("steady_state.duration", "5s");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    RunResult r = s.result;
    assertEquals("online", r.settings().get("recall.sample.mode"));
    Map<String, Object> w = window(r);
    assertTrue(w.toString(), number(w.get("mutations")) > 0);
    assertEquals(w.toString(), 0.0, number(w.get("mutation_errors")), 0.0);
    // Online sampling never closes the write gate; it excludes rows written during a query.
    assertEquals(w.toString(), 0.0, number(w.get("gate_closed_ms")), 0.0);
    List<Map<String, String>> samples = steadySamples(r);
    for (Map<String, String> row : samples) {
      assertEquals(row.toString(), 0.0, number(row.get("gate_closed_ms")), 0.0);
      assertTrue(row.toString(), number(row.get("excluded")) >= 0);
      double ratio = number(row.get("exclusion_ratio"));
      assertTrue(row.toString(), ratio >= 0 && ratio <= 1);
    }
    assertSamplesHigh(samples, 0);
    assertTrue(number(w.get("recall_samples")) >= 2);
  }

  @Test
  public void test07Deterministic() throws Exception {
    Properties p = profile("ivf", "LC_DETERMINISTIC", "lc-deterministic");
    p.setProperty("run.deterministic", "true");
    // Deterministic mode overrides these: one mutation worker and quiesced samples.
    p.setProperty("recall.sample.mode", "online");
    p.setProperty("mutation.threads", "3");
    p.setProperty("recall.sample.interval", "1s");
    Scenario s = HarnessScenarioSupport.runAll(output, p);
    assertValid(s);
    RunResult r = s.result;
    assertEquals(Boolean.TRUE, r.get("run.deterministic"));
    Map<String, Object> w = window(r);
    assertTrue(w.toString(), number(w.get("mutations")) > 0);
    // Quiesced samples close the write gate and need no exclusions.
    assertTrue(w.toString(), number(w.get("gate_closed_ms")) > 0);
    List<Map<String, String>> samples = steadySamples(r);
    for (Map<String, String> row : samples) {
      assertEquals(row.toString(), 0.0, number(row.get("excluded")), 0.0);
    }
    assertSamplesHigh(samples, 0);
    assertExhaustive(r, "probes=all", "corpus");
  }

  /** The profile of a steady window with a split and a major compaction of the data table. */
  private static Properties disrupted(String variant, String suffix, String runId) {
    Properties p = profile(variant, suffix, runId);
    // A split waits for its daughters to open and an HNSW recovery takes a couple of seconds, so
    // the window leaves room for both disruptions and their recoveries on a loaded machine.
    p.setProperty("steady_state.duration", "14s");
    p.setProperty("recall.sample.interval", "1s");
    p.setProperty("disruption.schedule", "1s:split,4s:compact");
    return p;
  }

  /** Checks the disruptions of a run and that queries and recall held up after them. */
  private static void assertDisrupted(Scenario s, String table) throws Exception {
    assertValid(s);
    RunResult r = s.result;
    Map<String, Object> w = window(r);
    List<Map<String, Object>> d = disruptions(w);
    assertEquals(d.toString(), 2, d.size());
    assertEquals("1s:split", d.get(0).get("disruption"));
    assertEquals("4s:compact", d.get(1).get("disruption"));
    for (Map<String, Object> rec : d) {
      assertNull(rec.toString(), rec.get("error"));
      assertNotNull(rec.toString(), rec.get("action"));
    }
    assertTrue(String.valueOf(d.get(0).get("action")).startsWith("split "));
    assertTrue(String.valueOf(d.get(1).get("action")).startsWith("flushed and major compacted"));
    List<Map<String, String>> ev = events(r, "disruption");
    assertEquals(ev.toString(), 2, ev.size());
    assertTrue(ev.get(0).get("detail"), ev.get(0).get("detail").startsWith("1s:split split "));
    assertTrue(ev.get(1).get("detail"), ev.get(1).get("detail").startsWith("4s:compact"));
    for (Map<String, String> e : ev) {
      assertTrue(e.toString(), e.get("stage").startsWith("steady@"));
    }
    assertTrue(r.flags().toString(), r.flags().isEmpty());
    // Queries continued after the last disruption, and recall samples after the first one are
    // still high.
    long first = (long) number(ev.get(0).get("offset_ms"));
    long last = (long) number(ev.get(1).get("offset_ms"));
    long after = 0;
    for (Map<String, String> row : csv(r, "throughput.csv")) {
      if (number(row.get("offset_ms")) > last) {
        after += (long) number(row.get("queries"));
      }
    }
    assertTrue("queries after the last disruption", after > 0);
    assertTrue(w.toString(), number(w.get("queries")) > 0);
    assertTrue(w.toString(), number(w.get("query_errors")) <= number(w.get("queries")) / 4);
    assertSamplesHigh(steadySamples(r), first);
    // The split left one more region.
    try (Admin admin = getUtility().getAdmin()) {
      assertEquals(3, admin.getRegions(TableName.valueOf(table)).size());
    }
  }

  @Test
  public void test08HnswDisruptions() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, disrupted("hnsw", "LC_HNSW_CHAOS", "lc-hnsw-chaos"));
    assertDisrupted(s, "VH.DOC_CORPUS_LC_HNSW_CHAOS");
    // HNSW recovery is read from the metrics: the split daughters were rebuilt.
    Map<String, Object> split = disruptions(window(s.result)).get(0);
    assertTrue(split.toString(), split.get("mttr_seconds") instanceof Number);
    assertFalse(events(s.result, "disruption-recovered").isEmpty());
    assertTrue(events(s.result, "disruption-unrecovered").isEmpty());
    // Each daughter of the split region has its own base segment again.
    String table = "VH.DOC_CORPUS_LC_HNSW_CHAOS";
    HnswVariant.SegmentCount sc = null;
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      long deadline = System.currentTimeMillis() + 60_000;
      do {
        sc = HnswVariant.segments(conn, table, table + "_VIDX");
        if (sc.bases() == sc.regions()) {
          break;
        }
        Thread.sleep(500);
      } while (System.currentTimeMillis() < deadline);
    }
    assertEquals(sc.toMap().toString(), 3, sc.regions());
    assertEquals(sc.toMap().toString(), 3, sc.bases());
  }

  @Test
  public void test09IvfDisruptions() throws Exception {
    Scenario s =
      HarnessScenarioSupport.runAll(output, disrupted("ivf", "LC_IVF_CHAOS", "lc-ivf-chaos"));
    assertDisrupted(s, "VH.DOC_CORPUS_LC_IVF_CHAOS");
  }

  /** Runs a phase with a profile that has one invalid setting; returns the error output. */
  private static String refused(String phase, String key, String value) throws Exception {
    Properties p = profile("ivf", "LC_INVALID", "lc-invalid");
    p.setProperty(key, value);
    Path profile = HarnessItSupport.write(p, output, "lc-invalid");
    HarnessItSupport.Invocation r = HarnessItSupport.harness(phase, "--config", profile.toString());
    assertEquals(key + "=" + value + ": " + r, VectorHarness.USAGE, r.status);
    assertTrue(key + "=" + value + ": " + r.err, r.err.contains(key));
    return r.err;
  }

  @Test
  public void test10InvalidProfile() throws Exception {
    assertTrue(refused("load", "run.variant", "annoy").contains("annoy"));
    refused("load", "query.k.values", "0");
    refused("run", "ingest.prebuild.fraction", "1.5");
    refused("build-index", "ivf.lists", "many");
    assertTrue(refused("run", "disruption.schedule", "30s:split").contains("falls after"));
    refused("load", "recall.sample.mode", "sometimes");
    // A sample size below the list count fails the IVF rules before any DDL.
    refused("load", "ivf.sample_size", "4");
    assertFalse(Files.exists(new RunStore(output).loadState("LC_INVALID")));
    // The command line itself: no phase, an unknown phase, and a phase without its profile.
    assertEquals(VectorHarness.USAGE, HarnessItSupport.harness().status);
    HarnessItSupport.Invocation unknown = HarnessItSupport.harness("benchmark");
    assertEquals(VectorHarness.USAGE, unknown.status);
    assertTrue(unknown.err, unknown.err.contains("benchmark"));
    HarnessItSupport.Invocation noConfig = HarnessItSupport.harness("run");
    assertEquals(VectorHarness.USAGE, noConfig.status);
    assertTrue(noConfig.err, noConfig.err.contains("needs --config"));
    HarnessItSupport.Invocation noFile = HarnessItSupport.harness("load", "--config",
      output.resolve("no-such-profile.properties").toString());
    assertEquals(VectorHarness.USAGE, noFile.status);
    assertTrue(noFile.err, noFile.err.contains("No such profile"));
  }

  @Test
  public void test11RunBeforeLoad() throws Exception {
    Properties p = profile("hnsw", "LC_NOT_LOADED", "lc-not-loaded");
    Path profile = HarnessItSupport.write(p, output, "lc-not-loaded");
    HarnessItSupport.Invocation run =
      HarnessItSupport.harness("run", "--config", profile.toString());
    assertEquals(run.toString(), VectorHarness.FAILED, run.status);
    assertTrue(run.err, run.err.contains("No load state"));
    assertTrue(run.err, run.err.contains("run load first"));
    HarnessItSupport.Invocation build =
      HarnessItSupport.harness("build-index", "--config", profile.toString());
    assertEquals(build.toString(), VectorHarness.FAILED, build.status);
    assertTrue(build.err, build.err.contains("run the load phase first"));
    assertFalse(
      Files.exists(new RunStore(output).runDir("lc-not-loaded").resolve(RunStore.MANIFEST)));
    // Loaded but not built: the run names the missing phase.
    HarnessItSupport.phase("load", profile);
    HarnessItSupport.Invocation unbuilt =
      HarnessItSupport.harness("run", "--config", profile.toString());
    assertEquals(unbuilt.toString(), VectorHarness.FAILED, unbuilt.status);
    assertTrue(unbuilt.err, unbuilt.err.contains("run build-index"));
  }

  @Test
  public void test12ReportLatest() throws Exception {
    // An identifier that sorts before every other run of the class, so that latest must go by
    // the time the run finished, not by name.
    Properties p = profile("exact", "LC_LATEST", "a-latest");
    p.setProperty("corpus.rows", "400");
    p.setProperty("steady_state.duration", "2s");
    Path profile = HarnessItSupport.write(p, output, "a-latest");
    HarnessItSupport.phase("load", profile);
    HarnessItSupport.phase("run", profile);
    HarnessItSupport.Invocation report =
      HarnessItSupport.harness("report", "--run-id", "latest", "-Drun.output.dir=" + output);
    assertEquals(report.toString(), VectorHarness.OK, report.status);
    assertTrue(report.out, report.out.startsWith("Run a-latest\n"));
    Path dir = new RunStore(output).runDir("a-latest").resolve("report");
    assertTrue(report.out, report.out.contains("report written to " + dir));
    assertTrue(Files.size(dir.resolve("report.txt")) > 0);
    // Without --run-id the report is of the latest run too.
    HarnessItSupport.Invocation implicit =
      HarnessItSupport.harness("report", "-Drun.output.dir=" + output);
    assertEquals(implicit.toString(), VectorHarness.OK, implicit.status);
    assertTrue(implicit.out, implicit.out.startsWith("Run a-latest\n"));
    HarnessItSupport.Invocation missing =
      HarnessItSupport.harness("report", "--run-id", "no-such-run", "-Drun.output.dir=" + output);
    assertEquals(missing.toString(), VectorHarness.FAILED, missing.status);
    assertTrue(missing.err, missing.err.contains("No run no-such-run"));
  }
}
