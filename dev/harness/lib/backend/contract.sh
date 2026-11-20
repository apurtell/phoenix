#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Defines the substrate contract by declaring what a backend must provide, binding a single
# backend, and verifying that the binding is complete.
#
# Every layer above this file interacts with the substrate exclusively through these 32 sb_*
# functions. Consequently, a driver, a check, a suite action, a health monitor, a collector, and
# the operator commands execute unchanged across every backend, and no component above this layer
# depends on a container runtime. This file itself does not reference any specific runtime.
#
# Each backend implementation resides in lib/backend/<name>.sh. It must define the required
# functions, report the current contract version, and perform no side effects when sourced,
# because sb_load sources the file before any operator intent is established.

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/backend/contract.sh is a library. Source it.\n' >&2
  exit 2
fi

if [ -n "${PX_CONTRACT_LOADED:-}" ]; then
  return 0
fi
PX_CONTRACT_LOADED=1

PX_BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../common.sh
. "$PX_BACKEND_DIR/../common.sh"

# ---------------------------------------------------------------------------
# Contract version
# ---------------------------------------------------------------------------
# The contract version is a decimal integer that must be incremented whenever a function name, an
# argument list, a stdout contract, an exit code, or a schema changes. Because sb_load rejects any
# backend reporting a different number, a backend can never operate in a partially upgraded state.
# This value is a read-only constant rather than a configuration parameter: the configuration
# loader refuses to set it, ensuring that neither a file nor an environment variable can alter the
# revision that sb_load enforces.

readonly PX_CONTRACT_VERSION=1

# ---------------------------------------------------------------------------
# Exit codes
# ---------------------------------------------------------------------------
# Every sb_* function uses the exit status vocabulary defined in lib/common.sh. Callers branch
# exclusively on the numeric exit code, while the underlying cause is reported in the diagnostic
# message, the cluster-check verdict, and the bundle manifest.
#
#   0 ok              The operation completed what its name states.
#   1 failure         The operation failed.
#   2 usage           The arguments are invalid, or a named mode is unknown.
#   3 anomaly         An instance terminated or restarted without an explicit request.
#   4 timeout         A finite wait expired before completion.
#   5 unsupported     This backend cannot perform the operation, as declared by sb_capabilities.
#   6 no such object  The specified scope, role, index, port, or value does not exist.
#
# When a runtime cannot respond at all, the function returns failure code 1. Returning code 6
# requires an affirmative response from the runtime confirming that the target object does not
# exist.
#
# The sb_exec function is the sole entry point that returns a foreign exit status by forwarding
# the exit code of the command it executed. Four codes remain reserved so that callers can
# distinguish a substrate framing failure from an in-container command failure, and a role program
# must never return any of these reserved codes.

SB_EXIT_OK=$PX_EXIT_OK
SB_EXIT_FAIL=$PX_EXIT_FAIL
SB_EXIT_USAGE=$PX_EXIT_USAGE
SB_EXIT_ANOMALY=$PX_EXIT_ANOMALY
SB_EXIT_TIMEOUT=$PX_EXIT_TIMEOUT
SB_EXIT_UNSUPPORTED=$PX_EXIT_UNSUPPORTED
SB_EXIT_NO_OBJECT=$PX_EXIT_NO_OBJECT

SB_EXEC_TIMEOUT=124  # The wait expired, following the coreutils timeout convention.
SB_EXEC_NOSTART=125  # The substrate could not start the command.
SB_EXEC_NOEXEC=126   # The target is present but is not executable.
SB_EXEC_NOTFOUND=127 # The target is not present.

# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------
# Every function that reports multiple values emits tab-separated (TSV) rows whose field order is
# normative. Callers resolve column positions dynamically through sb_schema_index instead of
# hard-coding column numbers, ensuring that adding a new column does not break existing readers.
#
#   instance    role index instance_id state ready image_ref node
#               Represents an inventory row emitted by sb_list, sb_provision, and sb_scale. The
#               instance_id field is an opaque backend-specific identifier, node names the host
#               running the instance, and image_ref is the image reference recorded in the
#               manifest.
#   ready       role index ready elapsed_ms
#               Represents a single readiness outcome emitted by sb_wait_ready and sb_group_start.
#               The elapsed_ms field measures the wait duration rather than the instance age.
#   stopped     role index stopped
#               Represents a single stop outcome emitted by sb_group_stop.
#   group       group running desired state
#               Represents the aggregate state of a role group emitted by sb_group_status.
#   phase       phase status elapsed_ms
#               Represents one phase of a compound operation. The sb_restart_drain function emits
#               drain, stop, start, ready, and rebalance in that order.
#   term        reason exit_code signal finished_at
#               Represents normalized exit evidence emitted by sb_termination_reason. Because
#               every backend maps onto the same reason vocabulary, anomaly detection decisions
#               remain identical across substrates.
#   oom         oom evidence
#               Represents the out-of-memory determination emitted by sb_oom_killed.
#   event       ts_ms role index type reason message
#               Represents a single lifecycle event emitted oldest-first by sb_events, with the
#               message text sanitized through px_tsv_clean.
#   sample      ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes
#               tx_bytes blk_read_bytes blk_write_bytes
#               Represents a per-instance resource snapshot emitted by sb_sample_resources. Any
#               column without an equivalent in the underlying metrics source is left empty.
#   capability  capability supported detail
#               Represents the capability degradation table emitted by sb_capabilities.
#   check       check status detail
#               Represents a single preflight check result emitted by sb_preflight.
#   kv          key value
#               Represents a descriptive key-value pair emitted by sb_scope_args and
#               sb_version_info.
#   endpoint    host port
#               Represents a host-reachable address emitted by sb_endpoint, where the host is
#               always on the loopback interface.
#   image       image_ref image_id
#               Represents the image an instance runs, as emitted by sb_image_ref, where image_id
#               is either a local identifier or a registry digest.

SB_SCHEMA_INSTANCE="role index instance_id state ready image_ref node"
SB_SCHEMA_READY="role index ready elapsed_ms"
SB_SCHEMA_STOPPED="role index stopped"
SB_SCHEMA_GROUP="group running desired state"
SB_SCHEMA_PHASE="phase status elapsed_ms"
SB_SCHEMA_TERM="reason exit_code signal finished_at"
SB_SCHEMA_OOM="oom evidence"
SB_SCHEMA_EVENT="ts_ms role index type reason message"
SB_SCHEMA_SAMPLE="ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes tx_bytes
blk_read_bytes blk_write_bytes"
SB_SCHEMA_CAPABILITY="capability supported detail"
SB_SCHEMA_CHECK="check status detail"
SB_SCHEMA_KV="key value"
SB_SCHEMA_ENDPOINT="host port"
SB_SCHEMA_IMAGE="image_ref image_id"

# Defines the canonical value vocabularies. Each backend normalizes its native representations
# onto these terms so that checks receive identical values across all backends.
SB_STATES="pending running stopped exited unknown"
SB_READY_VALUES="ready unready stopped"
SB_STOPPED_VALUES="stopped already_stopped"
SB_GROUP_STATES="up down partial"
SB_PHASE_STATUSES="ok fail skip"
SB_CHECK_STATUSES="ok fail warn"
SB_TERM_REASONS="running completed error oom killed evicted unknown"
SB_KILL_MODES="hard delete"
SB_TERM_WHICH="current previous"
SB_YES_NO="yes no"

# Translates a contract exit code, including the four framing codes reserved by sb_exec, into its
# human-readable name. Any other code is reported as the status of the command that sb_exec ran.
## @description  Resolves the human-readable name for a contract or sb_exec exit code.
## @param        code Exit code to translate.
## @return       Writes the exit code name to stdout and always exits 0.
sb_exit_name() {
  case "${1:-}" in
  "$SB_EXIT_OK") printf 'ok\n' ;;
  "$SB_EXIT_FAIL") printf 'failure\n' ;;
  "$SB_EXIT_USAGE") printf 'usage\n' ;;
  "$SB_EXIT_ANOMALY") printf 'cluster anomaly\n' ;;
  "$SB_EXIT_TIMEOUT") printf 'timeout\n' ;;
  "$SB_EXIT_UNSUPPORTED") printf 'unsupported\n' ;;
  "$SB_EXIT_NO_OBJECT") printf 'no such object\n' ;;
  "$SB_EXEC_TIMEOUT") printf 'exec timed out\n' ;;
  "$SB_EXEC_NOSTART") printf 'exec could not start\n' ;;
  "$SB_EXEC_NOEXEC") printf 'exec target not executable\n' ;;
  "$SB_EXEC_NOTFOUND") printf 'exec target not found\n' ;;
  *) printf 'command status\n' ;;
  esac
}

