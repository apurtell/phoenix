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
import static org.apache.phoenix.pherf.validation.ValidationFixtures.edit;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.findings;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.rules;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.validate;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.variant;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.apache.phoenix.pherf.validation.ValidationReport.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies the XML structure validation rules by checking element and attribute names and values
 * against the JAXB model defined by the configuration classes.
 */
public class XmlStructureTest {
  @org.junit.Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  /**
   * Verifies requirement RQ-193 by ensuring that an unknown XML element is reported with both its
   * element name and its line number.
   */
  @Test
  public void testUnknownElementReportedByName() throws Exception {
    String path = ValidationFixtures.file(ValidationFixtures.UNKNOWN_ELEMENT);
    ValidationReport report = validate(path);
    assertEquals(report.getFindings().toString(), 1, report.getErrors());
    Finding finding = report.getFindings().get(0);
    assertEquals(ValidationReport.Rule.XML_UNKNOWN_ELEMENT, finding.getRule());
    assertEquals(path, finding.getFile());
    assertEquals(Integer.valueOf(ValidationFixtures.line(path, "<notATag")), finding.getLine());
    assertEquals("datamodel/scenarios/scenario[1]/notATag", finding.getWhere());
    assertTrue(finding.getMessage(), finding.getMessage().contains("<notATag>"));
  }

  /**
   * Verifies the behavior carried over from P6: when a value cannot be parsed as its target type,
   * the underlying parser silently reads it as absent or 0 without raising an error, whereas the
   * validator explicitly reports each invalid value by its attribute or element.
   */
  @Test
  public void testInvalidValuesReportedByAttribute() throws Exception {
    File profileDir = folder.newFolder("profile");
    String path = variant(profileDir, CLEAN,
      edit(CLEAN_PROFILE, "<openLoop arrival=\"UNIFORM\" lagPolicy=\"LATE\">",
        "<openLoop arrival=\"POISON\" lagPolicy=\"drop\" seed=\"s1\" maxLagMs=\"1.5\">"),
      edit(CLEAN_PROFILE, "<stage durationMs=\"1000\" rate=\"10\"/>",
        "<stage durationMs=\"1e3\" rate=\"ten\"/>"));
    String profile = new File(profileDir, CLEAN_PROFILE).getPath();
    List<String> expected = Arrays.asList(profile + " workloadProfile/openLoop/@arrival 'POISON'",
      profile + " workloadProfile/openLoop/@lagPolicy 'drop'",
      profile + " workloadProfile/openLoop/@seed 's1'",
      profile + " workloadProfile/openLoop/@maxLagMs '1.5'",
      profile + " workloadProfile/openLoop/stage[1]/@durationMs '1e3'",
      profile + " workloadProfile/openLoop/stage[1]/@rate 'ten'");
    assertEquals(expected, values(validate(path)));

    File scenarioDir = folder.newFolder("scenario");
    path = variant(scenarioDir, CLEAN,
      edit(CLEAN, "executionType=\"SERIAL\"", "executionType=\"serial\" warmupExecutions=\"two\""),
      edit(CLEAN, "<length>8</length>", "<length>x</length>"));
    expected = Arrays.asList(path + " datamodel/datamapping/column[3]/length 'x'",
      path + " datamodel/scenarios/scenario[1]/querySet[2]/@executionType 'serial'",
      path + " datamodel/scenarios/scenario[1]/querySet[2]/@warmupExecutions 'two'");
    assertEquals(expected, values(validate(path)));

    // JAXB matches enum values verbatim without trimming whitespace, and the validator enforces
    // the same exact matching behavior.
    File trimDir = folder.newFolder("trim");
    path = variant(trimDir, CLEAN,
      edit(CLEAN, "executionType=\"SERIAL\"", "executionType=\" PARALLEL \""));
    assertEquals(
      Arrays.asList(
        path + " datamodel/scenarios/scenario[1]/querySet[2]/@executionType" + " ' PARALLEL '"),
      values(validate(path)));
    assertEquals(null, new XMLConfigParser(path).getDataModels().get(0).getScenarios().get(0)
      .getQuerySet().get(1).getExecutionType());
    // A numeric attribute value that fails to parse aborts parsing of the file, preventing any
    // subsequent validation rules from executing.
    assertTrue(rules(validate(variant(folder.newFolder("fatal"), CLEAN,
      edit(CLEAN, "rowCount=\"1000\"", "rowCount=\"1k\"")))).equals(Arrays.asList("xml.value")));
  }

  /**
   * Formats all xml.value findings into "file where 'value'" strings while verifying the expected
   * message prefix.
   */
  private static List<String> values(ValidationReport report) {
    List<String> values = new ArrayList<>();
    for (Finding finding : findings(report, Rule.XML_VALUE)) {
      String message = finding.getMessage();
      int start = message.indexOf('\'');
      assertTrue(message, message.startsWith("the value '"));
      values.add(finding.getFile() + " " + finding.getWhere() + " "
        + message.substring(start, message.indexOf('\'', start + 1) + 1));
    }
    return values;
  }
}
