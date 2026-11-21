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
package org.apache.phoenix.pherf.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.file.Header;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.run.Records;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A single child JVM executes Pherf four times with a recording connection provider, metrics sink,
 * and report section registered through META-INF/services:
 * <ol>
 * <li>The first run performs drop, schema, load, statistics, and query operations on the group
 * properties fixture.</li>
 * <li>The second run executes a multi-tenant load profile and its queries.</li>
 * <li>The third run performs the -diff export of the group properties fixture.</li>
 * <li>The fourth run executes the queries of the group properties fixture through the thin driver
 * URL.</li>
 * </ol>
 * The thin run is executed last because the thin driver flag is static.
 */
public class ExtensionsRunTest {
  private static final String FIXTURE = "test_group_properties";
  private static final String GROUP = "pherf.test.group";

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm child;
  private static List<List<String>> connections;
  private static List<List<String>> provided;
  private static List<List<String>> metrics;

  @BeforeClass
  public static void runPherf() throws Exception {
    child = new ChildJvm(FOLDER.getRoot());
    child.register(ConnectionProvider.class, RecordingExtensions.Provider.class);
    child.register(MetricsSink.class, RecordingExtensions.Sink.class);
    child.register(ReportSection.class, RecordingExtensions.KnownKeySection.class);
    String fixture = scenario(FIXTURE + ".xml");
    String schema = new File(ChildJvm.datamodelDir(), "test_schema.sql").getPath();
    List<List<String>> invocations = new ArrayList<>();
    invocations.add(ChildJvm.args("-drop", "all", "-l", "-stats", "-q", "-writerThreadSize", "2",
      "-z", "p3", "-label", "p3", "-schemaFile", schema, "-scenarioFile", fixture));
    invocations.add(ChildJvm.args("-mt", "-q", "-z", "p3", "-label", "p3", "-disableSchemaApply",
      "-scenarioFile", scenario("test_tbl_workload_template.xml")));
    invocations.add(ChildJvm.args("-q", "-diff", "-z", "p3", "-label", "p3", "-disableSchemaApply",
      "-scenarioFile", fixture));
    invocations.add(ChildJvm.args("-t", "-s", "http://fake", "-q", "-label", "p3",
      "-disableSchemaApply", "-scenarioFile", fixture));
    child.runCli(invocations);

    connections = Records.byInvocation(child.record(Records.CONNECTIONS));
    provided = Records.byInvocation(child.record(Records.PROVIDER));
    metrics = Records.byInvocation(child.record(Records.SINK));
    assertEquals(invocations.size(), connections.size());
    assertEquals(invocations.size(), provided.size());
    assertEquals(invocations.size(), metrics.size());
  }

  private static String scenario(String name) {
    return new File(ChildJvm.scenarioDir(), name).getPath();
  }

  /**
   * RQ-153: Verifies that the connection provider opens every connection that reaches the JDBC
   * driver.
   */
  @Test
  public void testEveryConnectionPassesThroughProvider() {
    for (int i = 0; i < connections.size(); i++) {
      List<String> driver = new ArrayList<>(connections.get(i));
      List<String> provider = new ArrayList<>(provided.get(i));
      Collections.sort(driver);
      Collections.sort(provider);
      assertFalse("Invocation " + (i + 1) + " opened no connection", driver.isEmpty());
      assertEquals("Invocation " + (i + 1), driver, provider);
    }
    // The write path passes the Pherf properties, whereas the thin run uses only the thin URL.
    boolean write = false;
    for (String line : connections.get(0)) {
      write |= properties(line).containsKey("pherf.default.dataloader.threadpool");
    }
    assertTrue("No write connection in invocation 1", write);
    for (String line : connections.get(3)) {
      assertTrue(line, line.startsWith("jdbc:phoenix:thin:url=http://fake;"));
    }
  }

