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

Three additional documents specify the interfaces layered above the contract.
`docs/driver-contract.md` defines the six lifecycle verbs of a driver, their exit codes, the
`describe` document, and the built-in `psql`, `pherf`, and `java-main` drivers.
`docs/bundle-format.md` defines the structure of a run's result bundle, its manifest, the verdict
merge logic, and the cluster checks. `docs/PLUGINS.md` defines the plug-in model, including its
manifest, its nine hooks, its check scripts, and its driver, using `plugins/example` as a worked
example.

## Quick start

```sh
export PATH="$PWD/dev/harness/bin:$PATH"   # or call the commands by path
px-cluster --help                          # every command answers --help
px-cluster config                          # every resolved value with its origin
px-cluster render --class validation       # render a deployment, change nothing else
px-suite my.suite --dry-run                # print the execution order, change nothing
px-run psql --class validation -- --prepare schema.sql query.sql   # one test, one bundle
dev/harness/test/run-tests.sh              # the self-test: no runtime, no network, no Maven
```

Because `px-cluster config` and `px-cluster render` do not contact a container runtime, they
function even before a runtime is installed on the host. Bringing up a cluster requires two
additional prerequisites: the container image and a rendered deployment directory. That deployment
directory serves as the sole architectural boundary between the generator and the backend, allowing
`px-cluster up DIR` to provision whatever directory it receives without any knowledge of roles,
sizing, ports, or versions. See the "The deployment directory" section of `docs/backends.md` for the
specification of what that directory contains.

Once the image is built, a single command brings up a cluster from a run class and a topology file.
During staging, `stage-artifacts.sh` pins each base image by digest through
`PX_IMAGE_BASE_JDK<N>_SHA256_<ARCH>`. Any digest not already declared in
`docker/versions.local.env` is resolved and recorded on first use in `$PX_OUT/images/pins.env`, and
every subsequent build uses that record:

```sh
px-cluster build                           # the Phoenix jars, jHiccup and the image
px-cluster up -n --class measurement --config dev/harness/docker/env/override-24gib.env
px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env up
px-cluster down                            # idempotent: exits 0 when nothing is recorded
```

The Distributed Pherf controller and agents execute in a second local container image defined in
`docker/pherf`, built on the `PX_IMAGE_BASE_JRE17` JRE base image and pinned by digest under the
same policy (`PX_IMAGE_BASE_JRE17_SHA256_<ARCH>`). Enabling `PX_PHERF=1` serves as the single switch
for the tier: running `px-cluster -e PX_PHERF=1 build` also compiles `phoenix-pherf-distributed`
and builds the `PX_PHERF_IMAGE_TAG` image, while rendering with that setting adds the tier to the
deployment as the on-demand `pherf` role group. The `px-pherf` command starts the tier and executes
a suite against it, and the `pherf-distributed` driver runs a workload plan on it:

```sh
px-cluster -e PX_PHERF=1 build             # also phoenix-pherf-distributed and the pherf image
px-pherf --class validation run fleet.suite -- --only r1   # up, start, the suite, then down
px-cluster --class validation -e PX_PHERF=1 up && px-pherf --class validation start
px-pherf status                            # the group and the agents; px-pherf stop stops them
```

By default, the tier targets the harness cluster. Setting `PX_PHERF_TARGET=external` with
`PX_PHERF_ZOOKEEPER` pointed at the quorum of an external cluster renders a deployment containing
the tier alone, and `px-pherf
start` brings it up. `docker/pherf/compose.yaml` provides a standalone deployment of the same image
outside the harness, while the module README of `phoenix-pherf-distributed` documents the image in
detail.

When invoked without a directory argument, `px-cluster up` first renders the deployment for the
active run class into `$PX_OUT/deploy/<cluster>`. Calling it without a directory,
`PX_DEPLOYMENT_DIR`, or a run class constitutes a usage error and exits with code 2. Prior to
creating any resources, the command executes the host preflight check, `scripts/preflight.sh`,
against the deployment directory to verify the container engine, its credentials, and the required
loopback ports, and then compares the sum of the container memory limits against total engine memory
less 4 GiB. Any deployment that exceeds this budget is refused with exit code 3 and a diagnostic
message showing the arithmetic. Running `px-cluster up -n` performs a read-only preflight check: it
renders the deployment into a scratch directory, prints the execution plan rows alongside
individual check rows for the cluster record, host environment, container image, engine memory, and
ports, and returns the exit status that an actual bring-up would produce.

