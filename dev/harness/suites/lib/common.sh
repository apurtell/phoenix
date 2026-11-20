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

# This shared library is sourced by suite action commands to provide two core facilities: the
# severity lattice and the verdict record writer. The px-suite runner consumes this same lattice
# definition along with the verdict records emitted by each test.
#
# Two architectural rules govern this module:
#   1. Recording a verdict writes the entry and returns to the caller without terminating the
#      process, ensuring that one failed check never obscures the checks that follow it. Only an
#      invalid invocation of this library terminates the process.
#   2. The severity lattice is defined once in this file. Both px-suite and every subsequent
#      consumer must read the lattice from here so that a severity term cannot carry different
#      meanings across components.
#
# The exported surface is intentionally minimal: a subsequent round layers cluster checks on top of
# these names, so every identifier introduced here becomes part of a published interface.

# This file is a library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: suites/lib/common.sh is a library. Source it from a suite action.\n' >&2
  exit 2
fi

# Guard against redundant sourcing so that symbols in this library are defined only once.
if [ -n "${SX_COMMON_LOADED:-}" ]; then
  return 0
fi
SX_COMMON_LOADED=1

SX_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../../lib/common.sh
. "$SX_LIB_DIR/../../lib/common.sh"

# ---------------------------------------------------------------------------
# Severity lattice definition and result aggregation policy.
# ---------------------------------------------------------------------------
# Severities are ordered from weakest to strongest. When merging outcomes, a later severity
# supersedes an earlier one only if it is stronger, so the ordering below defines the entire
# resolution policy:
#
#   PASS      The check held as expected.
#   SKIP      The check could not run, and skipping it is an accepted outcome.
#   ADVISORY  The check held only weakly, or host conditions rendered the evidence unsafe.
#   FAIL      The check did not hold.
#   ERROR     A harness or infrastructure fault broke execution, rendering the check void.
#
# PASS, SKIP, and ADVISORY outcomes do not fail a run. ERROR is reserved for harness or
# infrastructure faults so that a cluster anomaly remains distinguishable from a workload failure.
#
# An aggregated result that contains no severities, or contains only SKIP severities, is
# INCONCLUSIVE; because nothing was checked, it is not a pass. INCONCLUSIVE is a result rather than
# a severity: no verdict carries it, and it never enters a merge.

SX_SEVERITIES="PASS SKIP ADVISORY FAIL ERROR"

# Callers compare severities through their numeric ranks so that no caller duplicates the lattice
# ordering.
## @description  Resolves a severity name to its numeric rank in the severity lattice.
## @param        severity The severity name to evaluate.
## @return       Writes the rank (0 to 4) to stdout and exits 0, or exits 6 if not a severity.
sx_severity_rank() {
  case "${1:-}" in
  PASS) printf '0\n' ;;
  SKIP) printf '1\n' ;;
  ADVISORY) printf '2\n' ;;
  FAIL) printf '3\n' ;;
  ERROR) printf '4\n' ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
}

# Implements the entirety of the highest-severity aggregation policy. When an argument is not a
# recognized severity, the function terminates with exit code 2, which aborts an enclosing command
# substitution and returns status 2 to the caller.
## @description  Compares two severities and reports the stronger one.
## @param        left, right The two severity names to compare.
## @return       Writes the stronger severity to stdout and exits 0, or exits 2 on an invalid name.
sx_severity_worse() {
  local left=${1:-} right=${2:-} left_rank right_rank
  left_rank="$(sx_severity_rank "$left")" ||
    px_die "$PX_EXIT_USAGE" "'$left' is not a severity, expected one of $SX_SEVERITIES"
  right_rank="$(sx_severity_rank "$right")" ||
    px_die "$PX_EXIT_USAGE" "'$right' is not a severity, expected one of $SX_SEVERITIES"
  if [ "$right_rank" -gt "$left_rank" ]; then
    printf '%s\n' "$right"
  else
    printf '%s\n' "$left"
  fi
}

# Evaluates the aggregate outcome across a collection of severities, such as the tests within a
# session. Resolves to the worst severity in the set, or to INCONCLUSIVE when the set is empty or
# contains only SKIP outcomes. Exits 2 if the worst word is not a valid severity or if either count
# is not a whole number.
## @description  Computes the aggregate result for a set of recorded severities.
## @param        worst, total, skipped The worst severity, total count, and SKIP count of the set.
## @return       Writes the result word to stdout and exits 0, or exits 2 on invalid arguments.
sx_result() {
  local worst=${1:-} total=${2:-} skipped=${3:-}
  sx_severity_rank "$worst" >/dev/null ||
    px_die "$PX_EXIT_USAGE" "'$worst' is not a severity, expected one of $SX_SEVERITIES"
  if ! [[ $total =~ $PX_RE_INT ]] || ! [[ $skipped =~ $PX_RE_INT ]]; then
    px_die "$PX_EXIT_USAGE" "sx_result needs whole counts, got '$total' and '$skipped'"
  fi
  if [ "$skipped" -ge "$total" ]; then
    printf 'INCONCLUSIVE\n'
  else
    printf '%s\n' "$worst"
  fi
}

# Non-blocking outcomes (PASS, SKIP, and ADVISORY) map to 0, whereas FAIL and INCONCLUSIVE map to
# the failure exit code. ERROR maps to the anomaly exit code, which represents a harness or
# infrastructure fault everywhere in this harness.
## @description  Translates a severity level or INCONCLUSIVE result into a process exit code.
## @param        outcome The severity name or INCONCLUSIVE result to translate.
## @return       Writes the exit code to stdout and exits 0, or exits 6 on an unrecognized word.
sx_severity_code() {
  case "${1:-}" in
  PASS | SKIP | ADVISORY) printf '%s\n' "$PX_EXIT_OK" ;;
  FAIL | INCONCLUSIVE) printf '%s\n' "$PX_EXIT_FAIL" ;;
  ERROR) printf '%s\n' "$PX_EXIT_ANOMALY" ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
}

