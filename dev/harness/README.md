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

# Phoenix cluster and performance test harness

The test harness orchestrates the full lifecycle of a multi-role Phoenix cluster on a developer
host: it provisions the cluster, executes test suites and individual tests against it, gathers
runtime telemetry, and tears the deployment down. Implemented entirely in shell, the harness
requires only a POSIX userland, `bash`, and a container runtime accessible to the active backend.
It operates without `jq`, `python`, or Maven at runtime, and it never writes any data inside the
repository working tree.

All commands interact with a cluster exclusively through a single substrate contract defined in
`lib/backend/contract.sh`. That contract is backed by Docker Compose today and is designed to
support a Kubernetes backend in a subsequent phase, allowing test drivers, suites, health checks,
and telemetry collectors to run unmodified across both substrates. `docs/backends.md` serves as the
authoritative contract specification and the blueprint for implementing additional backends.

## Quick start

```sh
export PATH="$PWD/dev/harness/bin:$PATH"   # or call the commands by path
px-cluster --help                          # every command answers --help
px-cluster config                          # every resolved value with its origin
px-cluster validate --no-backend           # check the configuration and the contract only
px-suite --dry-run my.suite                # print the execution order, change nothing
dev/harness/test/run-tests.sh              # the self-test: no runtime, no network, no Maven
```

Because `px-cluster config` and `px-cluster validate --no-backend` do not contact a container
runtime, both commands function even before a runtime is installed on the host. Bringing up a live
cluster requires two additional prerequisites: a built container image and a rendered deployment
directory. The deployment directory serves as the sole architectural boundary between the topology
generator and the substrate backend; consequently, `px-cluster up DIR` provisions whatever
directory it receives without needing any built-in knowledge of roles, sizing, port assignments, or
component versions. Supply the target directory as a positional argument or configure it via
`PX_DEPLOYMENT_DIR`; invoking `px-cluster up` without either constitutes a usage error and exits
with code 2. Refer to the "The deployment directory" section of `docs/backends.md` for the exact
layout and contents of that directory.

When `px-cluster up` completes, it persists the active cluster scope so that subsequent commands
invoked from any shell session can discover it automatically, as detailed under "Scope resolution"
below. All generated artifacts are written beneath `$PX_OUT`, which defaults to
`$HOME/phoenix-harness`. Operators should inspect the run directory path printed by the command, as
no runtime artifacts are ever left inside the source tree.

## Commands

| Command | Purpose |
|---|---|
| `px-cluster` | Manages lifecycle: provision, wait, inspect, log, exec, scale, faults, teardown. |
| `px-suite` | Executes a declarative suite: dependencies, boundaries, filters, and dry-run plans. |
| `px-run` | Executes one test: one run directory, one driver, snapshots, disruptions, exit code. |
| `px-tool` | Runs administration and diagnosis tasks: an instance shell and the doctor report. |
| `px-collect` | Samples host and container resource utilization and writes the telemetry to CSV. |
| `px-profile` | Orchestrates sampling profiler sessions against selected cluster roles. |
| `px-jfr` | Captures Java Flight Recorder profiling sessions against selected cluster roles. |
| `px-trace-count` | Runs exact invocation-count tracing, the count-based evidence a run prefers. |
| `px-bundle` | Packages and validates the structured result bundle produced by a test run. |
| `px-report` | Aggregates verdict streams into a consolidated run verdict and final exit code. |
| `px-pherf` | Deploys and coordinates the Pherf workload controller and agents on any backend. |
| `px-k3d` | Creates and tears down the local k3d cluster that validates the Kubernetes backend. |

Every command responds to `--help` with exit code 0 and rejects any unrecognized flag with exit
code 2.

## Policy

Five foundational invariants are hardcoded into the architecture rather than exposed as tunable
settings. These guarantees protect developer workstations where the harness executes alongside
active day-to-day engineering work.

### Local images only

The harness operates exclusively with locally built container images. It compiles a single
multi-role image from the local source tree and loads that image directly into the local container
engine; it never pulls a harness image from a remote registry, nor does a test execution ever push
one. Remote registries participate in only one workflow: the Kubernetes promotion path, which
publishes the specific image digests recorded by a passing result bundle.

### Pinned downloads

