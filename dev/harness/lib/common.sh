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

# Walks upward from the library directory and assigns PX_TREE_ROOT to the first ancestor directory
# containing a repository marker, or leaves PX_TREE_ROOT empty when no marker is found.
## @description  Locates the enclosing repository working tree root for this harness instance.
## @return       Populates PX_TREE_ROOT and exits 0.
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

# Enumerates the supported JDK major versions that cluster roles and Maven builds may select.
PX_JDKS="11 17 21"

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

## @description  Translates a numeric exit code from the shared vocabulary into its symbolic name.
## @return       Writes the exit-code name (or undeclared) to stdout and exits 0.
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

# Prefers the shell printf time-formatting builtin over forking an external date process.
## @description  Reports the current UNIX epoch time in whole seconds.
## @return       Writes the epoch seconds to stdout and exits 0.
px_epoch_s() {
  if [ "$PX_HAVE_PRINTF_TIME" = 1 ]; then
    printf '%(%s)T\n' -1
  else
    date -u '+%s'
  fi
}

# Falls back to epoch seconds multiplied by 1000 on hosts where the date utility lacks a
# millisecond formatting field.
## @description  Reports the current UNIX epoch time in milliseconds.
## @return       Writes the epoch milliseconds to stdout and exits 0.
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

# Converts an epoch-second timestamp into PX_UTC_TEXT (YYYY-MM-DDTHH:MM:SSZ) and PX_UTC_COMPACT
# (YYYYMMDDTHHMMSSZ) using pure integer arithmetic so the result remains independent of TZ.
## @description  Populates standard and compact UTC timestamp variables from epoch seconds.
## @return       Sets PX_UTC_TEXT and PX_UTC_COMPACT and exits 0.
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

# Avoids forking a child process whenever the shell printf builtin supports time formatting.
## @description  Produces a formatted UTC timestamp suitable for a diagnostic message line.
## @return       Writes the UTC timestamp to stdout and exits 0.
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
# resolves PX_LOG_FILE under the output root, it records the admitted path in _PX_LOG_ADMITTED. The
# px_log function writes a copy only while PX_LOG_FILE still equals that result, ensuring that a
# value not admitted by the policy never receives a log line. Because this result is exported under
# a name outside the PX_* namespace, the configuration loader does not read it. When a driver
# sources this file through BASH_ENV, that shell inherits the parent admission and also writes the
# log copy.
_PX_LOG_ADMITTED="${_PX_LOG_ADMITTED:-}"

# Accepts a severity level followed by message words, formats a single timestamped diagnostic line
# to stderr, and appends a copy to PX_LOG_FILE when that path has been admitted by px_log_file_set.
## @description  Emits a formatted diagnostic message line to stderr and any admitted log file.
## @return       Writes the message line and exits 0.
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

## @description  Logs an informational progress message to stderr.
## @return       Writes the message to stderr and exits 0.
px_info() { px_log INFO "$@"; }

## @description  Logs a warning for an anomalous condition that does not stop the operation.
## @return       Writes the warning to stderr and exits 0.
px_warn() { px_log WARN "$@"; }

## @description  Logs a failure message while leaving the decision to continue or abort to the
##               caller.
## @return       Writes the error message to stderr and exits 0.
px_error() { px_log ERROR "$@"; }

## @description  Logs detailed diagnostic information only when PX_VERBOSE is enabled.
## @return       Writes the diagnostic message to stderr when PX_VERBOSE is true and exits 0.
px_debug() {
  px_is_verbose || return 0
  px_log DEBUG "$@"
}

# ---------------------------------------------------------------------------
# Boolean predicate helpers for execution switches.
# ---------------------------------------------------------------------------

# Explicitly matches both uppercase and lowercase character classes in the case pattern because
# Bash 3.2 does not support case-modifying parameter expansion.
## @description  Evaluates whether a configuration switch value represents boolean true.
## @return       Exits 0 for 1, true, yes, or on, or exits 1 for any other value.
px_is_true() {
  case "${1:-}" in
  1 | [Tt][Rr][Uu][Ee] | [Yy][Ee][Ss] | [Oo][Nn]) return 0 ;;
  *) return 1 ;;
  esac
}

## @description  Determines whether verbose diagnostic output is enabled.
## @return       Exits 0 when PX_VERBOSE is true, or exits 1 otherwise.
px_is_verbose() { px_is_true "${PX_VERBOSE:-0}"; }

## @description  Determines whether the current invocation is a dry run that must not mutate state.
## @return       Exits 0 when PX_DRY_RUN is true, or exits 1 otherwise.
px_is_dry_run() { px_is_true "${PX_DRY_RUN:-0}"; }

## @description  Determines whether the operator requested preserving runtime state for diagnosis.
## @return       Exits 0 when PX_KEEP is true, or exits 1 otherwise.
px_is_keep() { px_is_true "${PX_KEEP:-0}"; }

# ---------------------------------------------------------------------------
# Process termination, error trapping, and exit hook management.
# ---------------------------------------------------------------------------

# When a process has parsed its command line through the CLI framework, the final exit hook in
# px_on_exit_run logs the numeric status and symbolic exit name, so px_die omits that suffix here;
# processes that source this library without activating the CLI parser append the suffix directly.
## @description  Logs a fatal error message (taking the exit code followed by the message words)
##               and terminates the calling process.
## @return       Exits the process with the supplied exit code.
px_die() {
  local code=${1:-}
  shift
  if [ "${PX_CLI_ACTIVE:-0}" = 1 ]; then
    px_error "$*"
  else
    px_error "$* (exit $code, $(px_exit_name "$code"))"
  fi
  exit "$code"
}

# Validates the supplied run class argument, defaulting to PX_RUN_CLASS when omitted.
## @description  Verifies that the active run class is either validation or measurement.
## @return       Returns 0 when valid, or exits 2 on any other value.
px_run_class_require() {
  case "${1-${PX_RUN_CLASS:-}}" in
  validation | measurement) ;;
  *)
    px_die "$PX_EXIT_USAGE" "PX_RUN_CLASS needs validation or measurement," \
      "got '${1-${PX_RUN_CLASS:-}}'"
    ;;
  esac
}

# Invoke this helper as x="$(px_int_setting ...)" || exit $? so that a validation refusal inside the
# command substitution terminates the calling shell as well.
## @description  Reads and validates a whole-number setting from a variable name, a fallback
##               default, and a smallest allowed value.
## @return       Writes the number to stdout and exits 0, or exits 2 if non-numeric or too small.
px_int_setting() {
  local value=${!1:-$2}
  if ! [[ $value =~ $PX_RE_INT ]] || [ "$((10#$value))" -lt "$3" ]; then
    px_die "$PX_EXIT_USAGE" "$(px_setting_label "$1") needs a whole number of at least $3, got" \
      "'$value'"
  fi
  printf '%s\n' "$((10#$value))"
}

# Installed as the ERR trap handler by px_trap_enable; this callback only reports the failure
# without terminating the shell.
## @description  Reports the exit status, source line number, and command string of a failed
##               command.
## @return       Writes the failure diagnostic to stderr and exits 0.
px_err_trap() {
  local status=$1 line=$2 command=$3 where=${BASH_SOURCE[1]:-unknown}
  px_error "command failed with status $status at $where line $line: $command"
}

