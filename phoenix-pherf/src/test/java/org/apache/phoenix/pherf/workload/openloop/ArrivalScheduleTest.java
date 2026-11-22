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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.junit.Test;

/**
 * Verifies RQ-169 by validating the intended dispatch offsets generated for constant, step, linear
 * ramp, and seeded Poisson arrival schedules. Because these dispatch offsets are pure functions of
 * the workload configuration, these tests evaluate the schedule directly without requiring a clock.
 */
public class ArrivalScheduleTest {

  static OpenLoop.Stage stage(long durationMs, double rate, Double endRate) {
    OpenLoop.Stage stage = new OpenLoop.Stage();
    stage.setDurationMs(durationMs);
    stage.setRate(rate);
    stage.setEndRate(endRate);
    return stage;
  }

  static OpenLoop.Stage stage(long durationMs, double rate) {
    return stage(durationMs, rate, null);
  }

  static OpenLoop openLoop(OpenLoop.Stage... stages) {
    OpenLoop openLoop = new OpenLoop();
    openLoop.setStage(new ArrayList<>(Arrays.asList(stages)));
    return openLoop;
  }

  static OpenLoop poisson(long seed, OpenLoop.Stage... stages) {
    OpenLoop openLoop = openLoop(stages);
    openLoop.setArrival(OpenLoop.Arrival.POISSON);
    openLoop.setSeed(seed);
    return openLoop;
  }

  /** Collects and returns every scheduled dispatch offset produced until reaching END. */
  static List<Long> offsets(OpenLoop openLoop) {
    List<Long> offsets = new ArrayList<>();
    ArrivalSchedule.Cursor cursor = ArrivalSchedule.of(openLoop).cursor();
    for (long offset = cursor.next(); offset != ArrivalSchedule.END; offset = cursor.next()) {
      offsets.add(offset);
    }
    return offsets;
  }

  @Test
  public void testConstantIntervalsAreUniform() {
    List<Long> offsets = offsets(openLoop(stage(1000, 1000)));
    assertEquals(1000, offsets.size());
    for (int k = 0; k < offsets.size(); k++) {
      assertEquals(k * 1000L, (long) offsets.get(k));
    }
    // For a rate whose period is not an integer number of microseconds (us), verify that each
    // generated interval remains within 1 us of the ideal 1/3 s duration.
    List<Long> thirds = offsets(openLoop(stage(10_000, 3)));
    assertEquals(30, thirds.size());
    assertEquals(0L, (long) thirds.get(0));
    for (int k = 1; k < thirds.size(); k++) {
      long interval = thirds.get(k) - thirds.get(k - 1);
      assertTrue("interval " + interval, Math.abs(interval - 1_000_000.0 / 3) <= 1);
    }
  }

  @Test
  public void testStepReachesEachRateAtItsBoundary() {
    ArrivalSchedule schedule =
      ArrivalSchedule.of(openLoop(stage(1000, 100), stage(1000, 200), stage(1000, 50)));
    assertEquals(3_000_000, schedule.durationUs());
    List<Long> offsets = offsets(openLoop(stage(1000, 100), stage(1000, 200), stage(1000, 50)));
    assertEquals(100 + 200 + 50, offsets.size());
    // The initial arrival of each stage occurs precisely at the start boundary of that stage.
    assertEquals(0L, (long) offsets.get(0));
    assertEquals(1_000_000L, (long) offsets.get(100));
    assertEquals(2_000_000L, (long) offsets.get(300));
    assertIntervals(offsets.subList(0, 101), 10_000);
    assertIntervals(offsets.subList(100, 301), 5_000);
    assertIntervals(offsets.subList(300, 350), 20_000);
    assertEquals(100.0, schedule.rateAt(999_999), 0);
    assertEquals(200.0, schedule.rateAt(1_000_000), 0);
    assertEquals(50.0, schedule.rateAt(2_000_000), 0);
  }

