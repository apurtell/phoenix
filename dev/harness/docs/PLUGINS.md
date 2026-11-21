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

# Plug-ins

Plug-ins allow extending the harness without modifying its core implementation. Each plug-in
resides in a single directory containing a `plugin.env` manifest along with the supporting files
referenced by that manifest. Through this mechanism, a plug-in can supply six categories of
extensions: validation suites, a workload driver, configuration overlays, JVM options and heap
sizes, test datasets, and metric and diagnostic collectors. The reference implementation in
`plugins/example` serves as a complete worked example that demonstrates all six extension types.

Compatibility is governed by `PB_CONTRACT_VERSION` in `lib/bundle.sh`, which also defines the
driver contract version and is currently set to 1. Any modification to the manifest keys, hook
interfaces, or exit codes defined in this specification requires incrementing that version number.

## Layout

```text
<plug-in>/
  plugin.env          the manifest, required
  driver.sh           a six-verb driver, when PX_PLUGIN_DRIVER names it
  hooks/<name>        one executable per registered hook
  checks/<name>       executable check scripts, run by suites/check-cluster.sh
  ...                 any file that a hook or the driver reads
```

The name of the enclosing directory defines the plug-in id. Every path declared in the manifest is
resolved relative to the plug-in directory and must remain confined within that directory.

## Discovery and enablement

Plug-in discovery searches two locations in the following order:

1. Every subdirectory of `$PX_HOME/plugins` that contains a `plugin.env` file. This is the default
   plug-in location and is always scanned by the harness.
2. Every directory listed in the colon-separated `PX_PLUGIN_DIRS` variable. Each entry points
   directly to an individual plug-in directory containing a `plugin.env` file, allowing external
   plug-ins to reside anywhere outside the harness tree.

Discovery by itself does not activate any plug-in. Activation is controlled explicitly through
`PX_PLUGINS`, a space-separated list of plug-in ids that defaults to empty. Because plug-ins can
modify role sizes, JVM options, and cluster thresholds, a directory that simply happens to exist on
disk must never alter a measurement implicitly. Enabled plug-ins are applied in the exact order in
which they appear in `PX_PLUGINS`.

Container images embed only the enabled plug-ins. During staging, `scripts/stage-artifacts.sh`
copies all files belonging to each enabled plug-in into `plugins/<id>/` inside the build context,
and the image definition copies `plugins/` to `/opt/px/plugins`. When no plug-in is enabled, the
`plugins/` directory in the build context remains empty, ensuring that an image carries only the
plug-ins specified by the configuration that built it.

The harness aborts the command with exit code 2 and a diagnostic message naming both the directory
and the failure reason under any of the following conditions: a malformed `plugin.env` manifest,
two plug-ins declaring the same id, a plug-in id that collides with the name of a built-in driver,
an id requested in `PX_PLUGINS` that was not found during discovery, or a path in `PX_PLUGIN_DIRS`
that lacks a `plugin.env` file.

## plugin.env

The manifest follows the standard configuration grammar used across the harness, consisting solely
of `KEY=VALUE` assignments, comments, and blank lines. Because the harness parses the file as plain
text rather than sourcing it as shell code, a manifest cannot execute arbitrary commands and every
assigned value is treated as a literal string.

| Key | Required | Value |
|---|---|---|
| `PX_PLUGIN_ID` | yes | Plug-in id (`^[a-z][a-z0-9-]{0,31}$`) equal to the directory name. |
| `PX_PLUGIN_CONTRACT` | yes | Contract version implemented by the plug-in, which must be `1`. |
| `PX_PLUGIN_DRIVER` | no | Relative path to a six-verb workload driver script. |
| `PX_PLUGIN_HOOKS` | no | Space-separated list of registered hooks from the nine hook names. |
| `PX_<KEY>_HEAP_MIB` | no | JVM heap allocation for the specified role, in MiB. |
| `PX_<KEY>_MEM_MIB` | no | Container memory limit for the specified role, in MiB. |
| `PX_<KEY>_DIRECT_MIB` | no | JVM direct memory limit for the specified role, in MiB. |
| `PX_<KEY>_JVM_OPTS` | no | Role JVM options appended after the base options of the harness. |

