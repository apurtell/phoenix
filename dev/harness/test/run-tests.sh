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
# A primary goal of this suite is verifying the architectural boundary: no component outside
# lib/backend/compose.sh may ever directly invoke a container runtime or Kubernetes CLI tool.
# Because any single enforcement mechanism can be circumvented, verification is organized into
# three independent layers:
#   Layer 1: A static scan across every file that can be executed, sourced, or rendered. It checks
#            for runtime tool names and the backend engine variable. Every allowlist entry must
#            include a justification, and the scan fails if an allowlist entry becomes stale.
#   Layer 2: Verification of the runtime trap in lib/common.sh against the active function table
#            of processes that source the library.
#   Layer 3: Fake shims for the container runtime and each Kubernetes tool prepended to PATH.
#            Negative controls run entry points that must never invoke a runtime, a hostile driver
#            attempts to read the engine variable, and positive controls confirm that each fake
#            shim records invocations when called.
# The positive control validates that an empty log during negative controls is genuine evidence of
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
# container runtime client, and three Kubernetes tools. The shared library lib/common.sh installs
# a blocking trap function for each name.
T_TRAP_NAMES="docker docker-compose podman nerdctl kubectl k3d helm crictl"
T_RUNTIME_NAMES='docker|docker-compose|podman|nerdctl|kubectl|k3d|helm|crictl'
T_K8S_NAMES='kubectl|k3d|helm|crictl'

# Regular expressions for the Layer 1 static scan. The first pattern matches any restricted tool
# name appearing as a standalone word; occurrences preceded by a slash, dot, or hyphen belong to a
# filesystem path or compound identifier and are enforced by Layer 2 instead. The second pattern
# isolates the Kubernetes tools for requirement R-281. The third pattern detects internal engine
# variables of the Compose backend; referencing them allows a script to invoke the runtime by path
# and bypass shell function traps, so only the backend itself may reference them.
T_RUNTIME_RE='(^|[^[:alnum:]_./-])('"$T_RUNTIME_NAMES"')([^[:alnum:]_-]|$)'
T_K8S_RE='(^|[^[:alnum:]_./-])('"$T_K8S_NAMES"')([^[:alnum:]_-]|$)'
T_ENGINE_RE='(^|[^[:alnum:]_])(PX_DOCKER_BIN|_PXC_ENGINE)([^[:alnum:]_]|$)'
T_SCAN_RE="$T_RUNTIME_RE|$T_ENGINE_RE"

# Inline waiver marker honored by the Layer 1 static scan. Any source line carrying this directive
# must document why the token is permitted, allowing lib/common.sh to define the trap functions.
T_WAIVER='# px-allow-docker:'

# Invocation patterns checked by Layer 2 that would bypass shell function traps: explicit binary
# paths never resolve to shell functions, and the command, env, and exec builtins skip function
# lookup.
T_BYPASS_RE='(command|env|exec)[[:space:]]+('"$T_RUNTIME_NAMES"')([^[:alnum:]_-]|$)'
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

## @description  Prints command-line usage instructions for invoking this test runner.
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

## @description  Executes a command with the fake runtime first on PATH and set in PX_DOCKER_BIN.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_with_shim() {
  t_run env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" "$@"
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

