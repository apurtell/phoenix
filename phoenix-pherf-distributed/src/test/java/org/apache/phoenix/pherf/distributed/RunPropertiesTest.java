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

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.junit.Test;

/**
 * Verifies requirement RQ-228 by ensuring that any run-level property is visible in the resolved
 * property set delivered to every participating agent.
 */
public class RunPropertiesTest {

  @Test
  public void runLevelPropertiesWinOverStepProperties() {
    Simulation simulation = new Simulation();
    for (String id : new String[] { "a1", "a2", "a3" }) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Plans.plan(1).property("shared", "run")
      .property("token", "${pherf.slot.prefix}")
      .step(
        Plans.step(3).scenario("s.xml", "<s/>").property("shared", "step0").property("only0", "a"))
      .step(
        Plans.step(2).scenario("s.xml", "<s/>").property("only1", "b").property("shared", "step1"))
      .spec());
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    int seen = 0;
    for (String id : new String[] { "a1", "a2", "a3" }) {
      for (Assignment assignment : simulation.workload(id).prepared) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("shared", "run");
        expected.put(assignment.getStep() == 0 ? "only0" : "only1",
          assignment.getStep() == 0 ? "a" : "b");
        expected.put("token", assignment.getIdentity().getPrefix());
        assertEquals(id + " step " + assignment.getStep(), expected, assignment.getProperties());
        seen++;
      }
    }
    assertEquals(5, seen);
  }
}