## @description  Installs the ERR diagnostic trap in the calling shell; pair with set -e when the
##               command should abort upon the first unhandled failure.
## @return       Arms the ERR trap and exits 0.
px_trap_enable() {
  # Enabling set -E propagates the ERR trap into shell functions. Because the trap fires under the
  # same conditions where set -e exits, callers that expect a non-zero status guard the invocation
  # with "if cmd; then" or "cmd || status=$?" so the trap remains silent.
  set -E
  trap 'px_err_trap "$?" "$LINENO" "$BASH_COMMAND"' ERR
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

# Cleanup hooks execute in reverse registration order (last-in, first-out). Callers must invoke
# this at the top level of the shell rather than inside a command substitution so the registration
# persists in the main process.
## @description  Registers a cleanup command to run when the process exits.
## @return       Prepends the command to the exit hook stack and exits 0.
px_on_exit() {
  PX_EXIT_HOOKS="$*
$PX_EXIT_HOOKS"
}

# Returns immediately when invoked inside a subshell because registered resources belong exclusively
# to the top-level parent process.
# After running all hooks and temporary directory cleanup, a process that parsed its command line
# and terminates with a non-zero status (or a status matching px_exit_noted) emits either the custom
# closing message recorded by px_exit_noted or, if the process is _PX_EXIT_OWNER, the standard exit
# diagnostic line so that the termination summary is written once as the final line on stderr.
## @description  Executes all registered exit hooks in reverse order, removes temporary directories
##               created by this process, and emits the final exit diagnostic line.
## @return       Always exits 0 without altering the exit status of the process.
px_on_exit_run() {
  local status=$? hook
  if [ "${BASH_SUBSHELL:-0}" != 0 ]; then
    return 0
  fi
  # Registered hook commands originate exclusively from internal harness code and never from
  # untrusted operator input.
  while IFS= read -r hook; do
    [ -n "$hook" ] || continue
    eval "$hook" || px_debug "exit hook failed: $hook"
  done <<EOF
$PX_EXIT_HOOKS
EOF
  PX_EXIT_HOOKS=""
  px_tmp_cleanup
  if [ "$status" = "$PX_EXIT_NOTED" ]; then
    [ -z "$PX_EXIT_NOTE" ] || px_info "$PX_EXIT_NOTE"
  elif [ "${PX_CLI_ACTIVE:-0}" = 1 ] && [ "$status" != 0 ] && [ "$_PX_EXIT_OWNER" = "$$" ]; then
    px_error "${PX_CLI_SUB:-$PX_PROG} ends (exit $status, $(px_exit_name "$status"))"
  fi
}

# Accepts an expected exit status code followed by optional message words to emit from the exit
# hook in place of the standard non-zero termination line; when no message words are provided, the
# exit hook suppresses the termination line for that status code.
## @description  Records a custom exit status and optional closing summary line for the exit hook.
## @return       Populates PX_EXIT_NOTED and PX_EXIT_NOTE and exits 0.
px_exit_noted() {
  PX_EXIT_NOTED=${1:-}
  shift
  PX_EXIT_NOTE="$*"
}

# ---------------------------------------------------------------------------
# Declarative command-line specification grammar, argument parser, and help formatter.
# ---------------------------------------------------------------------------
# Every harness entry point, utility script, and generator declares its command-line interface once
# as a declarative specification loaded from a heredoc via px_cli_spec. Both px_cli_parse and
# px_cli_help interpret that single specification so argument parsing and help output cannot drift
# apart. Each line in a specification begins with one of the following keywords:
#   about TEXT           Declares the command purpose on the first occurrence and extended
#                        description paragraphs on subsequent occurrences; inside a sub block, the
#                        first occurrence serves as the subcommand summary.
#   shared WORD...       Enables optional shared selectors supported by the command: --cluster,
#                        --class, and --backend; when declared inside a sub block, these selectors
#                        apply only to that subcommand.
#   operands WORD...     Defines the positional operand synopsis for a command without subcommands.
#   sub NAME [WORD...]   Begins a subcommand block named NAME with its optional operand synopsis.
#   rest WORD...         Declares that the current block accepts passthrough arguments after --,
#                        using WORD... as their synopsis.
#   opt NAME ARG TARGET | TEXT
#                        Declares an option within the current block; ARG is - for a boolean flag
#                        or a value placeholder token, TARGET names the destination binding, and
#                        TEXT provides the help description.
#   output, exits, verdicts, example TEXT
#                        Contributes a line to the Output, Exit codes, or Examples section of the
#                        rendered help output.
#   + TEXT               Appends a continuation line to the immediately preceding directive.
#
# Positional operand tokens in a synopsis may be required (ROLE), optional ([ROLE]), repeatable
# (FILE... or [ROLE...]), or constrained to a fixed alternation (start|stop|status). An option
# TARGET takes one of six forms: PX_NAME binds a configuration setting, PX_NAME=V defines a flag
# that assigns constant V to a setting, PX_NAME+S defines a repeatable setting whose values are
# joined by separator S, NAME binds a local shell variable, NAME=V defines a flag that assigns V to
# a shell variable, and NAME+ appends each occurrence to a shell array. Configuration settings are
# recorded with the cli origin in command-line order so that the earliest occurrence takes
# precedence, matching the precedence model across configuration stages. Only the shared options -h,
# -v, -n, and -e provide short flags, and options may appear in any position prior to --.

PX_CLI_SPEC=""
PX_CLI_SUB=""
PX_CLI_ARGS=()
PX_CLI_REST=()
PX_CLI_FILES=()
PX_CLI_SHARED=()
PX_CLI_ACTIVE=0
# Maps each configuration setting assigned on the command line to the flag that set it, stored as
# one "NAME<TAB>OPTION" row per entry so error messages report the exact option the operator typed.
PX_CLI_OPTIONS=""
# In-memory index arrays and scalars populated by px_cli_index from PX_CLI_SPEC, storing the enabled
# shared selectors, the operand and passthrough synopses for the top-level block and each subcommand
# block, and parallel per-option arrays recording option names, value placeholders, targets, and
# owning blocks.
_PXC_SEL=""
_PXC_SEL_ANY=""
_PXC_SUB_SEL=()
_PXC_TOP_OPS=""
_PXC_TOP_REST=""
_PXC_SUBS=()
_PXC_SUB_OPS=()
_PXC_SUB_REST=()
_PXC_OPT_NAME=()
_PXC_OPT_ARG=()
_PXC_OPT_TGT=()
_PXC_OPT_BLK=()
_PXC_MIN=0
_PXC_MAX=0
_PXC_NEED=""
_PXC_REQ=()

## @description  Appends a command-line specification read from standard input (typically a quoted
##               heredoc) to PX_CLI_SPEC.
## @return       Updates PX_CLI_SPEC and exits 0.
px_cli_spec() {
  local text=""
  IFS= read -r -d '' text || true
  PX_CLI_SPEC="$PX_CLI_SPEC$text"
}

## @description  Parses PX_CLI_SPEC into the internal index of shared selectors, operand synopses,
##               and option descriptors for each block.
## @return       Populates the _PXC_* index variables and exits 0.
px_cli_index() {
  local line key rest block="" last
  local -a field=()
  _PXC_SEL="" _PXC_SEL_ANY="" _PXC_TOP_OPS="" _PXC_TOP_REST=""
  _PXC_SUBS=() _PXC_SUB_OPS=() _PXC_SUB_REST=() _PXC_SUB_SEL=()
  _PXC_OPT_NAME=() _PXC_OPT_ARG=() _PXC_OPT_TGT=() _PXC_OPT_BLK=()
  while IFS= read -r line; do
    px_trim_var line
    key=${line%%[[:space:]]*}
    rest=${line#"$key"}
    px_trim_var rest
    case "$key" in
    shared)
      _PXC_SEL_ANY="$_PXC_SEL_ANY $rest "
      if [ -z "$block" ]; then
        _PXC_SEL=" $rest "
      else
        last=$((${#_PXC_SUBS[@]} - 1))
        _PXC_SUB_SEL[last]=" $rest "
      fi
      ;;
    operands) _PXC_TOP_OPS=$rest ;;
    sub)
      block=${rest%%[[:space:]]*}
      rest=${rest#"$block"}
      px_trim_var rest
      _PXC_SUBS+=("$block")
      _PXC_SUB_OPS+=("$rest")
      _PXC_SUB_REST+=("")
      _PXC_SUB_SEL+=("")
      ;;
    rest)
      if [ -z "$block" ]; then
        _PXC_TOP_REST=$rest
      else
        last=$((${#_PXC_SUBS[@]} - 1))
        _PXC_SUB_REST[last]=$rest
      fi
      ;;
    opt)
      read -r -a field <<<"${rest%%|*}" || true
      _PXC_OPT_NAME+=("${field[0]:-}")
      _PXC_OPT_ARG+=("${field[1]:-}")
      _PXC_OPT_TGT+=("${field[2]:-}")
      _PXC_OPT_BLK+=("$block")
      ;;
    esac
  done <<EOF
$PX_CLI_SPEC
EOF
}

# Tokens enclosed in square brackets are optional, so synopses such as [ROLE [INDEX]] and
# [-- ARGUMENT...] contribute no required operands. Populates _PXC_MIN with the minimum count,
# _PXC_MAX with the maximum count (-1 when unbounded), and _PXC_NEED and _PXC_REQ with the required
# operand tokens.
## @description  Derives operand count bounds and required operand tokens from a synopsis string.
## @return       Populates _PXC_MIN, _PXC_MAX, _PXC_NEED, and _PXC_REQ and exits 0.
px_cli_counts() {
  local word open close depth=0
  local -a words=()
  _PXC_MIN=0 _PXC_MAX=0 _PXC_NEED=""
  _PXC_REQ=()
  read -r -a words <<<"${1:-}" || true
  for word in ${words[@]+"${words[@]}"}; do
    open=${word//[^[]/}
    close=${word//[^]]/}
    if [ "$depth" = 0 ] && [ -z "$open" ]; then
      _PXC_MIN=$((_PXC_MIN + 1))
      _PXC_NEED="$_PXC_NEED $word"
      _PXC_REQ+=("$word")
    fi
    depth=$((depth + ${#open} - ${#close}))
    case "$word" in
    *...*) _PXC_MAX=-1 ;;
    *) [ "$_PXC_MAX" = -1 ] || _PXC_MAX=$((_PXC_MAX + 1)) ;;
    esac
  done
}

## @description  Formats argument words as a disjunctive English list ("a", "a or b", or
##               "a, b or c").
## @return       Writes the formatted list to stdout and exits 0.
px_cli_or() {
  local text=""
  while [ $# -gt 1 ]; do
    text="$text${text:+, }$1"
    shift
  done
  printf '%s%s\n' "$text${text:+ or }" "${1:-}"
}

# Initial token scanning passes '*' to match any block because the active subcommand is not known
# until all positional operands have been collected; specifications assign consistent flag or value
# arity to an option across every block that declares it.
## @description  Locates an option by name within the top-level block, a named subcommand block, or
##               any block when the block argument is '*'.
## @return       Sets PX_AT to the matching option index and returns 0, or returns 1 when not found.
px_cli_opt() {
  local i=0
  while [ "$i" -lt "${#_PXC_OPT_NAME[@]}" ]; do
    if [ "${_PXC_OPT_NAME[i]}" = "$1" ] && { [ "$2" = '*' ] || [ -z "${_PXC_OPT_BLK[i]}" ] ||
      [ "${_PXC_OPT_BLK[i]}" = "$2" ]; }; then
      PX_AT=$i
      return 0
    fi
    i=$((i + 1))
  done
  return 1
}

# Because a subsequent command-line option for a setting that already has a value never takes
# effect, only the initial option that assigns the setting is recorded.
## @description  Records the command-line option (name, option) that assigns a setting.
## @return       Appends the mapping to PX_CLI_OPTIONS and exits 0.
px_cli_option() {
  [ -z "$(px_config_origin "$1" || true)" ] || return 0
  PX_CLI_OPTIONS="$PX_CLI_OPTIONS$1$PX_TAB$2$PX_NL"
}

## @description  Resolves the display label for a setting (name) to the command-line option that
##               assigned it, or falls back to the setting name itself.
## @return       Writes the resolved label to stdout and exits 0.
px_setting_label() { px_tsv_get "${1:-}" "$PX_CLI_OPTIONS" || printf '%s\n' "${1:-}"; }

# Must be invoked directly in the calling shell rather than inside a command substitution so that
# configuration assignments, output variables (PX_CLI_SUB, PX_CLI_ARGS, PX_CLI_REST, PX_CLI_FILES,
# PX_CLI_SHARED, and option targets), and process termination for --help or usage errors take
# effect in the main command process.
## @description  Parses command-line arguments against PX_CLI_SPEC into the CLI state variables and
##               declared option targets.
## @return       Returns 0 on success, exits 0 after --help, or exits 2 on a usage error.
px_cli_parse() {
  local _pxc_w _pxc_v _pxc_i _pxc_j _pxc_k _pxc_n _pxc_t _pxc_err="" _pxc_help=0 _pxc_dd=0
  local _pxc_syn _pxc_rsyn _pxc_done=" " _pxc_or _pxc_sel
  local -a _pxc_ops=() _pxc_rest=() _pxc_on=() _pxc_ov=() _pxc_oi=() _pxc_set=()
  PX_CLI_ACTIVE=1
  if [ -z "$_PX_EXIT_OWNER" ]; then
    _PX_EXIT_OWNER=$$
    export _PX_EXIT_OWNER
  fi
  PX_CLI_SUB=""
  PX_CLI_ARGS=() PX_CLI_REST=() PX_CLI_FILES=() PX_CLI_SHARED=()
  PX_CLI_OPTIONS=""
  px_cli_index
  _pxc_sel=$_PXC_SEL
  # Perform an initial pass to collect positional operands, passthrough arguments following --, and
  # option-value pairs. Deferred error reporting retains the first syntax error while allowing a
  # subsequent -h or --help flag to take precedence.
  while [ $# -gt 0 ]; do
    _pxc_w=$1
    shift
    if [ "$_pxc_dd" = 1 ]; then
      _pxc_rest+=("$_pxc_w")
      continue
    fi
    _pxc_j=""
    case "$_pxc_w" in
    --)
      _pxc_dd=1
      continue
      ;;
    -h | --help)
      _pxc_help=1
      continue
      ;;
    -v | --verbose | -n | --dry-run | --strict) _pxc_j=flag ;;
    -e | --set | --config) _pxc_j=value ;;
    --cluster | --class | --backend)
      case "$_PXC_SEL_ANY" in
      *" $_pxc_w "*) _pxc_j=value ;;
      esac
      ;;
    esac
    if [ -z "$_pxc_j" ]; then
      case "$_pxc_w" in
      -?*) ;;
      *)
        _pxc_ops+=("$_pxc_w")
        continue
        ;;
      esac
      if ! px_cli_opt "$_pxc_w" '*'; then
        _pxc_err=${_pxc_err:-"unknown option $_pxc_w"}
        continue
      fi
      _pxc_j=$PX_AT
    fi
    _pxc_k=1
    case "$_pxc_j" in
    flag) _pxc_k=0 ;;
    value) ;;
    *) [ "${_PXC_OPT_ARG[_pxc_j]}" != - ] || _pxc_k=0 ;;
    esac
    _pxc_v=""
    if [ "$_pxc_k" = 1 ]; then
      if [ $# -eq 0 ] || [ -z "$1" ]; then
        _pxc_err=${_pxc_err:-"$_pxc_w needs a value"}
        [ $# -eq 0 ] || shift
        continue
      fi
      _pxc_v=$1
      shift
    fi
    _pxc_on+=("$_pxc_w")
    _pxc_ov+=("$_pxc_v")
    _pxc_oi+=("$_pxc_j")
    case "$_pxc_j" in
    flag) PX_CLI_SHARED+=("$_pxc_w") ;;
    value) PX_CLI_SHARED+=("$_pxc_w" "$_pxc_v") ;;
    esac
  done
  # Help detection stops at the -- separator so that passthrough commands retain their own --help.
  if [ "$_pxc_help" = 1 ]; then
    _pxc_w=""
    if [ "${#_PXC_SUBS[@]}" -gt 0 ] && [ "${#_pxc_ops[@]}" -gt 0 ] &&
      px_word_at "${_pxc_ops[0]}" "${_PXC_SUBS[@]}"; then
      _pxc_w=${_pxc_ops[0]}
    fi
    px_cli_help "$_pxc_w"
    exit "$PX_EXIT_OK"
  fi
  [ -z "$_pxc_err" ] || px_die "$PX_EXIT_USAGE" "$_pxc_err"
  _pxc_syn=$_PXC_TOP_OPS
  _pxc_rsyn=$_PXC_TOP_REST
  if [ "${#_PXC_SUBS[@]}" -gt 0 ]; then
    _pxc_or="$(px_cli_or "${_PXC_SUBS[@]}")"
    [ "${#_pxc_ops[@]}" -gt 0 ] || px_die "$PX_EXIT_USAGE" "name a subcommand: $_pxc_or"
    if ! px_word_at "${_pxc_ops[0]}" "${_PXC_SUBS[@]}"; then
      px_die "$PX_EXIT_USAGE" "unknown subcommand ${_pxc_ops[0]}: use $_pxc_or"
    fi
    PX_CLI_SUB=${_pxc_ops[0]}
    _pxc_sel="$_pxc_sel${_PXC_SUB_SEL[PX_AT]}"
    _pxc_syn=${_PXC_SUB_OPS[PX_AT]}
    _pxc_rsyn=${_PXC_SUB_REST[PX_AT]}
    _pxc_ops=(${_pxc_ops[@]+"${_pxc_ops[@]:1}"})
  fi
  # When a block declares no passthrough rest synopsis, words following -- are treated as normal
  # positional operands so callers can pass operand values that begin with a hyphen.
  if [ -n "$_pxc_rsyn" ]; then
    PX_CLI_REST=(${_pxc_rest[@]+"${_pxc_rest[@]}"})
    px_cli_counts "$_pxc_rsyn"
    if [ "$((_pxc_dd + ${#_pxc_rest[@]}))" -lt "$_PXC_MIN" ]; then
      px_die "$PX_EXIT_USAGE" "${PX_CLI_SUB:-$PX_PROG} needs$_PXC_NEED"
    fi
  else
    _pxc_ops+=(${_pxc_rest[@]+"${_pxc_rest[@]}"})
  fi
  px_cli_counts "$_pxc_syn"
  _pxc_n=${#_pxc_ops[@]}
  [ "$_pxc_n" -ge "$_PXC_MIN" ] || px_die "$PX_EXIT_USAGE" "${PX_CLI_SUB:-$PX_PROG} needs$_PXC_NEED"
  if [ "$_PXC_MAX" != -1 ] && [ "$_pxc_n" -gt "$_PXC_MAX" ]; then
    px_die "$PX_EXIT_USAGE" "unexpected argument ${_pxc_ops[_PXC_MAX]}"
  fi
  _pxc_i=0
  for _pxc_w in ${_PXC_REQ[@]+"${_PXC_REQ[@]}"}; do
    case "$_pxc_w" in
    *'|'*)
      case "|$_pxc_w|" in
      *"|${_pxc_ops[_pxc_i]}|"*) ;;
      *)
        IFS='|' read -r -a _pxc_set <<<"$_pxc_w" || true
        px_die "$PX_EXIT_USAGE" "unknown subcommand ${_pxc_ops[_pxc_i]}:" \
          "use $(px_cli_or "${_pxc_set[@]}")"
        ;;
      esac
      ;;
    esac
    _pxc_i=$((_pxc_i + 1))
  done
  PX_CLI_ARGS=(${_pxc_ops[@]+"${_pxc_ops[@]}"})
  # Verify that every supplied option belongs to the top-level grammar or the selected subcommand
  # block, and validate all selector values and --set assignments before mutating configuration.
  _pxc_i=0
  while [ "$_pxc_i" -lt "${#_pxc_on[@]}" ]; do
    _pxc_w=${_pxc_on[_pxc_i]}
    _pxc_v=${_pxc_ov[_pxc_i]}
    case "$_pxc_w" in
    -e | --set)
      [ "${_pxc_v%%=*}" != "$_pxc_v" ] ||
        px_die "$PX_EXIT_USAGE" "--set takes NAME=VALUE, got '$_pxc_v'"
      ;;
    --cluster | --class | --backend)
      case "$_pxc_sel" in
      *" $_pxc_w "*) ;;
      *) px_die "$PX_EXIT_USAGE" "$_pxc_w does not apply to $PX_CLI_SUB" ;;
      esac
      ;;
    esac
    case "$_pxc_w" in
    --class) px_run_class_require "$_pxc_v" ;;
    --backend)
      case "$_pxc_v" in
      compose | k8s) ;;
      *) px_die "$PX_EXIT_USAGE" "--backend takes compose or k8s, got '$_pxc_v'" ;;
      esac
      ;;
    esac
    case "${_pxc_oi[_pxc_i]}" in
    flag | value) ;;
    *)
      px_cli_opt "$_pxc_w" "$PX_CLI_SUB" ||
        px_die "$PX_EXIT_USAGE" "$_pxc_w does not apply to $PX_CLI_SUB"
      _pxc_oi[_pxc_i]=$PX_AT
      ;;
    esac
    _pxc_i=$((_pxc_i + 1))
  done
  # Enable strict configuration checking before applying option bindings so that -e and --set
  # reject undeclared variable names regardless of flag ordering on the command line.
  if px_word_at --strict ${_pxc_on[@]+"${_pxc_on[@]}"} || px_is_true "${PX_STRICT_CONFIG:-0}"; then
    PX_CONFIG_STRICT=1
  fi
  _pxc_i=0
  while [ "$_pxc_i" -lt "${#_pxc_on[@]}" ]; do
    _pxc_w=${_pxc_on[_pxc_i]}
    _pxc_v=${_pxc_ov[_pxc_i]}
    _pxc_i=$((_pxc_i + 1))
    case "$_pxc_w" in
    -v | --verbose) px_config_set PX_VERBOSE 1 cli ;;
    -n | --dry-run) px_config_set PX_DRY_RUN 1 cli ;;
    --strict) px_config_set PX_STRICT_CONFIG 1 cli ;;
    -e | --set) px_config_set "${_pxc_v%%=*}" "${_pxc_v#*=}" cli ;;
    --config) PX_CLI_FILES+=("$_pxc_v") ;;
    --cluster) px_config_set PX_CLUSTER_ID "$_pxc_v" cli ;;
    --class) px_config_set PX_RUN_CLASS "$_pxc_v" cli ;;
    --backend) px_config_set PX_BACKEND "$_pxc_v" cli ;;
    *)
      _pxc_t=${_PXC_OPT_TGT[${_pxc_oi[_pxc_i - 1]}]}
      case "$_pxc_t" in
      PX_*+*)
        # Consolidate all occurrences of a repeatable setting at the position of its first
        # appearance on the command line.
        _pxc_n=${_pxc_t%%+*}
        case "$_pxc_done" in
        *" $_pxc_n "*) continue ;;
        esac
        _pxc_done="$_pxc_done$_pxc_n "
        _pxc_v=""
        _pxc_j=0
        while [ "$_pxc_j" -lt "${#_pxc_on[@]}" ]; do
          if [ "${_pxc_on[_pxc_j]}" = "$_pxc_w" ]; then
            _pxc_v="$_pxc_v${_pxc_v:+${_pxc_t#*+}}${_pxc_ov[_pxc_j]}"
          fi
          _pxc_j=$((_pxc_j + 1))
        done
        px_cli_option "$_pxc_n" "$_pxc_w"
        px_config_set "$_pxc_n" "$_pxc_v" cli
        ;;
      PX_*=*) px_config_set "${_pxc_t%%=*}" "${_pxc_t#*=}" cli ;;
      PX_*)
        px_cli_option "$_pxc_t" "$_pxc_w"
        px_config_set "$_pxc_t" "$_pxc_v" cli
        ;;
      # Safe to evaluate because the target array name originates from the internal command
      # specification rather than untrusted command-line input.
      *+) eval "${_pxc_t%+}+=(\"\$_pxc_v\")" ;;
      *)
        _pxc_n=${_pxc_t%%=*}
        case "$_pxc_done" in
        *" $_pxc_n "*) continue ;;
        esac
        _pxc_done="$_pxc_done$_pxc_n "
        [ "$_pxc_n" = "$_pxc_t" ] || _pxc_v=${_pxc_t#*=}
        printf -v "$_pxc_n" '%s' "$_pxc_v"
        ;;
      esac
      ;;
    esac
  done
  px_debug "parsed ${PX_CLI_SUB:-$PX_PROG}: ${#PX_CLI_ARGS[@]} operands, ${#PX_CLI_REST[@]}" \
    "words after --"
}

