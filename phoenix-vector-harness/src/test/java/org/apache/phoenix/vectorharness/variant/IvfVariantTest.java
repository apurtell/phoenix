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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.junit.Test;

public class IvfVariantTest {

  private final IvfVariant ivf = new IvfVariant();

  @Test
  public void testExpectedPlansOfSingleTenantTables() {
    for (QueryShape s : new QueryShape[] { QueryShape.UNFILTERED, QueryShape.COVERED_FILTER,
      QueryShape.UNCOVERED_FILTER, QueryShape.GLOBAL, QueryShape.DISTANCE_BOUND }) {
      assertEquals(s.id(), PlanClass.IVF_PROBE, ivf.expectedPlan(s, false));
    }
    // TENANT_ID leads the key, so a tenant equality or a key range is served filter first.
    assertEquals(PlanClass.FILTER_FIRST, ivf.expectedPlan(QueryShape.TENANT_SCOPED, false));
    assertEquals(PlanClass.FILTER_FIRST, ivf.expectedPlan(QueryShape.PK_RANGE, false));
  }

  @Test
  public void testExpectedPlansOfMultiTenantTables() {
    // Tenant connections probe the centroid ranges under their tenant prefix; the filter shapes
    // are tenant scoped by default. A key range runs on a tenant connection too, and its DOC_ID
    // bound cannot bind the index key past _CENTROID_ID, so it stays a server filter.
    for (QueryShape s : new QueryShape[] { QueryShape.TENANT_SCOPED, QueryShape.COVERED_FILTER,
      QueryShape.UNCOVERED_FILTER, QueryShape.PK_RANGE }) {
      assertEquals(s.id(), PlanClass.IVF_PROBE, ivf.expectedPlan(s, true));
    }
    // Shapes without a tenant run on a global connection, which gets no centroid ranges.
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
      // The steady state runs at the default breadth, so the frontier has it too.
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
}
