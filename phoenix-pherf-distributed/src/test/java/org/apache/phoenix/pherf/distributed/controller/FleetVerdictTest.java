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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.Bundles;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.distributed.protocol.FinalReport;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.Share;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Row;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Severity;
import org.junit.Test;

/**
 * Verifies the fleet verdict computation defined by RQ-239. The evaluation executes the five
 * standard Pherf health checks across the merged run data, followed by three fleet-specific checks
 * evaluating start skew, telemetry sample counts, and agent execution state. Any FAIL result in the
 * duration evidence class is demoted to ADVISORY when executing either a validation run or a
 * contaminated run.
 */
public class FleetVerdictTest {
  private static final String SKEW = "pherf.fleet.start_skew";
  private static final String TELEMETRY = "pherf.fleet.telemetry";
  private static final String AGENTS = "pherf.fleet.agents";
  private static final String KEY = "query:s:q|steady";

  /**
   * Executes a simulation run across 2 agents; when the late flag is set, the start reply from
   * agent a2 is delayed to arrive 500 ms after the scheduled start epoch.
   */
  private static File run(Plans plan, boolean late, String... properties) {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    if (late) {
      simulation.network.policy((agent, stream, direction,
        message) -> agent.equals("a2") && direction.equals(InProcessNetwork.REPLY)
          && message.type() == MessageType.START ? Protocol.START_LEAD_MS + 500 : 0);
    }
    Plans.Step step = Plans.step(2).scenario("scenario.xml", "<datamodel name=\"fake\"/>");
    for (int i = 0; i < properties.length; i += 2) {
      step.property(properties[i], properties[i + 1]);
    }
    String runId = simulation.controller.submit(plan.step(step).spec());
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    return simulation.bundle(runId);
  }

  private static JsonObject runMember(File bundle) throws Exception {
    return Bundles.manifest(bundle).getAsJsonObject("run");
  }

  @Test
  public void lateStartFailsAMeasurementRun() throws Exception {
    File bundle = run(Plans.plan(1).runClass("measurement"), true);
    String[] skew = Bundles.row(bundle, SKEW);
    assertEquals("FAIL", skew[1]);
    assertEquals(HealthVerdict.DURATION, skew[2]);
    assertTrue(skew[4], skew[4].startsWith("step 0 start skew at most "));
    assertTrue(skew[4], skew[4].endsWith(" ms, above the 50 ms bound"));
    long skewMs =
      Long.parseLong(skew[4].replaceAll("^step 0 start skew at most ([0-9]+) .*$", "$1"));
    assertTrue(skew[4], skewMs > 50);
    assertEquals("FAIL", runMember(bundle).get("result").getAsString());
    // Verify the per-agent skew by checking the start lag of agent a2 relative to the earliest
    // dispatch timestamp.
    JsonArray slots = Bundles.report(bundle).getAsJsonObject("fleet").getAsJsonArray("steps").get(0)
      .getAsJsonObject().getAsJsonArray("slots");
    assertEquals(0, slots.get(0).getAsJsonObject().get("startLagMs").getAsLong());
    assertTrue(slots.get(1).getAsJsonObject().get("startLagMs").getAsLong() >= 500);
  }

  @Test
  public void lateStartIsAdvisoryInAValidationRun() throws Exception {
    File bundle = run(Plans.plan(1), true);
    String[] skew = Bundles.row(bundle, SKEW);
    assertEquals("ADVISORY", skew[1]);
    assertTrue(skew[4],
      skew[4].startsWith("validation run: FAIL reads as ADVISORY; step 0 start skew at most "));
    assertEquals("ADVISORY", runMember(bundle).get("result").getAsString());
    assertEquals(new JsonArray(),
      Bundles.manifest(bundle).getAsJsonObject("quiet_host").get("tags"));
  }

  @Test
  public void lateStartIsAdvisoryInAContaminatedRun() throws Exception {
    File bundle =
      run(Plans.plan(1).runClass("measurement").property("pherf.verdict.noisyHost", "true"), true);
    String[] skew = Bundles.row(bundle, SKEW);
    assertEquals("ADVISORY", skew[1]);
    assertTrue(skew[4], skew[4].startsWith(
      "noisy host (pherf.verdict.noisyHost): FAIL reads as ADVISORY; step 0 start skew at most "));
    JsonArray tags = new JsonArray();
    tags.add("noisy-host");
    assertEquals(tags, Bundles.manifest(bundle).getAsJsonObject("quiet_host").get("tags"));
    assertTrue(Bundles.report(bundle).getAsJsonObject("fleet").get("contaminated").getAsBoolean());
  }

