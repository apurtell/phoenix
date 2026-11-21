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

# The pherf driver executes a Pherf scenario through the Pherf main class on client 0 and converts
# the resulting Pherf aggregate CSV output into one measurement interval per query group. The
# authoritative specification is in docs/driver-contract.md under the "pherf" section.
#
# A test invocation accepts the following arguments:
#
#   --scenario FILE [--schema FILE]... [--properties FILE]
#   [--scenario-name NAME] [-- PHERF_OPTION...]
#
# Until round P1 updates the resource loader, Pherf discovers its scenario and schema files by
# matching a regular expression pattern against the class path. The driver therefore stages those
# files into a directory placed first on the class path under scenario/<name> and datamodel/<name>,
# which serve as the resource roots of Pherf. The generated patterns match only the staged
# directory so that files packaged inside a Pherf jar are never matched.
#
# Because the Pherf result directory defaults to the relative path RESULTS, the staged directory
# also includes a pherf.properties file that Pherf loads from the class path, configured to place
# both the result directory and the summary file inside the remote work directory. All remaining
# property keys come from --properties, or else from phoenix-pherf/config/pherf.properties in the
# working tree, ensuring that nothing is written into the working tree.
#
# The prepare phase applies the schema and loads data (-l) inside the setup@load stage. The run
# phase executes the query sets (-q) inside the steady@pherf stage and copies the remote result
# directory into $PX_DRIVER_DIR/results. The report phase reads exclusively from that local copy.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/driver.sh
. "$SELF/../lib/driver.sh"

set -euo pipefail
px_trap_enable

PHERF_NAME=pherf
PHERF_CLASS=org.apache.phoenix.pherf.Pherf
PHERF_SCENARIO=""
PHERF_SCHEMAS=()
PHERF_PROPERTIES=""
PHERF_SCENARIO_NAME=""
PHERF_OPTIONS=()

# These are the column names from the AGGREGATE_PERFORMANCE header defined in
# phoenix-pherf/src/main/java/org/apache/phoenix/pherf/result/file/Header.java that the report
# phase extracts and processes.
PHERF_COLUMNS="START_TIME QUERY_GROUP AVG_MAX_TIME_MS AVG_TIME_MS AVG_MIN_TIME_MS"
PHERF_COLUMNS="$PHERF_COLUMNS RUN_COUNT RESULT_ROW_COUNT"

## @description  Parses the test arguments into the PHERF_* variables, resolving file paths as
##               absolute paths.
## @return       Returns 0, or exits 2 on an unknown option, a missing value, or no scenario.
pherf_args() {
  PHERF_SCENARIO=""
  PHERF_SCHEMAS=()
  PHERF_PROPERTIES=""
  PHERF_SCENARIO_NAME=""
  PHERF_OPTIONS=()
  while [ $# -gt 0 ]; do
    case "$1" in
    --scenario | --schema | --properties | --scenario-name)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "$1 needs a value"
      case "$1" in
      --scenario) PHERF_SCENARIO="$(dv_path "$2")" ;;
      --schema) PHERF_SCHEMAS+=("$(dv_path "$2")") ;;
      --properties) PHERF_PROPERTIES="$(dv_path "$2")" ;;
      *) PHERF_SCENARIO_NAME=$2 ;;
      esac
      shift 2
      ;;
    --)
      shift
      PHERF_OPTIONS=("$@")
      break
      ;;
    *) px_die "$PX_EXIT_USAGE" "unknown argument '$1', see the head of $SELF/driver.sh" ;;
    esac
  done
  [ -n "$PHERF_SCENARIO" ] || px_die "$PX_EXIT_USAGE" "pherf needs --scenario FILE"
  if [ -z "$PHERF_PROPERTIES" ] && [ -n "$PX_TREE_ROOT" ]; then
    PHERF_PROPERTIES="$PX_TREE_ROOT/phoenix-pherf/config/pherf.properties"
  fi
}

