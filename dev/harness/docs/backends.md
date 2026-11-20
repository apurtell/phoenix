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
Because every layer above the contract interacts exclusively through the 35 `sb_*` functions
defined below, drivers, checks, suite actions, health monitors, collectors, and operator commands
execute without modification across all backends, and no component above the contract requires
direct access to a container runtime.

This document serves both as the definitive specification for the substrate contract and as the
sole reference required to implement an additional backend.

* `contract.sh` acts as the single source of truth for function names, output schemas, value
  vocabularies, and exit codes. It declares these definitions, binds a single backend per process,
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
| `sb_verify_backend` | Lists missing required names on stdout; returns 0 if complete, 1 if not. |
| `sb_supports <fn>` | Returns 0 if available, 1 if unimplemented, or 2 if not a contract name. |
| `sb_call <fn> [arg...]` | Calls it; returns 5 if not implemented or 2 if not a contract name. |
| `sb_capability <name>` | Writes `yes` or `no` to stdout; exits 0 (yes), 1 (no), 6 (undeclared). |
| `sb_capability_detail <name>` | Emits that capability row's detail column; exits 6 when absent. |
| `sb_contract_required`, `sb_contract_optional`, `sb_contract_functions` | Emits the name lists. |

Both `sb_capability` and `sb_capability_detail` return exit code 1 with no output when no backend
is currently bound or when the bound backend cannot report its capability table.

If given an invalid or unknown backend name, `sb_load` terminates the process with exit code 2 and
reports the implementation file path it attempted to locate. It terminates the process with exit
code 1 if the backend's `sb_contract_version` does not match `PX_CONTRACT_VERSION`—preventing
partially upgraded backends—or if the backend omits any required contract function, listing the
missing names. A subsequent `sb_load` call naming the already-bound backend returns exit code 0 so
that commands can invoke it idempotently without guards, whereas a subsequent call naming a
different backend terminates the process with exit code 1 because each process may bind only a
single backend.

`PX_CONTRACT_VERSION` is a read-only constant defined in `contract.sh` and is currently set to 1.
It is not a configurable parameter: the configuration loader rejects any attempt to set it with
exit code 2, preventing configuration files or environment variables from altering the contract
revision enforced by `sb_load`. Each backend hardcodes its implemented revision as a literal, and
`sb_load` verifies that the two values match. Both values must be incremented whenever a function
name, argument signature, stdout format, exit code, or schema definition changes.

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
| 4 | `SB_EXIT_TIMEOUT` | timeout | A bounded wait operation expired before completion. |
| 5 | `SB_EXIT_UNSUPPORTED` | unsupported | The backend cannot perform it and declares so. |
| 6 | `SB_EXIT_NO_OBJECT` | no such object | The scope, role, index, port, or value is absent. |

When a container runtime fails to respond, functions return failure code 1; returning code 6
requires an affirmative response from the runtime confirming that the target object is absent.
Three functions deviate from this rule, as noted in their individual specifications below:
`sb_exec` reports an unresponsive runtime as exit code 125, while `sb_events` and
`sb_sample_resources` report it as exit code 5.

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
program running inside a role must never exit with any of these four codes.

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
event       ts_ms role index type reason message
            sb_events
sample      ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes
            tx_bytes blk_read_bytes blk_write_bytes
            sb_sample_resources
capability  capability supported detail
            sb_capabilities
check       check status detail
            sb_preflight
kv          key value
            sb_scope_args, sb_version_info
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

## Capabilities

The substrate contract comprises 30 required functions and 5 optional functions. When a function or
feature is optional, callers must query support beforehand and record an unsupported operation as a
skip rather than a failure. Backends advertise their actual feature support through
`sb_capabilities` using a fixed set of capability identifiers that callers can query by name.

| Capability | What it governs | Compose |
|---|---|---|
| `logs_previous` | Logs of a previous container. | no, as Compose retains no previous record. |
| `previous_termination` | Exit evidence of a previous container. | no, for the same reason. |
| `resource_sampling` | `sb_sample_resources` | probed: yes when a no-stream stats pass responds. |
| `signal_delivery` | `sb_signal` | yes, via the container runtime kill verb. |
| `events` | `sb_events` | yes, read from the container runtime event stream. |
| `drain_restart` | `sb_restart_drain` | yes, for any role whose drain label is `regionmover`. |
| `port_forward` | A port forward freed by `sb_endpoint_release`. | no, the port is published. |
| `scale` | `sb_scale` | yes, within the replica limit of 1 to 9. |
| `group_switch` | `sb_group_stop` and `sb_group_start` | yes, selected via the group label. |
| `oom_evidence` | `sb_oom_killed` | yes, as the container runtime reports the kill. |
| `image_digest` | Reporting a digest in the `image` row. | no, reports a local image id instead. |
| `node_placement` | Reporting a real `node` column. | no, as the node is always `localhost`. |

Any declared capability omitted from a backend's table is treated as absent so that callers degrade
gracefully rather than failing. The contract layer reads and caches the capability table once per
process. Furthermore, the Compose backend evaluates its single dynamically probed capability once
and persists the complete table to `$(px_state_dir)/compose/capabilities.tsv` for subsequent
processes on the same host to reuse, unless `PX_FORCE=1` is set to force a fresh probe.

