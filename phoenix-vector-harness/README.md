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

A functional and performance harness for Phoenix vector indexes: the global inverted file (IVF)
index of PHOENIX-7998 and the region-local HNSW graph index of PHOENIX-8009. The harness loads a
deterministic synthetic corpus or a published benchmark (TEXMEX `fvecs`/`bvecs` such as SIFT1M and
GIST1M, or ann-benchmarks HDF5), builds one index variant, and measures it under a mixed workload:
recall and related accuracy measures against an independently computed ground truth, latency
percentiles without coordinated omission, throughput, read amplification and RPC fan-out, the
vector index metrics of the RegionServers, the index lifecycle, and the storage footprint. Reports
place runs of different variants over the same corpus and query panel side by side on their
efficiency frontiers.

Each run measures exactly one variant: `exact` (no index, the distributed exact top-N plan), `ivf`,
or `hnsw`. The exact variant is the baseline that approximate indexes must improve on and an
independent check, on the cluster, of the harness's own ground truth.

## Building

The harness is a module of the Phoenix build. To build it with its runtime classpath for the
launcher:

```
mvn package -DskipTests -DPhoenixPatchProcess -Pharness-dist -pl phoenix-vector-harness -am
```

The `harness-dist` profile copies the runtime dependencies and the log4j2 binding to
`target/lib`. Add `-Pjava21` on JDK 21 to build the SIMD distance kernels of phoenix-core-client,
and run the launcher with `HARNESS_SIMD=true` so that the JVM resolves the incubating vector API;
the run manifest records whether the vector API was available.

## Running

`bin/vector-harness` runs the command line. It needs the cluster's client configuration on the
classpath, normally by pointing `HBASE_CONF_DIR` at the directory of its `hbase-site.xml`;
`HARNESS_OPTS` passes JVM options such as the heap size.

```
vector-harness load        --config <profile>   # generate or read the corpus, create and load tables
vector-harness build-index --config <profile>   # build the index variant and check the plans
vector-harness truth       --config <profile>   # compute or validate the ground truth of the panel
vector-harness run         --config <profile>   # validity checks, frontier sweep, steady state
vector-harness report      --run-id <id> [--compare <id> ...] [--force]
```

A profile is a properties file of the settings below. Any setting can be overridden on the command
line with `-Dkey=value`, and `--url` overrides `jdbc.url`. Settings whose keys start with
`phoenix.` or `hbase.` are passed to the Phoenix connections as connection properties, which is how
a profile sets client tunables such as `phoenix.query.timeoutMs`. The exit status is 0 on success,
1 when a phase fails, and 2 for an invalid command line or profile.

The phases are decoupled. Once a corpus is loaded, its tables serve any number of runs that vary
only query-time parameters such as `VECTOR_PROBE_COUNT` or `HNSW_EF_SEARCH`. Build-time parameters
(IVF lists, HNSW degree, construction beam width, quantization) need their own index, so each
variant and profile gets its own tables: the table suffix defaults to the variant and the profile
name, and every table of one corpus is populated from the same seeded stream. With
`table.snapshot=true` the load phase snapshots the unindexed tables and each build restores them
first, which saves reloading large corpora between builds. `run.repetitions` repeats the build and
the run, so that the spread across repetitions includes the variance of the build.

The run phase proceeds in stages. It first checks the validity of the setup: the exact plan must
reach recall 1 against the client-side ground truth, and a committed row must be visible at once.
When `ingest.prebuild.fraction` is below 1, the rows loaded after the build then stream in through
the live index write paths while recall is sampled. The frontier stage sweeps query breadth over
the panel with no concurrent writes, scoring every result, and records the latency, throughput,
read amplification, RPCs, and the plan class of each group of layout, condition family, k, and
sweep point; a plan that differs from the variant's expectation is recorded under the plan that
ran and flagged. The steady stage then runs open-loop queries at each offered rate together with
mutations, freshness probes, periodic recall samples against a live ground truth, scheduled
disruptions, and an optional explicit rebuild. The mutations of the run are reverted at the end,
so the next run starts from the loaded corpus.

