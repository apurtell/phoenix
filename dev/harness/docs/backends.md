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
Because every layer above the contract interacts exclusively through its 32 `sb_*` functions,
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
  permitted to invoke a container runtime directly. Through `sb_image_push`, it is also the sole
  component that writes to a container registry.
* `k8s.sh` implements the Kubernetes backend and is the only file in the repository permitted to
  invoke `kubectl` directly. Its dedicated section at the end of this document details its
  architectural mapping and backend-specific differences.
* `bin/px-k3d` is not a backend implementation; it manages the local k3d cluster and is the only
  file permitted to invoke `k3d`.

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
| `sb_contract_functions` | Emits the 32 contract function names, one per line. |

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
and the Compose backend, and this section defines the directory layout produced by the Compose
generator. The Kubernetes backend consumes the directory layout produced by its own generator, which
is defined in the "Kubernetes backend" section. `sb_provision` accepts the path to this directory,
and the backend records both the directory path and the compose file path inside its own scope
record so that subsequent processes can reach the same deployment. Because the backend reads
exclusively the artifacts listed in this section and nothing else—with scaling operations also
reading `services.tsv`—a hand-written test deployment provisions identically to a generated one when
it conforms to these rules, including the memory limit requirement described below. Treated as run
output, the deployment directory is rendered beneath `$PX_OUT` by the generator. This section is
normative for the generator.

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
in the port map. Similarly, the controller of the Distributed Pherf tier binds its agent channel on
port 8790 across all interfaces of `pherf-controller-0`; because only the agents access this
endpoint at `http://pherf-controller-0:8790`, it is not published on the host.

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

### The Distributed Pherf tier

Setting `PX_PHERF=1` renders the Distributed Pherf tier—comprising 1 controller and
`PX_PHERF_AGENT_COUNT` agents (1 to 9 in general, and 1 to 2 in the validation class)—as the
on-demand role group `pherf` of the deployment, defining the roles `pherf-controller` and
`pherf-agent` with the group label `pherf` and the start label `on-demand`. Because `pherf-agent` is
a scaled role, running `px-cluster scale pherf-agent N` changes the agent count. Both services run
the `PX_PHERF_IMAGE_TAG` image built from `docker/pherf`, read no harness environment file, and
receive the `PHERF_*` variables in `phoenix-pherf-distributed/README.md`: `command` set to
`["controller", "start"]` or `["agent",
"start"]`, `PHERF_JAVA_OPTS` with `-XX:MaxRAMPercentage` set to 50 for the controller and 35 for an
agent, the health probe `/opt/pherf/healthcheck.sh <role>`, and the memory limits
`PX_PHERF_CONTROLLER_MEM_MIB` and `PX_PHERF_AGENT_MEM_MIB` with swap limits set equal to memory
limits. The controller declares the `rest` port family, `PX_PORT_PHERF_CONTROLLER_REST` (8780),
passed as `PHERF_REST_PORT` and published on `127.0.0.1` of the host for operator access, and mounts
the volume `pherf-controller-0-data` at `/var/lib/pherf/run`. Each agent connects to the controller
at `http://pherf-controller-0:8790` and reaches the target cluster via `PHERF_ZOOKEEPER`. Across
both backends, the `pherf-distributed` driver invokes the REST API exclusively via `sb_exec` inside
`pherf-controller` instance 0 (`lib/fleet.sh`), and `px-pherf` does the same on Compose without
ever using a host-published port; on the Kubernetes backend, where the tier is rendered as
Deployments, `px-pherf` connects to the API through a loopback tunnel instead (see "Workloads" and
"Network, ports and DNS" under the Kubernetes backend). The preflight memory check accounts for
this group alongside the other on-demand groups, and `px-cluster group start pherf` re-runs the
memory check before starting the group.

The `PX_PHERF_TARGET` setting determines which cluster the agents target.

* `harness`, the default: the tier joins the main cluster deployment and its network, setting
  `PHERF_ZOOKEEPER` to `zookeeper-0:<PX_PORT_ZOOKEEPER_CLIENT>`. Setting `PX_PHERF_ZOOKEEPER` in
  this mode fails with exit code 2.
* `external`: the deployment provisions the tier by itself, within its own scope and network, to
  target a cluster external to the harness. `PHERF_ZOOKEEPER` is populated from
  `PX_PHERF_ZOOKEEPER`, which must be set explicitly by the operator: it is required, and any
  character outside `[A-Za-z0-9._:,/-]` fails with exit code 2. No file in the repository working
  tree stores a hardcoded ZooKeeper quorum. In this mode, the topology record captures the run
  parameters, the tier settings, and the version set from the pherf image staging record, while
  omitting all harness cluster roles. Because the tier itself constitutes a harness deployment,
  provisioning is refused on any target or host that already holds another harness deployment, and
  the agents can communicate with the external cluster only if its daemons register hostnames that
  resolve from within the tier's network or namespace.

Because the tier acts as the load generator rather than a cluster daemon, the log subject of the
cluster checks preserves the logs of the `pherf` group without counting any of their lines, and the
`all` collector target omits the group—just as it omits the setup role—across all collectors. Even
so, the restart, OOM, and container checks, together with the continuous anomaly monitor in
`px-run`, continue to monitor the tier.

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
The two memory limits of the Distributed Pherf tier, `PX_PHERF_CONTROLLER_MEM_MIB` and
`PX_PHERF_AGENT_MEM_MIB`, are tracked as sizing variables as well. When `PX_PHERF=1` is enabled, the
topology record also includes `PX_PHERF`, `PX_PHERF_TARGET`, `PX_PHERF_AGENT_COUNT`,
`PX_PHERF_IMAGE_TAG`, `PX_GROUP_PHERF`, and `PX_GROUP_PHERF_MEM_MIB`, and appends `pherf` to
`PX_GROUPS`, whereas `PX_TOPOLOGY_MEM_EAGER_MIB` excludes the on-demand tier. Rendering a deployment
without the tier produces an output identical to deployments prior to the introduction of the tier.

## The functions

The shelldoc on each `sb_*` function in `lib/backend/contract.sh` and `lib/backend/compose.sh`
serves as the definitive function reference: its `## @param` lines specify the arguments, and its
`## @return` line specifies the stdout schema and all possible exit codes. `sb_load` rejects any
backend that omits any of the 32 contract functions. Callers always record exit code 5 as a skipped
operation rather than a failure. The behavioral rules below apply across all contract functions.

Every function that operates on a cluster takes the scope identifier returned by `sb_scope_create`
as its first argument. Missing or malformed arguments result in exit code 2, as does passing a scope
identifier that does not belong to the active backend: on Compose, any identifier lacking the
`<PX_PROJECT_PREFIX>-` prefix; on Kubernetes, any identifier lacking a `<context>/` prefix or
specifying a namespace outside `<PX_PROJECT_PREFIX>-`.

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
output when executing on an amd64 host. `sb_image_push image target_ref` pushes a single local
image to `<registry>/<repository>:<tag>` and emits one image row containing the target reference
and the registry-assigned digest. The `sha256:<64 hex>` image argument specifies either a local
image ID or the configuration digest of a single local image: the containerd image store identifies
an image by its manifest digest, while the `config.digest` annotation on its descriptor records the
configuration digest that a kubelet reports for an image loaded by `px-k3d import`. The registry
must store the pushed image under its local image ID. The function returns exit code 1 on a failed
push, a missing digest, a registry digest that differs from the local ID, or when two local images
match the configuration digest; 2 on invalid usage; and 6 when no local image matches the specified
ID or configuration digest.
It performs no image build, never logs in to a registry (relying instead on the operator's
credential store to authenticate the push), and serves as the sole container runtime `push`
operation in the repository. Only `px-cluster push` invokes it, and the Kubernetes backend
returns exit code 5.

## The runtime boundary

**Only a designated owner may invoke a container runtime, a Kubernetes tool, or a registry, and each
owner may invoke only its own.** The twelve restricted tool binaries are `docker`, `docker-compose`,
`podman`, `nerdctl`, `kubectl`, `k3d`, `helm`, `crictl`, and the registry clients `skopeo`, `crane`,
`regctl`, and `oras`, while the engine variables are `PX_DOCKER_BIN`, `_PXC_ENGINE`,
`PX_KUBECTL_BIN`, `_PXK_KUBECTL`, and `_PXD_K3D`. Each owner is assigned a distinct token class:

| File | May name |
|---|---|
| `lib/backend/compose.sh` | The container runtimes alongside `PX_DOCKER_BIN` and `_PXC_ENGINE`. |
| `lib/backend/k8s.sh` | `kubectl` alongside `PX_KUBECTL_BIN` and `_PXK_KUBECTL`. |
| `bin/px-k3d` | `k3d` alongside `_PXD_K3D`. |

