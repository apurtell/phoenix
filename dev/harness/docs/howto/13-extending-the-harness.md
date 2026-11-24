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

# How to extend the harness with plug-ins, checks and drivers

## The question

How can you introduce a new workload, cluster check, threshold, data set, or suite into the harness
without modifying its core code, and how can you verify that your extension is well-formed before
running it against a live cluster? The harness architecture provides three primary extension points:
a plug-in, a driver, and a suite. This HOWTO examines the built-in example plug-in, constructs a
compact custom plug-in complete with a driver, a check, a threshold, and a suite, validates that
plug-in offline, and executes it once against a live cluster. Finally, it reviews the verification
gates that any modification to the harness itself must satisfy.

## Before you start

**Audience.** This guide is designed for harness contributors as well as engineering teams
developing custom cluster checks or workloads.

**Prerequisites.** You need the repository source tree and `bash`, along with `shellcheck` 0.11 for
the static lint gate. All steps prior to "Run the plug-in on a cluster" execute entirely offline;
that final live section requires the container image built in H02.

| Cost | Value |
|---|---|
| Wall time | around 15 minutes offline, self-test included; around 5 minutes for the live phase |
| Docker memory | none required offline; 8704 MiB for the live phase |
| Disk | less than 1 MiB, plus one result bundle |
| Files written | the `$HOWTO/plugins/howto/` plug-in, `$HOWTO/bad.suite`, and one result bundle |

**What this HOWTO does not run.** Because the example plug-in modifies the rendered configuration of
every service instance, executing it live requires a container image packaged with that plug-in. The
example plug-in steps are verified against the harness implementation (`docs/PLUGINS.md`,
`lib/plugin.sh`, `px-cluster build`) rather than run live for this HOWTO. Round A7
of the harness development cycle executed them: every resulting bundle contained 8 of the 9 hook
markers, and `px-report` merged the plug-in verdict. The live verification pass for these HOWTOs
similarly skipped steps 15 and 16, which execute the custom plug-in on a live cluster; both steps
are verified against the code, and their output excerpts are illustrative.

