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
package org.apache.phoenix.pherf.distributed.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.apache.phoenix.pherf.distributed.Bundles;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.clock.VirtualClock;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.distributed.protocol.IntervalFrame;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.RecorderConfig;
import org.apache.phoenix.pherf.distributed.protocol.Share;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.validation.ScenarioValidator;
import org.apache.phoenix.pherf.validation.ValidationReport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies the behavior of an agent's Pherf workload when operating on the virtual clock with a
 * fake launcher.
 */
public class PherfWorkloadTest {
  private static final String RUN = "20260101T000000000Z-1";
  private static final String HEADER = "[Pherf latency log 1: microseconds, lowest 1, highest"
    + " 600000000, 3 significant digits, Tag=OPERATION|PHASE]";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private final VirtualClock clock = new VirtualClock(Simulation.START_MS);
  private final FakeLauncher launcher = new FakeLauncher();
  private final Events events = new Events();
  private File work;
  private PherfWorkload workload;

  /** Records the listener calls emitted by the workload as text representations. */
  private static final class Events implements Workload.Listener {
    final List<String> calls = new ArrayList<>();

    @Override
    public synchronized void prepared(List<String> errors) {
      calls.add("prepared " + errors);
    }

    @Override
    public synchronized void armed() {
      calls.add("armed");
    }

    @Override
    public synchronized void started(long startedMs) {
      calls.add("started " + startedMs);
    }

    @Override
    public synchronized void interval(String operation, String phase, long startMs, long endMs,
      Histogram histogram) {
      calls.add("interval " + operation + " " + phase + " " + startMs + " " + endMs + " "
        + histogram.getTotalCount());
    }

    final List<FinalReport> reports = new ArrayList<>();

    @Override
    public synchronized void finished(FinalReport report) {
      calls.add("finished");
      reports.add(report);
    }

    @Override
    public synchronized void failed(String reason) {
      calls.add("failed " + reason);
    }
  }

  /**
   * Constructs a Pherf LATENCY_run.json payload containing every field defined in LATENCY.md.
   */
  private static String latency(long startMs, long endMs, String... keys) {
    StringBuilder histograms = new StringBuilder();
    for (int i = 0; i < keys.length; i += 3) {
      histograms.append(i == 0 ? "" : ",").append("{\"operation\":\"").append(keys[i])
        .append("\",\"phase\":\"").append(keys[i + 1]).append("\",\"count\":").append(keys[i + 2])
        .append(",\"minUs\":1,\"maxUs\":9,\"meanUs\":4.5,\"p50Us\":4,")
        .append("\"p90Us\":8,\"p99Us\":9,\"p999Us\":9}");
    }
    return "{\"format\":\"pherf-latency\",\"version\":1,\"label\":\"run\",\"startMs\":" + startMs
      + ",\"endMs\":" + endMs + ",\"recorder\":{\"lowestUs\":1,"
      + "\"highestUs\":600000000,\"significantDigits\":3},\"hlog\":\"LATENCY_run.hlog\","
      + "\"intervals\":\"LATENCY_run_intervals.hlog\",\"histograms\":[" + histograms + "],"
      + "\"schedules\":[{\"scenario\":\"s\",\"querySet\":1,\"startMs\":" + startMs
      + ",\"scheduled\":7,\"futureField\":[1]}]}";
  }

  private static Properties shippedForTest() {
    Properties shipped = new Properties();
    shipped.setProperty("pherf.default.threadpool", "10");
    shipped.setProperty("shared", "shipped");
    shipped.setProperty("a", "shipped");
    shipped.setProperty("b", "shipped");
    shipped.setProperty("c", "shipped");
    shipped.setProperty("pherf.default.results.dir", "RESULTS");
    return shipped;
  }

  @Before
  public void setUp() throws Exception {
    work = folder.newFolder("work");
    workload = new PherfWorkload(work, "zk1:2181", shippedForTest(), launcher, clock);
  }