No other file in the repository may reference any of these tokens, except for the self-test suite
that generates the mock executables. Furthermore, no file may reference a standalone registry client
or `helm`, as the container runtime push inside `sb_image_push` is the sole mechanism that writes to
a registry. The static scan also enforces three additional invariants across all executable code
lines in the repository tree: only `bin/px-k3d` may contain `cluster create` (ensuring
`lib/backend/k8s.sh` never creates a cluster), the container runtime `push` command may appear
exclusively within `sb_image_push`, and no line may log in to a registry or create a Kubernetes
secret. `px-k3d` invokes `k3d` through the resolved absolute path stored in its private variable
`_PXD_K3D`—just as each backend invokes its own underlying tool—while `px-cluster` and
`scripts/preflight-k8s.sh` interact with `k3d` exclusively by invoking a subcommand of `px-k3d`.

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
`px_timeout`. Because an absolute path can never resolve to a shell function, this single call site
bypasses the runtime trap described below while any other invocation form is caught. The Kubernetes
backend applies the exact same mechanism to `kubectl`: `PX_KUBECTL_BIN` is declared private and
scrubbed from the environment when the backend loads, and `px_k8s_call` executes the resolved
absolute path stored in `_PXK_KUBECTL`.

This isolation boundary is not merely a coding convention; it is the architectural invariant that
makes supporting a second backend possible. Consequently, the harness self-test suite enforces the
boundary through three independent verification layers designed so that no single bypass can evade
all three:

1. **Static scan.** `test/run-tests.sh` enumerates every file in the harness that can be executed,
   sourced, or rendered into executable form—including every file with a shell shebang, every file
   under `bin/`, and all `*.sh`, `*.env`, `*.tmpl`, and `*.suite` files. It scans each file for the
   twelve tool names and the five engine variables, ignoring comment lines as well as lines
   annotated with the inline waiver `# px-allow-docker: <reason>`. Any unwaived match outside the
   allowlist fails the test suite and reports the offending file path, line number, and line text.
   The allowlist is maintained inside the test script with one entry per path specifying its
   permitted token class and a mandatory reason. A match inside an allowlisted backend passes only
   when the token belongs to that backend's token class, and the scan for bypass forms below
   likewise restricts each backend to its own token class. A **stale** allowlist entry also fails
   the test: an allowlisted path that no longer exists or no longer contains a token of its assigned
   class must be removed rather than left behind to grow into a loophole. Markdown documentation is
   excluded from the scan, allowing this document to name the tools directly.
2. **Runtime trap.** `lib/common.sh` defines each of the twelve tool names as a shell function that
   immediately exits with code 2, reporting the attempted argument list and a diagnostic message
   naming the substrate contract. Because every `px-*` process sources `lib/common.sh`, every
   driver, check, hook, and collector executing within such a process inherits the trap—including
   inside subshells and under `set -e`. External child programs do not inherit shell functions, but
   layers 1 and 3 cover them, and the unexported private engine variable leaves them no path to the
   engine binary.
3. **Fake runtime shim.** The test suite writes a mock `docker` executable into a temporary
   directory, prepends that directory to `PATH`, points `PX_DOCKER_BIN` at the shim, and has it
   append its argument list to an audit log before responding from a fixture state file. Alongside
   it, the test writes a stateful fake `kubectl`—which logs every invocation first, rejects any call
   lacking `--context` and `--namespace`, and models pods, StatefulSets, claims, namespaces, the
   `OnDelete` strategy, the restart of a killed container, and target cluster properties—a stateful
   fake `k3d` that tracks its clusters and imported images, a simulated registry inside the fake
   container runtime that records each push and rejects any login attempt, and mock executables for
   `helm`, `crictl`, and the four registry clients that append their binary name and argument list
   to the same log. Any call to these tools resolved through `PATH` must reach its fake executable
   and populate the log. The negative control exercises every entry point that must never touch a
   container runtime or Kubernetes tool and asserts that the log remains empty. A simulated hostile
   driver reads `PX_DOCKER_BIN` and invokes `command "$PX_DOCKER_BIN" ps`, verifying that the
   variable is empty and the log remains empty; on the Kubernetes backend, a second hostile driver
   performs the same check against `PX_KUBECTL_BIN`, and exercising each backend reaches only that
   backend's designated tool. The positive control then executes a real backend call and asserts
   that the log is **not** empty and contains the expected scope label selector, because an empty
   log alone would prove nothing and could merely indicate a broken shim or a `PATH` change that
   never took effect.

Any requirement for a new file to access the container runtime directly is an architectural design
question rather than a routine allowlist addition. The image build pipeline illustrates this
principle: `scripts/stage-artifacts.sh` stages the build context and invokes `sb_image_build`, so
the build pipeline never references a container runtime directly and the allowlist remains
unchanged.

## Rules a backend author must honour

* **Perform no runtime work when sourced.** `sb_load` sources the backend file before any operator
  intent exists, and the self-test suite asserts that the runtime log remains empty after a bare
  `sb_load`. A backend file must only define functions and execute nothing when sourced.
* **Invoke the underlying runtime through a single call site**, using an absolute binary path stored
  in an unexported private variable, and register the backend file in the self-test allowlist under
  its assigned token class.
* **Emit TSV rows strictly in the declared schema field order** using `px_tsv_row`, sanitize
  free-form message text with `px_tsv_clean`, and represent missing values as empty columns rather
  than omitting columns.
* **Validate mode arguments with `sb_value_arg`**, which checks the value via `sb_valid_value` and
  returns exit code 2 on failure; never forward an unvalidated mode value to the underlying
  substrate.
* **Define every contract function, and return exit code 5 for any operation the substrate cannot
  support**, writing the reason to stderr. Callers record exit code 5 as a clean skip, whereas an
  unhandled crash cannot be recovered from by a caller.
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
  tracking. In addition, `contract.sh` provides shared helpers for parsing timeout arguments, flags,
  the arguments to `sb_exec`, `sb_logs`, `sb_copy_in`, `sb_copy_out`, and `sb_signal`, poll
  intervals, role selections, dry-run mode, and version floors; for managing backend scope records
  and the record-validation half of scope gating (`sb_record_*`, `sb_record_gate`, and
  `sb_scope_gated`); and for handling restart phase rows, region-mover phases, the region-mover
  command template, and the JVM signal delivery script. Reimplementing any of these utilities in a
  backend introduces duplicate behavior to debug.

A new backend implementation is considered complete when `sb_verify_backend` produces no missing
function names, the self-test contract suite confirms that every required contract function is
defined, and the standard verification test suite passes on the new backend just as it does on
Compose.

## Kubernetes backend

`lib/backend/k8s.sh` implements the exact same 32 contract functions, output schemas, and exit codes
on a Kubernetes cluster without introducing any custom substrate verbs of its own. Everything
earlier in this document is normative for the Kubernetes backend except where this section
explicitly specifies a difference, and it is the sole file in the repository permitted to invoke
`kubectl`. The backend operates on existing clusters and node pools: it never creates a cluster, and
only `bin/px-k3d` creates a local k3d cluster. Milestone K1 delivered the backend implementation and
its manifest generator verified against a fake `kubectl`; milestone K2 added cluster preflight
checks, endpoint tunnels, cluster target files, image promotion, the mesh overlay, and `px-k3d`; and
milestones K3 and K4 validate the implementation on a live k3d cluster.

### Binding and selection

Passing `--backend k8s` to `px-cluster` and `px-run`, passing `-e PX_BACKEND=k8s`, or setting
`PX_BACKEND=k8s` in `env/local.env` selects the Kubernetes backend before the configuration loader
runs. The loader then ingests `k8s/env/k8s.env` after the run-class configuration and before
`docker/env/defaults.env`, ensuring that explicit operator overrides and run-class settings take
precedence. Immediately prior to `k8s.env`, under the `backend:px-k3d` origin, the loader ingests
`k8s/env/k3d.env` for the local `px-k3d` cluster unless `PX_K8S_TARGET` designates another cluster,
making the local cluster the default target. A cluster target file (described below) is evaluated
immediately after any `--config` files and therefore overrides both backend default files. Once the
cluster is provisioned, the cluster record persists `PX_BACKEND=k8s` so that subsequent commands do
not need to pass `--backend`. Drivers, checks, hooks, and collectors inherit `PX_BACKEND` and
`PX_SCOPE_ID` from `px-run` and bind the
backend via `sb_load "$PX_BACKEND"` without modification.

