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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.junit.Test;

public class SteadyStateTest {

  private static float[] random(SplitMix64 rnd, int dim, double norm) {
    float[] v = new float[dim];
    double sum = 0;
    for (int i = 0; i < dim; i++) {
      v[i] = (float) rnd.nextGaussian();
      sum += v[i] * v[i];
    }
    double f = norm / Math.sqrt(sum);
    for (int i = 0; i < dim; i++) {
      v[i] = (float) (v[i] * f);
    }
    return v;
  }

  @Test
  public void testProbeVectorIsDeterministicAndClose() {
    float[] base = random(new SplitMix64(1), 16, 1.0);
    for (Metric m : new Metric[] { Metric.L2, Metric.COSINE }) {
      float[] a = SteadyState.probeVector(base, 7, m);
      assertArrayEquals(a, SteadyState.probeVector(base, 7, m), 0f);
      assertTrue(m + " probe is its query's nearest neighbor",
        m.distance(a, a) <= m.distance(a, base));
      for (int i = 0; i < base.length; i++) {
        assertEquals(base[i], a[i], 1e-2 * Math.abs(base[i]) + 1e-6);
      }
    }
  }

  @Test
  public void testDescribeResult() {
    QueryExecutor.Result r = new QueryExecutor.Result(new long[] { 7, 3 },
      new double[] { 1.5, 0.25 }, 1, 1, 0, 0, 0, 0, 0, null);
    assertEquals("[7@1.500, 3@0.2500]", SteadyState.describe(r));
    assertEquals("error IOException: boom",
      SteadyState.describe(QueryExecutor.Result.failed(1, "IOException: boom")));
  }

  @Test
  public void testInnerProductProbeIsItsOwnNearestNeighbor() {
    SplitMix64 rnd = new SplitMix64(2);
    for (int trial = 0; trial < 50; trial++) {
      float[] base = random(rnd, 16, 1.0);
      float[] probe = SteadyState.probeVector(base, trial, Metric.INNER_PRODUCT);
      double self = Metric.INNER_PRODUCT.distance(probe, probe);
      // Rows near the query, and rows far longer than the query, all rank after the probe row.
      for (double norm : new double[] { 1.0, 10.0, 100.0 }) {
        for (int i = 0; i < 20; i++) {
          float[] x = random(rnd, 16, norm);
          assertTrue("row of norm " + norm, self < Metric.INNER_PRODUCT.distance(probe, x));
        }
        float[] aligned = base.clone();
        for (int i = 0; i < aligned.length; i++) {
          aligned[i] = (float) (aligned[i] * norm);
        }
        assertTrue("aligned row of norm " + norm,
          self < Metric.INNER_PRODUCT.distance(probe, aligned));
      }
    }
  }

  @Test
  public void testMutationLatencyStartsAtTheReopeningOfTheGate() throws Exception {
    WriteGate gate = new WriteGate();
    long before = System.nanoTime();
    assertFalse(gate.reopenedAfter(before));
    assertEquals(before, SteadyState.latencyStart(before, gate));
    assertTrue(gate.close(1, TimeUnit.SECONDS));
    Thread.sleep(5);
    gate.open();
    // A mutation scheduled before the reopening waited for the sample, not for the write.
    assertTrue(gate.reopenedAfter(before));
    assertEquals(gate.lastOpenedNanos(), SteadyState.latencyStart(before, gate));
    assertTrue(gate.lastOpenedNanos() - before > 0);
    long after = System.nanoTime() + 1;
    assertFalse(gate.reopenedAfter(after));
    assertEquals(after, SteadyState.latencyStart(after, gate));
  }

  private static final String INDEX = "VH.DOC_CORPUS_T_VIDX";
  private static final String UNRESTRICTED = "vectorHnswRegionsUnrestricted." + INDEX;
  private static final String REGIONS = "vectorHnswRegions." + INDEX;

  private static MetricSnapshot sample(long wall, double regions, double unrestricted, double queue,
    int missing) {
    Map<String, Double> v = new HashMap<>();
    v.put(REGIONS, regions);
    v.put(UNRESTRICTED, unrestricted);
    v.put("vectorHnswRebuildQueue", queue);
    return new MetricSnapshot(wall, null, v, 2 - missing, missing);
  }

  private static boolean recovered(MetricSnapshot pre, MetricSnapshot s, int tableRegions,
    long notBefore) {
    return SteadyState.recovered(pre, s, UNRESTRICTED, REGIONS, tableRegions, notBefore);
  }

  @Test
  public void testRecoveryNeedsEveryRegionOfTheTableOpen() {
    MetricSnapshot pre = sample(1000, 4, 0, 0, 0);
    assertTrue(recovered(pre, sample(2000, 4, 0, 0, 0), 4, 0));
    // A split: the cached sample still shows the parent, or a daughter has no manager yet.
    assertFalse("pre-split values", recovered(pre, sample(2000, 4, 0, 0, 0), 5, 0));
    assertTrue(recovered(pre, sample(3000, 5, 0, 0, 0), 5, 0));
    // A merge leaves fewer regions, which is a recovery once the merged region is open.
    assertTrue(recovered(pre, sample(3000, 3, 0, 0, 0), 3, 0));
    // A restart: the regions of the stopped server are not open anywhere yet.
    assertFalse(recovered(pre, sample(3000, 2, 0, 0, 0), 4, 0));
    // An unknown region count leaves the regions out.
    assertTrue(recovered(pre, sample(3000, 2, 0, 0, 0), -1, 0));
  }

  @Test
  public void testRecoveryNeedsRestrictionQueueAndServersBack() {
    MetricSnapshot pre = sample(1000, 4, 1, 0, 0);
    assertTrue(recovered(pre, sample(2000, 4, 1, 0, 0), 4, 0));
    assertFalse("a daughter still unrestricted", recovered(pre, sample(2000, 4, 2, 0, 0), 4, 0));
    assertFalse("rebuilds still queued", recovered(pre, sample(2000, 4, 0, 1, 0), 4, 0));
    // A stopped server drops out of the sums, so its regions cannot count as unrestricted.
    assertFalse("a server missing", recovered(pre, sample(2000, 4, 0, 0, 1), 4, 0));
    // A server that was missing before the disruption does not hold the recovery.
    assertTrue(recovered(sample(1000, 4, 1, 0, 1), sample(2000, 4, 1, 0, 1), 4, 0));
  }

  @Test
  public void testRecoveryIgnoresSamplesTheJmxCacheMayHold() {
    MetricSnapshot pre = sample(1000, 4, 0, 0, 0);
    long applied = 5000;
    long notBefore = applied + SteadyState.JMX_CACHE_TTL_MS;
    assertFalse(recovered(pre, sample(applied + 2000, 4, 0, 0, 0), 4, notBefore));
    assertTrue(recovered(pre, sample(notBefore, 4, 0, 0, 0), 4, notBefore));
  }
}
