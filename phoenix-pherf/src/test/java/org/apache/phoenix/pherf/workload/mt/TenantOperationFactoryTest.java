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
package org.apache.phoenix.pherf.workload.mt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.XMLConfigParserTest;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.LoadProfile;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.TenantGroup;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;
import org.apache.phoenix.pherf.workload.mt.generators.WeightedRandomLoadEventGenerator;
import org.apache.phoenix.pherf.workload.mt.operations.IdleTimeOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.QueryOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.TenantOperationFactory;
import org.apache.phoenix.pherf.workload.mt.operations.UpsertOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.UserDefinedOperationSupplier;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the various operation supplier outcomes based on scenario, model and load profile.
 */
public class TenantOperationFactoryTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(TenantOperationFactoryTest.class);

  private enum TestOperationGroup {
    upsertOp,
    queryOp1,
    queryOp2,
    idleOp,
    udfOp
  }

  private enum TestTenantGroup {
    tg1,
    tg2,
    tg3
  }

  public DataModel readTestDataModel(String resourceName) throws Exception {
    URL scenarioUrl = XMLConfigParserTest.class.getResource(resourceName);
    assertNotNull(scenarioUrl);
    Path p = Paths.get(scenarioUrl.toURI());
    return XMLConfigParser.readDataModel(p);
  }

  @Test
  public void testVariousOperations() throws Exception {
    int numTenantGroups = 3;
    int numOpGroups = 5;
    int numRuns = 10;
    int numOperations = 10;

    PhoenixUtil pUtil = PhoenixUtil.create();
    Properties properties =
      PherfConstants.create().getProperties(PherfConstants.PHERF_PROPERTIES, false);

    DataModel model = readTestDataModel("/scenario/test_evt_gen1.xml");
    for (Scenario scenario : model.getScenarios()) {
      LOGGER.debug(String.format("Testing %s", scenario.getName()));
      LoadProfile loadProfile = scenario.getLoadProfile();
      assertEquals("tenant group size is not as expected: ", numTenantGroups,
        loadProfile.getTenantDistribution().size());
      assertEquals("operation group size is not as expected: ", numOpGroups,
        loadProfile.getOpDistribution().size());

      WeightedRandomLoadEventGenerator evtGen =
        new WeightedRandomLoadEventGenerator(pUtil, model, scenario, properties);
      TenantOperationFactory opFactory = evtGen.getOperationFactory();
      assertEquals("operation group size from the factory is not as expected: ", numOpGroups,
        opFactory.getOperations().size());

      for (int i = 0; i < numRuns; i++) {
        int ops = numOperations;
        loadProfile.setNumOperations(ops);
        while (ops-- > 0) {
          TenantOperationInfo info = evtGen.next();
          switch (TestOperationGroup.valueOf(info.getOperationGroupId())) {
            case upsertOp:
              assertTrue(opFactory.getOperationSupplier(info).getClass()
                .isAssignableFrom(UpsertOperationSupplier.class));
              break;
            case queryOp1:
            case queryOp2:
              assertTrue(opFactory.getOperationSupplier(info).getClass()
                .isAssignableFrom(QueryOperationSupplier.class));
              break;
            case idleOp:
              assertTrue(opFactory.getOperationSupplier(info).getClass()
                .isAssignableFrom(IdleTimeOperationSupplier.class));
              break;
            case udfOp:
              assertTrue(opFactory.getOperationSupplier(info).getClass()
                .isAssignableFrom(UserDefinedOperationSupplier.class));
              break;
            default:
              Assert.fail();

          }
        }
      }
    }
  }

  private static final String TEST_VIEW_DDL = "CREATE VIEW IF NOT EXISTS PHERF.TEST_VIEW "
    + "(field1 VARCHAR, field2 VARCHAR) AS SELECT * FROM PHERF.TEST_TABLE";

  private static Scenario getScenario(DataModel model, String name) {
    for (Scenario scenario : model.getScenarios()) {
      if (scenario.getName().equals(name)) {
        return scenario;
      }
    }
    throw new AssertionError("No scenario " + name);
  }

  private static TenantOperationInfo tenantInfo(DataModel model, Scenario scenario,
    String tenantId) {
    return new TenantOperationInfo(model.getName(), scenario.getName(), scenario.getTableName(),
      "tg", "op", tenantId, null);
  }

  @Test
  public void testScenarioWithoutLoadProfile() throws Exception {
    DataModel model = readTestDataModel("/scenario/test_scenario.xml");
    Scenario scenario = getScenario(model, "testMTWriteScenario");
    assertNull(scenario.getLoadProfile());
    PhoenixUtil pUtil = mock(PhoenixUtil.class);
    TenantOperationFactory factory = new TenantOperationFactory(pUtil, model, scenario);
    factory.initializeTenant(tenantInfo(model, scenario, "xyzdefghijklmno"));
    verify(pUtil).executeStatement(eq(TEST_VIEW_DDL), nullable(Connection.class));
  }

  @Test
  public void testEveryTenantRunsPreRunDdlOnce() throws Exception {
    DataModel model = readTestDataModel("/scenario/test_workload_with_load_profile.xml");
    Scenario scenario = getScenario(model, "scenario_11");
    for (TenantGroup tg : scenario.getLoadProfile().getTenantDistribution()) {
      tg.setNumTenants(1);
    }
    PhoenixUtil pUtil = mock(PhoenixUtil.class);
    TenantOperationFactory factory = new TenantOperationFactory(pUtil, model, scenario);
    int numTenants = 200;
    for (int i = 0; i < numTenants; i++) {
      String tenantId = String.format("T%04d", i);
      factory.initializeTenant(tenantInfo(model, scenario, tenantId));
      factory.initializeTenant(tenantInfo(model, scenario, tenantId));
    }
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(pUtil, atLeast(0)).executeStatement(sql.capture(), nullable(Connection.class));
    List<String> captured = sql.getAllValues();
    assertEquals(numTenants, captured.size());
    String viewDdl = scenario.getPreScenarioDdls().get(0).toString();
    for (String s : captured) {
      assertEquals(viewDdl, s);
    }
  }

  @Test
  public void testFailedPreRunIsRetried() throws Exception {
    DataModel model = readTestDataModel("/scenario/test_workload_with_load_profile.xml");
    Scenario scenario = getScenario(model, "scenario_11");
    String viewDdl = scenario.getPreScenarioDdls().get(0).toString();
    PhoenixUtil pUtil = mock(PhoenixUtil.class);
    doThrow(new SQLException()).when(pUtil).executeStatement(anyString(),
      nullable(Connection.class));
    TenantOperationFactory factory = new TenantOperationFactory(pUtil, model, scenario);
    factory.initializeTenant(tenantInfo(model, scenario, "T0000"));
    factory.initializeTenant(tenantInfo(model, scenario, "T0000"));
    verify(pUtil, times(2)).executeStatement(eq(viewDdl), nullable(Connection.class));
  }

  @Test
  public void testFailedPreRunStopsAfterLimit() throws Exception {
    DataModel model = readTestDataModel("/scenario/test_workload_with_load_profile.xml");
    Scenario scenario = getScenario(model, "scenario_11");
    String viewDdl = scenario.getPreScenarioDdls().get(0).toString();
    PhoenixUtil pUtil = mock(PhoenixUtil.class);
    doThrow(new SQLException()).when(pUtil).executeStatement(anyString(),
      nullable(Connection.class));
    TenantOperationFactory factory = new TenantOperationFactory(pUtil, model, scenario);
    factory.initializeTenant(tenantInfo(model, scenario, "T0000"));
    factory.initializeTenant(tenantInfo(model, scenario, "T0000"));
    for (int i = 0; i < 2; i++) {
      try {
        factory.initializeTenant(tenantInfo(model, scenario, "T0000"));
        fail("Expected the tenant to fail after the attempt limit");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage(),
          e.getMessage().contains("T0000") && e.getMessage().contains(scenario.getTableName()));
      }
    }
    verify(pUtil, times(3)).executeStatement(eq(viewDdl), nullable(Connection.class));
  }
}
