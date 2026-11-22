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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.bind.JAXBContext;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.RunTime;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.spi.MetricsSink;
import org.apache.phoenix.pherf.spi.RecordingExtensions;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.apache.phoenix.pherf.workload.cogen.IndexDecoder;
import org.apache.phoenix.pherf.workload.cogen.RowCursor;
import org.apache.phoenix.pherf.workload.cogen.Walk;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies RQ-189 end-to-end by invoking the Pherf CLI in a child JVM with the -l and -q flags. The
 * cogen_range.xml scenario defines 20 rows partitioned into blocks of 5 and executes three serial
 * query sets consisting of 4 SCAN executions, 2 DELETE executions, and 10 point reads. When the
 * coGeneration element is present, the decoder binds each statement such that the scans perform a
 * single walk pass across the 4 blocks, the deletes target blocks 0 and 1, and the point reads
 * observe only the remaining rows 10 to 19. When the coGeneration element is omitted, standard data
 * rules bind those same statements instead. Across both modes, the JSON latency report and the
 * metrics sink record each operation kind under its own distinct name, while the XML result
 * captures the updated row count for each DELETE execution.
 */
public class RangeOperationRunTest {
  private static final String ELEMENT = "<coGeneration seed=\"11\" blockSize=\"5\"/>";
  private static final String SCENARIO = "cogenRange";
  private static final int ROWS = 20;
  private static final int BLOCK = 5;
  private static final String SCAN_PREFIX = "SELECT K2 ";
  private static final String DELETE_PREFIX = "DELETE ";
  private static final String POINT_PREFIX = "SELECT V ";
  private static final Pattern BOUNDS = Pattern.compile("K1 >= (\\d+) AND K1 <= (\\d+)$");

  @ClassRule
  public static TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm coGenerated;

  private static File fixture() {
    return new File(ChildJvm.targetDir().getParentFile(),
      "src/test/resources/cogen/cogen_range.xml");
  }

