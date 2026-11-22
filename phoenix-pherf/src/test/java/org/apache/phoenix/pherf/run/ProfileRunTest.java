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
package org.apache.phoenix.pherf.run;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies RQ-190 end-to-end through the Pherf CLI using the real clock. The scenario file
 * profile_run.xml defines two scenarios that reference the same profile: a 70/20/10 operation mix
 * of SELECT, SCAN, and DELETE scheduled across a rate curve of 500 ms at 1000/s followed by 500 ms
 * ramping from 1000/s to 2000/s. This schedule produces 500 + 750 = 1250 arrivals at a concurrency
 * of 4.
 * <p>
 * Stated tolerance: the configured weights reduce to 7/2/1 (W = 10), and every cycle of W arrivals
 * matches those proportions exactly. The execution count for each kind is therefore exactly N * w /
 * W when W divides N, and within 1 of that value otherwise (as verified by MixOrderTest). Because W
 * divides 1250, the expected counts are exactly 875, 250, and 125. Stopping at the end of the
 * configured duration drains the queue, ensuring that every count is exact and all 1250 arrivals on
 * the curve complete.
 */
public class ProfileRunTest {
  private static final String[] SCENARIOS = { "profileA", "profileB" };
  private static final long ARRIVALS = 1250;

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;
  private static JsonObject report;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    child.systemProperty(ChildRun.REAL_CLOCK, "true");
    String fixture =
      new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/profile/profile_run.xml")
        .getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "profile", "-label", "p",
      "-disableSchemaApply", "-scenarioFile", fixture)));
    report = HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_p.json"));
  }

  /**
   * Verifies that the steady-state execution counts for each operation kind match the exact
   * 70/20/10 proportions of the 1250 arrivals in each scenario.
   */
  @Test
  public void testMixCounts() {
    Map<String, Long> expected = new HashMap<>();
    for (String scenario : SCENARIOS) {
      expected.put(LatencyReport.tag("query:" + scenario + ":get", LatencyRecorders.STEADY), 875L);
      expected.put(LatencyReport.tag("scan:" + scenario + ":scan", LatencyRecorders.STEADY), 250L);
      expected.put(LatencyReport.tag("delete:" + scenario + ":delete", LatencyRecorders.STEADY),
        125L);
      expected.put(LatencyReport.tag("response:" + scenario + ":get", LatencyRecorders.STEADY),
        875L);
      expected.put(LatencyReport.tag("response:" + scenario + ":scan", LatencyRecorders.STEADY),
        250L);
      expected.put(LatencyReport.tag("response:" + scenario + ":delete", LatencyRecorders.STEADY),
        125L);
    }
    assertEquals(expected, HdrFiles.countsByTag(report));
  }

  /**
   * Verifies that the load curve defined by the profile executed completely, with each scenario
   * scheduling and completing all 1250 arrivals.
   */
  @Test
  public void testScheduleEntries() {
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(2, schedules.size());
    for (int i = 0; i < SCENARIOS.length; i++) {
      JsonObject entry = schedules.get(i).getAsJsonObject();
      assertEquals(SCENARIOS[i], entry.get("scenario").getAsString());
      assertEquals("duration", entry.get("stopReason").getAsString());
      assertEquals(4, entry.get("concurrency").getAsInt());
      assertEquals(ARRIVALS, entry.get("scheduled").getAsLong());
      assertEquals(ARRIVALS, entry.get("completed").getAsLong());
      for (String zero : new String[] { "dropped", "aborted", "pending" }) {
        assertEquals(zero, 0, entry.get(zero).getAsLong());
      }
    }
  }

  /**
   * Verifies that the marshaled XML result records the profile name along with its open-loop stages
   * and operation mix.
   */
  @Test
  public void testXmlShowsTheProfile() throws Exception {
    DataModelResult xml =
      (DataModelResult) JAXBContext.newInstance(DataModelResult.class).createUnmarshaller()
        .unmarshal(new File(new File(child.work(), "RESULTS"), "RESULT_profile_run_p.xml"));
    assertEquals(2, xml.getScenarioResult().size());
    for (ScenarioResult scenario : xml.getScenarioResult()) {
      QuerySetResult querySet = scenario.getQuerySetResult().get(0);
      assertEquals("mix_profile.xml", querySet.getProfile());
      OpenLoop openLoop = querySet.getOpenLoop();
      assertEquals(2, openLoop.getStage().size());
      assertEquals(500, openLoop.getStage().get(0).getDurationMs());
      assertEquals(1000.0, openLoop.getStage().get(0).getRate(), 0);
      assertEquals(500, openLoop.getStage().get(1).getDurationMs());
      assertEquals(Double.valueOf(2000), openLoop.getStage().get(1).getEndRate());
      assertEquals(3, querySet.getMix().size());
      assertEquals("DELETE", querySet.getMix().get(2).getKind());
      assertEquals("10", querySet.getMix().get(2).getWeight());
    }
  }
}