## @description  Executes px-run under the fake runtime with a stub driver (name, argument...).
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_px_run() {
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 --scope px-selftest \
    --driver "$T_WORK/drivers/$1.sh" "${@:2}"
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

## @description  Resets the fake runtime to a single running RegionServer with restart count 0.
## @return       Reinitializes the fake runtime state files. Exit 0.
t_shim_state_reset() {
  rm -f "$T_SHIM_STATE"/*
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
    px-selftest-regionserver-1 running 'Up 3 minutes (healthy)' \
    phoenix-harness:selftest regionserver 1 0 >"$T_SHIM_STATE/instances.txt"
  printf '0\n' >"$T_SHIM_STATE/restart-count"
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
  cat >"$T_WORK/suites/order.suite" <<'SUITE_EOF'
suite: selftest-order
description: four tests whose declaration order is not their run order
timeout: 60

test: third
  needs: second
  action: exit 0

test: first
  action: exit 0

test: second
  needs: first
  action: exit 0

test: fourth
  needs: third
  action: exit 0
SUITE_EOF
  cat >"$T_WORK/suites/cycle.suite" <<'SUITE_EOF'
suite: selftest-cycle
timeout: 60

test: alpha
  needs: beta
  action: exit 0

test: beta
  needs: alpha
  action: exit 0
SUITE_EOF
  cat >"$T_WORK/suites/mixed.suite" <<'SUITE_EOF'
suite: selftest-mixed
timeout: 60

test: good
  action: exit 0

test: bad
  action: exit 1

test: harsh
  action: exit 2
SUITE_EOF
  cat >"$T_WORK/suites/trapped.suite" <<'SUITE_EOF'
suite: selftest-trapped
timeout: 60

test: blocked
  action: docker ps

test: blocked-kubectl
  action: kubectl get pods
SUITE_EOF
  cat >"$T_WORK/suites/skips.suite" <<'SUITE_EOF'
suite: selftest-skips
timeout: 60

test: skip-one
  action: exit 5

test: skip-two
  action: exit 5
SUITE_EOF
  cat >"$T_WORK/suites/severity.suite" <<'SUITE_EOF'
suite: selftest-severity
timeout: 60

test: unknown-severity
  action: printf 'chk\tFAILED\tbad\n' >>verdicts.tsv; exit 0
SUITE_EOF
  cat >"$T_WORK/suites/speak.suite" <<'SUITE_EOF'
suite: selftest-speak
timeout: 60

test: speak
  action: px_warn "hello from the action"; exit 0
SUITE_EOF
  # Spawn a background child process, record its PID, and wait so that the suite timeout expires
  # while the child process is still active.
  cat >"$T_WORK/suites/slow.suite" <<'SUITE_EOF'
suite: selftest-slow
timeout: 1

test: slow
  action: /bin/sleep 30 & printf '%s\n' "$!" >child.pid; wait
SUITE_EOF
  cat >"$T_WORK/suites/requires.suite" <<'SUITE_EOF'
suite: selftest-requires
timeout: 60

test: needs-yarn
  requires: yarn
  action: exit 0
SUITE_EOF
  # Because the driver path is absolute, this heredoc expands T_WORK while leaving globs, tildes,
  # and backslashes literal so they reach the suite parser unaltered.
  cat >"$T_WORK/suites/args.suite" <<SUITE_EOF
suite: selftest-args
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
  cat >"$T_WORK/drivers/exit-code.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver. It ends with the status its first argument names.
exit "${1:-0}"
DRIVER_EOF
  cat >"$T_WORK/drivers/bump-restart.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver. It raises the restart count the fake runtime reports, so the
# snapshot after the run differs from the snapshot before it.
printf '%s\n' "${1:-1}" >"$PX_FAKE_STATE_DIR/restart-count"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/exit-instance.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
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
# A stub driver. It removes the one instance of the fake runtime.
: >"$PX_FAKE_STATE_DIR/instances.txt"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/unreachable.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver. It makes the fake runtime stop answering, so the snapshot
# after the run cannot read the inventory.
: >"$PX_FAKE_STATE_DIR/unreachable"
exit 0
DRIVER_EOF
  cat >"$T_WORK/drivers/print-args.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
# A stub driver. It prints each argument between brackets, so an argument
# that was split, joined, expanded or lost shows in its output.
for driver_arg in "$@"; do
  printf '[%s]' "$driver_arg"
done
printf '\n'
DRIVER_EOF
  cat >"$T_WORK/drivers/engine-var.sh" <<'DRIVER_EOF'
#!/usr/bin/env bash
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
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf '%s\n' "$*" >>"$PX_FAKE_DOCKER_LOG"
fi
fake_state=${PX_FAKE_STATE_DIR:-/nonexistent}
fake_inv="$fake_state/instances.txt"
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
  local field=$1 key=$2 state=$3 status=$4 line value out=""
  [ -f "$fake_inv" ] || return 0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    fake_split "$line"
    value=$f_id
    [ "$field" = id ] || value=$f_service
    if [ "$value" = "$key" ]; then
      [ -n "$state" ] || continue
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

## @description  Scales the service of a --scale service=count argument, copying its first instance.
## @return       Exit 0.
fake_scale() {
  local spec="" service count line out="" top=0 template="" number
  while [ $# -gt 0 ]; do
    [ "$1" != --scale ] || spec=$2
    shift
  done
  service=${spec%%=*}
  count=${spec#*=}
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    fake_split "$line"
    if [ "$f_service" = "$service" ]; then
      [ -n "$template" ] || template=$line
      [ "$f_number" -le "$count" ] || continue
      [ "$f_number" -le "$top" ] || top=$f_number
    fi
    out="$out$line
"
  done <"$fake_inv"
  number=$((top + 1))
  while [ -n "$template" ] && [ "$number" -le "$count" ]; do
    fake_split "$template"
    f_id="${f_id%?}$number"
    f_name="${f_name%-*}-$number"
    f_number=$number
    f_state=running
    f_status='Up 1 second (healthy)'
    out="$out$(fake_join)
"
    number=$((number + 1))
  done
  printf '%s' "$out" >"$fake_inv"
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
      # Any other format is the host gate, which asks for the cluster label.
      printf '%s\n' "$fake_cluster"
      continue
      ;;
    esac
    [ "$fake_select" = "$fake_cluster" ] || continue
    [ -z "$fake_group" ] || [ "$f_group" = "$fake_group" ] || continue
    [ "$fake_running" = 0 ] || [ "$f_state" = running ] || continue
    printf '%s\n' "$fake_line"
  done <"$fake_inv"
  case "$fake_format" in
  '{{.ID}}|'*)
    [ ! -f "$fake_state/next.txt" ] || mv -f "$fake_state/next.txt" "$fake_inv"
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
    *RestartCount*) cat "$fake_state/restart-count" 2>/dev/null || printf '0\n' ;;
    *State.Status*)
      printf '%s|%s|%s|2026-01-01T00:00:00Z\n' "$f_state" "$fake_code" "$fake_oom"
      ;;
    *State.OOMKilled*) printf '%s|%s|\n' "$fake_oom" "$fake_code" ;;
    *Config.Image*) printf '%s|sha256:%s\n' "$f_image" "$fake_id" ;;
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
  for fake_id in "$@"; do
    fake_find "$fake_id" || exit 1
    printf '%s|1.50%%|128MiB / 1GiB|1.2kB / 3.4kB|5.6kB / 7.8kB\n' "$f_name"
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
  fake_rewrite id "$1" running 'Up 1 second (healthy)'
  ;;
logs)
  fake_id=${!#}
  printf 'fake log out %s\n' "$fake_id"
  printf 'fake log err %s\n' "$fake_id" >&2
  ;;
build)
  if [ -e "$fake_state/build-fails" ]; then
    printf 'fake: the build failed\n' >&2
    exit 1
  fi
  printf 'fake: built\n'
  ;;
image)
  if [ "${1:-}" = inspect ]; then
    printf 'sha256:f00dfeed0001\n'
  elif [ "${1:-}" = ls ] && [ ! -e "$fake_state/no-image" ]; then
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
events)
  if [ -e "$fake_state/no-events" ]; then
    printf 'fake: no events\n' >&2
    exit 1
  fi
  cat "$fake_state/events.txt" 2>/dev/null || true
  ;;
network)
  if [ "${1:-}" = ls ]; then
    cat "$fake_state/networks.txt" 2>/dev/null || true
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
  while [ $# -gt 0 ]; do
    case "$1" in
    -f | --project-directory | -p) shift 2 ;;
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
    case " $* " in
    *' --no-start '*) cp "$fake_state/provision.txt" "$fake_inv" ;;
    *' --scale '*) fake_scale "$@" ;;
    *)
      for fake_arg in "$@"; do
        case "$fake_arg" in
        -*) ;;
        *) fake_rewrite service "$fake_arg" running 'Up 1 second (healthy)' ;;
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
    while [ $# -gt 0 ]; do
      case "$1" in
      -T | --index=*) shift ;;
      *) break ;;
      esac
    done
    shift
    exec "$@"
    ;;
  cp)
    fake_target=${!#}
    case "$fake_target" in
    *:*) ;;
    *) printf 'fake copy\n' >"$fake_target" ;;
    esac
    ;;
  esac
  ;;
esac
exit 0
SHIM_EOF
  chmod +x "$T_SHIM_DIR/docker"
  for name in ${T_K8S_NAMES//|/ }; do
    cat >"$T_SHIM_DIR/$name" <<'SHIM_EOF'
#!/usr/bin/env bash
# A fixture that stands in for a Kubernetes tool. It records its name and its
# argument list in the log of the fake runtime.
if [ -n "${PX_FAKE_DOCKER_LOG:-}" ]; then
  printf '%s %s\n' "${0##*/}" "$*" >>"$PX_FAKE_DOCKER_LOG"
fi
exit 0
SHIM_EOF
    chmod +x "$T_SHIM_DIR/$name"
  done
  t_shim_state_reset
}

