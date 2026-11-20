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
# sb_* contract functions and serves as the sole component in the repository permitted to invoke a
# container runtime directly.
#
# Four architectural characteristics of the Compose runtime govern this implementation:
#   1. Compose has no native scope object. Scope is represented by a combination of a project name
#      and a label set, so every resource selection in this file operates as a label filter.
#   2. Compose addresses a service rather than an individual instance. Because the instance index
#      is derived from the container number, this module translates between the contract's
#      zero-based instance index and Compose's one-based container number.
#   3. Compose retains no record of a replaced container. Previous container logs and previous
#      termination reasons are therefore unsupported, as declared by sb_capabilities, and callers
#      must rely on the restart count together with the current termination reason instead.
#   4. The container runtime reports health rather than readiness. Readiness is derived from the
#      health state produced by the image healthcheck defined in A2, so this module inspects only
#      the reported health status.
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
# The contract revision is intentionally defined as a distinct literal both here and in
# contract.sh. Having two independent literals enables sb_load to compare them at binding time,
# ensuring that an out-of-date backend fails to bind rather than operating against a mismatched
# contract revision.

PX_COMPOSE_CONTRACT=1
PX_COMPOSE_ID=compose

# The minimum Docker engine version corresponds to the first release supporting the Compose v2
# capabilities required by this module: the cp subcommand, the --index instance selector on exec
# and cp, and project selection without an explicit compose file. The minimum Compose version is
# set to the v2 release series for the same reason.
PX_COMPOSE_ENGINE_FLOOR_MAJOR=20
PX_COMPOSE_ENGINE_FLOOR_MINOR=10
PX_COMPOSE_FLOOR_MAJOR=2

# Default template for the HBase RegionMover command line, parameterized by five placeholders that
# this module substitutes at runtime: {operation}, {host}, {port}, {timeout}, and {file}. The
# {port} placeholder resolves to the instance's RPC port from the generated port map, because
# multiple instances may share a hostname and be distinguished only by port. Because the container
# image providing the mover is introduced in A2, this command template is configurable and can be
# overridden via PX_REGION_MOVER_CMD rather than being hardcoded. Whether a given role supports
# drain semantics is governed by the role's drain label rather than by this command template.
PX_COMPOSE_MOVER='hbase org.apache.hadoop.hbase.util.RegionMover'
PX_COMPOSE_MOVER="$PX_COMPOSE_MOVER --regionserverhost {host}:{port}"
PX_COMPOSE_MOVER="$PX_COMPOSE_MOVER --operation {operation}"
PX_COMPOSE_MOVER="$PX_COMPOSE_MOVER --timeout {timeout}"
PX_COMPOSE_MOVER="$PX_COMPOSE_MOVER --filename {file}"

## @description  Report the backend identifier recorded in the bundle manifest.
## @return       Write the backend name to stdout and exit 0 always.
sb_backend_id() { printf '%s\n' "$PX_COMPOSE_ID"; }

## @description  Report the substrate contract revision implemented by this backend.
## @return       Write the contract revision number to stdout and exit 0 always.
sb_contract_version() { printf '%s\n' "$PX_COMPOSE_CONTRACT"; }

# ---------------------------------------------------------------------------
# 2. Container Runtime and Compose Invocation Flavour
# ---------------------------------------------------------------------------
# This backend targets the Docker engine, preferring the integrated `docker compose` CLI plugin
# subcommand and falling back to the standalone `docker-compose` binary only when the plugin is
# unavailable. Flavour detection is centralized in a single resolution function, and all
# invocations route through px_compose_call so that no other function needs to branch on the
# Compose flavour.
#
# Resolved executable locations are stored as absolute paths in module-private variables that are
# never exported, preventing child processes such as workload drivers from invoking the runtime
# through inherited environment variables. Using absolute paths also avoids colliding with the
# guard functions defined in lib/common.sh, which shadow bare runtime binary names to trap
# unauthorized direct calls; because px_timeout executes commands without the `command` builtin, a
# bare command name would trigger those traps, whereas an absolute path always resolves directly to
# the binary.

_PXC_ENGINE=""
_PXC_FLAVOUR=""
_PXC_COMPOSE=""

# Retain any operator-supplied PX_DOCKER_BIN value within the current shell process while stripping
# it from the exported environment of child processes. This unexport is performed at library load
# time because engine resolution frequently executes inside a command substitution subshell, where
# modifying export attributes would not propagate back to the parent shell.
if [ -n "${PX_DOCKER_BIN+set}" ]; then
  export -n PX_DOCKER_BIN
fi

# Resolve the Docker CLI executable path once per process. When the operator specifies
# PX_DOCKER_BIN (typically via env/local.env), that path or command name takes precedence;
# otherwise, resolution searches PATH followed by standard desktop installation directories, since
# non-interactive shells often omit desktop Docker directories from PATH.
## @description  Resolve the container runtime binary and cache its absolute path in _PXC_ENGINE.
## @return       Set _PXC_ENGINE and exit 0, or log an error and exit 1 if no runtime is present.
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
        # Skip the home-relative candidate when HOME is unset so that lookup does not probe a
        # root-level /.docker directory belonging to no user account.
        case "$candidate" in
        /.docker/*) continue ;;
        esac
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

# Determine once per process whether Docker Compose is available as the standard Docker CLI plugin
# subcommand or as a standalone docker-compose executable. The integrated CLI subcommand is the
# primary distribution format and is probed first, while the standalone binary serves as a fallback
# when the plugin subcommand is unavailable.
## @description  Resolve the Compose invocation flavour and cache _PXC_FLAVOUR and _PXC_COMPOSE.
## @return       Set _PXC_FLAVOUR to plugin or standalone (plus _PXC_COMPOSE) and exit 0, or exit 1
##               when Compose is absent.
px_compose_resolve_flavour() {
  local candidate
  [ -z "$_PXC_FLAVOUR" ] || return 0
  px_compose_resolve_engine || return "$SB_EXIT_FAIL"
  if px_compose_call runtime 30 compose version --short >/dev/null 2>&1; then
    _PXC_FLAVOUR=plugin
    px_debug "compose flavour: plugin"
    return 0
  fi
  for candidate in docker-compose "${HOME:-}/.docker/cli-plugins/docker-compose"; do
    case "$candidate" in
    /.docker/*) continue ;;
    */*) [ -x "$candidate" ] && _PXC_COMPOSE="$candidate" ;;
    *) _PXC_COMPOSE="$(type -P "$candidate" 2>/dev/null)" || _PXC_COMPOSE="" ;;
    esac
    [ -z "$_PXC_COMPOSE" ] || break
  done
  if [ -z "$_PXC_COMPOSE" ]; then
    px_error "no compose is available. Install the compose plugin of Docker."
    return "$SB_EXIT_FAIL"
  fi
  _PXC_FLAVOUR=standalone
  px_debug "compose flavour: standalone $_PXC_COMPOSE"
}

# Serve as the single execution gateway for all container runtime and Compose invocations in this
# backend, centralizing binary resolution, Compose flavour dispatch, and bounded timeout
# enforcement. Direct un-timed calls invoke the `command` builtin to bypass the shell trap
# functions in lib/common.sh, which is the only sanctioned bypass in the harness. The surface
# parameter selects `runtime` for a standard engine verb, `merged` for an engine verb with stderr
# merged into stdout, or `compose` for a Compose subcommand; timeout_s specifies the timeout in
# seconds or `-` for no timeout, followed by the command arguments. It returns the runtime exit
# status, 124 when the timeout expires, 137 when the runtime is terminated with KILL after the
# grace period, 125 when the timeout watchdog cannot start the command, 1 when the runtime or
# Compose binary cannot be resolved, or 2 when the requested surface is unknown.
## @description  Execute a container runtime or Compose command with optional timeout enforcement.
## @param        surface, timeout_s, argument...  Specify the surface, timeout (or -), and args.
## @return       Exit with the runtime status, 124/137 on timeout/KILL, 125 on nostart, 1, or 2.
px_compose_call() {
  local surface=${1:-} timeout=${2:-} status=0
  shift 2 || true
  px_compose_resolve_engine || return "$SB_EXIT_FAIL"
  px_debug "$surface call: $*"
  case "$surface" in
  runtime | merged) set -- "$_PXC_ENGINE" "$@" ;;
  compose)
    px_compose_resolve_flavour || return "$SB_EXIT_FAIL"
    if [ "$_PXC_FLAVOUR" = standalone ]; then
      set -- "$_PXC_COMPOSE" "$@"
    else
      set -- "$_PXC_ENGINE" compose "$@"
    fi
    ;;
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

## @description  Invoke a direct container runtime subcommand such as inspect or kill.
## @param        timeout_s, argument...  Specify the timeout in seconds (or -) and runtime args.
## @return       Return the exit status of the container runtime invocation.
px_compose_engine() {
  local timeout=${1:-}
  shift || true
  px_compose_call runtime "$timeout" "$@"
}

# Execute a Compose subcommand scoped to a specific project. The project name is always passed via
# `-p`, and when the persisted scope record provides an existing compose file and deployment
# directory, they are included so that subsequent processes target the exact same deployment. If
# the recorded compose file has been removed, the command falls back to project-name selection
# alone, which suffices for all operations except `up` and allows teardown to clean up remaining
# instances.
## @description  Run a Compose verb within the project context of a given scope.
## @param        scope_id, timeout_s, argument...  Pass the scope ID, timeout (or -), and args.
## @return       Return the exit status of the Compose invocation or of the preceding scope load.
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
# Every blocking operation in this backend enforces a finite timeout. Operations that accept an
# explicit timeout argument use it directly, while operations without a contract-level timeout
# parameter draw from the corresponding PX_TIMEOUT_* environment variable. The hardcoded fallback
# durations at each call site mirror docker/env/defaults.env so that callers binding the backend
# without the configuration loader still observe identical timeout behavior.

# Resolve a positive integer duration in seconds from the named configuration variable, falling
# back to fallback_seconds whenever the variable is unset, empty, non-numeric, or zero.
## @description  Read a positive integer timeout in seconds from a variable or use a fallback.
## @param        variable_name, fallback_seconds  Specify the variable name and fallback seconds.
## @return       Write the resolved number of seconds to stdout and exit 0.
px_compose_seconds() {
  local value=${!1:-}
  case "$value" in
  '' | *[!0-9]*) value=$2 ;;
  *) [ "$((10#$value))" -gt 0 ] || value=$2 ;;
  esac
  printf '%s\n' "$value"
}

## @description  Report the readiness polling interval in seconds, which may be fractional.
## @return       Write the polling interval to stdout and exit 0.
px_compose_interval() {
  local value=${PX_POLL_INTERVAL_S:-}
  if ! [[ $value =~ $PX_RE_SECONDS ]]; then
    value=2
  fi
  printf '%s\n' "$value"
}

# Validate and resolve an optional timeout argument. When the caller omits the argument or passes
# an empty string, the timeout defaults to the configured variable named by variable_name or to
# fallback_seconds; otherwise, it must be a whole number of seconds greater than zero.
## @description  Validate a positive integer timeout argument or resolve its configured default.
## @param        argument, variable_name, fallback_seconds  Specify the timeout and default values.
## @return       Write the timeout in seconds to stdout and exit 0, or exit 2 if invalid.
px_compose_timeout_arg() {
  local given=${1:-} name=${2:-} fallback=${3:-}
  if [ -z "$given" ]; then
    px_compose_seconds "$name" "$fallback"
    return 0
  fi
  if ! [[ $given =~ $PX_RE_INT ]] || [ "$((10#$given))" -lt 1 ]; then
    px_error "a timeout must be a whole number of seconds above zero, got '$given'"
    return "$SB_EXIT_USAGE"
  fi
  printf '%s\n' "$given"
}

# Determine whether an exit status from px_timeout represents a timeout expiration. A runtime
# command that survives the initial termination signal and is killed with SIGKILL after the grace
# period (status 137) is treated as an expiration alongside SB_EXEC_TIMEOUT (124).
## @description  Test whether a px_timeout exit status indicates that the wait expired.
## @param        status  Provide the exit status returned by px_timeout.
## @return       Exit 0 if the wait expired, or exit 1 otherwise.
px_compose_expired() {
  case "${1:-}" in
  "$SB_EXEC_TIMEOUT" | 137) return 0 ;;
  esac
  return 1
}

# Check whether dry-run mode is active and should short-circuit a mutating operation, logging the
# skipped action text so the operator can inspect the execution plan.
## @description  Check whether dry-run mode intercepts a state-changing action and log the action.
## @param        action  Describe the mutating action that would otherwise be performed.
## @return       Exit 0 when dry-run mode prevents execution, or exit 1 when execution should run.
px_compose_dry_run() {
  px_is_dry_run || return 1
  px_info "dry run: $* not performed"
  return 0
}

## @description  Report the label namespace prefix used to scope all harness-managed objects.
## @return       Write the label prefix to stdout and exit 0.
px_compose_label_prefix() { printf '%s\n' "${PX_LABEL_PREFIX:-px.harness}"; }

