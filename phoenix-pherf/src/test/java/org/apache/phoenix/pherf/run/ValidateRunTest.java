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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.pherf.spi.ConnectionProvider;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ScenarioValidator;
import org.apache.phoenix.pherf.validation.ValidationReport;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises the -validate execution mode through Pherf.main in isolated child JVMs using a
 * connection provider that records every connection open attempt and immediately fails it. Each
 * invocation runs in a dedicated child JVM because main terminates the JVM upon completion.
 */
public class ValidateRunTest {
  @ClassRule
  public static final TemporaryFolder FOLDER = new TemporaryFolder(ChildJvm.targetDir());

  private static ChildJvm clean;
  private static ChildJvm defects;
  private static ChildJvm drop;
  private static ChildJvm unnamed;
  private static ChildJvm range;
  private static File rangeScenario;
  private static int cleanExit;
  private static int defectsExit;
  private static int dropExit;
  private static int unnamedExit;
  private static int rangeExit;

  /** Records each connection attempt and then fails it so that no connection ever opens. */
  public static class ThrowingProvider implements ConnectionProvider {
    @Override
    public Connection getConnection(String url, Properties properties) throws SQLException {
      Records.append(Records.PROVIDER, Records.connectionLine(url, properties));
      throw new SQLException("The validation test opens no connection: " + url);
    }
  }

