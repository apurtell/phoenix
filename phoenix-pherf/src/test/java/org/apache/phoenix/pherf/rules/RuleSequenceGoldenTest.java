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
package org.apache.phoenix.pherf.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.Golden;
import org.junit.Test;

/**
 * Verifies requirement RQ-172 by ensuring that, when co-generation is not enabled, the seeded
 * rule-based generators produce the exact same sequence as they did prior to P7. The reference
 * golden file was captured from the codebase before the first P7 change. Any column whose value
 * source is unseeded (specifically the clock, ThreadLocalRandom, RandomUtils, RandomStringUtils, or
 * the unseeded RandomDataGenerator) is emitted as a structural shape token. All remaining values
 * are recorded verbatim, ensuring that any change in how the seeded value streams or null streams
 * are consumed alters the output.
 */
public class RuleSequenceGoldenTest {
  private static final long SEED = 8070L;
  private static final int WRITES = 25;
  private static final int READS = 10;
  private static final int NATIVE_WRITES = 10;

  /**
   * Defines the TEST_TABLE columns from test_schema.sql in table order, matching the sequence
   * returned by Phoenix metadata.
   */
  private static final String[] TABLE_COLUMNS = { "TENANT_ID", "CHAR", "PARENT_ID", "CHAR",
    "CREATED_DATE", "DATE", "SOME_DATE", "DATE", "RND_DATE", "DATE", "TS_DATE", "TIMESTAMP",
    "PRESENT_DATE", "DATE", "OTHER_ID", "CHAR", "FIELD", "VARCHAR", "VAR_ARRAY", "VARCHAR_ARRAY",
    "VAR_BIN", "VARBINARY", "DIVISION", "INTEGER", "OLDVAL_STRING", "VARCHAR", "NEWVAL_STRING",
    "VARCHAR", "CONNECTION_ID", "VARCHAR", "SOME_INT", "INTEGER" };

  /** Identifies the columns whose generated values originate from an unseeded source. */
  private static final Set<String> UNSEEDED = new HashSet<>(Arrays.asList("CREATED_DATE",
    "SOME_DATE", "RND_DATE", "TS_DATE", "PRESENT_DATE", "FIELD", "OLDVAL_STRING", "CONNECTION_ID"));

  private static final String[] NATIVE_COLUMNS = { "ID", "VARBINARY_ENCODED", "VB_L",
    "VARBINARY_ENCODED", "FA", "FLOAT_ARRAY", "FA_L", "FLOAT_ARRAY", "DA", "DOUBLE_ARRAY", "DA_L",
    "DOUBLE_ARRAY", "DOC", "JSON", "DOC_L", "JSON", "BDOC", "BSON", "BDOC_L", "BSON" };

  private static final String READ = "SELECT * FROM PHERF.TEST_TABLE WHERE PARENT_ID = [PARENT_ID]"
    + " AND NEWVAL_STRING = [NEWVAL_STRING] AND OTHER_ID = [OTHER_ID]";

  @Test
  public void testRuleSequenceUnchanged() throws Exception {
    StringBuilder out = new StringBuilder();

    DataModel model = new XMLConfigParser(".*test_scenario.xml").getDataModels().get(0);
    Scenario scenario = scenario(model, "testScenario");
    RulesApplier rules = new RulesApplier(model, SEED);
    List<Column> columns = columns(TABLE_COLUMNS);
    for (int row = 0; row < WRITES; row++) {
      for (Column column : columns) {
        String value = rules.getDataForRule(scenario, column).getValue();
        out.append("write\t").append(row).append('\t').append(column.getName()).append('\t')
          .append(Golden
            .cell(UNSEEDED.contains(column.getName()) ? shape(column.getName(), value) : value))
          .append('\n');
      }
    }
    // Execute read queries using the same rules applier instance after the write pass; the read
    // sequences do not continue from the write sequences.
    Query query = new Query();
    query.setStatement(READ);
    for (int read = 0; read < READS; read++) {
      out.append("read\t").append(read).append('\t')
        .append(query.getDynamicStatement(rules, scenario)).append('\n');
    }

    DataModel nativeModel = new XMLConfigParser(".*test_native_types.xml").getDataModels().get(0);
    Scenario nativeScenario = scenario(nativeModel, "testNativeTypes");
    RulesApplier nativeRules = new RulesApplier(nativeModel, SEED);
    List<Column> nativeColumns = columns(NATIVE_COLUMNS);
    for (int row = 0; row < NATIVE_WRITES; row++) {
      for (Column column : nativeColumns) {
        out.append("native\t").append(row).append('\t').append(column.getName()).append('\t')
          .append(Golden.cell(nativeRules.getDataForRule(nativeScenario, column).getValue()))
          .append('\n');
      }
    }

    Golden.assertMatches("RuleSequenceGoldenTest", "rule-sequence.golden", out.toString());
  }

  /**
   * Produces a structural token representing a value from an unseeded source. The token preserves
   * the properties constrained by the rule: whether the value is null or non-null, the date format
   * pattern, or the string length, prefix, and character class.
   */
  private static String shape(String name, String value) {
    if (value.isEmpty()) {
      return "<null>";
    }
    if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) {
      return "<date>";
    }
    String prefix = "OLDVAL_STRING".equals(name) ? "MYPRFX" : "";
    String rest = value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    if (rest == null || !rest.matches("[0-9A-Za-z]*")) {
      return "<bad:" + value + ">";
    }
    return "<alnum " + value.length() + (prefix.isEmpty() ? "" : " prefix " + prefix) + ">";
  }

  private static Scenario scenario(DataModel model, String name) {
    for (Scenario scenario : model.getScenarios()) {
      if (scenario.getName().equals(name)) {
        return scenario;
      }
    }
    throw new AssertionError("no scenario " + name);
  }

  private static List<Column> columns(String[] namesAndTypes) {
    List<Column> columns = new ArrayList<>();
    for (int i = 0; i < namesAndTypes.length; i += 2) {
      Column column = new Column();
      column.setName(namesAndTypes[i]);
      column.setType(DataTypeMapping.valueOf(namesAndTypes[i + 1]));
      columns.add(column);
    }
    return columns;
  }
}
