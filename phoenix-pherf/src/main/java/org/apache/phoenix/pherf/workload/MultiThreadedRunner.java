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
package org.apache.phoenix.pherf.workload;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.PherfConstants.GeneratePhoenixStats;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.Micros;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.ResultManager;
import org.apache.phoenix.pherf.result.RunTime;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.spi.Extensions;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;

class MultiThreadedRunner implements Callable<Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(MultiThreadedRunner.class);
  private Query query;
  private ThreadTime threadTime;
  private PhoenixUtil pUtil = PhoenixUtil.create();
  private String threadName;
  private DataModelResult dataModelResult;
  private long numberOfExecutions;
  private long executionDurationInMs;
  private static long lastResultWritten = EnvironmentEdgeManager.currentTimeMillis() - 1000;
  private final ResultManager resultManager;
  private final RulesApplier ruleApplier;
  private final Scenario scenario;
  private final WorkloadExecutor workloadExecutor;
  private final XMLConfigParser parser;
  private final boolean writeRuntimeResults;
  private final Map<String, String> connectionProperties;
  private final String latencyOperation;
  private final long warmupExecutions;

  /**
   * MultiThreadedRunner
   */
  MultiThreadedRunner(String threadName, Query query, DataModelResult dataModelResult,
    ThreadTime threadTime, long numberOfExecutions, long executionDurationInMs,
    boolean writeRuntimeResults, RulesApplier ruleApplier, Scenario scenario,
    WorkloadExecutor workloadExecutor, XMLConfigParser parser) {
    this(threadName, query, dataModelResult, threadTime, numberOfExecutions, executionDurationInMs,
      writeRuntimeResults, ruleApplier, scenario, workloadExecutor, parser,
      scenario.getPhoenixProperties(), 0);
  }

  /**
   * Constructs a runner that configures its query connections with the provided properties. Prior
   * to running the measured executions, the runner performs warmupExecutions iterations that are
   * recorded exclusively under the "warmup" latency phase.
   */
  MultiThreadedRunner(String threadName, Query query, DataModelResult dataModelResult,
    ThreadTime threadTime, long numberOfExecutions, long executionDurationInMs,
    boolean writeRuntimeResults, RulesApplier ruleApplier, Scenario scenario,
    WorkloadExecutor workloadExecutor, XMLConfigParser parser,
    Map<String, String> connectionProperties, long warmupExecutions) {
    this.query = query;
    this.threadName = threadName;
    this.threadTime = threadTime;
    this.dataModelResult = dataModelResult;
    this.numberOfExecutions = numberOfExecutions;
    this.executionDurationInMs = executionDurationInMs;
    this.ruleApplier = ruleApplier;
    this.scenario = scenario;
    this.resultManager = new ResultManager(dataModelResult.getName(), writeRuntimeResults);
    this.workloadExecutor = workloadExecutor;
    this.parser = parser;
    this.writeRuntimeResults = writeRuntimeResults;
    this.connectionProperties = connectionProperties;
    this.latencyOperation = "query:" + scenario.getName() + ":" + query.getId();
    this.warmupExecutions = warmupExecutions;
  }

  /**
   * Executes run for a minimum of number of execution or execution duration
   */
  @Override
  public Void call() throws Exception {
    LOGGER.info("\n\nThread Starting " + threadName + " ; '" + query.getStatement() + "' for "
      + numberOfExecutions + " times\n\n");
    // Execute warmup iterations through the same query path without counting against the thread's
    // target execution count or maximum execution duration.
    for (long i = 0; i < warmupExecutions; i++) {
      synchronized (workloadExecutor) {
        timedQuery(i + 1, true);
      }
    }
    long threadStartTime = EnvironmentEdgeManager.currentTimeMillis();
    for (long i = 0; i < numberOfExecutions; i++) {
      long threadElapsedTime = EnvironmentEdgeManager.currentTimeMillis() - threadStartTime;
      if (threadElapsedTime >= executionDurationInMs) {
        LOGGER.info("Queryset timeout of " + executionDurationInMs + " ms reached; current time is "
          + threadElapsedTime + " ms." + "\nStopping queryset execution for query " + query.getId()
          + " on thread " + threadName + "...");
        break;
      }

      synchronized (workloadExecutor) {
        timedQuery(i + 1, false);
        if (
          writeRuntimeResults
            && (EnvironmentEdgeManager.currentTimeMillis() - lastResultWritten) > 1000
        ) {
          resultManager.write(dataModelResult, ruleApplier);
          lastResultWritten = EnvironmentEdgeManager.currentTimeMillis();
        }
      }
    }

    if (!writeRuntimeResults) {
      long duration = EnvironmentEdgeManager.currentTimeMillis() - threadStartTime;
      LOGGER.info(
        "The read query " + query.getStatement() + " for this thread in (" + duration + ") Ms");
    }

    // Make sure all result have been dumped before exiting
    if (writeRuntimeResults) {
      synchronized (workloadExecutor) {
        resultManager.flush();
      }
    }

    LOGGER.info("\n\nThread exiting." + threadName + "\n\n");
    return null;
  }

  private synchronized ThreadTime getThreadTime() {
    return threadTime;
  }

  /**
   * Executes a timed query iteration. A warmup execution is recorded exclusively under the "warmup"
   * latency phase; it does not contribute run times to legacy reports, emit metrics to sinks, or
   * trigger concurrent write workloads.
   * <p>
   * The open-loop scheduler invokes this method directly without acquiring the closed-loop monitor
   * lock, allowing scheduler worker threads to execute concurrently.
   * @return the elapsed execution time in microseconds (us), measured from connection acquisition
   *         through consumption of the last row.
   */
  long timedQuery(long iterationNumber, boolean warmup) throws Exception {
    boolean isSelectCountStatement =
      query.getStatement().toUpperCase().trim().contains("COUNT(") ? true : false;

    Connection conn = null;
    PreparedStatement statement = null;
    ResultSet rs = null;
    Long queryStartTime = EnvironmentEdgeManager.currentTimeMillis();
    long startMicros = Micros.now();
    long elapsedMicros = 0;
    Date startDate = Calendar.getInstance().getTime();
    String exception = null;
    boolean failed = false;
    Long resultRowCount = 0L;
    String queryIteration = threadName + (warmup ? ":warmup:" : ":") + iterationNumber;
    Long queryElapsedTime = 0L;

    try {
      conn = pUtil.getConnection(query.getTenantId(), connectionProperties);
      conn.setAutoCommit(true);
      final String statementString = query.getDynamicStatement(ruleApplier, scenario);
      statement = conn.prepareStatement(statementString);
      LOGGER.debug("Executing iteration: " + queryIteration + ": " + statementString);

      if (!warmup && scenario.getWriteParams() != null) {
        Workload writes = new WriteWorkload(PhoenixUtil.create(), parser,
          PherfConstants.create().getProperties(PherfConstants.PHERF_PROPERTIES, true), scenario,
          GeneratePhoenixStats.NO);
        workloadExecutor.add(writes);
      }

      boolean isQuery = statement.execute();
      if (isQuery) {
        rs = statement.getResultSet();
        Pair<Long, Long> r = getResults(rs, queryIteration, isSelectCountStatement, queryStartTime);
        resultRowCount = r.getFirst();
      } else {
        conn.commit();
      }
      // Populate legacy reporting metrics by rounding the elapsed microsecond measurement to
      // milliseconds.
      elapsedMicros = Micros.now() - startMicros;
      queryElapsedTime = Micros.toMillis(elapsedMicros);
    } catch (Exception e) {
      LOGGER.error("Exception while executing query iteration " + queryIteration, e);
      exception = e.getMessage();
      failed = true;
      throw e;
    } finally {
      // Legacy reports derive exclusively from the thread run times list, so warmup iterations are
      // excluded from this collection.
      if (!warmup) {
        getThreadTime().getRunTimesInMs().add(new RunTime(exception, startDate, resultRowCount,
          queryElapsedTime, queryElapsedTime > query.getTimeoutDuration()));
      }
      // Record the latency measurement before closing JDBC resources or invoking metrics sinks so
      // that a downstream failure does not discard the sample.
      if (!failed) {
        LatencyRecorders.record(latencyOperation,
          warmup ? LatencyRecorders.WARMUP : LatencyRecorders.STEADY, elapsedMicros);
      }

      if (rs != null) rs.close();
      if (statement != null) statement.close();
      if (conn != null) conn.close();
      // Close the connection before notifying metrics sinks so that a sink failure cannot leak it.
      if (!warmup && Extensions.hasMetricsSinks()) {
        String outcome =
          failed ? "error" : queryElapsedTime > query.getTimeoutDuration() ? "timeout" : "ok";
        Extensions.record("query", Extensions.tags("scenario", scenario.getName(), "query",
          query.getId(), "tenant", query.getTenantId(), "outcome", outcome), queryElapsedTime,
          resultRowCount);
      }
    }
    return elapsedMicros;
  }

  @VisibleForTesting
  /**
   * Returns a Pair whose first value is the resultRowCount, and whose second value is whether the
   * query timed out.
   */
  Pair<Long, Long> getResults(ResultSet rs, String queryIteration, boolean isSelectCountStatement,
    Long queryStartTime) throws Exception {
    Long resultRowCount = 0L;
    while (rs.next()) {
      if (null != query.getExpectedAggregateRowCount()) {
        if (rs.getLong(1) != query.getExpectedAggregateRowCount())
          throw new RuntimeException("Aggregate count " + rs.getLong(1)
            + " does not match expected " + query.getExpectedAggregateRowCount());
      }

      if (isSelectCountStatement) {
        resultRowCount = rs.getLong(1);
      } else {
        resultRowCount++;
      }
      long queryElapsedTime = EnvironmentEdgeManager.currentTimeMillis() - queryStartTime;
      if (queryElapsedTime >= query.getTimeoutDuration()) {
        LOGGER.error("Query " + queryIteration + " exceeded timeout of "
          + query.getTimeoutDuration() + " ms at " + queryElapsedTime + " ms.");
        return new Pair(resultRowCount, queryElapsedTime);
      }
    }
    return new Pair(resultRowCount, EnvironmentEdgeManager.currentTimeMillis() - queryStartTime);
  }

}
