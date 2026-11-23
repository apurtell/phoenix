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
package org.apache.phoenix.pherf.latency;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.distributed.Bundles;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.junit.Test;

/**
 * Verifies that the fleet report conforms to version 1 of the P4 JSON report format and that its
 * run log conforms to the P4 run log format. This test resides in the same package as LatencyReport
 * so that it can invoke the standard Pherf writer directly on the same input data. Aside from the
 * top-level fleet member and the per-schedule slot metadata fields, the resulting JSON report trees
 * must be identical, and the generated run logs must match byte for byte.
 */
public class FleetReportParityTest {
  private static final String[] EXTRAS = { "step", "slot", "agent" };

  private static List<Histogram> copies(List<Histogram> totals) {
    List<Histogram> copies = new ArrayList<>();
    for (Histogram total : totals) {
      Histogram copy = total.copy();
      copy.setTag(total.getTag());
      copies.add(copy);
    }
    return copies;
  }

  /**
   * Returns a copy of the fleet report tree with the fleet member and the slot fields in its
   * schedules removed.
   */
  private static JsonObject pherfPart(JsonObject fleetReport) {
    JsonObject tree = fleetReport.deepCopy();
    tree.remove("fleet");
    if (tree.has("schedules")) {
      for (JsonElement schedule : tree.getAsJsonArray("schedules")) {
        for (String extra : EXTRAS) {
          schedule.getAsJsonObject().remove(extra);
        }
      }
    }
    return tree;
  }

  private static void assertParity(File fleet, File pherf) throws Exception {
    JsonObject fleetReport = Bundles.json(new File(fleet, "LATENCY_run.json"));
    JsonObject pherfReport = Bundles.json(new File(pherf, "LATENCY_run.json"));
    JsonObject part = pherfPart(fleetReport);
    assertEquals(pherfReport, part);
    // Verify that both JSON objects contain the exact same top-level members in the same order.
    assertEquals(new ArrayList<>(pherfReport.keySet()), new ArrayList<>(part.keySet()));
    assertArrayEquals(Files.readAllBytes(new File(pherf, "LATENCY_run.hlog").toPath()),
      Files.readAllBytes(new File(fleet, "LATENCY_run.hlog").toPath()));
  }

  @Test
  public void writerEqualsThePherfWriter() throws Exception {
    File root = Simulation.runDir();
    File fleet = new File(root, "fleet");
    File pherf = new File(root, "pherf");
    Files.createDirectories(fleet.toPath());
    Files.createDirectories(pherf.toPath());
    List<Histogram> totals = new ArrayList<>();
    String[][] keys = { { "query:s:a b,c", LatencyRecorders.STEADY },
      { "query:s:q", LatencyRecorders.STEADY }, { "query:s:q", LatencyRecorders.WARMUP } };
    for (int k = 0; k < keys.length; k++) {
      Histogram total = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
        LatencyRecorders.DIGITS);
      for (int i = 1; i <= 1000 * (k + 1); i++) {
        total.recordValue(i * 37L % 900_000 + k);
      }
      total.setTag(LatencyReport.tag(keys[k][0], keys[k][1]));
      totals.add(total);
    }
    JsonObject schedule = JsonParser.parseString("{\"scenario\":\"s\",\"querySet\":1,"
      + "\"startMs\":1767225601000,\"endMs\":1767225602000,\"lastTakeMs\":1767225601999,"
      + "\"scheduled\":10,\"completed\":10,\"targetRate\":0.0}").getAsJsonObject();
    JsonObject slotted = schedule.deepCopy();
    slotted.addProperty("step", 0);
    slotted.addProperty("slot", 1);
    slotted.addProperty("agent", "a2");
    JsonObject member = new JsonObject();
    member.addProperty("runId", "r");
    List<JsonObject> fleetSchedules = new ArrayList<>();
    fleetSchedules.add(slotted);
    List<Object> pherfSchedules = new ArrayList<>();
    pherfSchedules.add(schedule);
    RunBundle.writeLatency(fleet, 1767225600123L, 1767225609877L, copies(totals), fleetSchedules,
      member);
    LatencyReport.write(pherf, "run", 1767225600123L, 1767225609877L, false, copies(totals),
      pherfSchedules);
    assertParity(fleet, pherf);
  }

  /**
   * Verifies that the result bundle produced by a simulated distributed run matches the output of
   * the Pherf writer when invoked on the run's merged histogram totals and time window.
   */
  @Test
  public void bundleEqualsThePherfWriter() throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    String runId = simulation.controller.submit(
      Plans.plan(3).step(Plans.step(2).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
        .property("fake.seed", "3").property("fake.warmup", "2")).spec());
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    File results = Bundles.results(simulation.bundle(runId));
    JsonObject report = Bundles.json(new File(results, "LATENCY_run.json"));
    List<Histogram> totals = Bundles.hlog(new File(results, "LATENCY_run.hlog"));
    assertEquals(2, totals.size());
    File pherf = new File(Simulation.runDir(), "pherf");
    Files.createDirectories(pherf.toPath());
    LatencyReport.write(pherf, "run", report.get("startMs").getAsLong(),
      report.get("endMs").getAsLong(), false, totals, new ArrayList<Object>());
    assertParity(results, pherf);
    Map<String, Long> counts = Bundles.counts(report);
    assertTrue(counts.toString(), counts.get("query:fake:q1|warmup") > 0);
  }
}
