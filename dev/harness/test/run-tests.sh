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
# Beyond boundary enforcement, this file validates the remainder of the harness: documentation
# policies, substrate contract compliance against the backend, the twelve CLI entry points, a full
# sweep of every contract function and px-cluster subcommand against the fake runtime, the suite
# runner, the run wrapper, the configuration loader, the output policy, and the JSON and TSV
# emitters. Dedicated regression cases cover defects identified during design review wherever the
# fake runtime can reproduce them.
#
# All test fixtures are materialized from heredocs into a single temporary directory that is
# removed by the exit hook, ensuring no fixture files are committed to the repository. Commands
# execute with the working tree root as their working directory, and the final test group verifies
# that no test execution created any new paths under that root.
#
# Test case identifiers referenced as acceptance criteria by requirements retain those exact names
# to preserve requirement traceability: readme_states_policies (R-002), no_direct_docker (R-004),
# no_direct_kubectl (R-281), bins_source_common (R-006), and no_user_paths (R-011).
#
# Output follows TAP conventions: one "ok N - name" or "not ok N - name" line per assertion,
# diagnostic output formatted as comment lines, a trailing plan line, and a final summary. The
# shell runs with set -uo pipefail and omits set -e so failed assertions are recorded without
# terminating the test suite early.

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
T_ENGINE_RE='(^|[^[:alnum:]_])(PX_DOCKER_BIN|_PXC_ENGINE|_PXC_COMPOSE)([^[:alnum:]_]|$)'
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
T_VERBOSE=0
T_KEEP=0
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

## @description  Prints command-line usage instructions and options for this test runner.
## @return       Writes the usage text to stdout. Exit 0.
usage() {
  cat <<'EOF'
Usage: run-tests.sh [-v] [-k] [-h]

Runs the harness self-test. It needs no container runtime, no network and no
Maven build.

Options:
  -v  report the detail of a passing assertion as well as a failing one
  -k  keep the temporary fixture directory for a diagnosis
  -h  show this text

Output: TAP lines on stdout, a plan line, then a summary. Exit 0 when every
assertion passed, 1 when one failed.
EOF
}

# ---------------------------------------------------------------------------
# Test runner assertion and execution primitives
# ---------------------------------------------------------------------------

# Formats multi-line diagnostic messages as individual TAP comment lines so that test output
# remains readable.
## @description  Emits diagnostic text as TAP comment lines on standard output.
## @param        text...  Diagnostic text to format line by line.
## @return       Writes TAP comment lines to stdout. Exit 0.
t_diag() {
  local line
  while IFS= read -r line; do
    printf '# %s\n' "$line"
  done <<EOF
$*
EOF
}

## @description  Records a passing test assertion and emits its TAP result line.
## @param        name, detail...  Assertion name and optional detail reported only in verbose mode.
## @return       Writes one passing TAP line to stdout. Exit 0.
t_pass() {
  local name=$1
  shift
  T_NUMBER=$((T_NUMBER + 1))
  printf 'ok %s - %s\n' "$T_NUMBER" "$name"
  if [ "$T_VERBOSE" = 1 ] && [ $# -gt 0 ]; then
    t_diag "$@"
  fi
}

## @description  Records a failing test assertion along with its supporting diagnostic evidence.
## @param        name, detail...  Assertion name and diagnostic detail lines for the failure.
## @return       Writes one failing TAP line and its diagnostics to stdout. Exit 0.
t_fail() {
  local name=$1
  shift
  T_NUMBER=$((T_NUMBER + 1))
  T_FAILED=$((T_FAILED + 1))
  T_FAILED_NAMES="$T_FAILED_NAMES  $T_NUMBER $name$PX_NL"
  printf 'not ok %s - %s\n' "$T_NUMBER" "$name"
  [ $# -eq 0 ] || t_diag "$@"
}

## @description  Verifies that an actual value equals the expected value.
## @param        name, expected, actual  Assertion name, expected value, and actual value.
## @return       Records one equality assertion. Exit 0.
t_equals() {
  if [ "$2" = "$3" ]; then
    t_pass "$1" "value $3"
  else
    t_fail "$1" "expected: $2" "actual:   $3"
  fi
}

## @description  Verifies that a text string contains a specified substring.
## @param        name, needle, text  Assertion name, required substring, and searched text.
## @return       Records one substring presence assertion. Exit 0.
t_contains() {
  case "$3" in
  *"$2"*) t_pass "$1" "found: $2" ;;
  *) t_fail "$1" "did not find: $2" "in: $3" ;;
  esac
}

## @description  Verifies that a text string does not contain a forbidden substring.
## @param        name, needle, text  Assertion name, forbidden substring, and searched text.
## @return       Records one substring absence assertion. Exit 0.
t_absent() {
  case "$3" in
  *"$2"*) t_fail "$1" "unexpectedly found: $2" "in: $3" ;;
  *) t_pass "$1" "absent: $2" ;;
  esac
}

## @description  Verifies that a given text string is empty.
## @param        name, text  Assertion name and text string expected to be empty.
## @return       Records one emptiness assertion. Exit 0.
t_empty() {
  if [ -z "$2" ]; then
    t_pass "$1"
  else
    t_fail "$1" "expected nothing, got:" "$2"
  fi
}

## @description  Verifies that a given text string is non-empty.
## @param        name, text  Assertion name and text string expected to be non-empty.
## @return       Records one non-emptiness assertion. Exit 0.
t_nonempty() {
  if [ -n "$2" ]; then
    t_pass "$1" "$2"
  else
    t_fail "$1" "expected some output, got nothing"
  fi
}

# Combines the exit status and full standard output of the preceding command into a single
# comparison value so that any failure diagnostic displays both together.
## @description  Verifies both the exit status and complete standard output of the last command.
## @param        name, expected status, expected stdout  Assertion name, status, and stdout text.
## @return       Records one combined outcome assertion. Exit 0.
t_outcome() {
  t_equals "$1" "exit $2$PX_NL$3" "exit $T_STATUS$PX_NL$T_STDOUT"
}

# Compares the exit status and only the leading columns of standard output from the last command so
# test cases can ignore trailing timing columns that vary from run to run.
## @description  Verifies the exit status and leading TSV columns of the last command's output.
## @param        name, expected status, column count, expected rows  Assertion target and columns.
## @return       Records one column-sliced outcome assertion. Exit 0.
t_outcome_columns() {
  t_equals "$1" "exit $2$PX_NL$4" "exit $T_STATUS$PX_NL$(t_columns "$3" "$T_STDOUT")"
}

## @description  Extracts the leading columns from tab-separated rows.
## @param        column count, rows  Number of leading columns to keep and input TSV rows.
## @return       Writes the selected leading columns to stdout. Exit 0.
t_columns() {
  [ -n "$2" ] || return 0
  printf '%s\n' "$2" | cut -f "1-$1"
}