`px-cluster scale ROLE COUNT` adjusts the instance count of a DataNode, NodeManager, RegionServer,
or Distributed Pherf agent role in a running cluster. It re-renders the deployment from the stored
cluster record using the updated replica count, validates the new topology against the memory
budget, starts or stops only the instances added or removed by the count change, and persists the
updated count in the cluster record. If any command-line setting differs from the cluster record in
a way that would alter another instance, the command rejects the render.

Once provisioning completes, `px-cluster up` persists the cluster record so that subsequent
commands across any shell session can automatically discover the active cluster and its
configuration; see "Scope resolution" below. All generated artifacts are written beneath `$PX_OUT`,
which defaults to `$HOME/phoenix-harness`. Operators should inspect the run directory path emitted
by the command, because no runtime output is ever written inside the repository tree.

## Commands

| Command | Purpose |
|---|---|
| `px-cluster` | Manages the cluster lifecycle: build, up, scale, faults, down, matrix, and push. |
| `px-suite` | Executes a declarative suite file: dependencies, boundaries, filters, and dry run. |
| `px-run` | Executes one test: one run directory, one driver, snapshots, disruptions, exit code. |
| `px-tool` | Enforces host safeguards: the health monitor and the quiet-host check. |
| `px-collect` | Collects host and container samples to CSV and jHiccup with its idle control JVM. |
| `px-profile` | Runs async-profiler sessions, cpu and alloc, against the target JVMs. |
| `px-jfr` | Captures Java Flight Recorder recordings of the target JVMs. |
| `px-trace-count` | Reads Phoenix trace counters of every RegionServer, before and after the run. |
| `px-bundle` | Writes and validates the result bundle produced by a run. |
| `px-report` | Merges verdict streams into a single run verdict and a single exit code. |
| `px-pherf` | Manages the Distributed Pherf tier: start, stop, status, and run of a suite on it. |
| `px-k3d` | Manages the local k3d cluster for the Kubernetes backend proofs; implemented in K2. |

Every command responds to `--help` with exit code 0 and rejects any unrecognized flag with exit
code 2. Commands scheduled for implementation in a later round exit with code 5 on any other
invocation.

**Output conventions.** Every standard output stream is formatted as TSV. Any stream of checks or
verdicts concludes with a single `result STATUS CODE OBJECT` summary row reporting the outcome in
the stream's status vocabulary (either the `ok|warn|fail|skip` check status or the verdict severity
lattice), the numeric exit code, and the target object of the stream. This convention governs the
output of `px-report`, `px-bundle validate`, `px-suite`, `px-cluster matrix`, `px-cluster up -n`,
`px-collect stop`, `px-tool quiet-host`, and `px-k3d check`. A BUNDLE operand accepts either a
directory path or a run id resolved beneath `$PX_OUT/runs` (or for `px-collect`, resolved under
`$PX_OUT/collect` first); the resolved path must reside under PX_OUT (otherwise exiting with code 2)
and must already exist (otherwise exiting with code 6), as a missing bundle is never created
automatically. When invoked without an explicit bundle operand, `px-report`, `px-bundle validate`,
and `px-bundle metrics` default to the newest completed bundle—defined as the most recent run id
whose directory contains `manifest.json`, breaking ties between two run ids from the same second by
selecting the newer manifest. Passing `-n` always emits execution plan rows without executing any
actions, including for the read-only `px-report`, `px-bundle validate`, `px-bundle metrics`, and
`px-tool quiet-host` commands.

