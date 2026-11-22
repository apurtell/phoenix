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
package org.apache.phoenix.pherf.workload.cogen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import org.apache.phoenix.pherf.configuration.CoGeneration;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.CoGeneration.WatermarkPolicy;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.operations.TenantOperationFactory;
import org.junit.Test;

/**
 * Verifies RQ-181 and RQ-182 by testing the coGeneration configuration element, its default values
 * and validation checks, its effect on generator behavior, and its copy into the XML result. It
 * also confirms that no existing fixture includes this element.
 */
public class CoGenerationConfigTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  private static final Pattern K2 = Pattern.compile("K2 = (\\d+)");

  @Test
  public void testBinding() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    CoGeneration configured =
      CoGenFixtures.scenario(parser, CoGenFixtures.CONFIGURED).getCoGeneration();
    assertEquals("7", configured.getSeed());
    assertEquals("1000", configured.getOffset());
    assertEquals("7", configured.getBlockSize());
    assertEquals("2", configured.getClusterLimit());
    assertEquals("LOAD", configured.getWatermark());
    assertEquals("SEQUENTIAL", configured.getWalk());
    // Any absent attribute remains null so that the result output faithfully echoes the input.
    CoGeneration defaults =
      CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS).getCoGeneration();
    assertNotNull(defaults);
    assertNull(defaults.getSeed());
    assertNull(defaults.getOffset());
    assertNull(defaults.getBlockSize());
    assertNull(defaults.getClusterLimit());
    assertNull(defaults.getWatermark());
    assertNull(defaults.getWalk());
    // The writer parsing path binds the same configuration element.
    assertEquals("7",
      parser.getScenarioByName(CoGenFixtures.CONFIGURED).getCoGeneration().getBlockSize());
    int classes = 0;
    for (Column rule : parser.getDataModels().get(0).getDataMappingColumns()) {
      if (rule.getName().equals("G1") || rule.getName().equals("G2")) {
        assertTrue(rule.getGeneratorClass(), rule.getGeneratorClass()
          .startsWith("org.apache.phoenix.pherf.rules.IndexedGeneratorTest$"));
        classes++;
      } else {
        assertNull(rule.getGeneratorClass());
      }
    }
    assertEquals(2, classes);
  }

  /**
   * Verifies the default values of the scenario schema and confirms that a run without a load phase
   * starts at the scenario row count.
   */
  @Test
  public void testDefaults() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS);
    CoGenerator.Settings settings = CoGenerator.Settings.of(scenario);
    assertEquals(0, settings.seed);
    assertEquals(0, settings.offset);
    assertEquals(1000, settings.blockSize);
    assertNull(settings.clusterLimit);
    assertEquals(WatermarkPolicy.COMMIT, settings.watermark);
    assertEquals(WalkStrategy.MULTIPLICATIVE, settings.walk);
    RowCursor cursor = CoGenerator.of(parser, scenario).cursor();
    assertEquals(30, cursor.watermark());
    assertEquals(30, cursor.highWater());
    // A single parser instance and scenario name yield the same shared generator across both the
    // writer and reader parsing paths.
    assertSame(CoGenerator.of(parser, scenario),
      CoGenerator.of(parser, parser.getScenarioByName(CoGenFixtures.DEFAULTS)));
    assertNotSame(CoGenerator.of(parser, scenario),
      CoGenerator.of(CoGenFixtures.parser(), scenario));
  }

  /**
   * Verifies RQ-181 by confirming that each setting alters the observed generator behavior when
   * comparing cogenConfigured against cogenDefaults, both parsed from XML: block claims of at most
   * 7 rows versus 1000, at most 2 leading key values in a block versus one per row, a LOAD
   * watermark that waits for load completion versus a COMMIT watermark that advances with each
   * block, and a SEQUENTIAL walk of 0, 1, 2 versus a hashed traversal order.
   */
  @Test
  public void testSettingsChangeBehaviour() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    CoGenerator configured =
      CoGenerator.of(parser, CoGenFixtures.scenario(parser, CoGenFixtures.CONFIGURED));
    CoGenerator defaults =
      CoGenerator.of(parser, CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS));

    // Verify block size allocation limits and watermark advancement policies during data loading.
    configured.beginLoad();
    defaults.beginLoad();
    RowCursor.Load configuredLoad = configured.load();
    RowCursor.Load defaultsLoad = defaults.load();
    List<RowCursor.Block> configuredBlocks = new ArrayList<>();
    for (int k = 0; k < 5; k++) {
      RowCursor.Block block = configuredLoad.claim(1000);
      assertEquals(7, block.size());
      configuredLoad.committed(block);
      assertEquals(0, configured.cursor().watermark());
      configuredBlocks.add(block);
      RowCursor.Block big = defaultsLoad.claim(5000);
      assertEquals(1000, big.size());
      defaultsLoad.committed(big);
      assertEquals(big.end, defaults.cursor().watermark());
    }
    configuredLoad.finish();
    assertEquals(35, configured.cursor().watermark());

    // Verify batch clustering bounds on the leading primary key column across allocated blocks.
    List<Column> columns = CoGenFixtures.tableColumns();
    IndexDecoder clustered = CoGenFixtures.decoder(parser, CoGenFixtures.CONFIGURED, "K2");
    for (RowCursor.Block block : configuredBlocks) {
      Set<Object> leading = new HashSet<>();
      for (long i = block.start; i < block.end; i++) {
        leading.add(clustered.value(columns.get(1), i).getValue());
      }
      assertTrue(leading.toString(), leading.size() <= 2);
    }
    IndexDecoder unclustered = CoGenFixtures.decoder(parser, CoGenFixtures.DEFAULTS, "K2");
    Set<Object> leading = new HashSet<>();
    for (long i = 0; i < 1000; i++) {
      leading.add(unclustered.value(columns.get(1), i).getValue());
    }
    assertEquals(1000, leading.size());

    // Evaluate the walk strategy using a fresh parser instance so that the committed rows default
    // to the 30 rows configured for each scenario.
    XMLConfigParser readers = CoGenFixtures.parser();
    Connection connection = CoGenFixtures.keyedConnection("K1", "K2");
    List<Long> sequential = reads(readers, CoGenFixtures.CONFIGURED, connection);
    List<Long> hashed = reads(readers, CoGenFixtures.DEFAULTS, connection);
    List<Long> expected = new ArrayList<>();
    for (long i = 0; i < 30; i++) {
      expected.add(1000 + i);
    }
    assertEquals(expected, sequential);
    List<Long> identity = new ArrayList<>();
    for (long i = 0; i < 30; i++) {
      identity.add(i);
    }
    assertNotEquals(identity, hashed);
    Collections.sort(hashed);
    assertEquals(identity, hashed);
  }

  /**
   * Collects the K2 column values across 30 reads from a single reader, where K2 represents the
   * global index.
   */
  private static List<Long> reads(XMLConfigParser parser, String name, Connection connection)
    throws Exception {
    Scenario scenario = CoGenFixtures.scenario(parser, name);
    CoGenerator.Reader reader = CoGenerator.of(parser, scenario).reader(0, 1);
    String sql = scenario.getQuerySet().get(0).getQuery().get(0).getStatement();
    List<Long> keys = new ArrayList<>();
    for (int k = 0; k < 30; k++) {
      Matcher m = K2.matcher(reader.statement(sql, connection));
      assertTrue(m.find());
      keys.add(Long.parseLong(m.group(1)));
    }
    return keys;
  }

  /**
   * Verifies that an invalid configuration value fails upon first use with an error message
   * identifying both the scenario and the offending attribute, and that an unparseable value also
   * includes the rejected value in the message rather than falling back to the default.
   */
  @Test
  public void testChecks() throws Exception {
    assertRejected(c -> c.setOffset("-1"), "offset");
    assertRejected(c -> c.setBlockSize("0"), "blockSize");
    assertRejected(c -> c.setClusterLimit("0"), "clusterLimit");
    assertRejected(c -> c.setWalk("SEQUENTAIL"), "walk", "'SEQUENTAIL'");
    assertRejected(c -> c.setWatermark("LAOD"), "watermark", "'LAOD'");
    assertRejected(c -> c.setSeed("7a"), "seed", "'7a'");
    assertRejected(c -> c.setOffset("1e3"), "offset", "'1e3'");
    assertRejected(c -> c.setBlockSize("10000000000"), "blockSize", "'10000000000'");
    assertRejected(c -> c.setClusterLimit(""), "clusterLimit", "''");
    // Valid attribute text may include surrounding whitespace without causing parsing to fail.
    XMLConfigParser parser = CoGenFixtures.parser();
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS);
    scenario.getCoGeneration().setWalk(" SEQUENTIAL ");
    scenario.getCoGeneration().setBlockSize(" 5");
    CoGenerator.Settings settings = CoGenerator.Settings.of(scenario);
    assertEquals(WalkStrategy.SEQUENTIAL, settings.walk);
    assertEquals(5, settings.blockSize);
  }

  private interface Change {
    void apply(CoGeneration coGeneration);
  }

  private static void assertRejected(Change change, String... texts) throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS);
    change.apply(scenario.getCoGeneration());
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        CoGenerator.of(parser, scenario);
        fail("accepted a bad " + texts[0]);
      } catch (IllegalArgumentException e) {
        assertMessage(e, CoGenFixtures.DEFAULTS);
        assertMessage(e, texts);
      }
    }
  }

  private static void assertMessage(Exception e, String... texts) {
    for (String text : texts) {
      assertTrue(e.getMessage() + " has no " + text, e.getMessage().contains(text));
    }
  }

  /**
   * Verifies that because a cluster limit below the block size repeats the leading key within a
   * block, a single-column primary key fails when creating the first decoder. A cluster limit at
   * the block size does not cluster, and a two-column primary key is accepted.
   */
  @Test
  public void testClusterLimitNeedsTwoKeyColumns() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    CoGenerator configured =
      CoGenerator.of(parser, CoGenFixtures.scenario(parser, CoGenFixtures.CONFIGURED));
    try {
      configured.decoder(CoGenFixtures.keyedConnection("K1"));
      fail("accepted a clusterLimit with a primary key of one column");
    } catch (IllegalArgumentException e) {
      assertMessage(e, "clusterLimit", CoGenFixtures.CONFIGURED, "K1");
    }
    assertNotNull(configured.decoder(CoGenFixtures.keyedConnection("K1", "K2")));
    XMLConfigParser unclustered = CoGenFixtures.parser();
    Scenario scenario = CoGenFixtures.scenario(unclustered, CoGenFixtures.CONFIGURED);
    scenario.getCoGeneration().setClusterLimit("7");
    assertNotNull(
      CoGenerator.of(unclustered, scenario).decoder(CoGenFixtures.keyedConnection("K1")));
  }

  /**
   * Verifies that because generator state is keyed by scenario name, a co-generated scenario whose
   * name appears across two data models is rejected with an error message identifying the scenario
   * name and both data models.
   */
  @Test
  public void testScenarioNameMustBeUnique() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    DataModel second =
      XMLConfigParser.readDataModel(Paths.get(CoGenFixtures.path(CoGenFixtures.FIXTURE)));
    second.setName("second");
    parser.getDataModels().add(second);
    try {
      CoGenerator.of(parser, CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS));
      fail("accepted a scenario name in two data models");
    } catch (IllegalArgumentException e) {
      assertMessage(e, CoGenFixtures.DEFAULTS, "cogeneration", "second");
    }
  }

  /**
   * Verifies that copying a scenario into a result model preserves the coGeneration element in the
   * marshalled XML, whereas a scenario without co-generation omits the element entirely.
   */
  @Test
  public void testResultCopy() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    ScenarioResult copy =
      new ScenarioResult(CoGenFixtures.scenario(parser, CoGenFixtures.CONFIGURED));
    assertNotNull(copy.getCoGeneration());
    DataModelResult result = new DataModelResult();
    result.setScenarioResult(Collections.singletonList(copy));
    JAXBContext context = JAXBContext.newInstance(DataModelResult.class);
    Marshaller marshaller = context.createMarshaller();
    StringWriter xml = new StringWriter();
    marshaller.marshal(result, xml);
    assertTrue(xml.toString(), xml.toString().contains("<coGeneration "));
    DataModelResult back =
      (DataModelResult) context.createUnmarshaller().unmarshal(new StringReader(xml.toString()));
    CoGeneration bean = back.getScenarioResult().get(0).getCoGeneration();
    assertEquals("7", bean.getSeed());
    assertEquals("1000", bean.getOffset());
    assertEquals("7", bean.getBlockSize());
    assertEquals("2", bean.getClusterLimit());
    assertEquals("LOAD", bean.getWatermark());
    assertEquals("SEQUENTIAL", bean.getWalk());
    Scenario plain = CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS);
    plain.setCoGeneration(null);
    result.setScenarioResult(Collections.singletonList(new ScenarioResult(plain)));
    StringWriter none = new StringWriter();
    marshaller.marshal(result, none);
    assertTrue(none.toString(), !none.toString().contains("coGeneration"));
  }

  /**
   * Verifies RQ-182 by confirming that no existing scenario fixture defines the coGeneration
   * element, ensuring that every existing scenario continues to use the standard rule generators.
   */
  @Test
  public void testExistingFixturesHaveNoCoGeneration() throws Exception {
    int scenarios = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        for (Scenario scenario : model.getScenarios()) {
          assertNull(file.getName(), scenario.getCoGeneration());
          scenarios++;
        }
      }
    }
    assertTrue(scenarios > 10);
  }

  /**
   * Verifies that the load profile execution path explicitly rejects scenarios configured with
   * co-generation and reports that co-generation is unsupported.
   */
  @Test
  public void testLoadProfileRejected() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.DEFAULTS);
    try {
      new TenantOperationFactory(PhoenixUtil.create(), parser.getDataModels().get(0), scenario);
      fail("the load profile path accepted coGeneration");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(),
        e.getMessage().contains("coGeneration") && e.getMessage().contains(CoGenFixtures.DEFAULTS)
          && e.getMessage().contains("load profile"));
    }
  }
}