In the role-scoped keys above, `<KEY>` must match one of the role identifiers from the sizing
table: `ZK`, `NN`, `SNN`, `DN`, `RM`, `NM`, `JHS`, `MASTER`, `RS`, `CLIENT`, `TOOLING`, or
`SETUP`. Any unrecognized role key causes the harness to reject the plug-in.

The `PX_PLUGIN_HOOKS` key may appear on multiple lines, with each occurrence appending its hook
names to the registered list so that long declarations can remain within the line-length column
limit. Any other key that is defined more than once causes the harness to reject the plug-in with
exit code 2.

The harness rejects any manifest missing `PX_PLUGIN_ID` or `PX_PLUGIN_CONTRACT` with the message
`<dir>/plugin.env lacks <key>`, and rejects any contract version other than 1 with the distinct
message `plug-in <id> implements contract <n>, this harness implements 1`. Declaring a hook in
`PX_PLUGIN_HOOKS` without a corresponding executable at `hooks/<name>` also causes the plug-in to
be rejected, whereas any file present in `hooks/` that is omitted from the registered list is never
invoked.

**The heap allocation table and the JVM options.** The memory sizing and JVM option settings from
all enabled plug-ins constitute a dedicated precedence stage within the configuration loader. This
stage is evaluated after local environment overrides in `local.env` and before the run class
defaults, allowing a plug-in to replace the default class sizing for a role while ensuring that an
explicit operator value still takes precedence. Each value loaded at this stage records its origin
as `plugin:<id>`, which is displayed by `px-cluster config`. Because these settings use the
standard sizing variable names, the host preflight check sums them alongside every other role
limit, ensuring that a plug-in can never expand the total memory footprint beyond what the
container engine holds. Role options defined in `PX_<KEY>_JVM_OPTS` are passed to every instance of
that role after the base image options and before the `jvm-opts` hook. Including a heap, direct
memory, or container sizing flag there aborts the render with exit code 2, because memory sizes may
only be assigned through the dedicated sizing variables. This restriction enforces the standard
disallowed pattern set across every renderer: `-Xms*`, `-Xmx*`, `-Xmn*`,
`-XX:MaxDirectMemorySize*`, `-XX:MaxRAM*` (which also covers `-XX:MaxRAMPercentage`),
`-XX:MaxHeapSize*`, `-XX:InitialHeapSize*`, `-XX:MinHeapSize*`, `-XX:InitialRAM*`, and
`-XX:MinRAM*`. The identical set of forbidden flags applies to the output lines produced by the
`jvm-opts` hook.

## Hooks

A hook is an executable file at `hooks/<name>` provided by an enabled plug-in and declared in its
manifest. At each lifecycle call site, the harness invokes the corresponding registered hook for
every enabled plug-in once, following the order defined in `PX_PLUGINS`. Each hook execution runs
with the following runtime context:

* the current working directory set to the plug-in directory;
* `PX_PLUGIN_ID`, `PX_PLUGIN_DIR` (set to the absolute path of the plug-in directory), and
  `PX_HOOK` (set to the active hook name);
* `PX_HOOK_OUT`, designating the dedicated output directory pre-created by the harness for the
  hook's artifacts;
* `PX_RECORD_SOURCE=plugin:<id>`, ensuring that any emitted records identify the originating
  plug-in;
* `PX_HOME`, `PX_OUT`, the active configuration variables, and the call-site-specific environment
  described below;
* an execution timeout bound of `PX_TIMEOUT_VERB`, or `PX_TIMEOUT_FETCH` in the case of the
  `fetch-data` hook.

