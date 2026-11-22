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

import static org.apache.phoenix.pherf.validation.ValidationFixtures.CLEAN;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.CLEAN_PROFILE;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.DEFECTS;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.RELEASE;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.edit;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.rules;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.validate;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.variant;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.run.ReferenceScenarios;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ValidationFixtures.Edit;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.apache.phoenix.pherf.workload.QueryExecutor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies the scenario validation requirements for RQ-197 and RQ-198 by confirming that each check
 * uses a dedicated rule identifier, that a single validation pass collects every finding for a
 * file, and that precision guards prevent false findings across the existing scenarios.
 */
public class ScenarioValidatorTest {
  private static final String LOOKUP_PREFIX = "SELECT V FROM PHERF.VALIDATION_CLEAN ";
  private static final String LOOKUP = LOOKUP_PREFIX + "WHERE K1 = [K1] AND K2 = [K2]\"/>";

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  /** Verifies that RQ-197 XML structure validation detects unrecognized elements. */
  @Test
  public void testXmlStructure() throws Exception {
    assertOne("xml.unknown-element", "<notATag>",
      edit(CLEAN, "<coGeneration seed=\"7\" blockSize=\"50\"/>",
        "<coGeneration seed=\"7\" blockSize=\"50\"/><notATag/>"));
  }

  /**
   * Verifies RQ-197 predicate casing checks when a placeholder or a quoted column matches its
   * definition only when case is ignored.
   */
  @Test
  public void testPredicateCasing() throws Exception {
    // The query leaves [k2] unresolved in the statement because a rule is defined only for K2.
    assertOne("predicate.casing", "[k2]",
      edit(CLEAN, LOOKUP, LOOKUP_PREFIX + "WHERE K1 = [K1] AND K2 = [k2]\"/>"));
    // A quoted identifier preserves its casing, whereas the table defines K2 rather than k2.
    assertOne("predicate.casing", "\"k2\"",
      edit(CLEAN, LOOKUP, LOOKUP_PREFIX + "WHERE K1 = [K1] AND &quot;k2&quot; = [K2]\"/>"));
  }

  /**
   * Verifies RQ-197 primary key coverage checks, ensuring that every key column has a generation
   * rule, that a point lookup leaves no gap in its key prefix, and that each comparison references
   * the column corresponding to its placeholder.
   */
  @Test
  public void testPrimaryKeyCoverage() throws Exception {
    // The predicate leaves K2 unbound between K1 and K3.
    String k3 = "<name>K2</name>\n        </column>\n        <column><type>INTEGER</type>"
      + "<userDefined>true</userDefined><dataSequence>SEQUENTIAL</dataSequence>"
      + "<minValue>0</minValue><maxValue>2000000000</maxValue><name>K3</name></column>";
    assertOne("key.coverage", "but not K2 between them",
      edit(CLEAN, "<name>K2</name>\n        </column>", k3),
      edit(CLEAN, "V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2)",
        "K3 INTEGER NOT NULL, V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2, K3)"),
      edit(CLEAN, LOOKUP, LOOKUP_PREFIX + "WHERE K1 = [K1] AND K3 = [K3]\"/>"));
    // When a key column has no rule, the writer cannot populate it, and the lookup is not checked
    // again.
    assertOne("key.coverage", "K3", edit(CLEAN, "V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2)",
      "K3 INTEGER NOT NULL, V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2, K3)"));
    // An aligned comparison must name the column that matches its placeholder.
    assertOne("key.coverage", "[K2]",
      edit(CLEAN, LOOKUP, LOOKUP_PREFIX + "WHERE K1 = [K1] AND K2 = [K2] AND V = [K2]\"/>"));
    // These cases are valid because the decoder binds each placeholder from the drawn row: a
    // lookup on a non-key column, a lookup on the leading key of a clustered table, and a lookup
    // against another table using a column with a different name.
    assertNone(edit(CLEAN, LOOKUP, LOOKUP_PREFIX + "WHERE V = [V]\"/>"));
    assertNone(
      edit(CLEAN, "<coGeneration seed=\"7\" blockSize=\"50\"/>",
        "<coGeneration seed=\"7\" blockSize=\"50\" clusterLimit=\"10\"/>"),
      edit(CLEAN, LOOKUP, "SELECT COUNT(*) FROM PHERF.VALIDATION_CLEAN WHERE K1 = [K1]\"/>"));
    assertNone(edit(CLEAN, LOOKUP,
      "SELECT * FROM PHERF.VALIDATION_CHILD WHERE PARENT_ID = [K1]" + " AND PARENT_K2 = [K2]\"/>"));
  }

