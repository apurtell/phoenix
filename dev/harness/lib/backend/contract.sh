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
# functions. Consequently, a driver, a check, a plug-in hook, a health monitor, a collector, and
# the operator commands execute unchanged across every backend, and no component above this layer
# depends on a container runtime. This file itself does not reference any specific runtime.
#
# Each backend implementation resides in lib/backend/<name>.sh. It must define every function in
# the contract set and perform no side effects when sourced, because sb_load sources the file
# before any operator intent is established.

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
# stopped, group, phase, term, oom, sample, kv, check, endpoint, image, and restart.
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
  sample)
    printf '%s %s\n' 'ts_ms role index cpu_pct mem_bytes mem_limit_bytes rx_bytes' \
      'tx_bytes blk_read_bytes blk_write_bytes'
    ;;
  kv) printf '%s\n' 'key value' ;;
  check) printf '%s\n' 'check status detail' ;;
  endpoint) printf '%s\n' 'host port' ;;
  image) printf '%s\n' 'image_ref image_id' ;;
  restart) printf '%s\n' 'role index count' ;;
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
# The substrate contract consists of 32 required functions: sb_load refuses a backend that does not
# define each one. A backend that cannot serve a function on its substrate must still define it and
# return 5 so that the caller records a skip. Every function that operates on a cluster takes the
# scope id returned by sb_scope_create as its first argument. The four image functions
# sb_image_build, sb_image_present, sb_image_base_digest, and sb_image_push operate on the host
# rather than on a cluster, so they take no scope id.

SB_REQUIRED_FUNCTIONS="sb_backend_id
sb_version_info
sb_preflight
sb_scope_create
sb_scope_exists
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
sb_signal
sb_restart_drain
sb_list
sb_restart_count
sb_termination_reason
sb_oom_killed
sb_sample_resources
sb_endpoint
sb_endpoint_release
sb_image_ref
sb_image_build
sb_image_present
sb_image_base_digest
sb_image_push"

## @description  Outputs the list of all substrate contract function names.
## @return       Writes one function name per line to stdout and exits 0.
sb_contract_functions() { printf '%s\n' "$SB_REQUIRED_FUNCTIONS"; }

# ---------------------------------------------------------------------------
# Binding
# ---------------------------------------------------------------------------

PX_BACKEND_BOUND=""

## @description  Reports every contract function that the currently bound backend does not define.
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
# backend name, and with code 1 on an attempt to bind a second backend or a missing function.
## @description  Loads, validates, and binds one backend from lib/backend/<name>.sh.
## @param        backend: Name matching ^[a-z][a-z0-9_]*$; defaults to PX_BACKEND, else compose.
## @return       Exits 0 when bound, or ends the process with 2 on a bad name or 1 on bind failure.
sb_load() {
  local backend file missing
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
  missing="$(sb_verify_backend)" || px_die "$SB_EXIT_FAIL" \
    "backend $backend is incomplete, missing: $(px_tsv_clean "$missing")"
  PX_BACKEND_BOUND="$backend"
  PX_BACKEND="$backend"
  export PX_BACKEND
  px_debug "bound backend $backend"
}

## @description  Reports the name of the backend currently bound in this process.
## @return       Writes the backend name to stdout; exits 0, or 6 when no backend is bound.
sb_bound_backend() {
  if [ -z "$PX_BACKEND_BOUND" ]; then
    return "$SB_EXIT_NO_OBJECT"
  fi
  printf '%s\n' "$PX_BACKEND_BOUND"
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

# ---------------------------------------------------------------------------
# Shared backend helpers
# ---------------------------------------------------------------------------
# Every backend evaluates timeouts, boolean flags, contract function arguments, dry-run gates,
# version floors, scope records, and compound-operation phase rows according to the same rules. The
# helpers below define those rules in one place so that separate backends cannot drift apart. They
# do not reference any container runtime and are not part of the required contract function set.

# Reads the positive integer number of seconds stored in the variable named by NAME, falling back to
# FALLBACK when the variable is unset, non-numeric, or not a whole number of seconds above zero.
## @description  Reports the whole seconds above zero held by the variable NAME, or FALLBACK.
## @param        NAME: Name of the variable holding a timeout in whole seconds above zero.
## @param        FALLBACK: Default number of seconds to report when NAME is unset or invalid.
## @return       Writes the resolved number of seconds, or FALLBACK, to stdout and exits 0.
sb_seconds() {
  local value=${!1:-}
  case "$value" in
  '' | *[!0-9]*) value=$2 ;;
  *) [ "$((10#$value))" -gt 0 ] || value=$2 ;;
  esac
  printf '%s\n' "$value"
}

