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

# Pherf HOWTOs

These HOWTO guides explain how to use Pherf, the Apache Phoenix performance testing tool, against
an existing cluster. Each guide begins with a specific question that a performance measurement is
designed to answer, and then details the methodology, execution steps, result interpretation, and
reproduction procedure. Running these guides requires only Pherf, a JDK, and the target cluster.
The Phoenix cluster and performance test harness under `dev/harness` is optional; it provisions a
local Docker-based cluster and executes Pherf on your behalf (see
[the harness HOWTOs](../../../dev/harness/docs/howto/README.md)).

The Pherf reference documents define the foundational contracts applied throughout these guides:

- [README.md](../../README.md) documents the command-line options, scenario elements, data
  generation rules, and scenario validation mechanics.
- [LATENCY.md](../../LATENCY.md) specifies the latency report structure, histogram logs, and legacy
  reporting formats.
- [VERDICT.md](../../VERDICT.md) defines the run health verdict evaluation model and its diagnostic
  thresholds.
- [ANALYSIS.md](../../ANALYSIS.md) describes the offline statistical analysis of replicate runs and
  its associated exit codes.
- [config/reference](../../config/reference/) provides three co-generated reference scenarios along
  with their corresponding smoke profiles
  ([relational](../../config/reference/relational_scenario.xml),
  [json](../../config/reference/json_scenario.xml), and
  [bson](../../config/reference/bson_scenario.xml)).

## The HOWTOs

Each entry summarizes the objective of the guide and its approximate resource and time cost. The
"Before you start" section of each HOWTO provides the complete cost breakdown.

1. [How to run your first Pherf workload on a cluster][p01]. This guide covers building Pherf,
   authoring a scenario, loading data, executing a closed-loop workload with warmup and replicate
   runs, and locating the generated outputs. The estimated cost includes a user cluster, a warm
   build of about 8 min, workload runs lasting a few minutes, and a 3 GiB client heap.
2. [How to author a scenario and validate it offline][p02]. This guide explains how to detect
   scenario defects without a cluster by using the validator, its rule IDs, the pre-run gate, and
   unknown-tag detection. The procedure executes entirely offline, takes about 5 min, and requires
   under 1 MiB.
3. [How to drive an open-loop workload with profiles and operation mixes][p03]. This guide
   demonstrates how to offer load at a fixed rate, apply step or ramp profiles, combine multiple
   operation kinds, and override the load curve at launch time. The estimated cost is a user
   cluster—or a harness cluster via option B of step 2 (which is verified against the codebase
   rather than executed on a live cluster)—along with about 15 min of runs and under 50 MB.
4. [How to generate verifiable data with co-generation][p04]. This guide shows how to compute each
   column value as a deterministic function of the row index and a seed so that read operations
   reliably locate the records created by write operations. The estimated cost is a user
   cluster—or a harness cluster via option B of P03 step 2 (which is verified against the codebase
   rather than executed on a live cluster)—along with about 10 min of runs and under 50 MB.
5. [How to cover Phoenix types and multi-tenant workloads][p05]. This guide exercises array types,
   VARBINARY_ENCODED columns, JSON and BSON documents, tenant-specific views, and the `-mt`
   multi-tenant load profile. Running the guide requires a user cluster and takes about 20 min.
6. [How to read Pherf results, grade a run and compare replicates][p06]. This guide explains how
   to interpret the latency report and run health verdict, and how to compare replicate sets using
   `-analyze`. For cost, part 1 runs on a cluster in about 10 min, while part 2 runs offline in
   about 5 min, requiring under 50 MB in total.
7. [How to extend Pherf with providers, report sections, metrics sinks and generators][p07].
   This guide demonstrates how to implement, package, and verify custom Pherf extension points.
   The required cost consists of an offline build of one JAR and a user cluster to execute it.

Using a harness cluster adds about 5 min for cluster bring-up and requires 8704 MiB in container
resource limits (corresponding to the validation cluster class with 1 RegionServer).

## Reading paths