## @description  Materializes the sweep cluster deployment directory, instances, and event records.
## @return       Populates the sweep cluster deployment and runtime state. Exit 0.
t_fixtures_sweep() {
  T_SWEEP_STATE="$T_WORK/sweep-state"
  T_SWEEP_OUT="$T_WORK/sweep-out"
  T_DEPLOY="$T_WORK/deploy"
  mkdir -p "$T_SWEEP_STATE" "$T_SWEEP_OUT" "$T_DEPLOY" "$T_WORK/no-deploy"
  printf 'sweep\n' >"$T_SWEEP_STATE/cluster"
  printf 'px-sweep_default\n' >"$T_SWEEP_STATE/networks.txt"
  printf 'px-sweep_data\n' >"$T_SWEEP_STATE/volumes.txt"
  cat >"$T_SWEEP_STATE/provision.txt" <<'SWEEP_EOF'
ms01|master|px-sweep-master-1|created|Created|px:selftest|master|1|0|||
rs01|regionserver|px-sweep-regionserver-1|created|Created|px:selftest|regionserver|1||||regionmover
rs02|regionserver|px-sweep-regionserver-2|created|Created|px:selftest|regionserver|2||||regionmover
nm01|nodemanager|px-sweep-nodemanager-1|created|Created|px:selftest|nodemanager|1||yarn|on-demand|
SWEEP_EOF
  # Instance indices are derived from the explicit index label first, falling back to the
  # container number.
  cat >"$T_SWEEP_STATE/events.txt" <<'SWEEP_EOF'
1700000000000000000|regionserver||2|container|die|px-sweep-regionserver-2
1700000000500000000|master|0||container|start|px-sweep-master-1
1700000000750000000|regionserver||2|container|kill|px-sweep-regionserver-2
1700000001000000000||||network|connect|px-sweep_default
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
  printf 'PX_CONTRACT_VERSION=2\n' >"$T_WORK/env/contract.env"
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
# list, and it writes one jar for each module of -pl, so a build record names
# files that exist. Without -pl it writes the product that the case names.
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
  phoenix-server) jar="phoenix-server-hbase-$profile.jar" ;;
  */phoenix-client-embedded) jar="phoenix-client-embedded-hbase-$profile.jar" ;;
  *) jar="${module##*/}-5.4.0-SNAPSHOT.jar" ;;
  esac
  mkdir -p "$tree/$module/target"
  printf 'fake jar %s\n' "$module" >"$tree/$module/target/$jar"
done
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

  # Build the release archives served by the fake fetch layer, each containing a single top-level
  # directory. The jHiccup source archive includes its POM. Each valid HBase archive contains its
  # bundled ZooKeeper and Hadoop client JARs alongside look-alike JARs that must not match, while
  # HBase 2.6.5 intentionally omits ZooKeeper and bundles two conflicting Hadoop client JARs.
  mkdir -p "$T_A2/dist-src/jHiccup-2.0.10"
  printf '<project/>\n' >"$T_A2/dist-src/jHiccup-2.0.10/pom.xml"
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
  t_fixtures_suites
  t_fixtures_drivers
  t_fixtures_shim
  t_fixtures_sweep
  t_fixtures_config
  t_fixtures_scan
  t_fixtures_probes
  t_fixtures_a2
}

# ---------------------------------------------------------------------------
# Layer 1: Static source scan and allowlist enforcement
# ---------------------------------------------------------------------------

## @description  Lists the paths permitted to hold a runtime token, each with its mandatory reason.
## @return       Writes path and reason TSV rows to stdout. Exit 0.
t_allowlist() {
  px_tsv_row lib/backend/compose.sh "the backend, the only runtime caller"
  px_tsv_row test/run-tests.sh "generates the fake runtime shim from a heredoc"
}

## @description  Checks whether the Layer 1 allowlist covers a harness-relative file path.
## @return       Exit 0 when the allowlist covers the path; 1 otherwise.
t_allowlisted() {
  local row
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    if [ "${row%%"$PX_TAB"*}" = "$1" ]; then
      return 0
    fi
  done <<EOF
$(t_allowlist)
EOF
  return 1
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
  local root=$1 expression=${2:-$T_SCAN_RE} file rel hit text stripped
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
      t_allowlisted "$rel" && continue
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
  local root=$1 row path
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    path=${row%%"$PX_TAB"*}
    if [ ! -f "$root/$path" ]; then
      printf '%s: no such file, remove the allowlist entry\n' "$path"
      continue
    fi
    if ! grep -qE "$T_SCAN_RE" "$root/$path" 2>/dev/null; then
      printf '%s: no runtime token left, remove the allowlist entry\n' "$path"
    fi
  done <<EOF
$(t_allowlist)
EOF
}