  /**
   * Verifies that when a typo leaves a required runtime value null, the validator reports a finding
   * for the affected element and continues executing the remaining rules without stopping.
   */
  @Test
  public void testMissingRequiredValues() throws Exception {
    String scenario = "datamodel/scenarios/scenario[1]";
    String ddl = scenario + "/preScenarioDdls/ddl[1]";
    String[][] cases = {
      { "name=\"validationClean\"", "nmae=\"validationClean\"", scenario + "/@nmae",
        scenario + "/@name" },
      { "statement=\"CREATE", "statment=\"CREATE", ddl + "/@statment", ddl },
      { "<ddl tableName=", "<ddl tableNmae=", ddl + "/@tableNmae", ddl } };
    for (String[] c : cases) {
      ValidationReport report =
        validate(variant(folder.newFolder(), CLEAN, edit(CLEAN, c[0], c[1])));
      List<String> actual = new ArrayList<>();
      for (Finding finding : report.getFindings()) {
        actual.add(finding.getRule().id() + " " + finding.getWhere());
      }
      assertEquals(Arrays.asList("xml.unknown-attribute " + c[2], "xml.value " + c[3]), actual);
      assertTrue(report.getFindings().get(1).getMessage(),
        report.getFindings().get(1).getMessage().contains("NullPointerException"));
      Path written = report.write(folder.getRoot(), "missing");
      JsonObject json =
        JsonParser.parseString(new String(Files.readAllBytes(written), StandardCharsets.UTF_8))
          .getAsJsonObject();
      assertEquals(2, json.get("errors").getAsInt());
      assertEquals("xml.value",
        json.getAsJsonArray("findings").get(1).getAsJsonObject().get("rule").getAsString());
    }
  }

  /** Verifies that RQ-197 arrival rate validation rejects negative rates. */
  @Test
  public void testArrivalRate() throws Exception {
    assertOne("openloop.rate", "rate is negative",
      edit(CLEAN_PROFILE, "rate=\"10\"", "rate=\"-1\""));
  }

  /** Verifies the RQ-197 seed requirement that a POISSON arrival distribution defines a seed. */
  @Test
  public void testSeed() throws Exception {
    assertOne("openloop.seed", "POISSON requires a seed",
      edit(CLEAN_PROFILE, "arrival=\"UNIFORM\"", "arrival=\"POISSON\""));
    ValidationReport report = validate(variant(folder.newFolder(), CLEAN,
      edit(CLEAN_PROFILE, "arrival=\"UNIFORM\"", "arrival=\"POISSON\" seed=\"5\"")));
    assertEquals(new ArrayList<String>(), rules(report));
  }

  @Test
  public void testCleanFixturePasses() throws Exception {
    ValidationReport report = validate(ValidationFixtures.file(CLEAN));
    assertEquals(report.getFindings().toString(), 0, report.getErrors());
  }

