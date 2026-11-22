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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.spi.RecordingExtensions;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ScenarioValidator;
import org.apache.phoenix.pherf.validation.ValidationReport;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies the scenario validator operating as the pre-run gate of Pherf in child JVMs. A run with
 * a scenario file opens no connection until its scenario files pass validation. Invocations of
 * Pherf.main run with a connection provider that records each connection open and then fails it,
 * whereas a run that must proceed executes against the fake driver.
 */
public class ValidationGateRunTest {
  private static final String UNKNOWN = "unknown_element.xml";
  private static final String RELEASE = "release_attribute.xml";
  private static final String NEWER_TAG = "<notATag mode=\"new\">a tag of a newer Pherf</notATag>";

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  /**
   * Verifies RQ-199 by confirming that an invalid scenario stops the run before executing the drop,
   * the schema apply, and the load.
   */
  @Test
  public void testInvalidScenarioOpensNoConnection() throws Exception {
    ChildJvm child = throwing("invalid");
    int exit = child.runMain(ChildJvm.args("-drop", "all", "-l", "-q", "-label", "gate",
      "-scenarioFile", validation("defects.xml")));
    assertEquals(ScenarioValidator.EXIT_CODE, exit);
    assertEquals(new ArrayList<String>(), Records.read(child.record(Records.PROVIDER)));

    // Under the default schema apply behavior, the validation gate reads the schema files from the
    // class path.
    PhoenixUtil.setRowCountOverride(0);
    ValidationReport expected = ScenarioValidator.validate(validation("defects.xml"), ".*.sql");
    assertTrue(expected.getErrors() >= 10);
    JsonObject json = report(child, "gate");
    assertEquals(expected.getErrors(), json.get("errors").getAsInt());
    List<String> out = Files.readAllLines(child.out().toPath(), StandardCharsets.UTF_8);
    for (Finding finding : expected.getFindings()) {
      assertTrue("Not printed: " + finding, out.contains(finding.toString()));
    }
    assertTrue(out.toString(),
      out.contains(
        "Validation: " + expected.getErrors() + " error(s) in " + expected.getFiles().size()
          + " file(s). Report: " + new File("RESULTS/VALIDATION_gate.json")));
    // Neither a latency file nor a result file is produced because the run stopped at the gate.
    assertEquals(Collections.singletonList("VALIDATION_gate.json"), results(child));
  }

  /**
   * Verifies RQ-199 by confirming that a valid scenario passes the validation gate and runs to
   * completion without the gate writing a validation report.
   */
  @Test
  public void testValidScenarioRuns() throws Exception {
    ChildJvm child = recording("valid");
    child.runCli(
      Collections.singletonList(ChildJvm.args("-drop", "all", "-l", "-q", "-z", "valid", "-label",
        "valid", "-schemaFile", new File(ChildJvm.datamodelDir(), "test_schema.sql").getPath(),
        "-scenarioFile", new File(ChildJvm.scenarioDir(), "test_group_properties.xml").getPath())));
    assertEquals(Collections.singletonList(ChildRun.RAN),
      Records.read(child.record(Records.OUTCOME)));
    assertProvided(child);
    assertNoReport(child);
  }

  /**
   * Verifies RQ-200 by confirming that without the waiver flag, an unknown XML element causes the
   * validation gate to fail.
   */
  @Test
  public void testUnknownElementFailsWithoutFlag() throws Exception {
    ChildJvm child = throwing("unknown");
    int exit = child.runMain(ChildJvm.args("-q", "-disableSchemaApply", "-label", "unknown",
      "-scenarioFile", validation(UNKNOWN)));
    assertEquals(ScenarioValidator.EXIT_CODE, exit);
    assertEquals(new ArrayList<String>(), Records.read(child.record(Records.PROVIDER)));
    JsonArray findings = report(child, "unknown").getAsJsonArray("findings");
    assertEquals(findings.toString(), 1, findings.size());
    JsonObject finding = findings.get(0).getAsJsonObject();
    assertEquals("xml.unknown-element", finding.get("rule").getAsString());
    assertEquals("datamodel/scenarios/scenario[1]/notATag", finding.get("where").getAsString());
  }