Two important behavioral rules follow from this capability model and govern cluster health checks.
First, when `logs_previous` and `previous_termination` are unsupported, restart analysis falls back
to comparing restart counts and inspecting the current termination reason, ensuring that an
unplanned container restart still fails the test run with exit code 3 on Compose. Second, when
`image_digest` is unsupported, the bundle manifest records the local image identifier, restricting
digest-based image promotion workflows exclusively to the Kubernetes backend.

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

`sb_scope_resolve` determines the target scope identifier for a command. All cluster-targeted
commands (`px-cluster`, `px-run`, `px-suite`, `px-tool`, `px-collect`, and `px-pherf`) rely on this
single resolution precedence:

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
in this section and nothing else, a hand-written test deployment provisions identically to a
generated one when it conforms to these rules, including the memory limit requirement described
below. Treated as run output, the deployment directory is rendered beneath `$PX_OUT` by the
generator. This section is normative for the generator.

The generator for the Compose backend is `docker/compose.gen.sh`, invoked by `px-cluster render`.
It exclusively writes files to the deployment directory, and the backend reads no file that is not
specified in this section.

### compose.yaml

The root of the deployment directory must contain a file named `compose.yaml`; no alternative
compose file names are recognized, and a directory lacking `compose.yaml` fails with usage exit
code 2. On every Compose invocation, the backend explicitly passes the project name
`<PX_PROJECT_PREFIX>-<cluster>` along with the compose file path and project directory while the
file exists, making an embedded project name inside `compose.yaml` unnecessary. Every service must
configure its restart policy as `no` so that failed containers remain terminated and preserve
accurate failure evidence; the backend neither restarts failed instances automatically nor validates
the restart policy. Services that define a container healthcheck derive their readiness state from
that probe, whereas services without a healthcheck are considered ready while running, and one-shot
services are considered ready once they exit with status 0.

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
commands invoked via `sb_exec` (such as the region mover or `px-tool shell`) do not inherit the
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
Section "Versions and the HBase profile" in the README defines this version set.

## The 35 functions

Of the 35 contract functions, 30 are required—and `sb_load` rejects any backend that omits even one
of them—while 5 are optional: `sb_signal`, `sb_restart_drain`, `sb_events`, `sb_sample_resources`,
and `sb_endpoint_release`. Before invoking an optional function, callers must check `sb_supports`
or `sb_capability`, or else invoke it through `sb_call`, which translates an unimplemented function
into exit code 5. Callers must always record exit code 5 as a skipped operation rather than a
failure.

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

### Identity and introspection

#### `sb_backend_id`

Identifies the currently bound backend so that callers and the bundle manifest can record which
substrate produced a test result.

* **stdout** Outputs a single line containing the backend identifier.
* **exit** Always returns exit code 0.
* **Compose** Outputs the literal string `compose`.

#### `sb_contract_version`

Reports the substrate contract revision implemented by the backend; `sb_load` rejects the backend
if this value does not match `PX_CONTRACT_VERSION`.

* **stdout** Outputs a single line containing a decimal integer.
* **exit** Always returns exit code 0.
* **Compose** Outputs the literal value 1 in `compose.sh`, matching `PX_CONTRACT_VERSION` 1.

#### `sb_version_info`

Reports version metadata for the underlying container tooling to include in the bundle manifest and
diagnostic bug reports.

* **stdout** Emits `kv` rows pairing each component name with its version string.
* **exit** Returns exit code 0 on success, or 1 when the container runtime cannot be reached.
* **Compose** Emits rows for `engine_client`, `engine_server`, and `compose_version`, obtaining the
  last from the compose subcommand of the Docker CLI. If the engine client does not respond, the
  function returns exit code 1 without emitting rows; if the server daemon does not respond, it
  emits the rows with an empty `engine_server` value and returns exit code 1.

#### `sb_capabilities`

Advertises which optional capabilities the backend supports so that callers can degrade gracefully
rather than failing on unsupported operations.

* **stdout** Emits `capability` rows containing the capability name, `yes` or `no`, and a detail
  message explaining how the capability is satisfied or why it is unavailable.
* **exit** Always returns exit code 0; even if the runtime is unreachable, the backend responds
  with `no` rows.
* **Compose** Emits the capability table described in the capabilities section above. An inability
  to write the cache file in the state directory does not prevent the function from outputting the
  table. Results produced while the engine is unresponsive are never cached, ensuring that
  subsequent invocations re-probe the runtime.

#### `sb_preflight scope_id`

Executes the backend half of host preflight validation, verifying whether the runtime engine is
present and reachable, whether the caller is authorized, whether tool versions meet their minimum
floors, whether the mapped ports are free, and how much memory and how many processors the engine
holds. The host half—which sums the configured resource limits against available memory, disk space,
and processors—resides in `scripts/preflight.sh` (see the README) and reads the `engine_memory` and
`engine_cpus` rows. Because `sb_preflight` checks that mapped ports are free, a full preflight check
fails against a cluster that is already running. The memory check executed before starting a role
group also calls it, but reads only those two rows and ignores the port availability result.

* **stdout** Emits `check` rows containing the check name, a status of `ok`, `fail`, or `warn`, and
  an explanatory detail string.
* **exit** Returns exit code 0 when no check fails, 3 when at least one check fails, and 2 on
  invalid usage or when the configuration loader rejects a port-map line. A `warn` row does not
  alter the exit code.
