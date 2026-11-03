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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.junit.Test;

public class IvfVariantTest {

  private final IvfVariant ivf = new IvfVariant();

  @Test
  public void testExpectedPlansOfSingleTenantTables() {
    for (QueryShape s : new QueryShape[] { QueryShape.UNFILTERED, QueryShape.COVERED_FILTER,
      QueryShape.UNCOVERED_FILTER, QueryShape.GLOBAL, QueryShape.DISTANCE_BOUND }) {
      assertEquals(s.id(), PlanClass.IVF_PROBE, ivf.expectedPlan(s, false));
    }
    // Leading TENANT_ID column routes tenant equality and key range queries to filter-first plan
    assertEquals(PlanClass.FILTER_FIRST, ivf.expectedPlan(QueryShape.TENANT_SCOPED, false));
    assertEquals(PlanClass.FILTER_FIRST, ivf.expectedPlan(QueryShape.PK_RANGE, false));
  }

  @Test
  public void testExpectedPlansOfMultiTenantTables() {
    // Tenant scoped queries probe centroid ranges within tenant prefix.
    // Key range queries execute via tenant connection with DOC_ID server filter.
    for (QueryShape s : new QueryShape[] { QueryShape.TENANT_SCOPED, QueryShape.COVERED_FILTER,
      QueryShape.UNCOVERED_FILTER, QueryShape.PK_RANGE }) {
      assertEquals(s.id(), PlanClass.IVF_PROBE, ivf.expectedPlan(s, true));
    }
    // Global queries execute on global connection via full scan across centroids
    for (QueryShape s : new QueryShape[] { QueryShape.GLOBAL, QueryShape.UNFILTERED,
      QueryShape.DISTANCE_BOUND }) {
      assertEquals(s.id(), PlanClass.IVF_FULL_SCAN, ivf.expectedPlan(s, true));
    }
  }

  @Test
  public void testDefaultProbesFollowTheSquareRootRule() {
    assertEquals(3, IvfVariant.defaultProbes(null, 8));
    assertEquals(32, IvfVariant.defaultProbes("", 1024));
    assertEquals(1, IvfVariant.defaultProbes("0", 1));
    assertEquals(2, IvfVariant.defaultProbes("-1", 4));
  }

  @Test
  public void testDefaultProbesFollowTheConfiguredCount() {
    assertEquals(5, IvfVariant.defaultProbes("5", 8));
    assertEquals(8, IvfVariant.defaultProbes(" 64 ", 8));
  }

  @Test
  public void testDefaultPointNamesTheConfiguredCount() throws Exception {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    p.setProperty("ivf.lists", "8");
    p.setProperty("ivf.sample_size", "50");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      SweepPoint point = new IvfVariant().defaultPoint(ctx, null);
      assertEquals("probes=default(3)", point.label());
      assertEquals(3, point.value());
    }
    p.setProperty(IvfVariant.PROBE_COUNT_KEY, "64");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      SweepPoint point = new IvfVariant().defaultPoint(ctx, null);
      assertEquals("probes=default(8)", point.label());
      assertEquals(8, point.value());
    }
  }

  private static List<String> labels(List<SweepPoint> points) {
    List<String> out = new ArrayList<>();
    for (SweepPoint p : points) {
      out.add(p.label());
    }
    return out;
  }

  @Test
  public void testSweepMeasuresTheDefaultProbes() throws Exception {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    p.setProperty("ivf.lists", "8");
    p.setProperty("ivf.sample_size", "50");
    p.setProperty("query.ivf.probe.values", "1,2,all");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      // Default probe breadth is included in frontier sweep points
      assertEquals(Arrays.asList("probes=1", "probes=2", "probes=default(3)", "probes=all(8)"),
        labels(new IvfVariant().sweep(ctx, null)));
    }
    p.setProperty("query.ivf.probe.values", "1,3,all");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      assertEquals(Arrays.asList("probes=1", "probes=3", "probes=all(8)"),
        labels(new IvfVariant().sweep(ctx, null)));
    }
    p.setProperty("query.ivf.probe.values", "16,32");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      assertEquals(Arrays.asList("probes=default(3)", "probes=8"),
        labels(new IvfVariant().sweep(ctx, null)));
    }
  }

  private static Properties smallCorpus(String... kv) {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    p.setProperty("ivf.lists", "8");
    p.setProperty("ivf.sample_size", "50");
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }

  private static String ddl(String... kv) throws Exception {
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", smallCorpus(kv)))) {
      Schema schema = new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim());
      return new IvfVariant().createIndexDdl(ctx, schema, TableLayout.CORPUS);
    }
  }

  private static List<String> warnings(String... kv) throws Exception {
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", smallCorpus(kv)))) {
      return new IvfVariant().warnings(ctx, ctx.corpus().dim(), 1000);
    }
  }

  @Test
  public void testIndexDdlWithoutSplits() throws Exception {
    String ddl = ddl("table.regions", "1");
    assertFalse(ddl, ddl.contains("SPLIT ON"));
    assertFalse(ddl, ddl.contains("SALT_BUCKETS"));
    ddl = ddl("table.regions", "4", "ivf.regions", "1");
    assertFalse(ddl, ddl.contains("SPLIT ON"));
  }

  @Test
  public void testIndexDdlSplitsOnCentroidIds() throws Exception {
    String ddl = ddl("table.regions", "2");
    assertTrue(ddl, ddl.endsWith(" SPLIT ON (4)"));
    ddl = ddl("ivf.regions", "4", "ivf.async", "true");
    assertTrue(ddl, ddl.endsWith(" ASYNC SPLIT ON (2, 4, 6)"));
    // More regions than lists gives one list per region.
    ddl = ddl("ivf.regions", "16");
    assertTrue(ddl, ddl.endsWith(" SPLIT ON (1, 2, 3, 4, 5, 6, 7)"));
  }

  @Test
  public void testIndexDdlWithAnExplicitSalt() throws Exception {
    String ddl = ddl("table.salt_buckets", "4", "ivf.salt_buckets", "0", "ivf.regions", "2");
    assertTrue(ddl, ddl.contains("SALT_BUCKETS = 0"));
    assertTrue(ddl, ddl.endsWith(" SPLIT ON (4)"));
    ddl = ddl("ivf.salt_buckets", "3");
    assertTrue(ddl, ddl.contains("SALT_BUCKETS = 3"));
    assertFalse(ddl, ddl.contains("SPLIT ON"));
  }

  @Test
  public void testIndexDdlOfASaltedDataTable() throws Exception {
    String ddl = ddl("table.salt_buckets", "4", "ivf.regions", "2");
    assertFalse(ddl, ddl.contains("SPLIT ON"));
    assertFalse(ddl, ddl.contains("SALT_BUCKETS"));
    List<String> w = warnings("table.salt_buckets", "4", "ivf.regions", "2");
    assertEquals(w.toString(), 2, w.size());
    assertTrue(w.get(0), w.get(0).contains("inherits SALT_BUCKETS=4"));
    assertTrue(w.get(1), w.get(1).contains("has no effect"));
  }

  @Test
  public void testIndexDdlOfAMultiTenantTable() throws Exception {
    String ddl = ddl("table.multi_tenant", "true", "ivf.regions", "4");
    assertFalse(ddl, ddl.contains("SPLIT ON"));
    List<String> w = warnings("table.multi_tenant", "true", "ivf.regions", "4");
    assertEquals(w.toString(), 1, w.size());
    assertTrue(w.get(0), w.get(0).contains("midkey"));
  }
}
