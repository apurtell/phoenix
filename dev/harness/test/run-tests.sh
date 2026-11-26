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

# Self-test suite for the harness. This suite requires no container runtime, network access, or
# Maven build, enabling execution both on developer laptops and inside pre-commit containers.
#
# A primary goal of this suite is verifying the architectural boundary: no component outside the
# two backends and the local cluster manager may ever invoke a container runtime, a Kubernetes
# tool, or a registry client, and each owner invokes only its own tool (lib/backend/compose.sh
# invokes a container runtime and accesses the registry through it, lib/backend/k8s.sh invokes
# kubectl, and bin/px-k3d invokes k3d). No file invokes a registry client. Because any single check
# can be circumvented, verification is organized into three independent layers:
#   Layer 1: A static scan across every file that can be executed, sourced, or rendered. It checks
#            for restricted tool names and the backend engine variable. Each allowlist entry must
#            carry a justification reason, and the scan fails if an entry becomes stale.
#   Layer 2: Verification of the runtime trap in lib/common.sh against the live function table of
#            a process that sources the library.
#   Layer 3: A fake container runtime, a stateful fake kubectl, and a fake shim for each of the
#            other Kubernetes tools placed on PATH. Negative controls run the entry points that
#            must never reach them, a hostile driver attempts to read the engine variable of each
#            backend, and positive controls prove that each fake records calls when invoked.
# The positive control validates that an empty log during a negative control is genuine evidence of
# non-invocation rather than an artifact of a broken fixture.
#
# Every defect identified during the review of this round retains its own dedicated test case
# wherever the fake runtime can reproduce it.
#
# All test fixtures are materialized from heredocs into a single temporary directory that is
# removed by the exit hook, ensuring no fixture files are committed to the repository. Commands
# execute with the working tree root as their working directory, and the final test group verifies
# that no test execution created any new paths under that root.
#
# Test case identifiers referenced as acceptance criteria by requirements of this round retain
# those exact names so traceability from a requirement locates its test case:
# readme_states_policies (R-002), no_direct_docker (R-004), no_direct_kubectl (R-281),
# bins_source_common (R-006), no_user_paths (R-011), and loader_precedence (R-027).
#
# The shell runs with set -uo pipefail and omits set -e so that a failed assertion is recorded
# rather than terminating the test run early.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Clear any inherited PX_ environment variables before loading the harness libraries so ambient
# operator settings cannot influence test outcomes; the suite explicitly sets the variables it
# requires.
while IFS= read -r T_ENV_LINE; do
  case "${T_ENV_LINE%%=*}" in
  PX_*) unset "${T_ENV_LINE%%=*}" ;;
  esac
done <<EOF
$(env)
EOF

# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"
# shellcheck source=SCRIPTDIR/../lib/backend/contract.sh
. "$SELF/../lib/backend/contract.sh"

set -uo pipefail

# Execute the entire suite under a single Bash interpreter. Because the CLI commands use an env
# shebang, a host with a newer Bash first on PATH would otherwise test against a version higher
# than the minimum baseline guaranteed by the harness, which is the system Bash shipped with macOS.
T_BASH="${BASH:-/bin/bash}"
T_BIN="$PX_HOME/bin"
T_DEV="$(cd "$PX_HOME/.." && pwd)"

# Defines the nine CLI entry points in the order specified by the design.
T_COMMANDS="px-cluster px-suite px-run px-tool px-collect px-bundle px-report px-pherf px-k3d"

# Restricted tool names enforced by Layer 1 and Layer 2: four container runtimes, the Kubernetes
# container runtime client, three Kubernetes tools, and four registry clients. The shared library
# lib/common.sh installs a blocking trap function for each name. No file may reference a registry
# client because the container runtime push command is the only permitted write to a registry.
T_TRAP_NAMES="docker docker-compose podman nerdctl kubectl k3d helm crictl"
T_TRAP_NAMES="$T_TRAP_NAMES skopeo crane regctl oras"
T_REGISTRY_NAMES='skopeo|crane|regctl|oras'
T_RUNTIME_NAMES='docker|docker-compose|podman|nerdctl|kubectl|k3d|helm|crictl|'"$T_REGISTRY_NAMES"
T_K8S_NAMES='kubectl|k3d|helm|crictl'

# Regular expressions for the Layer 1 static scan. The first pattern matches any restricted tool
# name appearing as a standalone word; occurrences preceded by a slash, dot, or hyphen belong to a
# filesystem path or compound identifier and are enforced by Layer 2 instead. The second pattern
# isolates the Kubernetes tools for requirement R-281. The third pattern detects the engine
# variables of each backend; reading one allows a program to invoke its tool by path and bypass
# shell function traps, so only the corresponding backend itself may reference it.
T_RUNTIME_RE='(^|[^[:alnum:]_./-])('"$T_RUNTIME_NAMES"')([^[:alnum:]_-]|$)'
T_K8S_RE='(^|[^[:alnum:]_./-])('"$T_K8S_NAMES"')([^[:alnum:]_-]|$)'
T_ENGINE_RE='(^|[^[:alnum:]_])(PX_DOCKER_BIN|_PXC_ENGINE|PX_KUBECTL_BIN|_PXK_KUBECTL|_PXD_K3D)'
T_ENGINE_RE="$T_ENGINE_RE"'([^[:alnum:]_]|$)'
T_SCAN_RE="$T_RUNTIME_RE|$T_ENGINE_RE"
# Token-class boundary patterns for each owner. An owner may reference only the tokens of its own
# class: compose.sh may reference only the container runtimes and their engine variables, k8s.sh
# may reference only kubectl and its variables, and px-k3d may reference only k3d and its variable.
# Each regular expression below matches a token that is foreign to the corresponding class.
T_NOT_COMPOSE_RE='(^|[^[:alnum:]_./-])(kubectl|k3d|helm|crictl|'"$T_REGISTRY_NAMES"')'
T_NOT_COMPOSE_RE="$T_NOT_COMPOSE_RE"'([^[:alnum:]_-]|$)'
T_NOT_COMPOSE_RE="$T_NOT_COMPOSE_RE"'|(^|[^[:alnum:]_])(PX_KUBECTL_BIN|_PXK_KUBECTL|_PXD_K3D)'
T_NOT_COMPOSE_RE="$T_NOT_COMPOSE_RE"'([^[:alnum:]_]|$)'
T_NOT_K8S_RE='(^|[^[:alnum:]_./-])(docker|docker-compose|podman|nerdctl|k3d|helm|crictl|'
T_NOT_K8S_RE="$T_NOT_K8S_RE$T_REGISTRY_NAMES"')([^[:alnum:]_-]|$)'
T_NOT_K8S_RE="$T_NOT_K8S_RE"'|(^|[^[:alnum:]_])(PX_DOCKER_BIN|_PXC_ENGINE|_PXD_K3D)'
T_NOT_K8S_RE="$T_NOT_K8S_RE"'([^[:alnum:]_]|$)'
T_NOT_K3D_RE='(^|[^[:alnum:]_./-])(docker|docker-compose|podman|nerdctl|kubectl|helm|crictl|'
T_NOT_K3D_RE="$T_NOT_K3D_RE$T_REGISTRY_NAMES"')([^[:alnum:]_-]|$)'
T_NOT_K3D_RE="$T_NOT_K3D_RE"'|(^|[^[:alnum:]_])(PX_DOCKER_BIN|_PXC_ENGINE|PX_KUBECTL_BIN'
T_NOT_K3D_RE="$T_NOT_K3D_RE"'|_PXK_KUBECTL)([^[:alnum:]_]|$)'
# Patterns matching the token classes of the Kubernetes backend and the local cluster manager,
# checked when verifying that their allowlist entries have not become stale.
T_KUBECTL_RE='(^|[^[:alnum:]_./-])kubectl([^[:alnum:]_-]|$)'
T_K3D_RE='(^|[^[:alnum:]_./-])k3d([^[:alnum:]_-]|$)'

# Inline waiver marker honored by the Layer 1 static scan. Any source line carrying this directive
# must document why the token is permitted, allowing lib/common.sh to define the trap functions.
T_WAIVER='# px-allow-docker:'

# Invocation patterns checked by Layer 2 that would bypass shell function traps: explicit binary
# paths never resolve to shell functions, and the three builtins skip the shell function table.
T_BYPASS_RE='(^|[^[:alnum:]_.-])(command|env|exec)[[:space:]]+('"$T_RUNTIME_NAMES"')'
T_BYPASS_RE="$T_BYPASS_RE([^[:alnum:]_-]|\$)"
T_BYPASS_RE="$T_BYPASS_RE|/bin/($T_RUNTIME_NAMES)([^[:alnum:]_-]|\$)"
T_BYPASS_RE="$T_BYPASS_RE|(which|type -P)[[:space:]]+($T_RUNTIME_NAMES)"

# Region mover command template used during the verb sweep. It echoes the interpolated operation,
# host, and port so test cases can verify the endpoint values resolved by the backend.
T_MOVER_CMD="printf 'mover %s\\n' '{operation} {host}:{port}'"

# Pattern used to reject hardcoded user home directory paths, written with an alternation so this
# test file does not contain a literal match for the forbidden prefix.
T_USER_PATH_RE='/(Users|home)/'

# The five mandatory architectural policy phrases that must appear in README.md.
T_POLICIES="local images only
pinned downloads
output outside the tree
one cluster per host
teardown by default"

# Global assertion counters and execution state for the test runner.
T_NUMBER=0
T_FAILED=0
T_FAILED_NAMES=""
T_SKIPPED=""
T_WORK=""
T_OUT=""
T_ERR=""
T_STATUS=0
T_STDOUT=""
T_STDERR=""
T_SHIM_DIR=""
T_SHIM_LOG=""
T_SHIM_STATE=""
T_SWEEP_STATE=""
T_SWEEP_JVM=""
T_SWEEP_OUT=""
T_DEPLOY=""
T_A2=""
T_A2_BIN=""
T_A2_OUT=""
T_A2_STATE=""
T_A2_CURL_DIR=""
T_A2_CURL_LOG=""
T_A2_MVN_LOG=""
T_GEN=""
T_IMG=""
T_ENVD=""
T_ZK_PORT=""
T_ZK_PID=""
T_H3=""
T_H3_BIN=""
T_H3_RUN=""
T_H3_STATE=""
T_H3_IN_TIME=""
T_K1=""
T_K8S_STATE=""
T_K8S_OUT=""
T_K1_A5=""
# Image identifier reported for every pod by the fake kubectl shim, formatted as a 64-digit
# hexadecimal digest.
T_K8S_IMAGE_ID="sha256:f00dfeed00a5$(printf '0%.0s' $(seq 1 52))"
T_K2=""
T_K3S_PIN=""
T_K2_BUNDLE=""

# Default CLI options for executing against the fake selftest cluster recorded by t_seed_deployment:
# a full validation run configuration, and the cluster identifier option alone.
T_SELFTEST_RUN=(--class validation -e PX_CLUSTER_ID=selftest)
T_SELFTEST_CLUSTER=(-e PX_CLUSTER_ID=selftest)

## @description  Prints command-line usage instructions for invoking this command.
## @return       Writes the usage text to stdout. Exit 0.
usage() {
  cat <<'EOF'
Usage: run-tests.sh [-k] [-h]

Runs the harness self-test. It needs no container runtime, no network and no
Maven build.

Options:
  -k  keep the temporary fixture directory for a diagnosis
  -h  show this text

Output: TAP lines on stdout, a plan line, then a summary. Exit 0 when every
assertion passed, 1 when one failed.
EOF
}

# ---------------------------------------------------------------------------
# Test runner assertion and execution primitives
# ---------------------------------------------------------------------------

## @description  Emits diagnostic text as TAP comment lines, one line per input line.
## @return       Writes formatted TAP comment lines to stdout. Exit 0.
t_diag() {
  local line
  while IFS= read -r line; do
    printf '# %s\n' "$line"
  done <<EOF
$*
EOF
}

## @description  Records a passing test assertion for the given assertion name (name).
## @return       Writes one passing TAP result line to stdout. Exit 0.
t_pass() {
  T_NUMBER=$((T_NUMBER + 1))
  printf 'ok %s - %s\n' "$T_NUMBER" "$1"
}

## @description  Records a case (name, reason) that cannot run on this host as a counted TAP skip.
## @return       Writes one skipped TAP result line to stdout. Exit 0.
t_skip() {
  T_NUMBER=$((T_NUMBER + 1))
  T_SKIPPED="$T_SKIPPED  $T_NUMBER $1: $2$PX_NL"
  printf 'ok %s - %s # SKIP %s\n' "$T_NUMBER" "$1" "$2"
}

## @description  Records a failing test assertion (name, detail...) along with diagnostic evidence.
## @return       Writes one failing TAP result line and its diagnostics to stdout. Exit 0.
t_fail() {
  local name=$1
  shift
  T_NUMBER=$((T_NUMBER + 1))
  T_FAILED=$((T_FAILED + 1))
  T_FAILED_NAMES="$T_FAILED_NAMES  $T_NUMBER $name$PX_NL"
  printf 'not ok %s - %s\n' "$T_NUMBER" "$name"
  [ $# -eq 0 ] || t_diag "$@"
}

## @description  Verifies that two values are equal (name, expected, actual).
## @return       Records one equality assertion. Exit 0.
t_equals() {
  if [ "$2" = "$3" ]; then
    t_pass "$1" "value $3"
  else
    t_fail "$1" "expected: $2" "actual:   $3"
  fi
}

## @description  Verifies that a text string contains a required substring (name, needle, text).
## @return       Records one substring presence assertion. Exit 0.
t_contains() {
  case "$3" in
  *"$2"*) t_pass "$1" "found: $2" ;;
  *) t_fail "$1" "did not find: $2" "in: $3" ;;
  esac
}

## @description  Verifies that a text string does not contain a substring (name, needle, text).
## @return       Records one substring absence assertion. Exit 0.
t_absent() {
  case "$3" in
  *"$2"*) t_fail "$1" "unexpectedly found: $2" "in: $3" ;;
  *) t_pass "$1" "absent: $2" ;;
  esac
}

## @description  Verifies that a text string is empty (name, text).
## @return       Records one emptiness assertion. Exit 0.
t_empty() {
  if [ -z "$2" ]; then
    t_pass "$1"
  else
    t_fail "$1" "expected nothing, got:" "$2"
  fi
}

## @description  Verifies that a text string is non-empty (name, text).
## @return       Records one non-emptiness assertion. Exit 0.
t_nonempty() {
  if [ -n "$2" ]; then
    t_pass "$1" "$2"
  else
    t_fail "$1" "expected some output, got nothing"
  fi
}

## @description  Verifies the last command's exit status and full stdout (name, status, stdout).
## @return       Records one combined outcome assertion. Exit 0.
t_outcome() {
  t_equals "$1" "exit $2$PX_NL$3" "exit $T_STATUS$PX_NL$T_STDOUT"
}

# Compares the exit status and leading TSV columns of standard output from the last command.
## @description  Verifies the exit status and leading stdout columns (name, status, count, rows).
## @return       Records one column-sliced outcome assertion. Exit 0.
t_outcome_columns() {
  t_equals "$1" "exit $2$PX_NL$4" "exit $T_STATUS$PX_NL$(printf '%s\n' "$T_STDOUT" | cut -f "1-$3")"
}

## @description  Formats a flat list of fields into tab-separated rows of a given column count.
## @return       Writes the formatted TSV rows to stdout. Exit 0.
t_rows() { printf '%s\n' "${@:2}" | awk -v n="$1" '{ printf "%s%s", $0, (NR % n ? "\t" : "\n") }'; }

# Collapses consecutive spaces into a single space so test cases can inspect aligned tables.
## @description  Collapses every run of spaces in a text string into a single space.
## @return       Writes the normalized text to stdout. Exit 0.
t_squeeze() { printf '%s\n' "$1" | tr -s ' '; }

## @description  Reports whether a text string contains a given substring (needle, text).
## @return       Writes yes or no to stdout. Exit 0.
t_flag() {
  case "$2" in
  *"$1"*) printf yes ;;
  *) printf no ;;
  esac
}

## @description  Reports whether a filesystem path exists as a yes or no flag.
## @return       Writes yes or no to stdout. Exit 0.
t_exists() { if [ -e "$1" ]; then printf yes; else printf no; fi; }

## @description  Reports the state of a process (pid) as T when stopped by a signal, gone when it
##               does not exist, or run otherwise.
## @return       Writes the state word to stdout. Exit 0.
t_proc_state() {
  case "$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')" in
  T*) printf T ;;
  '') printf gone ;;
  *) printf run ;;
  esac
}

## @description  Causes the post-kill STOP state check inside an exec to fail (state directory), or
##               restores normal operation (state directory, off).
## @return       Exit 0.
t_ps_stop_fails() {
  if [ "${2:-}" = off ]; then
    rm -f "$T_SHIM_DIR/ps" "$1/ps-stop-fails"
  else
    cp -p "$T_WORK/ps-shim" "$T_SHIM_DIR/ps"
    : >"$1/ps-stop-fails"
  fi
}

## @description  Executes a command in a target directory (directory, command...) with empty stdin.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_run_in() {
  local dir=$1
  shift
  T_STATUS=0
  (cd "$dir" && exec "$@") </dev/null >"$T_OUT" 2>"$T_ERR" || T_STATUS=$?
  T_STDOUT="$(cat "$T_OUT")"
  T_STDERR="$(cat "$T_ERR")"
}

## @description  Executes a command in the current working directory of the test.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_run() { t_run_in "$PWD" "$@"; }

## @description  Executes a command with the fake runtime first on PATH and set in PX_DOCKER_BIN,
##               and with collectors disabled so runs of either class retain the A5 step sequence.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_with_shim() {
  t_run env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" PX_COLLECTORS=none "$@"
}

## @description  Executes a command (NAME=VALUE..., command...) against the fake sweep cluster.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_sweep() {
  t_with_shim "PX_FAKE_STATE_DIR=$T_SWEEP_STATE" "PX_OUT=$T_SWEEP_OUT" PX_CLUSTER_ID=sweep \
    PX_POLL_INTERVAL_S=0.2 "PX_REGION_MOVER_CMD=$T_MOVER_CMD" "$@"
}

## @description  Invokes a contract function (function, argument...) against the sweep cluster.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_sb() { t_sweep "$T_BASH" "$T_WORK/probe/sb.sh" "$@"; }

## @description  Executes a px-cluster subcommand against the sweep cluster.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_pc() { t_sweep "$T_BASH" "$T_BIN/px-cluster" --cluster sweep "$@"; }

## @description  Executes px-suite (session id, argument...) inside an isolated session.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_suite() { t_run env "PX_SESSION_ID=$1" "$T_BASH" "$T_BIN/px-suite" "${@:2}"; }

## @description  Executes px-run under the fake runtime with a stub driver (name, argument...),
##               leaving the cluster running so subsequent test cases can discover it.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_px_run() {
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_POLL_INTERVAL_S=1 \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/$1.sh" "${@:2}"
}

## @description  Verifies exit codes for contract calls (name, lines of code, function, arguments).
## @return       Records one batch assertion. Exit 0.
t_sb_codes() {
  local name=$1 line bad=""
  local words=()
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    read -r -a words <<<"$line"
    t_sb "${words[@]:1}"
    if [ "$T_STATUS" != "${words[0]}" ]; then
      bad="$bad${words[1]} gave $T_STATUS, not ${words[0]}: $line$PX_NL"
    fi
  done <<EOF
$2
EOF
  t_empty "$name" "$bad"
}

## @description  Verifies that the fake runtime recorded an expected invocation (name, call, raw).
## @return       Records one runtime invocation assertion. Exit 0.
t_called() {
  local name=$1 want=$2 raw=${3:-} calls line
  if [ "$raw" = raw ]; then
    calls="$(cat "$T_SHIM_LOG")"
  else
    calls="$(sed 's/ -f [^ ]* --project-directory [^ ]* / /' "$T_SHIM_LOG")"
  fi
  while IFS= read -r line; do
    if [ "$line" = "$want" ]; then
      t_pass "$name" "called: $want"
      return 0
    fi
  done <<EOF
$calls
EOF
  t_fail "$name" "no call: $want" "calls:" "$calls"
}

## @description  Clears the invocation log of the fake runtime.
## @return       Truncates the fake runtime call log. Exit 0.
t_shim_log_reset() { : >"$T_SHIM_LOG"; }

## @description  Records the fake selftest cluster deployment (scope px-selftest) in each output
##               root (root...) so run bundles generated against it include the cluster/ directory.
## @return       Exit 0.
t_seed_deployment() {
  local root dir="$T_WORK/deploy-selftest"
  mkdir -p "$dir"
  printf '%s\n' "# Rendered by compose.gen.sh. Render again instead of editing." \
    PX_CLUSTER_ID=selftest PX_PHOENIX_HBASE_PROFILE=2.6 PX_DIST_HBASE_VERSION=2.6.7 \
    PX_DIST_HADOOP_VERSION=3.4.3 PX_ZOOKEEPER_VERSION=3.8.6 PX_HBASE_HADOOP_VERSION=3.4.3 \
    PX_GROUPS= >"$dir/topology.env"
  printf '%s\n' "# service role index group start mem_limit_mib heap_mib direct_mib jdk" \
    "$(t_rows 9 regionserver-1 regionserver 1 '' '' 3584 3072 512 17)" >"$dir/services.tsv"
  printf 'PX_PORT_REGIONSERVER_RPC_0=16020\n' >"$dir/ports.generated.env"
  for root in "$@"; do
    mkdir -p "$root/state"
    printf 'px-selftest\n' >"$root/state/scope-compose-selftest"
    printf '%s\n' "$dir" >"$root/state/deployment-compose-selftest"
  done
}

## @description  Resets the fake runtime state to a single running RegionServer with a restart
##               count of 0.
## @return       Reinitializes the fake runtime state files. Exit 0.
t_shim_state_reset() {
  rm -f "$T_SHIM_STATE"/*
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
    px-selftest-regionserver-1 running 'Up 3 minutes (healthy)' \
    phoenix-harness:selftest regionserver 1 0 >"$T_SHIM_STATE/instances.txt"
  printf '0\n' >"$T_SHIM_STATE/restart-count"
  # The backend stores its container start table in the output root; remove it so that a start
  # timestamp recorded by an earlier test case does not add a restart to the next test case.
  rm -f "$PX_OUT"/state/compose/starts-* ${T_A3_OUT:+"$T_A3_OUT"/state/compose/starts-*}
  t_seed_deployment "$PX_OUT" ${T_A3_OUT:+"$T_A3_OUT"}
}

## @description  Reads the planned-action ledger for the sweep cluster.
## @return       Writes the ledger records to stdout. Exit 0.
t_sweep_planned() { cat "$T_SWEEP_OUT/state/planned-px-sweep.tsv" 2>/dev/null || true; }

## @description  Lists all filesystem paths under a root directory excluding .git and target.
## @return       Writes the sorted paths to stdout. Exit 0.
t_tree_paths() {
  find "$1" \( -name .git -o -name target \) -prune -o -print 2>/dev/null | LC_ALL=C sort
}

## @description  Reports paths in a second listing file that are absent from the first listing.
## @return       Writes the newly added paths to stdout. Exit 0.
t_tree_added() { LC_ALL=C comm -13 "$1" "$2"; }

# ---------------------------------------------------------------------------
# Dynamic test fixture generators
# ---------------------------------------------------------------------------

## @description  Writes the test suite fixture files, with the order suite out of dependency order.
## @return       Populates the suite fixture directory. Exit 0.
t_fixtures_suites() {
  mkdir -p "$T_WORK/suites"
  cat >"$T_WORK/suites/order.suite" <<SUITE_EOF
suite: selftest-order
class: validation
description: four tests whose declaration order is not their run order
timeout: 60

test: third
  needs: second
  driver: $T_WORK/drivers/exit-code.sh
  args: 0

test: first
  driver: $T_WORK/drivers/exit-code.sh
  args: 0

test: second
  needs: first
  driver: $T_WORK/drivers/exit-code.sh
  args: 0

test: fourth
  needs: third
  driver: $T_WORK/drivers/exit-code.sh
  args: 0
SUITE_EOF
  cat >"$T_WORK/suites/cycle.suite" <<SUITE_EOF
suite: selftest-cycle
class: validation
timeout: 60

test: alpha
  needs: beta
  driver: $T_WORK/drivers/exit-code.sh
  args: 0

test: beta
  needs: alpha
  driver: $T_WORK/drivers/exit-code.sh
  args: 0
SUITE_EOF
  cat >"$T_WORK/suites/mixed.suite" <<SUITE_EOF
suite: selftest-mixed
class: validation
timeout: 60

test: good
  driver: $T_WORK/drivers/exit-code.sh
  args: 0

test: bad
  driver: $T_WORK/drivers/exit-code.sh
  args: 1

test: harsh
  driver: $T_WORK/drivers/exit-code.sh
  args: 2
SUITE_EOF
  cat >"$T_WORK/suites/blocked.suite" <<SUITE_EOF
suite: selftest-blocked
class: validation
timeout: 60

test: first
  driver: $T_WORK/drivers/exit-code.sh
  args: 1

test: later
  needs: first
  driver: $T_WORK/drivers/exit-code.sh
  args: 0
SUITE_EOF
  cat >"$T_WORK/suites/trapped.suite" <<SUITE_EOF
suite: selftest-trapped
class: validation
timeout: 60

test: blocked
  driver: $T_WORK/drivers/runtime-call.sh
  args: docker ps

test: blocked-kubectl
  driver: $T_WORK/drivers/runtime-call.sh
  args: kubectl get pods
SUITE_EOF
  cat >"$T_WORK/suites/severity.suite" <<SUITE_EOF
suite: selftest-severity
class: validation
timeout: 60

test: unknown-severity
  driver: $T_WORK/drivers/bad-severity.sh
SUITE_EOF
  cat >"$T_WORK/suites/requires.suite" <<SUITE_EOF
suite: selftest-requires
class: validation
timeout: 60

test: needs-yarn
  requires: yarn
  driver: $T_WORK/drivers/exit-code.sh
  args: 0
SUITE_EOF
  # Because the driver path is absolute, this heredoc expands T_WORK while leaving globs, tildes,
  # and backslashes literal so they reach the suite parser unaltered.
  cat >"$T_WORK/suites/args.suite" <<SUITE_EOF
suite: selftest-args
class: validation
timeout: 60

test: literal
  driver: $T_WORK/drivers/print-args.sh
  arg: *.sql
  args: --glob *.sql two\back
  arg:
  arg: a b
  args: ~/data x=~
SUITE_EOF
  cat >"$T_WORK/suites/class.suite" <<SUITE_EOF
suite: selftest-class
class: measurement
timeout: 60

test: classed
  driver: $T_WORK/drivers/exit-code.sh
SUITE_EOF
  # Create two files that a glob pattern would match inside the suite execution directory.
  mkdir -p "$T_WORK/globdir"
  : >"$T_WORK/globdir/alpha.sql"
  : >"$T_WORK/globdir/beta.sql"
}

## @description  Materializes the stub driver scripts invoked by the run and suite test cases.
## @return       Populates the stub driver fixture directory. Exit 0.
t_fixtures_drivers() {
  mkdir -p "$T_WORK/drivers"
  # Shared six-verb lifecycle framework sourced by every stub driver with its incoming arguments
  # before shifting the verb argument away. The describe verb emits a descriptor declaring the five
  # steady-state metrics unsupported, the remaining short verbs succeed as no-ops, and the run verb
  # records a single closed steady-state window before returning control to the stub body so that
  # the resulting run bundle passes validation. Any unrecognized verb exits with status 2.
  cat >"$T_WORK/drivers/stub-lib.sh" <<'DRIVER_EOF'
case "${1:-}" in
describe)
  printf '{"contract": 1, "driver": "%s",\n' "${PX_DRIVER_NAME:-stub}"
  printf ' "phases": [{"anchor": "steady@stub", "verb": "run"}],\n'
  printf ' "metrics": {"count": [], "share": [], "duration": []},\n'
  printf ' "unsupported": {"checks": [\n'
  printf '  {"key": "query_latency.p50_ms", "reason": "a stub"},\n'
  printf '  {"key": "query_latency.p99_ms", "reason": "a stub"},\n'
  printf '  {"key": "query_errors", "reason": "a stub"},\n'
  printf '  {"key": "offered_rate", "reason": "a stub"},\n'
  printf '  {"key": "achieved_qps", "reason": "a stub"}],\n'
  printf '  "artifacts": [{"path": "oplog/", "reason": "a stub"}]}}\n'
  exit 0
  ;;
validate | prepare | restore | report) exit 0 ;;
run) ;;
*) exit 2 ;;
esac
. "$PX_HOME/lib/bundle.sh"
pb_stage begin steady@stub
pb_stage end steady@stub
DRIVER_EOF
  cat >"$T_WORK/drivers/validate-fails.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver whose validate fails.
[ "${1:-}" != validate ] || exit 1
. "${0%/*}/stub-lib.sh" "$@"
DRIVER_EOF
  cat >"$T_WORK/drivers/prepare-fails.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver whose prepare fails.
[ "${1:-}" != prepare ] || exit 1
. "${0%/*}/stub-lib.sh" "$@"
DRIVER_EOF
  cat >"$T_WORK/drivers/describe-fails.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver whose describe fails with the workload code.
[ "${1:-}" != describe ] || exit 1
. "${0%/*}/stub-lib.sh" "$@"
DRIVER_EOF
  cat >"$T_WORK/drivers/describe-sleeps.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver whose describe outlives its bound.
[ "${1:-}" != describe ] || exec sleep 30
. "${0%/*}/stub-lib.sh" "$@"
DRIVER_EOF
  cat >"$T_WORK/drivers/blink.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. Its run writes the begin and the end row of phase@blink in
# one write and exits, so no poll of px-run can see the anchor open.
blink_ms="$(px_epoch_ms)"
printf '%s\t%s\t%s\t%s\t\n%s\t%s\t%s\t%s\t\n' "$blink_ms" begin phase@blink \
  "$PX_RECORD_SOURCE" "$blink_ms" end phase@blink "$PX_RECORD_SOURCE" >>"$PX_RUN_DIR/stages.tsv"
DRIVER_EOF
  cat >"$T_WORK/drivers/monitor-peak.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. In run, it adds one sample of the health monitor: the
# RegionServer at 98 percent of its memory limit.
printf 'instance\t%s\tregionserver\t0\t5.0\t4110000\t4194304\t0\t0\t0\t0\n' \
  "$(px_epoch_ms)" >>"$PX_FAKE_MONITOR_SAMPLES"
DRIVER_EOF
  cat >"$T_WORK/drivers/exit-code.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It ends with the status its first argument names.
exit "${1:-0}"
DRIVER_EOF
  cat >"$T_WORK/drivers/runtime-call.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A hostile stub driver. Its run calls the container tool that its arguments
# name, which the trap of BASH_ENV must block.
"$@"
DRIVER_EOF
  cat >"$T_WORK/drivers/bad-severity.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. Its run writes a verdict row with an unknown severity.
printf 'chk\tFAILED\tbad\n' >>"$PX_RUN_DIR/verdicts.tsv"
DRIVER_EOF
  cat >"$T_WORK/drivers/bump-restart.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It raises the restart count the fake runtime reports, so the
# snapshot after the run differs from the snapshot before it.
printf '%s\n' "${1:-1}" >"$PX_FAKE_STATE_DIR/restart-count"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/exit-instance.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It ends the one instance of the fake runtime with the code of
# a kill and keeps its restart count. That is a crash under the restart
# policy no.
printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
  px-selftest-regionserver-1 exited 'Exited (137) 1 second ago' \
  phoenix-harness:selftest regionserver 1 0 >"$PX_FAKE_STATE_DIR/instances.txt"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/vanish.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It removes the one instance of the fake runtime.
: >"$PX_FAKE_STATE_DIR/instances.txt"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/unreachable.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It makes the fake runtime stop answering, so the snapshot
# after the run cannot read the inventory.
: >"$PX_FAKE_STATE_DIR/unreachable"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/print-args.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It prints each argument between brackets, so an argument
# that was split, joined, expanded or lost shows in its output.
for driver_arg in "$@"; do
  printf '[%s]' "$driver_arg"
done
printf '\n'
DRIVER_EOF
  cat >"$T_WORK/drivers/engine-var.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A hostile driver. It reads the engine variable of the backend and calls the
# runtime by that path, which is the one way past every shell function. The
# backend keeps the variable out of a child, so it must arrive empty here.
printf 'engine variable [%s]\n' "${PX_DOCKER_BIN:-}"
[ -n "${PX_DOCKER_BIN:-}" ] || exit 0
command "$PX_DOCKER_BIN" ps --filter label=hostile-driver
DRIVER_EOF
  chmod +x "$T_WORK/drivers"/*.sh
}

## @description  Materializes the fake container runtime, its state, and fake Kubernetes tool shims.
## @return       Populates the fake runtime and Kubernetes tool shims. Exit 0.
t_fixtures_shim() {
  local name
  T_SHIM_DIR="$T_WORK/shim"
  T_SHIM_STATE="$T_WORK/shim-state"
  T_SHIM_LOG="$T_WORK/runtime-calls.log"
  mkdir -p "$T_SHIM_DIR" "$T_SHIM_STATE"
  : >"$T_SHIM_LOG"
  cat >"$T_SHIM_DIR/docker" <<'SHIM_EOF'
#!/usr/bin/env bash
# A fixture that stands in for a container runtime. It records every argument
# list it receives and answers from the fixture state directory, so a test can
# tell an empty record from a record of the calls one command made.
#
# The state is one line per instance in instances.txt, in the field order of
# the inventory format of the backend: id, role, name, state, status, image,
# service, number, index, group, start and drain. A call that changes an
# instance rewrites its line. A flag file in the state directory turns on one
# fault, so a case stages the fault without a timing assumption. A next.txt
# file replaces the inventory after one listing, so a case stages a change
# during a wait in the same way.
#
# The fake is also the fake registry. local-images.txt (id|ref|config) lists
# the local images that image inspect finds by id or by reference; config is
# the config digest of the descriptor annotation config.digest, as the
# containerd image store reports it, and image ls with no reference lists
# every id. tag records
# the source of a reference in registry-tags.txt. push appends "push <ref>
# <id>" to registry.log and records the digest of the registry in
# repo-digests.txt: the image id, or another digest with the flag
# registry-digest-differs. push-fails makes a push fail, and
# descriptor-fails an image inspect of the descriptors. login is refused,
# because the harness never logs in.
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf '%s\n' "$*" >>"$PX_FAKE_DOCKER_LOG"
fi
fake_state=${PX_FAKE_STATE_DIR:-/nonexistent}
fake_inv="$fake_state/instances.txt"
if [ -e "$fake_state/denied" ]; then
  printf 'permission denied while trying to connect to the Docker daemon socket\n' >&2
  exit 1
fi
if [ -e "$fake_state/unreachable" ]; then
  printf 'fake: the engine does not answer\n' >&2
  exit 1
fi
fake_cluster=selftest
if [ -f "$fake_state/cluster" ]; then
  read -r fake_cluster <"$fake_state/cluster"
fi

## @description  Splits one inventory line into the f_ fields.
## @return       Sets the f_ fields. Exit 0.
fake_split() {
  IFS='|' read -r f_id f_role f_name f_state f_status f_image f_service \
    f_number f_index f_group f_start f_drain <<<"$1"
}

## @description  Joins the f_ fields into one inventory line.
## @return       Writes the line to stdout. Exit 0.
fake_join() {
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "$f_id" "$f_role" "$f_name" \
    "$f_state" "$f_status" "$f_image" "$f_service" "$f_number" "$f_index" \
    "$f_group" "$f_start" "$f_drain"
}

## @description  Sets the state and status (field, key, state, status) of matching instances.
## @return       Exit 0.
fake_rewrite() {
  local field=$1 key=$2 state=$3 status=$4 line value out="" seq
  [ -f "$fake_inv" ] || return 0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    fake_split "$line"
    value=$f_id
    [ "$field" = id ] || value=$f_service
    if [ "$value" = "$key" ]; then
      [ -n "$state" ] || continue
      # A start of a container that does not run gives it a new start time,
      # as the runtime does, and the backend counts that change.
      if [ "$state" = running ] && [ "$f_state" != running ] && [ -d "$fake_state" ]; then
        seq=$(($(cat "$fake_state/start-seq" 2>/dev/null || echo 0) + 1))
        printf '%s\n' "$seq" >"$fake_state/start-seq"
        printf '2026-01-03T00:00:00.%06dZ\n' "$seq" >"$fake_state/started-$f_id"
      fi
      f_state=$state
      f_status=$status
      line="$(fake_join)"
    fi
    out="$out$line
"
  done <"$fake_inv"
  printf '%s' "$out" >"$fake_inv"
}

## @description  Finds one instance by its id.
## @return       Exit 0 when the instance exists; 1, with an error on stderr, otherwise.
fake_find() {
  local line
  if [ -f "$fake_inv" ]; then
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      fake_split "$line"
      [ "$f_id" != "$1" ] || return 0
    done <"$fake_inv"
  fi
  printf 'Error: No such object: %s\n' "$1" >&2
  return 1
}

## @description  Adds the instance of a service (name) that the inventory does not hold yet, the
##               way a new service of a render appears: service <role>-<index>, created.
## @return       Exit 0.
fake_add() {
  local line
  if [ -f "$fake_inv" ]; then
    while IFS= read -r line; do
      fake_split "$line"
      [ "$f_service" != "$1" ] || return 0
    done <"$fake_inv"
  fi
  printf 'new-%s|%s|px-%s|created|Created|px:selftest|%s|1|%s|||\n' "$1" "${1%-*}" "$1" \
    "$1" "${1##*-}" >>"$fake_inv"
}

fake_verb=${1:-}
shift || true
case "$fake_verb" in
version)
  case "$*" in
  *Server*) cat "$fake_state/server-version" 2>/dev/null || printf '29.9.3\n' ;;
  *) printf '29.9.3\n' ;;
  esac
  ;;
info)
  case "$*" in
  *MemTotal*) cat "$fake_state/mem-total" 2>/dev/null || printf '25165824000\n' ;;
  *NCPU*) cat "$fake_state/ncpu" 2>/dev/null || printf '9\n' ;;
  *) printf 'fake-engine\n' ;;
  esac
  ;;
ps)
  fake_select="" fake_group="" fake_running=0 fake_format=""
  while [ $# -gt 0 ]; do
    case "$1" in
    --filter)
      case "${2:-}" in
      label=*.cluster=*) fake_select=${2##*=} ;;
      label=*.group=*) fake_group=${2##*=} ;;
      status=running) fake_running=1 ;;
      esac
      shift
      ;;
    --format)
      fake_format=${2:-}
      shift
      ;;
    esac
    shift
  done
  [ -f "$fake_inv" ] || exit 0
  while IFS= read -r fake_line; do
    [ -n "$fake_line" ] || continue
    fake_split "$fake_line"
    case "$fake_format" in
    '{{.ID}}|'*) ;;
    *)
      # Any other format is the host gate, which asks for the cluster label
      # and the project label.
      printf '%s|px-%s\n' "$fake_cluster" "$fake_cluster"
      continue
      ;;
    esac
    [ "$fake_select" = "$fake_cluster" ] || continue
    [ -z "$fake_group" ] || [ "$f_group" = "$fake_group" ] || continue
    [ "$fake_running" = 0 ] || [ "$f_state" = running ] || continue
    printf '%s\n' "$fake_line"
  done <"$fake_inv"
  # A next-after file holds how many listings show the inventory before
  # next.txt replaces it. Without the file, one listing does.
  case "$fake_format" in
  '{{.ID}}|'*)
    if [ -f "$fake_state/next.txt" ]; then
      fake_after="$(cat "$fake_state/next-after" 2>/dev/null || echo 1)"
      if [ "$fake_after" -gt 1 ]; then
        printf '%s\n' "$((fake_after - 1))" >"$fake_state/next-after"
      else
        mv -f "$fake_state/next.txt" "$fake_inv"
        rm -f "$fake_state/next-after"
      fi
    fi
    ;;
  esac
  ;;
inspect)
  if [ -e "$fake_state/inspect-fails" ]; then
    printf 'fake: inspection refused\n' >&2
    exit 1
  fi
  fake_template=""
  if [ "${1:-}" = --format ]; then
    fake_template=$2
    shift 2
  fi
  for fake_id in "$@"; do
    fake_find "$fake_id" || exit 1
    fake_code=0
    case "$f_status" in
    *'Exited ('*)
      fake_code=${f_status#*Exited (}
      fake_code=${fake_code%%)*}
      ;;
    esac
    fake_oom=false
    if grep -qx "$fake_id" "$fake_state/oom-ids" 2>/dev/null; then
      fake_oom=true
    fi
    case "$fake_template" in
    *RestartCount*)
      # A restart-count-<id> file holds the count of one instance, and a
      # started-<id> file its start time, which each start changes.
      printf '/%s|%s|%s|%s\n' "$f_name" "$(cat "$fake_state/restart-count-$fake_id" \
        2>/dev/null || cat "$fake_state/restart-count" 2>/dev/null || echo 0)" "$fake_id" \
        "$(cat "$fake_state/started-$fake_id" 2>/dev/null || echo 2026-01-01T00:00:00Z)"
      ;;
    *State.Status*)
      printf '%s|%s|%s|2026-01-01T00:00:00Z\n' "$f_state" "$fake_code" "$fake_oom"
      ;;
    *State.OOMKilled*) printf '%s|%s|\n' "$fake_oom" "$fake_code" ;;
    # An image-id file of the state directory gives every instance one image.
    *Config.Image*)
      printf '%s|%s\n' "$f_image" "$(cat "$fake_state/image-id" 2>/dev/null ||
        printf 'sha256:%s' "$fake_id")"
      ;;
    *Config.Hostname*) printf 'host-%s\n' "$fake_id" ;;
    *HostConfig.Memory*) printf '/%s|1073741824\n' "$f_name" ;;
    *) printf '\n' ;;
    esac
  done
  ;;
stats)
  # The stats-sleep file holds the seconds that one stats call takes.
  [ ! -f "$fake_state/stats-sleep" ] || sleep "$(cat "$fake_state/stats-sleep")"
  if [ -e "$fake_state/no-stats" ]; then
    printf 'fake: no stats\n' >&2
    exit 1
  fi
  case "$*" in
  *'{{.ID}}'*)
    printf 'fake00000001\n'
    exit 0
    ;;
  esac
  shift 3
  fake_mem="$(cat "$fake_state/stats-mem" 2>/dev/null || printf '128MiB / 1GiB')"
  for fake_id in "$@"; do
    fake_find "$fake_id" || exit 1
    printf '%s|1.50%%|%s|1.2kB / 3.4kB|5.6kB / 7.8kB\n' "$f_name" "$fake_mem"
  done
  ;;
kill)
  fake_find "${3:-}" || exit 1
  if [ "${2:-}" = KILL ]; then
    fake_rewrite id "$3" exited 'Exited (137) 1 second ago'
  fi
  ;;
rm)
  fake_find "${2:-}" || exit 1
  fake_rewrite id "$2" "" ""
  ;;
stop)
  fake_find "${3:-}" || exit 1
  fake_rewrite id "$3" exited 'Exited (143) 1 second ago'
  ;;
start)
  fake_find "${1:-}" || exit 1
  if [ -e "$fake_state/start-exits" ]; then
    fake_rewrite id "$1" exited 'Exited (1) 1 second ago'
  elif [ -e "$fake_state/start-unready" ]; then
    fake_rewrite id "$1" running 'Up 1 second (health: starting)'
  elif [ -e "$fake_state/start-then-exit-0" ] || [ -e "$fake_state/start-then-exit-1" ]; then
    # The instance starts and is healthy. After the next listing it exits 0,
    # or it exits 1 as a RegionServer does when the master processed it as
    # dead.
    fake_code=0
    [ ! -e "$fake_state/start-then-exit-1" ] || fake_code=1
    fake_rewrite id "$1" running 'Up 1 second (healthy)'
    awk -F '|' -v OFS='|' -v id="$1" -v c="$fake_code" \
      '$1 == id { $4 = "exited"; $5 = "Exited (" c ") 1 second ago" } { print }' \
      "$fake_inv" >"$fake_state/next.txt"
  elif [ -e "$fake_state/start-then-absent" ]; then
    # The instance starts and is healthy, and it is removed after the next
    # listing.
    fake_rewrite id "$1" running 'Up 1 second (healthy)'
    awk -F '|' -v id="$1" '$1 != id' "$fake_inv" >"$fake_state/next.txt"
  else
    fake_rewrite id "$1" running 'Up 1 second (healthy)'
  fi
  # The start-then-peer-unready file names a service that is unhealthy from
  # the start on, as hbase-master is when it counts one RegionServer too few.
  if [ -f "$fake_state/start-then-peer-unready" ]; then
    fake_peer="$(cat "$fake_state/start-then-peer-unready")"
    for fake_file in "$fake_inv" "$fake_state/next.txt"; do
      [ -f "$fake_file" ] || continue
      awk -F '|' -v OFS='|' -v s="$fake_peer" '$7 == s { $5 = "Up 3 minutes (unhealthy)" }
        { print }' "$fake_file" >"$fake_file.new"
      mv -f "$fake_file.new" "$fake_file"
    done
  fi
  ;;
logs)
  fake_id=${!#}
  # A log file of the state directory replaces the fixed lines for one id.
  if [ -f "$fake_state/logs-$fake_id.txt" ]; then
    cat "$fake_state/logs-$fake_id.txt"
    exit 0
  fi
  printf 'fake log out %s\n' "$fake_id"
  printf 'fake log err %s\n' "$fake_id" >&2
  ;;
build)
  # A builder that knows the provenance option says so in its help text.
  if [ "${1:-}" = --help ]; then
    [ ! -e "$fake_state/build-provenance" ] || printf '      --provenance string\n'
    exit 0
  fi
  if [ -e "$fake_state/build-fails" ]; then
    printf 'fake: the build failed\n' >&2
    exit 1
  fi
  printf 'fake: built\n'
  ;;
login)
  printf 'fake: the harness never logs in\n' >&2
  exit 1
  ;;
tag)
  printf '%s|%s\n' "${2:-}" "${1:-}" >>"$fake_state/registry-tags.txt"
  ;;
push)
  if [ -e "$fake_state/push-fails" ]; then
    printf 'fake: the registry refused the push\n' >&2
    exit 1
  fi
  fake_id="$(awk -F '|' -v r="${1:-}" '$1 == r { v = $2 } END { print v }' \
    "$fake_state/registry-tags.txt" 2>/dev/null)"
  printf 'push %s %s\n' "${1:-}" "$fake_id" >>"$fake_state/registry.log"
  fake_digest=$fake_id
  if [ -e "$fake_state/registry-digest-differs" ]; then
    fake_digest="sha256:$(printf 'e%.0s' $(seq 1 64))"
  fi
  printf '%s|%s@%s\n' "${1:-}" "${1%:*}" "$fake_digest" >>"$fake_state/repo-digests.txt"
  ;;
image)
  if [ "${1:-}" = ls ] && [ "${!#}" = --no-trunc ] && [ -f "$fake_state/local-images.txt" ]; then
    cut -d'|' -f1 "$fake_state/local-images.txt"
  elif [ "${1:-}" = inspect ] && [ -f "$fake_state/local-images.txt" ]; then
    case "$*" in
    *Descriptor*)
      [ ! -e "$fake_state/descriptor-fails" ] || exit 1
      shift 3
      for fake_id in "$@"; do
        awk -F '|' -v r="$fake_id" '$1 == r { a = ($3 == "") ? "" : \
          "\"annotations\":{\"config.digest\":\"" $3 "\"},"
          printf "%s {%s\"digest\":\"%s\"}\n", $1, a, $1; exit }' \
          "$fake_state/local-images.txt"
      done
      ;;
    *RepoDigests*)
      awk -F '|' -v r="${!#}" '$1 == r { print $2 }' "$fake_state/repo-digests.txt" \
        2>/dev/null
      ;;
    *)
      fake_id="$(awk -F '|' -v r="${!#}" '$1 == r || $2 == r { print $1; exit }' \
        "$fake_state/local-images.txt")"
      if [ -z "$fake_id" ]; then
        printf 'Error: No such image: %s\n' "${!#}" >&2
        exit 1
      fi
      printf '%s\n' "$fake_id"
      ;;
    esac
  elif [ "${1:-}" = inspect ]; then
    printf 'sha256:f00dfeed0001\n'
  elif [ "${1:-}" = ls ] && [ ! -e "$fake_state/no-image" ] &&
    ! grep -qxF -- "${!#}" "$fake_state/missing-images" 2>/dev/null; then
    # missing-images lists each reference that the host does not hold.
    printf 'sha256:f00dfeed0001\n'
  fi
  ;;
buildx)
  # The image index of a base image: one manifest per platform, and an
  # attestation manifest without a platform.
  if [ ! -e "$fake_state/no-manifest" ]; then
    printf 'linux/amd64 sha256:%s\n' "$(printf 'a%.0s' $(seq 1 64))"
    printf 'linux/arm64 sha256:%s\n' "$(printf 'b%.0s' $(seq 1 64))"
    printf ' sha256:%s\n' "$(printf 'c%.0s' $(seq 1 64))"
  fi
  ;;
network)
  if [ "${1:-}" = ls ]; then
    cat "$fake_state/networks.txt" 2>/dev/null || true
  elif [ "${1:-}" = rm ]; then
    grep -vx "${2:-}" "$fake_state/networks.txt" >"$fake_state/networks.new" || true
    mv -f "$fake_state/networks.new" "$fake_state/networks.txt"
  fi
  ;;
volume)
  if [ "${1:-}" = ls ]; then
    cat "$fake_state/volumes.txt" 2>/dev/null || true
  elif [ "${1:-}" = rm ]; then
    grep -vx "${2:-}" "$fake_state/volumes.txt" >"$fake_state/volumes.new" || true
    mv -f "$fake_state/volumes.new" "$fake_state/volumes.txt"
  fi
  ;;
compose)
  fake_project=""
  while [ $# -gt 0 ]; do
    case "$1" in
    -p) fake_project=${2:-} && shift 2 ;;
    -f | --project-directory) shift 2 ;;
    *) break ;;
    esac
  done
  fake_cverb=${1:-}
  shift || true
  case "$fake_cverb" in
  version)
    printf 'v2.40.0\n'
    ;;
  up)
    if [ -e "$fake_state/up-fails" ]; then
      printf 'fake: up failed\n' >&2
      exit 1
    fi
    # The project network appears with the first instance of the project.
    if [ -n "$fake_project" ] &&
      ! grep -qx "${fake_project}_default" "$fake_state/networks.txt" 2>/dev/null; then
      printf '%s_default\n' "$fake_project" >>"$fake_state/networks.txt"
    fi
    case " $* " in
    *' --no-start --no-build --no-deps '*)
      for fake_arg in "$@"; do
        case "$fake_arg" in -*) ;; *) fake_add "$fake_arg" ;; esac
      done
      ;;
    *' --no-start '*) cp "$fake_state/provision.txt" "$fake_inv" ;;
    *)
      for fake_arg in "$@"; do
        case "$fake_arg" in
        -*) ;;
        *)
          fake_add "$fake_arg"
          fake_rewrite service "$fake_arg" running 'Up 1 second (healthy)'
          ;;
        esac
      done
      ;;
    esac
    ;;
  down)
    [ -e "$fake_state/sticky" ] || : >"$fake_inv"
    case " $* " in
    *' --volumes '*) : >"$fake_state/volumes.txt" ;;
    esac
    if [ -e "$fake_state/down-then-unreachable" ]; then
      : >"$fake_state/unreachable"
    fi
    ;;
  stop | start)
    for fake_arg in "$@"; do
      case "$fake_arg" in
      -* | [0-9]*) ;;
      *)
        if [ "$fake_cverb" = stop ]; then
          fake_rewrite service "$fake_arg" exited 'Exited (143) 1 second ago'
        elif [ -e "$fake_state/start-exits" ]; then
          fake_rewrite service "$fake_arg" exited 'Exited (1) 1 second ago'
        else
          fake_rewrite service "$fake_arg" running 'Up 1 second (healthy)'
        fi
        ;;
      esac
    done
    ;;
  exec)
    fake_feed=0
    while [ $# -gt 0 ]; do
      case "$1" in
      -T)
        fake_feed=1
        shift
        ;;
      --index=*) shift ;;
      *) break ;;
      esac
    done
    # Without a terminal, compose gives all of its stdin to the command, also
    # to a command that reads none. The fake reads it all first, as compose
    # does, so a caller that loops over rows on stdin loses the rest of them.
    if [ "$fake_feed" = 1 ]; then
      cat >"$fake_state/exec-stdin.$$"
      exec <"$fake_state/exec-stdin.$$"
      rm -f "$fake_state/exec-stdin.$$"
    fi
    # The command learns its service, as a command of an instance would.
    export PX_FAKE_SERVICE=${1:-}
    shift
    # The online regions of the cluster come from the helper that the fake
    # kubectl shares.
    if [ "${1:-}" = px-region-count ]; then
      exec "${0%/*}/fake-region-count"
    fi
    # The work directory of a driver, /tmp/px/<run-id> in client 0, lives in
    # the state directory, so an exec writes nothing to the /tmp of the host.
    fake_args=()
    for fake_arg in "$@"; do
      fake_args+=("${fake_arg//\/tmp\/px\//$fake_state/remote/}")
    done
    exec "${fake_args[@]}"
    ;;
  cp)
    # The remote work directory /tmp/px of client 0 lives in the state
    # directory, as for an exec, so a copy into it and out of it moves the
    # real files. Any other remote path holds no file: a copy into it does
    # nothing, and a copy out of it gives copy-out.txt.
    fake_target=${!#}
    fake_source=${*: -2:1}
    case "$fake_target" in
    *:/tmp/px/*)
      fake_path="$fake_state/remote/${fake_target#*:/tmp/px/}"
      rm -rf "$fake_path"
      mkdir -p "${fake_path%/*}"
      cp -R "$fake_source" "$fake_path"
      ;;
    *:*) ;;
    *)
      case "$fake_source" in
      *:/tmp/px/*)
        fake_path="$fake_state/remote/${fake_source#*:/tmp/px/}"
        [ -e "$fake_path" ] || exit 1
        cp -R "$fake_path" "$fake_target"
        ;;
      *)
        if [ -f "$fake_state/copy-out.txt" ]; then
          cp "$fake_state/copy-out.txt" "$fake_target"
        else
          printf 'fake copy\n' >"$fake_target"
        fi
        ;;
      esac
      ;;
    esac
    ;;
  esac
  ;;
esac
exit 0
SHIM_EOF
  chmod +x "$T_SHIM_DIR/docker"
  # Shim for pgrep inside an instance, executed on the host by a fake runtime exec call; it reads
  # the daemon JVM PID from the jvm-pid file in the state directory.
  cat >"$T_SHIM_DIR/pgrep" <<'SHIM_EOF'
#!/usr/bin/env bash
[ "$*" = '-o -x java' ] || exit 2
cat "${PX_FAKE_STATE_DIR:-/nonexistent}/jvm-pid" 2>/dev/null || exit 1
SHIM_EOF
  chmod +x "$T_SHIM_DIR/pgrep"
  # Shim for the host ps command, which a fake runtime exec also invokes. When ps-stop-fails exists
  # in the state directory, a process query inside an exec that observes a stopped process fails,
  # causing the post-kill STOP state check to fail. The t_ps_stop_fails helper installs it into the
  # shim directory only for the individual test cases that require it.
  cat >"$T_WORK/ps-shim" <<'SHIM_EOF'
#!/usr/bin/env bash
shim_self=${0%/*}
shim_real=""
IFS=: read -r -a shim_path <<EOF
$PATH
EOF
for shim_dir in "${shim_path[@]}"; do
  if [ -n "$shim_dir" ] && [ "$shim_dir" != "$shim_self" ] && [ -x "$shim_dir/ps" ]; then
    shim_real=$shim_dir/ps
    break
  fi
done
[ -n "$shim_real" ] || exit 127
shim_out="$("$shim_real" "$@")" || exit
if [ -n "${PX_FAKE_SERVICE:-}" ] && [ -e "${PX_FAKE_STATE_DIR:-/nonexistent}/ps-stop-fails" ]
then
  case "$(printf '%s' "$shim_out" | tr -d ' ')" in
  T*) exit 1 ;;
  esac
fi
printf '%s\n' "$shim_out"
SHIM_EOF
  chmod +x "$T_WORK/ps-shim"
  cat >"$T_SHIM_DIR/fake-region-count" <<'SHIM_EOF'
#!/usr/bin/env bash
# The online regions of the fake cluster, which an exec of px-region-count
# reads through either fake: one line of region-count per call, the last line
# repeating, and 12 without the file. Each call appends its time in
# milliseconds and its answer to region-count.log.
fake_state=${PX_FAKE_STATE_DIR:-/nonexistent}
fake_n=$(($(cat "$fake_state/region-count.log" 2>/dev/null | wc -l) + 1))
fake_value=12
if [ -f "$fake_state/region-count" ]; then
  fake_value="$(awk -v n="$fake_n" 'NR <= n { v = $0 } END { print v }' \
    "$fake_state/region-count")"
fi
fake_ms="$(date -u '+%s%3N' 2>/dev/null)"
case "$fake_ms" in
'' | *[!0-9]*) fake_ms="$(gdate -u '+%s%3N' 2>/dev/null || printf '0')" ;;
esac
printf '%s\t%s\n' "$fake_ms" "$fake_value" >>"$fake_state/region-count.log" 2>/dev/null
[ ! -e "$fake_state/region-count-fails" ] || exit 1
# With count-then-absent, each read after the first removes every
# RegionServer while the read runs, and the read still gives its count.
if [ -e "$fake_state/count-then-absent" ] && [ "$fake_n" -gt 1 ]; then
  for fake_file in "$fake_state/instances.txt" "$fake_state/k8s-pods.txt"; do
    [ -f "$fake_file" ] || continue
    awk -F '|' '$2 != "regionserver" && $3 != "regionserver"' "$fake_file" \
      >"$fake_state/absent.txt"
    mv -f "$fake_state/absent.txt" "$fake_file"
  done
fi
# A RegionServer whose daemon JVM a STOP paused does not answer.
case "$(ps -o stat= -p "$(cat "$fake_state/jvm-pid" 2>/dev/null)" 2>/dev/null |
  tr -d ' ')" in
T*)
  printf 'px-region-count: regionserver 0 does not answer\n' >&2
  exit 1
  ;;
esac
# The line none is a RegionServer that answers without regionCount, as
# one does right after a restart or a rebalance.
if [ "$fake_value" = none ]; then
  printf 'px-region-count: regionserver 0 publishes no regionCount\n' >&2
  exit 1
fi
printf '%s\n' "$fake_value"
SHIM_EOF
  chmod +x "$T_SHIM_DIR/fake-region-count"
  for name in ${T_K8S_NAMES//|/ } ${T_REGISTRY_NAMES//|/ }; do
    case "$name" in kubectl | k3d) continue ;; esac
    cat >"$T_SHIM_DIR/$name" <<'SHIM_EOF'
#!/usr/bin/env bash
# A fixture that stands in for a Kubernetes tool or a registry client. It
# records its name and its argument list in the log of the fake runtime.
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf '%s %s\n' "${0##*/}" "$*" >>"$PX_FAKE_DOCKER_LOG"
fi
exit 0
SHIM_EOF
    chmod +x "$T_SHIM_DIR/$name"
  done
  cat >"$T_SHIM_DIR/k3d" <<'SHIM_EOF'
#!/usr/bin/env bash
# A fixture that stands in for k3d. It records every argument list in the log
# of the fake runtime first, and keeps its clusters in k3d-clusters.txt
# (name|arguments) of PX_FAKE_STATE_DIR. A create adds the context
# k3d-<name> to k8s-contexts.txt of the fake kubectl when that file exists,
# and a delete removes it. An image import adds the image to k8s-images, so
# the nodes of the fake kubectl hold it, and to k3d-images.txt. k3d-version
# holds the version that it reports; k3d-create-fails makes a create fail,
# k3d-create-partial makes it fail after it registered the cluster, and
# k3d-create-stops-list makes every list after a create fail. k3d-env.txt
# records the verb of each call and the helper images of its environment:
# verb|K3D_IMAGE_LOADBALANCER|K3D_IMAGE_TOOLS.
# Any other verb, a registry verb included, is recorded only.
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf 'k3d %s\n' "$*" >>"$PX_FAKE_DOCKER_LOG"
fi
st=${PX_FAKE_STATE_DIR:-/nonexistent}
clusters="$st/k3d-clusters.txt"
[ ! -d "$st" ] || printf '%s %s|%s|%s\n' "${1:-}" "${2:-}" "${K3D_IMAGE_LOADBALANCER:-}" \
  "${K3D_IMAGE_TOOLS:-}" >>"$st/k3d-env.txt"
case "${1:-} ${2:-}" in
'version '*)
  printf 'k3d version %s\nk3s version v1.31.4-k3s1 (default)\n' \
    "$(cat "$st/k3d-version" 2>/dev/null || printf 'v5.7.4')"
  ;;
'cluster create')
  [ ! -e "$st/k3d-create-stops-list" ] || : >"$st/k3d-list-fails"
  if [ -e "$st/k3d-create-fails" ] || grep -q "^${3:-}|" "$clusters" 2>/dev/null; then
    printf 'FATA[0000] Failed to create cluster %s\n' "${3:-}" >&2
    exit 1
  fi
  name=$3
  shift 3
  printf '%s|%s\n' "$name" "$*" >>"$clusters"
  [ ! -f "$st/k8s-contexts.txt" ] || printf 'k3d-%s\n' "$name" >>"$st/k8s-contexts.txt"
  if [ -e "$st/k3d-create-partial" ]; then
    printf 'FATA[0005] Failed to start the server of cluster %s\n' "$name" >&2
    exit 1
  fi
  ;;
'cluster list')
  if [ -e "$st/k3d-list-fails" ]; then
    printf 'FATA[0000] Failed to list clusters\n' >&2
    exit 1
  fi
  [ ! -f "$clusters" ] || awk -F '|' '{ print $1 "   1/1   0/0   true" }' "$clusters"
  ;;
'cluster delete')
  if ! grep -q "^${3:-}|" "$clusters" 2>/dev/null; then
    printf 'FATA[0000] No cluster %s\n' "${3:-}" >&2
    exit 1
  fi
  awk -F '|' -v n="$3" '$1 != n' "$clusters" >"$clusters.new" && mv -f "$clusters.new" "$clusters"
  if [ -f "$st/k8s-contexts.txt" ]; then
    grep -vx "k3d-$3" "$st/k8s-contexts.txt" >"$st/k8s-contexts.new" || true
    mv -f "$st/k8s-contexts.new" "$st/k8s-contexts.txt"
  fi
  ;;
'image import')
  shift 2
  [ "${1:-}" != -c ] || shift 2
  for image in "$@"; do
    printf '%s\n' "$image" >>"$st/k3d-images.txt"
    printf 'docker.io/library/%s\n' "$image" >>"$st/k8s-images"
  done
  ;;
esac
exit 0
SHIM_EOF
  chmod +x "$T_SHIM_DIR/k3d"
  cat >"$T_SHIM_DIR/kubectl" <<'SHIM_EOF'
#!/usr/bin/env bash
# A fixture that stands in for kubectl. It records every argument list in the
# log of the fake runtime first, so the negative controls cover it, and then
# it refuses a call that does not name both the context and the namespace.
#
# The state is in PX_FAKE_STATE_DIR, in k8s-* files. k8s-pods.txt holds one
# line per pod in the field order of px_k8s_pod_format of the backend, then
# two fields of the fake: the kind of the owner and a warm flag. A pod that
# a kill restarted is unready in two listings and ready in the next. The
# StatefulSets are in k8s-sts.txt (name|replicas|strategy|cksum|claims, the
# claim template names or no), the claims in k8s-pvc.txt, one
# <claim>-<set>-<ordinal> per template and pod, the namespaces in
# k8s-namespaces.txt and every
# applied file in k8s-applied/. A flag file turns on one fault. k8s-next.txt
# replaces the pods after k8s-next-after listings, for a change during a wait,
# and then the flag file that k8s-next-flag names, if any, is created. When
# k8s-next-sel holds a text, only a listing whose selector holds it counts.
# When k8s-view-sel holds a text, a listing whose selector holds it reads the
# pods of k8s-view.txt, for an instance that changes between two reads.
# With kill-lags, the pods show a kill only after the number of listings
# that the file holds, as a kubelet reports a kill some time after it.
# With kill-lists-fail, the pod listings after a kill fail the number of
# times that the file holds, as a loaded apiserver can.
# With scale-lingers, an ordinal that a scale down removes stays Terminating
# for the number of listings that the file holds, as on a cluster.
# The Deployments are in k8s-deploy.txt (name|replicas|cluster|image|limit).
# A Deployment pod is named <deployment>-5c9f7d8b6-fk<seq>, has no index
# label and no pod-index label, and a deleted one is replaced by a pod with a
# new name. "scale deployment NAME --replicas=N" adds or removes its pods.
# PX_FAKE_KUBECTL_LOG keeps every call of the K1 cases in one more log. A
# line break in an argument is logged as \n, so each call is one line.
#
# The facts of a target (round K2), each in a file of the state directory:
# k8s-contexts.txt, when present, the contexts that exist; client-version
# and server-version; k8s-storageclasses.txt (local-path without the file);
# k8s-secrets.txt; k8s-quotas.txt, one line per ResourceQuota in the field
# order of px_k8s_preflight_quotas of the backend; the node labels, the
# fifth field of k8s-nodes.txt, which a -l selector filters; k8s-events.txt
# (pod|reason|message); k8s-waiting.txt (pod|message), the waiting message
# of the px container that "get pod NAME" reads (with --ignore-not-found, a
# pod that is gone reads empty). A port-forward prints its Forwarding line,
# at the
# port of k8s-forward-port or at 40000 and a counter, records its pid in
# PX_FAKE_FORWARD_PIDS, and on TERM appends "ended pod/<pod>" to
# k8s-forward.log.
fk_all="$*"
fk_all=${fk_all//$'\n'/\\n}
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf 'kubectl %s\n' "$fk_all" >>"$PX_FAKE_DOCKER_LOG"
fi
if [ -n "${PX_FAKE_KUBECTL_LOG:-}" ]; then
  printf 'kubectl %s\n' "$fk_all" >>"$PX_FAKE_KUBECTL_LOG"
fi
st=${PX_FAKE_STATE_DIR:-/nonexistent}
fk_dir=${0%/*}
fk_ctx=""
fk_ns=""
fk_args=()
while [ $# -gt 0 ]; do
  case "$1" in
  --context) fk_ctx=${2:-} && shift 2 ;;
  --namespace) fk_ns=${2:-} && shift 2 ;;
  --)
    fk_args+=("$@")
    break
    ;;
  *) fk_args+=("$1") && shift ;;
  esac
done
if [ -z "$fk_ctx" ] || [ -z "$fk_ns" ]; then
  printf 'fake: --context and --namespace are required\n' >&2
  exit 2
fi
if [ -f "$st/k8s-contexts.txt" ] && ! grep -qxF -- "$fk_ctx" "$st/k8s-contexts.txt"; then
  printf 'error: context "%s" does not exist\n' "$fk_ctx" >&2
  exit 1
fi
set -- ${fk_args[@]+"${fk_args[@]}"}
if [ -e "$st/denied" ]; then
  printf 'error: You must be logged in to the server (Unauthorized)\n' >&2
  exit 1
fi
if [ -e "$st/unreachable" ]; then
  printf 'The connection to the server 127.0.0.1:6443 was refused\n' >&2
  exit 1
fi
pods="$st/k8s-pods.txt"
sts="$st/k8s-sts.txt"
pvc="$st/k8s-pvc.txt"
nss="$st/k8s-namespaces.txt"
deploys="$st/k8s-deploy.txt"
cluster=selftest
[ ! -f "$st/k8s-cluster" ] || read -r cluster <"$st/k8s-cluster"
touch "$pods" "$sts" "$pvc" "$nss" "$deploys" 2>/dev/null

## @description  Reports the group of a role, as the generator does.
## @return       Writes the group to stdout. Exit 0.
fk_group() {
  case "$1" in
  resourcemanager | nodemanager | jobhistory) printf 'yarn\n' ;;
  pherf-controller | pherf-agent) printf 'pherf\n' ;;
  esac
}

## @description  Tests a pod line (line, selector) against a label selector.
## @return       Exit 0 when every term matches. Exit 1 otherwise.
fk_match() {
  local role term key value
  role="$(printf '%s' "$1" | cut -d'|' -f3)"
  IFS=, read -r -a fk_terms <<<"$2"
  for term in ${fk_terms[@]+"${fk_terms[@]}"}; do
    key=${term%%=*}
    value=${term#*=}
    [ "$key" != "$term" ] || continue
    case "$key" in
    *.cluster) [ "$value" = "$cluster" ] || return 1 ;;
    *.role) [ "$value" = "$role" ] || return 1 ;;
    *.group) [ "$value" = "$(fk_group "$role")" ] || return 1 ;;
    esac
  done
  return 0
}

## @description  Prints one new pod line: name role index-label pod-index drain image limit owner.
## @return       Exit 0.
fk_pod_line() {
  local seq phase=Running ready=True started=2026-01-03T00:00:00Z code="" reason="" ended=""
  seq=$(($(cat "$st/k8s-uid-seq" 2>/dev/null || echo 0) + 1))
  printf '%s\n' "$seq" >"$st/k8s-uid-seq"
  if [ "${9:-}" = unready ]; then
    ready=False
  fi
  if [ "$8" = Job ]; then
    phase=Succeeded ready=False started="" code=0 reason=Completed ended=2026-01-03T00:00:01Z
    if [ -e "$st/setup-fails" ]; then
      phase=Failed code=1 reason=Error
    fi
  fi
  printf '%s|uid-%04d|%s|%s|%s|%s||%s||%s|%s||%s|%s|%s||||0|%s|sha256:%s|fake-node|%s|%s|\n' \
    "$1" "$seq" "$2" "$3" "$4" "$5" "$phase" "$ready" "$started" "$code" "$reason" "$ended" \
    "$6" "f00d$(printf '0%.0s' $(seq 1 60))" "$7" "$8"
}

## @description  Removes the pod lines whose name matches an extended expression.
## @return       Exit 0.
fk_drop() {
  awk -F '|' -v re="$1" '$1 !~ re' "$pods" >"$pods.new.$$"
  mv -f "$pods.new.$$" "$pods"
}

## @description  Reports the field (number) of the pod named (name).
## @return       Writes the field to stdout. Exit 0, 1 when no pod has the name.
fk_field() {
  awk -F '|' -v n="$1" -v f="$2" '$1 == n { print $f; found = 1; exit } END { exit !found }' \
    "$pods"
}

## @description  Rewrites fields of the pod named (name, field=value...).
## @return       Exit 0.
fk_set() {
  local name=$1
  shift
  awk -F '|' -v OFS='|' -v n="$name" -v sets="$*" 'BEGIN { k = split(sets, a, " ") }
    $1 == n { for (i = 1; i <= k; i++) { split(a[i], p, "="); $(p[1]) = p[2] } } { print }' \
    "$pods" >"$pods.new.$$"
  mv -f "$pods.new.$$" "$pods"
}

## @description  Creates the pod of one ordinal of a StatefulSet (set, ordinal, drain, image, limit,
##               mode). With mode replace the pod is unready when the flag recreate-unready is set.
## @return       Exit 0.
fk_sts_pod() {
  local profile=""
  if [ "${6:-}" = replace ] && [ -e "$st/recreate-unready" ]; then
    profile=unready
  fi
  fk_pod_line "$1-$2" "$1" "" "$2" "$3" "$4" "$5" StatefulSet "$profile" >>"$pods"
}

## @description  Applies one StatefulSet record (name|replicas|strategy|cksum|claims|image|limit|
##               drain), claims being the comma-separated claim template names or no. Ordinals come
##               and go with a claim <claim>-<name>-<i> each, and a changed template replaces pods
##               unless OnDelete.
## @return       Exit 0.
fk_apply_sts() {
  local name replicas strategy sum claims image limit drain old i n claim
  IFS='|' read -r name replicas strategy sum claims image limit drain <<<"$1"
  old="$(awk -F '|' -v n="$name" '$1 == n { print $4 }' "$sts")"
  awk -F '|' -v n="$name" '$1 != n' "$sts" >"$sts.new.$$"
  printf '%s|%s|%s|%s|%s\n' "$name" "$replicas" "$strategy" "$sum" "$claims" >>"$sts.new.$$"
  mv -f "$sts.new.$$" "$sts"
  i=0
  while [ "$i" -lt "$replicas" ]; do
    if ! fk_field "$name-$i" 1 >/dev/null; then
      fk_sts_pod "$name" "$i" "$drain" "$image" "$limit"
    elif [ -n "$old" ] && [ "$old" != "$sum" ] && [ "$strategy" != OnDelete ]; then
      fk_drop "^$name-$i\$"
      fk_sts_pod "$name" "$i" "$drain" "$image" "$limit"
    fi
    for claim in ${claims//,/ }; do
      [ "$claim" != no ] || continue
      grep -qx "$claim-$name-$i" "$pvc" || printf '%s-%s-%s\n' "$claim" "$name" "$i" >>"$pvc"
    done
    i=$((i + 1))
  done
  for n in $(awk -F '|' -v s="$name" '$1 ~ "^" s "-[0-9]+$" { print $1 }' "$pods"); do
    [ "${n##*-}" -ge "$replicas" ] || continue
    if [ -f "$st/scale-lingers" ]; then
      fk_set "$n" 7=2026-01-03T00:00:09Z 10=False "25=doomed:$(cat "$st/scale-lingers")"
    else
      fk_drop "^$n\$"
    fi
    grep -vE "^[a-z]+-$n\$" "$pvc" >"$pvc.new.$$" || true
    mv -f "$pvc.new.$$" "$pvc"
  done
}

## @description  Makes the live pods of a Deployment (name) match its replicas: new pods get the
##               next name, and a scale down removes the last names, Terminating with scale-lingers.
## @return       Exit 0.
fk_deploy_sync() {
  local name=$1 replicas image limit live n seq victim
  IFS='|' read -r _ replicas _ image limit <<<"$(grep "^$name|" "$deploys")"
  [ -n "$replicas" ] || return 0
  live="$(awk -F '|' -v r="$name" '$3 == r && $24 == "Deployment" && $7 == "" { print $1 }' \
    "$pods" | LC_ALL=C sort)"
  n="$(printf '%s' "$live" | grep -c .)" || n=0
  while [ "$n" -lt "$replicas" ]; do
    seq=$(($(cat "$st/k8s-deploy-seq" 2>/dev/null || echo 0) + 1))
    printf '%s\n' "$seq" >"$st/k8s-deploy-seq"
    fk_pod_line "$(printf '%s-5c9f7d8b6-fk%04d' "$name" "$seq")" "$name" "" "" "" "$image" \
      "$limit" Deployment >>"$pods"
    n=$((n + 1))
  done
  for victim in $(printf '%s\n' "$live" | sed -n "$((replicas + 1)),\$p"); do
    if [ -f "$st/scale-lingers" ]; then
      fk_set "$victim" 7=2026-01-03T00:00:09Z 10=False "25=doomed:$(cat "$st/scale-lingers")"
    else
      fk_drop "^$victim\$"
    fi
  done
}

## @description  Splits a rendered file into one record per document: kind|name|replicas|strategy|
##               claims|image|limit|drain|cluster, and the template block of document N into tpl.N.
## @return       Writes the records to stdout. Exit 0.
fk_docs() {
  awk -v dir="$st" '
    function flush() {
      if (kind != "") print kind "|" name "|" reps "|" strat "|" claims "|" image "|" lim "|" \
        drain "|" clu
      kind = name = reps = strat = image = lim = drain = clu = ""; claims = "no"; meta = 0; tpl = 0
      vct = 0
      n++
    }
    BEGIN { n = 0; claims = "no" }
    /^---$/ { flush(); next }
    /^kind: / { kind = $2 }
    /^metadata:/ { meta = 1 }
    meta == 1 && /^  name: / && name == "" { name = $2 }
    /^  replicas: / { reps = $2 }
    /^    type: (OnDelete|RollingUpdate|Recreate)/ { strat = $2 }
    /^  template:/ { tpl = 1 }
    /^  volumeClaimTemplates:/ { tpl = 0; vct = 1; claims = ""; next }
    vct == 1 && /^        name: / { claims = claims (claims == "" ? "" : ",") $2 }
    tpl == 1 { print > (dir "/tpl." n) }
    /^ +image: / && image == "" { image = $2; gsub(/"/, "", image) }
    /^ +memory: / { lim = $2 }
    /drain: "regionmover"/ { drain = "regionmover" }
    /\.cluster: "/ && clu == "" { clu = $2; gsub(/"/, "", clu) }
    END { flush() }' "$1"
}

## @description  Moves the pods to the next state, and sets the flag of k8s-next-flag, after the
##               listings that k8s-next-after names, and makes a warm pod ready.
## @return       Exit 0.
fk_after_listing() {
  local after
  awk -F '|' -v OFS='|' '$25 == "warm" { $10 = "True"; $25 = "" } $25 == "warm2" { $25 = "warm" }
    $25 ~ /^doomed:/ { n = substr($25, 8) - 1; if (n < 1) next; $25 = "doomed:" n }
    { print }' "$pods" >"$pods.new.$$"
  mv -f "$pods.new.$$" "$pods"
  if [ -f "$st/k8s-next-sel" ]; then
    case "$fk_sel" in
    *"$(cat "$st/k8s-next-sel")"*) ;;
    *) return 0 ;;
    esac
  fi
  if [ -f "$st/k8s-next.txt" ]; then
    after="$(cat "$st/k8s-next-after" 2>/dev/null || echo 1)"
    if [ "$after" -gt 1 ]; then
      printf '%s\n' "$((after - 1))" >"$st/k8s-next-after"
    else
      mv -f "$st/k8s-next.txt" "$pods"
      rm -f "$st/k8s-next-after"
      if [ -f "$st/k8s-next-flag" ]; then
        : >"$st/$(cat "$st/k8s-next-flag")"
      fi
      rm -f "$st/k8s-next-flag" "$st/k8s-next-sel"
    fi
  fi
}

## @description  Tests a node line (line, selector) against a selector of node labels, the fifth
##               field of k8s-nodes.txt.
## @return       Exit 0 when every term matches. Exit 1 otherwise.
fk_node_match() {
  local labels term
  labels=",$(printf '%s' "$1" | cut -d'|' -f5),"
  IFS=, read -r -a fk_terms <<<"$2"
  for term in ${fk_terms[@]+"${fk_terms[@]}"}; do
    case "$labels" in
    *",$term,"*) ;;
    *) return 1 ;;
    esac
  done
  return 0
}

fk_verb=${1:-}
shift || true
fk_sel="" fk_out="" fk_dry=0 fk_file="" fk_prev=0 fk_pos=() fk_field_sel="" fk_replicas=""
fk_ignore=0
if [ "$fk_verb" = port-forward ]; then
  fk_addr=localhost fk_pod="" fk_remote=""
  while [ $# -gt 0 ]; do
    case "$1" in
    --address) fk_addr=${2:-} && shift ;;
    pod/*) fk_pod=$1 ;;
    :*) fk_remote=${1#:} ;;
    esac
    shift
  done
  if [ -e "$st/k8s-forward-fails" ]; then
    printf 'error: unable to forward port because pod is not running\n' >&2
    exit 1
  fi
  fk_port="$(cat "$st/k8s-forward-port" 2>/dev/null)" || fk_port=""
  if [ -z "$fk_port" ]; then
    fk_port=$(($(cat "$st/k8s-forward-seq" 2>/dev/null || echo 0) + 1))
    printf '%s\n' "$fk_port" >"$st/k8s-forward-seq"
    fk_port=$((40000 + fk_port))
  fi
  [ -z "${PX_FAKE_FORWARD_PIDS:-}" ] || printf '%s\n' "$$" >>"$PX_FAKE_FORWARD_PIDS"
  trap 'printf "ended %s\n" "$fk_pod" >>"$st/k8s-forward.log"; exit 0' TERM
  [ "$fk_addr" != localhost ] || fk_addr=127.0.0.1
  printf 'Forwarding from %s:%s -> %s\n' "$fk_addr" "$fk_port" "$fk_remote"
  while :; do
    sleep 1 &
    wait $!
  done
fi
if [ "$fk_verb" != exec ] && [ "$fk_verb" != cp ]; then
  while [ $# -gt 0 ]; do
    case "$1" in
    -l) fk_sel=${2:-} && shift ;;
    --field-selector) fk_field_sel=${2:-} && shift ;;
    -o) fk_out=${2:-} && shift ;;
    -f) fk_file=${2:-} && shift ;;
    -c) shift ;;
    --dry-run=client) fk_dry=1 ;;
    --previous) fk_prev=1 ;;
    --ignore-not-found) fk_ignore=1 ;;
    --replicas=*) fk_replicas=${1#--replicas=} ;;
    -*) ;;
    *) fk_pos+=("$1") ;;
    esac
    shift
  done
fi
case "$fk_verb" in
version)
  printf '{\n  "clientVersion": {\n    "gitVersion": "%s"\n  },\n' \
    "$(cat "$st/client-version" 2>/dev/null || printf 'v1.37.0')"
  printf '  "serverVersion": {\n    "gitVersion": "%s"\n  }\n}\n' \
    "$(cat "$st/server-version" 2>/dev/null || printf 'v1.37.2+k3s1')"
  ;;
auth)
  if [ -e "$st/cannot-i" ]; then
    printf 'no\n'
    exit 1
  fi
  printf 'yes\n'
  ;;
apply)
  [ -f "$fk_file" ] || {
    printf 'error: the path "%s" does not exist\n' "$fk_file" >&2
    exit 1
  }
  fk_base=${fk_file##*/}
  if [ "$fk_dry" = 1 ]; then
    if grep -qx "$fk_base" "$st/dry-run-fails" 2>/dev/null; then
      printf 'error: error validating "%s": the fixture refuses it\n' "$fk_file" >&2
      exit 1
    fi
    # Every document names its API version and its kind.
    awk '/^---$/ { if (!(a && k)) bad = 1; a = k = 0; next } /^apiVersion: / { a = 1 }
      /^kind: / { k = 1 } END { if (!(a && k)) bad = 1; exit bad }' "$fk_file" || {
      printf 'error: a document of %s has no apiVersion or kind\n' "$fk_file" >&2
      exit 1
    }
    printf '%s configured (dry run)\n' "$fk_base"
    exit 0
  fi
  if grep -qx "$fk_base" "$st/apply-fails" 2>/dev/null; then
    printf 'error: the fixture refuses %s\n' "$fk_file" >&2
    exit 1
  fi
  mkdir -p "$st/k8s-applied"
  cp "$fk_file" "$st/k8s-applied/$fk_base"
  rm -f "$st"/tpl.*
  fk_n=0
  while IFS= read -r fk_doc; do
    IFS='|' read -r fk_kind fk_name fk_reps fk_strat fk_claims fk_image fk_lim fk_drain \
      fk_clu <<<"$fk_doc"
    case "$fk_kind" in
    Namespace)
      grep -v "^$fk_name|" "$nss" >"$nss.new.$$" || true
      printf '%s|%s\n' "$fk_name" "$fk_clu" >>"$nss.new.$$"
      mv -f "$nss.new.$$" "$nss"
      printf '%s\n' "$fk_clu" >"$st/k8s-cluster"
      cluster=$fk_clu
      ;;
    StatefulSet)
      fk_sum="$(cksum <"$st/tpl.$fk_n" 2>/dev/null | cut -d' ' -f1)"
      fk_apply_sts "$fk_name|$fk_reps|$fk_strat|$fk_sum|$fk_claims|$fk_image|$fk_lim|$fk_drain"
      ;;
    Deployment)
      grep -v "^$fk_name|" "$deploys" >"$deploys.new.$$" || true
      printf '%s|%s|%s|%s|%s\n' "$fk_name" "$fk_reps" "$fk_clu" "$fk_image" "$fk_lim" \
        >>"$deploys.new.$$"
      mv -f "$deploys.new.$$" "$deploys"
      fk_deploy_sync "$fk_name"
      ;;
    PersistentVolumeClaim)
      grep -qx "$fk_name" "$pvc" || printf '%s\n' "$fk_name" >>"$pvc"
      ;;
    Job)
      if ! grep -q "^$fk_name-" "$pods"; then
        fk_pod_line "$fk_name-fake1" "${fk_name%-0}" 0 "" "" "$fk_image" "$fk_lim" Job >>"$pods"
      fi
      ;;
    Pod)
      if ! fk_field "$fk_name" 1 >/dev/null; then
        fk_pod_line "$fk_name" "${fk_name%-0}" 0 "" "" "$fk_image" "$fk_lim" "" >>"$pods"
      fi
      ;;
    esac
    printf '%s/%s configured\n' "$(printf '%s' "$fk_kind" | tr '[:upper:]' '[:lower:]')" \
      "$fk_name"
    fk_n=$((fk_n + 1))
  done <<EOF
$(fk_docs "$fk_file")
EOF
  ;;
get)
  case "${fk_pos[0]:-}" in
  pods)
    if [ -f "$st/lists-fail" ]; then
      fk_left="$(cat "$st/lists-fail")"
      rm -f "$st/lists-fail"
      [ "$fk_left" -le 1 ] || printf '%s\n' "$((fk_left - 1))" >"$st/lists-fail"
      printf 'Error from server (Timeout): the server was unable to return a response\n' >&2
      exit 1
    fi
    fk_list=$pods
    if [ -f "$st/k8s-view-sel" ]; then
      case "$fk_sel" in
      *"$(cat "$st/k8s-view-sel")"*) fk_list="$st/k8s-view.txt" ;;
      esac
    fi
    while IFS= read -r fk_line; do
      [ -n "$fk_line" ] || continue
      fk_match "$fk_line" "$fk_sel" || continue
      case "$fk_sel" in *.cluster=*) ;; *) continue ;; esac
      printf '%s\n' "$fk_line"
    done <"$fk_list"
    fk_after_listing
    ;;
  namespaces)
    # The listing selects by the cluster label, so a namespace without it
    # stays out.
    awk -F '|' '$2 != "" { print $1 }' "$nss"
    ;;
  namespace)
    # The jsonpath of the backend asks for the name and the cluster label.
    case "$fk_out" in
    *labels*) grep "^${fk_pos[1]:-}|" "$nss" ;;
    *) grep -q "^${fk_pos[1]:-}|" "$nss" && printf 'namespace/%s\n' "${fk_pos[1]}" ;;
    esac
    ;;
  nodes)
    if [ -f "$st/k8s-nodes.txt" ]; then
      fk_nodes="$(cat "$st/k8s-nodes.txt")"
    else
      fk_nodes="fake-node|33554432Ki|8|$({ tr '\n' ' ' <"$st/k8s-images"; } 2>/dev/null)|"
    fi
    if [ -n "$fk_sel" ]; then
      fk_nodes="$(printf '%s\n' "$fk_nodes" | while IFS= read -r fk_line; do
        fk_node_match "$fk_line" "$fk_sel" && printf '%s\n' "$fk_line"
      done)"
    fi
    case "$fk_out" in
    *allocatable*) printf '%s\n' "$fk_nodes" | cut -d'|' -f2,3 ;;
    *) printf '%s\n' "$fk_nodes" | awk -F '|' 'NF { print $1 "|" $4 " " }' ;;
    esac
    ;;
  storageclass)
    if [ -f "$st/k8s-storageclasses.txt" ]; then
      grep -qx "${fk_pos[1]:-}" "$st/k8s-storageclasses.txt" &&
        printf 'storageclass.storage.k8s.io/%s\n' "${fk_pos[1]}"
    elif [ "${fk_pos[1]:-}" = local-path ]; then
      printf 'storageclass.storage.k8s.io/local-path\n'
    fi
    ;;
  secret)
    grep -qx "${fk_pos[1]:-}" "$st/k8s-secrets.txt" 2>/dev/null &&
      printf 'secret/%s\n' "${fk_pos[1]}"
    ;;
  resourcequota)
    cat "$st/k8s-quotas.txt" 2>/dev/null || true
    ;;
  mutatingwebhookconfigurations)
    # One webhook name a line, as the jsonpath of the backend asks.
    if [ -e "$st/webhooks-forbidden" ]; then
      printf 'Error from server (Forbidden): mutatingwebhookconfigurations is forbidden\n' >&2
      exit 1
    fi
    cat "$st/k8s-webhooks.txt" 2>/dev/null || true
    ;;
  events)
    awk -F '|' -v p="${fk_field_sel#involvedObject.name=}" '$1 == p { print $2 "|" $3 }' \
      "$st/k8s-events.txt" 2>/dev/null || true
    ;;
  pod)
    fk_field "${fk_pos[1]:-}" 1 >/dev/null || {
      [ "$fk_ignore" = 0 ] || exit 0
      printf 'Error from server (NotFound): pods "%s" not found\n' "${fk_pos[1]:-}" >&2
      exit 1
    }
    awk -v p="${fk_pos[1]}|" 'index($0, p) == 1 { printf "%s", substr($0, length(p) + 1) }' \
      "$st/k8s-waiting.txt" 2>/dev/null || true
    ;;
  *)
    # A kind list with -o name: what a teardown left.
    case ",${fk_pos[0]:-}," in
    *,statefulsets,*) awk -F '|' '{ print "statefulset.apps/" $1 }' "$sts" ;;
    esac
    case ",${fk_pos[0]:-}," in
    *,deployments,*) awk -F '|' '{ print "deployment.apps/" $1 }' "$deploys" ;;
    esac
    case ",${fk_pos[0]:-}," in
    *,pods,*)
      while IFS= read -r fk_line; do
        fk_match "$fk_line" "$fk_sel" && printf 'pod/%s\n' "${fk_line%%|*}"
      done <"$pods"
      ;;
    esac
    case ",${fk_pos[0]:-}," in
    *,pvc,*) sed 's|^|persistentvolumeclaim/|' "$pvc" ;;
    esac
    ;;
  esac
  ;;
delete)
  case "${fk_pos[0]:-}" in
  pod)
    fk_name=${fk_pos[1]:-}
    fk_owner="$(fk_field "$fk_name" 24)" || {
      printf 'Error from server (NotFound): pods "%s" not found\n' "$fk_name" >&2
      exit 1
    }
    fk_drain="$(fk_field "$fk_name" 6)"
    fk_role="$(fk_field "$fk_name" 3)"
    fk_image="$(fk_field "$fk_name" 20)"
    fk_lim="$(fk_field "$fk_name" 23)"
    fk_drop "^$fk_name\$"
    fk_set_name=${fk_name%-*}
    fk_reps="$(awk -F '|' -v n="$fk_set_name" '$1 == n { print $2 }' "$sts")"
    if [ "$fk_owner" = StatefulSet ] && [ -n "$fk_reps" ] && [ "${fk_name##*-}" -lt "$fk_reps" ] &&
      [ ! -e "$st/recreate-never" ]; then
      fk_sts_pod "$fk_set_name" "${fk_name##*-}" "$fk_drain" "$fk_image" "$fk_lim" replace
    fi
    [ "$fk_owner" != Deployment ] || fk_deploy_sync "$fk_role"
    printf 'pod "%s" deleted\n' "$fk_name"
    ;;
  namespace)
    grep -v "^${fk_pos[1]:-}|" "$nss" >"$nss.new.$$" || true
    mv -f "$nss.new.$$" "$nss"
    : >"$pods"
    : >"$sts"
    : >"$pvc"
    : >"$deploys"
    ;;
  pvc)
    : >"$pvc"
    ;;
  configmaps,services)
    : >"$st/k8s-configmaps.txt"
    ;;
  statefulsets | statefulsets,deployments)
    for fk_name in $(cut -d'|' -f1 "$sts"); do
      fk_match "x|x|$fk_name" "$fk_sel" || continue
      awk -F '|' -v n="$fk_name" '$1 != n' "$sts" >"$sts.new.$$"
      mv -f "$sts.new.$$" "$sts"
      fk_drop "^$fk_name-[0-9]+\$"
    done
    [ "${fk_pos[0]}" = statefulsets,deployments ] || exit 0
    for fk_name in $(cut -d'|' -f1 "$deploys"); do
      fk_match "x|x|$fk_name" "$fk_sel" || continue
      awk -F '|' -v n="$fk_name" '$1 != n' "$deploys" >"$deploys.new.$$"
      mv -f "$deploys.new.$$" "$deploys"
      awk -F '|' -v r="$fk_name" '!($3 == r && $24 == "Deployment")' "$pods" \
        >"$pods.new.$$"
      mv -f "$pods.new.$$" "$pods"
    done
    ;;
  *)
    [ ! -e "$st/delete-leaves" ] || exit 0
    : >"$sts"
    : >"$deploys"
    while IFS= read -r fk_line; do
      fk_match "$fk_line" "$fk_sel" || printf '%s\n' "$fk_line"
    done <"$pods" >"$pods.new.$$"
    mv -f "$pods.new.$$" "$pods"
    ;;
  esac
  ;;
scale)
  fk_name=${fk_pos[1]:-}
  if [ "${fk_pos[0]:-}" != deployment ] || ! grep -q "^$fk_name|" "$deploys" ||
    ! [[ $fk_replicas =~ ^[0-9]+$ ]]; then
    printf 'Error from server (NotFound): deployments.apps "%s" not found\n' "$fk_name" >&2
    exit 1
  fi
  awk -F '|' -v OFS='|' -v n="$fk_name" -v r="$fk_replicas" '$1 == n { $2 = r } { print }' \
    "$deploys" >"$deploys.new.$$"
  mv -f "$deploys.new.$$" "$deploys"
  fk_deploy_sync "$fk_name"
  printf 'deployment.apps/%s scaled\n' "$fk_name"
  ;;
exec)
  while [ $# -gt 0 ]; do
    case "$1" in
    -i | -t) shift ;;
    -c) shift 2 ;;
    --)
      shift
      break
      ;;
    *) fk_name=$1 && shift ;;
    esac
  done
  if ! fk_field "$fk_name" 1 >/dev/null; then
    printf 'Error from server (NotFound): pods "%s" not found\n' "$fk_name" >&2
    exit 1
  fi
  if [ -z "$(fk_field "$fk_name" 11)" ]; then
    printf 'error: unable to upgrade connection: container not found ("px")\n' >&2
    exit 1
  fi
  # The shell frame of the backend runs the command by exec.
  if [ "${1:-}" = sh ] && [ "${2:-}" = -c ] && [ "${4:-}" = px-exec ]; then
    shift 4
  fi
  # A kill or a TERM of the container is modelled in the state and never runs
  # on the host. A StatefulSet pod restarts, unready for two listings. A
  # standalone pod stays ended, as its restart policy is Never.
  if [ "${1:-}" = sh ] && [ "${2:-}" = -c ] &&
    { [ "${4:-}" = px-kill ] || [ "${4:-}" = px-term ]; }; then
    fk_code=137
    [ "$4" = px-kill ] || fk_code=143
    [ ! -f "$st/kill-lags" ] || cp "$pods" "$pods.now"
    [ ! -f "$st/kill-lists-fail" ] || mv -f "$st/kill-lists-fail" "$st/lists-fail"
    if [ "$(fk_field "$fk_name" 24)" = StatefulSet ]; then
      fk_set "$fk_name" "16=$fk_code" 17=Error 18=2026-01-03T00:00:02Z 10=False 25=warm2 \
        "19=$(($(fk_field "$fk_name" 19) + 1))" 11=2026-01-03T00:00:03Z
    else
      fk_set "$fk_name" 8=Failed 10=False 11= "13=$fk_code" 14=Error 15=2026-01-03T00:00:02Z
    fi
    if [ -f "$st/kill-lags" ]; then
      mv -f "$pods" "$st/k8s-next.txt"
      mv -f "$pods.now" "$pods"
      mv -f "$st/kill-lags" "$st/k8s-next-after"
    fi
    exit 0
  fi
  export PX_FAKE_SERVICE=$fk_name
  # kubectl exec -i gives all of its stdin to the command, as the Compose fake
  # does for an exec without a terminal.
  cat >"$st/exec-stdin.$$"
  exec <"$st/exec-stdin.$$"
  rm -f "$st/exec-stdin.$$"
  if [ "${1:-}" = px-region-count ]; then
    exec "$fk_dir/fake-region-count"
  fi
  fk_cmd=()
  for fk_arg in "$@"; do
    fk_cmd+=("${fk_arg//\/tmp\/px\//$st/remote/}")
  done
  exec "${fk_cmd[@]}"
  ;;
logs)
  fk_name=${fk_pos[0]:-}
  if [ "$fk_prev" = 1 ]; then
    cat "$st/previous-$fk_name.txt" 2>/dev/null || printf 'fake previous log %s\n' "$fk_name"
    exit 0
  fi
  if [ -f "$st/logs-$fk_name.txt" ]; then
    cat "$st/logs-$fk_name.txt"
    exit 0
  fi
  printf 'fake log out %s\n' "$fk_name"
  ;;
cp)
  # The remote work directory /tmp/px lives in the state directory, as for
  # the Compose fake. Any other remote path holds copy-out.txt.
  fk_source=${1:-}
  fk_target=${2:-}
  case "$fk_target" in
  *:/tmp/px/*)
    fk_path="$st/remote/${fk_target#*:/tmp/px/}"
    rm -rf "$fk_path"
    mkdir -p "${fk_path%/*}"
    cp -R "$fk_source" "$fk_path"
    ;;
  *:*) ;;
  *)
    case "$fk_source" in
    *:/tmp/px/*)
      fk_path="$st/remote/${fk_source#*:/tmp/px/}"
      [ -e "$fk_path" ] || exit 1
      cp -R "$fk_path" "$fk_target"
      ;;
    *)
      if [ -f "$st/copy-out.txt" ]; then
        cp "$st/copy-out.txt" "$fk_target"
      else
        printf 'fake copy\n' >"$fk_target"
      fi
      ;;
    esac
    ;;
  esac
  ;;
top)
  if [ -e "$st/no-metrics" ]; then
    printf 'error: Metrics API not available\n' >&2
    exit 1
  fi
  while IFS= read -r fk_line; do
    fk_match "$fk_line" "$fk_sel" || continue
    [ -n "$(printf '%s' "$fk_line" | cut -d'|' -f11)" ] || continue
    printf '%s px 253m %s\n' "${fk_line%%|*}" "$(cat "$st/top-mem" 2>/dev/null || echo 1024Mi)"
  done <"$pods"
  ;;
esac
exit 0
SHIM_EOF
  chmod +x "$T_SHIM_DIR/kubectl"
  t_shim_state_reset
}

## @description  Materializes the sweep cluster deployment directory, instances, and event records.
## @return       Populates the sweep cluster deployment and runtime state. Exit 0.
t_fixtures_sweep() {
  T_SWEEP_STATE="$T_WORK/sweep-state"
  T_SWEEP_OUT="$T_WORK/sweep-out"
  T_DEPLOY="$T_WORK/deploy"
  mkdir -p "$T_SWEEP_STATE" "$T_SWEEP_OUT" "$T_DEPLOY" "$T_WORK/no-deploy"
  # Spawn a real background process to stand in for the instance daemon JVM. It ignores every
  # catchable signal exercised by the test cases so its kernel process state reflects STOP and CONT
  # transitions; it self-terminates after an hour if the exit hook has not killed it first.
  cat >"$T_WORK/fake-jvm.sh" <<'SWEEP_EOF'
trap '' HUP INT QUIT USR1 USR2 TERM
n=0
while [ "$n" -lt 3600 ]; do
  sleep 1
  n=$((n + 1))
done
SWEEP_EOF
  "$T_BASH" "$T_WORK/fake-jvm.sh" </dev/null >/dev/null 2>&1 &
  T_SWEEP_JVM=$!
  px_on_exit "kill -s KILL $T_SWEEP_JVM 2>/dev/null"
  printf '%s\n' "$T_SWEEP_JVM" >"$T_SWEEP_STATE/jvm-pid"
  printf 'sweep\n' >"$T_SWEEP_STATE/cluster"
  printf 'px-sweep_default\n' >"$T_SWEEP_STATE/networks.txt"
  printf 'px-sweep_data\n' >"$T_SWEEP_STATE/volumes.txt"
  cat >"$T_SWEEP_STATE/provision.txt" <<'SWEEP_EOF'
ms01|master|px-sweep-master-1|created|Created|px:selftest|master|1|0|||
rs01|regionserver|px-sweep-regionserver-1|created|Created|px:selftest|regionserver|1||||regionmover
rs02|regionserver|px-sweep-regionserver-2|created|Created|px:selftest|regionserver|2||||regionmover
nm01|nodemanager|px-sweep-nodemanager-1|created|Created|px:selftest|nodemanager|1||yarn|on-demand|
SWEEP_EOF
  cat >"$T_DEPLOY/compose.yaml" <<'SWEEP_EOF'
services:
  master:
    mem_limit: 512m
    labels:
      px.harness.cluster: "sweep"
      px.harness.role: "master"
  regionserver:
    scale: 2
    mem_limit: 768m
    labels:
      px.harness.cluster: "sweep"
      px.harness.drain: "regionmover"
      px.harness.role: "regionserver"
  nodemanager:
    mem_limit: 512m
    labels:
      px.harness.cluster: "sweep"
      px.harness.group: "yarn"
      px.harness.role: "nodemanager"
      px.harness.start: "on-demand"
SWEEP_EOF
  # Materialize the rendered deployment metadata files that every run bundle copies.
  printf '%s\n' '# Rendered by compose.gen.sh. Render again instead of editing.' \
    PX_CLUSTER_ID=sweep PX_PHOENIX_HBASE_PROFILE=2.6 PX_DIST_HBASE_VERSION=2.6.7 \
    PX_GROUPS=yarn >"$T_DEPLOY/topology.env"
  printf '# service role index group start mem_limit_mib heap_mib direct_mib jdk\n' \
    >"$T_DEPLOY/services.tsv"
  printf 'PX_PORT_MASTER_UI=1\nPX_PORT_REGIONSERVER_RPC_0=2\n' \
    >"$T_DEPLOY/ports.generated.env"
}

## @description  Materializes the environment files for each stage of the precedence test case.
## @return       Populates the configuration environment fixture directory. Exit 0.
t_fixtures_config() {
  local stage name count
  mkdir -p "$T_WORK/env"
  # Generate the three precedence stages below defaults: version pins, local version pins, and the
  # port map. Each stage N assigns the value N to the first N timeout variables.
  for stage in 6:versions 7:versions-local 8:ports; do
    count=0
    for name in DEFAULT READY EXEC SCALE GROUP DRAIN FETCH PROVISION; do
      count=$((count + 1))
      [ "$count" -gt "${stage%%:*}" ] || printf 'PX_TIMEOUT_%s=%s\n' "$name" "${stage%%:*}"
    done >"$T_WORK/env/${stage#*:}.env"
  done
  cat >"$T_WORK/env/defaults.env" <<'ENV_EOF'
PX_TIMEOUT_DEFAULT=5
PX_TIMEOUT_READY=5
PX_TIMEOUT_EXEC=5
PX_TIMEOUT_SCALE=5
PX_TIMEOUT_GROUP=5
ENV_EOF
  cat >"$T_WORK/env/class-selftest.env" <<'ENV_EOF'
PX_TIMEOUT_READY=4
PX_TIMEOUT_EXEC=4
PX_TIMEOUT_SCALE=4
PX_TIMEOUT_GROUP=4
ENV_EOF
  cat >"$T_WORK/env/local.env" <<'ENV_EOF'
PX_TIMEOUT_EXEC=3
PX_TIMEOUT_SCALE=3
PX_TIMEOUT_GROUP=3
ENV_EOF
  cat >"$T_WORK/env/named.env" <<'ENV_EOF'
# A quoted value loses exactly one surrounding pair of quotes.
PX_TIMEOUT_SCALE = "2"
PX_TIMEOUT_GROUP=2
ENV_EOF
  printf 'PX_NOT_A_DECLARED_NAME=1\n' >"$T_WORK/env/undeclared.env"
  printf 'this line is not an assignment\n' >"$T_WORK/env/malformed.env"
  printf 'export PX_TIMEOUT_EXEC=9\n' >"$T_WORK/env/exported.env"
}

## @description  Writes the two Layer 1 fixture trees for violations and stale allowlist entries.
## @return       Populates the Layer 1 static scan fixture trees. Exit 0.
t_fixtures_scan() {
  mkdir -p "$T_WORK/scan/bin" "$T_WORK/scan/lib/backend" "$T_WORK/scan/lib" \
    "$T_WORK/scan/drivers/psql" "$T_WORK/scan/drivers/evil" \
    "$T_WORK/scan/suites/lib" "$T_WORK/scan/plugins/evil"
  cat >"$T_WORK/scan/bin/px-run" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture that bypasses the substrate contract. The scan must report it.
docker ps
SCAN_EOF
  cat >"$T_WORK/scan/drivers/psql/driver.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture driver that calls the runtime by name.
docker ps
SCAN_EOF
  cat >"$T_WORK/scan/drivers/evil/driver.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture driver that calls the runtime through the engine variable.
command "$PX_DOCKER_BIN" ps
SCAN_EOF
  cat >"$T_WORK/scan/suites/lib/common.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture suite library that calls a Kubernetes tool.
sx_evil() { kubectl delete pod x; }
SCAN_EOF
  cat >"$T_WORK/scan/plugins/evil/plugin.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture plug-in that calls three Kubernetes tools, one on each line.
helm list
k3d cluster list
crictl ps
SCAN_EOF
  cat >"$T_WORK/scan/lib/backend/compose.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture backend. The allowlist covers this path, so the scan is quiet.
docker ps
SCAN_EOF
  cat >"$T_WORK/scan/lib/commented.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture whose only token sits in a comment: docker ps
SCAN_EOF
  cat >"$T_WORK/scan/lib/waived.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
noop() { :; } # px-allow-docker: a waived line names its reason, docker ps
SCAN_EOF
  mkdir -p "$T_WORK/stale/lib/backend"
  cat >"$T_WORK/stale/lib/backend/compose.sh" <<'SCAN_EOF'
#!/usr/bin/env bash
# A fixture backend that no longer reaches a runtime, so its allowlist entry
# is stale and the scan must say so.
noop() { :; }
SCAN_EOF
  # Create the corresponding stale-allowlist fixture for the Kubernetes backend, which references a
  # container runtime token but contains no kubectl token.
  printf '#!/usr/bin/env bash\nnoop() { :; } # px-allow-docker: docker\n' \
    >"$T_WORK/stale/lib/backend/k8s.sh"
}

## @description  Writes probe scripts that exercise individual library functions in child processes.
## @return       Populates the library probe script directory. Exit 0.
t_fixtures_probes() {
  mkdir -p "$T_WORK/probe"
  cat >"$T_WORK/probe/term-when.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
# Runs COMMAND, sends it TERM once a file NAME newer than MARK appears under DIR, and exits with
# the status of COMMAND: term-when.sh DIR NAME MARK COMMAND...
probe_dir=$1 probe_name=$2 probe_mark=$3
shift 3
"$@" &
probe_pid=$!
probe_tries=0
while [ "$probe_tries" -lt 1800 ] &&
  [ -z "$(find "$probe_dir" -name "$probe_name" -newer "$probe_mark" 2>/dev/null | sed -n 1p)" ]; do
  sleep 0.1
  probe_tries=$((probe_tries + 1))
done
kill -s TERM "$probe_pid" 2>/dev/null
probe_status=0
wait "$probe_pid" || probe_status=$?
exit "$probe_status"
PROBE_EOF
  cat >"$T_WORK/probe/trap-direct.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
docker ps
printf 'the call returned\n'
PROBE_EOF
  cat >"$T_WORK/probe/trap-subshell.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
( podman run --rm nothing ) 2>/dev/null
printf 'subshell status %s\n' "$?"
PROBE_EOF
  cat >"$T_WORK/probe/trap-set-e.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
set -e
nerdctl ps
printf 'the call returned\n'
PROBE_EOF
  cat >"$T_WORK/probe/trap-names.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
for probe_name in "$@"; do
  if ! declare -F "$probe_name" >/dev/null 2>&1; then
    printf '%s undefined\n' "$probe_name"
    continue
  fi
  probe_status=0
  ( "$probe_name" version ) 2>/dev/null || probe_status=$?
  printf '%s %s\n' "$probe_name" "$probe_status"
done
PROBE_EOF
  cat >"$T_WORK/probe/shim-names.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
# This probe sources no library, so each name resolves through PATH alone.
for probe_name in "$@"; do
  "$probe_name" version
done
PROBE_EOF
  # The exit status of the contract function becomes the exit status of the probe so test cases can
  # inspect both the exit code and standard output of a single contract call. The shell runs under
  # set -u because every command executes the substrate contract under that option.
  cat >"$T_WORK/probe/sb.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/backend/contract.sh"
set -u
sb_load
"$@"
PROBE_EOF
  # Invokes a contract call with the report-only ERR trap armed, matching how commands such as
  # px-run execute substrate operations.
  cat >"$T_WORK/probe/sb-trap.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/backend/contract.sh"
set -u
px_trap_enable
sb_load
"$@"
PROBE_EOF
  # Clear PX_TIMEOUT_BIN to force execution through the pure-shell timeout fallback on every host
  # regardless of whether GNU timeout is installed.
  cat >"$T_WORK/probe/timeout.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
PX_TIMEOUT_BIN=""
printf 'through the fallback\n' | px_timeout 5 cat
printf 'stdin %s\n' "$?"
probe_status=0
px_timeout 1 sleep 30 || probe_status=$?
printf 'expiry %s\n' "$probe_status"
PROBE_EOF
  # The fallback watchdog resolves sleep via PATH. This wrapper records its PID before execing
  # /bin/sleep so the test can verify that no watchdog sleep process outlives px_timeout, while
  # the target commands invoke /bin/sleep directly so only watchdog instances are logged.
  mkdir -p "$T_WORK/nap"
  cat >"$T_WORK/nap/sleep" <<'PROBE_EOF'
#!/bin/sh
printf '%s\n' "$$" >>"${0%/*}/naps"
exec /bin/sleep "$@"
PROBE_EOF
  chmod +x "$T_WORK/nap/sleep"
  cat >"$T_WORK/probe/timeout-nap.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
PX_TIMEOUT_BIN=""
PATH="$1:$PATH"
px_timeout 600 /bin/sleep 1
px_timeout 1 /bin/sleep 30 || true
[ -s "$1/naps" ] || printf 'no sleep was recorded\n'
while IFS= read -r probe_pid; do
  if kill -s 0 "$probe_pid" 2>/dev/null; then
    printf 'sleep %s outlived px_timeout\n' "$probe_pid"
  fi
done <"$1/naps"
PROBE_EOF
  cat >"$T_WORK/probe/config-load.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_config_load "$@"
px_config_dump
PROBE_EOF
  # Executes the memory recheck for a group start against a copy of the harness passed as the
  # first argument.
  cat >"$T_WORK/probe/memory-recheck.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$1/lib/common.sh"
px_memory_recheck
printf 'status %s\n' "$?"
PROBE_EOF
  # Runs the compatibility range check for a version set and prints the resolved profile and
  # HBase release version.
  cat >"$T_WORK/probe/versions-check.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_config_load
px_versions_check
printf '%s %s\n' "$PX_PHOENIX_HBASE_PROFILE" "$PX_DIST_HBASE_VERSION"
PROBE_EOF
  cat >"$T_WORK/probe/config-strict.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_config_load --strict "$@"
printf 'the load returned\n'
PROBE_EOF
  cat >"$T_WORK/probe/out-root.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_out_root
PROBE_EOF
  cat >"$T_WORK/probe/out-resolve.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_out_resolve "$1"
PROBE_EOF
  cat >"$T_WORK/probe/run-ids.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
probe_count=0
while [ "$probe_count" -lt 8 ]; do
  px_run_id
  probe_count=$((probe_count + 1))
done
px_run_dir
px_run_dir
PROBE_EOF
  cat >"$T_WORK/probe/json.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_json_escape "$(printf 'a"b\\c\td\ne\001f')"
printf '\n'
px_json_open
px_json_str name 'the "run"'
px_json_arr roles
px_json_str rs
px_json_open
px_json_num index 0
px_json_close
px_json_close
px_json_close
PROBE_EOF
  cat >"$T_WORK/probe/json-number.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_json_open
px_json_num count "$1"
px_json_close
PROBE_EOF
  cat >"$T_WORK/probe/tsv.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
probe_status=0
px_tsv_row alpha "$(printf 'has\ttab')" gamma || probe_status=$?
printf 'tab %s\n' "$probe_status"
probe_status=0
px_tsv_row alpha "$(printf 'has\nnewline')" gamma || probe_status=$?
printf 'newline %s\n' "$probe_status"
probe_row="$(px_tsv_row alpha '' gamma)"
printf 'second [%s]\n' "$(px_tsv_field 2 "$probe_row")"
printf 'third [%s]\n' "$(px_tsv_field 3 "$probe_row")"
px_tsv_field 9 "$probe_row" >/dev/null
printf 'absent %s\n' "$?"
printf 'clean [%s]\n' "$(px_tsv_clean "$(printf 'x\ty\nz\r')")"
PROBE_EOF
}

## @description  Materializes deployment-layer fakes for tools, JDKs, archives, and a source tree.
## @return       Populates the deployment-layer fixture workspace. Exit 0.
t_fixtures_a2() {
  local real_uname name line zk client jar
  T_A2="$T_WORK/a2"
  T_A2_BIN="$T_A2/bin"
  T_A2_STATE="$T_A2/state"
  T_A2_CURL_DIR="$T_A2/curl"
  T_A2_CURL_LOG="$T_A2/curl.log"
  T_A2_MVN_LOG="$T_A2/mvn.log"
  T_GEN="$PX_HOME/$PX_DEPLOY_SUBDIR/compose.gen.sh"
  T_IMG="$PX_HOME/$PX_DEPLOY_SUBDIR/image"
  T_ENVD="$PX_HOME/$PX_DEPLOY_SUBDIR/env"
  mkdir -p "$T_A2_BIN" "$T_A2_STATE" "$T_A2_CURL_DIR" "$T_A2/out" "$T_A2/jdk/11/bin" \
    "$T_A2/jdk/17/bin" "$T_A2/jdk/21/bin" "$T_A2/hadoop/bin" "$T_A2/hbase/bin" \
    "$T_A2/phoenix/server" "$T_A2/tree" "$T_A2/dist-src" "$T_A2/decoy"
  T_A2_OUT="$(px_realpath "$T_A2/out")"
  : >"$T_A2_CURL_LOG"
  : >"$T_A2_MVN_LOG"
  printf 'a2\n' >"$T_A2_STATE/cluster"
  # Create a decoy runtime binary discoverable only via PATH; any process that ignores
  # PX_DOCKER_BIN invokes this decoy, which logs the call and fails.
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>"%s"\nexit 1\n' "$T_A2/decoy.log" \
    >"$T_A2/decoy/docker"

  cat >"$T_A2_BIN/curl" <<'A2_EOF'
#!/usr/bin/env bash
# A fixture that stands in for curl. It records every argument list, and it
# answers from a directory where a file named after the address holds the
# body. An address with no file fails the way curl --fail fails.
[ -z "${PX_FAKE_CURL_LOG:-}" ] || printf '%s\n' "$*" >>"$PX_FAKE_CURL_LOG"
out="" url=""
while [ $# -gt 0 ]; do
  case "$1" in
  -o) out=$2; shift ;;
  --max-time | --connect-timeout | --retry | --proto | --proto-redir | --noproxy) shift ;;
  http://* | https://*) url=$1 ;;
  esac
  shift
done
key="$(printf '%s' "$url" | LC_ALL=C tr -c 'A-Za-z0-9' '_')"
body="${PX_FAKE_CURL_DIR:-/nonexistent}/$key"
if [ ! -f "$body" ]; then
  printf 'curl: (22) The requested URL returned error: 404\n' >&2
  exit 22
fi
if [ -n "$out" ]; then
  cp "$body" "$out"
else
  cat "$body"
fi
A2_EOF

  cat >"$T_A2_BIN/mvn" <<'A2_EOF'
#!/usr/bin/env bash
# A fixture that stands in for Maven. It records the JDK and the argument
# list, and it writes the jar of each module of -pl under its real name, so a
# build record names files that exist. With PX_FAKE_MVN_NO_SHADED set, it
# writes no module jar. With -pl it also writes the class path of
# phoenix-core-client, which names an old bson jar of a local repository and
# a jar with a similar name. With PX_FAKE_MVN_NO_BSON set, the class path names
# no bson jar. Without -pl it writes the product that the case names.
[ -z "${PX_FAKE_MVN_LOG:-}" ] || printf 'JAVA_HOME=%s %s\n' "${JAVA_HOME:-}" "$*" \
  >>"$PX_FAKE_MVN_LOG"
printf '[INFO] fake build\n'
[ "${PX_FAKE_MVN_STATUS:-0}" = 0 ] || exit "$PX_FAKE_MVN_STATUS"
pom="" list="" profile=""
while [ $# -gt 0 ]; do
  case "$1" in
  -f) pom=$2; shift ;;
  -pl) list=$2; shift ;;
  -Dhbase.profile=*) profile=${1#*=} ;;
  esac
  shift
done
tree=${pom%/pom.xml}
for module in ${list//,/ }; do
  case "$module" in
  phoenix-server) jar="phoenix-server-hbase-$profile-5.4.0-SNAPSHOT.jar" ;;
  */phoenix-client-embedded) jar="phoenix-client-embedded-hbase-$profile-5.4.0-SNAPSHOT.jar" ;;
  *) jar="${module##*/}-5.4.0-SNAPSHOT.jar" ;;
  esac
  mkdir -p "$tree/$module/target"
  [ -n "${PX_FAKE_MVN_NO_SHADED:-}" ] ||
    printf 'fake jar %s\n' "$module" >"$tree/$module/target/$jar"
done
if [ -n "$list" ]; then
  repo=${tree%/*}/m2/org/mongodb
  mkdir -p "$repo/bson/5.3.1" "$tree/phoenix-core-client/target"
  printf 'fake bson\n' >"$repo/bson/5.3.1/bson-5.3.1.jar"
  touch -t 201901010000 "$repo/bson/5.3.1/bson-5.3.1.jar"
  cp="$repo/bson-record-codec/5.3.1/bson-record-codec-5.3.1.jar"
  [ -n "${PX_FAKE_MVN_NO_BSON:-}" ] || cp="$cp:$repo/bson/5.3.1/bson-5.3.1.jar"
  printf '%s\n' "$cp" >"$tree/phoenix-core-client/target/cached_classpath.txt"
  # The root pom writes the test class path of every module, as build-classpath does: one line,
  # no newline at its end. The test jars on it carry a log4j2-test configuration.
  mkdir -p "$tree/phoenix-pherf/target/classes"
  rm -f "$tree/phoenix-pherf/target/classes/mrapp-generated-classpath"
  [ -n "${PX_FAKE_MVN_NO_PHERF_CP:-}" ] || printf '%s' "${tree%/*}/m2/javax/jaxb-api-2.3.1.jar:\
$tree/phoenix-core/target/phoenix-core-5.4.0-SNAPSHOT-tests.jar:\
${tree%/*}/m2/log4j/log4j-slf4j-impl-2.25.5.jar:${tree%/*}/m2/hbase/hbase-server-2.6.5-tests.jar" \
    >"$tree/phoenix-pherf/target/classes/mrapp-generated-classpath"
fi
if [ -z "$list" ] && [ -n "${PX_FAKE_MVN_PRODUCT:-}" ]; then
  mkdir -p "$tree/${PX_FAKE_MVN_PRODUCT%/*}"
  printf 'fake product\n' >"$tree/$PX_FAKE_MVN_PRODUCT"
fi
A2_EOF

  real_uname="$(command -v uname)"
  cat >"$T_A2_BIN/uname" <<'A2_EOF'
#!/usr/bin/env bash
# A fixture that names the machine architecture and passes every other
# question to the real uname.
if [ "${1:-}" = -m ]; then
  printf '%s\n' "${PX_FAKE_UNAME_M:-arm64}"
  exit 0
fi
A2_EOF
  printf 'exec %q "$@"\n' "$real_uname" >>"$T_A2_BIN/uname"

  for name in 11 17 21; do
    cat >"$T_A2/jdk/$name/bin/java" <<A2_EOF
#!/usr/bin/env bash
# A fixture JDK. It answers a version request the way a JDK does.
printf 'openjdk version "$name.0.1" 2021-10-19\n' >&2
A2_EOF
  done

  cat >"$T_A2/hadoop/bin/hdfs" <<'A2_EOF'
#!/usr/bin/env bash
# A fixture Hadoop launcher. A format writes the namespace marker, and every
# other call exits with the status the case asks for.
case "$*" in
'namenode -format'*)
  mkdir -p "$PX_DATA_DIR/namenode/current"
  : >"$PX_DATA_DIR/namenode/current/VERSION"
  ;;
esac
exit "${PX_FAKE_HDFS_STATUS:-0}"
A2_EOF
  printf '#!/usr/bin/env bash\nexit 0\n' >"$T_A2/hbase/bin/hbase"
  chmod +x "$T_A2_BIN"/* "$T_A2"/jdk/*/bin/java "$T_A2/hadoop/bin/hdfs" "$T_A2/hbase/bin/hbase" \
    "$T_A2/decoy/docker"

  cat >"$T_A2/sha-probe.sh" <<'A2_EOF'
#!/usr/bin/env bash
# A probe of the SHA-512 helper with one PATH: the first argument.
. "$PX_HOME/lib/common.sh"
PATH=$1
px_sha512 "$2"
A2_EOF
  cat >"$T_A2/platform-probe.sh" <<'A2_EOF'
#!/usr/bin/env bash
# A probe of the other platform helpers with one PATH: the first argument.
. "$PX_HOME/lib/common.sh"
PATH=$1
probe_ms="$(px_epoch_ms)"
[[ $probe_ms =~ ^[0-9]{13}$ ]] && probe_ms=milliseconds
printf '%s %s %s\n' "$(px_nproc)" "$probe_ms" "$(px_realpath "$2")"
A2_EOF

  # Construct a synthetic Maven source tree containing a root POM with the version properties and
  # profiles inspected by the build, along with a module POM for each component. Profile 2.6.3
  # intentionally maps to a module that the harness compatibility table does not list for it.
  cat >"$T_A2/tree/pom.xml" <<'A2_EOF'
<project>
  <parent>
    <version>99</version>
  </parent>
  <version>5.4.0-SNAPSHOT</version>
  <properties>
    <hadoop.version>3.4.2</hadoop.version>
    <hbase-2.5.runtime.version>2.5.14-hadoop3</hbase-2.5.runtime.version>
    <hbase-2.6.3.runtime.version>2.6.3-hadoop3</hbase-2.6.3.runtime.version>
    <hbase-2.6.runtime.version>2.6.5-hadoop3</hbase-2.6.runtime.version>
  </properties>
  <profiles>
    <profile>
      <activation>
        <property>
          <name>hbase.profile</name>
          <value>2.5</value>
        </property>
      </activation>
      <properties>
        <hbase.compat.version>2.5.4</hbase.compat.version>
        <hadoop.version>3.3.6</hadoop.version>
        <hbase.version>${hbase-2.5.runtime.version}</hbase.version>
      </properties>
    </profile>
    <profile>
      <activation>
        <property>
          <name>hbase.profile</name>
          <value>2.6.3</value>
        </property>
      </activation>
      <properties>
        <hbase.compat.version>2.6.4</hbase.compat.version>
        <hbase.version>${hbase-2.6.3.runtime.version}</hbase.version>
      </properties>
    </profile>
    <profile>
      <activation>
        <property>
          <name>hbase.profile</name>
          <value>2.6</value>
        </property>
      </activation>
      <properties>
        <hbase.compat.version>2.6.4</hbase.compat.version>
        <hbase.version>${hbase-2.6.runtime.version}</hbase.version>
      </properties>
    </profile>
  </profiles>
</project>
A2_EOF
  for name in phoenix-server phoenix-client-parent/phoenix-client-embedded phoenix-pherf; do
    mkdir -p "$T_A2/tree/$name/src/main"
    printf '<project/>\n' >"$T_A2/tree/$name/pom.xml"
  done
  touch -t 202001010000 "$T_A2"/tree/pom.xml "$T_A2"/tree/*/pom.xml \
    "$T_A2"/tree/*/*/pom.xml 2>/dev/null || true

  # Build the release archives served by the fetch layer, each containing a single top-level
  # directory. The jHiccup source release archive includes its POM file configured with language
  # level 1.6, matching the real upstream release. Each HBase release archive contains the bundled
  # ZooKeeper and Hadoop client JARs that it ships alongside JARs whose names only resemble them,
  # whereas HBase 2.6.5 omits ZooKeeper and ships two Hadoop client JARs.
  mkdir -p "$T_A2/dist-src/jHiccup-2.0.10"
  printf '<project><source>1.6</source><target>1.6</target></project>\n' \
    >"$T_A2/dist-src/jHiccup-2.0.10/pom.xml"
  (cd "$T_A2/dist-src" && tar -czf jHiccup-2.0.10.tar.gz jHiccup-2.0.10)
  for line in hbase-2.6.7:3.8.6:3.4.1 hbase-2.6.6:3.8.5:3.4.0 hbase-2.5.15:3.8.4:3.3.6; do
    IFS=: read -r name zk client <<<"$line"
    mkdir -p "$T_A2/dist-src/$name/lib"
    for jar in "zookeeper-$zk" "zookeeper-jute-$zk" "hadoop-common-$client" \
      "hadoop-common-$client-tests"; do
      : >"$T_A2/dist-src/$name/lib/$jar.jar"
    done
  done
  mkdir -p "$T_A2/dist-src/hbase-2.6.5/lib"
  : >"$T_A2/dist-src/hbase-2.6.5/lib/hadoop-common-3.4.1.jar"
  : >"$T_A2/dist-src/hbase-2.6.5/lib/hadoop-common-3.4.2.jar"
  for name in hbase-2.6.7 hbase-2.6.6 hbase-2.6.5 hbase-2.5.15 hadoop-3.4.3 hadoop-3.4.2 \
    async-profiler-4.1; do
    mkdir -p "$T_A2/dist-src/$name/bin"
    printf 'fixture %s\n' "$name" >"$T_A2/dist-src/$name/bin/tool"
    (cd "$T_A2/dist-src" && tar -czf "$name.tar.gz" "$name")
  done
}

## @description  Materializes the host-program fakes, drivers, and state for the host-safety test
##               cases, placing the inhibitors and uptime first on PATH for the entire run.
## @return       Exit 0.
t_fixtures_a3() {
  local name os real_uname
  T_A3="$T_WORK/a3"
  T_HOST_DIR="$T_WORK/host"
  T_A3_STATE="$T_A3/state"
  mkdir -p "$T_HOST_DIR" "$T_A3_STATE" "$T_A3/out" "$T_A3/Darwin" "$T_A3/Linux" \
    "$T_A3/SunOS" "$T_A3/lowdisk" "$T_A3/onecpu" "$T_A3/quickexit"
  T_A3_OUT="$(px_realpath "$T_A3/out")"
  : >"$T_HOST_DIR/inhibit.log"
  : >"$T_HOST_DIR/uptime.log"
  # Each fake inhibitor records its argument list and blocks in the same manner as the real utility:
  # a single process executing under the program's own name. The fake caffeinate blocks for two
  # minutes, whereas the fake systemd-inhibit executes the command following its options just as the
  # real binary does so that an owner loop terminates it.
  sed -e "s|@HOST@|$T_HOST_DIR|" >"$T_HOST_DIR/caffeinate" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for caffeinate.
printf '%s %s\n' "${0##*/}" "$*" >>"@HOST@/inhibit.log"
exec -a "${0##*/}" sleep 120
A3_EOF
  sed -e "s|@HOST@|$T_HOST_DIR|" >"$T_HOST_DIR/systemd-inhibit" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for systemd-inhibit. It runs the command after its
# options under its own name.
printf '%s %s\n' "${0##*/}" "$*" >>"@HOST@/inhibit.log"
while [ "$#" -gt 0 ]; do
  case "$1" in
  --*) shift ;;
  *) break ;;
  esac
done
exec -a "${0##*/}" "$@"
A3_EOF
  sed -e "s|@HOST@|$T_HOST_DIR|" >"$T_HOST_DIR/uptime" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for uptime. It records each call and reports the
# load that the file load holds, else an idle host.
printf 'uptime\n' >>"@HOST@/uptime.log"
load="$(cat "@HOST@/load" 2>/dev/null || printf '0.00')"
printf ' 1:05  up 6 days, 2 users, load averages: %s %s %s\n' "$load" "$load" "$load"
A3_EOF
  real_uname="$(command -v uname)"
  for os in Darwin Linux SunOS; do
    sed -e "s|@OS@|$os|" -e "s|@REAL@|$real_uname|" >"$T_A3/$os/uname" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that names one kernel and passes every other question on.
if [ "${1:-}" = -s ]; then
  printf '%s\n' "@OS@"
  exit 0
fi
exec "@REAL@" "$@"
A3_EOF
  done
  sed -e "s|@HOST@|$T_HOST_DIR|" >"$T_A3/Darwin/vm_stat" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for vm_stat. The file free-pages holds the free
# pages, else 4 GiB of them.
pages="$(cat "@HOST@/free-pages" 2>/dev/null || printf '1048576')"
printf 'Mach Virtual Memory Statistics: (page size of 4096 bytes)\n'
printf 'Pages free:%30s.\nPages active:%28s.\n' "$pages" 1000
printf 'Pages inactive:%26s.\nPages speculative:%23s.\n' 0 0
A3_EOF
  cat >"$T_A3/lowdisk/df" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for df. It reports 1 MiB free.
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
printf '/dev/fake 10240 9216 1024 90%% /\n'
A3_EOF
  cat >"$T_A3/heartbeat-probe.sh" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that runs the monitors check of the bootstrap probe, from the
# image library its first argument names.
. "$1/lib/image.sh" && img_check_monitors && printf '%s\n' "$IMG_REASON"
A3_EOF
  cat >"$T_A3/onecpu/getconf" <<'A3_EOF'
#!/usr/bin/env bash
# A fixture that stands in for getconf. The host has one processor.
printf '1\n'
A3_EOF
  # Create a fake caffeinate binary that terminates immediately. Referencing a nonexistent
  # interpreter causes exec to fail outright without spawning a subshell that a heavily loaded host
  # could delay past the sleep inhibitor's liveness check.
  printf '#!/nonexistent/px-no-interpreter\n' >"$T_A3/quickexit/caffeinate"
  chmod +x "$T_HOST_DIR"/* "$T_A3"/Darwin/* "$T_A3"/Linux/* "$T_A3"/SunOS/* "$T_A3"/lowdisk/* \
    "$T_A3"/onecpu/* "$T_A3"/quickexit/*
  cat >"$T_WORK/drivers/restart-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It raises the restart count, then works on for 30 seconds.
printf '1\n' >"$PX_FAKE_STATE_DIR/restart-count"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/start-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It restarts the one instance as "docker restart" does: the
# start time changes, the restart count stays 0 and the state stays running.
# Then it works on for 30 seconds.
printf '2026-01-02T00:00:00Z\n' >"$PX_FAKE_STATE_DIR/started-c0ffee000001"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/exit-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. The one instance exits with a failure code, not an
# out-of-memory kill, then the driver works on for 30 seconds.
printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
  px-selftest-regionserver-1 exited 'Exited (1) 1 second ago' \
  phoenix-harness:selftest regionserver 1 0 >"$PX_FAKE_STATE_DIR/instances.txt"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/vanish-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It removes the one instance, then works on for 30 seconds.
: >"$PX_FAKE_STATE_DIR/instances.txt"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/oom-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. An out-of-memory kill ends the one instance, then the driver
# works on for 30 seconds.
printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
  px-selftest-regionserver-1 exited 'Exited (137) 1 second ago' \
  phoenix-harness:selftest regionserver 1 0 >"$PX_FAKE_STATE_DIR/instances.txt"
printf 'c0ffee000001\n' >"$PX_FAKE_STATE_DIR/oom-ids"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/sleep.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It records its process id in the run directory, then works
# for the seconds its first argument names.
[ -z "${PX_RUN_DIR:-}" ] || printf '%s\n' "$$" >"$PX_RUN_DIR/driver.pid"
exec sleep "${1:-30}"
DRIVER_EOF
  cat >"$T_WORK/drivers/host-probe.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. It prints the sleep inhibitor record of its run and the
# contamination of the run. It also prints its owner, px-run. Then it ends with
# the status its first argument names.
cat "$PX_OUT/state/inhibit-run-$PX_RUN_ID" 2>/dev/null || printf 'no record\n'
printf 'contamination [%s]\n' "${PX_CONTAMINATION:-}"
printf 'owner %s\n' "$PPID"
exit "${1:-0}"
DRIVER_EOF
  chmod +x "$T_WORK/drivers"/*.sh
}

## @description  Stops every monitor and sleep inhibitor identified by a fixture state record.
## @return       Exit 0.
t_daemons_stop() {
  local record
  while IFS= read -r record; do
    [ -z "$record" ] || px_guard_stop "$record"
  done <<EOF
$(find "$T_WORK" -type f \( -name 'monitor-*' -o -name 'inhibit-*' \) ! -name '*.heartbeat' \
    2>/dev/null)
EOF
  # Terminate any fake kubectl port-forward process left open by a test case, verifying the
  # process command line first so that a reused process ID is not signaled.
  while IFS= read -r record; do
    case "$(ps -p "${record:-0}" -o args= 2>/dev/null)" in
    *port-forward*) kill -s TERM "$record" 2>/dev/null || true ;;
    esac
  done <<EOF
$(cat "$T_WORK/forward.pids" 2>/dev/null)
EOF
}

## @description  Executes a command with the deployment-layer tool fakes and fake container runtime.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a2() {
  t_with_shim "PATH=$T_A2_BIN:$T_SHIM_DIR:$PATH" "PX_FAKE_STATE_DIR=$T_A2_STATE" \
    "PX_FAKE_CURL_DIR=$T_A2_CURL_DIR" "PX_FAKE_CURL_LOG=$T_A2_CURL_LOG" \
    "PX_FAKE_MVN_LOG=$T_A2_MVN_LOG" "PX_OUT=$T_A2_OUT" PX_POLL_INTERVAL_S=0.2 "$@"
}

## @description  Executes the deployment generator under the deployment-layer fixture environment.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_gen() { t_a2 "$T_BASH" "$T_GEN" "$@"; }

## @description  Registers a literal response body (address, text) to be served by the fake curl.
## @return       Writes the staged response file for the fake curl shim. Exit 0.
t_curl_body() { printf '%s' "$2" | t_curl_file "$1" /dev/stdin; }

## @description  Registers a file payload (address, file) to be served by the fake curl.
## @return       Copies the payload file into the fake curl response directory. Exit 0.
t_curl_file() { cat "$2" >"$T_A2_CURL_DIR/$(printf '%s' "$1" | LC_ALL=C tr -c 'A-Za-z0-9' '_')"; }

## @description  Extracts the effective environment of a rendered service (directory, service).
## @return       Writes one NAME=VALUE line per variable to stdout. Exit 0.
t_service_env() {
  local dir=$1 service=$2
  printf 'PX_DATA_DIR=%s\nPX_LOG_DIR=%s\nPX_HADOOP_HOME=%s\n' "$T_A2/data" \
    "$T_A2/logs" "$T_A2/hadoop"
  grep -v '^#' "$dir/ports.generated.env"
  grep -v '^#' "$dir/topology.env"
  awk -v s="  $service:" '
    $0 == s { f = 1; next }
    f && /^  [a-z]/ { f = 0 }
    f && /^    environment:/ { e = 1; next }
    f && e && /^    [a-z]/ { e = 0 }
    f && e { sub(/^ +/, ""); sub(/: /, "="); gsub(/"/, ""); print }
  ' "$dir/compose.yaml"
}

# Runs an image script under the rendered environment of a service (directory, service,
# NAME=VALUE..., --, command).
## @description  Executes an image script within a rendered service environment.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_in_service() {
  local dir=$1 service=$2 line
  local words=()
  shift 2
  while IFS= read -r line; do
    [ -n "$line" ] && words+=("$line")
  done <<EOF
$(t_service_env "$dir" "$service")
EOF
  t_image "${words[@]}" "$@"
}

## @description  Renders a service (directory, service, NAME=VALUE...) into an empty $T_A2/render.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_render() {
  local dir=$1 service=$2
  shift 2
  rm -rf "$T_A2/render"
  t_in_service "$dir" "$service" "$@" -- "$T_BASH" "$T_IMG/entrypoint.sh" --render-only \
    "$T_A2/render"
}

## @description  Executes an image script in a minimal environment (NAME=VALUE..., --, command...).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_image() {
  local words=()
  while [ $# -gt 0 ] && [ "$1" != -- ]; do
    words+=("$1")
    shift
  done
  shift
  t_run env -i "PATH=$T_A2_BIN:$PATH" "HOME=$T_A2" "TMPDIR=$T_A2" \
    "PX_FAKE_CURL_DIR=$T_A2_CURL_DIR" "PX_FAKE_CURL_LOG=$T_A2_CURL_LOG" \
    ${words[@]+"${words[@]}"} "$@"
}

## @description  Sums the memory limit column across the service table emitted by --check.
## @return       Writes the total memory sum to stdout. Exit 0.
t_mem_sum() { printf '%s\n' "$1" | awk -F '\t' 'NF { s += $6 } END { print s + 0 }'; }

## @description  Extracts space-joined variable values (file, name...) from an environment file.
## @return       Writes the resolved values to stdout. Exit 0.
t_env_value() {
  local file=$1 name out=""
  shift
  for name; do
    out="$out $(sed -n "s/^$name=//p" "$file" 2>/dev/null | sed -n 1p)"
  done
  printf '%s\n' "${out# }"
}

## @description  Extracts joined values for the specified keys (file, key...) from a key-value TSV.
## @return       Writes the resolved values to stdout. Exit 0.
t_tsv_values() {
  local file=$1 key out=""
  shift
  for key; do
    out="$out $(awk -F '\t' -v k="$key" '$1 == k { print $2; exit }' "$file" 2>/dev/null)"
  done
  printf '%s\n' "${out# }"
}

## @description  Extracts space-joined values for keys (key...) from the Phoenix build record.
## @return       Writes the resolved values to stdout. Exit 0.
t_build_record() { t_tsv_values "$T_A2_OUT/build/phoenix/build.tsv" "$@"; }

## @description  Writes the instance inventory produced by provisioning a deployment (directory).
## @return       Writes the provisioned container inventory file. Exit 0.
t_a2_provision() {
  local service role index group start drain n=0
  : >"$T_A2_STATE/provision.txt"
  while IFS="$PX_TAB" read -r service role index group start _; do
    case "$service" in '#'* | '') continue ;; esac
    n=$((n + 1))
    drain=""
    [ "$role" != regionserver ] || drain=regionmover
    printf 'a2%04d|%s|px-a2-%s-1|created|Created|px-harness:hbase-2.6.5|%s|1|%s|%s|%s|%s\n' \
      "$n" "$role" "$service" "$service" "$index" "$group" "$start" "$drain" \
      >>"$T_A2_STATE/provision.txt"
  done <"$1/services.tsv"
}

## @description  Creates the temporary workspace holding every output root and builds all fixtures.
## @return       Sets T_WORK and exports PX_OUT. Exit 0, or exits 1 if directory creation fails.
t_setup() {
  T_WORK="$(px_mktemp_dir px-selftest)" || exit "$PX_EXIT_FAIL"
  # Resolve the physical path of the fixture root up front because the output policy writes the
  # canonical physical path back to PX_OUT; temporary directories often traverse symbolic links,
  # and the two paths would otherwise fail string equality checks.
  T_WORK="$(px_realpath "$T_WORK")"
  T_OUT="$T_WORK/stdout.txt"
  T_ERR="$T_WORK/stderr.txt"
  : >"$T_OUT"
  : >"$T_ERR"
  mkdir -p "$T_WORK/out"
  PX_OUT="$(px_realpath "$T_WORK/out")"
  export PX_OUT
  # Place the fake inhibitors and fake uptime first on PATH so every command resolves them ahead of
  # host binaries. Configure any monitor started during cluster bring-up to sleep for an hour so it
  # takes no samples while a test case reads the runtime log, and rely on the exit hook to stop it.
  PATH="$T_WORK/host:$PATH"
  PX_MONITOR_INTERVAL_S=3600
  export PATH PX_MONITOR_INTERVAL_S
  px_on_exit t_daemons_stop
  t_fixtures_suites
  t_fixtures_drivers
  t_fixtures_shim
  t_fixtures_sweep
  t_fixtures_config
  t_fixtures_scan
  t_fixtures_probes
  t_fixtures_a2
  t_fixtures_a3
  t_fixtures_a5
  t_fixtures_a6
  t_h3_fixtures
  t_fixtures_k1
  t_fixtures_k2
}

# ---------------------------------------------------------------------------
# Layer 1: Static source scan and allowlist enforcement
# ---------------------------------------------------------------------------

## @description  Lists the paths permitted to hold a runtime token, each with its token class and
##               mandatory justification reason.
## @return       Writes path, class, and reason TSV rows to stdout. Exit 0.
t_allowlist() {
  px_tsv_row lib/backend/compose.sh compose "the Compose backend, the only runtime caller"
  px_tsv_row lib/backend/k8s.sh k8s "the Kubernetes backend, the only kubectl caller"
  px_tsv_row bin/px-k3d k3d "the local k3d manager, the only k3d caller"
  px_tsv_row test/run-tests.sh all "generates the fake runtime and the fake kubectl from heredocs"
}

## @description  Reports the permitted token class for a harness-relative file path.
## @return       Writes the class to stdout. Exit 0 if the allowlist covers the path, or 1 if not.
t_allowlisted() {
  local row
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    if [ "${row%%"$PX_TAB"*}" = "$1" ]; then
      row=${row#*"$PX_TAB"}
      printf '%s\n' "${row%%"$PX_TAB"*}"
      return 0
    fi
  done <<EOF
$(t_allowlist)
EOF
  return 1
}

## @description  Checks whether a matched line (class, text) contains a token outside the class.
## @return       Exit 0 when the line holds a foreign token; 1 when every token belongs to class.
t_foreign() {
  case "$1" in
  compose) printf '%s\n' "$2" | grep -qE "$T_NOT_COMPOSE_RE" ;;
  k8s) printf '%s\n' "$2" | grep -qE "$T_NOT_K8S_RE" ;;
  k3d) printf '%s\n' "$2" | grep -qE "$T_NOT_K3D_RE" ;;
  *) return 1 ;;
  esac
}

## @description  Determines whether a file (path) can be executed, sourced, or rendered.
## @return       Exit 0 when the file belongs to the scan. Exit 1 otherwise.
t_in_scope() {
  local first=""
  case "$1" in
  */bin/*) return 0 ;;
  *.sh | *.env | *.tmpl | *.suite) return 0 ;;
  esac
  IFS= read -r first <"$1" 2>/dev/null || first=""
  case "$first" in
  '#!'*sh | '#!'*sh[[:space:]]*) return 0 ;;
  esac
  return 1
}

## @description  Scans a directory tree (root, expression) for runtime tokens outside the allowlist.
## @return       Writes one path:line:text record per violation to stdout. Exit 0.
t_scan_root() {
  local root=$1 expression=${2:-$T_SCAN_RE} file rel hit text stripped class
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    t_in_scope "$file" || continue
    rel=${file#"$root"/}
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      text=${hit#*:}
      stripped=${text#"${text%%[![:space:]]*}"}
      case "$stripped" in '#'*) continue ;; esac
      case "$text" in *"$T_WAIVER"*) continue ;; esac
      if class="$(t_allowlisted "$rel")"; then
        t_foreign "$class" "$text" || continue
      fi
      printf '%s:%s\n' "$rel" "$hit"
    done <<EOF
$(grep -nE "$expression" "$file" 2>/dev/null || true)
EOF
  done <<EOF
$(find "$root" -type f 2>/dev/null | LC_ALL=C sort)
EOF
}

## @description  Reports functions in a directory tree (root) whose shelldoc violates convention C2.
## @return       Writes one path:function:reason record per violation to stdout. Exit 0.
t_shelldoc_gaps() {
  local root=$1 file
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    t_in_scope "$file" || continue
    # Heredoc bodies contain fixture data rather than function definitions belonging to the scanned
    # file, so the scanner skips them.
    awk -v rel="${file#"$root"/}" '
      tag != "" { if ($0 == tag) tag = ""; next }
      /^##/ {
        n++
        if ($0 ~ /@return/) r = 1
        if ($0 ~ /@audience|@stability/) print rel ":" NR ":retired tag"
        next
      }
      /^[a-z_][a-z0-9_]*\(\) *\{/ {
        name = $1; sub(/\(\).*/, "", name)
        if (n == 0) print rel ":" name ":no shelldoc"
        else if (!r) print rel ":" name ":no @return"
        else if (n > 3 && name !~ /^sb_/) print rel ":" name ":more than three lines"
      }
      { n = 0; r = 0 }
      match($0, /<<-?\047?[A-Za-z_]+\047?$/) {
        tag = substr($0, RSTART); sub(/^<<-?/, "", tag); gsub(/\047/, "", tag)
      }' "$file"
  done <<EOF
$(find "$root" -type f 2>/dev/null | LC_ALL=C sort)
EOF
}

## @description  Reports allowlist entries missing from a directory tree (root) or holding no token.
## @return       Writes one line per stale entry to stdout. Exit 0.
t_allowlist_stale() {
  local root=$1 row path class
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    path=${row%%"$PX_TAB"*}
    class=${row#*"$PX_TAB"}
    class=${class%%"$PX_TAB"*}
    if [ ! -f "$root/$path" ]; then
      printf '%s: no such file, remove the allowlist entry\n' "$path"
      continue
    fi
    if [ "$class" = k8s ] && ! grep -qE "$T_KUBECTL_RE" "$root/$path" 2>/dev/null; then
      printf '%s: no kubectl token left, remove the allowlist entry\n' "$path"
    elif [ "$class" = k3d ] && ! grep -qE "$T_K3D_RE" "$root/$path" 2>/dev/null; then
      printf '%s: no k3d token left, remove the allowlist entry\n' "$path"
    elif ! grep -qE "$T_SCAN_RE" "$root/$path" 2>/dev/null; then
      printf '%s: no runtime token left, remove the allowlist entry\n' "$path"
    fi
  done <<EOF
$(t_allowlist)
EOF
}

## @description  Reports runtime calls outside the backend via builtins, absolute paths, or PATH.
## @return       Writes one path:line:text record per violation to stdout. Exit 0.
t_scan_bypass() {
  local root=${1:-$PX_HOME} file rel hit class
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    t_in_scope "$file" || continue
    rel=${file#"$root"/}
    # Each backend legitimately invokes its own tool by path, whereas any bypass targeting a tool
    # outside its token class remains a violation.
    case "$rel" in
    lib/backend/compose.sh) class=compose ;;
    lib/backend/k8s.sh) class=k8s ;;
    bin/px-k3d) class=k3d ;;
    *) class="" ;;
    esac
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      [ -z "$class" ] || t_foreign "$class" "${hit#*:}" || continue
      printf '%s:%s\n' "$rel" "$hit"
    done <<EOF
$(grep -nE "$T_BYPASS_RE" "$file" 2>/dev/null || true)
EOF
  done <<EOF
$(find "$root" -type f 2>/dev/null | LC_ALL=C sort)
EOF
}

# ---------------------------------------------------------------------------
# Test assertion groups
# ---------------------------------------------------------------------------

## @description  Reports which of the five committed architectural policies a document (path) omits.
## @return       Writes one missing phrase per line to stdout. Exit 0.
t_missing_policies() {
  local phrase
  while IFS= read -r phrase; do
    [ -n "$phrase" ] || continue
    grep -qiF "$phrase" "$1" 2>/dev/null || printf '%s\n' "$phrase"
  done <<EOF
$T_POLICIES
EOF
}

## @description  Verifies the documentation of this round and the two directory-wide hygiene rules.
## @return       Records assertions. Exit 0.
t_group_documents() {
  local fixture
  printf '# documents and directory rules\n'
  t_run grep -q "^\\* \`harness/\` " "$T_DEV/README.md"
  t_equals dev_readme_indexes_harness 0 "$T_STATUS"
  t_empty readme_states_policies "$(t_missing_policies "$PX_HOME/README.md")"
  # Verify that stripping a required policy phrase causes the check to fail, confirming the
  # detector is sensitive to policy removal.
  fixture="$T_WORK/no-policy.md"
  grep -viF 'one cluster per host' "$PX_HOME/README.md" >"$fixture"
  t_equals readme_policy_removal_is_detected 'one cluster per host' \
    "$(t_missing_policies "$fixture")"
  t_empty shelldoc_follows_c2 "$(t_shelldoc_gaps "$PX_HOME")"
  # Verify that the shelldoc scanner detects every category of violation in a synthetic fixture
  # while permitting multi-parameter sb_* contract functions and function definitions embedded
  # inside heredocs.
  mkdir -p "$T_WORK/shelldoc/lib"
  fixture="$T_WORK/shelldoc/lib/gaps.sh"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'bare() { :; }\n'
    printf '## @description  Has no return line.\nno_return() { :; }\n'
    printf '## @description  A.\n##  B.\n## @return  Exit 0.\n##  C.\nlong() { :; }\n'
    printf '## @description  A.\n## @audience  private\n## @return  Exit 0.\nold() { :; }\n'
    printf '## @description  A.\n## @param  x\n## @param  y\n## @return  Exit 0.\nsb_ok() { :; }\n'
    printf "cat <<'FIXTURE_EOF'\nquoted() { :; }\nFIXTURE_EOF\n"
  } >"$fixture"
  t_equals shelldoc_gaps_are_detected "$(printf '%s\n' 'lib/gaps.sh:bare:no shelldoc' \
    'lib/gaps.sh:no_return:no @return' 'lib/gaps.sh:long:more than three lines' \
    'lib/gaps.sh:11:retired tag')" "$(t_shelldoc_gaps "$T_WORK/shelldoc")"
  t_empty no_java_under_dev "$(find "$T_DEV" -name '*.java' 2>/dev/null)"
  t_empty no_user_paths "$(grep -rlE "$T_USER_PATH_RE" "$PX_HOME" 2>/dev/null)"
}

## @description  Validates the Layer 1 static source scanner and its allowlist enforcement rules.
## @return       Records assertions. Exit 0.
t_group_scan() {
  local hits stale
  printf '# layer 1, the static scan\n'
  t_empty no_direct_docker "$(t_scan_root "$PX_HOME")"
  t_empty no_direct_kubectl "$(t_scan_root "$PX_HOME" "$T_K8S_RE")"
  hits="$(t_scan_root "$T_WORK/scan")"
  t_contains scan_reports_a_token_in_a_command 'bin/px-run:3:' "$hits"
  t_contains no_direct_docker_reports_a_driver 'drivers/psql/driver.sh:3:' "$hits"
  t_contains no_direct_docker_reports_the_engine_variable \
    'drivers/evil/driver.sh:3:' "$hits"
  t_absent scan_allows_the_backend 'compose.sh' "$hits"
  t_absent scan_drops_a_comment_line 'commented.sh' "$hits"
  t_absent scan_drops_a_waived_line 'waived.sh' "$hits"
  hits="$(t_scan_root "$T_WORK/scan" "$T_K8S_RE")"
  t_contains no_direct_kubectl_reports_a_suite_library 'suites/lib/common.sh:3:' "$hits"
  t_contains no_direct_kubectl_reports_helm_in_a_plugin 'plugins/evil/plugin.sh:3:' "$hits"
  t_contains no_direct_kubectl_reports_k3d_in_a_plugin 'plugins/evil/plugin.sh:4:' "$hits"
  t_contains no_direct_kubectl_reports_crictl_in_a_plugin 'plugins/evil/plugin.sh:5:' "$hits"
  t_absent no_direct_kubectl_ignores_a_runtime_name 'bin/px-run' "$hits"
  t_empty allowlist_entries_are_current "$(t_allowlist_stale "$PX_HOME")"
  stale="$(t_allowlist_stale "$T_WORK/stale")"
  t_contains allowlist_stale_token_is_reported \
    'lib/backend/compose.sh: no runtime token left' "$stale"
  t_contains allowlist_stale_path_is_reported \
    'test/run-tests.sh: no such file' "$stale"
}

## @description  Validates the Layer 2 runtime trap in lib/common.sh across every evasion context.
## @return       Records assertions. Exit 0.
t_group_trap() {
  local name libs lib probe offenders="" log
  local names=()
  printf '# layer 2, the runtime trap\n'
  read -r -a names <<<"$T_TRAP_NAMES"
  t_run "$T_BASH" "$T_WORK/probe/trap-names.sh" "${names[@]}"
  t_equals trap_blocks_every_tool_name "$(printf '%s 2\n' "${names[@]}")" "$T_STDOUT"
  t_run "$T_BASH" "$T_WORK/probe/trap-direct.sh"
  t_equals trap_blocks_a_direct_call_with_exit_two 2 "$T_STATUS"
  t_empty trap_stops_the_program_at_the_call "$T_STDOUT"
  t_contains trap_names_the_attempted_command \
    'container runtime call blocked: docker ps' "$T_STDERR"
  t_contains trap_names_the_way_through \
    'use the substrate contract in lib/backend/contract.sh' "$T_STDERR"
  t_run "$T_BASH" "$T_WORK/probe/trap-subshell.sh"
  t_contains trap_survives_a_subshell 'subshell status 2' "$T_STDOUT"
  t_run "$T_BASH" "$T_WORK/probe/trap-set-e.sh"
  t_equals trap_survives_a_set_e_context 2 "$T_STATUS"
  # Each CLI command inherits the runtime traps through the specific libraries it sources, so each
  # generated probe sources the exact library list extracted from that command.
  for name in $T_COMMANDS; do
    # Match the literal dollar sign in the command's source directive via a bracket expression so
    # this test script does not embed variable references belonging to target scripts.
    libs="$(sed -n 's|^\.[[:space:]]*"[$]SELF/\(.*\)"$|\1|p' "$T_BIN/$name")"
    probe="$T_WORK/probe/bin-$name.sh"
    {
      printf '#!/usr/bin/env bash\n'
      while IFS= read -r lib; do
        [ -n "$lib" ] || continue
        printf '. "%s/%s"\n' "$T_BIN" "$lib"
      done <<EOF
$libs
EOF
      printf 'declare -F docker >/dev/null 2>&1 || exit 9\n'
      printf 'docker ps\n'
      printf 'exit 0\n'
    } >"$probe"
    t_run "$T_BASH" "$probe"
    [ "$T_STATUS" = 2 ] || offenders="$offenders $name=$T_STATUS"
  done
  t_empty trap_resolves_in_every_command_process "$offenders"
  # A suite test driver executes in its own child process and must still inherit the runtime trap.
  t_suite selftest-trapped --continue-on-failure "$T_WORK/suites/trapped.suite"
  t_equals trap_reaches_a_suite_driver 3 "$T_STATUS"
  log="$PX_OUT/sessions/selftest-trapped/tests"
  t_contains trap_reaches_a_suite_driver_message 'container runtime call blocked' \
    "$(cat "$(sed -n 1p "$log/blocked/stdout.txt" 2>/dev/null)/logs/run.log" 2>/dev/null)"
  t_contains trap_blocks_kubectl_in_a_suite_driver 'call blocked: kubectl get pods' \
    "$(cat "$(sed -n 1p "$log/blocked-kubectl/stdout.txt" 2>/dev/null)/logs/run.log" \
      2>/dev/null)"
  t_empty no_bypass_form_outside_the_backend "$(t_scan_bypass)"
}

## @description  Validates Layer 3: no entry point or hostile driver invokes a fake tool on PATH.
## @return       Records assertions. Exit 0.
t_group_shim() {
  local calls
  local names=()
  printf '# layer 3, the fake runtime\n'
  t_shim_state_reset
  # Verify that the fake Docker shim is first on PATH and actively logs invocations; otherwise an
  # empty log during negative controls could merely reflect an ineffective PATH override.
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/shim-names.sh" docker
  t_equals shim_is_first_on_path 29.9.3 "$T_STDOUT"
  t_nonempty shim_records_a_call "$(cat "$T_SHIM_LOG")"
  # Run the same positive control across every fake Kubernetes tool shim so an empty call log below
  # confirms that no entry point invoked any of them.
  IFS='|' read -r -a names <<<"$T_K8S_NAMES"
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/shim-names.sh" "${names[@]}"
  t_equals shim_records_each_kubernetes_tool "$(printf '%s version\n' "${names[@]}")" \
    "$(cat "$T_SHIM_LOG")"
  t_negative_control cluster_config "$T_BIN/px-cluster" config
  t_negative_control suite_dry_run "$T_BIN/px-suite" --dry-run \
    "$T_WORK/suites/order.suite"
  t_negative_control run_dry_run "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run
  t_negative_control collect_list "$T_BIN/px-collect" list
  t_negative_control collect_help "$T_BIN/px-collect" start --help
  t_negative_control backend_bind "$T_WORK/probe/sb.sh" sb_bound_backend
  t_negative_control cluster_down_dry_run "$T_BIN/px-cluster" --dry-run down \
    -e PX_SCOPE_ID=px-selftest
  t_negative_control cluster_build_dry_run "$T_BIN/px-cluster" --dry-run build
  # Execute a positive control: a genuine backend call must record the scope's label selector in
  # the call log, proving that the empty logs from the negative controls above are real evidence
  # rather than an artifact of a broken fixture.
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/sb.sh" sb_list px-selftest
  t_contains positive_control_carries_the_scope_selector \
    'label=px.harness.cluster=selftest' "$(cat "$T_SHIM_LOG")"
  t_contains positive_control_reports_the_instance \
    "regionserver${PX_TAB}0${PX_TAB}c0ffee000001" "$T_STDOUT"
  # Run the hostile driver under px-run after pre-run snapshot resolution has initialized the
  # backend, verifying that the internal engine variable is not exported to child driver processes.
  t_shim_log_reset
  t_px_run engine-var
  calls="$(cat "$T_SHIM_LOG")"
  t_equals hostile_driver_run_succeeds 0 "$T_STATUS"
  t_contains hostile_driver_run_reaches_the_fake_runtime \
    'ps -a --filter label=px.harness.cluster=selftest' "$calls"
  t_contains hostile_driver_sees_no_engine_variable 'engine variable []' \
    "$(cat "$T_STDOUT/logs/run.log" 2>/dev/null)"
  t_absent hostile_driver_reaches_no_runtime 'hostile-driver' "$calls"
}

## @description  Verifies that an entry point (name, command...) succeeds and invokes no runtime.
## @return       Records two assertions. Exit 0.
t_negative_control() {
  local name=$1
  shift
  t_shim_log_reset
  t_with_shim "$T_BASH" "$@"
  t_equals "negative_control_${name}_succeeds" 0 "$T_STATUS"
  t_empty "negative_control_${name}_calls_no_runtime" "$(cat "$T_SHIM_LOG")"
}

## @description  Reports any substrate contract function that a backend file (path) omits or adds.
## @return       Writes one line per difference to stdout. Exit 0.
t_backend_verbs() {
  LC_ALL=C comm -3 <(sb_contract_functions | LC_ALL=C sort) \
    <(sed -n 's/^\(sb_[a-z0-9_]*\)().*/\1/p' "$1" | LC_ALL=C sort) |
    awk -F '\t' '{ print ($1 != "" ? "missing " $1 : "extra " $2) }'
}

## @description  Verifies that the substrate contract and Compose backend agree, schemas included.
## @return       Records assertions. Exit 0.
t_group_contract() {
  local fixture
  printf '# the substrate contract and its backend\n'
  t_empty contract_verbs_complete "$(t_backend_verbs "$PX_HOME/lib/backend/compose.sh")"
  fixture="$T_WORK/fixture-backend.sh"
  grep -v '^sb_oom_killed()' "$PX_HOME/lib/backend/compose.sh" >"$fixture"
  printf 'sb_not_a_contract_verb() { :; }\n' >>"$fixture"
  t_contains contract_missing_verb_is_reported 'missing sb_oom_killed' \
    "$(t_backend_verbs "$fixture")"
  t_contains contract_extra_verb_is_reported 'extra sb_not_a_contract_verb' \
    "$(t_backend_verbs "$fixture")"
  t_equals contract_declares_32_required_verbs 32 \
    "$(sb_contract_functions | grep -c .)"
  sb_valid_value ready stopped
  t_equals ready_vocabulary_holds_the_stopped_row 0 "$?"
  t_equals schema_index_finds_the_first_field 1 "$(sb_schema_index instance role)"
  t_equals schema_index_finds_the_last_field 7 "$(sb_schema_index instance node)"
  t_equals schema_index_follows_the_field_order 5 \
    "$(sb_schema_index sample mem_bytes)"
  t_equals schema_count_covers_every_column 10 "$(sb_schema_count sample)"
  sb_schema_index sample not_a_field >/dev/null 2>&1
  t_equals schema_index_refuses_an_unknown_field 6 "$?"
}

## @description  Verifies help output, usage error handling, and lib/common.sh sourcing across all
##               nine CLI entry points.
## @return       Records assertions. Exit 0.
t_group_commands() {
  local name missing="" help="" flag="" unsourced="" own="" stub=""
  printf '# the nine entry points\n'
  for name in $T_COMMANDS; do
    [ -x "$T_BIN/$name" ] || missing="$missing $name"
    t_run "$T_BASH" "$T_BIN/$name" --help
    if [ "$T_STATUS" != 0 ] || [ -z "$T_STDOUT" ]; then
      help="$help $name=$T_STATUS"
    fi
    t_run "$T_BASH" "$T_BIN/$name" --not-a-flag
    [ "$T_STATUS" = 2 ] || flag="$flag $name=$T_STATUS"
    grep -qE '^\.[[:space:]]*"[$]SELF/\.\./lib/(common\.sh|backend/contract\.sh)"$' \
      "$T_BIN/$name" || unsourced="$unsourced $name"
    if grep -qE '^(log|logger|info|warn|error|debug|die|fatal)[[:space:]]*\(\)' \
      "$T_BIN/$name"; then
      own="$own $name"
    fi
  done
  # Verify that the collector front door, the bundle tool, and the local cluster manager each
  # reject an unknown subcommand.
  t_run "$T_BASH" "$T_BIN/px-k3d" run
  [ "$T_STATUS" = 2 ] || stub="$stub px-k3d=$T_STATUS"
  # The px-pherf run subcommand requires a suite argument.
  t_run "$T_BASH" "$T_BIN/px-pherf" run
  [ "$T_STATUS" = 2 ] || stub="$stub px-pherf=$T_STATUS"
  for name in px-collect px-bundle; do
    t_run "$T_BASH" "$T_BIN/$name" run
    [ "$T_STATUS" = 2 ] || stub="$stub $name=$T_STATUS"
  done
  t_empty nine_entry_points_are_present "$missing"
  t_empty every_tool_refuses_an_unknown_subcommand "$stub"
  t_empty every_entry_point_answers_help "$help"
  t_empty every_entry_point_refuses_a_bad_flag "$flag"
  t_empty bins_source_common "$unsourced"
  t_run grep -qE '^\.[[:space:]]*"[$]PX_BACKEND_DIR/\.\./common\.sh"$' \
    "$PX_HOME/lib/backend/contract.sh"
  t_equals the_contract_sources_the_shared_library 0 "$T_STATUS"
  t_empty no_entry_point_defines_its_own_log_function "$own"
}

# ---------------------------------------------------------------------------
# Shared command-line grammar across all entry points, scripts, and generators
# ---------------------------------------------------------------------------

# Diagnostic substrings emitted by the command-line parser in lib/common.sh; a valid command line
# that parses cleanly emits none of these patterns on stderr.
T_CLI_PARSE_RE='unknown option|unknown subcommand|name a subcommand|needs a value'
T_CLI_PARSE_RE="$T_CLI_PARSE_RE|unexpected argument|does not apply to"

## @description  Lists every command participating in the shared grammar (path under PX_HOME)
##               paired with a lightweight argument list that parses without mutating state.
## @return       Writes one "path arguments" line per command to stdout. Exit 0.
t_cli_targets() {
  local b="$T_WORK/cli-out/runs/cli-bundle" none="$T_WORK/cli-none"
  cat <<EOF
bin/px-cluster config
bin/px-suite $T_WORK/suites/order.suite -n
bin/px-run $T_WORK/drivers/exit-code.sh -n
bin/px-tool quiet-host
bin/px-collect list
bin/px-bundle validate $b
bin/px-report --verdict $T_WORK/cli-pass.tsv
bin/px-pherf status -n
bin/px-k3d check -n
scripts/build-phoenix.sh -n --tree $none
scripts/build-jhiccup.sh -n
scripts/fetch-dist.sh -n jhiccup
scripts/stage-artifacts.sh -n
scripts/preflight.sh -n --deployment $none
scripts/preflight-k8s.sh -n --deployment $none
docker/compose.gen.sh --class validation --check
k8s/manifests.gen.sh --class validation --check
suites/check-cluster.sh -n -e PX_RUN_DIR=$b -e PX_THRESHOLDS_FILE=$b/t.properties
suites/checks/cluster.sh -n -e PX_RUN_DIR=$b -e PX_THRESHOLDS_FILE=$b/t.properties
suites/checks/steady.sh -n -e PX_RUN_DIR=$b -e PX_THRESHOLDS_FILE=$b/t.properties
EOF
}

## @description  Executes a command (path under PX_HOME, argument...) under the fake runtime using
##               a scratch output root and a 60-second timeout bound.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_cli_run() {
  local path=$1
  shift
  T_STATUS=0
  (px_timeout 60 env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" PX_COLLECTORS=none \
    "PX_OUT=$T_WORK/cli-out" PX_QUIET_HOST_WAIT_S=0 "$T_BASH" "$PX_HOME/$path" "$@") \
    </dev/null >"$T_OUT" 2>"$T_ERR" || T_STATUS=$?
  T_STDOUT="$(cat "$T_OUT")"
  T_STDERR="$(cat "$T_ERR")"
}

## @description  Extracts and prints the final line of a text string (text).
## @return       Writes the final line to stdout. Exit 0.
t_last_line() { printf '%s\n' "$1" | tail -n 1; }

## @description  Extracts the subcommand names listed in the top-level help output of a command
##               (path).
## @return       Writes one subcommand name per line to stdout. Exit 0.
t_cli_subs() {
  t_cli_run "$1" --help
  printf '%s\n' "$T_STDOUT" | awk '/^Subcommands:/ { on = 1; next } on && /^$/ { exit }
    on && /^  [a-z]/ { print $1 }'
}

## @description  Substitutes parseable test values for the placeholders of a synopsis or option
##               (word...) and omits optional bracketed tokens.
## @return       Writes the resolved words, one per line, to stdout. Exit 0.
t_cli_fill() {
  local word open close depth=0
  for word in "$@"; do
    open=${word//[^[]/}
    close=${word//[^]]/}
    depth=$((depth + ${#open}))
    if [ "$depth" -gt 0 ]; then
      depth=$((depth - ${#close}))
      continue
    fi
    case "$word" in
    ROLE) printf 'regionserver\n' ;;
    INDEX | COUNT | SECONDS | N | T) printf '1\n' ;;
    SIGNAL) printf 'QUIT\n' ;;
    PORT) printf 'rpc\n' ;;
    GROUP) printf 'yarn\n' ;;
    DRIVER) printf '%s\n' "$T_WORK/drivers/exit-code.sh" ;;
    SUITE) printf '%s\n' "$T_WORK/suites/order.suite" ;;
    FILE | FILE...) printf '%s\n' "$T_WORK/cli-set.env" ;;
    DIR) printf '%s\n' "$T_WORK/cli-out/runs/cli-bundle" ;;
    *'|'*) printf '%s\n' "${word##*|}" ;;
    ROLE=COUNT) printf 'regionserver=2\n' ;;
    NAME=VALUE) printf 'PX_CLI_PROBE=1\n' ;;
    SPEC) printf '+1,restart,regionserver,0\n' ;;
    ID | IDS) printf 'first\n' ;;
    LIST) printf 'all\n' ;;
    MODE) printf 'hard\n' ;;
    PHASE) printf 'fast\n' ;;
    --) printf -- '--\n' ;;
    COMMAND) printf 'true\n' ;;
    *) printf '%s\n' "$word" ;;
    esac
  done
}

## @description  Reports any command line (path, sub, words...) that fails to parse during a dry
##               run.
## @return       Writes "command: parse text" for any rejected invocation to stdout. Exit 0.
t_cli_parses() {
  local path=$1 line
  local words=()
  shift
  while IFS= read -r line; do
    words+=("$line")
  done <<EOF
$(t_cli_fill "$@")
EOF
  t_cli_run "$path" ${words[@]+"${words[@]}"} -n
  line="$(printf '%s\n' "$T_STDERR" | grep -E "$T_CLI_PARSE_RE" | sed -n 1p)"
  [ -z "$line" ] || printf '%s %s: %s\n' "${path##*/}" "$*" "$line"
}

## @description  Extracts every px-NAME command referenced in px_die, px_info, px_warn, px_error,
##               or PX_HINT_* messages across the shipped codebase after joining continuations.
## @return       Writes "file:command" rows to stdout. Exit 0.
t_cli_hints() {
  local file re='(run|Run|use|with|then|or) px-(cluster|run|suite|report|bundle|collect|tool|pherf'
  re="$re|k3d)( [^ ,.:;()]+)*"
  for file in "$PX_HOME"/bin/* "$PX_HOME"/lib/*.sh "$PX_HOME"/lib/backend/*.sh \
    "$PX_HOME"/scripts/*.sh; do
    awk -v rel="${file#"$PX_HOME"/}" -v re="$re" '
      /^[[:space:]]*#/ { next }
      { line = (cont ? line " " : "") $0; cont = /\\$/ }
      cont { sub(/\\$/, "", line); next }
      line ~ /px_(die|info|warn|error)[[:space:]]|^PX_HINT_[A-Z_]*=/ {
        text = line; out = ""
        while (match(text, /"[^"]*"/)) {
          out = out " " substr(text, RSTART + 1, RLENGTH - 2); text = substr(text, RSTART + RLENGTH)
        }
        while (match(out, re)) {
          hint = substr(out, RSTART, RLENGTH); sub(/^[A-Za-z]* /, "", hint)
          print rel ":" hint; out = substr(out, RSTART + RLENGTH)
        }
      }
      { line = "" }' "$file"
  done
}

## @description  Truncates an extracted hint command (words...) at the first surrounding prose word
##               and substitutes a concrete value for each variable reference.
## @return       Writes the command words, one per line, to stdout. Exit 0.
t_cli_hint_words() {
  local word
  for word in "$@"; do
    case "$word" in
    first | or | then | and | to | when | with | before | after | of | the | a | so | for | \
      on | in | by | from | at | is | reported | did | runs | holds | gives | recorded | \
      starts | stops | ends | names | fails | failed | brings | takes | needs | sets) break ;;
    *'$'*) printf 'x\n' ;;
    *) printf '%s\n' "$word" ;;
    esac
  done
}

## @description  Validates the shared CLI grammar across all commands, covering specs, help layout,
##               fixed parse texts, first-wins precedence, exit lines, plan rows, and hints.
## @return       Records assertions. Exit 0.
t_group_cli() {
  local row path args sub name text bad="" bad2="" bad3="" heads line word hints count spec got
  local cb="$T_WORK/cli-out/runs/cli-bundle"
  local words=() subs=() opts=()
  printf '# the shared command-line grammar\n'
  t_shim_state_reset
  mkdir -p "$T_WORK/cli-none" "$T_WORK/cli-out/runs/cli-bundle"
  printf '# a version set for the matrix plan\n' >"$T_WORK/cli-set.env"
  printf 'rows\tPASS\tcount\tdriver:x\tfine\n' >"$T_WORK/cli-pass.tsv"
  printf 'rows\tFAIL\tcount\tdriver:x\tshort\n' >"$T_WORK/cli-fail.tsv"
  # Every command defines its command-line grammar in a single declarative specification consumed by
  # both the argument parser and the help generator.
  while read -r path args; do
    spec="$(awk '/px_cli_spec <<.EOF.$/ { on = 1; next } on && /^EOF$/ { on = 0 } on' \
      "$PX_HOME/$path")"
    case "$path" in
    scripts/preflight*.sh) spec="$spec$PX_NL$(awk '/<<.EOF.$/ { on = 1; next }
      on && /^EOF$/ { on = 0 } on' "$PX_HOME/lib/preflight.sh")" ;;
    *.gen.sh) spec="$spec$PX_NL$(awk '/<<.EOF.$/ { on = 1; next }
      on && /^EOF$/ { on = 0 } on' "$PX_HOME/lib/deploy.sh")" ;;
    esac
    [ -n "$spec" ] || bad="$bad ${path##*/}=nospec"
    text="$(printf '%s\n' "$spec" | awk '
      $1 == "opt" && ($2 !~ /^--[a-z]/ || index($0, "|") == 0) { print "opt:" $2 }
      $1 == "sub" && NF < 2 { print "sub" }
      $1 == "shared" { for (i = 2; i <= NF; i++)
        if ($i !~ /^--(cluster|class|backend)$/) print "shared:" $i }
      $1 == "opt" { if (($2 in kind) && kind[$2] != ($3 == "-")) print "kind:" $2
        kind[$2] = ($3 == "-") }')"
    [ -z "$text" ] || bad="$bad ${path##*/}:$(printf '%s' "$text" | tr '\n' ',')"
  done <<EOF
$(t_cli_targets)
EOF
  t_empty cli_specs_are_well_formed "$bad"
  # Verify that every command and subcommand renders --help according to the standard section
  # template, wraps all lines within 80 columns, and exposes no short flags other than -h, -v, -n,
  # and -e.
  bad="" bad2="" bad3="" heads=""
  local helps=""
  while read -r path args; do
    subs=("")
    while IFS= read -r sub; do
      [ -z "$sub" ] || subs+=("$sub")
    done <<EOF
$(t_cli_subs "$path")
EOF
    for sub in "${subs[@]}"; do
      if [ -n "$sub" ]; then
        t_cli_run "$path" "$sub" --help
      else
        t_cli_run "$path" --help
      fi
      name="${path##*/}${sub:+ $sub}"
      case "$T_STATUS $T_STDOUT" in
      "0 Usage: ${path##*/}${sub:+ $sub}"*) ;;
      *) bad="$bad [$name]=$T_STATUS" ;;
      esac
      text="$(printf '%s\n' "$T_STDOUT" | sed -nE \
        's/^(Usage|Subcommands|Options|Shared options|Output|Exit codes|Examples):.*/\1/p' |
        tr '\n' ',')"
      case "$text" in
      Usage,*Shared\ options,*Exit\ codes,*) ;;
      *) heads="$heads [$name]=$text" ;;
      esac
      [ "$(printf '%s\n' "$text" | tr ',' '\n' | grep . | awk '
        { o["Usage"] = 1; o["Subcommands"] = 2; o["Options"] = 3; o["Shared options"] = 4
          o["Output"] = 5; o["Exit codes"] = 6; o["Examples"] = 7
          if (o[$0] <= last) bad = 1; last = o[$0] } END { print bad + 0 }')" = 0 ] ||
        heads="$heads [$name]=order:$text"
      line="$(printf '%s\n' "$T_STDOUT" | awk 'length > 80 { print length ": " $0; exit }')"
      [ -z "$line" ] || bad2="$bad2 [$name] $line"
      line="$(printf '%s\n' "$T_STDOUT" |
        grep -oE '(^|[ ,|[])-[A-Za-z]([ ,|]|$)' | grep -vE -- '-[hvne]([ ,|]|$)' | sed -n 1p)"
      [ -z "$line" ] || bad3="$bad3 [$name]$line"
      helps="$helps$path$PX_TAB$sub$PX_NL"
      printf '%s\n' "$T_STDOUT" >"$T_WORK/cli-help-${path##*/}-${sub:-top}.txt"
    done
  done <<EOF
$(t_cli_targets)
EOF
  t_empty every_subcommand_answers_help "$bad"
  t_empty help_sections_in_template_order "$heads"
  t_empty help_lines_fit_80_columns "$bad2"
  t_empty help_names_no_other_short_form "$bad3"
  t_equals help_covers_every_subcommand_of_px_cluster 18 \
    "$(printf '%s' "$helps" | grep -c "^bin/px-cluster$PX_TAB")"
  # Verify that the shared options section of each help text lists a selector flag if and only if
  # the command accepts that selector, and that every documented option and example parses cleanly
  # in dry-run mode when its placeholders are populated.
  bad="" bad2="" bad3=""
  while IFS="$PX_TAB" read -r path sub; do
    [ -n "$path" ] || continue
    text="$(cat "$T_WORK/cli-help-${path##*/}-${sub:-top}.txt")"
    for word in --cluster --class --backend; do
      t_cli_run "$path" ${sub:+"$sub"} "$word" x -n
      case "$(t_flag "      $word " "$text") $(t_flag "unknown option $word" "$T_STDERR")$(
        t_flag "$word does not apply to $sub" "$T_STDERR")" in
      "yes nono" | "no yesno" | "no noyes") ;;
      *) bad="$bad [${path##*/} $sub]$word" ;;
      esac
    done
    words=()
    while IFS= read -r line; do
      words+=("$line")
    done <<EOF
$(printf '%s\n' "$text" | sed -n 1p | sed 's/^Usage: [^ ]*//' | tr ' ' '\n' | grep . |
      sed "1{/^SUBCOMMAND$/d;}" | grep -v '^\[OPERAND\.\.\.\]$')
EOF
    [ -z "$sub" ] || [ "${words[0]:-}" != "$sub" ] || words=("${words[@]:1}")
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      read -r -a opts <<<"$line"
      bad2="$bad2$(t_cli_parses "$path" ${sub:+"$sub"} ${words[@]+"${words[@]}"} "${opts[@]}")"
    done <<EOF
$([ -n "$sub" ] || ! t_flag Subcommands: "$text" | grep -q yes || exit 0
      printf '%s\n' "$text" | awk '/^Options:/ { on = 1; next } on && /^[^ ]/ { on = 0 }
        on && /^  --/ { w = $1; if ($2 ~ /^[A-Z][A-Z=|]*$/) w = w " " $2; print w }')
EOF
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      read -r -a words <<<"$line"
      case "${words[0]}" in
      "${path##*/}") ;;
      *) continue ;;
      esac
      bad3="$bad3$(t_cli_parses "$path" "${words[@]:1}")"
    done <<EOF
$(printf '%s\n' "$text" | awk '/^Examples:/ { on = 1; next } on && /^[^ ]/ { on = 0 }
      on && /^  [a-z]/ { sub(/^  /, ""); print }')
EOF
  done <<EOF
$helps
EOF
  t_empty help_shared_block_matches_the_parser "$bad"
  t_empty help_options_are_accepted "$bad2"
  t_empty help_examples_parse "$bad3"
  # Verify that verdict prose reads sequentially ahead of its summary table, and that a
  # subcommand's help renders its summary as a single paragraph wrapped at 80 columns.
  t_contains help_verdict_text_reads_in_order "a session in which$PX_NL  every test was skipped is\
 INCONCLUSIVE:$PX_NL  PASS, SKIP, ADVISORY 0;" "$(cat "$T_WORK/cli-help-px-suite-top.txt")"
  t_equals help_summary_of_a_subcommand_is_one_paragraph \
    "Measure background host activity and report contamination." \
    "$(sed -n 3p "$T_WORK/cli-help-px-tool-quiet-host.txt")"
  # Verify that a subcommand's help output lists a shared selector only when that subcommand
  # accepts it.
  got=""
  for line in px-tool:quiet-host px-tool:monitor px-collect:start px-collect:stop \
    px-collect:list; do
    text="$(cat "$T_WORK/cli-help-${line%%:*}-${line#*:}.txt")"
    got="$got|$(t_flag '      --cluster ' "$text")"
    [ "${line%%:*}" = px-tool ] || got="$got $(t_flag '      --class ' "$text") $(t_flag \
      '      --backend ' "$text")"
  done
  t_equals help_shared_selectors_follow_the_subcommand "|no|yes|yes yes yes|yes no no|no no no" \
    "$got"
  t_contains help_collect_needs_collectors 'required unless PX_COLLECTORS is set' \
    "$(cat "$T_WORK/cli-help-px-collect-start.txt")"
  t_contains help_report_says_it_rewrites_report 'rewrites report/ from the streams it merged' \
    "$(cat "$T_WORK/cli-help-px-report-top.txt")"
  # Verify that every remediation hint referencing a harness command conforms to that command's
  # grammar.
  hints="$(t_cli_hints | LC_ALL=C sort -u)"
  count="$(printf '%s\n' "$hints" | grep -c .)"
  t_equals hint_extractor_finds_hints yes "$([ "$count" -ge 12 ] && echo yes || echo "no $count")"
  bad=""
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    read -r -a words <<<"${row#*:}"
    name=${words[0]}
    words=("${words[@]:1}")
    subs=()
    while IFS= read -r line; do
      [ -z "$line" ] || subs+=("$line")
    done <<EOF
$(t_cli_hint_words ${words[@]+"${words[@]}"})
EOF
    bad="$bad$(t_cli_parses "bin/$name" ${subs[@]+"${subs[@]}"})"
  done <<EOF
$hints
EOF
  t_empty hint_commands_parse "$bad"
  # Verify that options are accepted in any argument position, including after the subcommand and
  # after positional operands.
  bad=""
  while read -r path args; do
    read -r -a words <<<"$args"
    t_cli_run "$path" "${words[@]}" -e PX_CLI_PROBE=1 -v
    line="$(printf '%s\n' "$T_STDERR" | grep -E "$T_CLI_PARSE_RE" | sed -n 1p)"
    [ -z "$line" ] || bad="$bad [${path##*/}] $line"
  done <<EOF
$(t_cli_targets)
EOF
  t_cli_run bin/px-cluster config -e PX_RS_COUNT=4
  t_contains cli_options_after_the_subcommand_apply "PX_RS_COUNT${PX_TAB}4${PX_TAB}cli" \
    "$T_STDOUT"
  t_empty cli_options_in_any_position "$bad"
  # Verify that when a setting is specified multiple times on the command line, the first
  # occurrence in argument order takes precedence, matching the first-wins precedence rule across
  # configuration stages.
  t_cli_run bin/px-run "$T_WORK/drivers/exit-code.sh" -n -e PX_RUN_CLASS=measurement \
    --class validation
  t_contains cli_px_run_first_wins_setting_first "run_class${PX_TAB}measurement" "$T_STDOUT"
  t_cli_run bin/px-run "$T_WORK/drivers/exit-code.sh" -n --class validation \
    -e PX_RUN_CLASS=measurement
  t_contains cli_px_run_first_wins_flag_first "run_class${PX_TAB}validation" "$T_STDOUT"
  # Verify that command-line parse errors emit the canonical diagnostic messages and conclude
  # stderr with a single summary line naming the exit code.
  bad="" bad2="" bad3=""
  while read -r path args; do
    t_cli_run "$path" --not-a-flag
    [ "$T_STATUS $(t_flag 'unknown option --not-a-flag' "$T_STDERR")" = "2 yes" ] ||
      bad="$bad ${path##*/}=$T_STATUS"
    case "$(t_last_line "$T_STDERR")" in
    *"(exit 2, usage)") ;;
    *) bad2="$bad2 ${path##*/}" ;;
    esac
    [ "$(printf '%s\n' "$T_STDERR" | grep -c '(exit ')" = 1 ] || bad3="$bad3 ${path##*/}"
  done <<EOF
$(t_cli_targets)
EOF
  t_empty cli_unknown_option_text "$bad"
  t_empty cli_exit_line_on_usage "$bad2"
  t_empty cli_exit_line_once "$bad3"
  bad=""
  while read -r path args; do
    t_cli_run "$path" -e
    [ "$T_STATUS $(t_flag '-e needs a value' "$T_STDERR")" = "2 yes" ] ||
      bad="$bad ${path##*/}=$T_STATUS"
  done <<EOF
$(t_cli_targets)
EOF
  t_empty cli_missing_value_text "$bad"
  bad="" bad2=""
  for path in bin/px-cluster bin/px-pherf bin/px-k3d bin/px-tool bin/px-bundle bin/px-collect; do
    t_cli_run "$path" nosuchsub
    case "$T_STATUS $T_STDERR" in
    "2 "*"unknown subcommand nosuchsub: use "*) ;;
    *) bad="$bad ${path##*/}=$T_STATUS" ;;
    esac
    t_cli_run "$path"
    case "$T_STATUS $T_STDERR" in
    "2 "*"name a subcommand: "*) ;;
    *) bad2="$bad2 ${path##*/}=$T_STATUS" ;;
    esac
  done
  t_empty cli_unknown_subcommand_text "$bad"
  t_empty cli_missing_subcommand_text "$bad2"
  t_cli_run bin/px-cluster group restart yarn
  t_contains cli_operand_set_text 'unknown subcommand restart: use start, stop or status' \
    "$T_STDERR"
  bad=""
  for row in "bin/px-cluster down extra" "bin/px-report a extra" "bin/px-pherf status extra" \
    "scripts/build-jhiccup.sh extra" "docker/compose.gen.sh extra"; do
    read -r -a words <<<"$row"
    t_cli_run "${words[@]}"
    [ "$T_STATUS $(t_flag 'unexpected argument extra' "$T_STDERR")" = "2 yes" ] ||
      bad="$bad [$row]=$T_STATUS"
  done
  t_empty cli_unexpected_argument_text "$bad"
  bad=""
  for row in "bin/px-run|px-run needs DRIVER" "bin/px-suite|px-suite needs SUITE" \
    "bin/px-cluster logs regionserver|logs needs ROLE INDEX" \
    "bin/px-cluster exec regionserver 0|exec needs -- COMMAND" \
    "bin/px-pherf scale|scale needs COUNT"; do
    read -r -a words <<<"${row%%|*}"
    t_cli_run "${words[@]}"
    [ "$T_STATUS $(t_flag "${row#*|}" "$T_STDERR")" = "2 yes" ] || bad="$bad [$row]=$T_STATUS"
  done
  t_empty cli_missing_operand_text "$bad"
  t_cli_run bin/px-cluster down --timeout 5
  text=$T_STDERR
  t_cli_run bin/px-k3d delete --agents 3
  t_equals cli_option_scope_text "yes yes" "$(t_flag '--timeout does not apply to down' \
    "$text") $(t_flag '--agents does not apply to delete' "$T_STDERR")"
  # Verify that any failing command concludes stderr with a single exit-status summary line naming
  # the exit code, whereas a successful command emits no exit trailer.
  t_cli_run bin/px-cluster status
  t_equals cli_exit_line_on_no_object "6 yes" "$T_STATUS $(t_flag \
    '(exit 6, no such object)' "$(t_last_line "$T_STDERR")")"
  t_cli_run bin/px-report --verdict "$T_WORK/cli-fail.tsv"
  t_equals cli_exit_line_on_verdict "1 yes" "$T_STATUS $(t_flag '(exit 1, failure)' \
    "$(t_last_line "$T_STDERR")")"
  t_cli_run bin/px-cluster config
  t_equals cli_no_exit_line_on_success "0 0" "$T_STATUS $(printf '%s\n' "$T_STDERR" |
    grep -c '(exit ')"
  # Verify O4: every check or verdict stream concludes with exactly one result row, and no other
  # row in the stream is a result row.
  bad=""
  for row in "bin/px-report --verdict $T_WORK/cli-pass.tsv" "bin/px-bundle validate $cb" \
    "bin/px-tool quiet-host"; do
    read -r -a words <<<"$row"
    t_cli_run "${words[@]}"
    case "$(t_last_line "$T_STDOUT") $(printf '%s\n' "$T_STDOUT" | grep -c "^result$PX_TAB")" in
    "result$PX_TAB"*" 1") ;;
    *) bad="$bad [${row%% "$T_WORK"*}]" ;;
    esac
  done
  t_empty every_check_stream_ends_with_one_result_row "$bad"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" "${T_SELFTEST_RUN[@]}" \
    -e PX_SCOPE_ID=px-selftest -- 1
  t_equals cli_px_run_summary_is_the_exit_line "1 yes 1" "$T_STATUS $(t_flag \
    'ends at FAIL (exit 1, failure)' "$(t_last_line "$T_STDERR")") $(printf '%s\n' \
    "$T_STDERR" | grep -c '(exit ')"
  cat >"$T_WORK/cli-hook.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_cli_spec <<'EOF'
about A probe of the exit line.
EOF
px_cli_parse "$@"
px_on_exit px_info "the exit hook logs"
px_die 6 "no such probe object"
PROBE_EOF
  t_run "$T_BASH" "$T_WORK/cli-hook.sh"
  t_equals cli_exit_line_after_exit_hooks "6 yes 1" "$T_STATUS $(t_flag \
    'cli-hook.sh ends (exit 6, no such object)' "$(t_last_line "$T_STDERR")") $(printf '%s\n' \
    "$T_STDERR" | grep -c '(exit ')"
  # Verify that a child command invoked by a harness command emits its error diagnostics without
  # printing its own exit line, leaving a single exit line to close the failing command's stream.
  cat >"$T_WORK/cli-parent.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
px_cli_spec <<'EOF'
about A probe of one exit line for a parent and its child.
EOF
px_cli_parse "$@"
status=0
"$BASH" "${BASH_SOURCE[0]%/*}/cli-hook.sh" || status=$?
px_die "$status" "the child probe failed"
PROBE_EOF
  t_run "$T_BASH" "$T_WORK/cli-parent.sh"
  t_equals cli_child_prints_no_exit_line "6 yes yes 1" "$T_STATUS $(t_flag \
    'no such probe object' "$T_STDERR") $(t_flag 'cli-parent.sh ends (exit 6, no such object)' \
    "$(t_last_line "$T_STDERR")") $(printf '%s\n' "$T_STDERR" | grep -c '(exit ')"
  # Verify that the exit-status vocabulary designates exit code 3 as anomaly and that a single
  # shared mapping table translates each run verdict to its corresponding exit code.
  t_equals exit_code_3_is_named_anomaly "anomaly interrupted terminated" \
    "$(px_exit_name 3) $(px_exit_name 130) $(px_exit_name 143)"
  text=""
  for word in PASS SKIP ADVISORY FAIL INCONCLUSIVE ERROR WARN; do
    text="$text $(px_verdict_exit "$word" 2>/dev/null || printf 'r%s' "$?")"
  done
  t_equals verdict_map_table " 0 0 0 1 1 3 r6" "$text"
  bad=""
  for path in bin/px-run bin/px-suite bin/px-report; do
    t_cli_run "$path" --help
    t_flag 'PASS, SKIP, ADVISORY 0; FAIL, INCONCLUSIVE 1; ERROR 3' "$T_STDOUT" | grep -q yes ||
      bad="$bad ${path##*/}"
  done
  t_empty verdict_map_in_help "$bad"
  # Verify that legacy flag and option spellings retired by the unified grammar are rejected.
  bad=""
  for row in "bin/px-run|--driver x" "bin/px-run|-d x" "bin/px-run|-s x" "bin/px-run|--scope x" \
    "bin/px-run|-b compose" "bin/px-run|-c validation" "bin/px-run|-D x" "bin/px-run|-t 1" \
    "bin/px-run|-f x" "bin/px-run|-k" "bin/px-suite|-c validation" "bin/px-suite|-f x" \
    "bin/px-suite|-k" "bin/px-cluster|logs regionserver 0 -f" \
    "bin/px-cluster|up --ready-timeout 1" \
    "bin/px-cluster|restart regionserver 0 --drain-timeout 1" \
    "bin/px-k3d|check --context k3d-px" "scripts/build-phoenix.sh|--jdk 17" \
    "scripts/build-jhiccup.sh|--jdk 17" "bin/px-tool|quiet-host --scope x" \
    "bin/px-collect|--scope x" "bin/px-collect|--list" "bin/px-collect|start --collector x" \
    "bin/px-collect|start --interval 1" "bin/px-collect|start --out x" "bin/px-report|--run x" \
    "bin/px-report|--out x" "bin/px-bundle|validate --run x" "bin/px-cluster|status --group yarn" \
    "bin/px-cluster|render --check" "bin/px-run|--keep" "scripts/build-phoenix.sh|--distributed" \
    "scripts/stage-artifacts.sh|--tag x:1"; do
    read -r -a words <<<"${row#*|}"
    t_cli_run "${row%%|*}" "${words[@]}" -n
    [ "$T_STATUS $(t_flag 'unknown option -' "$T_STDERR")" = "2 yes" ] ||
      bad="$bad [$row]=$T_STATUS"
  done
  t_empty removed_spellings_are_refused "$bad"
  t_cli_run bin/px-run "$T_WORK/drivers/exit-code.sh" -n --strict -e PX_DRIVER=x
  t_equals px_driver_is_undeclared "2 yes" "$T_STATUS $(t_flag "undeclared name 'PX_DRIVER'" \
    "$T_STDERR")"
  t_cli_run bin/px-run --class validation
  t_equals px_run_needs_a_driver_operand "2 yes" "$T_STATUS $(t_flag 'px-run needs DRIVER' \
    "$T_STDERR")"
  t_cli_run bin/px-cluster config --strict -e PX_TEARDOWN=0
  t_equals px_teardown_is_undeclared "2 yes" "$T_STATUS $(t_flag "undeclared name 'PX_TEARDOWN'" \
    "$T_STDERR")"
  t_equals push_images_script_is_gone no "$(t_exists "$PX_HOME/scripts/push-images.sh")"
  t_cli_run bin/px-pherf run "$T_WORK/suites/order.suite" -n -- --only first
  t_equals pherf_run_takes_no_words_after_the_separator "2 yes" "$T_STATUS $(t_flag \
    'unexpected argument --only' "$T_STDERR")"
  # Verify that every command accepts the shared configuration flags, rejecting undeclared setting
  # names under --strict and accepting setting overrides via -e.
  bad=""
  while read -r path args; do
    read -r -a words <<<"$args"
    t_cli_run "$path" "${words[@]}" --strict -e PX_NOT_DECLARED_U2=1
    [ "$T_STATUS $(t_flag "undeclared name 'PX_NOT_DECLARED_U2'" "$T_STDERR")" = "2 yes" ] ||
      bad="$bad ${path##*/}=$T_STATUS"
  done <<EOF
$(t_cli_targets)
EOF
  t_empty strict_and_set_everywhere "$bad"
  # Verify that executing any state-mutating command in dry-run mode emits structured plan rows on
  # stdout without creating or modifying any files on disk.
  bad=""
  rm -rf "$T_WORK/cli-out"
  mkdir -p "$T_WORK/cli-out/runs/cli-bundle"
  for row in "bin/px-cluster build" "bin/px-cluster down -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster exec regionserver 0 -e PX_SCOPE_ID=px-cli -- true" \
    "bin/px-cluster group start yarn -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster kill regionserver 0 -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster signal regionserver 0 QUIT -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster restart regionserver 0 -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster endpoint regionserver 0 rpc -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster endpoint regionserver 0 rpc --release -e PX_SCOPE_ID=px-cli" \
    "bin/px-cluster matrix $T_WORK/suites/order.suite $T_WORK/cli-set.env" \
    "bin/px-pherf start" "bin/px-tool monitor -e PX_SCOPE_ID=px-cli" \
    "bin/px-report --verdict $T_WORK/cli-pass.tsv" \
    "bin/px-bundle write $cb" "bin/px-bundle validate $cb" "bin/px-bundle metrics $cb" \
    "bin/px-tool quiet-host -e PX_QUIET_HOST_WAIT_S=3 -e PX_REQUIRE_QUIET_HOST=1" \
    "bin/px-collect start $cb --collect host -e PX_SCOPE_ID=px-cli --class validation" \
    "suites/check-cluster.sh -e PX_RUN_DIR=$cb" \
    "suites/checks/cluster.sh -e PX_RUN_DIR=$cb -e PX_THRESHOLDS_FILE=$cb/t.properties" \
    "suites/checks/steady.sh -e PX_RUN_DIR=$cb -e PX_THRESHOLDS_FILE=$cb/t.properties" \
    "docker/compose.gen.sh --class validation" \
    "k8s/manifests.gen.sh --class validation --config $PX_HOME/k8s/env/k3d.env"; do
    read -r -a words <<<"$row"
    t_cli_run "${words[0]}" -n "${words[@]:1}"
    text="$(printf '%s\n' "$T_STDOUT" | grep -v "^plan${PX_TAB}[a-z]*${PX_TAB}")"
    if [ "$T_STATUS" != 0 ] || [ -z "$T_STDOUT" ] || [ -n "$text" ]; then
      bad="$bad [${row%% "$T_WORK"*}]=$T_STATUS"
    fi
  done
  t_empty dry_run_everywhere_prints_plan_rows "$bad"
  t_empty dry_run_writes_no_file "$(find "$T_WORK/cli-out" -type f 2>/dev/null)"
  # Verify that the lifecycle --timeout flag bounds cluster readiness waiting, while secondary
  # operation timeouts such as drain bounds are configured via settings.
  t_cli_run bin/px-cluster up "$T_DEPLOY" -n --timeout 7
  t_contains up_timeout_bounds_readiness "ready${PX_TAB}sb_wait_ready <scope> 7" "$T_STDOUT"
  t_cli_run bin/px-cluster restart regionserver 0 -n --timeout 7 -e PX_TIMEOUT_DRAIN=9 \
    -e PX_SCOPE_ID=px-cli
  t_contains restart_timeout_and_drain_setting \
    "restart${PX_TAB}sb_restart_drain px-cli regionserver 0 9 7" "$T_STDOUT"
  # Verify that the --cluster selector resolves the active scope previously persisted for that
  # cluster by px-cluster up.
  mkdir -p "$T_WORK/cli-out/state"
  printf 'px-other\n' >"$T_WORK/cli-out/state/scope-compose-other"
  t_cli_run bin/px-tool monitor --cluster other -n
  t_contains cluster_selects_the_scope_of_tools 'PX_SCOPE_ID=px-other' "$T_STDOUT"
  # Verify the two option-ordering regression cases identified during design review: passing global
  # selector options after a px-pherf subcommand and before a px-k3d subcommand.
  t_cli_run bin/px-pherf status --cluster nosuch -n
  t_equals pherf_options_after_the_subcommand "6 no" "$T_STATUS $(t_flag 'unknown' \
    "$T_STDERR")"
  t_cli_run bin/px-k3d --class validation create -n
  t_equals k3d_class_before_create no "$(printf '%s\n' "$T_STDERR" | grep -qE \
    "$T_CLI_PARSE_RE|belongs to" && echo yes || echo no)"
  t_cli_record_cases
}

## @description  Verifies shared selector help lines, the k3d default stage of the Kubernetes
##               backend, a target persisted in the cluster record, and teardown of nothing.
## @return       Records test assertions. Exit 0.
t_cli_record_cases() {
  local file bad="" target="$T_WORK/u3-target.env" record="$T_WORK/cli-out/state/cluster-u3t.env"
  for file in "$T_WORK"/cli-help-*.txt; do
    if grep -q '^      --class NAME' "$file" && ! grep -A1 '^      --class NAME' "$file" |
      tr -s ' \n' ' ' | grep -q 'default the class of the cluster record, else PX_RUN_CLASS'; then
      bad="$bad ${file##*/cli-help-}"
    fi
    if grep -q '^      --backend NAME' "$file" && ! grep -A1 '^      --backend NAME' "$file" |
      tr -s ' \n' ' ' | grep -q 'default the backend of the cluster record, else compose'; then
      bad="$bad ${file##*/cli-help-}"
    fi
  done
  t_empty shared_selectors_name_the_record "$bad"
  t_equals record_skip_list_is_the_documented_one "${PX_CONFIG_RECORD_SKIP:-unset}" \
    "$(tr -s ' \n' ' ' <"$PX_HOME/README.md" |
      sed -n 's/.*The record never holds \([^.]*\)\..*/\1/p' | tr -d '`,' | sed 's/ and / /')"
  t_cli_run bin/px-cluster config --backend k8s 'PX_K8S_*'
  t_contains k3d_env_is_the_k8s_default "$(t_rows 3 PX_K8S_CONTEXT k3d-px backend:px-k3d)" \
    "$T_STDOUT"
  printf 'PX_K8S_CONTEXT=remote-u3\n' >"$target"
  t_cli_run bin/px-cluster config --backend k8s -e "PX_K8S_TARGET=$target" 'PX_K8S_*'
  t_equals k3d_env_yields_to_a_target "yes no" "$(t_flag \
    "$(t_rows 3 PX_K8S_CONTEXT remote-u3 target)" "$T_STDOUT") $(t_flag backend:px-k3d \
    "$T_STDOUT")"
  mkdir -p "${record%/*}"
  printf "%s\n" "PX_BACKEND='k8s'" "PX_K8S_TARGET='$target'" "PX_K8S_CONTEXT='remote-u3'" \
    >"$record"
  t_cli_run bin/px-cluster --cluster u3t config 'PX_K8S_*' PX_BACKEND
  t_equals record_holds_the_target_values "0 yes yes" "$T_STATUS $(t_flag \
    "$(t_rows 3 PX_K8S_CONTEXT remote-u3 cluster)" "$T_STDOUT") $(t_flag \
    "$(t_rows 3 PX_BACKEND k8s cluster)" "$T_STDOUT")"
  rm -f "$record" "$target"
  t_cli_run bin/px-cluster down
  t_equals down_without_a_record_is_0 "0 yes" "$T_STATUS $(t_flag \
    'no cluster default is recorded' "$T_STDERR")"
}

## @description  Extracts test identifiers from suite plan TSV rows in planned execution order.
## @return       Writes the ids, separated by spaces, to stdout. Exit 0.
t_plan_ids() {
  printf '%s\n' "$1" | awk -F '\t' 'NF { printf "%s%s", s, $2; s = " " } END { print "" }'
}

## @description  Validates suite sorting, range boundaries, filters, fault tolerance, and isolation.
## @return       Records assertions. Exit 0.
t_group_suite() {
  local suite="$T_WORK/suites"
  printf '# the suite runner\n'
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run "$suite/order.suite"
  t_equals suite_resolves_the_dependency_order 'first second third fourth' \
    "$(t_plan_ids "$T_STDOUT")"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run --from second --to third \
    "$suite/order.suite"
  t_equals suite_cuts_the_order_at_the_boundaries 'second third' \
    "$(t_plan_ids "$T_STDOUT")"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run --only first,fourth \
    "$suite/order.suite"
  t_equals suite_keeps_only_the_named_tests 'first fourth' \
    "$(t_plan_ids "$T_STDOUT")"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run --skip second "$suite/order.suite"
  t_equals suite_removes_the_skipped_tests 'first third fourth' \
    "$(t_plan_ids "$T_STDOUT")"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run "$suite/cycle.suite"
  t_equals suite_refuses_a_dependency_cycle 2 "$T_STATUS"
  t_contains suite_names_the_cycle 'alpha -> beta -> alpha' "$T_STDERR"
  t_suite selftest-stop "$suite/mixed.suite"
  t_equals suite_stops_at_the_first_failure 1 "$T_STATUS"
  t_equals suite_runs_nothing_after_the_failure 3 "$(printf '%s\n' "$T_STDOUT" |
    grep -c .)"
  t_equals suite_ends_with_a_result_row "$(t_rows 4 result FAIL 1 \
    "$PX_OUT/sessions/selftest-stop")" "$(t_last_line "$T_STDOUT")"
  t_suite selftest-go --continue-on-failure "$suite/mixed.suite"
  t_equals suite_continues_past_a_failure 3 "$T_STATUS"
  t_contains suite_reports_the_worst_severity "harsh${PX_TAB}ERROR" "$T_STDOUT"
  t_equals suite_result_row_follows_the_session "$(t_rows 4 result ERROR 3 \
    "$PX_OUT/sessions/selftest-go") 1" "$(t_last_line "$T_STDOUT") $(printf '%s\n' \
    "$T_STDOUT" | grep -c "^result$PX_TAB")"
  t_equals suite_rows_carry_the_bundle "7 7 7 yes yes" "$(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$1 != "result" { printf "%s ", NF }')$(printf '%s\n' "$T_STDOUT" | awk -F '\t' \
    -v b="$(sed -n 1p "$PX_OUT/sessions/selftest-go/tests/good/stdout.txt" 2>/dev/null)" \
    '$2 == "good" { print ($7 == b && b != "") ? "yes" : "no" }') $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' -v r="$PX_OUT/runs/" '$2 == "harsh" { print (index($7, r) == 1) ? "yes" : "no" }')"
  t_equals results_file_carries_the_bundle "$(printf '%s\n' "$T_STDOUT" | grep -v \
    "^result$PX_TAB")" "$(cat "$PX_OUT/sessions/selftest-go/results.tsv" 2>/dev/null)"
  t_suite selftest-blocked --continue-on-failure "$suite/blocked.suite"
  t_equals suite_blocked_row_has_no_bundle "1 SKIP 7 empty" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$2 == "later" { print $3, NF, ($7 == "" ? "empty" : $7) }')"
  # Distinct sessions maintain separate output directories and result rows, and reusing an active
  # session identifier is rejected rather than appended to.
  t_suite selftest-second --continue-on-failure "$suite/mixed.suite"
  t_equals suite_sessions_hold_their_own_rows 3 \
    "$(grep -c . "$PX_OUT/sessions/selftest-second/results.tsv")"
  t_equals suite_sessions_do_not_share_state 3 \
    "$(grep -c . "$PX_OUT/sessions/selftest-go/results.tsv")"
  t_suite selftest-go --continue-on-failure "$suite/mixed.suite"
  t_equals suite_refuses_a_session_name_in_use 1 "$T_STATUS"
  # A dry run does not resolve the output root, so a nonexistent output root directory is never
  # created.
  t_with_shim "PX_OUT=$T_WORK/dry-run-out" "$T_BASH" "$T_BIN/px-suite" --dry-run \
    "$suite/order.suite"
  t_equals suite_dry_run_makes_no_session "0 no" \
    "$T_STATUS $(t_exists "$T_WORK/dry-run-out")"
  t_empty dry_run_has_no_result_row "$(printf '%s\n' "$T_STDOUT" | grep "^result$PX_TAB")"
  t_group_suite_regressions
}

## @description  Validates suite runner regression cases and operator run-class override (R-037).
## @return       Records assertions. Exit 0.
t_group_suite_regressions() {
  local suite="$T_WORK/suites" session="$PX_OUT/sessions" run_dir
  # Execute inside a directory containing matching *.sql files to detect unintended globbing.
  t_run_in "$T_WORK/globdir" env "PX_SESSION_ID=selftest-args" "$T_BASH" \
    "$T_BIN/px-suite" "$suite/args.suite"
  t_equals suite_args_run 0 "$T_STATUS"
  run_dir="$(cat "$session/selftest-args/tests/literal/stdout.txt" 2>/dev/null)"
  t_equals suite_args_reach_the_driver_as_written \
    '[*.sql][--glob][*.sql][two\back][][a b][~/data][x=~]' \
    "$(cat "$run_dir/logs/run.log" 2>/dev/null)"
  t_suite selftest-severity "$suite/severity.suite"
  t_equals suite_unknown_severity_exits_three 3 "$T_STATUS"
  t_contains suite_unknown_severity_is_an_error "unknown-severity${PX_TAB}ERROR" \
    "$T_STDOUT"
  t_shim_log_reset
  t_with_shim env "PX_SESSION_ID=selftest-noscope" "$T_BASH" "$T_BIN/px-suite" \
    "$suite/requires.suite"
  t_equals suite_requirement_without_scope_exits_three 3 "$T_STATUS"
  t_contains suite_requirement_without_scope_is_no_such_object \
    "needs-yarn${PX_TAB}ERROR${PX_TAB}6${PX_TAB}" "$T_STDOUT"
  t_empty suite_requirement_without_scope_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  # R-037: An operator-specified run class overrides the class declared by the suite, and px-run
  # records the effective run class that it executed with.
  t_suite selftest-class "$suite/class.suite"
  run_dir="$(cat "$session/selftest-class/tests/classed/stdout.txt" 2>/dev/null)"
  t_equals suite_class_of_the_suite_reaches_px_run "0 measurement" \
    "$T_STATUS $(grep "^run$PX_TAB" "$run_dir/run-record.tsv" 2>/dev/null | cut -f 3)"
  t_suite selftest-class-cli --class validation "$suite/class.suite"
  run_dir="$(cat "$session/selftest-class-cli/tests/classed/stdout.txt" 2>/dev/null)"
  t_equals suite_operator_class_wins_over_the_suite "0 validation" \
    "$T_STATUS $(grep "^run$PX_TAB" "$run_dir/run-record.tsv" 2>/dev/null | cut -f 3)"
  # Verify that the run class persisted in the cluster record overrides the class declared by the
  # suite, while an explicit --class flag overrides the cluster record.
  mkdir -p "$PX_OUT/state"
  printf 'PX_RUN_CLASS=validation\n' >"$PX_OUT/state/cluster-suiteclass.env"
  t_run "$T_BASH" "$T_BIN/px-suite" --cluster suiteclass -n "$suite/class.suite"
  t_equals suite_record_class_wins_over_the_suite "0 validation" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "classed" { print $6 }')"
  t_run "$T_BASH" "$T_BIN/px-suite" --cluster suiteclass --class measurement -n \
    "$suite/class.suite"
  t_equals suite_operator_class_wins_over_the_record "0 measurement" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "classed" { print $6 }')"
  # Even when PX_RUN_CLASS is exported in the environment and loaded before the cluster record, the
  # run class from the cluster record still takes precedence over the suite declaration.
  t_run env PX_RUN_CLASS=validation "$T_BASH" "$T_BIN/px-suite" --cluster suiteclass -n \
    "$suite/class.suite"
  t_equals suite_record_class_wins_under_an_exported_class "0 validation" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "classed" { print $6 }')"
  rm -f "$PX_OUT/state/cluster-suiteclass.env"
}

## @description  Validates run wrapper status pass-through, anomaly detection, and inventory reads.
## @return       Records assertions. Exit 0.
t_group_run() {
  local code record
  printf '# the run wrapper\n'
  t_shim_state_reset
  for code in 0 1 2 3; do
    t_run "$T_BASH" "$T_BIN/px-run" --class validation -e PX_POLL_INTERVAL_S=1 \
      "$T_WORK/drivers/exit-code.sh" -- "$code"
    t_equals "run_propagates_driver_exit_$code" "$code" "$T_STATUS"
  done
  # Executing a run without a cluster is a documented mode rather than a failure, so the failure
  # trap must emit no diagnostic for it.
  t_run env -u PX_SCOPE_ID "$T_BASH" "$T_BIN/px-run" --class validation \
    "$T_WORK/drivers/exit-code.sh" -- 0
  t_equals run_without_a_cluster 0 "$T_STATUS"
  t_absent run_without_a_cluster_reports_no_error 'ERROR' "$T_STDERR"
  # An initial snapshot that finds no existing start table is normal operation, so the failure
  # trap must emit no diagnostic for it.
  t_shim_state_reset
  t_px_run exit-code -- 0
  t_equals run_with_a_new_start_table "0 no" \
    "$T_STATUS $(t_flag 'command failed' "$T_STDERR")"
  # Have the stub driver increment the restart count reported by the fake runtime so that the
  # post-run snapshot diverges from the pre-run snapshot deterministically without any timing
  # assumptions.
  printf '0\n' >"$T_SHIM_STATE/restart-count"
  t_px_run bump-restart -- 4
  t_equals run_reports_an_unplanned_restart 3 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  t_contains run_records_the_unplanned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}4${PX_TAB}unplanned" \
    "$(grep '^diff' "$record" 2>/dev/null)"
  t_shim_state_reset
  # The recovery gate restarts the killed RegionServer via the region mover, requiring the drain
  # label on that instance, and the post-recovery check executes the bootstrap probe in client 0.
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||regionmover\n' c0ffee000001 regionserver \
    px-selftest-regionserver-1 running 'Up 3 minutes (healthy)' \
    phoenix-harness:selftest regionserver 1 0 >"$T_SHIM_STATE/instances.txt"
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000002 client px-selftest-client-1 \
    running 'Up 3 minutes (healthy)' phoenix-harness:selftest client 1 0 \
    >>"$T_SHIM_STATE/instances.txt"
  printf '0\n' >"$T_SHIM_STATE/restart-count-c0ffee000002"
  t_px_run bump-restart -e PX_PORT_REGIONSERVER_RPC_0=16020 \
    --disrupt 'phase:driver_start,kill,regionserver,0' -- 4
  t_equals run_accepts_a_planned_restart 0 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  # The total restart count combines the 4 restarts from the driver and 1 from the recovery gate
  # starting the instance, which assigns the instance a new start time.
  t_contains run_records_the_planned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}5${PX_TAB}planned" \
    "$(grep '^diff' "$record" 2>/dev/null)"
  # When the container restart policy is "no", a crashed container keeps a restart count of zero
  # and is detected instead by its transition from running to exited in the post-run snapshot.
  t_shim_state_reset
  t_px_run exit-instance
  t_equals run_reports_an_unplanned_exit 3 "$T_STATUS"
  record="$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  t_contains run_records_the_unplanned_exit \
    "$(t_rows 8 diff regionserver 0 0 0 unplanned running exited)" "$record"
  t_contains run_records_the_termination_reason \
    "$(t_rows 6 term regionserver 0 killed 137 9)" "$record"
  t_contains run_records_the_oom_evidence "$(t_rows 4 oom regionserver 0 no)" "$record"
  t_shim_state_reset
  t_px_run vanish
  t_equals run_reports_a_lost_instance 3 "$T_STATUS"
  t_contains run_records_the_lost_instance \
    "$(t_rows 7 diff regionserver 0 0 '' vanished running)" \
    "$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  # An unreachable runtime during post-run snapshot collection must be reported as an operational
  # failure (exit 1) rather than misinterpreted as a vanished container instance.
  t_shim_state_reset
  t_px_run unreachable
  t_equals run_unread_inventory_is_a_failure 1 "$T_STATUS"
  record="$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  t_contains run_unread_inventory_skips_the_diff \
    "$(t_rows 4 skip post diff 'the post-run inventory cannot be read')" "$record"
  t_absent run_unread_inventory_reports_no_lost_instance vanished "$record"
  # A stale planned-action ledger entry recorded prior to the current run must not excuse
  # instance crashes occurring during the run.
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/sb.sh" sb_planned_record px-selftest kill \
    regionserver 0 stale
  t_equals run_stale_planned_row_is_written 0 "$T_STATUS"
  t_px_run exit-instance
  t_equals run_ignores_a_planned_row_of_an_earlier_run 3 "$T_STATUS"
  t_shim_state_reset
}

## @description  Validates loader precedence, origin, fail-closed files, revision, and log copying.
## @return       Records assertions. Exit 0.
t_group_config() {
  local env_dir="$T_WORK/env"
  printf '# the configuration loader\n'
  # Stage N assigns the value N, and each stage defines one variable name more than the stage above
  # it so that each name reflects the strongest stage that sets it.
  t_run env "PX_ENV_DIR=$env_dir" "PX_RUN_CLASS=selftest" "PX_TIMEOUT_GROUP=1" \
    "PX_VERSIONS_ENV=$env_dir/versions.env" "PX_VERSIONS_LOCAL_ENV=$env_dir/versions-local.env" \
    "PX_PORT_MAP_ENV=$env_dir/ports.env" "$T_BASH" "$T_WORK/probe/config-load.sh" \
    "$env_dir/named.env"
  t_equals loader_precedence "$(t_rows 3 PX_TIMEOUT_DEFAULT 5 defaults PX_TIMEOUT_DRAIN 6 \
    versions PX_TIMEOUT_EXEC 3 local PX_TIMEOUT_FETCH 7 versions-local PX_TIMEOUT_GROUP 1 \
    environment PX_TIMEOUT_PROVISION 8 ports PX_TIMEOUT_READY 4 class:selftest \
    PX_TIMEOUT_SCALE 2 "$env_dir/named.env")" \
    "$(printf '%s\n' "$T_STDOUT" | grep "^PX_TIMEOUT_[A-Z]*$PX_TAB")"
  t_run env "PX_ENV_DIR=$env_dir" "$T_BASH" "$T_WORK/probe/config-strict.sh" \
    "$env_dir/undeclared.env"
  t_equals config_strict_refuses_an_undeclared_name 2 "$T_STATUS"
  t_contains config_strict_names_the_registry 'lib/varnames.txt' "$T_STDERR"
  t_run env "PX_ENV_DIR=$env_dir" "$T_BASH" "$T_WORK/probe/config-strict.sh" \
    "$env_dir/malformed.env"
  t_equals config_refuses_a_malformed_line 2 "$T_STATUS"
  t_contains config_names_the_malformed_line 'line 1: not an assignment' "$T_STDERR"
  t_run env "PX_ENV_DIR=$env_dir" "$T_BASH" "$T_WORK/probe/config-strict.sh" \
    "$env_dir/exported.env"
  t_equals config_refuses_an_export_line 2 "$T_STATUS"
  t_contains config_does_not_source_a_file 'KEY=VALUE, not export' "$T_STDERR"
  t_run env "PX_ENV_DIR=$env_dir" "$T_BASH" "$T_WORK/probe/config-strict.sh" \
    "$env_dir/absent.env"
  t_equals config_absent_named_file_is_no_such_object 6 "$T_STATUS"
  # The bundle format version is an immutable harness constant, so no configuration stage can alter
  # which format version a bundle claims.
  t_run "$T_BASH" "$T_BIN/px-run" -e PB_FORMAT_VERSION=2 exit-code
  t_equals config_command_line_cannot_set_a_constant 2 "$T_STATUS"
  t_contains config_names_the_constant 'is a constant of the harness' "$T_STDERR"
  # The mirrored log file adheres to the output boundary policy: running px-cluster status with no
  # active cluster emits an error line after configuration loading completes, which is captured in
  # an admitted log file.
  t_run env "PX_LOG_FILE=selftest.log" "$T_BASH" "$T_BIN/px-cluster" status
  t_equals config_log_file_run_ends_with_no_such_object 6 "$T_STATUS"
  t_contains config_log_file_under_the_root_gets_a_copy 'no cluster default is recorded' \
    "$(cat "$PX_OUT/selftest.log" 2>/dev/null)"
  t_run env "PX_LOG_FILE=$T_WORK/escape.log" "$T_BASH" "$T_BIN/px-cluster" status
  t_equals config_log_file_outside_the_root_is_refused 2 "$T_STATUS"
  t_equals config_refused_log_file_stays_absent no "$(t_exists "$T_WORK/escape.log")"
  t_run env "PX_LOG_FILE=$PX_HOME/selftest.log" "$T_BASH" "$T_BIN/px-cluster" status
  t_equals config_log_file_in_the_tree_is_refused 2 "$T_STATUS"
  t_equals config_log_file_in_the_tree_stays_absent no "$(t_exists "$PX_HOME/selftest.log")"
}

## @description  Validates the output policy, run identifier generation, and the timeout fallback.
## @return       Records assertions. Exit 0.
t_group_output() {
  local ids dirs
  printf '# the output policy and the identifiers\n'
  t_run env "PX_OUT=$PX_HOME/selftest-refused" "$T_BASH" \
    "$T_WORK/probe/out-root.sh"
  t_equals out_refuses_a_path_inside_the_tree 2 "$T_STATUS"
  t_contains out_names_the_working_tree 'must stay outside the working tree' "$T_STDERR"
  t_run env "PX_OUT=selftest/relative" "$T_BASH" "$T_WORK/probe/out-root.sh"
  t_equals out_refuses_a_relative_path 2 "$T_STATUS"
  t_run env "PX_OUT=/" "$T_BASH" "$T_WORK/probe/out-root.sh"
  t_equals out_refuses_the_filesystem_root 2 "$T_STATUS"
  t_run "$T_BASH" "$T_WORK/probe/out-resolve.sh" '../escapes-the-root'
  t_equals out_resolve_refuses_an_escaping_destination 2 "$T_STATUS"
  t_contains out_resolve_names_the_root 'destination escapes PX_OUT' "$T_STDERR"
  t_run "$T_BASH" "$T_WORK/probe/out-resolve.sh" 'inside/the/root'
  t_equals out_resolve_accepts_a_destination_under_the_root \
    "$PX_OUT/inside/the/root" "$T_STDOUT"
  # When PX_OUT is set to an ancestor directory of the working tree, destination resolution must
  # independently reject target paths that land inside the working tree. The probe writes no files.
  if [ -n "${PX_TREE_ROOT%/*}" ]; then
    t_run env "PX_OUT=${PX_TREE_ROOT%/*}" "$T_BASH" "$T_WORK/probe/out-resolve.sh" \
      "$PX_HOME/selftest.log"
    t_equals out_resolve_refuses_a_destination_in_the_tree 2 "$T_STATUS"
    t_contains out_resolve_names_the_tree 'inside the working tree' "$T_STDERR"
  fi
  t_run "$T_BASH" "$T_WORK/probe/timeout.sh"
  t_equals timeout_fallback_keeps_stdin_and_expires \
    "$(printf 'through the fallback\nstdin 0\nexpiry 124')" "$T_STDOUT"
  t_run "$T_BASH" "$T_WORK/probe/timeout-nap.sh" "$T_WORK/nap"
  t_empty timeout_fallback_leaves_no_sleep "$T_STDOUT"
  t_run "$T_BASH" "$T_WORK/probe/run-ids.sh"
  ids="$(printf '%s\n' "$T_STDOUT" | head -8)"
  t_equals two_run_ids_of_one_second_differ 8 \
    "$(printf '%s\n' "$ids" | LC_ALL=C sort -u | grep -c .)"
  dirs="$(printf '%s\n' "$T_STDOUT" | tail -2)"
  t_equals two_run_directories_differ 2 \
    "$(printf '%s\n' "$dirs" | LC_ALL=C sort -u | grep -c .)"
  t_contains a_run_directory_lives_under_the_output_root "$PX_OUT/runs/" "$dirs"
}

## @description  Validates the JSON emitters and the rejection of TSV fields that would break a row.
## @return       Records assertions. Exit 0.
t_group_emitters() {
  local expected
  printf '# the JSON and TSV emitters\n'
  t_run "$T_BASH" "$T_WORK/probe/json.sh"
  t_equals json_escapes_quote_backslash_and_control 'a\"b\\c\td\ne\u0001f' \
    "$(printf '%s\n' "$T_STDOUT" | head -1)"
  expected='{
  "name": "the \"run\"",
  "roles": [
    "rs",
    {
      "index": 0
    }
  ]
}'
  t_equals json_writes_a_nested_document "$expected" \
    "$(printf '%s\n' "$T_STDOUT" | tail -n +2)"
  t_run "$T_BASH" "$T_WORK/probe/json-number.sh" 'not-a-number'
  t_equals json_number_refuses_a_non_number 1 "$T_STATUS"
  t_run "$T_BASH" "$T_WORK/probe/json-number.sh" '-1.5e3'
  t_equals json_number_accepts_a_json_number 0 "$T_STATUS"
  t_run "$T_BASH" "$T_WORK/probe/tsv.sh"
  t_contains tsv_refuses_a_field_with_a_tab 'tab 1' "$T_STDOUT"
  t_contains tsv_refuses_a_field_with_a_newline 'newline 1' "$T_STDOUT"
  t_contains tsv_keeps_an_empty_column 'second []' "$T_STDOUT"
  t_contains tsv_reads_the_column_after_an_empty_one 'third [gamma]' "$T_STDOUT"
  t_contains tsv_absent_column_is_no_such_object 'absent 6' "$T_STDOUT"
  t_contains tsv_clean_replaces_tab_and_newline 'clean [x y z]' "$T_STDOUT"
}

# ---------------------------------------------------------------------------
# Comprehensive contract verb and px-cluster subcommand sweep
# ---------------------------------------------------------------------------
# Executes all 32 substrate contract functions and every px-cluster subcommand at least once
# against the fake container runtime, verifying both standard output and exit status for each call.
# The sweep operates in a dedicated output root and fake state directory, with each group building
# sequentially on the state left by its predecessor in the following order: scope and provisioning,
# readiness and role groups, instance access, diagnostic evidence and fault injection, scaling and
# observation, runtime failure and teardown, and finally the px-cluster CLI subcommands.

## @description  Sweeps backend identity, the scope lifecycle verbs, provisioning, and inventory.
## @return       Records assertions. Exit 0.
t_group_sweep_scope() {
  local rows
  printf '# verb sweep: identity, scope, provision and inventory\n'
  t_shim_log_reset
  t_sb sb_backend_id
  t_outcome sweep_sb_backend_id 0 compose
  t_sb sb_version_info
  t_outcome sweep_sb_version_info 0 "$(t_rows 2 engine_client 29.9.3 \
    engine_server 29.9.3 compose_version 2.40.0)"
  t_sb_codes sweep_scope_refusals "
2 sb_scope_create Bad
2 sb_scope_exists not-a-scope
6 sb_scope_exists px-sweep"
  t_sweep PX_DRY_RUN=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_scope_create sweep
  t_outcome sweep_sb_scope_create_dry_run 0 px-sweep
  t_empty sweep_scope_create_dry_run_writes_no_record \
    "$(find "$T_SWEEP_OUT" -name 'scope-*' 2>/dev/null)"
  t_sb sb_scope_create sweep
  t_outcome sweep_sb_scope_create 0 px-sweep
  t_sb sb_scope_create other
  t_equals sweep_scope_gate_refuses_a_second_scope 1 "$T_STATUS"
  t_contains sweep_scope_gate_names_the_recorded_scope 'another scope is recorded' \
    "$T_STDERR"
  t_sb sb_scope_exists px-sweep
  t_outcome sweep_sb_scope_exists_by_record 0 ""
  t_sb sb_wait_ready px-sweep 1
  t_outcome sweep_wait_ready_on_an_empty_scope_is_no_such_object 6 ""
  t_contains sweep_wait_ready_names_the_empty_scope 'holds no instance' "$T_STDERR"
  t_sb sb_provision px-sweep "$T_WORK/no-deploy" 30
  t_equals sweep_provision_needs_a_compose_file 2 "$T_STATUS"
  # Reject deployments whose created containers lack the mandatory cluster scope label.
  printf 'nolabel\n' >"$T_SWEEP_STATE/cluster"
  t_sb sb_provision px-sweep "$T_DEPLOY" 30
  t_equals sweep_provision_needs_the_cluster_label 2 "$T_STATUS"
  printf 'sweep\n' >"$T_SWEEP_STATE/cluster"
  : >"$T_SWEEP_STATE/instances.txt"
  t_shim_log_reset
  t_sb sb_provision px-sweep "$T_DEPLOY" 30
  rows="$(t_rows 7 master 0 ms01 running ready px:selftest localhost \
    nodemanager 0 nm01 pending unready px:selftest localhost \
    regionserver 0 rs01 running ready px:selftest localhost \
    regionserver 1 rs02 running ready px:selftest localhost)"
  t_outcome sweep_sb_provision 0 "$rows"
  t_called sweep_provision_creates_every_service \
    'compose -p px-sweep up --no-start --no-build'
  t_called sweep_provision_starts_no_on_demand_service \
    'compose -p px-sweep up -d --no-recreate --no-build master regionserver'
  t_sb sb_list px-sweep
  t_outcome sweep_sb_list 0 "$rows"
  t_sb sb_list px-sweep regionserver 1
  t_outcome sweep_sb_list_one_instance 0 \
    "$(t_rows 7 regionserver 1 rs02 running ready px:selftest localhost)"
  t_sb sb_list px-sweep "" 1
  t_equals sweep_sb_list_index_needs_a_role 2 "$T_STATUS"
  t_sb_codes sweep_sb_list_refusals "
6 sb_list px-sweep nosuch
6 sb_list px-sweep regionserver 7
2 sb_list px-sweep regionserver x
2 sb_list px-sweep regionserver 0 extra"
}

## @description  Sweeps readiness checks and role groups, including stopped and crashed groups.
## @return       Records assertions. Exit 0.
t_group_sweep_ready() {
  local saved="$T_WORK/sweep-saved.txt"
  local nm='^\(nm01|[^|]*|[^|]*|\)[^|]*|[^|]*|'
  printf '# verb sweep: readiness and role groups\n'
  t_sb sb_wait_ready px-sweep 5
  t_outcome_columns sweep_wait_ready_reports_a_stopped_group 0 3 "$(t_rows 3 \
    master 0 ready nodemanager 0 stopped regionserver 0 ready regionserver 1 ready)"
  t_sb sb_wait_ready px-sweep 1 nosuch
  t_outcome sweep_wait_ready_on_an_unknown_role_is_no_such_object 6 ""
  t_contains sweep_wait_ready_names_the_unknown_role 'has the role: nosuch' "$T_STDERR"
  t_sb sb_wait_ready px-sweep 1 nodemanager
  t_outcome_columns sweep_wait_ready_waits_for_a_named_stopped_role 4 3 \
    "$(t_rows 3 nodemanager 0 unready)"
  t_sb sb_group_status px-sweep yarn
  t_outcome sweep_sb_group_status_down 0 "$(t_rows 4 yarn 0 1 down)"
  t_shim_log_reset
  t_sb sb_group_start px-sweep yarn 5
  t_outcome_columns sweep_sb_group_start 0 3 "$(t_rows 3 nodemanager 0 ready)"
  t_called sweep_group_start_starts_the_service 'compose -p px-sweep start nodemanager'
  t_sb sb_group_status px-sweep yarn
  t_outcome sweep_sb_group_status_up 0 "$(t_rows 4 yarn 1 1 up)"
  t_shim_log_reset
  t_sb sb_group_stop px-sweep yarn 5
  t_outcome sweep_sb_group_stop 0 "$(t_rows 3 nodemanager 0 stopped)"
  t_called sweep_group_stop_keeps_the_instance \
    'compose -p px-sweep stop --timeout 120 nodemanager'
  t_sb sb_group_stop px-sweep yarn 5
  t_outcome sweep_sb_group_stop_again 0 "$(t_rows 3 nodemanager 0 already_stopped)"
  # Because sb_group_stop records the intentional group stop, a whole-scope readiness wait treats
  # its exited container as stopped rather than crashed.
  t_sb sb_wait_ready px-sweep 5
  t_outcome_columns sweep_wait_ready_skips_a_recorded_stop 0 3 "$(t_rows 3 \
    master 0 ready nodemanager 0 stopped regionserver 0 ready regionserver 1 ready)"
  : >"$T_SWEEP_STATE/start-exits"
  t_sb sb_group_start px-sweep yarn 5
  t_equals sweep_group_start_passes_an_anomaly_through 3 "$T_STATUS"
  rm -f "$T_SWEEP_STATE/start-exits"
  # Simulate a NodeManager that is running when the wait begins and then exits with code 137 on
  # the next poll so the readiness check classifies the exit as an unplanned crash.
  cp "$T_SWEEP_STATE/instances.txt" "$saved"
  sed "s/$nm/\\1running|Up 1 second (health: starting)|/" "$saved" \
    >"$T_SWEEP_STATE/instances.txt"
  sed "s/$nm/\\1exited|Exited (137) 1 second ago|/" "$saved" >"$T_SWEEP_STATE/next.txt"
  t_sb sb_wait_ready px-sweep 5
  t_outcome_columns sweep_wait_ready_reports_a_crash_in_a_running_group 3 3 "$(t_rows 3 \
    master 0 ready nodemanager 0 unready regionserver 0 ready regionserver 1 ready)"
  # An on-demand group whose member exited non-zero without a recorded group stop must be treated
  # as a pre-existing crash rather than an intentionally stopped group.
  sed "s/$nm/\\1exited|Exited (1) 2 seconds ago|/" "$saved" \
    >"$T_SWEEP_STATE/instances.txt"
  t_sb sb_wait_ready px-sweep 3
  t_equals sweep_wait_ready_reports_a_crash_before_the_wait 3 "$T_STATUS"
  sed "s/$nm/\\1restarting|Restarting (1) 1 second ago|/" "$saved" \
    >"$T_SWEEP_STATE/instances.txt"
  t_sb sb_wait_ready px-sweep 1
  t_outcome_columns sweep_wait_ready_waits_for_a_restarting_group 4 3 "$(t_rows 3 \
    master 0 ready nodemanager 0 unready regionserver 0 ready regionserver 1 ready)"
  cp "$saved" "$T_SWEEP_STATE/instances.txt"
  rm -f "$T_SWEEP_STATE/next.txt"
  t_sb_codes sweep_group_refusals "
2 sb_group_status px-sweep nogroup
2 sb_group_start px-sweep nogroup 5
2 sb_group_stop px-sweep nogroup 5
2 sb_group_start px-sweep yarn 0
2 sb_wait_ready px-sweep 0"
}

## @description  Sweeps instance exec, log retrieval, both copy operations, and endpoint queries.
## @return       Records assertions. Exit 0.
t_group_sweep_access() {
  printf '# verb sweep: exec, logs, copies and endpoints\n'
  t_shim_log_reset
  t_sb sb_exec px-sweep regionserver 0 30 -- sh -c 'printf "%s\n" exec-out; exit 7'
  t_outcome sweep_sb_exec_passes_the_command_through 7 exec-out
  t_called sweep_exec_selects_the_instance_number \
    "compose -p px-sweep exec -T --index=1 regionserver sh -c printf \"%s\\n\" exec-out; exit 7"
  t_sb sb_exec px-sweep regionserver 0 1 -- sh -c 'sleep 30'
  t_equals sweep_exec_expiry_is_124 124 "$T_STATUS"
  t_sb_codes sweep_exec_refusals "
2 sb_exec px-sweep regionserver 0 30 + true
2 sb_exec px-sweep regionserver 0 0 -- true
6 sb_exec px-sweep regionserver 9 30 -- true
125 sb_exec px-sweep nodemanager 0 30 -- true"
  t_sb sb_logs px-sweep regionserver 0 - 0 0
  t_outcome sweep_sb_logs_merges_both_streams 0 \
    "$(printf 'fake log out rs01\nfake log err rs01')"
  t_sb_codes sweep_logs_refusals "
5 sb_logs px-sweep regionserver 0 - 1 0
2 sb_logs px-sweep regionserver 0 - 0 2
2 sb_logs px-sweep regionserver 0 soon 0 0
6 sb_logs px-sweep regionserver 9 - 0 0"
  t_shim_log_reset
  t_sb sb_copy_in px-sweep regionserver 0 "$T_DEPLOY/compose.yaml" /tmp/staged
  t_outcome sweep_sb_copy_in 0 ""
  t_called sweep_copy_in_names_the_instance \
    "compose -p px-sweep cp --index=1 $T_DEPLOY/compose.yaml regionserver:/tmp/staged"
  t_sb sb_copy_out px-sweep regionserver 0 /etc/hosts copies/hosts
  t_outcome sweep_sb_copy_out 0 ""
  t_equals sweep_copy_out_lands_under_the_output_root 'fake copy' \
    "$(cat "$T_SWEEP_OUT/copies/hosts" 2>/dev/null)"
  t_sb_codes sweep_copy_refusals "
2 sb_copy_in px-sweep regionserver 0 /nonexistent/source /tmp/x
6 sb_copy_in px-sweep regionserver 9 /etc/hosts /tmp/x
2 sb_copy_out px-sweep regionserver 0 /etc/hosts /tmp/px-selftest-escape
6 sb_copy_out px-sweep regionserver 9 /etc/hosts copies/x"
  t_sb sb_endpoint px-sweep master 0 ui
  t_outcome sweep_sb_endpoint 0 "$(t_rows 2 127.0.0.1 1)"
  t_sb sb_endpoint_release px-sweep master 0 all
  t_outcome sweep_sb_endpoint_release 0 ""
  t_sb_codes sweep_endpoint_refusals "
6 sb_endpoint px-sweep master 0 nosuch
6 sb_endpoint px-sweep master 9 ui
2 sb_endpoint px-sweep master 0
2 sb_endpoint_release px-sweep master 0
2 sb_endpoint_release not-a-scope master 0 all"
}

## @description  Sweeps the diagnostic evidence verbs, signal delivery, and drain-aware restarts.
## @return       Records assertions. Exit 0.
t_group_sweep_evidence() {
  local count
  printf '# verb sweep: evidence, signal and drain-aware restart\n'
  t_sb sb_image_ref px-sweep master 0
  t_outcome sweep_sb_image_ref 0 "$(t_rows 2 px:selftest sha256:ms01)"
  t_sb sb_list px-sweep
  count="$(printf '%s\n' "$T_STDOUT" | grep -c .)"
  t_shim_log_reset
  t_sb sb_restart_count px-sweep
  t_equals sweep_sb_restart_count "0 yes $count" "$T_STATUS $(t_flag "$(t_rows 3 master 0 0)" \
    "$T_STDOUT") $(printf '%s\n' "$T_STDOUT" | grep -c .)"
  # A single inspection call queries every instance in the scope at once, so no call targets a
  # single container in isolation.
  t_equals sweep_restart_count_inspects_every_instance_at_once "1 0" \
    "$(grep -c RestartCount "$T_SHIM_LOG") $(awk '/RestartCount/ && NF == 4' "$T_SHIM_LOG" |
      grep -c .)"
  # A restart triggered from outside the harness changes only the instance start time; its
  # reported restart count increments by 1 once, and subsequent reads preserve that count.
  printf '2026-01-02T00:00:00Z\n' >"$T_SWEEP_STATE/started-ms01"
  t_sb sb_restart_count px-sweep
  count=$T_STDOUT
  t_sb sb_restart_count px-sweep
  t_equals sweep_restart_count_adds_a_changed_start_once "yes yes" \
    "$(t_flag "$(t_rows 3 master 0 1)" "$count") $(t_flag "$(t_rows 3 master 0 1)" "$T_STDOUT")"
  rm -f "$T_SWEEP_STATE/started-ms01" "$T_SWEEP_OUT/state/compose/starts-px-sweep.tsv"
  t_sb sb_termination_reason px-sweep master 0
  t_outcome sweep_sb_termination_reason 0 \
    "$(t_rows 4 running 0 '' 2026-01-01T00:00:00Z)"
  t_sb sb_oom_killed px-sweep master 0
  t_outcome sweep_sb_oom_killed 0 \
    "$(t_rows 2 no 'State.OOMKilled is false, exit code 0')"
  t_sb_codes sweep_evidence_refusals "
5 sb_termination_reason px-sweep master 0 previous
2 sb_termination_reason px-sweep master 0 bogus
6 sb_termination_reason px-sweep master 9
2 sb_restart_count not-a-scope
6 sb_oom_killed px-sweep master 9
6 sb_image_ref px-sweep master 9"
  : >"$T_SWEEP_STATE/inspect-fails"
  t_sb_codes sweep_inspection_failure_is_failure "
1 sb_restart_count px-sweep
1 sb_termination_reason px-sweep master 0
1 sb_oom_killed px-sweep master 0
1 sb_image_ref px-sweep master 0"
  rm -f "$T_SWEEP_STATE/inspect-fails"
  # Verify D-4: any signal other than KILL or TERM targets the daemon JVM (the oldest java process
  # in the instance) rather than the instance init process (tini), because tini cannot forward a
  # STOP signal. Because the fake JVM is a real process, its process state directly reflects whether
  # STOP paused it.
  t_shim_log_reset
  t_sb sb_signal px-sweep master 0 STOP
  t_equals sweep_signal_stop_pauses_the_daemon_jvm "0 T no" "$T_STATUS\
 $(t_proc_state "$T_SWEEP_JVM") $(t_flag 'kill -s STOP' "$(cat "$T_SHIM_LOG")")"
  t_sb sb_signal px-sweep master 0 CONT
  t_equals sweep_signal_cont_resumes_the_daemon_jvm "0 run" \
    "$T_STATUS $(t_proc_state "$T_SWEEP_JVM")"
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  # Verify that when a target process does not enter the stopped state within 5 s, the call fails
  # with a dedicated timeout diagnostic rather than reporting a failed signal delivery.
  t_ps_stop_fails "$T_SWEEP_STATE"
  t_sb sb_signal px-sweep master 0 STOP
  t_equals sweep_signal_state_not_shown_is_failure "1 T yes no" "$T_STATUS\
 $(t_proc_state "$T_SWEEP_JVM") $(t_flag 'did not reach the STOP state within 5 s' "$T_STDERR")\
 $(t_flag 'was not delivered' "$T_STDERR")"
  t_ps_stop_fails "$T_SWEEP_STATE" off
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  t_shim_log_reset
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_quit_goes_to_the_daemon_jvm "0 yes no" "$T_STATUS $(t_flag \
    'exec -T --index=1 master' "$(cat "$T_SHIM_LOG")") $(t_flag 'kill -s QUIT' \
    "$(cat "$T_SHIM_LOG")")"
  # Confirm that KILL and TERM are still delivered directly to the container instance as before,
  # terminating it just as a container kill or stop operation does.
  t_shim_log_reset
  t_sb sb_signal px-sweep master 0 TERM
  t_outcome sweep_sb_signal 0 ""
  t_called sweep_signal_term_names_the_instance 'kill -s TERM ms01'
  t_sb_codes sweep_signal_refusals "
2 sb_signal px-sweep master 0 BOGUS
6 sb_signal px-sweep master 9 TERM"
  # Verify that an instance with no daemon JVM is reported as a missing target object, whereas a
  # daemon JVM that has already exited is reported as a failed signal delivery.
  mv -f "$T_SWEEP_STATE/jvm-pid" "$T_SWEEP_STATE/jvm-pid.kept"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_without_a_jvm_is_no_object 6 "$T_STATUS"
  printf '999999\n' >"$T_SWEEP_STATE/jvm-pid"
  t_sb sb_signal px-sweep master 0 STOP
  t_equals sweep_signal_to_a_gone_jvm_is_failure 1 "$T_STATUS"
  mv -f "$T_SWEEP_STATE/jvm-pid.kept" "$T_SWEEP_STATE/jvm-pid"
  # Delivering a signal during a runtime outage fails and succeeds again once the runtime responds.
  : >"$T_SWEEP_STATE/unreachable"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  rm -f "$T_SWEEP_STATE/unreachable"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_after_an_outage 0 "$T_STATUS"
  t_sb sb_restart_drain px-sweep regionserver 0 10 5
  t_outcome_columns sweep_sb_restart_drain 0 2 \
    "$(t_rows 2 drain ok stop ok start ok ready ok rebalance ok)"
  t_contains sweep_drain_names_the_rpc_port 'mover unload host-rs01:2' "$T_STDERR"
  t_contains sweep_drain_moves_the_regions_back 'mover load host-rs01:2' "$T_STDERR"
  t_sb_codes sweep_drain_refusals "
2 sb_restart_drain px-sweep master 0 10 5
2 sb_restart_drain px-sweep regionserver 1 10 5
6 sb_restart_drain px-sweep regionserver 9 10 5
2 sb_restart_drain px-sweep regionserver 0 0 5"
}

## @description  Sweeps scaling, kill operations, event queries, resource samples, and preflight.
## @return       Records assertions. Exit 0.
t_group_sweep_scale() {
  printf '# verb sweep: scale refusals, kill, samples and preflight\n'
  # Scaling requires the rendered service table, which this hand-written deployment does not carry;
  # the a2 cluster test group exercises scaling against a rendered deployment.
  t_sb_codes sweep_scale_refusals "
2 sb_scale px-sweep regionserver 10 10
2 sb_scale px-sweep regionserver 3 10
2 sb_scale px-sweep nosuch 2 10
2 sb_scale px-sweep regionserver 2 0"
  # Seed a third RegionServer instance required by the test cases below.
  printf '%s|%s|%s|%s|%s|%s|%s|%s||||regionmover\n' rs03 regionserver \
    px-sweep-regionserver-3 running 'Up 1 second (healthy)' px:selftest regionserver 3 \
    >>"$T_SWEEP_STATE/instances.txt"
  # Because an exited instance runs no active process, restarting it skips the drain, stop, and
  # rebalance phases even after an earlier drain of that instance. Only regionserver 0 defines an
  # rpc port in this port map.
  t_sb sb_kill px-sweep regionserver 0 hard
  t_shim_log_reset
  t_sb sb_restart_drain px-sweep regionserver 0 10 5
  t_outcome_columns sweep_restart_drain_starts_a_killed_instance 0 2 \
    "$(t_rows 2 drain skip stop skip start ok ready ok rebalance skip)"
  t_absent sweep_killed_instance_has_no_unload 'mover unload' "$T_STDERR"
  t_absent sweep_killed_instance_loads_no_regions 'mover load' "$T_STDERR"
  t_absent sweep_killed_instance_has_no_stop 'stop --time' "$(cat "$T_SHIM_LOG")"
  # The readiness wait inspects only the restarted instance, so an exited sibling instance is not
  # treated as a fault; however, the wait fails if the restarted instance exits and expires if the
  # instance remains unready.
  t_shim_log_reset
  t_sb sb_kill px-sweep regionserver 2 hard
  t_outcome sweep_sb_kill_hard 0 ""
  t_called sweep_kill_hard_sends_kill 'kill -s KILL rs03'
  t_sb sb_kill px-sweep regionserver 0 hard
  t_sb sb_restart_drain px-sweep regionserver 0 10 5
  t_outcome_columns sweep_restart_drain_ignores_an_exited_sibling 0 2 \
    "$(t_rows 2 drain skip stop skip start ok ready ok rebalance skip)"
  t_sb sb_kill px-sweep regionserver 0 hard
  : >"$T_SWEEP_STATE/start-exits"
  t_sb sb_restart_drain px-sweep regionserver 0 10 5
  t_outcome_columns sweep_restart_drain_fails_when_the_instance_exits 1 2 \
    "$(t_rows 2 drain skip stop skip start ok ready fail rebalance skip)"
  rm -f "$T_SWEEP_STATE/start-exits"
  : >"$T_SWEEP_STATE/start-unready"
  t_sb sb_restart_drain px-sweep regionserver 0 10 1
  t_outcome_columns sweep_restart_drain_expires_when_the_instance_stays_unready 4 2 \
    "$(t_rows 2 drain skip stop skip start ok ready fail rebalance skip)"
  rm -f "$T_SWEEP_STATE/start-unready"
  t_sb sb_kill px-sweep regionserver 0 hard
  t_sb sb_restart_drain px-sweep regionserver 0 10 5
  t_sb sb_termination_reason px-sweep regionserver 2
  t_outcome sweep_termination_reason_of_a_kill 0 \
    "$(t_rows 4 killed 137 9 2026-01-01T00:00:00Z)"
  t_sb sb_wait_ready px-sweep 5
  t_equals sweep_wait_ready_reports_an_exit_as_an_anomaly 3 "$T_STATUS"
  printf 'rs03\n' >"$T_SWEEP_STATE/oom-ids"
  t_sb sb_oom_killed px-sweep regionserver 2
  t_outcome sweep_sb_oom_killed_yes 0 \
    "$(t_rows 2 yes 'State.OOMKilled is true, exit code 137')"
  t_sb sb_termination_reason px-sweep regionserver 2
  t_outcome sweep_termination_reason_of_an_oom 0 \
    "$(t_rows 4 oom 137 9 2026-01-01T00:00:00Z)"
  t_shim_log_reset
  t_sb sb_kill px-sweep regionserver 2 delete
  t_outcome sweep_sb_kill_delete 0 ""
  t_called sweep_kill_delete_removes_the_instance 'rm -f rs03'
  t_sb_codes sweep_kill_refusals "
6 sb_list px-sweep regionserver 2
6 sb_kill px-sweep regionserver 2 hard
2 sb_kill px-sweep regionserver 0 gentle"
  t_sb sb_sample_resources px-sweep
  t_equals sweep_sb_sample_resources 0 "$T_STATUS"
  t_contains sweep_sample_converts_the_units \
    "$(t_rows 9 master 0 1.50 134217728 1073741824 1200 3400 5600 7800)" \
    "$(printf '%s\n' "$T_STDOUT" | cut -f 2-)"
  : >"$T_SWEEP_STATE/no-stats"
  t_sb_codes sweep_observation_refusals "
5 sb_sample_resources px-sweep"
  rm -f "$T_SWEEP_STATE/no-stats"
  t_sb sb_preflight px-sweep
  t_outcome_columns sweep_sb_preflight 0 2 "$(t_rows 2 engine_binary ok \
    engine_responsive ok engine_version ok engine_credentials ok \
    compose_version ok engine_memory ok engine_cpus ok port_availability ok)"
  printf '19.03.12\n' >"$T_SWEEP_STATE/server-version"
  t_sb sb_preflight px-sweep
  t_equals sweep_preflight_refuses_an_old_engine 3 "$T_STATUS"
  t_contains sweep_preflight_names_the_engine_floor "engine_version${PX_TAB}fail" \
    "$T_STDOUT"
  rm -f "$T_SWEEP_STATE/server-version"
}

## @description  Sweeps unresponsive-runtime handling, teardown, and scope removal by project name.
## @return       Records assertions. Exit 0.
t_group_sweep_teardown() {
  local table
  printf '# verb sweep: runtime failure, teardown and scope removal\n'
  : >"$T_SWEEP_STATE/unreachable"
  t_sb_codes sweep_runtime_failure_is_failure "
1 sb_version_info
1 sb_scope_create another
1 sb_scope_exists px-other
0 sb_scope_exists px-sweep
1 sb_list px-sweep
1 sb_wait_ready px-sweep 1
1 sb_group_status px-sweep yarn
1 sb_group_start px-sweep yarn 5
1 sb_group_stop px-sweep yarn 5
1 sb_kill px-sweep master 0 hard
1 sb_signal px-sweep master 0 TERM
1 sb_logs px-sweep master 0 - 0 0
1 sb_copy_in px-sweep master 0 /etc/hosts /tmp/x
1 sb_copy_out px-sweep master 0 /etc/hosts copies/x
1 sb_endpoint px-sweep master 0 ui
1 sb_restart_count px-sweep
1 sb_termination_reason px-sweep master 0
1 sb_oom_killed px-sweep master 0
1 sb_image_ref px-sweep master 0
1 sb_restart_drain px-sweep regionserver 0 10 5
1 sb_teardown px-sweep 0
125 sb_exec px-sweep master 0 30 -- true
5 sb_sample_resources px-sweep"
  # When an unrecorded scope is queried while the runtime is unreachable, its absence cannot be
  # confirmed, so commands must report the runtime failure rather than a missing scope.
  t_sweep "$T_BASH" "$T_BIN/px-tool" -e PX_SCOPE_ID=px-other monitor
  t_equals sweep_tool_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  t_sweep PX_RUN_CLASS=validation "$T_BASH" "$T_BIN/px-collect" start -e PX_SCOPE_ID=px-other \
    --collect host --duration 1
  t_equals sweep_collect_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  rm -f "$T_SWEEP_STATE/unreachable"
  t_sb_codes sweep_teardown_refusals "
2 sb_teardown px-sweep 2
2 sb_scope_delete not-a-scope"
  : >"$T_SWEEP_STATE/sticky"
  t_sb sb_teardown px-sweep 0
  t_equals sweep_teardown_reports_a_leftover 1 "$T_STATUS"
  t_contains sweep_teardown_names_the_leftover 'containers of px-sweep remain' \
    "$T_STDERR"
  rm -f "$T_SWEEP_STATE/sticky"
  : >"$T_SWEEP_STATE/down-then-unreachable"
  t_sb sb_teardown px-sweep 0
  t_equals sweep_teardown_unproved_is_failure 1 "$T_STATUS"
  rm -f "$T_SWEEP_STATE/down-then-unreachable" "$T_SWEEP_STATE/unreachable"
  # Even if the rendered compose.yaml file has been deleted, teardown must still clean up the
  # cluster using the Compose project name alone.
  cp "$T_SWEEP_STATE/provision.txt" "$T_SWEEP_STATE/instances.txt"
  mv "$T_DEPLOY/compose.yaml" "$T_DEPLOY/compose.away"
  t_shim_log_reset
  t_sb sb_teardown px-sweep 0
  t_outcome sweep_sb_teardown_without_the_compose_file 0 ""
  t_called sweep_teardown_names_the_project_only \
    'compose -p px-sweep down --volumes --remove-orphans --timeout 120' raw
  t_contains sweep_teardown_warns_of_the_missing_file 'is gone' "$T_STDERR"
  mv "$T_DEPLOY/compose.away" "$T_DEPLOY/compose.yaml"
  : >"$T_SWEEP_OUT/state/compose/starts-px-sweep.tsv"
  table="$(t_exists "$T_SWEEP_OUT/state/compose/starts-px-sweep.tsv")"
  t_shim_log_reset
  t_sb sb_scope_delete px-sweep
  t_outcome sweep_sb_scope_delete 0 ""
  t_called sweep_scope_delete_removes_the_network 'network rm px-sweep_default'
  t_equals sweep_scope_delete_removes_the_start_table "yes no" \
    "$table $(t_exists "$T_SWEEP_OUT/state/compose/starts-px-sweep.tsv")"
  t_sb sb_scope_exists px-sweep
  t_equals sweep_deleted_scope_is_no_such_object 6 "$T_STATUS"
}

## @description  Sweeps every px-cluster subcommand plus px-run and px-suite on the recorded scope.
## @return       Records assertions. Exit 0.
t_group_cluster() {
  local text record="$T_SWEEP_OUT/state/scope-compose-sweep"
  printf '# verb sweep: every px-cluster subcommand\n'
  t_pc up
  t_equals cluster_up_needs_a_deployment_directory 2 "$T_STATUS"
  t_shim_log_reset
  t_pc up --timeout 5 "$T_DEPLOY"
  t_equals cluster_up 0 "$T_STATUS"
  text="$(t_squeeze "$T_STDOUT")"
  t_contains cluster_up_reports_the_inventory \
    'role index instance_id state ready image_ref node' "$text"
  t_contains cluster_up_leaves_an_on_demand_service_created \
    'nodemanager 0 nm01 pending unready px:selftest localhost' "$text"
  t_contains cluster_up_waits_past_a_stopped_group 'nodemanager 0 stopped' "$text"
  t_equals cluster_up_records_the_scope px-sweep "$(cat "$record" 2>/dev/null)"
  t_equals up_writes_the_cluster_record "yes yes" "$(t_exists \
    "$T_SWEEP_OUT/state/cluster-sweep.env") $(t_flag "PX_BACKEND='compose'" \
    "$(cat "$T_SWEEP_OUT/state/cluster-sweep.env" 2>/dev/null)")"
  # A command executing in a separate process discovers the active scope via the persisted record.
  t_sweep "$T_BASH" "$T_BIN/px-run" --class validation \
    "$T_WORK/drivers/exit-code.sh" \
    -- 0
  t_equals run_resolves_the_recorded_scope 0 "$T_STATUS"
  t_contains run_snapshots_the_recorded_scope "$(t_rows 4 instance pre master 0)" \
    "$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  t_shim_log_reset
  t_sweep PX_SESSION_ID=selftest-requires "$T_BASH" "$T_BIN/px-suite" \
    "$T_WORK/suites/requires.suite"
  t_equals suite_requirement_runs_with_its_group 0 "$T_STATUS"
  t_called suite_requirement_starts_the_group 'compose -p px-sweep start nodemanager'
  t_called suite_requirement_stops_the_group_after_the_test \
    'compose -p px-sweep stop --timeout 120 nodemanager'
  t_equals session_leaves_the_cluster_up "0 yes" \
    "$(t_calls 'compose -p px-sweep down') $(t_exists "$record")"
  t_pc status
  text="$(t_squeeze "$T_STDOUT")"
  t_equals cluster_status 0 "$T_STATUS"
  t_contains cluster_status_reports_an_instance \
    'regionserver 1 rs02 running ready px:selftest localhost' "$text"
  t_pc group status yarn
  t_contains cluster_status_reports_a_group 'yarn 0 1 down' "$(t_squeeze "$T_STDOUT")"
  t_pc ready --timeout 5
  t_equals cluster_ready 0 "$T_STATUS"
  t_contains cluster_ready_reports_the_stopped_group 'nodemanager 0 stopped' \
    "$(t_squeeze "$T_STDOUT")"
  t_pc ready --timeout 1 nosuch
  t_equals cluster_ready_on_an_unknown_role_is_no_such_object 6 "$T_STATUS"
  t_pc config
  t_equals cluster_config 0 "$T_STATUS"
  t_contains cluster_config_reports_the_cluster "PX_CLUSTER_ID${PX_TAB}sweep" "$T_STDOUT"
  t_pc logs regionserver 0
  t_outcome cluster_logs 0 "$(printf 'fake log out rs01\nfake log err rs01')"
  t_pc logs --previous regionserver 0
  t_equals cluster_logs_previous_is_unsupported 5 "$T_STATUS"
  t_pc exec regionserver 0 -- sh -c 'printf "%s\n" cluster-exec; exit 7'
  t_outcome cluster_exec_passes_the_status_through 7 cluster-exec
  t_equals cli_exec_status_passes_without_exit_line 0 "$(printf '%s\n' "$T_STDERR" |
    grep -c 'ends (exit ')"
  t_pc endpoint master 0 ui
  t_outcome cluster_endpoint 0 "$(t_rows 2 127.0.0.1 1)"
  t_pc endpoint master 0 nosuch
  t_equals cluster_endpoint_unknown_port_is_no_such_object 6 "$T_STATUS"
  t_pc endpoint --release master 0 ui
  t_equals cluster_endpoint_release 0 "$T_STATUS"
  t_group_cluster_faults
}

## @description  Sweeps the instance-mutating px-cluster subcommands and failed up handling.
## @return       Records assertions. Exit 0.
t_group_cluster_faults() {
  local text record="$T_SWEEP_OUT/state/scope-compose-sweep"
  t_pc group start yarn --timeout 5
  t_equals cluster_group_start 0 "$T_STATUS"
  t_contains cluster_group_start_reports_ready 'nodemanager 0 ready' \
    "$(t_squeeze "$T_STDOUT")"
  t_contains cluster_group_start_runs_the_memory_check \
    "memory_fit${PX_TAB}ok${PX_TAB}the limits 2560 MiB" "$T_STDERR"
  t_contains cluster_group_start_is_planned \
    "$(t_rows 4 group_start nodemanager '' 'group yarn')" "$(t_sweep_planned)"
  t_pc group status yarn
  t_contains cluster_group_status 'yarn 1 1 up' "$(t_squeeze "$T_STDOUT")"
  t_pc group stop yarn --timeout 5
  t_equals cluster_group_stop 0 "$T_STATUS"
  t_contains cluster_group_stop_reports_stopped 'nodemanager 0 stopped' \
    "$(t_squeeze "$T_STDOUT")"
  t_pc group start nogroup --timeout 5
  t_equals cluster_group_unknown_is_usage 2 "$T_STATUS"
  t_pc restart regionserver 0 -e PX_TIMEOUT_DRAIN=10 --timeout 5
  t_equals cluster_restart 0 "$T_STATUS"
  t_contains cluster_restart_reports_the_rebalance 'rebalance ok' \
    "$(t_squeeze "$T_STDOUT")"
  t_contains cluster_restart_is_planned "$(t_rows 4 restart regionserver 0 'drain 10s')" \
    "$(t_sweep_planned)"
  t_pc restart master 0
  t_equals cluster_restart_without_drain_label_is_usage 2 "$T_STATUS"
  t_pc signal master 0 QUIT
  t_equals cluster_signal 0 "$T_STATUS"
  t_contains cluster_signal_is_planned "$(t_rows 4 signal master 0 'signal QUIT')" \
    "$(t_sweep_planned)"
  # Because this hand-written deployment was not produced by the generator with per-instance
  # RegionServer services, re-rendering for a scale operation alters other instances; the scale is
  # refused and the deployment directory is restored unchanged. The a2 cluster test group exercises
  # scaling against a rendered deployment.
  text="$(cat "$T_DEPLOY/compose.yaml")"
  t_pc scale regionserver 3 --timeout 10
  t_equals cluster_scale_of_a_hand_written_deployment_is_refused 2 "$T_STATUS"
  t_equals cluster_refused_scale_keeps_the_deployment "$text" \
    "$(cat "$T_DEPLOY/compose.yaml" 2>/dev/null)"
  t_absent cluster_refused_scale_is_not_planned 'count 3' "$(t_sweep_planned)"
  printf '%s|%s|%s|%s|%s|%s|%s|%s||||regionmover\n' rs03 regionserver \
    px-sweep-regionserver-3 running 'Up 1 second (healthy)' px:selftest regionserver 3 \
    >>"$T_SWEEP_STATE/instances.txt"
  t_pc kill regionserver 2
  t_equals cluster_kill 0 "$T_STATUS"
  t_contains cluster_kill_is_planned "$(t_rows 4 kill regionserver 2 'mode hard')" \
    "$(t_sweep_planned)"
  t_pc kill regionserver 2 --mode gentle
  t_equals cluster_kill_unknown_mode_is_usage 2 "$T_STATUS"
  t_shim_log_reset
  t_pc down
  t_equals cluster_down 0 "$T_STATUS"
  t_called cluster_down_removes_the_volumes \
    'compose -p px-sweep down --volumes --remove-orphans --timeout 120'
  t_equals cluster_down_removes_the_scope_record no "$(t_exists "$record")"
  t_equals down_removes_the_record no "$(t_exists "$T_SWEEP_OUT/state/cluster-sweep.env")"
  t_shim_log_reset
  t_pc down
  t_equals cluster_down_without_a_record_is_ok "0 yes" "$T_STATUS $(t_flag \
    'no cluster sweep is recorded' "$T_STDERR")"
  t_equals down_without_a_record_calls_no_teardown 0 "$(t_calls ' down ')"
  # A failed px-cluster up tears down the partially created cluster by default, whereas passing
  # --keep preserves the scope record for post-mortem inspection.
  : >"$T_SWEEP_STATE/up-fails"
  t_shim_log_reset
  t_pc up "$T_DEPLOY"
  t_equals cluster_up_failure_is_failure 1 "$T_STATUS"
  t_called cluster_up_failure_tears_down \
    'compose -p px-sweep down --volumes --remove-orphans --timeout 120'
  t_equals cluster_up_failure_removes_the_scope_record no "$(t_exists "$record")"
  t_equals failed_up_removes_the_record no "$(t_exists "$T_SWEEP_OUT/state/cluster-sweep.env")"
  t_pc --keep up "$T_DEPLOY"
  t_equals cluster_up_failure_with_keep_is_failure 1 "$T_STATUS"
  t_equals cluster_up_failure_with_keep_keeps_the_scope yes "$(t_exists "$record")"
  t_equals failed_up_with_keep_keeps_the_record yes \
    "$(t_exists "$T_SWEEP_OUT/state/cluster-sweep.env")"
  rm -f "$T_SWEEP_STATE/up-fails"
  t_pc down
  t_equals cluster_down_after_a_kept_failure 0 "$T_STATUS"
  text="$(find "$T_SWEEP_OUT/state" -name 'scope-*' -o -name 'cluster-*.env' 2>/dev/null)"
  t_empty cluster_down_leaves_no_scope_record "$text"
  # R-057: After teardown completes, the runtime retains no container, network, or volume belonging
  # to the cluster, allowing the single-cluster admission gate to permit the next bring-up.
  t_equals teardown_leaves_no_labelled_object "0 0 0" "$(grep -c . \
    "$T_SWEEP_STATE/instances.txt") $(grep -c . "$T_SWEEP_STATE/networks.txt") $(grep -c . \
    "$T_SWEEP_STATE/volumes.txt")"
}

## @description  Verifies that no command created any path under the working tree root.
## @return       Records assertions. Exit 0.
t_group_tree() {
  printf '# no write under the repository root\n'
  t_tree_paths "$T_WORK/scan" >"$T_WORK/fixture-before.txt"
  : >"$T_WORK/scan/lib/new-file.sh"
  t_tree_paths "$T_WORK/scan" >"$T_WORK/fixture-after.txt"
  t_equals tree_check_reports_a_new_path "$T_WORK/scan/lib/new-file.sh" \
    "$(t_tree_added "$T_WORK/fixture-before.txt" "$T_WORK/fixture-after.txt")"
  if [ -z "$PX_TREE_ROOT" ]; then
    t_skip no_write_under_the_repository_root "this harness sits in no working tree"
    return 0
  fi
  t_tree_paths "$PX_TREE_ROOT" >"$T_WORK/tree-after.txt"
  t_empty no_write_under_the_repository_root \
    "$(t_tree_added "$T_WORK/tree-before.txt" "$T_WORK/tree-after.txt")"
}

# ---------------------------------------------------------------------------
# Deployment layer verification: generator, image, preflight, distributions, and bring-up
# ---------------------------------------------------------------------------
# These test groups verify the deployment layer exclusively against fake dependencies: the fake
# container runtime, a fake curl utility, a fake Maven executable, and fake JDK installations.

## @description  Extracts and sorts the published ports of a Compose file, one per line, reporting
##               any port mapping that does not match "127.0.0.1:P:P" as "refused <entry>".
## @return       Writes the sorted port entries to stdout. Exit 0.
t_published() {
  awk '/^    ports:$/ { p = 1; next }
    p && /^      - / {
      e = $2; gsub(/"/, "", e); n = split(e, f, ":")
      if (n == 3 && f[1] == "127.0.0.1" && f[2] == f[3] && f[2] ~ /^[0-9]+$/) print f[2]
      else print "refused " e
      next
    }
    { p = 0 }' "$1" | sort
}

## @description  Verifies the network topology of a rendered deployment (directory, cluster): one
##               bridge network, every service on it under its hostname, and loopback-only ports.
## @return       Records the network topology assertions. Exit 0.
t_a2_network_cases() {
  local dir=$1 cluster=$2 service block bad="" want
  local yaml="$dir/compose.yaml"
  t_absent "network_${cluster}_no_host_networking" 'network_mode' "$(cat "$yaml")"
  want="networks:${PX_NL}  default:${PX_NL}    driver: bridge${PX_NL}    labels:${PX_NL}"
  want="$want      px.harness.cluster: \"$cluster\""
  t_equals "network_${cluster}_one_labelled_bridge" "$want" \
    "$(sed -n '/^networks:/,/^volumes:/p' "$yaml" | grep -v '^volumes:')"
  for service in $(grep -o '^  [a-z-]*-[0-9]:$' "$yaml" | tr -d ' :'); do
    block="$(sed -n "/^  $service:\$/,/^  [a-z][a-z-]*-[0-9]:\$/p" "$yaml")"
    case "$block" in
    *"    hostname: \"$service\"$PX_NL    networks:$PX_NL      - default$PX_NL"*) ;;
    *) bad="$bad $service" ;;
    esac
    case "$block" in
    *"PX_HOSTNAME: \"$service\""*) ;;
    *) bad="$bad $service:PX_HOSTNAME" ;;
    esac
  done
  t_empty "network_${cluster}_every_service_has_its_hostname" "$bad"
  # Requirement R-032: ensure that no service port is published beyond the host loopback address.
  t_absent "network_${cluster}_loopback_only_publish" refused "$(t_published "$yaml")"
  t_absent "network_${cluster}_no_wildcard_in_compose" '0.0.0.0' "$(cat "$yaml")"
  t_equals "network_${cluster}_port_map_is_the_published_set" \
    "$(sed -n 's/^PX_PORT_[A-Z0-9_]*=//p' "$dir/ports.generated.env" | sort)" \
    "$(t_published "$yaml")"
}

## @description  Verifies the deployment generator, its fail-closed rendering behavior, and its
##               target directory rules.
## @return       Records the deployment generator assertions. Exit 0.
t_group_a2_generator() {
  local dir text rows count bad="" miss="" line
  local versions="$PX_HOME/$PX_DEPLOY_SUBDIR/versions.env"
  local tag=px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6
  printf '# the deployment generator\n'
  t_shim_log_reset
  t_gen --class measurement --cluster m
  t_equals generator_renders_the_measurement_class 0 "$T_STATUS"
  dir="$T_A2_OUT/deploy/m"
  t_equals generator_writes_the_directory_path "$dir" "$T_STDOUT"
  count="$(grep -c '^  [a-z-]*-[0-9]:$' "$dir/compose.yaml" 2>/dev/null)"
  t_equals compose_one_service_per_instance 18 "$count"
  # Verify that a healthcheck gates every service, including the one-shot setup service.
  for line in 'restart: "no"' 'pull_policy: never' 'healthcheck:' "image: \"$tag\""; do
    [ "$(grep -cxF "    $line" "$dir/compose.yaml")" = "$count" ] || miss="$miss $line;"
  done
  t_empty compose_policies_on_every_service "$miss"
  t_a2_network_cases "$dir" m
  t_absent debug_port_unpublished_by_default _DEBUG "$(cat "$dir/ports.generated.env")"
  t_equals bind_host_is_every_interface 0.0.0.0 \
    "$(t_env_value "$dir/topology.env" PX_BIND_HOST)"
  t_absent service_host_is_gone PX_SERVICE_HOST "$(cat "$dir/topology.env")"
  t_equals default_counts "3 3 3" \
    "$(t_env_value "$dir/topology.env" PX_DN_COUNT PX_NM_COUNT PX_RS_COUNT)"
  # The topology record captures the selected version set, while the jar versions bundled inside
  # the HBase archive remain unknown until the image artifacts are staged.
  line="$(t_env_value "$versions" PX_PHOENIX_HBASE_PROFILE_DEFAULT PX_DIST_HBASE_DEFAULT_2_6 \
    PX_DIST_HADOOP_DEFAULT)"
  t_equals topology_records_the_version_set "$line unstaged unstaged" \
    "$(t_env_value "$dir/topology.env" PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION \
      PX_DIST_HADOOP_VERSION PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION)"
  t_equals compose_one_service_per_regionserver 3 "$(grep -c '^  regionserver-[0-9]:$' \
    "$dir/compose.yaml")"
  t_empty generator_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  t_contains setup_mounts_the_namenode_volume '- namenode-0-data:/data' \
    "$(sed -n '/^  setup-0:/,/^[a-z]/p' "$dir/compose.yaml")"
  # The bootstrap probe reads the health monitor heartbeat through a read-only bind mount of the
  # state directory and accepts a maximum heartbeat age of three monitor intervals.
  text="$(sed -n '/^  setup-0:/,/^[a-z]/p' "$dir/compose.yaml")"
  t_equals setup_reads_the_monitor_heartbeat "yes yes yes" \
    "$(t_flag 'PX_MONITOR_HEARTBEATS: "/var/lib/px/state/monitor-px-m.heartbeat"' "$text") $(
      t_flag "PX_MONITOR_STALE_S: \"$((3 * PX_MONITOR_INTERVAL_S))\"" "$text") $(
      t_flag "\"$(px_realpath "$T_A2_OUT")/state:/var/lib/px/state:ro\"" "$text")"
  t_absent only_setup_reads_the_monitor_heartbeat /var/lib/px/state \
    "$(sed '/^  setup-0:/,/^[a-z]/d' "$dir/compose.yaml")"
  t_gen --class validation --cluster hb -e PX_MONITOR_INTERVAL_S=20
  t_equals heartbeat_age_is_three_monitor_intervals "0 yes" "$T_STATUS $(
    t_flag 'PX_MONITOR_STALE_S: "60"' "$(cat "$T_A2_OUT/deploy/hb/compose.yaml")")"
  rm -rf "$T_A2_OUT/deploy/hb"
  # Because a ':' character in the state directory path would prematurely terminate the host-path
  # field of the setup volume mount, deployment rendering rejects colons in both PX_STATE_DIR and
  # PX_OUT.
  t_gen --class validation --cluster colon -e PX_STATE_DIR=st:ate
  t_equals state_dir_with_a_colon_is_refused "2 yes no" "$T_STATUS $(t_flag \
    'because the setup role mounts it' "$T_STDERR") $(t_exists "$T_A2_OUT/deploy/colon")"
  mkdir -p "$T_A2/o:ut"
  t_a2 "PX_OUT=$T_A2/o:ut" "$T_BASH" "$T_GEN" --class validation --cluster colon
  t_equals output_root_with_a_colon_is_refused "2 yes" "$T_STATUS $(t_flag \
    'because the setup role mounts it' "$T_STDERR")"
  t_empty output_root_with_a_colon_gets_no_file "$(find "$T_A2/o:ut" ! -type d)"
  t_gen --class validation --cluster int0 -e PX_MONITOR_INTERVAL_S=0
  t_equals monitor_interval_of_zero_is_refused "2 no" \
    "$T_STATUS $(t_exists "$T_A2_OUT/deploy/int0")"
  t_equals volumes_are_declared_once_by_their_owner 6 \
    "$(sed -n '/^volumes:/,$p' "$dir/compose.yaml" | grep -c '^  [a-z0-9-]*-data:$')"
  rows="$(awk '/^  [a-z-]+-[0-9]:$/ { s = $1 } /px.harness.(group|start|drain):/ \
    { print s, $1, $2 }' "$dir/compose.yaml")"
  t_equals yarn_labels_group 5 "$(printf '%s\n' "$rows" | grep -c 'group: "yarn"')"
  t_equals yarn_labels_on_demand 5 "$(printf '%s\n' "$rows" | grep -c 'start: "on-demand"')"
  t_empty yarn_labels_only_on_yarn_roles "$(printf '%s\n' "$rows" | grep -E 'group|start' |
    grep -vE '^(resourcemanager|nodemanager|jobhistory)-')"
  t_equals drain_label_on_every_regionserver 3 \
    "$(printf '%s\n' "$rows" | grep -c '^regionserver-[0-9]: px.harness.drain: "regionmover"')"
  t_equals drain_label_only_on_regionservers 3 "$(printf '%s\n' "$rows" | grep -c drain)"
  t_equals yarn_group_record yarn "$(t_env_value "$dir/topology.env" PX_GROUPS)"
  t_equals yarn_group_roles 'resourcemanager nodemanager jobhistory' \
    "$(t_env_value "$dir/topology.env" PX_GROUP_YARN)"
  t_gen --class measurement --check
  t_equals memory_sums_with_yarn 41984 "$(t_mem_sum "$T_STDOUT")"
  t_contains memory_sums_regionserver_is_4608_around_3072 \
    "$(t_rows 9 regionserver-0 regionserver 0 '' '' 4608 3072 512 17)" "$T_STDOUT"
  t_gen --class measurement --check -e PX_YARN=0
  t_equals memory_sums_without_yarn 31744 "$(t_mem_sum "$T_STDOUT")"
  t_absent yarn_toggle_off_renders_no_yarn_role 'resourcemanager' "$T_STDOUT"
  t_equals memory_sums_topology_record "41984 31744 10240" "$(t_env_value \
    "$dir/topology.env" PX_TOPOLOGY_MEM_MIB PX_TOPOLOGY_MEM_EAGER_MIB PX_GROUP_YARN_MEM_MIB)"
  t_gen --class measurement --check -e PX_SNN=0
  t_absent snn_toggle_off_renders_no_secondary_namenode 'secondarynamenode' "$T_STDOUT"
  t_gen --class validation --cluster v
  t_equals validation_class 0 "$T_STATUS"
  dir="$T_A2_OUT/deploy/v"
  t_equals validation_class_services 8 "$(grep -c '^  [a-z-]*-[0-9]:$' "$dir/compose.yaml")"
  t_equals validation_class_one_per_role "1 1 1 1" "$(t_env_value "$dir/topology.env" \
    PX_DN_COUNT PX_NM_COUNT PX_RS_COUNT PX_HDFS_REPLICATION)"
  t_absent validation_class_renders_no_yarn 'nodemanager' "$(cat "$dir/services.tsv")"
  t_gen --class validation --check
  t_equals validation_class_fits_16_gib 8704 "$(t_mem_sum "$T_STDOUT")"
  t_gen --class validation --check -e PX_YARN=1
  t_equals validation_class_with_yarn_on_request 12032 "$(t_mem_sum "$T_STDOUT")"
  t_gen --class measurement --config "$T_ENVD/override-24gib.env" --check
  t_equals override_24gib 19200 "$(t_mem_sum "$T_STDOUT")"
  t_gen --class measurement --config "$T_ENVD/override-24gib.env" --check -e PX_RS_COUNT=1
  t_equals override_24gib_one_regionserver 15616 "$(t_mem_sum "$T_STDOUT")"
  t_gen --class measurement --config "$T_ENVD/override-24gib.env" --check -e PX_RS_COUNT=3 \
    -e PX_YARN=0
  t_equals override_24gib_three_regionservers_without_yarn 19456 "$(t_mem_sum "$T_STDOUT")"
  t_contains override_24gib_is_advisory 'TIME RESULTS FROM A RUN WITH THIS FILE ARE ADVISORY' \
    "$(sed -n 1,20p "$T_ENVD/override-24gib.env")"
  t_gen --class measurement --check -e PX_RS_COUNT=1
  t_equals count_one_renders 0 "$T_STATUS"
  # Render the maximum port map configuration: nine instances of each scalable role with JVM debug
  # ports enabled.
  t_gen --class measurement --cluster nine -e PX_RS_COUNT=9 -e PX_DN_COUNT=9 -e PX_NM_COUNT=9 \
    -e PX_JVM_DEBUG=1
  t_equals count_nine_renders 0 "$T_STATUS"
  dir="$T_A2_OUT/deploy/nine"
  t_equals port_map_nine 131 "$(grep -c '^PX_PORT_' "$dir/ports.generated.env")"
  t_equals debug_switch_reaches_every_service 1 \
    "$(t_env_value "$dir/topology.env" PX_JVM_DEBUG)"
  t_a2_network_cases "$dir" nine
  t_equals port_map_nine_regionserver_debug "$(seq 15011 15019 | tr '\n' ' ')" \
    "$(sed -n 's/^PX_PORT_REGIONSERVER_DEBUG_[0-9]=//p' "$dir/ports.generated.env" |
      sort -n | tr '\n' ' ')"
  t_equals port_map_nine_datanode_debug "$(seq 15021 15029 | tr '\n' ' ')" \
    "$(sed -n 's/^PX_PORT_DATANODE_DEBUG_[0-9]=//p' "$dir/ports.generated.env" |
      sort -n | tr '\n' ' ')"
  t_equals port_map_nine_nodemanager_debug "$(seq 15031 15039 | tr '\n' ' ')" \
    "$(sed -n 's/^PX_PORT_NODEMANAGER_DEBUG_[0-9]=//p' "$dir/ports.generated.env" |
      sort -n | tr '\n' ' ')"
  t_empty port_map_nine_has_no_collision "$(grep '^PX_PORT_' "$dir/ports.generated.env" |
    cut -d= -f2 | sort | uniq -d)"
  t_equals port_map_base_plus_step 16040 \
    "$(t_env_value "$dir/ports.generated.env" PX_PORT_REGIONSERVER_RPC_2)"
  t_equals port_map_jobhistory_admin 10033 \
    "$(t_env_value "$dir/ports.generated.env" PX_PORT_JOBHISTORY_ADMIN)"
  # The final iteration preserves its standard error output for the two assertions following the
  # loop.
  for line in PX_RS_COUNT=0 PX_DN_COUNT=10 PX_NM_COUNT=abc PX_RS_COUNT=10; do
    t_gen --class measurement --check -e "$line"
    [ "$T_STATUS" = 2 ] || bad="$bad$line gave $T_STATUS$PX_NL"
  done
  t_empty count_range_exits_2 "$bad"
  t_contains count_range_names_the_range 'outside the range 1 to 9' "$T_STDERR"
  t_contains count_range_cites_the_ceiling 'ceiling' "$T_STDERR"
  t_gen --check
  t_contains unknown_class_needs_a_class 'name the run class' "$T_STDERR"
  t_gen --class bogus --check
  t_outcome unknown_class_exits_2 2 ''
  t_gen --class measurement --cluster rep1 -e PX_DN_COUNT=1
  t_equals replication_clamp_to_the_datanode_count "1 3" "$(t_env_value \
    "$T_A2_OUT/deploy/rep1/topology.env" PX_HDFS_REPLICATION PX_HDFS_REPLICATION_REQUESTED)"
  t_contains replication_clamp_warns 'the deployment uses replication 1' "$T_STDERR"
  t_gen --class measurement --cluster rep2 -e PX_HDFS_REPLICATION=2
  t_equals replication_below_the_count_is_kept 2 \
    "$(t_env_value "$T_A2_OUT/deploy/rep2/topology.env" PX_HDFS_REPLICATION)"
  t_gen --class measurement --cluster jdk -e PX_RS_JDK=17 -e PX_CLIENT_JDK=21
  text="$(awk '/^  [a-z-]+-[0-9]:$/ { s = $1 } /PX_JDK:/ { print s, $2 }' \
    "$T_A2_OUT/deploy/jdk/compose.yaml")"
  t_contains per_role_jdk_client 'client-0: "21"' "$text"
  t_contains per_role_jdk_regionserver 'regionserver-0: "17"' "$text"
  t_gen --class measurement --check -e PX_CLIENT_JDK=11
  t_equals per_role_jdk_accepts_11 0 "$T_STATUS"
  t_gen --class measurement --check -e PX_CLIENT_JDK=8
  t_contains per_role_jdk_refuses_8 'PX_CLIENT_JDK' "$T_STDERR"
  t_gen --class measurement --check -e PX_RS_JDK=25
  t_equals per_role_jdk_refuses_25 2 "$T_STATUS"
  t_gen --class measurement --out fail1 -e PX_RS_HEAP_MIB=
  t_equals fail_closed_unset_exits_2 2 "$T_STATUS"
  t_contains fail_closed_unset_names_the_variable PX_RS_HEAP_MIB "$T_STDERR"
  t_equals fail_closed_unset_writes_no_file no "$(t_exists "$T_A2_OUT/fail1")"
  # Exercise a failing plug-in hook, which executes after the staging directory has been created.
  mkdir -p "$T_A2/noimage/hooks"
  printf '%s\n' PX_PLUGIN_ID=noimage PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=image-check \
    >"$T_A2/noimage/plugin.env"
  printf '#!/usr/bin/env bash\nexit 1\n' >"$T_A2/noimage/hooks/image-check"
  chmod +x "$T_A2/noimage/hooks/image-check"
  t_gen --class measurement --out fail2 -e "PX_PLUGIN_DIRS=$T_A2/noimage" -e PX_PLUGINS=noimage
  t_equals fail_closed_leaves_no_staging_directory "2 no " "$T_STATUS $(t_exists \
    "$T_A2_OUT/fail2") $(find "$T_A2_OUT" -name '.*.render.*' 2>/dev/null)"
  t_gen --class measurement --check -e PX_RS_HEAP_MIB=4608
  t_contains heap_bound_names_the_role 'role regionserver' "$T_STDERR"
  t_gen --class measurement --check -e PX_NM_MEM_MIB=2176
  t_contains heap_bound_counts_the_yarn_memory 'role nodemanager' "$T_STDERR"
  t_gen --class measurement --check -e PX_RS_HEAP_MIB=2048
  t_equals heap_bound_admits_a_valid_override 0 "$T_STATUS"
  t_gen --class measurement --check -e PX_HBASE_MEMSTORE_SIZE=0.55
  t_contains hbase_fractions_bound 'exceeds 0.8' "$T_STDERR"
  # Verify that any image tag naming a registry host is rejected, whether or not it includes a
  # port number.
  miss=""
  for line in reg.io:5000/x:t ghcr.io/x:t docker.io/library/x:t localhost/x:t; do
    t_gen --class measurement --check -e "PX_IMAGE_TAG=$line"
    [ "$T_STATUS" = 2 ] || miss="$miss $line gave $T_STATUS"
  done
  t_empty image_tag_grammar "$miss"
  t_gen --class measurement --out d1
  t_gen --class measurement --out d2
  t_empty deterministic "$(diff -r "$T_A2_OUT/d1" "$T_A2_OUT/d2" 2>&1)"
  cp -R "$T_A2_OUT/d1" "$T_A2/d1-copy"
  t_gen --class measurement --out d1
  t_empty deterministic_rerender "$(diff -r "$T_A2_OUT/d1" "$T_A2/d1-copy" 2>&1)"
  mkdir -p "$T_A2_OUT/foreign"
  printf 'keep\n' >"$T_A2_OUT/foreign/keep.txt"
  t_gen --class measurement --out foreign
  t_equals refuse_foreign_dir 2 "$T_STATUS"
  t_equals refuse_foreign_dir_keeps_the_file keep "$(cat "$T_A2_OUT/foreign/keep.txt")"
  mkdir -p "$T_A2_OUT/empty"
  t_gen --class measurement --out empty
  t_equals render_into_an_empty_dir 0 "$T_STATUS"
  t_gen --class measurement --out .
  t_equals refuse_the_output_root 2 "$T_STATUS"
  t_gen --class measurement --out "$T_A2/outside"
  t_equals refuse_a_dir_outside_the_output_root 2 "$T_STATUS"
  t_gen --class measurement --out dry -e PX_DRY_RUN=1
  t_equals dry_run_writes_nothing "0 no" "$T_STATUS $(t_exists "$T_A2_OUT/dry")"
  t_gen --class measurement --out checked --check
  t_equals check_writes_nothing no "$(t_exists "$T_A2_OUT/checked")"
  # Verify that the configuration loader reads the generated port map from a rendered deployment
  # directory as precedence stage 8.
  t_a2 "PX_DEPLOYMENT_DIR=$T_A2_OUT/deploy/m" "$T_BASH" "$T_BIN/px-cluster" config
  t_equals loader_reads_port_map "16030${PX_TAB}ports" \
    "$(printf '%s\n' "$T_STDOUT" | grep "^PX_PORT_REGIONSERVER_RPC_1$PX_TAB" | cut -f 2-3)"
  bad=""
  for line in class-measurement.env class-validation.env override-24gib.env; do
    bad="$bad$(grep -vE '^(#.*)?$' "$T_ENVD/$line" | grep -vE '^PX_[A-Z0-9_]+=[^ ]*$' |
      sed "s|^|$line: |")"
  done
  t_empty class_files_assignments_only "$bad"
  t_equals class_files_declare_the_same_names \
    "$(grep -oE '^PX_[A-Z0-9_]+' "$T_ENVD/class-measurement.env" | sort | tr '\n' ' ')" \
    "$(grep -oE '^PX_[A-Z0-9_]+' "$T_ENVD/class-validation.env" | sort | tr '\n' ' ')"
  t_gen --strict --class measurement --check
  t_equals strict_mode_measurement 0 "$T_STATUS"
  t_gen --strict --class validation --check --config "$T_ENVD/override-24gib.env"
  t_equals strict_mode_validation_and_override 0 "$T_STATUS"
  # A re-pinned component release renders with a distinct default image tag so that different
  # version sets never share a container image.
  t_gen --class validation --cluster pin -e PX_DIST_HBASE_VERSION=2.6.6
  t_equals version_set_repin_has_its_own_tag \
    "0 px-harness:hbase-2.6.6-hadoop-3.4.3-profile-2.6" \
    "$T_STATUS $(t_env_value "$T_A2_OUT/deploy/pin/topology.env" PX_IMAGE_TAG)"
  # Two distinct configuration files render two independent deployments, each carrying its own
  # image tag and JDK selection. The t_group_a2_matrix test group executes such files as a version
  # matrix.
  printf 'PX_IMAGE_TAG=px-harness:set-a\n' >"$T_A2/set-a.env"
  printf 'PX_IMAGE_TAG=px-harness:set-b\nPX_CLIENT_JDK=21\n' >"$T_A2/set-b.env"
  t_gen --class validation --cluster seta --config "$T_A2/set-a.env"
  t_gen --class validation --cluster setb --config "$T_A2/set-b.env"
  t_equals config_files_record_their_image_tag "px-harness:set-a px-harness:set-b" \
    "$(t_env_value "$T_A2_OUT/deploy/seta/topology.env" PX_IMAGE_TAG) $(t_env_value \
      "$T_A2_OUT/deploy/setb/topology.env" PX_IMAGE_TAG)"
  t_contains config_file_sets_the_jdk 'PX_JDK: "21"' \
    "$(sed -n '/^  client-0:/,/^  [a-z]/p' "$T_A2_OUT/deploy/setb/compose.yaml")"
}

## @description  Verifies the unified container image: its Dockerfile definition, a launcher branch
##               for every role, and the rendered configuration for each service.
## @return       Records the container image assertions. Exit 0.
t_group_a2_image() {
  local dir="$T_A2_OUT/deploy/m" out="$T_A2/render" bad="" wild="" https="" service role text line
  local d='$' props='([aA]ddress|bind-host|hostname|quorum|defaultFS|rootdir)'
  local hosts='0\.0\.0\.0|(zookeeper|namenode|secondarynamenode|datanode|resourcemanager'
  hosts="$hosts|nodemanager|jobhistory|hbase-master|regionserver|client|tooling|setup)-[0-9]"
  printf '# the image\n'
  t_equals one_image_all_roles "$T_IMG/Dockerfile" \
    "$(find "$T_IMG" -name 'Dockerfile*' 2>/dev/null)"
  for role in zookeeper namenode secondarynamenode datanode resourcemanager nodemanager \
    jobhistory hbase-master regionserver client tooling setup; do
    grep -qE "^  ([a-z-]+ \| )*$role( \| [a-z-]+)*\)" "$T_IMG/entrypoint.sh" ||
      bad="$bad $role"
  done
  t_empty one_image_all_roles_has_a_launcher "$bad"
  bad="$(grep '^FROM ' "$T_IMG/Dockerfile" | grep -v '@sha256:')"
  t_empty dockerfile_args_and_digests "$bad"
  t_equals dockerfile_is_multi_stage 4 "$(grep -c '^FROM ' "$T_IMG/Dockerfile")"
  bad=""
  for role in PX_IMAGE_BASE_JDK11 PX_IMAGE_BASE_JDK17 PX_IMAGE_BASE_JDK21 \
    PX_IMAGE_BASE_JDK11_SHA256 PX_IMAGE_BASE_JDK17_SHA256 PX_IMAGE_BASE_JDK21_SHA256 \
    PX_UID PX_GID PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION PX_PHOENIX_HBASE_PROFILE; do
    grep -q "^ARG $role\$" "$T_IMG/Dockerfile" || bad="$bad $role"
  done
  t_empty dockerfile_declares_the_build_arguments "$bad"
  t_contains dockerfile_amd64_best_effort 'amd64 is best effort' "$(cat "$T_IMG/Dockerfile")"
  t_contains dockerfile_arm64_primary 'arm64, the primary architecture' \
    "$(cat "$T_IMG/Dockerfile")"
  # Verify that commands executed via sb_exec also resolve the rendered configuration directory
  # through the container environment.
  bad=""
  for role in HADOOP_CONF_DIR YARN_CONF_DIR HBASE_CONF_DIR; do
    grep -qE "^(ENV +| +)$role=\\$\\{PX_CONF_DIR\\}( |$)" "$T_IMG/Dockerfile" ||
      bad="$bad $role"
  done
  t_empty dockerfile_exec_reads_the_rendered_conf "$bad"
  t_contains arm64_documented_as_primary 'arm64 is the primary architecture' \
    "$(cat "$PX_HOME/docs/backends.md")"
  # Verify that every service renders its configuration templates with no unexpanded placeholders
  # remaining. Every host referenced in an address, bind, or hostname property must be either a
  # peer hostname of the form <role>-<index> or the bind address 0.0.0.0, and no rendered file may
  # reference the loopback address.
  bad=""
  for service in $(grep -o '^  [a-z-]*-[0-9]:' "$dir/compose.yaml" | tr -d ' :'); do
    t_render "$dir" "$service"
    if [ "$T_STATUS" != 0 ]; then
      bad="$bad$service exit $T_STATUS: $T_STDERR$PX_NL"
    elif grep -rqF -e "$d{" -e "${d}PX_" "$out" 2>/dev/null; then
      bad="$bad$service: a placeholder remains$PX_NL"
    fi
    wild="$wild$(cat "$out"/*-site.xml 2>/dev/null | tr -d ' \n' |
      grep -oE "<name>[^<]*$props</name><value>[^<]*</value>" |
      sed 's|.*<value>\(hdfs://\)\{0,1\}\([^:/<]*\).*|\2|' | grep -vxE "$hosts" |
      sed "s|^|$service |")"
    wild="$wild$(grep -rlE '127\.0\.0\.1|localhost' "$out" 2>/dev/null)"
    # Enabling an HTTPS policy or SSL toggle would activate the default HTTPS wildcard listeners.
    https="$https$(cat "$out"/*-site.xml 2>/dev/null | tr -d ' \n' |
      grep -oE '<name>[^<]*(http\.policy|ssl\.enabled)</name><value>[^<]*</value>' |
      grep -vE '<value>(HTTP_ONLY|false)</value>')"
  done
  t_empty render_every_role "$bad"
  t_empty render_hosts_are_peer_hostnames_or_binds "$wild"
  t_empty render_no_https_policy "$https"
  t_render "$dir" jobhistory-0
  text="$(tr -d ' \n' <"$out/mapred-site.xml" 2>/dev/null)"
  t_contains render_jobhistory_admin_by_hostname \
    '<name>mapreduce.jobhistory.admin.address</name><value>jobhistory-0:10033</value>' "$text"
  t_contains render_jobhistory_binds_every_interface \
    '<name>mapreduce.jobhistory.bind-host</name><value>0.0.0.0</value>' "$text"
  t_contains render_jobhistory_reaches_hdfs_by_hostname \
    '<name>fs.defaultFS</name><value>hdfs://namenode-0:8020</value>' \
    "$(tr -d ' \n' <"$out/core-site.xml" 2>/dev/null)"
  t_render "$dir" nodemanager-1
  text="$(tr -d ' \n' <"$out/yarn-site.xml" 2>/dev/null)"
  for line in yarn.resourcemanager.address:resourcemanager-0:8032 \
    yarn.nodemanager.hostname:nodemanager-1 yarn.nodemanager.address:nodemanager-1:8151 \
    yarn.nodemanager.bind-host:0.0.0.0 yarn.resourcemanager.bind-host:0.0.0.0; do
    t_contains "render_nodemanager_1_${line%%:*}" \
      "<name>${line%%:*}</name><value>${line#*:}</value>" "$text"
  done
  t_render "$dir" resourcemanager-0
  line=org.apache.hadoop.yarn.server.resourcemanager.scheduler.fifo.FifoScheduler
  t_contains render_resourcemanager_needs_no_queue_file \
    "<name>yarn.resourcemanager.scheduler.class</name><value>$line</value>" \
    "$(tr -d ' \n' <"$out/yarn-site.xml" 2>/dev/null)"
  t_render "$dir" zookeeper-0
  t_contains render_zookeeper_binds_every_interface 'clientPortAddress=0.0.0.0' \
    "$(cat "$out/zoo.cfg" 2>/dev/null)"
  t_render "$dir" client-0
  t_contains render_client_jdbc_url_by_hostname \
    'phoenix.jdbc.url=jdbc:phoenix:zookeeper-0:2181:/hbase' \
    "$(cat "$out/phoenix-client.properties" 2>/dev/null)"
  t_render "$dir" regionserver-1
  text="$(tr -d ' \n' <"$out/hbase-site.xml" 2>/dev/null)"
  for line in hbase.zookeeper.quorum:zookeeper-0 hbase.master.hostname:hbase-master-0 \
    hbase.unsafe.regionserver.hostname:regionserver-1 hbase.regionserver.ipc.address:0.0.0.0 \
    hbase.regionserver.info.bindAddress:0.0.0.0 hbase.master.ipc.address:0.0.0.0 \
    hbase.rootdir:hdfs://namenode-0:8020/hbase; do
    t_contains "render_regionserver_1_${line%%:*}" \
      "<name>${line%%:*}</name><value>${line#*:}</value>" "$text"
  done
  t_contains render_regionserver_1_rpc_port \
    '<name>hbase.regionserver.port</name><value>16030</value>' "$text"
  t_contains render_regionserver_1_info_port \
    '<name>hbase.regionserver.info.port</name><value>16035</value>' "$text"
  t_contains render_master_waits_for_every_regionserver \
    '<name>hbase.master.wait.on.regionservers.mintostart</name><value>3</value>' "$text"
  t_contains heap_fits_container_g1 '-XX:+UseG1GC' "$(cat "$out/jvm.opts" 2>/dev/null)"
  t_contains heap_fits_container_fixed_heap '-Xms3072m' "$(cat "$out/jvm.opts")"
  t_contains heap_fits_container_gc_log "file=$T_A2/logs/gc-regionserver-1.log" \
    "$(cat "$out/jvm.opts")"
  t_absent jvm_debug_off_by_default jdwp "$(cat "$out/jvm.opts")"
  t_render "$T_A2_OUT/deploy/nine" regionserver-1
  t_contains jvm_debug_binds_every_interface 'address=0.0.0.0:15012' \
    "$(cat "$out/jvm.opts" 2>/dev/null)"
  t_render "$dir" datanode-2
  text="$(tr -d ' \n' <"$out/hdfs-site.xml" 2>/dev/null)"
  for line in dfs.datanode.address:0.0.0.0:9886 dfs.datanode.hostname:datanode-2 \
    dfs.namenode.rpc-address:namenode-0:8020 dfs.namenode.rpc-bind-host:0.0.0.0 \
    dfs.namenode.http-bind-host:0.0.0.0; do
    t_contains "render_datanode_2_${line%%:*}" \
      "<name>${line%%:*}</name><value>${line#*:}</value>" "$text"
  done
  # Verify that the image renders the replication factor clamped by the deployment generator and
  # rejects any replication factor that exceeds the configured DataNode count.
  for line in "rep1 1" "rep2 2"; do
    t_render "$T_A2_OUT/deploy/${line% *}" namenode-0
    t_contains "replication_clamp_renders_${line#* }" \
      "<name>dfs.replication</name><value>${line#* }</value>" \
      "$(tr -d ' \n' <"$out/hdfs-site.xml" 2>/dev/null)"
  done
  t_render "$dir" namenode-0 PX_DN_COUNT=1
  t_equals replication_above_the_count_is_refused "2 no" "$T_STATUS $(t_exists "$out")"
  # Verify that each role's JDK version selects the appropriate JVM module options and that
  # unsupported JDK versions (8 and 25) are rejected.
  t_render "$dir" client-0 PX_JDK=21
  t_contains per_role_jdk_21_options '-Djava.security.manager=allow' "$(cat "$out/jvm.opts")"
  t_render "$dir" regionserver-0
  t_contains per_role_jdk_17_options '--add-opens=java.base/jdk.internal.util.random=' \
    "$(cat "$out/jvm.opts")"
  t_absent per_role_jdk_17_has_no_security_manager 'security.manager' "$(cat "$out/jvm.opts")"
  t_render "$dir" client-0 PX_JDK=11
  t_equals per_role_jdk_11_accepted 0 "$T_STATUS"
  bad=""
  for line in 8 25; do
    t_render "$dir" client-0 "PX_JDK=$line"
    case "$T_STATUS:$T_STDERR" in
    2:*'is not supported'*) ;;
    *) bad="$bad JDK $line gave $T_STATUS" ;;
    esac
  done
  t_empty per_role_jdk_refuses_8_and_25 "$bad"
  # Verify instance identity validation, singleton indexing, and container heap sizing bounds.
  bad=""
  for line in "PX_INSTANCE_INDEX=" "PX_INSTANCE_INDEX=3" "PX_RS_COUNT=10" "PX_ROLE=bogus"; do
    t_render "$dir" regionserver-0 "$line"
    [ "$T_STATUS" = 2 ] || bad="$bad $line gave $T_STATUS"
  done
  t_empty identity_validation "$bad"
  t_render "$dir" namenode-0 PX_INSTANCE_INDEX=1
  t_equals identity_singleton_has_index_0 2 "$T_STATUS"
  t_render "$dir" regionserver-0 PX_HEAP_MIB=4608
  t_equals heap_fits_container 2 "$T_STATUS"
  t_contains heap_fits_container_names_the_role ']: regionserver: heap 4608 MiB plus direct' \
    "$T_STDERR"
  # Verify that the client role renders the YARN configuration files only when the deployment
  # includes YARN.
  t_render "$T_A2_OUT/deploy/v" client-0
  t_equals client_yarn_toggle_off "0 no" "$T_STATUS $(t_exists "$out/yarn-site.xml")"
  t_render "$dir" client-0
  t_equals client_yarn_toggle_on "yes yes" \
    "$(t_exists "$out/yarn-site.xml") $(t_exists "$out/mapred-site.xml")"
  t_a2_render_cases
  t_a2_health_cases
  t_a2_probe_cases
}

## @description  Verifies the fail-closed template renderer, its all-or-nothing output guarantee,
##               and the template overlay mechanism.
## @return       Records the template renderer assertions. Exit 0.
t_a2_render_cases() {
  local t="$T_A2/tmpl" bad="" line d='$'
  mkdir -p "$t/overlay"
  t_image PX_BIND_HOST= PX_PORT_ZOOKEEPER_CLIENT=2181 PX_DATA_DIR=/d -- "$T_BASH" \
    "$T_IMG/render.sh" -o "$t/out" "$T_IMG/conf/zoo.cfg.tmpl"
  t_equals render_fail_closed 2 "$T_STATUS"
  t_contains render_fail_closed_names_the_variable PX_BIND_HOST "$T_STDERR"
  t_equals render_fail_closed_writes_no_file no "$(t_exists "$t/out")"
  printf 'a=%s{foo}\n' "$d" >"$t/foreign.tmpl"
  t_image -- "$T_BASH" "$T_IMG/render.sh" -o "$t/out" "$t/foreign.tmpl"
  t_contains render_leftover_aborts_a_foreign_placeholder "${d}{foo} is not a PX_* placeholder" \
    "$T_STATUS $T_STDERR"
  printf 'a=%s{PX_EVIL}\n' "$d" >"$t/evil.tmpl"
  t_image "PX_EVIL=x${d}{PX_BIND_HOST}" PX_BIND_HOST=h -- "$T_BASH" "$T_IMG/render.sh" \
    -o "$t/out" "$t/evil.tmpl"
  t_contains render_leftover_aborts 'a placeholder remains' "$T_STDERR"
  for line in "a=${d}PX_BIND_HOST" "a=${d}{PX_BIND_HOST"; do
    printf '%s\n' "$line" >"$t/bare.tmpl"
    t_image PX_BIND_HOST=h -- "$T_BASH" "$T_IMG/render.sh" -o "$t/out" "$t/bare.tmpl"
    [ "$T_STATUS" = 2 ] || bad="$bad '$line' gave $T_STATUS"
  done
  t_empty render_leftover_aborts_bare_and_unclosed "$bad"
  t_equals render_leftover_writes_no_file no "$(t_exists "$t/out")"
  t_image PX_BIND_HOST=h PX_PORT_NAMENODE_RPC=8020 PX_DATA_DIR=/d -- "$T_BASH" \
    "$T_IMG/render.sh" -o "$t/out" "$T_IMG/conf/core-site.xml.tmpl" "$t/foreign.tmpl"
  t_equals render_all_or_nothing "2 no" "$T_STATUS $(t_exists "$t/out")"
  printf 'overlay=%s{PX_BIND_HOST}\n' "$d" >"$t/overlay/zoo.cfg.tmpl"
  t_image PX_BIND_HOST=h PX_PORT_ZOOKEEPER_CLIENT=2181 PX_DATA_DIR=/d -- "$T_BASH" \
    "$T_IMG/render.sh" -o "$t/out" --overlay "$t/overlay" "$T_IMG/conf/zoo.cfg.tmpl"
  t_equals render_overlay "0 overlay=h" "$T_STATUS $(cat "$t/out/zoo.cfg" 2>/dev/null)"
}

## @description  Verifies the container health probe for the HBase Master and for server-less roles
##               gated by a readiness marker file.
## @return       Records the health probe assertions. Exit 0.
t_a2_health_cases() {
  local master='http://127.0.0.1:16010/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server'
  local up='"tag.isActiveMaster" : "true", "masterFinishedInitializationTime" : 1'
  # The container healthcheck probes the local service ports of its own instance over the loopback
  # interface.
  local env=(PX_ROLE=hbase-master PX_RS_COUNT=3 PX_PORT_HBASE_MASTER_HTTP=16010)
  t_curl_body "$master" "{\"beans\":[{$up, \"numRegionServers\" : 2}]}"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_waits_for_rs 1 "$T_STATUS"
  t_contains master_waits_for_rs_reports_the_count '2 of 3 RegionServers checked in' \
    "$T_STDOUT"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh" live
  t_equals healthcheck_live_mode_at_2_of_3 0 "$T_STATUS"
  t_curl_body "$master" "{\"beans\":[{$up, \"numRegionServers\" : 3}]}"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_ready_at_3_of_3 0 "$T_STATUS"
  t_contains healthcheck_ignores_proxies "--noproxy * --max-time" \
    "$(tail -n 1 "$T_A2_CURL_LOG")"
  # Because the HBase Master reports itself as active before hbase:meta comes online, the health
  # probe must report the instance as unready until master initialization has completed.
  t_curl_body "$master" "{\"beans\":[{${up%1}0, \"numRegionServers\" : 3}]}"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_unready_until_initialized "1 yes" \
    "$T_STATUS $(t_flag 'has not finished its initialization' "$T_STDOUT")"
  t_curl_body "$master" "{\"beans\":[{${up/true/false}, \"numRegionServers\" : 3}]}"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_inactive_is_unready 1 "$T_STATUS"
  rm -f "$T_A2_CURL_DIR/$(printf '%s' "$master" | LC_ALL=C tr -c 'A-Za-z0-9' '_')"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_silent_is_unready 1 "$T_STATUS"
  t_curl_body "$master" "{\"beans\":[{$up, \"numRegionServers\" : 3}]}"
  t_image PX_ROLE=hbase-master PX_PORT_HBASE_MASTER_HTTP=16010 -- \
    "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals master_without_a_count_is_usage 2 "$T_STATUS"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/healthcheck.sh" bogus
  t_equals healthcheck_unknown_mode 2 "$T_STATUS"
  # A role that does not run a long-lived daemon, including the one-shot setup role, becomes ready
  # as soon as its startup sequence writes the readiness marker file.
  mkdir -p "$T_A2/conf"
  rm -f "$T_A2/conf/.px-ready"
  t_image PX_ROLE=client "PX_CONF_DIR=$T_A2/conf" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals healthcheck_client_without_marker 1 "$T_STATUS"
  t_image PX_ROLE=setup "PX_CONF_DIR=$T_A2/conf" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals healthcheck_setup_unready_until_the_probe_passed 1 "$T_STATUS"
  : >"$T_A2/conf/.px-ready"
  t_image PX_ROLE=client "PX_CONF_DIR=$T_A2/conf" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals healthcheck_client_with_marker 0 "$T_STATUS"
  t_image PX_ROLE=setup "PX_CONF_DIR=$T_A2/conf" -- "$T_BASH" "$T_IMG/healthcheck.sh"
  t_equals healthcheck_setup_with_marker 0 "$T_STATUS"
}

## @description  Starts a background loopback listener that replies "imok" to simulate ZooKeeper
##               responding to the "ruok" four-letter command.
## @return       Sets T_ZK_PORT and T_ZK_PID. Exit 0, or 1 when nc is absent.
t_zk_start() {
  command -v nc >/dev/null 2>&1 || return 1
  T_ZK_PORT=$((20000 + $$ % 20000))
  (
    while :; do
      printf 'imok\n' | nc -l 127.0.0.1 "$T_ZK_PORT" >/dev/null 2>&1 || sleep 0.1
    done
  ) </dev/null >/dev/null 2>&1 &
  T_ZK_PID=$!
  px_on_exit "kill $T_ZK_PID 2>/dev/null"
  sleep 0.3
  return 0
}

## @description  Terminates the background listener started by t_zk_start and the nc process it
##               runs.
## @return       Stops the fake ZooKeeper listener. Exit 0.
t_zk_stop() {
  [ -n "${T_ZK_PID:-}" ] || return 0
  {
    kill "$T_ZK_PID"
    wait "$T_ZK_PID"
  } 2>/dev/null
  pkill -f "nc -l 127.0.0.1 $T_ZK_PORT" 2>/dev/null || true
  T_ZK_PID=""
}

## @description  Verifies the one-shot setup role, the NameNode formatting gate, and the ordered
##               cluster bootstrap probe.
## @return       Records the setup and bootstrap probe assertions. Exit 0.
t_a2_probe_cases() {
  local env=() log order rs nn='http://namenode-0:9870/jmx?qry=Hadoop:service=NameNode'
  local master='http://hbase-master-0:16010/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server'
  local rm='http://resourcemanager-0:8088/ws/v1/cluster' setup="$T_A2/setup" port
  local up='"tag.isActiveMaster" : "true", "masterFinishedInitializationTime" : 1'
  local rsq='/jmx?qry=Hadoop:service=HBase,name=RegionServer,sub=Server'
  nn="$nn,name=FSNamesystemState"
  # The setup role formats the NameNode metadata directory once and then executes the bootstrap
  # probe.
  mkdir -p "$setup/data" "$setup/conf" "$setup/logs"
  env=(PX_ROLE=setup PX_INSTANCE_INDEX=0 PX_JDK=17 PX_HEAP_MIB=256 PX_MEM_MIB=512
    PX_CLUSTER_ID=a2 PX_HOSTNAME=setup-0 PX_BIND_HOST=0.0.0.0 PX_DN_COUNT=1 PX_RS_COUNT=1
    PX_NM_COUNT=1
    PX_HDFS_REPLICATION=1 PX_YARN=0 PX_HBASE_BLOCKCACHE_SIZE=0.30 PX_HBASE_MEMSTORE_SIZE=0.35
    "PX_DATA_DIR=$setup/data" "PX_LOG_DIR=$setup/logs" "PX_CONF_DIR=$setup/conf"
    "PX_JDK_ROOT=$T_A2/jdk" "PX_HADOOP_HOME=$T_A2/hadoop" "PX_HBASE_HOME=$T_A2/hbase"
    "PX_PHOENIX_HOME=$T_A2/phoenix" PX_TIMEOUT_READY=1 PX_POLL_INTERVAL_S=0.2)
  while IFS= read -r port; do
    env+=("$port")
  done <<EOF
$(grep -v '^#' "$T_A2_OUT/deploy/v/ports.generated.env")
EOF
  env+=(PX_PORT_ZOOKEEPER_CLIENT=1)
  # A failed NameNode format leaves no completion marker behind, ensuring that the next startup
  # attempt retries the format operation.
  t_image "${env[@]}" PX_FAKE_HDFS_STATUS=1 -- "$T_BASH" "$T_IMG/entrypoint.sh"
  t_equals setup_failed_format_writes_no_marker "1 no" \
    "$T_STATUS $(t_exists "$setup/data/namenode/.px-formatted")"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/entrypoint.sh"
  t_equals setup_formats_then_probes "4 yes" \
    "$T_STATUS $(t_exists "$setup/data/namenode/.px-formatted")"
  t_contains setup_probe_stops_at_zookeeper 'fail zookeeper' "$T_STDERR"
  t_contains setup_probe_reaches_zookeeper_by_hostname 'ruok on zookeeper-0:1' "$T_STDERR"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/entrypoint.sh"
  t_contains setup_formats_once 'formatted already' "$T_STDERR"
  rm -rf "$setup/data"
  mkdir -p "$setup/data"
  t_image "${env[@]}" PX_ROLE=namenode -- "$T_BASH" "$T_IMG/entrypoint.sh"
  t_equals namenode_gate 4 "$T_STATUS"
  t_contains namenode_gate_names_the_setup_role 'the setup role formats it' "$T_STDERR"
  # Exercise the ordered bootstrap probe against simulated responses for every subject. The probe
  # contacts each peer by its hostname, which the fake curl utility serves. Because the fake
  # ZooKeeper listener binds to the loopback address of this host, the probe runs as the zookeeper
  # instance so that it reaches its own client port on the loopback interface.
  if ! t_zk_start; then
    t_skip bootstrap_probe_order_and_failure "no nc to stand in for ZooKeeper"
    return 0
  fi
  t_curl_body "$nn" '{"beans":[{"FSState" : "Operational", "NumLiveDataNodes" : 3}]}'
  t_curl_body "$master" "{\"beans\":[{$up, \"numRegionServers\" : 3}]}"
  for rs in 0:16025 1:16035 2:16045; do
    t_curl_body "http://regionserver-${rs%:*}:${rs#*:}$rsq" \
      '{"beans":[{"tag.clusterId" : "c1"}]}'
  done
  t_curl_body "$rm/info" '{"clusterInfo":{"state":"STARTED"}}'
  t_curl_body "$rm/metrics" '{"clusterMetrics":{"activeNodes":3}}'
  t_curl_body 'http://jobhistory-0:19888/ws/v1/history/info' '{"historyInfo":{"startedOn":1}}'
  mkdir -p "$T_A2/probe-logs" "$T_A2/hooks"
  env=(PX_ROLE=zookeeper "PX_PORT_ZOOKEEPER_CLIENT=$T_ZK_PORT"
    PX_PORT_NAMENODE_HTTP=9870 PX_PORT_HBASE_MASTER_HTTP=16010
    PX_PORT_REGIONSERVER_HTTP_0=16025 PX_PORT_REGIONSERVER_HTTP_1=16035
    PX_PORT_REGIONSERVER_HTTP_2=16045 PX_PORT_RESOURCEMANAGER_HTTP=8088
    PX_PORT_JOBHISTORY_HTTP=19888 PX_DN_COUNT=3 PX_RS_COUNT=3 PX_NM_COUNT=3
    "PX_HADOOP_HOME=$T_A2/hadoop" "PX_LOG_DIR=$T_A2/probe-logs" PX_TIMEOUT_READY=2
    PX_POLL_INTERVAL_S=0.3)
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe" --yarn
  t_equals bootstrap_probe_verifies_every_subject 0 "$T_STATUS"
  log="$(cat "$T_A2/probe-logs/bootstrap-probe.log" 2>/dev/null)"
  t_contains bootstrap_probe_logs_its_plan \
    'plan zookeeper hdfs hdfs-namespace hbase-master regionservers yarn workload-setup monitors' \
    "$log"
  order="$(printf '%s\n' "$log" | sed -n 's/.* ok   \([a-z-]*\):.*/\1/p' | tr '\n' ' ')"
  t_equals bootstrap_probe_runs_in_order \
    'zookeeper hdfs hdfs-namespace hbase-master regionservers yarn monitors ' "$order"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe"
  t_contains bootstrap_probe_skips_yarn_without_the_flag 'skip yarn' "$T_STDERR"
  t_curl_body "$nn" '{"beans":[{"FSState" : "Operational", "NumLiveDataNodes" : 2}]}'
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_hdfs "4 yes" "$T_STATUS $(t_flag 'fail hdfs:' "$T_STDERR")"
  t_curl_body "$nn" '{"beans":[{"FSState" : "Operational", "NumLiveDataNodes" : 3}]}'
  t_image "${env[@]}" PX_FAKE_HDFS_STATUS=1 -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_hdfs_namespace "1 yes" \
    "$T_STATUS $(t_flag 'fail hdfs-namespace' "$T_STDERR")"
  t_curl_body "$master" "{\"beans\":[{${up%1}0, \"numRegionServers\" : 3}]}"
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_master "4 yes" \
    "$T_STATUS $(t_flag 'fail hbase-master' "$T_STDERR")"
  t_curl_body "$master" "{\"beans\":[{$up, \"numRegionServers\" : 3}]}"
  t_curl_body "http://regionserver-2:16045$rsq" \
    '{"beans":[{"tag.clusterId" : ""}]}'
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_one_regionserver "4 yes" \
    "$T_STATUS $(t_flag 'regionserver 2: the RegionServer has not joined' "$T_STDERR")"
  t_curl_body "http://regionserver-2:16045$rsq" \
    '{"beans":[{"tag.clusterId" : "c1"}]}'
  t_curl_body "$rm/metrics" '{"clusterMetrics":{"activeNodes":2}}'
  t_image "${env[@]}" -- "$T_BASH" "$T_IMG/bin/px-bootstrap-probe" --yarn
  t_equals bootstrap_probe_fails_yarn "4 yes" "$T_STATUS $(t_flag 'fail yarn' "$T_STDERR")"
  printf '#!/usr/bin/env bash\nexit 3\n' >"$T_A2/hooks/10-fails"
  chmod +x "$T_A2/hooks/10-fails"
  t_image "${env[@]}" "PX_BOOTSTRAP_SETUP_DIR=$T_A2/hooks" -- "$T_BASH" \
    "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_workload_setup "1 yes" \
    "$T_STATUS $(t_flag 'fail workload-setup' "$T_STDERR")"
  : >"$T_A2/heartbeat"
  touch -t 202001010000 "$T_A2/heartbeat"
  t_image "${env[@]}" "PX_MONITOR_HEARTBEATS=$T_A2/heartbeat" -- "$T_BASH" \
    "$T_IMG/bin/px-bootstrap-probe"
  t_equals bootstrap_probe_fails_a_stale_monitor "4 yes" \
    "$T_STATUS $(t_flag 'fail monitors' "$T_STDERR")"
  t_zk_stop
}

## @description  Verifies host preflight checks: engine memory budget, container heap bounds,
##               Compose memory syntax forms, and fast-phase delegation.
## @return       Records the host preflight assertions. Exit 0.
t_group_a2_preflight() {
  local pf="$PX_HOME/scripts/preflight.sh" hand="$T_A2/hand" row
  printf '# the host preflight\n'
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=m "$T_BASH" "$pf" --phase memory
  t_equals preflight_refuses_measurement_on_this_engine 3 "$T_STATUS"
  row="$(printf '%s\n' "$T_STDOUT" | grep "^memory_fit${PX_TAB}fail")"
  t_contains preflight_refusal_shows_the_sum '41984 MiB (41.00 GiB)' "$row"
  t_contains preflight_refusal_shows_the_budget 'budget 19905 MiB (19.44 GiB)' "$row"
  t_contains preflight_refusal_shows_the_engine 'engine 24001 MiB (23.44 GiB)' "$row"
  t_contains preflight_refusal_shows_the_headroom 'headroom 4096 MiB (4.00 GiB)' "$row"
  t_contains preflight_refusal_lists_the_roles 'regionserver: 3 instance(s), 13824 MiB' \
    "$T_STDERR"
  t_contains preflight_counts_the_on_demand_group 'group yarn starts on demand' "$T_STDERR"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory
  t_equals preflight_admits_validation 0 "$T_STATUS"
  t_contains preflight_admits_validation_with_the_sum "memory_fit${PX_TAB}ok${PX_TAB}the limits \
8704 MiB" "$T_STDOUT"
  t_gen --class measurement --cluster o --config "$T_ENVD/override-24gib.env"
  t_a2 PX_CLUSTER_ID=o "$T_BASH" "$pf" --phase memory
  t_equals preflight_admits_override_24gib 0 "$T_STATUS"
  t_contains preflight_admits_override_24gib_with_margin '705 MiB (0.69 GiB) to spare' \
    "$T_STDOUT"
  # Verify the memory_check boundary: admit a topology that sits 1 GiB below the available budget
  # and reject one that exceeds the budget by 1 GiB.
  printf '%s\n' "$(((8704 + 4096 + 1024) * 1048576))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory
  t_equals memory_check_passes_below_the_budget "0 yes" \
    "$T_STATUS $(t_flag '1024 MiB (1.00 GiB) to spare' "$T_STDOUT")"
  printf '%s\n' "$(((8704 + 4096 - 1024) * 1048576))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory
  t_equals memory_check_refuses_above_the_budget 3 "$T_STATUS"
  row="$(printf '%s\n' "$T_STDOUT" | grep "^memory_fit${PX_TAB}")"
  t_contains memory_check_names_both_totals 'the limits 8704 MiB (8.50 GiB)' "$row"
  t_contains memory_check_names_the_engine_total 'the engine 11776 MiB (11.50 GiB)' "$row"
  t_contains memory_check_names_the_excess 'exceed the budget by 1024 MiB (1.00 GiB)' "$row"
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  # Verify that a service's configured JVM heap size must remain strictly below its container
  # memory limit.
  mkdir -p "$hand/heap" "$hand/nolimit" "$hand/none" "$hand/empty" "$hand/forms"
  cat >"$hand/heap/compose.yaml" <<'A2_EOF'
services:
  regionserver-0:
    mem_limit: 3072m
    labels:
      px.harness.role: "regionserver"
    environment:
      PX_HEAP_MIB: "5000"
A2_EOF
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/heap"
  t_equals preflight_heap_bound 3 "$T_STATUS"
  t_contains preflight_heap_bound_names_the_role "heap_bound${PX_TAB}fail${PX_TAB}regionserver: \
the heap of 5000 MiB is not below the container limit of 3072 MiB" "$T_STDOUT"
  sed 's/"5000"/"2048"/' "$hand/heap/compose.yaml" >"$hand/heap/c2" &&
    mv "$hand/heap/c2" "$hand/heap/compose.yaml"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/heap"
  t_absent preflight_heap_below_the_limit_passes 'heap_bound' "$T_STDOUT"
  printf 'services:\n  x:\n    image: "y"\n' >"$hand/nolimit/compose.yaml"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/nolimit"
  t_equals preflight_unmeasured_limit "1 yes" \
    "$T_STATUS $(t_flag 'service x (x) has no memory limit' "$T_STDOUT")"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/none"
  t_equals preflight_unmeasured_deployment "1 yes" \
    "$T_STATUS $(t_flag 'no rendered deployment at' "$T_STDOUT")"
  printf 'services: {}\n' >"$hand/empty/compose.yaml"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/empty"
  t_contains preflight_unmeasured_no_service 'declares no service' "$T_STDOUT"
  cat >"$hand/forms/compose.yaml" <<'A2_EOF'
services:
  rs:
    labels:
      - "px.harness.role=regionserver"
    environment:
      - PX_HEAP_MIB=3072
    deploy:
      replicas: 3
      resources:
        limits:
          memory: 4.5G
  cl:
    scale: 2
    mem_limit: "4096MiB"
    labels:
      px.harness.role: "client"
volumes:
  data:
    labels:
      px.harness.cluster: "x"
A2_EOF
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/forms" -n
  t_contains preflight_parses_compose_forms_replicas \
    "memory:regionserver${PX_TAB}ok${PX_TAB}3 x 4608 MiB = 13824 MiB" "$T_STDOUT"
  t_contains preflight_parses_compose_forms_scale \
    "memory:client${PX_TAB}ok${PX_TAB}2 x 4096 MiB = 8192 MiB" "$T_STDOUT"
  # Verify that the fast phase delegates runtime and port availability checks to the backend
  # preflight.
  mkdir -p "$hand/fast"
  cp "$T_DEPLOY/compose.yaml" "$T_DEPLOY/ports.generated.env" "$hand/fast/"
  t_a2 "$T_BASH" "$pf" --phase fast --deployment "$hand/fast"
  t_equals preflight_fast_phase 0 "$T_STATUS"
  t_contains preflight_fast_phase_reports_the_ports "port_availability${PX_TAB}ok" "$T_STDOUT"
  t_contains preflight_fast_phase_reports_the_engine "engine_memory${PX_TAB}ok" "$T_STDOUT"
  t_a2 "$T_BASH" "$pf" --deployment "$hand/fast"
  t_equals preflight_runs_both_phases "0 yes yes" \
    "$T_STATUS $(t_flag engine_binary "$T_STDOUT") $(t_flag memory_fit "$T_STDOUT")"
  : >"$T_A2_STATE/unreachable"
  t_a2 "$T_BASH" "$pf" --phase fast --deployment "$hand/fast"
  t_equals preflight_fast_phase_refuses_a_silent_engine 1 "$T_STATUS"
  rm -f "$T_A2_STATE/unreachable"
  t_a2 "$T_BASH" "$pf" --phase slow
  t_equals preflight_unknown_phase 2 "$T_STATUS"
  t_a2 "$T_BASH" "$pf" --bogus
  t_equals preflight_unknown_option 2 "$T_STATUS"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pf" -n --deployment "$T_A2_OUT/deploy/m"
  t_equals preflight_dry_run "0 yes" "$T_STATUS $(t_flag 'limits sum to 41984 MiB' "$T_STDOUT")"
  t_empty preflight_dry_run_calls_no_runtime "$(cat "$T_SHIM_LOG")"
}

## @description  Verifies pinned artifact downloads, the local Phoenix Maven build, and container
##               image context staging and building.
## @return       Records the distribution and build assertions. Exit 0.
t_group_a2_dist() {
  local fd="$PX_HOME/scripts/fetch-dist.sh" fixture="$T_A2/dist-src/hbase-2.6.7.tar.gz"
  local sum other pins="$T_A2_OUT/dist/pins.env" url bad="" ctx tree="$T_A2/tree" text line
  local bp="$PX_HOME/scripts/build-phoenix.sh" sa="$PX_HOME/scripts/stage-artifacts.sh"
  local pins_env=() jdk="$T_A2/jdk/17" foo=(PX_DIST_FOO_VERSION=1 PX_DIST_FOO_PATH=x/foo-1.tar.gz)
  local versions="$PX_HOME/$PX_DEPLOY_SUBDIR/versions.env" archive=https://archive.apache.org/dist
  local tag=px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6
  local hadoop="$T_A2/dist-src/hadoop-3.4.3.tar.gz"
  local local_env="$T_A2/versions.local.env" probe="$T_WORK/probe/versions-check.sh"
  printf '# pinned downloads, the Phoenix build and the image context\n'
  sum="$(px_sha512 "$fixture")"
  url='https://archive.apache.org/dist/x/foo-1.tar.gz'
  t_curl_file "$url" "$fixture"
  t_a2 "${foo[@]}" "PX_DIST_FOO_1_SHA512_ARM64=$(printf 'a%.0s' $(seq 1 128))" "$T_BASH" "$fd" foo
  t_equals pin_mismatch_aborts 1 "$T_STATUS"
  t_contains pin_mismatch_names_the_expected_hash "expected $(printf 'a%.0s' $(seq 1 128))" \
    "$T_STDERR"
  t_contains pin_mismatch_names_the_actual_hash "actual $sum" "$T_STDERR"
  t_empty pin_mismatch_keeps_no_file "$(find "$T_A2_OUT/dist" -type f ! -name pins.env)"
  # Verify that a SHA-512 pin split across multiple continuation variables to respect line-length
  # limits is reassembled before verification.
  t_a2 "${foo[@]}" "PX_DIST_FOO_1_SHA512_ARM64=${sum:0:50}" \
    "PX_DIST_FOO_1_SHA512_ARM64_2=${sum:50:50}" "PX_DIST_FOO_1_SHA512_ARM64_3=${sum:100}" \
    "$T_BASH" "$fd" foo
  t_equals pin_in_parts_is_joined "0 pinned" "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  rm -f "$T_A2_OUT/dist/foo-1.tar.gz"
  t_a2 "${foo[@]}" "PX_DIST_FOO_1_SHA512_ARM64=$sum" "PX_DIST_FOO_1_SHA512_ARM64_2=${sum:64}" \
    "$T_BASH" "$fd" foo
  t_equals pin_whole_is_not_joined "0 pinned" "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  rm -f "$T_A2_OUT/dist/foo-1.tar.gz"
  : >"$T_A2_CURL_LOG"
  t_a2 "${foo[@]}" "$T_BASH" "$fd" foo
  t_equals tofu_records_pin "0 tofu" "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  t_contains tofu_records_pin_under_the_output_root "PX_DIST_FOO_1_SHA512_ARM64=$sum" \
    "$(cat "$pins" 2>/dev/null)"
  t_contains tofu_logs_the_hash "Its SHA-512 $sum is recorded" "$T_STDERR"
  t_contains fetch_mirror_falls_back_to_the_archive "$url" "$(cat "$T_A2_CURL_LOG")"
  t_contains fetch_curl_refuses_other_schemes '--proto =https --proto-redir =https' \
    "$(cat "$T_A2_CURL_LOG")"
  : >"$T_A2_CURL_LOG"
  t_a2 "${foo[@]}" "$T_BASH" "$fd" foo
  t_equals tofu_reuses_the_record "0 recorded" "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  t_empty tofu_reuse_fetches_nothing "$(cat "$T_A2_CURL_LOG")"
  rm -f "$T_A2_OUT/dist/foo-1.tar.gz"
  t_a2 "${foo[@]}" "$T_BASH" "$fd" foo
  t_equals tofu_verifies_a_new_download_against_the_record "0 recorded" \
    "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  rm -f "$T_A2_OUT/dist/foo-1.tar.gz"
  other="$T_A2/dist-src/other.tar.gz"
  printf 'other bytes\n' >"$other"
  t_curl_file "$url" "$other"
  t_a2 "${foo[@]}" "$T_BASH" "$fd" foo
  t_equals tofu_refuses_changed_bytes 1 "$T_STATUS"
  # Because a recorded hash pin is scoped to a specific release version, downloading a different
  # release of the same component triggers trust on first use again.
  t_curl_file 'https://archive.apache.org/dist/x/foo-2.tar.gz' "$other"
  t_a2 PX_DIST_FOO_VERSION=2 'PX_DIST_FOO_PATH=x/foo-{version}.tar.gz' "$T_BASH" "$fd" foo
  t_equals tofu_pin_belongs_to_one_release "0 tofu 2" \
    "$T_STATUS $(px_tsv_field 6 "$T_STDOUT") $(px_tsv_field 3 "$T_STDOUT")"
  t_curl_file "$url" "$fixture"
  # Verify that cached archive files are re-validated against their hash pin on every invocation.
  t_a2 "${foo[@]}" "PX_DIST_FOO_1_SHA512_ARM64=$sum" "$T_BASH" "$fd" foo
  printf 'tampered\n' >>"$T_A2_OUT/dist/foo-1.tar.gz"
  t_a2 "${foo[@]}" "PX_DIST_FOO_1_SHA512_ARM64=$sum" "$T_BASH" "$fd" foo
  t_equals fetch_cache_reverified "0 pinned" "$T_STATUS $(px_tsv_field 6 "$T_STDOUT")"
  t_contains fetch_cache_reverified_warns 'it is fetched again' "$T_STDERR"
  : >"$T_A2_CURL_LOG"
  t_a2 PX_DIST_FOO_VERSION=1 PX_DIST_FOO_URL=http://example.org/foo.tgz "$T_BASH" "$fd" foo
  t_equals fetch_refuses_non_https "2 yes" \
    "$T_STATUS $(t_flag 'only HTTPS addresses are fetched' "$T_STDERR")"
  t_empty fetch_refuses_non_https_before_a_request "$(cat "$T_A2_CURL_LOG")"
  # The scan must locate the agent channel in docker/pherf; otherwise, the subsequent check proves
  # nothing. Neither a loopback probe nor that agent channel represents an external download.
  text="$(grep -rn 'http://' "$PX_HOME/scripts" "$PX_HOME/$PX_DEPLOY_SUBDIR" 2>/dev/null)"
  t_contains fetch_http_scan_reads_the_tree "pherf/compose.yaml:" "$text"
  bad="$(printf '%s\n' "$text" | grep -v ':[[:space:]]*#' | grep -v 'www.apache.org/licenses' |
    grep -vE "http://(127\\.0\\.0\\.1|\\\$host|controller:|pherf-controller-0:)")"
  t_empty fetch_no_http_download_target "$bad"
  t_curl_body 'https://www.apache.org/dyn/closer.lua?path=x/bar-1.tar.gz&asjson=1' \
    '{"preferred": "https://dlcdn.apache.org/"}'
  t_curl_file 'https://dlcdn.apache.org/x/bar-1.tar.gz' "$fixture"
  : >"$T_A2_CURL_LOG"
  t_a2 PX_DIST_BAR_VERSION=1 PX_DIST_BAR_PATH=x/bar-1.tar.gz "$T_BASH" "$fd" bar
  t_equals fetch_mirror_resolution 0 "$T_STATUS"
  t_contains fetch_mirror_resolution_uses_the_mirror 'https://dlcdn.apache.org/x/bar-1.tar.gz' \
    "$(cat "$T_A2_CURL_LOG")"
  t_curl_body 'https://www.apache.org/dyn/closer.lua?path=x/baz-1.tar.gz&asjson=1' \
    '{"preferred": "http://insecure.example/"}'
  t_curl_file 'https://archive.apache.org/dist/x/baz-1.tar.gz' "$fixture"
  t_a2 PX_DIST_BAZ_VERSION=1 PX_DIST_BAZ_PATH=x/baz-1.tar.gz "$T_BASH" "$fd" baz
  t_equals fetch_mirror_ignores_an_http_answer "0 yes" \
    "$T_STATUS $(t_flag 'not HTTPS, it is ignored' "$T_STDERR")"
  t_curl_file 'https://github.com/r/v1/tool-arm64.tar.gz' "$fixture"
  t_curl_file 'https://example.org/jhiccup.tar.gz' "$fixture"
  : >"$T_A2_CURL_LOG"
  t_a2 PX_DIST_TOOL_VERSION=1 PX_DIST_TOOL_BASE_URL=https://github.com/r \
    'PX_DIST_TOOL_FILE_ARM64=v{version}/tool-arm64.tar.gz' PX_DIST_HICCUP_VERSION=1 \
    PX_DIST_HICCUP_URL=https://example.org/jhiccup.tar.gz "$T_BASH" "$fd" tool hiccup
  t_equals fetch_dedicated_flows "0 tofu tofu" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    cut -f 6 | tr '\n' ' ' | sed 's/ $//')"
  t_absent fetch_dedicated_flows_skip_the_mirror 'closer.lua' "$(cat "$T_A2_CURL_LOG")"
  t_contains fetch_base_url_and_file_join 'https://github.com/r/v1/tool-arm64.tar.gz' \
    "$(cat "$T_A2_CURL_LOG")"
  # Verify that the host CPU architecture selects the architecture-specific download path, URL, and
  # hash pin.
  t_curl_file 'https://archive.apache.org/dist/x/amd.tar.gz' "$fixture"
  t_a2 PX_FAKE_UNAME_M=x86_64 PX_DIST_QUX_VERSION=1 PX_DIST_QUX_PATH_AMD64=x/amd.tar.gz \
    PX_DIST_QUX_PATH_ARM64=x/arm.tar.gz "$T_BASH" "$fd" qux
  t_equals fetch_arch_amd64 "0 amd64" "$T_STATUS $(px_tsv_field 2 "$T_STDOUT")"
  t_a2 PX_FAKE_UNAME_M=riscv64 PX_DIST_QUX_VERSION=1 PX_DIST_QUX_PATH=x/a.tar.gz "$T_BASH" \
    "$fd" qux
  t_equals fetch_arch_unsupported 5 "$T_STATUS"
  bad=""
  for line in "6 nosuch" "2 Bad_Name"; do
    t_a2 "$T_BASH" "$fd" "${line#* }"
    [ "$T_STATUS" = "${line%% *}" ] || bad="$bad ${line#* } gave $T_STATUS"
  done
  t_a2 PX_DIST_BOTH_VERSION=1 PX_DIST_BOTH_PATH=x/a.tar.gz PX_DIST_BOTH_URL=https://e.org/a.tar.gz \
    "$T_BASH" "$fd" both
  [ "$T_STATUS" = 2 ] || bad="$bad both gave $T_STATUS"
  t_a2 "${foo[@]}" PX_DIST_FOO_1_SHA512_ARM64=xyz "$T_BASH" "$fd" foo
  [ "$T_STATUS" = 2 ] || bad="$bad pin xyz gave $T_STATUS"
  t_a2 PX_DIST_FOO_PATH=x/foo-1.tar.gz "$T_BASH" "$fd" foo
  [ "$T_STATUS" = 2 ] || bad="$bad an entry without a release gave $T_STATUS"
  t_empty fetch_arch_and_errors "$bad"
  : >"$T_A2_CURL_LOG"
  t_a2 "${foo[@]}" "$T_BASH" "$fd" -n foo
  t_equals fetch_dry_run "0 " "$T_STATUS $(cat "$T_A2_CURL_LOG")"
  # Verify that the default version catalog tracks the latest patch releases with declared hash
  # pins, and that the committed catalog defines only fallback defaults so any precedence stage can
  # re-pin the active selection.
  t_equals versions_track_the_latest_patch_releases "2.6 2.6.7 3.4.3" \
    "$(t_env_value "$versions" PX_PHOENIX_HBASE_PROFILE_DEFAULT PX_DIST_HBASE_DEFAULT_2_6 \
      PX_DIST_HADOOP_DEFAULT)"
  t_empty versions_set_no_selection "$(grep -E \
    '^PX_(PHOENIX_HBASE_PROFILE|DIST_HBASE_VERSION|DIST_HADOOP_VERSION|IMAGE_TAG)=' "$versions")"
  t_a2 "$T_BASH" "$fd" -n hbase hadoop
  t_equals declared_pins_cover_the_defaults "0 2 0" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    grep -c "^plan${PX_TAB}fetch${PX_TAB}.* pinned\$") $(printf '%s\n' "$T_STDOUT" |
    grep -c ' tofu$')"
  t_contains default_hbase_path "$archive/hbase/2.6.7/hbase-2.6.7-hadoop3-bin.tar.gz" "$T_STDOUT"
  t_contains default_hadoop_arm64_file \
    "$archive/hadoop/common/hadoop-3.4.3/hadoop-3.4.3-aarch64.tar.gz" "$T_STDOUT"
  t_a2 PX_FAKE_UNAME_M=x86_64 "$T_BASH" "$fd" -n hbase hadoop
  t_equals declared_pins_cover_the_defaults_amd64 "0 2" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    grep -c "^plan${PX_TAB}fetch${PX_TAB}.* pinned\$")"
  t_contains default_hadoop_amd64_file "$archive/hadoop/common/hadoop-3.4.3/hadoop-3.4.3.tar.gz" \
    "$T_STDOUT"
  t_a2 PX_DIST_HADOOP_VERSION=3.4.2 "$T_BASH" "$fd" -n hadoop
  t_contains hadoop_arm64_file_per_release \
    "$archive/hadoop/common/hadoop-3.4.2/hadoop-3.4.2-aarch64-lean.tar.gz" "$T_STDOUT"
  t_a2 PX_DIST_HADOOP_VERSION=3.9.9 "$T_BASH" "$fd" -n hadoop
  t_equals hadoop_release_without_an_arm64_file "6 yes" \
    "$T_STATUS $(t_flag 'PX_DIST_HADOOP_3_9_9_PATH' "$T_STDERR")"
  # A declared hash pin for a default release is always strictly enforced and never silently
  # replaced by trust on first use: serving the test fixture tarball at the HBase 2.6.7 URL must be
  # rejected.
  t_curl_file "$archive/hbase/2.6.7/hbase-2.6.7-hadoop3-bin.tar.gz" "$fixture"
  t_a2 "$T_BASH" "$fd" hbase
  line="$(t_env_value "$versions" PX_DIST_HBASE_2_6_7_SHA512_ARM64)"
  line="$line$(t_env_value "$versions" PX_DIST_HBASE_2_6_7_SHA512_ARM64_2)"
  t_equals declared_pin_refuses_other_bytes "1 yes" \
    "$T_STATUS $(t_flag "expected $line, actual $sum" "$T_STDERR")"
  t_absent declared_pin_records_no_tofu PX_DIST_HBASE_2_6_7 "$(cat "$pins" 2>/dev/null)"
  # Verify that every supported HBase profile maps to its corresponding compatibility module in the
  # repository pom.xml and that its default HBase release falls within that module's supported
  # version range.
  bad=""
  for line in 2.5.0:2.5.0:2.5.3 2.5.4:2.5.4:2.5.10 2.5:2.5.4:2.5.16 2.6.0:2.6.0:2.6.1 \
    2.6.3:2.6.0:2.6.3 2.6:2.6.4:2.6.7; do
    IFS=: read -r text other url <<<"$line"
    t_a2 "PX_PHOENIX_HBASE_PROFILE=$text" "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" \
      --tree "$PX_TREE_ROOT" -n
    case "$T_STATUS $T_STDERR" in
    "0 "*"profile $text (compatibility module $other) compiles against HBase "*) ;;
    *) bad="$bad $text:build" ;;
    esac
    t_a2 "PX_PHOENIX_HBASE_PROFILE=$text" "$T_BASH" "$probe"
    [ "$T_STATUS $T_STDOUT" = "0 $text $url" ] || bad="$bad $text:$T_STATUS:$T_STDOUT"
  done
  t_empty profiles_map_to_their_module_and_default_release "$bad"
  # Verify that an HBase release outside the version range admitted by its profile's compatibility
  # module is rejected, both at the upper and lower bounds of a closed range and below the floor of
  # an open-ended range.
  bad=""
  for line in 2.6.0:2.6.7:'2.6.0 to 2.6.3' 2.6:2.6.3:'2.6.4 and every later 2.6 release' \
    2.6:2.7.0:'2.6.4 and every later 2.6 release' 2.5.0:2.5.4:'2.5.0 to 2.5.3'; do
    IFS=: read -r text other url <<<"$line"
    t_a2 "PX_PHOENIX_HBASE_PROFILE=$text" "PX_DIST_HBASE_VERSION=$other" "$T_BASH" "$probe"
    case "$T_STATUS $T_STDERR" in
    "2 "*"HBase '$other'"*"of profile $text: its compatibility module"*"admits HBase $url"*) ;;
    *) bad="$bad $text:$other" ;;
    esac
  done
  t_a2 PX_PHOENIX_HBASE_PROFILE=2.7 "$T_BASH" "$probe"
  [ "$T_STATUS" = 2 ] || bad="$bad unknown profile gave $T_STATUS"
  t_empty range_refuses_a_release_outside_the_module "$bad"
  t_a2 PX_PHOENIX_HBASE_PROFILE=2.6.0 PX_DIST_HBASE_VERSION=2.6.7 "$T_BASH" \
    "$PX_HOME/scripts/preflight.sh" -n
  t_equals preflight_refuses_a_release_outside_the_range "2 yes" \
    "$T_STATUS $(t_flag "admits HBase 2.6.0 to 2.6.3" "$T_STDERR")"
  # Verify phoenix_is_local_only (R-019): Phoenix artifacts are always built from the local working
  # tree and never downloaded from a remote distribution URL.
  t_empty phoenix_is_local_only "$(grep -rniE 'https?://[^ ]*phoenix[^ ]*(\.tar\.gz|\.jar)' \
    "$PX_HOME/scripts" "$PX_HOME/$PX_DEPLOY_SUBDIR" 2>/dev/null)"
  t_empty phoenix_is_never_a_dist_entry "$(grep '^PX_DIST_' "$versions" | grep -i phoenix)"
  # Verify in build-phoenix.sh (R-028) that the selected profile is passed to the Maven command
  # line.
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_dry_run 0 "$T_STATUS"
  t_contains build_phoenix_dry_run_names_the_jdk "JAVA_HOME=$jdk" "$T_STDOUT"
  t_contains build_phoenix_dry_run_names_the_modules \
    '-pl phoenix-server,phoenix-client-parent/phoenix-client-embedded,phoenix-pherf -am' \
    "${T_STDOUT//\\/}"
  t_contains build_phoenix_dry_run_selects_the_default_profile '-Dhbase.profile=2.6 -DskipTests' \
    "$T_STDOUT"
  t_contains build_phoenix_cleans_before_it_packages '-Dskip.code-coverage clean package' \
    "$T_STDOUT"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.5 "$T_BASH" "$bp" --tree "$tree" -n
  t_contains build_phoenix_profile_reaches_maven '-Dhbase.profile=2.5 -DskipTests' "$T_STDOUT"
  t_empty build_phoenix_dry_run_runs_no_maven "$(cat "$T_A2_MVN_LOG")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.7 "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_refuses_an_unknown_profile 2 "$T_STATUS"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.6.3 "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_refuses_a_pom_with_another_module "1 yes" \
    "$T_STATUS $(t_flag 'Bring the table up to date' "$T_STDERR")"
  # Run this negative check before the first build of the fixture source tree so that no JAR left
  # behind by an earlier build can stand in for the missing artifact.
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_NO_SHADED=1 "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_refuses_a_missing_jar "1 yes no" "$T_STATUS $(t_flag \
    "made no $tree/phoenix-server/target/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar" \
    "$T_STDERR") $(t_exists "$T_A2_OUT/build/phoenix/build.tsv")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_NO_BSON=1 "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_refuses_a_class_path_without_bson "1 yes no" "$T_STATUS $(t_flag \
    "$tree/phoenix-core-client/target/cached_classpath.txt does not name exactly one bson jar" \
    "$T_STDERR") $(t_exists "$T_A2_OUT/build/phoenix/build.tsv")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_builds_and_records 0 "$T_STATUS"
  t_contains build_phoenix_runs_maven_with_the_jdk "JAVA_HOME=$jdk " "$(cat "$T_A2_MVN_LOG")"
  t_equals build_phoenix_records_the_profile "5.4.0-SNAPSHOT 2.6 2.6.4 2.6.5-hadoop3 3.4.2 17" \
    "$(t_build_record phoenix_version hbase_profile compat_module hbase_version hadoop_version \
      jdk)"
  t_equals build_phoenix_selects_the_shaded_jars \
    "$tree/phoenix-server/target/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar \
$tree/phoenix-client-parent/phoenix-client-embedded/target/\
phoenix-client-embedded-hbase-2.6-5.4.0-SNAPSHOT.jar \
$tree/phoenix-pherf/target/phoenix-pherf-5.4.0-SNAPSHOT.jar" \
    "$(t_build_record server_jar client_jar pherf_jar)"
  # Because the shaded client JARs leave org.bson to the external classpath, the build record must
  # capture the bson JAR resolved during the Maven build.
  t_equals build_phoenix_records_the_bson_jar "$T_A2/m2/org/mongodb/bson/5.3.1/bson-5.3.1.jar" \
    "$(t_build_record bson_jar)"
  t_equals build_phoenix_records_the_java_home "$jdk" "$(t_build_record java_home)"
  t_equals build_phoenix_records_the_pherf_classpath \
    "$tree/phoenix-pherf/target/phoenix-pherf-5.4.0-SNAPSHOT.jar:$T_A2/m2/javax/jaxb-api-2.3.1.jar:\
$T_A2/m2/log4j/log4j-slf4j-impl-2.25.5.jar" "$(t_build_record pherf_classpath)"
  # Test JARs on the classpath place the log4j2-test configuration ahead of Pherf's configuration.
  t_equals build_phoenix_leaves_out_the_test_jars no \
    "$(t_flag '-tests.jar' "$(t_build_record pherf_classpath)")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_BUILD_JDK=8
  t_equals build_phoenix_refuses_jdk_8 2 "$T_STATUS"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_BUILD_JDK=11
  t_equals build_phoenix_refuses_a_mismatched_jdk 2 "$T_STATUS"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_PHERF=1 -n
  t_equals build_phoenix_distributed_needs_the_module "5 yes" "$T_STATUS $(t_flag \
    'the tree holds no phoenix-pherf-distributed module, which PX_PHERF=1 builds' "$T_STDERR")"
  mkdir -p "$tree/phoenix-pherf-distributed"
  printf '<project/>\n' >"$tree/phoenix-pherf-distributed/pom.xml"
  touch -t 202001010000 "$tree/phoenix-pherf-distributed/pom.xml"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_PHERF=1 -n
  t_contains build_phoenix_selects_the_module_from_px_pherf \
    'phoenix-pherf,phoenix-pherf-distributed -am' "${T_STDOUT//\\/}"
  rm -rf "$tree/phoenix-pherf-distributed"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_STATUS=1 "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_failure_removes_the_record "1 no" \
    "$T_STATUS $(t_exists "$T_A2_OUT/build/phoenix/build.tsv")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_NO_PHERF_CP=1 "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_refuses_a_missing_pherf_classpath "1 yes no" "$T_STATUS $(t_flag \
    "the build wrote no class path of phoenix-pherf: $tree/phoenix-pherf/target/classes/\
mrapp-generated-classpath" "$T_STDERR") $(t_exists "$T_A2_OUT/build/phoenix/build.tsv")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  t_curl_file "$archive/hadoop/common/hadoop-3.4.3/hadoop-3.4.3-aarch64.tar.gz" "$hadoop"
  t_curl_file "$archive/hadoop/common/hadoop-3.4.3/hadoop-3.4.3.tar.gz" "$hadoop"
  url='https://github.com/async-profiler/async-profiler/releases/download'
  t_curl_file "$url/v4.1/async-profiler-4.1-linux-arm64.tar.gz" \
    "$T_A2/dist-src/async-profiler-4.1.tar.gz"
  t_curl_file "$url/v4.1/async-profiler-4.1-linux-x64.tar.gz" \
    "$T_A2/dist-src/async-profiler-4.1.tar.gz"
  # Because the synthetic fixture archives stand in for the default releases during staging, supply
  # their hashes at the highest-precedence configuration stage.
  pins_env=("PX_IMAGE_BASE_JDK11_SHA256_ARM64=$(printf '1%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK17_SHA256_ARM64=sha256:$(printf '7%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK21_SHA256_ARM64=$(printf '2%.0s' $(seq 1 64))")
  for line in ARM64 AMD64; do
    pins_env+=("PX_DIST_HBASE_2_6_7_SHA512_$line=$sum"
      "PX_DIST_HADOOP_3_4_3_SHA512_$line=$(px_sha512 "$hadoop")")
  done
  # Seed the jHiccup build output produced by build-jhiccup.sh so artifact staging can consume it.
  mkdir -p "$T_A2_OUT/build/jhiccup"
  printf 'fake jHiccup\n' >"$T_A2_OUT/build/jhiccup/jHiccup.jar"
  t_a2 "${pins_env[@]:0:2}" "${pins_env[@]:3}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_requires_every_digest 1 "$T_STATUS"
  t_contains stage_artifacts_names_the_missing_digest \
    'build argument PX_IMAGE_BASE_JDK21_SHA256' "$T_STDERR"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_checks 0 "$T_STATUS"
  ctx="$(px_tsv_field 2 "$T_STDOUT")"
  t_equals stage_artifacts_context_under_the_output_root \
    "$T_A2_OUT/images/px-harness_hbase-2.6.7-hadoop-3.4.3-profile-2.6" "$ctx"
  bad=""
  for line in Dockerfile entrypoint.sh healthcheck.sh render.sh lib/image.sh \
    bin/px-bootstrap-probe conf/hbase-site.xml.tmpl .dockerignore \
    dist/hbase-2.6.7-hadoop3-bin.tar.gz dist/hadoop-3.4.3-aarch64.tar.gz \
    dist/async-profiler-4.1-linux-arm64.tar.gz dist/jHiccup.jar \
    phoenix/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar \
    phoenix/phoenix-client-embedded-hbase-2.6-5.4.0-SNAPSHOT.jar \
    phoenix/phoenix-pherf-5.4.0-SNAPSHOT.jar phoenix/bson-5.3.1.jar; do
    [ -f "$ctx/$line" ] || bad="$bad $line"
  done
  t_empty stage_artifacts_context_holds_every_part "$bad"
  # Verify that staged Phoenix jars are resolved from the paths named in the build record: when the
  # record specifies an alternate server jar, that recorded jar is staged instead of the jar in the
  # source tree.
  text="$T_A2_OUT/build/phoenix/build.tsv"
  cp "$text" "$T_A2/build.tsv.saved"
  cp "$tree/phoenix-server/target/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar" \
    "$T_A2/recorded-server.jar"
  awk -F '\t' -v OFS='\t' -v p="$T_A2/recorded-server.jar" '$1 == "server_jar" { $2 = p } 1' \
    "$T_A2/build.tsv.saved" >"$text"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals phoenix_jars_come_from_the_build_record "0 yes no" "$T_STATUS $(t_exists \
    "$ctx/phoenix/recorded-server.jar") $(t_exists \
    "$ctx/phoenix/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar")"
  # Reject any stale build record created prior to bson JAR tracking, because it cannot supply the
  # client with its required bson JAR.
  grep -v '^bson_jar' "$T_A2/build.tsv.saved" >"$text"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_refuses_a_record_without_bson "1 yes" "$T_STATUS $(t_flag \
    "has no bson_jar. Run build-phoenix.sh again." "$T_STDERR")"
  cp "$T_A2/build.tsv.saved" "$text"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  # Verify that the image installs the staged bson JAR alongside the Phoenix client JARs in the
  # directory included on every driver's classpath.
  t_contains image_puts_the_bson_jar_with_the_client_jars \
    "\"/stage/\$PX_STAGED_PHOENIX_BSON\" /out/phoenix/client/" "$(cat "$ctx/Dockerfile")"
  # RQ-128: The artifact check above confirms that async-profiler and jHiccup are staged; verify
  # that the image installs them at their well-known paths and that the image build validates those
  # paths.
  text="$(cat "$ctx/Dockerfile")"
  bad=""
  for line in 'ARG PX_STAGED_JHICCUP=' 'test -x /out/px-tools/async-profiler/bin/asprof' \
    "cp \"/stage/\$PX_STAGED_JHICCUP\" /out/px-tools/jhiccup/jHiccup.jar" \
    'COPY --from=unpack /out/ /opt/' 'PX_TOOLS_DIR=/opt/px-tools'; do
    case "$text" in
    *"$line"*) ;;
    *) bad="$bad [$line]" ;;
    esac
  done
  t_empty image_installs_the_tools_at_known_paths "$bad"
  mv "$T_A2_OUT/build/jhiccup/jHiccup.jar" "$T_A2_OUT/build/jhiccup/away.jar"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  mv "$T_A2_OUT/build/jhiccup/away.jar" "$T_A2_OUT/build/jhiccup/jHiccup.jar"
  t_equals stage_artifacts_requires_the_jhiccup_jar "1 yes" "$T_STATUS $(t_flag \
    'run build-jhiccup.sh, or drop jhiccup from PX_DIST_ARTIFACTS' "$T_STDERR")"
  # RQ-094: Any enabled plug-in is staged into the build context and copied by the image
  # definition, whereas disabled plug-ins are omitted.
  t_equals stage_artifacts_without_a_plugin_stages_none "yes no" \
    "$(t_exists "$ctx/plugins") $(t_exists "$ctx/plugins/example")"
  t_a2 "${pins_env[@]}" PX_PLUGINS=example "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_stages_an_enabled_plugin "0 yes yes" "$T_STATUS $(t_exists \
    "$ctx/plugins/example/plugin.env") $(t_exists "$ctx/plugins/example/hooks/fetch-data")"
  t_equals stage_artifacts_keeps_the_hook_executable yes \
    "$([ -x "$ctx/plugins/example/hooks/fetch-data" ] && echo yes || echo no)"
  t_contains image_copies_the_staged_plugins 'COPY plugins/ /opt/px/plugins/' \
    "$(cat "$ctx/Dockerfile")"
  t_contains image_context_admits_the_plugins '!plugins/' "$(cat "$ctx/.dockerignore")"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_drops_a_plugin_that_is_no_longer_enabled no \
    "$(t_exists "$ctx/plugins/example")"
  # The bundled ZooKeeper and Hadoop client versions are extracted from the actual jar entries in
  # the archive rather than matched from similarly named files.
  t_equals staging_record_reads_the_shipped_jars "2.6 2.6.4 2.6.7 3.4.3 3.8.6 3.4.1" \
    "$(t_tsv_values "$ctx.staged.tsv" hbase_profile compat_module hbase_version hadoop_version \
      zookeeper_version hbase_hadoop_version)"
  t_shim_log_reset
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa"
  t_equals stage_artifacts_builds_through_contract "0 $tag" \
    "$T_STATUS $(px_tsv_field 1 "$T_STDOUT")"
  t_equals image_build_reports_the_image_id sha256:f00dfeed0001 "$(px_tsv_field 2 "$T_STDOUT")"
  text="$(cat "$T_SHIM_LOG")"
  t_contains image_build_runs_in_the_backend "build --tag $tag" "$text"
  t_contains image_build_passes_the_host_uid "--build-arg PX_UID=$(id -u) " "$text"
  t_contains image_build_passes_the_host_gid "--build-arg PX_GID=$(id -g) " "$text"
  bad=""
  for line in PX_HBASE_VERSION=2.6.7 PX_HADOOP_VERSION=3.4.3 PX_ZOOKEEPER_VERSION=3.8.6 \
    PX_HBASE_HADOOP_VERSION=3.4.1 PX_PHOENIX_HBASE_PROFILE=2.6; do
    case "$text " in
    *"--build-arg $line "*) ;;
    *) bad="$bad $line" ;;
    esac
  done
  t_empty image_build_passes_the_version_set "$bad"
  t_contains image_build_passes_the_digest_of_the_arch \
    "--build-arg PX_IMAGE_BASE_JDK17_SHA256=$(printf '7%.0s' $(seq 1 64)) " "$text"
  t_contains image_build_passes_the_staged_paths \
    '--build-arg PX_STAGED_HBASE=dist/hbase-2.6.7-hadoop3-bin.tar.gz' "$text"
  t_contains image_build_passes_the_staged_jhiccup \
    '--build-arg PX_STAGED_JHICCUP=dist/jHiccup.jar' "$text"
  t_contains image_build_passes_the_staged_bson \
    '--build-arg PX_STAGED_PHOENIX_BSON=phoenix/bson-5.3.1.jar' "$text"
  t_contains image_build_names_the_context "--file $ctx/Dockerfile $ctx" "$text"
  t_absent image_build_defaults_to_the_host_architecture '--platform' "$text"
  # R-066: When the container builder supports the provenance flag, image builds disable provenance
  # attestations to keep the resulting image ID deterministic; builders lacking that option receive
  # no provenance flag.
  : >"$T_A2_STATE/build-provenance"
  t_shim_log_reset
  t_a2 "$T_BASH" "$T_WORK/probe/sb.sh" sb_image_build "$ctx" px-harness:x
  t_contains image_build_turns_the_provenance_off '--provenance=false' "$(cat "$T_SHIM_LOG")"
  rm -f "$T_A2_STATE/build-provenance"
  t_shim_log_reset
  t_a2 "$T_BASH" "$T_WORK/probe/sb.sh" sb_image_build "$ctx" px-harness:x
  t_absent image_build_without_the_option_passes_none '--provenance' "$(cat "$T_SHIM_LOG")"
  # Rendering a deployment for a staged image records the component versions packaged into that
  # image, whereas a staging record from a mismatched version set causes the render to be refused.
  t_gen --class validation --cluster staged
  t_equals topology_records_the_shipped_jars "2.6 2.6.7 3.4.3 3.8.6 3.4.1" \
    "$(t_env_value "$T_A2_OUT/deploy/staged/topology.env" PX_PHOENIX_HBASE_PROFILE \
      PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION)"
  t_gen --class validation --check -e "PX_IMAGE_TAG=$tag" -e PX_DIST_HBASE_VERSION=2.6.6
  t_equals render_refuses_the_image_of_another_version_set "2 yes" \
    "$T_STATUS $(t_flag "was staged with hbase_version '2.6.7'" "$T_STDERR")"
  : >"$T_A2_STATE/build-fails"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa"
  t_equals image_build_failure_is_failure 1 "$T_STATUS"
  rm -f "$T_A2_STATE/build-fails"
  # When a base image has no pre-declared SHA-256 digest, trust on first use records the digest
  # resolved by the container backend, and subsequent image builds reuse that recorded digest.
  line="$(printf 'b%.0s' $(seq 1 64))"
  : >"$T_A2_STATE/no-manifest"
  t_a2 "${pins_env[@]:0:2}" "${pins_env[@]:3}" "$T_BASH" "$sa"
  t_equals base_digest_unresolved_is_a_presence_failure "1 yes" \
    "$T_STATUS $(t_flag 'build argument PX_IMAGE_BASE_JDK21_SHA256' "$T_STDERR")"
  rm -f "$T_A2_STATE/no-manifest"
  t_shim_log_reset
  t_a2 "${pins_env[@]:0:2}" "${pins_env[@]:3}" "$T_BASH" "$sa"
  t_equals base_digest_trust_on_first_use "0 yes" \
    "$T_STATUS $(t_flag 'trust on first use: the base image eclipse-temurin:21' "$T_STDERR")"
  t_equals base_digest_is_recorded "sha256:$line" \
    "$(t_env_value "$T_A2_OUT/images/pins.env" PX_IMAGE_BASE_JDK21_SHA256_ARM64)"
  t_contains base_digest_reaches_the_build "--build-arg PX_IMAGE_BASE_JDK21_SHA256=$line " \
    "$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_a2 "${pins_env[@]:0:2}" "${pins_env[@]:3}" "$T_BASH" "$sa"
  t_equals base_digest_record_is_reused "0 no" \
    "$T_STATUS $(t_flag 'buildx' "$(cat "$T_SHIM_LOG")")"
  t_a2 "$T_BASH" "$T_WORK/probe/sb.sh" sb_image_build "$T_A2/nowhere" px-harness:x
  t_equals image_build_without_a_context_is_usage 2 "$T_STATUS"
  bad=""
  for line in reg.io:5000/x:t ghcr.io/x:t docker.io/library/x:t; do
    t_a2 "$T_BASH" "$T_WORK/probe/sb.sh" sb_image_build "$ctx" "$line"
    [ "$T_STATUS" = 2 ] || bad="$bad sb_image_build $line gave $T_STATUS"
    t_a2 "${pins_env[@]}" "PX_IMAGE_TAG=$line" "$T_BASH" "$sa" --stage-only
    [ "$T_STATUS" = 2 ] || bad="$bad stage-artifacts $line gave $T_STATUS"
  done
  t_empty image_build_refuses_a_registry_tag "$bad"
  t_a2 "$T_BASH" "$T_WORK/probe/sb.sh" sb_image_build "$ctx" px-harness:x 'not an arg'
  t_equals image_build_refuses_a_malformed_argument 2 "$T_STATUS"
  # Verify that each pre-staging validation check independently detects and reports its respective
  # defect.
  printf 'x\n' >"$tree/phoenix-server/src/main/Changed.java.txt"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_freshness "1 yes" "$T_STATUS $(t_flag 'freshness:' "$T_STDERR")"
  rm -f "$tree/phoenix-server/src/main/Changed.java.txt"
  t_a2 "${pins_env[@]}" PX_PHOENIX_HBASE_PROFILE=2.6.0 PX_DIST_HBASE_VERSION=2.6.7 "$T_BASH" \
    "$sa" --stage-only
  t_equals stage_artifacts_refuses_a_release_outside_the_range "2 yes" \
    "$T_STATUS $(t_flag "HBase '2.6.7' (PX_DIST_HBASE_VERSION) is outside the range" "$T_STDERR")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.5 "$T_BASH" "$bp" --tree "$tree"
  t_a2 "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_refuses_the_build_of_another_profile "1 yes" \
    "$T_STATUS $(t_flag 'the build record is of the HBase profile 2.5' "$T_STDERR")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  t_curl_file "$archive/hbase/2.6.5/hbase-2.6.5-hadoop3-bin.tar.gz" \
    "$T_A2/dist-src/hbase-2.6.5.tar.gz"
  t_a2 "${pins_env[@]}" PX_DIST_HBASE_VERSION=2.6.5 "$T_BASH" "$sa" --stage-only
  t_equals stage_artifacts_reads_one_jar_of_each "1 yes yes" "$T_STATUS $(t_flag \
    'ships no lib/zookeeper-<release>.jar' "$T_STDERR") $(t_flag \
    'ships more than one lib/hadoop-common-<release>.jar' "$T_STDERR")"
  t_a2 "${pins_env[@]}" PX_FAKE_UNAME_M=x86_64 "$T_BASH" "$sa" --stage-only
  t_contains stage_artifacts_amd64_is_best_effort 'amd64, which is best effort' "$T_STDERR"
  # Verify end-to-end version re-pinning: a local version override file selects an alternate HBase
  # profile, HBase release, and Hadoop release. The Phoenix build, deployment rendering, and
  # artifact retrieval all honor the override, and any release without a declared pin uses trust on
  # first use.
  printf '%s\n' PX_PHOENIX_HBASE_PROFILE=2.5 PX_DIST_HBASE_VERSION=2.5.15 \
    PX_DIST_HADOOP_VERSION=3.4.2 >"$local_env"
  t_curl_file "$archive/hbase/2.5.15/hbase-2.5.15-hadoop3-bin.tar.gz" \
    "$T_A2/dist-src/hbase-2.5.15.tar.gz"
  t_curl_file "$archive/hadoop/common/hadoop-3.4.2/hadoop-3.4.2-aarch64-lean.tar.gz" \
    "$T_A2/dist-src/hadoop-3.4.2.tar.gz"
  t_a2 "PX_VERSIONS_LOCAL_ENV=$local_env" "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  t_equals repin_reaches_the_build_record "0 2.5 2.5.4" \
    "$T_STATUS $(t_build_record hbase_profile compat_module)"
  t_a2 "PX_VERSIONS_LOCAL_ENV=$local_env" "${pins_env[@]}" "$T_BASH" "$sa" --stage-only
  t_equals repin_is_trust_on_first_use "0 yes yes" "$T_STATUS $(t_flag \
    'trust on first use: hbase (arm64)' "$T_STDERR") $(t_flag \
    'trust on first use: hadoop (arm64)' "$T_STDERR")"
  t_contains repin_records_the_pin_of_its_release PX_DIST_HBASE_2_5_15_SHA512_ARM64= \
    "$(cat "$pins")"
  t_a2 "PX_VERSIONS_LOCAL_ENV=$local_env" "$T_BASH" "$T_GEN" --class validation --cluster repin
  t_equals repin_reaches_the_render \
    "2.5 2.5.15 3.4.2 3.8.4 3.3.6 px-harness:hbase-2.5.15-hadoop-3.4.2-profile-2.5" \
    "$(t_env_value "$T_A2_OUT/deploy/repin/topology.env" PX_PHOENIX_HBASE_PROFILE \
      PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION \
      PX_IMAGE_TAG)"
  t_a2 "PX_VERSIONS_LOCAL_ENV=$local_env" "$T_BASH" "$T_BIN/px-cluster" config
  t_contains repin_origin_is_the_local_version_override \
    "$(t_rows 3 PX_DIST_HBASE_VERSION 2.5.15 versions-local)" "$T_STDOUT"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  # Verify the stray-file staging check against a copy of the harness whose image directory holds
  # an editor backup file, a hidden file, and a symbolic link.
  cp -R "$PX_HOME" "$T_A2/harness"
  : >"$T_A2/harness/$PX_DEPLOY_SUBDIR/image/conf/x.tmpl~"
  : >"$T_A2/harness/$PX_DEPLOY_SUBDIR/image/.DS_Store"
  ln -s ../entrypoint.sh "$T_A2/harness/$PX_DEPLOY_SUBDIR/image/lib/link.sh"
  t_a2 "PX_HOME=$T_A2/harness" "${pins_env[@]}" "$T_BASH" \
    "$T_A2/harness/scripts/stage-artifacts.sh" --stage-only
  t_equals stage_artifacts_strays "1 3" \
    "$T_STATUS $(printf '%s\n' "$T_STDERR" | grep -c 'staging check failed: strays:')"
  # Verify build-jhiccup.sh: the dedicated retrieval and build flow for jHiccup (R-024).
  url='https://github.com/giltene/jHiccup/archive/refs/tags/jHiccup-2.0.10.tar.gz'
  t_curl_file "$url" "$T_A2/dist-src/jHiccup-2.0.10.tar.gz"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$PX_HOME/scripts/build-jhiccup.sh"
  t_equals build_jhiccup_needs_the_build_product "1 yes" \
    "$T_STATUS $(t_flag 'made no target/jHiccup.jar' "$T_STDERR")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" \
    "$PX_HOME/scripts/build-jhiccup.sh"
  t_outcome build_jhiccup_records_the_tool 0 \
    "$(t_rows 2 jar "$T_A2_OUT/build/jhiccup/jHiccup.jar")"
  # Verify A7: javac 11 and later reject the Java 1.6 language level configured in the release.
  t_equals build_jhiccup_sets_language_level_1_8 \
    '<project><source>1.8</source><target>1.8</target></project>' \
    "$(cat "$T_A2_OUT/build/jhiccup/src/pom.xml")"
  t_contains build_jhiccup_pins_its_source "PX_DIST_JHICCUP_2_0_10_SHA512_ARM64=" "$(cat "$pins")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_PRODUCT=target/jHiccup.jar \
    "PX_DIST_JHICCUP_2_0_10_SHA512_ARM64=$(printf 'b%.0s' $(seq 1 128))" "$T_BASH" \
    "$PX_HOME/scripts/build-jhiccup.sh"
  t_equals build_jhiccup_verifies_a_declared_pin 1 "$T_STATUS"
}

## @description  Extracts the lines of a single service block (file, service) from a Compose file.
## @return       Writes the service block lines to stdout. Exit 0.
t_d6_service() {
  awk -v s="  $2:" '$0 == s { f = 1; next } f && /^(  )?[a-z]/ { f = 0 } f' "$1"
}

## @description  Extracts the first RUN instruction following the FROM ... AS runtime line (file).
## @return       Writes the matching instruction lines to stdout. Exit 0.
t_d6_first_runtime_run() {
  awk '/^FROM .* AS runtime$/ { r = 1; next }
    r && !f && /^RUN / { f = 1 }
    f { print; if ($0 !~ /\\$/) exit }' "$1"
}

# shellcheck disable=SC2016 # The patterns hold the literal ${NAME} text of the files.
## @description  Verifies the Distributed Pherf container image: its image definition, standalone
##               Compose deployment, health probe, and --image pherf staging workflow (round D6).
## @return       Records the Distributed Pherf image assertions. Exit 0.
t_group_d6() {
  local dir="$PX_HOME/$PX_DEPLOY_SUBDIR/pherf" df compose hc tree="$T_A2/tree" bad="" line text
  local sa="$PX_HOME/scripts/stage-artifacts.sh" bp="$PX_HOME/scripts/build-phoenix.sh"
  local jdk="$T_A2/jdk/17" module="$T_A2/tree/phoenix-pherf-distributed" ctx tag sum jre run
  local versions="$PX_HOME/$PX_DEPLOY_SUBDIR/versions.env" bin="$T_WORK/d6/bin" ctl agent
  local pins=() jdks=()
  df="$dir/Dockerfile"
  compose="$dir/compose.yaml"
  hc="$dir/healthcheck.sh"
  tag=px-pherf:hbase-2.6.7-profile-2.6
  printf '# the Distributed Pherf image and its standalone deployment\n'
  # Verify RQ-246: a single container image built on a pinned JRE base image that validates the
  # presence of jdk.httpserver.
  text="$(grep '^FROM ' "$df" 2>/dev/null)"
  t_equals pherf_dockerfile_froms_pinned "2 " "$(printf '%s\n' "$text" | grep -c \
    '^FROM ${PX_IMAGE_BASE_JRE17}@sha256:${PX_IMAGE_BASE_JRE17_SHA256} AS ') \
$(printf '%s\n' "$text" | grep -v '@sha256:')"
  t_contains pherf_base_is_a_jre '-jre-' "$(t_env_value "$versions" PX_IMAGE_BASE_JRE17)"
  run="$(t_d6_first_runtime_run "$df" 2>/dev/null)"
  bad=""
  for line in '-Xlog:class+load' 'com.sun.net.httpserver.HttpServer' 'jrt:/jdk.httpserver' \
    'lacks the jdk.httpserver module' 'exit 1'; do
    case "$run" in
    *"$line"*) ;;
    *) bad="$bad [$line]" ;;
    esac
  done
  t_empty pherf_dockerfile_proves_httpserver_first "$bad"
  text="$(cat "$df" 2>/dev/null)"
  bad=""
  for line in 'USER ${PX_UID}:${PX_GID}' 'PX_UID must not be 0: the image runs as non-root' \
    'ENTRYPOINT ["/usr/bin/tini", "--", "/opt/pherf/bin/pherf-distributed.sh"]' \
    'CMD ["controller", "start"]' 'COPY healthcheck.sh /opt/pherf/' \
    'install -y --no-install-recommends curl procps tini' \
    'COPY module/bin/ /opt/pherf/bin/' 'COPY module/conf/ /opt/pherf/conf/' \
    'PHERF_RUN_DIR=/var/lib/pherf/run'; do
    case "$text" in
    *"$line"*) ;;
    *) bad="$bad [$line]" ;;
    esac
  done
  t_empty pherf_dockerfile_non_root_entrypoint_and_probe "$bad"
  t_equals pherf_dockerfile_leaves_the_healthcheck_to_the_deployment "0 1" \
    "$(grep -c '^HEALTHCHECK' "$df" 2>/dev/null) $(grep -c '^# No HEALTHCHECK here' "$df" \
      2>/dev/null)"
  # Verify that the class path assembled in the image matches the class path of the harness pherf
  # driver.
  bad=""
  for line in 'client-facing-thirdparty/' 'lib/jdk11/' 'lib/commons-codec-'; do
    case "$text" in *"$line"*) ;; *) bad="$bad image:[$line]" ;; esac
    grep -qF "$line" "$PX_HOME/drivers/lib/driver.sh" || bad="$bad driver:[$line]"
  done
  t_empty pherf_dockerfile_class_path_matches_the_pherf_driver "$bad"
  bad=""
  for line in PX_IMAGE_BASE_JRE17 PX_IMAGE_BASE_JRE17_SHA256 PX_STAGED_HBASE \
    PX_STAGED_PHOENIX_CLIENT PX_STAGED_PHOENIX_PHERF PX_STAGED_PHOENIX_BSON \
    PX_STAGED_PHOENIX_DISTRIBUTED PX_UID PX_GID PX_PHOENIX_VERSION; do
    grep -q "^ARG $line\$" "$df" 2>/dev/null || bad="$bad $line"
  done
  for line in PX_STAGED_HADOOP PX_STAGED_PHOENIX_SERVER PX_STAGED_JHICCUP; do
    grep -q "^ARG $line" "$df" 2>/dev/null && bad="$bad extra:$line"
  done
  t_empty pherf_dockerfile_declares_the_build_arguments "$bad"
  # Verify RQ-245 and RQ-249 through static inspection of the standalone deployment definition.
  ctl="$(t_d6_service "$compose" controller 2>/dev/null)"
  agent="$(t_d6_service "$compose" agent 2>/dev/null)"
  t_equals pherf_compose_memory_limits "1024m 1024m 1536m 1536m 3" \
    "$(printf '%s\n' "$ctl" | sed -n 's/^    mem_limit: //p;s/^    memswap_limit: //p' |
      tr '\n' ' ')$(printf '%s\n' "$agent" |
      sed -n 's/^    mem_limit: //p;s/^    memswap_limit: //p' | tr '\n' ' ')$(printf '%s\n' \
      "$agent" | sed -n 's/^      replicas: //p')"
  t_equals pherf_compose_fleet_is_5632_mib 5632 "$(printf '%s\n%s\n' "$ctl" "$agent" | awk '
    /^  *mem_limit: [0-9]+m$/ { m[n++] = $2 + 0 }
    /^  *replicas: / { r = $2 }
    END { print m[0] + r * m[1] }')"
  line='test: ["CMD", "/opt/pherf/healthcheck.sh",'
  t_equals pherf_compose_healthcheck_per_role "$line \"controller\"] $line \"agent\"]" \
    "$(printf '%s\n' "$ctl" | sed -n 's/^      \(test: .*\)/\1/p') $(printf '%s\n' "$agent" |
      sed -n 's/^      \(test: .*\)/\1/p')"
  t_equals pherf_compose_never_pulls_or_restarts "2 2" \
    "$(grep -c '^    pull_policy: never$' "$compose") $(grep -c '^    restart: "no"$' "$compose")"
  t_equals pherf_compose_publishes_on_loopback_only '- "127.0.0.1:8780:8780"|' \
    "$(printf '%s\n' "$ctl" | awk '/^    ports:/ { p = 1; next } p && /^      - / {
      sub(/^ +/, ""); printf "%s|", $0; next } { p = 0 }')$(printf '%s\n' "$agent" |
      grep -c '^    ports:' | sed 's/^0$//')"
  t_equals pherf_compose_quorum_only_from_the_operator \
    '      PHERF_ZOOKEEPER: "${PHERF_ZOOKEEPER:?set the ZooKeeper quorum of the target cluster}"' \
    "$(grep -v '^ *#' "$compose" | grep 'PHERF_ZOOKEEPER')"
  t_equals pherf_compose_image_only_from_the_variable 2 \
    "$(grep -c '^    image: "${PX_PHERF_IMAGE_TAG:?' "$compose")"
  # Because Docker Compose rejects start_interval on an Engine older than version 25 and the minimum
  # supported Engine floor is older than 25, the deployment must omit start_interval.
  t_equals pherf_compose_has_no_start_interval 0 "$(grep -c 'start_interval' "$compose")"
  t_equals pherf_compose_is_not_a_harness_cluster "1 no" "$(grep -c \
    '^name: px-pherf-standalone$' "$compose" 2>/dev/null) $(t_flag px.harness \
    "$(cat "$compose" 2>/dev/null)")"
  # Exercise the health probe using a fake curl binary and a fake pherf-distributed.sh script.
  mkdir -p "$bin"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>"%s"\nexit "${PX_FAKE_PD_STATUS:-0}"\n' \
    "$T_WORK/d6/pd.log" >"$bin/pherf-distributed.sh"
  chmod +x "$bin/pherf-distributed.sh"
  t_curl_body 'http://127.0.0.1:8780/v1/agents' '{"agents":[]}'
  : >"$T_A2_CURL_LOG"
  t_image -- "$T_BASH" "$hc" controller
  t_equals pherf_probe_controller_healthy "0 yes yes yes" "$T_STATUS $(t_flag \
    'http://127.0.0.1:8780/v1/agents' "$(cat "$T_A2_CURL_LOG")") $(t_flag '--max-time ' \
    "$(cat "$T_A2_CURL_LOG")") $(t_flag '--noproxy ' "$(cat "$T_A2_CURL_LOG")")"
  : >"$T_A2_CURL_LOG"
  t_image PHERF_REST_PORT=9999 -- "$T_BASH" "$hc" controller
  t_equals pherf_probe_controller_unhealthy_on_a_failed_request "22 yes" "$T_STATUS $(t_flag \
    'http://127.0.0.1:9999/v1/agents' "$(cat "$T_A2_CURL_LOG")")"
  : >"$T_A2_CURL_LOG"
  t_image PHERF_BIND_ADDRESS=::1 -- "$T_BASH" "$hc" controller
  t_contains pherf_probe_controller_brackets_an_ipv6_literal 'http://[::1]:8780/v1/agents' \
    "$(cat "$T_A2_CURL_LOG")"
  : >"$T_WORK/d6/pd.log"
  : >"$T_A2_CURL_LOG"
  t_curl_body 'http://controller:8790/v1/channel/' ''
  t_image "PATH=$bin:$T_A2_BIN:$PATH" PHERF_CONTROLLER=http://controller:8790/ -- "$T_BASH" \
    "$hc" agent
  t_equals pherf_probe_agent_runs_the_liveness_verb "0 agent status" \
    "$T_STATUS $(cat "$T_WORK/d6/pd.log")"
  t_equals pherf_probe_agent_reaches_the_channel "yes yes no" "$(t_flag \
    'http://controller:8790/v1/channel/' "$(cat "$T_A2_CURL_LOG")") $(t_flag '--noproxy ' \
    "$(cat "$T_A2_CURL_LOG")") $(t_flag '-f' "$(cat "$T_A2_CURL_LOG")")"
  t_image "PATH=$bin:$T_A2_BIN:$PATH" PHERF_CONTROLLER=http://controller:8790 \
    PX_FAKE_PD_STATUS=3 -- "$T_BASH" "$hc" agent
  t_equals pherf_probe_agent_not_running_is_unhealthy 3 "$T_STATUS"
  t_image "PATH=$bin:$T_A2_BIN:$PATH" PHERF_CONTROLLER=http://controller:8791 -- "$T_BASH" \
    "$hc" agent
  t_equals pherf_probe_agent_unhealthy_without_its_controller 22 "$T_STATUS"
  t_image "PATH=$bin:$T_A2_BIN:$PATH" -- "$T_BASH" "$hc" agent
  t_equals pherf_probe_agent_needs_its_controller "2 yes" "$T_STATUS $(t_flag \
    'PHERF_CONTROLLER is not set' "$T_STDERR")"
  bad=""
  for line in "" bogus "agent extra"; do
    # shellcheck disable=SC2086 # Each line is a word list.
    t_image -- "$T_BASH" "$hc" $line
    [ "$T_STATUS" = 2 ] || bad="$bad [$line] gave $T_STATUS"
  done
  t_empty pherf_probe_usage_is_2 "$bad"
  # Verify the --image pherf artifact staging and image build workflow.
  sum="$(px_sha512 "$T_A2/dist-src/hbase-2.6.7.tar.gz")"
  jre="$(printf 'e%.0s' $(seq 1 64))"
  pins=("PX_DIST_HBASE_2_6_7_SHA512_ARM64=$sum" "PX_DIST_HBASE_2_6_7_SHA512_AMD64=$sum"
    "PX_IMAGE_BASE_JRE17_SHA256_ARM64=sha256:$jre")
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image bogus --stage-only
  t_equals pherf_stage_unknown_image_is_usage "2 yes" "$T_STATUS $(t_flag \
    "the image 'bogus' is not harness or pherf" "$T_STDERR")"
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image pherf --stage-only
  t_equals pherf_stage_needs_the_distributed_jar "1 yes" "$T_STATUS $(t_flag \
    'has no distributed_jar. Build it with px-cluster build -e PX_PHERF=1.' "$T_STDERR")"
  mkdir -p "$module/src/main/bin" "$module/src/main/conf"
  printf '<project/>\n' >"$module/pom.xml"
  printf '#!/usr/bin/env bash\necho fake\n' >"$module/src/main/bin/pherf-distributed.sh"
  printf 'status = warn\n' >"$module/src/main/conf/log4j2.properties"
  touch -t 202001010000 "$module/pom.xml" "$module/src/main/bin/pherf-distributed.sh" \
    "$module/src/main/conf/log4j2.properties"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_PHERF=1
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image pherf -n
  t_equals pherf_stage_dry_run_names_the_image_directory "0 yes yes no" "$T_STATUS $(t_flag \
    "$PX_DEPLOY_SUBDIR/pherf" "$T_STDOUT") $(t_flag "build${PX_TAB}$tag" "$T_STDOUT") \
$(t_flag hadoop "$T_STDOUT")"
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image pherf --stage-only
  t_equals pherf_stage_checks 0 "$T_STATUS"
  ctx="$(px_tsv_field 2 "$T_STDOUT")"
  t_equals pherf_stage_context_is_named_by_the_tag \
    "$T_A2_OUT/images/px-pherf_hbase-2.6.7-profile-2.6" "$ctx"
  t_equals pherf_stage_record_names_no_hadoop "2.6.7 0" "$(t_tsv_values "$ctx.staged.tsv" \
    hbase_version) $(grep -c '^hadoop_version' "$ctx.staged.tsv" 2>/dev/null)"
  bad=""
  for line in Dockerfile healthcheck.sh compose.yaml module/bin/pherf-distributed.sh \
    module/conf/log4j2.properties dist/hbase-2.6.7-hadoop3-bin.tar.gz \
    phoenix/phoenix-client-embedded-hbase-2.6-5.4.0-SNAPSHOT.jar \
    phoenix/phoenix-pherf-5.4.0-SNAPSHOT.jar phoenix/bson-5.3.1.jar \
    phoenix/phoenix-pherf-distributed-5.4.0-SNAPSHOT.jar; do
    [ -f "$ctx/$line" ] || bad="$bad $line"
  done
  t_empty pherf_stage_context_holds_every_part "$bad"
  bad=""
  [ -f "$ctx/Dockerfile" ] || bad="no context"
  for line in phoenix/phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar dist/hadoop-3.4.3-aarch64.tar.gz \
    dist/async-profiler-4.1-linux-arm64.tar.gz dist/jHiccup.jar plugins .dockerignore \
    entrypoint.sh; do
    [ ! -e "$ctx/$line" ] || bad="$bad $line"
  done
  t_empty pherf_stage_context_lacks_the_harness_parts "$bad"
  t_a2 "${pins[@]}" PX_PLUGINS=example "$T_BASH" "$sa" --image pherf --stage-only
  t_equals pherf_stage_takes_no_plugin "0 no" "$T_STATUS $(t_exists "$ctx/plugins")"
  t_a2 "${pins[@]:0:2}" "$T_BASH" "$sa" --image pherf --stage-only
  t_equals pherf_stage_requires_the_jre_digest "1 yes" "$T_STATUS $(t_flag \
    'build argument PX_IMAGE_BASE_JRE17_SHA256, and PX_IMAGE_BASE_JRE17_SHA256_ARM64 holds' \
    "$T_STDERR")"
  mv "$module/src/main/conf/log4j2.properties" "$T_WORK/d6/log4j2.properties"
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image pherf --stage-only
  mv "$T_WORK/d6/log4j2.properties" "$module/src/main/conf/log4j2.properties"
  t_equals pherf_stage_needs_each_module_file "1 yes" "$T_STATUS $(t_flag \
    "presence: $module/src/main/conf/log4j2.properties is absent" "$T_STDERR")"
  t_shim_log_reset
  t_a2 "${pins[@]}" "$T_BASH" "$sa" --image pherf
  t_equals pherf_stage_builds_through_contract "0 $tag" "$T_STATUS $(px_tsv_field 1 "$T_STDOUT")"
  text="$(cat "$T_SHIM_LOG") "
  bad=""
  for line in "build --tag $tag " "--build-arg PX_IMAGE_BASE_JRE17=eclipse-temurin:17-jre-noble " \
    "--build-arg PX_IMAGE_BASE_JRE17_SHA256=$jre " \
    '--build-arg PX_STAGED_HBASE=dist/hbase-2.6.7-hadoop3-bin.tar.gz ' \
    '--build-arg PX_STAGED_PHOENIX_DISTRIBUTED=phoenix/phoenix-pherf-distributed-5.4.0-' \
    "--build-arg PX_UID=$(id -u) " "--build-arg PX_GID=$(id -g) " \
    '--build-arg PX_PHOENIX_VERSION=5.4.0-SNAPSHOT ' "--file $ctx/Dockerfile $ctx"; do
    case "$text" in
    *"$line"*) ;;
    *) bad="$bad [$line]" ;;
    esac
  done
  t_empty pherf_stage_passes_the_build_arguments "$bad"
  t_equals pherf_stage_passes_no_harness_argument "yes no no" "$(t_flag 'build --tag' \
    "$text") $(t_flag PX_IMAGE_BASE_JDK "$text") $(t_flag PX_STAGED_HADOOP "$text")"
  t_shim_log_reset
  t_a2 "${pins[@]:0:2}" "$T_BASH" "$sa" --image pherf
  t_equals pherf_jre_digest_trust_on_first_use "0 yes sha256:$(printf 'b%.0s' $(seq 1 64))" \
    "$T_STATUS $(t_flag 'trust on first use: the base image eclipse-temurin:17-jre-noble' \
      "$T_STDERR") $(t_env_value "$T_A2_OUT/images/pins.env" PX_IMAGE_BASE_JRE17_SHA256_ARM64)"
  t_a2 "$T_BASH" "$T_BIN/px-cluster" config
  t_contains pherf_image_tag_is_derived "$(t_rows 3 PX_PHERF_IMAGE_TAG "$tag" derived)" \
    "$T_STDOUT"
  # Verify that the harness image remains the default staging target when --image is omitted.
  jdks=("PX_IMAGE_BASE_JDK11_SHA256_ARM64=$(printf '1%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK17_SHA256_ARM64=$(printf '7%.0s' $(seq 1 64))")
  t_a2 "${pins[@]:0:2}" "${jdks[@]}" "PX_DIST_HADOOP_3_4_3_SHA512_ARM64=$(px_sha512 \
    "$T_A2/dist-src/hadoop-3.4.3.tar.gz")" "$T_BASH" "$sa" --stage-only
  ctx="$(px_tsv_field 2 "$T_STDOUT")"
  t_equals pherf_stage_default_is_the_harness_image "0 yes no" "$T_STATUS $(t_exists \
    "$ctx/entrypoint.sh") $(t_exists "$ctx/module")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  rm -rf "$module"
}

## @description  Verifies that each platform helper produces a consistent result when isolated to
##               each underlying utility program individually.
## @return       Records the platform helper assertions. Exit 0.
t_group_a2_platform() {
  local file="$T_A2/sha-abc.txt" want tool dir bad="" tried="" path name
  printf '# platform fallbacks\n'
  # Use the known SHA-512 digest of "abc" from FIPS 180-2 so that the expected value is independent
  # of any helper function.
  printf abc >"$file"
  want=ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a
  want=${want}2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f
  for tool in sha512sum gsha512sum shasum openssl; do
    command -v "$tool" >/dev/null 2>&1 || continue
    dir="$T_A2/only-$tool"
    mkdir -p "$dir"
    ln -sf "$(command -v "$tool")" "$dir/$tool"
    ln -sf "$(command -v tr)" "$dir/tr"
    t_run "$T_BASH" "$T_A2/sha-probe.sh" "$dir" "$file"
    tried="$tried $tool"
    [ "$T_STDOUT" = "$want" ] || bad="$bad $tool gave '$T_STDOUT'"
  done
  [ -n "$tried" ] || bad="no SHA-512 program on this host"
  t_empty platform_fallbacks "$bad"
  mkdir -p "$T_A2/no-sha"
  ln -sf "$(command -v tr)" "$T_A2/no-sha/tr"
  t_run "$T_BASH" "$T_A2/sha-probe.sh" "$T_A2/no-sha" "$file"
  t_equals platform_fallbacks_without_a_program_is_unsupported 5 "$T_STATUS"
  # Verify CPU core count detection via getconf, via sysctl, and when neither tool is available,
  # millisecond timestamp fallback without GNU date, and physical path resolution across a
  # symbolic link.
  mkdir -p "$T_A2/plat/target"
  ln -sf target "$T_A2/plat/link"
  path="$(cd -P "$T_A2/plat/target" && pwd -P)/sub/new"
  bad=""
  for tool in getconf sysctl none; do
    [ "$tool" != sysctl ] || sysctl -n hw.ncpu >/dev/null 2>&1 || continue
    dir="$T_A2/plat/only-$tool"
    mkdir -p "$dir"
    for name in $tool date readlink; do
      ! command -v "$name" >/dev/null 2>&1 || ln -sf "$(command -v "$name")" "$dir/$name"
    done
    want="$(px_nproc)"
    [ "$tool" != none ] || want=1
    t_run "$T_BASH" "$T_A2/platform-probe.sh" "$dir" "$T_A2/plat/link/sub/new"
    [ "$T_STDOUT" = "$want milliseconds $path" ] || bad="$bad $tool gave '$T_STDOUT'"
  done
  t_empty platform_fallbacks_of_the_other_helpers "$bad"
}

## @description  Verifies cluster bring-up from a run class, including preflight checks and safety
##               guards against overwriting active deployments.
## @return       Records the cluster bring-up assertions. Exit 0.
t_group_a2_cluster() {
  local pc="$T_BIN/px-cluster" dir="$T_A2_OUT/deploy/a2" trace_v trace_m text
  local -a words=()
  printf '# the bring-up from a run class\n'
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation render -n --out u3-render-n
  t_equals cluster_render_dry_run_prints_the_service_table "0 8 no" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c .) $(t_exists "$T_A2_OUT/u3-render-n")"
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation render
  t_equals cluster_render "0 $dir" "$T_STATUS $T_STDOUT"
  t_empty cluster_render_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  t_a2 "$T_BASH" "$pc" --cluster a2 render --help
  t_equals cluster_render_help 0 "$T_STATUS"
  # Requirement R-278: single-command cluster bring-up documents the required container image and
  # the exact commands used to build it.
  t_a2 "$T_BASH" "$pc" up --help
  t_contains cluster_up_help_names_the_image_build \
    'The image PX_IMAGE_TAG must exist first; px-cluster build makes it.' \
    "$(printf '%s\n' "$T_STDOUT" | tr -s ' \n' ' ')"
  t_a2 "$T_BASH" "$pc" --cluster a2 up
  t_equals cluster_up_needs_a_class_or_a_directory 2 "$T_STATUS"
  t_a2_provision "$dir"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --dry-run --class validation up
  t_equals cluster_up_dry_run_from_a_class 0 "$T_STATUS"
  t_empty cluster_up_dry_run_from_a_class_mutates_no_runtime "$(grep -E \
    '(^| )(up|create|run|build|rm|start|stop|kill)( |$)' "$T_SHIM_LOG")"
  t_equals up_plan_prints_plan_and_check_rows "yes yes yes yes yes" "$(t_flag \
    "plan${PX_TAB}scope${PX_TAB}sb_scope_create a2" "$T_STDOUT") $(t_flag \
    "cluster${PX_TAB}ok" "$T_STDOUT") $(t_flag "one_cluster${PX_TAB}ok" "$T_STDOUT") $(t_flag \
    "image${PX_TAB}ok" "$T_STDOUT") $(t_flag "memory_fit${PX_TAB}ok" "$T_STDOUT")"
  t_equals up_dry_run_ends_with_a_result_row "$(t_rows 4 result ok 0 a2) 1" \
    "$(t_last_line "$T_STDOUT") $(printf '%s\n' "$T_STDOUT" | grep -c "^result$PX_TAB")"
  t_equals up_plan_creates_nothing "no no no 0" "$(t_exists "$T_A2_OUT/state/scope-compose-a2")\
 $(t_exists "$T_A2_OUT/state/cluster-a2.env") $(t_flag ' up --no-start' "$(cat \
    "$T_SHIM_LOG")") $(find "$T_A2_OUT/plan" -type f 2>/dev/null | grep -c .)"
  printf '%s\n' "$((6 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_a2 "$T_BASH" "$pc" --cluster a2 --dry-run --class validation up
  t_equals up_plan_refused_memory_is_3 "3 yes" "$T_STATUS $(t_flag "memory_fit${PX_TAB}fail" \
    "$T_STDOUT")"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  mkdir -p "$T_A2_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$T_A2_OUT/state/px-k3d/scope-k3d-px.tsv"
  t_a2 "$T_BASH" "$pc" --cluster a2 --dry-run --class validation up
  rm -f "$T_A2_OUT/state/px-k3d/scope-k3d-px.tsv"
  t_equals up_plan_beside_a_k3d_cluster_is_3 "3 yes yes" "$T_STATUS $(t_flag \
    "one_cluster${PX_TAB}fail" "$T_STDOUT") $(t_flag "memory_fit${PX_TAB}ok" "$T_STDOUT")"
  t_equals up_dry_run_refusal_ends_with_a_fail_row "$(t_rows 4 result fail 3 a2)" \
    "$(t_last_line "$T_STDOUT")"
  : >"$T_A2_STATE/no-image"
  t_a2 "$T_BASH" "$pc" --cluster a2 --dry-run --class validation up
  rm -f "$T_A2_STATE/no-image"
  t_equals up_plan_missing_image_is_6 "6 yes" "$T_STATUS $(t_flag \
    "image${PX_TAB}fail${PX_TAB}the host holds no image" "$T_STDOUT")"
  t_contains up_plan_missing_image_names_the_build 'run px-cluster build --cluster a2 --class' \
    "$T_STDOUT"
  # Requirement R-278: specifying a run class and a topology configuration file suffices to perform
  # the complete cluster bring-up sequence.
  printf 'PX_RS_COUNT=1\n' >"$T_A2/topology.env"
  # When the required container image is absent on the host, bring-up fails before running
  # preflight checks and reports the two build commands needed to produce the image.
  : >"$T_A2_STATE/no-image"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation --config "$T_A2/topology.env" up
  rm -f "$T_A2_STATE/no-image"
  t_equals missing_image_refuses_the_bring_up "6 yes" "$T_STATUS $(t_flag \
    "run px-cluster build --cluster a2 --class validation --config $T_A2/topology.env" \
    "$T_STDERR")"
  text="$(printf '%s\n' "$T_STDERR" | sed -n 's/.*: run px-cluster build \(.*\)$/\1/p' |
    sed -n 1p)"
  read -r -a words <<<"$text"
  t_a2 "$T_BASH" "$pc" build ${words[@]+"${words[@]}"} -n
  t_equals missing_image_hint_parses "0 yes" "$T_STATUS $(t_flag \
    "plan${PX_TAB}build${PX_TAB}scripts/build-phoenix.sh" "$T_STDOUT")"
  t_absent missing_image_runs_no_preflight 'memory_fit' "$T_STDERR"
  t_absent missing_image_creates_nothing 'up --no-start' "$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation --config "$T_A2/topology.env" up \
    --timeout 5 -e "PX_U3_QUOTED=a 'b' \"c\" # d = e"
  t_equals single_bring_up_from_class_and_topology 0 "$T_STATUS"
  t_contains single_bring_up_runs_the_preflight "memory_fit${PX_TAB}ok" "$T_STDERR"
  t_contains single_bring_up_provisions_the_render "compose -p px-a2 -f $dir/compose.yaml" \
    "$(cat "$T_SHIM_LOG")"
  t_equals single_bring_up_records_the_deployment "$dir" \
    "$(cat "$T_A2_OUT/state/deployment-compose-a2" 2>/dev/null)"
  t_a2_record "$dir"
  # Because rendering overwrites the files in the target deployment directory, neither a second
  # bring-up nor a direct render call may modify the deployment directory of a cluster that is up.
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation up
  t_equals cluster_up_refuses_to_render_over_a_cluster_that_is_up "2 yes" \
    "$T_STATUS $(t_flag 'run px-cluster down first' "$T_STDERR")"
  t_equals up_refuses_an_existing_record "yes no" "$(t_flag \
    "recorded in $T_A2_OUT/state/cluster-a2.env" "$T_STDERR") $(t_flag 'PX_FORCE=1' "$T_STDERR")"
  # Setting PX_FORCE=1 marks a cluster record as stale only when its scope is gone, and never when
  # the cluster is currently up.
  text="$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation -e PX_FORCE=1 up --no-wait
  t_equals up_force_refuses_a_cluster_that_is_up "2 yes yes no" "$T_STATUS $(t_flag \
    'is up already' "$T_STDERR") $([ "$text" = "$(cat "$T_A2_OUT/state/cluster-a2.env" \
    2>/dev/null)" ] && echo yes || echo no) $(t_flag ' up --no-start' "$(cat "$T_SHIM_LOG")")"
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation up "$dir" --no-wait
  t_equals up_of_a_directory_refuses_an_existing_record 2 "$T_STATUS"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation render
  t_equals cluster_render_refuses_the_deployment_of_a_cluster_that_is_up 2 "$T_STATUS"
  t_empty cluster_refused_render_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  t_a2_scale "$dir"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 down
  t_equals cluster_down_after_a_class_bring_up 0 "$T_STATUS"
  t_equals cluster_down_removes_the_deployment_record no \
    "$(t_exists "$T_A2_OUT/state/deployment-compose-a2")"
  t_a2_record_stale "$dir"
  # Verify that the host preflight rejects the full measurement topology on the reference host's
  # engine memory budget before creating any container instances.
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class measurement up
  t_equals cluster_up_refuses_measurement_on_this_engine 3 "$T_STATUS"
  t_absent cluster_up_refusal_creates_nothing 'up --no-start' "$(cat "$T_SHIM_LOG")"
  t_contains cluster_up_refusal_shows_the_arithmetic 'budget 19905 MiB (19.44 GiB)' "$T_STDERR"
  # The memory preflight check runs again against the recorded deployment directory before starting
  # an on-demand service group: if the engine memory allocation has shrunk, starting the YARN group
  # is refused.
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_gen --class measurement --cluster a2
  t_a2_provision "$dir"
  # Both preflight checks execute in a child process. A decoy runtime binary placed early on PATH
  # must never be invoked, because the check must query the engine designated by PX_DOCKER_BIN.
  : >"$T_A2/decoy.log"
  t_a2 "PATH=$T_A2/decoy:$T_A2_BIN:$PATH" "$T_BASH" "$pc" --cluster a2 --class measurement up \
    --no-wait
  t_equals cluster_up_measurement_on_a_large_engine 0 "$T_STATUS"
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  t_a2 "PATH=$T_A2/decoy:$T_A2_BIN:$PATH" "$T_BASH" "$pc" --cluster a2 group start yarn \
    --timeout 5
  t_equals memory_recheck_runs_preflight 3 "$T_STATUS"
  t_contains memory_recheck_refuses_the_group 'the memory check refused the start of group yarn' \
    "$T_STDERR"
  t_empty preflight_child_uses_the_engine_of_the_parent "$(cat "$T_A2/decoy.log")"
  # Because the cluster record preserves the run class from bring-up, invoking scale without --class
  # re-renders the measurement deployment and leaves all other instances unchanged.
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_a2 "$T_BASH" "$pc" --cluster a2 scale regionserver 4 --timeout 5
  t_equals scale_keeps_the_class "0 measurement" "$T_STATUS $(t_env_value "$dir/topology.env" \
    PX_RUN_CLASS)"
  # Verify that the memory recheck fails closed if the preflight script is missing or not
  # executable.
  rm -f "$T_A2/harness/scripts/preflight.sh"
  t_a2 "PX_HOME=$T_A2/harness" "$T_BASH" "$T_WORK/probe/memory-recheck.sh" "$T_A2/harness"
  t_equals memory_recheck_without_its_script_is_failure "status 1 yes" \
    "$T_STDOUT $(t_flag 'is missing or not executable' "$T_STDERR")"
  t_a2 "$T_BASH" "$pc" --cluster a2 down
  # Verify that both the validation and measurement run classes execute the exact same code path,
  # differing only in their parameter values.
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  trace_v="$(t_a2_trace validation)"
  trace_m="$(t_a2_trace measurement)"
  t_nonempty class_switch_trace_is_recorded "$trace_v"
  t_equals class_switch_changes_no_script_path "$trace_v" "$trace_m"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

## @description  Verifies px-cluster scale on the rendered deployment (dir) of the a2 cluster (up
##               with one RegionServer): re-renders per scale and mutates only the changed instance.
## @return       Records assertions. Exit 0.
t_a2_scale() {
  local dir=$1 pc="$T_BIN/px-cluster" before
  local -a a2=(--cluster a2)
  # RQ-008, RQ-009: Re-rendering records the updated instance count, and the backend creates and
  # starts only the single newly added instance.
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" "${a2[@]}" scale regionserver 2 --timeout 5
  t_equals scale_adds_an_instance_to_a_render 0 "$T_STATUS"
  t_equals scale_renders_the_new_count 2 \
    "$(awk -F '\t' '$2 == "regionserver"' "$dir/services.tsv" | grep -c .)"
  t_contains scale_writes_the_count "PX_RS_COUNT='2'" \
    "$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)"
  t_called scale_starts_only_the_new_instance \
    'compose -p px-a2 up -d --no-recreate --no-build --no-deps regionserver-1'
  t_contains scale_reports_the_new_instance 'regionserver 1 new-regionserver-1 running ready' \
    "$(t_squeeze "$T_STDOUT")"
  t_contains scale_is_planned 'count 2' "$(cat "$T_A2_OUT/state/planned-px-a2.tsv" 2>/dev/null)"
  t_equals scale_keeps_no_copy_of_the_render no "$(t_exists "$dir.before-scale")"
  # Re-rendering with modified configuration parameters would diverge from the running instances,
  # and the memory preflight check validates the newly rendered topology: failures in either check
  # refuse the scale operation and leave the existing deployment intact.
  before="$(cat "$dir/services.tsv")"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" "${a2[@]}" -e PX_DN_COUNT=2 scale regionserver 3 --timeout 5
  t_equals scale_refuses_a_render_that_changes_another_instance "2 yes" \
    "$T_STATUS $(t_flag 'changes instances other than regionserver' "$T_STDERR")"
  t_contains scale_refuses_drift_from_the_record \
    "differs from the cluster record $T_A2_OUT/state/cluster-a2.env, so drop it" "$T_STDERR"
  printf '%s\n' "$((6 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_a2 "$T_BASH" "$pc" "${a2[@]}" scale regionserver 3 --timeout 5
  t_equals scale_refuses_what_the_memory_check_refuses 3 "$T_STATUS"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_equals scale_refusals_keep_the_deployment "$before" "$(cat "$dir/services.tsv")"
  t_absent scale_refusals_change_no_instance ' up ' "$(cat "$T_SHIM_LOG")"
  # Scaling back down stops and removes only the single instance exceeding the target count.
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" "${a2[@]}" scale regionserver 1 --timeout 5
  t_equals scale_removes_an_instance 0 "$T_STATUS"
  t_called scale_stops_the_removed_instance 'stop --time 120 new-regionserver-1'
  t_called scale_removes_the_removed_instance 'rm -f new-regionserver-1'
  t_contains scale_writes_the_lower_count "PX_RS_COUNT='1'" \
    "$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)"
  t_a2 "$T_BASH" "$pc" --cluster a2 status regionserver
  t_equals scale_leaves_the_other_instances "regionserver 0" \
    "$(printf '%s\n' "$T_STDOUT" | awk 'NR > 1 { print $1, $2 }')"
  t_a2 "$T_BASH" "$pc" --cluster a2 scale datanode 10
  t_equals scale_refuses_a_count_above_nine 2 "$T_STATUS"
}

## @description  Verifies the cluster record written during class-based bring-up of the a2 cluster,
##               its reported origin, and its precedence among the configuration stages.
## @return       Records test assertions. Exit 0.
t_a2_record() {
  local pc="$T_BIN/px-cluster" text out="$T_A2/u3-out"
  text="$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)"
  t_equals record_holds_the_backend_and_class "yes yes yes" "$(t_flag "PX_BACKEND='compose'" \
    "$text") $(t_flag "PX_RUN_CLASS='validation'" "$text") $(t_flag "PX_RS_COUNT='1'" "$text")"
  t_empty record_leaves_out_the_locating_names "$(printf '%s\n' "$text" |
    grep -E '^(PX_OUT|PX_STATE_DIR|PX_CLUSTER_ID)=')"
  t_a2 "$T_BASH" "$pc" --cluster a2 config
  t_equals record_values_have_origin_cluster "yes yes" "$(t_flag \
    "PX_RS_COUNT${PX_TAB}1${PX_TAB}cluster" "$T_STDOUT") $(t_flag \
    "PX_RUN_CLASS${PX_TAB}validation${PX_TAB}cluster" "$T_STDOUT")"
  t_contains record_round_trips_a_quoted_value \
    "PX_U3_QUOTED${PX_TAB}a 'b' \"c\" # d = e${PX_TAB}cluster" "$T_STDOUT"
  printf 'PX_RS_COUNT=3\n' >"$T_A2/topology.env"
  t_a2 "$T_BASH" "$pc" --cluster a2 config PX_RS_COUNT
  printf 'PX_RS_COUNT=1\n' >"$T_A2/topology.env"
  t_outcome record_is_flattened 0 "$(t_rows 3 PX_RS_COUNT 1 cluster)"
  t_a2 "$T_BASH" "$pc" --cluster a2 config PX_RS_COUNT -e PX_RS_COUNT=4
  t_outcome cli_beats_the_record 0 "$(t_rows 3 PX_RS_COUNT 4 cli)"
  t_a2 PX_RS_COUNT=5 "$T_BASH" "$pc" --cluster a2 config PX_RS_COUNT
  t_outcome environment_beats_the_record 0 "$(t_rows 3 PX_RS_COUNT 5 environment)"
  printf 'PX_RS_COUNT=7\nPX_U3_LOCAL=1\n' >"$T_A2/u3-local.env"
  t_a2 "PX_LOCAL_ENV=$T_A2/u3-local.env" "$T_BASH" "$pc" --cluster a2 config PX_RS_COUNT \
    PX_U3_LOCAL
  t_outcome record_beats_local_env 0 "$(t_rows 3 PX_RS_COUNT 1 cluster PX_U3_LOCAL 1 local)"
  # An output root specified solely in local.env still suffices to locate the cluster record.
  mkdir -p "$out/state"
  printf "PX_U3_SEEN='1'\n" >"$out/state/cluster-u3l.env"
  printf 'PX_OUT=%s\n' "$out" >"$T_A2/u3-local-out.env"
  t_a2 env -u PX_OUT "PX_LOCAL_ENV=$T_A2/u3-local-out.env" "$T_BASH" "$pc" --cluster u3l \
    config PX_U3_SEEN
  t_outcome record_located_through_local_env_out 0 "$(t_rows 3 PX_U3_SEEN 1 cluster)"
  rm -rf "$out"
  t_a2 "$T_BASH" "$pc" --cluster a2 config 'PX_RS_*' PX_RUN_CLASS
  t_equals config_pattern_selects_names "0 yes" "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk \
    -F '\t' '$1 !~ /^PX_RS_/ && $1 != "PX_RUN_CLASS" { bad = 1 } $1 == "PX_RUN_CLASS" { c = 1 }
      $1 == "PX_RS_COUNT" { r = 1 } END { print (!bad && c && r) ? "yes" : "no" }')"
  t_a2 "$T_BASH" "$pc" --cluster a2 config 'PX_NO_SUCH_*'
  t_equals config_pattern_without_match_is_6 "6 yes" "$T_STATUS $(t_flag \
    'no configuration value matches PX_NO_SUCH_*' "$T_STDERR")"
  t_a2 "$T_BASH" "$pc" --cluster a2 --backend k8s status
  t_equals record_refuses_another_backend "2 yes yes" "$T_STATUS $(t_flag \
    "cluster a2 is recorded on the compose backend in $T_A2_OUT/state/cluster-a2.env" \
    "$T_STDERR") $(t_flag "PX_BACKEND='k8s' comes from the command line" "$T_STDERR")"
  t_a2 PX_BACKEND=k8s "$T_BASH" "$pc" --cluster a2 status
  t_contains record_refusal_names_the_origin "PX_BACKEND='k8s' comes from the environment" \
    "$T_STDERR"
  t_a2 "$T_BASH" "$T_BIN/px-run" --cluster a2 "$T_WORK/drivers/exit-code.sh" -n
  t_contains run_takes_the_class_from_the_record "run_class${PX_TAB}validation" "$T_STDOUT"
}

## @description  Verifies that up rejects a stale a2 cluster record (dir) unless PX_FORCE=1, omits
##               per-invocation settings, and that down cleans up a record lacking an active scope.
## @return       Records test assertions. Exit 0.
t_a2_record_stale() {
  local dir=$1 pc="$T_BIN/px-cluster" file="$T_A2_OUT/state/cluster-a2.env" text name bad=""
  printf "# stale\nPX_RUN_CLASS='measurement'\nPX_U3_STALE='1'\n" >"$file"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation --config "$T_A2/topology.env" up \
    --no-wait
  t_equals up_refuses_a_stale_record "2 no" "$T_STATUS $(t_flag ' up --no-start' \
    "$(cat "$T_SHIM_LOG")")"
  t_a2_provision "$dir"
  # The rendered configuration from the earlier class bring-up includes the undeclared PX_U3_QUOTED
  # setting, which a strict child process of this bring-up would reject if persisted in the record.
  rm -f "$dir/render.env"
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation --config "$T_A2/topology.env" -v \
    --keep --strict -e PX_FORCE=1 -e PX_LOG_FILE=u3-up.log -e "PX_DEPLOYMENT_DIR=$dir" \
    -e PX_DRY_RUN=0 up --no-wait
  text="$(cat "$file" 2>/dev/null)"
  t_equals up_force_replaces_a_stale_record "0 no yes" "$T_STATUS $(t_flag PX_U3_STALE \
    "$text") $(t_flag "PX_RUN_CLASS='validation'" "$text")"
  for name in PX_VERBOSE PX_DRY_RUN PX_STRICT_CONFIG PX_KEEP PX_SCOPE_ID PX_DEPLOYMENT_DIR \
    PX_OUT PX_STATE_DIR PX_CLUSTER_ID PX_FORCE PX_LOG_FILE; do
    case "$PX_NL$text" in
    *"$PX_NL$name="*) bad="$bad $name" ;;
    esac
  done
  t_equals record_holds_no_invocation_name "" "$bad"
  # When the recorded scope is already gone, as after a crash, down still exits with status 0 and
  # removes both state records.
  : >"$T_A2_STATE/instances.txt"
  : >"$T_A2_STATE/networks.txt"
  : >"$T_A2_STATE/volumes.txt"
  t_a2 "$T_BASH" "$pc" --cluster a2 down
  t_equals down_of_a_stale_record_cleans_up "0 no no" "$T_STATUS $(t_exists "$file")\
 $(t_exists "$T_A2_OUT/state/scope-compose-a2")"
}

## @description  Verifies end-to-end version matrix execution of a suite across two version sets.
## @return       Records assertions. Exit 0.
t_group_a2_matrix() {
  local home="$T_A2/tree/dev/harness" a="$T_A2/set-a.env" b="$T_A2/set-b.env"
  local suite="$T_A2/matrix.suite" archive=https://archive.apache.org/dist rows line bad=""
  local n profile release jdk file
  local pins_env=("PX_IMAGE_BASE_JDK11_SHA256_ARM64=$(printf '1%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK17_SHA256_ARM64=$(printf '7%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK21_SHA256_ARM64=$(printf '2%.0s' $(seq 1 64))")
  printf '# the version matrix\n'
  # Place a copy of the harness inside the synthetic fixture repository tree so that building each
  # version set invokes the fake Maven shim against the fixture tree rather than the real working
  # tree.
  rm -rf "$home"
  mkdir -p "$T_A2/tree/dev"
  cp -R "$PX_HOME" "$home"
  t_curl_file "$archive/hbase/2.6.6/hbase-2.6.6-hadoop3-bin.tar.gz" \
    "$T_A2/dist-src/hbase-2.6.6.tar.gz"
  printf '%s\n' PX_DIST_HBASE_VERSION=2.6.6 PX_DIST_HADOOP_VERSION=3.4.2 PX_RS_JDK=17 >"$a"
  printf '%s\n' PX_PHOENIX_HBASE_PROFILE=2.5 PX_DIST_HBASE_VERSION=2.5.15 \
    PX_DIST_HADOOP_VERSION=3.4.2 PX_RS_JDK=21 >"$b"
  # Configure the suite test to invoke a stub driver so that each matrix entry produces a run bundle
  # whose manifest records that entry's version pins.
  printf 'suite: selftest-matrix\ntimeout: 60\nclass: validation\n\ntest: one\n' >"$suite"
  printf '  driver: %s\n  args: 0\n' "$T_WORK/drivers/exit-code.sh" >>"$suite"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_gen --class validation --cluster a2
  t_a2_provision "$T_A2_OUT/deploy/a2"
  t_shim_log_reset
  : >"$T_A2_MVN_LOG"
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$T_A2/jdk/17" PX_TIMEOUT_READY=30 "${pins_env[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" --cluster a2 \
    --class validation matrix "$suite" "$a" "$b"
  line="$(printf '%s\n' "$T_STDOUT" | tail -n 1)"
  rows="$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 != "result"')"
  # The terminal result row reports the worst session severity observed across the version sets.
  file="$T_A2_OUT/sessions/$(printf '%s\n' "$rows" | awk -F '\t' '$1 == 1 { print $7 }')"
  t_equals matrix_ends_with_a_result_row "result $(t_tsv_values "$file/summary.tsv" severity) 0\
 yes" "$(printf '%s\n' "$line" | cut -f 1-3 | tr '\t' ' ') $(t_exists "$(printf '%s\n' \
    "$line" | cut -f 4)/matrix.tsv")"
  t_equals matrix_runs_every_set 0 "$T_STATUS"
  t_equals matrix_brings_each_set_up_and_down "2 2" \
    "$(grep -c ' up --no-start' "$T_SHIM_LOG") $(grep -c ' down --volumes' "$T_SHIM_LOG")"
  t_equals matrix_builds_the_profile_of_each_set "2.6 2.5" \
    "$(sed -n 's/.*-Dhbase.profile=\([^ ]*\).*/\1/p' "$T_A2_MVN_LOG" | tr '\n' ' ' | sed 's/ $//')"
  t_equals matrix_reports_each_set "1 2.6 2.6.6 3.4.2 down 0|2 2.5 2.5.15 3.4.2 down 0" \
    "$(printf '%s\n' "$rows" | awk -F '\t' '{ printf "%s%s %s %s %s %s %s", sep, $1, $3, $4, \
      $5, $8, $9; sep = "|" }')"
  t_equals matrix_records_each_set "$rows" \
    "$(cat "$(find "$T_A2_OUT/matrix" -name matrix.tsv 2>/dev/null | sed -n 1p)" 2>/dev/null)"
  # Verify that the session summary record for each matrix entry captures its own HBase profile,
  # HBase release version, and per-role JDK pins.
  for line in "1:2.6:2.6.6:PX_RS_JDK=17" "2:2.5:2.5.15:PX_RS_JDK=21"; do
    IFS=: read -r n profile release jdk <<<"$line"
    file="$T_A2_OUT/sessions/$(printf '%s\n' "$rows" | awk -F '\t' -v n="$n" \
      '$1 == n { print $7 }')/summary.tsv"
    case " $(t_tsv_values "$file" hbase_profile hbase_version jdks) " in
    " $profile $release "*" $jdk "*) ;;
    *) bad="$bad set $n" ;;
    esac
  done
  t_empty matrix_session_records_its_version_set "$bad"
  # R-039: The run bundle produced under each matrix entry's session directory records that version
  # set's pins in its bundle manifest.
  bad=""
  for line in "1:2.6 2.6.6 17" "2:2.5 2.5.15 21"; do
    n=${line%%:*}
    file="$(sed -n 1p "$T_A2_OUT/sessions/$(printf '%s\n' "$rows" | awk -F '\t' -v n="$n" \
      '$1 == n { print $7 }')/tests/one/stdout.txt" 2>/dev/null)"
    [ "$(t_manifest "$file" /versions/phoenix_hbase_profile) $(t_manifest "$file" \
      /versions/hbase) $(t_manifest "$file" /versions/jdk/regionserver)" = "${line#*:}" ] ||
      bad="$bad set $n: ${file:-no bundle};"
  done
  t_empty version_sets_are_recorded_in_each_manifest "$bad"
  t_equals matrix_topology_records_the_last_set "2.5 2.5.15 3.4.2 3.8.4 3.3.6" \
    "$(t_env_value "$T_A2_OUT/deploy/a2/topology.env" PX_PHOENIX_HBASE_PROFILE \
      PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION)"
  t_contains matrix_services_run_the_jdk_of_the_set "$(t_rows 9 regionserver-0 regionserver 0)" \
    "$(grep -v '^#' "$T_A2_OUT/deploy/a2/services.tsv" | awk -F '\t' '$9 == 21')"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" matrix "$suite" "$T_A2/no-such.env"
  t_equals matrix_refuses_a_missing_set 6 "$T_STATUS"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" matrix "$suite"
  t_equals matrix_needs_a_set 2 "$T_STATUS"
  # RQ-008: Invoking px-cluster build executes the complete image build pipeline for a single
  # configuration: compiling Phoenix for the active profile, staging the artifacts, and building the
  # container image.
  : >"$T_A2_MVN_LOG"
  t_shim_log_reset
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$T_A2/jdk/17" "${pins_env[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" --config "$b" build
  t_equals cluster_build_builds_phoenix_then_the_image "0 2.5 1" "$T_STATUS $(sed -n \
    's/.*-Dhbase.profile=\([^ ]*\).*/\1/p' "$T_A2_MVN_LOG" | sed -n 1p) $(grep -c '^build --tag' \
    "$T_SHIM_LOG")"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" build extra
  t_equals cluster_build_takes_no_argument 2 "$T_STATUS"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" build --tree "$T_A2/tree" -n
  t_contains build_passes_the_tree \
    "build${PX_TAB}scripts/build-phoenix.sh --tree $T_A2/tree" "$T_STDOUT"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" build --backend k8s -n
  t_contains build_on_k8s_plans_the_import "import${PX_TAB}px-k3d import" "$T_STDOUT"
  # Verify that a build dry run validates the configured version set just like an actual build,
  # exiting with code 2 and emitting no plan rows when a version falls outside its profile range.
  printf 'PX_PHOENIX_HBASE_PROFILE=2.6.0\nPX_DIST_HBASE_VERSION=2.6.7\n' >"$T_A2/bad-range.env"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" --config "$T_A2/bad-range.env" \
    --dry-run build
  t_equals build_dry_run_refuses_a_version_outside_the_profile "2 yes 0 yes" "$T_STATUS $(t_flag \
    "HBase '2.6.7' (PX_DIST_HBASE_VERSION) is outside the range of profile 2.6.0" \
    "$T_STDERR") $(printf '%s\n' "$T_STDOUT" | grep -c '^plan') $(t_flag \
    'build ends (exit 2, usage)' "$(t_last_line "$T_STDERR")")"
  # Delivering a signal to the matrix execution tears down the cluster of the running set.
  printf 'suite: selftest-slow\ntimeout: 60\nclass: validation\n\ntest: one\n' >"$T_A2/slow.suite"
  printf '  driver: %s\n  args: 3\n' "$T_WORK/drivers/sleep.sh" >>"$T_A2/slow.suite"
  t_shim_log_reset
  : >"$T_A2/slow.mark"
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$T_A2/jdk/17" PX_TIMEOUT_READY=30 "${pins_env[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$T_WORK/probe/term-when.sh" \
    "$T_A2_OUT/runs" driver.pid "$T_A2/slow.mark" "$home/bin/px-cluster" --cluster a2 \
    --class validation matrix "$T_A2/slow.suite" "$a"
  t_equals matrix_tears_down_after_a_term "143 1 1" "$T_STATUS $(grep -c ' up --no-start' \
    "$T_SHIM_LOG") $(grep -c ' down --volumes' "$T_SHIM_LOG")"
  # Receiving a signal while a version set is still bringing its cluster up tears down that cluster
  # as well.
  t_shim_log_reset
  : >"$T_A2/slow.mark"
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$T_A2/jdk/17" PX_TIMEOUT_READY=30 "${pins_env[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$T_WORK/probe/term-when.sh" \
    "$T_A2_OUT/state" scope-compose-a2 "$T_A2/slow.mark" "$home/bin/px-cluster" --cluster a2 \
    --class validation matrix "$T_A2/slow.suite" "$a"
  t_equals matrix_tears_down_after_a_term_during_up "143 1 1 no" "$T_STATUS $(grep -c \
    ' up --no-start' "$T_SHIM_LOG") $(grep -c ' down --volumes' "$T_SHIM_LOG") $(t_exists \
    "$T_A2_OUT/state/scope-compose-a2")"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

## @description  Brings the test deployment cluster up for a given run class (class) and tears it
##               down again to trace the resulting runtime invocation pattern.
## @return       Writes the normalized, deduplicated runtime verbs and flags to stdout. Exit 0.
t_a2_trace() {
  t_gen --class "$1" --cluster a2
  t_a2_provision "$T_A2_OUT/deploy/a2"
  t_shim_log_reset
  t_a2 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 --class "$1" up --no-wait
  t_a2 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  awk '{
    out = ""
    for (i = 1; i <= NF; i++) {
      if ($i ~ /^[a-z][a-z-]*-[0-9]$/ || $i ~ /^a2[0-9]+$/ || $i ~ /\//) continue
      out = out " " $i
    }
    print out
  }' "$T_SHIM_LOG" | LC_ALL=C sort -u
}

# ---------------------------------------------------------------------------
# A3: Host safety verification
# ---------------------------------------------------------------------------
# Each host safety guard is exercised against the simulated container runtime and host utility
# shims. Any test case explicitly named by a requirement preserves that exact identifier.

## @description  Executes a single command against the fake runtime using the A3 state directory
##               and the A3 output root.
## @return       Populates T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a3() {
  t_with_shim "PX_FAKE_STATE_DIR=$T_A3_STATE" "PX_OUT=$T_A3_OUT" PX_POLL_INTERVAL_S=0.2 "$@"
}

## @description  Determines whether a given process ID is currently alive, reporting yes or no.
## @return       Writes yes or no to stdout. Exit 0.
t_alive() { if kill -s 0 "${1:-x}" 2>/dev/null; then printf yes; else printf no; fi; }

## @description  Computes the elapsed time in milliseconds since a given start timestamp (ms).
## @return       Writes the elapsed millisecond count to stdout. Exit 0.
t_ms_since() { printf '%s\n' "$(($(px_epoch_ms) - $1))"; }

## @description  Verifies that the elapsed time since a start timestamp remains strictly below a
##               specified threshold (bound MS, start time in ms).
## @return       Writes yes, or no followed by the elapsed milliseconds, to stdout. Exit 0.
t_within() { local n; n=$(t_ms_since "$2"); local r=("no, ${n}ms" yes); echo "${r[n < $1]}"; }

## @description  Retrieves the execution record of the most recent px-run invocation, resolving the
##               run directory from the first line of its captured stdout.
## @return       Writes the run record contents to stdout. Exit 0.
t_record() { cat "$(printf '%s\n' "$T_STDOUT" | sed -n 1p)/run-record.tsv" 2>/dev/null || true; }

## @description  Counts the number of entries in the simulated runtime log that match a given
##               substring (needle).
## @return       Writes the matching call count to stdout. Exit 0.
t_calls() { grep -c -- "$1" "$T_SHIM_LOG" 2>/dev/null || true; }

# Line 1 of T_A3_RUN_OUT identifies the active run directory, and T_A3_RUN and T_A3_INHIBITOR
# record its path and sleep inhibitor state once the driver and any T_A3_READY file are present.
## @description  Checks whether the run on line 1 of T_A3_RUN_OUT has reached its driver and
##               written the file named by T_A3_READY, if any.
## @return       Exit 0 when the driver has started; Exit 1 otherwise.
t_a3_driver_started() {
  local dir
  dir="$(sed -n 1p "${T_A3_RUN_OUT:-$T_OUT}" 2>/dev/null)"
  [ -n "$dir" ] && grep -q "^stage${PX_TAB}driver_start" "$dir/run-record.tsv" 2>/dev/null ||
    return 1
  [ -z "${T_A3_READY:-}" ] || [ -s "$dir/$T_A3_READY" ] || return 1
  T_A3_RUN=$dir
  T_A3_INHIBITOR="$(cut -f 1 "$PX_OUT/state/inhibit-run-${dir##*/}" 2>/dev/null)"
  return 0
}

# A shell process that was launched with SIGINT (INT) ignored propagates that disposition to every
# child process, and no child can override it.
## @description  Probes whether an INT signal is delivered to a background job spawned by the
##               current shell.
## @return       Exit 0 when the INT signal terminates the job; Exit 1 otherwise.
t_int_delivers() {
  local pid
  set -m
  sleep 5 &
  pid=$!
  set +m
  # The forked background job inherits this shell's exit trap until it execs sleep. Delivering an
  # INT signal before that point would trigger the exit hook and delete the test fixtures, so pause
  # briefly to allow the job to reach sleep first.
  sleep 0.3
  kill -s INT "$pid" 2>/dev/null || true
  sleep 0.3
  if kill -s 0 "$pid" 2>/dev/null; then
    kill -s KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    return 1
  fi
  wait "$pid" 2>/dev/null || true
  return 0
}

## @description  Launches a command (signal, command...) under job control, delivers the specified
##               signal once the run reaches its driver stage, and waits for termination.
## @return       Populates T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_run_interrupted() {
  local sig=$1 pid tries=0
  shift
  : >"$T_OUT"
  # Enabling job control preserves default INT handling for the child command. Without job control,
  # background jobs automatically ignore INT, which would prevent the test case from exercising
  # signal delivery.
  set -m
  "$@" </dev/null >"$T_OUT" 2>"$T_ERR" &
  pid=$!
  set +m
  while [ "$tries" -lt 150 ] && ! t_a3_driver_started; do
    sleep 0.1
    tries=$((tries + 1))
  done
  T_A3_SIGNALLED="$(px_epoch_ms)"
  kill -s "$sig" "$pid" 2>/dev/null || true
  T_STATUS=0
  wait "$pid" 2>/dev/null || T_STATUS=$?
  T_STDOUT="$(cat "$T_OUT")"
  T_STDERR="$(cat "$T_ERR")"
}

## @description  Verifies the fast and memory phases of preflight validation (R-042, R-043).
## @return       Records test assertions. Exit 0.
t_a3_preflight_cases() {
  local pf="$PX_HOME/scripts/preflight.sh" dir="$T_A3/fast" start bad=""
  local deploy="$T_A2_OUT/deploy/v"
  printf '# preflight: the fast phase and the host facts\n'
  mkdir -p "$dir"
  cp "$T_DEPLOY/compose.yaml" "$dir/"
  # R-042: Exercise an unresponsive container engine, a client refused by the engine, and an
  # occupied port. Each fault must fail immediately with its own distinct exit code and diagnostic
  # message.
  : >"$T_A2_STATE/unreachable"
  printf 'PX_PORT_MASTER_UI=1\n' >"$dir/ports.generated.env"
  start="$(px_epoch_ms)"
  t_a2 "$T_BASH" "$pf" --deployment "$dir"
  [ "$(t_ms_since "$start")" -lt 1000 ] || bad="$bad unresponsive=$(t_ms_since "$start")ms"
  rm -f "$T_A2_STATE/unreachable"
  t_equals preflight_unresponsive_engine "1 yes" "$T_STATUS $(t_flag \
    'refused the deployment: engine_responsive: the engine did not answer' "$T_STDERR")"
  t_absent preflight_fast_refusal_skips_the_memory_phase memory_fit "$T_STDOUT"
  : >"$T_A2_STATE/denied"
  start="$(px_epoch_ms)"
  t_a2 "$T_BASH" "$pf" --deployment "$dir"
  [ "$(t_ms_since "$start")" -lt 1000 ] || bad="$bad denied=$(t_ms_since "$start")ms"
  rm -f "$T_A2_STATE/denied"
  t_equals preflight_logged_out_engine "5 yes" "$T_STATUS $(t_flag \
    'engine_credentials: the client is not authorised against the engine' "$T_STDERR")"
  t_empty preflight_engine_faults_answer_in_under_a_second "$bad"
  if t_zk_start; then
    printf 'PX_PORT_MASTER_UI=%s\n' "$T_ZK_PORT" >"$dir/ports.generated.env"
    start="$(px_epoch_ms)"
    t_a2 "$T_BASH" "$pf" --deployment "$dir"
    bad=""
    [ "$(t_ms_since "$start")" -lt 1000 ] || bad="port=$(t_ms_since "$start")ms"
    t_zk_stop
    t_equals preflight_occupied_port "3 yes" "$T_STATUS $(t_flag \
      "port_availability: loopback ports already in use: $T_ZK_PORT" "$T_STDERR")"
    t_empty preflight_occupied_port_answers_in_under_a_second "$bad"
  else
    t_skip preflight_occupied_port "nc is absent, so no port can be held"
  fi
  # R-043: Drive each host capacity metric below its required threshold in turn.
  printf '%s\n' "$((1024 * 1048576))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory --deployment "$deploy"
  t_equals preflight_low_memory "3 yes" "$T_STATUS $(t_flag \
    'refused the deployment: memory_fit: the limits' "$T_STDERR")"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory --deployment "$deploy"
  t_equals preflight_admits_enough_of_each 0 "$T_STATUS"
  t_a2 PX_CLUSTER_ID=v "PATH=$T_A3/lowdisk:$T_A2_BIN:$T_SHIM_DIR:$PATH" "$T_BASH" "$pf" \
    --phase memory --deployment "$deploy"
  t_equals preflight_low_disk "3 yes" "$T_STATUS $(t_flag \
    'refused the deployment: disk_free: disk: 1 MiB (0.00 GiB) free' "$T_STDERR")"
  t_a2 PX_CLUSTER_ID=v "PATH=$T_A3/onecpu:$T_A2_BIN:$T_SHIM_DIR:$PATH" "$T_BASH" "$pf" \
    --phase memory --deployment "$deploy"
  t_equals preflight_few_processors "3 yes" "$T_STATUS $(t_flag \
    'refused the deployment: cpus: processors: 1 (host 1, engine 9), below the 2' "$T_STDERR")"
  # Simulate a container engine configured with fewer processors than the underlying host.
  printf '1\n' >"$T_A2_STATE/ncpu"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory --deployment "$deploy"
  rm -f "$T_A2_STATE/ncpu"
  t_equals preflight_few_engine_processors "3 yes" "$T_STATUS $(t_flag \
    "cpus: processors: 1 (host $(getconf _NPROCESSORS_ONLN), engine 1), below the 2" \
    "$T_STDERR")"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

## @description  Verifies sleep inhibitor lifecycle management across individual runs and active
##               clusters (R-044).
## @return       Records test assertions. Exit 0.
t_a3_inhibit_cases() {
  local pid manual run dir os prog logs="" sig code name run_name owner before tries=0 start
  printf '# sleep inhibition\n'
  t_shim_state_reset
  # Start an independent operator-owned sleep inhibitor process that harness teardown must never
  # terminate.
  "$T_HOST_DIR/caffeinate" -i </dev/null >/dev/null 2>&1 &
  manual=$!
  for os in Darwin Linux; do
    prog=caffeinate
    [ "$os" = Darwin ] || prog=systemd-inhibit
    : >"$T_HOST_DIR/inhibit.log"
    t_with_shim "PATH=$T_A3/$os:$T_SHIM_DIR:$PATH" "$T_BASH" "$T_BIN/px-run" \
      "${T_SELFTEST_RUN[@]}" \
      -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh" -- 0
    run="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
    pid="$(cut -f 1 "$run/logs/run.log" 2>/dev/null | sed -n 1p)"
    logs="$logs$(cat "$T_HOST_DIR/inhibit.log")$PX_NL"
    t_equals "inhibit_${os}_uses_$prog" "0 yes" "$T_STATUS $(t_flag "$prog " \
      "$(cat "$T_HOST_DIR/inhibit.log")")"
    t_contains "inhibit_${os}_records_the_pid" "$prog${PX_TAB}run-${run##*/}" \
      "$(cat "$run/logs/run.log" 2>/dev/null)"
    t_equals "inhibit_${os}_released_after_the_run" "no no" \
      "$(t_alive "$pid") $(t_exists "$PX_OUT/state/inhibit-run-${run##*/}")"
    if [ "$os" = Linux ]; then
      owner="$(sed -n 's/^owner //p' "$run/logs/run.log" 2>/dev/null)"
      t_contains inhibit_linux_follows_its_owner \
        "sh -c while kill -0 \"\$1\" 2>/dev/null; do sleep 1; done px-inhibit ${owner:-none}" \
        "$(cat "$T_HOST_DIR/inhibit.log")"
    fi
  done
  t_contains inhibit_darwin_follows_its_owner 'caffeinate -i -s -w ' "$logs"
  t_contains inhibit_linux_blocks_sleep '--what=sleep:idle --who=phoenix-harness' "$logs"
  # Verify that a Linux sleep inhibitor terminates automatically with its owning process, even when
  # the owner is killed with SIGKILL (KILL).
  sleep 30 &
  owner=$!
  (PATH="$T_A3/Linux:$PATH" px_inhibit_start a3-owner "$owner") 2>/dev/null
  pid="$(cut -f 1 "$PX_OUT/state/inhibit-a3-owner" 2>/dev/null)"
  before="$(t_alive "$pid")"
  kill -s KILL "$owner" 2>/dev/null || true
  wait "$owner" 2>/dev/null || true
  while [ "$tries" -lt 50 ] && [ "$(t_alive "$pid")" = yes ]; do
    sleep 0.1
    tries=$((tries + 1))
  done
  t_equals inhibit_linux_ends_with_its_owner "yes no" "$before $(t_alive "$pid")"
  px_guard_release inhibit a3-owner
  # When a sleep inhibitor process exits immediately, it holds no lock, so the run emits a warning
  # and leaves no inhibitor state record.
  t_with_shim "PATH=$T_A3/quickexit:$T_A3/Darwin:$T_SHIM_DIR:$PATH" "$T_BASH" \
    "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_SCOPE_ID=px-selftest \
    "$T_WORK/drivers/host-probe.sh" -- 0
  run="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  t_equals inhibit_that_ends_at_once_is_a_warning "0 yes yes" "$T_STATUS $(t_flag \
    'the sleep inhibitor caffeinate ended at once' "$T_STDERR") $(t_flag 'no record' \
    "$(cat "$run/logs/run.log" 2>/dev/null)")"
  t_with_shim "PATH=$T_A3/SunOS:$T_SHIM_DIR:$PATH" "$T_BASH" "$T_BIN/px-run" \
    "${T_SELFTEST_RUN[@]}" \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh" -- 0
  t_equals inhibit_absent_is_a_warning "0 yes yes" "$T_STATUS $(t_flag \
    'no sleep inhibitor on this host (SunOS' "$T_STDERR") $(t_flag 'no record' \
    "$(cat "$(printf '%s\n' "$T_STDOUT" | sed -n 1p)/logs/run.log" 2>/dev/null)")"
  # Verify that an injected driver failure still releases the active sleep inhibitor on exit.
  t_with_shim "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" "$T_BASH" "$T_BIN/px-run" \
    "${T_SELFTEST_RUN[@]}" \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh" -- 1
  run="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  pid="$(cut -f 1 "$run/logs/run.log" 2>/dev/null | sed -n 1p)"
  t_contains inhibit_failed_run_recorded_its_inhibitor "caffeinate${PX_TAB}run-${run##*/}" \
    "$(cat "$run/logs/run.log" 2>/dev/null)"
  t_equals inhibit_released_after_a_failure "1 no" "$T_STATUS $(t_alive "$pid")"
  # Delivering an interrupt signal releases the sleep inhibitor, terminates the active driver, and
  # tears down the cluster. If the parent shell started with INT ignored, INT cannot reach the run,
  # so this test case falls back to sending TERM.
  sig=INT code=130 name=an_interrupt run_name=interrupted
  if ! t_int_delivers; then
    t_skip inhibit_released_after_an_interrupt "this shell ignores INT, the case sends TERM"
    sig=TERM code=143 name=a_terminate run_name=terminated
  fi
  t_shim_state_reset
  t_shim_log_reset
  T_A3_INHIBITOR=""
  t_run_interrupted "$sig" env "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" \
    "PX_DOCKER_BIN=$T_SHIM_DIR/docker" "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" \
    "PX_FAKE_STATE_DIR=$T_SHIM_STATE" "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" \
    -e PX_POLL_INTERVAL_S=0.2 \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/sleep.sh" -- 30
  t_nonempty inhibit_interrupted_run_recorded_its_inhibitor "$T_A3_INHIBITOR"
  t_equals "inhibit_released_after_$name" "$code no" "$T_STATUS $(t_alive "$T_A3_INHIBITOR")"
  # R-051: A run never tears down a cluster that it did not bring up, even when interrupted.
  t_equals "${run_name}_run_leaves_the_cluster_up" 0 "$(t_calls 'compose -p px-selftest down')"
  t_equals inhibit_never_stops_the_inhibitor_of_the_operator yes "$(t_alive "$manual")"
  kill "$manual" 2>/dev/null || true
  wait "$manual" 2>/dev/null || true
  # A standalone cluster maintains both a sleep inhibitor and a background health monitor from the
  # time it comes up until it is torn down.
  rm -rf "${T_A3_STATE:?}"/*
  cp "$T_SWEEP_STATE/provision.txt" "$T_A3_STATE/"
  printf 'sweep\n' >"$T_A3_STATE/cluster"
  dir="$T_A3_OUT/state"
  t_a3 "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" "$T_BASH" "$T_BIN/px-cluster" --cluster sweep up \
    --timeout 5 "$T_DEPLOY"
  pid="$(cut -f 1 "$dir/inhibit-cluster-px-sweep" 2>/dev/null)"
  t_equals inhibit_held_while_a_cluster_is_up "0 yes" "$T_STATUS $(t_alive "$pid")"
  T_A3_MONITOR="$(cut -f 1 "$dir/monitor-px-sweep" 2>/dev/null)"
  t_equals monitor_runs_while_a_cluster_is_up "yes yes" \
    "$(t_alive "$T_A3_MONITOR") $(t_exists "$dir/monitor-px-sweep.heartbeat")"
  t_a3 "$T_BASH" "$T_BIN/px-cluster" --cluster sweep down
  t_equals inhibit_released_when_the_cluster_is_down "0 no no" \
    "$T_STATUS $(t_alive "$pid") $(t_exists "$dir/inhibit-cluster-px-sweep")"
  t_equals monitor_stops_when_the_cluster_is_down "no no no" "$(t_alive "$T_A3_MONITOR")\
 $(t_exists "$dir/monitor-px-sweep") $(t_exists "$dir/monitor-px-sweep.heartbeat")"
  # Because the setup role depends on the health monitor heartbeat, a monitor that fails to start
  # (simulated here by creating a regular file at its run directory path) aborts cluster bring-up
  # before provisioning begins.
  rm -rf "${T_A3_STATE:?}"/*
  cp "$T_SWEEP_STATE/provision.txt" "$T_A3_STATE/"
  printf 'sweep\n' >"$T_A3_STATE/cluster"
  run="$T_A3/no-monitor"
  mkdir -p "$run"
  : >"$run/monitor"
  t_shim_log_reset
  start="$(px_epoch_ms)"
  t_a3 "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" "PX_OUT=$run" "$T_BASH" "$T_BIN/px-cluster" \
    --cluster sweep up --timeout 5 "$T_DEPLOY"
  t_equals monitor_that_cannot_start_fails_the_up_at_once "1 yes yes" "$T_STATUS $(t_flag \
    'no run directory for the health monitor' "$T_STDERR") $(t_within 5000 "$start")"
  t_equals monitor_that_cannot_start_provisions_nothing 0 \
    "$(grep -c '^compose .* up ' "$T_SHIM_LOG")"
  t_equals monitor_that_cannot_start_leaves_no_guard "no no" \
    "$(t_exists "$run/state/inhibit-cluster-px-sweep") $(t_exists "$run/state/monitor-px-sweep")"
}

## @description  Verifies that individual runs and suite sessions leave an existing cluster running,
##               because each command tears down only resources it brought up (R-051).
## @return       Records test assertions. Exit 0.
t_a3_teardown_cases() {
  local suite="$T_A3/teardown.suite" code pid
  printf '# ownership: a command tears down only what it brought up\n'
  for code in 0 1; do
    t_shim_state_reset
    t_shim_log_reset
    t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_SCOPE_ID=px-selftest \
      "$T_WORK/drivers/exit-code.sh" -- "$code"
    t_equals "run_leaves_the_cluster_up_after_exit_$code" "$code 0" \
      "$T_STATUS $(t_calls 'compose -p px-selftest down')"
  done
  t_empty run_records_no_teardown "$(t_record | awk -F '\t' '$1 == "teardown" || $3 == "teardown"')"
  t_shim_state_reset
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" --keep -e PX_SCOPE_ID=px-selftest \
    "$T_WORK/drivers/exit-code.sh" -- 0
  t_equals px_run_refuses_keep "2 yes 0" "$T_STATUS $(t_flag 'unknown option --keep' \
    "$T_STDERR") $(t_calls 'compose -p px-selftest down')"
  # A suite session likewise leaves the cluster up; invoking px-cluster down removes it.
  printf 'suite: a3-teardown\nclass: validation\ntimeout: 60\n\n' >"$suite"
  printf 'test: one\n  driver: %s\n  args: 0\n\n' \
    "$T_WORK/drivers/exit-code.sh" >>"$suite"
  printf 'test: two\n  needs: one\n  driver: %s\n  args: 0\n' \
    "$T_WORK/drivers/exit-code.sh" >>"$suite"
  t_shim_state_reset
  t_shim_log_reset
  t_with_shim PX_SCOPE_ID=px-selftest PX_CLUSTER_ID=selftest PX_SESSION_ID=a3-teardown \
    "$T_BASH" "$T_BIN/px-suite" "$suite"
  t_equals session_leaves_the_cluster_up_after_its_tests "0 0" \
    "$T_STATUS $(t_calls 'compose -p px-selftest down')"
  t_shim_state_reset
  t_shim_log_reset
  t_with_shim PX_SCOPE_ID=px-selftest PX_CLUSTER_ID=selftest \
    PX_SESSION_ID=a3-teardown-keep "$T_BASH" "$T_BIN/px-suite" --keep "$suite"
  t_equals kept_session_leaves_the_cluster_up "0 0" \
    "$T_STATUS $(t_calls 'compose -p px-selftest down')"
  # Delivering a termination signal immediately aborts the session's wait on its active test; the
  # exit hook then terminates the test process, its driver, and its sleep inhibitor while leaving
  # the cluster up.
  printf 'suite: a3-interrupt\nclass: validation\ntimeout: 60\n\n' >"$suite"
  printf 'test: one\n  driver: %s\n  args: 30\n' \
    "$T_WORK/drivers/sleep.sh" >>"$suite"
  t_shim_state_reset
  t_shim_log_reset
  T_A3_RUN=""
  T_A3_INHIBITOR=""
  T_A3_RUN_OUT="$PX_OUT/sessions/a3-interrupt/tests/one/stdout.txt"
  # Delay signal delivery until the driver has written its PID file so that checking driver
  # termination does not race against driver startup.
  T_A3_READY=driver.pid
  t_run_interrupted TERM env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" \
    PX_SCOPE_ID=px-selftest PX_CLUSTER_ID=selftest PX_SESSION_ID=a3-interrupt \
    PX_POLL_INTERVAL_S=0.2 "$T_BASH" "$T_BIN/px-suite" "$suite"
  T_A3_RUN_OUT=""
  T_A3_READY=""
  pid="$(cat "$T_A3_RUN/driver.pid" 2>/dev/null)"
  t_equals terminated_session_leaves_the_cluster_up "143 yes 0" "$T_STATUS $(t_within 10000 \
    "$T_A3_SIGNALLED") $(t_calls 'compose -p px-selftest down')"
  t_equals teardown_terminated_session_stops_driver_and_inhibitor "no no" \
    "$(t_alive "${pid:-x}") $(t_alive "${T_A3_INHIBITOR:-x}")"
  t_equals teardown_terminated_session_found_its_driver "yes yes" \
    "${pid:+yes} ${T_A3_INHIBITOR:+yes}"
  t_empty interrupted_session_has_no_result_row "$(printf '%s\n' "$T_STDOUT" |
    grep "^result$PX_TAB")"
  # The execution time bound for px-run is the sum of the test time limit, the safety margin, and
  # six short-verb bounds, plus the quiet-host wait window and one poll interval for the test's run
  # class when neither plug-ins nor disruptions add terms.
  printf 'suite: a3-bound\ntimeout: 60\n\ntest: one\n  driver: %s\n  class: %s\n' \
    "$T_WORK/drivers/exit-code.sh" measurement >"$suite"
  t_shim_state_reset
  t_with_shim PX_SCOPE_ID=px-selftest PX_CLUSTER_ID=selftest PX_SESSION_ID=a3-bound \
    PX_POLL_INTERVAL_S=0.5 "$T_BASH" "$T_BIN/px-suite" -v "$suite"
  t_equals teardown_session_bound_covers_the_quiet_wait "0 yes" "$T_STATUS $(t_flag \
    "test one: px-run gets a bound of $((60 + 300 + 6 * 300 + 300 + 1))s" "$T_STDERR")"
  t_shim_state_reset
}

## @description  Verifies that concurrent teardown requests targeting the same scope are serialized
##               one after another (F3).
## @return       Records test assertions. Exit 0.
t_a3_down_lock_cases() {
  local lock="$PX_OUT/state/down-px-selftest" holder pid status kind
  printf '# one removal at a time\n'
  for kind in released removed; do
    t_shim_state_reset
    t_shim_log_reset
    mkdir -p "$lock"
    sleep 60 &
    holder=$!
    printf '%s\n' "$holder" >"$lock/pid"
    env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
      "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" \
      PX_SCOPE_ID=px-selftest "$T_BASH" "$T_BIN/px-cluster" down \
      </dev/null >"$T_OUT" 2>"$T_ERR" &
    pid=$!
    sleep 2.5
    t_equals "down_lock_${kind}_waits_for_the_holder" "yes 0" \
      "$(t_alive "$pid") $(t_calls 'compose -p px-selftest down')"
    # In the second scenario, simulate the lock holder completing scope removal while the contender
    # is still waiting on the teardown lock.
    if [ "$kind" = removed ]; then
      : >"$T_SHIM_STATE/instances.txt"
      rm -f "$PX_OUT/state/compose/scope-px-selftest.tsv"
    fi
    kill "$holder" 2>/dev/null || true
    wait "$holder" 2>/dev/null || true
    rm -rf "$lock"
    status=0
    wait "$pid" || status=$?
    if [ "$kind" = released ]; then
      t_equals down_lock_released_then_down "0 1" \
        "$status $(t_calls 'compose -p px-selftest down')"
    else
      t_equals down_lock_scope_gone_counts_as_down "0 yes 0" "$status $(t_flag \
        'scope px-selftest already came down' "$(cat "$T_ERR")") $(t_calls \
        'compose -p px-selftest down')"
    fi
  done
  # When the configured teardown timeout bound is not a positive integer number of seconds, the
  # backend falls back to its default timeout so cluster removal still completes cleanly without
  # leaving residual instances behind.
  for value in abc 300s 0; do
    t_shim_state_reset
    t_shim_log_reset
    t_with_shim PX_SCOPE_ID=px-selftest "PX_TIMEOUT_TEARDOWN=$value" "$T_BASH" \
      "$T_BIN/px-cluster" down
    t_equals "down_with_teardown_bound_${value}_removes_the_cluster" "0 1 0" \
      "$T_STATUS $(t_calls 'compose -p px-selftest down') $(grep -c . \
      "$T_SHIM_STATE/instances.txt" 2>/dev/null)"
  done
  t_shim_state_reset
}

## @description  Verifies that an unplanned service restart or an out-of-memory (OOM) kill triggers
##               an immediate run abort (R-052).
## @return       Records test assertions. Exit 0.
t_a3_abort_cases() {
  local start record driver name change bundle
  printf '# the immediate abort\n'
  # Iterate over one three-field tuple per fault scenario: the fault-injecting driver, the test case
  # name, and the expected state transition in the abort record row. The OOM kill scenario runs last
  # so its record retains the evidence inspected by the post-loop assertions, and the bundle
  # manifest records the abort under its anomaly member.
  while read -r driver name change; do
    t_shim_state_reset
    start="$(px_epoch_ms)"
    # Because px-run honors the first value assigned to a configuration variable, pass the fast poll
    # interval explicitly on this invocation.
    t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_POLL_INTERVAL_S=0.2 \
      -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/$driver.sh"
    t_equals "abort_on_${name}_is_at_once" yes "$(t_within 15000 "$start")"
    record="$(t_record)"
    bundle="$(t_bundle)"
    t_equals "abort_on_$name" 3 "$T_STATUS"
    t_contains "abort_${name}_records_its_change" "$(t_rows 3 regionserver 0 "$change")" \
      "$(printf '%s\n' "$record" | grep '^abort' | cut -f 3-)"
    t_equals "abort_${name}_is_the_manifest_anomaly" "true regionserver/$change" \
      "$(t_manifest "$bundle" /anomaly/aborted) $(t_manifest "$bundle" \
        /anomaly/instances/0/role)/$(t_manifest "$bundle" /anomaly/instances/0/change)"
    # RQ-080: Both the cluster verdict and the bundle manifest identify the affected instance, and
    # the run bundle produced by an aborted run still passes bundle validation.
    t_contains "abort_${name}_is_in_the_cluster_verdict" "regionserver/0 ($change; restarts" \
      "$(awk -F '\t' '$1 == "cluster.restart" { print $5 }' "$bundle/verdicts.tsv")"
    t_equals "abort_${name}_bundle_validates" 0 \
      "$(t_validate "$bundle" >/dev/null; printf '%s' "$T_STATUS")"
  done <<'A3_EOF'
restart-wait an_unplanned_restart restarted 0 to 1
start-wait an_outside_restart restarted 0 to 1
exit-wait an_unplanned_exit went from running to exited
vanish-wait a_lost_instance vanished
oom-wait an_oom_kill went from running to exited
A3_EOF
  t_contains abort_records_the_oom_reason "$(t_rows 4 term regionserver 0 oom)" "$record"
  t_contains abort_records_the_oom_evidence "$(t_rows 4 oom regionserver 0 yes)" "$record"
  t_equals abort_manifest_carries_the_oom_evidence "0 0 oom 137 true" \
    "$(for change in restarts_before restarts_after termination_reason exit_code oom; do
      t_manifest "$bundle" "/anomaly/instances/0/$change"; done | tr '\n' ' ' | sed 's/ $//')"
  t_contains abort_cluster_verdict_carries_the_oom_evidence 'reason oom, exit 137; oom yes' \
    "$(awk -F '\t' '$1 == "cluster.restart" { print $5 }' "$bundle/verdicts.tsv")"
  t_contains abort_code_is_documented_in_the_readme '| 3 | anomaly |' \
    "$(cat "$PX_HOME/README.md")"
  t_contains abort_code_is_documented_for_backends 'aborts the run at once with exit 3' \
    "$(tr -s ' \n' ' ' <"$PX_HOME/docs/backends.md")"
  # Verify A8: following an anomaly abort, the restore verb never executes against the untrusted
  # cluster, a SKIP verdict records the reason, the run performs no teardown, and the resulting
  # bundle still passes validation. Setting PX_KEEP=1 does not alter this behavior.
  t_shim_state_reset
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_POLL_INTERVAL_S=0.2 \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/restart-wait.sh"
  bundle="$(t_bundle)"
  t_equals abort_skips_restore "3 describe validate prepare run report 0" \
    "$T_STATUS $(t_verbs_of "$bundle") $(t_calls 'compose -p px-selftest down')"
  t_equals abort_restore_verdict_is_skip "SKIP yes" "$(t_severity_of "$bundle" driver.restore)\
 $(t_flag 'anomaly abort' "$(awk -F '\t' '$1 == "driver.restore"' "$bundle/verdicts.tsv")")"
  t_equals abort_bundle_validates 0 \
    "$(t_validate "$bundle" >/dev/null; printf '%s' "$T_STATUS")"
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_POLL_INTERVAL_S=0.2 \
    -e PX_KEEP=1 -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/restart-wait.sh"
  t_equals abort_with_keep_skips_restore "3 describe validate prepare run report SKIP" \
    "$T_STATUS $(t_verbs_of "$(t_bundle)") $(t_severity_of "$(t_bundle)" driver.restore)"
  t_shim_state_reset
}

## @description  Verifies quiet-host contention detection and contamination tagging (R-047, R-048).
## @return       Records test assertions. Exit 0.
t_a3_quiet_cases() {
  local want record
  printf '# the quiet-host check\n'
  t_shim_state_reset
  : >"$T_HOST_DIR/uptime.log"
  printf '0.00\n' >"$T_HOST_DIR/load"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_CLUSTER[@]}" --class validation \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh"
  t_equals quiet_validation_takes_no_sample "0 0" \
    "$T_STATUS $(grep -c . "$T_HOST_DIR/uptime.log")"
  record="$(t_record)"
  t_contains quiet_validation_records_the_check_off "$(t_rows 3 quiet quiet_host off)" "$record"
  t_contains quiet_off_check_contaminates_the_run \
    "$(t_rows 3 contamination quiet-host \
      'the quiet-host check is off, so a failed time verdict is ADVISORY')" "$record"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_CLUSTER[@]}" --class measurement \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh"
  record="$(t_record)"
  t_equals quiet_measurement_on_a_quiet_host "0 1" \
    "$T_STATUS $(grep -c . "$T_HOST_DIR/uptime.log")"
  t_contains quiet_records_the_quiet_result "$(t_rows 3 quiet quiet_host quiet)" "$record"
  t_absent quiet_record_holds_no_result_trailer "quiet${PX_TAB}result$PX_TAB" "$record"
  t_absent quiet_host_is_not_contaminated contamination "$record"
  # Simulate a heavily contended host with a load average far exceeding the quiet-host threshold on
  # any processor count.
  printf '99.00\n' >"$T_HOST_DIR/load"
  want="load $((9900 / $(getconf _NPROCESSORS_ONLN)))% of its processors is above 25%"
  : >"$T_HOST_DIR/uptime.log"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_CLUSTER[@]}" --class measurement \
    -e PX_QUIET_HOST_WAIT_S=1 -e PX_POLL_INTERVAL_S=0.2 -e PX_SCOPE_ID=px-selftest \
    "$T_WORK/drivers/host-probe.sh"
  record="$(t_record)"
  t_equals quiet_busy_host_fails_a_measurement 4 "$T_STATUS"
  t_contains quiet_reports_the_contention_with_its_value "$want" "$T_STDERR"
  t_equals quiet_busy_host_pauses_and_retries yes \
    "$([ "$(grep -c . "$T_HOST_DIR/uptime.log")" -ge 2 ] && echo yes || echo no)"
  t_contains quiet_records_the_refusal "$(t_rows 3 quiet quiet_host refused)" "$record"
  t_equals quiet_refusal_runs_no_driver 0 \
    "$(printf '%s\n' "$record" | grep -c "^verb${PX_TAB}run$PX_TAB")"
  # A quiet-host wait duration of 0 takes a single load sample and still enforces the quiet-host
  # requirement.
  : >"$T_HOST_DIR/uptime.log"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_CLUSTER[@]}" --class measurement \
    -e PX_QUIET_HOST_WAIT_S=0 -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh"
  t_equals quiet_wait_0_takes_one_sample_and_decides "4 1" \
    "$T_STATUS $(grep -c . "$T_HOST_DIR/uptime.log")"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_CLUSTER[@]}" --class measurement \
    -e PX_QUIET_HOST_WAIT_S=1 -e PX_REQUIRE_QUIET_HOST=0 -e PX_POLL_INTERVAL_S=0.2 \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/host-probe.sh"
  record="$(t_record)"
  t_equals quiet_not_required_proceeds 0 "$T_STATUS"
  t_contains quiet_contaminated_run_carries_the_tag \
    "$(t_rows 2 contamination quiet-host)" "$record"
  t_contains quiet_driver_learns_the_contamination 'contamination [quiet-host]' \
    "$(cat "$(printf '%s\n' "$T_STDOUT" | sed -n 1p)/logs/run.log" 2>/dev/null)"
  t_contains quiet_measurement_requires_by_default 'PX_REQUIRE_QUIET_HOST=1' \
    "$(cat "$T_ENVD/class-measurement.env")"
  # Verify O4 and O5: the check states are reported in the quiet_host row, and the stream
  # concludes with a single result row carrying a check status.
  want=""
  for line in 99.00:1 0.00:1 99.00:0; do
    printf '%s\n' "${line%%:*}" >"$T_HOST_DIR/load"
    t_run env PX_QUIET_HOST_WAIT_S=1 PX_POLL_INTERVAL_S=0.2 \
      "PX_REQUIRE_QUIET_HOST=${line##*:}" "$T_BASH" "$T_BIN/px-tool" quiet-host
    want="$want|$T_STATUS $(t_last_line "$T_STDOUT" | tr '\t' ' ') $(printf '%s\n' \
      "$T_STDOUT" | grep -c "^result$PX_TAB") $(printf '%s\n' "$T_STDOUT" | tail -n 2 |
      sed -n 1p | cut -f 1,2 | tr '\t' ' ')"
  done
  t_run env PX_QUIET_HOST_WAIT_S=0 PX_REQUIRE_QUIET_HOST=0 "$T_BASH" "$T_BIN/px-tool" quiet-host
  t_equals quiet_host_ends_with_a_result_row "|4 result fail 4 host 1 quiet_host refused|0 result\
 ok 0 host 1 quiet_host quiet|0 result warn 0 host 1 quiet_host contaminated|0 $(t_rows 5 \
    quiet_host off 0 0 '')$PX_NL$(t_rows 4 result skip 0 host)" "$want|$T_STATUS $T_STDOUT"
  printf '0.00\n' >"$T_HOST_DIR/load"
  t_shim_state_reset
}

## @description  Verifies the background host and container health monitor (R-049).
## @return       Records test assertions. Exit 0.
t_a3_monitor_cases() {
  local dir="$PX_OUT/a3-monitor" start hb pid seed="$T_A3/r049" record run
  printf '# the health monitor\n'
  t_run env -u PX_MONITOR_INTERVAL_S "$T_BASH" "$T_BIN/px-cluster" config
  t_contains monitor_interval_is_30_seconds "$(t_rows 3 PX_MONITOR_INTERVAL_S 30 defaults)" \
    "$T_STDOUT"
  # Simulate host memory exhaustion to trigger a safety breach: the monitor must tear down the
  # cluster within a single sampling interval and persist the triggering sample in breach.tsv.
  t_shim_state_reset
  t_shim_log_reset
  rm -rf "$dir"
  printf '1000\n' >"$T_HOST_DIR/free-pages"
  start="$(px_epoch_ms)"
  t_with_shim "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" "PX_RUN_DIR=$dir" PX_MONITOR_INTERVAL_S=2 \
    "$T_BASH" "$T_BIN/px-tool" monitor -e PX_SCOPE_ID=px-selftest
  t_equals monitor_breach_within_one_interval "1 yes" \
    "$(grep -c '^host' "$dir/samples.tsv" 2>/dev/null) $(t_within 8000 "$start")"
  t_equals monitor_breach_tears_down "3 1" "$T_STATUS $(t_calls 'compose -p px-selftest down')"
  t_contains monitor_writes_the_triggering_sample \
    "$(t_rows 2 breach 'the host has 3 MiB free, below the floor of 1024 MiB')" \
    "$(cat "$dir/breach.tsv" 2>/dev/null)"
  t_contains monitor_breach_holds_the_sample "${PX_TAB}4096000" \
    "$(grep '^host' "$dir/breach.tsv" 2>/dev/null)"
  rm -f "$T_HOST_DIR/free-pages"
  # A service instance approaching its own container memory limit is logged in the sample history
  # without triggering a host-level breach; any resulting container OOM stays bounded within the
  # container and is reported by the px-run abort check. Meanwhile, the monitor refreshes its
  # heartbeat file for the bootstrap probe and shuts down cleanly upon receiving TERM.
  t_shim_state_reset
  t_shim_log_reset
  rm -rf "$dir"
  printf '1000MiB / 1GiB\n' >"$T_SHIM_STATE/stats-mem"
  (
    env "PATH=$T_A3/Darwin:$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
      "PX_FAKE_STATE_DIR=$T_SHIM_STATE" "PX_RUN_DIR=$dir" PX_MONITOR_INTERVAL_S=1 \
      "$T_BASH" "$T_BIN/px-tool" monitor -e PX_SCOPE_ID=px-selftest </dev/null >/dev/null 2>&1 &
  )
  sleep 3
  hb="$PX_OUT/state/monitor-px-selftest.heartbeat"
  pid="$(cut -f 1 "$PX_OUT/state/monitor-px-selftest" 2>/dev/null)"
  t_equals monitor_records_an_instance_near_its_limit "yes no" \
    "$(t_flag "${PX_TAB}1048576000${PX_TAB}1073741824${PX_TAB}" \
      "$(grep '^instance' "$dir/samples.tsv" 2>/dev/null)") $(t_exists "$dir/breach.tsv")"
  t_image "PX_MONITOR_HEARTBEATS=$hb" -- "$T_BASH" "$T_A3/heartbeat-probe.sh" "$T_IMG"
  t_equals monitor_heartbeat_satisfies_the_bootstrap_probe \
    "0 1 monitor heartbeats are fresh" "$T_STATUS $T_STDOUT"
  px_guard_stop "$PX_OUT/state/monitor-px-selftest"
  t_equals monitor_stops_on_a_term "no no" \
    "$(t_alive "$pid") $(t_exists "$PX_OUT/state/monitor-px-selftest")"
  rm -f "$T_SHIM_STATE/stats-mem"
  # Passing a non-integer configuration value is rejected as a usage error. That validation failure
  # terminates the caller of px_int_setting immediately, ensuring the monitor removes nothing.
  t_shim_log_reset
  t_with_shim PX_MONITOR_INTERVAL_S=30s "$T_BASH" "$T_BIN/px-tool" monitor \
    -e PX_SCOPE_ID=px-selftest
  t_equals monitor_refuses_a_malformed_interval "2 0" \
    "$T_STATUS $(t_calls 'compose -p px-selftest down')"
  t_with_shim PX_QUIET_HOST_LOAD_PCT=25% "$T_BASH" "$T_BIN/px-tool" quiet-host
  t_equals quiet_host_refuses_a_malformed_threshold 2 "$T_STATUS"
  # When a monitor breach terminates an active px-run invocation, the run copies the monitor breach
  # record into its own run directory and references that file in its abort row. The synthetic
  # monitor state file cites process 1, whose command line never matches the monitor binary, so no
  # guard release can signal it.
  t_shim_state_reset
  mkdir -p "$seed"
  px_tsv_row breach 'the host has 3 MiB free, below the floor of 1024 MiB' >"$seed/breach.tsv"
  px_tsv_row 1 "px-tool monitor" "$seed" >"$PX_OUT/state/monitor-px-selftest"
  t_with_shim "$T_BASH" "$T_BIN/px-run" "${T_SELFTEST_RUN[@]}" -e PX_POLL_INTERVAL_S=0.2 \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/oom-wait.sh"
  rm -f "$PX_OUT/state/monitor-px-selftest"
  record="$(t_record)"
  run="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  t_equals monitor_breach_reaches_the_run "3 yes" "$T_STATUS $(t_exists "$run/breach.tsv")"
  t_contains monitor_breach_is_cited_by_the_abort 'the health monitor removed the cluster, see' \
    "$(printf '%s\n' "$record" | grep '^abort')"
  t_contains monitor_breach_run_records_the_monitor "$(t_rows 3 monitor pre "$seed")" "$record"
  t_contains monitor_breach_run_records_the_copy "$(t_rows 2 breach "$run/breach.tsv")" "$record"
  t_shim_state_reset
}

## @description  Verifies the container restart policy, the single-cluster host admission gate, and
##               the finite RegionServer drain timeout (R-045, R-046, R-050).
## @return       Records test assertions. Exit 0.
t_a3_policy_cases() {
  local dir="$T_A2_OUT/deploy/m" start bound
  printf '# restart policy, one cluster per host, finite drain\n'
  t_empty no_service_restarts_itself \
    "$(grep -E 'restart: *"?(always|unless-stopped|on-failure)' "$dir/compose.yaml")"
  # R-046: Simulate an existing container belonging to another harness cluster on the same host.
  rm -rf "${T_A3_STATE:?}"/*
  printf 'other\n' >"$T_A3_STATE/cluster"
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' ot01 regionserver px-other-regionserver-1 running \
    'Up 1 minute' px:o regionserver 1 0 >"$T_A3_STATE/instances.txt"
  t_a3 "$T_BASH" "$T_BIN/px-cluster" --cluster gate up "$T_DEPLOY"
  t_equals one_cluster_per_host_refuses_a_second "1 yes" "$T_STATUS $(t_flag \
    'a harness cluster is already on this host: project px-other (cluster other)' "$T_STDERR")"
  t_equals one_cluster_per_host_records_nothing no \
    "$(t_exists "$T_A3_OUT/state/compose/scope-px-gate.tsv")"
  # R-050: Verify that a RegionServer that never completes draining terminates the wait once its
  # drain timeout expires.
  t_equals drain_wait_has_a_finite_timeout 600 \
    "$(t_env_value "$T_ENVD/defaults.env" PX_TIMEOUT_DRAIN)"
  rm -rf "${T_A3_STATE:?}"/*
  printf 'a3\n' >"$T_A3_STATE/cluster"
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||%s\n' a3rs01 regionserver px-a3-regionserver-1 \
    running 'Up 1 minute (healthy)' px:a3 regionserver 1 0 regionmover \
    >"$T_A3_STATE/instances.txt"
  start="$(px_epoch_ms)"
  t_a3 "PX_REGION_MOVER_CMD=sleep 30" "$T_BASH" "$T_WORK/probe/sb.sh" sb_restart_drain px-a3 \
    regionserver 0 1 5
  bound="$(t_within 10000 "$start")"
  t_outcome_columns drain_never_draining_server_times_out 4 2 \
    "$(t_rows 2 drain fail stop skip start skip ready skip rebalance skip)"
  t_equals drain_wait_ends_at_its_timeout yes "$bound"
  rm -rf "${T_A3_STATE:?}"/*
}

## @description  Executes the complete suite of round A3 host safety guard verifications.
## @return       Records test assertions. Exit 0.
t_group_a3() {
  t_a3_preflight_cases
  t_a3_policy_cases
  t_a3_abort_cases
  t_a3_quiet_cases
  t_a3_monitor_cases
  t_a3_teardown_cases
  t_a3_down_lock_cases
  t_a3_inhibit_cases
}

## @description  Writes the A5 fixtures: a fake cluster with client 0, a fake java binary and
##               probe, stub drivers for disruption cases, fixture plug-ins, and verdict streams.
## @return       Exit 0.
t_fixtures_a5() {
  local name
  T_A5="$T_WORK/a5"
  T_A5_STATE="$T_A5/state"
  mkdir -p "$T_A5/bin" "$T_A5/jdk/fake/bin" "$T_A5/conf" "$T_A5/plugins" "$T_A5_STATE" \
    "$T_A5/sql" "$T_A5/verdicts" "$T_A5/hbase/lib"
  # Create the commons-codec JAR in the HBase lib directory, which client classpaths reference by
  # its filename.
  : >"$T_A5/hbase/lib/commons-codec-1.15.jar"
  # Materialize the bootstrap probe for client 0. Because the fake runtime executes container
  # commands directly on the host, placing this shim first on PATH for every fake-runtime call
  # allows it to stand in for the container image probe. It logs its arguments and fails when a
  # fault flag file is present.
  cat >"$T_SHIM_DIR/px-bootstrap-probe" <<'A5_EOF'
#!/usr/bin/env bash
printf 'probe %s\n' "$*" >>"$PX_FAKE_STATE_DIR/probe.log"
[ ! -e "$PX_FAKE_STATE_DIR/probe-fails" ] || exit 1
printf 'done every subject is verified\n'
A5_EOF
  # Materialize the fake JDK for client 0, which records its command line and emulates the main
  # class that it runs. PhoenixRuntime returns SYSTEM.CATALOG rows for a catalog query, with
  # UPDATE_CACHE_FREQUENCY set to 60000 for each table named by the query, or otherwise emits the
  # output of a two-statement script. Pherf reads pherf.properties from the first classpath entry
  # (matching how the real Pherf reads it from the classpath), and its -q query phase writes one
  # aggregate CSV with the Header.java column header into the result directory configured in that
  # file, or otherwise into RESULTS in its working directory. When the hdr directory exists in the
  # fake state, -q also copies its files into the sibling <results>_HDR directory, mirroring how
  # Pherf writes its latency report and verdict. The pherf-gate-fails flag file causes Pherf to
  # exit with code 65, mirroring how its pre-run gate handles a refused scenario, and the
  # catalog-sleeps flag file delays the catalog query for 30 s. The java-naps flag file adds a 2 s
  # delay to every invocation except the catalog query, while any other class emits two ad hoc
  # metrics.
  cat >"$T_A5/jdk/fake/bin/java" <<'A5_EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$PX_FAKE_STATE_DIR/java.log"
if [ -e "$PX_FAKE_STATE_DIR/java-naps" ] && ! grep -q 'SYSTEM.CATALOG' "${!#}" 2>/dev/null; then
  sleep 2
fi
java_cp=""
java_prev=""
for java_arg in "$@"; do
  [ "$java_prev" != -cp ] || java_cp=$java_arg
  java_prev=$java_arg
  case "$java_arg" in
  org.apache.phoenix.util.PhoenixRuntime)
    java_sql=${!#}
    if grep -q 'SYSTEM.CATALOG' "$java_sql" 2>/dev/null; then
      [ ! -e "$PX_FAKE_STATE_DIR/catalog-sleeps" ] || exec sleep 30
      printf 'TABLE_SCHEM TABLE_NAME UPDATE_CACHE_FREQUENCY \n----- ----- ----- \n'
      sed -n "s/.*TABLE_NAME IN (\(.*\));.*/\1/p" "$java_sql" | tr ',' '\n' | tr -d "'" |
        awk 'NF { print "<null> " $1 " 60000" }'
      printf 'Time: 0.005 sec(s)\n\n'
      exit 0
    fi
    printf '3 rows upserted\nTime: 0.012 sec(s)\n\n'
    printf 'ID  NAME \n--- ----- \n1   a     \n2   b     \nTime: 0.034 sec(s)\n\n'
    exit 0
    ;;
  org.apache.phoenix.pherf.Pherf)
    if [ -e "$PX_FAKE_STATE_DIR/pherf-gate-fails" ]; then
      printf 'ERROR the scenario files have validation findings\n' >&2
      exit 65
    fi
    case " $* " in
    *' -q '*)
      java_dir="$(sed -n 's/^pherf\.default\.results\.dir=//p' "${java_cp%%:*}/pherf.properties" \
        2>/dev/null)"
      java_dir=${java_dir//\/tmp\/px\//$PX_FAKE_STATE_DIR/remote/}
      java_dir=${java_dir:-RESULTS}
      mkdir -p "$java_dir"
      {
        printf '%s\n' "START_TIME,QUERY_GROUP,QUERY,TENANT_ID,AVG_MAX_TIME_MS,AVG_TIME_MS,\
AVG_MIN_TIME_MS,RUN_COUNT,EXPLAIN_PLAN,RESULT_ROW_COUNT"
        printf '%s,serial,"SELECT COUNT(*) FROM T",,12,10,8,5,"SCAN T",1\n' \
          "$(date -u '+%Y-%m-%d %H:%M:%S')"
      } >"$java_dir/RESULT_px_functional_aggregate.csv"
      if [ -d "$PX_FAKE_STATE_DIR/hdr" ]; then
        mkdir -p "${java_dir}_HDR"
        cp "$PX_FAKE_STATE_DIR/hdr/"* "${java_dir}_HDR/"
      fi
      ;;
    esac
    printf 'Pherf done\n'
    exit 0
    ;;
  org.example.Exit2) exit 2 ;;
  org.example.Exit6) exit 6 ;;
  org.example.Sleep) exec sleep 30 ;;
  esac
done
printf 'starting the fixture class\n'
printf 'px-metric fixture.rows count 42 rows\n'
printf 'px-metric fixture.share share 0.5 ratio\n'
printf 'the fixture class is done\n'
A5_EOF
  chmod +x "$T_SHIM_DIR/px-bootstrap-probe" "$T_A5/jdk/fake/bin/java"
  printf '%s\n' -Xmx64m >"$T_A5/conf/jvm.opts"
  printf '%s\n' 'phoenix.jdbc.url=jdbc:phoenix:zookeeper-0:2181' \
    >"$T_A5/conf/phoenix-client.properties"
  printf '%s\n' 'UPSERT INTO T1 VALUES (1, 2);' 'SELECT ID, NAME FROM T1;' \
    >"$T_A5/sql/fixture.sql"
  # Materialize a stub driver whose run verb holds the phase@kill milestone anchor open for two
  # seconds so a disruption targeting that anchor fires while the phase is active.
  cat >"$T_WORK/drivers/phase-kill.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. Its run holds the anchor phase@kill open for two seconds. It
# records one steady-state metric inside the steady window, and one table.
pb_stage begin phase@kill
sleep 2
pb_stage end phase@kill
stub_window="$(pb_stage_window steady@stub)"
pb_metric query_errors count 0 errors steady@stub "${stub_window%%"$PX_TAB"*}" \
  "${stub_window#*"$PX_TAB"}"
pb_table "" T1 60000
DRIVER_EOF
  # Materialize a stub driver whose run verb opens steady@script after 1.5 seconds and holds it open
  # for half a second, creating an anchor window shorter than the main poll interval.
  cat >"$T_WORK/drivers/brief.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
sleep 1.5
pb_stage begin steady@script
sleep 0.5
pb_stage end steady@script
DRIVER_EOF
  # Materialize a stub driver whose run verb records the process state of the fake JVM from jvm-pid
  # into jvm-states twice per second for the duration in seconds given by its first argument.
  cat >"$T_WORK/drivers/jvm-probe.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
probe_pid="$(cat "$PX_FAKE_STATE_DIR/jvm-pid")"
probe_n=0
while [ "$probe_n" -lt $((${1:-3} * 2)) ]; do
  ps -o stat= -p "$probe_pid" | tr -d ' ' >>"$PX_FAKE_STATE_DIR/jvm-states"
  sleep 0.5
  probe_n=$((probe_n + 1))
done
DRIVER_EOF
  chmod +x "$T_WORK/drivers/phase-kill.sh" "$T_WORK/drivers/brief.sh" \
    "$T_WORK/drivers/jvm-probe.sh"
  # Create an external plug-in outside the default directory containing one failing check, one
  # check that cannot run, and a custom threshold definition of its own.
  mkdir -p "$T_A5/plugins/extra/checks" "$T_A5/plugins/extra/hooks"
  printf '%s\n' PX_PLUGIN_ID=extra PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=thresholds \
    >"$T_A5/plugins/extra/plugin.env"
  printf '#!/usr/bin/env bash\nprintf "extra.limit=7\\n"\n' >"$T_A5/plugins/extra/hooks/thresholds"
  cat >"$T_A5/plugins/extra/checks/a-fail" <<'A5_EOF'
#!/usr/bin/env bash
. "$PX_HOME/suites/lib/common.sh"
sx_verdict_row plugin.extra.a-fail FAIL count "limit $(sx_threshold extra.limit)"
A5_EOF
  printf '#!/usr/bin/env bash\nexit 3\n' >"$T_A5/plugins/extra/checks/b-broken"
  chmod +x "$T_A5/plugins/extra/hooks"/* "$T_A5/plugins/extra/checks"/*
  # Create invalid plug-in manifests that must be rejected: each omits a required key, targets an
  # unsupported contract version, specifies an unknown key, or collides with a built-in driver id.
  for name in noid nocontract contract2 badkey psql; do
    mkdir -p "$T_A5/refused/$name"
  done
  printf 'PX_PLUGIN_CONTRACT=1\n' >"$T_A5/refused/noid/plugin.env"
  printf 'PX_PLUGIN_ID=nocontract\n' >"$T_A5/refused/nocontract/plugin.env"
  printf 'PX_PLUGIN_ID=contract2\nPX_PLUGIN_CONTRACT=2\n' >"$T_A5/refused/contract2/plugin.env"
  printf 'PX_PLUGIN_ID=badkey\nPX_PLUGIN_CONTRACT=1\nPX_RS_HEAP=1\n' \
    >"$T_A5/refused/badkey/plugin.env"
  printf 'PX_PLUGIN_ID=psql\nPX_PLUGIN_CONTRACT=1\n' >"$T_A5/refused/psql/plugin.env"
  # Materialize the helper probe script for testing the bundle and plug-in libraries; it invokes a
  # single target function and propagates that function's exit status.
  cat >"$T_WORK/probe/a5.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/common.sh"
. "$PX_HOME/lib/plugin.sh"

## @description  Reports one value (file, JSON pointer) of a JSON document.
## @return       Writes the value to stdout. Exit 0, 1 not JSON, 6 absent.
t_a5_json_get() { local rows; rows="$(pb_json_flat "$1")" || return; pb_json_get "$2" "$rows"; }

## @description  Reports the type of one value (file, JSON pointer) of a JSON document.
## @return       Writes the type to stdout. Exit 0, 1 not JSON, 6 absent.
t_a5_json_type() { local rows; rows="$(pb_json_flat "$1")" || return; pb_json_type "$2" "$rows"; }

## @description  Reports one TSV row per element (file, array pointer, field...) of a JSON array.
## @return       Writes the rows to stdout. Exit 0, 1 not JSON.
t_a5_json_rows() {
  local rows
  rows="$(pb_json_flat "$1")" || return
  pb_json_rows "$rows" "${@:2}"
}

## @description  Mints run directories (count) under PX_OUT/runs, one after the other.
## @return       Writes each directory to stdout. Exit 0, else the status of px_run_dir.
t_a5_run_ids() {
  local n=0
  while [ "$n" -lt "$1" ]; do
    px_run_dir runs || return
    n=$((n + 1))
  done
}

"$@"
PROBE_EOF
  t_pherf_hdr_fixture "$T_A5/h2"
  t_a5_state_reset
}

## @description  Resets the A5 fake cluster to a running and ready RegionServer with the region
##               mover and a running and ready client 0, with no fault flag set.
## @return       Exit 0.
t_a5_state_reset() {
  rm -rf "${T_A5_STATE:?}"/*
  {
    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||regionmover\n' c0ffee00a501 regionserver \
      px-selftest-regionserver-1 running 'Up 3 minutes (healthy)' phoenix-harness:selftest \
      regionserver 1 0
    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee00a502 client px-selftest-client-1 running \
      'Up 3 minutes (healthy)' phoenix-harness:selftest client 1 0
  } >"$T_A5_STATE/instances.txt"
  printf '0\n' >"$T_A5_STATE/restart-count"
  printf 'sha256:f00dfeed00a5\n' >"$T_A5_STATE/image-id"
  # Remove recorded start times so an earlier test case does not add a restart to the next one.
  rm -f "$PX_OUT"/state/compose/starts-*
  t_seed_deployment "$PX_OUT"
}

## @description  Executes a command against the A5 fake cluster using the fake bootstrap probe and
##               the fake JDK of client 0.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a5() {
  t_with_shim "PATH=$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_FAKE_STATE_DIR=$T_A5_STATE" \
    "PX_JDK_ROOT=$T_A5/jdk" PX_JDK=fake "PX_CONF_DIR=$T_A5/conf" PX_LOG_DIR=/var/log/px \
    PX_POLL_INTERVAL_S=0.2 PX_CLUSTER_ID=selftest PX_PORT_REGIONSERVER_RPC_0=16020 "$@"
}

## @description  Executes a validation-class px-run invocation against the A5 fake cluster (driver,
##               option...) and leaves the cluster running.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a5_run() {
  t_a5 "$T_BASH" "$T_BIN/px-run" --class validation -e PX_SCOPE_ID=px-selftest \
    "$T_WORK/drivers/$1.sh" "${@:2}"
}

## @description  Reports the run bundle directory emitted on the first stdout line of the last
##               px-run invocation.
## @return       Writes the directory path to stdout. Exit 0.
t_bundle() { printf '%s\n' "$T_STDOUT" | sed -n 1p; }

## @description  Reports the worst status among px-bundle validate rows for a check (rows, check):
##               fail, skip, or ok, or an empty string when no row matches.
## @return       Writes the status word to stdout. Exit 0.
t_check_status() {
  printf '%s\n' "$1" | awk -F '\t' -v c="$2" '$1 == c { s[$2] = 1 }
    END { if ("fail" in s) print "fail"; else if ("skip" in s) print "skip"
      else if ("ok" in s) print "ok" }'
}

## @description  Executes a function from lib/bundle.sh or lib/plugin.sh inside the A5 probe script
##               (function, args).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_lib() { t_run "$T_BASH" "$T_WORK/probe/a5.sh" "$@"; }

## @description  Copies a bundle (source, case) to <PX_OUT>/copies/<case>/<run-id> so a test case
##               can mutate the copy and validate it under the original run identifier.
## @return       Writes the destination path to stdout. Exit 0.
t_bundle_copy() {
  local target="${3:-$PX_OUT}/copies/$2/${1##*/}"
  rm -rf "$target"
  mkdir -p "${target%/*}"
  cp -R "$1" "$target"
  printf '%s\n' "$target"
}

# ---------------------------------------------------------------------------
# A5: Contract verification suite
# ---------------------------------------------------------------------------
# All test cases execute against fake fixtures: stub drivers, fixture bundles, fixture verdict
# streams, the fake runtime hosting client 0, a fake JDK, and a fake bootstrap probe. Any test case
# identifier referenced by a requirement retains that exact name.

## @description  Lists the shipped drivers, including built-in drivers and the example plug-in
##               driver, as name and path pairs.
## @return       Writes one "name path" line per driver to stdout. Exit 0.
t_a5_drivers() {
  printf '%s %s\n' psql "$PX_HOME/drivers/psql/driver.sh" pherf "$PX_HOME/drivers/pherf/driver.sh" \
    java-main "$PX_HOME/drivers/java-main/driver.sh" example "$PX_HOME/plugins/example/driver.sh"
}

## @description  Executes the describe verb of a driver (name, path) in the minimal environment used
##               by px-run, passing only PX_HOME, PX_DRIVER_NAME, and PX_VERB.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a5_describe() {
  t_run env -i "PATH=$PATH" "HOME=${HOME:-}" "TMPDIR=${TMPDIR:-/tmp}" "PX_HOME=$PX_HOME" \
    "PX_DRIVER_NAME=$1" PX_VERB=describe "$T_BASH" "$2" describe
}

## @description  Verifies the driver contract (R-058, R-059, R-060): contract documentation, the
##               six verbs and exit codes of each shipped driver, describe JSON, and boundary G4.
## @return       Records test assertions. Exit 0.
t_group_a5_contract() {
  local doc="$PX_HOME/docs/driver-contract.md" verb code name path missing="" bad="" rows
  local json="$T_A5/describe" hits
  printf '# A5: the driver contract\n'
  mkdir -p "$json"
  for verb in describe validate prepare run restore report; do
    grep -q "^| \`$verb\` |" "$doc" || missing="$missing $verb"
  done
  t_empty contract_doc_lists_the_six_verbs "$missing"
  missing=""
  for code in '| 0 | ok |' '| 1 | failure |' '| 2 | usage |' '| 3 | anomaly |'; do
    grep -qF "$code" "$doc" || missing="$missing [$code]"
  done
  t_empty contract_doc_lists_the_four_codes "$missing"
  t_contains contract_doc_names_the_abort_code "abort code of \`px-run\`" "$(cat "$doc")"
  while read -r name path; do
    missing=""
    for verb in describe validate prepare run restore report; do
      grep -q "^verb_$verb() {" "$path" || missing="$missing $verb"
    done
    [ -z "$missing" ] || bad="$bad $name lacks$missing;"
    t_run env -i "PATH=$PATH" "PX_HOME=$PX_HOME" "PX_DRIVER_NAME=$name" "$T_BASH" "$path" frobnicate
    [ "$T_STATUS" = 2 ] || bad="$bad $name gave $T_STATUS for an unknown verb;"
    t_run env -i "PATH=$PATH" "PX_HOME=$PX_HOME" "PX_DRIVER_NAME=$name" "$T_BASH" "$path"
    [ "$T_STATUS" = 2 ] || bad="$bad $name gave $T_STATUS for no verb;"
  done <<EOF
$(t_a5_drivers)
EOF
  t_empty drivers_implement_six_verbs_and_refuse_an_unknown_one "$bad"
  # Verify requirement R-059: each driver describe document parses as valid JSON and contains the
  # phases, metrics, and unsupported top-level members.
  bad=""
  while read -r name path; do
    t_a5_describe "$name" "$path"
    printf '%s\n' "$T_STDOUT" >"$json/$name.json"
    [ "$T_STATUS" = 0 ] || bad="$bad $name: describe gave $T_STATUS;"
    t_lib pb_json_flat "$json/$name.json"
    rows=$T_STDOUT
    [ "$T_STATUS" = 0 ] || bad="$bad $name: not JSON;"
    for path in /phases:array /metrics:object /metrics/count:array /metrics/share:array \
      /metrics/duration:array /unsupported:object /unsupported/checks:array \
      /unsupported/artifacts:array; do
      printf '%s\n' "$rows" | grep -q "^${path%%:*}$PX_TAB${path#*:}$PX_TAB" ||
        bad="$bad $name: no ${path%%:*} of type ${path#*:};"
    done
    t_lib pb_describe_load "$json/$name.json"
    [ "$T_STATUS" = 0 ] || bad="$bad $name: pb_describe_load gave $T_STATUS;"
  done <<EOF
$(t_a5_drivers)
EOF
  t_empty describe_of_every_driver_parses_with_phases_metrics_unsupported "$bad"
  printf '{"contract": 2}\n' >"$json/two.json"
  t_lib pb_describe_load "$json/two.json"
  t_equals describe_of_another_contract_is_refused 2 "$T_STATUS"
  # Verify requirement R-060: no driver or plug-in references a container runtime or Kubernetes CLI
  # tool directly. Any match fails this assertion, directly mirroring the acceptance criterion.
  hits="$(grep -rnE "$T_RUNTIME_RE" "$PX_HOME/drivers" "$PX_HOME/plugins" 2>/dev/null)"
  t_empty drivers_use_contract_only "$hits"
  hits="$(grep -rnE "$T_K8S_RE" "$PX_HOME/drivers" "$PX_HOME/plugins" 2>/dev/null)"
  t_empty drivers_name_no_kubernetes_tool "$hits"
}

## @description  Executes one verb of a built-in driver (driver, verb, run dir, argument...) against
##               the A5 fake cluster with the px-run verb environment and client 0 home directories.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a5_verb() {
  local driver=$1 verb=$2 run=$3
  shift 3
  t_a5 "PX_RUN_DIR=$run" "PX_RUN_ID=${run##*/}" "PX_DRIVER_NAME=$driver" \
    "PX_DRIVER_DIR=$run/drivers/$driver" "PX_VERB=$verb" "PX_RECORD_SOURCE=driver:$driver" \
    PX_RUN_CLASS=validation PX_BACKEND=compose PX_SCOPE_ID=px-selftest \
    "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" \
    "$T_BASH" "$PX_HOME/drivers/$driver/driver.sh" "$verb" "$@"
}

## @description  Reports the classpath passed to the most recent fake Java invocation that executed
##               the specified class (class).
## @return       Writes the -cp argument value to stdout, or empty if no call ran the class. Exit 0.
t_java_cp() {
  awk -v c="$1" '{ cp = ""; for (i = 1; i < NF; i++) if ($i == "-cp") cp = $(i + 1)
    for (i = 1; i <= NF; i++) if ($i == c) { last = cp; break } }
    END { print last }' "$T_A5_STATE/java.log" 2>/dev/null
}

## @description  Creates an empty run bundle for a driver fixture and outputs its directory path.
## @return       Writes the bundle directory path to stdout. Exit 0.
t_a5_bundle() {
  t_run "$T_BASH" "$T_WORK/probe/a5.sh" pb_bundle_create
  printf '%s\n' "$T_STDOUT"
}

## @description  Verifies the three built-in drivers against the fake cluster (R-061, R-062, R-063).
## @return       Records test assertions. Exit 0.
t_group_a5_drivers() {
  local run statements aggregate csv groups line
  printf '# A5: the built-in drivers\n'
  t_a5_state_reset
  # Verify requirement R-061: the psql driver executes a fixture SQL script through the fake
  # PhoenixRuntime, and its report verb records per-statement row counts and total script duration.
  run="$(t_a5_bundle)"
  t_a5_verb psql run "$run" "$T_A5/sql/fixture.sql"
  t_equals psql_run_exits_0 0 "$T_STATUS"
  t_a5_verb psql report "$run" "$T_A5/sql/fixture.sql"
  t_equals psql_report_exits_0 0 "$T_STATUS"
  statements="$(cat "$run/drivers/psql/statements.tsv" 2>/dev/null)"
  t_contains psql_records_the_rows_of_the_update \
    "$(t_rows 6 "$T_A5/sql/fixture.sql" 1 1 upserted 3 12)" "$statements"
  t_contains psql_records_the_rows_of_the_query \
    "$(t_rows 6 "$T_A5/sql/fixture.sql" 1 2 selected 2 34)" "$statements"
  aggregate="$(cat "$run/metrics/aggregate.tsv" 2>/dev/null)"
  t_contains psql_records_the_file_duration \
    "driver:psql${PX_TAB}steady@script${PX_TAB}psql.file_duration_ms${PX_TAB}duration" \
    "$aggregate"
  t_contains psql_records_the_statement_count \
    "steady@script${PX_TAB}psql.statements${PX_TAB}count${PX_TAB}2${PX_TAB}" "$aggregate"
  t_contains psql_lists_scans_unsupported '"key": "driver.storage_scans"' \
    "$(cat "$T_A5/describe/psql.json")"
  t_contains psql_lists_rpcs_unsupported '"key": "driver.rpc_calls"' \
    "$(cat "$T_A5/describe/psql.json")"
  t_contains psql_runs_phoenix_runtime_in_client_0 'org.apache.phoenix.util.PhoenixRuntime' \
    "$(cat "$T_A5_STATE/java.log" 2>/dev/null)"
  t_contains psql_puts_the_phoenix_client_jars_on_the_class_path ":$T_A5/phoenix/client/*:" \
    ":$(t_java_cp org.apache.phoenix.util.PhoenixRuntime):"
  # Verify requirement R-062: the pherf driver converts a RESULT_*_aggregate.csv file carrying the
  # Header.java header into one aggregated metric interval per query group.
  run="$(t_a5_bundle)"
  mkdir -p "$run/drivers/pherf/results"
  csv="$run/drivers/pherf/results/RESULT_px_functional_aggregate.csv"
  {
    printf '%s\n' "START_TIME,QUERY_GROUP,QUERY,TENANT_ID,AVG_MAX_TIME_MS,AVG_TIME_MS,\
AVG_MIN_TIME_MS,RUN_COUNT,EXPLAIN_PLAN,RESULT_ROW_COUNT"
    printf '%s\n' '2026-11-20 17:00:01,count,"SELECT COUNT(*) FROM T",,12,10,8,5,"SCAN T",1'
    printf '%s\n' '2026-11-20 17:00:02,scan,"SELECT * FROM T, U",,30,20,10,4,"FULL, SCAN",250'
  } >"$csv"
  printf 'start_ms\tend_ms\tstatus\n%s\t%s\t0\n' 1795194000000 1795194600000 \
    >"$run/drivers/pherf/run.tsv"
  printf '%s\tbegin\tsteady@pherf\tdriver:pherf\t\n%s\tend\tsteady@pherf\tdriver:pherf\t\n' \
    1795194000000 1795194600000 >"$run/stages.tsv"
  t_a5_verb pherf report "$run" --scenario "$T_A5/sql/fixture.sql"
  t_equals pherf_report_exits_0 0 "$T_STATUS"
  groups="$(cat "$run/drivers/pherf/groups.tsv" 2>/dev/null)"
  t_contains pherf_emits_the_count_group "count${PX_TAB}group@count${PX_TAB}" "$groups"
  t_contains pherf_emits_the_scan_group "scan${PX_TAB}group@scan${PX_TAB}" "$groups"
  aggregate="$(cat "$run/metrics/aggregate.tsv" 2>/dev/null)"
  line="$(printf '%s\n' "$aggregate" |
    awk -F '\t' '$4 == "group@scan" && $5 == "pherf.result_rows"')"
  t_contains pherf_group_interval_carries_its_rows "${PX_TAB}count${PX_TAB}250${PX_TAB}" "$line"
  t_equals pherf_group_interval_lies_in_the_steady_window yes \
    "$(printf '%s\n' "$line" | awk -F '\t' '{ print ($1 >= 1795194000000 && \
      $2 <= 1795194600000 && $1 <= $2) ? "yes" : "no" }')"
  # Verify requirement RQ-207: a Pherf run that produces only the aggregate CSV without the latency
  # report or verdict file still emits the query-group metrics and run rate, without any latency
  # percentiles.
  t_equals pherf_csv_only_fixture_still_produces_metrics "PASS 9 0.015 SKIP SKIP" \
    "$(t_severity_of "$run" pherf.results) $(t_h2_value "$run" steady@pherf pherf.run_count)\
 $(t_h2_value "$run" steady@pherf achieved_qps) $(t_severity_of "$run" pherf.latency)\
 $(t_severity_of "$run" pherf.verdict)"
  t_absent pherf_csv_only_fixture_has_no_percentile "${PX_TAB}query_latency." "$aggregate"
  # Verify requirement R-063: the java-main driver executes a fixture class that prints two ad hoc
  # metrics, passes the JDBC URL and its request metrics attribute via px.jdbc.url, and exports
  # both metrics to the aggregate metric table.
  run="$(t_a5_bundle)"
  : >"$T_A5_STATE/java.log"
  t_a5_verb java-main run "$run" org.example.Fixture one two
  t_equals java_main_run_exits_0 0 "$T_STATUS"
  t_contains java_main_turns_request_metrics_on \
    '-Dpx.jdbc.url=jdbc:phoenix:zookeeper-0:2181;RequestMetric=true' \
    "$(cat "$T_A5_STATE/java.log" 2>/dev/null)"
  t_contains java_main_runs_the_class_with_its_arguments 'org.example.Fixture one two' \
    "$(cat "$T_A5_STATE/java.log" 2>/dev/null)"
  t_contains java_main_puts_the_phoenix_client_jars_on_the_class_path \
    ":$T_A5/phoenix/client/*:" ":$(t_java_cp org.example.Fixture):"
  t_a5_verb java-main report "$run" org.example.Fixture one two
  aggregate="$(cat "$run/metrics/aggregate.tsv" 2>/dev/null)"
  t_contains java_main_records_the_first_metric \
    "steady@main${PX_TAB}fixture.rows${PX_TAB}count${PX_TAB}42${PX_TAB}rows" "$aggregate"
  t_contains java_main_records_the_second_metric \
    "steady@main${PX_TAB}fixture.share${PX_TAB}share${PX_TAB}0.5${PX_TAB}ratio" "$aggregate"
  t_absent java_main_keeps_program_lines_out_of_the_metrics 'starting the fixture' "$aggregate"
  # Verify that java-main --prepare provisions a dedicated user table for the target class using
  # the same lifecycle semantics as the psql driver: the prepare verb executes each SQL file inside
  # the setup@schema window and records the tables it creates, while the restore verb drops those
  # tables so the cluster returns to its initial state.
  run="$(t_a5_bundle)"
  : >"$T_A5_STATE/java.log"
  line=""
  for csv in prepare run restore; do
    t_a5_verb java-main "$csv" "$run" --prepare "$PX_HOME/suites/sql/smoke-schema.sql" \
      org.apache.phoenix.schema.tool.SchemaTool -m EXTRACT -tb ITEMS -s PX_SMOKE
    line="$line $csv:$T_STATUS"
  done
  t_equals java_main_prepare_runs_its_sql_in_setup_schema " prepare:0 run:0 restore:0 yes yes\
 yes" "$line $(t_flag "${PX_TAB}begin${PX_TAB}setup@schema$PX_TAB" "$(cat "$run/stages.tsv" \
    2>/dev/null)") $(t_flag 'org.apache.phoenix.util.PhoenixRuntime' "$(cat \
    "$T_A5_STATE/java.log" 2>/dev/null)") $(t_flag '/sql/smoke-schema.sql' "$(cat \
    "$T_A5_STATE/java.log" 2>/dev/null)")"
  t_equals java_main_restore_drops_the_tables_of_its_prepare \
    'DROP TABLE IF EXISTS "PX_SMOKE"."ITEMS" CASCADE;' \
    "$(cat "$run/drivers/java-main/restore.sql" 2>/dev/null)"
  t_contains java_main_runs_the_class_after_its_prepare \
    'SchemaTool -m EXTRACT -tb ITEMS -s PX_SMOKE' "$(cat "$T_A5_STATE/java.log" 2>/dev/null)"
  # Because the fake catalog reports every table without an explicit schema name, this test case
  # inspects a table created in the default schema.
  printf 'CREATE TABLE JM1 (ID INTEGER NOT NULL PRIMARY KEY) UPDATE_CACHE_FREQUENCY=60000;\n' \
    >"$T_A5/sql/jm-schema.sql"
  run="$(t_a5_bundle)"
  t_a5_verb java-main prepare "$run" --prepare "$T_A5/sql/jm-schema.sql" org.example.Fixture
  t_contains java_main_records_the_tables_of_its_prepare "$(t_rows 3 default JM1 60000)" \
    "$(cat "$run/tables.tsv" 2>/dev/null)"
  printf 'SELECT 1;\n' >"$T_A5/sql/prepare.txt"
  t_a5_verb java-main validate "$run" --prepare "$T_A5/sql/prepare.txt" org.example.Fixture
  t_equals java_main_refuses_a_prepare_file_that_is_not_sql "1 FAIL" "$T_STATUS $(t_severity_of \
    "$run" java-main.prepare)"
  run="$(t_a5_bundle)"
  : >"$T_A5_STATE/java.log"
  t_a5_verb java-main prepare "$run" org.example.Fixture
  t_equals java_main_without_prepare_opens_no_setup_window "0 no no" "$T_STATUS $(t_flag \
    setup@schema "$(cat "$run/stages.tsv" 2>/dev/null)") $(t_flag PhoenixRuntime "$(cat \
    "$T_A5_STATE/java.log" 2>/dev/null)")"
  t_a5_describe java-main "$PX_HOME/drivers/java-main/driver.sh"
  t_equals java_main_describes_its_setup_anchor "setup@schema prepare steady@main run" \
    "$(printf '%s\n' "$T_STDOUT" | sed -nE 's/^ *"(anchor|verb)": "(.*)",?$/\2/p' |
      tr '\n' ' ' | sed 's/ $//')"
  # Confirm that remote staging for fake client 0 writes only inside the fake state directory and
  # never directly under /tmp/px on the host.
  t_equals drivers_stage_only_below_the_fake_client 0 \
    "$(find /tmp/px -maxdepth 1 -name "${run##*/}" 2>/dev/null | grep -c .)"
}
## @description  Executes px-bundle validate against a run bundle directory (dir).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_validate() {
  local root=${1%/runs/*}
  [ "$root" != "$1" ] || root=${1%/copies/*}
  [ "$root" != "$1" ] || root=$PX_OUT
  t_run env "PX_OUT=$root" "$T_BASH" "$T_BIN/px-bundle" validate "$1"
}

## @description  Extracts a value (bundle, JSON pointer) from the manifest.json file of a bundle.
## @return       Writes the resolved value to stdout, or empty when absent. Exit 0.
t_manifest() {
  t_lib t_a5_json_get "$1/manifest.json" "$2"
  printf '%s\n' "$T_STDOUT"
}

## @description  Replaces literal old text with new text (file, anchor, old, new) on the first line
##               containing the old text at or after the first line matching the literal anchor.
## @return       Exit 0.
t_json_edit() {
  awk -v a="$2" -v o="$3" -v n="$4" '
    !hit && index($0, a) { seen = 1 }
    seen && !hit && (i = index($0, o)) {
      $0 = substr($0, 1, i - 1) n substr($0, i + length(o)); hit = 1
    }
    { print }' "$1" >"$1.new" && mv -f "$1.new" "$1"
}

## @description  Verifies milestone-triggered disruption (R-080) and validates the resulting base
##               run bundle and its mutated copies (R-064 to R-068, R-279).
## @return       Records test assertions. Exit 0.
t_group_a5_bundle() {
  local base run record stages begin end inj rec act copy rows path bad="" one two sum
  printf '# A5: the disruption and the bundle\n'
  t_a5_state_reset
  t_shim_log_reset
  # Verify requirement R-080: inject a RegionServer kill at the phase@kill milestone anchor opened
  # during the driver's run verb.
  t_a5_run phase-kill --disrupt 'phase:phase@kill,kill,regionserver,0'
  base="$(t_bundle)"
  T_A5_BASE=$base
  t_equals disruption_run_exits_0 0 "$T_STATUS"
  record="$(cat "$base/run-record.tsv" 2>/dev/null)"
  stages="$(cat "$base/stages.tsv" 2>/dev/null)"
  begin="$(printf '%s\n' "$stages" |
    awk -F '\t' '$2 == "begin" && $3 == "phase@kill" { print $1 }')"
  end="$(printf '%s\n' "$stages" | awk -F '\t' '$2 == "end" && $3 == "phase@kill" { print $1 }')"
  act="$(printf '%s\n' "$record" | awk -F '\t' '$1 == "action" && $3 == "kill" { print $2 }')"
  inj="$(printf '%s\n' "$record" | awk -F '\t' '$1 == "recover" { print $3 }')"
  rec="$(printf '%s\n' "$record" | awk -F '\t' '$1 == "recover" { print $4 }')"
  t_equals disruption_fires_inside_its_milestone yes "$(awk -v b="${begin:-0}" \
    -v e="${end:-0}" -v i="${inj:-0}" -v a="${act:-0}" \
    'BEGIN { print (b > 0 && i >= b && a >= i && i <= e) ? "yes" : "no" }')"
  t_contains disruption_kills_the_named_instance 'KILL c0ffee00a501' \
    "$(cat "$T_SHIM_LOG")"
  t_contains recovery_gate_starts_the_killed_instance 'start c0ffee00a501' "$(cat "$T_SHIM_LOG")"
  t_contains recovery_gate_passes "$(t_rows 5 recover 1 "$inj" "$rec" ok)" "$record"
  t_equals recovery_gate_ends_after_the_injection yes \
    "$([ "${rec:-0}" -ge "${inj:-1}" ] && echo yes || echo no)"
  t_equals disruption_is_in_the_manifest "kill regionserver phase:phase@kill ok" \
    "$(t_manifest "$base" /disruptions/0/action) $(t_manifest "$base" /disruptions/0/role)\
 $(t_manifest "$base" /disruptions/0/when) $(t_manifest "$base" /disruptions/0/recovery)"
  t_equals disruption_mttr_is_a_number number \
    "$(t_lib t_a5_json_type "$base/manifest.json" /disruptions/0/mttr_seconds; echo "$T_STDOUT")"
  t_equals disruption_mttr_is_the_recovery_time \
    "$(awk -v r="${rec:-0}" -v i="${inj:-0}" 'BEGIN { printf "%.3f", (r - i) / 1000 }')" \
    "$(t_manifest "$base" /disruptions/0/mttr_seconds)"
  t_contains recovery_check_passes "cluster.recovery${PX_TAB}PASS${PX_TAB}none" \
    "$(cat "$base/verdicts.tsv" 2>/dev/null)"
  t_contains post_recovery_check_passes "cluster.post_recovery${PX_TAB}PASS${PX_TAB}count" \
    "$(cat "$base/verdicts.tsv" 2>/dev/null)"
  t_contains post_recovery_runs_the_bootstrap_probe 'probe ' \
    "$(cat "$T_A5_STATE/probe.log" 2>/dev/null)"
  # Confirm that the recovery gate blocks until the target role becomes ready: an instance that
  # remains unready holds the gate until the readiness timeout expires, leaving mttr_seconds null.
  t_a5_state_reset
  : >"$T_A5_STATE/start-unready"
  t_a5_run phase-kill --disrupt 'phase:phase@kill,kill,regionserver,0' -e PX_TIMEOUT_READY=2
  run="$(t_bundle)"
  t_equals recovery_gate_waits_for_readiness_then_fails 1 "$T_STATUS"
  t_contains recovery_gate_records_the_timeout "recover${PX_TAB}1${PX_TAB}" \
    "$(grep "${PX_TAB}timeout\$" "$run/run-record.tsv" 2>/dev/null)"
  t_equals recovery_timeout_has_no_mttr null "$(t_lib t_a5_json_type "$run/manifest.json" \
    /disruptions/0/mttr_seconds; echo "$T_STDOUT")"
  t_contains recovery_timeout_fails_the_check "cluster.recovery${PX_TAB}FAIL" \
    "$(cat "$run/verdicts.tsv" 2>/dev/null)"
  t_validate "$run"
  t_equals null_mttr_still_validates ok "$(t_check_status "$T_STDOUT" disruptions)"
  rm -f "$T_A5_STATE/start-unready"

  # Verify requirements R-064 through R-068: the base bundle passes validation, whereas each
  # structural or schema defect introduced below is rejected.
  # Verify requirement R-059: checks and artifacts declared unsupported by the driver's describe
  # document are recorded as unavailable, and the overall run still passes.
  t_contains unsupported_check_is_recorded_unavailable \
    "query_latency.p50_ms${PX_TAB}SKIP${PX_TAB}none${PX_TAB}px-run${PX_TAB}unavailable: a stub" \
    "$(cat "$base/verdicts.tsv" 2>/dev/null)"
  t_lib t_a5_json_rows "$base/manifest.json" /artifacts path status required
  t_contains unsupported_artifact_is_unavailable_in_the_manifest \
    "oplog/${PX_TAB}unavailable${PX_TAB}false" "$T_STDOUT"
  t_validate "$base"
  t_equals bundle_of_a_full_run_validates 0 "$T_STATUS"
  rows=$T_STDOUT
  copy="$(t_bundle_copy "$base" nomanifest)"
  rm -f "$copy/manifest.json"
  t_validate "$copy"
  t_equals bundle_without_manifest_is_refused "1 fail" "$T_STATUS $(t_check_status \
    "$T_STDOUT" manifest)"
  copy="$(t_bundle_copy "$base" noversion)"
  t_json_edit "$copy/manifest.json" '"bundle_format_version"' bundle_format_version bundle_format
  t_validate "$copy"
  t_equals bundle_without_format_version_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" format)"
  copy="$(t_bundle_copy "$base" version2)"
  t_json_edit "$copy/manifest.json" '"bundle_format_version"' ': 1,' ': 2,'
  t_validate "$copy"
  t_equals bundle_of_an_unknown_format_version_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" format)"
  t_contains bundle_doc_states_the_run_id_grammar '^[0-9]{8}T[0-9]{6}Z-[0-9a-z]{4}$' \
    "$(cat "$PX_HOME/docs/bundle-format.md")"
  t_lib t_a5_run_ids 12
  one=$T_STDOUT
  t_equals run_ids_of_one_second_never_collide 12 \
    "$(printf '%s\n' "$one" | sort -u | grep -cE '/[0-9]{8}T[0-9]{6}Z-[0-9a-z]{4}$')"
  # Verify requirement RQ-111: generated run identifiers sort lexicographically in creation order,
  # even when minted within the same second.
  t_equals run_ids_sort_in_creation_order "$one" "$(printf '%s\n' "$one" | LC_ALL=C sort)"
  "$T_BASH" "$T_WORK/probe/a5.sh" t_a5_run_ids 3 >"$T_A5/ids-1" &
  one=$!
  "$T_BASH" "$T_WORK/probe/a5.sh" t_a5_run_ids 3 >"$T_A5/ids-2"
  wait "$one"
  t_equals run_ids_of_two_processes_never_collide 6 \
    "$(cat "$T_A5/ids-1" "$T_A5/ids-2" | sort -u | grep -c .)"
  # Verify requirement R-065: the manifest must include all five steady-state metric keys, the
  # disruption mttr_seconds key, and a steady@ phase anchor.
  for path in query_latency.p50_ms query_latency.p99_ms query_errors offered_rate achieved_qps; do
    copy="$(t_bundle_copy "$base" "nokey-$path")"
    t_json_edit "$copy/manifest.json" '"steady"' "\"$path\"" "\"x-$path\""
    t_validate "$copy"
    [ "$T_STATUS $(t_check_status "$T_STDOUT" steady)" = "1 fail" ] ||
      bad="$bad $path gave $T_STATUS;"
  done
  copy="$(t_bundle_copy "$base" nomttr)"
  t_json_edit "$copy/manifest.json" '"disruptions"' '"mttr_seconds"' '"mttr"'
  t_validate "$copy"
  [ "$T_STATUS $(t_check_status "$T_STDOUT" disruptions)" = "1 fail" ] || bad="$bad mttr;"
  t_empty bundle_without_a_steady_metric_key_is_refused "$bad"
  copy="$(t_bundle_copy "$base" nosteady)"
  t_json_edit "$copy/manifest.json" '"steady"' '"steady@stub"' '"other@stub"'
  t_validate "$copy"
  t_equals bundle_without_a_steady_anchor_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" steady)"
  t_equals bundle_steady_metric_has_a_value 0 "$(t_manifest "$base" \
    '/steady/0/metrics/query_errors/value')"
  copy="$(t_bundle_copy "$base" outside)"
  t_json_edit "$copy/manifest.json" '"query_errors": {' '"start_ms": ' '"start_ms": 1, "x_ms": '
  t_validate "$copy"
  t_equals bundle_metric_outside_its_window_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" steady)"
  # Verify requirement R-066: the manifest records the run class, backend, container image digests,
  # table UPDATE_CACHE_FREQUENCY property, and quiet-host contamination tags.
  t_equals bundle_records_class_backend_image_table_tags \
    "validation compose sha256:f00dfeed00a5 60000 quiet-host" \
    "$(t_manifest "$base" /run/class) $(t_manifest "$base" /run/backend)\
 $(t_manifest "$base" /images/0/image_id) $(t_manifest "$base" /tables/0/update_cache_frequency)\
 $(t_manifest "$base" /quiet_host/tags/0)"
  bad=""
  for path in '"run"|"class"|"klass"|class' '"run"|"backend": "compose"|"backend": ""|backend' \
    '"images"|"image_id"|"image"|images' \
    '"tables"|"update_cache_frequency"|"ucf"|tables' '"quiet_host"|"tags"|"tag"|quiet_host'; do
    IFS='|' read -r one two sum rows <<<"$path"
    copy="$(t_bundle_copy "$base" "field-$rows")"
    t_json_edit "$copy/manifest.json" "$one" "$two" "$sum"
    t_validate "$copy"
    [ "$T_STATUS $(t_check_status "$T_STDOUT" "$rows")" = "1 fail" ] ||
      bad="$bad $rows gave $T_STATUS $(t_check_status "$T_STDOUT" "$rows");"
  done
  rows="$(t_validate "$base"; printf '%s\n' "$T_STDOUT")"
  t_empty bundle_without_a_recorded_field_is_refused "$bad"
  # Verify requirement R-052 and invariant G16: a run that completes without an abort sets the
  # anomaly field to null, and a manifest omitting the anomaly member is rejected.
  t_equals clean_run_has_no_anomaly null \
    "$(t_lib t_a5_json_type "$base/manifest.json" /anomaly; echo "$T_STDOUT")"
  copy="$(t_bundle_copy "$base" noanomaly)"
  grep -v '^  "anomaly": null,$' "$copy/manifest.json" >"$copy/m.json"
  mv -f "$copy/m.json" "$copy/manifest.json"
  t_validate "$copy"
  t_equals manifest_without_the_anomaly_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" anomaly)"
  # Verify requirement R-067: px-bundle validate checks every required path and identifies missing
  # entries by name. The expected list is derived directly from the layout table in
  # docs/bundle-format.md for a cluster run with a surviving instance and the phase-kill driver,
  # rather than from the bundle's own manifest, so a faulty requirement condition in the validator
  # code is caught by this test.
  bad=""
  for path in run-record.tsv stages.tsv verdicts.tsv metrics/timeline.tsv \
    metrics/aggregate.tsv cluster/images.tsv report/verdicts.tsv report/run-summary.tsv \
    report/cluster-summary.tsv cluster/topology.env cluster/services.tsv logs/instance/ \
    drivers/phase-kill/describe.json logs/describe.log logs/validate.log logs/prepare.log \
    logs/run.log logs/restore.log logs/report.log; do
    copy="$(t_bundle_copy "$base" "path-${path//\//-}")"
    rm -rf "${copy:?}/$path"
    t_validate "$copy"
    if [ "$T_STATUS" != 1 ] || ! printf '%s\n' "$T_STDOUT" |
      grep -q "^path${PX_TAB}fail${PX_TAB}$path"; then
      bad="$bad $path;"
    fi
  done
  t_empty bundle_without_a_required_path_is_refused_by_name "$bad"
  t_equals bundle_requires_the_documented_paths 0 "$(for path in run-record.tsv stages.tsv \
    verdicts.tsv metrics/timeline.tsv metrics/aggregate.tsv cluster/images.tsv \
    cluster/topology.env cluster/services.tsv logs/instance/ report/verdicts.tsv \
    report/run-summary.tsv report/cluster-summary.tsv manifest.json; do
      grep -q "^| \`$path\` |" "$PX_HOME/docs/bundle-format.md" || echo "$path"
      [ -e "$base/$path" ] || echo "$path"
    done | grep -c .)"
  # Verify requirement R-068: a bundle missing all optional artifacts still passes validation, and
  # each absent optional path is reported as a skip row.
  copy="$(t_bundle_copy "$base" nooptional)"
  rm -rf "$copy/breach.tsv" "$copy/tables.tsv" "$copy/thresholds.properties" \
    "$copy/cluster/plugins" "$copy/logs/gc" "$copy/metrics/index.tsv" \
    "$copy/profiles" "$copy/jfr" "$copy/traces" "$copy/oplog" "$copy/meta" \
    "$copy/plugins"
  t_validate "$copy"
  t_equals bundle_without_optional_artifacts_validates 0 "$T_STATUS"
  bad=""
  for path in breach.tsv tables.tsv thresholds.properties cluster/plugins/ logs/gc/ \
    metrics/index.tsv profiles/ jfr/ traces/ meta/; do
    printf '%s\n' "$T_STDOUT" | grep -q "^path${PX_TAB}skip${PX_TAB}$path" || bad="$bad $path"
  done
  # Verify requirement R-064: the plugins context row in run-record.tsv lists the enabled plug-ins,
  # so an enabled plug-in that wrote no manifest.tsv or omitted a file listed in its manifest.tsv
  # produces a skip row rather than a validation failure.
  printf 'context\tplugins\texample\n' >>"$copy/run-record.tsv"
  t_validate "$copy"
  printf '%s\n' "$T_STDOUT" | grep -q "^path${PX_TAB}skip${PX_TAB}plugins/example/manifest.tsv" ||
    bad="$bad plugins/example/manifest.tsv"
  mkdir -p "$copy/plugins/example"
  printf 'path\tdescription\nlost.txt\tone file that the plug-in never wrote\n' \
    >"$copy/plugins/example/manifest.tsv"
  t_validate "$copy"
  printf '%s\n' "$T_STDOUT" | grep -q "^path${PX_TAB}skip${PX_TAB}plugins/example/lost.txt" ||
    bad="$bad plugins/example/lost.txt"
  printf '%s\n' "$T_STDOUT" | grep -q "^path${PX_TAB}ok${PX_TAB}plugins/example/manifest.tsv" ||
    bad="$bad present plugins/example/manifest.tsv"
  t_empty bundle_reports_each_absent_optional_path_skipped "$bad"
  t_contains bundle_reports_an_unsupported_artifact_unavailable \
    "path${PX_TAB}skip${PX_TAB}oplog/ is unavailable" "$T_STDOUT"

  # Verify requirement R-279: two consecutive runs produce two independent, versioned bundles
  # without mutating any file in the earlier bundle.
  t_a5_state_reset
  t_a5_run exit-code -- 0
  one="$(t_bundle)"
  sum="$(find "$one" -type f -exec cksum {} + | sort)"
  t_a5_run exit-code -- 0
  two="$(t_bundle)"
  t_equals two_runs_write_two_bundles yes \
    "$([ -n "$one" ] && [ -d "$two" ] && [ "$one" != "$two" ] && echo yes || echo no)"
  t_equals second_run_changes_no_file_of_the_first "$sum" \
    "$(find "$one" -type f -exec cksum {} + | sort)"
  t_equals each_manifest_carries_the_format_version "1 1" \
    "$(t_manifest "$one" /bundle_format_version) $(t_manifest "$two" /bundle_format_version)"
  t_empty harness_holds_no_workload_logic_outside_a_driver "$(grep -nE \
    'PhoenixRuntime|org\.apache\.phoenix\.pherf|jdbc:phoenix|UPSERT|SELECT ' "$T_BIN/px-run" \
    "$T_BIN/px-report" "$T_BIN/px-bundle" "$PX_HOME/lib/bundle.sh" "$PX_HOME/lib/plugin.sh" \
    "$PX_HOME/suites/check-cluster.sh" "$PX_HOME/suites/checks/cluster.sh" 2>/dev/null)"
  # Verify invariant G23 and requirement R-048: every metric record carries the run class and
  # contamination tags in force when the record was written.
  t_equals metric_records_carry_class_and_contamination "validation quiet-host" \
    "$(awk -F '\t' 'NR == 2 { print $9 " " $10 }' "$base/metrics/aggregate.tsv" 2>/dev/null)"
  t_a5_state_reset
}
## @description  Reports the numeric lattice rank of a verdict severity, from 0 (PASS) to 4 (ERROR).
## @return       Writes the numeric rank to stdout. Exit 0.
t_rank() {
  case "$1" in
  PASS) echo 0 ;;
  SKIP) echo 1 ;;
  ADVISORY) echo 2 ;;
  FAIL) echo 3 ;;
  *) echo 4 ;;
  esac
}

## @description  Reports the documented process exit code for a merged result word: 0, 1, or 3.
## @return       Writes the exit code to stdout. Exit 0.
t_code_of() { case "$1" in PASS | SKIP | ADVISORY) echo 0 ;; ERROR) echo 3 ;; *) echo 1 ;; esac; }

## @description  Executes px-report against fixture verdict streams (class, contamination,
##               argument...).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_report() {
  t_run env "PX_RUN_CLASS=$1" "PX_CONTAMINATION=$2" "$T_BASH" "$T_BIN/px-report" "${@:3}"
}

## @description  Extracts the overall result word from the most recent px-report stdout stream.
## @return       Writes the result word to stdout. Exit 0.
t_result() { printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "result" { print $2 }'; }

## @description  Verifies verdict lattice merging and contamination rules (R-069, R-070, R-071, and
##               the merge behavior of R-078).
## @return       Records test assertions. Exit 0.
t_group_a5_report() {
  local dir="$T_A5/verdicts" a b want bad="" copy run base=$T_A5_BASE
  printf '# A5: the verdict merge\n'
  # Verify requirement R-069: every pair of severities in the lattice merges to the higher-ranked
  # severity, while a stream consisting solely of SKIP rows resolves to INCONCLUSIVE.
  for a in PASS SKIP ADVISORY FAIL ERROR; do
    for b in PASS SKIP ADVISORY FAIL ERROR; do
      printf 'one\t%s\tnone\taction\tleft\n' "$a" >"$dir/$a-$b.tsv"
      printf 'two\t%s\tcount\tdriver:x\tright\n' "$b" >"$dir/$a-$b.d.tsv"
      want=$a
      [ "$(t_rank "$b")" -le "$(t_rank "$a")" ] || want=$b
      [ "$a$b" != SKIPSKIP ] || want=INCONCLUSIVE
      t_report measurement "" --verdict "$dir/$a-$b.tsv" --verdict "$dir/$a-$b.d.tsv"
      if [ "$(t_result)" != "$want" ] ||
        [ "$T_STATUS" != "$(t_code_of "$want")" ]; then
        bad="$bad $a+$b gave $(t_result) $T_STATUS, not $want;"
      fi
    done
  done
  t_empty report_merges_every_pair_to_the_documented_maximum "$bad"
  # R-170: Verify that the harness and every verdict writer share a single severity vocabulary.
  t_equals severity_vocabulary_is_the_shared_set "PASS SKIP ADVISORY FAIL ERROR" \
    "$(sed -n 's/^SX_SEVERITIES="\(.*\)"$/\1/p' "$PX_HOME/suites/lib/common.sh")"
  t_contains report_doc_orders_the_lattice 'PASS  <  SKIP  <  ADVISORY  <  FAIL  <  ERROR' \
    "$(cat "$PX_HOME/docs/bundle-format.md")"
  printf 'one\tPASS\tnone\taction\tok\nodd\tWARNING\tnone\taction\tx\n' >"$dir/unknown.tsv"
  t_report measurement "" --verdict "$dir/unknown.tsv"
  t_equals report_unknown_severity_is_an_error "3 ERROR" "$T_STATUS $(t_result)"
  printf 'odd\tFAIL\ttime\taction\tx\n' >"$dir/class.tsv"
  t_report measurement "" --verdict "$dir/class.tsv"
  t_equals report_unknown_class_is_an_error "3 ERROR" "$T_STATUS $(t_result)"
  t_report measurement "" --verdict "$dir/none.tsv"
  t_equals report_absent_stream_is_6 6 "$T_STATUS"
  # Verify requirement R-071: in a contaminated run, a time FAIL verdict is downgraded to
  # ADVISORY, whereas a count FAIL verdict still fails the run. A time PASS verdict remains PASS
  # because the rule demotes only FAIL verdicts.
  printf 'latency\tFAIL\tduration\tdriver:x\tslow\nrows\tFAIL\tcount\tdriver:x\tshort\n' \
    >"$dir/mixed.tsv"
  printf 'pause\tPASS\tduration\tcheck:x\tshort\n' >>"$dir/mixed.tsv"
  t_report measurement quiet-host --verdict "$dir/mixed.tsv"
  t_equals report_contaminated_run_fails_on_the_count "1 FAIL" "$T_STATUS $(t_result)"
  t_contains report_contaminated_time_verdict_is_advisory \
    "latency${PX_TAB}ADVISORY${PX_TAB}duration" "$T_STDOUT"
  t_contains report_count_verdict_stays_authoritative "rows${PX_TAB}FAIL${PX_TAB}count" \
    "$T_STDOUT"
  t_report measurement "" --verdict "$dir/mixed.tsv"
  t_contains report_quiet_measurement_keeps_the_time_fail \
    "latency${PX_TAB}FAIL${PX_TAB}duration" "$T_STDOUT"
  t_report validation "" --verdict "$dir/mixed.tsv"
  t_contains report_validation_time_verdict_is_advisory \
    "latency${PX_TAB}ADVISORY${PX_TAB}duration" "$T_STDOUT"
  t_contains report_validation_time_pass_stays_pass \
    "pause${PX_TAB}PASS${PX_TAB}duration${PX_TAB}check:x${PX_TAB}short${PX_TAB}PASS" "$T_STDOUT"
  # Confirm that evaluating a run bundle applies the same downgrade rule using the run class and
  # contamination recorded in the bundle itself rather than ambient environment variables.
  copy="$(t_bundle_copy "$base" contaminated)"
  cat "$dir/mixed.tsv" >>"$copy/verdicts.tsv"
  t_report measurement "" "$copy"
  t_equals report_bundle_reads_its_own_class "1 FAIL" "$T_STATUS $(t_result)"
  t_contains report_bundle_writes_the_effective_rows \
    "latency${PX_TAB}ADVISORY${PX_TAB}duration${PX_TAB}driver:x${PX_TAB}slow${PX_TAB}FAIL" \
    "$(cat "$copy/report/verdicts.tsv" 2>/dev/null)"
  t_contains report_bundle_writes_the_run_summary "${PX_TAB}FAIL${PX_TAB}" \
    "$(sed -n 2p "$copy/report/run-summary.tsv" 2>/dev/null)"
  t_equals report_bundle_writes_the_cluster_summary_header \
    "role index image_id state_pre state_post restarts_pre restarts_post change oom" \
    "$(head -n 1 "$copy/report/cluster-summary.tsv" 2>/dev/null | tr '\t' ' ')"
  t_a5_resolver_cases
}

## @description  Executes px-report or px-bundle (output root, command, argument...) with PX_OUT
##               configured to the specified output root.
## @return       Populates T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a5_at() {
  local root=$1 name=$2
  shift 2
  t_run env "PX_OUT=$root" "$T_BASH" "$T_BIN/$name" "$@"
}

## @description  Duplicates a run bundle (source, destination) under a new run id and updates the
##               run id recorded in its manifest.
## @return       Copies the bundle directory and rewrites manifest.json. Exit 0.
t_a5_rename() {
  cp -R "$1" "$2"
  sed "s/\"id\": \"${1##*/}\"/\"id\": \"${2##*/}\"/" "$1/manifest.json" >"$2/manifest.json"
}

## @description  Verifies the shared bundle resolver of px-report and px-bundle for run ids, paths,
##               newest finished bundle selection, PX_OUT confinement, and closing result rows.
## @return       Records test assertions. Exit 0.
t_a5_resolver_cases() {
  local base=$T_A5_BASE root="$T_A5/newest" empty="$T_A5/empty-out" old new got outside
  rm -rf "$root" "$empty" "$T_A5/outside"
  mkdir -p "$root/runs" "$root/copies" "$root/collect" "$empty/runs" "$T_A5/outside"
  old="$root/runs/20991231T000000Z-0old"
  new="$root/runs/20991231T000001Z-0new"
  outside="$T_A5/outside/20991231T000000Z-0out"
  t_a5_rename "$base" "$old"
  t_a5_rename "$base" "$new"
  cp -R "$base" "$outside"
  cp -R "$base" "$root/copies/20991231T000002Z-copy"
  cp -R "$base" "$root/collect/20991231T000003Z-coll"
  rm -rf "$new/manifest.json" "$new/report" "$old/report" "$outside/report"
  t_a5_at "$root" px-report -n
  t_equals report_dry_run_resolves_and_plans "0 yes no" "$T_STATUS $(t_flag \
    "plan${PX_TAB}write${PX_TAB}$old/report" "$T_STDOUT") $(t_exists "$old/report")"
  t_a5_at "$root" px-report
  t_equals report_defaults_to_the_newest_finished_bundle "0 $old yes no" "$T_STATUS $(t_last_line \
    "$T_STDOUT" | cut -f 4) $(t_exists "$old/report/run-summary.tsv") $(t_exists "$new/report")"
  t_equals report_result_row_names_the_bundle "$(t_rows 3 result 0 "$old") 1" \
    "$(t_last_line "$T_STDOUT" | cut -f 1,3,4) $(printf '%s\n' "$T_STDOUT" |
      grep -c "^result$PX_TAB")"
  got=$T_STDOUT
  t_a5_at "$root" px-report 20991231T000000Z-0old
  t_equals report_takes_a_run_id "0 yes" "$T_STATUS $([ "$got" = "$T_STDOUT" ] && echo yes ||
    echo no)"
  t_a5_at "$empty" px-report
  t_equals report_without_a_finished_bundle_is_6 "6 yes" "$T_STATUS $(t_flag \
    'no finished bundle under' "$T_STDERR")"
  t_run "$T_BASH" "$T_BIN/px-report" "$outside"
  t_equals report_refuses_a_bundle_outside_px_out "2 yes no" "$T_STATUS $(t_flag \
    'lies outside PX_OUT' "$T_STDERR") $(t_exists "$outside/report")"
  t_run "$T_BASH" "$T_BIN/px-report" "$PX_OUT/runs/20991231T000009Z-miss"
  t_equals report_refuses_a_missing_bundle "6 yes no" "$T_STATUS $(t_flag 'no such bundle' \
    "$T_STDERR") $(t_exists "$PX_OUT/runs/20991231T000009Z-miss")"
  printf 'rows\tPASS\tcount\tdriver:x\tfine\n' >"$T_A5/resolver-pass.tsv"
  t_report validation "" --verdict "$T_A5/resolver-pass.tsv"
  t_equals report_verdict_only_result_row_is_dash "0 $(t_rows 4 result PASS 0 -)" \
    "$T_STATUS $(t_last_line "$T_STDOUT")"
  t_a5_at "$root" px-report "$old" "$new"
  t_equals report_takes_one_bundle "2 yes" "$T_STATUS $(t_flag "unexpected argument $new" \
    "$T_STDERR")"
  t_a5_at "$root" px-bundle validate
  t_equals validate_defaults_to_the_newest_finished_bundle "0 $(t_rows 4 result ok 0 "$old")" \
    "$T_STATUS $(t_last_line "$T_STDOUT")"
  t_equals copies_are_never_the_newest "no no" "$(t_flag 20991231T000002Z-copy "$T_STDOUT") \
$(t_flag 20991231T000003Z-coll "$T_STDOUT")"
  t_equals validate_names_an_absent_path_once "yes no" "$(t_flag \
    "${PX_TAB}skip${PX_TAB}breach.tsv is absent$PX_NL" "$T_STDOUT") $(t_flag 'absent: absent' \
    "$T_STDOUT")"
  t_a5_at "$root" px-bundle validate 20991231T000001Z-0new
  t_equals validate_ends_with_a_result_row "1 $(t_rows 4 result fail 1 "$new") 1" \
    "$T_STATUS $(t_last_line "$T_STDOUT") $(printf '%s\n' "$T_STDOUT" | grep -c "^result$PX_TAB")"
  t_run_in "$root" env "PX_OUT=$root" "$T_BASH" "$T_BIN/px-bundle" validate \
    runs/20991231T000000Z-0old
  t_equals validate_takes_a_relative_path "0 $(t_rows 4 result ok 0 "$old")" \
    "$T_STATUS $(t_last_line "$T_STDOUT")"
  t_a5_at "$root" px-bundle validate 20991231T000000Z-0old
  t_equals validate_takes_a_run_id "0 $(t_rows 4 result ok 0 "$old")" \
    "$T_STATUS $(t_last_line "$T_STDOUT")"
  t_a5_at "$empty" px-bundle validate
  t_equals validate_without_a_finished_bundle_is_6 6 "$T_STATUS"
  t_run "$T_BASH" "$T_BIN/px-bundle" validate "$outside"
  t_equals validate_refuses_a_bundle_outside_px_out "2 yes" "$T_STATUS $(t_flag \
    'lies outside PX_OUT' "$T_STDERR")"
  t_a5_at "$root" px-bundle write
  t_equals write_needs_a_bundle "2 yes" "$T_STATUS $(t_flag 'write needs BUNDLE' "$T_STDERR")"
  t_a5_at "$root" px-bundle validate -n
  t_equals validate_dry_run_plans_the_check "0 $(t_rows 3 plan validate "$old")" \
    "$T_STATUS $T_STDOUT"
  t_a5_at "$root" px-report 20991231T0000
  t_equals a_mistyped_run_id_is_no_bundle "6 yes" "$T_STATUS $(t_flag \
    '20991231T0000 is no bundle: neither a run id nor a path' "$T_STDERR")"
  # When two bundles share a one-second timestamp, the bundle with the newer manifest is selected
  # as the newest bundle regardless of its suffix.
  t_a5_rename "$base" "$root/runs/20991231T000000Z-aaaa"
  touch -t 209912310000 "$old/manifest.json"
  t_a5_at "$root" px-report -n
  t_equals newest_breaks_a_tie_by_the_manifest "0 yes" "$T_STATUS $(t_flag \
    "plan${PX_TAB}write${PX_TAB}$old/report" "$T_STDOUT")"
  rm -rf "$root/runs/20991231T000000Z-aaaa"
  t_a5_metrics_cases
}

## @description  Verifies px-bundle metrics (D4), covering anchor, key, value, and unit rows, shell
##               globs, the operand rule, and exit codes for unmatched queries or invalid operands.
## @return       Records test assertions. Exit 0.
t_a5_metrics_cases() {
  local root="$T_A5/metrics-out" dir got
  dir="$root/runs/20991231T000000Z-0met"
  rm -rf "$root"
  mkdir -p "$dir/metrics"
  cp "$T_A5_BASE/manifest.json" "$dir/"
  {
    printf 'start_ms\tend_ms\tsource\tanchor\tkey\tclass\tvalue\tunit\trun_class\tcontamination\n'
    t_rows 10 1 2 driver:pherf op@query_a pherf.latency.p50_us duration 120 us validation none \
      1 2 driver:pherf op@query_a pherf.latency.p99_us duration 900 us validation none \
      1 2 driver:pherf op@query_a pherf.rows count 10 rows validation none \
      1 2 driver:pherf op@query_b pherf.latency.p99_us duration 950 us validation none \
      1 2 driver:pherf steady@pherf query_errors count 0 errors validation none
  } >"$dir/metrics/aggregate.tsv"
  t_a5_at "$root" px-bundle metrics "$dir" op@query_a pherf.rows
  t_equals metrics_prints_anchor_key_value_unit "0 $(t_rows 4 op@query_a pherf.rows 10 rows)" \
    "$T_STATUS $T_STDOUT"
  t_a5_at "$root" px-bundle metrics "$dir" 'op@*' 'pherf.latency.*_us'
  t_equals metrics_globs_anchor_and_key "0 $(t_rows 4 op@query_a pherf.latency.p50_us 120 us \
    op@query_a pherf.latency.p99_us 900 us op@query_b pherf.latency.p99_us 950 us)" \
    "$T_STATUS $T_STDOUT"
  t_a5_at "$root" px-bundle metrics 20991231T000000Z-0met steady@pherf
  got="$T_STATUS $T_STDOUT"
  t_a5_at "$root" px-bundle metrics op@query_b
  t_equals metrics_first_operand_bundle_or_anchor "0 $(t_rows 4 steady@pherf query_errors 0 \
    errors) 0 $(t_rows 4 op@query_b pherf.latency.p99_us 950 us)" "$got $T_STATUS $T_STDOUT"
  t_a5_at "$root" px-bundle metrics
  t_equals metrics_default_to_every_row_of_the_newest "0 5" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c .)"
  t_a5_at "$root" px-bundle metrics "$dir" 'op@*' nosuch
  t_equals metrics_without_match_is_6 "6 yes" "$T_STATUS $(t_flag 'no metric of' "$T_STDERR")"
  t_a5_at "$root" px-bundle metrics op@query_a pherf.rows extra
  t_equals metrics_three_operands_need_a_bundle "6 yes" "$T_STATUS $(t_flag \
    'op@query_a is no bundle' "$T_STDERR")"
  t_equals config_and_metrics_share_the_glob 1 "$(cat "$PX_HOME"/bin/* "$PX_HOME"/lib/*.sh |
    grep -c 'function glob(')"
  # Verify that glob patterns support shell bracket expressions while treating all other characters
  # as literals.
  t_a5_at "$root" px-bundle metrics "$dir" 'op@query_[ab]' '*.p99_us'
  got="$T_STATUS $(printf '%s\n' "$T_STDOUT" | cut -f 1 | tr '\n' ' ')"
  t_a5_at "$root" px-bundle metrics "$dir" 'op@query_[!a]' '*.p99_us'
  got="$got| $T_STATUS $(printf '%s\n' "$T_STDOUT" | cut -f 1 | tr '\n' ' ')"
  t_a5_at "$root" px-bundle metrics "$dir" 'op@query.a'
  t_equals metrics_glob_takes_bracket_expressions \
    "0 op@query_a op@query_b | 0 op@query_b | 6" "$got| $T_STATUS"
  # Verify that a subsequent record for the same source, anchor, and key supersedes an earlier
  # record.
  t_rows 10 3 4 driver:pherf op@query_a pherf.rows count 12 rows validation none \
    >>"$dir/metrics/aggregate.tsv"
  t_a5_at "$root" px-bundle metrics "$dir" op@query_a pherf.rows
  t_equals metrics_print_the_effective_record "0 $(t_rows 4 op@query_a pherf.rows 12 rows)" \
    "$T_STATUS $T_STDOUT"
  t_a5_at "$root" px-bundle metrics "$dir" 'op@*' 'pherf.*'
  t_equals metrics_keep_the_order_of_first_records "0 p50_us p99_us rows p99_us" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | cut -f 2 | sed 's/.*\.//' | tr '\n' ' ' |
      sed 's/ $//')"
  head -n 1 "$dir/metrics/aggregate.tsv" >"$dir/metrics/aggregate.tsv.tmp"
  mv "$dir/metrics/aggregate.tsv.tmp" "$dir/metrics/aggregate.tsv"
  t_a5_at "$root" px-bundle metrics "$dir"
  t_equals metrics_of_an_empty_table_say_so "6 yes" "$T_STATUS $(t_flag \
    "$dir holds no aggregate metrics" "$T_STDERR")"
  t_a5_at "$root" px-bundle metrics "$dir" -n
  t_equals metrics_dry_run_plans_the_read "0 $(t_rows 3 plan read \
    "$dir/metrics/aggregate.tsv")" "$T_STATUS $T_STDOUT"
}
## @description  Creates a fixture bundle for cluster checks (name, extra record rows...) modeling a
##               fake cluster run with one clean verb, diff, sample, and recovered disruption.
## @return       Writes the bundle directory path to stdout. Exit 0.
t_check_bundle() {
  local dir="$PX_OUT/checks/$1" now
  shift
  now="$(px_epoch_ms)"
  rm -rf "$dir"
  mkdir -p "$dir/cluster"
  cp "$T_WORK/deploy-selftest/topology.env" "$T_WORK/deploy-selftest/services.tsv" \
    "$dir/cluster/"
  {
    t_rows 6 run checks validation compose px-selftest driver
    t_rows 3 stage setup "$((now - 60000))"
    t_rows 5 verb run 0 100 0
    t_rows 8 diff regionserver 0 0 0 planned running running
    t_rows 8 diff client 0 0 0 unchanged running running
    t_rows 12 sample pre "$now" regionserver 0 5.0 1048576 4194304 0 0 0 0
    t_rows 9 disrupt 1 phase phase:phase@kill kill regionserver 0 hard ok
    t_rows 5 recover 1 "$((now - 50000))" "$((now - 9000))" ok
    for row in "$@"; do
      printf '%s\n' "$row"
    done
  } >"$dir/run-record.tsv"
  : >"$dir/verdicts.tsv"
  cp "$PX_HOME/suites/thresholds.properties" "$dir/thresholds.properties"
  printf '%s\n' "$dir"
}

## @description  Executes the built-in cluster checks against a fixture bundle on the fake cluster.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_cluster_checks() {
  t_a5 "PX_RUN_DIR=$1" "PX_THRESHOLDS_FILE=$1/thresholds.properties" PX_SCOPE_ID=px-selftest \
    PX_BACKEND=compose PX_RUN_CLASS=validation "$T_BASH" "$PX_HOME/suites/checks/cluster.sh"
}

## @description  Reports the recorded severity for a check (bundle, check) in verdicts.tsv.
## @return       Writes the severity string to stdout, or empty when no matching row exists. Exit 0.
t_severity_of() { awk -F '\t' -v c="$2" '$1 == c { s = $2 } END { print s }' "$1/verdicts.tsv"; }

## @description  Reports the space-separated severity and detail of a check (bundle, check) from
##               a verdict stream.
## @return       Writes the values to stdout, or empty when no matching row exists. Exit 0.
t_detail_of() {
  awk -F '\t' -v c="$2" '$1 == c { s = $2 " " $5 } END { print s }' "$1/verdicts.tsv"
}

## @description  Verifies the built-in and supplementary cluster checks (R-072, R-073, R-074) across
##               fixture bundles.
## @return       Records test assertions. Exit 0.
t_group_a5_checks() {
  local dir subject want got bad="" all="" base merged line key copy
  local subjects="wrapper restart oom log gc recovery post_recovery"
  printf '# A5: the cluster checks\n'
  t_a5_state_reset
  # Verify that px-run passes its configuration files to check-cluster.sh using the shared --config
  # option spelling.
  dir="$(t_check_bundle config)"
  printf 'PX_THRESHOLD_GC_PAUSE_MS_MAX=777\n' >"$T_A5/check-config.env"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh" --config "$T_A5/check-config.env"
  t_equals check_cluster_takes_config "0 yes" "$T_STATUS $(grep -qx 'gc.pause_ms.max=777' \
    "$dir/thresholds.properties" 2>/dev/null && echo yes || echo no)"
  t_a5 "PX_RUN_DIR=$dir" "$T_BASH" "$PX_HOME/suites/check-cluster.sh" -n
  t_equals check_scripts_dry_run_print_plan_rows "0 3 0" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c "^plan$PX_TAB") $(printf '%s\n' "$T_STDOUT" | grep -vc "^plan$PX_TAB")"
  t_a5 "PX_RUN_DIR=$dir" "$T_BASH" "$PX_HOME/suites/checks/cluster.sh" --help
  t_equals check_scripts_answer_help "0 yes" "$T_STATUS $(t_flag 'Usage: cluster.sh [option...]' \
    "$T_STDOUT")"
  # Verify requirement R-072: a clean fixture bundle passes all seven built-in cluster check
  # subjects.
  dir="$(t_check_bundle clean)"
  t_cluster_checks "$dir"
  t_equals cluster_checks_run 0 "$T_STATUS"
  for subject in $subjects; do
    all="$all $(t_severity_of "$dir" "cluster.$subject")"
  done
  t_equals cluster_checks_clean_fixture_passes_all_seven \
    " PASS PASS PASS PASS PASS PASS PASS" "$all"
  t_equals cluster_checks_keep_the_instance_logs yes \
    "$([ -f "$dir/logs/instance/client-0.log" ] && echo yes || echo no)"
  t_equals cluster_checks_keep_the_gc_logs yes \
    "$([ -f "$dir/logs/gc/regionserver-0.log" ] && echo yes || echo no)"
  t_equals cluster_checks_start_no_process_in_a_daemon 0 \
    "$(grep -c 'exec .*regionserver' "$T_SHIM_LOG")"
  # Confirm that injecting a fault for each individual check subject produces the expected failure
  # verdict for that subject alone.
  for subject in $subjects; do
    t_a5_state_reset
    case "$subject" in
    wrapper) dir="$(t_check_bundle "$subject" "$(t_rows 5 verb run 143 900 1)")" ;;
    restart) dir="$(t_check_bundle "$subject" \
      "$(t_rows 8 diff client 0 0 1 unplanned running running)")" ;;
    oom) dir="$(t_check_bundle "$subject" "$(t_rows 5 oom client 0 yes OOMKilled)")" ;;
    log)
      dir="$(t_check_bundle "$subject")"
      printf '2026-11-20 17:00:00,000 ERROR [main] Server: boom\n' \
        >"$T_A5_STATE/logs-c0ffee00a502.txt"
      ;;
    gc)
      dir="$(t_check_bundle "$subject")"
      printf '%s\n' '[1.0s][info][gc] GC(3) Pause Young (Normal) 51M->12M(256M) 1500.250ms' \
        >"$T_A5_STATE/copy-out.txt"
      ;;
    recovery)
      dir="$(t_check_bundle "$subject" "$(t_rows 5 recover 1 1000 400000 ok)")"
      ;;
    post_recovery)
      dir="$(t_check_bundle "$subject")"
      : >"$T_A5_STATE/probe-fails"
      ;;
    esac
    t_cluster_checks "$dir"
    case "$subject" in
    restart | oom) want=ERROR ;;
    *) want=FAIL ;;
    esac
    got="$(t_severity_of "$dir" "cluster.$subject")"
    [ "$got" = "$want" ] || bad="$bad $subject gave '$got', not $want;"
    # Ensure fault isolation: every check subject other than the faulted one must still report PASS.
    for line in $subjects; do
      [ "$line" != "$subject" ] || continue
      [ "$(t_severity_of "$dir" "cluster.$line")" = PASS ] ||
        bad="$bad the $subject fixture also moved $line;"
    done
  done
  t_empty cluster_checks_fail_each_subject_distinctly "$bad"
  t_a5_state_reset
  dir="$(t_check_bundle peak \
    "$(t_rows 12 sample post "$(px_epoch_ms)" regionserver 0 5.0 4100000 4194304 0 0 0 0)")"
  t_cluster_checks "$dir"
  t_equals cluster_oom_peak_over_the_limit_fails FAIL "$(t_severity_of "$dir" cluster.oom)"
  dir="$(t_check_bundle warnings)"
  awk 'BEGIN { for (i = 0; i < 101; i++) print "2026-11-20 17:00:00 WARN [x] w" }' \
    >"$T_A5_STATE/logs-c0ffee00a502.txt"
  t_cluster_checks "$dir"
  t_equals cluster_log_warnings_are_advisory ADVISORY "$(t_severity_of "$dir" cluster.log)"
  rm -f "$T_A5_STATE/logs-c0ffee00a502.txt"
  # Verify D-2: a log line matching an entry in suites/log-allow.txt is excluded from the error
  # count and the verdict detail names the matched entry. The same message under a different logger,
  # an unrecognized ERROR line, or the allowed line under an HBase version outside the entry's
  # release range are still counted. The fixture topology runs HBase 2.6.7.
  line='2026-11-20 17:00:00,000 ERROR [ProcedureDispatcherTimeoutThread]'
  line="$line procedure2.RemoteProcedureDispatcher: DelayQueue for RemoteProcedureDispatcher"
  line="$line is not empty when timed waiting elapsed. If this is repeated consistently, it"
  line="$line means no element is getting expired from the queue and it might freeze the"
  line="$line system. Queue: []"
  got=""
  for copy in allowed other-logger unknown other-version repeated; do
    dir="$(t_check_bundle "allow-$copy")"
    case "$copy" in
    allowed) printf '%s\n' "$line" ;;
    repeated) printf '%s\n' "$line" "${line/17:00:00/17:00:30}" ;;
    other-logger) printf '%s\n' "${line/procedure2.RemoteProcedureDispatcher:/master.Other:}" ;;
    unknown) printf '%s\n' "$line" '2026-11-20 17:00:01,000 ERROR [main] Server: boom' ;;
    other-version)
      printf '%s\n' "$line"
      sed 's/^PX_DIST_HBASE_VERSION=.*/PX_DIST_HBASE_VERSION=2.6.8/' \
        "$T_WORK/deploy-selftest/topology.env" >"$dir/cluster/topology.env"
      ;;
    esac >"$T_A5_STATE/logs-c0ffee00a502.txt"
    t_cluster_checks "$dir"
    want="$(t_detail_of "$dir" cluster.log)"
    got="$got|$copy ${want%% ERROR or FATAL*}"
    got="$got $(t_flag '1 allowed: hbase-dispatcher-delayqueue' "$want")"
    got="$got $(t_flag 'allow list, counted: hbase-dispatcher-delayqueue' "$want")"
  done
  # Verify that every entry in the log allowlist has a unique id and all required keys, including a
  # recognized log level, a max count of at least 1, and a release range for a known component.
  t_empty log_allow_entries_are_well_formed "$(awk '
    function close_entry() {
      if (id != "" && (seen[id]++ || id !~ /^[a-z0-9][a-z0-9-]*$/ ||
        k["level"] !~ /^(ERROR|FATAL|WARN)$/ || k["logger"] == "" || k["message"] == "" ||
        k["max"] !~ /^[1-9][0-9]*$/ ||
        k["release"] !~ /^(hbase|hadoop) [0-9]+(\.[0-9]+)* [0-9]+(\.[0-9]+)*$/ ||
        k["reason"] == "")) print "entry " id
      split("", k)
    }
    /^#/ || !index($0, "=") { next }
    { key = substr($0, 1, index($0, "=") - 1); val = substr($0, index($0, "=") + 1) }
    key == "id" { close_entry(); id = val; n++; next }
    key ~ /^(level|logger|max|message|release|reason)$/ { k[key] = k[key] val; next }
    { print "unknown key " key }
    END { close_entry(); if (!n) print "no entry" }' "$PX_HOME/suites/log-allow.txt" 2>&1)"
  t_equals cluster_log_allow_list_excuses_only_its_lines "|allowed PASS 0 yes no|other-logger\
 FAIL 1 no no|unknown FAIL 1 yes no|other-version FAIL 1 no no|repeated FAIL 1 yes yes" "$got"
  rm -f "$T_A5_STATE/logs-c0ffee00a502.txt"
  # Verify A7: a hard kill of a RegionServer causes DataNodes to log errors while the cluster
  # recovers. Any log line within the recovery window (now - 50 s to now - 9 s) is excluded under
  # either timestamp layout, whereas lines after recovery or before injection (now - 60 s) are
  # counted. A recover row with no recovery timestamp indicates a failed recovery and defines no
  # window.
  line="$(px_epoch_s)"
  dir="$(t_check_bundle window "$(t_rows 5 recover 2 "$(((line - 70) * 1000))" "" fail)")"
  px_utc_fields "$((line - 30))"
  copy=${PX_UTC_TEXT%Z}
  {
    printf '%s,196 ERROR [DataXceiver] datanode.DataNode: error processing WRITE_BLOCK\n' \
      "${copy/T/ }"
    printf '%s,201 WARN [main] Server: the pipeline broke\n' "$copy"
  } >"$T_A5_STATE/window.txt"
  got=""
  for copy in "" "0 after the recovery" "60 before the injection"; do
    cp "$T_A5_STATE/window.txt" "$T_A5_STATE/logs-c0ffee00a502.txt"
    if [ -n "$copy" ]; then
      px_utc_fields "$((line - ${copy%% *}))"
      printf '%s,500 ERROR [main] Server: %s\n' "${PX_UTC_TEXT%Z}" "${copy#* }" \
        >>"$T_A5_STATE/logs-c0ffee00a502.txt"
    fi
    t_cluster_checks "$dir"
    want="$(t_detail_of "$dir" cluster.log)"
    got="$got|${want%% ERROR or FATAL*} $(t_flag "2 lines in a recovery window" "$want")"
    got="$got${copy:+ $(t_flag "first: ${PX_UTC_TEXT%Z},500 ERROR [main] Server: ${copy#* }" \
      "$want")}"
  done
  t_equals cluster_log_counts_no_line_of_a_recovery_window \
    "|PASS 0 yes|FAIL 1 yes yes|FAIL 1 yes yes" "$got"
  rm -f "$T_A5_STATE/logs-c0ffee00a502.txt" "$T_A5_STATE/window.txt"
  # Verify regression A5-LIVE-2: the JDK pads log line tags to the widest tag set written so far,
  # so a GC pause line following a safepoint line carries a padded [gc       ] tag.
  t_a5_state_reset
  dir="$(t_check_bundle gcpad)"
  {
    printf '%s %s\n' '[0.5s][info][safepoint] Safepoint "G1CollectForAllocation", Time since' \
      'last: 1 ns, Total: 1 ns'
    printf '%s\n' '[1.0s][info][gc       ] GC(3) Pause Young (Normal) 51M->12M(256M) 1750.500ms'
  } >"$T_A5_STATE/copy-out.txt"
  t_cluster_checks "$dir"
  t_equals cluster_gc_reads_a_padded_tag "FAIL yes" "$(t_severity_of "$dir" cluster.gc) $(awk \
    -F '\t' '$1 == "cluster.gc" && index($5, "longest pause is 1750.500 ms") { f = 1 }
    END { print f ? "yes" : "no" }' "$dir/verdicts.tsv")"
  rm -f "$T_A5_STATE/copy-out.txt"
  # Confirm that the post-recovery bootstrap probe includes --yarn when the yarn service group is
  # active.
  dir="$(t_check_bundle yarn)"
  sed 's/^PX_GROUPS=.*/PX_GROUPS=yarn/' "$dir/cluster/topology.env" >"$dir/t.env"
  mv -f "$dir/t.env" "$dir/cluster/topology.env"
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|yarn|on-demand|\n' c0ffee00a503 nodemanager \
    px-selftest-nodemanager-1 running 'Up 1 minute (healthy)' phoenix-harness:selftest \
    nodemanager 1 0 >>"$T_A5_STATE/instances.txt"
  : >"$T_A5_STATE/probe.log"
  t_cluster_checks "$dir"
  t_contains cluster_post_recovery_probes_yarn_when_it_is_up 'probe --yarn' \
    "$(cat "$T_A5_STATE/probe.log" 2>/dev/null)"
  t_a5_state_reset
  dir="$(t_check_bundle nodisruption)"
  grep -v '^disrupt\|^recover' "$dir/run-record.tsv" >"$dir/r.tsv"
  mv -f "$dir/r.tsv" "$dir/run-record.tsv"
  t_cluster_checks "$dir"
  t_equals cluster_subjects_without_evidence_skip "SKIP SKIP" \
    "$(t_severity_of "$dir" cluster.recovery) $(t_severity_of "$dir" cluster.post_recovery)"
  # Verify requirement R-073: all check thresholds reside in thresholds.properties, the recovery
  # time bound defaults to 120 seconds, and no check script embeds a hardcoded threshold literal.
  base="$PX_HOME/suites/thresholds.properties"
  t_equals thresholds_hold_the_recovery_bound 120 \
    "$(sed -n 's/^recovery\.seconds\.max=//p' "$base")"
  bad=""
  # Pass the target files directly to grep rather than piping from an upstream command: under
  # pipefail, a producer piped into grep -q can receive SIGPIPE when grep exits upon finding the
  # first match, causing the overall pipeline to fail.
  while IFS="=" read -r key _; do
    case "$key" in '' | '#'*) continue ;; esac
    grep -qE "(ck|sc)_threshold [a-z_\$\"]+ ${key//./\\.}( |$)" "$PX_HOME/suites/checks"/*.sh ||
      bad="$bad $key is not read through sx_threshold;"
    grep -qE "${key//./\\.}=[0-9]" "$PX_HOME/suites/checks"/*.sh &&
      bad="$bad $key has a literal value;"
  done <"$base"
  grep -qE '(ck_over +[^ ]+ +"?[0-9]|-v m="?[0-9])' "$PX_HOME/suites/checks"/*.sh &&
    bad="$bad a comparison holds a literal;"
  t_empty cluster_checks_hold_no_threshold_literal "$bad"
  # Confirm that a plug-in threshold overlay overrides its target key while leaving all other
  # default thresholds intact.
  t_a5_state_reset
  dir="$(t_check_bundle overlay)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    PX_PLUGINS=example "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals check_cluster_with_the_example_overlay_exits_0 0 "$T_STATUS"
  merged="$(grep -v '^#' "$dir/thresholds.properties" | grep . | sort)"
  t_equals plugin_overlay_changes_one_key "log.warnings.max=200" \
    "$(LC_ALL=C comm -13 <(grep -v '^#' "$base" | grep . | sort) <(printf '%s\n' "$merged"))"
  t_equals plugin_overlay_leaves_the_rest "log.warnings.max=100" \
    "$(LC_ALL=C comm -23 <(grep -v '^#' "$base" | grep . | sort) <(printf '%s\n' "$merged"))"
  # Verify requirements R-074 and R-078: a failing supplementary check contributed by a plug-in at
  # runtime propagates into the merged run verdict, and a supplementary check that cannot execute is
  # recorded as an ERROR row.
  t_a5_state_reset
  dir="$(t_check_bundle extra)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "PX_PLUGIN_DIRS=$T_A5/plugins/extra" "PX_PLUGINS=example extra" \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals check_cluster_reports_a_check_that_could_not_run 1 "$T_STATUS"
  t_equals supplementary_checks_run_in_name_order "plugin.extra.a-fail plugin.extra.b-broken" \
    "$(awk -F '\t' '$1 ~ /^plugin\.extra\./ { printf "%s%s", s, $1; s = " " }' \
      "$dir/verdicts.tsv")"
  t_equals supplementary_check_reads_its_own_threshold "limit 7" \
    "$(awk -F '\t' '$1 == "plugin.extra.a-fail" { print $5 }' "$dir/verdicts.tsv")"
  t_equals broken_check_is_an_error ERROR "$(t_severity_of "$dir" plugin.extra.b-broken)"
  t_contains example_check_writes_a_parseable_record \
    "plugin.example.marker${PX_TAB}PASS${PX_TAB}count${PX_TAB}plugin:example" \
    "$(cat "$dir/verdicts.tsv")"
  t_contains example_check_records_its_metric "${PX_TAB}plugin:example${PX_TAB}" \
    "$(cat "$dir/metrics/aggregate.tsv" 2>/dev/null)"
  t_run env PX_RUN_CLASS=validation "$T_BASH" "$T_BIN/px-report" --verdict "$dir/verdicts.tsv"
  t_equals supplementary_failure_fails_the_merged_run "3 ERROR" "$T_STATUS $(t_result)"
  t_contains merge_includes_the_example_check "plugin.example.marker${PX_TAB}PASS" "$T_STDOUT"
  t_contains merge_includes_the_failing_supplementary_check "plugin.extra.a-fail${PX_TAB}FAIL" \
    "$T_STDOUT"
  # Verify requirement A5-REQ-08: when the broken check is removed, the failing supplementary check
  # alone determines the merged run outcome, yielding FAIL rather than ERROR.
  copy="$T_A5/plugins/failonly"
  rm -rf "$copy"
  cp -R "$T_A5/plugins/extra" "$copy"
  rm -f "$copy/checks/b-broken"
  sed 's/^PX_PLUGIN_ID=extra$/PX_PLUGIN_ID=failonly/' "$T_A5/plugins/extra/plugin.env" \
    >"$copy/plugin.env"
  t_a5_state_reset
  dir="$(t_check_bundle failonly)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "PX_PLUGIN_DIRS=$copy" PX_PLUGINS=failonly "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_run env PX_RUN_CLASS=validation "$T_BASH" "$T_BIN/px-report" --verdict "$dir/verdicts.tsv"
  t_equals failing_supplementary_check_fails_the_merged_run "1 FAIL" "$T_STATUS $(t_result)"
  dir="$(t_check_bundle badline)"
  printf '#!/usr/bin/env bash\nprintf "extra.limit=seven\\n"\n' \
    >"$T_A5/plugins/extra/hooks/thresholds"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "PX_PLUGIN_DIRS=$T_A5/plugins/extra" PX_PLUGINS=extra "$T_BASH" \
    "$PX_HOME/suites/check-cluster.sh"
  t_equals bad_threshold_line_runs_no_check "2 ERROR 0" "$T_STATUS $(t_severity_of "$dir" \
    cluster.thresholds) $(grep -c '^cluster\.wrapper' "$dir/verdicts.tsv")"
  printf '#!/usr/bin/env bash\nprintf "extra.limit=7\\n"\n' \
    >"$T_A5/plugins/extra/hooks/thresholds"
  t_run env -u PX_RUN_DIR "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals check_cluster_needs_a_run_dir 2 "$T_STATUS"
  t_a5_state_reset
}
## @description  Reports the line count of every marker file under a directory (dir) as sorted
##               "hook=count" tokens.
## @return       Writes the space-separated tokens to stdout. Exit 0.
t_markers() {
  find "$1" -name '*.marker' 2>/dev/null | while IFS= read -r t_file; do
    t_file_name=${t_file##*/}
    printf '%s=%s\n' "${t_file_name%.marker}" "$(grep -c . "$t_file")"
  done | LC_ALL=C sort | tr '\n' ' '
}

## @description  Computes sorted cksum records for every file under the core harness code
##               directories.
## @return       Writes the sorted checksum lines to stdout. Exit 0.
t_core_sums() {
  find "$PX_HOME/bin" "$PX_HOME/lib" "$PX_HOME/suites" -type f -exec cksum {} + | LC_ALL=C sort
}

## @description  Verifies the plug-in contract (R-075 to R-077, R-280) and shipped suites (R-079),
##               covering discovery, manifest refusal, all nine hooks in a full run, and dry runs.
## @return       Records test assertions. Exit 0.
t_group_a5_plugins() {
  local deploy session run copy core bad="" kind dir all line
  printf '# A5: the plug-in contract and the suites\n'
  # Verify requirement R-076: plug-in discovery scans only the default directory when
  # PX_PLUGIN_DIRS is unset, and includes external directories when specified by PX_PLUGIN_DIRS.
  t_run env -u PX_PLUGIN_DIRS "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
  t_equals plugins_discovers_only_the_default_directory \
    "example$PX_TAB$PX_HOME/plugins/example" "$T_STDOUT"
  t_run env "PX_PLUGIN_DIRS=$T_A5/plugins/extra" "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
  t_contains plugins_discovers_a_directory_outside_the_tree \
    "extra$PX_TAB$T_A5/plugins/extra" "$T_STDOUT"
  t_run env "PX_PLUGIN_DIRS=$T_A5/plugins/extra" PX_PLUGINS=extra "$T_BASH" \
    "$T_WORK/probe/a5.sh" pl_enabled
  t_equals plugins_enable_only_what_px_plugins_names "extra$PX_TAB$T_A5/plugins/extra" \
    "$T_STDOUT"
  t_run env PX_PLUGINS=nosuch "$T_BASH" "$T_WORK/probe/a5.sh" pl_enabled
  t_equals plugins_refuse_an_unknown_id 2 "$T_STATUS"
  t_run env "PX_PLUGIN_DIRS=$T_A5/no-such-dir" "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
  t_equals plugins_refuse_an_entry_without_a_manifest 2 "$T_STATUS"
  # Verify requirement R-075: manifests missing a required key, targeting another contract version,
  # containing an unrecognized key, or colliding with a built-in driver name are each rejected with
  # a distinct diagnostic message.
  for kind in noid nocontract contract2 badkey psql; do
    t_run env "PX_PLUGIN_DIRS=$T_A5/refused/$kind" "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
    case "$kind" in
    noid) all='plugin.env lacks PX_PLUGIN_ID' ;;
    nocontract) all='plugin.env lacks PX_PLUGIN_CONTRACT' ;;
    contract2) all='plug-in contract2 implements contract 2, this harness implements 1' ;;
    badkey) all='PX_RS_HEAP is not a plug-in key' ;;
    psql) all='has the name of a built-in driver' ;;
    esac
    if [ "$T_STATUS" != 2 ] || ! printf '%s\n' "$T_STDERR" | grep -qF "$all"; then
      bad="$bad $kind gave $T_STATUS: $T_STDERR;"
    fi
  done
  t_empty plugin_manifests_are_refused_with_distinct_messages "$bad"
  t_run env "PX_PLUGIN_DIRS=$PX_HOME/plugins/example" "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
  t_equals plugins_refuse_a_duplicate_id 2 "$T_STATUS"
  t_empty plugin_doc_names_every_manifest_key "$(for kind in PX_PLUGIN_ID PX_PLUGIN_CONTRACT \
    PX_PLUGIN_DRIVER PX_PLUGIN_HOOKS 'PX_<KEY>_HEAP_MIB' 'PX_<KEY>_JVM_OPTS'; do
      grep -qF "| \`$kind\` |" "$PX_HOME/docs/PLUGINS.md" || echo "$kind"; done)"
  # Confirm that plug-in heap sizing and JVM option settings participate as a configuration loader
  # stage with origin plugin:<id>, while operator overrides continue to take precedence.
  t_run env PX_PLUGINS=example "$T_BASH" "$T_BIN/px-cluster" --class validation config
  t_contains plugin_sizing_is_a_loader_stage \
    "PX_CLIENT_JVM_OPTS${PX_TAB}-Dpx.example=1${PX_TAB}plugin:example" "$T_STDOUT"
  t_run env PX_PLUGINS=example PX_SETUP_HEAP_MIB=300 "$T_BASH" "$T_BIN/px-cluster" \
    --class validation config
  t_contains operator_value_wins_over_a_plugin "PX_SETUP_HEAP_MIB${PX_TAB}300${PX_TAB}environment" \
    "$T_STDOUT"
  # Verify the deployment render hooks: the configuration overlay is merged and bind-mounted, and
  # both role-level and hook-emitted JVM options are propagated to the client service.
  t_gen --class validation --cluster plug -e PX_PLUGINS=example
  deploy="$T_A2_OUT/deploy/plug"
  t_equals render_with_the_example_plugin 0 "$T_STATUS"
  t_equals render_merges_the_overlay_template yes \
    "$([ -f "$deploy/conf-overlay/hbase-site.xml.tmpl" ] && echo yes || echo no)"
  t_contains render_mounts_the_overlay "$deploy/conf-overlay:/opt/px/conf-overlay:ro" \
    "$(cat "$deploy/compose.yaml" 2>/dev/null)"
  t_contains render_names_the_overlay 'PX_CONF_OVERLAY_DIR: "/opt/px/conf-overlay"' \
    "$(cat "$deploy/compose.yaml" 2>/dev/null)"
  t_contains render_passes_the_client_jvm_options \
    'PX_JVM_OPTS_EXTRA: "-Dpx.example=1 -Dpx.example.hook=jvm-opts"' \
    "$(sed -n '/^  client-0:/,/^  [a-z]/p' "$deploy/compose.yaml" 2>/dev/null)"
  t_equals render_hooks_fire_once "conf-overlay=1 image-check=1 jvm-opts=1 " \
    "$(t_markers "$deploy")"
  # Verify requirement RQ-123: a failing image-check hook aborts rendering before any deployment
  # directory is written.
  copy="$T_A5/plugins/noimage"
  rm -rf "$copy"
  mkdir -p "$copy/hooks"
  printf '%s\n' PX_PLUGIN_ID=noimage PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=image-check \
    >"$copy/plugin.env"
  printf '#!/usr/bin/env bash\nexit 1\n' >"$copy/hooks/image-check"
  chmod +x "$copy/hooks/image-check"
  t_gen --class validation --cluster noimage -e "PX_PLUGIN_DIRS=$copy" -e PX_PLUGINS=noimage
  t_equals render_stops_at_a_failed_image_check "2 yes no" "$T_STATUS $(t_flag \
    'image-check hook of a plug-in refused the image' "$T_STDERR") $(t_exists \
    "$T_A2_OUT/deploy/noimage")"
  t_gen --class validation --cluster plug2 -e PX_PLUGINS=example -e PX_CLIENT_JVM_OPTS=-Xmx1g
  t_equals render_refuses_a_size_option_in_jvm_options 2 "$T_STATUS"
  # Verify requirement R-077: executing the example suite through px-suite fires each of the nine
  # plug-in hooks exactly once. The run bundle captures the run-time hook markers and copies the
  # deployment render hook markers, while the session directory retains the suites hook marker.
  t_a5_state_reset
  printf '%s\n' "$deploy" >"$PX_OUT/state/deployment-compose-selftest"
  session="$PX_OUT/sessions/a5-example"
  t_a5 PX_PLUGINS=example PX_SESSION_ID=a5-example PX_SCOPE_ID=px-selftest \
    "$T_BASH" "$T_BIN/px-suite" example
  t_equals example_suite_passes 0 "$T_STATUS"
  run="$(sed -n 1p "$session/tests/rows/stdout.txt" 2>/dev/null)"
  all="$(t_markers "$run") $(t_markers "$session/plugins")"
  t_equals every_hook_fires_exactly_once "collect=1 conf-overlay=1 fetch-data=1 image-check=1\
 jvm-opts=1 post-workload=1 pre-workload=1 thresholds=1  suites=1 " "$all"
  t_equals every_run_hook_marker_is_in_the_bundle yes "$(for kind in fetch-data pre-workload \
    post-workload thresholds collect; do [ -f "$run/plugins/example/$kind.marker" ] || echo no
    done; for kind in conf-overlay jvm-opts image-check; do
      [ -f "$run/cluster/plugins/example/$kind.marker" ] || echo no; done | grep -c no >/dev/null &&
      echo no || echo yes)"
  t_contains example_driver_counts_the_staged_rows "plugin.example.rows${PX_TAB}PASS" \
    "$(cat "$run/report/verdicts.tsv" 2>/dev/null)"
  t_contains collect_hook_lists_its_files "path${PX_TAB}description" \
    "$(cat "$run/plugins/example/manifest.tsv" 2>/dev/null)"
  t_contains hook_calls_are_recorded "${PX_TAB}fetch-data${PX_TAB}0${PX_TAB}" \
    "$(cat "$run/plugins/example/hooks.tsv" 2>/dev/null)"
  # Verify requirement A5-REQ-10: run-time hooks are recorded in execution order with one check row
  # per supplementary check script, and all deployment render hooks precede the run-time hooks.
  t_equals run_hooks_are_recorded_in_run_order \
    "fetch-data pre-workload post-workload thresholds check collect" \
    "$(cut -f 2 "$run/plugins/example/hooks.tsv" 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
  t_equals render_hooks_precede_the_run_hooks "image-check jvm-opts conf-overlay yes" \
    "$(cut -f 2 "$run/cluster/plugins/example/hooks.tsv" 2>/dev/null | tr '\n' ' ')$(awk \
      -F '\t' 'FNR == NR { if ($1 > last) last = $1; next } FNR == 1 { first = $1 }
      END { print (last != "" && first != "" && last < first) ? "yes" : "no" }' \
      "$run/cluster/plugins/example/hooks.tsv" "$run/plugins/example/hooks.tsv" 2>/dev/null)"
  # Verify requirement RQ-124: the pre-workload hook executes before the driver's run verb begins,
  # and the post-workload hook executes after the run verb finishes.
  t_equals workload_hooks_bracket_the_run_verb "yes yes" "$(awk -F '\t' \
    'FNR == NR { if ($3 == "verb@run") { if ($2 == "begin") b = $1; else e = $1 }; next }
    $2 == "pre-workload" { p = $1 } $2 == "post-workload" { q = $1 }
    END { print (p != "" && p <= b) ? "yes" : "no", (q != "" && q >= e) ? "yes" : "no" }' \
    "$run/stages.tsv" "$run/plugins/example/hooks.tsv" 2>/dev/null)"
  t_equals plugin_bundle_validates 0 "$(t_validate "$run"; printf '%s' "$T_STATUS")"
  # Confirm that a failure in a fail-closed hook such as pre-workload prevents the run verb from
  # executing while still running the restore and report verbs.
  t_a5_state_reset
  copy="$T_A5/plugins/failing"
  rm -rf "$copy"
  mkdir -p "$copy/hooks"
  printf '%s\n' PX_PLUGIN_ID=failing PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=pre-workload \
    >"$copy/plugin.env"
  printf '#!/usr/bin/env bash\nexit 1\n' >"$copy/hooks/pre-workload"
  chmod +x "$copy/hooks/pre-workload"
  t_a5 "PX_PLUGIN_DIRS=$copy" PX_PLUGINS=failing "$T_BASH" "$T_BIN/px-run" --class validation \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/exit-code.sh" -- 0
  run="$(t_bundle)"
  t_equals failed_pre_workload_blocks_the_run_verb "1 FAIL no yes" \
    "$T_STATUS $(t_severity_of "$run" plugin.failing.pre-workload) $(grep -q \
      "^verb${PX_TAB}run$PX_TAB" "$run/run-record.tsv" && echo yes || echo no) $(grep -q \
      "^verb${PX_TAB}restore$PX_TAB" "$run/run-record.tsv" && echo yes || echo no)"
  # Verify requirement R-280: the example plug-in exercises all six extension mechanisms, and an
  # external copy of the plug-in executes its driver without modifying any core harness file.
  bad=""
  for kind in 'PX_PLUGIN_DRIVER=driver.sh' 'suites' 'conf-overlay' 'jvm-opts' 'fetch-data' \
    'collect' 'PX_CLIENT_JVM_OPTS='; do
    grep -q -- "$kind" "$PX_HOME/plugins/example/plugin.env" || bad="$bad $kind"
  done
  for kind in suites/example.suite data/example.tsv conf-overlay/hbase-site.xml.tmpl \
    driver.sh hooks/collect; do
    [ -f "$PX_HOME/plugins/example/$kind" ] || bad="$bad $kind"
  done
  t_empty example_plugin_contributes_six_kinds_of_extension "$bad"
  core="$(t_core_sums)"
  copy="$T_A5/plugins/sample"
  rm -rf "$copy"
  cp -R "$PX_HOME/plugins/example" "$copy"
  sed 's/^PX_PLUGIN_ID=example$/PX_PLUGIN_ID=sample/' "$PX_HOME/plugins/example/plugin.env" \
    >"$copy/plugin.env"
  t_a5_state_reset
  t_a5 "PX_PLUGIN_DIRS=$copy" PX_PLUGINS=sample "$T_BASH" "$T_BIN/px-run" --class validation \
    -e PX_SCOPE_ID=px-selftest sample
  t_equals plugin_outside_the_tree_runs_its_driver 0 "$T_STATUS"
  t_equals plugin_only_change_touches_no_core_file "$core" "$(t_core_sums)"
  # Verify requirement R-079: all four shipped suite files exist, and a dry run of each built-in
  # suite resolves its test steps and target drivers.
  for kind in smoke.suite functional.suite check-cluster.sh lib/common.sh; do
    [ -f "$PX_HOME/suites/$kind" ] || bad="$bad $kind"
  done
  t_empty suites_ship_the_four_files "$bad"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run smoke
  t_equals smoke_suite_dry_run_resolves "0 2 psql java-main" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c .) $(printf '%s\n' "$T_STDOUT" | cut -f 5 | tr '\n' ' ' | sed 's/ $//')"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run functional
  t_equals functional_suite_dry_run_resolves "0 4 psql java-main psql pherf" "$T_STATUS $(printf \
    '%s\n' "$T_STDOUT" | grep -c .) $(printf '%s\n' "$T_STDOUT" | cut -f 5 | tr '\n' ' ' |
    sed 's/ $//')"
  # Verify that the functional suite exercises all three built-in drivers: its java-main test
  # executes after the schema test and before the disruption test, and the java-main tests in both
  # suites read a user table created by their own prepare step.
  t_equals functional_suite_runs_the_java_main_driver "schema client:java-main recover pherf" \
    "$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '{ printf "%s%s%s", s, $2, \
      ($5 == "java-main") ? ":java-main" : ""; s = " " }')"
  # Collect the arguments configured for the client test in each suite by joining its args lines in
  # declaration order.
  line="$(for kind in smoke functional; do
    awk '/^test: / { t = $2 } t == "client" && /^  args: / { sub(/^  args: /, ""); printf "%s%s",
      s, $0; s = " " } END { print "" }' "$PX_HOME/suites/$kind.suite"
  done)"
  t_equals smoke_client_extracts_a_user_table "--prepare sql/smoke-schema.sql\
 org.apache.phoenix.schema.tool.SchemaTool -m EXTRACT -tb ITEMS -s PX_SMOKE$PX_NL--prepare\
 sql/functional-schema.sql org.apache.phoenix.schema.tool.SchemaTool -m EXTRACT -tb EVENTS -s\
 PX_FUNCTIONAL" "$line"
  dir="$T_A5/suites"
  mkdir -p "$dir"
  printf 'suite: broken\nclass: validation\n\ntest: one\n  driver: %s\n' \
    "$T_WORK/drivers/no-describe.sh" >"$dir/broken.suite"
  printf '#!/usr/bin/env bash\nprintf "not json\\n"\n' >"$T_WORK/drivers/no-describe.sh"
  chmod +x "$T_WORK/drivers/no-describe.sh"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run "$dir/broken.suite"
  t_equals suite_dry_run_refuses_a_bad_describe 2 "$T_STATUS"
  printf 'suite: absent\nclass: validation\n\ntest: one\n  driver: nosuchdriver\n' \
    >"$dir/absent.suite"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run "$dir/absent.suite"
  t_equals suite_dry_run_refuses_an_unknown_driver 2 "$T_STATUS"
  t_run env PX_PLUGINS=example "$T_BASH" "$T_BIN/px-suite" --dry-run example
  t_equals plugin_suite_resolves_by_name 0 "$T_STATUS"
  t_contains plugin_suite_plans_the_plugin_driver "driver${PX_TAB}example" "$T_STDOUT"
  # Confirm that every suite test step must invoke a driver; raw shell action or plug-in directives
  # are rejected as invalid test definitions.
  bad=""
  for kind in 'action: exit 0' 'plugin: example hello'; do
    printf 'suite: nodriver\nclass: validation\n\ntest: one\n  %s\n' "$kind" >"$dir/nodriver.suite"
    t_run env PX_PLUGINS=example "$T_BASH" "$T_BIN/px-suite" --dry-run "$dir/nodriver.suite"
    [ "$T_STATUS" = 2 ] || bad="$bad $kind=$T_STATUS"
  done
  t_empty suite_refuses_a_step_that_is_not_a_driver "$bad"
  t_a5_state_reset
}

## @description  Reports the verb rows recorded in run-record.tsv of a bundle (bundle) as a
##               space-separated sequence of verb names.
## @return       Writes the verb names to stdout. Exit 0.
t_verbs_of() {
  awk -F '\t' '$1 == "verb" { printf "%s%s", s, $2; s = " " }' "$1/run-record.tsv" 2>/dev/null
}

## @description  Reports whether the stop script executed after the Java invocation in the fake
##               runtime call log.
## @return       Writes yes or no to stdout. Exit 0.
t_stopped_after_java() {
  awk '/px-java/ { j = NR } /px-stop/ && j && NR > j { s = 1 } END { print s ? "yes" : "no" }' \
    "$T_SHIM_LOG"
}

## @description  Verifies A5 review regressions on the fakes: verb order, describe exit codes,
##               disruption recovery, Java process stop, class codes, timeout bounds, and refusals.
## @return       Records test assertions. Exit 0.
t_group_a5_fixes() {
  local run line bad="" doc dir copy seed want got
  local words=()
  local rs='/jmx?qry=Hadoop:service=HBase,name=RegionServer,sub=Server'
  printf '# A5: the regressions of the review\n'
  # Verify requirement A5-REQ-04: rule 1 of docs/driver-contract.md governs the verb sequence and
  # exit code for each failure outcome: exit 1 when validate fails, exit 1 when prepare fails,
  # exit 4 on a workload timeout, and exit 3 on an unplanned instance restart.
  while IFS='|' read -r line want code; do
    t_a5_state_reset
    read -r -a words <<<"$line"
    t_a5_run "${words[@]}"
    got="$(t_verbs_of "$(t_bundle)")"
    [ "$got $T_STATUS" = "$want $code" ] || bad="$bad $line gave '$got' $T_STATUS;"
  done <<'A5_EOF'
validate-fails|describe validate report|1
prepare-fails|describe validate prepare restore report|1
sleep --timeout 1 -- 30|describe validate prepare run restore report|4
bump-restart -- 4|describe validate prepare run report|3
A5_EOF
  t_empty verbs_follow_rule_one_for_each_outcome "$bad"
  # Verify finding F13: the describe verb keeps its own exit code. A failed describe is recorded
  # as FAIL with exit 1, and a describe that exceeds its timeout bound exits with code 4.
  t_a5_state_reset
  t_a5_run describe-fails
  t_equals describe_failure_keeps_its_code "1 FAIL" \
    "$T_STATUS $(t_severity_of "$(t_bundle)" driver.describe)"
  t_a5_run describe-sleeps -e PX_TIMEOUT_VERB=1
  t_equals describe_past_its_bound_is_a_timeout 4 "$T_STATUS"
  # Verify regression A5-LIVE-5: an omitted or empty artifacts array in a describe document
  # declares no extra artifacts, and every path in both artifact lists must remain relative to the
  # bundle root.
  doc='{"contract": 1, "driver": "d", "phases": [{"anchor": "steady@x", "verb": "run"}],
 "metrics": {"count": [], "share": [], "duration": []}, "unsupported": {"checks": []'
  dir="$T_A5/describe-cases"
  mkdir -p "$dir"
  printf '%s, "artifacts": []}}\n' "$doc" >"$dir/absent.json"
  printf '%s, "artifacts": []}, "artifacts": []}\n' "$doc" >"$dir/empty.json"
  printf '%s, "artifacts": []}, "artifacts": [{"path": "/x"}]}\n' "$doc" >"$dir/absolute.json"
  printf '%s, "artifacts": [{"path": "../x", "reason": "r"}]}}\n' "$doc" >"$dir/up.json"
  got=""
  for line in absent empty absolute up; do
    t_lib pb_describe_load "$dir/$line.json"
    got="$got $T_STATUS"
  done
  t_equals describe_artifacts_member_is_optional_and_inside " 0 0 2 2" "$got"
  # Verify finding F5: killing a role other than regionserver or specifying a destructive delete
  # mode is rejected because neither has a valid recovery path.
  t_a5_state_reset
  t_shim_log_reset
  t_a5_run phase-kill --disrupt 'phase:setup,kill,master,0'
  t_equals kill_of_another_role_is_refused "2 yes" \
    "$T_STATUS $(t_flag 'only a RegionServer kill has a recovery path' "$T_STDERR")"
  t_a5_run phase-kill --disrupt 'phase:setup,kill,regionserver,0,delete'
  t_equals kill_with_delete_is_refused "2 yes" \
    "$T_STATUS $(t_flag 'a deleted instance cannot start again' "$T_STDERR")"
  t_empty refused_kill_reaches_no_runtime "$(grep -E '^(kill|rm|stop|start) ' "$T_SHIM_LOG")"
  t_a5_run phase-kill --disrupt '+1,kill,master,0'
  t_equals offset_kill_of_another_role_runs_no_verb "2:" \
    "$T_STATUS:$(t_verbs_of "$(t_bundle)")"
  # Verify regression A5-LIVE-3 (a): a failed disruption injection causes the cluster.recovery
  # check to record a FAIL verdict.
  t_a5_state_reset
  t_a5_run phase-kill --disrupt 'phase:phase@kill,kill,regionserver,7'
  run="$(t_bundle)"
  t_equals failed_injection_fails_the_recovery "1 FAIL yes" "$T_STATUS $(t_severity_of "$run" \
    cluster.recovery) $(t_flag 'injection failed, exit' "$(cat "$run/verdicts.tsv")")"
  # Verify requirement A5-REQ-13: when no background health monitor samples exist, the cluster.oom
  # verdict detail explicitly notes that peak memory was derived from snapshots only.
  t_contains oom_peak_without_monitor_names_the_snapshots 'from the snapshots only' \
    "$(awk -F '\t' '$1 == "cluster.oom"' "$run/verdicts.tsv")"
  # Verify regression A5-LIVE-3 (b): a milestone anchor that opens and closes between two poll
  # checks is recorded as missed and causes the cluster.recovery check to fail.
  t_a5_state_reset
  t_a5_run blink --disrupt 'phase:phase@blink,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals missed_anchor_fails_the_recovery "1 missed FAIL" "$T_STATUS $(t_manifest "$run" \
    /disruptions/0/state) $(t_severity_of "$run" cluster.recovery)"
  t_contains missed_anchor_names_its_begin "anchor phase@blink began at " \
    "$(awk -F '\t' '$1 == "skip" && $3 == "disruption 1"' "$run/run-record.tsv")"
  # Verify regression A5-LIVE-3 (a): a brief anchor that opens and closes within a single main poll
  # interval still triggers its scheduled disruption because px-run checks stage anchors every tenth
  # of a second.
  t_a5_state_reset
  t_a5_run brief -e PX_POLL_INTERVAL_S=3 --disrupt 'phase:steady@script,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals brief_anchor_fires_its_disruption "0 ok 1 1 0" "$T_STATUS $(t_manifest "$run" \
    /disruptions/0/state) $(awk -F '\t' '$1 == "action" { a++ } $1 == "recover" { r++ }
    $1 == "skip" && $3 == "disruption 1" { s++ } END { print a + 0, r + 0, s + 0 }' \
    "$run/run-record.tsv")"
  # Verify A7: the recovery gate starts the killed instance, updating its start time and raising
  # its restart count from 0 to 1 just as on a real container runtime. The planned kill covers
  # that change, so the run passes.
  t_equals planned_kill_at_steady_counts_a_planned_restart "yes PASS" \
    "$(t_flag "$(t_rows 8 diff regionserver 0 0 1 planned running running)" \
      "$(cat "$run/run-record.tsv" 2>/dev/null)") $(t_severity_of "$run" cluster.restart)"
  # Confirm that disruption entries that never fired or were skipped by the backend provide no
  # recovery evidence.
  dir="$(t_check_bundle skipped "$(t_rows 9 disrupt 1 phase phase:phase@kill kill \
    regionserver 0 hard skip)")"
  grep -v '^recover' "$dir/run-record.tsv" >"$dir/r.tsv"
  mv -f "$dir/r.tsv" "$dir/run-record.tsv"
  t_cluster_checks "$dir"
  t_equals skipped_disruption_is_no_evidence SKIP "$(t_severity_of "$dir" cluster.recovery)"
  # Verify requirement A5-REQ-01: the recovery gate blocks until the online region count returns to
  # the pre-disruption baseline captured by the initial read.
  t_a5_state_reset
  printf '%s\n' 12 8 8 12 >"$T_A5_STATE/region-count"
  t_a5_run phase-kill --disrupt 'phase:phase@kill,kill,regionserver,0'
  run="$(t_bundle)"
  got="$(awk -F '\t' '$1 == "recover" { print $4 }' "$run/run-record.tsv")"
  t_equals recovery_gate_waits_for_the_regions "0 12 8 8 12 yes" "$T_STATUS $(cut -f 2 \
    "$T_A5_STATE/region-count.log" | tr '\n' ' ')$(awk -F '\t' -v r="${got:-0}" \
    'NR == 3 { low = $1 } END { print (low > 0 && r >= low) ? "yes" : "no" }' \
    "$T_A5_STATE/region-count.log")"
  # Confirm that if the online region count remains below the baseline when the readiness timeout
  # expires, recovery is recorded as failed.
  t_a5_state_reset
  printf '%s\n' 12 8 >"$T_A5_STATE/region-count"
  t_a5_run phase-kill -e PX_TIMEOUT_READY=2 --disrupt 'phase:phase@kill,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals lost_regions_fail_the_recovery "fail FAIL" "$(awk -F '\t' '$1 == "recover" {
    print $5 }' "$run/run-record.tsv") $(t_severity_of "$run" cluster.recovery)"
  # Verify that a region-count read that continues to fail until the readiness bound expires aborts
  # the disruption without injecting anything.
  t_a5_state_reset
  t_shim_log_reset
  : >"$T_A5_STATE/region-count-fails"
  t_a5_run phase-kill -e PX_TIMEOUT_READY=2 --disrupt 'phase:phase@kill,kill,regionserver,0'
  t_equals unread_regions_inject_nothing "fail no" "$(t_manifest "$(t_bundle)" \
    /disruptions/0/state) $(t_flag 'KILL' "$(cat "$T_SHIM_LOG")")"
  # Verify D-3: immediately after a restart or rebalance, a RegionServer may answer JMX queries
  # without publishing regionCount. The pre-injection read treats the server as not yet ready,
  # retries within the readiness bound, and fires the disruption entry once the count is published.
  t_a5_state_reset
  t_shim_log_reset
  printf '%s\n' none none 12 >"$T_A5_STATE/region-count"
  t_a5_run phase-kill --disrupt 'phase:phase@kill,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals missing_region_count_is_read_again_before_the_injection "0 ok yes none none 12" \
    "$T_STATUS $(t_manifest "$run" /disruptions/0/state) $(t_flag 'KILL' \
    "$(cat "$T_SHIM_LOG")") $(cut -f 2 "$T_A5_STATE/region-count.log" | sed -n 1,3p |
      tr '\n' ' ' | sed 's/ $//')"
  # Verify D-4: a STOP disruption pauses the RegionServer daemon JVM, and its recovery waits for
  # the scheduled CONT. The CONT reuses the baseline captured for the STOP because a paused server
  # cannot respond, while the driver records the state of the fake JVM throughout its execution.
  t_a5_state_reset
  printf '%s\n' "$T_SWEEP_JVM" >"$T_A5_STATE/jvm-pid"
  t_a5_run jvm-probe --disrupt '+1,signal,regionserver,0,STOP' \
    --disrupt '+3,signal,regionserver,0,CONT' -- 5
  run="$(t_bundle)"
  t_equals stop_recovers_at_its_cont "0 ok ok yes run 2 yes" "$T_STATUS $(t_manifest "$run" \
    /disruptions/0/state) $(t_manifest "$run" /disruptions/1/state) $(t_flag T \
    "$(cat "$T_A5_STATE/jvm-states" 2>/dev/null)") $(t_proc_state "$T_SWEEP_JVM") $(awk \
    -F '\t' '$1 == "action" && $6 == "CONT" { c = $2 }
    $1 == "recover" && $5 == "ok" { n++; r[$2] = $4 }
    END { print n + 0, (c > 0 && r[1] >= c) ? "yes" : "no" }' "$run/run-record.tsv")"
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  # Verify that a STOP disruption left unresumed by the schedule receives an automatic CONT from
  # px-run at the end of the run verb, recorded as a distinct action in the run record.
  t_a5_state_reset
  printf '%s\n' "$T_SWEEP_JVM" >"$T_A5_STATE/jvm-pid"
  t_a5_run jvm-probe --disrupt '+1,signal,regionserver,0,STOP' -- 3
  run="$(t_bundle)"
  t_equals unresumed_stop_is_resumed_at_the_end "0 yes run yes 1 1" "$T_STATUS $(t_flag T \
    "$(cat "$T_A5_STATE/jvm-states" 2>/dev/null)") $(t_proc_state "$T_SWEEP_JVM") $(t_flag \
    'px-run resumes it' "$T_STDERR") $(awk -F '\t' '$1 == "action" && $6 == "CONT" &&
    $7 == "ok" { c++ } $1 == "recover" && $2 == 1 && $5 == "ok" { r++ }
    END { print c + 0, r + 0 }' "$run/run-record.tsv")"
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  # Verify that the pause window between a STOP and its CONT rejects any intervening disruption:
  # because a paused RegionServer cannot respond, a region read or recovery gate for another entry
  # would otherwise wait up to PX_TIMEOUT_READY (left at its default here) and delay the CONT. The
  # intervening entry injects nothing, and the CONT is delivered on schedule.
  t_a5_state_reset
  t_shim_log_reset
  printf '%s\n' "$T_SWEEP_JVM" >"$T_A5_STATE/jvm-pid"
  t_a5_run jvm-probe --disrupt '+1,signal,regionserver,0,STOP' \
    --disrupt '+2,signal,client,0,QUIT' --disrupt '+3,signal,regionserver,0,CONT' -- 5
  run="$(t_bundle)"
  t_equals pause_window_allows_only_its_cont "ok fail ok run yes 0 yes" "$(t_manifest "$run" \
    /disruptions/0/state) $(t_manifest "$run" /disruptions/1/state) $(t_manifest "$run" \
    /disruptions/2/state) $(t_proc_state "$T_SWEEP_JVM") $(t_flag \
    'regionserver/0 is paused until its CONT' "$T_STDERR") $(awk -F '\t' '
    $1 == "action" && $6 == "STOP" { s = $2 } $1 == "action" && $6 == "QUIT" { q++ }
    $1 == "action" && $6 == "CONT" { c = $2 }
    END { print q + 0, (s > 0 && c > s && c - s < 10000) ? "yes" : "no" }' \
    "$run/run-record.tsv")"
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  # Verify that when a STOP state check fails after the kill signal is sent, the JVM may still be
  # paused; the disruption fails without running a recovery gate, and px-run still sends CONT at
  # the end of the run verb.
  t_a5_state_reset
  printf '%s\n' "$T_SWEEP_JVM" >"$T_A5_STATE/jvm-pid"
  t_ps_stop_fails "$T_A5_STATE"
  t_a5_run jvm-probe --disrupt '+1,signal,regionserver,0,STOP' -- 3
  run="$(t_bundle)"
  t_equals failed_stop_is_resumed_at_the_end "fail yes run yes 1 0" "$(t_manifest "$run" \
    /disruptions/0/state) $(t_flag T "$(cat "$T_A5_STATE/jvm-states" 2>/dev/null)")\
 $(t_proc_state "$T_SWEEP_JVM") $(t_flag 'px-run resumes it' "$T_STDERR") $(awk -F '\t' '
    $1 == "action" && $6 == "CONT" && $7 == "ok" { c++ } $1 == "recover" && $2 == 1 { r++ }
    END { print c + 0, r + 0 }' "$run/run-record.tsv")"
  t_ps_stop_fails "$T_A5_STATE" off
  kill -s CONT "$T_SWEEP_JVM" 2>/dev/null
  # Confirm that px-region-count in the container image sums regionCount across all RegionServers
  # and fails if any server does not respond.
  t_curl_body "http://regionserver-0:16030$rs" '{"beans":[{"regionCount" : 5}]}'
  t_curl_body "http://regionserver-1:16030$rs" '{"beans":[{"regionCount" : 7}]}'
  t_image PX_ROLE=client PX_RS_COUNT=2 PX_PORT_REGIONSERVER_HTTP=16030 -- "$T_BASH" \
    "$T_IMG/bin/px-region-count"
  t_equals region_count_sums_the_servers "0 12" "$T_STATUS $T_STDOUT"
  t_image PX_ROLE=client PX_RS_COUNT=3 PX_PORT_REGIONSERVER_HTTP=16030 -- "$T_BASH" \
    "$T_IMG/bin/px-region-count"
  t_equals region_count_refuses_a_silent_server 1 "$T_STATUS"
  # Verify regression A5-LIVE-1: when a remote exec exceeds its timeout bound or px-run receives
  # SIGTERM, the driver invokes the stop script to terminate the Java process inside client 0.
  t_a5_state_reset
  t_shim_log_reset
  run="$(t_a5_bundle)"
  t_a5 "PX_RUN_DIR=$run" "PX_RUN_ID=${run##*/}" PX_DRIVER_NAME=java-main \
    "PX_DRIVER_DIR=$run/drivers/java-main" PX_VERB=run PX_RECORD_SOURCE=driver:java-main \
    PX_RUN_CLASS=validation PX_BACKEND=compose PX_SCOPE_ID=px-selftest PX_TIMEOUT_RUN=1 \
    "$T_BASH" "$PX_HOME/drivers/java-main/driver.sh" run org.example.Sleep
  t_equals expired_class_is_stopped_in_client_0 "1 yes" "$T_STATUS $(t_stopped_after_java)"
  # Verify finding F1: the workload JVM for prepare and run uses the PX_TIMEOUT_RUN bound set by
  # px-run --timeout, allowing a workload exceeding PX_TIMEOUT_DEFAULT to complete while the
  # table-recording catalog query retains PX_TIMEOUT_DEFAULT.
  t_a5_state_reset
  : >"$T_A5_STATE/java-naps"
  got=""
  run="$(t_a5_bundle)"
  PX_TIMEOUT_DEFAULT=1 PX_TIMEOUT_RUN=60 t_a5_verb psql run "$run" "$T_A5/sql/fixture.sql"
  got="$got psql:$T_STATUS"
  run="$(t_a5_bundle)"
  PX_TIMEOUT_DEFAULT=1 PX_TIMEOUT_RUN=60 t_a5_verb java-main prepare "$run" \
    --prepare "$T_A5/sql/jm-schema.sql" org.example.Fixture
  got="$got java-main.prepare:$T_STATUS"
  PX_TIMEOUT_DEFAULT=1 PX_TIMEOUT_RUN=60 t_a5_verb java-main run "$run" org.example.Fixture
  got="$got java-main.run:$T_STATUS"
  run="$(t_a5_bundle)"
  PX_TIMEOUT_DEFAULT=1 PX_TIMEOUT_RUN=60 t_a5_verb pherf prepare "$run" \
    --scenario "$PX_HOME/suites/pherf/px_functional_scenario.xml" \
    --schema "$PX_HOME/suites/pherf/px_functional_schema.sql"
  got="$got pherf.prepare:$T_STATUS:$(grep -c . "$run/drivers/pherf/tables.list" 2>/dev/null)"
  rm -f "$T_A5_STATE/java-naps"
  t_equals workload_jvm_takes_the_run_bound \
    " psql:0 java-main.prepare:0 java-main.run:0 pherf.prepare:0:1" "$got"
  t_a5_state_reset
  t_shim_log_reset
  t_a5 "$T_BASH" "$T_BIN/px-run" --class validation -e PX_SCOPE_ID=px-selftest \
    java-main --timeout 2 -- org.example.Sleep
  t_equals terminated_class_is_stopped_in_client_0 "4 yes" "$T_STATUS $(t_stopped_after_java)"
  # Verify findings F2 and F12: a non-zero exit code from the Java main class is treated as a
  # workload failure (exit 1), and the report verb synthesizes a query_errors metric from that exit
  # status when the class itself emitted no query_errors metric.
  got=""
  for line in Exit6 Exit2 Fixture; do
    t_a5_state_reset
    t_a5 "$T_BASH" "$T_BIN/px-run" --class validation -e PX_SCOPE_ID=px-selftest \
      java-main -- "org.example.$line"
    run="$(t_bundle)"
    got="$got $line:$T_STATUS:$(t_severity_of "$run" java-main.exit):$(t_manifest "$run" \
      /steady/0/metrics/query_errors/value)"
  done
  t_equals class_status_belongs_to_the_workload " Exit6:1:FAIL:1 Exit2:1:FAIL:1 Fixture:0::0" \
    "$got"
  # Verify finding F3: px-suite does not support an overrides directive and rejects it as unknown.
  printf 'suite: o\nclass: validation\n\ntest: one\n  driver: psql\n  overrides: x.env\n' \
    >"$T_A5/overrides.suite"
  t_run "$T_BASH" "$T_BIN/px-suite" --dry-run "$T_A5/overrides.suite"
  t_equals overrides_is_an_unknown_directive "2 yes" \
    "$T_STATUS $(t_flag "unknown directive 'overrides'" "$T_STDERR")"
  # Verify finding S1: px-run resolves driver names through the plug-in library and rejects unknown
  # drivers with usage exit code 2.
  t_run "$T_BASH" "$T_BIN/px-run" --dry-run nosuchdriver
  t_equals unknown_driver_is_a_usage_error "2 yes" \
    "$T_STATUS $(t_flag "no driver 'nosuchdriver'" "$T_STDERR")"
  # Verify finding S12: rejecting an unrecognized plug-in hook lists all nine valid hook names on a
  # single diagnostic line.
  dir="$T_A5/badhook"
  mkdir -p "$dir"
  printf '%s\n' PX_PLUGIN_ID=badhook PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=bogus >"$dir/plugin.env"
  t_run env "PX_PLUGIN_DIRS=$dir" "$T_BASH" "$T_WORK/probe/a5.sh" pl_discover
  line='conf-overlay jvm-opts image-check fetch-data pre-workload post-workload suites thresholds'
  t_equals unknown_hook_refusal_names_the_hooks "2 yes" \
    "$T_STATUS $(t_flag "The hooks are: $line collect" "$T_STDERR")"
  # Verify finding F7: the outer timeout bound computed for px-run accounts for the four bounded
  # hooks of each enabled plug-in as well as the recovery gate budget for every test-level and
  # session-level disruption.
  printf 'suite: b\nclass: validation\ntimeout: 60\n\ntest: one\n  driver: %s\n  args: 0\n' \
    "$T_WORK/drivers/exit-code.sh" >"$T_A5/bound.suite"
  printf '  disrupt: phase:never@x,kill,regionserver,0\n' >>"$T_A5/bound.suite"
  t_a5_state_reset
  t_a5 PX_PLUGINS=example PX_SESSION_ID=a5-bound PX_SCOPE_ID=px-selftest \
    PX_DISRUPT_SPEC=+900,kill,regionserver,0 PX_TIMEOUT_VERB=10 PX_TIMEOUT_FETCH=100 \
    PX_TIMEOUT_DRAIN=20 PX_TIMEOUT_READY=30 \
    "$T_BASH" "$T_BIN/px-suite" -v "$T_A5/bound.suite"
  # Expected bound: 60 + 300 + 6 x 10 + 1 x (3 x 10 + 100) + 2 x (20 + 2 x 30) + 0 + 1 = 711s.
  t_contains suite_bound_counts_hooks_checks_and_recovery 'px-run gets a bound of 711s' \
    "$T_STDERR"
  # Verify finding F6: heap sizing flags such as -XX:MaxHeapSize are rejected both in role JVM
  # options and in lines emitted by a plug-in jvm-opts hook.
  t_gen --class validation --cluster heap1 -e PX_RS_JVM_OPTS=-XX:MaxHeapSize=16g
  t_equals render_refuses_max_heap_size_in_role_options 2 "$T_STATUS"
  copy="$T_A5/plugins/heap"
  mkdir -p "$copy/hooks"
  printf '%s\n' PX_PLUGIN_ID=heap PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=jvm-opts \
    >"$copy/plugin.env"
  printf '#!/usr/bin/env bash\nprintf "RS -XX:MaxHeapSize=16g\\n"\n' >"$copy/hooks/jvm-opts"
  chmod +x "$copy/hooks/jvm-opts"
  t_gen --class validation --cluster heap2 -e "PX_PLUGIN_DIRS=$copy" -e PX_PLUGINS=heap
  t_equals render_refuses_max_heap_size_from_the_hook 2 "$T_STATUS"
  # Verify requirement A5-REQ-13: peak memory evaluation incorporates samples collected by the
  # background health monitor.
  t_a5_state_reset
  seed="$T_A5/monitor-seed"
  mkdir -p "$seed"
  : >"$seed/samples.tsv"
  px_tsv_row 1 "px-tool monitor" "$seed" >"$PX_OUT/state/monitor-px-selftest"
  t_a5 "PX_FAKE_MONITOR_SAMPLES=$seed/samples.tsv" "$T_BASH" "$T_BIN/px-run" --class validation \
    -e PX_SCOPE_ID=px-selftest "$T_WORK/drivers/monitor-peak.sh"
  run="$(t_bundle)"
  : >"$PX_OUT/state/monitor-px-selftest"
  t_equals monitor_peak_fails_the_oom_check "FAIL yes" "$(t_severity_of "$run" \
    cluster.oom) $(t_flag "sample${PX_TAB}monitor${PX_TAB}" "$(cat "$run/run-record.tsv")")"
  t_a5_state_reset
}

## @description  Lists any sb_* calls in drivers under a root directory (root) outside the three G4
##               functions and sb_load (which binds the backend), formatted as file:line:name.
## @return       Writes matching occurrences to stdout, or nothing for a compliant tree. Exit 0.
t_driver_sb_calls() {
  local file
  for file in "$1"/drivers/*/driver.sh "$1"/drivers/lib/*.sh "$1"/lib/fleet.sh \
    "$1"/plugins/*/driver.sh; do
    [ -f "$file" ] || continue
    grep -noE 'sb_[a-z_]+' "$file" | awk -F: -v f="$file" \
      '$2 !~ /^sb_(exec|copy_in|copy_out|load)$/ { print f ":" $1 ":" $2 }'
  done
}

## @description  Reports the verb rows of a bundle (bundle) as space-separated verb:exit tokens.
## @return       Writes the formatted tokens to stdout. Exit 0.
t_verb_codes() {
  awk -F '\t' '$1 == "verb" { printf "%s%s:%s", s, $2, $3; s = " " }' "$1/run-record.tsv" \
    2>/dev/null
}

## @description  Counts malformed rows across both metric export tables of a bundle (bundle) that
##               lack ten fields, a valid run class, or a non-empty contamination field.
## @return       Writes the malformed row count to stdout. Exit 0.
t_metric_gaps() {
  cat "$1/metrics/aggregate.tsv" "$1/metrics/timeline.tsv" 2>/dev/null | awk -F '\t' '
    $1 == "start_ms" { next }
    NF != 10 || ($9 != "validation" && $9 != "measurement") || $10 == "" { n++ }
    END { print n + 0 }'
}

## @description  Verifies A5 contract closure on the fakes: sizing contamination, threshold layers,
##               plug-in rendering and staging, anomaly records, bundle rules, reports, and drivers.
## @return       Records test assertions. Exit 0.
t_group_a5_closure() {
  local run copy dir base=$T_A5_BASE got line one two deploy cause rows
  local sized psql pherf example
  printf '# A5: the closure of the contracts\n'
  # Verify requirement RQ-095: a measurement run executed with a sizing override is marked
  # contaminated, the contamination detail names the overridden variable, and the sizing tag is
  # propagated to every metric record.
  t_a5_state_reset
  t_a5 "$T_BASH" "$T_BIN/px-run" --class measurement -e PX_SCOPE_ID=px-selftest \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_QUIET_HOST_WAIT_S=0 -e PX_RS_HEAP_MIB=1024 \
    "$T_WORK/drivers/phase-kill.sh"
  sized="$(t_bundle)"
  t_equals sizing_override_tags_the_run "0 quiet-host sizing" "$T_STATUS $(t_manifest "$sized" \
    /quiet_host/tags/0) $(t_manifest "$sized" /quiet_host/tags/1)"
  t_contains sizing_override_names_the_value 'overridden: PX_RS_HEAP_MIB' \
    "$(awk -F '\t' '$1 == "contamination" && $2 == "sizing"' "$sized/run-record.tsv")"
  t_equals sizing_override_reaches_every_metric_record "quiet-host,sizing" \
    "$(awk -F '\t' 'NR > 1 { print $10 }' "$sized/metrics/aggregate.tsv" | sort -u)"
  # Confirm that a sizing contamination tag is also recorded when the cluster's deployment topology
  # records a sizing override or was rendered for a different run class.
  printf '%s\n' PX_RUN_CLASS=measurement PX_SIZING_OVERRIDES=PX_RS_MEM_MIB \
    >>"$T_WORK/deploy-selftest/topology.env"
  t_a5 "$T_BASH" "$T_BIN/px-run" --class measurement -e PX_SCOPE_ID=px-selftest \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_QUIET_HOST_WAIT_S=0 "$T_WORK/drivers/exit-code.sh"
  t_contains sizing_override_of_the_cluster_tags_the_run 'overridden: PX_RS_MEM_MIB' \
    "$(grep "^contamination${PX_TAB}sizing" "$(t_bundle)/run-record.tsv")"
  t_seed_deployment "$PX_OUT"
  printf 'PX_RUN_CLASS=validation\n' >>"$T_WORK/deploy-selftest/topology.env"
  t_a5 "$T_BASH" "$T_BIN/px-run" --class measurement -e PX_SCOPE_ID=px-selftest \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_QUIET_HOST_WAIT_S=0 "$T_WORK/drivers/exit-code.sh"
  t_contains cluster_of_another_class_tags_the_run 'the sizing of the class validation' \
    "$(grep "^contamination${PX_TAB}sizing" "$(t_bundle)/run-record.tsv")"
  t_seed_deployment "$PX_OUT"
  t_a5 "$T_BASH" "$T_BIN/px-run" --class measurement -e PX_SCOPE_ID=px-selftest \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_QUIET_HOST_WAIT_S=0 "$T_WORK/drivers/exit-code.sh"
  t_equals measurement_sizing_has_no_sizing_tag 0 \
    "$(grep -c "^contamination${PX_TAB}sizing" "$(t_bundle)/run-record.tsv")"
  t_gen --class measurement --cluster sized -e PX_RS_HEAP_MIB=1024
  t_equals generator_records_the_sizing_override "0 PX_RS_HEAP_MIB" "$T_STATUS $(t_env_value \
    "$T_A2_OUT/deploy/sized/topology.env" PX_SIZING_OVERRIDES)"
  t_gen --class validation --cluster unsized
  t_equals generator_records_no_override_of_the_class "0 " "$T_STATUS $(t_env_value \
    "$T_A2_OUT/deploy/unsized/topology.env" PX_SIZING_OVERRIDES)"
  # Verify requirements RQ-095 and RQ-085: on a sizing-contaminated measurement bundle, a latency
  # FAIL is downgraded to ADVISORY and passes the run while a count FAIL still fails the run; on an
  # uncontaminated measurement bundle, a latency FAIL remains a run failure.
  copy="$(t_bundle_copy "$sized" latency)"
  printf 'latency\tFAIL\tduration\tdriver:x\tslow\n' >>"$copy/verdicts.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals contaminated_latency_fail_passes "0 ADVISORY" "$T_STATUS $(t_result)"
  printf 'rows\tFAIL\tcount\tdriver:x\tshort\n' >>"$copy/verdicts.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals contaminated_count_fail_still_fails "1 FAIL" "$T_STATUS $(t_result)"
  copy="$(t_bundle_copy "$sized" clean)"
  printf 'latency\tFAIL\tduration\tdriver:x\tslow\n' >>"$copy/verdicts.tsv"
  grep -v "^contamination$PX_TAB" "$copy/run-record.tsv" >"$copy/r.tsv"
  mv -f "$copy/r.tsv" "$copy/run-record.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals clean_measurement_latency_fail_fails "1 FAIL" "$T_STATUS $(t_result)"

  # Verify requirements RQ-120 and RQ-119: when a run-class configuration file and the example
  # plug-in each override a threshold, the effective threshold set retains both overrides alongside
  # all remaining defaults, and the GC pause verdict for a 750 ms pause flips under the class
  # override. When the class file and the plug-in both set the same key, the plug-in takes
  # precedence.
  t_a5_state_reset
  printf '%s\n' PX_THRESHOLD_GC_PAUSE_MS_MAX=500 PX_THRESHOLD_LOG_WARNINGS_MAX=50 \
    >"$T_A5/class-threshold.env"
  printf '%s\n' '[1.0s][info][gc] GC(3) Pause Young (Normal) 51M->12M(256M) 750.000ms' \
    >"$T_A5_STATE/copy-out.txt"
  dir="$(t_check_bundle classlayer)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "PX_CLASS_ENV=$T_A5/class-threshold.env" PX_PLUGINS=example \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  got="$(LC_ALL=C comm -3 <(grep -v '^#' "$PX_HOME/suites/thresholds.properties" | grep . |
    LC_ALL=C sort) <(grep -v '^#' "$dir/thresholds.properties" | grep . | LC_ALL=C sort) |
    tr -d '\t' | tr '\n' ' ')"
  t_equals class_and_plugin_thresholds_are_in_force "0 gc.pause_ms.max=1000\
 gc.pause_ms.max=500 log.warnings.max=100 log.warnings.max=200 " "$T_STATUS $got"
  t_contains class_threshold_names_its_origin '# config:PX_THRESHOLD_GC_PAUSE_MS_MAX' \
    "$(cat "$dir/thresholds.properties")"
  t_equals class_threshold_fails_the_boundary_pause FAIL "$(t_severity_of "$dir" cluster.gc)"
  # Confirm that an operator threshold supplied via the environment overrides the plug-in threshold.
  dir="$(t_check_bundle operatorlayer)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "PX_CLASS_ENV=$T_A5/class-threshold.env" PX_PLUGINS=example PX_THRESHOLD_LOG_WARNINGS_MAX=5 \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals operator_threshold_wins_over_the_plugin "0 log.warnings.max=5 gc.pause_ms.max=500" \
    "$T_STATUS $(grep -E '^(log\.warnings|gc\.pause_ms)\.max=' "$dir/thresholds.properties" |
      LC_ALL=C sort -r | tr '\n' ' ' | sed 's/ $//')"
  dir="$(t_check_bundle nolayer)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals default_threshold_passes_the_boundary_pause PASS "$(t_severity_of "$dir" cluster.gc)"
  dir="$(t_check_bundle boundary)"
  sed 's/^gc\.pause_ms\.max=.*/gc.pause_ms.max=700/' "$dir/thresholds.properties" >"$dir/t"
  mv -f "$dir/t" "$dir/thresholds.properties"
  t_cluster_checks "$dir"
  t_equals changed_key_flips_the_boundary_verdict FAIL "$(t_severity_of "$dir" cluster.gc)"
  rm -f "$T_A5_STATE/copy-out.txt"
  dir="$(t_check_bundle typo)"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    PX_THRESHOLD_GC_PAUSE_MAX=5 "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals misspelt_threshold_name_runs_no_check "2 ERROR yes" "$T_STATUS $(t_severity_of "$dir" \
    cluster.thresholds) $(t_flag 'PX_THRESHOLD_GC_PAUSE_MAX names no threshold key' \
    "$(cat "$dir/verdicts.tsv")")"
  t_run "$T_BASH" "$T_BIN/px-cluster" --class validation --config "$T_A5/class-threshold.env" \
    config
  t_contains class_file_takes_a_threshold "PX_THRESHOLD_GC_PAUSE_MS_MAX${PX_TAB}500" "$T_STDOUT"

  # Verify requirements RQ-083 and RQ-082: when p99 query latency and throughput attainment breach
  # their thresholds, a validation-class bundle containing only those timing breaches still passes
  # with ADVISORY status, whereas the same breaches in a quiet measurement-class run fail the run.
  t_a5_state_reset
  dir="$(t_check_bundle timing)"
  {
    printf '%s\n' "start_ms end_ms source anchor key class value unit run_class contamination" |
      tr ' ' '\t'
    for line in query_latency.p99_ms:5000:ms offered_rate:100:ops/s achieved_qps:10:ops/s; do
      IFS=: read -r one two cause <<<"$line"
      t_rows 10 1 2 driver:x steady@x "$one" duration "$two" "$cause" validation none
    done
  } >"$dir/metrics.tsv"
  mkdir -p "$dir/metrics"
  mv -f "$dir/metrics.tsv" "$dir/metrics/aggregate.tsv"
  t_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=px-selftest PX_BACKEND=compose PX_RUN_CLASS=validation \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals steady_checks_fail_the_breaches "FAIL FAIL" "$(t_severity_of "$dir" \
    steady.query_latency.p99_ms) $(t_severity_of "$dir" steady.attainment)"
  t_contains steady_attainment_names_the_share 'attains 10.0% of its offered rate' \
    "$(cat "$dir/verdicts.tsv")"
  t_run "$T_BASH" "$T_BIN/px-report" "$dir"
  t_equals validation_bundle_with_only_timing_breaches_passes "0 ADVISORY" \
    "$T_STATUS $(t_result)"
  sed "s/^run${PX_TAB}checks${PX_TAB}validation/run${PX_TAB}checks${PX_TAB}measurement/" \
    "$dir/run-record.tsv" >"$dir/r.tsv"
  mv -f "$dir/r.tsv" "$dir/run-record.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$dir"
  t_equals quiet_measurement_timing_breach_fails "1 FAIL" "$T_STATUS $(t_result)"
  dir="$(t_check_bundle notiming)"
  t_cluster_checks "$dir"
  t_a5 "PX_RUN_DIR=$dir" PX_THRESHOLDS_FILE="$dir/thresholds.properties" \
    "$T_BASH" "$PX_HOME/suites/checks/steady.sh"
  t_equals steady_checks_without_evidence_skip "SKIP SKIP" "$(t_severity_of "$dir" \
    steady.query_latency.p99_ms) $(t_severity_of "$dir" steady.attainment)"
  # Verify requirement RQ-082: each authoritative validation gate independently fails a validation
  # run when triggered.
  got=""
  for cause in restart oom log readiness; do
    t_a5_state_reset
    case "$cause" in
    restart) dir="$(t_check_bundle "g-$cause" \
      "$(t_rows 8 diff client 0 0 1 unplanned running running)")" ;;
    oom) dir="$(t_check_bundle "g-$cause" "$(t_rows 5 oom client 0 yes OOMKilled)")" ;;
    log)
      dir="$(t_check_bundle "g-$cause")"
      printf '2026-11-20 17:00:00,000 ERROR [main] Server: boom\n' \
        >"$T_A5_STATE/logs-c0ffee00a502.txt"
      ;;
    readiness)
      dir="$(t_check_bundle "g-$cause")"
      : >"$T_A5_STATE/probe-fails"
      ;;
    esac
    t_cluster_checks "$dir"
    t_run "$T_BASH" "$T_BIN/px-report" "$dir"
    got="$got $cause:$T_STATUS:$(t_result)"
  done
  dir="$T_A5/verdicts"
  for cause in 'scenario|driver.workload_valid|none' 'assertion|driver.run|none' \
    'count|psql.rows|count'; do
    IFS='|' read -r one two line <<<"$cause"
    printf '%s\tFAIL\t%s\tdriver:x\t%s\n' "$two" "$line" "$one" >"$dir/gate-$one.tsv"
    t_report validation "" --verdict "$dir/gate-$one.tsv"
    got="$got $one:$T_STATUS:$(t_result)"
  done
  t_equals validation_gates_fail_on_each_cause " restart:3:ERROR oom:3:ERROR log:1:FAIL\
 readiness:1:FAIL scenario:1:FAIL assertion:1:FAIL count:1:FAIL" "$got"
  t_a5_state_reset

  # Verify requirements RQ-080 and A5-REQ-03: an unplanned instance restart aborts the run and
  # populates the manifest anomaly object if and only if the run-record result row indicates an
  # abort, recording the before and after restart counts in both the manifest and the cluster
  # verdict.
  t_a5_run bump-restart -- 4
  run="$(t_bundle)"
  t_equals restart_anomaly_is_in_the_manifest "3 true number 0 4 yes" "$T_STATUS $(t_manifest \
    "$run" /anomaly/aborted) $(t_lib t_a5_json_type "$run/manifest.json" /anomaly/abort_ms
    printf '%s' "$T_STDOUT") $(t_manifest "$run" /anomaly/instances/0/restarts_before)\
 $(t_manifest "$run" /anomaly/instances/0/restarts_after) $(awk -F '\t' '$1 == "result" {
      print $4 }' "$run/run-record.tsv")"
  t_contains restart_anomaly_is_in_the_cluster_verdict 'restarted 0 to 4; restarts 0 to 4' \
    "$(awk -F '\t' '$1 == "cluster.restart" { print $5 }' "$run/verdicts.tsv")"
  t_equals restart_anomaly_bundle_validates 0 "$(t_validate "$run" >/dev/null; printf '%s' \
    "$T_STATUS")"
  t_a5_state_reset
  t_a5_run exit-code -- 0
  run="$(t_bundle)"
  t_equals clean_run_anomaly_matches_the_result_row "null no" "$(t_lib t_a5_json_type \
    "$run/manifest.json" /anomaly; printf '%s' "$T_STDOUT") $(awk -F '\t' '$1 == "result" {
      print $4 }' "$run/run-record.tsv")"
  copy="$(t_bundle_copy "$run" noinstances)"
  line='"anomaly": {"aborted": false, "abort_ms": null, "instances": []},'
  sed "s/^  \"anomaly\": null,\$/  $line/" \
    "$copy/manifest.json" >"$copy/m.json"
  mv -f "$copy/m.json" "$copy/manifest.json"
  t_validate "$copy"
  t_equals anomaly_without_an_instance_is_refused "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" anomaly)"

  # Verify requirement RQ-112: a steady-state manifest entry missing its anchor name, start_ms, or
  # end_ms, or having an end_ms timestamp earlier than start_ms, is rejected with a diagnostic that
  # identifies the specific defect.
  got=""
  for cause in 'name|"anchor": "steady@stub"|"anchor": ""|lacks the name' \
    'start|"start_ms"|"x_start"|lacks start_ms' 'end|"end_ms"|"x_end"|lacks end_ms'; do
    IFS='|' read -r one two line rows <<<"$cause"
    copy="$(t_bundle_copy "$base" "steady-$one")"
    t_json_edit "$copy/manifest.json" '"steady": [' "$two" "$line"
    t_validate "$copy"
    got="$got $one:$T_STATUS:$(t_check_status "$T_STDOUT" steady):$(t_flag "$rows" "$T_STDOUT")"
  done
  copy="$(t_bundle_copy "$base" steady-inverted)"
  awk '/"steady": \[/ { s = 1 } s && !a && /"start_ms":/ {
      sub(/"start_ms": [0-9]+/, "\"start_ms\": 2000000000000"); a = 1 }
    s && !b && /"end_ms":/ { sub(/"end_ms": [0-9]+/, "\"end_ms\": 1000000000000"); b = 1 }
    { print }' "$copy/manifest.json" >"$copy/m.json"
  mv -f "$copy/m.json" "$copy/manifest.json"
  t_validate "$copy"
  got="$got inverted:$T_STATUS:$(t_check_status "$T_STDOUT" steady):$(t_flag \
    'ends at 1000000000000, before its start at 2000000000000' "$T_STDOUT")"
  t_equals steady_defects_are_refused_by_name " name:1:fail:yes start:1:fail:yes\
 end:1:fail:yes inverted:1:fail:yes" "$got"

  # Verify requirement RQ-084: any metric row missing a contamination tag or a run class is
  # rejected in both the aggregate and timeline metric exports.
  got=""
  for cause in aggregate timeline; do
    copy="$(t_bundle_copy "$base" "metric-$cause")"
    printf '1\t2\tdriver:x\trun\tk\tcount\t1\tn\tvalidation\t\n' >>"$copy/metrics/$cause.tsv"
    t_validate "$copy"
    got="$got $T_STATUS:$(t_check_status "$T_STDOUT" "metrics.$cause")"
    copy="$(t_bundle_copy "$base" "metric9-$cause")"
    printf '1\t2\tdriver:x\trun\tk\tcount\t1\tn\tnone\n' >>"$copy/metrics/$cause.tsv"
    t_validate "$copy"
    got="$got $T_STATUS:$(t_check_status "$T_STDOUT" "metrics.$cause")"
  done
  t_equals metric_row_without_its_tags_is_refused " 1:fail 1:fail 1:fail 1:fail" "$got"

  # Verify requirement RQ-117: an optional artifact declared by a driver or plug-in that was not
  # produced during the run is recorded as a SKIP row by both px-report and px-bundle validate, and
  # never as a FAIL.
  copy="$(t_bundle_copy "$base" declared)"
  printf '%s\n' '{"contract": 1, "driver": "phase-kill",' \
    ' "phases": [{"anchor": "steady@stub", "verb": "run"}],' \
    ' "metrics": {"count": [], "share": [], "duration": []},' \
    ' "unsupported": {"checks": [], "artifacts": []},' \
    ' "artifacts": [{"path": "oplog/"}, {"path": "host/"}, {"path": "profiles/"},' \
    '  {"path": "drivers/phase-kill/extra.tsv"}]}' >"$copy/drivers/phase-kill/describe.json"
  printf 'context\tplugins\texample\n' >>"$copy/run-record.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  got="$T_STATUS"
  for line in oplog/ host/ profiles/ drivers/phase-kill/extra.tsv plugins/example/manifest.tsv; do
    grep -q "^artifact\.$line${PX_TAB}SKIP${PX_TAB}" "$copy/report/verdicts.tsv" ||
      got="$got no-report-$line"
  done
  t_validate "$copy"
  got="$got $T_STATUS"
  for line in oplog/ host/ profiles/ drivers/phase-kill/extra.tsv plugins/example/manifest.tsv; do
    printf '%s\n' "$T_STDOUT" | grep -q "^path${PX_TAB}skip${PX_TAB}$line" ||
      got="$got no-validate-$line"
  done
  t_equals declared_artifacts_skip_in_report_and_validator "0 0" "$got"

  # Verify requirements RQ-087, RQ-123 (a), and RQ-117: a full run of the example plug-in merges
  # verdict rows from the driver, the cluster checks, and the plug-in, stages its dataset into
  # client 0 via sb_copy_in, and reports a missing declared driver artifact as a SKIP in px-report.
  # Also verify px-run threshold precedence: the example plug-in overrides a configuration file
  # threshold, while a command-line threshold override takes precedence over every other layer.
  t_a5_state_reset
  t_shim_log_reset
  printf 'PX_THRESHOLD_LOG_WARNINGS_MAX=50\n' >"$T_A5/config-threshold.env"
  t_a5 PX_PLUGINS=example "$T_BASH" "$T_BIN/px-run" --class validation \
    -e PX_SCOPE_ID=px-selftest example --config "$T_A5/config-threshold.env" \
    -e PX_THRESHOLD_GC_PAUSE_MS_MAX=2000
  example="$(t_bundle)"
  t_equals example_run_passes 0 "$T_STATUS"
  t_equals run_thresholds_put_the_plugin_over_the_file_and_cli_over_all \
    "log.warnings.max=200 gc.pause_ms.max=2000" "$(grep -E \
      '^(log\.warnings|gc\.pause_ms)\.max=' "$example/thresholds.properties" 2>/dev/null |
      LC_ALL=C sort -r | tr '\n' ' ' | sed 's/ $//')"
  t_equals report_merges_driver_cluster_and_plugin_rows "check:cluster driver:example\
 plugin:example" "$(awk -F '\t' 'NR > 1 && ($4 == "driver:example" || $4 == "check:cluster" ||
    $4 == "plugin:example") { print $4 }' "$example/report/verdicts.tsv" | sort -u | tr '\n' ' ' |
    sed 's/ $//')"
  t_contains example_dataset_reaches_client_0_through_the_copy \
    "data/example.tsv client:/tmp/px/${example##*/}/example.tsv" \
    "$(cat "$T_SHIM_LOG")"
  t_contains example_removes_the_copy_after_the_run "rm -f /tmp/px/${example##*/}/example.tsv" \
    "$(cat "$T_SHIM_LOG")"
  copy="$(t_bundle_copy "$example" norows)"
  rm -f "$copy/drivers/example/rows.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals absent_declared_file_is_a_report_skip "0 yes" "$T_STATUS $(t_flag \
    "artifact.drivers/example/rows.tsv${PX_TAB}SKIP" "$(cat "$copy/report/verdicts.tsv")")"

  # Verify requirements RQ-123 (b) and (c): the configuration overlay property is rendered into the
  # instance's hbase-site.xml alongside all base properties, and the plug-in's client JVM option is
  # applied exclusively to the client role.
  t_gen --class validation --cluster overlay -e PX_PLUGINS=example
  deploy="$T_A2_OUT/deploy/overlay"
  t_render "$deploy" client-0 "PX_CONF_OVERLAY_DIR=$deploy/conf-overlay"
  got="$(cat "$T_A2/render/hbase-site.xml" 2>/dev/null)"
  t_equals overlay_key_reaches_the_rendered_hbase_site "0 yes yes 1" "$T_STATUS $(t_flag \
    '<name>px.example.instance</name>' "$got") $(t_flag '<value>client-0</value>' "$got")\
 $(printf '%s\n' "$got" | grep -c '</configuration>')"
  t_contains overlay_keeps_the_base_properties '<name>hbase.cluster.distributed</name>' "$got"
  t_equals plugin_jvm_argument_reaches_only_the_client client-0 "$(awk '
    /^  [a-z][a-z0-9-]*:$/ { s = $1; sub(/:$/, "", s) }
    /-Dpx\.example/ && s != "" { print s }' "$deploy/compose.yaml" | sort -u | tr '\n' ' ' |
    sed 's/ $//')"

  # Verify requirement RQ-104: no shipped driver invokes any sb_* contract function outside the
  # three permitted by boundary G4, and the scanner detects a rogue fixture driver calling sb_kill.
  t_empty drivers_call_only_exec_and_copy "$(t_driver_sb_calls "$PX_HOME")"
  mkdir -p "$T_WORK/scan3/drivers/rogue"
  cat >"$T_WORK/scan3/drivers/rogue/driver.sh" <<'A5_EOF'
#!/usr/bin/env bash
sb_kill "$PX_SCOPE_ID" regionserver 0 hard
A5_EOF
  t_contains driver_scan_finds_another_sb_call 'rogue/driver.sh:2:sb_kill' \
    "$(t_driver_sb_calls "$T_WORK/scan3")"

  # Verify requirements RQ-100, RQ-104, RQ-105, RQ-106, and RQ-115: execute the psql driver across
  # all six lifecycle verbs against the fake cluster and inspect its runtime call log, metric
  # records, and bundle manifest.
  printf '%s\n' 'CREATE TABLE T1 (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR)' \
    '  UPDATE_CACHE_FREQUENCY=60000;' >"$T_A5/sql/schema.sql"
  t_a5_state_reset
  t_shim_log_reset
  t_a5 "$T_BASH" "$T_BIN/px-run" --class validation -e PX_SCOPE_ID=px-selftest \
    psql -- --prepare "$T_A5/sql/schema.sql" "$T_A5/sql/fixture.sql"
  psql="$(t_bundle)"
  t_equals psql_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$psql")"
  t_equals psql_run_records_the_five_manifest_fields \
    "validation compose sha256:f00dfeed00a5 T1 60000 array" "$(t_manifest "$psql" /run/class)\
 $(t_manifest "$psql" /run/backend) $(t_manifest "$psql" /images/0/image_id) $(t_manifest \
    "$psql" /tables/0/table) $(t_manifest "$psql" /tables/0/update_cache_frequency) $(t_lib \
    t_a5_json_type "$psql/manifest.json" /quiet_host/tags; printf '%s' "$T_STDOUT")"
  t_equals psql_bundle_validates 0 "$(t_validate "$psql" >/dev/null; printf '%s' "$T_STATUS")"
  rows="$(cat "$psql/metrics/aggregate.tsv")"
  t_contains psql_aggregate_holds_the_rows_of_statement_one \
    "statement@1.1${PX_TAB}psql.statement_rows${PX_TAB}count${PX_TAB}3$PX_TAB" "$rows"
  t_contains psql_aggregate_holds_the_rows_of_statement_two \
    "statement@1.2${PX_TAB}psql.statement_rows${PX_TAB}count${PX_TAB}2$PX_TAB" "$rows"
  t_contains psql_aggregate_holds_the_duration_of_the_script \
    "script@1${PX_TAB}psql.file_duration_ms${PX_TAB}duration$PX_TAB" "$rows"
  # Exercise the prepare, run, and restore verbs of the psql driver in isolation and inspect the
  # fake runtime log. Each runtime invocation begins with a compose line followed by the shell
  # script lines passed to that invocation.
  run="$(t_a5_bundle)"
  t_shim_log_reset
  for line in prepare run restore; do
    t_a5_verb psql "$line" "$run" --prepare "$T_A5/sql/schema.sql" "$T_A5/sql/fixture.sql"
  done
  t_empty psql_calls_reach_only_client_0 "$(awk '
    /^compose .* exec / && !/ exec -T --index=1 client / { print }
    /^compose .* cp / && !/ client:\/tmp\/px\// { print }' "$T_SHIM_LOG")"
  t_contains psql_calls_exec_and_copy_in_client_0 ' cp --index=1 ' "$(cat "$T_SHIM_LOG")"
  t_empty psql_and_its_hooks_start_no_python_launcher "$(grep -E '\.py( |$)|python' \
    "$T_SHIM_LOG")"
  t_equals psql_java_runs_phoenix_runtime_with_the_class_path yes "$(awk '
    /org\.apache\.phoenix\.util\.PhoenixRuntime/ { n++; if (index($0, "-cp ")) c++ }
    END { print (n > 0 && n == c) ? "yes" : "no" }' "$T_A5_STATE/java.log")"
  # Verify requirements RQ-096 and RQ-118: cluster checks execute only after both the run verb and
  # the report verb have completed.
  t_equals cluster_checks_run_after_the_run_verb yes "$(awk -F '\t' '
    $1 == "driver.run" { r = NR } $1 == "driver.report" { p = NR }
    $4 == "check:cluster" && !c { c = NR }
    END { print (r && p && c > r && c > p) ? "yes" : "no" }' "$psql/verdicts.tsv")"

  # Verify requirements RQ-099, RQ-100, RQ-105, and RQ-107: executing the pherf driver across all
  # six verbs against the fake cluster confines all Pherf output to the run directory and leaves no
  # relative RESULTS directory behind.
  t_a5_state_reset
  t_shim_log_reset
  t_a5 "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$T_BIN/px-run" \
    --class validation -e PX_SCOPE_ID=px-selftest \
    pherf -- --scenario "$PX_HOME/suites/pherf/px_functional_scenario.xml" \
    --schema "$PX_HOME/suites/pherf/px_functional_schema.sql"
  pherf="$(t_bundle)"
  t_equals pherf_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$pherf")"
  t_contains pherf_puts_the_phoenix_client_jars_on_the_class_path ":$T_A5/phoenix/client/*:" \
    ":$(t_java_cp org.apache.phoenix.pherf.Pherf):"
  # Verify A7: JDK 11 removed JAXB, which Pherf requires and HBase ships in lib/jdk11, and the
  # commons-csv library bundled in the Pherf JAR requires the commons-codec JAR from HBase.
  t_contains pherf_puts_the_jaxb_jars_of_hbase_on_the_class_path ":$T_A5/hbase/lib/jdk11/*:" \
    ":$(t_java_cp org.apache.phoenix.pherf.Pherf):"
  t_contains pherf_puts_the_codec_jar_of_hbase_on_the_class_path \
    ":$T_A5/hbase/lib/commons-codec-1.15.jar:" ":$(t_java_cp org.apache.phoenix.pherf.Pherf):"
  t_equals pherf_result_paths_are_in_the_run_work_directory "/tmp/px/${pherf##*/}/results\
 /tmp/px/${pherf##*/}/results/summary.html" "$(sed -n \
    's/^pherf\.default\.results\.dir=//p' "$pherf/drivers/pherf/stage/pherf.properties")\
 $(sed -n 's/^pherf\.default\.summary\.file=//p' "$pherf/drivers/pherf/stage/pherf.properties")"
  t_equals pherf_results_are_copied_into_the_bundle yes \
    "$(t_exists "$pherf/drivers/pherf/results/RESULT_px_functional_aggregate.csv")"
  t_contains pherf_run_aggregates_the_csv_group \
    "group@serial${PX_TAB}pherf.run_count${PX_TAB}count${PX_TAB}5$PX_TAB" \
    "$(cat "$pherf/metrics/aggregate.tsv")"
  t_equals pherf_bundle_validates 0 "$(t_validate "$pherf" >/dev/null; printf '%s' "$T_STATUS")"
  t_equals pherf_java_runs_the_main_class_with_the_staged_class_path yes "$(awk '
    /org\.apache\.phoenix\.pherf\.Pherf/ { n++; if (index($0, "-cp ") && index($0, "/stage:")) c++ }
    END { print (n > 0 && n == c) ? "yes" : "no" }' "$T_A5_STATE/java.log")"
  t_empty pherf_starts_no_python_launcher "$(grep -E '\.py( |$)|python' "$T_SHIM_LOG")"
  # Verify requirements RQ-207 and RQ-208: execute the same full-lifecycle test against the new
  # Pherf outputs.
  t_h2_hdr_run
  # Verify requirements RQ-100 and RQ-105: execute the java-main driver across every lifecycle verb
  # with the expected classpath.
  t_a5_state_reset
  t_shim_log_reset
  t_a5 "$T_BASH" "$T_BIN/px-run" --class validation -e PX_SCOPE_ID=px-selftest \
    java-main -- org.example.Fixture
  run="$(t_bundle)"
  t_equals java_main_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$run")"
  t_equals java_main_runs_the_class_with_the_class_path yes "$(awk '
    /org\.example\.Fixture/ { n++; if (index($0, "-cp ")) c++ }
    END { print (n > 0 && n == c) ? "yes" : "no" }' "$T_A5_STATE/java.log")"
  t_empty java_main_starts_no_python_launcher "$(grep -E '\.py( |$)|python' "$T_SHIM_LOG")"
  # Verify requirement RQ-084: every metric record emitted by any run in this test group includes
  # both the run class and contamination tags.
  got=""
  for run in "$base" "$sized" "$example" "$psql" "$pherf" "$T_H2_HDR" "$run"; do
    got="$got $(t_metric_gaps "$run")"
  done
  t_equals every_metric_record_of_a_run_carries_both_tags " 0 0 0 0 0 0 0" "$got"

  # Verify requirement RQ-090: a driver exit code of 3 maps to verdict severity ERROR and run exit
  # code 3, whereas a driver exit code of 1 maps to verdict severity FAIL and run exit code 1.
  got=""
  for line in 3 1; do
    t_a5_state_reset
    t_a5_run exit-code -- "$line"
    got="$got $T_STATUS:$(t_severity_of "$(t_bundle)" driver.run)"
  done
  t_equals driver_codes_map_onto_the_lattice " 3:ERROR 1:FAIL" "$got"

  # Verify requirement RQ-092: both px-bundle validate and px-report reject the non-contract
  # severity WARN and name the offending severity string in their output.
  copy="$(t_bundle_copy "$base" warn)"
  printf 'odd\tWARN\tnone\taction\tx\n' >>"$copy/verdicts.tsv"
  t_validate "$copy"
  t_equals bundle_refuses_an_unknown_severity_by_name "1 fail yes" "$T_STATUS $(t_check_status \
    "$T_STDOUT" verdicts) $(t_flag 'severity "WARN"' "$T_STDOUT")"
  t_report validation "" --verdict "$copy/verdicts.tsv"
  t_equals report_refuses_an_unknown_severity_by_name "3 yes" \
    "$T_STATUS $(t_flag 'unknown severity "WARN"' "$T_STDOUT")"
  # Verify requirement RQ-102: a driver describe document that is not valid JSON is rejected during
  # bundle validation.
  copy="$(t_bundle_copy "$base" notjson)"
  printf 'not json\n' >"$copy/drivers/phase-kill/describe.json"
  t_validate "$copy"
  t_equals bundle_refuses_a_describe_that_is_not_json "1 fail" \
    "$T_STATUS $(t_check_status "$T_STDOUT" describe)"
  # Verify requirement RQ-110: px-bundle validate can validate a run bundle from a harness tree
  # that has no drivers/ directory installed.
  rm -rf "$T_A5/nodrivers"
  mkdir -p "$T_A5/nodrivers"
  cp -R "$PX_HOME/bin" "$PX_HOME/lib" "$PX_HOME/suites" "$PX_HOME/docker" "$T_A5/nodrivers/"
  t_run "$T_BASH" "$T_A5/nodrivers/bin/px-bundle" validate "$base"
  t_equals bundle_validates_with_no_driver_present "0 no" \
    "$T_STATUS $(t_exists "$T_A5/nodrivers/drivers")"
  # Verify requirement RQ-086: executing a two-step suite produces two distinct run bundles under
  # PX_OUT/runs, each containing its own driver artifacts and merged verdict report.
  printf 'suite: two\nclass: validation\n\ntest: one\n  driver: %s\n  args: 0\n' \
    "$T_WORK/drivers/exit-code.sh" >"$T_A5/two.suite"
  printf '\ntest: two\n  driver: %s\n  args: 0\n' "$T_WORK/drivers/exit-code.sh" \
    >>"$T_A5/two.suite"
  t_a5_state_reset
  t_a5 PX_SESSION_ID=a5-two PX_SCOPE_ID=px-selftest "$T_BASH" "$T_BIN/px-suite" \
    "$T_A5/two.suite"
  one="$(sed -n 1p "$PX_OUT/sessions/a5-two/tests/one/stdout.txt" 2>/dev/null)"
  two="$(sed -n 1p "$PX_OUT/sessions/a5-two/tests/two/stdout.txt" 2>/dev/null)"
  t_equals two_steps_give_two_bundles "0 yes" "$T_STATUS $([ -n "$one" ] && [ -n "$two" ] &&
    [ "$one" != "$two" ] && [ "${one%/*}" = "$PX_OUT/runs" ] && [ "${two%/*}" = "$PX_OUT/runs" ] &&
    echo yes || echo no)"
  t_equals each_step_bundle_holds_its_artifacts_and_report "yes yes yes yes" \
    "$(t_exists "$one/drivers/exit-code/describe.json") $(t_exists "$one/report/verdicts.tsv")\
 $(t_exists "$two/drivers/exit-code/describe.json") $(t_exists "$two/report/verdicts.tsv")"

  # Verify requirement A5-REQ-01: waiting for region recovery after a disruption inside the run
  # verb is capped by the shared prepare-and-run timeout bound, causing the run to terminate with
  # timeout code 4 near that shared bound.
  t_a5_state_reset
  printf '%s\n' 12 8 >"$T_A5_STATE/region-count"
  t_a5_run phase-kill --timeout 3 -e PX_TIMEOUT_READY=8 \
    --disrupt 'phase:phase@kill,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals region_wait_ends_at_the_shared_bound "4 1 timeout yes" "$T_STATUS $(awk -F '\t' \
    '$1 == "verb" && $2 == "run" { print $5 }' "$run/run-record.tsv") $(awk -F '\t' \
    '$1 == "recover" { print $5 }' "$run/run-record.tsv") $(awk -F '\t' \
    '$1 == "verb" && $2 == "run" { print ($4 < 6000) ? "yes" : "no" }' "$run/run-record.tsv")"
  t_equals bounded_recovery_bundle_validates 0 "$(t_validate "$run" >/dev/null; printf '%s' \
    "$T_STATUS")"
  t_a5_state_reset
}


# ---------------------------------------------------------------------------
# A6: Measurement toolkit verification
# ---------------------------------------------------------------------------
# Every test case in this section executes against synthetic fakes: a four-instance fake runtime,
# a fake pgrep that discovers a JVM only in each RegionServer, fake shims for async-profiler,
# jcmd, java, and the jHiccup jar, a fake trace counter reader, and the hdr.sh stub driver whose
# run verb emits an HDR histogram log. Test cases referenced by requirement specifications retain
# their required identifiers.

## @description  Materializes the A6 test fixtures, including fake collector tools, fake JDKs,
##               the hdr.sh stub driver, and the four-instance cluster deployment.
## @return       Populates the A6 fixture directory. Exit 0.
t_fixtures_a6() {
  T_A6="$T_WORK/a6"
  T_A6_STATE="$T_A6/state"
  T_A6_OUT="$T_A6/out"
  mkdir -p "$T_A6/bin" "$T_A6/tools/async-profiler/bin" "$T_A6/tools/jhiccup" \
    "$T_A6/jdk/17/bin" "$T_A6/jdk-nojcmd/17/bin" "$T_A6/deploy" "$T_A6_STATE" "$T_A6_OUT"
  # The fake runtime executes container commands on the host and exports the target service in
  # PX_FAKE_SERVICE. Only RegionServer instances report a running JVM process.
  cat >"$T_A6/bin/pgrep" <<'A6_EOF'
#!/usr/bin/env bash
case "${PX_FAKE_SERVICE:-}" in
regionserver*) printf '4242\n' ;;
*) exit 1 ;;
esac
A6_EOF
  # Fake trace counter reader for client 0. Each invocation increments every counter by 10, a
  # flag file simulates a missing reader binary, and a second flag causes the second invocation
  # (the stop call) to record its start marker and sleep for two seconds.
  cat >"$T_A6/bin/px-trace-counters" <<'A6_EOF'
#!/usr/bin/env bash
[ ! -e "$PX_FAKE_STATE_DIR/no-trace-reader" ] || exit 127
calls=$(($(cat "$PX_FAKE_STATE_DIR/trace-calls" 2>/dev/null || echo 0) + 1))
printf '%s\n' "$calls" >"$PX_FAKE_STATE_DIR/trace-calls"
if [ "$calls" = 2 ] && [ -e "$PX_FAKE_STATE_DIR/slow-trace-stop" ]; then
  : >"$PX_FAKE_STATE_DIR/trace-stopping"
  sleep 2
fi
printf 'regionserver\t0\tServer\ttotalRequestCount\t%s\n' "$((calls * 10))"
printf 'regionserver\t1\tPhoenixIndexer\tpreBatchMutate_num_ops\t%s\n' "$((calls * 10))"
A6_EOF
  # Fake async-profiler shim that logs the target service and argument list; on start, it writes
  # the event list from the -e option into the output recording file.
  cat >"$T_A6/tools/async-profiler/bin/asprof" <<'A6_EOF'
#!/usr/bin/env bash
printf '%s %s\n' "${PX_FAKE_SERVICE:-}" "$*" >>"$PX_FAKE_STATE_DIR/asprof.log"
file="" events="" prev=""
for arg in "$@"; do
  [ "$prev" != -f ] || file=$arg
  [ "$prev" != -e ] || events=$arg
  prev=$arg
done
[ "${1:-}" != start ] || [ -z "$file" ] || printf '%s\n' "$events" >"$file"
A6_EOF
  printf 'fake jHiccup\n' >"$T_A6/tools/jhiccup/jHiccup.jar"
  # Fake JDK binaries. The java shim logs its arguments; in attacher mode it writes the target
  # histogram log, and in control-JVM mode it writes its log, records its PID, and stays alive.
  cat >"$T_A6/jdk/17/bin/java" <<'A6_EOF'
#!/usr/bin/env bash
printf '%s %s\n' "${PX_FAKE_SERVICE:-}" "$*" >>"$PX_FAKE_STATE_DIR/java.log"
log="" prev="" mode=""
for arg in "$@"; do
  [ "$prev" != -l ] || log=$arg
  case "$arg" in
  org.jhiccup.HiccupMeterAttacher) mode=attach ;;
  -jar) mode=control ;;
  esac
  prev=$arg
done
[ -z "$log" ] || printf '#[Histogram log format version 1.3]\n' >"$log"
if [ "$mode" = control ]; then
  printf '%s\n' "$$" >"$PX_FAKE_STATE_DIR/control.pid"
  exec sleep 30
fi
A6_EOF
  # Materialize the fake jcmd shim: JFR.start creates an empty target file and JFR.stop writes the
  # recording content, matching JVM behavior. When the jcmd-refuses flag file is present, the JVM
  # has lost the recording, so JFR.stop writes nothing while jcmd still exits with status 0.
  cat >"$T_A6/jdk/17/bin/jcmd" <<'A6_EOF'
#!/usr/bin/env bash
printf '%s %s\n' "${PX_FAKE_SERVICE:-}" "$*" >>"$PX_FAKE_STATE_DIR/jcmd.log"
file="" stop=0
for arg in "$@"; do
  case "$arg" in
  filename=*) file=${arg#filename=} ;;
  JFR.stop) stop=1 ;;
  esac
done
[ -n "$file" ] || exit 0
if [ "$stop" = 0 ]; then
  : >"$file"
elif [ -e "$PX_FAKE_STATE_DIR/jcmd-refuses" ]; then
  printf 'Could not write recording to file. Could not find the recording.\n'
else
  printf 'jfr recording\n' >"$file"
fi
A6_EOF
  cp "$T_A6/jdk/17/bin/java" "$T_A6/jdk-nojcmd/17/bin/java"
  chmod +x "$T_A6/bin"/* "$T_A6/tools/async-profiler/bin/asprof" "$T_A6/jdk/17/bin"/* \
    "$T_A6/jdk-nojcmd/17/bin/java"
  # Stub workload driver whose run verb writes an HDR log inside the steady@stub window and holds
  # that window open for the requested duration in seconds. When passed regress, it also records
  # both a counter regression and a latency regression.
  cat >"$T_WORK/drivers/hdr.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
case "${1:-}" in
describe)
  printf '{"contract": 1, "driver": "%s",\n' "${PX_DRIVER_NAME:-hdr}"
  printf ' "phases": [{"anchor": "steady@stub", "verb": "run"}],\n'
  printf ' "metrics": {"count": [], "share": [], "duration": []},\n'
  printf ' "unsupported": {"checks": [\n'
  printf '  {"key": "query_latency.p50_ms", "reason": "a stub"},\n'
  printf '  {"key": "query_latency.p99_ms", "reason": "a stub"},\n'
  printf '  {"key": "query_errors", "reason": "a stub"},\n'
  printf '  {"key": "offered_rate", "reason": "a stub"},\n'
  printf '  {"key": "achieved_qps", "reason": "a stub"}],\n'
  printf '  "artifacts": []},\n'
  printf ' "artifacts": [{"path": "drivers/hdr/run.hlog"}]}\n'
  exit 0
  ;;
validate | prepare | restore | report) exit 0 ;;
run) shift ;;
*) exit 2 ;;
esac
. "$PX_HOME/lib/bundle.sh"
hdr_sleep=1
hdr_regress=0
for hdr_arg in "$@"; do
  case "$hdr_arg" in
  regress) hdr_regress=1 ;;
  *) hdr_sleep=$hdr_arg ;;
  esac
done
pb_stage begin steady@stub
printf '#[Histogram log format version 1.3]\n' >"$PX_DRIVER_DIR/run.hlog"
sleep "$hdr_sleep"
pb_stage end steady@stub
if [ "$hdr_regress" = 1 ]; then
  sx_verdict_row stub.counter_regression FAIL count "the scans rose from 10 to 12"
  sx_verdict_row stub.latency_regression FAIL duration "the p99 rose from 5 ms to 9 ms"
fi
DRIVER_EOF
  chmod +x "$T_WORK/drivers/hdr.sh"
  printf '%s\n' "# Rendered by compose.gen.sh. Render again instead of editing." \
    PX_CLUSTER_ID=selftest PX_RS_COUNT=2 >"$T_A6/deploy/topology.env"
  {
    printf '%s\n' "# service role index group start mem_limit_mib heap_mib direct_mib jdk"
    t_rows 9 regionserver-0 regionserver 0 '' '' 2560 1792 256 17 \
      regionserver-1 regionserver 1 '' '' 2560 1792 256 17 \
      client-0 client 0 '' '' 1536 1024 128 17 tooling-0 tooling 0 '' '' 512 256 64 17
  } >"$T_A6/deploy/services.tsv"
  printf 'PX_PORT_REGIONSERVER_RPC_0=16020\n' >"$T_A6/deploy/ports.generated.env"
  # Default environment variables for all A6 commands and base CLI options for px-run invocations.
  T_A6_ENV=("PATH=$T_A6/bin:$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker"
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_A6_STATE" "PX_OUT=$T_A6_OUT"
    "PX_TOOLS_DIR=$T_A6/tools" "PX_JDK_ROOT=$T_A6/jdk" PX_COLLECT_INTERVAL_S=1
    PX_POLL_INTERVAL_S=0.2 PX_CLUSTER_ID=selftest)
  T_A6_RUN=(-e PX_SCOPE_ID=px-selftest -e PX_REQUIRE_QUIET_HOST=0
    -e PX_QUIET_HOST_WAIT_S=0 "$T_WORK/drivers/hdr.sh")
  t_a6_state_reset
}

## @description  Resets the A6 fake cluster to running regionserver 0 and 1, client 0, and
##               tooling 0 instances under scope px-selftest and cluster selftest.
## @return       Reinitializes the A6 fake cluster state. Exit 0.
t_a6_state_reset() {
  local line n=0
  rm -rf "${T_A6_STATE:?}"/*
  for line in regionserver:0 regionserver:1 client:0 tooling:0; do
    n=$((n + 1))
    printf 'c0ffee00a60%s|%s|px-selftest-%s-%s-1|running|Up 3 minutes (healthy)|%s|%s|1|%s|||\n' \
      "$n" "${line%:*}" "${line%:*}" "${line#*:}" phoenix-harness:selftest \
      "${line%:*}-${line#*:}" "${line#*:}"
  done >"$T_A6_STATE/instances.txt"
  printf '0\n' >"$T_A6_STATE/restart-count"
  printf 'sha256:f00dfeed00a6\n' >"$T_A6_STATE/image-id"
  rm -f "$T_A6_OUT"/state/compose/starts-* "$T_A6_OUT/state/collect-selftest.tsv"
  mkdir -p "$T_A6_OUT/state"
  printf 'px-selftest\n' >"$T_A6_OUT/state/scope-compose-selftest"
  printf '%s\n' "$T_A6/deploy" >"$T_A6_OUT/state/deployment-compose-selftest"
}

## @description  Executes a command against the A6 fake cluster with the fake tools first on PATH
##               and PX_COLLECTORS unset so the run class determines active collectors.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a6() {
  t_run env "${T_A6_ENV[@]}" "$@"
}

## @description  Executes px-run with hdr.sh against the A6 fake cluster (class, option...);
##               measurement runs require no quiet host, and the cluster remains running.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_a6_run() {
  local class=$1
  shift
  t_a6 "$T_BASH" "$T_BIN/px-run" --class "$class" "${T_A6_RUN[@]}" "$@"
}

## @description  Extracts unique runtime verbs from a fake runtime log (file), emitting
##               compose:<verb> for compose calls or the first word while skipping remote scripts.
## @return       Writes the sorted verbs, one per line, to stdout. Exit 0.
t_runtime_verbs() {
  awk '
    skip { if ($0 ~ / px-(collect|fetch)( |$)/) skip = 0; next }
    $1 == "compose" && $2 == "-p" { i = 4; if ($i == "-f") i += 4; print "compose:" $i }
    $1 == "compose" && $2 != "-p" { print "compose:" $2 }
    $1 != "compose" { print $1 }
    / bash -c / && $0 !~ / px-(collect|fetch)( |$)/ { skip = 1 }' "$1" | LC_ALL=C sort -u
}

## @description  Lists the collector stage anchors recorded in a run bundle (bundle), sorted and
##               joined by spaces.
## @return       Writes the space-separated collector anchors to stdout. Exit 0.
t_collector_anchors() {
  awk -F '\t' '$2 == "begin" && $3 ~ /^collector@/ { print $3 }' "$1/stages.tsv" |
    LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//'
}

## @description  Extracts the first time window (bundle, anchor) from stages.tsv as tab-separated
##               start_ms and end_ms values, or two empty fields when no closed window exists.
## @return       Writes the tab-separated window timestamps to stdout. Exit 0.
t_a6_window() {
  awk -F '\t' -v a="$2" '$3 == a && $2 == "begin" && b == "" { b = $1 }
    $3 == a && $2 == "end" && b != "" && e == "" { e = $1 } END { print b "\t" e }' \
    "$1/stages.tsv"
}

# Because a collection executes each collector at most once, this helper duplicates a bundle under
# the A6 output root and strips the collector index, the collector stage rows, collect/started.tsv,
# and the target artifact subdirectory.
## @description  Copies a run bundle (source, case, directory) to <A6 out>/copies/<case>/<run-id>
##               while omitting the collector index, collector stage rows, and specified directory.
## @return       Writes the copied bundle path to stdout. Exit 0.
t_a6_copy() {
  local target="$T_A6_OUT/copies/$2/${1##*/}"
  rm -rf "$target"
  mkdir -p "${target%/*}"
  cp -R "$1" "$target"
  rm -rf "$target/metrics/index.tsv" "$target/collect" "${target:?}/$3"
  awk -F '\t' '$3 !~ /^collector@/' "$1/stages.tsv" >"$target/stages.tsv"
  printf '%s\n' "$target"
}

## @description  Executes px-collect start then stop for one collector (name, bundle, env...) with
##               its env pairs in scope px-selftest, passing --duration 60 for profile and jfr.
## @return       Sets T_CYCLE to the two exit statuses formatted as "start stop". Exit 0.
t_a6_cycle() {
  local name=$1 run=$2 got dur=()
  shift 2
  [ "$name" = trace ] || dur=(--duration 60)
  t_a6 "$@" "$T_BASH" "$T_BIN/px-collect" start "$run" --collect "$name" --class validation \
    -e PX_SCOPE_ID=px-selftest ${dur[@]+"${dur[@]}"}
  got=$T_STATUS
  t_a6 "$@" "$T_BASH" "$T_BIN/px-collect" stop "$run" -e PX_SCOPE_ID=px-selftest
  T_CYCLE="$got $T_STATUS"
}

## @description  Lists all collection directories under the A6 output root, one directory per line.
## @return       Writes the sorted directory paths to stdout. Exit 0.
t_a6_collections() {
  find "$T_A6_OUT/collect" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | LC_ALL=C sort
}

## @description  Determines whether a collection process (pid) is still alive.
## @return       Writes yes when the process is alive, or no otherwise, to stdout. Exit 0.
t_a6_alive() { kill -s 0 "${1:-x}" 2>/dev/null && echo yes || echo no; }

## @description  Extracts the sampler pid for a collector (bundle, collector) from
##               collect/started.tsv.
## @return       Writes the pid to stdout, or nothing if not found. Exit 0.
t_a6_sampler_pid() {
  awk -F '\t' -v c="$2" '$1 == c { p = $3 } END { print p }' "$1/collect/started.tsv" 2>/dev/null
}

# Confirms that a time-series TSV file starts with a ts_ms header, holds at least 3 sample rows,
# maintains inter-sample intervals of 500 to 2000 ms (except for the final stop-triggered sample),
# and spans the target window with its first row at or before begin_ms and last at or after end_ms.
## @description  Validates the header, row count, sampling cadence, and window coverage of a
##               time-series sample TSV file (file, begin ms, end ms).
## @return       Writes yes when valid, or the detected defects, to stdout. Exit 0.
t_a6_series() {
  awk -F '\t' -v b="$2" -v e="$3" 'NR == 1 { if ($1 != "ts_ms") bad = bad " header"; next }
    { ts[++n] = $1 }
    END { if (n < 3) bad = bad " rows=" n
      for (i = 2; i <= n; i++) { gap = ts[i] - ts[i - 1]
        if (gap > 2000 || gap < 0 || (i < n && gap < 500)) bad = bad " gap" i "=" gap }
      if (n == 0 || ts[1] > b) bad = bad " first=" ts[1] ">" b
      if (n == 0 || ts[n] < e) bad = bad " last=" ts[n] "<" e
      print (bad == "" ? "yes" : substr(bad, 2)) }' "$1" 2>/dev/null || printf 'no file\n'
}

## @description  Queries the manifest artifact inventory of a bundle (bundle, path) for the
##               entry's status and required flag.
## @return       Writes "status required" to stdout, or nothing when the path is absent. Exit 0.
t_inventory() {
  t_lib t_a5_json_rows "$1/manifest.json" /artifacts path status required
  printf '%s\n' "$T_STDOUT" | awk -F '\t' -v p="$2" '$1 == p { print $2 " " $3 }'
}

## @description  Lists any processes that outlive a run (run dir), matching processes whose
##               arguments contain the run id as well as any live fake jHiccup control JVM.
## @return       Writes one line per lingering process to stdout. Exit 0.
t_a6_leftovers() {
  local pid=""
  ps -A -o args= 2>/dev/null | awk -v id="${1##*/}" 'index($0, id) && $1 != "awk"' || true
  pid="$(cat "$T_A6_STATE/control.pid" 2>/dev/null)" || pid=""
  [ -z "$pid" ] || ! kill -s 0 "$pid" 2>/dev/null || printf 'control JVM %s\n' "$pid"
}

## @description  Verifies the run-class collector defaults and opt-in flags (RQ-127) together with
##               the five-RegionServer topology recipe in override-24gib.env (RQ-135).
## @return       Records assertions. Exit 0.
t_group_a6_classes() {
  local run name bad="" got="" five="$T_A6/override-5rs.env" line
  printf '# A6: the class defaults of the collectors\n'
  # RQ-127: By default, a validation run launches no background collectors while still preserving
  # the workload driver's HDR histogram log in the run bundle.
  t_a6_state_reset
  t_shim_log_reset
  t_a6_run validation
  run="$(t_bundle)"
  T_A6_BASE=$run
  t_equals validation_run_starts_no_collector "0||no|no|no|no||||" "$T_STATUS|$(
    t_collector_anchors "$run")|$(t_exists "$run/metrics/index.tsv")|$(t_exists \
      "$run/metrics/host.tsv")|$(t_exists "$run/metrics/container")|$(t_exists \
      "$run/logs/px-collect.log")|$(cat "$T_A6_STATE/asprof.log" 2>/dev/null)|$(cat \
      "$T_A6_STATE/jcmd.log" 2>/dev/null)|$(cat "$T_A6_STATE/java.log" 2>/dev/null)|$(cat \
      "$T_A6_STATE/trace-calls" 2>/dev/null)"
  got="$(t_inventory "$run" drivers/hdr/run.hlog)"
  # RQ-127: Each individual collector opt-in flag activates only that requested collector.
  for line in profile:profiles/regionserver-0.jfr jfr:jfr/regionserver-0.jfr \
    hiccup:metrics/hiccup/control.hlog host:metrics/host.tsv; do
    name=${line%%:*}
    t_a6_state_reset
    t_a6_run validation --collect "$name"
    run="$(t_bundle)"
    [ "$T_STATUS $(t_collector_anchors "$run")" = "0 collector@$name" ] ||
      bad="$bad $name:$T_STATUS:$(t_collector_anchors "$run")"
    [ -s "$run/${line#*:}" ] || bad="$bad $name:no-${line#*:}"
    [ "$(t_inventory "$run" "${line#*:}")" = "present true" ] || bad="$bad $name:not-listed"
    got="$got,$(t_inventory "$run" drivers/hdr/run.hlog)"
  done
  t_empty validation_opt_in_starts_each_collector "$bad"
  t_equals hdr_output_stays_in_both_cases \
    "present false,present false,present false,present false,present false" "$got"
  # The run-class configuration files define the default collector sets, as reported by a dry run.
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation
  got="$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "collectors" { print $3 }')"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class measurement
  got="$got|$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "collectors" { print $3 }')"
  t_equals class_files_set_the_collector_defaults \
    "none|profile jfr hiccup host container trace" "$got"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --collect gc
  got=$T_STATUS
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --collect none,host
  t_equals unknown_collector_is_a_usage_error "2 2" "$got $T_STATUS"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --timeout 7
  got="$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "driver_timeout_s" { print $3 }')"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation
  t_equals run_timeout_sets_px_timeout_run "7 3600" "$got $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$2 == "driver_timeout_s" { print $3 }')"
  # Verify that diagnostic messages for invalid flag values name the option supplied by the
  # operator.
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --timeout 0
  got="$T_STATUS $(t_flag "--timeout needs a whole number of at least 1, got '0'" "$T_STDERR")"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --collect host,bogus
  t_equals run_errors_name_the_option "2 yes 2 yes" "$got $T_STATUS $(t_flag \
    "--collect: 'bogus' is no collector" "$T_STDERR")"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --collect host,trace --targets regionserver --timeout 60 -e PX_TIMEOUT_VERB=10
  t_equals run_dry_run_plans_the_collection "regionserver 70" "$(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$2 == "collect_targets" { t = $3 } $2 == "collect_bound_s" { b = $3 }
      END { print t " " b }')"
  t_a6 "$T_BASH" "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run --class validation \
    --strict -e PX_COLLECT_DURATION_S=1
  t_equals px_collect_duration_s_is_undeclared "2 yes" "$T_STATUS $(t_flag \
    "undeclared name 'PX_COLLECT_DURATION_S'" "$T_STDERR")"
  # RQ-135: The five-RegionServer topology recipe is shipped commented out; when uncommented, it
  # renders a 19328 MiB footprint that the reference engine admits with 577 MiB to spare.
  bad=""
  for line in PX_RS_COUNT=5 PX_YARN=0 PX_RS_MEM_MIB=2432 PX_RS_HEAP_MIB=1664 \
    PX_RS_DIRECT_MIB=256 PX_CLIENT_MEM_MIB=1536 PX_CLIENT_HEAP_MIB=1024 \
    PX_CLIENT_DIRECT_MIB=128; do
    grep -qx "# $line" "$T_ENVD/override-24gib.env" || bad="$bad $line"
  done
  t_empty override_24gib_ships_the_5rs_variant_commented "$bad"
  sed 's/^# \(PX_[A-Z0-9_]*=[0-9]*\)$/\1/' "$T_ENVD/override-24gib.env" >"$five"
  t_gen --class measurement --config "$five" --check
  t_equals uncommented_5rs_variant_renders_19328 "0 5 19328" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c '^regionserver-') $(t_mem_sum "$T_STDOUT")"
  t_gen --class measurement --cluster five --config "$five"
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=five "$T_BASH" "$PX_HOME/scripts/preflight.sh" --phase memory
  t_equals uncommented_5rs_variant_is_admitted_with_577_spare "0 yes yes" "$T_STATUS $(t_flag \
    '19328 MiB (18.88 GiB)' "$T_STDOUT") $(t_flag '577 MiB (0.56 GiB) to spare' "$T_STDOUT")"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

# Exercises the session collectors through px-collect to verify async-profiler invocation flags
# (RQ-129), JFR and trace file generation with SKIP handling (RQ-130), and exec-only cluster access
# (RQ-128).
## @description  Verifies session collector execution and cluster access boundaries via px-collect.
## @return       Records assertions. Exit 0.
t_group_a6_tools() {
  local run copy bad="" verbs line tool got
  printf '# A6: the collector tools\n'
  run=$T_A6_BASE
  t_a6_state_reset
  t_shim_log_reset
  for tool in profile jfr trace; do
    t_a6_cycle "$tool" "$run"
    [ "$T_CYCLE" = "0 0" ] || bad="$bad $tool:$T_CYCLE"
  done
  t_empty collector_tools_start_and_stop "$bad"
  t_equals collector_tools_are_gone "no no no" "$(t_exists "$T_BIN/px-profile") $(t_exists \
    "$T_BIN/px-jfr") $(t_exists "$T_BIN/px-trace-count")"
  # Aside from exec calls, the only backend runtime invocation is the label-filtered ps lookup
  # used for scope resolution.
  verbs="$(t_runtime_verbs "$T_SHIM_LOG")"
  t_equals collector_tools_reach_the_cluster_by_exec_only "compose:exec ps|" \
    "$(printf '%s\n' "$verbs" | tr '\n' ' ' | sed 's/ $//')|$(awk \
      '$1 == "ps" && $0 !~ /^ps -a --filter label=/' "$T_SHIM_LOG")"
  t_empty collector_tools_call_no_other_contract_function "$(grep -ho 'sb_[a-z_]*' \
    "$PX_HOME/lib/collect.sh" | LC_ALL=C sort -u | grep -vx -e sb_exec -e sb_load \
    -e sb_scope_require)"
  # RQ-129: Profiling captures cpu and alloc together in a single session at a 7919 us interval
  # on each RegionServer without frame-filtering or stack-truncation options, ensuring zero frames
  # are skipped. Client 0 and tooling 0 have no target JVM and are recorded as skipped.
  bad=""
  while IFS= read -r line; do
    case "$line" in
    *" --minwidth"* | *" -j "* | *" --jstackdepth"*) bad="$bad [$line]" ;;
    "regionserver-"*" stop "*) ;;
    "regionserver-"*" start -e cpu,alloc -i 7919us --timeout "*) ;;
    *) bad="$bad [$line]" ;;
    esac
  done <"$T_A6_STATE/asprof.log"
  t_equals profile_command_has_interval_7919us_and_skip_0_for_cpu_and_alloc "4 yes skip skip" \
    "$(grep -c '^regionserver-' "$T_A6_STATE/asprof.log") $([ -z "$bad" ] && echo yes ||
      echo "$bad") $(awk -F '\t' '$1 == "profile" && $2 == "client-0" { print $3 }' \
      "$run/metrics/index.tsv") $(awk -F '\t' '$1 == "profile" && $2 == "tooling-0" { print $3 }' \
      "$run/metrics/index.tsv")"
  # RQ-130: Both the JFR recording and the trace counter delta file are written into the bundle.
  t_equals jfr_writes_a_recording_into_the_bundle "yes ok PASS" "$(t_exists \
    "$run/jfr/regionserver-0.jfr") $(awk -F '\t' '$1 == "jfr" && $2 == "regionserver-0" {
      print $3 }' "$run/metrics/index.tsv") $(t_severity_of "$run" collector.jfr)"
  t_equals trace_count_writes_a_counter_file "yes 2 10 PASS" "$(t_exists \
    "$run/traces/counters.tsv") $(awk 'NR > 1' "$run/traces/counters.tsv" | grep -c .) $(awk \
    -F '\t' 'NR == 2 { print $7 }' "$run/traces/counters.tsv") $(t_severity_of "$run" \
    collector.trace)"
  # Verify A7: a JVM restart loses the active recording while jcmd still exits with status 0 on
  # the refused stop, and the empty file created at start must not pass as a valid recording.
  copy="$(t_a6_copy "$run" refused jfr)"
  : >"$T_A6_STATE/jcmd-refuses"
  t_a6_cycle jfr "$copy"
  rm -f "$T_A6_STATE/jcmd-refuses"
  got="$(awk -F '\t' '$1 == "jfr" && $2 == "regionserver-0" { print $3 }' \
    "$copy/metrics/index.tsv")"
  t_equals jfr_stop_that_writes_no_recording_fails "0 1 fail ADVISORY" \
    "$T_CYCLE $got $(t_severity_of "$copy" collector.jfr)"
  # Verify O8: diagnostic output from remote collector tools is redirected to logs/collect.log in
  # the bundle rather than emitted to stderr.
  t_equals collect_remote_output_goes_to_the_log "yes no" "$(t_flag 'Could not write recording' \
    "$(cat "$copy/logs/collect.log" 2>/dev/null)") $(t_flag 'Could not write recording' \
    "$T_STDERR")"
  t_equals collect_stop_prints_index_and_result "$(t_rows 4 result fail 1 "$copy")" \
    "$(t_last_line "$T_STDOUT")"
  # RQ-130: A missing jcmd binary or absent trace reader produces a SKIP verdict rather than a
  # failure.
  copy="$(t_a6_copy "$run" nojcmd jfr)"
  t_a6_cycle jfr "$copy" "PX_JDK_ROOT=$T_A6/jdk-nojcmd"
  t_equals jfr_without_jcmd_skips "0 0 SKIP no" "$T_CYCLE $(t_severity_of "$copy" \
    collector.jfr) $(t_exists "$copy/jfr/regionserver-0.jfr")"
  copy="$(t_a6_copy "$run" noreader traces)"
  : >"$T_A6_STATE/no-trace-reader"
  t_a6_cycle trace "$copy"
  rm -f "$T_A6_STATE/no-trace-reader"
  t_equals trace_count_without_its_reader_skips "0 0 SKIP no" "$T_CYCLE $(t_severity_of \
    "$copy" collector.trace) $(t_exists "$copy/traces/counters.tsv")"
  # RQ-130: Only JFR and trace collection skip when their tool is absent; a missing async-profiler
  # binary or jHiccup jar records a failed target with an ADVISORY verdict so lost telemetry never
  # fails the overall run.
  copy="$(t_a6_copy "$run" noasprof profiles)"
  t_a6_cycle profile "$copy" "PX_TOOLS_DIR=$T_A6/no-tools"
  got="$T_CYCLE $(awk -F '\t' '$1 == "profile" && $2 == "regionserver-0" { print $3 }' \
    "$copy/metrics/index.tsv") $(t_severity_of "$copy" collector.profile)"
  t_a6_state_reset
  t_a6_run validation -e "PX_TOOLS_DIR=$T_A6/no-tools" --collect hiccup
  t_equals only_jfr_and_trace_skip_an_absent_tool "1 1 fail ADVISORY|0 ADVISORY fail" \
    "$got|$T_STATUS $(t_severity_of "$(t_bundle)" collector.hiccup) $(awk -F '\t' \
      '$1 == "hiccup" && $2 == "regionserver-0" { print $3 }' "$(t_bundle)/metrics/index.tsv")"
  # RQ-130: The in-image trace counter reader emits counters only from the targeted Phoenix JMX
  # beans, remains silent when an optional bean is absent, and exits with a failure if a server
  # does not respond.
  line='/jmx?qry=Hadoop:service=HBase,name=RegionServer,sub='
  for tool in 0 1; do
    t_curl_body "http://regionserver-$tool:16030${line}Server" \
      '{"beans" : [ {"name" : "Hadoop:service=HBase,name=RegionServer,sub=Server",
    "totalRequestCount" : 7'"$tool"', "blockCacheHitPercent" : 3, "Get_num_ops" : 4,
    "tag.x" : "a,b"} ]}'
    for got in PhoenixIndexer GlobalIndexChecker PhoenixMetadata MetadataCaching; do
      t_curl_body "http://regionserver-$tool:16030$line$got" '{"beans" : [ ]}'
    done
  done
  t_curl_body "http://regionserver-1:16030${line}PhoenixIndexer" \
    '{"beans":[{"preBatchMutateTimeNumOps":2,"indexWriteCount":5}]}'
  t_image PX_ROLE=client PX_RS_COUNT=2 PX_PORT_REGIONSERVER_HTTP=16030 -- "$T_BASH" \
    "$T_IMG/bin/px-trace-counters"
  got="$T_STATUS|$T_STDOUT"
  t_image PX_ROLE=client PX_RS_COUNT=3 PX_PORT_REGIONSERVER_HTTP=16030 -- "$T_BASH" \
    "$T_IMG/bin/px-trace-counters"
  t_equals trace_counters_read_the_phoenix_beans "0|$(t_rows 5 \
    regionserver 0 Server totalRequestCount 70 regionserver 0 Server Get_num_ops 4 \
    regionserver 1 Server totalRequestCount 71 regionserver 1 Server Get_num_ops 4 \
    regionserver 1 PhoenixIndexer indexWriteCount 5)|1" "$got|$T_STATUS"
  t_a6_front_door
}

# Exercises the px-collect front door across collector listing, starting into a new or active
# collection, optionless stop, validation refusals, sampler duration bounds, and teardown via
# px-cluster down.
## @description  Verifies the px-collect lifecycle, validation refusals, sampler bounds, and down.
## @return       Records test assertions. Exit 0.
t_a6_front_door() {
  local dir copy got before line pid
  t_a6_state_reset
  t_a6 "$T_BASH" "$T_BIN/px-collect" list
  t_equals collect_list_names_the_six "0 profile jfr hiccup host container trace" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | cut -f 1 | tr '\n' ' ' | sed 's/ $//')"
  before="$(t_a6_collections)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host,container --class validation -n
  t_equals collect_start_dry_run_writes_nothing "0 2 yes $before" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c "^plan${PX_TAB}start$PX_TAB") $(t_flag \
    "plan${PX_TAB}start${PX_TAB}host - 3600 <new collection>" "$T_STDOUT") $(t_a6_collections)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation --duration 60
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  t_equals collect_start_prints_the_directory "0 1 yes" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c .) $([ -d "$dir" ] && echo yes || echo no)"
  t_equals collect_start_without_bundle_creates_a_collection "$T_A6_OUT/collect yes yes yes" \
    "${dir%/*} $([[ ${dir##*/} =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-z]{4}$ ]] && echo yes ||
      echo no) $(cmp -s "$T_A6/deploy/services.tsv" "$dir/cluster/services.tsv" && echo yes ||
      echo no) $(t_flag "$dir" "$(cat "$T_A6_OUT/state/collect-selftest.tsv" 2>/dev/null)")"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect container --class validation --duration 60
  t_equals collect_second_start_joins "0 $dir 2" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    sed -n 1p) $(grep -c . "$dir/collect/started.tsv" 2>/dev/null)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation
  t_equals collect_refuses_a_collector_that_ran "2 yes" "$T_STATUS $(t_flag \
    "collector host already ran in $dir" "$T_STDERR")"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop -n
  t_equals collect_stop_dry_run_plans_each_collector "0 $(t_rows 3 plan stop "host $dir" \
    plan stop "container $dir")" "$T_STATUS $T_STDOUT"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop
  t_equals collect_stop_needs_no_options "0 $(t_rows 4 result ok 0 "$dir") yes yes no" \
    "$T_STATUS $(t_last_line "$T_STDOUT") $(printf '%s\n' "$T_STDOUT" |
      awk -F '\t' '$1 == "host" && $3 == "ok" { f = 1 } END { print f ? "yes" : "no" }') \
$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "container" && $3 == "ok" { f = 1 }
      END { print f ? "yes" : "no" }') $(t_flag "$dir" \
      "$(cat "$T_A6_OUT/state/collect-selftest.tsv" 2>/dev/null)")"
  t_equals collect_stop_ends_the_sampler "no 2" "$(t_a6_alive "$(t_a6_sampler_pid "$dir" \
    host)") $(awk -F '\t' '$2 == "end" && $3 ~ /^collector@/' "$dir/stages.tsv" | grep -c .)"
  got="$(cksum "$dir/stages.tsv" "$dir/metrics/index.tsv" "$dir/verdicts.tsv")"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop "$dir"
  t_equals collect_stop_twice_writes_no_row_twice "0 yes 1" "$T_STATUS $([ "$got" = "$(cksum \
    "$dir/stages.tsv" "$dir/metrics/index.tsv" "$dir/verdicts.tsv")" ] && echo yes ||
      echo no) $(printf '%s\n' "$T_STDOUT" | grep -c .)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop
  t_equals collect_stop_without_active_collection_is_6 "6 yes" "$T_STATUS $(t_flag \
    'no collection of cluster selftest is active' "$T_STDERR")"
  before="$(t_a6_collections)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect gc --class validation
  t_equals collect_refuses_an_unknown_collector_with_2 "2 yes yes" "$T_STATUS $([ "$before" = \
    "$(t_a6_collections)" ] && echo yes || echo no) $(t_flag "--collect: 'gc' is no collector" \
    "$T_STDERR")"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --class validation
  t_equals collect_needs_collectors "2 yes" "$T_STATUS $(t_flag \
    'name the collectors with --collect' "$T_STDERR")"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation --duration 0
  t_equals collect_duration_needs_at_least_1 "2 yes" "$T_STATUS $(t_flag \
    "--duration needs whole seconds above zero, got '0'" "$T_STDERR")"
  rm -f "$T_A6_OUT/state/scope-compose-selftest"
  t_a6 PX_RUN_CLASS= "$T_BASH" "$T_BIN/px-collect" start --collect host -n
  t_equals collect_without_a_cluster_is_6_before_the_class "6 yes" "$T_STATUS $(t_flag \
    'no cluster selftest is recorded' "$T_STDERR")"
  printf 'px-selftest\n' >"$T_A6_OUT/state/scope-compose-selftest"
  rm -f "$T_A6_OUT/state/deployment-compose-selftest"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation
  t_equals collect_without_deployment_record_is_6 "6 yes yes" "$T_STATUS $(t_flag \
    'no deployment record of cluster selftest' "$T_STDERR") $([ "$before" = \
    "$(t_a6_collections)" ] && echo yes || echo no)"
  t_a6_state_reset
  # Verify that the "all" target selector resolves to every service role except setup and the pherf
  # group.
  copy="$T_A6_OUT/copies/alltargets/20991231T000000Z-0all"
  mkdir -p "$copy/cluster"
  {
    printf '%s\n' "# service role index group start mem_limit_mib heap_mib direct_mib jdk"
    t_rows 9 regionserver-0 regionserver 0 '' '' 2560 1792 256 17 \
      client-0 client 0 '' '' 1536 1024 128 17 setup-0 setup 0 '' '' 512 256 64 17 \
      pherf-agent-0 pherf-agent 0 pherf '' 1024 512 64 17
  } >"$copy/cluster/services.tsv"
  printf '. "%s/lib/common.sh"\n. "%s/lib/collect.sh"\nck_targets all\n' "$PX_HOME" "$PX_HOME" \
    >"$T_A6/targets.sh"
  t_run env "PX_OUT=$T_A6_OUT" "PX_RUN_DIR=$copy" "$T_BASH" "$T_A6/targets.sh"
  t_equals collect_all_excludes_setup_and_pherf "0 regionserver|client" "$T_STATUS $(printf \
    '%s\n' "$T_STDOUT" | cut -f 1 | paste -sd '|' -)"
  # Verify that a sampler terminates automatically upon reaching its duration bound, and that stop
  # forcibly kills any sampler that fails to terminate on its own.
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation --duration 2
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  pid="$(t_a6_sampler_pid "$dir" host)"
  sleep 5
  t_equals collect_sampler_ends_at_its_duration "yes no yes PASS" "${pid:+yes} $(t_a6_alive \
    "$pid") $(t_flag "${PX_TAB}end${PX_TAB}collector@host$PX_TAB" "$(cat "$dir/stages.tsv" \
    2>/dev/null)") $(t_severity_of "$dir" collector.host)"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop "${dir##*/}"
  t_equals collect_stop_takes_the_run_id_of_a_collection "0 $(t_rows 4 result skip 0 "$dir")" \
    "$T_STATUS $T_STDOUT"
  # Verify that invoking stop against a bundle with no active collectors leaves the bundle
  # contents unchanged.
  copy="$T_A6_OUT/copies/finished/20991231T000000Z-0fin"
  rm -rf "$copy"
  mkdir -p "$copy/metrics"
  printf '{}\n' >"$copy/manifest.json"
  t_a6 "$T_BASH" "$T_BIN/px-collect" stop "$copy"
  t_equals collect_stop_of_a_finished_bundle_creates_nothing \
    "0 $(t_rows 4 result skip 0 "$copy") manifest.json metrics" "$T_STATUS $T_STDOUT $(
      find "$copy" -mindepth 1 -maxdepth 1 | sed 's|.*/||' | LC_ALL=C sort | tr '\n' ' ' |
      sed 's/ $//')"
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect container --class validation --duration 60
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  pid="$(t_a6_sampler_pid "$dir" container)"
  printf '30\n' >"$T_A6_STATE/stats-sleep"
  sleep 2
  t_a6 PX_TIMEOUT_VERB=1 "$T_BASH" "$T_BIN/px-collect" stop "$dir"
  t_equals collect_stop_kills_a_stuck_sampler "0 ADVISORY yes no" "$T_STATUS $(t_severity_of \
    "$dir" collector.container) $(t_flag 'the sampler did not stop within 1s and was killed' \
    "$(cat "$dir/verdicts.tsv")") $(t_a6_alive "$pid")"
  rm -f "$T_A6_STATE/stats-sleep"
  # Verify that px-cluster down stops every recorded active collection before tearing down the
  # cluster.
  t_a6 "$T_BASH" "$T_BIN/px-collect" start --collect host --class validation --duration 60
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  pid="$(t_a6_sampler_pid "$dir" host)"
  t_a6 "$T_BASH" "$T_BIN/px-cluster" down -n
  t_contains down_dry_run_plans_the_collection_stop \
    "plan${PX_TAB}collect${PX_TAB}px-collect stop $dir" "$T_STDOUT"
  t_a6 "$T_BASH" "$T_BIN/px-cluster" down
  t_equals down_stops_a_recorded_collection "0 no yes no" "$T_STATUS $(t_a6_alive "$pid") \
$(t_flag "${PX_TAB}end${PX_TAB}collector@host$PX_TAB" "$(cat "$dir/stages.tsv" 2>/dev/null)") \
$(t_exists "$T_A6_OUT/state/collect-selftest.tsv")"
  t_a6_state_reset
}

## @description  Verifies measurement-run collectors: jHiccup (RQ-131), samplers (RQ-132), bundle
##               archiving (RQ-133), start/stop lifecycle ordering (RQ-134), and verdicts (RQ-126).
## @return       Records assertions. Exit 0.
t_group_a6_run() {
  local run host copy bad="" got line begin end first last name pid before sampler
  local -a envs=()
  printf '# A6: the collectors of a run\n'
  # A measurement-class run launches all six collectors bracketing the workload driver's run verb.
  t_a6_state_reset
  t_a6_run measurement -- 3
  run="$(t_bundle)"
  t_equals measurement_run_with_every_collector_passes 0 "$T_STATUS"
  # RQ-134: Every active collector's stage window must completely enclose the run verb's window.
  IFS="$PX_TAB" read -r begin end <<EOF
$(t_a6_window "$run" verb@run)
EOF
  bad=""
  for name in profile jfr hiccup host container trace; do
    IFS="$PX_TAB" read -r first last <<EOF
$(t_a6_window "$run" "collector@$name")
EOF
    if [ -z "$first" ] || [ -z "$last" ] || [ "$first" -gt "${begin:-0}" ] ||
      [ "$last" -lt "${end:-0}" ]; then
      bad="$bad $name"
    fi
  done
  t_equals collectors_start_before_the_run_verb_and_stop_after "6 yes" \
    "$(t_collector_anchors "$run" | wc -w | tr -d ' ') $([ -z "$bad" ] && echo yes ||
      echo "$bad")"
  t_empty no_collector_process_outlives_px_run "$(t_a6_leftovers "$run")"
  # RQ-131: jHiccup produces one histogram log per targeted daemon JVM plus one log for the idle
  # control JVM running inside tooling 0, while skipping client 0 and tooling 0 as target daemons.
  t_equals hiccup_writes_one_log_per_target_instance "yes yes no no|skip skip" \
    "$(t_exists "$run/metrics/hiccup/regionserver-0.hlog") $(t_exists \
      "$run/metrics/hiccup/regionserver-1.hlog") $(t_exists \
      "$run/metrics/hiccup/client-0.hlog") $(t_exists "$run/metrics/hiccup/tooling-0.hlog")|$(
      awk -F '\t' '$1 == "hiccup" && ($2 == "client-0" || $2 == "tooling-0") &&
        $5 ~ /no JVM/ { printf "%s%s", s, $3; s = " " }' "$run/metrics/index.tsv")"
  t_equals hiccup_runs_an_idle_control_jvm_in_tooling "yes 1" \
    "$(t_exists "$run/metrics/hiccup/control.hlog") $(grep -c '^tooling-0 .* -jar ' \
      "$T_A6_STATE/java.log")"
  t_equals run_collects_through_px_collect "yes 6 0" "$(t_exists "$run/logs/px-collect.log") \
$(grep -c . "$run/collect/started.tsv" 2>/dev/null) $(find "$run/logs" -name 'collector-*.log' |
    grep -c .)"
  # RQ-132: Resource sampling emits one TSV file per container instance and a single host TSV file,
  # recording one row per sampling pass across the entire run window.
  bad=""
  for name in regionserver-0 regionserver-1 client-0 tooling-0; do
    got="$(t_a6_series "$run/metrics/container/$name.tsv" "$begin" "$end")"
    [ "$got" = yes ] || bad="$bad $name: $got"
  done
  t_equals sampled_run_writes_one_tsv_per_container "4 yes" "$(find \
    "$run/metrics/container" -name '*.tsv' | grep -c .) $([ -z "$bad" ] && echo yes ||
      echo "$bad")"
  t_equals samples_span_the_run yes "$(t_a6_series "$run/metrics/host.tsv" "$begin" "$end")"
  # RQ-133: All HDR histogram logs and trace counter outputs reside under the run bundle directory,
  # are registered as required artifacts in the manifest, and pass bundle validation.
  bad=""
  got=""
  for line in metrics/hiccup/regionserver-0.hlog metrics/hiccup/regionserver-1.hlog \
    metrics/hiccup/control.hlog drivers/hdr/run.hlog traces/start.tsv traces/stop.tsv \
    traces/counters.tsv profiles/regionserver-0.jfr jfr/regionserver-1.jfr; do
    [ -s "$run/$line" ] || bad="$bad $line"
    got="$got $(t_inventory "$run" "$line")"
  done
  t_empty collector_artifacts_are_under_the_run_directory "$bad"
  t_equals collector_artifacts_are_listed_in_the_manifest \
    " present true present true present true present false present true present true\
 present true present true present true" "$got"
  t_validate "$run"
  t_equals bundle_with_collector_artifacts_validates "0 ok" \
    "$T_STATUS $(t_check_status "$T_STDOUT" collectors)"
  copy="$(t_bundle_copy "$run" lostlog)"
  rm -f "$copy/metrics/hiccup/control.hlog"
  t_validate "$copy"
  t_equals missing_collector_artifact_fails_validation "1 yes" "$T_STATUS $(t_flag \
    "path${PX_TAB}fail${PX_TAB}metrics/hiccup/control.hlog is absent" "$T_STDOUT")"
  printf 'gc\tx\tok\t../out\t\n' >>"$copy/metrics/index.tsv"
  t_validate "$copy"
  t_equals bundle_refuses_a_bad_collector_row fail "$(t_check_status "$T_STDOUT" collectors)"
  # RQ-132: A validation run with the host sampler opted in records one row per second, tagging
  # every sample row with the run class and host contamination state.
  t_a6_state_reset
  t_a6_run validation --collect host -- 3
  host="$(t_bundle)/metrics/host.tsv"
  IFS="$PX_TAB" read -r begin end <<EOF
$(t_a6_window "$(t_bundle)" verb@run)
EOF
  # Because the final sampling pass is triggered immediately by the stop signal, its interval
  # since the preceding sample may be shorter than the nominal cadence.
  t_equals sampled_run_writes_host_tsv_per_interval yes "$(t_a6_series "$host" "$begin" "$end")"
  t_equals sample_rows_carry_class_and_contamination "validation,quiet-host" \
    "$(awk -F '\t' 'NR > 1 { print $(NF - 1) "," $NF }' "$host" | sort -u)"
  t_a6_state_reset
  t_a6_run validation --collect host,container --targets regionserver -- 1
  t_equals run_collect_takes_a_list "0 collector@container collector@host" \
    "$T_STATUS $(t_collector_anchors "$(t_bundle)")"
  t_equals run_targets_option_reaches_the_collectors "regionserver-0 regionserver-1" \
    "$(awk -F '\t' '$1 == "container" { print $2 }' "$(t_bundle)/metrics/index.tsv" |
      LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')"
  # Verify that the timeout bound applied by px-run when invoking stop accommodates the px-collect
  # wait window, ensuring a hung sampler is killed and its ADVISORY verdict is recorded in the run.
  t_a6_state_reset
  before=" $(find "$T_A6_OUT/runs" -mindepth 1 -maxdepth 1 2>/dev/null | sed 's|.*/||' |
    tr '\n' ' ') "
  env "${T_A6_ENV[@]}" PX_TIMEOUT_VERB=8 "$T_BASH" "$T_BIN/px-run" --class validation \
    --collect container "${T_A6_RUN[@]}" -- 4 </dev/null >"$T_OUT" 2>"$T_ERR" &
  pid=$!
  run=""
  got=0
  while [ "$got" -lt 300 ] && [ -z "$run" ]; do
    for line in "$T_A6_OUT"/runs/*; do
      case "$before" in
      *" ${line##*/} "*) continue ;;
      esac
      ! grep -q "${PX_TAB}begin${PX_TAB}collector@container$PX_TAB" "$line/stages.tsv" \
        2>/dev/null || run=$line
    done
    [ -n "$run" ] || sleep 0.1
    got=$((got + 1))
  done
  printf '30\n' >"$T_A6_STATE/stats-sleep"
  wait "$pid" 2>/dev/null || true
  rm -f "$T_A6_STATE/stats-sleep"
  sampler="$(t_a6_sampler_pid "$run" container)"
  t_equals run_stop_kills_a_stuck_sampler "ADVISORY yes no" "$(t_severity_of "$run" \
    collector.container) $(t_flag 'the sampler did not stop within 4s and was killed' \
    "$(cat "$run/verdicts.tsv" 2>/dev/null)") $(t_a6_alive "$sampler")"
  # Verify that all child processes spawned by px-run inherit its resolved output root even when
  # PX_OUT is configured through a --config file.
  t_a6_state_reset
  printf 'PX_OUT=%s\n' "$T_A6_OUT" >"$T_A6/out.env"
  for line in "${T_A6_ENV[@]}"; do
    case "$line" in
    PX_OUT=*) ;;
    *) envs+=("$line") ;;
    esac
  done
  rm -rf "${T_A6:?}/user"
  mkdir -p "$T_A6/user"
  t_run env -u PX_OUT "${envs[@]}" "HOME=$T_A6/user" "$T_BASH" "$T_BIN/px-run" \
    --config "$T_A6/out.env" --class validation "${T_A6_RUN[@]}" -- 1
  run="$(t_bundle)"
  t_equals run_children_keep_the_output_root_of_a_config_file "0 $T_A6_OUT/runs yes yes no" \
    "$T_STATUS ${run%/*} $(t_exists "$run/manifest.json") $(t_exists \
      "$run/report/run-summary.tsv") $(t_exists "$T_A6/user/phoenix-harness")"
  got="$(t_a6_collections)"
  t_a6 PX_RUN_CLASS= "$T_BASH" "$T_BIN/px-collect" start --collect host --duration 1 \
    -e PX_SCOPE_ID=px-selftest
  t_equals collect_refuses_a_run_without_a_class "2 yes" \
    "$T_STATUS $([ "$got" = "$(t_a6_collections)" ] && echo yes || echo no)"
  # RQ-126: In a host-contaminated measurement bundle exhibiting both a counter regression and a
  # latency regression, the deterministic counter check still fails the run whereas the latency
  # check is downgraded to ADVISORY, and the bundle retains both trace counters and allocation
  # profiles for diagnosis.
  t_a6_state_reset
  t_a6_run measurement --collect trace,profile -- regress 1
  run="$(t_bundle)"
  t_equals contaminated_counter_regression_fails "1 FAIL" \
    "$T_STATUS $(awk -F '\t' '$1 == "stub.counter_regression" { print $2 }' \
      "$run/report/verdicts.tsv")"
  t_equals contaminated_latency_regression_is_advisory "ADVISORY quiet-host" \
    "$(awk -F '\t' '$1 == "stub.latency_regression" { print $2 }' "$run/report/verdicts.tsv") \
$(t_manifest "$run" /quiet_host/tags/0)"
  t_equals regression_bundle_carries_trace_counters_and_allocations \
    "yes yes present true present true" "$(awk -F '\t' 'NR > 1 && $7 > 0 { f = 1 }
      END { print (f ? "yes" : "no") }' "$run/traces/counters.tsv") $(grep -q alloc \
      "$run/profiles/regionserver-0.jfr" && echo yes || echo no) $(t_inventory "$run" \
      traces/counters.tsv) $(t_inventory "$run" profiles/regionserver-0.jfr)"
  # RQ-134: Interrupting px-run with a signal still stops all active collectors cleanly.
  t_a6_state_reset
  t_run_interrupted TERM env "${T_A6_ENV[@]}" "$T_BASH" "$T_BIN/px-run" --class measurement \
    "${T_A6_RUN[@]}" -- 30
  run="$(t_bundle)"
  t_equals interrupted_run_stops_its_collectors "143 6 none" "$T_STATUS $(awk -F '\t' \
    '$2 == "end" && $3 ~ /^collector@/ && $4 ~ /^collector:/ { print $3 }' "$run/stages.tsv" |
    sort -u | grep -c .) $(t_a6_leftovers "$run" | sed -n 1p | grep . || echo none)"
  # RQ-134: Receiving SIGTERM while collector shutdown is already in progress (simulated here
  # during trace collector stop) hands the remaining teardown to the exit hook, ensuring every
  # collector is stopped and no collector stop record is written twice.
  t_a6_state_reset
  : >"$T_A6_STATE/slow-trace-stop"
  env "${T_A6_ENV[@]}" "$T_BASH" "$T_BIN/px-run" --class measurement "${T_A6_RUN[@]}" -- 1 \
    </dev/null >"$T_OUT" 2>"$T_ERR" &
  line=$!
  got=0
  while [ "$got" -lt 300 ] && [ ! -e "$T_A6_STATE/trace-stopping" ]; do
    sleep 0.1
    got=$((got + 1))
  done
  kill -s TERM "$line" 2>/dev/null || true
  T_STATUS=0
  wait "$line" 2>/dev/null || T_STATUS=$?
  run="$(sed -n 1p "$T_OUT")"
  t_equals term_during_the_stop_stops_every_collector "143 ok ok 6 1 none" "$T_STATUS $(awk \
    -F '\t' '($1 == "profile" || $1 == "jfr") && $2 == "regionserver-0" { printf "%s ", $3 }' \
    "$run/metrics/index.tsv")$(awk -F '\t' '$2 == "end" && $3 ~ /^collector@/ { print $3 }' \
    "$run/stages.tsv" | sort -u | grep -c .) $(awk -F '\t' '$1 == "trace" && $2 == "stop"' \
    "$run/metrics/index.tsv" | grep -c .) $(t_a6_leftovers "$run" | sed -n 1p | grep . ||
      echo none)"
  # RQ-134: The per-test timeout bound computed by px-suite counts one start and one stop bound
  # for each active collector tool, plus one start and one stop bound for the single shared
  # px-collect lifecycle.
  printf 'suite: c\nclass: validation\ntimeout: 60\n\ntest: one\n  driver: %s\n  args: 0\n' \
    "$T_WORK/drivers/exit-code.sh" >"$T_A6/bound.suite"
  t_a6 PX_COLLECTORS=profile,host PX_SESSION_ID=a6-bound PX_SCOPE_ID=px-selftest \
    PX_TIMEOUT_VERB=10 "$T_BASH" "$T_BIN/px-suite" -v "$T_A6/bound.suite"
  # The expected 461-second bound is the sum 60 + 300 + 6 x 10 + 2 x 2 x 10 + 0 + 1.
  t_contains suite_bound_counts_the_collectors 'px-run gets a bound of 461s' "$T_STDERR"
}

## @description  Executes the A6 test groups in sequence: class defaults, collector tools, and
##               run-lifecycle collection.
## @return       Records assertions. Exit 0.
t_group_a6() {
  t_group_a6_classes
  t_group_a6_tools
  t_group_a6_run
  t_a6_state_reset
}

# ---------------------------------------------------------------------------
# H2: Pherf driver verification against new Pherf outputs
# ---------------------------------------------------------------------------
# These test cases feed the pherf driver the captured outputs of a real Pherf execution for the
# run label: the latency report, run log, interval log, and verdict file. Test cases that require a
# cluster execute against the A5 fake fixtures.

## @description  Concatenates each backslash-terminated line from stdin with the following line.
## @return       Writes the unfolded text to stdout. Exit 0.
t_unfold() { awk '/\\$/ { held = held substr($0, 1, length($0) - 1); next } { print held $0
  held = "" }'; }

## @description  Materializes the four real Pherf output files for the run label into a target
##               directory (directory): latency report, run log, interval log, and verdict.
## @return       Populates the target fixture directory. Exit 0.
t_pherf_hdr_fixture() {
  local dir=$1
  mkdir -p "$dir"
  # These files were captured from the P12 tree (c71fef4280) under JDK 17 by running the test class
  # org.apache.phoenix.pherf.run.ChildRun "cli" with the fake driver and the real clock using
  # "-q -z openloop -label run -runDurationMs 200 -targetRate 50 -disableSchemaApply" against the
  # VerdictRunTest fixture scenario src/test/resources/openloop/open_loop.xml in phoenix-pherf with
  # pherf.default.hdr.intervalMs=100. Each file reproduces the captured output byte for byte; a
  # trailing backslash folds any line longer than 99 columns so t_unfold can join it back together.
  # Because RAT rejects JSON or TSV data files without a license header, these files are not stored
  # in the source tree.
  t_unfold >"$dir/LATENCY_run.json" <<'H2_EOF'
{
  "format": "pherf-latency",
  "version": 1,
  "label": "run",
  "startMs": 1795376061757,
  "endMs": 1795376062376,
  "recorder": {
    "lowestUs": 1,
    "highestUs": 600000000,
    "significantDigits": 3
  },
  "hlog": "LATENCY_run.hlog",
  "intervals": "LATENCY_run_intervals.hlog",
  "histograms": [
    {
      "operation": "query:openLoop:o1",
      "phase": "steady",
      "count": 5,
      "minUs": 429,
      "maxUs": 625,
      "meanUs": 517.2,
      "p50Us": 515,
      "p90Us": 625,
      "p99Us": 625,
      "p999Us": 625
    },
    {
      "operation": "query:openLoop:o1",
      "phase": "warmup",
      "count": 4,
      "minUs": 324608,
      "maxUs": 325119,
      "meanUs": 324800.0,
      "p50Us": 324863,
      "p90Us": 325119,
      "p99Us": 325119,
      "p999Us": 325119
    },
    {
      "operation": "query:openLoop:o2",
      "phase": "steady",
      "count": 5,
      "minUs": 430,
      "maxUs": 607,
      "meanUs": 520.6,
      "p50Us": 518,
      "p90Us": 607,
      "p99Us": 607,
      "p999Us": 607
    },
    {
      "operation": "query:openLoop:o2",
      "phase": "warmup",
      "count": 4,
      "minUs": 499,
      "maxUs": 645,
      "meanUs": 588.25,
      "p50Us": 599,
      "p90Us": 645,
      "p99Us": 645,
      "p999Us": 645
    },
    {
      "operation": "response:openLoop:o1",
      "phase": "steady",
      "count": 5,
      "minUs": 739,
      "maxUs": 4391,
      "meanUs": 2983.2,
      "p50Us": 4231,
      "p90Us": 4391,
      "p99Us": 4391,
      "p999Us": 4391
    },
    {
      "operation": "response:openLoop:o2",
      "phase": "steady",
      "count": 5,
      "minUs": 4328,
      "maxUs": 5379,
      "meanUs": 4762.0,
      "p50Us": 4615,
      "p90Us": 5379,
      "p99Us": 5379,
      "p999Us": 5379
    }
  ],
  "schedules": [
    {
      "scenario": "openLoop",
      "querySet": 1,
      "operations": [
        "query:openLoop:o1",
        "query:openLoop:o2"
      ],
      "concurrency": 4,
      "phase": "done",
      "stopReason": "duration",
      "startMs": 1795376062108,
      "endMs": 1795376062312,
      "durationMs": 200,
      "lastTakeMs": 1795376062292,
      "targetRate": 0.0,
      "queueDepth": 0,
      "inFlight": 0,
      "driftUs": 0,
      "scheduled": 10,
      "completed": 10,
      "late": 0,
      "dropped": 0,
      "aborted": 0,
      "pending": 0,
      "maxQueueDepth": 1,
      "maxInFlight": 1,
      "maxDriftUs": 4874
    }
  ]
}
H2_EOF
  t_unfold >"$dir/LATENCY_run.hlog" <<'H2_EOF'
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERATI\
ON|PHASE]
#[Histogram log format version 1.3]
#[StartTime: 1795376061.757 (seconds since epoch), Sun Nov 22 11:34:21 PST 2026]
#[BaseTime: 1795376061.757 (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
Tag=query:openLoop:o1|steady,0.000,0.619,625.000,HISTFAAAACx42pNpmSzMwMDAwwABzFCaEUQoH3ZjsP8AEbjJxl\
TNpM0kyHSUkQkAh4wGGA==
Tag=query:openLoop:o1|warmup,0.000,0.619,325119.000,HISTFAAAACV42pNpmSzMwMDAygABzFCaEUQoH3ZjsP8AEXg\
+mZGNCQBgtgUu
Tag=query:openLoop:o2|steady,0.000,0.619,607.000,HISTFAAAACx42pNpmSzMwMDAwwABzFCaEUQoH3ZjsP8AEbjNxt\
TPyCTNZMxUyQQAhucF9A==
Tag=query:openLoop:o2|warmup,0.000,0.619,645.000,HISTFAAAACp42pNpmSzMwMDAxQABzFCaEUQoH3ZjsP8AEXjKzn\
SUkUmYyZkJAHySBcA=
Tag=response:openLoop:o1|steady,0.000,0.619,4391.000,HISTFAAAAC142pNpmSzMwMDAywABzFCaEUQoH3ZjsP8AET\
jKzbSXg2mnDJMwkzkTAJF2BnE=
Tag=response:openLoop:o2|steady,0.000,0.619,5379.000,HISTFAAAAC142pNpmSzMwMDAywABzFCaEUQoH3ZjsP8AEf\
hswOTPZMm0m5FpLyMTAJLzBuI=
H2_EOF
  t_unfold >"$dir/LATENCY_run_intervals.hlog" <<'H2_EOF'
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERATI\
ON|PHASE]
#[Histogram log format version 1.3]
#[StartTime: 1795376061.757 (seconds since epoch), Sun Nov 22 11:34:21 PST 2026]
#[BaseTime: 1795376061.757 (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
Tag=query:openLoop:o1|steady,0.311,0.100,525.000,HISTFAAAACV42pNpmSzMwMDAygABzFCaEUQoH3ZjsP8AEbjOzm\
TPBABeqwTM
Tag=query:openLoop:o1|warmup,0.311,0.100,325119.000,HISTFAAAACV42pNpmSzMwMDAygABzFCaEUQoH3ZjsP8AEXg\
+mZGNCQBgtgUu
Tag=query:openLoop:o2|steady,0.311,0.100,545.000,HISTFAAAACV42pNpmSzMwMDAygABzFCaEUQoH3ZjsP8AEejmYD\
JmAgBdGwR1
Tag=query:openLoop:o2|warmup,0.311,0.100,645.000,HISTFAAAACp42pNpmSzMwMDAxQABzFCaEUQoH3ZjsP8AEXjKzn\
SUkUmYyZkJAHySBcA=
Tag=response:openLoop:o1|steady,0.311,0.100,4275.000,HISTFAAAACZ42pNpmSzMwMDAxgABzFCaEUQoH3ZjsP8AEW\
gVYTovwwQAY9kFNA==
Tag=response:openLoop:o2|steady,0.311,0.100,4995.000,HISTFAAAACZ42pNpmSzMwMDAxgABzFCaEUQoH3ZjsP8AET\
hqyPSdkQkAZiwFng==
Tag=query:openLoop:o1|steady,0.411,0.100,515.000,HISTFAAAACZ42pNpmSzMwMDAxgABzFCaEUQoH3ZjsP8AEbjJxr\
SSkQkAZOMFOQ==
Tag=query:openLoop:o2|steady,0.411,0.100,503.000,HISTFAAAACZ42pNpmSzMwMDAxgABzFCaEUQoH3ZjsP8AEbjNxt\
TPyAQAZKEFIQ==
Tag=response:openLoop:o1|steady,0.411,0.100,4391.000,HISTFAAAACh42pNpmSzMwMDAwQABzFCaEUQoH3ZjsP8AET\
jKzfRThcmXCQBx4gXu
Tag=response:openLoop:o2|steady,0.411,0.100,5379.000,HISTFAAAACZ42pNpmSzMwMDAxgABzFCaEUQoH3ZjsP8AEf\
hswNTJwgQAZfcFYA==
Tag=query:openLoop:o1|steady,0.511,0.098,625.000,HISTFAAAACJ42pNpmSzMwMDAzAABMJoRRCgfdmOw/wAReMjJBA\
BU7wSV
Tag=query:openLoop:o2|steady,0.511,0.098,607.000,HISTFAAAACJ42pNpmSzMwMDAzAABMJoRRCgfdmOw/wAR2MvJBA\
BUgwRx
Tag=response:openLoop:o2|steady,0.511,0.098,4615.000,HISTFAAAACJ42pNpmSzMwMDAzAABMJoRRCgfdmOw/wARaD\
RiAgBUIQRe
H2_EOF
  {
    printf '%s\t%s\t%s\t%s\t%s\n' pherf.health.attainment PASS duration pherf "lowest attainment\
 100.0% (scenario openLoop query set 1: 10 of 10 completed, taken in 184 ms of a 200 ms window);\
 minimum 90%"
    printf '%s\t%s\t%s\t%s\t%s\n' pherf.health.saturation PASS duration pherf "peak lag 4.874 ms\
 (scenario openLoop query set 1), within 1000 ms; queue peak 1 of 4, below its bound; in-flight\
 peak 1 of 4; late 0, dropped 0"
    printf '%s\t%s\t%s\t%s\t%s\n' pherf.health.client_pool PASS count pherf "no task waited: tasks\
 1, peak demand 1 of 10 threads"
    printf '%s\t%s\t%s\t%s\t%s\n' pherf.health.tail_latency PASS duration pherf "all 4 keys within\
 the bounds p99 1000 ms, p99.9 5000 ms; highest response:openLoop:o2 p99 5.379 ms, p99.9\
 5.379 ms"
    printf '%s\t%s\t%s\t%s\t%s\n' pherf.health.read_skew SKIP share pherf "no source:\
 pherf.verdict.serverJmx is empty"
  } >"$dir/VERDICT_run.tsv"
}

## @description  Extracts the value of the last aggregate metric record matching an anchor and key
##               (bundle, anchor, key, [window]), or its "start end" window when window is set.
## @return       Writes the value or window to stdout, or empty if no record matches. Exit 0.
t_h2_value() {
  awk -F '\t' -v a="$2" -v k="$3" -v w="${4:-}" '
    $4 == a && $5 == k { v = (w == "") ? $7 : $1 " " $2 } END { print v }' \
    "$1/metrics/aggregate.tsv" 2>/dev/null
}

## @description  Populates a bundle with the files left by a pherf run (bundle, [hdr]): aggregate
##               CSV, run.tsv, a steady window around the fixture run, and the HDR fixture on hdr.
## @return       Writes the pherf run files into the bundle. Exit 0.
t_h2_report_bundle() {
  local run=$1 dir="$1/drivers/pherf"
  mkdir -p "$dir/results"
  {
    printf '%s\n' "START_TIME,QUERY_GROUP,QUERY,TENANT_ID,AVG_MAX_TIME_MS,AVG_TIME_MS,\
AVG_MIN_TIME_MS,RUN_COUNT,EXPLAIN_PLAN,RESULT_ROW_COUNT"
    printf '%s\n' '2026-11-22 19:34:22,openLoop,"select count(*) from T",,1,0,0,10,"SCAN T",10'
  } >"$dir/results/RESULT_open_loop_run_aggregate.csv"
  printf 'start_ms\tend_ms\tstatus\n%s\t%s\t0\n' 1795376061000 1795376063000 >"$dir/run.tsv"
  printf '%s\tbegin\tsteady@pherf\tdriver:pherf\t\n%s\tend\tsteady@pherf\tdriver:pherf\t\n' \
    1795376061000 1795376063000 >"$run/stages.tsv"
  [ "${2:-}" != hdr ] || t_pherf_hdr_fixture "$dir/results_HDR"
}

## @description  Executes the report verb of the pherf driver against a run bundle (bundle).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_h2_report() { t_a5_verb pherf report "$1" --scenario "$T_A5/sql/fixture.sql"; }

## @description  Verifies that the pherf report verb ingests the latency report, run log, and
##               verdict file from a real Pherf run while retaining the CSV fallback path (RQ-207).
## @return       Records test assertions. Exit 0.
t_group_h2_report() {
  local run fixture got op=op@query_openLoop_o1 rows
  printf '# H2: the pherf report on the new Pherf outputs\n'
  t_a5_state_reset
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR"
  t_h2_report "$run"
  # The expected literal values match the captured fixture data in t_pherf_hdr_fixture.
  t_equals pherf_report_reads_the_latency_report "0 5 625 5 5379" "$T_STATUS\
 $(t_h2_value "$run" "$op" pherf.operations) $(t_h2_value "$run" "$op" pherf.latency.p99_us)\
 $(t_h2_value "$run" op@response_openLoop_o2 pherf.operations)\
 $(t_h2_value "$run" op@response_openLoop_o2 pherf.latency.p99_us)"
  t_equals pherf_report_keeps_every_field_of_a_key "429 625 517.2 515 625 625 625" "$(for got in \
    min_us max_us mean_us p50_us p90_us p99_us p999_us; do
    t_h2_value "$run" "$op" "pherf.latency.$got"
  done | paste -sd ' ' -)"
  t_equals pherf_report_records_no_warmup_key "" \
    "$(awk -F '\t' '$7 == 325119 || $7 == 324800.0' "$run/metrics/aggregate.tsv")"
  t_equals pherf_key_window_is_the_report_window "1795376061757 1795376062376" \
    "$(t_h2_value "$run" "$op" pherf.latency.p99_us window)"
  t_equals pherf_steady_operations_count_the_queries_only 10 \
    "$(t_h2_value "$run" steady@pherf pherf.operations)"
  t_equals pherf_schedule_counters_equal_the_report "10 10 0 0 0 1 4874" "$(for got in \
    scheduled completed late dropped aborted max_queue_depth max_drift_us; do
    t_h2_value "$run" schedule@openLoop.1 "pherf.schedule.$got"
  done | paste -sd ' ' -)"
  t_equals pherf_schedule_window_is_the_schedule "1795376062108 1795376062312" \
    "$(t_h2_value "$run" schedule@openLoop.1 pherf.schedule.completed window)"
  t_equals pherf_achieved_rate_comes_once_from_the_report "1 5.000" "$(awk -F '\t' '
    $4 == "steady@pherf" && $5 == "achieved_qps" { n++; v = $7 } END { print n + 0, v }' \
    "$run/metrics/aggregate.tsv")"
  t_equals pherf_latency_passes PASS "$(t_severity_of "$run" pherf.latency)"
  t_contains pherf_latency_names_the_worst_key 'worst p99 5379 us at response:openLoop:o2' \
    "$(t_detail_of "$run" pherf.latency)"
  t_equals pherf_csv_path_runs_beside_the_report "10 PASS" \
    "$(t_h2_value "$run" group@openLoop pherf.result_rows) $(t_severity_of "$run" pherf.results)"
  # Verify that rows from the Pherf verdict file are appended to verdicts.tsv unchanged and in
  # their original order.
  t_equals pherf_report_copies_the_pherf_verdict_rows "$(cat "$fixture/VERDICT_run.tsv")" \
    "$(awk -F '\t' '$4 == "pherf"' "$run/verdicts.tsv")"
  t_equals pherf_verdict_copy_passes PASS "$(t_severity_of "$run" pherf.verdict)"
  # Adding a second schedule verifies that schedule counts are summed while maximum fields record
  # the peak across schedules. Giving one operation key the highest p50 without the highest p99
  # verifies that the steady-state p50 is the maximum p50 across all keys rather than the p50 of
  # the key with the worst p99.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/LATENCY_run.json"
  sed -e 's/: 4391,$/: 4800,/' -e 's/: 4391$/: 4800/' -e 's/"p50Us": 4231,$/"p50Us": 4700,/' \
    "$fixture" | awk '
    /^  "schedules": \[$/ { on = 1 }
    on && /^    \{$/ { held = "" }
    on { held = held $0 "\n" }
    on && /^    \}$/ {
      gsub(/"querySet": 1,/, "\"querySet\": 2,", held)
      gsub(/"scheduled": 10,/, "\"scheduled\": 7,", held)
      gsub(/"completed": 10,/, "\"completed\": 7,", held)
      gsub(/"maxQueueDepth": 1,/, "\"maxQueueDepth\": 3,", held)
      gsub(/"maxDriftUs": 4874/, "\"maxDriftUs\": 1200", held)
      print "    },"
      printf "%s", held
      on = 0
      next
    }
    { print }' >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_steady_sums_counts_and_takes_peak_maxima "0 17 3 4874" "$T_STATUS\
 $(t_h2_value "$run" steady@pherf pherf.schedule.completed)\
 $(t_h2_value "$run" steady@pherf pherf.schedule.max_queue_depth)\
 $(t_h2_value "$run" steady@pherf pherf.schedule.max_drift_us)"
  t_equals pherf_steady_percentiles_are_the_highest_over_the_keys "4.700 5.379" \
    "$(t_h2_value "$run" steady@pherf query_latency.p50_ms)\
 $(t_h2_value "$run" steady@pherf query_latency.p99_ms)"
  # Verify that a latency report with an unrecognized format version or a mismatched run log is
  # rejected.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/LATENCY_run.json"
  sed 's/^  "version": 1,$/  "version": 2,/' "$fixture" >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_unknown_latency_report_version_fails "0 FAIL 0" "$T_STATUS\
 $(t_severity_of "$run" pherf.latency) $(awk -F '\t' '$4 ~ /^op@/ { n++ } END { print n + 0 }' \
    "$run/metrics/aggregate.tsv")"
  t_equals pherf_report_without_the_json_keeps_no_percentile "" \
    "$(t_h2_value "$run" steady@pherf query_latency.p99_ms)"
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/LATENCY_run.hlog"
  sed '1s/highest 600000000/highest 3600000000/' "$fixture" >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_run_log_of_another_configuration_fails FAIL \
    "$(t_severity_of "$run" pherf.latency)"
  rm -f "$fixture"
  t_h2_report "$run"
  t_equals pherf_absent_run_log_fails FAIL "$(t_severity_of "$run" pherf.latency)"
  # Verify that a verdict file containing an unrecognized severity is rejected without appending
  # any rows.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/VERDICT_run.tsv"
  awk -F '\t' -v OFS='\t' 'NR == 2 { $2 = "WARN" } { print }' "$fixture" >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_malformed_verdict_file_is_refused "0 FAIL 0" "$T_STATUS\
 $(t_severity_of "$run" pherf.verdict) $(awk -F '\t' '$4 == "pherf" { n++ } END { print n + 0 }' \
    "$run/verdicts.tsv")"
  t_contains pherf_malformed_verdict_names_the_line 'line 2: severity "WARN"' \
    "$(t_detail_of "$run" pherf.verdict)"
  # Verify that a verdict row declaring any source other than pherf is rejected, because Pherf
  # emits verdict records exclusively under the pherf source.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/VERDICT_run.tsv"
  awk -F '\t' -v OFS='\t' 'NR == 3 { $4 = "px-run" } { print }' "$fixture" >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_verdict_of_another_source_is_refused "0 FAIL 0" "$T_STATUS\
 $(t_severity_of "$run" pherf.verdict) $(awk -F '\t' '$4 == "pherf" || $4 == "px-run" { n++ }
    END { print n + 0 }' "$run/verdicts.tsv")"
  t_contains pherf_verdict_of_another_source_names_the_line 'line 3: source "px-run"' \
    "$(t_detail_of "$run" pherf.verdict)"
  # Verify that a near miss of the check expression is rejected because each dot in the expression
  # matches only a literal dot, and that a repeated check is also rejected.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/VERDICT_run.tsv"
  awk -F '\t' -v OFS='\t' 'NR == 2 { gsub(/[.]/, "X", $1) } { print }' "$fixture" >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_verdict_refuses_a_near_miss_check "0 FAIL 0 yes" "$T_STATUS\
 $(t_severity_of "$run" pherf.verdict) $(awk -F '\t' '$4 == "pherf" { n++ } END { print n + 0 }' \
    "$run/verdicts.tsv") $(t_flag 'line 2: check "pherfXhealthX' \
    "$(t_detail_of "$run" pherf.verdict)")"
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  fixture="$run/drivers/pherf/results_HDR/VERDICT_run.tsv"
  awk -F '\t' -v OFS='\t' 'NR == 2 { k = $1 } NR == 3 { $1 = k } { print }' "$fixture" \
    >"$fixture.new"
  mv "$fixture.new" "$fixture"
  t_h2_report "$run"
  t_equals pherf_verdict_refuses_a_repeated_check "0 FAIL yes" "$T_STATUS\
 $(t_severity_of "$run" pherf.verdict) $(t_flag 'line 3: check' \
    "$(t_detail_of "$run" pherf.verdict)")"
  # Verify that when a -mt run writes no aggregate CSV, the report alone supplies the metrics.
  run="$(t_a5_bundle)"
  t_h2_report_bundle "$run" hdr
  rm -rf "$run/drivers/pherf/results"
  t_h2_report "$run"
  t_equals pherf_report_without_a_csv_skips_the_results "0 SKIP PASS 5.000" "$T_STATUS\
 $(t_severity_of "$run" pherf.results) $(t_severity_of "$run" pherf.latency)\
 $(t_h2_value "$run" steady@pherf achieved_qps)"
  # Verify requirement R-170: the verdict severity and metric class sets defined in Pherf match
  # those defined by the harness.
  fixture="${PX_TREE_ROOT:-/nonexistent}/phoenix-pherf/src/main/java/org/apache/phoenix/pherf"
  fixture="$fixture/verdict/HealthVerdict.java"
  if [ -f "$fixture" ]; then
    t_equals pherf_severities_equal_the_harness_lattice \
      "$(sed -n 's/^SX_SEVERITIES="\(.*\)"$/\1/p' "$PX_HOME/suites/lib/common.sh")" "$(awk '
      /enum Severity \{/ { on = 1; next }
      on && /\}/ { exit }
      on { gsub(/[ \t,]/, ""); if ($0 != "") { printf "%s%s", s, $0; s = " " } }' "$fixture")"
    rows="$(awk '/public static final String (COUNT|SHARE|DURATION|SOURCE) = "/ {
      split($0, q, "\""); print $5 "=" q[2] }' "$fixture" | LC_ALL=C sort | paste -sd ' ' -)"
    t_equals pherf_classes_are_harness_classes "COUNT=count DURATION=duration SHARE=share\
 SOURCE=pherf" "$rows"
  else
    t_skip pherf_severities_equal_the_harness_lattice "no phoenix-pherf source in this tree"
  fi
  t_report measurement "" --verdict "$T_A5/h2/VERDICT_run.tsv"
  t_equals pherf_real_verdict_passes_px_report "0 SKIP" "$T_STATUS $(t_result)"
  t_equals pherf_real_verdict_keeps_each_severity "PASS PASS PASS PASS SKIP" \
    "$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$4 == "pherf" && $2 == $6 { print $2 }' |
      paste -sd ' ' -)"
}

## @description  Verifies the staged properties, fixed labels, CLI overrides, and pre-run gate of
##               the pherf driver against the A5 fake fixtures (RQ-207).
## @return       Records test assertions. Exit 0.
t_group_h2_verbs() {
  local run props="$T_WORK/h2.properties" staged scenario schema option got
  printf '# H2: the pherf driver verbs on the new Pherf\n'
  scenario="$PX_HOME/suites/pherf/px_functional_scenario.xml"
  schema="$PX_HOME/suites/pherf/px_functional_schema.sql"
  printf '%s\n' 'pherf.default.threadpool=4' 'pherf.verdict.noisyHost=false' \
    'pherf.verdict.serverJmx=http://elsewhere:1' '  pherf.verdict.serverJmx = http://elsewhere:2' \
    >"$props"
  t_a5_state_reset
  run="$(t_a5_bundle)"
  PX_CONTAMINATION=quiet-host t_a5_verb pherf prepare "$run" --scenario "$scenario" \
    --schema "$schema" --properties "$props"
  staged="$(cat "$run/drivers/pherf/stage/pherf.properties" 2>/dev/null)"
  t_equals pherf_stages_the_noisy_flag "0 pherf.verdict.noisyHost=true" \
    "$T_STATUS $(printf '%s\n' "$staged" | grep '^pherf\.verdict\.noisyHost=' | paste -sd ' ' -)"
  t_equals pherf_drops_a_server_list_without_read_skew pherf.verdict.serverJmx= \
    "$(printf '%s\n' "$staged" | grep 'serverJmx')"
  t_contains pherf_keeps_the_other_user_keys 'pherf.default.threadpool=4' "$staged"
  run="$(t_a5_bundle)"
  PX_CONTAMINATION='' t_a5_verb pherf prepare "$run" --scenario "$scenario" --schema "$schema"
  t_equals pherf_quiet_host_stages_no_noisy_flag "0 pherf.verdict.noisyHost=false" "$T_STATUS\
 $(grep '^pherf\.verdict\.noisyHost=' "$run/drivers/pherf/stage/pherf.properties" 2>/dev/null)"
  run="$(t_a5_bundle)"
  PX_RS_COUNT=3 PX_PORT_REGIONSERVER_HTTP_0=16030 PX_PORT_REGIONSERVER_HTTP_1=16031 \
    PX_PORT_REGIONSERVER_HTTP_2=16032 t_a5_verb pherf prepare "$run" --scenario "$scenario" \
    --schema "$schema" --properties "$props" --read-skew
  t_equals pherf_read_skew_names_every_regionserver "0 pherf.verdict.serverJmx=\
http://regionserver-0:16030,http://regionserver-1:16031,http://regionserver-2:16032" "$T_STATUS\
 $(grep '^pherf\.verdict\.serverJmx=' "$run/drivers/pherf/stage/pherf.properties" 2>/dev/null |
      paste -sd ' ' -)"
  run="$(t_a5_bundle)"
  PX_RS_COUNT='' t_a5_verb pherf prepare "$run" --scenario "$scenario" --read-skew
  t_equals pherf_read_skew_without_a_regionserver_is_an_anomaly 3 "$T_STATUS"
  # Verify that user-supplied label options are rejected because the driver owns the Pherf label.
  for option in -label --label -label=x; do
    run="$(t_a5_bundle)"
    t_a5_verb pherf validate "$run" --scenario "$scenario" -- "$option" x
    t_equals "pherf_label_option_is_refused${option//[-=]/_}" "2 1" "$T_STATUS\
 $(printf '%s\n' "$T_STDERR" | grep -c 'the driver sets the Pherf -label')"
  done
  # Verify GAP: the driver stages every workload profile referenced by a querySet in the scenario
  # at its relative path alongside the staged scenario so Pherf can resolve it in the staging
  # directory, while ignoring any profile attribute inside an XML comment.
  mkdir -p "$T_WORK/p9/mix"
  cat >"$T_WORK/p9/p9.xml" <<'P9_EOF'
<datamodel name="p9"><datamapping/><scenarios>
  <!-- <querySet concurrency="1" profile="ghost.xml"> -->
  <scenario tableName="T1" name="p9" rowCount="1">
    <querySet concurrency="1"
        profile="open.xml"><query statement="SELECT 1"/></querySet>
    <querySet profile = 'mix/b.xml' concurrency="1"></querySet>
    <querySet concurrency="1" profile="open.xml"></querySet>
  </scenario>
</scenarios></datamodel>
P9_EOF
  printf '<workloadProfile></workloadProfile>\n' >"$T_WORK/p9/open.xml"
  printf '<workloadProfile></workloadProfile>\n' >"$T_WORK/p9/mix/b.xml"
  run="$(t_a5_bundle)"
  t_a5_verb pherf prepare "$run" --scenario "$T_WORK/p9/p9.xml"
  t_equals pherf_stages_the_profiles_of_the_scenario "0 yes yes no" "$T_STATUS\
 $(t_exists "$run/drivers/pherf/stage/scenario/open.xml")\
 $(t_exists "$run/drivers/pherf/stage/scenario/mix/b.xml")\
 $(t_exists "$run/drivers/pherf/stage/scenario/ghost.xml")"
  # Verify that a missing profile file is rejected as a validation failure, whereas an absolute
  # path or a relative path that escapes the scenario directory is rejected as a usage error.
  got=""
  for option in absent.xml /etc/p9.xml ../p9.xml mix/../../p9.xml; do
    sed "s|open.xml|$option|g" "$T_WORK/p9/p9.xml" >"$T_WORK/p9/bad.xml"
    run="$(t_a5_bundle)"
    t_a5_verb pherf validate "$run" --scenario "$T_WORK/p9/bad.xml"
    got="$got $option:$T_STATUS:$(awk -F '\t' '$1 == "pherf.profile" { print $2 }' \
      "$run/verdicts.tsv")"
  done
  t_equals pherf_refuses_an_absent_or_escaping_profile " absent.xml:1:FAIL /etc/p9.xml:2:\
 ../p9.xml:2: mix/../../p9.xml:2:" "$got"
  # Verify P11: when the pre-run gate rejects the scenario during prepare, the run verb never
  # executes.
  t_a5_state_reset
  : >"$T_A5_STATE/pherf-gate-fails"
  t_a5 "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$T_BIN/px-run" \
    --class validation -e PX_SCOPE_ID=px-selftest \
    pherf -- --scenario "$scenario" --schema "$schema"
  run="$(t_bundle)"
  t_equals pherf_gate_refusal_fails_prepare "1 describe:0 validate:0 prepare:1 restore:0\
 report:0 1" "$T_STATUS $(t_verb_codes "$run") $(awk -F '\t' '
    $1 == "pherf.gate" && $2 == "FAIL" && index($5, "exit 65") { n++ } END { print n + 0 }' \
    "$run/verdicts.tsv")"
  t_equals pherf_gate_refusal_is_no_load_failure "" "$(t_severity_of "$run" pherf.load)"
  t_a5_state_reset
}

## @description  Executes a pherf test across every px-run verb with the HDR fixture in fake client
##               0, verifies the bundle copies (RQ-207, RQ-208), and sets T_H2_HDR.
## @return       Records test assertions. Exit 0.
t_h2_hdr_run() {
  local run hdr java name missing=""
  t_a5_state_reset
  t_shim_log_reset
  t_pherf_hdr_fixture "$T_A5_STATE/hdr"
  t_a5 "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$T_BIN/px-run" \
    --class validation -e PX_SCOPE_ID=px-selftest \
    pherf -- --scenario "$PX_HOME/suites/pherf/px_functional_scenario.xml" \
    --schema "$PX_HOME/suites/pherf/px_functional_schema.sql" -- -runDurationMs 200 \
    --threadCount=2 -allowUnknownTags
  run="$(t_bundle)"
  T_H2_HDR=$run
  hdr="$run/drivers/pherf/results_HDR"
  t_equals pherf_hdr_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$run")"
  for name in LATENCY_run.json LATENCY_run.hlog LATENCY_run_intervals.hlog VERDICT_run.tsv; do
    cmp -s "$T_A5/h2/$name" "$hdr/$name" || missing="$missing $name"
  done
  t_empty pherf_hdr_files_are_copied_unchanged "$missing"
  missing=""
  t_lib t_a5_json_rows "$run/manifest.json" /artifacts path status
  for name in LATENCY_run.json LATENCY_run.hlog LATENCY_run_intervals.hlog VERDICT_run.tsv; do
    case "$PX_NL$T_STDOUT$PX_NL" in
    *"${PX_NL}drivers/pherf/results_HDR/$name${PX_TAB}present$PX_NL"*) ;;
    *) missing="$missing $name" ;;
    esac
  done
  t_empty pherf_hdr_files_are_present_artifacts "$missing"
  t_equals pherf_hdr_run_appends_the_pherf_rows "$(cat "$T_A5/h2/VERDICT_run.tsv")" \
    "$(awk -F '\t' '$4 == "pherf"' "$run/verdicts.tsv")"
  t_equals pherf_hdr_manifest_carries_the_steady_p99 5.379 \
    "$(t_manifest "$run" /steady/0/metrics/query_latency.p99_ms/value)"
  t_equals pherf_hdr_bundle_validates 0 "$(t_validate "$run" >/dev/null; printf '%s' "$T_STATUS")"
  java="$(grep 'org\.apache\.phoenix\.pherf\.Pherf' "$T_A5_STATE/java.log" 2>/dev/null)"
  # Verify that run-phase CLI overrides in both two-token and "=" forms are passed exclusively to
  # the query execution phase.
  t_equals pherf_run_overrides_reach_only_the_run "load:0 run:2" "$(printf '%s\n' "$java" |
    awk '{ c = (index($0, " -runDurationMs 200") > 0) + (index($0, " --threadCount=2") > 0) }
      / -l( |$)/ { l += c }
      / -q( |$)/ { q += c }
      END { print "load:" l + 0, "run:" q + 0 }')"
  t_equals pherf_passes_fixed_labels "load:1 run:1" "$(printf '%s\n' "$java" |
    awk '/ -l( |$)/ { l += index($0, " -label load") > 0 }
      / -q( |$)/ { q += index($0, " -label run") > 0 }
      END { print "load:" l + 0, "run:" q + 0 }')"
  t_equals pherf_unknown_tags_flag_reaches_both_verbs 2 \
    "$(printf '%s\n' "$java" | grep -c -- ' -allowUnknownTags')"
  t_a5_state_reset
}

# ---------------------------------------------------------------------------
# H3: Distributed load-generation tier integration in the harness
# ---------------------------------------------------------------------------
# The distributed tier is modeled as the on-demand pherf role group within the harness deployment,
# and every test case executes against the deployment layer fakes. The stub controller is
# implemented as a fake curl binary placed first on PATH: because the fake runtime executes
# commands targeted at pherf-controller 0 directly on the host, curl invocations inside that exec
# resolve to the stub, which serves responses from the pherf/ subdirectory of the fake runtime
# state directory.

## @description  Materializes the stub controller and agent list fixtures for the px-pherf test
##               cases.
## @return       Exit 0.
t_h3_fixtures() {
  T_H3="$T_WORK/h3"
  T_H3_BIN="$T_H3/bin"
  mkdir -p "$T_H3_BIN" "$T_H3/agents"
  cat >"$T_H3_BIN/curl" <<'H3_EOF'
#!/usr/bin/env bash
# The stub controller. It reads the command line of the REST client of the
# harness, logs "METHOD PATH" to calls.log, keeps the body of each call as
# body-<n>.json and answers from the reply files of its directory. A reply
# file holds an HTTP status on its first line and the body after it. A
# status of 400 or more exits 22, as curl --fail-with-body does. A flag file
# selects a fault.
st="${PX_FAKE_STATE_DIR:-/nonexistent}/pherf"
mkdir -p "$st"
method=GET url="" body=0
while [ $# -gt 0 ]; do
  case "$1" in
  -X) method=$2 && shift ;;
  -H | --max-time | --noproxy) shift ;;
  --data-binary) body=1 && shift ;;
  -*) ;;
  *) url=$1 ;;
  esac
  shift
done
path="/${url#http://*/}"
printf '%s %s\n' "$method" "$path" >>"$st/calls.log"
n="$(wc -l <"$st/calls.log" | tr -d ' ')"
[ "$body" = 0 ] || cat >"$st/body-$n.json"
if [ -e "$st/unreachable" ]; then
  printf 'curl: (7) Failed to connect to 127.0.0.1 port 8780\n' >&2
  exit 7
fi
## @description  Writes one reply file (name, sed expression) and exits with the code of its status.
## @return       Exits 0, or 22 for a status of 400 or more.
reply() {
  local file="$st/$1" status
  [ -f "$file" ] || file="$st/not-found"
  status="$(sed -n 1p "$file" 2>/dev/null)"
  sed -e 1d -e "${2:-}" "$file" 2>/dev/null
  [ "${status:-404}" -lt 400 ] || exit 22
  exit 0
}
## @description  Reports how many calls of the log match one expression (expression).
## @return       Writes the count to stdout. Exit 0.
count() { grep -c "^$1\$" "$st/calls.log"; }
case "$method $path" in
"GET /v1/agents")
  k="$(count 'GET /v1/agents')"
  [ ! -f "$st/agents-$k" ] || reply "agents-$k"
  reply agents
  ;;
# Each test and each run gets the number of its call in its id.
"POST /v1/tests")
  reply test "s/\(\"id\":\"t-[0-9TZ]*-\)[0-9]*\"/\1$(count 'POST /v1/tests')\"/"
  ;;
"POST /v1/tests/"*/runs)
  reply run "s/\(\"runId\":\"[0-9TZ]*-\)[0-9]*\"/\1$(count 'POST /v1/tests/.*/runs')\"/"
  ;;
"GET /v1/tests/"*/status)
  # Each run is RUNNING for the count of the file running, then ends.
  k="$(grep -cxF "$method $path" "$st/calls.log")"
  [ "$k" -gt "$(cat "$st/running" 2>/dev/null || echo 0)" ] || reply status-running
  reply status
  ;;
"GET /v1/tests/"*/report) reply report ;;
"GET /v1/tests/"*/bundle) reply bundle ;;
"GET /v1/tests/"*/bundle/*)
  # The file-sleep file holds the seconds that each file of a bundle takes.
  [ ! -f "$st/file-sleep" ] || sleep "$(cat "$st/file-sleep")"
  reply "files/${path#*/bundle/}"
  ;;
"POST /v1/tests/"*/cancel) reply cancel ;;
"POST /v1/tests/"*/end)
  # The end-sleep file holds the seconds that the end of a test takes.
  [ ! -f "$st/end-sleep" ] || sleep "$(cat "$st/end-sleep")"
  reply end
  ;;
esac
reply not-found
H3_EOF
  chmod +x "$T_H3_BIN/curl"
  t_fleet_fixture "$T_H3/fx"
  # Probe script that resolves bundle collector targets for all roles and then for the controller
  # explicitly by name.
  cat >"$T_H3/targets.sh" <<'H3_EOF'
. "$PX_HOME/lib/common.sh"
. "$PX_HOME/lib/collect.sh"
ck_targets all
ck_targets pherf-controller
H3_EOF
}

## @description  Writes a stub GET /v1/agents response (file name, agent id:state...) containing
##               the controller AgentView fields and a fault description for any FAULTED agent.
## @return       Exit 0.
t_h3_agents() {
  local file="$T_A2_STATE/pherf/$1" entry sep=""
  shift
  mkdir -p "$T_A2_STATE/pherf"
  {
    printf '200\n{"agents": ['
    for entry in "$@"; do
      printf '%s{"id": "%s", "incarnation": "i-%s", "state": "%s", "phase": "IDLE",' "$sep" \
        "${entry%%:*}" "${entry%%:*}" "${entry#*:}"
      [ "${entry#*:}" != FAULTED ] || printf ' "fault": "the fixture fault",'
      printf ' "registeredMs": 1795376000000, "lastSeenMs": 1795376001000}'
      sep=", "
    done
    printf ']}\n'
  } >"$file"
}

## @description  Resets the stub controller state directory for the deployment fakes.
## @return       Exit 0.
t_h3_reset() {
  rm -rf "$T_A2_STATE/pherf"
  mkdir -p "$T_A2_STATE/pherf"
  printf '404\n{"error": "not_found", "message": "no such path"}\n' \
    >"$T_A2_STATE/pherf/not-found"
}

## @description  Executes a command with the deployment fakes and the stub controller on PATH.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_h3() { t_a2 "PATH=$T_H3_BIN:$T_A2_BIN:$T_SHIM_DIR:$PATH" "$@"; }

## @description  Executes px-pherf against the deployment fakes and the stub controller.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_h3_pherf() { t_h3 "$T_BASH" "$T_BIN/px-pherf" "$@"; }

## @description  Extracts the rows belonging to group pherf from a rendered service table
##               (directory).
## @return       Writes the matching rows to stdout. Exit 0.
t_h3_tier() { awk -F '\t' '$4 == "pherf"' "$1/services.tsv" 2>/dev/null; }

## @description  Extracts a single service definition block (directory, service) from a rendered
##               Compose file.
## @return       Writes the service block lines to stdout. Exit 0.
t_h3_block() {
  awk -v s="  $2:" '$0 == s { f = 1; print; next } f && /^ ? ?[^ ]/ { f = 0 } f' \
    "$1/compose.yaml" 2>/dev/null
}

## @description  Finds every ZooKeeper client port quorum reference under the given paths (path...),
##               excluding hostnames in the reserved .invalid domain, formatted as file:line:match.
## @return       Writes the matching lines to stdout. Exit 0.
t_h3_quorums() {
  grep -rnoE '[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+:2181|PHERF_ZOOKEEPER[=:][ "]*[A-Za-z0-9][^ "]*' \
    "$@" 2>/dev/null | grep -vE '(\.invalid:2181|127\.0\.0\.1:2181|[" ]zookeeper-0:2181)$' || true
}

## @description  Verifies tier rendering across its enablement switch, targets, fail-closed checks,
##               and service definitions (RQ-249, RQ-251 to RQ-254).
## @return       Records test assertions. Exit 0.
t_group_h3_render() {
  local dir="$T_A2_OUT/deploy/h3m" ext="$T_A2_OUT/deploy/h3x" rows text bad="" line got
  local quorum='zk1.h3.invalid:2181,zk2.h3.invalid:2181/hbase' tag=px-pherf:hbase-2.6.7-profile-2.6
  local record
  printf '# H3: the tier in the render\n'
  t_gen --class measurement --cluster h3d
  text="$(cat "$T_A2_OUT/deploy/h3d/compose.yaml" "$T_A2_OUT/deploy/h3d/topology.env" \
    "$T_A2_OUT/deploy/h3d/services.tsv" 2>/dev/null)"
  t_equals pherf_default_render_holds_no_tier "0 no no" "$T_STATUS $(t_flag pherf "$text")\
 $(t_flag PX_PHERF "$text")"
  # Verify RQ-251 and RQ-254: the measurement class renders one controller and three agents.
  t_gen --class measurement --cluster h3m -e PX_PHERF=1
  rows="$(t_h3_tier "$dir")"
  t_equals pherf_measurement_renders_one_controller_three_agents \
    "0 pherf-controller-0 pherf-agent-0 pherf-agent-1 pherf-agent-2" \
    "$T_STATUS $(printf '%s\n' "$rows" | cut -f 1 | paste -sd ' ' -)"
  t_equals pherf_measurement_renders_three_agents 3 \
    "$(printf '%s\n' "$rows" | awk -F '\t' '$2 == "pherf-agent"' | grep -c .)"
  # Verify RQ-249: the tier memory limits sum to 1024 + 3 x 1536 = 5632 MiB, which is included in
  # the total topology memory footprint but excluded from the eager startup footprint.
  t_equals pherf_tier_limits_sum_to_5632_mib "5632 5632 47616 31744" "$(t_mem_sum "$rows")\
 $(t_env_value "$dir/topology.env" PX_GROUP_PHERF_MEM_MIB PX_TOPOLOGY_MEM_MIB \
      PX_TOPOLOGY_MEM_EAGER_MIB)"
  t_equals pherf_service_rows "$(t_rows 9 pherf-controller-0 pherf-controller 0 pherf on-demand \
    1024 512 0 17 pherf-agent-2 pherf-agent 2 pherf on-demand 1536 537 0 17)" \
    "$(printf '%s\n' "$rows" | sed -n '1p;$p')"
  t_equals pherf_topology_names_the_tier \
    "1 harness 3 $tag pherf-controller pherf-agent yarn pherf" \
    "$(t_env_value "$dir/topology.env" PX_PHERF PX_PHERF_TARGET PX_PHERF_AGENT_COUNT \
      PX_PHERF_IMAGE_TAG PX_GROUP_PHERF PX_GROUPS)"
  t_equals pherf_port_map_holds_the_rest_port 8780 \
    "$(t_env_value "$dir/ports.generated.env" PX_PORT_PHERF_CONTROLLER_REST)"
  t_equals pherf_controller_service "  pherf-controller-0:
    image: \"$tag\"
    pull_policy: never
    hostname: \"pherf-controller-0\"
    networks:
      - default
    restart: \"no\"
    mem_limit: 1024m
    memswap_limit: 1024m
    labels:
      px.harness.cluster: \"h3m\"
      px.harness.group: \"pherf\"
      px.harness.index: \"0\"
      px.harness.role: \"pherf-controller\"
      px.harness.start: \"on-demand\"
    command: [\"controller\", \"start\"]
    environment:
      PHERF_JAVA_OPTS: \"-XX:MaxRAMPercentage=50\"
      PHERF_REST_PORT: \"8780\"
    healthcheck:
      test: [\"CMD\", \"/opt/pherf/healthcheck.sh\", \"controller\"]
      interval: 5s
      timeout: 6s
      retries: 3
      start_period: 60s
    ports:
      - \"127.0.0.1:8780:8780\"
    volumes:
      - pherf-controller-0-data:/var/lib/pherf/run" "$(t_h3_block "$dir" pherf-controller-0)"
  t_equals pherf_agent_service "  pherf-agent-1:
    image: \"$tag\"
    pull_policy: never
    hostname: \"pherf-agent-1\"
    networks:
      - default
    restart: \"no\"
    mem_limit: 1536m
    memswap_limit: 1536m
    labels:
      px.harness.cluster: \"h3m\"
      px.harness.group: \"pherf\"
      px.harness.index: \"1\"
      px.harness.role: \"pherf-agent\"
      px.harness.start: \"on-demand\"
    command: [\"agent\", \"start\"]
    environment:
      PHERF_CONTROLLER: \"http://pherf-controller-0:8790\"
      PHERF_JAVA_OPTS: \"-XX:MaxRAMPercentage=35\"
      PHERF_ZOOKEEPER: \"zookeeper-0:2181\"
    healthcheck:
      test: [\"CMD\", \"/opt/pherf/healthcheck.sh\", \"agent\"]
      interval: 5s
      timeout: 6s
      retries: 3
      start_period: 60s" "$(t_h3_block "$dir" pherf-agent-1)"
  t_equals pherf_controller_volume_carries_the_cluster_label "1" "$(grep -c \
    '^  pherf-controller-0-data:$' "$dir/compose.yaml" 2>/dev/null)"
  # Verify RQ-252: the tier services join the harness network and resolve the harness ZooKeeper
  # endpoint from the generated port map.
  t_equals pherf_tier_joins_the_harness_network "4 1" "$(for line in pherf-controller-0 \
    pherf-agent-0 pherf-agent-1 pherf-agent-2; do t_h3_block "$dir" "$line"; done |
    grep -c '^      - default$') $(awk '/^networks:/ { n = 1; next } /^[a-z]/ { n = 0 }
    n && /^  [a-z]/ { c++ } END { print c + 0 }' "$dir/compose.yaml")"
  t_equals pherf_agents_resolve_the_harness_zookeeper "3 zookeeper-0:$(t_env_value \
    "$dir/ports.generated.env" PX_PORT_ZOOKEEPER_CLIENT)" "$(grep -c \
    '^      PHERF_ZOOKEEPER: "zookeeper-0:2181"$' "$dir/compose.yaml") $(sed -n \
    's/^      PHERF_ZOOKEEPER: "\(.*\)"$/\1/p' "$dir/compose.yaml" | sort -u)"
  t_equals pherf_tier_reads_no_harness_environment_file 0 "$(for line in pherf-controller-0 \
    pherf-agent-0; do t_h3_block "$dir" "$line"; done | grep -c 'env_file')"
  # Verify external target rendering: only the distributed tier services are rendered, and the
  # ZooKeeper quorum is taken exclusively from the operator-supplied environment variable.
  t_gen --class measurement --cluster h3x -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e "PX_PHERF_ZOOKEEPER=$quorum"
  t_equals pherf_external_renders_the_tier_only \
    "0 pherf-controller pherf-agent pherf-agent pherf-agent" \
    "$T_STATUS $(grep -v '^#' "$ext/services.tsv" 2>/dev/null | cut -f 2 | paste -sd ' ' -)"
  t_equals pherf_external_quorum_comes_only_from_the_variable "3 3" "$(grep -rn 'h3.invalid' \
    "$ext" | grep -c .) $(grep -cxF "      PHERF_ZOOKEEPER: \"$quorum\"" "$ext/compose.yaml")"
  t_equals pherf_external_topology_holds_the_tier_and_the_version_set \
    "external 2.6 2.6.7 3.8.6 pherf  " "$(t_env_value "$ext/topology.env" PX_PHERF_TARGET \
      PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION PX_ZOOKEEPER_VERSION PX_GROUPS \
      PX_DN_COUNT PX_GROUP_YARN)"
  t_gen --class measurement --cluster h3y -e PX_PHERF=1 -e PX_PHERF_TARGET=external
  t_equals pherf_external_target_needs_a_quorum "2 yes no" "$T_STATUS $(t_flag \
    PX_PHERF_ZOOKEEPER "$T_STDERR") $(t_exists "$T_A2_OUT/deploy/h3y")"
  t_gen --class measurement --cluster h3y -e PX_PHERF_TARGET=external \
    -e "PX_PHERF_ZOOKEEPER=$quorum"
  t_equals pherf_external_target_needs_the_tier "2 yes" \
    "$T_STATUS $(t_flag 'PX_PHERF=1' "$T_STDERR")"
  t_gen --class measurement --cluster h3y -e PX_PHERF=1 -e "PX_PHERF_ZOOKEEPER=$quorum"
  t_equals pherf_harness_target_refuses_an_external_quorum "2 yes no" "$T_STATUS $(t_flag \
    PX_PHERF_ZOOKEEPER "$T_STDERR") $(t_exists "$T_A2_OUT/deploy/h3y")"
  bad=""
  for line in "zk\$x:2181" 'zk":2181' "zk':2181" 'zk\x:2181' 'zk#1:2181' 'zk 1:2181' "\`zk\`"; do
    t_gen --class measurement --check -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
      -e "PX_PHERF_ZOOKEEPER=$line"
    [ "$T_STATUS" = 2 ] || bad="$bad [$line] gave $T_STATUS"
  done
  t_empty pherf_quorum_grammar_refuses_a_dollar_or_quote "$bad"
  bad=""
  for line in PX_PHERF=yes PX_PHERF_TARGET=cloud PX_PHERF_AGENT_COUNT=0 \
    PX_PHERF_AGENT_COUNT=10 PX_PHERF_AGENT_MEM_MIB=1.5g PX_PHERF_CONTROLLER_MEM_MIB= \
    PX_PHERF_IMAGE_TAG=reg.io/px-pherf:t; do
    got=PX_PHERF=1
    [ "${line%%=*}" != PX_PHERF ] || got=$line
    t_gen --class measurement --check -e "$got" -e "$line"
    case "$T_STATUS:$T_STDERR" in
    2:*"${line%%=*}"*) ;;
    *) bad="$bad [$line] gave $T_STATUS" ;;
    esac
  done
  t_empty pherf_render_fails_closed_on_each_tier_value "$bad"
  t_gen --class measurement --check -e PX_PHERF=1 -e PX_PHERF_AGENT_COUNT=9
  t_equals pherf_agent_count_ceiling_is_nine "0 9" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$2 == "pherf-agent"' | grep -c .)"
  # Verify that an image tag whose staging record specifies a mismatched version set is rejected.
  record="$T_A2_OUT/images/$(px_image_stage_name px-pherf:h3-other).staged.tsv"
  t_rows 2 image_tag px-pherf:h3-other hbase_profile 2.5 hbase_version 2.5.15 \
    zookeeper_version 3.8.4 hbase_hadoop_version 3.3.6 >"$record"
  t_gen --class measurement --check -e PX_PHERF=1 -e PX_PHERF_IMAGE_TAG=px-pherf:h3-other
  t_equals pherf_image_record_of_another_version_set_is_refused "2 yes" "$T_STATUS $(t_flag \
    'px-pherf:h3-other was staged with hbase_profile' "$T_STDERR")"
  rm -f "$record"
  # Verify RQ-253: the validation class permits one or two agents and defaults to two agents with a
  # combined tier memory footprint of 1024 + 2 x 1536 MiB.
  t_gen --class validation --check -e PX_PHERF=1
  rows="$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$4 == "pherf"')"
  t_equals pherf_validation_renders_two_agents "0 pherf-controller-0 pherf-agent-0 pherf-agent-1\
 4096" "$T_STATUS $(printf '%s\n' "$rows" | cut -f 1 | paste -sd ' ' -) $(t_mem_sum "$rows")"
  t_gen --class validation --check -e PX_PHERF=1 -e PX_PHERF_AGENT_COUNT=1
  t_equals pherf_validation_admits_one_agent "0 1" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$2 == "pherf-agent"' | grep -c .)"
  t_gen --class validation --check -e PX_PHERF=1 -e PX_PHERF_AGENT_COUNT=3
  t_equals pherf_validation_refuses_three_agents "2 yes" "$T_STATUS $(t_flag \
    'PX_PHERF_AGENT_COUNT' "$T_STDERR")"
  # Verify that px-cluster surfaces the generator's error diagnostic and closes the error stream
  # with only its own exit summary line.
  t_a2 "$T_BASH" "$T_BIN/px-cluster" --class validation -e PX_PHERF=1 \
    -e PX_PHERF_AGENT_COUNT=3 --dry-run render
  t_equals render_refusal_has_one_exit_line "2 yes yes 1" "$T_STATUS $(t_flag \
    'the validation class runs 1 to 2 agents' "$T_STDERR") $(t_flag \
    'px-cluster: render ends (exit 2, usage)' "$(t_last_line "$T_STDERR")") $(printf '%s\n' \
    "$T_STDERR" | grep -c '(exit ')"
  # Verify that pherf-agent is registered as a scalable role whose instance count can be set via
  # the --scale option.
  t_gen --class measurement --check -e PX_PHERF=1 --scale pherf-agent=4
  t_equals pherf_agent_is_a_scaled_role "0 4" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$2 == "pherf-agent"' | grep -c .)"
  # Verify that a tier memory limit that diverges from the class default is recorded as a sizing
  # override.
  t_gen --class measurement --cluster h3o -e PX_PHERF=1 -e PX_PHERF_AGENT_MEM_MIB=2048
  t_equals pherf_tier_limit_override_is_named "PX_PHERF_AGENT_MEM_MIB" \
    "$(t_env_value "$T_A2_OUT/deploy/h3o/topology.env" PX_SIZING_OVERRIDES)"
  t_equals pherf_tier_defaults_name_no_override "" \
    "$(t_env_value "$dir/topology.env" PX_SIZING_OVERRIDES)"
  # Verify that a jvm-opts plugin hook cannot target a tier role key because the distributed tier
  # exposes no harness JVM option variable name.
  mkdir -p "$T_A2/tierhook/hooks"
  printf '%s\n' PX_PLUGIN_ID=tierhook PX_PLUGIN_CONTRACT=1 PX_PLUGIN_HOOKS=jvm-opts \
    >"$T_A2/tierhook/plugin.env"
  printf '#!/usr/bin/env bash\nprintf "PHERF_AGENT -Dx=1\\n"\n' >"$T_A2/tierhook/hooks/jvm-opts"
  chmod +x "$T_A2/tierhook/hooks/jvm-opts"
  t_gen --class measurement --cluster h3h -e PX_PHERF=1 -e "PX_PLUGIN_DIRS=$T_A2/tierhook" \
    -e PX_PLUGINS=tierhook
  t_equals pherf_jvm_opts_hook_refuses_a_tier_key "2 yes no" "$T_STATUS $(t_flag \
    'PHERF_AGENT' "$T_STDERR") $(t_exists "$T_A2_OUT/deploy/h3h")"
  # Verify RQ-252: no file in the source tree contains a hardcoded external ZooKeeper quorum, and
  # the scanner positively detects a planted quorum in a canary directory.
  t_empty no_external_quorum_in_the_tree "$(t_h3_quorums "$PX_TREE_ROOT/dev" \
    "$PX_TREE_ROOT/phoenix-pherf-distributed/src" \
    "$PX_TREE_ROOT/phoenix-pherf-distributed/README.md")"
  mkdir -p "$T_H3/canary"
  # Construct the planted canary quorum strings dynamically so this test file itself contains no
  # literal quorum match.
  printf 'PHERF_ZOOKEEPER=%s:2181\n' zk1.example.com >"$T_H3/canary/a.env"
  printf -- '-z %s:2181\n' zk2.example.com >"$T_H3/canary/b.sh"
  got="$(t_h3_quorums "$T_H3/canary")"
  t_equals no_external_quorum_scan_finds_a_planted_one "2" "$(printf '%s\n' "$got" | awk -F: '
    { f[$1] = 1 } END { n = 0; for (k in f) n++; print n }')"
}

## @description  Verifies the preflight memory check for the distributed tier against a simulated
##               48 GiB container engine (RQ-249).
## @return       Records test assertions. Exit 0.
t_group_h3_preflight() {
  local pf="$PX_HOME/scripts/preflight.sh" pc="$T_BIN/px-cluster" dir="$T_A2_OUT/deploy/a2" row
  printf '# H3: the tier in the memory check\n'
  printf '51539607552\n' >"$T_A2_STATE/mem-total"
  t_gen --class measurement --cluster h3p -e PX_PHERF=1
  t_a2 PX_CLUSTER_ID=h3p "$T_BASH" "$pf" --phase memory
  row="$(printf '%s\n' "$T_STDOUT" | grep "^memory_fit${PX_TAB}")"
  t_equals preflight_refuses_topology_yarn_and_tier "3 yes yes yes" "$T_STATUS $(t_flag \
    'the limits 47616 MiB' "$row") $(t_flag 'budget 45056 MiB' "$row") $(t_flag \
    'exceed the budget by 2560 MiB' "$row")"
  t_contains preflight_counts_the_agents_on_demand "memory:pherf-agent${PX_TAB}ok${PX_TAB}3 x \
1536 MiB = 4608 MiB, group pherf starts on demand" "$T_STDOUT"
  t_gen --class measurement --cluster h3p -e PX_PHERF=1 -e PX_YARN=0
  t_a2 PX_CLUSTER_ID=h3p "$T_BASH" "$pf" --phase memory
  t_equals preflight_admits_the_tier_without_yarn "0 yes" "$T_STATUS $(t_flag \
    '7680 MiB (7.50 GiB) to spare' "$T_STDOUT")"
  t_gen --class measurement --cluster h3p -e PX_PHERF=1 -e PX_RS_COUNT=2
  t_a2 PX_CLUSTER_ID=h3p "$T_BASH" "$pf" --phase memory
  t_equals preflight_admits_the_tier_with_two_regionservers "0 yes" "$T_STATUS $(t_flag \
    '2048 MiB (2.00 GiB) to spare' "$T_STDOUT")"
  # Verify that starting the on-demand group re-evaluates the memory preflight check against the
  # recorded deployment topology.
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_gen --class measurement --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_a2 "$T_BASH" "$pc" --cluster a2 up "$dir" --no-wait
  t_equals pherf_cluster_comes_up_with_the_tier_created "0 4" "$T_STATUS $(grep -c \
    '|pherf|on-demand|' "$T_A2_STATE/instances.txt")"
  printf '51539607552\n' >"$T_A2_STATE/mem-total"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 group start pherf --timeout 5
  t_equals group_start_reruns_the_memory_check "3 yes no" "$T_STATUS $(t_flag \
    'the memory check refused the start of group pherf' "$T_STDERR") $(t_flag ' start ' \
    "$(cat "$T_SHIM_LOG")")"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_a2 "$T_BASH" "$pc" --cluster a2 group start pherf --timeout 5
  t_equals group_start_starts_the_tier "0 4" "$T_STATUS $(grep '|pherf|on-demand|' \
    "$T_A2_STATE/instances.txt" | grep -c '|running|')"
  t_equals group_start_of_the_tier_is_planned "pherf-agent pherf-controller" "$(awk -F '\t' \
    '$2 == "group_start" && $5 == "group pherf" { print $3 }' \
    "$T_A2_OUT/state/planned-px-a2.tsv" 2>/dev/null | LC_ALL=C sort -u | paste -sd ' ' -)"
  t_a2 "$T_BASH" "$pc" --cluster a2 down
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

## @description  Verifies the tier build pipeline, confirming that a single switch controls the
##               Maven module, the pherf image, and the harness staging context (RQ-248).
## @return       Records test assertions. Exit 0.
t_group_h3_build() {
  local home="$T_A2/tree/dev/harness" tree="$T_A2/tree" bp="$PX_HOME/scripts/build-phoenix.sh"
  local module="$T_A2/tree/phoenix-pherf-distributed" jdk="$T_A2/jdk/17" sa ctx text
  local dist_pins=()
  local pins_env=("PX_IMAGE_BASE_JDK11_SHA256_ARM64=$(printf '1%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK17_SHA256_ARM64=$(printf '7%.0s' $(seq 1 64))"
    "PX_IMAGE_BASE_JDK21_SHA256_ARM64=$(printf '2%.0s' $(seq 1 64))")
  sa="$PX_HOME/scripts/stage-artifacts.sh"
  printf '# H3: the build of the tier\n'
  mkdir -p "$module/src/main/bin" "$module/src/main/conf"
  printf '<project/>\n' >"$module/pom.xml"
  printf '#!/usr/bin/env bash\necho fake\n' >"$module/src/main/bin/pherf-distributed.sh"
  printf 'status = warn\n' >"$module/src/main/conf/log4j2.properties"
  touch -t 202001010000 "$module/pom.xml" "$module/src/main/bin/pherf-distributed.sh" \
    "$module/src/main/conf/log4j2.properties"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHERF=1 "$T_BASH" "$bp" --tree "$tree" -n
  t_equals pherf_switch_builds_the_distributed_module "0 yes" "$T_STATUS $(t_flag \
    ',phoenix-pherf-distributed' "${T_STDOUT//\\/}")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -n
  t_equals pherf_switch_off_builds_no_distributed_module "0 no" "$T_STATUS $(t_flag \
    phoenix-pherf-distributed "${T_STDOUT//\\/}")"
  t_equals pherf_switch_retires_the_build_name 0 "$(grep -c 'PX_BUILD_DISTRIBUTED' \
    "$PX_HOME/lib/varnames.txt" "$PX_HOME/$PX_DEPLOY_SUBDIR/env/defaults.env" "$bp" |
    awk -F: '{ s += $NF } END { print s + 0 }')"
  # Verify RQ-248: the harness image staging context excludes the distributed jar even when the
  # build record lists one, and the harness Dockerfile accepts no build argument for it.
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -e PX_PHERF=1
  dist_pins=("PX_DIST_HBASE_2_6_7_SHA512_ARM64=$(px_sha512 "$T_A2/dist-src/hbase-2.6.7.tar.gz")"
    "PX_DIST_HADOOP_3_4_3_SHA512_ARM64=$(px_sha512 "$T_A2/dist-src/hadoop-3.4.3.tar.gz")")
  t_shim_log_reset
  t_a2 "${dist_pins[@]}" "${pins_env[@]}" "$T_BASH" "$sa"
  ctx="$T_A2_OUT/images/$(px_image_stage_name px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6)"
  text="$(cat "$T_SHIM_LOG")"
  t_equals harness_context_holds_no_distributed_jar "0 yes 0 no 0" "$T_STATUS $(t_flag \
    distributed_jar "$(cat "$T_A2_OUT/build/phoenix/build.tsv")") $(find "$ctx/phoenix" \
    -name 'phoenix-pherf-distributed-*' 2>/dev/null | grep -c .) $(t_flag \
    PX_STAGED_PHOENIX_DISTRIBUTED "$text") $(grep -c 'DISTRIBUTED' \
    "$PX_HOME/$PX_DEPLOY_SUBDIR/image/Dockerfile")"
  # Verify px-cluster build: enabling the tier switch includes the distributed Maven module and
  # stages the px-pherf container image alongside the harness image.
  rm -rf "$home"
  mkdir -p "${home%/*}"
  cp -R "$PX_HOME" "$home"
  : >"$T_A2_MVN_LOG"
  t_shim_log_reset
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$jdk" "${pins_env[@]}" "${dist_pins[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" -e PX_PHERF=1 build
  t_equals build_with_the_tier_stages_the_pherf_image "0 yes 1 1" "$T_STATUS $(t_flag \
    phoenix-pherf-distributed "$(cat "$T_A2_MVN_LOG")") $(grep -c '^build --tag px-harness:' \
    "$T_SHIM_LOG") $(grep -c '^build --tag px-pherf:hbase-2.6.7-profile-2.6 ' "$T_SHIM_LOG")"
  # Under --backend k8s, the build still runs against the local engine and then imports both images
  # into the px-k3d cluster for that context, whereas any other context directs the operator to
  # px-cluster push.
  mkdir -p "$T_A2_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$T_A2_OUT/state/px-k3d/scope-k3d-px.tsv"
  t_shim_log_reset
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$jdk" "${pins_env[@]}" "${dist_pins[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" --backend k8s \
    -e PX_PHERF=1 build
  t_equals build_on_px_k3d_imports_both_images "0 1 1 2" "$T_STATUS $(grep -c \
    '^build --tag px-harness:' "$T_SHIM_LOG") $(grep -c '^build --tag px-pherf:' \
    "$T_SHIM_LOG") $(grep -c '^k3d image import -c px ' "$T_SHIM_LOG")"
  t_shim_log_reset
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$jdk" "${pins_env[@]}" "${dist_pins[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" --backend k8s \
    -e PX_K8S_CONTEXT=remote-u3 build
  t_equals build_on_a_remote_context_names_push "0 1 0 yes" "$T_STATUS $(grep -c \
    '^build --tag px-harness:' "$T_SHIM_LOG") $(grep -c '^k3d ' "$T_SHIM_LOG") $(t_flag \
    'promote the images with px-cluster push BUNDLE' "$T_STDERR")"
  rm -rf "$T_A2_OUT/state/px-k3d"
  : >"$T_A2_MVN_LOG"
  t_shim_log_reset
  t_a2 "PX_HOME=$home" "PX_BUILD_JAVA_HOME=$jdk" "${pins_env[@]}" "${dist_pins[@]}" \
    PX_FAKE_MVN_PRODUCT=target/jHiccup.jar "$T_BASH" "$home/bin/px-cluster" build
  t_equals build_without_the_tier_stages_no_pherf_image "0 no 1 0" "$T_STATUS $(t_flag \
    phoenix-pherf-distributed "$(cat "$T_A2_MVN_LOG")") $(grep -c '^build --tag px-harness:' \
    "$T_SHIM_LOG") $(grep -c '^build --tag px-pherf:' "$T_SHIM_LOG")"
  # Verify that cluster bring-up when the px-pherf image is missing reports the missing image tag
  # and directs the operator to stage the pherf image.
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  printf 'px-pherf:hbase-2.6.7-profile-2.6\n' >"$T_A2_STATE/missing-images"
  t_a2 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$T_A2_OUT/deploy/a2"
  rm -f "$T_A2_STATE/missing-images"
  t_equals image_check_hint_names_the_pherf_image "6 yes yes" "$T_STATUS $(t_flag \
    'no image px-pherf:hbase-2.6.7-profile-2.6' "$T_STDERR") $(t_flag \
    'run px-cluster build --cluster a2 -e PX_PHERF=1' "$T_STDERR")"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  rm -rf "$module"
}

## @description  Verifies px-pherf start, status, stop, and run subcommands against the deployment
##               fakes and the stub controller (RQ-247, RQ-251, RQ-253).
## @return       Records test assertions. Exit 0.
t_group_h3_pherf() {
  local dir="$T_A2_OUT/deploy/a2" suite="$T_H3/one.suite" calls got
  local quorum='zk1.h3.invalid:2181'
  printf '# H3: px-pherf\n'
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  printf 'suite: h3-one\ntimeout: 60\nclass: validation\n\ntest: one\n' >"$suite"
  printf '  driver: %s\n  args: 0\n' "$T_WORK/drivers/exit-code.sh" >>"$suite"
  # When no cluster is recorded, start brings the cluster up with the tier enabled and leaves it
  # running.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation start
  got="$(cat "$T_SHIM_LOG")"
  t_equals px_pherf_start_brings_the_cluster_up "0 yes yes" "$T_STATUS $(t_flag \
    ' up --no-start' "$got") $(t_flag 'start pherf-agent-0 pherf-agent-1 pherf-controller-0' \
    "$got")"
  t_equals pherf_start_from_nothing_leaves_the_cluster_up "yes no yes" "$(t_exists \
    "$T_A2_OUT/state/scope-compose-a2") $(t_flag ' down --volumes' "$got") $(t_flag \
    "PX_PHERF='1'" "$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)")"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  t_h3_pherf --cluster a2 --class validation run
  t_equals px_pherf_run_needs_a_suite 2 "$T_STATUS"
  t_h3_pherf --cluster a2 bogus
  t_equals px_pherf_refuses_an_unknown_subcommand 2 "$T_STATUS"
  # Verify that a cluster rendered without the distributed tier enabled rejects a tier start
  # request because the pherf role group is absent.
  t_gen --class validation --cluster a2
  t_a2_provision "$dir"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$dir" --no-wait
  t_h3_pherf --cluster a2 --class validation start
  t_equals px_pherf_start_needs_the_tier_in_the_render "6 yes" "$T_STATUS $(t_flag \
    'was rendered without the tier: run px-cluster down, then px-pherf start' "$T_STDERR")"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  # Verify RQ-253: in the validation class, starting the tier brings up the role group and waits
  # for two agents to register as IDLE.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$dir" --no-wait
  t_h3_reset
  t_h3_agents agents-1 pherf-agent-0:IDLE
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation start
  calls="$(cat "$T_A2_STATE/pherf/calls.log" 2>/dev/null)"
  t_equals px_pherf_validation_start_waits_for_two "0 yes 2" "$T_STATUS $(t_flag \
    'start pherf-agent-0 pherf-agent-1 pherf-controller-0' "$(cat "$T_SHIM_LOG")")\
 $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "IDLE"' | grep -c .)"
  t_equals px_pherf_start_waits_for_late_registration "2 GET /v1/agents" \
    "$(printf '%s\n' "$calls" | grep -c .) $(printf '%s\n' "$calls" | sort -u)"
  t_contains px_pherf_reaches_the_api_inside_the_controller \
    'exec -T --index=1 pherf-controller-0 bash -c' "$(cat "$T_SHIM_LOG")"
  t_h3_pherf --cluster a2 status
  t_equals px_pherf_status_reports_the_group_and_the_agents "0 pherf 3 3 up|2" "$T_STATUS\
 $(printf '%s\n' "$T_STDOUT" | sed -n 1p | tr '\t' ' ')|$(printf '%s\n' "$T_STDOUT" |
      grep -c '^pherf-agent-[01]')"
  t_shim_log_reset
  t_h3_pherf --cluster a2 stop
  t_equals px_pherf_stop_stops_the_group "0 0" "$T_STATUS $(grep '|pherf|on-demand|' \
    "$T_A2_STATE/instances.txt" | grep -c '|running|')"
  # Verify that an agent that fails to register within the readiness window causes a timeout, and
  # that a failed start automatically stops the on-demand group unless --keep is specified.
  t_h3_agents agents pherf-agent-0:IDLE
  rm -f "$T_A2_STATE/pherf/agents-1"
  t_h3_pherf --cluster a2 --class validation -e PX_TIMEOUT_READY=2 start
  t_equals px_pherf_start_times_out_on_a_missing_agent "4 yes" "$T_STATUS $(t_flag \
    pherf-agent-1 "$T_STDERR")"
  t_equals px_pherf_failed_start_stops_the_tier 0 "$(grep '|pherf|on-demand|' \
    "$T_A2_STATE/instances.txt" | grep -c '|running|')"
  t_h3_pherf --cluster a2 --class validation --keep -e PX_TIMEOUT_READY=2 start
  t_equals px_pherf_failed_start_keeps_the_tier_with_keep "4 3" "$T_STATUS $(grep \
    '|pherf|on-demand|' "$T_A2_STATE/instances.txt" | grep -c '|running|')"
  # Because only a reset clears a FAULTED agent and the harness never issues a reset, the readiness
  # wait terminates immediately upon observing a faulted agent in the first agent list rather than
  # polling until the timeout bound expires.
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:FAULTED
  t_h3_pherf --cluster a2 --class validation -e PX_TIMEOUT_READY=600 start
  t_equals px_pherf_start_fails_at_once_on_a_faulted_agent "3 yes 1" "$T_STATUS $(t_flag \
    'pherf-agent-1 is FAULTED, and only a reset clears it: the fixture fault' "$T_STDERR")\
 $(grep -c '^GET /v1/agents$' "$T_A2_STATE/pherf/calls.log")"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  # Verify RQ-251: in the measurement class, starting the tier brings up one controller and waits
  # for three agents to reach the IDLE state.
  t_gen --class measurement --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$dir" --no-wait
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE pherf-agent-2:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class measurement start
  t_equals px_pherf_start_starts_the_group_and_waits_for_three_idle "0 yes yes 3" "$T_STATUS\
 $(t_flag 'start pherf-agent-0 pherf-agent-1 pherf-agent-2 pherf-controller-0' \
    "$(cat "$T_SHIM_LOG")") $(t_flag "memory_fit${PX_TAB}ok" "$T_STDERR") $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$2 == "IDLE"' | grep -c .)"
  t_equals px_pherf_start_records_the_planned_group_start "pherf-agent pherf-controller" \
    "$(awk -F '\t' '$2 == "group_start" && $5 == "group pherf" { print $3 }' \
      "$T_A2_OUT/state/planned-px-a2.tsv" 2>/dev/null | LC_ALL=C sort -u | paste -sd ' ' -)"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  # Verify external target lifecycle management: start brings up the standalone tier deployment,
  # and stop tears the entire deployment down.
  t_gen --class validation --cluster a2 -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e "PX_PHERF_ZOOKEEPER=$quorum"
  t_a2_provision "$dir"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e "PX_PHERF_ZOOKEEPER=$quorum" start
  t_equals px_pherf_external_start_brings_the_tier_up "0 yes 3" "$T_STATUS $(t_flag \
    ' up --no-start' "$(cat "$T_SHIM_LOG")") $(grep -c '|running|' "$T_A2_STATE/instances.txt")"
  t_h3_pherf --cluster a2 --class validation -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e "PX_PHERF_ZOOKEEPER=$quorum" stop
  t_equals px_pherf_stop_removes_the_external_tier "0 0 no" "$T_STATUS $(grep -c . \
    "$T_A2_STATE/instances.txt") $(t_exists "$T_A2_OUT/state/scope-compose-a2")"
  # Verify RQ-247: px-pherf run provisions the cluster with the distributed tier, executes the
  # suite, and tears down every instance at session end unless --keep is specified to retain them.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation run "$suite"
  got="$(cat "$T_SHIM_LOG")"
  t_equals px_pherf_run_tears_the_tier_down_by_default "0 yes yes 0 no" "$T_STATUS $(t_flag \
    ' up --no-start' "$got") $(t_flag 'start pherf-agent-0 pherf-agent-1 pherf-controller-0' \
    "$got") $(grep -c . "$T_A2_STATE/instances.txt") $(t_exists \
    "$T_A2_OUT/state/scope-compose-a2")"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_h3_pherf --cluster a2 --class validation --keep run "$suite"
  t_equals px_pherf_run_keep_retains_the_tier "0 3 yes" "$T_STATUS $(grep '|pherf|on-demand|' \
    "$T_A2_STATE/instances.txt" | grep -c '|running|') $(t_exists \
    "$T_A2_OUT/state/scope-compose-a2")"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  # Because run tears down only resources it brought up, executing against an already running
  # cluster stops the tier it started while leaving the cluster itself up.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$dir" --no-wait
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation run "$suite" --only one
  got="$(cat "$T_SHIM_LOG")"
  t_equals pherf_run_on_an_up_cluster_stops_only_the_tier "0 yes 0 no" "$T_STATUS $(t_exists \
    "$T_A2_OUT/state/scope-compose-a2") $(grep '|pherf|on-demand|' "$T_A2_STATE/instances.txt" |
    grep -c '|running|') $(t_flag ' down --volumes' "$got")"
  t_h3_pherf --cluster a2 --class validation run "$suite" --only one --continue-on-failure -n
  t_equals px_pherf_run_passes_the_selection_options "0 yes" "$T_STATUS $(t_flag \
    "--only one --continue-on-failure $suite" "$(printf '%s\n' "$T_STDOUT" |
    grep "^plan${PX_TAB}suite${PX_TAB}px-suite ")")"
  # Verify that the check and suite plan rows record the exact argument vectors of the underlying
  # invocations, and that the suite row omits the -n dry-run flag.
  t_equals px_pherf_dry_run_plans_the_argv_of_the_run \
    "px-suite --cluster a2 --class validation --only one --continue-on-failure $suite" \
    "$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "plan" && $2 == "suite" { print $3 }')"
  t_equals px_pherf_dry_run_plans_the_argv_of_the_check \
    "px-suite --cluster a2 --class validation --dry-run --only one --continue-on-failure $suite" \
    "$(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "plan" && $2 == "check" { print $3 }')"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  # Verify that a suite rejected by px-suite starts no container instances because px-pherf run
  # validates the execution plan before provisioning. Likewise, if px-suite aborts before reaching
  # its own teardown phase (simulated here by a pre-existing session directory), the exit hook of
  # px-pherf run still tears down the cluster it provisioned.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 --class validation run "$T_H3/nosuch.suite"
  t_equals px_pherf_run_of_a_missing_suite_starts_nothing "6 no no" "$T_STATUS $(t_flag \
    ' up --no-start' "$(cat "$T_SHIM_LOG")") $(t_exists "$T_A2_OUT/state/scope-compose-a2")"
  mkdir -p "$T_A2_OUT/sessions/h3-taken"
  t_h3 PX_SESSION_ID=h3-taken "$T_BASH" "$T_BIN/px-pherf" --cluster a2 --class validation run \
    "$suite"
  t_equals px_pherf_run_removes_its_cluster_after_an_early_suite_exit "1 yes 0 no" "$T_STATUS\
 $(t_flag ' up --no-start' "$(cat "$T_SHIM_LOG")") $(grep -c . "$T_A2_STATE/instances.txt")\
 $(t_exists "$T_A2_OUT/state/scope-compose-a2")"
  rm -rf "$T_A2_OUT/sessions/h3-taken"
  # Receiving a termination signal while run is bringing the cluster up still tears down that
  # cluster.
  t_gen --class validation --cluster a2 -e PX_PHERF=1
  t_a2_provision "$dir"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_shim_log_reset
  : >"$T_H3/term.mark"
  t_h3 "$T_BASH" "$T_WORK/probe/term-when.sh" "$T_A2_OUT/state" scope-compose-a2 \
    "$T_H3/term.mark" "$T_BIN/px-pherf" --cluster a2 --class validation run "$suite"
  t_equals px_pherf_run_tears_down_after_a_term_during_up "143 yes 0 no" "$T_STATUS $(t_flag \
    ' up --no-start' "$(cat "$T_SHIM_LOG")") $(grep -c . "$T_A2_STATE/instances.txt")\
 $(t_exists "$T_A2_OUT/state/scope-compose-a2")"
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

## @description  Verifies that the tier is treated as a load generator rather than a cluster daemon,
##               so its log lines are not counted as daemon errors and no collector targets it.
## @return       Records test assertions. Exit 0.
t_group_h3_tier() {
  local dir run
  printf '# H3: the tier is the load generator, not a cluster daemon\n'
  t_a5_state_reset
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|\n' c0ffee00a5f1 pherf-controller \
    px-selftest-pherf-controller-1 running 'Up 3 minutes (healthy)' px-pherf:selftest \
    pherf-controller-0 1 0 pherf on-demand >>"$T_A5_STATE/instances.txt"
  dir="$(t_check_bundle tier)"
  t_rows 9 pherf-controller-0 pherf-controller 0 pherf on-demand 1024 512 0 17 \
    >>"$dir/cluster/services.tsv"
  awk 'BEGIN { for (i = 0; i < 50; i++) print "2026-11-20 17:00:00 ERROR [x] e" }' \
    >"$T_A5_STATE/logs-c0ffee00a5f1.txt"
  t_cluster_checks "$dir"
  t_equals tier_logs_are_kept_not_counted "PASS yes 50" "$(t_severity_of "$dir" cluster.log)\
 $(t_flag 'load generator, kept and not counted: pherf-controller/0' "$(t_detail_of "$dir" \
    cluster.log)") $(grep -c ERROR "$dir/logs/instance/pherf-controller-0.log" 2>/dev/null)"
  rm -f "$T_A5_STATE/logs-c0ffee00a5f1.txt"
  run="$PX_OUT/checks/tier"
  t_run env "PX_RUN_DIR=$run" "$T_BASH" "$T_H3/targets.sh"
  t_equals tier_is_no_collector_target "0 regionserver|pherf-controller" "$T_STATUS $(printf \
    '%s\n' "$T_STDOUT" | cut -f 1 | paste -sd '|' -)"
  t_a5_state_reset
}

## @description  Materializes the captured responses of a real controller run into a directory
##               (directory): agents, test, run, statuses, cancel, end, 400, 409, and files/.
## @return       Exit 0.
t_fleet_fixture() {
  local dir=$1
  mkdir -p "$dir/files/drivers/pherf/results_HDR/slots"
  # These response payloads were captured from a live execution via px-cluster exec
  # pherf-controller 0 -- curl against the docker/pherf image in the validation class with two
  # agents. The load step and the query step executed px_fleet_scenario.xml from suites/pherf using
  # -schemaFile slice/px_fleet_schema.sql -l followed by -disableSchemaApply -q at 40/s for
  # 15000 ms across 4 threads and 2000 rows. The ABORTED status payload was captured from a run of
  # the same plan with an unknown column referenced in the query. Every payload reproduces the live
  # capture byte for byte, except that each per-slot histogram log is truncated after its header
  # and first two intervals. Lines exceeding 98 columns are folded with a trailing backslash and
  # reassembled by t_unfold. Because Apache RAT rejects JSON, TSV, or log data files that lack a
  # license header, these fixtures are materialized at runtime rather than stored as static files
  # in the source tree.
  t_unfold >"$dir/agents.json" <<'H3_EOF'
{"agents":[{"id":"pherf-agent-0","incarnation":"ea05be49-0302-45a1-af7e-f3b5c56315ed","state":"ID\
LE","phase":"IDLE","step":0,"registeredMs":1795442520704,"lastSeenMs":1795442539603,"offsetMs":20\
,"delayMs":45},{"id":"pherf-agent-1","incarnation":"9e5dcc8c-da82-4048-9097-974322988ebc","state"\
:"IDLE","phase":"IDLE","step":0,"registeredMs":1795442520656,"lastSeenMs":1795442539599,"offsetMs\
":20,"delayMs":44}]}
H3_EOF
  t_unfold >"$dir/test.json" <<'H3_EOF'
{"id":"t-20261123T140324434Z-2","state":"OPEN","createdMs":1795442604434,"runs":[]}
H3_EOF
  t_unfold >"$dir/run.json" <<'H3_EOF'
{"runId":"20261123T140324866Z-2","state":"RUNNING","step":0,"steps":[{"index":0,"state":"RUNNING"\
,"startAtMs":0,"slots":[{"identity":{"slot":0,"seed":7126372246263655726,"offset":0,"prefix":"p00\
0"},"agent":"pherf-agent-0","state":"ASSIGNED","share":{"durationMs":15000,"threads":2,"rate":20,\
"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"counters":{}},{"identity":{"slot":1,"seed":226\
8977538148885555,"offset":1000,"prefix":"p001"},"agent":"pherf-agent-1","state":"ASSIGNED","share\
":{"durationMs":15000,"threads":2,"rate":20,"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"co\
unters":{}}]}]}
H3_EOF
  t_unfold >"$dir/status-running.json" <<'H3_EOF'
{"runId":"20261123T140324866Z-2","state":"RUNNING","step":0,"steps":[{"index":0,"state":"RUNNING"\
,"startAtMs":1795442609022,"skewMs":6,"slots":[{"identity":{"slot":0,"seed":7126372246263655726,"\
offset":0,"prefix":"p000"},"agent":"pherf-agent-0","state":"RUNNING","share":{"durationMs":15000,\
"threads":2,"rate":20,"rows":1000},"heldSeq":26,"finalSeq":0,"samples":542,"counters":{"query:px_\
fleet:lookup|steady":271,"response:px_fleet:lookup|steady":271},"offsetMs":-1,"delayMs":3,"startA\
tMs":1795442609021,"dispatchMs":1795442609022},{"identity":{"slot":1,"seed":2268977538148885555,"\
offset":1000,"prefix":"p001"},"agent":"pherf-agent-1","state":"RUNNING","share":{"durationMs":150\
00,"threads":2,"rate":20,"rows":1000},"heldSeq":26,"finalSeq":0,"samples":542,"counters":{"query:\
px_fleet:lookup|steady":271,"response:px_fleet:lookup|steady":271},"offsetMs":-1,"delayMs":3,"sta\
rtAtMs":1795442609021,"dispatchMs":1795442609022}]}]}
H3_EOF
  t_unfold >"$dir/status.json" <<'H3_EOF'
{"runId":"20261123T140324866Z-2","state":"COMPLETE","step":0,"steps":[{"index":0,"state":"COMPLET\
E","startAtMs":1795442609022,"skewMs":6,"slots":[{"identity":{"slot":0,"seed":7126372246263655726\
,"offset":0,"prefix":"p000"},"agent":"pherf-agent-0","state":"DONE","share":{"durationMs":15000,"\
threads":2,"rate":20,"rows":1000},"heldSeq":30,"finalSeq":30,"samples":600,"counters":{"query:px_\
fleet:lookup|steady":300,"response:px_fleet:lookup|steady":300},"offsetMs":-1,"delayMs":3,"startA\
tMs":1795442609021,"dispatchMs":1795442609022},{"identity":{"slot":1,"seed":2268977538148885555,"\
offset":1000,"prefix":"p001"},"agent":"pherf-agent-1","state":"DONE","share":{"durationMs":15000,\
"threads":2,"rate":20,"rows":1000},"heldSeq":30,"finalSeq":30,"samples":600,"counters":{"query:px\
_fleet:lookup|steady":300,"response:px_fleet:lookup|steady":300},"offsetMs":-1,"delayMs":3,"start\
AtMs":1795442609021,"dispatchMs":1795442609022}]}]}
H3_EOF
  t_unfold >"$dir/status-aborted.json" <<'H3_EOF'
{"runId":"20261123T140408062Z-3","state":"ABORTED","reason":"agent pherf-agent-1: predicate.parse\
 /tmp/pherf-agent-pherf-agent-1-17400994812333432315/slice/px_fleet_scenario.xml scenario px_flee\
t querySet 1 query 1: the statement does not parse after the placeholder substitution: ERROR 602 \
(42P00): Syntax error. Missing \"LPAREN\" at line 1, column 44.","step":0,"steps":[{"index":0,"st\
ate":"FAILED","startAtMs":0,"slots":[{"identity":{"slot":0,"seed":7126372246263655726,"offset":0,\
"prefix":"p000"},"agent":"pherf-agent-0","state":"FAILED","error":"agent pherf-agent-0: predicate\
.parse /tmp/pherf-agent-pherf-agent-0-6714635016495254443/slice/px_fleet_scenario.xml scenario px\
_fleet querySet 1 query 1: the statement does not parse after the placeholder substitution: ERROR\
 602 (42P00): Syntax error. Missing \"LPAREN\" at line 1, column 44.","share":{"durationMs":5000,\
"threads":1,"rate":20,"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"counters":{},"offsetMs":\
-1,"delayMs":1},{"identity":{"slot":1,"seed":2268977538148885555,"offset":1000,"prefix":"p001"},"\
agent":"pherf-agent-1","state":"FAILED","error":"agent pherf-agent-1: predicate.parse /tmp/pherf-\
agent-pherf-agent-1-17400994812333432315/slice/px_fleet_scenario.xml scenario px_fleet querySet 1\
 query 1: the statement does not parse after the placeholder substitution: ERROR 602 (42P00): Syn\
tax error. Missing \"LPAREN\" at line 1, column 44.","share":{"durationMs":5000,"threads":1,"rate\
":20,"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"counters":{},"offsetMs":-1,"delayMs":2}]}\
]}
H3_EOF
  t_unfold >"$dir/cancel.json" <<'H3_EOF'
{"runId":"20261123T140420950Z-4","state":"CANCELLED","reason":"cancelled","step":0,"steps":[{"ind\
ex":0,"state":"RUNNING","startAtMs":1795442665100,"slots":[{"identity":{"slot":0,"seed":712637224\
6263655726,"offset":0,"prefix":"p000"},"agent":"pherf-agent-0","state":"CANCELLED","share":{"dura\
tionMs":15000,"threads":2,"rate":20,"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"counters":\
{},"offsetMs":-1,"delayMs":4,"startAtMs":1795442665099},{"identity":{"slot":1,"seed":226897753814\
8885555,"offset":1000,"prefix":"p001"},"agent":"pherf-agent-1","state":"CANCELLED","share":{"dura\
tionMs":15000,"threads":2,"rate":20,"rows":1000},"heldSeq":0,"finalSeq":0,"samples":0,"counters":\
{},"offsetMs":-1,"delayMs":3,"startAtMs":1795442665099}]}]}
H3_EOF
  t_unfold >"$dir/end.json" <<'H3_EOF'
{"id":"t-20261123T140420524Z-5","state":"ENDED","createdMs":1795442660524,"endedMs":1795442664834\
,"runs":["20261123T140420950Z-4"]}
H3_EOF
  t_unfold >"$dir/err-400.json" <<'H3_EOF'
{"error":"bad_request","message":"steps[0].args[2]: -label belongs to the agent; the agent sets i\
t"}
H3_EOF
  t_unfold >"$dir/err-409.json" <<'H3_EOF'
{"error":"conflict","message":"step 0 needs 3 agents but 2 are free"}
H3_EOF
  t_unfold >"$dir/files/manifest.json" <<'H3_EOF'
{
  "bundle_format_version": 1,
  "run": {
    "id": "20261123T140324866Z-2",
    "class": "validation",
    "result": "ADVISORY",
    "state": "COMPLETE",
    "seed": 8070,
    "started_ms": 1795442604866,
    "ended_ms": 1795442624863
  },
  "quiet_host": {
    "tags": [
      "noisy-host"
    ]
  }
}
H3_EOF
  t_unfold >"$dir/files/status.json" <<'H3_EOF'
{
  "runId": "20261123T140324866Z-2",
  "state": "COMPLETE",
  "step": 0,
  "steps": [
    {
      "index": 0,
      "state": "COMPLETE",
      "startAtMs": 1795442609022,
      "skewMs": 6,
      "slots": [
        {
          "identity": {
            "slot": 0,
            "seed": 7126372246263655726,
            "offset": 0,
            "prefix": "p000"
          },
          "agent": "pherf-agent-0",
          "state": "DONE",
          "share": {
            "durationMs": 15000,
            "threads": 2,
            "rate": 20,
            "rows": 1000
          },
          "heldSeq": 30,
          "finalSeq": 30,
          "samples": 600,
          "counters": {
            "query:px_fleet:lookup|steady": 300,
            "response:px_fleet:lookup|steady": 300
          },
          "offsetMs": -1,
          "delayMs": 3,
          "startAtMs": 1795442609021,
          "dispatchMs": 1795442609022
        },
        {
          "identity": {
            "slot": 1,
            "seed": 2268977538148885555,
            "offset": 1000,
            "prefix": "p001"
          },
          "agent": "pherf-agent-1",
          "state": "DONE",
          "share": {
            "durationMs": 15000,
            "threads": 2,
            "rate": 20,
            "rows": 1000
          },
          "heldSeq": 30,
          "finalSeq": 30,
          "samples": 600,
          "counters": {
            "query:px_fleet:lookup|steady": 300,
            "response:px_fleet:lookup|steady": 300
          },
          "offsetMs": -1,
          "delayMs": 3,
          "startAtMs": 1795442609021,
          "dispatchMs": 1795442609022
        }
      ]
    }
  ]
}
H3_EOF
  t_unfold >"$dir/files/drivers/pherf/results_HDR/LATENCY_run.json" <<'H3_EOF'
{
  "format": "pherf-latency",
  "version": 1,
  "label": "run",
  "startMs": 1795442606544,
  "endMs": 1795442624099,
  "recorder": {
    "lowestUs": 1,
    "highestUs": 600000000,
    "significantDigits": 3
  },
  "hlog": "LATENCY_run.hlog",
  "histograms": [
    {
      "operation": "query:px_fleet:lookup",
      "phase": "steady",
      "count": 600,
      "minUs": 2418,
      "maxUs": 1375231,
      "meanUs": 13790.59,
      "p50Us": 4311,
      "p90Us": 6519,
      "p99Us": 23087,
      "p999Us": 1375231
    },
    {
      "operation": "response:px_fleet:lookup",
      "phase": "steady",
      "count": 600,
      "minUs": 2932,
      "maxUs": 1376255,
      "meanUs": 74359.42833333333,
      "p50Us": 5863,
      "p90Us": 23407,
      "p99Us": 1234943,
      "p999Us": 1376255
    }
  ],
  "schedules": [
    {
      "scenario": "px_fleet",
      "querySet": 1,
      "operations": [
        "query:px_fleet:lookup"
      ],
      "concurrency": 2,
      "phase": "done",
      "stopReason": "duration",
      "startMs": 1795442609024,
      "endMs": 1795442624025,
      "durationMs": 15000,
      "lastTakeMs": 1795442623976,
      "targetRate": 0.0,
      "queueDepth": 0,
      "inFlight": 0,
      "driftUs": 0,
      "scheduled": 300,
      "completed": 300,
      "late": 0,
      "dropped": 0,
      "aborted": 0,
      "pending": 0,
      "maxQueueDepth": 2,
      "maxInFlight": 2,
      "maxDriftUs": 1278041,
      "step": 0,
      "slot": 0,
      "agent": "pherf-agent-0"
    },
    {
      "scenario": "px_fleet",
      "querySet": 1,
      "operations": [
        "query:px_fleet:lookup"
      ],
      "concurrency": 2,
      "phase": "done",
      "stopReason": "duration",
      "startMs": 1795442609024,
      "endMs": 1795442624026,
      "durationMs": 15000,
      "lastTakeMs": 1795442623976,
      "targetRate": 0.0,
      "queueDepth": 0,
      "inFlight": 0,
      "driftUs": 0,
      "scheduled": 300,
      "completed": 300,
      "late": 0,
      "dropped": 0,
      "aborted": 0,
      "pending": 0,
      "maxQueueDepth": 2,
      "maxInFlight": 2,
      "maxDriftUs": 1260319,
      "step": 0,
      "slot": 1,
      "agent": "pherf-agent-1"
    }
  ],
  "fleet": {
    "runId": "20261123T140324866Z-2",
    "seed": 8070,
    "runClass": "validation",
    "contaminated": true,
    "state": "COMPLETE",
    "steps": [
      {
        "step": 0,
        "state": "COMPLETE",
        "startAtMs": 1795442609022,
        "skewMs": 6,
        "startMs": 1795442606544,
        "endMs": 1795442624099,
        "slots": [
          {
            "slot": 0,
            "agent": "pherf-agent-0",
            "incarnation": "ea05be49-0302-45a1-af7e-f3b5c56315ed",
            "seed": 7126372246263655726,
            "offset": 0,
            "prefix": "p000",
            "share": {
              "durationMs": 15000,
              "threads": 2,
              "rate": 20,
              "rows": 1000
            },
            "state": "DONE",
            "offsetMs": -1,
            "delayMs": 3,
            "startAtMs": 1795442609021,
            "dispatchMs": 1795442609022,
            "startLagMs": 0,
            "frames": 30,
            "samples": 600,
            "counts": {
              "query:px_fleet:lookup|steady": 300,
              "response:px_fleet:lookup|steady": 300
            },
            "intervals": "slots/0-0.hlog"
          },
          {
            "slot": 1,
            "agent": "pherf-agent-1",
            "incarnation": "9e5dcc8c-da82-4048-9097-974322988ebc",
            "seed": 2268977538148885555,
            "offset": 1000,
            "prefix": "p001",
            "share": {
              "durationMs": 15000,
              "threads": 2,
              "rate": 20,
              "rows": 1000
            },
            "state": "DONE",
            "offsetMs": -1,
            "delayMs": 3,
            "startAtMs": 1795442609021,
            "dispatchMs": 1795442609022,
            "startLagMs": 0,
            "frames": 30,
            "samples": 600,
            "counts": {
              "query:px_fleet:lookup|steady": 300,
              "response:px_fleet:lookup|steady": 300
            },
            "intervals": "slots/0-1.hlog"
          }
        ]
      }
    ]
  }
}
H3_EOF
  t_unfold >"$dir/files/drivers/pherf/results_HDR/LATENCY_run.hlog" <<'H3_EOF'
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERA\
TION|PHASE]
#[Histogram log format version 1.3]
#[StartTime: 1795442606.544 (seconds since epoch), Mon Nov 23 14:03:26 UTC 2026]
#[BaseTime: 1795442606.544 (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
Tag=query:px_fleet:lookup|steady,0.000,17.555,1375231.000,HISTFAAAAb942jVSu04kQQzsrXb3MBqNRqvVaHU\
ChMQKEZ0ITieECEhOl9wHIMTvkBAiQr6EmICvICYE/uCqqod9tNt22S67fXD3uEkpn6f2yYtc6di9/E1Xn83wdYyHFU5xhkNM\
GJGQokNGT71QQwfseOzTtsYGW1BMcvQY+B9BMUhPmAXueRQQPUA2XpLMmd+O12JDpisSg0tklutCdeULF58RSsTfJrJdwiotK\
dHCO9NPCGJoq7mheMJY0yjBw1plGEtEYZBYzOZldA5njyH61kO2CPfXiLJKUIaLi0NaesqumJaaCeuAOTJzRK4EqC0FRkSqTF\
qZqALV2apDsSTYSxFVWciy+55cBWPcIFxalZmcCWtr1QnFH0sfuTH2mNv7CRdmrHnQFkPN7idzgnvV3UppryRY12jQUdyYh+l\
WQ4yAtiAaZdemnbBMQaDSWijL+CW/35fH4BjS5mvULka/CXcnDPACahFjLXCPH9w+L8+MKbwAkzYrefk04pkxO8cUVh65qAX/\
6DlTuSMGHpruT7/6CcN+UZ7Ses5tviRi5EJf4AZiUqjf4nlF5QTXeFKhjx7vgbct7ld4/UPAb/r+A4TnGyw=
Tag=response:px_fleet:lookup|steady,0.000,17.555,1376255.000,HISTFAAAAf942jVTPWsVQRS9c+bujsPyWB6P\
EIIGCSGGEIKISAgprKKlRYoU4m8IYqOFhaBFsJIUIv4ASRX8BRbWYin+gPSCpLAQcs6dzds3c8/c73tm99bb04VZfmntlyeZt\
K1/f2QP/zTF3zswXCYM2ELls4RVKvCA0Li2POMQ1C3jJp7QawEcARSFCqP0NW4VK+hQfRZhBR0RQQb/le5FwAno5Z3kwOXuqg\
VzOcvDqJtOMmQJY0w3SfVEJ2NXOmV5KAEPWUUUbGGSi7KwhvowWmQqnl19+AAXRuZm7IYplBSBXTNY66JltnDLco+KanXgqn2\
brES/ms2CAOnkOUOfe3OxMnhMGpzE0G69tZgO/TURLVFR2b4NWKfp24Zr7IPLOvTL6INy2SpmLugMziwdd8DqQWGO4naD4WPw\
W6LdSkJGWhrLG42/7KP47OJug8oSq8SdD5g3FrJeABFag7lwrNG1KLC4+BKcdrgdaIleK6RF6ReY+17EzDGxVmIHFWtEY3AyR\
5ujo85a2ogQ2ieIV21G86bzRV1l1AYPl8CbjP8Jz3He4wI4G/HD8VsknBX8Ar4VfEr46fia8MWFP2cc41/CU3wEnuEEDR3hA3\
CAk8Tkpwn38Jofy/uE+3jFQ4AX2MW7xHYO+RzzS3nM0LvYYSs7lNvYxDr2uLbJwz4HuQLnATCe
H3_EOF
  t_unfold >"$dir/files/drivers/pherf/results_HDR/VERDICT_run.tsv" <<'H3_EOF'
pherf.health.attainment	PASS	duration	pherf	lowest attainment 100.0% (step 0 slot 0 (agent pherf-\
agent-0) scenario px_fleet query set 1: 300 of 300 completed, taken in 14952 ms of a 15000 ms win\
dow); minimum 90%
pherf.health.saturation	ADVISORY	duration	pherf	validation run: FAIL reads as ADVISORY; peak lag \
1278.041 ms (step 0 slot 0 (agent pherf-agent-0) scenario px_fleet query set 1), above 1000 ms; q\
ueue peak 2 of 2, at its bound; in-flight peak 2 of 2: the workers could not keep up; late 0, dro\
pped 0
pherf.health.client_pool	PASS	count	pherf	step 0 slot 0 (agent pherf-agent-0): no task waited: ta\
sks 1, peak demand 1 of 10 threads
pherf.health.tail_latency	ADVISORY	duration	pherf	validation run: FAIL reads as ADVISORY; 1 of 2 \
keys over the bounds p99 1000 ms, p99.9 5000 ms; worst response:px_fleet:lookup p99 1234.943 ms, \
p99.9 1376.255 ms
pherf.health.read_skew	SKIP	share	pherf	step 0 slot 0 (agent pherf-agent-0): no source: pherf.ver\
dict.serverJmx is empty
pherf.fleet.start_skew	PASS	duration	pherf	highest start skew at most 6 ms (step 0), within the 5\
0 ms bound
pherf.fleet.telemetry	PASS	count	pherf	the held counts of 2 completed slots equal the counts that\
 their agents reported
pherf.fleet.agents	PASS	count	pherf	run COMPLETE with 2 slots done
H3_EOF
  t_unfold >"$dir/files/drivers/pherf/results_HDR/slots/0-0.hlog" <<'H3_EOF'
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERA\
TION|PHASE]
#[pherf-distributed run 20261123T140324866Z-2 step 0 slot 0 agent pherf-agent-0, agent clock]
#[Histogram log format version 1.3]
#[StartTime: 1795442609.549 (seconds since epoch), Mon Nov 23 14:03:29 UTC 2026]
#[BaseTime: 1795442609.549 (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
Tag=query:px_fleet:lookup|steady,0.000,1.000,1375231.000,HISTFAAAAGF42pNpmSzMwMDgygABzFCaEUQoH3Zj\
sP8AEVisxdTNwhTKtJCRyZRJm8mQiZNJk0mciSmaSZ1JmEkSyORkus/ExAxkKjNZMk1lBFLcTI5Mu5mYnrMCVS0sZIplAgCl1\
g31
Tag=response:px_fleet:lookup|steady,0.000,1.000,1376255.000,HISTFAAAAGV42pNpmSzMwMAQxgABzFCaEUQoH\
3ZjsP8AEXhownSRm2m9FtNTPqbbHEzNbEyHWZl6WZiuMzF9Z2J6zsT0kYnpJhPTSkam44wg8iAj01oYeRgmspSRKZ4plCmWyR\
dIgzAAsNsZgA==
H3_EOF
  t_unfold >"$dir/files/drivers/pherf/results_HDR/slots/0-1.hlog" <<'H3_EOF'
#[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits, Tag=OPERA\
TION|PHASE]
#[pherf-distributed run 20261123T140324866Z-2 step 0 slot 1 agent pherf-agent-1, agent clock]
#[Histogram log format version 1.3]
#[StartTime: 1795442609.554 (seconds since epoch), Mon Nov 23 14:03:29 UTC 2026]
#[BaseTime: 1795442609.554 (seconds since epoch)]
"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"
Tag=query:px_fleet:lookup|steady,0.000,1.000,1354751.000,HISTFAAAAGJ42pNpmSzMwMDgygABzFCaEUQoH3Zj\
sP8AEThtwGTOJM8kyyTJFMtkz8QkzsTExMrEwCTIxMnkzmTIxMv0lpHpLiNQajEjkzCTM1M/I9NbFqbdzCDGbCamyTlM4UwAo\
XMOzg==
Tag=response:px_fleet:lookup|steady,0.000,1.000,1357823.000,HISTFAAAAG142pNpmSzMwMAQxgABzFCaEUQoH\
3ZjsP8AEThvxuTO9FuP6bYA00wuptesTLdZmbazML1mYvrKxPQSTALZFxmZNjMybWRk2s7ItJ6RaT8j00owOZGR6Sgj00xGpl\
5GplCmeDAOZIplAgDLoxnb
H3_EOF
}

## @description  Stages a single stub controller response file (name, HTTP status, body file) in
##               the A5 fake state directory.
## @return       Exit 0.
t_h3_reply() {
  mkdir -p "$(dirname "$T_A5_STATE/pherf/$1")"
  { printf '%s\n' "$2" && cat "$3"; } >"$T_A5_STATE/pherf/$1"
}

## @description  Resets the A5 fakes with pherf-controller 0 and the stub controller, seeding the
##               fixture agents, a two-poll RUNNING-to-COMPLETE run, and the fixture bundle index.
## @return       Exit 0.
t_h3_driver_reset() {
  local fx="$T_H3/fx" path sep=""
  t_a5_state_reset
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|\n' c0ffee00a5f1 pherf-controller \
    px-selftest-pherf-controller-1 running 'Up 3 minutes (healthy)' px-pherf:selftest \
    pherf-controller-0 1 0 pherf on-demand >>"$T_A5_STATE/instances.txt"
  mkdir -p "$T_A5_STATE/pherf"
  printf '404\n{"error": "not_found", "message": "no such path"}\n' \
    >"$T_A5_STATE/pherf/not-found"
  t_h3_reply agents 200 "$fx/agents.json"
  t_h3_reply test 201 "$fx/test.json"
  t_h3_reply run 201 "$fx/run.json"
  t_h3_reply status-running 200 "$fx/status-running.json"
  t_h3_reply status 200 "$fx/status.json"
  t_h3_reply cancel 200 "$fx/cancel.json"
  t_h3_reply end 200 "$fx/end.json"
  t_h3_reply report 200 "$fx/files/drivers/pherf/results_HDR/LATENCY_run.json"
  printf '2\n' >"$T_A5_STATE/pherf/running"
  {
    printf '200\n{"files":['
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      t_h3_reply "files/$path" 200 "$fx/files/$path"
      printf '%s{"path":"%s","bytes":%s}' "$sep" "$path" "$(wc -c <"$fx/files/$path" | tr -d ' ')"
      sep=,
    done <<EOF
$(cd "$fx/files" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)
EOF
    printf ']}\n'
  } >"$T_A5_STATE/pherf/bundle.new"
  mv "$T_A5_STATE/pherf/bundle.new" "$T_A5_STATE/pherf/bundle"
}

## @description  Executes a single lifecycle verb of the pherf-distributed driver (verb, bundle,
##               argument...) against the A5 fakes and the stub controller, matching px-run.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_h3_verb() {
  local verb=$1 run=$2
  shift 2
  t_a5 "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_RUN_DIR=$run" "PX_RUN_ID=${run##*/}" \
    PX_DRIVER_NAME=pherf-distributed "PX_DRIVER_DIR=$run/drivers/pherf-distributed" \
    "PX_VERB=$verb" PX_RECORD_SOURCE=driver:pherf-distributed PX_RUN_CLASS=validation \
    PX_BACKEND=compose PX_SCOPE_ID=px-selftest "PX_PHOENIX_HOME=$T_A5/phoenix" \
    "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$PX_HOME/drivers/pherf-distributed/driver.sh" \
    "$verb" "$@"
}

## @description  Outputs the HTTP requests recorded by the stub controller as METHOD PATH lines.
## @return       Writes the recorded request lines to stdout. Exit 0.
t_h3_calls() { cat "$T_A5_STATE/pherf/calls.log" 2>/dev/null; }

## @description  Configures the stub controller to serve a CANCELLED run manifest in each bundle.
## @return       Exit 0.
t_h3_cancelled() {
  local file="$T_H3/cancelled.json" bytes
  sed 's/"state": "COMPLETE"/"state": "CANCELLED"/' "$T_H3/fx/files/manifest.json" >"$file"
  t_h3_reply files/manifest.json 200 "$file"
  bytes="$(wc -c <"$file" | tr -d ' ')"
  sed "s|\"path\":\"manifest.json\",\"bytes\":[0-9]*|\"path\":\"manifest.json\",\"bytes\":$bytes|" \
    "$T_A5_STATE/pherf/bundle" >"$T_H3/bundle.new"
  mv "$T_H3/bundle.new" "$T_A5_STATE/pherf/bundle"
}

## @description  Executes the driver run verb (poll interval, call, argument...) against the stub
##               controller and delivers TERM 1 s after the first request matching call.
## @return       Sets T_H3_RUN, T_H3_STATE (last run state in tests.tsv), and T_H3_IN_TIME. Exit 0.
t_h3_term() {
  local interval=$1 call=$2 pid start ms
  shift 2
  T_H3_RUN="$(t_a5_bundle)"
  (
    exec env "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
      "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_A5_STATE" \
      "PX_POLL_INTERVAL_S=$interval" PX_CLUSTER_ID=selftest "PX_RUN_DIR=$T_H3_RUN" \
      "PX_RUN_ID=${T_H3_RUN##*/}" PX_DRIVER_NAME=pherf-distributed \
      "PX_DRIVER_DIR=$T_H3_RUN/drivers/pherf-distributed" PX_VERB=run \
      PX_RECORD_SOURCE=driver:pherf-distributed PX_RUN_CLASS=validation PX_BACKEND=compose \
      PX_SCOPE_ID=px-selftest "$T_BASH" "$PX_HOME/drivers/pherf-distributed/driver.sh" run "$@"
  ) </dev/null >"$T_H3/term.out" 2>&1 &
  pid=$!
  px_wait_until 30 0.2 grep -q "$call\$" "$T_A5_STATE/pherf/calls.log" 2>/dev/null || true
  sleep 1
  start="$(px_epoch_ms)"
  kill -s TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null
  ms=$(($(px_epoch_ms) - start))
  # Because px-run escalates to KILL 10 s after delivering TERM, record yes when the process exits
  # within that window or report the elapsed duration otherwise.
  T_H3_IN_TIME=yes
  [ "$ms" -lt 10000 ] || T_H3_IN_TIME="no, $ms ms"
  T_H3_STATE="$(awk -F '\t' '$1 == "run" { s = $4 } END { print s }' \
    "$T_H3_RUN/drivers/pherf-distributed/tests.tsv" 2>/dev/null)"
}

## @description  Verifies the pherf-distributed workload driver across its lifecycle verbs and
##               fault conditions against the stub controller (RQ-250, RQ-253).
## @return       Records test assertions. Exit 0.
t_group_h3_driver() {
  local run dir fx="$T_H3/fx" scenario schema bad="" name got calls plan pid line
  local args=()
  printf '# H3: the pherf-distributed driver\n'
  scenario="$PX_HOME/suites/pherf/px_fleet_scenario.xml"
  schema="$PX_HOME/suites/pherf/px_fleet_schema.sql"
  args=(--scenario "$scenario" --schema "$schema" --agents 2 --rows 2000 --rate 40
    --duration-ms 15000 --threads 4)
  # Verify RQ-250: execute a complete end-to-end driver lifecycle run through px-run.
  t_h3_driver_reset
  t_shim_log_reset
  t_a5 "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_PHOENIX_HOME=$T_A5/phoenix" \
    "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$T_BIN/px-run" --class validation \
    -e PX_SCOPE_ID=px-selftest pherf-distributed -- "${args[@]}"
  run="$(t_bundle)"
  dir="$run/drivers/pherf-distributed"
  calls="$(t_h3_calls)"
  t_equals driver_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$run")"
  t_equals driver_posts_the_plan_then_the_run "POST /v1/tests|POST /v1/tests/t-*-2/runs|\
GET /v1/tests/t-*-2/runs/*-2/status" "$(printf '%s\n' "$calls" | awk '
    /^POST \/v1\/tests$/ { n++ } n == 2 && !/^GET \/v1\/agents$/ && c < 3 {
      sub(/t-[0-9TZ]*-/, "t-*-"); sub(/runs\/[0-9TZ]*-/, "runs/*-"); printf "%s%s", s, $0
      s = "|"; c++ }')"
  plan="$(t_h3_calls | grep -n '^POST /v1/tests$' | sed -n 2p | cut -d: -f1)"
  t_equals driver_plan_is_the_posted_body yes "$(cmp -s "$dir/plan-run.json" \
    "$T_A5_STATE/pherf/body-$plan.json" && echo yes || echo no)"
  t_lib t_a5_json_get "$dir/plan-run.json" /steps/0/documents/px_fleet_schema.sql
  got="$T_STDOUT"
  t_equals driver_plan_carries_the_documents_and_shares "8070 validation true 2 -disableSchemaApply\
 -q 40 15000 4 2000 px_fleet_scenario.xml yes yes" "$(for line in /seed /runClass \
    /properties/pherf.verdict.noisyHost /steps/0/agents /steps/0/args/0 /steps/0/args/1 \
    /steps/0/rate /steps/0/durationMs /steps/0/threads /steps/0/rows /steps/0/scenario; do
    t_lib t_a5_json_get "$dir/plan-run.json" "$line"
    printf '%s ' "$T_STDOUT"
  done)$(t_lib t_a5_json_type "$dir/plan-run.json" /steps/0/documents/px_fleet_profile.xml
    t_flag string "$T_STDOUT") $(t_flag 'CREATE TABLE IF NOT EXISTS PX_PHERF.FLEET' "$got")"
  t_lib t_a5_json_get "$dir/plan-load.json" /steps/0/args/1
  t_equals driver_load_applies_the_schema_through_the_agents \
    "slice/px_fleet_schema.sql -l no" "$T_STDOUT $(t_lib t_a5_json_get "$dir/plan-load.json" \
    /steps/0/args/2; printf '%s' "$T_STDOUT") $(t_lib t_a5_json_type "$dir/plan-load.json" \
    /steps/0/rate; [ "$T_STATUS" = 6 ] && echo no || echo yes)"
  t_equals driver_polls_until_complete "3 COMPLETE" "$(printf '%s\n' "$calls" | grep -c \
    '/runs/[0-9TZ]*-2/status$') $(awk -F '\t' 'NR == 2 { print $5 }' "$dir/run.tsv")"
  t_equals driver_fetches_the_report yes "$(cmp -s "$dir/report.json" \
    "$fx/files/drivers/pherf/results_HDR/LATENCY_run.json" && echo yes || echo no)"
  bad=""
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    cmp -s "$fx/files/$name" "$dir/controller/$name" || bad="$bad $name"
  done <<EOF
$(cd "$fx/files" && find . -type f | sed 's|^\./||')
EOF
  t_equals driver_copies_the_bundle_byte_for_byte "7 " "$(find "$dir/controller" -type f |
    grep -c .) $bad"
  t_equals driver_copies_the_load_bundle 7 "$(find "$dir/load" -type f | grep -c .)"
  t_equals driver_bundle_validates 0 "$(t_validate "$run" >/dev/null; printf '%s' "$T_STATUS")"
  t_equals driver_appends_the_eight_fleet_rows \
    "$(cat "$fx/files/drivers/pherf/results_HDR/VERDICT_run.tsv")" \
    "$(awk -F '\t' '$4 == "pherf"' "$run/verdicts.tsv")"
  t_equals driver_records_the_merged_report "600 23087 600 1234943 PASS" \
    "$(t_h2_value "$run" op@query_px_fleet_lookup pherf.operations)\
 $(t_h2_value "$run" op@query_px_fleet_lookup pherf.latency.p99_us)\
 $(t_h2_value "$run" steady@pherf pherf.operations)\
 $(t_h2_value "$run" op@response_px_fleet_lookup pherf.latency.p99_us)\
 $(t_severity_of "$run" pherf.latency)"
  # Verify RQ-253: confirm agent registration evidence, per-slot workload shares for each agent,
  # and the merged latency histogram metrics.
  t_equals driver_records_agent_registration "yes PASS yes" "$(cmp -s "$fx/agents.json" \
    "$dir/agents.json" && echo yes || echo no) $(t_severity_of "$run" pherf.agents) $(t_flag \
    '2 idle agents: pherf-agent-0 pherf-agent-1' "$(t_detail_of "$run" pherf.agents)")"
  t_equals driver_records_per_slot_shares "20 2 1000 600 0|20 2 1000 600 0" "$(for line in \
    slot@0.0 slot@0.1; do
    for name in rate threads rows samples start_lag_ms; do
      printf '%s ' "$(t_h2_value "$run" "$line" "pherf.slot.$name")"
    done
    printf '|'
  done | sed 's/ |/|/g; s/|$//')"
  t_equals driver_records_one_schedule_per_slot "300 300 1278041 1260319 600" "$(for line in \
    schedule@px_fleet.1.0.0 schedule@px_fleet.1.0.1; do
    printf '%s ' "$(t_h2_value "$run" "$line" pherf.schedule.scheduled)"
  done)$(for line in schedule@px_fleet.1.0.0 schedule@px_fleet.1.0.1; do
    printf '%s ' "$(t_h2_value "$run" "$line" pherf.schedule.max_drift_us)"
  done)$(t_h2_value "$run" steady@pherf pherf.schedule.scheduled)"
  t_equals driver_records_the_fleet_agents_and_skew "2 6" "$(t_h2_value "$run" steady@pherf \
    pherf.fleet.agents) $(t_h2_value "$run" steady@pherf pherf.fleet.start_skew_ms)"
  t_equals driver_ends_each_test "yes yes 2" "$(t_flag \
    'POST /v1/tests/t-20261123T140324434Z-1/end' "$calls") $(t_flag \
    'POST /v1/tests/t-20261123T140324434Z-2/end' "$calls") $(awk -F '\t' \
    'NR > 1 { t[$2] = 1 } END { n = 0; for (k in t) n++; print n }' "$dir/tests.tsv")"
  t_contains driver_reaches_the_api_inside_the_controller \
    'exec -T --index=1 pherf-controller-0 bash -c' "$(cat "$T_SHIM_LOG")"
  # Verify that tampering with the merged histogram log causes the report verb latency check to
  # fail.
  sed '1s/highest 600000000/highest 3600000000/' \
    "$dir/controller/drivers/pherf/results_HDR/LATENCY_run.hlog" >"$T_H3/hlog.new"
  mv "$T_H3/hlog.new" "$dir/controller/drivers/pherf/results_HDR/LATENCY_run.hlog"
  t_h3_verb report "$run" "${args[@]}"
  t_equals driver_checks_the_merged_hlog FAIL "$(t_severity_of "$run" pherf.latency)"
  # Verify RQ-250 fault handling: even when a controller run ends in the FAILED state, the driver
  # still downloads and preserves the controller bundle.
  t_h3_driver_reset
  sed 's/"state":"COMPLETE"/"state":"FAILED","reason":"agent pherf-agent-1: boom"/' \
    "$fx/status.json" >"$T_H3/failed.json"
  t_h3_reply status 200 "$T_H3/failed.json"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals failed_run_still_copies_its_bundle "1 FAIL 7 PASS" "$T_STATUS $(t_severity_of \
    "$run" pherf.execution) $(find "$run/drivers/pherf-distributed/controller" -type f |
    grep -c .) $(t_severity_of "$run" pherf.bundle)"
  # Verify that the terminal failure reason of a failed run is still recorded in the execution
  # verdict even if downloading the controller bundle also fails.
  t_h3_driver_reset
  t_h3_reply status 200 "$T_H3/failed.json"
  rm -f "$T_A5_STATE/pherf/bundle"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals failed_run_keeps_its_end_reason_after_a_failed_copy "1 FAIL yes" "$T_STATUS\
 $(t_severity_of "$run" pherf.bundle) $(t_flag 'ended FAILED: agent pherf-agent-1: boom' \
    "$(t_detail_of "$run" pherf.execution)")"
  t_h3_driver_reset
  t_h3_reply status 200 "$fx/status-aborted.json"
  run="$(t_a5_bundle)"
  t_h3_verb prepare "$run" "${args[@]}"
  t_equals aborted_run_is_a_gate_failure "1 FAIL yes" "$T_STATUS $(t_severity_of "$run" \
    pherf.gate) $(t_flag 'the statement does not parse' "$(t_detail_of "$run" pherf.gate)")"
  t_h3_driver_reset
  : >"$T_A5_STATE/pherf/unreachable"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals unreachable_controller_is_an_anomaly "3 ERROR" "$T_STATUS $(t_severity_of "$run" \
    pherf.agents)"
  t_h3_driver_reset
  printf '{"agents":[{"id":"pherf-agent-0","incarnation":"i0","state":"IDLE"}]}\n' \
    >"$T_H3/one-agent.json"
  t_h3_reply agents 200 "$T_H3/one-agent.json"
  run="$(t_a5_bundle)"
  PX_TIMEOUT_VERB=1 t_h3_verb run "$run" "${args[@]}"
  t_equals missing_agents_is_an_anomaly "3 ERROR yes" "$T_STATUS $(t_severity_of "$run" \
    pherf.agents) $(t_flag pherf-agent-1:absent "$T_STDERR")"
  t_h3_driver_reset
  t_h3_reply run 409 "$fx/err-409.json"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals conflict_is_an_anomaly_that_names_the_cause "3 ERROR yes" "$T_STATUS $(t_severity_of \
    "$run" pherf.agents) $(t_flag 'step 0 needs 3 agents but 2 are free' "$(t_detail_of "$run" \
    pherf.agents)")"
  t_h3_driver_reset
  t_h3_reply test 400 "$fx/err-400.json"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals plan_refusal_names_the_json_path "1 FAIL yes" "$T_STATUS $(t_severity_of "$run" \
    pherf.plan) $(t_flag 'steps[0].args[2]' "$(t_detail_of "$run" pherf.plan)")"
  # Verify bundle integrity checks: every path in the bundle listing must stay within the target
  # directory and match its declared byte count.
  t_h3_driver_reset
  t_h3_reply files/../../escaped.json 200 "$fx/files/manifest.json"
  sed 's|"path":"manifest.json"|"path":"../../escaped.json"|' "$T_A5_STATE/pherf/bundle" \
    >"$T_H3/bundle.new"
  mv "$T_H3/bundle.new" "$T_A5_STATE/pherf/bundle"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals escaping_listing_path_is_refused "1 FAIL no" "$T_STATUS $(t_severity_of "$run" \
    pherf.bundle) $(t_exists "$run/drivers/escaped.json")"
  t_h3_driver_reset
  sed 's|"path":"manifest.json","bytes":\([0-9]*\)|"path":"manifest.json","bytes":9\1|' \
    "$T_A5_STATE/pherf/bundle" >"$T_H3/bundle.new"
  mv "$T_H3/bundle.new" "$T_A5_STATE/pherf/bundle"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals short_file_fails_the_bundle "1 FAIL no" "$T_STATUS $(t_severity_of "$run" \
    pherf.bundle) $(t_exists "$run/drivers/pherf-distributed/controller/manifest.json")"
  t_h3_driver_reset
  sed 's/"count": 600,/"count": 601,/' "$fx/files/drivers/pherf/results_HDR/LATENCY_run.json" \
    >"$T_H3/report.json"
  t_h3_reply report 200 "$T_H3/report.json"
  run="$(t_a5_bundle)"
  t_h3_verb run "$run" "${args[@]}"
  t_equals report_mismatch_fails_the_bundle "1 FAIL yes" "$T_STATUS $(t_severity_of "$run" \
    pherf.bundle) $(t_flag 'report.json differs' "$(t_detail_of "$run" pherf.bundle)")"
  # Verify that delivering TERM from px-run cancels the active controller run and retrieves its
  # CANCELLED bundle, and that a poll interval longer than the 10 s grace period that px-run waits
  # after sending TERM does not delay the signal handler.
  t_h3_driver_reset
  printf '1000000\n' >"$T_A5_STATE/pherf/running"
  t_h3_cancelled
  t_h3_term 12 /status "${args[@]}"
  run=$T_H3_RUN
  dir="$run/drivers/pherf-distributed"
  t_equals term_cancels_the_run "1" "$(t_h3_calls | grep -c '^POST /v1/tests/.*/runs/.*/cancel$')"
  t_lib t_a5_json_get "$dir/controller/manifest.json" /run/state
  t_equals term_copies_the_cancelled_bundle "7 CANCELLED PASS CANCELLED yes" "$(find \
    "$dir/controller" -type f 2>/dev/null | grep -c .) $T_STDOUT $(t_severity_of "$run" \
    pherf.bundle) $T_H3_STATE $T_H3_IN_TIME"
  # Verify that when the TERM budget interrupts bundle retrieval, every completed file is retained,
  # the file in transfer remains on disk with a .part suffix, and the pherf.bundle verdict row
  # records the partial copy.
  t_h3_driver_reset
  printf '1000000\n' >"$T_A5_STATE/pherf/running"
  printf '20\n' >"$T_A5_STATE/pherf/file-sleep"
  t_h3_term 0.2 /status "${args[@]}"
  run=$T_H3_RUN
  dir="$run/drivers/pherf-distributed"
  t_equals term_records_a_partial_copy_at_the_budget "FAIL yes 1 CANCELLED yes" \
    "$(t_severity_of "$run" pherf.bundle) $(t_flag 'the TERM budget' "$(t_detail_of "$run" \
    pherf.bundle)") $(find "$dir/controller" -name '*.part' 2>/dev/null | grep -c .) $(
    printf '%s' "$T_H3_STATE $T_H3_IN_TIME")"
  # Verify that delivering TERM after the run verb has already downloaded the bundle (simulated
  # here during the test end request) preserves that bundle copy and its single pherf.bundle row.
  t_h3_driver_reset
  printf '3\n' >"$T_A5_STATE/pherf/end-sleep"
  t_h3_term 0.2 /end "${args[@]}"
  run=$T_H3_RUN
  dir="$run/drivers/pherf-distributed"
  t_equals term_after_the_copy_keeps_it "7 0 1 PASS yes" "$(find "$dir/controller" -type f \
    2>/dev/null | grep -c .) $(find "$dir/controller" -name '*.part' 2>/dev/null | grep -c .) $(
    t_h3_calls | grep -c '^GET /v1/tests/.*/bundle$') $(awk -F '\t' \
    '$1 == "pherf.bundle" { printf "%s", $2 }' "$run/verdicts.tsv") $T_H3_IN_TIME"
  # Verify that delivering TERM during the post-load catalog query in prepare terminates the Java
  # process inside client 0 without issuing a cancellation request, since the controller run has
  # already ended.
  t_h3_driver_reset
  : >"$T_A5_STATE/catalog-sleeps"
  t_shim_log_reset
  run="$(t_a5_bundle)"
  (
    exec env "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
      "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_A5_STATE" PX_POLL_INTERVAL_S=0.2 \
      PX_COLLECTORS=none "PX_JDK_ROOT=$T_A5/jdk" PX_JDK=fake "PX_CONF_DIR=$T_A5/conf" \
      PX_LOG_DIR=/var/log/px PX_CLUSTER_ID=selftest PX_PORT_REGIONSERVER_RPC_0=16020 \
      "PX_RUN_DIR=$run" "PX_RUN_ID=${run##*/}" PX_DRIVER_NAME=pherf-distributed \
      "PX_DRIVER_DIR=$run/drivers/pherf-distributed" PX_VERB=prepare \
      PX_RECORD_SOURCE=driver:pherf-distributed PX_RUN_CLASS=validation PX_BACKEND=compose \
      PX_SCOPE_ID=px-selftest "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" \
      "$T_BASH" "$PX_HOME/drivers/pherf-distributed/driver.sh" prepare "${args[@]}"
  ) </dev/null >"$T_H3/term.out" 2>&1 &
  pid=$!
  px_wait_until 30 0.2 grep -q 'PhoenixRuntime' "$T_A5_STATE/java.log" || true
  sleep 1
  kill -s TERM "$pid" 2>/dev/null || true
  got=0
  wait "$pid" 2>/dev/null || got=$?
  rm -f "$T_A5_STATE/catalog-sleeps"
  t_equals term_in_prepare_stops_the_java_of_client_0 "1 yes 0" "$got $(t_stopped_after_java)\
 $(t_h3_calls | grep -c '/cancel$')"
  # Verify that the restore verb ends every controller test recorded in the bundle and drops the
  # workload tables through client 0.
  t_h3_driver_reset
  run="$(t_a5_bundle)"
  t_h3_verb prepare "$run" "${args[@]}"
  t_h3_verb run "$run" "${args[@]}"
  : >"$T_A5_STATE/pherf/calls.log"
  : >"$T_A5_STATE/java.log"
  t_h3_verb restore "$run" "${args[@]}"
  t_equals restore_ends_every_test "0 2 yes" "$T_STATUS $(t_h3_calls | grep -c \
    '^POST /v1/tests/t-[0-9TZ]*-[12]/end$') $(t_flag 'DROP TABLE IF EXISTS "PX_PHERF"."FLEET"' \
    "$(cat "$run/drivers/pherf-distributed/restore.sql" 2>/dev/null)")"
  # Verify that an unreachable controller during the restore verb is reported as a cluster anomaly
  # with exit code 3.
  : >"$T_A5_STATE/pherf/unreachable"
  t_h3_verb restore "$run" "${args[@]}"
  rm -f "$T_A5_STATE/pherf/unreachable"
  t_equals restore_without_the_controller_is_an_anomaly 3 "$T_STATUS"
  run="$(t_a5_bundle)"
  mkdir -p "$run/cluster"
  printf 'PX_PHERF=1\nPX_PHERF_TARGET=external\nPX_PHERF_AGENT_COUNT=2\n' \
    >"$run/cluster/topology.env"
  t_shim_log_reset
  t_h3_verb restore "$run" --scenario "$scenario" --schema "$schema"
  t_equals external_restore_skips_the_tables "0 SKIP no" "$T_STATUS $(t_severity_of "$run" \
    pherf.tables) $(t_flag ' client-' "$(cat "$T_SHIM_LOG")")"
  # Verify argument validation: pass-through flags that collide with driver-managed options in any
  # spelling, scenario profile paths containing subdirectories, duplicate document basenames, and
  # multiple schema files are all rejected.
  run="$(t_a5_bundle)"
  bad=""
  for line in -l --q -label=x -scenarioFile --schemaFile=y -disableSchemaApply -runDurationMs \
    --threadCount=2 -targetRate -rowCountOverride; do
    t_h3_verb validate "$run" "${args[@]}" -- "$line"
    [ "$T_STATUS" = 2 ] || bad="$bad $line:$T_STATUS"
  done
  t_h3_verb validate "$run" "${args[@]}" -- -allowUnknownTags
  t_equals driver_refuses_an_owned_option_in_any_spelling " 0" "$bad $T_STATUS"
  mkdir -p "$T_H3/docs/sub"
  sed 's|profile="px_fleet_profile.xml"|profile="sub/p.xml"|' "$scenario" >"$T_H3/docs/a.xml"
  cp "$schema" "$T_H3/docs/a.xml.sql"
  cp "$scenario" "$T_H3/docs/px_fleet_schema.sql"
  t_h3_verb validate "$run" --agents 2 --scenario "$T_H3/docs/a.xml"
  bad="$T_STATUS"
  t_h3_verb validate "$run" --agents 2 --scenario "$scenario" --schema \
    "$T_H3/docs/px_fleet_schema.sql" --schema "$schema"
  bad="$bad $T_STATUS"
  t_h3_verb validate "$run" --agents 2 --scenario "$scenario" --schema "$schema" --schema \
    "$T_H3/docs/a.xml.sql"
  t_equals driver_refuses_a_profile_path_a_repeated_name_and_two_schemas "2 2 2" \
    "$bad $T_STATUS"
  t_h3_verb validate "$run" "${args[@]}" --weights 1,2,3
  t_equals driver_refuses_weights_of_another_agent_count 2 "$T_STATUS"
  t_h3_verb validate "$run" "${args[@]}"
  t_equals driver_validate_passes "0 PASS" "$T_STATUS $(t_severity_of "$run" \
    driver.workload_valid)"
  t_a5_state_reset
}

## @description  Verifies the fleet measurement suite, confirming that it plans and executes three
##               replicates and produces a validated run bundle for each (RQ-254).
## @return       Records test assertions. Exit 0.
t_group_h3_suite() {
  local suite="$PX_HOME/suites/fleet.suite" session dir id runs="" bad="" n=0
  printf '# H3: the fleet suite\n'
  t_run env PX_SESSION_ID=h3-plan "$T_BASH" "$T_BIN/px-suite" -n "$suite"
  t_equals fleet_suite_plans_three_replicates "0 r1 r2 r3" "$T_STATUS $(t_plan_ids "$T_STDOUT")"
  t_equals fleet_suite_is_a_measurement_suite measurement "$(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' 'NR == 1 { print $6 }')"
  t_h3_driver_reset
  printf '%s\n' PX_PHERF=1 PX_PHERF_TARGET=harness PX_PHERF_AGENT_COUNT=2 \
    >>"$T_WORK/deploy-selftest/topology.env"
  t_a5 "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_PHOENIX_HOME=$T_A5/phoenix" \
    "PX_HBASE_HOME=$T_A5/hbase" PX_SESSION_ID=h3-fleet PX_SCOPE_ID=px-selftest \
    "$T_BASH" "$T_BIN/px-suite" --class validation "$suite"
  t_equals fleet_suite_runs_every_replicate "0 3" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$3 == "PASS" || $3 == "ADVISORY"' | grep -c .)"
  session="$PX_OUT/sessions/h3-fleet"
  for id in r1 r2 r3; do
    dir="$(sed -n 1p "$session/tests/$id/stdout.txt" 2>/dev/null)"
    [ -d "$dir" ] || { bad="$bad $id:no-bundle" && continue; }
    t_validate "$dir" >/dev/null
    [ "$T_STATUS" = 0 ] || bad="$bad $id:validate-$T_STATUS"
    runs="$runs$(awk -F '\t' '$1 == "run" && $3 != "" { print $3 }' \
      "$dir/drivers/pherf-distributed/tests.tsv" 2>/dev/null)$PX_NL"
    n=$((n + 1))
  done
  t_equals fleet_suite_writes_one_bundle_per_replicate "3  3" "$n $bad $(printf '%s' "$runs" |
    sort -u | grep -c .)"
  t_a5_state_reset
}

# Verifies that a disrupted instance that exits (even with exit status 0) or becomes absent
# terminates the recovery gate immediately during the readiness barrier, at a region count poll,
# and after a full region count.
## @description  Verifies that an exited (including exit 0) or absent instance terminates the
##               recovery gate immediately in the barrier, at a region poll, and at a full count.
## @return       Records test assertions. Exit 0.
t_group_h3_gate() {
  local run start elapsed got flag
  printf '# H2b follow-up: an exited instance ends the recovery gate\n'
  t_a5_state_reset
  printf '%s\n' 12 8 >"$T_A5_STATE/region-count"
  : >"$T_A5_STATE/start-then-exit-0"
  start="$(px_epoch_ms)"
  t_a5_run phase-kill -e PX_TIMEOUT_READY=120 --disrupt 'phase:phase@kill,kill,regionserver,0'
  elapsed=$((($(px_epoch_ms) - start) / 1000))
  run="$(t_bundle)"
  t_equals exited_instance_fails_the_recovery_at_once "yes fail yes" "$([ "$elapsed" -lt 30 ] &&
    echo yes || echo "no, ${elapsed}s") $(awk -F '\t' '$1 == "recover" { print $5 }' \
    "$run/run-record.tsv") $(t_flag exited "$T_STDERR")"
  t_a5_state_reset
  printf '%s\n' 12 8 >"$T_A5_STATE/region-count"
  : >"$T_A5_STATE/start-then-absent"
  start="$(px_epoch_ms)"
  t_a5_run phase-kill -e PX_TIMEOUT_READY=120 --disrupt 'phase:phase@kill,kill,regionserver,0'
  elapsed=$((($(px_epoch_ms) - start) / 1000))
  run="$(t_bundle)"
  t_equals absent_instance_fails_the_recovery_at_once "yes fail yes" "$([ "$elapsed" -lt 30 ] &&
    echo yes || echo "no, ${elapsed}s") $(awk -F '\t' '$1 == "recover" { print $5 }' \
    "$run/run-record.tsv") $(t_flag 'is absent, so it cannot recover' "$T_STDERR")"
  # A region count read that begins while the instance is running can return the full count after
  # the instance stops, and the instance check after the full count must fail the recovery gate.
  t_a5_state_reset
  : >"$T_A5_STATE/count-then-absent"
  t_a5_run phase-kill -e PX_TIMEOUT_READY=120 --disrupt 'phase:phase@kill,kill,regionserver,0'
  run="$(t_bundle)"
  t_equals full_count_of_an_absent_instance_fails_the_recovery "fail yes" "$(awk -F '\t' \
    '$1 == "recover" { print $5 }' "$run/run-record.tsv") $(t_flag \
    'is absent, so it cannot recover' "$T_STDERR")"
  # H3b: after the readiness barrier observes the restarted instance as ready, the instance exits
  # with status 1, exits with status 0, or becomes absent while hbase-master remains unready.
  # Because the barrier continues waiting only on instances not yet seen ready, only re-checking
  # the disrupted instance between barrier polls terminates the recovery gate immediately.
  got=""
  for flag in start-then-exit-1 start-then-exit-0 start-then-absent; do
    t_a5_state_reset
    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee00a503 hbase-master \
      px-selftest-hbase-master-1 running 'Up 3 minutes (healthy)' phoenix-harness:selftest \
      hbase-master 1 0 >>"$T_A5_STATE/instances.txt"
    printf 'hbase-master\n' >"$T_A5_STATE/start-then-peer-unready"
    printf '3\n' >"$T_A5_STATE/next-after"
    : >"$T_A5_STATE/$flag"
    t_a5_run phase-kill -e PX_TIMEOUT_READY=20 --disrupt 'phase:phase@kill,kill,regionserver,0'
    run="$(t_bundle)"
    got="$got|${flag#start-then-} $(awk -F '\t' '$1 == "recover" { print $5 }' \
      "$run/run-record.tsv") $(printf '%s\n' "$T_STDERR" | grep -cE \
      'cannot recover|terminated while the barrier waited') $(printf '%s\n' "$T_STDERR" |
      grep -c 'readiness barrier expired')"
  done
  t_equals exit_after_the_barrier_saw_ready_fails_the_recovery_at_once \
    "|exit-1 fail 1 0|exit-0 fail 1 0|absent fail 1 0" "$got"
  t_a5_state_reset
}

## @description  Extracts the driver arguments declared for a single test (suite file, test id),
##               emitting one argument per line exactly as written in the suite file.
## @return       Writes the driver arguments to stdout. Exit 0.
t_d7_args() {
  awk -v id="$2" '
    /^test:/ { on = ($2 == id) }
    on && /^ *args:/ { for (i = 2; i <= NF; i++) print $i }' "$1"
}

## @description  Verifies the plan and class of the three D7 fleet suites and validates each test
##               definition for the pherf-distributed driver (RQ-255 to RQ-257).
## @return       Records test assertions. Exit 0.
t_group_d7_suites() {
  local name id run line suite plans="" classes="" bad="" args=()
  printf '# D7: the end-to-end suites of the fleet\n'
  for name in fleet-rww fleet-rules fleet-ab; do
    t_run env PX_SESSION_ID=d7-plan "$T_BASH" "$T_BIN/px-suite" -n \
      "$PX_HOME/suites/$name.suite"
    plans="$plans|$T_STATUS $(t_plan_ids "$T_STDOUT")"
    classes="$classes $(printf '%s\n' "$T_STDOUT" | awk -F '\t' 'NR == 1 { print $6 }')"
  done
  t_equals d7_suites_plan_their_tests "|0 r1 r2|0 rules|0 a1 b1 a2 b2 a3 b3" "$plans"
  t_equals d7_suites_have_their_classes " validation validation measurement" "$classes"
  # Because the driver reads the agent count from the run topology, each validation check writes a
  # topology of 2 agents. The driver resolves each relative path against the suite's directory.
  for name in fleet-rww:r1 fleet-rww:r2 fleet-rules:rules fleet-ab:a1 fleet-ab:b1; do
    id=${name#*:}
    suite="$PX_HOME/suites/${name%%:*}.suite"
    args=()
    while IFS= read -r line; do
      [ -z "$line" ] || args+=("$line")
    done <<EOF
$(t_d7_args "$suite" "$id")
EOF
    run="$(t_a5_bundle)"
    mkdir -p "$run/cluster"
    printf 'PX_PHERF=1\nPX_PHERF_TARGET=harness\nPX_PHERF_AGENT_COUNT=2\n' \
      >"$run/cluster/topology.env"
    PX_SUITE_FILE=$suite t_h3_verb validate "$run" ${args[@]+"${args[@]}"}
    [ "$T_STATUS" = 0 ] && [ "$(t_severity_of "$run" driver.workload_valid)" = PASS ] &&
      [ "${#args[@]}" -gt 0 ] || bad="$bad $name:$T_STATUS"
  done
  t_equals d7_tests_are_valid_driver_definitions "" "$bad"
  t_a5_state_reset
}

# ---------------------------------------------------------------------------
# K1: Kubernetes backend and manifest generator verification
# ---------------------------------------------------------------------------
# Every test case runs against the fake test harnesses: the stateful fake kubectl shim, the fake
# JDK, and the fake A5 probe. No test case contacts a live cluster or the network. The fake kubectl
# shim records every invocation in both the fake runtime log and the K1 round log so that the final
# test group can audit every call issued across the entire round.

## @description  Materializes the K1 round fixtures, creating its working directories and the
##               hostile driver script for the Kubernetes backend.
## @return       Populates the K1 fixture directories. Exit 0.
t_fixtures_k1() {
  T_K1="$T_WORK/k1"
  T_K8S_STATE="$T_K1/state"
  T_K1_A5="$T_K1/a5-state"
  mkdir -p "$T_K1/out" "$T_K8S_STATE" "$T_K1_A5"
  T_K8S_OUT="$(px_realpath "$T_K1/out")"
  : >"$T_K1/calls.log"
  cat >"$T_WORK/drivers/kubectl-var.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A hostile driver. It reads the kubectl variable of the Kubernetes backend
# and calls kubectl by that path. The backend keeps the variable out of a
# child, so it must arrive empty here.
printf 'kubectl variable [%s]\n' "${PX_KUBECTL_BIN:-}"
[ -n "${PX_KUBECTL_BIN:-}" ] || exit 0
command "$PX_KUBECTL_BIN" get pods -l hostile-driver
DRIVER_EOF
  chmod +x "$T_WORK/drivers/kubectl-var.sh"
}

## @description  Executes a command (NAME=VALUE..., command...) against the fake kubectl shim using
##               the Kubernetes backend, the fake context, and the K1 round output root.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_k8s() {
  t_with_shim "PX_KUBECTL_BIN=$T_SHIM_DIR/kubectl" "PX_FAKE_KUBECTL_LOG=$T_K1/calls.log" \
    "PX_FAKE_FORWARD_PIDS=$T_WORK/forward.pids" \
    PX_BACKEND=k8s PX_K8S_CONTEXT=fake "PX_FAKE_STATE_DIR=$T_K8S_STATE" "PX_OUT=$T_K8S_OUT" \
    PX_CLUSTER_ID=selftest PX_POLL_INTERVAL_S=0.2 "PX_REGION_MOVER_CMD=$T_MOVER_CMD" "$@"
}

## @description  Invokes a single backend contract function (function, argument...) against the
##               Kubernetes backend.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_k8s_sb() { t_k8s "$T_BASH" "$T_WORK/probe/sb.sh" "$@"; }

## @description  Runs the Kubernetes manifest generator (option...) preconfigured with the k3d
##               target environment file.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_mgen() {
  t_k8s "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --config "$PX_HOME/k8s/env/k3d.env" "$@"
}

## @description  Executes a command with the A5 fakes (fake JDK and fake probe) on the Kubernetes
##               backend against the K1 round's A5 cluster state directory.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_k1_a5() {
  t_a5 "PX_FAKE_STATE_DIR=$T_K1_A5" PX_BACKEND=k8s "PX_KUBECTL_BIN=$T_SHIM_DIR/kubectl" \
    PX_K8S_CONTEXT=fake "PX_FAKE_KUBECTL_LOG=$T_K1/calls.log" "PX_REGION_MOVER_CMD=$T_MOVER_CMD" \
    "$@"
}

# The case branches of this function define the supported pod states, such as ready, unready,
# pending, crash, neverpull, config, and oom.
## @description  Formats a single fake kubectl pod inventory record for the given attributes (name,
##               role, index label, ordinal, drain, owner, state, restarts).
## @return       Writes the formatted pod line to stdout. Exit 0.
t_k8s_pod() {
  local phase=Running reason="" ready=True started=2026-01-03T00:00:00Z waiting="" code=""
  local treason="" fin="" lcode="" lreason="" lfin=""
  case "${7:-ready}" in
  unready) ready=False ;;
  pending) phase=Pending ready=False started="" waiting=ContainerCreating ;;
  crash)
    ready=False started="" waiting=CrashLoopBackOff lcode=1 lreason=Error
    lfin=2026-01-03T00:00:05Z
    ;;
  neverpull) phase=Pending ready=False started="" waiting=ErrImageNeverPull ;;
  config) phase=Pending ready=False started="" waiting=CreateContainerConfigError ;;
  oom) lcode=137 lreason=OOMKilled lfin=2026-01-03T00:00:05Z ;;
  restarted) lcode=1 lreason=Error lfin=2026-01-03T00:00:05Z ;;
  exited) phase=Failed ready=False started="" code=1 treason=Error fin=2026-01-03T00:00:06Z ;;
  evicted) phase=Failed reason=Evicted ready=False started="" ;;
  esac
  printf '%s|uid-%s|%s|%s|%s|%s||%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|\n' \
    "$1" "$1" "$2" "$3" "$4" "$5" "$phase" "$reason" "$ready" "$started" "$waiting" "$code" \
    "$treason" "$fin" "$lcode" "$lreason" "$lfin" "${8:-0}" phoenix-harness:selftest \
    "$T_K8S_IMAGE_ID" fake-node 3584Mi "$6"
}

## @description  Seeds the selftest Kubernetes cluster (state directory, output root...) for scope
##               fake/px-selftest with regionserver 0, client 0, tooling 0, and its state records.
## @return       Populates the seeded cluster state and deployment directories. Exit 0.
t_k8s_seed() {
  local state=$1 root dir="$T_K1/deploy-selftest"
  shift
  rm -rf "$state"
  mkdir -p "$state" "$dir"
  printf '%s\n' "# Rendered by manifests.gen.sh. Render again instead of editing." \
    PX_CLUSTER_ID=selftest PX_PHOENIX_HBASE_PROFILE=2.6 PX_DIST_HBASE_VERSION=2.6.7 \
    PX_DIST_HADOOP_VERSION=3.4.3 PX_ZOOKEEPER_VERSION=3.8.6 PX_HBASE_HADOOP_VERSION=3.4.3 \
    PX_GROUPS= >"$dir/topology.env"
  printf '%s\n' "# service role index group start mem_limit_mib heap_mib direct_mib jdk" \
    "$(t_rows 9 regionserver-0 regionserver 0 '' '' 3584 3072 512 17)" \
    "$(t_rows 9 client-0 client 0 '' '' 1536 1024 128 17)" \
    "$(t_rows 9 tooling-0 tooling 0 '' '' 512 256 64 17)" >"$dir/services.tsv"
  printf 'PX_PORT_REGIONSERVER_RPC=16020\nPX_PORT_REGIONSERVER_HTTP=16025\n' \
    >"$dir/ports.generated.env"
  mkdir -p "$dir/manifests"
  {
    t_k8s_pod regionserver-0 regionserver '' 0 regionmover StatefulSet
    t_k8s_pod client-0 client 0 '' '' ''
    t_k8s_pod tooling-0 tooling 0 '' '' ''
  } >"$state/k8s-pods.txt"
  printf 'regionserver|1|OnDelete|0|no\n' >"$state/k8s-sts.txt"
  printf 'px-selftest|selftest\n' >"$state/k8s-namespaces.txt"
  for root in "$@"; do
    rm -rf "$root/state/k8s" "$root/state/scope-k8s-selftest" \
      "$root/state/deployment-k8s-selftest" "$root/state/cluster-selftest.env"
    mkdir -p "$root/state/k8s"
    printf 'fake/px-selftest\n' >"$root/state/scope-k8s-selftest"
    printf '%s\n' "$dir" >"$root/state/deployment-k8s-selftest"
    printf 'scope\tfake/px-selftest\ndeployment_dir\t%s\nnamespace_owner\tharness\n' "$dir" \
      >"$root/state/k8s/scope-fake_px-selftest.tsv"
  done
}

## @description  Verifies the exit status of a batch of Kubernetes backend contract calls (name,
##               lines of expected code, function, and arguments).
## @return       Records one test assertion. Exit 0.
t_k8s_codes() {
  local name=$1 line bad=""
  local words=()
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    read -r -a words <<<"$line"
    t_k8s_sb "${words[@]:1}"
    if [ "$T_STATUS" != "${words[0]}" ]; then
      bad="$bad${words[1]} gave $T_STATUS, not ${words[0]}: $line$PX_NL"
    fi
  done <<EOF
$2
EOF
  t_empty "$name" "$bad"
}

## @description  Splits the YAML documents in the given files (file...) into one line per document
##               with the document lines joined by a bar for per-document grep matching.
## @return       Writes one line per document to stdout. Exit 0.
t_k8s_docs() {
  awk '/^---$/ { if (d != "") print d; d = ""; next } /^#/ { next }
    { d = d "|" $0 } END { if (d != "") print d }' "$@"
}

## @description  Locates the rendered manifest file for a role (render dir, role) under manifests/
##               or groups/.
## @return       Writes the manifest path to stdout, or nothing if no file matches. Exit 0.
t_k8s_role_file() {
  local file
  for file in "$1"/manifests/*-"$2".yaml "$1"/groups/*/*-"$2".yaml; do
    if [ -f "$file" ]; then
      printf '%s\n' "$file"
      return 0
    fi
  done
}

## @description  Extracts the container environment of a role (render dir, role, ordinal) as
##               NAME=VALUE lines, resolving pod field references to the pod name and ordinal.
## @return       Writes the NAME=VALUE lines to stdout. Exit 0.
t_k8s_env_of() {
  local file
  file="$(t_k8s_role_file "$1" "$2")"
  awk -v pod="$2-$3" -v ord="$3" '
    /^ *env:$/ { e = 1; next }
    e && /^ *ports:$/ { e = 0 }
    e && /- name: / { n = $3 }
    e && /value: / { v = $0; sub(/^ *value: "/, "", v); sub(/"$/, "", v); print n "=" v }
    e && /fieldPath: / {
      v = ($0 ~ /pod-index/) ? ord : pod
      print n "=" v }' "$file"
}

## @description  Extracts the environment of a Compose service (render dir, service) as NAME=VALUE
##               lines from the service's environment section in compose.yaml.
## @return       Writes the NAME=VALUE lines to stdout. Exit 0.
t_compose_env_of() {
  awk -v s="  $2:" '
    $0 == s { f = 1; next }
    f && /^  [a-z]/ { f = 0 }
    f && /^    environment:/ { e = 1; next }
    f && e && /^    [a-z]/ { e = 0 }
    f && e { sub(/^ +/, ""); sub(/: /, "="); gsub(/"/, ""); print }' "$1/compose.yaml"
}

## @description  Extracts the literal block content of an embedded file entry (key) from a rendered
##               ConfigMap manifest (file).
## @return       Writes the decoded file bytes to stdout. Exit 0.
t_k8s_block() {
  awk -v k="  $2: " '
    index($0, k) == 1 { f = 1; next }
    f && /^  [^ ]/ { exit }
    f { sub(/^    /, ""); print }' "$1"
}

## @description  Verifies the manifest generator for each round requirement a rendered file shows
##               (RQ-259, RQ-261 to RQ-269, RQ-271 to RQ-273, RQ-276 to RQ-278, RQ-280, RQ-282).
## @return       Records test assertions. Exit 0.
t_group_k1_render() {
  local dir full m docs bad="" role file line count copy compose want got names key
  local envs=()
  printf '# K1: the manifest generator\n'
  # Verify that the Kubernetes deployment uses only static manifests and kubectl, with no Helm
  # charts or custom resource definitions (RQ-259).
  t_equals k8s_ships_no_chart_or_crd yes "$(t_exists "$PX_HOME/k8s/manifests.gen.sh")$(find \
    "$PX_HOME/k8s" -name Chart.yaml; grep -rl 'kind: CustomResourceDefinition' "$PX_HOME/k8s")"
  t_mgen --class validation --cluster k1
  t_equals k8s_validation_renders 0 "$T_STATUS"
  dir="$T_K8S_OUT/deploy-k8s/k1"
  m="$dir/manifests"
  t_mgen --class measurement --cluster k1full -e PX_SNN=1 -e PX_DN_COUNT=2 -e PX_NM_COUNT=3 \
    -e PX_RS_COUNT=4
  t_equals k8s_measurement_renders_every_role 0 "$T_STATUS"
  full="$T_K8S_OUT/deploy-k8s/k1full"
  # Verify that every cluster role renders as a single StatefulSet paired with a headless Service
  # (RQ-261).
  for role in zookeeper namenode secondarynamenode datanode resourcemanager nodemanager \
    jobhistory hbase-master regionserver; do
    file="$(t_k8s_role_file "$full" "$role")"
    [ -n "$file" ] || { bad="$bad $role has no file;"; continue; }
    [ "$(grep -c '^kind: StatefulSet$' "$file")" = 1 ] || bad="$bad $role StatefulSet count;"
    [ "$(grep -c '^kind: Service$' "$file")" = 1 ] || bad="$bad $role Service count;"
    grep -q '^  clusterIP: None$' "$file" || bad="$bad $role Service is not headless;"
    grep -q "^  name: $role\$" "$file" || bad="$bad $role is not the name;"
  done
  t_empty k8s_one_statefulset_and_headless_service_per_role "$bad"
  bad=""
  for role in zookeeper namenode secondarynamenode resourcemanager jobhistory hbase-master; do
    file="$(t_k8s_role_file "$full" "$role")"
    [ -n "$file" ] && grep -qx '  replicas: 1' "$file" || bad="$bad $role;"
  done
  t_empty k8s_singletons_have_one_replica "$bad"
  t_equals k8s_scaled_roles_have_the_count "2 3 4" "$(grep -h '^  replicas: ' \
    "$full/manifests/23-datanode.yaml" "$full/groups/yarn/25-nodemanager.yaml" \
    "$full/manifests/28-regionserver.yaml" | awk '{ printf "%s%s", s, $2; s = " " }')"
  t_equals k8s_render_holds_no_deployment yes "$(t_exists "$full/manifests")$(grep -rl \
    '^kind: Deployment$' "$full")"
  t_mgen --class measurement --cluster k1full -e PX_SNN=1 -e PX_DN_COUNT=2 -e PX_NM_COUNT=3 \
    -e PX_RS_COUNT=4 --check
  got=$T_STDOUT
  t_gen --class measurement --cluster k1full -e PX_SNN=1 -e PX_DN_COUNT=2 -e PX_NM_COUNT=3 \
    -e PX_RS_COUNT=4 --check
  t_equals k8s_service_table_equals_compose "$T_STDOUT" "$got"
  t_equals k8s_service_table_file_equals_the_check "18 $got" \
    "$(grep -vc '^#' "$full/services.tsv" 2>/dev/null) $(grep -v '^#' "$full/services.tsv")"
  # Verify that every StatefulSet uses the OnDelete update strategy so that pods are replaced only
  # when explicitly deleted by the harness (RQ-262).
  t_equals k8s_every_statefulset_is_ondelete "9 9 0" "$(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | grep -c '^kind: StatefulSet$') $(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | grep -c '^    type: OnDelete$') $(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | grep -c 'RollingUpdate')"
  # Verify that the entrypoint instance index variable is populated from the StatefulSet pod
  # ordinal label (RQ-263).
  bad=""
  for file in "$full"/manifests/2?-*.yaml "$full"/groups/yarn/*.yaml; do
    awk '/- name: PX_INSTANCE_INDEX$/ { getline; getline; getline; print }' "$file" |
      grep -qF "fieldPath: \"metadata.labels['apps.kubernetes.io/pod-index']\"" ||
      bad="$bad ${file##*/};"
  done
  for file in "$m/30-setup.yaml" "$m/40-client.yaml" "$m/41-tooling.yaml"; do
    awk '/- name: PX_INSTANCE_INDEX$/ { getline; print }' "$file" | grep -qx ' *value: "0"' ||
      bad="$bad ${file##*/};"
  done
  t_empty k8s_index_comes_from_the_ordinal "$bad"
  t_empty entrypoint_has_no_backend_branch "$(grep -vE '^[[:space:]]*#' \
    "$T_IMG/entrypoint.sh" | grep -inE 'kube|k8s|compose|docker|statefulset|pod-index')"
  t_gen --class measurement --cluster k1full -e PX_SNN=1 -e PX_DN_COUNT=2 -e PX_NM_COUNT=3 \
    -e PX_RS_COUNT=4 --out deploy/k1full
  compose="$T_A2_OUT/deploy/k1full"
  bad=""
  for role in zookeeper namenode secondarynamenode datanode resourcemanager nodemanager \
    jobhistory hbase-master regionserver client tooling setup; do
    want="$(t_compose_env_of "$compose" "$role-0" | grep -v '^PX_MONITOR_' | LC_ALL=C sort)"
    got="$(t_k8s_env_of "$full" "$role" 0 | LC_ALL=C sort)"
    [ "$want" = "$got" ] || bad="$bad $role: compose [$want] k8s [$got];"
  done
  t_empty k8s_pod_env_equals_compose_env "$bad"
  # Verify that the configuration for a RegionServer at ordinal 2 renders cleanly from the combined
  # px-env ConfigMap and container environment variables and binds the base ports.
  while IFS= read -r key; do
    [ -n "$key" ] && envs+=("$key")
  done <<EOF
$(awk '/^data:$/ { d = 1; next } d { sub(/^  /, ""); sub(/: "/, "="); sub(/"$/, ""); print }' \
    "$full/manifests/10-env.yaml")
$(t_k8s_env_of "$full" regionserver 2)
EOF
  rm -rf "$T_A2/render"
  t_image "PX_DATA_DIR=$T_A2/data" "PX_LOG_DIR=$T_A2/logs" "PX_HADOOP_HOME=$T_A2/hadoop" \
    ${envs[@]+"${envs[@]}"} -- "$T_BASH" "$T_IMG/entrypoint.sh" --render-only "$T_A2/render"
  t_equals k8s_pod_env_renders_the_configuration 0 "$T_STATUS"
  t_equals k8s_rendered_regionserver_binds_the_base_port 16020 "$(awk '
    /<name>hbase.regionserver.port<\/name>/ { getline; gsub(/.*<value>|<\/value>.*/, ""); print }' \
    "$T_A2/render/hbase-site.xml" 2>/dev/null)"
  bad=""
  # Verify that the setup role renders as a non-restarting Job and that the client and tooling
  # roles render as standalone non-restarting Pods (RQ-264).
  t_equals k8s_setup_is_a_job_that_never_restarts "Job setup-0 Never 0 data-namenode-0 setup" \
    "$(awk '/^kind: /{ k = $2 } /^  name: / && !n { n = $2 } /restartPolicy: / { r = $2 }
      /backoffLimit: / { b = $2 } /claimName: / { c = $2 }
      /px.harness.role: "setup"/ { s = "setup" }
      END { print k, n, r, b, c, s }' "$m/30-setup.yaml")"
  t_equals k8s_client_and_tooling_are_pods \
    "PersistentVolumeClaim Pod Never PersistentVolumeClaim Pod Never" "$(awk '/^kind: /{
    printf "%s ",
    $2 } /^ *restartPolicy: / { printf "%s ", $2 }' "$m/40-client.yaml" "$m/41-tooling.yaml" |
    sed 's/ $//')"
  # Verify that every pod binds the unshifted base port so that multiple RegionServer ordinals use
  # the same port number (RQ-265).
  t_equals k8s_container_ports_are_base_ports "16020 16025" "$(awk '/containerPort: / {
    printf "%s%s", s, $2; s = " " }' "$full/manifests/28-regionserver.yaml")"
  t_empty k8s_port_map_has_no_index "$(grep -E '^PX_PORT_[A-Z0-9_]+_[0-9]+=' \
    "$full/ports.generated.env")"
  t_equals k8s_port_map_holds_the_bases "9866 16020 16025" "$(sed -n \
    -e 's/^PX_PORT_REGIONSERVER_RPC=//p' -e 's/^PX_PORT_REGIONSERVER_HTTP=//p' \
    -e 's/^PX_PORT_DATANODE_DATA=//p' "$full/ports.generated.env" | tr '\n' ' ' | sed 's/ $//')"
  # Verify that replica count bounds match the Compose generator, accepting counts 1 through 9 and
  # rejecting 0 and 10 (RQ-266).
  t_mgen --class validation --cluster k1 -e PX_RS_COUNT=1 --check
  count=$T_STATUS
  t_mgen --class validation --cluster k1 -e PX_RS_COUNT=9 --check
  t_equals k8s_counts_1_and_9_render "0 0 9" "$count $T_STATUS $(printf '%s\n' "$T_STDOUT" |
    grep -c '^regionserver-')"
  bad=""
  for count in 0 10; do
    t_mgen --class validation --cluster k1 -e "PX_RS_COUNT=$count" --check
    got="${T_STDERR%%"$PX_NL"*}"
    got="$T_STATUS ${got#*: }"
    t_gen --class validation --cluster k1 -e "PX_RS_COUNT=$count" --check
    want="${T_STDERR%%"$PX_NL"*}"
    want="$T_STATUS ${want#*: }"
    [ "$got" = "$want" ] && [ "${got%% *}" = 2 ] || bad="$bad $count: [$want] [$got];"
  done
  t_empty k8s_counts_0_and_10_refused_like_compose "$bad"
  # Verify that the container startup, readiness, and liveness probes execute the image healthcheck
  # script and that liveness timeouts exceed the maximum tolerated pause duration (RQ-267).
  bad=""
  for file in "$full"/manifests/2?-*.yaml "$full"/groups/yarn/*.yaml; do
    for line in startupProbe:live readinessProbe:ready livenessProbe:live; do
      awk -v p="${line%%:*}:" -v mode="${line#*:}" '$1 == p { f = 1; n = 0; next }
        f && n < 4 { n++; if ($0 ~ /healthcheck.sh$/) h = 1; if ($2 == mode) w = 1 }
        END { exit !(h && w) }' "$file" || bad="$bad ${file##*/} $line;"
    done
  done
  for file in "$m/30-setup.yaml" "$m/40-client.yaml" "$m/41-tooling.yaml"; do
    grep -q 'livenessProbe:' "$file" && bad="$bad ${file##*/} has a liveness probe;"
    grep -q '/opt/px/healthcheck.sh' "$file" || bad="$bad ${file##*/} has no probe;"
  done
  t_empty k8s_probes_exec_the_image_healthcheck "$bad"
  want="$(sed -n 's/^PX_K8S_MAX_PAUSE_S=//p' "$PX_HOME/k8s/env/k8s.env")"
  got="$(awk '$1 == "livenessProbe:" { f = 1 } f && /periodSeconds/ { p = $2 }
    f && /failureThreshold/ { print p * $2; exit }' "$m/28-regionserver.yaml")"
  t_mgen --class validation --cluster k1pause -e PX_K8S_MAX_PAUSE_S=300
  t_equals k8s_liveness_outlasts_the_tolerated_pause "yes yes" "$([ "${got:-0}" -gt \
    "${want:-0}" ] && echo yes || echo no) $(awk '$1 == "livenessProbe:" { f = 1 }
    f && /periodSeconds/ { p = $2 } f && /failureThreshold/ { print (p * $2 > 300) ? "yes" : \
    "no"; exit }' "$T_K8S_OUT/deploy-k8s/k1pause/manifests/28-regionserver.yaml")"
  # Verify that all containers run with Guaranteed QoS (equal resource requests and limits) and
  # match the Compose memory limits and heap sizes across both run classes (RQ-268).
  bad=""
  for file in "$dir"/manifests/*.yaml "$full"/manifests/*.yaml "$full"/groups/*/*.yaml; do
    awk '/^ *requests:$/ { r = 1; l = 0; next } /^ *limits:$/ { l = 1; r = 0; next }
      r && /cpu:|memory:/ { req[$1] = $2 } l && /cpu:|memory:/ { lim[$1] = $2 }
      /^ *(startupProbe|readinessProbe|volumeMounts):/ { r = l = 0 }
      END { for (k in req) if (req[k] != lim[k]) bad = 1; for (k in lim) if (req[k] != lim[k])
        bad = 1; exit bad }' "$file" || bad="$bad ${file##*/};"
  done
  t_empty k8s_requests_equal_limits "$bad"
  bad=""
  for role in regionserver hbase-master client setup; do
    want="$(awk -v s="  $role-0:" '$0 == s { f = 1 } f && /mem_limit:/ { sub(/m$/, "", $2)
      print $2; exit }' "$compose/compose.yaml") $(t_compose_env_of "$compose" "$role-0" |
      sed -n 's/^PX_HEAP_MIB=//p')"
    file="$(t_k8s_role_file "$full" "$role")"
    got="$(awk '/limits:/ { l = 1 } l && /memory:/ { sub(/Mi$/, "", $2); print $2; exit }' \
      "$file") $(t_k8s_env_of "$full" "$role" 0 | sed -n 's/^PX_HEAP_MIB=//p')"
    [ "$want" = "$got" ] || bad="$bad $role compose [$want] k8s [$got];"
  done
  t_empty k8s_memory_and_heap_equal_compose "$bad"
  t_empty k8s_cpu_follows_the_memory_rule "$(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | awk '/^ *limits:$/ { l = 1; next } l && /cpu:/ { c = $2 }
    l && /memory:/ { m = $2; sub(/Mi$/, "", m); l = 0; n++
      if (c != int((m * 250 + 1023) / 1024) "m") print m, c }
    END { if (n == 0) print "no container" }')"
  # Verify that stateful roles allocate persistent volume claims against the configured
  # StorageClass and that rendering fails when no StorageClass is configured (RQ-269).
  t_equals k8s_stateful_roles_claim_volumes "zookeeper namenode secondarynamenode datanode" \
    "$(for file in "$full"/manifests/2?-*.yaml; do
      grep -q '^        name: data$' "$file" && printf '%s ' \
        "$(sed -n 's/^  serviceName: //p' "$file")"
    done | sed 's/ $//')"
  # Verify that every harness-image pod addressed by a run mounts a dedicated persistent volume
  # claim at /tmp/px, which serves as the remote working directory for drivers and collectors, so
  # that run files survive pod replacement during a recovery restart. Each StatefulSet provisions
  # this volume through a second claim template named tmp, while the client and tooling Pods mount
  # a standalone claim document rendered ahead of the Pod manifest; neither the setup Job nor the
  # Pherf tier mounts a temporary claim.
  docs="$(t_k8s_docs "$full"/manifests/*.yaml "$full"/groups/*/*.yaml |
    grep -E '\|kind: (StatefulSet|Pod)\|')"
  t_equals k8s_every_harness_pod_mounts_tmp_px "11 " "$(printf '%s\n' "$docs" | grep -c .) $(
    printf '%s\n' "$docs" | grep -vE '\| *- name: tmp\| *mountPath: /tmp/px\| *readOnly: false\|' |
      sed -n 's/.*|  name: \([a-z-]*\)|.*/\1/p' | tr '\n' ' ')"
  t_equals k8s_statefulsets_claim_tmp_per_pod \
    "datanode:data,tmp:tmp=local-path/10Gi/k1full/datanode\
 hbase-master:tmp:tmp=local-path/10Gi/k1full/hbase-master\
 jobhistory:tmp:tmp=local-path/10Gi/k1full/jobhistory\
 namenode:data,tmp:tmp=local-path/10Gi/k1full/namenode\
 nodemanager:tmp:tmp=local-path/10Gi/k1full/nodemanager\
 regionserver:tmp:tmp=local-path/10Gi/k1full/regionserver\
 resourcemanager:tmp:tmp=local-path/10Gi/k1full/resourcemanager\
 secondarynamenode:data,tmp:tmp=local-path/10Gi/k1full/secondarynamenode\
 zookeeper:data,tmp:tmp=local-path/10Gi/k1full/zookeeper" "$(for file in \
    "$full"/manifests/2?-*.yaml "$full"/groups/yarn/*.yaml; do
      awk '/^  serviceName: / { role = $2 } /^  volumeClaimTemplates:$/ { v = 1; next }
        v && /^        name: / { c = $2; names = names (names == "" ? "" : ",") c }
        v && c == "tmp" && /storageClassName: / { sc = $2 }
        v && c == "tmp" && /storage: / { gib = $2 }
        v && c == "tmp" && /px\.harness\.cluster: / { cl = $2; gsub(/"/, "", cl) }
        v && c == "tmp" && /px\.harness\.role: / { r = $2; gsub(/"/, "", r) }
        END { print role ":" names ":tmp=" sc "/" gib "/" cl "/" r }' "$file"
    done | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')"
  got=""
  for role in client tooling; do
    got="$got $(awk 'BEGIN { d = 0 } /^---$/ { d++ } /^kind: / { k[d] = $2 }
      /^  name: / && !n[d]++ { nm[d] = $2 } /storageClassName: / { sc[d] = $2 }
      /storage: / { g[d] = $2 } /px\.harness\.role: / { r[d] = $2; gsub(/"/, "", r[d]) }
      /px\.harness\.cluster: / { c[d] = $2; gsub(/"/, "", c[d]) }
      /^    - name: tmp$/ { t[d] = 1; next } t[d] && /claimName: / { cn[d] = $2; t[d] = 0 }
      END { print k[0] ":" nm[0] ":" sc[0] "/" g[0] "/" c[0] "/" r[0] "," k[1] ":" nm[1] ":" \
        cn[1] }' "$(t_k8s_role_file "$dir" "$role")")"
  done
  t_equals k8s_pods_claim_tmp_by_name \
    " PersistentVolumeClaim:tmp-client-0:local-path/10Gi/k1/client\
,Pod:client-0:tmp-client-0 PersistentVolumeClaim:tmp-tooling-0:local-path/10Gi/k1/tooling\
,Pod:tooling-0:tmp-tooling-0" "$got"
  t_mgen --class validation --cluster k1tier -e PX_PHERF=1
  t_equals k8s_setup_and_tier_mount_no_tmp_claim "0 no no" "$T_STATUS $(t_flag 'tmp' \
    "$(grep -hE 'claimName|/tmp/px|^        name: ' "$m/30-setup.yaml" \
      "$T_K8S_OUT/deploy-k8s/k1tier/groups/pherf/"*.yaml)") $(t_flag 'tmp-pherf' \
    "$(cat "$T_K8S_OUT/deploy-k8s/k1tier/groups/pherf/"*.yaml)")"
  t_equals k8s_requests_count_the_tmp_claims \
    "Deployment:pherf-agent:0 Deployment:pherf-controller:1 Job:setup-0:0 Pod:client-0:1\
 Pod:tooling-0:1 StatefulSet:datanode:2 StatefulSet:hbase-master:1 StatefulSet:jobhistory:1\
 StatefulSet:namenode:2 StatefulSet:nodemanager:1 StatefulSet:regionserver:1\
 StatefulSet:resourcemanager:1 StatefulSet:secondarynamenode:2 StatefulSet:zookeeper:2" "$({
      awk -F '\t' '!/^#/ && NF { print $1 ":" $2 ":" $6 }' "$full/requests.tsv"
      awk -F '\t' '$2 ~ /^pherf-/ { print $1 ":" $2 ":" $6 }' \
        "$T_K8S_OUT/deploy-k8s/k1tier/requests.tsv"
    } | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')"
  t_equals k8s_tmp_mount_is_the_remote_root "/tmp/px /tmp/px /tmp/px" "$(sed -n \
    's/^PXK_TMP_MOUNT=//p' "$PX_HOME/k8s/manifests.gen.sh") $(sed -n 's/^DV_REMOTE_ROOT=//p' \
    "$PX_HOME/drivers/lib/driver.sh") $(sed -n 's|^  CK_REMOTE="\(/tmp/px\)/.*|\1|p' \
    "$PX_HOME/lib/collect.sh")"
  t_equals k8s_data_claim_renders_unchanged "$(printf '%s\n' '  volumeClaimTemplates:' \
    '    - metadata:' '        name: data' '        labels:' \
    '          px.harness.cluster: "k1"' '          px.harness.role: "zookeeper"' '      spec:' \
    '        accessModes:' '          - ReadWriteOnce' '        storageClassName: local-path' \
    '        resources:' '          requests:' '            storage: 10Gi')" "$(sed -n \
    '/^  volumeClaimTemplates:$/,/^            storage: /p' "$m/20-zookeeper.yaml")"
  t_equals k3d_env_resolves_local_path PX_K8S_STORAGE_CLASS=local-path \
    "$(grep '^PX_K8S_STORAGE_CLASS=' "$PX_HOME/k8s/env/k3d.env")"
  printf 'PX_K8S_CONTEXT=fake\n' >"$T_K1/nosc-target.env"
  t_k8s "PX_K8S_TARGET=$T_K1/nosc-target.env" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" \
    --class validation --cluster nosc
  t_equals k8s_render_without_storage_class_is_refused "2 yes no" "$T_STATUS $(t_flag \
    PX_K8S_STORAGE_CLASS "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/nosc")"
  # Verify that a single namespace carrying the harness cluster label scopes every rendered
  # resource object (RQ-271).
  t_equals k8s_namespace_carries_the_harness_label 'px-k1 px.harness.cluster: "k1"' \
    "$(awk '/^  name: / { n = $2 } /px.harness.cluster:/ { sub(/^ +/, ""); l = $0 }
      END { print n, l }' "$m/00-namespace.yaml")"
  docs="$(t_k8s_docs "$m"/*.yaml)"
  t_empty k8s_every_object_names_the_namespace_and_label "$(printf '%s\n' "$docs" |
    grep -v '|kind: Namespace|' | grep -vF '|  namespace: px-k1|' ; printf '%s\n' "$docs" |
    grep -vF 'px.harness.cluster: "k1"')"
  # Verify that no rendered resource exposes the cluster externally via external Service types,
  # host ports, or host namespaces (RQ-272).
  t_empty k8s_services_are_headless_and_internal "$(grep -rhE 'type: (NodePort|LoadBalancer)' \
    "$full"; t_k8s_docs "$full"/manifests/*.yaml "$full"/groups/*/*.yaml |
    grep '|kind: Service|' | grep -v '|  clusterIP: None|')"
  t_empty k8s_no_host_port_or_host_network "$(grep -rhE 'hostPort|hostNetwork|hostPID|hostIPC' \
    "$full")"
  # Verify that every workload container runs with an unprivileged security context as a non-root
  # user (RQ-273).
  docs="$(t_k8s_docs "$full"/manifests/*.yaml "$full"/groups/*/*.yaml |
    grep -E '\|kind: (StatefulSet|Job|Pod)\|')"
  bad=""
  while IFS= read -r line; do
    for want in 'runAsNonRoot: true' "runAsUser: $(id -u)" 'allowPrivilegeEscalation: false' \
      '- ALL' 'type: RuntimeDefault'; do
      [[ $line == *"$want"* ]] || bad="$bad [$want]"
    done
  done <<EOF
$docs
EOF
  # The client and tooling Pods appear as separate documents following their claim documents,
  # whereas the Job in 30-setup.yaml is merged with the preceding StatefulSet document because
  # t_k8s_docs splits solely on --- delimiters.
  t_equals k8s_every_container_runs_unprivileged "11 " "$(printf '%s\n' "$docs" | grep -c .) $bad"
  t_equals k8s_run_as_user_is_not_root yes "$([ "$(id -u)" != 0 ] && echo yes || echo no)"
  # Verify that every Service port declaration specifies both an appProtocol and a matching port
  # name prefix (RQ-276).
  bad="$(awk '/^kind: Service$/ { s = 1 } /^kind: StatefulSet$/ { s = 0 }
    s && /- name: / { n = $3 } s && /appProtocol: / { if (index(n, $2 "-") != 1) print n, $2 }
    s && /^  ports:$/ { p = 1 }' "$full"/manifests/*.yaml "$full"/groups/*/*.yaml)"
  t_equals k8s_service_ports_declare_protocol_and_prefix "0 $(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | grep -c '^      targetPort: ')" "$(printf '%s' "$bad" | grep -c .)\
 $(cat "$full"/manifests/*.yaml "$full"/groups/*/*.yaml |
    grep -cE '^      appProtocol: (tcp|http)$')"
  # Verify that the rendered ConfigMaps embed the exact image configuration templates and cluster
  # environment and are mounted into every workload pod template (RQ-277, RQ-282).
  names=""
  for file in "$T_IMG"/conf/*.tmpl; do
    names="$names${file##*/} "
  done
  t_equals k8s_conf_configmap_holds_the_image_templates "$names" "$(sed -n \
    's/^  \([A-Za-z0-9._-]*\.tmpl\): |2[+-]$/\1/p' "$m/11-conf.yaml" | tr '\n' ' ')"
  bad=""
  for key in $names; do
    t_k8s_block "$m/11-conf.yaml" "$key" >"$T_K1/block"
    cmp -s "$T_K1/block" "$T_IMG/conf/$key" || bad="$bad $key;"
  done
  t_empty k8s_conf_blocks_are_the_template_bytes "$bad"
  t_equals k8s_configmaps_carry_the_configuration "yes yes yes yes" "$(t_flag \
    'PX_CLUSTER_ID: "k1"' "$(cat "$m/10-env.yaml")") $(t_flag 'PX_PORT_ZOOKEEPER_CLIENT: "2181"' \
    "$(cat "$m/10-env.yaml")") $(t_flag 'hbase-site.xml.tmpl: |' "$(cat "$m/11-conf.yaml")")\
 $(t_flag 'phoenix-client.properties.tmpl: |' "$(cat "$m/11-conf.yaml")")"
  docs="$(t_k8s_docs "$full"/manifests/*.yaml "$full"/groups/*/*.yaml |
    grep -E '\|kind: (StatefulSet|Job|Pod)\|')"
  t_empty k8s_every_pod_template_mounts_the_configuration "$(printf '%s\n' "$docs" |
    grep -vF '|              mountPath: /opt/px/conf|' |
    grep -vF '|            mountPath: /opt/px/conf|' |
    grep -vF '|          mountPath: /opt/px/conf|' |
    cut -c1-80; printf '%s\n' "$docs" | grep -vF 'name: px-env' | cut -c1-80)"
  # Verify that injecting a sentinel comment into a configuration template in a harness copy
  # propagates identically into both the Kubernetes and Compose renders.
  copy="$T_K1/harness"
  rm -rf "$copy"
  cp -R "$PX_HOME" "$copy"
  sed -i.bak 's|^<configuration>$|<configuration>\
<!-- px-sentinel-k1 -->|' "$copy/$PX_DEPLOY_SUBDIR/image/conf/hbase-site.xml.tmpl"
  rm -f "$copy/$PX_DEPLOY_SUBDIR/image/conf/hbase-site.xml.tmpl.bak"
  t_k8s "PX_HOME=$copy" "$T_BASH" "$copy/k8s/manifests.gen.sh" --config \
    "$copy/k8s/env/k3d.env" --class validation --cluster sentinel
  got="$(t_flag px-sentinel-k1 "$(cat "$T_K8S_OUT/deploy-k8s/sentinel/manifests/11-conf.yaml" \
    2>/dev/null)")"
  t_a2 "PX_HOME=$copy" "$T_BASH" "$copy/$PX_DEPLOY_SUBDIR/compose.gen.sh" --class validation \
    --cluster sentinel
  rm -rf "$T_A2/render"
  t_in_service "$T_A2_OUT/deploy/sentinel" regionserver-0 -- "$T_BASH" \
    "$copy/$PX_DEPLOY_SUBDIR/image/entrypoint.sh" --render-only "$T_A2/render"
  t_equals template_sentinel_reaches_both_renders "yes yes" "$got $(t_flag px-sentinel-k1 \
    "$(cat "$T_A2/render/hbase-site.xml" 2>/dev/null)")"
  # Verify that all Kubernetes workload containers reference the same container image as Compose
  # with an imagePullPolicy of Never (RQ-278).
  want="$(sed -n 's/^    image: "\(.*\)"$/\1/p' "$compose/compose.yaml" | LC_ALL=C sort -u)"
  t_equals k8s_workload_images_equal_the_compose_image "$want $(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | grep -c '^ *- name: px$')" "$(cat "$full"/manifests/*.yaml \
    "$full"/groups/*/*.yaml | sed -n 's/^ *image: "\(.*\)"$/\1/p' | LC_ALL=C sort -u) $(cat \
    "$full"/manifests/*.yaml "$full"/groups/*/*.yaml | grep -c '^ *imagePullPolicy: Never$')"
  # Verify that both the Compose and Kubernetes generators share a single fail-closed template
  # validator that rejects unset variables, unexpanded placeholders, and surviving dollar signs
  # (RQ-280).
  t_mgen --class validation --cluster k1 -e PX_K8S_STORAGE_CLASS= --out deploy-k8s/unset
  t_equals k8s_render_refuses_an_unset_name "2 yes no" "$T_STATUS $(t_flag \
    'unset: PX_K8S_STORAGE_CLASS' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/unset")"
  cat >"$copy/k8s/templates/search.yaml.tmpl" <<'K1_EOF'
# A fixture template with a placeholder that has no value.
${PX_NOPE}
K1_EOF
  t_k8s "PX_HOME=$copy" "$T_BASH" "$copy/k8s/manifests.gen.sh" --config \
    "$copy/k8s/env/k3d.env" --class validation --cluster nope
  t_equals k8s_render_refuses_an_unexpanded_placeholder "2 yes no" "$T_STATUS $(t_flag \
    'line 2 needs PX_NOPE' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/nope")"
  cat >"$copy/k8s/templates/search.yaml.tmpl" <<'K1_EOF'
# A fixture template with a dollar sign that is not a placeholder.
- $PX_RAW
K1_EOF
  t_k8s "PX_HOME=$copy" "$T_BASH" "$copy/k8s/manifests.gen.sh" --config \
    "$copy/k8s/env/k3d.env" --class validation --cluster raw
  t_equals k8s_render_refuses_a_surviving_dollar_sign "1 yes no" "$T_STATUS $(t_flag \
    'a placeholder survived the render' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/raw")"
  t_equals generators_share_the_validator "lib/deploy.sh lib/deploy.sh yes yes yes yes" \
    "$(cd "$PX_HOME" && grep -rl '^pxg_validate() {' --include='*.sh' . | sed 's|^\./||')\
 $(cd "$PX_HOME" && grep -rl '^pxg_require() {' --include='*.sh' . | sed 's|^\./||')\
 $(t_flag "pxg_validate \"\$staging\"" "$(cat "$T_GEN")") $(t_flag "pxg_validate \"\$staging\"" \
    "$(cat "$PX_HOME/k8s/manifests.gen.sh")") $(t_flag 'pxg_require ' \
    "$(cat "$PX_HOME/lib/deploy.sh")") $(t_flag pxg_configure \
    "$(cat "$PX_HOME/k8s/manifests.gen.sh")")"
  t_mgen --class validation --cluster bad_id --check
  t_equals k8s_render_refuses_a_cluster_name_that_is_no_namespace 2 "$T_STATUS"
}

## @description  Renders a fresh validation deployment for the selftest cluster and provisions it
##               into the fake kubectl state.
## @return       Sets T_STATUS from the provision call. Exit 0.
t_k8s_provision() {
  rm -rf "$T_K8S_STATE" "$T_K8S_OUT/state"
  mkdir -p "$T_K8S_STATE"
  t_mgen --class validation --cluster selftest "$@"
  t_k8s_sb sb_scope_create selftest
  mkdir -p "$T_K8S_OUT/state"
  printf 'fake/px-selftest\n' >"$T_K8S_OUT/state/scope-k8s-selftest"
  printf '%s\n' "$T_K8S_OUT/deploy-k8s/selftest" >"$T_K8S_OUT/state/deployment-k8s-selftest"
  t_shim_log_reset
  t_k8s_sb sb_provision fake/px-selftest "$T_K8S_OUT/deploy-k8s/selftest" 30
}

## @description  Verifies the Kubernetes backend lifecycle and executes the contract sweep across
##               every backend function (RQ-259, RQ-262, RQ-265, RQ-270, RQ-279, RQ-281).
## @return       Records test assertions. Exit 0.
t_group_k1_backend() {
  local calls uid line bad="" file
  printf '# K1: the Kubernetes backend\n'
  t_empty k8s_defines_every_contract_function \
    "$(t_backend_verbs "$PX_HOME/lib/backend/k8s.sh")"
  t_k8s_provision
  t_equals k8s_provision_exits_0 0 "$T_STATUS"
  calls="$(cat "$T_SHIM_LOG")"
  # Verify that client-side dry-run validation of every manifest file completes before the first
  # live apply is executed (RQ-281).
  t_equals k8s_dry_run_precedes_each_apply "yes" "$(printf '%s\n' "$calls" | awk '
    / apply --dry-run=client -f / { d[$NF] = NR; if (a) late = 1; next }
    / apply -f / { a = 1; if (!(($NF) in d)) bad = 1; n++ }
    END { print (n > 0 && !bad && !late) ? "yes" : "no" }')"
  t_equals k8s_applies_every_manifest "$(find "$T_K8S_OUT/deploy-k8s/selftest/manifests" \
    -name '*.yaml' | grep -c .)" "$(printf '%s\n' "$calls" | grep -c ' apply -f ')"
  t_k8s_sb sb_wait_ready fake/px-selftest 5
  t_equals k8s_wait_ready_after_provision "0 8" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    grep -c "${PX_TAB}ready$PX_TAB")"
  # Verify that scaling after a render change applies the updated manifest without deleting any
  # existing pod (RQ-262).
  uid="$(cut -d'|' -f1,2 "$T_K8S_STATE/k8s-pods.txt" | grep '^regionserver-0|')"
  t_mgen --class validation --cluster selftest -e PX_RS_COUNT=3 -e PX_RS_JVM_OPTS=-XX:+UseNUMA
  t_shim_log_reset
  t_k8s_sb sb_scale fake/px-selftest regionserver 3 10
  t_equals k8s_changed_render_scales_by_apply 0 "$T_STATUS"
  t_equals k8s_changed_render_deletes_no_pod "0 $uid 3" "$(grep -c ' delete ' "$T_SHIM_LOG")\
 $(cut -d'|' -f1,2 "$T_K8S_STATE/k8s-pods.txt" | grep '^regionserver-0|') $(grep -c \
    '^regionserver-' "$T_K8S_STATE/k8s-pods.txt")"
  # Verify that scaling a role down (datanode from 2 replicas to 1) removes the excess pod ordinals
  # and their persistent volume claims. Each removed ordinal remains in the Terminating state
  # across the pod wait listing and the initial readiness barrier poll so that the barrier observes
  # its removal.
  printf '2\n' >"$T_K8S_STATE/scale-lingers"
  t_mgen --class validation --cluster selftest -e PX_RS_COUNT=3 -e PX_RS_JVM_OPTS=-XX:+UseNUMA \
    -e PX_DN_COUNT=2
  t_k8s_sb sb_scale fake/px-selftest datanode 2 10
  line="$T_STATUS $(grep -c '^data-datanode-' "$T_K8S_STATE/k8s-pvc.txt")"
  t_mgen --class validation --cluster selftest -e PX_RS_COUNT=3 -e PX_RS_JVM_OPTS=-XX:+UseNUMA \
    -e PX_DN_COUNT=1
  t_k8s_sb sb_scale fake/px-selftest datanode 1 10
  t_equals k8s_scale_moves_ordinals_and_their_claims "0 2 0 1 1" "$line $T_STATUS \
$(grep -c '^data-datanode-' "$T_K8S_STATE/k8s-pvc.txt") $(grep -c '^datanode-' \
    "$T_K8S_STATE/k8s-pods.txt")"
  rm -f "$T_K8S_STATE/scale-lingers"
  # Verify that a full teardown deletes both the persistent volume claims and the namespace,
  # whereas a retained teardown preserves both (RQ-270).
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_teardown_exits_0 0 "$T_STATUS"
  t_equals k8s_teardown_deletes_pvcs_and_namespace "yes yes" "$(t_flag \
    'delete pvc -l px.harness.cluster=selftest' "$calls") $(t_flag \
    'delete namespace px-selftest' "$calls")"
  # Verify that cluster lifecycle operations mutate state exclusively by applying rendered manifest
  # files or deleting resources (RQ-259).
  t_empty k8s_lifecycle_mutates_by_apply_and_delete_only "$(cat "$T_K1/calls.log" | awk '
    { v = $6 } v ~ /^(create|patch|replace|scale|edit|label|annotate|set|rollout|run|expose)$/')"
  t_empty k8s_applies_only_rendered_files "$(grep ' apply ' "$T_K1/calls.log" |
    grep -v " -f $T_K8S_OUT/deploy-k8s/selftest/")"
  t_k8s_provision
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down --keep
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_retained_teardown_deletes_neither "0 no no yes" "$T_STATUS $(t_flag \
    'delete pvc' "$calls") $(t_flag 'delete namespace' "$calls") $(t_flag \
    'delete statefulsets,jobs,pods -l px.harness.cluster=selftest' "$calls")"
  # Verify that if client-side dry-run validation fails for any manifest file, no manifest file is
  # applied to the cluster (RQ-281).
  rm -rf "$T_K8S_STATE" "$T_K8S_OUT/state"
  mkdir -p "$T_K8S_STATE"
  printf '30-setup.yaml\n' >"$T_K8S_STATE/dry-run-fails"
  t_mgen --class validation --cluster selftest
  t_k8s_sb sb_scope_create selftest
  t_shim_log_reset
  t_k8s_sb sb_provision fake/px-selftest "$T_K8S_OUT/deploy-k8s/selftest" 30
  t_equals k8s_failed_dry_run_applies_nothing "1 0 yes" "$T_STATUS $(grep -c ' apply -f ' \
    "$T_SHIM_LOG") $(t_flag 'dry run refused' "$T_STDERR")"
  # Execute a contract sweep across every backend function to verify its exit codes and output.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '%s\n' "$(t_k8s_pod regionserver-1 regionserver '' 1 regionmover StatefulSet pending)" \
    >>"$T_K8S_STATE/k8s-pods.txt"
  t_k8s_codes k8s_backend_refuses_bad_usage_with_2 "2 sb_list px-selftest
2 sb_list fake/
2 sb_list fake/default
2 sb_scope_create bad_id
2 sb_exec fake/px-selftest client 0 5 true
2 sb_kill fake/px-selftest regionserver 0 soft
2 sb_signal fake/px-selftest regionserver 0 BOGUS
2 sb_scale fake/px-selftest regionserver 0
2 sb_scale fake/px-selftest regionserver 2
2 sb_group_status fake/px-selftest nogroup
2 sb_teardown fake/px-selftest 7
2 sb_logs fake/px-selftest client 0 - 2
2 sb_restart_drain fake/px-selftest client 0"
  t_k8s_codes k8s_backend_reports_absence_with_6 "6 sb_list fake/px-selftest nosuchrole
6 sb_exec fake/px-selftest regionserver 9 5 -- true
6 sb_logs fake/px-selftest client 0 - 1
6 sb_termination_reason fake/px-selftest client 0 previous
6 sb_endpoint fake/px-selftest regionserver 0 nosuchport
6 sb_endpoint fake/px-selftest client 0 debug
6 sb_scope_exists fake/px-absent"
  t_k8s_codes k8s_exec_frames_its_codes "125 sb_exec fake/px-selftest regionserver 1 5 -- true
127 sb_exec fake/px-selftest client 0 5 -- px-no-such-command
0 sb_exec fake/px-selftest client 0 5 -- true
1 sb_exec fake/px-selftest client 0 5 -- false"
  t_k8s_codes k8s_backend_reports_what_it_cannot_do_with_5 "5 sb_image_build ctx px:t
5 sb_image_base_digest eclipse-temurin:17 arm64
5 sb_image_push $T_K8S_IMAGE_ID registry.example/px:t"
  t_k8s_sb sb_exec fake/px-selftest client 0 5 -- printf '%s|' 'a b' c
  t_outcome k8s_exec_keeps_each_argument 0 'a b|c|'
  t_k8s_sb sb_scope_exists fake/px-selftest
  t_equals k8s_scope_exists_by_its_namespace 0 "$T_STATUS"
  t_k8s_sb sb_list fake/px-selftest
  t_equals k8s_list_reports_the_inventory "$(t_rows 7 client 0 client-0 running ready \
    phoenix-harness:selftest fake-node regionserver 0 regionserver-0 running ready \
    phoenix-harness:selftest fake-node regionserver 1 regionserver-1 pending unready \
    phoenix-harness:selftest fake-node tooling 0 tooling-0 running ready phoenix-harness:selftest \
    fake-node)" "$T_STDOUT"
  t_k8s_sb sb_image_ref fake/px-selftest regionserver 0
  t_outcome k8s_image_ref_reads_the_spec_and_the_id 0 \
    "phoenix-harness:selftest${PX_TAB}$T_K8S_IMAGE_ID"
  # Verify readiness barrier failure detection: a pod that cannot start aborts the barrier
  # immediately.
  for line in crash neverpull exited evicted; do
    t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
    t_k8s_pod client-0 client 0 '' '' '' "$line" >"$T_K1/pod"
    grep -v '^client-0|' "$T_K8S_STATE/k8s-pods.txt" >>"$T_K1/pod"
    mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
    t_k8s_sb sb_wait_ready fake/px-selftest 5
    [ "$T_STATUS" = 3 ] || bad="$bad $line gave $T_STATUS;"
  done
  t_empty k8s_wait_stops_on_a_pod_that_cannot_start "$bad"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  awk -F '|' -v OFS='|' '$1 == "client-0" { $19 = 2 } { print }' \
    "$T_K8S_STATE/k8s-pods.txt" >"$T_K8S_STATE/k8s-next.txt"
  awk -F '|' -v OFS='|' '$1 == "client-0" { $10 = "False" } { print }' \
    "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pod" && mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_wait_ready fake/px-selftest 5
  t_equals k8s_wait_stops_on_a_rising_restart_count "3 yes" "$T_STATUS $(t_flag restarted \
    "$T_STDERR")"
  # Verify that polling an unready pod that exhibits no anomaly does not trigger the ERR trap in a
  # command such as px-run while the readiness barrier waits.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  cp "$T_K8S_STATE/k8s-pods.txt" "$T_K8S_STATE/k8s-next.txt"
  printf '3\n' >"$T_K8S_STATE/k8s-next-after"
  awk -F '|' -v OFS='|' '$1 == "client-0" { $10 = "False" } { print }' \
    "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pod" && mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_k8s "$T_BASH" "$T_WORK/probe/sb-trap.sh" sb_wait_ready fake/px-selftest 5
  t_equals k8s_wait_on_an_unready_pod_keeps_the_err_trap_quiet "0 no" "$T_STATUS $(t_flag \
    'command failed with status' "$T_STDERR")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '%s\n' "$(t_k8s_pod regionserver-1 regionserver '' 1 regionmover StatefulSet unready)" \
    >>"$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_wait_ready fake/px-selftest 1
  t_equals k8s_wait_expires_on_an_unready_pod "4 unready" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$1 == "regionserver" && $2 == 1 { print $3 }')"
  t_k8s_sb sb_wait_ready fake/px-selftest 1 nosuchrole
  t_equals k8s_wait_refuses_an_unknown_role 6 "$T_STATUS"
  # Verify that a pod declared in the rendered service table but not yet created by its controller
  # is treated as pending and prevents the readiness barrier from succeeding.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '%s\n' "$(t_rows 9 datanode-0 datanode 0 '' '' 1024 512 128 17)" \
    >>"$T_K1/deploy-selftest/services.tsv"
  t_k8s_sb sb_wait_ready fake/px-selftest 1
  t_equals k8s_wait_counts_a_missing_pod_as_pending "4 unready" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$1 == "datanode" { print $3 }')"
  # Verify diagnostic evidence collection for container restart counts, termination reasons, and
  # out-of-memory kills.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_sb sb_restart_count fake/px-selftest
  line=$T_STDOUT
  t_k8s_sb sb_kill fake/px-selftest regionserver 0 hard
  t_k8s_sb sb_restart_count fake/px-selftest
  file=$T_STDOUT
  t_shim_log_reset
  t_k8s_sb sb_kill fake/px-selftest regionserver 0 delete
  t_equals k8s_kill_delete_kills_before_it_waits_for_the_delete "0 yes no" "$T_STATUS $(awk '
    / exec -i regionserver-0 -c px -- .* px-kill$/ { k = NR }
    / delete pod regionserver-0 --wait=true --timeout=/ { d = NR }
    END { print (k && d > k) ? "yes" : "no" }' "$T_SHIM_LOG") $(t_flag '--force' \
    "$(cat "$T_SHIM_LOG")")"
  t_k8s_sb sb_restart_count fake/px-selftest
  t_equals k8s_restart_count_adds_a_restart_and_a_new_pod "0 1 2" "$(printf '%s\n' "$line" |
    awk -F '\t' '$1 == "regionserver" { print $3 }') $(printf '%s\n' "$file" | awk -F '\t' \
    '$1 == "regionserver" { print $3 }') $(printf '%s\n' "$T_STDOUT" | awk -F '\t' \
    '$1 == "regionserver" { print $3 }')"
  t_k8s_sb sb_kill fake/px-selftest client 0 hard
  t_k8s_sb sb_termination_reason fake/px-selftest client 0
  t_outcome_columns k8s_killed_standalone_pod_stays_ended 0 3 "killed${PX_TAB}137${PX_TAB}9"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_pod client-0 client 0 '' '' '' crash >"$T_K1/pod"
  t_k8s_pod regionserver-0 regionserver '' 0 regionmover StatefulSet oom 1 >>"$T_K1/pod"
  t_k8s_pod tooling-0 tooling 0 '' '' '' >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_termination_reason fake/px-selftest client 0
  line="$(printf '%s\n' "$T_STDOUT" | cut -f1,2)"
  t_k8s_sb sb_termination_reason fake/px-selftest regionserver 0 previous
  file="$(printf '%s\n' "$T_STDOUT" | cut -f1-3)"
  t_k8s_sb sb_termination_reason fake/px-selftest regionserver 0
  t_equals k8s_termination_maps_current_and_previous "error${PX_TAB}1 oom${PX_TAB}137${PX_TAB}9\
 running" "$line $file $(printf '%s\n' "$T_STDOUT" | cut -f1)"
  t_k8s_sb sb_oom_killed fake/px-selftest regionserver 0
  t_equals k8s_oom_comes_from_the_last_termination "0 yes" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | cut -f1)"
  t_k8s_sb sb_logs fake/px-selftest regionserver 0 - 1
  t_outcome k8s_previous_log_is_served 0 'fake previous log regionserver-0'
  # Verify file copy-in and copy-out round-tripping through the fake runtime working directory, and
  # confirm that copying requires a running target pod.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf 'k1 copy\n' >"$T_K1/copy-in.txt"
  t_k8s_sb sb_copy_in fake/px-selftest client 0 "$T_K1/copy-in.txt" /tmp/px/k1/in.txt
  line=$T_STATUS
  t_k8s_sb sb_copy_out fake/px-selftest client 0 /tmp/px/k1/in.txt k1/out.txt
  t_equals k8s_copy_round_trip "0 0 k1 copy" "$line $T_STATUS $(cat "$T_K8S_OUT/k1/out.txt" \
    2>/dev/null)"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_pod client-0 client 0 '' '' '' exited >"$T_K1/pod"
  grep -v '^client-0|' "$T_K8S_STATE/k8s-pods.txt" >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_copy_in fake/px-selftest client 0 "$T_K1/copy-in.txt" /tmp/px/k1/in.txt
  t_equals k8s_copy_needs_a_running_pod 1 "$T_STATUS"
  # Verify resource usage sampling: because the Kubernetes metrics API does not report I/O counters,
  # the I/O columns remain empty while CPU and memory columns are populated.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_sb sb_sample_resources fake/px-selftest
  t_equals k8s_sample_rows_leave_io_empty "0 3 25.3 1073741824 3758096384 yes" "$T_STATUS\
 $(printf '%s\n' "$T_STDOUT" | grep -c .) $(printf '%s\n' "$T_STDOUT" | awk -F '\t' \
    '$2 == "regionserver" { print $4, $5, $6, ($7 $8 $9 $10 == "" && NF == 10) ? "yes" : "no" }')"
  : >"$T_K8S_STATE/no-metrics"
  t_k8s_sb sb_sample_resources fake/px-selftest
  t_equals k8s_sample_without_metrics_is_5 5 "$T_STATUS"
  rm -f "$T_K8S_STATE/no-metrics"
  # Verify local image presence checks: because workloads use imagePullPolicy Never, every cluster
  # node must already hold the required container image.
  t_k8s_sb sb_image_present "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k8s_image_absent_from_the_nodes_is_6 6 "$T_STATUS"
  printf 'docker.io/library/%s\n' "$(sed -n 's/^ *image: "\(.*\)"$/\1/p' \
    "$T_K8S_OUT/deploy-k8s/selftest/manifests/28-regionserver.yaml")" >"$T_K8S_STATE/k8s-images"
  t_k8s_sb sb_image_present "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k8s_image_present_on_every_node 0 "$T_STATUS"
  # Verify target cluster version reporting and preflight checks for API server version and client
  # authentication.
  t_k8s_sb sb_version_info
  t_equals k8s_version_info_names_the_target "0 v1.37.2+k3s1 fake" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$1 == "api_server" || $1 == "context" { printf "%s ", $2 }' |
    sed 's/ $//')"
  printf 'v1.27.9\n' >"$T_K8S_STATE/server-version"
  t_k8s_sb sb_preflight fake/px-selftest
  t_equals k8s_preflight_refuses_an_old_api "3 fail" "$T_STATUS $(printf '%s\n' "$T_STDOUT" |
    awk -F '\t' '$1 == "engine_version" { print $2 }')"
  rm -f "$T_K8S_STATE/server-version"
  : >"$T_K8S_STATE/denied"
  t_k8s_sb sb_preflight fake/px-selftest
  t_equals k8s_preflight_names_a_refused_client "3 fail" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$1 == "engine_credentials" { print $2 }')"
  rm -f "$T_K8S_STATE/denied"
  # Verify that running mutating backend operations in dry-run mode issues no delete, apply, or
  # exec calls to kubectl.
  t_shim_log_reset
  t_k8s PX_DRY_RUN=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_kill fake/px-selftest regionserver 0 \
    delete
  t_k8s PX_DRY_RUN=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_teardown fake/px-selftest 0
  t_k8s PX_DRY_RUN=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_provision fake/px-selftest \
    "$T_K8S_OUT/deploy-k8s/selftest"
  t_empty k8s_dry_run_changes_nothing "$(grep -E ' (delete|apply|exec) ' "$T_SHIM_LOG")"
  # Verify the single-deployment target gate that refuses scope creation when another harness
  # namespace already exists on the target cluster.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf 'px-other|other\n' >>"$T_K8S_STATE/k8s-namespaces.txt"
  t_k8s PX_FORCE=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_scope_create selftest
  t_equals k8s_scope_create_refuses_another_harness_namespace "1 yes" "$T_STATUS $(t_flag \
    px-other "$T_STDERR")"
  # Verify namespace ownership protection: an existing namespace with the target scope name that
  # lacks the matching harness cluster label is treated as belonging to another owner and is neither
  # adopted on scope creation nor removed on scope deletion.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf 'px-selftest|\n' >"$T_K8S_STATE/k8s-namespaces.txt"
  rm -f "$T_K8S_OUT/state/k8s/scope-fake_px-selftest.tsv"
  t_k8s_sb sb_scope_create selftest
  line="$T_STATUS $(t_flag 'does not carry the label' "$T_STDERR") $(t_exists \
    "$T_K8S_OUT/state/k8s/scope-fake_px-selftest.tsv")"
  t_k8s_sb sb_scope_exists fake/px-selftest
  line="$line $T_STATUS"
  t_shim_log_reset
  t_k8s_sb sb_scope_delete fake/px-selftest
  t_equals k8s_scope_leaves_a_namespace_of_another_owner "1 yes no 6 1 no px-selftest|" \
    "$line $T_STATUS $(t_flag 'delete namespace' "$(cat "$T_SHIM_LOG")") $(cat \
    "$T_K8S_STATE/k8s-namespaces.txt")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s "PX_K8S_CONTEXT=" "$T_BASH" "$T_WORK/probe/sb.sh" sb_scope_create other
  t_equals k8s_scope_create_needs_a_context "2 yes" "$T_STATUS $(t_flag PX_K8S_CONTEXT \
    "$T_STDERR")"
  : >"$T_K8S_STATE/delete-leaves"
  t_k8s_sb sb_teardown fake/px-selftest 1
  t_equals k8s_teardown_reports_leftovers 1 "$T_STATUS"
  rm -f "$T_K8S_STATE/delete-leaves"
  # Verify optional YARN service group lifecycle management: its manifests are rendered but left
  # unapplied during initial provisioning, started and stopped on demand via apply and StatefulSet
  # deletion, and its persistent volume claims are retained across stops.
  t_k8s_provision -e PX_YARN=1
  t_k8s_sb sb_group_status fake/px-selftest yarn
  line="$T_STATUS $(printf '%s\n' "$T_STDOUT" | cut -f4)"
  t_k8s_sb sb_wait_ready fake/px-selftest 5
  t_equals k8s_parked_group_is_stopped_in_a_whole_wait "down 0 stopped" "${line#* }\
 $T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "nodemanager" { print $3 }')"
  t_shim_log_reset
  t_k8s_sb sb_group_start fake/px-selftest yarn 10
  t_equals k8s_group_start_applies_its_files "0 3 ready" "$T_STATUS $(grep -c \
    ' apply -f .*/groups/yarn/' "$T_SHIM_LOG") $(printf '%s\n' "$T_STDOUT" | awk -F '\t' \
    '$1 == "nodemanager" { print $3 }')"
  t_k8s_sb sb_group_status fake/px-selftest yarn
  line=$T_STDOUT
  t_shim_log_reset
  t_k8s_sb sb_group_stop fake/px-selftest yarn 10
  t_equals k8s_group_stop_deletes_its_statefulsets "0 yes stopped" "$T_STATUS $(t_flag \
    'delete statefulsets -l px.harness.cluster=selftest,px.harness.group=yarn' \
    "$(cat "$T_SHIM_LOG")") $(printf '%s\n' "$T_STDOUT" | awk -F '\t' \
    '$1 == "resourcemanager" { print $3 }')"
  t_k8s_sb sb_group_status fake/px-selftest yarn
  t_equals k8s_group_status_round_trip "up down" "$(printf '%s\n' "$line" | cut -f4)\
 $(printf '%s\n' "$T_STDOUT" | cut -f4)"
}

## @description  Verifies fault injection and drain-aware rolling restarts on the Kubernetes
##               backend (RQ-274, RQ-275).
## @return       Records test assertions. Exit 0.
t_group_k1_faults() {
  local base record calls
  printf '# K1: faults on the Kubernetes backend\n'
  # Verify that a drain-aware restart unloads regions via tooling 0, deletes the target pod, waits
  # for a replacement pod to be created and pass readiness, and reloads regions (RQ-275).
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_shim_log_reset
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 5
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_rolling_restart_orders_unload_delete_wait_load "yes" "$(printf '%s\n' "$calls" |
    awk '/ exec -i tooling-0 .*unload regionserver-0:16020/ { u = NR }
      / delete pod regionserver-0 --wait=true/ { d = NR }
      / get pods / && d && !g { g = NR }
      / exec -i tooling-0 .*load regionserver-0:16020/ && $0 !~ /unload/ { l = NR }
      END { print (u && d > u && g > d && l > g) ? "yes" : "no" }')"
  t_outcome_columns k8s_rolling_restart_reports_five_phases 0 2 "$(t_rows 2 drain ok stop ok \
    start ok ready ok rebalance ok)"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  : >"$T_K8S_STATE/recreate-unready"
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 2
  t_outcome_columns k8s_rolling_restart_expires_on_an_unready_replacement 4 2 \
    "$(t_rows 2 drain ok stop ok start ok ready fail rebalance skip)"
  rm -f "$T_K8S_STATE/recreate-unready"
  # Verify that restarting an unready instance skips the region drain and rebalance phases while
  # still deleting the pod so that the replacement picks up the current render. A running unready
  # container executes the stop phase, whereas a container in a kubelet crash-loop back-off skips
  # the stop phase.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_sb sb_kill fake/px-selftest regionserver 0 hard
  t_shim_log_reset
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 5
  t_equals k8s_restart_of_a_killed_instance_skips_drain "0 drain:skip stop:ok start:ok\
 ready:ok rebalance:skip no yes" "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '{
    printf "%s:%s ", $1, $2 }')$(t_flag 'unload' "$(cat "$T_SHIM_LOG")") $(t_flag \
    'delete pod regionserver-0 --wait=true' "$(cat "$T_SHIM_LOG")")"
  # Verify that a hard kill returns only after the pod status reflects the termination, tolerating
  # both delayed kubelet status updates and transient pod-listing failures.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '2\n' >"$T_K8S_STATE/kill-lags"
  t_k8s_sb sb_kill fake/px-selftest regionserver 0 hard
  line=$T_STATUS
  t_shim_log_reset
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 5
  t_equals k8s_kill_returns_when_the_pod_status_shows_it "0 0 drain:skip stop:ok start:ok\
 ready:ok rebalance:skip no" "$line $T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '{
    printf "%s:%s ", $1, $2 }')$(t_flag 'unload' "$(cat "$T_SHIM_LOG")")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '1000\n' >"$T_K8S_STATE/kill-lags"
  t_k8s PX_TIMEOUT_STOP=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_kill fake/px-selftest \
    regionserver 0 hard
  t_equals k8s_kill_fails_when_the_pod_status_never_shows_it "1 yes" "$T_STATUS $(t_flag \
    'the pod status did not show the kill of regionserver/0' "$T_STDERR")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '2\n' >"$T_K8S_STATE/kill-lists-fail"
  t_k8s_sb sb_kill fake/px-selftest regionserver 0 hard
  t_equals k8s_kill_retries_a_failed_pod_listing "0 gone" "$T_STATUS $(
    [ -e "$T_K8S_STATE/lists-fail" ] && echo left || echo gone)"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  printf '1000\n' >"$T_K8S_STATE/kill-lists-fail"
  t_k8s PX_TIMEOUT_STOP=1 "$T_BASH" "$T_WORK/probe/sb.sh" sb_kill fake/px-selftest \
    regionserver 0 hard
  t_equals k8s_kill_names_a_pod_listing_that_keeps_failing "1 yes" "$T_STATUS $(t_flag \
    'kubectl could not read the pod status of regionserver/0 (status 1)' "$T_STDERR")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_pod regionserver-0 regionserver '' 0 regionmover StatefulSet crash >"$T_K1/pod"
  grep -v '^regionserver-0|' "$T_K8S_STATE/k8s-pods.txt" >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_shim_log_reset
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 5
  t_equals k8s_restart_of_a_waiting_instance_deletes_its_pod "0 drain:skip stop:skip start:ok\
 ready:ok rebalance:skip yes" "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '{
    printf "%s:%s ", $1, $2 }')$(t_flag 'delete pod regionserver-0 --wait=true' \
    "$(cat "$T_SHIM_LOG")")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_k8s_sb sb_restart_drain fake/px-selftest client 0 5 5
  t_equals k8s_restart_needs_the_drain_label 2 "$T_STATUS"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  grep -v '^tooling-0|' "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_restart_drain fake/px-selftest regionserver 0 5 5
  t_equals k8s_restart_needs_tooling_0 "2 yes" "$T_STATUS $(t_flag 'tooling 0' "$T_STDERR")"
  # Verify signal delivery: KILL and TERM target the container init process to terminate the
  # container, whereas all other signals are delivered directly to the daemon JVM process.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_shim_log_reset
  t_k8s_sb sb_signal fake/px-selftest regionserver 0 TERM
  t_equals k8s_term_goes_to_the_init_of_the_pod "0 yes 143" "$T_STATUS $(t_flag \
    "px-exec sh -c kill -s TERM 1 px-term" "$(cat "$T_SHIM_LOG")") $(cut -d'|' -f1,16 \
    "$T_K8S_STATE/k8s-pods.txt" | sed -n 's/^regionserver-0|//p')"
  t_k8s_sb sb_signal fake/px-selftest regionserver 0 QUIT
  t_equals k8s_signal_without_a_jvm_is_6 6 "$T_STATUS"
  # Verify that a scheduled pod kill triggered at a workload phase milestone executes during that
  # phase and is recorded as a planned disruption rather than an unplanned restart (RQ-274).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_shim_log_reset
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/phase-kill.sh" \
    --disrupt 'phase:phase@kill,kill,regionserver,0'
  base="$(t_bundle)"
  record="$(cat "$base/run-record.tsv" 2>/dev/null)"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_scheduled_kill_run_exits_0 0 "$T_STATUS"
  t_equals k8s_scheduled_kill_reaches_the_pod_at_its_milestone "1 yes" "$(printf '%s\n' \
    "$calls" | grep -c ' exec -i regionserver-0 -c px -- .* px-kill$') $({ cat "$base/stages.tsv"
    printf '%s\n' "$record"; } 2>/dev/null | awk -F '\t' '
    $2 == "begin" && $3 == "phase@kill" { b = $1 } $2 == "end" && $3 == "phase@kill" { e = $1 }
    $1 == "action" && $3 == "kill" { a = $2 } $1 == "recover" { i = $3 }
    END { print (b > 0 && i >= b && a >= i && i <= e) ? "yes" : "no" }')"
  t_equals k8s_scheduled_kill_is_in_the_manifest "kill regionserver phase:phase@kill ok" \
    "$(t_manifest "$base" /disruptions/0/action) $(t_manifest "$base" /disruptions/0/role)\
 $(t_manifest "$base" /disruptions/0/when) $(t_manifest "$base" /disruptions/0/recovery)"
  t_equals k8s_scheduled_kill_restart_is_planned "planned" "$(printf '%s\n' "$record" |
    awk -F '\t' '$1 == "diff" && $2 == "regionserver" { print $6 }')"
  t_equals k8s_scheduled_kill_run_names_the_backend k8s "$(t_manifest "$base" /run/backend)"
}

## @description  Verifies that unmodified drivers and cluster checks run on the Kubernetes backend
##               and that psql fixture output matches Compose (RQ-260, RQ-279).
## @return       Records test assertions. Exit 0.
t_group_k1_runs() {
  local run psql dir subject all="" want got="" now file
  printf '# K1: drivers and checks on the Kubernetes backend\n'
  t_empty no_runtime_token_in_drivers_suites_plugins "$(grep -rnE "$T_RUNTIME_RE" \
    "$PX_HOME/drivers" "$PX_HOME/suites" "$PX_HOME/plugins" 2>/dev/null)"
  # Verify that the unmodified psql, pherf, and java-main drivers execute cleanly through all six
  # lifecycle verbs on the Kubernetes backend (RQ-260).
  printf '%s\n' 'CREATE TABLE T1 (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR)' \
    '  UPDATE_CACHE_FREQUENCY=60000;' >"$T_K1/schema.sql"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest psql -- --prepare "$T_K1/schema.sql" \
    "$T_A5/sql/fixture.sql"
  psql="$(t_bundle)"
  t_equals k8s_psql_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0 k8s 0" "$T_STATUS $(t_verb_codes "$psql") $(t_manifest "$psql" /run/backend)\
 $(t_validate "$psql" >/dev/null; printf '%s' "$T_STATUS")"
  # Verify that cluster checks executed during a Kubernetes backend run enforce the Kubernetes
  # recovery bound.
  t_equals k8s_run_bundle_holds_the_kubernetes_recovery_bound "recovery.seconds.max=220" \
    "$(grep '^recovery\.seconds\.max=' "$psql/thresholds.properties" 2>/dev/null)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "PX_PHOENIX_HOME=$T_A5/phoenix" "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" \
    "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest pherf -- \
    --scenario "$PX_HOME/suites/pherf/px_functional_scenario.xml" \
    --schema "$PX_HOME/suites/pherf/px_functional_schema.sql"
  run="$(t_bundle)"
  t_equals k8s_pherf_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0 restore:0\
 report:0" "$T_STATUS $(t_verb_codes "$run")"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest java-main -- org.example.Fixture
  run="$(t_bundle)"
  t_equals k8s_java_main_run_passes_every_verb "0 describe:0 validate:0 prepare:0 run:0\
 restore:0 report:0" "$T_STATUS $(t_verb_codes "$run")"
  t_equals k8s_drivers_pass_every_verb 0 "$(t_validate "$run" >/dev/null; printf '%s' \
    "$T_STATUS")"
  # Verify that the cluster health check script and check-cluster suite run unmodified against the
  # fake Kubernetes cluster (RQ-260).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  dir="$(t_check_bundle k8s)"
  t_k1_a5 "PX_RUN_DIR=$dir" "PX_THRESHOLDS_FILE=$dir/thresholds.properties" \
    PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation "$T_BASH" \
    "$PX_HOME/suites/checks/cluster.sh"
  for subject in wrapper restart oom log gc recovery post_recovery; do
    all="$all $(t_severity_of "$dir" "cluster.$subject")"
  done
  t_equals k8s_cluster_checks_pass_unchanged "0 PASS PASS PASS PASS PASS PASS PASS" \
    "$T_STATUS$all"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  dir="$(t_check_bundle k8s-suite)"
  t_k1_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation \
    "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals k8s_check_cluster_suite_runs_unchanged 0 "$T_STATUS"
  # Verify that the Kubernetes backend defines its own recovery bound as a single threshold
  # configuration setting in k8s/env/k8s.env, combining the 120 s Compose gate with the additional
  # time that pod CPU limits introduce during a drain-aware restart. A drain-aware restart measured
  # at 168.6 s on k3d in K3 passes under the Kubernetes bound while failing the Compose gate, and
  # an explicit operator override still takes precedence over the backend default.
  now="$(px_epoch_ms)"
  for subject in k8s compose; do
    dir="$(t_check_bundle "bound-$subject" "$(t_rows 5 recover 1 "$((now - 170000))" \
      "$((now - 1400))" ok)")"
    t_k1_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID= PX_RUN_CLASS=validation "PX_BACKEND=$subject" \
      "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
    got="$got $subject:$T_STATUS:$(t_severity_of "$dir" cluster.recovery):$(sed -n \
      's/^recovery\.seconds\.max=//p' "$dir/thresholds.properties" 2>/dev/null)"
  done
  t_equals k8s_recovery_bound_passes_a_drain_aware_restart " k8s:0:PASS:220 compose:0:FAIL:120" \
    "$got"
  t_equals k8s_recovery_bound_is_in_force "# config:PX_THRESHOLD_RECOVERY_SECONDS_MAX" \
    "$(awk '/^recovery\.seconds\.max=/ { print p } { p = $0 }' \
      "$PX_OUT/checks/bound-k8s/thresholds.properties" 2>/dev/null)"
  t_equals compose_recovery_bound_stays_120 "recovery.seconds.max=120 no" "$(grep \
    '^recovery\.seconds\.max=' "$dir/thresholds.properties" 2>/dev/null) $(t_flag \
    RECOVERY_SECONDS "$(cat "$dir/thresholds.properties" 2>/dev/null)")"
  dir="$(t_check_bundle bound-operator)"
  t_k1_a5 "PX_RUN_DIR=$dir" PX_SCOPE_ID= PX_RUN_CLASS=validation PX_BACKEND=k8s \
    PX_THRESHOLD_RECOVERY_SECONDS_MAX=300 "$T_BASH" "$PX_HOME/suites/check-cluster.sh"
  t_equals operator_recovery_bound_wins_on_k8s "0 recovery.seconds.max=300" "$T_STATUS $(grep \
    '^recovery\.seconds\.max=' "$dir/thresholds.properties" 2>/dev/null)"
  # Verify that the recovery bound matches the documented sizing formula: across every shipped
  # sizing profile, each container receives ceil(MiB x PX_K8S_CPU_MILLI_PER_GIB / 1024) millicores,
  # and a CPU-bound phase requiring W core-seconds takes W x 1000 / c seconds under a limit of
  # c < 1000 millicores. A drain-aware restart launches two region mover JVMs in tooling 0 at
  # 6.25 core-seconds each (45 to 50 s at 125m in K3) and one RegionServer JVM requiring 16.7 s to
  # reach readiness on Compose (K4). The resulting bound equals the base gate plus the worst-case
  # throttling penalty rounded up to a 10 s multiple, ensuring that any change to the CPU ratio or
  # pod memory sizing fails this test until k8s/env/k8s.env is updated accordingly.
  got="$(for file in class-validation.env class-measurement.env override-24gib.env; do
    printf '%s ' "$file"
    sed -nE 's/^PX_(TOOLING|RS)_MEM_MIB=/\1 /p' "$PX_HOME/docker/env/$file" | tr '\n' ' '
    printf '\n'
  done | awk -v base="$(sed -n 's/^recovery\.seconds\.max=//p' \
    "$PX_HOME/suites/thresholds.properties")" -v ratio="$(sed -n \
    's/^PX_K8S_CPU_MILLI_PER_GIB=//p' "$PX_HOME/k8s/env/k8s.env")" '
    function milli(mib) { return int((mib * ratio + 1023) / 1024) }
    function slow(work, c) { return (c < 1000) ? work * (1000 / c - 1) : 0 }
    { for (i = 2; i < NF; i += 2) mem[$i] = $(i + 1)
      p = 2 * slow(6.25, milli(mem["TOOLING"])) + slow(16.7, milli(mem["RS"]))
      if (p > worst) worst = p }
    END { b = base + worst; print (b % 10) ? (int(b / 10) + 1) * 10 : b }')"
  t_equals k8s_recovery_bound_follows_the_cpu_ratio "$got" "$(sed -n \
    's/^PX_THRESHOLD_RECOVERY_SECONDS_MAX=//p' "$PX_HOME/k8s/env/k8s.env")"
  # Verify backend parity by confirming that executing the psql fixture against the fake Kubernetes
  # backend produces statement and verdict records identical to the fake Compose backend (RQ-279).
  t_a5_state_reset
  run="$(t_a5_bundle)"
  t_a5_verb psql run "$run" "$T_A5/sql/fixture.sql"
  t_a5_verb psql report "$run" "$T_A5/sql/fixture.sql"
  want="$(cat "$run/drivers/psql/statements.tsv" 2>/dev/null)$PX_NL---$PX_NL$(cut -f1-3,5 \
    "$run/verdicts.tsv" 2>/dev/null)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  run="$(t_a5_bundle)"
  for subject in run report; do
    t_k1_a5 "PX_RUN_DIR=$run" "PX_RUN_ID=${run##*/}" PX_DRIVER_NAME=psql \
      "PX_DRIVER_DIR=$run/drivers/psql" "PX_VERB=$subject" PX_RECORD_SOURCE=driver:psql \
      PX_RUN_CLASS=validation PX_SCOPE_ID=fake/px-selftest "PX_PHOENIX_HOME=$T_A5/phoenix" \
      "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$PX_HOME/drivers/psql/driver.sh" "$subject" \
      "$T_A5/sql/fixture.sql"
  done
  got="$(cat "$run/drivers/psql/statements.tsv" 2>/dev/null)$PX_NL---$PX_NL$(cut -f1-3,5 \
    "$run/verdicts.tsv" 2>/dev/null)"
  t_equals k8s_psql_fixture_matches_compose "$want" "$got"
  t_nonempty k8s_psql_fixture_records_statements "$(cat "$run/drivers/psql/statements.tsv" \
    2>/dev/null)"
}

## @description  Verifies the three-layer isolation boundary between the Compose and Kubernetes
##               backends and audits the aggregate kubectl call log for the entire round (RQ-279).
## @return       Records test assertions. Exit 0.
t_group_k1_boundary() {
  local hits calls run line root="$T_WORK/scan-class"
  printf '# K1: the boundary of two backends\n'
  # Layer 1 static token scan: verify that each backend implementation references only the CLI
  # tokens belonging to its own runtime class.
  mkdir -p "$root/lib/backend" "$root/drivers/x" "$root/bin"
  printf '#!/usr/bin/env bash\n# A fixture.\ndocker ps\nkubectl get pods\n' \
    >"$root/lib/backend/compose.sh"
  cat >"$root/lib/backend/k8s.sh" <<'K1_EOF'
#!/usr/bin/env bash
# A fixture.
kubectl get pods
docker ps
command "$PX_DOCKER_BIN" ps
K1_EOF
  printf '#!/usr/bin/env bash\n# A fixture.\nkubectl get pods\n' >"$root/drivers/x/driver.sh"
  cat >"$root/bin/px-x" <<'K1_EOF'
#!/usr/bin/env bash
# A fixture.
command "$PX_KUBECTL_BIN" get pods
K1_EOF
  hits="$(t_scan_root "$root")"
  t_equals scan_holds_each_backend_to_its_token_class "bin/px-x:3 drivers/x/driver.sh:3\
 lib/backend/compose.sh:4 lib/backend/k8s.sh:4 lib/backend/k8s.sh:5" "$(printf '%s\n' "$hits" |
    cut -d: -f1,2 | tr '\n' ' ' | sed 's/ $//')"
  t_empty compose_backend_names_no_kubernetes_tool "$(grep -vE '^[[:space:]]*#' \
    "$PX_HOME/lib/backend/compose.sh" | grep -E "$T_NOT_COMPOSE_RE")"
  t_empty k8s_backend_names_no_container_runtime "$(grep -vE '^[[:space:]]*#' \
    "$PX_HOME/lib/backend/k8s.sh" | grep -E "$T_NOT_K8S_RE")"
  # Construct the synthetic bypass fixture strings via variable interpolation so that this test
  # script itself contains no literal bypass token sequences.
  line='type -P'
  printf '#!/usr/bin/env bash\n# A fixture.\nx="%s %s kubectl)"\ny="%s %s docker)"\n' \
    "\$(" "$line" "\$(" "$line" >"$root/lib/backend/k8s.sh"
  t_equals bypass_scan_holds_each_backend_to_its_class "lib/backend/k8s.sh:4" \
    "$(t_scan_bypass "$root" | cut -d: -f1,2 | tr '\n' ' ' | sed 's/ $//')"
  t_contains allowlist_stale_kubectl_backend_is_reported \
    'lib/backend/k8s.sh: no kubectl token left' "$(t_allowlist_stale "$T_WORK/stale")"
  # Layer 2 runtime trap enforcement: verify that the Kubernetes backend invokes kubectl via its
  # resolved binary path while any bare kubectl invocation in shell code is blocked by the trap.
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_shim_log_reset
  t_k8s_sb sb_version_info
  t_equals k8s_backend_reaches_kubectl_by_path "0 yes" "$T_STATUS $(t_flag \
    'kubectl --context fake --namespace px-selftest version -o json' "$(cat "$T_SHIM_LOG")")"
  rm -rf "$T_K1/trap"
  mkdir -p "$T_K1/trap"
  cp -R "$PX_HOME/lib" "$PX_HOME/k8s" "$T_K1/trap/"
  # Rewrite the copied backend to invoke kubectl by its bare command name in the shell that
  # sourced lib/common.sh, so that the runtime trap function defined there intercepts the call.
  cat >"$T_K1/trap.sed" <<'K1_EOF'
s|set -- "$_PXK_KUBECTL" --context|set -- kubectl --context|
s|    px_timeout "$timeout" "$@" \|\| status=$?|    "$@" \|\| status=$?|
K1_EOF
  sed -i.bak -f "$T_K1/trap.sed" "$T_K1/trap/lib/backend/k8s.sh"
  t_k8s "PX_HOME=$T_K1/trap" "$T_BASH" "$T_WORK/probe/sb.sh" sb_list fake/px-selftest
  t_equals k8s_bare_kubectl_hits_the_trap "1 yes" "$T_STATUS $(t_flag 'call blocked: kubectl' \
    "$T_STDERR")"
  # Layer 3 negative and positive controls: verify that non-cluster entry points and dry-run modes
  # on the Kubernetes backend invoke no external runtime tools, whereas live queries pass the
  # context, namespace, and cluster label selector.
  t_mgen --class validation --cluster selftest
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s config
  t_equals negative_control_k8s_config "0 no" "$T_STATUS $(t_flag kubectl "$(cat "$T_SHIM_LOG")")"
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --dry-run down -e PX_SCOPE_ID=fake/px-selftest
  t_equals negative_control_k8s_down_dry_run "0" "$T_STATUS$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_mgen --class validation --cluster selftest
  t_equals negative_control_k8s_render "0" "$T_STATUS$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_k8s_sb sb_bound_backend
  t_equals negative_control_k8s_bind "0 k8s" "$T_STATUS $T_STDOUT$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_k8s_sb sb_list fake/px-selftest
  t_contains k8s_positive_control_carries_context_namespace_and_selector \
    'kubectl --context fake --namespace px-selftest get pods -l px.harness.cluster=selftest' \
    "$(cat "$T_SHIM_LOG")"
  t_contains k8s_positive_control_reports_the_instance \
    "regionserver${PX_TAB}0${PX_TAB}regionserver-0" "$T_STDOUT"
  # Verify that the internal kubectl binary path variable is scrubbed from child driver
  # environments and that workload runs on each backend invoke only their own backend tool.
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_shim_log_reset
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/kubectl-var.sh"
  run="$(t_bundle)"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_hostile_driver_run_succeeds 0 "$T_STATUS"
  t_contains k8s_hostile_driver_sees_no_kubectl_variable 'kubectl variable []' \
    "$(cat "$run/logs/run.log" 2>/dev/null)"
  t_absent k8s_hostile_driver_reaches_no_kubectl 'hostile-driver' "$calls"
  t_empty k8s_run_reaches_no_container_runtime "$(printf '%s\n' "$calls" | grep -v '^kubectl ' |
    grep .)"
  t_shim_state_reset
  t_shim_log_reset
  t_px_run engine-var
  t_empty compose_run_reaches_no_kubectl "$(grep '^kubectl' "$T_SHIM_LOG")"
  # Audit the complete round call log: every kubectl invocation must specify both the context and
  # the namespace, and every call that selects harness objects must carry the cluster label
  # selector, whereas version, auth, and get nodes queries read target cluster facts (RQ-279).
  calls="$(cat "$T_K1/calls.log")"
  t_nonempty k8s_round_log_holds_calls "$(printf '%s\n' "$calls" | sed -n 1p)"
  t_empty k8s_every_call_carries_context_and_namespace "$(printf '%s\n' "$calls" |
    grep -v '^kubectl --context [^ ][^ ]* --namespace [^ ][^ ]* ')"
  t_empty k8s_every_selecting_call_carries_the_cluster_selector "$(printf '%s\n' "$calls" |
    awk '{ v = $6; o = $7 }
      (v == "get" || v == "delete" || v == "top") &&
      o ~ /(^|,)(pods|statefulsets|jobs|pvc|namespaces)(,|$)/ &&
      $0 !~ / -l px\.harness\.cluster/ { print }')"
}

# ---------------------------------------------------------------------------
# K2: Kubernetes preflight and safety checks, target files, image promotion, the service mesh
# overlay, and the local cluster manager
# ---------------------------------------------------------------------------
# Every test case executes against the fake shims: the fake kubectl, the stateful fake k3d, the fake
# registry built into the fake container runtime, and the record-only registry client shims. No test
# case contacts a real cluster, an external registry, or the network. Each test case places its
# target configuration file inside the temporary fixture directory outside the working tree,
# matching how an operator stores a target file outside the repository.

## @description  Initializes the round K2 fixture directory, the pinned k3s image reference, and the
##               stub drivers that mutate fake kubectl pod state during workload execution.
## @return       Populates the round K2 fixtures. Exit 0.
t_fixtures_k2() {
  T_K2="$T_WORK/k2"
  mkdir -p "$T_K2"
  : >"$T_WORK/forward.pids"
  T_K3S_PIN="rancher/k3s:v1.37.2-k3s1@sha256:$(printf 'c%.0s' $(seq 1 64))"
  cat >"$T_WORK/drivers/k8s-restart-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. A SIGKILL ends the container of regionserver 0, and the
# kubelet starts it again at once: its restart count rises, it runs, and its
# last state holds the exit code 137. Then the driver works on for 30 seconds.
pods="$PX_FAKE_STATE_DIR/k8s-pods.txt"
awk -F '|' -v OFS='|' '$1 == "regionserver-0" { $19 = $19 + 1; $16 = 137; $17 = "Error"
  $18 = "2026-01-03T00:00:07Z" } { print }' "$pods" >"$pods.new" && mv -f "$pods.new" "$pods"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/k8s-unknown-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. The node of regionserver 0 stops reporting, so a listing of
# all pods finds its state unknown, while a read of that instance alone finds
# it running again with no new restart, and with a last state that holds an
# exit code 1 from before the run. Then the driver works on for 30 seconds.
pods="$PX_FAKE_STATE_DIR/k8s-pods.txt"
awk -F '|' -v OFS='|' '$1 == "regionserver-0" { $16 = 1; $17 = "Error"
  $18 = "2026-01-02T00:00:00Z" } { print }' "$pods" >"$PX_FAKE_STATE_DIR/k8s-view.txt"
printf 'role=regionserver\n' >"$PX_FAKE_STATE_DIR/k8s-view-sel"
awk -F '|' -v OFS='|' '$1 == "regionserver-0" { $8 = "Unknown"; $10 = "False"; $11 = "" }
  { print }' "$pods" >"$pods.new" && mv -f "$pods.new" "$pods"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/k8s-restart-unreachable.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. As k8s-restart-wait.sh, the container of regionserver 0 ends
# with exit code 137 and runs again. After the next listing of that instance
# alone, the API server can no longer be reached. Then the driver works on.
pods="$PX_FAKE_STATE_DIR/k8s-pods.txt"
awk -F '|' -v OFS='|' '$1 == "regionserver-0" { $19 = $19 + 1; $16 = 137; $17 = "Error"
  $18 = "2026-01-03T00:00:07Z" } { print }' "$pods" >"$pods.new"
printf 'role=regionserver\n' >"$PX_FAKE_STATE_DIR/k8s-next-sel"
printf 'unreachable\n' >"$PX_FAKE_STATE_DIR/k8s-next-flag"
printf '1\n' >"$PX_FAKE_STATE_DIR/k8s-next-after"
cp "$pods.new" "$PX_FAKE_STATE_DIR/k8s-next.txt"
mv -f "$pods.new" "$pods"
exec sleep 30
DRIVER_EOF
  cat >"$T_WORK/drivers/k8s-oom-wait.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
. "${0%/*}/stub-lib.sh" "$@"
shift
# A stub driver. An out-of-memory kill ends the container of client 0, a
# standalone pod: it terminates with the reason OOMKilled. Then the driver
# works on for 30 seconds.
pods="$PX_FAKE_STATE_DIR/k8s-pods.txt"
awk -F '|' -v OFS='|' '$1 == "client-0" { $8 = "Failed"; $10 = "False"; $11 = ""
  $13 = 137; $14 = "OOMKilled"; $15 = "2026-01-03T00:00:07Z" } { print }' "$pods" \
  >"$pods.new" && mv -f "$pods.new" "$pods"
exec sleep 30
DRIVER_EOF
  chmod +x "$T_WORK/drivers/k8s-restart-wait.sh" "$T_WORK/drivers/k8s-oom-wait.sh" \
    "$T_WORK/drivers/k8s-unknown-wait.sh" "$T_WORK/drivers/k8s-restart-unreachable.sh"
}

## @description  Materializes a target configuration file (name, NAME=VALUE...) in the round K2
##               fixture directory.
## @return       Writes the target file path to stdout. Exit 0.
t_k2_target() {
  local file="$T_K2/$1.env"
  shift
  printf '%s\n' "$@" >"$file"
  printf '%s\n' "$file"
}

## @description  Formats a fake kubectl resource quota record (name, resource, hard, used) in the
##               backend field order, leaving all other quota resource columns empty.
## @return       Writes the formatted quota line to stdout. Exit 0.
t_k2_quota() {
  local key line=$1
  for key in requests.memory limits.memory memory requests.cpu limits.cpu cpu pods \
    persistentvolumeclaims requests.storage; do
    if [ "$key" = "$2" ]; then
      line="$line|$3|$4"
    else
      line="$line||"
    fi
  done
  printf '%s\n' "$line"
}

## @description  Resets the round K2 Kubernetes fixture state to an empty baseline with no pods,
##               namespaces, state records, or rendered manifests.
## @return       Clears the Kubernetes test state directories. Exit 0.
t_k2_reset() {
  rm -rf "$T_K8S_STATE" "$T_K8S_OUT/state" "$T_K8S_OUT/deploy-k8s"
  mkdir -p "$T_K8S_STATE"
}

## @description  Executes scripts/preflight-k8s.sh (NAME=VALUE..., option...) against the fake
##               kubectl using the k3d config file for its StorageClass and the selftest deployment.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_pfk() {
  local envs=()
  while [ $# -gt 0 ] && [[ $1 =~ ^PX_[A-Z0-9_]*= ]]; do
    envs+=("$1")
    shift
  done
  t_k8s ${envs[@]+"${envs[@]}"} "$T_BASH" "$PX_HOME/scripts/preflight-k8s.sh" --config \
    "$PX_HOME/k8s/env/k3d.env" --deployment "$T_K8S_OUT/deploy-k8s/selftest" "$@"
}

## @description  Executes bin/px-k3d (argument...) against the fake shims on the k3d-px context
##               using the fixture-pinned k3s image digest.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_k3d() {
  t_k8s PX_K8S_CONTEXT=k3d-px "PX_K3S_IMAGE=$T_K3S_PIN" "$T_BASH" "$T_BIN/px-k3d" "$@"
}

## @description  Counts the existing entries in a directory (directory, glob) that match a glob.
## @return       Writes the matching entry count to stdout. Exit 0.
t_k2_count() {
  local file n=0
  for file in "$1"/$2; do
    [ -e "$file" ] && n=$((n + 1))
  done
  printf '%s\n' "$n"
}

## @description  Extracts the status column for a named preflight check row (rows, check).
## @return       Writes the status to stdout, or an empty line when no row matches. Exit 0.
t_k2_row() { printf '%s\n' "$1" | awk -F '\t' -v c="$2" '$1 == c { s = $2 } END { print s }'; }

## @description  Flattens each rendered workload YAML document (directory) into a single
##               pipe-delimited line.
## @return       Writes the flattened workload document lines to stdout. Exit 0.
t_k2_workloads() {
  local file
  for file in "$1"/manifests/*.yaml "$1"/groups/*/*.yaml; do
    [ -f "$file" ] && t_k8s_docs "$file"
  done | grep -E '\|kind: (StatefulSet|Job|Pod)\|'
}

## @description  Seeds the round K1 selftest cluster fixture (state, root) under an alternate
##               context (context), recording the <context>/px-selftest scope and its state files.
## @return       Populates the context-scoped cluster state records. Exit 0.
t_k2_seed_on() {
  local ctx=$1 state=$2 root=$3
  t_k8s_seed "$state" "$root"
  printf '%s/px-selftest\n' "$ctx" >"$root/state/scope-k8s-selftest"
  rm -f "$root/state/k8s/scope-fake_px-selftest.tsv"
  printf 'scope\t%s/px-selftest\ndeployment_dir\t%s\nnamespace_owner\tharness\n' "$ctx" \
    "$T_K1/deploy-selftest" >"$root/state/k8s/scope-${ctx}_px-selftest.tsv"
}

## @description  Verifies the round K2 manifest generator: placement, remote images, requests table,
##               and mesh overlay (RQ-293, RQ-294, RQ-297, RQ-301, RQ-305, RQ-306, RQ-309).
## @return       Records test assertions. Exit 0.
t_group_k2_render() {
  local dir on off target sel bad="" doc file line want got digest sums docs
  printf '# K2: the generator\n'
  t_k2_reset
  # Verify that target node selector and toleration placement rules propagate to every rendered pod
  # specification (RQ-301).
  sel="$(t_k2_target place PX_K8S_STORAGE_CLASS=fast PX_K8S_NODE_SELECTOR=pool=phoenix \
    PX_K8S_TOLERATIONS=dedicated=phoenix:NoSchedule)"
  t_k8s "PX_K8S_TARGET=$sel" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class measurement \
    --cluster place -e PX_YARN=1 -e PX_SNN=1
  dir="$T_K8S_OUT/deploy-k8s/place"
  docs="$(t_k2_workloads "$dir")"
  while IFS= read -r doc; do
    [[ $doc == *'nodeSelector:|'*' pool: "phoenix"|'* ]] || bad="$bad selector:${doc:0:60};"
    [[ $doc == *'tolerations:|'*'- key: "dedicated"|'*'operator: Equal|'*'value: "phoenix"|'* ]] ||
      bad="$bad toleration:${doc:0:60};"
  done <<EOF
$docs
EOF
  t_equals target_placement_reaches_every_pod_spec "0 12" "$T_STATUS $(printf '%s\n' "$docs" |
    grep -c .)$bad"
  bad=""
  for file in "$dir"/manifests/2?-*.yaml "$dir"/groups/*/2?-*.yaml; do
    line="$(sed -n 's/^  serviceName: //p' "$file")"
    got="$(t_k8s_docs "$file" | grep '|kind: StatefulSet|' | grep -c \
      'preferredDuringSchedulingIgnoredDuringExecution:|.*topologyKey: kubernetes.io/hostname|')"
    case "$line" in
    datanode | regionserver) [ "$got" = 1 ] || bad="$bad $line has no spread;" ;;
    *) [ "$got" = 0 ] || bad="$bad $line spreads;" ;;
    esac
  done
  t_empty datanodes_and_regionservers_spread_by_host "$bad"
  t_mgen --class validation --cluster place -e PX_K8S_NODE_SELECTOR=pool --check
  t_equals bad_node_selector_is_refused "2 yes" "$T_STATUS $(t_flag PX_K8S_NODE_SELECTOR \
    "$T_STDERR")"
  t_mgen --class validation --cluster place -e PX_K8S_TOLERATIONS=a=b:Sometimes --check
  t_equals bad_toleration_is_refused "2 yes" "$T_STATUS $(t_flag PX_K8S_TOLERATIONS \
    "$T_STDERR")"
  # Verify that the rendered resource requests summary table accurately reflects the CPU, memory,
  # and storage requests declared across all generated workload manifests.
  got="$(for file in "$dir"/manifests/*.yaml "$dir"/groups/*/*.yaml; do
    awk '/^kind: (StatefulSet|Job|Pod)$/ { k = $2 }
      /^kind: (Service|Namespace|ConfigMap)$/ { k = "" }
      k != "" && /^  name: / && n == "" { n = $2 }
      k == "StatefulSet" && /^  replicas: / { r = $2 }
      k != "" && /^ *requests:$/ { q = 1; next } q && /cpu:/ { c = $2; sub(/m$/, "", c) }
      q && /memory:/ { m = $2; sub(/Mi$/, "", m); q = 0 }
      /^ +storage: / { g = $2; sub(/Gi$/, "", g); s++ }
      END { if (k != "") print k, n, (r == "" ? 1 : r), c, m, s + 0, g + 0 }' \
      "$file"
  done | LC_ALL=C sort)"
  want="$(grep -v '^#' "$dir/requests.tsv" | tr '\t' ' ' | LC_ALL=C sort)"
  t_equals k8s_render_requests_match_the_manifests "$want" "$got"
  # Verify that rendering manifests for a remote registry pins every container image reference to
  # its promoted digest (RQ-306).
  digest="sha256:$(printf 'd%.0s' $(seq 1 64))"
  target="$(t_k2_target remote PX_K8S_STORAGE_CLASS=fast PX_K8S_REGISTRY=reg.example:5000/phx \
    PX_K8S_PULL_SECRET=regcred)"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster remote -e PX_IMAGE_TAG=phoenix-harness:selftest \
    -e "PX_K8S_IMAGE_DIGESTS=phoenix-harness:selftest=$digest"
  dir="$T_K8S_OUT/deploy-k8s/remote"
  t_equals remote_render_references_bundle_digests \
    "0 reg.example:5000/phx/phoenix-harness@$digest IfNotPresent" "$T_STATUS $(cat \
    "$dir"/manifests/*.yaml | sed -n 's/^ *image: "\(.*\)"$/\1/p' | LC_ALL=C sort -u) $(cat \
    "$dir"/manifests/*.yaml | sed -n 's/^ *imagePullPolicy: //p' | LC_ALL=C sort -u)"
  docs="$(t_k2_workloads "$dir")"
  t_equals remote_render_lists_the_pull_secret_on_every_pod "8 8" "$(printf '%s\n' "$docs" |
    grep -c .) $(printf '%s\n' "$docs" | grep -cE 'imagePullSecrets:\| *- name: regcred\|')"
  t_k8s_sb sb_image_present "$dir"
  t_equals k8s_image_check_admits_digest_references "0 $digest no" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | cut -f2 | LC_ALL=C sort -u) $(t_flag 'get nodes' "$(cat "$T_SHIM_LOG")")"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster nodigest -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals remote_render_without_digests_is_refused "2 yes no" "$T_STATUS $(t_flag \
    'px-cluster push' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/nodigest")"
  # Because container runtimes interpret a leading registry path component that lacks a dot, a port
  # number, or the literal localhost as a Docker Hub namespace, the manifest generator rejects it.
  want="PX_K8S_IMAGE_DIGESTS=phoenix-harness:selftest=$digest"
  t_mgen --class validation --cluster hub -e PX_K8S_REGISTRY=apurtell \
    -e PX_IMAGE_TAG=phoenix-harness:selftest -e "$want"
  line="$T_STATUS $(t_flag PX_K8S_REGISTRY "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/hub")"
  t_mgen --class validation --cluster hub -e PX_K8S_REGISTRY=localhost:5000/phx \
    -e PX_IMAGE_TAG=phoenix-harness:selftest -e "$want"
  t_equals remote_render_refuses_a_registry_without_a_host "2 yes no 0" "$line $T_STATUS"
  # Verify the Istio service mesh overlay behavior, including namespace injection labels, core
  # daemon opt-in and opt-out annotations, and job sidecar exclusion (RQ-305, RQ-293, RQ-294,
  # RQ-309).
  t_mgen --class validation --cluster mesh -e PX_YARN=1 -e PX_SNN=1 --out deploy-k8s/off
  off="$T_K8S_OUT/deploy-k8s/off"
  t_mgen --class validation --cluster mesh -e PX_YARN=1 -e PX_SNN=1 -e PX_K8S_MESH=1 \
    --out deploy-k8s/on
  on="$T_K8S_OUT/deploy-k8s/on"
  t_equals mesh_overlay_differs_only_by_label_and_annotations "" "$(diff -r "$off" "$on" |
    grep '^[<>]' |
    grep -vE '^> +(istio-injection: "enabled"|annotations:|sidecar\.istio\.io/inject: "false")$')"
  t_equals mesh_off_render_has_neither "0 1" "$(grep -rc 'istio-injection' "$off" |
    awk -F: '{ s += $2 } END { print s }') $(grep -rc 'sidecar.istio.io/inject' "$off" |
    awk -F: '{ s += $2 } END { print s }')"
  bad=""
  for line in zookeeper namenode secondarynamenode datanode resourcemanager nodemanager \
    jobhistory hbase-master regionserver client tooling; do
    file="$(t_k8s_role_file "$on" "$line")"
    t_k8s_docs "$file" | grep -E '\|kind: (StatefulSet|Pod)\|' |
      grep -q 'annotations:| *sidecar.istio.io/inject: "false"|' || bad="$bad $line;"
  done
  t_empty mesh_overlay_keeps_core_daemons_out "$bad"
  t_equals mesh_overlay_labels_the_namespace 1 "$(grep -c '^    istio-injection: "enabled"$' \
    "$on/manifests/00-namespace.yaml")"
  t_mgen --class validation --cluster mesh -e PX_YARN=1 -e PX_K8S_MESH=1 -e PX_K8S_MESH_CORE=1 \
    --out deploy-k8s/core
  t_equals mesh_overlay_opt_in_injects_core_daemons "10 0" "$(cat \
    "$T_K8S_OUT"/deploy-k8s/core/manifests/*.yaml "$T_K8S_OUT"/deploy-k8s/core/groups/*/*.yaml |
    grep -c 'sidecar.istio.io/inject: "true"') $(cat \
    "$T_K8S_OUT"/deploy-k8s/core/manifests/2*.yaml \
    "$T_K8S_OUT"/deploy-k8s/core/groups/*/*.yaml "$T_K8S_OUT"/deploy-k8s/core/manifests/4*.yaml |
    grep -c 'sidecar.istio.io/inject: "false"')"
  bad=""
  for file in "$off/manifests/30-setup.yaml" "$on/manifests/30-setup.yaml" \
    "$T_K8S_OUT/deploy-k8s/core/manifests/30-setup.yaml"; do
    t_k8s_docs "$file" | grep '|kind: Job|' |
      grep -q '|    metadata:|      annotations:|        sidecar.istio.io/inject: "false"|' ||
      bad="$bad ${file#"$T_K8S_OUT"/};"
  done
  t_empty every_job_opts_out_of_the_sidecar "$bad"
  t_mgen --class measurement --cluster flat
  t_equals measurement_render_is_flat_by_default "0 0 1" "$T_STATUS $(cat \
    "$T_K8S_OUT"/deploy-k8s/flat/manifests/*.yaml | grep -c 'istio-injection') $(cat \
    "$T_K8S_OUT"/deploy-k8s/flat/manifests/*.yaml | grep -c 'sidecar.istio.io/inject')"
  target="$(t_k2_target meshed PX_K8S_STORAGE_CLASS=fast PX_K8S_MESH=1)"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster meshed
  t_equals validation_render_with_mesh_carries_the_overlay "0 1 8" "$T_STATUS $(cat \
    "$T_K8S_OUT"/deploy-k8s/meshed/manifests/*.yaml | grep -c 'istio-injection: "enabled"')\
 $(cat "$T_K8S_OUT"/deploy-k8s/meshed/manifests/*.yaml | grep -c 'sidecar.istio.io/inject')"
  # Verify that rendering manifests across two distinct target files and both run classes applies
  # each target's configuration and class sizing without modifying the source templates (RQ-297).
  sums="$(cd "$PX_HOME/k8s" && cat templates/*.tmpl overlays/mesh/*.tmpl | cksum)"
  t_k2_target ta PX_K8S_STORAGE_CLASS=class-a PX_K8S_NODE_SELECTOR=pool=a >/dev/null
  t_k2_target tb PX_K8S_STORAGE_CLASS=class-b PX_K8S_TOLERATIONS=spot:NoSchedule >/dev/null
  bad=""
  for target in ta tb; do
    for line in validation measurement; do
      t_k8s "PX_K8S_TARGET=$T_K2/$target.env" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" \
        --class "$line" --cluster "$target-$line"
      dir="$T_K8S_OUT/deploy-k8s/$target-$line"
      [ "$T_STATUS" = 0 ] || bad="$bad $target-$line exit $T_STATUS;"
      grep -q "storageClassName: class-${target#t}$" "$(t_k8s_role_file "$dir" zookeeper)" ||
        bad="$bad $target-$line storage;"
      case "$target" in
      ta) grep -q 'pool: "a"' "$dir/manifests/28-regionserver.yaml" || bad="$bad ta selector;" ;;
      tb) grep -q 'key: "spot"' "$dir/manifests/28-regionserver.yaml" || bad="$bad tb tol;" ;;
      esac
    done
    dir="$T_K8S_OUT/deploy-k8s/$target"
    [ "$(grep -h 'memory:' "$dir-validation/manifests/28-regionserver.yaml" | sed -n 1p)" != \
      "$(grep -h 'memory:' "$dir-measurement/manifests/28-regionserver.yaml" | sed -n 1p)" ] ||
      bad="$bad $target class sizing equal;"
  done
  t_empty two_targets_and_two_classes_render_as_configured "$bad"
  t_equals renders_leave_the_templates_unchanged "$sums" \
    "$(cd "$PX_HOME/k8s" && cat templates/*.tmpl overlays/mesh/*.tmpl | cksum)"
}

## @description  Verifies target file loading, precedence, and validation in the configuration
##               loader (RQ-296, RQ-297).
## @return       Records test assertions. Exit 0.
t_group_k2_target() {
  local target dated calls dir bad="" key run
  printf '# K2: target files\n'
  t_k2_reset
  target="$(t_k2_target all PX_K8S_CONTEXT=tgt-ctx PX_CLUSTER_ID=tc PX_PROJECT_PREFIX=tgt \
    PX_K8S_STORAGE_CLASS=fast-ssd PX_K8S_REGISTRY=reg.example/phx PX_K8S_PULL_SECRET=regcred \
    PX_K8S_NODE_SELECTOR=pool=phx PX_K8S_TOLERATIONS=dedicated=phx:NoSchedule \
    PX_K8S_MESH=1 PX_K8S_DEDICATED_NODES=1)"
  printf 'storageclass-placeholder\nfast-ssd\n' >"$T_K8S_STATE/k8s-storageclasses.txt"
  printf 'regcred\n' >"$T_K8S_STATE/k8s-secrets.txt"
  printf 'n1|33554432Ki|8||pool=phx\n' >"$T_K8S_STATE/k8s-nodes.txt"
  t_k8s PX_K8S_CONTEXT= PX_CLUSTER_ID= "PX_K8S_TARGET=$target" "$T_BASH" \
    "$PX_HOME/k8s/manifests.gen.sh" --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest \
    -e "PX_K8S_IMAGE_DIGESTS=phoenix-harness:selftest=$T_K8S_IMAGE_ID"
  dir="$T_K8S_OUT/deploy-k8s/tc"
  t_shim_log_reset
  t_k8s PX_K8S_CONTEXT= PX_CLUSTER_ID= "PX_K8S_TARGET=$target" "$T_BASH" \
    "$PX_HOME/scripts/preflight-k8s.sh" --deployment "$dir" --phase fast
  calls="$(cat "$T_SHIM_LOG")"
  for key in "--context tgt-ctx --namespace tgt-tc" 'get storageclass fast-ssd' \
    'get secret regcred' 'get nodes -l pool=phx' 'get mutatingwebhookconfigurations'; do
    [[ $calls == *"$key"* ]] || bad="$bad call [$key];"
  done
  for key in 'namespace: tgt-tc' "image: \"reg.example/phx/phoenix-harness@$T_K8S_IMAGE_ID\"" \
    '- name: regcred' 'storageClassName: fast-ssd' 'pool: "phx"' 'key: "dedicated"' \
    'istio-injection: "enabled"'; do
    grep -rqF -- "$key" "$dir/manifests" || bad="$bad render [$key];"
  done
  dated="$(t_k2_target dedicated PX_K8S_DEDICATED_NODES=1)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "PX_K8S_TARGET=$dated" "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  [ "$(t_manifest "$run" /quiet_host/state)" = dedicated ] || bad="$bad quiet state;"
  t_equals target_settings_reach_the_render_and_calls "0" "$T_STATUS$bad"
  t_k8s "PX_K8S_TARGET=$PX_HOME/k8s/env/k3d.env" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" \
    --class validation --cluster intree
  t_equals target_inside_the_repository_is_refused "2 yes no" "$T_STATUS $(t_flag \
    'outside the repository' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/intree")"
  t_k8s "PX_K8S_TARGET=$T_K2/absent.env" "$T_BASH" "$T_BIN/px-cluster" --backend k8s config
  t_equals absent_target_is_refused "6 yes" "$T_STATUS $(t_flag 'target file' "$T_STDERR")"
  printf 'PX_K8S_TARGET=%s\n' "$target" >"$T_K2/local.env"
  t_k8s "PX_LOCAL_ENV=$T_K2/local.env" "$T_BASH" "$T_BIN/px-cluster" --backend k8s config
  t_equals target_named_in_local_env_is_refused "2 yes" "$T_STATUS $(t_flag \
    'after the target stage' "$T_STDERR")"
  t_k8s PX_K8S_CONTEXT= "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" --backend k8s \
    config
  t_equals target_values_carry_the_target_origin "0 tgt-ctx target" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$1 == "PX_K8S_CONTEXT" { print $2, $3 }')"
  bad=""
  for key in PX_K8S_TARGET PX_K8S_CONTEXT PX_CLUSTER_ID PX_PROJECT_PREFIX PX_K8S_STORAGE_CLASS \
    PX_K8S_REGISTRY PX_K8S_PULL_SECRET PX_K8S_NODE_SELECTOR PX_K8S_TOLERATIONS \
    PX_K8S_DEDICATED_NODES PX_K8S_MESH PX_K8S_MESH_CORE; do
    grep -q "^| \`$key\` |" "$PX_HOME/docs/backends.md" || bad="$bad $key"
  done
  t_empty backends_md_lists_every_target_key "$bad"
  t_equals k8s_env_holds_two_files "k3d.env k8s.env" "$(for key in "$PX_HOME"/k8s/env/*; do
    printf '%s ' "${key##*/}"; done | sed 's/ $//')"
}

## @description  Verifies scripts/preflight-k8s.sh checks and operator-prepared namespace handling
##               (RQ-284, RQ-285, RQ-286, RQ-289, RQ-290, RQ-298).
## @return       Records test assertions. Exit 0.
t_group_k2_preflight() {
  local total budget mib bad="" calls pin
  printf '# K2: the Kubernetes preflight\n'
  t_k2_reset
  t_mgen --class validation --cluster selftest
  t_shim_log_reset
  t_pfk
  t_equals k8s_preflight_passes_when_all_fit "0 ok ok ok ok ok" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" memory_fit) $(t_k2_row "$T_STDOUT" pod_fit) $(t_k2_row "$T_STDOUT" cpu_fit)\
 $(t_k2_row "$T_STDOUT" deployment_absent) $(t_k2_row "$T_STDOUT" storage_class)"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals remote_preflight_asks_no_engine "no no" "$(t_flag 'info --format' "$calls")\
 $(t_flag 'k3d ' "$calls")"
  t_empty k8s_preflight_applies_nothing "$(grep -E -- '--namespace [^ ]+ (apply|create|delete) ' \
    "$T_SHIM_LOG")"
  # Verify each individual Kubernetes preflight failure condition in isolation (RQ-289).
  printf 'v1.27.9\n' >"$T_K8S_STATE/server-version"
  t_pfk
  t_equals k8s_preflight_refuses_an_old_api "3 fail yes" "$T_STATUS $(t_k2_row "$T_STDOUT" \
    engine_version) $(t_flag 'floor 1.28' "$T_STDOUT")"
  rm -f "$T_K8S_STATE/server-version"
  printf 'n1|4Gi|8||\n' >"$T_K8S_STATE/k8s-nodes.txt"
  t_pfk
  t_equals k8s_preflight_refuses_short_allocatable_memory "3 fail yes yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" memory_fit) $(t_flag 'against 4096 MiB (4.00 GiB): the memory requests' \
    "$T_STDOUT") $(t_flag 'regionserver: 1 x 2560 MiB' "$T_STDERR")"
  printf 'n%s|2048Mi|8||\n' 1 2 3 4 5 >"$T_K8S_STATE/k8s-nodes.txt"
  t_pfk
  t_equals k8s_preflight_refuses_a_pod_larger_than_any_node "3 ok fail" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" memory_fit) $(t_k2_row "$T_STDOUT" pod_fit)"
  printf 'n1|33554432Ki|1||\n' >"$T_K8S_STATE/k8s-nodes.txt"
  t_pfk
  t_equals k8s_preflight_refuses_short_allocatable_cpu "3 fail" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" cpu_fit)"
  printf 'n1|33554432Ki|8||pool=phx\nn2|33554432Ki|8||\n' >"$T_K8S_STATE/k8s-nodes.txt"
  t_shim_log_reset
  t_pfk PX_K8S_NODE_SELECTOR=pool=phx
  t_equals k8s_preflight_counts_only_selected_nodes "0 yes yes" "$T_STATUS $(t_flag \
    '34359738368 bytes allocatable' "$T_STDOUT") $(t_flag 'get nodes -l pool=phx' \
    "$(cat "$T_SHIM_LOG")")"
  rm -f "$T_K8S_STATE/k8s-nodes.txt"
  t_k2_quota q1 requests.memory 10Gi 9Gi >"$T_K8S_STATE/k8s-quotas.txt"
  t_pfk
  t_equals k8s_preflight_refuses_an_exhausted_quota "3 fail yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" quota_fit:memory) $(t_flag 'quota q1 requests.memory' "$T_STDOUT")"
  t_pfk --phase memory
  t_equals k8s_memory_recheck_compares_with_the_hard_quota "0 ok" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" quota_fit:memory)"
  t_k2_quota q2 pods 4 0 >"$T_K8S_STATE/k8s-quotas.txt"
  t_pfk
  t_equals k8s_preflight_refuses_a_pod_quota "3 fail" "$T_STATUS $(t_k2_row "$T_STDOUT" \
    quota_fit:pods)"
  rm -f "$T_K8S_STATE/k8s-quotas.txt"
  printf 'other\n' >"$T_K8S_STATE/k8s-storageclasses.txt"
  t_pfk
  t_equals k8s_preflight_refuses_a_missing_storage_class "3 fail" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" storage_class)"
  rm -f "$T_K8S_STATE/k8s-storageclasses.txt"
  # Verify the minimum version floors for the kubectl client and k3d, confirming that failure
  # diagnostics report both the detected version and the required floor (RQ-298).
  printf 'v1.27.0\n' >"$T_K8S_STATE/client-version"
  t_pfk
  t_equals k8s_preflight_refuses_an_old_kubectl "3 fail yes yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" client_version) $(t_flag v1.27.0 "$T_STDOUT") $(t_flag 1.28 "$T_STDOUT")"
  rm -f "$T_K8S_STATE/client-version"
  pin="$(grep -cE '^PX_K3S_IMAGE=rancher/k3s:[^@]+@sha256:[0-9a-f]{64}$' \
    "$PX_HOME/docker/versions.env")"
  t_equals versions_env_pins_k3s_and_tool_floors "1 1.28 5.0" "$pin $(sed -n \
    's/^PX_KUBECTL_MIN_VERSION=//p' "$PX_HOME/docker/versions.env") $(sed -n \
    's/^PX_K3D_MIN_VERSION=//p' "$PX_HOME/docker/versions.env")"
  # Verify that preflight rejects the target when another harness deployment exists on the cluster
  # or within the target namespace, but admits an existing namespace prepared by the operator
  # (RQ-286).
  printf 'px-other|other\n' >"$T_K8S_STATE/k8s-namespaces.txt"
  t_shim_log_reset
  t_pfk
  t_equals k8s_preflight_refuses_another_harness_namespace "3 fail yes no" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" deployment_absent) $(t_flag px-other "$T_STDOUT") $(t_flag ' apply ' \
    "$(cat "$T_SHIM_LOG")")"
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  t_pfk
  t_equals k8s_preflight_refuses_a_deployment_in_its_namespace "3 fail yes" "$T_STATUS \
$(t_k2_row "$T_STDOUT" deployment_absent) $(t_flag 'holds a harness deployment' "$T_STDOUT")"
  t_k2_reset
  t_mgen --class validation --cluster selftest
  printf 'px-selftest|selftest\n' >"$T_K8S_STATE/k8s-namespaces.txt"
  printf 'regcred\n' >"$T_K8S_STATE/k8s-secrets.txt"
  t_pfk PX_K8S_PULL_SECRET=regcred
  t_equals k8s_preflight_admits_a_prepared_namespace "0 ok yes ok" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" deployment_absent) $(t_flag 'the operator prepared it' "$T_STDOUT") $(t_k2_row \
    "$T_STDOUT" pull_secret)"
  t_pfk PX_K8S_PULL_SECRET=other
  t_equals k8s_preflight_refuses_an_absent_pull_secret "3 fail yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" pull_secret) $(t_flag 'prepare the namespace with the secret' "$T_STDOUT")"
  # Verify that a deployment rendered with PX_K8S_MESH=1 requires the default-revision Istio
  # sidecar injector (which acts on the istio-injection label) so that a result bundle never claims
  # a service mesh that was absent during the run. The rendered namespace label—rather than
  # PX_K8S_MESH in the preflight environment—determines whether this check executes.
  t_shim_log_reset
  t_pfk PX_K8S_MESH=1
  t_equals k8s_flat_preflight_asks_no_webhook "0 no no" "$T_STATUS $(t_flag mesh_injector \
    "$T_STDOUT") $(t_flag mutatingwebhookconfigurations "$(cat "$T_SHIM_LOG")")"
  t_mgen --class validation --cluster selftest -e PX_K8S_MESH=1
  t_pfk
  t_equals k8s_preflight_refuses_a_mesh_without_an_injector "3 fail yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" mesh_injector) $(t_flag 'install the mesh, or drop PX_K8S_MESH=1' "$T_STDOUT")"
  printf '%s.sidecar-injector.istio.io\n' rev.namespace rev.object object \
    >"$T_K8S_STATE/k8s-webhooks.txt"
  t_pfk
  bad="$T_STATUS $(t_k2_row "$T_STDOUT" mesh_injector)"
  printf 'namespace.sidecar-injector.istio.io\n' >>"$T_K8S_STATE/k8s-webhooks.txt"
  t_pfk
  t_equals k8s_preflight_admits_only_the_default_injector "3 fail 0 ok yes" "$bad $T_STATUS\
 $(t_k2_row "$T_STDOUT" mesh_injector) $(t_flag 'injector namespace.sidecar-injector.istio.io' \
    "$T_STDOUT")"
  : >"$T_K8S_STATE/webhooks-forbidden"
  t_pfk
  t_equals k8s_preflight_refuses_a_mesh_it_cannot_list "3 fail yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" mesh_injector) $(t_flag 'grant the list, or drop PX_K8S_MESH=1' "$T_STDOUT")"
  rm -f "$T_K8S_STATE/webhooks-forbidden" "$T_K8S_STATE/k8s-webhooks.txt"
  t_mgen --class validation --cluster selftest
  t_k8s_sb sb_scope_create selftest
  t_shim_log_reset
  t_k8s_sb sb_scope_delete fake/px-selftest
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_operator_namespace_stays_at_teardown "0 yes no px-selftest|selftest regcred" \
    "$T_STATUS $(t_flag 'delete configmaps,services -l px.harness.cluster=selftest' "$calls")\
 $(t_flag 'delete namespace' "$calls") $(cat "$T_K8S_STATE/k8s-namespaces.txt") $(cat \
    "$T_K8S_STATE/k8s-secrets.txt")"
  # Because the cluster label alone does not indicate whether the harness or an operator created the
  # namespace, running teardown on a host that lacks the provisioning state record preserves an
  # operator-prepared namespace.
  t_k2_reset
  printf 'px-selftest|selftest\n' >"$T_K8S_STATE/k8s-namespaces.txt"
  printf 'regcred\n' >"$T_K8S_STATE/k8s-secrets.txt"
  t_shim_log_reset
  t_k8s PX_SCOPE_ID=fake/px-selftest "$T_BASH" "$T_BIN/px-cluster" --backend k8s \
    --cluster selftest down
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_down_without_a_record_keeps_a_prepared_namespace \
    "0 yes no px-selftest|selftest regcred" "$T_STATUS $(t_flag \
    'delete configmaps,services -l px.harness.cluster=selftest' "$calls") $(t_flag \
    'delete namespace' "$calls") $(cat "$T_K8S_STATE/k8s-namespaces.txt") $(cat \
    "$T_K8S_STATE/k8s-secrets.txt")"
  # Provisioning a mesh-enabled deployment into an operator-prepared namespace skips applying the
  # Namespace manifest so that existing operator labels on the namespace remain untouched.
  t_k2_reset
  t_mgen --class validation --cluster selftest -e PX_K8S_MESH=1
  printf 'px-selftest|selftest\n' >"$T_K8S_STATE/k8s-namespaces.txt"
  t_k8s_sb sb_scope_create selftest
  t_shim_log_reset
  t_k8s_sb sb_provision fake/px-selftest "$T_K8S_OUT/deploy-k8s/selftest" 30
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_prepared_namespace_gets_no_namespace_apply "0 no yes px-selftest|selftest" \
    "$T_STATUS $(t_flag '00-namespace.yaml' "$calls") $(t_flag '10-env.yaml' "$calls") $(cat \
    "$T_K8S_STATE/k8s-namespaces.txt")"
  t_k8s_sb sb_scope_delete fake/px-selftest
  t_k2_reset
  t_k8s_sb sb_scope_create selftest
  t_equals k8s_harness_namespace_is_recorded_as_its_own "0 harness" "$T_STATUS $(sed -n \
    's/^namespace_owner\t//p' "$T_K8S_OUT/state/k8s/scope-fake_px-selftest.tsv")"
  # Verify local k3d cluster memory preflight accounting, which subtracts both the host memory
  # headroom and the k3s control-plane allowance from the container engine's total memory (RQ-290).
  t_k2_reset
  t_mgen --class validation --cluster selftest
  t_shim_log_reset
  t_pfk PX_K8S_CONTEXT=k3d-px
  t_equals k3d_context_that_px_k3d_did_not_create_is_refused "3 fail yes no" "$T_STATUS \
$(t_k2_row "$T_STDOUT" k3d_cluster) $(t_flag 'px-k3d create' "$T_STDOUT") $(t_flag 'k3d ' \
    "$(cat "$T_SHIM_LOG")")"
  mkdir -p "$T_K8S_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv"
  mib="$(sed -n 's/^PX_K3S_ALLOWANCE_MIB=//p' "$PX_HOME/k8s/env/k3d.env")"
  total=$(($(awk -F '\t' '!/^#/ && NF { s += $3 * $5 } END { print s }' \
    "$T_K8S_OUT/deploy-k8s/selftest/requests.tsv") * 1048576))
  budget=$((total + 4294967296 + mib * 1048576))
  printf '%s\n' "$((budget - 1048576))" >"$T_K8S_STATE/mem-total"
  t_shim_log_reset
  t_pfk PX_K8S_CONTEXT=k3d-px
  bad="$T_STATUS $(t_k2_row "$T_STDOUT" k3d_memory_fit) $(t_flag "allowance $mib MiB" \
    "$T_STDOUT") $(t_flag 'info --format {{.MemTotal}}' "$(cat "$T_SHIM_LOG")")"
  printf '%s\n' "$((budget + 1048576))" >"$T_K8S_STATE/mem-total"
  t_pfk PX_K8S_CONTEXT=k3d-px
  t_equals k3d_preflight_subtracts_headroom_and_allowance "3 fail yes yes 0 ok ok ok" \
    "$bad $T_STATUS $(t_k2_row "$T_STDOUT" k3d_memory_fit) $(t_k2_row "$T_STDOUT" k3d_binary)\
 $(t_k2_row "$T_STDOUT" k3d_version)"
  t_k8s PX_K8S_CONTEXT=k3d-px PX_K8S_STORAGE_CLASS=local-path "$T_BASH" \
    "$PX_HOME/scripts/preflight-k8s.sh" -e PX_K3S_ALLOWANCE_MIB= \
    --deployment "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k3d_preflight_needs_the_allowance "2 yes" "$T_STATUS $(t_flag PX_K3S_ALLOWANCE_MIB \
    "$T_STDERR")"
  printf 'v4.4.8\n' >"$T_K8S_STATE/k3d-version"
  t_pfk PX_K8S_CONTEXT=k3d-px
  t_equals k3d_preflight_refuses_an_old_k3d "3 fail yes yes" "$T_STATUS $(t_k2_row "$T_STDOUT" \
    k3d_version) $(t_flag v4.4.8 "$T_STDOUT") $(t_flag 'floor 5.0' "$T_STDOUT")"
  rm -f "$T_K8S_STATE/k3d-version" "$T_K8S_STATE/mem-total"
  # Verify that attempting to bring up a deployment on a kubeconfig context that does not exist
  # fails cleanly without creating a cluster (RQ-284).
  t_k2_reset
  t_mgen --class validation --cluster selftest
  printf 'other\n' >"$T_K8S_STATE/k8s-contexts.txt"
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up \
    "$T_K8S_OUT/deploy-k8s/selftest"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_up_on_a_missing_context_creates_no_cluster "yes no no no" "$([ "$T_STATUS" != 0 ] &&
    echo yes || echo no) $(t_flag 'k3d ' "$calls") $(t_flag ' apply ' "$calls") $(t_exists \
    "$T_K8S_OUT/state/scope-k8s-selftest")"
  rm -f "$T_K8S_STATE/k8s-contexts.txt"
  # Verify that cluster bring-up executes preflight checks before applying any manifests: a
  # preflight rejection applies nothing, whereas a passing preflight proceeds to provision the
  # cluster.
  printf 'other\n' >"$T_K8S_STATE/k8s-storageclasses.txt"
  printf 'docker.io/library/%s\n' "$(sed -n 's/^ *image: "\(.*\)"$/\1/p' \
    "$T_K8S_OUT/deploy-k8s/selftest/manifests/28-regionserver.yaml")" >"$T_K8S_STATE/k8s-images"
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up \
    "$T_K8S_OUT/deploy-k8s/selftest"
  bad="$T_STATUS $(t_flag ' apply ' "$(cat "$T_SHIM_LOG")") $(t_flag storage_class "$T_STDERR")"
  rm -f "$T_K8S_STATE/k8s-storageclasses.txt"
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up \
    "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k8s_up_runs_preflight_before_any_apply "3 no yes 0 yes" "$bad $T_STATUS $(awk '
    / version -o json$/ && !v { v = NR } / apply -f / && !a { a = NR }
    END { print (v && a > v) ? "yes" : "no" }' "$T_SHIM_LOG")"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  # Verify that both up DIR and up -n DIR reject a deployment rendered with PX_K8S_MESH=1 on a
  # target cluster lacking a sidecar injector even when -e PX_K8S_MESH=1 is not passed at bring-up,
  # and that passing -e PX_K8S_MESH=1 when bringing up a flat deployment checks for no injector.
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest --class validation -e PX_K8S_MESH=1 render --out deploy-k8s/meshed
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up -n "$T_K8S_OUT/deploy-k8s/meshed"
  bad="$T_STATUS $(t_k2_row "$T_STDOUT" mesh_injector)"
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up "$T_K8S_OUT/deploy-k8s/meshed"
  bad="$bad $T_STATUS $(t_flag ' apply ' "$(cat "$T_SHIM_LOG")") $(t_flag 'drop PX_K8S_MESH=1' \
    "$T_STDERR")"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up -n -e PX_K8S_MESH=1 "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k8s_up_refuses_a_mesh_without_an_injector "3 fail 3 no yes 0 no" "$bad $T_STATUS\
 $(t_flag mesh_injector "$T_STDOUT")"
}

## @description  Verifies on-demand loopback port-forward tunnels for Kubernetes service endpoints
##               (RQ-287).
## @return       Records test assertions. Exit 0.
t_group_k2_tunnels() {
  local row pid first again record stale
  printf '# K2: loopback tunnels\n'
  t_k8s_provision -e PX_RS_COUNT=3
  t_empty k8s_no_tunnel_before_a_request "$(grep 'port-forward' "$T_SHIM_LOG")"
  t_shim_log_reset
  t_k8s_sb sb_endpoint fake/px-selftest regionserver 2 rpc
  first=$T_STDOUT
  t_equals k8s_endpoint_opens_a_loopback_tunnel "0 127.0.0.1 yes" "$T_STATUS $(printf '%s\n' \
    "$first" | cut -f1) $(t_flag 'port-forward --address 127.0.0.1 pod/regionserver-2 :16020' \
    "$(cat "$T_SHIM_LOG")")"
  record="$T_K8S_OUT/state/k8s/tunnel-fake_px-selftest_regionserver_2_rpc.tsv"
  pid="$(cut -f1 "$record" 2>/dev/null)"
  t_shim_log_reset
  t_k8s_sb sb_endpoint fake/px-selftest regionserver 2 rpc
  again=$T_STDOUT
  t_equals k8s_endpoint_reuses_a_live_tunnel "0 yes no" "$T_STATUS $([ "$again" = "$first" ] &&
    echo yes || echo no) $(t_flag port-forward "$(cat "$T_SHIM_LOG")")"
  # After a host reboot, the PID stored in a tunnel state record may have been recycled for an
  # unrelated process; the backend must recognize that the PID is not an active tunnel and open a
  # new port-forward tunnel.
  [ -z "$pid" ] || kill "$pid" 2>/dev/null || true
  sleep 60 &
  stale=$!
  awk -F '\t' -v p="$stale" 'BEGIN { OFS = FS } { $1 = p; print }' "$record" >"$record.new" &&
    mv -f "$record.new" "$record"
  t_shim_log_reset
  t_k8s_sb sb_endpoint fake/px-selftest regionserver 2 rpc
  t_equals k8s_endpoint_replaces_a_record_whose_pid_is_no_tunnel "0 yes yes run" "$T_STATUS\
 $(t_flag port-forward "$(cat "$T_SHIM_LOG")") $([ "$T_STDOUT" != "$first" ] && echo yes ||
    echo no) $(t_proc_state "$stale")"
  kill "$stale" 2>/dev/null || true
  wait "$stale" 2>/dev/null || true
  pid="$(cut -f1 "$record" 2>/dev/null)"
  : >"$T_K8S_STATE/k8s-forward.log"
  t_k8s_sb sb_endpoint fake/px-selftest regionserver 0 rpc
  t_equals k8s_tunnels_share_the_base_port "0 yes" "$T_STATUS $(t_flag \
    'pod/regionserver-0 :16020' "$(cat "$T_SHIM_LOG")")"
  t_k8s_sb sb_endpoint_release fake/px-selftest regionserver 2 rpc
  t_equals k8s_endpoint_release_stops_the_tunnel "0 gone no yes" "$T_STATUS $(t_proc_state \
    "${pid:-0}") $(t_exists "$record") $(t_flag 'ended pod/regionserver-2' \
    "$(cat "$T_K8S_STATE/k8s-forward.log" 2>/dev/null)")"
  t_k8s_sb sb_endpoint fake/px-selftest client 0 rpc
  t_equals k8s_endpoint_of_a_role_without_a_service_is_6 6 "$T_STATUS"
  # Verify that the CLI endpoint command returns immediately while the background tunnel process
  # continues running until explicitly released.
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest endpoint regionserver 1 \
    rpc
  row=$T_STDOUT
  record="$T_K8S_OUT/state/k8s/tunnel-fake_px-selftest_regionserver_1_rpc.tsv"
  pid="$(cut -f1 "$record" 2>/dev/null)"
  first="$T_STATUS $(printf '%s\n' "$row" | cut -f1) $(t_proc_state "${pid:-0}")"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest endpoint regionserver 1 \
    rpc --release
  t_equals px_cluster_endpoint_opens_and_releases_a_tunnel "0 127.0.0.1 run 0 gone" \
    "$first $T_STATUS $(t_proc_state "${pid:-0}")"
  pid="$(cut -f1 "$T_K8S_OUT/state/k8s/tunnel-fake_px-selftest_regionserver_0_rpc.tsv" \
    2>/dev/null)"
  t_k8s_sb sb_endpoint fake/px-selftest regionserver 2 rpc
  row="$(cut -f1 "$T_K8S_OUT/state/k8s/tunnel-fake_px-selftest_regionserver_2_rpc.tsv" \
    2>/dev/null)"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  t_equals k8s_teardown_stops_every_tunnel "0 gone gone" "$T_STATUS $(t_proc_state "${pid:-0}")\
 $(t_proc_state "${row:-0}")"
  t_empty k8s_every_port_forward_binds_loopback "$(grep ' port-forward ' "$T_K1/calls.log" |
    grep -v ' port-forward --address 127.0.0.1 pod/')"
}

## @description  Verifies unplanned restart and out-of-memory detection, post-run cluster checks,
##               and the kubernetes manifest section for Kubernetes runs (RQ-288, RQ-299, RQ-300).
## @return       Records test assertions. Exit 0.
t_group_k2_evidence() {
  local run copy dir record calls key bad=""
  printf '# K2: restart, termination and out-of-memory evidence\n'
  # Verify that an unplanned container restart or an out-of-memory container termination aborts the
  # run with exit status 3 (RQ-288).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/k8s-restart-wait.sh"
  run="$(t_bundle)"
  t_equals k8s_run_aborts_on_a_rising_restart_count "3 true regionserver 0" \
    "$T_STATUS $(t_manifest \
    "$run" /anomaly/aborted) $(t_manifest "$run" /anomaly/instances/0/role) $(t_manifest "$run" \
    /anomaly/instances/0/index)"
  # Because the kubelet restarts a terminated container immediately, the recorded anomaly must
  # report the previous container's termination state rather than the current running state.
  t_equals k8s_run_anomaly_names_the_previous_termination "killed 137" "$(t_manifest "$run" \
    /anomaly/instances/0/termination_reason) $(t_manifest "$run" /anomaly/instances/0/exit_code)"
  # When an instance returns to the running state without an increased restart count, the anomaly
  # retains the current state because any prior container termination may predate the run.
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/k8s-unknown-wait.sh"
  run="$(t_bundle)"
  t_equals k8s_run_anomaly_keeps_an_old_previous_termination_out "3 unplanned running 0" \
    "$T_STATUS $(awk -F '\t' '$1 == "diff" && $2 == "regionserver" { print $6 }' \
    "$run/run-record.tsv") $(t_manifest "$run" /anomaly/instances/0/termination_reason)\
 $(t_manifest "$run" /anomaly/instances/0/exit_code)"
  # Verify that a failed attempt to read the previous termination state records a skip row in the
  # run record.
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/k8s-restart-unreachable.sh"
  run="$(t_bundle)"
  t_equals k8s_run_records_a_failed_previous_termination_read "3 yes" "$T_STATUS $(t_flag \
    "$(t_rows 4 skip post 'previous termination reason of regionserver/0' 'exit 1')" \
    "$(cat "$run/run-record.tsv" 2>/dev/null)")"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/k8s-oom-wait.sh"
  run="$(t_bundle)"
  record="$(cat "$run/run-record.tsv" 2>/dev/null)"
  t_equals k8s_run_aborts_on_an_oom_killed_termination "3 client yes yes" "$T_STATUS $(t_manifest \
    "$run" /anomaly/instances/0/role) $(t_flag "$(t_rows 4 oom client 0 yes)" "$record")\
 $(t_flag "$(t_rows 4 term client 0 oom)" "$record")"
  # Verify that a Kubernetes run populates the kubernetes object in manifest.json and that bundle
  # validation enforces its required fields (RQ-299).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  t_equals k8s_run_manifest_carries_the_kubernetes_member \
    "0 v1.37.2+k3s1 fake-node regionserver-0 phoenix-harness:selftest $T_K8S_IMAGE_ID" \
    "$T_STATUS $(t_manifest "$run" /kubernetes/api_version) $(t_manifest "$run" \
    /kubernetes/placement/0/node) $(t_manifest "$run" /kubernetes/placement/1/pod) $(t_manifest \
    "$run" /kubernetes/registry_digests/0/image_ref) $(t_manifest "$run" \
    /kubernetes/registry_digests/0/digest)"
  t_validate "$run"
  t_equals k8s_run_bundle_validates_its_kubernetes_member "0 ok ok ok" "$T_STATUS $(t_check_status \
    "$T_STDOUT" kubernetes.api_version) $(t_check_status "$T_STDOUT" kubernetes.placement)\
 $(t_check_status "$T_STDOUT" kubernetes.registry_digests)"
  T_K2_BUNDLE=$run
  for key in api_version placement registry_digests; do
    copy="$(t_bundle_copy "$run" "k8s-no-$key")"
    t_json_edit "$copy/manifest.json" '"kubernetes"' "\"$key\"" "\"x_$key\""
    t_validate "$copy"
    [ "$T_STATUS" = 1 ] && [ "$(t_check_status "$T_STDOUT" "kubernetes.$key")" = fail ] ||
      bad="$bad $key gave $T_STATUS;"
  done
  t_empty k8s_bundle_without_a_kubernetes_field_fails_validation "$bad"
  t_a5_state_reset
  t_a5_run exit-code
  run="$(t_bundle)"
  t_validate "$run"
  t_equals compose_bundle_needs_no_kubernetes_member "0 no no" "$T_STATUS $(t_flag kubernetes \
    "$T_STDOUT") $(t_flag '"kubernetes"' "$(cat "$run/manifest.json")")"
  # Verify that post-run cluster health checks inspect live pod restart counters, termination
  # reasons, warning events, and previous container logs (RQ-300).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  dir="$(t_check_bundle k8s-clean "$(t_rows 5 restart pre regionserver 0 0)")"
  t_k1_a5 "PX_RUN_DIR=$dir" "PX_THRESHOLDS_FILE=$dir/thresholds.properties" \
    PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation "$T_BASH" \
    "$PX_HOME/suites/checks/cluster.sh"
  t_equals k8s_check_clean_fixture_passes "0 PASS PASS" "$T_STATUS $(t_severity_of "$dir" \
    cluster.restart) $(t_severity_of "$dir" cluster.oom)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k8s_pod regionserver-0 regionserver '' 0 regionmover StatefulSet restarted 1 >"$T_K1/pod"
  grep -v '^regionserver-0|' "$T_K1_A5/k8s-pods.txt" >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K1_A5/k8s-pods.txt"
  dir="$(t_check_bundle k8s-restart "$(t_rows 5 restart pre regionserver 0 0)")"
  t_shim_log_reset
  t_k1_a5 "PX_RUN_DIR=$dir" "PX_THRESHOLDS_FILE=$dir/thresholds.properties" \
    PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation "$T_BASH" \
    "$PX_HOME/suites/checks/cluster.sh"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_check_fails_a_live_restart_count "ERROR yes" "$(t_severity_of "$dir" \
    cluster.restart) $(t_flag 'restart count 0 to 1 after the post-run snapshot' \
    "$(cat "$dir/verdicts.tsv")")"
  t_equals k8s_check_reads_the_previous_logs "yes yes fake previous log regionserver-0" \
    "$(t_flag 'logs regionserver-0 -c px --previous' "$calls") $(t_flag \
    'logs/instance/regionserver-0.previous.log' "$(cat "$dir/verdicts.tsv")") $(cat \
    "$dir/logs/instance/regionserver-0.previous.log" 2>/dev/null)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k8s_pod regionserver-0 regionserver '' 0 regionmover StatefulSet oom 1 >"$T_K1/pod"
  grep -v '^regionserver-0|' "$T_K1_A5/k8s-pods.txt" >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K1_A5/k8s-pods.txt"
  dir="$(t_check_bundle k8s-oom)"
  t_k1_a5 "PX_RUN_DIR=$dir" "PX_THRESHOLDS_FILE=$dir/thresholds.properties" \
    PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation "$T_BASH" \
    "$PX_HOME/suites/checks/cluster.sh"
  t_equals k8s_check_fails_an_oom_termination "ERROR yes" "$(t_severity_of "$dir" cluster.oom)\
 $(t_flag 'the previous log of regionserver/0' "$(cat "$dir/verdicts.tsv")")"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  printf 'client-0|OOMKilling|Memory cgroup out of memory: Killed process 4242 (java)\n' \
    >"$T_K1_A5/k8s-events.txt"
  dir="$(t_check_bundle k8s-event)"
  t_shim_log_reset
  t_k1_a5 "PX_RUN_DIR=$dir" "PX_THRESHOLDS_FILE=$dir/thresholds.properties" \
    PX_SCOPE_ID=fake/px-selftest PX_RUN_CLASS=validation "$T_BASH" \
    "$PX_HOME/suites/checks/cluster.sh"
  t_equals k8s_check_fails_an_oom_event "ERROR yes yes yes" "$(t_severity_of "$dir" cluster.oom)\
 $(t_flag 'client/0' "$(cat "$dir/verdicts.tsv")") $(t_flag 'no previous log of client/0, exit 6' \
    "$(cat "$dir/verdicts.tsv")") $(t_flag \
    'get events --field-selector involvedObject.name=client-0' \
    "$(cat "$T_SHIM_LOG")")"
  rm -f "$T_K1_A5/k8s-events.txt"
  t_a5_state_reset
  dir="$(t_check_bundle compose-oom "$(t_rows 5 oom client 0 yes OOMKilled)")"
  t_shim_log_reset
  t_cluster_checks "$dir"
  t_equals compose_check_notes_no_previous_log "ERROR yes no" "$(t_severity_of "$dir" cluster.oom)\
 $(t_flag 'no previous log of client/0, exit 5' "$(cat "$dir/verdicts.tsv")") $(t_flag \
    'logs --previous' "$(cat "$T_SHIM_LOG")")"
  t_a5_state_reset
}

## @description  Verifies bin/px-k3d digest pin, both cluster shapes, image import, state records,
##               and one cluster per host (RQ-284, RQ-291, RQ-292, RQ-298, RQ-303, RQ-304, R-258).
## @return       Records test assertions. Exit 0.
t_group_k2_k3d() {
  local line calls bad="" word pin lb tools
  printf '# K2: the local cluster manager\n'
  t_k2_reset
  # The diagnostic remedy for a missing image is determined by whether the context has a px-k3d
  # record rather than by the context name.
  t_mgen --class validation --cluster selftest
  t_k8s PX_K8S_CONTEXT=k3d-other "$T_BASH" "$T_BIN/px-cluster" up \
    "$T_K8S_OUT/deploy-k8s/selftest" -n
  t_equals image_hint_follows_the_px_k3d_record "6 yes" "$T_STATUS $(t_flag \
    'promote a passing validation bundle with px-cluster push BUNDLE' "$(printf '%s\n' \
    "$T_STDOUT" | grep "^image$PX_TAB")")"
  t_k2_reset
  t_k3d check
  t_equals px_k3d_check_runs_before_create "0 ok ok ok" "$T_STATUS $(t_k2_row "$T_STDOUT" \
    k3d_binary) $(t_k2_row "$T_STDOUT" k3d_version) $(t_k2_row "$T_STDOUT" engine_memory)"
  t_equals k3d_check_ends_with_a_result_row "$(t_rows 4 result ok 0 host)" \
    "$(t_last_line "$T_STDOUT")"
  t_shim_log_reset
  t_k3d create --class validation --agents 3
  line="$(grep '^k3d cluster create' "$T_SHIM_LOG")"
  t_equals px_k3d_create_pins_the_k3s_digest "0 $T_K3S_PIN yes" "$T_STATUS $(printf '%s\n' "$line" |
    sed -n 's/.* --image \([^ ]*\) .*/\1/p') $(printf '%s\n' "$line" | grep -qE \
    -- '--image [^ ]*k3s(:[^@ ]+)?@sha256:[0-9a-f]{64} ' && echo yes || echo no)"
  bad=""
  for word in $line; do
    case "$word" in
    127.0.0.1:* | --*) ;;
    *:[0-9]*@*) bad="$bad $word" ;;
    esac
  done
  t_equals px_k3d_validation_has_n_agents_on_loopback "yes yes no yes yes" "$(t_flag \
    '--agents 3 ' "$line") $(t_flag '--k3s-arg --disable=traefik@server:*' "$line") $(t_flag \
    'servicelb' "$line") $(t_flag '-p 127.0.0.1:8081:80@loadbalancer' "$line") $(t_flag \
    '--api-port 127.0.0.1:6550' "$line")$bad"
  t_contains px_k3d_keeps_the_current_context '--kubeconfig-switch-context=false' "$line"
  # Verify that the k3d load balancer and tools node helper images are pinned by digest in
  # docker/versions.env alongside the k3s image, and that every k3d invocation receives both pins
  # so that k3d pulls each image by digest while retaining the version tag as documentation.
  lb="$(sed -n 's/^PX_K3D_LOADBALANCER_IMAGE=//p' "$PX_HOME/docker/versions.env")"
  tools="$(sed -n 's/^PX_K3D_TOOLS_IMAGE=//p' "$PX_HOME/docker/versions.env")"
  t_equals versions_env_pins_the_k3d_helpers_by_digest "yes yes" "$(printf '%s\n' "$lb" |
    grep -qE '^ghcr\.io/k3d-io/k3d-proxy:[0-9.]+@sha256:[0-9a-f]{64}$' && echo yes || echo no)\
 $(printf '%s\n' "$tools" | grep -qE '^ghcr\.io/k3d-io/k3d-tools:[0-9.]+@sha256:[0-9a-f]{64}$' &&
    echo yes || echo no)"
  t_equals k3d_create_runs_with_the_pinned_helper_images "cluster create|$lb|$tools yes" \
    "$(grep '^cluster create|' "$T_K8S_STATE/k3d-env.txt" 2>/dev/null | tail -n 1) $([ -n \
    "$lb" ] && [ -n "$tools" ] && echo yes || echo no)"
  t_equals px_k3d_records_its_cluster "scope created_at k3d-px" "$(cut -f1 \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv" 2>/dev/null | tr '\n' ' ')$(sed -n \
    's/^scope\t//p' "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv" 2>/dev/null)"
  t_k3d create --class validation
  t_equals px_k3d_create_refuses_an_existing_cluster "3 yes" "$T_STATUS $(t_flag \
    'exists already' "$T_STDERR")"
  t_k3d -e PX_K8S_CONTEXT=k3d-two create --class validation
  t_equals px_k3d_create_refuses_another_record "3 yes no" "$T_STATUS $(t_flag \
    'another scope is recorded on this host: k3d-px' "$T_STDERR") $(t_exists \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-two.tsv")"
  # Verify that local harness container images populate the k3d node cache directly via image import
  # without creating a registry or pushing images (RQ-292).
  t_shim_log_reset
  t_k3d import img-a:1 img-b:2
  t_equals px_k3d_import_imports_each_harness_image "0 2 yes yes" "$T_STATUS $(grep -c \
    '^k3d image import -c px ' "$T_SHIM_LOG") $(t_flag 'k3d image import -c px img-a:1' \
    "$(cat "$T_SHIM_LOG")") $(t_flag 'k3d image import -c px img-b:2' "$(cat "$T_SHIM_LOG")")"
  t_equals k3d_import_runs_with_the_pinned_tools_image "image import|$lb|$tools yes" \
    "$(grep '^image import|' "$T_K8S_STATE/k3d-env.txt" 2>/dev/null | tail -n 1) $([ -n \
    "$tools" ] && echo yes || echo no)"
  t_k3d import reg.example/img:1
  t_equals px_k3d_import_refuses_a_remote_reference 2 "$T_STATUS"
  t_k3d -e PX_PHERF=1 import -n
  t_equals px_k3d_import_defaults_to_the_needed_tags "0 2 yes" "$T_STATUS $(printf '%s\n' \
    "$T_STDOUT" | grep -c "^plan${PX_TAB}import${PX_TAB}k3d image import -c px ") $(t_flag \
    ' px-pherf:' "$T_STDOUT")"
  t_mgen --class validation --cluster selftest
  t_k3d import
  t_k8s_sb sb_image_present "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals k3d_image_check_passes_after_import 0 "$T_STATUS"
  t_empty px_k3d_creates_no_registry_and_pushes_nothing "$(grep -E '(registry|push)' \
    "$T_SHIM_LOG")"
  t_k3d check
  t_equals px_k3d_check_reports_k3d_and_the_engine "0 ok ok ok" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" k3d_binary) $(t_k2_row "$T_STDOUT" k3d_version) $(t_k2_row "$T_STDOUT" \
    engine_memory)"
  # Verify that the preflight check evaluates the helper image pins, reporting ok when their tag
  # matches the installed k3d version or emitting a non-fatal warning with the remediation steps
  # when the tag differs.
  printf 'v5.9.0\n' >"$T_K8S_STATE/k3d-version"
  t_k3d check
  t_equals k3d_check_reports_the_helper_pins "0 ok yes" "$T_STATUS $(t_k2_row "$T_STDOUT" \
    k3d_helpers) $(t_flag 'the tag 5.9.0' "$(printf '%s\n' "$T_STDOUT" | grep "^k3d_helpers")")"
  printf 'v5.10.0\n' >"$T_K8S_STATE/k3d-version"
  t_k3d check
  t_equals k3d_check_warns_on_a_helper_tag_other_than_k3d "0 warn yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" k3d_helpers) $(t_flag 'PX_K3D_LOADBALANCER_IMAGE and PX_K3D_TOOLS_IMAGE' \
    "$(printf '%s\n' "$T_STDOUT" | grep "^k3d_helpers")")"
  rm -f "$T_K8S_STATE/k3d-version"
  # Verify that the diagnostic check reports a helper image pin lacking a digest as a failed row
  # while continuing to evaluate and report the container engine memory.
  t_k3d -e "PX_K3D_TOOLS_IMAGE=${tools%@*}" check
  t_equals k3d_check_fails_a_helper_pin_without_a_digest "3 fail yes ok" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" k3d_helpers) $(t_flag "PX_K3D_TOOLS_IMAGE='${tools%@*}' names no digest" \
    "$(printf '%s\n' "$T_STDOUT" | grep "^k3d_helpers")") $(t_k2_row "$T_STDOUT" engine_memory)"
  # Verify that cluster deletion refuses to proceed while an active harness deployment is recorded
  # on the context, and that it deletes only a cluster recorded by px-k3d.
  mkdir -p "$T_K8S_OUT/state/k8s"
  printf 'scope\tk3d-px/px-selftest\n' >"$T_K8S_OUT/state/k8s/scope-k3d-px_px-selftest.tsv"
  t_k3d delete
  t_equals px_k3d_delete_waits_for_the_deployment "3 yes yes" "$T_STATUS $(t_flag 'px-selftest' \
    "$T_STDERR") $(t_flag 'run px-cluster down first' "$T_STDERR")"
  rm -f "$T_K8S_OUT/state/k8s/scope-k3d-px_px-selftest.tsv"
  t_shim_log_reset
  t_k3d -e PX_K8S_CONTEXT=k3d-other delete
  t_equals px_k3d_delete_removes_only_its_own_cluster "6 no" "$T_STATUS $(t_flag 'k3d ' \
    "$(cat "$T_SHIM_LOG")")"
  t_k3d -e PX_K8S_CONTEXT=remote-ctx check
  t_equals px_k3d_check_of_a_remote_context_is_6 6 "$T_STATUS"
  t_shim_log_reset
  t_k3d delete
  t_equals px_k3d_delete_removes_its_cluster "0 yes no" "$T_STATUS $(t_flag \
    'k3d cluster delete px' "$(cat "$T_SHIM_LOG")") $(t_exists \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv")"
  # Verify that the measurement class topology creates a single flat server node with no agents,
  # load balancer port mappings, Traefik, or ServiceLB (RQ-303).
  t_shim_log_reset
  t_k3d create --class measurement
  line="$(grep '^k3d cluster create' "$T_SHIM_LOG")"
  t_equals px_k3d_measurement_is_one_flat_node "0 yes yes yes no" "$T_STATUS $(t_flag \
    '--servers 1 --agents 0 ' "$line") $(t_flag '--k3s-arg --disable=traefik@server:*' \
    "$line") $(t_flag '--k3s-arg --disable=servicelb@server:*' "$line") $(t_flag ' -p ' "$line")"
  # Because cluster deletion does not start any helper containers, a helper image pin lacking a
  # digest must never block teardown of a cluster owned by px-k3d.
  t_shim_log_reset
  t_k3d -e "PX_K3D_LOADBALANCER_IMAGE=${lb%@*}" delete
  t_equals k3d_delete_ignores_a_helper_pin_without_a_digest "0 yes no" "$T_STATUS $(t_flag \
    'k3d cluster delete px' "$(cat "$T_SHIM_LOG")") $(t_exists \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv")"
  t_k3d create --class validation --agents 10
  t_equals px_k3d_agents_out_of_range_refused 2 "$T_STATUS"
  # Verify that cluster creation rejects a k3s image reference that lacks an immutable SHA-256
  # digest pin before invoking k3d (RQ-291).
  t_shim_log_reset
  t_k8s PX_K8S_CONTEXT=k3d-px PX_K3S_IMAGE=rancher/k3s:v1.37.2-k3s1 "$T_BASH" "$T_BIN/px-k3d" \
    create
  t_equals px_k3d_create_refuses_a_pin_without_digest "2 yes no" "$T_STATUS $(t_flag @sha256 \
    "$T_STDERR") $(t_flag 'cluster create' "$(cat "$T_SHIM_LOG")")"
  t_shim_log_reset
  t_k3d -e "PX_K3D_TOOLS_IMAGE=${tools%@*}" create
  line="$T_STATUS $(t_flag 'PX_K3D_TOOLS_IMAGE' "$T_STDERR") $(t_flag 'k3d ' "$(cat \
    "$T_SHIM_LOG")")"
  t_k3d -e "PX_K3D_LOADBALANCER_IMAGE=${lb%@*}" import
  t_equals k3d_helper_pin_without_a_digest_is_refused "2 yes no 2 yes no" "$line $T_STATUS\
 $(t_flag 'PX_K3D_LOADBALANCER_IMAGE' "$T_STDERR") $(t_flag 'k3d ' "$(cat "$T_SHIM_LOG")")"
  # Verify that the default image pin shipped in docker/versions.env satisfies the digest
  # validation rule and is passed through to k3d unaltered (RQ-291, RQ-298).
  t_shim_log_reset
  t_k8s PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-k3d" create
  line="$(grep '^k3d cluster create' "$T_SHIM_LOG")"
  pin="$(sed -n 's/^PX_K3S_IMAGE=//p' "$PX_HOME/docker/versions.env")"
  t_equals px_k3d_create_admits_the_shipped_pin "0 $pin" "$T_STATUS $(printf '%s\n' "$line" |
    sed -n 's/.* --image \([^ ]*\) .*/\1/p')"
  t_k3d delete
  printf 'v4.4.8\n' >"$T_K8S_STATE/k3d-version"
  t_k3d create
  t_equals px_k3d_create_refuses_an_old_k3d "3 yes yes no" "$T_STATUS $(t_flag v4.4.8 \
    "$T_STDERR") $(t_flag 5.0 "$T_STDERR") $(t_exists "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv")"
  t_k3d check
  t_equals k3d_check_below_the_floor_ends_with_a_fail_row "3 $(t_rows 4 result fail 3 host)" \
    "$T_STATUS $(t_last_line "$T_STDOUT")"
  rm -f "$T_K8S_STATE/k3d-version"
  # Verify mutual exclusion of the one-cluster-per-host policy in both directions between Compose
  # deployments and local k3d clusters.
  mkdir -p "$T_K8S_OUT/state/compose"
  printf 'scope\tpx-busy\n' >"$T_K8S_OUT/state/compose/scope-px-busy.tsv"
  t_k3d create
  t_equals px_k3d_create_refuses_beside_a_compose_cluster "3 yes" "$T_STATUS $(t_flag px-busy \
    "$T_STDERR")"
  rm -f "$T_K8S_OUT/state/compose/scope-px-busy.tsv"
  printf 'px|--servers 1\n' >"$T_K8S_STATE/k3d-clusters.txt"
  t_k3d create
  t_equals px_k3d_create_refuses_a_cluster_it_did_not_record "3 yes no" "$T_STATUS $(t_flag \
    'exists already' "$T_STDERR") $(t_exists "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv")"
  rm -f "$T_K8S_STATE/k3d-clusters.txt"
  # Verify that if cluster creation fails after k3d has already registered the cluster, px-k3d
  # cleans up the partially created cluster so that a subsequent create invocation succeeds.
  : >"$T_K8S_STATE/k3d-create-partial"
  t_shim_log_reset
  t_k3d create
  line="$T_STATUS $(t_flag 'k3d cluster delete px' "$(cat "$T_SHIM_LOG")") $(t_exists \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv") $(grep -c . "$T_K8S_STATE/k3d-clusters.txt")"
  rm -f "$T_K8S_STATE/k3d-create-partial"
  t_k3d create
  t_equals px_k3d_failed_create_removes_the_partial_cluster "1 yes no 0 0" "$line $T_STATUS"
  t_k3d delete
  # Verify that when cluster creation fails and k3d subsequently fails to list its clusters, the
  # scope record is retained so that px-k3d delete can remove the partially created cluster.
  : >"$T_K8S_STATE/k3d-create-partial"
  : >"$T_K8S_STATE/k3d-create-stops-list"
  t_k3d create
  line="$T_STATUS $(t_flag 'did not list' "$T_STDERR") $(t_exists \
    "$T_K8S_OUT/state/px-k3d/scope-k3d-px.tsv")"
  rm -f "$T_K8S_STATE/k3d-create-partial" "$T_K8S_STATE/k3d-create-stops-list" \
    "$T_K8S_STATE/k3d-list-fails"
  t_k3d delete
  t_equals px_k3d_failed_create_keeps_the_record_when_k3d_cannot_list "1 yes yes 0 0" \
    "$line $T_STATUS $(grep -c . "$T_K8S_STATE/k3d-clusters.txt")"
  t_k3d create
  t_shim_log_reset
  t_with_shim "PX_OUT=$T_K8S_OUT" PX_CLUSTER_ID=selftest "$T_BASH" "$T_BIN/px-cluster" up \
    "$T_DEPLOY"
  t_equals compose_up_refuses_beside_a_k3d_cluster "3 yes" "$T_STATUS $(t_flag k3d-px \
    "$T_STDERR")$(cat "$T_SHIM_LOG")"
  t_k3d delete
  t_k3d create --class staging
  t_equals px_k3d_refuses_an_unknown_class 2 "$T_STATUS"
}

## @description  Verifies px-cluster push image promotion from a passing validation run bundle to a
##               target registry (RQ-285, RQ-295, RQ-306).
## @return       Records test assertions. Exit 0.
t_group_k2_promotion() {
  local target bundle copy calls dir state=$T_K8S_STATE text count line word
  printf '# K2: image promotion\n'
  t_k2_reset
  target="$(t_k2_target push PX_K8S_STORAGE_CLASS=fast PX_K8S_REGISTRY=reg.example:5000/phx \
    PX_K8S_PULL_SECRET=regcred)"
  bundle="$(t_bundle_copy "$T_K2_BUNDLE" k2-pass "$T_K8S_OUT")"
  sed 's/"result": "[A-Z]*"/"result": "PASS"/' "$bundle/manifest.json" >"$bundle/m.json" &&
    mv "$bundle/m.json" "$bundle/manifest.json"
  printf '%s|phoenix-harness:selftest\n' "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  cp "$T_K1/calls.log" "$T_K1/calls.log.mark"
  t_shim_log_reset
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  calls="$(cat "$T_SHIM_LOG")"
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  t_equals push_prints_only_the_deployment_directory "1 yes" "$(printf '%s\n' "$T_STDOUT" |
    grep -c .) $([ -d "$dir" ] && echo yes || echo no)"
  t_equals push_pushes_exactly_the_bundle_digests \
    "0 push reg.example:5000/phx/phoenix-harness:selftest $T_K8S_IMAGE_ID" \
    "$T_STATUS $(cat "$state/registry.log" 2>/dev/null)"
  t_equals push_runs_no_login "no no" "$(t_flag login "$calls") $(t_flag login \
    "$(cat "$T_K1/calls.log")")"
  t_equals push_creates_no_secret "no yes" "$(t_flag 'create' "$calls")\
 $(cmp -s "$T_K1/calls.log" "$T_K1/calls.log.mark" && echo yes || echo no)"
  t_equals push_builds_nothing "no no" "$(t_flag ' build' "$calls") $(t_flag mvn "$calls")"
  t_equals push_render_names_the_target_pull_secret "8 8 1" "$(t_k2_workloads "$dir" |
    grep -c .) $(t_k2_workloads "$dir" | grep -cE 'imagePullSecrets:\| *- name: regcred\|') $(cat \
    "$dir"/manifests/*.yaml | sed -n 's/^ *image: "\(.*\)"$/\1/p' | LC_ALL=C sort -u |
    grep -c "^reg.example:5000/phx/phoenix-harness@$T_K8S_IMAGE_ID$")"
  t_equals push_renders_into_a_directory_of_its_run "$T_K8S_OUT/deploy-k8s/push-$(t_manifest \
    "$bundle" /run/id)" "$dir"
  text="$(cat "$dir/render.env" 2>/dev/null)"
  t_equals push_records_the_settings_of_its_render "yes yes yes" "$(t_flag \
    "PX_K8S_IMAGE_DIGESTS='phoenix-harness:selftest=$T_K8S_IMAGE_ID'" "$text") $(t_flag \
    "PX_BACKEND='k8s'" "$text") $(t_flag "PX_RUN_CLASS='validation'" "$text")"
  # Because push renders solely from the target configuration, a local cluster record on either
  # backend affects neither the push operation nor its render, and no cluster deployment directory
  # is modified.
  count="$(t_env_value "$dir/topology.env" PX_RS_COUNT)"
  mkdir -p "$T_K8S_OUT/state" "$T_K8S_OUT/deploy-k8s/selftest"
  : >"$T_K8S_OUT/deploy-k8s/selftest/u3-mark"
  line=""
  for word in compose k8s; do
    printf "PX_BACKEND='%s'\nPX_RS_COUNT='%s'\n" "$word" "$((count + 2))" \
      >"$T_K8S_OUT/state/cluster-selftest.env"
    t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
      --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
    line="$line $T_STATUS $(t_env_value "$(printf '%s\n' "$T_STDOUT" | sed -n 1p)/topology.env" \
      PX_RS_COUNT) $(t_exists "$T_K8S_OUT/deploy-k8s/selftest/u3-mark")"
  done
  rm -rf "$T_K8S_OUT/state/cluster-selftest.env" "$T_K8S_OUT/deploy-k8s/selftest"
  t_equals push_reads_no_cluster_record " 0 $count yes 0 $count yes" "$line"
  # Verify that image promotion rejects failed, missing, measurement-class, or invalid run bundles,
  # missing local images, and mismatched registry digests without pushing (RQ-295).
  rm -f "$state/registry.log"
  copy="$(t_bundle_copy "$bundle" k2-fail "$T_K8S_OUT")"
  sed 's/"result": "PASS"/"result": "FAIL"/' "$copy/manifest.json" >"$copy/m.json" &&
    mv "$copy/m.json" "$copy/manifest.json"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$copy" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_failed_bundle "1 no" "$T_STATUS $(t_exists "$state/registry.log")"
  # Promotion takes every result that maps to exit 0, that is no check failed: ADVISORY timing
  # verdicts and SKIP checks without a source, functional ones too. It refuses every other result.
  for word in ADVISORY SKIP INCONCLUSIVE ERROR; do
    rm -f "$state/registry.log"
    copy="$(t_bundle_copy "$bundle" "k2-$word")"
    sed "s/\"result\": \"PASS\"/\"result\": \"$word\"/" "$copy/manifest.json" >"$copy/m.json" &&
      mv "$copy/m.json" "$copy/manifest.json"
    t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$copy" \
      --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
    line="$T_STATUS $(cat "$state/registry.log" 2>/dev/null | grep -c .) $(t_flag \
      'promotes its images' "$T_STDERR")"
    case "$word" in
    ADVISORY) t_equals push_promotes_an_advisory_validation_bundle "0 1 no" "$line" ;;
    SKIP) t_equals push_promotes_a_skip_validation_bundle "0 1 no" "$line" ;;
    INCONCLUSIVE) t_equals push_refuses_an_inconclusive_bundle "1 0 yes" "$line" ;;
    ERROR) t_equals push_refuses_an_error_bundle "1 0 yes" "$line" ;;
    esac
  done
  rm -f "$state/registry.log"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push \
    "$T_K8S_OUT/no-bundle" --class validation
  t_equals push_refuses_a_missing_bundle "6 no" "$T_STATUS $(t_exists \
    "$state/registry.log")"
  copy="$(t_bundle_copy "$bundle" k2-measure "$T_K8S_OUT")"
  t_json_edit "$copy/manifest.json" '"run"' '"class": "validation"' '"class": "measurement"'
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$copy" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_measurement_bundle "1 no" "$T_STATUS $(t_exists \
    "$state/registry.log")"
  copy="$(t_bundle_copy "$bundle" k2-invalid "$T_K8S_OUT")"
  t_json_edit "$copy/manifest.json" '"kubernetes"' '"api_version"' '"x_api_version"'
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$copy" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_an_invalid_bundle "1 no" "$T_STATUS $(t_exists \
    "$state/registry.log")"
  : >"$state/local-images.txt"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_an_absent_local_image "6 no" "$T_STATUS $(t_exists \
    "$state/registry.log")"
  printf '%s|phoenix-harness:selftest\n' "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  : >"$state/registry-digest-differs"
  rm -rf "$dir"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_registry_digest_that_differs "1 yes no" "$T_STATUS $(t_flag \
    'no render' "$T_STDERR") $(t_exists "$dir")"
  rm -f "$state/registry-digest-differs"
  # Verify that push promotes a run bundle produced on a local px-k3d cluster. Because the kubelet
  # records the configuration digest of an imported image whereas the containerd image store
  # identifies the local image by its manifest digest, the engine links the two through its
  # config.digest descriptor annotation. The push command resolves the configuration digest to the
  # single matching local image, pushes that image to the target registry, and renders the
  # deployment manifests using the registry digest.
  word="sha256:$(printf 'd%.0s' $(seq 1 64))"
  printf '%s|phoenix-harness:selftest|%s\n' "$word" "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  rm -rf "$dir" "$state/registry.log"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_maps_a_config_digest_to_its_local_image \
    "0 push reg.example:5000/phx/phoenix-harness:selftest $word 1 yes yes" "$T_STATUS $(cat \
    "$state/registry.log" 2>/dev/null) $(cat "$dir"/manifests/*.yaml 2>/dev/null |
    sed -n 's/^ *image: "\(.*\)"$/\1/p' | LC_ALL=C sort -u |
    grep -c "^reg.example:5000/phx/phoenix-harness@$word$") $(t_flag \
    "PX_K8S_IMAGE_DIGESTS='phoenix-harness:selftest=$word'" "$(cat "$dir/render.env" \
    2>/dev/null)") $(t_flag "pushed reg.example:5000/phx/phoenix-harness:selftest as $word (the\
 config digest $T_K8S_IMAGE_ID of the bundle)" "$T_STDERR")"
  # Verify that a dry-run push does not query the container engine, emitting a plan that references
  # the bundle digest directly and explicitly notes that behavior.
  rm -rf "$dir" "$state/registry.log"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" --dry-run push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_dry_run_marks_the_bundle_digests "0 yes yes no" "$T_STATUS $(t_flag \
    "plan${PX_TAB}push${PX_TAB}reg.example:5000/phx/phoenix-harness:selftest $T_K8S_IMAGE_ID" \
    "$T_STDOUT") $(t_flag 'the plan names the digests of the bundle' "$T_STDERR") $(t_exists \
    "$state/registry.log")"
  printf '%s|phoenix-harness:%s|%s\n' "$word" selftest "$T_K8S_IMAGE_ID" \
    "sha256:$(printf 'b%.0s' $(seq 1 64))" other "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  rm -rf "$dir" "$state/registry.log"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_config_digest_of_two_local_images "1 yes no" "$T_STATUS $(t_flag \
    "images sha256:$(printf 'b%.0s' $(seq 1 64)) $word have the config digest $T_K8S_IMAGE_ID" \
    "$T_STDERR") $(t_exists "$state/registry.log")"
  printf '%s|phoenix-harness:selftest|sha256:%s\n' "$word" "$(printf 'a%.0s' $(seq 1 64))" \
    >"$state/local-images.txt"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_config_digest_that_no_local_image_has "6 no" "$T_STATUS $(t_exists \
    "$state/registry.log")"
  : >"$state/descriptor-fails"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_fails_when_the_engine_inspects_no_descriptor "1 yes no" "$T_STATUS $(t_flag \
    'the engine did not inspect its images' "$T_STDERR") $(t_exists "$state/registry.log")"
  rm -f "$state/descriptor-fails"
  printf '%s|phoenix-harness:selftest|%s\n' "$word" "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  : >"$state/registry-digest-differs"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_checks_the_registry_digest_of_a_mapped_image "1 yes yes no" "$T_STATUS $(t_flag \
    'no render' "$T_STDERR") $(t_flag "the local image is $word" "$T_STDERR") $(t_exists "$dir")"
  rm -f "$state/registry-digest-differs"
  printf '%s|phoenix-harness:selftest\n' "$T_K8S_IMAGE_ID" >"$state/local-images.txt"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" push "$bundle" --class validation
  t_equals push_needs_a_target_registry 2 "$T_STATUS"
  target="$(t_k2_target hub PX_K8S_STORAGE_CLASS=fast PX_K8S_REGISTRY=apurtell)"
  rm -f "$state/registry.log"
  t_shim_log_reset
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_IMAGE_TAG=phoenix-harness:selftest
  t_equals push_refuses_a_registry_without_a_host "2 yes no no" "$T_STATUS $(t_flag \
    PX_K8S_REGISTRY "$T_STDERR") $(t_exists "$state/registry.log") $(t_flag push \
    "$(cat "$T_SHIM_LOG")")"
}

## @description  Verifies host sleep inhibition, dedicated-node measurement checks, and measurement
##               teardown sequencing for Kubernetes runs (RQ-302, RQ-308, RQ-310).
## @return       Records test assertions. Exit 0.
t_group_k2_runs() {
  local run copy calls record line
  printf '# K2: Kubernetes runs\n'
  # Verify that cluster bring-up acquires the host sleep inhibitor and starts the background health
  # monitor daemon, and that cluster teardown stops both (RQ-302).
  t_k2_reset
  t_mgen --class validation --cluster selftest
  printf 'docker.io/library/%s\n' "$(sed -n 's/^ *image: "\(.*\)"$/\1/p' \
    "$T_K8S_OUT/deploy-k8s/selftest/manifests/28-regionserver.yaml")" >"$T_K8S_STATE/k8s-images"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --config "$PX_HOME/k8s/env/k3d.env" \
    --cluster selftest up \
    "$T_K8S_OUT/deploy-k8s/selftest"
  line="$T_STATUS $(t_k2_count "$T_K8S_OUT/state" 'inhibit-cluster-*')\
 $(($(t_k2_count "$T_K8S_OUT/state" 'monitor-*') - $(t_k2_count "$T_K8S_OUT/state" \
    'monitor-*.heartbeat')))"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  t_equals k8s_up_starts_the_inhibitor_and_monitor "0 1 1 0 0 0" "$line $T_STATUS $(t_k2_count \
    "$T_K8S_OUT/state" 'inhibit-cluster-*') $(t_k2_count "$T_K8S_OUT/state" 'monitor-*')"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/host-probe.sh"
  t_contains k8s_run_holds_the_inhibitor "run-" "$(cat "$(t_bundle)/logs/run.log" 2>/dev/null)"
  # Verify that a measurement run on a target without dedicated nodes is refused when a quiet host
  # is required, or marked contaminated and downgraded to advisory when quiet-host enforcement is
  # disabled (RQ-308, RQ-302).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  record="$(cat "$(t_bundle)/run-record.tsv" 2>/dev/null)"
  t_equals k8s_measurement_without_dedicated_nodes_aborts_when_required "2 refused no" \
    "$T_STATUS $(t_manifest "$(t_bundle)" /quiet_host/state) $(t_flag "verb${PX_TAB}prepare" \
    "$record")"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  record="$(cat "$run/run-record.tsv" 2>/dev/null)"
  t_equals k8s_measurement_without_dedicated_nodes_is_contaminated "0 undeclared dedicated-node" \
    "$T_STATUS $(t_manifest "$run" /quiet_host/state) $(t_manifest "$run" /quiet_host/tags/0)"
  t_empty k8s_run_takes_no_host_load_sample "$(printf '%s\n' "$record" |
    grep "^quiet${PX_TAB}sample")"
  copy="$(t_bundle_copy "$run" k2-advisory)"
  printf 'latency\tFAIL\tduration\tdriver:x\tslow\n' >>"$copy/verdicts.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  line="$T_STATUS $(t_result)"
  printf 'rows\tFAIL\tcount\tdriver:x\tshort\n' >>"$copy/verdicts.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals k8s_measurement_without_dedicated_nodes_is_advisory "0 ADVISORY 1 FAIL" \
    "$line $T_STATUS $(t_result)"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_K8S_DEDICATED_NODES=1 -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  copy="$(t_bundle_copy "$run" k2-dedicated)"
  printf 'latency\tFAIL\tduration\tdriver:x\tslow\n' >>"$copy/verdicts.tsv"
  t_run "$T_BASH" "$T_BIN/px-report" "$copy"
  t_equals k8s_measurement_on_dedicated_nodes_is_authoritative "dedicated 0 1 FAIL" \
    "$(t_manifest "$run" /quiet_host/state) $(t_manifest "$run" /quiet_host/tags) $T_STATUS\
 $(t_result)"
  # Verify that a run against a local px-k3d cluster executes the same local quiet-host check as a
  # Compose run, enforcing identical wait, refusal, and evidence semantics because the cluster
  # nodes run as containers on the local container engine. Because those shared nodes always
  # contaminate the run, any failed timing verdicts remain ADVISORY, whereas a context without a
  # px-k3d state record retains the remote dedicated-node check regardless of its context name.
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  mkdir -p "$PX_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  : >"$T_HOST_DIR/uptime.log"
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_QUIET_HOST_WAIT_S=0 -e PX_SCOPE_ID=k3d-px/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  record="$(cat "$run/run-record.tsv" 2>/dev/null)"
  t_equals k3d_measurement_runs_the_host_quiet_check "0 quiet yes 1 dedicated-node" \
    "$T_STATUS $(t_manifest "$run" /quiet_host/state) $(t_flag "quiet${PX_TAB}sample" \
    "$record") $(grep -c . "$T_HOST_DIR/uptime.log") $(t_manifest "$run" /quiet_host/tags/0)"
  line='the nodes of the local px-k3d cluster share this host'
  t_contains k3d_shared_nodes_contaminate_the_run "$(t_rows 3 contamination dedicated-node \
    "$line, so a failed time verdict is ADVISORY")" "$record"
  printf '99.00\n' >"$T_HOST_DIR/load"
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_QUIET_HOST_WAIT_S=0 -e PX_SCOPE_ID=k3d-px/px-selftest "$T_WORK/drivers/exit-code.sh"
  record="$(cat "$(t_bundle)/run-record.tsv" 2>/dev/null)"
  t_equals k3d_busy_host_refuses_a_measurement_run "4 refused no" "$T_STATUS $(t_manifest \
    "$(t_bundle)" /quiet_host/state) $(t_flag "verb${PX_TAB}prepare" "$record")"
  printf '0.00\n' >"$T_HOST_DIR/load"
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=k3d-px/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  t_equals k3d_validation_run_is_contaminated_by_its_shared_nodes \
    "0 off quiet-host dedicated-node" "$T_STATUS $(t_manifest "$run" /quiet_host/state)\
 $(t_manifest "$run" /quiet_host/tags/0) $(t_manifest "$run" /quiet_host/tags/1)"
  rm -f "$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  : >"$T_HOST_DIR/uptime.log"
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_QUIET_HOST_WAIT_S=0 -e PX_SCOPE_ID=k3d-px/px-selftest "$T_WORK/drivers/exit-code.sh"
  t_equals remote_k8s_keeps_the_dedicated_node_check "2 refused 0" "$T_STATUS $(t_manifest \
    "$(t_bundle)" /quiet_host/state) $(grep -c . "$T_HOST_DIR/uptime.log")"
  t_empty k3d_env_sets_no_quiet_host_requirement "$(grep 'PX_REQUIRE_QUIET_HOST' \
    "$PX_HOME/k8s/env/k3d.env")"
  # Individual runs leave the deployment namespace in place. Invoking px-cluster down removes only
  # the namespace that it provisioned across all run classes (D1). Specifying down --label
  # k3d.cluster=px additionally deletes the local k3d cluster after removing the namespace, whereas
  # naming a k3d cluster with no px-k3d record deletes no k3d cluster and supplying any other label
  # key fails as a usage error (RQ-310).
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  mkdir -p "$PX_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  printf 'px|--servers 1\n' >"$T_K1_A5/k3d-clusters.txt"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-run" --backend k8s --class measurement \
    -e PX_REQUIRE_QUIET_HOST=0 -e PX_QUIET_HOST_WAIT_S=0 -e PX_SCOPE_ID=k3d-px/px-selftest \
    "$T_WORK/drivers/exit-code.sh"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k8s_run_leaves_the_namespace "0 no no" "$T_STATUS $(t_flag \
    'delete namespace px-selftest' "$calls") $(t_flag 'k3d ' "$calls")"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-cluster" --backend k8s --class measurement \
    -e PX_SCOPE_ID=k3d-px/px-selftest down
  calls="$(cat "$T_SHIM_LOG")"
  t_equals measurement_down_keeps_the_k3d_cluster "0 yes no yes" "$T_STATUS $(t_flag \
    'delete namespace px-selftest' "$calls") $(t_flag 'k3d ' "$calls") $(t_exists \
    "$PX_OUT/state/px-k3d/scope-k3d-px.tsv")"
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  printf 'scope\tk3d-px\n' >"$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  printf 'px|--servers 1\n' >"$T_K1_A5/k3d-clusters.txt"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-cluster" --backend k8s --class measurement \
    -e PX_SCOPE_ID=k3d-px/px-selftest down --label k3d.cluster=px
  t_equals down_label_deletes_namespace_then_k3d_cluster "0 yes no" "$T_STATUS $(awk '
    / delete namespace px-selftest / && !n { n = NR } /^k3d cluster delete px/ { k = NR }
    END { print (n && k > n) ? "yes" : "no" }' "$T_SHIM_LOG") $(t_exists \
    "$PX_OUT/state/px-k3d/scope-k3d-px.tsv")"
  t_k2_seed_on k3d-px "$T_K1_A5" "$PX_OUT"
  printf 'scope\tk3d-px\n' >"$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  printf 'px|--servers 1\n' >"$T_K1_A5/k3d-clusters.txt"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-cluster" --backend k8s --class validation \
    -e PX_SCOPE_ID=k3d-px/px-selftest down
  calls="$(cat "$T_SHIM_LOG")"
  t_equals validation_run_end_deletes_only_the_namespace "0 yes no yes" "$T_STATUS $(t_flag \
    'delete namespace px-selftest' "$calls") $(t_flag 'k3d ' "$calls") $(t_exists \
    "$PX_OUT/state/px-k3d/scope-k3d-px.tsv")"
  rm -f "$PX_OUT/state/px-k3d/scope-k3d-px.tsv" "$T_K1_A5/k3d-clusters.txt"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_shim_log_reset
  t_k1_a5 "$T_BASH" "$T_BIN/px-cluster" --backend k8s --class measurement \
    -e PX_SCOPE_ID=fake/px-selftest down --label k3d.cluster=px
  calls="$(cat "$T_SHIM_LOG")"
  t_equals down_label_without_a_k3d_record_deletes_no_k3d_cluster "0 yes no" "$T_STATUS $(t_flag \
    'delete namespace px-selftest' "$calls") $(t_flag 'k3d ' "$calls")"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_shim_log_reset
  t_k1_a5 "$T_BASH" "$T_BIN/px-cluster" --backend k8s -e PX_SCOPE_ID=fake/px-selftest \
    down --label app=other
  t_equals down_label_with_another_key_is_usage "2 yes no" "$T_STATUS $(t_flag \
    'use k3d.cluster=NAME' "$T_STDERR") $(t_flag 'delete namespace' "$(cat "$T_SHIM_LOG")")"
  t_k1_a5 "$T_BASH" "$T_BIN/px-cluster" --backend k8s -e PX_SCOPE_ID=fake/px-selftest \
    down --keep --label k3d.cluster=px
  t_equals down_label_with_keep_is_usage "2 yes" "$T_STATUS $(t_flag \
    'deletes the data that --keep keeps' "$T_STDERR")"
  # A cluster targeted by --label is torn down even when the primary cluster has no recorded state,
  # while dry-run mode (-n) only records the deletion in the execution plan.
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  mkdir -p "$PX_OUT/state/px-k3d"
  printf 'scope\tk3d-px\n' >"$PX_OUT/state/px-k3d/scope-k3d-px.tsv"
  printf 'px|--servers 1\n' >"$T_K1_A5/k3d-clusters.txt"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster other \
    -n down --label k3d.cluster=px
  t_equals down_label_dry_run_plans_the_delete "0 yes no" "$T_STATUS $(t_flag \
    "$(printf 'plan\tdelete\tpx-k3d delete --label k3d.cluster=px')" "$T_STDOUT") $(t_flag 'k3d ' \
    "$(cat "$T_SHIM_LOG")")"
  t_shim_log_reset
  t_k1_a5 PX_K8S_CONTEXT=k3d-px "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster other \
    down --label k3d.cluster=px
  t_equals down_label_with_nothing_recorded_deletes_the_k3d_cluster "0 yes no" "$T_STATUS\
 $(t_flag 'k3d cluster delete px' "$(cat "$T_SHIM_LOG")") $(t_exists \
    "$PX_OUT/state/px-k3d/scope-k3d-px.tsv")"
  rm -f "$PX_OUT/state/px-k3d/scope-k3d-px.tsv" "$T_K1_A5/k3d-clusters.txt"
}

## @description  Verifies the round K2 boundary: the px-k3d token class, registry clients, and
##               static rules for cluster create, push, login, and secrets (RQ-284, RQ-285, RQ-307).
## @return       Records test assertions. Exit 0.
t_group_k2_boundary() {
  local root="$T_WORK/scan-k2" hits names=() code file
  printf '# K2: the boundary of the local cluster manager and the registry\n'
  mkdir -p "$root/bin" "$root/lib/backend" "$root/scripts"
  cat >"$root/bin/px-k3d" <<'K2_EOF'
#!/usr/bin/env bash
# A fixture.
k3d cluster list
docker ps
kubectl get pods
skopeo copy a b
K2_EOF
  printf '#!/usr/bin/env bash\n# A fixture.\ncrane push a b\n' >"$root/lib/backend/compose.sh"
  printf '#!/usr/bin/env bash\n# A fixture.\nx() { k3d version; }\n' >"$root/scripts/x.sh"
  cat >"$root/bin/px-y" <<'K2_EOF'
#!/usr/bin/env bash
# A fixture.
y="$_PXD_K3D"
K2_EOF
  hits="$(t_scan_root "$root")"
  t_equals scan_holds_px_k3d_to_its_class "bin/px-k3d:4 bin/px-k3d:5 bin/px-k3d:6 bin/px-y:3\
 lib/backend/compose.sh:3 scripts/x.sh:3" "$(printf '%s\n' "$hits" | cut -d: -f1,2 |
    tr '\n' ' ' | sed 's/ $//')"
  t_empty px_k3d_names_no_other_tool "$(grep -vE '^[[:space:]]*#' "$T_BIN/px-k3d" |
    grep -E "$T_NOT_K3D_RE")"
  mkdir -p "$root/stale/bin"
  printf '#!/usr/bin/env bash\nnoop() { :; }\n' >"$root/stale/bin/px-k3d"
  t_contains allowlist_stale_k3d_entry_is_reported 'bin/px-k3d: no k3d token left' \
    "$(t_allowlist_stale "$root/stale")"
  # Layer 2 runtime trap enforcement: verify that px-k3d invokes k3d via its resolved binary path,
  # whereas a bare k3d invocation in shell code is intercepted and blocked by the trap.
  t_k2_reset
  t_shim_log_reset
  t_k3d create
  t_equals px_k3d_reaches_k3d_by_path "0 yes" "$T_STATUS $(t_flag 'k3d cluster create px' \
    "$(cat "$T_SHIM_LOG")")"
  t_k3d delete
  rm -rf "$T_K2/trap"
  mkdir -p "$T_K2/trap/bin"
  cp -R "$PX_HOME/lib" "$PX_HOME/k8s" "$PX_HOME/docker" "$T_K2/trap/"
  # Rewrite the copied script to invoke k3d by its bare command name directly in the shell so that
  # the runtime trap function intercepts the call.
  cat >"$T_K2/trap.sed" <<'K2_EOF'
s|  px_timeout "$timeout" "$_PXD_K3D" "$@"|  k3d "$@"|
K2_EOF
  sed -f "$T_K2/trap.sed" "$T_BIN/px-k3d" >"$T_K2/trap/bin/px-k3d"
  t_shim_log_reset
  t_k8s "PX_HOME=$T_K2/trap" PX_K8S_CONTEXT=k3d-px "PX_K3S_IMAGE=$T_K3S_PIN" "$T_BASH" \
    "$T_K2/trap/bin/px-k3d" create
  t_equals px_k3d_bare_k3d_hits_the_trap "1 yes no" "$T_STATUS $(t_flag 'call blocked: k3d' \
    "$T_STDERR") $(t_flag 'k3d ' "$(cat "$T_SHIM_LOG")")"
  # Layer 3 positive and negative controls: verify that each fake registry client shim logs calls
  # when invoked directly and that no harness entry point ever invokes a registry client.
  IFS='|' read -r -a names <<<"$T_REGISTRY_NAMES"
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/shim-names.sh" "${names[@]}"
  t_equals registry_positive_control_records_each_client \
    "$(printf '%s version\n' "${names[@]}")" "$(cat "$T_SHIM_LOG")"
  t_empty negative_control_k2_round_reaches_no_registry_client "$(grep -E \
    "^($T_REGISTRY_NAMES) " "$T_K1/calls.log" "$T_SHIM_LOG" 2>/dev/null | grep -v ' version$')"
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  t_shim_log_reset
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  t_equals negative_control_k8s_run_reaches_no_registry_client "0 " "$T_STATUS $(grep -E \
    "^($T_REGISTRY_NAMES|k3d) |^(docker )?(push|login|tag) " "$T_SHIM_LOG")"
  # Audit non-comment source lines across the repository: only bin/px-k3d may invoke cluster
  # creation, only sb_image_push may push images to a registry, and no code may perform a registry
  # login or create a Kubernetes secret.
  code="$(find "$PX_HOME/bin" "$PX_HOME/lib" "$PX_HOME/scripts" "$PX_HOME/k8s" \
    "$PX_HOME/docker" "$PX_HOME/drivers" "$PX_HOME/suites" "$PX_HOME/plugins" -type f \
    2>/dev/null | LC_ALL=C sort | while IFS= read -r file; do
      t_in_scope "$file" || continue
      grep -nH -vE '^[[:space:]]*#' "$file" | sed "s|^$PX_HOME/||" || true
    done)"
  t_empty k8s_backend_holds_no_cluster_create "$(grep -n 'cluster create' \
    "$PX_HOME/lib/backend/k8s.sh")"
  t_equals only_px_k3d_names_cluster_create "bin/px-k3d" "$(printf '%s\n' "$code" |
    grep 'cluster create' | cut -d: -f1 | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')"
  t_equals registry_push_only_in_sb_image_push "1 sb_image_push" "$(printf '%s\n' "$code" |
    grep -cE 'px_compose_(engine|call)[^|]* push ') $(awk '/^[a-z_]+\(\) \{/ { f = $1 }
    /px_compose_(engine|call)[^|]* push / { sub(/\(\)/, "", f); print f }' \
    "$PX_HOME/lib/backend/compose.sh")"
  t_empty no_registry_login_in_the_tree "$(printf '%s\n' "$code" |
    grep -E '(^|[^[:alnum:]_-])login([^[:alnum:]_-]|$)')"
  t_empty no_secret_creation_in_the_tree "$(printf '%s\n' "$code" |
    grep -E 'create secret|kubectl create|px_k8s_call [0-9-]+ create ')"
  t_empty k8s_contract_has_32_functions "$(t_backend_verbs "$PX_HOME/lib/backend/k8s.sh")\
$(t_backend_verbs "$PX_HOME/lib/backend/compose.sh")$([ "$(sb_contract_functions | grep -c .)" \
    = 32 ] || echo count)"
}

# ---------------------------------------------------------------------------
# K5: Distributed Pherf tier on Kubernetes
# ---------------------------------------------------------------------------
# Every test case executes against the fake kubectl shim and the H3 stub controller. The fake
# kubectl creates Deployment pods with names that carry no ordinal, and the stub controller serves
# host curl requests routed through the fake port-forward loopback tunnel because it ignores the
# URL host.

# Pod names assigned by the fake kubectl shim to the tier pods during a fresh provision, where the
# controller Deployment is applied first followed by the two-replica agent Deployment.
T_K5_C='pherf-controller-5c9f7d8b6-fk0001'
T_K5_A0='pherf-agent-5c9f7d8b6-fk0002'
T_K5_A1='pherf-agent-5c9f7d8b6-fk0003'

## @description  Runs px-pherf (option...) on the Kubernetes backend against the fake kubectl shim
##               and the stub controller in the K1 state directory.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_k5_pherf() {
  t_k8s "PATH=$T_H3_BIN:$T_SHIM_DIR:$PATH" "$T_BASH" "$T_BIN/px-pherf" --backend k8s \
    --cluster selftest --class validation "$@"
}

## @description  Populates the stub GET /v1/agents response in the K1 state directory (agent
##               id:state...).
## @return       Exit 0.
t_k5_agents() { T_A2_STATE=$T_K8S_STATE t_h3_agents agents "$@"; }

## @description  Provisions a fresh selftest deployment with the Pherf tier in the fake kubectl
##               state and resets the stub controller with two IDLE agents.
## @return       Sets T_STATUS from the provision call. Exit 0.
t_k5_provision() {
  t_k8s_provision -e PX_PHERF=1
  T_A2_STATE=$T_K8S_STATE t_h3_reset
  t_k5_agents "$T_K5_A0:IDLE" "$T_K5_A1:IDLE"
}

## @description  Reports the process state of a tunnel (pid) via t_proc_state, or none when the
##               tunnel record contains no PID.
## @return       Writes the state word to stdout. Exit 0.
t_k5_tunnel_state() { if [ -n "$1" ]; then t_proc_state "$1"; else printf none; fi; }

## @description  Outputs the sorted basenames of all files in a directory (directory) on one line.
## @return       Writes the space-separated file names to stdout. Exit 0.
t_k5_names() {
  find "$1" -type f 2>/dev/null | sed 's|.*/||' | LC_ALL=C sort | paste -sd ' ' -
}

## @description  Extracts rendered tier manifest documents (render dir) matching the specified
##               resource kinds (kind regular expression).
## @return       Writes one flattened line per matching document to stdout. Exit 0.
t_k5_docs() {
  local file
  for file in "$1"/groups/pherf/*.yaml; do
    t_k8s_docs "$file"
  done | sed 's/| */|/g' | grep -E "\\|kind: ($2)\\|"
}

## @description  Verifies rendered Kubernetes manifests for the tier: controller, claim, Service,
##               agents, quorum ConfigMap, and mesh rules (RQ-318, RQ-319).
## @return       Records test assertions. Exit 0.
t_group_k5_render() {
  local dir g x doc bad="" file got want line on off name
  printf '# K5: the render of the tier\n'
  t_mgen --class validation --cluster k5 -e PX_PHERF=1
  dir="$T_K8S_OUT/deploy-k8s/k5"
  g="$dir/groups/pherf"
  t_equals k5_render_writes_the_three_tier_files "0 50-pherf-target.yaml 51-pherf-controller.yaml\
 52-pherf-agent.yaml" "$T_STATUS $(t_k5_names "$g")"
  # Verify that the controller renders as a single-replica Deployment with a persistent volume
  # claim mounted at its run directory and a rollout strategy that never runs two pods concurrently
  # against that directory (RQ-318).
  doc="$(t_k5_docs "$dir" Deployment | grep '|name: pherf-controller|')"
  t_equals k5_render_controller_is_one_replica_with_a_claim_at_the_run_dir \
    "yes yes yes yes yes yes" "$(t_flag '|replicas: 1|' "$doc") $(t_flag \
    '|strategy:|type: Recreate|' "$doc") $(t_flag \
    '|persistentVolumeClaim:|claimName: data-pherf-controller-0' "$doc") $(t_flag \
    '|mountPath: /var/lib/pherf/run|' "$doc") $(t_flag '|args:|- controller|- start|' \
    "$doc") $(t_flag '|storageClassName: local-path|' "$(t_k5_docs "$dir" \
    PersistentVolumeClaim | grep '|name: data-pherf-controller-0|')")"
  doc="$(t_k5_docs "$dir" Service)"
  t_equals k5_render_controller_service_is_cluster_ip_with_8780_and_8790 \
    "1 yes no http-rest:8780:http http-agent:8790:http" "$(printf '%s\n' "$doc" | grep -c .)\
 $(t_flag '|type: ClusterIP|' "$doc") $(t_flag 'clusterIP: None' "$doc") $(printf '%s\n' \
    "$doc" | tr '|' '\n' | awk '/- name: / { n = $3 } /port: / && !/target/ { p = $2 }
      /appProtocol: / { printf "%s%s:%s:%s", s, n, p, $2; s = " " }')"
  doc="$(t_k5_docs "$dir" Deployment | grep '|name: pherf-agent|')"
  line=PHERF_ZOOKEEPER
  t_equals k5_render_agents_are_a_deployment_named_by_the_pod "yes yes yes yes yes yes" \
    "$(t_flag '|replicas: 2|' "$doc") $(t_flag '|type: Recreate|' "$doc") $(t_flag \
    '|- name: PHERF_AGENT_ID|valueFrom:|fieldRef:|fieldPath: "metadata.name"|' "$doc") $(t_flag \
    '|- name: PHERF_CONTROLLER|value: "http://pherf-controller:8790"|' "$doc") $(t_flag \
    "|- name: PHERF_ZOOKEEPER|valueFrom:|configMapKeyRef:|name: pherf-target|key: $line|" \
    "$doc") $(t_flag '|args:|- agent|- start|' "$doc")"
  t_equals k5_render_harness_quorum_is_the_harness_zookeeper "yes 1" "$(t_flag \
    '|name: pherf-target|' "$(t_k5_docs "$dir" ConfigMap)") $(grep -c \
    '^  PHERF_ZOOKEEPER: "zookeeper-0:2181"$' "$g/50-pherf-target.yaml" 2>/dev/null)"
  # Verify that an external target render records the operator-supplied quorum string exclusively
  # in a single ConfigMap and generates manifests for the Pherf tier alone (RQ-318).
  t_mgen --class validation --cluster k5x -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e PX_PHERF_ZOOKEEPER=zk1.k5.invalid:2181
  x="$T_K8S_OUT/deploy-k8s/k5x"
  t_equals k5_render_external_quorum_is_only_in_the_configmap "0 groups/pherf/50-pherf-target.yaml\
 1 00-namespace.yaml 10-env.yaml 11-conf.yaml 0 3" "$T_STATUS $(cd "$x" 2>/dev/null &&
    grep -rl zk1.k5.invalid . | sed 's|^\./||' | tr '\n' ' ' | sed 's/ $//') $(cat \
    "$x"/groups/pherf/*.yaml 2>/dev/null | grep -c zk1.k5.invalid) $(t_k5_names \
    "$x/manifests") $(cat "$x"/manifests/*.yaml \
    "$x"/groups/*/*.yaml 2>/dev/null | grep -cE '^kind: (StatefulSet|Job|Pod)$') $(cat \
    "$x"/groups/*/*.yaml 2>/dev/null | grep -cE '^kind: (Deployment|PersistentVolumeClaim)$')"
  # Verify that the Kubernetes and Compose generators reject invalid tier configurations with
  # identical diagnostic messages (RQ-318).
  bad=""
  for line in PX_PHERF_TARGET=external PX_PHERF_ZOOKEEPER=zk1@k5.invalid:2181 \
    PX_PHERF_ZOOKEEPER=zk1.k5.invalid:2181 PX_PHERF_AGENT_COUNT=3; do
    set -- -e "$line"
    [ "${line%%=*}" != PX_PHERF_ZOOKEEPER ] || [ "${line#*zk1.}" != "$line" ] ||
      set -- "$@" -e PX_PHERF_TARGET=external
    t_mgen --class validation --cluster k5r -e PX_PHERF=1 "$@" --check
    got="${T_STDERR%%"$PX_NL"*}"
    got="$T_STATUS ${got#*: }"
    t_gen --class validation --cluster k5r -e PX_PHERF=1 "$@" --check
    want="${T_STDERR%%"$PX_NL"*}"
    want="$T_STATUS ${want#*: }"
    [ "$got" = "$want" ] && [ "${got%% *}" = 2 ] || bad="$bad [$*] [$want] [$got];"
  done
  t_empty k5_render_refuses_what_compose_refuses "$bad"
  bad=""
  for line in harness external; do
    set -- -e PX_PHERF=1
    [ "$line" = harness ] || set -- "$@" -e PX_PHERF_TARGET=external \
      -e PX_PHERF_ZOOKEEPER=zk1.k5.invalid:2181
    t_mgen --class measurement --cluster k5t "$@" --check
    got=$T_STDOUT
    t_gen --class measurement --cluster k5t "$@" --check
    [ "$T_STDOUT" = "$got" ] && [ -n "$got" ] || bad="$bad $line: [$T_STDOUT] [$got];"
  done
  t_empty k5_generators_give_the_same_tier_rows "$bad"
  t_equals k5_render_requests_count_the_tier "Deployment pherf-controller 1 250 1024 1 10|\
Deployment pherf-agent 2 375 1536 0 0|4096" "$(grep '^Deployment' "$dir/requests.tsv" |
    tr '\t' ' ' | paste -sd '|' -)|$(sed -n 's/^PX_GROUP_PHERF_MEM_MIB=//p' \
    "$dir/topology.env")"
  # Verify that the rendered tier manifests satisfy all baseline K1 manifest invariants: namespace
  # and cluster labels, Guaranteed QoS under the CPU-to-memory sizing rule, unprivileged container
  # security contexts, no host exposure, and protocol-prefixed Service port names.
  bad="$(t_k8s_docs "$g"/*.yaml | grep -vF '|  namespace: px-k5|'
    t_k8s_docs "$g"/*.yaml | grep -vF 'px.harness.cluster: "k5"')"
  for file in "$g"/*.yaml; do
    awk '/^ *requests:$/ { r = 1; l = 0; next } /^ *limits:$/ { l = 1; r = 0; next }
      r && /cpu:|memory:/ { req[$1] = $2 } l && /cpu:|memory:/ { lim[$1] = $2 }
      /^ *(readinessProbe|volumeMounts):/ { r = l = 0 }
      END { for (k in req) if (req[k] != lim[k]) bad = 1; for (k in lim) if (req[k] != lim[k])
        bad = 1; exit bad }' "$file" || bad="$bad ${file##*/} requests;"
  done
  while IFS= read -r line; do
    for want in 'runAsNonRoot: true' "runAsUser: $(id -u)" 'allowPrivilegeEscalation: false' \
      '- ALL' 'type: RuntimeDefault' 'enableServiceLinks: false' \
      'automountServiceAccountToken: false' '|- name: px|'; do
      [[ $line == *"$want"* ]] || bad="$bad [$want]"
    done
  done <<EOF
$(t_k5_docs "$dir" Deployment)
EOF
  bad="$bad$(grep -hE 'type: (NodePort|LoadBalancer)|hostPort|hostNetwork|hostPID|hostIPC' \
    "$g"/*.yaml)$(cat "$g"/*.yaml | awk '/^ *limits:$/ { l = 1; next } l && /cpu:/ { c = $2 }
    l && /memory:/ { m = $2; sub(/Mi$/, "", m); l = 0
      if (c != int((m * 250 + 1023) / 1024) "m") print m, c }')$(awk '/^kind: Service$/ { s = 1 }
    /^kind: Deployment$/ { s = 0 } s && /- name: / { n = $3 }
    s && /appProtocol: / { if (index(n, $2 "-") != 1) print n, $2 }' "$g"/*.yaml)"
  t_equals k5_tier_render_passes_the_k1_sweeps "2 " "$(t_k5_docs "$dir" Deployment |
    grep -c .) $bad"
  t_equals k5_tier_probes_are_readiness_only "controller agent|0" "$(cat "$g"/*.yaml | awk '
    /readinessProbe:/ { r = 1; next } r && /- \/opt\/pherf\/healthcheck.sh$/ { getline
      sub(/^ *- /, ""); printf "%s%s", s, $0; s = " "; r = 0 }')|$(cat "$g"/*.yaml |
    grep -cE 'livenessProbe|startupProbe')"
  # Verify that the image presence check includes the Pherf image, which the controller and agent
  # pods pull from the local node cache just like the core harness image (RQ-318).
  want="$(sed -n 's/^PX_PHERF_IMAGE_TAG=//p' "$dir/topology.env")"
  printf 'docker.io/library/%s\n' "$(sed -n 's/^ *image: "\(.*\)"$/\1/p' \
    "$dir/manifests/28-regionserver.yaml")" >"$T_K8S_STATE/k8s-images"
  t_k8s_sb sb_image_present "$dir"
  line="$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' -v w="$want" \
    '$1 == w { print ($2 == "") ? "absent" : $2 }')"
  printf 'docker.io/library/%s\n' "$want" >>"$T_K8S_STATE/k8s-images"
  t_k8s_sb sb_image_present "$dir"
  t_equals k5_image_present_names_the_pherf_image "6 absent 0 docker.io/library/$want Never" \
    "$line $T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' -v w="$want" \
    '$1 == w { print $2 }') $(sed -n 's/^ *imagePullPolicy: //p' "$g"/*.yaml | LC_ALL=C sort -u)"
  rm -f "$T_K8S_STATE/k8s-images"
  # Verify that enabling the service mesh overlay enrolls the tier pods in the mesh automatically
  # without further opt-in, leaves the core cluster pods outside the mesh unless PX_K8S_MESH_CORE
  # opts them in, and preserves the setup Job's sidecar opt-out (RQ-319).
  t_mgen --class validation --cluster k5m -e PX_PHERF=1 --out deploy-k8s/k5-off
  off="$T_K8S_OUT/deploy-k8s/k5-off"
  t_mgen --class validation --cluster k5m -e PX_PHERF=1 -e PX_K8S_MESH=1 --out deploy-k8s/k5-on
  on="$T_K8S_OUT/deploy-k8s/k5-on"
  bad=""
  for name in pherf-controller pherf-agent; do
    [ "$(t_flag '|metadata:|annotations:|sidecar.istio.io/inject: "true"|' \
      "$(t_k5_docs "$on" Deployment | grep "|name: $name|")")" = yes ] || bad="$bad $name;"
  done
  t_equals k5_mesh_puts_the_tier_in_with_no_opt_in "0 yes yes" "$T_STATUS$bad $(t_flag \
    'sidecar.istio.io/inject: "false"' "$(t_k8s_docs "$on/manifests/28-regionserver.yaml" |
    grep '|kind: StatefulSet|')") $(t_flag 'sidecar.istio.io/inject: "false"' \
    "$(t_k8s_docs "$on/manifests/30-setup.yaml" | grep '|kind: Job|')")"
  t_equals k5_mesh_overlay_adds_only_metadata "yes yes" "$(t_exists "$off/groups/pherf")\
 $(t_exists "$on/groups/pherf")$(diff -r "$off" "$on" | grep '^[<>]' |
    grep -vE '^> +(istio-injection: "enabled"|annotations:)$' |
    grep -vE '^> +sidecar\.istio\.io/inject: "(false|true)"$')"
  t_equals k5_flat_render_has_no_tier_annotation "0 0" "$(cat "$off"/groups/pherf/*.yaml |
    grep -c 'sidecar.istio.io/inject') $(grep -rc 'istio-injection' "$off" | awk -F: \
    '{ s += $2 } END { print s }')"
}

## @description  Verifies Kubernetes backend management of the tier Deployments: group startup,
##               pod-name replica numbering, kubectl scale, group stop, and teardown (RQ-318).
## @return       Records test assertions. Exit 0.
t_group_k5_backend() {
  local calls dir
  printf '# K5: the backend for the tier\n'
  t_k5_provision
  dir="$T_K8S_OUT/deploy-k8s/selftest"
  t_shim_log_reset
  t_k8s_sb sb_group_start fake/px-selftest pherf 10
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_group_start_dry_runs_every_tier_file_first "yes 3" "$(printf '%s\n' "$calls" | awk '
    / apply --dry-run=client -f / { d[$NF] = 1; if (a) late = 1; next }
    / apply -f / { a = 1; if (!(($NF) in d)) bad = 1; n++ }
    END { print (n > 0 && !bad && !late) ? "yes" : "no" }') $(printf '%s\n' "$calls" |
    grep -c ' apply -f .*/groups/pherf/5[0-2]-pherf-')"
  t_equals k5_group_start_waits_for_the_deployment_replicas "$(t_rows 3 pherf-agent 0 ready \
    pherf-agent 1 ready pherf-controller 0 ready)" "$(printf '%s\n' "$T_STDOUT" | cut -f1-3)"
  t_equals k5_group_start_makes_the_pods_and_the_claim "0 2 1 yes" "$T_STATUS $(grep -c \
    '^pherf-agent-5c9f7d8b6-' "$T_K8S_STATE/k8s-pods.txt") $(grep -c \
    '^pherf-controller-5c9f7d8b6-' "$T_K8S_STATE/k8s-pods.txt") $(grep -qx \
    data-pherf-controller-0 "$T_K8S_STATE/k8s-pvc.txt" && echo yes || echo no)"
  # Because Deployment pods carry no StatefulSet ordinal, the backend assigns replica indices for
  # a role in pod-name order, sorting live pods first and Terminating pods after them.
  t_k8s_pod pherf-agent-5c9f7d8b6-fk0000 pherf-agent '' '' '' Deployment | awk -F '|' \
    -v OFS='|' '{ $7 = "2026-01-03T00:00:09Z"; print }' >>"$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_list fake/px-selftest pherf-agent
  t_equals k5_list_numbers_replicas_by_pod_name "$(t_rows 4 pherf-agent 0 "$T_K5_A0" running \
    pherf-agent 1 "$T_K5_A1" running pherf-agent 2 pherf-agent-5c9f7d8b6-fk0000 stopped)" \
    "$(printf '%s\n' "$T_STDOUT" | cut -f1-4)"
  grep -v '^pherf-agent-5c9f7d8b6-fk0000|' "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pods.new"
  mv "$T_K1/pods.new" "$T_K8S_STATE/k8s-pods.txt"
  # Because an evicted Deployment pod remains in the pod listing alongside the replacement created
  # by its ReplicaSet, it is ordered after the live pods and the role's readiness barrier does not
  # stop on it.
  t_k8s_pod pherf-agent-5c9f7d8b6-fk0000 pherf-agent '' '' '' Deployment evicted \
    >>"$T_K8S_STATE/k8s-pods.txt"
  t_k8s_sb sb_list fake/px-selftest pherf-agent
  t_equals k5_list_numbers_a_failed_replica_after_the_live_ones "$(t_rows 3 pherf-agent 0 \
    "$T_K5_A0" pherf-agent 1 "$T_K5_A1" pherf-agent 2 pherf-agent-5c9f7d8b6-fk0000)" \
    "$(printf '%s\n' "$T_STDOUT" | cut -f1-3)"
  t_k8s_sb sb_wait_ready fake/px-selftest 10 pherf-agent
  t_equals k5_wait_passes_beside_an_evicted_replica 0 "$T_STATUS"
  grep -v '^pherf-agent-5c9f7d8b6-fk0000|' "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pods.new"
  mv "$T_K1/pods.new" "$T_K8S_STATE/k8s-pods.txt"
  # Verify that scaling a Deployment down waits until the removed replica has fully disappeared,
  # which is delayed while the removed pod remains in the Terminating state.
  t_mgen --class validation --cluster selftest -e PX_PHERF=1 -e PX_PHERF_AGENT_COUNT=1
  printf '2\n' >"$T_K8S_STATE/scale-lingers"
  t_shim_log_reset
  t_k8s_sb sb_scale fake/px-selftest pherf-agent 1 10
  t_equals k5_scale_waits_until_the_removed_replica_is_gone "0 yes 1 no" "$T_STATUS $(t_flag \
    'scale deployment pherf-agent --replicas=1' "$(cat "$T_SHIM_LOG")") $(grep -c \
    '^pherf-agent-' "$T_K8S_STATE/k8s-pods.txt") $(t_flag doomed \
    "$(cat "$T_K8S_STATE/k8s-pods.txt")")"
  rm -f "$T_K8S_STATE/scale-lingers"
  t_equals k5_scale_applies_the_env_and_not_the_deployment "yes no" "$(t_flag \
    "apply -f $dir/manifests/10-env.yaml" "$(cat "$T_SHIM_LOG")") $(t_flag \
    '52-pherf-agent.yaml' "$(cat "$T_SHIM_LOG")")"
  # Verify that stopping the group deletes the group's Deployments while retaining the controller's
  # persistent volume claim.
  t_shim_log_reset
  t_k8s_sb sb_group_stop fake/px-selftest pherf 10
  t_equals k5_group_stop_deletes_the_deployments_and_keeps_the_claim "0 yes 0 yes yes" \
    "$T_STATUS $(t_flag \
    'delete statefulsets,deployments -l px.harness.cluster=selftest,px.harness.group=pherf' \
    "$(cat "$T_SHIM_LOG")") $(grep -c '^pherf-' "$T_K8S_STATE/k8s-pods.txt") $(grep -qx \
    data-pherf-controller-0 "$T_K8S_STATE/k8s-pvc.txt" && echo yes || echo no) $(grep -q \
    '^regionserver-0|' "$T_K8S_STATE/k8s-pods.txt" && echo yes || echo no)"
  # Verify that a full teardown deletes both the Deployments and the persistent volume claim,
  # whereas a retained teardown (--keep) preserves the claim.
  t_k5_provision
  t_k8s_sb sb_group_start fake/px-selftest pherf 10
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down --keep
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_teardown_keep_retains_the_claim "0 yes no yes 0" "$T_STATUS $(t_flag \
    'delete statefulsets,deployments,jobs,pods -l px.harness.cluster=selftest' "$calls")\
 $(t_flag 'delete pvc' "$calls") $(grep -qx data-pherf-controller-0 \
    "$T_K8S_STATE/k8s-pvc.txt" && echo yes || echo no) $(grep -c . "$T_K8S_STATE/k8s-deploy.txt")"
  t_k5_provision
  t_k8s_sb sb_group_start fake/px-selftest pherf 10
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_teardown_deletes_the_deployments_and_the_claim "0 yes yes yes" "$T_STATUS $(t_flag \
    'delete statefulsets,deployments,jobs,pods -l px.harness.cluster=selftest' "$calls")\
 $(t_flag 'delete pvc -l px.harness.cluster=selftest' "$calls") $(t_flag \
    'get statefulsets,deployments,jobs,pods,pvc -l px.harness.cluster=selftest' "$calls")"
  t_k8s_provision
  t_shim_log_reset
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  t_equals k5_teardown_of_a_render_without_the_tier_names_no_deployment "0 no" "$T_STATUS\
 $(t_flag deployments "$(cat "$T_SHIM_LOG")")"
  # When a newly scheduled pod has not yet been authorized for its assigned node by the node
  # authorizer, the kubelet transiently reports CreateContainerConfigError with "failed to sync
  # configmap cache" and retries container creation. The readiness barrier treats that transient
  # condition as pending until its timeout expires, whereas any other configuration error aborts
  # the barrier immediately.
  t_k5_config_pod 'failed to sync configmap cache: timed out waiting for the condition'
  cp "$T_K1/ready-pods" "$T_K8S_STATE/k8s-next.txt"
  printf '2\n' >"$T_K8S_STATE/k8s-next-after"
  t_k8s_sb sb_wait_ready fake/px-selftest 10
  t_equals k5_wait_holds_a_configmap_sync_error_as_pending "0 ready no" "$T_STATUS $(printf \
    '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "client" { print $3 }') $(t_flag \
    'terminated while the barrier waited' "$T_STDERR")"
  # If a pod disappears between the inventory listing and the subsequent query for its waiting
  # message, the readiness barrier treats it as pending and defers the decision to the next poll.
  t_k5_config_pod 'configmap "px-env" not found'
  awk -F '|' -v OFS='|' '$1 == "client-0" { $25 = "doomed:1" } { print }' \
    "$T_K8S_STATE/k8s-pods.txt" >"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  cp "$T_K1/ready-pods" "$T_K8S_STATE/k8s-next.txt"
  printf '2\n' >"$T_K8S_STATE/k8s-next-after"
  t_k8s_sb sb_wait_ready fake/px-selftest 10
  t_equals k5_wait_holds_a_pod_gone_before_its_message_read_as_pending "0 ready no no" \
    "$T_STATUS $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$1 == "client" { print $3 }') $(t_flag \
    'did not read the waiting message' "$T_STDERR") $(t_flag 'terminated while' "$T_STDERR")"
  t_k5_config_pod 'configmap "px-env" not found'
  t_k8s_sb sb_wait_ready fake/px-selftest 10
  t_equals k5_wait_stops_on_another_config_error "3 yes" "$T_STATUS $(t_flag \
    'client/0(CreateContainerConfigError: configmap "px-env" not found)' "$T_STDERR")"
  t_k5_config_pod 'failed to sync configmap cache: timed out waiting for the condition'
  t_k8s_sb sb_wait_ready fake/px-selftest 1
  t_equals k5_wait_expiry_reports_the_last_configmap_message "4 yes yes" "$T_STATUS $(t_flag \
    'readiness barrier expired' "$T_STDERR") $(t_flag \
    'client/0(failed to sync configmap cache: timed out waiting for the condition)' "$T_STDERR")"
}

## @description  Seeds the core scope with pod client-0 waiting on CreateContainerConfigError with
##               a given waiting message (message), saving the ready pods in T_K1/ready-pods.
## @return       Exit 0.
t_k5_config_pod() {
  t_k8s_seed "$T_K8S_STATE" "$T_K8S_OUT"
  cp "$T_K8S_STATE/k8s-pods.txt" "$T_K1/ready-pods"
  t_k8s_pod client-0 client 0 '' '' '' config >"$T_K1/pod"
  grep -v '^client-0|' "$T_K8S_STATE/k8s-pods.txt" >>"$T_K1/pod"
  mv "$T_K1/pod" "$T_K8S_STATE/k8s-pods.txt"
  printf 'client-0|%s\n' "$1" >"$T_K8S_STATE/k8s-waiting.txt"
}

## @description  Verifies px-pherf and the pherf-distributed driver on Kubernetes, covering the
##               loopback tunnel, pod-name agent IDs, and kubectl agent scaling (RQ-318, RQ-320).
## @return       Records test assertions. Exit 0.
t_group_k5_fleet() {
  local calls record pid run line args=() scenario schema new dir="$T_A2_OUT/deploy/a2"
  printf '# K5: px-pherf and the driver through a loopback tunnel\n'
  t_k5_provision
  t_shim_log_reset
  t_k5_pherf start
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_px_pherf_reaches_the_api_through_a_loopback_tunnel "0 yes no yes" "$T_STATUS\
 $(t_flag "port-forward --address 127.0.0.1 pod/$T_K5_C :8780" "$calls") $(t_flag \
    " exec -i $T_K5_C " "$calls") $(t_flag \
    'GET /v1/agents' "$(cat "$T_K8S_STATE/pherf/calls.log" 2>/dev/null)")"
  t_equals k5_agent_wait_expects_the_pod_names "$T_K5_A0 IDLE|$T_K5_A1 IDLE" \
    "$(printf '%s\n' "$T_STDOUT" | cut -f1,2 | tr '\t' ' ' | paste -sd '|' -)"
  record="$T_K8S_OUT/state/k8s/tunnel-fake_px-selftest_pherf-controller_0_rest.tsv"
  pid="$(cut -f1 "$record" 2>/dev/null)"
  t_k5_pherf status
  t_equals k5_px_pherf_status_reuses_the_tunnel "0 pherf 3 3 up 2 $pid run" "$T_STATUS $(printf \
    '%s\n' "$T_STDOUT" | sed -n 1p | tr '\t' ' ') $(printf '%s\n' "$T_STDOUT" |
    grep -c '^pherf-agent-5c9f7d8b6-') $(cut -f1 "$record" 2>/dev/null) $(t_k5_tunnel_state "$pid")"
  # Verify that on Kubernetes the agent readiness wait counts IDLE agents and aborts immediately
  # if any agent reports the FAULTED state.
  t_k5_agents "$T_K5_A0:IDLE"
  t_k5_pherf -e PX_TIMEOUT_READY=2 --keep start
  t_equals k5_agent_wait_counts_the_idle_agents "4 yes" "$T_STATUS $(t_flag \
    ' 1 of 2 agents IDLE' "$T_STDERR")"
  t_k5_agents "$T_K5_A0:IDLE" "$T_K5_A1:FAULTED"
  t_k5_pherf -e PX_TIMEOUT_READY=2 --keep start
  t_equals k5_agent_wait_stops_on_a_faulted_agent "3 yes" "$T_STATUS $(t_flag \
    "$T_K5_A1 is FAULTED, and only a reset clears it: the fixture fault" "$T_STDERR")"
  t_k5_agents "$T_K5_A0:IDLE" "$T_K5_A1:IDLE"
  # Verify that replacing the controller pod terminates the existing port-forward tunnel and opens
  # a new tunnel to the replacement pod.
  t_k8s "$T_SHIM_DIR/kubectl" --context fake --namespace px-selftest delete pod "$T_K5_C"
  t_shim_log_reset
  : >"$T_K8S_STATE/k8s-forward.log"
  t_k5_pherf status
  new="$(awk -F '|' '$3 == "pherf-controller" { print $1 }' "$T_K8S_STATE/k8s-pods.txt")"
  t_equals k5_controller_replacement_opens_a_new_tunnel "0 yes yes gone yes" "$T_STATUS\
 $([ -n "$new" ] && [ "$new" != "$T_K5_C" ] && echo yes || echo no) $(t_flag \
    "port-forward --address 127.0.0.1 pod/$new :8780" "$(cat "$T_SHIM_LOG")")\
 $(t_k5_tunnel_state "$pid") $(t_flag "ended pod/$T_K5_C" "$(cat "$T_K8S_STATE/k8s-forward.log")")"
  pid="$(cut -f1 "$record" 2>/dev/null)"
  # Verify that px-pherf scale executes kubectl scale against the pherf-agent Deployment and waits
  # for the target number of agents to become IDLE (RQ-318).
  t_k5_agents "$T_K5_A0:IDLE"
  t_shim_log_reset
  # Because the seeded cluster has no persisted cluster record, the scale invocation passes
  # PX_PHERF=1 explicitly just as cluster bring-up would have.
  t_k5_pherf -e PX_K8S_STORAGE_CLASS=local-path -e PX_PHERF=1 scale 1
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_px_pherf_scale_issues_kubectl_scale "0 yes 0 1 1" "$T_STATUS $(t_flag \
    'kubectl --context fake --namespace px-selftest scale deployment pherf-agent --replicas=1' \
    "$calls") $(printf '%s\n' "$calls" | sed -n '/ scale deployment /,$p' | grep -c \
    'apply -f .*52-pherf-agent.yaml') $(grep -c \
    "${PX_TAB}pherf-agent$PX_TAB" "$T_K8S_OUT/deploy-k8s/selftest/services.tsv") $(printf '%s\n' \
    "$T_STDOUT" | awk -F '\t' '$2 == "IDLE"' | grep -c .)"
  t_k5_pherf scale 0
  t_equals k5_px_pherf_scale_refuses_a_count_out_of_range "2 yes" "$T_STATUS $(t_flag \
    'an agent count of 1 to 9' "$T_STDERR")"
  # Verify that failure to establish a port-forward tunnel to the controller is reported as an
  # infrastructure anomaly.
  : >"$T_K8S_STATE/k8s-forward-fails"
  [ -z "$pid" ] || kill "$pid" 2>/dev/null || true
  t_k5_pherf status
  t_equals k5_controller_without_a_tunnel_is_an_anomaly "3 yes" "$T_STATUS $(t_flag \
    'unable to forward port' "$T_STDERR")"
  rm -f "$T_K8S_STATE/k8s-forward-fails"
  t_k5_pherf status
  pid="$(cut -f1 "$record" 2>/dev/null)"
  line="$T_STATUS $(t_k5_tunnel_state "$pid")"
  t_k5_pherf stop
  t_equals k5_px_pherf_stop_releases_the_tunnel "0 run 0 no gone 0" "$line $T_STATUS $(t_exists \
    "$record") $(t_k5_tunnel_state "$pid") $(grep -c '^pherf-' "$T_K8S_STATE/k8s-pods.txt")"
  # Verify that a failed start command stops the tier group by default and releases the tunnel
  # opened during startup. The preceding scale command left one agent in the rendered topology,
  # while the stub controller reports zero registered agents.
  t_k5_agents
  : >"$T_K8S_STATE/k8s-forward.log"
  line="$(grep -c . "$T_WORK/forward.pids" 2>/dev/null)" || line=0
  t_k5_pherf -e PX_TIMEOUT_READY=2 start
  pid="$(sed -n "$((line + 1))p" "$T_WORK/forward.pids" 2>/dev/null)"
  t_equals k5_failed_start_releases_the_tunnel "4 no gone yes 0" "$T_STATUS $(t_exists \
    "$record") $(t_k5_tunnel_state "$pid") $(t_flag 'ended pod/pherf-controller-' \
    "$(cat "$T_K8S_STATE/k8s-forward.log")") $(grep -c '^pherf-' "$T_K8S_STATE/k8s-pods.txt")"
  t_k5_agents "$T_K5_A0:IDLE" "$T_K5_A1:IDLE"
  t_k8s "$T_BASH" "$T_BIN/px-cluster" --backend k8s --cluster selftest down
  # Verify that the pherf-distributed driver executes unchanged against an external-target
  # Kubernetes deployment: it interacts exclusively through backend contract calls, running curl
  # inside pherf-controller 0, and waits for the required count of IDLE agents just as px-pherf
  # does on Kubernetes.
  rm -rf "$T_K1_A5"
  mkdir -p "$T_K1_A5"
  # Configure the fake metrics server to report 256 MiB per pod so the post-run peak memory check
  # passes cleanly.
  printf '256Mi\n' >"$T_K1_A5/top-mem"
  t_mgen --class validation --cluster selftest -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
    -e PX_PHERF_ZOOKEEPER=zk1.k5.invalid:2181 --out deploy-k8s/k5drv
  rm -rf "$PX_OUT/state/k8s" "$PX_OUT/state/scope-k8s-selftest" \
    "$PX_OUT/state/cluster-selftest.env"
  t_k8s "PX_FAKE_STATE_DIR=$T_K1_A5" "PX_OUT=$PX_OUT" "$T_BASH" "$T_WORK/probe/sb.sh" \
    sb_scope_create selftest
  printf 'fake/px-selftest\n' >"$PX_OUT/state/scope-k8s-selftest"
  printf '%s\n' "$T_K8S_OUT/deploy-k8s/k5drv" >"$PX_OUT/state/deployment-k8s-selftest"
  t_k8s "PX_FAKE_STATE_DIR=$T_K1_A5" "PX_OUT=$PX_OUT" "$T_BASH" "$T_WORK/probe/sb.sh" \
    sb_provision fake/px-selftest "$T_K8S_OUT/deploy-k8s/k5drv" 30
  t_k8s "PX_FAKE_STATE_DIR=$T_K1_A5" "PX_OUT=$PX_OUT" "$T_BASH" "$T_WORK/probe/sb.sh" \
    sb_group_start fake/px-selftest pherf 10
  line="$T_STATUS"
  t_h3_driver_reset
  cp -R "$T_A5_STATE/pherf" "$T_K1_A5/pherf"
  sed -e "s/pherf-agent-0/$T_K5_A0/g" -e "s/pherf-agent-1/$T_K5_A1/g" "$T_A5_STATE/pherf/agents" \
    >"$T_K1_A5/pherf/agents"
  scenario="$PX_HOME/suites/pherf/px_fleet_scenario.xml"
  schema="$PX_HOME/suites/pherf/px_fleet_schema.sql"
  args=(--scenario "$scenario" --schema "$schema" --agents 2 --rows 2000 --rate 40
    --duration-ms 15000 --threads 4)
  t_shim_log_reset
  t_k1_a5 "PATH=$T_H3_BIN:$T_A5/bin:$T_SHIM_DIR:$PATH" "PX_PHOENIX_HOME=$T_A5/phoenix" \
    "PX_HBASE_HOME=$T_A5/hbase" "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest pherf-distributed -- "${args[@]}"
  run="$(t_bundle)"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals k5_driver_runs_unchanged_on_kubernetes "0 0 describe:0 validate:0 prepare:0 run:0\
 restore:0 report:0 7 0 no yes" "$line $T_STATUS $(t_verb_codes "$run") $(find \
    "$run/drivers/pherf-distributed/controller" -type f 2>/dev/null | grep -c .) $(t_validate \
    "$run" >/dev/null; printf '%s' "$T_STATUS") $(t_flag 'port-forward' "$calls") $(t_flag \
    " exec -i $T_K5_C -c px -- " "$calls")"
  rm -rf "$PX_OUT/state/k8s" "$PX_OUT/state/scope-k8s-selftest" \
    "$PX_OUT/state/deployment-k8s-selftest" "$PX_OUT/state/cluster-selftest.env"
  t_a5_state_reset
  # Verify that on the Compose backend, px-pherf scale delegates scaling of the agent service to
  # px-cluster scale.
  # Bringing up a cluster from a rendered directory persists the render settings in the cluster
  # record, so a subsequent scale requires neither the run class nor PX_PHERF=1.
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 --class validation -e PX_PHERF=1 render
  t_a2_provision "$dir"
  printf '%s\n' "$((64 * 1073741824))" >"$T_A2_STATE/mem-total"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 up "$dir" --no-wait
  t_equals up_of_a_render_records_its_settings "0 yes yes" "$T_STATUS $(t_flag "PX_PHERF='1'" \
    "$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)") $(t_flag \
    "PX_RUN_CLASS='validation'" "$(cat "$T_A2_OUT/state/cluster-a2.env" 2>/dev/null)")"
  t_h3_reset
  t_h3_agents agents pherf-agent-0:IDLE pherf-agent-1:IDLE
  t_h3_pherf --cluster a2 start
  t_h3_agents agents pherf-agent-0:IDLE
  t_shim_log_reset
  t_h3_pherf --cluster a2 scale 1
  t_equals px_pherf_scale_on_compose_scales_the_agent_service "0 1 yes 1" "$T_STATUS $(grep -c \
    "${PX_TAB}pherf-agent$PX_TAB" "$dir/services.tsv") $(t_flag 'rm -f' "$(cat "$T_SHIM_LOG")")\
 $(printf '%s\n' "$T_STDOUT" | awk -F '\t' '$2 == "IDLE"' | grep -c .)"
  t_h3 "$T_BASH" "$T_BIN/px-cluster" --cluster a2 down
  printf '25165824000\n' >"$T_A2_STATE/mem-total"
}

# ---------------------------------------------------------------------------
# K7: Multi-node target verification on the fakes
# ---------------------------------------------------------------------------
# Round K7 requires a real multi-node cluster, which is unavailable on this host (G22). These test
# cases verify against the fakes every multi-node property that the runbook in docs/backends.md
# ("Multi-node proof") inspects on a real cluster: the preflight capacity sum across nodes, the
# placement configuration of every pod, the promoted digests of both images, the external tier, and
# the placement map recorded in a run bundle.

## @description  Populates the K2 state with three fake nodes (memory MiB, processors, labels of
##               node 3).
## @return       Writes the fake node inventory file. Exit 0.
t_k7_nodes() {
  printf 'n1|%sMi|%s||pool=phx\nn2|%sMi|%s||pool=phx\nn3|%sMi|%s||%s\n' "$1" "$2" "$1" "$2" \
    "$1" "$2" "${3:-pool=phx}" >"$T_K8S_STATE/k8s-nodes.txt"
}

## @description  Extracts and flattens every workload manifest document in a rendered deployment
##               directory (directory), including the tier manifests.
## @return       Writes one line per StatefulSet, Job, Pod, or Deployment to stdout. Exit 0.
t_k7_workloads() {
  local file
  for file in "$1"/manifests/*.yaml "$1"/groups/*/*.yaml; do
    [ -f "$file" ] && t_k8s_docs "$file"
  done | sed 's/| */|/g' | grep -E '\|kind: (StatefulSet|Job|Pod|Deployment)\|'
}

## @description  Extracts the pod placement entries from a run bundle (bundle) as sorted
##               "role node" lines.
## @return       Writes the sorted placement lines to stdout. Exit 0.
t_k7_placement() {
  local i=0 role
  while :; do
    role="$(t_manifest "$1" "/kubernetes/placement/$i/role")"
    [ -n "$role" ] || break
    printf '%s %s\n' "$role" "$(t_manifest "$1" "/kubernetes/placement/$i/node")"
    i=$((i + 1))
  done | LC_ALL=C sort
}

## @description  Verifies against the fakes the multi-node behavior inspected by the K7 runbook
##               (RQ-289, RQ-295, RQ-299, RQ-301, RQ-306, RQ-318, RQ-328).
## @return       Records test assertions. Exit 0.
t_group_k7_multinode() {
  local dir file total milli largest node cpu target d1 d2 tier docs bad="" line want bundle run
  printf '# K7: a multi-node target, on the fakes\n'
  # Verify that preflight sums allocatable memory and CPU across all selected nodes so that three
  # nodes, none of which can host the deployment alone, together admit it (RQ-289).
  t_k2_reset
  t_mgen --class validation --cluster selftest
  file="$T_K8S_OUT/deploy-k8s/selftest/requests.tsv"
  total="$(awk -F '\t' '!/^#/ && NF { s += $3 * $5 } END { print s }' "$file")"
  milli="$(awk -F '\t' '!/^#/ && NF { s += $3 * $4 } END { print s }' "$file")"
  largest="$(awk -F '\t' '!/^#/ && NF && $5 > m { m = $5 } END { print m }' "$file")"
  node=$(((total + 2) / 3 + 1))
  cpu=$(((milli + 2999) / 3000))
  t_k7_nodes "$node" "$cpu"
  t_pfk
  t_equals k7_preflight_sums_three_nodes "0 ok ok ok yes yes yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" memory_fit) $(t_k2_row "$T_STDOUT" pod_fit) $(t_k2_row "$T_STDOUT" cpu_fit)\
 $(t_flag "$((3 * node * 1048576)) bytes allocatable on the selected nodes" "$T_STDOUT")\
 $(t_flag "$((node * 1048576)) bytes allocatable on the largest selected node" "$T_STDOUT")\
 $([ "$node" -lt "$total" ] && [ $((cpu * 1000)) -lt "$milli" ] && echo yes || echo no)"
  # Verify that three nodes whose combined capacity falls short reject the deployment even when
  # each individual node can accommodate the largest pod.
  node=$((total / 3 - 1))
  t_k7_nodes "$node" "$cpu"
  t_pfk
  t_equals k7_preflight_refuses_three_nodes_short_in_sum "3 fail ok yes" "$T_STATUS $(t_k2_row \
    "$T_STDOUT" memory_fit) $(t_k2_row "$T_STDOUT" pod_fit) $([ "$largest" -le "$node" ] &&
    echo yes || echo no)"
  # Verify that a node selector matching only two of the three nodes restricts the capacity sum to
  # those two matching nodes.
  node=$(((total + 2) / 3 + 1))
  t_k7_nodes "$node" "$cpu" pool=other
  t_shim_log_reset
  t_pfk PX_K8S_NODE_SELECTOR=pool=phx
  t_equals k7_preflight_counts_two_of_three_selected_nodes "3 fail yes yes" "$T_STATUS \
$(t_k2_row "$T_STDOUT" memory_fit) $(t_flag "$((2 * node * 1048576)) bytes allocatable on the\
 selected nodes" "$T_STDOUT") $(t_flag 'get nodes -l pool=phx' "$(cat "$T_SHIM_LOG")")"
  rm -f "$T_K8S_STATE/k8s-nodes.txt"
  # Verify that when rendering for a remote multi-node target with the tier enabled, every workload
  # manifest, including the tier Deployments, carries the node selector, both tolerations, and the
  # pull secret, references its image by the promoted digest, and spreads the 3 DataNode and 3
  # RegionServer replicas across hosts (RQ-301, RQ-306).
  d1="sha256:$(printf '1%.0s' $(seq 1 64))"
  d2="sha256:$(printf '2%.0s' $(seq 1 64))"
  t_mgen --class validation --cluster k7t -e PX_PHERF=1
  tier="$(t_env_value "$T_K8S_OUT/deploy-k8s/k7t/topology.env" PX_PHERF_IMAGE_TAG)"
  target="$(t_k2_target k7 PX_K8S_STORAGE_CLASS=gp3 PX_K8S_REGISTRY=reg.example:5000/phx \
    PX_K8S_PULL_SECRET=regcred PX_K8S_NODE_SELECTOR=pool=phx,tier=perf \
    PX_K8S_TOLERATIONS=dedicated=phoenix:NoSchedule,spot:PreferNoSchedule \
    PX_K8S_DEDICATED_NODES=1)"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster k7 -e PX_PHERF=1 -e PX_RS_COUNT=3 -e PX_DN_COUNT=3 \
    -e PX_IMAGE_TAG=phoenix-harness:selftest \
    -e "PX_K8S_IMAGE_DIGESTS=phoenix-harness:selftest=$d1,$tier=$d2"
  dir="$T_K8S_OUT/deploy-k8s/k7"
  docs="$(t_k7_workloads "$dir")"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    [[ $line == *'|nodeSelector:|pool: "phx"|tier: "perf"|'* ]] || bad="$bad selector;"
    [[ $line == *'|- key: "dedicated"|operator: Equal|value: "phoenix"|effect: NoSchedule|'* ]] ||
      bad="$bad equal;"
    [[ $line == *'|- key: "spot"|operator: Exists|effect: PreferNoSchedule|'* ]] ||
      bad="$bad exists;"
    [[ $line == *'|imagePullSecrets:|- name: regcred|'* ]] || bad="$bad secret;"
    case "$line" in
    *'|kind: Deployment|'*) want="reg.example:5000/phx/${tier%%:*}@$d2" ;;
    *) want="reg.example:5000/phx/phoenix-harness@$d1" ;;
    esac
    [[ $line == *"|image: \"$want\"|imagePullPolicy: IfNotPresent|"* ]] ||
      bad="$bad image:${line:0:50};"
  done <<EOF
$docs
EOF
  t_equals k7_remote_tier_render_places_every_pod "0 2 yes" "$T_STATUS $(printf '%s\n' \
    "$docs" | grep -c '|kind: Deployment|') $([ "$(printf '%s\n' "$docs" | grep -c .)" -gt 2 ] &&
    echo yes || echo no)$bad"
  bad=""
  for line in datanode regionserver; do
    file="$(t_k8s_role_file "$dir" "$line")"
    t_k8s_docs "$file" | sed 's/| */|/g' | grep '|kind: StatefulSet|' | grep -q \
      "|replicas: 3|.*|podAntiAffinity:|preferredDuringSchedulingIgnoredDuringExecution:|.*\
|topologyKey: kubernetes.io/hostname|labelSelector:|matchLabels:|.*role: \"$line\"|" ||
      bad="$bad $line;"
  done
  t_empty k7_three_datanodes_and_regionservers_spread_by_host "$bad"
  rm -rf "$T_K8S_OUT/deploy-k8s/k7x"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster k7x -e PX_PHERF=1 -e PX_IMAGE_TAG=phoenix-harness:selftest \
    -e "PX_K8S_IMAGE_DIGESTS=phoenix-harness:selftest=$d1"
  t_equals k7_remote_tier_render_without_the_tier_digest_is_refused "2 yes no" "$T_STATUS \
$(t_flag 'px-cluster push' "$T_STDERR") $(t_exists "$T_K8S_OUT/deploy-k8s/k7x")"
  # Verify that rendering the external tier for a remote target emits manifests for the tier alone,
  # records the three-host quorum exclusively in the tier ConfigMap, and applies the target
  # placement rules to the tier pods (RQ-318, RQ-323).
  line=zk1.k7.invalid:2181,zk2.k7.invalid:2181,zk3.k7.invalid:2181
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$PX_HOME/k8s/manifests.gen.sh" --class validation \
    --cluster k7e -e PX_PHERF=1 -e PX_PHERF_TARGET=external -e "PX_PHERF_ZOOKEEPER=$line" \
    -e "PX_K8S_IMAGE_DIGESTS=$tier=$d2"
  dir="$T_K8S_OUT/deploy-k8s/k7e"
  docs="$(t_k7_workloads "$dir")"
  t_equals k7_external_tier_on_a_remote_target "0 00-namespace.yaml 10-env.yaml 11-conf.yaml\
 50-pherf-target.yaml 51-pherf-controller.yaml 52-pherf-agent.yaml|groups/pherf/50-pherf-target.\
yaml|2 2 2" "$T_STATUS $(t_k5_names "$dir/manifests") $(t_k5_names "$dir/groups")|$(cd "$dir" \
    2>/dev/null && grep -rl zk2.k7.invalid . | sed 's|^\./||' | paste -sd ' ' -)|$(printf \
    '%s\n' "$docs" | grep -c '|kind: Deployment|') $(printf '%s\n' "$docs" | grep -c \
    "|nodeSelector:|pool: \"phx\"|tier: \"perf\"|.*|imagePullSecrets:|- name: regcred|") $(printf \
    '%s\n' "$docs" | grep -c "|image: \"reg.example:5000/phx/${tier%%:*}@$d2\"|")"
  # Verify that for a three-node run with the tier active, the bundle placement map records the
  # assigned node of every pod and the per-role host spread inspected by the runbook, and the
  # bundle image inventory records both images (RQ-299, RQ-328).
  t_k8s_seed "$T_K1_A5" "$PX_OUT"
  dir="$T_K1/deploy-selftest"
  {
    t_rows 9 regionserver-1 regionserver 1 '' '' 3584 3072 512 17
    t_rows 9 regionserver-2 regionserver 2 '' '' 3584 3072 512 17
    t_rows 9 pherf-agent-0 pherf-agent 0 pherf on-demand 1536 537 0 17
    t_rows 9 pherf-agent-1 pherf-agent 1 pherf on-demand 1536 537 0 17
  } >>"$dir/services.tsv"
  {
    t_k8s_pod regionserver-1 regionserver '' 1 regionmover StatefulSet
    t_k8s_pod regionserver-2 regionserver '' 2 regionmover StatefulSet
    t_k8s_pod pherf-agent-5c9f7d8b6-fk0001 pherf-agent '' '' '' Deployment
    t_k8s_pod pherf-agent-5c9f7d8b6-fk0002 pherf-agent '' '' '' Deployment
  } >>"$T_K1_A5/k8s-pods.txt"
  printf 'regionserver|3|OnDelete|0|no\n' >"$T_K1_A5/k8s-sts.txt"
  awk -F '|' -v OFS='|' -v t="$tier" -v d="$d2" '
    $1 ~ /^regionserver-/ { $22 = "n" (substr($1, 14) + 1) }
    $1 == "client-0" { $22 = "n1" } $1 == "tooling-0" { $22 = "n2" }
    $1 ~ /^pherf-agent-/ { $20 = t; $21 = d; $22 = "n" (substr($1, 28) + 1) }
    { print }' "$T_K1_A5/k8s-pods.txt" >"$T_K1/k7-pods" && mv "$T_K1/k7-pods" \
    "$T_K1_A5/k8s-pods.txt"
  t_k1_a5 "$T_BASH" "$T_BIN/px-run" --backend k8s --class validation \
    -e PX_SCOPE_ID=fake/px-selftest "$T_WORK/drivers/exit-code.sh"
  run="$(t_bundle)"
  t_equals k7_bundle_places_each_pod_on_its_node "0 client n1|pherf-agent n2|pherf-agent n3|\
regionserver n1|regionserver n2|regionserver n3|tooling n2 3 2" "$T_STATUS $(t_k7_placement \
    "$run" | paste -sd '|' -) $(t_k7_placement "$run" | awk '$1 == "regionserver" { n[$2] = 1 }
    END { print length(n) }') $(t_k7_placement "$run" | awk '$1 == "pherf-agent" { n[$2] = 1 }
    END { print length(n) }')"
  t_validate "$run"
  t_equals k7_bundle_of_three_nodes_validates "0 ok" "$T_STATUS $(t_check_status "$T_STDOUT" \
    kubernetes.placement)"
  # Verify that promoting that run bundle pushes both images to the target registry and renders the
  # target manifests, including the tier, with each image pinned by its promoted digest (RQ-295,
  # RQ-306).
  bundle="$(t_bundle_copy "$run" k7-pass)"
  sed 's/"result": "[A-Z]*"/"result": "PASS"/' "$bundle/manifest.json" >"$bundle/m.json" &&
    mv "$bundle/m.json" "$bundle/manifest.json"
  printf '%s|phoenix-harness:selftest\n%s|%s\n' "$T_K8S_IMAGE_ID" "$d2" "$tier" \
    >"$T_K8S_STATE/local-images.txt"
  rm -f "$T_K8S_STATE/registry.log"
  t_k8s "PX_K8S_TARGET=$target" "$T_BASH" "$T_BIN/px-cluster" push "$bundle" \
    --class validation -e PX_PHERF=1 -e PX_IMAGE_TAG=phoenix-harness:selftest
  dir="$(printf '%s\n' "$T_STDOUT" | sed -n 1p)"
  docs="$(t_k7_workloads "$dir")"
  t_equals k7_push_promotes_both_images_of_the_bundle "0 2 yes yes 2 yes" "$T_STATUS $(grep -c \
    '^push ' "$T_K8S_STATE/registry.log" 2>/dev/null) $(t_flag \
    "push reg.example:5000/phx/$tier $d2" "$(cat "$T_K8S_STATE/registry.log" 2>/dev/null)")\
 $(t_flag "push reg.example:5000/phx/phoenix-harness:selftest $T_K8S_IMAGE_ID" \
    "$(cat "$T_K8S_STATE/registry.log" 2>/dev/null)") $(printf '%s\n' "$docs" | grep -c \
    "|kind: Deployment|.*|image: \"reg.example:5000/phx/${tier%%:*}@$d2\"|") $(t_flag \
    "$tier=$d2" "$(cat "$dir/render.env" 2>/dev/null)")"
  rm -f "$T_K8S_STATE/local-images.txt" "$T_K8S_STATE/registry.log"
}

# ---------------------------------------------------------------------------
# Command-line option parsing and test suite execution
# ---------------------------------------------------------------------------

while getopts ":kh" T_OPTION; do
  case "$T_OPTION" in
  # Instruct the exit hook to preserve the temporary fixture directory. PX_KEEP remains unexported
  # so that no command under test inherits it.
  k) PX_KEEP=1 ;;
  h)
    usage
    exit "$PX_EXIT_OK"
    ;;
  *)
    printf 'run-tests.sh: unknown option -%s\n' "$OPTARG" >&2
    usage >&2
    exit "$PX_EXIT_USAGE"
    ;;
  esac
done
shift $((OPTIND - 1))
if [ $# -gt 0 ]; then
  printf 'run-tests.sh: unexpected argument %s\n' "$1" >&2
  usage >&2
  exit "$PX_EXIT_USAGE"
fi

printf '# harness self-test in %s\n' "$PX_HOME"
printf '# bash %s, fixtures from heredocs, no runtime, no network, no Maven\n' \
  "${BASH_VERSION:-unknown}"
t_setup
printf '# fixtures in %s\n' "$T_WORK"
# Capture a baseline snapshot of all paths under the working tree root before running any test
# group, and switch to that root directory so any stray relative write lands inside the tree and is
# caught by the final cleanliness check.
if [ -n "$PX_TREE_ROOT" ]; then
  t_tree_paths "$PX_TREE_ROOT" >"$T_WORK/tree-before.txt"
  cd "$PX_TREE_ROOT" || exit "$PX_EXIT_FAIL"
fi

t_group_documents
t_group_scan
t_group_trap
t_group_shim
t_group_contract
t_group_sweep_scope
t_group_sweep_ready
t_group_sweep_access
t_group_sweep_evidence
t_group_sweep_scale
t_group_sweep_teardown
t_group_cluster
t_group_commands
t_group_cli
t_group_suite
t_group_run
t_group_config
t_group_output
t_group_emitters
t_group_a2_generator
t_group_a2_image
t_group_a2_preflight
t_group_a2_dist
t_group_d6
t_group_a2_platform
t_group_a2_cluster
t_group_a2_matrix
t_group_a3
t_group_a5_contract
t_group_a5_drivers
t_group_a5_bundle
t_group_a5_report
t_group_a5_checks
t_group_a5_plugins
t_group_a5_fixes
t_group_a5_closure
t_group_a6
t_group_h2_report
t_group_h2_verbs
t_group_h3_render
t_group_h3_preflight
t_group_h3_build
t_group_h3_pherf
t_group_h3_tier
t_group_h3_driver
t_group_h3_suite
t_group_h3_gate
t_group_d7_suites
t_group_k1_render
t_group_k1_backend
t_group_k1_faults
t_group_k1_runs
t_group_k1_boundary
t_group_k2_render
t_group_k2_target
t_group_k2_preflight
t_group_k2_tunnels
t_group_k2_evidence
t_group_k2_k3d
t_group_k2_promotion
t_group_k2_runs
t_group_k2_boundary
t_group_k5_render
t_group_k5_backend
t_group_k5_fleet
t_group_k7_multinode
t_group_tree

t_daemons_stop
printf '1..%s\n' "$T_NUMBER"
if [ -n "$T_SKIPPED" ]; then
  printf '# skipped, so not proven on this host:\n'
  printf '%s' "$T_SKIPPED" | sed 's/^/#/'
fi
if [ "$T_FAILED" = 0 ]; then
  printf '# %s assertions, all passed\n' "$T_NUMBER"
  exit "$PX_EXIT_OK"
fi
printf '# %s assertions, %s failed:\n' "$T_NUMBER" "$T_FAILED"
printf '%s' "$T_FAILED_NAMES" | while IFS= read -r T_LINE; do
  [ -z "$T_LINE" ] || printf '# %s\n' "$T_LINE"
done
exit "$PX_EXIT_FAIL"