# Constructs a Java regular expression anchored to the staged directory so that Pherf matches
# either a specific file under the given resource root or, when no file name is supplied, every
# staged .sql file under that root, without ever matching entries packaged inside a jar.
## @description  Generates a class-path pattern matching staged resource files under a root.
## @return       Writes the pattern to stdout and returns 0.
pherf_pattern() {
  if [ -n "${2:-}" ]; then
    printf '.*\\Q/stage/%s/%s\\E\n' "$1" "$2"
  else
    printf '.*\\Q/stage/%s/\\E.*\\.sql\n' "$1"
  fi
}

## @description  Populates the local staging directory under PX_DRIVER_DIR for the given remote
##               work directory and uploads it to client 0 as stage.
## @return       Returns 0 on success, or the driver exit code of the copy (1, 2, or 3).
pherf_stage() {
  local remote=$1 stage="$PX_DRIVER_DIR/stage" file
  if [ ! -f "$PHERF_PROPERTIES" ]; then
    px_error "no pherf.properties to stage: '$PHERF_PROPERTIES'"
    return "$PX_EXIT_FAIL"
  fi
  rm -rf "$stage"
  mkdir -p "$stage/scenario" "$stage/datamodel" || return "$PX_EXIT_FAIL"
  cp "$PHERF_SCENARIO" "$stage/scenario/" || return "$PX_EXIT_FAIL"
  for file in ${PHERF_SCHEMAS[@]+"${PHERF_SCHEMAS[@]}"}; do
    cp "$file" "$stage/datamodel/" || return "$PX_EXIT_FAIL"
  done
  {
    grep -v -e '^pherf\.default\.results\.dir=' -e '^pherf\.default\.summary\.file=' \
      "$PHERF_PROPERTIES" || true
    printf 'pherf.default.results.dir=%s/results\n' "$remote"
    printf 'pherf.default.summary.file=%s/results/summary.html\n' "$remote"
  } >"$stage/pherf.properties" || return "$PX_EXIT_FAIL"
  dv_put "$stage" stage
}

## @description  Executes Pherf on client 0 using the given URL and Pherf phase options along with
##               the staged scenario and configured test options.
## @return       Returns 0 on success, or the driver exit code of the execution (1, 2, or 3).
pherf_exec() {
  local url=$1 scenario
  shift
  scenario="$(pherf_pattern scenario "${PHERF_SCENARIO##*/}")"
  set -- -z "${url#jdbc:phoenix:}" -scenarioFile "$scenario" "$@"
  if [ -n "$PHERF_SCENARIO_NAME" ]; then
    set -- "$@" -scenarioName "$PHERF_SCENARIO_NAME"
  fi
  # Bound the workload by PX_TIMEOUT_RUN, the timeout bound for prepare and run; px-run stops the
  # verb first when its shared deadline is reached.
  dv_java "${PX_TIMEOUT_RUN:-3600}" --cp stage "$PHERF_CLASS" "$@" \
    ${PHERF_OPTIONS[@]+"${PHERF_OPTIONS[@]}"}
}

# Parses one or more Pherf aggregate CSV files while respecting CSV quoting rules, converts each
# GMT START_TIME timestamp to epoch milliseconds, and emits rows containing the fields
# "start_ms group avg_max avg avg_min run_count result_rows".
## @description  Extracts normalized performance rows from the given Pherf aggregate CSV files.
## @return       Writes the rows to stdout and returns 0, or returns 1 if a header lacks a column.
pherf_csv_rows() {
  awk -v want="$PHERF_COLUMNS" '
    function fields(s, f,   n, i, c, v, inq, len) {
      n = 0; v = ""; inq = 0; len = length(s)
      for (i = 1; i <= len; i++) {
        c = substr(s, i, 1)
        if (inq) {
          if (c != "\"") v = v c
          else if (substr(s, i + 1, 1) == "\"") { v = v "\""; i++ }
          else inq = 0
        } else if (c == "\"") inq = 1
        else if (c == ",") { f[++n] = v; v = "" }
        else v = v c
      }
      f[++n] = v
      return n
    }
    function epoch_ms(t,   y, m, d, era, yoe, mp, doe) {
      if (t !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]/)
        return -1
      y = substr(t, 1, 4) + 0; m = substr(t, 6, 2) + 0; d = substr(t, 9, 2) + 0
      if (m <= 2) y--
      era = int(y / 400); yoe = y - era * 400; mp = (m + 9) % 12
      doe = yoe * 365 + int(yoe / 4) - int(yoe / 100) + int((153 * mp + 2) / 5) + d - 1
      return ((era * 146097 + doe - 719468) * 86400 + substr(t, 12, 2) * 3600 + \
        substr(t, 15, 2) * 60 + substr(t, 18, 2)) * 1000 + substr(t, 21, 3)
    }
    BEGIN { nw = split(want, names, " ") }
    FNR == 1 { header = 0; quotes = 0; rec = "" }
    {
      sub(/\r$/, "")
      rec = (quotes % 2 == 1) ? rec "\n" $0 : $0
      quotes += gsub(/"/, "\"")
      if (quotes % 2 == 1) next
      quotes = 0
      n = fields(rec, f)
      if (!header) {
        for (i = 1; i <= n; i++) col[f[i]] = i
        for (i = 1; i <= nw; i++) if (!(names[i] in col)) {
          printf "%s: the header lacks %s\n", FILENAME, names[i] > "/dev/stderr"
          bad = 1; exit 1
        }
        header = 1
        next
      }
      g = f[col["QUERY_GROUP"]]; gsub(/[\t\n]/, " ", g)
      printf "%.0f\t%s\t%s\t%s\t%s\t%s\t%s\n", epoch_ms(f[col["START_TIME"]]), g,
        f[col["AVG_MAX_TIME_MS"]] + 0, f[col["AVG_TIME_MS"]] + 0, f[col["AVG_MIN_TIME_MS"]] + 0,
        f[col["RUN_COUNT"]] + 0, f[col["RESULT_ROW_COUNT"]] + 0
    }
    END { exit bad }
  ' "$@"
}