| Name | Default | Meaning |
|---|---|---|
| `PX_K8S_CONTEXT` | none | The target kubeconfig context; `k3d.env` sets this to `k3d-px`. |
| `PX_K8S_STORAGE_CLASS` | none | The StorageClass for every claim; `k3d.env` sets `local-path`. |
| `PX_K8S_VOLUME_GIB` | 10 | The persistent volume claim size in GiB for each stateful role. |
| `PX_K8S_CPU_MILLI_PER_GIB` | 250 | The container CPU allocation per GiB of its memory limit. |
| `PX_K8S_MAX_PAUSE_S` | 120 | The maximum JVM pause duration that the liveness probe tolerates. |
| `PX_K8S_CLUSTER_DOMAIN` | `cluster.local` | The internal DNS domain of the Kubernetes cluster. |
| `PX_KUBECTL_BIN` | the `PATH` | The `kubectl` binary path, kept private like `PX_DOCKER_BIN`. |
| `PX_KUBECTL_MIN_VERSION` | 1.28 | The minimum `kubectl` version, set in `docker/versions.env`. |
| `PX_K8S_DEDICATED_NODES`, `PX_K8S_MESH`, `PX_K8S_MESH_CORE` | 0 | See the target files section. |

The backend never relies on the implicit current context of the local kubeconfig. Whenever a
Kubernetes context is required, an empty `PX_K8S_CONTEXT` causes the operation to fail with exit
code 2, preventing a test run from accidentally targeting whichever cluster happens to be active in
the operator's shell.

### Scope, labels and the call site

A Kubernetes scope identifier has the form `<context>/<namespace>`, where the namespace is
`<PX_PROJECT_PREFIX>-<cluster>`, such as `k3d-px/px-default`. Because the namespace is parsed as the
substring following the final `/`, context names that contain a `/` parse cleanly, whereas any scope
identifier lacking a `/` (such as a Compose scope identifier) is rejected with exit code 2. Because
the cluster name forms part of a Kubernetes namespace name, any cluster name containing `_`—although
permitted in general by `SB_CLUSTER_RE`—is rejected with exit code 2 during both `sb_scope_create`
and manifest rendering.

`sb_scope_create` fails with exit code 1 if the target cluster already contains a namespace
belonging to another harness cluster (enforcing a single deployment per target cluster), if a
namespace matching the target scope name already exists without the scope's matching cluster label,
or if a local state record already exists for another scope on the host unless `PX_FORCE=1` is set.
It records the scope metadata in `$(px_state_dir)/k8s/scope-<id>.tsv` via the record helpers in
`contract.sh`, storing the deployment directory path, the list of role groups started by test steps,
and `namespace_owner`, while the scope's container restart table resides alongside it in
`pods-<id>.tsv`. During provisioning, `sb_provision` applies the namespace manifest first. Because
applying a namespace manifest would otherwise adopt an existing namespace, checking the cluster
label up front prevents the harness from hijacking and subsequently deleting a namespace owned by
another user or system. For the same reason, `sb_scope_exists` considers a namespace present only
when it carries the expected cluster label for that scope.

**The prepared namespace.** Because a remote image pull secret resides inside the target namespace
and the harness never creates a secret, the operator of a remote target cluster prepares the
namespace in advance by creating `<PX_PROJECT_PREFIX>-<cluster>` with the label
`px.harness.cluster=<cluster>` and installing the pull secret within it. A namespace that already
exists with the matching cluster label when the scope gate runs is recorded with
`namespace_owner operator`, whereas a namespace created by the initial manifest application is
recorded with `namespace_owner harness`. The harness deletes only resources that it created:
`sb_scope_delete` deletes the namespace itself only when the scope record specifies
`namespace_owner harness`. For any other namespace—whether prepared by the operator or lacking a
local scope record (since the cluster label alone does not indicate who created the namespace)—the
function deletes the scope's ConfigMaps and Services by label, verifies that none remain, and
preserves the namespace along with its pull secret. During bring-up, the backend does not apply
`00-namespace.yaml` to an operator-prepared namespace so that none of its existing labels are
modified; consequently, when `PX_K8S_MESH=1` is enabled, the operator must also configure the mesh
injection label on that namespace.

The Kubernetes backend reuses the exact same label keys as the Compose backend—`px.harness.cluster`,
`.role`, `.index`, `.group`, `.start`, and `.drain`—all of which are valid Kubernetes label names.
Every rendered resource, including the namespace itself, carries the cluster label.

All `kubectl` invocations pass through a single function, `px_k8s_call`, using the resolved
absolute binary path stored in the private variable `_PXK_KUBECTL`. Every invocation explicitly
specifies both `--context` and `--namespace`, even when querying cluster-scoped resources for which
`kubectl` ignores the namespace flag. Every command that selects harness resources includes the
label selector `-l px.harness.cluster=<cluster>`, including queries listing pods, StatefulSets,
Jobs, persistent volume claims, or namespaces, label-based deletions, and `top`. Commands that act
on a single named object—such as `exec POD`, `logs POD`, `cp`, `port-forward pod/POD`,
`delete pod NAME`, or `delete namespace NAME`—are scoped by the target namespace. Only read-only
queries inspecting target cluster properties omit a label selector: `version`, `auth can-i`,
`get nodes`, `get storageclass NAME`, `get secret NAME` (retrieving only the secret name, never its
data payload), `get resourcequota`, and `get events` for a single pod.

### The deployment directory

The Kubernetes deployment directory is rendered by `k8s/manifests.gen.sh`, which is invoked via
`px-cluster --backend k8s render`. It accepts the same command-line options and returns the same
exit codes as `docker/compose.gen.sh`, sharing the common deployment generation library
`lib/deploy.sh`—including the role table, all configuration value checks, plug-in render hooks, the
topology record writer, artifact staging, and the fail-closed template validator. Consequently, a
given configuration produces identical `services.tsv` rows across both backends, and any invalid
setting rejected by one generator is rejected by the other with the exact same diagnostic message.
Manifests are rendered by default into
`$PX_OUT/deploy-k8s/<cluster>` so that Compose and Kubernetes renders for the same cluster name
never overwrite one another.

```text
deploy-k8s/<cluster>/
  topology.env          the topology record of the shared writer
  ports.generated.env   PX_PORT_<ROLE>_<FAMILY>=<base>, with no index
  services.tsv          the columns and the rows of the Compose generator
  requests.tsv          kind name replicas cpu_milli mem_mib claims claim_gib, one row per
                        workload document, which scripts/preflight-k8s.sh sums
  render.env            the settings of a px-cluster render or push, which up DIR records
  manifests/            applied by sb_provision in name order
    00-namespace.yaml   the namespace, with the cluster label
    10-env.yaml         ConfigMap px-env: topology.env and the port map
    11-conf.yaml        ConfigMap px-conf: the bytes of docker/image/conf/*.tmpl
    12-conf-overlay.yaml  ConfigMap px-conf-overlay, with a conf-overlay plug-in
    2N-<role>.yaml      the headless Service and the StatefulSet of a cluster role,
                        N its position in the role table
    30-setup.yaml       the setup Job
    40-client.yaml  41-tooling.yaml   the claims tmp-client-0 and tmp-tooling-0, and the
                                      standalone Pods
  groups/<group>/       the files of an on-demand group, applied by sb_group_start
  groups/pherf/         with PX_PHERF=1, the Distributed Pherf tier:
    50-pherf-target.yaml      ConfigMap pherf-target: PHERF_ZOOKEEPER, the one quorum
    51-pherf-controller.yaml  the claim data-pherf-controller-0, the ClusterIP Service
                              pherf-controller and the controller Deployment
    52-pherf-agent.yaml       the agent Deployment
  conf-overlay/  plugins/<id>/        the plug-in records, as on Compose
```

The generator produces every manifest by expanding the template files under `k8s/templates`. Both
document skeletons and repeated elements are represented as templates, and the generator simply
selects, repeats, indents, and concatenates the expanded text, rendering any empty list as `[]` and
any empty map as `{}`. Cluster target files and run-class configurations modify only the bound
variable values; the template files and the overlays under `k8s/overlays` are strictly read-only
and are never modified in place. Each template may reference only the variables explicitly bound for
it by the generator. `pxg_expand` rejects any unbound or empty `${PX_*}` placeholder—as well as any
`${` sequence that does not form a valid placeholder—with exit code 2, reporting the offending
template path and line number. Next, `pxg_validate` scans the written files and fails with exit code
1 across both generators if any unexpanded `$` character remains. The two ConfigMaps containing raw
configuration templates are appended after this validation pass because those templates
intentionally preserve `${PX_*}` placeholders for container startup: each file is embedded as an
explicitly indented YAML literal block so the pod receives its exact bytes, and the container's
`render.sh` script serves as their fail-closed validator at startup.

The container image is identical to the image used on Compose and requires no modifications. At
startup, each pod renders its own service configuration from the `px-conf` ConfigMap mounted
read-only at `/opt/px/conf` together with the environment variables imported from `px-env`,
executing the exact same entrypoint and `render.sh` script. The container entrypoint contains no
backend-specific branching.

### Workloads

