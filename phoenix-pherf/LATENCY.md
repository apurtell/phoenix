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

# Pherf latency outputs

Pherf captures the execution latency of every successful operation using microsecond-resolution HDR
histograms. This document defines the formal contract for those output artifacts, which are consumed
by the harness Pherf driver, the offline analysis tooling, and the distributed controller. The
implementation resides in `org.apache.phoenix.pherf.latency`.

## Location and names

Latency artifacts are written to the `<pherf.default.results.dir>_HDR` directory, which defaults to
`RESULTS_HDR`, while legacy reports remain in `<pherf.default.results.dir>`. The `<suffix>` token
matches the naming suffix used by legacy result files, consisting of `_` followed by the `-label`
argument value, or `_` followed by a generated timestamp.

| File | Content |
|---|---|
| `LATENCY<suffix>.json` | The JSON summary report, which Pherf writes last via an atomic rename. |
| `LATENCY<suffix>.hlog` | Holds one cumulative histogram for each key across the entire run. |
| `LATENCY<suffix>_intervals.hlog` | Contains interval histograms, written only when export is on. |

Every invocation that executes a scenario file generates these output files. Running again with the
same label supersedes prior artifacts: at startup, Pherf deletes all three files to guarantee that
a stale JSON report is never paired with newly generated logs. Invocations that do not execute a
scenario (`-listFiles`, `-compare`, `-drop`, or schema-only runs) produce none of these files.

## Recorder configuration

Every recorder uses a fixed configuration with a lowest trackable value of 1 us, a highest trackable
value of 600,000,000 us (600 s), and a precision of 3 significant digits. These parameters are not
tunable, because merging histograms produces a result equivalent to a single recorder only when
their configurations match. Pherf clamps any measurement outside [0, 600 s] to the nearest bound
and logs a warning upon the first clamped value in a run. Every encoded histogram in a log file also
embeds this configuration.

## Keys

Each histogram key pairs an operation identifier with an execution phase, using `steady` for a
measured operation and `warmup` for a warmup operation. When a query set configures
`warmupExecutions="K"`, each thread executes K warmup runs of every query prior to its measured
executions; only those runs produce `warmup` keys, so a run without warmup omits any `warmup` key.
In an open-loop query set, each worker thread executes K warmup runs of every query before the
schedule begins. Warmup applies exclusively to query sets, whereas data load and load profile
operations are always recorded under the `steady` phase. For an open-loop query set, `query`
captures the service time, whereas `response` (recorded only for open-loop query sets in the
`steady` phase) captures the response time from the intended dispatch time to the final row; this
equals the scheduling lag (the wait from the intended dispatch time until a worker takes the
operation) plus the service time, so `response` minus `query` gives the client queueing delay. The
operation identifier follows the `<kind>:<scenario>:<name>` format:

| Kind | Name | One sample |
|---|---|---|
| `query` | query id | One execution of a query of a query set, from the connection to the last row. On an open loop query set this is the service time. |
| `scan`, `delete` | query id | One execution of a query of a query set with `kind="SCAN"` or `kind="DELETE"`, as for `query`. A DELETE ends at its commit. |
| `response` | query id | Open loop query sets only, phase `steady` only: the response time of one execution, from its intended dispatch time to the last row. It is the lag (the wait from the intended time until a worker took the operation) plus the service time, so `response` minus `query` is the client queueing delay. |
| `load` | table name | One committed batch of the data load. |
| `operation` | operation group id | One upsert, query, SCAN, DELETE or pre-run DDL operation of a load profile. |

Failed operations are omitted from recording and produce no sample. Similarly, idle intervals and
user-defined operations in a load profile are excluded because they do not measure database work. A
query that reaches its configured `timeoutDuration` stops reading rows and is treated as a normal
completion rather than a failure: its recorded sample captures the elapsed time until termination,
near the timeout bound. Consequently, a latency tail at the timeout threshold indicates queries that
timed out. When `-connectionCache` is enabled, a query sample that reuses a cached connection does
not include opening a connection.

## JSON report, version 1

| Field | Type | Meaning |
|---|---|---|
| `format` | string | Identifies the report format and is always set to `pherf-latency`. |
| `version` | integer | Specifies the report schema version, which is `1`. |
| `label` | string | Contains the artifact naming suffix without its leading `_` character. |
| `startMs`, `endMs` | integer | Record the start and end of the recording in ms since the epoch. |
| `recorder` | object | Specifies `lowestUs`, `highestUs`, and `significantDigits`. |
| `hlog` | string | Specifies the file name of the cumulative run histogram log. |
| `intervals` | string | Gives the interval log file name, and is omitted when export is off. |
| `histograms` | array | Holds one entry for each key, ordered by operation and then by phase. |
| `schedules` | array | Holds one entry per open-loop query set in run order; omitted if none. |

Each element in the `histograms` array contains `operation`, `phase`, `count`, `minUs`, `maxUs`,
`meanUs` (formatted as a decimal number), `p50Us`, `p90Us`, `p99Us`, and `p999Us`. All latency
metrics are reported in microseconds at the HDR precision of 3 significant digits. A run that
records no samples produces an empty list.

Consumers must ignore any unrecognized fields. The schema version is incremented only for a change
that an existing reader cannot parse. Accordingly, introducing the `response` keys and the
`schedules` field did not increment the version, because they only add new keys and an optional
field without altering any existing structure.

### Schedule entries

Each element in the `schedules` array captures the final execution state of a single open-loop query
set:

| Field | Meaning |
|---|---|
| `scenario`, `querySet` | Give the scenario name and the query set position in it, from 1. |
| `operations` | Lists the `query` operations of the query set in arrival order. |
| `concurrency` | Sets the worker limit, which also serves as the dispatch queue bound. |
| `phase` | Is always `done` in the report (`warmup` and `steady` appear only in live state). |
| `stopReason` | Reports `duration`, `stopTime`, `predicate`, `cancel`, `interrupt`, or `error`. |
| `startMs`, `endMs` | Record schedule start (after warmup) and stop, in ms since the epoch. |
| `targetRate` | Gives the declared rate at that time in operations per second; 0 once stopped. |
| `queueDepth`, `inFlight`, `driftUs` | Give queued, running, and oldest due lag; all 0 at stop. |
| `scheduled` | Counts operations queued, plus due arrivals held back by the queue bound at halt. |
| `completed` | Counts operations that succeeded, matching each `response` key count of the set. |
| `late` | Counts executed operations whose lag exceeded `maxLagMs` under the `LATE` policy. |
| `dropped` | Counts operations not executed because lag exceeded `maxLagMs` (policy `DROP`). |
| `aborted` | Counts failed operations, queued ones removed by a halt, and due ones held back. |
| `pending` | Reports unfinished operations, computed as `queueDepth + inFlight`. |
| `maxQueueDepth`, `maxInFlight`, `maxDriftUs` | Record the peak values observed across the run. |

The invariant `scheduled = completed + dropped + aborted + pending` holds at all times, and
`pending` is always 0 in the final report, where `targetRate`, `queueDepth`, `inFlight` (actively
executing operations), and `driftUs` (the lag of the oldest due operation that has not yet started)
also settle to 0 after the stop. Warmup executions are excluded from these counters.

The possible termination causes behave as follows:

- `duration`: indicates normal completion of the schedule. Every arrival within the schedule window
  has either executed or been dropped, and the dispatch queue has drained completely. Under the
  `LATE` policy, execution continues past the end of the arrival window until the accumulated
  backlog finishes draining.
- `stopTime`, `predicate`, `cancel`, `interrupt`, and `error` represent early halts: no further
  arrivals are admitted, any operations remaining in the queue are counted as aborted, and
  currently executing operations are allowed to finish. Arrivals whose intended dispatch times fell
  before the halt timestamp but were held back by a full queue are counted as both scheduled and
  aborted. In addition, `cancel` and `interrupt` actively interrupt the worker threads, while
  `error` is triggered by the first operation that fails. Stop deadlines and termination predicates
  are supplied programmatically through the scheduler API and have no corresponding attributes in
  the query set XML configuration.

## Histogram logs

Both log files conform to the standard HdrHistogram log format consumed by
`org.HdrHistogram.HistogramLogReader`. Recorded values are expressed in microseconds (with a max
value unit ratio of 1.0). The file header lines appear in the following order:

```
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERATION|PHASE]
#[Histogram log format version 1.3]
#[StartTime: <start> (seconds since epoch), <date>]
#[BaseTime: <start> (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
```

Entry timestamps are recorded relative to the base time, which corresponds to `startMs`. The
cumulative run log contains one line per key spanning the full [`startMs`, `endMs`] window.

Each histogram tag consists of the operation identifier, a `|` separator, and the phase. Within the
operation portion, Pherf encodes `%`, space, comma, CR, and LF as `%25`, `%20`, `%2C`, `%0D`, and
`%0A`, because the log reader splits tokens on those characters. To parse a tag, split the string at
its last `|` delimiter and decode every `%XX` sequence in the operation portion. The helper methods
`LatencyReport.tag` and `LatencyReport.parseTag` perform this encoding and decoding.

## Interval export

The `pherf.properties` setting `pherf.default.hdr.intervalMs` controls periodic interval export:

- A positive integer enables the interval log and rolls the histograms every configured number of
  ms.
- An unset property, an empty string, or `0` disables interval export.
- A negative or non-numeric value aborts the run with an error message identifying the property key.

All keys roll across identical time boundaries, with a final roll executed at the end of the run. As
a result, each interval begins where the preceding interval ended, and the interval sample counts
for any key sum to its total count in the JSON report. Pherf emits entries only for non-empty
intervals, and each line is flushed immediately to the file so that a reader can tail the log while
the benchmark is running.

## Legacy reports

The legacy CSV and XML reports retain their existing headers and millisecond units. For metrics that
Pherf also captures in HDR histograms, the reported value is derived from the microsecond
measurement using half-up rounding via `(us + 500) / 1000`, so 2,600 us rounds to 3 ms and 300 us
rounds to 0 ms. This derivation applies to query execution durations (`RUN_TIME_MS` and
`elapsedDurationInMs`) as well as the `durationInMs` field of the `-mt` operation log. The `AVG_*`
columns continue to compute averages using integer division, while data load summary and detail
records, scenario DDL rows, and `-diff` timings remain measured on the millisecond clock.

Legacy reports and metrics sinks receive only measured steady-state operations. Warmup executions do
not contribute to recorded run times, ensuring that `RUN_COUNT`, detail rows, `AVG_*` aggregations,
and XML run time entries reflect only measured executions. In addition, warmup executions emit no
metrics and do not trigger a write workload.

In an open-loop query set, each steady-state operation that actually executes records a single run
time entry containing its service time in ms (including failed executions, which also record their
exception message) and emits one `query` metric, matching closed-loop behavior. Each query maintains
a single thread time entry labeled `1,<concurrency>`, so detail rows report THREAD_NUMBER as 1 and
CONCURRENCY_LEVEL as the configured concurrency. Dropped operations and aborted operations that
never began execution record no run time and emit no metric. Response times, scheduling lag, and
schedule counters are reported exclusively in this JSON report.