# Resolves a timeout argument in whole seconds above zero. When ARGUMENT is empty, delegates to
# sb_seconds to read the variable NAME or use FALLBACK; when ARGUMENT is provided, exits 2 if it is
# not a whole number of seconds above 0.
## @description  Resolves a timeout from ARGUMENT, falling back to the variable NAME or FALLBACK.
## @param        ARGUMENT: Explicit timeout in whole seconds above 0, or empty for the fallback.
## @param        NAME: Variable name consulted when ARGUMENT is empty.
## @param        FALLBACK: Default timeout in seconds used when ARGUMENT and NAME provide no value.
## @return       Writes the seconds to stdout; exits 0, or 2 if ARGUMENT is not whole seconds > 0.
sb_timeout_arg() {
  local given=${1:-} name=${2:-} fallback=${3:-}
  if [ -z "$given" ]; then
    sb_seconds "$name" "$fallback"
    return 0
  fi
  if ! [[ $given =~ $PX_RE_INT ]] || [ "$((10#$given))" -lt 1 ]; then
    px_error "a timeout must be a whole number of seconds above zero, got '$given'"
    return "$SB_EXIT_USAGE"
  fi
  printf '%s\n' "$given"
}

# Determines whether an exit status returned by px_timeout indicates that the wait expired,
# including status 137 when the process was terminated with KILL after the grace period.
## @description  Tests whether a px_timeout exit status represents a timeout expiry, including KILL.
## @param        status: The exit status returned by px_timeout.
## @return       Exits 0 when the wait expired, or 1 otherwise.
sb_expired() {
  case "${1:-}" in
  "$SB_EXEC_TIMEOUT" | 137) return 0 ;;
  esac
  return 1
}

# Translates the exit STATUS of a timed runtime call into a contract exit code and logs an error on
# failure. Logs "WHAT expired after <TIMEOUT_S>s" and exits 4 when STATUS indicates a timeout
# expiry, or logs MESSAGE and exits 1 for any other non-zero STATUS.
## @description  Maps and logs the exit STATUS of a timed runtime call.
## @param        STATUS: The exit status returned by the runtime call.
## @param        TIMEOUT_S: The timeout in seconds that governed the call.
## @param        WHAT: Description of the operation used in the timeout message ("WHAT expired").
## @param        MESSAGE: Error message logged when STATUS is a non-timeout failure.
## @return       Exits 0 when STATUS is 0, 4 on expiry ("WHAT expired"), or 1 otherwise (MESSAGE).
sb_outcome() {
  if sb_expired "$1"; then
    px_error "$3 expired after ${2}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  [ "$1" != 0 ] || return 0
  px_error "$4"
  return "$SB_EXIT_FAIL"
}

# Validates that a boolean flag argument VALUE passed as NAME to FUNCTION is either 0 or 1, logging
# a diagnostic and exiting 2 otherwise.
## @description  Validates that the flag argument NAME of FUNCTION has a VALUE of 0 or 1.
## @param        FUNCTION: Calling function name used in diagnostic messages.
## @param        NAME: Parameter name of the flag being validated.
## @param        VALUE: Candidate flag value to test against 0 and 1.
## @return       Exits 0 when VALUE is 0 or 1, or exits 2 with an error message otherwise.
sb_flag() {
  case "${3:-}" in
  0 | 1) return 0 ;;
  esac
  px_error "$1 needs $2 of 0 or 1, got '${3:-}'"
  return "$SB_EXIT_USAGE"
}

# Validates that VALUE, supplied for the argument WHAT of FUNCTION, belongs to the contract
# vocabulary for KIND, logging the allowed vocabulary words and exiting 2 when it does not.
## @description  Validates that the argument WHAT of FUNCTION has a VALUE in the KIND vocabulary.
## @param        FUNCTION: Calling function name used in diagnostic messages.
## @param        WHAT: Human-readable argument description for diagnostics.
## @param        KIND: Contract vocabulary kind passed to sb_valid_value and sb_vocabulary.
## @param        VALUE: Candidate word to validate against the vocabulary of KIND.
## @return       Exits 0 when VALUE belongs to the KIND vocabulary, or 2 with a message otherwise.
sb_value_arg() {
  if sb_valid_value "${3:-}" "${4:-}"; then
    return 0
  fi
  px_error "$1 needs $2: $(sb_vocabulary "${3:-}"), got '${4:-}'"
  return "$SB_EXIT_USAGE"
}

