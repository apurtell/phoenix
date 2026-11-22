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


# Pherf offline analysis

The `-analyze` command reads completed Pherf runs and evaluates them offline without establishing a
database connection. It computes the median and a bootstrap confidence interval for each latency and
throughput metric across replicate runs, verifies that identical runs produce equal operation and
row counts, and compares a candidate set of runs against a baseline set. This document specifies the
contract for its inputs, evaluation rules, exit codes, and output report. The implementation resides
in `org.apache.phoenix.pherf.analysis`.

## Invocation

```
bin/pherf-standalone.py -analyze SET [-label LABEL]
bin/pherf-standalone.py -analyze BASELINE CANDIDATE [-label LABEL]
```

- Supplying one directory analyzes a single set by evaluating its replicates.
- Supplying two directories specifies a baseline set followed by a candidate set. A baseline
  regression check and an A/B comparison perform the same operation; for an A/B comparison, pass A
  first.
- The `-analyze` command honors only `-label`, which names the output report, and ignores all run
  execution options. Passing any number of directories other than 1 or 2 causes the command to exit
  with status 1.

## Inputs

A set is represented by a directory whose immediate subdirectories each correspond to one replicate,
processed in lexicographical order by name. Because harness run identifiers sort chronologically, a
set of run bundles is evaluated in execution order. Regular files located directly inside a set
directory are ignored. Encountering any subdirectory that is not a valid replicate terminates the
analysis with exit status 1 and an error message identifying that subdirectory.

Each replicate uses one of two directory layouts.

**Run bundle.** A directory containing `manifest.json` as produced by the harness
(`dev/harness/docs/bundle-format.md`). The analysis inspects the following paths:

| Path | What the analysis uses |
|---|---|
| `manifest.json` | `bundle_format_version` (must be 1), `run.id`, `run.class`, `run.result` and `quiet_host.tags` |
| `drivers/pherf/results_HDR/LATENCY_run.json` | The latency report ([LATENCY.md](LATENCY.md)). |
| `drivers/pherf/results/RESULT_*_run.xml` | Provides the legacy XML result files, when present. |

**Bare results directory.** A Pherf `<pherf.default.results.dir>_HDR` directory without a manifest.
This directory must contain exactly one `LATENCY_<label>.json` file. The analysis also reads any
matching `RESULT_*_<label>.xml` files placed in the same directory, which the operator copies from
the legacy results directory whenever exact operation counts or row counts are required. Because a
bare directory declares no run class, it is assigned the `undeclared` class; its timing evidence is
never considered authoritative, it can be compared only against other bare directories, and no
command-line option is provided to declare a class for it.

The analysis ignores `.hlog` files, interval logs, verdict files, CSV reports, and `metrics/*.tsv`
files within a bundle.

The legacy XML result files supply two pieces of information not present in the latency report: each
scenario result preserves the `<coGeneration>` element from its scenario definition to identify a
co-generated scenario, and each measured query execution records an individual run time entry
containing its `resultRowCount`. Runs executed with `-mt` do not produce XML result files.

### Refusals

Any of the following conditions aborts the analysis with exit status 1 without writing a report,
emitting an error message that identifies the offending directory:

- A latency report whose `format`, `version`, or `recorder` parameters do not match
  `pherf-latency`, `1`, and (`lowestUs` 1, `highestUs` 600000000, `significantDigits` 3), because
  measurements recorded under different histogram configurations are not comparable.
- A replicate that lacks a latency report, or a bare directory that contains more than one latency
  report.
- A run bundle with an unrecognized `bundle_format_version`, a `run.class` other than `validation`
  or `measurement`, or a manifest missing any of the required fields listed above.
- A run bundle whose `run.result` is `ERROR`, which indicates an infrastructure fault that renders
  the run inconclusive.
- A latency report or an XML result file that cannot be read or parsed.
- A set directory that does not exist or contains no replicate subdirectories.

## Metrics

Each metric is identified by the tuple (operation, phase, metric), where the operation and phase
names match those in the latency report.

| Metric | Keys | Value of one replicate |
|---|---|---|
| `p50Us`, `p90Us`, `p99Us`, `p999Us` | each `steady` key of each kind whose `count` is at least 2, 10, 100 and 1000 | the percentile of the latency report |
| `opsPerSec` | one key: operation `run`, phase `steady` | the `steady` counts of the kinds `query`, `scan`, `delete` and `operation`, x 1000 / (`endMs` - `startMs`) |
| `count` | Every key across both phases. | The `count` recorded in the latency report. |
| `rows` | `<kind>:<scenario>:<query id>`, phase `steady` | the sum of `resultRowCount` over the run times of the query in the XML results |

