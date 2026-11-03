<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# phoenix-vector-harness

`phoenix-vector-harness` is an evaluation and benchmarking framework for Apache Phoenix vector index implementations. It supports both approximate nearest neighbor (ANN) indexing architectures available in Phoenix, as well as an exact distributed baseline:

- **Global Inverted File (IVF) Indexes** ([PHOENIX-7998](https://issues.apache.org/jira/browse/PHOENIX-7998)): Coarse-quantized inverted file indexes suited for cluster-wide vector partitions.
- **Region-Local HNSW Graph Indexes** ([PHOENIX-8009](https://issues.apache.org/jira/browse/PHOENIX-8009)): Hierarchical Navigable Small World graphs embedded within individual HBase regions.
- **Exact Distributed Top-N Baseline (`exact`)**: Brute-force distributed scanning without an index, serving as the performance floor and cluster-side ground truth.

The harness evaluates indexing, storage, and search performance across synthetic or published vector datasets (such as SIFT1M, GIST1M, or ann-benchmarks HDF5 corpora). It executes mixed workloads to assess search accuracy (recall against independent ground truth), latency distributions without coordinated omission, sustained query throughput, read amplification, RPC fan-out, RegionServer resource metrics, index lifecycle behavior, and physical storage footprints. Comparative reporting plots competing index variants and configurations along their Pareto efficiency frontiers.

## Building

The harness is packaged as a module of the Apache Phoenix project. To compile and stage runtime dependencies for the command-line launcher:

```bash
mvn package -DskipTests -DPhoenixPatchProcess -Pharness-dist -pl phoenix-vector-harness -am
```

The `harness-dist` profile stages dependencies and logging configurations under `target/lib`. On JDK 21 and above, include `-Pjava21` to compile SIMD distance kernels in `phoenix-core-client`. Set `HARNESS_SIMD=true` in the runtime environment so the JVM resolves the incubating Vector API. Execution manifests record whether SIMD acceleration was active during a run.

## Running

The harness is operated through the `bin/vector-harness` CLI launcher. It requires the target cluster client configuration on the classpath, typically by pointing `HBASE_CONF_DIR` to the directory containing `hbase-site.xml`. JVM tuning options (such as heap limits) can be passed using `HARNESS_OPTS`.

```bash
vector-harness load        --config <profile>   # Generate or load dataset, create and populate tables
vector-harness build-index --config <profile>   # Build index structures and verify query plans
vector-harness truth       --config <profile>   # Compute or validate panel ground truth
vector-harness run         --config <profile>   # Execute validity checks, frontier sweeps, and workloads
vector-harness report      --run-id <id> [--compare <id> ...] [--force]
```

### Configuration Profiles

Workloads are configured using standard Java properties files. Individual settings can be overridden on the command line using `-Dkey=value`, and the target JDBC endpoint can be specified using `--url`. Settings prefixed with `phoenix.` or `hbase.` are passed directly to client JDBC connections, allowing runtime tuning of driver properties like `phoenix.query.timeoutMs`.

Command execution returns exit status `0` on success, `1` on phase execution failure, and `2` on invalid CLI syntax or configuration errors.

### Workflow and Execution Lifecycle

Harness phases are decoupled so that expensive dataset ingestion and index builds can be reused across different benchmarking passes:

- **Data Loading (`load`)**: Creates the target schema and loads vector and attribute data. When `table.snapshot=true` is enabled, the harness creates an HBase snapshot of unindexed baseline tables immediately after loading. Subsequent builds restore from this snapshot, eliminating the need to reload large corpora between test runs.
- **Index Construction (`build-index`)**: Builds the selected index variant (IVF or HNSW) using configured parameters such as centroid counts, graph degree, search beam width, or scalar/product quantization. Multiple repetitions (`run.repetitions`) rebuild the index to measure index construction variance.
- **Ground Truth Calculation (`truth`)**: Computes exact k-nearest neighbors across the evaluation query panel to establish the baseline for recall scoring.
- **Benchmark Execution (`run`)**: Executes multi-stage evaluation workflows:
  1. *Validation Stage*: Verifies that the distributed exact plan achieves perfect recall against reference ground truth and verifies read-after-write consistency.
  2. *Streaming Ingest Stage*: When `ingest.prebuild.fraction` is set below 1.0, streams remaining records into the table concurrently to exercise real-time indexing paths while sampling search recall.
  3. *Frontier Sweep Stage*: Sweeps search parameters (such as IVF probe limits or HNSW search beam widths) across the query panel under quiescent conditions. This measures latency, throughput, read amplification, RPC fan-out, and query plan classifications across table layouts and query shapes. Read amplification is `rows_scanned` per returned row, from the Phoenix scan metrics of each query. For HNSW, `rows_scanned` includes the rows that the candidate probe reads. The `hnsw_probe_rows_read` column gives the JMX probe count only under the `registry` reader. The `http` and `local` readers cache JMX values, so the column is empty for them, and the sweep does not wait for the cache.
  4. *Steady-State Stage*: Runs open-loop query traffic at configured arrival rates alongside background mutations, freshness probes, periodic recall verification against live ground truth, and optional cluster disruptions (e.g., region splits, merges, or server restarts).
  5. *Cleanup*: Reverts mutation records at the conclusion of the run to restore tables to their baseline loaded state.

### Example Workflow

A typical evaluation comparing HNSW (with SQ8 quantization) and IVF on SIFT1M:

```bash
# Benchmark HNSW with scalar quantization
vector-harness load --config profiles/sift1m-hnsw-sq8.properties
vector-harness build-index --config profiles/sift1m-hnsw-sq8.properties
vector-harness run --config profiles/sift1m-hnsw-sq8.properties

# Benchmark IVF
vector-harness load --config profiles/sift1m-ivf.properties
vector-harness build-index --config profiles/sift1m-ivf.properties
vector-harness run --config profiles/sift1m-ivf.properties

# Compare efficiency frontiers
vector-harness report --run-id <hnsw-run-id> --compare <ivf-run-id>
```

Example profiles in `profiles/`:
- `smoke.properties`: Small synthetic dataset for quick cluster verification (specify variant with `-Drun.variant`).
- `sift1m-hnsw-sq8.properties` / `sift1m-ivf.properties`: SIFT1M benchmark profiles for HNSW and IVF comparisons. They read the ann-benchmarks file `sift-128-euclidean.hdf5`.
- `rag-1m-768.properties`: Multi-tenant document retrieval scenario representing enterprise RAG access patterns.
- `streaming-hnsw.properties`: High-throughput continuous ingestion, skewed mutation distribution, and region disruption scenarios.
- `drift-ivf.properties`: Simulates vector distribution drift and tests manual index rebuild cycles.
- `glove100-hnsw.properties`: Benchmark profile using the GloVe-100 dataset from ann-benchmarks.

## Outputs

All generated datasets, index states, and run metrics are stored under `run.output.dir`:

| Path | Contents |
|---|---|
| `corpus/<fingerprint>/truth-*.gt` | Cached panel ground truth over a corpus prefix |
| `tables/<suffix>/load.json` | Table load metadata: corpus fingerprint, row counts, ingestion rate, storage footprint, and snapshots |
| `tables/<suffix>/build.json` | Index build metadata: build duration, index options, plan check, segment/centroid statistics, and storage footprint |
| `runs/<run-id>/manifest.json` | Run manifest: configuration settings, corpus, cluster topology, validation checks, stage outcomes, and execution flags |
| `runs/<run-id>/frontier.csv` | Frontier stage results per table layout, predicate condition, k, and sweep parameter. The `coverage` column gives the real filter coverage (see below) |
| `runs/<run-id>/per_query.csv` | Scored per-query results from the frontier sweep for paired statistical evaluation |
| `runs/<run-id>/steady.csv`, `throughput.csv` | Steady-state window measurements (with the same `coverage` column) and interval throughput metrics |
| `runs/<run-id>/recall.csv` | Time-series recall measurements from streaming and steady-state stages |
| `runs/<run-id>/metrics.csv`, `cluster.csv` | RegionServer vector index metrics and cluster health telemetry |
| `runs/<run-id>/lifecycle.csv`, `events.csv` | IVF centroid and HNSW region lifecycle samples, along with cluster event logs |
| `runs/<run-id>/summary.txt` | Human-readable text summary of the run |
| `runs/<run-id>/report/` | Standalone single-run report files: `report.txt` and raw CSV data |
| `reports/<a>_vs_<b>/` | Comparative analysis report: `report.txt`, `runs.csv`, `frontier.csv`, `pareto.csv`, `cost_at_recall.csv`, `recall_at_budget.csv`, `steady.csv`, `recall_timeseries.csv`, `paired.csv` |

### Filter Coverage

The shape names `covered_filter` and `uncovered_filter` give the filter column (`CATEGORY` or `SOURCE`). They do not give the coverage. The `coverage` column of `frontier.csv` and `steady.csv` gives the real coverage for the variant and layout. The reports show it as a tag after the group, for example `profile covered_filter@0.1 k=10 [uncovered]`. The group key itself does not change, so comparisons still match groups across runs.

| Variant | Layout | No filter | `CATEGORY` filter | `SOURCE` filter |
|---|---|---|---|---|
| `ivf` | `corpus` | `none` | `covered` | `uncovered` |
| `ivf` | `profile` | `none` | `uncovered` | `uncovered` |
| `hnsw` | any | `none` | `local` | `local` |
| `exact` | any | `none` | `no_index` | `no_index` |

The IVF index is global. The IVF index of the `corpus` layout includes `CATEGORY, ACL_TAG, CREATED_AT`, and the IVF index of the `profile` layout includes no columns. Thus an uncovered IVF filter merges index rows with data rows on the server. The HNSW graph lives in the data region, so a filter reads the data rows of the same region and needs no merge (`local`). The `exact` variant uses no index (`no_index`). Expectations that depend on coverage, such as the `uncovered-path` suite check, use this column.

### Correctness and Validation Metrics

To guarantee benchmark integrity, run manifests capture execution invariants alongside performance statistics:
- **Baseline Accuracy**: Tracks exact query attempts, execution failures (`exact_failures`), and query predicate violations (`exact_violations`). The `truth` stage halts if the Phoenix exact execution plan disagrees with the reference ground truth.
- **Consistency and Freshness**: Measures read-after-write probe failures (`freshness_errors`) and query evaluation errors during recall sampling (`query_errors`).
- **Table State Tracking**: `load.json` flags tables left in an uncommitted state (`dirty_by`) until mutations are reverted, while restore records track unconfirmed mutation commits (`unresolved_rows`).

### Degraded Runs

A run is degraded when a defect stops part of its work too early. The run still writes all its outputs, and the manifest lists each defect under `run.degraded`. Then the `run` command prints each defect and exits with 1, so a suite does not count the run as a pass. These events in `events.csv` show the defects:

| Event | Meaning |
|---|---|
| `thread-failed` | A worker thread stopped on an unchecked exception or an error |
| `thread-stuck` | A worker thread did not stop within one minute after the end of its stage |
| `explicit-rebuild-failed` | An explicit rebuild failed. Only an unchecked exception degrades the run. A refused rebuild does not |

## Reporting and Analysis

The `report` command produces summary analyses for individual runs or generates comparative evaluations between multiple runs:

```bash
# Generate report for a single run (or the latest completed run)
vector-harness report --run-id <id>
vector-harness report --run-id latest

# Compare a base run against alternative runs
vector-harness report --run-id <base-id> --compare <comparison-id-1> <comparison-id-2>
```

When comparing runs, the reporter verifies that runs share compatible datasets (corpus fingerprint, query panel, and workload parameters). Comparisons between incompatible configurations are prevented unless overridden with `--force`.

Comparative reports quantify performance trade-offs across several dimensions:
- **Cost-at-Recall Frontiers**: Measures the resource cost (p50 and p99 latency, throughput, rows scanned per result, and RPC fan-out) required to achieve specific recall thresholds defined by `report.recall.targets`.
- **Recall-at-Latency Budgets**: Identifies achievable recall ceilings under strict p99 latency constraints defined by `report.latency.budgets`.
- **Statistical Significance**: Computes paired query-by-query latency and recall differences with bootstrap confidence intervals across equivalent operating points.
- **Filter Selectivity**: Analyzes query execution times across filter selectivity curves to identify crossover thresholds where vector index scans outperform full scans or secondary indexes.
- **Resource Consumption**: Compares index construction time, raw and indexed storage footprints, write amplification during ingest, and tenant isolation behavior.

## Configuration Reference

Default settings reflect baseline recommendations. Settings with inter-dependencies note their prerequisites (for example, setting `run.repetitions` with `ingest.prebuild.fraction < 1.0` requires `table.snapshot=true` so that streaming ingest repeats against an identical baseline).

| Run Settings | Default | Description |
|---|---|---|
| `run.variant` | `ivf` | Index variant: `exact`, `ivf`, or `hnsw` (exactly one per run) |
| `run.repetitions` | `1` | Number of repeated build-and-run cycles to capture engine variance |
| `run.deterministic` | `false` | Enables single mutation worker and pauses sampling for deterministic execution |
| `run.id` | — | Unique run identifier; auto-generated from variant and timestamp if omitted |
| `run.output.dir` | `vector-harness-output` | Output directory for corpus caches, table state, and benchmark results |
| `run.profile.name` | — | Profile name; defaults to the configuration file base name |
| `jdbc.url` | `jdbc:phoenix:localhost` | Phoenix JDBC connection URL for the target cluster |

| Corpus Settings | Default | Description |
|---|---|---|
| `corpus.source` | `synthetic` | Dataset source: `synthetic`, `texmex`, or `hdf5` |
| `corpus.seed` | `42` | Random seed for reproducible data and query generation |
| `corpus.path` | — | File path to published corpus directory (TEXMEX) or file (HDF5) |
| `corpus.name` | — | Prefix for TEXMEX files (e.g. `sift`, `gist`); auto-detected if omitted |
| `corpus.rows` | `1000000` | Number of rows to generate or slice from published dataset. A prefix of a published dataset has no published ground truth; the harness computes it |
| `corpus.holdout.rows` | `auto` | Rows at the tail of a published dataset that the run does not load. Each insert and move takes a distinct tail row, so no new vector repeats a loaded row. The steady window and the sequence number of the operation select the row, never thread timing, so paired runs write the same rows in the same operations. Each window owns a range of tail rows of the planned size per window, and the planned volume is that size times the window count. `auto` is 0 for a run that writes no new vectors, and otherwise the planned volume rounded up to a multiple of 10,000. Validation fails when the tail is smaller than the planned volume. With a prefix, the rows after the prefix supply the new vectors. A texmex learn file replaces the tail |
| `corpus.dim` | `768` | Vector dimension (synthetic datasets only; inferred for published files) |
| `corpus.clusters` | `200` | Number of mixture components in synthetic generator |
| `corpus.cluster.weights` | `uniform` | Cluster size distribution: `uniform` or Zipf skew exponent |
| `corpus.variance.spread` | `1.0` | Variance ratio between the most diffuse and tightest clusters |
| `corpus.intrinsic_dim` | `dim` | Subspace rank for synthetic cluster generation |
| `corpus.noise` | `0.05` | Standard deviation of ambient noise orthogonal to cluster subspace |
| `corpus.normalize` | `true for COSINE` | Normalizes vectors onto the unit sphere |
| `corpus.query.boundary_fraction` | `0.0` | Proportion of panel queries positioned along cluster boundaries |
| `corpus.query.ood_fraction` | `0.0` | Proportion of panel queries sampled outside the mixture distribution |
| `corpus.drift.mode` | `none` | Vector drift mode: `none`, `migrate`, `birth_death`, or `reassign` |
| `corpus.drift.rate` | `0.0` | Rate of cluster drift per minute during steady state |
| `corpus.filter.skew` | `uniform` | Attribute vocabulary distribution: `uniform` or Zipf exponent |
| `corpus.filter.categories` | `1000` | Vocabulary cardinality for `CATEGORY` attribute (included only by the IVF index of the `corpus` layout) |
| `corpus.filter.sources` | `1000` | Vocabulary cardinality for `SOURCE` attribute (unindexed) |
| `corpus.filter.acl_tags` | `16` | Vocabulary cardinality for `ACL_TAG` attribute |
| `corpus.tenants` | `1` | Number of distinct `TENANT_ID` values |
| `corpus.tenant.skew` | `uniform` | Distribution of rows per tenant: `uniform` or Zipf exponent |
| `corpus.body.bytes` | `128` | Byte length of unindexed `BODY` payload column |

| Table Settings | Default | Description |
|---|---|---|
| `table.schema` | `VH` | Phoenix schema for benchmark tables |
| `table.suffix` | — | Suffix for table names; defaults to `<variant>_<profile>` |
| `table.layouts` | `corpus,profile` | Provisioned table layouts: `corpus` (`DOC_CORPUS`) and `profile` (`DOC_PROFILE`) |
| `table.multi_tenant` | `false` | Enable multi-tenant table layout |
| `table.salt_buckets` | `0` | Number of salt buckets for data table partitioning |
| `table.regions` | `16` | Initial pre-split region count for the data table |
| `table.storage_scheme` | `ONE_CELL_PER_COLUMN` | Storage scheme for immutable tables |
| `table.update_statistics` | `true` | Executes `UPDATE STATISTICS` following index creation |
| `table.snapshot` | `false` | Take HBase snapshot of unindexed table after load and restore before each build |

| Index Settings | Default | Description |
|---|---|---|
| `index.metric` | `COSINE (L2 for SIFT and GIST)` | Distance metric for indexing and querying |
| `index.build.timeout` | `2h` | Maximum timeout waiting for index build to reach `ACTIVE` state |
| `ivf.lists` | `1024` | Number of centroids (inverted lists) for IVF indexes |
| `ivf.sample_size` | `50000` | Number of vectors sampled for k-means centroid training |
| `ivf.async` | `false` | Builds index asynchronously via Phoenix index tooling |
| `ivf.regions` | `table.regions` | Region count of the IVF index table after the build. `1` leaves the index in one region. See [IVF Index Regions](#ivf-index-regions) |
| `ivf.salt_buckets` | — | `SALT_BUCKETS` of the IVF index. Unset, the index inherits the salt of the data table. `0` makes an unsalted index, and a positive value salts it. A positive value with an explicit `ivf.regions` above 1 fails validation |
| `hnsw.m` | `16` | Maximum node degree (outgoing edges per node) in HNSW graph |
| `hnsw.ef_construction` | `100` | Size of dynamic candidate list during HNSW graph construction |
| `hnsw.alpha` | `1.2` | Vamana diversity factor |
| `hnsw.quantization` | `NONE` | Vector quantization method: `NONE`, `SQ8`, or `PQ` |
| `hnsw.pq_segments` | — | Subspace count for Product Quantization (must evenly divide dimension) |

| Ingestion Settings | Default | Description |
|---|---|---|
| `ingest.prebuild.fraction` | `1.0` | Proportion of corpus ingested before index construction |
| `ingest.mode` | `jdbc` | Ingestion mode: `jdbc` or `bulkload` |
| `ingest.threads` | `4` | Number of concurrent ingestion worker threads |
| `ingest.batch.size` | `500` | Batch size (rows per commit) for JDBC ingestion |
| `ingest.stream.rate.per_sec` | `0` | Ingestion rate limit (rows/sec) for post-build streaming (0 = unthrottled) |

| Query Settings | Default | Description |
|---|---|---|
| `query.k.values` | `10,100` | Values of k evaluated during query sweeps |
| `query.ivf.probe.values` | `1,4,16,64,all` | List of `VECTOR_PROBE_COUNT` values evaluated during IVF sweeps |
| `query.ivf.max_probe_limit` | `8` | Maximum allowable `VECTOR_PROBE_COUNT` for the run |
| `query.hnsw.ef.values` | `16,32,64,128,256,512` | List of `HNSW_EF_SEARCH` values evaluated during HNSW sweeps |
| `query.filter.selectivity` | `0.001,0.01,0.1,1.0` | Evaluated predicate selectivity values |
| `query.shapes` | `all` | Evaluated query shape filters (or `all`) |
| `query.tenant_scoped.fraction` | `1.0` | Proportion of queries scoped by `TENANT_ID` (multi-tenant tables) |
| `query.mode` | `open` | Query arrival pattern: `open` (constant rate) or `closed` |
| `query.rate.values` | — | Offered arrival rates evaluated during open-loop sweeps |
| `query.rate.per_sec` | `20` | Fixed query arrival rate when not sweeping rates |
| `query.threads` | `8` | Concurrent query worker threads |
| `query.timeout` | `60s` | Individual query timeout |
| `query.sweep.panel_size` | `0` | Query panel sample size evaluated per sweep point (0 = full panel) |
| `query.distance_bound.rank` | `0.5` | Distance threshold rank for bounded queries relative to k |
| `query.pk_range.fraction` | `0.25` | Key-range fraction scanned by primary-key bounded queries |

| Mutation Settings | Default | Description |
|---|---|---|
| `mutation.rate.per_sec` | `0` | Target aggregate mutation rate (ops/sec) |
| `mutation.mix` | `0.3,0.5,0.2` | Relative distribution of insert, update, and delete operations |
| `mutation.update.move_fraction` | `0.0` | Proportion of updates that relocate vector to another cluster |
| `mutation.update.attribute_fraction` | `0.3` | Proportion of updates that modify non-vector attributes only |
| `mutation.hotspot.regions` | `0` | Restricts mutations to a subset of regions to induce hotspots (0 = uniform) |
| `mutation.threads` | `2` | Concurrent mutation worker threads |

| Steady-State and Lifecycle Settings | Default | Description |
|---|---|---|
| `steady_state.warmup` | `1m` | Initial warmup window excluded from measurement analysis |
| `steady_state.duration` | `10m` | Duration of active steady-state measurement window. No mutation or freshness probe that is due at or after the planned end runs. One that is due before the end can still run late. A disruption that is in flight at the planned end extends the reads: queries, recall samples and the throughput record continue until the disruption and its recovery wait end. The disruption has 40 minutes to end, and the threads then have a 10 minute grace to stop. After about 50 minutes, the harness stops the stage and marks the run degraded. No disruption starts after the planned end. The manifest gives `achieved_qps` per `measured_seconds`, the real length of the reads, and `achieved_mutations_per_second` per `write_seconds`, the planned length |
| `rebuild.auto.enabled` | `false` | Expected state of IVF automatic rebuild configuration |
| `rebuild.explicit.at` | — | Elapsed time before triggering manual `ALTER INDEX ... REBUILD` |

| Recall and Measurement Settings | Default | Description |
|---|---|---|
| `recall.panel.size` | `1000` | Number of queries comprising the evaluation panel |
| `recall.sample.interval` | `30s` | Frequency of periodic recall evaluations |
| `recall.sample.mode` | `quiesced` | Sampling mode: `quiesced` (mutations paused) or `online` |
| `recall.sample.queries` | `100` | Queries evaluated per recall sample (0 = full panel) |
| `recall.tie_epsilon` | `1e-6` | Relative distance tolerance for identifying tied neighbors |
| `recall.truth.margin` | `2` | Distance margin factor for live ground truth checks |
| `recall.bootstrap.resamples` | `1000` | Bootstrap iterations for confidence interval calculation |
| `freshness.rate.per_sec` | `1` | Frequency of read-after-write consistency checks (probes/sec) |

| Observation Settings | Default | Description |
|---|---|---|
| `observe.interval` | `15s` | Polling frequency for RegionServer JMX and vector index metrics |
| `observe.fsck.interval` | `stage` | HNSW `indexfsck` verification frequency: `stage` (stage transitions), `off`, or duration |
| `observe.jmx.mode` | `auto` | JMX collection mode: `auto`, `http` (/jmx servlet), `local` (JVM MBeans), `registry`, or `off` |
| `observe.jmx.port` | — | RegionServer info port; auto-detected if omitted |

| Fault Injection Settings | Default | Description |
|---|---|---|
| `disruption.schedule` | — | Timed cluster disruptions (e.g. `2m:split,5m:compact`, `move`, `restart`, `merge`). A `restart` is graceful: the HBase region mover moves the regions off the server, the server stops cleanly, its supervisor starts it, and the region mover moves the regions back. The disruption record gives the time of each step and of the full cycle |

| Reporting Settings | Default | Description |
|---|---|---|
| `report.recall.targets` | `0.90,0.95,0.99` | Target recall thresholds for cost analysis along efficiency frontiers |
| `report.latency.budgets` | `10ms` | Target p99 latency ceilings for recall analysis along efficiency frontiers |

### IVF Index Regions

A production IVF index starts in one region. It splits as it grows, early in its life, and then keeps a steady region count. The harness gives the index that steady-state layout, so the query plan and the cost per query match a naturally split index. Pre-splitting is a test aid. It does not tune production.

The layout method depends on the row key `[salt][tenant][centroid ID][data key]`:

| Index | Method |
|---|---|
| Unsalted, single tenant | `CREATE VECTOR INDEX ... SPLIT ON (...)` at even centroid IDs over the first generation: the step is the ceiling of `ivf.lists` over `ivf.regions`. After the build, the harness reads the real first ID and list count. Training can return more lists than requested, so the harness adds split points where a region holds one and a half even shares of the lists or more. A few extra lists do not add a region |
| Unsalted, multi-tenant | The tenant ID leads the row key, so SPLIT ON cannot give the layout. After the build, the harness splits the largest region at its midkey, as a size-based split does. Between rounds, the daughters compact away their reference files |
| Salted | The salt buckets give the regions. The harness does not split the index. A data table with `table.salt_buckets` above 0 salts the index too, unless `ivf.salt_buckets=0` |

SPLIT ON covers only the centroid IDs of the first generation. A rebuilt generation takes the IDs after the IDs of the generation that it replaces. The rows of the old generation stay in the table, so a rebuild must give the new ID range its own regions:

| Rebuild | Method |
|---|---|
| Explicit (`rebuild.explicit.at`), keyed by centroid ID | Before `ALTER INDEX ... REBUILD`, the harness splits the empty ID range of the new generation. The first split is at the first new ID. The other splits are at even steps over the expected list count: the larger of the requested count and the list count of the outgoing generation. The target is the larger of `ivf.regions` and the populated region count of the outgoing generation. A split of an empty range moves no data. After the promotion, the harness reads the real list count and adds split points if training returned more lists |
| Explicit, multi-tenant | After the promotion, the harness splits the largest region at its midkey. Both generations share the regions of each tenant, so this step usually finds the target count already in place |
| Automatic (`rebuild.auto.enabled=true`) | The harness polls the active generation every 10 seconds. After a promotion, it splits the largest regions that hold the new ID range at their midkeys while the workload runs. The splits stop at the end of the window, and the next window continues them. A failed layout gets three attempts |

After any split, the harness spreads the regions of the new generation across the region servers. A split opens both daughters on the server of the parent, and the balancer evens out only the region count of the whole table. The retired regions of earlier generations count in that total. Thus the harness moves regions of the generation from the busiest server to the idlest server until the counts are even. A spread that times out records a warning event and does not stop the run. Each step records an `index-presplit` event with the phase: `first-build`, `before-rebuild`, `after-promotion`, or `auto-promotion`. A steady window records each rebuild layout change under `layout_changes` with its start and end offsets. The report lists these changes below the window. From the promotion to the end of the split, the index does not have a steady-state layout, so the measurements of that interval do not describe one.

The build record `tables/<suffix>/build.json` holds the layout under `indexes.<layout>.regions`: `index_regions`, `populated_regions`, `split_points`, `lists_per_region` (for an index keyed by centroid ID), `region_bytes`, `size_cv` (the coefficient of variation of the populated region sizes), `regions_per_server` (for an index keyed by centroid ID, only the regions of the populated generation count), `table_regions_per_server`, and the events. The build record also holds the layout time under `regions.seconds`, and `build.layout_seconds` holds the sum. The build time does not include it. Reports show the region count in the `index regions` column. The run records the resolved layout: `ivf.salt_buckets` is the salt of the index, and `ivf.regions` is the target region count, or the bucket count of a salted index. Runs with other resolved values are not comparable.

## Integration Testing

Integration tests run against an embedded Phoenix minicluster, validating harness components alongside core vector index behaviors. They follow standard Phoenix test patterns and can be executed via Maven:

```bash
mvn verify -pl phoenix-vector-harness -am -DPhoenixPatchProcess -Dskip.code-coverage \
  -Dtest=NoUnitTests -Dit.test=VectorHarnessEndToEndIT -Dfailsafe.failIfNoSpecifiedTests=false
```

| Test Class | Minicluster Configuration | Scope |
|---|---|---|
| `VectorHarnessEndToEndIT` | Shared minicluster | End-to-end execution of CLI phases (`load`, `build-index`, `truth`, `run`, `report`) across exact, IVF, and HNSW variants; validates plan selection, recall agreement with ground truth, and report comparison logic. |
| `VectorHarnessLayoutsIT` | Shared minicluster | Table storage models (relational vs. BSON document columns), table partitioning strategies (salting and multi-tenancy), distance functions (COSINE and INNER_PRODUCT), metric compatibility validation, and bulk ingest paths. |
| `VectorHarnessLifecycleIT` | Shared minicluster | Post-build streaming ingestion, table snapshot restore cycles across benchmark repetitions, JMX metric registry collection, deterministic sampling, background compaction/split behavior, and invalid phase transition handling. |
| `VectorIndexFunctionsIT` | Shared minicluster | Functional validation of Phoenix vector indexing: distance metric calculations, IVF probe accuracy, predicate pushdown filters, uncommitted mutations, DDL validation, index durability across memstore flushes and compactions, manual IVF rebuilds, and tenant isolation boundaries. |
| `VectorHarnessDisruptionIT` | Dedicated 2-RegionServer cluster | Cluster fault tolerance under active steady-state workloads: region migrations, splits, merges, major compactions, and RegionServer restarts. Verifies query resilience, event logging, and index integrity recovery via `indexfsck`. |

## Architecture & Design References

For in-depth architectural details, workload generation models, and measurement methodologies, refer to the [Phoenix Vector Harness Design Specification](https://gist.github.com/apurtell/3d8c4908eb51bb2246986f322efed233).