# Validates the framing arguments of an sb_exec call: SCOPE ROLE INDEX TIMEOUT_S -- CMD [ARG...].
# Once this check succeeds, the caller shifts five positional arguments to reach CMD and its
# arguments.
## @description  Validates the argument frame of an sb_exec call before the caller shifts five args.
## @param        args: The full positional argument list SCOPE ROLE INDEX TIMEOUT_S -- CMD [ARG...].
## @return       Exits 0 when the frame includes -- and a command, or 2 with a message otherwise.
sb_exec_args() {
  if [ $# -lt 6 ]; then
    px_error "sb_exec needs a scope, a role, an index, a timeout, -- and a command"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$5" != "--" ]; then
    px_error "sb_exec separates the command with --, got '$5'"
    return "$SB_EXIT_USAGE"
  fi
}

# Validates the SINCE, PREVIOUS, and FOLLOW arguments of sb_logs and normalizes SINCE. Accepts "-"
# or an empty string to request the full log (writing nothing), or a whole number of seconds
# (writing the seconds to stdout).
## @description  Validates the SINCE, PREVIOUS, and FOLLOW arguments of sb_logs, normalizing SINCE.
## @param        SINCE: Log lookback window in whole seconds, or "-" or empty for the entire log.
## @param        PREVIOUS: Flag (0 or 1) selecting logs from the previous instance execution.
## @param        FOLLOW: Flag (0 or 1) enabling live log streaming.
## @return       Writes whole seconds (nothing for the whole log) and exits 0, or 2 with a message.
sb_logs_args() {
  sb_flag sb_logs previous "${2:-}" || return $?
  sb_flag sb_logs follow "${3:-}" || return $?
  case "${1:-}" in
  - | '') return 0 ;;
  *[!0-9]*)
    px_error "sb_logs needs since in whole seconds or -, got '$1'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  printf '%s\n' "$1"
}

# Validates the local and remote path arguments for sb_copy_in (in LOCAL REMOTE) or sb_copy_out
# (out REMOTE LOCAL) and resolves the local path. Exits 2 on missing paths, a non-existent local
# source for "in", or an output destination refused by policy for "out", and exits 1 when the local
# destination path cannot be resolved.
## @description  Validates copy paths (in LOCAL REMOTE or out REMOTE LOCAL) and resolves LOCAL.
## @param        direction: Copy direction, either "in" (LOCAL REMOTE) or "out" (REMOTE LOCAL).
## @param        src: Source path (LOCAL when direction is "in", or REMOTE when direction is "out").
## @param        dst: Target path (REMOTE when direction is "in", or LOCAL when direction is "out").
## @return       Writes LOCAL; exits 0, 2 on usage, absent source, or refused dst, or 1 on error.
sb_copy_args() {
  if [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
    if [ "$1" = in ]; then
      px_error "sb_copy_in needs a scope, a role, an index, a local path and a remote path"
    else
      px_error "sb_copy_out needs a scope, a role, an index, a remote path and a local path"
    fi
    return "$SB_EXIT_USAGE"
  fi
  if [ "$1" = out ]; then
    # When the destination escapes the allowed output root, px_out_resolve terminates the caller's
    # subshell with the usage exit status so that the error code propagates directly to the caller.
    px_out_resolve "$3"
    return $?
  fi
  if [ ! -e "$2" ]; then
    px_error "no such local path: $2"
    return "$SB_EXIT_USAGE"
  fi
  printf '%s\n' "$2"
}

# Normalizes a signal name for sb_signal by stripping an optional SIG prefix and converting the name
# to uppercase. Accepts only the supported signal set and exits 2 for any other name.
## @description  Normalizes a signal name for sb_signal (either case, with or without a SIG prefix).
## @param        signal: The candidate signal name to normalize and validate.
## @return       Writes the uppercase name without SIG; exits 0, or 2 for a name outside the set.
sb_signal_name() {
  local name
  name="$(printf '%s' "${1#SIG}" | tr '[:lower:]' '[:upper:]')"
  case "$name" in
  HUP | INT | QUIT | ABRT | KILL | ALRM | TERM | USR1 | USR2 | STOP | CONT | TSTP)
    printf '%s\n' "$name"
    return 0
    ;;
  esac
  px_error "sb_signal does not accept the signal name '${1:-}'"
  return "$SB_EXIT_USAGE"
}

## @description  Reports the delay in seconds between wait polls from PX_POLL_INTERVAL_S, or 2.
## @return       Writes the polling interval in seconds to stdout and exits 0.
sb_poll_interval() {
  local interval=${PX_POLL_INTERVAL_S:-2}
  [[ $interval =~ $PX_RE_SECONDS ]] || interval=2
  printf '%s\n' "$interval"
}

# Verifies that every space-separated role in SELECTION exists within the space-separated list of
# PRESENT roles for SCOPE, logging an error and exiting 6 if any requested role has no instances.
## @description  Verifies that every space-separated role in SELECTION is listed in PRESENT.
## @param        SCOPE: The scope id checked, used in the diagnostic message when a role is absent.
## @param        PRESENT: Space-separated list of role names currently present in the scope.
## @param        SELECTION: Space-separated list of target role names to verify.
## @return       Exits 0 when every selected role is present, or 6 with an error message otherwise.
sb_selection_check() {
  local word missing=""
  for word in ${3:-}; do
    case " ${2:-} " in
    *" $word "*) ;;
    *) missing="$missing $word" ;;
    esac
  done
  [ -n "$missing" ] || return 0
  px_error "no instance in scope ${1:-} has the role:$missing"
  return "$SB_EXIT_NO_OBJECT"
}