Every external artifact required during image construction is fetched through a cryptographically
pinned download mechanism. A declared pin specifies an architecture-specific cryptographic digest
that the fetcher validates over HTTPS after resolving an appropriate Apache mirror. When an
artifact lacks a predeclared pin, the fetcher applies a trust-on-first-use policy: the initial
download records the observed digest into the local version override file, and all subsequent
downloads verify the artifact against that recorded hash. Any digest mismatch immediately aborts
the retrieval.

### Output outside the tree

All runtime output is strictly isolated from the repository: every byte produced during execution
is written beneath `$PX_OUT`. The `PX_OUT` path must be an absolute path, cannot be the filesystem
root, and must not reside anywhere inside the working tree containing the harness. Every output
destination, including the `PX_LOG_FILE` log path, is validated through a single canonical path
resolver that anchors relative paths under `$PX_OUT` and rejects any target that escapes `$PX_OUT`
or falls within the working tree. Directory containment checks compare underlying filesystem
identities rather than raw path strings, ensuring enforcement across symbolic links and
case-insensitive volumes. Furthermore, the self-test suite fails if an execution introduces any
new path beneath the repository root.

### One cluster per host

Only a single active cluster is permitted on a host at any given time. Scope creation acquires a
host-level lock and aborts if any harness container is already present on the machine or if the
state directory contains an existing scope record, because concurrent clusters compete for host
memory and loopback ports and invalidate performance measurements for both runs. Operators must
tear down an existing deployment with `px-cluster down` before starting another. If a scope record
persists after its underlying containers have disappeared, setting `PX_FORCE=1` authorizes the
harness to reclaim the stale state.

### Teardown by default

Clusters are automatically torn down as soon as the operation that created them concludes.
Rendered deployments disable automatic container restarts across all instances, and every readiness
or completion wait enforces a finite bound. If `px-cluster up` encounters a failure during
bring-up, it automatically dismantles the partially provisioned cluster, and `px-suite` stops every
role group it launched when the suite session finishes. Specifying `PX_TEARDOWN=0` or `--keep`
preserves the running instances for post-failure triage, though this serves strictly as a
diagnostic aid rather than a normal operating mode. Manual cleanup is performed via
`px-cluster down`.

## The output directory

The `PX_OUT` variable designates the root directory for all harness outputs and defaults to
`$HOME/phoenix-harness`. During initialization, `lib/common.sh` resolves this path once, creates
the directory if necessary, normalizes `PX_OUT` to its canonical form, and exports it so that every
child process and deployment generator operates against the exact same root.

```text
$PX_OUT/
  runs/<run-id>/       one directory per px-run: artifacts, logs, run-record.tsv
  collect/<run-id>/    one collection that px-collect start made by hand
  sessions/<id>/       one directory per px-suite session: plan, results (with the
                       bundle of each test), suite.log
  state/               the state directory, PX_STATE_DIR moves it under $PX_OUT
    scope-<backend>-<cluster>   the scope id that px-cluster up recorded
    cluster-<cluster>.env       the cluster record: the configuration of the
                                bring-up
    planned-<scope>.tsv         the planned actions of one scope
    collect-<cluster>.tsv       the collections of the cluster that may hold a
                                live collector, one directory per line
    compose/                    the Compose backend: its scope records, its
                                capability table and its host lock
```

Subsequent implementation rounds will place downloaded binary distributions, container image build
contexts, and rendered deployment directories beneath this same root directory.

Each run identifier combines a chronologically sortable UTC timestamp with a compact disambiguating
suffix in the format `<YYYYMMDDTHHMMSSZ>-<4 characters>`. The timestamp prefix ensures that run
directories sort lexicographically in execution order. The suffix incorporates the process ID, two
random characters, and a per-process counter, and the resulting run directory is created
exclusively so that two runs initiated within the same second cannot collide or share a directory.

## Scope resolution

Every command that targets a running cluster resolves the active scope identifier through a single
function, `sb_scope_resolve`. Resolution checks `PX_SCOPE_ID` first when explicitly set in the
environment; otherwise, it reads the scope record persisted by `px-cluster up` for the selected
backend and `PX_CLUSTER_ID` (which defaults to `default`), or exits with code 6 and an error
message identifying the missing cluster. This resolution path is shared across `px-cluster`,
`px-run`, `px-suite`, `px-tool`, `px-collect`, and `px-pherf`, while `px-cluster down` removes the
persisted scope record upon teardown. A valid cluster name must begin with a lowercase letter or
digit and may contain only lowercase letters, digits, `-`, and `_`.

## The PX_* namespace

