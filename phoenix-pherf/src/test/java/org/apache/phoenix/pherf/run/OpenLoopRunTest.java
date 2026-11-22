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
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.apache.phoenix.pherf.result.file.Header;
import org.apache.phoenix.pherf.spi.MetricsSink;
import org.apache.phoenix.pherf.spi.RecordingExtensions;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Validates requirements RQ-166, RQ-170, and the duration stop behavior of RQ-171 end to end
 * through the Pherf CLI using the real clock. The open_loop.xml scenario schedules 50 arrivals (at
 * 1000/s for 50 ms) across queries o1 and o2 using 4 workers, with 1 warmup execution of each query
 * per worker. Because a duration stop drains the queue before terminating, the resulting execution
 * counts are exact. This test asserts only operation counts and report structure, never measured
 * timing values.
 */
public class OpenLoopRunTest {
  private static final String SCENARIO = "openLoop";
  private static final String[] QUERIES = { "o1", "o2" };

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;
  private static JsonObject report;
  private static Map<String, JsonObject> histograms;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    child.register(MetricsSink.class, RecordingExtensions.Sink.class, BarrierSink.class);
    child.systemProperty(ChildRun.REAL_CLOCK, "true");
    String fixture =
      new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/openloop/open_loop.xml")
        .getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "openloop", "-label", "o",
      "-disableSchemaApply", "-scenarioFile", fixture)));
    report = HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_o.json"));
    histograms = HdrFiles.histograms(report);
  }

  private static String key(String kind, String query, String phase) {
    return LatencyReport.tag(kind + ":" + SCENARIO + ":" + query, phase);
  }

  private static long count(String kind, String query, String phase) {
    return histograms.get(key(kind, query, phase)).get("count").getAsLong();
  }

  private static Map<String, Integer> each(int value) {
    Map<String, Integer> counts = new HashMap<>();
    for (String query : QUERIES) {
      counts.put(query, value);
    }
    return counts;
  }

  /**
   * Verifies that each query produces a service latency key in both phases and a response latency
   * key in the steady phase only.
   */
  @Test
  public void testKeys() {
    Set<String> keys = new HashSet<>();
    for (String query : QUERIES) {
      keys.add(key("query", query, LatencyRecorders.STEADY));
      keys.add(key("query", query, LatencyRecorders.WARMUP));
      keys.add(key("response", query, LatencyRecorders.STEADY));
    }
    assertEquals(keys, histograms.keySet());
  }

  /**
   * Confirms that service latency and response latency are recorded in separate histograms covering
   * the same underlying operations.
   */
  @Test
  public void testServiceAndResponseCounts() {
    for (String query : QUERIES) {
      assertEquals(25, count("query", query, LatencyRecorders.STEADY));
      assertEquals(25, count("response", query, LatencyRecorders.STEADY));
      assertEquals(4, count("query", query, LatencyRecorders.WARMUP));
    }
  }

  @Test
  public void testHlogCountsMatchJson() throws Exception {
    assertEquals(HdrFiles.countsByTag(report), HdrFiles.countsByTag(
      HdrFiles.hlog(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_o.hlog"))));
  }

  /**
   * Verifies that under a duration stop, every arrival scheduled within the window executes to
   * completion and no unfinished operations remain.
   */
  @Test
  public void testScheduleEntry() {
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(1, schedules.size());
    JsonObject entry = schedules.get(0).getAsJsonObject();
    assertEquals("duration", entry.get("stopReason").getAsString());
    assertEquals("done", entry.get("phase").getAsString());
    assertEquals(SCENARIO, entry.get("scenario").getAsString());
    assertEquals(1, entry.get("querySet").getAsInt());
    assertEquals(4, entry.get("concurrency").getAsInt());
    assertEquals(50, entry.get("scheduled").getAsLong());
    assertEquals(50, entry.get("completed").getAsLong());
    for (String zero : new String[] { "late", "dropped", "aborted", "pending" }) {
      assertEquals(zero, 0, entry.get(zero).getAsLong());
    }
    JsonArray operations = entry.getAsJsonArray("operations");
    assertEquals("query:" + SCENARIO + ":o1", operations.get(0).getAsString());
    assertEquals("query:" + SCENARIO + ":o2", operations.get(1).getAsString());
  }

  /**
   * Verifies requirements RQ-170 and R-131 by confirming that four operations were simultaneously
   * in flight within the real handler.
   */
  @Test
  public void testProductionHandlerIsNotSerialized() throws Exception {
    assertEquals(Arrays.asList(BarrierSink.TRIPPED, BarrierSink.TRIPPED, BarrierSink.TRIPPED,
      BarrierSink.TRIPPED), Records.read(child.record(BarrierSink.RECORD)));
    JsonObject entry = report.getAsJsonArray("schedules").get(0).getAsJsonObject();
    assertEquals(4, entry.get("maxInFlight").getAsInt());
  }

  /**
   * Verifies that the legacy detail CSV output records one row for each executed operation, all
   * attributed to thread 1 of 4.
   */
  @Test
  public void testDetailRows() throws Exception {
    List<String> lines = Files.readAllLines(
      new File(new File(child.work(), "RESULTS"), "RESULT_open_loop_o_detail.csv").toPath(),
      StandardCharsets.UTF_8);
    String header = Header.DETAILED_PERFORMANCE.toString();
    List<String> columns = Arrays.asList(header.split(","));
    int query = columns.indexOf("QUERY_ID");
    int thread = columns.indexOf("THREAD_NUMBER");
    int concurrency = columns.indexOf("CONCURRENCY_LEVEL");
    Map<String, Integer> rows = new HashMap<>();
    boolean body = false;
    for (String line : lines) {
      if (body) {
        String[] fields = line.split(",");
        assertEquals(line, "1", fields[thread]);
        assertEquals(line, "4", fields[concurrency]);
        rows.merge(fields[query], 1, Integer::sum);
      }
      body |= line.equals(header);
    }
    assertEquals(each(25), rows);
  }

  /**
   * Verifies that the XML result file echoes the open-loop configuration and records one run time
   * entry for each executed operation.
   */
  @Test
  public void testXmlEchoesOpenLoop() throws Exception {
    DataModelResult xml =
      (DataModelResult) JAXBContext.newInstance(DataModelResult.class).createUnmarshaller()
        .unmarshal(new File(new File(child.work(), "RESULTS"), "RESULT_open_loop_o.xml"));
    QuerySetResult querySet = xml.getScenarioResult().get(0).getQuerySetResult().get(0);
    OpenLoop openLoop = querySet.getOpenLoop();
    assertNotNull(openLoop);
    assertEquals(1, openLoop.getStage().size());
    assertEquals(50, openLoop.getStage().get(0).getDurationMs());
    assertEquals(1000.0, openLoop.getStage().get(0).getRate(), 0);
    Map<String, Integer> runTimes = new HashMap<>();
    for (QueryResult query : querySet.getQueryResults()) {
      assertEquals(1, query.getThreadTimes().size());
      ThreadTime thread = query.getThreadTimes().get(0);
      assertEquals("1,4", thread.getThreadName());
      runTimes.merge(query.getId(), thread.getRunTimesInMs().size(), Integer::sum);
    }
    assertEquals(each(25), runTimes);
  }

  /**
   * Verifies that the metrics sink receives one query latency record for each executed steady
   * operation.
   */
  @Test
  public void testSinkRecords() throws Exception {
    Map<String, Integer> records = new HashMap<>();
    for (String line : Records.withoutMarks(child.record(Records.SINK))) {
      String[] fields = line.split("\t");
      if (fields[0].equals("query.elapsed_ms")) {
        for (String query : QUERIES) {
          if (fields[1].contains("query=" + query + ",")) {
            records.merge(query, 1, Integer::sum);
          }
        }
      }
    }
    assertEquals(each(25), records);
  }
}
