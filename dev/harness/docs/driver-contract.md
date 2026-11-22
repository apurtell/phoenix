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

# The driver contract

A driver executes a single workload against a cluster and records the resulting data in the run
bundle. Drivers are always invoked by `px-run` rather than being executed directly. Every driver,
whether built-in or provided by a plug-in, adheres to the contract specified in this document. The
target bundle structure is defined in `docs/bundle-format.md`, and the mechanism for contributing a
driver through a plug-in is specified in `docs/PLUGINS.md`.

The contract version is governed by `PB_CONTRACT_VERSION` in `lib/bundle.sh`, which is currently 1.
Any modification to a verb, an exit code, an environment variable name, or a key within the
describe document increments this version number.

## The executable

Each driver is implemented as a single executable file: `drivers/<name>/driver.sh` for a built-in
driver, or the executable path designated by `PX_PLUGIN_DRIVER` for a plug-in driver. The driver
name corresponds to its directory name or its plug-in id, and a test selects its driver in a suite
definition via `driver: <name>`. The built-in drivers are `psql`, `pherf`, and `java-main`; Round
H3 adds `pherf-distributed`.

The command line accepts the lifecycle verb followed by the test arguments:

```text
driver.sh describe
driver.sh <verb> [argument...]      verb: validate prepare run restore report
```

The positional arguments consist of the `args:` and `arg:` values configured for the test, passed
in the exact sequence provided by `px-suite`. Every verb other than `describe` receives this
identical argument list. Any relative path appearing in an argument is resolved relative to the
directory containing `PX_SUITE_FILE`. Encountering an unknown verb, a missing verb, or an invalid
argument causes the driver to terminate with exit 2.

A driver is written as a bash script that sources three core libraries in sequence:
`lib/common.sh`, `lib/bundle.sh`, and, for verbs that interact with the cluster,
`lib/backend/contract.sh`, followed by a call to `sb_load "$PX_BACKEND"`. Shared helper functions
used across the built-in drivers reside in `drivers/lib/driver.sh` and are detailed in "The driver
kit" below. A driver implementation may source `drivers/lib/driver.sh` by itself, as that library
automatically loads `lib/common.sh` and `lib/bundle.sh`, while `dv_cluster` handles loading the
substrate contract.

## The six verbs

`px-run` invokes the lifecycle verbs in the sequence shown in the table below. Each verb executes
as an independent OS process and retains no in-memory state for subsequent verbs; instead, all
persistent state across verbs is stored on disk within `$PX_DRIVER_DIR`.

| Verb | Cluster | Purpose | Writes, besides `$PX_DRIVER_DIR` |
|---|---|---|---|
| `describe` | no | Declares phases, metric classes, and unsupported items. | Document on stdout. |
| `validate` | no | Validates the workload definition. | Verdict rows. |
| `prepare` | yes | Creates the schema, loads data, and warms up. | Stage rows and `tables.tsv`. |
| `run` | yes | Executes the workload and its assertions. | Stage rows and metric records. |
| `restore` | yes | Reverses the state changes introduced by `prepare`. | Nothing. |
| `report` | no | Converts what `run` left into records. | Metric records and verdict rows. |

Only `prepare` is permitted to create or alter a table, and only `run` is permitted to record a
`steady@` window.

Five invariants govern the execution of every verb.

1. **Order and guarantees.** `px-run` always invokes `describe` first; if `describe` fails, no
   further verbs execute. If `validate` fails, `prepare` and `run` are skipped. If `prepare` fails,
   `run` is skipped. Conversely, `restore` runs whenever `prepare` has executed, even following a
   verb failure, a timeout, or an abort. The only exception is an anomaly abort: because the
   cluster state can no longer be trusted, `restore` is never invoked after one, and `px-run` emits
   a `SKIP` verdict row for `driver.restore` citing the reason `anomaly abort`. Meanwhile, `report`
   executes whenever `validate` has run. Consequently, `restore` must gracefully
   handle a partially completed `prepare` phase as well as a cluster that has lost an instance, and
   `report` must handle a bundle to which `run` never wrote.
2. **Idempotence.** Both `prepare` and `restore` must produce the exact same outcome when executed
   twice in succession.
3. **No cluster for three verbs.** The `describe`, `validate`, and `report` verbs must not open
   cluster connections or invoke substrate functions. Both `describe` and `validate` execute prior
   to cluster health checks, and `report` must be able to operate offline against a copied bundle
   on a separate host.
4. **Measured traffic only in `run`.** No activity performed during `prepare` may overlap with or
   fall inside a `steady@` window.