  @Test
  public void onTimeStartPassesAndNoGateSkips() throws Exception {
    File bundle = run(Plans.plan(1).runClass("measurement"), false);
    String[] skew = Bundles.row(bundle, SKEW);
    assertEquals("PASS", skew[1]);
    assertTrue(skew[4],
      skew[4].matches("highest start skew at most [0-9]+ ms \\(step 0\\), within the 50 ms bound"));
    assertEquals("PASS", Bundles.row(bundle, TELEMETRY)[1]);
    assertEquals("PASS", Bundles.row(bundle, AGENTS)[1]);

    bundle = run(Plans.plan(1).runClass("measurement"), false, "fake.gate", "false");
    assertEquals("SKIP", Bundles.row(bundle, SKEW)[1]);
  }

  /**
   * Verifies that the fleet telemetry check fails whenever an agent reports a sample count that
   * exceeds the held count recorded by the controller for that slot.
   */
  @Test
  public void reportCountAboveTheHeldCountFails() throws Exception {
    File bundle = run(Plans.plan(1), false, "fake.reportExtra.1", "3");
    String[] telemetry = Bundles.row(bundle, TELEMETRY);
    assertEquals("FAIL", telemetry[1]);
    assertEquals(HealthVerdict.COUNT, telemetry[2]);
    assertEquals("step 0 slot 1 (agent a2): the controller holds 6 samples of query:fake:q1|steady,"
      + " and the agent reported 9", telemetry[4]);
    // Ensure that a failure row in the count evidence class is never demoted, even during a
    // validation run.
    assertEquals("FAIL", runMember(bundle).get("result").getAsString());
  }

  /**
   * Verifies that the fleet-level client pool verdict row reflects the worst client pool row
   * reported across all participating agents.
   */
  @Test
  public void clientPoolIsTheWorstAgentRow() throws Exception {
    File bundle = run(Plans.plan(1), false, "fake.poolWaited.1", "2");
    String[] pool = Bundles.row(bundle, HealthVerdict.CLIENT_POOL);
    assertEquals("FAIL", pool[1]);
    assertTrue(pool[4], pool[4].startsWith("step 0 slot 1 (agent a2): 2 of "));
  }

  private static RunView.SlotView view(int slot, String agent, RunView.SlotState state,
    String error, Map<String, Long> counters) {
    return new RunView.SlotView(DataPlaneIdentity.derive(1, slot, 0), agent, state, error,
      new Share(null, null, null, null), 1, 1, 0, counters, null, null, null, null);
  }

  private static FinalReport report(Map<String, Long> counts, String... verdict) {
    return new FinalReport(0, 1, counts, Collections.<JsonObject> emptyList(),
      Arrays.asList(verdict));
  }

  private static Map<String, Long> counts(long n) {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put(KEY, n);
    return counts;
  }

  @Test
  public void telemetryCheck() {
    List<FleetVerdict.Slot> slots = new ArrayList<>();
    assertEquals(Severity.SKIP, FleetVerdict.telemetry(slots).getSeverity());
    slots.add(new FleetVerdict.Slot(0, view(0, "a1", RunView.SlotState.DONE, null, counts(5)),
      report(counts(5))));
    slots.add(
      new FleetVerdict.Slot(1, view(0, "a2", RunView.SlotState.CANCELLED, null, counts(1)), null));
    Row pass = FleetVerdict.telemetry(slots);
    assertEquals(Severity.PASS, pass.getSeverity());
    assertEquals(
      "the held counts of 1 completed slots equal the counts that their agents" + " reported",
      pass.getDetail());
    slots.add(new FleetVerdict.Slot(1, view(1, "a3", RunView.SlotState.DONE, null, counts(4)),
      report(counts(7))));
    Row fail = FleetVerdict.telemetry(slots);
    assertEquals(Severity.FAIL, fail.getSeverity());
    assertEquals(HealthVerdict.COUNT, fail.getEvidence());
    assertEquals("step 1 slot 1 (agent a3): the controller holds 4 samples of " + KEY
      + ", and the agent reported 7", fail.getDetail());
    // Verify that the telemetry check also fails when the agent report includes a key that is
    // absent from the controller's held counts.
    Map<String, Long> more = counts(4);
    more.put("query:s:other|steady", 1L);
    slots.set(2, new FleetVerdict.Slot(1, view(1, "a3", RunView.SlotState.DONE, null, counts(4)),
      report(more)));
    assertEquals("step 1 slot 1 (agent a3): the controller holds 0 samples of query:s:other|steady,"
      + " and the agent reported 1", FleetVerdict.telemetry(slots).getDetail());
  }

