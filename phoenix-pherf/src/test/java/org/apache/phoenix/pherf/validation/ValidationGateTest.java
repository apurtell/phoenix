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
package org.apache.phoenix.pherf.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.run.ReferenceScenarios;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.apache.phoenix.pherf.validation.ValidationReport.Rule;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the validator operating in-process as the pre-run gate, covering both the reuse of its
 * models and the name waiver.
 */
public class ValidationGateTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  // Query.getId() generates a random identifier whenever a query does not define one.
  private static final String RANDOM_ID =
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final Set<Rule> UNKNOWN_NAMES =
    EnumSet.of(Rule.XML_UNKNOWN_ELEMENT, Rule.XML_UNKNOWN_ATTRIBUTE);

  @Before
  public void noRowCountOverride() {
    // Another test running in this JVM can leave a row-count override behind, which would
    // otherwise be reflected in the marshalled row counts.
    PhoenixUtil.setRowCountOverride(0);
  }

  /**
   * Verifies that the run reuses the models already parsed by the validation gate so that the gate
   * introduces no additional parsing pass. Those models match the ones produced by a parser that
   * reads the file directly, and the parser emits identical output lines.
   */
  @Test
  public void testGateModelsAreTheRunModels() throws Exception {
    List<File> files = new ArrayList<>(
      Arrays.asList(ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))));
    files.removeIf(file -> file.getName().equals(DTD_FIXTURE));
    files.sort(null);
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      files.add(reference.scenarioFile());
    }
    assertTrue(files.toString(), files.size() > 15);
    for (File file : files) {
      String pattern = file.getPath();
      Map<Path, DataModel> models = new LinkedHashMap<>();
      ValidationReport report = ScenarioValidator.validate(pattern, null, models);
      assertEquals(pattern + ": " + report.getFindings(), 0, report.getErrors());
      assertEquals(pattern, 1, models.size());

      ByteArrayOutputStream gatedOut = new ByteArrayOutputStream();
      XMLConfigParser gated = printing(gatedOut, () -> new XMLConfigParser(pattern, models));
      ByteArrayOutputStream freshOut = new ByteArrayOutputStream();
      XMLConfigParser fresh = printing(freshOut, () -> new XMLConfigParser(pattern));

      List<DataModel> expected = new ArrayList<>(models.values());
      assertEquals(pattern, expected.size(), gated.getDataModels().size());
      for (int i = 0; i < expected.size(); i++) {
        assertSame(pattern, expected.get(i), gated.getDataModels().get(i));
      }
      assertEquals(pattern, marshal(fresh.getDataModels()), marshal(gated.getDataModels()));
      String printed = new String(freshOut.toByteArray(), StandardCharsets.UTF_8);
      assertTrue(printed, printed.startsWith("Adding model for path:"));
      assertEquals(pattern, printed, new String(gatedOut.toByteArray(), StandardCharsets.UTF_8));
    }
  }

  /**
   * RQ-200: Verifies that the waiver removes unknown-name findings in report order while preserving
   * every other finding in its original order.
   */
  @Test
  public void testWaiverRemovesOnlyUnknownNames() throws Exception {
    ValidationReport report = ValidationFixtures.validate(ValidationFixtures.file("defects.xml"));
    List<Finding> names = new ArrayList<>();
    List<Finding> rest = new ArrayList<>();
    for (Finding finding : report.getFindings()) {
      (UNKNOWN_NAMES.contains(finding.getRule()) ? names : rest).add(finding);
    }
    assertEquals(report.getFindings().toString(), 2, names.size());
    assertEquals(UNKNOWN_NAMES, ruleSet(names));
    Set<Rule> other = ruleSet(rest);
    assertTrue(other.toString(), other.size() >= 10);

    assertEquals(names, report.waiveUnknownNames());
    assertEquals(rest, report.getFindings());
    assertEquals(rest.size(), report.getErrors());
    assertEquals(other, ruleSet(report.getFindings()));
    assertEquals(new ArrayList<Finding>(), report.waiveUnknownNames());
  }

  private static Set<Rule> ruleSet(List<Finding> findings) {
    Set<Rule> rules = EnumSet.noneOf(Rule.class);
    for (Finding finding : findings) {
      rules.add(finding.getRule());
    }
    return rules;
  }

  private static String marshal(List<DataModel> models) throws Exception {
    StringBuilder text = new StringBuilder();
    for (DataModel model : models) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      XMLConfigParser.writeDataModel(model, out);
      text.append(
        new String(out.toByteArray(), StandardCharsets.UTF_8).replaceAll(RANDOM_ID, "<RANDOM-ID>"));
    }
    return text.toString();
  }

  private interface ParserSource {
    XMLConfigParser get() throws Exception;
  }

  /** Constructs a parser while capturing the output that it prints. */
  private static XMLConfigParser printing(ByteArrayOutputStream out, ParserSource source)
    throws Exception {
    PrintStream saved = System.out;
    System.setOut(new PrintStream(out, true, "UTF-8"));
    try {
      return source.get();
    } finally {
      System.setOut(saved);
    }
  }
}
