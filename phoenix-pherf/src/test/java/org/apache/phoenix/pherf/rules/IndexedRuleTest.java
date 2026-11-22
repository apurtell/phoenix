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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataSequence;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.Golden;
import org.apache.phoenix.pherf.workload.cogen.CoGenFixtures;
import org.apache.phoenix.pherf.workload.cogen.IndexDecoder;
import org.junit.Test;

/**
 * Verifies RQ-173 and RQ-174 by ensuring that an index-based value is computed as a pure function
 * of the row index, column rule, and seed, and that each rule kind deterministically maps the row
 * index into its declared sequence, distribution weights, and value bounds.
 */
public class IndexedRuleTest {
  private static final long SEED = IndexHash.columnSeed(8070, "C");
  private static final int N = 100000;
  /**
   * Represents an absolute frequency tolerance equivalent to approximately 6 standard deviations
   * over a sample size of N = 100,000.
   */
  private static final double TOLERANCE = 0.01;
  private static final DateTimeFormatter UTC =
    DateTimeFormatter.ofPattern(PherfConstants.DEFAULT_DATE_PATTERN).withZone(ZoneOffset.UTC);

  /** Defines the fixture columns evaluated against the golden file, covering each rule kind. */
  private static final String[] GOLDEN_COLUMNS =
    { "K1", "VARCHAR", "K2", "INTEGER", "V", "VARCHAR", "T_CHAR", "CHAR", "T_VARBINARY",
      "VARBINARY", "T_VBE", "VARBINARY_ENCODED", "T_DEC", "DECIMAL", "T_INT", "INTEGER", "T_TINY",
      "TINYINT", "T_BIG", "BIGINT", "T_ULONG", "UNSIGNED_LONG", "T_DATE", "DATE", "T_TS",
      "TIMESTAMP", "T_DLIST", "DATE", "T_FA", "FLOAT_ARRAY", "T_DA", "DOUBLE_ARRAY", "T_VA",
      "VARCHAR_ARRAY", "T_JSON", "JSON", "T_BSON", "BSON", "T_NULL", "VARCHAR" };

