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

# The result bundle

A result bundle is the directory generated for a single execution at `$PX_OUT/runs/<run-id>`.
During a run, `px-run` creates the directory, the workload driver and verification checks populate
it with evidence, `px-report` evaluates the collected verdicts, and `px-bundle` writes the manifest
and validates the result. Each execution writes exclusively to its own bundle and no two runs ever
share a file, making every bundle self-contained without reference to any other directory. This
specification defines every file path, column, and key in the bundle format. The library
`lib/bundle.sh` maintains the corresponding path lists along with the writer and reader functions,
and the self-test verifies that the required path definitions in both locations match.

The schema version is defined by `PB_FORMAT_VERSION` in `lib/bundle.sh`, which is currently 1, and
the manifest records this value under `bundle_format_version`. Any modification to a path, column,
or key increments the version number, and `px-bundle validate` rejects any format version that it
does not recognize.

## The run id

A run identifier consists of a sortable UTC timestamp, a hyphen, and a four-character suffix in
the format `<YYYYMMDDTHHMMSSZ>-<xxxx>`, such as `20261120T171109Z-co61`. The four suffix characters
form a single base-36 value of digits and lowercase letters encoding the millisecond within the
second, followed by a per-process counter, followed by one random digit. As a result, identifiers
sort lexicographically in creation order: first by the second, next by the millisecond, and within
the same millisecond of a single process by the counter. Only when two separate processes generate
an identifier within the same millisecond does sorting depend on the random digit. On a host whose
system clock does not report milliseconds, the millisecond field is 0 and the counter preserves
creation order within a single process. The `px_run_dir` function creates the target directory
exclusively and retries with a new identifier whenever the directory already exists, ensuring that
two runs started in the same second—whether in one process or across two—never share a directory.
The identifier grammar matches `^[0-9]{8}T[0-9]{6}Z-[0-9a-z]{4}$`.

## Layout

```text
runs/<run-id>/
  manifest.json                 the run manifest
  run-record.tsv                the execution record and the runtime diagnostics
  stages.tsv                    the stage anchors
  verdicts.tsv                  the verdict stream
  tables.tsv                    UPDATE_CACHE_FREQUENCY of each table               optional
  breach.tsv                    the sample that made the health monitor tear down  optional
  thresholds.properties         the cluster-check thresholds in force              optional
  metrics/timeline.tsv          the timeline metric export
  metrics/aggregate.tsv         the aggregate metric export
  metrics/index.tsv             the collector index                                optional
  metrics/host.tsv              the host samples of px-collect                     optional
  metrics/container/<role>-<index>.tsv  the samples of one instance               optional
  metrics/hiccup/<role>-<index>.hlog    the jHiccup log of one target JVM         optional
  metrics/hiccup/control.hlog   the jHiccup log of the idle control JVM            optional
  cluster/images.tsv            the image of each instance
  cluster/topology.env          the topology and version record of the deployment
  cluster/services.tsv          the services, sizing and JDK of the deployment
  cluster/plugins/<id>/         the render-time hook records of each plug-in         optional
  collect/started.tsv           the collectors that px-collect start started       optional
  drivers/<name>/describe.json  the describe document of the driver
  drivers/<name>/...            the private files of the driver
  logs/<verb>.log               the output of each verb that ran
  logs/hook-<hook>.log          the output of each run hook of the plug-ins         optional
  logs/check-cluster.log        the output of suites/check-cluster.sh               optional
  logs/px-report.log            the output of px-report                            optional
  logs/px-bundle.log            the output of px-bundle write and validate         optional
  logs/px-collect.log           the stderr of px-collect start and stop under px-run  optional
  logs/collect.log              the remote tool output and the sampler log         optional
  logs/instance/<role>-<index>.log  the daemon log of each instance
  logs/gc/<role>-<index>.log    the garbage-collection log of each JVM instance     optional
  plugins/<id>/                 the output of each plug-in, its hooks.tsv           optional
  plugins/<id>/manifest.tsv     the output manifest of a plug-in                    optional
  report/verdicts.tsv           the effective verdict rows
  report/run-summary.tsv        the run summary table
  report/cluster-summary.tsv    the cluster summary table
  profiles/<role>-<index>.jfr   the async-profiler recording of one target JVM     optional
  jfr/<role>-<index>.jfr        the Java Flight Recorder recording of one target   optional
  traces/start.tsv stop.tsv     the Phoenix trace counters before and after run    optional
  traces/counters.tsv           the change of each trace counter over the run      optional
  oplog/                        sampled operation logs                             optional
  meta/                         run metadata of any shape, read by no gate         optional
```

Tabular files in the bundle fall into two categories that share a uniform field-encoding rule. A
stream file contains no header line because either its first column or its column count identifies
each individual row (`run-record.tsv`, `stages.tsv`, `verdicts.tsv`, `metrics/index.tsv`,
`collect/started.tsv`, and `plugins/<id>/hooks.tsv`). By contrast, an export file opens with a
single header row declaring its column names (the metric exports, `cluster/images.tsv`,
`tables.tsv`, `plugins/<id>/manifest.tsv`, the report tables, the collector sample files, and the
`traces/` files). No field may contain a tab or a newline
character; the writers either reject it or replace it with a space, and an empty field remains in
its column position.

## Required paths

During validation, `px-bundle validate` reports each required path that is missing and exits with
code 1. The arrays `PB_REQUIRED` and `PB_REQUIRED_CLUSTER` in `lib/bundle.sh` define these path
lists.

| Path | Producer | Required |
|---|---|---|
| `manifest.json` | `px-bundle write` | always |
| `run-record.tsv` | `px-run` | always |
| `stages.tsv` | `px-run` and the workload driver | always |
| `verdicts.tsv` | `px-run`, the driver, the checks, and the plug-ins | always |
| `metrics/timeline.tsv` | `pb_sample` writers, after `px-run` writes the header | always |
| `metrics/aggregate.tsv` | `pb_metric` writers, after `px-run` writes the header | always |
| `cluster/images.tsv` | `px-run`, containing only the header when run without a cluster | always |
| `report/verdicts.tsv` | `px-report` | always |
| `report/run-summary.tsv` | `px-report` | always |
| `report/cluster-summary.tsv` | `px-report` | always |
| `cluster/topology.env` | `px-run`, copied from the deployment directory | runs with a cluster |
| `cluster/services.tsv` | `px-run`, copied from the deployment directory | runs with a cluster |
| `logs/instance/` | `suites/checks/cluster.sh`, saving one log per instance | instance after run |
| `drivers/<name>/describe.json` | `px-run`, capturing `describe` stdout | runs with a driver |
| `logs/<verb>.log` | `px-run`, capturing the verb stderr and stdout | every verb that ran |