  /**
   * RQ-154: Verifies that each querySet connection receives its own properties layered over the
   * scenario properties, while a querySet without properties receives the scenario properties.
   */
  @Test
  public void testGroupPropertiesIsolated() {
    Map<String, String> scenarioOnly = new TreeMap<>();
    scenarioOnly.put("pherf.test.key", "scenario");
    scenarioOnly.put("pherf.test.shared", "scenario");
    int groupA = 0;
    int groupB = 0;
    int noGroup = 0;
    for (String line : connections.get(0)) {
      Map<String, String> properties = testProperties(line);
      if ("A".equals(properties.get(GROUP))) {
        groupA++;
        assertEquals(line, "A", properties.get("pherf.test.key"));
        assertEquals(line, "scenario", properties.get("pherf.test.shared"));
      } else if ("B".equals(properties.get(GROUP))) {
        groupB++;
        assertEquals(line, "scenario", properties.get("pherf.test.key"));
        assertEquals(line, "scenario", properties.get("pherf.test.shared"));
      } else if (!properties.isEmpty()) {
        noGroup++;
        assertEquals(line, scenarioOnly, properties);
      }
    }
    assertEquals(3, groupA);
    assertEquals(2, groupB);
    assertEquals(1, noGroup);
    // No other connection receives a scenario or group property, including DDL, explain, write,
    // statistics, schema, export, multi-tenant, and thin connections.
    int withTestProperties = 0;
    for (List<String> invocation : connections) {
      for (String line : invocation) {
        if (!testProperties(line).isEmpty()) {
          withTestProperties++;
        }
      }
    }
    assertEquals(groupA + groupB + noGroup, withTestProperties);
  }

  /** Extracts the pherf.test.* properties from a record line. */
  private static Map<String, String> testProperties(String line) {
    Map<String, String> properties = properties(line);
    properties.keySet().removeIf(key -> !key.startsWith("pherf.test."));
    return properties;
  }

  /**
   * RQ-151: Verifies that the metrics sink receives one query record per run time and one load
   * record per load row.
   */
  @Test
  public void testRunMetricsReachSink() {
    Map<String, Integer> queries = new HashMap<>();
    int rowRecords = 0;
    boolean load = false;
    for (String line : metrics.get(0)) {
      String[] fields = line.split("\t");
      Map<String, String> tags = properties("x\t" + fields[1]);
      if (fields[0].startsWith("query.")) {
        assertEquals(line, "testGroupProperties", tags.get("scenario"));
        assertEquals(line, "ok", tags.get("outcome"));
        if (fields[0].equals("query.elapsed_ms")) {
          String id = tags.get("query");
          queries.put(id, queries.containsKey(id) ? queries.get(id) + 1 : 1);
        } else {
          assertEquals(line, "query.rows", fields[0]);
          rowRecords++;
        }
      }
      if (
        fields[0].equals("load.rows") && "PHERF.TEST_TABLE".equals(tags.get("table"))
          && fields[2].equals("4")
      ) {
        load = true;
      }
    }
    Map<String, Integer> expected = new HashMap<>();
    expected.put("qa", 3);
    expected.put("qb", 2);
    expected.put("qc", 1);
    assertEquals(expected, queries);
    assertEquals(6, rowRecords);
    assertTrue("No load.rows=4 record for PHERF.TEST_TABLE: " + metrics.get(0), load);

    boolean upsert = false;
    boolean select = false;
    boolean query = false;
    for (String line : metrics.get(1)) {
      String[] fields = line.split("\t");
      Map<String, String> tags = properties("x\t" + fields[1]);
      if (fields[0].startsWith("operation.")) {
        upsert |= "UPSERT".equals(tags.get("type"));
        select |= "SELECT".equals(tags.get("type"));
      }
      query |= fields[0].startsWith("query.");
    }
    assertTrue("No UPSERT operation record: " + metrics.get(1), upsert);
    assertTrue("No SELECT operation record: " + metrics.get(1), select);
    assertTrue("No query record: " + metrics.get(1), query);
  }

  /**
   * RQ-155: Verifies that the report section key appears in the XML run report while the CSV header
   * remains unchanged.
   */
  @Test
  public void testSectionKeyInRunReport() throws Exception {
    File results = new File(child.work(), "RESULTS");
    File xml = new File(results, "RESULT_" + FIXTURE + "_p3.xml");
    DataModelResult report = (DataModelResult) JAXBContext.newInstance(DataModelResult.class)
      .createUnmarshaller().unmarshal(xml);
    assertEquals(FIXTURE, report.getSections().get("test.known"));

    File csv = new File(results, "RESULT_" + FIXTURE + "_p3_aggregate.csv");
    List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
    assertEquals(Header.AGGREGATE_PERFORMANCE.toString(), lines.get(0));
  }

  /**
   * Parses the sorted properties from a record line formatted as "URL\t{key=value, key=value}".
   */
  private static Map<String, String> properties(String line) {
    String text = line.substring(line.indexOf('\t') + 1);
    text = text.substring(1, text.length() - 1);
    Map<String, String> properties = new TreeMap<>();
    if (!text.isEmpty()) {
      for (String entry : text.split(", ")) {
        int split = entry.indexOf('=');
        properties.put(entry.substring(0, split), entry.substring(split + 1));
      }
    }
    return properties;
  }
}
