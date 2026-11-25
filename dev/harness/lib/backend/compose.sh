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

# This module implements the Docker Compose backend for the substrate contract. It provides all 32
# sb_* contract functions and serves as the sole file in this repository permitted to invoke a
# container runtime directly.
#
# Four architectural characteristics of the Compose runtime govern this implementation:
#   1. Compose has no native scope object. Scope is represented by a project name paired with a
#      label set, so every resource selection in this file operates as a label filter.
#   2. Compose addresses a service rather than an individual instance. Because the instance index
#      is derived from the container number, this module translates between the contract's
#      zero-based index and Compose's one-based container number.
#   3. Compose retains no record of a previous container. Retrieving the previous log or previous
#      termination reason is therefore unsupported and returns exit code 5; callers inspect the
#      restart count and the current termination reason instead.
#   4. The container runtime reports health rather than readiness. Readiness is derived from the
#      health state produced by the image healthcheck defined by A2, so this module inspects only
#      the reported health status and nothing else.
#
# All interaction with the container runtime passes through a single choke point, px_compose_call.
# Dry-run execution performs no state mutations: operations that create, remove, stop, or copy
# resources report the planned action and return 0.

# This file is a shell library that must be sourced rather than executed directly; running it as a
# standalone script is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/backend/compose.sh is a library. Source it with sb_load.\n' >&2
  exit 2
fi

if [ -n "${PX_COMPOSE_LOADED:-}" ]; then
  return 0
fi
PX_COMPOSE_LOADED=1

PX_COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/contract.sh
. "$PX_COMPOSE_DIR/contract.sh"

# ---------------------------------------------------------------------------
# 1. Backend Identity and Versioning
# ---------------------------------------------------------------------------
# The minimum Docker engine version floor is the first release supporting the Compose v2 flags
# required by this module: the cp subcommand, the index selector on exec and cp, and project
# selection without an explicit compose file. The minimum Compose version floor is set to the v2
# release series for the same reason.
PX_COMPOSE_ENGINE_FLOOR=20.10
PX_COMPOSE_FLOOR=2.0

## @description  Report the backend name recorded in the bundle manifest.
## @return       Write compose to stdout and exit 0.
sb_backend_id() { printf 'compose\n'; }

# ---------------------------------------------------------------------------
# 2. Container Runtime Resolution and Invocation
# ---------------------------------------------------------------------------
# The container runtime is Docker, and Compose is invoked as the compose subcommand of its CLI.
#
# The resolved executable path is stored as an absolute path in a private variable within this file
# and is never exported, preventing child processes such as workload drivers from reaching the
# runtime through inherited environment variables. Using an absolute path serves a second purpose:
# lib/common.sh defines a guard shell function for each runtime name to trap stray calls, and
# because px_timeout starts commands without the command builtin, a bare command name would trigger
# that trap, whereas a filesystem path can never name a shell function.

_PXC_ENGINE=""

# Retain any operator-supplied PX_DOCKER_BIN value within the current shell process while stripping
# it from the exported environment of child processes. This unexport is performed at library load
# time because engine resolution frequently executes inside a command substitution subshell, where
# modifying export attributes would not propagate back to the parent shell.
if [ -n "${PX_DOCKER_BIN+set}" ]; then
  export -n PX_DOCKER_BIN
fi

