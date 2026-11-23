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
package org.apache.phoenix.pherf.distributed.protocol;

import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.latency.LatencyRecorders;

/**
 * Encapsulates the configuration parameters for an HDR histogram latency recorder, with all latency
 * bounds expressed in microseconds.
 */
public final class RecorderConfig {
  /**
   * Defines the canonical recorder configuration shared uniformly across all Pherf latency
   * recorders.
   */
  public static final RecorderConfig PINNED = new RecorderConfig(LatencyRecorders.LOWEST_US,
    LatencyRecorders.HIGHEST_US, LatencyRecorders.DIGITS);

  private long lowestUs;
  private long highestUs;
  private int significantDigits;

  private RecorderConfig() {
  }

  public RecorderConfig(long lowestUs, long highestUs, int significantDigits) {
    this.lowestUs = lowestUs;
    this.highestUs = highestUs;
    this.significantDigits = significantDigits;
  }

  /**
   * Extracts a configuration snapshot reflecting the actual parameters of the given histogram.
   */
  public static RecorderConfig of(Histogram histogram) {
    return new RecorderConfig(histogram.getLowestDiscernibleValue(),
      histogram.getHighestTrackableValue(), histogram.getNumberOfSignificantValueDigits());
  }

  public long getLowestUs() {
    return lowestUs;
  }

  public long getHighestUs() {
    return highestUs;
  }

  public int getSignificantDigits() {
    return significantDigits;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof RecorderConfig)) {
      return false;
    }
    RecorderConfig that = (RecorderConfig) other;
    return lowestUs == that.lowestUs && highestUs == that.highestUs
      && significantDigits == that.significantDigits;
  }

  @Override
  public int hashCode() {
    return (int) (31 * (31 * lowestUs + highestUs) + significantDigits);
  }

  @Override
  public String toString() {
    return lowestUs + " us to " + highestUs + " us, " + significantDigits + " digits";
  }
}
