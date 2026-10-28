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

import java.util.HashMap;
import java.util.Map;

/**
 * The settings of a harness profile. A profile is a properties file; each setting has a key, a
 * default, and a description. A default of null means the setting has no value unless the profile
 * gives one. Some defaults depend on other settings and are resolved by {@link HarnessConfig}; the
 * default text of such a setting names the dependency.
 */
public enum Setting {
  // Run
  RUN_VARIANT("run.variant", "ivf", "exact, ivf, or hnsw; exactly one per run"),
  RUN_REPETITIONS("run.repetitions", "1", "Repeated build-and-run cycles, for engine variance"),
  RUN_DETERMINISTIC("run.deterministic", "false", "One mutation worker and quiesced samples"),
  RUN_ID("run.id", null, "Identifier of the run; generated from the variant and time if absent"),
  RUN_OUTPUT_DIR("run.output.dir", "vector-harness-output",
    "Directory that holds corpus caches, table state, and run results"),
  RUN_PROFILE_NAME("run.profile.name", null,
    "Name of the profile; defaults to the base name of the profile file"),
  JDBC_URL("jdbc.url", "jdbc:phoenix:localhost", "Phoenix JDBC URL of the cluster under test"),

  // Corpus
  CORPUS_SOURCE("corpus.source", "synthetic", "synthetic, texmex, or hdf5"),
  CORPUS_SEED("corpus.seed", "42", "Master seed for every harness random choice"),
  CORPUS_PATH("corpus.path", null, "Directory (texmex) or file (hdf5) of a published corpus"),
  CORPUS_NAME("corpus.name", null,
    "File name prefix of a TEXMEX corpus, such as sift or gist; detected if absent"),
  CORPUS_ROWS("corpus.rows", "1000000", "Rows to generate, or a prefix of a published corpus"),
  CORPUS_DIM("corpus.dim", "768", "Dimension (synthetic only; published files set it)"),
  CORPUS_CLUSTERS("corpus.clusters", "200", "Mixture components"),
  CORPUS_CLUSTER_WEIGHTS("corpus.cluster.weights", "uniform",
    "uniform, or a Zipf exponent for cluster sizes"),
  CORPUS_VARIANCE_SPREAD("corpus.variance.spread", "1.0",
    "Ratio between the most diffuse and the tightest cluster"),
  CORPUS_INTRINSIC_DIM("corpus.intrinsic_dim", "dim",
    "Rank of the subspace that each cluster lies near"),
  CORPUS_NOISE("corpus.noise", "0.05",
    "Standard deviation of the ambient noise added off the cluster subspace"),
  CORPUS_NORMALIZE("corpus.normalize", "true for COSINE", "Project vectors onto the unit sphere"),
  CORPUS_QUERY_BOUNDARY_FRACTION("corpus.query.boundary_fraction", "0.0",
    "Share of panel queries between clusters"),
  CORPUS_QUERY_OOD_FRACTION("corpus.query.ood_fraction", "0.0",
    "Share of panel queries from outside the mixture"),
  CORPUS_DRIFT_MODE("corpus.drift.mode", "none", "none, migrate, birth_death, or reassign"),
  CORPUS_DRIFT_RATE("corpus.drift.rate", "0.0", "Strength of drift per minute of steady state"),
  CORPUS_FILTER_SKEW("corpus.filter.skew", "uniform", "Zipf exponent for attribute vocabularies"),
  CORPUS_FILTER_CATEGORIES("corpus.filter.categories", "1000",
    "Size of the CATEGORY vocabulary (covered by IVF indexes)"),
  CORPUS_FILTER_SOURCES("corpus.filter.sources", "1000",
    "Size of the SOURCE vocabulary (never covered by vector indexes)"),
  CORPUS_FILTER_ACL_TAGS("corpus.filter.acl_tags", "16", "Size of the ACL_TAG vocabulary"),
  CORPUS_TENANTS("corpus.tenants", "1", "Number of distinct TENANT_ID values"),
  CORPUS_TENANT_SKEW("corpus.tenant.skew", "uniform",
    "uniform, or a Zipf exponent for the rows per tenant"),
  CORPUS_BODY_BYTES("corpus.body.bytes", "128", "Length of the unindexed BODY text payload"),