## @description  Renders all specification entries matching a directive keyword and block name
##               (including continuation lines) in their target help-section layout.
## @return       Writes the formatted section lines to stdout and exits 0.
px_cli_print() {
  local want=$1 blk=$2 line key text block="" last="" n=0 label table=0
  local -a field=()
  while IFS= read -r line; do
    px_trim_var line
    key=${line%%[[:space:]]*}
    text=${line#"$key"}
    px_trim_var text
    # Emit the verdict table immediately after the verdicts text and all of its continuation lines
    # have been printed.
    if [ "$table" = 1 ] && [ "$key" != + ]; then
      printf '  %s.\n' "$(px_verdict_text)"
      table=0
    fi
    case "$key" in
    sub)
      block=${text%%[[:space:]]*}
      last=""
      continue
      ;;
    +)
      if [ "$last" != "$want" ] || [ "$block" != "$blk" ]; then
        continue
      fi
      case "$want" in
      about) printf '%s\n' "$text" ;;
      opt) printf '%28s%s\n' "" "$text" ;;
      example) printf '    %s\n' "$text" ;;
      *) printf '  %s\n' "$text" ;;
      esac
      continue
      ;;
    esac
    last=$key
    [ "$key" = "$want" ] && [ "$block" = "$blk" ] || continue
    n=$((n + 1))
    case "$want" in
    about)
      [ "$n" = 1 ] || printf '\n'
      printf '%s\n' "$text"
      ;;
    output)
      if [ "$n" = 1 ]; then printf 'Output: %s\n' "$text"; else printf '  %s\n' "$text"; fi
      ;;
    opt)
      read -r -a field <<<"${text%%|*}" || true
      label=${field[0]:-}
      [ "${field[1]:--}" = - ] || label="$label ${field[1]}"
      text=${text#*|}
      px_trim_var text
      px_cli_row "$label" "$text"
      ;;
    verdicts)
      printf '  %s\n' "$text"
      table=1
      ;;
    *) printf '  %s\n' "$text" ;;
    esac
  done <<EOF
$PX_CLI_SPEC
EOF
  [ "$table" = 0 ] || printf '  %s.\n' "$(px_verdict_text)"
}

# Aligns the description text at column 28, wrapping it onto the following line whenever the left
# label exceeds 25 characters.
## @description  Formats a two-column help table row from a label and description string.
## @return       Writes the formatted row to stdout and exits 0.
px_cli_row() {
  if [ "${#1}" -le 25 ]; then
    printf '  %-26s%s\n' "$1" "$2"
  else
    printf '  %s\n%28s%s\n' "$1" "" "$2"
  fi
}

## @description  Renders the Subcommands help section, pairing each subcommand synopsis with its
##               summary description.
## @return       Writes the formatted subcommand rows to stdout and exits 0.
px_cli_print_subs() {
  local line key text label="" state=0 at=-1
  while IFS= read -r line; do
    px_trim_var line
    key=${line%%[[:space:]]*}
    text=${line#"$key"}
    px_trim_var text
    case "$key $state" in
    "sub "*)
      at=$((at + 1))
      label="$text${_PXC_SUB_REST[at]:+ ${_PXC_SUB_REST[at]}}"
      state=1
      ;;
    "about 1")
      px_cli_row "$label" "$text"
      state=2
      ;;
    "+ 2") printf '%28s%s\n' "" "$text" ;;
    *" 2") state=0 ;;
    esac
  done <<EOF
$PX_CLI_SPEC
EOF
}

# Emits help sections in a fixed canonical order: usage synopsis, purpose and description,
# subcommands, command options, shared options, output contract, exit codes, and examples.
## @description  Renders the complete help documentation for the command or an optional named
##               subcommand from PX_CLI_SPEC.
## @return       Writes the formatted help text to stdout and exits 0.
px_cli_help() {
  local want=${1:-} text code sel
  px_cli_index
  sel=$_PXC_SEL_ANY
  if [ -n "$want" ] && px_word_at "$want" ${_PXC_SUBS[@]+"${_PXC_SUBS[@]}"}; then
    sel="$_PXC_SEL${_PXC_SUB_SEL[PX_AT]}"
    text="$want${_PXC_SUB_OPS[PX_AT]:+ ${_PXC_SUB_OPS[PX_AT]}} [option...]"
    text="$text${_PXC_SUB_REST[PX_AT]:+ ${_PXC_SUB_REST[PX_AT]}}"
  elif [ "${#_PXC_SUBS[@]}" -gt 0 ]; then
    want=""
    text="SUBCOMMAND [OPERAND...] [option...]"
  else
    want=""
    text="${_PXC_TOP_OPS:+$_PXC_TOP_OPS }[option...]${_PXC_TOP_REST:+ $_PXC_TOP_REST}"
  fi
  printf 'Usage: %s %s\n\n' "$PX_PROG" "$text"
  if [ -n "$want" ]; then
    # In a subcommand block, the first about directive is phrased as a brief summary fragment for
    # the Subcommands table and wrapped to fit that column; when rendering the subcommand's own
    # help screen, join and rewrap that fragment at 80 columns as a complete sentence with a
    # capitalized leading letter and a trailing period.
    px_cli_print about "$want" | awk '
      function flush(n, i, w, line) {
        p = toupper(substr(p, 1, 1)) substr(p, 2)
        sub(/[^.]$/, "&.", p)
        n = split(p, w, " ")
        for (i = 1; i <= n; i++) {
          if (line != "" && length(line) + 1 + length(w[i]) > 80) { print line; line = "" }
          line = line (line == "" ? "" : " ") w[i]
        }
        print line
      }
      !end && $0 != "" { p = p (p == "" ? "" : " ") $0; next }
      !end { end = 1; flush(); print; next }
      { print }
      END { if (!end) flush() }'
  else
    px_cli_print about ""
  fi
  if [ -z "$want" ] && [ "${#_PXC_SUBS[@]}" -gt 0 ]; then
    printf '\nSubcommands:\n'
    px_cli_print_subs
    printf "\nRun '%s SUBCOMMAND --help' for its options.\n" "$PX_PROG"
  fi
  text="$(px_cli_print opt "")"
  if [ -n "$want" ]; then
    code="$(px_cli_print opt "$want")"
    text="$text${text:+${code:+$PX_NL}}$code"
  fi
  [ -z "$text" ] || printf '\nOptions:\n%s\n' "$text"
  printf '\nShared options:\n'
  # Filter the shared-options table so that selector flags (--cluster, --class, and --backend)
  # appear only when explicitly enabled by the command's shared directive, while any indented
  # continuation line inherits the visibility decision of the option line immediately preceding it.
  awk -v sel="$sel" '/^  +-/ { keep = $1 !~ /^--(cluster|class|backend)$/ ||
    index(sel, " " $1 " ") } keep' <<'EOF'
  -h, --help                print this help on stdout and exit 0
  -v, --verbose             log debug detail on stderr
  -n, --dry-run             print the plan on stdout and change nothing
  -e, --set NAME=VALUE      set one configuration value (repeatable)
      --config FILE         read one more configuration file (repeatable;
                            earlier is stronger)
      --strict              refuse a PX_* name that lib/varnames.txt does not
                            declare
      --cluster ID          the harness cluster, default "default"
      --class NAME          validation or measurement, default the class of
                            the cluster record, else PX_RUN_CLASS
      --backend NAME        compose or k8s, default the backend of the
                            cluster record, else compose