  /**
   * Verifies RQ-198 by confirming that a single pass reports every structural and co-generation
   * finding in the defects fixture in deterministic order.
   */
  @Test
  public void testOnePass() throws Exception {
    String path = ValidationFixtures.file(DEFECTS);
    ValidationReport report = validate(path);
    String scenario = "datamodel/scenarios/scenario[1]";
    List<String> expected = Arrays.asList("xml.unknown-attribute datamodel/@revision",
      "xml.value datamodel/datamapping/column[3]/length",
      "xml.unknown-element " + scenario + "/notATag",
      "xml.value " + scenario + "/querySet[1]/openLoop/@arrival",
      "openloop.rate scenario defectsA querySet 1", "openloop.rate scenario defectsA querySet 1",
      "query.kind scenario defectsA querySet 1 query 2",
      "predicate.in-list scenario defectsA querySet 1 query 1",
      "key.coverage scenario defectsA querySet 2 query 1",
      "cogen.walk scenario defectsA querySet 2", "cogen.settings scenario defectsC",
      "cogen.writer-blocks scenario defectsD", "cogen.watermark scenario defectsA",
      "cogen.offsets scenario defectsB");
    List<String> actual = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Finding finding : report.getFindings()) {
      assertEquals(path, finding.getFile());
      actual.add(finding.getRule().id() + " " + finding.getWhere());
      ids.add(finding.getRule().id());
    }
    assertEquals(expected, actual);
    assertEquals(12, ids.size());
    // A structural finding includes a source line number, whereas a model finding has none.
    assertEquals(Integer.valueOf(ValidationFixtures.line(path, "<notATag/>")),
      report.getFindings().get(2).getLine());
    assertEquals(null, report.getFindings().get(4).getLine());
  }

  /**
   * Verifies RQ-152 by confirming that the release attribute from older Pherf versions on the
   * datamodel element produces no validation finding, whereas a different unknown attribute on
   * datamodel still produces a finding.
   */
  @Test
  public void testReleaseAttributeIsKnown() throws Exception {
    String path = ValidationFixtures.file(RELEASE);
    assertEquals(new ArrayList<String>(), rules(validate(path)));

    ValidationReport report = validate(
      variant(folder.newFolder(), RELEASE, edit(RELEASE, "release=\"192\"", "revision=\"192\"")));
    List<String> actual = new ArrayList<>();
    for (Finding finding : report.getFindings()) {
      actual.add(finding.getRule().id() + " " + finding.getWhere());
    }
    assertEquals(Collections.singletonList("xml.unknown-attribute datamodel/@revision"), actual);
  }

  /**
   * Verifies RQ-192, carried forward from P9, by confirming that all reference scenarios and their
   * associated smoke profiles validate with no error.
   */
  @Test
  public void testReferenceScenariosHaveNoError() throws Exception {
    PhoenixUtil.setRowCountOverride(0);
    List<Path> all = new ArrayList<>();
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      Path path = reference.scenarioFile().toPath().toAbsolutePath();
      all.add(path);
      ValidationReport report = ScenarioValidator.validate(Collections.singletonList(path), null);
      assertEquals(reference.name + ": " + report.getFindings(), 0, report.getErrors());
      assertEquals(Arrays.asList(path.toString(),
        reference.profileFile().toPath().toAbsolutePath().toString()), report.getFiles());
    }
    ValidationReport report = ScenarioValidator.validate(all, null);
    assertEquals(report.getFindings().toString(), 0, report.getErrors());
    assertEquals(6, report.getFiles().size());
  }

  /**
   * Verifies that every existing scenario file validates with only its genuine structural defects,
   * ensuring that a subsequent validation gate rejects nothing that currently runs except those
   * known defective files, using the schema files from a default run on the test class path.
   */
  @Test
  public void testExistingFixtures() throws Exception {
    PhoenixUtil.setRowCountOverride(0);
    File module = ChildJvm.targetDir().getParentFile();
    Map<String, List<String>> expected = new LinkedHashMap<>();
    expected.put("src/test/resources/scenario/malicious_scenario_with_dtd.xml",
      Collections.singletonList("xml.parse datamodel"));
    expected.put("src/test/resources/malicious_results_with_dtd.xml",
      Collections.singletonList("xml.parse datamodel"));
    int files = 0;
    for (String dir : new String[] { "config", "src/test/resources", "src/it/resources" }) {
      for (File file : xmlFiles(new File(module, dir))) {
        String name = module.toPath().relativize(file.toPath()).toString();
        List<String> actual = new ArrayList<>();
        ValidationReport report =
          ScenarioValidator.validate(Collections.singletonList(file.toPath()), ".*.sql");
        for (Finding finding : report.getFindings()) {
          actual.add(finding.getRule().id() + " " + finding.getWhere());
        }
        List<String> want =
          expected.containsKey(name) ? expected.remove(name) : Collections.<String> emptyList();
        assertEquals(name + ": " + report.getFindings(), want, actual);
        files++;
      }
    }
    assertEquals("Fixtures not found: " + expected.keySet(), 0, expected.size());
    assertTrue(files > 25);
  }

  /**
   * Collects the scenario XML files under a directory while excluding profile files, HBase
   * configuration files, and golden files.
   */
  private static List<File> xmlFiles(File dir) {
    List<File> files = new ArrayList<>();
    File[] children = dir.listFiles();
    Arrays.sort(children);
    for (File child : children) {
      String name = child.getName();
      if (child.isDirectory()) {
        if (!name.equals("golden") && !name.equals("validation")) {
          files.addAll(xmlFiles(child));
        }
      } else if (
        name.endsWith(".xml") && !name.endsWith("_profile.xml") && !name.equals("hbase-site.xml")
      ) {
        files.add(child);
      }
    }
    return files;
  }

  /**
   * Verifies that the in-run checks preserve their evaluation order and diagnostic messages, such
   * that the data model check reports the first failure in the original sequence and each
   * per-object check produces the same message.
   */
  @Test
  public void testInRunChecksKeepOrder() throws Exception {
    String path = variant(folder.getRoot(), CLEAN,
      edit(CLEAN_PROFILE, "<openLoop arrival=\"UNIFORM\" lagPolicy=\"LATE\">",
        "<openLoop arrival=\"POISSON\" lagPolicy=\"DROP\" maxLagMs=\"-1\">"),
      edit(CLEAN_PROFILE, "rate=\"10\"", "rate=\"-1\""),
      edit(CLEAN_PROFILE, "weight=\"10\"", "weight=\"0\""));
    PhoenixUtil.setRowCountOverride(0);
    DataModel model = new XMLConfigParser(path).getDataModels().get(0);
    Scenario scenario = model.getScenarios().get(0);
    QuerySet querySet = scenario.getQuerySet().get(0);
    String where = QueryExecutor.openLoopWhere(1, scenario);
    String rates = message(() -> QueryExecutor.checkOpenLoopRates(where, querySet));
    assertEquals(where + ": stage 1: rate is negative or not finite: -1.0", rates);
    assertEquals(rates, message(() -> QueryExecutor.checkOpenLoop(model)));
    assertEquals(where + ": arrival POISSON requires a seed",
      message(() -> QueryExecutor.checkArrivalSeed(where, querySet.getOpenLoop())));
    assertEquals(where + ": maxLagMs is negative: -1",
      message(() -> QueryExecutor.checkOpenLoopLag(where, querySet.getOpenLoop())));
    String mix = message(() -> QueryExecutor.checkMix(scenario, 1, querySet));
    assertEquals("mix of querySet 1 of scenario validationClean (profile clean_cogen_profile.xml)"
      + ": weight of kind DELETE is not an integer of at least 1: '0'", mix);
  }

  /**
   * Verifies RQ-198 by confirming that the serialized JSON report emits all top-level fields in the
   * expected order and includes every finding.
   */
  @Test
  public void testJsonReportFields() throws Exception {
    ValidationReport report = validate(ValidationFixtures.file(DEFECTS));
    Path written = report.write(folder.getRoot(), "label1");
    assertEquals("VALIDATION_label1.json", written.getFileName().toString());
    String text = new String(Files.readAllBytes(written), StandardCharsets.UTF_8);
    String[] keys = { "\"format\"", "\"version\"", "\"label\"", "\"files\"", "\"schemaFiles\"",
      "\"errors\"", "\"findings\"" };
    int last = -1;
    for (String key : keys) {
      int at = text.indexOf(key);
      assertTrue(key + " out of order: " + text, at > last);
      last = at;
    }
    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
    assertEquals("pherf-validation", json.get("format").getAsString());
    assertEquals(1, json.get("version").getAsInt());
    assertEquals("label1", json.get("label").getAsString());
    assertEquals(report.getErrors(), json.get("errors").getAsInt());
    JsonArray findings = json.getAsJsonArray("findings");
    assertEquals(report.getErrors(), findings.size());
    for (int i = 0; i < findings.size(); i++) {
      JsonObject entry = findings.get(i).getAsJsonObject();
      Finding finding = report.getFindings().get(i);
      assertEquals(finding.getRule().id(), entry.get("rule").getAsString());
      assertEquals(finding.getFile(), entry.get("file").getAsString());
      assertEquals(finding.getWhere(), entry.get("where").getAsString());
      assertEquals(finding.getMessage(), entry.get("message").getAsString());
      JsonElement line = entry.get("line");
      assertEquals(finding.getLine(), line == null ? null : Integer.valueOf(line.getAsInt()));
    }
    assertEquals(null, findings.get(4).getAsJsonObject().get("line"));
    assertTrue(new File(folder.getRoot(), "VALIDATION_label1.json.tmp").exists() == false);
  }

  private void assertNone(Edit... edits) throws Exception {
    ValidationReport report = validate(variant(folder.newFolder(), CLEAN, edits));
    assertEquals(report.getFindings().toString(), new ArrayList<String>(), rules(report));
  }

  private void assertOne(String rule, String text, Edit... edits) throws Exception {
    ValidationReport report = validate(variant(folder.newFolder(), CLEAN, edits));
    assertEquals(Collections.singletonList(rule), rules(report));
    String message = report.getFindings().get(0).getMessage();
    assertTrue(message, message.contains(text));
  }

  private interface Check {
    void run();
  }

  private static String message(Check check) {
    try {
      check.run();
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
    fail("No IllegalArgumentException");
    return null;
  }
}
