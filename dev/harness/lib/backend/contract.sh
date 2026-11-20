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
# Every layer above this file interacts with the substrate exclusively through these 35 sb_*
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
# When a runtime cannot respond, the function returns failure code 1. Returning code 6 requires an
# explicit response from the runtime confirming that the target object does not exist.
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
# Every function that reports multiple values emits tab-separated (TSV) rows, and callers query
# sb_schema_index to resolve column positions. The field lists defined in sb_schema_fields are
# normative. The reference in docs/backends.md documents the writer and meaning of each column and
# maps the design verbs to these functions.

# Translates a contract exit code, including the four codes reserved by sb_exec, into its
# human-readable name. Any exit status outside the contract is reported as "command status".
## @description  Resolves the human-readable name of a contract or sb_exec exit code.
## @param        code: The exit status to translate.
## @return       Writes the exit code name to stdout and exits 0.
sb_exit_name() {
  case "${1:-}" in
  "$SB_EXEC_TIMEOUT") printf 'exec timed out\n' ;;
  "$SB_EXEC_NOSTART") printf 'exec could not start\n' ;;
  "$SB_EXEC_NOEXEC") printf 'exec target not executable\n' ;;
  "$SB_EXEC_NOTFOUND") printf 'exec target not found\n' ;;
  "$SB_EXIT_OK" | "$SB_EXIT_FAIL" | "$SB_EXIT_USAGE" | "$SB_EXIT_ANOMALY" | \
    "$SB_EXIT_TIMEOUT" | "$SB_EXIT_UNSUPPORTED" | "$SB_EXIT_NO_OBJECT") px_exit_name "$1" ;;
  *) printf 'command status\n' ;;
  esac
}

# Reports the canonical words accepted for a value kind so that backends can normalize native
# representations onto the contract vocabulary. The supported kinds are state, ready, stopped,
# group_state, phase_status, check_status, term_reason, kill_mode, term_which, and yes_no.
## @description  Outputs the space-separated list of valid words for a contract value kind.
## @param        kind: The value kind to look up.
## @return       Writes the space-separated words to stdout; exits 0, or 6 for an unknown kind.
sb_vocabulary() {
  case "${1:-}" in
  state) printf '%s\n' 'pending running stopped exited unknown' ;;
  ready) printf '%s\n' 'ready unready stopped' ;;
  stopped) printf '%s\n' 'stopped already_stopped' ;;
  group_state) printf '%s\n' 'up down partial' ;;
  phase_status) printf '%s\n' 'ok fail skip' ;;
  check_status) printf '%s\n' 'ok fail warn' ;;
  term_reason) printf '%s\n' 'running completed error oom killed evicted unknown' ;;
  kill_mode) printf '%s\n' 'hard delete' ;;
  term_which) printf '%s\n' 'current previous' ;;
  yes_no) printf '%s\n' 'yes no' ;;
  *) return "$SB_EXIT_NO_OBJECT" ;;
  esac
}

# Validates a candidate word against the vocabulary for its value kind so that an unknown mode
# fails closed.
## @description  Checks whether a word belongs to the specified contract value vocabulary.
## @param        kind: A value kind supported by sb_vocabulary.
## @param        value: The candidate word to test.
## @return       Exits 0 when the value is in the vocabulary, 1 when not, or 6 for an unknown kind.
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

# Reports the ordered field names for a contract schema. The supported schemas are instance, ready,
# stopped, group, phase, term, oom, event, sample, capability, check, kv, endpoint, and image.
## @description  Outputs the ordered field names for a contract schema.
## @param        schema: The schema name to inspect.
## @return       Writes the space-separated names to stdout; exits 0, or 6 for an unknown schema.
sb_schema_fields() {
  case "${1:-}" in
  instance) printf '%s\n' 'role index instance_id state ready image_ref node' ;;
  ready) printf '%s\n' 'role index ready elapsed_ms' ;;
  stopped) printf '%s\n' 'role index stopped' ;;
  group) printf '%s\n' 'group running desired state' ;;
  phase) printf '%s\n' 'phase status elapsed_ms' ;;
  term) printf '%s\n' 'reason exit_code signal finished_at' ;;
  oom) printf '%s\n' 'oom evidence' ;;
  event) printf '%s\n' 'ts_ms role index type reason message' ;;
  sample)
    printf '%s %s\n' 'ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes' \
      'tx_bytes blk_read_bytes blk_write_bytes'
    ;;
  capability) printf '%s\n' 'capability supported detail' ;;
  check) printf '%s\n' 'check status detail' ;;
  kv) printf '%s\n' 'key value' ;;
  endpoint) printf '%s\n' 'host port' ;;
  image) printf '%s\n' 'image_ref image_id' ;;
  *) return "$SB_EXIT_NO_OBJECT" ;;
  esac
}