  // Table
  TABLE_SCHEMA("table.schema", "VH", "Phoenix schema of the harness tables"),
  TABLE_SUFFIX("table.suffix", null,
    "Suffix of the table names; defaults to the variant and the profile name"),
  TABLE_LAYOUTS("table.layouts", "corpus,profile",
    "Layouts to provision: corpus (DOC_CORPUS) and profile (DOC_PROFILE)"),
  TABLE_MULTI_TENANT("table.multi_tenant", "false", "Multi-tenant tables"),
  TABLE_SALT_BUCKETS("table.salt_buckets", "0", "Data table salting"),
  TABLE_REGIONS("table.regions", "16", "Pre-split region count of the data table"),
  TABLE_STORAGE_SCHEME("table.storage_scheme", "ONE_CELL_PER_COLUMN", "Immutable storage scheme"),
  TABLE_UPDATE_STATISTICS("table.update_statistics", "true",
    "Run UPDATE STATISTICS after the build"),
  TABLE_SNAPSHOT("table.snapshot", "false",
    "Snapshot the unindexed data tables after load and restore them before each index build"),

  // Index
  INDEX_METRIC("index.metric", "COSINE (L2 for SIFT and GIST)", "Index and query metric"),
  INDEX_BUILD_TIMEOUT("index.build.timeout", "2h", "Longest wait for an index to become ACTIVE"),
  IVF_LISTS("ivf.lists", "1024", "Centroid count"),
  IVF_SAMPLE_SIZE("ivf.sample_size", "50000", "Training sample"),
  IVF_ASYNC("ivf.async", "false", "Asynchronous population"),
  HNSW_M("hnsw.m", "16", "Maximum node degree"),
  HNSW_EF_CONSTRUCTION("hnsw.ef_construction", "100", "Construction beam width"),
  HNSW_ALPHA("hnsw.alpha", "1.2", "Vamana diversity factor"),
  HNSW_QUANTIZATION("hnsw.quantization", "NONE", "NONE, SQ8, or PQ"),
  HNSW_PQ_SEGMENTS("hnsw.pq_segments", null, "Required for PQ; must divide the dimension"),

  // Ingest
  INGEST_PREBUILD_FRACTION("ingest.prebuild.fraction", "1.0",
    "Share of the corpus loaded before the index exists"),
  INGEST_MODE("ingest.mode", "jdbc", "jdbc or bulkload"),
  INGEST_THREADS("ingest.threads", "4", "Concurrent ingestion workers"),
  INGEST_BATCH_SIZE("ingest.batch.size", "500", "Rows per JDBC commit"),
  INGEST_STREAM_RATE("ingest.stream.rate.per_sec", "0",
    "Rate of the streaming ingest of the rows loaded after the build (0 means unthrottled)"),

  // Query
  QUERY_K_VALUES("query.k.values", "10,100", "Values of k"),
  QUERY_IVF_PROBE_VALUES("query.ivf.probe.values", "1,4,16,64,all", "VECTOR_PROBE_COUNT sweep"),
  QUERY_IVF_MAX_PROBE_LIMIT("query.ivf.max_probe_limit", "8", "MAX_PROBE_LIMIT for the run"),
  QUERY_HNSW_EF_VALUES("query.hnsw.ef.values", "16,32,64,128,256,512", "HNSW_EF_SEARCH sweep"),
  QUERY_FILTER_SELECTIVITY("query.filter.selectivity", "0.001,0.01,0.1,1.0",
    "Generated predicate selectivities"),
  QUERY_SHAPES("query.shapes", "all", "Subset of the query shapes to run"),
  QUERY_TENANT_SCOPED_FRACTION("query.tenant_scoped.fraction", "1.0",
    "Share of tenant-scoped queries (multi-tenant tables)"),
  QUERY_MODE("query.mode", "open", "open (fixed arrival rate) or closed"),
  QUERY_RATE_VALUES("query.rate.values", null, "Offered rates for an open-loop sweep"),
  QUERY_RATE("query.rate.per_sec", "20",
    "Offered query rate of the steady state when no rate sweep is given"),
  QUERY_THREADS("query.threads", "8", "Query worker threads"),
  QUERY_TIMEOUT("query.timeout", "60s", "Timeout of one query"),
  QUERY_SWEEP_PANEL_SIZE("query.sweep.panel_size", "0",
    "Panel queries per sweep point of the frontier stage (0 means the whole panel)"),
  QUERY_DISTANCE_BOUND_RANK("query.distance_bound.rank", "0.5",
    "Radius of distance-bounded queries, as the fraction of k whose true distance sets it"),
  QUERY_PK_RANGE_FRACTION("query.pk_range.fraction", "0.25",
    "Share of the DOC_ID key space that PK_RANGE queries scan"),