# Formats a flat list of argument fields into tab-separated rows of a fixed column count so
# expected test values stay readable without embedding literal tab characters.
## @description  Constructs tab-separated rows of a specified width from a flat list of fields.
## @param        column count, field...  Columns per row followed by the individual field values.
## @return       Writes the formatted TSV rows to stdout. Exit 0.
t_rows() {
  local count=$1 column=0 row="" field
  shift
  for field in "$@"; do
    column=$((column + 1))
    if [ "$column" = 1 ]; then
      row=$field
    else
      row="$row$PX_TAB$field"
    fi
    if [ "$column" = "$count" ]; then
      printf '%s\n' "$row"
      column=0
    fi
  done
}

# Collapses consecutive spaces into a single space so test cases can inspect aligned table rows
# without depending on column padding.
## @description  Collapses runs of multiple spaces in a text string into single spaces.
## @param        text  Input text string to normalize.
## @return       Writes the space-collapsed text to stdout. Exit 0.
t_squeeze() { printf '%s\n' "$1" | tr -s ' '; }

# Executes a command inside a target working directory and captures its exit status, stdout, and
# stderr via temporary files rather than command substitution so variables set by the command are
# not lost in a subshell pipeline and output streams stay separate. Standard input is connected to
# /dev/null so commands cannot consume enclosing loop input or observe a terminal during exec.
## @description  Executes a command in a specified directory and captures its status and outputs.
## @param        directory, command...  Target working directory followed by the command and args.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_run_in() {
  local dir=$1
  shift
  T_STATUS=0
  (cd "$dir" && exec "$@") </dev/null >"$T_OUT" 2>"$T_ERR" || T_STATUS=$?
  T_STDOUT="$(cat "$T_OUT")"
  T_STDERR="$(cat "$T_ERR")"
}

## @description  Executes a command in the current test working directory and captures its outcome.
## @param        command...  Command and arguments to execute.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_run() { t_run_in "$PWD" "$@"; }

# Runs a command with the fake runtime directory prepended to PATH and referenced by PX_DOCKER_BIN
# so any invocation reaching a runtime is logged rather than executed against the host system.
## @description  Executes a command with the fake runtime shims active on PATH and PX_DOCKER_BIN.
## @param        command...  Command and arguments to execute under the shim environment.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_with_shim() {
  t_run env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SHIM_STATE" "$@"
}

# Runs a command configured against the sweep test cluster, pointing the fake runtime at the sweep
# state directory, setting the output root to the sweep output directory, and targeting cluster ID
# sweep. Any leading NAME=VALUE arguments inject additional environment variables for the call.
## @description  Executes a command in the verb sweep environment against the sweep cluster.
## @param        command...  Optional leading NAME=VALUE overrides followed by the command to run.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_sweep() {
  t_run env "PATH=$T_SHIM_DIR:$PATH" "PX_DOCKER_BIN=$T_SHIM_DIR/docker" \
    "PX_FAKE_DOCKER_LOG=$T_SHIM_LOG" "PX_FAKE_STATE_DIR=$T_SWEEP_STATE" \
    "PX_OUT=$T_SWEEP_OUT" "PX_CLUSTER_ID=sweep" "PX_POLL_INTERVAL_S=0.2" \
    "PX_REGION_MOVER_CMD=$T_MOVER_CMD" "$@"
}

## @description  Invokes a substrate contract function in a backend-bound process against sweep.
## @param        function, argument...  Contract function name and its arguments.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_sb() { t_sweep "$T_BASH" "$T_WORK/probe/sb.sh" "$@"; }

## @description  Executes a px-cluster subcommand against the sweep cluster under the fake runtime.
## @param        argument...  Subcommand and arguments passed to px-cluster --cluster sweep.
## @return       Sets T_STATUS, T_STDOUT, and T_STDERR. Exit 0.
t_pc() { t_sweep "$T_BASH" "$T_BIN/px-cluster" --cluster sweep "$@"; }

# Evaluates multiple substrate contract calls within a single assertion. Each input line specifies
# the expected exit code followed by the contract function name and its space-separated arguments,
# and any call whose actual exit code differs is reported in the failure diagnostic.
## @description  Verifies the exit codes of a batch of substrate contract function calls.
## @param        name, lines  Assertion name and newline-delimited expected-code call specs.
## @return       Records one assertion covering all listed contract calls. Exit 0.
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

# Verifies that the fake runtime log contains a specific invocation line. Because compose calls
# include the compose file path and project directory after the project name, those two flags are
# stripped before matching unless the caller passes raw to compare the unmodified log line.
## @description  Verifies that the fake runtime recorded a specific invocation line.
## @param        name, the whole call line, raw  Assertion name, expected call, and optional raw.
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

## @description  Truncates the fake runtime call log so subsequent assertions inspect one command.
## @return       Empties the log file at T_SHIM_LOG. Exit 0.
t_shim_log_reset() { : >"$T_SHIM_LOG"; }