# Checks whether dry-run mode is active so that a mutating operation should be skipped, logging the
# skipped action description formed from WORDS when dry-run mode is enabled.
## @description  Tests whether dry-run mode prevents a change, logging the skipped action WORDS.
## @param        WORDS: Words describing the mutating action that is skipped during a dry run.
## @return       Exits 0 when dry-run mode stops the action, or 1 when the action should proceed.
sb_dry_run() {
  px_is_dry_run || return 1
  px_info "dry run: $* not performed"
  return 0
}

# Scope metadata that cannot be represented in container or cluster labels is persisted in the
# backend's state directory. Each scope stores a key-value record in scope-<id>.tsv, with one key
# and one value per row, alongside backend-specific tables. Because characters in the scope id
# outside A-Za-z0-9._- are sanitized to underscores in file names, the record preserves the raw
# scope id under the key "scope".

# Resolves and ensures the existence of the per-backend state directory under px_state_dir. Exits 2
# when the output policy refuses the state directory, and exits 1 if the directory cannot be
# created.
## @description  Resolves and creates the state directory for BACKEND.
## @param        BACKEND: The backend identifier whose state subdirectory is requested.
## @return       Writes the directory path to stdout; exits 0, 2 on policy refusal, or 1 on error.
sb_state_dir() {
  local dir
  dir="$(px_state_dir)" || return $?
  mkdir -p "$dir/${1:-}" ||
    px_die "$SB_EXIT_FAIL" "cannot create the backend state directory in $dir"
  printf '%s/%s\n' "$dir" "${1:-}"
}

# Computes the path to a per-scope TSV state file for BACKEND, KIND, and SCOPE, where a KIND of
# "scope" names the primary scope record.
## @description  Reports the per-scope state file path for BACKEND, KIND, and SCOPE.
## @param        BACKEND: The backend identifier owning the state directory.
## @param        KIND: The state table kind, where "scope" names the scope record file.
## @param        SCOPE: The scope id, with characters outside A-Za-z0-9._- sanitized to _.
## @return       Writes the file path to stdout; exits 0, 2, or 1 under the rules of sb_state_dir.
sb_record_path() {
  local dir id=${3:-}
  dir="$(sb_state_dir "${1:-}")" || return $?
  printf '%s/%s-%s.tsv\n' "$dir" "${2:-}" "${id//[^A-Za-z0-9._-]/_}"
}