All configuration variables consumed by the harness reside in the `PX_*` namespace, and every
recognized `PX_*` identifier is registered in `lib/varnames.txt`, one entry per line. An entry
ending with an asterisk defines a prefix family, allowing `PX_PORT_*` to authorize all dynamic
variables emitted in a generated port map. An entry annotated with the `private` modifier remains
confined to the process that loads it and is never exported to child processes; `PX_DOCKER_BIN` is
the sole variable marked with this restriction.

Enabling `--strict` on the command line (or exporting `PX_STRICT_CONFIG=1` in the environment)
causes the configuration loader to reject any variable outside the `PX_` namespace as well as any
`PX_*` identifier absent from the registry, reporting the offending file, the loading stage, and
the corrective action. This ensures that typographical errors fail closed rather than silently
falling back to default values. Attempting to enable `PX_STRICT_CONFIG` inside a configuration file
is rejected with exit code 2, because strict validation must be active prior to reading the first
file. Lax validation remains the default so that subsequent development rounds can incrementally
register their own variable families: under lax mode, an undeclared variable is logged as a debug
note and loading proceeds.

Internal harness constants are not user-configurable settings. In particular, `PX_CONTRACT_VERSION`
is strictly read-only, and any attempt to assign it in a configuration file or via `--set` is
rejected with exit code 2.

Whenever a change introduces a new configuration variable, register its name under the appropriate
section of `lib/varnames.txt` within the same commit.

## Configuration precedence

Configuration resolution is handled by a single loader that evaluates sources in a deterministic
sequence and records the provenance of every resolved setting. The loader evaluates stages in the
following order, and **the first stage that defines a variable takes precedence**:

| # | Stage | Origin | Default location |
|---|---|---|---|
| 1 | Process environment | `env` | Exported `PX_*` variables |
| 2 | Command-line configuration files | the file path | `--config FILE` |
| 3 | Local environment overrides | `local` | `docker/env/local.env` |
| 4 | Run class defaults | `class:<name>` | `docker/env/class-<class>.env` |
| 5 | Baseline defaults | `defaults` | `docker/env/defaults.env` (mandatory) |
| 6 | Component version definitions | `versions` | `docker/versions.env` |
| 7 | Local version overrides | `versions-local` | `docker/versions.local.env` |
| 8 | Generated port map | `ports` | `<deployment directory>/ports.generated.env` |

Because a later stage never overwrites a value established by an earlier stage, explicit overrides
reliably supersede lower-priority defaults. This precedence model also underpins trust-on-first-use
artifact pinning: local version overrides supply a digest only when the tracked version definitions
do not already declare one, whereas an operator who wishes to override a committed pin places the
replacement in `local.env`, which outranks both version files. Similarly, because run class
defaults are evaluated prior to the baseline defaults, specifying a run class only inside a later
stage would occur too late to take effect; the loader actively rejects such late declarations
rather than silently ignoring them, requiring the class to be specified on the command line or in
the environment.

Operators also use `local.env` to configure `PX_DOCKER_BIN` when the Docker CLI resides outside the
standard search paths inspected by the backend. Every `px-*` command reads this file directly, while
the private variable rule prevents the setting from leaking into child processes.

Because the generated port map is a runtime artifact, stage 8 loads it from the rendered deployment
directory beneath `$PX_OUT` and never from the source tree; setting `PX_PORT_MAP_ENV` redirects this
stage to an alternate file path. Stage 8 executes during initial configuration loading whenever
`PX_DEPLOYMENT_DIR` or `PX_PORT_MAP_ENV` is already defined, whereas `px-cluster up DIR` and the
backend invoke stage 8 dynamically once the target deployment directory becomes known. Because
stage 8 occupies the lowest precedence tier, evaluating it late preserves the overall precedence
order.

Configuration files may contain only `KEY=VALUE` assignments, comment lines, and blank lines;
shell constructs such as `export K=V` are rejected. The loader parses configuration files as plain
text rather than sourcing them in a shell, ensuring that files cannot execute arbitrary commands
and that all assigned values are treated as uninterpreted literals without variable expansion.
Running `px-cluster config` displays every resolved variable alongside its origin stage, providing
the primary starting point for diagnosing configuration precedence issues.

## Exit codes

The harness commands and the substrate contract share a single, uniform exit code vocabulary.
Callers branch directly on the numeric exit code, while detailed diagnostic causes are recorded in
the error message, the cluster-check verdict stream, and the result bundle manifest.

