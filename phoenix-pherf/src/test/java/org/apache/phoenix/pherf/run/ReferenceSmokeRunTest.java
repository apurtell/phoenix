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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies RQ-192 by executing each reference scenario with its smoke profile through the Pherf CLI
 * (-l -q) across three invocations in a single child JVM, using the real clock and the fake driver.
 * Each execution must produce the exact expected operation count for every operation kind. Because
 * the fake driver stores no rows, row count verification is left to the mini cluster test.
 */
public class ReferenceSmokeRunTest {
  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    // Range statements resolve the leading key column by inspecting the primary key metadata.
    child.register(ConnectionProvider.class, KeyedStatementRecorder.class);
    child.systemProperty(ChildRun.REAL_CLOCK, "true");
    List<List<String>> invocations = new ArrayList<>();
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      invocations.add(
        ChildJvm.args("-l", "-q", "-z", "reference", "-label", reference.name, "-writerThreadSize",
          "2", "-disableSchemaApply", "-scenarioFile", reference.scenarioFile().getPath()));
    }
    child.runCli(invocations);
  }

  private static JsonObject report(ReferenceScenarios reference) throws Exception {
    return HdrFiles
      .json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_" + reference.name + ".json"));
  }

  @Test
  public void testOperationCountsOfEachSmoke() throws Exception {
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      Map<String, Long> expected = new HashMap<>();
      String[][] keys = { { "query", ReferenceScenarios.LOOKUP },
        { "scan", ReferenceScenarios.SCAN }, { "delete", ReferenceScenarios.DELETE } };
      for (String[] key : keys) {
        long count = reference.operations(key[1]);
        expected.put(LatencyReport.tag(key[0] + ":" + reference.scenario + ":" + key[1],
          LatencyRecorders.STEADY), count);
        expected.put(LatencyReport.tag("response:" + reference.scenario + ":" + key[1],
          LatencyRecorders.STEADY), count);
      }
      Map<String, Long> counts = new HashMap<>();
      for (Map.Entry<String, Long> entry : HdrFiles.countsByTag(report(reference)).entrySet()) {
        // The data loading phase triggered by -l records its own separate keys.
        if (!entry.getKey().startsWith("load")) {
          counts.put(entry.getKey(), entry.getValue());
        }
      }
      assertEquals(reference.name, expected, counts);
    }
  }

  @Test
  public void testEverySmokeArrivalRan() throws Exception {
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      JsonArray schedules = report(reference).getAsJsonArray("schedules");
      assertEquals(1, schedules.size());
      JsonObject entry = schedules.get(0).getAsJsonObject();
      assertEquals(reference.scenario, entry.get("scenario").getAsString());
      assertEquals(1, entry.get("concurrency").getAsInt());
      assertEquals(reference.name, reference.arrivals, entry.get("scheduled").getAsLong());
      assertEquals(reference.name, reference.arrivals, entry.get("completed").getAsLong());
      for (String zero : new String[] { "dropped", "aborted", "pending" }) {
        assertEquals(reference.name + " " + zero, 0, entry.get(zero).getAsLong());
      }
    }
  }
}
