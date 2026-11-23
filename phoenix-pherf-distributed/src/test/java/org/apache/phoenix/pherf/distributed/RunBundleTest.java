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
package org.apache.phoenix.pherf.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.analysis.Replicate;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.junit.Test;

/**
 * Verifies RQ-237: every run produces a complete bundle in its run directory containing the raw
 * interval log for each slot, the fleet report and run log, and the fleet verdict, with
 * manifest.json written last. The bundle conforms to the run-bundle layout expected by offline
 * analysis and satisfies the field validation rules of the H2 driver.
 */
public class RunBundleTest {
  /**
   * Defines a representative schedule entry containing every field specified in LATENCY.md as
   * emitted by a Pherf open-loop query set, with all timestamps recorded relative to the agent host
   * clock.
   */
  private static final String SCHEDULE = "{\"scenario\":\"relational\",\"querySet\":1,"
    + "\"operations\":[\"query:relational:lookup\"],\"concurrency\":16,\"phase\":\"done\","
    + "\"stopReason\":\"duration\",\"startMs\":1767225610000,\"endMs\":1767225613000,"
    + "\"durationMs\":3000,\"lastTakeMs\":1767225612990,\"targetRate\":0.0,\"queueDepth\":0,"
    + "\"inFlight\":0,\"driftUs\":0,\"scheduled\":300,\"completed\":300,\"late\":0,\"dropped\":0,"
    + "\"aborted\":0,\"pending\":0,\"maxQueueDepth\":3,\"maxInFlight\":5,\"maxDriftUs\":420}";
  private static final List<String> SEVERITIES =
    Arrays.asList("PASS", "SKIP", "ADVISORY", "FAIL", "ERROR");
  private static final List<String> CLASSES = Arrays.asList("count", "share", "duration");
  private static final List<String> CHECKS = new ArrayList<>(HealthVerdict.CHECKS);

  static {
    CHECKS.addAll(
      Arrays.asList("pherf.fleet.start_skew", "pherf.fleet.telemetry", "pherf.fleet.agents"));
  }

  private static void assertVerdictRows(File bundle) throws Exception {
    List<String[]> rows = Bundles.verdict(bundle);
    assertEquals(8, rows.size());
    for (int i = 0; i < rows.size(); i++) {
      String[] row = rows.get(i);
      assertEquals(Arrays.toString(row), 5, row.length);
      assertEquals(CHECKS.get(i), row[0]);
      assertTrue(row[1], SEVERITIES.contains(row[1]));
      assertTrue(row[2], CLASSES.contains(row[2]));
      assertEquals(HealthVerdict.SOURCE, row[3]);
    }
  }

  private static List<String> severities(File bundle) throws Exception {
    List<String> severities = new ArrayList<>();
    for (String[] row : Bundles.verdict(bundle)) {
      severities.add(row[1]);
    }
    return severities;
  }

  /**
   * Returns the highest severity present across the verdict rows, which the manifest records as the
   * overall run result.
   */
  private static String highest(File bundle) throws Exception {
    int highest = 0;
    for (String severity : severities(bundle)) {
      highest = Math.max(highest, SEVERITIES.indexOf(severity));
    }
    return SEVERITIES.get(highest);
  }

  @Test
  public void completeRunWritesTheBundle() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    // Configure agent a2 on a host whose clock is 5 s ahead to verify that the bundle normalizes
    // every timestamp to the controller clock.
    simulation.skewedAgent("a2", 5000);
    simulation.runFor(1000);
    long submittedMs = simulation.clock.millis();
    String runId = simulation.controller.submit(
      Plans.plan(42).step(Plans.step(2).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
        .property("fake.warmup", "1").property("fake.schedule", SCHEDULE)).spec());
    simulation.runFor(20000);
    RunView run = simulation.run(runId);
    assertEquals(run.toString(), RunView.State.COMPLETE, run.getState());
    File bundle = simulation.bundle(runId);
    File results = Bundles.results(bundle);
    for (String path : new String[] { "LATENCY_run.json", "LATENCY_run.hlog", "VERDICT_run.tsv",
      "slots/0-0.hlog", "slots/0-1.hlog" }) {
      assertTrue(path, new File(results, path).isFile());
    }
    assertEquals(Arrays.asList("drivers", "manifest.json"), sorted(bundle.list()));
    assertEquals(Arrays.asList("LATENCY_run.hlog", "LATENCY_run.json", "VERDICT_run.tsv", "slots"),
      sorted(results.list()));