| Code | Name | Meaning |
|---|---|---|
| 0 | ok | The requested operation succeeded, or `px-cluster down` found no recorded cluster. |
| 1 | failure | The system under test or the requested operation failed. |
| 2 | usage | The command-line syntax or a supplied value is invalid. |
| 3 | anomaly | The run outcome cannot be trusted. |
| 4 | timeout | A finite wait bound expired before its target condition was satisfied. |
| 5 | unsupported | The active backend or host environment lacks a required capability. |
| 6 | no such object | A referenced object does not exist. |
| 130, 143 | interrupted, terminated | An INT or TERM signal terminated the command. |

Exit code 0 encompasses the PASS, SKIP, and ADVISORY verdicts. Exit code 1 covers the FAIL and
INCONCLUSIVE outcomes, a failed check or build, leftover resources remaining after down, an output
that cannot be written, and an unresponsive runtime. Exit code 2 covers invalid syntax or an invalid
value, an unrecognized token from a fixed vocabulary, and an attempted write inside the repository
tree. Exit code 3 covers an instance that restarted, exited, vanished, or ran out of memory, a gate
or preflight check that rejected execution, another active cluster holding the host, and an ERROR
verdict. Exit code 4 includes a host that remained busy. Exit code 5 signifies an unsupported
backend capability, a missing required tool, or a container engine that rejected the client
connection. Exit code 6 indicates an unknown cluster, scope, instance, port, bundle, session,
suite, test, group, image, or configuration file. Exit code 0 also applies when `px-cluster down`
runs with no cluster recorded. Furthermore, `px-cluster exec`
propagates the exit status of its executed command directly, including codes 124 through 127.

Automation scripts can rely on a single governing distinction: exit code 1 indicates a failure in
the system under test, whereas exit code 3 indicates that the collected evidence or execution
environment cannot be trusted. The verdict mapping is defined as a second named table in
`lib/common.sh`, where PASS, SKIP, and ADVISORY map to 0, FAIL and INCONCLUSIVE map to 1, and ERROR
maps to 3. Every help message that maps a verdict to an exit code prints this table.

Exit code 3 serves as the universal indicator for cluster anomalies across every backend. During a
test execution, `px-run` captures an instance state snapshot immediately before and after invoking
the test driver, and it overrides a driver exit status of 0 or 1 with code 3 whenever an instance
not covered by a planned action exhibits any of three state transitions: its restart count
increased, it was running prior to the test and is exited, dead, or in an unknown state afterward,
or it disappeared entirely. For every affected instance, the run record captures both the
termination reason and the out-of-memory flag so operators can distinguish an unexpected process
restart from an out-of-memory kill.

An action qualifies as planned only when `px-cluster` (`kill`, `signal`, `restart`, `scale`, or
`group`) or a scheduled disruption managed by `px-run` logs an entry to the planned-action ledger
prior to executing the change. Only entries recorded within the time bounds of the active run are
honored, ensuring that disruptions from earlier runs never mask new failures. The schema and
semantics of this ledger are detailed in `docs/backends.md`.

The `sb_exec` contract function is the sole exception that propagates a foreign exit status,
returning the exact exit code produced by the command executed inside the target instance. Four
status codes are reserved to distinguish substrate framing failures from in-container command
failures: 124 indicates that the execution wait expired, 125 indicates that the substrate could not
launch the command, 126 indicates that the target binary is not executable, and 127 indicates that
the target binary does not exist. Programs running inside a cluster role must never exit with any
of these four reserved codes.

## The host memory check

Starting an additional role group commits its container memory limits against host capacity;
accordingly, both `px-cluster group start` and the `requires` directive processing in `px-suite`
execute the host memory check prior to starting each role group. This check corresponds to the
memory validation phase of the host preflight subsystem, invoked via the command-line contract
implemented in round A2:

```sh
"$PX_HOME/scripts/preflight.sh" --phase memory
```

* The invoking command executes the preflight script under the `PX_TIMEOUT_DEFAULT` bound and
  redirects both its standard output and standard error streams to the caller's standard error,
  preventing diagnostic output from corrupting structured data on standard output.
* An exit code of 0 authorizes the role group to start. Any non-zero exit status blocks the
  start and is propagated by the caller: `px-cluster` terminates with that status code, while
  `px-suite` marks the affected test as `ERROR`. The preflight script exits with code 3 when the
  configured container limits exceed available host capacity, code 2 when given an invalid
  argument, and code 1 when host memory cannot be measured. It never returns code 4, which is
  reserved by the caller to report that the preflight check itself timed out.
