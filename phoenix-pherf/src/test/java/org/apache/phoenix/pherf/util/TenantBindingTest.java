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
package org.apache.phoenix.pherf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataLoadTimeSummary;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

public class TenantBindingTest {

  private Scenario scenario;
  private PhoenixUtil util;
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    URL url = TenantBindingTest.class.getResource("/scenario/test_tenant_binding.xml");
    DataModel model = XMLConfigParser.readDataModel(Paths.get(url.toURI()));
    scenario = model.getScenarios().get(0);
    conn = mock(Connection.class);
    util = spy(PhoenixUtil.create());
    doReturn(conn).when(util).getConnection(nullable(String.class));
    doReturn(true).when(util).executeStatement(anyString(), any(Connection.class));
  }

  @Test
  public void testQueryTakesScenarioTenant() {
    List<Query> queries = scenario.getQuerySet().get(0).getQuery();
    assertEquals("TENANT_A", queries.get(0).getTenantId());
    assertEquals("TENANT_B", queries.get(1).getTenantId());
    assertNull(queries.get(2).getTenantId());
  }

  @Test
  public void testQuerySetDdlUsesScenarioTenant() throws Exception {
    QuerySet querySet = scenario.getQuerySet().get(0);
    util.executeQuerySetDdls(querySet);
    verify(util).getConnection("TENANT_A");
  }

  @Test
  public void testScenarioDdlHonoursGlobalConnection() throws Exception {
    util.executeScenarioDdl(scenario.getPreScenarioDdls(), "TENANT_A", new DataLoadTimeSummary());
    InOrder order = inOrder(util);
    order.verify(util).getConnection("TENANT_A");
    order.verify(util).getConnection(null);
    verify(conn, times(2)).close();
  }
}
