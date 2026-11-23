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
package org.apache.phoenix.pherf.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies RQ-231 by confirming that the controller estimates the clock offset and round-trip delay
 * of each agent from the heartbeat exchanges on its control stream. Each agent host operates with
 * its own independent wall-clock skew and monotonic origin.
 */
public class ClockOffsetTest {
  private static final String[] AGENTS = { "a1", "a2", "a3" };
  private static final long[] SKEWS = { -4321, 0, 98765 };
  /** Defines the maximum injected network delay of 45 ms upstream and 10 ms downstream. */
  private static final long MAX_UP_MS = 45;
  private static final long MAX_DOWN_MS = 10;

  private static Simulation fleet(InProcessNetwork.Policy policy) {
    Simulation simulation = new Simulation();
    simulation.network.policy(policy);
    for (int i = 0; i < AGENTS.length; i++) {
      simulation.skewedAgent(AGENTS[i], SKEWS[i]);
    }
    simulation.runFor(15000);
    return simulation;
  }

  @Test
  public void offsetIsWithinTheInjectedDelayOfTheSkew() {
    Random random = new Random(8070);
    Simulation simulation = fleet((agent, stream, direction, message) -> {
      if (!stream.equals(Protocol.CONTROL)) {
        return 0;
      }
      return direction.equals(InProcessNetwork.REQUEST)
        ? 5 + random.nextInt((int) MAX_UP_MS - 4)
        : random.nextInt((int) MAX_DOWN_MS + 1);
    });
    for (int i = 0; i < AGENTS.length; i++) {
      AgentView view = simulation.view(AGENTS[i]);
      assertNotNull(view.getOffsetMs());
      long error = Math.abs(view.getOffsetMs() - SKEWS[i]);
      assertTrue(AGENTS[i] + " error " + error, error <= MAX_UP_MS);
      assertTrue(view.getDelayMs() + "", view.getDelayMs() >= 5);
      assertTrue(view.getDelayMs() + "", view.getDelayMs() <= MAX_UP_MS + MAX_DOWN_MS);
      assertTrue(AGENTS[i] + " error " + error, error <= view.getDelayMs() / 2 + 1);
    }
  }

  @Test
  public void symmetricDelayGivesTheExactSkew() {
    Simulation simulation = fleet((agent, stream, direction, message) -> 7);
    for (int i = 0; i < AGENTS.length; i++) {
      AgentView view = simulation.view(AGENTS[i]);
      assertEquals(Long.valueOf(SKEWS[i]), view.getOffsetMs());
      assertEquals(Long.valueOf(14), view.getDelayMs());
    }
  }
}