* If the preflight script file is absent, execution is not blocked: the caller emits a warning
  noting that the memory check is not yet installed and proceeds to start the role group.
  Conversely, if the script file exists on disk but lacks execute permissions, the check fails with
  exit code 1.
* The substrate backend preflight function, `sb_preflight`, cannot be used as a substitute for this
  check because its host port availability assertions fail whenever a cluster is already running.

## Verdict severity

Every individual verdict record is assigned a severity level drawn from a strict total ordering:

```text
PASS  <  SKIP  <  ADVISORY  <  FAIL  <  ERROR
```

When aggregating multiple verdicts, the highest severity always dominates. Only `FAIL` and `ERROR`
represent failing outcomes. The `ERROR` level is reserved exclusively for harness or underlying
infrastructure faults, preserving a clear distinction between a cluster anomaly and a functional
or performance regression in the workload itself. Neither `SKIP` nor `ADVISORY` causes a run to
fail: a check whose required input artifact is unavailable emits `SKIP`, while a timing assertion
evaluated on a noisy or contaminated host emits `ADVISORY`. The severity lattice is implemented in
`suites/lib/common.sh`.

This aggregation lattice is already enforced by `px-suite`. Each test's process exit code is mapped
onto an initial severity level: exit code 0 maps to `PASS`, code 5 maps to `SKIP`, codes 2, 3, and
6 map to `ERROR`, and any other exit code maps to `FAIL`. If a test also emits explicit verdict
records, the test assumes the highest severity present in its verdict stream whenever that severity
exceeds the exit-code mapping. Any verdict record containing a severity token outside the defined
lattice is rejected, elevating the test outcome to `ERROR`. At the suite level, the session assumes
the worst severity observed across all executed tests, with one important exception: a session in
which every executed test resolved to `SKIP`, or in which no tests executed at all, has verified
nothing and therefore concludes with `INCONCLUSIVE`. Note that `INCONCLUSIVE` is a session-level
summary result rather than a verdict severity, and no individual verdict record may carry it. Upon
completion, `px-suite` prints the final session result and exits with code 0 for `PASS`, `SKIP`, or
`ADVISORY`, code 1 for `FAIL` or `INCONCLUSIVE`, and code 3 for `ERROR`. Once round A5 is
implemented, `px-report` will apply this exact same lattice when reducing the verdict streams of a
single test run.

## Self-test

The self-test suite in `test/run-tests.sh` executes without a container runtime, network access, or
a prior Maven build, allowing it to run both on a developer laptop and inside the pre-commit CI
container. It emits TAP-formatted test output and exits with code 1 if any assertion fails. Among
its architectural guarantees, the suite verifies through three independent enforcement layers that
no component outside the active backend directly invokes a container runtime or Kubernetes CLI
tool: a static source scanner paired with a documented allowlist that also fails on stale allowlist
entries, a runtime shell trap that forces any unauthorized invocation to exit with code 2, and a
mock runtime shim exercised against a negative control, a deliberately hostile driver, and a
positive control. The design of these three isolation layers is documented in `docs/backends.md`.
Using the same mock runtime environment, the suite exercises every substrate contract function and
every `px-cluster` subcommand, and its final test group verifies that the entire run left no new
files or directories beneath the working tree root.

## What is here, and what is not yet

This initial delivery establishes the foundational architecture: the substrate contract and its
Docker Compose backend, the twelve CLI commands with their complete command-line interfaces, the
declarative suite grammar and single-run wrapper, the shared shell library, this overview, and
`docs/backends.md`. By design, this layer contains no hardcoded cluster topology knowledge.

| Round | Adds |
|---|---|
| A2 | Adds the image, deployment generator, port map, versions and pins, sizing, and preflight. |
| A3 | Adds host safety controls: sleep inhibition, the health monitor, and quiet-host checks. |
| A5 | Adds the driver contract, bundle format, cluster checks, plug-in contract, and suites. |
| A6 | Wires the measurement toolkit: profiler, flight recorder, tracing, jHiccup, and samplers. |
| K1, K2 | Adds the Kubernetes backend, its preflight and safety checks, and the `px-k3d` tool. |

Any command or subcommand whose functional implementation belongs to a subsequent round exits
cleanly with code 5 and identifies the owning round, giving operators an explicit status message
rather than a cascade of runtime errors.
