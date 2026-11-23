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

# Substrate backends

The substrate contract serves as the sole interface between the test harness and the underlying
container execution environment. This contract is declared in `lib/backend/contract.sh` and
implemented in `lib/backend/<name>.sh`, with one implementation file dedicated to each backend.
Because every layer above the contract interacts exclusively through its 31 `sb_*` functions,
drivers, checks, plug-in hooks, health monitors, collectors, and operator commands execute without
modification across all backends, and no component above the contract requires a container runtime.

Implementing a second backend requires only this document and the shelldoc in
`lib/backend/contract.sh`, which together specify the exit codes, output schemas, deployment
directory layout, network model, service labels, port map, and runtime boundary rules.

* `contract.sh` serves as the single source of truth for function names, signatures, output schemas,
  value vocabularies, and exit codes, with the shelldoc on each `sb_*` function acting as the
  definitive function reference. It declares these definitions, binds a single backend per process,
  and verifies the completeness of that binding. In addition, it manages the two state records
  shared across all commands: the scope record and the planned-action record.
* `compose.sh` implements the Docker Compose backend and is the only file in the repository
  permitted to invoke a container runtime directly.
* The Kubernetes backend has not yet been implemented. Its section at the end of this document
  presents the planned architectural mapping, which milestones K1 and K2 will complete.

## Binding a backend

```sh
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/backend/contract.sh
. "$SELF/../lib/backend/contract.sh"   # sources lib/common.sh itself
sb_load                                 # binds ${PX_BACKEND:-compose}
```

Sourcing `contract.sh` does not bind a backend; it only declares the contract definitions, ensuring
that commands that merely print configuration never touch a backend implementation. Calling
`sb_load` establishes the backend binding:

| Function | Purpose |
|---|---|
| `sb_load [backend]` | Binds a single backend; defaults to `${PX_BACKEND:-compose}`. |
| `sb_bound_backend` | Outputs the bound backend name on stdout, or returns 6 if none is bound. |
| `sb_verify_backend` | Lists missing function names on stdout; returns 0 if complete, 1 if not. |
| `sb_contract_functions` | Emits the 31 contract function names, one per line. |

If given an invalid or unknown backend name, `sb_load` terminates the process with exit code 2 and
reports the implementation file path it attempted to locate. It terminates the process with exit
code 1 if the backend omits any contract function, listing the missing function names. A subsequent
`sb_load` call naming the already-bound backend returns exit code 0 so that commands can invoke it
idempotently without guards, whereas a subsequent call naming a different backend terminates the
process with exit code 1 because each process may bind only a single backend.

Every contract function is required. Because both backends ship within the same repository tree, the
contract carries no version number: any signature change updates both backends in the same commit. A
backend whose underlying substrate cannot support a particular operation must still define the
function and return exit code 5 with an explanatory message on stderr—for example, `sb_logs` with
`previous=1` on Compose—which callers record as a skipped operation.

## Exit codes

Every `sb_*` function adheres to a unified exit-code vocabulary. Callers branch exclusively on the
numeric exit code, while the underlying cause is recorded in diagnostic messages, cluster-check
verdicts, and the bundle manifest.

| Code | Constant | Name | Meaning |
|---|---|---|---|
| 0 | `SB_EXIT_OK` | ok | The operation completed its intended action successfully. |
| 1 | `SB_EXIT_FAIL` | failure | The operation failed, or the container runtime failed to respond. |
| 2 | `SB_EXIT_USAGE` | usage | The arguments are invalid, or a specified mode is unknown. |
| 3 | `SB_EXIT_ANOMALY` | anomaly | An instance terminated or restarted without being requested. |
| 4 | `SB_EXIT_TIMEOUT` | timeout | A finite wait operation expired before completion. |
| 5 | `SB_EXIT_UNSUPPORTED` | unsupported | The backend cannot perform it on its substrate. |
| 6 | `SB_EXIT_NO_OBJECT` | no such object | The scope, role, index, port, or value is absent. |

When a container runtime fails to respond, functions return failure code 1; returning code 6
requires an affirmative response from the runtime confirming that the target object does not exist.
Two functions deviate from this rule, as documented in their shelldoc: `sb_exec` reports an
unresponsive runtime as exit code 125, while `sb_sample_resources` reports it as exit code 5.

Exit code 3 serves as the single cluster-anomaly code across every backend. Each backend reports
the underlying diagnostic evidence—the restart count, the termination reason, and the
out-of-memory verdict—while `px-run` evaluates that evidence to make the anomaly decision. While
the workload driver executes, `px-run` continuously compares the active cluster against its initial
snapshot, and the first unplanned restart, unexpected exit, instance loss, or out-of-memory kill
aborts the run at once with exit 3. Section "Exit codes" in the README defines this rule,
and the driver contract in A5 lists the code.

