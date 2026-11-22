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
package org.apache.phoenix.pherf.workload.cogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.PherfConstants.GeneratePhoenixStats;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.WriteWorkload;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Verifies requirements RQ-176, RQ-181, and RQ-184 by executing the real write workload within this
 * JVM. A single writer thread loads each scenario defined in the fixture through a recording JDBC
 * fake, where every JDBC commit captures the batch rows along with the watermark value at the
 * moment of the commit.
 */
public class CoGenerationWriteTest {
  private static final String[] SCENARIOS =
    { CoGenFixtures.DEFAULTS, CoGenFixtures.CONFIGURED, CoGenFixtures.CLASSES };
  private static final String[] TABLES = { "PHERF.COGEN", "PHERF.COGEN_C", "PHERF.COGEN_G" };

  /**
   * Represents a single JDBC commit, capturing the target table, the rows included in the batch,
   * and the watermark recorded at the time of the commit.
   */
  private static final class Commit {
    final String table;
    final List<List<Object>> rows;
    final long watermark;

    Commit(String table, List<List<Object>> rows, long watermark) {
      this.table = table;
      this.rows = rows;
      this.watermark = watermark;
    }
  }

  private XMLConfigParser parser;
  private final List<Commit> commits = Collections.synchronizedList(new ArrayList<>());
  private final List<String> keyLookups = Collections.synchronizedList(new ArrayList<>());

  @Before
  public void load() throws Exception {
    // The data load summary includes the cluster name, and Pherf provides no mechanism to clear
    // that name once it has been set.
    String zookeeper = PhoenixUtil.getZookeeper();
    PhoenixUtil.setZookeeper("cogen");
    try {
      write();
    } finally {
      if (zookeeper != null) {
        PhoenixUtil.setZookeeper(zookeeper);
      }
    }
  }

  private void write() throws Exception {
    parser = CoGenFixtures.parser();
    // Configure the writer's parsed cogenDefaults scenario to use blocks of 10 so that a COMMIT
    // watermark can advance during the load.
    parser.getScenarioByName(CoGenFixtures.DEFAULTS).getCoGeneration().setBlockSize("10");
    PhoenixUtil util = mock(PhoenixUtil.class);
    doCallRealMethod().when(util).buildSql(anyList(), anyString());
    doCallRealMethod().when(util).buildStatement(anyList(), anyList(), any(PreparedStatement.class),
      any(SimpleDateFormat.class));
    when(util.getColumnsFromPhoenix(anyString(), anyString(), any()))
      .thenReturn(CoGenFixtures.tableColumns());
    when(util.getConnection(nullable(String.class))).thenAnswer(inv -> connection());
    when(util.getConnection(nullable(String.class), any(Properties.class)))
      .thenAnswer(inv -> connection());
    Properties properties =
      PherfConstants.create().getProperties(PherfConstants.PHERF_PROPERTIES, false);
    properties.setProperty("pherf.default.dataloader.threadpool", "1");
    WriteWorkload write = new WriteWorkload(util, parser, properties, GeneratePhoenixStats.NO);
    try {
      write.execute().call();
    } finally {
      write.complete();
    }
  }

  /**
   * Verifies requirement RQ-181 by ensuring that the COMMIT watermark advances as each block is
   * committed, whereas the LOAD watermark remains unchanged until the entire write workload
   * completes. At the conclusion of the workload, every scenario has all of its rows committed.
   */
  @Test
  public void testWatermarkPolicy() throws Exception {
    assertEquals(list(0, 10, 20), watermarks(TABLES[0]));
    assertEquals(list(0, 0, 0, 0, 0), watermarks(TABLES[1]));
    for (String name : SCENARIOS) {
      RowCursor cursor = CoGenerator.of(parser, CoGenFixtures.scenario(parser, name)).cursor();
      assertEquals(name, 30, cursor.watermark());
      assertEquals(name, 30, cursor.highWater());
    }
  }

