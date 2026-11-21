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

# Shared library sourced by drivers, checks, and plug-in hooks to provide two core facilities: the
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
  printf 'px: suites/lib/common.sh is a library. Source it from a driver, a check or a hook.\n' >&2
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

# Callers compare severities through their numeric rank so that no caller duplicates the lattice
# ordering.
## @description  Resolves a single severity to its rank in the severity lattice.
## @return       Writes the rank (0 to 4) to stdout and exits 0, or exits 6 on an unknown word.
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

# Selecting the stronger of two severities implements the entire severity resolution policy.
## @description  Compares two severities and reports the stronger one.
## @return       Writes the stronger word to stdout and exits 0, or exits 2 on a word that is not a
##               valid severity.
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

# Evaluates the aggregate result of a set from its worst severity, total count, and SKIP count,
# resolving to the worst severity or to INCONCLUSIVE when the set is empty or holds only SKIP.
## @description  Computes the aggregate result for a set of severities.
## @return       Writes the result to stdout and exits 0, or exits 2 on an invalid argument.
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

# ---------------------------------------------------------------------------
# Run directory resolution and output isolation enforcement.
# ---------------------------------------------------------------------------
# Every artifact produced by a writer resides in a single directory designated by px-suite or px-run
# via PX_RUN_DIR. This library resolves that directory through the output policy so that a writer
# cannot write inside the working tree, even when given a path that points there.

## @description  Resolves and reports the PX_RUN_DIR run directory for the current writer.
## @return       Writes the directory to stdout and exits 0; exits 2 when unset, outside the output
##               root, or inside the tree, or exits 1 when it cannot be resolved.
sx_run_dir() {
  if [ -z "${PX_RUN_DIR:-}" ]; then
    px_die "$PX_EXIT_USAGE" \
      "PX_RUN_DIR is unset. px-suite and px-run set it for every test."
  fi
  px_out_resolve "$PX_RUN_DIR"
}

## @description  Resolves the append-only verdict stream path and creates its run directory.
## @return       Writes the path to stdout and exits 0; returns exit code 2 or 1 from sx_run_dir,
##               or exits 1 if the directory cannot be created.
sx_verdict_file() {
  local dir
  dir="$(sx_run_dir)" || return $?
  mkdir -p "$dir" || px_die "$PX_EXIT_FAIL" "cannot create the run directory $dir"
  printf '%s/verdicts.tsv\n' "$dir"
}

# ---------------------------------------------------------------------------
# Verdict stream recording and severity aggregation.
# ---------------------------------------------------------------------------
# Each verdict is recorded as a single TSV row with five columns: check, severity, class, source,
# and detail. Keeping severity in the second column allows any reader computing the worst severity
# to inspect that column alone. The class column identifies the evidence class: count, share, and
# duration correspond to the metric classes in docs/driver-contract.md, whereas none indicates a
# functional or structural check. px-report demotes a failed duration verdict to ADVISORY whenever
# the run's time evidence is not authoritative, and never demotes any other class. The source column
# records the writer from PX_RECORD_SOURCE. The detail message is sanitized through px_tsv_clean
# because an embedded tab would break column alignment for every reader. See docs/bundle-format.md,
# section "verdicts.tsv", for the specification.

SX_CLASSES="count share duration none"

## @description  Appends a verdict row (check, severity, class, detail...) and logs it to stderr.
## @return       Exits 0, or propagates exit code 2 or 1 from sx_verdict_file; exits 2 on an empty
##               check or an unknown severity or class, and exits 1 if the row cannot be written.
sx_verdict_row() {
  local check=${1:-} severity=${2:-} class=${3:-} detail="${*:4}" file source
  [ -n "$check" ] || px_die "$PX_EXIT_USAGE" "a verdict needs a check name"
  sx_severity_rank "$severity" >/dev/null ||
    px_die "$PX_EXIT_USAGE" \
      "check '$check' carries unknown severity '$severity', expected one of $SX_SEVERITIES"
  case " $SX_CLASSES " in
  *" $class "*) ;;
  *)
    px_die "$PX_EXIT_USAGE" "check '$check' carries unknown class '$class'," \
      "expected one of $SX_CLASSES"
    ;;
  esac
  file="$(sx_verdict_file)" || return $?
  check="$(px_tsv_clean "$check")"
  detail="$(px_tsv_clean "$detail")"
  source="$(px_tsv_clean "${PX_RECORD_SOURCE:-action}")"
  px_tsv_row "$check" "$severity" "$class" "$source" "$detail" >>"$file" ||
    px_die "$PX_EXIT_FAIL" "cannot append to the verdict stream $file"
  case "$severity" in
  PASS) px_debug "$check: PASS $detail" ;;
  SKIP) px_info "$check: SKIP $detail" ;;
  ADVISORY) px_warn "$check: ADVISORY $detail" ;;
  *) px_error "$check: $severity $detail" ;;
  esac
  return 0
}

## @description  Records a verdict (check, severity, detail...) with class none for a functional or
##               structural check that no host condition can demote.
## @return       Exits 0, or returns the exit status of sx_verdict_row.
sx_verdict() { sx_verdict_row "${1:-}" "${2:-}" none "${@:3}"; }

## @description  Looks up a threshold (key) in PX_THRESHOLDS_FILE, the merged cluster-check set.
## @return       Writes the value to stdout and exits 0; exits 6 when the key or file is absent, or
##               exits 2 when PX_THRESHOLDS_FILE is unset.
sx_threshold() {
  local key=${1:-} file=${PX_THRESHOLDS_FILE:-} value
  [ -n "$file" ] || px_die "$PX_EXIT_USAGE" \
    "PX_THRESHOLDS_FILE is unset. suites/check-cluster.sh sets it for every check."
  [ -f "$file" ] || return "$PX_EXIT_NO_OBJECT"
  value="$(awk -v k="$key" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2); f = 1 }
    END { if (!f) exit 6; print v }' "$file" || exit "$?")" || return "$PX_EXIT_NO_OBJECT"
  printf '%s\n' "$value"
}

# Maps a shared-vocabulary exit code onto the severity lattice: 0 to PASS, 5 to SKIP, 2, 3, and 6
# to ERROR, and any other exit code to FAIL.
## @description  Translates a shared-vocabulary process exit code into a severity lattice level.
## @return       Writes the severity to stdout and exits 0.
sx_severity_of_code() {
  case "${1:-}" in
  "$PX_EXIT_OK") printf 'PASS\n' ;;
  "$PX_EXIT_UNSUPPORTED") printf 'SKIP\n' ;;
  "$PX_EXIT_USAGE" | "$PX_EXIT_ANOMALY" | "$PX_EXIT_NO_OBJECT") printf 'ERROR\n' ;;
  *) printf 'FAIL\n' ;;
  esac
}

# Treats any row carrying an unknown severity as ERROR so that a misspelled FAIL cannot pass.
## @description  Reports the worst severity recorded in a verdict stream.
## @return       Writes the worst severity to stdout, or nothing for an absent or empty file, and
##               exits 0.
sx_verdict_worst() {
  # Signal absence through empty output rather than a non-zero exit status, because callers read
  # this function via command substitution where Bash treats a non-zero status as a caller failure.
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
