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
package org.apache.phoenix.pherf.analysis;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** Verifies median calculation and seeded percentile bootstrap estimation for RQ-213. */
public class BootstrapTest {
  private static final double[] SPREAD = new double[31];

  static {
    for (int i = 0; i < SPREAD.length; i++) {
      SPREAD[i] = 1000 + (i * 37) % 101 * 10;
    }
  }

  @Test
  public void testMedianOddAndEven() {
    assertEquals(1000.0, Bootstrap.median(new double[] { 900, 1200, 1000 }), 0);
    assertEquals(1050.0, Bootstrap.median(new double[] { 900, 1000, 1100, 1300 }), 0);
    assertEquals(7.0, Bootstrap.median(new double[] { 7 }), 0);
  }

  @Test
  public void testMedianLeavesInputUnchanged() {
    double[] values = { 900, 1200, 1000 };
    Bootstrap.median(values);
    assertArrayEquals(new double[] { 900, 1200, 1000 }, values, 0);
  }

  @Test
  public void testSameSeedSameCi() {
    double[] first = Bootstrap.ci(SPREAD, 8070);
    double[] second = Bootstrap.ci(SPREAD.clone(), 8070);
    assertArrayEquals(first, second, 0);
    double[] baseline = { 1000, 1010, 990, 1005 };
    assertArrayEquals(Bootstrap.deltaCi(baseline, SPREAD, 8070),
      Bootstrap.deltaCi(baseline, SPREAD, 8070), 0);
  }

  @Test
  public void testOtherSeedOtherCi() {
    // Varying the seed has an observable effect: supplying a different seed for two spread
    // datasets produces a different confidence interval for their difference. The confidence
    // interval for a single median shifts less because the median is always a sample value, and
    // across these 31 values its ranks fall on the same values for each seed.
    double[] other = new double[SPREAD.length];
    for (int i = 0; i < other.length; i++) {
      other[i] = 1200 + i * i * 7.3;
    }
    double[] first = Bootstrap.deltaCi(SPREAD, other, 8070);
    double[] second = Bootstrap.deltaCi(SPREAD, other, 8071);
    assertFalse(Arrays.toString(first) + " " + Arrays.toString(second),
      Arrays.equals(first, second));
    assertArrayEquals(first, Bootstrap.deltaCi(SPREAD, other, 8070), 0);
  }

  @Test
  public void testConstantSampleGivesPoint() {
    assertArrayEquals(new double[] { 5, 5 }, Bootstrap.ci(new double[] { 5, 5, 5 }, 1), 0);
    assertArrayEquals(new double[] { 5, 5 }, Bootstrap.ci(new double[] { 5 }, 1), 0);
    assertArrayEquals(new double[] { 200, 200 },
      Bootstrap.deltaCi(new double[] { 1000, 1000 }, new double[] { 1200, 1200 }, 1), 0);
  }

  @Test
  public void testCiWithinRangeAndHoldsMedian() {
    double[][] samples = { SPREAD, { 900, 1200, 1000 }, { 1, 2 }, { 10, 20, 30, 40, 1000 } };
    for (double[] sample : samples) {
      for (long seed = 0; seed < 5; seed++) {
        double[] ci = Bootstrap.ci(sample, seed);
        double min = Arrays.stream(sample).min().getAsDouble();
        double max = Arrays.stream(sample).max().getAsDouble();
        double median = Bootstrap.median(sample);
        String text = Arrays.toString(sample) + " " + Arrays.toString(ci);
        assertTrue(text, min <= ci[0] && ci[0] <= median);
        assertTrue(text, median <= ci[1] && ci[1] <= max);
      }
    }
  }

  @Test
  public void testDeltaCiExcludesZeroForSeparateSets() {
    double[] ci =
      Bootstrap.deltaCi(new double[] { 1000, 1010, 990 }, new double[] { 1500, 1510, 1490 }, 8070);
    // Because the two sample sets do not overlap, every resampled difference is bounded below by
    // 1490 - 1010.
    assertTrue(Arrays.toString(ci), ci[0] >= 480 && ci[1] <= 520);
  }

  @Test
  public void testStreamSeedDependsOnKey() {
    assertEquals(Bootstrap.streamSeed(8070, "set|query:S:q|steady|p99Us"),
      Bootstrap.streamSeed(8070, "set|query:S:q|steady|p99Us"));
    assertFalse(Bootstrap.streamSeed(8070, "set|query:S:q|steady|p99Us")
        == Bootstrap.streamSeed(8070, "set|query:S:q|steady|p90Us"));
    assertFalse(Bootstrap.streamSeed(8070, "k") == Bootstrap.streamSeed(8071, "k"));
  }
}
