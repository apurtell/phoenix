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

# This shared foundation library is sourced by every px-* command to centralize common harness
# facilities that must not be duplicated: message logging, failure handling, configuration loading,
# output policy enforcement, identifier generation, bounded waiting, structured data emission,
# platform fallbacks, and the container-runtime trap.
#
# Four architectural rules govern this module:
#   1. Standard output (stdout) is reserved as a data channel; every diagnostic message goes to
#      standard error (stderr).
#   2. Every wait operation is finite; a caller provides a timeout and receives exit code 4 upon
#      expiration.
#   3. Only lib/backend/compose.sh is permitted to invoke a container runtime; this file traps
#      container runtime names and Kubernetes tool names so that any stray call fails at run time.
#   4. Bash 3.2 is the minimum supported baseline because macOS ships that version; therefore, this
#      file avoids associative arrays, case-modifying expansions, mapfile, and [[ -v ]].

# This file is a library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/common.sh is a library. Source it from a px-* command.\n' >&2
  exit 2
fi

# Guard against redundant sourcing so existing configuration origins and emitter state are kept.
if [ -n "${PX_COMMON_LOADED:-}" ]; then
  return 0
fi
PX_COMMON_LOADED=1

# ---------------------------------------------------------------------------
# Layout and structural constants for the harness workspace.
# ---------------------------------------------------------------------------

PX_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PX_HOME="${PX_HOME:-$(dirname "$PX_LIB_DIR")}"
export PX_HOME

# Tracks the root of the working tree that contains this harness. The output policy refuses to write
# inside this directory so that a test run can never mutate the repository it tests. Discovery
# begins at the physical location of this file rather than PX_HOME so an operator cannot relocate
# the boundary guard with an environment variable. An empty value indicates that this copy of the
# harness lives outside a working tree, such as a test fixture, leaving no repository to protect.
PX_TREE_ROOT=""

# Walks upward from the library directory PX_LIB_DIR and stops at the first ancestor directory that
# carries a repository marker (.git or pom.xml), leaving PX_TREE_ROOT empty if none is found.
## @description  Locates the enclosing working tree root for this harness instance.
## @return       Populates PX_TREE_ROOT (empty when outside a working tree) and exits 0.
px_tree_root_find() {
  local dir=$PX_LIB_DIR
  while [ -n "$dir" ] && [ "$dir" != "/" ]; do
    if [ -e "$dir/.git" ] || [ -f "$dir/pom.xml" ]; then
      PX_TREE_ROOT="$dir"
      return 0
    fi
    dir=${dir%/*}
  done
}
px_tree_root_find

# Within the committed repository layout, the container image definition, the deployment generator,
# and the environment files reside in a single subdirectory under PX_HOME. Defining this internal
# subdirectory name once here keeps path literals out of the configuration loader; an operator who
# needs to relocate an individual configuration stage overrides PX_ENV_DIR or PX_VERSIONS_ENV.
PX_DEPLOY_SUBDIR="docker" # px-allow-docker: a directory name of the layout

PX_PROG="${0##*/}"
if [ "$PX_PROG" = "bash" ] || [ "$PX_PROG" = "sh" ] || [ -z "$PX_PROG" ]; then
  PX_PROG="px"
fi

# Stores the tab and newline delimiters required by the TSV contract in dedicated variables because
# literal tab characters in source text are invisible to readers and easily corrupted during edits.
# Every TSV emitter and parser in the harness references these variables.
PX_TAB=$'\t'
PX_NL=$'\n'

# Shared regular expression patterns used across validation functions.
PX_RE_VARNAME='^[A-Za-z_][A-Za-z0-9_]*$'
PX_RE_INT='^[0-9]+$'
PX_RE_SECONDS='^[0-9]+(\.[0-9]+)?$'
PX_RE_NUMBER='^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$'

# ---------------------------------------------------------------------------
# Shared exit-code vocabulary for harness commands and the substrate contract.
# ---------------------------------------------------------------------------
# Every component in the harness and the substrate contract in lib/backend/contract.sh communicates
# outcomes through these seven exit codes. Callers branch on the numeric status and read the root
# cause from the accompanying diagnostic message or verdict record:
#   0 (ok):             The operation completed as requested, or the evaluated verdict is PASS,
#                       SKIP, or ADVISORY; invoking px-cluster down when no cluster is recorded
#                       also returns 0.
#   1 (failure):        The system under test or the operation failed, covering a FAIL or
#                       INCONCLUSIVE verdict, a failed check or build, or an output that cannot be
#                       written.
#   2 (usage):          The command invocation is invalid, an argument word falls outside a fixed
#                       vocabulary, or a write would land inside the working tree.
#   3 (anomaly):        The outcome cannot be trusted because an instance restarted, was killed, or
#                       exhausted memory, a gate or preflight check refused execution, another
#                       cluster already occupies the host, or the evaluated verdict is ERROR.
#   4 (timeout):        A finite wait bound expired before its target condition held.
#   5 (unsupported):    The active backend lacks the capability, a required tool is absent, or the
#                       execution engine refused the request.
#   6 (no such object): The target cluster, scope, instance, port, bundle, suite, test, group,
#                       image, or configuration file could not be found.
# Exit codes 130 and 143 report termination by INT and TERM signals. Code 1 indicates a defect in
# the system under test or operation, whereas code 3 indicates that the collected evidence or the
# execution environment cannot be trusted.

PX_EXIT_OK=0
PX_EXIT_FAIL=1
PX_EXIT_USAGE=2
PX_EXIT_ANOMALY=3
PX_EXIT_TIMEOUT=4
PX_EXIT_UNSUPPORTED=5
PX_EXIT_NO_OBJECT=6

## @description  Translates an exit code from the shared vocabulary into its human-readable name.
## @param        code The numeric exit status to translate.
## @return       Writes the corresponding exit-code name to stdout and always exits 0.
px_exit_name() {
  case "${1:-}" in
  "$PX_EXIT_OK") printf 'ok\n' ;;
  "$PX_EXIT_FAIL") printf 'failure\n' ;;
  "$PX_EXIT_USAGE") printf 'usage\n' ;;
  "$PX_EXIT_ANOMALY") printf 'anomaly\n' ;;
  "$PX_EXIT_TIMEOUT") printf 'timeout\n' ;;
  "$PX_EXIT_UNSUPPORTED") printf 'unsupported\n' ;;
  "$PX_EXIT_NO_OBJECT") printf 'no such object\n' ;;
  130) printf 'interrupted\n' ;;
  143) printf 'terminated\n' ;;
  *) printf 'undeclared\n' ;;
  esac
}

# Pairs each verdict in the severity lattice (along with INCONCLUSIVE) with its corresponding
# command exit code. Every command and help screen that maps verdicts to exit statuses consults
# this authoritative table.
PX_VERDICT_EXITS="PASS 0 SKIP 0 ADVISORY 0 FAIL 1 INCONCLUSIVE 1 ERROR 3"

## @description  Resolves a verdict word to its corresponding exit code using PX_VERDICT_EXITS.
## @return       Writes the exit code to stdout and exits 0, or returns 6 on an unknown word.
px_verdict_exit() {
  local i=0
  local -a map=()
  read -r -a map <<<"$PX_VERDICT_EXITS"
  while [ "$i" -lt "${#map[@]}" ]; do
    if [ "${map[i]}" = "${1:-}" ]; then
      printf '%s\n' "${map[i + 1]}"
      return 0
    fi
    i=$((i + 2))
  done
  return "$PX_EXIT_NO_OBJECT"
}

## @description  Formats the verdict-to-exit-code mapping as a single phrase for help output,
##               grouping verdicts that share the same exit code.
## @return       Writes the formatted summary phrase to stdout and exits 0.
px_verdict_text() {
  local i=0 last="" text=""
  local -a map=()
  read -r -a map <<<"$PX_VERDICT_EXITS"
  while [ "$i" -lt "${#map[@]}" ]; do
    if [ -z "$text" ]; then
      text=${map[i]}
    elif [ "${map[i + 1]}" = "$last" ]; then
      text="$text, ${map[i]}"
    else
      text="$text $last; ${map[i]}"
    fi
    last=${map[i + 1]}
    i=$((i + 2))
  done
  printf '%s %s\n' "$text" "$last"
}

# ---------------------------------------------------------------------------
# Time measurement and UTC timestamp formatting primitives.
# ---------------------------------------------------------------------------
# Bash 4.2 and newer provide built-in time formatting via printf, whereas Bash 3.2 must fall back
# to invoking the external date utility. The two capability probes below rely exclusively on shell
# builtins and execute once when this library is sourced, allowing functions invoked inside command
# substitutions to reuse the cached results without repeating the probe.

PX_HAVE_PRINTF_TIME=0
PX_DATE_MS_BIN="date"
PX_UTC_TEXT=""
PX_UTC_COMPACT=""
if printf -v PX_UTC_TEXT '%(%s)T' -1 2>/dev/null; then
  PX_HAVE_PRINTF_TIME=1
fi
# GNU date supports millisecond precision and is typically installed on macOS under the name gdate.
if command -v gdate >/dev/null 2>&1; then
  PX_DATE_MS_BIN=gdate
fi

## @description  Reports the current UNIX epoch time in seconds, preferring printf over a fork.
## @return       Writes the current epoch seconds to stdout and exits 0.
px_epoch_s() {
  if [ "$PX_HAVE_PRINTF_TIME" = 1 ]; then
    printf '%(%s)T\n' -1
  else
    date -u '+%s'
  fi
}

# Falls back to second resolution padded with zeros when the host date binary lacks millisecond
# formatting support.
## @description  Reports the current UNIX epoch time in milliseconds.
## @return       Writes the current epoch milliseconds to stdout and exits 0.
px_epoch_ms() {
  local value
  value="$("$PX_DATE_MS_BIN" -u '+%s%3N' 2>/dev/null)" || value=""
  case "$value" in
  '' | *[!0-9]*)
    value="$(px_epoch_s)"
    value="${value}000"
    ;;
  esac
  printf '%s\n' "$value"
}