### Example

```
$ vector-harness load --config profiles/sift1m-hnsw-sq8.properties
$ vector-harness build-index --config profiles/sift1m-hnsw-sq8.properties
$ vector-harness run --config profiles/sift1m-hnsw-sq8.properties
$ vector-harness load --config profiles/sift1m-ivf.properties
$ vector-harness build-index --config profiles/sift1m-ivf.properties
$ vector-harness run --config profiles/sift1m-ivf.properties
$ vector-harness report --run-id <hnsw-run> --compare <ivf-run>
```

The `profiles` directory holds example profiles: `smoke.properties` (a small synthetic corpus for
a quick functional check of a cluster, with the variant given by `-Drun.variant`),
`sift1m-hnsw-sq8.properties` and `sift1m-ivf.properties` (a comparable pair over SIFT1M),
`rag-1m-768.properties` (the multi-tenant document retrieval scenario at its default scale),
`streaming-hnsw.properties` (continuous ingestion, hot-spotted mutations, and region
disruptions), `drift-ivf.properties` (centroid drift and an explicit rebuild), and
`glove100-hnsw.properties` (an ann-benchmarks HDF5 corpus).

## Outputs

Everything lands under `run.output.dir`:

| Path | Contents |
|---|---|
| `corpus/<fingerprint>/truth-*.gt` | Cached ground truth of a panel over a corpus prefix |
| `tables/<suffix>/load.json` | Load state: corpus fingerprint, rows loaded, ingest rate, footprint, snapshots |
| `tables/<suffix>/build.json` | Build state: build time, index options, plan check, segments or centroids, footprint |
| `runs/<run-id>/manifest.json` | Everything a report needs: settings, corpus, cluster, validity, stages, flags |
| `runs/<run-id>/frontier.csv` | One row per layout, condition family, k, and sweep point of the frontier stage |
| `runs/<run-id>/per_query.csv` | Every scored query of the frontier stage, for paired comparisons |
| `runs/<run-id>/steady.csv`, `throughput.csv` | Per-group measurements and per-interval throughput of the steady windows |
| `runs/<run-id>/recall.csv` | The recall time series of the stream and steady stages |
| `runs/<run-id>/metrics.csv`, `cluster.csv` | Vector index metrics and cluster indicators in long format |
| `runs/<run-id>/lifecycle.csv`, `events.csv` | IVF centroid or HNSW region lifecycle samples, and the event log |
| `runs/<run-id>/summary.txt` | The text summary of the run |
| `runs/<run-id>/report/` | A single-run report: `report.txt` and the CSV datasets below |
| `reports/<a>_vs_<b>/` | A comparison: `report.txt`, `runs.csv`, `frontier.csv`, `pareto.csv`, `cost_at_recall.csv`, `recall_at_budget.csv`, `steady.csv`, `recall_timeseries.csv`, `paired.csv` |

Beyond the files above, the manifests record what makes a measurement trustworthy or not: the
validity section counts failed exact queries (`exact_failures` of `exact_attempted`), predicate
violations of the exact plan (`exact_violations`), and failed freshness probes
(`freshness_errors`); each steady window counts `freshness_errors` and the query errors of its
recall samples (the `query_errors` column of `recall.csv`); `load.json` names the run that left
the tables dirty (`dirty_by`) until its mutations are reverted, and the restore section counts
mutations whose commit failed but may have applied (`unresolved_rows`); the truth phase writes
its exact-plan check to `tables/<suffix>/truth.json` and fails when the exact plan disagrees with
the client-side ground truth.

## Reports

