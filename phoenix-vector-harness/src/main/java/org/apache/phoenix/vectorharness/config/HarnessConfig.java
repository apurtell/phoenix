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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.apache.phoenix.vectorharness.corpus.Vocabulary;

/**
 * Configuration profile combining properties file settings and command line overrides, providing
 * typed accessors and default value resolution. Preserves custom properties (e.g. {@code phoenix.*}
 * client settings) for passthrough to connections.
 */
public final class HarnessConfig {

  /** Corpus source type. */
  public enum CorpusSource {
    SYNTHETIC,
    TEXMEX,
    HDF5
  }

  /** Synthetic corpus geometric drift modes during steady state execution. */
  public enum DriftMode {
    NONE,
    MIGRATE,
    BIRTH_DEATH,
    REASSIGN
  }

  /** Ingestion modes. */
  public enum IngestMode {
    JDBC,
    BULKLOAD
  }

  /** Query dispatch modes. */
  public enum QueryMode {
    /** Open loop dispatch with fixed arrival rate. */
    OPEN,
    /** Closed loop dispatch where workers issue queries back-to-back. */
    CLOSED
  }

  /** Recall sampling modes. */
  public enum SampleMode {
    QUIESCED,
    ONLINE
  }

  /** RegionServer JMX metrics collection modes. */
  public enum JmxMode {
    /** Auto-detect: HTTP if info ports are exposed, otherwise in-process registry. */
    AUTO,
    /** RegionServer HTTP /jmx servlet endpoint. */
    HTTP,
    /** Local platform MBeanServer. */
    LOCAL,
    /** In-process Hadoop metrics2 registry, used for minicluster tests. */
    REGISTRY,
    /** Disabled */
    OFF
  }

  /** Sentinel value representing exhaustive probing of all centroids. */
  public static final int PROBE_ALL = -1;

  private final String profileName;
  private final Properties props;

  private HarnessConfig(String profileName, Properties props) {
    this.profileName = profileName;
    this.props = props;
  }

  /** Creates a configuration profile from property definitions. */
  public static HarnessConfig of(String profileName, Properties props) {
    Properties copy = new Properties();
    for (String k : props.stringPropertyNames()) {
      copy.setProperty(k, props.getProperty(k).trim());
    }
    String name = copy.getProperty(Setting.RUN_PROFILE_NAME.key());
    if (name == null || name.isEmpty()) {
      name = profileName == null ? "default" : profileName;
    }
    return new HarnessConfig(name, copy);
  }

