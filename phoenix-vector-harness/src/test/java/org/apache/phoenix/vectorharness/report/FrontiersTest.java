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
package org.apache.phoenix.vectorharness.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.junit.Test;

public class FrontiersTest {

  /** Builds a frontier point with the columns that the operating point choice reads. */
  private static FrontierPoint point(String sweep, double recall, double p50, Long rowsScanned,
    Double rpcsPerQuery) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("layout", "corpus");
    row.put("family", "unfiltered");
    row.put("k", "10");
    row.put("sweep", sweep);
    row.put("recall", Double.toString(recall));
    row.put("p50_ms", Double.toString(p50));
    row.put("queries", "100");
    if (rowsScanned != null) {
      row.put("rows_scanned", Long.toString(rowsScanned));
    }
    if (rpcsPerQuery != null) {
      row.put("rpcs_per_query", Double.toString(rpcsPerQuery));
    }
    return FrontierPoint.of(row);
  }

  @Test
  public void testZeroBandKeepsTheLatencyChoice() {
    FrontierPoint fast = point("probes=64", 0.97, 20, 900_000L, 40.0);
    FrontierPoint lean = point("probes=16", 0.96, 30, 200_000L, 20.0);
    List<FrontierPoint> group = Arrays.asList(lean, fast);
    assertSame(fast, Frontiers.operatingPoint(group, 0.95));
    assertSame(fast, Frontiers.operatingPoint(group, 0.95, 0.0));
    assertSame(fast, Frontiers.operatingPoint(group, 0.95, -1.0));
  }

  @Test
  public void testBandPrefersFewerRowsScannedPerQuery() {
    FrontierPoint fast = point("probes=64", 0.97, 20, 900_000L, 10.0);
    FrontierPoint lean = point("probes=16", 0.96, 30, 200_000L, 40.0);
    FrontierPoint slow = point("probes=8", 0.95, 500, 100_000L, 5.0);
    List<FrontierPoint> group = Arrays.asList(fast, lean, slow);
    assertSame(lean, Frontiers.operatingPoint(group, 0.95, 100));
    // A point outside the band does not compete, even with less work.
    assertSame(lean, Frontiers.operatingPoint(group, 0.95, 10));
    assertSame(fast, Frontiers.operatingPoint(group, 0.95, 5));
  }

  @Test
  public void testBandIgnoresPointsBelowTheTarget() {
    FrontierPoint fast = point("probes=64", 0.97, 20, 900_000L, 40.0);
    FrontierPoint low = point("probes=4", 0.80, 10, 50_000L, 5.0);
    assertSame(fast, Frontiers.operatingPoint(Arrays.asList(low, fast), 0.95, 100));
  }

  @Test
  public void testRpcsAndThenLatencyBreakEqualRows() {
    FrontierPoint fast = point("a", 0.97, 20, 400_000L, 30.0);
    FrontierPoint fewRpcs = point("b", 0.97, 40, 400_000L, 10.0);
    FrontierPoint fewRpcsSlower = point("c", 0.97, 60, 400_000L, 10.0);
    assertSame(fewRpcs,
      Frontiers.operatingPoint(Arrays.asList(fewRpcsSlower, fast, fewRpcs), 0.95, 100));
    FrontierPoint same = point("d", 0.97, 50, 400_000L, 30.0);
    assertSame(fast, Frontiers.operatingPoint(Arrays.asList(same, fast), 0.95, 100));
  }

  @Test
  public void testBandWithoutWorkColumnsKeepsTheLatencyChoice() {
    FrontierPoint fast = point("a", 0.97, 20, null, null);
    FrontierPoint slow = point("b", 0.97, 40, null, null);
    assertSame(fast, Frontiers.operatingPoint(Arrays.asList(slow, fast), 0.95, 100));
  }

  @Test
  public void testBandAppliesToTheMostAccurateFallback() {
    FrontierPoint fast = point("a", 0.90, 20, 900_000L, 40.0);
    FrontierPoint lean = point("b", 0.90, 50, 300_000L, 40.0);
    FrontierPoint less = point("c", 0.85, 10, 10_000L, 1.0);
    List<FrontierPoint> group = Arrays.asList(less, fast, lean);
    assertSame(fast, Frontiers.operatingPoint(group, 0.99));
    assertSame(lean, Frontiers.operatingPoint(group, 0.99, 100));
  }

  @Test
  public void testEmptyGroupHasNoOperatingPoint() {
    assertNull(Frontiers.operatingPoint(Collections.emptyList(), 0.95, 100));
  }

  @Test
  public void testHostNoiseSetsTheBand() {
    assertEquals(100.0, HarnessConfig.of("t", new Properties()).operatingPointNoiseBandMillis(),
      0.0);
    Properties quiet = new Properties();
    quiet.setProperty("host.noise", "quiet");
    quiet.setProperty("report.operating_point.noise_band", "250ms");
    assertEquals(0.0, HarnessConfig.of("t", quiet).operatingPointNoiseBandMillis(), 0.0);
    Properties wide = new Properties();
    wide.setProperty("report.operating_point.noise_band", "250ms");
    assertEquals(250.0, HarnessConfig.of("t", wide).operatingPointNoiseBandMillis(), 0.0);
    // On a quiet host, the table titles do not change.
    assertEquals("", new ReportGenerator().noiseNote());
    assertEquals("", ReportGenerator.of(HarnessConfig.of("t", quiet)).noiseNote());
    String note = ReportGenerator.of(HarnessConfig.of("t", wide)).noiseNote();
    assertTrue(note, note.contains("250 ms"));
  }
}