5. **Output stays in the bundle.** Every verb must confine its filesystem writes to paths beneath
   `$PX_RUN_DIR`, either by calling the record writers in `lib/bundle.sh` or by writing directly
   into `$PX_DRIVER_DIR`. Any file retrieved from the cluster by the driver must be placed inside
   `$PX_DRIVER_DIR`, and no files may be written to the source working tree.

### describe

The `describe` verb emits a single JSON document to stdout and writes no other output to that
stream; any diagnostic messages are directed to stderr. It operates without an active run, a live
cluster, or any environment variables beyond `PX_HOME` and `PX_DRIVER_NAME`, allowing dry runs of
both `px-suite` and `px-run` to invoke it directly. If a driver's capabilities vary depending on
the test configuration, it declares the union of all phases, metrics, and artifacts it can
potentially produce. `px-run` persists the emitted document at `drivers/<name>/describe.json` and
rejects it with exit 2 if `pb_describe_load` detects a schema or contract violation.

```json
{
  "contract": 1,
  "driver": "psql",
  "summary": "Runs SQL scripts and records the rows of each statement and the time of each file.",
  "phases": [
    { "anchor": "setup@schema", "verb": "prepare" },
    { "anchor": "steady@script", "verb": "run" }
  ],
  "metrics": {
    "count": ["psql.statements", "psql.rows", "query_errors"],
    "share": [],
    "duration": ["psql.file_duration_ms"]
  },
  "unsupported": {
    "checks": [
      { "key": "query_latency.p50_ms", "reason": "no latency per operation" },
      { "key": "driver.storage_scans", "reason": "request metrics are off" },
      { "key": "driver.rpc_calls", "reason": "request metrics are off" }
    ],
    "artifacts": [
      { "path": "oplog/", "reason": "no operation log" }
    ]
  },
  "artifacts": [
    { "path": "drivers/psql/statements.tsv" }
  ]
}
```

Every key listed below is mandatory unless explicitly marked as optional.

* `contract`, a number: must equal `PB_CONTRACT_VERSION`. `pb_describe_load` rejects any other
  value with an error message identifying both the expected and actual versions.
* `driver`, a string: the canonical name of the driver.
* `summary`, a string, optional: a single-line description intended for operators.
* `phases`, an array: an ordered list of phase objects in chronological sequence. Each object
  contains `anchor`, specifying the `<kind>@<name>` identifier recorded on stage rows for that
  phase, and `verb`, identifying the lifecycle verb that owns the phase (`validate`, `prepare`,
  `run`, or `restore`). At least one declared anchor must use the `steady@` prefix.
* `metrics`, an object containing three string arrays (`count`, `share`, and `duration`): the
  metric keys emitted by the driver, partitioned by metric class. Each key may belong to only one
  class, and individual arrays may be empty.
* `unsupported`, an object containing two arrays: `checks`, consisting of `{ "key", "reason" }`
  objects for each verdict check or steady-state metric that the driver cannot provide, and
  `artifacts`, consisting of `{ "path", "reason" }` objects for each optional bundle path that the
  driver never generates.
* `artifacts`, an array, optional: a list of `{ "path" }` objects enumerating the optional bundle
  paths that the driver may produce.

The harness preserves any unrecognized top-level or nested keys without error. Every artifact path
must be relative to the bundle root and remain confined within the bundle directory; a path ending
with `/` denotes a directory.

**The metric classes.** Every metric is assigned to exactly one of three classes, each carrying a
distinct level of evaluative authority.

| Class | Meaning | Authoritative |
|---|---|---|
| `count` | Whole units of work: statements, rows, operations, scans, calls, or bytes. | always |
| `share` | A ratio from 0 to 1 of two counts, such as a busiest-server share. | always |
| `duration` | A time-derived value: a latency, a wall-clock time, or a rate. | quiet runs |

Timing evidence is authoritative only during a measurement-class run executed on an uncontaminated
host. Under all other conditions, `px-report` demotes any failing `duration` verdict to `ADVISORY`.
Furthermore, every metric record embeds the active run class and host contamination state at the
time it is written, ensuring that downstream consumers can see those conditions even if they do not
independently apply the demotion rule. See `docs/bundle-format.md`, section "Verdict severity", for
the complete specification.

**Unsupported items.** For every entry declared in `unsupported.checks`, `px-run` emits a `SKIP`
verdict row carrying the check key, the metric class `none`, and the detail string
`unavailable: <reason>`. Consequently, any evaluation gate inspecting that key encounters an
explicitly documented `SKIP` rather than missing data. Similarly, each entry in
`unsupported.artifacts` is recorded in the manifest's artifact inventory with a status of
`unavailable`. Neither declaration ever causes a run to fail.