# Resolve the container runtime executable once per process, checking PX_DOCKER_BIN first, then
# PATH, and finally standard desktop installation paths.
## @description  Resolve the container runtime binary and store its absolute path in _PXC_ENGINE.
## @return       Exit 0 when _PXC_ENGINE holds an absolute path, or 1 when no runtime is present.
px_compose_resolve_engine() {
  local named candidate found=""
  [ -z "$_PXC_ENGINE" ] || return 0
  named="${PX_DOCKER_BIN:-}"
  if [ -n "$named" ]; then
    case "$named" in
    */*) [ -x "$named" ] && [ ! -d "$named" ] && found="$named" ;;
    *) found="$(type -P "$named" 2>/dev/null)" || found="" ;;
    esac
    if [ -z "$found" ]; then
      px_error "PX_DOCKER_BIN names no executable: $named"
      return "$SB_EXIT_FAIL"
    fi
  else
    # Query PATH directly via `type -P` so that lookup bypasses the shell trap functions defined in
    # lib/common.sh.
    found="$(type -P docker 2>/dev/null)" || found=""
    if [ -z "$found" ]; then
      for candidate in \
        "${HOME:-}/.docker/bin/docker" \
        /usr/local/bin/docker \
        /opt/homebrew/bin/docker \
        /usr/bin/docker; do
        if [ -x "$candidate" ]; then
          found="$candidate"
          break
        fi
      done
    fi
    if [ -z "$found" ]; then
      px_error "no Docker CLI found. Install Docker, or set PX_DOCKER_BIN in env/local.env."
      return "$SB_EXIT_FAIL"
    fi
  fi
  case "$found" in
  /*) ;;
  *) found="$PWD/$found" ;;
  esac
  _PXC_ENGINE="$found"
  px_debug "container runtime: $_PXC_ENGINE"
}

# Execute all container runtime and Compose invocations through a single gateway accepting a surface
# selector (runtime, compose, or merged to redirect stderr to stdout via 2>&1), a timeout in seconds
# (TIMEOUT_S or - for no timeout), and the command arguments (ARG...).
## @description  Execute the single runtime call for runtime, compose, or merged (2>&1) commands.
## @return       Return the runtime status, 124 on expiry, 137 on KILL after the grace period, 125
##               if the watchdog cannot start, 1 if no runtime resolves, or 2 on unknown surface.
px_compose_call() {
  local surface=${1:-} timeout=${2:-} status=0
  shift 2 || true
  px_compose_resolve_engine || return "$SB_EXIT_FAIL"
  px_debug "$surface call: $*"
  case "$surface" in
  runtime | merged) set -- "$_PXC_ENGINE" "$@" ;;
  compose) set -- "$_PXC_ENGINE" compose "$@" ;;
  *)
    px_error "px_compose_call: unknown surface '$surface'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  if [ "$timeout" = "-" ] && [ "$surface" = merged ]; then
    command "$@" 2>&1 || status=$?
  elif [ "$timeout" = "-" ]; then
    command "$@" || status=$?
  elif [ "$surface" = merged ]; then
    px_timeout "$timeout" "$@" 2>&1 || status=$?
  else
    px_timeout "$timeout" "$@" || status=$?
  fi
  return "$status"
}

# Invoke a direct container runtime verb such as inspect or kill with a timeout in seconds
# (TIMEOUT_S or - for no timeout) and command arguments (ARG...).
## @description  Execute a direct container runtime verb such as inspect or kill.
## @return       Return the exit status of px_compose_call.
px_compose_engine() { px_compose_call runtime "$@"; }

# Invoke a Compose subcommand within the project context and compose file recorded for SCOPE, using
# a timeout in seconds (TIMEOUT_S) and the supplied Compose arguments (ARG...).
## @description  Execute a Compose verb within the project and compose file of SCOPE.
## @return       Return the Compose exit status, or exit 2 when SCOPE is not a valid scope ID of
##               this backend.
px_compose_project() {
  local scope=${1:-} timeout=${2:-} status=0
  shift 2 || true
  px_compose_scope_load "$scope" || return $?
  if [ -n "$PX_COMPOSE_FILE" ] && [ -f "$PX_COMPOSE_FILE" ]; then
    if [ -n "$PX_COMPOSE_DEPLOY_DIR" ]; then
      set -- --project-directory "$PX_COMPOSE_DEPLOY_DIR" "$@"
    fi
    set -- -f "$PX_COMPOSE_FILE" "$@"
  elif [ -n "$PX_COMPOSE_FILE" ]; then
    px_warn "the compose file $PX_COMPOSE_FILE is gone, so compose names" \
      "the project $PX_COMPOSE_PROJECT only"
  fi
  px_compose_call compose "$timeout" -p "$PX_COMPOSE_PROJECT" "$@" || status=$?
  return "$status"
}

# ---------------------------------------------------------------------------
# 3. Configuration Values, Timeouts, and Dry-Run Execution
# ---------------------------------------------------------------------------
# Every blocking wait enforces a finite timeout. Operations that accept an explicit timeout argument
# use it directly, while operations without a contract-level timeout parameter fall back to the
# corresponding PX_TIMEOUT_* configuration variable. The fallback durations in seconds at each call
# site match docker/env/defaults.env so that callers binding the backend without the configuration
# loader observe identical wait durations. The helper functions that resolve timeouts, boolean
# flags, and dry-run mode are shared in lib/backend/contract.sh so that both backends apply uniform
# validation rules.

# ---------------------------------------------------------------------------
# 4. Scope Management: Project Names, Labels, and Persistent State Records
# ---------------------------------------------------------------------------
# Because Docker Compose lacks a native scope resource, a scope is synthesized from a Compose
# project name paired with a standardized label set. The scope identifier serves directly as the
# project name, formed by combining the project prefix with the cluster identifier. Every resource
# created by the backend carries the cluster label, while optional role groups carry a group label
# that allows subsystems such as the YARN tier to be stopped and started independently within a
# running cluster.
#
# A persistent state record stored under PX_OUT captures metadata that container labels cannot
# represent before or after container existence, specifically the deployment directory and the
# compose file path. Subsequent CLI processes read this record so follow-up commands automatically
# target the same deployment without requiring repeated path arguments.

PX_COMPOSE_SCOPE=""
PX_COMPOSE_PROJECT=""
PX_COMPOSE_CLUSTER=""
PX_COMPOSE_FILE=""
PX_COMPOSE_DEPLOY_DIR=""
PX_COMPOSE_NETWORK=""

## @description  Load the PX_COMPOSE_* values of SCOPE; the ID alone names the project and cluster.
## @return       Exit 0 on success, or exit 2 when the ID is empty or does not belong to this
##               backend.
px_compose_scope_load() {
  local scope=${1:-} prefix
  prefix="$(sb_project_prefix)"
  if [ -z "$scope" ]; then
    px_error "a scope id is required"
    return "$SB_EXIT_USAGE"
  fi
  case "$scope" in
  "$prefix"-?*) ;;
  *)
    px_error "'$scope' is not a scope id of this backend, expected $prefix-<cluster>"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  if [ "$PX_COMPOSE_SCOPE" = "$scope" ]; then
    return 0
  fi
  PX_COMPOSE_SCOPE="$scope"
  PX_COMPOSE_PROJECT="$scope"
  PX_COMPOSE_CLUSTER="${scope#"$prefix"-}"
  # A scope without a persisted record is a normal pre-provisioning state, so an absent lookup
  # result must be absorbed inside the command substitution. The inline || guard prevents that
  # expected non-zero status from triggering a failure trap armed by the caller.
  PX_COMPOSE_DEPLOY_DIR="$(sb_record_get compose "$scope" deployment_dir || true)"
  PX_COMPOSE_FILE="$(sb_record_get compose "$scope" compose_file || true)"
  PX_COMPOSE_NETWORK="$(sb_record_get compose "$scope" network || true)"
  if [ -z "$PX_COMPOSE_NETWORK" ]; then
    PX_COMPOSE_NETWORK="${scope}_default"
  fi
  return 0
}

## @description  Report the cluster label selector expression for SCOPE.
## @return       Write the selector to stdout and exit 0, or exit 2 when SCOPE is not a valid scope
##               ID of this backend.
px_compose_selector() {
  local prefix
  px_compose_scope_load "${1:-}" || return $?
  prefix="$(sb_label_prefix)"
  printf '%s.cluster=%s\n' "$prefix" "$PX_COMPOSE_CLUSTER"
}

## @description  Report every harness cluster on this host as "project PROJECT (cluster ID)".
## @return       Write one cluster entry per line to stdout and exit 0, or exit 1 when the
##               container runtime cannot be reached.
px_compose_host_clusters() {
  local prefix out
  prefix="$(sb_label_prefix)"
  out="$(px_compose_engine 30 ps -a --filter "label=$prefix.cluster" \
    --format "{{.Label \"$prefix.cluster\"}}|{{.Label \"com.docker.compose.project\"}}")" ||
    return "$SB_EXIT_FAIL"
  # Include the Compose project name in the refusal summary because operators reference the project
  # name to tear down a leftover cluster; containers not created by Compose carry no project label.
  printf '%s\n' "$out" | awk -F '|' '$1 != "" {
    printf "project %s (cluster %s)\n", ($2 == "" ? "unknown" : $2), $1 }' |
    LC_ALL=C sort -u
}

## @description  Refuse admission if any harness container exists on the host or another scope
##               record is present, then record SCOPE with its project and network.
## @return       Exit 0 when the record is written, or exit 1 if the gate refuses or a step fails.
px_compose_scope_gate() {
  local scope=${1:-} present status=0
  present="$(px_compose_host_clusters)" || status=$?
  if [ "$status" != 0 ]; then
    px_error "the container runtime did not answer, so the host gate cannot run"
    return "$SB_EXIT_FAIL"
  fi
  if [ -n "$present" ]; then
    px_error "a harness cluster is already on this host: $(px_tsv_clean "$present")." \
      "Tear it down first, because the harness runs one cluster per host."
    return "$SB_EXIT_FAIL"
  fi
  sb_record_gate compose "$scope" || return "$SB_EXIT_FAIL"
  sb_record_set compose "$scope" cluster "$PX_COMPOSE_CLUSTER" || return "$SB_EXIT_FAIL"
  sb_record_set compose "$scope" project "$PX_COMPOSE_PROJECT" || return "$SB_EXIT_FAIL"
  sb_record_set compose "$scope" network "$PX_COMPOSE_NETWORK" || return "$SB_EXIT_FAIL"
}

# Create the scope envelope for a cluster deployment—consisting of a Compose project name, a
# standard label set, and a persistent state record—after verifying the single-cluster-per-host
# admission gate.
## @description  Create a cluster scope (project name, label set, and record) behind the host gate.
## @param        cluster_id: cluster name matching SB_CLUSTER_RE; defaults to PX_CLUSTER_ID
## @return       Write the scope ID to stdout and exit 0, exit 1 if the gate refuses, the runtime
##               does not respond, or the record cannot be written, or exit 2 on invalid usage.
sb_scope_create() {
  local cluster=${1:-${PX_CLUSTER_ID:-}} prefix scope
  if [ -z "$cluster" ]; then
    px_error "sb_scope_create needs a cluster id"
    return "$SB_EXIT_USAGE"
  fi
  if ! [[ $cluster =~ $SB_CLUSTER_RE ]]; then
    px_error "a cluster id starts with a lower-case letter or a digit and holds" \
      "lower-case letters, digits, - and _ only: '$cluster'"
    return "$SB_EXIT_USAGE"
  fi
  prefix="$(sb_project_prefix)"
  scope="$prefix-$cluster"
  px_compose_scope_load "$scope" || return $?
  if sb_dry_run "create the scope $scope"; then
    printf '%s\n' "$scope"
    return 0
  fi
  sb_scope_gated compose px_compose_scope_gate "$scope" || return $?
  px_config_set PX_SCOPE_ID "$scope" backend
  printf '%s\n' "$scope"
}

## @description  Test whether a scope exists via a labeled container or a persisted backend record.
## @param        scope_id: scope ID returned by sb_scope_create (<PX_PROJECT_PREFIX>-<cluster>)
## @return       Exit 0 when present, 6 when absent, 1 when the runtime does not respond and no
##               record exists, or 2 on invalid usage.
sb_scope_exists() {
  local scope=${1:-} rows path status=0
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope")" || status=$?
  if [ "$status" = 0 ] && [ -n "$rows" ]; then
    return 0
  fi
  path="$(sb_record_path compose scope "$scope")" || return "$SB_EXIT_FAIL"
  if [ -f "$path" ]; then
    px_debug "scope $scope has a record but no instance"
    return 0
  fi
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return "$SB_EXIT_NO_OBJECT"
}

## @description  Remove the project network, volumes, and state record that outlive the containers.
## @param        scope_id: the scope ID returned by sb_scope_create
## @return       Exit 0 on success, exit 1 when a resource could not be removed, or exit 2 on usage.
sb_scope_delete() {
  local scope=${1:-} timeout failed=0 name out
  px_compose_scope_load "$scope" || return $?
  if sb_dry_run "delete the scope $scope"; then
    return 0
  fi
  timeout="$(sb_seconds PX_TIMEOUT_TEARDOWN 300)"
  out="$(px_compose_engine "$timeout" network ls --format '{{.Name}}' \
    --filter "name=^${PX_COMPOSE_NETWORK}$")" || {
    px_error "the container runtime did not list the networks of $scope"
    failed=1
    out=""
  }
  if [ -n "$out" ]; then
    if ! px_compose_engine "$timeout" network rm "$PX_COMPOSE_NETWORK" >/dev/null; then
      px_error "cannot remove the network $PX_COMPOSE_NETWORK"
      failed=1
    fi
  fi
  if px_is_keep; then
    px_info "PX_KEEP is set, the volumes of $scope stay"
  else
    out="$(px_compose_volumes "$scope")" || {
      px_error "the container runtime did not list the volumes of $scope"
      failed=1
      out=""
    }
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      if ! px_compose_engine "$timeout" volume rm "$name" >/dev/null; then
        px_error "cannot remove the volume $name"
        failed=1
      fi
    done <<EOF
$out
EOF
  fi
  sb_record_drop compose "$scope" scope starts
  PX_COMPOSE_SCOPE=""
  [ "$failed" = 0 ] || return "$SB_EXIT_FAIL"
}

## @description  List all volumes belonging to SCOPE by the cluster label and Compose project label.
## @return       Write one volume name per line to stdout and exit 0, exit 2 on invalid usage, or
##               exit 1 when the runtime does not respond.
px_compose_volumes() {
  local scope=${1:-} selector out other
  px_compose_scope_load "$scope" || return $?
  selector="$(px_compose_selector "$scope")" || return "$SB_EXIT_FAIL"
  out="$(px_compose_engine 60 volume ls -q --filter "label=$selector")" || return "$SB_EXIT_FAIL"
  other="$(px_compose_engine 60 volume ls -q \
    --filter "label=com.docker.compose.project=$PX_COMPOSE_PROJECT")" || return "$SB_EXIT_FAIL"
  printf '%s\n%s\n' "$out" "$other" | sed '/^$/d' | LC_ALL=C sort -u
}

# ---------------------------------------------------------------------------
# 5. Instance Inventory and Identity Resolution
# ---------------------------------------------------------------------------
# A single container runtime query answers all inventory questions. Its format template extracts
# both the harness metadata labels written by the generator (`cluster`, `role`, `index`, `group`,
# `start`, and `drain` under the configured label prefix, which form the interface seam defined in
# docs/backends.md) and the two native labels managed by Docker Compose itself (the service name
# and the container number).
#
# Because the substrate contract uses zero-based instance indices whereas Docker Compose numbers
# containers starting from one, all index conversion is centralized exclusively in this section.
#
# The formatted runtime status string embeds both the healthcheck state and any termination exit
# code, allowing readiness and exit status to be determined without issuing a second runtime query.

PX_PS_ID=""
PX_PS_ROLE=""
PX_PS_NAME=""
PX_PS_STATE=""
PX_PS_RUNTIME_STATE=""
PX_PS_READY=""
PX_PS_EXIT=""
PX_PS_HEALTH=""
PX_PS_IMAGE=""
PX_PS_SERVICE=""
PX_PS_NUMBER=""
PX_PS_INDEX=""
PX_PS_GROUP=""
PX_PS_START=""
PX_PS_DRAIN=""
PX_COMPOSE_INDEX=""

## @description  Report the pipe-delimited inventory format string; vertical bars separate fields
##               because label values may contain spaces.
## @return       Write the Go template format string to stdout and exit 0.
px_compose_ps_format() {
  local prefix fmt
  prefix="$(sb_label_prefix)"
  fmt="{{.ID}}|{{.Label \"$prefix.role\"}}|{{.Names}}|{{.State}}|{{.Status}}"
  fmt="$fmt|{{.Image}}|{{.Label \"com.docker.compose.service\"}}"
  fmt="$fmt|{{.Label \"com.docker.compose.container-number\"}}"
  fmt="$fmt|{{.Label \"$prefix.index\"}}|{{.Label \"$prefix.group\"}}"
  fmt="$fmt|{{.Label \"$prefix.start\"}}|{{.Label \"$prefix.drain\"}}"
  printf '%s\n' "$fmt"
}

## @description  List all containers in SCOPE, including stopped containers (SCOPE [FILTER_ARG...]).
## @return       Write one raw inventory line per container to stdout and exit 0, exit 2 on invalid
##               usage, or exit 1 when the container runtime cannot be reached.
px_compose_ps() {
  local scope=${1:-} selector fmt status=0
  shift || true
  selector="$(px_compose_selector "$scope")" || return $?
  fmt="$(px_compose_ps_format)"
  px_compose_engine 60 ps -a --filter "label=$selector" "$@" \
    --format "$fmt" || status=$?
  if [ "$status" != 0 ]; then
    px_error "the container runtime did not list the instances of scope $scope"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

## @description  Set PX_COMPOSE_INDEX to INDEX_LABEL if numeric, else NUMBER minus 1, or else 0.
## @return       Exit 0 always.
px_compose_index_of() {
  local label=${1:-} number=${2:-}
  case "$label" in
  '' | *[!0-9]*)
    case "$number" in
    '' | *[!0-9]*) PX_COMPOSE_INDEX=0 ;;
    *) PX_COMPOSE_INDEX=$((10#$number - 1)) ;;
    esac
    ;;
  *) PX_COMPOSE_INDEX=$((10#$label)) ;;
  esac
  if [ "$PX_COMPOSE_INDEX" -lt 0 ]; then
    PX_COMPOSE_INDEX=0
  fi
  return 0
}

## @description  Parse a raw inventory LINE into the PX_PS_* variables, including the normalized
##               contract lifecycle and readiness states.
## @return       Exit 0 on success, or exit 1 when LINE is not a valid inventory line.
px_compose_ps_split() {
  local line=${1:-} rest count=0
  rest="$line"
  while [ "$rest" != "${rest#*|}" ]; do
    rest="${rest#*|}"
    count=$((count + 1))
  done
  if [ "$count" -lt 8 ]; then
    return 1
  fi
  # Append three trailing delimiters so that optional trailing fields always have a terminator and
  # parse cleanly as empty strings when omitted.
  line="$line|||"
  PX_PS_ID="${line%%|*}" && line="${line#*|}"
  PX_PS_ROLE="${line%%|*}" && line="${line#*|}"
  PX_PS_NAME="${line%%|*}" && line="${line#*|}"
  PX_PS_STATE="${line%%|*}" && line="${line#*|}"
  PX_PS_HEALTH="${line%%|*}" && line="${line#*|}"
  PX_PS_IMAGE="${line%%|*}" && line="${line#*|}"
  PX_PS_SERVICE="${line%%|*}" && line="${line#*|}"
  PX_PS_NUMBER="${line%%|*}" && line="${line#*|}"
  PX_PS_INDEX="${line%%|*}" && line="${line#*|}"
  PX_PS_GROUP="${line%%|*}" && line="${line#*|}"
  PX_PS_START="${line%%|*}" && line="${line#*|}"
  PX_PS_DRAIN="${line%%|*}"
  [ -n "$PX_PS_ID" ] || return 1
  if [ -z "$PX_PS_ROLE" ]; then
    PX_PS_ROLE="$PX_PS_SERVICE"
  fi
  px_compose_index_of "$PX_PS_INDEX" "$PX_PS_NUMBER"
  PX_PS_INDEX="$PX_COMPOSE_INDEX"
  PX_PS_EXIT=""
  case "$PX_PS_HEALTH" in
  *'Exited ('*)
    PX_PS_EXIT="${PX_PS_HEALTH#*Exited (}"
    PX_PS_EXIT="${PX_PS_EXIT%%)*}"
    ;;
  esac
  PX_PS_RUNTIME_STATE="$PX_PS_STATE"
  case "$PX_PS_STATE" in
  running) PX_PS_STATE=running ;;
  created | restarting) PX_PS_STATE=pending ;;
  paused) PX_PS_STATE=stopped ;;
  exited) PX_PS_STATE=exited ;;
  *) PX_PS_STATE=unknown ;;
  esac
  PX_PS_READY=unready
  case "$PX_PS_HEALTH" in
  *'(healthy)'*) PX_PS_READY=ready ;;
  *'(unhealthy)'* | *'(health: starting)'*) PX_PS_READY=unready ;;
  *)
    # When the status string contains no healthcheck annotation, either the service defines no
    # healthcheck or the container has already exited, leaving the lifecycle state as the sole
    # readiness evidence. A running container is treated as ready, as is a container that exited
    # with status 0: one-shot setup services exit with 0 upon completion, and the readiness barrier
    # must not block waiting for them to run again.
    if [ "$PX_PS_STATE" = running ]; then
      PX_PS_READY=ready
    elif [ "$PX_PS_STATE" = exited ] && [ "$PX_PS_EXIT" = 0 ]; then
      PX_PS_READY=ready
    fi
    ;;
  esac
  return 0
}

## @description  Load the instance identified by SCOPE, ROLE, and INDEX into the PX_PS_* variables.
## @return       Exit 0 when found, exit 6 when the instance is absent, exit 2 on invalid usage, or
##               exit 1 when the runtime does not respond.
px_compose_instance() {
  local scope=${1:-} role=${2:-} index=${3:-} rows line
  if [ -z "$role" ] || ! [[ $index =~ $PX_RE_INT ]]; then
    px_error "an instance needs a role and a whole-number index, got '$role' '$index'"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    if [ "$PX_PS_ROLE" = "$role" ] && [ "$PX_PS_INDEX" = "$index" ]; then
      return 0
    fi
  done <<EOF
$rows
EOF
  px_error "no instance $role/$index in scope $scope"
  return "$SB_EXIT_NO_OBJECT"
}

## @description  Report the one-based Compose container number for the current PX_PS_* instance.
## @return       Write the container number to stdout and exit 0.
px_compose_number() {
  case "${PX_PS_NUMBER:-}" in
  '' | *[!0-9]*) printf '%s\n' $((PX_PS_INDEX + 1)) ;;
  *) printf '%s\n' "$PX_PS_NUMBER" ;;
  esac
}

## @description  Report the instance inventory sorted by role and index, with localhost as the node.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: optional role name filter; defaults to empty to include every role
## @param        index: optional zero-based index filter (requires role); defaults to every index
## @return       Write instance TSV rows to stdout and exit 0, exit 6 if a named role or index does
##               not exist, exit 1 when the runtime does not respond, or exit 2 on invalid usage.
sb_list() {
  local scope=${1:-} role=${2:-} index=${3:-} rows line out="" named=0
  if [ $# -gt 3 ]; then
    px_error "sb_list takes a scope, an optional role and an optional index"
    return "$SB_EXIT_USAGE"
  fi
  if [ -n "$index" ] && ! [[ $index =~ $PX_RE_INT ]]; then
    px_error "an index is a whole number, got '$index'"
    return "$SB_EXIT_USAGE"
  fi
  if [ -n "$index" ] && [ -z "$role" ]; then
    px_error "an index selects one instance of a role, so name the role"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  [ -z "$role" ] || named=1
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    [ -z "$role" ] || [ "$PX_PS_ROLE" = "$role" ] || continue
    [ -z "$index" ] || [ "$PX_PS_INDEX" = "$index" ] || continue
    out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" "$PX_PS_ID" \
      "$PX_PS_STATE" "$PX_PS_READY" "$PX_PS_IMAGE" localhost)$PX_NL"
  done <<EOF
$rows
EOF
  if [ -z "$out" ]; then
    if [ "$named" = 1 ]; then
      px_error "no instance matches role '$role' index '$index' in scope $scope"
      return "$SB_EXIT_NO_OBJECT"
    fi
    return 0
  fi
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
}

# ---------------------------------------------------------------------------
# 6. Runtime Version Checks and Host Preflight Validation
# ---------------------------------------------------------------------------

## @description  Report the container runtime client and server versions and the Compose version.
## @return       Write key-value TSV rows to stdout and exit 0, or exit 1 when the runtime client
##               or server does not respond.
sb_version_info() {
  local client server compose
  client="$(px_compose_engine 30 version --format '{{.Client.Version}}')" || client=""
  if [ -z "$client" ]; then
    px_error "the container runtime did not report its client version"
    return "$SB_EXIT_FAIL"
  fi
  server="$(px_compose_engine 30 version --format '{{.Server.Version}}')" || server=""
  compose="$(px_compose_compose_version)" || compose=""
  px_tsv_row engine_client "$client"
  px_tsv_row engine_server "$server"
  px_tsv_row compose_version "$compose"
  if [ -z "$server" ]; then
    px_error "the container engine is not reachable, only the client answered"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

## @description  Report the active Compose version string with any leading v prefix removed.
## @return       Write the version string to stdout and exit 0, or exit 1 when Compose does not
##               respond.
px_compose_compose_version() {
  local value
  value="$(px_compose_call compose 30 version --short)" || return "$SB_EXIT_FAIL"
  printf '%s\n' "${value#v}"
}

_PXC_PORTS_FROM=""

## @description  Load the ports.generated.env file recorded for SCOPE once per deployment directory.
## @return       Exit 0 (even when no port map exists), or exit 2 on an invalid scope ID or a line
##               rejected by the configuration loader.
px_compose_ports_load() {
  px_compose_scope_load "${1:-}" || return $?
  if [ -z "$PX_COMPOSE_DEPLOY_DIR" ] && [ -z "${PX_PORT_MAP_ENV:-}" ]; then
    return 0
  fi
  if [ "$_PXC_PORTS_FROM" = "$PX_COMPOSE_DEPLOY_DIR|${PX_PORT_MAP_ENV:-}" ]; then
    return 0
  fi
  px_config_load_ports "$PX_COMPOSE_DEPLOY_DIR"
  _PXC_PORTS_FROM="$PX_COMPOSE_DEPLOY_DIR|${PX_PORT_MAP_ENV:-}"
}

# Enumerate all PX_PORT_* variable names in the current environment by scanning env output, because
# Bash 3.2 does not reliably support variable-prefix expansion.
## @description  List all PX_PORT_* variable names currently present in the process environment.
## @return       Write one variable name per line to stdout and exit 0.
px_compose_port_names() {
  local line name
  while IFS= read -r line; do
    name="${line%%=*}"
    case "$name" in
    PX_PORT_*) ;;
    *) continue ;;
    esac
    [[ $name =~ $PX_RE_VARNAME ]] || continue
    printf '%s\n' "$name"
  done <<EOF
$(env)
EOF
}

## @description  Test whether loopback TCP PORT is unused via Bash /dev/tcp redirection.
## @return       Exit 0 when the port is free or non-numeric, or exit 1 when it is already in use.
px_compose_port_free() {
  local port=${1:-}
  case "$port" in
  '' | *[!0-9]*) return 0 ;;
  esac
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    return 1
  fi
  return 0
}

# Evaluates the container engine portion of host preflight validation, checking runtime presence,
# daemon responsiveness, client authorization, engine and Compose version floors, total engine
# memory, engine processor count, and loopback port availability.
## @description  Run the engine preflight checks: engine, versions, memory, processors, and ports.
## @param        scope_id: the scope ID whose generated port map is checked for port availability
## @return       Write check TSV rows to stdout and exit 0 when no check fails, exit 3 when any
##               check fails, or exit 2 on invalid usage or a port-map line rejected by the loader.
sb_preflight() {
  local scope=${1:-} failed=0 server compose memory cpus busy="" names name port status=0
  px_compose_ports_load "$scope" || return $?
  if px_compose_resolve_engine; then
    px_tsv_row engine_binary ok "$_PXC_ENGINE"
  else
    px_tsv_row engine_binary fail "no container runtime, set PX_DOCKER_BIN"
    px_tsv_row engine_responsive fail "no runtime to ask"
    return "$SB_EXIT_ANOMALY"
  fi
  # Terminate evaluation at the first engine fault because no subsequent check can pass without a
  # working engine. Inspecting the error text from the version query distinguishes a refused client
  # from an unresponsive daemon so that each fault condition emits its own dedicated check row.
  server="$(px_compose_call merged 30 version --format '{{.Server.Version}}')" || status=$?
  if [ "$status" != 0 ] || [ -z "$server" ]; then
    server="$(printf '%s\n' "$server" | sed -n '/./{p;q;}')"
    case "$server" in
    *[Pp]ermission\ denied* | *[Uu]nauthori[sz]ed* | *[Aa]ccess\ denied*)
      px_tsv_row engine_responsive warn "not judged, the engine refused the client"
      px_tsv_row engine_credentials fail \
        "the client is not authorised against the engine: $(px_tsv_clean "$server")"
      ;;
    *)
      px_tsv_row engine_responsive fail \
        "the engine did not answer a version request: $(px_tsv_clean "${server:-no text}")"
      ;;
    esac
    return "$SB_EXIT_ANOMALY"
  fi
  server="$(printf '%s\n' "$server" | sed -n '$p')"
  px_tsv_row engine_responsive ok "server $server"
  if sb_version_floor "$server" PX_COMPOSE_ENGINE_FLOOR; then
    px_tsv_row engine_version ok "$server"
  else
    px_tsv_row engine_version fail "engine $server is below the floor $PX_COMPOSE_ENGINE_FLOOR"
    failed=1
  fi
  if px_compose_engine 30 info --format '{{.ID}}' >/dev/null 2>&1; then
    px_tsv_row engine_credentials ok "the client is authorised against the engine"
  else
    px_tsv_row engine_credentials fail "the engine refused an info request"
    failed=1
  fi
  compose="$(px_compose_compose_version)" || compose=""
  if sb_version_floor "$compose" PX_COMPOSE_FLOOR; then
    px_tsv_row compose_version ok "$compose"
  else
    px_tsv_row compose_version fail \
      "compose ${compose:-unknown} is absent or below the floor $PX_COMPOSE_FLOOR"
    failed=1
  fi
  memory="$(px_compose_engine 30 info --format '{{.MemTotal}}')" || memory=""
  case "$memory" in
  '' | *[!0-9]*)
    px_tsv_row engine_memory warn "the engine reported no memory total"
    ;;
  *)
    px_tsv_row engine_memory ok "$memory bytes reported by the engine"
    ;;
  esac
  cpus="$(px_compose_engine 30 info --format '{{.NCPU}}')" || cpus=""
  case "$cpus" in
  '' | *[!0-9]*)
    px_tsv_row engine_cpus warn "the engine reported no processor count"
    ;;
  *)
    px_tsv_row engine_cpus ok "$cpus processors reported by the engine"
    ;;
  esac
  names="$(px_compose_port_names)"
  if [ -z "$names" ]; then
    px_tsv_row port_availability warn \
      "no generated port map is loaded, px-cluster render writes it"
  else
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      port="${!name:-}"
      px_compose_port_free "$port" || busy="$busy $port"
    done <<EOF
$names
EOF
    if [ -n "$busy" ]; then
      px_tsv_row port_availability fail "loopback ports already in use:$busy"
      failed=1
    else
      px_tsv_row port_availability ok "every published port is free on 127.0.0.1"
    fi
  fi
  if [ "$failed" != 0 ]; then
    return "$SB_EXIT_ANOMALY"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 7. Readiness Synchronization Barrier
# ---------------------------------------------------------------------------
# Because the container runtime reports healthcheck status directly, the readiness barrier operates
# by polling that reported health state.
#
# Every readiness wait is bounded by a finite timeout and records the elapsed time until each
# instance becomes ready during the wait rather than the total age of the container. If any watched
# instance terminates with a non-zero exit code while the barrier is waiting, the barrier aborts
# immediately with an anomaly status because continuing to wait for a terminated instance could only
# end in a timeout. A role selection that matches no instances is rejected immediately as an error
# because an empty wait proves nothing.

## @description  Report the role groups recorded as stopped by sb_group_stop in SCOPE.
## @return       Write the space-padded group names to stdout and exit 0, even without a record.
px_compose_stopped_groups() {
  local groups
  groups="$(sb_record_get compose "${1:-}" stopped_groups || true)"
  printf ' %s \n' "$groups"
}

## @description  Add or remove GROUP in the stopped_groups record of SCOPE (SCOPE GROUP add|remove).
## @return       Exit 0 on success, or exit 1 when the scope state record cannot be written.
px_compose_stopped_mark() {
  local scope=${1:-} group=${2:-} action=${3:-} word out=""
  for word in $(px_compose_stopped_groups "$scope"); do
    [ "$word" != "$group" ] || continue
    out="$out $word"
  done
  if [ "$action" = add ]; then
    out="$out $group"
  fi
  sb_record_set compose "$scope" stopped_groups "${out# }"
}

## @description  Report the active role groups that participate in a whole-scope readiness wait
##               given inventory ROWS and recorded STOPPED groups.
## @return       Write the space-padded group names to stdout and exit 0.
px_compose_live_groups() {
  local rows=${1:-} stopped=${2:-} line live=" "
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    [ -n "$PX_PS_GROUP" ] || continue
    case "$PX_PS_RUNTIME_STATE" in
    running | restarting) ;;
    *)
      case "$stopped" in
      *" $PX_PS_GROUP "*) continue ;;
      esac
      if [ "$PX_PS_START" = on-demand ] && [ "$PX_PS_RUNTIME_STATE" = created ]; then
        continue
      fi
      ;;
    esac
    case "$live" in
    *" $PX_PS_GROUP "*) ;;
    *) live="$live$PX_PS_GROUP " ;;
    esac
  done <<EOF
$rows
EOF
  printf '%s\n' "$live"
}

## @description  Test whether a whole-scope readiness wait should skip the current PX_PS_* instance
##               given role SELECTION and live groups UP.
## @return       Exit 0 when SELECTION is empty and the instance's group is not in UP, or exit 1.
px_compose_parked() {
  local selection=${1:-} up=${2:-}
  [ -z "$selection" ] || return 1
  [ -n "$PX_PS_GROUP" ] || return 1
  case "$up" in
  *" $PX_PS_GROUP "*) return 1 ;;
  esac
  return 0
}

## @description  Verify that inventory ROWS for SCOPE contain at least one instance for each role
##               listed in SELECTION.
## @return       Exit 0 when the selection matches, or log an error message and exit 6 otherwise.
px_compose_selection_found() {
  local scope=${1:-} selection=${2:-} rows=${3:-} roles=" " line
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    roles="$roles$PX_PS_ROLE "
  done <<EOF
$rows
EOF
  if [ "$roles" = " " ]; then
    px_error "scope $scope holds no instance that carries its cluster label"
    return "$SB_EXIT_NO_OBJECT"
  fi
  sb_selection_check "$scope" "$roles" "$selection"
}

## @description  Wait for targeted instances to become ready, skipping groups stopped on purpose.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        timeout_s: whole seconds above zero; empty uses PX_TIMEOUT_READY (default 600)
## @param        role...: optional role names to wait for; omitting roles waits for the whole scope
## @return       Write ready TSV rows to stdout and exit 0 when all are ready, 3 if an instance
##               terminated, 4 on timeout, 6 on no match, 1 on no runtime answer, or 2 on usage.
sb_wait_ready() {
  local scope=${1:-} timeout=${2:-} roles="" start deadline interval
  local rows line key seen="" anomaly="" pending out="" elapsed status=0
  local out_elapsed up checked=0
  if [ $# -lt 1 ]; then
    px_error "sb_wait_ready needs a scope and a timeout"
    return "$SB_EXIT_USAGE"
  fi
  shift || true
  shift || true
  roles="$*"
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_READY 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  interval="$(sb_poll_interval)"
  start="$(px_epoch_ms)"
  deadline=$(($(px_epoch_s) + timeout))
  while :; do
    rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
    if [ "$checked" = 0 ]; then
      px_compose_selection_found "$scope" "$roles" "$rows" || return $?
      # Determine which groups are intentionally parked from the persisted scope record and the
      # initial inventory snapshot; all other groups remain in the wait so crashed containers are
      # flagged as anomalies.
      up="$(px_compose_live_groups "$rows" "$(px_compose_stopped_groups "$scope")")"
      checked=1
    fi
    pending=0
    while IFS= read -r line; do
      px_compose_ps_split "$line" || continue
      sb_role_selected "$PX_PS_ROLE" "$roles" || continue
      px_compose_parked "$roles" "$up" && continue
      key="$PX_PS_ROLE/$PX_PS_INDEX"
      if sb_row_get "$key" "$seen" >/dev/null; then
        continue
      fi
      if [ "$PX_PS_READY" = ready ]; then
        seen="$seen$key$PX_TAB$(($(px_epoch_ms) - start))$PX_NL"
        continue
      fi
      if [ "$PX_PS_STATE" = exited ] || [ "$PX_PS_STATE" = unknown ]; then
        if [ "$PX_PS_EXIT" != 0 ]; then
          anomaly="$anomaly $key(${PX_PS_HEALTH:-$PX_PS_STATE})"
        fi
      fi
      pending=$((pending + 1))
    done <<EOF
$rows
EOF
    if [ -n "$anomaly" ]; then
      px_error "an instance terminated while the barrier waited:$(px_tsv_clean "$anomaly")"
      status=$SB_EXIT_ANOMALY
      break
    fi
    if [ "$pending" = 0 ]; then
      break
    fi
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      px_error "the readiness barrier expired after ${timeout}s in scope $scope"
      status=$SB_EXIT_TIMEOUT
      break
    fi
    sleep "$interval"
  done
  elapsed=$(($(px_epoch_ms) - start))
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    sb_role_selected "$PX_PS_ROLE" "$roles" || continue
    key="$PX_PS_ROLE/$PX_PS_INDEX"
    if out_elapsed="$(sb_row_get "$key" "$seen" || exit "$?")"; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" ready "$out_elapsed")$PX_NL"
    elif px_compose_parked "$roles" "$up"; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" stopped "$elapsed")$PX_NL"
    else
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" unready "$elapsed")$PX_NL"
    fi
  done <<EOF
$rows
EOF
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  return "$status"
}

# ---------------------------------------------------------------------------
# 8. Cluster Lifecycle: Provisioning, Teardown, and Horizontal Scaling
# ---------------------------------------------------------------------------
# The rendered deployment directory is the sole integration seam between the deployment generator
# and this backend. As specified in docs/backends.md, the backend reads only compose.yaml, the
# labels declared on its services, ports.generated.env, and services.tsv from that directory, and
# assumes nothing else about its contents. Scaling operations consult services.tsv because each
# instance of a role is rendered as an individual service.
#
# Provisioning first creates every container in the deployment and then starts all services except
# those carrying the start=on-demand label. On-demand services remain created in the stopped state
# until sb_group_start explicitly activates their role group.

## @description  Create every container instance in a deployment and start all non-on-demand roles.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        deployment_dir: rendered deployment directory containing compose.yaml
## @param        timeout_s: whole seconds above zero; empty uses PX_TIMEOUT_PROVISION (default 900)
## @return       Write instance TSV rows and exit 0, exit 1 on creation failure or no runtime
##               answer, 2 on usage or bad directory, 3 if an instance terminated, or 4 on timeout.
sb_provision() {
  local scope=${1:-} dir=${2:-} timeout=${3:-} file rows line name status=0 bad=""
  if [ -z "$dir" ]; then
    px_error "sb_provision needs a scope and a rendered deployment directory"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_PROVISION 900)" || return $?
  px_compose_scope_load "$scope" || return $?
  dir="$(px_realpath "$dir")" || dir=""
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    px_error "no such deployment directory: ${2:-}"
    return "$SB_EXIT_USAGE"
  fi
  file="$dir/compose.yaml"
  if [ ! -f "$file" ]; then
    px_error "deployment directory $dir holds no compose.yaml"
    return "$SB_EXIT_USAGE"
  fi
  sb_record_set compose "$scope" deployment_dir "$dir" || return "$SB_EXIT_FAIL"
  sb_record_set compose "$scope" compose_file "$file" || return "$SB_EXIT_FAIL"
  PX_COMPOSE_DEPLOY_DIR="$dir"
  PX_COMPOSE_FILE="$file"
  px_config_set PX_DEPLOYMENT_DIR "$dir" backend
  px_compose_ports_load "$scope" || return $?
  if sb_dry_run "provision $scope from $file"; then
    return 0
  fi
  px_compose_project "$scope" "$timeout" up --no-start --no-build >&2 || status=$?
  sb_outcome "$status" "$timeout" "provision of $scope" \
    "compose could not create the instances of $scope" || return $?
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  if [ -z "$rows" ]; then
    px_error "the deployment in $dir creates no instance with the label" \
      "$(px_compose_selector "$scope")"
    return "$SB_EXIT_USAGE"
  fi
  set --
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    [ "$PX_PS_START" != on-demand ] || continue
    name="${PX_PS_SERVICE:-$PX_PS_ROLE}"
    case " $* " in
    *" $name "*) continue ;;
    esac
    set -- "$@" "$name"
  done <<EOF
$rows
EOF
  if [ $# -gt 0 ]; then
    px_compose_project "$scope" "$timeout" up -d --no-recreate --no-build "$@" >&2 ||
      status=$?
  fi
  sb_outcome "$status" "$timeout" "provision of $scope" \
    "compose could not start the instances of $scope" || return $?
  rows="$(sb_list "$scope")" || return "$SB_EXIT_FAIL"
  printf '%s\n' "$rows"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    if [ "$PX_PS_STATE" = exited ] && [ "$PX_PS_EXIT" != 0 ]; then
      bad="$bad $PX_PS_ROLE/$PX_PS_INDEX"
    fi
  done <<EOF
$(px_compose_ps "$scope")
EOF
  if [ -n "$bad" ]; then
    px_error "an instance terminated during creation:$bad"
    return "$SB_EXIT_ANOMALY"
  fi
  return 0
}

## @description  Stop and remove every container instance in a scope, plus volumes if keep_data=0.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        keep_data: 0 to remove volumes or 1 to retain them for diagnostics (required)
## @return       Exit 0 when clean, exit 1 if leftovers remain, the runtime does not respond, or
##               the teardown wait expires, or exit 2 on invalid usage.
sb_teardown() {
  local scope=${1:-} keep=${2:-} timeout grace rows volumes status=0
  sb_flag sb_teardown keep_data "$keep" || return $?
  px_compose_scope_load "$scope" || return $?
  if sb_dry_run "tear down $scope with keep_data=$keep"; then
    return 0
  fi
  timeout="$(sb_seconds PX_TIMEOUT_TEARDOWN 300)"
  grace="$(sb_seconds PX_TIMEOUT_STOP 120)"
  if [ "$keep" = 0 ]; then
    px_compose_project "$scope" "$timeout" down --volumes --remove-orphans \
      --timeout "$grace" >&2 || status=$?
  else
    px_compose_project "$scope" "$timeout" down --remove-orphans \
      --timeout "$grace" >&2 || status=$?
  fi
  if sb_expired "$status"; then
    px_error "teardown of $scope expired after ${timeout}s"
    return "$SB_EXIT_FAIL"
  fi
  rows="$(px_compose_ps "$scope")" || {
    px_error "the container runtime did not answer, so the teardown of $scope is not proved"
    return "$SB_EXIT_FAIL"
  }
  if [ -n "$rows" ]; then
    px_error "containers of $scope remain:"
    printf '%s\n' "$rows" >&2
    return "$SB_EXIT_FAIL"
  fi
  if [ "$keep" = 0 ]; then
    volumes="$(px_compose_volumes "$scope")" || return "$SB_EXIT_FAIL"
    if [ -n "$volumes" ]; then
      px_error "volumes of $scope remain: $(px_tsv_clean "$volumes")"
      return "$SB_EXIT_FAIL"
    fi
  fi
  [ "$status" = 0 ] || return "$SB_EXIT_FAIL"
}

# Reconcile the live instances of a role with its target count in the recorded deployment after the
# caller has re-rendered the deployment directory, modifying only the instances added or removed by
# the count change.
## @description  Reconcile a role's instances to the target count in the re-rendered deployment.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: target role name in the rendered deployment
## @param        count: new instance count from 1 to 9, matching the rendered deployment count
## @param        timeout_s: whole seconds above zero; empty uses PX_TIMEOUT_SCALE (default 600)
## @return       Write role instance TSV rows to stdout and exit 0, exit 1 on scale failure or no
##               runtime answer, 2 if count is outside 1..9 or mismatches the render, or 4 timeout.
sb_scale() {
  local scope=${1:-} role=${2:-} count=${3:-} timeout=${4:-} file rows line grace
  local wanted service index start present=" " remove="" volumes volume running=0 status=0
  local -a add=()
  if [ -z "$role" ]; then
    px_error "sb_scale needs a scope, a role and a count"
    return "$SB_EXIT_USAGE"
  fi
  case "$count" in
  [1-9]) ;;
  *)
    px_error "an instance count is 1 to 9, got '$count'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_SCALE 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  file="$PX_COMPOSE_DEPLOY_DIR/services.tsv"
  # The rendered service table enumerates each instance with columns for service, role, index,
  # group, and start policy.
  wanted="$(awk -F '\t' -v r="$role" '!/^#/ && $2 == r { print $3 "\t" $1 "\t" $5 }' \
    "$file" 2>/dev/null)" || wanted=""
  if [ -z "$wanted" ]; then
    px_error "the deployment of $scope renders no instance of role '$role'"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$(printf '%s\n' "$wanted" | grep -c .)" != "$count" ]; then
    px_error "the deployment of $scope renders a different count of $role than $count:" \
      "render it with the new count first"
    return "$SB_EXIT_USAGE"
  fi
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    [ "$PX_PS_ROLE" = "$role" ] || continue
    [ "$PX_PS_STATE" != running ] || running=1
    if [ "$PX_PS_INDEX" -ge "$count" ]; then
      remove="$remove $PX_PS_ID"
    else
      present="$present$PX_PS_INDEX "
    fi
  done <<EOF
$rows
EOF
  start="$(printf '%s\n' "$wanted" | awk -F '\t' 'NR == 1 { print $3 }')"
  while IFS="$PX_TAB" read -r index service _; do
    case "$present" in
    *" $index "*) ;;
    *) add+=("$service") ;;
    esac
  done <<EOF
$wanted
EOF
  if sb_dry_run "scale $role to $count in $scope"; then
    return 0
  fi
  # Reload the generated port map from the updated render so that port bindings for newly added
  # instances are available in the environment.
  px_config_load_ports "$PX_COMPOSE_DEPLOY_DIR"
  _PXC_PORTS_FROM="$PX_COMPOSE_DEPLOY_DIR|${PX_PORT_MAP_ENV:-}"
  # Because scaled-down instances are absent from the updated render, the runtime removes each
  # instance directly by its container ID along with any project volumes it mounts; leaving a
  # volume behind would cause the teardown verification check to fail.
  grace="$(sb_seconds PX_TIMEOUT_STOP 120)"
  for line in $remove; do
    volumes="$(px_compose_engine 60 inspect --format \
      '{{range .Mounts}}{{.Name}} {{end}}' "$line")" || volumes=""
    px_compose_engine "$timeout" stop --time "$grace" "$line" >/dev/null || status=$?
    px_compose_engine "$timeout" rm -f "$line" >/dev/null || status=$?
    for volume in $volumes; do
      case "$volume" in
      "${PX_COMPOSE_PROJECT}_"*)
        px_compose_engine 60 volume rm "$volume" >/dev/null || status=$?
        ;;
      esac
    done
  done
  if [ "$status" != 0 ]; then
    px_error "compose could not remove the instances of $role above $count"
    return "$SB_EXIT_FAIL"
  fi
  if [ "${#add[@]}" -gt 0 ]; then
    # When an on-demand role has no running instances, its role group is intentionally parked, so
    # create the new container instances without starting them.
    if [ "$start" = on-demand ] && [ "$running" = 0 ]; then
      px_compose_project "$scope" "$timeout" up --no-start --no-build --no-deps \
        "${add[@]}" >&2 || status=$?
    else
      px_compose_project "$scope" "$timeout" up -d --no-recreate --no-build --no-deps \
        "${add[@]}" >&2 || status=$?
    fi
    sb_outcome "$status" "$timeout" "scale of $role" \
      "compose could not scale $role to $count" || return $?
    if [ "$start" != on-demand ] || [ "$running" = 1 ]; then
      sb_wait_ready "$scope" "$timeout" "$role" >/dev/null || status=$?
    fi
  fi
  if [ "$status" = "$SB_EXIT_TIMEOUT" ]; then
    sb_list "$scope" "$role"
    px_error "the new instances of $role did not become ready in ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  sb_list "$scope" "$role"
  [ "$status" = 0 ] || return "$SB_EXIT_FAIL"
}

# ---------------------------------------------------------------------------
# 9. In-Container Command Execution, Log Streaming, and File Transfer
# ---------------------------------------------------------------------------
# Workload drivers, health checks, region-mover operations, and in-cluster diagnostic tools all
# interact with cluster instances exclusively through the primitives in this section.
#
# Because Compose commands select a service replica using a one-based `--index` flag, translation
# from the contract's zero-based instance index occurs at the invocation boundary. Conversely,
# because `compose logs` targets a whole service and prepends service name prefixes to every output
# line—whereas the contract requires the raw log stream of a single instance—log retrieval queries
# the underlying container engine directly using the resolved container ID.

## @description  Execute a command inside a single running instance, forwarding stdout verbatim.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        timeout_s: whole seconds above zero; empty uses PX_TIMEOUT_EXEC (default 600)
## @param        --: required literal argument separator before the command
## @param        cmd [arg...]: required command executable and its optional arguments
## @return       Return the command exit status, 124 on timeout expiry, 125 on start or substrate
##               failure, 126 if not executable, 127 if not found, 2 on usage, or 6 if absent.
sb_exec() {
  local scope=${1:-} role=${2:-} index=${3:-} timeout=${4:-} service number status=0
  sb_exec_args "$@" || return $?
  shift 5
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_EXEC 600)" || return $?
  px_compose_instance "$scope" "$role" "$index" || status=$?
  case "$status" in
  0) ;;
  "$SB_EXIT_USAGE" | "$SB_EXIT_NO_OBJECT") return "$status" ;;
  *)
    px_error "the substrate did not answer, so the exec in $role/$index cannot start"
    return "$SB_EXEC_NOSTART"
    ;;
  esac
  # Resolve the container runtime binary in the current shell before invoking exec so that any
  # engine-resolution failure returns SB_EXEC_NOSTART (125), ensuring an exit status of 1 from the
  # exec call below unambiguously reflects the in-container command itself.
  px_compose_resolve_engine || return "$SB_EXEC_NOSTART"
  service="${PX_PS_SERVICE:-$role}"
  number="$(px_compose_number)"
  if [ "$PX_PS_STATE" != running ]; then
    px_error "instance $role/$index is $PX_PS_STATE, so the exec cannot start"
    return "$SB_EXEC_NOSTART"
  fi
  if [ -t 0 ]; then
    # When standard input is attached to a terminal, an interactive operator is present, so allow
    # Compose to retain its default pseudo-TTY allocation.
    px_compose_project "$scope" "$timeout" exec --index="$number" \
      "$service" "$@" || status=$?
  else
    px_compose_project "$scope" "$timeout" exec -T --index="$number" \
      "$service" "$@" || status=$?
  fi
  return "$status"
}

## @description  Stream raw container log bytes for one instance with stderr merged into stdout.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        since_s: lookback window in whole seconds; - or empty (default) reads the full log
## @param        previous: 0 (default) for current or 1 for previous (returns 5 on Compose)
## @param        follow: 0 (default) for one-shot or 1 to stream until closed by the reader
## @return       Write raw log bytes to stdout and exit 0, exit 1 on read failure or no runtime
##               answer, 2 on invalid usage, 5 when previous=1 (unsupported), or 6 if absent.
sb_logs() {
  local scope=${1:-} role=${2:-} index=${3:-} since=${4:--} previous=${5:-0}
  local follow=${6:-0} timeout status=0
  since="$(sb_logs_args "$since" "$previous" "$follow")" || return $?
  if [ "$previous" = 1 ]; then
    px_error "Compose keeps no previous container, so read sb_restart_count" \
      "and sb_termination_reason instead"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  px_compose_instance "$scope" "$role" "$index" || return $?
  # The container runtime emits the container's stderr log stream on its own stderr; because
  # cluster daemons write logs to stderr, the merged surface combines both streams onto stdout. A
  # followed log stream runs until closed by the reader and therefore executes without a timeout
  # (`-`).
  timeout="-"
  if [ "$follow" = 0 ]; then
    timeout="$(sb_seconds PX_TIMEOUT_DEFAULT 300)"
  fi
  if [ "$follow" = 1 ] && [ -n "$since" ]; then
    px_compose_call merged "$timeout" logs --follow --since "${since}s" "$PX_PS_ID" ||
      status=$?
  elif [ "$follow" = 1 ]; then
    px_compose_call merged "$timeout" logs --follow "$PX_PS_ID" || status=$?
  elif [ -n "$since" ]; then
    px_compose_call merged "$timeout" logs --since "${since}s" "$PX_PS_ID" || status=$?
  else
    px_compose_call merged "$timeout" logs "$PX_PS_ID" || status=$?
  fi
  if [ "$status" != 0 ]; then
    px_error "the runtime could not read the log of $role/$index"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

## @description  Copy SRC to DST for the current PX_PS_* instance (SCOPE WHAT SRC DST [PARENT]).
## @return       Exit 0 on success or dry run, or exit 1 when PARENT cannot be created or the copy
##               fails.
px_compose_cp() {
  local parent=${5:-}
  if sb_dry_run "copy $2"; then
    return 0
  fi
  if [ -n "$parent" ] && [ ! -d "$parent" ] && ! mkdir -p "$parent"; then
    px_error "cannot create the destination directory $parent"
    return "$SB_EXIT_FAIL"
  fi
  if ! px_compose_project "$1" "$(sb_seconds PX_TIMEOUT_DEFAULT 300)" cp \
    --index="$(px_compose_number)" "$3" "$4" >&2; then
    px_error "the copy of $2 failed"
    return "$SB_EXIT_FAIL"
  fi
}

## @description  Stage a file or directory from the host filesystem into a container instance.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        local_path: existing source file or directory path on the host
## @param        remote_path: destination filesystem path inside the instance
## @return       Exit 0 on success, exit 1 if the copy fails or the runtime does not respond,
##               exit 2 on invalid usage or a missing local_path, or exit 6 if no instance exists.
sb_copy_in() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-}
  source="$(sb_copy_args in "$source" "$target")" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  px_compose_cp "$scope" "$source into $role/$index at $target" "$source" \
    "${PX_PS_SERVICE:-$role}:$target"
}

## @description  Retrieve an artifact from one instance to a destination allowed by output policy.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        remote_path: source path inside the container instance
## @param        local_path: destination path relative to or under PX_OUT, outside the working tree
## @return       Exit 0 on success, exit 1 on copy failure, no runtime answer, or unresolved path,
##               exit 2 on invalid usage or a refused destination, or exit 6 if no instance exists.
sb_copy_out() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-} resolved
  resolved="$(sb_copy_args out "$source" "$target")" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  px_compose_cp "$scope" "$source out of $role/$index to $resolved" \
    "${PX_PS_SERVICE:-$role}:$source" "$resolved" "${resolved%/*}"
}

# ---------------------------------------------------------------------------
# 10. Role Groups: On-Demand Subsystem Switching
# ---------------------------------------------------------------------------
# Role groups are defined via container labels assigned by the deployment generator. Group
# lifecycle operations exist primarily to manage optional heavy tiers such as YARN on
# memory-constrained engines: the generator renders the tier into the deployment, and the harness
# starts it only for test steps that require it and stops it afterward to reclaim engine memory
# mid-run.
#
# Stopping and starting a group uses Compose `stop` and `start` rather than `down` or `rm` so that
# containers and their attached volumes remain intact across transitions, allowing a group to
# resume later in the same run with its state preserved. Because sb_provision pre-creates
# `on-demand` group containers in the stopped state, their initial activation also uses `start`.

_PXC_GROUP_SERVICES=()

## @description  List raw pipe-delimited inventory lines for all containers in GROUP within SCOPE.
## @return       Write one raw inventory line per container to stdout and exit 0, exit 2 on invalid
##               usage, or exit 1 when the runtime does not respond.
px_compose_group_rows() {
  local scope=${1:-} group=${2:-} prefix
  prefix="$(sb_label_prefix)"
  px_compose_ps "$scope" --filter "label=$prefix.group=$group"
}

## @description  List the distinct sorted services or roles in GROUP (SCOPE GROUP [service|role]).
## @return       Write one sorted name per line to stdout and exit 0, exit 2 on invalid usage, or
##               exit 1 when the container runtime does not respond.
px_compose_group_names() {
  local field=${3:-service} rows line
  rows="$(px_compose_group_rows "${1:-}" "${2:-}")" || return $?
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    if [ "$field" = role ]; then
      printf '%s\n' "$PX_PS_ROLE"
    else
      printf '%s\n' "${PX_PS_SERVICE:-$PX_PS_ROLE}"
    fi
  done <<EOF | sed '/^$/d' | LC_ALL=C sort -u
$rows
EOF
}

## @description  Load the Compose services of GROUP into _PXC_GROUP_SERVICES (FUNCTION SCOPE GROUP).
## @return       Exit 0 on success, exit 2 on an empty group, invalid scope, or group with no
##               matching instances, or exit 1 when the runtime does not respond.
px_compose_group_load() {
  local services
  if [ -z "${3:-}" ]; then
    px_error "$1 needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "${2:-}" || return $?
  services="$(px_compose_group_names "$2" "$3" service)" || return "$SB_EXIT_FAIL"
  if [ -z "$services" ]; then
    px_error "no instance in scope $2 carries the group label '$3'"
    return "$SB_EXIT_USAGE"
  fi
  IFS="$PX_NL" read -r -d '' -a _PXC_GROUP_SERVICES <<EOF || true
$services
EOF
}

## @description  Stop every instance of a role group while preserving container state for restart.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        group: required role group label value to stop
## @param        timeout_s: whole seconds above zero; empty uses PX_TIMEOUT_GROUP (default 600)
## @return       Write stopped TSV rows to stdout and exit 0, exit 1 if an instance still runs or
##               the runtime does not respond, 2 on usage or unknown group, or 4 on timeout.
sb_group_stop() {
  local scope=${1:-} group=${2:-} timeout=${3:-} rows line grace before="" out=""
  local still=0 state status=0
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  px_compose_group_load sb_group_stop "$scope" "$group" || return $?
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    before="$before$PX_PS_ROLE/$PX_PS_INDEX$PX_TAB$PX_PS_STATE$PX_NL"
  done <<EOF
$rows
EOF
  if sb_dry_run "stop the group $group in $scope"; then
    return 0
  fi
  grace="$(sb_seconds PX_TIMEOUT_STOP 120)"
  px_compose_stopped_mark "$scope" "$group" add || return "$SB_EXIT_FAIL"
  px_compose_project "$scope" "$timeout" stop --timeout "$grace" \
    "${_PXC_GROUP_SERVICES[@]}" >&2 || status=$?
  if sb_expired "$status"; then
    px_error "the stop of group $group expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    state="$(sb_row_get "$PX_PS_ROLE/$PX_PS_INDEX" "$before")" || state=""
    if [ "$PX_PS_STATE" = running ]; then
      still=$((still + 1))
      continue
    fi
    if [ "$state" = running ]; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" stopped)$PX_NL"
    else
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" already_stopped)$PX_NL"
    fi
  done <<EOF
$rows
EOF
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  if [ "$still" != 0 ]; then
    px_error "$still instance of group $group still runs"
    return "$SB_EXIT_FAIL"
  fi
  if [ "$status" != 0 ]; then
    px_error "compose reported status $status for the stop of group $group"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

## @description  Start a stopped role group and wait for its instances to reach readiness.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        group: required role group label value to start
## @param        timeout_s: whole seconds > 0 for start and wait; empty takes PX_TIMEOUT_GROUP (600)
## @return       Write ready TSV rows and exit 0, exit 1 on start failure, no runtime answer, or no
##               role, 2 on usage or unknown group, 4 if start expires, or the sb_wait_ready status.
sb_group_start() {
  local scope=${1:-} group=${2:-} timeout=${3:-} roles status=0
  local -a names
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  px_compose_group_load sb_group_start "$scope" "$group" || return $?
  # An empty resolved role list would widen sb_wait_ready to the entire scope, so treat a missing
  # role mapping as an explicit error rather than falling back to a scope-wide wait.
  roles="$(px_compose_group_names "$scope" "$group" role)" || return "$SB_EXIT_FAIL"
  if [ -z "$roles" ]; then
    px_error "the group $group in scope $scope names no role to wait for"
    return "$SB_EXIT_FAIL"
  fi
  if sb_dry_run "start the group $group in $scope"; then
    return 0
  fi
  px_compose_stopped_mark "$scope" "$group" remove || return "$SB_EXIT_FAIL"
  px_compose_project "$scope" "$timeout" start "${_PXC_GROUP_SERVICES[@]}" >&2 || status=$?
  sb_outcome "$status" "$timeout" "the start of group $group" \
    "compose could not start the group $group" || return $?
  IFS="$PX_NL" read -r -d '' -a names <<EOF || true
$roles
EOF
  sb_wait_ready "$scope" "$timeout" "${names[@]}"
}

## @description  Report the runtime status of a role group so test steps can skip stopped groups.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        group: required role group label value to inspect
## @return       Write one group TSV row to stdout and exit 0, exit 1 when the runtime does not
##               respond, or exit 2 on invalid usage or an unknown group.
sb_group_status() {
  local scope=${1:-} group=${2:-} rows line running=0 desired=0 state
  if [ -z "$group" ]; then
    px_error "sb_group_status needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    desired=$((desired + 1))
    if [ "$PX_PS_STATE" = running ]; then
      running=$((running + 1))
    fi
  done <<EOF
$rows
EOF
  if [ "$desired" = 0 ]; then
    px_error "no instance in scope $scope carries the group label '$group'"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$running" = 0 ]; then
    state=down
  elif [ "$running" = "$desired" ]; then
    state=up
  else
    state=partial
  fi
  px_tsv_row "$group" "$running" "$desired" "$state"
}

# ---------------------------------------------------------------------------
# 11. Fault Injection and Drain-Aware Rolling Restart
# ---------------------------------------------------------------------------
# Fault injection terminates instances abruptly by design. A hard kill leaves the container object
# in place so that its restart count and termination reason remain inspectable afterward, whereas a
# delete removes the container object to exercise the backend's recreation semantics. Callers must
# record injected faults as planned actions because post-run cluster checks treat any unexplained
# restart as a fatal anomaly.

## @description  Terminate a single container instance abruptly via hard kill or object deletion.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        mode: hard to kill and retain the instance object, or delete to remove the object
## @return       Exit 0 on success, exit 1 on termination failure or no runtime answer, exit 2 on
##               invalid usage or an unknown mode, or exit 6 if no such instance exists.
sb_kill() {
  local scope=${1:-} role=${2:-} index=${3:-} mode=${4:-} timeout status=0
  sb_value_arg sb_kill "a mode" kill_mode "$mode" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  if sb_dry_run "$mode kill of $role/$index in $scope"; then
    return 0
  fi
  timeout="$(sb_seconds PX_TIMEOUT_STOP 120)"
  if [ "$mode" = hard ]; then
    px_compose_engine "$timeout" kill -s KILL "$PX_PS_ID" >/dev/null || status=$?
  else
    px_compose_engine "$timeout" rm -f "$PX_PS_ID" >/dev/null || status=$?
  fi
  if [ "$status" != 0 ]; then
    px_error "the $mode kill of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Deliver a named signal to a single instance, such as QUIT to trigger a thread dump. KILL and TERM
# are sent to the container instance and terminate it, whereas every other signal is delivered to
# its daemon JVM process, where STOP or TSTP must stop the JVM and CONT must resume it; exit 6 is
# returned when either the instance or its JVM is absent.
## @description  Deliver a named signal to an instance (KILL or TERM) or to its daemon JVM.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        signal_name: HUP, INT, QUIT, ABRT, KILL, ALRM, TERM, USR1, USR2, STOP, CONT, or
##               TSTP (case-insensitive, with or without the SIG prefix)
## @return       Exit 0, 1 if delivery fails, STOP/TSTP fails to stop, CONT fails to resume, or no
##               runtime answers, 2 on usage or unknown signal, or 6 if the instance/JVM is absent.
sb_signal() {
  local scope=${1:-} role=${2:-} index=${3:-} name timeout status=0
  name="$(sb_signal_name "${4:-}")" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  if sb_dry_run "signal $name to $role/$index in $scope"; then
    return 0
  fi
  timeout="$(sb_seconds PX_TIMEOUT_STOP 120)"
  case "$name" in
  KILL | TERM)
    px_compose_engine "$timeout" kill -s "$name" "$PX_PS_ID" >/dev/null || status=$?
    ;;
  *)
    sb_signal_jvm "$scope" "$role" "$index" "$timeout" "$name" || return $?
    ;;
  esac
  if [ "$status" != 0 ]; then
    px_error "the signal $name to $role/$index was not delivered"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Perform a drain-aware restart of a single regionmover instance; an already exited instance skips
# the drain, stop, and rebalance phases, and a missing instance exits with status 6.
## @description  Restart one regionmover instance, skipping drain, stop, and rebalance if exited.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: target role name carrying the drain=regionmover label
## @param        index: the zero-based instance index
## @param        drain_timeout_s: whole seconds above zero for the drain, stop, and rebalance
##               phases; empty uses PX_TIMEOUT_DRAIN (default 600)
## @param        ready_timeout_s: whole seconds above zero for the start and readiness wait; empty
##               uses PX_TIMEOUT_READY (default 600)
## @return       Emit phase TSV rows and exit 0 if no phase fails, 1 on phase failure or no runtime
##               answer, 2 on usage, no drain label, or no rpc port, 4 on phase expiry, 6 if absent.
sb_restart_drain() {
  local scope=${1:-} role=${2:-} index=${3:-} drain=${4:-} ready=${5:-}
  local host port file cmd container grace start status=0 exited=0
  local deadline interval
  drain="$(sb_timeout_arg "$drain" PX_TIMEOUT_DRAIN 600)" || return $?
  ready="$(sb_timeout_arg "$ready" PX_TIMEOUT_READY 600)" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  if [ "$PX_PS_DRAIN" != regionmover ]; then
    px_error "role '$role' has no drain semantics: the deployment gives it no" \
      "$(sb_label_prefix).drain=regionmover label"
    return "$SB_EXIT_USAGE"
  fi
  container="$PX_PS_ID"
  file="/tmp/px-regions-$role-$index"
  px_compose_ports_load "$scope" || return $?
  port="$(px_compose_port_value "$role" rpc "$index")" || port=""
  host="$(px_compose_engine 30 inspect --format '{{.Config.Hostname}}' \
    "$container")" || host=""
  if [ -z "$host" ]; then
    host="$PX_PS_NAME"
  fi
  cmd="$(sb_mover_cmd unload "$host" "$port" "$drain" "$file")" || cmd=""
  if [ -z "$cmd" ]; then
    px_error "the port map holds no rpc port for $role/$index, so the mover cannot name it"
    return "$SB_EXIT_USAGE"
  fi
  if sb_dry_run "drain-aware restart of $role/$index in $scope"; then
    return 0
  fi
  grace="$(sb_seconds PX_TIMEOUT_STOP 120)"

  # An exited instance has no running process, regardless of whether it terminated due to a kill,
  # an OOM condition, or a crash. Because it cannot drain regions, has no running process to stop,
  # and produces no region list during this restart to reload later, those three phases are marked
  # as skip.
  start="$(px_epoch_ms)"
  if [ "$PX_PS_STATE" = exited ]; then
    exited=1
    sb_phase_row drain skip "$start"
    sb_phase_row stop skip "$start"
  else
    sb_mover_phase drain "$start" "$role/$index" "$drain" "$cmd" "$scope" "$role" "$index" ||
      return $?

    start="$(px_epoch_ms)"
    px_compose_engine "$drain" stop --time "$grace" "$container" >/dev/null || status=$?
    if [ "$status" != 0 ]; then
      sb_phase_fail stop "$start"
      px_error "the stop of $role/$index failed"
      return "$SB_EXIT_FAIL"
    fi
    sb_phase_row stop ok "$start"
  fi

  start="$(px_epoch_ms)"
  status=0
  px_compose_engine "$ready" start "$container" >/dev/null || status=$?
  if [ "$status" != 0 ]; then
    sb_phase_fail start "$start"
    px_error "the start of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  sb_phase_row start ok "$start"

  # Scope the readiness wait strictly to the instance being restarted; another instance of the same
  # role may be exited, which is not a failure of this restart operation.
  start="$(px_epoch_ms)"
  deadline=$(($(px_epoch_s) + ready))
  interval="$(sb_poll_interval)"
  while :; do
    status=0
    px_compose_instance "$scope" "$role" "$index" || status=$?
    if [ "$status" != 0 ] || [ "$PX_PS_STATE" = exited ]; then
      sb_phase_fail ready "$start"
      px_error "$role/$index stopped while the restart waited for it to become ready"
      return "$SB_EXIT_FAIL"
    fi
    [ "$PX_PS_READY" != ready ] || break
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      sb_phase_fail ready "$start"
      px_error "$role/$index did not become ready in ${ready}s"
      return "$SB_EXIT_TIMEOUT"
    fi
    sleep "$interval"
  done
  sb_phase_row ready ok "$start"

  start="$(px_epoch_ms)"
  if [ "$exited" = 1 ]; then
    # Because this restart wrote no region list, any list left behind by an earlier drain of the
    # instance must not be reloaded to move regions back, ensuring that the outcome of an instance
    # rejoin never depends on earlier restarts.
    sb_phase_row rebalance skip "$start"
    return 0
  fi
  cmd="$(sb_mover_cmd load "$host" "$port" "$drain" "$file")"
  sb_mover_phase rebalance "$start" "$role/$index" "$drain" "$cmd" "$scope" "$role" "$index"
}

# ---------------------------------------------------------------------------
# 12. Diagnostic Evidence: Restarts, Termination, OOM, Events, and Resource Samples
# ---------------------------------------------------------------------------
# Because a test run fails immediately upon detecting an unplanned container restart or an
# out-of-memory kill, diagnostic evidence must be unambiguous. Container inspection answers restart
# count, termination reason, and OOM status directly for the current container instance.
#
# Termination reasons are normalized into a backend-independent vocabulary so cluster health checks
# can evaluate a single canonical status across all substrates.

## @description  Query one or more inspection fields for CONTAINER in a single call using TEMPLATE.
## @return       Write the rendered template and exit 0, or exit 1 when the runtime does not
##               respond (never 6, because the caller verified the instance beforehand).
px_compose_inspect() {
  if ! px_compose_engine 30 inspect --format "${2:-}" "${1:-}"; then
    px_error "the runtime did not answer an inspection of ${1:-}"
    return "$SB_EXIT_FAIL"
  fi
}

# Queries the cumulative container restart count for every instance in the scope by combining one
# container inventory listing with one batch inspection across all containers, while maintaining the
# scope's container start table.
## @description  Report the restart count for each instance of a scope and update its start table.
## @param        scope_id: the scope ID returned by sb_scope_create
## @return       Write one restart TSV row per instance to stdout and exit 0, exit 1 if the runtime
##               does not respond or the start table cannot be written, or exit 2 on invalid usage.
sb_restart_count() {
  local scope=${1:-} rows line names="" counts name count id cid started
  local path tmp seen="" table="" row extra
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  set --
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    names="$names$PX_PS_NAME$PX_TAB$PX_PS_ROLE/$PX_PS_INDEX$PX_NL"
    set -- "$@" "$PX_PS_ID"
  done <<EOF
$rows
EOF
  [ $# -gt 0 ] || return 0
  if ! counts="$(px_compose_engine 30 inspect \
    --format '{{.Name}}|{{.RestartCount}}|{{.Id}}|{{.State.StartedAt}}' "$@")"; then
    px_error "the runtime did not answer an inspection of scope $scope"
    return "$SB_EXIT_FAIL"
  fi
  # The container runtime counts only restarts triggered by a restart policy, whereas every
  # service in the deployment uses a policy of no. Consequently, an external restart outside the
  # harness, such as "docker restart", leaves that runtime count at 0 and the container state as
  # running. To detect these events, the start table records the most recent start time of each
  # container and adds 1 to the count whenever that timestamp changes between two calls.
  path="$(sb_record_path compose starts "$scope")" || return "$SB_EXIT_FAIL"
  [ ! -f "$path" ] || seen="$(cat "$path")"
  # Container inspection prefixes each reported container name with a leading slash.
  while IFS='|' read -r name count cid started; do
    [ -n "$name" ] || continue
    id="$(sb_row_get "${name#/}" "$names")" || continue
    case "$count" in
    '' | *[!0-9]*) count=0 ;;
    esac
    [ -n "$started" ] || started=none
    extra=0
    # A newly observed container will naturally be absent from the existing start table; exiting
    # explicitly inside the command substitution prevents a caller's ERR failure trap from firing
    # on that expected lookup miss.
    if row="$(sb_row_get "$cid" "$seen" || exit "$?")"; then
      extra=${row#*"$PX_TAB"}
      [ "${row%%"$PX_TAB"*}" = "$started" ] || extra=$((extra + 1))
    fi
    table="$table$(px_tsv_row "$cid" "$started" "$extra")$PX_NL"
    px_tsv_row "${id%%/*}" "${id#*/}" "$((count + extra))"
  done <<EOF