- Keys in the warmup phase provide counts only. Neither `maxUs` (which reflects a single sample) nor
  `meanUs` is evaluated as an analysis metric.
- A percentile q is included for a replicate only when the key's `count` is at least 1 / (1 - q). At
  smaller sample counts, the HdrHistogram percentile degenerates to the slowest observation,
  matching `maxUs`. For example, the default query set of 10 executions yields `p50Us` and `p90Us`
  but omits `p99Us` and `p999Us`. This minimum sample threshold is a mathematical property of the
  percentile rather than a configurable setting.
- Throughput calculation excludes `response` (which duplicates the operation count of `query`) and
  `load` (which counts commit batches). Any replicate where `endMs` equals `startMs` produces no
  throughput metric.
- The synthetic operation name `run` cannot collide with a per-operation key because operation keys
  always contain a `:` delimiter.
- Latency and throughput metrics (the first two rows of the table) undergo median calculation,
  confidence interval estimation, and cross-set comparison, whereas `count` and `rows` participate
  exclusively in the count parity check.
- Within a set, each metric is aggregated across the replicates for which it is present, and the
  report records that sample size as `n`.

### Whole-run values

Medians and confidence intervals are computed directly from the percentile values in each
replicate's JSON latency report rather than by merging `.hlog` histogram logs. Because Pherf derives
its JSON report percentiles from the same whole-run histogram written to the run log, the JSON
report and the log yield identical values for an individual replicate. Treating each replicate as
the unit of independent experimental variation requires one summary value per replicate for
bootstrap resampling; pooling all histograms into a single merged distribution would weight each
replicate by its operation count and obscure run-to-run variance. Furthermore, because the analysis
never parses interval timestamp bounds from `.hlog` files, it is unaffected by the HdrHistogram log
reader behavior that can report an interval bound 1 ms low.

## Statistics

- **Median.** Replicate values are sorted in ascending order; when the replicate count is even, the
  median is the arithmetic mean of the two middle values.
- **Interval.** Confidence intervals are computed via a percentile bootstrap of the median using
  10,000 resamples drawn with replacement at a 95% confidence level, taking nearest ranks 250 and
  9,750 of the sorted resample medians.
- **Seed.** Each metric uses an independent `java.util.Random` stream seeded with
  `mix64(8070 ^ fnv1a64(operation|phase|metric))` when evaluating a single set, and
  `mix64(8070 ^ fnv1a64(comparison|operation|phase|metric))` when evaluating a cross-set comparison
  (using the hash functions defined in `org.apache.phoenix.pherf.rules.IndexHash`). Because the JDK
  specification fixes the `java.util.Random` algorithm, identical inputs and seeds produce identical
  intervals across all JVM implementations. Moreover, because a metric's seed and resamples depend
  solely on its key and replicate values, a set yields the same confidence intervals whether it is
  analyzed standalone, as a baseline, or as a candidate. The base seed 8070 is fixed, recorded in
  the report, and not overridable via any command-line option.
- A set containing only a single replicate yields a degenerate point interval whose lower and upper
  bounds both equal the replicate value.

## Comparison

For every latency or throughput key that has observations in both the baseline and candidate sets:

- `delta` is computed as median(candidate) - median(baseline).
- The confidence interval of `delta` is estimated via a two-sample bootstrap of
  median(candidate*) - median(baseline*), in which each resample draws independently from the two
  sets. Metrics are paired across sets by key ("paired" refers to key pairing; individual replicates
  are never paired across sets).
- `deltaPct`, `ciLowPct`, and `ciHighPct` scale `delta` and its confidence interval bounds by
  100 / median(baseline). When the baseline median is 0, the comparison outcome is set to `skip` and
  these percentage fields are omitted.

Comparison outcomes are classified according to the following rules:

| Outcome | Rule |
|---|---|
| `regression` | `deltaPct` exceeds 10% in the worse direction; the interval excludes 0 there. |
| `improvement` | `deltaPct` exceeds 10% in the better direction; the interval excludes 0 there. |
| `unchanged` | Any comparison that does not qualify as a regression, an improvement, or a skip. |
| `skip` | The baseline median is 0. |