# ---------------------------------------------------------------------------
# Run directory resolution and output isolation enforcement.
# ---------------------------------------------------------------------------
# Every artifact produced by an action resides in a single directory designated by px-suite or
# px-run via PX_RUN_DIR. Resolving this location through the shared output policy guarantees that
# an action cannot write inside the working tree, even if supplied with a path that points there.

# Exits 2 when PX_RUN_DIR is unset, escapes the output root, or points inside the working tree, and
# exits 1 when the path cannot be resolved.
## @description  Resolves and validates the run directory for the current action.
## @return       Writes the absolute run directory to stdout and exits 0, or exits 2 or 1 on error.
sx_run_dir() {
  if [ -z "${PX_RUN_DIR:-}" ]; then
    px_die "$PX_EXIT_USAGE" \
      "PX_RUN_DIR is unset. px-suite and px-run set it for every test."
  fi
  px_out_resolve "$PX_RUN_DIR"
}

# Ensures the enclosing run directory exists before returning the stream path. Because the verdict
# stream is append-only, multiple checks executed within the same action never overwrite each other.
# Exits 1 when the run directory cannot be resolved or created, and exits 2 when the run directory
# violates the output policy.
## @description  Resolves the verdict stream file for the current action and creates its directory.
## @return       Writes the verdict file path to stdout and exits 0, or exits 1 or 2 on failure.
sx_verdict_file() {
  local dir status=0
  dir="$(sx_run_dir)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  mkdir -p "$dir" || px_die "$PX_EXIT_FAIL" "cannot create the run directory $dir"
  printf '%s/verdicts.tsv\n' "$dir"
}

# ---------------------------------------------------------------------------
# Verdict stream recording and severity aggregation.
# ---------------------------------------------------------------------------
# Each verdict is persisted as a three-column TSV record containing check, severity, and detail.
# Field values are sanitized with px_tsv_clean before writing because an embedded tab character in
# a detail message would corrupt column alignment for every downstream reader.

# Appends the sanitized TSV row to the action's verdict stream and logs the verdict to stderr at a
# diagnostic level corresponding to its severity weight. Exits 2 when the check name is empty or the
# severity is not in the lattice, exits 1 when the row cannot be formatted or written, and
# propagates the status of sx_verdict_file (2 or 1) when the stream cannot be opened.
## @description  Records a check verdict to the verdict stream and logs it to stderr.
## @param        check, severity, detail... The check name, severity level, and detail tokens.
## @return       Exits 0 on success, or exits 2 or 1 on invalid input or write failure.
sx_verdict() {
  local check=${1:-} severity=${2:-} detail="" file row status=0
  if [ $# -gt 2 ]; then
    shift 2
    detail="$*"
  fi
  [ -n "$check" ] || px_die "$PX_EXIT_USAGE" "a verdict needs a check name"
  sx_severity_rank "$severity" >/dev/null ||
    px_die "$PX_EXIT_USAGE" \
      "check '$check' carries unknown severity '$severity', expected one of $SX_SEVERITIES"
  file="$(sx_verdict_file)" || status=$?
  if [ "$status" != 0 ]; then
    return "$status"
  fi
  check="$(px_tsv_clean "$check")"
  detail="$(px_tsv_clean "$detail")"
  row="$(px_tsv_row "$check" "$severity" "$detail")" ||
    px_die "$PX_EXIT_FAIL" "cannot write the verdict of check '$check'"
  printf '%s\n' "$row" >>"$file" ||
    px_die "$PX_EXIT_FAIL" "cannot append to the verdict stream $file"
  case "$severity" in
  PASS) px_debug "$check: PASS $detail" ;;
  SKIP) px_info "$check: SKIP $detail" ;;
  ADVISORY) px_warn "$check: ADVISORY $detail" ;;
  *) px_error "$check: $severity $detail" ;;
  esac
  return 0
}

# Invoked by px-suite after each test completes so that a test exiting 0 after recording an
# ADVISORY verdict still surfaces that advisory outcome. Any row carrying an unrecognized severity
# is rejected and promoted to ERROR so that a misspelled FAIL cannot be mistaken for a pass. When
# the file is missing or contains no verdicts, absence is signaled by empty output rather than a
# non-zero exit status because callers invoke this function inside a command substitution, where
# Bash treats a non-zero return status as a failure in the caller.
## @description  Determines the highest-ranking severity recorded in a verdict stream file.
## @param        file The path to the verdict TSV file to inspect.
## @return       Writes the worst severity to stdout (or nothing if absent or empty) and exits 0.
sx_verdict_worst() {
  local file=${1:-} row rest severity worst="" lineno=0
  [ -f "$file" ] || return 0
  while IFS= read -r row || [ -n "$row" ]; do
    lineno=$((lineno + 1))
    [ -n "$row" ] || continue
    # Extract the second tab-delimited column via parameter expansion to avoid spawning a subshell;
    # a row lacking a second column leaves severity empty and is rejected by the rank check below.
    severity=""
    case "$row" in
    *"$PX_TAB"*)
      rest=${row#*"$PX_TAB"}
      severity=${rest%%"$PX_TAB"*}
      ;;
    esac
    if ! sx_severity_rank "$severity" >/dev/null; then
      px_error "$file line $lineno holds unknown severity '$severity'," \
        "the row is rejected and counts as ERROR"
      severity=ERROR
    fi
    worst="$(sx_severity_worse "${worst:-PASS}" "$severity")"
  done <"$file"
  [ -n "$worst" ] || return 0
  printf '%s\n' "$worst"
}