    JsonObject manifest = Bundles.manifest(bundle);
    assertEquals(1, manifest.get("bundle_format_version").getAsInt());
    JsonObject runMember = manifest.getAsJsonObject("run");
    assertEquals(runId, runMember.get("id").getAsString());
    assertEquals("validation", runMember.get("class").getAsString());
    assertEquals(highest(bundle), runMember.get("result").getAsString());
    assertEquals("COMPLETE", runMember.get("state").getAsString());
    assertFalse(runMember.has("reason"));
    assertEquals(42, runMember.get("seed").getAsLong());
    assertEquals(submittedMs, runMember.get("started_ms").getAsLong());
    long endedMs = runMember.get("ended_ms").getAsLong();
    assertTrue(endedMs > submittedMs && endedMs <= simulation.clock.millis());
    assertEquals(new JsonArray(), manifest.getAsJsonObject("quiet_host").get("tags"));

    // Verify that the fleet report conforms to P4 version 1, contains the merged keys, and records
    // timestamps on the controller clock.
    JsonObject report = Bundles.report(bundle);
    assertEquals("run", report.get("label").getAsString());
    assertEquals("LATENCY_run.hlog", report.get("hlog").getAsString());
    assertFalse(report.has("intervals"));
    long startMs = report.get("startMs").getAsLong();
    long endMs = report.get("endMs").getAsLong();
    assertTrue(startMs + " " + submittedMs, startMs >= submittedMs);
    assertTrue(endMs + " " + endedMs, endMs <= endedMs && endMs > startMs);
    Map<String, Long> counts = Bundles.counts(report);
    assertEquals(2, counts.size());
    JsonObject fleet = report.getAsJsonObject("fleet");
    assertEquals(runId, fleet.get("runId").getAsString());
    assertEquals(42, fleet.get("seed").getAsLong());
    assertEquals("validation", fleet.get("runClass").getAsString());
    assertFalse(fleet.get("contaminated").getAsBoolean());
    assertEquals("COMPLETE", fleet.get("state").getAsString());
    JsonObject step = fleet.getAsJsonArray("steps").get(0).getAsJsonObject();
    assertEquals(0, step.get("step").getAsInt());
    assertEquals(run.getSteps().get(0).getStartAtMs(), step.get("startAtMs").getAsLong());
    assertEquals(run.getSteps().get(0).getSkewMs().longValue(), step.get("skewMs").getAsLong());
    JsonArray slots = step.getAsJsonArray("slots");
    assertEquals(2, slots.size());
    long[] merged = new long[1];
    for (int i = 0; i < 2; i++) {
      RunView.SlotView view = run.getSteps().get(0).getSlots().get(i);
      JsonObject slot = slots.get(i).getAsJsonObject();
      assertEquals(i, slot.get("slot").getAsInt());
      assertEquals(view.getAgent(), slot.get("agent").getAsString());
      assertEquals(simulation.agentRuntime(view.getAgent()).identity().getIncarnation(),
        slot.get("incarnation").getAsString());
      assertEquals(view.getIdentity().getSeed(), slot.get("seed").getAsLong());
      assertEquals(view.getIdentity().getPrefix(), slot.get("prefix").getAsString());
      assertEquals("DONE", slot.get("state").getAsString());
      assertEquals(view.getOffsetMs().longValue(), slot.get("offsetMs").getAsLong());
      assertEquals(view.getHeldSeq(), slot.get("frames").getAsLong());
      assertEquals(view.getSamples(), slot.get("samples").getAsLong());
      assertEquals("slots/0-" + i + ".hlog", slot.get("intervals").getAsString());
      assertTrue(slot.get("startLagMs").getAsLong() >= 0);
      for (Map.Entry<String, Long> key : view.getCounters().entrySet()) {
        assertEquals(key.getValue().longValue(),
          slot.getAsJsonObject("counts").get(key.getKey()).getAsLong());
        merged[0] += key.getValue();
      }
    }
    assertEquals(5000, slots.get(1).getAsJsonObject().get("offsetMs").getAsLong());
    long total = 0;
    for (long count : counts.values()) {
      total += count;
    }
    assertEquals(merged[0], total);