**Collectors.** `px-collect` serves as the unified entry point for all six telemetry collectors:
`profile`, `jfr`, `hiccup`, `host`, `container`, and `trace`. During a test run, `px-run` invokes
`px-collect start` on its result bundle for the collectors configured in `PX_COLLECTORS` immediately
before launching the `run` verb, and invokes `px-collect stop` immediately after the verb completes.
By default, the measurement run class starts all six collectors while the validation class starts
none; `px-run --collect LIST` selects the collectors to run and `--targets LIST` selects their
target roles, whereas a driver's HDR histograms remain enabled across both run classes. When invoked
manually, `px-collect start` either attaches to the cluster's active collection or initializes a new
collection directory at `$PX_OUT/collect/<run-id>`, and `px-collect stop` terminates the collectors
and retrieves their output artifacts into the bundle. Every collector writes exclusively within its
assigned bundle, and any collector failure yields an `ADVISORY` verdict rather than failing the run.
See the "The collectors" section of `docs/bundle-format.md` for the artifact file specifications.

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

Every third-party distribution required by the container image is retrieved through a pinned
download mechanism. A declared pin specifies an architecture-specific cryptographic hash that the
fetcher verifies over HTTPS after resolving an Apache mirror. When an artifact has no declared pin,
retrieval follows a trust-on-first-use policy: the first download logs the observed hash and records
it in `$PX_OUT/dist/pins.env` outside the source tree, and all subsequent downloads verify against
that recorded value. Because each pin is bound to a specific release version, a re-pinned release
never inherits the pin of another release. Any hash mismatch immediately aborts the download. Base
container images are similarly pinned by digest, whereas the four OS packages installed from the
base distribution's package archive remain unpinned. Phoenix itself is never downloaded remotely;
the image packages only the jars produced by the local Maven build.

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
host-level lock and refuses to start if any harness container is already present on the host or if
the state directory records another scope, because concurrent clusters on one host contend for
memory and loopback ports and invalidate performance measurements for both runs. The refusal message
identifies the project and the cluster currently holding the host. Operators must tear down the
existing cluster with `px-cluster down` before starting another. A scope record that outlives its
underlying cluster is treated as stale, and setting `PX_FORCE=1` authorizes the harness to reclaim
it.

### Teardown by default

**Teardown by default.** By default, every command tears down only the resources that it
provisioned. If px-cluster up fails or is interrupted, it tears down the partially provisioned
cluster; similarly, px-pherf run and px-cluster matrix tear down any clusters they bring up, and a
px-suite session stops the role groups it started. A cluster provisioned directly by px-cluster up
remains running until an explicit px-cluster down invocation, which is idempotent and serves as the
final cleanup step in every HOWTO workflow and script exit trap. Passing --keep preserves the
resources that a command would otherwise remove to support post-failure triage. All rendered
deployments disable automatic restarts, and every readiness or completion wait enforces a finite
bound.

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
    deployment-<backend>-<cluster>  the deployment directory that up provisioned
    inhibit-<holder>            the sleep inhibitor of a cluster or a run
    monitor-<scope>             the health monitor of a cluster, and its
                                .heartbeat
    down-<scope>                the lock that one px-cluster down holds
    compose/                    the Compose backend: its scope records and its
                                host lock
  deploy/<cluster>/    the rendered deployment directory: compose.yaml,
                       ports.generated.env, topology.env, services.tsv
  dist/                the verified distributions, and pins.env, the pins that trust on
                       first use recorded
  build/phoenix/       the record and the log of the local Phoenix build: build.tsv holds
                       the jars, the bson jar, pherf_classpath (the pherf jar and the
                       class path of phoenix-pherf without its test jars) and java_home
                       (the JDK of the build)
  images/<context>/    the staged build context of the image: the tag, each : and / as _
  images/<context>.staged.tsv  the version set of that context
  matrix/<run-id>/     one directory per px-cluster matrix: matrix.tsv
  monitor/<run-id>/    one directory per health monitor: samples.tsv, breach.tsv