* Each cluster daemon role is deployed as a StatefulSet named after the role, configured with
  `serviceName` set to the role name, `replicas` set to the role's instance count,
  `podManagementPolicy: Parallel` (because the container entrypoint coordinates startup ordering,
  just as on Compose), `updateStrategy` set to `OnDelete`, and persistent volume claim retention
  configured with `whenDeleted:
  Retain`, `whenScaled: Delete`. Updating a rendered manifest never automatically replaces a
  running pod; pods are replaced only when explicitly deleted.
* The setup role is deployed as the Job `setup-0`, configured with `restartPolicy: Never`,
  `backoffLimit: 0`, the persistent volume claim `data-namenode-0` mounted at `/data`, and a
  required pod affinity rule co-locating it on the same node as the NameNode pod because that
  volume claim binds to a single node. Its pod template always carries
  `sidecar.istio.io/inject: "false"` across every render because an injected sidecar container that
  does not terminate would prevent the Job from completing.
* The `client` and `tooling` roles are deployed as standalone Pods named `client-0` and `tooling-0`
  with `restartPolicy: Never`.
* Each StatefulSet pod derives `PX_HOSTNAME` from its pod name and `PX_INSTANCE_INDEX` from the
  `apps.kubernetes.io/pod-index` label, whereas the setup Job and standalone Pods define literal
  values. All remaining environment variables match those of the corresponding Compose service. The
  setup Job does not read a monitor heartbeat file: a pod cannot read a host file without a
  `hostPath` volume—which the `restricted` security profile forbids and a remote cluster node cannot
  serve—and `px-cluster up` aborts any bring-up whose health monitor fails to start.
* Every pod disables service account token mounting (`automountServiceAccountToken: false`) and
  service environment variable injection (`enableServiceLinks: false`), sets
  `terminationGracePeriodSeconds` to `PX_TIMEOUT_STOP`, and enforces `runAsNonRoot` using the host
  UID and GID that built the container image (rejecting UID or GID 0), alongside `fsGroup` and
  `seccompProfile: RuntimeDefault`. Within each pod, the primary container `px` sets
  `allowPrivilegeEscalation: false` and drops all Linux capabilities, satisfying the Kubernetes
  `restricted` Pod Security Standard profile. Local images use `imagePullPolicy: Never`, whereas
  remote images are referenced by immutable digest with `IfNotPresent`.
* For node placement, DataNode and RegionServer pods prefer nodes that do not already host a pod of
  the same role (`preferredDuringSchedulingIgnoredDuringExecution` with weight 100 on
  `kubernetes.io/hostname`), which is a no-op on a single-node cluster. Every pod specification
  includes the target cluster's `nodeSelector`, `tolerations`, and `imagePullSecrets`, rendering
  `{}` and `[]` when none are configured.
* Resource requests are set equal to resource limits so that every pod is assigned to the
  Guaranteed QoS class. Memory limits, heap sizes, and direct memory allocations match the role's
  Compose values; because Compose does not specify CPU limits, each container's CPU allocation is
  computed as `ceil(memory MiB * PX_K8S_CPU_MILLI_PER_GIB / 1024)` millicores. Because these CPU
  limits lengthen each CPU-bound recovery phase, `k8s/env/k8s.env` derives and configures the
  Kubernetes recovery bound `PX_THRESHOLD_RECOVERY_SECONDS_MAX` at 220 s (compared to 120 s on
  Compose).
* Persistent volume claims for `zookeeper`, `namenode`, `secondarynamenode`, and `datanode` are
  provisioned through `volumeClaimTemplates` backed by `PX_K8S_STORAGE_CLASS` at `PX_K8S_VOLUME_GIB`
  GiB per claim; attempting to render manifests without a configured StorageClass fails with exit
  code 2. Furthermore, `/var/lib/px/logs` is mounted as an `emptyDir` volume so that a daemon's
  garbage-collection log survives container restarts within the same pod.
* Every harness-image pod addressed during a run mounts a ReadWriteOnce persistent volume claim
  `tmp` at `/tmp/px`, configured with the same StorageClass and size and carrying the cluster and
  role labels: a StatefulSet provisions it via a second `volumeClaimTemplates` entry (creating
  claim `tmp-<role>-<i>`, placed after `data` on roles that also declare a data volume), whereas
  the standalone `client` and `tooling` Pods mount the claims `tmp-client-0` and `tmp-tooling-0`
  defined ahead of the Pod objects in their manifest files. The `/tmp/px` mount holds the driver
  working directory on client 0 (staged inputs, output files awaiting retrieval, and catalog and
  restore SQL scripts) together with the collector artifacts on each target instance
  (`/tmp/px/<run-id>/collect/<collector>/`, containing the jHiccup log, JFR recordings, profiler
  output, and trace counters). Because a recovery restart replaces the pod—which an `emptyDir`
  volume does not survive—the persistent claim preserves these files across pod replacement in the
  same way that the writable layer of a restarted Compose container does. Mounting a claim also
  schedules a replacement pod back onto the node hosting its volume (for local-path storage) or
  into the same availability zone (for zonal block storage). The setup Job and the Pherf tier do
  not mount a tmp claim: the Job retains no run state, and the Pherf agents run as Deployment
  replicas that cannot own individual claims, so collector output captured with
  `--targets pherf-agent` is still lost if an agent pod is replaced. `requests.tsv` accounts for
  the tmp claim in its `claims` column, and teardown removes it by label alongside the other
  claims. Because each pod requests an additional `PX_K8S_VOLUME_GIB` of storage, any namespace
  ResourceQuota on claim count or storage capacity must accommodate the tmp claims, and because
  collectors leave `/tmp/px/<run-id>/collect` in place, a long-lived cluster retains each run's
  collector artifacts on the claim until teardown.
* When `PX_PHERF=1` is enabled, the Distributed Pherf tier is rendered as the on-demand `pherf`
  group comprising two Deployments under `groups/pherf/`. Both Deployments specify the `Recreate`
  strategy so that rolling out an updated pod template starts a replacement pod only after the old
  pod has terminated, whereas pod deletion or eviction provides no such ordering guarantee (see
  "Lifecycle"). The controller Deployment `pherf-controller` runs one replica and mounts the
  persistent volume claim `data-pherf-controller-0` (`PX_K8S_STORAGE_CLASS`, `PX_K8S_VOLUME_GIB`)
  at `/var/lib/pherf/run` so that frozen reports persist across pod replacements and group stops,
  while the ClusterIP Service `pherf-controller` exposes `http-rest` on port 8780 and `http-agent`
  on port 8790. The agent Deployment `pherf-agent` runs `PX_PHERF_AGENT_COUNT` replicas; each agent
  populates `PHERF_AGENT_ID` from its pod name, reaches the controller through the Service at
  `http://pherf-controller:8790`, and reads `PHERF_ZOOKEEPER` from the `pherf-target` ConfigMap via
  `configMapKeyRef`. That ConfigMap is the sole place in the rendered manifests that stores the
  quorum—set to `zookeeper-0:<PX_PORT_ZOOKEEPER_CLIENT>` for the harness target or
  `PX_PHERF_ZOOKEEPER` for the external target, whose render contains only the namespace, the two
  harness ConfigMaps, and `groups/pherf/`. Tier pods reuse the standard pod template shared by all
  other workloads, configured with the `PX_PHERF_IMAGE_TAG` image, the role verb as their argument,
  and a readiness-only probe; they preserve the container name `px`, the security context, and the
  Guaranteed resource requests, receive the `px-env` ConfigMap (which the pherf image ignores), and
  inherit the DNS search domains of the cluster daemons so that the bare hostnames registered by
  those daemons resolve. Because a tier pod carries neither an explicit index label nor a pod-index
  label, the backend assigns replica indices for a Deployment role by sorting live pods first in
  pod-name order followed by Terminating pods, and `sb_list` reports instance `<i>` with the pod
  name as its identifier. During a replacement, instance 0 therefore resolves to the live pod, and
  after a scale-down, the removed pods occupy the indices at and above the new replica count until
  they finish terminating. Consequently, a planned fault against an agent targets a positional
  index that can shift to a different pod following a replacement.

### Network, ports and DNS

Because every pod receives its own dedicated IP address, each role instance binds directly to the
base port of each port family, and `ports.generated.env` contains only unindexed port variables.
Each cluster daemon role is fronted by a headless Service configured with `clusterIP:
None`, `publishNotReadyAddresses: true`, where every declared port carries a protocol-prefixed
name and a matching `appProtocol` (`http-` for web, shuffle, and REST port families, and `tcp-`
for all other families). No Service declares an external service type, and no pod allocates a host
port or uses host networking. Peers continue to address one another by bare instance hostname—such
as `zookeeper-0`, matching the existing configuration templates—because every pod is configured
with a DNS search domain `<role>.<namespace>.svc.<PX_K8S_CLUSTER_DOMAIN>` for each headless
Service. Supporting more than six DNS search domains alongside the pod-index label requires at
least Kubernetes 1.28, which `sb_preflight` enforces as the minimum cluster version.