## @description  Reads the value associated with KEY from the scope record of SCOPE on BACKEND.
## @param        BACKEND: The backend identifier owning the scope record.
## @param        SCOPE: The target scope id to inspect.
## @param        KEY: The record key to look up.
## @return       Writes the value to stdout; exits 0, or 6 when the record or KEY is absent.
sb_record_get() {
  local path
  path="$(sb_record_path "${1:-}" scope "${2:-}")" || return "$SB_EXIT_NO_OBJECT"
  [ -f "$path" ] || return "$SB_EXIT_NO_OBJECT"
  sb_row_get "${3:-}" "$(cat "$path")"
}

# Atomically sets KEY to a single row holding VALUE in the scope record for SCOPE on BACKEND,
# replacing any existing row for KEY.
## @description  Writes KEY once with VALUE into the scope record for BACKEND and SCOPE.
## @param        BACKEND: The backend identifier owning the scope record.
## @param        SCOPE: The target scope id to update.
## @param        KEY: The record key to insert or replace.
## @param        VALUE: The value to associate with KEY.
## @return       Exits 0 on success, or 1 when the scope record cannot be written.
sb_record_set() {
  local path tmp row
  path="$(sb_record_path "${1:-}" scope "${2:-}")" || return "$SB_EXIT_FAIL"
  tmp="$path.$$"
  {
    if [ -f "$path" ]; then
      while IFS= read -r row; do
        case "$row" in
        "${3:-}$PX_TAB"* | '') continue ;;
        esac
        printf '%s\n' "$row"
      done <"$path"
    fi
    px_tsv_row "${3:-}" "${4:-}"
  } >"$tmp" && mv -f "$tmp" "$path" && return 0
  rm -f "$tmp"
  px_error "cannot write the scope record $path"
  return "$SB_EXIT_FAIL"
}

## @description  Removes the specified KIND state files (for example, scope) for SCOPE on BACKEND.
## @param        BACKEND: The backend identifier owning the state directory.
## @param        SCOPE: The target scope id whose state files should be removed.
## @param        KIND: One or more state file kinds to delete, such as scope.
## @return       Exits 0, even when the output policy refuses the state directory.
sb_record_drop() {
  local backend=${1:-} scope=${2:-} kind path
  shift 2
  for kind in "$@"; do
    if path="$(sb_record_path "$backend" "$kind" "$scope")"; then
      rm -f "$path"
    fi
  done
  return 0
}

# Executes the admission gate for creating a new scope while holding the backend's host lock. The
# callback FUNCTION inspects the substrate for conflicting scopes and then invokes sb_record_gate.
## @description  Runs the scope-creation gate FUNCTION for SCOPE under the BACKEND host lock.
## @param        BACKEND: The backend identifier whose host lock is acquired.
## @param        FUNCTION: Callback that checks the substrate and invokes sb_record_gate.
## @param        SCOPE: The candidate scope id passed to FUNCTION.
## @return       Exits 0 when the gate passes, or 1 if the gate refuses or the lock is not taken.
sb_scope_gated() {
  local dir status=0
  dir="$(sb_state_dir "${1:-}")" || return "$SB_EXIT_FAIL"
  px_lock "$dir/host.lock" 30 || return "$SB_EXIT_FAIL"
  "$2" "${3:-}" || status=$?
  px_unlock "$dir/host.lock"
  return "$status"
}

# Performs the state-record check of the new-scope gate for BACKEND and SCOPE. Unless PX_FORCE=1 is
# set, refuses creation if any scope record already exists on the host, then initializes a fresh
# record containing the scope id and the creation timestamp.
## @description  Enforces the state-record gate for a new scope and initializes its scope record.
## @param        BACKEND: The backend identifier owning the state directory.
## @param        SCOPE: The new scope id to admit and record.
## @return       Exits 0 when the new record is written, or 1 if the gate refuses or writing fails.
sb_record_gate() {
  local backend=${1:-} scope=${2:-} path others
  if ! px_is_true "${PX_FORCE:-0}"; then
    others="$(sb_record_scopes "$backend" "$scope")" || return "$SB_EXIT_FAIL"
    if [ -n "$others" ]; then
      px_error "another scope is recorded on this host:$others. Tear it down first," \
        "or set PX_FORCE=1 when the record is stale."
      return "$SB_EXIT_FAIL"
    fi
  fi
  path="$(sb_record_path "$backend" scope "$scope")" || return "$SB_EXIT_FAIL"
  if [ -f "$path" ] && ! px_is_true "${PX_FORCE:-0}"; then
    px_error "scope $scope already exists in $path." \
      "Run a teardown, or set PX_FORCE=1 to replace the record."
    return "$SB_EXIT_FAIL"
  fi
  rm -f "$path"
  sb_record_set "$backend" "$scope" scope "$scope" || return "$SB_EXIT_FAIL"
  sb_record_set "$backend" "$scope" created_at "$(px_timestamp)" || return "$SB_EXIT_FAIL"
}

