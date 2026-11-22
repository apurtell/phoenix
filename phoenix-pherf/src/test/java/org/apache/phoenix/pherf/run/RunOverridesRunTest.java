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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies RQ-191 through the Pherf CLI using the real clock in a single run configured with
 * -runDurationMs 100, -targetRate 2000, and -threadCount 3. The overrides.xml fixture defines an
 * open loop query set running for 50 ms at 1000/s and a closed loop query set running for 600 s,
 * both configured at concurrency 1.
 * <ul>
 * <li>The open loop schedules exactly 100 ms x 2000/s = 200 arrivals. Without either override it
 * would schedule 50 arrivals, and with only one of the two overrides applied it would schedule
 * 100.</li>
 * <li>The closed loop terminates after 100 ms. Without the duration override, it would continue
 * running past the timeout of the child JVM.</li>
 * <li>Both query sets execute concurrently across 3 threads.</li>
 * </ul>
 */
public class RunOverridesRunTest {
  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;
  private static List<QuerySetResult> sets;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    child.systemProperty(ChildRun.REAL_CLOCK, "true");
    String fixture =
      new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/overrides/overrides.xml")
        .getPath();
    child.runCli(Collections.singletonList(
      ChildJvm.args("-q", "-z", "overrides", "-label", "v", "-disableSchemaApply", "-runDurationMs",
        "100", "-targetRate", "2000", "-threadCount", "3", "-scenarioFile", fixture)));
    DataModelResult xml =
      (DataModelResult) JAXBContext.newInstance(DataModelResult.class).createUnmarshaller()
        .unmarshal(new File(new File(child.work(), "RESULTS"), "RESULT_run_overrides_v.xml"));
    sets = xml.getScenarioResult().get(0).getQuerySetResult();
  }

  /** Verifies that the open loop query set executes the overridden rate curve across 3 workers. */
  @Test
  public void testOpenLoopUsesTheOverrides() throws Exception {
    JsonObject report =
      HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_v.json"));
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(1, schedules.size());
    JsonObject entry = schedules.get(0).getAsJsonObject();
    assertEquals(3, entry.get("concurrency").getAsInt());
    assertEquals(200, entry.get("scheduled").getAsLong());
    assertEquals(200, entry.get("completed").getAsLong());
    for (String zero : new String[] { "dropped", "aborted", "pending" }) {
      assertEquals(zero, 0, entry.get(zero).getAsLong());
    }
    QuerySetResult open = sets.get(0);
    assertEquals("3", open.getConcurrency());
    OpenLoop openLoop = open.getOpenLoop();
    assertEquals(1, openLoop.getStage().size());
    assertEquals(100, openLoop.getStage().get(0).getDurationMs());
    assertEquals(2000.0, openLoop.getStage().get(0).getRate(), 0);
    ThreadTime thread = open.getQueryResults().get(0).getThreadTimes().get(0);
    assertEquals("1,3", thread.getThreadName());
    assertEquals(200, thread.getRunTimesInMs().size());
  }

  /**
   * Verifies that the closed loop query set runs across 3 threads and that each thread stops at the
   * overridden duration.
   */
  @Test
  public void testClosedLoopUsesTheOverrides() {
    QuerySetResult closed = sets.get(1);
    assertEquals("3", closed.getConcurrency());
    assertEquals(100, closed.getExecutionDurationInMs());
    assertEquals(1000000000L, closed.getNumberOfExecutions());
    assertEquals(1, closed.getQueryResults().size());
    QueryResult query = closed.getQueryResults().get(0);
    Set<String> names = new HashSet<>();
    for (ThreadTime thread : query.getThreadTimes()) {
      names.add(thread.getThreadName());
      assertFalse(thread.getThreadName(), thread.getRunTimesInMs().isEmpty());
    }
    assertEquals(new HashSet<>(Arrays.asList("1,3", "2,3", "3,3")), names);
  }
}