From the host, the operator and harness commands access a pod exclusively through a port-forward
tunnel bound to the loopback interface. When a caller requests an endpoint, `sb_endpoint` launches a
detached `kubectl port-forward --address 127.0.0.1 pod/<pod> :<base port>` process, recording its
log alongside a state record in the state directory (`tunnel-<scope>_<role>_<index>_<port>.tsv`,
containing the process ID, local port, pod UID, and scope). The function waits up to 30 s for the
tunnel to emit its `Forwarding from 127.0.0.1:<port>` line and then outputs `127.0.0.1` together
with that dynamically allocated local port (returning exit code 4 if the readiness line does not
appear within the timeout, or exit code 1 if the process terminates early or the pod is not
running). Subsequent calls reuse an active tunnel that targets the same pod. When a controller
replaces the pod, the existing tunnel can no longer reach it: the `kubectl` process exits on its
first failed connection attempt, and the next endpoint call terminates the stale tunnel after
detecting that the recorded pod UID has changed before establishing a replacement tunnel.
`sb_endpoint_release` stops an individual tunnel—or every tunnel in the scope when given `all`,
which cluster teardown executes as its opening step. Because the detached tunnel process inherits no
open file descriptors from the caller, `px-cluster endpoint` returns immediately while the tunnel
continues operating in the background. Roles without a Service—specifically `setup`, `client`, and
`tooling`—expose no endpoints and return exit code 6.

The Distributed Pherf controller is the sole workload fronted by a ClusterIP Service rather than a
headless Service, providing the agents with a stable virtual address for the agent channel. No
harness command contacts that Service from the host. `px-pherf` opens a tunnel via `sb_endpoint
<scope> pherf-controller 0 rest` and names its local port in `PF_API_PORT`, so `lib/fleet.sh` runs
its curl script on the host against `127.0.0.1:<tunnel port>`; any endpoint that fails to open is
reported as a cluster anomaly with exit code 3. The host curl binary must be version 7.76 or later
to support `--fail-with-body`. By contrast, the `pherf-distributed` driver uses the standard driver
contract primitives (`sb_exec`, `sb_copy_in`, `sb_copy_out`) and executes the same curl script
inside the controller pod. On Kubernetes, both callers wait for the target count of IDLE agents
because each agent ID is a pod name that is not known in advance. Running `px-pherf stop` releases
the tunnel after stopping the group, as does a failed `px-pherf start` after stopping the group,
while a full teardown releases every tunnel in the scope.

### Probes

Every cluster daemon container configures three probes: a startup probe executing
`healthcheck.sh live` with a 10 s period and a failure threshold of 90 (matching the 900 s Compose
`start_period`), a readiness probe executing `healthcheck.sh ready` with a 5 s period, a 15 s
timeout, and a failure threshold of 3 (matching the Compose healthcheck parameters), and a liveness
probe executing `live` with a 10 s period, a 15 s timeout, and a failure threshold of
`PX_K8S_MAX_PAUSE_S / 10 + 2`. Consequently, a JVM pause lasting up to `PX_K8S_MAX_PAUSE_S` does
not trigger a container restart, whereas a pause exceeding that bound causes the kubelet to restart
the daemon—a behavior that never occurs on Compose. The setup Job and the two standalone Pods
configure only the readiness probe, because a liveness probe failure on a workload with a
non-restarting policy would permanently terminate the pod. Similarly, the tier pods configure only
the readiness probe `/opt/pherf/healthcheck.sh <controller|agent>` using the Compose probe timings
(5 s period, 6 s timeout, and failure threshold 3); omitting a liveness probe ensures that a
controller restart never causes the kubelet to restart agents whose communication channel closed.

### Lifecycle

* `sb_provision` first validates every manifest in `manifests/` using
  `kubectl apply --dry-run=client -f` and applies the files in lexical name order only after every
  dry-run check succeeds; if any dry run fails, the function returns exit code 1 without applying
  any resources to the cluster. Once applied, it waits until a pod exists for every instance listed
  in `services.tsv`. Passing a deployment directory rendered for a cluster name that does not match
  the target scope fails with exit code 2.
* `sb_teardown` deletes all StatefulSets, Jobs, and Pods matching the cluster label selector—along
  with the Deployments whenever the recorded render includes the Distributed Pherf tier. When
  `keep_data` is 0, it also deletes the persistent volume claims by label and verifies that no
  selected resources remain. When `keep_data` is 1, the persistent volume claims, ConfigMaps,
  Services, and namespace are preserved for post-mortem diagnosis. `sb_scope_delete` deletes the
  namespace and waits for its removal, provided that the namespace carries the scope's cluster
  label; if the namespace belongs to another owner, it is left untouched and the function returns
  exit code 1. Setting `PX_KEEP` preserves the namespace. Consequently, `px-cluster down` removes
  both the volume claims and the namespace, whereas `px-cluster down --keep` preserves both.
* `sb_scale` validates and applies `10-env.yaml` along with the target role's manifest after a
  client-side dry run, then waits for any newly added pod ordinals to appear; when scaling down, the
  StatefulSet's claim retention policy automatically removes the persistent volume claims of removed
  ordinals. Scaling an on-demand role belonging to a group that has not yet been started updates
  only the rendered files on disk, which take effect when the group is subsequently started. When
  scaling the tier's agent Deployment, `sb_scale` validates and applies `10-env.yaml` before
  executing `kubectl scale deployment pherf-agent --replicas=<count>`; `px-pherf scale <count>`
  invokes this path through `px-cluster scale pherf-agent <count>` and then waits for the agents to
  become idle.
* `sb_group_start` validates and applies `groups/<group>/*.yaml` after a client-side dry run and
  waits for all roles in the group to become ready. `sb_group_stop` deletes the group's
  StatefulSets—as well as its Deployments when the render includes the tier—while retaining their
  persistent volume claims so that a subsequent start reattaches the existing volume state,
  including the controller's claim. Consequently, StatefulSet replica counts in the manifests
  always match the configured topology counts, and every state transition performed by the backend
  consists solely of an `apply` of a rendered manifest, a resource `delete`, or scaling the agent
  Deployment.
* `sb_wait_ready` waits for every instance expected by the rendered topology, treating any pod not
  yet created by its controller as pending rather than absent. Conversely, any pod not expected by
  the current render—such as an excess ordinal being terminated during a scale-down—is ignored once
  its controller initiates deletion, ensuring that scaling down does not block on a Terminating pod.
  The wait aborts immediately with exit code 3 upon encountering a non-zero container termination, a
  failed or evicted pod, a container waiting reason that cannot recover without modifying the
  deployment (`CrashLoopBackOff`, `ErrImageNeverPull`, `ErrImagePull`, `ImagePullBackOff`,
  `InvalidImageName`, or `CreateContainerConfigError`), or any increase in an instance's restart
  count during the wait. One specific `CreateContainerConfigError` condition is treated as
  transient: when the container's waiting message reports `failed to sync configmap cache`, the
  node authorizer has not yet recorded the binding between the newly scheduled pod and its assigned
  node, and the kubelet automatically retries container startup. The readiness barrier inspects the
  pod's waiting message and treats the pod as pending until the barrier timeout expires, reporting
  the most recent waiting message on timeout. Any other `CreateContainerConfigError` immediately
  aborts the wait with exit code 3. If a pod disappears or transitions out of that waiting state
  before the barrier can read its message, it is treated as pending and evaluated on the next
  polling pass.
* When a tier controller pod is deleted or evicted, its termination can overlap with its
  replacement: the ReplicaSet creates the replacement pod immediately while the old pod may remain
  terminating for up to `PX_TIMEOUT_STOP`, and under a node-local StorageClass such as k3d's
  `local-path`, the ReadWriteOnce claim can be mounted simultaneously by both pods on the same
  node. The `Recreate` deployment strategy does not prevent this overlap because it serializes only
  template rollouts. Consequently, a starting controller that finds `controller.lock` held by
  another controller instance waits to acquire it: it retries the lock every 500 ms for up to
  150 s, logs a single diagnostic line while waiting, and proceeds with startup once the terminating
  controller releases the lock. This 150 s bound accommodates the default 120 s `PX_TIMEOUT_STOP`
  grace period—after which the kubelet forcibly kills the old pod—plus an additional 30 s margin
  for forced termination and lock release. While waiting for the lock, the replacement pod remains
  unready, and the readiness barrier waits for it without recording a restart. If the lock remains
  held for the full 150 s, the controller exits with code 2; configuring `PX_TIMEOUT_STOP` above
  120 s allows a terminating pod to outlast that acquisition bound.

