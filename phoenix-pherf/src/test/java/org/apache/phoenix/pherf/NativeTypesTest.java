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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataSequence;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.DataValue;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.schema.types.PBson;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDoubleArray;
import org.apache.phoenix.schema.types.PFloatArray;
import org.apache.phoenix.schema.types.PJson;
import org.apache.phoenix.schema.types.PVarbinaryEncoded;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * Verifies that every native data type defines a corresponding type mapping, supports deterministic
 * value generation for both RANDOM and LIST rules using a fixed seed, and provides a JDBC parameter
 * binding.
 */
public class NativeTypesTest {
  private static final long SEED = 8070L;
  private static final String FIXTURE = "/scenario/test_native_types.xml";

  /**
   * Defines the fixture column names in table declaration order alongside their corresponding
   * Phoenix data types.
   */
  private static final String[] NAMES =
    { "ID", "VB_L", "FA", "FA_L", "DA", "DA_L", "DOC", "DOC_L", "BDOC", "BDOC_L" };
  private static final PDataType[] TYPES = { PVarbinaryEncoded.INSTANCE, PVarbinaryEncoded.INSTANCE,
    PFloatArray.INSTANCE, PFloatArray.INSTANCE, PDoubleArray.INSTANCE, PDoubleArray.INSTANCE,
    PJson.INSTANCE, PJson.INSTANCE, PBson.INSTANCE, PBson.INSTANCE };

  private DataModel model;

  @Before
  public void readModel() throws Exception {
    model = readFixture();
  }

  @Test
  public void testFloatArray() throws Exception {
    assertMapped(PFloatArray.INSTANCE);
    checkArray("FA", 4, -100, 100, "FLOAT", Float.class, Arrays.asList("1.5", "-2.25", "0.0"));
  }

  @Test
  public void testDoubleArray() throws Exception {
    assertMapped(PDoubleArray.INSTANCE);
    checkArray("DA", 3, 0, 1000, "DOUBLE", Double.class, Arrays.asList("0.125", "999.5"));
  }

  @Test
  public void testVarbinaryEncoded() throws Exception {
    assertMapped(PVarbinaryEncoded.INSTANCE);
    String random = seeded("ID");
    assertEquals(32, random.length());
    assertEquals(16, Bytes.fromHex(random).length);
    String listed = seeded("VB_L");
    assertTrue(listed, Arrays.asList("00ff00", "ff00", "0a").contains(listed));

    List<String> expected = expectedRow(model);
    Map<Integer, InvocationOnMock> row = bindRow(model);
    for (String name : new String[] { "ID", "VB_L" }) {
      InvocationOnMock call = row.get(index(name));
      assertEquals("setBytes", call.getMethod().getName());
      assertArrayEquals(Bytes.fromHex(expected.get(index(name) - 1)), (byte[]) call.getArgument(1));
    }
    assertNullBinding("ID", Types.VARBINARY);
    assertNullBinding("VB_L", Types.VARBINARY);
  }

  @Test
  public void testJson() throws Exception {
    assertMapped(PJson.INSTANCE);
    String random = seeded("DOC");
    assertDocument(random, 8, "p");
    assertNotNull(PJson.INSTANCE.toObject(random));
    String listed = seeded("DOC_L");
    assertTrue(listed, valuesOf("DOC_L").contains(listed));
    assertNotNull(PJson.INSTANCE.toObject(listed));
    checkStringBinding("DOC", "DOC_L");
    assertSequentialNeedsList(PJson.INSTANCE);
  }

  @Test
  public void testBson() throws Exception {
    assertMapped(PBson.INSTANCE);
    String random = seeded("BDOC");
    assertDocument(random, 8, "");
    assertNotNull(PBson.INSTANCE.toObject(random));
    String listed = seeded("BDOC_L");
    assertTrue(listed, valuesOf("BDOC_L").contains(listed));
    assertNotNull(PBson.INSTANCE.toObject(listed));
    checkStringBinding("BDOC", "BDOC_L");
    assertSequentialNeedsList(PBson.INSTANCE);
  }

