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

# This module implements the psql workload driver, which executes SQL script files sequentially
# through PhoenixRuntime on client 0 and records per-statement row counts, the total statement
# count, and per-file execution times. The governing specification is docs/driver-contract.md,
# section "psql".
#
# A test invocation accepts arguments of the form:
#
#   [--prepare FILE]... FILE...
#
# Files supplied via --prepare run during the prepare phase inside the setup@schema anchor to
# provision the schema and initial data, while all remaining positional files run during the run
# phase inside the steady@script anchor. Every script filename must end in .sql because
# PhoenixRuntime selects its SQL reader based on that file suffix. Because PhoenixRuntime halts at
# the first failing statement, any file failure terminates the remaining script sequence.
#
# The run phase persists each file's raw output under $PX_DRIVER_DIR/out, and the report phase
# derives all records solely from those captured outputs so that re-evaluating a copied bundle on
# another host yields identical results. The restore phase drops every table named by a CREATE TABLE
# statement in any of the test's script files.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/driver.sh
. "$SELF/../lib/driver.sh"

set -euo pipefail
px_trap_enable

PSQL_NAME=psql
PSQL_PREPARE=()
PSQL_FILES=()

## @description  Parse test arguments into PSQL_PREPARE and PSQL_FILES as resolved absolute paths.
## @return       Exit 0 on success, or exit 2 on an unknown option, a missing option value, or when
##               no run file is provided.
psql_args() {
  PSQL_PREPARE=()
  PSQL_FILES=()
  while [ $# -gt 0 ]; do
    case "$1" in
    --prepare)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "--prepare needs a SQL file"
      PSQL_PREPARE+=("$(dv_path "$2")")
      shift 2
      ;;
    -*) px_die "$PX_EXIT_USAGE" "unknown option '$1', expected [--prepare FILE]... FILE..." ;;
    *)
      PSQL_FILES+=("$(dv_path "$1")")
      shift
      ;;
    esac
  done
  [ "${#PSQL_FILES[@]}" -gt 0 ] || px_die "$PX_EXIT_USAGE" "psql needs at least one SQL file"
}

## @description  Stage SQL files (kind, file...) into the remote work directory under
##               sql/<kind>-<n>.sql.
## @return       Exit 0 on success, or return the driver exit code from the copy (1, 2, or 3).
psql_stage() {
  local kind=$1 file index=0
  shift
  for file in "$@"; do
    index=$((index + 1))
    dv_put "$file" "sql/$kind-$index.sql" || return $?
  done
}

# Execute staged SQL files (kind, URL, file...) sequentially, capturing each file's standard output
# in out/<kind>-<n>.out and recording one timing and status row per file in <kind>.tsv, halting on
# the first failure.
## @description  Execute staged SQL files (kind, URL, file...) in order and record their outputs
##               and execution rows, stopping on the first failure.
## @return       Exit 0 on success, or return the driver exit code of the failed file (1, 2, or 3).
psql_exec() {
  local kind=$1 url=$2 file index=0 start end status=0 record remote
  shift 2
  record="$PX_DRIVER_DIR/$kind.tsv"
  remote="$(dv_remote_path)" || return $?
  rm -rf "$PX_DRIVER_DIR/out/$kind"-*.out "$record"
  mkdir -p "$PX_DRIVER_DIR/out" || return "$PX_EXIT_FAIL"
  pb_header "$record" "index file start_ms end_ms status" || return
  for file in "$@"; do
    index=$((index + 1))
    start="$(px_epoch_ms)"
    # Bound the workload by PX_TIMEOUT_RUN, the timeout bound for prepare and run; px-run stops the
    # verb first when its shared deadline is reached.
    dv_java "${PX_TIMEOUT_RUN:-3600}" "$DV_RUNTIME_CLASS" "$url" \
      "$remote/sql/$kind-$index.sql" >"$PX_DRIVER_DIR/out/$kind-$index.out" || status=$?
    end="$(px_epoch_ms)"
    px_tsv_row "$index" "$(px_tsv_clean "$file")" "$start" "$end" "$status" >>"$record" ||
      return "$PX_EXIT_FAIL"
    if [ "$status" != 0 ]; then
      sx_verdict psql.execution FAIL "$file: PhoenixRuntime failed with driver code $status," \
        "so the later files did not run"
      return "$status"
    fi
  done
}

