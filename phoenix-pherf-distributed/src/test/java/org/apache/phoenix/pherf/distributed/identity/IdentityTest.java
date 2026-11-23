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
package org.apache.phoenix.pherf.distributed.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.distributed.CapturingAppender;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.controller.RunSpec;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.apache.phoenix.pherf.rules.IndexedRule;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IdentityTest {
  private static final long PLAN_SEED = 20260101L;
  /** Defines the starting row offset assigned to slot 1 across the identity test cases. */
  private static final long STRIDE = 1000;
  private static final int ROWS = 20;
  private static final String DOCUMENT = "scenario.xml";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private static String template() throws Exception {
    try (
      InputStream in = IdentityTest.class.getResourceAsStream("/identity/scenario_template.xml")) {
      byte[] bytes = new byte[64 * 1024];
      int length = 0;
      int n;
      while ((n = in.read(bytes, length, bytes.length - length)) > 0) {
        length += n;
      }
      return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
  }

  /** Returns the primary keys for the first rows of the scenario as produced by co-generation. */
  private List<String> keys(String scenarioXml) throws Exception {
    File file = folder.newFile();
    Files.write(file.toPath(), scenarioXml.getBytes(StandardCharsets.UTF_8));
    DataModel model = XMLConfigParser.parse(file.toPath());
    Scenario scenario = model.getScenarios().get(0);
    CoGenerator.Settings settings = CoGenerator.Settings.of(scenario);
    List<String> keys = new ArrayList<>();
    for (long i = 0; i < ROWS; i++) {
      StringBuilder key = new StringBuilder();
      for (Column column : model.getDataMappingColumns()) {
        key.append(new IndexedRule(column).valueAt(settings.offset + i, settings.seed).getValue())
          .append('|');
      }
      keys.add(key.toString());
    }
    return keys;
  }

  private static RunSpec templateRun(String template) {
    return Plans.plan(PLAN_SEED).step(Plans.step(1).scenario(DOCUMENT, template).rows(ROWS)
      .property("pherf.prefix", "${pherf.slot.prefix}")).spec();
  }

  /** Executes the template on one agent and returns the assignment received by its workload. */
  private static Assignment resolvedOn(String agentId, String template) {
    Simulation simulation = new Simulation();
    simulation.agent(agentId);
    simulation.runFor(1000);
    String runId = simulation.controller.submit(templateRun(template));
    simulation.runFor(10000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    assertEquals(1, simulation.workload(agentId).prepared.size());
    return simulation.workload(agentId).prepared.get(0);
  }

  @Test
  public void operationalIdDoesNotChangeData() throws Exception {
    String template = template();
    Assignment a1 = resolvedOn("a1", template);
    Assignment b7 = resolvedOn("b7", template);
    assertEquals(a1.getIdentity(), b7.getIdentity());
    assertEquals(a1.getDocuments(), b7.getDocuments());
    assertEquals(a1.getProperties(), b7.getProperties());
    String text = a1.getDocuments().get(DOCUMENT);
    assertFalse(text, text.contains("${"));
    assertFalse(text, text.contains("a1"));
    assertEquals("p000", a1.getProperties().get("pherf.prefix"));
    assertEquals(keys(text), keys(b7.getDocuments().get(DOCUMENT)));
  }

  @Test
  public void dataPlaneIdentityChangesSeedAndPrefix() throws Exception {
    String template = template();
    DataPlaneIdentity slot0 = DataPlaneIdentity.derive(PLAN_SEED, 0, 0);
    DataPlaneIdentity slot1 = DataPlaneIdentity.derive(PLAN_SEED, 1, STRIDE);
    assertNotEquals(slot0.getSeed(), slot1.getSeed());
    assertEquals(0, slot0.getOffset());
    assertEquals(STRIDE, slot1.getOffset());
    assertEquals("p000", SlotTokens.resolve("${pherf.slot.prefix}", slot0));
    assertEquals("p001", SlotTokens.resolve("${pherf.slot.prefix}", slot1));
    String text0 = SlotTokens.resolve(template, slot0);
    String text1 = SlotTokens.resolve(template, slot1);
    assertNotEquals(text0, text1);
    assertTrue(text0.contains("seed=\"" + slot0.getSeed() + "\""));
    List<String> keys0 = keys(text0);
    List<String> keys1 = keys(text1);
    Set<String> common = new HashSet<>(keys0);
    common.retainAll(keys1);
    assertEquals(Collections.emptySet(), common);
    for (String key : keys0) {
      assertTrue(key, key.startsWith("p000"));
    }
    for (String key : keys1) {
      assertTrue(key, key.startsWith("p001"));
    }
    assertEquals(ROWS, new HashSet<>(keys0).size());
    // Varying the seed alone alters the random portion of K1 that follows the prefix.
    assertNotEquals(randomParts(keys0), randomParts(keys1));
  }

  private static List<String> randomParts(List<String> keys) {
    List<String> parts = new ArrayList<>();
    for (String key : keys) {
      parts.add(key.substring("p000".length(), key.indexOf('|')));
    }
    return parts;
  }

  @Test
  public void deriveIsDeterministic() {
    for (int slot = 0; slot < DataPlaneIdentity.MAX_SLOTS; slot += 111) {
      assertEquals(DataPlaneIdentity.derive(PLAN_SEED, slot, STRIDE),
        DataPlaneIdentity.derive(PLAN_SEED, slot, STRIDE));
    }
    DataPlaneIdentity last = DataPlaneIdentity.derive(PLAN_SEED, 999, 999 * STRIDE);
    assertEquals("p999", last.getPrefix());
    assertEquals(999 * STRIDE, last.getOffset());
    assertNotEquals(DataPlaneIdentity.derive(PLAN_SEED, 5, STRIDE).getSeed(),
      DataPlaneIdentity.derive(PLAN_SEED + 1, 5, STRIDE).getSeed());
    Set<Long> seeds = new HashSet<>();
    for (int slot = 0; slot < DataPlaneIdentity.MAX_SLOTS; slot++) {
      seeds.add(DataPlaneIdentity.derive(PLAN_SEED, slot, STRIDE).getSeed());
    }
    assertEquals(DataPlaneIdentity.MAX_SLOTS, seeds.size());
    for (int slot : new int[] { -1, DataPlaneIdentity.MAX_SLOTS }) {
      try {
        DataPlaneIdentity.derive(PLAN_SEED, slot, STRIDE);
        fail("slot " + slot);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("slot"));
      }
    }
    try {
      DataPlaneIdentity.derive(PLAN_SEED, 1, -1);
      fail("negative offset");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("offset"));
    }
  }

  @Test
  public void unknownSlotTokenIsAnError() {
    DataPlaneIdentity slot0 = DataPlaneIdentity.derive(PLAN_SEED, 0, 0);
    for (String token : new String[] { "${pherf.slot.agent}", "${pherf.slot.id}",
      "${pherf.slot.}" }) {
      try {
        SlotTokens.resolve("key " + token, slot0);
        fail(token);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(token));
      }
    }
    assertEquals("${other.token} ${pherf.default} p000",
      SlotTokens.resolve("${other.token} ${pherf.default} ${pherf.slot.prefix}", slot0));
  }

  @Test
  public void logAndTraceRecordsCarryTheOperationalId() throws Exception {
    CapturingAppender appender = CapturingAppender.attach();
    Simulation simulation = new Simulation();
    try {
      simulation.agent("a1");
      simulation.runFor(1000);
      String runId = simulation.controller.submit(Simulation.spec(1));
      simulation.runFor(10000);
      assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());
    } finally {
      appender.detach();
    }
    List<LogEvent> events = appender.events("org.apache.phoenix.pherf.distributed.agent");
    assertTrue(events.size() >= 4);
    for (LogEvent event : events) {
      assertEquals(event.getMessage().getFormattedMessage(), "a1",
        event.getContextData().getValue(Protocol.MDC_KEY));
    }
    int requests = 0;
    for (InProcessNetwork.Event event : simulation.network.events()) {
      assertEquals("a1", event.agent);
      if (event.kind.equals(InProcessNetwork.REQUEST)) {
        AgentMessage message = (AgentMessage) Codec.decode(event.json);
        assertEquals(event.json, "a1", message.getAgent());
        assertTrue(event.json, message.getTrace().startsWith("a1/" + event.stream + "/"));
        requests++;
      }
    }
    assertTrue(requests > 4);
  }
}