  private static ChildJvm run(File scenarioFile) throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder());
    child.register(ConnectionProvider.class, KeyedStatementRecorder.class);
    child.register(MetricsSink.class, RecordingExtensions.Sink.class);
    child.runCli(Collections.singletonList(ChildJvm.args("-l", "-q", "-z", "range", "-label", "r",
      "-writerThreadSize", "2", "-disableSchemaApply", "-scenarioFile", scenarioFile.getPath())));
    return child;
  }

  /**
   * Returns all recorded SQL statements matching any of the specified prefixes, preserving their
   * original invocation order.
   */
  private static List<String> statements(ChildJvm child, String... prefixes) throws Exception {
    List<String> statements = new ArrayList<>();
    for (String line : Records.read(child.record(StatementRecorder.RECORD))) {
      for (String prefix : prefixes) {
        if (line.startsWith(prefix)) {
          statements.add(line);
          break;
        }
      }
    }
    return statements;
  }

  @BeforeClass
  public static void runWithElement() throws Exception {
    String text = new String(Files.readAllBytes(fixture().toPath()), StandardCharsets.UTF_8);
    assertTrue(text.contains(ELEMENT));
    coGenerated = run(fixture());
  }

  private static Scenario scenario(XMLConfigParser parser) {
    return parser.getDataModels().get(0).getScenarios().get(0);
  }

  private static String sql(Scenario scenario, int querySet) {
    return scenario.getQuerySet().get(querySet).getQuery().get(0).getStatement();
  }

  private static RowCursor.Block block(long k) {
    return new RowCursor.Block(k * BLOCK, Math.min((k + 1) * BLOCK, ROWS));
  }

  /**
   * Verifies that when the coGeneration element is active, the recorded scan statements match the
   * decoder bounds produced by a single walk pass over the 4 blocks, the delete statements target
   * blocks 0 and 1 in sequential order, and the point reads match a single walk pass across the 20
   * rows excluding the 10 deleted rows.
   */
  @Test
  public void testDecoderBindsRangeStatements() throws Exception {
    XMLConfigParser parser = new XMLConfigParser(fixture().getPath());
    Scenario scenario = scenario(parser);
    IndexDecoder decoder =
      new IndexDecoder(new RulesApplier(parser), scenario, CoGenerator.Settings.of(scenario), null);
    Walk blocks = new Walk(IndexHash.columnSeed(11, "#walk"), WalkStrategy.MULTIPLICATIVE, 0, 1);
    List<String> scans = new ArrayList<>();
    for (int k = 0; k < 4; k++) {
      long w = blocks.next(ROWS / BLOCK);
      scans.add(decoder.range(sql(scenario, 0), "K1", ROWS, () -> block(w)));
    }
    List<String> deletes = new ArrayList<>();
    for (int k = 0; k < 2; k++) {
      final long w = k;
      deletes.add(decoder.range(sql(scenario, 1), "K1", ROWS, () -> block(w)));
    }
    Walk walk = new Walk(IndexHash.columnSeed(11, "#walk"), WalkStrategy.MULTIPLICATIVE, 0, 1);
    List<String> points = new ArrayList<>();
    for (int k = 0; k < ROWS; k++) {
      final long i = walk.next(ROWS);
      if (i >= 2 * BLOCK) {
        points.add(decoder.statement(sql(scenario, 2), () -> i));
      }
    }
    assertEquals(ROWS - 2 * BLOCK, points.size());
    assertEquals(scans, statements(coGenerated, SCAN_PREFIX));
    assertEquals(deletes, statements(coGenerated, DELETE_PREFIX));
    assertEquals(points, statements(coGenerated, POINT_PREFIX));
    assertEquals("DELETE FROM PHERF.COGEN_RANGE WHERE K1 >= 0 AND K1 <= 4", deletes.get(0));
  }

  private static Map<String, Long> counts(ChildJvm child) throws Exception {
    JsonObject report =
      HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_r.json"));
    Map<String, Long> counts = new HashMap<>();
    for (Map.Entry<String, Long> entry : HdrFiles.countsByTag(report).entrySet()) {
      String key = entry.getKey();
      if (key.startsWith("query:") || key.startsWith("scan:") || key.startsWith("delete:")) {
        counts.put(key, entry.getValue());
      }
    }
    return counts;
  }

  private static Map<String, Long> expectedCounts() {
    Map<String, Long> expected = new HashMap<>();
    expected.put(LatencyReport.tag("scan:" + SCENARIO + ":scan", LatencyRecorders.STEADY), 4L);
    expected.put(LatencyReport.tag("delete:" + SCENARIO + ":delete", LatencyRecorders.STEADY), 2L);
    expected.put(LatencyReport.tag("query:" + SCENARIO + ":lookup", LatencyRecorders.STEADY), 10L);
    return expected;
  }

  /**
   * Verifies that each operation kind is reported under its own dedicated latency key and metrics
   * sink record name, registering exactly one count per execution.
   */
  @Test
  public void testReportCountsEachKind() throws Exception {
    assertEquals(expectedCounts(), counts(coGenerated));
    Map<String, Integer> records = new HashMap<>();
    Map<String, Long> rows = new HashMap<>();
    for (String line : Records.withoutMarks(coGenerated.record(Records.SINK))) {
      String[] fields = line.split("\t");
      // Ignore metrics emitted during the initial data load phase triggered by the -l flag.
      if (fields[0].startsWith("load.")) {
        continue;
      }
      if (fields[0].endsWith(".elapsed_ms")) {
        records.merge(fields[0], 1, Integer::sum);
      } else if (fields[0].endsWith(".rows")) {
        rows.merge(fields[0], Long.parseLong(fields[2]), Long::sum);
      }
    }
    Map<String, Integer> expected = new HashMap<>();
    expected.put("scan.elapsed_ms", 4);
    expected.put("delete.elapsed_ms", 2);
    expected.put("query.elapsed_ms", 10);
    assertEquals(expected, records);
    // The mock JDBC driver reports one affected row per update statement and returns zero rows for
    // query result sets.
    assertEquals(Long.valueOf(2), rows.get("delete.rows"));
    assertEquals(Long.valueOf(0), rows.get("scan.rows"));
  }

  /**
   * Verifies that the XML result output preserves the configured operation kind and records the
   * update row count for each DELETE execution.
   */
  @Test
  public void testXmlGivesDeleteRowCounts() throws Exception {
    DataModelResult xml =
      (DataModelResult) JAXBContext.newInstance(DataModelResult.class).createUnmarshaller()
        .unmarshal(new File(new File(coGenerated.work(), "RESULTS"), "RESULT_cogen_range_r.xml"));
    List<QuerySetResult> sets = xml.getScenarioResult().get(0).getQuerySetResult();
    assertEquals(3, sets.size());
    String[] kinds = { "SCAN", "DELETE", null };
    long[] executions = { 4, 2, 10 };
    long[] rowCounts = { 0, 1, 0 };
    for (int s = 0; s < 3; s++) {
      QueryResult query = sets.get(s).getQueryResults().get(0);
      assertEquals(kinds[s], query.getKind());
      List<RunTime> runTimes = query.getThreadTimes().get(0).getRunTimesInMs();
      assertEquals(executions[s], runTimes.size());
      for (RunTime runTime : runTimes) {
        assertEquals(query.getId(), Long.valueOf(rowCounts[s]), runTime.getResultRowCount());
      }
    }
  }

  /**
   * Verifies that when the same scenario file is executed without the coGeneration element, both
   * the executed statements and the report's EXPLAIN statements match a deterministic replay of the
   * rule placeholders seeded with the child clock, and both bounds of each range statement resolve
   * to a single rule value.
   */
  @Test
  public void testRulesBindWithoutElement() throws Exception {
    String text = new String(Files.readAllBytes(fixture().toPath()), StandardCharsets.UTF_8);
    File scenarioFile = folder.newFile("cogen_range_rules.xml");
    Files.write(scenarioFile.toPath(), text.replace(ELEMENT, "").getBytes(StandardCharsets.UTF_8));
    ChildJvm child = run(scenarioFile);
    List<String> statements = statements(child, "SELECT", "DELETE", "EXPLAIN ");

    XMLConfigParser parser = new XMLConfigParser(scenarioFile.getPath());
    DataModel model = parser.getDataModels().get(0);
    Scenario scenario = model.getScenarios().get(0);
    RulesApplier rules = new RulesApplier(model, ChildRun.NOW);
    List<String> recorded = new ArrayList<>();
    List<String> replay = new ArrayList<>();
    Map<String, Integer> executed = new HashMap<>();
    for (String statement : statements) {
      boolean explain = statement.startsWith("EXPLAIN ");
      String sql = explain ? statement.substring("EXPLAIN ".length()) : statement;
      recorded.add(sql);
      Query query = null;
      for (QuerySet querySet : scenario.getQuerySet()) {
        Query candidate = querySet.getQuery().get(0);
        String template = candidate.getStatement();
        if (sql.startsWith(template.substring(0, template.indexOf('[')))) {
          query = candidate;
        }
      }
      assertTrue(sql, query != null);
      replay.add(query.getDynamicStatement(rules, scenario));
      if (!explain) {
        executed.merge(query.getId(), 1, Integer::sum);
      }
      if (query.kind() != Query.Kind.SELECT) {
        assertFalse(sql, sql.contains("["));
        Matcher m = BOUNDS.matcher(sql);
        assertTrue(sql, m.find());
        assertEquals(sql, m.group(1), m.group(2));
      }
    }
    Map<String, Integer> expected = new HashMap<>();
    expected.put("scan", 4);
    expected.put("delete", 2);
    expected.put("lookup", 10);
    assertEquals(expected, executed);
    assertEquals(replay, recorded);
    assertEquals(expectedCounts(), counts(child));
  }
}
