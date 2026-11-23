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
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.rules.DataValue;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.apache.phoenix.pherf.workload.cogen.IndexDecoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirement RQ-227: data-plane identities partition the keyspace into disjoint ranges
 * across slots without requiring any inter-agent messaging. Each slot's starting row offset is
 * computed deterministically as the cumulative sum of the row shares assigned to all lower-indexed
 * slots.
 */
public class KeyspaceTest {
  private static final int ROWS = 1000;
  private static final String[] AGENTS = { "a1", "a2", "a3" };

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * Reconstructs the primary keys generated for a slot by using the co-generation writer's decoder
   * to resolve the rows bound for each local index within the slot's assigned row share.
   */
  private List<String> keys(Assignment assignment) throws Exception {
    File file = folder.newFile();
    Files.write(file.toPath(),
      assignment.getDocuments().get("keyspace.xml").getBytes(StandardCharsets.UTF_8));
    DataModel model = XMLConfigParser.parse(file.toPath());
    XMLConfigParser.updateDataValueType(model);
    Scenario scenario = model.getScenarios().get(0);
    CoGenerator.Settings settings = CoGenerator.Settings.of(scenario);
    assertEquals(assignment.getIdentity().getOffset(), settings.offset);
    // Because the scenario defines no cluster limit, the decoder does not require a leading key.
    IndexDecoder decoder = new IndexDecoder(new RulesApplier(model), scenario, settings, null);
    List<String> keys = new ArrayList<>();
    for (long i = 0; i < assignment.getShare().getRows(); i++) {
      StringBuilder key = new StringBuilder();
      for (DataValue value : decoder.row(model.getDataMappingColumns(), i)) {
        key.append(value.getValue()).append('|');
      }
      keys.add(key.toString());
    }
    return keys;
  }

  @Test
  public void slotsWriteDisjointKeysThatCoverTheTable() throws Exception {
    Simulation simulation = new Simulation();
    for (String id : AGENTS) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    String template = Plans.resource("/keyspace/cogen_template.xml");
    String runId = simulation.controller.submit(Plans.plan(8070)
      .step(Plans.step(3).weights(1, 2, 1).scenario("keyspace.xml", template).rows(ROWS))
      .step(Plans.step(2).scenario("keyspace.xml", template).rows(ROWS)).spec());
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());

    // In step 0, three slots partition the workload using a 1:2:1 weight ratio.
    List<Long> offsets = new ArrayList<>();
    List<Set<String>> slots = new ArrayList<>();
    Set<String> step0 = new HashSet<>();
    for (String id : AGENTS) {
      Assignment assignment = simulation.workload(id).prepared.get(0);
      assertEquals(0, assignment.getStep());
      offsets.add(assignment.getIdentity().getOffset());
      List<String> keys = keys(assignment);
      Set<String> distinct = new HashSet<>(keys);
      assertEquals(keys.size(), distinct.size());
      for (Set<String> other : slots) {
        Set<String> common = new HashSet<>(other);
        common.retainAll(distinct);
        assertEquals(new HashSet<String>(), common);
      }
      slots.add(distinct);
      step0.addAll(distinct);
    }
    assertEquals(Arrays.asList(0L, 250L, 750L), offsets);
    assertEquals(ROWS, step0.size());

    // In step 1, two slots within the same execution plan read back the exact same set of keys.
    Set<String> step1 = new HashSet<>();
    List<Long> offsets1 = new ArrayList<>();
    for (String id : new String[] { "a1", "a2" }) {
      Assignment assignment = simulation.workload(id).prepared.get(1);
      assertEquals(1, assignment.getStep());
      offsets1.add(assignment.getIdentity().getOffset());
      step1.addAll(keys(assignment));
    }
    assertEquals(Arrays.asList(0L, 500L), offsets1);
    assertEquals(step0, step1);
    assertEquals(1, simulation.workload("a3").prepared.size());

    // Agents never communicate directly with one another; every network event occurs exclusively
    // between an individual agent and the controller over the agent's two dedicated streams.
    Set<String> agents = new HashSet<>(Arrays.asList(AGENTS));
    for (InProcessNetwork.Event event : simulation.network.events()) {
      assertTrue(event.toString(), agents.contains(event.agent));
      assertTrue(event.toString(),
        event.stream.equals(Protocol.CONTROL) || event.stream.equals(Protocol.TELEMETRY));
    }
    // Because the controller is the sole network listener, no peer can establish a connection to
    // an agent.
    for (String id : AGENTS) {
      try {
        simulation.network.connect("x", id);
        fail("connected to agent " + id);
      } catch (IllegalArgumentException expected) {
      }
    }
    simulation.network.connect("x", Simulation.ENDPOINT);
  }
}
