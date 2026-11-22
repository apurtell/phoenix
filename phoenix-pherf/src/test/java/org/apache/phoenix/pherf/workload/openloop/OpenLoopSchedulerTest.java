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
package org.apache.phoenix.pherf.workload.openloop;

import static org.apache.phoenix.pherf.workload.openloop.ArrivalScheduleTest.openLoop;
import static org.apache.phoenix.pherf.workload.openloop.ArrivalScheduleTest.stage;
import static org.apache.phoenix.pherf.workload.openloop.FakeClock.awaitState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

/**
 * Verifies requirements RQ-166 through RQ-170 by exercising the open-loop scheduler against a fake
 * clock. Because the clock advances only when explicitly driven by a test and a fake task holds
 * operations on latches, every observed scheduler state is exact.
 */
public class OpenLoopSchedulerTest {
  private static final String SERVICE = "query:s:q";
  private static final Histogram PRECISION = new Histogram(1, 600_000_000L, 3);

  @Rule
  public Timeout timeout = Timeout.seconds(120);

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * Executes each operation immediately, or suspends it until the test releases the associated
   * stall.
   */
  static final class FakeTask implements OpenLoopScheduler.Task {
    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    final Map<Long, CountDownLatch> stalls = new ConcurrentHashMap<>();
    final Map<Long, Long> services = new ConcurrentHashMap<>();

    FakeTask stall(long... sequences) {
      for (long sequence : sequences) {
        stalls.put(sequence, new CountDownLatch(1));
      }
      return this;
    }

    FakeTask service(long sequence, long micros) {
      services.put(sequence, micros);
      return this;
    }

    void release(long sequence) {
      stalls.get(sequence).countDown();
    }

    @Override
    public long execute(long sequence, boolean warmup) throws Exception {
      calls.add((warmup ? "w" : "s") + sequence);
      CountDownLatch stall = warmup ? null : stalls.get(sequence);
      if (stall != null) {
        stall.await();
      }
      Long service = services.get(sequence);
      return service == null ? 0 : service;
    }

    /**
     * Returns a distinct key for each sequence number so that the latency report captures the exact
     * value recorded for each individual operation.
     */
    @Override
    public String responseOperation(long sequence) {
      return "response:s:op" + sequence;
    }
  }

  static OpenLoopScheduler scheduler(FakeClock clock, OpenLoop openLoop, int concurrency,
    long warmup, OpenLoopScheduler.Task task) {
    OpenLoopScheduler scheduler = new OpenLoopScheduler(openLoop, concurrency, warmup, task, "s", 1,
      Collections.singletonList(SERVICE), clock);
    clock.attach(scheduler);
    return scheduler;
  }

  /**
   * Configures a schedule with concurrency C=1, a target rate of 1000/s for 11 ms, and maxLagMs=2,
   * where op0 stalls until the clock reaches 10 ms.
   */
  private static OpenLoop lagged(OpenLoop.LagPolicy policy) {
    OpenLoop openLoop = openLoop(stage(11, 1000));
    openLoop.setMaxLagMs(2L);
    openLoop.setLagPolicy(policy);
    return openLoop;
  }

  /**
   * Runs op0 until it blocks on its stall, advances the clock to 10 ms, and awaits the state where
   * op1 is waiting in the queue.
   */
  private static void stallUntilTenMs(OpenLoopScheduler scheduler, FakeClock clock) {
    awaitState(scheduler, s -> s.getInFlight() == 1 && s.getScheduled() == 1);
    clock.advanceTo(10_000);
    awaitState(scheduler, s -> s.getQueueDepth() == 1 && s.getScheduled() == 2);
  }

  private static long histogramCount(JsonObject report, String operation) {
    return HdrFiles.histograms(report).get(LatencyReport.tag(operation, LatencyRecorders.STEADY))
      .get("count").getAsLong();
  }