## @description  Identifies any allowlist entry that lacks a mandatory justification reason.
## @return       Writes one path per unreasoned entry to stdout. Exit 0.
t_allowlist_unreasoned() {
  local row path reason
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    path=${row%%"$PX_TAB"*}
    reason=${row#*"$PX_TAB"}
    if [ -z "$reason" ] || [ "$reason" = "$path" ]; then
      printf '%s\n' "$path"
    fi
  done <<EOF
$(t_allowlist)
EOF
}

## @description  Reports runtime calls outside the backend using builtins, absolute paths, or PATH.
## @return       Writes one path:line:text record per violation to stdout. Exit 0.
t_scan_bypass() {
  local file rel hit
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    t_in_scope "$file" || continue
    rel=${file#"$PX_HOME"/}
    [ "$rel" != lib/backend/compose.sh ] || continue
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      printf '%s:%s\n' "$rel" "$hit"
    done <<EOF
$(grep -nE "$T_BYPASS_RE" "$file" 2>/dev/null || true)
EOF
  done <<EOF
$(find "$PX_HOME" -type f 2>/dev/null | LC_ALL=C sort)
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
  t_run grep -qi harness "$T_DEV/README.md"
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
  t_empty allowlist_entries_carry_a_reason "$(t_allowlist_unreasoned)"
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
  t_equals trap_blocks_eight_tool_names "$(printf '%s 2\n' "${names[@]}")" "$T_STDOUT"
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
  # Suite test actions execute in isolated child shells and must still inherit the runtime traps
  # through BASH_ENV.
  t_suite selftest-trapped --continue-on-failure "$T_WORK/suites/trapped.suite"
  t_equals trap_reaches_a_suite_action 3 "$T_STATUS"
  log="$PX_OUT/sessions/selftest-trapped/tests"
  t_contains trap_reaches_a_suite_action_message 'container runtime call blocked' \
    "$(cat "$log/blocked/stderr.log" 2>/dev/null)"
  t_contains trap_blocks_kubectl_in_a_suite_action 'call blocked: kubectl get pods' \
    "$(cat "$log/blocked-kubectl/stderr.log" 2>/dev/null)"
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
  t_negative_control cluster_validate "$T_BIN/px-cluster" validate --no-backend
  t_negative_control suite_dry_run "$T_BIN/px-suite" --dry-run \
    "$T_WORK/suites/order.suite"
  t_negative_control run_dry_run "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run
  t_negative_control tool_doctor "$T_BIN/px-tool" doctor --config-only
  t_negative_control collect_list "$T_BIN/px-collect" list
  t_negative_control collect_help "$T_BIN/px-collect" start --help
  t_negative_control backend_bind "$T_WORK/probe/sb.sh" sb_bound_backend
  t_negative_control cluster_down_dry_run "$T_BIN/px-cluster" --dry-run down \
    -e PX_SCOPE_ID=px-selftest
  # Execute a positive control through the substrate contract to confirm that a real backend call
  # records the scope label selector in the shim log, validating the negative controls above.
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
    "$(cat "$T_STDOUT/driver.log" 2>/dev/null)"
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
  t_equals contract_declares_30_required_verbs 30 \
    "$(sb_contract_required | grep -c .)"
  t_equals contract_declares_5_optional_verbs 5 \
    "$(sb_contract_optional | grep -c .)"
  t_equals contract_version_is_one 1 "$PX_CONTRACT_VERSION"
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

## @description  Validates the twelve entry points for help, usage errors, and lib/common.sh use.
## @return       Records assertions. Exit 0.
t_group_commands() {
  local name missing="" help="" flag="" unsourced="" own=""
  printf '# the twelve entry points\n'
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
  t_empty twelve_entry_points_are_present "$missing"
  t_empty every_entry_point_answers_help "$help"
  t_empty every_entry_point_refuses_a_bad_flag "$flag"
  t_empty bins_source_common "$unsourced"
  t_run grep -q 'common\.sh' "$PX_HOME/lib/backend/contract.sh"
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
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_BIN/px-suite" --dry-run "$suite/order.suite"
  t_empty suite_dry_run_makes_no_session "$(find "$PX_OUT/sessions" -name 'plan.tsv' \
    -newer "$T_SHIM_LOG" 2>/dev/null)"
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
    "$(cat "$run_dir/driver.log" 2>/dev/null)"
  t_suite selftest-skips "$suite/skips.suite"
  t_equals suite_all_skip_exits_one 1 "$T_STATUS"
  t_contains suite_all_skip_is_inconclusive "result${PX_TAB}INCONCLUSIVE" \
    "$(cat "$session/selftest-skips/summary.tsv" 2>/dev/null)"
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
  t_suite selftest-speak "$suite/speak.suite"
  t_equals suite_speak_runs 0 "$T_STATUS"
  t_contains suite_log_holds_the_message_of_an_action 'hello from the action' \
    "$(cat "$session/selftest-speak/suite.log" 2>/dev/null)"
  # R-037: An operator-specified run class overrides the class declared by the suite, and px-run
  # records the run class that it executed with.
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
  # Only a GNU timeout binary terminates the entire process group of a test, whereas the fallback
  # terminates the test process alone.
  if [ -n "$PX_TIMEOUT_BIN" ]; then
    t_suite selftest-slow "$suite/slow.suite"
    t_equals suite_slow_test_expires 1 "$T_STATUS"
    t_suite_child_gone suite_time_limit_ends_the_child_of_a_test \
      "$session/selftest-slow/tests/slow/child.pid"
  fi
}

## @description  Verifies that a test process (name, pid file) terminates within one second.
## @return       Records one assertion. Exit 0.
t_suite_child_gone() {
  local name=$1 pid="" tries=0
  read -r pid <"$2" 2>/dev/null || true
  if [ -z "$pid" ]; then
    t_fail "$name" "no process identifier in $2"
    return 0
  fi
  while kill -s 0 "$pid" 2>/dev/null && [ "$tries" -lt 10 ]; do
    sleep 0.1
    tries=$((tries + 1))
  done
  if kill -s 0 "$pid" 2>/dev/null; then
    kill -s KILL "$pid" 2>/dev/null || true
    t_fail "$name" "process $pid outlived the time limit of its test"
  else
    t_pass "$name" "process $pid is gone"
  fi
}

## @description  Validates run wrapper status pass-through, anomaly detection, and inventory reads.
## @return       Records assertions. Exit 0.
t_group_run() {
  local code record
  printf '# the run wrapper\n'
  t_shim_state_reset
  for code in 0 1 2 3; do
    t_run "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
      --driver "$T_WORK/drivers/exit-code.sh" -- "$code"
    t_equals "run_propagates_driver_exit_$code" "$code" "$T_STATUS"
  done
  # Running without an active cluster scope is a supported mode rather than an error, so the
  # failure trap must remain silent.
  t_run env -u PX_SCOPE_ID "$T_BASH" "$T_BIN/px-run" \
    --driver "$T_WORK/drivers/exit-code.sh" -- 0
  t_equals run_without_a_cluster 0 "$T_STATUS"
  t_absent run_without_a_cluster_reports_no_error 'ERROR' "$T_STDERR"
  # Have the stub driver increment the fake runtime's reported restart count so the post-run
  # snapshot diverges from the pre-run baseline deterministically without timing dependencies.
  printf '0\n' >"$T_SHIM_STATE/restart-count"
  t_px_run bump-restart -- 4
  t_equals run_reports_an_unplanned_restart 3 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  t_contains run_records_the_unplanned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}4${PX_TAB}unplanned" \
    "$(grep '^diff' "$record" 2>/dev/null)"
  t_shim_state_reset
  t_px_run bump-restart --disrupt 'phase:driver_start,kill,regionserver,0' -- 4
  t_equals run_accepts_a_planned_restart 0 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  t_contains run_records_the_planned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}4${PX_TAB}planned" \
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
  # PX_CONTRACT_VERSION is an immutable harness constant; neither config files nor CLI overrides
  # may alter the contract revision enforced by sb_load.
  t_run "$T_BASH" "$T_BIN/px-cluster" --config "$env_dir/contract.env" config
  t_equals config_file_cannot_set_the_contract_version 2 "$T_STATUS"
  t_contains config_names_the_constant 'is a constant of the harness' "$T_STDERR"
  t_run "$T_BASH" "$T_BIN/px-cluster" -e PX_CONTRACT_VERSION=2 config
  t_equals config_command_line_cannot_set_the_contract_version 2 "$T_STATUS"
  # Verify that PX_LOG_FILE adheres to the output boundary policy: running px-cluster status
  # without an active cluster emits a diagnostic after config loading, which is mirrored to an
  # allowed log path under PX_OUT while paths outside PX_OUT or inside the tree are rejected.
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

## @description  Sweeps backend identity, capabilities, scope verbs, provisioning, and inventory.
## @return       Records assertions. Exit 0.
t_group_sweep_scope() {
  local rows
  printf '# verb sweep: identity, scope, provision and inventory\n'
  t_shim_log_reset
  t_sb sb_backend_id
  t_outcome sweep_sb_backend_id 0 compose
  t_sb sb_contract_version
  t_outcome sweep_sb_contract_version 0 1
  t_sb sb_version_info
  t_outcome sweep_sb_version_info 0 "$(t_rows 2 engine_client 29.9.3 \
    engine_server 29.9.3 compose_version 2.40.0)"
  t_sb sb_capabilities
  t_outcome_columns sweep_sb_capabilities 0 2 "$(t_rows 2 logs_previous no \
    resource_sampling yes signal_delivery yes previous_termination no events yes \
    drain_restart yes port_forward no scale yes group_switch yes oom_evidence yes \
    image_digest no node_placement no)"
  t_sb_codes sweep_scope_refusals "
2 sb_scope_args
2 sb_scope_create Bad
2 sb_scope_exists not-a-scope
6 sb_scope_exists px-sweep
6 sb_scope_args px-sweep"
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
  t_sb sb_scope_args px-sweep
  t_outcome sweep_sb_scope_args 0 "$(t_rows 2 project px-sweep \
    label_selector px.harness.cluster=sweep compose_file "$T_DEPLOY/compose.yaml" \
    network px-sweep_default)"
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
  printf '# verb sweep: evidence, signal and drain-aware restart\n'
  t_sb sb_image_ref px-sweep master 0
  t_outcome sweep_sb_image_ref 0 "$(t_rows 2 px:selftest sha256:ms01)"
  t_sb sb_restart_count px-sweep master 0
  t_outcome sweep_sb_restart_count 0 0
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
6 sb_restart_count px-sweep master 9
6 sb_oom_killed px-sweep master 9
6 sb_image_ref px-sweep master 9"
  : >"$T_SWEEP_STATE/inspect-fails"
  t_sb_codes sweep_inspection_failure_is_failure "
1 sb_restart_count px-sweep master 0
1 sb_termination_reason px-sweep master 0
1 sb_oom_killed px-sweep master 0
1 sb_image_ref px-sweep master 0"
  rm -f "$T_SWEEP_STATE/inspect-fails"
  t_shim_log_reset
  t_sb sb_signal px-sweep master 0 QUIT
  t_outcome sweep_sb_signal 0 ""
  t_called sweep_signal_names_the_instance 'kill -s QUIT ms01'
  t_sb_codes sweep_signal_refusals "
2 sb_signal px-sweep master 0 BOGUS
6 sb_signal px-sweep master 9 TERM"
  # A capability probe during a runtime outage fails without caching the result, allowing signal
  # delivery to succeed once the runtime responds again.
  rm -f "$T_SWEEP_OUT/state/compose/capabilities.tsv"
  : >"$T_SWEEP_STATE/unreachable"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  t_sb sb_capabilities
  rm -f "$T_SWEEP_STATE/unreachable"
  t_equals sweep_capabilities_do_not_cache_an_outage no \
    "$(t_exists "$T_SWEEP_OUT/state/compose/capabilities.tsv")"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_after_an_outage 0 "$T_STATUS"
  t_sb sb_capabilities
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
  printf '# verb sweep: scale, kill, events, samples and preflight\n'
  t_shim_log_reset
  t_sb sb_scale px-sweep regionserver 3 10
  t_outcome_columns sweep_sb_scale 0 4 "$(t_rows 4 regionserver 0 rs01 running \
    regionserver 1 rs02 running regionserver 2 rs03 running)"
  t_called sweep_scale_names_the_service_operand "compose -p px-sweep up -d \
--no-recreate --no-build --no-deps --scale regionserver=3 regionserver"
  t_sb_codes sweep_scale_refusals "
2 sb_scale px-sweep regionserver 10 10
2 sb_scale px-sweep nosuch 2 10
2 sb_scale px-sweep regionserver 2 0"
  t_shim_log_reset
  t_sb sb_kill px-sweep regionserver 2 hard
  t_outcome sweep_sb_kill_hard 0 ""
  t_called sweep_kill_hard_sends_kill 'kill -s KILL rs03'
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
  t_sb sb_events px-sweep 60
  t_outcome sweep_sb_events_derive_the_index 0 "$(t_rows 6 \
    1700000000000 regionserver 1 container die px-sweep-regionserver-2 \
    1700000000500 master 0 container start px-sweep-master-1 \
    1700000000750 regionserver 1 container kill px-sweep-regionserver-2 \
    1700000001000 '' '' network connect px-sweep_default)"
  t_sb sb_sample_resources px-sweep
  t_equals sweep_sb_sample_resources 0 "$T_STATUS"
  t_contains sweep_sample_converts_the_units \
    "$(t_rows 9 master 0 1.50 134217728 1073741824 1200 3400 5600 7800)" \
    "$(printf '%s\n' "$T_STDOUT" | cut -f 2-)"
  : >"$T_SWEEP_STATE/no-events"
  : >"$T_SWEEP_STATE/no-stats"
  t_sb_codes sweep_observation_refusals "
2 sb_events px-sweep soon
5 sb_events px-sweep 60
5 sb_sample_resources px-sweep"
  rm -f "$T_SWEEP_STATE/no-events" "$T_SWEEP_STATE/no-stats"
  t_sb sb_preflight px-sweep
  t_outcome_columns sweep_sb_preflight 0 2 "$(t_rows 2 engine_binary ok \
    engine_responsive ok engine_version ok engine_credentials ok \
    compose_version ok engine_memory ok port_availability ok)"
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
1 sb_scale px-sweep regionserver 2 5
1 sb_kill px-sweep master 0 hard
1 sb_signal px-sweep master 0 TERM
1 sb_logs px-sweep master 0 - 0 0
1 sb_copy_in px-sweep master 0 /etc/hosts /tmp/x
1 sb_copy_out px-sweep master 0 /etc/hosts copies/x
1 sb_endpoint px-sweep master 0 ui
1 sb_restart_count px-sweep master 0
1 sb_termination_reason px-sweep master 0
1 sb_oom_killed px-sweep master 0
1 sb_image_ref px-sweep master 0
1 sb_restart_drain px-sweep regionserver 0 10 5
1 sb_teardown px-sweep 0
125 sb_exec px-sweep master 0 30 -- true
5 sb_events px-sweep 60
5 sb_sample_resources px-sweep"
  # When an unrecorded scope is queried while the runtime is unreachable, commands must report the
  # runtime failure (exit 1) because scope absence cannot be confirmed.
  t_sweep "$T_BASH" "$T_BIN/px-tool" --scope px-other shell --role regionserver \
    --index 0 -- true
  t_equals sweep_tool_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  t_sweep "$T_BASH" "$T_BIN/px-collect" --scope px-other --duration 1 \
    --out "$T_SWEEP_OUT/collect"
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
  t_shim_log_reset
  t_sb sb_scope_delete px-sweep
  t_outcome sweep_sb_scope_delete 0 ""
  t_called sweep_scope_delete_removes_the_network 'network rm px-sweep_default'
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
  # Before a scope record exists, px-cluster validate must load the generated port map directly
  # from the deployment directory argument.
  t_pc validate "$T_DEPLOY"
  t_contains cluster_validate_loads_the_port_map_of_its_directory \
    'port_availability ok' "$(t_squeeze "$T_STDOUT")"
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
  # A subsequent CLI invocation in a separate process must discover the active cluster scope via
  # the persisted scope record.
  t_sweep "$T_BASH" "$T_BIN/px-run" --driver "$T_WORK/drivers/exit-code.sh" -- 0
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
  t_pc status --group yarn
  text="$(t_squeeze "$T_STDOUT")"
  t_equals cluster_status 0 "$T_STATUS"
  t_contains cluster_status_reports_an_instance \
    'regionserver 1 rs02 running ready px:selftest localhost' "$text"
  t_contains cluster_status_reports_a_group 'yarn 0 1 down' "$text"
  t_pc ready --timeout 5
  t_equals cluster_ready 0 "$T_STATUS"
  t_contains cluster_ready_reports_the_stopped_group 'nodemanager 0 stopped' \
    "$(t_squeeze "$T_STDOUT")"
  t_pc ready --timeout 1 nosuch
  t_equals cluster_ready_on_an_unknown_role_is_no_such_object 6 "$T_STATUS"
  t_pc config
  t_equals cluster_config 0 "$T_STATUS"
  t_contains cluster_config_reports_the_cluster "PX_CLUSTER_ID${PX_TAB}sweep" "$T_STDOUT"
  t_pc validate
  t_equals cluster_validate 0 "$T_STATUS"
  t_contains cluster_validate_runs_the_preflight 'engine_binary ok' \
    "$(t_squeeze "$T_STDOUT")"
  t_pc logs regionserver 0
  t_outcome cluster_logs 0 "$(printf 'fake log out rs01\nfake log err rs01')"
  t_pc logs --previous regionserver 0
  t_equals cluster_logs_previous_is_unsupported 5 "$T_STATUS"
  t_pc exec regionserver 0 -- sh -c 'printf "%s\n" cluster-exec; exit 7'
  t_outcome cluster_exec_passes_the_status_through 7 cluster-exec
  t_equals cli_exec_status_passes_without_exit_line 0 "$(printf '%s\n' "$T_STDERR" |
    grep -c 'ends (exit ')"
  t_pc cp-in regionserver 0 "$T_DEPLOY/compose.yaml" /tmp/staged
  t_equals cluster_cp_in 0 "$T_STATUS"
  t_pc cp-out regionserver 0 /etc/hosts cluster/hosts
  t_equals cluster_cp_out 0 "$T_STATUS"
  t_equals cluster_cp_out_lands_under_the_output_root 'fake copy' \
    "$(cat "$T_SWEEP_OUT/cluster/hosts" 2>/dev/null)"
  t_pc cp-out regionserver 0 /etc/hosts /tmp/px-selftest-escape
  t_equals cluster_cp_out_refuses_an_escape 2 "$T_STATUS"
  t_pc endpoint master 0 ui
  t_outcome cluster_endpoint 0 "$(t_rows 2 127.0.0.1 1)"
  t_pc endpoint master 0 nosuch
  t_equals cluster_endpoint_unknown_port_is_no_such_object 6 "$T_STATUS"
  t_pc endpoint --release master 0 ui
  t_equals cluster_endpoint_release 0 "$T_STATUS"
  t_pc version
  text="$(t_squeeze "$T_STDOUT")"
  t_equals cluster_version 0 "$T_STATUS"
  t_contains cluster_version_reports_the_contract 'harness_contract 1' "$text"
  t_contains cluster_version_reports_the_backend 'backend compose' "$text"
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
  t_pc scale regionserver 3 --timeout 10
  t_equals cluster_scale 0 "$T_STATUS"
  t_contains cluster_scale_reports_the_new_instance \
    'regionserver 2 rs03 running ready' "$(t_squeeze "$T_STDOUT")"
  t_contains cluster_scale_is_planned "$(t_rows 4 scale regionserver '' 'count 3')" \
    "$(t_sweep_planned)"
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
    t_pass no_write_under_the_repository_root "this harness sits in no working tree"
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
  t_contains setup_is_gated_by_its_healthcheck 'healthcheck:' \
    "$(sed -n '/^  setup-0:/,/^  [a-z]/p' "$dir/compose.yaml")"
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
  t_empty fail_closed_leaves_no_staging_directory \
    "$(find "$T_A2_OUT" -name '.*.render.*' 2>/dev/null)"
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
  t_contains heap_fits_container_names_the_role 'regionserver' "$T_STDERR"
  # Verify that the client role renders the YARN configuration files only when YARN is enabled in
  # the deployment.
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
  local pf="$PX_HOME/scripts/preflight.sh" hand="$T_A2/hand" row status_seen=""
  printf '# the host preflight\n'
  printf '25167863808\n' >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=m "$T_BASH" "$pf" --phase memory
  status_seen="$status_seen $T_STATUS"
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
  status_seen="$status_seen $T_STATUS"
  t_equals preflight_admits_validation 0 "$T_STATUS"
  t_contains preflight_admits_validation_with_the_sum "memory_fit${PX_TAB}ok${PX_TAB}the limits \
8704 MiB" "$T_STDOUT"
  t_gen --class measurement --cluster o --config "$T_ENVD/override-24gib.env"
  t_a2 PX_CLUSTER_ID=o "$T_BASH" "$pf" --phase memory
  status_seen="$status_seen $T_STATUS"
  t_equals preflight_admits_override_24gib 0 "$T_STATUS"
  t_contains preflight_admits_override_24gib_with_margin '705 MiB (0.69 GiB) to spare' \
    "$T_STDOUT"
  # Verify the memory_check boundary: admit a topology that sits 1 GiB below the available budget
  # and reject one that exceeds the budget by 1 GiB.
  printf '%s\n' "$(((8704 + 4096 + 1024) * 1048576))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory
  status_seen="$status_seen $T_STATUS"
  t_equals memory_check_passes_below_the_budget "0 yes" \
    "$T_STATUS $(t_flag '1024 MiB (1.00 GiB) to spare' "$T_STDOUT")"
  printf '%s\n' "$(((8704 + 4096 - 1024) * 1048576))" >"$T_A2_STATE/mem-total"
  t_a2 PX_CLUSTER_ID=v "$T_BASH" "$pf" --phase memory
  status_seen="$status_seen $T_STATUS"
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
  status_seen="$status_seen $T_STATUS"
  t_equals preflight_heap_bound 3 "$T_STATUS"
  t_contains preflight_heap_bound_names_the_role "heap_bound${PX_TAB}fail${PX_TAB}regionserver: \
the heap of 5000 MiB is not below the container limit of 3072 MiB" "$T_STDOUT"
  sed 's/"5000"/"2048"/' "$hand/heap/compose.yaml" >"$hand/heap/c2" &&
    mv "$hand/heap/c2" "$hand/heap/compose.yaml"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/heap"
  t_absent preflight_heap_below_the_limit_passes 'heap_bound' "$T_STDOUT"
  printf 'services:\n  x:\n    image: "y"\n' >"$hand/nolimit/compose.yaml"
  t_a2 "$T_BASH" "$pf" --phase memory --deployment "$hand/nolimit"
  status_seen="$status_seen $T_STATUS"
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
  status_seen="$status_seen $T_STATUS"
  t_equals preflight_fast_phase 0 "$T_STATUS"
  t_contains preflight_fast_phase_reports_the_ports "port_availability${PX_TAB}ok" "$T_STDOUT"
  t_contains preflight_fast_phase_reports_the_engine "engine_memory${PX_TAB}ok" "$T_STDOUT"
  t_a2 "$T_BASH" "$pf" --deployment "$hand/fast"
  t_equals preflight_runs_both_phases "0 yes yes" \
    "$T_STATUS $(t_flag engine_binary "$T_STDOUT") $(t_flag memory_fit "$T_STDOUT")"
  : >"$T_A2_STATE/unreachable"
  t_a2 "$T_BASH" "$pf" --phase fast --deployment "$hand/fast"
  status_seen="$status_seen $T_STATUS"
  t_equals preflight_fast_phase_refuses_a_silent_engine 3 "$T_STATUS"
  rm -f "$T_A2_STATE/unreachable"
  t_a2 "$T_BASH" "$pf" --phase slow
  t_equals preflight_unknown_phase 2 "$T_STATUS"
  t_a2 "$T_BASH" "$pf" --bogus
  t_equals preflight_unknown_option 2 "$T_STATUS"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pf" -n --deployment "$T_A2_OUT/deploy/m"
  t_equals preflight_dry_run "0 yes" "$T_STATUS $(t_flag 'limits sum to 41984 MiB' "$T_STDOUT")"
  t_empty preflight_dry_run_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  t_absent preflight_never_returns_4 ' 4' "$status_seen"
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
  t_contains tofu_logs_the_hash 'trust on first use' "$T_STDERR"
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
  bad="$(grep -rn 'http://' "$PX_HOME/scripts" "$PX_HOME/$PX_DEPLOY_SUBDIR" 2>/dev/null |
    grep -v ':[[:space:]]*#' | grep -v 'www.apache.org/licenses' |
    grep -vE "http://(127\\.0\\.0\\.1|\\\$host)")"
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
  t_contains phoenix_jars_come_from_the_build_record \
    'for SA_KEY in server_jar client_jar pherf_jar distributed_jar' "$(cat "$sa")"
  # Verify build-phoenix.sh (R-028): the selected HBase profile is propagated to the Maven command
  # line.
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_dry_run 0 "$T_STATUS"
  t_contains build_phoenix_dry_run_names_the_jdk "JAVA_HOME=$jdk" "$T_STDOUT"
  t_contains build_phoenix_dry_run_names_the_modules \
    '-pl phoenix-server,phoenix-client-parent/phoenix-client-embedded,phoenix-pherf -am' \
    "${T_STDOUT//\\/}"
  t_contains build_phoenix_dry_run_selects_the_default_profile '-Dhbase.profile=2.6 -DskipTests' \
    "$T_STDOUT"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.5 "$T_BASH" "$bp" --tree "$tree" -n
  t_contains build_phoenix_profile_reaches_maven '-Dhbase.profile=2.5 -DskipTests' "$T_STDOUT"
  t_empty build_phoenix_dry_run_runs_no_maven "$(cat "$T_A2_MVN_LOG")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.7 "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_refuses_an_unknown_profile 2 "$T_STATUS"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_PHOENIX_HBASE_PROFILE=2.6.3 "$T_BASH" "$bp" --tree "$tree" -n
  t_equals build_phoenix_refuses_a_pom_with_another_module "1 yes" \
    "$T_STATUS $(t_flag 'Bring the table up to date' "$T_STDERR")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" "$T_BASH" "$bp" --tree "$tree"
  t_equals build_phoenix_builds_and_records 0 "$T_STATUS"
  t_contains build_phoenix_runs_maven_with_the_jdk "JAVA_HOME=$jdk " "$(cat "$T_A2_MVN_LOG")"
  t_equals build_phoenix_records_the_profile "5.4.0-SNAPSHOT 2.6 2.6.4 2.6.5-hadoop3 3.4.2 17" \
    "$(t_build_record phoenix_version hbase_profile compat_module hbase_version hadoop_version \
      jdk)"
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
    dist/async-profiler-4.1-linux-arm64.tar.gz phoenix/phoenix-server-hbase-2.6.jar \
    phoenix/phoenix-client-embedded-hbase-2.6.jar phoenix/phoenix-pherf-5.4.0-SNAPSHOT.jar; do
    [ -f "$ctx/$line" ] || bad="$bad $line"
  done
  t_empty stage_artifacts_context_holds_every_part "$bad"
  # Verify that the bundled ZooKeeper and Hadoop client versions recorded during staging are
  # extracted from the actual jar entries inside the HBase archive rather than matched from
  # similarly named files.
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
  t_contains image_build_names_the_context "--file $ctx/Dockerfile $ctx" "$text"
  t_absent image_build_defaults_to_the_host_architecture '--platform' "$text"
  # Verify that rendering a deployment for a staged image records the exact jar versions carried by
  # that image, and that a staging record produced for a different version set causes the generator
  # to refuse the render.
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
  t_contains build_jhiccup_pins_its_source "PX_DIST_JHICCUP_2_0_10_SHA512_ARM64=" "$(cat "$pins")"
  t_a2 "PX_BUILD_JAVA_HOME=$jdk" PX_FAKE_MVN_PRODUCT=target/jHiccup.jar \
    "PX_DIST_JHICCUP_2_0_10_SHA512_ARM64=$(printf 'b%.0s' $(seq 1 128))" "$T_BASH" \
    "$PX_HOME/scripts/build-jhiccup.sh"
  t_equals build_jhiccup_verifies_a_declared_pin 1 "$T_STATUS"
}