# Restores the fake runtime state for the selftest cluster to a single running RegionServer
# instance with a restart count of zero so test cases never observe state left by earlier runs.
## @description  Resets the selftest cluster fake runtime state to one healthy RegionServer.
## @return       Reinitializes instances.txt and restart-count in T_SHIM_STATE. Exit 0.
t_shim_state_reset() {
  rm -f "$T_SHIM_STATE"/*
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|||\n' c0ffee000001 regionserver \
    px-selftest-regionserver-1 running 'Up 3 minutes (healthy)' \
    phoenix-harness:selftest regionserver 1 0 >"$T_SHIM_STATE/instances.txt"
  printf '0\n' >"$T_SHIM_STATE/restart-count"
}

## @description  Reads the planned-action ledger for the sweep cluster if present.
## @return       Writes the contents of planned-px-sweep.tsv to stdout. Exit 0.
t_sweep_planned() { cat "$T_SWEEP_OUT/state/planned-px-sweep.tsv" 2>/dev/null || true; }

# Enumerates all filesystem paths under a root directory while pruning .git repository metadata and
# target build output directories that concurrent builds in other shells might modify.
## @description  Lists all sorted paths under a root directory excluding .git and target trees.
## @param        root directory  Root directory path to traverse.
## @return       Writes sorted paths to stdout. Exit 0.
t_tree_paths() {
  find "$1" \( -name .git -o -name target \) -prune -o -print 2>/dev/null | LC_ALL=C sort
}

## @description  Reports paths present in a second directory listing that were absent in the first.
## @param        first listing file, second listing file  Baseline and post-run path listing files.
## @return       Writes newly added paths to stdout. Exit 0.
t_tree_added() { LC_ALL=C comm -13 "$1" "$2"; }

## @description  Extracts a specific column from a named variable row in the last config dump.
## @param        name, column  Configuration variable name and 1-based column index.
## @return       Writes the field value to stdout, or an empty line when no row matches. Exit 0.
t_dump_field() {
  local name=$1 column=$2 line
  while IFS= read -r line; do
    case "$line" in
    "$name$PX_TAB"*)
      px_tsv_field "$column" "$line" || printf '\n'
      return 0
      ;;
    esac
  done <<EOF
$T_STDOUT
EOF
  printf '\n'
}

# shellcheck disable=SC2329 # the exit hook of lib/common.sh calls it by name
## @description  Removes the temporary fixture directory via the exit hook on pass or fail.
## @return       Deletes T_WORK unless T_KEEP is set. Exit 0.
t_cleanup() {
  [ -n "$T_WORK" ] || return 0
  if [ "$T_KEEP" = 1 ]; then
    printf '# fixtures kept in %s\n' "$T_WORK"
    return 0
  fi
  rm -rf "$T_WORK"
}

# ---------------------------------------------------------------------------
# Dynamic test fixture generators
# ---------------------------------------------------------------------------

# Generates the suite definition fixtures exercised by the suite runner tests. The order.suite
# fixture intentionally declares tests out of topological dependency order so the printed execution
# plan demonstrates that dependency sorting occurred.
## @description  Materializes test suite fixture files under the temporary workspace.
## @return       Writes suite fixture files into T_WORK/suites. Exit 0.
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
  # Create two matching files in the execution directory to detect unintended glob expansion.
  mkdir -p "$T_WORK/globdir"
  : >"$T_WORK/globdir/alpha.sql"
  : >"$T_WORK/globdir/beta.sql"
}

# Generates stub driver scripts for px-run and px-suite tests: one exits with its argument status
# to verify exit code propagation, four deterministically mutate fake runtime state without timing
# assumptions (staging a container restart, an unexpected exit, a vanished instance, and an
# unresponsive runtime), one prints its received arguments, and one hostile driver attempts to read
# the backend engine variable.
## @description  Materializes stub and fault-injection driver scripts under T_WORK/drivers.
## @return       Writes executable driver scripts. Exit 0.
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

# Generates the fake container runtime shim, its backing state directory, and fake shims for each
# Kubernetes CLI tool. Each Kubernetes shim appends its binary name and arguments to the shared
# runtime call log and exits without output. Generating these executables at runtime avoids
# committing files named after restricted tools into the source tree where Layer 1 would scan them.
## @description  Creates fake container runtime and Kubernetes tool shims and initializes state.
## @return       Populates T_SHIM_DIR, T_SHIM_STATE, and T_SHIM_LOG. Exit 0.
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

fake_split() {
  IFS='|' read -r f_id f_role f_name f_state f_status f_image f_service \
    f_number f_index f_group f_start f_drain <<<"$1"
}

fake_join() {
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "$f_id" "$f_role" "$f_name" \
    "$f_state" "$f_status" "$f_image" "$f_service" "$f_number" "$f_index" \
    "$f_group" "$f_start" "$f_drain"
}

# Rewrites the state and the status of the instances whose id or service is
# the key. An empty state removes them.
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

# Scales one service: it keeps the numbers up to the count and adds the
# missing ones as copies of the first instance of the service.
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
  *MemTotal*) printf '25165824000\n' ;;
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
  if [ "${1:-}" = --help ]; then
    [ ! -e "$fake_state/no-kill" ] || exit 1
    printf 'Usage: fake kill [-s SIGNAL] CONTAINER\n'
    exit 0
  fi
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
    [ ! -e "$fake_state/no-compose" ] || exit 1
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

# Initializes the fixture state for the verb sweep cluster, including a rendered deployment
# directory, fake runtime instance records, and simulated events, networks, and volumes. The
# topology comprises one master, two RegionServers carrying the drain label, and an on-demand
# NodeManager in the yarn group. The generated port map binds ports 1 and 2, which remain unused by
# host services so preflight port availability checks behave deterministically.
## @description  Creates the deployment directory and fake runtime state for the sweep cluster.
## @return       Populates T_SWEEP_STATE, T_SWEEP_OUT, and T_DEPLOY. Exit 0.
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
  # Event records test index derivation fallback: explicit label first, container number second,
  # and container name suffix third.
  cat >"$T_SWEEP_STATE/events.txt" <<'SWEEP_EOF'
1700000000000000000|regionserver||2|container|die|px-sweep-regionserver-2
1700000000500000000|master|0||container|start|px-sweep-master-1
1700000000750000000|regionserver|||container|kill|px-sweep-regionserver-2
1700000001000000000||||network|connect|px-sweep_default
SWEEP_EOF
  printf 'services: {}\n' >"$T_DEPLOY/compose.yaml"
  printf 'PX_PORT_MASTER_UI=1\nPX_PORT_REGIONSERVER_RPC_0=2\n' \
    >"$T_DEPLOY/ports.generated.env"
}

# Generates environment configuration fixtures for testing five-stage precedence resolution. Each
# successive precedence layer overrides one fewer variable than the layer below it so a single
# configuration dump reveals the winning origin for every stage.
## @description  Writes environment files for configuration precedence and validation tests.
## @return       Populates T_WORK/env with valid and malformed environment files. Exit 0.
t_fixtures_config() {
  mkdir -p "$T_WORK/env"
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

# Builds two synthetic directory trees for testing the Layer 1 static scanner. The first tree
# contains one violation of each category on line 3 of its respective file alongside three benign
# files (allowlisted backend, comment-only token, and inline waiver) that the scanner must ignore.
# The second tree simulates a workspace where both allowlist entries have become stale.
## @description  Creates synthetic source trees for Layer 1 static scan and allowlist tests.
## @return       Populates T_WORK/scan and T_WORK/stale. Exit 0.
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

# Generates small standalone probe scripts that source harness libraries and invoke internal
# functions in an isolated process, enabling direct behavioral assertions on runtime traps,
# configuration loading, timeouts, and JSON/TSV emitters.
## @description  Writes helper probe scripts used to test library functions in child processes.
## @return       Populates T_WORK/probe and T_WORK/nap with probe scripts. Exit 0.
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
  cat >"$T_WORK/probe/shim-direct.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
# This probe sources no library, so the name resolves through PATH alone.
docker version
PROBE_EOF
  cat >"$T_WORK/probe/shim-names.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
# This probe sources no library, so each name resolves through PATH alone.
for probe_name in "$@"; do
  "$probe_name" version
done
PROBE_EOF
  cat >"$T_WORK/probe/sb-load.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/backend/contract.sh"
sb_load
sb_bound_backend
PROBE_EOF
  cat >"$T_WORK/probe/sb-list.sh" <<'PROBE_EOF'
#!/usr/bin/env bash
. "$PX_HOME/lib/backend/contract.sh"
sb_load
sb_list px-selftest
PROBE_EOF
  # Forward the exit status and standard output of a single contract function directly as the
  # probe outcome, running under set -u to match production CLI execution.
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

# Creates the temporary workspace directory, registers the cleanup exit hook, and populates all
# test fixtures. The PX_OUT output root is also placed inside this directory so commands under test
# never write artifacts outside the temporary workspace.
## @description  Allocates the temporary workspace, exports PX_OUT, and generates all fixtures.
## @return       Sets T_WORK and exports PX_OUT. Exit 0, or exits 1 when creation fails.
t_setup() {
  local root=${TMPDIR:-/tmp}
  T_WORK="$(mktemp -d "${root%/}/px-selftest.XXXXXXXX")" || {
    printf 'Bail out! cannot create a fixture directory\n'
    exit 1
  }
  px_on_exit t_cleanup
  # Resolve the physical path of T_WORK up front because the output policy canonicalizes PX_OUT,
  # and temporary directories on platforms such as macOS often traverse symbolic links that would
  # otherwise cause string comparisons between paths to fail.
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
}

# ---------------------------------------------------------------------------
# Layer 1: Static source scan and allowlist enforcement
# ---------------------------------------------------------------------------

# Emits the Layer 1 static scan allowlist mapping relative file paths that may contain runtime
# tokens to their mandatory architectural justifications. Entries that no longer match any token
# fail the staleness check so the allowlist cannot accumulate unused exemptions.
## @description  Outputs the Layer 1 allowlist as tab-separated path and rationale rows.
## @return       Writes path and reason TSV rows to stdout. Exit 0.
t_allowlist() {
  px_tsv_row lib/backend/compose.sh "the backend, the only runtime caller"
  px_tsv_row test/run-tests.sh "generates the fake runtime shim from a heredoc"
}

## @description  Determines whether a harness-relative file path appears in the Layer 1 allowlist.
## @param        path  File path relative to the harness root directory.
## @return       Exit 0 when the allowlist covers the path. Exit 1 otherwise.
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

# Determines whether a file is subject to the Layer 1 static scan. Any file under bin/, any file
# with a .sh, .env, .tmpl, or .suite extension, and any file beginning with a shell shebang can be
# executed, sourced, or rendered into executable form, whereas Markdown documentation is excluded
# because prose must name container runtimes and tools to describe the architecture.
## @description  Checks whether a file is in scope for the Layer 1 static runtime token scan.
## @param        path  Filesystem path of the candidate file.
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

# Scans all in-scope files under a root directory for restricted tool tokens or backend engine
# variables outside allowlisted paths. Comment lines and lines carrying the inline waiver marker
# are ignored because neither performs an executable runtime invocation.
## @description  Scans a directory tree for unallowlisted runtime tokens or engine variables.
## @param        root directory, expression  Target root path and regex (default Layer 1 tokens).
## @return       Writes one path, line number, and line per violation to stdout. Exit 0.
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

# Identifies allowlist entries that have become stale either because the referenced file no longer
# exists in the tree or because the file no longer contains any restricted runtime tokens.
## @description  Reports allowlist entries whose target files are missing or hold no tokens.
## @param        root directory  Root directory against which allowlist paths are checked.
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

## @description  Reports any Layer 1 allowlist entry that lacks a non-empty justification.
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

# Scans all in-scope harness files outside lib/backend/compose.sh for constructs that bypass shell
# function traps: shell builtins that skip function lookup (command, env, exec), explicit /bin/
# runtime paths that Layer 1 word-boundary regexes ignore, and PATH lookups via which or type -P.
## @description  Scans the harness tree for shell trap bypass constructs outside the backend.
## @return       Writes one path, line number, and line per violation to stdout. Exit 0.
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

# Checks a documentation file for the five normative policy phrases mandated by the design so that
# any documentation edit that omits a committed operational policy is detected.
## @description  Reports which of the five mandatory policy phrases are absent from a document.
## @param        path  Filesystem path to the Markdown document under inspection.
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

## @description  Verifies harness documentation requirements and repository-wide hygiene rules.
## @return       Records assertions. Exit 0.
t_group_documents() {
  local fixture
  printf '# documents and directory rules\n'
  if [ -f "$T_DEV/README.md" ]; then
    t_pass dev_readme_exists "$T_DEV/README.md"
  else
    t_fail dev_readme_exists "the index of dev tools is missing"
  fi
  t_run grep -qi harness "$T_DEV/README.md"
  t_equals dev_readme_indexes_harness 0 "$T_STATUS"
  t_empty readme_states_policies "$(t_missing_policies "$PX_HOME/README.md")"
  # Verify that stripping a required policy phrase causes the check to fail, confirming the
  # detector is sensitive to policy removal.
  fixture="$T_WORK/no-policy.md"
  grep -viF 'one cluster per host' "$PX_HOME/README.md" >"$fixture"
  t_equals readme_policy_removal_is_detected 'one cluster per host' \
    "$(t_missing_policies "$fixture")"
  t_empty no_java_under_dev "$(find "$T_DEV" -name '*.java' 2>/dev/null)"
  t_empty no_user_paths "$(grep -rlE "$T_USER_PATH_RE" "$PX_HOME" 2>/dev/null)"
}

# Validates Layer 1 of the architectural boundary: confirms the live harness tree is clean,
# verifies detection of direct runtime tokens and engine variables across commands, drivers, suite
# libraries, and plug-ins, checks exemption handling for the backend, comments, and inline waivers,
# and enforces allowlist justification and staleness rules.
## @description  Executes all Layer 1 static scan and allowlist validation assertions.
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
  hits="$(t_scan_root "$T_WORK/scan" "$T_K8S_RE")"
  t_contains no_direct_kubectl_reports_a_suite_library 'suites/lib/common.sh:3:' "$hits"
  t_contains no_direct_kubectl_reports_helm_in_a_plugin 'plugins/evil/plugin.sh:3:' "$hits"
  t_contains no_direct_kubectl_reports_k3d_in_a_plugin 'plugins/evil/plugin.sh:4:' "$hits"
  t_contains no_direct_kubectl_reports_crictl_in_a_plugin 'plugins/evil/plugin.sh:5:' "$hits"
  t_absent no_direct_kubectl_ignores_a_runtime_name 'bin/px-run' "$hits"
  hits="$(t_scan_root "$T_WORK/scan")"
  t_absent scan_allows_the_backend 'compose.sh' "$hits"
  t_absent scan_drops_a_comment_line 'commented.sh' "$hits"
  t_absent scan_drops_a_waived_line 'waived.sh' "$hits"
  t_empty allowlist_entries_carry_a_reason "$(t_allowlist_unreasoned)"
  t_empty allowlist_entries_are_current "$(t_allowlist_stale "$PX_HOME")"
  stale="$(t_allowlist_stale "$T_WORK/stale")"
  t_contains allowlist_stale_token_is_reported \
    'lib/backend/compose.sh: no runtime token left' "$stale"
  t_contains allowlist_stale_path_is_reported \
    'test/run-tests.sh: no such file' "$stale"
}

# Validates Layer 2 of the architectural boundary by testing the runtime trap functions defined in
# lib/common.sh against the active function table of processes that source it across direct calls,
# subshells, set -e contexts, all twelve CLI entry points, and BASH_ENV suite actions.
## @description  Executes all Layer 2 runtime function trap and bypass scan assertions.
## @return       Records assertions. Exit 0.
t_group_trap() {
  local name libs lib probe expected="" offenders="" log
  local names=()
  printf '# layer 2, the runtime trap\n'
  for name in $T_TRAP_NAMES; do
    names+=("$name")
    expected="$expected$name 2$PX_NL"
  done
  t_run "$T_BASH" "$T_WORK/probe/trap-names.sh" "${names[@]}"
  t_equals trap_blocks_eight_tool_names "${expected%"$PX_NL"}" "$T_STDOUT"
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
  # Suite test actions execute in separate child shells and must still inherit the runtime traps
  # via BASH_ENV.
  t_run env "PX_SESSION_ID=selftest-trapped" "$T_BASH" "$T_BIN/px-suite" \
    --continue-on-failure "$T_WORK/suites/trapped.suite"
  t_equals trap_reaches_a_suite_action 3 "$T_STATUS"
  log="$PX_OUT/sessions/selftest-trapped/tests"
  t_contains trap_reaches_a_suite_action_message 'container runtime call blocked' \
    "$(cat "$log/blocked/stderr.log" 2>/dev/null)"
  t_contains trap_blocks_kubectl_in_a_suite_action 'call blocked: kubectl get pods' \
    "$(cat "$log/blocked-kubectl/stderr.log" 2>/dev/null)"
  t_empty no_bypass_form_outside_the_backend "$(t_scan_bypass)"
}

# Validates Layer 3 of the architectural boundary by placing fake container runtime and Kubernetes
# tool shims first on PATH. Verifies that eleven non-runtime CLI entry points and a hostile driver
# attempting to read PX_DOCKER_BIN make zero runtime calls, while a genuine substrate contract call
# is recorded by the shim.
## @description  Executes all Layer 3 fake runtime shim negative, positive, and hostile controls.
## @return       Records assertions. Exit 0.
t_group_shim() {
  local calls name expected=""
  local names=()
  printf '# layer 3, the fake runtime\n'
  t_shim_state_reset
  # Verify that the fake Docker shim is first on PATH and actively logs invocations; otherwise an
  # empty log during negative controls could merely reflect an ineffective PATH override.
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/shim-direct.sh"
  t_equals shim_is_first_on_path 29.9.3 "$T_STDOUT"
  t_nonempty shim_records_a_call "$(cat "$T_SHIM_LOG")"
  # Verify that every Kubernetes tool shim on PATH also logs invocations so an empty log below
  # confirms that no entry point invoked any Kubernetes binary.
  for name in ${T_K8S_NAMES//|/ }; do
    names+=("$name")
    expected="$expected$name version$PX_NL"
  done
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/shim-names.sh" "${names[@]}"
  t_equals shim_records_each_kubernetes_tool "${expected%"$PX_NL"}" \
    "$(cat "$T_SHIM_LOG")"
  # Run negative controls across all eleven non-runtime entry points one by one.
  t_negative_control cluster_config "$T_BIN/px-cluster" config
  t_negative_control cluster_validate "$T_BIN/px-cluster" validate --no-backend
  t_negative_control suite_dry_run "$T_BIN/px-suite" --dry-run \
    "$T_WORK/suites/order.suite"
  t_negative_control run_dry_run "$T_BIN/px-run" "$T_WORK/drivers/exit-code.sh" --dry-run
  t_negative_control tool_doctor "$T_BIN/px-tool" doctor --config-only
  t_negative_control collect_list "$T_BIN/px-collect" list
  t_negative_control collect_help "$T_BIN/px-collect" start --help
  t_negative_control backend_bind "$T_WORK/probe/sb-load.sh"
  t_negative_control cluster_down_dry_run "$T_BIN/px-cluster" --dry-run down \
    -e PX_SCOPE_ID=px-selftest
  # Execute a positive control through the substrate contract to confirm that a real backend call
  # records the scope label selector in the shim log, validating the negative controls above.
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_WORK/probe/sb-list.sh"
  t_nonempty positive_control_fills_the_record "$(cat "$T_SHIM_LOG")"
  t_contains positive_control_carries_the_scope_selector \
    'label=px.harness.cluster=selftest' "$(cat "$T_SHIM_LOG")"
  t_contains positive_control_reports_the_instance \
    "regionserver${PX_TAB}0${PX_TAB}c0ffee000001" "$T_STDOUT"
  # Run the hostile driver under px-run after pre-run snapshot resolution has initialized the
  # backend, verifying that the internal engine variable is not exported to child driver processes.
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/engine-var.sh"
  calls="$(cat "$T_SHIM_LOG")"
  t_equals hostile_driver_run_succeeds 0 "$T_STATUS"
  t_contains hostile_driver_run_reaches_the_fake_runtime \
    'ps -a --filter label=px.harness.cluster=selftest' "$calls"
  t_contains hostile_driver_sees_no_engine_variable 'engine variable []' \
    "$(cat "$T_STDOUT/driver.log" 2>/dev/null)"
  t_absent hostile_driver_reaches_no_runtime 'hostile-driver' "$calls"
}

## @description  Runs a non-runtime entry point and verifies that the fake runtime log stays empty.
## @param        name, command...  Control case suffix and command with arguments to execute.
## @return       Records two assertions. Exit 0.
t_negative_control() {
  local name=$1
  shift
  t_shim_log_reset
  t_with_shim "$T_BASH" "$@"
  t_equals "negative_control_${name}_succeeds" 0 "$T_STATUS"
  t_empty "negative_control_${name}_calls_no_runtime" "$(cat "$T_SHIM_LOG")"
}

# Compares the sb_* functions declared by the substrate contract against those defined in a backend
# implementation file in both directions: a missing function breaks callers, while an undeclared
# extra sb_* function introduces an uncontracted verb that alternative backends would not know to
# implement.
## @description  Reports missing or undeclared extra sb_* functions in a backend implementation.
## @param        backend file  Filesystem path to the backend script being audited.
## @return       Writes one line per difference to stdout. Exit 0.
t_backend_verbs() {
  local file=$1 declared defined name
  declared="$(sb_contract_functions)"
  defined="$(sed -n 's/^\(sb_[a-z0-9_]*\)().*/\1/p' "$file")"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    case "$PX_NL$defined$PX_NL" in
    *"$PX_NL$name$PX_NL"*) ;;
    *) printf 'missing %s\n' "$name" ;;
    esac
  done <<EOF