# Parse statement execution summaries from a single PhoenixRuntime output file (file) by matching
# each statement's update-count line or query result rows up to its terminating Time line.
## @description  Extract per-statement operation, row count, and duration from a PhoenixRuntime
##               output file (file).
## @return       Write "statement operation rows time_ms" rows to stdout and exit 0.
psql_statements() {
  [ -f "$1" ] || return 0
  awk '
    /^Time: [-+0-9.eE]+ sec\(s\)$/ {
      n++
      printf "%d\t%s\t%d\t%d\n", n, (op == "" ? "none" : op), rows, int($2 * 1000 + 0.5)
      op = ""; rows = 0; data = 0
      next
    }
    data { if (NF) rows++; next }
    /^(no|[0-9]+) rows? [a-z]+$/ { op = $NF; rows = ($1 == "no") ? 0 : $1 + 0; next }
    /^(-+ )+$/ { op = "selected"; rows = 0; data = 1 }
  ' "$1"
}

## @description  Emit the static capability and metric descriptor document for the psql driver.
## @return       Write the describe document to stdout and exit 0.
verb_describe() {
  dv_describe "$PSQL_NAME" \
    "Runs SQL scripts and records the rows of each statement and the time of each file." \
    "setup@schema|prepare
steady@script|run" \
    "psql.statements
psql.rows
psql.statement_rows
query_errors" \
    "" \
    "psql.file_duration_ms" \
    "query_latency.p50_ms|no latency per operation
query_latency.p99_ms|no latency per operation
offered_rate|no schedule
achieved_qps|no schedule
driver.storage_scans|request metrics are off
driver.rpc_calls|request metrics are off" \
    "oplog/|no operation log" \
    "drivers/$PSQL_NAME/statements.tsv"
}

## @description  Verify that each test SQL file (argument...) exists, is readable, is non-empty,
##               and ends in .sql, reporting every refused file.
## @return       Exit 0 when every file is valid, exit 1 otherwise, or exit 2 on invalid arguments.
verb_validate() {
  local file problem count=0 failed=0
  psql_args "$@"
  for file in ${PSQL_PREPARE[@]+"${PSQL_PREPARE[@]}"} "${PSQL_FILES[@]}"; do
    count=$((count + 1))
    problem=""
    if [ ! -f "$file" ]; then
      problem="no such file"
    elif [ ! -r "$file" ]; then
      problem="the file is not readable"
    elif [ ! -s "$file" ]; then
      problem="the file is empty"
    fi
    case "$file" in
    *.sql) ;;
    *) problem="${problem:+$problem; }PhoenixRuntime runs only a file whose name ends in .sql" ;;
    esac
    if [ -n "$problem" ]; then
      failed=$((failed + 1))
      sx_verdict psql.script FAIL "$file: $problem"
    fi
  done
  if [ "$failed" != 0 ]; then
    sx_verdict driver.workload_valid FAIL "$failed of $count SQL files are refused"
    return "$PX_EXIT_FAIL"
  fi
  sx_verdict driver.workload_valid PASS "$count SQL files"
}

## @description  Catalog all test tables for restore, execute --prepare files inside setup@schema,
##               and record the tables that they create.
## @return       Exit 0 on success, return driver code 1, 2, or 3, or exit 2 on bad arguments.
verb_prepare() {
  local url status=0 created="$PX_DRIVER_DIR/prepare-tables.list"
  psql_args "$@"
  dv_cluster
  dv_tables_parse ${PSQL_PREPARE[@]+"${PSQL_PREPARE[@]}"} "${PSQL_FILES[@]}" \
    >"$PX_DRIVER_DIR/tables.list" || return "$PX_EXIT_FAIL"
  [ "${#PSQL_PREPARE[@]}" -gt 0 ] || return 0
  dv_tables_parse "${PSQL_PREPARE[@]}" >"$created" || return "$PX_EXIT_FAIL"
  dv_remote >/dev/null || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  psql_stage prepare "${PSQL_PREPARE[@]}" || return $?
  pb_stage begin setup@schema || return
  psql_exec prepare "$url" "${PSQL_PREPARE[@]}" || status=$?
  pb_stage end setup@schema || return
  [ "$status" = 0 ] || return "$status"
  dv_tables_record "$created" "${PX_TIMEOUT_DEFAULT:-300}" "$url"
}

