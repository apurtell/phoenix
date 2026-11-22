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
package org.apache.phoenix.pherf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.apache.commons.lang3.StringUtils;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.Result;
import org.apache.phoenix.pherf.result.ResultValue;
import org.apache.phoenix.pherf.result.file.ResultFileDetails;
import org.apache.phoenix.pherf.result.impl.CSVFileResultHandler;
import org.apache.phoenix.pherf.run.ReferenceScenarios;
import org.apache.phoenix.pherf.workload.mt.MultiTenantTestUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies RQ-192 on a mini cluster by executing each reference scenario with its smoke profile
 * end-to-end through Pherf (-l -q), matching a standard user invocation. Confirms that each run
 * produces the exact expected operation count for every kind, completes all scheduled arrivals,
 * reads or deletes the expected number of rows per operation, and leaves the expected number of
 * rows remaining in the table.
 */
@Category(NeedsOwnMiniClusterTest.class)
public class ReferenceScenariosIT extends ResultBaseTestIT {
  private static final String[] QUERIES =
    { ReferenceScenarios.LOOKUP, ReferenceScenarios.SCAN, ReferenceScenarios.DELETE };
  private static final String[] KEYS = { "query", "scan", "delete" };

  @Test
  public void testSmokeProfiles() throws Exception {
    String zookeeper = MultiTenantTestUtils.getZookeeperFromUrl(url);
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      Pherf pherf = new Pherf(new String[] { "-l", "-q", "-z", zookeeper, "-disableSchemaApply",
        "-label", reference.name, "-scenarioFile", reference.scenarioFile().getPath() });
      pherf.run();
      for (Future<Void> job : pherf.workloadExecutor.jobs.values()) {
        job.get();
      }
      assertReport(reference);
      assertRowCounts(reference);
      try (Connection connection = util.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + reference.table)) {
        assertTrue(rs.next());
        assertEquals(reference.name, reference.rowsLeft, rs.getLong(1));
      }
    }
  }

  /**
   * Verifies that the latency report records the expected steady-state operation count for each
   * query kind and that the schedule summary shows every scheduled arrival completed.
   */
  private static void assertReport(ReferenceScenarios reference) throws Exception {
    File dir = new File(properties.getProperty("pherf.default.results.dir") + "_HDR");
    JsonObject report = HdrFiles.json(new File(dir, "LATENCY_" + reference.name + ".json"));
    Map<String, Long> counts = HdrFiles.countsByTag(report);
    for (int i = 0; i < QUERIES.length; i++) {
      String key = LatencyReport.tag(KEYS[i] + ":" + reference.scenario + ":" + QUERIES[i],
        LatencyRecorders.STEADY);
      assertEquals(key, Long.valueOf(reference.operations(QUERIES[i])), counts.get(key));
    }
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(1, schedules.size());
    JsonObject entry = schedules.get(0).getAsJsonObject();
    assertEquals(reference.name, reference.arrivals, entry.get("scheduled").getAsLong());
    assertEquals(reference.name, reference.arrivals, entry.get("completed").getAsLong());
    for (String zero : new String[] { "dropped", "aborted", "pending" }) {
      assertEquals(reference.name + " " + zero, 0, entry.get(zero).getAsLong());
    }
  }

  /**
   * Verifies that every point lookup reads 1 row and that every scan and delete operation affects
   * one full block of rows.
   */
  private static void assertRowCounts(ReferenceScenarios reference) throws Exception {
    CSVFileResultHandler results = new CSVFileResultHandler();
    results.setResultFileDetails(ResultFileDetails.CSV_DETAILED_PERFORMANCE);
    results.setResultFileName("COMBINED");
    Map<String, List<String>> rowCounts = new HashMap<>();
    for (Result result : results.read()) {
      Map<String, String> fields = fields(result);
      rowCounts.computeIfAbsent(fields.get("QUERY_ID"), k -> new ArrayList<>())
        .add(fields.get("RESULT_ROW_COUNT"));
    }
    Map<String, List<String>> expected = new HashMap<>();
    for (String id : QUERIES) {
      expected.put(id, Collections.nCopies((int) reference.operations(id),
        String.valueOf(reference.rowsPerOperation(id))));
    }
    assertEquals(reference.name, expected, rowCounts);
  }

  /**
   * Extracts the field values of a single result row into a map keyed by column header name.
   */
  private static Map<String, String> fields(Result result) {
    Map<String, String> fields = new HashMap<>();
    List<ResultValue> values = result.getResultValues();
    String[] headers = result.getHeader().split(PherfConstants.RESULT_FILE_DELIMETER);
    for (int i = 0; i < headers.length; i++) {
      fields.put(StringUtils.strip(headers[i], "[] "),
        StringUtils.strip(values.get(i).toString(), "[] "));
    }
    return fields;
  }
}
