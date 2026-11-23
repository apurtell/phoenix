<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Harness HOWTOs

These HOWTO guides provide a structured curriculum for Phoenix performance engineering using the
cluster and performance test harness in `dev/harness` alongside the Pherf load generator in
`phoenix-pherf`. The progression begins by establishing the measurement protocol and bringing up an
initial cluster, and it concludes with A/B comparisons, fault injection, profiling, and harness
extensions. Every hands-on HOWTO explicitly defines the engineering question it addresses, the
underlying hypothesis, the experimental controls and noise sources, the metrics to collect, the
methodology for interpreting results, the conditions that would invalidate the findings, and the
steps required for full reproduction.

The guides in this directory execute directly on the test harness, which orchestrates Pherf across
several workflows. By contrast, the companion Pherf guides in `phoenix-pherf/docs/howto` execute
Pherf against an existing, pre-provisioned cluster; see the [Pherf HOWTO index][pherf-index] for
that sequence.

## Reading paths

**(a) Prospective users evaluating the tooling.**

* For standalone Pherf evaluation, start with [P01][p01], [P02][p02], [P03][p03], and [P06][p06],
  and then proceed to [P04][p04] or [P05][p05] as your workload requires. The
  [Pherf HOWTO index][pherf-index] catalogues the complete sequence.
* For harness-managed workflows, follow [H01][h01], [H02][h02], [H04][h04], and [H05][h05].

**(b) Phoenix developers validating a code change.**

* Every validation cycle should run [H02][h02] for build smoke verification, [H04][h04] for
  baseline scenario execution, [H07][h07] to verify data correctness under load, and [H09][h09] to
  confirm fault resilience.
* Run [H08][h08] whenever a modification affects the write path, secondary indexes, or query
  execution plans.
* Run [H10][h10] when a change alters CPU utilization or memory allocation profiles.
* Run [H12][h12] to establish statistically rigorous proof of a performance effect.

**(c) Committers reviewing a performance claim.**

* Review the methodology and evidence using [H01][h01], [H03][h03], and [H12][h12], and then
  inspect [P06][p06], [H05][h05] for tail latency behavior, and [H10][h10] for root-cause
  profiling evidence.
* Verify the submission against the review checklist defined in [H01][h01]: the runs must use the
  measurement class on a quiet host with no contamination tag, provide at least 3 replicates per
  side (or 5 for a gate), demonstrate a clean A/A comparison, show a confidence interval that
  excludes zero with an effect size of at least 10%, confirm equal operation counts, achieve PASS
  verdicts for both attainment and saturation, collect enough samples for each reported
  percentile, prove that the client was not the bottleneck, keep the version set and random seed
  identical across sides, and supply archived result bundles.

**(d) Contributors extending the harness or Pherf.**

* Begin with [H13][h13] and [P07][p07] for extension patterns, alongside [H03][h03] for the bundle
  layout and exit code semantics and [P02][p02] for the scenario validator rules.
* Consult the formal architectural contracts in [driver-contract.md][driver],
  [PLUGINS.md][plugins], [backends.md][backends], [LATENCY.md][latency], [VERDICT.md][verdict],
  and [ANALYSIS.md][analysis].

## The HOWTOs

Resource and duration estimates in the table below are approximate; consult the "Before you start"
table in each individual guide for a detailed breakdown. Memory figures represent the aggregate
container memory limits admitted by the preflight checks. The "Live" column indicates the
verification level completed for each guide: "yes" denotes full execution against a live cluster,
"no" indicates static verification against the implementation code only, and "offline" denotes a
workflow that operates without a running cluster.