# Resolves the 1-based column position of a field within a contract schema for use with
# px_tsv_field, so that callers never hard-code column numbers.
## @description  Reports the 1-based column position of a field within a contract schema.
## @param        schema: A schema name defined by sb_schema_fields.
## @param        field: A field name belonging to that schema.
## @return       Writes the position to stdout; exits 0, or 6 for an unknown schema or field.
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
## @param        schema: A schema name defined by sb_schema_fields.
## @return       Writes the column count to stdout; exits 0, or 6 for an unknown schema.
sb_schema_count() {
  local fields field count=0
  fields="$(sb_schema_fields "${1:-}")" || return "$SB_EXIT_NO_OBJECT"
  for field in $fields; do
    count=$((count + 1))
  done
  printf '%s\n' "$count"
}

## @description  Reads the value from the first row in rows whose first field exactly matches key.
## @param        key: The first field to match exactly.
## @param        rows: Lines formatted as a key, a tab, and a value.
## @return       Writes the text after the first tab; exits 0, or 6 when no row has the key.
sb_row_get() {
  local key=${1:-} rows=${2:-} line
  while IFS= read -r line; do
    case "$line" in
    "$key$PX_TAB"*)
      printf '%s\n' "${line#*"$PX_TAB"}"
      return 0
      ;;
    esac
  done <<EOF
$rows
EOF
  return "$SB_EXIT_NO_OBJECT"
}

# ---------------------------------------------------------------------------
# The function set
# ---------------------------------------------------------------------------
# The substrate contract consists of 35 functions. Every function that operates on a cluster takes
# the scope id returned by sb_scope_create as its first argument. The three image functions
# sb_image_build, sb_image_present, and sb_image_base_digest operate on the host rather than on a
# cluster, so they take no scope id.
#
# Required functions must be defined by every backend implementation, and sb_load refuses to bind a
# backend that omits any of them. Optional functions require callers to check sb_supports or
# sb_capability first and record a skip rather than fail when support is absent. The five optional
# functions correspond to capabilities that a substrate can genuinely lack: signal delivery, a
# drain-aware restart, a lifecycle event stream, a metrics source, and a forward to release.

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
sb_image_ref
sb_image_build
sb_image_present
sb_image_base_digest"

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

## @description  Outputs every substrate contract function name, with required functions first.
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
## @param        name: The function name to test.
## @return       Exits 0 when the name is a contract function, or 1 otherwise.
sb_is_contract_name() {
  case "$PX_NL$SB_REQUIRED_FUNCTIONS$PX_NL$SB_OPTIONAL_FUNCTIONS$PX_NL" in
  *"$PX_NL${1:-}$PX_NL"*) return 0 ;;
  esac
  return 1
}

# Verifies that the currently bound backend defines every required contract function and reports
# any missing function names.
## @description  Reports every required contract function that the bound backend does not define.
## @return       Writes any missing names to stdout; exits 0 when complete, or 1 if any is missing.
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

# Binds a single backend for the process by sourcing lib/backend/<name>.sh, while treating a repeat
# call for the same backend as a no-op. Ends the process with code 2 on an invalid or unknown
# backend name, and with code 1 on a contract version mismatch, an attempt to bind a second
# backend, or a missing required function.
## @description  Loads, validates, and binds one backend from lib/backend/<name>.sh.
## @param        backend: Name matching ^[a-z][a-z0-9_]*$; defaults to PX_BACKEND, else compose.
## @return       Exits 0 when bound, or ends the process with 2 on a bad name or 1 on bind failure.
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

## @description  Tests whether the bound backend implements a specified contract function.
## @param        name: A contract function name to check.
## @return       Exits 0 when available, 1 when not implemented, or 2 if not a contract function.
sb_supports() {
  local name=${1:-}
  if ! sb_is_contract_name "$name"; then
    px_error "'$name' is not a substrate contract function"
    return "$SB_EXIT_USAGE"
  fi
  declare -F "$name" >/dev/null 2>&1
}

