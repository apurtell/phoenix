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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.Query.Kind;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.junit.Test;

/**
 * Verifies the delete watermark semantics, where deleters remove committed blocks in commit order
 * with each block claimed once, while point reads and scans draw only from rows above the deleted
 * prefix. When no deletes occur, the draws follow the walk sequence directly.
 */
public class DeleteWatermarkTest {
  private static final String FIXTURE = "/cogen/cogen_range.xml";
  private static final String SCENARIO = "cogenRange";
  private static final String POINT =
    "SELECT V FROM PHERF.COGEN_RANGE WHERE K1 = [K1] AND K2 = [K2]";
  private static final String SCAN =
    "SELECT K2 FROM PHERF.COGEN_RANGE WHERE K1 >= [K1] AND K1 <= [K1]";
  private static final String DELETE =
    "DELETE FROM PHERF.COGEN_RANGE WHERE K1 >= [K1] AND K1 <= [K1]";
  private static final Pattern INDEX = Pattern.compile("K2 = (\\d+)");
  private static final Pattern BOUNDS = Pattern.compile("K1 >= (\\d+) AND K1 <= (\\d+)");
  // The test fixture defines 20 rows organized into blocks of 5 with seed 11, no offset, and no
  // clustering.
  private static final int ROWS = 20;
  private static final int BLOCK = 5;
  // Mocked table metadata configured with a composite primary key of (K1, K2).
  private static final Connection KEYED;

  static {
    try {
      KEYED = CoGenFixtures.keyedConnection("K1", "K2");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void assertBlock(RowCursor.Block block, long start, long end) {
    assertEquals(start, block.start);
    assertEquals(end, block.end);
  }

  /**
   * Claims and commits blocks of at most the configured block size until the committed watermark
   * reaches n.
   */
  private static void commitTo(RowCursor cursor, long n) {
    while (cursor.watermark() < n) {
      cursor.commit(cursor.claim(n - cursor.highWater()));
    }
    assertEquals(n, cursor.watermark());
  }

  private static CoGenerator generator(int rows) throws Exception {
    XMLConfigParser parser = new XMLConfigParser(CoGenFixtures.path(FIXTURE));
    Scenario scenario = CoGenFixtures.scenario(parser, SCENARIO);
    scenario.setRowCount(rows);
    return CoGenerator.of(parser, scenario);
  }

  private static long index(String sql) {
    Matcher m = INDEX.matcher(sql);
    assertTrue(sql, m.find());
    return Long.parseLong(m.group(1));
  }

  /**
   * Extracts the lower and upper range bounds from a range statement using the K1 values of its
   * first and last row.
   */
  private static long[] bounds(String sql) {
    Matcher m = BOUNDS.matcher(sql);
    assertTrue(sql, m.find());
    return new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) };
  }

  private static long window(String sql) {
    long[] b = bounds(sql);
    assertEquals(sql, b[0] / BLOCK, b[1] / BLOCK);
    return b[0] / BLOCK;
  }

