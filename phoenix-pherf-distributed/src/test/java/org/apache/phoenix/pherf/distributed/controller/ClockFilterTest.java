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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.phoenix.pherf.distributed.protocol.ClockSample;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.junit.Test;

/**
 * Verifies the NTP-style clock offset estimation performed by the clock filter for RQ-231, using
 * the controller clock as the reference time source.
 */
public class ClockFilterTest {
  private static final long SKEW = 98765;
  private long trueMs = 1767225600000L;

  /**
   * Simulates a single clock sample exchange in which the request takes upMs to reach the
   * controller, the controller holds it for holdMs, and the reply takes downMs to return, while the
   * agent clock runs SKEW ahead of the controller clock.
   */
  private ClockSample exchange(long upMs, long holdMs, long downMs) {
    long sent = trueMs + SKEW;
    long rx = trueMs + upMs;
    long tx = rx + holdMs;
    long received = tx + downMs + SKEW;
    trueMs = tx + downMs + 10;
    return new ClockSample(sent, rx, tx, received);
  }

  @Test
  public void errorIsWithinHalfTheDelay() {
    long[][] delays = { { 5, 45 }, { 45, 0 }, { 13, 13 }, { 0, 1 }, { 30, 2 } };
    for (long[] delay : delays) {
      ClockFilter filter = new ClockFilter();
      filter.add(exchange(delay[0], 1000, delay[1]));
      long[] best = filter.best();
      assertEquals(delay[0] + delay[1], best[1]);
      long error = Math.abs(best[0] - SKEW);
      assertTrue(error + " for " + delay[0] + "/" + delay[1], error <= best[1] / 2 + 1);
    }
    // A symmetric network delay yields the exact clock skew regardless of the controller hold
    // duration.
    ClockFilter filter = new ClockFilter();
    filter.add(exchange(7, 999, 7));
    assertEquals(SKEW, filter.best()[0]);
  }

  @Test
  public void leastDelayWinsAndTheLatestOnATie() {
    ClockFilter filter = new ClockFilter();
    filter.add(exchange(40, 1000, 0));
    filter.add(exchange(3, 1000, 1));
    filter.add(exchange(30, 1000, 30));
    assertArrayEquals(new long[] { SKEW - 1, 4 }, filter.best());
    // When a subsequent sample achieves the same total round-trip delay split in the opposite
    // direction, the most recent sample takes precedence.
    filter.add(exchange(1, 1000, 3));
    assertArrayEquals(new long[] { SKEW + 1, 4 }, filter.best());
  }

  @Test
  public void windowKeepsTheLastSamples() {
    ClockFilter filter = new ClockFilter();
    filter.add(exchange(1, 1000, 1));
    for (int i = 0; i < Protocol.CLOCK_SAMPLES - 1; i++) {
      filter.add(exchange(10, 1000, 10));
    }
    assertEquals(2, filter.best()[1]);
    filter.add(exchange(10, 1000, 10));
    assertEquals(20, filter.best()[1]);
  }

  @Test
  public void invalidSamplesAreIgnored() {
    ClockFilter filter = new ClockFilter();
    assertNull(filter.best());
    // A sample in which the reply departed before the request arrived is ignored.
    filter.add(new ClockSample(1000, 500, 499, 1600));
    // A sample in which the total elapsed exchange time is shorter than the controller hold time
    // is ignored.
    filter.add(new ClockSample(1000, 500, 900, 1300));
    assertNull(filter.best());
    filter.add(new ClockSample(1000, 500, 900, 1500));
    assertArrayEquals(new long[] { 550, 100 }, filter.best());
  }
}