$counts
EOF
  # Atomically renaming the temporary file replaces the start table in a single step, ensuring that
  # a concurrent reader never observes a partially written table.
  tmp="$path.$$.$RANDOM"
  if ! printf '%s' "$table" >"$tmp" || ! mv -f "$tmp" "$path"; then
    rm -f "$tmp"
    px_error "cannot write the start table $path"
    return "$SB_EXIT_FAIL"
  fi
}

## @description  Report normalized termination evidence for an instance in the shared vocabulary.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        which: current (default) or previous (returns exit code 5 on Docker Compose)
## @return       Write one term TSV row to stdout and exit 0, exit 1 when the runtime does not
##               respond, 2 on invalid usage, 5 when previous is unsupported, or 6 if absent.
sb_termination_reason() {
  local which=${4:-current} fields state code oom finished reason signal=""
  sb_value_arg sb_termination_reason which term_which "$which" || return $?
  if [ "$which" = previous ]; then
    px_error "Compose keeps no previous container, so read sb_restart_count instead"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  fields="$(px_compose_inspect "$PX_PS_ID" \
    '{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.FinishedAt}}')" ||
    return $?
  IFS='|' read -r state code oom finished <<<"$fields"
  case "$code" in
  '' | *[!0-9]*) code=0 ;;
  esac
  if [ "$code" -gt 128 ] && [ "$code" -lt 256 ]; then
    signal=$((code - 128))
  fi
  case "$state" in
  running | restarting | paused) reason=running ;;
  created) reason=unknown ;;
  exited)
    if [ "$oom" = true ]; then
      reason=oom
    elif [ "$code" = 0 ]; then
      reason=completed
    elif [ -n "$signal" ]; then
      reason=killed
    else
      reason=error
    fi
    ;;
  dead) reason=error ;;
  *) reason=unknown ;;
  esac
  px_tsv_row "$reason" "$code" "$signal" "$(px_tsv_clean "$finished")"
}