## @description  Report the project name prefix used to construct scope identifiers.
## @return       Write the project prefix to stdout and exit 0.
px_compose_project_prefix() { printf '%s\n' "${PX_PROJECT_PREFIX:-px}"; }

# Normalize a role name or port identifier into the uppercase environment variable format used by
# the generated port map, delegating case conversion and punctuation normalization to `tr` for
# compatibility with Bash 3.2, which lacks case-modifying parameter expansion.
## @description  Convert a role or port name into the uppercase token format of the port map.
## @param        text  Provide the role or port name string to normalize.
## @return       Write the normalized uppercase string to stdout and exit 0.
px_compose_upper() {
  printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]' | tr '.-' '__'
}

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

## @description  Resolve and create the persistent state directory for the Compose backend.
## @return       Write the state directory path to stdout and exit 0, or return the output-policy
##               error status if directory resolution is refused.
px_compose_state_dir() {
  local dir status=0
  dir="$(px_state_dir)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  mkdir -p "$dir/compose" ||
    px_die "$SB_EXIT_FAIL" "cannot create the backend state directory in $dir"
  printf '%s/compose\n' "$dir"
}

## @description  Report the filesystem path to the state record file for a given scope.
## @param        scope_id  Identify the scope whose record path is requested.
## @return       Write the path to stdout and exit 0, or return the output-policy error status.
px_compose_record_path() {
  local dir status=0
  dir="$(px_compose_state_dir)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  printf '%s/scope-%s.tsv\n' "$dir" "${1:-}"
}

## @description  Read a single configuration value from a scope state record.
## @param        scope_id, key  Identify the target scope and the record key to look up.
## @return       Write the value to stdout and exit 0, or exit 6 if the record or key is absent.
px_compose_record_get() {
  local scope=${1:-} key=${2:-} path row status=0
  path="$(px_compose_record_path "$scope")" || status=$?
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_NO_OBJECT"
  fi
  [ -f "$path" ] || return "$SB_EXIT_NO_OBJECT"
  while IFS= read -r row; do
    case "$row" in
    "$key$PX_TAB"*)
      printf '%s\n' "${row#*"$PX_TAB"}"
      return 0
      ;;
    esac
  done <"$path"
  return "$SB_EXIT_NO_OBJECT"
}

# Atomically update a key-value entry in the scope's TSV state record by rewriting existing rows
# without the target key and appending the new pair, guaranteeing that each key appears at most
# once so subsequent reads are unambiguous.
## @description  Write or replace a single key-value entry in a scope state record.
## @param        scope_id, key, value  Specify the target scope, the record key, and its new value.
## @return       Exit 0 on success, or exit 1 if the record file cannot be written.
px_compose_record_set() {
  local scope=${1:-} key=${2:-} value=${3:-} path tmp row status=0
  path="$(px_compose_record_path "$scope")" || status=$?
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  tmp="$path.$$"
  : >"$tmp" || {
    px_error "cannot write the scope record $path"
    return "$SB_EXIT_FAIL"
  }
  if [ -f "$path" ]; then
    while IFS= read -r row; do
      case "$row" in
      "$key$PX_TAB"* | '') continue ;;
      esac
      printf '%s\n' "$row" >>"$tmp"
    done <"$path"
  fi
  px_tsv_row "$key" "$value" >>"$tmp" || status=$?
  if [ "$status" != 0 ]; then
    rm -f "$tmp"
    return "$SB_EXIT_FAIL"
  fi
  mv -f "$tmp" "$path" || {
    rm -f "$tmp"
    px_error "cannot replace the scope record $path"
    return "$SB_EXIT_FAIL"
  }
}

## @description  Delete the persistent state record file for a given scope.
## @param        scope_id  Identify the scope whose state record should be removed.
## @return       Exit 0 always.
px_compose_record_delete() {
  local path status=0
  path="$(px_compose_record_path "${1:-}")" || status=$?
  if [ "$status" = 0 ]; then
    rm -f "$path"
  fi
  return 0
}

# Load the runtime scoping variables for a given scope identifier into the PX_COMPOSE_* globals.
# When a state record exists on disk, it supplies the deployment directory, compose file path, and
# network name; even without a state record, the project name and cluster ID are derived directly
# from the scope ID so that inventory and inspection commands remain functional against clusters
# created by another process.
## @description  Populate the PX_COMPOSE_* scoping variables for a validated scope identifier.
## @param        scope_id  Provide the scope identifier in <project_prefix>-<cluster> format.
## @return       Set the PX_COMPOSE_* globals and exit 0, or exit 2 if the scope ID is invalid.
px_compose_scope_load() {
  local scope=${1:-} prefix
  prefix="$(px_compose_project_prefix)"
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
  # A scope without a persisted record is a normal pre-provisioning state, so missing-record
  # lookups must be absorbed inside the command substitution via `|| true` to avoid triggering any
  # ERR trap armed by the caller.
  PX_COMPOSE_DEPLOY_DIR="$(px_compose_record_get "$scope" deployment_dir || true)"
  PX_COMPOSE_FILE="$(px_compose_record_get "$scope" compose_file || true)"
  PX_COMPOSE_NETWORK="$(px_compose_record_get "$scope" network || true)"
  if [ -z "$PX_COMPOSE_NETWORK" ]; then
    PX_COMPOSE_NETWORK="${scope}_default"
  fi
  return 0
}

## @description  Report the cluster label selector expression for a given scope.
## @param        scope_id  Identify the scope whose label selector is requested.
## @return       Write the label selector to stdout and exit 0, or return the scope-load status.
px_compose_selector() {
  local prefix
  px_compose_scope_load "${1:-}" || return $?
  prefix="$(px_compose_label_prefix)"
  printf '%s.cluster=%s\n' "$prefix" "$PX_COMPOSE_CLUSTER"
}

# Query the container runtime for all distinct cluster identifiers currently present on the host
# under the harness cluster label, providing the live container inventory used by the
# one-cluster-per-host admission gate.
## @description  List all harness cluster identifiers that currently have containers on this host.
## @return       Write one cluster ID per line to stdout and exit 0, or exit 1 if the runtime
##               cannot be reached.
px_compose_host_clusters() {
  local prefix out status=0 seen="" line
  prefix="$(px_compose_label_prefix)"
  out="$(px_compose_engine 30 ps -a --filter "label=$prefix.cluster" \
    --format "{{.Label \"$prefix.cluster\"}}")" || status=$?
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$PX_NL$seen" in
    *"$PX_NL$line$PX_NL"*) continue ;;
    esac
    seen="$seen$line$PX_NL"
    printf '%s\n' "$line"
  done <<EOF
$out
EOF
}

## @description  List all scope identifiers that currently have a state record in the backend dir.
## @return       Write one scope ID per line to stdout and exit 0, or exit 1 if the state
##               directory is refused.
px_compose_recorded_scopes() {
  local dir path name
  dir="$(px_compose_state_dir)" || return "$SB_EXIT_FAIL"
  for path in "$dir"/scope-*.tsv; do
    [ -f "$path" ] || continue
    name="${path##*/scope-}"
    printf '%s\n' "${name%.tsv}"
  done
}

# Acquire the host-wide admission lock for scope creation so that two concurrent sessions cannot
# both pass the single-cluster check before either writes its state record. Locking uses atomic
# directory creation via `mkdir` and waits up to 30 seconds; if an existing lock directory belongs
# to a process ID that is no longer alive, it is treated as stale and reclaimed automatically.
## @description  Acquire the atomic directory lock guarding the host scope-creation gate.
## @param        lock_dir  Specify the filesystem path of the lock directory to acquire.
## @return       Exit 0 when acquired, or exit 1 if held by another process for 30 seconds.
px_compose_lock() {
  local lock=${1:-} owner waited=0
  while ! mkdir "$lock" 2>/dev/null; do
    owner="$(cat "$lock/pid" 2>/dev/null)" || owner=""
    case "$owner" in
    '' | *[!0-9]*) owner="" ;;
    esac
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
      px_warn "removing the stale host lock of process $owner"
      rm -f "$lock/pid"
      rmdir "$lock" 2>/dev/null || true
      continue
    fi
    if [ "$waited" -ge 30 ]; then
      px_error "another process holds the host lock $lock"
      return "$SB_EXIT_FAIL"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  printf '%s\n' "$$" >"$lock/pid" || true
  return 0
}

## @description  Release the directory lock guarding the host scope-creation gate.
## @param        lock_dir  Specify the filesystem path of the lock directory to release.
## @return       Exit 0 always.
px_compose_unlock() {
  rm -f "${1:-}/pid"
  rmdir "${1:-}" 2>/dev/null || true
  return 0
}

# Enforce the scope-creation admission gate while holding the host lock: no harness container may
# already exist on the host, and unless PX_FORCE is enabled, neither a record for another scope nor
# a pre-existing record for this scope may be present. Checking persisted records is required
# because a newly created scope holds no containers prior to provisioning. Once all checks pass,
# initialize the new scope's state record on disk, or return exit code 1 if the gate refuses, the
# runtime fails to respond, or the record cannot be written.
## @description  Verify the single-cluster host admission gate and initialize the scope record.
## @param        scope_id  Identify the scope being admitted and recorded.
## @return       Exit 0 when recorded, or exit 1 if refused, unreachable, or unwritable.
px_compose_scope_gate() {
  local scope=${1:-} present others="" name path now status=0
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
  if ! px_is_true "${PX_FORCE:-0}"; then
    while IFS= read -r name; do
      [ -n "$name" ] && [ "$name" != "$scope" ] || continue
      others="$others $name"
    done <<EOF
$(px_compose_recorded_scopes)
EOF
    if [ -n "$others" ]; then
      px_error "another scope is recorded on this host:$others. Tear it down first," \
        "or set PX_FORCE=1 when the record is stale."
      return "$SB_EXIT_FAIL"
    fi
  fi
  path="$(px_compose_record_path "$scope")" || return "$SB_EXIT_FAIL"
  if [ -f "$path" ] && ! px_is_true "${PX_FORCE:-0}"; then
    px_error "scope $scope already exists in $path." \
      "Run a teardown, or set PX_FORCE=1 to replace the record."
    return "$SB_EXIT_FAIL"
  fi
  rm -f "$path"
  now="$(px_timestamp)"
  px_compose_record_set "$scope" cluster "$PX_COMPOSE_CLUSTER" || return "$SB_EXIT_FAIL"
  px_compose_record_set "$scope" project "$PX_COMPOSE_PROJECT" || return "$SB_EXIT_FAIL"
  px_compose_record_set "$scope" network "$PX_COMPOSE_NETWORK" || return "$SB_EXIT_FAIL"
  px_compose_record_set "$scope" created_at "$now" || return "$SB_EXIT_FAIL"
}

# Create the logical scoping envelope for a cluster deployment. Because a Compose scope consists of
# a project name, a label set, and a state record rather than a live runtime object, creation is
# lightweight and primarily enforces the host admission gate: at most one harness cluster per host
# and no pre-existing deployment of the requested cluster.
## @description  Create the scoping envelope for a cluster and persist its initial state record.
## @param        cluster_id  Specify the cluster identifier, defaulting to PX_CLUSTER_ID.
## @return       Write the scope ID to stdout and exit 0, exit 1 if a cluster is already present
##               or the runtime did not respond, or exit 2 on invalid usage.
sb_scope_create() {
  local cluster=${1:-${PX_CLUSTER_ID:-}} prefix scope dir status=0
  if [ -z "$cluster" ]; then
    px_error "sb_scope_create needs a cluster id"
    return "$SB_EXIT_USAGE"
  fi
  if ! [[ $cluster =~ $SB_CLUSTER_RE ]]; then
    px_error "a cluster id starts with a lower-case letter or a digit and holds" \
      "lower-case letters, digits, - and _ only: '$cluster'"
    return "$SB_EXIT_USAGE"
  fi
  prefix="$(px_compose_project_prefix)"
  scope="$prefix-$cluster"
  px_compose_scope_load "$scope" || return $?
  if px_compose_dry_run "create the scope $scope"; then
    printf '%s\n' "$scope"
    return 0
  fi
  dir="$(px_compose_state_dir)" || return "$SB_EXIT_FAIL"
  px_compose_lock "$dir/host.lock" || return "$SB_EXIT_FAIL"
  px_compose_scope_gate "$scope" || status=$?
  px_compose_unlock "$dir/host.lock"
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  px_config_set PX_SCOPE_ID "$scope" backend
  printf '%s\n' "$scope"
}

# Determine whether a scope currently exists on this host. Live containers carrying the scope's
# cluster label provide definitive evidence of existence, while a persisted state record alone is
# accepted as sufficient weak evidence so that an unprovisioned scope cannot be created twice.
## @description  Test whether a scope exists via live labeled containers or a persisted record.
## @param        scope_id  Identify the scope to check.
## @return       Exit 0 if present, exit 6 if absent, exit 1 if the runtime did not respond and no
##               record exists, or exit 2 on invalid usage.
sb_scope_exists() {
  local scope=${1:-} rows path status=0
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope")" || status=$?
  if [ "$status" = 0 ] && [ -n "$rows" ]; then
    return 0
  fi
  path="$(px_compose_record_path "$scope")" || return "$SB_EXIT_FAIL"
  if [ -f "$path" ]; then
    px_debug "scope $scope has a record but no instance"
    return 0
  fi
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return "$SB_EXIT_NO_OBJECT"
}