| # | Purpose | Cost | Live |
|---|---|---|---|
| [H01][h01] | Establish a trustworthy measurement protocol. | 10 min, no cluster | offline |
| [H02][h02] | Compile code, boot a cluster, and smoke-test a build. | 45 min, 8.5-18.8 GiB | yes |
| [H03][h03] | Inspect, validate, and archive bundles; script exit codes. | 20 min, 8.5 GiB | yes |
| [H04][h04] | Execute a Pherf scenario through the test harness. | 12 min, 8.5 GiB | yes |
| [H05][h05] | Verify an open-loop latency SLO and soak. | 25 min and the soak, 8.5 GiB | partly |
| [H06][h06] | Identify maximum sustainable throughput and the knee. | 30 min, 8.5 GiB | partly |
| [H07][h07] | Verify read-what-you-wrote correctness under load. | 10 min, 8.5 GiB | partly |
| [H08][h08] | Measure ingest, inspect plans, and cost a secondary index. | 25 min, 11.8 GiB | no |
| [H09][h09] | Inject service faults and quantify recovery behavior. | 30 min, 11.0 GiB | yes |
| [H10][h10] | Profile an active workload run and isolate root causes. | 25 min, 8.5 GiB | yes |
| [H11][h11] | Plan capacity, hotspots on 1, 3, 5 RegionServers. | 80-100 min, up to 18.9 GiB | no |
| [H12][h12] | Compare builds, configs, versions for regressions. | 2 builds, 30 min, 8.5 GiB | no |
| [H13][h13] | Extend the harness with plug-ins, checks, and drivers. | 20 min, 8.5 GiB | no |

A status of "partly" designates a guide whose procedure was updated after its initial live cluster
verification. Specifically, H05 and H06 were expanded to use longer measurement windows, and H07
was extended with a correctness proof under concurrent load; those added or lengthened runs were
verified statically against the code, and each guide notes which output excerpts originated from
the earlier live executions. Finally, H13 was not executed during the live verification pass: both
its custom plug-in execution (steps 16 and 17) and the example plug-in workflow, which requires
rebuilding the container image, were verified directly against the source code.

## Shared conventions

* Execute every command from the root directory of the repository. Each guide begins with the
  shell initialization block below inside its "Before you start" section, substituting its own
  guide identifier (such as `h02` or `h14`):

  ```sh
  export PATH="$PWD/dev/harness/bin:$PATH"
  export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
  HOWTO="$PX_OUT/howto/hNN"; mkdir -p "$HOWTO"
  ```

* Each guide materializes its supporting input files (workload scenarios, SQL schemas, suite
  definitions, and `pherf.properties` overrides) under `$HOWTO` using shell heredocs. Pass driver
  arguments as absolute paths, such as `"$HOWTO/scenario.xml"`, because drivers resolve relative
  paths solely against a suite definition file.
* All generated artifacts and outputs are written exclusively under `$PX_OUT`. The guides never
  hardcode user-specific directories, relying instead on `$PX_OUT` and `$HOME`, and all text and
  command lines wrap at a maximum of 100 columns.
* Each command tears down only the resources that it provisioned. Every guide brings its cluster up
  once via `px-cluster up` (or `px-pherf start` for the distributed fleet tier) and concludes with
  `px-cluster down`, which operates idempotently and exits with status 0 when no cluster is
  recorded. Neither `px-run` nor `px-suite` tears down the cluster, so neither requires `--keep`; a
  `px-suite` session stops only the role groups that it started itself. By contrast, `px-pherf run`
  tears down what it provisioned unless `--keep` is passed, and `px-cluster matrix` tears down the
  cluster for each version set after its suite finishes.
* `px-cluster up` writes the persistent cluster record, capturing the execution class alongside
  every `-e` and `--config` value supplied at bring-up. Subsequent commands against the cluster
  inherit their default settings from this record, so the execution class and bring-up parameters
  are specified only once at `up`. Because the execution class recorded in the cluster record takes
  precedence over any class declared inside a suite file, executing a suite against the active
  cluster requires no `--class` option (see H14, step 27, and H15, steps 9, 21, and 23), whereas an
  explicit command-line class option overrides both.
* Because `px-run` writes the path of the resulting bundle to standard output immediately upon
  creating the bundle, the shell idiom `RUN="$(px-run ...)"` captures that location for subsequent
  inspection. Similarly, a `px-suite` or `px-pherf run` session emits one result row per test—with
  the seventh column recording the test's bundle path—and concludes with a summary `result` row
  whose fourth column records the session directory; the guides capture these rows via `tee` and
  extract the artifact handles directly from them.
