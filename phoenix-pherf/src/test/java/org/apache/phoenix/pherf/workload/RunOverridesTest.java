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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.RunOverrides;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Test;

/**
 * Verifies RQ-191 on the configuration beans: the -runDurationMs, -threadCount, and -targetRate
 * overrides update every query set in the run after parsing completes, including any curve loaded
 * from a profile. The existing open loop validation check subsequently accepts the modified result,
 * and when no override is supplied, the data model remains unchanged.
 */
public class RunOverridesTest {
  private static final String CLOSED = "<querySet concurrency=\"1-4\" numberOfExecutions=\"7\""
    + " executionDurationInMs=\"60000\"><query id=\"c\" statement=\"select 1\"/></querySet>";
  private static final String OPEN =
    "<querySet concurrency=\"2\"><openLoop>" + "<stage durationMs=\"100\" rate=\"10\"/>"
      + "<stage durationMs=\"200\" rate=\"10\" endRate=\"30\"/>"
      + "<stage durationMs=\"300\" rate=\"0\"/>" + "<stage durationMs=\"400\" rate=\"20\"/>"
      + "</openLoop><query id=\"o\" statement=\"select 2\"/></querySet>";

  private static QuerySet bind(String xml) throws Exception {
    return JAXBContext.newInstance(QuerySet.class).createUnmarshaller()
      .unmarshal(new StreamSource(new StringReader(xml)), QuerySet.class).getValue();
  }

  private static List<DataModel> model(String... querySets) throws Exception {
    Scenario scenario = new Scenario();
    scenario.setName("overridden");
    QuerySet[] sets = new QuerySet[querySets.length];
    for (int i = 0; i < sets.length; i++) {
      sets[i] = bind(querySets[i]);
    }
    scenario.setQuerySet(Arrays.asList(sets));
    DataModel dataModel = new DataModel();
    dataModel.setName("m");
    dataModel.setScenarios(Collections.singletonList(scenario));
    return Collections.singletonList(dataModel);
  }

  private static QuerySet set(List<DataModel> models, int position) {
    return models.get(0).getScenarios().get(0).getQuerySet().get(position);
  }

  private static RunOverrides of(String runDurationMs, String threadCount, String targetRate) {
    return RunOverrides.of(runDurationMs, threadCount, targetRate, true);
  }

  private static long[] durations(OpenLoop openLoop) {
    long[] durations = new long[openLoop.getStage().size()];
    for (int i = 0; i < durations.length; i++) {
      durations[i] = openLoop.getStage().get(i).getDurationMs();
    }
    return durations;
  }

  @Test
  public void testThreadCountAlone() throws Exception {
    List<DataModel> models = model(CLOSED, OPEN);
    of(null, "3", null).apply(models);
    assertEquals("3", set(models, 0).getConcurrency());
    assertEquals(3, set(models, 0).getMinConcurrency());
    assertEquals(3, set(models, 0).getMaxConcurrency());
    assertEquals("3", set(models, 1).getConcurrency());
    // Verify that all other configuration attributes remain unchanged.
    assertEquals(60000, set(models, 0).getExecutionDurationInMs());
    assertEquals(7, set(models, 0).getNumberOfExecutions());
    assertEquals(1000, Arrays.stream(durations(set(models, 1).getOpenLoop())).sum());
    QueryExecutor.checkOpenLoop(models.get(0));
  }

  /**
   * Verifies that the stages preserve their relative shape: each stage is assigned floor(d * D /
   * T), while the last stage receives the remaining duration.
   */
  @Test
  public void testRunDurationAlone() throws Exception {
    List<DataModel> models = model(CLOSED, OPEN);
    of("333", null, null).apply(models);
    // Stages of 100, 200, 300, and 400 ms out of 1000 ms scale to 33, 66, and 99 ms, while the
    // final stage receives the remainder of 333 - 198 = 135 ms.
    assertEquals(Arrays.toString(new long[] { 33, 66, 99, 135 }),
      Arrays.toString(durations(set(models, 1).getOpenLoop())));
    assertEquals(333, set(models, 0).getExecutionDurationInMs());
    assertEquals(7, set(models, 0).getNumberOfExecutions());
    assertEquals(10.0, set(models, 1).getOpenLoop().getStage().get(0).getRate(), 0);
    QueryExecutor.checkOpenLoop(models.get(0));

    // Verify that scaling preserves exact arithmetic near the largest allowed total duration.
    long max = Long.MAX_VALUE / 1000;
    List<DataModel> large = model("<querySet concurrency=\"1\"><openLoop>" + "<stage durationMs=\""
      + (max / 2) + "\" rate=\"1\"/>" + "<stage durationMs=\"" + (max - max / 2)
      + "\" rate=\"1\"/></openLoop></querySet>");
    of(String.valueOf(max - 1), null, null).apply(large);
    long[] scaled = durations(set(large, 0).getOpenLoop());
    assertEquals(max / 2 - 1, scaled[0]);
    assertEquals(max - 1, scaled[0] + scaled[1]);
  }

  @Test
  public void testRunDurationStageBelowOneMs() throws Exception {
    List<DataModel> models = model("<querySet concurrency=\"1\"><openLoop>"
      + "<stage durationMs=\"1\" rate=\"10\"/><stage durationMs=\"999\" rate=\"10\"/>"
      + "</openLoop></querySet>");
    try {
      of("10", null, null).apply(models);
      fail("Accepted a stage of 0 ms");
    } catch (IllegalArgumentException e) {
      String message = e.getMessage();
      assertTrue(message, message.contains("-runDurationMs") && message.contains("stage 1")
        && message.contains("querySet 1") && message.contains("overridden"));
    }
  }