$declared
EOF
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    case "$PX_NL$declared$PX_NL" in
    *"$PX_NL$name$PX_NL"*) ;;
    *) printf 'extra %s\n' "$name" ;;
    esac
  done <<EOF
$defined
EOF
}

## @description  Verifies contract-to-backend verb parity, contract version, and schema indexing.
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
  t_equals contract_declares_27_required_verbs 27 \
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

# Validates the CLI command surface across all twelve entry points: executable permissions, --help
# output, exit code 2 on invalid flags, mandatory sourcing of lib/common.sh or contract.sh, and the
# prohibition on redefining logging primitives owned by lib/common.sh.
## @description  Verifies executable presence, flag handling, and library usage for all commands.
## @return       Records assertions. Exit 0.
t_group_commands() {
  local name missing="" bad="" unsourced="" own=""
  printf '# the twelve entry points\n'
  for name in $T_COMMANDS; do
    [ -x "$T_BIN/$name" ] || missing="$missing $name"
  done
  t_empty twelve_entry_points_are_present "$missing"
  for name in $T_COMMANDS; do
    t_run "$T_BASH" "$T_BIN/$name" --help
    if [ "$T_STATUS" != 0 ] || [ -z "$T_STDOUT" ]; then
      bad="$bad $name=$T_STATUS"
    fi
  done
  t_empty every_entry_point_answers_help "$bad"
  bad=""
  for name in $T_COMMANDS; do
    t_run "$T_BASH" "$T_BIN/$name" --not-a-flag
    [ "$T_STATUS" = 2 ] || bad="$bad $name=$T_STATUS"
  done
  t_empty every_entry_point_refuses_a_bad_flag "$bad"
  for name in $T_COMMANDS; do
    grep -qE '^\.[[:space:]]*"[$]SELF/\.\./lib/(common\.sh|backend/contract\.sh)"$' \
      "$T_BIN/$name" || unsourced="$unsourced $name"
  done
  t_empty bins_source_common "$unsourced"
  t_run grep -q 'common\.sh' "$PX_HOME/lib/backend/contract.sh"
  t_equals the_contract_sources_the_shared_library 0 "$T_STATUS"
  for name in $T_COMMANDS; do
    if grep -qE '^(log|logger|info|warn|error|debug|die|fatal)[[:space:]]*\(\)' \
      "$T_BIN/$name"; then
      own="$own $name"
    fi
  done
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

# Extracts the ordered test identifiers from tab-separated suite plan output into a single
# space-delimited string for concise ordering assertions.
## @description  Extracts space-separated test identifiers from suite plan TSV rows in plan order.
## @param        plan rows  Multi-line TSV plan output produced by px-suite --dry-run.
## @return       Writes the ids, separated by spaces, to stdout. Exit 0.
t_plan_ids() {
  local row out=""
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    out="$out ${row#*"$PX_TAB"}"
    out="${out%%"$PX_TAB"*}"
  done <<EOF
$1
EOF
  printf '%s\n' "${out# }"
}

# Validates the six core capabilities of px-suite: topological dependency sorting and cycle
# rejection, positional range boundaries (--from/--to), inclusion and exclusion filters
# (--only/--skip), fault-tolerant continuation (--continue-on-failure), per-session directory
# isolation, and side-effect-free dry runs.
## @description  Executes assertions covering px-suite planning, filtering, and session isolation.
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
  t_run env "PX_SESSION_ID=selftest-stop" "$T_BASH" "$T_BIN/px-suite" \
    "$suite/mixed.suite"
  t_equals suite_stops_at_the_first_failure 1 "$T_STATUS"
  t_equals suite_runs_nothing_after_the_failure 3 "$(printf '%s\n' "$T_STDOUT" |
    grep -c .)"
  t_equals suite_ends_with_a_result_row "$(t_rows 4 result FAIL 1 \
    "$PX_OUT/sessions/selftest-stop")" "$(t_last_line "$T_STDOUT")"
  t_run env "PX_SESSION_ID=selftest-go" "$T_BASH" "$T_BIN/px-suite" \
    --continue-on-failure "$suite/mixed.suite"
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
  # Distinct session IDs must maintain isolated result directories and row counts, and reusing an
  # existing session ID must be rejected rather than appended to.
  t_run env "PX_SESSION_ID=selftest-second" "$T_BASH" "$T_BIN/px-suite" \
    --continue-on-failure "$suite/mixed.suite"
  t_equals suite_sessions_hold_their_own_rows 3 \
    "$(grep -c . "$PX_OUT/sessions/selftest-second/results.tsv")"
  t_equals suite_sessions_do_not_share_state 3 \
    "$(grep -c . "$PX_OUT/sessions/selftest-go/results.tsv")"
  t_run env "PX_SESSION_ID=selftest-go" "$T_BASH" "$T_BIN/px-suite" \
    --continue-on-failure "$suite/mixed.suite"
  t_equals suite_refuses_a_session_name_in_use 1 "$T_STATUS"
  t_shim_log_reset
  t_with_shim "$T_BASH" "$T_BIN/px-suite" --dry-run "$suite/order.suite"
  t_empty suite_dry_run_makes_no_backend_call "$(cat "$T_SHIM_LOG")"
  t_empty suite_dry_run_makes_no_session "$(find "$PX_OUT/sessions" -name 'plan.tsv' \
    -newer "$T_SHIM_LOG" 2>/dev/null)"
  t_group_suite_regressions
}