# Report the resolved project, label selector, compose file, and network parameters for a scope so
# higher-level callers never need to construct backend-specific CLI arguments themselves.
## @description  Report the resolved backend scoping parameters for an existing scope as TSV rows.
## @param        scope_id  Identify the scope to inspect.
## @return       Write key-value TSV rows to stdout and exit 0, exit 6 if the scope is unknown,
##               exit 1 if the runtime did not respond, or exit 2 on invalid usage.
sb_scope_args() {
  local scope=${1:-} selector
  px_compose_scope_load "$scope" || return $?
  sb_scope_exists "$scope" || return $?
  selector="$(px_compose_selector "$scope")" || return $?
  px_tsv_row project "$PX_COMPOSE_PROJECT"
  px_tsv_row label_selector "$selector"
  px_tsv_row compose_file "$PX_COMPOSE_FILE"
  px_tsv_row network "$PX_COMPOSE_NETWORK"
}

# Remove the scope envelope after container instances have been torn down. Because Docker networks
# and volumes outlive containers in Compose deployments, this function explicitly cleans up the
# project network, any remaining volumes (unless PX_KEEP is set), and the persisted scope record so
# that orphaned resources are not left behind.
## @description  Remove the scope network, volumes, and state record after instance teardown.
## @param        scope_id  Identify the scope to delete.
## @return       Exit 0 on complete removal, or exit 1 if any resource could not be removed.
sb_scope_delete() {
  local scope=${1:-} timeout failed=0 name out status=0
  px_compose_scope_load "$scope" || return $?
  if px_compose_dry_run "delete the scope $scope"; then
    return 0
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_TEARDOWN 300)"
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
  px_compose_record_delete "$scope"
  PX_COMPOSE_SCOPE=""
  if [ "$failed" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return "$status"
}

# Discover all Docker volumes associated with a scope by querying both the harness cluster label
# applied by the deployment generator and the com.docker.compose.project label applied directly by
# Compose, deduplicating the combined volume list.
## @description  List all unique volume names belonging to a scope across cluster and project tags.
## @param        scope_id  Identify the scope whose volumes should be listed.
## @return       Write one volume name per line to stdout and exit 0, or exit 1 on runtime failure.
px_compose_volumes() {
  local scope=${1:-} selector out other seen="" name status=0
  px_compose_scope_load "$scope" || return $?
  selector="$(px_compose_selector "$scope")" || return "$SB_EXIT_FAIL"
  out="$(px_compose_engine 60 volume ls -q --filter "label=$selector")" || status=$?
  other="$(px_compose_engine 60 volume ls -q \
    --filter "label=com.docker.compose.project=$PX_COMPOSE_PROJECT")" || status=$?
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    case "$PX_NL$seen" in
    *"$PX_NL$name$PX_NL"*) continue ;;
    esac
    seen="$seen$name$PX_NL"
    printf '%s\n' "$name"
  done <<EOF
$out
$other
EOF
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
# Because the substrate contract uses zero-based instance indices whereas Compose assigns one-based
# container numbers, index translation is centralized exclusively within this section. Even a
# container missing explicit harness labels retains a deterministic identity: its container name
# encodes the project, service, and container number, which px_compose_index_of and
# px_compose_role_from_name parse as a fallback.
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

# Construct the Go template format string used for container inventory queries. Fields are
# delimited by vertical bars (`|`) because label values may contain whitespace whereas none of the
# selected inventory fields can contain a vertical bar.
## @description  Output the pipe-delimited Go template format string for container inventory calls.
## @return       Write the format template to stdout and exit 0.
px_compose_ps_format() {
  local prefix fmt
  prefix="$(px_compose_label_prefix)"
  fmt="{{.ID}}|{{.Label \"$prefix.role\"}}|{{.Names}}|{{.State}}|{{.Status}}"
  fmt="$fmt|{{.Image}}|{{.Label \"com.docker.compose.service\"}}"
  fmt="$fmt|{{.Label \"com.docker.compose.container-number\"}}"
  fmt="$fmt|{{.Label \"$prefix.index\"}}|{{.Label \"$prefix.group\"}}"
  fmt="$fmt|{{.Label \"$prefix.start\"}}|{{.Label \"$prefix.drain\"}}"
  printf '%s\n' "$fmt"
}

# Query all containers belonging to a scope, including stopped and exited containers so that
# intentionally stopped role groups and completed one-shot setup containers remain visible in the
# cluster inventory. Additional runtime `--filter` arguments (such as `--filter status=running`)
# may be appended after scope_id.
## @description  List raw pipe-delimited inventory lines for all containers in a scope.
## @param        scope_id, filter_arg...  Specify the target scope and optional `ps` filter flags.
## @return       Write one raw inventory line per container to stdout and exit 0, or 1 on failure.
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

# Parse the zero-based instance index from a Compose container name. Compose constructs container
# names from the project, service, and one-based replica number separated by hyphens (or by
# underscores in older naming conventions); a container name without a trailing numeric suffix
# represents a single-instance service at index 0.
## @description  Derive the zero-based instance index from a Compose container name suffix.
## @param        container_name  Provide the container name to parse.
## @return       Write the zero-based instance index to stdout and exit 0.
px_compose_index_from_name() {
  local name=${1:-} tail index=0
  tail="${name##*[-_]}"
  case "$tail" in
  '' | *[!0-9]*) index=0 ;;
  *)
    index=$((10#$tail - 1))
    if [ "$index" -lt 0 ]; then
      index=0
    fi
    ;;
  esac
  printf '%s\n' "$index"
}

# Extract the role name from a Compose container name by stripping the leading project prefix and
# any trailing replica number suffix, leaving the middle service segment.
## @description  Extract the role identifier from the service segment of a Compose container name.
## @param        container_name  Provide the container name to parse.
## @return       Write the extracted role name to stdout and exit 0.
px_compose_role_from_name() {
  local name=${1:-} body
  body="${name#"$PX_COMPOSE_PROJECT"-}"
  body="${body#"$PX_COMPOSE_PROJECT"_}"
  case "$body" in
  *[-_]*) printf '%s\n' "${body%[-_]*}" ;;
  *) printf '%s\n' "$body" ;;
  esac
}

# Resolve the zero-based instance index for a container using a three-tier precedence order: the
# explicit generator index label takes priority, followed by Compose's one-based container-number
# label, and finally the numeric suffix of the container name. Both inventory queries and the event
# stream share this resolver so they always assign identical instance identities. The result is
# stored directly in PX_COMPOSE_INDEX rather than written to stdout to avoid forking a subshell for
# every inventory line.
## @description  Resolve a container's zero-based instance index into PX_COMPOSE_INDEX.
## @param        label, number, name  Pass the index label, Compose container number, and name.
## @return       Set PX_COMPOSE_INDEX to the zero-based index and exit 0.
px_compose_index_of() {
  local label=${1:-} number=${2:-} name=${3:-}
  case "$label" in
  '' | *[!0-9]*)
    case "$number" in
    '' | *[!0-9]*) PX_COMPOSE_INDEX="$(px_compose_index_from_name "$name")" ;;
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

# Parse a single pipe-delimited inventory line into the PX_PS_* variables, normalizing the role,
# zero-based index, contract lifecycle state, and readiness status into the shared substrate
# vocabulary. The trailing group, start, and drain label columns are optional and default to empty
# strings when omitted from shorter lines.
#
# Short-lived setup containers that format filesystems or probe cluster state exit with status 0
# upon completion; this parser treats a cleanly exited container (exit code 0) without a failing
# healthcheck as ready so that readiness barriers do not block indefinitely waiting for one-shot
# initialization tasks to remain running.
## @description  Parse a raw pipe-delimited inventory line into the normalized PX_PS_* variables.
## @param        line  Provide the raw pipe-delimited inventory line from px_compose_ps.
## @return       Populate the PX_PS_* variables and exit 0, or exit 1 if the line is malformed.
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
  if [ -z "$PX_PS_ROLE" ]; then
    PX_PS_ROLE="$(px_compose_role_from_name "$PX_PS_NAME")"
  fi
  px_compose_index_of "$PX_PS_INDEX" "$PX_PS_NUMBER" "$PX_PS_NAME"
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
    # When the status string contains no healthcheck annotation, the image defines no healthcheck;
    # lifecycle state is then the sole readiness signal, so a running container or a container that
    # exited cleanly with code 0 is considered ready.
    if [ "$PX_PS_STATE" = running ]; then
      PX_PS_READY=ready
    elif [ "$PX_PS_STATE" = exited ] && [ "$PX_PS_EXIT" = 0 ]; then
      PX_PS_READY=ready
    fi
    ;;
  esac
  return 0
}

# Check whether a given role is included in a space-separated role filter list. An empty selection
# matches every role, allowing readiness waits without explicit role arguments to cover the entire
# scope.
## @description  Test whether a role matches a space-separated role selection list (or empty list).
## @param        role, selection  Provide the candidate role name and space-separated selection.
## @return       Exit 0 if the role is selected (or selection is empty), or exit 1 otherwise.
px_compose_role_selected() {
  local role=${1:-} selection=${2:-} word
  [ -n "$selection" ] || return 0
  for word in $selection; do
    if [ "$word" = "$role" ]; then
      return 0
    fi
  done
  return 1
}

# Locate a single container instance by scope, role, and zero-based index and load its metadata
# into the PX_PS_* variables. All single-instance operations begin with this lookup so that input
# validation and missing-instance (`SB_EXIT_NO_OBJECT`) diagnostics are handled uniformly in one
# place.
## @description  Load the inventory attributes of a specific role/index instance into PX_PS_*.
## @param        scope_id, role, index  Identify the target scope, role name, and zero-based index.
## @return       Set PX_PS_* and exit 0, exit 6 if absent, exit 2 on bad index, or exit 1 on fail.
px_compose_instance() {
  local scope=${1:-} role=${2:-} index=${3:-} rows line
  if [ -z "$role" ] || ! [[ $index =~ $PX_RE_INT ]]; then
    px_error "an instance needs a role and a whole-number index, got '$role' '$index'"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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

# Resolve the Docker Compose service name corresponding to a contract role name by inspecting the
# scope's container inventory, returning exit code 6 when the role has no container in the scope.
## @description  Resolve the Compose service name associated with a contract role in a scope.
## @param        scope_id, role  Identify the target scope and the contract role name.
## @return       Write the service name to stdout and exit 0, or exit 6 if the role is absent.
px_compose_service_of() {
  local scope=${1:-} role=${2:-} rows line
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    if [ "$PX_PS_ROLE" = "$role" ]; then
      if [ -n "$PX_PS_SERVICE" ]; then
        printf '%s\n' "$PX_PS_SERVICE"
      else
        printf '%s\n' "$role"
      fi
      return 0
    fi
  done <<EOF
$rows
EOF
  return "$SB_EXIT_NO_OBJECT"
}

# Report the one-based container number used by Docker Compose to address the currently loaded
# PX_PS_* instance, using the explicit com.docker.compose.container-number label when present and
# otherwise computing `PX_PS_INDEX + 1`.
## @description  Report the one-based Compose container number for the current PX_PS_* instance.
## @return       Write the one-based container number to stdout and exit 0.
px_compose_number() {
  case "${PX_PS_NUMBER:-}" in
  '' | *[!0-9]*) printf '%s\n' $((PX_PS_INDEX + 1)) ;;
  *) printf '%s\n' "$PX_PS_NUMBER" ;;
  esac
}

# Enumerate container instances in the scope and emit normalized inventory rows sorted
# lexicographically by role and numerically by zero-based index (so index 2 precedes index 10).
# Because Docker Compose executes all containers on the local engine, the node column is always the
# literal `localhost`, while the image column records the image reference configured on each
# container.
## @description  List container instances in a scope, optionally filtered by role and index.
## @param        scope_id  Identify the scope to query.
## @param        role      Optionally filter results to a specific role name.
## @param        index     Optionally filter results to a specific zero-based instance index.
## @return       Write sorted instance TSV rows to stdout and exit 0, exit 6 if a requested role or
##               index does not exist, or exit 2 on invalid usage.
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
    [ -n "$line" ] || continue
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
# 6. Runtime Versions, Backend Capabilities, and Host Preflight
# ---------------------------------------------------------------------------
# Because the container engine reports version metadata in a single query, gathering version rows
# and enforcing minimum version floors are inexpensive operations. Most entries in the backend
# capability table are static architectural properties of Docker Compose rather than
# version-dependent features. The two capabilities that depend on the installed CLI
# (`resource_sampling` and `signal_delivery`) are dynamically probed once and cached under PX_OUT
# so that test suites making repeated capability checks pay the probe cost only once.

