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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.junit.Test;

/**
 * Verifies RQ-228 by testing parsing of the strict JSON execution plan of ordered steps along with
 * every validation rejection enforced during the submission check.
 */
public class RunSpecTest {
  private static final String SCENARIO = "<datamodel name=\"s\"/>";

  private static Plans.Step step(int agents) {
    return Plans.step(agents).scenario("s.xml", SCENARIO);
  }

  private static void refused(String json, String message) {
    try {
      RunSpec.parse(json);
      fail("parsed: " + json);
    } catch (IllegalArgumentException e) {
      assertEquals(message, e.getMessage());
    }
  }

  private static void refused(Plans plan, String message) {
    refused(plan.json(), message);
  }

  private static void refused(Plans.Step step, String message) {
    refused(Plans.plan(1).step(step), message);
  }

  @Test
  public void multiStepPlanKeepsOrderAndEveryValue() {
    RunSpec spec = Plans.plan(-8070).property("pherf.verdict.x", "run").property("r", "1")
      .step(Plans.step(3).weights(1, 2, 1).scenario("a.xml", "<a/>").document("p.xml", "<p/>")
        .args("-q", "-disableSchemaApply").rate(300).durationMs(60000).threads(12).rows(30000)
        .property("pherf.default.threadpool", "16"))
      .step(Plans.step(1).scenario("b.xml", "<b/>").args("-l").rows(7))
      .step(Plans.step(2).scenario("c.xml", "<c/>").document("c.sql", "CREATE").durationMs(5))
      .spec();
    assertEquals(-8070, spec.getSeed());
    Map<String, String> run = new LinkedHashMap<>();
    run.put("pherf.verdict.x", "run");
    run.put("r", "1");
    assertEquals(run, spec.getProperties());
    assertEquals(3, spec.getSteps().size());

    StepSpec first = spec.getSteps().get(0);
    assertEquals(3, first.getAgents());
    assertArrayEquals(new long[] { 1, 2, 1 }, first.getWeights());
    assertEquals("a.xml", first.getScenario());
    assertEquals(Arrays.asList("a.xml", "p.xml"),
      Arrays.asList(first.getDocuments().keySet().toArray()));
    assertEquals("<p/>", first.getDocuments().get("p.xml"));
    assertEquals(Arrays.asList("-q", "-disableSchemaApply"), first.getArgs());
    assertEquals(Long.valueOf(300), first.getRate());
    assertEquals(Long.valueOf(60000), first.getDurationMs());
    assertEquals(Long.valueOf(12), first.getThreads());
    assertEquals(Long.valueOf(30000), first.getRows());
    assertEquals(Collections.singletonMap("pherf.default.threadpool", "16"), first.getProperties());

    StepSpec second = spec.getSteps().get(1);
    assertEquals("b.xml", second.getScenario());
    assertArrayEquals(new long[] { 1 }, second.getWeights());
    assertEquals(Arrays.asList("-l"), second.getArgs());
    assertEquals(Long.valueOf(7), second.getRows());
    assertNull(second.getRate());
    assertNull(second.getDurationMs());
    assertNull(second.getThreads());
    assertTrue(second.getProperties().isEmpty());

    StepSpec third = spec.getSteps().get(2);
    assertEquals("c.xml", third.getScenario());
    assertArrayEquals(new long[] { 1, 1 }, third.getWeights());
    assertEquals(Collections.emptyList(), third.getArgs());
    assertEquals(Long.valueOf(5), third.getDurationMs());
    assertNull(third.getRows());
  }

  @Test
  public void badJsonIsRefused() {
    for (String json : new String[] { "", "{", "{\"seed\": 1} {}", "{seed: 1}", "[1]", "7" }) {
      try {
        RunSpec.parse(json);
        fail(json);
      } catch (IllegalArgumentException e) {
        assertTrue(json + ": " + e.getMessage(),
          e.getMessage().startsWith("the plan is not valid JSON: ")
            || e.getMessage().equals("the plan is not a JSON object"));
      }
    }
    refused("[1]", "the plan is not a JSON object");
  }

  @Test
  public void unknownAndDuplicateKeysAreRefusedAtEveryLevel() {
    refused(Plans.plan(1).step(step(1)).set("stepz", new JsonArray()), "stepz: unknown key");
    refused(Plans.plan(1).step(step(1)).step(step(1).set("rat", new JsonPrimitive(1))),
      "steps[1].rat: unknown key");
    refused("{\"seed\": 1, \"seed\": 2, \"steps\": []}", "seed: duplicate key");
    refused("{\"seed\": 1, \"steps\": [{\"agents\": 1, \"agents\": 1}]}",
      "steps[0].agents: duplicate key");
    refused(
      "{\"seed\": 1, \"steps\": [{\"agents\": 1, \"scenario\": \"s.xml\", \"documents\":"
        + " {\"s.xml\": \"a\", \"s.xml\": \"b\"}}]}",
      "steps[0].documents[\"s.xml\"]: duplicate key");
  }

