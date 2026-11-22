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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.ExecutionType;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.configuration.OperationMix;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ResultManager;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.apache.phoenix.pherf.workload.openloop.MixOrder;
import org.apache.phoenix.pherf.workload.openloop.OpenLoopScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryExecutor implements Workload {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryExecutor.class);
  private List<DataModel> dataModels;
  private String queryHint;
  private final boolean exportCSV;
  private final XMLConfigParser parser;
  private final PhoenixUtil util;
  private final WorkloadExecutor workloadExecutor;
  private final boolean writeRuntimeResults;
  private RulesApplier ruleApplier;
  // Synchronizes access to the currently active open-loop query set scheduler and the cancellation
  // flag.
  private final Object openLoopMonitor = new Object();
  private OpenLoopScheduler activeScheduler;
  private boolean cancelled;

  public QueryExecutor(XMLConfigParser parser, PhoenixUtil util,
    WorkloadExecutor workloadExecutor) {
    this(parser, util, workloadExecutor, parser.getDataModels(), null, false, true);
  }

  public QueryExecutor(XMLConfigParser parser, PhoenixUtil util, WorkloadExecutor workloadExecutor,
    List<DataModel> dataModels, String queryHint, boolean exportCSV) {
    this(parser, util, workloadExecutor, dataModels, queryHint, exportCSV, true);
  }

  public QueryExecutor(XMLConfigParser parser, PhoenixUtil util, WorkloadExecutor workloadExecutor,
    List<DataModel> dataModels, String queryHint, boolean exportCSV, boolean writeRuntimeResults) {
    this.parser = parser;
    this.queryHint = queryHint;
    this.exportCSV = exportCSV;
    this.dataModels = dataModels;
    this.util = util;
    this.workloadExecutor = workloadExecutor;
    this.writeRuntimeResults = writeRuntimeResults;
    this.ruleApplier = new RulesApplier(parser);
  }

  /**
   * Cancels the currently running open-loop query set and returns after its scheduler finishes
   * reporting or the grace period of {@link OpenLoopScheduler#cancel()} elapses. The query set
   * execution loop then terminates before advancing to the next query set.
   */
  @Override
  public void complete() {
    OpenLoopScheduler scheduler;
    synchronized (openLoopMonitor) {
      cancelled = true;
      scheduler = activeScheduler;
    }
    if (scheduler != null) {
      scheduler.cancel();
    }
  }

  private boolean isCancelled() {
    synchronized (openLoopMonitor) {
      return cancelled;
    }
  }

  /**
   * Calls in Multithreaded Query Executor for all datamodels
   */
  @Override
  public Callable<Void> execute() throws Exception {
    Callable<Void> callable = null;
    for (DataModel dataModel : dataModels) {
      if (exportCSV) {
        callable = exportAllScenarios(dataModel);
      } else {
        callable = executeAllScenarios(dataModel);
      }
    }
    return callable;
  }

  /**
   * Export all queries results to CSV
   */
  protected Callable<Void> exportAllScenarios(final DataModel dataModel) throws Exception {
    return new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        try {
          List<Scenario> scenarios = dataModel.getScenarios();
          QueryVerifier exportRunner = new QueryVerifier(false);
          for (Scenario scenario : scenarios) {
            for (QuerySet querySet : scenario.getQuerySet()) {
              util.executeQuerySetDdls(querySet);
              for (Query query : querySet.getQuery()) {
                exportRunner.exportCSV(query);
              }
            }
          }
        } catch (Exception e) {
          LOGGER.error("Scenario throws exception", e);
          throw e;
        }
        return null;
      }
    };
  }

  /**
   * Execute all scenarios
   */
  protected Callable<Void> executeAllScenarios(final DataModel dataModel) throws Exception {
    checkWarmupExecutions(dataModel);
    checkOpenLoop(dataModel);
    checkKinds(dataModel);
    checkMix(dataModel);
    return new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        List<DataModelResult> dataModelResults = new ArrayList<>();
        DataModelResult dataModelResult =
          new DataModelResult(dataModel, PhoenixUtil.getZookeeper());
        ResultManager resultManager = new ResultManager(dataModelResult.getName());

        dataModelResults.add(dataModelResult);
        List<Scenario> scenarios = dataModel.getScenarios();
        Configuration conf = HBaseConfiguration.create();
        Map<String, String> phoenixProperty = conf.getValByRegex("phoenix");
        try {

          for (Scenario scenario : scenarios) {
            ScenarioResult scenarioResult = new ScenarioResult(scenario);
            scenarioResult.setPhoenixProperties(phoenixProperty);
            dataModelResult.getScenarioResult().add(scenarioResult);

            int position = 0;
            for (QuerySet querySet : scenario.getQuerySet()) {
              position++;
              if (isCancelled()) {
                break;
              }
              QuerySetResult querySetResult = new QuerySetResult(querySet);
              scenarioResult.getQuerySetResult().add(querySetResult);

              util.executeQuerySetDdls(querySet);
              if (querySet.getOpenLoop() != null && workloadExecutor.isPerformance()) {
                executeQuerySetOpenLoop(dataModelResult, querySet, querySetResult, scenario,
                  position);
              } else if (querySet.getExecutionType() == ExecutionType.SERIAL) {
                executeQuerySetSerial(dataModelResult, querySet, querySetResult, scenario);
              } else {
                executeQuerySetParallel(dataModelResult, querySet, querySetResult, scenario);
              }
            }
            resultManager.write(dataModelResult, ruleApplier);
          }
          resultManager.write(dataModelResults, ruleApplier);
          resultManager.flush();
        } catch (Exception e) {
          LOGGER.error("Scenario throws exception", e);
          throw e;
        }
        return null;
      }
    };
  }

  /**
   * Execute query set serially
   */
  protected void executeQuerySetSerial(DataModelResult dataModelResult, QuerySet querySet,
    QuerySetResult querySetResult, Scenario scenario)
    throws ExecutionException, InterruptedException {
    for (Query query : querySet.getQuery()) {
      QueryResult queryResult = new QueryResult(query);
      querySetResult.getQueryResults().add(queryResult);

      for (int cr = querySet.getMinConcurrency(); cr <= querySet.getMaxConcurrency(); cr++) {

        List<Future> threads = new ArrayList<>();

        for (int i = 0; i < cr; i++) {

          Callable thread = executeRunner((i + 1) + "," + cr, dataModelResult, queryResult,
            querySetResult, scenario, i, cr);
          threads.add(workloadExecutor.getPool().submit(thread));
        }

        for (Future thread : threads) {
          thread.get();
        }
      }
    }
  }

  /**
   * Execute query set in parallel
   */
  protected void executeQuerySetParallel(DataModelResult dataModelResult, QuerySet querySet,
    QuerySetResult querySetResult, Scenario scenario)
    throws ExecutionException, InterruptedException {
    for (int cr = querySet.getMinConcurrency(); cr <= querySet.getMaxConcurrency(); cr++) {
      List<Future> threads = new ArrayList<>();
      for (int i = 0; i < cr; i++) {
        for (Query query : querySet.getQuery()) {
          QueryResult queryResult = new QueryResult(query);
          querySetResult.getQueryResults().add(queryResult);

          Callable<Void> thread = executeRunner((i + 1) + "," + cr, dataModelResult, queryResult,
            querySetResult, scenario, i, cr);
          threads.add(workloadExecutor.getPool().submit(thread));
        }

        for (Future thread : threads) {
          thread.get();
        }
      }
    }
  }

  /**
   * Executes a query set using the open-loop scheduler, applying the configured concurrency as the
   * worker limit. Arrival k executes query k mod n, or the query determined by the mix order.
   * Warmup execution i executes query i mod n. Each query produces a single result containing one
   * thread time entry named "1,C", which records one run time for each executed operation.
   */
  protected void executeQuerySetOpenLoop(DataModelResult dataModelResult, QuerySet querySet,
    QuerySetResult querySetResult, Scenario scenario, int position) throws Exception {
    int concurrency = querySet.getMinConcurrency();
    final List<MultiThreadedRunner> runners = new ArrayList<>();
    final List<String> responses = new ArrayList<>();
    List<String> services = new ArrayList<>();
    for (Query query : querySet.getQuery()) {
      QueryResult queryResult = new QueryResult(query);
      querySetResult.getQueryResults().add(queryResult);
      runners.add((MultiThreadedRunner) executeRunner("1," + concurrency, dataModelResult,
        queryResult, querySetResult, scenario));
      // Construct the operation keys before starting the worker threads, because the first call to
      // getId() may lazily generate the query identifier.
      services.add(MultiThreadedRunner.latencyOperation(queryResult.kind(), scenario.getName(),
        queryResult.getId()));
      responses.add("response:" + scenario.getName() + ":" + queryResult.getId());
    }
    final int queries = runners.size();
    if (queries == 0) {
      return;
    }
    final MixOrder mix = mixOrder(querySet);
    OpenLoopScheduler.Task task = new OpenLoopScheduler.Task() {
      @Override
      public long execute(long sequence, boolean warmup) throws Exception {
        return runners.get(warmup ? (int) (sequence % queries) : query(sequence))
          .timedQuery(sequence + 1, warmup);
      }

      @Override
      public String responseOperation(long sequence) {
        return responses.get(query(sequence));
      }

      private int query(long sequence) {
        return mix == null ? (int) (sequence % queries) : mix.query(sequence);
      }
    };
    OpenLoopScheduler scheduler = OpenLoopScheduler.of(querySet.getOpenLoop(), concurrency,
      warmupExecutions(querySet) * queries, task, scenario.getName(), position, services);
    synchronized (openLoopMonitor) {
      if (cancelled) {
        return;
      }
      activeScheduler = scheduler;
    }
    try {
      LOGGER.info("\nExecuting open loop query set " + position + " of scenario "
        + scenario.getName() + " with concurrency " + concurrency);
      scheduler.run();
    } finally {
      synchronized (openLoopMonitor) {
        activeScheduler = null;
      }
    }
  }

  /**
   * Execute multi-thread runner
   */
  protected Callable<Void> executeRunner(String name, DataModelResult dataModelResult,
    QueryResult queryResult, QuerySet querySet, Scenario scenario) {
    return executeRunner(name, dataModelResult, queryResult, querySet, scenario, 0, 1);
  }

  /**
   * Creates the execution runner for worker w out of W total workers. When co-generation is
   * enabled, each worker reads its assigned share of the committed rows.
   */
  private Callable<Void> executeRunner(String name, DataModelResult dataModelResult,
    QueryResult queryResult, QuerySet querySet, Scenario scenario, int worker, int workers) {
    ThreadTime threadTime = new ThreadTime();
    queryResult.getThreadTimes().add(threadTime);
    threadTime.setThreadName(name);
    queryResult.setHint(this.queryHint);
    LOGGER.info("\nExecuting query " + queryResult.getStatement());
    Callable<Void> thread;
    if (workloadExecutor.isPerformance()) {
      thread = new MultiThreadedRunner(threadTime.getThreadName(), queryResult, dataModelResult,
        threadTime, querySet.getNumberOfExecutions(), querySet.getExecutionDurationInMs(),
        writeRuntimeResults, ruleApplier, scenario, workloadExecutor, parser,
        connectionProperties(scenario, querySet), warmupExecutions(querySet),
        scenario.getCoGeneration() == null
          ? null
          : CoGenerator.of(parser, scenario).reader(worker, workers));
    } else {
      thread = new MultithreadedDiffer(threadTime.getThreadName(), queryResult, threadTime,
        querySet.getNumberOfExecutions(), querySet.getExecutionDurationInMs());
    }
    return thread;
  }

  /**
   * Validates the configured warmup execution counts across all query sets in the data model before
   * any query set begins execution.
   * @throws IllegalArgumentException if any warmup execution count is negative, halting execution
   *                                  of the query executor.
   */
  static void checkWarmupExecutions(DataModel dataModel) {
    for (Scenario scenario : dataModel.getScenarios()) {
      for (QuerySet querySet : scenario.getQuerySet()) {
        checkWarmupExecutions(scenario, querySet);
      }
    }
  }

  /**
   * Validates the configured warmup execution count for a single query set.
   * @throws IllegalArgumentException when the warmup execution count is negative
   */
  public static void checkWarmupExecutions(Scenario scenario, QuerySet querySet) {
    Long warmup = querySet.getWarmupExecutions();
    if (warmup != null && warmup < 0) {
      throw new IllegalArgumentException("warmupExecutions of a querySet of scenario "
        + scenario.getName() + " is negative: " + warmup);
    }
  }

  /**
   * Validates the open-loop configuration across all query sets in the data model before any query
   * set begins execution and before any launch override scales the stages.
   * @throws IllegalArgumentException identifying the scenario, the query set, and the offending
   *                                  attribute
   */
  public static void checkOpenLoop(DataModel dataModel) {
    for (Scenario scenario : dataModel.getScenarios()) {
      int position = 0;
      for (QuerySet querySet : scenario.getQuerySet()) {
        position++;
        OpenLoop openLoop = querySet.getOpenLoop();
        if (openLoop == null) {
          continue;
        }
        // Execute the three checks in the original condition order so that the first reported
        // validation error message remains unchanged.
        String where = openLoopWhere(position, scenario);
        checkOpenLoopRates(where, querySet);
        checkArrivalSeed(where, openLoop);
        checkOpenLoopLag(where, openLoop);
      }
    }
  }

  /**
   * Formats the location prefix used in open-loop validation messages for a single query set, where
   * query set positions start at 1.
   */
  public static String openLoopWhere(int position, Scenario scenario) {
    return "openLoop of querySet " + position + " of scenario " + scenario.getName();
  }

  /**
   * Validates the concurrency setting and stage definitions for the open-loop schedule of a query
   * set.
   * @throws IllegalArgumentException with a message starting with where that identifies the
   *                                  offending attribute
   */
  public static void checkOpenLoopRates(String where, QuerySet querySet) {
    if (!isPositiveInteger(querySet.getConcurrency())) {
      throw new IllegalArgumentException(where + ": concurrency is not one integer of at"
        + " least 1: " + querySet.getConcurrency());
    }
    List<OpenLoop.Stage> stages = querySet.getOpenLoop().getStage();
    if (stages == null || stages.isEmpty()) {
      throw new IllegalArgumentException(where + ": no stage");
    }
    // Because the schedule operates in microseconds (us), the total duration in microseconds must
    // fit within a 64-bit long.
    long totalMs = 0;
    double expected = 0;
    for (int i = 0; i < stages.size(); i++) {
      OpenLoop.Stage stage = stages.get(i);
      String which = where + ": stage " + (i + 1) + ": ";
      if (stage.getDurationMs() <= 0) {
        throw new IllegalArgumentException(
          which + "durationMs is not positive: " + stage.getDurationMs());
      }
      if (stage.getRate() == null) {
        throw new IllegalArgumentException(which + "rate is absent");
      }
      if (!isRate(stage.getRate())) {
        throw new IllegalArgumentException(
          which + "rate is negative or not finite: " + stage.getRate());
      }
      if (stage.getEndRate() != null && !isRate(stage.getEndRate())) {
        throw new IllegalArgumentException(
          which + "endRate is negative or not finite: " + stage.getEndRate());
      }
      totalMs += stage.getDurationMs();
      if (totalMs < 0 || totalMs > Long.MAX_VALUE / 1000) {
        throw new IllegalArgumentException(which + "durationMs is too large: the total of the"
          + " stages is more than " + Long.MAX_VALUE / 1000 + " ms");
      }
      double endRate = stage.getEndRate() == null ? stage.getRate() : stage.getEndRate();
      expected += (stage.getRate() + endRate) / 2 * stage.getDurationMs();
    }
    if (expected == 0) {
      throw new IllegalArgumentException(where + ": no arrival, the rate of all stages is 0");
    }
  }

  /**
   * Validates that an open-loop configuration using a POISSON arrival process specifies a seed.
   * @throws IllegalArgumentException with a message starting with where when the seed is missing
   */
  public static void checkArrivalSeed(String where, OpenLoop openLoop) {
    if (openLoop.getArrival() == OpenLoop.Arrival.POISSON && openLoop.getSeed() == null) {
      throw new IllegalArgumentException(where + ": arrival POISSON requires a seed");
    }
  }

  /**
   * Validates the maximum lag threshold and lag policy of an open-loop configuration.
   * @throws IllegalArgumentException with a message starting with where that identifies the
   *                                  offending attribute
   */
  public static void checkOpenLoopLag(String where, OpenLoop openLoop) {
    if (openLoop.getMaxLagMs() != null && openLoop.getMaxLagMs() < 0) {
      throw new IllegalArgumentException(
        where + ": maxLagMs is negative: " + openLoop.getMaxLagMs());
    }
    if (openLoop.getMaxLagMs() != null && openLoop.getMaxLagMs() > Long.MAX_VALUE / 1000) {
      throw new IllegalArgumentException(
        where + ": maxLagMs is more than " + Long.MAX_VALUE / 1000 + ": " + openLoop.getMaxLagMs());
    }
    if (openLoop.getLagPolicy() == OpenLoop.LagPolicy.DROP && openLoop.getMaxLagMs() == null) {
      throw new IllegalArgumentException(where + ": lagPolicy DROP requires maxLagMs");
    }
  }

  /**
   * Validates the kind of every query in the data model before any query set begins execution.
   * @throws IllegalArgumentException identifying the scenario and query when validation fails
   */
  static void checkKinds(DataModel dataModel) {
    for (Scenario scenario : dataModel.getScenarios()) {
      for (QuerySet querySet : scenario.getQuerySet()) {
        for (Query query : querySet.getQuery()) {
          query.kind(scenario.getName());
        }
      }
    }
  }

  /**
   * Validates the operation mix configuration across all query sets in the data model before any
   * query set begins execution. An operation mix requires an open-loop schedule. The mix may
   * specify the SELECT, SCAN, or DELETE kinds at most once each, with an integer weight of at least
   * 1 per kind and a combined weight of at most {@link MixOrder#MAX_TOTAL_WEIGHT}. Every kind in
   * the mix must match at least one query, and every query must belong to a kind included in the
   * mix or it would never run.
   * @throws IllegalArgumentException identifying the scenario, the query set, and the profile when
   *                                  validation fails.
   */
  static void checkMix(DataModel dataModel) {
    for (Scenario scenario : dataModel.getScenarios()) {
      int position = 0;
      for (QuerySet querySet : scenario.getQuerySet()) {
        position++;
        checkMix(scenario, position, querySet);
      }
    }
  }

  /**
   * Validates the operation mix configuration of a single query set using the rules enforced by
   * {@link #checkMix(DataModel)}, where query set positions start at 1.
   * @throws IllegalArgumentException identifying the scenario, the query set, and the profile
   */
  public static void checkMix(Scenario scenario, int position, QuerySet querySet) {
    if (querySet.getMix() == null) {
      return;
    }
    String where = "mix of querySet " + position + " of scenario " + scenario.getName()
      + (querySet.getProfile() == null ? "" : " (profile " + querySet.getProfile() + ")");
    if (querySet.getOpenLoop() == null) {
      throw new IllegalArgumentException(
        where + ": a mix needs an openLoop; the closed loop runs each query in turn");
    }
    Map<Query.Kind, Integer> weights = weights(where, querySet.getMix());
    Map<Query.Kind, List<Integer>> queries =
      queriesByKind(where, weights, querySet, scenario.getName());
    for (Map.Entry<Query.Kind, List<Integer>> entry : queries.entrySet()) {
      if (entry.getValue().isEmpty()) {
        throw new IllegalArgumentException(where + ": no query of kind " + entry.getKey());
      }
    }
  }

  /**
   * Groups the query indexes of a query set by their weighted operation kind, preserving the
   * declared order of the kinds.
   * @throws IllegalArgumentException if any query has a kind without an assigned weight and would
   *                                  therefore never run.
   */
  private static Map<Query.Kind, List<Integer>> queriesByKind(String where,
    Map<Query.Kind, Integer> weights, QuerySet querySet, String scenarioName) {
    Map<Query.Kind, List<Integer>> queries = new LinkedHashMap<>();
    for (Query.Kind kind : weights.keySet()) {
      queries.put(kind, new ArrayList<>());
    }
    for (int q = 0; q < querySet.getQuery().size(); q++) {
      Query query = querySet.getQuery().get(q);
      Query.Kind kind = query.kind(scenarioName);
      if (!queries.containsKey(kind)) {
        throw new IllegalArgumentException(where + ": query " + query.getId() + " has kind " + kind
          + ", which has no weight, so it never runs");
      }
      queries.get(kind).add(q);
    }
    return queries;
  }

  /**
   * Parses and validates the operation mix, returning a map from each operation kind to its weight
   * in declared order.
   */
  private static Map<Query.Kind, Integer> weights(String where, List<OperationMix> mix) {
    if (mix.isEmpty()) {
      throw new IllegalArgumentException(where + ": no operation");
    }
    Map<Query.Kind, Integer> weights = new LinkedHashMap<>();
    long total = 0;
    for (OperationMix operation : mix) {
      String text = operation.getKind();
      Query.Kind kind;
      try {
        kind = Query.Kind.valueOf(text == null ? "" : text.trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
          where + ": kind is not SELECT, SCAN or DELETE: '" + text + "'", e);
      }
      int weight;
      try {
        weight =
          Integer.parseInt(operation.getWeight() == null ? "" : operation.getWeight().trim());
      } catch (NumberFormatException e) {
        weight = 0;
      }
      if (weight < 1) {
        throw new IllegalArgumentException(where + ": weight of kind " + kind
          + " is not an integer of at least 1: '" + operation.getWeight() + "'");
      }
      if (weights.put(kind, weight) != null) {
        throw new IllegalArgumentException(where + ": kind " + kind + " is given twice");
      }
      total += weight;
    }
    if (total > MixOrder.MAX_TOTAL_WEIGHT) {
      throw new IllegalArgumentException(
        where + ": the total weight " + total + " is more than " + MixOrder.MAX_TOTAL_WEIGHT);
    }
    return weights;
  }

  /**
   * Constructs the mix order over the query indexes of a query set validated by
   * {@link #checkMix(DataModel)}, or returns null when the query set does not define a mix.
   */
  static MixOrder mixOrder(QuerySet querySet) {
    if (querySet.getMix() == null) {
      return null;
    }
    Map<Query.Kind, Integer> weights = weights("mix", querySet.getMix());
    Map<Query.Kind, List<Integer>> indexes = queriesByKind("mix", weights, querySet, "");
    int[] values = new int[weights.size()];
    int[][] queries = new int[weights.size()][];
    int i = 0;
    for (Map.Entry<Query.Kind, List<Integer>> entry : indexes.entrySet()) {
      values[i] = weights.get(entry.getKey());
      queries[i++] = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
    }
    return new MixOrder(values, queries);
  }

  private static boolean isPositiveInteger(String value) {
    return value != null && value.matches("0*[1-9][0-9]{0,8}");
  }

  private static boolean isRate(double rate) {
    return Double.isFinite(rate) && rate >= 0;
  }

  /**
   * Returns the number of warmup executions to run per thread for each query in the query set,
   * defaulting to 0 when unspecified.
   */
  static long warmupExecutions(QuerySet querySet) {
    Long warmup = querySet.getWarmupExecutions();
    return warmup == null ? 0 : warmup;
  }

  /**
   * Resolves the effective connection properties for the query connections of a given query set.
   * Any property defined on the query set overrides the corresponding scenario property sharing the
   * same key, while leaving the underlying source maps unmodified.
   */
  static Map<String, String> connectionProperties(Scenario scenario, QuerySet querySet) {
    if (querySet.getPhoenixProperties() == null) {
      return scenario.getPhoenixProperties();
    }
    Map<String, String> properties = new HashMap<>();
    if (scenario.getPhoenixProperties() != null) {
      properties.putAll(scenario.getPhoenixProperties());
    }
    properties.putAll(querySet.getPhoenixProperties());
    return properties;
  }
}
