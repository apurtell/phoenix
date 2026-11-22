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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

/**
 * Verifies for RQ-171 that every termination path shuts down all worker threads and produces a
 * complete JSON report reflecting the final schedule state. Each test scenario executes at a rate
 * of 1000/s with concurrency C=1 against a fake clock.
 */
public class OpenLoopStopTest {
  private static final String RESPONSE = "response:s:q";

  @Rule
  public Timeout timeout = Timeout.seconds(120);

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * Represents a task that blocks a specific sequence number until its gate is released, and
   * optionally throws a configured exception once unblocked.
   */
  private static final class GatedTask implements OpenLoopScheduler.Task {
    private final long gated;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final Exception failure;

    GatedTask(long gated, Exception failure) {
      this.gated = gated;
      this.failure = failure;
    }

    @Override
    public long execute(long sequence, boolean warmup) throws Exception {
      if (sequence == gated) {
        // Interrupting the thread terminates this wait by throwing an InterruptedException.
        gate.await();
        if (failure != null) {
          throw failure;
        }
      }
      return 100;
    }

    @Override
    public String responseOperation(long sequence) {
      return RESPONSE;
    }
  }

  private OpenLoopScheduler scheduler(FakeClock clock, int durationMs, GatedTask task) {
    OpenLoopScheduler scheduler = new OpenLoopScheduler(openLoop(stage(durationMs, 1000)), 1, 0,
      task, "s", 2, Collections.singletonList("query:s:q"), clock);
    clock.attach(scheduler);
    return scheduler;
  }

  private File begin(String label) throws Exception {
    HdrFiles.begin(folder.getRoot(), label);
    return new File(folder.getRoot(), "LATENCY_" + label + ".json");
  }

  /**
   * Completes op0, leaves op1 in flight, and places op2 into the queue. The fake clock advances
   * only after the schedule has started, ensuring that the initial start time t0 remains 0.
   */
  private static void holdOneQueueOne(OpenLoopScheduler scheduler, FakeClock clock) {
    awaitState(scheduler, s -> s.getCompleted() == 1);
    clock.advanceTo(1000);
    awaitState(scheduler, s -> s.getInFlight() == 1);
    clock.advanceTo(2000);
    awaitState(scheduler, s -> s.getQueueDepth() == 1);
  }

  /** Validates the generated report for a single schedule and returns its schedule entry. */
  private static JsonObject assertReport(File json, String stopReason, long scheduled,
    long completed, long aborted) throws Exception {
    FakeClock.assertNoWorkers();
    JsonObject report = HdrFiles.json(json);
    assertEquals(LatencyReport.FORMAT, report.get("format").getAsString());
    assertEquals(1, report.get("version").getAsInt());
    JsonArray schedules = report.getAsJsonArray("schedules");
    assertEquals(1, schedules.size());
    JsonObject entry = schedules.get(0).getAsJsonObject();
    assertEquals(stopReason, entry.get("stopReason").getAsString());
    assertEquals(OpenLoopState.DONE, entry.get("phase").getAsString());
    assertEquals("s", entry.get("scenario").getAsString());
    assertEquals(2, entry.get("querySet").getAsInt());
    assertEquals(1, entry.get("concurrency").getAsInt());
    assertEquals("query:s:q", entry.getAsJsonArray("operations").get(0).getAsString());
    assertEquals(0, entry.get("pending").getAsLong());
    assertEquals(0, entry.get("queueDepth").getAsLong());
    assertEquals(0, entry.get("inFlight").getAsLong());
    assertEquals(scheduled, entry.get("scheduled").getAsLong());
    assertEquals(completed, entry.get("completed").getAsLong());
    assertEquals(aborted, entry.get("aborted").getAsLong());
    assertEquals(entry.get("scheduled").getAsLong(), entry.get("completed").getAsLong()
      + entry.get("dropped").getAsLong() + entry.get("aborted").getAsLong());
    assertTrue(entry.get("endMs").getAsLong() > 0);
    // Schedule entries exclude inline histograms because recorded samples reside in the keyed
    // histograms section of the report.
    assertTrue(entry.toString(), !entry.has("service") && !entry.has("response"));
    JsonObject response =
      HdrFiles.histograms(report).get(LatencyReport.tag(RESPONSE, LatencyRecorders.STEADY));
    assertEquals(completed, response == null ? 0 : response.get("count").getAsLong());
    return entry;
  }