* The default laptop configuration targets the validation class, allocating approximately 8.5 GiB
  of aggregate container memory limits with 1 RegionServer. This profile produces `ADVISORY`
  timing measurements alongside authoritative operation counts.
* Running the measurement class on a 24 GiB container engine requires passing
  `--config dev/harness/docker/env/override-24gib.env`, which tags every resulting bundle with
  `contamination sizing`. Every guide that presents timing data notes this caveat explicitly.
* Running a JDBC or HBase client directly on the host machine is unsupported. All client workloads
  and utilities execute inside the `client-0` or `tooling-0` containers, invoked either through
  harness drivers or via `px-cluster exec`.
* Pherf's offline modes, `-validate` and `-analyze`, operate without a running cluster and execute
  directly on the host machine. During compilation, `px-cluster build` records the build JDK as
  `java_home` and the complete offline classpath as `pherf_classpath` inside
  `$PX_OUT/build/phoenix/build.tsv`, combining the Pherf, embedded client, and bson jars with the
  JAXB and log4j jars required by Pherf on JDK 11 and later. Guide [H12][h12], step 8, reads both
  recorded rows, while the standalone Pherf guides construct the equivalent classpath using the
  `pherf-env.sh` helper in [P01][p01], step 3. Omitting the JAXB jars causes execution to abort
  with `NoClassDefFoundError: javax/xml/bind/JAXBContext`, whereas omitting the log4j jars
  suppresses diagnostic logging when a run is rejected. Note that `bin/pherf-standalone.py` omits
  the JAXB dependencies on JDK 11 and later; consult the [Pherf HOWTO index][pherf-index] for
  further details. Only the HdrHistogram log processor invoked in H05, H06, and H10 can execute
  using the standalone Pherf jar on its own.
* Because standalone Pherf writes its `RESULTS` and `RESULTS_HDR` output directories relative to
  the current working directory, the standalone Pherf guides always switch first to a dedicated
  workspace outside the source tree, such as `$HOME/pherf-work`.

## Command style

All guides follow a uniform command and scripting discipline for harness operations.

* **No exit echoes.** Whenever a harness command fails, it concludes its standard error stream with
  a single `(exit N, name)` diagnostic line, so the guides never append `echo "exit $?"` to a
  harness command. Any step that specifically demonstrates an exit code includes that diagnostic
  line in its transcript. Following an external non-harness command where the exit status is the
  subject of verification, such as `Pherf -analyze` or `diff`, a labeled statement such as
  `echo "analyze exit $?"` is retained. Capturing `$?` is likewise preserved when the exit code is
  recorded as data, such as a status column in a TSV file.
* **One lifecycle.** Each guide brings the cluster up once and runs `px-cluster down` as its final
  step, while automated scripts register `trap 'px-cluster down' EXIT`. No guide verifies a
  teardown by running `px-cluster status` or counting Docker labels.
* **No repeated bring-up values.** The execution class and `-e` configuration values are specified
  once at `up` and supplied thereafter from the persisted cluster record. The `-e PX_PHERF=1`
  setting is passed only to `px-cluster build`. The `--timeout` flag is supplied only when a run
  requires an execution bound other than `PX_TIMEOUT_RUN` (3600 s), such as a soak test that
  exceeds that duration or a test exercising the timeout bound itself.
* **Captured handles, not discovery.** Subsequent steps reference the explicit handles emitted by
  the tools: the bundle path printed by `px-run`, the summary `result` row emitted by `px-suite`,
  `px-pherf run`, and `px-cluster
  matrix`, and the bundle column of the result rows. No guide lists `$PX_OUT` to discover the most
  recent run or session, or reads a test's `stdout.txt`.
* **One override style, long options.** Configuration overrides use `-e NAME=VALUE` rather than
  inline environment prefixes or `export` and `unset` pairs. Every command-line option is written in
  its long form; only `-h`, `-v`, `-n`, and `-e` support short forms.