The `PX_HOOK_OUT` directory resolves to `<base>/plugins/<id>`, where the base path corresponds to
the output root of the active call site: the run bundle `$PX_RUN_DIR` during a benchmark run, the
deployment directory during cluster rendering, or the session directory for the `suites` hook. When
executing a run, `px-run` copies the deployment's `plugins/` directory into `cluster/plugins/`
within each run bundle. For every hook invocation, the harness appends a telemetry row formatted as
`ts_ms hook status elapsed_ms` to `<base>/plugins/<id>/hooks.tsv`, and it records an entry with the
hook name `check` for each check script executed by `suites/check-cluster.sh`. Like workload
drivers, hooks must interact with the cluster exclusively through the substrate contract: they must
never invoke a container runtime or Kubernetes CLI directly, and the harness self-test actively
scans `plugins/` for forbidden tool references.

During cluster rendering and suite resolution, render hooks and the `suites` hook write their
output into a temporary staging directory assigned to `PX_HOOK_OUT`. Once the hook finishes, the
harness relocates this staging directory to `<deployment dir>/plugins/<id>` for render hooks, or
copies its contents to `<session dir>/plugins/<id>` for the `suites` hook. Consequently, a hook
must never persist the absolute value of `PX_HOOK_OUT` or embed that absolute path inside generated
files or templates; all written paths must remain relative to `PX_HOOK_OUT`.

A hook must exit with status 0 upon successful completion, including when it determines that no
action is required. Any non-zero exit code indicates a failure. When a fail-closed hook fails, the
harness aborts the enclosing operation immediately, whereas a non-blocking hook failure simply
records an `ADVISORY` verdict row. If a hook exceeds its allotted timeout bound, the harness sends
a TERM signal followed by a KILL signal and records the invocation as failed with exit code 4.

| Hook | Call site | Arguments | stdout | On failure |
|---|---|---|---|---|
| `conf-overlay` | `docker/compose.gen.sh`, each render | none | ignored | render exit 2 |
| `jvm-opts` | `docker/compose.gen.sh`, each render | none | `<KEY> <option>...` | render exit 2 |
| `image-check` | `docker/compose.gen.sh`, each render | IMAGE | ignored | render exit 2 |
| `fetch-data` | `px-run`, before `prepare` | none | ignored | `FAIL`, no prepare |
| `pre-workload` | `px-run`, after `prepare`, before `run` | none | ignored | `FAIL`, no run |
| `post-workload` | `px-run`, after `run`, before `restore` | none | ignored | `FAIL` |
| `suites` | `px-suite`, when it resolves a suite | none | suite paths | `px-suite` exit 2 |
| `thresholds` | `suites/check-cluster.sh` | none | `key=value` | `ERROR`, no checks |
| `collect` | `px-run`, after the checks | none | ignored | `ADVISORY` only |

Any `FAIL` or `ADVISORY` verdict row generated by a hook uses the check key `plugin.<id>.<hook>`,
assigns the evidence class `none`, and records the hook's exit code in the detail field.

### conf-overlay

This hook supplies configuration overlay templates that the container template renderer merges at
startup. During each render pass, `docker/compose.gen.sh` invokes the hook once with no arguments.
The hook populates `$PX_HOOK_OUT/conf-overlay/` with `*.tmpl` files, typically by copying template
files packaged within the plug-in. The generator aggregates the overlay files from all enabled
plug-ins into the deployment directory's `conf-overlay/` folder, mounts that folder read-only into
every container instance, and exposes its location via `PX_CONF_OVERLAY_DIR`. At container startup,
each instance renders the overlays after processing its base templates, enforcing the same
fail-closed substitution rules. When an overlay named `<name>.xml.tmpl` targets a Hadoop, HBase, or
Phoenix XML configuration file, its contents are merged into the corresponding base file: each
`<property>` element either overrides the base property with the matching `<name>` or inserts a new
property, leaving all other base properties intact. For any other file format, an overlay template
replaces the matching base file outright, while an overlay that has no counterpart in the role's
base template set introduces a new file. An unresolved `${PX_*}` variable in any overlay aborts the
startup of the instance. If two plug-ins output the same overlay file name, the generator aborts
the render with exit code 2.