EOF
  text="$(px_cli_print output "$want")"
  [ -z "$text" ] || printf '\n%s\n' "$text"
  text="Exit codes:"
  for code in 0 1 2 3 4 5; do
    text="$text $code $(px_exit_name "$code"),"
  done
  printf '\n%s\n  %s %s.\n' "$text" "$PX_EXIT_NO_OBJECT" "$(px_exit_name "$PX_EXIT_NO_OBJECT")"
  px_cli_print verdicts "$want"
  px_cli_print exits "$want"
  text="$(px_cli_print example "$want")"
  [ -z "$text" ] || printf '\nExamples:\n%s\n' "$text"
}

# During a dry run, mutating operations emit a plan record in place of performing their action,
# whereas read-only steps execute normally so that child commands inheriting PX_DRY_RUN continue to
# return answers.
## @description  Emits a dry-run plan row for a step name and its arguments when dry-run mode is
##               active.
## @return       Writes "plan STEP WORDS" to stdout and exits 0 in a dry run, or returns 1.
px_plan() {
  local step=${1:-}
  px_is_dry_run || return 1
  shift
  printf 'plan\t%s\t%s\n' "$step" "$*"
}

# ---------------------------------------------------------------------------
# In-place string manipulation utilities.
# ---------------------------------------------------------------------------

## @description  Strips leading and trailing whitespace from the named shell variable in place
##               using parameter expansion without forking.
## @return       Updates the named variable in place and exits 0.
px_trim_var() {
  # The temporary scratch variable uses a reserved prefix so it cannot collide with and shadow the
  # caller's target variable name, which would otherwise discard the trimmed result.
  local __px_trim=${!1}
  __px_trim=${__px_trim#"${__px_trim%%[![:space:]]*}"}
  __px_trim=${__px_trim%"${__px_trim##*[![:space:]]}"}
  printf -v "$1" '%s' "$__px_trim"
}

## @description  Converts a token into canonical configuration-variable form by uppercasing ASCII
##               letters and replacing hyphens and dots with underscores.
## @return       Writes the normalized uppercase identifier to stdout and exits 0.
px_upper() {
  # Enumerates the full ASCII alphabet explicitly because character range expressions in tr depend
  # on the active locale collation order.
  printf '%s\n' "${1:-}" | tr 'abcdefghijklmnopqrstuvwxyz.-' 'ABCDEFGHIJKLMNOPQRSTUVWXYZ__'
}

## @description  Constructs the port-map variable name PX_PORT_<ROLE>_<NAME>[_<INDEX>] from a role,
##               a port name, and an optional instance index.
## @return       Writes the port variable name to stdout and exits 0.
px_port_var() {
  printf 'PX_PORT_%s%s\n' "$(px_upper "${1:-}_${2:-}")" "${3:+_$3}"
}

PX_AT=""

## @description  Locates a target word (the first argument) among the remaining arguments and sets
##               PX_AT to its zero-based index.
## @return       Exits 0 when found, or returns 6 (leaving PX_AT empty) when no word matches.
px_word_at() {
  local want=${1:-} word
  shift
  PX_AT=0
  for word in "$@"; do
    [ "$word" != "$want" ] || return 0
    PX_AT=$((PX_AT + 1))
  done
  PX_AT=""
  return "$PX_EXIT_NO_OBJECT"
}

# ---------------------------------------------------------------------------
# Lexical path normalization, physical path resolution, and containment checks.
# ---------------------------------------------------------------------------

# Performs purely lexical path normalization without accessing the filesystem, stripping empty and
# single-dot (.) segments while resolving parent-directory (..) segments.
## @description  Lexically normalizes a relative or absolute path into canonical absolute form.
## @return       Writes the normalized absolute path to stdout and exits 0.
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

# Traverses symbolic links across existing ancestor directories so paths that do not exist yet can
# still be resolved to their canonical physical location before creation.
## @description  Resolves a path to its absolute physical location through every symbolic link.
## @return       Writes the path to stdout and exits 0, or returns 1 if an ancestor cannot be
##               entered.
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

## @description  Determines whether a resolved path equals or resides within a target directory by
##               comparing directory inode identity rather than string prefixes.
## @return       Exits 0 when the path is the directory or lies inside it, or returns 1 otherwise.
px_path_within() {
  # Compares directory identity (-ef) rather than lexical text so that alternate names for the same
  # directory, such as case variants on a case-insensitive filesystem, are still recognized as
  # contained within the boundary.
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

# Rejects relative paths, the filesystem root (/), and any location inside the working tree
# (PX_TREE_ROOT) so harness artifacts stay strictly isolated from the repository.
## @description  Validates, resolves, and creates the harness output root directory PX_OUT.
## @return       Writes the resolved root to stdout and exits 0; exits 2 on a refused path or 1 on
##               resolution or creation failure.
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

# Anchors relative paths under the output root and rejects empty paths, paths that escape PX_OUT,
# and paths that resolve inside the working tree.
## @description  Resolves and validates a relative or absolute destination path under PX_OUT.
## @return       Writes the path to stdout and exits 0; exits 2 on a policy violation or empty
##               path, or 1 when the path cannot be resolved.
px_out_resolve() {
  local root path resolved
  # Propagate any non-zero exit status from px_out_root unchanged so callers of the substrate
  # contract can branch directly on usage exit code 2.
  root="$(px_out_root)" || return $?
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

# Must be invoked directly in the calling shell rather than inside a command substitution so the
# admitted log file path and admission token persist and are exported to child processes.
## @description  Validates a log file path under the output policy and enables log mirroring to it.
## @return       Exits 0 on success, or propagates the status of px_out_resolve (2 or 1).
px_log_file_set() {
  local resolved
  _PX_LOG_ADMITTED=""
  resolved="$(px_out_resolve "${1:-}")" || return $?
  PX_LOG_FILE="$resolved"
  _PX_LOG_ADMITTED="$resolved"
  export PX_LOG_FILE _PX_LOG_ADMITTED
}

## @description  Resolves and creates a subdirectory under the output root PX_OUT.
## @return       Writes the directory path to stdout and exits 0; returns the status of
##               px_out_resolve (2 or 1) on policy or resolution failure, or exits 1 if mkdir fails.
px_out_subdir() {
  local dir
  dir="$(px_out_resolve "${1:-}")" || return $?
  mkdir -p "$dir" || px_die "$PX_EXIT_FAIL" "cannot create directory: $dir"
  printf '%s\n' "$dir"
}

## @description  Resolves and creates the backend runtime state directory (PX_STATE_DIR, defaulting
##               to state) under the output root.
## @return       Writes the state directory path to stdout and exits 0, or exits 2 or 1 on failure.
px_state_dir() {
  px_out_subdir "${PX_STATE_DIR:-state}"
}

# ---------------------------------------------------------------------------
# Sortable run identifier generation and exclusive run directory creation.
# ---------------------------------------------------------------------------

PX_ID_SEQ=0
PX_BASE36="0123456789abcdefghijklmnopqrstuvwxyz"

## @description  Encodes a non-negative integer (given value and width) in base 36, zero-padded to
##               the requested character width.
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

# Formats each identifier as a compact UTC timestamp, a hyphen, and four base-36 digits: two for
# the millisecond, one for PX_ID_SEQ, and one random digit.
## @description  Mints a chronologically sortable run identifier for a harness execution.
## @return       Writes the identifier to stdout and exits 0.
px_run_id() {
  # The suffix digits sort in creation order: the millisecond within the second comes first,
  # followed by the calling shell's sequence counter, which orders two calls from the same shell
  # within a single millisecond. The trailing random digit separates concurrent processes, and
  # px_run_dir closes any remaining collision window with an exclusive mkdir call.
  local now
  now="$(px_epoch_ms)"
  px_utc_fields "$((now / 1000))"
  printf '%s-%s\n' "$PX_UTC_COMPACT" \
    "$(px_base36 $(((now % 1000) * 1296 + (PX_ID_SEQ % 36) * 36 + RANDOM % 36)) 4)"
}

# Allocates an exclusive directory using a freshly minted run identifier under the specified parent
# subdirectory of PX_OUT (defaulting to runs), retrying up to 16 times on collision.
## @description  Exclusively creates a fresh run directory under a parent directory of PX_OUT.
## @return       Writes the directory path to stdout and exits 0; exits 2 on policy refusal or 1
##               when directory creation fails or no identifier is free.
px_run_dir() {
  local runs id dir attempt=0
  runs="$(px_out_subdir "${1:-runs}")" || return $?
  while [ "$attempt" -lt 16 ]; do
    # Advance the sequence counter in the parent shell because px_run_id executes inside a command
    # substitution subshell where any variable increment would be lost.
    PX_ID_SEQ=$((PX_ID_SEQ + 1))
    id="$(px_run_id)"
    dir="$runs/$id"
    if mkdir "$dir" 2>/dev/null; then
      printf '%s\n' "$dir"
      return 0
    fi
    attempt=$((attempt + 1))
  done
  px_die "$PX_EXIT_FAIL" "no free identifier under $runs after 16 attempts"
}

# ---------------------------------------------------------------------------
# Multi-stage configuration loader, variable registry validation, and provenance tracking.
# ---------------------------------------------------------------------------
# Configuration is governed by a single loader that enforces a deterministic precedence hierarchy
# and records the provenance of every variable. The loader evaluates stages in the following order,
# and the first stage that defines a variable wins:
#   1. The inherited process environment.
#   2. Explicit configuration files named on the command line.
#   3. Local environment overrides in <layout>/env/local.env.
#      3a. Enabled plug-ins (the sizing and JVM lines of each plugin.env).
#   4. Run-class defaults in <layout>/env/class-<class>.env.
#   5. Baseline defaults in <layout>/env/defaults.env (mandatory).
#   6. Component version definitions in <layout>/versions.env.
#   7. Local version overrides in <layout>/versions.local.env.
#   8. The generated port map in <deployment>/ports.generated.env.
# Because later stages never overwrite values established by earlier stages, explicit overrides
# always prevail over defaults. Stage 7 holds pins recorded manually by an operator, such as base
# image digests or a promoted download pin, together with version selections that re-pin a release.
# Stage 6 sets no active selection, only its default, which the loader applies after the last
# stage. The trust-on-first-use records of scripts/fetch-dist.sh are not a configuration stage:
# they reside in $PX_OUT/dist/pins.env and are read directly by fetch-dist.sh. An operator who
# wishes to replace a declared pin places the override in local.env.
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

# Loads the variable registry from lib/varnames.txt at most once per shell process so strict mode
# can fail closed on misspelled or undeclared PX_* names.
## @description  Loads the declared registry of recognized PX_* configuration variable names.
## @return       Exits 0 when loaded (or skipped in lax mode), or exits 1 in strict mode when the
##               registry file is missing.
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
  # Each non-empty line declares a prefix family ending with an asterisk (*), a single variable name
  # followed by the "private" keyword, or one or more whitespace-separated variable names. Matching
  # the wildcard prefix case before unquoted word splitting ensures that trailing asterisks never
  # trigger filesystem pathname expansion.
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%%#*}
    px_trim_var line
    case "$line" in
    '') ;;
    *\*) PX_VARNAMES_PREFIXES="$PX_VARNAMES_PREFIXES ${line%\*}" ;;
    *[[:space:]]private)
      name=${line%%[[:space:]]*}
      PX_VARNAMES_PRIVATE="$PX_VARNAMES_PRIVATE $name "
      PX_VARNAMES="$PX_VARNAMES$PX_NL$name"
      ;;
    *)
      for name in $line; do
        PX_VARNAMES="$PX_VARNAMES$PX_NL$name"
      done
      ;;
    esac
  done <"$file"
  PX_VARNAMES="$PX_VARNAMES$PX_NL"
}

# Treats all variable names as declared when the registry file is absent in non-strict mode.
## @description  Checks whether a configuration variable name is declared in the variable registry.
## @return       Exits 0 when declared, returns 1 when undeclared, or exits 1 under strict mode
##               when the registry file is missing.
px_config_known() {
  local name=${1:-} prefix
  px_varnames_load
  [ -n "$PX_VARNAMES" ] || return 0
  case "$PX_VARNAMES" in
  *"$PX_NL$name$PX_NL"*) return 0 ;;
  esac
  for prefix in $PX_VARNAMES_PREFIXES; do
    case "$name" in
    "$prefix"*) return 0 ;;
    esac
  done
  return 1
}