    // Verify that each slot schedule entry has its timestamps translated to the controller clock
    // and is annotated with its corresponding slot metadata.
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(2, schedules.size());
    JsonObject fixture = JsonParser.parseString(SCHEDULE).getAsJsonObject();
    for (int i = 0; i < 2; i++) {
      JsonObject schedule = schedules.get(i).getAsJsonObject();
      long offset = slots.get(i).getAsJsonObject().get("offsetMs").getAsLong();
      for (String time : new String[] { "startMs", "endMs", "lastTakeMs" }) {
        assertEquals(fixture.get(time).getAsLong() - offset, schedule.get(time).getAsLong());
      }
      assertEquals(300, schedule.get("completed").getAsLong());
      assertEquals(0, schedule.get("step").getAsInt());
      assertEquals(i, schedule.get("slot").getAsInt());
      assertEquals(run.getSteps().get(0).getSlots().get(i).getAgent(),
        schedule.get("agent").getAsString());
    }

    // Verify that the aggregated run log begins with the standard Pherf header and contains one
    // histogram for each key with sample counts matching the JSON report.
    List<Histogram> totals = Bundles.hlog(new File(results, "LATENCY_run.hlog"));
    assertEquals(counts, Bundles.counts(totals));
    assertEquals(Bundles.HLOG_HEADER,
      Files.readAllLines(new File(results, "LATENCY_run.hlog").toPath()).get(0));

    // Verify that the raw log for each slot records one interval line per held frame and that its
    // total counts match the slot counters.
    for (int i = 0; i < 2; i++) {
      RunView.SlotView view = run.getSteps().get(0).getSlots().get(i);
      File raw = new File(results, "slots/0-" + i + ".hlog");
      List<String> lines = Files.readAllLines(raw.toPath());
      assertEquals(Bundles.HLOG_HEADER, lines.get(0));
      assertEquals("#[pherf-distributed run " + runId + " step 0 slot " + i + " agent "
        + view.getAgent() + ", agent clock]", lines.get(1));
      List<Histogram> intervals = Bundles.hlog(raw);
      assertEquals(view.getHeldSeq(), intervals.size());
      assertEquals(view.getCounters(), Bundles.counts(intervals));
    }

    assertVerdictRows(bundle);
    assertEquals("PASS", Bundles.row(bundle, "pherf.fleet.telemetry")[1]);
    assertEquals("PASS", Bundles.row(bundle, "pherf.fleet.agents")[1]);
    assertEquals("PASS", Bundles.row(bundle, "pherf.fleet.start_skew")[1]);
    assertEquals("PASS", Bundles.row(bundle, HealthVerdict.ATTAINMENT)[1]);
    assertEquals("PASS", Bundles.row(bundle, HealthVerdict.SATURATION)[1]);
    assertTrue(Bundles.row(bundle, HealthVerdict.CLIENT_POOL)[4].startsWith("step 0 slot "));