**(a) Prospective user, standalone Pherf.** Start by reading [01](01-first-pherf-run.md),
[02](02-scenario-authoring-and-validation.md), [03](03-open-loop-profiles-and-mixes.md), and
[06](06-results-verdict-and-analysis.md) in sequence. Proceed to [04](04-co-generation.md) when
read workloads must deterministically match rows produced by write workloads, or to
[05](05-types-and-multi-tenant.md) when testing schemas with documents, arrays, binary keys, or
multi-tenant views. To evaluate the test harness as well, follow the harness guides
[01](../../../dev/harness/docs/howto/01-measurement-protocol.md),
[02](../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md),
[04](../../../dev/harness/docs/howto/04-pherf-through-the-harness.md), and
[05](../../../dev/harness/docs/howto/05-latency-slo-and-soak.md).
When a single client is insufficient or the workload must be driven concurrently across multiple
clients, consult the Distributed Pherf HOWTOs [D01][d01] (which covers the controller, the agents,
and the REST API without requiring a cluster) and [D02][d02] (which walks through an initial fleet
run).

**(b) Phoenix developer validating a change.** This workflow begins with the test harness; consult
the reading paths in [the harness HOWTO index](../../../dev/harness/docs/howto/README.md).

**(c) Committer reviewing a performance claim.** This workflow also begins in the harness index.
When evaluating a claim produced by a fleet of load generators, also review [D04][d04], which
explains how to distinguish a client bound from a cluster bound and why fleet-wide percentiles are
computed from merged histograms.

**(d) Harness or Pherf contributor.** Begin with harness guide
[13](../../../dev/harness/docs/howto/13-extending-the-harness.md), followed by
[07](07-extending-pherf.md), harness guide
[03](../../../dev/harness/docs/howto/03-result-bundles-and-automation.md) (covering result bundle
structure and exit codes), and [02](02-scenario-authoring-and-validation.md) (covering scenario
validator rules). Next, review the architectural reference contracts:
[driver-contract.md](../../../dev/harness/docs/driver-contract.md),
[PLUGINS.md](../../../dev/harness/docs/PLUGINS.md),
[backends.md](../../../dev/harness/docs/backends.md), [LATENCY.md](../../LATENCY.md),
[VERDICT.md](../../VERDICT.md), and [ANALYSIS.md](../../ANALYSIS.md).
For Distributed Pherf contributions, also review [D01][d01] (which details the runtime processes,
the environment contract, and the REST API) and [D03][d03] (which covers run cancellation, agent
fault handling, and controller restart behavior).

## Performance engineering tasks

The Pherf guides address the client-side performance engineering tasks listed below. The harness
guides cover operations that require an orchestrated cluster lifecycle, including cluster
provisioning, fault injection, profiling, scale-out testing, version matrix comparisons, and
automated result bundle processing.

| Task | Pherf HOWTO |
|---|---|
| Baselining: warmup, replicates, the closed loop as a single-client service time | 01 |
| Scenario authoring and offline validation: the validator, the pre-run gate, unknown tags | 02 |
| Workload characterization and operation mixes: profiles, mix weights, `-mt` weights | 03, 05 |
| Closed loop and open loop, coordinated omission: service time and response time | 01, 03 |
| Rate steps and ramps, launch overrides | 03 |
| Read-what-you-wrote correctness: point lookups, range scans, deletes, seeds and offsets | 04 |
| Hotspot inputs: leading-key clustering and offsets | 04 |
| Schema and type coverage: FLOAT and DOUBLE arrays, VARBINARY_ENCODED, JSON, BSON | 05 |
| Multi-tenant workloads, tenant fairness and noisy neighbours | 05 |
| Latency SLO evidence: percentiles from HDR histograms, interval logs, tail checks | 06 |
| Noise control and result validity: the verdict, the noisy-host rule | 06 |
| Regression detection and A/B comparison with replicates: medians, bootstrap intervals | 06 |
| Extending Pherf: connection provider, report sections, metrics sinks, generator classes | 07 |
| Reproducibility: seeds, labels, pinned recorder, archived result directories | 01, 04, 06 |
| Multi-client load and load-generator scale-out | [D02][d02], [D04][d04] |
| Fleet latency: merged histograms, per-agent skew | [D04][d04] |
| Fleet A/B with replicates and `-analyze` | [H15][h15] |