**The steady-state metrics.** For every `steady@` window, the run manifest reports
`query_latency.p50_ms`, `query_latency.p99_ms`, `query_errors`, `offered_rate`, and `achieved_qps`.
A driver must either supply each of these keys as an aggregate metric record bound to that steady
anchor (as described under `run`) or list the omitted key in `unsupported.checks`, in which case
the manifest records `null` for that field.

### validate

The `validate` verb verifies the integrity of the workload definition, confirming that all
referenced files exist, parse cleanly, and satisfy workload-specific constraints. It emits a
primary verdict row named `driver.workload_valid` along with an individual verdict row for each
violated rule. Rather than halting on the first defect, it reports all validation errors to stderr.
The verb exits with 0 if the definition is valid and 1 if validation fails. Any underlying tool
that uses a distinct exit status for a rejected workload, such as the Pherf validator, must map
that status to 1; a driver must never exit with a code outside the range 0 to 3.

### prepare

The `prepare` verb applies the database schema, configures table properties, loads initial data,
and performs any necessary warm-up operations. For every table it creates or alters, it queries
`SYSTEM.CATALOG` to read back the effective `UPDATE_CACHE_FREQUENCY` value and records the table
via `pb_table schema table update_cache_frequency`. Because the run manifest populates its `tables`
member directly from these records, `UPDATE_CACHE_FREQUENCY` is derived from a single authoritative
producer. The verb may also bracket its work with stage rows, such as `pb_stage begin setup@schema`
and `pb_stage end setup@schema`.

### run

The `run` verb executes the measured workload together with its functional assertions. It brackets
the measured execution phase with a pair of stage rows—`pb_stage begin steady@<name>` and
`pb_stage end steady@<name>`—using the anchor declared by `describe`. The time interval bounded by
these two rows defines the steady window. The driver records steady-state metrics as aggregate
records associated with that anchor, ensuring that each record's time bounds fall within the steady
window:

```sh
window="$(pb_stage_window steady@script)" || exit "$?"
pb_metric query_errors count 0 errors steady@script "${window%%$PX_TAB*}" "${window#*$PX_TAB}"
```

An implementation of `run` must tolerate fault disruptions injected by `px-run`: connection drops
occurring during a scheduled disruption are expected events that the driver must either retry or
count. If `px-run` terminates `run` due to a timeout or an abort, the harness automatically closes
any open stage anchors so that the stage log remains structurally valid. Whenever `run` exits with
a non-zero code, it must emit an explanatory verdict row so that the downstream verdict merge never
has to infer the failure reason from the process exit code alone.

A driver that records HDR histograms writes each raw histogram log as a `.hlog` file beneath
`drivers/<name>/` and declares each path in `artifacts` so that the run manifest includes it in the
artifact inventory. Because this recording is intrinsic to the driver rather than an external
collector, `px-run` never disables it in either run class. The pherf driver copies the histogram
logs generated by Pherf into `drivers/pherf/results_HDR/`.

### restore

The `restore` verb drops or truncates the database objects created during `prepare`, reading the
state saved in `$PX_DRIVER_DIR` to determine what `prepare` provisioned. If cleanup fails, the
harness records a `FAIL` verdict row under `driver.restore`, because any residual table leaking
into a subsequent test compromises the isolation of that test.

### report

The `report` verb operates strictly on the existing bundle contents, reading only the artifacts in
`$PX_DRIVER_DIR` and the stage file. It emits any aggregate metric records not already written by
`run`, along with the verdict rows for functional assertions and deterministic counts. Its exit
code reflects solely whether report generation itself succeeded: 0 indicates that the report was
produced, and 1 indicates a reporting failure. Accordingly, a run in which the workload itself
failed still concludes `report` with exit code 0, encoding the failure severity within the emitted
verdict rows so that `px-report` remains the single point of authority for the final run outcome.

## Exit codes

Every verb terminates with one of four exit codes, corresponding to codes 0 through 3 of the
standard harness vocabulary defined in the README under section "Exit codes".

| Code | Name | Meaning for a verb |
|---|---|---|
| 0 | ok | The verb completed its task; for `validate` and `run`, all assertions also held. |
| 1 | failure | The workload, the workload definition, or the driver itself failed. |
| 2 | usage | The invocation is invalid, indicating a harness or suite defect. |
| 3 | anomaly | The underlying cluster, rather than the workload, is at fault. |