# Reports the space-separated set of canonical words accepted for a given value kind so that
# backends can query the contract rather than duplicating the vocabulary. Supported kinds are
# state, ready, stopped, group_state, phase_status, check_status, term_reason, kill_mode,
# term_which, and yes_no.
## @description  Outputs the space-separated list of valid words for a contract value kind.
## @param        kind Vocabulary kind to look up.
## @return       Writes the space-separated words to stdout; exits 0, or 6 if the kind is unknown.
sb_vocabulary() {
  case "${1:-}" in
  state) printf '%s\n' "$SB_STATES" ;;
  ready) printf '%s\n' "$SB_READY_VALUES" ;;
  stopped) printf '%s\n' "$SB_STOPPED_VALUES" ;;
  group_state) printf '%s\n' "$SB_GROUP_STATES" ;;
  phase_status) printf '%s\n' "$SB_PHASE_STATUSES" ;;
  check_status) printf '%s\n' "$SB_CHECK_STATUSES" ;;
  term_reason) printf '%s\n' "$SB_TERM_REASONS" ;;
  kill_mode) printf '%s\n' "$SB_KILL_MODES" ;;
  term_which) printf '%s\n' "$SB_TERM_WHICH" ;;
  yes_no) printf '%s\n' "$SB_YES_NO" ;;
  *) return "$SB_EXIT_NO_OBJECT" ;;
  esac
}

# Validates a candidate value against the canonical vocabulary for its kind. Backends use this
# check to validate arguments so that an unknown mode fails closed with the usage exit code before
# reaching the underlying substrate.
## @description  Checks whether a value belongs to the specified contract vocabulary kind.
## @param        kind Vocabulary kind to validate against.
## @param        value Candidate word to test.
## @return       Exits 0 if valid, 1 if not in the vocabulary, or 6 if the kind is unknown.
sb_valid_value() {
  local words word
  words="$(sb_vocabulary "${1:-}")" || return "$SB_EXIT_NO_OBJECT"
  for word in $words; do
    if [ "$word" = "${2:-}" ]; then
      return 0
    fi
  done
  return 1
}

# Reports the ordered field names for a named contract schema, such as instance, separated by
# spaces on stdout.
## @description  Outputs the ordered field names for a given contract schema.
## @param        schema Schema name, for example instance.
## @return       Writes space-separated field names to stdout; exits 0, or 6 if schema is unknown.
sb_schema_fields() {
  case "${1:-}" in
  instance) printf '%s\n' "$SB_SCHEMA_INSTANCE" ;;
  ready) printf '%s\n' "$SB_SCHEMA_READY" ;;
  stopped) printf '%s\n' "$SB_SCHEMA_STOPPED" ;;
  group) printf '%s\n' "$SB_SCHEMA_GROUP" ;;
  phase) printf '%s\n' "$SB_SCHEMA_PHASE" ;;
  term) printf '%s\n' "$SB_SCHEMA_TERM" ;;
  oom) printf '%s\n' "$SB_SCHEMA_OOM" ;;
  event) printf '%s\n' "$SB_SCHEMA_EVENT" ;;
  sample) printf '%s\n' "$(printf '%s' "$SB_SCHEMA_SAMPLE" | tr '\n' ' ')" ;;
  capability) printf '%s\n' "$SB_SCHEMA_CAPABILITY" ;;
  check) printf '%s\n' "$SB_SCHEMA_CHECK" ;;
  kv) printf '%s\n' "$SB_SCHEMA_KV" ;;
  endpoint) printf '%s\n' "$SB_SCHEMA_ENDPOINT" ;;
  image) printf '%s\n' "$SB_SCHEMA_IMAGE" ;;
  *) return "$SB_EXIT_NO_OBJECT" ;;
  esac
}