# Combines rows emitted by pherf_csv_rows into a single interval per query group bounded by the
# steady-state start and end timestamps, assuming each row spans at most its run count multiplied
# by its average duration.
## @description  Aggregates pherf_csv_rows rows across the steady window into per-group intervals.
## @return       Writes "group anchor start end runs rows avg min max" rows to stdout and exits 0.
pherf_groups() {
  awk -F '\t' -v ws="$1" -v we="$2" '
    {
      g = $2
      if (!(g in runs)) { order[++n] = g; lo[g] = $5; hi[g] = $3; first[g] = -1; last[g] = -1 }
      runs[g] += $6; sum[g] += $4 * $6
      if ($7 < 0) unknown[g] = 1; else rows[g] += $7
      if ($5 < lo[g]) lo[g] = $5
      if ($3 > hi[g]) hi[g] = $3
      if ($1 >= 0 && (first[g] < 0 || $1 < first[g])) first[g] = $1
      if ($1 >= 0 && $1 + $6 * $4 > last[g]) last[g] = $1 + $6 * $4
    }
    END {
      for (i = 1; i <= n; i++) {
        g = order[i]
        s = (first[g] < ws) ? ws : first[g]; if (s > we) s = we
        e = (last[g] < 0 || last[g] > we) ? we : last[g]; if (e < s) e = s
        a = g; gsub(/[^A-Za-z0-9_.-]/, "_", a); if (a == "") a = "_"
        r = (g in unknown) ? -1 : rows[g]
        m = (runs[g] > 0) ? sum[g] / runs[g] : 0
        printf "%s\tgroup@%s\t%.0f\t%.0f\t%d\t%d\t%.3f\t%s\t%s\n", g, a, s, e, runs[g], r, m,
          lo[g], hi[g]
      }
    }'
}

## @description  Emits the driver capability description document for the pherf driver.
## @return       Writes the description document to stdout and returns 0.
verb_describe() {
  dv_describe "$PHERF_NAME" \
    "Runs a Pherf scenario and records one aggregate interval per query group." \
    "setup@load|prepare
steady@pherf|run" \
    "pherf.run_count
pherf.result_rows" \
    "" \
    "pherf.avg_time_ms
pherf.min_time_ms
pherf.max_time_ms
achieved_qps" \
    "query_latency.p50_ms|the aggregate CSV holds averages only
query_latency.p99_ms|the aggregate CSV holds averages only
offered_rate|Pherf has no schedule
query_errors|the aggregate CSV records no failed query
driver.storage_scans|request metrics are off
driver.rpc_calls|request metrics are off" \
    "oplog/|no operation log" \
    "drivers/$PHERF_NAME/results/
drivers/$PHERF_NAME/groups.tsv"
}