  @Test
  public void testUnboundTypeThrows() throws Exception {
    // Verify that attempting to bind a data type without a supported JDBC binding throws an
    // exception rather than silently skipping the statement parameter.
    Column flag = column("FLAG", DataTypeMapping.BOOLEAN);
    RulesApplier rules = mock(RulesApplier.class);
    when(rules.getDataForRule(any(Scenario.class), any(Column.class)))
      .thenReturn(new DataValue(DataTypeMapping.BOOLEAN, "true"));
    Map<Integer, InvocationOnMock> calls = new HashMap<>();
    try {
      PhoenixUtil.create().buildStatement(rules, scenario(model), Collections.singletonList(flag),
        recorder(calls), new SimpleDateFormat(PherfConstants.DEFAULT_DATE_PATTERN));
      fail("a type without a binding must throw");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("FLAG"));
    }
    assertTrue(calls.isEmpty());
  }

  /**
   * Validates data generation and JDBC statement binding for a RANDOM array column and its paired
   * LIST array column.
   */
  private void checkArray(String name, int length, double min, double max, String baseType,
    Class<?> elementType, List<String> listed) throws Exception {
    String random = seeded(name);
    List<String> tokens = Arrays.asList(random.split(","));
    assertEquals(random, length, tokens.size());
    for (String token : tokens) {
      double v = Double.parseDouble(token);
      assertTrue(random, v >= min && v <= max);
    }
    String listName = name + "_L";
    assertEquals(String.join(",", listed), seeded(listName));

    List<String> expected = expectedRow(model);
    Map<Integer, InvocationOnMock> row = bindRow(model);
    for (String column : new String[] { name, listName }) {
      InvocationOnMock call = row.get(index(column));
      assertEquals("setArray", call.getMethod().getName());
      Array array = (Array) call.getArgument(1);
      assertEquals(baseType, array.getBaseTypeName());
      Object[] elements = (Object[]) array.getArray();
      assertEquals(elementType, elements.getClass().getComponentType());
      List<Object> want = new ArrayList<>();
      for (String token : expected.get(index(column) - 1).split(",")) {
        if (elementType == Float.class) {
          want.add(Float.valueOf(token));
        } else {
          want.add(Double.valueOf(token));
        }
      }
      assertEquals(want, Arrays.asList(elements));
    }
    assertNullBinding(name, Types.ARRAY);
    assertNullBinding(listName, Types.ARRAY);
  }

  private void checkStringBinding(String... names) throws Exception {
    List<String> expected = expectedRow(model);
    Map<Integer, InvocationOnMock> row = bindRow(model);
    for (String name : names) {
      InvocationOnMock call = row.get(index(name));
      assertEquals("setString", call.getMethod().getName());
      assertEquals(expected.get(index(name) - 1), call.getArgument(1));
      assertNullBinding(name, Types.VARBINARY);
    }
  }

  /**
   * Verifies that a SEQUENTIAL document generation rule without an explicit value list throws an
   * exception because a sequential counter string is not a valid document.
   */
  private void assertSequentialNeedsList(PDataType type) throws Exception {
    Column rule = column("SEQ_DOC", mapped(type));
    rule.setDataSequence(DataSequence.SEQUENTIAL);
    rule.setLength(8);
    try {
      new RulesApplier(model, SEED).getDataValue(rule);
      fail("SEQUENTIAL " + type + " without a value list must throw");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("value list"));
    }
  }

  /**
   * Verifies that a RANDOM document conforms to the expected fixed structural shape {s, n, d, b,
   * a[3], o{s, n}}.
   */
  private static void assertDocument(String text, int length, String prefix) {
    JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
    assertEquals(new HashSet<>(Arrays.asList("s", "n", "d", "b", "a", "o")), doc.keySet());
    String s = doc.get("s").getAsString();
    assertEquals(text, length, s.length());
    assertTrue(text, s.startsWith(prefix));
    doc.get("n").getAsInt();
    doc.get("d").getAsDouble();
    doc.get("b").getAsBoolean();
    JsonArray a = doc.get("a").getAsJsonArray();
    assertEquals(3, a.size());
    JsonObject o = doc.get("o").getAsJsonObject();
    assertEquals(new HashSet<>(Arrays.asList("s", "n")), o.keySet());
    assertEquals(length, o.get("s").getAsString().length());
    o.get("n").getAsInt();
  }

  /**
   * Verifies that a Phoenix data type resolves to a Pherf mapping through its metadata TYPE_NAME
   * and formats back to that exact SQL type name.
   */
  private static DataTypeMapping assertMapped(PDataType type) {
    DataTypeMapping mapped = mapped(type);
    assertEquals(type.getSqlTypeName(), mapped.toString());
    return mapped;
  }

  private static DataTypeMapping mapped(PDataType type) {
    return DataTypeMapping.valueOf(type.getSqlTypeName().replace(" ", "_"));
  }

  /**
   * Generates a value for a single column and verifies that a second rule applier initialized with
   * the same seed produces an identical value.
   */
  private String seeded(String name) throws Exception {
    Column meta = metaColumns().get(index(name) - 1);
    String first = new RulesApplier(model, SEED).getDataForRule(scenario(model), meta).getValue();
    String second = new RulesApplier(model, SEED).getDataForRule(scenario(model), meta).getValue();
    assertNotNull(first);
    assertEquals(first, second);
    return first;
  }

  /**
   * Produces the expected sequence of values for a single row in column order using a rule applier
   * initialized with the same seed.
   */
  private static List<String> expectedRow(DataModel m) throws Exception {
    RulesApplier twin = new RulesApplier(m, SEED);
    List<String> values = new ArrayList<>();
    for (Column meta : metaColumns()) {
      values.add(twin.getDataForRule(scenario(m), meta).getValue());
    }
    return values;
  }

  /**
   * Binds a generated row to a prepared statement and verifies that every parameter position
   * receives a binding.
   */
  private static Map<Integer, InvocationOnMock> bindRow(DataModel m) throws Exception {
    Map<Integer, InvocationOnMock> calls = new HashMap<>();
    PhoenixUtil.create().buildStatement(new RulesApplier(m, SEED), scenario(m), metaColumns(),
      recorder(calls), new SimpleDateFormat(PherfConstants.DEFAULT_DATE_PATTERN));
    Set<Integer> all = new HashSet<>();
    for (int i = 1; i <= NAMES.length; i++) {
      all.add(i);
    }
    assertEquals(all, calls.keySet());
    return calls;
  }

  private static void assertNullBinding(String name, int sqlType) throws Exception {
    InvocationOnMock call = bindRow(nullModel()).get(index(name));
    assertEquals("setNull", call.getMethod().getName());
    assertEquals(sqlType, (int) call.getArgument(1));
  }

  /**
   * Loads a copy of the fixture data model configured with a nullChance of 100 on every column
   * rule.
   */
  private static DataModel nullModel() throws Exception {
    DataModel m = readFixture();
    for (Column rule : m.getDataMappingColumns()) {
      rule.setNullChance(100);
    }
    return m;
  }

  private List<String> valuesOf(String name) {
    List<String> values = new ArrayList<>();
    for (Column rule : model.getDataMappingColumns()) {
      if (rule.getName().equals(name)) {
        for (DataValue v : rule.getDataValues()) {
          values.add(v.getValue());
        }
      }
    }
    return values;
  }

  private static DataModel readFixture() throws Exception {
    URL url = NativeTypesTest.class.getResource(FIXTURE);
    assertNotNull(FIXTURE, url);
    Path path = Paths.get(url.toURI());
    return XMLConfigParser.readDataModel(path);
  }

  private static Scenario scenario(DataModel m) {
    return m.getScenarios().get(0);
  }

  private static int index(String name) {
    return Arrays.asList(NAMES).indexOf(name) + 1;
  }

  /**
   * Constructs column descriptors matching the structure provided by Phoenix metadata, consisting
   * of each column name and its mapped Pherf data type.
   */
  private static List<Column> metaColumns() {
    List<Column> columns = new ArrayList<>();
    for (int i = 0; i < NAMES.length; i++) {
      columns.add(column(NAMES[i], mapped(TYPES[i])));
    }
    return columns;
  }

  private static Column column(String name, DataTypeMapping type) {
    Column column = new Column();
    column.setName(name);
    column.setType(type);
    return column;
  }

  /**
   * Creates a mock PreparedStatement that records each parameter set invocation by its parameter
   * index and raises an error if a parameter is bound two times. Its associated connection creates
   * Array instances that preserve their base type name and elements.
   */
  private static PreparedStatement recorder(final Map<Integer, InvocationOnMock> calls)
    throws SQLException {
    Connection connection = mock(Connection.class);
    when(connection.createArrayOf(anyString(), any(Object[].class))).thenAnswer(inv -> {
      Array array = mock(Array.class);
      when(array.getBaseTypeName()).thenReturn(inv.getArgument(0));
      when(array.getArray()).thenReturn(inv.getArgument(1));
      return array;
    });
    return mock(PreparedStatement.class, inv -> {
      String name = inv.getMethod().getName();
      Object[] args = inv.getArguments();
      if (name.equals("getConnection")) {
        return connection;
      }
      if (name.startsWith("set") && args.length > 0 && args[0] instanceof Integer) {
        if (calls.put((Integer) args[0], inv) != null) {
          throw new AssertionError("parameter " + args[0] + " is bound two times");
        }
        return null;
      }
      return Mockito.RETURNS_DEFAULTS.answer(inv);
    });
  }
}
