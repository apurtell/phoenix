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
import static org.apache.phoenix.pherf.validation.ValidationFixtures.edit;
import static org.apache.phoenix.pherf.validation.ValidationFixtures.variant;
import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.RunOverrides;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.validation.ValidationFixtures.Edit;
import org.apache.phoenix.pherf.validation.ValidationReport.Finding;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that running -validate with launch overrides validates both the models as written and
 * the models after the overrides are applied. Each finding is reported once, ensuring that a
 * passing -validate run guarantees a passing pre-run gate.
 */
public class ValidateOverridesTest {
  /**
   * Configures the open loop query set with a concurrency range, which the open loop validation
   * check rejects.
   */
  private static final Edit OPEN_LOOP_RANGE =
    edit(CLEAN, "concurrency=\"1\" profile=\"clean_cogen_profile.xml\"",
      "concurrency=\"1-2\" profile=\"clean_cogen_profile.xml\"");
  /** Introduces a validation defect that launch overrides do not alter. */
  private static final Edit CASING =
    edit(CLEAN, "SELECT V FROM PHERF.VALIDATION_CLEAN WHERE K1 = [K1] AND K2 = [K2]",
      "SELECT V FROM PHERF.VALIDATION_CLEAN WHERE K1 = [K1] AND K2 = [k2]");

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void noRowCountOverride() {
    PhoenixUtil.setRowCountOverride(0);
  }

  /**
   * Verifies that the pre-run gate validates the file as written and that its finding is retained
   * even when the -threadCount 2 override removes the concurrency range from the models.
   */
  @Test
  public void testGateFindingStaysWithOverrides() throws Exception {
    String path = variant(folder.newFolder(), CLEAN, OPEN_LOOP_RANGE);
    List<String> gate = lines(gate(path));
    assertEquals(gate.toString(), 1, gate.size());
    assertEquals(gate.toString(), "openloop.rate", gate.get(0).split(" ")[0]);
    assertEquals(gate, lines(withOverrides(path, "2")));
  }

  /**
   * Verifies that a finding produced exclusively by the overridden models is also reported after
   * the findings from the pre-run gate. When 2000 threads run against 1000 rows, the closed loop
   * query set leaves a worker without an assigned row.
   */
  @Test
  public void testUnionOfBothWays() throws Exception {
    String path = variant(folder.newFolder(), CLEAN, OPEN_LOOP_RANGE);
    List<String> expected = lines(gate(path));
    List<String> overridden = lines(withOverrides(variant(folder.newFolder(), CLEAN), "2000"));
    assertEquals(overridden.toString(), 1, overridden.size());
    assertEquals(overridden.toString(), "cogen.walk", overridden.get(0).split(" ")[0]);
    // Because the variant and the clean copy reside at different paths, the finding references the
    // variant path.
    expected.add(overridden.get(0).replace(overridden.get(0).split(" ")[1], path));
    assertEquals(expected, lines(withOverrides(path, "2000")));
  }

  /**
   * Verifies that a defect detected by both the unoverridden and overridden validation passes is
   * reported only once.
   */
  @Test
  public void testDefectOfBothWaysOnce() throws Exception {
    String path = variant(folder.newFolder(), CLEAN, OPEN_LOOP_RANGE, CASING);
    List<String> gate = lines(gate(path));
    assertEquals(gate.toString(), Arrays.asList("openloop.rate", "predicate.casing"),
      ValidationFixtures.rules(gate(path)));
    assertEquals(gate, lines(withOverrides(path, "2")));
  }

  /**
   * Verifies that supplying empty overrides produces the same validation report as the pre-run
   * gate.
   */
  @Test
  public void testNoOverridesIsTheGate() throws Exception {
    String path = variant(folder.newFolder(), CLEAN, OPEN_LOOP_RANGE, CASING);
    Map<Path, DataModel> models = new LinkedHashMap<>();
    ValidationReport report =
      ScenarioValidator.validate(path, null, models, RunOverrides.of(null, null, null, true));
    assertEquals(lines(gate(path)), lines(report));
    assertEquals(1, models.size());
  }

  private static ValidationReport gate(String path) throws Exception {
    return ScenarioValidator.validate(path, null, new LinkedHashMap<>());
  }

  private static ValidationReport withOverrides(String path, String threads) throws Exception {
    return ScenarioValidator.validate(path, null, new LinkedHashMap<>(),
      RunOverrides.of(null, threads, null, true));
  }

  private static List<String> lines(ValidationReport report) {
    List<String> lines = new ArrayList<>();
    for (Finding finding : report.getFindings()) {
      lines.add(finding.toString());
    }
    return lines;
  }
}