`report --run-id <id>` summarizes one run (`latest` names the most recent). With `--compare`, the
report compares the base run with the others. Comparable runs share the corpus fingerprint, the
query panel, and every execution parameter, differing only in the variant and its tuning, names and
locations, timeouts, ingest parallelism, and observation settings; the report refuses other
pairings unless `--force` is given, in which case it lists the differences. A comparison states the
cost each run pays to reach each recall target of `report.recall.targets` (p50, p99, throughput,
rows read per result, and RPCs per query, each minimized over the sweep points that reach the
target), the recall each run reaches within each p99 budget of `report.latency.budgets`, paired
per-query differences of latency and recall with bootstrap confidence intervals at matched
operating points, the build time and storage footprint, the write overhead, the selectivity curves
of filtered queries and where the fastest run changes, tenant-scoped against global scans, and the
lifecycle events and flags of each run.

## Settings

The defaults are those of the design. A default that depends on other settings names the
dependency. `run.repetitions` with `ingest.prebuild.fraction` below 1 needs `table.snapshot=true`,
so that every repetition streams the same rows into a freshly built index.

| Run | | |
|---|---|---|
| `run.variant` | `ivf` | exact, ivf, or hnsw; exactly one per run |
| `run.repetitions` | `1` | Repeated build-and-run cycles, for engine variance |
| `run.deterministic` | `false` | One mutation worker and quiesced samples |
| `run.id` | — | Identifier of the run; generated from the variant and time if absent |
| `run.output.dir` | `vector-harness-output` | Directory that holds corpus caches, table state, and run results |
| `run.profile.name` | — | Name of the profile; defaults to the base name of the profile file |
| `jdbc.url` | `jdbc:phoenix:localhost` | Phoenix JDBC URL of the cluster under test |

| Corpus | | |
|---|---|---|
| `corpus.source` | `synthetic` | synthetic, texmex, or hdf5 |
| `corpus.seed` | `42` | Master seed for every harness random choice |
| `corpus.path` | — | Directory (texmex) or file (hdf5) of a published corpus |
| `corpus.name` | — | File name prefix of a TEXMEX corpus, such as sift or gist; detected if absent |
| `corpus.rows` | `1000000` | Rows to generate, or a prefix of a published corpus |
| `corpus.dim` | `768` | Dimension (synthetic only; published files set it) |
| `corpus.clusters` | `200` | Mixture components |
| `corpus.cluster.weights` | `uniform` | uniform, or a Zipf exponent for cluster sizes |
| `corpus.variance.spread` | `1.0` | Ratio between the most diffuse and the tightest cluster |
| `corpus.intrinsic_dim` | `dim` | Rank of the subspace that each cluster lies near |
| `corpus.noise` | `0.05` | Standard deviation of the ambient noise added off the cluster subspace |
| `corpus.normalize` | `true for COSINE` | Project vectors onto the unit sphere |
| `corpus.query.boundary_fraction` | `0.0` | Share of panel queries between clusters |
| `corpus.query.ood_fraction` | `0.0` | Share of panel queries from outside the mixture |
| `corpus.drift.mode` | `none` | none, migrate, birth_death, or reassign |
| `corpus.drift.rate` | `0.0` | Strength of drift per minute of steady state |
| `corpus.filter.skew` | `uniform` | Zipf exponent for attribute vocabularies |
| `corpus.filter.categories` | `1000` | Size of the CATEGORY vocabulary (covered by IVF indexes) |
| `corpus.filter.sources` | `1000` | Size of the SOURCE vocabulary (never covered by vector indexes) |
| `corpus.filter.acl_tags` | `16` | Size of the ACL_TAG vocabulary |
| `corpus.tenants` | `1` | Number of distinct TENANT_ID values |
| `corpus.tenant.skew` | `uniform` | uniform, or a Zipf exponent for the rows per tenant |
| `corpus.body.bytes` | `128` | Length of the unindexed BODY text payload |

| Table | | |
|---|---|---|
| `table.schema` | `VH` | Phoenix schema of the harness tables |
| `table.suffix` | — | Suffix of the table names; defaults to the variant and the profile name |
| `table.layouts` | `corpus,profile` | Layouts to provision: corpus (DOC_CORPUS) and profile (DOC_PROFILE) |
| `table.multi_tenant` | `false` | Multi-tenant tables |
| `table.salt_buckets` | `0` | Data table salting |
| `table.regions` | `16` | Pre-split region count of the data table |
| `table.storage_scheme` | `ONE_CELL_PER_COLUMN` | Immutable storage scheme |
| `table.update_statistics` | `true` | Run UPDATE STATISTICS after the build |
| `table.snapshot` | `false` | Snapshot the unindexed data tables after load and restore them before each index build |

