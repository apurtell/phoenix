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
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataLoadThreadTime;
import org.apache.phoenix.pherf.result.file.Header;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that executing a query workload with a scripted microsecond clock produces rounded
 * milliseconds in the legacy CSV and XML reports while recording full microseconds in the JSON
 * report and HDR logs. The script assigns elapsed durations of 2,600 us, 1,234 us, and 300 us to
 * q1, q2, and q3, respectively. The resulting legacy reports must differ from the P3 golden files
 * only in their duration values and their label.
 */
public class LatencyRunTest {
  private static final String TEST = LatencyRunTest.class.getSimpleName();
  private static final String DIR = "latency-run/";
  private static final String[] QUERIES = { "q1", "q2", "q3" };

  @ClassRule
  public static TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;

  @BeforeClass
  public static void runOnce() throws Exception {
    child = new ChildJvm(folder.getRoot());
    child.systemProperty(ChildRun.ELAPSED_MICROS, "2600,1234,300");
    child.pherfProperty(LatencyRecorders.INTERVAL_PROPERTY, "3600000");
    String scenario = new File(ChildJvm.scenarioDir(), "test_tenant_binding.xml").getPath();
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-z", "golden", "-label", "us",
      "-disableSchemaApply", "-scenarioFile", scenario)));
  }

  private static File results() {
    return new File(child.work(), "RESULTS");
  }

  private static File hdr(String name) {
    return new File(new File(child.work(), "RESULTS_HDR"), name);
  }

  private static String key(String query) {
    return LatencyReport.tag("query:testTenantBinding:" + query, LatencyRecorders.STEADY);
  }

  @Test
  public void testLegacyHeadersMatchGolden() throws Exception {
    StringBuilder text = new StringBuilder();
    for (Header header : Header.values()) {
      text.append(header.name()).append('=').append(header).append('\n');
    }
    text.append("DataLoadThreadTime=")
      .append(new DataLoadThreadTime().getCsvTitle().replace("\n", "\\n")).append('\n');
    Golden.assertMatches(TEST, "legacy-headers.golden", text.toString());
  }

  @Test
  public void testLegacyReportsRoundMicros() throws Exception {
    String[] names = results().list();
    Arrays.sort(names);
    Map<String, String> outputs = new LinkedHashMap<>();
    outputs.put("files.golden", String.join("\n", names) + "\n");
    for (String name : names) {
      outputs.put(name + ".golden",
        new String(Files.readAllBytes(new File(results(), name).toPath()), StandardCharsets.UTF_8));
    }
    for (Map.Entry<String, String> output : outputs.entrySet()) {
      Golden.capture(TEST, DIR + output.getKey(), output.getValue());
    }
    for (Map.Entry<String, String> output : outputs.entrySet()) {
      Golden.assertMatches(TEST, DIR + output.getKey(), output.getValue());
    }
  }

  @Test
  public void testJsonGivesMicros() throws Exception {
    JsonObject report = HdrFiles.json(hdr("LATENCY_us.json"));
    assertEquals(LatencyReport.FORMAT, report.get("format").getAsString());
    assertEquals(LatencyReport.VERSION, report.get("version").getAsInt());
    assertEquals("us", report.get("label").getAsString());
    Map<String, JsonObject> histograms = HdrFiles.histograms(report);
    assertEquals(new HashSet<>(Arrays.asList(key("q1"), key("q2"), key("q3"))),
      histograms.keySet());

    JsonObject q1 = histograms.get(key("q1"));
    assertTrue(
      new Histogram(1, 600_000_000L, 3).valuesAreEquivalent(2_600, q1.get("p50Us").getAsLong()));
    JsonObject q2 = histograms.get(key("q2"));
    assertEquals(1_234, q2.get("minUs").getAsLong());
    assertEquals(1_234, q2.get("p50Us").getAsLong());
    assertEquals(1_234, q2.get("maxUs").getAsLong());
    // Query q3 took 300 us; the JSON report retains this value, whereas the legacy reports
    // show 0 ms.
    JsonObject q3 = histograms.get(key("q3"));
    assertEquals(300, q3.get("p50Us").getAsLong());
    for (String query : QUERIES) {
      assertEquals(1, histograms.get(key(query)).get("count").getAsLong());
    }
  }

  @Test
  public void testHlogReloads() throws Exception {
    JsonObject report = HdrFiles.json(hdr("LATENCY_us.json"));
    assertEquals("LATENCY_us.hlog", report.get("hlog").getAsString());
    List<Histogram> reloaded = HdrFiles.hlog(hdr("LATENCY_us.hlog"));
    long total = 0;
    for (Histogram histogram : reloaded) {
      HdrFiles.assertPinned(histogram);
      total += histogram.getTotalCount();
    }
    long jsonTotal = 0;
    for (long count : HdrFiles.countsByTag(report).values()) {
      jsonTotal += count;
    }
    // The detail CSV contains one detail row for each query run following the initial header row.
    List<String> detail =
      Files.readAllLines(new File(results(), "RESULT_test_tenant_binding_us_detail.csv").toPath(),
        StandardCharsets.UTF_8);
    assertEquals(3, total);
    assertEquals(jsonTotal, total);
    assertEquals(detail.size() - 1, total);
    assertEquals(HdrFiles.countsByTag(report), HdrFiles.countsByTag(reloaded));
  }

  @Test
  public void testIntervalsFromProperty() throws Exception {
    JsonObject report = HdrFiles.json(hdr("LATENCY_us.json"));
    assertEquals("LATENCY_us_intervals.hlog", report.get("intervals").getAsString());
    List<Histogram> intervals = HdrFiles.hlog(hdr("LATENCY_us_intervals.hlog"));
    assertFalse(intervals.isEmpty());
    assertEquals(HdrFiles.countsByTag(report), HdrFiles.countsByTag(intervals));
  }

  @Test
  public void testNoNewFileInResults() {
    for (String name : results().list()) {
      assertFalse(name, name.startsWith("LATENCY"));
    }
  }
}
