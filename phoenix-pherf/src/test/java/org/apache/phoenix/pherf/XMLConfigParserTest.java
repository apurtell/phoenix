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
package org.apache.phoenix.pherf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.bind.UnmarshalException;
import javax.xml.stream.XMLStreamException;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XMLConfigParserTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(XMLConfigParserTest.class);

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testScenarioByAbsolutePath() throws Exception {
    File f = new File(tmp.getRoot(), "outside_cp_scenario.xml");
    try (InputStream in =
      XMLConfigParserTest.class.getResourceAsStream("/scenario/test_evt_gen1.xml")) {
      Files.copy(in, f.toPath());
    }
    DataModel expected = new XMLConfigParser(".*scenario/test_evt_gen1.xml").getDataModels().get(0);
    XMLConfigParser parser = new XMLConfigParser(f.getAbsolutePath());
    assertEquals(1, parser.getDataModels().size());
    DataModel model = parser.getDataModels().get(0);
    assertEquals(expected.getName(), model.getName());
    assertEquals(expected.getScenarios().size(), model.getScenarios().size());
    assertEquals(expected.getScenarios().size(), parser.getScenarios().size());
  }

  @Test
  public void testScenarioFromClasspath() throws Exception {
    XMLConfigParser parser = new XMLConfigParser(".*test_scenario.xml");
    assertEquals(1, parser.getDataModels().size());
    assertEquals(5, parser.getScenarios().size());
  }

  @Test
  public void testDTDInScenario() throws Exception {
    URL scenarioUrl =
      XMLConfigParserTest.class.getResource("/scenario/malicious_scenario_with_dtd.xml");
    assertNotNull(scenarioUrl);
    Path p = Paths.get(scenarioUrl.toURI());
    try {
      XMLConfigParser.readDataModel(p);
      fail("The scenario should have failed to parse because it contains a DTD");
    } catch (UnmarshalException e) {
      // If we don't parse the DTD, the variable 'name' won't be defined in the XML
      LOGGER.warn("Caught expected exception", e);
      Throwable cause = e.getLinkedException();
      assertTrue("Cause was a " + cause.getClass(), cause instanceof XMLStreamException);
    }
  }
}
