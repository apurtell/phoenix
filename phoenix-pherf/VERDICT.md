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

# Pherf health verdict

At the conclusion of an execution run, Pherf evaluates the run against five health checks and
records the results in a verdict file. This document defines the formal contract for that artifact,
whose rows are copied into the result bundle by the harness Pherf driver and consumed by
`px-report`. The implementation resides in `org.apache.phoenix.pherf.verdict`.

The verdict is strictly an output artifact and is always enabled without requiring any configuration
setting. It never alters the exit code of a run: any failure to delete a prior verdict file or to
write the new verdict file is logged without failing the execution.

## Location and lifecycle

The verdict is written to `<pherf.default.results.dir>_HDR/VERDICT<suffix>.tsv` alongside the
latency report `LATENCY<suffix>.json` (see [LATENCY.md](LATENCY.md)). The `<suffix>` token consists
of `_` followed by the `-label` argument value, or `_` followed by a generated timestamp.

- Every run that executes a scenario file—including a run that terminates with a failure—writes the
  verdict file together with the latency report, whereas a run that executes no scenario produces
  neither file.
- When the latency recorders initialize at the start of a run, Pherf deletes any existing verdict
  file with the same label so that a stale verdict is never paired with a newly generated report.
- At the beginning of the measured workload phase—immediately after a `-l` data load completes, or
  prior to starting a `-mt` workload—Pherf captures the initial baseline sample of server read
  counts.
- After writing the latency report, Pherf captures the final server read sample, evaluates the five
  health checks, writes the verdict output to a temporary file, and atomically renames it into
  place so that consumers never observe a partially written file.
- If the latency report cannot be written, Pherf skips writing the verdict file as well.
- No verdict artifact is added to the legacy results directory.

## Format

The file is formatted as a harness verdict stream conforming to the exact schema of `verdicts.tsv`
defined in `dev/harness/docs/bundle-format.md`:

- The file is encoded in UTF-8 with LF line terminators and omits a header line.
- The output contains exactly five rows, one for each check, emitted in the order shown in the
  table below.
- Each row consists of five TAB-separated fields: `check severity class source detail`.
- The `severity` field is one of `PASS`, `SKIP`, `ADVISORY`, `FAIL`, or `ERROR`, listed in
  increasing order of severity rank.
- The `class` field identifies the evidence class and is set to `count`, `share`, or `duration`.
- The `source` field is always set to `pherf`.
- The `detail` field provides human-readable diagnostic text for an operator, including the reason
  whenever a check produces `SKIP`; any tab, CR, or LF character within a field is normalized to a
  space.

Only `FAIL` and `ERROR` represent check failures. An `ERROR` verdict indicates an infrastructure
fault rather than a workload deficiency, specifically that Pherf could not read a configured server
read-count source.

## Checks

| check | class | FAIL when | SKIP when |
|---|---|---|---|
| `pherf.health.attainment` | duration | Minimum is below `attainment.minPct`. | No schedule ran. |
| `pherf.health.saturation` | duration | Highest peak lag exceeds `lag.maxMs`. | No schedule ran. |
| `pherf.health.client_pool` | count | A task waited for a pool thread. | No task used the pool. |
| `pherf.health.tail_latency` | duration | A p99 or p99.9 exceeds its bound. | No key is measured. |
| `pherf.health.read_skew` | share | Server read skew exceeds `readSkew.max`. | See rules below. |

A value equal to its configured threshold passes the check.

**Attainment.** For each open-loop query set that scheduled at least one operation and started
execution, attainment is computed as `completed / scheduled x min(1, durationMs / elapsed)`, where
`durationMs` is the declared schedule window and elapsed is `lastTakeMs - startMs` (floored at a
minimum of 1 ms). These inputs are read from the `schedules` entries of the latency report. Because
`lastTakeMs` records the timestamp when a worker last pulled an operation from the dispatch queue,
the execution service time of the final operations is excluded from the elapsed window.
Consequently, a schedule that stops on duration after dropping operations, or after accumulating a
queued backlog that workers drain past the end of the declared window, yields a reduced attainment
score. A schedule that halts early is evaluated across the portion of the schedule that actually
ran. The verdict detail identifies the lowest-scoring query set. Closed-loop runs and `-mt` load
profile runs produce no schedule entries, so the check reports SKIP.

**Saturation.** Peak scheduling lag is taken from `maxDriftUs` on each schedule entry, which
measures the delay between an operation's intended dispatch timestamp and the time a worker thread
pulled it from the queue. This lag accumulates only while arrivals outpace worker completion. The
verdict detail reports the worst query set's peak queue depth relative to its queue bound (equal to
the configured concurrency), its peak in-flight operation count (`maxInFlight`) relative to the
concurrency, and its late and dropped operation counts. A peak queue depth that reaches the queue
bound indicates that worker threads could not keep pace with the arrival rate. When peak lag exceeds
the threshold while the queue remains below its bound, two distinct causes are possible: if peak
in-flight operations reached the concurrency limit, all workers were occupied and the bottleneck
lies in the cluster; if at least one worker remained free, the dispatcher thread itself fell behind
and the bottleneck lies on the client. A schedule whose queue reaches capacity while keeping peak
lag within the configured threshold passes the check.

**Client pool.** Pherf executes its general workloads and closed-loop query runners on a single
fixed-size thread pool configured with `pherf.default.threadpool` threads. A task experiences
queueing wait whenever the number of unfinished tasks already submitted to the pool exceeds the
thread count at the moment of submission. When this occurs, the run fails to achieve its declared
concurrency because of client-side thread starvation rather than cluster performance. The verdict
detail reports the peak concurrent task demand, which represents the minimum pool size required to
execute the run without thread contention. Open-loop worker threads run outside this shared pool and
are covered instead by the saturation check, ensuring that the two checks evaluate independent
sources of evidence. This check enforces a strict zero-wait invariant and has no tunable threshold.

