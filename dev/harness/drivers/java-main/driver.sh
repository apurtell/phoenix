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

# The java-main workload driver executes a single Java main class inside the client 0 instance and
# converts the metric lines printed by that class into aggregate metric records. The full driver
# specification is defined in docs/driver-contract.md under the "java-main" section.
#
# The target class receives the cluster's JDBC connection URL through the px.jdbc.url Java system
# property, appended with the RequestMetric=true connection attribute. A class that establishes its
# connections using this provided URL automatically enables Phoenix request metrics, whereas a class
# that constructs its own JDBC URL collects none.
#
# Test workload definitions follow the argument syntax:
#
#   [--cp FILE]... [--prepare FILE]... CLASS [ARGUMENT...]
#
# Each file or directory supplied via --cp (such as the JAR containing the target class) is staged
# into the remote working directory during the prepare phase and prepended to the Java classpath
# ahead of the Phoenix client JARs during the run phase. Each --prepare argument designates a SQL
# script that follows the semantics of psql --prepare: the prepare phase executes the script via
# PhoenixRuntime within the setup@schema anchor and records the created tables, while the restore
# phase drops those tables. This lifecycle allows the target class to query its own dedicated tables
# without interference from prior tests, whose tables are removed during their respective restore
# phases. The first positional argument names the target main class, and all remaining arguments are
# passed through to that class unmodified.
#
# The executed class emits metrics by writing formatted lines to standard output:
#
#   px-metric <key> <class> <value> <unit>
#
# The run phase captures the complete standard output stream in $PX_DRIVER_DIR/output.txt. During
# the report phase, each px-metric line is recorded as an aggregate metric entry for the steady@main
# window, while all other lines remain preserved as general program output.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/driver.sh
. "$SELF/../lib/driver.sh"

set -euo pipefail
px_trap_enable

JM_NAME=java-main
JM_CLASS_RE='^[A-Za-z_$][A-Za-z0-9_$]*([.][A-Za-z_$][A-Za-z0-9_$]*)*$'
JM_CP=()
JM_PREPARE=()
JM_CLASS=""
JM_ARGS=()

## @description  Parses workload arguments to populate JM_CP, JM_PREPARE, JM_CLASS, and JM_ARGS.
## @return       Returns exit code 0 on success, or exits with code 2 on an unknown option, a
##               missing option value, or a missing main class name.
jm_args() {
  JM_CP=()
  JM_PREPARE=()
  JM_CLASS=""
  JM_ARGS=()
  while [ $# -gt 0 ]; do
    case "$1" in
    --cp)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "--cp needs a file or a directory"
      JM_CP+=("$(dv_path "$2")")
      shift 2
      ;;
    --prepare)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "--prepare needs a SQL file"
      JM_PREPARE+=("$(dv_path "$2")")
      shift 2
      ;;
    -*)
      px_die "$PX_EXIT_USAGE" "unknown option '$1', expected [--cp FILE]... [--prepare FILE]..." \
        "CLASS [ARG...]"
      ;;
    *)
      JM_CLASS=$1
      shift
      JM_ARGS=("$@")
      break
      ;;
    esac
  done
  [ -n "$JM_CLASS" ] || px_die "$PX_EXIT_USAGE" "java-main needs a main class"
}

## @description  Emits the self-description metadata document for the java-main driver.
## @return       Writes the descriptor document to stdout and returns exit code 0.
verb_describe() {
  dv_describe "$JM_NAME" \
    "Runs one Java main class with px.jdbc.url and records the metrics it prints." \
    "setup@schema|prepare
steady@main|run" \
    "query_errors" \
    "" \
    "" \
    "query_latency.p50_ms|only the class knows what it measures
query_latency.p99_ms|only the class knows what it measures
offered_rate|only the class knows what it measures
achieved_qps|only the class knows what it measures" \
    "oplog/|no operation log" \
    "drivers/$JM_NAME/output.txt"
}

