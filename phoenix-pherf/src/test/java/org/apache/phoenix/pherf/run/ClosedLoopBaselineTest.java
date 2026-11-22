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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirement RQ-165 by confirming that a scenario without an open loop executes the
 * closed-loop path identically to its behavior before P6. Running the DefaultRunGoldenTest
 * configuration produces the same CSV and XML output files and recorded connections as its golden
 * files, which this test only reads. The resulting JSON report contains neither a schedule entry
 * nor any response key, and each query key records a count equal to the number of run times for
 * that query in the XML report.
 */
public class ClosedLoopBaselineTest {
  private static final String TEST = ClosedLoopBaselineTest.class.getSimpleName();
  private static final String DIR = "default-run/";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  @Test
  public void testNoOpenLoopRunsTheClosedLoopAsBefore() throws Exception {
    ChildJvm child = new ChildJvm(folder.getRoot());
    String scenario = new File(ChildJvm.scenarioDir(), "test_tenant_binding.xml").getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-l", "-q", "-z", "golden", "-label",
      "golden", "-writerThreadSize", "2", "-disableSchemaApply", "-scenarioFile", scenario)));

    File results = new File(child.work(), "RESULTS");
    String[] names = results.list();
    Arrays.sort(names);
    Golden.assertMatches(TEST, DIR + "files.golden", String.join("\n", names) + "\n");
    for (String name : names) {
      Golden.assertMatches(TEST, DIR + name + ".golden",
        new String(Files.readAllBytes(new File(results, name).toPath()), StandardCharsets.UTF_8));
    }
    List<String> connections = Records.withoutMarks(child.record(Records.CONNECTIONS));
    Collections.sort(connections);
    Golden.assertMatches(TEST, DIR + "connections.golden", String.join("\n", connections) + "\n");

    JsonObject report =
      HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_golden.json"));
    assertNull(report.get("schedules"));
    Map<String, Long> runTimes = new HashMap<>();
    DataModelResult xml = (DataModelResult) JAXBContext.newInstance(DataModelResult.class)
      .createUnmarshaller().unmarshal(new File(results, "RESULT_test_tenant_binding_golden.xml"));
    for (ScenarioResult scenarioResult : xml.getScenarioResult()) {
      for (QuerySetResult querySet : scenarioResult.getQuerySetResult()) {
        assertNull(querySet.getOpenLoop());
        for (QueryResult query : querySet.getQueryResults()) {
          for (ThreadTime thread : query.getThreadTimes()) {
            runTimes
              .merge(LatencyReport.tag("query:" + scenarioResult.getName() + ":" + query.getId(),
                LatencyRecorders.STEADY), (long) thread.getRunTimesInMs().size(), Long::sum);
          }
        }
      }
    }
    assertFalse(runTimes.isEmpty());
    Map<String, Long> queryCounts = new HashMap<>();
    for (Map.Entry<String, Long> entry : HdrFiles.countsByTag(report).entrySet()) {
      String operation = LatencyReport.parseTag(entry.getKey())[0];
      assertFalse(entry.getKey(), operation.startsWith("response:"));
      if (operation.startsWith("query:")) {
        queryCounts.put(entry.getKey(), entry.getValue());
      }
    }
    assertEquals(runTimes, queryCounts);
    assertTrue(runTimes.values().stream().allMatch(count -> count > 0));
  }
}