## @description  Reports every recorded scope id for BACKEND except an optional EXCEPT scope.
## @return       Writes each scope id preceded by a space, or nothing if none exist; exits 0, or 2
##               when the output policy refuses the state directory.
sb_record_scopes() {
  local dir path name
  dir="$(px_state_dir)" || return $?
  for path in "$dir/${1:-}"/scope-*.tsv; do
    [ -f "$path" ] || continue
    name="$(sb_row_get scope "$(cat "$path")")" || continue
    [ "$name" = "${2:-}" ] || printf ' %s' "$name"
  done
}

## @description  Reports the label namespace prefix that scopes every substrate object.
## @return       Writes the label prefix to stdout and exits 0.
sb_label_prefix() { printf '%s\n' "${PX_LABEL_PREFIX:-px.harness}"; }

## @description  Reports the project name prefix used when constructing a scope identifier.
## @return       Writes the scope name prefix to stdout and exits 0.
sb_project_prefix() { printf '%s\n' "${PX_PROJECT_PREFIX:-px}"; }

## @description  Tests whether ROLE matches the space-separated role filter SELECTION.
## @param        ROLE: The role name to test.
## @param        SELECTION: Space-separated list of selected roles, or empty to match every role.
## @return       Exits 0 when ROLE is in SELECTION or SELECTION is empty, or 1 otherwise.
sb_role_selected() {
  local role=${1:-} selection=${2:-} word
  [ -n "$selection" ] || return 0
  for word in $selection; do
    if [ "$word" = "$role" ]; then
      return 0
    fi
  done
  return 1
}

# Compares a semantic version string (with or without a leading "v") numerically against a required
# major and minor version floor.
## @description  Compares VERSION numerically against FLOOR_MAJOR and FLOOR_MINOR.
## @param        VERSION: Version string to test (leading "v" and patch suffix are ignored).
## @param        FLOOR_MAJOR: Minimum required major version number; defaults to 0.
## @param        FLOOR_MINOR: Minimum required minor version number; defaults to 0.
## @return       Exits 0 if VERSION meets the floor, 1 if lower, or 2 if VERSION holds no number.
sb_version_at_least() {
  local version=${1:-} major_floor=${2:-0} minor_floor=${3:-0} major minor
  version="${version#v}"
  major="${version%%.*}"
  minor="${version#*.}"
  minor="${minor%%.*}"
  case "$major" in
  '' | *[!0-9]*) return "$SB_EXIT_USAGE" ;;
  esac
  case "$minor" in
  '' | *[!0-9]*) minor=0 ;;
  esac
  if [ "$((10#$major))" -gt "$major_floor" ]; then
    return 0
  fi
  if [ "$((10#$major))" -lt "$major_floor" ]; then
    return 1
  fi
  if [ "$((10#$minor))" -ge "$minor_floor" ]; then
    return 0
  fi
  return 1
}

# Compares VERSION against the major.minor version floor stored in the variable named by NAME, such
# as PX_K3D_MIN_VERSION from docker/versions.env.
## @description  Tests VERSION against the major.minor floor stored in the variable NAME.
## @return       Exits 0 when VERSION meets the floor, 1 when it is lower, or 2 when either value
##               contains no version number.
sb_version_floor() {
  local floor=${!2:-}
  case "$floor" in
  [0-9]*.[0-9]*) sb_version_at_least "${1:-}" "${floor%%.*}" "${floor#*.}" ;;
  *) return "$SB_EXIT_USAGE" ;;
  esac
}

# Defines the lowercase grammar for a registry address, consisting of a host, an optional port, and
# an optional path. Because the container engine interprets a leading component without a dot, a
# port, or the name localhost as a Docker Hub namespace, accepting such a value would direct a push
# to a public registry; the host validation rule therefore rejects it.
SB_REGISTRY_RE='^[a-z0-9]([-a-z0-9.]*[a-z0-9])?(:[0-9]+)?(/[a-z0-9]([-a-z0-9._]*[a-z0-9])?)*$'

