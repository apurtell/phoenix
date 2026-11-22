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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.spi.MetricsSink;
import org.apache.phoenix.pherf.spi.RecordingExtensions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies RQ-187 by invoking the Pherf CLI inside a child JVM (-mt) with a single load profile
 * that combines upsert, query, SCAN, DELETE, user-defined, and idle-time operations. Using the
 * SEQUENTIAL generator with one handler executes each of the six operations twice across a total of
 * 12 operations. The metrics sink records operation counts for every operation type, whereas the
 * JSON latency report tracks counts only for database operations because idle-time and user-defined
 * operations do not produce latency samples. Data generation rules bind the parameters for the SCAN
 * and DELETE statements.
 */
public class MixedOperationsRunTest {
  private static final List<String> GROUPS =
    Arrays.asList("upsertOp", "queryOp", "scanOp", "deleteOp", "idleOp", "udfOp");
  private static final List<String> TYPES =
    Arrays.asList("UPSERT", "SELECT", "SCAN", "DELETE", "IDLE_TIME", "USER_DEFINED");
  private static final Pattern BOUNDS = Pattern.compile("K1 >= (\\d+) AND K1 <= (\\d+)$");
  private static final Pattern TYPE = Pattern.compile("[{ ]type=([A-Z_]+)[,}]");

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private static File fixture() {
    return new File(ChildJvm.targetDir().getParentFile(),
      "src/test/resources/operations/mixed_operations.xml");
  }

  private ChildJvm run(File scenarioFile) throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder());
    child.register(ConnectionProvider.class, StatementRecorder.class);
    child.register(MetricsSink.class, RecordingExtensions.Sink.class);
    child.pherfProperty(PherfConstants.HANDLERS_PER_SCENARIO_PROP_KEY, "1");
    child.runCli(Collections.singletonList(ChildJvm.args("-mt", "-z", "mixed", "-label", "m",
      "-disableSchemaApply", "-scenarioFile", scenarioFile.getPath())));
    return child;
  }

  /**
   * Returns the steady-state counts recorded for the operation keys in the mixed scenario, keyed by
   * operation group.
   */
  private static Map<String, Long> counts(ChildJvm child) throws Exception {
    Map<String, Long> all = HdrFiles.countsByTag(
      HdrFiles.json(new File(new File(child.work(), "RESULTS_HDR"), "LATENCY_m.json")));
    Map<String, Long> counts = new HashMap<>();
    for (String group : GROUPS) {
      Long count = all.get(LatencyReport.tag("operation:mixed:" + group, LatencyRecorders.STEADY));
      if (count != null) {
        counts.put(group, count);
      }
    }
    return counts;
  }

  /**
   * Tallies the number of operation records captured by the metrics sink, grouped by operation
   * type.
   */
  private static Map<String, Integer> records(ChildJvm child) throws Exception {
    Map<String, Integer> records = new HashMap<>();
    for (String line : Records.withoutMarks(child.record(Records.SINK))) {
      String[] fields = line.split("\t");
      if (fields[0].equals("operation.elapsed_ms")) {
        Matcher m = TYPE.matcher(fields[1]);
        assertTrue(line, m.find());
        records.merge(m.group(1), 1, Integer::sum);
      }
    }
    return records;
  }

  @Test
  public void testEachTypeRunsAndIsCounted() throws Exception {
    ChildJvm child = run(fixture());
    Map<String, Integer> expectedRecords = new HashMap<>();
    for (String type : TYPES) {
      expectedRecords.put(type, 2);
    }
    assertEquals(expectedRecords, records(child));
    Map<String, Long> expected = new HashMap<>();
    for (String group : GROUPS.subList(0, 4)) {
      expected.put(group, 2L);
    }
    assertEquals(expected, counts(child));

    List<String> ranges = new ArrayList<>();
    for (String line : Records.read(child.record(StatementRecorder.RECORD))) {
      if (line.startsWith("SELECT V FROM PHERF.MIXED WHERE K1 >=") || line.startsWith("DELETE")) {
        ranges.add(line);
      }
    }
    assertEquals(ranges.toString(), 4, ranges.size());
    int deletes = 0;
    for (String sql : ranges) {
      deletes += sql.startsWith("DELETE") ? 1 : 0;
      assertFalse(sql, sql.contains("["));
      Matcher m = BOUNDS.matcher(sql);
      assertTrue(sql, m.find());
      assertEquals(sql, m.group(1), m.group(2));
    }
    assertEquals(2, deletes);
  }
}