## @description  Report the runtime client, runtime server, and Compose versions for the manifest.
## @return       Write key-value TSV rows to stdout and exit 0, or exit 1 if the runtime is
##               unreachable.
sb_version_info() {
  local client server compose status=0
  client="$(px_compose_engine 30 version --format '{{.Client.Version}}')" || status=$?
  if [ "$status" != 0 ] || [ -z "$client" ]; then
    px_error "the container runtime did not report its client version"
    return "$SB_EXIT_FAIL"
  fi
  server="$(px_compose_engine 30 version --format '{{.Server.Version}}')" || server=""
  px_tsv_row engine_client "$client"
  px_tsv_row engine_server "$server"
  if px_compose_resolve_flavour; then
    px_tsv_row compose_flavour "$_PXC_FLAVOUR"
    compose="$(px_compose_compose_version)" || compose=""
    px_tsv_row compose_version "$compose"
  else
    px_tsv_row compose_flavour ""
    px_tsv_row compose_version ""
  fi
  if [ -z "$server" ]; then
    px_error "the container engine is not reachable, only the client answered"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

## @description  Report the active Compose version string with any leading `v` prefix stripped.
## @return       Write the normalized version string to stdout and exit 0, or exit 1 on failure.
px_compose_compose_version() {
  local value status=0
  value="$(px_compose_call compose 30 version --short)" || status=$?
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  printf '%s\n' "${value#v}"
}

# Compare a dotted version string against a minimum major and minor version floor using numeric
# comparison on the first two components, which is sufficient for the Docker engine and Compose
# floors enforced by this backend.
## @description  Test whether a version string meets or exceeds a major.minor version floor.
## @param        version, major_floor, minor_floor  Provide the version text and numeric floors.
## @return       Exit 0 if the floor is met, exit 1 if lower, or exit 2 if non-numeric.
px_compose_version_at_least() {
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

# Report the backend capability matrix. Five capabilities are unsupported (`no`) by architectural
# design: because Compose retains no replaced container state, `logs_previous` and
# `previous_termination` do not exist (callers inspect `sb_restart_count` and the current
# termination reason instead), while single-host execution (`node_placement`), pre-published ports
# (`port_forward`), and local image IDs without registry digests (`image_digest`) account for the
# remaining three. Two CLI-dependent capabilities (`resource_sampling` and `signal_delivery`) are
# dynamically probed against the active engine. When the engine responds cleanly, the resulting
# table is cached under PX_OUT so repeated queries avoid re-probing; if the engine is unreachable,
# the result is omitted from the cache so the next call re-runs the probe.
## @description  Report the backend capability table as TSV rows, using a cached probe when valid.
## @return       Write capability TSV rows to stdout and exit 0 always.
sb_capabilities() {
  local dir cache="" table status=0
  dir="$(px_compose_state_dir)" || dir=""
  if [ -n "$dir" ]; then
    cache="$dir/capabilities.tsv"
  fi
  if [ -n "$cache" ] && [ -f "$cache" ] && ! px_is_true "${PX_FORCE:-0}"; then
    cat "$cache"
    return 0
  fi
  table="$(px_compose_capability_table 2>/dev/null || exit "$?")" || status=$?
  # Caching is purely an optimization; an unwritable output directory must never prevent a caller
  # from querying backend capabilities.
  if [ "$status" = 0 ] && [ -n "$cache" ] &&
    printf '%s\n' "$table" >"$cache.$$" 2>/dev/null &&
    mv -f "$cache.$$" "$cache" 2>/dev/null; then
    :
  else
    rm -f "$cache.$$" 2>/dev/null
  fi
  printf '%s\n' "$table"
  return 0
}

# Assemble and print the complete capability table, executing the dynamic CLI probes before
# emitting the remaining static capability rows.
## @description  Generate the full backend capability TSV table including dynamic runtime probes.
## @return       Write capability TSV rows to stdout and exit 0, or exit 1 if the engine did not
##               respond and the probed entries could not be verified.
px_compose_capability_table() {
  local status=0
  px_tsv_row logs_previous no \
    "Compose keeps no previous container log, use sb_restart_count"
  px_compose_probe_capabilities || status=$?
  px_tsv_row previous_termination no \
    "Compose keeps no previous container, use sb_restart_count and the current reason"
  px_tsv_row events yes "runtime events filtered by the scope label"
  px_tsv_row drain_restart yes "the region mover runs through sb_exec on a drain role"
  px_tsv_row port_forward no "the deployment publishes the port, sb_endpoint reads the map"
  px_tsv_row scale yes "compose up --scale inside the limit of 1 to 9"
  px_tsv_row group_switch yes "compose stop and start selected by the group label"
  px_tsv_row oom_evidence yes "State.OOMKilled from inspection"
  px_tsv_row image_digest no "a local image carries an image id, not a registry digest"
  px_tsv_row node_placement no "one host, so the node column is localhost"
  return "$status"
}

# Probe the two capabilities that depend on the installed container runtime CLI: one-shot resource
# sampling (`stats --no-stream`) and signal delivery (`kill`). A negative probe result is trusted
# as authoritative only if the engine subsequently responds to a server version check.
## @description  Probe the active runtime CLI for resource_sampling and signal_delivery support.
## @return       Write the two probed capability TSV rows to stdout and exit 0, or exit 1 if the
##               engine server did not respond.
px_compose_probe_capabilities() {
  if px_compose_engine 20 stats --no-stream --format '{{.ID}}' >/dev/null 2>&1; then
    px_tsv_row resource_sampling yes "one stats pass per sample, limits from inspection"
  else
    px_tsv_row resource_sampling no "the runtime refused a stats pass"
  fi
  if px_compose_engine 20 kill --help >/dev/null 2>&1; then
    px_tsv_row signal_delivery yes "the runtime kill verb delivers a named signal"
  else
    px_tsv_row signal_delivery no "the runtime has no kill verb"
  fi
  px_compose_engine 20 version --format '{{.Server.Version}}' >/dev/null 2>&1 ||
    return "$SB_EXIT_FAIL"
  return 0
}

_PXC_PORTS_FROM=""

# Load the generated port mapping file (`ports.generated.env`) from the root of the scope's
# deployment directory. Because the persisted scope record stores the deployment directory path,
# processes that did not provision the scope can still locate and load the same port map. Loading
# is delegated to the configuration loader in lib/common.sh (preserving any variables already set
# by higher-precedence configuration stages and exiting with code 2 if the loader rejects a
# malformed line) and is memoized so each directory is loaded at most once per process.
## @description  Load the generated port map for a scope once per process if a map is available.
## @param        scope_id  Identify the scope whose port map should be loaded.
## @return       Exit 0 (even if no map exists), return scope-load status, or exit 2 on bad syntax.
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

# Enumerate all PX_PORT_* variable names currently exported in the process environment. Because
# Bash 3.2 does not reliably support variable-prefix expansion for this lookup, variable names are
# extracted by scanning `env` output.
## @description  List all PX_PORT_* port-mapping variable names present in the process environment.
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

# Test whether a TCP port on the local loopback interface (127.0.0.1) is currently unused by
# attempting a connection through Bash's built-in `/dev/tcp` redirection, avoiding any dependency
# on external networking utilities.
## @description  Test whether a loopback TCP port is free for binding.
## @param        port  Specify the TCP port number to probe on 127.0.0.1.
## @return       Exit 0 when the port is free, or exit 1 when a listener is already bound to it.
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

# Execute the backend-specific portion of host preflight validation. This check verifies the
# container runtime binary, daemon responsiveness, minimum engine version floor, client
# authorization credentials, Compose plugin or standalone version floor, engine-reported memory,
# and availability of all loopback TCP ports defined in the generated port map. Host-level memory,
# disk, and CPU sizing checks reside in scripts/preflight.sh, which consumes the A2 sizing table.
## @description  Run container runtime, Compose version, and loopback port preflight checks.
## @param        scope_id  Identify the scope whose port map and runtime environment are checked.
## @return       Write check TSV rows to stdout and exit 0 if all checks pass, exit 3 if any check
##               fails, or exit 2 on invalid usage.
sb_preflight() {
  local scope=${1:-} failed=0 server compose memory busy="" names name port floor
  px_compose_ports_load "$scope" || return $?
  if px_compose_resolve_engine; then
    px_tsv_row engine_binary ok "$_PXC_ENGINE"
  else
    px_tsv_row engine_binary fail "no container runtime, set PX_DOCKER_BIN"
    px_tsv_row engine_responsive fail "no runtime to ask"
    return "$SB_EXIT_ANOMALY"
  fi
  server="$(px_compose_engine 30 version --format '{{.Server.Version}}')" || server=""
  if [ -n "$server" ]; then
    px_tsv_row engine_responsive ok "server $server"
  else
    px_tsv_row engine_responsive fail "the engine did not answer a version request"
    failed=1
  fi
  floor="$PX_COMPOSE_ENGINE_FLOOR_MAJOR.$PX_COMPOSE_ENGINE_FLOOR_MINOR"
  if [ -n "$server" ]; then
    if px_compose_version_at_least "$server" \
      "$PX_COMPOSE_ENGINE_FLOOR_MAJOR" "$PX_COMPOSE_ENGINE_FLOOR_MINOR"; then
      px_tsv_row engine_version ok "$server"
    else
      px_tsv_row engine_version fail "engine $server is below the floor $floor"
      failed=1
    fi
  fi
  if px_compose_engine 30 info --format '{{.ID}}' >/dev/null 2>&1; then
    px_tsv_row engine_credentials ok "the client is authorised against the engine"
  else
    px_tsv_row engine_credentials fail "the engine refused an info request"
    failed=1
  fi
  if px_compose_resolve_flavour; then
    compose="$(px_compose_compose_version)" || compose=""
    if [ -n "$compose" ] &&
      px_compose_version_at_least "$compose" "$PX_COMPOSE_FLOOR_MAJOR" 0; then
      px_tsv_row compose_version ok "$_PXC_FLAVOUR $compose"
    else
      px_tsv_row compose_version fail \
        "compose ${compose:-unknown} is below the floor $PX_COMPOSE_FLOOR_MAJOR"
      failed=1
    fi
  else
    px_tsv_row compose_version fail "compose is not available"
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
  names="$(px_compose_port_names)"
  if [ -z "$names" ]; then
    px_tsv_row port_availability warn \
      "no generated port map is loaded, the generator of A2 writes it"
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
      px_tsv_row port_availability ok "every mapped loopback port is free"
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
# Because the container runtime exposes healthcheck status directly, the readiness barrier operates
# by polling that reported health state. The semantic definition of service health is encapsulated
# inside each container image's healthcheck (authored in A2), so this module inspects only the
# external health status reported by the engine rather than probing inside containers.
#
# Every readiness wait is bounded by a finite timeout and records the elapsed time at which each
# instance first transitions to ready during the wait rather than the container's total uptime. If
# any watched instance terminates with a non-zero exit code while the barrier is polling, the wait
# aborts immediately with an anomaly status rather than waiting for an inevitable timeout.
# Similarly, a role selection that matches no instances is rejected immediately because an empty
# barrier proves nothing. Which role groups are intentionally stopped is determined exactly once at
# the beginning of the wait: a group is considered intentionally stopped only if sb_group_stop
# recorded it in the scope record or if it is an `on-demand` group that has never been started.
# Container state alone never marks a group as intentionally stopped, ensuring that a crashed group
# cannot be mistaken for an intentionally parked one.

## @description  Look up the value associated with a key in an in-memory tab-separated list.
## @param        key, list  Provide the lookup key and the newline-delimited key-tab-value list.
## @return       Write the matching value to stdout and exit 0, or exit 6 when the key is absent.
px_compose_seen_get() {
  local key=${1:-} list=${2:-} line
  while IFS= read -r line; do
    case "$line" in
    "$key$PX_TAB"*)
      printf '%s\n' "${line#*"$PX_TAB"}"
      return 0
      ;;
    esac
  done <<EOF
$list
EOF
  return "$SB_EXIT_NO_OBJECT"
}

# Read the list of role groups that sb_group_stop recorded as intentionally stopped in the scope
# state record, formatted with leading and trailing spaces for substring matching.
## @description  Report the space-padded list of groups recorded as stopped in a scope.
## @param        scope_id  Identify the scope whose stopped-group record should be read.
## @return       Write the space-delimited group names to stdout and exit 0 (even if unrecorded).
px_compose_stopped_groups() {
  local groups
  groups="$(px_compose_record_get "${1:-}" stopped_groups || true)"
  printf ' %s \n' "$groups"
}

# Update the persisted list of intentionally stopped role groups in a scope record. sb_group_stop
# records the group with `add` before stopping its containers, and sb_group_start clears the entry
# with `remove` before starting them.
## @description  Add or remove a role group in the scope's persisted stopped_groups record.
## @param        scope_id, group, action  Pass the scope ID, group name, and action (add/remove).
## @return       Exit 0 on success, or exit 1 if the scope record cannot be written.
px_compose_stopped_mark() {
  local scope=${1:-} group=${2:-} action=${3:-} word out=""
  for word in $(px_compose_stopped_groups "$scope"); do
    [ "$word" != "$group" ] || continue
    out="$out $word"
  done
  if [ "$action" = add ]; then
    out="$out $group"
  fi
  px_compose_record_set "$scope" stopped_groups "${out# }"
}

# Determine which role groups must be included in a scope-wide readiness wait. A group remains
# active in the barrier if any of its members is currently running or restarting, or if any member
# is neither part of a group recorded by sb_group_stop nor an unstarted `on-demand` container still
# in the `created` state. Consequently, a group whose members crashed prior to the wait remains
# subject to the barrier rather than being silently skipped.
## @description  Report the space-padded list of role groups that participate in a scope-wide wait.
## @param        rows, stopped  Provide the raw inventory lines and the recorded stopped groups.
## @return       Write the active group names padded with spaces to stdout and exit 0.
px_compose_live_groups() {
  local rows=${1:-} stopped=${2:-} line live=" "
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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

# Check whether the current PX_PS_* instance belongs to an intentionally stopped role group that
# should be bypassed by the readiness barrier. Intentional group skipping applies only during a
# scope-wide wait (when the explicit role selection is empty); when specific roles are named, the
# barrier waits for every requested role unconditionally.
## @description  Test whether the current PX_PS_* instance is parked and skipped by a scope wait.
## @param        selection, up  Provide the space-separated role selection and active live groups.
## @return       Exit 0 when the barrier should skip the instance, or exit 1 when it must wait.
px_compose_parked() {
  local selection=${1:-} up=${2:-}
  [ -z "$selection" ] || return 1
  [ -n "$PX_PS_GROUP" ] || return 1
  case "$up" in
  *" $PX_PS_GROUP "*) return 1 ;;
  esac
  return 0
}

# Verify that a readiness wait targets at least one existing container instance. An empty selection
# requires the scope to contain at least one labeled container, while a non-empty selection
# requires every named role to match at least one container in the scope, preventing a misspelled
# role name or an unlabeled deployment from vacuously succeeding as ready.
## @description  Verify that a scope-wide or role-filtered selection matches existing instances.
## @param        scope_id, selection, rows  Pass the scope ID, role selection, and inventory rows.
## @return       Exit 0 when all selected roles match, or log the missing selection and exit 6.
px_compose_selection_found() {
  local scope=${1:-} selection=${2:-} rows=${3:-} roles=" " line word missing=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    roles="$roles$PX_PS_ROLE "
  done <<EOF
$rows
EOF
  if [ "$roles" = " " ]; then
    px_error "scope $scope holds no instance that carries its cluster label"
    return "$SB_EXIT_NO_OBJECT"
  fi
  for word in $selection; do
    case "$roles" in
    *" $word "*) ;;
    *) missing="$missing $word" ;;
    esac
  done
  if [ -n "$missing" ]; then
    px_error "no instance in scope $scope has the role:$missing"
    return "$SB_EXIT_NO_OBJECT"
  fi
  return 0
}