Initialize every shell session for this guide with the following three lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h13"; mkdir -p "$HOWTO"
```

## Method

**Hypothesis.** An extension that conforms to the harness contracts will pass plug-in discovery,
describe validation, and dry-run checks, emit verdict rows that the report merger processes
identically to built-in rows, and keep the harness self-test suite passing.

**Controls.** Plug-in discovery does not activate any extension automatically: `PX_PLUGINS` defaults
to empty, ensuring that the presence of a plug-in directory does not alter any run unless that
plug-in is explicitly selected.

**Noise.** Because a plug-in can override memory sizing, JVM flags, and evaluation thresholds, it
can alter performance measurements. Always enable the exact same set of plug-ins across both sides
of any comparative study. Any plug-in that overrides a `PX_<KEY>_MEM_MIB`, `HEAP_MIB`, or
`DIRECT_MIB` setting from the standard measurement table marks a measurement run with
`contamination sizing` (H01), and the preflight budget check aggregates its memory allocations just
like any other limit.

**What to measure.** Observe the exit codes returned by the dry runs, the execution records in
`plugins/<id>/hooks.tsv`, and the plug-in verdict entries recorded in `report/verdicts.tsv`.

**What invalidates the result.** If a check script exits with a non-zero status, the harness records
an `ERROR` row because a check that fails to complete leaves a verification gate unguarded. If a
threshold line does not match the `key=number` format, the harness aborts check execution and no
checks run at all.

### The plug-in

A plug-in is packaged as a single directory whose directory name serves as its identifier. It
contains a manifest named `plugin.env` along with the supporting files referenced by that manifest.
The full specification is defined in `../PLUGINS.md`.

| Key of `plugin.env` | Required | Value |
|---|---|---|
| `PX_PLUGIN_ID` | yes | identifier `^[a-z][a-z0-9-]{0,31}$`, matching the directory name |
| `PX_PLUGIN_CONTRACT` | yes | `1` |
| `PX_PLUGIN_DRIVER` | no | relative path to a six-verb workload driver |
| `PX_PLUGIN_HOOKS` | no | registered hooks; the key may repeat and each line appends |
| `PX_<KEY>_HEAP_MIB`, `_MEM_MIB`, `_DIRECT_MIB` | no | role memory sizing (`ZK` ... `SETUP`) |
| `PX_<KEY>_JVM_OPTS` | no | role JVM options; any memory size option is rejected |

During initialization, the harness discovers every subdirectory under `dev/harness/plugins` that
contains a `plugin.env` file, followed by each directory listed in `PX_PLUGIN_DIRS` (specified as a
colon-separated list of individual plug-in directories). It then activates the plug-in identifiers
listed in `PX_PLUGINS` (space-separated) in the exact order declared. An invalid manifest, a
duplicate identifier shared by two plug-ins, an identifier that collides with a built-in driver, or
an identifier in `PX_PLUGINS` that was not found during discovery each cause the command to abort
with exit code 2.

The nine lifecycle hooks execute in the following order during a session:

| Hook | Called by | When | On failure |
|---|---|---|---|
| `suites` | `px-suite` | when resolving the suite argument, including in a dry run | exit 2 |
| `image-check` | `compose.gen.sh` | on each render, run first with the image tag | render exit 2 |
| `conf-overlay` | `compose.gen.sh` | on each render pass | render exit 2 |
| `jvm-opts` | `compose.gen.sh` | on each render pass | render exit 2 |
| `fetch-data` | `px-run` | before `prepare` runs | `FAIL`, no prepare |
| `pre-workload` | `px-run` | after `prepare` and before `run` | `FAIL`, no run |
| `post-workload` | `px-run` | after `run` and before `restore` | `FAIL` |
| `thresholds` | `suites/check-cluster.sh` | after reading the base file | `ERROR`, no check |
| `collect` | `px-run` | after checks finish and before verdicts are merged | `ADVISORY` only |

Every hook must restrict its file output exclusively to `$PX_HOOK_OUT`, and the harness logs one
row per invocation in `plugins/<id>/hooks.tsv`. Like a workload driver, a hook interacts with the
cluster solely through the substrate contract. Custom check scripts placed under `checks/` execute
after all built-in checks complete, recording one `check` row each in `hooks.tsv`.

### The example plug-in

The reference implementation in `dev/harness/plugins/example` registers all nine lifecycle hooks and
demonstrates every manifest key. Each hook writes a `<hook>.marker` file into `$PX_HOOK_OUT` and
performs one concrete action:

* `conf-overlay` injects the `px.example.instance` property into `hbase-site.xml` for every
  service instance.
* `jvm-opts` appends a custom JVM option to the client role.
* `fetch-data` copies `data/example.tsv` into `client-0`, where the two workload hooks verify its
  presence.
* `thresholds` increases the `log.warnings.max` threshold to 200.
* The `checks/marker` check script verifies the marker count, while the driver counts the staged
  data rows.

A completed run bundle captures 8 of the 9 markers. Run-time hooks write directly to
`plugins/example/` inside the bundle, whereas render-time hooks write into the deployment directory
that `px-run` subsequently archives under `cluster/plugins/example/`. Because the `suites` hook
operates at the session level rather than within an individual run, the `suites` marker is stored
under `plugins/example/` in the session directory.

Because the example plug-in modifies container instances directly, the container image itself must
contain the plug-in assets. Running `px-cluster build` copies every enabled plug-in into
`/opt/px/plugins` within the image, guaranteeing that the built image embeds the exact set of
plug-ins enabled at build time. The sequence below builds a dedicated image tag packaging the
example plug-in, starts a validation cluster from that image, runs the plug-in suite, and tears the
cluster down. These commands are verified against the source code and were not run live for this
HOWTO:

```sh
EX=(-e PX_PLUGINS=example -e PX_IMAGE_TAG=px-harness:with-example)
px-cluster "${EX[@]}" build
px-cluster --class validation "${EX[@]}" up
px-suite example
px-cluster down
```

Because `px-cluster build` recompiles Phoenix from a clean source tree prior to assembling the
container image, its build duration matches the initial build in H02. Using a distinct image tag
keeps this image separate from the default harness image, and each generated bundle manifest
captures both the image tag and its resolved image identifier. Because the persisted cluster record
retains both settings, `px-suite` does not need to repeat either of them.

### The driver

A workload driver executes a single workload and records its outputs in the run bundle. It is
implemented as a single executable supporting six lifecycle verbs, as specified in
`../driver-contract.md`.

| Verb | Cluster | Purpose |
|---|---|---|
| `describe` | no | emits the describe document: phases, metric classes, and unsupported items |
| `validate` | no | validates the workload definition offline |
| `prepare` | yes | creates the schema, loads initial data, and performs warm-up |
| `run` | yes | executes the workload inside a single `steady@<name>` window |
| `restore` | yes | reverses `prepare`; runs whenever `prepare` was executed |
| `report` | no | converts `run` output into structured records; works on a bundle copy |

Each verb returns exit code 0 on success, 1 when the workload execution or its definition fails, 2
on usage errors or harness defects, or 3 when the cluster itself is at fault. A driver interacts
with the cluster exclusively by invoking `sb_exec`, `sb_copy_in`, and `sb_copy_out` against
`client-0`. The shared helper library `dev/harness/drivers/lib/driver.sh` provides the standard
driver toolkit:

* `dv_main` parses and dispatches the six lifecycle verbs.
* `dv_describe` formats and emits the driver describe document.
* `dv_cluster` binds the active substrate backend and exits with code 2 if no run scope is set.
* `dv_java` launches a Java main class inside `client-0`.
* `dv_window` retrieves the bounds of a closed stage window for use during `report`.

The harness ships with four built-in drivers: `psql`, `pherf`, `java-main`, and
`pherf-distributed`. Both Pherf drivers share the parsing helpers defined in
`dev/harness/drivers/lib/pherf.sh`: `pherf_verdict_copy` validates and appends the rows of a Pherf
verdict file to `verdicts.tsv`, `pherf_latency` records a Pherf latency report, and
`pherf_profile_names` extracts the workload profiles referenced by a scenario. Any custom driver
that consumes Pherf outputs should source this shared library rather than duplicating its parsers.
Unlike the standalone driver, `pherf-distributed` launches no local JVM; instead, it drives the
REST API of the Distributed Pherf controller through `lib/fleet.sh` by invoking `sb_exec` inside
`pherf-controller` 0, representing the sole architectural exception to the `client-0` execution
rule described above. See
[How to run Pherf on a fleet through the harness](14-fleet-through-the-harness.md) for details.

When you need to execute a custom Java class without writing a new driver, the built-in `java-main`
driver accepts `[--cp FILE]... CLASS [ARG...]`. The target class retrieves the cluster JDBC URL
from the `px.jdbc.url` system property, which automatically enables request metrics, and reports
each measurement by writing a single line to stdout:

```text
px-metric <key> <class> <value> <unit>
```

For example, emitting `px-metric howto.rows count 6 rows` causes the driver to record that metric
under the `steady@main` window. Compile your class against the Phoenix client JAR and pass its
output directory via `--cp`. This HOWTO does not compile a Java class because the repository policy
prohibits storing Java source files under `dev/`.

### The suite

A suite file consists of declarative `key: value` directives. The normative grammar is documented
in the header comments of `dev/harness/bin/px-suite`.

| Directive | Where | Meaning |
|---|---|---|
| `suite:`, `description:` | header | suite name and a one-line summary for the operator |
| `class:`, `timeout:`, `requires:` | header or test | run class, limit in seconds, role groups |
| `test: <id>` | opens a test | test id `^[A-Za-z0-9][A-Za-z0-9_-]*$`, unique in the file |
| `name:` | test | human-readable display name |
| `driver:` | test, required | workload driver assigned to the test |
| `args:` | test, repeatable | arguments split on whitespace without glob expansion |
| `arg:` | test, repeatable | a single literal argument that may contain spaces |
| `needs:` | test, repeatable | prerequisite tests that must execute first |
| `disrupt:` | test, repeatable | one fault disruption using `px-run --disrupt` syntax (H09) |

Declaring `requires: yarn` verifies available host memory, starts the YARN role group prior to the
test, and stops that group once the test concludes. Any relative path passed in a test argument is
resolved relative to the directory containing the suite file. The parser rejects any unrecognized
directive key and reports its line number, ensuring that a misspelled directive never causes a test
requirement to be silently ignored.

## Steps

### Inspect the example plug-in

1. Examine the configuration parameters supplied by the example plug-in:

   ```sh
   px-cluster --class validation -e PX_PLUGINS=example config | grep 'plugin:'
   ```

   ```text
   PX_CLIENT_JVM_OPTS	-Dpx.example=1	plugin:example
   PX_SETUP_HEAP_MIB	256	plugin:example
   ```

   In the configuration precedence chain, the plug-in layer is evaluated after `local.env` and
   before the run-class defaults, allowing a plug-in to override class settings while ensuring
   that explicit operator settings in `local.env` or on the command line continue to take
   precedence.

2. Confirm that specifying an unknown plug-in identifier is rejected immediately:

   ```sh
   px-cluster -e PX_PLUGINS=nosuch config >/dev/null
   ```

   ```text
   ... ERROR px-cluster: PX_PLUGINS names the plug-in nosuch, which discovery did not find in
   .../dev/harness/plugins or PX_PLUGIN_DIRS
   ... ERROR px-cluster: the plug-ins of PX_PLUGINS do not load
   ... ERROR px-cluster: config ends (exit 2, usage)
   ```

3. Resolve the plug-in suite through its `suites` hook and confirm its workload driver binding:

   ```sh
   px-suite example -e PX_PLUGINS=example --dry-run
   ```

   ```text
   1	rows	Count the staged rows with the example driver	driver	example	validation		300
   ```

4. Validate the plug-in driver's describe metadata offline without an active cluster:

   ```sh
   px-run example -e PX_PLUGINS=example --dry-run | grep -E 'driver_name|describe|plugins'
   ```

   ```text
   plan	driver	.../dev/harness/plugins/example/driver.sh
   plan	driver_name	example
   plan	describe	accepted, 1 phases
   plan	verbs	describe validate prepare run restore report
   plan	plugins	example
   ```

   During a dry run, `px-run` invokes only the `describe` verb, validates the resulting metadata
   document, bypasses all backend calls, and writes no result bundle. If the driver cannot be
   resolved or its describe document fails validation, the command terminates with exit code 2.

### Write a plug-in

Next, construct a custom plug-in named `howto` comprising a workload driver that executes a single
command inside `client-0`, a `thresholds` hook that introduces a custom threshold key, a cluster
check script that evaluates that threshold, and a suite file.

5. Create the directory structure for the custom plug-in:

   ```sh
   P="$HOWTO/plugins/howto"; mkdir -p "$P/hooks" "$P/checks" "$P/suites"
   ```

6. Write the plug-in manifest file:

   ```sh
   cat >"$P/plugin.env" <<'EOF'
   # The howto plug-in: a driver, one threshold, one check and one suite.
   PX_PLUGIN_ID=howto
   PX_PLUGIN_CONTRACT=1
   PX_PLUGIN_DRIVER=driver.sh
   PX_PLUGIN_HOOKS=thresholds suites
   EOF
   ```

7. Write the workload driver script, which loads the shared driver toolkit, establishes a single
   steady-state measurement window, and records `query_errors` within that window:

   ```sh
   cat >"$P/driver.sh" <<'EOF'
   #!/usr/bin/env bash
   # A six-verb driver: run runs one command in client 0 inside steady@probe.
   : "${PX_HOME:?px-run exports PX_HOME}"
   # shellcheck source=/dev/null
   . "$PX_HOME/drivers/lib/driver.sh"
   set -euo pipefail
   px_trap_enable
   ANCHOR=steady@probe
   NONE="the probe issues no query"

   verb_describe() {
     dv_describe "${PX_DRIVER_NAME:-howto}" "Runs one command in client 0." \
       "$ANCHOR|run" "query_errors" "" "" \
       "query_latency.p50_ms|$NONE
   query_latency.p99_ms|$NONE
   offered_rate|$NONE
   achieved_qps|$NONE" \
       "oplog/|the probe has no operation log"
   }

   verb_validate() {
     [ $# -eq 0 ] || px_die "$PX_EXIT_USAGE" "the howto driver takes no argument, got: $*"
     sx_verdict driver.workload_valid PASS "the probe has no workload file"
   }

   verb_prepare() { dv_cluster; }

   verb_run() {
     local status=0 window
     dv_cluster
     pb_stage begin "$ANCHOR"
     sb_exec "$PX_SCOPE_ID" client 0 "$PX_TIMEOUT_VERB" -- true </dev/null || status=$?
     pb_stage end "$ANCHOR"
     window="$(pb_stage_window "$ANCHOR")"
     pb_metric query_errors count "$((status != 0))" errors "$ANCHOR" \
       "${window%%"$PX_TAB"*}" "${window#*"$PX_TAB"}"
     if [ "$status" != 0 ]; then
       sx_verdict driver.run FAIL "the command in client 0 ended with $status"
       return "$(dv_code "$status")"
     fi
   }

   verb_restore() { :; }

   verb_report() { :; }

   dv_main "$@"
   EOF
   chmod +x "$P/driver.sh"
   ```

   The `describe` helper accepts the stage anchor and verb for each phase, the emitted count,
   share, and duration metric keys, and the lists of unsupported checks and artifacts. For every
   declared unsupported check, `px-run` records an explicit `SKIP` verdict row carrying the
   stated rationale, ensuring that downstream automated gates encounter a documented explanation
   rather than an unexplained gap.

8. Write the `thresholds` hook script so that it registers `howto.lost.max` with a default
   value of 0:

   ```sh
   cat >"$P/hooks/thresholds" <<'EOF'
   #!/usr/bin/env bash
   # Adds the threshold of the howto check: no instance may be lost in a run.
   set -euo pipefail
   printf 'howto.lost.max=0\n'
   EOF
   chmod +x "$P/hooks/thresholds"
   ```

   Every threshold key must conform to `^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$` and therefore cannot
   contain hyphens. Operators can still override this plug-in threshold at run time using
   `-e PX_THRESHOLD_HOWTO_LOST_MAX=1`.

9. Write the cluster check script to compare the pre-run and post-run instance snapshots, record
   the measured instance loss as a bundle metric, and emit a `count`-class verdict row:

   ```sh
   cat >"$P/checks/lost" <<'EOF'
   #!/usr/bin/env bash
   # Counts the instances that the pre-run snapshot holds and the post-run one does not.
   set -euo pipefail
   : "${PX_RUN_DIR:?}" "${PX_HOME:?}"
   # shellcheck source=/dev/null
   . "$PX_HOME/lib/bundle.sh"
   max="$(sx_threshold howto.lost.max)"
   rows() { awk -F '\t' -v p="$1" '$1 == "instance" && $2 == p' "$PX_RUN_DIR/run-record.tsv" |
     awk 'END { print NR }'; }
   lost=$(($(rows pre) - $(rows post)))
   pb_metric plugin.howto.lost count "$lost" instances
   if [ "$lost" -le "$max" ]; then
     sx_verdict_row plugin.howto.lost PASS count "$lost instances lost; howto.lost.max=$max"
   else
     sx_verdict_row plugin.howto.lost FAIL count "$lost instances lost; howto.lost.max=$max"
   fi
   EOF
   chmod +x "$P/checks/lost"
   ```

   The exit code of a check script reports whether the check executed to completion rather than
   whether the check condition passed: exit code 0 means evaluation completed normally. When a
   referenced threshold key is missing, `sx_threshold` exits with code 6, aborting the script so
   that the harness records a `plugin.howto.lost ERROR` verdict row.

10. Create the suite definition file along with the `suites` hook that publishes its relative path:

    ```sh
    cat >"$P/suites/howto.suite" <<'EOF'
    suite: howto
    description: The probe of the howto plug-in
    class: validation
    timeout: 300

    test: probe
      name: Run one command in client 0
      driver: howto
    EOF
    cat >"$P/hooks/suites" <<'EOF'
    #!/usr/bin/env bash
    # Lists the suites of the howto plug-in, relative to the plug-in directory.
    set -euo pipefail
    printf 'suites/howto.suite\n'
    EOF
    chmod +x "$P/hooks/suites"
    ```

### Check the plug-in offline

11. Run static shell analysis across every script in the plug-in:

    ```sh
    shellcheck -x "$P/driver.sh" "$P/hooks/thresholds" "$P/hooks/suites" "$P/checks/lost"
    ```

    When all scripts pass static analysis, the command produces no output.

12. Store the plug-in search path and activation list in a single shell array so that subsequent
    commands pass identical plug-in arguments:

    ```sh
    PLUG=(-e PX_PLUGIN_DIRS="$P" -e PX_PLUGINS=howto)
    ```

13. Verify suite resolution and driver describe metadata offline in dry-run mode:

    ```sh
    px-suite howto "${PLUG[@]}" --dry-run
    px-run howto "${PLUG[@]}" --dry-run | grep -E 'driver_name|describe'
    ```

    ```text
    1	probe	Run one command in client 0	driver	howto	validation		300
    plan	driver_name	howto
    plan	describe	accepted, 1 phases
    plan	verbs	describe validate prepare run restore report
    ```

14. Write a suite file with an invalid directive key and verify that the suite parser immediately
    rejects it:

    ```sh
    printf 'suite: bad\ntimout: 60\n' >"$HOWTO/bad.suite"
    px-suite --dry-run "$HOWTO/bad.suite"
    ```

    ```text
    ... ERROR px-suite: .../howto/h13/bad.suite line 2: unknown directive 'timout'
    ... ERROR px-suite: px-suite ends (exit 2, usage)
    ```

### Run the plug-in on a cluster

15. Bring up the validation cluster with the plug-in configuration enabled. Because the `howto`
    plug-in defines no render-time hooks, the standard harness container image can be used
    directly. The persisted cluster record retains both the run class and the plug-in settings, so
    subsequent commands against this cluster do not need to repeat either option:

    ```sh
    px-cluster --class validation "${PLUG[@]}" up
    ```

16. Run the probe driver through `px-run`. Because the run leaves the cluster active, step 17
    tears it down afterward:

    ```sh
    RUN="$(px-run howto)"
    grep -E '^(driver\.run|query_errors|plugin\.howto)' "$RUN/report/verdicts.tsv" | cut -f1-4
    grep '^howto' "$RUN/thresholds.properties"
    cut -f2,3 "$RUN/plugins/howto/hooks.tsv"
    ```

    ```text
    driver.run	PASS	none	px-run
    plugin.howto.lost	PASS	count	plugin:howto
    howto.lost.max=0
    thresholds	0
    check	0
    ```

    The `query_errors` metric record is written to `metrics/aggregate.tsv` under the
    `steady@probe` stage anchor. The selected columns from `hooks.tsv` display each invoked hook
    alongside its exit status; the `suites` hook does not appear because `px-run` was invoked
    directly rather than through a suite.

17. Tear down the validation cluster, which also removes its cluster record and plug-in settings:

    ```sh
    px-cluster down
    ```

    ```text
    ... INFO  px-cluster: cluster default is down
    ```

### Pass the contributor gates

Every change under `dev/harness` must satisfy the following verification gates prior to review.
All of these checks run offline without a cluster.

18. Run the offline harness self-test suite:

    ```sh
    dev/harness/test/run-tests.sh | tail -n 1
    ```

    ```text
    # 1243 assertions, all passed
    ```

    The self-test requires no container runtime, network access, or Maven build. Among other
    invariants, it verifies that no component outside the substrate backend invokes a container
    runtime directly, that workload drivers interact with the substrate solely through `sb_exec`,
    `sb_copy_in`, `sb_copy_out`, and `sb_load`, that every shell function includes required
    shelldoc annotations, and that no harness command writes files inside the repository working
    tree.

19. Run the static linter across all harness shell scripts:

    ```sh
    git ls-files -co --exclude-standard dev/harness |
      xargs awk 'FNR == 1 && /^#!.*sh/ { print FILENAME }' | xargs shellcheck -x
    ```

    A compliant source tree produces no output. This pipeline inspects every file under
    `dev/harness` that begins with a shell shebang line, including untracked files not yet staged
    in git. Any external plug-in residing outside `dev/harness` must be linted separately, as shown
    in step 11.

20. Check the line-length limit, the ban on Java source files, and the absence of hard-coded
    user home paths:

    ```sh
    find dev/harness -type f -exec awk 'length > 100 { print FILENAME ":" FNR }' {} +
    find dev -name '*.java'
    grep -rnE '/(Users|home)/' dev/harness
    ```

    On a compliant source tree, each command produces no output.

Additional requirements for harness changes include:

* **Shelldoc.** Every shell function must carry a `## @description` annotation and a `## @return`
  comment spanning one to three lines, where `@return` enumerates every possible exit code. Each
  `sb_*` substrate contract function must additionally include one `@param` line per parameter.
* **Licence header.** Every newly added file must include the standard ASF license header.
* **The registry.** Every new `PX_*` variable must be registered in `dev/harness/lib/varnames.txt`
  within the same change that introduces it so that `--strict` mode recognizes the variable.
* **No Java under `dev/`.** No Maven module compiles Java sources under `dev/`, yet Apache RAT
  still audits the directory.
* **The Maven gates.** Run `mvn -N spotless:check` and `mvn -N apache-rat:check` from the
  repository root.
* **A plug-in-only change** must not modify any files under `bin/`, `lib/`, or `suites/`.

## Read the result

An extension is verified when all dry runs exit with code 0, plug-in verdict rows appear in
`report/verdicts.tsv` tagged with the `plugin:<id>` source, `hooks.tsv` records an exit status of
0 for every executed hook and check, and all contributor gates pass.
A check verdict of class `count` or `none` remains authoritative on any host environment. By
contrast, a verdict row of class `duration` is automatically demoted to `ADVISORY` on uncalibrated
hosts just like any built-in duration metric, preventing a plug-in from making timing assertions
authoritative on a noisy host.

## Reproduce it

Archive the plug-in directory alongside the result bundle or commit it to version control. Within
the bundle, the `plugins` context entry in `run-record.tsv` records the exact list of enabled
plug-ins, while `thresholds.properties` captures the effective merged thresholds in force during
the run.

## Clean up

1. Tear down any cluster that remains active, such as after an interrupted run. When no cluster is
   recorded, `down` still succeeds with exit code 0:

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: no cluster default is recorded
   ```

2. Delete the temporary working directory once you are finished by running `rm -rf "$HOWTO"`. The
   generated result bundle under `$PX_OUT/runs/` is preserved.

## See also

* [../PLUGINS.md](../PLUGINS.md) defines the formal plug-in contract and manifest specification.
* [../driver-contract.md](../driver-contract.md) specifies the six lifecycle verbs, the describe
  document schema, and the shared driver toolkit.
* [../bundle-format.md](../bundle-format.md) documents the cluster health checks and threshold
  merging rules.
* [../backends.md](../backends.md) defines the substrate backend contract used by workload drivers
  and lifecycle hooks.
* [03-result-bundles-and-automation.md](03-result-bundles-and-automation.md) explains verdict
  evaluation and command exit codes.
* [09-fault-injection-and-recovery.md](09-fault-injection-and-recovery.md) covers fault injection
  and the `disrupt:` suite directive.
* [Pherf HOWTO 07, extending Pherf][p07] details the extension points available within Pherf.
* [14-fleet-through-the-harness.md](14-fleet-through-the-harness.md) describes the
  `pherf-distributed` driver and the distributed load-generation tier.

[p07]: ../../../../phoenix-pherf/docs/howto/07-extending-pherf.md
