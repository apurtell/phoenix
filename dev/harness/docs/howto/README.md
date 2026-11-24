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

The guides in this directory execute workflows on the test harness, which drives Pherf across
several scenarios. By contrast, the standalone Pherf guides in `phoenix-pherf/docs/howto` run Pherf
against an existing, pre-provisioned cluster; see the [Pherf HOWTO index][pherf-index]. The
Distributed Pherf guides in `phoenix-pherf-distributed/docs/howto` operate a central controller and
a fleet of load-generation agents; see the [Distributed Pherf HOWTO index][d-index]. Guides H14 and
H15 orchestrate that distributed fleet directly through the harness.

## Reading paths

**(a) Prospective users evaluating the tooling.**

* For standalone Pherf evaluation, start with [P01][p01], [P02][p02], [P03][p03], and [P06][p06],
  and then proceed to [P04][p04] or [P05][p05] as your workload requires; the
  [Pherf HOWTO index][pherf-index] catalogues the complete sequence.
* For harness-managed workflows, follow [H01][h01], [H02][h02], [H04][h04], and [H05][h05].
* For distributed fleet load generation, start with [D01][d01] to learn the controller, agents, and
  REST API, and then proceed to [H14][h14] to orchestrate the fleet through the harness.

**(b) Phoenix developers validating a code change.**

* Every validation pass should run [H02][h02] for build smoke testing, [H04][h04] for scenario
  execution, [H07][h07] for data correctness under load, and [H09][h09] for fault resilience.
* Run [H08][h08] whenever a change modifies the write path, secondary indexes, or query plans.
* Run [H10][h10] when a change alters CPU utilization or memory allocation cost.
* Run [H12][h12] to establish statistically rigorous proof of a performance effect.
* Run [H14][h14] (`fleet-rww.suite` for read-what-you-wrote verification across agents) and
  [H15][h15] (fleet replicates, A/B comparisons, and fault injection under fleet load) when a
  change must be validated under high client concurrency.

**(c) Committers reviewing a performance claim.**

* Review the experimental design and evidence using [H01][h01], [H03][h03], and [H12][h12], and
  then consult [P06][p06], [H05][h05] for tail latency analysis, and [H10][h10] for root-cause
  profiling evidence.
* When evaluating a claim measured across a distributed fleet, consult [D04][d04] for merged
  histograms, client-bound detection, and start skew, alongside [H15][h15] for fleet replicates and
  A/B analysis.
* Verify every submission against the review checklist in [H01][h01]: the runs must use the
  measurement class on a quiet host with no contamination tag, provide at least 3 replicates per
  side (or 5 for a gate), demonstrate a clean A/A comparison, show a confidence interval that
  excludes zero with an effect size of at least 10%, confirm equal operation counts, record PASS
  for both attainment and saturation, collect sufficient samples for each percentile, prove that
  the client was not the bottleneck (see [D04][d04] for a fleet), preserve the same version set and
  seed across sides, and supply archived bundles.

**(d) Contributors extending the harness or Pherf.**

* Start with [H13][h13] and [P07][p07] for extension workflows, alongside [H03][h03] for the bundle
  structure and exit codes and [P02][p02] for the scenario validator rules.
* Next, consult the formal architectural contracts: [driver-contract.md][driver],
  [PLUGINS.md][plugins], [backends.md][backends], [LATENCY.md][latency], [VERDICT.md][verdict], and
  [ANALYSIS.md][analysis].
* For distributed fleet extensions, review [D01][d01], [D03][d03], and the
  [README of phoenix-pherf-distributed][module].

## The HOWTOs

Resource and duration estimates in the table below are approximate; consult the "Before you start"
table in each individual guide for a detailed breakdown. Memory figures represent the aggregate
container memory limits admitted by the preflight checks. The "Live" column indicates the
verification level completed for each guide: "yes" denotes full execution against a live cluster,
"no" indicates static verification against the implementation code only, and "offline" denotes a
workflow that operates without a running cluster.

| # | Purpose | Cost | Live |
|---|---|---|---|
| [H01][h01] | Plan a trustworthy performance measurement. | 10 min, no cluster | offline |
| [H02][h02] | Build, bring up a cluster, and smoke-test a build. | 45 min, 8.5-18.8 GiB | yes |
| [H03][h03] | Read, validate, archive bundles; automate on exit codes. | 20 min, 8.5 GiB | yes |
| [H04][h04] | Execute a Pherf scenario through the test harness. | 12 min, 8.5 GiB | yes |
| [H05][h05] | Validate an open-loop SLO and soak (code only). | 25 min + soak, 8.5 GiB | yes |
| [H06][h06] | Find the maximum sustainable throughput and the knee. | 30 min, 8.5 GiB | yes |
| [H07][h07] | Prove read-what-you-wrote correctness under load. | 10 min, 8.5 GiB | yes |
| [H08][h08] | Measure ingest, check plans, and cost a secondary index. | 25 min, 11.8 GiB | no |
| [H09][h09] | Inject service faults and quantify cluster recovery. | 30 min, 11.0 GiB | yes |
| [H10][h10] | Profile an active workload run and isolate the root cause. | 25 min, 8.5 GiB | yes |
| [H11][h11] | Plan capacity on 1, 3, and 5 RegionServers; hotspots. | 80-100 min, 18.9 GiB | no |
| [H12][h12] | Detect regressions; compare builds and configs. | 2 builds + 30 min, 8.5 GiB | no |
| [H13][h13] | Extend the harness with plug-ins, checks, and drivers. | 20 min, 8.5 GiB | no |
| [H14][h14] | Run Pherf across a fleet through the harness. | 15 min + build, 12.5-15 GiB | yes |
| [H15][h15] | Run fleet replicates, A/B with -analyze, faults under load. | 40 min, 15 GiB | yes |

