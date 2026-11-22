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
import static org.junit.Assert.assertTrue;

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
import org.HdrHistogram.Histogram;
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
 * Verifies RQ-164, under which each worker thread performs K warmup executions followed by N
 * measured executions for its assigned query. In this test scenario, query set 1 runs query w1 on 1
 * thread with K=2 warmup executions and N=3 measured executions, while query set 2 runs query w2
 * across 2 threads with K=3 warmup executions and N=4 measured executions per thread. A scripted
 * microsecond clock assigns 900,000 us to each of the two warmup executions of w1, followed by 100
 * us, 200 us, and 300 us for its three measured executions, while all subsequent executions
 * complete in 0 us.
 */
public class WarmupRunTest {
  private static final String FIXTURE = "test_warmup";
  private static final String SCENARIO = "testWarmup";
  private static final Histogram PRECISION = new Histogram(1, 600_000_000L, 3);

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;
  private static Map<String, JsonObject> histograms;
  private static JsonObject report;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    child.register(MetricsSink.class, RecordingExtensions.Sink.class);
    child.systemProperty(ChildRun.ELAPSED_MICROS, "900000,900000,100,200,300");
    String fixture = new File(ChildJvm.scenarioDir(), FIXTURE + ".xml").getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "warmup", "-label", "w",
      "-disableSchemaApply", "-scenarioFile", fixture)));
    report = HdrFiles.json(hdr("LATENCY_w.json"));
    histograms = HdrFiles.histograms(report);
  }

  private static File hdr(String name) {
    return new File(new File(child.work(), "RESULTS_HDR"), name);
  }

  private static File result(String name) {
    return new File(new File(child.work(), "RESULTS"), name);
  }

  private static String key(String query, String phase) {
    return LatencyReport.tag("query:" + SCENARIO + ":" + query, phase);
  }

  private static long count(String query, String phase) {
    return histograms.get(key(query, phase)).get("count").getAsLong();
  }

  private static Map<String, Integer> expected(int w1, int w2) {
    Map<String, Integer> counts = new HashMap<>();
    counts.put("w1", w1);
    counts.put("w2", w2);
    return counts;
  }

  /**
   * Verifies that the latency report contains exactly one steady-phase histogram key and one
   * warmup-phase histogram key for each query.
   */
  @Test
  public void testKeysPerPhase() {
    Set<String> keys = new HashSet<>();
    for (String query : new String[] { "w1", "w2" }) {
      keys.add(key(query, LatencyRecorders.STEADY));
      keys.add(key(query, LatencyRecorders.WARMUP));
    }
    assertEquals(keys, histograms.keySet());
  }

  /**
   * Verifies that the steady-phase sample count equals exactly N executions per thread and the
   * warmup-phase sample count equals K executions per thread.
   */
  @Test
  public void testCountsPerPhase() {
    assertEquals(3, count("w1", LatencyRecorders.STEADY));
    assertEquals(4 * 2, count("w2", LatencyRecorders.STEADY));
    assertEquals(2, count("w1", LatencyRecorders.WARMUP));
    assertEquals(3 * 2, count("w2", LatencyRecorders.WARMUP));
  }

  /**
   * Verifies that latency samples from the slow warmup executions are isolated from the steady
   * histogram and do not reach it.
   */
  @Test
  public void testWarmupSamplesStayOutOfSteady() {
    JsonObject steady = histograms.get(key("w1", LatencyRecorders.STEADY));
    JsonObject warmup = histograms.get(key("w1", LatencyRecorders.WARMUP));
    assertTrue(steady.toString(),
      PRECISION.valuesAreEquivalent(300, steady.get("maxUs").getAsLong()));
    assertTrue(warmup.toString(),
      PRECISION.valuesAreEquivalent(900_000, warmup.get("minUs").getAsLong()));
  }

  /**
   * Verifies that the histogram run log records the exact same sample counts per key as the JSON
   * latency report.
   */
  @Test
  public void testHlogCountsMatchJson() throws Exception {
    assertEquals(HdrFiles.countsByTag(report),
      HdrFiles.countsByTag(HdrFiles.hlog(hdr("LATENCY_w.hlog"))));
  }

  /**
   * Verifies that the legacy detail CSV output records one row for each measured execution only,
   * excluding warmup executions.
   */
  @Test
  public void testDetailRowsCountMeasuredOnly() throws Exception {
    List<String> lines = Files.readAllLines(result("RESULT_" + FIXTURE + "_w_detail.csv").toPath(),
      StandardCharsets.UTF_8);
    String header = Header.DETAILED_PERFORMANCE.toString();
    int column = Arrays.asList(header.split(",")).indexOf("QUERY_ID");
    Map<String, Integer> rows = new HashMap<>();
    boolean body = false;
    for (String line : lines) {
      if (body) {
        rows.merge(line.split(",")[column], 1, Integer::sum);
      }
      body |= line.equals(header);
    }
    assertEquals(expected(3, 8), rows);
  }

  /**
   * Verifies that the XML result report preserves the configured warmup setting and records exactly
   * N measured run times for each thread.
   */
  @Test
  public void testXmlRunTimesCountMeasuredOnly() throws Exception {
    DataModelResult xml = (DataModelResult) JAXBContext.newInstance(DataModelResult.class)
      .createUnmarshaller().unmarshal(result("RESULT_" + FIXTURE + "_w.xml"));
    Map<String, Integer> runTimes = new HashMap<>();
    Map<String, Long> warmups = new HashMap<>();
    for (QuerySetResult querySet : xml.getScenarioResult().get(0).getQuerySetResult()) {
      for (QueryResult query : querySet.getQueryResults()) {
        warmups.put(query.getId(), querySet.getWarmupExecutions());
        for (ThreadTime thread : query.getThreadTimes()) {
          assertEquals(thread.getThreadName(), querySet.getNumberOfExecutions(),
            thread.getRunTimesInMs().size());
          runTimes.merge(query.getId(), thread.getRunTimesInMs().size(), Integer::sum);
        }
      }
    }
    assertEquals(expected(3, 8), runTimes);
    Map<String, Long> expectedWarmups = new HashMap<>();
    expectedWarmups.put("w1", 2L);
    expectedWarmups.put("w2", 3L);
    assertEquals(expectedWarmups, warmups);
  }

  /**
   * Verifies that the metrics sink receives events for the measured executions only, omitting
   * warmup executions.
   */
  @Test
  public void testSinkGetsMeasuredOnly() throws Exception {
    Map<String, Integer> records = new HashMap<>();
    for (String line : Records.withoutMarks(child.record(Records.SINK))) {
      String[] fields = line.split("\t");
      if (fields[0].equals("query.elapsed_ms")) {
        for (String query : new String[] { "w1", "w2" }) {
          if (fields[1].contains("query=" + query + ",")) {
            records.merge(query, 1, Integer::sum);
          }
        }
      }
    }
    assertEquals(expected(3, 8), records);
  }
}