# Verifies that the scenario file, every schema file, and the properties file specified by the
# test arguments exist and are readable, and ensures that no two staged file names collide.
## @description  Validates the workload files and arguments for the test definition.
## @return       Returns 0 when the definition is valid, 1 otherwise, or exits 2 on bad arguments.
verb_validate() {
  local file kind problem failed=0 names=" "
  pherf_args "$@"
  for file in "scenario:$PHERF_SCENARIO" "properties:$PHERF_PROPERTIES" \
    ${PHERF_SCHEMAS[@]+"${PHERF_SCHEMAS[@]/#/schema:}"}; do
    kind=${file%%:*}
    file=${file#*:}
    problem=""
    if [ -z "$file" ]; then
      problem="no pherf.properties: give --properties FILE outside a working tree"
    elif [ ! -f "$file" ] || [ ! -r "$file" ]; then
      problem="no readable file"
    fi
    case "$kind:$file" in
    scenario:*.xml)
      if [ -z "$problem" ] && ! grep -q '<scenario' "$file"; then
        problem="the file holds no <scenario> element"
      fi
      ;;
    scenario:*) problem="${problem:+$problem; }a scenario file name ends in .xml" ;;
    schema:*.sql)
      case "$names" in
      *" ${file##*/} "*) problem="${problem:+$problem; }a second schema file of this name" ;;
      esac
      names="$names${file##*/} "
      ;;
    schema:*) problem="${problem:+$problem; }a schema file name ends in .sql" ;;
    esac
    if [ -n "$problem" ]; then
      failed=$((failed + 1))
      sx_verdict "pherf.$kind" FAIL "${file:-pherf.properties}: $problem"
    fi
  done
  if [ "$failed" != 0 ]; then
    sx_verdict driver.workload_valid FAIL "$failed Pherf files are refused"
    return "$PX_EXIT_FAIL"
  fi
  sx_verdict driver.workload_valid PASS "scenario ${PHERF_SCENARIO##*/}," \
    "${#PHERF_SCHEMAS[@]} schema files"
}

## @description  Stages the workload files, applies the schema and loads data within setup@load,
##               and records the tables defined in the schema files.
## @return       Returns 0, or the driver code (1, 2, or 3) on failure; exits 2 on bad arguments.
verb_prepare() {
  local remote url status=0
  pherf_args "$@"
  dv_cluster
  dv_tables_parse ${PHERF_SCHEMAS[@]+"${PHERF_SCHEMAS[@]}"} >"$PX_DRIVER_DIR/tables.list" ||
    return "$PX_EXIT_FAIL"
  remote="$(dv_remote || exit "$?")" || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  pherf_stage "$remote" || return $?
  if [ "${#PHERF_SCHEMAS[@]}" -gt 0 ]; then
    set -- -schemaFile "$(pherf_pattern datamodel)" -l
  else
    set -- -disableSchemaApply -l
  fi
  pb_stage begin setup@load || return
  pherf_exec "$url" "$@" || status=$?
  pb_stage end setup@load || return
  if [ "$status" != 0 ]; then
    sx_verdict pherf.load FAIL "Pherf failed to apply the schema or load the data," \
      "driver code $status"
    return "$status"
  fi
  dv_tables_record "$PX_DRIVER_DIR/tables.list" "${PX_TIMEOUT_DEFAULT:-300}" "$url"
}

## @description  Executes the query sets within the steady@pherf stage and copies the generated
##               results into results/.
## @return       Returns 0, or the driver code (1, 2, or 3) on failure; exits 2 on bad arguments.
verb_run() {
  local remote url status=0 start end code=0
  pherf_args "$@"
  dv_cluster
  remote="$(dv_remote || exit "$?")" || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  pherf_stage "$remote" || return $?
  rm -f "$PX_DRIVER_DIR/run.tsv"
  pb_stage begin steady@pherf || return
  start="$(px_epoch_ms)"
  pherf_exec "$url" -disableSchemaApply -q || status=$?
  end="$(px_epoch_ms)"
  pb_stage end steady@pherf || return
  pb_header "$PX_DRIVER_DIR/run.tsv" "start_ms end_ms status" || return
  px_tsv_row "$start" "$end" "$status" >>"$PX_DRIVER_DIR/run.tsv" || return "$PX_EXIT_FAIL"
  dv_get results "$PX_DRIVER_DIR/results" || code=$?
  if [ "$status" != 0 ]; then
    sx_verdict pherf.execution FAIL "Pherf failed to run the query sets, driver code $status"
    return "$status"
  fi
  return "$code"
}

