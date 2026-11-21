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

Each histogram key pairs an operation identifier with an execution phase. Pherf currently records
only the `steady` phase, while future warmup support will introduce the `warmup` phase. The
operation identifier follows the `<kind>:<scenario>:<name>` format:

| Kind | Name | One sample |
|---|---|---|
| `query` | query id | One query execution in a query set, from connection to the final row. |
| `load` | table name | One batch committed during the data load phase. |
| `operation` | operation group id | One upsert, query, or pre-run DDL executed in a load profile. |

Failed operations are omitted from recording and produce no sample. Similarly, idle intervals and
user-defined operations in a load profile are excluded because they do not measure database work. A
query that reaches its configured `timeoutDuration` stops fetching rows and is treated as a normal
completion rather than a failure: its recorded sample captures the elapsed time until termination,
near the timeout bound. Consequently, a latency tail at the timeout threshold indicates queries that
timed out.

## JSON report, version 1

| Field | Type | Meaning |
|---|---|---|
| `format` | string | Identifies the report format and is always `pherf-latency`. |
| `version` | integer | Specifies the report schema version, which is `1`. |
| `label` | string | Holds the file suffix without its leading `_` character. |
| `startMs`, `endMs` | integer | Start and end timestamps of the recording in ms since the epoch. |
| `recorder` | object | Recorder settings: `lowestUs`, `highestUs`, and `significantDigits`. |
| `hlog` | string | Gives the file name of the cumulative run histogram log. |
| `intervals` | string | Gives the interval log file name, or is absent when export is off. |
| `histograms` | array | Contains one entry per key, ordered by operation and then by phase. |

Each element in the `histograms` array contains `operation`, `phase`, `count`, `minUs`, `maxUs`,
`meanUs` (formatted as a decimal number), `p50Us`, `p90Us`, `p99Us`, and `p999Us`. All latency
metrics are reported in microseconds at the HDR precision of 3 significant digits. A run that
records no samples produces an empty list.

Consumers must ignore any unknown fields. The schema version is incremented only for backwards-
incompatible changes that an existing reader cannot parse.

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
