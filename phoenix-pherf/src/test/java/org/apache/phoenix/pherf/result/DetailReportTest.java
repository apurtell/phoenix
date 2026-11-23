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
package org.apache.phoenix.pherf.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;
import org.apache.phoenix.pherf.ResultBaseTest;
import org.apache.phoenix.pherf.configuration.ExecutionType;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.result.file.ResultFileDetails;
import org.apache.phoenix.pherf.result.impl.CSVFileResultHandler;
import org.apache.phoenix.pherf.result.impl.XMLResultHandler;
import org.apache.phoenix.pherf.run.Golden;
import org.apache.phoenix.util.DateUtil;
import org.junit.Test;

/**
 * Validates the legacy detail report by verifying the memory footprint of its generated rows and
 * comparing its CSV and XML output against a small test fixture. The golden files for this fixture
 * were captured from the implementation prior to the memory fix.
 */
public class DetailReportTest extends ResultBaseTest {
  private static final String TEST = "DetailReportTest";

  /**
   * Defines the number of run times recorded in the large query test case. The previous
   * implementation allocated an array of RUNS references for every generated row and rescanned all
   * run times on each row to compute the query start time.
   */
  private static final int RUNS = 10_000;

  /**
   * Specifies the maximum number of bytes that constructing a single detail row may allocate. The
   * fixed implementation allocates less than 1 KB per row, whereas the previous implementation
   * allocated more than 500 KB per row at RUNS run times.
   */
  private static final long ROW_BUDGET_BYTES = 2_048;

  @Test
  public void testPerformanceRowsUseLinearMemory() {
    assertRowMemory(ResultFileDetails.CSV_DETAILED_PERFORMANCE);
  }

  @Test
  public void testFunctionalRowsUseLinearMemory() {
    assertRowMemory(ResultFileDetails.CSV_DETAILED_FUNCTIONAL);
  }

  @Test
  public void testOutputMatchesGolden() throws Exception {
    DataModelResult model = fixture();
    ResultUtil util = new ResultUtil();
    Golden.assertMatches(TEST, "detail-report/performance_detail.csv.golden",
      write(util, model, new CSVFileResultHandler(), ResultFileDetails.CSV_DETAILED_PERFORMANCE));
    Golden.assertMatches(TEST, "detail-report/functional_detail.csv.golden",
      write(util, model, new CSVFileResultHandler(), ResultFileDetails.CSV_DETAILED_FUNCTIONAL));
    Golden.assertMatches(TEST, "detail-report/model.xml.golden",
      write(util, model, new XMLResultHandler(), ResultFileDetails.XML));
  }

  /**
   * Explicitly verifies the start time column because timestamps are masked during golden file
   * comparisons. Every row generated for a query must display the earliest start time recorded
   * across all threads belonging to that query.
   */
  @Test
  public void testRowsShowQueryStartTime() {
    List<QueryResult> queries =
      fixture().getScenarioResult().get(0).getQuerySetResult().get(0).getQueryResults();
    ResultUtil util = new ResultUtil();
    for (ResultFileDetails details : new ResultFileDetails[] {
      ResultFileDetails.CSV_DETAILED_PERFORMANCE, ResultFileDetails.CSV_DETAILED_FUNCTIONAL }) {
      assertStartTime(queries.get(0), util, details, 4, new Date(1_000L));
      assertStartTime(queries.get(1), util, details, 1, new Date(5_000L));
    }
  }

  private static void assertStartTime(QueryResult queryResult, ResultUtil util,
    ResultFileDetails details, int expectedRows, Date start) {
    String expected = DateUtil.DEFAULT_MS_DATE_FORMATTER.format(start);
    List<List<ResultValue>> rows = queryResult.getCsvDetailedRepresentation(util, details);
    assertEquals(expectedRows, rows.size());
    for (List<ResultValue> row : rows) {
      assertEquals(details + " start time", expected, row.get(0).getResultValue());
    }
  }