A run is considered to have a cluster when the `run` row of `run-record.tsv` specifies a scope, and
it has an instance after the run when the record contains an `instance` row for the `post` phase,
since an instance that vanished leaves no log to read. The variable `PB_REQUIRED_DAEMON` defines the
daemon log path requirement. The six artifact classes of the harness design map directly onto these
paths: the timeline and aggregate exports are the two metric files; the container inspection
snapshots and runtime diagnostics are captured in `run-record.tsv` together with `cluster/`; the
daemon logs reside in `logs/instance/` as one file per instance, which the log subject of the
cluster checks writes for every surviving instance, including any instance covered by a planned
action; the execution logs reside in `logs/`; and the run and cluster summary tables reside in
`report/`.

## Optional paths

An absent optional path never causes bundle validation to fail. Instead, `px-bundle validate`
reports a missing optional path as `skip`, and the manifest inventory assigns it the status
`skipped`. The variable `PB_OPTIONAL` defines the fixed optional paths of the standard layout. A
driver registers its own optional paths through the `artifacts` member of its describe document and
lists the optional paths it never generates under `unsupported.artifacts`, which the manifest
inventory records as `unavailable` along with the stated reason. A plug-in contributes the directory
`plugins/<id>/` and enumerates its generated files in `plugins/<id>/manifest.tsv`. For every
plug-in named in the `plugins` context row of `run-record.tsv`, the inventory includes
`plugins/<id>/manifest.tsv`—marked as `skipped` when the plug-in wrote no manifest—followed by each
path listed after the manifest header, resolved relative to `plugins/<id>/` and marked as either
`present` or `skipped`. The harness reads no other files produced by a plug-in. Each declared path
of a driver or plug-in that is absent, whether marked `skipped` or `unavailable`, also produces a
corresponding `SKIP` row `artifact.<path>` in the report so that the validator and the report yield
the same result.

## run-record.tsv

The execution record of `px-run` is stored as an unheadered stream of tab-separated rows. The first
column identifies the row type, and rows appear in the chronological order of the events. Where a
row type permits superseding entries, readers retain the final row of that type.

| Type | Fields after the type | Writer and meaning |
|---|---|---|
| `run` | run_id run_class backend scope driver_path | records the core identity of the run |
| `context` | key value | records `driver`, `suite`, `test`, `session`, and `plugins` of the run |
| `stage` | name ts_ms | marks a `px-run` milestone that a disruption trigger can reference |
| `verb` | verb exit_code elapsed_ms timed_out | one row per verb call, with timed_out 0 or 1 |
| `version` | phase component version | substrate tooling versions from `sb_version_info` |
| `instance` | phase role index instance_id state ready image_ref node | state of each instance |
| `restart` | phase role index count | records the container restart count per snapshot |
| `sample` | phase ts_ms role index cpu_pct mem_bytes mem_limit_bytes ... | resource usage sample |
| `diff` | role index before after verdict pre_state post_state | state transition of an instance |
| `term` | role index reason exit_code signal finished_at | evidence of an unplanned state change |
| `oom` | role index oom evidence | out-of-memory evidence of an unplanned instance change |
| `quiet` | kind, followed by the fields for that kind below | results of the quiet-host check |
| `contamination` | cause detail | `quiet-host` or `sizing`, marking time evidence as not valid |
| `monitor` | phase dir | records the run directory of the background health monitor |
| `disrupt` | ordinal kind when action role index argument state | the final row for each counts |
| `action` | ts_ms action role index argument state exit_code | one injected disruption action |
| `phase` | role index phase status elapsed_ms | records the sub-phases of a drain-aware restart |
| `recover` | ordinal injected_ms recovered_ms state | the recovery gate outcome of a disruption |
| `abort` | ts_ms role index change | records the unplanned instance change that aborted the run |
| `breach` | path | records the bundle copy of the breach sample from the health monitor |
| `skip` | phase subject reason | a skipped step and its reason, including a missed disruption |
| `result` | exit_code name anomaly | the final row; a record lacking this row was interrupted |

A `quiet` row has the form `sample ts_ms load_pct threshold_pct` or `result state samples waited_s
load_pct`. A `diff` verdict is `unchanged`, `planned`, `unplanned`, `vanished` or `unknown`. A
`quiet` result state is `quiet`, `contaminated`, `refused`, `unmeasured`, or `off`. A `disrupt`
kind is either `offset`, with `when` expressed in seconds after the `run` verb starts, or `phase`,
naming a lifecycle milestone. A `disrupt` state is `pending`, `ok`, `skip`, `fail`, or `missed`.
The value `missed` indicates an anchor that began and ended before `px-run` observed it open, so the
disruption never fired; a corresponding `skip` row also identifies it. A `recover` state is `ok`,
`timeout`, or `fail`, and recovered_ms remains empty unless the state is `ok`. A `sample` phase is
either `pre` or `post` for the boundary snapshots around the workload, or `monitor` for a continuous
sample collected by the health monitor from the start of the run onward. The `plugins` context row
lists the identifiers of the enabled plug-ins separated by spaces. The fields of the `instance`,
`sample`, `term`, `oom`, and `phase` rows follow the schemas defined in `docs/backends.md`, section
"Schemas".

## stages.tsv

The stage file records one row per stage boundary in the format `ts_ms event anchor source detail`.
The event value is either `begin` or `end`, and an anchor is formatted as `<kind>@<name>` matching
the regular expression `^[a-z]+@[A-Za-z0-9_.-]+$`. The function `pb_stage` writes an individual
boundary row, while `pb_stage_pairs` pairs each `begin` row with the first subsequent `end` row for
the same anchor.

| Anchor | Writer | Window |
|---|---|---|
| `verb@<verb>` | `px-run` | one verb invocation of the driver |
| `collector@<name>` | the collector | the active collection window of a single collector |
| `steady@<name>` | the driver, during `run` | the steady-state measurement window |
| any other `<kind>@<name>` | the driver | a phase declared in the describe document |

Only the `steady` anchor kind carries special meaning to the harness. A `steady@<name>` window
represents the half-open time interval from its `begin` event to its `end` event during which the
configuration remains constant, warm-up has completed, and the driver issues its measured workload
operations. Every base bundle carries at least one closed steady window. Whenever `px-run` stops a
verb, it closes every open anchor by writing a terminating row with the source `px-run`, ensuring
that a finished bundle never contains an open anchor.

## verdicts.tsv

The verdict stream records one row per evaluation verdict in the format
`check severity class source detail`.

* `check`: the check identifier, such as `driver.run`, `cluster.restart`, or
  `plugin.example.marker`. A check key never contains a tab character.
* `severity`: a single keyword from the severity lattice defined below. Because the severity always
  occupies the second column, a consumer inspecting only the worst severity does not need to read
  any other column.
* `class`: the evidence classification, which is `count`, `share`, `duration`, or `none`. The value
  `none` designates a functional or structural check. Only `duration` verdicts are ever demoted.
* `source`: the emitting component taken from `PX_RECORD_SOURCE` (`px-run`, `driver:<name>`,
  `check:cluster`, `plugin:<id>`, or `collector:<name>`), or `action` when no writer identifies
  itself. The source `pherf` identifies rows originating from the Pherf verdict file, which the
  pherf driver copies verbatim.
* `detail`: human-readable diagnostic text for an operator. Every `SKIP` row states the reason why
  the check did not run.