  /**
   * Verifies that a slot marked failed by the controller due to a running total mismatch causes the
   * fleet telemetry check to fail, even when another slot in the same step completes in the DONE
   * state with matching sample counts.
   */
  @Test
  public void totalMismatchFailsTelemetry() {
    List<FleetVerdict.Slot> slots = new ArrayList<>();
    slots.add(new FleetVerdict.Slot(0, view(0, "a1", RunView.SlotState.DONE, null, counts(5)),
      report(counts(5))));
    String mismatch = "lost or duplicated telemetry: frame 3 of " + KEY
      + " has the total 12, but the controller holds 5";
    slots.add(new FleetVerdict.Slot(0,
      view(1, "a2", RunView.SlotState.FAILED, "agent a2: " + mismatch, counts(2)), null, mismatch));
    Row fail = FleetVerdict.telemetry(slots);
    assertEquals(Severity.FAIL, fail.getSeverity());
    assertEquals(HealthVerdict.COUNT, fail.getEvidence());
    assertEquals("step 0 slot 1 (agent a2): " + mismatch, fail.getDetail());
    assertEquals(Severity.FAIL, FleetVerdict.telemetry(slots.subList(1, 2)).getSeverity());
  }

  @Test
  public void agentsCheck() {
    List<FleetVerdict.Slot> slots = new ArrayList<>();
    slots.add(new FleetVerdict.Slot(0, view(0, "a1", RunView.SlotState.DONE, null, counts(5)),
      report(counts(5))));
    Row pass = FleetVerdict.agents(RunView.State.COMPLETE, null, slots);
    assertEquals(Severity.PASS, pass.getSeverity());
    assertEquals("run COMPLETE with 1 slots done", pass.getDetail());
    slots.add(new FleetVerdict.Slot(0, view(1, "a2", RunView.SlotState.FAILED,
      "agent a2 is misconfigured: frame 1 has lowestUs 10, not 1", counts(0)), null));
    slots.add(
      new FleetVerdict.Slot(0, view(2, "a3", RunView.SlotState.CANCELLED, null, counts(0)), null));
    Row fail = FleetVerdict.agents(RunView.State.FAILED,
      "agent a2 is misconfigured: frame 1 has lowestUs 10, not 1", slots);
    assertEquals(Severity.FAIL, fail.getSeverity());
    assertEquals(HealthVerdict.COUNT, fail.getEvidence());
    assertEquals("run FAILED: agent a2 is misconfigured: frame 1 has lowestUs 10, not 1; failed"
      + " slots: step 0 slot 1 (agent a2): agent a2 is misconfigured: frame 1 has lowestUs 10,"
      + " not 1", fail.getDetail());
    assertEquals("run ABORTED: step 0 needs 3 agents but 2 are free; failed slots: none",
      FleetVerdict.agents(RunView.State.ABORTED, "step 0 needs 3 agents but 2 are free",
        Collections.<FleetVerdict.Slot> emptyList()).getDetail());
  }

  private static String line(String check, Severity severity, String evidence, String detail) {
    return new Row(check, severity, evidence, detail).toString();
  }

  @Test
  public void worstAgentRow() {
    List<FleetVerdict.Slot> slots = new ArrayList<>();
    Row none = FleetVerdict.worst(HealthVerdict.READ_SKEW, HealthVerdict.SHARE, slots);
    assertEquals(Severity.SKIP, none.getSeverity());
    assertEquals(HealthVerdict.SHARE, none.getEvidence());
    assertEquals("no slot reported a verdict", none.getDetail());
    slots.add(new FleetVerdict.Slot(0, view(0, "a1", RunView.SlotState.DONE, null, counts(1)),
      report(counts(1), line(HealthVerdict.READ_SKEW, Severity.PASS, "share", "skew 1.10"))));
    slots.add(new FleetVerdict.Slot(0, view(1, "a2", RunView.SlotState.DONE, null, counts(1)),
      report(counts(1), line(HealthVerdict.READ_SKEW, Severity.FAIL, "share", "skew 3.00"))));
    slots.add(new FleetVerdict.Slot(1, view(0, "a3", RunView.SlotState.DONE, null, counts(1)),
      report(counts(1), line(HealthVerdict.READ_SKEW, Severity.FAIL, "share", "skew 4.00"))));
    slots
      .add(new FleetVerdict.Slot(1, view(1, "a4", RunView.SlotState.DONE, null, counts(1)), report(
        counts(1), "not a row", line(HealthVerdict.CLIENT_POOL, Severity.ERROR, "count", "x"))));
    Row worst = FleetVerdict.worst(HealthVerdict.READ_SKEW, HealthVerdict.SHARE, slots);
    // Ensure that aggregation selects the row with the highest severity, breaking ties by choosing
    // the lowest step and slot indices.
    assertEquals(Severity.FAIL, worst.getSeverity());
    assertEquals(HealthVerdict.READ_SKEW, worst.getCheck());
    assertEquals("share", worst.getEvidence());
    assertEquals("step 0 slot 1 (agent a2): skew 3.00", worst.getDetail());
    Row pool = FleetVerdict.worst(HealthVerdict.CLIENT_POOL, HealthVerdict.COUNT, slots);
    assertEquals(Severity.ERROR, pool.getSeverity());
    assertEquals("step 1 slot 1 (agent a4): x", pool.getDetail());
  }