### jvm-opts

This hook provides dynamically computed, role-specific JVM parameters when static manifest strings
are insufficient. During each render pass, `docker/compose.gen.sh` calls the hook once with no
arguments. Each line written to standard output must consist of a valid role key, a single space,
and the JVM options to apply to every instance of that role. The generator appends these options
after the static `PX_<KEY>_JVM_OPTS` values. If any output line specifies an unrecognized role key,
includes a forbidden memory sizing option, or contains characters disallowed by the generator's
value grammar—which prohibits dollar signs, quotes, backslashes, and hash characters—the render is
rejected with exit code 2.

### image-check

This hook validates container image prerequisites prior to deployment rendering.
`docker/compose.gen.sh` invokes the hook once per render before executing any other render hooks,
passing a single argument: the `PX_IMAGE_TAG` image identifier that all roles in the deployment
will run. During validation, the hook may also inspect the active configuration and the image's
staging metadata under `$PX_OUT/images/`. A non-zero exit aborts the render with exit code 2,
guaranteeing that the backend never instantiates a deployment rejected by a plug-in and that no
benchmark run ever measures it.

### fetch-data

This hook handles test dataset retrieval and cluster staging for the plug-in. `px-run` invokes the
hook with no arguments after completing the pre-run snapshot and quiet-host verification, and prior
to running the driver's `prepare` verb. The hook populates `$PX_HOOK_OUT/data/` with its required
datasets—drawing from a plug-in-managed cache under `$PX_OUT/dist/plugins/<id>/` when
applicable—and transfers the files into the cluster using `sb_copy_in` to the remote work directory
`/tmp/px/$PX_RUN_ID` on `client` 0, matching the path layout defined in `docs/driver-contract.md`.
Any remote download must occur over HTTPS and either validate against a declared cryptographic hash
or record the hash upon first retrieval and verify it on every subsequent execution, following the
same integrity model as `scripts/fetch-dist.sh`. Locally, the plug-in workload driver can access
the staged files at `$PX_RUN_DIR/plugins/<id>/data`. If this hook fails, the harness records a
`FAIL` verdict row and neither `prepare` nor `run` starts.

### pre-workload

This hook serves as the readiness gate between test preparation and workload measurement. `px-run`
calls the hook with no arguments after the driver's `prepare` verb exits with status 0 and before
the `run` verb begins. Typical uses include verifying staged datasets, schema objects, and cluster
state, such as confirming an expected initial row count. If the hook fails, it records a `FAIL`
verdict row and `run` does not start, although `restore` and `report` still execute to clean up and
summarize the attempt.

### post-workload

This hook performs post-measurement validation immediately after the workload finishes. `px-run`
invokes the hook with no arguments after `run` terminates—even if `run` itself failed—and before
`restore` begins. The hook can inspect the post-execution state left behind by the workload, for
instance verifying that input datasets were not destructively consumed. A non-zero exit records a
`FAIL` verdict row, while `restore` and `report` still run to completion.

### suites

This hook registers custom test suites supplied by the plug-in. `px-suite` calls the hook on each
enabled plug-in once while resolving its suite argument, including during dry-run invocations. Each
line emitted to standard output must be the relative path to a `.suite` file located inside the
plug-in directory. When resolving a suite identifier, `px-suite` searches in the following order:
an explicit file path, `<name>.suite` inside `PX_SUITE_DIR`, and finally a plug-in-contributed
suite matching that base name. During a dry run, `PX_HOOK_OUT` points to a temporary directory that
`px-suite` removes upon completion so that a dry run leaves no persistent output on disk.

### thresholds

