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

# Implements the workload driver for the example plug-in as a six-verb driver conforming to
# docs/driver-contract.md and built with the driver kit. Because its workload operates on the
# dataset that the fetch-data hook stages into the run bundle, it requires neither network access
# nor a database table:
#   validate: Verifies that the plug-in dataset parses with a key and value on each row.
#   prepare:  Verifies that the staged copy exists and records its row count.
#   run:      Executes a single steady@example window that counts the rows again.
#   restore:  Removes the record written by prepare.
#   report:   Verifies that the run count equals the row count of the dataset.
# Every verb persists its state in PX_DRIVER_DIR so that no verb depends on in-memory state from an
# earlier invocation, ensuring that identical counts produce the same verdict on every run.

EX_HOME="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [ -z "${PX_HOME:-}" ]; then
  printf 'px: the example driver needs PX_HOME, which px-run exports\n' >&2
  exit 2
fi
# shellcheck source=SCRIPTDIR/../../drivers/lib/driver.sh
. "$PX_HOME/drivers/lib/driver.sh"

set -euo pipefail
px_trap_enable

# Defines the stage anchor identifier for this driver's single steady-state measurement window.
EX_ANCHOR=steady@example

## @description  Rejects any command-line test arguments because the example workload takes none.
## @return       Exits 0 when no arguments are given, or exits 2 otherwise.
ex_no_args() {
  [ $# -eq 0 ] || px_die "$PX_EXIT_USAGE" "the example driver takes no test argument, got: $*"
}

## @description  Counts the tab-separated key-value data rows in a dataset file (path).
## @return       Writes the row count to stdout and exits 0, or exits 1 (logging the cause to
##               stderr) when the file is absent or a row does not contain a key and value.
ex_rows() {
  [ -f "$1" ] || { px_error "no dataset at $1"; return "$PX_EXIT_FAIL"; }
  awk -F '\t' '/^#/ || $0 == "" { next }
    NF != 2 || $1 == "" { printf "%s line %d is not key and value\n", FILENAME, NR > "/dev/stderr"
      bad = 1; next }
    { n++ } END { if (bad) exit 1; print n + 0 }' "$1"
}

## @description  Reports the bundle path of the dataset copy staged by the fetch-data hook.
## @return       Writes the staged file path to stdout and exits 0.
ex_staged() { printf '%s/plugins/%s/data/example.tsv\n' "$PX_RUN_DIR" "$PX_DRIVER_NAME"; }

## @description  Emits the driver description document declaring its phase, metric classes,
##               unsupported steady-state metrics, and output files.
## @return       Writes the description document to stdout and exits 0.
verb_describe() {
  local name=${PX_DRIVER_NAME:-example} none="the example workload issues no query"
  dv_describe "$name" "Counts the rows of the dataset that the example plug-in stages." \
    "$EX_ANCHOR|run" "plugin.example.rows
query_errors" "" "" \
    "query_latency.p50_ms|$none
query_latency.p99_ms|$none
offered_rate|$none
achieved_qps|$none" \
    "oplog/|the example workload has no operation to sample" \
    "drivers/$name/rows.tsv"
}

## @description  Validates that the committed dataset of the example plug-in parses cleanly.
## @return       Exits 0 when the dataset parses, or returns 1 when it does not.
verb_validate() {
  local count
  ex_no_args "$@"
  if count="$(ex_rows "$EX_HOME/data/example.tsv" || exit "$?")"; then
    sx_verdict driver.workload_valid PASS "the dataset holds $count rows"
    return 0
  fi
  sx_verdict driver.workload_valid FAIL "the dataset $EX_HOME/data/example.tsv does not parse"
  return "$PX_EXIT_FAIL"
}

## @description  Verifies the staged dataset and records its row count in PX_DRIVER_DIR.
## @return       Exits 0, or returns 1 when the staged dataset is absent or fails to parse.
verb_prepare() {
  local count
  ex_no_args "$@"
  if ! count="$(ex_rows "$(ex_staged)" || exit "$?")"; then
    sx_verdict driver.prepare FAIL "the fetch-data hook staged no valid dataset at $(ex_staged)"
    return "$PX_EXIT_FAIL"
  fi
  printf '%s\n' "$count" >"$PX_DRIVER_DIR/prepared.txt"
}

## @description  Counts the staged rows within a single steady-state window and records the row
##               count and error count as aggregate metrics for that window.
## @return       Exits 0 on success, or returns 1 when the staged dataset cannot be read.
verb_run() {
  local count window start end
  ex_no_args "$@"
  pb_stage begin "$EX_ANCHOR"
  count="$(ex_rows "$(ex_staged)" || exit "$?")" || count=""
  pb_stage end "$EX_ANCHOR"
  window="$(pb_stage_window "$EX_ANCHOR" || exit "$?")"
  start=${window%%"$PX_TAB"*}
  end=${window#*"$PX_TAB"}
  if [ -z "$count" ]; then
    pb_metric query_errors count 1 errors "$EX_ANCHOR" "$end" "$end"
    sx_verdict driver.run FAIL "the staged dataset $(ex_staged) cannot be read"
    return "$PX_EXIT_FAIL"
  fi
  px_tsv_row rows "$count" >"$PX_DRIVER_DIR/rows.tsv"
  pb_metric plugin.example.rows count "$count" rows "$EX_ANCHOR" "$start" "$end"
  pb_metric query_errors count 0 errors "$EX_ANCHOR" "$end" "$end"
}

## @description  Removes the state record written by prepare; a repeat call finds nothing and
##               succeeds.
## @return       Exits 0 on success, or returns 1 when the record cannot be removed.
verb_restore() {
  ex_no_args "$@"
  rm -f "$PX_DRIVER_DIR/prepared.txt"
}

## @description  Compares the run row count against the prepared dataset count using only bundle
##               state.
## @return       Exits 0 after writing the verdict row.
verb_report() {
  local prepared="" counted=""
  ex_no_args "$@"
  if [ -f "$PX_DRIVER_DIR/rows.tsv" ]; then
    counted="$(px_tsv_field 2 "$(cat "$PX_DRIVER_DIR/rows.tsv")" || true)"
  fi
  if [ -z "$counted" ]; then
    sx_verdict_row plugin.example.rows SKIP count "run recorded no row count"
    return 0
  fi
  # Because restore removes the record written by prepare, derive the expected count from the
  # plug-in dataset that prepare originally staged.
  prepared="$(ex_rows "$EX_HOME/data/example.tsv" || true)"
  if [ "$counted" = "$prepared" ]; then
    sx_verdict_row plugin.example.rows PASS count "run counted $counted rows, as staged"
  else
    sx_verdict_row plugin.example.rows FAIL count "run counted $counted rows," \
      "the dataset holds ${prepared:-no valid row}"
  fi
}

dv_main "$@"