# Assigns a variable (given name, value, and stage origin) only if no earlier stage has set it, and
# exports the variable to child processes unless the registry marks it private.
## @description  Sets a configuration variable under first-wins precedence and records its origin.
## @return       Exits 0 on success, exits 2 on an invalid, undeclared (under strict mode), or
##               read-only name, or exits 1 under strict mode when the registry is missing.
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
## @return       Writes the origin label to stdout and exits 0, or returns 6 when the loader never
##               set the named variable.
px_config_origin() { px_tsv_get "${1:-}" "$PX_CONFIG_ORIGINS"; }

# Parses an environment data file (given path and origin label) containing only comments, blank
# lines, and literal KEY=VALUE assignments without executing shell syntax.
## @description  Parses a single environment data file and applies its assignments for a stage.
## @return       Exits 0 on success, returns 6 if absent, exits 2 on any invalid line or rejected
##               name, or exits 1 under strict mode when the registry is missing.
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

# Treats empty environment variables as unset so an empty export in the caller's shell cannot
# shadow a lower-precedence file or baseline default.
## @description  Captures all non-empty exported PX_* variables as the highest-precedence stage.
## @return       Exits 0 on success, exits 2 on an undeclared name under strict mode, or exits 1
##               under strict mode when the registry is missing.
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

# Accepts [--strict] [--no-record] [--] followed by optional stage 2 configuration file paths and
# evaluates all configuration stages in precedence order. Passing --no-record resolves the cluster
# record path without loading its contents, because cluster bring-up writes a fresh record rather
# than reading an existing one.
## @description  Executes the multi-stage configuration loading sequence in precedence order.
## @return       Exits 0 on success, 6 on an absent named file, 1 without defaults, or 2 on a
##               rejected line or name, PX_LOG_FILE, late PX_STRICT_CONFIG, or mismatched backend.
px_config_load() {
  local file env_dir record=1
  while [ $# -gt 0 ]; do
    case "$1" in
    --strict) PX_CONFIG_STRICT=1 ;;
    --no-record) record=0 ;;
    --) shift; break ;;
    -*) px_die "$PX_EXIT_USAGE" "px_config_load: unknown option $1" ;;
    *) break ;;
    esac
    shift
  done
  if px_is_true "${PX_STRICT_CONFIG:-0}"; then
    PX_CONFIG_STRICT=1
  fi
  px_config_stage_env
  for file in "$@"; do
    px_config_parse_file "$file" "$file" ||
      px_die "$PX_EXIT_NO_OBJECT" "configuration file not found: $file"
  done
  env_dir="${PX_ENV_DIR:-$PX_HOME/$PX_DEPLOY_SUBDIR/env}"
  px_config_set PX_ENV_DIR "$env_dir" derived
  px_config_parse_file "${PX_LOCAL_ENV:-$env_dir/local.env}" local || true
  # Load the sizing and JVM assignments of enabled plug-ins after local overrides and before the
  # run-class defaults so that explicit operator values still take precedence.
  if [ -n "${PX_PLUGINS:-}" ]; then
    if ! declare -F pl_config_stage >/dev/null; then
      # Because lib/plugin.sh sources this file, ShellCheck validates it through the callers that
      # source it directly rather than following this circular include.
      # shellcheck source=/dev/null
      . "$PX_HOME/lib/plugin.sh"
    fi
    pl_config_stage
  fi
  if [ -n "${PX_RUN_CLASS:-}" ]; then
    px_config_parse_file "${PX_CLASS_ENV:-$env_dir/class-$PX_RUN_CLASS.env}" \
      "class:$PX_RUN_CLASS" || true
  fi
  file="${PX_DEFAULTS_ENV:-$env_dir/defaults.env}"
  px_config_parse_file "$file" defaults ||
    px_die "$PX_EXIT_FAIL" "the baseline defaults are mandatory: $file"
  px_config_parse_file "${PX_VERSIONS_ENV:-$PX_HOME/$PX_DEPLOY_SUBDIR/versions.env}" \
    versions || true
  px_config_parse_file \
    "${PX_VERSIONS_LOCAL_ENV:-$PX_HOME/$PX_DEPLOY_SUBDIR/versions.local.env}" \
    versions-local || true
  px_config_load_ports "${PX_DEPLOYMENT_DIR:-}"
  px_versions_resolve
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

# Loads PX_PORT_MAP_ENV when set, or falls back to ports.generated.env inside the supplied
# deployment directory when one is provided.
## @description  Loads stage 8 port-map assignments from the deployment port map file.
## @return       Exits 0 (including when no port map file exists), or exits 2 on a refused line.
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

## @description  Emits every loaded configuration variable with its value and provenance, sorted by
##               variable name.
## @return       Writes tab-separated rows of name, value, and origin to stdout and exits 0.
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

# Lists the role keys of the sizing table in table order. Each sizing variable is named
# PX_<KEY>_MEM_MIB, PX_<KEY>_HEAP_MIB, or PX_<KEY>_DIRECT_MIB.
PX_SIZING_KEYS="ZK NN SNN DN RM NM JHS MASTER RS CLIENT TOOLING SETUP"

## @description  Reports each sizing variable whose loaded value differs from the run-class file for
##               PX_RUN_CLASS, identifying overrides of the sizing defined by the class.
## @return       Writes the space-separated variable names in table order to stdout and exits 0.
px_sizing_overrides() {
  local file table key part name value out=""
  file="${PX_CLASS_ENV:-${PX_ENV_DIR:-$PX_HOME/$PX_DEPLOY_SUBDIR/env}/class-${PX_RUN_CLASS:-}.env}"
  if [ -z "${PX_RUN_CLASS:-}" ] || [ ! -f "$file" ]; then
    printf '\n'
    return 0
  fi
  # Because the run-class file is plain text, a single pass extracts every sizing assignment in it.
  table="$(awk '{ sub(/\r$/, ""); gsub(/^[ \t]+|[ \t]+$/, "") }
    /^PX_[A-Z]+_(MEM|HEAP|DIRECT)_MIB[ \t]*=/ {
      k = $0; sub(/[ \t]*=.*/, "", k); v = $0; sub(/^[^=]*=[ \t]*/, "", v)
      gsub(/^["\047]|["\047]$/, "", v); print k "\t" v }' "$file")"
  for key in $PX_SIZING_KEYS; do
    for part in MEM HEAP DIRECT; do
      name="PX_${key}_${part}_MIB"
      value="$(px_tsv_get "$name" "$table" || true)"
      [ -n "$value" ] || continue
      [ "${!name:-}" = "$value" ] || out="${out:+$out }$name"
    done
  done
  printf '%s\n' "$out"
}

# ---------------------------------------------------------------------------
# Component version resolution and Phoenix HBase profile compatibility matrix.
# ---------------------------------------------------------------------------
# A version set comprises a Phoenix HBase profile, the cluster HBase release, the cluster Hadoop
# release, and the build and role JDK selections. The committed version definitions establish only
# the default for each selection. After the final configuration stage completes, the loader derives
# any unset selection from its default, allowing every stage (including local version overrides) to
# re-pin a component selection.
#
# The Phoenix Maven build selects a profile via -Dhbase.profile, which activates a single HBase
# compatibility module admitting a bounded range of HBase releases at run time. Each row in
# PX_HBASE_PROFILES defines one profile using five space-separated fields: the profile identifier,
# its compatibility module, the HBase minor line, and the lowest and highest admitted patch
# releases (where * admits every subsequent patch release on that minor line). The root pom.xml maps
# each profile to its compatibility module (hbase.compat.version), while the lowest admitted patch
# release corresponds to the check-hbase-compatibility enforcer rule in phoenix-core-client/pom.xml.
# Because Phoenix introduces a new compatibility module whenever HBase modifies an interface that
# the module consumes, the upper patch bound of a module is one patch release below the lower bound
# of the next compatibility module on the same minor line. Whenever the repository's compatibility
# modules change, this table must be updated; until then, the harness build rejects any pom.xml that
# disagrees with it.
PX_HBASE_PROFILES="2.5.0 2.5.0 2.5 0 3
2.5.4 2.5.4 2.5 4 *
2.5 2.5.4 2.5 4 *
2.6.0 2.6.0 2.6 0 3
2.6.3 2.6.0 2.6 0 3
2.6 2.6.4 2.6 4 *"

## @description  Looks up a Phoenix HBase profile in PX_HBASE_PROFILES and reports its compatibility
##               module, minor release line, lowest admitted patch, and highest admitted patch.
## @return       Writes the four fields to stdout and exits 0, or returns 6 for an unknown profile.
px_hbase_profile() {
  local profile rest
  while read -r profile rest; do
    if [ "$profile" = "${1:-}" ]; then
      printf '%s\n' "$rest"
      return 0
    fi
  done <<EOF
$PX_HBASE_PROFILES
EOF
  return "$PX_EXIT_NO_OBJECT"
}

## @description  Derives any version selection or image tag that no configuration stage set from its
##               corresponding default value.
## @return       Exits 0 on success, or exits 2 if strict mode rejects a derived variable name.
px_versions_resolve() {
  local name tag
  if [ -z "${PX_PHOENIX_HBASE_PROFILE:-}" ] &&
    [ -n "${PX_PHOENIX_HBASE_PROFILE_DEFAULT:-}" ]; then
    px_config_set PX_PHOENIX_HBASE_PROFILE "$PX_PHOENIX_HBASE_PROFILE_DEFAULT" derived
  fi
  # Because each Phoenix HBase profile defines its own default HBase distribution version, a
  # re-pinned profile automatically inherits the default HBase release for that profile.
  if [ -z "${PX_DIST_HBASE_VERSION:-}" ] && [ -n "${PX_PHOENIX_HBASE_PROFILE:-}" ]; then
    name="PX_DIST_HBASE_DEFAULT_$(px_upper "$PX_PHOENIX_HBASE_PROFILE")"
    if [[ $name =~ $PX_RE_VARNAME ]] && [ -n "${!name:-}" ]; then
      px_config_set PX_DIST_HBASE_VERSION "${!name}" derived
    fi
  fi
  if [ -z "${PX_DIST_HADOOP_VERSION:-}" ] && [ -n "${PX_DIST_HADOOP_DEFAULT:-}" ]; then
    px_config_set PX_DIST_HADOOP_VERSION "$PX_DIST_HADOOP_DEFAULT" derived
  fi
  # Assigns a distinct container image tag to each HBase, Hadoop, and profile combination so
  # different version sets never collide on a shared image. Role JDK selections are omitted from
  # the image tag because the container image bundles all supported JDK versions simultaneously.
  tag="hbase-${PX_DIST_HBASE_VERSION:-}-hadoop-${PX_DIST_HADOOP_VERSION:-}"
  tag="px-harness:$tag-profile-${PX_PHOENIX_HBASE_PROFILE:-}"
  if [ -z "${PX_IMAGE_TAG:-}" ] && [ -n "${PX_DIST_HBASE_VERSION:-}" ] &&
    [ -n "${PX_DIST_HADOOP_VERSION:-}" ] && [ -n "${PX_PHOENIX_HBASE_PROFILE:-}" ]; then
    px_config_set PX_IMAGE_TAG "$tag" derived
  fi
  # Because the Distributed Pherf image packages the client JARs of the selected HBase release
  # without bundling a Hadoop distribution or selectable JDKs, its derived image tag encodes only
  # the HBase release and the Phoenix HBase profile.
  tag="px-pherf:hbase-${PX_DIST_HBASE_VERSION:-}-profile-${PX_PHOENIX_HBASE_PROFILE:-}"
  if [ -z "${PX_PHERF_IMAGE_TAG:-}" ] && [ -n "${PX_DIST_HBASE_VERSION:-}" ] &&
    [ -n "${PX_PHOENIX_HBASE_PROFILE:-}" ]; then
    px_config_set PX_PHERF_IMAGE_TAG "$tag" derived
  fi
}

## @description  Verifies that the configured HBase release falls within the patch range admitted by
##               the compatibility module of the selected Phoenix HBase profile.
## @return       Exits 0 when compatible, or exits 2 on an unknown profile or out-of-range release.
px_versions_check() {
  local profile=${PX_PHOENIX_HBASE_PROFILE:-} release=${PX_DIST_HBASE_VERSION:-}
  local module="" minor="" low="" high="" patch="" range names="" row
  read -r module minor low high <<<"$(px_hbase_profile "$profile" || true)"
  if [ -z "$module" ]; then
    while read -r row _; do
      names="$names $row"
    done <<EOF
$PX_HBASE_PROFILES
EOF
    px_die "$PX_EXIT_USAGE" "PX_PHOENIX_HBASE_PROFILE='$profile' is not a Phoenix HBase" \
      "profile. The profiles are$names"
  fi
  range="$minor.$low to $minor.$high"
  [ "$high" != '*' ] || range="$minor.$low and every later $minor release"
  case "$release" in
  "$minor".*) patch=${release#"$minor".} ;;
  esac
  if ! [[ $patch =~ $PX_RE_INT ]] || [ "$patch" -lt "$low" ] ||
    { [ "$high" != '*' ] && [ "$patch" -gt "$high" ]; }; then
    px_die "$PX_EXIT_USAGE" "HBase '$release' (PX_DIST_HBASE_VERSION) is outside the range" \
      "of profile $profile: its compatibility module $module admits HBase $range"
  fi
}

