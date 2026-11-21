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
package org.apache.phoenix.pherf.latency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.phoenix.pherf.PherfConstants.GeneratePhoenixStats;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataLoadThreadTime;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.WriteWorkload;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;
import org.apache.phoenix.pherf.workload.mt.operations.Operation;
import org.apache.phoenix.pherf.workload.mt.operations.OperationStats;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Verifies that write workloads and load profile operations feed the latency recorders. */
public class LatencyFeedTest {
  private static final String LABEL = "f";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @After
  public void tearDown() throws Exception {
    LatencyRecorders.finish();
  }

  private Map<String, Long> counts() throws Exception {
    return HdrFiles.countsByTag(HdrFiles.json(new File(folder.getRoot(), "LATENCY_f.json")));
  }

  // Constructs a concrete operation instance so that Gson can serialize the operation statistics
  // in the same manner as the runtime handlers.
  private static TenantOperationInfo info(String id, Operation.OperationType type) {
    Operation operation = new Operation() {
      @Override
      public String getId() {
        return id;
      }

      @Override
      public OperationType getType() {
        return type;
      }
    };
    return new TenantOperationInfo("m", "s", "T", "tg", id, "tenant", operation);
  }

  @Test
  public void testOperationStatsRecordsOnlySuccess() throws Exception {
    TenantOperationInfo info = info("g", Operation.OperationType.SELECT);
    LatencyRecorders.begin(folder.getRoot(), LABEL, 0);
    OperationStats ok = new OperationStats(info, 0, 0, 1, 2_600);
    ok.recordMetrics();
    new OperationStats(info, 0, -1, 1, 5_000).recordMetrics();
    // Idle periods and user-defined operations do not represent database work and are excluded.
    new OperationStats(info("idle", Operation.OperationType.IDLE_TIME), 0, 0, 0, 10_000_000)
      .recordMetrics();
    new OperationStats(info("udf", Operation.OperationType.USER_DEFINED), 0, 0, 0, 0)
      .recordMetrics();
    LatencyRecorders.finish();

    assertEquals(3, ok.getDurationInMs());
    assertFalse(new Gson().toJson(ok).contains("Micros"));
    assertEquals(Collections.singletonMap(LatencyReport.tag("operation:s:g", "steady"), 1L),
      counts());
  }

  @Test
  public void testEachCommittedBatchIsOneSample() throws Exception {
    // Processing five rows with a batch size of two triggers two commits during the ingestion loop
    // followed by one final commit for the remaining row.
    assertEquals(
      Collections.singletonMap(LatencyReport.tag("load:testTenantBinding:T", "steady"), 3L),
      load(5, Integer.MAX_VALUE));
  }

  @Test
  public void testFailedBatchIsNoSample() throws Exception {
    // When the third row fails, only the first committed batch is recorded, and the final commit
    // produces no latency sample.
    assertEquals(
      Collections.singletonMap(LatencyReport.tag("load:testTenantBinding:T", "steady"), 1L),
      load(5, 3));
  }

  private Map<String, Long> load(int rows, int failingRow) throws Exception {
    XMLConfigParser parser = new XMLConfigParser(".*test_tenant_binding.xml");
    Scenario scenario = parser.getScenarios().get(0);
    Properties properties = new Properties();
    properties.setProperty("pherf.default.dataloader.threadpool", "1");
    properties.setProperty("pherf.default.dataloader.batchsize", "2");
    PhoenixUtil util = mock(PhoenixUtil.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(util.getConnection(anyString(), any(Properties.class))).thenReturn(connection);
    when(connection.getClientInfo()).thenReturn(new Properties());
    when(connection.prepareStatement(any())).thenReturn(statement);
    when(util.buildSql(any(), any())).thenReturn("UPSERT");
    when(util.buildStatement(any(), any(), any(), any(), any())).thenReturn(statement);
    int[] calls = { 0 };
    when(statement.executeUpdate()).thenAnswer(invocation -> {
      if (++calls[0] == failingRow) {
        throw new SQLException("failed row");
      }
      return 1;
    });

    WriteWorkload workload =
      new WriteWorkload(util, parser, properties, scenario, GeneratePhoenixStats.NO);
    LatencyRecorders.begin(folder.getRoot(), LABEL, 0);
    try {
      workload
        .upsertData(scenario, Collections.emptyList(), "T", rows, new DataLoadThreadTime(), false)
        .get();
      assertTrue(failingRow > rows);
    } catch (ExecutionException e) {
      if (failingRow > rows) {
        fail(e.toString());
      }
    } finally {
      workload.complete();
    }
    LatencyRecorders.finish();
    return counts();
  }
}