  /**
   * Verifies that the peak rate becomes R, every stage rate scales proportionally with it, and any
   * pause stage remains at 0.
   */
  @Test
  public void testTargetRateAlone() throws Exception {
    List<DataModel> models = model(CLOSED, OPEN);
    of(null, null, "60").apply(models);
    List<OpenLoop.Stage> stages = set(models, 1).getOpenLoop().getStage();
    assertEquals(20.0, stages.get(0).getRate(), 1e-9);
    assertNull(stages.get(0).getEndRate());
    assertEquals(20.0, stages.get(1).getRate(), 1e-9);
    assertEquals(60.0, stages.get(1).getEndRate(), 0);
    assertEquals(0.0, stages.get(2).getRate(), 0);
    assertEquals(40.0, stages.get(3).getRate(), 1e-9);
    assertEquals(1000, Arrays.stream(durations(set(models, 1).getOpenLoop())).sum());
    assertEquals("1-4", set(models, 0).getConcurrency());
    assertEquals(60000, set(models, 0).getExecutionDurationInMs());
    QueryExecutor.checkOpenLoop(models.get(0));
  }

  /**
   * Verifies that runtime overrides act on the workload curve loaded from a profile after the
   * configuration parser has resolved it.
   */
  @Test
  public void testOverridesWinOverAProfile() throws Exception {
    DataModel model = XMLConfigParser.readDataModel(
      new File(ChildJvm.targetDir().getParentFile(), "src/test/resources/profile/profile_run.xml")
        .toPath());
    List<DataModel> models = Collections.singletonList(model);
    of("100", "2", "4000").apply(models);
    for (Scenario scenario : model.getScenarios()) {
      QuerySet querySet = scenario.getQuerySet().get(0);
      assertEquals("mix_profile.xml", querySet.getProfile());
      assertEquals("2", querySet.getConcurrency());
      List<OpenLoop.Stage> stages = querySet.getOpenLoop().getStage();
      assertEquals(50, stages.get(0).getDurationMs());
      assertEquals(50, stages.get(1).getDurationMs());
      assertEquals(2000.0, stages.get(0).getRate(), 0);
      assertEquals(2000.0, stages.get(1).getRate(), 0);
      assertEquals(4000.0, stages.get(1).getEndRate(), 0);
      assertEquals(3, querySet.getMix().size());
    }
    QueryExecutor.checkOpenLoop(model);
    QueryExecutor.checkMix(model);
  }

  private static void assertInvalid(String option, String runDurationMs, String threadCount,
    String targetRate, boolean querySets) {
    try {
      RunOverrides.of(runDurationMs, threadCount, targetRate, querySets);
      fail("Accepted " + option);
    } catch (IllegalArgumentException e) {
      String value = option.equals("-runDurationMs") ? runDurationMs
        : option.equals("-threadCount") ? threadCount
        : targetRate;
      assertTrue(e.getMessage(),
        e.getMessage().contains(option) && (querySets || e.getMessage().contains("-q"))
          && (!querySets || e.getMessage().contains("'" + value + "'")));
    }
  }

  @Test
  public void testValidationErrors() throws Exception {
    for (String bad : new String[] { "0", "-1", "x", "1.5", "",
      String.valueOf(Long.MAX_VALUE / 1000 + 1), "99999999999999999999" }) {
      assertInvalid("-runDurationMs", bad, null, null, true);
    }
    for (String bad : new String[] { "0", "-3", "x", "2.0", "1000000000" }) {
      assertInvalid("-threadCount", null, bad, null, true);
    }
    for (String bad : new String[] { "0", "-1", "x", "NaN", "Infinity", "" }) {
      assertInvalid("-targetRate", null, null, bad, true);
    }
    // Specifying any override without the -q option is rejected as an error because only query
    // sets use these overrides.
    assertInvalid("-runDurationMs", "10", null, null, false);
    assertInvalid("-threadCount", null, "3", null, false);
    assertInvalid("-targetRate", null, null, "5", false);
    // Verify that the largest allowed boundary values are accepted as valid overrides.
    assertFalse(of(String.valueOf(Long.MAX_VALUE / 1000), "999999999", "0.001").isEmpty());
    // Specifying -targetRate when no open loop query set is present would have no effect, so it is
    // rejected as an error.
    List<DataModel> closed = model(CLOSED);
    try {
      of(null, null, "5").apply(closed);
      fail("Accepted -targetRate without an open loop query set");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("-targetRate"));
    }
    assertEquals("1-4", set(closed, 0).getConcurrency());
  }

  /**
   * Verifies that the open loop validation check runs before scaling is applied, ensuring that a
   * malformed curve fails with its validation error message.
   */
  @Test
  public void testApplyChecksTheOpenLoopFirst() throws Exception {
    List<DataModel> models = model("<querySet concurrency=\"1\"><openLoop/></querySet>");
    try {
      of("100", null, null).apply(models);
      fail("Scaled an open loop with no stage");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no stage"));
    }
  }

  /** Verifies that when no override is provided, the data model remains byte-equal. */
  @Test
  public void testNoOverrideLeavesTheModelEqual() throws Exception {
    assertTrue(RunOverrides.of(null, null, null, false).isEmpty());
    assertTrue(RunOverrides.of(null, null, null, true).isEmpty());
    List<DataModel> models = model(CLOSED, OPEN);
    String before = marshal(models.get(0));
    RunOverrides.of(null, null, null, true).apply(models);
    assertEquals(before, marshal(models.get(0)));
  }

  private static String marshal(DataModel model) throws Exception {
    StringWriter xml = new StringWriter();
    JAXBContext.newInstance(DataModel.class).createMarshaller().marshal(model, xml);
    return xml.toString();
  }
}