  private static void assertNoRow(CoGenerator.Reader reader, Kind kind, String sql, String message)
    throws Exception {
    try {
      reader.statement(kind, sql, KEYED);
      fail(kind + " drew a row: " + sql);
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(SCENARIO));
      assertTrue(e.getMessage(), e.getMessage().contains(message));
    }
  }

  /**
   * Verifies that blocks are claimed for deletion in sequential commit order, and that a block is
   * deleted only after all of its constituent rows have been committed.
   */
  @Test
  public void testClaimsInCommitOrder() {
    RowCursor cursor = new RowCursor(10, 0);
    commitTo(cursor, 30);
    assertBlock(cursor.claimDelete(35), 0, 10);
    assertBlock(cursor.claimDelete(35), 10, 20);
    assertBlock(cursor.claimDelete(35), 20, 30);
    // The trailing block [30, 35) has not been committed yet and cannot be claimed for deletion.
    assertNull(cursor.claimDelete(35));
    assertEquals(30, cursor.deleted());
    commitTo(cursor, 35);
    assertBlock(cursor.claimDelete(35), 30, 35);
    assertNull(cursor.claimDelete(35));
    assertEquals(35, cursor.deleted());

    RowCursor half = new RowCursor(10, 0);
    commitTo(half, 15);
    assertBlock(half.claimDelete(100), 0, 10);
    assertNull(half.claimDelete(100));
    assertEquals(10, half.deleted());
  }

  @Test
  public void testConcurrentDeletersAreDisjoint() throws Exception {
    final RowCursor cursor = new RowCursor(10, 1000);
    final List<RowCursor.Block> claimed = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      Thread thread = new Thread(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          return;
        }
        RowCursor.Block block;
        while ((block = cursor.claimDelete(1000)) != null) {
          claimed.add(block);
        }
      });
      thread.start();
      threads.add(thread);
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    List<RowCursor.Block> blocks = new ArrayList<>(claimed);
    blocks.sort(Comparator.comparingLong(b -> b.start));
    assertEquals(100, blocks.size());
    long next = 0;
    for (RowCursor.Block block : blocks) {
      assertBlock(block, next, next + 10);
      next = block.end;
    }
    assertEquals(1000, cursor.deleted());
  }

  /**
   * Verifies that after one block is deleted, a single pass of the point reader visits each live
   * row in [B, C) exactly once, and a single scan pass visits blocks 1 to 3 once. The deleter
   * claims the remaining blocks in order, and once all blocks are deleted, every query kind fails
   * with a descriptive message.
   */
  @Test
  public void testReadsSkipTheDeletedRows() throws Exception {
    CoGenerator generator = generator(ROWS);
    CoGenerator.Reader deleter = generator.reader(0, 1);
    assertEquals(0, window(deleter.statement(Kind.DELETE, DELETE, KEYED)));
    assertEquals(BLOCK, generator.cursor().deleted());

    CoGenerator.Reader points = generator.reader(0, 1);
    Set<Long> visited = new HashSet<>();
    for (int k = 0; k < ROWS - BLOCK; k++) {
      long i = index(points.statement(Kind.SELECT, POINT, KEYED));
      assertTrue("index " + i, i >= BLOCK && i < ROWS);
      assertTrue("index " + i + " twice", visited.add(i));
    }
    assertEquals(ROWS - BLOCK, visited.size());

    CoGenerator.Reader scans = generator.reader(0, 1);
    Set<Long> windows = new HashSet<>();
    for (int k = 0; k < 3; k++) {
      assertTrue(windows.add(window(scans.statement(Kind.SCAN, SCAN, KEYED))));
    }
    assertEquals(new HashSet<>(java.util.Arrays.asList(1L, 2L, 3L)), windows);

    for (long k = 1; k < 4; k++) {
      long[] b = bounds(deleter.statement(Kind.DELETE, DELETE, KEYED));
      assertEquals(k * BLOCK, b[0]);
      assertEquals(k * BLOCK + BLOCK - 1, b[1]);
    }
    assertEquals(ROWS, generator.cursor().deleted());
    assertNoRow(deleter, Kind.DELETE, DELETE, "no committed block is left to delete");
    assertNoRow(points, Kind.SELECT, POINT, "no committed row");
    assertNoRow(scans, Kind.SCAN, SCAN, "no committed block");
  }

  /**
   * Verifies that a worker whose assigned partition share contains only deleted rows fails, while
   * workers with live rows in their share continue reading.
   */
  @Test
  public void testEmptyShareFails() throws Exception {
    CoGenerator generator = generator(ROWS);
    for (int k = 0; k < 3; k++) {
      generator.cursor().claimDelete(ROWS);
    }
    // Only rows 15 .. 19 remain live, so out of 8 workers, workers 7 and 0 .. 3 read them.
    assertEquals(15, index(generator.reader(7, 8).statement(Kind.SELECT, POINT, KEYED)));
    assertEquals(16, index(generator.reader(0, 8).statement(Kind.SELECT, POINT, KEYED)));
    assertNoRow(generator.reader(4, 8), Kind.SELECT, POINT, "worker 4 of 8");
    // Only block 3 remains live, so out of 8 workers, only worker 3 can scan it.
    assertEquals(3, window(generator.reader(3, 8).statement(Kind.SCAN, SCAN, KEYED)));
    assertNoRow(generator.reader(0, 8), Kind.SCAN, SCAN, "worker 0 of 8");
  }

  @Test
  public void testResetClearsTheDeletes() {
    RowCursor cursor = new RowCursor(10, 100);
    cursor.claimDelete(100);
    cursor.claimDelete(100);
    assertEquals(20, cursor.deleted());
    cursor.reset(0);
    assertEquals(0, cursor.deleted());
    assertNull(cursor.claimDelete(100));
    cursor.reset(100);
    assertBlock(cursor.claimDelete(100), 0, 10);
  }

  /**
   * Verifies that the range rule does not wrap across rows added above the initial row count by
   * writeParams writers. Column K1 configured with maxValue 24 provides 25 distinct values: a scan
   * over the initial 20 rows succeeds, but once writers claim indexes up to 30, a DELETE operation
   * fails prior to drawing and claims no block.
   */
  @Test
  public void testWrittenRowsMustNotWrap() throws Exception {
    XMLConfigParser parser = new XMLConfigParser(CoGenFixtures.path(FIXTURE));
    for (Column column : parser.getDataModels().get(0).getDataMappingColumns()) {
      if (column.getName().equals("K1")) {
        column.setMaxValue(24);
      }
    }
    Scenario scenario = CoGenFixtures.scenario(parser, SCENARIO);
    scenario.setRowCount(ROWS);
    CoGenerator generator = CoGenerator.of(parser, scenario);
    CoGenerator.Reader reader = generator.reader(0, 1);
    window(reader.statement(Kind.SCAN, SCAN, KEYED));
    RowCursor cursor = generator.cursor();
    while (cursor.highWater() < 30) {
      cursor.claim(30 - cursor.highWater());
    }
    assertEquals(30, cursor.highWater());
    try {
      reader.statement(Kind.DELETE, DELETE, KEYED);
      fail("accepted a range column that wraps over the written rows");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(SCENARIO));
      assertTrue(e.getMessage(), e.getMessage().contains("K1"));
      assertTrue(e.getMessage(), e.getMessage().contains("global index 29"));
    }
    assertEquals(0, cursor.deleted());
  }

  /**
   * Verifies that when no deletes occur, both point reads and range scans follow the deterministic
   * walk sequence across two complete passes.
   */
  @Test
  public void testNoDeleteKeepsTheWalk() throws Exception {
    CoGenerator generator = generator(ROWS);
    CoGenerator.Reader reader = generator.reader(0, 1);
    XMLConfigParser parser = new XMLConfigParser(CoGenFixtures.path(FIXTURE));
    Scenario scenario = CoGenFixtures.scenario(parser, SCENARIO);
    IndexDecoder decoder =
      new IndexDecoder(new RulesApplier(parser), scenario, CoGenerator.Settings.of(scenario), null);
    Walk walk = new Walk(IndexHash.columnSeed(11, "#walk"), WalkStrategy.MULTIPLICATIVE, 0, 1);
    Walk blocks = new Walk(IndexHash.columnSeed(11, "#walk"), WalkStrategy.MULTIPLICATIVE, 0, 1);
    for (int k = 0; k < 2 * ROWS; k++) {
      assertEquals(decoder.statement(POINT, () -> walk.next(ROWS)),
        reader.statement(Kind.SELECT, POINT, KEYED));
      // Calling the statement overload without an explicit query kind defaults to a point read.
      assertEquals(decoder.statement(POINT, () -> walk.next(ROWS)), reader.statement(POINT, KEYED));
    }
    for (int k = 0; k < 8; k++) {
      long w = blocks.next(ROWS / BLOCK);
      String expected =
        decoder.range(SCAN, "K1", ROWS, () -> new RowCursor.Block(w * BLOCK, w * BLOCK + BLOCK));
      assertEquals(expected, reader.statement(Kind.SCAN, SCAN, KEYED));
    }
    assertEquals(0, generator.cursor().deleted());
  }

  /**
   * Runs a deleter concurrently alongside two readers and verifies that no read draw ever returns a
   * row belonging to the deleted prefix observed by the reader before its draw began.
   */
  @Test
  public void testConcurrentReadsNeverDrawDeletedRows() throws Exception {
    final int rows = 10000;
    final CoGenerator generator = generator(rows);
    final RowCursor cursor = generator.cursor();
    final AtomicInteger violations = new AtomicInteger();
    final AtomicInteger draws = new AtomicInteger();
    final CountDownLatch start = new CountDownLatch(1);
    Thread deleter = new Thread(() -> {
      CoGenerator.Reader reader = generator.reader(0, 1);
      try {
        start.await();
        for (int k = 0; k < 1500; k++) {
          reader.statement(Kind.DELETE, DELETE, KEYED);
        }
      } catch (Exception e) {
        violations.incrementAndGet();
      }
    });
    List<Thread> readers = new ArrayList<>();
    for (final Kind kind : new Kind[] { Kind.SELECT, Kind.SCAN }) {
      readers.add(new Thread(() -> {
        CoGenerator.Reader reader = generator.reader(0, 1);
        try {
          start.await();
          for (int k = 0; k < 3000; k++) {
            long floor = cursor.deleted();
            String sql = reader.statement(kind, kind == Kind.SCAN ? SCAN : POINT, KEYED);
            long first = kind == Kind.SCAN ? bounds(sql)[0] : index(sql);
            if (first < floor) {
              violations.incrementAndGet();
            }
            draws.incrementAndGet();
          }
        } catch (Exception e) {
          violations.incrementAndGet();
        }
      }));
    }
    deleter.start();
    for (Thread reader : readers) {
      reader.start();
    }
    start.countDown();
    deleter.join();
    for (Thread reader : readers) {
      reader.join();
    }
    assertEquals(0, violations.get());
    assertEquals(6000, draws.get());
    assertEquals(1500 * BLOCK, cursor.deleted());
  }
}
