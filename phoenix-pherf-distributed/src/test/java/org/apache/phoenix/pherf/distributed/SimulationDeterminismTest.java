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
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.junit.Test;

/**
 * Verifies requirement RQ-225 by confirming that executing the same simulation script consistently
 * produces identical bytes on the wire.
 */
public class SimulationDeterminismTest {

  private static List<String> script() {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.agent("a3");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "4");
    String first = simulation.controller.submit(Simulation.spec(11, properties, 2, 3));
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(first).getState());
    properties.put("fake.fail.1", "2");
    String second = simulation.controller.submit(Simulation.spec(12, properties, 3));
    simulation.runFor(10000);
    assertEquals(RunView.State.FAILED, simulation.run(second).getState());
    simulation.controller.shutdownAgent("a3");
    simulation.runFor(2000);
    return simulation.network.log();
  }

  @Test
  public void sameScriptSameWire() {
    List<String> first = script();
    List<String> second = script();
    assertTrue(first.size() > 100);
    assertEquals(String.join("\n", first), String.join("\n", second));
  }
}
