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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.variant.IvfRegionLayout;
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
      // Probe vector achieves strictly closer inner-product self distance than random or aligned
      // alternatives
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
    // Latency calculation adjusts start timestamp to gate reopen time for queued mutations
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
    // Post-split verification: stale cached sample with 4 regions fails check when 5 expected
    assertFalse("pre-split values", recovered(pre, sample(2000, 4, 0, 0, 0), 5, 0));
    assertTrue(recovered(pre, sample(3000, 5, 0, 0, 0), 5, 0));
    // Post-merge verification: 3 open regions matches expected merged count
    assertTrue(recovered(pre, sample(3000, 3, 0, 0, 0), 3, 0));
    // Restart verification: missing regions prevent recovery confirmation
    assertFalse(recovered(pre, sample(3000, 2, 0, 0, 0), 4, 0));
    // Unknown expected region count (-1) bypasses region count check
    assertTrue(recovered(pre, sample(3000, 2, 0, 0, 0), -1, 0));
  }

  @Test
  public void testRecoveryNeedsRestrictionQueueAndServersBack() {
    MetricSnapshot pre = sample(1000, 4, 1, 0, 0);
    assertTrue(recovered(pre, sample(2000, 4, 1, 0, 0), 4, 0));
    assertFalse("a daughter still unrestricted", recovered(pre, sample(2000, 4, 2, 0, 0), 4, 0));
    assertFalse("rebuilds still queued", recovered(pre, sample(2000, 4, 0, 1, 0), 4, 0));
    // Missing server prevents recovery confirmation
    assertFalse("a server missing", recovered(pre, sample(2000, 4, 0, 0, 1), 4, 0));
    // Servers missing prior to disruption do not block recovery
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

  private static IvfRegionLayout.Plan plan(String... kv) {
    Properties p = new Properties();
    p.setProperty("ivf.lists", "8");
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return IvfRegionLayout.plan(HarnessConfig.of("t", p));
  }

  @Test
  public void testRelayoutsOnlyForSplitLayouts() {
    assertTrue(SteadyState.relayouts(plan("ivf.regions", "2")));
    assertTrue(SteadyState.relayouts(plan("ivf.regions", "2", "table.multi_tenant", "true")));
    assertFalse(SteadyState.relayouts(plan("ivf.regions", "1")));
    assertFalse(SteadyState.relayouts(plan("ivf.salt_buckets", "4", "ivf.regions", "1")));
    assertFalse(SteadyState.relayouts(plan("table.salt_buckets", "4")));
  }

  @Test
  public void testCompleteLayoutNeedsEverySplit() {
    assertTrue(SteadyState.complete(null));
    assertTrue(SteadyState.complete(map("index_regions", 4)));
    assertTrue(SteadyState.complete(map("midkey", map("outcome", "done"))));
    assertTrue(SteadyState.complete(map("top_up", map("failed", new ArrayList<>()))));
    assertFalse(SteadyState.complete(map("error", "java.io.IOException")));
    assertFalse(SteadyState.complete(map("interrupted", true)));
    assertFalse(SteadyState.complete(map("midkey", map("outcome", "no splittable region"))));
    assertFalse(SteadyState.complete(map("top_up", map("failed", Arrays.asList(12)))));
    Map<String, Object> ended = map("midkey", map("outcome", "window ended"));
    assertFalse(SteadyState.complete(ended));
    assertTrue(SteadyState.windowEnded(ended));
    assertFalse(SteadyState.windowEnded(map("midkey", map("outcome", "timed out"))));
    assertFalse(SteadyState.windowEnded(null));
  }

  @Test
  public void testRestartWaitsForTheLayoutLockOnlyUntilTheWindowEnd() throws Exception {
    ReentrantLock lock = new ReentrantLock();
    StageEnd end = new StageEnd(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread relayout = new Thread(() -> {
      lock.lock();
      try {
        held.countDown();
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    });
    relayout.start();
    held.await();
    try {
      // A relayout holds the lock past the window end, so the restart does not start.
      assertEquals(-1, SteadyState.lockUntilWindowEnd(lock, end));
      assertFalse(lock.isHeldByCurrentThread());
    } finally {
      release.countDown();
      relayout.join(10_000);
    }
    StageEnd open = new StageEnd(System.nanoTime() + TimeUnit.MINUTES.toNanos(10));
    assertTrue(SteadyState.lockUntilWindowEnd(lock, open) >= 0);
    assertTrue(lock.isHeldByCurrentThread());
    lock.unlock();
  }

  @Test
  public void testStatisticsAfterExplicitRebuildWaitForTheLayout() {
    // Without a relayout, the regions are final at the promotion.
    assertTrue(SteadyState.statisticsAfterExplicitRebuild(false, 1L, 2L, true));
    // A complete layout gives the final regions.
    assertTrue(SteadyState.statisticsAfterExplicitRebuild(true, 2L, 2L, true));
    // An incomplete layout leaves the update to the generation loop.
    assertFalse(SteadyState.statisticsAfterExplicitRebuild(true, 1L, 2L, true));
    assertFalse(SteadyState.statisticsAfterExplicitRebuild(true, null, 2L, true));
    // Without the generation loop, no later layout comes.
    assertTrue(SteadyState.statisticsAfterExplicitRebuild(true, 1L, 2L, false));
  }

  private static Map<String, Object> map(String key, Object value) {
    Map<String, Object> m = new HashMap<>();
    m.put(key, value);
    return m;
  }
}
