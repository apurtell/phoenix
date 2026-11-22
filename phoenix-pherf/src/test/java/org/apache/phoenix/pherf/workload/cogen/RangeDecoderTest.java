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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.phoenix.pherf.configuration.CoGeneration;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.IndexedRule;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.junit.Test;

/**
 * Verifies RQ-188: the decoder populates a range statement with the boundary values of a single
 * drawn block. For every block k and every written local index i, the value bound by the writer
 * falls within the bounds of block k if and only if i belongs to block k. Any range column that is
 * not the leading key, any range column whose values do not increase with the index, and any other
 * placeholder associated with a rule are rejected before a block is drawn.
 */
public class RangeDecoderTest {
  private static final String FIXTURE = "/cogen/cogen_range.xml";
  private static final String SCENARIO = "cogenRange";

  /**
   * Builds a decoder for the range fixture configured with the given row count and block settings.
   */
  private static IndexDecoder decoder(int rows, int blockSize, Integer clusterLimit, long offset,
    String leadingKey) throws Exception {
    XMLConfigParser parser = new XMLConfigParser(CoGenFixtures.path(FIXTURE));
    Scenario scenario = CoGenFixtures.scenario(parser, SCENARIO);
    scenario.setRowCount(rows);
    CoGeneration element = scenario.getCoGeneration();
    element.setBlockSize(String.valueOf(blockSize));
    element.setClusterLimit(clusterLimit == null ? null : String.valueOf(clusterLimit));
    element.setOffset(String.valueOf(offset));
    return new IndexDecoder(new RulesApplier(parser), scenario, CoGenerator.Settings.of(scenario),
      leadingKey);
  }

  /**
   * Converts a SQL literal or a writer value into a numeric value so that values can be compared
   * according to SQL ordering semantics.
   */
  private static long number(DataTypeMapping type, String text) throws Exception {
    if (type == DataTypeMapping.DATE) {
      String date = text.startsWith("DATE '") ? text.substring(6, text.length() - 1) : text;
      return IndexDecoder.dateFormat().parse(date).getTime();
    }
    return Long.parseLong(text);
  }

  /**
   * Verifies that the decoded bounds for each block bracket the exact set of rows belonging to that
   * block, and returns the maximum number of distinct range values observed within a single block.
   */
  private static int assertBrackets(IndexDecoder decoder, String name, DataTypeMapping type,
    int rows, int blockSize) throws Exception {
    String sql =
      "SELECT V FROM T WHERE " + name + " >= [" + name + "] AND " + name + " <= [" + name + "]";
    Pattern bounds =
      Pattern.compile(Pattern.quote(name) + " >= (.+?) AND " + Pattern.quote(name) + " <= (.+?)$");
    Column column = CoGenFixtures.column(name, type);
    int blocks = (rows + blockSize - 1) / blockSize;
    int widest = 0;
    for (int k = 0; k < blocks; k++) {
      final RowCursor.Block block =
        new RowCursor.Block((long) k * blockSize, Math.min((long) (k + 1) * blockSize, rows));
      AtomicInteger draws = new AtomicInteger();
      String text = decoder.range(sql, name, rows, () -> {
        draws.incrementAndGet();
        return block;
      });
      assertEquals(1, draws.get());
      assertFalse(text, text.contains("["));
      Matcher m = bounds.matcher(text);
      assertTrue(text, m.find());
      long lo = number(type, m.group(1));
      long hi = number(type, m.group(2));
      Set<Long> distinct = new HashSet<>();
      for (long i = 0; i < rows; i++) {
        long value = number(type, decoder.value(column, i).getValue());
        boolean inBlock = i / blockSize == k;
        assertEquals(name + " block " + k + " index " + i + ": " + text, inBlock,
          lo <= value && value <= hi);
        if (inBlock) {
          distinct.add(value);
        }
      }
      widest = Math.max(widest, distinct.size());
    }
    return widest;
  }

  @Test
  public void testUnclusteredBlocks() throws Exception {
    IndexDecoder decoder = decoder(100, 20, null, 0, "K1");
    assertEquals(20, assertBrackets(decoder, "K1", DataTypeMapping.INTEGER, 100, 20));
  }

  /**
   * Verifies that when B = 20 and L = 7 do not divide evenly, the leading key spans at most 7
   * values within each block.
   */
  @Test
  public void testClusteredBlocks() throws Exception {
    IndexDecoder decoder = decoder(100, 20, 7, 0, "K1");
    assertEquals(7, assertBrackets(decoder, "K1", DataTypeMapping.INTEGER, 100, 20));
  }

  @Test
  public void testOffset() throws Exception {
    IndexDecoder decoder = decoder(100, 20, 7, 1000, "K1");
    assertEquals(7, assertBrackets(decoder, "K1", DataTypeMapping.INTEGER, 100, 20));
    String text =
      decoder.range("K1 >= [K1] AND K1 <= [K1]", "K1", 100, () -> new RowCursor.Block(0, 20));
    assertEquals("K1 >= 1000 AND K1 <= 1006", text);
  }

  /**
   * Verifies that 95 rows produce a final block of 15 rows, and that the decoded bounds bracket
   * that partial block accurately.
   */
  @Test
  public void testPartialLastBlock() throws Exception {
    IndexDecoder decoder = decoder(95, 20, 7, 0, "K1");
    assertBrackets(decoder, "K1", DataTypeMapping.INTEGER, 95, 20);
    assertEquals("K1 >= 28 AND K1 <= 32",
      decoder.range("K1 >= [K1] AND K1 <= [K1]", "K1", 95, () -> new RowCursor.Block(80, 95)));
  }