### Exec, logs and copies

`sb_exec` invokes `kubectl exec -i POD -c px -- sh -c 'exec "$@"' px-exec CMD...`, passing `-t`
only when standard input is a terminal. Framing execution through the container shell yields the
exact exit codes 126 and 127 when a command is not executable or cannot be found, while targeting a
pod that is not running returns exit code 125. `sb_logs` reads from the `px` container, and
specifying `previous=1` retrieves the logs of the previously terminated container instance: because
Kubernetes retains the prior container's logs, the backend serves them directly and returns exit
code 6 when the instance has no previous container. `sb_copy_in` and `sb_copy_out` delegate to
`kubectl cp`, which executes `tar` inside the container and therefore requires a running pod, unlike
Compose.

### Faults and the drain-aware restart

* `sb_kill hard` and `sb_signal KILL` send SIGKILL to every child process of PID 1 inside the
  container. Because the Linux kernel ignores SIGKILL sent from within a PID namespace to that
  namespace's init process, killing the child processes causes `tini` to exit with status 137,
  prompting the kubelet to restart the container inside a StatefulSet pod. `sb_kill hard` returns
  only after the pod status reflects the kill—evidenced by a higher restart count, a new pod, or a
  container that is no longer running—within the `PX_TIMEOUT_STOP` timeout bound. Failed pod
  listings are retried until that bound expires, and the function exits with code 1 and reports the
  failure cause if the status never reflects the kill. This synchronization is necessary because
  the kubelet reports a kill several seconds after it occurs; until the status updates, a
  drain-aware restart reading the pod status would still observe a ready instance and drain the new
  container that the kubelet just restarted. `sb_signal TERM` delivers SIGTERM to PID 1, which
  `tini` forwards to its child process. All other signals are delivered to the oldest `java`
  process via the shared signal script in `contract.sh`, enforcing the same STOP and CONT state
  verification performed on Compose.
* `sb_kill delete` first terminates every child process of PID 1—just as `sb_kill hard`
  does—whenever the container is currently running, and then invokes `delete pod --wait=true`
  bounded by a timeout of `PX_TIMEOUT_STOP` plus 30 s without passing `--force`. Deleting a pod
  directly without killing its children first would send SIGTERM to the container, which `tini`
  would forward to the JVM to trigger a graceful shutdown via its shutdown hooks rather than an
  abrupt fault; killing the child processes first ensures that the termination is immediate.
  Waiting for pod deletion to complete ensures that the replacement pod created by the StatefulSet
  controller (with a new UID) never contends with the terminating pod for the same persistent
  volume claim, as `OnDelete` only defers template updates rather than pod recreation after
  deletion. A deleted standalone Pod is not recreated and remains terminated, matching Compose
  behavior.
* `sb_restart_drain` executes the HBase region mover inside `tooling 0`—so that the saved region
  list file persists across the target pod's deletion and recreation—targeting the pod's hostname
  (under which the RegionServer registered in ZooKeeper) and the base rpc port. When the target
  instance is currently ready, the operation unloads its regions, runs `delete pod --wait`, waits
  for a replacement pod with a new UID to appear, waits for that pod to pass its readiness probe
  (returning exit code 4 if the timeout bound expires), and reloads the regions. When the target
  instance is not ready, the drain and rebalance phases are skipped, but the pod is still deleted
  because `OnDelete` applies the latest rendered StatefulSet template only upon pod deletion:
  deleting a running pod is recorded as the stop phase, whereas deleting a pod with no running
  container marks the stop phase as skip and records the deletion under the start phase. If
  tooling 0 is absent, the function fails with exit code 2.

### Evidence

`sb_restart_count` sums the container's current Kubernetes restart count with the cumulative count
recorded for that role and index in the local restart table; whenever a pod is recreated with a new
UID, the table increments the cumulative count by one while preserving the prior total, and rows for
deleted pods are retained in the table. `sb_termination_reason current` reports the container's
active termination state—or its last termination state while the container is waiting in backoff to
restart—whereas `previous` inspects the prior termination state and returns exit code 6 if none
exists. A Kubernetes termination reason of `OOMKilled` maps to `oom`, and an evicted pod maps to
`evicted`. `sb_oom_killed` inspects both current and previous states and then checks the pod's
events (`get events
--field-selector involvedObject.name=<pod>`): a reason `OOMKilling` or `OOMKilled`, or a message
containing `OOMKilled`, indicates an out-of-memory kill. `sb_sample_resources` runs `kubectl top
--containers` for the container `px`; because the Kubernetes Metrics API does not expose network or
block I/O counters, those four schema columns are emitted as empty fields (denoting unknown values
rather than 0), and an unavailable Metrics API causes the function to return exit code 5. While a
workload runs, px-run aborts the run with exit code 3 if any instance's restart count increases or a
container terminates unexpectedly. Because the kubelet immediately restarts a terminated container,
the instance typically reports a current state of `running` by the time px-run inspects it; when the
restart count has increased, px-run instead records the `previous` container termination state in
both the run record and the manifest anomaly block—such as `killed` with exit code 137 following a
SIGKILL, or `oom`. Furthermore, the cluster health checks compare live restart
counts against the run's snapshots and capture the previous container log of every instance that
restarted or suffered an out-of-memory kill into `logs/instance/<role>-<index>.previous.log`.

### Images

`sb_image_ref` outputs the image reference declared in the pod specification alongside the resolved
image identifier reported by the container status. For local image references, `sb_image_present`
verifies that every node in the cluster already holds every local image required by the rendered
deployment—because pods using local tags never pull images remotely—matching node image names even
when the container runtime prefixes them with a registry hostname. An image referenced by immutable
digest is always treated as present with the digest itself serving as its image ID: the kubelet
pulls the image from the registry on demand, and any pull failure surfaces as `ErrImagePull`, which
aborts the readiness barrier with exit code 3. `sb_image_build`, `sb_image_base_digest`, and
`sb_image_push` all return exit code 5 on the Kubernetes backend: container images are built and
pushed through the Compose backend, while `px-k3d import` loads local images into a local k3d
cluster.

### The preflight

`scripts/preflight-k8s.sh` implements the preflight check for the Kubernetes backend, invoked by
`px-cluster up`, `scale`, and role-group starts via `px_preflight_script`. It accepts the same
command-line options and returns the same exit codes as `scripts/preflight.sh` (0 when the
deployment fits, 1 when the cluster fails to respond or a required metric cannot be measured, 2 on
invalid usage, 3 when a preflight check rejects the target, and 5 when the client is rejected) and
shares the common helper library `lib/preflight.sh`. During the fast phase, it emits the check rows
returned by `sb_preflight`:

| Row | Fails when |
|---|---|
| `engine_binary`, `engine_responsive`, `engine_version` | No client, no reply, or API below 1.28. |
| `client_version` | The `kubectl` client is below `PX_KUBECTL_MIN_VERSION`, naming both versions. |
| `engine_credentials` | The client lacks permission to create StatefulSets or exec into pods. |
| `deployment_absent` | Another harness namespace is on the target, or workloads are in its own. |
| `storage_class` | `PX_K8S_STORAGE_CLASS` is unset, or the named StorageClass does not exist. |
| `pull_secret` | `PX_K8S_PULL_SECRET` is set, and no such secret exists in the namespace. |
| `engine_memory`, `engine_cpus`, `node_memory` | Never; reports the selected nodes' figures. |
| `quota:<class>` | Never; reports the hard limit and usage of each ResourceQuota and resource. |

The capacity figures report the total allocatable memory and CPU millicores across the nodes
matching `PX_K8S_NODE_SELECTOR`, along with the allocatable memory of the single largest selected
node; each quota row reports a single ResourceQuota object and one of the tracked resources
(memory, cpu, pods, claims, or storage).

When `px-k3d check` recognizes the target context, the fast phase appends its `k3d_binary` and
`k3d_version` rows (rejecting a version below `PX_K3D_MIN_VERSION` with exit code 3 and reporting
both versions). Any `k3d-*` context that was not created by `px-k3d` fails the `k3d_cluster` row
with exit code 3: because every node in a k3d cluster reports the entire memory of the underlying
container engine as allocatable, only the host-level budget enforced by `k3d_memory_fit` prevents a
multi-node k3d deployment from exceeding actual host memory. The memory phase sums `requests.tsv`
across all workloads—including on-demand role groups—and emits `memory_fit` (comparing total memory
requests against `engine_memory`), `pod_fit` (comparing the largest single pod against
`node_memory`), `cpu_fit` (comparing total CPU requests against `engine_cpus`;
`PX_PREFLIGHT_MIN_CPUS` does not apply because container CPU requests are explicit), one
`quota_fit:<class>` row for each quota row (comparing total demand against the hard limit minus
current usage, or against the full hard limit when rechecking a running deployment with
`--phase memory`), `k3d_memory_fit` for a cluster managed by `px-k3d` (comparing demand against the
engine memory reported by `px-k3d check` minus the 4 GiB host headroom and minus
`PX_K3S_ALLOWANCE_MIB`, or failing with exit code 2 if a local target lacks that variable), and
`disk_free`. Every fit row formats its detail as `<demand> against <limit>: <what>`, and a failing
`memory_fit` check also lists the individual resource requests of each workload.

