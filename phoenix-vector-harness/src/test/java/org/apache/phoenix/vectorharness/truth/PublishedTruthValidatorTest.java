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
package org.apache.phoenix.vectorharness.truth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.MutationVectors;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.VecsFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PublishedTruthValidatorTest {

  private static final int SIDE = 10;
  private static final int RANKS = 10;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /** A corpus of explicit vectors and published neighbors; only what validation reads. */
  private static final class FakeCorpus implements Corpus {
    private final float[][] base;
    private final int[][] published;

    FakeCorpus(float[][] base, int[][] published) {
      this.base = base;
      this.published = published;
    }

    @Override
    public String description() {
      return "fake";
    }

    @Override
    public String sourceName() {
      return "fake";
    }

    @Override
    public int dim() {
      return base[0].length;
    }

    @Override
    public long rows() {
      return base.length;
    }

    @Override
    public Metric definedMetric() {
      return Metric.L2;
    }

    @Override
    public float[] vector(long ordinal) {
      return base[(int) ordinal];
    }

    @Override
    public int clusterOf(long ordinal) {
      return -1;
    }

    @Override
    public DocAttributes attributes(long ordinal) {
      return new DocAttributes(ordinal, 0, "t0", AttributeModel.docId(ordinal), 0, 0, 0, 0L, "");
    }

    @Override
    public AttributeModel attributeModel() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PanelQuery> panel(int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int[][] publishedNeighbors() {
      return published;
    }

    @Override
    public MutationVectors mutations() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, String> parameters() {
      return Collections.emptyMap();
    }

    @Override
    public String fingerprint() {
      return "fake";
    }

    @Override
    public String panelDigest(int size) {
      return "fake";
    }

    @Override
    public void close() {
    }
  }

  /** The points of a SIDE x SIDE integer grid, whose distances tie often. */
  private static float[][] grid() {
    float[][] base = new float[SIDE * SIDE][];
    for (int x = 0; x < SIDE; x++) {
      for (int y = 0; y < SIDE; y++) {
        base[x * SIDE + y] = new float[] { x, y };
      }
    }
    return base;
  }

  /** Queries on grid points, at cell centers, and on cell edges: all with tied neighbors. */
  private static List<PanelQuery> gridPanel(int n) {
    List<PanelQuery> panel = new ArrayList<>();
    for (int q = 0; q < n; q++) {
      float x = 1 + (q * 3) % 7 + (q % 3 == 1 ? 0.5f : 0f);
      float y = 2 + (q * 5) % 6 + (q % 3 == 2 ? 0.5f : 0f) + (q % 3 == 1 ? 0.5f : 0f);
      panel.add(new PanelQuery(q, new float[] { x, y }, PanelQuery.Kind.PUBLISHED, 0, -1));
    }
    return panel;
  }

  private static List<NeighborList> computeTruth(Corpus corpus, List<PanelQuery> panel,
    Metric metric) {
    BruteForceKnn knn = new BruteForceKnn(metric, 2);
    QueryCondition unfiltered = QueryCondition.builder(QueryShape.UNFILTERED).build();
    RowSource rows = RowSource.prefix(corpus, corpus.rows());
    List<NeighborList> out = new ArrayList<>();
    for (PanelQuery q : panel) {
      out.add(knn.single(q.vector(), unfiltered, rows, RANKS + 5));
    }
    return out;
  }

  /** Returns the computed neighbors with every run of exactly tied distances reversed. */
  private static int[][] swappedTies(List<NeighborList> truth, int ranks) {
    int[][] out = new int[truth.size()][];
    int swaps = 0;
    for (int q = 0; q < truth.size(); q++) {
      NeighborList l = truth.get(q);
      int n = Math.min(ranks, l.size());
      int[] p = new int[n];
      int i = 0;
      while (i < n) {
        int j = i;
        while (j + 1 < n && l.distance(j + 1) == l.distance(i)) {
          j++;
        }
        for (int a = i; a <= j; a++) {
          p[a] = (int) l.ordinal(j - (a - i));
        }
        if (j > i) {
          swaps++;
        }
        i = j + 1;
      }
      out[q] = p;
    }
    assertTrue("the grid should produce ties", swaps > truth.size());
    return out;
  }

  @Test
  public void testAgreesWithSwappedExactTies() {
    List<PanelQuery> panel = gridPanel(12);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    int[][] published = swappedTies(truth, RANKS);
    boolean reordered = false;
    for (int q = 0; q < published.length; q++) {
      for (int i = 0; i < RANKS; i++) {
        reordered |= published[q][i] != truth.get(q).ordinal(i);
      }
    }
    assertTrue(reordered);
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, published), panel, truth, RANKS);
    assertTrue(r.mismatches().toString(), r.agrees());
    assertEquals(12, r.queries());
    assertEquals(RANKS, r.ranks());
    assertTrue(r.mismatches().isEmpty());
  }

  @Test
  public void testReportsAWrongNeighbor() {
    List<PanelQuery> panel = gridPanel(6);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    int[][] published = swappedTies(truth, RANKS);
    // The far corner is never among the ten nearest neighbors of a query near the middle.
    published[2][6] = SIDE * SIDE - 1;
    PublishedTruthValidator validator = new PublishedTruthValidator(Metric.L2, 1e-6);
    PublishedTruthValidator.Result r =
      validator.validate(new FakeCorpus(base, published), panel, truth, RANKS);
    assertFalse(r.agrees());
    assertEquals(1, r.mismatches().size());
    String m = r.mismatches().get(0);
    assertTrue(m, m.startsWith("query 2 rank 6: published neighbor " + (SIDE * SIDE - 1)));
    assertTrue(m, m.contains("computed " + truth.get(2).ordinal(6)));

    // Comparing fewer ranks than the error's rank passes.
    r = validator.validate(new FakeCorpus(base, published), panel, truth, 6);
    assertTrue(r.agrees());
    assertEquals(6, r.ranks());
  }

  @Test
  public void testReportsNeighborsOutsideTheCorpus() {
    List<PanelQuery> panel = gridPanel(3);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    int[][] published = swappedTies(truth, RANKS);
    published[0][0] = -1;
    published[1][4] = SIDE * SIDE;
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, published), panel, truth, RANKS);
    assertFalse(r.agrees());
    assertEquals(2, r.mismatches().size());
    assertTrue(r.mismatches().get(0), r.mismatches().get(0).contains("query 0 rank 0"));
    assertTrue(r.mismatches().get(0), r.mismatches().get(0).contains("outside the corpus"));
    assertTrue(r.mismatches().get(1), r.mismatches().get(1).contains("query 1 rank 4"));
  }

  @Test
  public void testPublishedTruthOfAnotherMetricIsNotCompared() {
    // The fake corpus defines L2; its published neighbors say nothing about cosine neighbors.
    List<PanelQuery> panel = gridPanel(6);
    float[][] base = grid();
    for (float[] v : base) {
      v[0] += 1f;
    }
    List<NeighborList> l2 = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    int[][] published = swappedTies(l2, RANKS);
    List<NeighborList> cosine = computeTruth(new FakeCorpus(base, null), panel, Metric.COSINE);
    assertFalse("the metrics disagree", new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, published), panel, cosine, RANKS).agrees());
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.COSINE, 1e-6)
      .validate(new FakeCorpus(base, published), panel, cosine, RANKS);
    assertTrue(r.mismatches().toString(), r.agrees());
    assertEquals(0, r.queries());
    assertEquals(0, r.ranks());
    // Under the corpus's own metric the same lists are compared.
    r = new PublishedTruthValidator(Metric.L2, 1e-6).validate(new FakeCorpus(base, published),
      panel, l2, RANKS);
    assertTrue(r.agrees());
    assertEquals(6, r.queries());
  }

  @Test
  public void testNoPublishedTruth() {
    List<PanelQuery> panel = gridPanel(3);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, null), panel, truth, RANKS);
    assertTrue(r.agrees());
    assertEquals(0, r.queries());
    assertEquals(0, r.ranks());
  }

  @Test
  public void testComparesTheCommonPrefix() {
    List<PanelQuery> panel = gridPanel(8);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    // Five published lists of four ranks each, for eight queries of fifteen computed ranks.
    int[][] full = swappedTies(truth, RANKS);
    int[][] published = new int[5][];
    for (int q = 0; q < published.length; q++) {
      published[q] = Arrays.copyOf(full[q], 4);
    }
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, published), panel, truth, RANKS);
    assertTrue(r.agrees());
    assertEquals(5, r.queries());
    assertEquals(4, r.ranks());
  }

  @Test
  public void testCapsTheMismatchList() {
    List<PanelQuery> panel = gridPanel(30);
    float[][] base = grid();
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    int[][] published = swappedTies(truth, RANKS);
    for (int[] p : published) {
      p[RANKS - 1] = SIDE * SIDE - 1;
    }
    PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, published), panel, truth, RANKS);
    assertFalse(r.agrees());
    assertEquals(21, r.mismatches().size());
    assertEquals("30 queries disagree in total", r.mismatches().get(20));
  }

  @Test
  public void testTieTolerance() {
    // Two neighbors of the origin at distances 1 and 1 + 1.2e-7, which a float rounds to.
    float[][] base = { { 1f, 0f }, { 0f, 1.0000001f }, { 5f, 5f } };
    List<PanelQuery> panel = Collections
      .singletonList(new PanelQuery(0, new float[] { 0f, 0f }, PanelQuery.Kind.PUBLISHED, 0, -1));
    List<NeighborList> truth = computeTruth(new FakeCorpus(base, null), panel, Metric.L2);
    assertEquals(0, truth.get(0).ordinal(0));
    int[][] swapped = { { 1, 0, 2 } };
    assertTrue(new PublishedTruthValidator(Metric.L2, 1e-6)
      .validate(new FakeCorpus(base, swapped), panel, truth, 3).agrees());
    PublishedTruthValidator.Result strict = new PublishedTruthValidator(Metric.L2, 1e-9)
      .validate(new FakeCorpus(base, swapped), panel, truth, 3);
    assertFalse(strict.agrees());
    assertTrue(strict.mismatches().get(0), strict.mismatches().get(0).contains("query 0 rank 0"));
  }

  /** Writes a TEXMEX directory of byte vectors on a grid, with integer queries. */
  private Path texmex(List<PanelQuery> panel, int[][] groundTruth) throws IOException {
    Path dir = folder.newFolder("tiny").toPath();
    List<int[]> base = new ArrayList<>();
    for (float[] v : grid()) {
      base.add(new int[] { (int) v[0], (int) v[1] });
    }
    VecsFile.writeBvecs(dir.resolve("tiny_base.bvecs"), base);
    List<float[]> queries = new ArrayList<>();
    for (PanelQuery q : panel) {
      queries.add(q.vector());
    }
    VecsFile.writeFvecs(dir.resolve("tiny_query.fvecs"), queries);
    List<int[]> gt = new ArrayList<>();
    for (int[] g : groundTruth) {
      gt.add(g);
    }
    VecsFile.writeIvecs(dir.resolve("tiny_groundtruth.ivecs"), gt);
    return dir;
  }

  @Test
  public void testTexmexCorpusWithSwappedTies() throws IOException {
    List<PanelQuery> panel = gridPanel(9);
    List<NeighborList> expectedTruth = computeTruth(new FakeCorpus(grid(), null), panel, Metric.L2);
    int[][] published = swappedTies(expectedTruth, RANKS);
    Path dir = texmex(panel, published);
    HarnessConfig config = TruthTestSupport.config("corpus.source", "texmex", "corpus.path",
      dir.toString(), "corpus.body.bytes", "0");
    try (Corpus corpus = CorpusFactory.open(config)) {
      assertEquals(SIDE * SIDE, corpus.rows());
      Metric metric = CorpusFactory.effectiveMetric(config, corpus);
      assertEquals(Metric.L2, metric);
      List<PanelQuery> corpusPanel = corpus.panel(panel.size());
      assertEquals(panel.size(), corpusPanel.size());
      List<NeighborList> truth = computeTruth(corpus, corpusPanel, metric);
      PublishedTruthValidator validator = new PublishedTruthValidator(metric, 1e-6);
      PublishedTruthValidator.Result r = validator.validate(corpus, corpusPanel, truth, RANKS);
      assertTrue(r.mismatches().toString(), r.agrees());
      assertEquals(panel.size(), r.queries());
      assertEquals(RANKS, r.ranks());

      // Truth computed from a misread corpus disagrees with the published neighbors.
      float[][] shifted = grid();
      for (float[] v : shifted) {
        v[0] = v[0] * 1.5f;
      }
      List<NeighborList> wrong = computeTruth(new FakeCorpus(shifted, null), corpusPanel, metric);
      assertFalse(validator.validate(corpus, corpusPanel, wrong, RANKS).agrees());
    }
  }

  @Test
  public void testTexmexPrefixPublishesNoTruth() throws IOException {
    List<PanelQuery> panel = gridPanel(4);
    List<NeighborList> truth = computeTruth(new FakeCorpus(grid(), null), panel, Metric.L2);
    Path dir = texmex(panel, swappedTies(truth, RANKS));
    HarnessConfig config = TruthTestSupport.config("corpus.source", "texmex", "corpus.path",
      dir.toString(), "corpus.rows", "50", "corpus.body.bytes", "0");
    try (Corpus corpus = CorpusFactory.open(config)) {
      assertEquals(50, corpus.rows());
      // The published truth describes all 100 base rows, so it does not apply to 50 of them.
      assertNull(corpus.publishedNeighbors());
      PublishedTruthValidator.Result r = new PublishedTruthValidator(Metric.L2, 1e-6)
        .validate(corpus, corpus.panel(4), truth, RANKS);
      assertTrue(r.agrees());
      assertEquals(0, r.queries());
    }
    try (Corpus corpus = CorpusFactory.open(config.with("corpus.rows", "0"))) {
      assertNotNull(corpus.publishedNeighbors());
    }
  }
}