Exit code 1 (failure) indicates a rejected workload definition, a failed data load, a violated
assertion, an incomplete cleanup, or an ungenerated report. Exit code 2 (usage) indicates an
unrecognized verb, an invalid argument, a missing environment variable, or a describe document
rejected by `px-run`, and never signifies a product defect. Exit code 3 (anomaly) indicates
that the substrate cannot reach a client instance (`sb_exec` returning 125 to 127), that the
cluster refuses all connections outside a scheduled disruption window, or that a required cluster
role is absent.

`px-run` normalizes any other exit status—including termination by a signal—to code 1 while logging
the raw return value. Code 3 is also the abort code of `px-run` itself: if a cluster
instance unexpectedly restarts, exits, disappears, or exhausts its memory outside a planned fault
action, `px-run` immediately halts the active verb, records an `abort` stage row, and terminates
the run with code 3 regardless of the driver's return status. Both backends uniformly return code 3
for all such infrastructure faults, recording the specific root cause in the cluster-check verdict
and the manifest's `anomaly` field rather than encoding it in distinct exit codes.

For every verb that executes, `px-run` emits a verdict row named `driver.<verb>` with metric class
`none` and a severity mapped from its exit status: code 0 yields `PASS`, codes 2 and 3 yield
`ERROR`, and any other code yields `FAIL`. A verb terminated by `px-run` upon reaching its time
limit receives exit code 4 and is recorded as `FAIL`. If `describe` exits non-zero, `px-run` emits
a `driver.describe` verdict row with the severity corresponding to its exit status and terminates
with that same status: code 1 for `FAIL`, or code 4 when timed out at its bound. Only a describe
document rejected during schema validation by `pb_describe_load` produces an `ERROR` verdict with
exit 2.

The overall exit code of `px-run` defaults to the first non-zero verb exit code, modified by three
precedence rules. First, a cluster anomaly overrides code 0 or 1 with code 3. Second, exceeding a
time limit produces code 4. Third, when every executed verb exits with 0, `px-run` adopts the exit
code corresponding to the merged verdict computed by `px-report`: 0 for `PASS`, `SKIP`, and
`ADVISORY`; 1 for `FAIL` or an inconclusive run; and 3 for `ERROR`. Finally, if
`px-bundle validate` rejects the resulting bundle, a nominal 0 exit status is converted to 1.

## The environment

`px-run` exports the environment variables listed below to every verb except `describe`, and each
verb must treat them as read-only. In addition to these variables, `px-run` exports the run class,
timeout bounds, and all other resolved configuration parameters for the active run.

| Name | Value |
|---|---|
| `PX_HOME` | The absolute path to the `dev/harness` directory. |
| `PX_OUT` | The root directory for harness output. |
| `PX_RUN_ID` | The unique run identifier, formatted as `<YYYYMMDDTHHMMSSZ>-<4 characters>`. |
| `PX_RUN_DIR` | The run bundle directory, located at `$PX_OUT/runs/<run-id>`. |
| `PX_RUN_CLASS` | Either `validation` or `measurement`; always populated for an active run. |
| `PX_CONTAMINATION` | Host contamination causes, such as `quiet-host`; empty on a quiet host. |
| `PX_BACKEND` | The bound substrate backend identifier passed to `sb_load`. |
| `PX_SCOPE_ID` | The target cluster scope identifier, or empty when running without a cluster. |
| `PX_DRIVER_NAME` | The canonical name of the driver. |
| `PX_DRIVER_DIR` | The driver directory `$PX_RUN_DIR/drivers/<name>`, created by `px-run`. |
| `PX_VERB` | The lifecycle verb currently being executed. |
| `PX_RECORD_SOURCE` | The provenance tag `driver:<name>`, recorded by every bundle writer. |
| `PX_SUITE_NAME` | The enclosing suite name when invoked via `px-suite`, or empty otherwise. |
| `PX_SUITE_FILE` | The absolute path to that suite file, or empty otherwise. |
| `PX_TEST_ID` | The test identifier within the suite, or empty otherwise. |

When `describe` is invoked, it is guaranteed to receive `PX_HOME`, `PX_DRIVER_NAME`, and
`PX_VERB=describe`. Any verb that requires cluster access and discovers that `PX_SCOPE_ID` is empty
must terminate immediately with exit 2.

## Time limits