`sb_exec` is the sole contract function that propagates an external exit status, returning the exit
status of the command it executed inside the instance. Four exit codes remain reserved so that
callers can distinguish a substrate execution-framing failure from a command execution failure. A
program running inside a role must never exit with any of these four codes. Because `sb_exec`
forwards its standard input to the executed command, any caller invoking it inside a loop that reads
rows from standard input must redirect the call's input from `/dev/null`.

| Code | Constant | Meaning |
|---|---|---|
| 124 | `SB_EXEC_TIMEOUT` | The wait expired, following the coreutils `timeout` convention. |
| 125 | `SB_EXEC_NOSTART` | The substrate was unable to start the requested command. |
| 126 | `SB_EXEC_NOEXEC` | The target binary exists inside the instance but is not executable. |
| 127 | `SB_EXEC_NOTFOUND` | The target binary could not be found inside the instance. |

Calling `sb_exit_name <code>` translates any defined exit code above into its canonical name and
reports any other numeric value as `command status`.

## Schemas

Every contract function that outputs structured data emits tab-separated value (TSV) rows formatted
via `px_tsv_row`, which rejects any field containing a tab or newline character. Free-form message
text is sanitized through `px_tsv_clean` prior to emission. Missing values are represented as empty
columns rather than omitted columns, ensuring a fixed column count for parsers.

The field ordering specified below is normative, and callers resolve columns symbolically rather
than hardcoding numeric indices:

```sh
mem="$(px_tsv_field "$(sb_schema_index sample mem_bytes)" "$row")"
```

Each entry below lists the schema identifier, its ordered field names, and the contract functions
that produce rows conforming to that schema.

```text
instance    role index instance_id state ready image_ref node
            sb_list, sb_provision, sb_scale
ready       role index ready elapsed_ms
            sb_wait_ready, sb_group_start
stopped     role index stopped
            sb_group_stop
group       group running desired state
            sb_group_status
phase       phase status elapsed_ms
            sb_restart_drain
term        reason exit_code signal finished_at
            sb_termination_reason
oom         oom evidence
            sb_oom_killed
sample      ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes
            tx_bytes blk_read_bytes blk_write_bytes
            sb_sample_resources
check       check status detail
            sb_preflight
kv          key value
            sb_version_info
endpoint    host port
            sb_endpoint
image       image_ref image_id
            sb_image_ref, sb_image_build, sb_image_present,
            sb_image_base_digest
restart     role index count
            sb_restart_count
```

`sb_schema_fields <schema>` outputs the schema's field names in order,
`sb_schema_index <schema> <field>` returns the 1-based column index for a field, and
`sb_schema_count <schema>` returns the total number of columns in the schema. All three helpers
return exit code 6 when given an unknown schema or field name.

In these schemas, `instance_id` is an opaque identifier owned by the backend, `node` identifies the
host executing the instance, and `elapsed_ms` records the duration of the wait operation rather
than the lifetime of the instance.

## Value vocabularies

Each backend normalizes its runtime-specific terminology into fixed value vocabularies so that
higher-level checks observe identical states across all substrates. `sb_vocabulary <kind>` lists
the permitted values for a given vocabulary kind and returns exit code 6 if the kind is unknown.
`sb_valid_value <kind> <value>` returns exit code 0 when the value is valid, 1 when invalid, and 6
when the vocabulary kind is unknown; backends use this helper to validate mode arguments and return
exit code 2 when given an invalid value.

| Kind | Words |
|---|---|
| `state` | `pending running stopped exited unknown` |
| `ready` | `ready unready stopped` |
| `stopped` | `stopped already_stopped` |
| `group_state` | `up down partial` |
| `phase_status` | `ok fail skip` |
| `check_status` | `ok fail warn` |
| `term_reason` | `running completed error oom killed evicted unknown` |
| `kill_mode` | `hard delete` |
| `term_which` | `current previous` |
| `yes_no` | `yes no` |

The `stopped` value appears in the `ready` column exclusively during a whole-scope `sb_wait_ready`
call, where it denotes an instance belonging to a role group that is intentionally stopped.

## Scope records and planned actions

Two shared state records reside in the state directory `$(px_state_dir)` beneath `$PX_OUT`. Because
`contract.sh` manages both files directly, all commands and backends access them uniformly, and
commands invoked in separate shell sessions observe state updates written by prior invocations.

### The scope record and scope resolution

Every cluster name must match the regular expression `SB_CLUSTER_RE` (`^[a-z0-9][a-z0-9_-]*$`), as
the cluster name is embedded directly into scope identifiers, container labels, and state file
names.

`sb_scope_record [cluster]` outputs the filesystem path to the scope record for a cluster:
`$(px_state_dir)/scope-<backend>-<cluster>`. The backend segment resolves to the currently bound
backend, falling back to `PX_BACKEND` and then `compose`; the cluster segment resolves to the
supplied argument, falling back to `PX_CLUSTER_ID` and then `default`. The first line of this file
stores the active scope identifier. `px-cluster up` writes the file immediately after
`sb_scope_create` succeeds, and `px-cluster down` deletes it after `sb_scope_delete` completes. The
function returns exit code 0 on success, 2 if the cluster name is invalid or the state directory
path is rejected, and 1 if the state directory cannot be created.

