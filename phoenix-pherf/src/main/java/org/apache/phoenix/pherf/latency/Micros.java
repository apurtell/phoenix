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
package org.apache.phoenix.pherf.latency;

import org.apache.phoenix.util.DefaultEnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;

/**
 * Provides the microsecond clock used for latency measurements along with rounding conversion to
 * millisecond values for legacy reports. Time resolution adapts to the active delegate configured
 * in {@link EnvironmentEdgeManager}:
 * <ul>
 * <li>An edge implementing {@link Source} supplies its own microsecond readings directly.</li>
 * <li>The default edge converts {@link System#nanoTime()} into microseconds to provide a monotonic
 * time source.</li>
 * <li>Any other edge scales its millisecond timestamp by 1000, ensuring that tests injecting a
 * constant clock measure an elapsed time of 0.</li>
 * </ul>
 */
public final class Micros {

  /** An environment edge capable of supplying microsecond-resolution timestamps directly. */
  public interface Source {
    long currentTimeMicros();
  }

  private Micros() {
  }

  /**
   * Returns the current reading from the microsecond clock. Only the elapsed difference between two
   * readings has semantic meaning.
   */
  public static long now() {
    EnvironmentEdge edge = EnvironmentEdgeManager.getDelegate();
    if (edge instanceof Source) {
      return ((Source) edge).currentTimeMicros();
    }
    // Match the exact runtime class because any subclass or mock of the default edge represents an
    // injected test clock.
    if (edge.getClass() == DefaultEnvironmentEdge.class) {
      return System.nanoTime() / 1000;
    }
    return edge.currentTime() * 1000;
  }

  /**
   * Converts a microsecond duration to milliseconds using half-up rounding, returning 0 for any
   * value of 0 or less.
   */
  public static long toMillis(long micros) {
    return micros <= 0 ? 0 : (micros + 500) / 1000;
  }
}