# Verifies regression cases for previously resolved px-suite defects: literal preservation of
# driver arguments without glob or tilde expansion, INCONCLUSIVE summary status when all tests
# skip, ERROR classification for unrecognized verdict severities, exit code 6 when a test requires
# a role group without an active scope, forwarding of action warnings to suite.log, and process
# group termination of child processes when a test timeout expires.
## @description  Executes regression test cases for argument parsing, verdicts, and timeouts.
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
  t_run env "PX_SESSION_ID=selftest-skips" "$T_BASH" "$T_BIN/px-suite" \
    "$suite/skips.suite"
  t_equals suite_all_skip_exits_one 1 "$T_STATUS"
  t_contains suite_all_skip_is_inconclusive "result${PX_TAB}INCONCLUSIVE" \
    "$(cat "$session/selftest-skips/summary.tsv" 2>/dev/null)"
  t_run env "PX_SESSION_ID=selftest-severity" "$T_BASH" "$T_BIN/px-suite" \
    "$suite/severity.suite"
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
  t_run env "PX_SESSION_ID=selftest-speak" "$T_BASH" "$T_BIN/px-suite" \
    "$suite/speak.suite"
  t_equals suite_speak_runs 0 "$T_STATUS"
  t_contains suite_log_holds_the_message_of_an_action 'hello from the action' \
    "$(cat "$session/selftest-speak/suite.log" 2>/dev/null)"
  # Only a dedicated timeout binary terminates the entire child process group on expiry; the
  # shell fallback terminates only the immediate test process.
  if [ -n "$PX_TIMEOUT_BIN" ]; then
    t_run env "PX_SESSION_ID=selftest-slow" "$T_BASH" "$T_BIN/px-suite" \
      "$suite/slow.suite"
    t_equals suite_slow_test_expires 1 "$T_STATUS"
    t_suite_child_gone suite_time_limit_ends_the_child_of_a_test \
      "$session/selftest-slow/tests/slow/child.pid"
  fi
}