```

Each run identifier combines a sortable UTC timestamp with a short suffix in the format
`<YYYYMMDDTHHMMSSZ>-<4 characters>`. The suffix encodes the millisecond, a per-process counter, and
one random character, ensuring that identifiers sort lexicographically in creation order even within
the same second. The harness then creates the run directory exclusively so that two runs initiated
within the same second never share a directory. See the "The run id" section of
`docs/bundle-format.md` for the formal grammar.

## Scope resolution

Every command that operates on a cluster resolves the active scope identifier through
`sb_scope_resolve`, which checks `PX_SCOPE_ID` first, falls back to the scope record written by
`px-cluster up`, and otherwise terminates with exit code 6 and the diagnostic hint
`no cluster ID is recorded: run px-cluster up, or name another with --cluster`. Because the target
backend for the scope record is determined from the stored cluster record, `--backend` only needs
to be specified during initial cluster bring-up. The sole exception is `px-cluster down`, which
exits with code 0 when no cluster is recorded. The complete resolution contract is detailed in
`docs/backends.md`.

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

Whenever a change reads a new configuration variable, register its name under the group it belongs
to in `lib/varnames.txt` within the same change.

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

Because a later stage never overwrites a value set by an earlier one, an explicit override always
supersedes lower-priority defaults. The local version overrides file holds operator-declared pins,
such as base image digests, together with version selections that re-pin a release; `versions.env`
specifies only the fallback default for each selection, which the loader applies after the final
stage under the `derived` origin. Trust-on-first-use pins live outside the configuration loader:
distribution digests are stored in `$PX_OUT/dist/pins.env` and read directly by
`scripts/fetch-dist.sh`, while base image digests are stored in `$PX_OUT/images/pins.env` and read
directly by `scripts/stage-artifacts.sh`. An operator who wants to replace a declared pin sets the
override in `local.env`, which outranks both version files. Because the run class is evaluated prior
to the baseline defaults, it must be specified on the command line, in the environment, in a
`--config` file, or in `local.env`; declaring it in a later stage is too late to take effect.

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

## Versions and the HBase profile

A version set comprises the Phoenix HBase build profile, the cluster's HBase and Hadoop runtime
releases, and the target JDK assigned to each role. By default, these selections track the latest
patch releases so that test runs include upstream bug fixes that an older static pin would miss:
Hadoop 3.4.3 across every profile, and the latest HBase 2.6 release (2.6.7) for the default `2.6`
profile.

The `PX_PHOENIX_HBASE_PROFILE` setting determines the `-Dhbase.profile` argument passed to the
Phoenix build. Each profile selects an HBase compatibility module and compiles Phoenix against the
HBase and Hadoop versions declared for that profile in the root `pom.xml`. The deployed cluster
executes its own HBase runtime release, which must fall within the version range supported by that
compatibility module. The lower bound of each range is governed by the `check-hbase-compatibility`
enforcer rule in `phoenix-core-client/pom.xml`, while the upper bound is the final patch release
prior to the next compatibility module in the same minor series, since Phoenix introduces a new
module whenever HBase modifies an interface that the module consumes. This compatibility matrix is
codified in `PX_HBASE_PROFILES` within `lib/common.sh`, and the build script rejects any root pom
that maps a profile to a different module.

| Profile | Module | Phoenix compiles against | Admitted runtime | Default runtime |
|---|---|---|---|---|
| `2.5.0` | 2.5.0 | HBase 2.5.3-hadoop3, Hadoop 3.2.4 | 2.5.0 to 2.5.3 | 2.5.3 |
| `2.5.4` | 2.5.4 | HBase 2.5.10-hadoop3, Hadoop 3.3.6 | 2.5.4 and later 2.5 | 2.5.10 |
| `2.5` | 2.5.4 | HBase 2.5.14-hadoop3, Hadoop 3.4.2 | 2.5.4 and later 2.5 | 2.5.16 |
| `2.6.0` | 2.6.0 | HBase 2.6.1-hadoop3, Hadoop 3.3.6 | 2.6.0 to 2.6.3 | 2.6.1 |
| `2.6.3` | 2.6.0 | HBase 2.6.3-hadoop3, Hadoop 3.4.2 | 2.6.0 to 2.6.3 | 2.6.3 |
| `2.6` (default) | 2.6.4 | HBase 2.6.5-hadoop3, Hadoop 3.4.2 | 2.6.4 and later 2.6 | 2.6.7 |

Because the floating `2.5` and `2.6` profiles track their respective minor release lines, each
defaults to the latest patch release in that series, whereas every other profile defaults to the
exact release declared for it in the pom. Both the artifact staging step and the host preflight
check reject any HBase release outside the admitted range with exit code 2, reporting the active
profile, its permitted version range, and the offending release.

The ZooKeeper and Hadoop client libraries used by the HBase daemons are the bundled jars shipped
inside the HBase distribution's `lib/` directory. During staging, the harness inspects the archive
to extract the exact version of each jar (requiring precisely one match for each), passes those
versions to the container image build for secondary verification, and records them in the staging
manifest. Any deployment rendered from that image propagates those versions into `topology.env`
alongside the rest of the version set, and `px-suite` records the complete version set in the
`summary.tsv` artifact of every suite session.

Predeclared cryptographic pins, taken from the official `.sha512` checksum files published by
Apache, cover the default distributions: Hadoop 3.4.3 for both CPU architectures, HBase 2.6.7, and
HBase 2.5.16. All other releases are verified under trust on first use. Because Apache Hadoop uses
different arm64 archive filenames across releases, each Hadoop release entry explicitly declares its
arm64 archive path: release 3.4.3 uses `hadoop-3.4.3-aarch64.tar.gz`, whereas release 3.4.2 used
`hadoop-3.4.2-aarch64-lean.tar.gz`.

### Re-pinning

Operators can re-pin component versions for a single host without modifying any tracked repository
file by writing the desired version selections into `docker/versions.local.env`, which is ignored by
git:

```sh
cat >dev/harness/docker/versions.local.env <<'EOF'
PX_DIST_HBASE_VERSION=2.6.8
PX_DIST_HADOOP_VERSION=3.4.4
PX_DIST_HADOOP_3_4_4_PATH_ARM64=hadoop/common/hadoop-3.4.4/hadoop-3.4.4-aarch64.tar.gz
EOF
dev/harness/scripts/build-phoenix.sh && dev/harness/scripts/stage-artifacts.sh
```

Adding `PX_PHOENIX_HBASE_PROFILE=2.5` to that file selects an alternate build profile along with
that profile's default runtime release unless an explicit release version is also specified. Passing
a `--config FILE` containing the same assignments applies those overrides to a single command
invocation. Because a newly re-pinned release lacks a predeclared checksum pin, its initial download
uses trust on first use and records the observed hash in `$PX_OUT/dist/pins.env`. To promote that
hash to a declared pin, copy the digest into `versions.local.env` under a variable such as
`PX_DIST_HBASE_2_6_8_SHA512_ARM64`, splitting values that exceed a single line across `_2` and
subsequent continuation suffixes. Running `px-cluster config` displays the provenance of each active
version selection.

### The version matrix

The `px-cluster matrix SUITE FILE...` command executes a single test suite across multiple version
sets sequentially. Each `FILE` argument is a configuration file that re-pins the HBase profile, the
runtime distribution releases, or the role JDK versions. For each file in turn, the command builds
Phoenix, stages the container image, provisions the cluster, runs the suite, and tears the cluster
down, evaluating the matrix configuration file ahead of any `--config FILE` options supplied on the
command line. The command emits one summary row per version set, persists the table to
`$PX_OUT/matrix/<run-id>/matrix.tsv`, and records each set's resolved versions within its own suite
session directory.

```sh
px-cluster matrix smoke set-2.6.env set-2.5.env --class validation \
  --config dev/harness/docker/env/override-24gib.env
