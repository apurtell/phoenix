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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.pherf.configuration.CoGeneration;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.util.DateUtil;
import org.junit.Test;

/**
 * Verifies requirements RQ-175, RQ-180, RQ-184, and RQ-186, ensuring that the decoder
 * deterministically derives both the writer row and the reader lookup keys from the row index
 * alone.
 */
public class IndexDecoderTest {
  private static final Pattern KEYS = Pattern.compile("K1 = '([^']*)' AND K2 = (-?\\d+)");
  private static final int N = 500;

  /**
   * Verifies RQ-175: a writer decoder instantiated from one configuration parse binds rows over the
   * interval [0, N), while an independent reader decoder instantiated from a separate parse shares
   * only the committed watermark N and executes a single traversal pass. For every read in the
   * pass, the generated query keys match the primary key bound by the writer for the drawn index.
   */
  @Test
  public void testReaderReproducesWriterKeys() throws Exception {
    IndexDecoder writer =
      CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null);
    List<Column> columns = CoGenFixtures.tableColumns();
    List<List<Object>> written = new ArrayList<>();
    for (long i = 0; i < N; i++) {
      written.add(CoGenFixtures.bind(columns, writer.row(columns, i)));
    }

    XMLConfigParser readerParser = CoGenFixtures.parser();
    IndexDecoder reader = CoGenFixtures.decoder(readerParser, CoGenFixtures.DEFAULTS, null);
    RowCursor cursor = new RowCursor(1000, N);
    Walk walk =
      new Walk(IndexHash.columnSeed(0, "#walk"), CoGeneration.WalkStrategy.MULTIPLICATIVE, 0, 1);
    Set<Long> visited = new HashSet<>();
    for (int k = 0; k < N; k++) {
      final long[] drawn = new long[1];
      String sql = reader.statement(CoGenFixtures.LOOKUP, () -> {
        drawn[0] = walk.next(cursor.watermark());
        return drawn[0];
      });
      assertTrue(visited.add(drawn[0]));
      Matcher keys = KEYS.matcher(sql);
      assertTrue(sql, keys.find());
      List<Object> row = written.get((int) drawn[0]);
      assertEquals(sql, row.get(0), keys.group(1));
      assertEquals(sql, row.get(1), Integer.valueOf(keys.group(2)));
    }
    assertEquals(N, visited.size());
  }

  /**
   * Verifies RQ-180: all placeholders within a point query are resolved from a single index draw.
   * Each substituted literal matches both the decoder value for its column at the drawn index and
   * the value bound by the writer for that index, whereas a statement containing no matching rule
   * placeholder performs no draw.
   */
  @Test
  public void testPointQueryBindsFromOneIndex() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    IndexDecoder decoder = CoGenFixtures.decoder(parser, CoGenFixtures.DEFAULTS, null);
    List<Column> columns = CoGenFixtures.tableColumns();
    String sql = "SELECT V FROM PHERF.COGEN WHERE K1 = [K1] AND K2 = [K2] AND V = [V]";
    Pattern literals = Pattern.compile("K1 = '([^']*)' AND K2 = (\\d+) AND V = '([^']*)'");
    for (long index : new long[] { 0, 1, 17, 499, 123456 }) {
      AtomicInteger calls = new AtomicInteger();
      String text = decoder.statement(sql, () -> {
        calls.incrementAndGet();
        return index;
      });
      assertEquals(1, calls.get());
      Matcher m = literals.matcher(text);
      assertTrue(text, m.find());
      for (int c = 0; c < 3; c++) {
        assertEquals(text, decoder.value(columns.get(c), index).getValue(), m.group(c + 1));
      }
      List<Object> bound = CoGenFixtures.bind(columns, CoGenFixtures
        .decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null).row(columns, index));
      assertEquals(Arrays.asList(m.group(1), Integer.valueOf(m.group(2)), m.group(3)), bound);
    }
    AtomicInteger calls = new AtomicInteger();
    String plain = "SELECT COUNT(*) FROM PHERF.COGEN WHERE X = [NO_SUCH_RULE]";
    assertEquals(plain, decoder.statement(plain, () -> {
      calls.incrementAndGet();
      return 0;
    }));
    assertEquals(0, calls.get());
  }

  /**
   * Verifies RQ-184: when clustering is enabled, the leading key takes at most clusterLimit
   * distinct values within each block, including blocks that do not start at a multiple of the
   * block size, while the composite primary key remains unique. When clustering is disabled, a
   * SEQUENTIAL leading key produces one distinct value for each row of the block.
   */
  @Test
  public void testClusteringBoundsLeadingKeys() throws Exception {
    List<Column> columns = CoGenFixtures.tableColumns();
    for (String leading : new String[] { "K1", "K2" }) {
      XMLConfigParser parser = CoGenFixtures.parser();
      CoGeneration settings =
        CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS).getCoGeneration();
      settings.setBlockSize("100");
      settings.setClusterLimit("4");
      IndexDecoder clustered = CoGenFixtures.decoder(parser, CoGenFixtures.DEFAULTS, leading);
      XMLConfigParser plainParser = CoGenFixtures.parser();
      CoGenFixtures.scenario(plainParser, CoGenFixtures.DEFAULTS).getCoGeneration()
        .setBlockSize("100");
      IndexDecoder plain = CoGenFixtures.decoder(plainParser, CoGenFixtures.DEFAULTS, leading);
      int column = leading.equals("K1") ? 0 : 1;
      Set<List<Object>> keys = new HashSet<>();
      RowCursor cursor = new RowCursor(100, 37);
      Random random = new Random(5);
      for (int b = 0; b < 40; b++) {
        RowCursor.Block block = cursor.claim(1 + random.nextInt(150));
        Set<Object> clusteredValues = new HashSet<>();
        Set<Object> plainValues = new HashSet<>();
        for (long i = block.start; i < block.end; i++) {
          List<Object> row = CoGenFixtures.bind(columns, clustered.row(columns, i));
          clusteredValues.add(row.get(column));
          plainValues.add(CoGenFixtures.bind(columns, plain.row(columns, i)).get(column));
          if (leading.equals("K1")) {
            assertTrue("duplicate key at " + i, keys.add(row.subList(0, 2)));
          }
        }
        assertTrue(leading + " " + clusteredValues, clusteredValues.size() <= 4);
        if (leading.equals("K2")) {
          assertEquals(block.size(), plainValues.size());
        }
      }
    }
  }

  /**
   * Verifies RQ-186: two generators configured with offsets 0 and 1000 over the local row range [0,
   * 1000) produce completely disjoint primary key sets, whereas generators sharing equal offsets
   * produce overlapping keys as a negative control.
   */
  @Test
  public void testOffsetsGiveDisjointKeys() throws Exception {
    Set<List<Object>> zero = keys(0);
    Set<List<Object>> thousand = keys(1000);
    assertEquals(1000, zero.size());
    assertEquals(1000, thousand.size());
    Set<List<Object>> common = new HashSet<>(zero);
    common.retainAll(thousand);
    assertTrue(common.toString(), common.isEmpty());
    Set<List<Object>> same = new HashSet<>(zero);
    same.retainAll(keys(0));
    assertEquals(1000, same.size());
  }

  private static Set<List<Object>> keys(long offset) throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS).getCoGeneration()
      .setOffset(String.valueOf(offset));
    IndexDecoder decoder = CoGenFixtures.decoder(parser, CoGenFixtures.DEFAULTS, null);
    List<Column> columns = CoGenFixtures.tableColumns();
    Set<List<Object>> keys = new HashSet<>();
    for (long i = 0; i < 1000; i++) {
      keys.add(CoGenFixtures.bind(columns, decoder.row(columns, i)).subList(0, 2));
    }
    return keys;
  }

  /**
   * Verifies RQ-180 across every key data type: each formatted statement literal, when parsed the
   * way Phoenix interprets SQL literals, equals the value that the writer binds for the same row
   * index. CHAR and VARCHAR values are enclosed in quotes, numeric values are unquoted, DATE and
   * TIMESTAMP values are emitted as typed literals that Phoenix parses in GMT, and binary values
   * are formatted as hexadecimal literals. Executing under a non-UTC JVM time zone ensures that any
   * binding evaluated in the local time zone fails, while also confirming that the millisecond
   * precision of RANDOM dates is preserved.
   */
  @Test
  public void testLiteralsEqualWriterBinding() throws Exception {
    TimeZone zone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
    try {
      IndexDecoder decoder =
        CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.DEFAULTS, null);
      Object[][] keys = { { "K1", DataTypeMapping.VARCHAR }, { "T_CHAR", DataTypeMapping.CHAR },
        { "K2", DataTypeMapping.INTEGER }, { "T_TINY", DataTypeMapping.TINYINT },
        { "T_BIG", DataTypeMapping.BIGINT }, { "T_ULONG", DataTypeMapping.UNSIGNED_LONG },
        { "T_DEC", DataTypeMapping.DECIMAL }, { "T_DATE", DataTypeMapping.DATE },
        { "T_DLIST", DataTypeMapping.DATE }, { "T_TS", DataTypeMapping.TIMESTAMP },
        { "T_VARBINARY", DataTypeMapping.VARBINARY },
        { "T_VBE", DataTypeMapping.VARBINARY_ENCODED } };
      boolean millis = false;
      for (Object[] key : keys) {
        String name = (String) key[0];
        DataTypeMapping type = (DataTypeMapping) key[1];
        List<Column> columns = Collections.singletonList(CoGenFixtures.column(name, type));
        for (long index = 0; index < 50; index++) {
          final long drawn = index;
          String literal = decoder.statement("[" + name + "]", () -> drawn);
          Object bound = CoGenFixtures.bind(columns, decoder.row(columns, index)).get(0);
          String where = name + " " + index + ": " + literal;
          switch (type) {
            case CHAR:
            case VARCHAR:
              assertEquals(where, "'" + bound + "'", literal);
              break;
            case DECIMAL:
              assertEquals(where, bound, new BigDecimal(literal));
              break;
            case DATE:
              Date date = DateUtil.parseDate(text(literal, "DATE '"));
              assertEquals(where, ((Date) bound).getTime(), date.getTime());
              millis |= date.getTime() % 1000 != 0;
              break;
            case TIMESTAMP:
              assertEquals(where, bound, DateUtil.parseTimestamp(text(literal, "TIMESTAMP '")));
              break;
            case VARBINARY:
            case VARBINARY_ENCODED:
              assertArrayEquals(where, (byte[]) bound, Bytes.fromHex(text(literal, "X'")));
              break;
            default:
              assertEquals(where, ((Number) bound).longValue(), Long.parseLong(literal));
          }
        }
      }
      assertTrue("no RANDOM date has milliseconds", millis);
    } finally {
      TimeZone.setDefault(zone);
    }
  }

  /** Extracts the inner text of a literal between its opening prefix and its closing quote. */
  private static String text(String literal, String opening) {
    assertTrue(literal, literal.startsWith(opening) && literal.endsWith("'"));
    return literal.substring(opening.length(), literal.length() - 1);
  }
}
