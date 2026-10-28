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
package org.apache.phoenix.vectorharness.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.schema.stats.StatsCollectionDisabledOnServerException;
import org.apache.phoenix.vectorharness.config.HarnessConfig.StatisticsMode;
import org.junit.Test;

public class StatisticsTest {

  @Test
  public void testMatchingClusterHasNoProblems() {
    Map<String, String> servers = new TreeMap<>();
    servers.put("http://rs1:16030", "false");
    servers.put("http://rs2:16030", " false ");
    assertTrue(Statistics.problems(StatisticsMode.OFF, false, servers).isEmpty());
    servers.put("http://rs1:16030", "true");
    servers.put("http://rs2:16030", null);
    assertTrue(Statistics.problems(StatisticsMode.ON, true, servers).isEmpty());
    // Without the info ports only the client check applies.
    assertTrue(Statistics.problems(StatisticsMode.ON, true, Collections.emptyMap()).isEmpty());
  }

  @Test
  public void testOnRunAgainstAnOffCluster() {
    Map<String, String> servers = new TreeMap<>();
    servers.put("http://rs1:16030", "false");
    servers.put("http://rs2:16030", "true");
    List<String> p = Statistics.problems(StatisticsMode.ON, false, servers);
    assertEquals(p.toString(), 2, p.size());
    assertTrue(p.get(0),
      p.get(0).startsWith("the client sets phoenix.stats.collection.enabled=false"));
    assertTrue(p.get(0), p.get(0).endsWith("table.statistics=on"));
    assertTrue(p.get(1), p.get(1).startsWith("RegionServer http://rs1:16030 sets"));
    assertTrue(Statistics.advice(StatisticsMode.ON).contains("VH_STATISTICS=on"));
    assertTrue(
      Statistics.advice(StatisticsMode.ON).contains("phoenix.stats.collection.enabled=true"));
  }

  @Test
  public void testOffRunAgainstAnOnCluster() {
    Map<String, String> servers = new TreeMap<>();
    // A server without the key collects statistics, as the Phoenix default says.
    servers.put("http://rs1:16030", null);
    List<String> p = Statistics.problems(StatisticsMode.OFF, true, servers);
    assertEquals(p.toString(), 2, p.size());
    assertTrue(p.get(0), p.get(0).contains("table.statistics=off"));
    assertTrue(p.get(1), p.get(1).contains("unset, which means true"));
    assertTrue(Statistics.serverCollects(null));
    assertFalse(Statistics.serverCollects("FALSE"));
    assertTrue(Statistics.advice(StatisticsMode.OFF).contains("VH_STATISTICS=off"));
  }

  @Test
  public void testUpdateStatement() {
    assertEquals("UPDATE STATISTICS S.T ALL", Statistics.statement("S.T", false));
    // A promotion changes only the index, so the update skips the data table.
    assertEquals("UPDATE STATISTICS S.T INDEX", Statistics.statement("S.T", true));
  }

  @Test
  public void testDisabledOnServerFailureIsRecognized() {
    assertTrue(Statistics.disabledOnServer(new StatsCollectionDisabledOnServerException()));
    SQLExceptionCode code = SQLExceptionCode.STATS_COLLECTION_DISABLED_ON_SERVER;
    // The client sees the server failure as text in a wrapped remote exception.
    IOException remote = new IOException(
      "org.apache.phoenix.schema.stats." + "StatsCollectionDisabledOnServerException: ERROR "
        + code.getErrorCode() + " (" + code.getSQLState() + "): " + code.getMessage());
    assertTrue(Statistics.disabledOnServer(new SQLException("scan failed", remote)));
    assertFalse(Statistics.disabledOnServer(new SQLException("ERROR 1012 (42M03): no table")));
    assertFalse(Statistics.disabledOnServer(null));
  }
}