The function `sx_verdict_row check severity class detail...` in `suites/lib/common.sh` is the sole
writer for the verdict stream and rejects an unknown severity or class with exit code 2. The helper
`sx_verdict check severity detail...` delegates to it with the class set to `none`. Conversely,
every reader rejects any row in a verdict file that contains an unknown severity or class and
counts that row as `ERROR`.

## Metric exports

Both `metrics/aggregate.tsv` and `metrics/timeline.tsv` share a single header line and column
schema:

```text
start_ms end_ms source anchor key class value unit run_class contamination
```

* `start_ms`, `end_ms`: the time window covered by the metric value, expressed in epoch
  milliseconds. A point-in-time timeline sample sets the start timestamp equal to the end
  timestamp.
* `source`: the writer identity, populated from `PX_RECORD_SOURCE`.
* `anchor`: the lifecycle stage associated with the value, either a `<kind>@<name>` anchor or `run`
  for the entire execution.
* `key`: the metric identifier, matching the grammar `^[A-Za-z0-9_][A-Za-z0-9_.:@-]*$`.
* `class`: the metric evidence class—`count`, `share`, or `duration`—as specified in
  `docs/driver-contract.md`.
* `value`: a JSON number, accompanied by `unit`, which is a single token such as `rows`, `ms`,
  `ops/s`, or `ratio`.
* `run_class`: either `validation` or `measurement`, captured from `PX_RUN_CLASS` at write time.
* `contamination`: the active contamination causes captured from `PX_CONTAMINATION` at write time,
  or `none` when clean.

The function `pb_metric key class value unit [anchor [start_ms end_ms]]` appends a record to the
aggregate export with one effective value per source, anchor, and key, such that a later record
replaces an earlier one. The function `pb_sample key class value unit [anchor [ts_ms]]` appends a
point-in-time observation to the timeline export. Both functions reject any record when no valid
run class is set, ensuring that no metric record can lose its run class provenance. Any record
written under the validation class, or carrying a contamination value other than `none`, never
qualifies as authoritative time evidence regardless of which tool reads it.

**Reading metrics.** The command `px-bundle metrics [BUNDLE] [ANCHOR [KEY]]` queries
`metrics/aggregate.tsv` and outputs every row whose anchor and key match the supplied shell glob
patterns (`*`, `?`, and `[...]`, each defaulting to `*`) in the format `anchor key value unit`,
exiting with code 6 if no rows match or the table is empty. When multiple records share the same
source, anchor, and key, the final record supersedes earlier ones while retaining the output
position of the first occurrence. The first operand designates the target bundle whenever three
operands are provided or whenever that operand is a run id or contains a `/`; otherwise, the command
reads the newest finished bundle. For example, invoking
`px-bundle metrics 'op@*' 'pherf.latency.*_us'` prints the latency metrics across every operation.

## The other exports

* `cluster/images.tsv`: contains `role index image_ref image_id` with one row per instance present
  in the pre-run snapshot. To populate it, `px-run` obtains `image_ref` from `sb_list` and
  `image_id` from `sb_image_ref`; on Compose the identifier is the local image ID, whereas on
  Kubernetes it is the image digest.
* `tables.tsv`: contains `schema table update_cache_frequency` with one row for each table that the
  `prepare` verb created or altered, as read back from `SYSTEM.CATALOG`. The function `pb_table`
  writes this file and records an empty schema or attribute value as `default`.
* `cluster/topology.env` and `cluster/services.tsv`: hold copies of the corresponding files from
  the deployment directory as defined in `docs/backends.md`. Together they record the complete
  version set of the cluster: the HBase profile, the HBase and Hadoop releases, the ZooKeeper and
  Hadoop client versions bundled with HBase, and the JDK version of each service.
* `breach.tsv`: holds a copy of the breach sample captured by the health monitor whenever a
  threshold breach terminated the run.
* `thresholds.properties`: records the effective threshold values applied by the cluster checks
  after merging the `PX_THRESHOLD_<KEY>` settings from the run configuration and any plug-in
  overlays according to the precedence order defined in "The cluster checks".
* `plugins/<id>/hooks.tsv`: contains `ts_ms hook status elapsed_ms` with one row per hook call
  written by the plug-in loader, plus one row with the hook name `check` for each check script
  executed by `suites/check-cluster.sh`. Alongside it, `plugins/<id>/manifest.tsv` uses the columns
  `path description` to list the output files written by the plug-in relative to `plugins/<id>/`.

**The pherf driver files.** The pherf driver copies two Pherf output directories into the bundle
without modification: the legacy result reports and the HDR latency and verdict artifacts. Because
the driver declares each path in its describe document, the manifest inventory tracks them and
reports any absent file as a standard `SKIP` row. The schemas for these files are defined in
`phoenix-pherf/LATENCY.md` and `phoenix-pherf/VERDICT.md`.

| Path | Content |
|---|---|
| `drivers/pherf/results/` | legacy result reports containing the `RESULT_*_aggregate.csv` files |
| `drivers/pherf/groups.tsv` | summary table with one row per query group from the aggregate CSV |
| `drivers/pherf/results_HDR/` | verbatim copy of `<pherf.default.results.dir>_HDR` |
| `drivers/pherf/results_HDR/LATENCY_run.json` | JSON latency report emitted for the `run` verb |
| `drivers/pherf/results_HDR/LATENCY_run.hlog` | run histogram log with one HDR histogram per key |
| `drivers/pherf/results_HDR/LATENCY_run_intervals.hlog` | interval log, written when exported |
| `drivers/pherf/results_HDR/VERDICT_run.tsv` | Pherf health verdicts emitted for the `run` verb |

Because the `prepare` verb executes Pherf with the label `load`, the `results_HDR/` directory also
contains the corresponding `LATENCY_load.*` and `VERDICT_load.tsv` files, which no downstream
consumer reads. The interval histogram log is generated only when `pherf.default.hdr.intervalMs` is
configured.

**The pherf-distributed driver files.** The pherf-distributed driver copies the controller run
bundle produced during its `run` verb into `controller/` file by file without modification, and
copies the load bundle produced during its `prepare` verb into `load/`. The structure of a
controller bundle is defined in `phoenix-pherf-distributed/README.md`, section "Run bundle". A
controller bundle is distinct from a harness bundle: `controller/manifest.json` is the controller's
own manifest with its own `bundle_format_version`, while `px-bundle validate` validates the
enclosing harness bundle around it. Because the driver declares each of these paths in its describe
document, this table introduces no new required paths and leaves the harness bundle format version
unchanged.