  @Test
  public void missingFieldsAreRefused() {
    refused("{\"steps\": []}", "seed: required");
    refused("{\"seed\": 1}", "steps: required");
    refused(Plans.plan(1), "steps: no step; a plan needs at least one");
    refused("{\"seed\": 1, \"steps\": [{\"scenario\": \"s.xml\", \"documents\": {}}]}",
      "steps[0].agents: required");
    refused("{\"seed\": 1, \"steps\": [{\"agents\": 1, \"documents\": {}}]}",
      "steps[0].scenario: required");
    refused("{\"seed\": 1, \"steps\": [{\"agents\": 1, \"scenario\": \"s.xml\"}]}",
      "steps[0].documents: required");
    refused(
      Plans.plan(1)
        .step(Plans.step(1).scenario("s.xml", "x").set("scenario", new JsonPrimitive("t.xml"))),
      "steps[0].documents: no document named t.xml for the scenario");
  }

  @Test
  public void wrongTypesAreRefused() {
    String head = "{\"seed\": 1, \"steps\": [{\"agents\": 1, \"scenario\": \"s.xml\", "
      + "\"documents\": {\"s.xml\": \"x\"}, ";
    for (String literal : new String[] { "1.5", "1.0", "1e3", "\"5\"", "true", "null",
      "9223372036854775808", "[]" }) {
      refused(head + "\"rate\": " + literal + "}]}", "steps[0].rate: not a 64-bit integer");
    }
    refused("{\"seed\": \"1\", \"steps\": []}", "seed: not a 64-bit integer");
    refused("{\"seed\": 1, \"steps\": {}}", "steps: not an array");
    refused("{\"seed\": 1, \"steps\": [3]}", "steps[0]: not an object");
    refused(head + "\"args\": [\"-q\", 3]}]}", "steps[0].args[1]: not a string");
    refused(head + "\"args\": \"-q\"}]}", "steps[0].args: not an array");
    refused(head + "\"weights\": [1.5]}]}", "steps[0].weights[0]: not a 64-bit integer");
    refused(head + "\"properties\": {\"k\": 1}}]}", "steps[0].properties[\"k\"]: not a string");
    refused(head + "\"properties\": []}]}", "steps[0].properties: not an object");
    refused("{\"seed\": 1, \"properties\": {\"k\": null}, \"steps\": []}",
      "properties[\"k\"]: not a string");
    refused("{\"seed\": 1, \"steps\": [{\"agents\": 1, \"scenario\": 5, \"documents\": {}}]}",
      "steps[0].scenario: not a string");
  }

  @Test
  public void valuesOutsideTheirRangeAreRefused() {
    refused(step(0), "steps[0].agents: 0 is outside 1 to 1000");
    refused(step(1001), "steps[0].agents: 1001 is outside 1 to 1000");
    refused(step(1).rate(0), "steps[0].rate: 0 is below 1");
    refused(step(1).durationMs(-1), "steps[0].durationMs: -1 is below 1");
    refused(step(1).threads(0), "steps[0].threads: 0 is below 1");
    refused(step(1).rows(0), "steps[0].rows: 0 is below 1");
    refused(Plans.plan(1).step(step(1)).step(step(2).weights(1, 2, 3)),
      "steps[1].weights: 3 weights for 2 agents");
    refused(step(3).weights(1, 0, 1), "steps[0].weights[1]: 0 is below 1");
  }

  @Test
  public void badDocumentNamesAreRefused() {
    String rule = ": not a document name; a name matches [A-Za-z0-9._-]{1,128} and does not"
      + " start with '.'";
    char[] tooLong = new char[129];
    Arrays.fill(tooLong, 'a');
    for (String name : new String[] { "../x.xml", ".hidden", "a/b.xml", "", "a b",
      new String(tooLong), "xé.xml" }) {
      refused(step(1).document(name, "x"), "steps[0].documents[\"" + name + "\"]" + rule);
    }
  }