# Resolves the 1-based column position of a field within a contract schema. Callers pair this
# lookup with px_tsv_field to avoid hard-coding numeric column indices.
## @description  Reports the 1-based column position of a field within a named schema.
## @param        schema Schema name to query.
## @param        field Field name whose column position is requested.
## @return       Writes the position to stdout; exits 0, or 6 if the schema or field is unknown.
sb_schema_index() {
  local fields field position=0
  fields="$(sb_schema_fields "${1:-}")" || return "$SB_EXIT_NO_OBJECT"
  for field in $fields; do
    position=$((position + 1))
    if [ "$field" = "${2:-}" ]; then
      printf '%s\n' "$position"
      return 0
    fi
  done
  return "$SB_EXIT_NO_OBJECT"
}

## @description  Reports the total number of columns defined by a contract schema.
## @param        schema Schema name to inspect.
## @return       Writes the column count to stdout; exits 0, or 6 when the schema is unknown.
sb_schema_count() {
  local fields field count=0
  fields="$(sb_schema_fields "${1:-}")" || return "$SB_EXIT_NO_OBJECT"
  for field in $fields; do
    count=$((count + 1))
  done
  printf '%s\n' "$count"
}

# ---------------------------------------------------------------------------
# The function set
# ---------------------------------------------------------------------------
# The substrate contract consists of 32 functions. Every function that operates on a cluster
# accepts the scope id returned by sb_scope_create as its first argument.
#
# Required functions must be defined by every backend implementation, and sb_load rejects any
# backend that omits one. Optional functions require callers to check sb_supports or sb_capability
# beforehand and record a skip rather than failing when support is absent. The five optional
# functions correspond to features that a substrate may genuinely lack: signal delivery, a
# drain-aware restart, a lifecycle event stream, a metrics source, and a port-forward release.
#
# The design document describes the substrate verb set in prose, mapping to contract functions as
# follows:
#   provision -> sb_provision          teardown  -> sb_teardown
#   wait_ready -> sb_wait_ready        exec      -> sb_exec
#   logs -> sb_logs                    cp        -> sb_copy_in, sb_copy_out
#   scale -> sb_scale                  kill_role -> sb_kill
#   restart_role -> sb_restart_drain   inspect   -> sb_list
#   sample_resources -> sb_sample_resources
#   restart_count -> sb_restart_count  oom_detect -> sb_oom_killed

SB_REQUIRED_FUNCTIONS="sb_backend_id
sb_contract_version
sb_version_info
sb_capabilities
sb_preflight
sb_scope_create
sb_scope_exists
sb_scope_args
sb_scope_delete
sb_provision
sb_teardown
sb_wait_ready
sb_exec
sb_logs
sb_copy_in
sb_copy_out
sb_scale
sb_group_stop
sb_group_start
sb_group_status
sb_kill
sb_list
sb_restart_count
sb_termination_reason
sb_oom_killed
sb_endpoint
sb_image_ref"

SB_OPTIONAL_FUNCTIONS="sb_signal
sb_restart_drain
sb_events
sb_sample_resources
sb_endpoint_release"

# Lists the capability identifiers that a backend may declare in its sb_capabilities table.
# Callers query sb_capability so they can degrade to a skip instead of failing when one is absent.
SB_CAPABILITY_NAMES="logs_previous
previous_termination
resource_sampling
signal_delivery
events
drain_restart
port_forward
scale
group_switch
oom_evidence
image_digest
node_placement"

## @description  Outputs the list of required substrate contract function names.
## @return       Writes one function name per line to stdout and exits 0.
sb_contract_required() { printf '%s\n' "$SB_REQUIRED_FUNCTIONS"; }

## @description  Outputs the list of optional substrate contract function names.
## @return       Writes one function name per line to stdout and exits 0.
sb_contract_optional() { printf '%s\n' "$SB_OPTIONAL_FUNCTIONS"; }

# Outputs the complete set of substrate contract function names with required functions listed
# first, allowing the self-test to compare the contract against the functions a backend defines.
## @description  Lists every required and optional contract function name in order.
## @return       Writes one function name per line to stdout and exits 0.
sb_contract_functions() {
  printf '%s\n%s\n' "$SB_REQUIRED_FUNCTIONS" "$SB_OPTIONAL_FUNCTIONS"
}

