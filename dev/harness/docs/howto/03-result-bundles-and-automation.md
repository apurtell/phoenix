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

# How to read, validate, archive and automate on result bundles

## The question

How can you determine what an execution proved, and how can an automated script evaluate that
outcome without human intervention? Every `px-run` invocation produces a single result bundle
directory containing a manifest, an execution record, evaluation verdicts, collected metrics, and
service logs. This HOWTO walks through inspecting a result bundle, validating its integrity,
demonstrating the verdict merge logic using two deliberately triggered threshold breaches, archiving
a bundle for portability, and wrapping the harness in a shell script that branches on exit codes in
the manner of a CI job.

## Before you start

**Audience.** Developers who inspect the outcome of a test run, committers who review result
bundles, and engineers who author CI jobs or harness extensions.

**Prerequisites.** The container image built in H02 (`px-cluster build`), and a container engine
provisioned with at least 16 GiB of memory.

| Cost | Value |
|---|---|
| Wall time | Takes approximately 20 minutes to complete. |
| Docker memory | Requires 8704 MiB for the validation profile with 1 RegionServer. |
| Disk | Uses a few MiB of storage for each bundle. |
| Files written | Writes 6 bundles and 1 session in `$PX_OUT`; archive, copy, script in `$HOWTO`. |

Initialize every shell session for this guide with the following three environment setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h03"; mkdir -p "$HOWTO"
```

## Method

**Hypothesis.** Verdict aggregation enforces the authority rule defined in H01. Forcing a breach of
a duration threshold during a validation run produces an `ADVISORY` severity and `px-run` exits with
0, whereas forcing a breach of a count threshold in the same run produces a `FAIL` severity and
`px-run` exits with 1.

**Controls.** Every test execution shares a single running cluster that `px-run` leaves active and
runs the same workload defined by the smoke suite SQL scripts, altering only one threshold parameter
per run on the command line.

**Noise.** Environmental jitter is inconsequential because the demonstration does not rely on
fine-grained timing precision: every recorded GC pause exceeds the 1 ms threshold, and at least
one container instance utilizes more than 10% of its allocated memory limit.

**What to measure.** Observe the recorded severity of `cluster.gc` and `cluster.oom` in
`report/verdicts.tsv`, the aggregated outcome reported by `px-report`, and the process exit code
returned by `px-run`.

**What invalidates the result.** The demonstration is invalidated if `cluster.gc` reports `SKIP`
because no GC log was found, or if a lifecycle verb fails directly, in which case the exit code
reflects the verb failure rather than the verdict merge.

### The bundle

Each bundle resides at `$PX_OUT/runs/<run-id>`, and `../bundle-format.md` defines the exact schema
for every file path, column, and key. The primary files inspected during analysis are listed below:

| Path | What it tells you |
|---|---|
| `manifest.json` | Holds identity, class, result, versions, images, quiet host, anomaly, windows. |
| `run-record.tsv` | Logs `px-run` events: verbs, snapshots, quiet host, contamination, result. |
| `stages.tsv` | Records the windows: `verb@<verb>`, `steady@<name>`, `collector@<name>`, phases. |
| `verdicts.tsv` | Records the emitted verdict stream: `check severity class source detail`. |
| `report/verdicts.tsv` | Lists effective verdicts, with the emitted severity written last. |
| `report/run-summary.tsv` | Summarizes the result, severity counts, and contamination in one row. |
| `report/cluster-summary.tsv` | Gives one row per instance: image, states, restarts, change, OOM. |
| `metrics/aggregate.tsv` | Stores one value per source, anchor, and key, along with its classes. |
| `metrics/timeline.tsv` | Records time-series metric samples using the same column layout. |
| `thresholds.properties` | Records the resolved threshold limits enforced by the checks. |
| `cluster/topology.env` | Captures the component version set, sizing overrides, and topology. |
| `cluster/images.tsv` | Maps each cluster instance to its container image id. |
| `cluster/services.tsv` | Records the resource sizing and JDK configuration for each service. |
| `drivers/<name>/` | Holds the workload driver artifacts alongside its `describe.json`. |
| `logs/` | Holds `<verb>.log` for each verb, `instance/` daemon logs, and `gc/` GC logs. |

Each run identifier follows the format `<YYYYMMDDTHHMMSSZ>-<xxxx>`, combining a UTC timestamp with a
four-character base-36 suffix so that run identifiers sort chronologically by creation time. Tools
that inspect a bundle accept a directory path or a run ID, and when omitted default to the newest
finished bundle (the latest run directory containing `manifest.json`). Because `px-run` prints the
created bundle path directly, automation scripts capture that output instead of searching for it.

The `steady@<name>` stage anchor is the primary window of interest for performance and correctness
analysis. It marks the interval during which the cluster configuration remains constant, warmup has
completed, and the workload driver executes its measured operations. The steady-state summary
metrics recorded in the manifest (`query_latency.p50_ms`, `query_latency.p99_ms`, `query_errors`,
`offered_rate`, and `achieved_qps`) are all scoped to this window.

### The verdicts

Every verdict row is assigned a severity drawn from the ordered lattice:

```text
PASS  <  SKIP  <  ADVISORY  <  FAIL  <  ERROR
```

Each row also declares one evidence class: `count`, `share`, `duration`, or `none` (representing
functional checks). Only `FAIL` and `ERROR` cause a run to fail. The `ERROR` severity is reserved
for harness or infrastructure faults, such as an unplanned daemon restart, ensuring that cluster
instability remains clearly separated from product defects. The `px-report` utility merges verdict
rows across four stages: collect, demote (where any `FAIL` of class `duration` is downgraded to
`ADVISORY` unless the execution runs in the measurement class without any contamination row), join
(where the highest severity in the lattice prevails), and write. A run that produces no verdict rows
at all, or only `SKIP` rows, resolves to `INCONCLUSIVE`, which is a run-level result rather than a
row severity and causes the run to fail.

### The thresholds

Cluster health checks load their baseline limits from `dev/harness/suites/thresholds.properties`.
Threshold overrides are merged in the following precedence order, where later definitions supersede
earlier ones:

1. The baseline properties file.
2. Any `PX_THRESHOLD_<KEY>` assignment defined in a run-class profile or a `--config` file.
3. Definitions emitted by the `thresholds` hook of each active plug-in, evaluated in the order
   listed in `PX_PLUGINS`.
4. Any `PX_THRESHOLD_<KEY>` override supplied through the process environment or via `px-run -e`.

To form `<KEY>`, convert the property key to uppercase and replace every dot with `_`; for example,
`gc.pause_ms.max` maps to `PX_THRESHOLD_GC_PAUSE_MS_MAX`. Supplying an override name that does not
correspond to a known threshold key triggers an immediate error, preventing a misspelled variable
from silently leaving the default limit in effect.

| Key | Default | Check | Class |
|---|---|---|---|
| `wrapper.terminations.max` | 0 | `cluster.wrapper` | none |
| `restart.unplanned.max` | 0 | `cluster.restart` | count |
| `oom.kills.max`, `oom.peak_pct.max` | 0, 95 | `cluster.oom` | count |
| `log.errors.max`, `log.warnings.max` | 0, 100 | `cluster.log` | count |
| `gc.pause_ms.max` | 1000 | `cluster.gc` | duration |
| `recovery.seconds.max` | 120 | `cluster.recovery` | none |
| `post_recovery.faults.max` | 0 | `cluster.post_recovery` | count |
| `steady.p99_ms.max` | 1000 | `steady.query_latency.p99_ms` | duration |
| `steady.attainment_pct.min` | 90 | `steady.attainment` | duration |

### The exit codes

All harness commands share a unified exit code vocabulary. Automation scripts branch directly on the
exit code, while diagnostic context is recorded in the error message, the verdict rows, and the
bundle manifest. Whenever a command exits with a non-zero status, its final stderr line includes
`(exit N, name)`, making the exit status visible in logs without running `echo "exit $?"`.

| Code | Name | Meaning |
|---|---|---|
| 0 | ok | The requested operation completed as intended. |
| 1 | failure | The operation failed, or the container runtime did not respond. |
| 2 | usage | The command invocation is invalid, or a named mode is unrecognized. |
| 3 | anomaly | Run is untrusted: instance restart, exit, vanish, OOM; gate/host refusal; `ERROR`. |
| 4 | timeout | A bounded wait operation expired before completion. |
| 5 | unsupported | The active backend or host environment cannot perform this operation. |
| 6 | no such object | The named cluster, bundle, session, suite, test, image, or file is absent. |

Each command maps its operational outcomes onto this shared vocabulary as follows:

* `px-cluster`: Emits exit codes 0 through 6 as defined in the table above. The `up` subcommand
  returns 6 if the container image is absent, 3 if preflight validation rejects the topology or
  another cluster occupies the host, and 4 if service instances fail to become ready before the
  timeout. The `down` subcommand returns 0 even when no active cluster is recorded. The `exec`
  subcommand propagates the exit status of the executed command, returning 124 on timeout, 125 if
  execution fails to start, 126 if the target binary is not executable, and 127 if it is not found.
* `px-run`: Returns the first non-zero lifecycle verb exit code, normalizing any verb code outside 0
  to 3 to 1. Exit code 3 indicates an unplanned cluster state change, including one detected while a
  verb is running. Exit code 4 indicates that a verb exceeded its time bound or that the host
  remained busy when the run required a quiet host. When every lifecycle verb returns 0, `px-run`
  exits with the code of the merged verdict: 0 for `PASS`, `SKIP`, and `ADVISORY`; 1 for `FAIL` and
  `INCONCLUSIVE`; and 3 for `ERROR`. If bundle validation fails, an exit code of 0 is converted to
  1. Its final stderr line reports the outcome and code: `run ID ends at RESULT (exit N, name)`.
* `px-suite`: Exits with 0 for pass, skip, or advisory outcomes; 1 when a test fails or the session
  is `INCONCLUSIVE`; 2 for invalid usage; 3 for a harness or cluster fault; and 6 when the specified
  suite or test does not exist.
* `px-report`: Exits with 0 for `PASS`, `SKIP`, and `ADVISORY`; 1 for `FAIL` and `INCONCLUSIVE`; 2
  for invalid command usage, an unreadable record, or a bundle path outside `PX_OUT`; 3 for `ERROR`;
  and 6 when the target bundle or a required record file is missing.
* `px-bundle`: Exits with 0 on success; 1 when a validation check fails or the manifest cannot be
  written; 2 for invalid usage or a bundle path located outside `PX_OUT`; and 6 when the specified
  bundle is missing or no metric record matches the query.
* Pherf: Exits with 0 on success; 1 for an invalid invocation or general error; 65 when the
  validator or pre-run gate rejects the scenario files; 66 when `-analyze` rejects replicates from
  different run classes; and 67 when `-analyze` writes a report containing a `FAIL` row.

The pherf driver translates a Pherf exit code of 65 into a `FAIL` verdict row named `pherf.gate` and
returns driver exit code 1. No exit code outside the range 0 to 3 ever leaves a driver.

### The JSON reports

Pherf emits three structured JSON reports, each stamped with explicit `format` and `version` fields
so that automation scripts can parse them directly without fragile text scraping. Consumers ignore
any unrecognized field to maintain forward compatibility.

| Report | Written by | Format |
|---|---|---|
| `LATENCY_<label>.json` | Every Pherf run executed with a scenario file | `pherf-latency` |
| `VALIDATION_<label>.json` | `-validate` | `pherf-validation` |
| `ANALYSIS_<label>.json` | `-analyze` | `pherf-analysis` |

Pherf writes the latency report to `<results>_HDR`, which the pherf driver copies into
`drivers/pherf/results_HDR/` within the result bundle. The other two reports are written directly to
the Pherf results directory.

HOWTO H04 reads the latency report through the harness, while P02 and P06 read the validation and
analysis reports.

## Steps

### Read a bundle

1. Provision the validation-class cluster:

   ```sh
   px-cluster --class validation up
   ```

   Because the cluster state record written by `up` persists the run class, subsequent commands
   operating against this deployment do not need to repeat `--class validation`.

2. Define the shared driver arguments used across the test runs in this guide. Specify absolute
   paths to the smoke suite SQL scripts, because workload drivers resolve relative file paths only
   with respect to a suite definition file:

   ```sh
   SQL="$PWD/dev/harness/suites/sql"
   SMOKE=(--prepare "$SQL/smoke-schema.sql" "$SQL/smoke-query.sql")
   ```

3. Execute a baseline test run through the psql workload driver:

   ```sh
   RUN="$(px-run psql -- "${SMOKE[@]}")"
   echo "$RUN"
   ```

   ```text
   ... INFO  px-run: run 20261127T091833Z-8k16 ends at SKIP (exit 0, ok)
   .../phoenix-harness/runs/20261127T091833Z-8k16
   ```

   The `px-run` utility prints the newly created bundle path to stdout immediately upon creation and
   streams operational logs to stderr. The cluster remains active until step 16 tears it down.

   The HBase master may log a single `ERROR` line while `restore` is dropping the table:
   `RemoteProcedureDispatcher: DelayQueue for RemoteProcedureDispatcher is not empty when timed
   waiting elapsed`. This is an internal HBase timing message, and the region closes within a
   second. Because the allowlist `suites/log-allow.txt` includes this message, `cluster.log`
   excludes it from the error count and appends `1 allowed: hbase-dispatcher-delayqueue` to its
   `detail` column. Any other `ERROR` line causes `cluster.log` to emit `FAIL` because
   `log.errors.max` defaults to 0, with the `detail` column identifying the instance and the first
   matching line.

4. Examine the primary lifecycle event rows in the run execution record:

   ```sh
   awk -F '\t' '$1 ~ /^(run|verb|contamination|result)$/' "$RUN/run-record.tsv"
   ```

   ```text
   run	20261127T091833Z-8k16	validation	compose	px-default	.../drivers/psql/driver.sh
   verb	describe	0	...	0
   verb	validate	0	...	0
   contamination	quiet-host	the quiet-host check is off, so a failed time verdict is ADVISORY
   verb	prepare	0	...	0
   verb	run	0	...	0
   verb	restore	0	...	0
   verb	report	0	...	0
   result	0	ok	no
   ```

   Each `verb` row records the verb name, its exit code, the elapsed duration in milliseconds, and
   the timeout flag. Every validation run records the `quiet-host` contamination row because the
   validation class disables the quiet-host gate. The final field of the `result` row indicates
   whether an unplanned cluster state change occurred.

5. Examine the stage window boundaries recorded during the run:

   ```sh
   cut -f2-4 "$RUN/stages.tsv"
   ```

   ```text
   begin	verb@describe	px-run
   end	verb@describe	px-run
   ...
   begin	setup@schema	driver:psql
   end	setup@schema	driver:psql
   ...
   begin	steady@script	driver:psql
   end	steady@script	driver:psql
   ...
   ```

   The displayed columns contain the boundary event, the stage anchor, and the emitting source. The
   `pb_stage_pairs` helper pairs each `begin` entry with the first subsequent `end` entry sharing
   the same anchor.

6. Examine the effective merged verdict rows produced by the report phase:

   ```sh
   cut -f1-4 "$RUN/report/verdicts.tsv"
   ```

   ```text
   check	severity	class	source
   driver.describe	PASS	none	px-run
   query_latency.p50_ms	SKIP	none	px-run
   ...
   driver.workload_valid	PASS	none	driver:psql
   ...
   query_errors	PASS	count	driver:psql
   ...
   cluster.restart	PASS	count	check:cluster
   cluster.oom	PASS	count	check:cluster
   cluster.log	PASS	count	check:cluster
   cluster.gc	PASS	duration	check:cluster
   ...
   ```

   Each check not supported by the driver appears as an explained `SKIP` row. Because the psql
   driver does not record per-operation latency, it declares the latency keys unsupported.

7. Examine the deterministic count metrics captured for the steady-state stage window:

   ```sh
   awk -F '\t' '$4 == "steady@script"' "$RUN/metrics/aggregate.tsv" | cut -f5-10
   ```

   ```text
   psql.statements	count	5	statements	validation	quiet-host
   psql.rows	count	5	rows	validation	quiet-host
   psql.file_duration_ms	duration	2084	ms	validation	quiet-host
   query_errors	count	0	errors	validation	quiet-host
   ```

   The extracted columns show the metric key, the evidence class, the value, the unit, the run
   class, and the contamination markers. Because every metric record carries those final two fields,
   downstream consumers cannot mistake this validation duration for an authoritative baseline.

8. Examine the recorded component version set and container image digests:

   ```sh
   grep -E '^PX_(RUN_CLASS|IMAGE_TAG|PHOENIX_HBASE_PROFILE)=' "$RUN/cluster/topology.env"
   grep -E '^PX_(DIST_HBASE|DIST_HADOOP|ZOOKEEPER)_VERSION=' "$RUN/cluster/topology.env"
   cut -f1,2,4 "$RUN/cluster/images.tsv" | head -n 3
   ```

   ```text
   PX_RUN_CLASS=validation
   PX_IMAGE_TAG=px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6
   PX_PHOENIX_HBASE_PROFILE=2.6
   PX_DIST_HBASE_VERSION=2.6.7
   PX_DIST_HADOOP_VERSION=3.4.3
   PX_ZOOKEEPER_VERSION=3.8.6
   role	index	image_id
   client	0	sha256:...
   datanode	0	sha256:...
   ```

9. Validate the bundle layout, which operates offline without a workload driver or active cluster:

   ```sh
   px-bundle validate "$RUN" | awk -F '\t' '$2 == "fail" || $1 == "result"'
   ```

   ```text
   result	ok	0	.../phoenix-harness/runs/20261127T091833Z-8k16
   ```

   No check row emits a status of `fail`, and the final `result` row reports `ok`. If any check
   fails—such as when a mandatory artifact file is missing—the validator reports the failing check
   in its output stream, concludes with `result fail 1 BUNDLE`, and returns exit code 1. A missing
   bundle path returns exit code 6, whereas a path outside `PX_OUT` returns exit code 2. Omitting
   the BUNDLE argument causes `px-bundle validate` to inspect the newest completed bundle.

10. Re-execute the verdict merge pass and inspect the trailing summary line:

    ```sh
    px-report "$RUN" | tail -n 1
    ```

    ```text
    result	SKIP	0	.../phoenix-harness/runs/20261127T091833Z-8k16
    ```

    The overall result is `SKIP` rather than `PASS` because the lattice join retains the highest
    observed severity, and the explained `SKIP` rows from step 6 outrank `PASS`. Every count and
    functional row evaluated to `PASS`, so the run succeeds and `px-report` exits with 0. Because
    `px-report` regenerates the three files under `report/` directly from `verdicts.tsv`, it
    produces the exact same result when run against a copy of the bundle on another host.

### Force a breach of each kind

11. Execute the same workload with a 1 ms GC pause limit, exercising the `cluster.gc` check of class
    `duration`:

    ```sh
    RUN_T="$(px-run psql -e PX_THRESHOLD_GC_PAUSE_MS_MAX=1 -- "${SMOKE[@]}")"
    grep '^cluster.gc' "$RUN_T/report/verdicts.tsv"
    grep '^gc.pause_ms.max' "$RUN_T/thresholds.properties"
    ```

    ```text
    ... INFO  px-run: run ... ends at ADVISORY (exit 0, ok)
    cluster.gc	ADVISORY	duration	check:cluster	the longest pause is ... ms in ...;
    gc.pause_ms.max=1	FAIL
    gc.pause_ms.max=1
    ```

    The check emitted `FAIL`, which is preserved in the final column. The verdict merge demoted that
    row to `ADVISORY` because a validation run does not provide authoritative timing evidence, so
    the run still passes.

12. Execute the same workload with a 10% peak memory limit, exercising the `cluster.oom` check of
    class `count`:

    ```sh
    RUN_C="$(px-run psql -e PX_THRESHOLD_OOM_PEAK_PCT_MAX=10 -- "${SMOKE[@]}")"
    grep '^cluster.oom' "$RUN_C/report/verdicts.tsv"
    ```

    ```text
    ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
    cluster.oom	FAIL	count	check:cluster	... peaked at ...% of its limit; peaks: ...;
    oom.peak_pct.max=10	FAIL
    ```

    Because count evidence is authoritative across every run class, the merge retains `FAIL` and
    `px-run` exits with 1.

13. Execute the test while supplying a deliberately misspelled threshold override variable:

    ```sh
    RUN_E="$(px-run psql -e PX_THRESHOLD_GC_PAUSE_MAX=1 -- "${SMOKE[@]}")"
    grep -E '^cluster\.(thresholds|checks)' "$RUN_E/report/verdicts.tsv" | cut -f1-2
    ```

    ```text
    ... ERROR px-run: cluster.checks: ERROR suites/check-cluster.sh ended with exit 2, output in
    logs/check-cluster.log
    ... WARN  px-run: the bundle does not validate, exit 1, rows in logs/px-bundle.log
    ... WARN  px-run: bundle check path: logs/instance/ is absent, required by a run with an
    instance after the run
    ... WARN  px-run: bundle check result: 1	.../phoenix-harness/runs/...
    ... INFO  px-run: run ... ends at ERROR (exit 3, anomaly)
    cluster.thresholds	ERROR
    cluster.checks	ERROR
    ```

    No check executes against a threshold set containing an unknown key. The run resolves to
    `ERROR`, the severity class reserved for harness faults, and `px-run` exits with 3.

### Archive and reproduce a bundle

14. Archive the initial result bundle into a compressed tarball:

    ```sh
    ID="${RUN##*/}"
    tar -C "$PX_OUT/runs" -czf "$HOWTO/$ID.tar.gz" "$ID"
    ```

15. Unpack the archive into a separate staging directory and validate the restored bundle:

    ```sh
    mkdir -p "$HOWTO/restore"
    tar -C "$HOWTO/restore" -xzf "$HOWTO/$ID.tar.gz"
    px-bundle validate "$HOWTO/restore/$ID" | tail -n 1
    px-report "$HOWTO/restore/$ID" | tail -n 1
    ```

    ```text
    result	ok	0	.../phoenix-harness/howto/h03/restore/20261127T091833Z-8k16
    result	SKIP	0	.../phoenix-harness/howto/h03/restore/20261127T091833Z-8k16
    ```

    A result bundle is completely self-contained. Because the validator verifies that `run.id`
    matches the bundle directory name, preserve the directory name whenever you move or archive a
    bundle. Because `px-report` and `px-bundle` reject any bundle path outside `PX_OUT` with exit 2,
    always unpack archived bundles beneath `PX_OUT`, as demonstrated here.

    To reproduce this execution, apply the version set from step 8, build the image from the same
    source tree revision (recorded in the `tree` row of `$PX_OUT/build/phoenix/build.tsv`), and pass
    the same test arguments. The harness builds every image locally from source and pinned
    downloads; it never pulls a harness image from a remote registry, and a test run never pushes
    one.

16. Tear down the running cluster so the remaining steps can manage their own cluster lifecycle:

    ```sh
    px-cluster down
    ```

### Automate on the exit codes

17. Preview the filtered test execution plan in dry-run mode; these selection flags behave
    identically during a live suite execution:

    ```sh
    px-suite --dry-run --only schema,pherf functional | cut -f1,2
    px-suite --dry-run --from recover functional | cut -f1,2
    px-suite --dry-run --skip recover functional | cut -f1,2
    ```

    ```text
    1	schema
    2	pherf
    1	recover
    2	pherf
    1	schema
    2	client
    3	pherf
    ```

    The `--from` and `--to` flags slice the ordered test plan inclusively, whereas `--only` retains
    solely the named tests and `--skip` removes them. If a test's prerequisite dependency fails or
    is skipped during the run, that test is marked `SKIP` and its output row explains the reason. If
    a dependency is omitted by the filter selection, such as `schema` under `--from recover`, the
    harness emits a warning only. Passing `--continue-on-failure` executes the remainder of the plan
    after a test failure and reports the worst severity encountered.

18. Enable strict configuration checking so that any unrecognized variable name fails closed:

    ```sh
    px-cluster --strict -e PX_RS_CUONT=3 config >/dev/null
    px-suite --strict -e PX_RS_CUONT=3 --dry-run smoke >/dev/null
    ```

    ```text
    ... ERROR px-cluster: undeclared name 'PX_RS_CUONT' from cli. Add it to lib/varnames.txt.
    ... ERROR px-cluster: config ends (exit 2, usage)
    ... ERROR px-suite: undeclared name 'PX_RS_CUONT' from cli. Add it to lib/varnames.txt.
    ... ERROR px-suite: px-suite ends (exit 2, usage)
    ```

    Without strict mode enabled, an unrecognized variable name logs a debug note while the default
    setting remains active. Always enforce strict mode in automated pipelines: every harness command
    accepts `--strict`, and exporting `PX_STRICT_CONFIG=1` in the environment has the same effect.

19. Create a CI wrapper script that branches on harness exit codes and guarantees automatic cluster
    cleanup on exit:

    ```sh
    cat >"$HOWTO/ci-smoke.sh" <<'EOF'
    #!/usr/bin/env bash
    # Smoke-tests the build of this tree. Run it from the repository root.
    # Argument: the file for the result rows. Exit: the px-suite code, or the
    # code of the bring-up.
    set -u
    export PATH="$PWD/dev/harness/bin:$PATH"
    results=${1:?name a file for the result rows}
    # The session leaves the cluster up. px-cluster down is idempotent, so the
    # trap tears down whatever the bring-up left, also after an interrupt.
    trap 'px-cluster down' EXIT
    px-cluster --strict --class validation up >/dev/null
    rc=$?
    case "$rc" in
    0) ;;
    6) echo "bring-up: the image is missing (px-cluster build)" >&2; exit "$rc" ;;
    3) echo "bring-up: the host cannot hold the cluster, a port is in use, or another" \
         "cluster holds the host" >&2
       exit "$rc" ;;
    *) echo "bring-up: failed with exit $rc" >&2; exit "$rc" ;;
    esac
    px-suite --strict smoke >"$results"
    rc=$?
    case "$rc" in
    0) echo "smoke: PASS, SKIP or ADVISORY" ;;
    1) echo "smoke: a test failed, or nothing was checked (INCONCLUSIVE)" >&2 ;;
    3) echo "smoke: an anomaly: the evidence cannot be trusted, not a product failure" >&2 ;;
    *) echo "smoke: the suite or its options are wrong, exit $rc" >&2 ;;
    esac
    exit "$rc"
    EOF
    ```

20. Run the CI wrapper script and inspect the captured suite result rows:

    ```sh
    bash "$HOWTO/ci-smoke.sh" "$HOWTO/ci-results.tsv"
    cat "$HOWTO/ci-results.tsv"
    ```

    ```text
    ...
    smoke: PASS, SKIP or ADVISORY
    ...
    ... INFO  px-cluster: cluster default is down
    1	sql	SKIP	0	62911	the verdicts of this test reach SKIP	.../runs/...
    2	client	SKIP	0	42071	the verdicts of this test reach SKIP	.../runs/...
    result	SKIP	0	.../phoenix-harness/sessions/...
    ```

    The suite writes tab-separated result rows to stdout and diagnostic messages to stderr, allowing
    a CI job to capture each stream separately. A job that requires individual check verdicts per
    test can read `report/verdicts.tsv` within the bundle directory listed in the seventh column of
    each test row, while the final `result` row provides the session directory path.

## Read the result

Step 11 exits with status 0 while step 12 exits with status 1 on the same cluster and workload. That
contrast demonstrates the authority rule in practice: a duration breach during a validation run
serves as an advisory notice to an operator, whereas a count breach fails the run on any host. Step
13 verifies that a threshold configuration error emits `ERROR` instead of falling back to defaults.
In automated pipelines, treat exit code 1 as a product or workload failure, exit code 3 as an
anomaly (indicating untrusted evidence or host environment state) that warrants a rerun or operator
triage, and exit codes 2 or 6 as invocation errors in the CI job definition itself.

## Reproduce it

Preserve the tarball archive generated in step 14. It packages the full component version set
(`cluster/topology.env`), the container image digests (`cluster/images.tsv`), the active threshold
limits (`thresholds.properties`), and the evaluation results (`manifest.json` and `report/`). Record
the `tree` row of the build record alongside the archive.

## Clean up

1. Tear down any remaining cluster deployment. Because the exit trap in the wrapper script already
   stopped the cluster, this call logs that no active cluster is recorded and exits with status 0:

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: no cluster default is recorded
   ```