  private static Assignment assignment(Share share) {
    Map<String, String> documents = new LinkedHashMap<>();
    documents.put("s.xml", "<datamodel name=\"s\"/>");
    documents.put("p.xml", "<workloadProfile/>");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("pherf.default.threadpool", "16");
    return new Assignment(RUN, 0, DataPlaneIdentity.derive(1, 0, 0), "s.xml", documents,
      Arrays.asList("-q", "-disableSchemaApply"), share, properties);
  }

  private static Assignment assignment() {
    return assignment(new Share(2000L, 3L, 34L, 995L));
  }

  /** Prepares the workload assignment and terminates the -validate child process with exit 0. */
  private Assignment prepared(Assignment assignment) {
    workload.prepare(assignment, events);
    launcher.last().exit(0);
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared []"), events.calls);
    events.calls.clear();
    return assignment;
  }

  private static Properties load(File file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = new FileInputStream(file)) {
      properties.load(in);
    }
    return properties;
  }

  @Test
  public void prepareWritesTheSliceAndValidatesItWithTheRunOptions() throws Exception {
    Files.write(new File(work, "stale.txt").toPath(), "old".getBytes(StandardCharsets.UTF_8));
    workload.prepare(assignment(), events);
    assertFalse(new File(work, "stale.txt").exists());
    assertEquals("<datamodel name=\"s\"/>", new String(
      Files.readAllBytes(new File(work, "slice/s.xml").toPath()), StandardCharsets.UTF_8));
    assertTrue(new File(work, "slice/p.xml").isFile());
    FakeLauncher.Launch validate = launcher.last();
    String scenario = new File(work, "slice/s.xml").getAbsolutePath();
    assertEquals(Arrays.asList("-q", "-disableSchemaApply", "-scenarioFile", scenario, "-label",
      "validate", "-z", "zk1:2181", "-runDurationMs", "2000", "-threadCount", "3", "-targetRate",
      "34", "-rowCountOverride", "995", "-validate"), validate.args);
    assertEquals(work, validate.workDir);
    // No notifications are delivered to the listener before the child process exits.
    clock.advance(1000);
    assertEquals(Collections.emptyList(), events.calls);
    validate.exit(0);
    assertEquals(Collections.emptyList(), events.calls);
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared []"), events.calls);

    // Launching the run child process starts it immediately with the same command-line options,
    // omitting the -validate flag.
    workload.launch(assignment(), events);
    assertEquals(2, launcher.launches.size());
    FakeLauncher.Launch run = launcher.last();
    assertEquals(Arrays.asList("-q", "-disableSchemaApply", "-scenarioFile", scenario, "-label",
      "run", "-z", "zk1:2181", "-runDurationMs", "2000", "-threadCount", "3", "-targetRate", "34",
      "-rowCountOverride", "995"), run.args);
    // Lifecycle markers from the child process are delivered to the listener on the clock thread,
    // and starting the run writes the target start timestamp to the child's standard input stream.
    run.arm();
    assertEquals(Collections.singletonList("prepared []"), events.calls);
    clock.advance(0);
    long startAtMs = clock.millis() + 5000;
    workload.start(RUN, startAtMs);
    workload.start("other", startAtMs + 1);
    assertEquals(Collections.singletonList(Long.toString(startAtMs)), run.sent);
    run.started(startAtMs + 1);
    clock.advance(0);
    run.results(latency(startAtMs, startAtMs + 9, "query:s:q", "steady", "3"));
    run.exit(0);
    clock.advance(0);
    assertEquals(Arrays.asList("prepared []", "armed", "started " + (startAtMs + 1), "finished"),
      events.calls);
  }

  /**
   * Verifies that a child process that exits without arming at a start gate, as a -l load execution
   * does, still completes its run normally.
   */
  @Test
  public void childThatNeverArmsFinishes() {
    prepared(assignment());
    workload.launch(assignment(), events);
    FakeLauncher.Launch run = launcher.last();
    workload.start(RUN, clock.millis());
    run.results(latency(1, 2));
    run.exit(0);
    clock.advance(0);
    assertEquals(Collections.singletonList("finished"), events.calls);
  }

  /**
   * Verifies that when the run child process exits with code 0, it produces a final report
   * containing the time window and per-key sample counts from its JSON report, its schedule entries
   * unchanged, and its verdict rows.
   */
  @Test
  public void finishedRunGivesTheFinalReport() {
    prepared(assignment());
    workload.launch(assignment(), events);
    FakeLauncher.Launch run = launcher.last();
    String verdict = "pherf.health.client_pool\tPASS\tcount\tpherf\tno task waited";
    run.results(latency(1000, 5000, "query:s:a b", "steady", "12", "query:s:a b", "warmup", "2"),
      verdict, "pherf.health.read_skew\tSKIP\tshare\tpherf\tno source");
    run.exit(0);
    clock.advance(0);
    assertEquals(Collections.singletonList("finished"), events.calls);
    FinalReport report = events.reports.get(0);
    assertEquals(1000, report.getStartMs());
    assertEquals(5000, report.getEndMs());
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("query:s:a%20b|steady", 12L);
    counts.put("query:s:a%20b|warmup", 2L);
    assertEquals(counts, report.getCounts());
    assertEquals(1, report.getSchedules().size());
    assertEquals(7, report.getSchedules().get(0).get("scheduled").getAsLong());
    assertTrue(report.getSchedules().get(0).has("futureField"));
    assertEquals(Arrays.asList(verdict, "pherf.health.read_skew\tSKIP\tshare\tpherf\tno source"),
      report.getVerdict());
  }

  /**
   * Verifies that a run child process exiting with code 0 without a valid JSON report fails the
   * workload, whereas an absent verdict file yields an empty list of verdict rows.
   */
  @Test
  public void finishedRunNeedsItsJsonReport() {
    String[] bad = { null, "{\"format\":\"pherf-latency\",\"version\":2}", "[1]",
      "{\"format\":\"pherf-latency\",\"version\":1,\"startMs\":1,\"endMs\":2,"
        + "\"histograms\":[{\"operation\":\"q\"}]}" };
    for (String json : bad) {
      events.calls.clear();
      prepared(assignment());
      workload.launch(assignment(), events);
      FakeLauncher.Launch run = launcher.last();
      if (json != null) {
        run.results(json);
      } else {
        new File(work, "RESULTS_HDR/LATENCY_run.json").delete();
      }
      run.exit(0);
      clock.advance(0);
      assertEquals(1, events.calls.size());
      assertTrue(events.calls.get(0),
        events.calls.get(0).startsWith("failed pherf wrote no valid LATENCY_run.json: "));
    }
    events.calls.clear();
    events.reports.clear();
    prepared(assignment());
    workload.launch(assignment(), events);
    launcher.last().results(latency(1, 2));
    launcher.last().exit(0);
    clock.advance(0);
    assertEquals(Collections.singletonList("finished"), events.calls);
    assertEquals(Collections.emptyList(), events.reports.get(0).getVerdict());
    assertEquals(Collections.emptyMap(), events.reports.get(0).getCounts());
  }

  @Test
  public void absentSharesGiveNoOverrideOptions() {
    workload.prepare(assignment(new Share(null, null, null, null)), events);
    assertEquals(Arrays.asList("-q", "-disableSchemaApply", "-scenarioFile",
      new File(work, "slice/s.xml").getAbsolutePath(), "-label", "validate", "-z", "zk1:2181",
      "-validate"), launcher.last().args);
  }

  /**
   * Verifies that the child process pherf.properties overlay applies properties in order of
   * increasing precedence: shipped defaults, step properties, run-level properties, and finally
   * agent keys.
   */
  @Test
  public void overlayPrecedence() throws Exception {
    Simulation simulation = new Simulation();
    PherfWorkload agent =
      new PherfWorkload(work, "zk", shippedForTest(), launcher, simulation.clock);
    simulation.agent("a1", agent);
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Plans.plan(1).property("shared", "run")
      .property("a", "run").step(Plans.step(1).scenario("s.xml", "<s/>").property("shared", "step")
        .property("b", "step").property("pherf.default.threadpool", "16"))
      .spec());
    simulation.runFor(0);
    assertTrue(launcher.last().validates());
    Properties overlay = load(new File(work, "overlay/pherf.properties"));
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("shared", "run");
    expected.put("a", "run");
    expected.put("b", "step");
    expected.put("c", "shipped");
    expected.put("pherf.default.threadpool", "16");
    expected.put("pherf.default.results.dir", new File(work, "RESULTS").getAbsolutePath());
    expected.put(LatencyRecorders.INTERVAL_PROPERTY, "1000");
    assertEquals(expected, new LinkedHashMap<Object, Object>(overlay));
    launcher.last().exit(0);
    simulation.runFor(1000);
    launcher.last().results(latency(1, 2));
    launcher.last().exit(0);
    simulation.runFor(5000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
  }

  @Test
  public void validationExitCodes() throws Exception {
    // An exit code of 65 produces one error for each finding in the report produced by Pherf's
    // own validator.
    File slice = folder.newFolder("bad");
    File scenario = new File(slice, "closed_loop_scenario.xml");
    Files.write(scenario.toPath(),
      Plans.resource("/fleet/closed_loop_scenario.xml").replace("${pherf.slot.offset}", "0")
        .replace("rowCount=\"1000\"", "rowCount=\"1\"").getBytes(StandardCharsets.UTF_8));
    ValidationReport report =
      ScenarioValidator.validate(Collections.singletonList(scenario.toPath()), null);
    assertTrue(report.getErrors() > 0);
    List<String> findings = new ArrayList<>();
    for (ValidationReport.Finding finding : report.getFindings()) {
      findings.add(finding.toString());
    }
    assertTrue(findings.toString(), findings.get(0).startsWith("cogen.walk "));
    workload.prepare(assignment(), events);
    Files.createDirectories(new File(work, "RESULTS").toPath());
    report.write(new File(work, "RESULTS"), "validate");
    launcher.last().exit(ScenarioValidator.EXIT_CODE, "Validation: 2 error(s)");
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared " + findings), events.calls);

    // An exit code of 65 accompanied by a validation report containing no findings is treated as
    // an invalid report error.
    events.calls.clear();
    workload.prepare(assignment(), events);
    Files.createDirectories(new File(work, "RESULTS").toPath());
    Files.write(new File(work, "RESULTS/VALIDATION_validate.json").toPath(),
      "{\"findings\": []}".getBytes(StandardCharsets.UTF_8));
    launcher.last().exit(ScenarioValidator.EXIT_CODE);
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared [pherf -validate exited with 65 and gave no"
      + " valid report: the report has no finding]"), events.calls);

    // Any other non-zero exit code surfaces the last lines of the child process output.
    events.calls.clear();
    workload.prepare(assignment(), events);
    launcher.last().exit(1, "l2", "l3", "l4", "l5", "-threadCount is not an integer");
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared [pherf -validate exited with 1: l2 | l3 | l4 |"
      + " l5 | -threadCount is not an integer]"), events.calls);

    // A failure to launch the validation child process is reported as a preparation error.
    events.calls.clear();
    launcher.failure = new IOException("no java");
    workload.prepare(assignment(), events);
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared [cannot launch pherf -validate: no java]"),
      events.calls);

    // An exit code of 0 transitions the workload preparation to the READY state with no errors.
    events.calls.clear();
    prepared(assignment());
  }

  /**
   * Constructs a workload assignment representing a slice of a later step using the documents from
   * {@link #assignment(Share)}.
   */
  private static Assignment later(int step, int slot, Share share) {
    Assignment base = assignment(share);
    return new Assignment(RUN, step, DataPlaneIdentity.derive(1, slot, 100L * slot),
      base.getScenario(), base.getDocuments(), base.getArgs(), share, base.getProperties());
  }

  /** Constructs an assignment for step 0 bundled with two later slices for upfront validation. */
  private static Assignment withLaterSlices() {
    Assignment base = assignment();
    return new Assignment(RUN, 0, base.getIdentity(), base.getScenario(), base.getDocuments(),
      base.getArgs(), base.getShare(), base.getProperties(),
      Arrays.asList(later(1, 1, new Share(null, 2L, null, 500L)),
        later(2, 3, new Share(null, null, null, null))));
  }

  /**
   * Verifies that preparation first validates its own slice in the work directory and then
   * validates each later slice in its own dedicated directory, running one child process at a time
   * and reporting the accumulated errors of all slices while retaining its own slice for the start
   * phase.
   */
  @Test
  public void prepareValidatesTheLaterSlices() throws Exception {
    workload.prepare(withLaterSlices(), events);
    FakeLauncher.Launch own = launcher.last();
    assertEquals(work, own.workDir);
    own.exit(0);
    FakeLauncher.Launch first = launcher.last();
    File dir = new File(work, PherfWorkload.LATER + "/1-1");
    assertEquals(dir, first.workDir);
    assertEquals(Arrays.asList("-q", "-disableSchemaApply", "-scenarioFile",
      new File(dir, "slice/s.xml").getAbsolutePath(), "-label", "validate", "-z", "zk1:2181",
      "-threadCount", "2", "-rowCountOverride", "500", "-validate"), first.args);
    assertEquals(new File(dir, "RESULTS").getAbsolutePath(),
      load(new File(dir, "overlay/pherf.properties")).getProperty("pherf.default.results.dir"));
    Files.createDirectories(new File(dir, "RESULTS").toPath());
    Files.write(new File(dir, "RESULTS/VALIDATION_validate.json").toPath(),
      ("{\"findings\": [{\"rule\": \"cogen.walk\", \"file\": \"s.xml\", \"where\":"
        + " \"scenario s querySet 1\", \"message\": \"no row\"}]}")
          .getBytes(StandardCharsets.UTF_8));
    first.exit(ScenarioValidator.EXIT_CODE);
    FakeLauncher.Launch second = launcher.last();
    assertEquals(new File(work, PherfWorkload.LATER + "/2-3"), second.workDir);
    assertEquals(Collections.emptyList(), events.calls);
    second.exit(1, "bad");
    clock.advance(0);
    assertEquals(Collections.singletonList("prepared [step 1 slot 1: cogen.walk s.xml scenario s"
      + " querySet 1: no row, step 2 slot 3: pherf -validate exited with 1: bad]"), events.calls);
    assertEquals(3, launcher.launches.size());
    assertTrue(new File(work, "slice/s.xml").isFile());
  }

  /**
   * Verifies that cancelling the run during the validation of a later slice terminates preparation
   * immediately without launching any further child processes.
   */
  @Test
  public void cancelBetweenTheSlices() {
    workload.prepare(withLaterSlices(), events);
    launcher.last().exit(0);
    FakeLauncher.Launch first = launcher.last();
    workload.cancel(RUN);
    assertEquals(1, first.destroys);
    first.exit(143);
    clock.advance(0);
    assertEquals(2, launcher.launches.size());
    assertEquals(1, events.calls.size());
    assertTrue(events.calls.get(0), events.calls.get(0).startsWith("prepared "));
  }

  @Test
  public void runExitCodes() {
    prepared(assignment());
    workload.launch(assignment(), events);
    launcher.last().exit(3, "a", "b");
    clock.advance(0);
    assertEquals(Collections.singletonList("failed pherf exited with 3: a | b"), events.calls);

    events.calls.clear();
    prepared(assignment());
    launcher.failure = new IOException("no java");
    workload.launch(assignment(), events);
    clock.advance(0);
    assertEquals(Collections.singletonList("failed cannot launch pherf: no java"), events.calls);
  }

  @Test
  public void cancelWhileValidating() {
    workload.prepare(assignment(), events);
    FakeLauncher.Launch validate = launcher.last();
    workload.cancel(RUN);
    assertEquals(1, validate.destroys);
    clock.advance(Protocol.STOP_GRACE_MS - 1);
    assertEquals(0, validate.kills);
    clock.advance(1);
    assertEquals(1, validate.kills);
    assertEquals(Collections.emptyList(), events.calls);
    validate.exit(137);
    clock.advance(0);
    assertEquals(1, events.calls.size());
    assertTrue(events.calls.get(0), events.calls.get(0).startsWith("prepared "));
    // Cancelling with a non-matching run identifier has no effect on the active workload.
    workload.prepare(assignment(), events);
    workload.cancel("other");
    assertEquals(0, launcher.last().destroys);
  }

  /**
   * Verifies that cancelling a child process while it is holding at its start gate terminates the
   * child and prevents any start time from being sent to it.
   */
  @Test
  public void cancelWhileArmed() {
    prepared(assignment());
    workload.launch(assignment(), events);
    FakeLauncher.Launch run = launcher.last();
    run.arm();
    clock.advance(0);
    workload.cancel(RUN);
    assertEquals(1, run.destroys);
    workload.start(RUN, clock.millis());
    assertEquals(Collections.emptyList(), run.sent);
    // Any lifecycle marker emitted after cancellation is suppressed and does not reach the
    // listener.
    run.started(clock.millis());
    run.exit(143);
    clock.advance(0);
    assertEquals(Arrays.asList("armed", "finished"), events.calls);
    assertEquals(Collections.singletonList(null), events.reports);
    assertEquals(2, launcher.launches.size());
  }

  @Test
  public void cancelWhileRunning() {
    prepared(assignment());
    workload.launch(assignment(), events);
    FakeLauncher.Launch run = launcher.last();
    assertFalse(run.validates());
    workload.cancel(RUN);
    assertEquals(1, run.destroys);
    clock.advance(Protocol.STOP_GRACE_MS - 1);
    assertEquals(0, run.kills);
    clock.advance(1);
    assertEquals(1, run.kills);
    assertEquals(Collections.emptyList(), events.calls);
    run.exit(137);
    clock.advance(0);
    assertEquals(Collections.singletonList("finished"), events.calls);

    // A child process that terminates within the stop grace period is not sent a SIGKILL signal.
    events.calls.clear();
    prepared(assignment());
    workload.launch(assignment(), events);
    FakeLauncher.Launch quick = launcher.last();
    workload.cancel(RUN);
    quick.exit(143);
    clock.advance(Protocol.STOP_GRACE_MS * 2);
    assertEquals(1, quick.destroys);
    assertEquals(0, quick.kills);
    assertEquals(Collections.singletonList("finished"), events.calls);
  }

  @Test
  public void cancelWhenReadyReportsNothing() {
    prepared(assignment());
    workload.cancel(RUN);
    clock.advance(10000);
    assertEquals(Collections.emptyList(), events.calls);
  }

  @Test
  public void closeKillsALiveChild() {
    workload.prepare(assignment(), events);
    workload.close();
    assertEquals(1, launcher.last().kills);
    launcher.last().exit(137);
    clock.advance(0);

    events.calls.clear();
    prepared(assignment());
    workload.launch(assignment(), events);
    workload.close();
    assertEquals(1, launcher.last().kills);
    // Closing the workload when no child process is alive performs no further kill action.
    launcher.last().exit(137);
    workload.close();
    assertEquals(1, launcher.last().kills);
  }

  /**
   * Simulates an HdrHistogram interval log file that tests can write incrementally, mirroring the
   * streaming behavior of Pherf's interval export.
   */
  private static final class IntervalLog {
    private final File file;
    private final long baseMs;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final HistogramLogWriter writer;
    private int flushed;

    IntervalLog(File file, long baseMs) throws Exception {
      this.file = file;
      this.baseMs = baseMs;
      this.writer = new HistogramLogWriter(new PrintStream(bytes, true, "UTF-8"));
      writer.outputComment(HEADER);
      writer.outputLogFormatVersion();
      writer.outputStartTime(baseMs);
      writer.outputBaseTime(baseMs);
      writer.outputLegend();
    }

    /**
     * Renders a single interval histogram log line and returns the formatted frame representation
     * that the controller is expected to hold for it.
     */
    String interval(String operation, String phase, long startMs, long endMs, int samples) {
      Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      for (int i = 1; i <= samples; i++) {
        histogram.recordValue(i * 101);
      }
      histogram.setTag(LatencyReport.tag(operation, phase));
      writer.outputIntervalHistogram((startMs - baseMs) / 1000.0, (endMs - baseMs) / 1000.0,
        histogram, 1.0);
      return operation + " " + phase + " " + startMs + " " + endMs + " " + samples;
    }

    /**
     * Appends the buffered rendered bytes to the underlying log file, withholding the specified
     * number of trailing bytes from the end.
     */
    void flush(int heldBack) throws IOException {
      byte[] all = bytes.toByteArray();
      int end = all.length - heldBack;
      Files.createDirectories(file.getParentFile().toPath());
      Files.write(file.toPath(), Arrays.copyOfRange(all, flushed, end), StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
      flushed = end;
    }

    void flush() throws IOException {
      flush(0);
    }

    void append(String text) throws IOException {
      Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
  }

  /**
   * Extracts the telemetry interval frames that reached the controller, ordered by sequence number
   * and formatted as text.
   */
  private static List<String> held(Simulation simulation) throws Exception {
    Map<Long, String> frames = new TreeMap<>();
    for (InProcessNetwork.Event event : simulation.network.events()) {
      if (event.kind.equals(InProcessNetwork.REQUEST) && event.stream.equals(Protocol.TELEMETRY)) {
        for (IntervalFrame frame : ((Intervals) Codec.decode(event.json)).getFrames()) {
          assertEquals(RecorderConfig.PINNED.getHighestUs(), frame.getRecorder().getHighestUs());
          frames.put(frame.getSeq(),
            frame.getOperation() + " " + frame.getPhase() + " " + frame.getStartMs() + " "
              + frame.getEndMs() + " "
              + HistogramBlob.decode(frame.getHistogram()).getTotalCount());
        }
      }
    }
    return new ArrayList<>(frames.values());
  }

  private Simulation runOnOneAgent() {
    Simulation simulation = new Simulation();
    workload = new PherfWorkload(work, "zk", shippedForTest(), launcher, simulation.clock);
    simulation.agent("a1", workload);
    simulation.runFor(1000);
    return simulation;
  }

  private static String submit(Simulation simulation) {
    String runId = simulation.controller
      .submit(Plans.plan(1).step(Plans.step(1).scenario("s.xml", "<s/>").args("-q")).spec());
    simulation.runFor(0);
    return runId;
  }

  /**
   * Verifies that P4 interval records are streamed to the controller as D1 telemetry frames in
   * sequence order, preserving exact time bounds and sample counts.
   */
  @Test
  public void intervalsReachTheController() throws Exception {
    Simulation simulation = runOnOneAgent();
    String runId = submit(simulation);
    launcher.last().exit(0);
    simulation.runFor(2000);
    FakeLauncher.Launch run = launcher.last();
    assertFalse(run.validates());
    // Once the child process arms, the scheduled start time is written to its standard input
    // stream, and the start gate opens at that timestamp.
    run.arm();
    simulation.runFor(4000);
    assertEquals(1, run.sent.size());
    long startAtMs = Long.parseLong(run.sent.get(0));
    run.started(startAtMs);
    long base = simulation.clock.millis() + 7;
    IntervalLog log =
      new IntervalLog(new File(work, "RESULTS_HDR/LATENCY_run_intervals.hlog"), base);
    List<String> expected = new ArrayList<>();
    expected.add(
      log.interval("query:relational:lookup", LatencyRecorders.WARMUP, base + 1, base + 999, 2));
    expected
      .add(log.interval("response:s:q 1,x%", LatencyRecorders.STEADY, base + 999, base + 2001, 5));
    String partial =
      log.interval("load:PHERF.T", LatencyRecorders.STEADY, base + 2001, base + 3000, 1);
    // An incompletely flushed trailing line is buffered until the full line has been written.
    log.flush(10);
    simulation.runFor(1000);
    assertEquals(expected, held(simulation));
    log.flush();
    expected.add(partial);
    simulation.runFor(1000);
    assertEquals(expected, held(simulation));

    // Trailing lines written after the last periodic poll are captured by the final read and sent
    // before the workload reports finished.
    expected
      .add(log.interval("scan:s:range", LatencyRecorders.STEADY, base + 3000, base + 3500, 3));
    log.flush();
    simulation.network.freeze("a1", Protocol.TELEMETRY);
    run.results(latency(base, base + 3500, "load:PHERF.T", "steady", "1", "query:relational:lookup",
      "warmup", "2", "response:s:q 1,x%", "steady", "5", "scan:s:range", "steady", "3"));
    run.exit(0);
    simulation.runFor(3000);
    // The transition to DONE waits until the controller acknowledges the final telemetry frame.
    assertEquals(AgentRuntime.State.RUNNING, simulation.agentRuntime("a1").state());
    assertEquals(RunView.State.RUNNING, simulation.run(runId).getState());
    simulation.network.thaw("a1", Protocol.TELEMETRY);
    simulation.runFor(3000);
    assertEquals(expected, held(simulation));
    RunView done = simulation.run(runId);
    assertEquals(RunView.State.COMPLETE, done.getState());
    RunView.SlotView slot = done.getSteps().get(0).getSlots().get(0);
    assertEquals(4, slot.getHeldSeq());
    assertEquals(4, slot.getFinalSeq());
    assertEquals(2 + 5 + 1 + 3, slot.getSamples());
    assertEquals(Long.valueOf(startAtMs), slot.getStartAtMs());
    assertEquals(Long.valueOf(startAtMs), slot.getDispatchMs());
    assertEquals(Long.valueOf(0), done.getSteps().get(0).getSkewMs());
    // The counters held by the controller match the counts reported in the child's JSON report.
    assertEquals("PASS", Bundles.row(simulation.bundle(runId), "pherf.fleet.telemetry")[1]);
  }

  @Test
  public void malformedIntervalLineFailsTheRun() throws Exception {
    Simulation simulation = runOnOneAgent();
    String runId = submit(simulation);
    launcher.last().exit(0);
    simulation.runFor(2000);
    FakeLauncher.Launch run = launcher.last();
    assertFalse(run.validates());
    IntervalLog log = new IntervalLog(new File(work, "RESULTS_HDR/LATENCY_run_intervals.hlog"),
      simulation.clock.millis());
    log.flush();
    log.append("Tag=query:s:q|steady,0.000,1.000,0.101,not-a-histogram\n");
    simulation.runFor(1000);
    // The workload terminates the running child process and reports the failure once the child
    // process has exited.
    assertEquals(1, run.destroys);
    assertEquals(RunView.State.RUNNING, simulation.run(runId).getState());
    run.exit(143);
    simulation.runFor(3000);
    RunView failed = simulation.run(runId);
    assertEquals(RunView.State.FAILED, failed.getState());
    assertTrue(failed.getReason(),
      failed.getReason().startsWith("agent a1: bad interval log: line 6: "));
    assertEquals(AgentRuntime.State.IDLE, simulation.agentRuntime("a1").state());
  }
}