| Path | Content |
|---|---|
| `drivers/pherf-distributed/agents.json` | controller agent inventory captured prior to the run |
| `drivers/pherf-distributed/plan-load.json` | workload execution plan submitted by `prepare` |
| `drivers/pherf-distributed/plan-run.json` | workload execution plan submitted by `run` |
| `drivers/pherf-distributed/tests.tsv` | verb, test id, run id, and final state of each test |
| `drivers/pherf-distributed/run.tsv` | steady window, test id, run id, and final run state |
| `drivers/pherf-distributed/report.json` | run latency report, identical to the fleet report |
| `drivers/pherf-distributed/load/` | controller run bundle captured from the data load phase |
| `drivers/pherf-distributed/controller/` | controller run bundle captured from the measured run |
| `drivers/pherf-distributed/controller/manifest.json` | manifest generated by the controller |
| `drivers/pherf-distributed/controller/status.json` | run status snapshot at bundle commit time |
| `.../controller/drivers/pherf/results_HDR/LATENCY_run.json` | fleet latency report, merged keys |
| `.../controller/drivers/pherf/results_HDR/LATENCY_run.hlog` | merged run HDR histogram log |
| `.../controller/drivers/pherf/results_HDR/VERDICT_run.tsv` | fleet verdict stream of eight rows |
| `.../controller/drivers/pherf/results_HDR/slots/<step>-<slot>.hlog` | raw histogram log per slot |

Because Pherf `-analyze` reads each replicate from `drivers/pherf-distributed/controller` rather
than from the root of the harness bundle, pass that subdirectory from each bundle in a replicate
set.

## The collectors

`px-collect` serves as the unified entry point for all six telemetry collectors. During execution,
`px-run` invokes `px-collect start` against the active bundle for the collectors configured in
`PX_COLLECTORS` (either `none` or a comma-separated list) immediately before the `run` verb begins,
and invokes `px-collect stop` immediately after the verb finishes. Passing `px-run --collect LIST`
overrides the configured collector list for an individual run, while `--targets LIST` overrides
`PX_COLLECT_TARGETS`. The variable `PB_COLLECTORS` in `lib/bundle.sh` defines the six collectors in
their startup order:

| Collector | Tool | Files |
|---|---|---|
| `profile` | `px-collect`: async-profiler, CPU and alloc, 7919 us, all frames | `profiles/*.jfr` |
| `jfr` | `px-collect`: Java Flight Recorder via `jcmd` using the `profile` settings | `jfr/*.jfr` |
| `hiccup` | `px-collect`: jHiccup in each target and an idle JVM in tooling 0 | `metrics/hiccup/` |
| `host` | `px-collect`: host load and memory utilization samples | `metrics/host.tsv` |
| `container` | `px-collect`: instance samples in the sample schema | `metrics/container/*.tsv` |
| `trace` | `px-collect`: Phoenix trace counters from every RegionServer | `traces/*.tsv` |

By default, the measurement run class starts all six collectors, whereas the validation class starts
none. Driver HDR histogram logs are not managed as collectors, and `px-run` never disables them. The
target instances for `profile`, `jfr`, `hiccup`, and `container` are the instances listed in
`cluster/services.tsv` whose roles match `PX_COLLECT_TARGETS`, which defaults to `all`. The `all`
target carries a uniform definition across every collector: it selects every service role except
`setup` and the `pherf` role group, because the setup instance runs no persistent daemon, the pherf
image omits async-profiler, JFR tooling, and jHiccup, and the tier operates as the load generator
rather than a cluster daemon.
Collectors interact with the cluster exclusively through `sb_exec` and retrieve remote files by
streaming the output of `cat`, while diagnostic output from remote tools is captured in
`logs/collect.log`. Each sample row in `metrics/host.tsv` and
`metrics/container/<role>-<index>.tsv` records a single sampling pass taken at the interval
`PX_COLLECT_INTERVAL_S` and concludes with the `run_class` and `contamination` columns; because the
initial pass executes before the run verb starts and the final pass executes after it finishes, the
resulting sample series spans the entire workload execution. A counter delta in
`traces/counters.tsv` is valid only for a RegionServer that retained the same JVM throughout the
run, as a JVM restart resets all counters to 0 and the instance's `diff` row in `run-record.tsv`
records that state transition.

**Collections by hand.** Running `px-collect start [BUNDLE]` launches the collectors specified by
`--collect LIST` within an existing bundle, within the cluster's active collection directory, or
within a newly allocated directory at `$PX_OUT/collect/<run-id>` populated with
`cluster/services.tsv` from the deployment record; the command prints the target directory path and
returns while the background samplers continue executing. A bare run identifier resolves first to a
collection under `$PX_OUT/collect` and otherwise to a run bundle under `$PX_OUT/runs`. Running
`px-collect stop [BUNDLE]` terminates the collectors recorded during startup, emits the index rows
for the stopped collectors, and concludes with a `result ok|fail|skip CODE DIR` summary row, leaving
any bundle with no active collectors untouched. Active collections for a cluster are tracked in
`state/collect-<cluster>.tsv` so that `px-cluster down` can stop each collection prior to cluster
teardown. Each collector may execute at most once per bundle; attempting a second start of the same
collector fails with exit code 2.

**collect/started.tsv.** This unheadered stream records one row for each started collector using the
schema `collector targets pid duration_s start_ms`. The target column is set to `-` for `host` and
`trace`, while the process identifier column records the background sampler pid for `hiccup`,
`host`, and `container` and `-` for all other collectors. A collector is considered live from the
moment its startup row is recorded until the collector itself writes the closing `collector@<name>`
stage row with source `collector:<name>`. Because that closing stage row is written only after the
collector's index rows and verdict row have been emitted, a subsequent stop invocation never writes
duplicate rows.

**The index.** The file `metrics/index.tsv` is an unheadered stream recording one row per collection
target with the fields `collector target status
file detail`. The status is `ok`, `skip` (when the target instance is not running, does not run a
JVM, or lacks the tool required by `jfr` or `trace`), or `fail` (for any other fault, including a
missing async-profiler or jHiccup binary). The output file named by an `ok` row becomes a required
path in the artifact inventory, so any missing file from a successful collection causes
`px-bundle validate` to fail. For any other status, the artifact file is treated as optional: if
stopping a `profile` or `jfr` session fails, the collector still retrieves whatever partial
recording was produced and logs it under a `fail` row.

**The verdict.** Each collector emits a single verdict row under `collector.<name>` with evidence
class `none` and source `collector:<name>`, recording `ADVISORY` if any target row failed, `PASS` if
no row failed and at least one row succeeded with `ok`, or `SKIP` otherwise. A sampler that fails to
start or stop within its timeout bound likewise records `ADVISORY`, and `px-run` emits an `ADVISORY`
verdict for each configured collector whenever `px-collect start` or `stop` exits with a status code
other than 0 or 1. Collectors never emit `FAIL` or `ERROR` verdicts, ensuring that lost telemetry
never causes a workload run to fail.