# Verifies that a background child process spawned by a timed-out test has terminated, polling for
# up to one second because signal delivery and process termination are asynchronous events.
## @description  Verifies that a child process recorded by a suite test is no longer running.
## @param        name, file  Assertion name and path to the file containing the child PID.
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

# Validates px-run lifecycle and snapshot reconciliation: driver exit codes propagate unchanged
# when cluster state is healthy, and unexplained instance state changes (restarts, unexpected exits
# of running instances, or vanished instances) produce anomaly exit code 3 unless accounted for by
# a planned disruption action from the current run. Post-run inventory read failures yield exit
# code 1 rather than false vanished-instance verdicts.
## @description  Verifies px-run driver exit propagation and pre/post-run inventory reconciliation.
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
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/bump-restart.sh" -- 4
  t_equals run_reports_an_unplanned_restart 3 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  t_contains run_records_the_unplanned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}4${PX_TAB}unplanned" \
    "$(grep '^diff' "$record" 2>/dev/null)"
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/bump-restart.sh" \
    --disrupt 'phase:driver_start,kill,regionserver,0' -- 4
  t_equals run_accepts_a_planned_restart 0 "$T_STATUS"
  record="$T_STDOUT/run-record.tsv"
  t_contains run_records_the_planned_verdict \
    "regionserver${PX_TAB}0${PX_TAB}0${PX_TAB}4${PX_TAB}planned" \
    "$(grep '^diff' "$record" 2>/dev/null)"
  # When the container restart policy is "no", a crashed container keeps a restart count of zero
  # and is detected instead by its transition from running to exited in the post-run snapshot.
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/exit-instance.sh"
  t_equals run_reports_an_unplanned_exit 3 "$T_STATUS"
  record="$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  t_contains run_records_the_unplanned_exit \
    "$(t_rows 8 diff regionserver 0 0 0 unplanned running exited)" "$record"
  t_contains run_records_the_termination_reason \
    "$(t_rows 6 term regionserver 0 killed 137 9)" "$record"
  t_contains run_records_the_oom_evidence "$(t_rows 4 oom regionserver 0 no)" "$record"
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/vanish.sh"
  t_equals run_reports_a_lost_instance 3 "$T_STATUS"
  t_contains run_records_the_lost_instance \
    "$(t_rows 7 diff regionserver 0 0 '' vanished running)" \
    "$(cat "$T_STDOUT/run-record.tsv" 2>/dev/null)"
  # An unreachable runtime during post-run snapshot collection must be reported as an operational
  # failure (exit 1) rather than misinterpreted as a vanished container instance.
  t_shim_state_reset
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/unreachable.sh"
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
  t_with_shim "$T_BASH" "$T_BIN/px-run" -e PX_POLL_INTERVAL_S=1 \
    --scope px-selftest --driver "$T_WORK/drivers/exit-instance.sh"
  t_equals run_ignores_a_planned_row_of_an_earlier_run 3 "$T_STATUS"
  t_shim_state_reset
}