# Poll container readiness across the scope until all targeted instances report ready or the
# timeout expires. When called without explicit role arguments, the barrier waits for every
# instance in the scope except members of role groups that are intentionally stopped at the start
# of the wait (groups with no running or restarting members that were either recorded by
# sb_group_stop or configured as `on-demand` and never started). Those intentionally parked
# instances are reported with state `stopped` and not waited on, whereas every other group remains
# in the barrier. It exits 0 when all watched instances are ready, 3 if an instance terminates
# while waiting, 4 on timeout, 6 if the selection matches no instance, 1 if the runtime fails to
# respond, or 2 on invalid usage.
## @description  Wait until all selected instances in a scope reach readiness or fail.
## @param        scope_id   Identify the target scope to poll.
## @param        timeout_s  Specify the maximum wait duration in seconds.
## @param        role...    Optionally list specific role names to wait for instead of all roles.
## @return       Write readiness TSV rows to stdout and exit 0 when all are ready, exit 3 on
##               instance termination, 4 on timeout, 6 on no match, 1 on runtime failure, or 2.
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
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_READY 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  interval="$(px_compose_interval)"
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
      [ -n "$line" ] || continue
      px_compose_ps_split "$line" || continue
      px_compose_role_selected "$PX_PS_ROLE" "$roles" || continue
      px_compose_parked "$roles" "$up" && continue
      key="$PX_PS_ROLE/$PX_PS_INDEX"
      if px_compose_seen_get "$key" "$seen" >/dev/null; then
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
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    px_compose_role_selected "$PX_PS_ROLE" "$roles" || continue
    key="$PX_PS_ROLE/$PX_PS_INDEX"
    if out_elapsed="$(px_compose_seen_get "$key" "$seen")"; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" ready "$out_elapsed")$PX_NL"
    elif px_compose_parked "$roles" "$up"; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" stopped "$elapsed")$PX_NL"
    else
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" unready "$elapsed")$PX_NL"
    fi
  done <<EOF
$rows
EOF
  if [ -n "$out" ]; then
    printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  fi
  return "$status"
}

# ---------------------------------------------------------------------------
# 8. Cluster Lifecycle: Provisioning, Teardown, and Horizontal Scaling
# ---------------------------------------------------------------------------
# The rendered deployment directory is the sole integration boundary between the deployment
# generator and this backend. As specified in docs/backends.md, the backend consumes only
# `compose.yaml`, the service metadata labels declared within it, and `ports.generated.env` from
# that directory without assuming any generator-internal structure. Consequently, a hand-written
# test fixture provisions identically to a generator-rendered deployment.
#
# Provisioning first creates all containers without starting them and then starts every service
# except those carrying the `start=on-demand` label. On-demand services remain in the created,
# stopped state until explicitly activated via sb_group_start.
#
# Docker Compose creates and tears down an entire project in a single invocation, and when scaling
# a service down it removes the highest-numbered container replicas first, naturally preserving
# contiguous zero-based instance indices without extra renumbering logic in this module.

## @description  Locate the root compose.yaml file within a rendered deployment directory.
## @param        deployment_dir  Specify the path to the rendered deployment directory.
## @return       Write the compose.yaml path to stdout and exit 0, or exit 6 if it is absent.
px_compose_file_of() {
  local dir=${1:-}
  if [ -f "$dir/compose.yaml" ]; then
    printf '%s/compose.yaml\n' "$dir"
    return 0
  fi
  return "$SB_EXIT_NO_OBJECT"
}

# Create all container instances defined in a rendered deployment directory and start every service
# not marked `on-demand`, returning immediately without blocking for service readiness. Because the
# generated compose specification configures `restart: "no"`, any early daemon crash remains
# visible as a terminated container rather than being masked by automatic runtime restarts.
## @description  Create all containers from a deployment directory and start non-on-demand ones.
## @param        scope_id        Identify the target scope to provision.
## @param        deployment_dir  Specify the path to the rendered deployment directory.
## @param        timeout_s       Specify the provisioning timeout in seconds.
## @return       Write instance TSV rows to stdout and exit 0, exit 1 on creation failure, 2 on
##               invalid usage or directory, exit 3 on early termination, or exit 4 on timeout.
sb_provision() {
  local scope=${1:-} dir=${2:-} timeout=${3:-} file rows line name status=0 bad=""
  if [ -z "$dir" ]; then
    px_error "sb_provision needs a scope and a rendered deployment directory"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_PROVISION 900)" || return $?
  px_compose_scope_load "$scope" || return $?
  dir="$(px_realpath "$dir")" || dir=""
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    px_error "no such deployment directory: ${2:-}"
    return "$SB_EXIT_USAGE"
  fi
  file="$(px_compose_file_of "$dir")" || file=""
  if [ -z "$file" ]; then
    px_error "deployment directory $dir holds no compose.yaml"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_record_set "$scope" deployment_dir "$dir" || return "$SB_EXIT_FAIL"
  px_compose_record_set "$scope" compose_file "$file" || return "$SB_EXIT_FAIL"
  PX_COMPOSE_DEPLOY_DIR="$dir"
  PX_COMPOSE_FILE="$file"
  px_config_set PX_DEPLOYMENT_DIR "$dir" backend
  px_compose_ports_load "$scope" || return $?
  if px_compose_dry_run "provision $scope from $file"; then
    return 0
  fi
  px_compose_project "$scope" "$timeout" up --no-start --no-build >&2 || status=$?
  if px_compose_expired "$status"; then
    px_error "provision of $scope expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_error "compose could not create the instances of $scope"
    return "$SB_EXIT_FAIL"
  fi
  rows="$(px_compose_ps "$scope")" || return "$SB_EXIT_FAIL"
  if [ -z "$rows" ]; then
    px_error "the deployment in $dir creates no instance with the label" \
      "$(px_compose_selector "$scope")"
    return "$SB_EXIT_USAGE"
  fi
  # Build the positional argument array of non-on-demand service names from the newline-delimited
  # inventory output using `set --`.
  set --
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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
  if px_compose_expired "$status"; then
    px_error "provision of $scope expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_error "compose could not start the instances of $scope"
    return "$SB_EXIT_FAIL"
  fi
  rows="$(sb_list "$scope")" || return "$SB_EXIT_FAIL"
  printf '%s\n' "$rows"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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

# Tear down and remove all container instances belonging to a scope. Full teardown (`keep_data=0`)
# is the standard end-of-run cleanup path that also removes volumes, whereas `keep_data=1`
# preserves data volumes on disk for post-run diagnostics.
## @description  Stop and remove all containers in a scope, optionally preserving data volumes.
## @param        scope_id   Identify the target scope to tear down.
## @param        keep_data  Pass 0 to remove volumes or 1 to retain volumes for diagnostics.
## @return       Exit 0 when clean, exit 1 if containers or volumes remain, or exit 2 on bad usage.
sb_teardown() {
  local scope=${1:-} keep=${2:-} timeout grace rows volumes status=0
  case "$keep" in
  0 | 1) ;;
  *)
    px_error "sb_teardown needs keep_data of 0 or 1, got '$keep'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  px_compose_scope_load "$scope" || return $?
  if px_compose_dry_run "tear down $scope with keep_data=$keep"; then
    return 0
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_TEARDOWN 300)"
  grace="$(px_compose_seconds PX_TIMEOUT_STOP 120)"
  if [ "$keep" = 0 ]; then
    px_compose_project "$scope" "$timeout" down --volumes --remove-orphans \
      --timeout "$grace" >&2 || status=$?
  else
    px_compose_project "$scope" "$timeout" down --remove-orphans \
      --timeout "$grace" >&2 || status=$?
  fi
  if px_compose_expired "$status"; then
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
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Adjust the replica count of a scalable role within the supported range of 1 through 9 instances.
# The upper bound of 9 replicas is fixed by the harness port allocation scheme, which assigns ports
# as a base plus a per-index stride and reserves space for nine instances between adjacent debug
# port base ranges.
## @description  Scale a role to a target replica count between 1 and 9 and wait for readiness.
## @param        scope_id   Identify the target scope.
## @param        role       Specify the role name to scale.
## @param        count      Specify the desired replica count (1 to 9).
## @param        timeout_s  Specify the scaling and readiness timeout in seconds.
## @return       Write role instance TSV rows to stdout and exit 0, exit 1 on scale or runtime
##               failure, exit 2 if count is outside 1..9 or role is unscalable, or 4 on timeout.
sb_scale() {
  local scope=${1:-} role=${2:-} count=${3:-} timeout=${4:-} service status=0
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
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_SCALE 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  service="$(px_compose_service_of "$scope" "$role")" || status=$?
  if [ "$status" = "$SB_EXIT_FAIL" ]; then
    return "$SB_EXIT_FAIL"
  fi
  if [ -z "$service" ]; then
    px_error "role '$role' has no service in $PX_COMPOSE_PROJECT, so it cannot scale"
    return "$SB_EXIT_USAGE"
  fi
  status=0
  if px_compose_dry_run "scale $role to $count in $scope"; then
    return 0
  fi
  # Compose removes the highest-numbered containers first when scaling down, keeping zero-based
  # instance indices contiguous without manual reindexing. Passing the target service name with
  # --no-deps ensures all other services, including intentionally stopped groups, remain untouched.
  px_compose_project "$scope" "$timeout" up -d --no-recreate --no-build --no-deps \
    --scale "$service=$count" "$service" >&2 || status=$?
  if px_compose_expired "$status"; then
    px_error "scale of $role expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_error "compose could not scale $role to $count"
    return "$SB_EXIT_FAIL"
  fi
  sb_wait_ready "$scope" "$timeout" "$role" >/dev/null || status=$?
  if [ "$status" = "$SB_EXIT_TIMEOUT" ]; then
    sb_list "$scope" "$role"
    px_error "the new instances of $role did not become ready in ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  sb_list "$scope" "$role"
  if [ "$status" != 0 ]; then
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 9. In-Container Command Execution, Log Streaming, and File Transfer
# ---------------------------------------------------------------------------
# Workload drivers, health checks, RegionMover invocations, and in-cluster diagnostic tools all
# interact with cluster instances exclusively through the primitives in this section, keeping
# direct container runtime calls out of every other module in the repository.
#
# Because Compose commands select a service replica using a one-based `--index` flag, translation
# from the contract's zero-based instance index occurs at the invocation boundary. Conversely,
# because `compose logs` targets a whole service and prepends service name prefixes to every output
# line—whereas the contract requires the raw log stream of a single instance—log retrieval queries
# the underlying container engine directly using the resolved container ID.

# Execute an arbitrary command inside a single running instance, forwarding the command's stdout
# byte-for-byte without modification. Four high exit codes (124, 125, 126, and 127) are reserved
# for execution framing failures so callers can reliably distinguish an in-container command
# failure from a substrate or timeout failure.
## @description  Execute a command inside a specific running container instance.
## @param        scope_id   Identify the target scope.
## @param        role       Specify the target role name.
## @param        index      Specify the zero-based instance index.
## @param        timeout_s  Specify the execution timeout in seconds.
## @param        --         Separate sb_exec options from the command and its arguments.
## @return       Return the command exit status, 124 on timeout, 125 if exec cannot start or the
##               substrate fails, 126 if non-executable, 127 if missing, 2 on usage, or 6.
sb_exec() {
  local scope=${1:-} role=${2:-} index=${3:-} timeout=${4:-} separator
  local service number status=0
  if [ $# -lt 6 ]; then
    px_error "sb_exec needs a scope, a role, an index, a timeout, -- and a command"
    return "$SB_EXIT_USAGE"
  fi
  shift 4
  separator=$1
  shift
  if [ "$separator" != "--" ]; then
    px_error "sb_exec separates the command with --, got '$separator'"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_EXEC 600)" || return $?
  px_compose_instance "$scope" "$role" "$index" || status=$?
  case "$status" in
  0) ;;
  "$SB_EXIT_USAGE" | "$SB_EXIT_NO_OBJECT") return "$status" ;;
  *)
    px_error "the substrate did not answer, so the exec in $role/$index cannot start"
    return "$SB_EXEC_NOSTART"
    ;;
  esac
  # Resolve the Compose flavour in the current shell before invoking exec so a resolution failure
  # returns SB_EXEC_NOSTART (125) instead of being indistinguishable from a command exit code of 1.
  px_compose_resolve_flavour || return "$SB_EXEC_NOSTART"
  service="$PX_PS_SERVICE"
  if [ -z "$service" ]; then
    service="$role"
  fi
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