## @description  Drops the tables from the schema files and removes the remote work directory;
##               repeated invocations find nothing to do.
## @return       Returns 0, or the driver code (1, 2, or 3) on failure; exits 2 on bad arguments.
verb_restore() {
  pherf_args "$@"
  dv_cluster
  dv_tables_restore
}

## @description  Converts the aggregate CSV into groups.tsv, metric records, and pherf.results.
## @return       Returns 0 when the report is written or run left nothing, 1 when it cannot be
##               written, or exits 2 on bad arguments.
verb_report() {
  local start end file rows groups group anchor gs ge runs result avg min max
  local total=0 count=0 table="$PX_DRIVER_DIR/groups.tsv"
  local csv=()
  pherf_args "$@"
  dv_window pherf.results steady@pherf "no query set ran" || return 0
  start=$DV_START end=$DV_END
  while IFS= read -r file; do
    [ -z "$file" ] || csv+=("$file")
  done <<EOF
$(find "$PX_DRIVER_DIR/results" -type f -name 'RESULT_*_aggregate.csv' 2>/dev/null | LC_ALL=C sort)
EOF
  if [ "${#csv[@]}" = 0 ]; then
    sx_verdict pherf.results FAIL "run left no RESULT_*_aggregate.csv in $PX_DRIVER_DIR/results"
    return 0
  fi
  if ! rows="$(pherf_csv_rows "${csv[@]}" || exit "$?")"; then
    sx_verdict pherf.results FAIL "an aggregate CSV does not carry the AGGREGATE_PERFORMANCE" \
      "header of Header.java"
    return 0
  fi
  groups="$(printf '%s\n' "$rows" | awk 'NF' | pherf_groups "$start" "$end" || exit "$?")" ||
    return "$PX_EXIT_FAIL"
  rm -f "$table"
  pb_header "$table" \
    "group anchor start_ms end_ms run_count result_rows avg_time_ms min_time_ms max_time_ms" ||
    return
  while IFS="$PX_TAB" read -r group anchor gs ge runs result avg min max; do
    [ -n "$anchor" ] || continue
    px_tsv_row "$group" "$anchor" "$gs" "$ge" "$runs" "$result" "$avg" "$min" "$max" \
      >>"$table" || return "$PX_EXIT_FAIL"
    pb_metric pherf.run_count count "$runs" runs "$anchor" "$gs" "$ge" || return
    if [ "$result" -ge 0 ]; then
      pb_metric pherf.result_rows count "$result" rows "$anchor" "$gs" "$ge" || return
    fi
    pb_metric pherf.avg_time_ms duration "$avg" ms "$anchor" "$gs" "$ge" || return
    pb_metric pherf.min_time_ms duration "$min" ms "$anchor" "$gs" "$ge" || return
    pb_metric pherf.max_time_ms duration "$max" ms "$anchor" "$gs" "$ge" || return
    total=$((total + runs))
    count=$((count + 1))
  done <<EOF
$groups
EOF
  pb_metric pherf.run_count count "$total" runs steady@pherf "$start" "$end" || return
  if [ "$end" -gt "$start" ]; then
    pb_metric achieved_qps duration \
      "$(awk -v n="$total" -v ms="$((end - start))" 'BEGIN { printf "%.3f", n * 1000 / ms }')" \
      ops/s steady@pherf "$start" "$end" || return
  fi
  if [ "$count" = 0 ]; then
    sx_verdict pherf.results FAIL "the aggregate CSV holds no query group"
  else
    sx_verdict pherf.results PASS "$count query groups, $total query runs"
  fi
}

dv_main "$@"
