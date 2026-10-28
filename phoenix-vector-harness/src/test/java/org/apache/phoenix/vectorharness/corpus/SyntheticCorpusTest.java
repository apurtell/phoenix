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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.apache.phoenix.vectorharness.config.HarnessConfig.DriftMode;
import org.apache.phoenix.vectorharness.config.Metric;
import org.junit.Test;

public class SyntheticCorpusTest {

  /** The parameters of a small synthetic corpus, with setters for the ones a test varies. */
  private static final class Spec {
    long seed = 7L;
    long rows = 2000L;
    int dim = 16;
    int clusters = 8;
    double zipf = 0.0;
    double spread = 1.0;
    /** The intrinsic dimension, or -1 for the ambient dimension. */
    int intrinsic = -1;
    double noise = 0.05;
    boolean normalize = false;
    double boundary = 0.0;
    double ood = 0.0;
    DriftMode drift = DriftMode.NONE;
    double rate = 0.0;
    Metric metric = Metric.L2;
    int tenants = 4;
    int categories = 100;

    Spec seed(long v) {
      seed = v;
      return this;
    }

    Spec rows(long v) {
      rows = v;
      return this;
    }

    Spec dim(int v) {
      dim = v;
      return this;
    }

    Spec clusters(int v) {
      clusters = v;
      return this;
    }

    Spec zipf(double v) {
      zipf = v;
      return this;
    }

    Spec spread(double v) {
      spread = v;
      return this;
    }

    Spec intrinsic(int v) {
      intrinsic = v;
      return this;
    }

    Spec noise(double v) {
      noise = v;
      return this;
    }

    Spec normalize(boolean v) {
      normalize = v;
      return this;
    }

    Spec boundary(double v) {
      boundary = v;
      return this;
    }

    Spec ood(double v) {
      ood = v;
      return this;
    }

    Spec drift(DriftMode mode, double r) {
      drift = mode;
      rate = r;
      return this;
    }

    Spec tenants(int v) {
      tenants = v;
      return this;
    }

    Spec categories(int v) {
      categories = v;
      return this;
    }

    SyntheticCorpus build() {
      RandomStreams streams = new RandomStreams(seed);
      AttributeModel attrs = new AttributeModel(streams, tenants, 0.0, 50, categories, 8, 0.0, 16);
      return new SyntheticCorpus(seed, rows, dim, clusters, zipf, spread,
        intrinsic < 0 ? dim : intrinsic, noise, normalize, boundary, ood, drift, rate, metric,
        attrs);
    }
  }

  private static Spec spec() {
    return new Spec();
  }

