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
package org.apache.phoenix.pherf.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Test;

/**
 * A querySet may specify connection properties that override scenario properties sharing the same
 * key, scoping those overrides exclusively to the connections established for that querySet.
 */
public class GroupConnectionPropertiesTest {
  private static final String FIXTURE = "test_group_properties.xml";
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";

  @Test
  public void testFixtureGivesGroupMaps() throws Exception {
    String path = new File(ChildJvm.scenarioDir(), FIXTURE).getPath();
    Scenario scenario = new XMLConfigParser(path).getScenarios().get(0);
    List<QuerySet> querySets = scenario.getQuerySet();
    assertEquals(3, querySets.size());
    assertEquals(map("pherf.test.group", "A", "pherf.test.key", "A"),
      querySets.get(0).getPhoenixProperties());
    assertEquals(map("pherf.test.group", "B"), querySets.get(1).getPhoenixProperties());
    assertNull(querySets.get(2).getPhoenixProperties());
    assertEquals(map("pherf.test.shared", "scenario", "pherf.test.key", "scenario"),
      scenario.getPhoenixProperties());
  }

  @Test
  public void testPreExistingFixturesHaveNoGroupMap() throws Exception {
    int querySets = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(FIXTURE) || file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        for (Scenario scenario : model.getScenarios()) {
          for (QuerySet querySet : scenario.getQuerySet()) {
            assertNull(file.getName(), querySet.getPhoenixProperties());
            querySets++;
          }
        }
      }
    }
    assertTrue(querySets > 10);
  }

  @Test
  public void testGroupWinsAndScenarioIsFallback() {
    Scenario scenario = new Scenario();
    scenario.setPhoenixProperties(map("shared", "scenario", "key", "scenario"));
    QuerySet querySet = new QuerySet();
    querySet.setPhoenixProperties(map("group", "A", "key", "A"));

    Map<String, String> properties = QueryExecutor.connectionProperties(scenario, querySet);
    assertEquals(map("shared", "scenario", "key", "A", "group", "A"), properties);
    // Resolving the effective properties leaves the underlying source maps unmodified, and
    // subsequent mutations to the returned map do not propagate back to them.
    properties.put("key", "changed");
    assertEquals(map("shared", "scenario", "key", "scenario"), scenario.getPhoenixProperties());
    assertEquals(map("group", "A", "key", "A"), querySet.getPhoenixProperties());
  }

  @Test
  public void testGroupMapWithoutScenarioMap() {
    Scenario scenario = new Scenario();
    scenario.setPhoenixProperties(null);
    QuerySet querySet = new QuerySet();
    querySet.setPhoenixProperties(map("group", "B"));
    assertEquals(map("group", "B"), QueryExecutor.connectionProperties(scenario, querySet));
  }

  @Test
  public void testNoGroupMapGivesScenarioMap() {
    Scenario scenario = new Scenario();
    scenario.setPhoenixProperties(map("key", "scenario"));
    assertSame(scenario.getPhoenixProperties(),
      QueryExecutor.connectionProperties(scenario, new QuerySet()));
  }

  @Test
  public void testQuerySetResultKeepsMap() {
    QuerySet querySet = new QuerySet();
    assertNull(new QuerySetResult(querySet).getPhoenixProperties());
    querySet.setPhoenixProperties(map("group", "A"));
    assertEquals(map("group", "A"), new QuerySetResult(querySet).getPhoenixProperties());
  }

  private static Map<String, String> map(String... keysAndValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