## @description  Report whether an instance was OOM-killed based on State.OOMKilled and exit code.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @return       Write one oom TSV row to stdout and exit 0, exit 1 when the runtime does not
##               respond, exit 2 on invalid usage, or exit 6 if no such instance exists.
sb_oom_killed() {
  local fields oom code error evidence
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  fields="$(px_compose_inspect "$PX_PS_ID" \
    '{{.State.OOMKilled}}|{{.State.ExitCode}}|{{.State.Error}}')" || return $?
  IFS='|' read -r oom code error <<<"$fields"
  case "$code" in
  '' | *[!0-9]*) code=0 ;;
  esac
  if [ "$oom" = true ]; then
    evidence="State.OOMKilled is true, exit code $code"
    if [ -n "$error" ]; then
      evidence="$evidence, $error"
    fi
    px_tsv_row yes "$(px_tsv_clean "$evidence")"
    return 0
  fi
  evidence="State.OOMKilled is false, exit code $code"
  px_tsv_row no "$(px_tsv_clean "$evidence")"
}

## @description  Convert a binary or decimal human-readable size (such as 1.5GiB or 1.2kB) into an
##               integer byte count.
## @return       Write the whole number of bytes to stdout and exit 0.
px_compose_bytes() {
  local text=${1:-} number unit whole frac scale=1
  text="${text// /}"
  number="${text%%[!0-9.]*}"
  unit="${text#"$number"}"
  case "$number" in
  '' | .)
    printf '0\n'
    return 0
    ;;
  esac
  case "$unit" in
  B | '') scale=1 ;;
  [kK]B) scale=1000 ;;
  [kK]iB) scale=1024 ;;
  MB) scale=1000000 ;;
  MiB) scale=1048576 ;;
  GB) scale=1000000000 ;;
  GiB) scale=1073741824 ;;
  TB) scale=1000000000000 ;;
  TiB) scale=1099511627776 ;;
  *) scale=1 ;;
  esac
  whole="${number%%.*}"
  frac="${number#*.}"
  if [ "$frac" = "$number" ]; then
    frac=0
  fi
  frac="${frac}000"
  frac="${frac:0:3}"
  case "$whole" in
  '' | *[!0-9]*) whole=0 ;;
  esac
  case "$frac" in
  *[!0-9]*) frac=0 ;;
  esac
  printf '%s\n' "$(((10#$whole * 1000 + 10#$frac) * scale / 1000))"
}