  /**
   * Verifies RQ-173 by confirming that two decoders constructed from separate configuration parses
   * produce identical values for every column and row index, and that their output matches the
   * reference golden file captured once and validated across subsequent JVM executions.
   */
  @Test
  public void testValuesAreReproducible() throws Exception {
    IndexDecoder first =
      CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null);
    IndexDecoder second =
      CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null);
    StringBuilder out = new StringBuilder();
    for (Column column : goldenColumns()) {
      for (long i = 0; i < 20; i++) {
        String value = first.value(column, i).getValue();
        assertEquals(column.getName() + " " + i, value, second.value(column, i).getValue());
        // Replace spaces with 'T' so that date values are not altered by the time mask applied
        // during golden file comparison.
        out.append(column.getName()).append('\t').append(i).append('\t')
          .append(Golden.cell(value.replace(' ', 'T'))).append('\n');
      }
    }
    Golden.assertMatches("IndexedRuleTest", "cogen-values.golden", out.toString());
  }

  /**
   * Verifies RQ-173 by confirming that changing the seed alters hashed values produced by LIST,
   * RANDOM, and null rules, whereas SEQUENTIAL values and constant lists remain unchanged because
   * they do not depend on the seed by definition.
   */
  @Test
  public void testSeedChangesHashedValues() throws Exception {
    IndexDecoder base = CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null);
    XMLConfigParser other = CoGenFixtures.parser();
    CoGenFixtures.scenario(other, CoGenFixtures.DEFAULTS).getCoGeneration().setSeed("8071");
    IndexDecoder reseeded = CoGenFixtures.decoder(other, CoGenFixtures.DEFAULTS, null);
    for (Column column : goldenColumns()) {
      int differ = 0;
      for (long i = 0; i < 100; i++) {
        if (!base.value(column, i).getValue().equals(reseeded.value(column, i).getValue())) {
          differ++;
        }
      }
      switch (column.getName()) {
        case "K2":
        case "T_VARBINARY":
        case "T_ULONG":
        case "T_TS":
        case "T_VA":
          assertEquals(column.getName(), 0, differ);
          break;
        default:
          assertTrue(column.getName() + " differs at " + differ + " of 100", differ > 10);
      }
    }
  }

  /**
   * Verifies that for a SEQUENTIAL INTEGER rule, value(i) produces the i-th value generated by
   * SequentialIntegerDataGenerator, including wrapping around at the configured range boundary.
   */
  @Test
  public void testSequentialInteger() throws Exception {
    Column rule = rule(DataTypeMapping.INTEGER, DataSequence.SEQUENTIAL);
    rule.setMinValue(3);
    rule.setMaxValue(9);
    assertSequence(rule, new SequentialIntegerDataGenerator(rule), 0, 30);
    assertEquals("3", value(rule, 7));
  }

  /**
   * Verifies that a SEQUENTIAL VARCHAR rule formats the counter with the configured prefix and
   * padding and truncates it to the target length even when the counter exceeds the padding width.
   */
  @Test
  public void testSequentialVarchar() throws Exception {
    Column rule = rule(DataTypeMapping.VARCHAR, DataSequence.SEQUENTIAL);
    rule.setLength(6);
    rule.setPrefix("ab");
    assertSequence(rule, new SequentialVarcharDataGenerator(rule), 0, 12000);
    assertEquals("abxx12", value(rule, 12));
  }

  /**
   * Verifies that a SEQUENTIAL rule configured with a value list selects elements in round-robin
   * order across the list.
   */
  @Test
  public void testSequentialList() throws Exception {
    Column rule = rule(DataTypeMapping.CHAR, DataSequence.SEQUENTIAL);
    rule.setDataValues(values(DataTypeMapping.CHAR, "x", "y", "z"));
    assertSequence(rule, new SequentialListDataGenerator(rule), 0, 30);
  }

  /**
   * Verifies that a SEQUENTIAL DATE rule starts at 1970-01-01 00:00:00.000 and advances by one
   * second per row index, matching the step increment of SequentialDateDataGenerator.
   */
  @Test
  public void testSequentialDate() throws Exception {
    DateTimeFormatter format = DateTimeFormatter.ofPattern(PherfConstants.DEFAULT_DATE_PATTERN);
    for (DataTypeMapping type : new DataTypeMapping[] { DataTypeMapping.DATE,
      DataTypeMapping.TIMESTAMP }) {
      Column rule = rule(type, DataSequence.SEQUENTIAL);
      SequentialDateDataGenerator generator = new SequentialDateDataGenerator(rule);
      LocalDateTime start = LocalDateTime.parse(generator.getDataValue().getValue(), format);
      assertEquals("1970-01-01 00:00:00.000", value(rule, 0));
      for (int i = 1; i < 100; i++) {
        LocalDateTime next = LocalDateTime.parse(generator.getDataValue().getValue(), format);
        assertEquals(java.time.Duration.between(start, next).getSeconds(),
          java.time.Duration.between(LocalDateTime.parse(value(rule, 0), format),
            LocalDateTime.parse(value(rule, i), format)).getSeconds());
      }
    }
  }

  /**
   * Verifies that because the global index equals the configured offset plus the local index,
   * applying an offset seamlessly continues the declared sequence from that starting position.
   */
  @Test
  public void testSequentialWithOffset() throws Exception {
    Column rule = rule(DataTypeMapping.BIGINT, DataSequence.SEQUENTIAL);
    rule.setMinValue(0);
    rule.setMaxValue(Long.MAX_VALUE - 1);
    assertSequence(rule, new SequentialIntegerDataGenerator(rule), 1000, 50);
    XMLConfigParser parser = CoGenFixtures.parser();
    CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS).getCoGeneration().setOffset("5000");
    IndexDecoder decoder = CoGenFixtures.decoder(parser, CoGenFixtures.DEFAULTS, null);
    Column k2 = CoGenFixtures.column("K2", DataTypeMapping.INTEGER);
    for (long i = 0; i < 50; i++) {
      assertEquals(String.valueOf(5000 + i), decoder.value(k2, i).getValue());
    }
  }

  /**
   * Verifies that LIST selection frequencies across indexes 0..99,999 match the declared weights
   * within an absolute tolerance of 0.01 (approximately 6 standard deviations), producing a
   * deterministic outcome under a fixed seed while ensuring that entries with weight 0 are never
   * selected and unweighted lists are sampled uniformly.
   */
  @Test
  public void testListFrequencies() throws Exception {
    assertFrequencies(new int[] { 60, 20, 20 });
    assertFrequencies(new int[] { 80, 10, 10 });
    assertFrequencies(new int[] { 0, 0, 0 });
    assertFrequencies(new int[] { 50, 0, 25, 0, 25 });
  }

  /**
   * Verifies that every generated RANDOM value falls within its configured bounds and satisfies its
   * declared length and prefix constraints.
   */
  @Test
  public void testRandomBounds() throws Exception {
    Column varchar = random(DataTypeMapping.VARCHAR);
    varchar.setLength(9);
    varchar.setPrefix("pre");
    Column chars = random(DataTypeMapping.CHAR);
    chars.setLength(5);
    Column binary = random(DataTypeMapping.VARBINARY);
    binary.setLength(4);
    binary.setPrefix("b");
    Column integer = bounded(DataTypeMapping.INTEGER, -3, 3);
    Column tiny = bounded(DataTypeMapping.TINYINT, -128, 127);
    Column big = bounded(DataTypeMapping.BIGINT, 5, 9);
    Column unsigned = bounded(DataTypeMapping.UNSIGNED_LONG, 1, Long.MAX_VALUE);
    Column decimal = bounded(DataTypeMapping.DECIMAL, 2, 30);
    decimal.setPrecision(1);
    Column date = bounded(DataTypeMapping.DATE, 2001, 2001);
    Column timestamp = bounded(DataTypeMapping.TIMESTAMP, 1999, 2000);
    Column encoded = random(DataTypeMapping.VARBINARY_ENCODED);
    encoded.setLength(6);
    Column floats = bounded(DataTypeMapping.FLOAT_ARRAY, -2, 2);
    floats.setLength(3);
    Column doubles = bounded(DataTypeMapping.DOUBLE_ARRAY, 10, 20);
    doubles.setLength(2);
    Column json = random(DataTypeMapping.JSON);
    json.setLength(4);
    json.setPrefix("j");
    Column bson = random(DataTypeMapping.BSON);
    bson.setLength(3);
    int[] intSeen = new int[7];
    for (long i = 0; i < 2000; i++) {
      assertText(value(varchar, i), 9, "pre");
      assertText(value(chars, i), 5, "");
      assertText(value(binary, i), 4, "b");
      intSeen[Integer.parseInt(value(integer, i)) + 3]++;
      assertBetween(value(tiny, i), -128, 127);
      assertBetween(value(big, i), 5, 8);
      assertTrue(Long.parseLong(value(unsigned, i)) >= 1);
      double d = Double.parseDouble(value(decimal, i));
      assertTrue(value(decimal, i), d >= 2 && d < 9);
      assertDate(value(date, i), "2001-01-01 00:00:00.001", "2001-12-31 23:59:59.998");
      assertDate(value(timestamp, i), "1999-01-01 00:00:00.001", "2000-12-31 23:59:59.998");
      assertEquals(6, Bytes.fromHex(value(encoded, i)).length);
      assertArray(value(floats, i), 3, -2, 2);
      assertArray(value(doubles, i), 2, 10, 20);
      assertDocument(value(json, i), 4, "j");
      assertDocument(value(bson, i), 3, "");
    }
    for (int seen : intSeen) {
      assertTrue(Arrays.toString(intSeen), seen > 0);
    }
    Column equal = bounded(DataTypeMapping.BIGINT, 7, 7);
    assertEquals("7", value(equal, 3));
  }

  /**
   * Verifies that configured nullChance probabilities of 0, 10, and 100 produce null frequencies
   * matching their expected rates within the same absolute tolerance of 0.01.
   */
  @Test
  public void testNullChance() throws Exception {
    for (int chance : new int[] { 0, 10, 100 }) {
      Column rule = random(DataTypeMapping.VARCHAR);
      rule.setLength(3);
      rule.setNullChance(chance);
      int nulls = 0;
      for (long i = 0; i < N; i++) {
        if (IndexedRule.isNull(rule, i, SEED)) {
          nulls++;
        }
      }
      assertEquals("nullChance " + chance, chance / 100.0, nulls / (double) N, TOLERANCE);
    }
    assertFalse(IndexedRule.isNull(random(DataTypeMapping.VARCHAR), 1, SEED));
  }

  /**
   * Verifies that any rule configuration that cannot be evaluated as a pure function of the row
   * index, or that is otherwise invalid on the standard rule evaluation path, is rejected.
   */
  @Test
  public void testRejects() throws Exception {
    Column current = bounded(DataTypeMapping.DATE, 2000, 2001);
    current.setUseCurrentDate(true);
    assertRejected(current, "row index");
    Column currentEntry = rule(DataTypeMapping.DATE, DataSequence.LIST);
    DataValue now = new DataValue(DataTypeMapping.DATE, null);
    now.setUseCurrentDate(true);
    now.setDistribution(0);
    currentEntry.setDataValues(new ArrayList<>(Arrays.asList(now)));
    assertRejected(currentEntry, "row index");
    for (DataTypeMapping type : new DataTypeMapping[] { DataTypeMapping.FLOAT_ARRAY,
      DataTypeMapping.DOUBLE_ARRAY, DataTypeMapping.VARBINARY_ENCODED }) {
      Column sequential = bounded(type, 0, 1);
      sequential.setDataSequence(DataSequence.SEQUENTIAL);
      sequential.setLength(2);
      assertRejected(sequential, type.toString());
    }
    Column document = rule(DataTypeMapping.JSON, DataSequence.SEQUENTIAL);
    document.setLength(3);
    assertRejected(document, "value list");
    assertRejected(random(DataTypeMapping.BOOLEAN), "BOOLEAN");
    Column weights = rule(DataTypeMapping.VARCHAR, DataSequence.LIST);
    weights.setDataValues(values(DataTypeMapping.VARCHAR, "a", "b"));
    weights.getDataValues().get(0).setDistribution(30);
    weights.getDataValues().get(1).setDistribution(30);
    assertRejected(weights, "add up to 100");
    Column backwards = bounded(DataTypeMapping.INTEGER, 5, 4);
    assertRejected(backwards, "maxValue");
  }

  private static void assertRejected(Column rule, String text) {
    try {
      new IndexedRule(rule);
      fail("accepted " + rule.getType() + " " + rule.getDataSequence());
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(text));
    }
  }

  private static void assertFrequencies(int[] weights) {
    Column rule = rule(DataTypeMapping.VARCHAR, DataSequence.LIST);
    List<DataValue> list = new ArrayList<>();
    int sum = 0;
    for (int i = 0; i < weights.length; i++) {
      DataValue value = new DataValue(DataTypeMapping.VARCHAR, "v" + i);
      value.setDistribution(weights[i]);
      list.add(value);
      sum += weights[i];
    }
    rule.setDataValues(list);
    IndexedRule indexed = new IndexedRule(rule);
    int[] counts = new int[weights.length];
    for (long i = 0; i < N; i++) {
      counts[Integer.parseInt(indexed.valueAt(i, SEED).getValue().substring(1))]++;
    }
    for (int i = 0; i < weights.length; i++) {
      double want = sum == 0 ? 1.0 / weights.length : weights[i] / 100.0;
      assertEquals(Arrays.toString(weights) + " entry " + i, want, counts[i] / (double) N,
        TOLERANCE);
      if (sum != 0 && weights[i] == 0) {
        assertEquals(0, counts[i]);
      }
    }
  }

  private static void assertSequence(Column rule, RuleBasedDataGenerator generator, long from,
    int count) {
    for (long i = 0; i < from; i++) {
      generator.getDataValue();
    }
    IndexedRule indexed = new IndexedRule(rule);
    for (long i = from; i < from + count; i++) {
      assertEquals("index " + i, generator.getDataValue().getValue(),
        indexed.valueAt(i, SEED).getValue());
    }
  }

  private static void assertText(String value, int length, String prefix) {
    assertEquals(value, length, value.length());
    assertTrue(value, value.startsWith(prefix));
    assertTrue(value, value.substring(prefix.length()).matches("[0-9A-Za-z]*"));
  }

  private static void assertBetween(String value, long min, long max) {
    long v = Long.parseLong(value);
    assertTrue(value, v >= min && v <= max);
  }

  private static void assertDate(String value, String min, String max) {
    Instant v = ZonedDateTime.parse(value, UTC).toInstant();
    assertFalse(value, v.isBefore(ZonedDateTime.parse(min, UTC).toInstant()));
    assertFalse(value, v.isAfter(ZonedDateTime.parse(max, UTC).toInstant()));
  }

  private static void assertArray(String value, int length, double min, double max) {
    String[] tokens = value.split(",");
    assertEquals(value, length, tokens.length);
    for (String token : tokens) {
      double v = Double.parseDouble(token);
      assertTrue(value, v >= min && v <= max);
    }
  }

  private static void assertDocument(String text, int length, String prefix) {
    JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
    assertEquals(Arrays.asList("s", "n", "d", "b", "a", "o"), new ArrayList<>(doc.keySet()));
    assertText(doc.get("s").getAsString(), length, prefix);
    int n = doc.get("n").getAsInt();
    assertTrue(text, n >= 0 && n < 1000000);
    double d = doc.get("d").getAsDouble();
    assertTrue(text, d >= 0 && d < 1000);
    doc.get("b").getAsBoolean();
    JsonArray a = doc.get("a").getAsJsonArray();
    assertEquals(3, a.size());
    for (int i = 0; i < 3; i++) {
      assertTrue(text, a.get(i).getAsInt() >= 0 && a.get(i).getAsInt() < 1000);
    }
    JsonObject o = doc.get("o").getAsJsonObject();
    assertText(o.get("s").getAsString(), length, "");
    assertNotEquals(-1, o.get("n").getAsInt());
  }

  private static String value(Column rule, long index) {
    return new IndexedRule(rule).valueAt(index, SEED).getValue();
  }

  private static Column rule(DataTypeMapping type, DataSequence sequence) {
    Column rule = new Column();
    rule.setName("C");
    rule.setType(type);
    rule.setDataSequence(sequence);
    return rule;
  }

  private static Column random(DataTypeMapping type) {
    return rule(type, DataSequence.RANDOM);
  }

  private static Column bounded(DataTypeMapping type, long min, long max) {
    Column rule = random(type);
    rule.setMinValue(min);
    rule.setMaxValue(max);
    return rule;
  }

  private static List<DataValue> values(DataTypeMapping type, String... texts) {
    List<DataValue> list = new ArrayList<>();
    for (String text : texts) {
      DataValue value = new DataValue(type, text);
      value.setDistribution(0);
      list.add(value);
    }
    return list;
  }

  private static List<Column> goldenColumns() {
    List<Column> columns = new ArrayList<>();
    for (int i = 0; i < GOLDEN_COLUMNS.length; i += 2) {
      columns.add(
        CoGenFixtures.column(GOLDEN_COLUMNS[i], DataTypeMapping.valueOf(GOLDEN_COLUMNS[i + 1])));
    }
    return columns;
  }
}
