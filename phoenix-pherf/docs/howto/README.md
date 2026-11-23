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

| # | HOWTO | Purpose | Cost |
|---|---|---|---|
| 01 | [How to run your first Pherf workload on a cluster](01-first-pherf-run.md) | Build Pherf, write a scenario, load data, run a closed loop with warmup and replicates, and find the outputs. | User cluster; build about 8 min warm; runs a few minutes; 3 GiB client heap |
| 02 | [How to author a scenario and validate it offline](02-scenario-authoring-and-validation.md) | Find scenario defects with no cluster: the validator, its rule ids, the pre-run gate and unknown tags. | Offline; about 5 min; under 1 MiB |
| 03 | [How to drive an open-loop workload with profiles and operation mixes](03-open-loop-profiles-and-mixes.md) | Offer load at a set rate, step or ramp it, mix operation kinds, and override the curve at launch. | User cluster, or a harness cluster through option B of step 2 (checked against the code, not run on a cluster); about 15 min of runs; under 50 MB |
| 04 | [How to generate verifiable data with co-generation](04-co-generation.md) | Make each value a function of the row index and a seed, so readers find what writers wrote. | User cluster, or a harness cluster through option B of P03 step 2 (checked against the code, not run on a cluster); about 10 min of runs; under 50 MB |
| 05 | [How to cover Phoenix types and multi-tenant workloads](05-types-and-multi-tenant.md) | Exercise arrays, VARBINARY_ENCODED, JSON and BSON, tenant views and the `-mt` load profile. | User cluster; about 20 min |
| 06 | [How to read Pherf results, grade a run and compare replicates](06-results-verdict-and-analysis.md) | Read the latency report and the verdict, and compare replicate sets with `-analyze`. | Part 1 on a cluster, about 10 min; part 2 offline, about 5 min; under 50 MB |
| 07 | [How to extend Pherf with providers, report sections, metrics sinks and generators](07-extending-pherf.md) | Write, package and check the Pherf extension points. | Offline build of one jar; a user cluster to run it |

Each guide details its resource and time requirements. Using a harness cluster adds approximately
5 min for cluster bring-up and requires 8704 MiB in container memory limits (corresponding to the
validation cluster class with 1 RegionServer).

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

**(b) Phoenix developer validating a change** and **(c) committer reviewing a performance
claim.** Both of these workflows begin with the test harness. Consult the reading paths outlined in
[the harness HOWTO index](../../../dev/harness/docs/howto/README.md).

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
| Reproducibility: seeds, labels, pinned recorder configuration, archived result directories | 01, 04, 06 |

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

- The `pherf` shell function defined in `pherf-env.sh` invokes `java -cp` using the Pherf JAR, the
  Phoenix embedded client JAR, the bson JAR, the JAXB JARs, and a log4j backend compiled from this
  source tree. The guides use this invocation method, which runs cleanly on JDK 17.
- The `bin/pherf-standalone.py` script packaged in a Phoenix assembly omits the JAXB JARs from its
  classpath; on JDK 11 and later, executing a scenario file fails with
  `NoClassDefFoundError: javax/xml/bind/JAXBException` (observed on JDK 17 when running from the
  source tree). When invoked from the source tree, it also fails to locate the bson JAR because it
  searches only within `lib/`. Accordingly, these guides do not use this script.
- The test harness executes Pherf inside the client container of a harness-managed cluster, either
  through the `pherf` driver
  ([harness HOWTO 04](../../../dev/harness/docs/howto/04-pherf-through-the-harness.md)) or via
  `px-cluster exec` on client 0.

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

**Paths and lines.** All guides reference paths through `$PX_OUT`, `$HOWTO`, and `$HOME` rather
than hard-coded user home directories. Prose and code lines wrap at a maximum of 100 columns,
whereas table rows may exceed that width.

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

Pherf retains every operation record in memory for the duration of a run because both XML report
generation and replicate analysis operate over the complete dataset. CSV export likewise buffers
all formatted rows in memory before writing the first row to disk. Consequently, memory consumption
scales linearly with the number of operations at approximately 1.5 KB per operation (as noted in
`ResultUtil.write`). The 3 GiB heap configured by the `pherf` function accommodates approximately
2 million operations; partition longer workloads into a sequence of separate runs. Builds prior to
63c9dd0ec2 allocated memory in proportion to the square of the operation count within a single
query set, exhausting a 1 GiB heap above approximately 10,000 operations.

## Coming later

Distributed Pherf (W2) is planned for a future release, and its corresponding guides will be added
once the D track lands.

## Out of scope

- Distributed and multi-client load generation are deferred to W2 (the D track).
- Kubernetes backends, service mesh integration, and environment promotion belong to the K track.
- Network partitions, artificial latency injection, and disk-full faults are excluded because the
  test harness currently implements only process kill, signal, and restart faults (see
  [harness HOWTO 09](../../../dev/harness/docs/howto/09-fault-injection-and-recovery.md)).
- Running a direct JDBC client on the host against a harness cluster is unsupported by design;
  when targeting a harness cluster, always execute Pherf through the harness.
- The Phoenix Query Server and thin JDBC driver are not covered; although Pherf provides the `-t`
  and `-s` options, none of these guides exercise them.
- Vector data types are excluded from scope by owner decision in round P2.
- MapReduce bulk loading (`CsvBulkLoadTool`) is not covered because the MapReduce job client and
  container image configuration remain unverified
  ([harness HOWTO 08, "Not covered"](../../../dev/harness/docs/howto/08-ingest-plans-and-index-cost.md#not-covered-mapreduce-bulk-load)).