**The bounds.** Every `px-collect` invocation launched by `px-run` budgets one `PX_TIMEOUT_VERB`
interval per synchronous step: one each for `profile`, `jfr`, and `trace`, plus one shared interval
for the background sampler process. In addition, every collector is bounded by `--duration` (at
least 1 second and defaulting to `PX_TIMEOUT_RUN`); `px-run` sets this duration to expire one verb
timeout bound after the deadline of the run verb so that each remote collection session (the asprof
timeout, the JFR recording duration, and the jHiccup execution duration) as well as the local
sampler process self-terminates cleanly. Collectors stop in reverse startup order (`trace`, followed
by the background samplers, `jfr`, and `profile`), and the exit trap of `px-run` runs this shutdown
sequence on any early termination once the active driver verb halts and before releasing the host
sleep inhibitor; stopping an already-stopped collection is a no-op that writes no additional rows.
During shutdown, `px-collect stop` sends TERM to the sampler process and escalates to KILL across
its process tree if it does not exit within half of `PX_TIMEOUT_VERB` (at least 1 s), while
`px-collect start` waits up to that same half-bound for the sampler stage windows to open, ensuring
that forced termination and closing rows complete within the overall call timeout. Each collector
brackets its active execution with a `collector@<name>` stage window; if `px-run` terminates the
driver upon an interrupt, it closes all open driver stage windows before the closing collector stage
rows are written.

## manifest.json

After `px-report` finishes, `px-bundle write` assembles the manifest from the bundle files
described above. Because the file is never edited by hand, `px-bundle write` can safely regenerate
it at any time. Every member shown below is required; an unknown string value is represented as the
empty string, and an unknown numeric value is represented as `null`.

```json
{
  "bundle_format_version": 1,
  "run": {
    "id": "20261120T171109Z-co61", "class": "validation", "backend": "compose",
    "scope": "px-default", "driver": "psql", "suite": "smoke", "test": "create",
    "session": "20261120T171100Z-ab12", "started_ms": 1795194669000,
    "ended_ms": 1795194690000, "result": "PASS"
  },
  "versions": {
    "phoenix_hbase_profile": "2.6", "hbase": "2.6.7", "hadoop": "3.4.3",
    "zookeeper": "3.8.6", "hbase_hadoop_client": "3.4.3",
    "jdk": { "regionserver": "17", "client": "17" }
  },
  "images": [
    { "role": "regionserver", "index": 0, "image_ref": "phoenix-harness:local",
      "image_id": "sha256:..." }
  ],
  "tables": [ { "schema": "default", "table": "T1", "update_cache_frequency": "default" } ],
  "quiet_host": { "state": "off", "tags": ["quiet-host"] },
  "breach": null,
  "anomaly": null,
  "stages": [
    { "anchor": "steady@script", "source": "driver:psql", "start_ms": 1795194680000,
      "end_ms": 1795194685000 }
  ],
  "steady": [
    {
      "anchor": "steady@script", "start_ms": 1795194680000, "end_ms": 1795194685000,
      "metrics": {
        "query_latency.p50_ms": null,
        "query_latency.p99_ms": null,
        "query_errors": { "value": 0, "start_ms": 1795194685000, "end_ms": 1795194685000 },
        "offered_rate": null,
        "achieved_qps": null
      }
    }
  ],
  "disruptions": [
    { "ordinal": 1, "action": "kill", "role": "regionserver", "index": 1, "argument": "hard",
      "when": "phase:steady@script", "state": "ok", "recovery": "ok",
      "injected_ms": 1795194681000, "recovered_ms": 1795194721450, "mttr_seconds": 40.450 }
  ],
  "artifacts": [
    { "path": "run-record.tsv", "status": "present", "required": true, "reason": "" }
  ]
}
```

| Member | Source | Meaning |
|---|---|---|
| `bundle_format_version` | `PB_FORMAT_VERSION` | the schema version of this bundle |
| `run` | `run` and `context` rows, `report/run-summary.tsv` | the run identity and final result |
| `versions` | `cluster/topology.env`, `cluster/services.tsv` | the complete cluster version set |
| `images` | `cluster/images.tsv` | the container image metadata of each instance |
| `tables` | `tables.tsv` | the effective `UPDATE_CACHE_FREQUENCY` of each table |
| `quiet_host` | the `quiet` result and `contamination` rows | host quiescence state and tags |
| `breach` | `breach.tsv` | the relative path of the breach sample, or null |
| `anomaly` | `abort`, `diff`, `term`, `oom` rows | every unplanned change and its evidence |
| `stages` | `stages.tsv` | every closed stage anchor and its time window |
| `steady` | `stages.tsv`, `metrics/aggregate.tsv` | each steady-state window and its metrics |
| `disruptions` | `disrupt` and `recover` rows | each disruption and its recovery outcome |
| `artifacts` | `pb_artifact_rows` | the complete file inventory of the bundle |

The field `run.started_ms` records the timestamp of the first milestone of `px-run`, while
`run.ended_ms` records the timestamp at which the manifest is written, and `run.result` holds the
merged run outcome computed by `px-report`. The field `quiet_host.state` reflects the state of the
`quiet` result row, defaulting to `unknown`, while `quiet_host.tags` lists all contamination
causes; an empty array indicates that the time evidence of the run is clean. Each entry in
`artifacts` carries a status of `present`, `missing`, `skipped`, or `unavailable`.

**The anomaly.** The `anomaly` member is `null` whenever no instance changed state without a
planned action, which corresponds exactly to the `result` row of `run-record.tsv` recording the
anomaly flag `no`. Otherwise, it is an object with the following structure:

```json
"anomaly": {
  "aborted": true, "abort_ms": 1795194683000,
  "instances": [
    { "role": "regionserver", "index": 0, "change": "went from running to exited",
      "restarts_before": 0, "restarts_after": 0, "termination_reason": "oom",
      "exit_code": 137, "oom": true }
  ]
}
```

The boolean `aborted` is `true` when an `abort` row halted execution while `prepare` or `run` was
active, and `abort_ms` records the timestamp of the first such row, or `null` if none occurred.
The `instances` array contains one entry for each instance referenced in an `abort` row or in a
`diff` row marked `unplanned` or `vanished`: it records the state change description from the
instance's `abort` row (or otherwise from its `diff` row), the before and after restart counts from
the `diff` row, the termination reason and exit code from its `term` row, and the out-of-memory
flag from its `oom` row. Any attribute not present in the record defaults to the empty string or
`null`. The `cluster.restart` check reports these exact same fields in its verdict detail.

**The steady window.** The `steady` array contains one entry for each `steady@` anchor. Its
`metrics` object always includes the five steady-state metric keys—`query_latency.p50_ms`,
`query_latency.p99_ms`, `query_errors`, `offered_rate`, and `achieved_qps`—each set either to
`null` or to the final aggregate record emitted for that key and anchor, carrying its value and its
own observation window. The sixth steady-state metric is recorded under
`disruptions[].mttr_seconds`.