  @Test
  public void testRampReachesTheEndRateAtTheBoundary() {
    OpenLoop ramp = openLoop(stage(2000, 100, 300.0), stage(1000, 300));
    ArrivalSchedule schedule = ArrivalSchedule.of(ramp);
    List<Long> offsets = offsets(ramp);
    // The linear ramp stage is expected to produce (100 + 300) / 2 x 2 s = 400 arrivals, while
    // the subsequent flat stage produces 300 arrivals.
    int inRamp = 0;
    for (long offset : offsets) {
      inRamp += offset < 2_000_000 ? 1 : 0;
    }
    assertEquals(400, inRamp);
    assertEquals(700, offsets.size());
    assertEquals(2_000_000L, (long) offsets.get(400));
    assertEquals(100.0, schedule.rateAt(0), 0);
    assertEquals(200.0, schedule.rateAt(1_000_000), 1e-9);
    assertEquals(300.0, schedule.rateAt(2_000_000), 0);
    assertEquals(0.0, schedule.rateAt(3_000_000), 0);
    assertEquals(0.0, schedule.rateAt(-1), 0);
    // The instantaneous local rate approaching the stage boundary matches the configured end
    // rate within a 1% tolerance.
    double local = 1_000_000.0 / (offsets.get(399) - offsets.get(398));
    assertEquals(300, local, 3);
    // The instantaneous local rate near the beginning of the stage matches the configured start
    // rate within a 1% tolerance.
    assertEquals(100, 1_000_000.0 / (offsets.get(1) - offsets.get(0)), 1);
    // Inter-arrival intervals decrease monotonically across the entire ascending ramp, subject to
    // microsecond rounding of up to 1 us per offset.
    for (int k = 2; k < 400; k++) {
      assertTrue("at " + k,
        offsets.get(k) - offsets.get(k - 1) <= offsets.get(k - 1) - offsets.get(k - 2) + 2);
    }
  }

  @Test
  public void testFallingRampAndRampFromZero() {
    List<Long> falling = offsets(openLoop(stage(1000, 300, 100.0)));
    assertEquals(200, falling.size());
    assertEquals(300, 1_000_000.0 / (falling.get(2) - falling.get(1)), 3);
    List<Long> rising = offsets(openLoop(stage(1000, 0, 200.0)));
    assertEquals(100, rising.size());
    assertEquals(0L, (long) rising.get(0));
    assertTrue(rising.get(99) < 1_000_000);
  }

  @Test
  public void testPauseStageIsSkippedAndEndIsFinal() {
    OpenLoop paused = openLoop(stage(1000, 100), stage(1000, 0), stage(1000, 100));
    List<Long> offsets = offsets(paused);
    assertEquals(200, offsets.size());
    assertEquals(990_000L, (long) offsets.get(99));
    assertEquals(2_000_000L, (long) offsets.get(100));
    assertEquals(2_990_000L, (long) offsets.get(199));
    assertEquals(0.0, ArrivalSchedule.of(paused).rateAt(1_500_000), 0);
    ArrivalSchedule.Cursor cursor = ArrivalSchedule.of(openLoop(stage(2, 1000))).cursor();
    assertEquals(0, cursor.next());
    assertEquals(1000, cursor.next());
    assertEquals(ArrivalSchedule.END, cursor.next());
    assertEquals(ArrivalSchedule.END, cursor.next());
  }

  @Test
  public void testPoissonSeedReproducesTheSequence() {
    List<Long> first = offsets(poisson(7, stage(20_000, 1000)));
    List<Long> again = offsets(poisson(7, stage(20_000, 1000)));
    List<Long> other = offsets(poisson(8, stage(20_000, 1000)));
    assertTrue("count " + first.size(), first.size() > 10_000);
    first = first.subList(0, 10_000);
    assertEquals(first, again.subList(0, 10_000));
    assertFalse(first.equals(other.subList(0, 10_000)));
    // The empirical mean inter-arrival interval of the seeded sequence converges to 1 ms within a
    // 5% tolerance.
    double mean = first.get(first.size() - 1) / (double) first.size();
    assertEquals(1000, mean, 50);
    // Generated arrivals must be ordered chronologically and remain bounded within the schedule
    // window.
    for (int k = 1; k < first.size(); k++) {
      assertTrue(first.get(k) >= first.get(k - 1));
    }
    assertTrue(first.get(0) > 0);
  }

  @Test
  public void testPoissonFollowsTheStageRates() {
    // When stepping from 100/s to 1000/s, the second period receives approximately ten times the
    // number of arrivals as the first.
    List<Long> offsets = offsets(poisson(11, stage(10_000, 100), stage(10_000, 1000)));
    int low = 0;
    for (long offset : offsets) {
      low += offset < 10_000_000 ? 1 : 0;
    }
    int high = offsets.size() - low;
    assertEquals(1000, low, 100);
    assertEquals(10_000, high, 300);
  }

  private static void assertIntervals(List<Long> offsets, long interval) {
    for (int k = 1; k < offsets.size(); k++) {
      assertEquals("at " + k, interval, offsets.get(k) - offsets.get(k - 1));
    }
  }
}