  /**
   * Constructs the detail rows for a single query containing RUNS run times on one thread and
   * asserts that the bytes allocated on the current thread remain within the per-row budget. An
   * initial build of a small query warms up and initializes the required classes so that class
   * initialization overhead is excluded from the allocation measurement.
   */
  private static void assertRowMemory(ResultFileDetails details) {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
    com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
    assumeTrue(threads.isThreadAllocatedMemorySupported());
    threads.setThreadAllocatedMemoryEnabled(true);
    ResultUtil util = new ResultUtil();
    assertEquals(1, query(1).getCsvDetailedRepresentation(util, details).size());
    QueryResult queryResult = query(RUNS);

    long thread = Thread.currentThread().getId();
    long before = threads.getThreadAllocatedBytes(thread);
    List<List<ResultValue>> rows = queryResult.getCsvDetailedRepresentation(util, details);
    long allocated = threads.getThreadAllocatedBytes(thread) - before;

    assertEquals(RUNS, rows.size());
    long perRow = allocated / RUNS;
    assertTrue(details + " rows allocate " + perRow + " bytes for each row, more than "
      + ROW_BUDGET_BYTES + ". Possible causes: a row list sized by the run count, or a start time"
      + " that each row gets again from all run times", perRow <= ROW_BUDGET_BYTES);
  }

  /** Creates a query result containing the specified number of run times on a single thread. */
  private static QueryResult query(int runs) {
    ThreadTime tt = new ThreadTime();
    tt.setThreadName("query-1,4");
    for (int i = 0; i < runs; i++) {
      tt.getRunTimesInMs()
        .add(new RunTime("ok", "plan", new Date(1_000L + i), 1L, Long.valueOf(i), false));
    }
    Query query = new Query();
    query.setId("q1");
    query.setStatement("select 1");
    QueryResult queryResult = new QueryResult(query);
    queryResult.getThreadTimes().add(tt);
    return queryResult;
  }

  /** Writes the data model through the specified handler and returns the resulting file text. */
  private static String write(ResultUtil util, DataModelResult model, ResultHandler handler,
    ResultFileDetails details) throws Exception {
    handler.setResultFileDetails(details);
    handler.setResultFileName("detail_report_" + details.name().toLowerCase());
    try {
      util.write(handler, model, null);
    } finally {
      handler.flush();
      handler.close();
    }
    File file = new File(handler.getResultFileName());
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  /**
   * Builds a test data model containing two queries whose run times cover every value variation,
   * including a message, an explain plan, a time out, and null values. One thread name includes a
   * concurrency component while another omits it.
   */
  private static DataModelResult fixture() {
    DataModelResult model = new DataModelResult();
    model.setName("detail_model");
    model.setZookeeper("zk1");
    ScenarioResult scenario = new ScenarioResult();
    scenario.setName("detail_scenario");
    scenario.setTableName("PHERF.DETAIL");
    scenario.setRowCount(100);
    TreeMap<String, String> props = new TreeMap<>();
    props.put("phoenix.query.timeoutMs", "60000");
    props.put("phoenix.query.threadPoolSize", "16");
    scenario.setPhoenixProperties(props);
    model.getScenarioResult().add(scenario);

    QuerySetResult querySet = new QuerySetResult();
    querySet.setConcurrency("2");
    querySet.setNumberOfExecutions(3);
    querySet.setExecutionType(ExecutionType.SERIAL);
    scenario.getQuerySetResult().add(querySet);

    Query first = new Query();
    first.setId("q1");
    first.setQueryGroup("g1");
    first.setTenantId("t1");
    first.setStatement("select count(*) from PHERF.DETAIL");
    QueryResult firstResult = new QueryResult(first);
    ThreadTime firstThread = new ThreadTime();
    firstThread.setThreadName("query-1,2");
    firstThread.getRunTimesInMs().add(new RunTime(new Date(1_000L), 100L, 12L));
    firstThread.getRunTimesInMs()
      .add(new RunTime("failed, \"quoted\"", "plan A", new Date(2_000L), 0L, 30L, true));
    firstThread.getRunTimesInMs().add(new RunTime(null, null, new Date(3_000L), 7L, null, false));
    firstResult.getThreadTimes().add(firstThread);
    ThreadTime secondThread = new ThreadTime();
    secondThread.setThreadName("query-2");
    secondThread.getRunTimesInMs().add(new RunTime("", "plan B", new Date(4_000L), 1L, 5L, false));
    firstResult.getThreadTimes().add(secondThread);
    querySet.getQueryResults().add(firstResult);

    Query second = new Query();
    second.setId("q2");
    second.setStatement("select * from PHERF.DETAIL limit 1");
    QueryResult secondResult = new QueryResult(second);
    ThreadTime thirdThread = new ThreadTime();
    thirdThread.setThreadName("query-3,1");
    thirdThread.getRunTimesInMs().add(new RunTime(new Date(5_000L), 1L, 9L));
    secondResult.getThreadTimes().add(thirdThread);
    querySet.getQueryResults().add(secondResult);
    return model;
  }
}