## Conventions

**Work directory.** Pherf writes the `RESULTS` and `RESULTS_HDR` directories (along with
`VALIDATION_*.json` and `ANALYSIS_*.json` inside `RESULTS`) relative to the current working
directory because `pherf.default.results.dir` defaults to the relative path `RESULTS`.
Consequently, every guide changes to a dedicated working directory outside the source tree before
invoking Pherf. Never execute Pherf directly from the root of the repository.

**Setup.** Execute the initialization commands at the beginning of each guide from the root of the
repository. These commands configure `PX_OUT` (which defaults to `$HOME/phoenix-harness`, the same
output root used by the test harness) and `HOWTO`, which designates the working root for the active
guide:

```sh
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/pNN"; mkdir -p "$HOWTO/work"
. "$PX_OUT/howto/pherf-env.sh"
```

The standalone Pherf guides do not require the test harness on the `PATH`. The `pherf-env.sh`
script defines the `pherf` shell function used to launch Pherf on the local host.
[HOWTO 01](01-first-pherf-run.md) generates this file in step 3; create it once and source it in
every subsequent guide that executes Pherf on the host.

**Launcher forms.** Pherf can be launched in three ways:

- The `pherf` shell function defined in `pherf-env.sh` invokes `java -cp` with the Pherf JAR, the
  Phoenix embedded client JAR, the bson JAR, the OpenTelemetry API JARs, `jcl-over-slf4j`, the JAXB
  JARs, and a log4j backend compiled from this source tree. The HOWTO guides use this launcher
  form, which runs cleanly on JDK 17.
- The `bin/pherf-standalone.py` script packaged in a Phoenix assembly omits the JAXB JAR from its
  classpath; on JDK 11 and later, executing a run with a scenario file fails with
  `NoClassDefFoundError: javax/xml/bind/JAXBException` (observed on JDK 17 when running from the
  source tree). When invoked from the source tree, it also fails to locate the bson JAR because it
  searches only within `lib/`. Accordingly, the HOWTO guides do not use this script.
- The test harness executes Pherf inside the client container of a harness cluster, either through
  the `pherf` driver
  ([harness HOWTO 04](../../../dev/harness/docs/howto/04-pherf-through-the-harness.md)) or via
  `px-cluster exec` into client 0.

**Files.** Each guide generates all required configuration and source files (including scenarios,
schemas, `pherf.properties`, and Java sources) under `$HOWTO` using heredocs. Always pass scenario
and schema files to Pherf as absolute paths (such as `"$HOWTO/x.xml"`). Any file argument that does
not resolve to a regular file on disk is treated as a regular expression and matched exclusively
against the `scenario/` and `datamodel/` directories on the Pherf classpath.

**Output excerpts.** Commands are accompanied by representative excerpts of their output where
`$HOWTO` denotes the resolved absolute path. Timestamps and durations shown in excerpts are
illustrative, and any excerpt that could not be captured against a live cluster is explicitly noted.
Commands and their corresponding outputs are presented in separate `sh` and `text` blocks so that
each command block can be copied and pasted directly, and harness log line prefixes are elided as
`... INFO  px-x:`.

**Paths and lines.** All guides reference paths using `$PX_OUT`, `$HOWTO`, and `$HOME`, never a
hard-coded user home path. Every line, including table rows, is formatted to at most 100 columns.

**Clean up.** Any guide that modifies cluster state drops its tables upon completion by passing
`-drop` with a pattern scoped exclusively to the tables created by that guide. The `-drop` option
matches uppercase table names within the `PHERF` schema and substitutes `.*` for every occurrence
of `ALL` in the pattern, so table names must never contain the substring `ALL`.

