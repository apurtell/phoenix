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
package org.apache.phoenix.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.parse.HintNode.Hint;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.schema.types.PVectorDouble;
import org.apache.phoenix.schema.types.PVectorFloat;
import org.junit.Test;

/** Tests for parsing VECTOR column definitions in SQL statements. */
public class VectorColumnParseTest {

  @Test
  public void testParseVectorFloatColumn() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(FLOAT, 128))";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateTableStatement", stmt instanceof CreateTableStatement);
    CreateTableStatement createStmt = (CreateTableStatement) stmt;

    List<ColumnDef> colDefs = createStmt.getColumnDefs();
    assertEquals(2, colDefs.size());

    ColumnDef vectorCol = colDefs.get(1);
    assertEquals("V", vectorCol.getColumnDefName().getColumnName());
    assertEquals(PVectorFloat.INSTANCE, vectorCol.getDataType());
    assertEquals(Integer.valueOf(128), vectorCol.getMaxLength());
  }

  @Test
  public void testParseVectorDoubleColumn() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(DOUBLE, 64))";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateTableStatement", stmt instanceof CreateTableStatement);
    CreateTableStatement createStmt = (CreateTableStatement) stmt;

    List<ColumnDef> colDefs = createStmt.getColumnDefs();
    assertEquals(2, colDefs.size());

    ColumnDef vectorCol = colDefs.get(1);
    assertEquals("V", vectorCol.getColumnDefName().getColumnName());
    assertEquals(PVectorDouble.INSTANCE, vectorCol.getDataType());
    assertEquals(Integer.valueOf(64), vectorCol.getMaxLength());
  }

  @Test
  public void testParseVectorColumnWithNotNull() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(FLOAT, 256) NOT NULL)";
    SQLParser parser = new SQLParser(ddl);
    CreateTableStatement createStmt = (CreateTableStatement) parser.parseStatement();

    ColumnDef vectorCol = createStmt.getColumnDefs().get(1);
    assertEquals(PVectorFloat.INSTANCE, vectorCol.getDataType());
    assertEquals(Integer.valueOf(256), vectorCol.getMaxLength());
    assertFalse("Column should be NOT NULL", vectorCol.isNull());
  }

  @Test
  public void testZeroDimensionRejection() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(FLOAT, 0))";
    try {
      SQLParser parser = new SQLParser(ddl);
      parser.parseStatement();
      fail("Should have rejected zero dimension vector column definition");
    } catch (SQLException e) {
      assertEquals("Expected NONPOSITIVE_MAX_LENGTH error code",
        SQLExceptionCode.NONPOSITIVE_MAX_LENGTH.getErrorCode(), e.getErrorCode());
    }
  }

  @Test
  public void testMissingDimensionRejection() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(FLOAT))";
    try {
      SQLParser parser = new SQLParser(ddl);
      parser.parseStatement();
      fail("Should have rejected missing dimension vector column definition");
    } catch (SQLException e) {
      // Expected parse error due to missing dimension
      assertNotNull("Expected parse exception", e.getMessage());
    }
  }

  @Test
  public void testNegativeDimensionRejection() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(FLOAT, -5))";
    try {
      SQLParser parser = new SQLParser(ddl);
      parser.parseStatement();
      fail("Should have rejected negative dimension vector column definition");
    } catch (SQLException e) {
      assertNotNull("Expected parse exception for negative dimension", e.getMessage());
    }
  }

  @Test
  public void testInvalidComponentTypeRejection() throws Exception {
    String ddl = "CREATE TABLE t (pk INTEGER PRIMARY KEY, v VECTOR(INT, 128))";
    try {
      SQLParser parser = new SQLParser(ddl);
      parser.parseStatement();
      fail("Should have rejected unsupported component type in vector column definition");
    } catch (SQLException e) {
      assertNotNull("Expected parse exception for invalid component type", e.getMessage());
    }
  }

  @Test
  public void testDynamicVectorColumn() throws Exception {
    String sql = "SELECT * FROM t(v VECTOR(FLOAT, 128))";
    SQLParser parser = new SQLParser(sql);
    SelectStatement select = (SelectStatement) parser.parseStatement();
    NamedTableNode tableNode = (NamedTableNode) select.getFrom();
    List<ColumnDef> dynCols = tableNode.getDynamicColumns();
    assertEquals(1, dynCols.size());
    ColumnDef dynCol = dynCols.get(0);
    assertEquals("V", dynCol.getColumnDefName().getColumnName());
    assertEquals(PVectorFloat.INSTANCE, dynCol.getDataType());
    assertEquals(Integer.valueOf(128), dynCol.getMaxLength());
  }

  @Test
  public void testColumnDefConstructorValidation() {
    ColumnName colName = new ColumnName("V");
    try {
      new ColumnDef(colName, PVectorFloat.INSTANCE, null, null, null, false, SortOrder.getDefault(),
        null, null, false);
      fail("Should reject null dimension for vector type");
    } catch (ParseException e) {
      assertTrue("Cause must be SQLException", e.getCause() instanceof SQLException);
      assertEquals(SQLExceptionCode.MISSING_MAX_LENGTH.getErrorCode(),
        ((SQLException) e.getCause()).getErrorCode());
    }

    try {
      new ColumnDef(colName, PVectorFloat.INSTANCE, null, 0, null, false, SortOrder.getDefault(),
        null, null, false);
      fail("Should reject zero dimension for vector type");
    } catch (ParseException e) {
      assertTrue("Cause must be SQLException", e.getCause() instanceof SQLException);
      assertEquals(SQLExceptionCode.NONPOSITIVE_MAX_LENGTH.getErrorCode(),
        ((SQLException) e.getCause()).getErrorCode());
    }

    try {
      new ColumnDef(colName, PVectorDouble.INSTANCE, null, -1, null, false, SortOrder.getDefault(),
        null, null, false);
      fail("Should reject negative dimension for vector type");
    } catch (ParseException e) {
      assertTrue("Cause must be SQLException", e.getCause() instanceof SQLException);
      assertEquals(SQLExceptionCode.NONPOSITIVE_MAX_LENGTH.getErrorCode(),
        ((SQLException) e.getCause()).getErrorCode());
    }
  }

  @Test
  public void testColumnDefToString() {
    ColumnDef floatVec = new ColumnDef(new ColumnName("V"), PVectorFloat.INSTANCE, null, 128, null,
      false, SortOrder.getDefault(), null, null, false);
    assertEquals("V VECTOR(FLOAT, 128)", floatVec.toString());

    ColumnDef doubleVec = new ColumnDef(new ColumnName("V"), PVectorDouble.INSTANCE, null, 64, null,
      false, SortOrder.getDefault(), null, null, false);
    assertEquals("V VECTOR(DOUBLE, 64)", doubleVec.toString());
  }

  @Test
  public void testParseVectorIndexFullSyntax() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (v) WITH (metric='COSINE', algorithm='IVF', lists=32, sample_size=1000)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertEquals("IDX", indexStmt.getIndexTableName().getTableName());
    assertEquals("T", indexStmt.getTable().getName().getTableName());
    assertEquals(PTable.IndexType.VECTOR_GLOBAL, indexStmt.getIndexType());
    assertFalse("Expected ifNotExists to be false", indexStmt.ifNotExists());
    assertFalse("Expected async to be false", indexStmt.isAsync());
    assertEquals(1, indexStmt.getIndexConstraint().getParseNodeAndSortOrderList().size());

    List<Pair<String, Object>> props =
      indexStmt.getProps().get(QueryConstants.ALL_FAMILY_PROPERTIES_KEY);
    assertEquals(4, props.size());
    boolean foundMetric = false, foundAlgorithm = false, foundLists = false,
        foundSampleSize = false;
    for (Pair<String, Object> prop : props) {
      if ("METRIC".equals(prop.getFirst())) {
        foundMetric = true;
        assertEquals("COSINE", prop.getSecond());
      } else if ("ALGORITHM".equals(prop.getFirst())) {
        foundAlgorithm = true;
        assertEquals("IVF", prop.getSecond());
      } else if ("LISTS".equals(prop.getFirst())) {
        foundLists = true;
        assertEquals(32, ((Number) prop.getSecond()).intValue());
      } else if ("SAMPLE_SIZE".equals(prop.getFirst())) {
        foundSampleSize = true;
        assertEquals(1000, ((Number) prop.getSecond()).intValue());
      }
    }
    assertTrue("METRIC property expected", foundMetric);
    assertTrue("ALGORITHM property expected", foundAlgorithm);
    assertTrue("LISTS property expected", foundLists);
    assertTrue("SAMPLE_SIZE property expected", foundSampleSize);
  }

  @Test
  public void testParseVectorIndexIfNotExists() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX IF NOT EXISTS idx ON t (v) WITH (algorithm='IVF', metric='L2', lists=16, sample_size=500)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertTrue("Expected ifNotExists to be true", indexStmt.ifNotExists());
    assertEquals(PTable.IndexType.VECTOR_GLOBAL, indexStmt.getIndexType());
  }

  @Test
  public void testParseVectorIndexAsync() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (v) WITH (algorithm='IVF', metric='L2', lists=16, sample_size=500) ASYNC";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertTrue("Expected async to be true", indexStmt.isAsync());
    assertEquals(PTable.IndexType.VECTOR_GLOBAL, indexStmt.getIndexType());
  }

  @Test
  public void testParseVectorIndexIncludeClause() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (v) INCLUDE (col1, col2) WITH (metric='COSINE', algorithm='IVF', lists=32, sample_size=1000)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    List<ColumnName> includeCols = indexStmt.getIncludeColumns();
    assertEquals(2, includeCols.size());
    assertEquals("COL1", includeCols.get(0).getColumnName());
    assertEquals("COL2", includeCols.get(1).getColumnName());
  }

  @Test
  public void testParseVectorIndexFunctionExpression() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (BSON_VECTOR_VALUE(profile, 'search.embedding', 768)) "
        + "INCLUDE (author) WITH (metric='COSINE', algorithm='IVF', lists=1024, sample_size=50000)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertEquals(1, indexStmt.getIndexConstraint().getParseNodeAndSortOrderList().size());
    assertTrue(indexStmt.getIndexConstraint().getParseNodeAndSortOrderList().get(0)
      .getFirst() instanceof FunctionParseNode);
    FunctionParseNode fn = (FunctionParseNode) indexStmt.getIndexConstraint()
      .getParseNodeAndSortOrderList().get(0).getFirst();
    assertEquals("BSON_VECTOR_VALUE", fn.getName());
    assertEquals(1, indexStmt.getIncludeColumns().size());
    assertEquals("AUTHOR", indexStmt.getIncludeColumns().get(0).getColumnName());
  }

  @Test
  public void testVectorIndexAccessorValues() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (v) WITH (algorithm='IVF', metric='COSINE', lists=32, sample_size=1000)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertEquals("IVF", indexStmt.getVectorAlgorithm());
    assertEquals("COSINE", indexStmt.getVectorMetric());
    assertEquals(Integer.valueOf(32), indexStmt.getVectorLists());
    assertEquals(Integer.valueOf(1000), indexStmt.getVectorSampleSize());
  }

  @Test
  public void testCreateIndexStatementConstructorOverload() {
    CreateIndexStatement stmt = new CreateIndexStatement(new NamedNode("IDX"),
      new NamedTableNode(null, TableName.create(null, "T")), IndexKeyConstraint.EMPTY, null, null,
      null, false, PTable.IndexType.VECTOR_GLOBAL, false, 0, null, null, "IVF", "L2", 64, 2000);
    assertEquals("IVF", stmt.getVectorAlgorithm());
    assertEquals("L2", stmt.getVectorMetric());
    assertEquals(Integer.valueOf(64), stmt.getVectorLists());
    assertEquals(Integer.valueOf(2000), stmt.getVectorSampleSize());
  }

  @Test
  public void testNonVectorIndexAccessorValues() throws Exception {
    String ddl = "CREATE INDEX idx ON t (v)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;
    assertNull(indexStmt.getVectorAlgorithm());
    assertNull(indexStmt.getVectorIndexType());
    assertNull(indexStmt.getVectorMetric());
    assertNull(indexStmt.getVectorLists());
    assertNull(indexStmt.getVectorSampleSize());
    assertNull(indexStmt.getHnswM());
    assertNull(indexStmt.getHnswEfConstruction());
    assertNull(indexStmt.getHnswAlpha());
    assertNull(indexStmt.getQuantizationType());
    assertNull(indexStmt.getPqSegments());
    assertNull(indexStmt.getPqTrainingSize());
    assertNull(indexStmt.getVectorIndexParams());
  }

  @Test
  public void testHnswPropertyExtraction() throws Exception {
    String ddl =
      "CREATE VECTOR INDEX idx ON t (v) WITH (algorithm='HNSW', metric='COSINE', dimension=128, "
        + "M=16, ef_construction=64, alpha=1.25, quantization='PQ', pq_segments=4, pq_training_size=500)";
    SQLParser parser = new SQLParser(ddl);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected CreateIndexStatement", stmt instanceof CreateIndexStatement);
    CreateIndexStatement indexStmt = (CreateIndexStatement) stmt;

    assertEquals("IDX", indexStmt.getIndexTableName().getTableName());
    assertEquals(PTable.IndexType.VECTOR_GLOBAL, indexStmt.getIndexType());
    assertFalse(indexStmt.isAsync());
    assertFalse(indexStmt.ifNotExists());

    assertEquals("HNSW", indexStmt.getVectorAlgorithm());
    assertEquals(VectorIndexType.HNSW, indexStmt.getVectorIndexType());
    assertEquals("COSINE", indexStmt.getVectorMetric());
    assertEquals(Integer.valueOf(16), indexStmt.getHnswM());
    assertEquals(Integer.valueOf(64), indexStmt.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.25), indexStmt.getHnswAlpha());
    assertEquals("PQ", indexStmt.getQuantizationType());
    assertEquals(Integer.valueOf(4), indexStmt.getPqSegments());
    assertEquals(Integer.valueOf(500), indexStmt.getPqTrainingSize());

    assertEquals("HNSW", CreateIndexStatement.getVectorAlgorithm(indexStmt.getProps()));
    assertEquals("COSINE", CreateIndexStatement.getVectorMetric(indexStmt.getProps()));
    assertEquals(Integer.valueOf(128),
      CreateIndexStatement.getVectorDimension(indexStmt.getProps()));
    assertEquals(Integer.valueOf(16), CreateIndexStatement.getHnswM(indexStmt.getProps()));
    assertEquals(Integer.valueOf(64),
      CreateIndexStatement.getHnswEfConstruction(indexStmt.getProps()));
    assertEquals(Double.valueOf(1.25), CreateIndexStatement.getHnswAlpha(indexStmt.getProps()));
    assertEquals("PQ", CreateIndexStatement.getQuantizationType(indexStmt.getProps()));
    assertEquals(Integer.valueOf(4), CreateIndexStatement.getPqSegments(indexStmt.getProps()));
    assertEquals(Integer.valueOf(500),
      CreateIndexStatement.getPqTrainingSize(indexStmt.getProps()));

    assertNotNull(indexStmt.getVectorIndexParams());
    Map<String, Object> tableProps = new HashMap<>();
    indexStmt.getVectorIndexParams().populateTableProps(tableProps);
    assertEquals("HNSW", tableProps.get(PhoenixDatabaseMetaData.VECTOR_INDEX_ALGORITHM));
    assertEquals("COSINE", tableProps.get(PhoenixDatabaseMetaData.VECTOR_DISTANCE_METRIC));
    assertEquals(Integer.valueOf(128), tableProps.get(PhoenixDatabaseMetaData.VECTOR_DIMENSION));
    assertEquals(Integer.valueOf(16), tableProps.get(PhoenixDatabaseMetaData.VECTOR_HNSW_M));
    assertEquals(Integer.valueOf(64),
      tableProps.get(PhoenixDatabaseMetaData.VECTOR_HNSW_EF_CONSTRUCTION));
    assertEquals(Double.valueOf(1.25), tableProps.get(PhoenixDatabaseMetaData.VECTOR_HNSW_ALPHA));
    assertEquals("PQ", tableProps.get(PhoenixDatabaseMetaData.VECTOR_QUANTIZATION_TYPE));
    assertEquals(Integer.valueOf(4), tableProps.get(PhoenixDatabaseMetaData.VECTOR_PQ_SEGMENTS));
    assertEquals(Integer.valueOf(500),
      tableProps.get(PhoenixDatabaseMetaData.VECTOR_PQ_TRAINING_SIZE));
  }

  @Test
  public void testDdlWithoutParenthesesAroundProperties() throws Exception {
    String ddl = "CREATE VECTOR INDEX idx ON t (v) WITH metric='L2', algorithm='HNSW', M=32, "
      + "ef_construction=128, alpha=1.5, quantization='SQ8'";
    SQLParser parser = new SQLParser(ddl);
    CreateIndexStatement indexStmt = (CreateIndexStatement) parser.parseStatement();

    assertEquals("L2", indexStmt.getVectorMetric());
    assertEquals("HNSW", indexStmt.getVectorAlgorithm());
    assertEquals(VectorIndexType.HNSW, indexStmt.getVectorIndexType());
    assertEquals(Integer.valueOf(32), indexStmt.getHnswM());
    assertEquals(Integer.valueOf(128), indexStmt.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.5), indexStmt.getHnswAlpha());
    assertEquals("SQ8", indexStmt.getQuantizationType());
    assertNull(indexStmt.getPqSegments());
    assertNull(indexStmt.getPqTrainingSize());
  }

  @Test
  public void testCaseInsensitiveAndAliasProperties() throws Exception {
    String ddl = "CREATE VECTOR INDEX idx ON t (v) WITH (ALGORITHM='HNSW', HNSW_M=48, "
      + "HNSW_EF_CONSTRUCTION=256, HNSW_ALPHA=1.8, QUANTIZATION_TYPE='PQ', "
      + "VECTOR_PQ_SEGMENTS=64, VECTOR_PQ_TRAINING_SIZE=2048)";
    SQLParser parser = new SQLParser(ddl);
    CreateIndexStatement indexStmt = (CreateIndexStatement) parser.parseStatement();

    assertEquals("HNSW", indexStmt.getVectorAlgorithm());
    assertEquals(Integer.valueOf(48), indexStmt.getHnswM());
    assertEquals(Integer.valueOf(256), indexStmt.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.8), indexStmt.getHnswAlpha());
    assertEquals("PQ", indexStmt.getQuantizationType());
    assertEquals(Integer.valueOf(64), indexStmt.getPqSegments());
    assertEquals(Integer.valueOf(2048), indexStmt.getPqTrainingSize());
  }

  @Test
  public void testCreateIndexStatementHnswConstructorOverload() {
    CreateIndexStatement stmt = new CreateIndexStatement(new NamedNode("IDX"),
      new NamedTableNode(null, TableName.create(null, "T")), IndexKeyConstraint.EMPTY, null, null,
      null, false, PTable.IndexType.VECTOR_GLOBAL, false, 0, null, null, "HNSW", "COSINE", 16, 200,
      1.2, "PQ", 96, 500);

    assertEquals("HNSW", stmt.getVectorAlgorithm());
    assertEquals(VectorIndexType.HNSW, stmt.getVectorIndexType());
    assertEquals("COSINE", stmt.getVectorMetric());
    assertEquals(Integer.valueOf(16), stmt.getHnswM());
    assertEquals(Integer.valueOf(200), stmt.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.2), stmt.getHnswAlpha());
    assertEquals("PQ", stmt.getQuantizationType());
    assertEquals(Integer.valueOf(96), stmt.getPqSegments());
    assertEquals(Integer.valueOf(500), stmt.getPqTrainingSize());
  }

  @Test
  public void testOmittedParametersPreservedAsNull() throws Exception {
    String ddl = "CREATE VECTOR INDEX idx ON t (v) WITH (algorithm='HNSW', M=16)";
    SQLParser parser = new SQLParser(ddl);
    CreateIndexStatement stmt = (CreateIndexStatement) parser.parseStatement();

    assertEquals("HNSW", stmt.getVectorAlgorithm());
    assertEquals(Integer.valueOf(16), stmt.getHnswM());
    assertNull(stmt.getHnswEfConstruction());
    assertNull(stmt.getHnswAlpha());
    assertNull(stmt.getQuantizationType());
    assertNull(stmt.getPqSegments());
    assertNull(stmt.getPqTrainingSize());
    assertNull(stmt.getVectorMetric());
  }

  @Test
  public void testVectorIndexParamsBuilderAndCopy() {
    CreateIndexStatement.VectorIndexParams params =
      new CreateIndexStatement.VectorIndexParams.Builder().setAlgorithm("HNSW").setMetric("COSINE")
        .setHnswM(32).setHnswEfConstruction(100).setHnswAlpha(1.4).setQuantizationType("SQ8")
        .build();

    assertEquals("HNSW", params.getAlgorithm());
    assertEquals(VectorIndexType.HNSW, params.getType());
    assertEquals("COSINE", params.getMetric());
    assertEquals(Integer.valueOf(32), params.getHnswM());
    assertEquals(Integer.valueOf(100), params.getHnswEfConstruction());
    assertEquals(Double.valueOf(1.4), params.getHnswAlpha());
    assertEquals("SQ8", params.getQuantizationType());
    assertNull(params.getPqSegments());
    assertNull(params.getPqTrainingSize());

    CreateIndexStatement.VectorIndexParams copy =
      new CreateIndexStatement.VectorIndexParams.Builder(params).setPqSegments(64).build();
    assertEquals("HNSW", copy.getAlgorithm());
    assertEquals(Integer.valueOf(32), copy.getHnswM());
    assertEquals(Integer.valueOf(64), copy.getPqSegments());
  }

  @Test
  public void testParseBsonVectorValueFunctionInSelect() throws Exception {
    String sql = "SELECT BSON_VECTOR_VALUE(doc, 'path', 128) FROM t";
    SQLParser parser = new SQLParser(sql);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected SelectStatement", stmt instanceof SelectStatement);
    SelectStatement selectStmt = (SelectStatement) stmt;
    assertEquals(1, selectStmt.getSelect().size());
    ParseNode node = selectStmt.getSelect().get(0).getNode();
    assertTrue("Expected BsonVectorValueParseNode", node instanceof BsonVectorValueParseNode);
    BsonVectorValueParseNode funcNode = (BsonVectorValueParseNode) node;
    assertEquals("BSON_VECTOR_VALUE", funcNode.getName());
    assertEquals(3, funcNode.getChildren().size());
  }

  @Test
  public void testVectorIndexQueryHintParsing() throws Exception {
    String sql = "SELECT /*+ VECTOR_INDEX(ef_search=128) */ * FROM t";
    SQLParser parser = new SQLParser(sql);
    BindableStatement stmt = parser.parseStatement();
    assertTrue("Expected SelectStatement", stmt instanceof SelectStatement);
    SelectStatement selectStmt = (SelectStatement) stmt;
    HintNode hintNode = selectStmt.getHint();
    assertNotNull(hintNode);
    assertTrue(hintNode.hasHint(Hint.VECTOR_INDEX));
    Map<String, String> params = HintNode.parseVectorIndexHint(hintNode);
    assertEquals("128", params.get(HintNode.HINT_PARAM_EF_SEARCH));

    // Multi-parameter hint
    HintNode multiHint =
      new HintNode("/*+ VECTOR_INDEX(probes=100, oversample=3.0, ef_search=128) */");
    assertTrue(multiHint.hasHint(Hint.VECTOR_INDEX));
    Map<String, String> multiParams = HintNode.parseVectorIndexHint(multiHint);
    assertEquals("100", multiParams.get(HintNode.HINT_PARAM_PROBES));
    assertEquals("3.0", multiParams.get(HintNode.HINT_PARAM_OVERSAMPLE));
    assertEquals("128", multiParams.get(HintNode.HINT_PARAM_EF_SEARCH));

    // Flag parameter without value
    HintNode flagHint = new HintNode("/*+ VECTOR_INDEX(probes=100, oversample) */");
    Map<String, String> flagParams = HintNode.parseVectorIndexHint(flagHint);
    assertEquals("100", flagParams.get(HintNode.HINT_PARAM_PROBES));
    assertEquals("true", flagParams.get(HintNode.HINT_PARAM_OVERSAMPLE));

    // Combined with other hints
    HintNode combined = new HintNode("/*+ INDEX(t idx) VECTOR_INDEX(ef_search=128) */");
    assertTrue(combined.hasHint(Hint.INDEX));
    assertTrue(combined.hasHint(Hint.VECTOR_INDEX));
    assertEquals("128", HintNode.parseVectorIndexHint(combined).get(HintNode.HINT_PARAM_EF_SEARCH));
  }

  @Test
  public void testHnswEfSearchConfigurationDefault() {
    assertEquals(64, QueryServicesOptions.DEFAULT_HNSW_EF_SEARCH);
    assertEquals("phoenix.vector.hnsw.ef_search.default", QueryServices.HNSW_EF_SEARCH_ATTRIB);
    QueryServicesOptions options = QueryServicesOptions.withDefaults();
    assertEquals(64, options.getHnswEfSearch());
    options.setHnswEfSearch(128);
    assertEquals(128, options.getHnswEfSearch());
  }
}