## @description  Stage the workload SQL files and execute them sequentially inside steady@script.
## @return       Exit 0 on success, return the failed file's driver code (1, 2, or 3), or exit 2 on
##               bad arguments.
verb_run() {
  local url status=0
  psql_args "$@"
  dv_cluster
  dv_remote >/dev/null || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  psql_stage run "${PSQL_FILES[@]}" || return $?
  pb_stage begin steady@script || return
  psql_exec run "$url" "${PSQL_FILES[@]}" || status=$?
  pb_stage end steady@script || return
  return "$status"
}

## @description  Drop every table created by the test scripts and remove the remote work directory
##               idempotently so repeated calls find nothing to do.
## @return       Exit 0 on success, return driver code 1, 2, or 3, or exit 2 on bad arguments.
verb_restore() {
  psql_args "$@"
  dv_cluster
  dv_tables_restore
}

## @description  Compile captured run outputs into statements.tsv, metric records, and query_errors.
## @return       Exit 0 when the report is written or run left nothing, exit 1 when it cannot be
##               written, or exit 2 on bad arguments.
verb_report() {
  local record="$PX_DRIVER_DIR/run.tsv" table="$PX_DRIVER_DIR/statements.tsv" start end
  local index file fstart fend status rows statements=0 total_rows=0 errors=0 duration=0 files=0
  local number count
  psql_args "$@"
  dv_window query_errors steady@script "no statement ran" || return 0
  start=$DV_START end=$DV_END
  rm -f "$table"
  pb_header "$table" "file index statement operation rows time_ms" || return
  while IFS="$PX_TAB" read -r index file fstart fend status; do
    [ "$index" != index ] && [ -n "$index" ] || continue
    files=$((files + 1))
    rows="$(psql_statements "$PX_DRIVER_DIR/out/run-$index.out")"
    printf '%s\n' "$rows" | awk -v f="$file" -v i="$index" 'NF { print f "\t" i "\t" $0 }' \
      >>"$table" || return "$PX_EXIT_FAIL"
    # Emit one aggregate record per statement under the statement@<file>.<statement> anchor and one
    # aggregate record per file under the script@<file> anchor, both bounded by that file's time
    # window, while the steady@script records sum the totals across all files.
    while IFS="$PX_TAB" read -r number _ count _; do
      [ -n "$number" ] || continue
      statements=$((statements + 1))
      total_rows=$((total_rows + count))
      pb_metric psql.statement_rows count "$count" rows "statement@$index.$number" "$fstart" \
        "$fend" || return
    done <<EOF
$rows
EOF
    [ "$status" != 1 ] || errors=$((errors + 1))
    duration=$((duration + fend - fstart))
    pb_metric psql.file_duration_ms duration "$((fend - fstart))" ms "script@$index" "$fstart" \
      "$fend" || return
    pb_sample psql.file_duration_ms duration "$((fend - fstart))" ms steady@script "$fend" ||
      return
  done <"$record"
  pb_metric psql.statements count "$statements" statements steady@script "$start" "$end" || return
  pb_metric psql.rows count "$total_rows" rows steady@script "$start" "$end" || return
  pb_metric psql.file_duration_ms duration "$duration" ms steady@script "$start" "$end" || return
  pb_metric query_errors count "$errors" errors steady@script "$start" "$end" || return
  if [ "$errors" = 0 ]; then
    sx_verdict_row query_errors PASS count "$statements statements in $files files, no error"
  else
    sx_verdict_row query_errors FAIL count "$errors of $files script files failed"
  fi
}

dv_main "$@"