  // Mutation
  MUTATION_RATE("mutation.rate.per_sec", "0", "Combined mutation rate"),
  MUTATION_MIX("mutation.mix", "0.3,0.5,0.2", "Insert, update, and delete shares"),
  MUTATION_UPDATE_MOVE_FRACTION("mutation.update.move_fraction", "0.0",
    "Share of updates that move a row to another cluster"),
  MUTATION_UPDATE_ATTRIBUTE_FRACTION("mutation.update.attribute_fraction", "0.3",
    "Share of updates that change attributes only"),
  MUTATION_HOTSPOT_REGIONS("mutation.hotspot.regions", "0",
    "Concentrate writes on this many regions (0 means none)"),
  MUTATION_THREADS("mutation.threads", "2", "Mutation worker threads"),

  // Steady state and lifecycle
  STEADY_STATE_WARMUP("steady_state.warmup", "1m", "Discarded warm-up"),
  STEADY_STATE_DURATION("steady_state.duration", "10m", "Measured duration"),
  REBUILD_AUTO_ENABLED("rebuild.auto.enabled", "false",
    "Expected value of the IVF automatic rebuild setting"),
  REBUILD_EXPLICIT_AT("rebuild.explicit.at", null, "Time of an explicit ALTER INDEX ... REBUILD"),

  // Recall
  RECALL_PANEL_SIZE("recall.panel.size", "1000", "Panel queries"),
  RECALL_SAMPLE_INTERVAL("recall.sample.interval", "30s", "Sampling interval"),
  RECALL_SAMPLE_MODE("recall.sample.mode", "quiesced", "quiesced or online"),
  RECALL_SAMPLE_QUERIES("recall.sample.queries", "100",
    "Panel queries scored at each steady-state sample (0 means the whole panel)"),
  RECALL_TIE_EPSILON("recall.tie_epsilon", "1e-6", "Relative tie tolerance"),
  RECALL_TRUTH_MARGIN("recall.truth.margin", "2", "Margin c of the live ground truth"),
  RECALL_BOOTSTRAP_RESAMPLES("recall.bootstrap.resamples", "1000",
    "Bootstrap resamples of the confidence intervals"),
  FRESHNESS_RATE("freshness.rate.per_sec", "1", "Freshness probe rate"),

  // Observation
  OBSERVE_INTERVAL("observe.interval", "15s",
    "Poll interval of the JMX and vector index metrics observer"),
  OBSERVE_FSCK_INTERVAL("observe.fsck.interval", "stage",
    "HNSW indexfsck inspect interval: stage (stage boundaries only), off, or a duration"),
  OBSERVE_JMX_MODE("observe.jmx.mode", "auto",
    "auto, http (RegionServer /jmx servlets), local (this JVM's MBeans), registry, or off"),
  OBSERVE_JMX_PORT("observe.jmx.port", null,
    "RegionServer info port; defaults to the port each server reports"),
  OBSERVE_JMX_CACHE_TTL("observe.jmx.cache_ttl", "11s",
    "Refresh period of the RegionServer JMX caches (metrics2 period plus a margin) that the HNSW"
      + " frontier sweep waits out per group under the http and local readers; 0 records the probe"
      + " rows as unknown"),

  // Disruption
  DISRUPTION_SCHEDULE("disruption.schedule", null,
    "Timed disruptions (move, restart, split, merge, compact), such as 2m:split,5m:compact"),

  // Report
  REPORT_RECALL_TARGETS("report.recall.targets", "0.90,0.95,0.99",
    "Recall targets at which reports state the cost of each frontier"),
  REPORT_LATENCY_BUDGETS("report.latency.budgets", "10ms",
    "p99 latency ceilings within which reports state the achievable recall");

  private static final Map<String, Setting> BY_KEY = new HashMap<>();

  static {
    for (Setting s : values()) {
      BY_KEY.put(s.key, s);
    }
  }

  private final String key;
  private final String defaultText;
  private final String description;

  Setting(String key, String defaultText, String description) {
    this.key = key;
    this.defaultText = defaultText;
    this.description = description;
  }

  public String key() {
    return key;
  }

  /** Returns the default as documented; derived defaults are resolved by HarnessConfig. */
  public String defaultText() {
    return defaultText;
  }

  public String description() {
    return description;
  }

  /** Returns the setting with the given key, or null if the key is not a harness setting. */
  public static Setting forKey(String key) {
    return BY_KEY.get(key);
  }
}