## @description  Stream raw container log bytes for a specific instance to stdout.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @param        since_s   Specify the lookback window in whole seconds, or - for the full log.
## @param        previous  Pass 0 for the current container or 1 for the previous container.
## @param        follow    Pass 1 to stream live log output continuously or 0 for a one-shot read.
## @return       Write raw log bytes to stdout and exit 0, exit 1 on runtime read failure, exit 5
##               when previous=1 (unsupported), exit 6 if the instance is absent, or 2 on usage.
sb_logs() {
  local scope=${1:-} role=${2:-} index=${3:-} since=${4:--} previous=${5:-0}
  local follow=${6:-0} timeout status=0
  case "$previous" in
  0 | 1) ;;
  *)
    px_error "sb_logs needs previous of 0 or 1, got '$previous'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  case "$follow" in
  0 | 1) ;;
  *)
    px_error "sb_logs needs follow of 0 or 1, got '$follow'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  case "$since" in
  - | '') since="" ;;
  *[!0-9]*)
    px_error "sb_logs needs since in whole seconds or -, got '${4:-}'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
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
    timeout="$(px_compose_seconds PX_TIMEOUT_DEFAULT 300)"
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

## @description  Copy a local file or directory from the host into a container instance.
## @param        scope_id     Identify the target scope.
## @param        role         Specify the target role name.
## @param        index        Specify the zero-based instance index.
## @param        local_path   Specify the existing source path on the host filesystem.
## @param        remote_path  Specify the destination path inside the container instance.
## @return       Exit 0 on success, exit 1 if the copy fails, exit 2 on invalid usage or a missing
##               local_path, or exit 6 if the target instance does not exist.
sb_copy_in() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-}
  local service number timeout status=0
  if [ -z "$source" ] || [ -z "$target" ]; then
    px_error "sb_copy_in needs a scope, a role, an index, a local path and a remote path"
    return "$SB_EXIT_USAGE"
  fi
  if [ ! -e "$source" ]; then
    px_error "no such local path: $source"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_instance "$scope" "$role" "$index" || return $?
  service="${PX_PS_SERVICE:-$role}"
  number="$(px_compose_number)"
  if px_compose_dry_run "copy $source into $role/$index at $target"; then
    return 0
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_DEFAULT 300)"
  px_compose_project "$scope" "$timeout" cp --index="$number" \
    "$source" "$service:$target" >&2 || status=$?
  if [ "$status" != 0 ]; then
    px_error "the copy into $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Retrieve a file or directory artifact from a container instance to the host filesystem. The local
# destination path is validated against the PX_OUT output policy before transfer so that retrieved
# artifacts can never be written into the repository working tree; this function serves as the
# lowest-level enforcement boundary for that policy.
## @description  Copy a file or directory artifact from a container instance into PX_OUT.
## @param        scope_id     Identify the target scope.
## @param        role         Specify the target role name.
## @param        index        Specify the zero-based instance index.
## @param        remote_path  Specify the source path inside the container instance.
## @param        local_path   Specify the destination path on the host under PX_OUT.
## @return       Exit 0 on success, exit 1 if the copy fails, exit 2 on invalid usage or a target
##               outside PX_OUT, or exit 6 if the instance does not exist.
sb_copy_out() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-}
  local service number timeout resolved parent status=0
  if [ -z "$source" ] || [ -z "$target" ]; then
    px_error "sb_copy_out needs a scope, a role, an index, a remote path and a local path"
    return "$SB_EXIT_USAGE"
  fi
  # px_out_resolve terminates the command-substitution subshell with SB_EXIT_USAGE (2) whenever the
  # target path escapes the PX_OUT root, propagating that status directly to the caller.
  resolved="$(px_out_resolve "$target")" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  px_compose_instance "$scope" "$role" "$index" || return $?
  service="${PX_PS_SERVICE:-$role}"
  number="$(px_compose_number)"
  if px_compose_dry_run "copy $source out of $role/$index to $resolved"; then
    return 0
  fi
  parent="${resolved%/*}"
  if [ -n "$parent" ] && [ ! -d "$parent" ]; then
    mkdir -p "$parent" || {
      px_error "cannot create the destination directory $parent"
      return "$SB_EXIT_FAIL"
    }
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_DEFAULT 300)"
  px_compose_project "$scope" "$timeout" cp --index="$number" \
    "$service:$source" "$resolved" >&2 || status=$?
  if [ "$status" != 0 ]; then
    px_error "the copy out of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  return 0
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

## @description  List raw pipe-delimited inventory lines for all containers carrying a group label.
## @param        scope_id, group  Identify the target scope and the role group name.
## @return       Write one raw inventory line per container to stdout and exit 0 (or ps status).
px_compose_group_rows() {
  local scope=${1:-} group=${2:-} prefix
  prefix="$(px_compose_label_prefix)"
  px_compose_ps "$scope" --filter "label=$prefix.group=$group"
}

# Extract the deduplicated list of Compose service names or contract role names belonging to a role
# group from a single inventory query. Compose lifecycle commands target service names while
# contract readiness checks target role names, so both projections are derived from the same pass.
## @description  List the unique Compose service names or contract role names in a role group.
## @param        scope_id, group, field  Pass the scope ID, group name, and field (service/role).
## @return       Write one distinct name per line to stdout and exit 0 (or return the ps status).
px_compose_group_names() {
  local scope=${1:-} group=${2:-} field=${3:-service} rows line name seen=""
  rows="$(px_compose_group_rows "$scope" "$group")" || return $?
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    if [ "$field" = role ]; then
      name="$PX_PS_ROLE"
    else
      name="${PX_PS_SERVICE:-$PX_PS_ROLE}"
    fi
    [ -n "$name" ] || continue
    case "$PX_NL$seen" in
    *"$PX_NL$name$PX_NL"*) continue ;;
    esac
    seen="$seen$name$PX_NL"
    printf '%s\n' "$name"
  done <<EOF
$rows
EOF
}