# Invokes a contract function on the bound backend and translates an unimplemented optional
# function into exit code 5 (unsupported).
## @description  Dispatches a contract function call, returning code 5 if it is not implemented.
## @param        name: The contract function name to invoke.
## @param        argument...: Zero or more arguments forwarded to the contract function.
## @return       Returns the function status, 5 if not implemented, or 2 if not a contract function.
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

## @description  Reads and caches the capability table of the bound backend once per process.
## @return       Exits 0, or 1 when no backend is bound or the backend cannot report its table.
sb_capabilities_cache() {
  [ -z "$PX_SB_CAPS_READY" ] || return 0
  if ! declare -F sb_capabilities >/dev/null 2>&1; then
    px_error "no backend is bound, call sb_load first"
    return "$SB_EXIT_FAIL"
  fi
  PX_SB_CAPS="$(sb_capabilities)" || return "$SB_EXIT_FAIL"
  PX_SB_CAPS_READY=1
}

# Checks whether the bound backend declares a capability from SB_CAPABILITY_NAMES, treating any
# omitted row as no. Exits 1 with no stdout output when the capability table cannot be read, and
# exits 6 when the queried name is not a declared capability.
## @description  Reports whether the bound backend declares support for a named capability.
## @param        name: A capability name from SB_CAPABILITY_NAMES.
## @return       Writes yes or no to stdout; exits 0 for yes, 1 for no or error, or 6 if undeclared.
sb_capability() {
  local name=${1:-} row
  case "$PX_NL$SB_CAPABILITY_NAMES$PX_NL" in
  *"$PX_NL$name$PX_NL"*) ;;
  *)
    px_error "'$name' is not a declared capability"
    return "$SB_EXIT_NO_OBJECT"
    ;;
  esac
  sb_capabilities_cache || return "$SB_EXIT_FAIL"
  row="$(sb_row_get "$name" "$PX_SB_CAPS")" || row=no
  if [ "${row%%"$PX_TAB"*}" = yes ]; then
    printf 'yes\n'
    return 0
  fi
  printf 'no\n'
  return 1
}

