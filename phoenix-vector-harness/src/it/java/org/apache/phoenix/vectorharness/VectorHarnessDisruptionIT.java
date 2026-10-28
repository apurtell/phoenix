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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.vectorharness.config.Disruption;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.report.FrontierPoint;
import org.apache.phoenix.vectorharness.report.RunResult;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.Json;
import org.apache.phoenix.vectorharness.result.RunStore;
import org.apache.phoenix.vectorharness.workload.ChaosInjector;
import org.apache.phoenix.vectorharness.workload.TableNames;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test verifying steady-state execution under chaos injection (region moves, splits,
 * compactions, merges, and RegionServer restarts) on a multi-node minicluster for both IVF and
 * HNSW. Checks that queries and mutations continue, freshness probes succeed, recall recovers, and
 * mutations are reverted cleanly.
 */
@Category(NeedsOwnMiniClusterTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VectorHarnessDisruptionIT extends BaseTest {

  private static final Logger LOG = LoggerFactory.getLogger(VectorHarnessDisruptionIT.class);

  /** Disruption schedule for region operations (moves, splits, compactions, merges). */
  static final String REGION_SCHEDULE = "3s:move,9s:split,15s:compact,21s:merge";
  static final String REGION_WINDOW = "32s";
  /**
   * Disruption schedule for RegionServer restart. The graceful restart moves each region off the
   * server and back, and here it takes more than a minute. The window is shorter than the restart.
   * The queries run until the restart cycle and its recovery end, but no write that is due after
   * the window end runs. The recovery check waits for the moved regions to open their HNSW indexes,
   * and the queries cause these opens.
   */
  static final String RESTART_SCHEDULE = "4s:restart";
  static final String RESTART_WINDOW = "30s";
  static final String STEADY_STAGE = "steady@10";
  static final long RESTART_TIMEOUT_MS = 180_000;
  /** Probe count for unhinted IVF queries (probes all lists). */
  static final String FULL_PROBES = "64";
  /** Search beam for unhinted HNSW queries. */
  static final String WIDE_EF = "256";
  /** Maximum allowed failure fraction during region disruption windows. */
  static final double MAX_REGION_ERROR_SHARE = 0.1;
  /** Maximum allowed failure fraction during RegionServer restart windows. */
  static final double MAX_RESTART_ERROR_SHARE = 0.5;

  private static String tmpDir;
  private static Path output;
  private static MiniClusterRestart restarts;

  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    tmpDir = System.getProperty("java.io.tmpdir");
    Map<String, String> serverProps = new HashMap<>();
    serverProps.put(QueryServices.TESTS_MINI_CLUSTER_NUM_REGION_SERVERS, "2");
    serverProps.put(BaseScannerRegionObserverConstants.PHOENIX_MAX_LOOKBACK_AGE_CONF_KEY,
      Integer.toString(60 * 60));
    serverProps.put(QueryServices.USE_STATS_FOR_PARALLELIZATION, Boolean.FALSE.toString());
    Map<String, String> clientProps = new HashMap<>();
    clientProps.put(HConstants.HBASE_CLIENT_RETRIES_NUMBER, "10");
    clientProps.put(HConstants.HBASE_CLIENT_PAUSE, "100");
    clientProps.put(BaseScannerRegionObserverConstants.PHOENIX_MAX_LOOKBACK_AGE_CONF_KEY,
      Integer.toString(60 * 60));
    clientProps.put(QueryServices.USE_STATS_FOR_PARALLELIZATION, Boolean.FALSE.toString());
    clientProps.put(QueryServices.VECTOR_PROBE_COUNT_ATTRIB, FULL_PROBES);
    clientProps.put(QueryServices.HNSW_EF_SEARCH_ATTRIB, WIDE_EF);
    setUpTestDriver(new ReadOnlyProps(serverProps.entrySet().iterator()),
      new ReadOnlyProps(clientProps.entrySet().iterator()));
    output = tmpFolder.newFolder("vh-disruption").toPath();
    HarnessContext.setClusterConfiguration(getUtility().getConfiguration());
    restarts = new MiniClusterRestart();
    ChaosInjector.setClusterControl(restarts);
    // Disable balancer so only scheduled disruptions move regions.
    getUtility().getAdmin().balancerSwitch(false, true);
  }

  @AfterClass
  public static synchronized void tearDown() throws Exception {
    ChaosInjector.setClusterControl(null);
    HarnessContext.setClusterConfiguration(null);
    try {
      tearDownMiniCluster(0);
    } finally {
      if (tmpDir != null) {
        System.setProperty("java.io.tmpdir", tmpDir);
      }
    }
  }

  @Test
  public void test1Ivf() throws Exception {
    Runs runs = runAll("ivf");
    for (RunResult r : new RunResult[] { runs.regions, runs.restart }) {
      String lists = null;
      for (FrontierPoint p : r.frontier()) {
        if (p.sweep().startsWith("probes=all(")) {
          lists = p.sweep().substring("probes=all(".length(), p.sweep().indexOf(')'));
          assertEquals(r.id() + " " + p.group(), 1.0, p.recall(), 1e-9);
        }
      }
      assertNotNull(r.id() + " has no full probe points", lists);
      assertEquals("probes=default(" + lists + ")", window(r).get("sweep"));
      for (Map<String, String> s : samplesAfterDisruptions(r)) {
        assertEquals(r.id() + " sample " + s, 1.0, Double.parseDouble(s.get("recall")), 1e-9);
        if ("unfiltered".equals(s.get("family"))) {
          assertEquals(r.id() + " sample " + s, "IVF_PROBE", s.get("plan_class"));
        }
      }
    }
  }

  @Test
  public void test2Hnsw() throws Exception {
    Runs runs = runAll("hnsw");
    for (RunResult r : new RunResult[] { runs.regions, runs.restart }) {
      for (FrontierPoint p : r.frontier()) {
        if (p.family().equals("unfiltered") && p.sweep().equals("ef=" + WIDE_EF)) {
          assertEquals("HNSW_SEARCH", p.planClass());
          assertTrue(r.id() + " ef=" + WIDE_EF + " recall " + p.recall(), p.recall() >= 0.9);
        }
      }
      assertEquals("ef=default(" + WIDE_EF + ")", window(r).get("sweep"));
      for (Map<String, String> s : samplesAfterDisruptions(r)) {
        assertTrue(r.id() + " sample " + s, Double.parseDouble(s.get("recall")) >= 0.9);
        if ("unfiltered".equals(s.get("family"))) {
          assertEquals(r.id() + " sample " + s, "HNSW_SEARCH", s.get("plan_class"));
        }
      }
      for (Map<String, Object> d : disruptions(r)) {
        assertTrue(r.id() + " " + d, d.get("mttr_seconds") instanceof Number);
      }
      assertTrue(events(r, "disruption-unrecovered").isEmpty());
      assertHealthyInspections(r);
    }
    assertFalse(events(runs.regions, "region-split").isEmpty());
    assertFalse(events(runs.regions, "region-merge").isEmpty());
    assertFalse(events(runs.regions, "region-move").isEmpty());
    assertFalse(events(runs.restart, "region-move").isEmpty());
    List<Map<String, String>> regions = CsvTable.read(runs.regions.dir().resolve("lifecycle.csv"));
    int most = 0;
    for (Map<String, String> row : regions) {
      most = Math.max(most, Integer.parseInt(row.get("regions")));
    }
    assertEquals("the split made five regions", 5, most);
    assertEquals("the merge made four regions again", "4",
      regions.get(regions.size() - 1).get("regions"));
    boolean oneServer = false;
    for (Map<String, String> row : CsvTable.read(runs.restart.dir().resolve("lifecycle.csv"))) {
      oneServer |= STEADY_STAGE.equals(row.get("stage")) && "1".equals(row.get("servers"));
    }
    assertTrue("the restart moved every region to the surviving server", oneServer);
  }

  /** Holds scenario run results for a variant. */
  private static final class Runs {
    final RunResult regions;
    final RunResult restart;

    Runs(RunResult regions, RunResult restart) {
      this.regions = regions;
      this.restart = restart;
    }
  }

  /**
   * Loads the corpus across four regions, builds the index, and runs the steady state under region
   * disruptions and RS restart.
   */
  private static Runs runAll(String variant) throws Exception {
    String suffix = "DIS_" + variant.toUpperCase(Locale.ROOT);
    Properties p = HarnessItSupport.profile(getUrl(), output, variant, suffix);
    p.setProperty("run.profile.name", "disruption");
    p.setProperty("table.regions", "4");
    p.setProperty("steady_state.warmup", "2s");
    p.setProperty("recall.sample.interval", "3s");
    p.setProperty("query.ivf.probe.values", "1,all");
    p.setProperty("query.hnsw.ef.values", "16," + WIDE_EF);
    if (variant.equals("ivf")) {
      p.setProperty(QueryServices.VECTOR_PROBE_COUNT_ATTRIB, FULL_PROBES);
    } else {
      p.setProperty(QueryServices.HNSW_EF_SEARCH_ATTRIB, WIDE_EF);
    }
    Path profile = HarnessItSupport.write(p, output, "disruption-" + variant);
    HarnessItSupport.Invocation load = HarnessItSupport.phase("load", profile);
    assertTrue(load.out, load.out.contains("loaded VH.DOC_CORPUS_" + suffix));
    HarnessItSupport.Invocation build = HarnessItSupport.phase("build-index", profile);
    assertTrue(build.out, build.out.contains("ACTIVE"));
    if (variant.equals("ivf")) {
      productionRepairAge(new TableNames("VH", suffix).fullIndex(TableLayout.CORPUS));
    }
    int restartsBefore = restarts.restarts().size();
    RunResult regions = disruptedRun(variant, suffix, profile, "dis-" + variant + "-regions",
      REGION_SCHEDULE, REGION_WINDOW, false);
    RunResult restart = disruptedRun(variant, suffix, profile, "dis-" + variant + "-restart",
      RESTART_SCHEDULE, RESTART_WINDOW, true);
    assertEquals(restarts.restarts().toString(), restartsBefore + 1, restarts.restarts().size());
    assertEquals("the replacement RegionServer joined", 2,
      getUtility().getHBaseCluster().getLiveRegionServerThreads().size());
    return new Runs(regions, restart);
  }

  /**
   * Configures production unverified row retention age on the IVF index table so read repair scans
   * do not delete in-flight unverified index rows during concurrent disruptions.
   */
  private static void productionRepairAge(String index) throws Exception {
    try (Admin admin = getUtility().getAdmin()) {
      TableName tn = TableName.valueOf(index);
      admin.modifyTable(TableDescriptorBuilder.newBuilder(admin.getDescriptor(tn))
        .setValue(QueryServices.GLOBAL_INDEX_ROW_AGE_THRESHOLD_TO_DELETE_MS_ATTRIB,
          Long.toString(QueryServicesOptions.DEFAULT_GLOBAL_INDEX_ROW_AGE_THRESHOLD_TO_DELETE_MS))
        .build());
    }
  }

  /** Runs the run phase with a disruption schedule and asserts stability invariants. */
  private static RunResult disruptedRun(String variant, String suffix, Path profile, String runId,
    String schedule, String window, boolean restart) throws Exception {
    HarnessItSupport.Invocation run = HarnessItSupport.phase("run", profile, "--run-id", runId,
      "-Ddisruption.schedule=" + schedule, "-Dsteady_state.duration=" + window);
    System.out.println(run.out);
    RunResult r = RunResult.read(new RunStore(output).runDir(runId));
    System.out.println(observations(r));
    assertEquals(variant, r.variant());
    assertEquals(2.0, r.getDouble("cluster.region_servers"), 0);
    for (String f : r.flags()) {
      assertFalse(f, f.contains("vectorHnswMaintenanceStalled"));
      assertFalse(f, f.contains("vectorHnswOpenFailures"));
    }
    assertTrue(r.flags().toString(), r.flags().isEmpty());
    assertEquals(1.0, r.getDouble("validity.exact_recall"), 1e-9);
    assertEquals(1.0, r.getDouble("validity.freshness"), 1e-9);
    for (FrontierPoint p : r.frontier()) {
      assertFalse(p.group() + " " + p.sweep() + " discrepancy: " + p.planClass(),
        p.planDiscrepancy());
    }

    List<Disruption> scheduled = Disruption.parseSchedule(schedule);
    List<Map<String, Object>> applied = disruptions(r);
    assertEquals(applied.toString(), scheduled.size(), applied.size());
    List<Map<String, String>> logged = events(r, "disruption");
    assertEquals(logged.toString(), scheduled.size(), logged.size());
    for (int i = 0; i < scheduled.size(); i++) {
      Disruption d = scheduled.get(i);
      Map<String, Object> record = applied.get(i);
      assertEquals(d.toString(), record.get("disruption"));
      assertNull(record.toString(), record.get("error"));
      String action = String.valueOf(record.get("action"));
      assertTrue(record.toString(), action.startsWith(actionPrefix(d.kind())));
      Map<String, String> event = logged.get(i);
      assertEquals(d + " " + action, event.get("detail"));
      assertEquals(STEADY_STAGE, event.get("stage"));
    }

    Map<String, Object> w = window(r);
    assertTrue(w.toString(), ((List<?>) w.get("skipped_disruptions")).isEmpty());
    if (restart) {
      // The restart outlasts the window, so the reads run until the restart and its recovery end.
      double extension = ((Number) w.get("extension_seconds")).doubleValue();
      assertTrue(w.toString(), extension > 0);
      assertEquals(scheduled.get(scheduled.size() - 1).toString(), w.get("extended_by"));
      assertTrue(w.toString(), ((Number) w.get("measured_seconds")).doubleValue()
          >= ((Number) w.get("planned_seconds")).doubleValue() + extension);
      assertExtensionOnlyReads(r, w);
    }
    double maxShare = restart ? MAX_RESTART_ERROR_SHARE : MAX_REGION_ERROR_SHARE;
    assertTrue(runId + " query error share", errorShare(w, "queries", "query_errors") <= maxShare);
    assertTrue(runId + " mutation error share",
      errorShare(w, "mutations", "mutation_errors") <= maxShare);
    if (!restart) {
      for (Map<String, Object> g : groups(w)) {
        assertEquals(runId + " " + g, 0L, ((Number) g.get("errors")).longValue());
      }
    }
    assertTrue(events(r, "plan-transition").toString(), events(r, "plan-transition").isEmpty());
    assertTrue(runId + " " + events(r, "freshness-miss"), events(r, "freshness-miss").isEmpty());
    assertFalse("no recall samples after the disruptions of " + runId,
      samplesAfterDisruptions(r).isEmpty());

    assertEquals(Boolean.TRUE, r.get("restore.complete"));
    Map<String, Object> load = Json.readMap(new RunStore(output).loadState(suffix));
    assertEquals(Boolean.FALSE, Json.path(load, "load.dirty"));
    return r;
  }

  /**
   * Asserts that the queries continue in the extension and that the writes stop before it. The
   * ticker starts a new sample each second, so the check starts one second after the writes end.
   */
  private static void assertExtensionOnlyReads(RunResult r, Map<String, Object> w)
    throws IOException {
    long writesEnd = ((Number) w.get("writes_end_offset_ms")).longValue();
    long readsEnd = ((Number) w.get("end_offset_ms")).longValue();
    assertTrue(w.toString(), writesEnd < readsEnd);
    long queries = 0;
    long writes = 0;
    int samples = 0;
    for (Map<String, String> row : CsvTable.read(r.dir().resolve("throughput.csv"))) {
      if (
        STEADY_STAGE.equals(row.get("stage"))
          && (long) Double.parseDouble(row.get("offset_ms")) > writesEnd + 1000
      ) {
        samples++;
        queries += (long) Double.parseDouble(row.get("queries"));
        writes += (long) Double.parseDouble(row.get("mutations"))
          + (long) Double.parseDouble(row.get("mutation_errors"));
      }
    }
    System.out.printf(Locale.ROOT,
      "extension: writes end at %d ms, reads end at %d ms; %d samples, %d queries,"
        + " %d mutations after the writes end%n",
      writesEnd, readsEnd, samples, queries, writes);
    assertTrue("queries in the extension", queries > 0);
    assertEquals("mutations in the extension", 0, writes);
  }

  private static String actionPrefix(Disruption.Kind kind) {
    switch (kind) {
      case MOVE:
        return "moved ";
      case SPLIT:
        return "split ";
      case MERGE:
        return "merged ";
      case COMPACT:
        return "flushed and major compacted ";
      case RESTART:
        return "restarted ";
      default:
        throw new IllegalArgumentException(kind.toString());
    }
  }

  /** Calculates the fraction of failed operations in a window. */
  private static double errorShare(Map<String, Object> w, String ok, String failed) {
    long n = ((Number) w.get(ok)).longValue();
    long e = ((Number) w.get(failed)).longValue();
    assertTrue(ok + " of " + w, n > 0);
    return (double) e / (n + e);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> groups(Map<String, Object> w) {
    Object g = w.get("groups");
    assertTrue(String.valueOf(g), g instanceof List);
    return (List<Map<String, Object>>) g;
  }

  private static Map<String, Object> window(RunResult r) {
    List<Map<String, Object>> windows = r.steadyWindows();
    assertEquals(1, windows.size());
    Map<String, Object> w = windows.get(0);
    assertEquals(STEADY_STAGE, w.get("stage"));
    return w;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> disruptions(RunResult r) {
    Object d = window(r).get("disruptions");
    assertTrue(String.valueOf(d), d instanceof List);
    return (List<Map<String, Object>>) d;
  }

  private static List<Map<String, String>> events(RunResult r, String kind) {
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, String> e : r.events()) {
      if (kind.equals(e.get("kind"))) {
        out.add(e);
      }
    }
    return out;
  }

  /** Returns steady-state recall samples recorded after the final disruption. */
  private static List<Map<String, String>> samplesAfterDisruptions(RunResult r) {
    long last = 0;
    for (Map<String, String> e : events(r, "disruption")) {
      last = Math.max(last, Long.parseLong(e.get("offset_ms")));
    }
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, String> s : r.recallSamples()) {
      if (
        STEADY_STAGE.equals(s.get("stage")) && Long.parseLong(s.get("offset_ms")) > last
          && Long.parseLong(s.get("queries")) > 0
      ) {
        out.add(s);
      }
    }
    return out;
  }

  /**
   * Verifies HNSW index fsck stage boundary inspection reports for full coverage and lack of
   * overflow.
   */
  @SuppressWarnings("unchecked")
  private static void assertHealthyInspections(RunResult r) throws IOException {
    Object all = r.get("lifecycle.hnsw_inspections");
    assertTrue(String.valueOf(all), all instanceof Map);
    Map<String, Object> byLabel = (Map<String, Object>) all;
    for (String label : new String[] { "run-start", "frontier-start", STEADY_STAGE + "-start",
      "run-end" }) {
      Object perIndex = byLabel.get(label);
      assertTrue(label + ": " + perIndex,
        perIndex instanceof Map && !((Map<String, Object>) perIndex).isEmpty());
      for (Map.Entry<String, Object> e : ((Map<String, Object>) perIndex).entrySet()) {
        String where = r.id() + " " + label + " " + e.getKey() + ": " + e.getValue();
        assertTrue(where, e.getValue() instanceof Map);
        Map<String, Object> commands = (Map<String, Object>) e.getValue();
        for (String cmd : new String[] { "list", "coverage", "delta" }) {
          assertTrue(where, commands.get(cmd) instanceof Map);
        }
        Map<String, Object> coverage = (Map<String, Object>) commands.get("coverage");
        assertTrue(where, ((Number) coverage.get("regions")).intValue() > 0);
        assertEquals(where, ((Number) coverage.get("regions")).intValue(),
          ((Number) coverage.get("covered")).intValue());
        Map<String, Object> delta = (Map<String, Object>) commands.get("delta");
        assertEquals(where, 0, ((Number) delta.get("overflowed")).intValue());
      }
    }
    List<Path> reports;
    try (Stream<Path> files = Files.list(r.dir())) {
      reports = files.filter(f -> f.getFileName().toString().startsWith("fsck-")
        && f.getFileName().toString().endsWith(".json")).collect(Collectors.toList());
    }
    assertFalse(r.id() + " has no inspect reports", reports.isEmpty());
    for (Path f : reports) {
      Map<String, Object> report = Json.readMap(f);
      assertEquals(f + ": " + report.get("findings"), Boolean.FALSE, report.get("errors"));
    }
  }

  /** Formats observed disruption behavior for test diagnostic logs. */
  private static String observations(RunResult r) {
    StringBuilder sb = new StringBuilder("[observed] ").append(r.id()).append('\n');
    Map<String, Object> w = window(r);
    sb.append("  queries=").append(w.get("queries")).append(" query_errors=")
      .append(w.get("query_errors")).append(" mutations=").append(w.get("mutations"))
      .append(" mutation_errors=").append(w.get("mutation_errors")).append(" freshness=")
      .append(w.get("freshness")).append(" sweep=").append(w.get("sweep")).append('\n');
    for (Map<String, Object> d : disruptions(r)) {
      sb.append("  disruption ").append(d).append('\n');
    }
    for (Map<String, Object> g : groups(w)) {
      if (((Number) g.get("errors")).longValue() > 0) {
        sb.append("  failed ").append(g.get("family")).append(": ").append(g.get("errors"))
          .append(" of ").append(g.get("queries")).append(", last ").append(g.get("last_error"))
          .append('\n');
      }
    }
    Map<String, List<String>> recall = new TreeMap<>();
    for (Map<String, String> s : samplesAfterDisruptions(r)) {
      recall.computeIfAbsent(s.get("family"), k -> new ArrayList<>())
        .add(s.get("recall") + "/" + s.get("plan_class"));
    }
    sb.append("  recall after the disruptions ").append(recall).append('\n');
    for (String kind : new String[] { "freshness-miss", "freshness-error" }) {
      for (Map<String, String> e : events(r, kind)) {
        sb.append("  ").append(kind).append(" +").append(e.get("offset_ms")).append(" ms: ")
          .append(e.get("detail")).append('\n');
      }
    }
    Map<String, Integer> kinds = new LinkedHashMap<>();
    for (Map<String, String> e : r.events()) {
      kinds.merge(e.get("kind"), 1, Integer::sum);
    }
    sb.append("  events ").append(kinds).append('\n');
    sb.append("  flags ").append(r.flags());
    return sb.toString();
  }

  /** ClusterControl implementation that restarts a regionserver on the minicluster. */
  static final class MiniClusterRestart implements ChaosInjector.ClusterControl {
    private final List<String> restarts = new CopyOnWriteArrayList<>();

    List<String> restarts() {
      return restarts;
    }

    @Override
    public ServerName restart(Admin admin, ServerName server) throws Exception {
      MiniHBaseCluster cluster = getUtility().getHBaseCluster();
      long deadline = System.currentTimeMillis() + RESTART_TIMEOUT_MS;
      JVMClusterUtil.RegionServerThread stopped = null;
      for (JVMClusterUtil.RegionServerThread t : cluster.getLiveRegionServerThreads()) {
        if (t.getRegionServer().getServerName().equals(server)) {
          stopped = t;
        }
      }
      if (stopped == null) {
        throw new IllegalStateException(
          "No live RegionServer " + server + " in " + cluster.getLiveRegionServerThreads());
      }
      LOG.info("Stopping RegionServer {}", server);
      stopped.getRegionServer().stop("vector harness disruption");
      stopped.join(RESTART_TIMEOUT_MS);
      if (stopped.isAlive()) {
        throw new IllegalStateException("RegionServer " + server + " did not stop");
      }
      JVMClusterUtil.RegionServerThread started = cluster.startRegionServer();
      await(deadline, "the replacement of " + server + " to come online",
        () -> started.getRegionServer().isOnline());
      ServerName replacement = started.getRegionServer().getServerName();
      HMaster master = cluster.getMaster();
      await(deadline, "the regions of " + server + " to be reassigned", () -> {
        ServerManager sm = master.getServerManager();
        if (
          sm.isServerOnline(server) || !sm.isServerOnline(replacement) || deadServersInProgress(sm)
            || master.getAssignmentManager().hasRegionsInTransition()
        ) {
          return false;
        }
        for (RegionState rs : master.getAssignmentManager().getRegionStates().getRegionStates()) {
          boolean retired = rs.isSplit() || rs.isMerged() || rs.isOffline() || rs.isClosed();
          if (!retired && (!rs.isOpened() || server.equals(rs.getServerName()))) {
            return false;
          }
        }
        return true;
      });
      restarts.add(server + " -> " + replacement);
      LOG.info("Restarted RegionServer {} as {}", server, replacement);
      return replacement;
    }

    /**
     * Reports whether dead server processing is still in progress. HBase 2.6 declares a checked
     * exception on this call and HBase 2.5 does not, so the catch is broad. A failed check counts
     * as in progress, and the caller waits again.
     */
    private static boolean deadServersInProgress(ServerManager sm) {
      try {
        return sm.areDeadServersInProgress();
      } catch (Exception e) {
        LOG.debug("The dead server check failed", e);
        return true;
      }
    }

    private static void await(long deadline, String what, BooleanSupplier condition)
      throws InterruptedException {
      while (!condition.getAsBoolean()) {
        if (System.currentTimeMillis() > deadline) {
          throw new IllegalStateException("Timed out waiting for " + what);
        }
        Thread.sleep(200);
      }
    }
  }
}
