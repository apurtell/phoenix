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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.run.Golden;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies under requirement RQ-194 that the XML unmarshaller remains lenient during parsing, while
 * strict enforcement against unknown names is delegated exclusively to the validator.
 */
public class ParserCompatibilityTest {
  private static final String GOLDEN = "scenario-fixture-models.golden";
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  // When a query lacks an explicit identifier, Query.getId() automatically generates a random ID.
  private static final String RANDOM_ID =
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Before
  public void noRowCountOverride() {
    // Clear any row-count override left behind by another test in this JVM, because an active
    // override would alter the marshalled row counts.
    PhoenixUtil.setRowCountOverride(0);
  }

  @Test
  public void testUnknownElementFixtureLoads() throws Exception {
    XMLConfigParser parser = new XMLConfigParser(ValidationFixtures.file("unknown_element.xml"));
    assertEquals(1, parser.getDataModels().size());
    DataModel model = parser.getDataModels().get(0);
    assertEquals("validation_unknown_element", model.getName());
    assertEquals(1, model.getDataMappingColumns().size());
    Scenario scenario = model.getScenarios().get(0);
    assertEquals("unknownElement", scenario.getName());
    assertEquals(10, scenario.getRowCount());
    QuerySet querySet = scenario.getQuerySet().get(0);
    assertEquals("count", querySet.getQuery().get(0).getId());
    assertEquals("select count(*) from PHERF.TEST_TABLE",
      querySet.getQuery().get(0).getStatement());
  }

  /**
   * Verifies that every scenario fixture produces the exact same marshalled data models as the
   * source tree did prior to introducing the validator. The reference golden file was captured
   * before any main production code changes for the validator round were applied.
   */
  @Test
  public void testScenarioFixturesLoadUnchanged() throws Exception {
    File[] files = ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"));
    Arrays.sort(files);
    StringBuilder text = new StringBuilder();
    int loaded = 0;
    for (File file : files) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      text.append("== ").append(file.getName()).append('\n');
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLConfigParser.writeDataModel(model, out);
        text.append(new String(out.toByteArray(), StandardCharsets.UTF_8).replaceAll(RANDOM_ID,
          "<RANDOM-ID>"));
      }
      loaded++;
    }
    assertEquals(files.length - 1, loaded);
    Golden.assertMatches(getClass().getSimpleName(), GOLDEN, text.toString());
  }

  @Test
  public void testDtdFixtureStillRejected() throws Exception {
    try {
      new XMLConfigParser(new File(ChildJvm.scenarioDir(), DTD_FIXTURE).getPath());
      fail("A scenario with a DTD loaded");
    } catch (Exception e) {
      assertTrue(e.toString(), e instanceof javax.xml.stream.XMLStreamException
        || e instanceof javax.xml.bind.JAXBException);
    }
  }
}