  /**
   * Verifies RQ-200 by confirming that when the flag is enabled, the same scenario passes the gate
   * and runs while recording the waived element in the log.
   */
  @Test
  public void testUnknownElementRunsWithFlag() throws Exception {
    ChildJvm child = recording("unknownAllowed");
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-disableSchemaApply", "-z",
      "unknown", "-label", "unknown", "-allowUnknownTags", "-scenarioFile", validation(UNKNOWN))));
    assertEquals(Collections.singletonList(ChildRun.RAN),
      Records.read(child.record(Records.OUTCOME)));
    assertProvided(child);
    assertNoReport(child);

    PhoenixUtil.setRowCountOverride(0);
    List<Finding> waived = ScenarioValidator.validate(validation(UNKNOWN), null).getFindings();
    assertEquals(waived.toString(), 1, waived.size());
    boolean logged = false;
    for (String line : Records.read(new File(child.out().getParentFile(), "child.err"))) {
      logged |= line.contains(" WARN ") && line.contains(waived.get(0).toString());
    }
    assertTrue("No WARN line for " + waived.get(0), logged);
  }

  /**
   * Verifies RQ-200 by confirming that the flag waives only the unknown element finding while a
   * primary key error still causes validation to fail.
   */
  @Test
  public void testFlagKeepsKeyError() throws Exception {
    File dir = folder.newFolder("keyError");
    String scenario = copy(dir, "clean_cogen.xml", "<coGeneration seed=\"7\" blockSize=\"50\"/>",
      "<coGeneration seed=\"7\" blockSize=\"50\"/>\n            " + NEWER_TAG,
      "statement=\"SELECT K1, V FROM PHERF.VALIDATION_CLEAN WHERE K1 = [K1] AND K2 = [K2]\"",
      "statement=\"SELECT K1, V FROM PHERF.VALIDATION_CLEAN WHERE K1 = [K1] AND K2 = [K1]\"");
    copy(dir, "clean_cogen_profile.xml");

    // As a control baseline, confirm that without the waiver the scenario variant produces
    // exactly these two findings.
    PhoenixUtil.setRowCountOverride(0);
    List<String> rules = new ArrayList<>();
    for (Finding finding : ScenarioValidator.validate(scenario, null).getFindings()) {
      rules.add(finding.getRule().id());
    }
    assertEquals(Arrays.asList("xml.unknown-element", "key.coverage"), rules);

    ChildJvm child = throwing("keyErrorRun");
    int exit = child.runMain(ChildJvm.args("-q", "-disableSchemaApply", "-allowUnknownTags",
      "-label", "key", "-scenarioFile", scenario));
    assertEquals(ScenarioValidator.EXIT_CODE, exit);
    assertEquals(new ArrayList<String>(), Records.read(child.record(Records.PROVIDER)));
    JsonObject json = report(child, "key");
    assertEquals(1, json.get("errors").getAsInt());
    JsonArray findings = json.getAsJsonArray("findings");
    assertEquals(findings.toString(), 1, findings.size());
    assertEquals("key.coverage", findings.get(0).getAsJsonObject().get("rule").getAsString());
    for (String line : Files.readAllLines(child.out().toPath(), StandardCharsets.UTF_8)) {
      assertFalse(line, line.startsWith("xml.unknown-element"));
    }
  }

  /**
   * Verifies RQ-152 by confirming that running with -validate produces no finding for the release
   * attribute of older Pherf when no flag is supplied.
   */
  @Test
  public void testReleaseAttributeValidates() throws Exception {
    ChildJvm child = throwing("releaseValidate");
    int exit = child.runMain(ChildJvm.args("-validate", "-disableSchemaApply", "-label", "release",
      "-scenarioFile", validation(RELEASE)));
    assertEquals(0, exit);
    assertEquals(new ArrayList<String>(), Records.read(child.record(Records.PROVIDER)));
    JsonObject json = report(child, "release");
    assertEquals(0, json.get("errors").getAsInt());
    assertEquals(0, json.getAsJsonArray("findings").size());
  }

  /**
   * Verifies RQ-152 by confirming that a scenario containing the release attribute passes the
   * validation gate without any flag and runs to completion.
   */
  @Test
  public void testReleaseAttributeRuns() throws Exception {
    ChildJvm child = recording("release");
    child.runCli(Collections.singletonList(ChildJvm.args("-q", "-disableSchemaApply", "-z",
      "release", "-label", "release", "-scenarioFile", validation(RELEASE))));
    assertEquals(Collections.singletonList(ChildRun.RAN),
      Records.read(child.record(Records.OUTCOME)));
    assertProvided(child);
    assertNoReport(child);
  }

  /**
   * Verifies RQ-152 by confirming that the existing invocations that run Pherf from startup pass
   * the validation gate with no flag, covering the argument sets from PherfMainIT and the "To test"
   * command in the README. Each invocation subsequently fails at its first connection attempt
   * during the drop or the schema apply, and therefore exits with code 1 rather than 65.
   */
  @Test
  public void testExistingStartupInvocationsPass() throws Exception {
    List<List<String>> invocations = new ArrayList<>();
    // Include the startup arguments exercised by PherfMainIT#testPherfMain.
    invocations.add(ChildJvm.args("-q", "-l", "-z", "localhost", "-schemaFile",
      ".*create_prod_test_unsalted.sql", "-scenarioFile", ".*prod_test_unsalted_scenario.xml"));
    // Include the startup arguments exercised by PherfMainIT#testQueryTimeout,
    // #testLargeQueryTimeout, and #testNoQueryTimeout.
    invocations.add(ChildJvm.args("-q", "-l", "-z", "localhost", "-drop", "all", "-schemaFile",
      ".*timeout_test_schema.sql", "-scenarioFile", ".*scenario_with_query_timeouts.xml"));
    // Include the startup arguments documented in the README "To test" command.
    invocations.add(ChildJvm.args("-drop", "all", "-l", "-q", "-z", "localhost", "-schemaFile",
      ".*user_defined_schema.sql", "-scenarioFile", ".*user_defined_scenario.xml"));
    for (int i = 0; i < invocations.size(); i++) {
      ChildJvm child = throwing("startup" + i);
      int exit = child.runMain(invocations.get(i));
      String name = invocations.get(i).toString();
      assertEquals(name, 1, exit);
      List<String> opened = Records.read(child.record(Records.PROVIDER));
      assertEquals(name + " " + opened, 1, opened.size());
      assertNoReport(child);
    }
  }

  private ChildJvm throwing(String name) throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder(name));
    child.register(ConnectionProvider.class, ValidateRunTest.ThrowingProvider.class);
    return child;
  }

  private ChildJvm recording(String name) throws Exception {
    ChildJvm child = new ChildJvm(folder.newFolder(name));
    child.register(ConnectionProvider.class, RecordingExtensions.Provider.class);
    return child;
  }

  private static String validation(String name) {
    return new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/validation/" + name)
      .getPath();
  }

  /**
   * Copies a validation fixture into the target directory while applying the specified text
   * replacements. Each replacement pair must match exactly once so that the resulting copy differs
   * from the base fixture only as intended by the test.
   */
  private static String copy(File dir, String name, String... replacements) throws Exception {
    String text =
      new String(Files.readAllBytes(new File(validation(name)).toPath()), StandardCharsets.UTF_8);
    for (int i = 0; i < replacements.length; i += 2) {
      int at = text.indexOf(replacements[i]);
      assertTrue("No '" + replacements[i] + "' in " + name, at >= 0);
      assertEquals("A second '" + replacements[i] + "' in " + name, -1,
        text.indexOf(replacements[i], at + 1));
      text = text.replace(replacements[i], replacements[i + 1]);
    }
    File file = new File(dir, name);
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    return file.getPath();
  }

  /**
   * Asserts that at least one connection was opened and that every connection reaching the driver
   * was routed through the connection provider.
   */
  private static void assertProvided(ChildJvm child) throws Exception {
    List<String> driver = new ArrayList<>(Records.withoutMarks(child.record(Records.CONNECTIONS)));
    List<String> provider = new ArrayList<>(Records.withoutMarks(child.record(Records.PROVIDER)));
    Collections.sort(driver);
    Collections.sort(provider);
    assertFalse("No connection", driver.isEmpty());
    assertEquals(driver, provider);
  }

  private static void assertNoReport(ChildJvm child) {
    for (String name : results(child)) {
      assertFalse(name, name.startsWith("VALIDATION_"));
    }
  }

  /** Returns the sorted list of file names present in the child JVM results directory. */
  private static List<String> results(ChildJvm child) {
    String[] names = new File(child.work(), "RESULTS").list();
    List<String> list =
      names == null ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(names));
    Collections.sort(list);
    return list;
  }

  private static JsonObject report(ChildJvm child, String label) throws Exception {
    File file = new File(child.work(), "RESULTS/VALIDATION_" + label + ".json");
    assertTrue("No report " + file, file.exists());
    return JsonParser
      .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }
}