## @description  Outputs the list of all declared substrate capability names.
## @return       Writes one capability name per line to stdout and exits 0.
sb_capability_names() { printf '%s\n' "$SB_CAPABILITY_NAMES"; }

# ---------------------------------------------------------------------------
# Binding
# ---------------------------------------------------------------------------

PX_BACKEND_BOUND=""
PX_SB_CAPS=""
PX_SB_CAPS_READY=""

## @description  Checks whether a function name belongs to the substrate contract.
## @param        name Function name to test.
## @return       Exits 0 when the name is a contract function, or 1 otherwise.
sb_is_contract_name() {
  local all
  all="
$SB_REQUIRED_FUNCTIONS
$SB_OPTIONAL_FUNCTIONS
"
  case "$all" in
  *"
$1
"*) return 0 ;;
  esac
  return 1
}

# Verifies that the currently loaded backend defines every required contract function and outputs
# any missing function names. Both sb_load and validation commands invoke this check so that an
# incomplete backend can be diagnosed without interacting with a cluster.
## @description  Verifies that all required contract functions are defined by the backend.
## @return       Writes any missing names to stdout; exits 0 if complete, or 1 if any are missing.
sb_verify_backend() {
  local name missing=0
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    if ! declare -F "$name" >/dev/null 2>&1; then
      printf '%s\n' "$name"
      missing=$((missing + 1))
    fi
  done <<EOF
$SB_REQUIRED_FUNCTIONS
EOF
  if [ "$missing" -gt 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Binds a single backend implementation for the process by sourcing lib/backend/<name>.sh. It
# rejects any backend whose contract version does not match, refuses to bind a second backend over
# an already bound one, and rejects any incomplete backend missing required functions. A repeated
# call naming the currently bound backend returns immediately as a no-op so that any command can
# call sb_load without a guard. Exits with code 2 on an invalid or unknown backend name, and exits
# with code 1 on a contract version mismatch, a second backend, or a missing required function.
## @description  Loads, validates, and binds the specified substrate backend.
## @param        backend Backend name, defaulting to PX_BACKEND and then compose.
## @return       Exits 0 when bound, 2 on invalid/unknown name, or 1 on version or binding errors.
sb_load() {
  local backend file version missing
  backend="${1:-${PX_BACKEND:-compose}}"
  if ! [[ $backend =~ ^[a-z][a-z0-9_]*$ ]]; then
    px_die "$SB_EXIT_USAGE" "invalid backend name '$backend'"
  fi
  if [ -n "$PX_BACKEND_BOUND" ]; then
    if [ "$PX_BACKEND_BOUND" = "$backend" ]; then
      return 0
    fi
    px_die "$SB_EXIT_FAIL" \
      "backend $PX_BACKEND_BOUND is bound, a process binds one backend only"
  fi
  file="$PX_BACKEND_DIR/$backend.sh"
  if [ ! -f "$file" ]; then
    px_die "$SB_EXIT_USAGE" "unknown backend '$backend', no such file $file"
  fi
  # The backend file path is constructed at runtime from a validated name, so the directory
  # contents determine which backends are available.
  # shellcheck source=/dev/null
  . "$file" || px_die "$SB_EXIT_FAIL" "backend $backend could not be read"
  if ! declare -F sb_contract_version >/dev/null 2>&1; then
    px_die "$SB_EXIT_FAIL" "backend $backend declares no contract version"
  fi
  version="$(sb_contract_version)"
  if [ "$version" != "$PX_CONTRACT_VERSION" ]; then
    px_die "$SB_EXIT_FAIL" \
      "backend $backend implements contract $version, this harness needs $PX_CONTRACT_VERSION"
  fi
  missing="$(sb_verify_backend)" || px_die "$SB_EXIT_FAIL" \
    "backend $backend is incomplete, missing: $(px_tsv_clean "$missing")"
  PX_BACKEND_BOUND="$backend"
  PX_BACKEND="$backend"
  export PX_BACKEND
  px_debug "bound backend $backend at contract $PX_CONTRACT_VERSION"
}

## @description  Reports the name of the backend currently bound in this process.
## @return       Writes the backend name to stdout; exits 0, or 6 when no backend is bound.
sb_bound_backend() {
  if [ -z "$PX_BACKEND_BOUND" ]; then
    return "$SB_EXIT_NO_OBJECT"
  fi
  printf '%s\n' "$PX_BACKEND_BOUND"
}

# Determines whether the currently bound backend implements a given contract function. Callers
# must query this predicate before invoking any optional contract function.
## @description  Tests whether the bound backend defines a specified contract function.
## @param        name Contract function name to check.
## @return       Exits 0 if implemented, 1 if unimplemented, or 2 if not a contract function.
sb_supports() {
  local name=${1:-}
  if ! sb_is_contract_name "$name"; then
    px_error "'$name' is not a substrate contract function"
    return "$SB_EXIT_USAGE"
  fi
  declare -F "$name" >/dev/null 2>&1
}

# Invokes a contract function on the bound backend and translates an unimplemented optional
# function into the unsupported exit code (5), which callers record as a skipped operation.
## @description  Dispatches a contract function call, returning code 5 if unimplemented.
## @param        name Contract function name to invoke.
## @param        argument... Arguments forwarded to the contract function.
## @return       Returns the function status, 5 if not implemented, or 2 if not a contract name.
sb_call() {
  local name=${1:-} status
  shift || true
  if ! sb_is_contract_name "$name"; then
    px_error "'$name' is not a substrate contract function"
    return "$SB_EXIT_USAGE"
  fi
  if ! declare -F "$name" >/dev/null 2>&1; then
    px_debug "backend ${PX_BACKEND_BOUND:-none} does not implement $name"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  status=0
  "$name" "$@" || status=$?
  return "$status"
}

# Queries the capability table of the bound backend once and caches the result for the lifetime of
# the current process. Because the table is small whereas probing the runtime can be expensive,
# caching ensures that repeated capability lookups incur the probe cost only once.
## @description  Populates the per-process capability table cache from the bound backend.
## @return       Exits 0 on success, or 1 when no backend is bound or the table cannot be read.
sb_capabilities_cache() {
  [ -z "$PX_SB_CAPS_READY" ] || return 0
  if ! declare -F sb_capabilities >/dev/null 2>&1; then
    px_error "no backend is bound, call sb_load first"
    return "$SB_EXIT_FAIL"
  fi
  PX_SB_CAPS="$(sb_capabilities)" || return "$SB_EXIT_FAIL"
  PX_SB_CAPS_READY=1
}

# Checks whether the bound backend supports a declared capability. Any declared capability name
# omitted from the backend capability table is treated as unsupported so that callers degrade
# gracefully instead of failing.
## @description  Reports whether the bound backend supports a named capability.
## @param        name Declared capability name to query.
## @return       Writes yes or no to stdout; exits 0 for yes, 1 for no, or 6 if undeclared.
sb_capability() {
  local name=${1:-} row value
  case "
$SB_CAPABILITY_NAMES
" in
  *"
$name
"*) ;;
  *)
    px_error "'$name' is not a declared capability"
    return "$SB_EXIT_NO_OBJECT"
    ;;
  esac
  sb_capabilities_cache || return "$SB_EXIT_FAIL"
  while IFS= read -r row; do
    case "$row" in
    "$name$PX_TAB"*)
      value="$(px_tsv_field 2 "$row")" || value=no
      if [ "$value" = yes ]; then
        printf 'yes\n'
        return 0
      fi
      printf 'no\n'
      return 1
      ;;
    esac
  done <<EOF
$PX_SB_CAPS
EOF
  printf 'no\n'
  return 1
}

