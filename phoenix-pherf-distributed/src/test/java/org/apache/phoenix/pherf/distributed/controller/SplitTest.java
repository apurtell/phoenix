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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.RunOverrides;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.workload.openloop.ArrivalSchedule;
import org.junit.Test;

/**
 * Verifies for RQ-226 that partitioning a total across uniform or weighted shares produces values
 * whose sum equals the original total exactly.
 */
public class SplitTest {

  private static long sum(long[] shares) {
    long sum = 0;
    for (long share : shares) {
      sum = Math.addExact(sum, share);
    }
    return sum;
  }

  private static long[] uniform(int k) {
    long[] weights = new long[k];
    for (int i = 0; i < k; i++) {
      weights[i] = 1;
    }
    return weights;
  }

  @Test
  public void uniformSplitSumsExactly() {
    for (int k = 1; k <= 7; k++) {
      for (long total = 0; total <= 200; total++) {
        long[] shares = Split.shares(total, uniform(k));
        assertEquals(k, shares.length);
        assertEquals(total, sum(shares));
        // Any division remainder is distributed one unit at a time across the leading slots.
        for (int i = 0; i < k; i++) {
          assertEquals(total + " over " + k, total / k + (i < total % k ? 1 : 0), shares[i]);
        }
      }
      for (long total : new long[] { Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 6 }) {
        long[] shares = Split.shares(total, uniform(k));
        assertEquals(total, sum(shares));
        for (int i = 0; i < k; i++) {
          assertEquals(total / k + (i < total % k ? 1 : 0), shares[i]);
        }
      }
    }
  }

  @Test
  public void weightedSplitIsProportional() {
    long[] weights = { 1, 2, 1 };
    assertArrayEquals(new long[] { 3, 5, 2 }, Split.shares(10, weights));
    assertArrayEquals(new long[] { 250, 500, 250 }, Split.shares(1000, weights));
    assertArrayEquals(new long[] { 2, 3, 2 }, Split.shares(7, weights));
    assertArrayEquals(new long[] { 995, 5 }, Split.shares(1000, new long[] { 199, 1 }));
    long[] large = Split.shares(Long.MAX_VALUE, new long[] { Long.MAX_VALUE, 1, Long.MAX_VALUE });
    assertEquals(Long.MAX_VALUE, sum(large));
  }

  /**
   * Verifies that every allocated share equals either the floor or the ceiling of its exact
   * proportional quota.
   */
  @Test
  public void eachShareIsWithinOneOfItsQuota() {
    for (long w0 = 1; w0 <= 6; w0++) {
      for (long w1 = 1; w1 <= 6; w1++) {
        for (long w2 = 1; w2 <= 6; w2++) {
          long[] weights = { w0, w1, w2 };
          long total = w0 + w1 + w2;
          for (long n = 0; n <= 120; n++) {
            long[] shares = Split.shares(n, weights);
            assertEquals(n, sum(shares));
            for (int i = 0; i < 3; i++) {
              // Confirm that |share - n * w / W| < 1, which is equivalent in integer arithmetic to
              // the bound |share * W - n * w| < W.
              long error = Math.abs(shares[i] * total - n * weights[i]);
              assertTrue(n + " over " + w0 + ":" + w1 + ":" + w2, error < total);
            }
          }
        }
      }
    }
  }

  @Test
  public void tiesGoToTheLowerSlotAndTheInputsAloneDecide() {
    assertArrayEquals(new long[] { 1, 1, 0, 0 }, Split.shares(2, uniform(4)));
    assertArrayEquals(new long[] { 2, 1, 2, 1 }, Split.shares(6, new long[] { 2, 1, 2, 1 }));
    // When splitting 5 across weights 2:1:2:1, the fractional remainders are 2/3, 5/6, 2/3, and
    // 5/6; slots 1 and 3 receive the first two residual units, and the final unit is awarded to
    // slot 0 as the lower-indexed slot tied at 2/3.
    assertArrayEquals(new long[] { 2, 1, 1, 1 }, Split.shares(5, new long[] { 2, 1, 2, 1 }));
    long[] weights = { 3, 1, 4, 1, 5 };
    long[] first = Split.shares(1234567, weights);
    assertArrayEquals(first, Split.shares(1234567, weights.clone()));
    assertArrayEquals(new long[] { 3, 1, 4, 1, 5 }, weights);
  }

  @Test
  public void badInputsAreRefused() {
    for (long[] weights : new long[][] { {}, { 0 }, { 1, -1 } }) {
      try {
        Split.shares(1, weights);
        fail("weights " + Arrays.toString(weights));
      } catch (IllegalArgumentException expected) {
      }
    }
    try {
      Split.shares(-1, uniform(2));
      fail("negative total");
    } catch (IllegalArgumentException expected) {
    }
  }

  /**
   * Computes the total number of arrivals that Pherf schedules for a single slot's target rate
   * share over the given duration.
   */
  private static long arrivals(long rate, long durationMs) throws Exception {
    DataModel model = XMLConfigParser
      .readDataModel(new File(Plans.referenceDir(), "relational_scenario.xml").toPath());
    RunOverrides.of(String.valueOf(durationMs), null, String.valueOf(rate), true)
      .apply(Collections.singletonList(model));
    QuerySet querySet = model.getScenarios().get(0).getQuerySet().get(0);
    ArrivalSchedule.Cursor cursor = ArrivalSchedule.of(querySet.getOpenLoop()).cursor();
    long count = 0;
    while (cursor.next() != ArrivalSchedule.END) {
      count++;
    }
    return count;
  }

  /**
   * Verifies that applying each slot's share through Pherf's -targetRate override and arrival
   * schedule produces per-slot arrival counts that sum to the exact fleet total.
   */
  @Test
  public void splitRateGivesExactFleetArrivals() throws Exception {
    long rate = 100;
    long durationMs = 2000;
    for (long[] weights : new long[][] { uniform(3), { 1, 2, 1 }, { 3, 1 } }) {
      long[] shares = Split.shares(rate, weights);
      long fleet = 0;
      for (long share : shares) {
        long arrivals = arrivals(share, durationMs);
        assertEquals(share * durationMs / 1000, arrivals);
        fleet += arrivals;
      }
      assertEquals(rate * durationMs / 1000, fleet);
    }
    assertArrayEquals(new long[] { 34, 33, 33 }, Split.shares(rate, uniform(3)));
    assertEquals(68, arrivals(34, durationMs));
    assertEquals(66, arrivals(33, durationMs));
  }
}