    // Verify that the offline analysis loader recognizes the directory as a run bundle and loads
    // the merged operation counts.
    Replicate replicate = Replicate.load(bundle);
    assertEquals(Replicate.BUNDLE, replicate.getLayout());
    assertEquals(runId, replicate.getRunId());
    assertEquals(Replicate.VALIDATION, replicate.getRunClass());
    assertEquals(highest(bundle), replicate.getResult());
    assertEquals(Collections.emptyList(), replicate.getContamination());
    for (Map.Entry<String, Long> key : counts.entrySet()) {
      String[] parsed = LatencyReport.parseTag(key.getKey());
      assertEquals(key.getValue(),
        replicate.getCounts().get(new Replicate.Key(parsed[0], parsed[1], Replicate.COUNT)));
    }
    Bundles.assertH2Rules(results);
  }

  private static List<String> sorted(String[] names) {
    List<String> list = new ArrayList<>(Arrays.asList(names));
    Collections.sort(list);
    return list;
  }

  @Test
  public void failedAndAbortedRunsWriteBundles() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);

    String aborted = simulation.controller.submit(Plans.plan(1)
      .step(Plans.step(2).scenario("scenario.xml", "<s/>").property("fake.prepareError.1", "bad"))
      .spec());
    simulation.runFor(5000);
    assertEquals(RunView.State.ABORTED, simulation.run(aborted).getState());
    File bundle = simulation.bundle(aborted);
    JsonObject manifest = Bundles.manifest(bundle);
    assertEquals("ABORTED", manifest.getAsJsonObject("run").get("state").getAsString());
    assertEquals("agent a2: bad", manifest.getAsJsonObject("run").get("reason").getAsString());
    assertEquals("FAIL", manifest.getAsJsonObject("run").get("result").getAsString());
    JsonObject report = Bundles.report(bundle);
    assertEquals(new JsonArray(), report.get("histograms"));
    assertEquals(report.get("startMs").getAsLong(), report.get("endMs").getAsLong());
    assertFalse(new File(Bundles.results(bundle), "slots").exists());
    assertVerdictRows(bundle);
    assertEquals("FAIL", Bundles.row(bundle, "pherf.fleet.agents")[1]);
    assertEquals("SKIP", Bundles.row(bundle, "pherf.fleet.telemetry")[1]);
    assertEquals("SKIP", Bundles.row(bundle, "pherf.fleet.start_skew")[1]);
    assertEquals("FAIL", Replicate.load(bundle).getResult());
    Bundles.assertH2Rules(Bundles.results(bundle));

    String failed = simulation.controller.submit(Plans.plan(1)
      .step(Plans.step(2).scenario("scenario.xml", "<s/>").property("fake.fail.1", "2")).spec());
    simulation.runFor(15000);
    RunView run = simulation.run(failed);
    assertEquals(RunView.State.FAILED, run.getState());
    bundle = simulation.bundle(failed);
    manifest = Bundles.manifest(bundle);
    assertEquals("FAILED", manifest.getAsJsonObject("run").get("state").getAsString());
    assertEquals(run.getReason(), manifest.getAsJsonObject("run").get("reason").getAsString());
    assertVerdictRows(bundle);
    String[] agents = Bundles.row(bundle, "pherf.fleet.agents");
    assertEquals("FAIL", agents[1]);
    assertTrue(agents[4], agents[4].contains("step 0 slot 1 (agent a2): agent a2: fake failure"));
    // Verify that all held frames collected across both slots are preserved in the bundle.
    long held = 0;
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      for (long count : slot.getCounters().values()) {
        held += count;
      }
    }
    long merged = 0;
    for (long count : Bundles.counts(Bundles.report(bundle)).values()) {
      merged += count;
    }
    assertTrue(held > 0);
    assertEquals(held, merged);
    Bundles.assertH2Rules(Bundles.results(bundle));
    assertTrue(new File(bundle, RunBundle.MANIFEST).isFile());
  }
}