## @description  Stop all instances in a role group while preserving their containers and volumes.
## @param        scope_id   Identify the target scope.
## @param        group      Specify the role group label to stop.
## @param        timeout_s  Specify the stop timeout in seconds.
## @return       Write stopped TSV rows to stdout and exit 0, exit 1 if an instance fails to stop
##               or the runtime does not respond, exit 2 for an unknown group, or 4 on timeout.
sb_group_stop() {
  local scope=${1:-} group=${2:-} timeout=${3:-} services rows line grace
  local before="" out="" still=0 status=0 name
  if [ -z "$group" ]; then
    px_error "sb_group_stop needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  services="$(px_compose_group_names "$scope" "$group" service)" || return "$SB_EXIT_FAIL"
  if [ -z "$services" ]; then
    px_error "no instance in scope $scope carries the group label '$group'"
    return "$SB_EXIT_USAGE"
  fi
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    before="$before$PX_PS_ROLE/$PX_PS_INDEX$PX_TAB$PX_PS_STATE$PX_NL"
  done <<EOF
$rows
EOF
  if px_compose_dry_run "stop the group $group in $scope"; then
    return 0
  fi
  grace="$(px_compose_seconds PX_TIMEOUT_STOP 120)"
  # Record the group as intentionally stopped so subsequent scope-wide readiness barriers skip it.
  px_compose_stopped_mark "$scope" "$group" add || return "$SB_EXIT_FAIL"
  # Populate the positional argument list from the newline-delimited service names using `set --`.
  set --
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    set -- "$@" "$name"
  done <<EOF
$services
EOF
  px_compose_project "$scope" "$timeout" stop --timeout "$grace" "$@" >&2 || status=$?
  if px_compose_expired "$status"; then
    px_error "the stop of group $group expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    px_compose_ps_split "$line" || continue
    name="$(px_compose_seen_get "$PX_PS_ROLE/$PX_PS_INDEX" "$before")" || name=""
    if [ "$PX_PS_STATE" = running ]; then
      still=$((still + 1))
      continue
    fi
    if [ "$name" = running ]; then
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" stopped)$PX_NL"
    else
      out="$out$(px_tsv_row "$PX_PS_ROLE" "$PX_PS_INDEX" already_stopped)$PX_NL"
    fi
  done <<EOF
$rows
EOF
  if [ -n "$out" ]; then
    printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  fi
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

# Start all containers belonging to a stopped or on-demand role group and block until every member
# instance reaches readiness, completing the on-demand group lifecycle switch.
## @description  Start all instances of a role group and wait for them to become ready.
## @param        scope_id   Identify the target scope.
## @param        group      Specify the role group label to start.
## @param        timeout_s  Specify the start and readiness timeout in seconds.
## @return       Write ready TSV rows to stdout and exit 0 when all are ready, exit 1 on start or
##               runtime failure, exit 2 on unknown group, exit 3 on termination, or 4 on timeout.
sb_group_start() {
  local scope=${1:-} group=${2:-} timeout=${3:-} services roles name status=0
  if [ -z "$group" ]; then
    px_error "sb_group_start needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(px_compose_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  px_compose_scope_load "$scope" || return $?
  services="$(px_compose_group_names "$scope" "$group" service)" || return "$SB_EXIT_FAIL"
  if [ -z "$services" ]; then
    px_error "no instance in scope $scope carries the group label '$group'"
    return "$SB_EXIT_USAGE"
  fi
  # Reject an empty resolved role list explicitly rather than passing no role arguments to
  # sb_wait_ready, which would inadvertently widen the readiness wait to the entire scope.
  roles="$(px_compose_group_names "$scope" "$group" role)" || return "$SB_EXIT_FAIL"
  if [ -z "$roles" ]; then
    px_error "the group $group in scope $scope names no role to wait for"
    return "$SB_EXIT_FAIL"
  fi
  if px_compose_dry_run "start the group $group in $scope"; then
    return 0
  fi
  px_compose_stopped_mark "$scope" "$group" remove || return "$SB_EXIT_FAIL"
  set --
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    set -- "$@" "$name"
  done <<EOF
$services
EOF
  px_compose_project "$scope" "$timeout" start "$@" >&2 || status=$?
  if px_compose_expired "$status"; then
    px_error "the start of group $group expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_error "compose could not start the group $group"
    return "$SB_EXIT_FAIL"
  fi
  set --
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    set -- "$@" "$name"
  done <<EOF
$roles
EOF
  sb_wait_ready "$scope" "$timeout" "$@" || status=$?
  return "$status"
}

# Report the aggregate runtime status (`up`, `down`, or `partial`) of a role group so test steps
# can cleanly skip optional workloads when their backing group is intentionally stopped.
## @description  Report the running and desired instance counts and state for a role group.
## @param        scope_id  Identify the target scope.
## @param        group     Specify the role group label to inspect.
## @return       Write one group TSV row to stdout and exit 0, exit 1 if the runtime does not
##               respond, or exit 2 if the group is unknown.
sb_group_status() {
  local scope=${1:-} group=${2:-} rows line running=0 desired=0 state
  if [ -z "$group" ]; then
    px_error "sb_group_status needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_group_rows "$scope" "$group")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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
# Fault-injection operations terminate or perturb instances abruptly by design. A `hard` kill sends
# SIGKILL while leaving the container object intact so that its restart count and termination
# reason remain inspectable afterward, whereas a `delete` kill removes the container object
# entirely to exercise the backend's recreation semantics. Callers must record injected faults as
# planned events because post-test cluster health checks treat any unexplained container restart as
# a fatal anomaly.
#
# The drain-aware restart provides the graceful maintenance counterpart: before stopping an
# instance, it evacuates hosted HBase regions using the RegionMover utility, and after the
# restarted instance passes the readiness barrier, it reloads the evacuated regions. Because the
# container image bundling RegionMover is delivered in A2, the mover command template is
# parameterized here, while a role's eligibility for draining is governed strictly by its
# `drain=regionmover` label.

## @description  Terminate a single container instance abruptly via a hard kill or object removal.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @param        mode      Pass `hard` to retain the container object or `delete` to remove it.
## @return       Exit 0 on success, exit 1 on termination or runtime failure, exit 2 for an unknown
##               kill mode, or exit 6 if the instance does not exist.
sb_kill() {
  local scope=${1:-} role=${2:-} index=${3:-} mode=${4:-} timeout status=0
  if ! sb_valid_value kill_mode "$mode"; then
    px_error "sb_kill needs a mode of $(sb_vocabulary kill_mode), got '$mode'"
    return "$SB_EXIT_USAGE"
  fi
  px_compose_instance "$scope" "$role" "$index" || return $?
  if px_compose_dry_run "$mode kill of $role/$index in $scope"; then
    return 0
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_STOP 120)"
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

# Deliver a POSIX signal to a container instance for non-destructive fault injection and diagnostic
# collection, such as QUIT to trigger a JVM thread dump, STOP and CONT to simulate an extended
# pause, or TERM to initiate a graceful daemon shutdown.
## @description  Deliver a named signal to a specific container instance.
## @param        scope_id     Identify the target scope.
## @param        role         Specify the target role name.
## @param        index        Specify the zero-based instance index.
## @param        signal_name  Specify the signal name, with or without a leading SIG prefix.
## @return       Exit 0 on delivery, exit 1 on delivery or runtime failure, exit 2 for an unknown
##               signal, exit 5 if signal_delivery is unsupported, or exit 6 if absent.
sb_signal() {
  local scope=${1:-} role=${2:-} index=${3:-} signal=${4:-} name timeout status=0
  local supported
  name="${signal#SIG}"
  name="$(px_compose_upper "$name")"
  case "$name" in
  HUP | INT | QUIT | ABRT | KILL | ALRM | TERM | USR1 | USR2 | STOP | CONT | TSTP) ;;
  *)
    px_error "sb_signal does not accept the signal name '$signal'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  # Resolve the target instance before checking the capability cache so that an unreachable runtime
  # fails with exit code 1 rather than being misreported as exit code 5 (unsupported) from a failed
  # capability probe.
  px_compose_instance "$scope" "$role" "$index" || return $?
  supported="$(sb_capability signal_delivery)" || true
  if [ "$supported" = no ]; then
    px_error "the runtime declares no signal delivery, so $name is not sent"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  if px_compose_dry_run "signal $name to $role/$index in $scope"; then
    return 0
  fi
  timeout="$(px_compose_seconds PX_TIMEOUT_STOP 120)"
  px_compose_engine "$timeout" kill -s "$name" "$PX_PS_ID" >/dev/null || status=$?
  if [ "$status" != 0 ]; then
    px_error "the signal $name to $role/$index was not delivered"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Render the RegionMover command line for an `unload` or `load` operation by substituting
# `{operation}`, `{host}`, `{port}`, `{timeout}`, and `{file}` into the active command template
# (using PX_REGION_MOVER_CMD when set and non-empty, or PX_COMPOSE_MOVER by default). When the port
# map defines no RPC port (`port` is empty) and the template requires `{port}`, return exit code 6.
## @description  Render the RegionMover command string for an unload or load operation.
## @param        operation, host, port, timeout_s, file  Supply the five placeholder values.
## @return       Write the command line to stdout and exit 0, or exit 6 if a needed port is empty.
px_compose_mover_cmd() {
  local operation=${1:-} host=${2:-} port=${3:-} timeout=${4:-} file=${5:-} cmd
  cmd="${PX_REGION_MOVER_CMD:-$PX_COMPOSE_MOVER}"
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

## @description  Emit a single timing and status TSV row for one phase of a compound operation.
## @param        phase, status, start_ms  Give the phase name, status (ok/fail/skip), and start ms.
## @return       Write the formatted phase TSV row to stdout and exit 0.
px_compose_phase() {
  px_tsv_row "${1:-}" "${2:-}" "$(($(px_epoch_ms) - ${3:-0}))"
}

# Perform a graceful drain-aware rolling restart of a single region-hosting instance across five
# sequential phases: `drain`, `stop`, `start`, `ready`, and `rebalance`. One TSV row is emitted for
# each phase, and any downstream phase rendered moot by an earlier failure is reported as `skip`.
# Only roles carrying the deployment label `<prefix>.drain=regionmover` support this operation,
# because the deployment manifest declares which roles host HBase regions; if the role lacks drain
# semantics or the port map holds no RPC port for the instance, exit code 2 is returned.
## @description  Perform a five-phase drain-aware restart of a region-hosting instance.
## @param        scope_id         Identify the target scope.
## @param        role             Specify the target role name (must carry drain=regionmover).
## @param        index            Specify the zero-based instance index.
## @param        drain_timeout_s  Specify the timeout in seconds for drain, stop, and rebalance.
## @param        ready_timeout_s  Specify the timeout in seconds for start and readiness.
## @return       Write five phase TSV rows to stdout and exit 0 on success, exit 1 on phase or
##               runtime failure, 4 on timeout, 2 if undrainable or unmapped, or 6 if absent.
sb_restart_drain() {
  local scope=${1:-} role=${2:-} index=${3:-} drain=${4:-} ready=${5:-}
  local host port file cmd container grace start status=0 outcome=0
  drain="$(px_compose_timeout_arg "$drain" PX_TIMEOUT_DRAIN 600)" || return $?
  ready="$(px_compose_timeout_arg "$ready" PX_TIMEOUT_READY 600)" || return $?
  px_compose_instance "$scope" "$role" "$index" || return $?
  if [ "$PX_PS_DRAIN" != regionmover ]; then
    px_error "role '$role' has no drain semantics: the deployment gives it no" \
      "$(px_compose_label_prefix).drain=regionmover label"
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
  cmd="$(px_compose_mover_cmd unload "$host" "$port" "$drain" "$file")" || cmd=""
  if [ -z "$cmd" ]; then
    px_error "the port map holds no rpc port for $role/$index, so the mover cannot name it"
    return "$SB_EXIT_USAGE"
  fi
  if px_compose_dry_run "drain-aware restart of $role/$index in $scope"; then
    return 0
  fi
  grace="$(px_compose_seconds PX_TIMEOUT_STOP 120)"

  # Redirect RegionMover's standard output to stderr so its progress messages do not corrupt the
  # structured phase TSV rows emitted on this function's stdout.
  start="$(px_epoch_ms)"
  sb_exec "$scope" "$role" "$index" "$drain" -- sh -c "$cmd" >&2 || status=$?
  if [ "$status" = "$SB_EXEC_TIMEOUT" ]; then
    px_compose_phase drain fail "$start"
    px_compose_phase stop skip "$start"
    px_compose_phase start skip "$start"
    px_compose_phase ready skip "$start"
    px_compose_phase rebalance skip "$start"
    px_error "the region drain of $role/$index expired after ${drain}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_compose_phase drain fail "$start"
    px_compose_phase stop skip "$start"
    px_compose_phase start skip "$start"
    px_compose_phase ready skip "$start"
    px_compose_phase rebalance skip "$start"
    px_error "the region drain of $role/$index failed with status $status"
    return "$SB_EXIT_FAIL"
  fi
  px_compose_phase drain ok "$start"

  start="$(px_epoch_ms)"
  status=0
  px_compose_engine "$drain" stop --time "$grace" "$container" >/dev/null || status=$?
  if [ "$status" != 0 ]; then
    px_compose_phase stop fail "$start"
    px_compose_phase start skip "$start"
    px_compose_phase ready skip "$start"
    px_compose_phase rebalance skip "$start"
    px_error "the stop of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  px_compose_phase stop ok "$start"

  start="$(px_epoch_ms)"
  status=0
  px_compose_engine "$ready" start "$container" >/dev/null || status=$?
  if [ "$status" != 0 ]; then
    px_compose_phase start fail "$start"
    px_compose_phase ready skip "$start"
    px_compose_phase rebalance skip "$start"
    px_error "the start of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  px_compose_phase start ok "$start"

  start="$(px_epoch_ms)"
  status=0
  sb_wait_ready "$scope" "$ready" "$role" >/dev/null || status=$?
  if [ "$status" != 0 ]; then
    px_compose_phase ready fail "$start"
    px_compose_phase rebalance skip "$start"
    px_error "$role/$index did not become ready in ${ready}s"
    if [ "$status" = "$SB_EXIT_TIMEOUT" ]; then
      return "$SB_EXIT_TIMEOUT"
    fi
    return "$SB_EXIT_FAIL"
  fi
  px_compose_phase ready ok "$start"

  start="$(px_epoch_ms)"
  status=0
  cmd="$(px_compose_mover_cmd load "$host" "$port" "$drain" "$file")"
  sb_exec "$scope" "$role" "$index" "$drain" -- sh -c "$cmd" >&2 || status=$?
  if [ "$status" = "$SB_EXEC_TIMEOUT" ]; then
    px_compose_phase rebalance fail "$start"
    px_error "the rebalance of $role/$index expired after ${drain}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  if [ "$status" != 0 ]; then
    px_compose_phase rebalance fail "$start"
    px_error "the rebalance of $role/$index failed with status $status"
    outcome=$SB_EXIT_FAIL
  else
    px_compose_phase rebalance ok "$start"
  fi
  return "$outcome"
}

# ---------------------------------------------------------------------------
# 12. Diagnostic Evidence: Restarts, Termination, OOM, Events, and Resource Samples
# ---------------------------------------------------------------------------
# Because a test run fails immediately upon detecting an unplanned restart or an out-of-memory
# kill, failure evidence must be unambiguous. Container inspection answers restart count,
# termination reason, and OOM status for the current container instance, which represents the full
# extent of state retained by Docker Compose: replaced containers leave no historical record, so
# querying a previous container's termination reason is unsupported and callers rely on the restart
# count instead.
#
# Termination reasons are normalized into a backend-independent vocabulary so cluster health checks
# can evaluate a single canonical status across all substrates.

# Query one or more container inspection attributes in a single `inspect` call using a Go format
# template. Because callers resolve and verify the target instance via px_compose_instance before
# calling this helper, a non-zero exit from `inspect` always indicates a runtime communication
# failure rather than a missing container object.
## @description  Inspect a resolved container ID with a Go template in a single runtime call.
## @param        container_id, template  Provide the resolved container ID and Go format template.
## @return       Write the rendered template to stdout and exit 0, or exit 1 on runtime failure.
px_compose_inspect() {
  local container=${1:-} template=${2:-} out status=0
  out="$(px_compose_engine 30 inspect --format "$template" "$container")" || status=$?
  if [ "$status" != 0 ]; then
    px_error "the runtime did not answer an inspection of $container"
    return "$SB_EXIT_FAIL"
  fi
  printf '%s\n' "$out"
}

# Report how many times the container runtime has restarted a given instance. Test runs snapshot
# this counter before and after workload execution, and any unplanned increase fails the run with
# the anomaly exit code.
## @description  Report the runtime restart count for a specific container instance.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @return       Write the integer restart count to stdout and exit 0, exit 1 if the runtime does
##               not respond, exit 2 on invalid usage, or exit 6 if the instance is absent.
sb_restart_count() {
  local count
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  count="$(px_compose_inspect "$PX_PS_ID" '{{.RestartCount}}')" || return $?
  case "$count" in
  '' | *[!0-9]*) count=0 ;;
  esac
  printf '%s\n' "$count"
}

# Report the normalized termination status, exit code, signal number, and finish timestamp for an
# instance using the shared contract vocabulary. Querying `previous` is unsupported on Docker
# Compose because the engine retains no record of replaced container instances.
## @description  Report the normalized termination evidence for a container instance.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @param        which     Select `current` (supported) or `previous` (unsupported on Compose).
## @return       Write one termination TSV row to stdout and exit 0, exit 1 if the runtime does not
##               respond, exit 2 on bad usage, exit 5 if which=previous, or exit 6 if absent.
sb_termination_reason() {
  local which=${4:-current} fields state code oom finished reason signal=""
  if ! sb_valid_value term_which "$which"; then
    px_error "sb_termination_reason needs current or previous, got '$which'"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$which" = previous ]; then
    px_error "Compose keeps no previous container, so read sb_restart_count instead"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  fields="$(px_compose_inspect "$PX_PS_ID" \
    '{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.FinishedAt}}')" ||
    return $?
  state="${fields%%|*}" && fields="${fields#*|}"
  code="${fields%%|*}" && fields="${fields#*|}"
  oom="${fields%%|*}" && fields="${fields#*|}"
  finished="${fields%%|*}"
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

# Report a definitive yes/no determination of whether an instance was terminated by the kernel
# out-of-memory killer, since any OOM event must fail the test run immediately. The runtime's
# `State.OOMKilled` flag is authoritative, while the exit code (such as a SIGKILL exit status from
# signal 9) and error message are included as corroborating evidence.
## @description  Report whether a container instance was terminated by an out-of-memory kill.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @return       Write one OOM evidence TSV row to stdout and exit 0, exit 1 if the runtime does
##               not respond, exit 2 on invalid usage, or exit 6 if the instance is absent.
sb_oom_killed() {
  local fields oom code error evidence
  px_compose_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  fields="$(px_compose_inspect "$PX_PS_ID" \
    '{{.State.OOMKilled}}|{{.State.ExitCode}}|{{.State.Error}}')" || return $?
  oom="${fields%%|*}" && fields="${fields#*|}"
  code="${fields%%|*}" && fields="${fields#*|}"
  error="$fields"
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

# Query bounded container, network, and volume lifecycle events for a scope to populate the run
# timeline. Because `docker events` streams indefinitely unless bounded, the query window is closed
# at the current epoch timestamp (`--until "$now"`).
## @description  Report chronological runtime lifecycle events for a scope over a lookback window.
## @param        scope_id  Identify the target scope.
## @param        since_s   Specify the lookback duration in whole seconds prior to the call.
## @return       Write event TSV rows to stdout (oldest first) and exit 0, exit 5 if runtime events
##               are unavailable, or exit 2 on invalid usage.
sb_events() {
  local scope=${1:-} since=${2:-} selector prefix fmt now out line status=0
  local nanos role label number index type reason message
  case "$since" in
  '' | *[!0-9]*)
    px_error "sb_events needs a window in whole seconds, got '${2:-}'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  selector="$(px_compose_selector "$scope")" || return $?
  prefix="$(px_compose_label_prefix)"
  now="$(px_epoch_s)"
  fmt="{{.TimeNano}}|{{index .Actor.Attributes \"$prefix.role\"}}"
  fmt="$fmt|{{index .Actor.Attributes \"$prefix.index\"}}"
  fmt="$fmt|{{index .Actor.Attributes \"com.docker.compose.container-number\"}}"
  fmt="$fmt|{{.Type}}|{{.Action}}|{{index .Actor.Attributes \"name\"}}"
  out="$(px_compose_engine 60 events --since "${since}s" --until "$now" \
    --filter "label=$selector" --format "$fmt")" || status=$?
  if [ "$status" != 0 ]; then
    px_error "the runtime did not report its events"
    return "$SB_EXIT_UNSUPPORTED"
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    nanos="${line%%|*}" && line="${line#*|}"
    role="${line%%|*}" && line="${line#*|}"
    label="${line%%|*}" && line="${line#*|}"
    number="${line%%|*}" && line="${line#*|}"
    type="${line%%|*}" && line="${line#*|}"
    reason="${line%%|*}" && line="${line#*|}"
    message="$line"
    case "$nanos" in
    '' | *[!0-9]*) continue ;;
    esac
    # Container events correspond to a specific instance and resolve their zero-based index through
    # the same parser used by inventory queries, whereas network and volume events carry no index.
    index=""
    if [ "$type" = container ]; then
      px_compose_index_of "$label" "$number" "$message"
      index="$PX_COMPOSE_INDEX"
    fi
    px_tsv_row "$((nanos / 1000000))" "$role" "$index" "$type" "$reason" \
      "$(px_tsv_clean "$message")"
  done <<EOF
$out
EOF
  return 0
}

# Convert a human-readable size string emitted by `docker stats` into an integer byte count. The
# runtime formats memory quantities with binary units (such as `1.5GiB`) and network or block I/O
# counters with decimal units (such as `1.2kB`), so both unit families are supported. Because shell
# arithmetic is integer-only, fractional values are scaled by 1000 before multiplying by the unit
# factor and dividing back down.
## @description  Convert a human-formatted binary or decimal size string into an integer byte count.
## @param        text  Provide the formatted size string (for example, 1.5GiB or 1.2kB).
## @return       Write the integer byte count to stdout and exit 0.
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

# Capture a point-in-time resource utilization snapshot across all running instances in a scope.
# Because the metrics collector invokes this function periodically and the health monitor calls it
# every thirty seconds, the entire snapshot is gathered using one batched `stats` call and one
# batched `inspect` call (for configured memory limits), avoiding per-instance runtime invocations.
## @description  Capture a single resource usage snapshot for all running instances in a scope.
## @param        scope_id  Identify the target scope to sample.
## @return       Write resource sample TSV rows to stdout and exit 0, exit 5 if no metrics source
##               is available, or exit 2 on invalid usage.
sb_sample_resources() {
  local scope=${1:-} rows line names="" limits="" stats ts status=0
  local name cpu mem net blk used limit rx tx blk_read blk_write
  px_compose_scope_load "$scope" || return $?
  rows="$(px_compose_ps "$scope" --filter status=running)" ||
    return "$SB_EXIT_UNSUPPORTED"
  if [ -z "$rows" ]; then
    return 0
  fi
  set --
  while IFS= read -r line; do
    [ -n "$line" ] || continue
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
  limits="$(px_compose_engine 60 inspect \
    --format '{{.Name}}|{{.HostConfig.Memory}}' "$@")" || limits=""
  ts="$(px_epoch_ms)"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    name="${line%%|*}" && line="${line#*|}"
    cpu="${line%%|*}" && line="${line#*|}"
    mem="${line%%|*}" && line="${line#*|}"
    net="${line%%|*}" && line="${line#*|}"
    blk="$line"
    used="$(px_compose_bytes "${mem%%/*}")"
    limit="$(px_compose_limit_of "$name" "$limits")"
    if [ "$limit" = 0 ]; then
      limit="$(px_compose_bytes "${mem##*/}")"
    fi
    rx="$(px_compose_bytes "${net%%/*}")"
    tx="$(px_compose_bytes "${net##*/}")"
    blk_read="$(px_compose_bytes "${blk%%/*}")"
    blk_write="$(px_compose_bytes "${blk##*/}")"
    px_tsv_row "$ts" "$(px_compose_identity_of "$name" "$names" role)" \
      "$(px_compose_identity_of "$name" "$names" index)" "${cpu%\%}" \
      "$used" "$limit" "$rx" "$tx" "$blk_read" "$blk_write"
  done <<EOF
$stats
EOF
  return 0
}

# Look up the configured memory limit in bytes for a container from batched `docker inspect`
# output, stripping any leading slash that `inspect` prepends to container names before comparing.
## @description  Extract the configured memory limit in bytes for a container from inspect lines.
## @param        name, list  Provide the container name and pipe-delimited name|limit lines.
## @return       Write the memory limit in bytes to stdout and exit 0.
px_compose_limit_of() {
  local name=${1:-} list=${2:-} line field value
  while IFS= read -r line; do
    field="${line%%|*}"
    value="${line#*|}"
    if [ "${field#/}" = "${name#/}" ]; then
      case "$value" in
      '' | *[!0-9]*) value=0 ;;
      esac
      printf '%s\n' "$value"
      return 0
    fi
  done <<EOF
$list
EOF
  printf '0\n'
}

