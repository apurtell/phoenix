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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.pherf.latency.HdrFiles;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.workload.StartGate;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

/**
 * Verifies the start gate synchronization behavior for open-loop execution, ensuring that the
 * dispatcher invokes the gate after all warmup operations complete and before the schedule starts.
 * Because the gate is static, each test clears it after execution.
 */
public class StartGateSchedulerTest {
  @Rule
  public Timeout timeout = Timeout.seconds(120);

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  @After
  public void clearGate() {
    StartGate.set(null);
  }

  private OpenLoopScheduler.Task task() {
    return new OpenLoopScheduler.Task() {
      @Override
      public long execute(long sequence, boolean warmup) {
        calls.add((warmup ? "w" : "s") + sequence);
        return 0;
      }

      @Override
      public String responseOperation(long sequence) {
        return "response:s:q";
      }
    };
  }

  /**
   * Configures a start gate that records its invocation and holds the dispatcher until the test
   * releases it.
   */
  private void holdingGate() {
    StartGate.set(() -> {
      calls.add("gate");
      entered.countDown();
      release.await();
    });
  }

  @Test
  public void gateHoldsTheScheduleAfterTheWarmup() throws Exception {
    holdingGate();
    FakeClock clock = new FakeClock(5000);
    OpenLoopScheduler scheduler =
      OpenLoopSchedulerTest.scheduler(clock, openLoop(stage(2, 1000)), 2, 2, task());
    HdrFiles.begin(folder.getRoot(), "gate");
    OpenLoopState last;
    long releasedMs;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      assertTrue(entered.await(FakeClock.GUARD_MS, TimeUnit.MILLISECONDS));
      // Verify that the schedule remains unstarted while the dispatcher is held at the gate.
      OpenLoopState held = scheduler.state();
      assertEquals(OpenLoopState.WARMUP, held.getPhase());
      assertEquals(0, held.getScheduled());
      assertEquals(0, held.getStartMs());
      assertEquals(Arrays.asList("w0", "w0", "w1", "w1", "gate"), sorted(calls));
      releasedMs = System.currentTimeMillis();
      release.countDown();
      awaitState(scheduler, s -> s.getCompleted() == 1);
      clock.advanceTo(7000);
      last = run.result();
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals("gate", calls.get(4));
    assertEquals(Arrays.asList("s0", "s1"), calls.subList(5, 7));
    assertEquals(2, last.getCompleted());
    assertTrue(last.getStartMs() + " < " + releasedMs, last.getStartMs() >= releasedMs);
  }

  /**
   * Verifies that cancelling the scheduler while held at the gate terminates the run, preventing
   * any steady-state operation from executing after the gate is released.
   */
  @Test
  public void cancelDuringTheHoldStartsNothing() throws Exception {
    holdingGate();
    FakeClock clock = new FakeClock(5000);
    OpenLoopScheduler scheduler =
      OpenLoopSchedulerTest.scheduler(clock, openLoop(stage(2, 1000)), 1, 1, task());
    HdrFiles.begin(folder.getRoot(), "gatecancel");
    OpenLoopState last;
    try {
      FakeClock.Run run = new FakeClock.Run(scheduler);
      assertTrue(entered.await(FakeClock.GUARD_MS, TimeUnit.MILLISECONDS));
      Thread canceller = new Thread(scheduler::cancel, "test-cancel");
      canceller.setDaemon(true);
      canceller.start();
      awaitState(scheduler, s -> OpenLoopState.CANCEL.equals(s.getStopReason()));
      release.countDown();
      last = run.result();
      canceller.join(FakeClock.GUARD_MS);
    } finally {
      LatencyRecorders.finish();
    }
    assertEquals(Arrays.asList("w0", "gate"), calls);
    assertEquals(0, last.getScheduled());
    assertEquals(OpenLoopState.CANCEL, last.getStopReason());
  }

  /**
   * Normalizes the recorded call sequence to account for worker warmup operations executing in
   * arbitrary order prior to the start gate invocation.
   */
  private static List<String> sorted(List<String> calls) {
    List<String> copy;
    synchronized (calls) {
      copy = new ArrayList<>(calls);
    }
    Collections.sort(copy.subList(0, copy.size() - 1));
    return copy;
  }
}
