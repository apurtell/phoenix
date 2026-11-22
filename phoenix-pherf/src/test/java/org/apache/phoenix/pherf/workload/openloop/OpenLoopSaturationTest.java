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
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Row;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Schedule;
import org.apache.phoenix.pherf.workload.openloop.OpenLoopSchedulerTest.FakeTask;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Exercises the attainment and saturation health verdict checks for RQ-202 and RQ-203 against the
 * terminal state produced by the real open-loop scheduler driven by a fake clock. Each scenario
 * runs with a concurrency limit of C=1, an arrival rate of 1000 arrivals per second over an 11 ms
 * stage, no scheduler lag limit, and a maximum permissible peak lag bound of 5 ms.
 */
public class OpenLoopSaturationTest {
  private static final long LAG_MAX_US = 5_000;

  @Rule
  public Timeout timeout = Timeout.seconds(120);

  private static Row saturation(OpenLoopState last) {
    return HealthVerdict.saturation(Collections.singletonList(Schedule.of(last)), LAG_MAX_US);
  }

  /**
   * Verifies that when op0 stalls until the clock reaches 10 ms, op1 is forced to wait 9 ms in a
   * full queue, causing the saturation check to fail.
   */
  @Test
  public void testStallFailsTheSaturationCheck() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask().stall(0);
    OpenLoopScheduler scheduler =
      OpenLoopSchedulerTest.scheduler(clock, openLoop(stage(11, 1000)), 1, 0, task);
    FakeClock.Run run = new FakeClock.Run(scheduler);
    awaitState(scheduler, s -> s.getInFlight() == 1 && s.getScheduled() == 1);
    clock.advanceTo(10_000);
    awaitState(scheduler, s -> s.getQueueDepth() == 1 && s.getScheduled() == 2);
    task.release(0);
    awaitState(scheduler, s -> s.getCompleted() == 11);
    clock.advanceTo(11_000);
    OpenLoopState last = run.result();
    assertEquals(9000, last.getMaxDriftUs());
    assertEquals(1, last.getMaxQueueDepth());
    assertEquals(11, last.getDurationMs());
    Row row = saturation(last);
    assertEquals(row.toString(), HealthVerdict.Severity.FAIL, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains("9 ms"));
    assertTrue(row.getDetail(), row.getDetail().contains("1 of 1"));
    assertTrue(row.getDetail(), row.getDetail().contains("scenario s query set 1"));
  }

  /**
   * Verifies for RQ-202 that when the last operation takes 490 ms and finishes long after the 11 ms
   * scheduling window, the attainment check still passes because every arrival was taken on time
   * and the drain phase does not decrease attainment. The wall clock of the scheduler tracks the
   * fake clock starting from an epoch time of 1000 s.
   */
  @Test
  public void testSlowLastOperationPassesTheAttainmentCheck() throws Exception {
    FakeClock clock = new FakeClock(0);
    EnvironmentEdgeManager.injectEdge(new EnvironmentEdge() {
      @Override
      public long currentTime() {
        return 1_000_000 + clock.nowMicros() / 1000;
      }
    });
    try {
      FakeTask task = new FakeTask().stall(10);
      OpenLoopScheduler scheduler =
        OpenLoopSchedulerTest.scheduler(clock, openLoop(stage(11, 1000)), 1, 0, task);
      FakeClock.Run run = new FakeClock.Run(scheduler);
      for (int k = 1; k <= 10; k++) {
        int done = k;
        awaitState(scheduler, s -> s.getCompleted() == done);
        clock.advanceTo(k * 1000L);
      }
      awaitState(scheduler, s -> s.getInFlight() == 1 && s.getScheduled() == 11);
      clock.advanceTo(500_000);
      task.release(10);
      OpenLoopState last = run.result();
      assertEquals(OpenLoopState.DURATION, last.getStopReason());
      assertEquals(11, last.getScheduled());
      assertEquals(11, last.getCompleted());
      assertEquals(0, last.getLate());
      assertEquals(10, last.getLastTakeMs() - last.getStartMs());
      assertEquals(500, last.getEndMs() - last.getStartMs());
      Row row = HealthVerdict.attainment(Collections.singletonList(Schedule.of(last)), 90);
      assertEquals(row.toString(), HealthVerdict.Severity.PASS, row.getSeverity());
      assertTrue(row.getDetail(), row.getDetail().contains("100.0%"));
    } finally {
      EnvironmentEdgeManager.reset();
    }
  }

  /**
   * Verifies that when no operation stalls, each operation executes at its intended arrival time
   * and the saturation check passes.
   */
  @Test
  public void testSteadyRunPassesTheSaturationCheck() throws Exception {
    FakeClock clock = new FakeClock(0);
    FakeTask task = new FakeTask();
    OpenLoopScheduler scheduler =
      OpenLoopSchedulerTest.scheduler(clock, openLoop(stage(11, 1000)), 1, 0, task);
    FakeClock.Run run = new FakeClock.Run(scheduler);
    for (int k = 1; k <= 11; k++) {
      int done = k;
      awaitState(scheduler, s -> s.getCompleted() == done);
      clock.advanceTo(k * 1000L);
    }
    OpenLoopState last = run.result();
    assertEquals(11, last.getCompleted());
    assertEquals(0, last.getMaxDriftUs());
    assertEquals(11, last.getDurationMs());
    Row row = saturation(last);
    assertEquals(row.toString(), HealthVerdict.Severity.PASS, row.getSeverity());
  }
}