The non-workload verbs—`describe`, `validate`, `restore`, and `report`—are each governed
individually by the timeout bound `PX_TIMEOUT_VERB`, which defaults to 300 seconds. In contrast,
`prepare` and `run` share a single combined deadline corresponding to the overall test time limit
configured through `px-run --timeout`, which populates `PX_TIMEOUT_RUN`. Within the built-in
drivers, the workload JVM launched during `prepare` and `run` is bounded by `PX_TIMEOUT_RUN`,
whereas short catalog queries are bounded by `PX_TIMEOUT_DEFAULT`. When any verb exceeds its
allocated time limit, the harness delivers a TERM signal to the process and follows it ten seconds
later with KILL. Accordingly, every driver implementation must trap and handle TERM cleanly by
terminating all child processes and leaving the artifacts in `$PX_DRIVER_DIR` in a consistent,
readable state.

## Reaching the cluster

A driver interacts with the cluster exclusively by invoking three substrate contract functions
against `client` role instances: `sb_exec`, `sb_copy_in`, and `sb_copy_out` (`sb_load` only binds
the backend implementation locally without contacting the cluster). To ensure portability across
all supported substrates, a driver must never invoke any other `sb_*` function or reference
container runtimes or Kubernetes CLI tools directly. The harness self-test statically scans both
`drivers/` and all plug-in drivers for forbidden `sb_*` calls and tool names, while a runtime trap
in `lib/common.sh` intercepts any unauthorized substrate call and terminates the process with exit
2.

All client workloads execute inside the `client` instance at index 0, which is guaranteed to exist
in every deployment topology. Within the container image, the Phoenix client JARs and the one bson
JAR that they require reside in `$PX_PHOENIX_HOME/client`, rendered client configuration files
reside in `$PX_CONF_DIR`, and the JVM options for the client role are listed one per line in
`$PX_CONF_DIR/jvm.opts`. The cluster JDBC URL is defined by the `phoenix.jdbc.url` property inside
`$PX_CONF_DIR/phoenix-client.properties`; a driver must never construct a host or port on its own.

Drivers invoke Java main classes directly rather than calling wrapper scripts such as `bin/psql.py`
or `bin/pherf-standalone.py`, eliminating any dependency on Python inside the container image and
standardizing the class path as follows:

```sh
sb_exec "$PX_SCOPE_ID" client 0 "$timeout" -- bash -c '
  exec "$PX_JDK_ROOT/$PX_JDK/bin/java" $(cat "$PX_CONF_DIR/jvm.opts") \
    -cp "$PX_CONF_DIR:$PX_PHOENIX_HOME/client/*:$PX_HBASE_HOME/lib/client-facing-thirdparty/*" \
    "$@"' px-java org.apache.phoenix.util.PhoenixRuntime "$url" "$remote/script.sql"
```

Single quotes defer expansion of container environment variables to the shell executing inside the
client instance. Applying the role's JVM options assigns the client JVM the exact heap budget
configured for the client role, preserving deployment resource sizing. Client programs must never
be launched inside daemon instances, where spawning an additional JVM alongside a tightly sized
service daemon could push the container past its memory ceiling.

A driver stages input and output files through a dedicated remote working directory located at
`/tmp/px/<run-id>` inside the client instance. It provisions this directory via
`sb_exec ... -- mkdir -p`, uploads workload inputs using `sb_copy_in`, and retrieves generated
outputs into `$PX_DRIVER_DIR` using `sb_copy_out`. The `restore` verb removes the remote working
directory during cleanup.

`sb_exec` propagates the exit status of the remote program. For substrate calls managed by the
harness—specifically `dv_remote`, `dv_put`, `dv_get`, and the clear script—the driver translates
the return status according to the following mapping: 0 maps to 0; 2 maps to 2; 124 (indicating
that the call reached its time limit) maps to 1; 125, 126, 127, and 6 (indicating that the
substrate could not start the program or could not locate the target instance) map to 3; and every
other status maps to 1. Because the exit status of a Java main class reflects the outcome of the
workload itself, `dv_java` applies its own mapping: 0 maps to 0; 124 maps to 1; 125, 126, and 127
map to 3; and every other status, including 2 and 6, maps to 1. Furthermore, because `sb_exec`
forwards its standard input to the remote program, a driver that invokes it while iterating over
rows on standard input must redirect the call's input from `/dev/null`.

## The driver kit

The library `drivers/lib/driver.sh` provides shared helper functions used across the built-in
drivers, and plug-in drivers are free to source it as well. Every helper terminates the calling
process with exit code 2 if invoked improperly.

* `dv_main "$@"` validates the requested verb and dispatches execution to the driver's
  `verb_<verb>` function along with the remaining arguments. It disables the shell failure trap so
  that a non-zero return status from the verb function serves directly as the driver's exit code.
  It exits with 2 if the verb is unknown or the corresponding function is undefined.