**The recovery time.** For each disruption, `mttr_seconds` is calculated as
`(recovered_ms - injected_ms) / 1000` formatted to three decimal places, or set to `null` when the
recovery gate did not pass. The timestamp `injected_ms` captures the moment immediately preceding
the substrate disruption call. Before invoking that call, `px-run` determines the baseline online
region count of the cluster by summing `regionCount` across the RegionServers queried by
`px-region-count` inside `client` 0. Immediately after a restart or a rebalance, a RegionServer can
respond before publishing `regionCount`, so a failed read indicates that the server is not yet
ready; `px-run` retries the read within the `PX_TIMEOUT_READY` bound and the active verb time
bound. If the read still fails at the end of that bound, no disruption is injected and the
disruption state is recorded as `fail`. A resume operation skips this read because a paused server
cannot respond, reusing instead the baseline count captured before the pause. The timestamp
`recovered_ms` marks the time at which the recovery gate of `px-run` succeeds across three checks in
sequence: any killed RegionServer has been started again through the drain-aware restart procedure,
every configured instance reports ready, and the summed online region count has returned to the
count measured before the disruption. Because the region-count wait uses the remainder of the
post-restart `PX_TIMEOUT_READY` bound, a single disruption consumes at most one `PX_TIMEOUT_DRAIN`
interval and two `PX_TIMEOUT_READY` intervals. Immediately after the readiness wait and on each
subsequent region-count poll, the gate also inspects the state of the disrupted instance: if the
instance has exited with any exit code or is absent, the gate terminates immediately with state
`fail` because a terminated server cannot restore online regions. Because the backend treats a
one-shot exit status of 0 as ready, the readiness wait alone would not detect an instance that
exited cleanly with code 0. If a restart or readiness wait expires, the recovery state is recorded
as `timeout`, whereas if the online region count has not returned to the baseline by the end of the
bound, the state is recorded as `fail` with both counts included in the diagnostic message. A
disruption that fires while a verb is running waits within the time bound of that verb—the shared
deadline governing prepare and run—so each recovery step receives at most the time remaining in the
verb budget. Any drain, readiness, or region wait truncated by that verb bound enters the state
`timeout` and terminates the verb at its deadline, assigning exit code 4 to the verb and causing
`px-run` to exit with code 4. Consequently, `mttr_seconds` measures the full interval until the
server is live and every region is back online. The recovery gate never inspects `ritCount` (the
regions-in-transition count, which can lag by up to a minute), and region rebalancing is excluded
from recovery because a rejoined server does not need to be balanced to serve traffic. A kill action
must target a RegionServer—the service role whose drain label is `regionmover`—using the mode
`hard`, because only the drain-aware restart procedure starts a killed instance again and a deleted
instance cannot be started again; `px-run` rejects any other kill configuration with exit code 2
before any verb runs. Finally, the cluster check `cluster.recovery` compares the measured value
against the threshold `recovery.seconds.max`.

**A pause.** A `signal` disruption delivering `STOP` or `TSTP` pauses the daemon JVM of the target
instance (`sb_signal` in [backends.md](backends.md)), and the JVM cannot recover while it remains
paused. Its recovery gate is therefore deferred until the instance is resumed by the next scheduled
`signal` `CONT` targeting the same instance. The recovery gate executes after that `CONT` and emits
a `recover` row for the `CONT` as well as for each pause entry that it concludes, measured from
each entry's own injection timestamp; consequently, the `mttr_seconds` of a pause spans the full
interval from the initial `STOP` until the server is live and every region is back online following
the `CONT`. If the run verb finishes while an instance is still paused, `px-run` delivers the
`CONT` itself as a planned action, records a corresponding `action` row, and executes the recovery
gate for the pause. On an abnormal exit, it delivers the `CONT` without running the gate or writing
a record row so that no instance is left paused after `px-run` terminates. A `STOP` or `TSTP` whose
post-signal state check failed after the signal was sent may still leave the JVM stopped and is
tracked as a pause as well: its disruption state is recorded as `fail` and it receives neither a
recovery gate nor a `recover` row, yet the subsequent `CONT` from the schedule or from `px-run` is
still delivered to it. No other disruption may execute during the window between a pause and its
resuming `CONT`, because the baseline region read and recovery gate of another entry would block on
the paused server and delay the `CONT`; any other entry that triggers during this window injects
nothing and is recorded with state `fail`. Pausing a server for longer than its ZooKeeper session
timeout causes the master to expire the server as dead, forcing the daemon to abort upon resuming
and causing the recovery gate to fail. Because the container image bounds the ZooKeeper session
timeout to at most 40 s, a schedule that pauses a server must deliver its `CONT` less than 25 s
after the `STOP`.

## Validation

The command `px-bundle validate [BUNDLE]` performs offline validation of a result bundle without
requiring a workload driver or an active cluster. The BUNDLE operand specifies either a directory
path or a run identifier beneath `$PX_OUT/runs`, defaulting to the newest finished bundle, and must
resolve within PX_OUT. The command emits one tab-separated row per validation check in the format
`check status detail` (where status is `ok`, `fail`, or `skip`) followed by a summary row
`result ok|fail CODE BUNDLE`. It exits with code 0 when all checks pass, 1 when any check fails, 2
on a usage error or a target path outside PX_OUT, and 6 when the specified bundle is absent or no
finished bundle exists. The validator enforces the following rules:

1. `manifest.json` exists and forms a single valid JSON document.
2. `bundle_format_version` is present and equal to `PB_FORMAT_VERSION`.
3. `run.id` matches the bundle directory name and conforms to the run identifier grammar.
4. `run.class` is either `validation` or `measurement`, and `run.backend` is non-empty.
5. `versions`, `images`, `tables`, and `quiet_host.tags` are present with their expected types, each
   entry in `images` includes an `image_id`, and each entry in `tables` includes an
   `update_cache_frequency`. In addition, `anomaly` is either `null` or an object containing
   `aborted`, `abort_ms`, and at least one entry in `instances`, where each entry carries every
   member shown in the schema example above.
6. `steady` contains at least one entry with a `steady@` anchor and a closed time window. Each
   entry names its anchor and holds integer `start_ms` and `end_ms` timestamps such that the end
   does not precede the start, and the validator explicitly names the defect if an entry has no
   name, no start, no end, or an end timestamp prior to its start. Each entry also holds all five
   steady-state metric keys, and every metric value that is not `null` lies within its enclosing
   anchor window, with `start_ms` at or after the start of the anchor and `end_ms` at or before its
   end.
7. `disruptions` is present, and every entry includes the key `mttr_seconds`.
8. No stage entry in `stages` remains open.
9. Every required path is present in the bundle, and each absent optional path is reported as a
   `skip` row.
10. The captured describe document of the driver passes `pb_describe_load`.
11. Every row in the metric exports contains ten fields, a recognized metric class, a numeric value,
    a run class matching the manifest, and a contamination field set to `none` when the run is
    clean. Every row in `verdicts.tsv` carries a valid severity from the lattice and a recognized
    evidence class.
12. Every row in `metrics/index.tsv` contains five fields, a collector name from `PB_COLLECTORS`, a
    status of `ok`, `skip`, or `fail`, and, when the status is `ok`, a valid relative path inside
    the bundle. Rule 9 fails validation if the artifact file referenced by an `ok` row is missing,
    whereas an absent index file is reported as a `skip` row.

The command `px-bundle write BUNDLE` generates the bundle manifest via `pb_manifest_write`. It exits
with code 0 on success, 1 if the manifest cannot be written, 2 on a usage error or a bundle path
outside PX_OUT, and 6 if the target bundle directory does not exist. The `write` subcommand requires
an explicit bundle operand, and no subcommand consults `PX_RUN_DIR`.