Higher values represent worse performance for latency metrics, whereas lower values represent worse
performance for `opsPerSec`. A shift of exactly 10% is classified as `unchanged`. The 10% threshold
is fixed to align with the tolerance implied by the harness throughput attainment minimum of 90%.
When each set contains 3 or fewer replicates, a bootstrap interval that excludes 0 indicates that
the observed replicate ranges of the two sets do not overlap. Any key present in only one of the two
sets is recorded under `unpaired`.

At small replicate counts, the actual coverage of a percentile bootstrap interval is below its
nominal 95% level. For a single set of 3 observations, the bootstrap interval always spans from the
sample minimum to the sample maximum, which encloses the true population median with 75%
probability. Two independent samples drawn from the same underlying distribution fail to overlap in
a given direction by chance with probability 1/6 when each set has 2 values, and 1/20 when each set
has 3 values. Because the analysis applies no multiple-comparison correction—even though each
operation key contributes up to five metrics and a single regression on any metric causes the run's
regression verdict row to report `FAIL`—a detected regression produces a `FAIL` verdict only when
both sets contain at least 3 observations for that metric (see Verdicts below).

## Count parity

Count parity is enforced strictly within each set, since the replicates belonging to a set represent
repeated executions of an identical configuration. Parity is never enforced across sets because an
A/B comparison with modified configuration parameters may legitimately alter operation or row
counts.

A count key is classified as **exact** whenever an XML result file in any replicate of the set marks
its scenario `S` as co-generated. For a co-generated scenario `S`, the exact operations comprise
`<kind>:S:<query id>` and `response:S:<query id>` for every query present in the XML result, along
with `load:S:<table>`. Exact parity applies across both warmup and steady phases and covers both the
`count` and `rows` metrics. All other count keys are classified as advisory, including those from
rule-based scenarios, `-mt` load profile runs (which emit no XML results), and bare result
directories without copied XML result files.

A closed-loop query in a co-generated scenario produces deterministic counts only when every reader
thread completes the full `numberOfExecutions` configured on its query set. If any thread records
fewer run times, that thread terminated due to the duration limit (`executionDurationInMs`, which
defaults to 10000 ms), making its execution count time-dependent. Whenever any replicate in a set
exhibits a duration-limited thread for a query, the operations for that query are demoted to
advisory within that set and the corresponding count report entry populates `reason`. Because
open-loop query sets are governed by a schedule rather than an execution count, this check does not
apply to them.

If a key is present in some replicates of a set but absent from another, that absence is treated as
a count discrepancy. Consequently, if one replicate in a co-generated set lacks its XML result file,
its missing row counts register as a parity mismatch on every exact `rows` key.

## Verdicts

The analysis report includes verdict rows structured using the standard harness verdict stream
fields (`dev/harness/docs/bundle-format.md`): `check`, `severity`, `class`, `source` (always set to
`pherf`), and `detail`. Severities follow the harness ordering
`PASS` < `SKIP` < `ADVISORY` < `FAIL` < `ERROR`.

| check | class | Severity |
|---|---|---|
| `pherf.analysis.count_parity` | `count` | `FAIL` when an exact key differs in a set; `ADVISORY` when only advisory keys differ; `PASS` when all are equal; `SKIP` when no set has two or more replicates, or there is no count key |
| `pherf.analysis.regression` | `duration` | Two sets only. `FAIL` when a comparison with at least 3 values in each set is a regression; `ADVISORY` when each regression has fewer than 3 values in a set; `PASS` when there is no regression; `SKIP` when no key has a nonzero baseline median in both sets |

The detail field identifies either the first mismatched count key along with its per-replicate
values, or the most severe regression along with its percentage shift and confidence interval. When
a regression verdict is reported as `ADVISORY`, the detail also states the reason for demotion, such
as `fewer than 3 replicates in a set`. A count parity verdict is never demoted.

**Run class and authority.** Every replicate included in an analysis—across both sets when two sets
are compared—must declare the same run class (`validation`, `measurement`, or `undeclared`). If any
replicate differs in run class, the analysis aborts with exit status 66 without writing a report and
emits an error message listing each set, each replicate, and its class. This consistency check
executes after all replicates have been loaded and before any statistical computation begins.

Timing evidence is authoritative only when the common run class is `measurement` and every replicate
has an empty `quiet_host.tags` list. If either condition is not met, any detected regression is
demoted from `FAIL` to `ADVISORY`, and the verdict detail records the reason—identifying either the
non-measurement run class or the contaminated replicates and their host tags. This policy matches
the `px-report` authority rule for bundle timing evidence.