* `dv_cluster` sources the substrate contract, binds `$PX_BACKEND`, and verifies that
  `PX_SCOPE_ID` is set, exiting with 2 if no cluster scope is present.
* `dv_remote` creates the remote working directory inside `client` instance 0 and prints its path
  to stdout.
* `dv_put LOCAL NAME` uploads a local file or directory into the remote working directory under the
  specified name.
* `dv_get NAME LOCAL` downloads a file or directory from the remote working directory into the
  local bundle at the specified path.
* `dv_java TIMEOUT [--cp ENTRY]... [--opt OPTION]... CLASS [ARG...]` launches a Java main class
  inside `client` instance 0 using the standard command structure shown above. Any extra class path
  entries (resolved relative to the remote working directory) are prepended to the class path, and
  any extra JVM options are appended after the role's default JVM options. Standard output streams
  through unmodified. Exit status 0 maps to 0, 124 maps to 1, 125 through 127 map to 3, and all
  other statuses map to 1. If the remote execution exceeds its timeout bound or the verb receives
  TERM, the helper terminates the Java process inside `client` instance 0 by sending TERM, waiting
  up to 5 seconds, and then sending KILL.
* `dv_jdbc_url` extracts and prints the JDBC URL from the client configuration, mapping a timeout
  status of 124 to 1 and returning 3 if no URL can be found.
* `dv_window CHECK ANCHOR WHAT` reads the closed time window for `ANCHOR` during `report` and
  populates `DV_START` and `DV_END`. If `run` did not produce `run.tsv` or if the stage window
  remains unclosed, it writes a `SKIP` verdict row for `CHECK` explaining the omission—appending
  `WHAT` after the prefix "run left no record, so"—and returns 1.
* `dv_tables_restore` drops every table in `tables.list` under `$PX_DRIVER_DIR` via `DROP TABLE IF
  EXISTS ... CASCADE` under `PX_TIMEOUT_VERB`, then removes the remote working directory. The first
  non-zero status encountered is preserved as the return code, and repeated invocations are
  idempotent no-ops.

## The built-in drivers

### psql

```text
[--prepare FILE]... FILE...
```

Executes one or more SQL script files passed as test arguments, in sequential order, using
`org.apache.phoenix.util.PhoenixRuntime`. Any script specified via `--prepare` executes during the
`prepare` verb inside the `setup@schema` phase. The driver parses the standard output emitted for
each statement—specifically matching the `N row(s) upserted`, `deleted`, and `selected` lines,
`no rows selected`, and the `Time: N sec(s)` timing line. From this output, it records per-statement
row counts, total statement counts, and per-file execution durations in `metrics/aggregate.tsv`: one
`psql.statement_rows` record under the anchor `statement@<file>.<statement>` for each statement,
one `psql.file_duration_ms` record under the anchor `script@<file>` for each file, and the rolled-up
totals `psql.statements`, `psql.rows`, and `psql.file_duration_ms` under the steady anchor
`steady@script`. In addition, `drivers/psql/statements.tsv` captures those same per-statement row
counts annotated with each statement's operation type. Because PhoenixRuntime aborts execution of a
script file at its first failing statement, `query_errors` counts the number of failed script files,
and any failed file generates a `FAIL` verdict row. Its steady anchor is `steady@script`. The
driver declares `query_latency.p50_ms`, `query_latency.p99_ms`, `offered_rate`, and `achieved_qps`
as unsupported because it neither measures per-operation latency nor follows a rate schedule, and it
declares the storage scan and RPC checks unsupported because it does not enable request-level
metrics.

### pherf

```text
--scenario FILE [--schema FILE]... [--properties FILE] [--scenario-name NAME] [--read-skew]
[-- PHERF_OPTION...]
```

Executes a Pherf workload scenario via `org.apache.phoenix.pherf.Pherf` after staging the test's
scenario and schema files into the remote working directory. Because Pherf resolves these files by
bare filename from the class path until round P1 updates that behavior, the driver prepends the
remote staging directory to the class path. It also redirects Pherf's result output directory into
the remote working directory rather than relying on Pherf's relative default path `RESULTS`. Its
steady anchor is `steady@pherf`, and the schemas of the Pherf output files consumed by the driver
are defined in `phoenix-pherf/LATENCY.md` and `phoenix-pherf/VERDICT.md`.

