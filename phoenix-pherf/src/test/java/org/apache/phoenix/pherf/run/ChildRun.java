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

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.pherf.Pherf;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.PherfConstants.GeneratePhoenixStats;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.LoadProfile;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.ResultUtil;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.QueryExecutor;
import org.apache.phoenix.pherf.workload.WorkloadExecutor;
import org.apache.phoenix.pherf.workload.WriteWorkload;
import org.apache.phoenix.pherf.workload.mt.MultiTenantWorkload;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;

/**
 * Serves as the entry point for a child JVM that executes Pherf against {@link FakePhoenixDriver}.
 * By pinning the system clock to a constant timestamp, every execution produces deterministic,
 * reproducible output.
 * <p>
 * In "cli" mode, this runner invokes Pherf once for each distinct set of arguments delimited by
 * {@link #NEXT}. In "fixtures" mode, it parses and executes each scenario configuration file named
 * in the arguments. Upon completion, the child process records its final execution status in
 * {@link Records#OUTCOME} and terminates with exit code 0 when successful.
 */
public final class ChildRun {
  public static final String NEXT = "--next";
  public static final String RAN = "ran";
  public static final String REJECTED = "rejected";
  public static final String FAILED = "failed";
  private static final long NOW = 1700000000000L;
  private static final long MAX_OPERATIONS = 20;

  private ChildRun() {
  }

  public static void main(String[] args) {
    int exitCode = 1;
    try {
      EnvironmentEdgeManager.injectEdge(new EnvironmentEdge() {
        @Override
        public long currentTime() {
          return NOW;
        }
      });
      FakePhoenixDriver.install();
      List<String> rest = Arrays.asList(args).subList(1, args.length);
      if ("cli".equals(args[0])) {
        runCli(rest);
      } else {
        runFixtures(rest);
      }
      Records.append(Records.OUTCOME, RAN);
      exitCode = 0;
    } catch (Throwable t) {
      Records.append(Records.OUTCOME, FAILED + "\t" + stackTrace(t));
    }
    System.exit(exitCode);
  }

  private static void runCli(List<String> args) throws Exception {
    List<List<String>> invocations = new ArrayList<>();
    invocations.add(new ArrayList<String>());
    for (String arg : args) {
      if (NEXT.equals(arg)) {
        invocations.add(new ArrayList<String>());
      } else {
        invocations.get(invocations.size() - 1).add(arg);
      }
    }
    for (int i = 0; i < invocations.size(); i++) {
      Records.mark(i + 1);
      new Pherf(invocations.get(i).toArray(new String[0])).run();
    }
  }

  /**
   * Executes the write workload, the query workload, and every load profile defined within each
   * fixture. Workload callables are invoked directly on the calling thread because WorkloadExecutor
   * merely logs workload failures without propagating them. Each load profile is capped at a
   * maximum of {@link #MAX_OPERATIONS} operations. If a generator rejects a load profile, the
   * runner records an outcome line in the format "FILE:SCENARIO rejected" and proceeds to execute
   * the remaining scenarios.
   */
  private static void runFixtures(List<String> paths) throws Exception {
    PhoenixUtil.setZookeeper("fixtures");
    ResultUtil.setFileSuffix("fixtures");
    Properties properties = new Properties();
    properties
      .putAll(PherfConstants.create().getProperties(PherfConstants.PHERF_PROPERTIES, false));
    WorkloadExecutor executor = new WorkloadExecutor(properties, new ArrayList<>(), true);
    try {
      for (int i = 0; i < paths.size(); i++) {
        String path = paths.get(i);
        String name = new File(path).getName();
        Records.mark(i + 1);
        XMLConfigParser parser;
        try {
          parser = new XMLConfigParser(path);
        } catch (Exception e) {
          Records.append(Records.OUTCOME, name + "\t" + REJECTED + "\t" + e);
          continue;
        }
        try {
          runFixture(name, parser, properties, executor);
          Records.append(Records.OUTCOME, name + "\t" + RAN);
        } catch (Exception e) {
          Records.append(Records.OUTCOME, name + "\t" + FAILED + "\t" + stackTrace(e));
        }
      }
    } finally {
      executor.shutdown();
    }
  }

  private static void runFixture(String name, XMLConfigParser parser, Properties properties,
    WorkloadExecutor executor) throws Exception {
    WriteWorkload write =
      new WriteWorkload(PhoenixUtil.create(), parser, properties, GeneratePhoenixStats.NO);
    try {
      write.execute().call();
    } finally {
      write.complete();
    }
    for (DataModel model : parser.getDataModels()) {
      new QueryExecutor(parser, PhoenixUtil.create(), executor, Collections.singletonList(model),
        null, false, true).execute().call();
      for (Scenario scenario : model.getScenarios()) {
        LoadProfile profile = scenario.getLoadProfile();
        if (profile == null) {
          continue;
        }
        profile.setNumOperations(Math.min(profile.getNumOperations(), MAX_OPERATIONS));
        Properties copy = new Properties();
        copy.putAll(properties);
        MultiTenantWorkload workload;
        try {
          workload = new MultiTenantWorkload(PhoenixUtil.create(), model, scenario, copy);
        } catch (RuntimeException e) {
          Records.append(Records.OUTCOME,
            name + ":" + scenario.getName() + "\t" + REJECTED + "\t" + e);
          continue;
        }
        try {
          workload.execute().call();
        } finally {
          workload.complete();
        }
      }
    }
  }

  private static String stackTrace(Throwable t) {
    StringWriter text = new StringWriter();
    t.printStackTrace(new PrintWriter(text));
    return text.toString().replace("\n", "\\n");
  }
}