| Index | | |
|---|---|---|
| `index.metric` | `COSINE (L2 for SIFT and GIST)` | Index and query metric |
| `index.build.timeout` | `2h` | Longest wait for an index to become ACTIVE |
| `ivf.lists` | `1024` | Centroid count |
| `ivf.sample_size` | `50000` | Training sample |
| `ivf.async` | `false` | Asynchronous population |
| `hnsw.m` | `16` | Maximum node degree |
| `hnsw.ef_construction` | `100` | Construction beam width |
| `hnsw.alpha` | `1.2` | Vamana diversity factor |
| `hnsw.quantization` | `NONE` | NONE, SQ8, or PQ |
| `hnsw.pq_segments` | — | Required for PQ; must divide the dimension |

| Ingest | | |
|---|---|---|
| `ingest.prebuild.fraction` | `1.0` | Share of the corpus loaded before the index exists |
| `ingest.mode` | `jdbc` | jdbc or bulkload |
| `ingest.threads` | `4` | Concurrent ingestion workers |
| `ingest.batch.size` | `500` | Rows per JDBC commit |
| `ingest.stream.rate.per_sec` | `0` | Rate of the streaming ingest of the rows loaded after the build (0 means unthrottled) |

| Query | | |
|---|---|---|
| `query.k.values` | `10,100` | Values of k |
| `query.ivf.probe.values` | `1,4,16,64,all` | VECTOR_PROBE_COUNT sweep |
| `query.ivf.max_probe_limit` | `8` | MAX_PROBE_LIMIT for the run |
| `query.hnsw.ef.values` | `16,32,64,128,256,512` | HNSW_EF_SEARCH sweep |
| `query.filter.selectivity` | `0.001,0.01,0.1,1.0` | Generated predicate selectivities |
| `query.shapes` | `all` | Subset of the query shapes to run |
| `query.tenant_scoped.fraction` | `1.0` | Share of tenant-scoped queries (multi-tenant tables) |
| `query.mode` | `open` | open (fixed arrival rate) or closed |
| `query.rate.values` | — | Offered rates for an open-loop sweep |
| `query.rate.per_sec` | `20` | Offered query rate of the steady state when no rate sweep is given |
| `query.threads` | `8` | Query worker threads |
| `query.timeout` | `60s` | Timeout of one query |
| `query.sweep.panel_size` | `0` | Panel queries per sweep point of the frontier stage (0 means the whole panel) |
| `query.distance_bound.rank` | `0.5` | Radius of distance-bounded queries, as the fraction of k whose true distance sets it |
| `query.pk_range.fraction` | `0.25` | Share of the DOC_ID key space that PK_RANGE queries scan |

| Mutation | | |
|---|---|---|
| `mutation.rate.per_sec` | `0` | Combined mutation rate |
| `mutation.mix` | `0.3,0.5,0.2` | Insert, update, and delete shares |
| `mutation.update.move_fraction` | `0.0` | Share of updates that move a row to another cluster |
| `mutation.update.attribute_fraction` | `0.3` | Share of updates that change attributes only |
| `mutation.hotspot.regions` | `0` | Concentrate writes on this many regions (0 means none) |
| `mutation.threads` | `2` | Mutation worker threads |

| Steady state and lifecycle | | |
|---|---|---|
| `steady_state.warmup` | `1m` | Discarded warm-up |
| `steady_state.duration` | `10m` | Measured duration |
| `rebuild.auto.enabled` | `false` | Expected value of the IVF automatic rebuild setting |
| `rebuild.explicit.at` | — | Time of an explicit ALTER INDEX ... REBUILD |