# Performs pure local integer arithmetic to populate PX_UTC_TEXT (YYYY-MM-DDTHH:MM:SSZ) and
# PX_UTC_COMPACT (YYYYMMDDTHHMMSSZ), ensuring the conversion never depends on the TZ variable.
## @description  Converts an epoch-second timestamp into standard and compact UTC strings.
## @param        epoch_seconds The UNIX epoch time in seconds to convert.
## @return       Sets PX_UTC_TEXT and PX_UTC_COMPACT in the current shell and exits 0.
px_utc_fields() {
  local t=$1 days secs era doe yoe doy mp y m d hh mm ss z
  days=$((t / 86400))
  secs=$((t % 86400))
  hh=$((secs / 3600))
  mm=$((secs / 60 % 60))
  ss=$((secs % 60))
  # Compute the civil calendar date from the day count using the standard 400-year era algorithm.
  z=$((days + 719468))
  era=$((z / 146097))
  doe=$((z - era * 146097))
  yoe=$(((doe - doe / 1460 + doe / 36524 - doe / 146096) / 365))
  y=$((yoe + era * 400))
  doy=$((doe - (365 * yoe + yoe / 4 - yoe / 100)))
  mp=$(((5 * doy + 2) / 153))
  d=$((doy - (153 * mp + 2) / 5 + 1))
  if [ "$mp" -lt 10 ]; then m=$((mp + 3)); else m=$((mp - 9)); fi
  if [ "$m" -le 2 ]; then y=$((y + 1)); fi
  printf -v PX_UTC_TEXT '%04d-%02d-%02dT%02d:%02d:%02dZ' "$y" "$m" "$d" "$hh" "$mm" "$ss"
  printf -v PX_UTC_COMPACT '%04d%02d%02dT%02d%02d%02dZ' "$y" "$m" "$d" "$hh" "$mm" "$ss"
}

# Uses the printf time builtin to avoid forking when available, or falls back to a single date
# invocation on older shells.
## @description  Generates an ISO-8601 UTC timestamp for diagnostic log messages.
## @return       Writes the formatted UTC timestamp to stdout and exits 0.
px_timestamp() {
  local now
  if [ "$PX_HAVE_PRINTF_TIME" = 1 ]; then
    printf -v now '%(%s)T' -1
    px_utc_fields "$now"
    printf '%s\n' "$PX_UTC_TEXT"
  else
    date -u '+%Y-%m-%dT%H:%M:%SZ'
  fi
}

# ---------------------------------------------------------------------------
# Diagnostic message formatting and log file routing.
# ---------------------------------------------------------------------------

# Duplicating log messages to a file is governed by the output policy. When px_log_file_set
# validates and resolves PX_LOG_FILE under the output root, it records the admitted path in
# _PX_LOG_ADMITTED. The px_log function appends to PX_LOG_FILE only while its value matches that
# admitted path, ensuring an unvalidated path never receives log output. Because the admission token
# is exported outside the PX_* namespace, the configuration loader ignores it, while child shells
# that source this library via BASH_ENV (such as test actions or drivers) inherit the parent
# admission and continue teeing log lines to the admitted file.
_PX_LOG_ADMITTED="${_PX_LOG_ADMITTED:-}"

## @description  Formats and writes a diagnostic line to stderr and to an admitted PX_LOG_FILE.
## @param        level, message... The severity label followed by the message tokens.
## @return       Writes the formatted message to stderr (and PX_LOG_FILE if admitted) and exits 0.
px_log() {
  local level=$1 line ts
  shift
  ts="$(px_timestamp)"
  printf -v line '%s %-5s %s: %s' "$ts" "$level" "$PX_PROG" "$*"
  printf '%s\n' "$line" >&2
  if [ -n "$_PX_LOG_ADMITTED" ] && [ "${PX_LOG_FILE:-}" = "$_PX_LOG_ADMITTED" ]; then
    printf '%s\n' "$line" >>"$PX_LOG_FILE" 2>/dev/null || true
  fi
}

## @description  Logs an informational progress message at the INFO severity level.
## @param        message... The progress message text to log.
## @return       Writes the formatted message to stderr and exits 0.
px_info() { px_log INFO "$@"; }

## @description  Logs a warning for a non-fatal condition that does not halt the current operation.
## @param        message... The warning message text to log.
## @return       Writes the formatted message to stderr and exits 0.
px_warn() { px_log WARN "$@"; }

## @description  Logs a failure at the ERROR level while leaving control flow to the caller.
## @param        message... The error message text to log.
## @return       Writes the formatted message to stderr and exits 0.
px_error() { px_log ERROR "$@"; }

# Diagnostic output is gated by PX_VERBOSE so that standard runs remain quiet.
## @description  Logs detailed diagnostic information needed only for troubleshooting.
## @param        message... The diagnostic message text to log.
## @return       Writes the message to stderr when PX_VERBOSE is enabled and exits 0.
px_debug() {
  px_is_verbose || return 0
  px_log DEBUG "$@"
}

# ---------------------------------------------------------------------------
# Boolean predicate helpers for execution switches.
# ---------------------------------------------------------------------------

# Because Bash 3.2 lacks case-modifying parameter expansion, the pattern match explicitly lists both
# uppercase and lowercase character classes.
## @description  Evaluates whether a configuration switch value represents boolean true.
## @param        value The string value to test (1, true, yes, or on, case-insensitively).
## @return       Exits 0 when the value represents true, or exits 1 for any other value.
px_is_true() {
  case "${1:-}" in
  1 | [Tt][Rr][Uu][Ee] | [Yy][Ee][Ss] | [Oo][Nn]) return 0 ;;
  *) return 1 ;;
  esac
}

## @description  Determines whether verbose diagnostic logging is enabled via PX_VERBOSE.
## @return       Exits 0 when PX_VERBOSE is true, or exits 1 otherwise.
px_is_verbose() { px_is_true "${PX_VERBOSE:-0}"; }

## @description  Determines whether the current invocation must perform a non-mutating dry run.
## @return       Exits 0 when PX_DRY_RUN is true, or exits 1 otherwise.
px_is_dry_run() { px_is_true "${PX_DRY_RUN:-0}"; }

# Consulted by cluster teardown and temporary-directory cleanup routines before deleting artifacts.
## @description  Determines whether the operator requested preserving runtime state for diagnosis.
## @return       Exits 0 when PX_KEEP is true, or exits 1 otherwise.
px_is_keep() { px_is_true "${PX_KEEP:-0}"; }

# ---------------------------------------------------------------------------
# Process termination, error trapping, and exit hook management.
# ---------------------------------------------------------------------------

## @description  Logs a fatal error and terminates the process with a shared-vocabulary exit code.
## @param        exit_code, message... The numeric exit code followed by the diagnostic message.
## @return       Writes the error to stderr and exits with exit_code (or 1 if non-numeric).
px_die() {
  local code=${1:-}
  shift
  if ! [[ $code =~ $PX_RE_INT ]]; then
    px_error "px_die needs a numeric exit code, got '$code': $*"
    exit "$PX_EXIT_FAIL"
  fi
  px_error "$* (exit $code, $(px_exit_name "$code"))"
  exit "$code"
}

## @description  Logs a usage error, prints the caller's usage function if defined, and terminates.
## @param        message... The usage error description to report.
## @return       Writes the error and optional usage text to stderr and exits 2.
px_usage_error() {
  px_error "$*"
  if declare -F usage >/dev/null 2>&1; then
    usage >&2
  fi
  exit "$PX_EXIT_USAGE"
}

# Installed via px_trap_enable to log failing commands without forcing immediate termination, so the
# caller retains control of the exit path. Because the ERR trap fires under the same conditions
# where set -e exits, callers that expect a non-zero status (such as exit code 6 from an absence
# probe) must guard the call with "if cmd; then" or "cmd || status=$?" to keep both the trap and
# set -e silent.
## @description  Logs the failing command, source location, and exit status when an ERR trap fires.
## @param        status, line, command The exit status, line number, and failed command string.
## @return       Writes the failure diagnostic to stderr and exits 0.
px_err_trap() {
  local status=$1 line=$2 command=$3 where=${BASH_SOURCE[1]:-unknown}
  px_error "command failed with status $status at $where line $line: $command"
}

# Enables set -E so shell functions inherit the ERR trap, and ensures the EXIT cleanup trap is
# installed; callers that should abort on the first unhandled failure pair this with set -e.
## @description  Arms the ERR diagnostic trap and the EXIT cleanup trap in the calling shell.
## @return       Configures shell trap state and exits 0.
px_trap_enable() {
  set -E
  trap 'px_err_trap "$?" "$LINENO" "$BASH_COMMAND"' ERR
  px_on_exit_install
}

PX_EXIT_HOOKS=""
# Holds the expected exit status and custom closing summary message for commands that supply their
# own final diagnostic line, allowing the exit hook to emit that message in place of the generic
# termination line.
PX_EXIT_NOTED=""
PX_EXIT_NOTE=""
# Stores the process ID of the outermost harness command that parsed a command line. Because
# px_cli_parse sets and exports this variable once, any child command invoked by a harness command
# inherits a different ID and suppresses its own exit line, ensuring that a single exit line closes
# the diagnostic stream of a failing command.
_PX_EXIT_OWNER="${_PX_EXIT_OWNER:-}"
PX_EXIT_HOOKS_INSTALLED=""

# This library owns the process EXIT trap; commands register cleanup actions via px_on_exit rather
# than overwriting the trap directly. Installation occurs at source time at the top level of the
# calling command.
## @description  Installs the centralized harness EXIT trap if it is not already active.
## @return       Registers px_on_exit_run on EXIT and exits 0.
px_on_exit_install() {
  if [ -z "$PX_EXIT_HOOKS_INSTALLED" ]; then
    PX_EXIT_HOOKS_INSTALLED=1
    trap px_on_exit_run EXIT
  fi
}

# Hooks execute in reverse registration order (LIFO) so inner resources are released before outer
# ones. Callers must invoke this from the top-level shell because registrations made inside a
# command substitution vanish when that subshell exits.
## @description  Pushes a cleanup command onto the process exit hook stack.
## @param        command... The cleanup command string to evaluate upon process exit.
## @return       Prepends the hook to PX_EXIT_HOOKS, ensures the EXIT trap is armed, and exits 0.
px_on_exit() {
  PX_EXIT_HOOKS="$*
$PX_EXIT_HOOKS"
  px_on_exit_install
}

# Subshells must not execute registered hooks because the tracked resources belong to the parent
# process that registered them; therefore, this function returns immediately whenever BASH_SUBSHELL
# is non-zero. All hook strings originate from internal harness code rather than operator input.
## @description  Executes all registered exit hooks in reverse order and cleans up temporary dirs.
## @return       Always exits 0 without altering the process exit status.
px_on_exit_run() {
  local hook
  if [ "${BASH_SUBSHELL:-0}" != 0 ]; then
    return 0
  fi
  while IFS= read -r hook; do
    [ -n "$hook" ] || continue
    eval "$hook" || px_debug "exit hook failed: $hook"
  done <<EOF
$PX_EXIT_HOOKS
EOF
  PX_EXIT_HOOKS=""
  px_tmp_cleanup
}