  @Test
  public void testBigintLeadingColumn() throws Exception {
    IndexDecoder decoder = decoder(100, 20, 7, 0, "KB");
    assertEquals(7, assertBrackets(decoder, "KB", DataTypeMapping.BIGINT, 100, 20));
  }

  /**
   * Verifies that a range column that is not the leading key cannot form a valid key range and is
   * therefore rejected.
   */
  @Test
  public void testNonLeadingColumnRejected() throws Exception {
    IndexDecoder decoder = decoder(100, 20, 7, 0, "K1");
    assertRejected(decoder, "K1", 100, "K2 >= [K2] AND K2 <= [K2]", "K2", "leading", "K1");
  }

  @Test
  public void testDateColumn() throws Exception {
    IndexDecoder decoder = decoder(100, 20, 7, 0, "D");
    assertEquals(7, assertBrackets(decoder, "D", DataTypeMapping.DATE, 100, 20));
  }

  /**
   * Verifies that every rejected range query identifies the scenario and the offending column in
   * the exception message without drawing a block.
   */
  private static void assertRejected(IndexDecoder decoder, String leadingKey, long indexes,
    String sql, String... names) {
    AtomicInteger draws = new AtomicInteger();
    try {
      decoder.range(sql, leadingKey, indexes, () -> {
        draws.incrementAndGet();
        return new RowCursor.Block(0, 20);
      });
      fail("accepted " + sql);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(SCENARIO));
      for (String name : names) {
        assertTrue(e.getMessage(), e.getMessage().contains(name));
      }
    }
    assertEquals(0, draws.get());
  }

  @Test
  public void testRejectedRangeColumns() throws Exception {
    // Each rule check below configures the target column as the leading key without clustering.
    // The x-padded counter produced by a SEQUENTIAL VARCHAR rule does not preserve lexicographical
    // string ordering.
    assertRejected(decoder(95, 20, null, 0, "VS"), "VS", 95, "VS >= [VS] AND VS <= [VS]", "VS");
    // A range of 0 .. 49 wraps around within a 95-row dataset.
    assertRejected(decoder(95, 20, null, 0, "W"), "W", 95, "W >= [W] AND W <= [W]", "W");
    assertRejected(decoder(95, 20, null, 0, "N"), "N", 95, "N >= [N] AND N <= [N]", "N",
      "nullChance");
    assertRejected(decoder(95, 20, null, 0, "R"), "R", 95, "R >= [R] AND R <= [R]", "R");
    // Statements that do not conform to the required two-bound range shape are rejected.
    IndexDecoder decoder = decoder(95, 20, 7, 0, "K1");
    assertRejected(decoder, "K1", 95, "K1 = [K1] AND K2 = [K2]");
    assertRejected(decoder, "K1", 95, "K1 >= [K1] AND K1 <= [K1] AND K1 <> [K1]", "K1");
    assertRejected(decoder, "K1", 95, "K1 >= [K1] AND K1 <= [K1] AND K2 >= [K2] AND K2 <= [K2]",
      "K1", "K2");
    // Including another placeholder backed by a rule introduces a filter predicate whose value
    // varies across the rows within a block.
    assertRejected(decoder, "K1", 95, "K1 >= [K1] AND K1 <= [K1] AND V = [V]", "V");
    assertRejected(decoder, "K1", 95,
      "K1 >= [K1] AND K1 <= [K1] AND V = [V] AND V <> [V] AND V > [V]", "V");
    // While 50 rows of W fit within 0 .. 49 without wrapping, additional writeParams rows starting
    // at index 50 cause W to wrap around.
    IndexDecoder w = decoder(50, 20, null, 0, "W");
    assertEquals("W >= 0 AND W <= 19",
      w.range("W >= [W] AND W <= [W]", "W", 50, () -> new RowCursor.Block(0, 20)));
    assertRejected(w, "W", 51, "W >= [W] AND W <= [W]", "W", "global index 50");
  }

  /**
   * Verifies the exact monotonicity boundary of a SEQUENTIAL integer rule, where min 0 and max 49
   * yield 50 strictly increasing values before wrapping.
   */
  @Test
  public void testIncreasingLimit() throws Exception {
    Column w = new Column();
    w.setName("W");
    w.setType(DataTypeMapping.INTEGER);
    w.setDataSequence(org.apache.phoenix.pherf.configuration.DataSequence.SEQUENTIAL);
    w.setMinValue(0);
    w.setMaxValue(49);
    assertTrue(new IndexedRule(w).increasing(49));
    assertFalse(new IndexedRule(w).increasing(50));
    w.setMinValue(Long.MIN_VALUE);
    w.setMaxValue(Long.MAX_VALUE);
    assertFalse(new IndexedRule(w).increasing(1));
    Column d = new Column();
    d.setName("D");
    d.setType(DataTypeMapping.TIMESTAMP);
    d.setDataSequence(org.apache.phoenix.pherf.configuration.DataSequence.SEQUENTIAL);
    assertTrue(new IndexedRule(d).increasing(Integer.MAX_VALUE));
    Column r = new Column();
    r.setName("R");
    r.setType(DataTypeMapping.INTEGER);
    r.setMinValue(0);
    r.setMaxValue(10);
    assertFalse(new IndexedRule(r).increasing(1));
  }
}