Certain sections within the "yes" guides were verified statically against the codebase rather than
executed live: the one-hour soak section of H05, and part 3 of H15 (because executing the
measurement class on a 24 GiB container engine requires a quiet host, that section cites the H3
window 2 evidence). Step 12 of H09 references the live absent-instance proof from round D7, which
requires a manually timed container deletion. The live verification pass did not execute H13: both
its custom plug-in execution (steps 15 and 16) and the example plug-in workflow, which requires
rebuilding the container image, were verified directly against the source code. A second live
verification pass on a Docker Desktop engine configured with 24001 MiB of memory executed steps 10
through 14 of H04, the updated procedures for H05 through H07, and guides H14 and H15.

The companion guides in the Distributed Pherf series cover standalone fleet operations: [D01][d01]
deploys a standalone controller and agent fleet and exercises the REST API; [D02][d02] executes a
multi-step workload plan across a fleet; [D03][d03] covers run cancellation, agent fault handling,
and controller restarts; and [D04][d04] demonstrates how to scale out the load generator and
interpret aggregated fleet latency accurately.

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

In the coverage matrix below, an H prefix denotes a harness HOWTO in this directory, a P prefix
denotes a standalone Pherf HOWTO in `phoenix-pherf/docs/howto`, and a D prefix denotes a
Distributed Pherf HOWTO in `phoenix-pherf-distributed/docs/howto`. The
[Distributed Pherf HOWTO index][d-index] provides the complete feature coverage matrix for the
distributed fleet.

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
| Read-what-you-wrote correctness under load | H07, P04, H14 (`fleet-rww.suite`) |
| Hotspots and skew | H11, P04 |
| Multi-tenant workloads | P05 |
| Schema and type coverage | P05, H07 |
| Query plans and secondary index cost | H08 |
| Fault injection, resilience, MTTR | H09, H15 |
| Soak and endurance | H05, H08 (sustained ingest) |
| Profiling and root cause | H10, H08 (trace counters) |
| Noise control and result validity | H01, H03, H05 |
| Warmup and a cold start inside the measured window | H04, H05 (step 15), D04 |
| The A/A noise floor and drift between sets | H15 (step 14), H12 |
| Regression detection and A/B with replicates | H12, P06, H15 |
| Version and configuration matrices | H12 |
| Scenario authoring and offline validation | P02 |
| Extending the harness and Pherf | H13, P07 |
| Reading and archiving result bundles | H03 |
| CI and automation | H03 |
| Reproducibility | H01, H03, H12, P04 |
| Distributed load generation (fleet) | H14, D01, D02 |
| Load-generator scale-out: client vs cluster bound | D04, H06, H11 |
| Coordinated omission across agents, merged histograms | D04 |
| Per-agent start skew and clock offsets | D04, H14 |
| Fleet replicates and A/B | H15 |
| Resilience under fleet load | H15, D03 |

## Known defects

Live verification of these HOWTOs and of the D7 proof surfaced six defects. All six have been
resolved; builds compiled prior to each fix still exhibit the earlier behavior.
* **Memory retention in the Pherf detail report**, resolved in 63c9dd0ec2: builds prior to that fix
  exhausted a 1 GiB heap once a single query set exceeded approximately 10,000 operations.
* **Spurious HBase `ERROR` log line.** The HBase Master can log `RemoteProcedureDispatcher:
  DelayQueue for RemoteProcedureDispatcher is not empty when timed waiting elapsed` while a table
  is being dropped, which caused `cluster.log` to evaluate to `FAIL` in 2 of 59 runs. The harness
  allow list now recognizes this signature so that it is not counted as an error, and the
  `cluster.log` detail records `1 allowed: hbase-dispatcher-delayqueue` (see H03, step 3).
* **Missing region count prior to a disruption.** Immediately following a rebalance, a RegionServer
  can respond to JMX queries without publishing `regionCount`, which previously prevented the next
  `--disrupt` action from being injected. `px-run` now retries reading the region count within the
  configured readiness timeout bound (see H09, step 12).