## See also

* [01-measurement-protocol.md](01-measurement-protocol.md): Explains the evidence authority rule
  and the bundle review checklist.
* [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md): Introduces the suite session
  directory layout.
* [09-fault-injection-and-recovery.md](09-fault-injection-and-recovery.md): Details cluster
  anomaly detection and recovery verification checks.
* [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md): Demonstrates using
  replicate bundles as input to `-analyze`.
* [13-extending-the-harness.md](13-extending-the-harness.md): Covers adding custom checks,
  thresholds, and workload drivers through a plug-in.
* [../bundle-format.md](../bundle-format.md): Specifies every file path, column schema, and
  manifest key in a result bundle.
* [../driver-contract.md](../driver-contract.md): Defines the driver lifecycle verb exit codes.
* [../../README.md](../../README.md): Summarizes the harness exit code vocabulary and verdict
  severity lattice.
* [../../../../phoenix-pherf/LATENCY.md](../../../../phoenix-pherf/LATENCY.md),
  [../../../../phoenix-pherf/ANALYSIS.md](../../../../phoenix-pherf/ANALYSIS.md): Document the
  Pherf latency and analysis JSON report schemas.
* [Pherf HOWTO 02, scenario authoring and validation][p02]: Describes the scenario validation JSON
  report.

[p02]: ../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md