### Target files

A cluster target file describes the environment and placement parameters of a single external
Kubernetes cluster. The operator specifies the file path via `PX_K8S_TARGET`—either using `-e` or in
the shell environment—so every harness command accepts it without requiring a dedicated command-line
flag. The configuration loader ingests the target file in its own dedicated precedence stage
immediately after `--config` files and before `env/local.env`; consequently, command-line overrides,
environment variables, and `--config` files take precedence over the target file, while the target
file takes precedence over `local.env`, plug-in defaults, the run-class configuration, `k8s.env`,
and the base defaults. Specifying a path located inside the repository working tree fails with exit
code 2 because target files contain cluster-specific environment details and must reside outside the
repository; a non-existent target file fails with exit code 6; and defining `PX_K8S_TARGET` only
inside `local.env` or a lower-precedence stage fails with exit code 2, except when loaded from the
cluster record, which stores the flattened settings of the target file used during bring-up. The
repository ships no target files: `k8s/env` contains only `k8s.env` and `k3d.env`, where `k3d.env`
serves as the backend default that an external target file replaces.

| Key | Required | Grammar | Reaches |
|---|---|---|---|
| `PX_K8S_TARGET` | | A path outside the working tree | The loader stage that reads this file. |
| `PX_K8S_CONTEXT` | yes | No whitespace and no `\|` | `--context` on every call and the scope ID. |
| `PX_CLUSTER_ID` | no | A valid DNS label segment | The target namespace `<prefix>-<cluster>`. |
| `PX_PROJECT_PREFIX` | no | A valid DNS label part | The target namespace `<prefix>-<cluster>`. |
| `PX_K8S_STORAGE_CLASS` | yes | A valid DNS name | `storageClassName` and `get storageclass`. |
| `PX_K8S_REGISTRY` | remote | `host[:port][/path]`, see below | Every image and the push target. |
| `PX_K8S_PULL_SECRET` | no | A valid DNS name | `imagePullSecrets` on every pod, `get secret`. |
| `PX_K8S_NODE_SELECTOR` | no | `key=value[,key=value]` | `nodeSelector` and `get nodes -l`. |
| `PX_K8S_TOLERATIONS` | no | `key[=value]:Effect[,...]` | `tolerations` on every pod. |
| `PX_K8S_DEDICATED_NODES` | no, 0 | 0 or 1 | The quiet-host state of a run, described below. |
| `PX_K8S_MESH` | no, 0 | 0 or 1 | The service mesh overlay. |
| `PX_K8S_MESH_CORE` | no, 0 | 0 or 1 | Enrolls the core pods of the overlay in the mesh. |

Each toleration effect must be `NoSchedule`, `PreferNoSchedule`, or `NoExecute`; specifying
`key=value` matches a taint carrying that exact value (`operator: Equal`), whereas specifying `key`
alone matches any value for that taint key (`operator: Exists`). Any setting that violates its
declared grammar fails with exit code 2 and reports the offending key name. Any other `PX_*`
variable in a target file is processed as ordinary harness configuration. For example:

```sh
# ~/targets/perf-east.env, outside the repository
PX_K8S_CONTEXT=perf-east
PX_K8S_STORAGE_CLASS=gp3
PX_K8S_REGISTRY=registry.example.com/phoenix-harness
PX_K8S_PULL_SECRET=harness-pull
PX_K8S_NODE_SELECTOR=pool=phoenix-perf
PX_K8S_TOLERATIONS=dedicated=phoenix:NoSchedule
PX_K8S_DEDICATED_NODES=1
```

### Remote images and promotion

When `PX_K8S_REGISTRY` is configured, manifest rendering rewrites every container image reference to
an immutable digest of the form `<registry>/<repository>@sha256:<digest>` with pull policy
`IfNotPresent`, resolving digests exclusively from `PX_K8S_IMAGE_DIGESTS` (formatted as
comma-separated `<local ref>=sha256:<64 hex>` entries). Attempting a remote render without a
resolved digest for `PX_IMAGE_TAG` fails with exit code 2 and directs the operator to
`px-cluster push BUNDLE`:

```sh
px-cluster push "$PX_OUT/runs/<run-id>" --class measurement \
  -e PX_K8S_TARGET="$HOME/targets/perf-east.env"
px-cluster --backend k8s -e PX_K8S_TARGET="$HOME/targets/perf-east.env" up DIR
```

`px-cluster push` promotes the exact container images verified in a passing validation run. It
requires a run bundle that passes `px-bundle validate` with `run.class` set to validation and a
`run.result` that maps to exit code 0 (PASS, SKIP or ADVISORY, that is no check failed: timing
verdicts are advisory, and a check without a source is SKIP, a functional check such as
`query_errors` or `driver.rpc_calls` too), failing with exit code 1 otherwise or exit code 6 if the
bundle path does not exist, and whose recorded image references are local tags each mapped to a
single `sha256` digest. Each digest recorded in the bundle is either a local image ID or, for a run
executed on px-k3d, the configuration digest reported by the kubelet, which `sb_image_push`
resolves to the unique local image whose descriptor carries that digest. For each image, it invokes
`sb_image_push` on the Compose backend to push that local image to
`<PX_K8S_REGISTRY>/<repository>:<tag>`, which verifies that the registry stores the pushed image
under its local ID (failing with exit code 1 without rendering if it does not), renders the target
deployment manifests with `PX_K8S_IMAGE_DIGESTS` populated with the resulting registry digests into
`$PX_OUT/deploy-k8s/push-<run id>`, and writes that directory path to stdout while logging an INFO
line to stderr for each pushed image (noting any configuration digest that was mapped). In `-n`
dry-run mode, the command does not query the container engine, so its plan rows report the bundle
digests directly and an INFO message notes this distinction. Because neither the push operation
nor its manifest render
consults a cluster record, an active local cluster neither alters the promoted render nor has its
own deployment directory modified; `render.env` inside the rendered directory persists the
resolved digests so that subsequent scaling operations on the promoted cluster require no
repeated digest settings. The command never logs in to a registry, builds an image, invokes
`kubectl`, or creates a Kubernetes secret. The `PX_K8S_REGISTRY` value must be lowercase, and its
hostname component must contain a `.`, include a port, or be `localhost`; any other value is
rejected with exit code 2 during both manifest rendering and `px-cluster push` before any push is
attempted, because the container engine interprets a single-word prefix such as
`apurtell` as a user namespace on the public Docker Hub registry.

Image promotion requires the container engine to use the containerd image store. Under the
containerd store, the local image ID is the digest of the image manifest stored by the registry, so
the local and registry digests match. Under the classic overlay2 image store, the local image ID is
the digest of the image configuration JSON, which never matches a registry manifest digest and
causes every promotion attempt to fail with exit code 1. Milestone K3 validates this image store
behavior on a live environment (owner question 2).

### The mesh overlay

When `PX_K8S_MESH=1` is enabled, the manifest generator applies the mesh overlay from
`k8s/overlays/mesh` after rendering the base manifests: the namespace receives the label
`istio-injection: "enabled"`, and every pod in the core group (all cluster daemons alongside the
client and tooling pods) receives the annotation `sidecar.istio.io/inject: "false"`, or `"true"`
when `PX_K8S_MESH_CORE=1` is also set. Because a namespace with injection enabled injects a sidecar
into any pod lacking an explicit annotation, setting `"false"` explicitly keeps the core cluster
pods outside the mesh by default, while the setup Job unconditionally retains its opt-out
annotation. Because the overlay modifies nothing else, a mesh-enabled render differs from a flat
render solely by those added metadata lines. Both flags default to 0 in `k8s.env`, so renders
without explicit mesh settings remain flat across both run classes, whereas a validation target that
sets `PX_K8S_MESH=1` activates the overlay. The Distributed Pherf tier forms the primary mesh
group: when the overlay is active, the pod templates of both tier Deployments automatically receive
`sidecar.istio.io/inject: "true"` without requiring any additional opt-in, regardless of
`PX_K8S_MESH_CORE`.

### The local cluster: px-k3d

`bin/px-k3d` creates, imports images into, checks, and deletes the local k3d cluster associated with
the target context, which defaults to `k3d-px` (defined in `k8s/env/k3d.env` and loaded after
operator configuration files and prior to the cluster record); the k3d cluster name is derived by
stripping the `k3d-` prefix from the context name. The command invokes `k3d` through the resolved
absolute binary path stored in `_PXD_K3D`, discovered by searching `PATH` followed by
`/opt/homebrew/bin`, `/usr/local/bin`, and `/usr/bin`.