## @description  Extracts the active version set from a configuration dump (name, value, origin).
## @return       Writes key-value TSV rows for the HBase profile, HBase version, Hadoop version, and
##               JDK selections to stdout and exits 0.
px_versions_rows() {
  local dump=${1:-} line name key jdks=""
  for key in hbase_profile:PX_PHOENIX_HBASE_PROFILE hbase_version:PX_DIST_HBASE_VERSION \
    hadoop_version:PX_DIST_HADOOP_VERSION; do
    line="$(px_tsv_get "${key#*:}" "$dump")" || line=""
    px_tsv_row "${key%%:*}" "${line%%"$PX_TAB"*}"
  done
  while IFS= read -r line; do
    name=${line%%"$PX_TAB"*}
    case "$name" in
    PX_*_JDK)
      line=${line#*"$PX_TAB"}
      jdks="$jdks $name=${line%%"$PX_TAB"*}"
      ;;
    esac
  done <<EOF
$dump
EOF
  px_tsv_row jdks "${jdks# }"
}

## @description  Converts a container image tag into a filesystem-safe staging directory name under
##               $PX_OUT/images by replacing every colon (:) and slash (/) with an underscore (_).
## @return       Writes the sanitized image staging name to stdout and exits 0.
px_image_stage_name() {
  local name=${1//:/_}
  printf '%s\n' "${name//\//_}"
}

# ---------------------------------------------------------------------------
# Bounded polling and retry execution primitives.
# ---------------------------------------------------------------------------
# Every wait operation in the harness has a finite bound. When a wait expires, it returns exit code
# 4 (PX_EXIT_TIMEOUT), leaving the caller to decide whether expiration constitutes a hard failure
# or a skipped condition.

## @description  Repeatedly executes a command (given timeout_s, interval_s, and command arguments)
##               until it succeeds or the deadline expires.
## @return       Exits 0 on command success, returns 4 on timeout expiry, or exits 2 on bad usage.
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

## @description  Executes a command (given attempts, sleep_s, and command arguments) up to a bounded
##               number of attempts with a fixed pause between retries.
## @return       Returns the exit status of the final attempt, or exits 2 on invalid usage.
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
# Operating system differences between macOS and Linux are isolated behind the helper functions in
# this section so that no other script in the harness needs platform-specific conditionals.

# Fixed grace period in seconds between sending SIGTERM and escalating to SIGKILL when terminating
# an expired command. This is an internal harness constant rather than a configurable parameter.
PX_TIMEOUT_GRACE_S=2

# Holds the resolved absolute path to a GNU-compatible timeout executable. Only binaries supporting
# GNU flags (--foreground and -k) qualify, because --foreground keeps the controlling terminal and
# its signals attached to the child command. Storing an absolute path avoids invoking the "command"
# builtin, which in Bash 3.2 triggers the ERR trap on failure even inside an "||" list. This probe
# runs once at source time so calls inside command substitutions reuse the cached path.
PX_TIMEOUT_BIN=""

## @description  Probes PATH for a timeout executable accepting GNU options (--foreground and -k)
##               and stores its resolved path in PX_TIMEOUT_BIN, or leaves it empty if unavailable.
## @return       Populates PX_TIMEOUT_BIN and exits 0.
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

# Preserves the caller's standard input, controlling terminal, and signal delivery while enforcing a
# whole-second execution deadline (given timeout_s and command arguments).
## @description  Executes an external program under a finite timeout while keeping terminal signals.
## @return       Returns the command status, 124 when expired, 137 when killed, 125 or 1 on a
##               watchdog fault, or exits 2 on invalid usage.
px_timeout() {
  local timeout=${1:-} mark status=0 watch_pid line expiry="" fg=""
  shift
  if ! [[ $timeout =~ $PX_RE_INT ]] || [ "$timeout" -lt 1 ]; then
    px_die "$PX_EXIT_USAGE" "px_timeout needs a whole number of seconds above zero"
  fi
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "px_timeout needs a command"
  if [ -n "$PX_TIMEOUT_BIN" ]; then
    # When standard input is a terminal, keep the command in the foreground process group so it
    # retains terminal access and signals; otherwise, run it in a separate process group so timeout
    # expiration also terminates any child processes that the command spawned.
    if [ -t 0 ]; then fg=--foreground; fi
    "$PX_TIMEOUT_BIN" ${fg:+"$fg"} -k "$PX_TIMEOUT_GRACE_S" "$timeout" "$@" ||
      status=$?
    return "$status"
  fi
  # When no GNU timeout executable is available, run the command in the foreground and spawn a
  # background watchdog process that signals only the immediate command upon expiration.
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

# Sleeps for timeout_s seconds and, unless it was stopped, no PID was recorded, or the calling shell
# has exited, appends 124 and sends SIGTERM; after the grace period, if the target is still alive,
# appends 137 and sends SIGKILL.
## @description  Enforces a command deadline in the background and signals the process upon expiry.
## @return       Exits 0.
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

## @description  Sleeps for one interval stage of px_timeout_watch, terminating the sleep process
##               and exiting the watchdog immediately when the stop flag is set.
## @return       Returns 0 after the full sleep elapses, or exits the watchdog with 0 when stopped.
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

# Persists tracked directory paths in a per-process file rather than a shell variable so that
# registrations made inside a command-substitution subshell survive when the subshell exits.
## @description  Reports the filesystem path of the temporary-directory registry for this process.
## @return       Writes the registry file path to stdout and exits 0.
px_tmp_registry() {
  local root=${TMPDIR:-/tmp}
  printf '%s/px.tmpdirs.%s\n' "${root%/}" "$$"
}

## @description  Creates a temporary directory incorporating an optional label in its name and
##               registers it for automatic removal on process exit unless PX_KEEP is enabled.
## @return       Writes the created directory path to stdout and exits 0, or exits 1 on failure.
px_mktemp_dir() {
  local label=${1:-px} dir root=${TMPDIR:-/tmp}
  dir="$(mktemp -d "${root%/}/$label.XXXXXXXX")" ||
    px_die "$PX_EXIT_FAIL" "cannot create a temporary directory"
  printf '%s\n' "$dir" >>"$(px_tmp_registry)"
  printf '%s\n' "$dir"
}

## @description  Removes all temporary directories registered by the current process unless PX_KEEP
##               is true; idempotent and safe to invoke multiple times.
## @return       Exits 0.
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

## @description  Computes the SHA-512 digest of a file using the first available utility among
##               sha512sum, gsha512sum, shasum, and openssl.
## @return       Writes 128 hex digits to stdout and exits 0; returns 1 on failure or 5 if no tool.
px_sha512() {
  local file=$1 prog out
  for prog in sha512sum gsha512sum 'shasum -a 512' 'openssl dgst -sha512 -r'; do
    command -v "${prog%% *}" >/dev/null 2>&1 || continue
    if ! out="$($prog "$file")"; then
      px_error "cannot compute the SHA-512 of $file"
      return "$PX_EXIT_FAIL"
    fi
    # GNU coreutils checksum programs prefix the output line with a leading backslash whenever the
    # target filename contains a backslash or newline character.
    out=${out#\\}
    printf '%s\n' "${out%%[[:space:]]*}" | tr 'ABCDEF' 'abcdef'
    return 0
  done
  px_error "no SHA-512 program on this host: install sha512sum or shasum"
  return "$PX_EXIT_UNSUPPORTED"
}

## @description  Determines the major version of the JDK at a given JAVA_HOME directory by parsing
##               its java -version output (normalizing legacy 1.x strings such as 1.8 to 8).
## @return       Writes the major version number to stdout and exits 0, or returns 1 on failure.
px_java_major() {
  local home=$1 out line major re
  re='version "([0-9]+)(\.([0-9]+))?'
  out="$("$home/bin/java" -version 2>&1)" || return "$PX_EXIT_FAIL"
  while IFS= read -r line; do
    if [[ $line =~ $re ]]; then
      major=${BASH_REMATCH[1]}
      if [ "$major" = 1 ]; then
        major=${BASH_REMATCH[3]}
      fi
      printf '%s\n' "$major"
      return 0
    fi
  done <<EOF
$out
EOF
  return "$PX_EXIT_FAIL"
}

# Resolves a JDK installation from PX_BUILD_JAVA_HOME or the host operating system's standard JDK
# registry (/usr/libexec/java_home on macOS or /usr/lib/jvm/* on Linux), intentionally ignoring any
# java binary on PATH so builds remain reproducible.
## @description  Locates the installation home directory for a requested JDK major version.
## @return       Writes the JDK home path to stdout and exits 0, returns 2 when PX_BUILD_JAVA_HOME
##               does not match the requested version, or returns 5 when no matching JDK is found.
px_java_home() {
  local want=$1 home=${PX_BUILD_JAVA_HOME:-} code=$PX_EXIT_USAGE candidate major
  if [ -z "$home" ]; then
    code=$PX_EXIT_UNSUPPORTED
    if [ -x /usr/libexec/java_home ]; then
      home="$(/usr/libexec/java_home -v "$want" 2>/dev/null)" || home=""
    fi
    if [ -z "$home" ]; then
      for candidate in /usr/lib/jvm/*; do
        [ -x "$candidate/bin/java" ] || continue
        major="$(px_java_major "$candidate")" || continue
        if [ "$major" = "$want" ]; then
          home=$candidate
          break
        fi
      done
    fi
  fi
  major=""
  if [ -n "$home" ] && [ -x "$home/bin/java" ]; then
    major="$(px_java_major "$home")" || major=""
  fi
  if [ "$major" != "$want" ]; then
    px_error "no JDK $want at ${home:-any known place} (found ${major:-no java})." \
      "Install it, or set PX_BUILD_JAVA_HOME to its home"
    return "$code"
  fi
  printf '%s\n' "$home"
}

# Determines the target Maven build JDK version from PX_BUILD_JDK and populates PX_BUILD_JDK_SEL,
# PX_BUILD_HOME, and PX_BUILD_TIMEOUT in the calling shell.
## @description  Resolves and validates the JDK installation and timeout settings for a Maven build.
## @return       Exits 0 on success, returns 2 or 5 from px_java_home, or exits 2 on an unsupported
##               JDK version or invalid PX_TIMEOUT_BUILD value.
px_build_jdk() {
  PX_BUILD_JDK_SEL=${PX_BUILD_JDK:-17}
  case " $PX_JDKS " in
  *" $PX_BUILD_JDK_SEL "*) ;;
  *) px_die "$PX_EXIT_USAGE" "the JDK must be one of $PX_JDKS, got '$PX_BUILD_JDK_SEL'" ;;
  esac
  PX_BUILD_TIMEOUT=${PX_TIMEOUT_BUILD:-3600}
  if ! [[ $PX_BUILD_TIMEOUT =~ $PX_RE_INT ]] || [ "$PX_BUILD_TIMEOUT" -lt 1 ]; then
    px_die "$PX_EXIT_USAGE" "PX_TIMEOUT_BUILD needs whole seconds above zero," \
      "got '$PX_BUILD_TIMEOUT'"
  fi
  PX_BUILD_HOME="$(px_java_home "$PX_BUILD_JDK_SEL")"
}

# Redirects Maven build output to the specified log file and prints the final 40 lines to stderr
# when a non-timeout build failure occurs.
## @description  Executes Maven (given log file path and mvn arguments) using the JDK and timeout
##               configured by px_build_jdk.
## @return       Exits 0 on success, 5 if mvn is missing, 4 on timeout expiry, or 1 on build error.
px_maven_run() {
  local log=$1 status=0
  shift
  command -v mvn >/dev/null 2>&1 ||
    px_die "$PX_EXIT_UNSUPPORTED" "Maven is required: no mvn on the PATH"
  px_timeout "$PX_BUILD_TIMEOUT" env "JAVA_HOME=$PX_BUILD_HOME" \
    "PATH=$PX_BUILD_HOME/bin:$PATH" mvn "$@" >"$log" 2>&1 || status=$?
  case "$status" in
  0) ;;
  124 | 137)
    px_die "$PX_EXIT_TIMEOUT" "the build expired after ${PX_BUILD_TIMEOUT}s, see $log"
    ;;
  *)
    tail -n 40 "$log" >&2 || true
    px_die "$PX_EXIT_FAIL" "the build failed with status $status, see $log"
    ;;
  esac
}

## @description  Reports the number of online logical processors available on the host (at least 1).
## @return       Writes the processor count to stdout and exits 0.
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

# Parses a "KEY: N kB" entry from /proc/meminfo and converts the kilobyte value N into bytes by
# multiplying by 1024.
## @description  Reads a named memory field (key) from /proc/meminfo and converts it to bytes.
## @return       Writes N times 1024 to stdout and exits 0, or returns 5 when /proc/meminfo is
##               unreadable or the field is absent or not a number.
px_meminfo_bytes() {
  local key value
  [ -r /proc/meminfo ] || return "$PX_EXIT_UNSUPPORTED"
  while IFS=': ' read -r key value _; do
    [ "$key" = "${1:-}" ] || continue
    case "$value" in '' | *[!0-9]*) break ;; esac
    printf '%s\n' "$((value * 1024))"
    return 0
  done </proc/meminfo
  return "$PX_EXIT_UNSUPPORTED"
}

# Preflight capacity checks do not use this host figure; they instead query the container engine's
# memory pool reported by the active backend.
## @description  Reports the total physical memory of the host machine in bytes.
## @return       Writes the byte count to stdout and exits 0, or returns 5 when no host memory
##               source is available.
px_mem_total_bytes() {
  local value
  value="$(px_meminfo_bytes MemTotal)" || value=""
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

# Temporarily exports non-empty variables marked "private" in the registry within a subshell so
# internal child scripts such as scripts/preflight.sh inherit settings like the discovered container
# engine without exposing them to unrelated processes.
## @description  Executes a harness command in a subshell with private configuration variables
##               exported.
## @return       Returns the exit status of the executed command.
px_with_private() {
  (
    for _px_name in $PX_VARNAMES_PRIVATE; do
      [ -z "${!_px_name:-}" ] || export "${_px_name?}"
    done
    "$@"
  )
}

# Unsets every configuration value this process loaded or derived (any origin other than the
# process environment or the command line, such as files, plug-ins, the target file, and derived
# version tags) so the child inherits only the operator's environment and command-line values.
# PX_OUT and PX_STATE_DIR are preserved across this boundary because every child process shares the
# output root of its parent.
## @description  Executes a harness command (command...) retaining only operator-supplied
##               configuration from the process environment and command-line flags.
## @return       Returns the exit status of the executed command.
px_config_child() {
  local line names=""
  while IFS= read -r line; do
    case "${line#*"$PX_TAB"}" in
    '' | environment | cli) ;;
    *)
      case "${line%%"$PX_TAB"*}" in
      PX_OUT | PX_STATE_DIR) ;;
      *) names="$names ${line%%"$PX_TAB"*}" ;;
      esac
      ;;
    esac
  done <<EOF
$PX_CONFIG_ORIGINS
EOF
  # Because loaded configuration variables are exported, they would otherwise enter the child
  # process at the highest-precedence environment stage and mask the child's own run class or
  # component version set.
  (
    for line in $names; do
      unset "$line"
    done
    px_with_private "$@"
  )
}

# Invokes scripts/preflight.sh --phase memory under PX_TIMEOUT_DEFAULT against the supplied
# deployment directory (DIR) or the recorded deployment before starting a group or scaling,
# redirecting its report to stderr.
## @description  Re-evaluates memory capacity via scripts/preflight.sh before group start or scale.
## @return       Returns 0 to admit, 1 if the script is missing or not executable, 4 on timeout
##               expiry, or the exit status of preflight.sh.
px_memory_recheck() {
  # Although the memory phase invokes sb_preflight, it inspects only the engine_memory row; host
  # port availability checks inside sb_preflight fail against an already-running cluster, so the
  # memory phase ignores their outcome.
  local script="$PX_HOME/scripts/preflight.sh" status=0
  if [ ! -x "$script" ]; then
    px_error "the memory check $script is missing or not executable"
    return "$PX_EXIT_FAIL"
  fi
  px_with_private px_timeout "${PX_TIMEOUT_DEFAULT:-300}" "$script" --phase memory \
    ${1:+--deployment "$1"} >&2 ||
    status=$?
  case "$status" in
  124 | 137)
    px_error "the memory check expired after ${PX_TIMEOUT_DEFAULT:-300}s"
    return "$PX_EXIT_TIMEOUT"
    ;;
  esac
  return "$status"
}

# ---------------------------------------------------------------------------
# Host safety, sleep inhibition, and workstation resource guards.
# ---------------------------------------------------------------------------
# Because the harness often runs on an interactive workstation that the operator uses concurrently,
# these helpers keep the host awake while a cluster is up or a run is active, measure host load and
# available memory, and tear down a cluster whenever its health monitor detects low host memory.
# Host metrics and process tables are queried via standard utilities resolved on PATH (uname,
# uptime, vm_stat for Darwin memory, and ps), whereas Linux memory statistics are read directly
# from /proc/meminfo. This design allows tests to inject fake implementations by prepending stubs
# to PATH, and a stubbed uname reporting Darwin selects the vm_stat memory path on any platform.

# Identifies the holder name reported by the Linux systemd sleep inhibitor.
PX_INHIBIT_WHO="phoenix-harness"

# Reclaims and removes a stale lock directory automatically if its recorded owner process is no
# longer running.
## @description  Acquires the lock directory LOCK, waiting up to WAIT_S seconds (default 30).
## @return       Exits 0 when this process holds the lock, or returns 1 if another process holds it
##               past the wait timeout.
px_lock() {
  local lock=${1:-} wait=${2:-30} owner waited=0
  while ! mkdir "$lock" 2>/dev/null; do
    owner="$(cat "$lock/pid" 2>/dev/null)" || owner=""
    case "$owner" in
    '' | *[!0-9]*) owner="" ;;
    esac
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
      px_warn "removing the stale lock $lock of process $owner"
      rm -f "$lock/pid"
      rmdir "$lock" 2>/dev/null || true
      continue
    fi
    if [ "$waited" -ge "$wait" ]; then
      px_error "another process holds the lock $lock"
      return "$PX_EXIT_FAIL"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  printf '%s\n' "$$" >"$lock/pid" || true
  return 0
}

## @description  Releases the lock directory LOCK previously acquired by px_lock.
## @return       Removes the owner record and lock directory and exits 0.
px_unlock() {
  rm -f "${1:-}/pid"
  rmdir "${1:-}" 2>/dev/null || true
  return 0
}

## @description  Computes the sanitized record file name for a host guard given KIND and KEY.
## @return       Writes the guard file name to stdout and exits 0.
px_guard_name() {
  local key=${2:-}
  printf '%s-%s\n' "${1:-guard}" "${key//[!A-Za-z0-9._-]/_}"
}

## @description  Resolves the state-directory record file path for a host guard (KIND and KEY).
## @return       Writes the path to stdout and exits 0, or propagates exit code 2 or 1 from
##               px_state_dir.
px_guard_path() {
  local dir
  dir="$(px_state_dir)" || return $?
  printf '%s/%s\n' "$dir" "$(px_guard_name "$@")"
}

## @description  Lists all descendant process IDs beneath a root process ID in breadth-first
##               (children-first) order.
## @return       Writes one process ID per line to stdout and exits 0.
px_child_pids() {
  ps -A -o pid= -o ppid= 2>/dev/null | awk -v root="${1:-}" '
    { parent[$1] = $2; order[NR] = $1 }
    END {
      keep[root] = 1
      do {
        added = 0
        for (i = 1; i <= NR; i++) {
          p = order[i]
          if (!(p in keep) && (parent[p] in keep)) { keep[p] = 1; print p; added = 1 }
        }
      } while (added)
    }'
}

## @description  Terminates a target process ID and all of its descendants by sending SIGTERM,
##               waiting up to two seconds, and escalating to SIGKILL.
## @return       Exits 0 when every process has terminated, or returns 1 if any survived SIGKILL.
px_kill_tree() {
  local pid=${1:-} kids tries=0 left="" p
  case "$pid" in '' | *[!0-9]*) return 0 ;; esac
  # Enumerate descendant processes before sending SIGTERM; once the parent terminates, surviving
  # children are reparented and no longer appear beneath the original process ID.
  kids="$(px_child_pids "$pid")"
  kill -s TERM "$pid" 2>/dev/null || true
  for p in $kids; do
    kill -s TERM "$p" 2>/dev/null || true
  done
  # Direct child processes of this shell remain zombies until reaped by wait, whereas wait returns
  # immediately when the target process belongs to another parent.
  wait "$pid" 2>/dev/null || true
  while :; do
    left=""
    for p in "$pid" $kids; do
      ! kill -s 0 "$p" 2>/dev/null || left="$left $p"
    done
    [ -n "$left" ] || return 0
    [ "$tries" -lt 20 ] || break
    sleep 0.1
    tries=$((tries + 1))
  done
  for p in $left; do
    kill -s KILL "$p" 2>/dev/null || true
  done
  wait "$pid" 2>/dev/null || true
  sleep 0.1
  for p in $left; do
    ! kill -s 0 "$p" 2>/dev/null || return "$PX_EXIT_FAIL"
  done
  return 0
}

# Stops the recorded process only if its live command line still matches the program name stored in
# the guard record, logging a warning if the process survives termination.
## @description  Terminates the guard process identified by a guard record path and removes the
##               record file.
## @return       Always exits 0, emitting a warning if the target process fails to stop.
px_guard_stop() {
  local record=${1:-} pid="" prog="" args
  [ -f "$record" ] || return 0
  IFS="$PX_TAB" read -r pid prog _ <"$record" || true
  case "$pid" in
  '' | *[!0-9]*) ;;
  *)
    # Because operating systems recycle process IDs, verifying the live command line prevents a
    # guard release from signaling an unrelated process or an inhibitor started directly by the
    # operator.
    args="$(ps -p "$pid" -o args= 2>/dev/null)" || args=""
    if [ -n "$prog" ]; then
      case "$args" in
      *"$prog"*) px_kill_tree "$pid" || px_warn "process $pid of $record did not stop" ;;
      esac
    fi
    ;;
  esac
  rm -f "$record"
}

# Launches caffeinate on Darwin or systemd-inhibit on Linux to keep the host awake for KEY (bound
# to an optional owner PID) and records the inhibitor PID in the state directory under PX_OUT.
## @description  Starts a background host sleep inhibitor for KEY and an optional owner PID.
## @return       Exits 0 (with a warning if no inhibitor holds), or 2 or 1 if the state directory
##               is refused or cannot be created.
px_inhibit_start() {
  local key=${1:-} owner=${2:-} record os prog path pid
  local args=()
  record="$(px_guard_path inhibit "$key")" || return $?
  os="$(uname -s 2>/dev/null)" || os=""
  # Binding the inhibitor to an owner PID terminates the inhibitor automatically when the owner
  # exits, even if the owner is killed with SIGKILL; without an owner PID, the inhibitor persists
  # until explicitly released.
  case "$os" in
  Darwin)
    prog=caffeinate
    # Pass -i to prevent idle sleep and -s to prevent system sleep while running on AC power.
    args=(-i -s ${owner:+-w "$owner"})
    ;;
  Linux)
    prog=systemd-inhibit
    args=(--what=sleep:idle "--who=$PX_INHIBIT_WHO" "--why=$key" --mode=block)
    if [ -n "$owner" ]; then
      args+=(sh -c "while kill -0 \"\$1\" 2>/dev/null; do sleep 1; done" px-inhibit "$owner")
    else
      args+=(sleep 2147483647)
    fi
    ;;
  *) prog="" ;;
  esac
  path=""
  if [ -n "$prog" ]; then
    path="$(type -P "$prog" 2>/dev/null)" || path=""
  fi
  if [ -z "$path" ]; then
    px_warn "no sleep inhibitor on this host (${os:-unknown}, ${prog:-none known}):" \
      "the host can sleep while $key holds it"
    return 0
  fi
  px_guard_stop "$record"
  # Because a non-interactive shell without job control ignores terminal interrupts in background
  # jobs, the inhibitor stops only when explicitly released or when its owner process exits. The
  # subshell replaces itself via exec so the recorded PID belongs directly to the inhibitor binary
  # and a signal delivered before exec cannot trigger the parent shell's exit hooks.
  (exec "$path" ${args[@]+"${args[@]}"}) </dev/null >/dev/null 2>&1 &
  pid=$!
  sleep 0.2
  if ! kill -s 0 "$pid" 2>/dev/null; then
    wait "$pid" 2>/dev/null || true
    px_warn "the sleep inhibitor $prog ended at once: the host can sleep while $key holds it"
    return 0
  fi
  px_tsv_row "$pid" "$prog" "$key" >"$record" ||
    px_warn "cannot record the sleep inhibitor $pid in $record"
  px_debug "sleep inhibitor $prog $pid holds the host for $key"
}

## @description  Stops and removes the host guard for KIND and KEY (such as inhibit and run-<id>).
## @return       Always exits 0.
px_guard_release() {
  local record
  record="$(px_guard_path "${1:-}" "${2:-}" 2>/dev/null)" || return 0
  px_guard_stop "$record"
}

# Sums the free, inactive, and speculative page counts from vm_stat on Darwin, or reads
# MemAvailable from /proc/meminfo on Linux.
## @description  Reports the host memory available without swapping, in bytes.
## @return       Writes the available byte count to stdout and exits 0, or returns 5 when no memory
##               source is available.
px_mem_available_bytes() {
  local line value="" page="" pages=0 n
  if [ "$(uname -s 2>/dev/null)" = Darwin ]; then
    while IFS= read -r line; do
      case "$line" in
      *'page size of '*)
        page=${line#*page size of }
        page=${page%% *}
        ;;
      'Pages free:'* | 'Pages inactive:'* | 'Pages speculative:'*)
        n=${line##* }
        n=${n%.}
        case "$n" in '' | *[!0-9]*) ;; *) pages=$((pages + n)) ;; esac
        ;;
      esac
    done <<PX_EOF
$(vm_stat 2>/dev/null)
PX_EOF
    case "$page" in '' | *[!0-9]*) ;; *) value=$((pages * page)) ;; esac
  else
    value="$(px_meminfo_bytes MemAvailable)" || value=""
  fi
  [ -n "$value" ] || return "$PX_EXIT_UNSUPPORTED"
  printf '%s\n' "$value"
}

# Reads the one-minute load average from uptime, which ships on both Linux and Darwin, and scales
# it by the host processor count.
## @description  Reports the one-minute host load average as a whole percentage of its processors.
## @return       Writes the percentage to stdout and exits 0, or returns 5 when uptime reports no
##               load average.
px_load_pct() {
  local out load whole frac
  out="$(LC_ALL=C uptime 2>/dev/null)" || out=""
  case "$out" in
  *'load average'*) ;;
  *) return "$PX_EXIT_UNSUPPORTED" ;;
  esac
  # Normalize the uptime output across platforms: Linux formats the label as "load average: 0.52,
  # 0.58", whereas Darwin writes "load averages: 1.48 1.61".
  load=${out##*load average}
  load=${load#*:}
  load=${load//,/ }
  read -r load _ <<<"$load" || true
  whole=${load%%.*}
  frac=${load#"$whole"}
  frac=${frac#.}00
  case "$whole${frac:0:2}" in
  '' | *[!0-9]*) return "$PX_EXIT_UNSUPPORTED" ;;
  esac
  printf '%s\n' "$(((10#$whole * 100 + 10#${frac:0:2}) / $(px_nproc)))"
}

# Delegates teardown to px-cluster down, which also releases the sleep inhibitor and stops the
# background monitor associated with the cluster.
## @description  Tears down the cluster identified by a scope ID via px-cluster down.
## @return       Propagates the px-cluster down exit code: 0 on clean removal or if the scope is
##               already down, or 1 if residual resources remain.
px_cluster_down() {
  PX_SCOPE_ID=${1:-} px_with_private "$PX_HOME/bin/px-cluster" down >&2
}

# ---------------------------------------------------------------------------
# Tab-separated value (TSV) formatting, sanitization, and field extraction.
# ---------------------------------------------------------------------------
# The substrate contract exchanges structured data as tab-separated (TSV) rows. Because an embedded
# tab or newline inside a field would corrupt column framing, the emitter rejects any field
# containing those characters rather than writing a malformed row that downstream parsers cannot
# split reliably.

## @description  Formats and writes a single tab-separated row from the supplied field arguments.
## @return       Writes the TSV row to stdout and exits 0, or returns 1 without writing anything
##               when any field contains a tab or a newline.
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

## @description  Sanitizes text for a single TSV field by replacing tabs and newlines with spaces
##               and stripping carriage returns.
## @return       Writes the sanitized field to stdout and exits 0.
px_tsv_clean() {
  local value=${1:-}
  value=${value//$'\t'/ }
  value=${value//$'\r'/}
  value=${value//$'\n'/ }
  printf '%s\n' "$value"
}

## @description  Extracts a 1-based positional field (given column index and row) from a TSV row,
##               where schema column indices are defined in contract.sh.
## @return       Writes the field to stdout and exits 0, or returns 6 when no such field exists.
px_tsv_field() {
  # Slices columns explicitly with parameter expansion rather than IFS word splitting: because the
  # shell classifies tab as IFS whitespace, consecutive tabs would collapse into a single delimiter
  # and drop empty columns. Since the sample schema leaves columns empty whenever a metrics source
  # has no equivalent metric, empty columns must preserve their positions; the trailing tab acts as
  # a termination sentinel.
  local index=${1:-} count=1 rest="${2:-}$PX_TAB"
  [[ $index =~ $PX_RE_INT ]] && [ "$index" -ge 1 ] || return "$PX_EXIT_NO_OBJECT"
  while [ "$count" -lt "$index" ]; do
    case "$rest" in
    *"$PX_TAB"*"$PX_TAB"*) rest=${rest#*"$PX_TAB"} ;;
    *) return "$PX_EXIT_NO_OBJECT" ;;
    esac
    count=$((count + 1))
  done
  printf '%s\n' "${rest%%"$PX_TAB"*}"
}

## @description  Extracts the remainder of the first line in a multi-line TSV text that begins with
##               the specified key followed by a tab delimiter.
## @return       Writes the value to stdout and exits 0, or returns 6 when no line matches the key.
px_tsv_get() {
  local key=${1:-} rest="$PX_NL${2:-}"
  case "$rest" in
  *"$PX_NL$key$PX_TAB"*) rest=${rest#*"$PX_NL$key$PX_TAB"} ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
  printf '%s\n' "${rest%%"$PX_NL"*}"
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

## @description  Escapes a string for JSON by escaping double quotes, backslashes, and control
##               characters while replacing invalid UTF-8 sequences with U+FFFD (\ufffd).
## @return       Writes the escaped text without surrounding quotes to stdout and exits 0.
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

## @description  Determines the byte width (1 to 4) of the UTF-8 sequence starting at a byte index
##               in a string, setting PX_UTF8_WIDTH to that width or to 0 when invalid.
## @return       Populates PX_UTF8_WIDTH and exits 0.
px_utf8_width() {
  # Rejects overlong encodings, UTF-16 surrogate code points, and values exceeding the Unicode
  # maximum so that only valid UTF-8 byte sequences pass through.
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

## @description  Emits the comma separator, indentation, and property key (empty inside an array)
##               for a JSON member and marks the current nesting level as started.
## @return       Exits 0 on success, or exits 1 when no container is open.
px_json_prefix() {
  local key=$1 depth=${#PX_JSON_STACK}
  [ "$depth" != 0 ] || px_die "$PX_EXIT_FAIL" "px_json: no open container"
  if [ "${PX_JSON_FIRST:$((depth - 1)):1}" = 0 ]; then
    printf ','
  fi
  printf '\n%s' "${PX_JSON_SPACES:0:$((depth * 2))}"
  if [ -n "$key" ]; then
    printf '"%s": ' "$(px_json_escape "$key")"
  fi
  PX_JSON_FIRST="${PX_JSON_FIRST:0:$((depth - 1))}0"
}

## @description  Populates PX_JSON_KEY and PX_JSON_VALUE from member arguments (taking a key and a
##               value inside an object, or a value alone inside an array).
## @return       Exits 0 on success, or exits 2 when the argument count does not match.
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

## @description  Opens a nested JSON container (given opening bracket, kind o or a, caller name,
##               and the property key required when nesting inside an open object).
## @return       Exits 0 on success, or exits 2 when an object member is missing its key.
px_json_container() {
  local bracket=$1 kind=$2 name=$3 depth=${#PX_JSON_STACK}
  shift 3
  if [ "$depth" != 0 ]; then
    if [ "${PX_JSON_STACK:$((depth - 1)):1}" = a ]; then
      px_json_prefix ""
    else
      [ $# -eq 1 ] || px_die "$PX_EXIT_USAGE" "$name: a member of an object needs a key"
      px_json_prefix "$1"
    fi
  fi
  printf '%s' "$bracket"
  PX_JSON_STACK="$PX_JSON_STACK$kind"
  PX_JSON_FIRST="${PX_JSON_FIRST}1"
}

## @description  Opens a JSON object container (requiring a property key only when nested inside an
##               open object).
## @return       Exits 0 on success, or exits 2 when nested inside an object without a key.
px_json_open() { px_json_container '{' o px_json_open "$@"; }

## @description  Opens a JSON array container (requiring a property key only when nested inside an
##               open object).
## @return       Exits 0 on success, or exits 2 when nested inside an object without a key.
px_json_arr() { px_json_container '[' a px_json_arr "$@"; }

## @description  Closes the innermost open JSON container, terminating the document with a trailing
##               newline when closing the outermost root container.
## @return       Exits 0 on success, or exits 1 when no container is open.
px_json_close() {
  local depth=${#PX_JSON_STACK} bracket='}'
  [ "$depth" != 0 ] || px_die "$PX_EXIT_FAIL" "px_json_close: no open container"
  [ "${PX_JSON_STACK:$((depth - 1)):1}" = o ] || bracket=']'
  if [ "${PX_JSON_FIRST:$((depth - 1)):1}" = 0 ]; then
    printf '\n%s' "${PX_JSON_SPACES:0:$(((depth - 1) * 2))}"
  fi
  printf '%s' "$bracket"
  PX_JSON_STACK=${PX_JSON_STACK:0:$((depth - 1))}
  PX_JSON_FIRST=${PX_JSON_FIRST:0:$((depth - 1))}
  [ -n "$PX_JSON_STACK" ] || printf '\n'
}

## @description  Emits a JSON string member (taking a key inside an object, followed by the value).
## @return       Exits 0 on success, exits 2 on a wrong argument count, or 1 with no open container.
px_json_str() {
  px_json_args "$@"
  px_json_prefix "$PX_JSON_KEY"
  printf '"%s"' "$(px_json_escape "$PX_JSON_VALUE")"
}

## @description  Emits a JSON numeric member (taking a key inside an object, followed by the value).
## @return       Exits 0 on success, exits 2 on a wrong argument count, or exits 1 when no container
##               is open or the value is not a valid JSON number (failing at write time).
px_json_num() {
  px_json_args "$@"
  if ! [[ $PX_JSON_VALUE =~ $PX_RE_NUMBER ]]; then
    px_die "$PX_EXIT_FAIL" "px_json_num: not a number: $PX_JSON_VALUE"
  fi
  px_json_prefix "$PX_JSON_KEY"
  printf '%s' "$PX_JSON_VALUE"
}

# Writes a JSON literal member (true, false, or null), taking a key when inside an object followed
# by the literal value.
## @description  Emits a JSON boolean or null literal member (true, false, or null).
## @return       Exits 0 on success, exits 2 on a wrong argument count or another word, or exits 1
##               when no container is open.
px_json_lit() {
  px_json_args "$@"
  case "$PX_JSON_VALUE" in
  true | false | null) ;;
  *) px_die "$PX_EXIT_USAGE" "px_json_lit: not a JSON literal: $PX_JSON_VALUE" ;;
  esac
  px_json_prefix "$PX_JSON_KEY"
  printf '%s' "$PX_JSON_VALUE"
}

# ---------------------------------------------------------------------------
# Runtime execution traps that forbid direct container and Kubernetes CLI invocations.
# ---------------------------------------------------------------------------
# Only a backend implementation is permitted to invoke a container runtime or a Kubernetes tool,
# which it accomplishes by invoking the binary through the command builtin and a privately held
# path (since command bypasses shell functions by definition). Because every px-* process sources
# this file, any stray call from a driver, a check, a hook, or a collector is intercepted by the
# trap and fails with the usage exit code. The loop below defines one trap function for each tool
# name, and its inline waiver marker satisfies the static scan in test/run-tests.sh.

## @description  Rejects a direct call to a container runtime or Kubernetes tool and reports the
##               attempted command line in the error message so the author can identify it.
## @return       Exits 2.
px_runtime_blocked() {
  px_die "$PX_EXIT_USAGE" "container runtime call blocked: $*" \
    "- use the substrate contract in lib/backend/contract.sh"
}

## @description  Defines a shell trap function for each container runtime and Kubernetes CLI tool.
## @return       Each generated trap function exits 2.
_px_names="docker docker-compose podman nerdctl kubectl k3d helm crictl" # px-allow-docker: traps
for _px_t in $_px_names; do
  eval "$_px_t() { px_runtime_blocked $_px_t \"\$@\"; }"
done
unset _px_t _px_names

# ---------------------------------------------------------------------------
# Top-level initialization of the process exit trap upon sourcing.
# ---------------------------------------------------------------------------
# Install the EXIT trap here at the top level of the calling command when this library is sourced.
# Because this library owns the process EXIT trap, commands register cleanup work via px_on_exit
# rather than replacing the trap directly. Installing the trap lazily from inside a helper function
# could run inside a command substitution subshell, causing the subshell's EXIT trap to delete a
# newly created temporary directory as soon as the subshell returns its path to the caller.
trap px_on_exit_run EXIT