# Validates the configuration loader: five-stage precedence ordering and provenance tracking,
# fail-closed rejection of undeclared variable names, malformed lines, export statements, and
# missing files, immutability of PX_CONTRACT_VERSION across config files and CLI overrides, and
# output-policy enforcement for PX_LOG_FILE destinations.
## @description  Verifies configuration precedence, provenance tracking, and strict validation.
## @return       Records assertions. Exit 0.
t_group_config() {
  local env_dir="$T_WORK/env"
  printf '# the configuration loader\n'
  t_run env "PX_ENV_DIR=$env_dir" "PX_RUN_CLASS=selftest" "PX_TIMEOUT_GROUP=1" \
    "$T_BASH" "$T_WORK/probe/config-load.sh" "$env_dir/named.env"
  t_equals config_the_environment_wins 1 "$(t_dump_field PX_TIMEOUT_GROUP 2)"
  t_equals config_a_named_file_beats_the_local_file 2 \
    "$(t_dump_field PX_TIMEOUT_SCALE 2)"
  t_equals config_the_local_file_beats_the_class_file 3 \
    "$(t_dump_field PX_TIMEOUT_EXEC 2)"
  t_equals config_the_class_file_beats_the_defaults 4 \
    "$(t_dump_field PX_TIMEOUT_READY 2)"
  t_equals config_the_defaults_are_the_weakest_stage 5 \
    "$(t_dump_field PX_TIMEOUT_DEFAULT 2)"
  t_equals config_reports_the_environment_origin environment \
    "$(t_dump_field PX_TIMEOUT_GROUP 3)"
  t_equals config_reports_the_file_origin "$env_dir/named.env" \
    "$(t_dump_field PX_TIMEOUT_SCALE 3)"
  t_equals config_reports_the_local_origin local "$(t_dump_field PX_TIMEOUT_EXEC 3)"
  t_equals config_reports_the_class_origin class:selftest \
    "$(t_dump_field PX_TIMEOUT_READY 3)"
  t_equals config_reports_the_defaults_origin defaults \
    "$(t_dump_field PX_TIMEOUT_DEFAULT 3)"
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
  t_equals config_refused_log_file_stays_absent no \
    "$([ -e "$T_WORK/escape.log" ] && printf yes || printf no)"
  t_run env "PX_LOG_FILE=$PX_HOME/selftest.log" "$T_BASH" "$T_BIN/px-cluster" status
  t_equals config_log_file_in_the_tree_is_refused 2 "$T_STATUS"
  t_equals config_log_file_in_the_tree_stays_absent no \
    "$([ -e "$PX_HOME/selftest.log" ] && printf yes || printf no)"
}

# Validates output boundary enforcement, unique run identifier generation, and the pure-shell
# timeout fallback: paths inside the working tree, relative paths, the filesystem root, and paths
# escaping PX_OUT are rejected; multiple run IDs generated within the same second remain distinct;
# and the timeout fallback preserves standard input, enforces expiry, and cleans up its watchdog
# sleep process.
## @description  Verifies PX_OUT boundary checks, run ID uniqueness, and timeout fallback behavior.
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

# Validates the JSON and TSV structured output emitters: proper escaping of quotes, backslashes,
# and control characters, nested JSON object/array formatting, numeric literal validation, and TSV
# rejection or sanitization of embedded tabs and newlines.
## @description  Verifies JSON escaping, nesting, and number validation alongside TSV field rules.
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