## Verdict severity

Every verdict carries a single severity from the ordered lattice defined in `suites/lib/common.sh`:

```text
PASS  <  SKIP  <  ADVISORY  <  FAIL  <  ERROR
```

| Severity | Meaning | Fails the run |
|---|---|---|
| `PASS` | the check invariant held | no |
| `SKIP` | the check could not run for a stated reason, or an item was unavailable | no |
| `ADVISORY` | the check did not hold, but its timing evidence is not authoritative | no |
| `FAIL` | the check did not hold, and its evidence is authoritative | yes |
| `ERROR` | the harness or the infrastructure failed, so the run proves nothing | yes |

The severity `ERROR` is reserved for harness or infrastructure faults, including an unplanned
restart, an unexpected container exit or out-of-memory kill, a driver returning exit code 2 or 3, or
a check script that could not execute. This distinction separates cluster or harness anomalies from
workload verification failures and aligns directly with exit code 3. Because `SKIP` sits below
`ADVISORY` in the ordering, an absent optional artifact can never obscure a genuine advisory
verdict.

The reporting tool `px-report [BUNDLE]` merges the verdict rows of a run in four steps. The BUNDLE
operand specifies a directory path or a run identifier beneath `$PX_OUT/runs` and must resolve
within PX_OUT; when omitted, `px-report` reads the newest finished bundle unless `--verdict`
specifies explicit verdict streams to merge, in which case only those streams are evaluated. When
operating on a bundle, `px-report` regenerates `report/` from all merged streams—including any
additional `--verdict` stream—while `manifest.json` retains the original result of the run.

1. **Collect.** It reads the bundle's `verdicts.tsv` along with every file supplied via
   `--verdict FILE`. Any row whose severity or evidence class is unknown is converted to `ERROR`
   with an explanatory detail message.
2. **Demote.** A `FAIL` row of the `duration` class is demoted to `ADVISORY` unless the run's time
   evidence is authoritative, which requires that the run class be `measurement` and that the run
   contain no `contamination` row. No other verdicts change: count, share, and functional checks
   remain authoritative under all conditions, and no rule ever elevates a severity. The function
   `pb_effective_severity` implements this demotion rule.
3. **Join.** The overall run result is the highest effective severity across all rows. A run with
   no verdict rows, or with only `SKIP` rows, receives the outcome `INCONCLUSIVE`, which is a run
   result rather than a row severity.
4. **Write.** When operating on a bundle, it writes the three report files described below. It emits
   the effective verdict rows followed by a closing summary row
   `result <result> <exit code> <bundle>` on standard output, substituting `-` for the bundle field
   when no bundle was read.

The exit code of `px-report` is 0 for `PASS`, `SKIP`, and `ADVISORY`, 1 for `FAIL` and
`INCONCLUSIVE`, 3 for `ERROR`, 2 for a usage error, a bundle path outside PX_OUT, or an unreadable
record, and 6 when the target bundle is absent, no finished bundle exists, or the execution record
is missing. When evaluating standalone verdict streams without a bundle, it obtains the active run
class and contamination state directly from `PX_RUN_CLASS` and `PX_CONTAMINATION`.

### The report files

* `report/verdicts.tsv`: contains `check severity class source detail written` with one row per
  merged verdict, placing the effective severity in the second column and the originally written
  severity in the final column. The suite runner `px-suite` reads the worst severity from this file
  when evaluating a driver test.
* `report/run-summary.tsv`: contains a single summary row beneath the header
  `run_id class backend driver suite test result rows pass skip advisory fail error contamination`.
* `report/cluster-summary.tsv`: holds one row per pre-run snapshot instance under the header `role
  index image_id state_pre state_post restarts_pre restarts_post change oom`, from the `instance`,
  `restart`, `diff`, and `oom` rows together with `cluster/images.tsv`. When no cluster instances
  exist, the file contains only the header line.

## The cluster checks

The runner `suites/check-cluster.sh` executes after the `report` verb while the cluster remains up.
It merges the active threshold configuration, runs the built-in subjects in
`suites/checks/cluster.sh` and the steady-state checks in `suites/checks/steady.sh`, and then runs
the check scripts of each enabled plug-in, with every check appending verdict rows. During a run
with a cluster, `px-run` invokes this runner automatically. Each individual plug-in check script is
bounded by `PX_TIMEOUT_VERB`, while `px-run` bounds the entire execution of
`suites/check-cluster.sh` by twice `PX_TIMEOUT_VERB`—a single combined budget covering the
`thresholds` hooks, the seven built-in subjects, the two steady-state checks, and every plug-in
check script together. If that aggregate budget expires, the remaining checks do not run and
`px-run` records a `cluster.checks ERROR` verdict row with `exit 124`.

**Thresholds.** All baseline thresholds reside in `suites/thresholds.properties` as individual
`key=value` lines, and no check script embeds a hard-coded threshold literal. The `thresholds` hook
of an enabled plug-in may print `key=value` lines that override existing keys or add new keys while
leaving all other keys unchanged. The run configuration can also override individual keys through
variables of the form `PX_THRESHOLD_<KEY>`—the threshold key converted to uppercase with each dot
replaced by an underscore, such as `PX_THRESHOLD_LOG_WARNINGS_MAX=5`. The merge order starts with
the base properties file, layers on values from a run-class file or configuration file, applies
plug-in threshold lines in the order of `PX_PLUGINS`, and finishes with explicit operator values
from the environment or `px-run -e`, where a later assignment for the same key wins. In this way, a
plug-in overrides the run class—matching the precedence of the configuration loader—and the operator
overrides all other sources. Any override name that matches no key defined in the base file or in an
enabled plug-in is rejected as a usage error so that a misspelled variable name never silently
leaves a threshold at its default value. The merged threshold set is written to
`thresholds.properties` in the bundle, and `PX_THRESHOLDS_FILE` points each check script to that
file. The helper `sx_threshold key` reads a single threshold value and exits with code 6 when the
key is absent, which the calling check records as `ERROR`.

| Key | Default | Subject |
|---|---|---|
| `wrapper.terminations.max` | 0 | verbs terminated by `px-run` at a time limit or by a signal |
| `restart.unplanned.max` | 0 | unplanned restarts, unexpected exits, and vanished instances |
| `oom.kills.max` | 0 | out-of-memory container kills |
| `oom.peak_pct.max` | 95 | peak memory usage of an instance as a percentage of its limit |
| `log.errors.max` | 0 | `ERROR` and `FATAL` lines in the daemon instance logs |
| `log.warnings.max` | 100 | `WARN` lines in the daemon instance logs |
| `gc.pause_ms.max` | 1000 | longest garbage-collection pause of any JVM instance, in milliseconds |
| `recovery.seconds.max` | 120 | recovery time (mttr) of each injected disruption, in seconds |
| `post_recovery.faults.max` | 0 | readiness or probe faults remaining after the final recovery |
| `steady.p99_ms.max` | 1000 | highest `query_latency.p99_ms` of any steady window, in ms |
| `steady.attainment_pct.min` | 90 | lowest `achieved_qps` of a window, as a % of `offered_rate` |