  /** Loads a profile file and applies property overrides. */
  public static HarnessConfig load(Path file, Properties overrides) throws IOException {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    if (overrides != null) {
      for (String k : overrides.stringPropertyNames()) {
        p.setProperty(k, overrides.getProperty(k));
      }
    }
    String base = file.getFileName().toString();
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      base = base.substring(0, dot);
    }
    return of(base, p);
  }

  /** Loads a profile from a classpath resource. */
  public static HarnessConfig loadResource(String resource, Properties overrides)
    throws IOException {
    Properties p = new Properties();
    try (InputStream in = HarnessConfig.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("No such resource: " + resource);
      }
      p.load(in);
    }
    if (overrides != null) {
      for (String k : overrides.stringPropertyNames()) {
        p.setProperty(k, overrides.getProperty(k));
      }
    }
    String base = Paths.get(resource).getFileName().toString();
    int dot = base.lastIndexOf('.');
    return of(dot > 0 ? base.substring(0, dot) : base, p);
  }

  /** Returns a copy of this profile with the specified setting replaced. */
  public HarnessConfig with(String key, String value) {
    Properties copy = new Properties();
    copy.putAll(props);
    if (value == null) {
      copy.remove(key);
    } else {
      copy.setProperty(key, value);
    }
    return new HarnessConfig(profileName, copy);
  }

  public HarnessConfig with(Setting setting, String value) {
    return with(setting.key(), value);
  }

  public String profileName() {
    return profileName;
  }

  /** Returns true if the setting was explicitly provided. */
  public boolean isSet(Setting s) {
    String v = props.getProperty(s.key());
    return v != null && !v.isEmpty();
  }

  /** Returns the setting value or its default string representation. */
  public String raw(Setting s) {
    String v = props.getProperty(s.key());
    if (v == null || v.isEmpty()) {
      return s.defaultText();
    }
    return v;
  }

  /** Returns the property value for a key or null if unset. */
  public String get(String key) {
    String v = props.getProperty(key);
    return v == null || v.isEmpty() ? null : v;
  }

  /** Returns properties starting with a prefix, stripping the prefix. */
  public Properties withPrefix(String prefix) {
    Properties out = new Properties();
    for (String k : props.stringPropertyNames()) {
      if (k.startsWith(prefix)) {
        out.setProperty(k.substring(prefix.length()), props.getProperty(k));
      }
    }
    return out;
  }

  /**
   * Returns Phoenix and HBase client connection properties (prefixed with {@code phoenix.} or
   * {@code hbase.}).
   */
  public Properties clientProperties() {
    Properties out = new Properties();
    for (String k : props.stringPropertyNames()) {
      if (k.startsWith("phoenix.") || k.startsWith("hbase.")) {
        out.setProperty(k, props.getProperty(k));
      }
    }
    return out;
  }

  /** Returns a map of all effective settings and explicit properties sorted by key. */
  public Map<String, String> effectiveSettings() {
    Map<String, String> out = new TreeMap<>();
    for (String k : props.stringPropertyNames()) {
      out.put(k, props.getProperty(k));
    }
    for (Setting s : Setting.values()) {
      if (!out.containsKey(s.key())) {
        String v = s.defaultText();
        out.put(s.key(), v == null ? "" : v);
      }
    }
    out.put(Setting.CORPUS_INTRINSIC_DIM.key(), Integer.toString(intrinsicDim()));
    out.put(Setting.RUN_PROFILE_NAME.key(), profileName);
    return out;
  }

  // Run

  public VariantKind variant() {
    return VariantKind.parse(raw(Setting.RUN_VARIANT));
  }

  public int repetitions() {
    return intValue(Setting.RUN_REPETITIONS);
  }

  public boolean deterministic() {
    return boolValue(Setting.RUN_DETERMINISTIC);
  }

  /** Returns the explicit run identifier, or null if omitted. */
  public String runId() {
    return get(Setting.RUN_ID.key());
  }

  public Path outputDir() {
    return Paths.get(raw(Setting.RUN_OUTPUT_DIR));
  }

  public String jdbcUrl() {
    return raw(Setting.JDBC_URL);
  }

  // Corpus

  public CorpusSource corpusSource() {
    return parseEnum(CorpusSource.class, Setting.CORPUS_SOURCE);
  }

  public long seed() {
    return longValue(Setting.CORPUS_SEED);
  }

  /** Returns the path to the published corpus dataset, or null. */
  public Path corpusPath() {
    String v = get(Setting.CORPUS_PATH.key());
    return v == null ? null : Paths.get(v);
  }

  /** Returns the TEXMEX corpus prefix, or null to auto-detect. */
  public String corpusName() {
    return get(Setting.CORPUS_NAME.key());
  }

  public long rows() {
    return longValue(Setting.CORPUS_ROWS);
  }

  public int dim() {
    return intValue(Setting.CORPUS_DIM);
  }

  public int clusters() {
    return intValue(Setting.CORPUS_CLUSTERS);
  }

  /** Returns the Zipf exponent for cluster weights (0.0 for uniform). */
  public double clusterZipf() {
    return zipfValue(Setting.CORPUS_CLUSTER_WEIGHTS);
  }

  public double varianceSpread() {
    return doubleValue(Setting.CORPUS_VARIANCE_SPREAD);
  }

  /** Returns the intrinsic subspace dimension, defaulting to the ambient dimension. */
  public int intrinsicDim() {
    String v = raw(Setting.CORPUS_INTRINSIC_DIM);
    if (v.equalsIgnoreCase("dim")) {
      return dim();
    }
    return parseInt(Setting.CORPUS_INTRINSIC_DIM, v);
  }

  public double noise() {
    return doubleValue(Setting.CORPUS_NOISE);
  }

  /** Returns true if vectors should be normalized to the unit sphere. */
  public boolean normalize(Metric effectiveMetric) {
    if (isSet(Setting.CORPUS_NORMALIZE)) {
      return boolValue(Setting.CORPUS_NORMALIZE);
    }
    return effectiveMetric.normalizesByDefault();
  }

  public double boundaryFraction() {
    return doubleValue(Setting.CORPUS_QUERY_BOUNDARY_FRACTION);
  }

  public double oodFraction() {
    return doubleValue(Setting.CORPUS_QUERY_OOD_FRACTION);
  }

  public DriftMode driftMode() {
    return parseEnum(DriftMode.class, Setting.CORPUS_DRIFT_MODE);
  }

  public double driftRate() {
    return doubleValue(Setting.CORPUS_DRIFT_RATE);
  }

  /** Returns the Zipf exponent for attribute vocabularies (0.0 for uniform). */
  public double filterSkew() {
    return zipfValue(Setting.CORPUS_FILTER_SKEW);
  }

  public int categories() {
    return intValue(Setting.CORPUS_FILTER_CATEGORIES);
  }

  public int sources() {
    return intValue(Setting.CORPUS_FILTER_SOURCES);
  }

  public int aclTags() {
    return intValue(Setting.CORPUS_FILTER_ACL_TAGS);
  }

  public int tenants() {
    return intValue(Setting.CORPUS_TENANTS);
  }

  public double tenantSkew() {
    return zipfValue(Setting.CORPUS_TENANT_SKEW);
  }

  public int bodyBytes() {
    return intValue(Setting.CORPUS_BODY_BYTES);
  }

  // Table

  public String schema() {
    return raw(Setting.TABLE_SCHEMA).toUpperCase(Locale.ROOT);
  }

  /** Returns the suffix for table names, defaulting to {@code <variant>_<profileName>}. */
  public String tableSuffix() {
    String v = get(Setting.TABLE_SUFFIX.key());
    if (v == null) {
      v = variant().id() + "_" + profileName;
    }
    return sanitizeIdentifier(v);
  }

  public Set<TableLayout> layouts() {
    return TableLayout.parseList(raw(Setting.TABLE_LAYOUTS));
  }

  public boolean multiTenant() {
    return boolValue(Setting.TABLE_MULTI_TENANT);
  }

  public int saltBuckets() {
    return intValue(Setting.TABLE_SALT_BUCKETS);
  }

  public int regions() {
    return intValue(Setting.TABLE_REGIONS);
  }

  public String storageScheme() {
    return raw(Setting.TABLE_STORAGE_SCHEME).toUpperCase(Locale.ROOT);
  }

  public boolean updateStatistics() {
    return boolValue(Setting.TABLE_UPDATE_STATISTICS);
  }

  public boolean snapshot() {
    return boolValue(Setting.TABLE_SNAPSHOT);
  }

  // Index

  /** Returns the explicitly configured metric, or null to infer from corpus. */
  public Metric explicitMetric() {
    String v = get(Setting.INDEX_METRIC.key());
    return v == null ? null : Metric.parse(v);
  }

  /** Returns the effective distance metric, falling back to corpus default or COSINE. */
  public Metric metric(Metric corpusDefault) {
    Metric m = explicitMetric();
    if (m != null) {
      return m;
    }
    return corpusDefault != null ? corpusDefault : Metric.COSINE;
  }

  public Duration buildTimeout() {
    return durationValue(Setting.INDEX_BUILD_TIMEOUT);
  }

  public int ivfLists() {
    return intValue(Setting.IVF_LISTS);
  }

  public int ivfSampleSize() {
    return intValue(Setting.IVF_SAMPLE_SIZE);
  }

  public boolean ivfAsync() {
    return boolValue(Setting.IVF_ASYNC);
  }

  public int hnswM() {
    return intValue(Setting.HNSW_M);
  }

  public int hnswEfConstruction() {
    return intValue(Setting.HNSW_EF_CONSTRUCTION);
  }

  public double hnswAlpha() {
    return doubleValue(Setting.HNSW_ALPHA);
  }

  public String hnswQuantization() {
    return raw(Setting.HNSW_QUANTIZATION).toUpperCase(Locale.ROOT);
  }

  /** Returns the PQ segment count, or 0 if unset. */
  public int hnswPqSegments() {
    return isSet(Setting.HNSW_PQ_SEGMENTS) ? intValue(Setting.HNSW_PQ_SEGMENTS) : 0;
  }

  // Ingest

  public double prebuildFraction() {
    return doubleValue(Setting.INGEST_PREBUILD_FRACTION);
  }

  /** Returns the number of initial rows loaded prior to index build. */
  public long prebuildRows(long corpusRows) {
    double f = prebuildFraction();
    if (f >= 1.0) {
      return corpusRows;
    }
    return Math.max(1L, (long) Math.floor(corpusRows * f));
  }

  public IngestMode ingestMode() {
    return parseEnum(IngestMode.class, Setting.INGEST_MODE);
  }

  public int ingestThreads() {
    return intValue(Setting.INGEST_THREADS);
  }

  public int ingestBatchSize() {
    return intValue(Setting.INGEST_BATCH_SIZE);
  }

  public double streamRate() {
    return doubleValue(Setting.INGEST_STREAM_RATE);
  }

  // Query

  public int[] kValues() {
    return intList(Setting.QUERY_K_VALUES);
  }

  /** Returns the IVF probe counts to sweep, with {@link #PROBE_ALL} representing all. */
  public int[] ivfProbeValues() {
    String v = raw(Setting.QUERY_IVF_PROBE_VALUES);
    List<Integer> out = new ArrayList<>();
    for (String part : v.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) {
        continue;
      }
      if (p.equalsIgnoreCase("all")) {
        out.add(PROBE_ALL);
      } else {
        out.add(parseInt(Setting.QUERY_IVF_PROBE_VALUES, p));
      }
    }
    return out.stream().mapToInt(Integer::intValue).toArray();
  }

  public int ivfMaxProbeLimit() {
    return intValue(Setting.QUERY_IVF_MAX_PROBE_LIMIT);
  }

  public int[] hnswEfValues() {
    return intList(Setting.QUERY_HNSW_EF_VALUES);
  }

  public double[] filterSelectivities() {
    return doubleList(Setting.QUERY_FILTER_SELECTIVITY);
  }

  public Set<QueryShape> shapes() {
    return QueryShape.parseList(raw(Setting.QUERY_SHAPES));
  }

  public double tenantScopedFraction() {
    return doubleValue(Setting.QUERY_TENANT_SCOPED_FRACTION);
  }

  public QueryMode queryMode() {
    return parseEnum(QueryMode.class, Setting.QUERY_MODE);
  }

  /** Returns the offered query rates for open-loop evaluation. */
  public double[] queryRates() {
    if (isSet(Setting.QUERY_RATE_VALUES)) {
      return doubleList(Setting.QUERY_RATE_VALUES);
    }
    return new double[] { doubleValue(Setting.QUERY_RATE) };
  }

  public int queryThreads() {
    return intValue(Setting.QUERY_THREADS);
  }

  public Duration queryTimeout() {
    return durationValue(Setting.QUERY_TIMEOUT);
  }

  public int sweepPanelSize() {
    return intValue(Setting.QUERY_SWEEP_PANEL_SIZE);
  }

  public double distanceBoundRank() {
    return doubleValue(Setting.QUERY_DISTANCE_BOUND_RANK);
  }

  public double pkRangeFraction() {
    return doubleValue(Setting.QUERY_PK_RANGE_FRACTION);
  }

  // Mutation

  public double mutationRate() {
    return doubleValue(Setting.MUTATION_RATE);
  }

  /** Returns the normalized mutation mix shares [insert, update, delete]. */
  public double[] mutationMix() {
    double[] mix = doubleList(Setting.MUTATION_MIX);
    if (mix.length != 3) {
      throw new IllegalArgumentException(
        Setting.MUTATION_MIX.key() + " needs three shares: insert, update, delete");
    }
    double sum = mix[0] + mix[1] + mix[2];
    if (sum <= 0) {
      throw new IllegalArgumentException(Setting.MUTATION_MIX.key() + " shares sum to zero");
    }
    return new double[] { mix[0] / sum, mix[1] / sum, mix[2] / sum };
  }

  public double moveFraction() {
    return doubleValue(Setting.MUTATION_UPDATE_MOVE_FRACTION);
  }

  public double attributeFraction() {
    return doubleValue(Setting.MUTATION_UPDATE_ATTRIBUTE_FRACTION);
  }

  public int hotspotRegions() {
    return intValue(Setting.MUTATION_HOTSPOT_REGIONS);
  }

  /** Returns the mutation thread count (forced to 1 in deterministic mode). */
  public int mutationThreads() {
    return deterministic() ? 1 : intValue(Setting.MUTATION_THREADS);
  }

  // Steady state

  public Duration warmup() {
    return durationValue(Setting.STEADY_STATE_WARMUP);
  }

  public Duration duration() {
    return durationValue(Setting.STEADY_STATE_DURATION);
  }

  public boolean rebuildAutoEnabled() {
    return boolValue(Setting.REBUILD_AUTO_ENABLED);
  }

  /** Returns the duration offset at which an explicit rebuild is scheduled, or null. */
  public Duration rebuildExplicitAt() {
    return isSet(Setting.REBUILD_EXPLICIT_AT) ? durationValue(Setting.REBUILD_EXPLICIT_AT) : null;
  }

  // Recall

  public int panelSize() {
    return intValue(Setting.RECALL_PANEL_SIZE);
  }

  public Duration sampleInterval() {
    return durationValue(Setting.RECALL_SAMPLE_INTERVAL);
  }

  /** Returns the recall sampling mode (forced to QUIESCED in deterministic mode). */
  public SampleMode sampleMode() {
    return deterministic()
      ? SampleMode.QUIESCED
      : parseEnum(SampleMode.class, Setting.RECALL_SAMPLE_MODE);
  }

  public int sampleQueries() {
    return intValue(Setting.RECALL_SAMPLE_QUERIES);
  }

  /** Returns sorted recall targets for reporting. */
  public double[] reportRecallTargets() {
    double[] t = doubleList(Setting.REPORT_RECALL_TARGETS);
    Arrays.sort(t);
    return t;
  }

  /** Returns sorted p99 latency budgets in milliseconds for reporting. */
  public double[] reportLatencyBudgetsMillis() {
    List<Double> out = new ArrayList<>();
    for (String part : raw(Setting.REPORT_LATENCY_BUDGETS).split(",")) {
      if (!part.trim().isEmpty()) {
        out.add((double) Durations.parse(part.trim()).toMillis());
      }
    }
    double[] b = new double[out.size()];
    for (int i = 0; i < b.length; i++) {
      b[i] = out.get(i);
    }
    Arrays.sort(b);
    return b;
  }

  public double tieEpsilon() {
    return doubleValue(Setting.RECALL_TIE_EPSILON);
  }

  public double truthMargin() {
    return doubleValue(Setting.RECALL_TRUTH_MARGIN);
  }

  public int bootstrapResamples() {
    return intValue(Setting.RECALL_BOOTSTRAP_RESAMPLES);
  }

  public double freshnessRate() {
    return doubleValue(Setting.FRESHNESS_RATE);
  }

  // Observation

  public Duration observeInterval() {
    return durationValue(Setting.OBSERVE_INTERVAL);
  }

  /** Returns the HNSW indexfsck inspection interval (null for stage, ZERO for disabled). */
  public Duration fsckInterval() {
    String v = raw(Setting.OBSERVE_FSCK_INTERVAL).trim().toLowerCase(Locale.ROOT);
    if (v.equals("stage")) {
      return null;
    }
    if (v.equals("off")) {
      return Duration.ZERO;
    }
    return Durations.parse(v);
  }

  public boolean fsckEnabled() {
    Duration d = fsckInterval();
    return d == null || !d.isZero();
  }

  public JmxMode jmxMode() {
    return parseEnum(JmxMode.class, Setting.OBSERVE_JMX_MODE);
  }

  /** Returns the configured RegionServer info port, or 0 to auto detect. */
  public int jmxPort() {
    return isSet(Setting.OBSERVE_JMX_PORT) ? intValue(Setting.OBSERVE_JMX_PORT) : 0;
  }

  public List<Disruption> disruptions() {
    return Disruption.parseSchedule(get(Setting.DISRUPTION_SCHEDULE.key()));
  }

  /**
   * Validates profile settings that can be checked independently of corpus data or cluster state.
   * @return list of validation error descriptions, empty if valid
   */
  public List<String> validate() {
    List<String> problems = new ArrayList<>();
    for (Setting s : Setting.values()) {
      try {
        parseCheck(s);
      } catch (RuntimeException e) {
        problems.add(s.key() + ": " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      return problems;
    }
    CorpusSource source = corpusSource();
    if (source != CorpusSource.SYNTHETIC && corpusPath() == null) {
      problems.add(Setting.CORPUS_PATH.key() + " is required for corpus.source="
        + source.name().toLowerCase(Locale.ROOT));
    }
    positive(problems, Setting.CORPUS_ROWS, rows());
    positive(problems, Setting.CORPUS_DIM, dim());
    positive(problems, Setting.CORPUS_CLUSTERS, clusters());
    positive(problems, Setting.RECALL_PANEL_SIZE, panelSize());
    positive(problems, Setting.CORPUS_TENANTS, tenants());
    positive(problems, Setting.CORPUS_FILTER_CATEGORIES, categories());
    positive(problems, Setting.CORPUS_FILTER_SOURCES, sources());
    positive(problems, Setting.CORPUS_FILTER_ACL_TAGS, aclTags());
    positive(problems, Setting.INGEST_THREADS, ingestThreads());
    positive(problems, Setting.INGEST_BATCH_SIZE, ingestBatchSize());
    positive(problems, Setting.QUERY_THREADS, queryThreads());
    positive(problems, Setting.RUN_REPETITIONS, repetitions());
    positive(problems, Setting.TABLE_REGIONS, regions());
    if (source == CorpusSource.SYNTHETIC) {
      int id = intrinsicDim();
      if (id < 1 || id > dim()) {
        problems.add(Setting.CORPUS_INTRINSIC_DIM.key() + " must be between 1 and corpus.dim");
      }
    }
    if (varianceSpread() < 1.0) {
      problems.add(Setting.CORPUS_VARIANCE_SPREAD.key() + " must be at least 1.0");
    }
    if (noise() < 0) {
      problems.add(Setting.CORPUS_NOISE.key() + " must not be negative");
    }
    fraction(problems, Setting.CORPUS_QUERY_BOUNDARY_FRACTION, boundaryFraction());
    fraction(problems, Setting.CORPUS_QUERY_OOD_FRACTION, oodFraction());
    if (boundaryFraction() + oodFraction() > 1.0) {
      problems.add("corpus.query.boundary_fraction plus corpus.query.ood_fraction exceeds 1");
    }
    double pre = prebuildFraction();
    if (pre <= 0.0 || pre > 1.0) {
      problems.add(Setting.INGEST_PREBUILD_FRACTION.key() + " must be in (0, 1]");
    }
    fraction(problems, Setting.QUERY_TENANT_SCOPED_FRACTION, tenantScopedFraction());
    fraction(problems, Setting.MUTATION_UPDATE_MOVE_FRACTION, moveFraction());
    fraction(problems, Setting.MUTATION_UPDATE_ATTRIBUTE_FRACTION, attributeFraction());
    if (moveFraction() + attributeFraction() > 1.0) {
      problems
        .add("mutation.update.move_fraction plus mutation.update.attribute_fraction exceeds 1");
    }
    if (source == CorpusSource.SYNTHETIC && clusters() == 1) {
      if (moveFraction() > 0) {
        problems.add(
          Setting.MUTATION_UPDATE_MOVE_FRACTION.key() + " must be 0 when corpus.clusters is 1");
      }
      if (driftMode() == DriftMode.REASSIGN && driftRate() > 0) {
        problems.add("corpus.drift.mode=reassign needs more than one cluster");
      }
    }
    fraction(problems, Setting.QUERY_DISTANCE_BOUND_RANK, distanceBoundRank());
    if (pkRangeFraction() <= 0.0 || pkRangeFraction() > 1.0) {
      problems.add(Setting.QUERY_PK_RANGE_FRACTION.key() + " must be in (0, 1]");
    }
    for (double s : filterSelectivities()) {
      if (s <= 0.0 || s > 1.0) {
        problems.add(Setting.QUERY_FILTER_SELECTIVITY.key() + " values must be in (0, 1]");
        break;
      }
    }
    Set<QueryShape> shapes = shapes();
    if (shapes.contains(QueryShape.COVERED_FILTER) && categories() > 0) {
      realizable(problems, Setting.CORPUS_FILTER_CATEGORIES, categories());
    }
    if (shapes.contains(QueryShape.UNCOVERED_FILTER) && sources() > 0) {
      realizable(problems, Setting.CORPUS_FILTER_SOURCES, sources());
    }
    for (int k : kValues()) {
      if (k <= 0) {
        problems.add(Setting.QUERY_K_VALUES.key() + " values must be positive");
        break;
      }
    }
    for (int p : ivfProbeValues()) {
      if (p <= 0 && p != PROBE_ALL) {
        problems.add(Setting.QUERY_IVF_PROBE_VALUES.key() + " values must be positive or all");
        break;
      }
    }
    for (int ef : hnswEfValues()) {
      if (ef <= 0) {
        problems.add(Setting.QUERY_HNSW_EF_VALUES.key() + " values must be positive");
        break;
      }
    }
    for (double r : queryRates()) {
      if (r <= 0) {
        Setting rate =
          isSet(Setting.QUERY_RATE_VALUES) ? Setting.QUERY_RATE_VALUES : Setting.QUERY_RATE;
        problems.add(rate.key() + " must be positive");
        break;
      }
    }
    if (mutationRate() < 0) {
      problems.add(Setting.MUTATION_RATE.key() + " must not be negative");
    }
    try {
      double[] mix = mutationMix();
      if (mix[0] < 0 || mix[1] < 0 || mix[2] < 0) {
        problems.add(Setting.MUTATION_MIX.key() + " shares must not be negative");
      }
    } catch (IllegalArgumentException e) {
      problems.add(e.getMessage());
    }
    if (saltBuckets() < 0 || saltBuckets() > 256) {
      problems.add(Setting.TABLE_SALT_BUCKETS.key() + " must be between 0 and 256");
    }
    String scheme = storageScheme();
    if (!scheme.equals("ONE_CELL_PER_COLUMN") && !scheme.equals("SINGLE_CELL_ARRAY_WITH_OFFSETS")) {
      problems.add(Setting.TABLE_STORAGE_SCHEME.key()
        + " must be ONE_CELL_PER_COLUMN or SINGLE_CELL_ARRAY_WITH_OFFSETS");
    }
    if (layouts().isEmpty()) {
      problems.add(Setting.TABLE_LAYOUTS.key() + " names no layout");
    }
    if (truthMargin() < 1.0) {
      problems.add(Setting.RECALL_TRUTH_MARGIN.key() + " must be at least 1");
    }
    if (tieEpsilon() < 0) {
      problems.add(Setting.RECALL_TIE_EPSILON.key() + " must not be negative");
    }
    if (duration().isZero()) {
      problems.add(Setting.STEADY_STATE_DURATION.key() + " must be positive");
    }
    if (sampleInterval().isZero()) {
      problems.add(Setting.RECALL_SAMPLE_INTERVAL.key() + " must be positive");
    }
    if (observeInterval().isZero()) {
      problems.add(Setting.OBSERVE_INTERVAL.key() + " must be positive");
    }
    Duration at = rebuildExplicitAt();
    if (at != null && at.compareTo(duration()) >= 0) {
      problems.add(Setting.REBUILD_EXPLICIT_AT.key() + " falls after the measured duration");
    }
    for (Disruption d : disruptions()) {
      if (d.at().compareTo(duration()) >= 0) {
        problems.add(Setting.DISRUPTION_SCHEDULE.key() + ": disruption " + d
          + " falls after the measured duration");
      }
    }
    return problems;
  }

  /** Validates the profile and throws {@link IllegalArgumentException} if invalid. */
  public void validateOrThrow() {
    List<String> problems = validate();
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
        "Invalid profile " + profileName + ":\n  " + String.join("\n  ", problems));
    }
  }

  /** Validates the format and type of an individual setting. */
  private void parseCheck(Setting s) {
    switch (s) {
      case RUN_VARIANT:
        variant();
        break;
      case CORPUS_SOURCE:
        corpusSource();
        break;
      case CORPUS_DRIFT_MODE:
        driftMode();
        break;
      case INGEST_MODE:
        ingestMode();
        break;
      case QUERY_MODE:
        queryMode();
        break;
      case RECALL_SAMPLE_MODE:
        parseEnum(SampleMode.class, Setting.RECALL_SAMPLE_MODE);
        break;
      case OBSERVE_JMX_MODE:
        jmxMode();
        break;
      case QUERY_SHAPES:
        shapes();
        break;
      case TABLE_LAYOUTS:
        layouts();
        break;
      case INDEX_METRIC:
        explicitMetric();
        break;
      case CORPUS_INTRINSIC_DIM:
        intrinsicDim();
        break;
      case QUERY_IVF_PROBE_VALUES:
        ivfProbeValues();
        break;
      case DISRUPTION_SCHEDULE:
        disruptions();
        break;
      case OBSERVE_FSCK_INTERVAL:
        fsckInterval();
        break;
      case CORPUS_CLUSTER_WEIGHTS:
      case CORPUS_FILTER_SKEW:
      case CORPUS_TENANT_SKEW:
        zipfValue(s);
        break;
      case QUERY_K_VALUES:
      case QUERY_HNSW_EF_VALUES:
        intList(s);
        break;
      case QUERY_FILTER_SELECTIVITY:
      case MUTATION_MIX:
      case REPORT_RECALL_TARGETS:
        doubleList(s);
        break;
      case REPORT_LATENCY_BUDGETS:
        reportLatencyBudgetsMillis();
        break;
      case QUERY_RATE_VALUES:
        if (isSet(s)) {
          doubleList(s);
        }
        break;
      case STEADY_STATE_WARMUP:
      case STEADY_STATE_DURATION:
      case RECALL_SAMPLE_INTERVAL:
      case OBSERVE_INTERVAL:
      case INDEX_BUILD_TIMEOUT:
      case QUERY_TIMEOUT:
        durationValue(s);
        break;
      case REBUILD_EXPLICIT_AT:
        rebuildExplicitAt();
        break;
      case RUN_REPETITIONS:
      case CORPUS_DIM:
      case CORPUS_CLUSTERS:
      case CORPUS_FILTER_CATEGORIES:
      case CORPUS_FILTER_SOURCES:
      case CORPUS_FILTER_ACL_TAGS:
      case CORPUS_TENANTS:
      case CORPUS_BODY_BYTES:
      case TABLE_SALT_BUCKETS:
      case TABLE_REGIONS:
      case IVF_LISTS:
      case IVF_SAMPLE_SIZE:
      case HNSW_M:
      case HNSW_EF_CONSTRUCTION:
      case INGEST_THREADS:
      case INGEST_BATCH_SIZE:
      case QUERY_IVF_MAX_PROBE_LIMIT:
      case QUERY_THREADS:
      case QUERY_SWEEP_PANEL_SIZE:
      case MUTATION_HOTSPOT_REGIONS:
      case MUTATION_THREADS:
      case RECALL_PANEL_SIZE:
      case RECALL_SAMPLE_QUERIES:
      case RECALL_BOOTSTRAP_RESAMPLES:
        intValue(s);
        break;
      case HNSW_PQ_SEGMENTS:
      case OBSERVE_JMX_PORT:
        if (isSet(s)) {
          intValue(s);
        }
        break;
      case CORPUS_SEED:
      case CORPUS_ROWS:
        longValue(s);
        break;
      case CORPUS_VARIANCE_SPREAD:
      case CORPUS_NOISE:
      case CORPUS_QUERY_BOUNDARY_FRACTION:
      case CORPUS_QUERY_OOD_FRACTION:
      case CORPUS_DRIFT_RATE:
      case HNSW_ALPHA:
      case INGEST_PREBUILD_FRACTION:
      case INGEST_STREAM_RATE:
      case QUERY_TENANT_SCOPED_FRACTION:
      case QUERY_RATE:
      case QUERY_DISTANCE_BOUND_RANK:
      case QUERY_PK_RANGE_FRACTION:
      case MUTATION_RATE:
      case MUTATION_UPDATE_MOVE_FRACTION:
      case MUTATION_UPDATE_ATTRIBUTE_FRACTION:
      case RECALL_TIE_EPSILON:
      case RECALL_TRUTH_MARGIN:
      case FRESHNESS_RATE:
        doubleValue(s);
        break;
      case RUN_DETERMINISTIC:
      case TABLE_MULTI_TENANT:
      case TABLE_UPDATE_STATISTICS:
      case TABLE_SNAPSHOT:
      case IVF_ASYNC:
      case REBUILD_AUTO_ENABLED:
        boolValue(s);
        break;
      case CORPUS_NORMALIZE:
        if (isSet(s)) {
          boolValue(s);
        }
        break;
      default:
        break;
    }
  }

  /** Validates filter selectivities against vocabulary realizability. */
  private void realizable(List<String> problems, Setting vocabulary, int size) {
    Vocabulary v = new Vocabulary("v", size, filterSkew());
    for (double s : filterSelectivities()) {
      if (s <= 0.0 || s >= 1.0) {
        continue;
      }
      int[] r = v.rangeForSelectivity(s, 0);
      double m = v.rangeProbability(r[0], r[1]);
      if (Math.abs(m - s) > 0.05 * s) {
        problems.add(String.format(Locale.ROOT,
          "%s %s is not realizable with %s=%d: the nearest realizable selectivity is %.6g",
          Setting.QUERY_FILTER_SELECTIVITY.key(), s, vocabulary.key(), size, m));
      }
    }
  }

  private static void positive(List<String> problems, Setting s, long value) {
    if (value <= 0) {
      problems.add(s.key() + " must be positive");
    }
  }

  private static void fraction(List<String> problems, Setting s, double value) {
    if (value < 0.0 || value > 1.0) {
      problems.add(s.key() + " must be between 0 and 1");
    }
  }

  private int intValue(Setting s) {
    return parseInt(s, raw(s));
  }

  private static int parseInt(Setting s, String v) {
    try {
      return Integer.parseInt(v.trim().replace("_", ""));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not an integer: " + v);
    }
  }

  private long longValue(Setting s) {
    String v = raw(s);
    try {
      return Long.parseLong(v.trim().replace("_", ""));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not an integer: " + v);
    }
  }

  private double doubleValue(Setting s) {
    String v = raw(s);
    try {
      return Double.parseDouble(v.trim().replace("_", ""));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not a number: " + v);
    }
  }

  private boolean boolValue(Setting s) {
    String v = raw(s).trim().toLowerCase(Locale.ROOT);
    if (v.equals("true") || v.equals("yes") || v.equals("on")) {
      return true;
    }
    if (v.equals("false") || v.equals("no") || v.equals("off")) {
      return false;
    }
    throw new IllegalArgumentException("not a boolean: " + v);
  }

  private Duration durationValue(Setting s) {
    return Durations.parse(raw(s));
  }

  private double zipfValue(Setting s) {
    String v = raw(s).trim();
    if (v.equalsIgnoreCase("uniform")) {
      return 0.0;
    }
    String t = v.toLowerCase(Locale.ROOT);
    if (t.startsWith("zipf:")) {
      t = t.substring(5);
    } else if (t.startsWith("zipf(") && t.endsWith(")")) {
      t = t.substring(5, t.length() - 1);
    }
    try {
      double e = Double.parseDouble(t);
      if (e < 0) {
        throw new IllegalArgumentException("Zipf exponent must not be negative: " + v);
      }
      return e;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected uniform or a Zipf exponent: " + v);
    }
  }

  private int[] intList(Setting s) {
    List<Integer> out = new ArrayList<>();
    for (String part : raw(s).split(",")) {
      if (!part.trim().isEmpty()) {
        out.add(parseInt(s, part));
      }
    }
    return out.stream().mapToInt(Integer::intValue).toArray();
  }

  private double[] doubleList(Setting s) {
    List<Double> out = new ArrayList<>();
    for (String part : raw(s).split(",")) {
      String p = part.trim();
      if (p.isEmpty()) {
        continue;
      }
      try {
        out.add(Double.parseDouble(p.replace("_", "")));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("not a number: " + p);
      }
    }
    return out.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private <E extends Enum<E>> E parseEnum(Class<E> type, Setting s) {
    String v = raw(s).trim().toUpperCase(Locale.ROOT).replace('-', '_');
    try {
      return Enum.valueOf(type, v);
    } catch (IllegalArgumentException e) {
      List<String> names = new ArrayList<>();
      for (E c : type.getEnumConstants()) {
        names.add(c.name().toLowerCase(Locale.ROOT));
      }
      throw new IllegalArgumentException("expected one of " + names + ": " + raw(s));
    }
  }

  /** Sanitizes an identifier name by converting to uppercase alphanumeric and underscores. */
  public static String sanitizeIdentifier(String name) {
    StringBuilder sb = new StringBuilder();
    for (char c : name.toUpperCase(Locale.ROOT).toCharArray()) {
      sb.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
    }
    return sb.toString();
  }

  /** Returns an unmodifiable view of explicitly configured property keys. */
  public Set<String> explicitKeys() {
    return Collections.unmodifiableSet(props.stringPropertyNames());
  }

  @Override
  public String toString() {
    return "HarnessConfig[" + profileName + "]";
  }
}
