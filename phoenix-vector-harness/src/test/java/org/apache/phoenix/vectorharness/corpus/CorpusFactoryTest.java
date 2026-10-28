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
import static org.junit.Assert.fail;

import io.jhdf.HdfFile;
import io.jhdf.WritableHdfFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.HarnessConfig.DriftMode;
import org.apache.phoenix.vectorharness.config.Metric;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CorpusFactoryTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static HarnessConfig config(String... kv) {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "500");
    p.setProperty("corpus.dim", "8");
    p.setProperty("corpus.clusters", "4");
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return HarnessConfig.of("factory", p);
  }

  private static double norm(float[] v) {
    double s = 0.0;
    for (float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }

  @Test
  public void testAttributeModelFollowsTheProfile() {
    HarnessConfig c = config("corpus.tenants", "7", "corpus.tenant.skew", "zipf:1.2",
      "corpus.filter.sources", "30", "corpus.filter.categories", "40", "corpus.filter.acl_tags",
      "5", "corpus.filter.skew", "0.9", "corpus.body.bytes", "12");
    AttributeModel m = CorpusFactory.attributeModel(c, new RandomStreams(c.seed()));
    assertEquals(7, m.tenants().size());
    assertEquals(1.2, m.tenants().exponent(), 0.0);
    assertEquals(30, m.sources().size());
    assertEquals(40, m.categories().size());
    assertEquals(5, m.aclTags().size());
    assertEquals(0.9, m.categories().exponent(), 0.0);
    assertEquals(0.9, m.sources().exponent(), 0.0);
    assertEquals(12, m.attributes(3).body().length());
  }

  @Test
  public void testSyntheticCorpusFollowsTheProfile() throws IOException {
    HarnessConfig c = config("corpus.intrinsic_dim", "3", "corpus.variance.spread", "2",
      "corpus.cluster.weights", "1.0", "corpus.noise", "0.1", "corpus.query.boundary_fraction",
      "0.1", "corpus.query.ood_fraction", "0.2", "corpus.drift.mode", "migrate",
      "corpus.drift.rate", "0.01", "corpus.seed", "9");
    try (Corpus corpus = CorpusFactory.open(c)) {
      assertTrue(corpus instanceof SyntheticCorpus);
      SyntheticCorpus s = (SyntheticCorpus) corpus;
      assertEquals(500L, s.rows());
      assertEquals(8, s.dim());
      Map<String, String> p = s.parameters();
      assertEquals("9", p.get("seed"));
      assertEquals("4", p.get("clusters"));
      assertEquals("1.0", p.get("cluster.zipf"));
      assertEquals("2.0", p.get("variance.spread"));
      assertEquals("3", p.get("intrinsic_dim"));
      assertEquals("0.1", p.get("noise"));
      assertEquals("true", p.get("normalize"));
      assertEquals("0.1", p.get("query.boundary_fraction"));
      assertEquals("0.2", p.get("query.ood_fraction"));
      assertEquals("MIGRATE", p.get("drift.mode"));
      assertEquals("0.01", p.get("drift.rate"));
      assertSame(Metric.COSINE, s.metric());
      // The same as constructing it directly
      RandomStreams streams = new RandomStreams(9L);
      SyntheticCorpus direct = new SyntheticCorpus(9L, 500, 8, 4, 1.0, 2.0, 3, 0.1, true, 0.1, 0.2,
        DriftMode.MIGRATE, 0.01, Metric.COSINE, CorpusFactory.attributeModel(c, streams));
      assertEquals(direct.fingerprint(), s.fingerprint());
      assertEquals(direct.panelDigest(50), s.panelDigest(50));
    }
  }

  @Test
  public void testSyntheticNormalizationFollowsTheMetric() throws IOException {
    try (Corpus cosine = CorpusFactory.open(config())) {
      SyntheticCorpus s = (SyntheticCorpus) cosine;
      assertSame(Metric.COSINE, s.metric());
      assertTrue(s.normalized());
      assertEquals(1.0, norm(s.vector(1)), 1e-5);
      assertSame(Metric.COSINE, CorpusFactory.effectiveMetric(config(), cosine));
    }
    HarnessConfig l2 = config("index.metric", "L2");
    try (Corpus corpus = CorpusFactory.open(l2)) {
      SyntheticCorpus s = (SyntheticCorpus) corpus;
      assertSame(Metric.L2, s.metric());
      assertFalse(s.normalized());
      assertSame(Metric.L2, CorpusFactory.effectiveMetric(l2, corpus));
    }
    HarnessConfig forced = config("index.metric", "L2", "corpus.normalize", "true");
    try (Corpus corpus = CorpusFactory.open(forced)) {
      assertTrue(((SyntheticCorpus) corpus).normalized());
    }
  }

  @Test
  public void testSameProfileSameCorpus() throws IOException {
    try (Corpus a = CorpusFactory.open(config()); Corpus b = CorpusFactory.open(config())) {
      assertEquals(a.fingerprint(), b.fingerprint());
      assertArrayEquals(a.vector(42), b.vector(42), 0.0f);
      assertEquals(a.attributes(42), b.attributes(42));
    }
    try (Corpus a = CorpusFactory.open(config());
      Corpus b = CorpusFactory.open(config("corpus.seed", "43"))) {
      assertNotEquals(a.fingerprint(), b.fingerprint());
    }
  }

  private Path texmexDir() throws IOException {
    Path dir = tmp.newFolder("sift").toPath();
    List<float[]> base = new ArrayList<>();
    SplitMix64 rnd = new SplitMix64(1L);
    for (int i = 0; i < 60; i++) {
      float[] v = new float[4];
      for (int j = 0; j < 4; j++) {
        v[j] = (float) rnd.nextDouble();
      }
      base.add(v);
    }
    VecsFile.writeFvecs(dir.resolve("sift_base.fvecs"), base);
    VecsFile.writeFvecs(dir.resolve("sift_query.fvecs"), base.subList(0, 5));
    return dir;
  }

  @Test
  public void testTexmexCorpus() throws IOException {
    Path dir = texmexDir();
    HarnessConfig c = config("corpus.source", "texmex", "corpus.path", dir.toString(),
      "corpus.rows", "1000000", "corpus.tenants", "3");
    assertTrue(c.validate().isEmpty());
    try (Corpus corpus = CorpusFactory.open(c)) {
      assertTrue(corpus instanceof TexmexCorpus);
      assertEquals("texmex:sift", corpus.sourceName());
      // corpus.rows beyond the base count loads every row
      assertEquals(60L, corpus.rows());
      assertEquals(4, corpus.dim());
      assertEquals(3, corpus.attributeModel().tenants().size());
      // A TEXMEX corpus is Euclidean unless the profile says otherwise
      assertSame(Metric.L2, CorpusFactory.effectiveMetric(c, corpus));
      assertSame(Metric.COSINE,
        CorpusFactory.effectiveMetric(c.with("index.metric", "cosine"), corpus));
    }
    try (
      Corpus corpus = CorpusFactory.open(c.with("corpus.rows", "25").with("corpus.name", "sift"))) {
      assertEquals(25L, corpus.rows());
    }
  }

  @Test
  public void testHdf5Corpus() throws IOException {
    Path file = tmp.getRoot().toPath().resolve("tiny-angular.hdf5");
    float[][] train = new float[30][3];
    for (int i = 0; i < 30; i++) {
      train[i] = new float[] { i, 1, -i };
    }
    try (WritableHdfFile w = HdfFile.write(file)) {
      w.putDataset("train", train);
      w.putDataset("test", new float[][] { { 1, 2, 3 }, { 4, 5, 6 } });
      w.putAttribute("distance", "angular");
    }
    HarnessConfig c =
      config("corpus.source", "hdf5", "corpus.path", file.toString(), "corpus.rows", "20");
    try (Corpus corpus = CorpusFactory.open(c)) {
      assertTrue(corpus instanceof Hdf5Corpus);
      assertEquals("hdf5:tiny-angular", corpus.sourceName());
      assertEquals(20L, corpus.rows());
      assertEquals(3, corpus.dim());
      assertSame(Metric.COSINE, CorpusFactory.effectiveMetric(c, corpus));
      assertSame(Metric.INNER_PRODUCT,
        CorpusFactory.effectiveMetric(c.with("index.metric", "ip"), corpus));
    }
  }

  @Test
  public void testPublishedCorpusNeedsAPath() {
    try {
      CorpusFactory.open(config("corpus.source", "texmex"));
      fail("Expected an exception");
    } catch (IOException | RuntimeException e) {
      // validate() reports the missing path; open() fails without it
      assertFalse(config("corpus.source", "texmex").validate().isEmpty());
    }
  }
}