# Extracts the detail column for a capability row from the backend capability table, explaining why
# the capability is absent or how the backend satisfies it.
## @description  Reports the diagnostic detail column for one capability row.
## @param        name: A capability name from SB_CAPABILITY_NAMES.
## @return       Writes the detail to stdout; exits 0, 1 if the table is unreadable, or 6 if absent.
sb_capability_detail() {
  local row
  sb_capabilities_cache || return "$SB_EXIT_FAIL"
  row="$(sb_row_get "${1:-}" "$PX_SB_CAPS")" || return "$SB_EXIT_NO_OBJECT"
  px_tsv_field 2 "$row" || printf '\n'
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

# Computes the state file path <kind>-<backend>-<cluster> for a cluster. Exits 2 when the cluster
# name does not match SB_CLUSTER_RE or the output policy refuses the state directory, and exits 1
# when the state directory cannot be created.
## @description  Reports the <kind>-<backend>-<cluster> state file path for a cluster.
## @param        kind: The record kind, either scope or deployment.
## @param        cluster: Name matching SB_CLUSTER_RE; defaults to PX_CLUSTER_ID, else "default".
## @return       Writes the path to stdout; exits 0, 2 on bad name or policy refusal, or 1 on error.
sb_cluster_file() {
  local cluster=${2:-${PX_CLUSTER_ID:-default}} dir
  if ! [[ $cluster =~ $SB_CLUSTER_RE ]]; then
    px_error "invalid cluster name '$cluster'"
    return "$SB_EXIT_USAGE"
  fi
  dir="$(px_state_dir)" || return $?
  printf '%s/%s-%s-%s\n' "$dir" "$1" "${PX_BACKEND_BOUND:-${PX_BACKEND:-compose}}" "$cluster"
}

# Reports the state file whose first line stores the scope id for a cluster on the active backend.
# Exits 2 on an invalid cluster name or a refused state directory, and exits 1 when the state
# directory cannot be created.
## @description  Reports the state file path that records the scope id for a cluster.
## @param        cluster: Name matching SB_CLUSTER_RE; defaults to PX_CLUSTER_ID, else "default".
## @return       Writes the path to stdout; exits 0, 2 on bad name or policy refusal, or 1 on error.
sb_scope_record() { sb_cluster_file scope "${1:-}"; }

# Reports the state file whose first line stores the deployment directory created by px-cluster up.
# Exits 2 on an invalid cluster name or a refused state directory, and exits 1 when the state
# directory cannot be created.
## @description  Reports the state file path recording the deployment directory from px-cluster up.
## @param        cluster: Name matching SB_CLUSTER_RE; defaults to PX_CLUSTER_ID, else "default".
## @return       Writes the path to stdout; exits 0, 2 on bad name or policy refusal, or 1 on error.
sb_deployment_record() { sb_cluster_file deployment "${1:-}"; }

# Resolves the target scope id for a cluster command, preferring PX_SCOPE_ID when set and falling
# back to the recorded scope for PX_CLUSTER_ID. Exits 6 when no scope is named or recorded, 2 on an
# invalid cluster name or a refused state directory, and 1 when the state directory cannot be
# created.
## @description  Resolves the active scope id from PX_SCOPE_ID or the recorded cluster scope.
## @return       Writes the scope id to stdout; exits 0, 6 if none, 2 on bad name/policy, or 1.
sb_scope_resolve() {
  local file id=""
  if [ -n "${PX_SCOPE_ID:-}" ]; then
    printf '%s\n' "$PX_SCOPE_ID"
    return 0
  fi
  file="$(sb_scope_record)" || return $?
  if [ -f "$file" ]; then
    IFS= read -r id <"$file" || true
  fi
  if [ -z "$id" ]; then
    px_debug "no scope recorded in $file"
    return "$SB_EXIT_NO_OBJECT"
  fi
  printf '%s\n' "$id"
}

# Defines the operator remedy for a cluster with no recorded scope so that every diagnostic message
# reporting that condition presents the same guidance.
PX_HINT_NO_CLUSTER="run px-cluster up, or name another with --cluster"

# Determines the scope of the selected cluster via sb_scope_resolve, terminating the process if
# no scope can be determined. When resolution fails, ends the process with code 6 if no scope is
# recorded, or with the status of sb_scope_resolve (2 for a refused state directory, or 1 on
# failure).
## @description  Emits the resolved scope id for the selected cluster, or terminates the process.
## @return       Writes the scope id and exits 0, or ends the process with 6, 2, or 1.
sb_scope_require() {
  local id status=0
  id="$(sb_scope_resolve || exit "$?")" || status=$?
  if [ "$status" = "$SB_EXIT_NO_OBJECT" ]; then
    px_die "$SB_EXIT_NO_OBJECT" "no cluster ${PX_CLUSTER_ID:-default} is recorded:" \
      "$PX_HINT_NO_CLUSTER"
  fi
  if [ "$status" != 0 ]; then
    px_die "$status" "cannot read the scope record of cluster ${PX_CLUSTER_ID:-default}"
  fi
  printf '%s\n' "$id"
}

# Verifies through sb_scope_exists that the target scope exists on the bound backend, terminating
# the process otherwise. Ends the process with code 6 when the scope is absent, or propagates the
# failure status of sb_scope_exists (2 on usage errors, or 1 when the runtime did not respond).
## @description  Terminates the process unless the bound backend confirms that a scope exists.
## @param        scope_id: The required scope id to verify.
## @return       Exits 0 if the scope exists, or ends the process with 6 if absent, 2, or 1.
sb_scope_check() {
  local status=0
  sb_scope_exists "${1:-}" || status=$?
  if [ "$status" = "$SB_EXIT_NO_OBJECT" ]; then
    px_die "$SB_EXIT_NO_OBJECT" "no such scope: ${1:-}"
  fi
  if [ "$status" != 0 ]; then
    px_die "$status" "cannot tell whether the scope ${1:-} exists"
  fi
}

# ---------------------------------------------------------------------------
# Images
# ---------------------------------------------------------------------------
# An image tag must reference a local image only. Because a digest or a registry host would allow
# the container engine to pull the image from a remote registry, the tag grammar rejects both.

## @description  Tests that a tag is a local repository:tag without a digest or registry host.
## @param        tag: The image tag to validate, for example px-harness:dev.
## @return       Exits 0 for a valid local tag, or 1 otherwise.
sb_image_tag_local() {
  local tag=${1:-} re='^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*$' first
  [[ $tag =~ $re ]] || return 1
  first=${tag%%/*}
  [ "$first" != "$tag" ] || return 0
  case "$first" in
  *.* | *:* | localhost) return 1 ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
# Port map
# ---------------------------------------------------------------------------
# The deployment generator writes each published port under a PX_PORT_* variable name, and the
# backend reads that variable back. A single helper constructs the variable name for both sides.

## @description  Names the port-map variable PX_PORT_<ROLE>_<NAME>[_<INDEX>], mapping . and - to _.
## @param        role: The role name.
## @param        port_name: The logical port name, for example rpc.
## @param        index: The zero-based instance index, or empty to omit the index suffix.
## @return       Writes the variable name to stdout and exits 0.
sb_port_var() {
  printf 'PX_PORT_%s%s\n' "$(printf '%s_%s' "${1:-}" "${2:-}" | tr '[:lower:].-' '[:upper:]__')" \
    "${3:+_$3}"
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

# Computes the state file path for a scope's planned-action log, replacing any character outside
# A-Za-z0-9._- with an underscore so that the scope id is safe for use in a file name. Exits 2 when
# the output policy refuses the state directory, and exits 1 when the directory cannot be created.
## @description  Reports the planned-action log file path for a scope, sanitizing unsafe characters.
## @param        scope_id: The scope id, where each character outside A-Za-z0-9._- becomes _.
## @return       Writes the path to stdout; exits 0, 2 if policy refuses the dir, or 1 on dir error.
sb_planned_file() {
  local dir
  dir="$(px_state_dir)" || return $?
  printf '%s/planned-%s.tsv\n' "$dir" "${1//[^A-Za-z0-9._-]/_}"
}

# Appends an intentional action to the scope's planned-action log before execution begins so that a
# concurrent anomaly reader observes the record while the operation is in flight. Exits 2 when the
# scope id or action is empty or the output policy refuses the state directory, and exits 1 when a
# field contains a tab or newline, the state directory cannot be created, or the row cannot be
# written.
## @description  Records an action as planned before it starts so concurrent readers observe it.
## @param        scope_id: The target scope id, required.
## @param        action: The required action name, such as kill, signal, restart, or scale.
## @param        role: The target role name; an empty value matches no instance.
## @param        index: The target instance index; an empty value covers every instance of the role.
## @param        detail: Optional free-form detail text, which may be empty.
## @return       Exits 0, 2 on missing arguments or refused directory, or 1 on format or I/O error.
sb_planned_record() {
  local scope=${1:-} action=${2:-} role=${3:-} index=${4:-} detail=${5:-}
  local file row
  if [ -z "$scope" ] || [ -z "$action" ]; then
    px_error "sb_planned_record needs a scope id and an action"
    return "$SB_EXIT_USAGE"
  fi
  file="$(sb_planned_file "$scope")" || return $?
  row="$(px_tsv_row "$(px_epoch_ms)" "$action" "$role" "$index" \
    "$(px_tsv_clean "$detail")")" || return "$SB_EXIT_FAIL"
  if ! printf '%s\n' "$row" >>"$file"; then
    px_error "cannot write the planned-action record $file"
    return "$SB_EXIT_FAIL"
  fi
  px_info "recorded planned $action${role:+ $role}${index:+/$index} in $file"
}

# Determines whether a planned-action row recorded at or after the observation window start covers
# the specified role and instance index. Exits 0 when a matching row covers the instance, 1 when no
# row matches or the state directory cannot be created, and 2 when the scope id or role is empty,
# the window start is not a whole number of epoch milliseconds, or the state directory is refused.
## @description  Tests whether a planned row recorded since the window start covers an instance.
## @param        scope_id: The target scope id, required.
## @param        role: The target role name, required.
## @param        index: The target instance index; also matched by a row with an empty index.
## @param        since: The observation window start in whole epoch milliseconds; defaults to 0.
## @return       Exits 0 if covered, 1 if uncovered or on directory error, or 2 on bad args/policy.
sb_planned_contains() {
  local scope=${1:-} role=${2:-} index=${3:-} since=${4:-0}
  local file row ts rest row_role row_index
  if [ -z "$scope" ] || [ -z "$role" ] || ! [[ $since =~ $PX_RE_INT ]]; then
    px_error "sb_planned_contains needs a scope id, a role and a whole window start"
    return "$SB_EXIT_USAGE"
  fi
  file="$(sb_planned_file "$scope")" || return $?
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
