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
package org.apache.phoenix.vectorharness.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.junit.Test;

public class SchemaTest {

  private static final String CORPUS = "CREATE TABLE IF NOT EXISTS VH.DOC_CORPUS_IVF_TEST"
    + " (TENANT_ID VARCHAR NOT NULL, DOC_ID VARCHAR NOT NULL, SOURCE VARCHAR, CATEGORY VARCHAR,"
    + " ACL_TAG VARCHAR, CREATED_AT TIMESTAMP, BODY VARCHAR, EMBEDDING VECTOR(FLOAT, 16)"
    + " CONSTRAINT PK PRIMARY KEY (TENANT_ID, DOC_ID))";
  private static final String PROFILE = "CREATE TABLE IF NOT EXISTS VH.DOC_PROFILE_IVF_TEST"
    + " (TENANT_ID VARCHAR NOT NULL, DOC_ID VARCHAR NOT NULL, PROFILE BSON"
    + " CONSTRAINT PK PRIMARY KEY (TENANT_ID, DOC_ID))";

  private static Schema schema(String... kv) {
    Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    HarnessConfig c = HarnessConfig.of("test", p);
    return new Schema(c, TableNames.of(c), 16);
  }

  @Test
  public void testDefaultDdlOmitsUpdateCacheFrequency() {
    assertEquals(CORPUS + " SPLIT ON (?, ?)", schema().createTable(TableLayout.CORPUS, 2));
    assertEquals(PROFILE, schema().createTable(TableLayout.PROFILE, 0));
  }

  @Test
  public void testExplicitUpdateCacheFrequencySetsProperty() {
    assertEquals(CORPUS + " UPDATE_CACHE_FREQUENCY=60000 SPLIT ON (?, ?)",
      schema("table.update_cache_frequency", "60000").createTable(TableLayout.CORPUS, 2));
    assertEquals(PROFILE + " UPDATE_CACHE_FREQUENCY=900000",
      schema("table.update_cache_frequency", "900_000").createTable(TableLayout.PROFILE, 0));
  }

  @Test
  public void testAlwaysKeepsEarlierDdl() {
    for (String v : new String[] { "ALWAYS", "always", "0", "" }) {
      assertEquals(v, CORPUS + " SPLIT ON (?, ?)",
        schema("table.update_cache_frequency", v).createTable(TableLayout.CORPUS, 2));
      assertEquals(v, PROFILE,
        schema("table.update_cache_frequency", v).createTable(TableLayout.PROFILE, 0));
    }
  }

  @Test
  public void testUpdateCacheFrequencyFollowsOtherProperties() {
    String ddl =
      schema("table.multi_tenant", "true", "table.storage_scheme", "SINGLE_CELL_ARRAY_WITH_OFFSETS",
        "table.update_cache_frequency", "60000").createTable(TableLayout.CORPUS, 1);
    assertEquals(CORPUS + " MULTI_TENANT=true, IMMUTABLE_ROWS=true,"
      + " IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS, UPDATE_CACHE_FREQUENCY=60000"
      + " SPLIT ON (?)", ddl);
    String always = schema("table.multi_tenant", "true", "table.salt_buckets", "4",
      "table.update_cache_frequency", "ALWAYS").createTable(TableLayout.PROFILE, 0);
    assertEquals(PROFILE + " MULTI_TENANT=true, SALT_BUCKETS=4", always);
    assertFalse(always.contains("UPDATE_CACHE_FREQUENCY"));
    assertTrue(schema("table.salt_buckets", "4", "table.update_cache_frequency", "60000")
      .createTable(TableLayout.PROFILE, 0)
      .endsWith(" SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000"));
  }

  @Test
  public void testStatisticsOnSetsGuidepostProperties() {
    assertEquals(
      CORPUS + " GUIDE_POSTS_WIDTH=262144, USE_STATS_FOR_PARALLELIZATION=false SPLIT ON (?, ?)",
      schema("table.statistics", "on").createTable(TableLayout.CORPUS, 2));
    assertEquals(
      PROFILE + " UPDATE_CACHE_FREQUENCY=60000, GUIDE_POSTS_WIDTH=262144,"
        + " USE_STATS_FOR_PARALLELIZATION=false",
      schema("table.statistics", "on", "table.update_cache_frequency", "60000")
        .createTable(TableLayout.PROFILE, 0));
    assertEquals(256L * 1024, Schema.STATISTICS_GUIDE_POSTS_WIDTH);
  }

  @Test
  public void testStatisticsOffOmitsGuidepostProperties() {
    for (String[] kv : new String[][] { {}, { "table.statistics", "off" } }) {
      String ddl = schema(kv).createTable(TableLayout.CORPUS, 0);
      assertFalse(ddl, ddl.contains("GUIDE_POSTS_WIDTH"));
      assertFalse(ddl, ddl.contains("USE_STATS_FOR_PARALLELIZATION"));
    }
  }
}