* **Compose** Evaluates the checks `engine_binary`, `engine_responsive`, `engine_version` (minimum
  floor 20.10), `engine_credentials`, `compose_version` (minimum floor 2), `engine_memory`,
  `engine_cpus`, and `port_availability`. Evaluation stops at the first engine fault. When no engine
  binary is found, the function returns after emitting two `fail` rows. When the version query
  fails, evaluation also stops immediately: `engine_credentials` fails after an `engine_responsive`
  row of `warn` when the error text indicates that the engine refused the client (permission denied,
  unauthorized, or access denied), whereas `engine_responsive` fails otherwise. `engine_memory`
  reports the total memory available to the engine—which on a desktop host runs inside a virtual
  machine with a limit of its own—and reports `warn` when the engine reports no total. `engine_cpus`
  reports the processor count of the engine from `info {{.NCPU}}` and reports `warn` when the engine
  reports no number. `port_availability` fails when any port in the loaded map (the set of ports the
  deployment publishes) already accepts a connection on 127.0.0.1 of the host, and reports `warn`
  when no port map is loaded.

### Scope

A scope represents the isolation envelope encapsulating a single cluster. It enforces the
one-cluster-per-host concurrency gate and establishes the label boundary that prevents one test run
from interfering with another.

#### `sb_scope_create cluster_id`

Initializes the cluster scope envelope and outputs its opaque scope identifier, which every
subsequent cluster operation accepts as its first argument.

* **stdout** Outputs a single line containing the newly created scope identifier.
* **exit** Returns exit code 0 when created; 1 if the concurrency gate refuses creation, the
  runtime fails to respond, or the scope record cannot be written; and 2 on invalid usage or when
  the cluster identifier fails to match `SB_CLUSTER_RE`.
* **Compose** Sets the scope identifier to the project name `<PX_PROJECT_PREFIX>-<cluster_id>`. The
  concurrency gate executes under the host lock `$(px_state_dir)/compose/host.lock`, waiting up to
  30 seconds and reclaiming stale locks left by terminated processes. The gate refuses creation if
  any existing container on the host carries a harness cluster label, if the backend already holds
  a scope record for another scope, or if it already holds a record for the requested scope;
  setting `PX_FORCE=1` overrides the two record-based refusals to recover from a stale record. When
  the gate refuses because of an existing container, the refusal message identifies both its Compose
  project and its cluster. Once the gate passes, the backend writes its internal scope record to
  `$(px_state_dir)/compose/scope-<scope>.tsv` and sets `PX_SCOPE_ID` in the current process. This
  backend record is separate from the contract-level scope record managed by `px-cluster`.

#### `sb_scope_exists scope_id`

Provides an idempotence check for callers that must verify whether a cluster scope exists rather
than assuming it is active.

* **stdout** Emits no output.
* **exit** Returns exit code 0 if the scope exists, 6 if it is absent, 1 if the runtime fails to
  respond and no local record exists, and 2 on invalid usage.
* **Compose** Treats any container carrying the cluster label as definitive proof of existence,
  while also treating the presence of the backend's internal scope record alone as existence so
  that an initialized scope with no provisioned instances cannot be created a second time.

#### `sb_scope_args scope_id`

Outputs the backend-specific scoping parameters for diagnostics and bundle manifest recording,
ensuring that higher-level callers never construct backend CLI invocations themselves and keeping
the runtime boundary strictly enforceable.

* **stdout** Emits `kv` rows describing the scope parameters.
* **exit** Returns exit code 0 on success, 6 if the scope is unknown, 1 if the runtime fails to
  respond, and 2 on invalid usage.
* **Compose** Emits rows for `project`, `label_selector`, `compose_file`, and `network`.

#### `sb_scope_delete scope_id`

Deletes the scope envelope itself after instance teardown has completed, ensuring that no orphaned
resources survive a test run.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success, 1 if any resource could not be removed (reporting the
  remaining objects on stderr), and 2 on invalid usage.
* **Compose** Removes the Compose project network along with every volume carrying either the
  harness cluster label or the Compose project label—unless `PX_KEEP` is set—and then deletes the
  backend scope record.

### Lifecycle

#### `sb_provision scope_id deployment_dir [timeout_s]`

Creates all instances defined in the rendered deployment directory without waiting for service
readiness. The expected structure of the deployment directory is specified in the deployment
directory section above.

* **stdout** Emits `instance` rows, one for each provisioned instance.
* **exit** Returns exit code 0 on success; 1 if creation fails; 2 on invalid usage or a malformed
  deployment directory; 3 if an instance terminates unexpectedly during creation; and 4 if the
  operation exceeds its timeout (defaulting to `PX_TIMEOUT_PROVISION`).
* **Compose** Records the deployment directory and `compose.yaml` paths in the backend scope
  record, sets `PX_DEPLOYMENT_DIR`, and loads the port map. It then runs `up --no-start --no-build`
  to create all service containers, followed by `up -d --no-recreate --no-build` naming every
  service that is not on-demand. A deployment directory whose created containers lack the scope's
  cluster label is rejected as malformed. The emitted output rows include on-demand instances in
  their created, non-running state.

#### `sb_teardown scope_id keep_data`

Stops and removes all instances belonging to the scope. The `keep_data` flag accepts 0 or 1, where
`keep_data=1` implements the `--keep` diagnostic preservation mode.

* **stdout** Emits no output.
* **exit** Returns exit code 0 when teardown completes cleanly; 1 if leftover resources remain
  (which are listed on stderr), the runtime fails to respond, or the teardown wait expires; and 2
  on invalid usage.