| Recall | | |
|---|---|---|
| `recall.panel.size` | `1000` | Panel queries |
| `recall.sample.interval` | `30s` | Sampling interval |
| `recall.sample.mode` | `quiesced` | quiesced or online |
| `recall.sample.queries` | `100` | Panel queries scored at each steady-state sample (0 means the whole panel) |
| `recall.tie_epsilon` | `1e-6` | Relative tie tolerance |
| `recall.truth.margin` | `2` | Margin c of the live ground truth |
| `recall.bootstrap.resamples` | `1000` | Bootstrap resamples of the confidence intervals |
| `freshness.rate.per_sec` | `1` | Freshness probe rate |

| Observation | | |
|---|---|---|
| `observe.interval` | `15s` | Poll interval of the JMX and vector index metrics observer |
| `observe.fsck.interval` | `stage` | HNSW indexfsck inspect interval: stage (stage boundaries only), off, or a duration |
| `observe.jmx.mode` | `auto` | auto, http (RegionServer /jmx servlets), local (this JVM's MBeans), registry, or off |
| `observe.jmx.port` | — | RegionServer info port; defaults to the port each server reports |
| `observe.jmx.cache_ttl` | `11s` | Refresh period of the RegionServer JMX caches (metrics2 period plus a margin) that the HNSW frontier sweep waits out per group under the http and local readers; 0 records the probe rows as unknown |

| Disruption | | |
|---|---|---|
| `disruption.schedule` | — | Timed disruptions (move, restart, split, merge, compact), such as 2m:split,5m:compact |

| Report | | |
|---|---|---|
| `report.recall.targets` | `0.90,0.95,0.99` | Recall targets at which reports state the cost of each frontier |
| `report.latency.budgets` | `10ms` | p99 latency ceilings within which reports state the achievable recall |

## Integration tests

The integration tests run the harness against a Phoenix minicluster and validate the harness and
the basic functions of the indexes together. They follow the conventions of the Phoenix
integration tests, so a single class runs with

```
mvn verify -pl phoenix-vector-harness -DPhoenixPatchProcess -Dskip.code-coverage \
  -Dtest=NoUnitTests -Dit.test=VectorHarnessEndToEndIT -Dfailsafe.failIfNoSpecifiedTests=false
```

and `-am` builds the engine modules from the same source tree first, instead of using the
installed artifacts.

| Class | Cluster | Validates |
|---|---|---|
| `VectorHarnessEndToEndIT` | shared | Every phase through the command line for the exact, IVF, and HNSW variants over one corpus and panel: the exact plan agrees with the ground truth, full IVF probing finds the true neighbors, a wide HNSW beam finds most of them, every plan matches the variant's expectation, and the report compares the runs and refuses runs over another corpus |
| `VectorHarnessLayoutsIT` | shared | The relational and BSON layouts, multi-tenant and salted tables, the COSINE and INNER_PRODUCT metrics, the refusal of HNSW with INNER_PRODUCT, and bulk loading |
| `VectorHarnessLifecycleIT` | shared | Streaming ingest after the build, snapshots with repetitions, the vector index metrics in registry mode, online and deterministic sampling, splits and compactions during a steady window, invalid profiles and phases out of order, and `report --run-id latest` |
| `VectorIndexFunctionsIT` | shared | Basic index functions through harness components: engine distances against client-side distances, IVF full-probe results against brute force, filters, live inserts, moves and deletes without a flush, DDL validation against the variants' own checks, plans, HNSW durability across flush and compaction, IVF rebuild, metrics, and tenant isolation |
| `VectorHarnessDisruptionIT` | own, two RegionServers | Region moves, splits, merges, compactions, and a RegionServer restart during steady windows of IVF and HNSW runs: disruptions recorded, queries continuing, recall after the disruptions, region tracking and fsck, and restore |

## Design

The design of the harness, its workload model, and its measurement methodology are described in
https://gist.github.com/apurtell/3d8c4908eb51bb2246986f322efed233.