  @BeforeClass
  public static void runPherf() throws Exception {
    clean = child("clean");
    // Exercise the default schema application behavior, in which the validator reads the schema
    // files from the class path.
    cleanExit = clean.runMain(
      ChildJvm.args("-validate", "-label", "clean", "-scenarioFile", fixture("clean_cogen.xml")));
    defects = child("defects");
    defectsExit = defects.runMain(ChildJvm.args("-validate", "-disableSchemaApply", "-label",
      "defects", "-scenarioFile", fixture("defects.xml")));
    // Execute a drop operation as a positive control to verify that it opens a connection through
    // the provider.
    drop = child("drop");
    dropExit = drop.runMain(ChildJvm.args("-drop", "all", "-disableSchemaApply", "-label", "drop",
      "-scenarioFile", fixture("clean_cogen.xml")));
    // Introduce an attribute typo that leaves the scenario without a name, which the model getters
    // of the run reject.
    unnamed = child("unnamed");
    File dir = FOLDER.newFolder("unnamedScenario");
    for (String name : new String[] { "clean_cogen.xml", "clean_cogen_profile.xml" }) {
      String text =
        new String(Files.readAllBytes(new File(fixture(name)).toPath()), StandardCharsets.UTF_8)
          .replace("name=\"validationClean\"", "nmae=\"validationClean\"");
      Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    unnamedExit = unnamed.runMain(ChildJvm.args("-validate", "-disableSchemaApply", "-label",
      "unnamed", "-scenarioFile", new File(dir, "clean_cogen.xml").getPath()));
    // Configure the open loop query set with a concurrency range that is replaced by the
    // -threadCount 2 override.
    range = child("range");
    rangeScenario =
      copy("rangeScenario", "concurrency=\"1\" profile=", "concurrency=\"1-2\" profile=");
    rangeExit = range.runMain(ChildJvm.args("-validate", "-q", "-threadCount", "2",
      "-disableSchemaApply", "-label", "range", "-scenarioFile", rangeScenario.getPath()));
  }

  /**
   * Copies the clean fixture and its profile with a single string replacement applied, returning
   * the copied scenario file.
   */
  private static File copy(String folder, String from, String to) throws Exception {
    File dir = FOLDER.newFolder(folder);
    for (String name : new String[] { "clean_cogen.xml", "clean_cogen_profile.xml" }) {
      String text =
        new String(Files.readAllBytes(new File(fixture(name)).toPath()), StandardCharsets.UTF_8);
      assertTrue(name.endsWith("profile.xml") || text.contains(from));
      Files.write(new File(dir, name).toPath(),
        text.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }
    return new File(dir, "clean_cogen.xml");
  }

  private static ChildJvm child(String name) throws Exception {
    ChildJvm child = new ChildJvm(FOLDER.newFolder(name));
    child.register(ConnectionProvider.class, ThrowingProvider.class);
    return child;
  }

  private static String fixture(String name) {
    return new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/validation/" + name)
      .getPath();
  }

  /**
   * Verifies RQ-197: validation opens no connection, whereas the control run confirms that the
   * connection provider works as expected.
   */
  @Test
  public void testNoConnectionOpens() throws Exception {
    assertEquals(0, cleanExit);
    assertEquals(new ArrayList<String>(), Records.read(clean.record(Records.PROVIDER)));
    assertEquals(ScenarioValidator.EXIT_CODE, defectsExit);
    assertEquals(new ArrayList<String>(), Records.read(defects.record(Records.PROVIDER)));
    assertEquals(1, dropExit);
    List<String> opened = Records.read(drop.record(Records.PROVIDER));
    assertEquals(opened.toString(), 1, opened.size());
    assertTrue(opened.get(0), opened.get(0).startsWith("jdbc:phoenix:localhost"));
  }

  /**
   * Verifies RQ-198: every validation finding is printed to standard output and recorded in the
   * JSON report, and the process terminates with exit code 65.
   */
  @Test
  public void testCliPrintsAllAndExitsWithValidationCode() throws Exception {
    assertEquals(65, ScenarioValidator.EXIT_CODE);
    assertNotEquals(0, defectsExit);
    assertNotEquals(1, defectsExit);
    assertEquals(ScenarioValidator.EXIT_CODE, defectsExit);

    PhoenixUtil.setRowCountOverride(0);
    ValidationReport expected = ScenarioValidator
      .validate(Collections.singletonList(new File(fixture("defects.xml")).toPath()), null);
    assertTrue(expected.getErrors() >= 10);
    List<String> out = Files.readAllLines(defects.out().toPath(), StandardCharsets.UTF_8);
    for (Finding finding : expected.getFindings()) {
      assertTrue("Not printed: " + finding, out.contains(finding.toString()));
    }
    File report = new File(defects.work(), "RESULTS/VALIDATION_defects.json");
    assertTrue(out.toString(), out.contains("Validation: " + expected.getErrors()
      + " error(s) in 1 file(s). Report: " + new File("RESULTS/VALIDATION_defects.json")));
    JsonObject json = read(report);
    assertEquals("pherf-validation", json.get("format").getAsString());
    assertEquals(1, json.get("version").getAsInt());
    assertEquals("defects", json.get("label").getAsString());
    assertEquals(expected.getErrors(), json.get("errors").getAsInt());
    JsonArray findings = json.getAsJsonArray("findings");
    assertEquals(expected.getErrors(), findings.size());
    for (int i = 0; i < findings.size(); i++) {
      Finding finding = expected.getFindings().get(i);
      JsonObject entry = findings.get(i).getAsJsonObject();
      assertEquals(finding.getRule().id(), entry.get("rule").getAsString());
      assertEquals(finding.getWhere(), entry.get("where").getAsString());
      assertEquals(finding.getMessage(), entry.get("message").getAsString());
    }
  }

  /**
   * Verifies that validating a scenario with no name produces validation findings, writes a
   * validation report, and terminates with the validation exit code.
   */
  @Test
  public void testMissingNameExitsWithValidationCode() throws Exception {
    assertEquals(ScenarioValidator.EXIT_CODE, unnamedExit);
    JsonObject json = read(new File(unnamed.work(), "RESULTS/VALIDATION_unnamed.json"));
    JsonArray findings = json.getAsJsonArray("findings");
    assertEquals(json.toString(), 2, findings.size());
    assertEquals("xml.unknown-attribute",
      findings.get(0).getAsJsonObject().get("rule").getAsString());
    assertEquals("xml.value", findings.get(1).getAsJsonObject().get("rule").getAsString());
    List<String> out = Files.readAllLines(unnamed.out().toPath(), StandardCharsets.UTF_8);
    assertTrue(out.toString(), out.contains("Validation: 2 error(s) in 1 file(s). Report: "
      + new File("RESULTS/VALIDATION_unnamed.json")));
  }

  /**
   * Verifies that validating a clean scenario exits with status code 0 and writes a validation
   * report containing no findings.
   */
  @Test
  public void testCleanExitsZero() throws Exception {
    assertEquals(0, cleanExit);
    JsonObject json = read(new File(clean.work(), "RESULTS/VALIDATION_clean.json"));
    assertEquals(0, json.get("errors").getAsInt());
    assertEquals(0, json.getAsJsonArray("findings").size());
    assertEquals(2, json.getAsJsonArray("files").size());
    assertTrue(json.toString(), json.getAsJsonArray("schemaFiles").size() > 0);
  }

  /**
   * Verifies that running -validate with launch overrides still reports the findings that the
   * pre-run gate detects in the files as written, ensuring that a passing -validate run guarantees
   * a passing pre-run gate.
   */
  @Test
  public void testOverridesKeepTheGateFindings() throws Exception {
    assertEquals(ScenarioValidator.EXIT_CODE, rangeExit);
    PhoenixUtil.setRowCountOverride(0);
    ValidationReport gate =
      ScenarioValidator.validate(rangeScenario.getPath(), null, new LinkedHashMap<>());
    assertEquals(gate.getFindings().toString(), 1, gate.getErrors());
    assertEquals("openloop.rate", gate.getFindings().get(0).getRule().id());
    JsonArray findings =
      read(new File(range.work(), "RESULTS/VALIDATION_range.json")).getAsJsonArray("findings");
    assertEquals(findings.toString(), 1, findings.size());
    JsonObject entry = findings.get(0).getAsJsonObject();
    assertEquals("openloop.rate", entry.get("rule").getAsString());
    assertEquals(gate.getFindings().get(0).getWhere(), entry.get("where").getAsString());
    assertEquals(gate.getFindings().get(0).getMessage(), entry.get("message").getAsString());
  }

  private static JsonObject read(File file) throws Exception {
    return JsonParser
      .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }
}