**The command line.** The `prepare` verb invokes Pherf with `-l` and `-label load`, whereas `run`
invokes it with `-q` and `-label run`. Assigning distinct labels keeps the artifacts of the two
verbs separate: because Pherf's pre-run gate executes before Pherf purges existing files for a
label, a run that terminates early cannot leave load-phase files behind under the run label. The
driver manages the label directly, and supplying `-label` in `PHERF_OPTION` causes the driver to
terminate with exit 2. The workload override options `-runDurationMs`, `-threadCount`, and
`-targetRate`—whether passed with the value as the next token or attached via `=`—are forwarded
only to `run`, since Pherf rejects them without `-q`. Every other option, including
`-allowUnknownTags`, is forwarded to both verbs because the pre-run gate executes in both.

**The staged properties.** The driver controls four keys in the staged `pherf.properties` file—the
result directory, the summary file, `pherf.verdict.noisyHost`, and `pherf.verdict.serverJmx`—and
strips any existing lines defining them from the test's properties file, including lines formatted
with leading whitespace or using a `:` or space separator. It appends these four keys at the end of
the staged file so that the driver-assigned values always take precedence. The `noisyHost` property
is set to `true` when `PX_CONTAMINATION` is non-empty, and `false` otherwise. Only `--read-skew`
populates `serverJmx`, listing `http://regionserver-<i>:<port>` for every RegionServer in the
deployment topology and resolving each port from `PX_PORT_REGIONSERVER_HTTP_<i>` according to the
same rule used by `px-trace-counters`. Without that flag, `serverJmx` remains empty, matching
Pherf's default of configuring no JMX source. Only a test designed to evaluate read skew enables
this option, pairing it with a measured window of at least 60 s, because the share check is never
demoted and a table with a single region across 3 RegionServers will fail it. A deployment topology
containing no RegionServer terminates with exit 3. All verdict threshold settings remain in the
test's properties file.

**The gate.** If Pherf's pre-run gate rejects the scenario files, Pherf terminates with exit code
65. The active verb then emits a `FAIL` verdict row under `pherf.gate` referencing
`logs/<verb>.log` and terminates with exit 1. Because `prepare` is the first lifecycle verb with
access to a cluster and a client JDK—neither of which is available during `validate`—it is the
first phase to execute the pre-run gate. Any other Pherf execution failure produces a `FAIL`
verdict row under `pherf.load` or `pherf.execution`.

**The copies.** Before launching Pherf, `run` pre-creates the `<results>_HDR` directory, and after
execution it copies the legacy result directory to `drivers/pherf/results/` and `<results>_HDR` to
`drivers/pherf/results_HDR/` without modification. If Pherf does not generate those outputs, the
corresponding directory remains empty. The full artifact layout is cataloged in
`docs/bundle-format.md`, section "The pherf driver files".

**The report.** The `report` verb operates exclusively on the local bundle copies on the host using
the JSON reader provided by `lib/bundle.sh`, emitting the following metric records and verdict
rows:

* All rows in `VERDICT_run.tsv` are appended unchanged to `verdicts.tsv` with the source set to
  `pherf`, even when the workload run itself failed. The file must contain exactly five rows, each
  comprising five tab-separated fields with a `pherf.health.*` check key, a recognized severity, a
  recognized evidence class, and the source `pherf`; otherwise, no rows are copied and the driver
  emits a `FAIL` verdict row under `pherf.verdict`. When the verdict file is absent, the check is
  recorded as `SKIP`.
* The latency summary `LATENCY_run.json` must declare the format `pherf-latency`, version 1, and
  the recorder configuration specified in LATENCY.md, and the run histogram log that it references
  must begin with the header matching that configuration. If any of these checks fails,
  `pherf.latency` is recorded as `FAIL` and no latency metric records are emitted; if the JSON
  report is absent, it is recorded as `SKIP`. The driver never decodes raw histograms directly,
  deriving all metric values from the JSON report.
* For each `steady` histogram key, the driver emits records bound to the anchor `op@<operation>`
  (with every character outside `[A-Za-z0-9_.-]` replaced by `_`): `pherf.operations` (count) and
  `pherf.latency.{min,max,mean,p50,p90,p99,p999}_us` (duration, in us). Entries recorded for a
  `warmup` key produce no metric records.
* For each open-loop query-set schedule, the driver emits records bound to the anchor
  `schedule@<scenario>.<querySet>`:
  `pherf.schedule.{scheduled,completed,late,dropped,aborted,max_queue_depth}` (count) and
  `pherf.schedule.max_drift_us` (duration).