# Resolve either the role name or the zero-based instance index for a container name using the
# tab-separated lookup table collected during the inventory pass of the current resource sample.
## @description  Look up the role or zero-based index for a container name from a sample map.
## @param        name, list, field  Provide the container name, name-to-identity map, and field.
## @return       Write the requested role or index value to stdout and exit 0.
px_compose_identity_of() {
  local name=${1:-} list=${2:-} field=${3:-role} value
  value="$(px_compose_seen_get "${name#/}" "$list")" || value=""
  if [ "$field" = index ]; then
    case "$value" in
    */*) printf '%s\n' "${value##*/}" ;;
    *) printf '\n' ;;
    esac
    return 0
  fi
  printf '%s\n' "${value%%/*}"
}

# ---------------------------------------------------------------------------
# 13. Service Endpoints and Container Image References
# ---------------------------------------------------------------------------
# Because Docker Compose publishes host ports when creating the deployment, endpoint resolution
# does not need to spawn a port-forwarding process and endpoint release is a no-op. Published host
# ports are read exclusively from the generated port map—which assigns each zero-based instance
# index a port equal to a role base plus a fixed stride—so the backend never computes port
# arithmetic itself and port definitions have a single source of truth.
#
# The port map is represented as a set of environment variables written by the A2 generator to
# `ports.generated.env` at the root of the deployment directory. The configuration loader in
# lib/common.sh imports and exports those variables into the process environment, reducing endpoint
# resolution here to an indirect variable lookup rather than repeated file parsing.

# Construct the environment variable name for a published service port by combining `PX_PORT_`, the
# uppercased role name, the uppercased port name, and (when provided) the zero-based instance
# index, matching the naming convention emitted by the deployment generator.
## @description  Construct the PX_PORT_* variable name for a role, port name, and optional index.
## @param        role, port_name, index  Provide the role name, port name, and optional index.
## @return       Write the constructed environment variable name to stdout and exit 0.
px_compose_port_name() {
  local role=${1:-} port=${2:-} index=${3:-} name
  name="PX_PORT_$(px_compose_upper "$role")_$(px_compose_upper "$port")"
  if [ -n "$index" ]; then
    name="${name}_$index"
  fi
  printf '%s\n' "$name"
}

# Resolve the published loopback port number for a role, port name, and instance index from the
# loaded port map environment variables. The indexed variable name takes precedence, falling back
# to the unindexed variable name used by single-instance roles.
## @description  Look up the published host port number for a role, port name, and instance index.
## @param        role, port_name, index  Specify the target role name, port name, and index.
## @return       Write the numeric port to stdout and exit 0, or exit 6 if no mapping exists.
px_compose_port_value() {
  local role=${1:-} port=${2:-} index=${3:-} name value
  name="$(px_compose_port_name "$role" "$port" "$index")"
  value="${!name:-}"
  if [ -z "$value" ]; then
    name="$(px_compose_port_name "$role" "$port" "")"
    value="${!name:-}"
  fi
  case "$value" in
  '' | *[!0-9]*) return "$SB_EXIT_NO_OBJECT" ;;
  esac
  printf '%s\n' "$value"
}

# Report the host-reachable loopback address (`127.0.0.1` and mapped TCP port) for a named service
# port on a specific instance, giving test clients, JDBC URLs, and operators a uniform local
# endpoint without managing separate port-forwarding processes.
## @description  Report the host-reachable loopback address and port for an instance service port.
## @param        scope_id   Identify the target scope.
## @param        role       Specify the target role name.
## @param        index      Specify the zero-based instance index.
## @param        port_name  Specify the logical service port name.
## @return       Write one endpoint TSV row (127.0.0.1 and port) to stdout and exit 0, exit 1 if
##               the endpoint cannot be established, exit 6 if unknown, or exit 2 on invalid usage.
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
        "$role/$index $port. The generator of A2 writes the map."
    else
      px_error "the generated port map holds no port for $role/$index $port"
    fi
    return "$SB_EXIT_NO_OBJECT"
  fi
  px_tsv_row 127.0.0.1 "$value"
}

# Release a previously acquired service endpoint. Because Docker Compose publishes ports statically
# as part of the deployment rather than spawning auxiliary forwarding processes, this function
# simply validates the arguments and scope identifier.
## @description  Validate and release a discovered service endpoint (a no-op on Docker Compose).
## @param        scope_id   Identify the target scope.
## @param        role       Specify the target role name.
## @param        index      Specify the zero-based instance index.
## @param        port_name  Specify the logical service port name to release, or `all`.
## @return       Exit 0 for a valid scope, or exit 2 on invalid usage.
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

# Report the configured image reference and resolved image identifier for a running or stopped
# container instance so it can be recorded in the diagnostic bundle manifest. Because locally built
# images carry a local content image ID rather than a registry digest, the second TSV column
# reports that image ID.
## @description  Report the configured image reference and image ID for a container instance.
## @param        scope_id  Identify the target scope.
## @param        role      Specify the target role name.
## @param        index     Specify the zero-based instance index.
## @return       Write one image TSV row to stdout and exit 0, exit 1 if the runtime does not
##               respond, exit 2 on invalid usage, or exit 6 if the instance does not exist.
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