# Extracts the detail column for a capability row from the backend capability table, explaining
# either why a capability is unavailable or how the backend satisfies it.
## @description  Outputs the diagnostic detail field for a row in the capability table.
## @param        name Capability name to look up.
## @return       Writes the detail text to stdout; exits 0, or 6 when the row is not present.
sb_capability_detail() {
  local name=${1:-} row
  sb_capabilities_cache || return "$SB_EXIT_FAIL"
  while IFS= read -r row; do
    case "$row" in
    "$name$PX_TAB"*)
      px_tsv_field 3 "$row" || printf '\n'
      return 0
      ;;
    esac
  done <<EOF
$PX_SB_CAPS
EOF
  return "$SB_EXIT_NO_OBJECT"
}

# ---------------------------------------------------------------------------
# Scope resolution
# ---------------------------------------------------------------------------
# During cluster startup, px-cluster up persists the scope id returned by sb_scope_create under a
# key formed from the backend and the cluster name. Every subsequent command that operates on a
# cluster resolves its target scope through sb_scope_resolve, allowing commands run in separate
# shells to locate the cluster created by px-cluster without ever constructing a scope id manually.

# Defines the valid character grammar for a cluster name. Because a cluster name is embedded into
# scope identifiers, label values, and state file names, its allowed syntax remains narrow.
SB_CLUSTER_RE='^[a-z0-9][a-z0-9_-]*$'