  @Test
  public void agentOptionsAreRefusedInEverySpelling() {
    String[][] owned = { { "scenarioFile", "-scenarioFile", "use the step field scenario" },
      { "label", "-label", "the agent sets it" },
      { "z", "-z", "the agent takes it from PHERF_ZOOKEEPER" },
      { "zookeeper", "-z", "the agent takes it from PHERF_ZOOKEEPER" },
      { "runDurationMs", "-runDurationMs", "use the step field durationMs" },
      { "threadCount", "-threadCount", "use the step field threads" },
      { "targetRate", "-targetRate", "use the step field rate" },
      { "rowCountOverride", "-rowCountOverride", "use the step field rows" },
      { "validate", "-validate", "the agent runs it before each step" } };
    for (String[] option : owned) {
      for (String spelling : new String[] { "-" + option[0], "--" + option[0],
        "-" + option[0] + "=v", "--" + option[0] + "=v" }) {
        refused(step(1).args("-q", spelling),
          "steps[0].args[1]: " + option[1] + " belongs to the agent; " + option[2]);
      }
    }
    String[][] modes = { { "analyze", "-analyze" }, { "compare", "-compare" },
      { "listFiles", "-listFiles" }, { "h", "-h" }, { "help", "-h" }, { "t", "-t" },
      { "thin", "-t" }, { "s", "-s" }, { "server", "-s" } };
    for (String[] mode : modes) {
      for (String spelling : new String[] { "-" + mode[0], "--" + mode[0], "-" + mode[0] + "=v" }) {
        refused(step(1).args(spelling),
          "steps[0].args[0]: " + mode[1] + " is not allowed in a distributed run");
      }
    }
    // All other command-line options and option values are accepted without rejection.
    Plans.plan(1).step(step(1).args("-q", "-l", "-scenarioName", "x", "-hint", "SMALL", "-mt",
      "--disableSchemaApply", "-stats", "-b", "label", "z")).spec();
  }

  @Test
  public void agentPropertiesAreRefused() {
    refused(step(1).property("pherf.default.results.dir", "/tmp"),
      "steps[0].properties[\"pherf.default.results.dir\"]: belongs to the agent");
    refused(Plans.plan(1).property("pherf.default.hdr.intervalMs", "5").step(step(1)),
      "properties[\"pherf.default.hdr.intervalMs\"]: belongs to the agent");
  }

  @Test
  public void offsetTokenNeedsRows() {
    String message = "steps[0]: ${pherf.slot.offset} needs rows";
    refused(Plans.step(1).scenario("s.xml", "<c offset=\"${pherf.slot.offset}\"/>"), message);
    refused(step(1).property("k", "${pherf.slot.offset}"), message);
    refused(Plans.plan(1).property("k", "${pherf.slot.offset}").step(step(1).rows(5)).step(step(1)),
      "steps[1]: ${pherf.slot.offset} needs rows");
    Plans.plan(1)
      .step(Plans.step(1).scenario("s.xml", "<c offset=\"${pherf.slot.offset}\"/>").rows(5)).spec();
  }

  @Test
  public void everySlotNeedsAShareOfEachSplitQuantity() {
    refused(step(3).threads(2), "steps[0].threads: slot 2 gets a share of 0 of 2");
    refused(step(4).weights(5, 5, 5, 1).rate(3), "steps[0].rate: slot 3 gets a share of 0 of 3");
    refused(step(2).weights(1000, 1).rows(500), "steps[0].rows: slot 1 gets a share of 0 of 500");
    refused(step(2).rows(Integer.MAX_VALUE * 4L),
      "steps[0].rows: slot 0 gets 4294967294 rows; -rowCountOverride takes at most 2147483647");
    // The durationMs parameter is applied as a whole rather than being split across slots.
    Plans.plan(1).step(step(3).durationMs(1)).spec();
  }

  @Test
  public void anAssignOverTheBodyLimitIsRefused() {
    char[] text = new char[Protocol.MAX_BODY_BYTES - 600];
    Arrays.fill(text, 'x');
    try {
      RunSpec.parse(Plans.plan(1).step(step(1).document("big.xml", new String(text))).json());
      fail("parsed an oversized assign");
    } catch (IllegalArgumentException e) {
      String limit = "; the limit is " + (Protocol.MAX_BODY_BYTES - 1024);
      assertTrue(e.getMessage(), e.getMessage().matches(
        "steps\\[0\\]: the assign of slot 0 has [0-9]+ bytes" + limit.replace(".", "\\.")));
      long bytes = Long.parseLong(e.getMessage().replaceAll("^.* has ([0-9]+) bytes.*$", "$1"));
      assertTrue(e.getMessage(), bytes > Protocol.MAX_BODY_BYTES - 1024);
    }
    char[] fits = new char[Protocol.MAX_BODY_BYTES / 2];
    Arrays.fill(fits, 'x');
    Plans.plan(1).step(step(1).document("big.xml", new String(fits))).spec();
    // Slot 0 of step 0 also carries the 2 slices of step 1 that it is responsible for validating.
    try {
      RunSpec.parse(
        Plans.plan(1).step(step(1)).step(step(2).document("big.xml", new String(fits))).json());
      fail("parsed an oversized assign of step 0");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().startsWith("steps[0]: the assign of slot 0 has "));
    }
  }

  @Test
  public void stepPropertiesAreAMapOfStrings() {
    JsonObject properties = new JsonObject();
    properties.addProperty("a", "1");
    properties.addProperty("b", "${pherf.slot.prefix}");
    RunSpec spec = Plans.plan(1).step(step(1).set("properties", properties)).spec();
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("a", "1");
    expected.put("b", "${pherf.slot.prefix}");
    assertEquals(expected, spec.getSteps().get(0).getProperties());
  }
}