  @Test
  public void demotionChangesOnlyDurationFails() {
    List<Row> rows = Arrays.asList(new Row("d", Severity.FAIL, HealthVerdict.DURATION, "late"),
      new Row("c", Severity.FAIL, HealthVerdict.COUNT, "lost"),
      new Row("s", Severity.FAIL, HealthVerdict.SHARE, "skew"),
      new Row("p", Severity.PASS, HealthVerdict.DURATION, "ok"));
    assertEquals(rows, FleetVerdict.demote(rows, false, false));
    List<Row> validation = FleetVerdict.demote(rows, true, false);
    assertEquals(Severity.ADVISORY, validation.get(0).getSeverity());
    assertEquals("validation run: FAIL reads as ADVISORY; late", validation.get(0).getDetail());
    List<Row> noisy = FleetVerdict.demote(rows, false, true);
    assertEquals("noisy host (pherf.verdict.noisyHost): FAIL reads as ADVISORY; late",
      noisy.get(0).getDetail());
    for (List<Row> demoted : Arrays.asList(validation, noisy)) {
      for (int i = 1; i < rows.size(); i++) {
        assertEquals(rows.get(i), demoted.get(i));
      }
    }
    assertEquals(Severity.FAIL, FleetVerdict.result(validation));
    assertEquals(Severity.ADVISORY, FleetVerdict.result(validation.subList(0, 1)));
    assertEquals(Severity.PASS, FleetVerdict.result(rows.subList(3, 4)));
  }

  @Test
  public void startSkewCheck() {
    List<RunView.StepView> steps = new ArrayList<>();
    steps.add(new RunView.StepView(0, RunView.StepState.COMPLETE, 0, null,
      Collections.<RunView.SlotView> emptyList()));
    assertEquals(Severity.SKIP, FleetVerdict.startSkew(steps).getSeverity());
    steps.add(new RunView.StepView(1, RunView.StepState.COMPLETE, 5, 50L,
      Collections.<RunView.SlotView> emptyList()));
    Row pass = FleetVerdict.startSkew(steps);
    assertEquals(Severity.PASS, pass.getSeverity());
    assertEquals(HealthVerdict.DURATION, pass.getEvidence());
    assertEquals("highest start skew at most 50 ms (step 1), within the 50 ms bound",
      pass.getDetail());
    steps.add(new RunView.StepView(2, RunView.StepState.COMPLETE, 5, 51L,
      Collections.<RunView.SlotView> emptyList()));
    Row fail = FleetVerdict.startSkew(steps);
    assertEquals(Severity.FAIL, fail.getSeverity());
    assertEquals("step 2 start skew at most 51 ms, above the 50 ms bound", fail.getDetail());
    assertEquals(50, Protocol.MAX_START_SKEW_MS);
  }

  /**
   * Verifies that a gated step lacking a measured start skew prevents the check from returning PASS
   * because its start skew bound has not been proven.
   */
  @Test
  public void unprovenGatedStepSkips() {
    List<RunView.StepView> steps = new ArrayList<>();
    steps.add(new RunView.StepView(0, RunView.StepState.COMPLETE, 5, 10L,
      Collections.<RunView.SlotView> emptyList()));
    steps.add(new RunView.StepView(1, RunView.StepState.COMPLETE, 9, null,
      Collections.<RunView.SlotView> emptyList()));
    Row skip = FleetVerdict.startSkew(steps);
    assertEquals(Severity.SKIP, skip.getSeverity());
    assertEquals(HealthVerdict.DURATION, skip.getEvidence());
    assertEquals("step 1: start skew not proven, a slot with no clock sample or no dispatch;"
      + " highest proven start skew at most 10 ms (step 0)", skip.getDetail());
    assertEquals("step 1: start skew not proven, a slot with no clock sample or no dispatch",
      FleetVerdict.startSkew(steps.subList(1, 2)).getDetail());
    // Ensure that any step with a proven start skew exceeding the bound still causes the overall
    // check to fail.
    steps.add(new RunView.StepView(2, RunView.StepState.COMPLETE, 5, 51L,
      Collections.<RunView.SlotView> emptyList()));
    assertEquals(Severity.FAIL, FleetVerdict.startSkew(steps).getSeverity());
    assertEquals("no step has a start gate",
      FleetVerdict.startSkew(Collections.singletonList(new RunView.StepView(0,
        RunView.StepState.COMPLETE, 0, null, Collections.<RunView.SlotView> emptyList())))
        .getDetail());
  }
}
