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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.spi.RecordingExtensions;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies connection caching behavior (RQ-163) by executing the same query fixture twice within a
 * single child JVM through a counting connection provider. Invocation 1 enables -connectionCache,
 * whereas invocation 2 omits it. The fixture executes 6 threads with 20 executions each, using a
 * configured worker pool size (pherf.default.threadpool) of {@value #WORKERS}.
 */
public class ConnectionCacheRunTest {
  private static final int WORKERS = 4;
  private static final int ITERATIONS = 6 * 20;
  private static final String GROUP = "pherf.test.group=C";

  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static List<List<String>> connections;
  private static List<List<String>> provided;
  private static List<List<String>> closes;

  @BeforeClass
  public static void runPherf() throws Exception {
    ChildJvm child = new ChildJvm(FOLDER.getRoot());
    child.register(ConnectionProvider.class, RecordingExtensions.Provider.class);
    child.pherfProperty("pherf.default.threadpool", String.valueOf(WORKERS));
    String fixture = new File(ChildJvm.scenarioDir(), "test_connection_cache.xml").getPath();
    List<List<String>> invocations = new ArrayList<>();
    invocations.add(ChildJvm.args("-q", "-z", "cache", "-label", "cache", "-disableSchemaApply",
      "-connectionCache", "-scenarioFile", fixture));
    invocations.add(ChildJvm.args("-q", "-z", "cache", "-label", "cache", "-disableSchemaApply",
      "-scenarioFile", fixture));
    child.runCli(invocations);

    connections = Records.byInvocation(child.record(Records.CONNECTIONS));
    provided = Records.byInvocation(child.record(Records.PROVIDER));
    closes = Records.byInvocation(child.record(Records.CLOSES));
    assertEquals(invocations.size(), connections.size());
    assertEquals(invocations.size(), provided.size());
    assertEquals(invocations.size(), closes.size());
  }

  private static int queryLines(List<String> lines) {
    int count = 0;
    for (String line : lines) {
      if (line.contains(GROUP)) {
        count++;
      }
    }
    return count;
  }

  private static List<String> sorted(List<String> lines) {
    List<String> copy = new ArrayList<>(lines);
    Collections.sort(copy);
    return copy;
  }

  /**
   * Verifies that when the connection cache is enabled, the number of query connections opened by
   * the provider does not exceed the number of worker threads.
   */
  @Test
  public void testCachedOpensBoundedByWorkers() {
    int opens = queryLines(provided.get(0));
    assertTrue("Cached query opens: " + opens, opens <= WORKERS);
  }

  /**
   * Verifies that when the connection cache is disabled, every query execution opens a distinct
   * connection.
   */
  @Test
  public void testUncachedOpensMatchIterations() {
    assertEquals(ITERATIONS, queryLines(provided.get(1)));
  }

  /**
   * Verifies that across both invocations, every connection established with the JDBC driver is
   * routed through the registered connection provider.
   */
  @Test
  public void testProviderOpensEveryDriverConnection() {
    for (int i = 0; i < connections.size(); i++) {
      assertEquals("Invocation " + (i + 1), sorted(connections.get(i)), sorted(provided.get(i)));
    }
  }

  /**
   * Verifies that upon completion of the cached run, the connection cache closes every connection
   * that it opened.
   */
  @Test
  public void testCachedRunClosesEveryConnection() {
    assertEquals(sorted(connections.get(0)), sorted(closes.get(0)));
  }
}