# Computes the path to the state file that stores the scope id for a cluster on the bound backend.
# Embedding the backend name in the file name allows the same cluster name to exist simultaneously
# on two backends without either record shadowing the other. The file stores the scope id on its
# first line; px-cluster writes it after sb_scope_create and removes it after sb_scope_delete.
# Exits 2 if the cluster name is invalid or the output policy refuses the state directory, and 1 if
# the state directory cannot be created.
## @description  Reports the state file path recording the scope id for a cluster.
## @param        cluster Cluster name; defaults to PX_CLUSTER_ID, or "default" when unset.
## @return       Writes the path to stdout; exits 0, 2 on invalid name or policy refusal, or 1.
sb_scope_record() {
  local cluster=${1:-${PX_CLUSTER_ID:-default}} dir status=0
  if ! [[ $cluster =~ $SB_CLUSTER_RE ]]; then
    px_error "invalid cluster name '$cluster'"
    return "$SB_EXIT_USAGE"
  fi
  dir="$(px_state_dir)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  printf '%s/scope-%s-%s\n' "$dir" "${PX_BACKEND_BOUND:-${PX_BACKEND:-compose}}" "$cluster"
}

# Resolves the target scope id for a cluster command. An explicit PX_SCOPE_ID environment variable
# takes precedence so that an operator can target a scope that was never recorded on the local
# host; otherwise, the scope id is read from the record written by px-cluster for the bound backend
# and PX_CLUSTER_ID. When PX_SCOPE_ID is unset, propagates any sb_scope_record failure (2 on an
# invalid cluster name or refused state directory, or 1 when the state directory cannot be created)
# and exits 6 if no scope record exists.
## @description  Resolves the active scope id from PX_SCOPE_ID or the local cluster record.
## @return       Writes the scope id to stdout; exits 0, 6 if unrecorded, or 1 or 2 on error.
sb_scope_resolve() {
  local file id="" status=0
  if [ -n "${PX_SCOPE_ID:-}" ]; then
    printf '%s\n' "$PX_SCOPE_ID"
    return 0
  fi
  file="$(sb_scope_record)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  if [ -f "$file" ]; then
    IFS= read -r id <"$file" || true
  fi
  if [ -z "$id" ]; then
    px_debug "no scope recorded in $file"
    return "$SB_EXIT_NO_OBJECT"
  fi
  printf '%s\n' "$id"
}

# ---------------------------------------------------------------------------
# Planned actions
# ---------------------------------------------------------------------------
# Intentional operator actions, such as a kill, a signal, or a restart, and scheduled disruptions
# driven by px-run deliberately alter the state and restart count of an instance. Every intentional
# disruption is logged here before execution begins, and px-run consults this log before reporting
# a cluster anomaly. Because a single record file per scope resides in the shared state directory,
# px-cluster running in one shell and px-run running in another observe the same planned actions.
#
# Each entry is stored as a tab-separated row with fields: ts_ms action role index detail. A row
# with an empty index applies to every instance of that role, whereas a row with an empty role
# matches no instance; consequently, an operation targeting an entire group records one row for
# each role in the group. Readers supply the start timestamp of their observation window so that
# rows left over from an earlier run or a previous cluster sharing the same scope id never excuse
# new unexpected restarts.