* **`signal STOP` did not pause the JVM.** Container signals were previously delivered to `tini`,
  the initial process of the container. Every signal other than `KILL` and `TERM` is now routed
  directly to the JVM process, and any `STOP` signal that fails to place the JVM into a stopped
  state is immediately recorded as a failure (see H09, step 12).
* **The recovery gate missed a process exit after the readiness check observed the instance as
  ready**, resolved in round H3b. In a cluster with 2 RegionServers, a 50 s pause expired the
  session of RegionServer 0, causing it to abort upon resuming execution. Because the readiness
  barrier had already observed the instance as ready, it continued waiting only on the Master,
  which reported `1 of 2 RegionServers checked in`, and the gate waited out the full 600 s of
  `PX_TIMEOUT_READY` before terminating with `timeout, exit 4`. The gate now polls the barrier in
  1 s intervals and verifies the instance state prior to each interval, failing within seconds of
  an unexpected process termination (see H15, step 23, and H09, step 12).
* **Missing fleet report when the verb time bound interrupted a fleet run**, resolved in round H3b.
  Upon receiving TERM, the `pherf-distributed` driver previously cancelled the controller run
  without retrieving its `CANCELLED` bundle. It now copies that bundle into
  `drivers/pherf-distributed/controller/` within the 10 s grace period that `px-run` waits after
  sending TERM (H15, "The run bound").

## Out of scope

* Kubernetes backends, service mesh integration, and image promotion are deferred to the upcoming
  Kubernetes HOWTOs in the K track. The harness README documents the local workflow: run
  `px-k3d create` followed by `px-cluster --backend k8s` commands; `px-cluster down` tears down
  only the harness cluster, whereas `px-cluster down --label k3d.cluster=px` also deletes the local
  k3d cluster.
* Network partitions, synthetic latency injection, and disk-full fault modes are not supported; the
  harness fault injector provides process kill, signal delivery, and service restart actions only
  (H09).
* Direct execution of a JDBC or HBase client on the host machine is excluded by architectural
  design.
* The Phoenix Query Server (PQS) and the thin JDBC driver are not provisioned by the harness;
  Pherf's `-t` option is mentioned for reference only.
* Workloads exercising vector data types are not covered in this curriculum.
* MapReduce bulk loading via `CsvBulkLoadTool` is not covered because the MapReduce job client and
  cluster configuration inside the container image remain unverified
  ([H08, "Not covered"][h08-mr]).

## Reference

* Consult [../../README.md](../../README.md) for the harness command reference, operational policy,
  configuration parameters, host safety guards, exit codes, and verdict severity rules.
* See [../bundle-format.md][bundle], [../driver-contract.md][driver], [../PLUGINS.md][plugins], and
  [../backends.md][backends] for the formal harness architectural contracts.
* See [README.md][pherf-readme], [LATENCY.md][latency], [VERDICT.md][verdict], and
  [ANALYSIS.md][analysis] for the Pherf subsystem contracts.
* Browse the [Pherf HOWTO index][pherf-index] for standalone load-generation guides.
* Consult the [README of phoenix-pherf-distributed][module] for the architecture of the Distributed
  Pherf processes, workload plans, work splitting, REST API, and run bundle layout.
* Browse the [Distributed Pherf HOWTO index][d-index] for distributed fleet guides.

[bundle]: ../bundle-format.md
[driver]: ../driver-contract.md
[plugins]: ../PLUGINS.md
[backends]: ../backends.md
[pherf-index]: ../../../../phoenix-pherf/docs/howto/README.md
[pherf-readme]: ../../../../phoenix-pherf/README.md
[latency]: ../../../../phoenix-pherf/LATENCY.md
[verdict]: ../../../../phoenix-pherf/VERDICT.md
[analysis]: ../../../../phoenix-pherf/ANALYSIS.md
[module]: ../../../../phoenix-pherf-distributed/README.md
[d-index]: ../../../../phoenix-pherf-distributed/docs/howto/README.md
[d01]: ../../../../phoenix-pherf-distributed/docs/howto/01-standalone-fleet-and-rest-api.md
[d02]: ../../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[d03]: ../../../../phoenix-pherf-distributed/docs/howto/03-faults-cancel-and-restart.md
[d04]: ../../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md

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
[h14]: 14-fleet-through-the-harness.md
[h15]: 15-fleet-replicates-ab-and-faults.md
[h08-mr]: 08-ingest-plans-and-index-cost.md#not-covered-mapreduce-bulk-load
[p01]: ../../../../phoenix-pherf/docs/howto/01-first-pherf-run.md
[p02]: ../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md
[p03]: ../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md
[p04]: ../../../../phoenix-pherf/docs/howto/04-co-generation.md
[p05]: ../../../../phoenix-pherf/docs/howto/05-types-and-multi-tenant.md
[p06]: ../../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md
[p07]: ../../../../phoenix-pherf/docs/howto/07-extending-pherf.md