**Tail latency.** This check evaluates the cumulative run-total histogram for every `steady` phase
key of kind `query`, `scan`, `delete`, `response`, or `operation` in the latency report, comparing
the p99 and p99.9 percentiles reported in the JSON summary against their respective bounds. Both
`load` keys and `warmup` phase keys are excluded from evaluation. The verdict detail identifies the
key with the worst violation along with the total number of keys that exceeded a bound.

**Read skew.** Server read skew is defined as the read count of the busiest server divided by the
mean read count per server across all configured servers, including any server that recorded zero
reads. The check reports SKIP when no read-count source is configured, when the run terminated
before the measured workload began, when the sampling window is shorter than 60 s, when fewer than 2
servers are configured, when zero total reads occurred across the window, or when any server's read
counter decreased between samples (indicating a region movement or a server restart). The check
reports ERROR if any configured server source cannot be read during either the initial or the final
sampling pass.

## Server reads

Because Pherf does not maintain internal server-side read counters, it queries the RegionServer JMX
servlet directly. For each base URL configured in `pherf.verdict.serverJmx`, Pherf requests
`<url>/jmx?qry=Hadoop:service=HBase,name=RegionServer,sub=Tables` at the start of the measured
workload and again at the end of the run, enforcing a 10 s connect and read timeout. The effective
read window is measured between the completion timestamps of the two sampling passes. Each server's
read count is computed as the sum of the
`Namespace_<namespace>_table_<table>_metric_readRequestCount` attributes across all user tables,
excluding tables in the `hbase` and `SYSTEM` namespaces as well as `SYSTEM.*` tables in the default
namespace. A server that exposes no table metrics bean is assigned a read count of 0, and the
configured base URL serves as the server identifier in the verdict detail.

Operational limitations:

- JMX counter values are not instantaneous. Each RegionServer refreshes its table-level aggregates
  once every `hbase.regionserver.metrics.period` (5 s by default), and the JMX servlet caches the
  response for one metrics2 period (10 s in the standard HBase metrics2 configuration). As a
  result, any sample may lag behind live execution by roughly 15 s, with independent phase offsets
  across servers. To bound this sampling jitter, the check enforces a fixed minimum read window of
  60 s and reports SKIP for shorter runs; smoke-length runs are therefore not graded, and longer
  windows retain an uncertainty of up to roughly 15 s of read traffic per server.
- Although the sampling window begins after a `-l` data load completes, it includes any warmup
  executions performed by the query sets.
- Each per-table counter aggregates reads across the regions currently online on that server, so an
  in-flight region move alters the counter; concurrent reads issued by external clients against user
  tables are also included, and SPNEGO authentication is not supported.

## Thresholds

All verdict settings are configured as keys in `pherf.properties`, where an omitted or empty
property resolves to its default value. Pherf validates these properties at startup; an invalid
value aborts the run before any database connection is opened, exiting with code 1 and an error
message that identifies the offending property key.

| key | default | rule |
|---|---|---|
| `pherf.verdict.attainment.minPct` | `90` | Must be a decimal number in the interval (0, 100]. |
| `pherf.verdict.lag.maxMs` | `1000` | Must be a decimal number greater than or equal to 0. |
| `pherf.verdict.tail.p99MaxMs` | `1000` | Must be a decimal number strictly greater than 0. |
| `pherf.verdict.tail.p999MaxMs` | `5000` | Must be a decimal number strictly greater than 0. |
| `pherf.verdict.readSkew.max` | `1.5` | Must be a decimal number greater than or equal to 1. |
| `pherf.verdict.noisyHost` | `false` | Must be either `true` or `false`. |
| `pherf.verdict.serverJmx` | empty | Unique comma-separated `http`/`https` info server base URLs. |

The default values for `attainment.minPct` and `tail.p99MaxMs` match the harness thresholds
`steady.attainment_pct.min` and `steady.p99_ms.max`. Threshold bounds specified in ms are compared
directly against microsecond measurements in us without rounding. Note that `lag.maxMs` is distinct
from the `maxLagMs` attribute on an open-loop query set: the query set attribute governs per-arrival
late marking or dropping during execution, whereas this property grades the overall health of the
run. Verdict thresholds reside in host configuration rather than scenario files because they grade
execution health on a specific host environment.

## Noisy host

When `pherf.verdict.noisyHost` is set to `true`, any `FAIL` verdict belonging to the `duration`
evidence class (covering attainment, saturation, and tail latency) is downgraded to `ADVISORY`, and
its detail message notes the downgrade reason. Verdicts of class `count` or `share`, as well as any
`ERROR` row, remain unchanged, and no verdict severity is ever elevated. This behavior mirrors the
contaminated-run downgrade policy enforced by `px-report`. The harness enables this property when
host contamination is detected; Pherf itself does not read any environment variable.

## Use by the harness

The harness Pherf driver invokes Pherf with a fixed `-label`, copies the `results_HDR` directory
into the artifact bundle, and appends the rows of the generated verdict file unmodified to
`verdicts.tsv`. In addition, `px-report --verdict` can consume the verdict file directly. Using the
label `run`, the driver reads `VERDICT_run.tsv`, enables the noisy-host flag when host contamination
is detected, and populates `pherf.verdict.serverJmx` from the cluster topology only when invoked
with `--read-skew`. Configure `pherf.verdict.serverJmx` only for tests that specifically evaluate
read distribution across a measured window of at least 60 s, as a single-region table deployed
across 3 RegionServers will fail the read skew check.