  /**
   * Verifies requirements RQ-176 and RQ-184 by confirming that the committed batches correspond to
   * the claimed blocks sequentially and without gaps. When configured with a clusterLimit of 2,
   * each batch contains at most 2 distinct values of the leading key K1 resolved from the database
   * metadata, while preserving primary key uniqueness. When clustering is not enabled, a batch
   * spans all values of K1.
   */
  @Test
  public void testCommittedBatchesAreClustered() throws Exception {
    List<Integer> sizes = new ArrayList<>();
    Set<List<Object>> keys = new HashSet<>();
    long next = 1000;
    for (Commit commit : commits(TABLES[1])) {
      sizes.add(commit.rows.size());
      Set<Object> leading = new HashSet<>();
      for (List<Object> row : commit.rows) {
        leading.add(row.get(0));
        assertTrue("duplicate key " + row, keys.add(row.subList(0, 2)));
        assertEquals(Integer.valueOf((int) next++), row.get(1));
      }
      assertTrue(leading.toString(), leading.size() <= 2);
    }
    assertEquals(list(7, 7, 7, 7, 2), toLongs(sizes));
    assertEquals(Collections.singletonList("PHERF.COGEN_C"), keyLookups);
    int most = 0;
    for (Commit commit : commits(TABLES[0])) {
      Set<Object> leading = new HashSet<>();
      for (List<Object> row : commit.rows) {
        leading.add(row.get(0));
      }
      most = Math.max(most, leading.size());
    }
    assertEquals(3, most);
  }

  private List<Commit> commits(String table) {
    List<Commit> selected = new ArrayList<>();
    synchronized (commits) {
      for (Commit commit : commits) {
        if (commit.table.equals(table)) {
          selected.add(commit);
        }
      }
    }
    return selected;
  }

  private List<Long> watermarks(String table) {
    List<Long> watermarks = new ArrayList<>();
    for (Commit commit : commits(table)) {
      watermarks.add(commit.watermark);
    }
    return watermarks;
  }

  private static List<Long> list(long... values) {
    List<Long> list = new ArrayList<>();
    for (long value : values) {
      list.add(value);
    }
    return list;
  }

  private static List<Long> toLongs(List<Integer> values) {
    List<Long> list = new ArrayList<>();
    for (int value : values) {
      list.add((long) value);
    }
    return list;
  }

  /**
   * Creates a mock JDBC connection that captures the parameter values bound to its upsert
   * statements and appends the resulting row batches to {@link #commits} upon each commit, along
   * with the current watermark of the scenario associated with the target table.
   */
  private Connection connection() throws Exception {
    final String[] table = new String[1];
    final Map<Integer, Object> row = new TreeMap<>();
    final List<List<Object>> pending = new ArrayList<>();
    final PreparedStatement statement = mock(PreparedStatement.class, inv -> {
      String name = inv.getMethod().getName();
      Object[] args = inv.getArguments();
      if (name.startsWith("set") && args.length > 1 && args[0] instanceof Integer) {
        row.put((Integer) args[0], name.equals("setNull") ? "NULL" : args[1]);
        return null;
      }
      if (name.equals("executeUpdate")) {
        pending.add(new ArrayList<>(row.values()));
        row.clear();
        return 1;
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
    final DatabaseMetaData metaData = mock(DatabaseMetaData.class, inv -> {
      if (inv.getMethod().getName().equals("getPrimaryKeys")) {
        keyLookups.add(inv.getArgument(1) + "." + inv.getArgument(2));
        return CoGenFixtures.primaryKeys("K1", "K2");
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
    return mock(Connection.class, inv -> {
      String name = inv.getMethod().getName();
      if (name.equals("prepareStatement")) {
        String sql = inv.getArgument(0);
        table[0] = sql.split(" ")[2];
        return statement;
      }
      if (name.equals("getMetaData")) {
        return metaData;
      }
      if (name.equals("commit")) {
        commits.add(new Commit(table[0], new ArrayList<>(pending), watermark(table[0])));
        pending.clear();
        return null;
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
  }

  private long watermark(String table) {
    for (int i = 0; i < TABLES.length; i++) {
      if (TABLES[i].equals(table)) {
        Scenario scenario = CoGenFixtures.scenario(parser, SCENARIOS[i]);
        return CoGenerator.of(parser, scenario).cursor().watermark();
      }
    }
    throw new AssertionError("no scenario for table " + table);
  }
}
