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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.PherfConstants.GeneratePhoenixStats;
import org.apache.phoenix.pherf.ResultBaseTestIT;
import org.apache.phoenix.pherf.configuration.CoGeneration.WalkStrategy;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.IndexHash;
import org.apache.phoenix.pherf.workload.WriteWorkload;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies RQ-180 against a mini cluster by executing the real write workload to load 300
 * co-generated rows with clustering enabled, and then using a decoder initialized from an
 * independent configuration parse to read every committed row via a point lookup. Each lookup must
 * match exactly one row whose payload matches the expected value computed by the decoder. The
 * primary key includes DATE and TIMESTAMP columns while the filter predicate includes a binary
 * column, exercising literal encoding when the build JVM time zone is not UTC.
 */
@Category(NeedsOwnMiniClusterTest.class)
public class CoGenerationIT extends ResultBaseTestIT {
  private static final String LOOKUP = "SELECT V FROM PHERF.COGEN WHERE K1 = [K1] AND K2 = [K2]"
    + " AND D = [D] AND T = [T] AND B = [B]";
  private static final int ROWS = 300;

  @Test
  public void testPointLookupFindsOneRow() throws Exception {
    try (Connection connection = util.getConnection();
      Statement ddl = connection.createStatement()) {
      ddl.execute("CREATE TABLE PHERF.COGEN (K1 VARCHAR NOT NULL, K2 INTEGER NOT NULL,"
        + " D DATE NOT NULL, T TIMESTAMP NOT NULL, V VARCHAR, B VARBINARY_ENCODED"
        + " CONSTRAINT PK PRIMARY KEY (K1, K2, D, T))");
    }
    String fixture = path("/cogen/point_lookup.xml");
    XMLConfigParser writerParser = new XMLConfigParser(fixture);
    Properties properties =
      PherfConstants.create().getProperties(PherfConstants.PHERF_PROPERTIES, false);
    WriteWorkload write =
      new WriteWorkload(util, writerParser, properties, GeneratePhoenixStats.NO);
    try {
      write.execute().call();
    } finally {
      write.complete();
    }
    Scenario written = writerParser.getScenarioByName("pointLookup");
    assertEquals(ROWS, CoGenerator.of(writerParser, written).cursor().watermark());

    XMLConfigParser readerParser = new XMLConfigParser(fixture);
    Scenario scenario = readerParser.getDataModels().get(0).getScenarios().get(0);
    Column v = new Column();
    v.setName("V");
    v.setType(DataTypeMapping.VARCHAR);
    Set<Long> visited = new HashSet<>();
    Set<String> leading = new HashSet<>();
    try (Connection connection = util.getConnection()) {
      try (Statement count = connection.createStatement();
        ResultSet rs = count.executeQuery("SELECT COUNT(*) FROM PHERF.COGEN")) {
        assertTrue(rs.next());
        assertEquals(ROWS, rs.getLong(1));
      }
      CoGenerator reader = CoGenerator.of(readerParser, scenario);
      assertEquals(ROWS, reader.cursor().watermark());
      IndexDecoder decoder = reader.decoder(connection);
      Walk walk = new Walk(IndexHash.columnSeed(5, "#walk"), WalkStrategy.MULTIPLICATIVE, 0, 1);
      for (int k = 0; k < ROWS; k++) {
        final long[] drawn = new long[1];
        String sql = decoder.statement(LOOKUP, () -> {
          drawn[0] = walk.next(reader.cursor().watermark());
          return drawn[0];
        });
        assertTrue(visited.add(drawn[0]));
        try (Statement lookup = connection.createStatement();
          ResultSet rs = lookup.executeQuery(sql)) {
          assertTrue(sql, rs.next());
          assertEquals(sql, decoder.value(v, drawn[0]).getValue(), rs.getString(1));
          assertFalse(sql, rs.next());
        }
        leading.add(sql.substring(sql.indexOf("K1 = "), sql.indexOf(" AND")));
      }
    }
    assertEquals(ROWS, visited.size());
    assertTrue(leading.toString(), leading.size() > 1);
  }

  private static String path(String resource) throws Exception {
    URL url = CoGenerationIT.class.getResource(resource);
    assertNotNull(resource, url);
    return Paths.get(url.toURI()).toString();
  }
}
