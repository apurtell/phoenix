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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.jhdf.HdfFile;
import io.jhdf.WritableHdfFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Metric;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Hdf5CorpusTest {

  private static final int DIM = 6;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static float[][] floats(long seed, int n, int dim) {
    SplitMix64 rnd = new SplitMix64(seed);
    float[][] out = new float[n][dim];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < dim; j++) {
        out[i][j] = (float) rnd.nextGaussian();
      }
    }
    return out;
  }

  private static int[][] neighbors(int queries, int k, int rows) {
    int[][] out = new int[queries][k];
    for (int q = 0; q < queries; q++) {
      for (int i = 0; i < k; i++) {
        out[q][i] = (q * 7 + i * 13) % rows;
      }
    }
    return out;
  }

  private final float[][] train = floats(1L, 120, DIM);
  private final float[][] test = floats(2L, 9, DIM);
  private final int[][] nb = neighbors(9, 4, 120);

  /** Writes an ANN-Benchmarks file; a null distance writes no distance attribute. */
  private Path write(String name, Object trainData, Object testData, Object neighborData,
    String distance) throws IOException {
    Path p = tmp.getRoot().toPath().resolve(name);
    try (WritableHdfFile w = HdfFile.write(p)) {
      if (trainData != null) {
        w.putDataset("train", trainData);
      }
      if (testData != null) {
        w.putDataset("test", testData);
      }
      if (neighborData != null) {
        w.putDataset("neighbors", neighborData);
      }
      if (distance != null) {
        w.putAttribute("distance", distance);
      }
    }
    return p;
  }

  private Path standard(String distance) throws IOException {
    return write("glove-6-angular.hdf5", train, test, nb, distance);
  }

  private static AttributeModel attributes(RandomStreams streams) {
    return new AttributeModel(streams, 3, 0.0, 10, 20, 4, 0.0, 8);
  }

  private static Hdf5Corpus open(Path p, long rows, long seed) throws IOException {
    RandomStreams streams = new RandomStreams(seed);
    return Hdf5Corpus.open(p, rows, streams, attributes(streams));
  }

  private static double norm(float[] v) {
    double s = 0.0;
    for (float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }

  @Test
  public void testOpenAngularCorpus() throws IOException {
    Path p = standard("angular");
    try (Hdf5Corpus c = open(p, 0, 42L)) {
      assertSame(Metric.COSINE, c.definedMetric());
      assertEquals("hdf5:glove-6-angular", c.sourceName());
      assertEquals("hdf5:glove-6-angular rows=120 dim=6 queries=9 distance=angular",
        c.description());
      assertEquals(DIM, c.dim());
      assertEquals(120L, c.rows());
      assertEquals(-1, c.clusterOf(0));
      for (int i = 0; i < 120; i++) {
        assertArrayEquals(train[i], c.vector(i), 0.0f);
      }
      assertEquals(9, c.panel(100).size());
      for (PanelQuery q : c.panel(9)) {
        assertSame(PanelQuery.Kind.PUBLISHED, q.kind());
        assertArrayEquals(test[q.index()], q.vector(), 0.0f);
        assertTrue(q.tenant() >= 0 && q.tenant() < 3);
      }
      int[][] published = c.publishedNeighbors();
      assertEquals(9, published.length);
      for (int q = 0; q < 9; q++) {
        assertArrayEquals(nb[q], published[q]);
      }
      try {
        c.vector(120);
        fail("Expected an exception");
      } catch (IndexOutOfBoundsException e) {
        // expected
      }
    }
  }

  @Test
  public void testDistanceAttributes() throws IOException {
    String[][] cases =
      { { "euclidean", "L2" }, { "angular", "COSINE" }, { "dot", "INNER_PRODUCT" } };
    for (String[] c : cases) {
      Path p = write("d-" + c[0] + ".hdf5", train, test, null, c[0]);
      try (Hdf5Corpus corpus = open(p, 0, 1L)) {
        assertSame(Metric.valueOf(c[1]), corpus.definedMetric());
        assertEquals(c[0], corpus.parameters().get("distance"));
      }
    }
  }

  @Test
  public void testDefaultDistanceIsEuclidean() throws IOException {
    Path p = write("plain.hdf5", train, test, null, null);
    try (Hdf5Corpus c = open(p, 0, 1L)) {
      assertSame(Metric.L2, c.definedMetric());
      assertEquals("euclidean", c.parameters().get("distance"));
      assertNull(c.publishedNeighbors());
    }
  }

  @Test
  public void testUnsupportedDistance() throws IOException {
    Path p = write("hamming.hdf5", train, test, null, "hamming");
    try {
      open(p, 0, 1L).close();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("hamming"));
    }
  }

  @Test
  public void testMetricOf() {
    assertSame(Metric.L2, Hdf5Corpus.metricOf("euclidean"));
    assertSame(Metric.L2, Hdf5Corpus.metricOf(" L2 "));
    assertSame(Metric.COSINE, Hdf5Corpus.metricOf("Angular"));
    assertSame(Metric.COSINE, Hdf5Corpus.metricOf("cosine"));
    assertSame(Metric.INNER_PRODUCT, Hdf5Corpus.metricOf("dot"));
    assertSame(Metric.INNER_PRODUCT, Hdf5Corpus.metricOf("ip"));
    assertSame(Metric.INNER_PRODUCT, Hdf5Corpus.metricOf("INNER_PRODUCT"));
    for (String bad : new String[] { "hamming", "jaccard", "" }) {
      try {
        Hdf5Corpus.metricOf(bad);
        fail("Expected an exception for " + bad);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  @Test
  public void testConversions() {
    float[][] f = { { 1, 2 } };
    assertSame(f, Hdf5Corpus.toFloats(f));
    float[][] fromDouble = Hdf5Corpus.toFloats(new double[][] { { 1.5, -2.25 }, { 3, 4 } });
    assertArrayEquals(new float[] { 1.5f, -2.25f }, fromDouble[0], 0.0f);
    assertArrayEquals(new float[] { 3, 4 }, fromDouble[1], 0.0f);
    int[][] i = { { 1, 2 } };
    assertSame(i, Hdf5Corpus.toInts(i));
    int[][] fromLong = Hdf5Corpus.toInts(new long[][] { { 1L, 2L }, { 3L, 4L } });
    assertArrayEquals(new int[] { 1, 2 }, fromLong[0]);
    assertArrayEquals(new int[] { 3, 4 }, fromLong[1]);
    try {
      Hdf5Corpus.toInts(new long[][] { { 1L << 40 } });
      fail("Expected an exception");
    } catch (ArithmeticException e) {
      // expected
    }
    try {
      Hdf5Corpus.toFloats(new int[][] { { 1 } });
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("float"));
    }
    try {
      Hdf5Corpus.toInts(new float[][] { { 1 } });
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("integer"));
    }
  }

  @Test
  public void testDoubleTrainAndLongNeighbors() throws IOException {
    double[][] dtrain = new double[train.length][DIM];
    for (int i = 0; i < train.length; i++) {
      for (int j = 0; j < DIM; j++) {
        dtrain[i][j] = train[i][j];
      }
    }
    long[][] lnb = new long[nb.length][nb[0].length];
    for (int q = 0; q < nb.length; q++) {
      for (int k = 0; k < nb[q].length; k++) {
        lnb[q][k] = nb[q][k];
      }
    }
    Path p = write("doubles.hdf5", dtrain, test, lnb, "euclidean");
    try (Hdf5Corpus c = open(p, 0, 1L)) {
      for (int i = 0; i < train.length; i++) {
        assertArrayEquals(train[i], c.vector(i), 0.0f);
      }
      int[][] published = c.publishedNeighbors();
      for (int q = 0; q < nb.length; q++) {
        assertArrayEquals(nb[q], published[q]);
      }
    }
  }

  @Test
  public void testRowsPrefixAndHeldOutRows() throws IOException {
    Path p = standard("angular");
    try (Hdf5Corpus c = open(p, 100, 42L)) {
      assertEquals(100L, c.rows());
      assertNull(c.publishedNeighbors());
      assertEquals("100", c.parameters().get("rows"));
      assertEquals("120", c.parameters().get("train.count"));
      MutationVectors m = c.mutations();
      for (int j = 0; j < 20; j++) {
        Embedding e = m.insert(100 + j, 0.0);
        assertEquals(-1, e.cluster());
        assertArrayEquals(train[100 + j], e.vector(), 0.0f);
      }
      assertArrayEquals(train[100], m.insert(120, 0.0).vector(), 0.0f);
      for (long seq = 0; seq < 20; seq++) {
        float[] v = m.move(1, -1, seq, 0.0).vector();
        boolean found = false;
        for (int j = 100; j < 120; j++) {
          found |= Arrays.equals(train[j], v);
        }
        assertTrue(found);
      }
    }
    try (Hdf5Corpus c = open(p, 500, 42L)) {
      assertEquals(120L, c.rows());
      assertTrue(c.publishedNeighbors() != null);
    }
  }

  @Test
  public void testPerturbedMutationsOfAngularCorpusAreNormalized() throws IOException {
    Path p = standard("angular");
    try (Hdf5Corpus c = open(p, 0, 42L)) {
      MutationVectors m = c.mutations();
      for (long ordinal = 120; ordinal < 140; ordinal++) {
        assertEquals(1.0, norm(m.insert(ordinal, 0.0).vector()), 1e-5);
      }
      float[] local = m.reembedLocal(3, -1, c.vector(3), 9, 0.0).vector();
      assertEquals(1.0, norm(local), 1e-5);
      assertTrue(Metric.COSINE.distance(local, c.vector(3)) < 0.05);
    }
    Path e = write("euclid.hdf5", train, test, null, "euclidean");
    try (Hdf5Corpus c = open(e, 0, 42L)) {
      float[] local = c.mutations().reembedLocal(3, -1, c.vector(3), 9, 0.0).vector();
      assertNotEquals(1.0, norm(local), 1e-3);
    }
  }

  @Test
  public void testBlockBoundaries() throws IOException {
    int rows = Hdf5Corpus.BLOCK_ROWS * (Hdf5Corpus.CACHED_BLOCKS + 2) + 17;
    float[][] big = new float[rows][2];
    for (int i = 0; i < rows; i++) {
      big[i][0] = i;
      big[i][1] = -i;
    }
    Path p = write("big.hdf5", big, floats(3L, 2, 2), null, null);
    try (Hdf5Corpus c = open(p, 0, 1L)) {
      assertEquals(rows, c.rows());
      long[] probes = { 0, Hdf5Corpus.BLOCK_ROWS - 1, Hdf5Corpus.BLOCK_ROWS,
        Hdf5Corpus.BLOCK_ROWS + 1, rows - 1, 0 };
      for (long i : probes) {
        assertArrayEquals(new float[] { i, -i }, c.vector(i), 0.0f);
      }
      // Visit every block, more than the cache holds, then revisit evicted blocks
      for (long i = 5; i < rows; i += Hdf5Corpus.BLOCK_ROWS) {
        assertArrayEquals(new float[] { i, -i }, c.vector(i), 0.0f);
      }
      for (long i : probes) {
        assertArrayEquals(new float[] { i, -i }, c.vector(i), 0.0f);
      }
    }
  }

  @Test
  public void testInvalidFiles() throws IOException {
    try {
      open(tmp.getRoot().toPath(), 0, 1L).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not a file"));
    }
    Path noTest = write("notest.hdf5", train, null, null, "euclidean");
    try {
      open(noTest, 0, 1L).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("test"));
    }
    Path noTrain = write("notrain.hdf5", null, test, null, "euclidean");
    try {
      open(noTrain, 0, 1L).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("train"));
    }
    Path widths = write("widths.hdf5", train, floats(4L, 3, DIM + 1), null, "euclidean");
    try {
      open(widths, 0, 1L).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("equal widths"));
    }
  }

  @Test
  public void testParametersAndFingerprint() throws IOException {
    Path p = standard("angular");
    String full;
    try (Hdf5Corpus a = open(p, 0, 42L); Hdf5Corpus b = open(p, 0, 42L)) {
      Map<String, String> params = a.parameters();
      assertEquals("hdf5:glove-6-angular", params.get("source"));
      assertEquals("120", params.get("rows"));
      assertEquals("6", params.get("dim"));
      assertEquals("9", params.get("queries"));
      assertEquals("42", params.get("seed"));
      assertEquals("3", params.get("attr.tenants"));
      full = a.fingerprint();
      assertTrue(full.matches("[0-9a-f]{64}"));
      assertSame(full, a.fingerprint());
      assertEquals(full, b.fingerprint());
      assertEquals(a.panelDigest(9), b.panelDigest(9));
    }
    try (Hdf5Corpus c = open(p, 100, 42L)) {
      assertNotEquals(full, c.fingerprint());
    }
    try (Hdf5Corpus c = open(p, 0, 43L)) {
      assertNotEquals(full, c.fingerprint());
    }
    // The decoded values are digested, so a different neighbor list changes the fingerprint
    int[][] other = neighbors(9, 4, 120);
    other[0][0] = (other[0][0] + 1) % 120;
    Path q = write("other.hdf5", train, test, other, "angular");
    Path same = write("same.hdf5", train, test, nb, "angular");
    try (Hdf5Corpus c = open(q, 0, 42L); Hdf5Corpus d = open(same, 0, 42L)) {
      assertNotEquals(d.fingerprint(), c.fingerprint());
    }
  }
}