* Every `k3d` invocation receives the two helper image pins from `docker/versions.env` as
  `K3D_IMAGE_LOADBALANCER` (`PX_K3D_LOADBALANCER_IMAGE`, the service load balancer) and
  `K3D_IMAGE_TOOLS` (`PX_K3D_TOOLS_IMAGE`, the tools node used by `create` and `import`), ensuring
  that the container engine pulls each helper image by its pinned digest rather than
  `<repository>:<k3d version>`. Each pin must conform to `<repository>:<tag>@sha256:<64 hex>`,
  where the digest identifies the image index for the tag; any malformed value causes `create` and
  `import` to fail with exit code 2 before `k3d` is invoked, whereas `delete` starts no helper
  container and proceeds normally. Operators can re-pin both helper images in `versions.local.env`.
* `create [--class validation|measurement] [--agents N]` rejects with exit code 2 any `PX_K3S_IMAGE`
  pin that does not match `k3s(:<tag>)?@sha256:<64 hex>` (in `docker/versions.env`), and rejects
  with exit code 3 an existing Compose cluster record on the host, a k3d version below
  `PX_K3D_MIN_VERSION`, an existing k3d cluster with the target name, or another local `px-k3d`
  state record (unless `PX_FORCE=1` marks that record as stale). If cluster creation fails or times
  out, the command deletes any partial cluster left behind by k3d—which the pre-creation gate
  confirmed did not exist beforehand—and removes the state record; if that cleanup deletion also
  fails, the state record is retained so a subsequent `px-k3d delete` can remove it. The command
  invokes `k3d
  cluster create px --image <pin> --servers 1 --agents <N> --api-port 127.0.0.1:6550 --k3s-arg
  --disable=traefik@server:* ... --kubeconfig-update-default --kubeconfig-switch-context=false
  --wait --timeout <s>`. The measurement topology uses 0 agents, disables the service load balancer
  (`--k3s-arg --disable=servicelb@server:*`), and publishes no host port mappings; the validation
  topology uses N agents (0 to 9, defaulting to 2), retains the service load balancer, and maps
  `127.0.0.1:8081:80@loadbalancer`. Every published port binds exclusively to 127.0.0.1, and the
  active context in the operator's kubeconfig remains unchanged.
* `import [IMAGE...]` executes `k3d image import -c px <image>` once for each specified local image
  tag, defaulting to every image tag required by the active configuration: `PX_IMAGE_TAG`, plus
  `PX_PHERF_IMAGE_TAG` when `PX_PHERF=1` is enabled. Running `px-cluster build --backend k8s`
  automatically invokes this import step after building the images. No container registry is
  created and no image is pushed.
* `check` executes prior to `create` without requiring a state record. It emits the `k3d_binary`,
  `k3d_version`, `k3d_helpers`, and `engine_memory` check rows, querying total container engine
  memory through the Compose backend, which `px-k3d` binds solely to produce that row. The
  `k3d_helpers` row reports `ok` when both helper pin tags match the installed k3d version, `warn`
  with re-pinning guidance on a version mismatch (as version skew never rejects the check), and
  `fail` (returning exit code 3) if a helper pin lacks a digest.
* `delete` fails with exit code 3 if an active Kubernetes scope is still recorded for the target
  context; otherwise, it deletes the k3d cluster and removes its state record.

The state file `$(px_state_dir)/px-k3d/scope-<context>.tsv` (recording the scope and created_at
fields) tracks each cluster provisioned by `px-k3d`. Both `delete` and `import` verify this record
before invoking any external tool: when no record exists for the target context, they terminate
immediately with exit code 6 without calling `k3d`, guaranteeing that `px-k3d` never removes an
externally managed cluster. Targeting a context that does not follow the k3d naming convention,
such as a remote cluster, causes every subcommand except `create` to exit with code 6, whereas
`create` rejects it with exit code 2. The single-cluster-per-host invariant is enforced
symmetrically: `px-k3d create` refuses to run if a Compose scope record is present, while
`px-cluster up` on the Compose backend fails with exit code 3 if a `px-k3d` state record exists.
Overall, the command exits with code 0 on success, 1 if k3d is absent or fails, 2 on invalid usage
or an image pin lacking a digest, 3 when a pre-creation gate or version floor check rejects the
operation, 4 when a wait operation expires, and 6 when no `px-k3d` cluster is recorded for the
target context during `delete` or `import`.

### Dedicated nodes and the end of a measurement

Because CPU utilization on the operator's local workstation does not reflect the load on the worker
nodes of a remote Kubernetes cluster, px-run replaces the local quiet-host check with a
dedicated-node check on remote targets. Setting `PX_K8S_DEDICATED_NODES=1` records the quiet state
as `dedicated`
without flagging run contamination, allowing measurement runs to produce authoritative timing
verdicts. A target cluster without that setting is rejected with exit code 2 prior to the prepare
phase whenever the run requires a quiet host (`PX_REQUIRE_QUIET_HOST`, which defaults to 1 in the
measurement class); when a quiet host is not required, the run records the quiet state as
`undeclared` and marks the bundle with `dedicated-node` contamination, downgrading timing verdicts
to ADVISORY while preserving the full severity of count-based verdicts. Because the nodes of a
cluster provisioned by px-k3d (identified by a px-k3d state record for the context) run as
containers inside the local container engine, a run targeting that cluster executes the local
quiet-host check just as on Compose—applying the same wait bound (`PX_QUIET_HOST_WAIT_S`), the same
refusal (exit 4 when a quiet host is required and the host remains busy), and the same `quiet`
record rows—while always adding `dedicated-node` contamination so that timing verdicts remain
ADVISORY and the run
evaluates count parity only. Both the workstation sleep inhibitor and the cluster health monitor
operate on Kubernetes just as they do on Compose.

Test runs leave the cluster running upon completion. On Kubernetes, `px-cluster down` deletes the
harness namespace created by `px-cluster up` regardless of the run class while leaving the
underlying Kubernetes cluster intact for reuse, because the cluster itself was provisioned by
`px-k3d create`. Tearing down an additional cluster requires the operator to target it explicitly by
label: invoking `px-cluster down --label k3d.cluster=px` deletes the harness namespace and then
delegates to `px-k3d delete` to remove the local k3d cluster `px`. If px-k3d has no record of that
cluster, the deletion step returns exit code 6 and leaves all other resources untouched.

### What K3 and later prove live

Milestone K3 installs k3d and validates on a live cluster the runtime behaviors that a simulated
fake cannot prove: the pinned k3s image digest, the `--k3s-arg` node filters and loopback bindings
of `--api-port` and `-p`, the actual memory footprint of k3s relative to `PX_K3S_ALLOWANCE_MIB`,
the resolved image ID following an image import, live port-forward tunnel creation and termination
upon pod restart, the event reasons emitted by a genuine out-of-memory kill, DNS search domain
resolution, the pod-index label, probe timing behavior, the shared persistent volume claim mounted
by the setup Job, `kubectl cp`, resource metrics sampling, and drain-aware restarts. Live cluster
execution established several runtime behaviors that cannot be observed against a simulated fake:

* Across a three-node validation cluster, the node containers consume approximately 780 MiB when
  idle and up to 2210 MiB beyond the pod working sets under load; accordingly, `k3d.env` configures
  the node memory allowance at 2304 MiB. With this allowance reserved, a five-RegionServer
  deployment at validation sizing does not fit within a 24 GiB container engine.
* Following `px-k3d import`, the kubelet reports the digest of the image configuration as the
  container's image ID rather than the manifest digest used as the local image ID by the containerd
  image store. Consequently, a Kubernetes run bundle records a digest that does not match any local
  image ID, and `px-cluster push` resolves that digest back to the local image via the
  `config.digest` annotation on the image descriptor.
* A cgroup out-of-memory kill sets the container termination reason to `OOMKilled` with exit code
  137, while k3s emits neither an `OOMKilling` nor an `OOMKilled` pod event, making the container
  termination state the sole evidence of the kill.
* Under the 250 millicores per GiB CPU allocation ratio, the 125-millicore tooling pod takes 45 to
  50 s to start the region mover JVM, yielding an overall drain-aware restart duration of roughly
  130 to 170 s that is accommodated by the 220 s Kubernetes recovery bound.

Subsequent milestones build on this baseline: milestone K4 validates behavioral parity with Compose
and end-to-end measurement run teardown; milestone K5 validates the Distributed Pherf tier and its
mesh role group; milestone K6 validates service mesh integration and image promotion against a live
registry; and milestone K7 validates node placement, resource quotas, and operator-prepared
namespaces on dedicated hardware.
