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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.Metric;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TexmexCorpusTest {

  private static final int DIM = 8;
  private static final int BASE = 200;
  private static final int QUERIES = 10;
  private static final int LEARN = 30;
  private static final int K = 5;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static List<float[]> vectors(long seed, int n, int dim) {
    SplitMix64 rnd = new SplitMix64(seed);
    List<float[]> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      float[] v = new float[dim];
      for (int j = 0; j < dim; j++) {
        v[j] = (float) (rnd.nextDouble() * 100);
      }
      out.add(v);
    }
    return out;
  }

  /** Returns the K nearest base ordinals of each query by L2, as TEXMEX ground truth. */
  private static List<int[]> groundTruth(List<float[]> base, List<float[]> queries) {
    List<int[]> out = new ArrayList<>();
    for (float[] q : queries) {
      Integer[] order = new Integer[base.size()];
      for (int i = 0; i < order.length; i++) {
        order[i] = i;
      }
      Arrays.sort(order, (a, b) -> Double.compare(Metric.L2.distance(q, base.get(a)),
        Metric.L2.distance(q, base.get(b))));
      int[] row = new int[K];
      for (int i = 0; i < K; i++) {
        row[i] = order[i];
      }
      out.add(row);
    }
    return out;
  }

  private final List<float[]> base = vectors(1L, BASE, DIM);
  private final List<float[]> queries = vectors(2L, QUERIES, DIM);
  private final List<float[]> learn = vectors(3L, LEARN, DIM);

  /** Writes a SIFT-like corpus directory. */
  private Path corpusDir(String name, boolean withGroundTruth, boolean withLearn)
    throws IOException {
    Path dir = tmp.newFolder().toPath();
    writeCorpus(dir, name, withGroundTruth, withLearn);
    return dir;
  }

  private void writeCorpus(Path dir, String name, boolean withGroundTruth, boolean withLearn)
    throws IOException {
    VecsFile.writeFvecs(dir.resolve(name + "_base.fvecs"), base);
    VecsFile.writeFvecs(dir.resolve(name + "_query.fvecs"), queries);
    if (withGroundTruth) {
      VecsFile.writeIvecs(dir.resolve(name + "_groundtruth.ivecs"), groundTruth(base, queries));
    }
    if (withLearn) {
      VecsFile.writeFvecs(dir.resolve(name + "_learn.fvecs"), learn);
    }
  }

  private static AttributeModel attributes(RandomStreams streams) {
    return new AttributeModel(streams, 4, 0.0, 10, 20, 4, 0.0, 8);
  }

  private static TexmexCorpus open(Path dir, String name, long rows, long seed) throws IOException {
    RandomStreams streams = new RandomStreams(seed);
    return TexmexCorpus.open(dir, name, rows, streams, attributes(streams));
  }

  private static TexmexCorpus open(Path dir, long rows) throws IOException {
    return open(dir, null, rows, 42L);
  }

  private static boolean contains(List<float[]> list, float[] v) {
    for (float[] x : list) {
      if (Arrays.equals(x, v)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void testOpenDetectsName() throws IOException {
    Path dir = corpusDir("sift", true, true);
    try (TexmexCorpus c = open(dir, 0)) {
      assertEquals("texmex:sift", c.sourceName());
      assertEquals("texmex:sift rows=200 dim=8 queries=10", c.description());
      assertTrue(c.toString().contains(dir.toString()));
      assertEquals(DIM, c.dim());
      assertEquals(BASE, c.rows());
      assertSame(Metric.L2, c.definedMetric());
      assertFalse(c.quantized());
      assertEquals(-1, c.clusterOf(3));
      assertEquals(c.attributeModel().attributes(7), c.attributes(7));
    }
  }

  @Test
  public void testVectors() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", false, false), 0)) {
      for (int i = 0; i < BASE; i++) {
        assertArrayEquals(base.get(i), c.vector(i), 0.0f);
      }
      for (long bad : new long[] { -1L, BASE }) {
        try {
          c.vector(bad);
          fail("Expected an exception for " + bad);
        } catch (IndexOutOfBoundsException e) {
          // expected
        }
      }
    }
  }

  @Test
  public void testRowsPrefix() throws IOException {
    Path dir = corpusDir("sift", true, true);
    try (TexmexCorpus c = open(dir, 50)) {
      assertEquals(50L, c.rows());
      assertArrayEquals(base.get(49), c.vector(49), 0.0f);
      try {
        c.vector(50);
        fail("Expected an exception");
      } catch (IndexOutOfBoundsException e) {
        // expected
      }
      // The published ground truth describes the full base set
      assertNull(c.publishedNeighbors());
      assertEquals("50", c.parameters().get("rows"));
      assertEquals("200", c.parameters().get("base.count"));
    }
    try (TexmexCorpus c = open(dir, 5000)) {
      assertEquals(BASE, c.rows());
      assertTrue(c.publishedNeighbors() != null);
    }
    try (TexmexCorpus c = open(dir, BASE)) {
      assertTrue(c.publishedNeighbors() != null);
    }
  }

  @Test
  public void testExplicitAndAmbiguousNames() throws IOException {
    Path dir = corpusDir("sift", true, false);
    writeCorpus(dir, "gist", false, false);
    try {
      open(dir, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Several"));
    }
    try (TexmexCorpus c = open(dir, "gist", 0, 42L)) {
      assertEquals("texmex:gist", c.sourceName());
      assertNull(c.publishedNeighbors());
    }
    try (TexmexCorpus c = open(dir, "sift", 0, 42L)) {
      assertEquals("texmex:sift", c.sourceName());
      assertEquals(QUERIES, c.publishedNeighbors().length);
    }
    try {
      open(dir, "deep", 0, 42L).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("deep_base"));
    }
  }

  @Test
  public void testMissingFiles() throws IOException {
    Path empty = tmp.newFolder().toPath();
    try {
      open(empty, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("_base"));
    }
    Path notDir = tmp.newFile("sift_base.fvecs").toPath();
    try {
      open(notDir, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not a directory"));
    }
    Path noQuery = tmp.newFolder().toPath();
    VecsFile.writeFvecs(noQuery.resolve("sift_base.fvecs"), base);
    try {
      open(noQuery, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("sift_query"));
    }
  }

  @Test
  public void testQueryDimensionMismatch() throws IOException {
    Path dir = tmp.newFolder().toPath();
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), base);
    VecsFile.writeFvecs(dir.resolve("sift_query.fvecs"), vectors(9L, 3, DIM + 1));
    try {
      open(dir, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("dimension"));
    }
  }

  @Test
  public void testPanel() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", true, false), 0)) {
      List<PanelQuery> five = c.panel(5);
      assertEquals(5, five.size());
      List<PanelQuery> all = c.panel(1000);
      assertEquals(QUERIES, all.size());
      for (int q = 0; q < QUERIES; q++) {
        PanelQuery pq = all.get(q);
        assertEquals(q, pq.index());
        assertSame(PanelQuery.Kind.PUBLISHED, pq.kind());
        assertEquals(-1, pq.cluster());
        assertTrue(pq.tenant() >= 0 && pq.tenant() < 4);
        assertArrayEquals(queries.get(q), pq.vector(), 0.0f);
        if (q < 5) {
          assertSame(five.get(q), pq);
        }
      }
      assertTrue(c.panel(0).isEmpty());
    }
  }

  @Test
  public void testPanelTenantsFollowTheSeed() throws IOException {
    Path dir = corpusDir("sift", false, false);
    int[] a = new int[QUERIES];
    int[] b = new int[QUERIES];
    int[] c = new int[QUERIES];
    try (TexmexCorpus x = open(dir, null, 0, 1L); TexmexCorpus y = open(dir, null, 0, 1L);
      TexmexCorpus z = open(dir, null, 0, 2L)) {
      for (int q = 0; q < QUERIES; q++) {
        a[q] = x.panel(QUERIES).get(q).tenant();
        b[q] = y.panel(QUERIES).get(q).tenant();
        c[q] = z.panel(QUERIES).get(q).tenant();
      }
      assertArrayEquals(a, b);
      assertFalse(Arrays.equals(a, c));
      assertEquals(x.panelDigest(QUERIES), y.panelDigest(QUERIES));
      assertNotEquals(x.panelDigest(QUERIES), z.panelDigest(QUERIES));
      assertNotEquals(x.panelDigest(QUERIES), x.panelDigest(QUERIES - 1));
    }
  }

  @Test
  public void testPublishedNeighbors() throws IOException {
    List<int[]> truth = groundTruth(base, queries);
    try (TexmexCorpus c = open(corpusDir("sift", true, false), 0)) {
      int[][] nb = c.publishedNeighbors();
      assertEquals(QUERIES, nb.length);
      for (int q = 0; q < QUERIES; q++) {
        assertArrayEquals(truth.get(q), nb[q]);
      }
    }
    try (TexmexCorpus c = open(corpusDir("sift", false, false), 0)) {
      assertNull(c.publishedNeighbors());
    }
  }

  @Test
  public void testGroundTruthOfOtherQueryCountIsIgnored() throws IOException {
    Path dir = corpusDir("sift", false, false);
    VecsFile.writeIvecs(dir.resolve("sift_groundtruth.ivecs"),
      groundTruth(base, queries.subList(0, QUERIES - 1)));
    try (TexmexCorpus c = open(dir, 0)) {
      assertNull(c.publishedNeighbors());
    }
  }

  @Test
  public void testMutationsUseTheLearnPartition() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", true, true), 0)) {
      MutationVectors m = c.mutations();
      for (int j = 0; j < LEARN; j++) {
        Embedding e = m.insert(BASE + j, 0.0);
        assertEquals(-1, e.cluster());
        assertArrayEquals(learn.get(j), e.vector(), 0.0f);
      }
      // Inserts past the learn partition wrap around
      assertArrayEquals(learn.get(0), m.insert(BASE + LEARN, 0.0).vector(), 0.0f);
      assertArrayEquals(learn.get(3), m.insert(BASE + 2 * LEARN + 3, 5.0).vector(), 0.0f);
      Set<String> moved = new HashSet<>();
      for (long seq = 0; seq < 100; seq++) {
        Embedding e = m.move(1, -1, seq, 0.0);
        assertEquals(-1, e.cluster());
        assertTrue(contains(learn, e.vector()));
        moved.add(Arrays.toString(e.vector()));
      }
      assertTrue(moved.size() > 10);
      assertEquals(0.25, m.moveProbability(0.25, 100.0), 0.0);
    }
  }

  @Test
  public void testMutationsUseBaseRowsPastThePrefix() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", false, false), 150)) {
      MutationVectors m = c.mutations();
      for (int j = 0; j < 50; j++) {
        assertArrayEquals(base.get(150 + j), m.insert(150 + j, 0.0).vector(), 0.0f);
      }
      assertArrayEquals(base.get(150), m.insert(200, 0.0).vector(), 0.0f);
      for (long seq = 0; seq < 50; seq++) {
        float[] v = m.move(3, -1, seq, 0.0).vector();
        assertTrue(contains(base.subList(150, 200), v));
      }
    }
  }

  @Test
  public void testMutationsPerturbLoadedRowsWithoutHeldOutVectors() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", false, false), 0)) {
      MutationVectors m = c.mutations();
      for (long ordinal = BASE; ordinal < BASE + 50; ordinal++) {
        float[] v = m.insert(ordinal, 0.0).vector();
        assertEquals(DIM, v.length);
        assertFalse(contains(base, v));
        // Close to one loaded row: the noise is 2% of the RMS element, about 0.02 * 58 per
        // element
        double nearest = Double.MAX_VALUE;
        for (float[] b : base) {
          nearest = Math.min(nearest, Metric.L2.distance(v, b));
        }
        assertTrue("nearest " + nearest, nearest < 4 * 0.02 * 60 * Math.sqrt(DIM));
        assertArrayEquals(v, m.insert(ordinal, 0.0).vector(), 0.0f);
      }
      float[] moved = m.move(1, -1, 7, 0.0).vector();
      assertFalse(contains(base, moved));
      assertArrayEquals(moved, m.move(1, -1, 7, 0.0).vector(), 0.0f);
    }
  }

  @Test
  public void testReembedLocalPerturbsTheCurrentVector() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", false, true), 0)) {
      MutationVectors m = c.mutations();
      float[] current = c.vector(17);
      double ss = 0.0;
      for (float x : current) {
        ss += (double) x * x;
      }
      double sd = PublishedCorpus.LOCAL_NOISE * Math.sqrt(ss / DIM);
      for (long seq = 0; seq < 50; seq++) {
        Embedding e = m.reembedLocal(17, -1, current, seq, 0.0);
        assertEquals(-1, e.cluster());
        float[] v = e.vector();
        assertFalse(Arrays.equals(current, v));
        for (int i = 0; i < DIM; i++) {
          assertEquals(current[i], v[i], 5 * sd);
        }
        assertArrayEquals(v, m.reembedLocal(17, -1, current, seq, 0.0).vector(), 0.0f);
      }
      assertFalse(Arrays.equals(m.reembedLocal(17, -1, current, 1, 0.0).vector(),
        m.reembedLocal(17, -1, current, 2, 0.0).vector()));
      // The current vector is not modified
      assertArrayEquals(base.get(17), current, 0.0f);
    }
  }

  @Test
  public void testByteVectors() throws IOException {
    Path dir = tmp.newFolder().toPath();
    List<int[]> bytes = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      int[] v = new int[DIM];
      for (int j = 0; j < DIM; j++) {
        v[j] = (i * 37 + j * 11) % 256;
      }
      bytes.add(v);
    }
    VecsFile.writeBvecs(dir.resolve("bigann_base.bvecs"), bytes);
    VecsFile.writeBvecs(dir.resolve("bigann_query.bvecs"), bytes.subList(0, 4));
    VecsFile.writeBvecs(dir.resolve("bigann_learn.bvecs"), bytes.subList(10, 20));
    try (TexmexCorpus c = open(dir, 0)) {
      assertEquals("texmex:bigann", c.sourceName());
      assertTrue(c.quantized());
      assertEquals("BVECS", c.parameters().get("base.format"));
      for (int i = 0; i < 40; i++) {
        for (int j = 0; j < DIM; j++) {
          assertEquals(bytes.get(i)[j], c.vector(i)[j], 0.0f);
        }
      }
      assertEquals(4, c.panel(100).size());
      assertEquals(bytes.get(12)[3], c.mutations().insert(42, 0.0).vector()[3], 0.0f);
    }
  }

  @Test
  public void testParameters() throws IOException {
    try (TexmexCorpus c = open(corpusDir("sift", true, true), 120)) {
      Map<String, String> p = c.parameters();
      assertEquals("texmex:sift", p.get("source"));
      assertEquals("120", p.get("rows"));
      assertEquals("8", p.get("dim"));
      assertEquals("200", p.get("base.count"));
      assertEquals("FVECS", p.get("base.format"));
      assertEquals("10", p.get("queries"));
      assertEquals("42", p.get("seed"));
      assertEquals("20", p.get("attr.categories"));
      assertEquals("30", p.get("learn.count"));
      assertEquals("FVECS", p.get("learn.format"));
    }
    try (TexmexCorpus c = open(corpusDir("sift", true, false), 120)) {
      assertEquals("0", c.parameters().get("learn.count"));
      assertNull(c.parameters().get("learn.format"));
    }
  }

  @Test
  public void testFingerprint() throws IOException {
    Path dir = corpusDir("sift", true, true);
    String full;
    String prefix;
    try (TexmexCorpus a = open(dir, 0); TexmexCorpus b = open(dir, 0)) {
      full = a.fingerprint();
      assertTrue(full.matches("[0-9a-f]{64}"));
      assertSame(full, a.fingerprint());
      assertEquals(full, b.fingerprint());
    }
    try (TexmexCorpus c = open(dir, 100)) {
      prefix = c.fingerprint();
      assertNotEquals(full, prefix);
    }
    try (TexmexCorpus c = open(dir, null, 0, 43L)) {
      assertNotEquals(full, c.fingerprint());
    }
    // Rows past the loaded prefix are not part of the fingerprint; rows inside it are.
    List<float[]> changedTail = new ArrayList<>(base);
    changedTail.set(150, new float[DIM]);
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), changedTail);
    try (TexmexCorpus c = open(dir, 100)) {
      assertEquals(prefix, c.fingerprint());
    }
    try (TexmexCorpus c = open(dir, 0)) {
      assertNotEquals(full, c.fingerprint());
    }
    List<float[]> changedHead = new ArrayList<>(base);
    changedHead.set(50, new float[DIM]);
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), changedHead);
    try (TexmexCorpus c = open(dir, 100)) {
      assertNotEquals(prefix, c.fingerprint());
    }
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), base);
    // The queries and the ground truth are part of it
    List<float[]> changedQueries = new ArrayList<>(queries);
    changedQueries.set(0, new float[DIM]);
    VecsFile.writeFvecs(dir.resolve("sift_query.fvecs"), changedQueries);
    try (TexmexCorpus c = open(dir, 0)) {
      assertNotEquals(full, c.fingerprint());
    }
    VecsFile.writeFvecs(dir.resolve("sift_query.fvecs"), queries);
    try (TexmexCorpus c = open(dir, 0)) {
      assertEquals(full, c.fingerprint());
    }
    // So is the learn partition, which mutations insert from.
    List<float[]> changedLearn = new ArrayList<>(learn);
    changedLearn.set(LEARN - 1, new float[DIM]);
    VecsFile.writeFvecs(dir.resolve("sift_learn.fvecs"), changedLearn);
    try (TexmexCorpus c = open(dir, 0)) {
      assertNotEquals(full, c.fingerprint());
    }
    Files.delete(dir.resolve("sift_learn.fvecs"));
    try (TexmexCorpus c = open(dir, 0)) {
      assertNotEquals(full, c.fingerprint());
    }
    VecsFile.writeFvecs(dir.resolve("sift_learn.fvecs"), learn);
    try (TexmexCorpus c = open(dir, 0)) {
      assertEquals(full, c.fingerprint());
    }
    Files.delete(dir.resolve("sift_groundtruth.ivecs"));
    try (TexmexCorpus c = open(dir, 0)) {
      assertNotEquals(full, c.fingerprint());
    }
  }

  /** Counts the descriptors of this process that are open on files in a directory. */
  private static int openFiles(Path dir) throws IOException {
    int n = 0;
    Path real = dir.toRealPath();
    try (DirectoryStream<Path> fds = Files.newDirectoryStream(Paths.get("/proc/self/fd"))) {
      for (Path fd : fds) {
        try {
          if (Files.readSymbolicLink(fd).startsWith(real)) {
            n++;
          }
        } catch (IOException e) {
          // The descriptor closed while listing.
        }
      }
    }
    return n;
  }

  @Test
  public void testFailedOpenClosesWhatItOpened() throws IOException {
    Assume.assumeTrue(Files.isDirectory(Paths.get("/proc/self/fd")));
    Path dir = corpusDir("sift", true, true);
    // A partially downloaded learn file, whose size is not a whole number of records.
    try (FileChannel ch =
      FileChannel.open(dir.resolve("sift_learn.fvecs"), StandardOpenOption.WRITE)) {
      ch.truncate(ch.size() - 3);
    }
    for (int i = 0; i < 5; i++) {
      try {
        open(dir, 0).close();
        fail("Expected an exception");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("sift_learn"));
      }
    }
    assertEquals(0, openFiles(dir));
    Path mismatch = tmp.newFolder().toPath();
    VecsFile.writeFvecs(mismatch.resolve("sift_base.fvecs"), base);
    VecsFile.writeFvecs(mismatch.resolve("sift_query.fvecs"), vectors(9L, 3, DIM + 1));
    try {
      open(mismatch, 0).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("dimension"));
    }
    assertEquals(0, openFiles(mismatch));
  }
}
