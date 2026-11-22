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

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.file.Paths;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.DataValue;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.mockito.Mockito;

/** Provides shared test fixtures and helper utilities for co-generation workload tests. */
public final class CoGenFixtures {
  public static final String FIXTURE = "/cogen/cogeneration.xml";
  public static final String DEFAULTS = "cogenDefaults";
  public static final String CONFIGURED = "cogenConfigured";
  public static final String CLASSES = "cogenClasses";
  public static final String LOOKUP = "SELECT V FROM PHERF.COGEN WHERE K1 = [K1] AND K2 = [K2]";

  private CoGenFixtures() {
  }

  /** Resolves the local file system path for a given test resource on the classpath. */
  public static String path(String resource) throws Exception {
    URL url = CoGenFixtures.class.getResource(resource);
    assertNotNull(resource, url);
    return Paths.get(url.toURI()).toString();
  }

  /** Creates a new configuration parser initialized with the co-generation XML test fixture. */
  public static XMLConfigParser parser() throws Exception {
    return new XMLConfigParser(path(FIXTURE));
  }

  /**
   * Locates and returns a named scenario from the parsed data models, matching how the query
   * executor retrieves scenario definitions.
   */
  public static Scenario scenario(XMLConfigParser parser, String name) {
    for (DataModel model : parser.getDataModels()) {
      for (Scenario scenario : model.getScenarios()) {
        if (scenario.getName().equals(name)) {
          return scenario;
        }
      }
    }
    throw new AssertionError("no scenario " + name);
  }

  /** Constructs an index decoder configured with its own rules applier instance. */
  public static IndexDecoder decoder(XMLConfigParser parser, String scenario, String leadingKey) {
    Scenario named = scenario(parser, scenario);
    return new IndexDecoder(new RulesApplier(parser), named, CoGenerator.Settings.of(named),
      leadingKey);
  }

  /**
   * Constructs a column definition populated with a name and a data type, matching the structure
   * provided by Phoenix metadata.
   */
  public static Column column(String name, DataTypeMapping type) {
    Column column = new Column();
    column.setName(name);
    column.setType(type);
    return column;
  }

  /** Returns the column definitions for the fixture table in their defined table order. */
  public static List<Column> tableColumns() {
    List<Column> columns = new ArrayList<>();
    columns.add(column("K1", DataTypeMapping.VARCHAR));
    columns.add(column("K2", DataTypeMapping.INTEGER));
    columns.add(column("V", DataTypeMapping.VARCHAR));
    return columns;
  }

  /**
   * Binds a single row through the writer's JDBC statement binding pathway and returns the bound
   * values in parameter order.
   */
  public static List<Object> bind(List<Column> columns, List<DataValue> values) throws Exception {
    Map<Integer, Object> calls = new TreeMap<>();
    PhoenixUtil.create().buildStatement(columns, values, recorder(calls),
      IndexDecoder.dateFormat());
    return new ArrayList<>(calls.values());
  }

  /**
   * Creates a mock prepared statement that records the value of each parameter-setting call by its
   * parameter index. Null bindings are recorded as the string "NULL", and the statement's mock
   * connection supports creating SQL arrays.
   */
  public static PreparedStatement recorder(final Map<Integer, Object> calls) throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.createArrayOf(anyString(), any(Object[].class))).thenAnswer(inv -> {
      Array array = mock(Array.class);
      when(array.getArray()).thenReturn(inv.getArgument(1));
      return array;
    });
    return mock(PreparedStatement.class, inv -> {
      String name = inv.getMethod().getName();
      Object[] args = inv.getArguments();
      if (name.equals("getConnection")) {
        return connection;
      }
      if (name.startsWith("set") && args.length > 1 && args[0] instanceof Integer) {
        calls.put((Integer) args[0], name.equals("setNull") ? "NULL" : args[1]);
        return null;
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
  }

  /**
   * Creates a mock connection whose database metadata returns the primary key columns of every
   * table in reverse key order, ensuring that only KEY_SEQ identifies the leading key. All other
   * method invocations return default values.
   */
  public static Connection keyedConnection(final String... keys) throws Exception {
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(metaData.getPrimaryKeys(nullable(String.class), nullable(String.class), anyString()))
      .thenAnswer(inv -> primaryKeys(keys));
    Connection connection = mock(Connection.class);
    when(connection.getMetaData()).thenReturn(metaData);
    return connection;
  }

  /**
   * Builds a mock result set representing the primary key metadata rows for
   * {@link #keyedConnection}, providing COLUMN_NAME and KEY_SEQ values.
   */
  public static ResultSet primaryKeys(final String... keys) {
    final int[] row = { 0 };
    return mock(ResultSet.class, inv -> {
      String name = inv.getMethod().getName();
      int k = keys.length - row[0];
      if (name.equals("next")) {
        return ++row[0] <= keys.length;
      }
      if (name.equals("getString")) {
        return keys[k];
      }
      if (name.equals("getInt") || name.equals("getShort")) {
        return k + 1;
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
  }
}