This hook adjusts existing validation thresholds or introduces new threshold keys for plug-in
checks. `suites/check-cluster.sh` calls the hook once per run with no arguments immediately after
loading the baseline `suites/thresholds.properties` file. Each line printed to standard output must
take the form `key=value`, where the key either overrides an entry from the base file or defines a
new threshold consumed by a plug-in check script, and the value is a number. Each line updates its
target key while leaving all other thresholds untouched. If multiple plug-ins set the same key,
entries appearing later in `PX_PLUGINS` override earlier ones. A plug-in threshold takes precedence
over any `PX_THRESHOLD_<KEY>` value defined in a run class file or configuration file, matching the
precedence of plug-in memory sizing over run class defaults. Conversely, an operator-supplied
`PX_THRESHOLD_<KEY>` passed via the process environment or `px-run -e` overrides any plug-in line.
If threshold evaluation fails, no cluster checks are executed, because validating against an
invalid threshold set proves nothing. Emitting a line that does not match the `key=number` syntax
constitutes a usage error: `suites/check-cluster.sh` records an `ERROR` row for
`cluster.thresholds` and exits with code 2, causing `px-run` to record an accompanying `ERROR` row
for `cluster.checks`. By contrast, when the hook process exits with a non-zero status, only the
`ERROR` row for `cluster.thresholds` is emitted.

### collect

This hook executes diagnostic collectors that attach supplemental artifacts to the run bundle.
`px-run` invokes the hook with no arguments after `suites/check-cluster.sh` completes and before
cluster teardown begins, ensuring that the cluster remains running and accessible during
collection. The hook writes its collected files into `$PX_HOOK_OUT` and may catalog them in a
`manifest.tsv` file within that directory using the header `path description`. In addition, the
hook may record supplemental metrics via `pb_metric` using metric keys prefixed with
`plugin.<id>.`. A failure in this hook produces only an `ADVISORY` verdict row, ensuring that a
diagnostic collection issue never invalidates a benchmark measurement.

## Check scripts

A plug-in can contribute custom cluster health and validation checks by placing executable scripts
in its `checks/` directory. During post-run validation, `suites/check-cluster.sh` executes every
executable file under `checks/` for each enabled plug-in in lexicographical name order, running
them after the built-in check subjects and steady-state checks. Each check script receives the
standard hook environment with `PX_HOOK=check` and `PX_HOOK_OUT=$PX_RUN_DIR/plugins/<id>`,
supplemented by `PX_RUN_DIR`, `PX_RUN_CLASS`, `PX_CONTAMINATION`, `PX_SCOPE_ID`, `PX_BACKEND`, and
`PX_THRESHOLDS_FILE`, and is bounded by an individual timeout of `PX_TIMEOUT_VERB`. Because each
script execution is treated as an individual hook call, the plug-in's `hooks.tsv` log records a
corresponding `check` row for it. At the outer level, `px-run` limits the entire
`suites/check-cluster.sh` execution to a total budget of two `PX_TIMEOUT_VERB` intervals. This
single aggregate timeout encompasses all `thresholds` hooks, the seven built-in check subjects, the
two steady-state checks, and all plug-in check scripts combined. If that aggregate timer expires,
any remaining checks are skipped and `px-run` emits a `cluster.checks ERROR` verdict row with
`exit 124`.

Each check script is responsible for persisting its findings directly into the run bundle. It must
emit at least one verdict row using `sx_verdict_row`, keyed as either `plugin.<id>.<name>` or
`plugin.<id>.<name>.<detail>`, and tagged with the appropriate evidence class so that verdict
aggregation demotes timing-dependent results in the same manner as built-in checks. The script also
records its measured value as a metric entry using `pb_metric` so that the quantitative observation
remains available for post-run analysis, and it looks up any required threshold values via
`sx_threshold`. A check script's exit status does not signal pass or fail severity: an exit code of
0 simply indicates that the check ran to completion, whereas any non-zero exit code causes
`suites/check-cluster.sh` to record an `ERROR` row under `plugin.<id>.<name>`, since a check that
failed to execute leaves a validation gate unverified.

## The plug-in driver