* **Compose** Invokes `down --remove-orphans` with a stop grace period of `PX_TIMEOUT_STOP`—adding
  `--volumes` when `keep_data` is 0—under an overall timeout of `PX_TIMEOUT_TEARDOWN`. Afterward,
  it verifies that no containers (and, when `keep_data=0`, no volumes) belonging to the scope
  remain. If the recorded compose file no longer exists on disk, the backend invokes Compose using
  only the project name, which still locates and removes the project's containers.

#### `sb_wait_ready scope_id [timeout_s] [role...]`

Enforces a finite readiness barrier across the selected instances, consistent with the harness rule
that all waits have a bounded timeout.

* **stdout** Emits `ready` rows, one per selected instance, reporting `ready`, `unready`, or
  `stopped` for instances intentionally skipped during a whole-scope wait.
* **exit** Returns exit code 0 when all selected instances are ready; 3 if any watched instance
  terminates with a non-zero exit status before or during the wait; 4 if the timeout expires; 6 if
  the selection matches no instances (accompanied by an error message naming the unmatched target);
  1 if the runtime fails to respond; and 2 on invalid usage. The default timeout is
  `PX_TIMEOUT_READY`.
* **Compose** Polls the container inventory at intervals of `PX_POLL_INTERVAL_S` (which may be a
  fractional number of seconds) until all selected instances report ready. When invoked with no
  role arguments, it targets every instance in the scope except members of role groups that are
  intentionally stopped at the start of the wait (as defined under the `group` label), skipping
  those instances and reporting their status as `stopped`. All other groups remain under
  observation, so any member instance that exits with a non-zero status before or during the wait
  triggers exit code 3. When explicit role names are provided, the function waits for every named
  role.

### Access

The following four functions provide a test driver's complete operational interface to a running
cluster, eliminating any need for components above the backend contract to invoke a container
runtime directly.

#### `sb_exec scope_id role index [timeout_s] -- cmd [arg...]`

Executes a single command inside a specific role instance. All workload drivers, cluster checks,
region-mover invocations, and in-cluster diagnostic tools operate through this function.

* **stdout** Streams the executed command's stdout verbatim, byte for byte, while forwarding its
  stderr directly to stderr.
* **exit** Propagates the executed command's own exit status; returns 124 if the execution timeout
  expires; 125 if the substrate fails to respond, the target instance is not running, or execution
  cannot be initiated; 126 or 127 as reported by the runtime; 2 on invalid usage; and 6 if the
  specified instance does not exist. The default timeout is `PX_TIMEOUT_EXEC`.
* **Compose** Runs `exec -T --index=<n>` against the target service—or an interactive exec when
  stdin is attached to a terminal—wrapped in `px_timeout`. If the client process survives the grace
  period following timeout expiration, it is terminated with a KILL signal and exits with status
  137, which is indistinguishable to the caller from an in-container command killed by signal 9.

#### `sb_logs scope_id role index since_s previous follow`

Streams log output from a single role instance. `since_s` specifies a lookback window in seconds or
`-` to retrieve the complete log history, while `previous` and `follow` accept boolean flags 0 or
1.

* **stdout** Streams the instance's combined stdout and stderr log bytes.
* **exit** Returns exit code 0 on success, 1 if the runtime cannot read the logs or fails to
  respond, 5 if `previous` logs were requested on a backend that does not support them, 6 if the
  instance does not exist, and 2 on invalid usage.
* **Compose** Invokes `logs` on the target container with the corresponding since and follow
  options. When following logs, the stream runs until closed by the reader; otherwise, the command
  executes under `PX_TIMEOUT_DEFAULT`. Specifying `previous=1` immediately returns exit code 5
  prior to any container lookup because Compose does not retain previous containers and
  `sb_capabilities` declares `logs_previous` as unsupported.

#### `sb_copy_in scope_id role index local_path remote_path`

Copies a local file or directory from the host into a target role instance, such as staging a
workload specification, SQL script, profiler agent, or plug-in overlay.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success, 1 if the copy operation fails or the runtime fails to
  respond, 2 on invalid usage or when the local source path does not exist, and 6 if the target
  instance does not exist.
* **Compose** Executes `cp --index=<n> <local> <service>:<remote>` under `PX_TIMEOUT_DEFAULT`.

#### `sb_copy_out scope_id role index remote_path local_path`

Retrieves an artifact from a role instance to the host filesystem, such as a profiler capture, Java
Flight Recording, garbage-collection log, histogram log, or server-side command output.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success; 1 if the copy operation fails, the runtime fails to
  respond, or the destination path cannot be resolved; 2 on invalid usage or if the destination
  path escapes `$PX_OUT` or resides inside the repository working tree; and 6 if the target
  instance does not exist.
* **Compose** Validates the local destination path through `px_out_resolve` first, creates the
  destination's parent directory, and executes `cp --index=<n> <service>:<remote>` targeting the
  validated path under `PX_TIMEOUT_DEFAULT`. Enforcing the output directory policy at this lowest
  substrate layer guarantees that an invalid destination path is rejected before ever reaching the
  container runtime.

### Topology

#### `sb_scale scope_id role count [timeout_s]`

Adjusts the replica count of a scalable role within the 1 to 9 instance bound enforced across both
backends. Scaling up blocks until all instances of the role report ready, whereas scaling down
terminates the highest-indexed instances first so that remaining instance indices stay contiguous
and refer to the same underlying instances.

