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
package org.apache.phoenix.pherf.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.configuration.OperationMix;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies workload profile resolution and operation mix validation. Under RQ-190, an external
 * workload profile file supplies a query set with its open-loop configuration and its operation
 * mix. A querySet element references the profile using a path relative to the scenario file, and
 * the parser resolves the profile during parsing so that every parse produces the same populated
 * query sets. Operation mix validation executes alongside the open-loop validation check. Under
 * RQ-152, no existing test fixture references a profile or defines an operation mix.
 */
public class WorkloadProfileTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  private static final String MIX = "<mix><operation kind=\"SELECT\" weight=\"70\"/>"
    + "<operation kind=\"SCAN\" weight=\"20\"/><operation kind=\"DELETE\" weight=\"10\"/></mix>";
  private static final String OPEN_LOOP =
    "<openLoop><stage durationMs=\"100\" rate=\"10\"/></openLoop>";
  private static final String QUERIES = "<query id=\"get\" statement=\"select 1\"/>"
    + "<query id=\"scan\" kind=\"SCAN\" statement=\"select 2\"/>"
    + "<query id=\"delete\" kind=\"DELETE\" statement=\"delete 3\"/>";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(ChildJvm.targetDir());

  private static Path fixture() {
    return new File(ChildJvm.targetDir().getParentFile(),
      "src/test/resources/profile/profile_run.xml").toPath();
  }

  private static QuerySet bind(String xml) throws Exception {
    return JAXBContext.newInstance(QuerySet.class).createUnmarshaller()
      .unmarshal(new StreamSource(new StringReader(xml)), QuerySet.class).getValue();
  }

  private static <T> String marshal(Class<T> type, T bean) throws Exception {
    Marshaller marshaller = JAXBContext.newInstance(type).createMarshaller();
    StringWriter xml = new StringWriter();
    marshaller.marshal(new JAXBElement<>(new QName("bean"), type, bean), xml);
    return xml.toString();
  }

  private static QuerySet querySet(DataModel model, int scenario) {
    return model.getScenarios().get(scenario).getQuerySet().get(0);
  }

  private static void assertMix(List<OperationMix> mix, String... kindWeights) {
    assertNotNull(mix);
    assertEquals(kindWeights.length / 2, mix.size());
    for (int i = 0; i < mix.size(); i++) {
      assertEquals(kindWeights[2 * i], mix.get(i).getKind());
      assertEquals(kindWeights[2 * i + 1], mix.get(i).getWeight());
    }
  }

  private static void assertCurve(OpenLoop openLoop) {
    assertNotNull(openLoop);
    assertEquals(2, openLoop.getStage().size());
    assertEquals(500, openLoop.getStage().get(0).getDurationMs());
    assertEquals(1000.0, openLoop.getStage().get(0).getRate(), 0);
    assertNull(openLoop.getStage().get(0).getEndRate());
    assertEquals(500, openLoop.getStage().get(1).getDurationMs());
    assertEquals(1000.0, openLoop.getStage().get(1).getRate(), 0);
    assertEquals(Double.valueOf(2000), openLoop.getStage().get(1).getEndRate());
  }

  @Test
  public void testResolutionCopiesOpenLoopAndMix() throws Exception {
    DataModel model = XMLConfigParser.readDataModel(fixture());
    QuerySet querySet = querySet(model, 0);
    assertEquals("mix_profile.xml", querySet.getProfile());
    assertCurve(querySet.getOpenLoop());
    assertMix(querySet.getMix(), "SELECT", "70", "SCAN", "20", "DELETE", "10");
    // Verify that the scenario retains its own local attributes and query definitions after profile
    // resolution.
    assertEquals("4", querySet.getConcurrency());
    assertEquals(3, querySet.getQuery().size());
    QueryExecutor.checkOpenLoop(model);
    QueryExecutor.checkKinds(model);
    QueryExecutor.checkMix(model);
  }

  /**
   * Verifies that when a single profile file is loaded across two scenarios, each scenario receives
   * equal configuration values instantiated as separate bean instances on every parse.
   */
  @Test
  public void testOneProfileLoadsInTwoScenarios() throws Exception {
    DataModel model = XMLConfigParser.readDataModel(fixture());
    QuerySet a = querySet(model, 0);
    QuerySet b = querySet(model, 1);
    assertEquals("profileA", model.getScenarios().get(0).getName());
    assertEquals("profileB", model.getScenarios().get(1).getName());
    assertNotSame(a.getOpenLoop(), b.getOpenLoop());
    assertNotSame(a.getMix(), b.getMix());
    assertEquals(marshal(OpenLoop.class, a.getOpenLoop()),
      marshal(OpenLoop.class, b.getOpenLoop()));
    assertCurve(b.getOpenLoop());
    assertMix(b.getMix(), "SELECT", "70", "SCAN", "20", "DELETE", "10");
    // Calling getScenarios() triggers a fresh parse of the configuration, which also resolves the
    // referenced workload profile.
    XMLConfigParser parser = new XMLConfigParser(fixture().toString());
    for (Scenario scenario : parser.getScenarios()) {
      assertCurve(scenario.getQuerySet().get(0).getOpenLoop());
    }
    assertEquals(2, parser.getScenarios().size());
  }

  /**
   * Verifies that a scenario loaded from the classpath resolves its referenced workload profile
   * from the classpath as well, such as when packaged inside a JAR archive.
   */
  @Test
  public void testClassPathScenarioFindsItsProfile() throws Exception {
    DataModel model = XMLConfigParser.readDataModel(Paths.get("/profile/profile_run.xml"));
    assertCurve(querySet(model, 0).getOpenLoop());
  }

  /**
   * Writes a scenario configuration file containing a single query set into dir, populated with the
   * specified querySet attributes and inner XML body.
   */
  private File scenario(File dir, String attributes, String body) throws Exception {
    File file = new File(dir, "scenario.xml");
    write(file,
      "<datamodel name=\"m\"><scenarios><scenario tableName=\"PHERF.T\" rowCount=\"1\""
        + " name=\"resolved\"><querySet concurrency=\"1\" " + attributes + ">" + body + QUERIES
        + "</querySet></scenario></scenarios></datamodel>");
    return file;
  }

  private static void write(File file, String text) throws Exception {
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Asserts that parsing the scenario fails with a diagnostic message identifying the scenario, the
   * query set, and the referenced workload profile.
   */
  private static IllegalArgumentException assertUnresolved(File scenario, String profile,
    String reason) throws Exception {
    try {
      XMLConfigParser.readDataModel(scenario.toPath());
      fail("Resolved a bad profile: " + reason);
      return null;
    } catch (IllegalArgumentException e) {
      String message = e.getMessage();
      assertTrue(message, message.contains("scenario resolved") && message.contains("querySet 1")
        && message.contains(profile) && message.contains(reason));
      return e;
    }
  }

  @Test
  public void testRelativePathWithParent() throws Exception {
    File dir = folder.newFolder();
    write(new File(dir, "profiles/p.xml"),
      "<workloadProfile>" + OPEN_LOOP + MIX + "</workloadProfile>");
    File scenario = scenario(new File(dir, "scenarios"), "profile=\"../profiles/p.xml\"", "");
    QuerySet querySet =
      XMLConfigParser.readDataModel(scenario.toPath()).getScenarios().get(0).getQuerySet().get(0);
    assertEquals(100, querySet.getOpenLoop().getStage().get(0).getDurationMs());
    assertEquals(3, querySet.getMix().size());
  }

  @Test
  public void testResolutionErrors() throws Exception {
    File dir = folder.newFolder();
    assertUnresolved(scenario(dir, "profile=\"absent.xml\"", ""), "absent.xml", "cannot read");

    write(new File(dir, "root.xml"), "<datamodel name=\"x\"/>");
    assertUnresolved(scenario(dir, "profile=\"root.xml\"", ""), "root.xml", "workloadProfile");

    write(new File(dir, "mixonly.xml"), "<workloadProfile>" + MIX + "</workloadProfile>");
    assertUnresolved(scenario(dir, "profile=\"mixonly.xml\"", ""), "mixonly.xml", "no openLoop");

    write(new File(dir, "full.xml"), "<workloadProfile>" + OPEN_LOOP + MIX + "</workloadProfile>");
    assertUnresolved(scenario(dir, "profile=\"full.xml\"", OPEN_LOOP), "full.xml", "openLoop");
    assertUnresolved(scenario(dir, "profile=\"full.xml\"", MIX), "full.xml", "mix");
  }

  /**
   * Verifies that a DTD declaration inside a workload profile is not processed, leaving its
   * external entity undefined so that parsing the profile fails.
   */
  @Test
  public void testDtdInProfileIsRejected() throws Exception {
    File dir = folder.newFolder();
    File secret = new File(dir, "secret.txt");
    write(secret, "100");
    write(new File(dir, "dtd.xml"),
      "<?xml version=\"1.0\"?><!DOCTYPE workloadProfile [<!ENTITY d SYSTEM \"" + secret.toURI()
        + "\">]><workloadProfile><openLoop><stage durationMs=\"&d;\""
        + " rate=\"10\"/></openLoop></workloadProfile>");
    IllegalArgumentException e =
      assertUnresolved(scenario(dir, "profile=\"dtd.xml\"", ""), "dtd.xml", "cannot read");
    boolean stream = false;
    for (Throwable t = e; t != null; t = t.getCause()) {
      stream |= t instanceof XMLStreamException;
      if (t instanceof javax.xml.bind.UnmarshalException) {
        Throwable linked = ((javax.xml.bind.UnmarshalException) t).getLinkedException();
        stream |= linked instanceof XMLStreamException;
      }
    }
    assertTrue(String.valueOf(e.getCause()), stream);
  }

  /**
   * Places the target query set in the second position of a scenario following a valid query set,
   * and runs operation mix validation on the data model.
   */
  private static void checkMix(QuerySet querySet) throws Exception {
    Scenario scenario = new Scenario();
    scenario.setName("checked");
    scenario.setQuerySet(Arrays.asList(
      bind("<querySet concurrency=\"1\">" + OPEN_LOOP + MIX + QUERIES + "</querySet>"), querySet));
    DataModel dataModel = new DataModel();
    dataModel.setScenarios(Collections.singletonList(scenario));
    QueryExecutor.checkMix(dataModel);
  }

  private static void assertMixRejected(String body, String reason) throws Exception {
    QuerySet querySet = bind("<querySet concurrency=\"1\">" + body + "</querySet>");
    try {
      checkMix(querySet);
      fail("Accepted a bad mix: " + reason);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage();
      assertTrue(message,
        message.contains("checked") && message.contains("querySet 2") && message.contains(reason));
    }
  }

  private static String mix(String... kindWeights) {
    StringBuilder text = new StringBuilder("<mix>");
    for (int i = 0; i < kindWeights.length; i += 2) {
      text.append("<operation");
      if (kindWeights[i] != null) {
        text.append(" kind=\"").append(kindWeights[i]).append('"');
      }
      if (kindWeights[i + 1] != null) {
        text.append(" weight=\"").append(kindWeights[i + 1]).append('"');
      }
      text.append("/>");
    }
    return text.append("</mix>").toString();
  }

  @Test
  public void testCheckMixErrors() throws Exception {
    // An inline operation mix binds to the same configuration bean, and a valid definition passes
    // validation.
    checkMix(bind("<querySet concurrency=\"2\">" + OPEN_LOOP + MIX + QUERIES + "</querySet>"));
    assertMixRejected(MIX + QUERIES, "openLoop");
    assertMixRejected(OPEN_LOOP + "<mix/>" + QUERIES, "no operation");
    assertMixRejected(
      OPEN_LOOP + mix("SELECT", "1", "UPDATE", "1", "SCAN", "1", "DELETE", "1") + QUERIES,
      "UPDATE");
    assertMixRejected(
      OPEN_LOOP + mix("SELECT", "1", null, "1", "SCAN", "1", "DELETE", "1") + QUERIES, "kind");
    assertMixRejected(
      OPEN_LOOP + mix("SELECT", "1", "SCAN", "1", "SELECT", "2", "DELETE", "1") + QUERIES,
      "SELECT is given twice");
    for (String weight : new String[] { "x", "0", "-1", "1.5", "", null }) {
      assertMixRejected(OPEN_LOOP + mix("SELECT", "1", "SCAN", weight, "DELETE", "1") + QUERIES,
        "weight");
    }
    assertMixRejected(OPEN_LOOP + mix("SELECT", "5000", "SCAN", "5000", "DELETE", "1") + QUERIES,
      "10000");
    assertMixRejected(
      OPEN_LOOP + mix("SELECT", "1", "SCAN", "1") + "<query id=\"get\" statement=\"select 1\"/>",
      "no query of kind SCAN");
    assertMixRejected(OPEN_LOOP + mix("SELECT", "1", "SCAN", "1") + QUERIES, "delete");
  }

  /**
   * Verifies that the validation failure message for an invalid operation mix originating from a
   * workload profile includes the name of the profile.
   */
  @Test
  public void testCheckMixNamesTheProfile() throws Exception {
    QuerySet querySet = bind(
      "<querySet concurrency=\"1\">" + OPEN_LOOP + mix("SELECT", "0") + QUERIES + "</querySet>");
    querySet.setProfile("named_profile.xml");
    try {
      checkMix(querySet);
      fail();
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("named_profile.xml"));
    }
  }

  /**
   * Verifies that a marshalled result copy preserves the profile reference, the open-loop load
   * curve, and the operation mix, while omitting those elements when their values are null.
   */
  @Test
  public void testResultEchoesProfileOpenLoopAndMix() throws Exception {
    QuerySet querySet = querySet(XMLConfigParser.readDataModel(fixture()), 0);
    ScenarioResult scenario = new ScenarioResult();
    scenario.setName("s");
    scenario.setTableName("T");
    scenario.setQuerySetResult(Collections.singletonList(new QuerySetResult(querySet)));
    DataModelResult result = new DataModelResult();
    result.setScenarioResult(Collections.singletonList(scenario));
    JAXBContext context = JAXBContext.newInstance(DataModelResult.class);
    StringWriter xml = new StringWriter();
    context.createMarshaller().marshal(result, xml);
    String text = xml.toString();
    assertTrue(text, text.contains("profile=\"mix_profile.xml\"") && text.contains("<openLoop")
      && text.contains("<mix><operation kind=\"SELECT\" weight=\"70\"/>"));
    DataModelResult back =
      (DataModelResult) context.createUnmarshaller().unmarshal(new StringReader(text));
    QuerySetResult copy = back.getScenarioResult().get(0).getQuerySetResult().get(0);
    assertEquals("mix_profile.xml", copy.getProfile());
    assertCurve(copy.getOpenLoop());
    assertMix(copy.getMix(), "SELECT", "70", "SCAN", "20", "DELETE", "10");

    QuerySet plain = bind("<querySet concurrency=\"1\">" + QUERIES + "</querySet>");
    assertNull(plain.getProfile());
    assertNull(plain.getMix());
    scenario.setQuerySetResult(Collections.singletonList(new QuerySetResult(plain)));
    StringWriter none = new StringWriter();
    context.createMarshaller().marshal(result, none);
    assertFalse(none.toString(), none.toString().contains("profile=")
      || none.toString().contains("<mix") || none.toString().contains("operation"));
  }

  /**
   * Verifies under RQ-152 that no existing scenario fixture references a workload profile or
   * defines an operation mix.
   */
  @Test
  public void testExistingFixturesHaveNoProfile() throws Exception {
    int querySets = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        for (Scenario scenario : model.getScenarios()) {
          for (QuerySet querySet : scenario.getQuerySet()) {
            assertNull(file.getName(), querySet.getProfile());
            assertNull(file.getName(), querySet.getMix());
            querySets++;
          }
        }
        QueryExecutor.checkMix(model);
      }
    }
    assertTrue(querySets > 10);
  }
}