## @description  Verifies that each cross-platform helper produces identical results when isolated
##               to each supported underlying system utility in turn.
## @return       Records the platform fallback assertions. Exit 0.
t_group_a2_platform() {
  local file="$T_A2/dist-src/hbase-2.6.5.tar.gz" want tool dir bad="" tried="" path name
  printf '# platform fallbacks\n'
  want="$(px_sha512 "$file")"
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
  t_nonempty platform_fallbacks_tried_a_program "$tried"
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
    'scripts/build-phoenix.sh builds the Phoenix jars, then scripts/stage-artifacts.sh' \
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
  t_equals missing_image_refuses_the_bring_up "2 yes yes" "$T_STATUS $(t_flag \
    'scripts/build-phoenix.sh, then' "$T_STDERR") $(t_flag \
    'scripts/stage-artifacts.sh --tag px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6' \
    "$T_STDERR")"
  t_absent missing_image_runs_no_preflight 'memory_fit' "$T_STDERR"
  t_absent missing_image_creates_nothing 'up --no-start' "$(cat "$T_SHIM_LOG")"
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation --config "$T_A2/topology.env" up \
    --timeout 5
  t_equals single_bring_up_from_class_and_topology 0 "$T_STATUS"
  t_contains single_bring_up_runs_the_preflight "memory_fit${PX_TAB}ok" "$T_STDERR"
  t_contains single_bring_up_provisions_the_render "compose -p px-a2 -f $dir/compose.yaml" \
    "$(cat "$T_SHIM_LOG")"
  t_equals single_bring_up_records_the_deployment "$dir" \
    "$(cat "$T_A2_OUT/state/deployment-compose-a2" 2>/dev/null)"
  # Because rendering overwrites the files in the target deployment directory, neither a second
  # bring-up nor a direct render call may modify the deployment directory of a cluster that is up.
  t_shim_log_reset
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation up
  t_equals cluster_up_refuses_to_render_over_a_cluster_that_is_up "2 yes" \
    "$T_STATUS $(t_flag 'run px-cluster down first' "$T_STDERR")"
  t_a2 "$T_BASH" "$pc" --cluster a2 --class validation render
  t_equals cluster_render_refuses_the_deployment_of_a_cluster_that_is_up 2 "$T_STATUS"
  t_empty cluster_refused_render_calls_no_runtime "$(cat "$T_SHIM_LOG")"
  t_a2 "$T_BASH" "$pc" --cluster a2 scale datanode 2
  t_equals scale_refuses_a_role_with_a_service_per_instance 2 "$T_STATUS"
  t_contains scale_names_the_render_as_the_remedy 'Render the deployment with the new count' \
    "$T_STDERR"
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

## @description  Verifies end-to-end version matrix execution: running a test suite sequentially
##               across two distinct component version sets.
## @return       Records the version matrix assertions. Exit 0.
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
  printf 'suite: selftest-matrix\ntimeout: 60\n\ntest: one\n  action: exit 0\n' >"$suite"
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
  t_equals matrix_topology_records_the_last_set "2.5 2.5.15 3.4.2 3.8.4 3.3.6" \
    "$(t_env_value "$T_A2_OUT/deploy/a2/topology.env" PX_PHOENIX_HBASE_PROFILE \
      PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION)"
  t_contains matrix_services_run_the_jdk_of_the_set "$(t_rows 9 regionserver-0 regionserver 0)" \
    "$(grep -v '^#' "$T_A2_OUT/deploy/a2/services.tsv" | awk -F '\t' '$9 == 21')"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" matrix "$suite" "$T_A2/no-such.env"
  t_equals matrix_refuses_a_missing_set 6 "$T_STATUS"
  t_a2 "PX_HOME=$home" "$T_BASH" "$home/bin/px-cluster" matrix "$suite"
  t_equals matrix_needs_a_set 2 "$T_STATUS"
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
# Command-line argument parsing and test suite execution
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
t_group_a2_platform
t_group_a2_cluster
t_group_a2_matrix
t_group_tree

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