* **stdout** Emits `instance` rows representing the complete resulting instance set for the scaled
  role.
* **exit** Returns exit code 0 on success; 1 if scaling fails, the role fails to achieve readiness,
  or the runtime fails to respond; 2 if the requested count falls outside the 1 to 9 range or the
  role has no instances in the scope; and 4 if the operation exceeds its timeout (defaulting to
  `PX_TIMEOUT_SCALE`).
* **Compose** Runs `up -d --no-recreate --no-build --no-deps --scale <service>=<count> <service>`
  so that no unrelated services are started or modified, and then invokes `sb_wait_ready` restricted
  to that role. Because a role whose instances carry an explicit index label defines a dedicated
  service per instance with its own published ports, adding a service replica would duplicate both
  the index and the ports. Consequently, such a role cannot be scaled in place: the call fails with
  exit code 2 and instructs the caller to re-render the deployment with the updated count and bring
  the cluster up again.

#### `sb_group_stop scope_id group [timeout_s]`

Stops all instances belonging to a named role group while preserving their container state and
volumes so that the group can be restarted later within the same test run. This provides the
on-demand lifecycle switch for role groups that are only needed during specific test phases.

* **stdout** Emits `stopped` rows reporting `stopped` for each instance that was running and
  `already_stopped` for each instance that was already halted.
* **exit** Returns exit code 0 on success, 1 if any member instance remains running or the runtime
  fails to respond, 2 if no instance in the scope carries the specified group label, and 4 if the
  timeout expires (defaulting to `PX_TIMEOUT_GROUP`).
* **Compose** Invokes `stop` with a grace period of `PX_TIMEOUT_STOP` across all services matching
  the group label. Container instances and attached volumes are preserved; the operation never runs
  `down` or removes containers. Prior to stopping the services, the backend records the stopped
  group in its scope record so that subsequent whole-scope `sb_wait_ready` calls recognize that the
  group is stopped intentionally; `sb_group_start` removes the group from this record.

#### `sb_group_start scope_id group [timeout_s]`

Starts a previously stopped or on-demand role group and waits until all of its member instances
become ready, forming the counterpart to `sb_group_stop`.

* **stdout** Emits `ready` rows for the group's instances.
* **exit** Returns exit code 0 when all member instances are ready; 1 if the start operation fails,
  the runtime fails to respond, or the group resolves to an empty role list; 2 if no instance
  carries the specified group label; 4 if the start command times out; or propagates the exit status
  of the subsequent readiness wait (including 3 if an instance terminates unexpectedly and 4 if the
  readiness wait expires). The default timeout is `PX_TIMEOUT_GROUP`.
* **Compose** Invokes `start` across the services belonging to the group, followed by
  `sb_wait_ready` restricted to the group's roles. An empty role list is treated as an error and
  never causes the readiness wait to widen to the entire scope.

Callers execute the host memory preflight check prior to starting a group because activating the
group adds its container memory limits to the active host footprint. The README defines that memory
check.

#### `sb_group_status scope_id group`

Reports the operational state of a role group so that test suites can declare group dependencies
and workload drivers can cleanly skip rather than fail when a required group is intentionally
stopped.

* **stdout** Emits a single `group` row reporting `up` when every member instance is running,
  `down` when no member instances are running, and `partial` otherwise.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, and 2 if no instance
  carries the specified group label.
* **Compose** Compares the count of currently running containers against the total number of
  containers labeled for that group.

### Faults

Because fault injection is always intentional, callers record every fault operation in the
planned-action log before initiating it (see the planned-action record section above) so that
post-run cluster checks do not classify the resulting disruption as an unplanned restart anomaly.

#### `sb_kill scope_id role index mode`

Abruptly terminates a single role instance at a test milestone. The `mode` argument accepts either
`hard`, which immediately kills the container process without graceful shutdown while leaving the
instance object intact, or `delete`, which removes the instance object entirely so that the
backend's native recreation semantics take effect.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success, 1 if termination fails or the runtime fails to respond,
  2 if the specified mode is unrecognized, and 6 if the target instance does not exist.
* **Compose** Implements `hard` via `kill -s KILL` against the container and `delete` via `rm -f`,
  executing both commands under `PX_TIMEOUT_STOP`.

#### `sb_signal scope_id role index signal_name` (optional)

Delivers a named POSIX signal to a role instance to support non-destructive fault injection and
live diagnostics—such as `QUIT` to trigger a JVM thread dump, `STOP` and `CONT` to simulate an
extended process pause, or `TERM` to initiate a graceful shutdown.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success, 1 if signal delivery fails or the runtime fails to
  respond, 2 if the signal name is unrecognized, 5 if `sb_capabilities` declares `signal_delivery`
  unsupported, and 6 if the target instance does not exist.
* **Compose** Invokes `kill -s <name>` on the target container. Supported signal names are `HUP INT
  QUIT ABRT KILL ALRM TERM USR1 USR2 STOP CONT TSTP`, with or without the `SIG` prefix, in either
  letter case.

#### `sb_restart_drain scope_id role index [drain_timeout_s] [ready_timeout_s]` (optional)

Executes a graceful rolling restart of a role instance: draining hosted regions via the HBase
region mover, stopping the instance, starting it again, waiting for readiness, and reloading
regions to rebalance the cluster. This primitive resides inside the substrate contract rather than
in higher-level callers because the mechanism by which a replacement instance is created differs
between backends.