# Prevents a flag with an omitted argument from silently consuming or misinterpreting the remaining
# arguments. Must be invoked directly in the calling shell rather than inside a command substitution
# so that px_usage_error terminates the main process instead of a subshell.
## @description  Validates that a command-line option was supplied with a non-empty argument value.
## @param        option_name, value The option flag name and its candidate value argument.
## @return       Exits 0 when a non-empty value is present, or exits 2 when absent or empty.
px_cli_need_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    px_usage_error "$1 needs a value"
  fi
}

# ---------------------------------------------------------------------------
# In-place string manipulation utilities.
# ---------------------------------------------------------------------------

# Uses pure parameter expansion so parsing large files never forks external processes. The local
# scratch variable uses the reserved name __px_trim so that it cannot collide with and shadow the
# caller's target variable name.
## @description  Strips leading and trailing whitespace from the named shell variable in place.
## @param        variable_name The name of the variable to trim in place.
## @return       Updates the named variable in the caller's scope and exits 0.
px_trim_var() {
  local __px_trim=${!1}
  __px_trim=${__px_trim#"${__px_trim%%[![:space:]]*}"}
  __px_trim=${__px_trim%"${__px_trim##*[![:space:]]}"}
  printf -v "$1" '%s' "$__px_trim"
}

# ---------------------------------------------------------------------------
# Lexical path normalization, physical path resolution, and containment checks.
# ---------------------------------------------------------------------------

# Collapses redundant slashes and "." segments and resolves ".." segments purely through string
# manipulation without accessing the filesystem.
## @description  Lexically normalizes a path into an absolute canonical form.
## @param        path The relative or absolute path string to normalize.
## @return       Writes the lexically normalized absolute path to stdout and exits 0.
px_path_normalize() {
  local path=${1:-} out="" seg had_noglob=""
  case "$path" in
  /*) ;;
  *) path="$PWD/$path" ;;
  esac
  # Because a path segment may contain wildcard characters, disable pathname expansion before
  # splitting on slashes and restore the caller's noglob setting afterward.
  case "$-" in
  *f*) had_noglob=1 ;;
  esac
  set -f
  local IFS=/
  # shellcheck disable=SC2086  # the split on IFS=/ is the point, and -f is set
  set -- $path
  for seg in "$@"; do
    case "$seg" in
    '' | .) ;;
    ..) out=${out%/*} ;;
    *) out="$out/$seg" ;;
    esac
  done
  if [ -z "$had_noglob" ]; then
    set +f
  fi
  printf '%s\n' "${out:-/}"
}

# Follows symbolic links in both the final path component and every intermediate directory. When the
# target path does not yet exist, resolution anchors at its longest existing ancestor directory so
# callers can validate a destination path before creating it.
## @description  Resolves a path to its absolute physical filesystem location.
## @param        path The existing or prospective filesystem path to resolve.
## @return       Writes the physical path to stdout and exits 0, or exits 1 if an ancestor fails.
px_realpath() {
  local path=${1:-} head tail base dir link hops=0
  path="$(px_path_normalize "$path")"
  while [ -L "$path" ] && [ "$hops" -lt 32 ]; do
    link="$(readlink "$path")" || break
    case "$link" in
    /*) path="$link" ;;
    *) path="${path%/*}/$link" ;;
    esac
    path="$(px_path_normalize "$path")"
    hops=$((hops + 1))
  done
  head=$path
  tail=""
  while [ ! -e "$head" ] && [ "$head" != "/" ] && [ -n "$head" ]; do
    base=${head##*/}
    head=${head%/*}
    [ -n "$head" ] || head="/"
    if [ -z "$tail" ]; then tail=$base; else tail="$base/$tail"; fi
  done
  if [ -d "$head" ]; then
    head="$(cd -P -- "$head" 2>/dev/null && pwd -P)" || return 1
  elif [ -e "$head" ]; then
    base=${head##*/}
    dir=${head%/*}
    [ -n "$dir" ] || dir="/"
    dir="$(cd -P -- "$dir" 2>/dev/null && pwd -P)" || return 1
    head="${dir%/}/$base"
  fi
  if [ -n "$tail" ]; then
    printf '%s\n' "${head%/}/$tail"
  else
    printf '%s\n' "$head"
  fi
}

# Walks up the candidate path and compares directory inode identity (-ef) rather than string
# prefixes, ensuring that alternate names for the same directory (such as case variants on a
# case-insensitive volume) still match. Callers should pass a path resolved by px_realpath so that
# no unexpanded symbolic links remain.
## @description  Determines whether an absolute path equals or resides within a target directory.
## @param        absolute_path, directory The resolved path to test and the boundary directory.
## @return       Exits 0 when the path is the directory or lies inside it, or exits 1 otherwise.
px_path_within() {
  local path=${1:-} dir=${2:-}
  [ -n "$dir" ] || return 1
  while :; do
    if [ "$path" -ef "$dir" ]; then
      return 0
    fi
    case "$path" in
    / | '' | [!/]*) return 1 ;;
    esac
    path=${path%/*}
    [ -n "$path" ] || path="/"
  done
}

# ---------------------------------------------------------------------------
# Output directory isolation policy and state directory resolution.
# ---------------------------------------------------------------------------
# Every byte generated during a harness run must be written under PX_OUT. By default, PX_OUT is
# $HOME/phoenix-harness, and it is strictly forbidden from resolving inside the working tree so that
# a test execution can never modify the repository under test.

PX_OUT_RESOLVED=""

# Rejects relative paths, the filesystem root (/), and any location inside PX_TREE_ROOT.
## @description  Validates, resolves, and creates the root output directory PX_OUT.
## @return       Writes the resolved root to stdout and exits 0; exits 2 if refused or 1 on error.
px_out_root() {
  local raw resolved
  # Invocations in the current shell cache the resolved path in PX_OUT_RESOLVED and update PX_OUT
  # so subsequent direct calls skip re-resolution. Invocations inside a command substitution do not
  # persist state to the parent shell, and any later change to PX_OUT invalidates the cached path so
  # a caller never observes a stale output root.
  if [ -n "$PX_OUT_RESOLVED" ] && [ "${PX_OUT:-}" = "$PX_OUT_RESOLVED" ]; then
    printf '%s\n' "$PX_OUT_RESOLVED"
    return 0
  fi
  raw="${PX_OUT:-}"
  if [ -z "$raw" ]; then
    if [ -z "${HOME:-}" ]; then
      px_die "$PX_EXIT_USAGE" "PX_OUT is unset and HOME is unset. Set PX_OUT."
    fi
    raw="$HOME/phoenix-harness"
  fi
  case "$raw" in
  /*) ;;
  *) px_die "$PX_EXIT_USAGE" "PX_OUT must be an absolute path, got '$raw'" ;;
  esac
  resolved="$(px_realpath "$raw")" ||
    px_die "$PX_EXIT_FAIL" "PX_OUT cannot be resolved: $raw"
  if [ "$resolved" = "/" ]; then
    px_die "$PX_EXIT_USAGE" "PX_OUT must not be the filesystem root"
  fi
  if px_path_within "$resolved" "$PX_TREE_ROOT"; then
    px_die "$PX_EXIT_USAGE" \
      "PX_OUT must stay outside the working tree $PX_TREE_ROOT, got '$resolved'"
  fi
  mkdir -p "$resolved" ||
    px_die "$PX_EXIT_FAIL" "PX_OUT cannot be created: $resolved"
  if [ ! -w "$resolved" ]; then
    px_die "$PX_EXIT_FAIL" "PX_OUT is not writable: $resolved"
  fi
  PX_OUT_RESOLVED="$resolved"
  PX_OUT="$resolved"
  export PX_OUT
  printf '%s\n' "$PX_OUT_RESOLVED"
}

# Relative paths are anchored against PX_OUT, and the target path does not need to exist yet. As
# the lowest-level enforcement point for the output isolation policy, sb_copy_out invokes this
# function before every artifact copy. Exits 2 when the path is empty, escapes the output root, or
# lies inside the working tree, exits 1 when the path cannot be resolved, and propagates the status
# of px_out_root when the output root itself is refused (2) or cannot be created (1).
## @description  Resolves and validates a destination path under the output root PX_OUT.
## @param        path The relative or absolute destination path to validate.
## @return       Writes the resolved path to stdout and exits 0, or exits 2 or 1 on failure.
px_out_resolve() {
  local root path resolved status=0
  # Propagate any non-zero status from px_out_root unchanged because it exits with usage code 2 on
  # a policy refusal, and substrate contract callers branch directly on that exit code.
  root="$(px_out_root)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  path=${1:-}
  if [ -z "$path" ]; then
    px_die "$PX_EXIT_USAGE" "px_out_resolve needs a path"
  fi
  case "$path" in
  /*) ;;
  *) path="$root/$path" ;;
  esac
  resolved="$(px_realpath "$path")" ||
    px_die "$PX_EXIT_FAIL" "destination cannot be resolved: $path"
  if ! px_path_within "$resolved" "$root"; then
    px_die "$PX_EXIT_USAGE" "destination escapes PX_OUT ($root): $resolved"
  fi
  # Even when PX_OUT lies above PX_TREE_ROOT in the directory hierarchy, a subpath under PX_OUT
  # could still point into the working tree and must be explicitly rejected.
  if px_path_within "$resolved" "$PX_TREE_ROOT"; then
    px_die "$PX_EXIT_USAGE" \
      "destination lies inside the working tree $PX_TREE_ROOT: $resolved"
  fi
  printf '%s\n' "$resolved"
}

# Once admitted, the target file receives a copy of every message logged by this process. Exporting
# PX_LOG_FILE passes the path to child px-* commands (which re-admit it via px_config_load), while
# child shells that source this file without running configuration loading inherit _PX_LOG_ADMITTED
# from the parent. Invoke this directly in the current shell so the admission persists for
# subsequent log calls.
## @description  Validates and admits a log file path under the output isolation policy.
## @param        path The log file destination, either relative to PX_OUT or absolute.
## @return       Exits 0 on admission, or returns px_out_resolve status (2 on violation, 1 on fail).
px_log_file_set() {
  local resolved status=0
  resolved="$(px_out_resolve "${1:-}")" || status=$?
  if [ "$status" != 0 ]; then
    _PX_LOG_ADMITTED=""
    return "$status"
  fi
  PX_LOG_FILE="$resolved"
  _PX_LOG_ADMITTED="$resolved"
  export PX_LOG_FILE _PX_LOG_ADMITTED
}

## @description  Validates, resolves, and creates a subdirectory under the output root PX_OUT.
## @param        relative_or_absolute_path The directory path to resolve and create under PX_OUT.
## @return       Writes the resolved directory to stdout and exits 0, or exits 1 if mkdir fails.
px_out_subdir() {
  local dir status=0
  dir="$(px_out_resolve "${1:-}")" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  mkdir -p "$dir" || px_die "$PX_EXIT_FAIL" "cannot create directory: $dir"
  printf '%s\n' "$dir"
}

# Backends store their scope records, capability caches, and port-forwarding records in this
# directory so that no runtime state ever resides inside the working tree. An operator may relocate
# the directory via PX_STATE_DIR provided the destination still resolves inside PX_OUT.
## @description  Resolves and creates the backend runtime state directory under the output root.
## @return       Writes the directory path to stdout and exits 0, or exits 2 if outside PX_OUT.
px_state_dir() {
  if [ -n "${PX_STATE_DIR:-}" ]; then
    px_out_subdir "$PX_STATE_DIR"
  else
    px_out_subdir "state"
  fi
}

# ---------------------------------------------------------------------------
# Sortable run identifier generation and exclusive run directory creation.
# ---------------------------------------------------------------------------

PX_ID_SEQ=0
PX_BASE36="0123456789abcdefghijklmnopqrstuvwxyz"

## @description  Formats a non-negative integer in base 36, zero-padded to a fixed character width.
## @param        value, width The non-negative integer to encode and the required output width.
## @return       Writes the base-36 digit string to stdout and exits 0.
px_base36() {
  local value=$1 width=$2 out="" digit
  while [ "$width" -gt 0 ]; do
    digit=$((value % 36))
    out="${PX_BASE36:$digit:1}$out"
    value=$((value / 36))
    width=$((width - 1))
  done
  printf '%s\n' "$out"
}

# Formats each identifier as a compact UTC timestamp, a hyphen, and a four-character base-36 suffix
# composed of one process-ID digit, two random digits, and one per-shell sequence counter digit.
# The sequence counter distinguishes successive calls in the same shell, while the random digits
# separate calls executed inside command substitutions (where subshell counter increments do not
# persist to the caller); px_run_dir closes any remaining collision window via atomic mkdir.
## @description  Generates a chronologically sortable unique run identifier.
## @return       Writes the generated run identifier to stdout and exits 0.
px_run_id() {
  local now a b c
  now="$(px_epoch_s)"
  px_utc_fields "$now"
  PX_ID_SEQ=$((PX_ID_SEQ + 1))
  a="$(px_base36 $(($$ % 36)) 1)"
  b="$(px_base36 $((RANDOM % 1296)) 2)"
  c="$(px_base36 $((PX_ID_SEQ % 36)) 1)"
  printf '%s-%s%s%s\n' "$PX_UTC_COMPACT" "$a" "$b" "$c"
}

# Uses atomic directory creation (mkdir without -p) across up to 16 attempts so concurrent
# processes starting within the same second cannot share a run directory.
## @description  Allocates and exclusively creates a unique run directory under PX_OUT/runs.
## @return       Writes the new directory path to stdout and exits 0, or exits 1 if none is free.
px_run_dir() {
  local runs id dir attempt=0 status=0
  runs="$(px_out_subdir "runs")" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  while [ "$attempt" -lt 16 ]; do
    id="$(px_run_id)"
    dir="$runs/$id"
    if mkdir "$dir" 2>/dev/null; then
      printf '%s\n' "$dir"
      return 0
    fi
    attempt=$((attempt + 1))
  done
  px_die "$PX_EXIT_FAIL" "no free run identifier under $runs after 16 attempts"
}

# ---------------------------------------------------------------------------
# Multi-stage configuration loader, variable registry validation, and provenance tracking.
# ---------------------------------------------------------------------------
# Configuration is managed by a single loader that enforces one strict precedence hierarchy and
# records the provenance of every variable. Stages are evaluated from highest to lowest priority,
# and the first stage that defines a variable wins:
#   1. The inherited process environment.
#   2. Any explicit configuration files specified on the command line.
#   3. Local environment overrides in <layout>/env/local.env.
#   4. Run-class default settings in <layout>/env/class-<class>.env.
#   5. Baseline default settings in <layout>/env/defaults.env (mandatory).
#   6. Component version definitions in <layout>/versions.env.
#   7. Local version overrides in <layout>/versions.local.env.
#   8. The generated port map in <deployment>/ports.generated.env.
# Because lower-priority stages never overwrite values established by earlier stages, explicit
# overrides always prevail over defaults. Consequently, stage 7 (<layout>/versions.local.env) only
# supplies version pins that are absent from stage 6 (<layout>/versions.env), which is how
# trust-on-first-use pins are recorded; an operator who wishes to override an already-declared pin
# places the override in local.env (stage 3).
#
# The deployment generator writes ports.generated.env into the deployment directory under PX_OUT,
# ensuring that stage 8 never reads generated state from the working tree. Stage 8 runs
# automatically during px_config_load whenever PX_DEPLOYMENT_DIR (or PX_PORT_MAP_ENV) is already
# known; commands that discover the deployment directory later invoke px_config_load_ports
# directly, which preserves the precedence order because stage 8 is the lowest-priority stage.
#
# Environment files may contain only KEY=VALUE assignments, comments, and blank lines. The loader
# parses each file as literal data without sourcing it or performing shell expansion, preventing
# configuration files from executing arbitrary commands.

PX_CONFIG_ORIGINS=""
PX_CONFIG_STRICT=0
PX_VARNAMES=""
PX_VARNAMES_PREFIXES=""
PX_VARNAMES_PRIVATE=""
PX_VARNAMES_READY=""

# The variable registry enables strict mode to fail closed on misspelled PX_* names. Each entry
# declares a single variable name, or a prefix family when terminated by an asterisk (*); a
# trailing "private" keyword prevents the variable from being exported to child processes.
## @description  Loads the declared registry of recognized PX_* configuration variable names.
## @return       Exits 0 when loaded (or skipped in lax mode), or exits 1 in strict mode if missing.
px_varnames_load() {
  local file line name
  [ -z "$PX_VARNAMES_READY" ] || return 0
  file="${PX_VARNAMES_FILE:-$PX_HOME/lib/varnames.txt}"
  # Do not mark the registry as loaded when the file is absent so that a subsequent strict-mode
  # invocation still fails closed.
  if [ ! -f "$file" ]; then
    if [ "$PX_CONFIG_STRICT" = 1 ]; then
      px_die "$PX_EXIT_FAIL" "strict configuration needs the registry $file"
    fi
    px_debug "no variable registry at $file, strict checks stay off"
    return 0
  fi
  PX_VARNAMES_READY=1
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%%#*}
    px_trim_var line
    [ -n "$line" ] || continue
    name=${line%%[![:graph:]]*}
    line=${line#"$name"}
    px_trim_var line
    if [ "$line" = private ]; then
      PX_VARNAMES_PRIVATE="$PX_VARNAMES_PRIVATE $name "
    fi
    case "$name" in
    *\*) PX_VARNAMES_PREFIXES="$PX_VARNAMES_PREFIXES ${name%\*}" ;;
    *) PX_VARNAMES="$PX_VARNAMES
$name" ;;
    esac
  done <"$file"
  PX_VARNAMES="$PX_VARNAMES
"
}

## @description  Checks whether a configuration variable name matches the declared registry.
## @param        name The configuration variable name to look up.
## @return       Exits 0 when the variable name is declared, or exits 1 otherwise.
px_config_known() {
  local name=${1:-} prefix
  px_varnames_load
  [ -n "$PX_VARNAMES" ] || return 0
  case "$PX_VARNAMES" in
  *"
$name
"*) return 0 ;;
  esac
  for prefix in $PX_VARNAMES_PREFIXES; do
    case "$name" in
    "$prefix"*) return 0 ;;
    esac
  done
  return 1
}

# When a variable was already set by a higher-precedence stage, the existing value is retained and
# logged at debug level. Accepted values are exported so generators, child px-* commands, and
# containers share the same environment, except for variables marked "private" in the registry,
# which remain local to the current process. Exits 2 on an invalid identifier, an undeclared PX_*
# name under strict mode, or a read-only harness constant.
## @description  Assigns a configuration variable if unset and records its stage origin.
## @param        name, value, origin The variable name, literal value, and source stage label.
## @return       Exits 0 on success, or exits 2 on an invalid, undeclared, or read-only name.
px_config_set() {
  local name=${1:-} value=${2:-} origin=${3:-caller} previous attributes
  if ! [[ $name =~ $PX_RE_VARNAME ]]; then
    px_die "$PX_EXIT_USAGE" "invalid variable name '$name' from $origin"
  fi
  px_varnames_load
  if [ "$PX_CONFIG_STRICT" = 1 ]; then
    case "$name" in
    PX_*)
      px_config_known "$name" ||
        px_die "$PX_EXIT_USAGE" \
          "undeclared name '$name' from $origin. Add it to lib/varnames.txt."
      ;;
    *)
      px_die "$PX_EXIT_USAGE" \
        "name '$name' from $origin is outside the PX_ namespace"
      ;;
    esac
  fi
  # Because px_config_origin runs inside a command substitution, an absent variable returns exit
  # code 6 as the subshell status; guarding it with "|| true" inside the substitution prevents an
  # armed ERR trap in the caller from firing during the normal first assignment of a variable.
  previous="$(px_config_origin "$name" || true)"
  if [ -n "$previous" ]; then
    px_debug "keep $name from $previous, $origin not applied"
    return 0
  fi
  # Read-only variables represent internal harness constants. Because Bash 3.2 under set -e aborts
  # the shell when printf -v targets a read-only variable even inside an "||" list, inspect the
  # variable attributes via declare -p prior to assignment.
  attributes="$(declare -p "$name" 2>/dev/null || true)"
  attributes=${attributes#declare -}
  case "${attributes%% *}" in
  *r*)
    px_die "$PX_EXIT_USAGE" \
      "$name from $origin is a constant of the harness, not a configuration value"
    ;;
  esac
  printf -v "$name" '%s' "$value"
  case "$PX_VARNAMES_PRIVATE" in
  *" $name "*) export -n "${name?}" ;;
  *) export "${name?}" ;;
  esac
  PX_CONFIG_ORIGINS="$PX_CONFIG_ORIGINS$name$PX_TAB$origin
"
}

## @description  Reports the configuration stage origin that established a variable's value.
## @param        name The configuration variable name to query.
## @return       Writes the origin label to stdout and exits 0, or exits 6 if never set by loader.
px_config_origin() {
  local name=${1:-} line
  case "$PX_CONFIG_ORIGINS" in
  "$name$PX_TAB"* | *"
$name$PX_TAB"*) ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
  while IFS= read -r line; do
    case "$line" in
    "$name$PX_TAB"*)
      printf '%s\n' "${line#*"$PX_TAB"}"
      return 0
      ;;
    esac
  done <<EOF
$PX_CONFIG_ORIGINS
EOF
  return "$PX_EXIT_NO_OBJECT"
}

# Accepts only comments, blank lines, and literal KEY=VALUE assignments while rejecting all other
# syntax (including "export" prefixes) so that shell constructs in data files fail closed instead
# of executing.
## @description  Parses a single environment data file and applies its assignments to a stage.
## @param        path, origin The filesystem path of the environment file and its stage origin tag.
## @return       Exits 0 on success, returns 6 when the file is absent, or exits 2 on invalid lines.
px_config_parse_file() {
  local path=$1 origin=$2 line name value lineno=0
  [ -f "$path" ] || return "$PX_EXIT_NO_OBJECT"
  px_debug "configuration stage $origin reads $path"
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    line=${line%$'\r'}
    px_trim_var line
    case "$line" in
    '' | '#'*) continue ;;
    export\ *)
      px_die "$PX_EXIT_USAGE" \
        "$path line $lineno: an environment file holds KEY=VALUE, not export"
      ;;
    esac
    name=${line%%=*}
    if [ "$name" = "$line" ]; then
      px_die "$PX_EXIT_USAGE" "$path line $lineno: not an assignment: $line"
    fi
    value=${line#*=}
    px_trim_var name
    px_trim_var value
    if ! [[ $name =~ $PX_RE_VARNAME ]]; then
      px_die "$PX_EXIT_USAGE" "$path line $lineno: invalid name: $name"
    fi
    case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
    esac
    px_config_set "$name" "$value" "$origin"
  done <"$path"
  return 0
}

# Empty environment values are treated as unset so that an empty export in the caller's shell
# cannot accidentally shadow a baseline default.
## @description  Captures all non-empty exported PX_* variables as the highest-precedence stage.
## @return       Records inherited environment variables under the "environment" origin and exits 0.
px_config_stage_env() {
  local line name value
  while IFS= read -r line; do
    name=${line%%=*}
    [ "$name" != "$line" ] || continue
    case "$name" in
    PX_*) ;;
    *) continue ;;
    esac
    [[ $name =~ $PX_RE_VARNAME ]] || continue
    value=${line#*=}
    [ -n "$value" ] || continue
    px_config_set "$name" "$value" environment
  done <<EOF
$(env)
EOF
}

# Evaluates all configuration stages in precedence order, requiring only the baseline defaults file
# (defaults.env) to be present. Accepts --strict to reject undeclared PX_* names, --lax (the
# default) to permit them, and any number of explicit file arguments (evaluated in the order given
# as the highest-precedence file stage, such as for --config or a target environment file). Fails
# with exit code 2 on malformed lines, undeclared variables in strict mode, late definitions of
# PX_RUN_CLASS or PX_STRICT_CONFIG, or a PX_LOG_FILE path rejected by the output policy.
## @description  Executes the full multi-stage configuration loading sequence in precedence order.
## @param        [--strict|--lax] [file...] Optional strictness flags followed by config file paths.
## @return       Exits 0 on success, 6 if a named file is missing, 1 if defaults are missing, or 2.
px_config_load() {
  local file env_dir class_file had_class=0
  while [ $# -gt 0 ]; do
    case "$1" in
    --strict) PX_CONFIG_STRICT=1 ;;
    --lax) PX_CONFIG_STRICT=0 ;;
    --) shift; break ;;
    -*) px_die "$PX_EXIT_USAGE" "px_config_load: unknown option $1" ;;
    *) break ;;
    esac
    shift
  done
  if px_is_true "${PX_STRICT_CONFIG:-0}"; then
    PX_CONFIG_STRICT=1
  fi
  px_varnames_load
  px_config_stage_env
  for file in "$@"; do
    px_config_parse_file "$file" "$file" ||
      px_die "$PX_EXIT_NO_OBJECT" "configuration file not found: $file"
  done
  env_dir="${PX_ENV_DIR:-$PX_HOME/$PX_DEPLOY_SUBDIR/env}"
  px_config_set PX_ENV_DIR "$env_dir" derived
  env_dir="$PX_ENV_DIR"
  px_config_parse_file "${PX_LOCAL_ENV:-$env_dir/local.env}" local || true
  if [ -n "${PX_RUN_CLASS:-}" ]; then
    had_class=1
    class_file="${PX_CLASS_ENV:-$env_dir/class-$PX_RUN_CLASS.env}"
    px_config_parse_file "$class_file" "class:$PX_RUN_CLASS" || true
  fi
  file="${PX_DEFAULTS_ENV:-$env_dir/defaults.env}"
  px_config_parse_file "$file" defaults ||
    px_die "$PX_EXIT_FAIL" "the baseline defaults are mandatory: $file"
  px_config_parse_file "${PX_VERSIONS_ENV:-$PX_HOME/$PX_DEPLOY_SUBDIR/versions.env}" \
    versions || true
  px_config_parse_file \
    "${PX_VERSIONS_LOCAL_ENV:-$PX_HOME/$PX_DEPLOY_SUBDIR/versions.local.env}" \
    versions-local || true
  if [ -n "${PX_PORT_MAP_ENV:-}" ] || [ -n "${PX_DEPLOYMENT_DIR:-}" ]; then
    px_config_load_ports "${PX_DEPLOYMENT_DIR:-}"
  fi
  # Because the run-class file is evaluated before baseline defaults, defining PX_RUN_CLASS in
  # defaults.env or a later stage arrives too late to take effect and is rejected rather than
  # silently ignored.
  if [ "$had_class" = 0 ] && [ -n "${PX_RUN_CLASS:-}" ]; then
    class_file="${PX_CLASS_ENV:-$env_dir/class-$PX_RUN_CLASS.env}"
    if [ -f "$class_file" ]; then
      px_die "$PX_EXIT_USAGE" \
        "name the run class on the command line or in the environment, not in $file"
    fi
  fi
  # Because strict variable validation must be active before stage 1 runs, enabling PX_STRICT_CONFIG
  # inside a configuration file arrives too late and is rejected rather than silently ignored.
  if [ "$PX_CONFIG_STRICT" = 0 ] && px_is_true "${PX_STRICT_CONFIG:-0}"; then
    px_die "$PX_EXIT_USAGE" "set PX_STRICT_CONFIG in the environment, not in a file," \
      "because strict mode must apply before the first stage"
  fi
  if [ -n "${PX_LOG_FILE:-}" ]; then
    px_log_file_set "$PX_LOG_FILE" ||
      px_die "$?" "the output policy refuses PX_LOG_FILE '$PX_LOG_FILE'"
  fi
  return 0
}

# Reads PX_PORT_MAP_ENV when explicitly set, or falls back to ports.generated.env inside the
# supplied deployment directory. Invoked automatically by px_config_load when PX_DEPLOYMENT_DIR is
# known upfront, or called directly by commands that determine the deployment directory later.
## @description  Loads stage 8 of configuration from the generated deployment port map.
## @param        deployment_directory The deployment directory path (optional if PX_PORT_MAP_ENV).
## @return       Exits 0 (including when the file is absent), or exits 2 on syntax/strict errors.
px_config_load_ports() {
  local dir=${1:-} file
  file="${PX_PORT_MAP_ENV:-}"
  if [ -z "$file" ]; then
    [ -n "$dir" ] || return 0
    file="$dir/ports.generated.env"
  fi
  if ! px_config_parse_file "$file" ports; then
    px_debug "no port map at $file"
  fi
  return 0
}

# Provides the primary diagnostic view for troubleshooting configuration precedence surprises.
## @description  Emits all loaded configuration variables, their values, and their origins sorted.
## @return       Writes TSV rows of (name, value, origin) to stdout and exits 0.
px_config_dump() {
  local line name
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    name=${line%%"$PX_TAB"*}
    px_tsv_row "$name" "${!name:-}" "${line#*"$PX_TAB"}"
  done <<EOF
$(printf '%s' "$PX_CONFIG_ORIGINS" | sort)
EOF
}

# ---------------------------------------------------------------------------
# Bounded polling and retry execution primitives.
# ---------------------------------------------------------------------------
# Every wait operation in the harness has a finite bound. When a wait expires, it returns exit code
# 4 (PX_EXIT_TIMEOUT), leaving the caller to decide whether expiration constitutes a hard failure
# or a skipped condition.

## @description  Polls a command at a fixed interval until it succeeds or the timeout expires.
## @param        timeout_s, interval_s, command... Timeout in seconds, poll interval, and command.
## @return       Exits 0 when the command succeeds, returns 4 on expiry, or exits 2 on usage error.
px_wait_until() {
  local timeout=${1:-} interval=${2:-} deadline now
  shift 2
  if ! [[ $timeout =~ $PX_RE_INT ]] || ! [[ $interval =~ $PX_RE_SECONDS ]]; then
    px_die "$PX_EXIT_USAGE" "px_wait_until needs a timeout and an interval"
  fi
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "px_wait_until needs a command"
  now="$(px_epoch_s)"
  deadline=$((now + timeout))
  while :; do
    if "$@"; then
      return 0
    fi
    now="$(px_epoch_s)"
    if [ "$now" -ge "$deadline" ]; then
      px_debug "wait expired after ${timeout}s: $*"
      return "$PX_EXIT_TIMEOUT"
    fi
    sleep "$interval"
  done
}

## @description  Executes a command up to a bounded number of attempts with a delay between tries.
## @param        attempts, sleep_s, command... Maximum attempt count (>= 1), pause seconds, and cmd.
## @return       Returns 0 on success, the last attempt's exit status on exhaustion, or 2 on usage.
px_retry() {
  local attempts=${1:-} pause=${2:-} attempt=1 status=0
  shift 2
  if ! [[ $attempts =~ $PX_RE_INT ]] || [ "$attempts" -lt 1 ]; then
    px_die "$PX_EXIT_USAGE" "px_retry needs an attempt count of 1 or more"
  fi
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "px_retry needs a command"
  while :; do
    # Capture the command exit code via "|| status=$?" because after an "if" statement, $? reflects
    # the status of the overall compound statement rather than the failed condition.
    status=0
    "$@" || status=$?
    if [ "$status" = 0 ]; then
      return 0
    fi
    if [ "$attempt" -ge "$attempts" ]; then
      px_debug "attempt $attempt of $attempts failed with $status: $*"
      return "$status"
    fi
    px_debug "attempt $attempt of $attempts failed with $status, retry in ${pause}s"
    attempt=$((attempt + 1))
    sleep "$pause"
  done
}

# ---------------------------------------------------------------------------
# Cross-platform compatibility wrappers for macOS and Linux differences.
# ---------------------------------------------------------------------------
# Operating system differences between macOS and Linux are encapsulated behind dedicated helper
# functions in this section so that no other script in the harness needs platform conditionals.

PX_TMP_SEQ=0

# Fixed grace period in seconds between sending SIGTERM and escalating to SIGKILL when terminating
# an expired command. This is an internal harness constant rather than a configurable parameter.
PX_TIMEOUT_GRACE_S=2

# Holds the resolved absolute path to a GNU-compatible timeout executable. Only binaries supporting
# GNU flags (--foreground and -k) qualify, because --foreground keeps the controlling terminal and
# its signals attached to the child command. Storing an absolute path avoids invoking the "command"
# builtin, which in Bash 3.2 triggers the ERR trap on failure even inside an "||" list. This probe
# runs once at source time so calls inside command substitutions reuse the cached path.
PX_TIMEOUT_BIN=""

## @description  Probes PATH for a timeout executable (gtimeout or timeout) supporting GNU options.
## @return       Sets PX_TIMEOUT_BIN to the executable path (or empty if unavailable) and exits 0.
px_timeout_probe() {
  local candidate path
  for candidate in gtimeout timeout; do
    path="$(type -P "$candidate" 2>/dev/null)" || continue
    [ -n "$path" ] || continue
    if "$path" --foreground -k 1 5 true >/dev/null 2>&1; then
      PX_TIMEOUT_BIN=$path
      return 0
    fi
  done
}
px_timeout_probe

# Preserves the caller's stdin, controlling terminal, and terminal signals while enforcing a time
# limit. Prefers a GNU timeout binary when available: when stdin is not a terminal, timeout
# expiration terminates the entire process group of the command, whereas when stdin is a terminal
# (and on the shell watchdog fallback path), expiration terminates only the immediate command
# process. When no GNU timeout binary is installed, the command runs in the foreground while a
# background watchdog process enforces the deadline. Returns 124 on SIGTERM expiry or 137 when the
# command outlives the grace period after SIGTERM and takes SIGKILL; the watchdog path returns 125
# when it cannot start the command, exits 1 when it cannot create its mark file, and exits 2 on
# invalid usage. As with external timeout binaries, the command must be an executable program and
# not a shell function.
## @description  Executes an external command under a finite timeout in whole seconds.
## @param        timeout_s, command... Positive integer timeout in seconds and the command to run.
## @return       Returns command status, 124 on expiry, 137 on KILL, 125 on start error, or 1 or 2.
px_timeout() {
  local timeout=${1:-} mark status=0 watch_pid line expiry=""
  shift
  if ! [[ $timeout =~ $PX_RE_INT ]] || [ "$timeout" -lt 1 ]; then
    px_die "$PX_EXIT_USAGE" "px_timeout needs a whole number of seconds above zero"
  fi
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "px_timeout needs a command"
  if [ -n "$PX_TIMEOUT_BIN" ]; then
    # When standard input is a terminal, keep the command in the foreground process group so it
    # retains terminal access and signals; otherwise, run it in a separate process group so timeout
    # expiration also terminates any child processes it spawned.
    if [ -t 0 ]; then
      "$PX_TIMEOUT_BIN" --foreground -k "$PX_TIMEOUT_GRACE_S" "$timeout" "$@" ||
        status=$?
    else
      "$PX_TIMEOUT_BIN" -k "$PX_TIMEOUT_GRACE_S" "$timeout" "$@" || status=$?
    fi
    return "$status"
  fi
  mark="$(mktemp "${TMPDIR:-/tmp}/px.timeout.XXXXXXXX")" ||
    px_die "$PX_EXIT_FAIL" "cannot create the mark file of a timeout"
  px_timeout_watch "$timeout" "$mark" >/dev/null 2>&1 &
  watch_pid=$!
  # The child shell records its own PID in the mark file and replaces itself via exec so the
  # watchdog signals the command directly. File descriptor 3 routes the command's stderr to the
  # caller's stderr while redirecting the outer shell's signal-termination notice to /dev/null,
  # since the numeric expiry exit status already communicates the timeout.
  {
    "${BASH:-bash}" -c "printf '%s\n' \"\$\$\" >\"\$1\" || exit 125; shift; exec \"\$@\"" \
      px-timeout "$mark" "$@" 2>&3 3>&- || status=$?
  } 3>&2 2>/dev/null
  kill -s TERM "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
  # The first line of the mark file records the target PID; any subsequent lines record the expiry
  # exit statuses written by the watchdog prior to sending signals, with the final line winning.
  {
    IFS= read -r line || true
    while IFS= read -r line; do
      expiry=$line
    done
  } <"$mark"
  rm -f "$mark"
  if [ -n "$expiry" ]; then
    status=$expiry
  fi
  return "$status"
}

# Runs in the background to enforce a command deadline: upon expiration it appends 124 to the mark
# file and sends SIGTERM, waits PX_TIMEOUT_GRACE_S seconds, and if the process still exists appends
# 137 and sends SIGKILL. Receiving SIGTERM from px_timeout aborts the watchdog and its active sleep
# immediately.
## @description  Background watchdog that signals an expired command after its timeout elapses.
## @param        timeout_s, mark_file The timeout in seconds and mark file holding the target PID.
## @return       Always exits 0.
px_timeout_watch() {
  local timeout=$1 mark=$2 pid="" nap="" stop=""
  # The SIGTERM trap terminates any active background sleep with SIGKILL and reaps it. Because a
  # newly forked child shell briefly shares the parent's signal handlers before execing sleep, a
  # SIGTERM sent to that child could be caught and ignored; moreover, SIGTERM from px_timeout may
  # arrive after sleep starts but before $! is assigned to nap, in which case the trap sets stop=1
  # and px_timeout_nap kills the sleep process.
  trap 'stop=1; [ -z "$nap" ] || { kill -s KILL "$nap"; wait "$nap"; exit 0; }' TERM
  px_timeout_nap "$timeout"
  IFS= read -r pid <"$mark" || true
  # Exit immediately if px_timeout cancelled the watchdog between stages, if no PID was recorded,
  # or if the parent shell has already exited (since the recorded PID could be reused by the OS).
  if [ -n "$stop" ] || [ -z "$pid" ] || ! kill -s 0 "$$" 2>/dev/null; then
    exit 0
  fi
  printf '124\n' >>"$mark"
  kill -s TERM "$pid" 2>/dev/null || exit 0
  px_timeout_nap "$PX_TIMEOUT_GRACE_S"
  kill -s 0 "$pid" 2>/dev/null || exit 0
  printf '137\n' >>"$mark"
  kill -s KILL "$pid" 2>/dev/null || true
  exit 0
}

# Records the background sleep PID in nap and checks the watchdog stop flag. If px_timeout sent
# SIGTERM before nap was assigned, this helper kills and reaps the sleep process and exits the
# watchdog so no orphaned sleep process outlives the timeout call.
## @description  Executes an interruptible background sleep stage for px_timeout_watch.
## @param        seconds The duration in seconds to sleep.
## @return       Returns 0 when the sleep completes, or exits the watchdog with 0 if stopped.
px_timeout_nap() {
  sleep "$1" &
  nap=$!
  if [ -z "$stop" ]; then
    wait "$nap" || true
  fi
  if [ -n "$stop" ]; then
    kill -s KILL "$nap" 2>/dev/null || true
    wait "$nap" 2>/dev/null || true
    exit 0
  fi
  nap=""
}

# Tracks created temporary directories in a per-process file rather than a shell variable because
# callers invoke px_mktemp_dir inside command substitutions where variable mutations would be lost
# when the subshell exits. Including $$ in the filename ensures each process removes only the
# directories it created.
## @description  Computes the filesystem path of the current process's temporary-directory registry.
## @return       Writes the registry file path to stdout and exits 0.
px_tmp_registry() {
  local root=${TMPDIR:-/tmp}
  printf '%s/px.tmpdirs.%s\n' "${root%/}" "$$"
}

# Directories recorded in the process registry are removed automatically on exit unless PX_KEEP is
# enabled for post-mortem diagnosis.
## @description  Creates a temporary directory and registers it for automatic cleanup on exit.
## @param        label An optional prefix label incorporated into the temporary directory name.
## @return       Writes the created directory path to stdout and exits 0, or exits 1 on failure.
px_mktemp_dir() {
  local label=${1:-px} dir root=${TMPDIR:-/tmp}
  dir="$(mktemp -d "${root%/}/$label.XXXXXXXX")" ||
    px_die "$PX_EXIT_FAIL" "cannot create a temporary directory"
  printf '%s\n' "$dir" >>"$(px_tmp_registry)"
  printf '%s\n' "$dir"
}

# Invoked automatically by the EXIT trap; idempotent and safe to call multiple times.
## @description  Deletes all temporary directories registered by the current process unless PX_KEEP.
## @return       Removes registered temporary directories (if PX_KEEP is unset) and exits 0.
px_tmp_cleanup() {
  local dir registry
  registry="$(px_tmp_registry)"
  [ -f "$registry" ] || return 0
  if px_is_keep; then
    px_debug "PX_KEEP is set, temporary directories stay, listed in $registry"
    return 0
  fi
  while IFS= read -r dir; do
    [ -n "$dir" ] || continue
    rm -rf "$dir" || px_debug "cannot remove $dir"
  done <"$registry"
  rm -f "$registry"
}

# Probes GNU stat (-c %s) first and falls back to BSD stat (-f %z) only when the GNU flag fails.
## @description  Reports the size of a filesystem entry in bytes across GNU and BSD platforms.
## @param        path The filesystem path to inspect.
## @return       Writes the byte size to stdout and exits 0, returns 6 if absent, or 1 on failure.
px_stat_size() {
  local path=${1:-}
  [ -e "$path" ] || return "$PX_EXIT_NO_OBJECT"
  if stat -c %s "$path" 2>/dev/null || stat -f %z "$path" 2>/dev/null; then
    return 0
  fi
  px_error "no usable stat on this host"
  return "$PX_EXIT_FAIL"
}

# Because BSD sed and GNU sed require incompatible syntax for the -i flag, this function writes the
# transformed output to a temporary sibling file and overwrites the target via cat redirection,
# preserving both the permissions mode and the inode of the original file.
## @description  Applies a sed script to one or more files in place without relying on sed -i.
## @param        expression, file... The sed expression to apply followed by one or more file paths.
## @return       Exits 0 when all files are updated in place, or returns 1 when an edit fails.
px_sed_inplace() {
  local expression=${1:-} file tmp
  shift
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "px_sed_inplace needs a file"
  for file in "$@"; do
    if [ ! -f "$file" ]; then
      px_error "no such file: $file"
      return "$PX_EXIT_FAIL"
    fi
    PX_TMP_SEQ=$((PX_TMP_SEQ + 1))
    tmp="$file.px.$$.$PX_TMP_SEQ"
    if ! sed -e "$expression" "$file" >"$tmp"; then
      rm -f "$tmp"
      px_error "sed failed on $file"
      return "$PX_EXIT_FAIL"
    fi
    if ! cat "$tmp" >"$file"; then
      rm -f "$tmp"
      px_error "cannot write $file"
      return "$PX_EXIT_FAIL"
    fi
    rm -f "$tmp"
  done
}

## @description  Reports the number of online logical processors available on the host.
## @return       Writes the processor count (defaulting to 1) to stdout and exits 0.
px_nproc() {
  local count=""
  count="$(getconf _NPROCESSORS_ONLN 2>/dev/null)" || count=""
  case "$count" in
  '' | *[!0-9]*) count="" ;;
  esac
  if [ -z "$count" ] && command -v sysctl >/dev/null 2>&1; then
    count="$(sysctl -n hw.ncpu 2>/dev/null)" || count=""
  fi
  case "$count" in
  '' | *[!0-9]*) count=1 ;;
  esac
  printf '%s\n' "$count"
}

# Reports physical host memory from /proc/meminfo or sysctl hw.memsize; preflight checks compare
# container memory limits against the container engine's reported memory pool supplied by the
# active backend.
## @description  Reports the total physical memory of the host machine in bytes.
## @return       Writes total memory in bytes to stdout and exits 0, or returns 5 if unsupported.
px_mem_total_bytes() {
  local value="" line field
  if [ -r /proc/meminfo ]; then
    while IFS= read -r line; do
      case "$line" in
      MemTotal:*)
        field=${line#MemTotal:}
        px_trim_var field
        value=${field%% *}
        break
        ;;
      esac
    done </proc/meminfo
    case "$value" in
    '' | *[!0-9]*) value="" ;;
    *) value=$((value * 1024)) ;;
    esac
  fi
  if [ -z "$value" ] && command -v sysctl >/dev/null 2>&1; then
    value="$(sysctl -n hw.memsize 2>/dev/null)" || value=""
    case "$value" in
    '' | *[!0-9]*) value="" ;;
    esac
  fi
  if [ -z "$value" ]; then
    px_error "no host memory source on this platform"
    return "$PX_EXIT_UNSUPPORTED"
  fi
  printf '%s\n' "$value"
}

# Re-runs the host memory capacity check before starting an on-demand role group, since adding a
# role group commits additional container memory limits on the host. Invokes the documented host
# preflight interface "$PX_HOME/scripts/preflight.sh --phase memory" under PX_TIMEOUT_DEFAULT and
# redirects its report to stderr so the caller's stdout remains a clean data channel. That script
# owns the summation of configured container memory limits; if the script is not installed, startup
# proceeds with a warning. Backend preflight is never used as a substitute because its host port
# availability checks would fail against an already-running cluster.
## @description  Re-evaluates host memory capacity via preflight.sh before starting a role group.
## @return       Exits 0 if admitted or absent, 1 if not executable, 4 on timeout, 2 on bad timeout,
##               or returns the preflight script's exit status.
px_memory_recheck() {
  local script="$PX_HOME/scripts/preflight.sh" status=0
  if [ ! -e "$script" ]; then
    px_warn "the memory check is not installed, no $script: the group starts unchecked"
    return 0
  fi
  if [ ! -x "$script" ]; then
    px_error "the memory check $script is not executable"
    return "$PX_EXIT_FAIL"
  fi
  px_timeout "${PX_TIMEOUT_DEFAULT:-300}" "$script" --phase memory >&2 || status=$?
  case "$status" in
  124 | 137)
    px_error "the memory check expired after ${PX_TIMEOUT_DEFAULT:-300}s"
    return "$PX_EXIT_TIMEOUT"
    ;;
  esac
  return "$status"
}

# ---------------------------------------------------------------------------
# Tab-separated value (TSV) formatting, sanitization, and field extraction.
# ---------------------------------------------------------------------------
# The substrate contract exchanges structured data as tab-separated (TSV) rows. Because an embedded
# tab or newline inside a field would corrupt column framing, the emitter rejects any field
# containing those characters rather than writing a malformed row that downstream parsers cannot
# split reliably.

## @description  Emits a single tab-separated row from the supplied field arguments.
## @param        field... The column values to join with tab delimiters.
## @return       Writes the TSV row to stdout and exits 0, or returns 1 if any field has tab/NL.
px_tsv_row() {
  local field index=0 out=""
  for field in "$@"; do
    index=$((index + 1))
    case "$field" in
    *"$PX_TAB"* | *"$PX_NL"*)
      px_error "TSV field $index holds a tab or a newline: $field"
      return "$PX_EXIT_FAIL"
      ;;
    esac
    if [ "$index" = 1 ]; then out="$field"; else out="$out$PX_TAB$field"; fi
  done
  printf '%s\n' "$out"
}

# Terminates a stream of check or verdict rows with a single summary row containing the literal
# tag result, the stream-specific status word, the command exit code, and the target object name.
## @description  Emits the closing summary row for a check or verdict stream (status, code, object).
## @return       Writes the TSV row to stdout and exits 0, or returns 1 if any field contains a tab.
px_result_row() { px_tsv_row result "${1:-}" "${2:-}" "${3:-}"; }

## @description  Emits the closing result row of a check stream as ok or fail based on the supplied
##               exit status (code, object).
## @return       Writes "result ok 0 OBJECT" or "result fail CODE OBJECT" and returns the exit code.
px_result_close() {
  if [ "$1" = 0 ]; then
    px_result_row ok 0 "$2"
  else
    px_result_row fail "$1" "$2"
  fi
  return "$1"
}

# Defines a reusable awk function, glob(pattern), that translates a shell glob pattern into an
# anchored extended regular expression supporting *, ?, and closed bracket expressions [...] or
# [!...]. Every other character is treated literally by backslash-escaping only extended regular
# expression metacharacters. Harness commands splice this function definition into their awk
# programs so that glob patterns share uniform semantics across the entire harness.
## @description  Emits the awk source definition of the glob(pattern) helper function.
## @return       Writes the awk function text to stdout and exits 0.
px_awk_glob() {
  printf '%s\n' 'function glob(p, out, i, j, c) {
  out = "^"
  for (i = 1; i <= length(p); i++) {
    c = substr(p, i, 1)
    if (c == "*") c = ".*"
    else if (c == "?") c = "."
    else if (c == "[" && (j = index(substr(p, i + 2), "]")) > 0) {
      c = substr(p, i + 1, j)
      if (substr(c, 1, 1) == "!") c = "^" substr(c, 2)
      gsub(/\\/, "\\\\", c)
      c = "[" c "]"
      i += j + 1
    } else if (index("\\^$.[]|()+{}", c)) c = "\\" c
    out = out c
  }
  return out "$"
}'
}

# Replaces tabs and newlines with spaces and strips carriage returns so free-form text such as log
# messages can be safely embedded inside a single TSV column.
## @description  Sanitizes arbitrary text so it can be safely emitted as a single TSV field.
## @param        text The raw text string to sanitize.
## @return       Writes the sanitized single-line field to stdout and exits 0.
px_tsv_clean() {
  local value=${1:-}
  value=${value//$'\t'/ }
  value=${value//$'\r'/}
  value=${value//$'\n'/ }
  printf '%s\n' "$value"
}

# Callers reading substrate contract schemas query contract.sh for column positions rather than
# hard-coding numeric column indices by hand.
## @description  Extracts a 1-based positional field from a tab-separated row.
## @param        index, row The 1-based column index and the TSV row string.
## @return       Writes the field value to stdout and exits 0, or returns 6 if the column is absent.
px_tsv_field() {
  local index=${1:-} count=1 field rest=${2:-}
  # Slice fields explicitly with parameter expansion rather than IFS word splitting: because the
  # shell treats tab as whitespace in IFS, consecutive tabs would collapse into a single delimiter
  # and shift subsequent columns whenever an optional field (such as an unavailable metric in the
  # sample schema) is empty.
  while :; do
    case "$rest" in
    *"$PX_TAB"*)
      field=${rest%%"$PX_TAB"*}
      rest=${rest#*"$PX_TAB"}
      ;;
    *)
      if [ "$count" = "$index" ]; then
        printf '%s\n' "$rest"
        return 0
      fi
      return "$PX_EXIT_NO_OBJECT"
      ;;
    esac
    if [ "$count" = "$index" ]; then
      printf '%s\n' "$field"
      return 0
    fi
    count=$((count + 1))
  done
}

# ---------------------------------------------------------------------------
# Stateful JSON serialization and UTF-8 string escaping without external tools.
# ---------------------------------------------------------------------------
# Because the harness emits JSON manifests and verdict records without depending on jq, this
# serializer tracks container nesting and first-element comma state in two compact strings
# (PX_JSON_STACK and PX_JSON_FIRST). Members added inside an open object require both a key and a
# value, whereas members added inside an open array take only a value.

PX_JSON_STACK=""
PX_JSON_FIRST=""
PX_JSON_KEY=""
PX_JSON_VALUE=""
PX_JSON_SPACES="                                                                "

# Escapes double quotes, backslashes, and all ASCII control characters, and replaces any invalid
# UTF-8 byte sequence with the Unicode replacement character (\ufffd) so raw log excerpts cannot
# inject malformed UTF-8 into a JSON manifest.
## @description  Escapes a string for inclusion inside a JSON string literal.
## @param        text The raw string to escape.
## @return       Writes the escaped content (without surrounding quotes) to stdout and exits 0.
px_json_escape() {
  local LC_ALL=C text=${1:-} out="" index=0 length byte ord width seq invalid=0
  case "$text" in
  *[!$'\x20'-$'\x7e']* | *'"'* | *\\*) ;;
  *)
    printf '%s' "$text"
    return 0
    ;;
  esac
  length=${#text}
  while [ "$index" -lt "$length" ]; do
    byte=${text:$index:1}
    printf -v ord '%d' "'$byte" 2>/dev/null || ord=0
    if [ "$ord" -lt 0 ]; then ord=$((ord + 256)); fi
    if [ "$ord" -ge 128 ]; then
      px_utf8_width "$text" "$index"
      width=$PX_UTF8_WIDTH
      if [ "$width" = 0 ]; then
        out="$out\\ufffd"
        invalid=1
        index=$((index + 1))
        continue
      fi
      seq=${text:$index:$width}
      out="$out$seq"
      index=$((index + width))
      continue
    fi
    case "$byte" in
    '"') out="$out\\\"" ;;
    \\) out="$out\\\\" ;;
    *)
      if [ "$ord" -lt 32 ] || [ "$ord" = 127 ]; then
        case "$ord" in
        8) out="$out\\b" ;;
        9) out="$out\\t" ;;
        10) out="$out\\n" ;;
        12) out="$out\\f" ;;
        13) out="$out\\r" ;;
        *) out="$out$(printf '\\u%04x' "$ord")" ;;
        esac
      else
        out="$out$byte"
      fi
      ;;
    esac
    index=$((index + 1))
  done
  if [ "$invalid" = 1 ]; then
    px_warn "replaced invalid UTF-8 bytes in a JSON string"
  fi
  printf '%s' "$out"
}

PX_UTF8_WIDTH=0

# Rejects overlong encodings, UTF-16 surrogate halves, and code points above the Unicode maximum so
# only well-formed multi-byte UTF-8 sequences are accepted.
## @description  Determines the byte length of the UTF-8 sequence starting at a string byte offset.
## @param        text, index The input byte string and the 0-based byte offset to inspect.
## @return       Sets PX_UTF8_WIDTH to 1, 2, 3, or 4 (or 0 when invalid) and exits 0.
px_utf8_width() {
  local LC_ALL=C text=$1 index=$2 need lead second ord low=128 high=191 step
  PX_UTF8_WIDTH=0
  printf -v lead '%d' "'${text:$index:1}" 2>/dev/null || return 0
  if [ "$lead" -lt 0 ]; then lead=$((lead + 256)); fi
  if [ "$lead" -ge 194 ] && [ "$lead" -le 223 ]; then
    need=2
  elif [ "$lead" -ge 224 ] && [ "$lead" -le 239 ]; then
    need=3
    if [ "$lead" = 224 ]; then low=160; fi
    if [ "$lead" = 237 ]; then high=159; fi
  elif [ "$lead" -ge 240 ] && [ "$lead" -le 244 ]; then
    need=4
    if [ "$lead" = 240 ]; then low=144; fi
    if [ "$lead" = 244 ]; then high=143; fi
  else
    return 0
  fi
  if [ $((index + need)) -gt "${#text}" ]; then
    return 0
  fi
  step=1
  while [ "$step" -lt "$need" ]; do
    second=${text:$((index + step)):1}
    printf -v ord '%d' "'$second" 2>/dev/null || return 0
    if [ "$ord" -lt 0 ]; then ord=$((ord + 256)); fi
    if [ "$step" = 1 ]; then
      if [ "$ord" -lt "$low" ] || [ "$ord" -gt "$high" ]; then return 0; fi
    else
      if [ "$ord" -lt 128 ] || [ "$ord" -gt 191 ]; then return 0; fi
    fi
    step=$((step + 1))
  done
  PX_UTF8_WIDTH=$need
}

## @description  Emits the comma separator, indentation, and optional property key for a JSON item.
## @param        key The object property key, or an empty string when emitting inside an array.
## @return       Writes the prefix to stdout, marks the level started, or exits 1 if no container.
px_json_prefix() {
  local key=$1 depth=${#PX_JSON_STACK} pad
  if [ "$depth" = 0 ]; then
    px_die "$PX_EXIT_FAIL" "px_json: no open container"
  fi
  if [ "${PX_JSON_FIRST:$((depth - 1)):1}" = 0 ]; then
    printf ','
  fi
  pad=$((depth * 2))
  if [ "$pad" -gt ${#PX_JSON_SPACES} ]; then pad=${#PX_JSON_SPACES}; fi
  printf '\n%s' "${PX_JSON_SPACES:0:$pad}"
  if [ -n "$key" ]; then
    printf '"%s": ' "$(px_json_escape "$key")"
  fi
  PX_JSON_FIRST="${PX_JSON_FIRST:0:$((depth - 1))}0"
}

# Object members require two arguments (key and value), whereas array members require a single
# value argument.
## @description  Parses member arguments according to the innermost open JSON container type.
## @param        argument... Either (value) inside an array or (key, value) inside an object.
## @return       Populates PX_JSON_KEY and PX_JSON_VALUE and exits 0, or exits 2 on bad arity.
px_json_args() {
  local depth=${#PX_JSON_STACK}
  PX_JSON_KEY=""
  PX_JSON_VALUE=""
  if [ "${PX_JSON_STACK:$((depth - 1)):1}" = a ]; then
    if [ $# -ne 1 ]; then
      px_die "$PX_EXIT_USAGE" "px_json: an array member takes one argument"
    fi
    PX_JSON_VALUE=$1
  else
    if [ $# -ne 2 ]; then
      px_die "$PX_EXIT_USAGE" "px_json: an object member takes a key and a value"
    fi
    PX_JSON_KEY=$1
    PX_JSON_VALUE=$2
  fi
}

# A property key is required when nesting inside an object and must be omitted at the document root
# or inside an array.
## @description  Opens a new JSON object container at the current nesting depth.
## @param        key The property key when the parent container is an object (omitted otherwise).
## @return       Writes the opening brace to stdout and exits 0.
px_json_open() {
  local depth=${#PX_JSON_STACK}
  if [ "$depth" = 0 ]; then
    printf '{'
  else
    if [ "${PX_JSON_STACK:$((depth - 1)):1}" = a ]; then
      px_json_prefix ""
    else
      [ $# -eq 1 ] ||
        px_die "$PX_EXIT_USAGE" "px_json_open: a member of an object needs a key"
      px_json_prefix "$1"
    fi
    printf '{'
  fi
  PX_JSON_STACK="${PX_JSON_STACK}o"
  PX_JSON_FIRST="${PX_JSON_FIRST}1"
}

## @description  Opens a new JSON array container at the current nesting depth.
## @param        key The property key when the parent container is an object (omitted otherwise).
## @return       Writes the opening bracket to stdout and exits 0.
px_json_arr() {
  local depth=${#PX_JSON_STACK}
  if [ "$depth" = 0 ]; then
    printf '['
  else
    if [ "${PX_JSON_STACK:$((depth - 1)):1}" = a ]; then
      px_json_prefix ""
    else
      [ $# -eq 1 ] ||
        px_die "$PX_EXIT_USAGE" "px_json_arr: a member of an object needs a key"
      px_json_prefix "$1"
    fi
    printf '['
  fi
  PX_JSON_STACK="${PX_JSON_STACK}a"
  PX_JSON_FIRST="${PX_JSON_FIRST}1"
}

# Closing the outermost root container terminates the JSON document with a trailing newline.
## @description  Closes the innermost open JSON object or array container.
## @return       Writes the closing delimiter to stdout and exits 0, or exits 1 if none is open.
px_json_close() {
  local depth=${#PX_JSON_STACK} kind first pad
  if [ "$depth" = 0 ]; then
    px_die "$PX_EXIT_FAIL" "px_json_close: no open container"
  fi
  kind=${PX_JSON_STACK:$((depth - 1)):1}
  first=${PX_JSON_FIRST:$((depth - 1)):1}
  PX_JSON_STACK=${PX_JSON_STACK:0:$((depth - 1))}
  PX_JSON_FIRST=${PX_JSON_FIRST:0:$((depth - 1))}
  if [ "$first" = 1 ]; then
    if [ "$kind" = a ]; then printf ']'; else printf '}'; fi
  else
    pad=$(((depth - 1) * 2))
    if [ "$pad" -gt ${#PX_JSON_SPACES} ]; then pad=${#PX_JSON_SPACES}; fi
    if [ "$kind" = a ]; then
      printf '\n%s]' "${PX_JSON_SPACES:0:$pad}"
    else
      printf '\n%s}' "${PX_JSON_SPACES:0:$pad}"
    fi
  fi
  if [ -z "$PX_JSON_STACK" ]; then
    printf '\n'
  fi
}

## @description  Serializes a JSON string member into the current open object or array.
## @param        [key], value The property key (required inside an object only) and string value.
## @return       Writes the escaped, quoted JSON string member to stdout and exits 0.
px_json_str() {
  px_json_args "$@"
  px_json_prefix "$PX_JSON_KEY"
  printf '"%s"' "$(px_json_escape "$PX_JSON_VALUE")"
}

# Rejects values that do not match the JSON number grammar so malformed metrics fail immediately at
# emission time rather than when a consumer parses the output.
## @description  Validates and serializes a numeric JSON member into the current open container.
## @param        [key], value The property key (required inside an object only) and numeric value.
## @return       Writes the numeric literal to stdout and exits 0, or exits 1 if not a valid number.
px_json_num() {
  px_json_args "$@"
  if ! [[ $PX_JSON_VALUE =~ $PX_RE_NUMBER ]]; then
    px_die "$PX_EXIT_FAIL" "px_json_num: not a number: $PX_JSON_VALUE"
  fi
  px_json_prefix "$PX_JSON_KEY"
  printf '%s' "$PX_JSON_VALUE"
}

# ---------------------------------------------------------------------------
# Runtime execution traps that forbid direct container and Kubernetes CLI invocations.
# ---------------------------------------------------------------------------
# Only a backend implementation is permitted to invoke a container runtime or Kubernetes CLI tool,
# which it does by calling the binary via the "command" builtin and a privately held path (since
# "command" bypasses shell functions by definition). Because every px-* process sources this
# library, any stray direct invocation from a driver, check, hook, suite action, or collector is
# intercepted by these shell function traps and terminated with usage exit code 2. The function
# definitions below carry the waiver marker expected by the static scan in test/run-tests.sh.

## @description  Aborts an unauthorized direct invocation of a container runtime or cluster tool.
## @param        argv... The blocked executable name and argument vector so the caller sees it.
## @return       Logs the blocked command line and exits 2.
px_runtime_blocked() {
  px_die "$PX_EXIT_USAGE" "container runtime call blocked: $*" \
    "- use the substrate contract in lib/backend/contract.sh"
}

## @description  Intercepts and blocks direct invocations of this container runtime.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
docker() { px_runtime_blocked docker "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this container runtime.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
docker-compose() { px_runtime_blocked docker-compose "$@"; } # px-allow-docker: trap

## @description  Intercepts and blocks direct invocations of this container runtime.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
podman() { px_runtime_blocked podman "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this container runtime.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
nerdctl() { px_runtime_blocked nerdctl "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this Kubernetes tool.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
kubectl() { px_runtime_blocked kubectl "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this Kubernetes tool.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
k3d() { px_runtime_blocked k3d "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this Kubernetes tool.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
helm() { px_runtime_blocked helm "$@"; } # px-allow-docker: the trap itself

## @description  Intercepts and blocks direct invocations of this container runtime client.
## @param        argv... The arguments passed to the intercepted command.
## @return       Exits 2 via px_runtime_blocked.
crictl() { px_runtime_blocked crictl "$@"; } # px-allow-docker: the trap itself

# ---------------------------------------------------------------------------
# Top-level initialization of the process exit trap upon sourcing.
# ---------------------------------------------------------------------------
# The EXIT trap must be installed here at the top level of the calling command when this library is
# sourced. Installing the trap lazily from inside a helper function would bind it to a command
# substitution subshell, causing temporary directories to be deleted immediately when the subshell
# returns their path to the caller.
px_on_exit_install