# Computes the state file path that stores planned actions for a given scope. Because a scope id
# may contain characters that are unsafe in file names, such as the separator between a cluster
# context and a namespace, characters outside [A-Za-z0-9._-] are normalized to underscores.
# Propagates the output policy status (2 if the state directory is refused, or 1 if the directory
# cannot be created).
## @description  Reports the sanitized state file path for a scope's planned-action log.
## @param        scope Target scope id.
## @return       Writes the file path to stdout; exits 0, or 2 or 1 on state directory failure.
sb_planned_file() {
  local dir status=0
  dir="$(px_state_dir)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  printf '%s/planned-%s.tsv\n' "$dir" "${1//[^A-Za-z0-9._-]/_}"
}

# Appends a planned disruption entry to the scope's planned-action log. Callers must record the
# action before initiating it because an anomaly reader may inspect the log while the action is
# still in flight. Exits 2 when the scope id or action is empty or when the output policy refuses
# the state directory, and exits 1 when a field contains a tab or newline, when the state directory
# cannot be created, or when the record file cannot be written.
## @description  Records an intentional disruption in the scope's planned-action log.
## @param        scope Target scope id.
## @param        action Disruption verb, such as kill, signal, restart, or scale.
## @param        role Target role name, which may be empty.
## @param        index Target instance index, or empty to cover all instances of the role.
## @param        detail Optional free-form detail string, which may be empty.
## @return       Exits 0 on success, 2 on usage or policy refusal, or 1 on formatting or I/O error.
sb_planned_record() {
  local scope=${1:-} action=${2:-} role=${3:-} index=${4:-} detail=${5:-}
  local file row target status=0
  if [ -z "$scope" ] || [ -z "$action" ]; then
    px_error "sb_planned_record needs a scope id and an action"
    return "$SB_EXIT_USAGE"
  fi
  file="$(sb_planned_file "$scope")" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  row="$(px_tsv_row "$(px_epoch_ms)" "$action" "$role" "$index" \
    "$(px_tsv_clean "$detail")")" || return "$SB_EXIT_FAIL"
  if ! printf '%s\n' "$row" >>"$file"; then
    px_error "cannot write the planned-action record $file"
    return "$SB_EXIT_FAIL"
  fi
  target=$action
  if [ -n "$role" ]; then
    target="$target $role"
  fi
  if [ -n "$index" ]; then
    target="$target/$index"
  fi
  px_info "recorded planned $target in $file"
}

# Determines whether a logged planned action covers the specified role and instance index. Only
# rows recorded at or after the start of the caller's observation window are considered. Exits 0
# when a matching row covers the instance and 1 when no row matches or the state directory cannot
# be created; exits 2 when the scope id or role is empty, when the window start is not a whole
# number, or when the output policy refuses the state directory.
## @description  Tests whether a planned action recorded within the window covers an instance.
## @param        scope Target scope id.
## @param        role Target role name.
## @param        index Target instance index.
## @param        since Observation window start in epoch milliseconds, defaulting to 0.
## @return       Exits 0 if covered, 1 if not covered or on state dir creation failure, or 2.
sb_planned_contains() {
  local scope=${1:-} role=${2:-} index=${3:-} since=${4:-0}
  local file row ts rest row_role row_index status=0
  if [ -z "$scope" ] || [ -z "$role" ] || ! [[ $since =~ $PX_RE_INT ]]; then
    px_error "sb_planned_contains needs a scope id, a role and a whole window start"
    return "$SB_EXIT_USAGE"
  fi
  file="$(sb_planned_file "$scope")" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  [ -f "$file" ] || return 1
  while IFS= read -r row || [ -n "$row" ]; do
    # Fields are split explicitly by parameter expansion because consecutive tabs would collapse
    # under standard IFS splitting, causing an empty role or index field to lose its position.
    ts=${row%%"$PX_TAB"*}
    rest=${row#*"$PX_TAB"}
    rest=${rest#*"$PX_TAB"}
    row_role=${rest%%"$PX_TAB"*}
    rest=${rest#*"$PX_TAB"}
    row_index=${rest%%"$PX_TAB"*}
    [[ $ts =~ $PX_RE_INT ]] || continue
    [ "$ts" -ge "$since" ] || continue
    [ "$row_role" = "$role" ] || continue
    if [ -z "$row_index" ] || [ "$row_index" = "$index" ]; then
      return 0
    fi
  done <"$file"
  return 1
}
