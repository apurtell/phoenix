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
package org.apache.phoenix.vectorharness.corpus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.phoenix.vectorharness.config.Metric;
import org.junit.Test;

public class PublishedCorpusTest {

  /** An in-memory published corpus that counts the calls of the base class. */
  private static final class StubCorpus extends PublishedCorpus {
    final float[][] base;
    final float[][] queries;
    final float[][] heldOut;
    final AtomicInteger queryReads = new AtomicInteger();
    final AtomicInteger digests = new AtomicInteger();

    StubCorpus(long seed, Metric metric, float[][] base, float[][] queries, float[][] heldOut) {
      this(new RandomStreams(seed), metric, base, queries, heldOut);
    }

    private StubCorpus(RandomStreams streams, Metric metric, float[][] base, float[][] queries,
      float[][] heldOut) {
      super(streams, new AttributeModel(streams, 5, 0.0, 10, 10, 4, 0.0, 0), base.length, metric);
      this.base = base;
      this.queries = queries;
      this.heldOut = heldOut;
    }

    @Override
    protected int queryCount() {
      return queries.length;
    }

    @Override
    protected float[] queryVector(int q) {
      queryReads.incrementAndGet();
      return queries[q];
    }

    @Override
    protected long heldOutCount() {
      return heldOut.length;
    }

    @Override
    protected float[] heldOutVector(long i) {
      return heldOut[(int) i];
    }

    @Override
    protected void digestFiles(MessageDigest md) {
      digests.incrementAndGet();
      for (float[] v : base) {
        Digests.update(md, v);
      }
    }

    @Override
    public String description() {
      return "stub";
    }

    @Override
    public String sourceName() {
      return "stub:test";
    }

    @Override
    public int dim() {
      return base[0].length;
    }

    @Override
    public float[] vector(long ordinal) {
      return base[(int) ordinal];
    }

    @Override
    public int[][] publishedNeighbors() {
      return null;
    }

    @Override
    public Map<String, String> parameters() {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("source", sourceName());
      m.put("seed", Long.toString(streams.masterSeed()));
      return m;
    }

    @Override
    public void close() {
    }
  }