The top-level `result` of the report is the maximum severity across its verdict rows.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The analysis report was written and contains no `FAIL` verdict row. |
| 1 | The command invocation was invalid or an input could not be processed; no report is written. |
| 66 | The analysis was refused because replicate run classes differ; no report is written. |
| 67 | The analysis report was written and contains at least one `FAIL` verdict row. |

Offline analysis never exits with status 65, which is reserved for scenario validation.

## Report

The analysis writes `ANALYSIS<suffix>.json` to `pherf.default.results.dir`, where `<suffix>` matches
the filename suffix used by legacy result files (`_` followed by the `-label` argument, or `_`
followed by a generated timestamp). At startup, the analysis deletes any existing report file for
the same label so that a subsequent refusal never leaves a stale report behind. The new report is
written to a temporary file and atomically moved into place. Because the report payload omits
generation timestamps, identical inputs and labels produce byte-for-byte identical output.

| Field | Meaning |
|---|---|
| `format`, `version` | `pherf-analysis`, `1`. The version increments only for a change that an existing reader cannot read; a reader ignores an unknown field. |
| `label` | The file suffix with its leading `_` removed. |
| `bootstrap` | An object recording `resamples` (10000), `confidencePct` (95), and `seed` (8070). |
| `regressionThresholdPct` | Fixed at 10. |
| `timingAuthoritative` | Set to true when the timing evidence is authoritative. |
| `sets` | A list containing one entry per analyzed set, as detailed below. |
| `comparisons` | Present for two sets only; holds one entry per key in both sets, sorted by key. |
| `unpaired` | Two sets only: `operation`, `phase`, `metric` and `set` (`baseline` or `candidate`) of each key in one set only. |
| `verdicts` | The evaluated verdict rows, beginning with count parity. |
| `result` | The maximum severity across all verdict rows. |

Each entry in `sets` contains the following fields:

| Field | Meaning |
|---|---|
| `role` | Set to `set` for one set, or to `baseline` or `candidate` when comparing two sets. |
| `dir` | The set directory path as supplied on the command line. |
| `runClass` | The common run class shared by the replicates of the set. |
| `replicates` | Each replicate in order: `name`, `layout` (`bundle` or `bare`), `runId` and `result` (bundles only), `runClass`, `contamination` (the tags), `latencyReport` and `xmlResults` (paths in the replicate), and `coGenerated` (the co-generated scenarios). |
| `metrics` | Each latency and throughput key: `operation`, `phase`, `metric`, `values` (one for each replicate, in order; null when the replicate has none), `n`, `median`, `ciLow`, `ciHigh`. |
| `counts` | Each count key: `operation`, `phase`, `metric` (`count` or `rows`), `values` (as above), `exact`, `equal`, and `reason` when a co-generated key is advisory because a thread stopped on time. |

Each entry in `comparisons` records `operation`, `phase`, `metric`, `baselineMedian`,
`candidateMedian`, `delta`, `deltaPct`, `ciLowPct`, `ciHighPct`, and `outcome`.

In addition to writing the JSON file, the analysis prints a human-readable summary to stdout listing
each replicate with its run class and contamination tags, every detected regression and improvement,
the verdict rows formatted in the standard verdict stream representation, the overall result, and
the output report path. It does not write a separate verdict TSV file because offline analysis is
not executed as a harness step.

## Limits

- A co-generated open-loop query set whose schedule drops overdue arrivals (under lag policy `DROP`)
  or halts early does not produce deterministic operation counts, causing its count parity check to
  report `FAIL`. Schedule `late` counters and peak lag measurements represent timing observations
  and are excluded from count parity checks. Closed-loop query sets that terminate due to their
  duration limit are treated as advisory (see Count parity).
- All count keys produced by a `-mt` load profile run are advisory because `-mt` executions do not
  write XML result files.
- A bare results directory requires copied XML result files in order to enforce exact count parity
  and evaluate query row counts.
- Cross-set comparisons pair metrics by key name, requiring the baseline and candidate sets to use
  matching scenario names and query identifiers.
- Because legacy XML result files from long runs can be large, the analysis parses them sequentially
  one replicate at a time.
- When comparing sets that contain only a single replicate each, every confidence interval collapses
  to a point and the comparison rule reduces to the threshold check; any resulting regression, like
  any regression derived from fewer than 3 observations in either set, is reported as `ADVISORY`.
