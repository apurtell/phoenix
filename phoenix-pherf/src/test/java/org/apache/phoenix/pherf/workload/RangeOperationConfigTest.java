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
import javax.xml.bind.Marshaller;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.run.ChildJvm;
import org.junit.Test;

/**
 * Verifies query operation kind configuration for RQ-189, where the kind attribute of a query
 * declares a range SCAN or DELETE operation. This suite tests how the attribute binds from XML, how
 * it is validated before a query set starts, how it is copied into the XML result, and that no
 * existing fixture declares it.
 */
public class RangeOperationConfigTest {
  private static final String DTD_FIXTURE = "malicious_scenario_with_dtd.xml";
  private static final String SET =
    "<querySet>" + "<query id=\"s\" kind=\"SCAN\" statement=\"SELECT V FROM T WHERE K &gt;= [K] AND"
      + " K &lt;= [K]\"/>"
      + "<query id=\"d\" kind=\"DELETE\" statement=\"DELETE FROM T WHERE K &gt;= [K] AND"
      + " K &lt;= [K]\"/>" + "<query id=\"p\" statement=\"SELECT V FROM T WHERE K = [K]\"/>"
      + "<query id=\"e\" kind=\"SELECT\" statement=\"SELECT V FROM T\"/>" + "</querySet>";

  private static QuerySet bind(String xml) throws Exception {
    return JAXBContext.newInstance(QuerySet.class).createUnmarshaller()
      .unmarshal(new StreamSource(new StringReader(xml)), QuerySet.class).getValue();
  }

  private static DataModel model(QuerySet... querySets) {
    Scenario scenario = new Scenario();
    scenario.setName("kinds");
    scenario.setQuerySet(Arrays.asList(querySets));
    DataModel dataModel = new DataModel();
    dataModel.setScenarios(Collections.singletonList(scenario));
    return dataModel;
  }

  @Test
  public void testKindBinds() throws Exception {
    List<Query> queries = bind(SET).getQuery();
    assertEquals(Query.Kind.SCAN, queries.get(0).kind());
    assertEquals(Query.Kind.DELETE, queries.get(1).kind());
    assertNull(queries.get(2).getKind());
    assertEquals(Query.Kind.SELECT, queries.get(2).kind());
    assertEquals(Query.Kind.SELECT, queries.get(3).kind());
    QueryExecutor.checkKinds(model(bind(SET)));
  }

  @Test
  public void testBadKindIsRejected() throws Exception {
    QuerySet bad =
      bind("<querySet><query id=\"r\" kind=\"RANGE\" statement=\"SELECT 1\"/>" + "</querySet>");
    try {
      bad.getQuery().get(0).kind();
      fail("kind RANGE accepted");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("query r"));
      assertTrue(e.getMessage(), e.getMessage().contains("RANGE"));
    }
    // Validating across all query sets reports the enclosing scenario name even when the invalid
    // kind appears in a subsequent query set.
    try {
      QueryExecutor.checkKinds(model(bind(SET), bad));
      fail("kind RANGE accepted");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("scenario kinds"));
      assertTrue(e.getMessage(), e.getMessage().contains("query r"));
      assertTrue(e.getMessage(), e.getMessage().contains("RANGE"));
    }
  }

  /**
   * Verifies that copying a query into a result preserves its kind attribute and marshals it into
   * the result XML, whereas a query configured without a kind attribute omits it from both.
   */
  @Test
  public void testResultCopiesAndMarshalsKind() throws Exception {
    QuerySet querySet = bind(SET);
    QuerySetResult copy = new QuerySetResult(querySet);
    for (Query query : querySet.getQuery()) {
      QueryResult result = new QueryResult(query);
      assertEquals(query.getKind(), result.getKind());
      assertEquals(query.kind(), result.kind());
      copy.getQueryResults().add(result);
    }
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
    assertTrue(xml.toString(), xml.toString().contains("kind=\"SCAN\""));
    assertTrue(xml.toString(), xml.toString().contains("kind=\"DELETE\""));
    DataModelResult back =
      (DataModelResult) context.createUnmarshaller().unmarshal(new StringReader(xml.toString()));
    List<QueryResult> queries =
      back.getScenarioResult().get(0).getQuerySetResult().get(0).getQueryResults();
    assertEquals(Query.Kind.SCAN, queries.get(0).kind());
    assertEquals(Query.Kind.DELETE, queries.get(1).kind());
    assertNull(queries.get(2).getKind());

    QuerySet plain = bind("<querySet><query id=\"p\" statement=\"SELECT 1\"/></querySet>");
    QuerySetResult plainCopy = new QuerySetResult(plain);
    plainCopy.getQueryResults().add(new QueryResult(plain.getQuery().get(0)));
    scenario.setQuerySetResult(Collections.singletonList(plainCopy));
    StringWriter text = new StringWriter();
    marshaller.marshal(result, text);
    assertFalse(text.toString(), text.toString().contains("kind="));
  }

  /** Verifies for RQ-152 that no existing scenario fixture declares a kind attribute. */
  @Test
  public void testExistingFixturesHaveNoKind() throws Exception {
    int queries = 0;
    for (File file : ChildJvm.scenarioDir().listFiles((dir, name) -> name.endsWith(".xml"))) {
      if (file.getName().equals(DTD_FIXTURE)) {
        continue;
      }
      for (DataModel model : new XMLConfigParser(file.getPath()).getDataModels()) {
        for (Scenario scenario : model.getScenarios()) {
          for (QuerySet querySet : scenario.getQuerySet()) {
            for (Query query : querySet.getQuery()) {
              assertNull(file.getName(), query.getKind());
              assertEquals(Query.Kind.SELECT, query.kind());
              queries++;
            }
          }
        }
        QueryExecutor.checkKinds(model);
      }
    }
    assertTrue(queries > 10);
  }
}