# Verifies that the target class name is a valid Java binary class name, every --cp path is
# readable, and every --prepare path refers to a readable regular file ending in .sql.
## @description  Validates the workload definition arguments (argument...).
## @return       Returns exit code 0 when valid, 1 when invalid, or exits with 2 on bad arguments.
verb_validate() {
  local entry failed=0
  jm_args "$@"
  if ! [[ $JM_CLASS =~ $JM_CLASS_RE ]]; then
    failed=$((failed + 1))
    sx_verdict java-main.class FAIL "'$JM_CLASS' is not a Java class name"
  fi
  for entry in ${JM_CP[@]+"${JM_CP[@]}"}; do
    if [ ! -r "$entry" ]; then
      failed=$((failed + 1))
      sx_verdict java-main.classpath FAIL "$entry: no readable file or directory"
    fi
  done
  # PhoenixRuntime selects its SQL script reader based on the filename suffix.
  for entry in ${JM_PREPARE[@]+"${JM_PREPARE[@]}"}; do
    case "$entry" in
    *.sql) [ -f "$entry" ] && [ -r "$entry" ] && continue ;;
    esac
    failed=$((failed + 1))
    sx_verdict java-main.prepare FAIL "$entry: no readable SQL file whose name ends in .sql"
  done
  if [ "$failed" != 0 ]; then
    sx_verdict driver.workload_valid FAIL "$failed parts of the definition are refused"
    return "$PX_EXIT_FAIL"
  fi
  sx_verdict driver.workload_valid PASS "class $JM_CLASS, ${#JM_CP[@]} class path entries"
}

# When neither --cp nor --prepare is specified, the prepare phase performs no cluster operations:
# it creates no remote working directory, opens no setup@schema stage window, and runs no catalog
# query. Otherwise, it uploads each --cp entry, executes each --prepare SQL script within the
# setup@schema window, and records the catalog metadata for all tables created by those scripts.
## @description  Stages --cp paths, runs --prepare scripts in setup@schema, and records tables.
## @return       Returns exit code 0 on success, the driver code (1, 2, or 3) of a failed copy or
##               SQL script execution, or exits with code 2 on invalid arguments.
verb_prepare() {
  local entry index=0 url status=0
  jm_args "$@"
  [ "${#JM_CP[@]}" -gt 0 ] || [ "${#JM_PREPARE[@]}" -gt 0 ] || return 0
  dv_cluster
  dv_remote >/dev/null || return $?
  for entry in ${JM_CP[@]+"${JM_CP[@]}"}; do
    index=$((index + 1))
    dv_put "$entry" "cp/$index-${entry##*/}" || return $?
  done
  [ "${#JM_PREPARE[@]}" -gt 0 ] || return 0
  dv_tables_parse "${JM_PREPARE[@]}" >"$PX_DRIVER_DIR/tables.list" || return "$PX_EXIT_FAIL"
  url="$(dv_jdbc_url || exit "$?")" || return $?
  mkdir -p "$PX_DRIVER_DIR/out" || return "$PX_EXIT_FAIL"
  index=0
  pb_stage begin setup@schema || return
  for entry in "${JM_PREPARE[@]}"; do
    index=$((index + 1))
    # Bound workload execution by PX_TIMEOUT_RUN, the timeout shared across the prepare and run
    # verbs; px-run terminates the active verb first when that shared deadline expires.
    dv_sql "${PX_TIMEOUT_RUN:-3600}" "$url" "$entry" "$PX_DRIVER_DIR/out/prepare-$index.out" ||
      status=$?
    if [ "$status" != 0 ]; then
      sx_verdict java-main.prepare FAIL "$entry: PhoenixRuntime failed with driver code $status"
      break
    fi
  done
  pb_stage end setup@schema || return
  [ "$status" = 0 ] || return "$status"
  dv_tables_record "$PX_DRIVER_DIR/tables.list" "${PX_TIMEOUT_DEFAULT:-300}" "$url"
}

