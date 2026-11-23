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

import java.util.ArrayDeque;
import org.apache.phoenix.pherf.distributed.protocol.ClockSample;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;

/**
 * Estimates the clock offset of a single agent using an NTP-style calculation with the controller
 * clock as the reference. For each exchange, t1 and t4 are the agent timestamps of the request and
 * the reply, while t2 and t3 are the corresponding controller timestamps. The clock offset (agent
 * minus controller) is ((t1 - t2) + (t4 - t3)) / 2, and the round-trip delay is (t4 - t1) - (t3 -
 * t2), ensuring that the hold duration of a long poll is excluded. The error of the offset is at
 * most delay / 2 + 1 ms. The filter retains the last {@link Protocol#CLOCK_SAMPLES} valid samples
 * and selects the one with the smallest delay, preferring the latest sample on a tie.
 */
final class ClockFilter {
  /** Stores retained samples as {offset, delay} pairs ordered from oldest to newest. */
  private final ArrayDeque<long[]> samples = new ArrayDeque<>();
  private ClockSample last;

  /**
   * Records a new clock sample. A sample that repeats the last sample or does not have a valid
   * delay is ignored.
   */
  void add(ClockSample sample) {
    long t1 = sample.getSentMs();
    long t2 = sample.getRxMs();
    long t3 = sample.getTxMs();
    long t4 = sample.getReceivedMs();
    if (t3 < t2 || last != null && t1 == last.getSentMs() && t4 == last.getReceivedMs()) {
      return;
    }
    long delay = (t4 - t1) - (t3 - t2);
    if (delay < 0) {
      return;
    }
    last = sample;
    samples.addLast(new long[] { Math.floorDiv((t1 - t2) + (t4 - t3), 2), delay });
    if (samples.size() > Protocol.CLOCK_SAMPLES) {
      samples.removeFirst();
    }
  }

  /**
   * Returns the offset and round-trip delay of the best sample as {offset, delay} in milliseconds,
   * or null if no valid samples have been recorded.
   */
  long[] best() {
    long[] best = null;
    for (long[] sample : samples) {
      if (best == null || sample[1] <= best[1]) {
        best = sample;
      }
    }
    return best;
  }
}
