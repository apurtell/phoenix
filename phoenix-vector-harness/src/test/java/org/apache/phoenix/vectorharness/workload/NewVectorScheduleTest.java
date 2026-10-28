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
package org.apache.phoenix.vectorharness.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.junit.Test;

public class NewVectorScheduleTest {

  private static final int WINDOWS = 3;
  private static final long PER_WINDOW = 60;
  private static final int OPS = 150;

  /** Tells if an operation writes a new vector, about 30 percent of the operations. */
  private static LongPredicate kinds(long seed) {
    return seq -> new SplitMix64(SplitMix64.combine(seed, seq)).nextDouble() < 0.3;
  }

  /**
   * Runs all windows. Two threads take the operations of each window in a shuffled order and record
   * the draw index of each (window, seq).
   */
  private static Map<String, Long> run(long shuffleSeed) throws InterruptedException {
    NewVectorSchedule schedule = new NewVectorSchedule(PER_WINDOW, WINDOWS);
    Map<String, Long> draws = new ConcurrentHashMap<>();
    Random shuffle = new Random(shuffleSeed);
    AtomicReference<Throwable> error = new AtomicReference<>();
    for (int w = 0; w < WINDOWS; w++) {
      LongPredicate kinds = kinds(42L);
      schedule.beginWindow(kinds);
      List<Long> ops = new ArrayList<>();
      for (long seq = 0; seq < OPS; seq++) {
        if (kinds.test(seq)) {
          ops.add(seq);
        }
      }
      Collections.shuffle(ops, shuffle);
      int window = w;
      CountDownLatch start = new CountDownLatch(1);
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < 2; t++) {
        List<Long> mine =
          t == 0 ? ops.subList(0, ops.size() / 2) : ops.subList(ops.size() / 2, ops.size());
        Thread thread = new Thread(() -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          try {
            for (long seq : mine) {
              draws.put(window + ":" + seq, schedule.draw(seq));
            }
          } catch (RuntimeException e) {
            error.compareAndSet(null, e);
          }
        });
        thread.start();
        threads.add(thread);
      }
      start.countDown();
      for (Thread t : threads) {
        t.join();
      }
      if (error.get() != null) {
        throw new AssertionError("a draw failed", error.get());
      }
    }
    return draws;
  }

  @Test
  public void testDrawsDoNotDependOnThreadInterleaving() throws Exception {
    Map<String, Long> a = run(1L);
    Map<String, Long> b = run(2L);
    assertTrue(a.size() > WINDOWS * 30);
    assertEquals(new TreeMap<>(a), new TreeMap<>(b));
    // No two operations share a draw index.
    assertEquals(a.size(), new HashSet<>(a.values()).size());
  }

  @Test
  public void testWindowsOwnSeparateRanges() throws Exception {
    Map<String, Long> draws = run(3L);
    for (Map.Entry<String, Long> e : draws.entrySet()) {
      int window = Integer.parseInt(e.getKey().substring(0, e.getKey().indexOf(':')));
      long draw = e.getValue();
      assertTrue(e.toString(), draw >= window * PER_WINDOW && draw < (window + 1) * PER_WINDOW);
    }
    // In each window, the draws are dense and follow the sequence order.
    LongPredicate kinds = kinds(42L);
    for (int w = 0; w < WINDOWS; w++) {
      long next = w * PER_WINDOW;
      for (long seq = 0; seq < OPS; seq++) {
        if (kinds.test(seq)) {
          assertEquals(Long.valueOf(next++), draws.get(w + ":" + seq));
        }
      }
    }
  }

  @Test
  public void testSkippedOperationsKeepLaterDraws() {
    LongPredicate all = seq -> true;
    NewVectorSchedule full = new NewVectorSchedule(10, 1);
    full.beginWindow(all);
    NewVectorSchedule sparse = new NewVectorSchedule(10, 1);
    sparse.beginWindow(all);
    // The second schedule never asks for the even operations, as if they were skipped.
    for (long seq = 0; seq < 10; seq++) {
      long d = full.draw(seq);
      if (seq % 2 == 1) {
        assertEquals(d, sparse.draw(seq));
      }
    }
  }

  @Test
  public void testExhaustedRangeFailsClearly() {
    NewVectorSchedule schedule = new NewVectorSchedule(3, 2);
    schedule.beginWindow(seq -> true);
    for (long seq = 0; seq < 3; seq++) {
      assertEquals(seq, schedule.draw(seq));
    }
    try {
      schedule.draw(3);
      fail("a draw past the range of the window must fail");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(),
        expected.getMessage().contains("needs more than its 3 planned new vectors"));
    }
    // The next window starts at its own range, so the failure does not leak into it.
    schedule.beginWindow(seq -> true);
    assertEquals(3L, schedule.draw(0));
    schedule.beginWindow(seq -> true);
    try {
      schedule.draw(0);
      fail("a window past the planned windows must fail");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("past the 2 planned"));
    }
  }

  @Test
  public void testInvalidRequestsFail() {
    NewVectorSchedule schedule = new NewVectorSchedule(5, 1);
    expectFailure(schedule, 0, "has no steady window");
    schedule.beginWindow(seq -> seq % 2 == 0);
    expectFailure(schedule, 1, "writes no new vector");
    expectFailure(schedule, -1, "out of range");
    assertEquals(1L, schedule.draw(2));
  }

  private static void expectFailure(NewVectorSchedule schedule, long seq, String text) {
    try {
      schedule.draw(seq);
      fail("draw(" + seq + ") must fail");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(text));
    }
  }

  @Test
  public void testSchedulesWithTheSameKindsAgree() {
    Set<Long> seen = new HashSet<>();
    NewVectorSchedule a = new NewVectorSchedule(PER_WINDOW, 1);
    NewVectorSchedule b = new NewVectorSchedule(PER_WINDOW, 1);
    a.beginWindow(kinds(7L));
    b.beginWindow(kinds(7L));
    // One schedule answers in reverse order, which extends its table in one step.
    List<Long> seqs = new ArrayList<>();
    for (long seq = 0; seq < OPS; seq++) {
      if (kinds(7L).test(seq)) {
        seqs.add(seq);
      }
    }
    List<Long> reversed = new ArrayList<>(seqs);
    Collections.reverse(reversed);
    Map<Long, Long> fromB = new TreeMap<>();
    for (long seq : reversed) {
      fromB.put(seq, b.draw(seq));
    }
    for (long seq : seqs) {
      long d = a.draw(seq);
      assertEquals(fromB.get(seq), Long.valueOf(d));
      assertTrue(seen.add(d));
    }
  }
}