**The seven subjects.** Each built-in cluster subject writes one or more verdict rows with the
source `check:cluster`.

| Check | Evidence | Over the threshold | Class |
|---|---|---|---|
| `cluster.wrapper` | `verb` rows with timed_out set or an exit code above 128 | `FAIL` | none |
| `cluster.restart` | `diff` rows `unplanned` or `vanished`, plus `abort` rows | `ERROR` | count |
| `cluster.oom` | `oom`, `sb_oom_killed`, and `sample` peaks | kill `ERROR`, peak `FAIL` | count |
| `cluster.log` | `sb_logs` output since run start | errors `FAIL`, warnings `ADVISORY` | count |
| `cluster.gc` | the GC log of each JVM instance retrieved via `sb_copy_out` | `FAIL` | duration |
| `cluster.recovery` | `disrupt`, `action`, `recover` rows | `FAIL`, and `fail` or `missed` | none |
| `cluster.post_recovery` | readiness and bootstrap probe checks after recovery | `FAIL` | count |

Any subject lacking applicable evidence emits a `SKIP` verdict stating the reason, such as no
cluster, no disruption, or no GC log. The `cluster.recovery` subject emits `FAIL` for any
disruption whose state is `fail` (a failed fault injection) or `missed`, and it emits `SKIP` only
when no disruption entry is `ok`, `fail`, or `missed`, since entries that are `skip` or never fired
prove nothing. The log subject saves each daemon log in `logs/instance/`, and the GC subject saves
each GC log in `logs/gc/`. When a planned action covered an instance, the log subject still
preserves its log file but excludes its lines from the counts. It also excludes any line logged by
any instance during a disruption's recovery window—from the injection timestamp to the recovery
timestamp of the `recover` row, with 2 s of slack at each end—because an intentional fault
expectedly causes peer instances to log errors, such as a DataNode whose write pipeline from a
killed RegionServer breaks. Each line's timestamp is taken from its leading UTC date and time. Lines
logged after the recovery window are counted as usual, and `cluster.post_recovery` verifies any
faults that remain. Because the instances belonging to the `pherf` group in `cluster/services.tsv`
(the controller and agents of the Distributed Pherf tier) act as the load generator rather than
cluster daemons, the log subject preserves their logs without counting any of their lines and notes
them in the verdict detail as `load generator, kept and not counted`, while the restart, OOM, and
container checks continue to monitor them. Furthermore, the log subject excludes known benign server
log lines matching an entry in the allowlist `suites/log-allow.txt`, and the verdict detail reports
each matched entry, such as `1 allowed: hbase-dispatcher-delayqueue`. Each allowlist entry specifies
a unique `id`, the log `level`, the exact `logger` name as formatted by the container image layout
(the final two components of the class name immediately following the thread name), an awk regular
expression `message` matched against the text following `<logger>: `, a per-instance `max` count of
at least 1, an inclusive `release` version range for `hbase` or `hadoop`, and a `reason` with a
supporting reference. An entry permits at most `max` matching lines within the log of a single
instance; any subsequent matching lines are counted as ordinary log lines and noted in the detail as
`over the max of the allow list, counted: <id>`, because a benign message that repeats excessively
can indicate a genuine fault. An entry applies only when the active release recorded in
`cluster/topology.env` (`PX_DIST_HBASE_VERSION` or `PX_DIST_HADOOP_VERSION`) falls within its
declared version range, ensuring that the message is counted again on newer releases until a review
extends the range. The same message text logged under a different logger or severity level is still
counted. The allowlist is owned exclusively by the harness and cannot be extended by a plug-in. For
each instance, the restart subject reports its state change, its restart counts, its termination
reason and exit code, and its out-of-memory flag, matching the fields of the `anomaly` member of
the manifest.

The oom subject evaluates peak memory utilization for each role against its limit across two sample
sources combined: the `pre` and `post` snapshots, and the continuous instance samples collected by
the health monitor during the run window, which `px-run` copies into the execution record as
`sample` rows of the phase `monitor`. When no monitor samples are present, the verdict detail notes
that the peak was derived from the boundary snapshots alone. The GC subject parses pause times from
the unified JVM garbage-collection log written by the container image to
`$PX_LOG_DIR/gc-<role>-<index>.log`. Because every instance shares the same `PX_LOG_DIR` defined by
the `ENV` directive of the common image, `cluster.gc` queries that path once from `client` 0 via
`sb_exec` before copying `$PX_LOG_DIR/gc-<role>-<index>.log` from each JVM instance with
`sb_copy_out`; accordingly, every backend must maintain a single `PX_LOG_DIR` across all service
roles. The post-recovery subject executes only after a disruption: it requires every configured
instance outside an intentionally stopped group to be running and ready, and it executes
`px-bootstrap-probe`—passing `--yarn` when the `yarn` service group is active—inside `client` 0 via
`sb_exec`.

No verification check ever starts a JVM inside a daemon instance. Launching an auxiliary JVM next
to a tightly sized daemon—for example, running `hbase hbck` inside the master container—can push the
container over its memory limit. Any check that requires a client program executes it inside
`client` 0.

**The steady-state checks.** The script `suites/checks/steady.sh` writes two verdict rows with the
source `check:steady`, both carrying the evidence class `duration` so that `px-report` demotes a
`FAIL` verdict on either check to `ADVISORY` unless the run's time evidence is authoritative. The
evaluated evidence is the final aggregate metric record for each key within each `steady@` anchor.

| Check | Evidence | Over the threshold |
|---|---|---|
| `steady.query_latency.p99_ms` | `query_latency.p99_ms` per window | above `steady.p99_ms.max` |
| `steady.attainment` | `achieved_qps` as % of `offered_rate` | below `steady.attainment_pct.min` |

Each check emits `SKIP` with an explanatory reason when no steady window records its required keys,
as occurs when a driver declares those metrics unsupported. Consequently, a bundle whose only
threshold failures are these two steady-state rows still passes in the validation run class and on a
contaminated host.

By contrast, `cluster.recovery` carries the class `none` and is never demoted. Its two-minute upper
bound acts as a structural liveness gate for the A4, A7, and K3 proofs across both run classes and
on any host, providing a wide margin above the measured 40 to 70 seconds rather than acting as a
timing measurement.

**Plug-in checks.** Custom plug-in check scripts are defined in `docs/PLUGINS.md`, section "Check
scripts". A check script emits verdict rows with `sx_verdict_row` and may record aggregate metrics
with `pb_metric`. Any check script that fails to execute and returns a non-zero exit status causes
the runner to append an `ERROR` row under the key `plugin.<id>.<name>`.

The runner `suites/check-cluster.sh` exits with code 0 when every check script ran, 1 when any
check script could not run, 2 on a usage error—such as an unset `PX_RUN_DIR` or a threshold line
that does not match `key=number`—and 6 when a configuration file passed by `px-run` is absent.
Because `px-run` forwards the `--config` files of the run to the script, the child process resolves
each threshold value with its proper configuration origin.
