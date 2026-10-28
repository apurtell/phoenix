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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class StageEndTest {

  private static final long END = 10_000_000_000L;
  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final long MAX = 100 * SECOND;

  @Test
  public void testWithoutDisruptionTheStageStopsAtTheWindowEnd() {
    StageEnd end = new StageEnd(END);
    assertFalse(end.ended(END - 1));
    assertTrue(end.ended(END));
    assertTrue(end.ended(END + SECOND));
    assertEquals(0.0, end.extensionSeconds(), 0.0);
    assertNull(end.extendedBy());
    assertTrue(end.skipped().isEmpty());
    assertEquals(END + 5 * SECOND, end.joinDeadline(END + SECOND, 5 * SECOND, MAX));
  }

  @Test
  public void testDisruptionInFlightExtendsTheStageUntilItsRecoveryEnds() {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("4s:restart", END - 26 * SECOND));
    assertFalse(end.ended(END));
    assertFalse(end.ended(END + 40 * SECOND));
    // The deadline for the threads moves while the disruption is in flight.
    assertEquals(END + 45 * SECOND, end.joinDeadline(END + 40 * SECOND, 5 * SECOND, MAX));
    end.finish(END + 52 * SECOND);
    assertTrue(end.ended(END + 52 * SECOND));
    assertEquals(52.0, end.extensionSeconds(), 1e-9);
    assertEquals("4s:restart", end.extendedBy());
    assertEquals(END + 57 * SECOND, end.joinDeadline(END + 60 * SECOND, 5 * SECOND, MAX));
  }

  @Test
  public void testClosedStageStillRunsTheSlotsBeforeTheWindowEnd() throws Exception {
    StageEnd end = new StageEnd(END);
    assertTrue(end.ended(END));
    // An open loop that runs late still runs the slots that are due before the window end.
    assertFalse(end.reached(END - 1));
    assertTrue(end.reached(END));
    end.stop();
    assertTrue(end.reached(END - 1));
  }

  @Test
  public void testExtensionHasALimit() {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("4s:restart", END - SECOND));
    assertEquals(END + 55 * SECOND, end.joinDeadline(END + 50 * SECOND, 5 * SECOND, MAX));
    // A disruption that hangs does not move the deadline past the limit.
    assertEquals(END + MAX + 5 * SECOND, end.joinDeadline(END + 3 * MAX, 5 * SECOND, MAX));
    assertEquals("4s:restart", end.inFlight());
    end.finish(END + 3 * MAX);
    assertNull(end.inFlight());
    assertEquals(END + MAX + 5 * SECOND, end.joinDeadline(END + 3 * MAX, 5 * SECOND, MAX));
  }

  @Test
  public void testOverrunEndsTheExtensionAtTheStop() {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("4s:restart", END - SECOND));
    assertEquals("4s:restart", end.overrun(END + MAX + 5 * SECOND));
    assertTrue(end.ended(END + MAX + 5 * SECOND));
    assertEquals("4s:restart", end.extendedBy());
    assertEquals("4s:restart", end.overrunBy());
    // A disruption that ends after the stop does not move the end of the extension.
    end.finish(END + 2 * MAX);
    assertEquals((MAX + 5 * SECOND) / 1e9, end.extensionSeconds(), 1e-9);
    // Without a disruption in flight, the stage only stops.
    StageEnd quiet = new StageEnd(END);
    assertNull(quiet.overrun(END + SECOND));
    assertTrue(quiet.ended(END - SECOND));
    assertNull(quiet.overrunBy());
    assertNull(quiet.extendedBy());
  }

  @Test
  public void testDisruptionThatEndsInTheWindowDoesNotExtendIt() {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("3s:move", END - 20 * SECOND));
    assertFalse(end.ended(END - 19 * SECOND));
    end.finish(END - 18 * SECOND);
    assertTrue(end.ended(END));
    assertEquals(0.0, end.extensionSeconds(), 0.0);
    assertNull(end.extendedBy());
  }

  @Test
  public void testNoDisruptionStartsAfterTheWindowEnd() {
    StageEnd end = new StageEnd(END);
    assertFalse(end.begin("9s:split", END));
    end.skip("15s:compact");
    assertEquals(Arrays.asList("9s:split", "15s:compact"), end.skipped());
    // A closed stage also refuses a disruption that a late clock read places before its end.
    StageEnd closed = new StageEnd(END);
    assertTrue(closed.ended(END));
    assertFalse(closed.begin("9s:split", END - SECOND));
    assertEquals(Collections.singletonList("9s:split"), closed.skipped());
  }

  @Test
  public void testStopEndsTheStageAtOnce() throws Exception {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("4s:restart", END - SECOND));
    end.stop();
    assertTrue(end.ended(END - SECOND));
    assertTrue(end.reached(END - SECOND));
    assertTrue(end.ended(END + SECOND));
    assertFalse(end.begin("9s:split", END - SECOND));
  }

  @Test
  public void testStopReleasesAWorkerThatWaitsForTheWindowEnd() throws Exception {
    long windowEnd = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
    StageEnd end = new StageEnd(windowEnd);
    CompletableFuture<Boolean> reached =
      CompletableFuture.supplyAsync(() -> reached(end, windowEnd));
    Thread.sleep(200);
    assertFalse(reached.isDone());
    end.stop();
    assertTrue(reached.get(10, TimeUnit.SECONDS));
  }

  @Test
  public void testWorkerWaitsForTheWindowEndBeforeItStops() throws Exception {
    long windowEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
    StageEnd end = new StageEnd(windowEnd);
    // The next slot of the worker falls after the window end, but a disruption starts before it.
    CompletableFuture<Boolean> reached =
      CompletableFuture.supplyAsync(() -> reached(end, windowEnd));
    assertTrue(end.begin("4s:restart", System.nanoTime()));
    assertFalse(reached.get(10, TimeUnit.SECONDS));
  }

  @Test
  public void testWorkloadRunsUntilTheDisruptionEnds() throws Exception {
    long windowEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
    StageEnd end = new StageEnd(windowEnd);
    assertTrue(end.begin("4s:restart", System.nanoTime()));
    AtomicLong stopped = new AtomicLong();
    Thread worker = new Thread(() -> {
      try {
        while (!end.reached(System.nanoTime())) {
          Thread.sleep(5);
        }
        stopped.set(System.nanoTime());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    worker.start();
    Thread.sleep(400);
    assertTrue("the worker runs past the window end", worker.isAlive());
    long finished = System.nanoTime();
    end.finish(finished);
    worker.join(10_000);
    assertFalse(worker.isAlive());
    assertTrue(stopped.get() - finished >= 0);
    assertEquals("4s:restart", end.extendedBy());
    assertEquals((finished - windowEnd) / 1e9, end.extensionSeconds(), 1e-9);
    assertTrue(end.extensionSeconds() >= 0.3);
  }

  @Test
  public void testWritesStopAtTheWindowEndWhileTheReadsContinue() throws Exception {
    StageEnd end = new StageEnd(END);
    assertTrue(end.begin("4s:restart", END - 26 * SECOND));
    // A write slot before the window end still runs, also when it runs late.
    assertFalse(end.writesReached(END - 1));
    assertTrue(end.writesReached(END));
    // The reads continue during the extension.
    assertFalse(end.reached(END + SECOND));
    assertFalse(end.ended(END + 40 * SECOND));
    end.finish(END + 52 * SECOND);
    assertTrue(end.reached(END + 52 * SECOND));
    end.stop();
    assertTrue(end.writesReached(END - 1));
  }

  @Test
  public void testMutationWorkerStopsWhileTheQueryWorkerRunsInTheExtension() throws Exception {
    long windowEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
    StageEnd end = new StageEnd(windowEnd);
    assertTrue(end.begin("4s:restart", System.nanoTime()));
    Throttle throttle = new Throttle(200);
    AtomicLong lastWrite = new AtomicLong(Long.MIN_VALUE);
    Thread writer = worker(() -> {
      while (true) {
        long slot = throttle.claim();
        if (end.writesReached(slot)) {
          return;
        }
        Throttle.awaitSlot(slot);
        lastWrite.set(slot);
      }
    });
    AtomicLong reads = new AtomicLong();
    Thread reader = worker(() -> {
      while (!end.reached(System.nanoTime())) {
        reads.incrementAndGet();
        Thread.sleep(5);
      }
    });
    writer.join(10_000);
    assertFalse("the writes stop at the window end", writer.isAlive());
    assertTrue(lastWrite.get() - windowEnd < 0);
    long readsAtWriteEnd = reads.get();
    Thread.sleep(300);
    assertTrue("the reads continue in the extension", reader.isAlive());
    assertTrue(reads.get() > readsAtWriteEnd);
    end.finish(System.nanoTime());
    reader.join(10_000);
    assertFalse(reader.isAlive());
  }

  /** A loop body that a worker thread runs until it returns or an interrupt stops it. */
  private interface Loop {
    void run() throws InterruptedException;
  }

  private static Thread worker(Loop loop) {
    Thread t = new Thread(() -> {
      try {
        loop.run();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static boolean reached(StageEnd end, long windowEnd) {
    try {
      return end.reached(windowEnd + 1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