* Under the steady anchor `steady@pherf`, the driver emits `pherf.operations` as the sum across all
  steady keys of the kinds `query`, `scan`, `delete`, and `operation`; the sums of the schedule
  counts together with the maximum `max_queue_depth` and `max_drift_us`; and
  `query_latency.p50_ms` and `query_latency.p99_ms`, each computed as the maximum value in ms
  across all steady keys of the kinds `query`, `scan`, `delete`, `response`, and `operation`, which
  is the exact key set evaluated by `pherf.health.tail_latency`.
* A single `achieved_qps` record is emitted: computed from `pherf.operations` divided across the
  steady window when the JSON latency report is valid, or derived from the aggregate CSV run count
  otherwise.

Every emitted metric window is confined within the steady window: a histogram key spans the
recording interval reported in the JSON summary, while a schedule spans its own start and stop
timestamps, with both intervals clamped to the steady window boundaries.

**The CSV.** When `RESULT_*_aggregate.csv` files are present—formatted with the
`AGGREGATE_PERFORMANCE` header defined in
`phoenix-pherf/src/main/java/org/apache/phoenix/pherf/result/file/Header.java`—the driver emits one
aggregate interval record per query group under `group@<group>` containing the run count, the
result row count, and the average, minimum, and maximum durations bounded by that group's execution
interval, along with the total run count under `steady@pherf`. This parsing path executes whenever
an aggregate CSV exists: it serves as the fallback when Pherf did not produce a JSON latency
report, and it is the sole producer of `pherf.result_rows`, the deterministic row count. When no
aggregate CSV is present, `pherf.results` is recorded as `SKIP` if a valid JSON latency report is
available (such as during a `-mt` workload), and as `FAIL` otherwise.

**The verdict keys.** Because `pherf.health.*` verdicts originate from Pherf while `steady.*` and
`cluster.*` verdicts originate from the harness check scripts, verdict keys never collide. The
harness check `steady.query_latency.p99_ms` evaluates the same worst-case steady key as
`pherf.health.tail_latency`, and both checks default to a threshold bound of 1000 ms, so they can
diverge only when an operator overrides one bound independently. Similarly, `px-report` demotes any
failed `duration` verdict produced during a validation or contaminated run, and the noisy-host
property instructs Pherf to apply the same demotion on a contaminated host, ensuring that the two
verdict sources remain aligned.

**Unsupported.** The driver declares `offered_rate` unsupported because Pherf evaluates open-loop
rate attainment directly under `pherf.health.attainment`; computed across the entire `steady@pherf`
window—which includes JVM startup, the pre-run gate, and any closed-loop query sets—the harness
formula for `steady.attainment` would underreport attainment and contradict Pherf's verdict. The
driver also declares `query_errors` unsupported because the Pherf report does not count failed
closed-loop operations, while failed open-loop operations are tracked under `aborted`. Finally,
`driver.storage_scans` and `driver.rpc_calls` are declared unsupported because the driver does not
enable request-level metrics.

### java-main

```text
[--cp FILE]... CLASS [ARG...]
```

Executes a single Java main class designated by the first test argument, forwarding all subsequent
arguments to that class. Any file or directory supplied via `--cp` is placed on the class path ahead
of the Phoenix client JARs. The driver passes the cluster JDBC URL to the class through the
`px.jdbc.url` system property with the `RequestMetric=true` connection attribute appended. When the
target class opens its JDBC connection using `System.getProperty("px.jdbc.url")`, that attribute
enables request-level metric collection, which Phoenix inspects via
`JDBCUtil.isCollectingRequestLevelMetricsEnabled`; a class that constructs its own JDBC URL will not
collect request metrics. The class may emit custom ad hoc metrics to stdout, formatted one per line
as follows:

```text
px-metric <key> <class> <value> <unit>
```

The driver records each emitted `px-metric` line as an aggregate metric record bound to
`steady@main` while preserving all other stdout lines as standard program output. Any non-zero exit
status from the Java class produces a `FAIL` verdict row. If the class emits no `px-metric` line
for `query_errors`, the `report` verb records `query_errors` as 0 when the class exited with 0, and
as 1 otherwise. Its steady anchor is `steady@main`. Aside from `query_errors`, the driver declares
all standard steady-state metrics unsupported because only the custom Java class knows what
measurements it produces.

## Dry runs

During a dry run, `px-run` or `px-suite` resolves each configured driver executable, invokes its
`describe` verb under the `PX_TIMEOUT_VERB` timeout bound, and validates the resulting JSON document
with `pb_describe_load`. A dry run executes no other lifecycle verbs, makes no substrate backend
calls, and creates no run bundle. If any driver fails to resolve or produces a describe document
that `pb_describe_load` rejects, the dry run terminates with exit 2.
