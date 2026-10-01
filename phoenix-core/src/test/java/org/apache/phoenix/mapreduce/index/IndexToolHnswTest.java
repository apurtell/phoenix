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
package org.apache.phoenix.mapreduce.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.mapreduce.vector.HnswGraphBuildInputFormat;
import org.apache.phoenix.mapreduce.vector.HnswGraphBuildMapper;
import org.apache.phoenix.query.BaseConnectionlessQueryTest;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.VectorIndexType;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;

public class IndexToolHnswTest extends BaseConnectionlessQueryTest {

  private static final String DATA_TABLE_NAME = "DATA_HNSW_TEST";
  private static final String INDEX_TABLE_NAME = "IDX_HNSW_TEST";
  private static final String INDEX_PQ_TABLE_NAME = "IDX_HNSW_PQ_TEST";

  private static final String CREATE_DATA_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS " + DATA_TABLE_NAME + " (\n"
      + "    ID VARCHAR NOT NULL PRIMARY KEY,\n" + "    V VECTOR(FLOAT, 4)\n" + ")";

  private static final String CREATE_INDEX_DDL =
    "CREATE VECTOR INDEX IF NOT EXISTS " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_NAME + " (V)\n"
      + "    ALGORITHM = 'HNSW',\n" + "    M = 32,\n" + "    EF_CONSTRUCTION = 200,\n"
      + "    ALPHA = 1.5,\n" + "    DISTANCE_METRIC = 'COSINE'";

  private static final String CREATE_INDEX_PQ_DDL = "CREATE VECTOR INDEX IF NOT EXISTS "
    + INDEX_PQ_TABLE_NAME + " ON " + DATA_TABLE_NAME + " (V)\n" + "    ALGORITHM = 'HNSW',\n"
    + "    DISTANCE_METRIC = 'L2',\n" + "    QUANTIZATION = 'PQ',\n" + "    PQ_SEGMENTS = 2";

  private static PTable pDataTable;
  private static PTable pIndexTable;
  private static PTable pIndexPqTable;
  private static Connection conn;

  @BeforeClass
  public static synchronized void setupClass() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(true);
    conn.createStatement().execute(CREATE_DATA_TABLE_DDL);
    conn.createStatement().execute(CREATE_INDEX_DDL);
    conn.createStatement().execute(CREATE_INDEX_PQ_DDL);

    PhoenixConnection pConn = conn.unwrap(PhoenixConnection.class);
    pDataTable = pConn.getTable(new PTableKey(null, DATA_TABLE_NAME));
    pIndexTable = pConn.getTable(new PTableKey(null, INDEX_TABLE_NAME));
    pIndexPqTable = pConn.getTable(new PTableKey(null, INDEX_PQ_TABLE_NAME));
  }

  @Test
  public void testConfigureJobForHnswIndexDispatch() throws Exception {
    Configuration conf = HBaseConfiguration.create();
    IndexTool it = new IndexTool();
    it.setPDataTable(pDataTable);
    it.setPIndexTable(pIndexTable);

    IndexTool.JobFactory jobFactory = it.new JobFactory(conn, conf, null);
    Job job = jobFactory.configureJobForVectorIndex();

    assertNotNull(job);
    assertEquals(HnswGraphBuildMapper.class, job.getMapperClass());
    assertEquals(HnswGraphBuildInputFormat.class, job.getInputFormatClass());
    assertEquals(PhoenixIndexImportDirectReducer.class, job.getReducerClass());
    assertEquals(ImmutableBytesWritable.class, job.getMapOutputKeyClass());
    assertEquals(IntWritable.class, job.getMapOutputValueClass());

    Configuration jobConf = job.getConfiguration();
    assertEquals("HNSW", PhoenixConfigurationUtil.getVectorAlgorithm(jobConf));
    assertEquals(32, PhoenixConfigurationUtil.getHnswM(jobConf));
    assertEquals(200, PhoenixConfigurationUtil.getHnswEfConstruction(jobConf));
    assertEquals(1.5, PhoenixConfigurationUtil.getHnswAlpha(jobConf), 0.001);
    assertEquals(4, PhoenixConfigurationUtil.getVectorDimension(jobConf));
    assertEquals("COSINE", PhoenixConfigurationUtil.getVectorDistanceMetric(jobConf));

    assertEquals(3600000L, jobConf.getLong("mapreduce.task.timeout", 0));
    assertTrue(jobConf.getLong("mapreduce.map.memory.mb", 0) >= 2048L);
    String javaOpts = jobConf.get("mapreduce.map.java.opts");
    assertNotNull(javaOpts);
    assertTrue(javaOpts.startsWith("-Xmx"));
  }

  @Test
  public void testResolveVectorColumnExpression() throws Exception {
    IndexTool it = new IndexTool();
    it.setPDataTable(pDataTable);
    it.setPIndexTable(pIndexTable);

    IndexTool.JobFactory jobFactory = it.new JobFactory(conn, HBaseConfiguration.create(), null);
    PhoenixConnection pConn = conn.unwrap(PhoenixConnection.class);
    IndexMaintainer maintainer = pIndexTable.getIndexMaintainer(pDataTable, pConn);

    String vectorColExpr = jobFactory.resolveVectorColumnExpression(maintainer, pConn);
    assertEquals("V", vectorColExpr);
  }

  @Test
  public void testVectorIndexAlgorithmCheckAndScorecardGuard() {
    PTable.VectorIndex vi = pIndexTable.getVectorIndex();
    assertNotNull(vi);
    assertEquals("HNSW", vi.getAlgorithm());
    assertTrue(VectorIndexType.HNSW.name().equalsIgnoreCase(vi.getAlgorithm()));
    // Guard verification: HNSW algorithm should NOT match IVF, ensuring reconciliation is skipped
    assertFalse(VectorIndexType.IVF.name().equalsIgnoreCase(vi.getAlgorithm()));
  }

  @Test
  public void testHnswPqParameters() {
    PTable.VectorIndex viPq = pIndexPqTable.getVectorIndex();
    assertNotNull(viPq);
    assertEquals("HNSW", viPq.getAlgorithm());
    assertEquals("PQ", viPq.getQuantizationType());
    assertNotNull(viPq.getPqSegments());
    assertEquals(2, viPq.getPqSegments().intValue());
  }
}