## @description  Validates a registry value against SB_REGISTRY_RE and the explicit host rule.
## @return       Exits 0 when the value names an explicit registry host, or 1 otherwise.
sb_registry_check() {
  local host=${1:-}
  host=${host%%/*}
  [[ ${1:-} =~ $SB_REGISTRY_RE ]] || return "$SB_EXIT_FAIL"
  case "$host" in
  *.* | *:* | localhost) return 0 ;;
  esac
  return "$SB_EXIT_FAIL"
}

## @description  Emits one phase row for a compound operation (PHASE, ok|fail|skip, and START_MS).
## @return       Writes one phase TSV row to stdout and exits 0.
sb_phase_row() {
  px_tsv_row "${1:-}" "${2:-}" "$(($(px_epoch_ms) - ${3:-0}))"
}

## @description  Emits a fail row for PHASE and a skip row for each subsequent restart-drain phase.
## @param        PHASE: The phase that failed within drain, stop, start, ready, and rebalance.
## @param        START_MS: Start timestamp in epoch milliseconds for the failed phase.
## @return       Writes the fail and remaining skip phase TSV rows to stdout and exits 0.
sb_phase_fail() {
  local phase row=""
  for phase in drain stop start ready rebalance; do
    if [ "$phase" = "$1" ]; then
      sb_phase_row "$phase" fail "$2"
      row=skip
    elif [ -n "$row" ]; then
      sb_phase_row "$phase" skip "$2"
    fi
  done
}

# Expands the region mover template from PX_REGION_MOVER_CMD (or SB_MOVER_DEFAULT) by substituting
# OPERATION, HOST, PORT, TIMEOUT_S, and FILE into their corresponding placeholders.
## @description  Substitutes placeholders in PX_REGION_MOVER_CMD or SB_MOVER_DEFAULT.
## @param        OPERATION: Mover operation to perform, such as unload or load.
## @param        HOST: Registered RegionServer hostname for {host}.
## @param        PORT: RegionServer RPC port for {port}.
## @param        TIMEOUT_S: Timeout in seconds for {timeout}.
## @param        FILE: State file path inside the container for {file}.
## @return       Writes the command line; exits 0, or 6 when the template requires an empty PORT.
sb_mover_cmd() {
  local operation=${1:-} host=${2:-} port=${3:-} timeout=${4:-} file=${5:-} cmd
  cmd="${PX_REGION_MOVER_CMD:-$SB_MOVER_DEFAULT}"
  case "$cmd" in
  *'{port}'*) [ -n "$port" ] || return "$SB_EXIT_NO_OBJECT" ;;
  esac
  cmd="${cmd//\{operation\}/$operation}"
  cmd="${cmd//\{host\}/$host}"
  cmd="${cmd//\{port\}/$port}"
  cmd="${cmd//\{timeout\}/$timeout}"
  cmd="${cmd//\{file\}/$file}"
  printf '%s\n' "$cmd"
}

# Executes either the drain or rebalance mover phase of a drain-aware restart inside the target
# container (SCOPE, ROLE, INDEX) via sb_exec, emitting the resulting phase TSV rows to stdout.
## @description  Runs the drain or rebalance mover phase of a drain-aware restart via sb_exec.
## @param        PHASE: The mover phase to execute, either drain or rebalance.
## @param        START_MS: Phase start timestamp in epoch milliseconds.
## @param        SUBJECT: Human-readable target label used in diagnostic messages.
## @param        TIMEOUT_S: Timeout in seconds for the mover execution.
## @param        CMD: Shell command line to execute inside the target container.
## @param        SCOPE: The scope id containing the execution target.
## @param        ROLE: The target role name for sb_exec.
## @param        INDEX: The target instance index for sb_exec.
## @return       Writes phase TSV rows to stdout; exits 0 if ok, 4 if the mover expired, or 1.
sb_mover_phase() {
  local phase=$1 start=$2 subject=$3 timeout=$4 status=0 what=rebalance
  [ "$phase" != drain ] || what="region drain"
  # Because the region mover writes progress messages to stdout while this function reserves stdout
  # for structured phase TSV rows, the mover's output is redirected to stderr.
  sb_exec "$6" "$7" "$8" "$timeout" -- sh -c "$5" </dev/null >&2 || status=$?
  if [ "$status" = 0 ]; then
    sb_phase_row "$phase" ok "$start"
    return 0
  fi
  sb_phase_fail "$phase" "$start"
  if [ "$status" = "$SB_EXEC_TIMEOUT" ]; then
    px_error "the $what of $subject expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  px_error "the $what of $subject failed with status $status"
  return "$SB_EXIT_FAIL"
}

# Defines the default region mover command template with five placeholders substituted by
# sb_mover_cmd: {operation}, {host}, {port}, {timeout}, and {file}. Here {host} is the hostname
# under which the RegionServer registered, and {port} is its rpc port from the port map as
# advertised in the RegionServer server name; each backend resolves both values for its substrate.
# Setting PX_REGION_MOVER_CMD overrides this template. A role's drain label, rather than this
# variable, determines whether the role uses drain semantics.
SB_MOVER_DEFAULT='hbase org.apache.hadoop.hbase.util.RegionMover'
SB_MOVER_DEFAULT="$SB_MOVER_DEFAULT --regionserverhost {host}:{port}"
SB_MOVER_DEFAULT="$SB_MOVER_DEFAULT --operation {operation}"
SB_MOVER_DEFAULT="$SB_MOVER_DEFAULT --timeout {timeout}"
SB_MOVER_DEFAULT="$SB_MOVER_DEFAULT --filename {file}"

# Defines the remote script that delivers a signal to an instance's daemon JVM, identified as the
# oldest java process. Because the container's initial process is tini, which cannot forward SIGSTOP
# to child processes, the script signals the JVM directly. It then polls the JVM process state for
# up to five seconds to verify that STOP or TSTP transitions the process to the stopped state and
# that CONT resumes execution, ensuring that an ineffective pause is treated as a failure. The
# script exits 6 when no JVM is running, 1 when kill fails, and 3 when the expected state is not
# observed within five seconds (including when the state cannot be read). The script is assigned via
# read rather than command substitution because bash 3.2 misparses a case statement inside $(...).
SB_SIGNAL_SCRIPT=""
read -r -d '' SB_SIGNAL_SCRIPT <<'SCRIPT_EOF' || true
pid="$(pgrep -o -x java)" || exit 6
kill -s "$1" "$pid" || exit 1
case "$1" in
STOP | TSTP) want=T ;;
CONT) want=R ;;
*) exit 0 ;;
esac
n=0
while :; do
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ')"
  case "$want:$state" in
  T:T* | R:[!T]*) exit 0 ;;
  esac
  [ "$n" -lt 50 ] || exit 3
  n=$((n + 1))
  sleep 0.1
done
SCRIPT_EOF

# Delivers a normalized signal NAME to the daemon JVM of an already-resolved instance via sb_exec
# and SB_SIGNAL_SCRIPT. Exits 0 on success, 6 when the instance runs no JVM process, and 1 when the
# signal cannot be delivered or the expected process state does not appear within 5 s.
## @description  Sends signal NAME to the daemon JVM of a resolved instance via sb_exec.
## @param        SCOPE: The scope id containing the target instance.
## @param        ROLE: The role name of the target instance (must be resolved before calling).
## @param        INDEX: The instance index of the target instance (must be resolved before calling).
## @param        TIMEOUT_S: Timeout in seconds passed to sb_exec.
## @param        NAME: Normalized signal name passed to SB_SIGNAL_SCRIPT.
## @return       Exits 0, 1 if delivery fails or the state does not show within 5 s, or 6 if no JVM.
sb_signal_jvm() {
  local status=0
  sb_exec "$1" "$2" "$3" "$4" -- bash -c "$SB_SIGNAL_SCRIPT" px-signal "$5" </dev/null \
    >/dev/null || status=$?
  case "$status" in
  0) return 0 ;;
  "$SB_EXIT_NO_OBJECT")
    px_error "$2/$3 runs no JVM, so the signal $5 has no target"
    return "$SB_EXIT_NO_OBJECT"
    ;;
  3) px_error "the JVM of $2/$3 did not reach the $5 state within 5 s" ;;
  *) px_error "the signal $5 to $2/$3 was not delivered" ;;
  esac
  return "$SB_EXIT_FAIL"
}