  /**
   * Verifies RQ-166 with concurrency C=1 at a rate of 1000/s for 2 ms. Because op0 stalls and has a
   * service time of 500 us, op1 (with a service time of 300 us) waits in the queue from its
   * intended arrival time of 1,000 us until the clock reaches 2,500 us. Its recorded response time
   * minus its service time equals that exact dispatch delay of 1,500 us.
   */
  @Test
  public void testResponseMinusServiceIsDispatchDelay() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask().stall(0).service(0, 500).service(1, 300);
    OpenLoopScheduler scheduler = scheduler(clock, openLoop(stage(2, 1000)), 1, 0, task);
    HdrFiles.begin(folder.getRoot(), "rq166");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      awaitState(scheduler, s -> s.getInFlight() == 1);
      clock.advanceTo(1000);
      awaitState(scheduler, s -> s.getQueueDepth() == 1);
      clock.advanceTo(2500);
      task.release(0);
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(2, last.getCompleted());
    assertEquals(1500, last.getMaxDriftUs());
    JsonObject report = HdrFiles.json(new File(folder.getRoot(), "LATENCY_rq166.json"));
    Map<String, JsonObject> histograms = HdrFiles.histograms(report);
    JsonObject op1 = histograms.get(LatencyReport.tag("response:s:op1", LatencyRecorders.STEADY));
    assertEquals(1800, op1.get("minUs").getAsLong());
    assertEquals(1800, op1.get("maxUs").getAsLong());
    assertEquals(1500, op1.get("minUs").getAsLong() - 300);
    JsonObject op0 = histograms.get(LatencyReport.tag("response:s:op0", LatencyRecorders.STEADY));
    assertEquals(500, op0.get("maxUs").getAsLong());
    // Verify that the live state histograms track service time and response time separately.
    assertEquals(300, last.getService().getMinValue());
    assertEquals(500, last.getService().getMaxValue());
    assertEquals(500, last.getResponse().getMinValue());
    assertEquals(1800, last.getResponse().getMaxValue());
  }

  /**
   * Verifies RQ-167 in LATE mode: operations 1 through 7 experience a lag of 9 to 3 ms, which
   * exceeds the 2 ms threshold, and all of them still execute.
   */
  @Test
  public void testLateModeCountsLate() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask().stall(0);
    OpenLoopScheduler scheduler = scheduler(clock, lagged(OpenLoop.LagPolicy.LATE), 1, 0, task);
    FakeClock.Run run = new FakeClock.Run(scheduler);
    stallUntilTenMs(scheduler, clock);
    task.release(0);
    awaitState(scheduler, s -> s.getCompleted() == 11);
    clock.advanceTo(11_000);
    OpenLoopState last = run.result();
    assertEquals(7, last.getLate());
    assertEquals(11, last.getCompleted());
    assertEquals(0, last.getDropped());
    assertEquals(11, last.getScheduled());
    assertEquals(9000, last.getMaxDriftUs());
    assertTrue(PRECISION.valuesAreEquivalent(9000, last.getResponse().getMaxValue()));
    assertEquals(Arrays.asList("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"),
      task.calls);
  }

  /**
   * Verifies RQ-167 in DROP mode: operations 1 through 7 exceed the lag threshold and are dropped
   * by the scheduler, so the underlying task never receives or executes them.
   */
  @Test
  public void testDropModeDropsWithoutExecuting() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask().stall(0);
    OpenLoopScheduler scheduler = scheduler(clock, lagged(OpenLoop.LagPolicy.DROP), 1, 0, task);
    FakeClock.Run run = new FakeClock.Run(scheduler);
    stallUntilTenMs(scheduler, clock);
    task.release(0);
    awaitState(scheduler, s -> s.getCompleted() == 4);
    clock.advanceTo(11_000);
    OpenLoopState last = run.result();
    assertEquals(7, last.getDropped());
    assertEquals(4, last.getCompleted());
    assertEquals(0, last.getLate());
    assertEquals(11, last.getScheduled());
    assertEquals(Arrays.asList("s0", "s8", "s9", "s10"), task.calls);
  }

  /**
   * Verifies RQ-168 using the DROP configuration with stalls on op0 and op8. While op8 is stalled
   * in flight, op9 waits in the queue and op10 becomes due with no room remaining. The captured
   * state snapshot is exact because both the clock and the task are stationary.
   */
  @Test
  public void testStateMidRun() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask().stall(0, 8);
    OpenLoopScheduler scheduler = scheduler(clock, lagged(OpenLoop.LagPolicy.DROP), 1, 0, task);
    FakeClock.Run run = new FakeClock.Run(scheduler);
    stallUntilTenMs(scheduler, clock);
    task.release(0);
    OpenLoopState s = awaitState(scheduler,
      x -> x.getScheduled() == 10 && x.getInFlight() == 1 && x.getQueueDepth() == 1);
    assertEquals(1000.0, s.getTargetRate(), 0);
    assertEquals(OpenLoopState.STEADY, s.getPhase());
    assertNull(s.getStopReason());
    assertEquals(1, s.getQueueDepth());
    assertEquals(1, s.getInFlight());
    // Operation op9, with an intended arrival time of 9 ms, is the oldest operation that has not
    // yet started executing.
    assertEquals(1000, s.getDriftUs());
    assertEquals(10, s.getScheduled());
    assertEquals(1, s.getCompleted());
    assertEquals(7, s.getDropped());
    assertEquals(0, s.getLate());
    assertEquals(0, s.getAborted());
    assertEquals(2, s.getPending());
    assertEquals(s.getScheduled(),
      s.getCompleted() + s.getDropped() + s.getAborted() + s.getPending());
    assertEquals(1, s.getService().getTotalCount());
    assertEquals(1, s.getResponse().getTotalCount());
    assertEquals(9000, s.getMaxDriftUs());
    assertEquals(1, s.getMaxQueueDepth());
    assertEquals(1, s.getMaxInFlight());
    assertEquals(1, s.getConcurrency());
    assertEquals("s", s.getScenario());
    assertEquals(1, s.getQuerySet());
    assertEquals(Collections.singletonList(SERVICE), s.getOperations());
    assertTrue(s.getStartMs() > 0);
    assertEquals(0, s.getEndMs());
    task.release(8);
    awaitState(scheduler, x -> x.getCompleted() == 4);
    clock.advanceTo(11_000);
    OpenLoopState last = run.result();
    assertEquals(OpenLoopState.DONE, last.getPhase());
    assertEquals(0, last.getPending());
    assertEquals(0.0, last.getTargetRate(), 0);
    assertEquals(0, last.getDriftUs());
  }

  /**
   * Verifies RQ-169: each arrival k is released by the scheduler at its exact target timestamp t0 +
   * offset_k and not 1 us before.
   */
  @Test
  public void testDispatchAtIntendedTimes() throws Exception {
    long t0 = 1_000_000;
    OpenLoop openLoop = openLoop(stage(3, 1000), stage(4, 500, 1500.0));
    List<Long> offsets = ArrivalScheduleTest.offsets(openLoop);
    assertEquals(7, offsets.size());
    FakeClock clock = new FakeClock(t0);
    OpenLoopScheduler scheduler = scheduler(clock, openLoop, 4, 0, new FakeTask());
    FakeClock.Run run = new FakeClock.Run(scheduler);
    awaitState(scheduler, s -> s.getScheduled() == 1);
    for (int k = 1; k < offsets.size(); k++) {
      clock.advanceTo(t0 + offsets.get(k) - 1);
      assertEquals("before arrival " + k, k, scheduler.state().getScheduled());
      clock.advanceTo(t0 + offsets.get(k));
      assertEquals("at arrival " + k, k + 1, scheduler.state().getScheduled());
    }
    clock.advanceTo(t0 + 7000);
    OpenLoopState last = run.result();
    assertEquals(7, last.getScheduled());
    assertEquals(7, last.getCompleted());
    assertEquals(OpenLoopState.DURATION, last.getStopReason());
  }

  /**
   * Verifies RQ-170 and R-131 with concurrency C=4 at a rate of 10,000/s, far above the capacity of
   * a task that holds each operation until the test grants it a permit. Neither the in-flight
   * operation count nor the queue depth ever exceeds 4. Each 1 ms round with 4 permits completes 4
   * operations, proving that the workers execute concurrently rather than in series.
   */
  @Test
  public void testConcurrencyAndQueueBounds() throws Exception {
    AtomicInteger current = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    Semaphore permits = new Semaphore(0);
    OpenLoopScheduler.Task task = new OpenLoopScheduler.Task() {
      @Override
      public long execute(long sequence, boolean warmup) throws Exception {
        peak.accumulateAndGet(current.incrementAndGet(), Math::max);
        try {
          permits.acquire();
        } finally {
          current.decrementAndGet();
        }
        return 1000;
      }

      @Override
      public String responseOperation(long sequence) {
        return "response:s:q";
      }
    };
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, openLoop(stage(10, 10_000)), 4, 0, task);
    Consumer<OpenLoopState> bounds = s -> {
      FakeClock.assertIdentity(s);
      assertTrue(FakeClock.describe(s), s.getInFlight() <= 4);
      assertTrue(FakeClock.describe(s), s.getQueueDepth() <= 4);
    };
    FakeClock.Run run = new FakeClock.Run(scheduler);
    try {
      // The clock advances only after the arrival schedule has started at t0 = 0.
      awaitState(scheduler, s -> s.getInFlight() == 1, bounds);
      clock.advanceTo(1000);
      awaitState(scheduler, s -> s.getInFlight() == 4 && s.getQueueDepth() == 4, bounds);
      for (int round = 1; round <= 5; round++) {
        final long completed = 4L * round;
        permits.release(4);
        clock.advanceTo((round + 1) * 1000L);
        OpenLoopState s = awaitState(scheduler,
          x -> x.getCompleted() == completed && x.getInFlight() == 4 && x.getQueueDepth() == 4,
          bounds);
        assertEquals(completed, s.getCompleted());
      }
    } finally {
      permits.release(1000);
    }
    clock.advanceTo(10_000);
    OpenLoopState last = run.result();
    bounds.accept(last);
    assertEquals(100, last.getScheduled());
    assertEquals(100, last.getCompleted());
    assertEquals(4, last.getMaxInFlight());
    assertEquals(4, last.getMaxQueueDepth());
    assertEquals(4, peak.get());
  }

  /**
   * Verifies warmup execution: with K=2 iterations, one query, and concurrency C=2, the scheduler
   * executes 4 warmup calls before dispatching any steady-state call. During warmup the arrival
   * schedule has not yet started, and the scheduler's operation counts exclude warmup calls.
   */
  @Test
  public void testWarmupBeforeSchedule() throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    OpenLoopScheduler.Task task = new OpenLoopScheduler.Task() {
      @Override
      public long execute(long sequence, boolean warmup) throws Exception {
        calls.add((warmup ? "w" : "s") + sequence);
        if (warmup) {
          entered.countDown();
          go.await();
        }
        return 0;
      }

      @Override
      public String responseOperation(long sequence) {
        return "response:s:q";
      }
    };
    FakeClock clock = new FakeClock(5000);
    OpenLoopScheduler scheduler = scheduler(clock, openLoop(stage(2, 1000)), 2, 2, task);
    HdrFiles.begin(folder.getRoot(), "warmup");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      assertTrue(entered.await(FakeClock.GUARD_MS, TimeUnit.MILLISECONDS));
      OpenLoopState s = scheduler.state();
      assertEquals(OpenLoopState.WARMUP, s.getPhase());
      assertEquals(0, s.getScheduled());
      assertEquals(0.0, s.getTargetRate(), 0);
      assertEquals(0, s.getStartMs());
      go.countDown();
      awaitState(scheduler, x -> x.getCompleted() == 1);
      clock.advanceTo(7000);
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(6, calls.size());
    for (int i = 0; i < 4; i++) {
      assertTrue(calls.toString(), calls.get(i).startsWith("w"));
    }
    assertEquals(Arrays.asList("s0", "s1"), calls.subList(4, 6));
    assertEquals(2, last.getScheduled());
    assertEquals(2, last.getCompleted());
    // Confirm that only steady-state operations contribute samples to the response histogram.
    JsonObject report = HdrFiles.json(new File(folder.getRoot(), "LATENCY_warmup.json"));
    assertEquals(2, histogramCount(report, "response:s:q"));
  }
}