* **stdout** Emits `phase` rows in fixed sequence for `drain`, `stop`, `start`, `ready`, and
  `rebalance`. If any phase fails, its row reports `fail` and all subsequent phases report `skip`.
  Because an already exited instance runs no process, its `drain`, `stop`, and `rebalance` phases
  are recorded as `skip`. The `ready` wait inspects only the restarted instance.
* **exit** Returns exit code 0 when no phase fails; 1 if any phase fails or the runtime fails to
  respond; 4 if the drain phase, readiness wait, or rebalance phase times out; 2 if the target role
  lacks drain semantics or the region-mover command requires an `rpc` port that is absent from the
  port map; and 6 if the target instance does not exist. The default timeouts are
  `PX_TIMEOUT_DRAIN` and `PX_TIMEOUT_READY`.
* **Compose** Permits draining only for roles carrying the `regionmover` drain label. The backend
  executes the region-mover unload command via `sb_exec` under the drain timeout, stops the
  container with a grace period of `PX_TIMEOUT_STOP`, starts the container, polls that instance
  until it reports ready, and re-runs the region mover to reload regions. The readiness poll fails
  immediately if the instance exits and times out after the readiness timeout. The mover command
  line is expanded from a template—using `PX_REGION_MOVER_CMD` when configured—supporting the
  placeholders `{operation}` (`unload` or `load`), `{host}` (the container hostname), `{port}` (the
  instance's `rpc` port from the port map), `{timeout}`, and `{file}`.

### Evidence

Cluster health checks query the following evidence functions to determine whether a test run
completes with exit code 0 or fails with cluster anomaly exit code 3, and their outputs are
recorded in the bundle manifest.

#### `sb_list scope_id [role] [index]`

Serves as the single unified inventory query; every component that needs to inspect existing
instances, their lifecycle states, their running container images, or their host placement calls
this function.

* **stdout** Emits `instance` rows sorted lexicographically by role name and then numerically by
  instance index. An existing scope containing no instances emits no rows and returns exit code 0.
* **exit** Returns exit code 0 on success, 6 if a requested role or index does not exist, 1 if the
  runtime fails to respond, and 2 on invalid usage (including supplying an index without a role).
* **Compose** Inspects containers filtered by the scope's cluster label. In the emitted rows,
  `node` is always the literal `localhost`, `image_ref` reports the container's configured image,
  and `state` normalizes container statuses by mapping `running` to `running`, `created` and
  `restarting` to `pending`, `paused` to `stopped`, `exited` to `exited`, and any other runtime
  status to `unknown`.

#### `sb_restart_count scope_id`

Reports the cumulative involuntary-restart evidence across all instances in the scope. `px-run`
queries this function before a run begins, at each polling interval while the workload driver
executes, and again after the driver completes.

* **stdout** Emits one `restart` row per instance containing the role, the instance index, and the
  restart count as a decimal integer.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, and 2 on invalid
  usage.
* **Compose** Performs a single container listing for the scope followed by a single inspection call
  covering every container in that scope. Any restart count that is not numeric is reported as 0.

#### `sb_termination_reason scope_id role index [which]`

Normalizes container termination evidence into a unified vocabulary so that cluster checks and exit
codes behave identically across all backends. The optional `which` argument selects either
`current` (the default) or `previous`.

* **stdout** Emits a single `term` row where `reason` is one of `running`, `completed`, `error`,
  `oom`, `killed`, `evicted`, or `unknown`, and `signal` is populated whenever the numeric exit
  code exceeds 128.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, 2 on invalid usage,
  5 if `previous` termination state is unsupported, and 6 if the target instance does not exist.
* **Compose** Extracts the container status, exit code, out-of-memory flag, and finish timestamp
  from container inspection. A running, restarting, or paused container maps to `running`; an
  exited container maps to `oom` if the out-of-memory flag is set, `completed` if the exit status
  is 0, `killed` if terminated by a signal, or `error` otherwise; a dead container maps to `error`;
  and any other state maps to `unknown`. Requesting `which=previous` immediately returns exit code
  5 prior to any lookup.

#### `sb_oom_killed scope_id role index`

Reports a definitive boolean determination of whether an instance experienced an out-of-memory
kill, as any out-of-memory event must fail the test run.

* **stdout** Emits a single `oom` row containing `yes` or `no` followed by supporting diagnostic
  evidence.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, 2 on invalid usage,
  and 6 if the target instance does not exist.
* **Compose** Determines the verdict from the container inspection out-of-memory flag, while the
  evidence column records the flag value, the numeric exit code, and any runtime error message.

#### `sb_events scope_id since_s` (optional)

Retrieves backend lifecycle events to populate the run timeline and corroborate unexpected restart
or out-of-memory findings.

* **stdout** Emits `event` rows ordered chronologically from oldest to newest.
* **exit** Returns exit code 0 on success, 5 if the runtime cannot report events, and 2 on invalid
  usage (including a lookback window that is not an integer number of seconds).
* **Compose** Queries the runtime event stream covering the interval from `since_s` seconds ago up
  to the time of the call, filtered by the scope's cluster label. Container events derive their
  instance index using the same parser as `sb_list`, whereas non-container events leave the index
  column empty.

#### `sb_sample_resources scope_id` (optional)

Captures a point-in-time resource utilization snapshot across every running instance in the scope.
`px-collect` calls this function at a fixed interval, and the health monitor, `px-tool monitor`,
records its output in `samples.tsv` on each sampling pass.

* **stdout** Emits `sample` rows, one per running instance; if no instances are currently running,
  no rows are emitted.
* **exit** Returns exit code 0 on success, 5 if the runtime fails to respond or refuses a stats
  pass, and 2 on invalid usage.
* **Compose** Executes a single non-streaming stats pass and populates `mem_limit_bytes` from a
  single inspection of the configured container memory limit so that recorded samples compare memory
  usage against that configured limit rather than total host memory. A container without a
  configured limit reports the limit value returned by the stats pass.

### Addressing and images

#### `sb_endpoint scope_id role index port_name`

Performs service port discovery, returning a host-reachable address for a named service port—always
on the loopback interface—so that host-side tools and operators never hardcode port numbers.

* **stdout** Emits a single `endpoint` row.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, 6 if the instance is
  unknown or the port map contains no entry for the requested port, and 2 on invalid usage.
* **Compose** Verifies that the target instance exists, reads the port from the loaded port map,
  and returns `127.0.0.1` paired with that port. Because the deployment publishes that port on
  127.0.0.1 of the host, the returned address is directly reachable from the host.

#### `sb_endpoint_release scope_id role index port_name` (optional)

Releases a previously discovered endpoint so that long-running sessions do not leak background
port-forwarding processes and cluster teardown leaves no stray processes behind. The `port_name`
argument may be `all`.

* **stdout** Emits no output.
* **exit** Returns exit code 0 on success and 2 on invalid usage.
* **Compose** Performs a no-op after validating arguments: because ports are statically published
  as part of the Compose deployment, the function validates its arguments and returns exit code 0
  for any valid scope identifier belonging to the Compose backend.

#### `sb_image_ref scope_id role index`

Reports the container image actually running inside an instance, which is recorded in the bundle
manifest and later read back by the image promotion workflow from a passing test bundle.

* **stdout** Emits a single `image` row.
* **exit** Returns exit code 0 on success, 1 if the runtime fails to respond, 2 on invalid usage,
  and 6 if the target instance does not exist.
* **Compose** Outputs the configured image reference alongside the local image identifier obtained
  from container inspection. Because the `image_digest` capability is `no` on Compose, callers that
  require an immutable registry digest must check that capability first.

#### `sb_image_build context_dir tag [NAME=VALUE...]`

Builds the single multi-role container image from a staged build context so that the build
pipeline's image construction invokes the container engine exclusively through the substrate
contract like every other operation. Because this function and the two image functions that follow
operate at the host level rather than on a cluster scope, they take no scope identifier argument.
`scripts/stage-artifacts.sh` stages the build context under `$PX_OUT/images/` and invokes this
function; no other component builds an image.

* **stdout** Emits a single `image` row containing the tag as `image_ref` and the local image
  identifier.
* **exit** Returns exit code 0 when built; 1 if the build fails, the resulting image lacks an
  identifier, or the runtime fails to respond; 2 on invalid usage, a context directory without a
  `Dockerfile`, a tag that is not a local `repository:tag`, or a build argument not formatted as
  `NAME=VALUE`; and 4 if the build exceeds its timeout (defaulting to `PX_TIMEOUT_BUILD`).
* **Compose** Invokes the engine's `build` command with `--tag`, one `--build-arg` flag for each
  build argument, and `--file <context>/Dockerfile`, followed by an image inspection to retrieve the
  image identifier. All build output is directed to stderr. The image remains strictly local: any
  tag specifying a registry host or a digest is rejected, and nothing is pushed. The build fetches
  the base images pinned by digest in the `Dockerfile`, while its runtime stage installs four
  operating-system packages from the base distribution archive without version pins. The engine
  builds for the host architecture; arm64 is the primary supported architecture and amd64 is best
  effort, which the staging step notes in its output when running on an amd64 host.

#### `sb_image_present deployment_dir`

Reports each container image referenced by a deployment before cluster bring-up creates any
resources. `px-cluster up` calls this function prior to running preflight checks and rejects any
deployment whose required image is absent on the host, reporting the two commands that build it.

* **stdout** Emits one `image` row per referenced image, containing the image reference and the
  local image identifier (which is empty for any image not held by the engine).
* **exit** Returns exit code 0 when every referenced image is present; 1 if the runtime fails to
  respond; 2 on invalid usage or a directory lacking the deployment manifest file; and 6 when one or
  more images are absent.
* **Compose** Inspects each of the `image:` values declared in `compose.yaml` via the engine's
  `image ls` command. A deployment that declares no image references—such as one written by
  hand—emits no rows and returns exit code 0.

#### `sb_image_base_digest ref arch`

Resolves the manifest digest for a specific architecture of a base image from its upstream registry.
`scripts/stage-artifacts.sh` calls this function for any base image lacking a declared digest,
records the resolved digest in `$PX_OUT/images/pins.env`, and verifies against that record on
subsequent invocations—applying the same trust-on-first-use policy that `scripts/fetch-dist.sh`
uses for binary distributions.

* **stdout** Emits a single `image` row containing the image reference and `sha256:<hex>` as
  `image_id`.
* **exit** Returns exit code 0 on success; 1 if the registry or container runtime fails to return a
  digest for the requested architecture; and 2 on invalid usage, an architecture other than `arm64`
  or `amd64`, or an image reference that already includes a digest.
* **Compose** Invokes `buildx imagetools inspect` with a format string that lists the platform and
  digest of each manifest in the image index, and selects the `linux/<arch>` entry.

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
   eight prohibited tool names and three engine variables, ignoring comment lines as well as lines
   annotated with the inline waiver `# px-allow-docker: <reason>`. Any unwaived match outside the
   explicit allowlist fails the test suite and reports the offending file path, line number, and
   line text. The allowlist is maintained inside the test script with one entry per path and a
   mandatory justification, and **stale** allowlist entries also fail the test: if an allowlisted
   file no longer exists or no longer contains a restricted token, its entry must be removed rather
   than left behind as a potential loophole. Markdown documentation is excluded from the scan,
   allowing this reference document to name the tools directly.
2. **Runtime trap.** `lib/common.sh` defines shell wrapper functions for all eight prohibited tool
   names that immediately exit with code 2, logging the attempted argument list and a diagnostic
   message referencing the substrate contract. Because every `px-*` command sources
   `lib/common.sh`, all workload drivers, checks, hooks, suite actions, and collectors executing
   within those processes inherit the trap—including inside subshells and under `set -e`. External
   child binaries do not inherit shell functions, but they are covered by layers 1 and 3 and are
   deprived of the engine path by the unexported private engine variable.
3. **Fake runtime shim.** The test suite installs a mock `docker` executable in a temporary
   directory, prepends that directory to `PATH`, sets `PX_DOCKER_BIN` to point to the shim, and
   programs the shim to append its invocation arguments to an audit log before returning responses
   from a fixture state file. Alongside `docker`, the test also creates mock executables for
   `kubectl`, `k3d`, `helm`, and `crictl` that log their binary name and arguments to the same file.
   Any `PATH`-resolved invocation of these tools is intercepted and recorded in the audit log. A
   negative control exercises every harness entry point that must remain runtime-agnostic and
   asserts that the audit log remains completely empty; in addition, a simulated hostile driver
   attempts to read `PX_DOCKER_BIN` and run `command "$PX_DOCKER_BIN" ps`, verifying that the
   variable is empty and nothing reaches the shim log. A positive control then executes a genuine
   backend call and asserts that the audit log is **not** empty and contains the expected scope
   label selector, proving that the shim and `PATH` interception are functioning properly.

Any requirement for a new file to access the container runtime directly is an architectural design
question rather than a routine allowlist addition. The image build pipeline illustrates this
principle: `scripts/stage-artifacts.sh` stages the build context and invokes `sb_image_build`, so
the build pipeline never references a container runtime directly and the allowlist remains
unchanged.

## Rules a backend author must honour

* **Perform no runtime work when sourced.** `sb_load` sources the backend file before any operator
  command executes, and the self-test suite verifies that the runtime log remains empty after a
  bare `sb_load`. A backend file must only define functions when sourced.
* **Declare the implemented contract version** from `sb_contract_version` as an explicit decimal
  integer literal.
* **Invoke the underlying runtime through a single call site** using an absolute binary path stored
  in an unexported private variable, and annotate that single line with an inline waiver comment
  stating its reason.
* **Emit TSV rows strictly in the declared schema field order** using `px_tsv_row`, sanitize free
  text through `px_tsv_clean`, and represent missing values as empty columns rather than omitting
  columns.
* **Validate mode arguments with `sb_valid_value`** and return exit code 2 on failure; never forward
  unvalidated mode strings to the underlying substrate.
* **Return exit code 5 for any operation declared unsupported by `sb_capabilities`**, and explicitly
  report that capability as absent in the capability table so that callers record a clean skip
  rather than encountering an unexpected failure.
* **Return exit code 6 for an unknown scope, role, index, or port name, and return exit code 1 when
  the runtime fails to respond.** Resource absence must never be conflated with runtime failure,
  and an unresponsive runtime must never be reported as resource absence.
* **Ensure every wait operation is bounded** and returns exit code 4 upon timeout expiration. Use
  `px_wait_until` or `px_timeout`, and resolve an empty timeout argument to the corresponding
  configured default.
* **Store all persistent backend state under `px_state_dir`** and validate every copy-out
  destination path via `px_out_resolve`. No backend-managed state or artifact may ever be written
  into the repository working tree.
* **Consume the rendered deployment directory strictly as specified above**, without reading
  undeclared files from it.
* **Reuse the shared utility functions** provided by `lib/common.sh` and `contract.sh` for logging,
  timeouts, retries, temporary directories, cross-platform compatibility, scope resolution, and
  planned-action tracking rather than duplicating infrastructure logic in the backend.

A new backend implementation is considered complete when `sb_verify_backend` produces no missing
function names, the self-test contract suite confirms that every required contract function is
defined, and the standard verification test suite passes on the new backend just as it does on
Compose.

## Kubernetes backend

**Status: not yet implemented. Milestone K1 implements the core backend, and milestone K2
implements its preflight checks, safety controls, and image promotion workflows.** Milestone K1 adds
the specification sections covering binding, scope management, manifest generation, readiness
probes, command execution and file copy, scaling, and capabilities; milestone K2 adds the sections
covering fault injection, failure evidence, endpoints, images, preflight checks, and k3d proofing.
Everything earlier in this document is normative for the Kubernetes backend: it implements the exact
same 35 contract functions, output schemas, and exit codes without introducing any custom substrate
verbs.

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