* **Front doors only.** Commands invoke only the public entry points in `dev/harness/bin`, never an
  internal script under `scripts/`: `px-cluster build` compiles project artifacts and, when invoked
  with `--dry-run`, validates the configured version set ([H12][h12], step 14),
  `px-cluster up --dry-run` runs the preflight checks, and `px-cluster push` promotes a result
  bundle.
* **Effective verdicts.** The guides always inspect `report/verdicts.tsv` or the output of
  `px-report`, never the raw `verdicts.tsv` file inside a bundle.
* **One JSON reader.** All JSON parsing uses `jq`, which every guide that inspects JSON lists as a
  prerequisite even though it is not a dependency of the harness itself. Guides [H14][h14] and
  [H15][h15] print 64-bit slot seeds and therefore require `jq` 1.7 or later, because earlier
  versions of `jq` round 64-bit integers.
* **Shared values in variables.** Reused driver arguments and the suites directory path
  (`SUITES="$PWD/dev/harness/suites"`) are defined once in shell variables.
* **Transcripts.** Commands and their output are presented in separate `sh` and `text` code blocks
  so that every command block can be pasted directly, and log prefixes are abbreviated as
  `... INFO  px-x:`.

## The run classes and the authority rule

The harness distinguishes between two execution classes: a validation run verifies build integrity
and workload correctness on a developer laptop, whereas a measurement run captures authoritative
timing evidence on a quiet host configured with full measurement sizing. Operation count, workload
share, and functional correctness verdicts carry full authority across all runs regardless of
class. By contrast, any failed duration verdict is downgraded to `ADVISORY` unless the run
executes in the measurement class on a quiet host with full measurement sizing.

## Coverage of the performance engineering tasks

In the matrix below, an H prefix denotes a harness guide in this directory and a P prefix denotes
a standalone Pherf guide in `phoenix-pherf/docs/howto`.

| Task | HOWTOs |
|---|---|
| Baselining and the measurement protocol | H01; H04 (warmup), H12 (replicates), P01 |
| Bring-up and smoke, one node and several | H02, H11 |
| Workload characterization and operation mixes | P03, H07 |
| Closed loop, open loop, coordinated omission | H05, H09, P03 |
| Latency SLO validation from HDR histograms | H05, P06 |
| Maximum sustainable throughput, the knee | H06 |
| Capacity planning and scale-out | H11, H01 (sizing) |
| Bulk load, burst and sustained ingest throughput | H08 |
| Read-what-you-wrote correctness under load | H07, P04 |
| Hotspots and skew | H11, P04 |
| Multi-tenant workloads | P05 |
| Schema and type coverage | P05, H07 |
| Query plans and secondary index cost | H08 |
| Fault injection, resilience, MTTR | H09 |
| Soak and endurance | H05, H08 (sustained ingest) |
| Profiling and root cause | H10, H08 (trace counters) |
| Noise control and result validity | H01, H03, H05 |
| Regression detection and A/B with replicates | H12, P06 |
| Version and configuration matrices | H12 |
| Scenario authoring and offline validation | P02 |
| Extending the harness and Pherf | H13, P07 |
| Reading and archiving result bundles | H03 |
| CI and automation | H03 |
| Reproducibility | H01, H03, H12, P04 |

## Known defects

Live verification of these HOWTOs surfaced four defects. All four have been resolved, though
builds prior to the fixes still exhibit the earlier behavior.
* **Memory retention in the Pherf detail report**, resolved in 63c9dd0ec2: builds prior to that
  commit exhausted a 1 GiB heap once a single query set exceeded approximately 10,000 operations.
* **Spurious HBase `ERROR` log line.** The HBase Master can log `RemoteProcedureDispatcher:
  DelayQueue for RemoteProcedureDispatcher is not empty when timed waiting elapsed` while a table
  is being dropped, which caused `cluster.log` to report `FAIL` in 2 of 59 runs. The harness allow
  list now includes this signature so that it is not counted as an error, and the `cluster.log`
  detail reports `1 allowed: hbase-dispatcher-delayqueue` (H03 step 4).