  private static float[][] vectors(long seed, int n, int dim, double scale) {
    SplitMix64 rnd = new SplitMix64(seed);
    float[][] out = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        out[i][j] = (float) (scale * rnd.nextGaussian());
      }
    }
    return out;
  }

  private static double norm(float[] v) {
    double s = 0.0;
    for (float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }

  private static StubCorpus stub(Metric metric, int heldOut) {
    return new StubCorpus(5L, metric, vectors(1L, 50, 4, 10.0), vectors(2L, 12, 4, 10.0),
      vectors(3L, heldOut, 4, 10.0));
  }

  @Test
  public void testDefaults() {
    StubCorpus c = stub(Metric.INNER_PRODUCT, 0);
    assertEquals(50L, c.rows());
    assertSame(Metric.INNER_PRODUCT, c.definedMetric());
    assertEquals(-1, c.clusterOf(7));
    assertEquals(c.attributeModel().attributes(9), c.attributes(9));
    assertSame(c.mutations(), c.mutations());
  }

  @Test
  public void testPanelIsReadOnceAndCapped() {
    StubCorpus c = stub(Metric.L2, 0);
    List<PanelQuery> three = c.panel(3);
    assertEquals(3, three.size());
    assertEquals(12, c.queryReads.get());
    List<PanelQuery> all = c.panel(100);
    assertEquals(12, all.size());
    assertEquals(12, c.queryReads.get());
    for (int q = 0; q < 12; q++) {
      PanelQuery p = all.get(q);
      assertEquals(q, p.index());
      assertSame(c.queries[q], p.vector());
      assertSame(PanelQuery.Kind.PUBLISHED, p.kind());
      assertEquals(-1, p.cluster());
      assertTrue(p.tenant() >= 0 && p.tenant() < 5);
    }
    assertSame(all.get(1), three.get(1));
  }

  @Test
  public void testPanelIsBuiltOnceUnderConcurrency() {
    StubCorpus c = stub(Metric.L2, 0);
    IntStream.range(0, 64).parallel().forEach(i -> c.panel(1 + i % 12));
    assertEquals(12, c.queryReads.get());
  }

  @Test
  public void testPanelTenantsAreSpread() {
    float[][] queries = vectors(2L, 500, 4, 1.0);
    StubCorpus c = new StubCorpus(5L, Metric.L2, vectors(1L, 10, 4, 1.0), queries, new float[0][]);
    int[] counts = new int[5];
    for (PanelQuery q : c.panel(500)) {
      counts[q.tenant()]++;
    }
    for (int n : counts) {
      assertEquals(100.0, n, 40.0);
    }
  }

  @Test
  public void testFingerprintIsComputedOnce() {
    StubCorpus c = stub(Metric.L2, 0);
    String f = c.fingerprint();
    assertSame(f, c.fingerprint());
    assertEquals(1, c.digests.get());
    assertEquals(f, stub(Metric.L2, 0).fingerprint());
    assertNotEquals(f, new StubCorpus(6L, Metric.L2, c.base, c.queries, c.heldOut).fingerprint());
  }

  @Test
  public void testPanelDigest() {
    StubCorpus a = stub(Metric.L2, 0);
    StubCorpus b = stub(Metric.L2, 0);
    assertEquals(a.panelDigest(12), b.panelDigest(12));
    assertEquals(a.panelDigest(12), a.panelDigest(50));
    assertNotEquals(a.panelDigest(12), a.panelDigest(11));
  }

  @Test
  public void testHeldOutInsertsAndMoves() {
    StubCorpus c = stub(Metric.L2, 7);
    MutationVectors m = c.mutations();
    for (int j = 0; j < 21; j++) {
      assertSame(c.heldOut[j % 7], m.insert(50 + j, 0.0).vector());
    }
    // An ordinal below rows() maps into the held-out set too
    assertSame(c.heldOut[Math.floorMod(10 - 50, 7)], m.insert(10, 0.0).vector());
    for (long seq = 0; seq < 30; seq++) {
      float[] v = m.move(0, -1, seq, 0.0).vector();
      assertTrue(Arrays.asList(c.heldOut).contains(v));
    }
  }

  @Test
  public void testPerturbedCopies() {
    for (Metric metric : Metric.values()) {
      StubCorpus c = stub(metric, 0);
      MutationVectors m = c.mutations();
      for (long ordinal = 50; ordinal < 80; ordinal++) {
        float[] v = m.insert(ordinal, 0.0).vector();
        assertArrayEquals(v, m.insert(ordinal, 0.0).vector(), 0.0f);
        boolean equalsBase = false;
        for (float[] b : c.base) {
          equalsBase |= Arrays.equals(b, v);
        }
        assertFalse(equalsBase);
        if (metric == Metric.COSINE) {
          assertEquals(1.0, norm(v), 1e-5);
        } else {
          assertTrue(norm(v) > 2.0);
        }
      }
    }
  }

  @Test
  public void testLocalNoiseScalesWithTheVector() {
    StubCorpus c = stub(Metric.L2, 0);
    MutationVectors m = c.mutations();
    float[] small = { 0.001f, -0.001f, 0.001f, -0.001f };
    float[] large = { 1000f, -1000f, 1000f, -1000f };
    double smallShift = 0.0;
    double largeShift = 0.0;
    for (long seq = 0; seq < 200; seq++) {
      smallShift += Metric.L2.distance(small, m.reembedLocal(0, -1, small, seq, 0.0).vector());
      largeShift += Metric.L2.distance(large, m.reembedLocal(0, -1, large, seq, 0.0).vector());
    }
    // The deviation is LOCAL_NOISE times the RMS element: 2e-5 and 20, a ratio of 1e6
    assertEquals(1e6, largeShift / smallShift, 1e6 * 1e-3);
    // A zero vector still moves
    float[] zero = new float[4];
    float[] moved = m.reembedLocal(0, -1, zero, 1, 0.0).vector();
    assertTrue(norm(moved) > 0.0);
    assertTrue(norm(moved) < 5 * PublishedCorpus.LOCAL_NOISE * 2);
  }

  @Test
  public void testMoveProbabilityIgnoresTime() {
    MutationVectors m = stub(Metric.L2, 0).mutations();
    assertEquals(0.3, m.moveProbability(0.3, 0.0), 0.0);
    assertEquals(0.3, m.moveProbability(0.3, 1e6), 0.0);
  }
}