  @Test
  public void testDurationDrainsTheSchedule() throws Exception {
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, 5, new GatedTask(-1, null));
    File json = begin("duration");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      awaitState(scheduler, s -> s.getScheduled() == 1);
      clock.advanceTo(5000);
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(OpenLoopState.DURATION, last.getStopReason());
    assertReport(json, OpenLoopState.DURATION, 5, 5, 0);
  }

  @Test
  public void testStopTimeHaltsAndAbortsTheQueue() throws Exception {
    FakeClock clock = new FakeClock(0);
    GatedTask task = new GatedTask(1, null);
    OpenLoopScheduler scheduler = scheduler(clock, 10, task).stopAt(2500);
    File json = begin("stopTime");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      holdOneQueueOne(scheduler, clock);
      clock.advanceTo(2500);
      // Halting the schedule aborts the queued op2 while allowing the in-flight op1 to complete.
      awaitState(scheduler, s -> s.getAborted() == 1 && s.getInFlight() == 1);
      task.gate.countDown();
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(OpenLoopState.STOP_TIME, last.getStopReason());
    assertReport(json, OpenLoopState.STOP_TIME, 3, 2, 1);
  }

  @Test
  public void testPredicateHalts() throws Exception {
    FakeClock clock = new FakeClock(0);
    AtomicBoolean stop = new AtomicBoolean();
    OpenLoopScheduler scheduler = scheduler(clock, 10, new GatedTask(-1, null)).stopWhen(stop::get);
    File json = begin("predicate");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      awaitState(scheduler, s -> s.getCompleted() == 1);
      clock.advanceTo(1000);
      awaitState(scheduler, s -> s.getCompleted() == 2);
      stop.set(true);
      clock.advanceTo(1500);
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(OpenLoopState.PREDICATE, last.getStopReason());
    assertReport(json, OpenLoopState.PREDICATE, 2, 2, 0);
  }

  /**
   * Verifies that cancel() interrupts the blocked op1 and returns only after the report reflects
   * the final schedule state.
   */
  @Test
  public void testCancelInterruptsAndReportsBeforeReturn() throws Exception {
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, 10, new GatedTask(1, null));
    File json = begin("cancel");
    FakeClock.Run run;
    try {
      run = new FakeClock.Run(scheduler);
      holdOneQueueOne(scheduler, clock);
      scheduler.cancel();
    } finally {
      // Finalize recorders immediately after cancel(), matching the Pherf teardown sequence.
      LatencyRecorders.finish();
    }
    OpenLoopState last = run.result();
    assertEquals(OpenLoopState.CANCEL, last.getStopReason());
    assertReport(json, OpenLoopState.CANCEL, 3, 1, 2);
  }

  /**
   * Arrivals due at 3, 4, and 5 ms remain backed up behind the full queue. Canceling the schedule
   * at 5 ms accounts for those arrivals as both scheduled and aborted, alongside the queued op2 and
   * the interrupted op1.
   */
  @Test
  public void testCancelCountsTheHeldBackArrivals() throws Exception {
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, 10, new GatedTask(1, null));
    File json = begin("backlog");
    FakeClock.Run run;
    try {
      run = new FakeClock.Run(scheduler);
      holdOneQueueOne(scheduler, clock);
      clock.advanceTo(5000);
      awaitState(scheduler, s -> s.getScheduled() == 3 && s.getDriftUs() == 3000);
      scheduler.cancel();
    } finally {
      LatencyRecorders.finish();
    }
    OpenLoopState last = run.result();
    assertEquals(OpenLoopState.CANCEL, last.getStopReason());
    assertReport(json, OpenLoopState.CANCEL, 6, 1, 5);
  }

  @Test
  public void testCancelBeforeRunStopsAtOnce() throws Exception {
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, 10, new GatedTask(-1, null));
    scheduler.cancel();
    OpenLoopState last = scheduler.run();
    assertEquals(OpenLoopState.CANCEL, last.getStopReason());
    assertEquals(0, last.getScheduled());
    assertEquals(OpenLoopState.DONE, last.getPhase());
    FakeClock.assertNoWorkers();
  }

  @Test
  public void testInterruptThrowsAfterTheReport() throws Exception {
    FakeClock clock = new FakeClock(0);
    OpenLoopScheduler scheduler = scheduler(clock, 10, new GatedTask(1, null));
    File json = begin("interrupt");
    Throwable error;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      holdOneQueueOne(scheduler, clock);
      run.interrupt();
      error = run.error();
    } finally {
      LatencyRecorders.finish();
    }
    assertTrue(String.valueOf(error), error instanceof InterruptedException);
    assertReport(json, OpenLoopState.INTERRUPT, 3, 1, 2);
  }

  @Test
  public void testErrorRethrowsTheFirstFailure() throws Exception {
    FakeClock clock = new FakeClock(0);
    IllegalStateException failure = new IllegalStateException("op1 failed");
    GatedTask task = new GatedTask(1, failure);
    OpenLoopScheduler scheduler = scheduler(clock, 10, task);
    File json = begin("error");
    Throwable error;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      holdOneQueueOne(scheduler, clock);
      task.gate.countDown();
      error = run.error();
    } finally {
      LatencyRecorders.finish();
    }
    assertSame(failure, error);
    assertReport(json, OpenLoopState.ERROR, 3, 1, 2);
  }
}