Setting `PX_PLUGIN_DRIVER` in the manifest registers a workload driver that implements the six-verb
driver contract specified in `docs/driver-contract.md`. The driver is identified by the plug-in id,
allowing a suite definition to select it using `driver: <id>`. When resolving a driver name,
`px-run` first checks for a built-in driver at `drivers/<name>/driver.sh` before falling back to
the driver provided by the enabled plug-in with that id. During execution, a plug-in driver writes
its artifacts to `drivers/<id>/` inside the run bundle, and its emitted verdicts participate in the
exact same verdict merge pipeline as built-in drivers. The driver script may also source the shared
helper library at `drivers/lib/driver.sh`.

## The example plug-in

The reference implementation in `plugins/example` registers all nine lifecycle hooks and exercises
every supported manifest key. Each hook creates a `<hook>.marker` file inside `$PX_HOOK_OUT` so
that automated tests can confirm its execution, and performs a minimal, representative operation:

* `plugin.env`: declares the id `example`, contract version 1, the driver path, all nine hooks, one
  heap allocation line that preserves the default run class value, and
  `PX_CLIENT_JVM_OPTS=-Dpx.example=1`;
* `conf-overlay`: copies a single overlay template for `hbase-site.xml` defining the
  `px.example.instance` property, which the renderer merges into `hbase-site.xml` across every
  container instance;
* `jvm-opts`: emits one computed JVM option targeting the `CLIENT` role;
* `image-check`: verifies that the container image uses a local repository:tag format without a
  registry host or content digest;
* `fetch-data`: stages a small local dataset from `plugins/example/data`—requiring no network
  connectivity—into the run bundle and transfers it with `sb_copy_in` into `client` 0;
* `pre-workload`: confirms that the staged dataset arrived both in the bundle and inside `client` 0;
* `post-workload`: verifies that the dataset remains intact after the workload completes and cleans
  up the remote copy on `client` 0;
* `suites`: outputs the plug-in's `suites/example.suite` definition;
* `thresholds`: emits a replacement value for the `log.warnings.max` threshold key;
* `collect`: writes a supplemental diagnostic file alongside its `manifest.tsv` index;
* `checks/marker`: records one cluster validation verdict row and one accompanying metric record;
* `driver.sh`: provides a complete six-verb workload driver implementation.

A self-contained plug-in addition or modification requires no edits to any file under `bin/`,
`lib/`, or `suites/`.

## The plug-in library

The shared library `lib/plugin.sh` implements the mechanics defined in this specification for every
harness entry point, and all call sites invoke its functions rather than maintaining custom
iteration loops. Callers source this library after loading `lib/common.sh`.

* `pl_discover` outputs one `id dir` line per discovered plug-in and exits with code 2 if any
  plug-in fails validation.
* `pl_enabled` outputs the rows for all active plug-ins in the order listed in `PX_PLUGINS`, and
  exits with code 2 if any requested id is unknown.
* `pl_value ID KEY` retrieves and prints a single manifest setting for the specified plug-in.
* `pl_driver NAME` prints the workload driver path for the enabled plug-in matching that id, or
  exits with code 6 when none matches.
* `pl_driver_resolve SPEC` resolves a driver identifier using the search order shared by `px-run`
  and `px-suite`—testing a direct path first, then `drivers/<spec>/driver.sh`, and finally
  `pl_driver`—and exits with code 2 if no executable driver is found.
* `pl_hook_run HOOK BASE [ARG...]` executes the specified hook across every enabled plug-in that
  registers it, passing the same arguments and setting `PX_HOOK_OUT=BASE/plugins/<id>`, appends an
  execution record to `hooks.tsv`, and prefixes every line written to standard output with the
  plug-in id and a tab character. It exits with code 0 when all invoked hooks succeed, or continues
  running the remaining registered hooks before returning the exit status of the first hook that
  failed.
* `pl_config_stage` executes the plug-in precedence stage within the configuration loader.