`sb_scope_resolve` determines the target scope identifier for a command. Every command that operates
against a cluster (`px-cluster`, `px-run`, `px-suite`, `px-tool`, `px-collect`, and `px-pherf`)
follows this unified resolution order:

1. `PX_SCOPE_ID`, if explicitly set, allowing an operator to target a scope that was never recorded
   locally on the current host;
2. otherwise, the first line of the scope record corresponding to the bound backend and
   `PX_CLUSTER_ID`;
3. otherwise, exit code 6, emitting no message so the caller can report the missing context.

The function returns exit code 2 if `PX_CLUSTER_ID` is invalid or the state directory is rejected,
and 1 if the state directory cannot be created. Higher-level commands never construct scope
identifiers manually.

`sb_deployment_record [cluster]` outputs the path to a second per-cluster state record,
`$(px_state_dir)/deployment-<backend>-<cluster>`, whose first line stores the deployment directory
provisioned by `px-cluster up`. `px-cluster down` removes this file alongside the scope record.
Host preflight reads this record so that the memory check performed before starting a role group
sums the resource limits of the active deployment, regardless of where it was rendered. Its exit
codes match those of `sb_scope_record`.

### The cluster record

Immediately after writing the scope record, `px-cluster up` writes a third per-cluster state file:
`$PX_OUT/$PX_STATE_DIR/cluster-<cluster>.env`. Because the filename encodes no backend identifier,
subsequent commands discover the active backend directly from this file, and cluster names are
unique across all backends within a given `PX_OUT` directory. The record persists the bring-up
configuration as flattened `NAME='VALUE'` assignments covering every setting supplied on the
command line, in `--config` files, and in the cluster target file, together with `PX_BACKEND` and
`PX_RUN_CLASS`. Every command's configuration loader evaluates this file at stage 2b under the
`cluster` origin—immediately after the target file and prior to `local.env` (see "Configuration
precedence" in the README)—allowing the scope record for the recorded backend to resolve without
requiring `--backend`. If an explicitly supplied `PX_BACKEND` conflicts with the recorded backend,
the loader terminates with exit code 2 and reports the precedence stage that defined the
conflicting value. Running `px-cluster scale` updates the replica count in the record,
`px-cluster down` removes the record alongside the scope record, and `px-cluster down --keep`
preserves it. Invoking `px-cluster up` when the target cluster's scope already exists fails with
exit code 2 even if `PX_FORCE=1` is set; if the scope no longer exists, the cluster record is
treated as stale, so `up` accepts it only when `PX_FORCE=1` is set and `px-cluster down` removes
it. Both `px-cluster render` and `px-cluster push` write their render-time settings to `render.env`
in the deployment directory as `NAME='VALUE'` assignments, and `px-cluster up DIR` merges those
entries into the cluster record at a lower precedence than its own settings. When no cluster is
recorded, `sb_scope_require` exits with code 6 and the diagnostic hint
`no cluster ID is recorded: run px-cluster up, or name another with --cluster`, whereas
`px-cluster down` exits with code 0.

### Planned actions

Intentional lifecycle operations deliberately alter instance states and restart counts. To prevent
health checks from misinterpreting these changes as unexpected failures, every intentional action
is logged before execution in `$(px_state_dir)/planned-<scope>.tsv`, with any characters in the
scope identifier that are unsafe for file names replaced by `_`. `px-cluster` logs its `kill`,
`signal`, `restart`, `scale`, and group-switch operations, while `px-run` logs every scheduled fault
disruption. Each entry is stored as a TSV row with fields `ts_ms action role index detail`.

* `sb_planned_record scope action [role] [index] [detail]` appends a single planned-action row and
  writes a log entry. An empty index matches all instances of the specified role, whereas an empty
  role matches no instances; consequently, a caller operating on an entire role group writes a
  separate row for each role in that group. It returns exit code 0 on success; 2 if the scope or
  action is empty or the state directory is rejected; and 1 if any field contains a tab or newline,
  the file write fails, or the state directory cannot be created. The function always returns a
  status code rather than terminating the process so the caller can decide how to proceed;
  `px-cluster` refuses to initiate any disruptive action whose planned-action row cannot be
  recorded.
* `sb_planned_contains scope role index [since_ms]` returns exit code 0 if at least one row with
  `ts_ms >= since_ms` matches the specified role and either the exact index or an empty wildcard
  index, and returns 1 if no matching row exists or the file is absent. It returns exit code 2 if
  the scope or role is empty, the start timestamp is not an integer, or the state directory is
  rejected, and returns 1 if the state directory cannot be created. `px-run` supplies the epoch
  millisecond timestamp captured immediately prior to its pre-run cluster snapshot, ensuring that
  records left over from earlier test runs or previous clusters sharing the same scope identifier
  do not mask unplanned restarts.

## The deployment directory

The rendered deployment directory forms the sole integration boundary between a manifest generator
and the Compose backend. `sb_provision` accepts the path to this directory, and the backend records
both the directory path and the compose file path inside its own scope record so that subsequent
processes can reach the same deployment. Because the backend reads exclusively the artifacts listed
in this section and nothing else—with scaling operations also reading `services.tsv`—a hand-written
test deployment provisions identically to a generated one when it conforms to these rules, including
the memory limit requirement described below. Treated as run output, the deployment directory is
rendered beneath `$PX_OUT` by the generator. This section is normative for the generator.

The generator for the Compose backend is `docker/compose.gen.sh`, invoked by `px-cluster render`.
It exclusively writes files to the deployment directory, and the backend reads no file that is not
specified in this section.

When plug-ins are enabled (`docs/PLUGINS.md`), the generator also executes their render hooks,
aborting the render with exit code 2 if an `image-check` hook fails. The generator writes plug-in
records to `plugins/<id>/`, merges the overlay templates produced by the `conf-overlay` hook into
`conf-overlay/`—which every service mounts read-only and references via `PX_CONF_OVERLAY_DIR` for
`docker/image/render.sh` to merge—and appends the options from `PX_<KEY>_JVM_OPTS` and the
`jvm-opts` hook to `PX_JVM_OPTS_EXTRA` on each service belonging to the target role. The backend
reads neither directory.

### compose.yaml

The root of the deployment directory must contain `compose.yaml`; no alternative file names are
recognized, and a directory lacking it fails with usage exit code 2. On every Compose invocation,
the backend passes the project name `<PX_PROJECT_PREFIX>-<cluster>` along with the file path and the
directory as the project directory while the file exists, so the file requires no embedded project
name of its own. Every service sets its restart policy to `no` so that a terminated instance remains
terminated and restart evidence stays meaningful; the backend neither restarts instances
automatically nor validates the configured policy. Because the container runtime counts only
policy-driven restarts, `sb_restart_count` also tracks each container's most recent start timestamp
in `$(px_state_dir)/compose/starts-<scope>.tsv` and increments the count by 1 whenever that
timestamp changes between calls: an external restart initiated outside the harness, such as
`docker restart`, leaves the container state as `running` and alters only its start time.
`sb_scope_delete` removes this state table. A service that declares a healthcheck derives its
readiness from that probe, whereas a service without one is considered ready while running, and a
one-shot service that exited with status 0 is considered ready as well.

Every service must declare a memory limit via `mem_limit` or `deploy.resources.limits.memory`,
formatted in YAML block style with one key per line. `px-cluster up` runs the host preflight check
against the deployment directory first and rejects any deployment containing a service whose memory
limit cannot be parsed, exiting with code 1.

### The network

The harness manages the deployment network. `compose.yaml` declares a single network—the project's
default network—configured with the `bridge` driver and the cluster label, and attaches every
service to it. Compose names this network `<project>_default`, matching the identifier stored in
the scope record so that teardown removes it cleanly. No service uses host networking.

Every service, including the setup and client roles, is assigned a stable hostname `<role>-<index>`
that matches its service name, such as `regionserver-2` or `client-0`. Because the container engine
resolves these names across the network, instances address one another by hostname: the ZooKeeper
quorum at `zookeeper-0`, `fs.defaultFS` on `namenode-0`, the ResourceManager on `resourcemanager-0`,
the JobHistoryServer on `jobhistory-0`, the master at `hbase-master-0`, and each RegionServer,
DataNode, and NodeManager under its own hostname. The container image receives the instance hostname
in `PX_HOSTNAME` and the bind address in `PX_BIND_HOST`, which is `0.0.0.0`. Every daemon binds its
service ports on all interfaces inside its container through the bind-host and bind-address
properties of HDFS, YARN, MapReduce, and HBase, along with ZooKeeper's `clientPortAddress`, allowing
peers on the network to connect. A self-targeted health probe connects to `127.0.0.1` inside the
container, whereas a probe targeting another instance—such as the setup role's bootstrap probe—uses
that target instance's hostname. All health probes ignore environment proxy settings. Because
commands invoked via `sb_exec` (such as the region mover or `px-cluster exec`) do not inherit the
environment exported by the container entrypoint, the image defines `HADOOP_CONF_DIR`,
`YARN_CONF_DIR`, and `HBASE_CONF_DIR` pointing to the rendered configuration so that executed
commands also address each peer by hostname.

Every port in the port map is published exclusively on the host's loopback address as
`"127.0.0.1:<port>:<port>"`. Keeping the container port identical to the host port ensures that the
base-plus-step port map remains the single source of truth for containers, the host, and every
client. No port is published on any other host interface, preventing access to cluster services
from outside the host. A role's debug port is included in the port map—and therefore published—only
when `PX_JVM_DEBUG=1` is set at render time.

From the host, the operator accesses every published port at `127.0.0.1:<port>`, which matches the
address returned by `sb_endpoint`: the web interfaces for HDFS, YARN, the JobHistoryServer, and
HBase, the JMX servlets, and any requested debug port. Running a JDBC or HBase client directly on
the host is not supported. Because HBase returns the internal hostnames of the master and
RegionServers—which resolve only on the harness network—clients must run inside the `client` or
`tooling` container, where the rendered `phoenix-client.properties` file contains the JDBC URL.

### Service labels

All harness label keys begin with `PX_LABEL_PREFIX`, which defaults to `px.harness`. Every service
definition includes the following entries in its `labels` block so that the resulting containers
inherit them.

| Label | Value | Required | What reads it |
|---|---|---|---|
| `px.harness.cluster` | The cluster id. | yes | Every selection; this label defines the scope. |
| `px.harness.role` | The logical role name. | yes | Every contract function that targets a role. |
| `px.harness.index` | The zero-based instance index. | no | Instance index resolution. |
| `px.harness.group` | The role group name. | no | `sb_group_*` calls and whole-scope waits. |
| `px.harness.start` | `on-demand` | no | `sb_provision`, which leaves matching services stopped. |
| `px.harness.drain` | `regionmover` | no | `sb_restart_drain`, which drains the target role. |

* `cluster`. If a provisioned deployment produces no containers carrying the scope's cluster label,
  `sb_provision` fails with exit code 2. Declared volumes and the project network also carry this
  cluster label so that teardown and `sb_scope_delete` can identify and remove them.
* `role`. When this label is omitted, the backend defaults to the Compose service name, mapping one
  service directly to one role.
* `index`. When omitted, the instance index is derived by subtracting one from the Compose
  container replica number. Any role managed by `sb_scale` must omit this explicit label because
  all replicas of a scaled Compose service share identical service labels and would otherwise
  receive colliding indices. Conversely, a role whose services carry an explicit index label
  defines a separate Compose service per instance, and its instance count changes only by rendering
  a new deployment.
* `group`. Services sharing a group label are started and stopped as a unit. A group is considered
  intentionally stopped when none of its member instances are running or restarting and either
  `sb_group_stop` stopped the group or the group is an on-demand group that no test step has
  started. A whole-scope readiness wait immediately reports members of an intentionally stopped
  group as `stopped` rather than blocking on them, whereas a group whose members crashed
  unexpectedly is never treated as intentionally stopped.
* `start`. During provisioning, `sb_provision` creates all services and starts every service except
  those whose start label is `on-demand`. Such a service remains created and stopped until
  `sb_group_start` activates its group, and therefore must also declare a group label.
* `drain`. Only roles whose drain label is `regionmover` support graceful region draining; invoking
  `sb_restart_drain` on any other role fails with exit code 2.

### The port map

The deployment generator writes all published port mappings to `ports.generated.env` at the root of
the deployment directory. The file contains `KEY=VALUE` assignments, comments, and blank lines
following the standard configuration file grammar. Each variable is named
`PX_PORT_<ROLE>_<PORT>_<INDEX>` for an individual instance or `PX_PORT_<ROLE>_<PORT>` for a
single-instance role, where `<ROLE>` and `<PORT>` are the uppercase role and port identifiers with
`.` and `-` converted to `_`. When both forms are present, the indexed variable takes precedence
over the unindexed one. The assigned value specifies both the internal container port of the
instance and the host port published on 127.0.0.1. Port numbers are calculated exclusively by the
generator; the backend never computes a port. The port map defines the exact set of published
ports: `compose.yaml` publishes every port listed in the map and no others.

Certain internal container bindings are intentionally absent from the port map. The application
master of each MapReduce job executes inside a NodeManager container and binds three sockets on
ephemeral ports: the client RPC port of `MRClientService`, the task umbilical port of
`TaskAttemptListenerImpl`, and the AM web application port. Hadoop provides no configuration
setting to pin these ports. Because they bind across all interfaces of the NodeManager container,
they remain isolated to the private harness network, and none of them are published on the host.
The MapReduce shuffle handler likewise binds all container interfaces on the `shuffle` port defined
in the port map.

No HTTPS endpoints are bound because no configuration template sets an HTTPS policy, leaving all
policies at `HTTP_ONLY`. The self-test suite verifies that every published port conforms to
`127.0.0.1:<port>:<port>`, that all rendered configurations address peers by hostname and bind
`0.0.0.0`, and that no template configures an HTTPS policy.

The configuration loader ingests this file at stage 8 (see "Configuration precedence" in the
README), and the backend reads it from the deployment directory named in its scope record. Any line
rejected by the loader terminates the process with exit code 2. A missing port-map file is not an
error: in its absence, `sb_endpoint` returns exit code 6 and `sb_preflight` reports a warning.
Additionally, `sb_restart_drain` looks up the instance's `rpc` port via `PX_PORT_<ROLE>_RPC_<INDEX>`
whenever the region-mover command template uses `{port}`.

### The topology record

The deployment generator writes `topology.env` at the root of the deployment directory. While
`compose.yaml` attaches it as the environment file for every service, the backend itself does not
read its contents. The file supplies every service with the bind address `PX_BIND_HOST` and the
debug flag `PX_JVM_DEBUG`. Alongside the topology parameters, it records the deployment's version
set for preservation in diagnostic bundles: `PX_PHOENIX_HBASE_PROFILE`, the HBase profile of the
Phoenix build; `PX_DIST_HBASE_VERSION` and `PX_DIST_HADOOP_VERSION`, the daemon release versions for
HBase, HDFS, and YARN; and `PX_ZOOKEEPER_VERSION` and `PX_HBASE_HADOOP_VERSION`, the ZooKeeper and
Hadoop client JAR versions shipped within the HBase distribution. The latter two values are read
from the image staging record, `$PX_OUT/images/<context>.staged.tsv`, and default to `unstaged`
prior to image staging. If a staging record corresponds to a different profile or release, rendering
fails with exit code 2 so that the topology record never declares a version absent from the image.
Section "Versions and the HBase profile" in the README defines this version set. In addition,
`PX_RUN_CLASS` records the run class used during rendering, while `PX_SIZING_OVERRIDES` lists each
sizing variable—`PX_<KEY>_MEM_MIB`, `PX_<KEY>_HEAP_MIB`, or `PX_<KEY>_DIRECT_MIB`—that differs from
that class's configuration file. `px-run` reads both fields from the bundle copy and marks any
measurement run executed on a cluster of a different class or with sizing overrides as contaminated.

## The functions

The shelldoc on each `sb_*` function in `lib/backend/contract.sh` and `lib/backend/compose.sh`
serves as the definitive function reference: its `## @param` lines specify the arguments, and its
`## @return` line specifies the stdout schema and all possible exit codes. `sb_load` rejects any
backend that omits any of the 31 contract functions. Callers always record exit code 5 as a skipped
operation rather than a failure. The behavioral rules below apply across all contract functions.

Every function that operates on a cluster takes the scope identifier returned by `sb_scope_create`
as its first argument. Missing or malformed arguments result in exit code 2, as does passing a
scope identifier that does not belong to the active backend—for Compose, any identifier lacking the
`<PX_PROJECT_PREFIX>-` prefix.

All timeout parameters are optional. When a timeout argument is omitted or empty, the function uses
the corresponding configured default listed below; when supplied, it must be a positive integer
number of seconds or the function returns exit code 2. Default timeout values are defined in
`docker/env/defaults.env`, and the Compose backend falls back to these same built-in values if
invoked without running the configuration loader:

| Name | Seconds | Used by |
|---|---|---|
| `PX_TIMEOUT_PROVISION` | 900 | `sb_provision` |
| `PX_TIMEOUT_READY` | 600 | `sb_wait_ready` and the readiness phase of `sb_restart_drain` |
| `PX_TIMEOUT_EXEC` | 600 | `sb_exec` |
| `PX_TIMEOUT_SCALE` | 600 | `sb_scale` |
| `PX_TIMEOUT_GROUP` | 600 | `sb_group_stop`, `sb_group_start` |
| `PX_TIMEOUT_DRAIN` | 600 | The drain and rebalance phases of `sb_restart_drain` |
| `PX_TIMEOUT_TEARDOWN` | 300 | `sb_teardown`, `sb_scope_delete` |
| `PX_TIMEOUT_STOP` | 120 | The stop grace period for teardown, group stop, kill, and signal |
| `PX_TIMEOUT_DEFAULT` | 300 | Non-following `sb_logs`, `sb_copy_in`, `sb_copy_out` |
| `PX_TIMEOUT_BUILD` | 3600 | `sb_image_build` |

When `PX_DRY_RUN=1` is active, any function that creates, removes, stops, starts, signals, or
copies artifacts logs the intended operation and returns exit code 0 without emitting data rows,
except that `sb_scope_create` still outputs the scope identifier it would have created.

Where the high-level design document refers to abstract substrate verbs in prose, `cp` maps to
`sb_copy_in` and `sb_copy_out`, `kill_role` maps to `sb_kill`, `restart_role` maps to
`sb_restart_drain`, `oom_detect` maps to `sb_oom_killed`, `inspect` maps to `sb_list`, and all
remaining verbs map directly to their matching `sb_*` function names.

**Signals.** `sb_signal scope_id role index name` delivers `KILL` and `TERM` directly to the
instance so that they terminate it just as a kill or stop operation does. Every other signal is
sent to the daemon JVM—the oldest `java` process in the instance—because the initial process of the
image is `tini`: `tini` forwards catchable signals to its child process, whereas `STOP` would halt
only `tini` itself. After sending `STOP` or `TSTP`, the function polls the JVM process state for up
to 5 s and returns exit code 1 unless the JVM is stopped; after sending `CONT`, it returns 1 unless
the JVM is running. Consequently, a pause that does not take effect is reported as a failure rather
than a success, and the error message distinguishes a signal that could not be delivered from a
process state that did not appear within 5 s. Targeting an instance with no JVM returns exit code
6. Because a paused JVM cannot respond, the instance's health probe—which queries its management
server with a bounded wait—also reflects the paused state.

**Scaling.** `sb_scale scope_id role count [timeout_s]` reads the target role's instance definitions
from `services.tsv` in the recorded deployment directory, after `px-cluster scale` first re-renders
the deployment with the updated replica count. The operation creates and starts only the newly added
instances, or stops and removes only the excess instances along with their mounted project volumes.
If the requested count does not match the rendered deployment, the function returns exit code 2.
When scaling an on-demand role whose existing instances are all stopped, `sb_scale` creates the new
instances in a stopped state without starting them.

**Images.** `sb_image_build` constructs a single local container image from a staged build context
without pushing to any registry, rejecting any image tag that includes a registry hostname or a
digest. The container engine builds for the host architecture; arm64 is the primary architecture
while amd64 is supported on a best-effort basis, which the staging step notes in its
output when executing on an amd64 host.

## The runtime boundary

**Only a backend implementation may invoke a container runtime or a Kubernetes tool.** In the
current repository, `lib/backend/compose.sh` is the sole file permitted to reference any of the
eight tool binaries `docker`, `docker-compose`, `podman`, `nerdctl`, `kubectl`, `k3d`, `helm`, and
`crictl`, or either of the engine variables `PX_DOCKER_BIN` and `_PXC_ENGINE`.

The Compose backend resolves the container engine binary once per process and stores its absolute
filesystem path in `_PXC_ENGINE`, a private shell variable that is never exported. Only Docker is
accepted by this resolution search. If the operator sets `PX_DOCKER_BIN`—conventionally in
`env/local.env`—that setting takes precedence; otherwise, the backend searches `PATH` followed by
`~/.docker/bin`, `/usr/local/bin`, `/opt/homebrew/bin`, and `/usr/bin`. Compose operations always
use the compose subcommand of that resolved engine binary, and the backend never invokes the
standalone `docker-compose` program. Because `PX_DOCKER_BIN` is declared `private` in
`lib/varnames.txt`, the configuration loader populates it as an unexported variable in the current
process, and `compose.sh` scrubs it from the environment upon loading so that child processes cannot
discover the engine binary through it.

Every runtime invocation inside the backend funnels through a single function, `px_compose_call`,
which executes the resolved absolute path either via the `command` shell builtin or under
`px_timeout`. Because an absolute path containing slashes can never resolve to a shell function,
this single call site bypasses the runtime trap described below while any unqualified tool
invocation is caught.

This isolation boundary is not merely a coding convention; it is the architectural invariant that
makes supporting a second backend possible. Consequently, the harness self-test suite enforces the
boundary through three independent verification layers designed so that no single bypass can evade
all three:

1. **Static scan.** `test/run-tests.sh` enumerates every file in the harness that can be executed,
   sourced, or rendered into executable form—including every file with a shell shebang, every file
   under `bin/`, and all `*.sh`, `*.env`, `*.tmpl`, and `*.suite` files. It scans each file for the
   eight prohibited tool names and the three engine variables, ignoring comment lines as well as
   lines annotated with the inline waiver `# px-allow-docker: <reason>`. Any unwaived match outside
   the allowlist fails the test suite and reports the offending file path, line number, and line
   text. The allowlist is maintained inside the test script with one entry per path and a mandatory
   reason, and **stale** allowlist entries also fail the test: an allowlisted path that no longer
   exists or no longer contains a restricted token is removed rather than left behind to grow into a
   loophole. Markdown documentation is excluded from the scan, allowing this document to name the
   tools directly.
2. **Runtime trap.** `lib/common.sh` defines each of the eight tool names as a shell function that
   immediately exits with code 2, reporting the attempted argument list and a diagnostic message
   naming the substrate contract. Because every `px-*` process sources `lib/common.sh`, every
   driver, check, hook, and collector executing within such a process inherits the trap—including
   inside subshells and under `set -e`. External child programs do not inherit shell functions, but
   layers 1 and 3 cover them, and the unexported private engine variable leaves them no path to the
   engine binary.
3. **Fake runtime shim.** The test suite writes a mock `docker` executable into a temporary
   directory, prepends that directory to `PATH`, points `PX_DOCKER_BIN` at the shim, and has it
   append its argument list to an audit log before responding from a fixture state file. Alongside
   it, the test writes mock executables for `kubectl`, `k3d`, `helm`, and `crictl` that append
   their binary name and argument list to the same log, ensuring that any `PATH`-resolved call to
   those tools reaches its fake and populates the log. A negative control exercises every entry
   point that must never touch a container runtime or Kubernetes tool and asserts that the log
   remains empty; in addition, a simulated hostile driver reads `PX_DOCKER_BIN` and invokes
   `command "$PX_DOCKER_BIN" ps`, verifying that the variable is empty and the log remains empty. A
   positive control then executes a real backend call and asserts that the log is **not** empty and
   contains the expected scope label selector, because an empty log alone would prove nothing and
   could merely indicate a broken shim or a `PATH` change that never took effect.

Any requirement for a new file to access the container runtime directly is an architectural design
question rather than a routine allowlist addition. The image build pipeline illustrates this
principle: `scripts/stage-artifacts.sh` stages the build context and invokes `sb_image_build`, so
the build pipeline never references a container runtime directly and the allowlist remains
unchanged.

## Rules a backend author must honour

* **Perform no runtime work when sourced.** `sb_load` sources the backend file before any operator
  intent exists, and the self-test suite verifies that the runtime log remains empty after a bare
  `sb_load`. A backend file must only define functions and execute nothing when sourced.
* **Invoke the underlying runtime through a single call site**, using an absolute binary path stored
  in an unexported private variable, and annotate that call site with the inline waiver comment and
  its justification.
* **Emit TSV rows strictly in the declared schema field order** using `px_tsv_row`, sanitize
  free-form message text with `px_tsv_clean`, and represent missing values as empty columns rather
  than omitting columns.
* **Validate mode arguments with `sb_valid_value`** and return exit code 2 on failure; never forward
  an unvalidated mode value to the underlying substrate.
* **Define every contract function, and return exit code 5 for any operation the substrate cannot
  support**, writing the reason to stderr. Callers record exit code 5 as a clean skip, whereas an
  undefined function causes an unrecoverable failure.
* **Return exit code 6 for an unknown scope, role, index, or port name, and return exit code 1 when
  the runtime fails to respond.** Resource absence must not be conflated with runtime failure, and
  an unresponsive runtime must not be reported as resource absence.
* **Keep every wait operation finite** and return exit code 4 upon timeout expiration. Use
  `px_wait_until` or `px_timeout`, and resolve an empty timeout argument to the corresponding
  configured default.
* **Store all persistent backend state under `px_state_dir`** and validate every copy-out
  destination path with `px_out_resolve`. No backend-owned state or artifact may reside in the
  repository working tree.
* **Read the deployment directory strictly as defined above**, without reading any undeclared files
  from it.
* **Use the shared helper functions** in `lib/common.sh` and `contract.sh` for logging, timeouts,
  retries, temporary directories, platform differences, scope resolution, and planned-action
  tracking; reimplementing any of these utilities introduces duplicate behavior to debug.

A new backend implementation is considered complete when `sb_verify_backend` produces no missing
function names, the self-test contract suite confirms that every required contract function is
defined, and the standard verification test suite passes on the new backend just as it does on
Compose.

## Kubernetes backend

**Status: not yet implemented. Milestone K1 implements the core backend, and milestone K2
implements its preflight checks, safety controls, and image promotion paths.** Milestone K1 adds the
specification sections covering binding, scope management, manifest generation, readiness probes,
command execution and file copy, and scaling; milestone K2 adds the sections covering fault
injection, failure evidence, endpoints, images, preflight checks, and k3d proofing. Everything
earlier in this document is normative for the Kubernetes backend: it implements the exact same 31
contract functions, output schemas, and exit codes without introducing any custom substrate verbs of
its own.

The planned architectural mapping from the design document serves as the baseline design rather than
an immutable commitment:

```text
scope            <context>/<namespace>, the namespace carries the harness label
provision        apply the rendered manifests after a client-side dry run
                 StatefulSets with OnDelete, Jobs for one-shot work
wait_ready       pod readiness, where the probe delegates to the same healthcheck script
exec, logs, cp   the pod's named container, with --previous for previous logs
scale            statefulset replicas; an ordinal keeps an index aligned with Compose
group stop/start replicas to zero and back, which keeps the volume claims bound
kill             hard is a kill against pid 1; delete removes the pod, and OnDelete
                 recreates it only at the harness's direction
restart_drain    region-mover unload, delete the pod, wait for the replacement, load
evidence         restart counts, terminated reason and exit code, pod events; the
                 API already distinguishes an out-of-memory kill from an error
endpoint         a loopback-bound port-forward whose pid is recorded in the state dir
image_ref        the spec reference plus the resolved digest, which promotion reads
```