# Exercises backend identity, version reporting, capability discovery, scope creation and lookup,
# cluster provisioning, and instance listing. Provisioning creates all defined containers and
# starts every service except those marked on-demand.
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
    engine_server 29.9.3 compose_flavour plugin compose_version 2.40.0)"
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

# Exercises the readiness barrier and role-group lifecycle verbs. A whole-scope readiness wait
# reports intentionally stopped on-demand groups as stopped, whereas a wait explicitly targeting a
# stopped role waits for it and times out. Any group whose instances are running when the wait
# begins remains part of the readiness barrier, so a member crash before or during the wait is
# reported as anomaly exit code 3 rather than an intentional stop.
## @description  Sweeps sb_wait_ready and the sb_group_* lifecycle verbs across group states.
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

## @description  Sweeps instance access verbs: sb_exec, sb_logs, sb_copy_in/out, and sb_endpoint*.
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

# Exercises diagnostic evidence queries, signal delivery, and drain-aware restarts along with their
# error exit codes: exit code 1 when container inspection fails, exit code 5 when capability
# probing reports no signal delivery support, and exit code 2 when drain-restart targets a role
# lacking a drain label or a configured RPC port.
## @description  Sweeps diagnostic evidence verbs, sb_signal, and sb_restart_drain error paths.
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
  # Clear the cached capability table before and after simulating missing kill support so the
  # probe re-evaluates capabilities and restores the normal table for subsequent tests.
  : >"$T_SWEEP_STATE/no-kill"
  rm -f "$T_SWEEP_OUT/state/compose/capabilities.tsv"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_without_delivery_is_unsupported 5 "$T_STATUS"
  rm -f "$T_SWEEP_STATE/no-kill" "$T_SWEEP_OUT/state/compose/capabilities.tsv"
  # Capability probes during a runtime outage must fail without caching negative results so signal
  # delivery succeeds once the runtime becomes reachable again.
  : >"$T_SWEEP_STATE/unreachable"
  t_sb sb_signal px-sweep master 0 QUIT
  t_equals sweep_signal_on_an_unreachable_runtime_is_failure 1 "$T_STATUS"
  t_sb sb_capabilities
  rm -f "$T_SWEEP_STATE/unreachable"
  t_equals sweep_capabilities_do_not_cache_an_outage no \
    "$([ -e "$T_SWEEP_OUT/state/compose/capabilities.tsv" ] && printf yes || printf no)"
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

# Exercises service scaling, hard and delete instance termination, event log index derivation,
# resource usage sampling and unit conversion, and preflight environment checks. Service scaling
# explicitly passes the target service operand to compose up so stopped on-demand services are not
# inadvertently started.
## @description  Sweeps sb_scale, sb_kill, sb_events, sb_sample_resources, and sb_preflight.
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

# Verifies behavior when the container runtime becomes unresponsive, followed by cluster teardown
# and scope deletion. During a runtime outage, every contract function must report operational
# failure (exit code 1) rather than no such object (exit code 6), except sb_exec (exit code 125)
# and the two optional observation verbs sb_events and sb_sample_resources (exit code 5). Teardown
# verification includes leftover container detection and fallback to project-name teardown when the
# compose file has been removed.
## @description  Sweeps unresponsive-runtime exit codes, sb_teardown, and sb_scope_delete.
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

# Exercises every px-cluster subcommand against the fake runtime along with px-run and px-suite
# discovery of the active scope persisted by px-cluster up. Also verifies that a failed bring-up
# automatically tears the cluster down unless --keep is specified.
## @description  Sweeps all px-cluster lifecycle, inspection, and access subcommands.
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

# Exercises the state-mutating px-cluster subcommands: group, restart, signal, scale, kill, down,
# and automatic teardown on a failed up. Single-target mutations record a planned-action row before
# acting, while group switches record a planned-action row for each affected role returned by the
# backend.
## @description  Sweeps mutating px-cluster subcommands, planned-action logging, and teardown.
## @return       Records assertions. Exit 0.
t_group_cluster_faults() {
  local text record="$T_SWEEP_OUT/state/scope-compose-sweep"
  t_pc group start yarn --timeout 5
  t_equals cluster_group_start 0 "$T_STATUS"
  t_contains cluster_group_start_reports_ready 'nodemanager 0 ready' \
    "$(t_squeeze "$T_STDOUT")"
  t_contains cluster_group_start_runs_the_memory_check \
    'the memory check is not installed' "$T_STDERR"
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
  t_equals cluster_down_removes_the_scope_record no \
    "$([ -e "$record" ] && printf yes || printf no)"
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
  t_equals cluster_up_failure_removes_the_scope_record no \
    "$([ -e "$record" ] && printf yes || printf no)"
  t_equals failed_up_removes_the_record no "$(t_exists "$T_SWEEP_OUT/state/cluster-sweep.env")"
  t_pc --keep up "$T_DEPLOY"
  t_equals cluster_up_failure_with_keep_is_failure 1 "$T_STATUS"
  t_equals cluster_up_failure_with_keep_keeps_the_scope yes \
    "$([ -e "$record" ] && printf yes || printf no)"
  t_equals failed_up_with_keep_keeps_the_record yes \
    "$(t_exists "$T_SWEEP_OUT/state/cluster-sweep.env")"
  rm -f "$T_SWEEP_STATE/up-fails"
  t_pc down
  t_equals cluster_down_after_a_kept_failure 0 "$T_STATUS"
  text="$(find "$T_SWEEP_OUT/state" -name 'scope-*' -o -name 'cluster-*.env' 2>/dev/null)"
  t_empty cluster_down_leaves_no_scope_record "$text"
}

# Verifies that executing the entire self-test suite created no new files or directories anywhere
# under the repository working tree root. Because every test command ran with the working tree root
# as its current working directory, any accidental write to a relative path would appear there. A
# synthetic control against a fixture tree first confirms that the diff check detects newly created
# paths.
## @description  Verifies that no command in the test run created paths inside the working tree.
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
# Command-line argument parsing and suite execution
# ---------------------------------------------------------------------------

while getopts ":vkh" T_OPTION; do
  case "$T_OPTION" in
  v) T_VERBOSE=1 ;;
  k) T_KEEP=1 ;;
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
t_group_tree

printf '1..%s\n' "$T_NUMBER"
if [ "$T_FAILED" = 0 ]; then
  printf '# %s assertions, all passed\n' "$T_NUMBER"
  exit "$PX_EXIT_OK"
fi
printf '# %s assertions, %s failed:\n' "$T_NUMBER" "$T_FAILED"
printf '%s' "$T_FAILED_NAMES" | while IFS= read -r T_LINE; do
  [ -z "$T_LINE" ] || printf '# %s\n' "$T_LINE"
done
exit "$PX_EXIT_FAIL"
