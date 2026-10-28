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
package org.apache.phoenix.vectorharness.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.HarnessConfig.CorpusSource;
import org.apache.phoenix.vectorharness.config.HarnessConfig.DriftMode;
import org.apache.phoenix.vectorharness.config.HarnessConfig.IngestMode;
import org.apache.phoenix.vectorharness.config.HarnessConfig.JmxMode;
import org.apache.phoenix.vectorharness.config.HarnessConfig.QueryMode;
import org.apache.phoenix.vectorharness.config.HarnessConfig.SampleMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HarnessConfigTest {

  private static final double EPS = 1e-12;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static Properties props(String... kv) {
    if (kv.length % 2 != 0) {
      throw new IllegalArgumentException("Odd number of key and value arguments");
    }
    Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }

  private static HarnessConfig config(String... kv) {
    return HarnessConfig.of("test", props(kv));
  }

  private static HarnessConfig defaults() {
    return config();
  }

  // Defaults

  @Test
  public void testRunDefaults() {
    HarnessConfig c = defaults();
    assertEquals(VariantKind.IVF, c.variant());
    assertEquals(1, c.repetitions());
    assertFalse(c.deterministic());
    assertNull(c.runId());
    assertEquals(Paths.get("vector-harness-output"), c.outputDir());
    assertEquals("jdbc:phoenix:localhost", c.jdbcUrl());
    assertEquals("test", c.profileName());
  }

  @Test
  public void testCorpusDefaults() {
    HarnessConfig c = defaults();
    assertEquals(CorpusSource.SYNTHETIC, c.corpusSource());
    assertEquals(42L, c.seed());
    assertNull(c.corpusPath());
    assertNull(c.corpusName());
    assertEquals(1000000L, c.rows());
    assertEquals(768, c.dim());
    assertEquals(200, c.clusters());
    assertEquals(0.0, c.clusterZipf(), EPS);
    assertEquals(1.0, c.varianceSpread(), EPS);
    assertEquals(768, c.intrinsicDim());
    assertEquals(0.05, c.noise(), EPS);
    assertEquals(0.0, c.boundaryFraction(), EPS);
    assertEquals(0.0, c.oodFraction(), EPS);
    assertEquals(DriftMode.NONE, c.driftMode());
    assertEquals(0.0, c.driftRate(), EPS);
    assertEquals(0.0, c.filterSkew(), EPS);
    assertEquals(1000, c.categories());
    assertEquals(1000, c.sources());
    assertEquals(16, c.aclTags());
    assertEquals(1, c.tenants());
    assertEquals(0.0, c.tenantSkew(), EPS);
    assertEquals(128, c.bodyBytes());
  }

  @Test
  public void testTableDefaults() {
    HarnessConfig c = defaults();
    assertEquals("VH", c.schema());
    assertEquals("IVF_TEST", c.tableSuffix());
    assertEquals(EnumSet.of(TableLayout.CORPUS, TableLayout.PROFILE), c.layouts());
    assertFalse(c.multiTenant());
    assertEquals(0, c.saltBuckets());
    assertEquals(16, c.regions());
    assertEquals("ONE_CELL_PER_COLUMN", c.storageScheme());
    assertTrue(c.updateStatistics());
    assertFalse(c.snapshot());
  }

  @Test
  public void testIndexDefaults() {
    HarnessConfig c = defaults();
    assertNull(c.explicitMetric());
    assertEquals(Duration.ofHours(2), c.buildTimeout());
    assertEquals(1024, c.ivfLists());
    assertEquals(50000, c.ivfSampleSize());
    assertFalse(c.ivfAsync());
    assertEquals(16, c.hnswM());
    assertEquals(100, c.hnswEfConstruction());
    assertEquals(1.2, c.hnswAlpha(), EPS);
    assertEquals("NONE", c.hnswQuantization());
    assertEquals(0, c.hnswPqSegments());
  }

  @Test
  public void testIngestDefaults() {
    HarnessConfig c = defaults();
    assertEquals(1.0, c.prebuildFraction(), EPS);
    assertEquals(IngestMode.JDBC, c.ingestMode());
    assertEquals(4, c.ingestThreads());
    assertEquals(500, c.ingestBatchSize());
    assertEquals(0.0, c.streamRate(), EPS);
  }

  @Test
  public void testQueryDefaults() {
    HarnessConfig c = defaults();
    assertArrayEquals(new int[] { 10, 100 }, c.kValues());
    assertArrayEquals(new int[] { 1, 4, 16, 64, HarnessConfig.PROBE_ALL }, c.ivfProbeValues());
    assertEquals(-1, HarnessConfig.PROBE_ALL);
    assertEquals(8, c.ivfMaxProbeLimit());
    assertArrayEquals(new int[] { 16, 32, 64, 128, 256, 512 }, c.hnswEfValues());
    assertArrayEquals(new double[] { 0.001, 0.01, 0.1, 1.0 }, c.filterSelectivities(), EPS);
    assertEquals(EnumSet.allOf(QueryShape.class), c.shapes());
    assertEquals(1.0, c.tenantScopedFraction(), EPS);
    assertEquals(QueryMode.OPEN, c.queryMode());
    assertArrayEquals(new double[] { 20.0 }, c.queryRates(), EPS);
    assertEquals(8, c.queryThreads());
    assertEquals(Duration.ofSeconds(60), c.queryTimeout());
    assertEquals(0, c.sweepPanelSize());
    assertEquals(0.5, c.distanceBoundRank(), EPS);
    assertEquals(0.25, c.pkRangeFraction(), EPS);
  }

  @Test
  public void testMutationDefaults() {
    HarnessConfig c = defaults();
    assertEquals(0.0, c.mutationRate(), EPS);
    assertArrayEquals(new double[] { 0.3, 0.5, 0.2 }, c.mutationMix(), EPS);
    assertEquals(0.0, c.moveFraction(), EPS);
    assertEquals(0.3, c.attributeFraction(), EPS);
    assertEquals(0, c.hotspotRegions());
    assertEquals(2, c.mutationThreads());
  }

  @Test
  public void testSteadyStateAndRecallDefaults() {
    HarnessConfig c = defaults();
    assertEquals(Duration.ofMinutes(1), c.warmup());
    assertEquals(Duration.ofMinutes(10), c.duration());
    assertFalse(c.rebuildAutoEnabled());
    assertNull(c.rebuildExplicitAt());
    assertEquals(1000, c.panelSize());
    assertEquals(Duration.ofSeconds(30), c.sampleInterval());
    assertEquals(SampleMode.QUIESCED, c.sampleMode());
    assertEquals(100, c.sampleQueries());
    assertEquals(1e-6, c.tieEpsilon(), 0.0);
    assertEquals(2.0, c.truthMargin(), EPS);
    assertEquals(1000, c.bootstrapResamples());
    assertEquals(1.0, c.freshnessRate(), EPS);
  }

  @Test
  public void testObservationAndDisruptionDefaults() {
    HarnessConfig c = defaults();
    assertEquals(Duration.ofSeconds(15), c.observeInterval());
    assertNull(c.fsckInterval());
    assertTrue(c.fsckEnabled());
    assertEquals(JmxMode.AUTO, c.jmxMode());
    assertEquals(0, c.jmxPort());
    assertEquals(Duration.ofSeconds(11), c.jmxCacheTtl());
    assertTrue(c.disruptions().isEmpty());
  }

  /**
   * Gives every setting whose default is a literal value explicitly, set to its default text, and
   * checks that every typed accessor returns the same value as with no settings at all.
   */
  @Test
  public void testAccessorDefaultsMatchSettingDefaultText() {
    Properties explicit = new Properties();
    List<String> derived = new ArrayList<>();
    for (Setting s : Setting.values()) {
      String d = s.defaultText();
      if (d == null) {
        continue;
      }
      if (
        s == Setting.CORPUS_INTRINSIC_DIM || s == Setting.CORPUS_NORMALIZE
          || s == Setting.INDEX_METRIC
      ) {
        // Derived defaults; their default text names the dependency.
        derived.add(s.key());
        continue;
      }
      explicit.setProperty(s.key(), d);
    }
    assertEquals(Arrays.asList("corpus.intrinsic_dim", "corpus.normalize", "index.metric"),
      derived);
    HarnessConfig withDefaults = HarnessConfig.of("test", explicit);
    assertEquals(snapshot(defaults()), snapshot(withDefaults));
    assertTrue(withDefaults.validate().isEmpty());
  }

  /** Returns the values of every typed accessor, with arrays boxed into lists. */
  private static Map<String, Object> snapshot(HarnessConfig c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("variant", c.variant());
    m.put("repetitions", c.repetitions());
    m.put("deterministic", c.deterministic());
    m.put("runId", c.runId());
    m.put("outputDir", c.outputDir());
    m.put("jdbcUrl", c.jdbcUrl());
    m.put("corpusSource", c.corpusSource());
    m.put("seed", c.seed());
    m.put("corpusPath", c.corpusPath());
    m.put("corpusName", c.corpusName());
    m.put("rows", c.rows());
    m.put("dim", c.dim());
    m.put("clusters", c.clusters());
    m.put("clusterZipf", c.clusterZipf());
    m.put("varianceSpread", c.varianceSpread());
    m.put("intrinsicDim", c.intrinsicDim());
    m.put("noise", c.noise());
    for (Metric metric : Metric.values()) {
      m.put("normalize." + metric, c.normalize(metric));
      m.put("metric." + metric, c.metric(metric));
    }
    m.put("metric.null", c.metric(null));
    m.put("boundaryFraction", c.boundaryFraction());
    m.put("oodFraction", c.oodFraction());
    m.put("driftMode", c.driftMode());
    m.put("driftRate", c.driftRate());
    m.put("filterSkew", c.filterSkew());
    m.put("categories", c.categories());
    m.put("sources", c.sources());
    m.put("aclTags", c.aclTags());
    m.put("tenants", c.tenants());
    m.put("tenantSkew", c.tenantSkew());
    m.put("bodyBytes", c.bodyBytes());
    m.put("schema", c.schema());
    m.put("tableSuffix", c.tableSuffix());
    m.put("layouts", c.layouts());
    m.put("multiTenant", c.multiTenant());
    m.put("saltBuckets", c.saltBuckets());
    m.put("regions", c.regions());
    m.put("storageScheme", c.storageScheme());
    m.put("updateStatistics", c.updateStatistics());
    m.put("snapshot", c.snapshot());
    m.put("explicitMetric", c.explicitMetric());
    m.put("buildTimeout", c.buildTimeout());
    m.put("ivfLists", c.ivfLists());
    m.put("ivfSampleSize", c.ivfSampleSize());
    m.put("ivfAsync", c.ivfAsync());
    m.put("hnswM", c.hnswM());
    m.put("hnswEfConstruction", c.hnswEfConstruction());
    m.put("hnswAlpha", c.hnswAlpha());
    m.put("hnswQuantization", c.hnswQuantization());
    m.put("hnswPqSegments", c.hnswPqSegments());
    m.put("prebuildFraction", c.prebuildFraction());
    m.put("prebuildRows", c.prebuildRows(12345));
    m.put("ingestMode", c.ingestMode());
    m.put("ingestThreads", c.ingestThreads());
    m.put("ingestBatchSize", c.ingestBatchSize());
    m.put("streamRate", c.streamRate());
    m.put("kValues", boxed(c.kValues()));
    m.put("ivfProbeValues", boxed(c.ivfProbeValues()));
    m.put("ivfMaxProbeLimit", c.ivfMaxProbeLimit());
    m.put("hnswEfValues", boxed(c.hnswEfValues()));
    m.put("filterSelectivities", boxed(c.filterSelectivities()));
    m.put("shapes", c.shapes());
    m.put("tenantScopedFraction", c.tenantScopedFraction());
    m.put("queryMode", c.queryMode());
    m.put("queryRates", boxed(c.queryRates()));
    m.put("queryThreads", c.queryThreads());
    m.put("queryTimeout", c.queryTimeout());
    m.put("sweepPanelSize", c.sweepPanelSize());
    m.put("distanceBoundRank", c.distanceBoundRank());
    m.put("pkRangeFraction", c.pkRangeFraction());
    m.put("mutationRate", c.mutationRate());
    m.put("mutationMix", boxed(c.mutationMix()));
    m.put("moveFraction", c.moveFraction());
    m.put("attributeFraction", c.attributeFraction());
    m.put("hotspotRegions", c.hotspotRegions());
    m.put("mutationThreads", c.mutationThreads());
    m.put("warmup", c.warmup());
    m.put("duration", c.duration());
    m.put("rebuildAutoEnabled", c.rebuildAutoEnabled());
    m.put("rebuildExplicitAt", c.rebuildExplicitAt());
    m.put("panelSize", c.panelSize());
    m.put("sampleInterval", c.sampleInterval());
    m.put("sampleMode", c.sampleMode());
    m.put("sampleQueries", c.sampleQueries());
    m.put("tieEpsilon", c.tieEpsilon());
    m.put("truthMargin", c.truthMargin());
    m.put("bootstrapResamples", c.bootstrapResamples());
    m.put("freshnessRate", c.freshnessRate());
    m.put("observeInterval", c.observeInterval());
    m.put("fsckInterval", c.fsckInterval());
    m.put("fsckEnabled", c.fsckEnabled());
    m.put("jmxMode", c.jmxMode());
    m.put("jmxPort", c.jmxPort());
    m.put("jmxCacheTtl", c.jmxCacheTtl());
    m.put("disruptions", c.disruptions().toString());
    return m;
  }

  private static List<Integer> boxed(int[] a) {
    List<Integer> out = new ArrayList<>();
    for (int x : a) {
      out.add(x);
    }
    return out;
  }

  private static List<Double> boxed(double[] a) {
    List<Double> out = new ArrayList<>();
    for (double x : a) {
      out.add(x);
    }
    return out;
  }

  // Derived defaults

  @Test
  public void testIntrinsicDimDefaultsToDim() {
    assertEquals(768, defaults().intrinsicDim());
    assertEquals(64, config("corpus.dim", "64").intrinsicDim());
    assertEquals(64, config("corpus.dim", "64", "corpus.intrinsic_dim", "DIM").intrinsicDim());
    assertEquals(8, config("corpus.dim", "64", "corpus.intrinsic_dim", "8").intrinsicDim());
  }

  @Test
  public void testNormalizeDefaultDependsOnMetric() {
    HarnessConfig c = defaults();
    assertFalse(c.isSet(Setting.CORPUS_NORMALIZE));
    assertTrue(c.normalize(Metric.COSINE));
    assertFalse(c.normalize(Metric.L2));
    assertFalse(c.normalize(Metric.INNER_PRODUCT));
    HarnessConfig on = config("corpus.normalize", "true");
    assertTrue(on.normalize(Metric.L2));
    assertTrue(on.normalize(Metric.COSINE));
    HarnessConfig off = config("corpus.normalize", "no");
    assertFalse(off.normalize(Metric.COSINE));
  }

  @Test
  public void testMetricPrecedence() {
    HarnessConfig c = defaults();
    assertEquals(Metric.COSINE, c.metric(null));
    assertEquals(Metric.L2, c.metric(Metric.L2));
    assertEquals(Metric.INNER_PRODUCT, c.metric(Metric.INNER_PRODUCT));
    HarnessConfig explicit = config("index.metric", "l2");
    assertEquals(Metric.L2, explicit.explicitMetric());
    assertEquals(Metric.L2, explicit.metric(null));
    assertEquals(Metric.L2, explicit.metric(Metric.COSINE));
    assertEquals(Metric.INNER_PRODUCT, config("index.metric", "dot").metric(Metric.L2));
    assertEquals(Metric.COSINE, config("index.metric", "angular").metric(Metric.L2));
  }

  @Test
  public void testTableSuffix() {
    assertEquals("IVF_TEST", defaults().tableSuffix());
    assertEquals("HNSW_TEST", config("run.variant", "hnsw").tableSuffix());
    assertEquals("EXACT_MY_PROFILE_V2",
      HarnessConfig.of("my-profile.v2", props("run.variant", "exact")).tableSuffix());
    assertEquals("CUSTOM_1", config("table.suffix", "custom-1").tableSuffix());
    // The profile name setting overrides the given name
    assertEquals("IVF_NAMED",
      HarnessConfig.of("ignored", props("run.profile.name", "named")).tableSuffix());
  }

  @Test
  public void testSanitizeIdentifier() {
    assertEquals("ABC_123", HarnessConfig.sanitizeIdentifier("abc_123"));
    assertEquals("A_B_C_D", HarnessConfig.sanitizeIdentifier("a-b.c d"));
    assertEquals("CAF_", HarnessConfig.sanitizeIdentifier("caf\u00e9"));
    assertEquals("", HarnessConfig.sanitizeIdentifier(""));
  }

  @Test
  public void testPrebuildRows() {
    assertEquals(1000L, defaults().prebuildRows(1000));
    assertEquals(250L, config("ingest.prebuild.fraction", "0.25").prebuildRows(1000));
    assertEquals(333L, config("ingest.prebuild.fraction", "0.3333").prebuildRows(1000));
    // At least one row is loaded before the build
    assertEquals(1L, config("ingest.prebuild.fraction", "0.0001").prebuildRows(10));
    assertEquals(10L, config("ingest.prebuild.fraction", "1.5").prebuildRows(10));
  }

  @Test
  public void testDeterministicModeOverrides() {
    HarnessConfig c =
      config("run.deterministic", "true", "mutation.threads", "6", "recall.sample.mode", "online");
    assertTrue(c.deterministic());
    assertEquals(1, c.mutationThreads());
    assertEquals(SampleMode.QUIESCED, c.sampleMode());
    HarnessConfig online = config("mutation.threads", "6", "recall.sample.mode", "online");
    assertEquals(6, online.mutationThreads());
    assertEquals(SampleMode.ONLINE, online.sampleMode());
  }

  // Parsing

  @Test
  public void testExplicitValues() {
    HarnessConfig c = config("run.variant", " HNSW ", "corpus.rows", "1_000", "corpus.seed", "-7",
      "corpus.source", "texmex", "corpus.path", "/data/sift", "corpus.name", "sift",
      "corpus.drift.mode", "birth-death", "ingest.mode", "BULKLOAD", "query.mode", "closed",
      "observe.jmx.mode", "http", "observe.jmx.port", "16030", "hnsw.pq_segments", "8",
      "table.schema", "vh2", "table.storage_scheme", "single_cell_array_with_offsets",
      "hnsw.quantization", "sq8", "run.id", "r1", "run.output.dir", "/tmp/out");
    assertEquals(VariantKind.HNSW, c.variant());
    assertEquals(1000L, c.rows());
    assertEquals(-7L, c.seed());
    assertEquals(CorpusSource.TEXMEX, c.corpusSource());
    assertEquals(Paths.get("/data/sift"), c.corpusPath());
    assertEquals("sift", c.corpusName());
    assertEquals(DriftMode.BIRTH_DEATH, c.driftMode());
    assertEquals(IngestMode.BULKLOAD, c.ingestMode());
    assertEquals(QueryMode.CLOSED, c.queryMode());
    assertEquals(JmxMode.HTTP, c.jmxMode());
    assertEquals(16030, c.jmxPort());
    assertEquals(8, c.hnswPqSegments());
    assertEquals("VH2", c.schema());
    assertEquals("SINGLE_CELL_ARRAY_WITH_OFFSETS", c.storageScheme());
    assertEquals("SQ8", c.hnswQuantization());
    assertEquals("r1", c.runId());
    assertEquals(Paths.get("/tmp/out"), c.outputDir());
  }

  @Test
  public void testBooleanAliases() {
    for (String t : new String[] { "true", "TRUE", "yes", "on" }) {
      assertTrue(t, config("table.multi_tenant", t).multiTenant());
    }
    for (String t : new String[] { "false", "False", "no", "off" }) {
      assertFalse(t, config("table.multi_tenant", t).multiTenant());
    }
    try {
      config("table.multi_tenant", "maybe").multiTenant();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("maybe"));
    }
  }

  @Test
  public void testZipfValues() {
    assertEquals(0.0, config("corpus.filter.skew", "uniform").filterSkew(), EPS);
    assertEquals(0.0, config("corpus.filter.skew", "UNIFORM").filterSkew(), EPS);
    assertEquals(1.1, config("corpus.filter.skew", "1.1").filterSkew(), EPS);
    assertEquals(0.8, config("corpus.cluster.weights", "zipf:0.8").clusterZipf(), EPS);
    assertEquals(1.2, config("corpus.tenant.skew", "Zipf(1.2)").tenantSkew(), EPS);
    assertEquals(0.0, config("corpus.tenant.skew", "0").tenantSkew(), EPS);
  }

  @Test
  public void testProbeValues() {
    assertArrayEquals(new int[] { 2, HarnessConfig.PROBE_ALL, 8 },
      config("query.ivf.probe.values", " 2 , ALL ,8,").ivfProbeValues());
    assertArrayEquals(new int[] { HarnessConfig.PROBE_ALL },
      config("query.ivf.probe.values", "all").ivfProbeValues());
  }

  @Test
  public void testListValues() {
    assertArrayEquals(new int[] { 1, 5, 1000 }, config("query.k.values", "1, 5,1_000").kValues());
    assertArrayEquals(new double[] { 0.5, 1.0 },
      config("query.filter.selectivity", "0.5,,1.0").filterSelectivities(), EPS);
    assertArrayEquals(new double[] { 5, 10, 20 },
      config("query.rate.values", "5,10,20", "query.rate.per_sec", "99").queryRates(), EPS);
    assertArrayEquals(new double[] { 99 }, config("query.rate.per_sec", "99").queryRates(), EPS);
    // Digits group with underscores in number lists as in single numbers.
    HarnessConfig grouped =
      config("query.rate.values", "1_000,2_000", "report.recall.targets", "0.9_5");
    assertArrayEquals(new double[] { 1000, 2000 }, grouped.queryRates(), EPS);
    assertArrayEquals(new double[] { 0.95 }, grouped.reportRecallTargets(), EPS);
    assertTrue(grouped.validate().isEmpty());
    assertEquals(EnumSet.of(QueryShape.UNFILTERED, QueryShape.PK_RANGE),
      config("query.shapes", "unfiltered,pk-range").shapes());
    assertEquals(EnumSet.of(TableLayout.CORPUS), config("table.layouts", "corpus").layouts());
  }

  @Test
  public void testMutationMixIsNormalized() {
    assertArrayEquals(new double[] { 0.25, 0.25, 0.5 },
      config("mutation.mix", "1,1,2").mutationMix(), EPS);
    assertArrayEquals(new double[] { 1.0, 0.0, 0.0 }, config("mutation.mix", "3,0,0").mutationMix(),
      EPS);
    try {
      config("mutation.mix", "1,2").mutationMix();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("mutation.mix"));
    }
    try {
      config("mutation.mix", "0,0,0").mutationMix();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("mutation.mix"));
    }
  }

  @Test
  public void testDurationsAndIntervals() {
    HarnessConfig c = config("steady_state.warmup", "90s", "steady_state.duration", "1.5h",
      "rebuild.explicit.at", "20m", "observe.fsck.interval", "2m", "query.timeout", "500ms");
    assertEquals(Duration.ofSeconds(90), c.warmup());
    assertEquals(Duration.ofMinutes(90), c.duration());
    assertEquals(Duration.ofMinutes(20), c.rebuildExplicitAt());
    assertEquals(Duration.ofMinutes(2), c.fsckInterval());
    assertTrue(c.fsckEnabled());
    assertEquals(Duration.ofMillis(500), c.queryTimeout());
    HarnessConfig off = config("observe.fsck.interval", "OFF");
    assertEquals(Duration.ZERO, off.fsckInterval());
    assertFalse(off.fsckEnabled());
    assertNull(config("observe.fsck.interval", "Stage").fsckInterval());
  }

  @Test
  public void testDisruptions() {
    HarnessConfig c = config("disruption.schedule", "5m:compact, 2m:split");
    List<Disruption> d = c.disruptions();
    assertEquals(2, d.size());
    assertEquals(Disruption.Kind.SPLIT, d.get(0).kind());
    assertEquals(Duration.ofMinutes(2), d.get(0).at());
    assertEquals(Disruption.Kind.COMPACT, d.get(1).kind());
    assertTrue(c.validate().isEmpty());
  }

  // Properties handling

  @Test
  public void testOfTrimsAndCopies() {
    Properties p = props("corpus.dim", " 32 ");
    HarnessConfig c = HarnessConfig.of(null, p);
    assertEquals("default", c.profileName());
    assertEquals(32, c.dim());
    p.setProperty("corpus.dim", "64");
    assertEquals(32, c.dim());
  }

  @Test
  public void testRawGetAndIsSet() {
    HarnessConfig c = config("corpus.dim", "", "custom.key", "v");
    assertFalse(c.isSet(Setting.CORPUS_DIM));
    assertEquals("768", c.raw(Setting.CORPUS_DIM));
    assertEquals(768, c.dim());
    assertNull(c.get("corpus.dim"));
    assertEquals("v", c.get("custom.key"));
    assertNull(c.get("missing.key"));
    assertNull(c.raw(Setting.RUN_ID));
    assertTrue(config("corpus.dim", "8").isSet(Setting.CORPUS_DIM));
  }

  @Test
  public void testWithReturnsCopy() {
    HarnessConfig c = config("corpus.dim", "32");
    HarnessConfig d = c.with(Setting.CORPUS_DIM, "64");
    assertEquals(32, c.dim());
    assertEquals(64, d.dim());
    assertEquals(c.profileName(), d.profileName());
    HarnessConfig e = d.with("corpus.dim", null);
    assertEquals(768, e.dim());
    assertEquals(64, d.dim());
    assertFalse(e.explicitKeys().contains("corpus.dim"));
  }

  @Test
  public void testExplicitKeysUnmodifiable() {
    HarnessConfig c = config("corpus.dim", "32");
    Set<String> keys = c.explicitKeys();
    assertEquals(1, keys.size());
    try {
      keys.add("x");
      fail("Expected an exception");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testPrefixAndClientProperties() {
    HarnessConfig c = config("phoenix.query.timeoutMs", "1000", "hbase.rpc.timeout", "2000",
      "corpus.dim", "32", "other.key", "x");
    Properties client = c.clientProperties();
    assertEquals(2, client.size());
    assertEquals("1000", client.getProperty("phoenix.query.timeoutMs"));
    assertEquals("2000", client.getProperty("hbase.rpc.timeout"));
    Properties corpus = c.withPrefix("corpus.");
    assertEquals(1, corpus.size());
    assertEquals("32", corpus.getProperty("dim"));
    assertTrue(c.withPrefix("nothing.").isEmpty());
  }

  @Test
  public void testEffectiveSettings() {
    HarnessConfig c = config("corpus.dim", "32", "phoenix.x", "y");
    Map<String, String> eff = c.effectiveSettings();
    for (Setting s : Setting.values()) {
      assertTrue(s.key(), eff.containsKey(s.key()));
    }
    assertEquals("32", eff.get("corpus.dim"));
    assertEquals("32", eff.get("corpus.intrinsic_dim"));
    assertEquals("test", eff.get("run.profile.name"));
    assertEquals("ivf", eff.get("run.variant"));
    assertEquals("", eff.get("run.id"));
    assertEquals("y", eff.get("phoenix.x"));
    // Sorted by key
    List<String> keys = new ArrayList<>(eff.keySet());
    List<String> sorted = new ArrayList<>(keys);
    sorted.sort(null);
    assertEquals(sorted, keys);
  }

  @Test
  public void testLoadAppliesOverridesAndNamesProfile() throws IOException {
    Path file = tmp.newFile("sift-ivf.properties").toPath();
    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      w.write("# A test profile\ncorpus.dim = 128\ncorpus.rows=5000\nrun.variant=hnsw\n");
    }
    HarnessConfig c = HarnessConfig.load(file, props("corpus.rows", "10"));
    assertEquals("sift-ivf", c.profileName());
    assertEquals(128, c.dim());
    assertEquals(10L, c.rows());
    assertEquals(VariantKind.HNSW, c.variant());
    assertEquals("HNSW_SIFT_IVF", c.tableSuffix());
    HarnessConfig noOverrides = HarnessConfig.load(file, null);
    assertEquals(5000L, noOverrides.rows());

    Path named = tmp.newFile("x.y.properties").toPath();
    Files.write(named, "run.profile.name=explicit\n".getBytes(StandardCharsets.UTF_8));
    assertEquals("explicit", HarnessConfig.load(named, null).profileName());
    Path dotted = tmp.newFile("x.y.properties.txt").toPath();
    assertEquals("x.y.properties", HarnessConfig.load(dotted, null).profileName());
  }

  @Test
  public void testLoadResource() throws IOException {
    HarnessConfig c =
      HarnessConfig.loadResource("vectorharness/unit-test-profile.properties", null);
    assertEquals("unit-test-profile", c.profileName());
    assertEquals(16, c.dim());
    assertEquals(2000L, c.rows());
    assertTrue(c.validate().isEmpty());
    HarnessConfig o = HarnessConfig.loadResource("vectorharness/unit-test-profile.properties",
      props("corpus.dim", "8"));
    assertEquals(8, o.dim());
    try {
      HarnessConfig.loadResource("vectorharness/no-such-profile.properties", null);
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("no-such-profile"));
    }
  }

  @Test
  public void testToString() {
    assertEquals("HarnessConfig[test]", defaults().toString());
  }

  // Validation

  @Test
  public void testDefaultProfileIsValid() {
    HarnessConfig c = defaults();
    assertEquals(new ArrayList<String>(), c.validate());
    c.validateOrThrow();
  }

  @Test
  public void testValidPublishedProfile() {
    HarnessConfig c = config("corpus.source", "hdf5", "corpus.path", "/data/glove.hdf5",
      "corpus.intrinsic_dim", "100000");
    assertEquals(new ArrayList<String>(), c.validate());
  }

  /** Each entry is a bad setting, its value, and the key that the problem must name. */
  private static final String[][] BAD_VALUES = { { "run.variant", "ivfpq", "run.variant" },
    { "run.repetitions", "0", "run.repetitions" }, { "run.repetitions", "x", "run.repetitions" },
    { "run.deterministic", "sometimes", "run.deterministic" },
    { "corpus.source", "parquet", "corpus.source" }, { "corpus.source", "texmex", "corpus.path" },
    { "corpus.source", "hdf5", "corpus.path" }, { "corpus.seed", "seed", "corpus.seed" },
    { "corpus.rows", "0", "corpus.rows" }, { "corpus.rows", "many", "corpus.rows" },
    { "corpus.dim", "-1", "corpus.dim" }, { "corpus.dim", "1.5", "corpus.dim" },
    { "corpus.clusters", "0", "corpus.clusters" },
    { "corpus.cluster.weights", "zipf:-1", "corpus.cluster.weights" },
    { "corpus.cluster.weights", "heavy", "corpus.cluster.weights" },
    { "corpus.variance.spread", "0.5", "corpus.variance.spread" },
    { "corpus.variance.spread", "x", "corpus.variance.spread" },
    { "corpus.intrinsic_dim", "0", "corpus.intrinsic_dim" },
    { "corpus.intrinsic_dim", "769", "corpus.intrinsic_dim" },
    { "corpus.intrinsic_dim", "half", "corpus.intrinsic_dim" },
    { "corpus.noise", "-0.1", "corpus.noise" },
    { "corpus.normalize", "perhaps", "corpus.normalize" },
    { "corpus.query.boundary_fraction", "1.5", "corpus.query.boundary_fraction" },
    { "corpus.query.ood_fraction", "-0.1", "corpus.query.ood_fraction" },
    { "corpus.drift.mode", "sideways", "corpus.drift.mode" },
    { "corpus.drift.rate", "fast", "corpus.drift.rate" },
    { "corpus.filter.skew", "zipf(abc)", "corpus.filter.skew" },
    { "corpus.filter.categories", "0", "corpus.filter.categories" },
    { "corpus.filter.sources", "0", "corpus.filter.sources" },
    { "corpus.filter.acl_tags", "0", "corpus.filter.acl_tags" },
    { "corpus.tenants", "0", "corpus.tenants" },
    { "corpus.tenant.skew", "-2", "corpus.tenant.skew" },
    { "corpus.body.bytes", "lots", "corpus.body.bytes" },
    { "table.layouts", "columnar", "table.layouts" }, { "table.layouts", ",", "table.layouts" },
    { "table.multi_tenant", "maybe", "table.multi_tenant" },
    { "table.salt_buckets", "300", "table.salt_buckets" },
    { "table.salt_buckets", "-1", "table.salt_buckets" }, { "table.regions", "0", "table.regions" },
    { "table.storage_scheme", "COLUMNAR", "table.storage_scheme" },
    { "table.update_statistics", "x", "table.update_statistics" },
    { "table.snapshot", "x", "table.snapshot" }, { "index.metric", "hamming", "index.metric" },
    { "index.build.timeout", "forever", "index.build.timeout" },
    { "ivf.lists", "many", "ivf.lists" }, { "ivf.sample_size", "x", "ivf.sample_size" },
    { "ivf.async", "x", "ivf.async" }, { "hnsw.m", "x", "hnsw.m" },
    { "hnsw.ef_construction", "x", "hnsw.ef_construction" }, { "hnsw.alpha", "x", "hnsw.alpha" },
    { "hnsw.pq_segments", "x", "hnsw.pq_segments" },
    { "ingest.prebuild.fraction", "0", "ingest.prebuild.fraction" },
    { "ingest.prebuild.fraction", "1.5", "ingest.prebuild.fraction" },
    { "ingest.mode", "ftp", "ingest.mode" }, { "ingest.threads", "0", "ingest.threads" },
    { "ingest.batch.size", "0", "ingest.batch.size" },
    { "ingest.stream.rate.per_sec", "x", "ingest.stream.rate.per_sec" },
    { "query.k.values", "10,0", "query.k.values" }, { "query.k.values", "10,x", "query.k.values" },
    { "query.ivf.probe.values", "0", "query.ivf.probe.values" },
    { "query.ivf.probe.values", "1,two", "query.ivf.probe.values" },
    { "query.ivf.max_probe_limit", "x", "query.ivf.max_probe_limit" },
    { "query.hnsw.ef.values", "16,-1", "query.hnsw.ef.values" },
    { "query.filter.selectivity", "0", "query.filter.selectivity" },
    { "query.filter.selectivity", "0.1,1.5", "query.filter.selectivity" },
    { "query.filter.selectivity", "x", "query.filter.selectivity" },
    { "query.shapes", "unfiltered,bogus", "query.shapes" },
    { "query.tenant_scoped.fraction", "2", "query.tenant_scoped.fraction" },
    { "query.mode", "half-open", "query.mode" },
    { "query.rate.values", "10,0", "query.rate.values" },
    { "query.rate.values", "10,x", "query.rate.values" },
    { "query.rate.per_sec", "-1", "query.rate.per_sec" }, { "query.threads", "0", "query.threads" },
    { "query.timeout", "never", "query.timeout" },
    { "query.sweep.panel_size", "x", "query.sweep.panel_size" },
    { "query.distance_bound.rank", "1.5", "query.distance_bound.rank" },
    { "query.pk_range.fraction", "0", "query.pk_range.fraction" },
    { "query.pk_range.fraction", "1.01", "query.pk_range.fraction" },
    { "mutation.rate.per_sec", "-1", "mutation.rate.per_sec" },
    { "mutation.mix", "1,2", "mutation.mix" }, { "mutation.mix", "0,0,0", "mutation.mix" },
    { "mutation.mix", "-1,1,1", "mutation.mix" }, { "mutation.mix", "a,b,c", "mutation.mix" },
    { "mutation.update.move_fraction", "1.5", "mutation.update.move_fraction" },
    { "mutation.update.attribute_fraction", "-0.5", "mutation.update.attribute_fraction" },
    { "mutation.hotspot.regions", "x", "mutation.hotspot.regions" },
    { "mutation.threads", "x", "mutation.threads" },
    { "steady_state.warmup", "soon", "steady_state.warmup" },
    { "steady_state.duration", "0", "steady_state.duration" },
    { "steady_state.duration", "-5m", "steady_state.duration" },
    { "rebuild.auto.enabled", "x", "rebuild.auto.enabled" },
    { "rebuild.explicit.at", "20m", "rebuild.explicit.at" },
    { "rebuild.explicit.at", "later", "rebuild.explicit.at" },
    { "recall.panel.size", "0", "recall.panel.size" },
    { "recall.sample.interval", "0s", "recall.sample.interval" },
    { "recall.sample.mode", "sometimes", "recall.sample.mode" },
    { "recall.sample.queries", "x", "recall.sample.queries" },
    { "recall.tie_epsilon", "-1", "recall.tie_epsilon" },
    { "recall.truth.margin", "0.5", "recall.truth.margin" },
    { "recall.bootstrap.resamples", "x", "recall.bootstrap.resamples" },
    { "freshness.rate.per_sec", "x", "freshness.rate.per_sec" },
    { "observe.interval", "0", "observe.interval" },
    { "observe.jmx.cache_ttl", "x", "observe.jmx.cache_ttl" },
    { "observe.fsck.interval", "sometimes", "observe.fsck.interval" },
    { "observe.jmx.mode", "snmp", "observe.jmx.mode" },
    { "observe.jmx.port", "x", "observe.jmx.port" },
    { "disruption.schedule", "20m:split", "disruption.schedule" },
    { "disruption.schedule", "2m:explode", "disruption.schedule" },
    { "disruption.schedule", "split", "disruption.schedule" }, };

  @Test
  public void testValidateReportsBadValues() {
    for (String[] bad : BAD_VALUES) {
      HarnessConfig c = config(bad[0], bad[1]);
      List<String> problems = c.validate();
      String label = bad[0] + "=" + bad[1] + " -> " + problems;
      assertFalse(label, problems.isEmpty());
      boolean named = false;
      for (String p : problems) {
        named |= p.contains(bad[2]);
      }
      assertTrue(label, named);
      try {
        c.validateOrThrow();
        fail("Expected an exception for " + label);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("test"));
        assertTrue(e.getMessage(), e.getMessage().contains(bad[2]));
      }
    }
  }

  @Test
  public void testValidateReportsCombinedProblems() {
    List<String> p =
      config("corpus.query.boundary_fraction", "0.6", "corpus.query.ood_fraction", "0.6")
        .validate();
    assertEquals(1, p.size());
    assertTrue(p.get(0).contains("corpus.query.boundary_fraction"));
    assertTrue(p.get(0).contains("corpus.query.ood_fraction"));
    p = config("mutation.update.move_fraction", "0.8").validate();
    assertEquals(1, p.size());
    assertTrue(p.get(0).contains("mutation.update.move_fraction"));
    assertTrue(p.get(0).contains("mutation.update.attribute_fraction"));
  }

  @Test
  public void testValidateReportsUnrealizableSelectivities() {
    // A uniform vocabulary of 100 terms realizes multiples of 0.01 only.
    List<String> p = config("corpus.filter.categories", "100").validate();
    assertEquals(p.toString(), 1, p.size());
    assertTrue(p.get(0), p.get(0).contains("query.filter.selectivity 0.001"));
    assertTrue(p.get(0), p.get(0).contains("corpus.filter.categories=100"));
    assertTrue(p.get(0), p.get(0).contains("0.01"));
    p = config("corpus.filter.sources", "100", "query.filter.selectivity", "0.002,0.01").validate();
    assertEquals(p.toString(), 1, p.size());
    assertTrue(p.get(0), p.get(0).contains("0.002"));
    assertTrue(p.get(0), p.get(0).contains("corpus.filter.sources=100"));
    // Off the grid of 1 / size, a selectivity is rounded: 0.0015 of 1000 terms would run 0.002.
    p = config("query.filter.selectivity", "0.0015").validate();
    assertEquals(p.toString(), 2, p.size());
    // The vocabulary of a shape that does not run is not checked.
    assertTrue(
      config("corpus.filter.categories", "100", "query.shapes", "unfiltered," + "uncovered_filter")
        .validate().isEmpty());
    // Skewed vocabularies realize small selectivities from their rare tail.
    assertTrue(config("corpus.filter.skew", "zipf:1.1").validate().isEmpty());
    assertTrue(
      config("corpus.filter.categories", "100", "query.filter.selectivity", "0.01,0.05,1.0")
        .validate().isEmpty());
  }

  @Test
  public void testValidateRejectsMovesWithOneCluster() {
    List<String> p =
      config("corpus.clusters", "1", "mutation.update.move_fraction", "0.2").validate();
    assertEquals(p.toString(), 1, p.size());
    assertTrue(p.get(0), p.get(0).contains("mutation.update.move_fraction"));
    p = config("corpus.clusters", "1", "corpus.drift.mode", "reassign", "corpus.drift.rate", "0.1")
      .validate();
    assertEquals(p.toString(), 1, p.size());
    assertTrue(p.get(0), p.get(0).contains("reassign"));
    assertTrue(config("corpus.clusters", "1").validate().isEmpty());
    assertTrue(
      config("corpus.clusters", "2", "mutation.update.move_fraction", "0.2").validate().isEmpty());
  }

  @Test
  public void testValidateReportsEverySyntaxError() {
    List<String> p = config("corpus.dim", "x", "corpus.rows", "y", "run.variant", "z").validate();
    for (String key : new String[] { "run.variant", "corpus.rows", "corpus.dim" }) {
      boolean found = false;
      for (String problem : p) {
        found |= problem.startsWith(key + ":");
      }
      assertTrue(key + " in " + p, found);
    }
  }

  @Test
  public void testValidateAcceptsBoundaries() {
    assertTrue(config("corpus.query.boundary_fraction", "0.5", "corpus.query.ood_fraction", "0.5")
      .validate().isEmpty());
    assertTrue(config("table.salt_buckets", "256").validate().isEmpty());
    assertTrue(config("corpus.intrinsic_dim", "1").validate().isEmpty());
    assertTrue(config("corpus.intrinsic_dim", "768").validate().isEmpty());
    assertTrue(config("rebuild.explicit.at", "9m").validate().isEmpty());
    assertTrue(config("disruption.schedule", "9m59s:merge").validate().isEmpty());
    assertTrue(config("query.ivf.probe.values", "all").validate().isEmpty());
    assertTrue(config("corpus.normalize", "on").validate().isEmpty());
  }

  @Test
  public void testParseErrorsCarryTheValue() {
    try {
      config("corpus.dim", "abc").dim();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("abc"));
    }
    try {
      config("corpus.drift.mode", "sideways").driftMode();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("sideways"));
      assertTrue(e.getMessage().contains("birth_death"));
    }
  }
}
