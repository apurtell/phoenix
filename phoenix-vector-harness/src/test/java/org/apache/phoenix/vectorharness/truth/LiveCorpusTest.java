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
package org.apache.phoenix.vectorharness.truth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.junit.Before;
import org.junit.Test;

public class LiveCorpusTest {

  private static final int ROWS = 500;

  private Corpus corpus;

  @Before
  public void setUp() {
    corpus = TruthTestSupport.synthetic(ROWS, 6, Metric.L2);
  }

  private static float[] vector(float x) {
    return new float[] { x, x, x, x, x, x };
  }

  /** Returns the rows that a full scan visits, by ordinal. */
  private static Map<Long, float[]> scanAll(LiveCorpus live) {
    Map<Long, float[]> seen = new TreeMap<>();
    live.scan(0, live.ordinalLimit(), (ordinal, vector, attributes) -> {
      assertNull("row " + ordinal + " visited twice", seen.put(ordinal, vector));
      assertNotNull(attributes);
    });
    return seen;
  }

  @Test
  public void testBaseRowsAreRegenerated() {
    LiveCorpus live = new LiveCorpus(corpus, ROWS);
    assertSame(corpus, live.corpus());
    assertEquals(ROWS, live.loaded());
    assertEquals(ROWS, live.liveCount());
    assertEquals(ROWS, live.ordinalLimit());
    for (long o = 0; o < ROWS; o += 7) {
      LiveCorpus.RowState s = live.get(o);
      assertNotNull(s);
      assertArrayEquals(corpus.vector(o), s.vector(), 0f);
      assertEquals(corpus.attributes(o), s.attributes());
      assertEquals(corpus.clusterOf(o), s.cluster());
      assertFalse(s.deleted());
      assertEquals(0L, s.committedNanos());
      assertTrue(live.isLive(o));
      assertNull(live.overlaid(o));
    }
    assertNull(live.get(-1));
    assertNull(live.get(ROWS));
    assertFalse(live.isLive(-1));
    assertFalse(live.isLive(ROWS));
  }

  @Test
  public void testLoadedIsClampedToTheCorpus() {
    LiveCorpus live = new LiveCorpus(corpus, 10 * ROWS);
    assertEquals(ROWS, live.loaded());
    assertEquals(ROWS, live.liveCount());
  }

  @Test
  public void testPutAndDeleteBookkeeping() {
    LiveCorpus live = new LiveCorpus(corpus, ROWS);
    DocAttributes a3 = corpus.attributes(3);

    // An update of a base row overlays it and leaves the count alone.
    live.put(3, vector(1f), a3, 2);
    assertEquals(ROWS, live.liveCount());
    LiveCorpus.RowState s = live.get(3);
    assertArrayEquals(vector(1f), s.vector(), 0f);
    assertEquals(2, s.cluster());
    assertSame(s, live.overlaid(3));
    assertTrue(s.committedNanos() != 0L);

    // An insert takes a new ordinal from the corpus row count upward.
    long o1 = live.allocateOrdinal();
    long o2 = live.allocateOrdinal();
    assertEquals(ROWS, o1);
    assertEquals(ROWS + 1, o2);
    live.put(o1, vector(2f), corpus.attributes(o1), 0);
    assertEquals(ROWS + 1, live.liveCount());
    assertEquals(o1 + 1, live.ordinalLimit());
    assertTrue(live.isLive(o1));
    assertFalse(live.isLive(o2));
    live.put(o1, vector(3f), corpus.attributes(o1), 0);
    assertEquals("an update of an inserted row", ROWS + 1, live.liveCount());

    // Deletes of base and inserted rows.
    live.delete(3);
    assertEquals(ROWS, live.liveCount());
    assertNull(live.get(3));
    assertFalse(live.isLive(3));
    assertTrue(live.overlaid(3).deleted());
    assertNull(live.overlaid(3).vector());
    live.delete(3);
    assertEquals("a repeated delete", ROWS, live.liveCount());
    live.delete(o1);
    assertEquals(ROWS - 1, live.liveCount());
    assertNull(live.get(o1));
    // A delete of a row that never existed changes nothing.
    live.delete(o2);
    assertEquals(ROWS - 1, live.liveCount());

    // A row that comes back after a delete is live again.
    live.put(3, vector(4f), a3, 1);
    assertEquals(ROWS, live.liveCount());
    assertTrue(live.isLive(3));
    assertArrayEquals(vector(4f), live.get(3).vector(), 0f);
  }

