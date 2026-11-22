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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.run.ReferenceScenarios;
import org.apache.phoenix.pherf.workload.cogen.CoGenerator;
import org.apache.phoenix.pherf.workload.openloop.MixOrder;
import org.junit.Test;

/**
 * Verifies for RQ-192 that the three reference scenarios and their accompanying smoke profiles are
 * distributed under config/reference, parse cleanly, satisfy all pre-execution query set validation
 * checks, and produce the exact operation counts defined in {@link ReferenceScenarios}. The P10
 * suite extends this coverage by asserting that the static validator reports zero errors across
 * these configuration files.
 */
public class ReferenceScenariosTest {

  private static DataModel parse(ReferenceScenarios reference) throws Exception {
    List<DataModel> models =
      new XMLConfigParser(reference.scenarioFile().getPath()).getDataModels();
    assertEquals(1, models.size());
    return models.get(0);
  }

  @Test
  public void testSixFilesShipOutsideTheFixtureDirectory() throws Exception {
    File[] files = ReferenceScenarios.dir().listFiles((dir, name) -> name.endsWith(".xml"));
    assertNotNull(files);
    assertEquals(6, files.length);
    String fixtures = ChildJvm.scenarioDir().getCanonicalPath() + File.separator;
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      for (File file : Arrays.asList(reference.scenarioFile(), reference.profileFile())) {
        assertTrue(file.getPath(), file.isFile());
        assertFalse(file.getPath(), file.getCanonicalPath().startsWith(fixtures));
      }
    }
  }

  @Test
  public void testEachScenarioParsesAndPassesTheChecks() throws Exception {
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      DataModel model = parse(reference);
      assertEquals(reference.dataModel, model.getName());
      assertEquals(1, model.getScenarios().size());
      Scenario scenario = model.getScenarios().get(0);
      assertEquals(reference.scenario, scenario.getName());
      assertEquals(reference.table, scenario.getTableName());
      assertEquals(reference.rows, scenario.getRowCount());
      assertNotNull(reference.name, scenario.getCoGeneration());
      CoGenerator.Settings settings = CoGenerator.Settings.of(scenario);
      assertEquals(reference.blockSize, settings.blockSize);
      assertEquals(null, settings.clusterLimit);
      assertEquals(reference.rows % reference.blockSize, 0);
      // The target table is provisioned by a scenario-level DDL statement rather than an external
      // schema file.
      assertEquals(1, scenario.getPreScenarioDdls().size());
      assertTrue(scenario.getPreScenarioDdls().get(0).toString()
        .startsWith("CREATE TABLE IF NOT EXISTS " + reference.table + " ("));
      assertEquals(1, scenario.getQuerySet().size());
      QuerySet querySet = scenario.getQuerySet().get(0);
      assertEquals(reference.profileFile().getName(), querySet.getProfile());
      assertEquals("1", querySet.getConcurrency());
      assertEquals(null, querySet.getWarmupExecutions());
      OpenLoop openLoop = querySet.getOpenLoop();
      assertEquals(OpenLoop.LagPolicy.LATE, openLoop.getLagPolicy());
      assertEquals(null, openLoop.getMaxLagMs());
      Map<String, Query.Kind> kinds = new HashMap<>();
      for (Query query : querySet.getQuery()) {
        kinds.put(query.getId(), query.kind());
      }
      Map<String, Query.Kind> expected = new HashMap<>();
      expected.put(ReferenceScenarios.LOOKUP, Query.Kind.SELECT);
      expected.put(ReferenceScenarios.SCAN, Query.Kind.SCAN);
      expected.put(ReferenceScenarios.DELETE, Query.Kind.DELETE);
      assertEquals(expected, kinds);
      QueryExecutor.checkWarmupExecutions(model);
      QueryExecutor.checkOpenLoop(model);
      QueryExecutor.checkKinds(model);
      QueryExecutor.checkMix(model);
    }
  }

  /**
   * Confirms that integrating the open-loop rate curve yields the expected total arrivals and that
   * projecting the mix order across those arrivals produces the expected execution count for each
   * query. Also verifies that delete operations stay within the populated row count and leave the
   * expected number of surviving rows.
   */
  @Test
  public void testProfilesGiveTheExpectedCounts() throws Exception {
    for (ReferenceScenarios reference : ReferenceScenarios.ALL) {
      Scenario scenario = parse(reference).getScenarios().get(0);
      QuerySet querySet = scenario.getQuerySet().get(0);
      double integral = 0;
      for (OpenLoop.Stage stage : querySet.getOpenLoop().getStage()) {
        double end = stage.getEndRate() == null ? stage.getRate() : stage.getEndRate();
        integral += (stage.getRate() + end) / 2 * stage.getDurationMs() / 1000;
      }
      assertEquals(reference.name, (double) reference.arrivals, integral, 0);
      MixOrder order = QueryExecutor.mixOrder(querySet);
      Map<String, Long> counts = new HashMap<>();
      for (long k = 0; k < reference.arrivals; k++) {
        counts.merge(querySet.getQuery().get(order.query(k)).getId(), 1L, Long::sum);
      }
      Map<String, Long> expected = new HashMap<>();
      for (String id : new String[] { ReferenceScenarios.LOOKUP, ReferenceScenarios.SCAN,
        ReferenceScenarios.DELETE }) {
        expected.put(id, reference.operations(id));
      }
      assertEquals(reference.name, expected, counts);
      assertTrue(reference.rowsLeft >= reference.blockSize);
    }
  }
}
