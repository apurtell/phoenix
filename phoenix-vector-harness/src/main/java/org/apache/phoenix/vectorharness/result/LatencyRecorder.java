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
package org.apache.phoenix.vectorharness.result;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * A concurrent latency recorder over an HDR histogram, in microseconds, with three significant
 * digits from one microsecond to one hour. Writers record without locks; readers take interval
 * snapshots for time series and accumulate them into a total for the whole window.
 */
public final class LatencyRecorder {

  static final long MAX_MICROS = TimeUnit.HOURS.toMicros(1);

  private final Recorder recorder = new Recorder(1, MAX_MICROS, 3);
  private final Histogram total = new Histogram(1, MAX_MICROS, 3);
  private Histogram interval;

  /** Records one latency in nanoseconds; values beyond one hour are clamped. */
  public void recordNanos(long nanos) {
    long micros = Math.max(1, Math.min(MAX_MICROS, TimeUnit.NANOSECONDS.toMicros(nanos)));
    recorder.recordValue(micros);
  }

  /** Returns the latencies recorded since the previous call, and adds them to the total. */
  public synchronized Histogram interval() {
    interval = recorder.getIntervalHistogram(interval);
    total.add(interval);
    return interval.copy();
  }

  /** Returns all latencies recorded so far. */
  public synchronized Histogram total() {
    interval();
    return total.copy();
  }

  /** Summarizes a histogram in milliseconds. */
  public static Map<String, Object> summarize(Histogram h) {
    Map<String, Object> m = new LinkedHashMap<>();
    long n = h.getTotalCount();
    m.put("count", n);
    if (n == 0) {
      return m;
    }
    m.put("mean_ms", h.getMean() / 1000.0);
    m.put("p50_ms", h.getValueAtPercentile(50) / 1000.0);
    m.put("p95_ms", h.getValueAtPercentile(95) / 1000.0);
    m.put("p99_ms", h.getValueAtPercentile(99) / 1000.0);
    m.put("p999_ms", h.getValueAtPercentile(99.9) / 1000.0);
    m.put("max_ms", h.getMaxValue() / 1000.0);
    return m;
  }

  /** Returns a percentile of a histogram in milliseconds, or NaN if it is empty. */
  public static double percentileMillis(Histogram h, double percentile) {
    return h.getTotalCount() == 0 ? Double.NaN : h.getValueAtPercentile(percentile) / 1000.0;
  }
}