  @Test
  public void testMarkLoaded() {
    LiveCorpus live = new LiveCorpus(corpus, 100);
    assertEquals(100, live.loaded());
    assertEquals(100, live.liveCount());
    assertNull(live.get(150));
    // Writes to rows beyond the loaded prefix: an early update and a delete of an unloaded row.
    live.put(150, vector(1f), corpus.attributes(150), 0);
    assertEquals(101, live.liveCount());
    live.delete(160);
    assertEquals(101, live.liveCount());

    live.markLoaded(0, 200);
    assertEquals(200, live.loaded());
    // Rows 100 to 199 are loaded, but 150 was already counted and 160 is deleted.
    assertEquals(199, live.liveCount());
    assertArrayEquals(vector(1f), live.get(150).vector(), 0f);
    assertNull(live.get(160));
    assertArrayEquals(corpus.vector(170), live.get(170).vector(), 0f);

    live.markLoaded(0, 150);
    assertEquals("markLoaded never shrinks", 200, live.loaded());
    assertEquals(199, live.liveCount());
    live.markLoaded(0, 10 * ROWS);
    assertEquals(ROWS, live.loaded());
    assertEquals(ROWS - 1, live.liveCount());
    assertEquals(live.liveCount(), scanAll(live).size());
  }

  @Test
  public void testScanVisitsExactlyTheLiveRows() {
    LiveCorpus live = new LiveCorpus(corpus, 400);
    SplitMix64 rnd = new SplitMix64(31);
    Map<Long, float[]> expected = new HashMap<>();
    for (long o = 0; o < 400; o++) {
      expected.put(o, corpus.vector(o));
    }
    for (int step = 0; step < 2000; step++) {
      double u = rnd.nextDouble();
      if (u < 0.3) {
        long o = live.allocateOrdinal();
        float[] v = vector((float) rnd.nextGaussian());
        live.put(o, v, corpus.attributes(o), -1);
        expected.put(o, v);
      } else if (u < 0.65) {
        long o = live.randomLive(rnd, null);
        assertTrue(expected.containsKey(o));
        float[] v = vector((float) rnd.nextGaussian());
        live.put(o, v, live.get(o).attributes(), -1);
        expected.put(o, v);
      } else {
        long o = live.randomLive(rnd, null);
        assertTrue(expected.containsKey(o));
        live.delete(o);
        expected.remove(o);
      }
      if (step % 250 == 0 || step == 1999) {
        Map<Long, float[]> seen = scanAll(live);
        assertEquals("step " + step, expected.keySet(), seen.keySet());
        for (Map.Entry<Long, float[]> e : expected.entrySet()) {
          assertArrayEquals("row " + e.getKey(), e.getValue(), seen.get(e.getKey()), 0f);
        }
        assertEquals(expected.size(), live.liveCount());
      }
    }
    assertTrue("inserted rows lie beyond the base rows", live.ordinalLimit() > ROWS);
    // Rows 400 to 499 were never loaded and never written.
    for (long o = 400; o < ROWS; o++) {
      assertFalse(live.isLive(o));
    }

    // Scans of adjacent ranges partition a full scan.
    long limit = live.ordinalLimit();
    Set<Long> union = new HashSet<>();
    long[] cuts = { 0, 1, 137, 399, 400, ROWS, ROWS + 17, limit };
    for (int i = 0; i + 1 < cuts.length; i++) {
      long from = cuts[i];
      long to = cuts[i + 1];
      live.scan(from, to, (ordinal, vector, attributes) -> {
        assertTrue(ordinal >= from && ordinal < to);
        assertTrue("row " + ordinal + " visited twice", union.add(ordinal));
      });
    }
    assertEquals(expected.keySet(), union);
  }

