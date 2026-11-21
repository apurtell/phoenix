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
package org.apache.phoenix.pherf.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that every scenario fixture is parsed by XMLConfigParser and executes its write
 * workload, query workload, and load profiles without requiring additional configuration, while
 * ensuring that the fixture declaring a DTD continues to be rejected. Because the test enumerates
 * the fixture directory dynamically at runtime, any newly added fixture is automatically covered.
 * <p>
 * Three load profiles within a single fixture cannot initialize a load generator solely from the
 * scenario definition: scenario_11 configures the UNIFORM generator with three tenant groups even
 * though that generator only accepts one, whereas scenario_12 and scenario_13 do not specify a
 * generator name because their dedicated tests supply the generator programmatically in code. This
 * test confirms that only those three profiles are rejected and that each fails with its expected
 * error.
 */
public class ScenarioFixturesRunTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  private static final String PROFILES = "test_workload_with_load_profile.xml:";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  @Test
  public void testEveryFixtureLoadsAndRuns() throws Exception {
    File[] files = ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"));
    Arrays.sort(files);
    List<String> paths = new ArrayList<>();
    for (File file : files) {
      paths.add(file.getPath());
    }
    assertTrue("Fixture directory has too few files: " + paths, paths.size() > 10);

    ChildJvm child = new ChildJvm(folder.getRoot());
    child.runFixtures(paths);

    Map<String, String> outcomes = new HashMap<>();
    for (String line : Records.read(child.record(Records.OUTCOME))) {
      String[] fields = line.split("\t", 3);
      if (fields.length > 1) {
        outcomes.put(fields[0], fields[1] + (fields.length > 2 ? "\t" + fields[2] : ""));
      }
    }
    List<List<String>> connections = Records.byInvocation(child.record(Records.CONNECTIONS));
    assertEquals(files.length, connections.size());
    for (int i = 0; i < files.length; i++) {
      String name = files[i].getName();
      boolean dtd = DTD_FIXTURE.equals(name);
      String outcome = outcomes.get(name);
      assertTrue(name + ": " + outcome,
        outcome != null && outcome.split("\t")[0].equals(dtd ? ChildRun.REJECTED : ChildRun.RAN));
      // An executed fixture establishes connections, whereas the rejected fixture opens none.
      assertEquals(name + " connections", dtd, connections.get(i).isEmpty());
    }
    String noGenerator = ChildRun.REJECTED + "\tjava.lang.NullPointerException: Name is null";
    assertEquals(
      ChildRun.REJECTED
        + "\tjava.lang.IllegalArgumentException: Tenant group cannot be more than 1",
      outcomes.get(PROFILES + "scenario_11"));
    assertEquals(noGenerator, outcomes.get(PROFILES + "scenario_12"));
    assertEquals(noGenerator, outcomes.get(PROFILES + "scenario_13"));
    assertEquals("Unexpected outcomes: " + outcomes, files.length + 3, outcomes.size());
  }
}
