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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.phoenix.util.DefaultEnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.junit.Test;
import org.mockito.Mockito;

public class MicrosTest {

  @Test
  public void testToMillisRoundsHalfUp() {
    assertEquals(3, Micros.toMillis(2_600));
    assertEquals(3, Micros.toMillis(2_500));
    assertEquals(2, Micros.toMillis(2_499));
    assertEquals(0, Micros.toMillis(300));
    assertEquals(0, Micros.toMillis(0));
    assertEquals(0, Micros.toMillis(-7));
  }

  @Test
  public void testDefaultEdgeIsMonotonic() throws Exception {
    EnvironmentEdgeManager.reset();
    long first = Micros.now();
    Thread.sleep(2);
    long second = Micros.now();
    assertTrue(second - first >= 2_000);
    assertTrue(second - first < 10_000_000);
  }

  @Test
  public void testPlainEdgeGivesMillisTimesThousand() {
    EnvironmentEdgeManager.injectEdge(new EnvironmentEdge() {
      @Override
      public long currentTime() {
        return 1234;
      }
    });
    try {
      assertEquals(1_234_000, Micros.now());
    } finally {
      EnvironmentEdgeManager.reset();
    }
  }

  @Test
  public void testMockedDefaultEdgeFollowsMock() {
    DefaultEnvironmentEdge clock = Mockito.mock(DefaultEnvironmentEdge.class);
    Mockito.when(clock.currentTime()).thenReturn(5L, 7L);
    EnvironmentEdgeManager.injectEdge(clock);
    try {
      assertEquals(5_000, Micros.now());
      assertEquals(7_000, Micros.now());
    } finally {
      EnvironmentEdgeManager.reset();
    }
  }

  @Test
  public void testSourceEdgeGivesItsMicros() {
    EnvironmentEdgeManager.injectEdge(new SourceEdge(42, 1_000_007));
    try {
      assertEquals(1_000_007, Micros.now());
    } finally {
      EnvironmentEdgeManager.reset();
    }
  }

  /** Provides an environment edge configured with fixed millisecond and microsecond values. */
  static class SourceEdge extends EnvironmentEdge implements Micros.Source {
    private final long millis;
    private final long micros;

    SourceEdge(long millis, long micros) {
      this.millis = millis;
      this.micros = micros;
    }

    @Override
    public long currentTime() {
      return millis;
    }

    @Override
    public long currentTimeMicros() {
      return micros;
    }
  }
}
