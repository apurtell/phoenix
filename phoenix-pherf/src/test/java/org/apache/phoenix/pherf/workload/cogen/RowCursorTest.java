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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.CoGeneration.WatermarkPolicy;
import org.junit.Test;

/**
 * Verifies requirements RQ-176 and RQ-177: writers claim disjoint blocks with no gaps below the
 * high-water mark, and the watermark tracks the contiguous committed prefix so that it never
 * decreases and readers draw only committed indexes.
 */
public class RowCursorTest {
  private static final int BLOCK = 64;

  /**
   * Verifies RQ-176: 8 threads concurrently claim blocks until their respective quotas are
   * exhausted. The union of all claimed blocks covers the interval [0, high water) without claiming
   * any index twice, and each block resides within a single aligned window of the block size.
   */
  @Test
  public void testConcurrentClaimsAreDisjoint() throws Exception {
    final RowCursor cursor = new RowCursor(BLOCK, 0);
    final List<RowCursor.Block> blocks = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch go = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    long total = 0;
    for (int t = 0; t < 8; t++) {
      final long quota = 5000 + 37 * t;
      total += quota;
      final Random random = new Random(t);
      threads.add(new Thread(() -> {
        try {
          go.await();
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
        long left = quota;
        while (left > 0) {
          RowCursor.Block block = cursor.claim(Math.min(left, 1 + random.nextInt(100)));
          blocks.add(block);
          left -= block.size();
        }
      }));
    }
    for (Thread thread : threads) {
      thread.start();
    }
    go.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertEquals(total, cursor.highWater());
    BitSet seen = new BitSet();
    for (RowCursor.Block block : blocks) {
      assertTrue(block.size() >= 1 && block.size() <= BLOCK);
      assertEquals(block.start / BLOCK, (block.end - 1) / BLOCK);
      for (long i = block.start; i < block.end; i++) {
        assertTrue("claimed twice " + i, !seen.get((int) i));
        seen.set((int) i);
      }
    }
    assertEquals(total, seen.cardinality());
    assertEquals(total, seen.nextClearBit(0));
  }

  /**
   * Verifies RQ-177: 4 writer threads commit blocks out of order while a watcher thread samples the
   * watermark and a recording reader thread draws indexes. The sampled watermarks never decrease,
   * each draw is strictly below the watermark observed by the reader immediately after the draw,
   * and once all writers finish, the next full pass visits every committed index exactly once.
   */
  @Test
  public void testWatermarkUnderConcurrentWriters() throws Exception {
    final RowCursor cursor = new RowCursor(BLOCK, 0);
    final AtomicBoolean done = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final List<Long> samples = new ArrayList<>();
    final List<long[]> draws = new ArrayList<>();
    final Walk walk = new Walk(3L, WalkStrategy.MULTIPLICATIVE, 0, 1);
    List<Thread> writers = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      final Random random = new Random(100 + t);
      writers.add(new Thread(() -> {
        List<RowCursor.Block> held = new ArrayList<>();
        for (int k = 0; k < 200; k++) {
          held.add(cursor.claim(1 + random.nextInt(BLOCK)));
          // Accumulate a batch of claimed blocks and commit them in a shuffled order.
          if (held.size() == 3 || k == 199) {
            Collections.shuffle(held, random);
            for (RowCursor.Block block : held) {
              cursor.commit(block);
              Thread.yield();
            }
            held.clear();
          }
        }
      }));
    }
    Thread watcher = new Thread(() -> {
      while (!done.get()) {
        samples.add(cursor.watermark());
      }
    });
    Thread reader = new Thread(() -> {
      try {
        while (!done.get()) {
          long i = walk.next(cursor.watermark());
          long after = cursor.watermark();
          if (i >= 0) {
            draws.add(new long[] { i, after });
          }
        }
      } catch (Throwable t) {
        failure.set(t);
      }
    });
    watcher.start();
    reader.start();
    for (Thread writer : writers) {
      writer.start();
    }
    for (Thread writer : writers) {
      writer.join();
    }
    done.set(true);
    watcher.join();
    reader.join();
    assertEquals(null, failure.get());
    long committed = cursor.highWater();
    assertEquals(committed, cursor.watermark());
    assertTrue(samples.size() > 1);
    for (int k = 1; k < samples.size(); k++) {
      assertTrue("watermark went down at sample " + k, samples.get(k) >= samples.get(k - 1));
    }
    assertTrue(draws.size() > 0);
    for (long[] draw : draws) {
      assertTrue(draw[0] + " drawn at watermark " + draw[1], draw[0] < draw[1]);
    }
    // Complete the active walk pass, then execute a full pass across the final watermark.
    Walk.State state = walk.state();
    for (long k = state.position; k < state.window; k++) {
      walk.next(committed);
    }
    BitSet seen = new BitSet();
    for (long k = 0; k < committed; k++) {
      long i = walk.next(committed);
      assertTrue("visited twice " + i, !seen.get((int) i));
      seen.set((int) i);
    }
    assertEquals(committed, seen.cardinality());
    assertEquals(committed, seen.nextClearBit(0));
  }

  /**
   * Verifies that committing a block above an uncommitted gap holds the watermark at the gap
   * boundary until the intervening gap block is committed.
   */
  @Test
  public void testOutOfOrderCommitsHoldAtGap() {
    RowCursor cursor = new RowCursor(10, 0);
    RowCursor.Block first = cursor.claim(10);
    RowCursor.Block second = cursor.claim(10);
    RowCursor.Block third = cursor.claim(10);
    assertEquals(30, cursor.highWater());
    cursor.commit(third);
    assertEquals(0, cursor.watermark());
    cursor.commit(first);
    assertEquals(10, cursor.watermark());
    cursor.commit(second);
    assertEquals(30, cursor.watermark());
  }

  /**
   * Verifies that a block claim stops at the next multiple of the block size, including when
   * starting from an initial position that is not a multiple of the block size.
   */
  @Test
  public void testClaimsStayInAlignedWindows() {
    RowCursor cursor = new RowCursor(10, 7);
    assertEquals(7, cursor.watermark());
    assertBlock(cursor.claim(100), 7, 10);
    assertBlock(cursor.claim(4), 10, 14);
    assertBlock(cursor.claim(100), 14, 20);
    cursor.reset(0);
    assertEquals(0, cursor.watermark());
    assertEquals(0, cursor.highWater());
    assertBlock(cursor.claim(3), 0, 3);
  }

  /**
   * Verifies that the COMMIT policy publishes each block to the watermark immediately upon commit,
   * whereas the LOAD policy defers publishing the blocks until finish is invoked.
   */
  @Test
  public void testLoadPolicies() {
    RowCursor cursor = new RowCursor(10, 0);
    RowCursor.Load commit = cursor.load(WatermarkPolicy.COMMIT);
    commit.committed(commit.claim(10));
    assertEquals(10, cursor.watermark());
    RowCursor.Load load = cursor.load(WatermarkPolicy.LOAD);
    load.committed(load.claim(10));
    load.committed(load.claim(10));
    assertEquals(10, cursor.watermark());
    load.finish();
    assertEquals(30, cursor.watermark());
  }

  private static void assertBlock(RowCursor.Block block, long start, long end) {
    assertEquals(start, block.start);
    assertEquals(end, block.end);
  }
}