**Harness lines.** Any guide that operates a harness cluster invokes only the `px-*` commands in
`dev/harness/bin` and never calls internal scripts under `dev/harness/scripts`. Every px option
provides a long form, while only `-h`, `-v`, `-n`, and `-e` have short forms. Configuration
overrides are supplied via `-e NAME=VALUE` on the command line rather than as environment prefixes.
The run class and cluster-level `-e` values are declared once at `px-cluster up`, and subsequent
commands read them from the persisted cluster record. Command outputs are obtained from the handle
that px prints, such as the bundle path returned by `px-run`. Each guide concludes with
`px-cluster down`, which is idempotent. Because any failing px command terminates its standard error
output with a single `(exit N, name)` summary line, no guide echoes `$?` after a px command. The
sole exception is `px-cluster exec`, which returns the exit code of the command it executes without
printing a line of its own; consequently, the `pherf` wrapper in P03 option B retains the exit echo
for Pherf. Because Pherf itself prints no such status line, a guide echoes the Pherf exit code
(`echo "exit=$?"` or `echo "exit $?"`) whenever that exit code is the focus of the step.

## Memory of a long run

Pherf retains every operation record in memory until the run completes because both XML report
generation and replicate analysis process the entire dataset. CSV export similarly constructs all
formatted rows in memory before writing the first row to disk. Consequently, memory consumption
scales linearly with the operation count at about 1.5 KB per operation (as documented in the
comment on `ResultUtil.write`). The 3 GiB heap configured by the `pherf` function accommodates
about 2 million operations; partition longer workloads into a sequence of separate runs.

## Distributed Pherf

Distributed Pherf coordinates a single Pherf execution plan across a fleet of agents managed by a
central controller and merges their individual outputs into a unified fleet report. Its guides are
collected in [the Distributed Pherf HOWTO index][d-index]:

- [D01][d01] explains how to deploy a standalone controller and agent processes and interact with
  the REST API.
- [D02][d02] demonstrates how to execute a plan across a fleet, covering work splitting, slot
  tokens, start synchronization, and the fleet report.
- [D03][d03] details how to cancel active runs, recover from agent faults, and restart the
  controller.
- [D04][d04] describes how to scale out the load generator fleet and accurately interpret
  fleet-wide latency metrics.
- [H14][h14] shows how to orchestrate a Distributed Pherf fleet through the test harness using
  `px-pherf` and the `pherf-distributed` driver.
- [H15][h15] covers fleet comparison and stress testing, including replicate runs, A/B analysis
  with `-analyze`, and fault injection under fleet load.

## Out of scope

- Kubernetes backends, service mesh integration, and environment promotion belong to the K track.
- Network partitions, artificial latency injection, and disk-full faults are excluded because the
  test harness currently implements only process kill, signal, and restart faults (see
  [harness HOWTO 09](../../../dev/harness/docs/howto/09-fault-injection-and-recovery.md)).
- Running a direct JDBC client on the host of a harness cluster is not supported by the harness
  design; when targeting a harness cluster, always run Pherf through the harness.
- The Phoenix Query Server and the thin JDBC driver are not covered; although Pherf provides the
  `-t` and `-s` options, none of the HOWTO guides exercise them.
- Vector data types remain out of scope by owner decision in round P2.
- MapReduce bulk load (`CsvBulkLoadTool`) is not covered because the MapReduce job client and the
  container image configuration remain unverified ([harness HOWTO 08, "Not covered"][h08-mr]).

[p01]: 01-first-pherf-run.md
[p02]: 02-scenario-authoring-and-validation.md
[p03]: 03-open-loop-profiles-and-mixes.md
[p04]: 04-co-generation.md
[p05]: 05-types-and-multi-tenant.md
[p06]: 06-results-verdict-and-analysis.md
[p07]: 07-extending-pherf.md
[d-index]: ../../../phoenix-pherf-distributed/docs/howto/README.md
[d01]: ../../../phoenix-pherf-distributed/docs/howto/01-standalone-fleet-and-rest-api.md
[d02]: ../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[d03]: ../../../phoenix-pherf-distributed/docs/howto/03-faults-cancel-and-restart.md
[d04]: ../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[h08-mr]:
  ../../../dev/harness/docs/howto/08-ingest-plans-and-index-cost.md#not-covered-mapreduce-bulk-load
[h14]: ../../../dev/harness/docs/howto/14-fleet-through-the-harness.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
