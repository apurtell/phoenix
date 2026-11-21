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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonParser;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies that Pherf can persist each supported native data type to a Phoenix table and accurately
 * retrieve the written values on read.
 */
@Category(NeedsOwnMiniClusterTest.class)
public class NativeTypesIT extends ResultBaseTestIT {
  private static final long SEED = 8070L;

  @Test
  public void testNativeTypesRoundTrip() throws Exception {
    DataModel model = XMLConfigParser.readDataModel(
      Paths.get(NativeTypesIT.class.getResource("/scenario/test_native_types.xml").toURI()));
    Scenario scenario = model.getScenarios().get(0);
    int rowCount = scenario.getRowCount();
    try (Connection conn = util.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE PHERF.NATIVE_TYPES (ID VARBINARY_ENCODED NOT NULL PRIMARY KEY,"
        + " VB_L VARBINARY_ENCODED, FA FLOAT ARRAY, FA_L FLOAT ARRAY, DA DOUBLE ARRAY,"
        + " DA_L DOUBLE ARRAY, DOC JSON, DOC_L JSON, BDOC BSON, BDOC_L BSON)");

      // Verify that the schema metadata lookup resolves the Phoenix type name of each column to
      // its corresponding Pherf data type mapping.
      List<Column> columns = util.getColumnsFromPhoenix("PHERF", "NATIVE_TYPES", conn);
      List<DataTypeMapping> types = new ArrayList<>();
      List<String> names = new ArrayList<>();
      for (Column column : columns) {
        types.add(column.getType());
        names.add(column.getName());
      }
      assertEquals(Arrays.asList(DataTypeMapping.VARBINARY_ENCODED,
        DataTypeMapping.VARBINARY_ENCODED, DataTypeMapping.FLOAT_ARRAY, DataTypeMapping.FLOAT_ARRAY,
        DataTypeMapping.DOUBLE_ARRAY, DataTypeMapping.DOUBLE_ARRAY, DataTypeMapping.JSON,
        DataTypeMapping.JSON, DataTypeMapping.BSON, DataTypeMapping.BSON), types);

      // Populate the table with generated rows using the same statement construction path as the
      // standard write workload.
      RulesApplier writer = new RulesApplier(model, SEED);
      SimpleDateFormat format = new SimpleDateFormat(PherfConstants.DEFAULT_DATE_PATTERN);
      String upsert = util.buildSql(columns, scenario.getTableName());
      for (int r = 0; r < rowCount; r++) {
        try (PreparedStatement ps = conn.prepareStatement(upsert)) {
          util.buildStatement(writer, scenario, columns, ps, format);
          assertEquals(1, ps.executeUpdate());
        }
      }
      conn.commit();

      // Instantiate a second rules applier with the identical random seed to deterministically
      // reproduce the expected row values, indexed by the hexadecimal representation of the ID.
      RulesApplier twin = new RulesApplier(model, SEED);
      Map<String, List<String>> expected = new HashMap<>();
      for (int r = 0; r < rowCount; r++) {
        List<String> row = new ArrayList<>();
        for (Column column : columns) {
          row.add(twin.getDataForRule(scenario, column).getValue());
        }
        expected.put(row.get(0), row);
      }
      assertEquals(rowCount, expected.size());

      int rows = 0;
      try (ResultSet rs = stmt
        .executeQuery("SELECT " + String.join(",", names) + " FROM " + scenario.getTableName())) {
        while (rs.next()) {
          rows++;
          String id = Bytes.toHex(rs.getBytes(1));
          List<String> want = expected.get(id);
          assertNotNull("unexpected row " + id, want);
          for (int c = 1; c <= columns.size(); c++) {
            assertColumn(columns.get(c - 1), want.get(c - 1), rs, c);
          }
        }
      }
      assertEquals(rowCount, rows);
    }
  }

  /**
   * Asserts that binary bytes and array elements match their expected values exactly, and compares
   * document columns by their parsed JSON structure.
   */
  private static void assertColumn(Column column, String want, ResultSet rs, int c)
    throws Exception {
    String what = column.getName() + " " + want;
    switch (column.getType()) {
      case VARBINARY_ENCODED:
        assertEquals(what, want, Bytes.toHex(rs.getBytes(c)));
        break;
      case FLOAT_ARRAY:
      case DOUBLE_ARRAY:
        Object elements = rs.getArray(c).getArray();
        List<Object> got = new ArrayList<>();
        for (int i = 0; i < java.lang.reflect.Array.getLength(elements); i++) {
          got.add(java.lang.reflect.Array.get(elements, i));
        }
        List<Object> tokens = new ArrayList<>();
        for (String token : want.split(",")) {
          if (column.getType() == DataTypeMapping.FLOAT_ARRAY) {
            tokens.add(Float.valueOf(token));
          } else {
            tokens.add(Double.valueOf(token));
          }
        }
        assertEquals(what, tokens, got);
        break;
      case JSON:
      case BSON:
        assertEquals(what, JsonParser.parseString(want), JsonParser.parseString(rs.getString(c)));
        break;
      default:
        throw new AssertionError("no check for " + what);
    }
  }
}