* **Missing region count prior to a disruption.** Immediately after a rebalance, a RegionServer
  can respond to JMX queries without publishing `regionCount`, which previously prevented the next
  `--disrupt` entry from being injected. `px-run` now retries reading the region count within the
  readiness timeout bound (H09 step 13).
* **`signal STOP` did not pause the JVM.** Container signals were previously delivered to `tini`,
  the initial process of the container. Every signal other than `KILL` and `TERM` is now delivered
  directly to the JVM process, and any `STOP` signal that fails to place the JVM into a stopped
  state is reported as a failure (H09 step 13).

## Out of scope

* Distributed and multi-client load generation are not yet covered because Distributed Pherf (W2)
  remains under development, and its companion guides will follow once the D track lands.
* Kubernetes backends, service mesh integration, and container image promotion are deferred until
  the K track lands; in the current release, `px-k3d` exits with code 5.
* Network partitions, synthetic network latency injection, and disk-full fault modes are excluded
  because the harness fault injector supports only process kill, signal delivery, and container
  restart actions (H09).
* Direct execution of JDBC or HBase clients on the host machine is excluded by architectural
  design.
* The Phoenix Query Server (PQS) and the thin JDBC driver are not provisioned by the harness;
  Pherf's `-t` option is noted only for reference.
* Workloads exercising vector data types are not covered in this series.
* MapReduce bulk loading via `CsvBulkLoadTool` is omitted because the MapReduce job client and
  cluster configuration inside the container image remain unverified
  ([H08, "Not covered"][h08-mr]).

## Reference

* Consult [../../README.md](../../README.md) for the harness command reference, operational
  policy, configuration parameters, host safety guards, exit codes, and verdict severity rules.
* See [../bundle-format.md][bundle], [../driver-contract.md][driver], [../PLUGINS.md][plugins],
  and [../backends.md][backends] for the formal harness architectural contracts.
* See [README.md][pherf-readme], [LATENCY.md][latency], [VERDICT.md][verdict], and
  [ANALYSIS.md][analysis] for the Pherf subsystem contracts and specifications.
* Browse the [Pherf HOWTO index][pherf-index] for standalone load generation guides.

[bundle]: ../bundle-format.md
[driver]: ../driver-contract.md
[plugins]: ../PLUGINS.md
[backends]: ../backends.md
[pherf-index]: ../../../../phoenix-pherf/docs/howto/README.md
[pherf-readme]: ../../../../phoenix-pherf/README.md
[latency]: ../../../../phoenix-pherf/LATENCY.md
[verdict]: ../../../../phoenix-pherf/VERDICT.md
[analysis]: ../../../../phoenix-pherf/ANALYSIS.md

[h01]: 01-measurement-protocol.md
[h02]: 02-first-cluster-and-smoke.md
[h03]: 03-result-bundles-and-automation.md
[h04]: 04-pherf-through-the-harness.md
[h05]: 05-latency-slo-and-soak.md
[h06]: 06-saturation-knee.md
[h07]: 07-read-what-you-wrote.md
[h08]: 08-ingest-plans-and-index-cost.md
[h09]: 09-fault-injection-and-recovery.md
[h10]: 10-profiling-and-root-cause.md
[h11]: 11-scale-out-capacity-and-skew.md
[h12]: 12-regression-ab-and-matrices.md
[h13]: 13-extending-the-harness.md
[h08-mr]: 08-ingest-plans-and-index-cost.md#not-covered-mapreduce-bulk-load
[p01]: ../../../../phoenix-pherf/docs/howto/01-first-pherf-run.md
[p02]: ../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md
[p03]: ../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md
[p04]: ../../../../phoenix-pherf/docs/howto/04-co-generation.md
[p05]: ../../../../phoenix-pherf/docs/howto/05-types-and-multi-tenant.md
[p06]: ../../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md
[p07]: ../../../../phoenix-pherf/docs/howto/07-extending-pherf.md