## @description  Report a resource snapshot of every running instance from a single stats call.
## @param        scope_id: the scope ID returned by sb_scope_create
## @return       Write sample TSV rows to stdout and exit 0, exit 2 on invalid usage, or exit 5
##               when the runtime does not respond or refuses the stats pass.
sb_sample_resources() {
  local scope=${1:-} rows line names="" limits stats ts status=0
  local name cpu mem net blk used limit rx tx blk_read blk_write id
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope" --filter status=running)" ||
    return "$SB_EXIT_UNSUPPORTED"
  if [ -z "$rows" ]; then
    return 0
  fi
  set --
  while IFS= read -r line; do
    px_compose_ps_split "$line" || continue
    names="$names$PX_PS_NAME$PX_TAB$PX_PS_ROLE/$PX_PS_INDEX$PX_NL"
    set -- "$@" "$PX_PS_ID"
  done <<EOF
$rows
EOF
  if [ $# = 0 ]; then
    return 0
  fi
  stats="$(px_compose_engine 60 stats --no-stream \
    --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}' "$@")" ||
    status=$?
  if [ "$status" != 0 ]; then
    px_error "the runtime refused a stats pass, so there is no metrics source"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  # Container inspection prefixes each container name with a leading slash; stripping slashes and
  # replacing vertical bars with tabs formats the output directly as key-value rows for sb_row_get.
  limits="$(px_compose_engine 60 inspect \
    --format '{{.Name}}|{{.HostConfig.Memory}}' "$@")" || limits=""
  limits=${limits//\//}
  limits=${limits//|/$PX_TAB}
  ts="$(px_epoch_ms)"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    IFS='|' read -r name cpu mem net blk <<<"$line"
    used="$(px_compose_bytes "${mem%%/*}")"
    limit="$(sb_row_get "${name#/}" "$limits")" || limit=0
    case "$limit" in
    '' | 0 | *[!0-9]*) limit="$(px_compose_bytes "${mem##*/}")" ;;
    esac
    rx="$(px_compose_bytes "${net%%/*}")"
    tx="$(px_compose_bytes "${net##*/}")"
    blk_read="$(px_compose_bytes "${blk%%/*}")"
    blk_write="$(px_compose_bytes "${blk##*/}")"
    id="$(sb_row_get "${name#/}" "$names")" || id=""
    px_tsv_row "$ts" "${id%%/*}" "${id#*/}" "${cpu%\%}" \
      "$used" "$limit" "$rx" "$tx" "$blk_read" "$blk_write"
  done <<EOF
$stats
EOF
  return 0
}

# ---------------------------------------------------------------------------
# 13. Service Endpoints and Container Image References
# ---------------------------------------------------------------------------
# Because Docker Compose publishes host ports when creating the deployment, endpoint resolution does
# not establish a separate port-forwarding process and endpoint release has no resource to free.
# Published ports are read directly from the generated port map—which assigns each zero-based
# instance index a base port plus a fixed stride—so this backend never computes port arithmetic of
# its own and port assignments have a single source of truth.

## @description  Resolve the published port for (ROLE PORT_NAME INDEX) from the loaded port map.
## @return       Write the port number to stdout (checking the indexed variable name first) and
##               exit 0, or exit 6 when the port map defines no matching port.
px_compose_port_value() {
  local name value
  name="$(sb_port_var "${1:-}" "${2:-}" "${3:-}")"
  value="${!name:-}"
  if [ -z "$value" ]; then
    name="$(sb_port_var "${1:-}" "${2:-}")"
    value="${!name:-}"
  fi
  case "$value" in
  '' | *[!0-9]*) return "$SB_EXIT_NO_OBJECT" ;;
  esac
  printf '%s\n' "$value"
}

## @description  Report the host loopback address and mapped port for a named instance service port.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        port_name: logical port name in the generated port map (for example, rpc)
## @return       Write one endpoint TSV row to stdout and exit 0, exit 1 when the runtime does not
##               respond, 2 on usage or a rejected port-map line, or 6 for an unknown instance/port.
sb_endpoint() {
  local scope=${1:-} role=${2:-} index=${3:-} port=${4:-} value
  if [ -z "$port" ]; then
    px_error "sb_endpoint needs a scope, a role, an index and a port name"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_instance "$scope" "$role" "$index" || return $?
  px_compose_ports_load "$scope" || return $?
  if ! value="$(px_compose_port_value "$role" "$port" "$index")"; then
    if [ -z "$(px_compose_port_names)" ]; then
      px_error "no generated port map is loaded, so there is no port for" \
        "$role/$index $port. px-cluster render writes the map."
    else
      px_error "the generated port map holds no port for $role/$index $port"
    fi
    return "$SB_EXIT_NO_OBJECT"
  fi
  px_tsv_row 127.0.0.1 "$value"
}

## @description  Release a discovered service endpoint; a no-op because the deployment publishes it.
## @param        scope_id: a valid scope ID of this backend
## @param        role: the target role name (unused by the Compose backend)
## @param        index: the zero-based instance index (unused by the Compose backend)
## @param        port_name: required logical port name to release, or all
## @return       Exit 0 for a valid scope ID of this backend, or exit 2 on invalid usage.
sb_endpoint_release() {
  local scope=${1:-} port=${4:-}
  if [ -z "$port" ]; then
    px_error "sb_endpoint_release needs a scope, a role, an index and a port name or all"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  px_debug "nothing to release: the deployment publishes the port of $scope"
  return 0
}

## @description  Report the configured image reference and local image ID for a container instance.
## @param        scope_id: the scope ID returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @return       Write one image TSV row to stdout and exit 0, exit 1 when the runtime does not
##               respond, exit 2 on invalid usage, or exit 6 if no such instance exists.
sb_image_ref() {
  local fields reference identifier
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  fields="$(px_compose_inspect "$PX_PS_ID" '{{.Config.Image}}|{{.Image}}')" ||
    return $?
  reference="${fields%%|*}"
  identifier="${fields#*|}"
  if [ -z "$reference" ]; then
    reference="$PX_PS_IMAGE"
  fi
  px_tsv_row "$reference" "$identifier"
}

# Build a local container image from a staged build context without pushing it to a registry,
# routing all build output to stderr.
## @description  Build a local container image from a staged context (never pushes; logs to stderr).
## @param        context_dir: staged build context directory containing the Dockerfile
## @param        tag: local repository:tag reference with no registry host and no digest
## @param        NAME=VALUE...: zero or more optional build arguments
## @return       Write one image TSV row (tag, image ID) and exit 0, exit 1 if the build fails or
##               yields no ID, 2 on bad context, tag, or arg, or 4 if PX_TIMEOUT_BUILD expires.
sb_image_build() {
  local ctx=${1:-} tag=${2:-} arg timeout status=0 id help
  local args=()
  if [ $# -lt 2 ] || [ -z "$ctx" ] || [ -z "$tag" ]; then
    px_error "sb_image_build needs a context directory and a tag"
    return "$SB_EXIT_USAGE"
  fi
  shift 2
  if [ ! -d "$ctx" ] || [ ! -f "$ctx/Dockerfile" ]; then
    px_error "the build context $ctx holds no Dockerfile"
    return "$SB_EXIT_USAGE"
  fi
  if ! sb_image_tag_local "$tag"; then
    px_error "the image tag '$tag' is not a local repository:tag"
    return "$SB_EXIT_USAGE"
  fi
  for arg in "$@"; do
    if ! [[ $arg =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      px_error "a build argument is NAME=VALUE, got '$arg'"
      return "$SB_EXIT_USAGE"
    fi
    args+=(--build-arg "$arg")
  done
  timeout="$(sb_seconds PX_TIMEOUT_BUILD 3600)"
  if sb_dry_run "build of $tag from $ctx"; then
    return 0
  fi
  # Attaching a provenance attestation causes the containerd image store to assign a new image ID
  # on every build of identical content, so disable provenance when the builder supports the option
  # to keep image IDs in cluster/images.tsv comparable across builds.
  help="$(px_compose_engine 30 build --help 2>/dev/null || true)"
  case "$help" in
  *--provenance*) args+=(--provenance=false) ;;
  esac
  px_compose_engine "$timeout" build --tag "$tag" ${args[@]+"${args[@]}"} \
    --file "$ctx/Dockerfile" "$ctx" >&2 || status=$?
  sb_outcome "$status" "$timeout" "the build of $tag" \
    "the engine could not build $tag from $ctx" || return $?
  id="$(px_compose_engine 60 image inspect --format '{{.Id}}' "$tag")" || id=""
  if [ -z "$id" ]; then
    px_error "the engine reports no image id for $tag after the build"
    return "$SB_EXIT_FAIL"
  fi
  px_tsv_row "$tag" "$id"
}

# Inspect every image referenced in a deployment's compose.yaml and check whether the local engine
# holds it. Cluster bring-up queries image presence before creating the scope because pull_policy
# never would otherwise cause a missing image to fail only after the scope already exists.
## @description  Report each image referenced in compose.yaml and whether the local engine holds it.
## @param        deployment_dir: rendered deployment directory containing compose.yaml
## @return       Write one image TSV row per image (with an empty ID when absent) and exit 0 if all
##               exist, 1 on no runtime answer, 2 on usage or missing compose.yaml, or 6 if missing.
sb_image_present() {
  local dir=${1:-} ref id missing=0
  if [ -z "$dir" ] || [ ! -f "$dir/compose.yaml" ]; then
    px_error "sb_image_present needs a deployment directory that holds compose.yaml"
    return "$SB_EXIT_USAGE"
  fi
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    id="$(px_compose_engine 60 image ls --quiet --no-trunc "$ref")" || {
      px_error "the engine did not answer the image query for $ref"
      return "$SB_EXIT_FAIL"
    }
    id="${id%%"$PX_NL"*}"
    [ -n "$id" ] || missing=1
    px_tsv_row "$ref" "$id"
  done <<LIST
$(sed -n 's/^[[:space:]]*image:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' \
    "$dir/compose.yaml" | LC_ALL=C sort -u)
LIST
  [ "$missing" = 0 ] || return "$SB_EXIT_NO_OBJECT"
}

# Query the remote registry for the architecture-specific manifest digest of a base image so the
# image build can record it in its trust-on-first-use provenance log.
## @description  Resolve the registry manifest digest of a base image for a target architecture.
## @param        ref: base image reference without a digest (e.g., eclipse-temurin:17-jdk-noble)
## @param        arch: target CPU architecture (arm64 or amd64)
## @return       Write one image TSV row (ref and sha256:<hex>) and exit 0, exit 1 if the registry
##               or runtime returns no digest for the architecture, or exit 2 on invalid usage.
sb_image_base_digest() {
  local ref=${1:-} arch=${2:-} out platform value digest=""
  case "$arch" in
  arm64 | amd64) ;;
  *)
    px_error "sb_image_base_digest needs arm64 or amd64, got '$arch'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  case "$ref" in
  '' | *[[:space:]@]*)
    px_error "sb_image_base_digest needs a reference without a digest, got '$ref'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  out="$(px_compose_engine 120 buildx imagetools inspect "$ref" --format \
    '{{range .Manifest.Manifests}}{{with .Platform}}{{.OS}}/{{.Architecture}}{{end}} {{.Digest}}
{{end}}')" || out=""
  while read -r platform value; do
    if [ "$platform" = "linux/$arch" ] && [[ $value =~ ^sha256:[0-9a-f]{64}$ ]]; then
      digest=$value
      break
    fi
  done <<LIST
$out
LIST
  if [ -z "$digest" ]; then
    px_error "the registry gave no linux/$arch manifest digest for $ref"
    return "$SB_EXIT_FAIL"
  fi
  px_tsv_row "$ref" "$digest"
}

# Push a local image, identified by its image ID, to a target registry reference and report the
# manifest digest assigned by the registry. This operation neither builds an image nor logs into the
# registry, relying instead on the operator's credential store to authenticate the push.
## @description  Push a local image by ID to a registry reference and report its registry digest.
## @param        image_id: local image ID formatted as sha256:<64 hex>
## @param        target_ref: target <registry>/<repository>:<tag> reference without a digest
## @return       Write one image TSV row (target_ref and registry digest) and exit 0, 1 on no engine
##               answer, push failure, or missing digest, 2 on usage, or 6 if no local image has ID.
sb_image_push() {
  local id=${1:-} target=${2:-} found timeout status=0 line digest=""
  if ! [[ $id =~ ^sha256:[0-9a-f]{64}$ ]]; then
    px_error "sb_image_push needs an image id sha256:<64 hex>, got '$id'"
    return "$SB_EXIT_USAGE"
  fi
  if ! [[ $target =~ ^[a-z0-9][a-z0-9.:-]*/[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]
  then
    px_error "sb_image_push needs a target <registry>/<repository>:<tag>, got '$target'"
    return "$SB_EXIT_USAGE"
  fi
  if sb_dry_run "push of $id to $target"; then
    return 0
  fi
  found="$(px_compose_engine 60 image inspect --format '{{.Id}}' "$id" 2>/dev/null)" || found=""
  if [ "$found" != "$id" ]; then
    px_error "no local image has the id $id"
    return "$SB_EXIT_NO_OBJECT"
  fi
  if ! px_compose_engine 60 tag "$id" "$target" >&2; then
    px_error "the engine could not tag $id as $target"
    return "$SB_EXIT_FAIL"
  fi
  timeout="$(sb_seconds PX_TIMEOUT_PROVISION 900)"
  px_compose_engine "$timeout" push "$target" >&2 || status=$?
  if [ "$status" != 0 ]; then
    sb_expired "$status" && px_error "the push of $target expired after ${timeout}s"
    px_error "the engine could not push $target"
    return "$SB_EXIT_FAIL"
  fi
  # The container engine records the manifest digest assigned by the registry in RepoDigests, with
  # one entry for each repository associated with the image.
  while IFS= read -r line; do
    case "$line" in
    "${target%:*}@sha256:"*) digest=${line#*@} ;;
    esac
  done <<EOF_DIGESTS
$(px_compose_engine 60 image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' \
    "$target" 2>/dev/null || true)
EOF_DIGESTS
  if ! [[ $digest =~ ^sha256:[0-9a-f]{64}$ ]]; then
    px_error "the registry gave no digest for $target"
    return "$SB_EXIT_FAIL"
  fi
  px_tsv_row "$target" "$digest"
}
