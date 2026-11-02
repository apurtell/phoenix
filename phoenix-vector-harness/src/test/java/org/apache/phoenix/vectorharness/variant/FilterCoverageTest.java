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
package org.apache.phoenix.vectorharness.variant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.truth.QueryCondition.Attribute;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.junit.Test;

public class FilterCoverageTest {

  @Test
  public void testIvfCoversCategoryOnlyInTheCorpusLayout() {
    IvfVariant ivf = new IvfVariant();
    assertEquals(FilterCoverage.COVERED, ivf.coverage(TableLayout.CORPUS, Attribute.CATEGORY));
    assertEquals(FilterCoverage.UNCOVERED, ivf.coverage(TableLayout.CORPUS, Attribute.SOURCE));
    // The profile layout index includes no columns, so every filter is uncovered.
    assertEquals(FilterCoverage.UNCOVERED, ivf.coverage(TableLayout.PROFILE, Attribute.CATEGORY));
    assertEquals(FilterCoverage.UNCOVERED, ivf.coverage(TableLayout.PROFILE, Attribute.SOURCE));
    for (TableLayout layout : TableLayout.values()) {
      assertEquals(FilterCoverage.NONE, ivf.coverage(layout, Attribute.NONE));
    }
  }

  @Test
  public void testIvfCoverageAgreesWithTheIndexDdl() throws Exception {
    IvfVariant ivf = new IvfVariant();
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      Schema schema = new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim());
      for (TableLayout layout : TableLayout.values()) {
        String ddl = ivf.createIndexDdl(ctx, schema, layout);
        int include = ddl.indexOf(" INCLUDE (");
        String included = include < 0 ? "" : ddl.substring(include, ddl.indexOf(')', include));
        assertEquals(ddl, included.contains("CATEGORY"),
          ivf.coverage(layout, Attribute.CATEGORY) == FilterCoverage.COVERED);
        assertFalse(ddl, included.contains("SOURCE"));
      }
    }
  }

  @Test
  public void testHnswAndExactCoverageDoesNotDependOnTheLayout() {
    for (TableLayout layout : TableLayout.values()) {
      for (Attribute a : Arrays.asList(Attribute.CATEGORY, Attribute.SOURCE)) {
        assertEquals(FilterCoverage.LOCAL, new HnswVariant().coverage(layout, a));
        assertEquals(FilterCoverage.NO_INDEX, new ExactVariant().coverage(layout, a));
      }
      assertEquals(FilterCoverage.NONE, new HnswVariant().coverage(layout, Attribute.NONE));
      assertEquals(FilterCoverage.NONE, new ExactVariant().coverage(layout, Attribute.NONE));
    }
  }

  @Test
  public void testIdsAreLowerCase() {
    assertEquals("uncovered", FilterCoverage.UNCOVERED.id());
    assertEquals("no_index", FilterCoverage.NO_INDEX.id());
    assertTrue(Collections.singletonList("covered").contains(FilterCoverage.COVERED.id()));
  }
}
