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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Test;

/**
 * Verifies the openLoop element of a querySet, covering its XML binding and default values, the
 * validation check across all query sets before any query set starts, and the copying of the
 * configuration into the XML result. Under RQ-165, no existing fixture defines an openLoop element.
 */
public class OpenLoopConfigTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  private static final String FULL = "<querySet concurrency=\"8\" warmupExecutions=\"2\">"
    + "<openLoop arrival=\"POISSON\" seed=\"7\" maxLagMs=\"50\" lagPolicy=\"DROP\">"
    + "<stage durationMs=\"10000\" rate=\"100\"/>"
    + "<stage durationMs=\"10000\" rate=\"100\" endRate=\"400\"/>"
    + "<stage durationMs=\"10000\" rate=\"400\"/></openLoop>"
    + "<query id=\"q1\" statement=\"select 1\"/></querySet>";

  private static QuerySet bind(String xml) throws Exception {
    return JAXBContext.newInstance(QuerySet.class).createUnmarshaller()
      .unmarshal(new StreamSource(new StringReader(xml)), QuerySet.class).getValue();
  }

  private static QuerySet valid() throws Exception {
    return bind(FULL);
  }

  /**
   * Places the given query set second in a scenario after a valid query set, and runs the open-loop
   * validation check on the enclosing data model.
   */
  private static void check(QuerySet querySet) throws Exception {
    Scenario scenario = new Scenario();
    scenario.setName("checked");
    scenario.setQuerySet(Arrays.asList(valid(), querySet));
    DataModel dataModel = new DataModel();
    dataModel.setScenarios(Collections.singletonList(scenario));
    QueryExecutor.checkOpenLoop(dataModel);
  }

  private static void assertRejected(QuerySet querySet, String attribute) throws Exception {
    try {
      check(querySet);
      fail("Accepted an open loop with a bad " + attribute);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage();
      assertTrue(message, message.contains(attribute) && message.contains("checked")
        && message.contains("querySet 2"));
    }
  }

  @Test
  public void testBindingOfEveryAttribute() throws Exception {
    QuerySet querySet = valid();
    OpenLoop openLoop = querySet.getOpenLoop();
    assertNotNull(openLoop);
    assertEquals(OpenLoop.Arrival.POISSON, openLoop.getArrival());
    assertEquals(Long.valueOf(7), openLoop.getSeed());
    assertEquals(Long.valueOf(50), openLoop.getMaxLagMs());
    assertEquals(OpenLoop.LagPolicy.DROP, openLoop.getLagPolicy());
    assertEquals(3, openLoop.getStage().size());
    OpenLoop.Stage ramp = openLoop.getStage().get(1);
    assertEquals(10_000, ramp.getDurationMs());
    assertEquals(100.0, ramp.getRate(), 0);
    assertEquals(Double.valueOf(400), ramp.getEndRate());
    assertNull(openLoop.getStage().get(0).getEndRate());
    assertEquals(1, querySet.getQuery().size());
  }

  @Test
  public void testAbsentAttributesAreNull() throws Exception {
    QuerySet querySet = bind("<querySet concurrency=\"2\"><openLoop>"
      + "<stage durationMs=\"5\" rate=\"10\"/></openLoop></querySet>");
    OpenLoop openLoop = querySet.getOpenLoop();
    assertNull(openLoop.getArrival());
    assertNull(openLoop.getSeed());
    assertNull(openLoop.getMaxLagMs());
    assertNull(openLoop.getLagPolicy());
    check(querySet);
    assertNull(bind("<querySet concurrency=\"2\"/>").getOpenLoop());
  }

  @Test
  public void testValidConfigPasses() throws Exception {
    check(valid());
    // A pause stage configured with a rate of 0 is permitted as a valid workload stage.
    QuerySet pause = valid();
    pause.getOpenLoop().getStage().get(0).setRate(0.0);
    check(pause);
    // The largest lag limit and total stage duration whose microsecond (us) values fit within a
    // long are accepted as valid configuration bounds.
    QuerySet limits = valid();
    limits.getOpenLoop().setMaxLagMs(Long.MAX_VALUE / 1000);
    limits.getOpenLoop().getStage().get(0).setDurationMs(Long.MAX_VALUE / 1000 - 20_000);
    check(limits);
  }

  @Test
  public void testEachRuleRejectsWithItsAttribute() throws Exception {
    for (String concurrency : new String[] { "1-4", "0", "-1", "x", "", "99999999999" }) {
      QuerySet querySet = valid();
      querySet.setConcurrency(concurrency);
      assertRejected(querySet, "concurrency");
    }
    QuerySet noStage = valid();
    noStage.getOpenLoop().setStage(new ArrayList<OpenLoop.Stage>());
    assertRejected(noStage, "stage");
    QuerySet duration = valid();
    duration.getOpenLoop().getStage().get(2).setDurationMs(0);
    assertRejected(duration, "durationMs");
    for (double rate : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
      QuerySet badRate = valid();
      badRate.getOpenLoop().getStage().get(0).setRate(rate);
      assertRejected(badRate, "rate");
      QuerySet badEnd = valid();
      badEnd.getOpenLoop().getStage().get(1).setEndRate(rate);
      assertRejected(badEnd, "endRate");
    }
    QuerySet noRate = valid();
    noRate.getOpenLoop().getStage().get(2).setRate(null);
    assertRejected(noRate, "stage 3: rate");
    QuerySet noArrival = valid();
    for (OpenLoop.Stage stage : noArrival.getOpenLoop().getStage()) {
      stage.setRate(0.0);
      stage.setEndRate(null);
    }
    assertRejected(noArrival, "rate");
    for (long durationMs : new long[] { Long.MAX_VALUE / 1000 - 19_999, Long.MAX_VALUE }) {
      QuerySet longStage = valid();
      longStage.getOpenLoop().getStage().get(0).setDurationMs(durationMs);
      assertRejected(longStage, "durationMs");
    }
    QuerySet longLag = valid();
    longLag.getOpenLoop().setMaxLagMs(Long.MAX_VALUE / 1000 + 1);
    assertRejected(longLag, "maxLagMs");
    QuerySet noSeed = valid();
    noSeed.getOpenLoop().setSeed(null);
    assertRejected(noSeed, "seed");
    QuerySet negativeLag = valid();
    negativeLag.getOpenLoop().setMaxLagMs(-1L);
    assertRejected(negativeLag, "maxLagMs");
    QuerySet dropNoLag = valid();
    dropNoLag.getOpenLoop().setMaxLagMs(null);
    assertRejected(dropNoLag, "maxLagMs");
  }

  /**
   * Verifies that constructing a result copy preserves the open-loop configuration and that
   * marshaling the result retains the element in the output XML.
   */
  @Test
  public void testResultCopiesAndMarshalsOpenLoop() throws Exception {
    QuerySetResult copy = new QuerySetResult(valid());
    assertNotNull(copy.getOpenLoop());
    ScenarioResult scenario = new ScenarioResult();
    scenario.setName("s");
    scenario.setTableName("T");
    scenario.setQuerySetResult(Collections.singletonList(copy));
    DataModelResult result = new DataModelResult();
    result.setScenarioResult(Collections.singletonList(scenario));
    JAXBContext context = JAXBContext.newInstance(DataModelResult.class);
    Marshaller marshaller = context.createMarshaller();
    StringWriter xml = new StringWriter();
    marshaller.marshal(result, xml);
    assertTrue(xml.toString(), xml.toString().contains("<openLoop "));
    DataModelResult back =
      (DataModelResult) context.createUnmarshaller().unmarshal(new StringReader(xml.toString()));
    OpenLoop openLoop = back.getScenarioResult().get(0).getQuerySetResult().get(0).getOpenLoop();
    assertEquals(OpenLoop.LagPolicy.DROP, openLoop.getLagPolicy());
    assertEquals(3, openLoop.getStage().size());
    assertEquals(Double.valueOf(400), openLoop.getStage().get(1).getEndRate());
    // A closed-loop query set writes no openLoop element into the marshaled XML output.
    QuerySet closed = valid();
    closed.setOpenLoop(null);
    scenario.setQuerySetResult(Collections.singletonList(new QuerySetResult(closed)));
    StringWriter plain = new StringWriter();
    marshaller.marshal(result, plain);
    assertTrue(plain.toString(), !plain.toString().contains("openLoop"));
  }

  /**
   * Verifies requirement RQ-165 by asserting that every existing scenario fixture is configured to
   * run in closed-loop mode.
   */
  @Test
  public void testExistingFixturesHaveNoOpenLoop() throws Exception {
    int querySets = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        for (Scenario scenario : model.getScenarios()) {
          for (QuerySet querySet : scenario.getQuerySet()) {
            assertNull(file.getName(), querySet.getOpenLoop());
            querySets++;
          }
        }
      }
    }
    assertTrue(querySets > 10);
  }
}