  @Test
  public void testRandomLive() {
    LiveCorpus live = new LiveCorpus(corpus, ROWS);
    SplitMix64 rnd = new SplitMix64(8);
    for (long o = 0; o < ROWS; o += 2) {
      live.delete(o);
    }
    Set<Long> drawn = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      long o = live.randomLive(rnd, null);
      assertTrue(live.isLive(o));
      assertEquals(1, o % 2);
      drawn.add(o);
    }
    assertTrue("draws should spread over the live rows", drawn.size() > 100);
    for (int i = 0; i < 100; i++) {
      long o = live.randomLive(rnd, x -> x < 50);
      assertTrue(o < 50 && o % 2 == 1);
    }
    assertEquals(-1, live.randomLive(rnd, x -> false));

    LiveCorpus empty = new LiveCorpus(corpus, 0);
    assertEquals(0, empty.ordinalLimit());
    assertEquals(-1, empty.randomLive(rnd, null));
    long o = empty.allocateOrdinal();
    empty.put(o, vector(1f), corpus.attributes(o), -1);
    empty.delete(o);
    assertEquals(0, empty.liveCount());
    assertEquals(-1, empty.randomLive(rnd, null));
  }

  @Test
  public void testInFlightWrites() {
    LiveCorpus live = new LiveCorpus(corpus, ROWS);
    assertEquals(0, live.inFlightCount());
    assertTrue(live.beginWrite(5));
    assertFalse("a second write of the same row", live.beginWrite(5));
    assertTrue(live.beginWrite(6));
    assertTrue(live.writeInFlight(5));
    assertFalse(live.writeInFlight(7));
    assertEquals(2, live.inFlightCount());
    live.endWrite(5);
    assertFalse(live.writeInFlight(5));
    assertEquals(1, live.inFlightCount());
    assertTrue(live.beginWrite(5));
    live.endWrite(5);
    live.endWrite(6);
    live.endWrite(6);
    assertEquals(0, live.inFlightCount());
  }

  @Test
  public void testChangedSince() {
    LiveCorpus live = new LiveCorpus(corpus, ROWS);
    long before = System.nanoTime();
    assertFalse("an untouched base row", live.changedSince(1, before));
    live.put(1, vector(1f), corpus.attributes(1), 0);
    assertTrue("committed after the instant", live.changedSince(1, before));
    long committed = live.overlaid(1).committedNanos();
    assertTrue("committed at the instant", live.changedSince(1, committed));
    long after = System.nanoTime();
    while (after - committed <= 0) {
      after = System.nanoTime();
    }
    assertFalse("committed before the instant", live.changedSince(1, after));

    // A write in flight may change the row at any time.
    assertTrue(live.beginWrite(1));
    assertTrue(live.changedSince(1, after));
    assertTrue(live.beginWrite(2));
    assertTrue(live.changedSince(2, after));
    live.endWrite(1);
    live.endWrite(2);
    assertFalse(live.changedSince(1, after));
    assertFalse(live.changedSince(2, after));

    // A delete is a change.
    long beforeDelete = System.nanoTime();
    live.delete(2);
    assertTrue(live.changedSince(2, beforeDelete));
  }

  @Test
  public void testStreamedRowsChangeWhenTheyCommit() {
    LiveCorpus live = new LiveCorpus(corpus, 100);
    long before = System.nanoTime();
    assertFalse("rows loaded before the record", live.changedSince(10, before));
    live.markLoaded(100, 200);
    live.markLoaded(200, 300);
    assertTrue(live.changedSince(100, before));
    assertTrue(live.changedSince(199, before));
    assertTrue(live.changedSince(250, before));
    assertFalse(live.changedSince(99, before));
    assertFalse("not streamed yet", live.changedSince(300, before));
    long stamped = System.nanoTime();
    long after = System.nanoTime();
    while (after - stamped <= 0) {
      after = System.nanoTime();
    }
    // A query that starts after the commit sees the rows as they are.
    assertFalse(live.changedSince(150, after));
    assertFalse(live.changedSince(250, after));
    live.markLoaded(300, 400);
    assertTrue(live.changedSince(350, after));
    assertFalse(live.changedSince(250, after));
  }
}