  private static double norm(float[] v) {
    double s = 0.0;
    for (float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }

  private static double dist2(float[] a, float[] b) {
    double s = 0.0;
    for (int i = 0; i < a.length; i++) {
      double d = (double) a[i] - b[i];
      s += d * d;
    }
    return s;
  }

  /** Returns the component whose center is nearest, among components [0, n). */
  private static int nearestCenter(SyntheticCorpus c, float[] v, int n) {
    int best = -1;
    double bestD = Double.MAX_VALUE;
    for (int k = 0; k < n; k++) {
      double d = dist2(v, c.component(k).center);
      if (d < bestD) {
        bestD = d;
        best = k;
      }
    }
    return best;
  }

  private static double nearestCenterDistance(SyntheticCorpus c, float[] v, int n) {
    return Math.sqrt(dist2(v, c.component(nearestCenter(c, v, n)).center));
  }

  // Construction and metadata

  @Test
  public void testConstructorValidation() {
    Spec[] bad = { spec().rows(0), spec().dim(0), spec().clusters(0), spec().intrinsic(0),
      spec().intrinsic(17) };
    for (Spec s : bad) {
      try {
        s.build();
        fail("Expected an exception");
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
    try {
      // 1000 * 2 * 100 * 1000 floats exceed the basis budget
      spec().dim(1000).intrinsic(100).clusters(1000).build();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("corpus.clusters"));
    }
    // An isotropic corpus needs no bases, so the budget does not apply
    assertEquals(1000, spec().dim(1000).intrinsic(1000).clusters(1000).rows(10).build().dim());
  }

  @Test
  public void testMetadata() {
    SyntheticCorpus c = spec().build();
    assertEquals("synthetic:gmm", c.sourceName());
    assertEquals("synthetic:gmm rows=2000 dim=16 clusters=8 intrinsic=16", c.description());
    assertEquals(16, c.dim());
    assertEquals(2000L, c.rows());
    assertNull(c.definedMetric());
    assertSame(Metric.L2, c.metric());
    assertFalse(c.normalized());
    assertNull(c.publishedNeighbors());
    assertSame(c.mutations(), c.mutations());
    assertEquals(c.attributeModel().attributes(5), c.attributes(5));
    c.close();
  }

  @Test
  public void testParameters() {
    SyntheticCorpus c = spec().zipf(1.1).spread(2.0).intrinsic(4).normalize(true).boundary(0.1)
      .ood(0.2).drift(DriftMode.REASSIGN, 0.01).build();
    Map<String, String> p = c.parameters();
    assertEquals("synthetic:gmm", p.get("source"));
    assertEquals("1", p.get("generator.version"));
    assertEquals("7", p.get("seed"));
    assertEquals("2000", p.get("rows"));
    assertEquals("16", p.get("dim"));
    assertEquals("8", p.get("clusters"));
    assertEquals("1.1", p.get("cluster.zipf"));
    assertEquals("2.0", p.get("variance.spread"));
    assertEquals("4", p.get("intrinsic_dim"));
    assertEquals("0.05", p.get("noise"));
    assertEquals("true", p.get("normalize"));
    assertEquals("0.1", p.get("query.boundary_fraction"));
    assertEquals("0.2", p.get("query.ood_fraction"));
    assertEquals("REASSIGN", p.get("drift.mode"));
    assertEquals("0.01", p.get("drift.rate"));
    assertEquals("100", p.get("attr.categories"));
    assertEquals("4", p.get("attr.tenants"));
  }

  // Base vectors

  @Test
  public void testDeterminismAcrossInstances() {
    SyntheticCorpus a = spec().intrinsic(4).build();
    SyntheticCorpus b = spec().intrinsic(4).build();
    for (long i = 0; i < 300; i++) {
      assertArrayEquals(a.vector(i), b.vector(i), 0.0f);
      assertEquals(a.clusterOf(i), b.clusterOf(i));
    }
  }

  @Test
  public void testDifferentSeedsDiffer() {
    SyntheticCorpus a = spec().build();
    SyntheticCorpus b = spec().seed(8L).build();
    for (long i = 0; i < 100; i++) {
      assertFalse(Arrays.equals(a.vector(i), b.vector(i)));
    }
    assertFalse(Arrays.equals(a.component(0).center, b.component(0).center));
  }

  @Test
  public void testVectorsAreIndependentOfOrderAndThread() {
    int n = 500;
    SyntheticCorpus a = spec().intrinsic(3).build();
    float[][] forward = new float[n][];
    for (int i = 0; i < n; i++) {
      forward[i] = a.vector(i);
    }
    SyntheticCorpus b = spec().intrinsic(3).build();
    for (int i = n - 1; i >= 0; i--) {
      assertArrayEquals(forward[i], b.vector(i), 0.0f);
    }
    // A fresh instance generates its components lazily from several threads at once
    SyntheticCorpus c = spec().intrinsic(3).build();
    float[][] parallel = new float[n][];
    IntStream.range(0, n).parallel().forEach(i -> parallel[i] = c.vector(n - 1 - i));
    for (int i = 0; i < n; i++) {
      assertArrayEquals(forward[n - 1 - i], parallel[i], 0.0f);
    }
    assertArrayEquals(forward[321], spec().intrinsic(3).build().vector(321), 0.0f);
  }

  @Test
  public void testVectorsAreFreshCopies() {
    SyntheticCorpus c = spec().build();
    float[] v = c.vector(3);
    float[] copy = v.clone();
    Arrays.fill(v, 0.0f);
    assertArrayEquals(copy, c.vector(3), 0.0f);
  }

  @Test
  public void testOrdinalBounds() {
    SyntheticCorpus c = spec().build();
    for (long bad : new long[] { -1L, 2000L, Long.MAX_VALUE }) {
      try {
        c.vector(bad);
        fail("Expected an exception for " + bad);
      } catch (IndexOutOfBoundsException e) {
        // expected
      }
      try {
        c.clusterOf(bad);
        fail("Expected an exception for " + bad);
      } catch (IndexOutOfBoundsException e) {
        // expected
      }
    }
    c.vector(0);
    c.vector(1999);
  }

  @Test
  public void testDimensionAndNormalization() {
    for (int intrinsic : new int[] { 3, 24 }) {
      SyntheticCorpus unit = spec().dim(24).intrinsic(intrinsic).normalize(true).build();
      assertTrue(unit.normalized());
      SyntheticCorpus raw = spec().dim(24).intrinsic(intrinsic).build();
      for (long i = 0; i < 200; i++) {
        float[] v = unit.vector(i);
        assertEquals(24, v.length);
        assertEquals(1.0, norm(v), 1e-5);
        float[] r = raw.vector(i);
        assertEquals(24, r.length);
        // Unnormalized points lie about sqrt(D) from the origin
        assertTrue(norm(r) > 2.0);
        // Normalization is a projection of the same point
        double nr = norm(r);
        for (int j = 0; j < 24; j++) {
          assertEquals(r[j] / nr, v[j], 1e-5);
        }
      }
    }
  }

  @Test
  public void testClusterOfIsConsistentWithMixture() {
    for (int intrinsic : new int[] { 32, 4 }) {
      SyntheticCorpus c = spec().dim(32).intrinsic(intrinsic).build();
      for (long i = 0; i < 500; i++) {
        int k = c.clusterOf(i);
        assertTrue(k >= 0 && k < 8);
        assertEquals("row " + i, k, nearestCenter(c, c.vector(i), 8));
      }
    }
  }

  @Test
  public void testBasisIsOrthonormal() {
    SyntheticCorpus c = spec().dim(32).intrinsic(5).build();
    for (int k = 0; k < 8; k++) {
      float[][] b = c.component(k).basis;
      assertEquals(5, b.length);
      for (int i = 0; i < 5; i++) {
        assertEquals(32, b[i].length);
        for (int j = 0; j < 5; j++) {
          double dot = 0.0;
          for (int d = 0; d < 32; d++) {
            dot += (double) b[i][d] * b[j][d];
          }
          assertEquals(i == j ? 1.0 : 0.0, dot, 1e-5);
        }
      }
    }
    assertNull(spec().build().component(0).basis);
  }

  @Test
  public void testNoiseFreeClusterLiesInAffineSubspaceOfIntrinsicRank() {
    SyntheticCorpus c = spec().dim(16).intrinsic(2).noise(0.0).clusters(4).rows(400).build();
    for (int k = 0; k < 4; k++) {
      assertEquals(2, affineRank(clusterPoints(c, k)));
    }
    // With ambient noise the points leave the subspace
    SyntheticCorpus noisy = spec().dim(16).intrinsic(2).noise(0.05).clusters(4).rows(400).build();
    assertEquals(16, affineRank(clusterPoints(noisy, 0)));
  }

  private static List<float[]> clusterPoints(SyntheticCorpus c, int k) {
    List<float[]> out = new ArrayList<>();
    for (long i = 0; i < c.rows(); i++) {
      if (c.clusterOf(i) == k) {
        out.add(c.vector(i));
      }
    }
    assertTrue(out.size() > 20);
    return out;
  }

  /** Returns the rank of the differences from the first point, by Gram-Schmidt. */
  private static int affineRank(List<float[]> points) {
    float[] origin = points.get(0);
    int dim = origin.length;
    List<double[]> basis = new ArrayList<>();
    for (int p = 1; p < points.size(); p++) {
      double[] d = new double[dim];
      double dn = 0.0;
      for (int i = 0; i < dim; i++) {
        d[i] = (double) points.get(p)[i] - origin[i];
        dn += d[i] * d[i];
      }
      dn = Math.sqrt(dn);
      for (int pass = 0; pass < 2; pass++) {
        for (double[] q : basis) {
          double dot = 0.0;
          for (int i = 0; i < dim; i++) {
            dot += d[i] * q[i];
          }
          for (int i = 0; i < dim; i++) {
            d[i] -= dot * q[i];
          }
        }
      }
      double rn = 0.0;
      for (double x : d) {
        rn += x * x;
      }
      rn = Math.sqrt(rn);
      if (rn > 1e-3 * dn) {
        for (int i = 0; i < dim; i++) {
          d[i] /= rn;
        }
        basis.add(d);
      }
    }
    return basis.size();
  }

  @Test
  public void testNoisyClusterKeepsMostEnergyInItsSubspace() {
    SyntheticCorpus c = spec().dim(16).intrinsic(2).noise(0.05).build();
    for (int k = 0; k < 8; k++) {
      SyntheticCorpus.Component comp = c.component(k);
      double inSubspace = 0.0;
      double total = 0.0;
      for (float[] x : clusterPoints(c, k)) {
        double[] y = new double[16];
        for (int i = 0; i < 16; i++) {
          y[i] = (double) x[i] - comp.center[i];
          total += y[i] * y[i];
        }
        for (float[] b : comp.basis) {
          double dot = 0.0;
          for (int i = 0; i < 16; i++) {
            dot += y[i] * b[i];
          }
          inSubspace += dot * dot;
        }
      }
      // Subspace energy 2 sigma^2 against noise energy 16 (0.05 sigma)^2: a ratio of about 0.98
      double ratio = inSubspace / total;
      assertTrue("cluster " + k + " ratio " + ratio, ratio > 0.95 && ratio <= 1.0);
    }
  }

  @Test
  public void testVarianceSpread() {
    double spread = 4.0;
    SyntheticCorpus c = spec().spread(spread).rows(4000).build();
    double minSigma = Double.MAX_VALUE;
    double maxSigma = 0.0;
    Set<Double> sigmas = new TreeSet<>();
    for (int k = 0; k < 8; k++) {
      double s = c.component(k).sigma;
      minSigma = Math.min(minSigma, s);
      maxSigma = Math.max(maxSigma, s);
      sigmas.add(s);
    }
    assertEquals(8, sigmas.size());
    assertEquals(spread, maxSigma / minSigma, 1e-9);
    // The tightest component has radius 0.35 sqrt(D)
    assertEquals(SyntheticCorpus.TIGHTEST_RADIUS * Math.sqrt(16), minSigma * Math.sqrt(16), 1e-9);
    // Deviations are spread geometrically
    Double[] sorted = sigmas.toArray(new Double[0]);
    for (int i = 1; i < sorted.length; i++) {
      assertEquals(Math.pow(spread, 1.0 / 7), sorted[i] / sorted[i - 1], 1e-9);
    }
    // Empirical mean squared radii follow sigma^2 D, so their ratio is about spread^2
    double[] sum = new double[8];
    int[] count = new int[8];
    for (long i = 0; i < c.rows(); i++) {
      int k = c.clusterOf(i);
      sum[k] += dist2(c.vector(i), c.component(k).center);
      count[k]++;
    }
    double minR = Double.MAX_VALUE;
    double maxR = 0.0;
    for (int k = 0; k < 8; k++) {
      double msr = sum[k] / count[k];
      double expected = c.component(k).sigma * c.component(k).sigma * 16;
      assertEquals("cluster " + k, expected, msr, expected * 0.1);
      minR = Math.min(minR, msr);
      maxR = Math.max(maxR, msr);
    }
    assertEquals(spread * spread, maxR / minR, spread * spread * 0.12);
  }

  @Test
  public void testNoSpreadMeansEqualDeviations() {
    SyntheticCorpus c = spec().build();
    for (int k = 1; k < 8; k++) {
      assertEquals(c.component(0).sigma, c.component(k).sigma, 0.0);
    }
    SyntheticCorpus one = spec().clusters(1).spread(5.0).build();
    assertEquals(SyntheticCorpus.TIGHTEST_RADIUS, one.component(0).sigma, 1e-12);
  }

  @Test
  public void testUniformClusterWeights() {
    SyntheticCorpus c = spec().rows(16000).build();
    int[] counts = new int[8];
    for (long i = 0; i < c.rows(); i++) {
      counts[c.clusterOf(i)]++;
    }
    for (int n : counts) {
      assertEquals(2000.0, n, 200.0);
    }
  }

  @Test
  public void testZipfClusterWeights() {
    SyntheticCorpus c = spec().zipf(1.0).rows(20000).build();
    int[] counts = new int[8];
    for (long i = 0; i < c.rows(); i++) {
      counts[c.clusterOf(i)]++;
    }
    Vocabulary weights = new Vocabulary("k", 8, 1.0);
    for (int k = 0; k < 8; k++) {
      assertEquals("cluster " + k, weights.probability(k), counts[k] / 20000.0, 0.015);
      assertTrue(counts[0] >= counts[k]);
    }
    for (int k = 0; k < 4; k++) {
      assertTrue(counts[k] > counts[k + 1]);
    }
    assertEquals(1.0 / 2.717857, counts[0] / 20000.0, 0.015);
  }

  // Panel

  @Test
  public void testPanelKindFractions() {
    SyntheticCorpus c = spec().boundary(0.2).ood(0.1).build();
    List<PanelQuery> panel = c.panel(3000);
    assertEquals(3000, panel.size());
    int boundary = 0;
    int ood = 0;
    int in = 0;
    for (int i = 0; i < panel.size(); i++) {
      PanelQuery q = panel.get(i);
      assertEquals(i, q.index());
      assertEquals(16, q.vector().length);
      assertTrue(q.tenant() >= 0 && q.tenant() < 4);
      switch (q.kind()) {
        case BOUNDARY:
          boundary++;
          assertEquals(-1, q.cluster());
          break;
        case OUT_OF_DISTRIBUTION:
          ood++;
          assertEquals(-1, q.cluster());
          break;
        case IN_DISTRIBUTION:
          in++;
          assertTrue(q.cluster() >= 0 && q.cluster() < 8);
          break;
        default:
          fail("Unexpected kind " + q.kind());
      }
    }
    assertEquals(0.2, boundary / 3000.0, 0.025);
    assertEquals(0.1, ood / 3000.0, 0.02);
    assertEquals(0.7, in / 3000.0, 0.03);
  }

  @Test
  public void testDefaultPanelIsInDistribution() {
    for (PanelQuery q : spec().build().panel(500)) {
      assertSame(PanelQuery.Kind.IN_DISTRIBUTION, q.kind());
    }
  }

  @Test
  public void testPanelKindGeometry() {
    SyntheticCorpus c = spec().dim(64).boundary(0.3).ood(0.3).build();
    double[] sum = new double[3];
    int[] n = new int[3];
    for (PanelQuery q : c.panel(600)) {
      int kind = q.kind().ordinal();
      sum[kind] += nearestCenterDistance(c, q.vector(), 8);
      n[kind]++;
      if (q.kind() == PanelQuery.Kind.IN_DISTRIBUTION) {
        assertEquals(q.cluster(), nearestCenter(c, q.vector(), 8));
      }
    }
    double in =
      sum[PanelQuery.Kind.IN_DISTRIBUTION.ordinal()] / n[PanelQuery.Kind.IN_DISTRIBUTION.ordinal()];
    double boundary =
      sum[PanelQuery.Kind.BOUNDARY.ordinal()] / n[PanelQuery.Kind.BOUNDARY.ordinal()];
    double ood = sum[PanelQuery.Kind.OUT_OF_DISTRIBUTION.ordinal()]
      / n[PanelQuery.Kind.OUT_OF_DISTRIBUTION.ordinal()];
    // In-distribution queries lie about 0.35 sqrt(D) from their center, boundary queries a third
    // or more of the way to another center, and out-of-distribution queries about sqrt(2D) away.
    assertEquals(SyntheticCorpus.TIGHTEST_RADIUS * 8, in, 0.3);
    assertTrue(in + " " + boundary, boundary > 1.3 * in);
    assertTrue(boundary + " " + ood, ood > 1.5 * boundary);
  }

  @Test
  public void testSingleClusterHasNoBoundaryQueries() {
    // Boundary queries need two clusters; with one, they must not turn into out-of-distribution
    // queries.
    SyntheticCorpus c = spec().clusters(1).boundary(0.5).build();
    for (PanelQuery q : c.panel(400)) {
      assertSame(PanelQuery.Kind.IN_DISTRIBUTION, q.kind());
      assertEquals(0, q.cluster());
    }
    SyntheticCorpus withOod = spec().clusters(1).boundary(0.5).ood(0.2).build();
    int ood = 0;
    for (PanelQuery q : withOod.panel(2000)) {
      assertNotEquals(PanelQuery.Kind.BOUNDARY, q.kind());
      if (q.kind() == PanelQuery.Kind.OUT_OF_DISTRIBUTION) {
        ood++;
      }
    }
    assertEquals(0.2, ood / 2000.0, 0.03);
  }

  @Test
  public void testPanelDeterminismAndCaching() {
    SyntheticCorpus a = spec().boundary(0.2).ood(0.2).build();
    SyntheticCorpus b = spec().boundary(0.2).ood(0.2).build();
    List<PanelQuery> small = a.panel(50);
    assertEquals(50, small.size());
    List<PanelQuery> large = a.panel(200);
    List<PanelQuery> direct = b.panel(200);
    assertEquals(200, large.size());
    for (int i = 0; i < 200; i++) {
      PanelQuery x = large.get(i);
      PanelQuery y = direct.get(i);
      assertArrayEquals(x.vector(), y.vector(), 0.0f);
      assertSame(x.kind(), y.kind());
      assertEquals(x.tenant(), y.tenant());
      assertEquals(x.cluster(), y.cluster());
      if (i < 50) {
        assertSame(small.get(i), x);
      }
    }
    assertSame(large.get(10), a.panel(20).get(10));
    assertTrue(a.panel(0).isEmpty());
    try {
      a.panel(10).add(large.get(0));
      fail("Expected an exception");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testPanelIsHeldOut() {
    SyntheticCorpus c = spec().build();
    Set<String> base = new HashSet<>();
    for (long i = 0; i < c.rows(); i++) {
      base.add(Arrays.toString(c.vector(i)));
    }
    for (PanelQuery q : c.panel(300)) {
      assertFalse(base.contains(Arrays.toString(q.vector())));
    }
  }

  @Test
  public void testPanelNormalization() {
    SyntheticCorpus c = spec().normalize(true).boundary(0.3).ood(0.3).build();
    for (PanelQuery q : c.panel(300)) {
      assertEquals(q.kind().toString(), 1.0, norm(q.vector()), 1e-5);
    }
  }

  @Test
  public void testPanelDigest() {
    SyntheticCorpus a = spec().boundary(0.2).build();
    String d = a.panelDigest(100);
    assertEquals(64, d.length());
    assertTrue(d.matches("[0-9a-f]{64}"));
    assertEquals(d, a.panelDigest(100));
    assertEquals(d, spec().boundary(0.2).build().panelDigest(100));
    assertNotEquals(d, a.panelDigest(99));
    assertNotEquals(d, spec().seed(8L).boundary(0.2).build().panelDigest(100));
    assertNotEquals(d, spec().boundary(0.3).build().panelDigest(100));
    // The digest covers the tenant targets too
    assertNotEquals(d, spec().boundary(0.2).tenants(7).build().panelDigest(100));
  }

  // Fingerprint

  @Test
  public void testFingerprintIsStable() {
    SyntheticCorpus a = spec().build();
    String f = a.fingerprint();
    assertTrue(f.matches("[0-9a-f]{64}"));
    assertSame(f, a.fingerprint());
    assertEquals(f, spec().build().fingerprint());
  }

  @Test
  public void testFingerprintChangesWithEveryParameter() {
    Spec[] variants = { spec().seed(8L), spec().rows(2001), spec().dim(17), spec().clusters(9),
      spec().zipf(0.5), spec().spread(2.0), spec().intrinsic(8), spec().noise(0.1),
      spec().normalize(true), spec().categories(101), spec().tenants(5) };
    Set<String> seen = new HashSet<>();
    seen.add(spec().build().fingerprint());
    for (Spec s : variants) {
      assertTrue(s.build().parameters().toString(), seen.add(s.build().fingerprint()));
    }
  }

  @Test
  public void testFingerprintIgnoresPanelAndDriftParameters() {
    // They shape the queries and the mutations of a run but no loaded row, so a table loaded
    // under one value serves runs under another. The manifest still records them.
    SyntheticCorpus base = spec().build();
    Spec[] variants = { spec().boundary(0.1), spec().ood(0.1), spec().drift(DriftMode.MIGRATE, 0.2),
      spec().drift(DriftMode.BIRTH_DEATH, 0.1), spec().drift(DriftMode.REASSIGN, 0.05) };
    for (Spec s : variants) {
      SyntheticCorpus c = s.build();
      assertNotEquals(base.parameters(), c.parameters());
      assertEquals(c.parameters().toString(), base.fingerprint(), c.fingerprint());
      for (long i = 0; i < 50; i++) {
        assertArrayEquals(base.vector(i), c.vector(i), 0.0f);
      }
    }
  }

  @Test
  public void testFingerprintOfLargeCorpusSamplesRows() {
    long rows = 3L * SyntheticCorpus.FINGERPRINT_SAMPLE_ROWS + 1;
    SyntheticCorpus c = spec().dim(2).clusters(2).rows(rows).build();
    String f = c.fingerprint();
    assertEquals(f, spec().dim(2).clusters(2).rows(rows).build().fingerprint());
    assertNotEquals(f, spec().dim(2).clusters(2).rows(rows + 1).build().fingerprint());
  }

  // Mutations

  @Test
  public void testInsertIsDeterministic() {
    SyntheticCorpus a = spec().dim(32).build();
    SyntheticCorpus b = spec().dim(32).build();
    Set<String> distinct = new HashSet<>();
    for (long i = 0; i < 200; i++) {
      Embedding x = a.mutations().insert(2000 + i, 0.0);
      Embedding y = b.mutations().insert(2000 + i, 0.0);
      assertArrayEquals(x.vector(), y.vector(), 0.0f);
      assertEquals(x.cluster(), y.cluster());
      assertEquals(32, x.vector().length);
      assertTrue(x.cluster() >= 0 && x.cluster() < 8);
      assertEquals(x.cluster(), nearestCenter(a, x.vector(), 8));
      distinct.add(Arrays.toString(x.vector()));
    }
    assertEquals(200, distinct.size());
    assertEquals(1.0, norm(spec().normalize(true).build().mutations().insert(5000, 0.0).vector()),
      1e-5);
  }

  @Test
  public void testReembedLocalStaysInCluster() {
    SyntheticCorpus c = spec().dim(32).build();
    MutationVectors m = c.mutations();
    double radius = SyntheticCorpus.TIGHTEST_RADIUS * Math.sqrt(32);
    for (int k = 0; k < 8; k++) {
      float[] current = c.component(k).point(new SplitMix64(100 + k), 0.0);
      double moved = 0.0;
      for (long seq = 0; seq < 20; seq++) {
        Embedding e = m.reembedLocal(10, k, current, seq, 0.0);
        assertEquals(k, e.cluster());
        assertEquals(k, nearestCenter(c, e.vector(), 8));
        assertArrayEquals(e.vector(),
          spec().dim(32).build().mutations().reembedLocal(10, k, current, seq, 0.0).vector(), 0.0f);
        moved += Math.sqrt(dist2(e.vector(), current));
      }
      // A local re-embedding perturbs the row, a tenth of the radius, rather than redrawing it
      // anywhere in its cluster, which would move it about 1.4 radii.
      double mean = moved / 20;
      assertTrue("mean step " + mean, mean > 0.02 * radius && mean < 0.25 * radius);
    }
    float[] current = c.component(3).point(new SplitMix64(7), 0.0);
    assertFalse(Arrays.equals(m.reembedLocal(10, 3, current, 1, 0.0).vector(),
      m.reembedLocal(10, 3, current, 2, 0.0).vector()));
    // A row of unknown cluster gets a fresh point of some cluster.
    Embedding any = m.reembedLocal(10, -1, current, 5, 0.0);
    assertTrue(any.cluster() >= 0 && any.cluster() < 8);
    assertEquals(any.cluster(), nearestCenter(c, any.vector(), 8));
  }

  @Test
  public void testRepeatedLocalReembeddingsKeepTheComponentSpread() {
    for (boolean normalize : new boolean[] { false, true }) {
      SyntheticCorpus c = spec().dim(64).intrinsic(16).spread(3.0).normalize(normalize).build();
      MutationVectors m = c.mutations();
      for (int k = 0; k < 8; k++) {
        SyntheticCorpus.Component comp = c.component(k);
        // The spread of fresh points about their mean.
        int n = 2000;
        float[][] fresh = new float[n][];
        double[] mean = new double[64];
        SplitMix64 rnd = new SplitMix64(11);
        for (int i = 0; i < n; i++) {
          fresh[i] = comp.point(rnd, 0.0);
          for (int j = 0; j < 64; j++) {
            mean[j] += fresh[i][j] / n;
          }
        }
        float[] center = new float[64];
        for (int j = 0; j < 64; j++) {
          center[j] = (float) mean[j];
        }
        double spread = 0.0;
        for (float[] f : fresh) {
          spread += Math.sqrt(dist2(f, center)) / n;
        }
        // A long chain of re-embeddings neither collapses nor spreads the component.
        float[] x = fresh[0];
        double chain = 0.0;
        int steps = 4000;
        for (int s = 0; s < steps; s++) {
          Embedding e = m.reembedLocal(1, k, x, s, 0.0);
          assertEquals(k, e.cluster());
          x = e.vector();
          if (normalize) {
            assertEquals(1.0, norm(x), 1e-5);
          }
          if (s >= steps / 2) {
            chain += Math.sqrt(dist2(x, center)) / (steps / 2);
          }
        }
        String msg =
          "normalize=" + normalize + " component " + k + ": chain " + chain + ", fresh " + spread;
        assertTrue(msg, chain > 0.85 * spread && chain < 1.15 * spread);
      }
    }
  }

  @Test
  public void testLocalReembeddingFollowsMigrationGradually() {
    double rate = 0.2;
    SyntheticCorpus c = spec().dim(32).drift(DriftMode.MIGRATE, rate).build();
    SyntheticCorpus.Component comp = c.component(2);
    double shift = rate * 10.0 * SyntheticCorpus.TIGHTEST_RADIUS * Math.sqrt(32);
    float[] x = comp.point(new SplitMix64(3), 0.0);
    // The offset of the row along the drift direction, from the original center.
    float[] first = c.mutations().reembedLocal(1, 2, x, 0, 10.0).vector();
    assertTrue(Math.abs(along(first, comp)) < 0.25 * shift);
    double sum = 0.0;
    int steps = 3000;
    for (int s = 0; s < steps; s++) {
      x = c.mutations().reembedLocal(1, 2, x, s, 10.0).vector();
      if (s >= steps - 1000) {
        sum += along(x, comp) / 1000;
      }
    }
    assertTrue("offset " + sum + " of shift " + shift, sum > 0.8 * shift && sum < 1.2 * shift);
  }

  private static double along(float[] v, SyntheticCorpus.Component comp) {
    double s = 0.0;
    for (int i = 0; i < v.length; i++) {
      s += ((double) v[i] - comp.center[i]) * comp.driftDir[i];
    }
    return s;
  }

  @Test
  public void testMovePicksDifferentCluster() {
    SyntheticCorpus c = spec().dim(32).zipf(2.0).build();
    MutationVectors m = c.mutations();
    for (int k = 0; k < 8; k++) {
      Set<Integer> targets = new HashSet<>();
      for (long seq = 0; seq < 100; seq++) {
        Embedding e = m.move(1, k, seq, 0.0);
        assertNotEquals(k, e.cluster());
        assertTrue(e.cluster() >= 0 && e.cluster() < 8);
        assertEquals(e.cluster(), nearestCenter(c, e.vector(), 8));
        Embedding again = spec().dim(32).zipf(2.0).build().mutations().move(1, k, seq, 0.0);
        assertEquals(e.cluster(), again.cluster());
        assertArrayEquals(e.vector(), again.vector(), 0.0f);
        targets.add(e.cluster());
      }
      assertTrue(targets.size() > 2);
    }
    // With one cluster there is nowhere else to go
    SyntheticCorpus one = spec().clusters(1).build();
    assertEquals(0, one.mutations().move(1, 0, 3, 0.0).cluster());
  }

  @Test
  public void testMoveOutOfADominantComponentAvoidsRetiredOnes() {
    // Under strong skew most draws return the heaviest component, so moves out of it fall back
    // to the active component of the next slot.
    SyntheticCorpus c = spec().zipf(6.0).drift(DriftMode.BIRTH_DEATH, 0.05).build();
    double minutes = 10.0;
    int k = c.turnover(minutes);
    assertEquals(4, k);
    Set<Integer> active = new HashSet<>();
    for (int slot = 0; slot < 8; slot++) {
      int r = c.retirementRank(slot);
      active.add(r < k ? 8 + r : slot);
    }
    assertEquals(8, active.size());
    for (int from : active) {
      for (long seq = 0; seq < 300; seq++) {
        Embedding e = c.mutations().move(1, from, seq, minutes);
        assertNotEquals(from, e.cluster());
        assertTrue("moved from " + from + " into retired " + e.cluster(),
          active.contains(e.cluster()));
      }
    }
  }

  @Test
  public void testRetirementRanksOfMoreThanAMillionComponents() {
    int clusters = (1 << 20) + 4096;
    SyntheticCorpus c = spec().dim(2).clusters(clusters).build();
    boolean[] seen = new boolean[clusters];
    for (int slot = 0; slot < clusters; slot++) {
      int r = c.retirementRank(slot);
      assertFalse("rank " + r + " repeats", seen[r]);
      seen[r] = true;
    }
  }

  @Test
  public void testMoveProbability() {
    MutationVectors none = spec().build().mutations();
    assertEquals(0.1, none.moveProbability(0.1, 0.0), 0.0);
    assertEquals(0.1, none.moveProbability(0.1, 100.0), 0.0);
    MutationVectors migrate = spec().drift(DriftMode.MIGRATE, 0.5).build().mutations();
    assertEquals(0.1, migrate.moveProbability(0.1, 10.0), 0.0);
    MutationVectors reassign = spec().drift(DriftMode.REASSIGN, 0.01).build().mutations();
    assertEquals(0.1, reassign.moveProbability(0.1, 0.0), 0.0);
    assertEquals(0.1, reassign.moveProbability(0.1, -5.0), 0.0);
    assertEquals(0.2, reassign.moveProbability(0.1, 10.0), 1e-12);
    assertEquals(1.0, reassign.moveProbability(0.1, 1000.0), 0.0);
    double prev = -1.0;
    for (double minutes = 0; minutes <= 120; minutes += 5) {
      double p = reassign.moveProbability(0.1, minutes);
      assertTrue(p >= prev);
      assertTrue(p <= 1.0);
      prev = p;
    }
    assertEquals(0.1,
      spec().drift(DriftMode.REASSIGN, 0.0).build().mutations().moveProbability(0.1, 10.0), 0.0);
  }

  @Test
  public void testMigrateShiftsClustersOverTime() {
    double rate = 0.05;
    SyntheticCorpus c = spec().dim(32).drift(DriftMode.MIGRATE, rate).build();
    double expectedShift = rate * 10.0 * SyntheticCorpus.TIGHTEST_RADIUS * Math.sqrt(32);
    for (long ordinal = 2000; ordinal < 2050; ordinal++) {
      Embedding before = c.mutations().insert(ordinal, 0.0);
      Embedding after = c.mutations().insert(ordinal, 10.0);
      assertEquals(before.cluster(), after.cluster());
      float[] dir = c.component(after.cluster()).driftDir;
      assertEquals(1.0, norm(dir), 1e-5);
      for (int i = 0; i < 32; i++) {
        assertEquals(expectedShift * dir[i], after.vector()[i] - before.vector()[i], 1e-4);
      }
    }
    // Base rows are not affected
    assertArrayEquals(spec().dim(32).build().vector(5), c.vector(5), 0.0f);
    // Without drift there is no direction, and time does not matter
    SyntheticCorpus still = spec().dim(32).build();
    assertNull(still.component(0).driftDir);
    assertArrayEquals(still.mutations().insert(2000, 0.0).vector(),
      still.mutations().insert(2000, 10.0).vector(), 0.0f);
  }

  @Test
  public void testBirthDeathTurnover() {
    SyntheticCorpus c = spec().drift(DriftMode.BIRTH_DEATH, 0.05).build();
    assertEquals(0, c.turnover(0.0));
    assertEquals(0, c.turnover(-1.0));
    assertEquals(0, c.turnover(2.4));
    assertEquals(1, c.turnover(2.5));
    assertEquals(2, c.turnover(5.0));
    assertEquals(4, c.turnover(10.0));
    assertEquals(8, c.turnover(20.0));
    assertEquals(8, c.turnover(1000.0));
    assertEquals(0, spec().drift(DriftMode.MIGRATE, 0.05).build().turnover(10.0));
    assertEquals(0, spec().drift(DriftMode.BIRTH_DEATH, 0.0).build().turnover(10.0));
  }

  @Test
  public void testBirthDeathReplacesRetiredComponents() {
    SyntheticCorpus c = spec().spread(3.0).drift(DriftMode.BIRTH_DEATH, 0.05).build();
    Set<Integer> previousRetired = new HashSet<>();
    for (int k = 0; k <= 8; k++) {
      double minutes = 2.5 * k;
      assertEquals(k, c.turnover(minutes));
      Set<Integer> active = new TreeSet<>();
      SplitMix64 rnd = new SplitMix64(k);
      for (int i = 0; i < 2000; i++) {
        active.add(c.sampleActive(rnd, minutes));
      }
      Set<Integer> retired = new TreeSet<>();
      Set<Integer> born = new TreeSet<>();
      for (int slot = 0; slot < 8; slot++) {
        if (!active.contains(slot)) {
          retired.add(slot);
        }
      }
      for (int id : active) {
        if (id >= 8) {
          born.add(id);
        }
      }
      assertEquals("turnover " + k + " active " + active, k, retired.size());
      assertEquals(k, born.size());
      for (int j = 0; j < k; j++) {
        assertTrue(born.contains(8 + j));
      }
      // Retirement is cumulative
      assertTrue(retired.containsAll(previousRetired));
      previousRetired = retired;
      // A born component inherits the deviation of the component it replaces
      Set<Double> bornSigmas = new TreeSet<>();
      for (int id : born) {
        bornSigmas.add(c.component(id).sigma);
      }
      Set<Double> retiredSigmas = new TreeSet<>();
      for (int slot : retired) {
        retiredSigmas.add(c.component(slot).sigma);
      }
      assertEquals(retiredSigmas, bornSigmas);
    }
  }

  @Test
  public void testBirthDeathInsertsUseBornComponents() {
    SyntheticCorpus c = spec().dim(32).drift(DriftMode.BIRTH_DEATH, 0.05).build();
    // Born components get fresh centers
    for (int j = 8; j < 16; j++) {
      for (int k = 0; k < 8; k++) {
        assertFalse(Arrays.equals(c.component(j).center, c.component(k).center));
      }
    }
    // After full turnover, every insert comes from a born component
    for (long ordinal = 2000; ordinal < 2200; ordinal++) {
      Embedding e = c.mutations().insert(ordinal, 20.0);
      assertTrue(e.cluster() >= 8 && e.cluster() < 16);
      assertEquals(e.cluster(), nearestCenter(c, e.vector(), 16));
    }
    // Before any turnover, none does
    for (long ordinal = 2000; ordinal < 2200; ordinal++) {
      assertTrue(c.mutations().insert(ordinal, 0.0).cluster() < 8);
    }
    // Moves at half turnover avoid retired slots
    Set<Integer> retired = new HashSet<>();
    SplitMix64 rnd = new SplitMix64(1L);
    Set<Integer> active = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      active.add(c.sampleActive(rnd, 10.0));
    }
    for (int slot = 0; slot < 8; slot++) {
      if (!active.contains(slot)) {
        retired.add(slot);
      }
    }
    assertEquals(4, retired.size());
    for (long seq = 0; seq < 200; seq++) {
      Embedding e = c.mutations().move(1, 0, seq, 10.0);
      assertFalse(retired.contains(e.cluster()));
      assertNotEquals(0, e.cluster());
    }
  }
}