```

## The cluster network

All cluster instances execute on a dedicated bridge network managed by the harness and never use
host networking. Every instance is assigned a deterministic hostname of the form `<role>-<index>`,
such as `namenode-0`, `regionserver-2`, or `client-0`, and cluster nodes communicate with one
another using these hostnames. Inside its container, each daemon binds its listening ports across
all network interfaces so that peer instances on the bridge network can establish connections.

Every port defined in the port map is published exclusively on the host's `127.0.0.1` loopback
address using the identical port number assigned inside the container. Consequently, a single port
map governs inter-container, host, and client access while ensuring that no cluster service is
exposed outside the host machine. JVM debug ports are published only when the deployment is rendered
with `PX_JVM_DEBUG=1`. During bring-up, the host preflight check aborts provisioning if any
published port is already bound on `127.0.0.1`.

Operators on the host can reach every published port at `127.0.0.1:<port>`, including daemon web
interfaces, JMX servlets, and debug ports, and can query `px-cluster endpoint` to resolve the port
of an instance. Running a JDBC or HBase client directly on the host is not supported because HBase
returns container hostnames to the client that the host system cannot resolve. Instead, run clients
inside the `client` or `tooling` container, for example with
`px-cluster exec client 0 -- /bin/bash`, where the rendered `phoenix-client.properties` file holds
the JDBC URL. The "The network" section of `docs/backends.md` provides the full specification.

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

Exit code 3 serves as the universal anomaly code across every backend. `px-run` captures a snapshot
of every instance before and after the workload, and overrides a verb exit code of 0 or 1 with code
3 whenever an instance not covered by a planned action exhibits one of three state transitions: its
restart count increased, it was running beforehand and is exited, dead, or unknown afterward, or it
vanished. For each affected instance, the run record captures the termination reason and the
out-of-memory indicator so operators can distinguish an unplanned restart from an out-of-memory
kill.

While the `prepare` and `run` verbs are executing, `px-run` also compares the live cluster against
the initial snapshot once every `PX_POLL_INTERVAL_S`. The first unplanned restart, exit, loss, or
out-of-memory kill immediately aborts the run with exit code 3 regardless of what the verb would
return: `px-run` stops the active verb, writes an `abort` row recording the instance and its state
change, and then captures the second snapshot to preserve the failure evidence. Because a workload
running on a broken cluster produces no valid measurements, the run does not wait for the verb to
finish. Following an anomaly abort, `restore` is never executed against the untrusted cluster, and
the `driver.restore` verdict is recorded as `SKIP` with the reason `anomaly abort`.

An action qualifies as planned only when `px-cluster` (`kill`, `signal`, `restart`, `scale`, or
`group`) or a scheduled disruption managed by `px-run` logs an entry in the planned-action record
prior to executing the change. Only rows written during the active run are honored, ensuring that
disruptions from earlier runs never excuse new failures. The schema and semantics of this record are
described in `docs/backends.md`.

The `sb_exec` function returns the exit status of the command it executes and reserves status codes
124 through 127 for substrate framing errors, as detailed in `docs/backends.md`.

## Host safety

Because the harness executes on a workstation that its operator also uses, it enforces host-level
safeguards. These safeguards read host state from standard utilities on `PATH`: `uname`, `uptime`,
`vm_stat`, `df`, `getconf`, and `ps`, while Linux host memory is read from `/proc/meminfo`. The
self-test suite places mock implementations first on `PATH`, and a mock `uname` reporting Darwin
selects the `vm_stat` memory source on any host platform.

**The preflight.** `scripts/preflight.sh` executes a fast phase followed by a memory phase. The fast
phase queries the backend to verify the container engine, client credentials, component versions,
and loopback ports, stopping at the first engine fault. Any refusal in the fast phase terminates the
preflight before the memory phase runs, so each fault returns immediately with its specific exit
code and diagnostic message: code 1 if the engine did not respond, code 5 if the engine refused the
client, or code 3 if a port in the port map is already in use. The memory phase compares the sum of
the container memory limits against total engine memory less 4 GiB, and requires at least
`PX_PREFLIGHT_MIN_DISK_MIB` of free disk space beneath `$PX_OUT` on the host and at least
`PX_PREFLIGHT_MIN_CPUS` processors. The processor requirement is evaluated against the lesser of the
host processor count and the engine processor count, and the diagnostic message reports both values.
Any measurement below its required threshold refuses the deployment with exit code 3 and names both
the resource and the measured value. The active run class defines both requirements.

**Sleep inhibition.** While a cluster is up or a test run is active, a sleep inhibitor keeps the
host awake using `caffeinate -i -s` on macOS or `systemd-inhibit --what=sleep:idle` on Linux, or
emits a warning on a host with neither tool. `px-cluster up` starts the cluster-scoped inhibitor and
every teardown path releases it. `px-run` maintains its own inhibitor for the duration of the run
and releases it across every exit path, including interrupt and termination signals. That inhibitor
also terminates automatically with `px-run` on both platforms, even after a KILL signal: via `-w` on
its process ID on macOS, and under `systemd-inhibit` on Linux via a watchdog command that exits as
soon as the `px-run` process disappears. An inhibitor that exits immediately produces the same
warning as a host with no inhibitor utility. The inhibitor process ID is recorded in
`$PX_OUT/state/inhibit-<holder>`. Releasing an inhibitor stops only that recorded process, and only
while its command line still names the inhibitor, ensuring that an inhibitor started independently
by the operator is never stopped.

**The health monitor.** `px-cluster up` launches `px-tool monitor` for the cluster, and every
teardown stops it. Every `PX_MONITOR_INTERVAL_S` seconds (default 30), the monitor samples the
memory usage of every instance via `sb_sample_resources`, appends the results to `samples.tsv`, and
samples the free memory of the host. A breach is declared only when available host memory falls
below `PX_MONITOR_HOST_FREE_MIB` (default 1024), because host memory exhaustion compromises the
entire workstation, whereas an instance reaching its own memory limit remains confined to its
container where `px-run` reports its out-of-memory kill. Upon detecting a breach, the monitor writes
the triggering sample to `breach.tsv` in its run directory at `$PX_OUT/monitor/<run-id>`, tears the
cluster down with `px-cluster down`, and exits with code 3. Any active `px-run` copies that
`breach.tsv` file into its own run directory and references it in its abort row. Two concurrent
`px-cluster down` invocations against the same scope execute sequentially, and the second call
succeeds as already down. Each sampling pass also refreshes the heartbeat file
`$PX_OUT/state/monitor-<scope>.heartbeat`, whose age is verified by the monitors subject of the
bootstrap probe. The setup instance reads this heartbeat through a read-only mount of
`$PX_OUT/state`, and the probe accepts a heartbeat age of up to three monitor intervals.

**The quiet-host check.** Timing measurements are meaningful only on a quiet host. After `validate`
and before `prepare`, `px-run` invokes `px-tool quiet-host`, which compares the host's one-minute
load average, expressed as a percentage of its processor count, against `PX_QUIET_HOST_LOAD_PCT`.
Two settings govern this check: the wait duration `PX_QUIET_HOST_WAIT_S` bounds how long the check
pauses for the load to subside (a wait of 0 takes a single sample), and `PX_REQUIRE_QUIET_HOST=1`
causes the run to fail with exit code 4 if the host remains busy when the wait expires. The check is
disabled and takes no sample only when the wait duration is 0 and no quiet host is required, which
is the configuration of the validation run class. Both a disabled check and a busy host on a run
that does not require a quiet host mark the run as contaminated: the run record receives a
`contamination quiet-host` row, any failed timing verdict of the run becomes ADVISORY, and every
verb receives `PX_CONTAMINATION=quiet-host`. The check outputs its load samples, followed by a
`quiet_host STATE SAMPLES ELAPSED LOAD` summary row reporting a state of `quiet`, `contaminated`,
`refused`, `unmeasured`, or `off`, and concludes with a `result STATUS CODE host` row where the
status is `ok` for a quiet host, `warn` when the host is contaminated or unmeasured, `fail` when a
required quiet host is refused or unmeasured, and `skip` when the check is disabled; `px-run`
persists the `quiet_host` row in its run record. A measurement run is also marked contaminated
whenever
its sizing diverges from the standard measurement table: through an override of `PX_<KEY>_MEM_MIB`,
`PX_<KEY>_HEAP_MIB`, or `PX_<KEY>_DIRECT_MIB` in its own configuration, through an override named in
the cluster's topology record (such as `override-24gib.env`), or by running against a cluster
rendered for another class. In that case, the run record receives a `contamination sizing` row
identifying the overrides. Each metric record captures both the run class and the contamination
state at the time it is written. `px-report` demotes any failed verdict of class `duration` to
`ADVISORY` unless the run belongs to the measurement class and has no contamination row, whereas
count, share, and functional checks remain authoritative on any host. The measurement class waits up
to 300 seconds and requires a quiet host.

## The host memory check

Starting an additional role group commits its container resource limits against the container
engine; accordingly, `px-cluster group start`, `px-pherf start`, and the `requires` directive
handling in `px-suite` execute `scripts/preflight.sh --phase memory` prior to starting each group
via `px_memory_recheck` in `lib/common.sh`, which defines the invocation contract and its exit
codes. Any exit status other than 0 refuses the group start. This check evaluates only the
`engine_memory` and `engine_cpus` rows reported by the backend preflight, since the full preflight's
host port checks would fail against a cluster that is already running.

The preflight check accounts for every rendered service, including on-demand role groups. In the
measurement class, the Distributed Pherf tier requires 1024 + 3 x 1536 = 5632 MiB. On a 48 GiB
container engine (which provides a 45056 MiB budget), the full sizing table with both YARN and the
tier totals 47616 MiB and is refused; setting `PX_YARN=0` reduces the total to 37376 MiB, while
running 2 RegionServers with YARN totals 43008 MiB. On a 24 GiB engine configured with
`docker/env/override-24gib.env`, only `PX_YARN=0` with 1 RegionServer fits within the budget, using
17920 of 19905 MiB.

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

`px-report [BUNDLE]` merges the verdict streams for a single run using this lattice after applying
the demotion rule described above, and writes `report/verdicts.tsv`, the run summary, and the
cluster summary into the result bundle; when invoked without an operand, it defaults to reading the
newest completed bundle and concludes its output with a `result RESULT CODE BUNDLE` row. See the
"Verdict severity" section of `docs/bundle-format.md` for the four steps of the merge procedure.

`px-suite` applies the same lattice to a suite session. Each test's process exit code maps onto an
initial severity: 0 maps to `PASS`, 5 maps to `SKIP`, 2, 3, and 6 map to `ERROR`, and every other
exit code maps to `FAIL`. A test that recorded verdicts assumes the worst verdict in its stream
whenever that severity is worse; for a driver test, those are the effective rows of
`report/verdicts.tsv` in its bundle. `px-suite` rejects any verdict row whose severity is outside
the lattice and elevates the test outcome to `ERROR`. The session assumes the worst test severity,
with one exception: a session in which no test verified anything, either because every test resolved
to `SKIP` with no verdict of another severity or because no test ran, concludes with the result
`INCONCLUSIVE`. A driver test whose executed checks passed alongside the `SKIP` rows of its
unsupported checks resolves to `SKIP` but still counts as having verified checks. `INCONCLUSIVE` is
a summary result rather than a verdict severity, and no individual verdict may carry it. Both
`px-suite` and `px-report` print the final result and exit with code 0 for `PASS`, `SKIP`, and
`ADVISORY`, code 1 for `FAIL` and `INCONCLUSIVE`, and code 3 for `ERROR`. Each test result row
emitted by `px-suite` records the test's bundle in a seventh column—left empty if the test did not
run—and `results.tsv` persists the same rows; a session that runs to completion concludes standard
output with a `result RESULT CODE SESSION_DIR` row, whereas an interrupted session emits no closing
row.

## Self-test

The self-test suite in `test/run-tests.sh` executes without a container runtime, network access, or
a prior Maven build, allowing it to run both on a developer laptop and inside the pre-commit
container. It emits TAP-formatted test output and exits with code 1 if any assertion fails. Among
its architectural guarantees, the suite verifies through three independent enforcement layers that
no component outside the active backend directly invokes a container runtime or a Kubernetes tool: a
static source scan paired with a reasoned allowlist that also fails on stale entries, a runtime
trap that forces any stray invocation to exit with code 2, and a fake runtime shim exercised against
a negative control, a hostile driver, and a positive control. The design of these three layers is
documented in `docs/backends.md`. Against the same fake runtime, the suite exercises every substrate
contract function and every `px-cluster` subcommand, and its final test group proves that no command
created any new path beneath the root of the working tree.
