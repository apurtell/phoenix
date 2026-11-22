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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;
import org.apache.phoenix.pherf.workload.mt.operations.IdleTimeOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.Operation;
import org.apache.phoenix.pherf.workload.mt.operations.Operation.OperationType;
import org.apache.phoenix.pherf.workload.mt.operations.QueryOperation;
import org.apache.phoenix.pherf.workload.mt.operations.QueryOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.RangeOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.TenantOperationFactory;
import org.apache.phoenix.pherf.workload.mt.operations.UpsertOperationSupplier;
import org.apache.phoenix.pherf.workload.mt.operations.UserDefinedOperationSupplier;
import org.junit.Test;

/**
 * Verifies RQ-187: the load profile determines the operation type of each query operation from its
 * configured kind. Operations of kind SCAN or DELETE are backed by the range operation supplier,
 * whereas all existing fixtures retain their original operations and suppliers unchanged.
 */
public class RangeOperationFactoryTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";

  private static DataModel mixed() throws Exception {
    URL url = RangeOperationFactoryTest.class.getResource("/operations/mixed_operations.xml");
    assertNotNull(url);
    return XMLConfigParser.readDataModel(Paths.get(url.toURI()));
  }

  private static TenantOperationInfo info(Scenario scenario, Operation operation) {
    return new TenantOperationInfo("m", scenario.getName(), scenario.getTableName(), "tg1",
      operation.getId(), "t1", operation);
  }

  @Test
  public void testKindsGiveTheRangeTypes() throws Exception {
    DataModel model = mixed();
    Scenario scenario = model.getScenarios().get(0);
    TenantOperationFactory factory =
      new TenantOperationFactory(mock(PhoenixUtil.class), model, scenario);
    List<String> ids = new ArrayList<>();
    List<OperationType> types = new ArrayList<>();
    for (Operation operation : factory.getOperations()) {
      ids.add(operation.getId());
      types.add(operation.getType());
    }
    assertEquals(Arrays.asList("upsertOp", "queryOp", "scanOp", "deleteOp", "idleOp", "udfOp"),
      ids);
    assertEquals(Arrays.asList(OperationType.UPSERT, OperationType.SELECT, OperationType.SCAN,
      OperationType.DELETE, OperationType.IDLE_TIME, OperationType.USER_DEFINED), types);
    List<Operation> operations = factory.getOperations();
    assertSame(UpsertOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(0))).getClass());
    assertSame(QueryOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(1))).getClass());
    assertSame(RangeOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(2))).getClass());
    assertSame(RangeOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(3))).getClass());
    assertSame(IdleTimeOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(4))).getClass());
    assertSame(UserDefinedOperationSupplier.class,
      factory.getOperationSupplier(info(scenario, operations.get(5))).getClass());
    assertEquals("scanOp", ((QueryOperation) operations.get(2)).getQuery().getId());
  }

  /**
   * Verifies RQ-152: every query defined across existing load profile fixtures continues to resolve
   * to a SELECT operation backed by the query supplier.
   */
  @Test
  public void testExistingFixturesKeepTheirOperations() throws Exception {
    int queries = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      DataModel model = XMLConfigParser.readDataModel(file.toPath());
      for (Scenario scenario : model.getScenarios()) {
        if (scenario.getCoGeneration() != null) {
          continue;
        }
        TenantOperationFactory factory =
          new TenantOperationFactory(mock(PhoenixUtil.class), model, scenario);
        for (Operation operation : factory.getOperations()) {
          assertTrue(operation.getType() != OperationType.SCAN);
          assertTrue(operation.getType() != OperationType.DELETE);
          if (operation.getType() == OperationType.SELECT) {
            assertSame(QueryOperationSupplier.class,
              factory.getOperationSupplier(info(scenario, operation)).getClass());
            queries++;
          }
        }
      }
    }
    assertTrue(queries > 10);
  }

  @Test
  public void testBadKindIsRejected() throws Exception {
    DataModel model = mixed();
    Scenario scenario = model.getScenarios().get(0);
    QuerySet querySet = scenario.getQuerySet().get(0);
    Query bad = querySet.getQuery().get(2);
    bad.setKind("REMOVE");
    try {
      new TenantOperationFactory(mock(PhoenixUtil.class), model, scenario);
      fail("kind REMOVE accepted");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("scenario mixed"));
      assertTrue(e.getMessage(), e.getMessage().contains("query deleteOp"));
      assertTrue(e.getMessage(), e.getMessage().contains("REMOVE"));
    }
  }
}