# Executes the target main class within the steady@main stage, passing the cluster JDBC URL via the
# px.jdbc.url system property with request metrics enabled and capturing stdout in output.txt.
## @description  Executes the configured Java main class inside the steady@main measurement stage.
## @return       Returns exit code 0 on success, 1 if the class fails, 3 if client 0 cannot execute
##               it, or exits with code 2 on invalid arguments.
verb_run() {
  local entry index=0 start end url status=0
  local cp=()
  jm_args "$@"
  dv_cluster
  dv_remote >/dev/null || return $?
  url="$(dv_jdbc_url)" || return $?
  for entry in ${JM_CP[@]+"${JM_CP[@]}"}; do
    index=$((index + 1))
    cp+=(--cp "cp/$index-${entry##*/}")
  done
  rm -f "$PX_DRIVER_DIR/run.tsv"
  pb_stage begin steady@main || return
  start="$(px_epoch_ms)"
  # Bound workload execution by PX_TIMEOUT_RUN, the timeout shared across the prepare and run
  # verbs; px-run terminates the active verb first when that shared deadline expires.
  dv_java "${PX_TIMEOUT_RUN:-3600}" ${cp[@]+"${cp[@]}"} \
    --opt "-Dpx.jdbc.url=$url;RequestMetric=true" "$JM_CLASS" ${JM_ARGS[@]+"${JM_ARGS[@]}"} \
    >"$PX_DRIVER_DIR/output.txt" || status=$?
  end="$(px_epoch_ms)"
  pb_stage end steady@main || return
  pb_header "$PX_DRIVER_DIR/run.tsv" "start_ms end_ms exit_status" || return
  px_tsv_row "$start" "$end" "$DV_STATUS" >>"$PX_DRIVER_DIR/run.tsv" || return "$PX_EXIT_FAIL"
  if [ "$status" != 0 ]; then
    sx_verdict java-main.exit FAIL "class $JM_CLASS ended with status $DV_STATUS"
  fi
  return "$status"
}

# Cleans up the state provisioned during prepare by dropping all tables created by the --prepare
# SQL scripts and removing the remote working directory; repeated calls are idempotent.
## @description  Idempotently drops --prepare tables and removes the remote working directory.
## @return       Returns exit code 0 on success, the driver code (1, 2, or 3) of a failed table
##               drop or directory removal, or exits with code 2 on invalid arguments.
verb_restore() {
  jm_args "$@"
  dv_cluster
  dv_tables_restore
}

# Parses px-metric lines from output.txt into aggregate metric records for the steady@main window,
# synthesizing a fallback query_errors metric from the process exit status when none is emitted.
## @description  Records aggregate metrics for steady@main from the captured output.txt stream.
## @return       Returns exit code 0 when metrics are written or nothing is runnable to report, 1
##               if output records cannot be written, or exits with code 2 on invalid arguments.
verb_report() {
  local start end tag key class value unit extra count=0 bad=0 errors=""
  jm_args "$@"
  dv_window java-main.metrics steady@main "the class did not run" || return 0
  start=$DV_START end=$DV_END
  while read -r tag key class value unit extra; do
    [ "$tag" = px-metric ] || continue
    if [ -z "$unit" ] || [ -n "$extra" ] || ! [[ $key =~ $PB_KEY_RE ]] ||
      ! [[ $value =~ $PX_RE_NUMBER ]] || ! [[ $unit =~ $PB_UNIT_RE ]]; then
      bad=$((bad + 1))
      continue
    fi
    case " $PB_METRIC_CLASSES " in
    *" $class "*) ;;
    *)
      bad=$((bad + 1))
      continue
      ;;
    esac
    pb_metric "$key" "$class" "$value" "$unit" steady@main "$start" "$end" || return
    [ "$key" != query_errors ] || errors=$value
    count=$((count + 1))
  done <"$PX_DRIVER_DIR/output.txt" 2>/dev/null
  # If the executed class does not emit an explicit query_errors metric line, record an error count
  # of 1 when the process exited with a non-zero status, or 0 when it exited cleanly with status 0.
  if [ -z "$errors" ]; then
    errors=1
    [ "$(awk -F '\t' 'NR == 2 { print $3 }' "$PX_DRIVER_DIR/run.tsv")" != 0 ] || errors=0
    pb_metric query_errors count "$errors" errors steady@main "$start" "$end" || return
  fi
  if [ "$bad" != 0 ]; then
    sx_verdict java-main.metrics FAIL "$bad px-metric lines are malformed, expected" \
      "'px-metric <key> <class> <value> <unit>' with a class of $PB_METRIC_CLASSES"
  else
    sx_verdict java-main.metrics PASS "$count metrics"
  fi
}

dv_main "$@"
