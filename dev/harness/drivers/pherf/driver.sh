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

# The pherf driver executes a Pherf scenario through the Pherf main class on client 0, records the
# Pherf latency report, appends the Pherf health verdict to the verdict stream, and converts the
# Pherf aggregate CSV output into one measurement interval per query group. The authoritative
# specification is in docs/driver-contract.md under the "pherf" section.
#
# A test invocation accepts the following arguments:
#
#   --scenario FILE [--schema FILE]... [--properties FILE]
#   [--scenario-name NAME] [--read-skew] [-- PHERF_OPTION...]
#
# Until round P1 updates the resource loader, Pherf discovers its scenario and schema files by
# matching a regular expression pattern against the class path. The driver therefore stages those
# files into a directory placed first on the class path under scenario/<name> and datamodel/<name>,
# which serve as the resource roots of Pherf. The generated patterns match only the staged
# directory so that files packaged inside a Pherf jar are never matched. Because Pherf resolves the
# profile="PATH" attribute of a querySet relative to the scenario file, the driver stages every
# profile referenced by the scenario at that relative path under scenario/.
#
# Because the Pherf result directory defaults to the relative path RESULTS, the staged directory
# also includes a pherf.properties file that Pherf loads from the class path, configured to place
# both the result directory and the summary file inside the remote work directory. All remaining
# property keys come from --properties, or else from phoenix-pherf/config/pherf.properties in the
# working tree, ensuring that nothing is written into the working tree.
#
# The driver configures two Pherf verdict properties: pherf.verdict.noisyHost is set to true on a
# contaminated host, while pherf.verdict.serverJmx is populated only when --read-skew is specified,
# listing the info server endpoints of the RegionServers.
#
# The prepare verb applies the schema and loads the data (-l) within the setup@load stage. The run
# verb executes the query sets (-q) within the steady@pherf stage and copies both the result
# directory and its sibling <results>_HDR directory into $PX_DRIVER_DIR. Each verb uses a fixed
# Pherf label, load or run, so that the latency files produced by the two verbs have distinct
# names. The report verb reads exclusively from those local copies.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/driver.sh
. "$SELF/../lib/driver.sh"

set -euo pipefail
px_trap_enable

PHERF_NAME=pherf
PHERF_CLASS=org.apache.phoenix.pherf.Pherf
PHERF_SCENARIO=""
PHERF_SCHEMAS=()
PHERF_PROFILES=()
PHERF_PROPERTIES=""
PHERF_SCENARIO_NAME=""
PHERF_READ_SKEW=0
PHERF_OPTIONS=()

# Fixed Pherf labels assigned to each execution verb. The latency files and the verdict file for a
# label are written to <results>_HDR with the label embedded in their file names.
PHERF_LABEL_LOAD=load
PHERF_LABEL_RUN=run

# Expected header line at the start of a Pherf histogram log, as specified in
# phoenix-pherf/LATENCY.md. It records the histogram recorder configuration, matching the metadata
# reported in the JSON latency report.
PHERF_HLOG_HEADER="#[Pherf latency log 1: microseconds, lowest 1, highest 600000000,"
PHERF_HLOG_HEADER="$PHERF_HLOG_HEADER 3 significant digits, Tag=OPERATION|PHASE]"

# Total operation count across steady-state keys from the JSON latency report, and total run count
# from the aggregate CSV. Each variable remains empty when the report verb has not read its source
# file.
PHERF_JSON_OPS=""
PHERF_CSV_RUNS=""

# Shell script evaluated inside the environment of client 0 to construct the info server URL of
# each RegionServer as http://regionserver-<i>:<port> for each index i below PX_RS_COUNT. The port
# is resolved from PX_PORT_REGIONSERVER_HTTP_<i>, falling back to PX_PORT_REGIONSERVER_HTTP as in
# px-trace-counters. If the RegionServer count or any port is missing, the script produces no
# output.
PHERF_JMX_SCRIPT="$(
  cat <<'SCRIPT_EOF'
count=${PX_RS_COUNT:-0}
[ "$count" -ge 1 ] 2>/dev/null || exit 0
urls=""
i=0
while [ "$i" -lt "$count" ]; do
  name="PX_PORT_REGIONSERVER_HTTP_$i"
  port=${!name:-${PX_PORT_REGIONSERVER_HTTP:-}}
  [ -n "$port" ] || exit 0
  urls="$urls${urls:+,}http://regionserver-$i:$port"
  i=$((i + 1))
done
printf '%s\n' "$urls"
SCRIPT_EOF
)"

# Column names from the AGGREGATE_PERFORMANCE header defined in
# phoenix-pherf/src/main/java/org/apache/phoenix/pherf/result/file/Header.java that the report verb
# reads and processes.
PHERF_COLUMNS="START_TIME QUERY_GROUP AVG_MAX_TIME_MS AVG_TIME_MS AVG_MIN_TIME_MS"
PHERF_COLUMNS="$PHERF_COLUMNS RUN_COUNT RESULT_ROW_COUNT"

# Parses the test arguments and scenario profile paths into the PHERF_* variables, rejecting any
# Pherf -label option (which the driver owns) and any profile outside the scenario directory.
## @description  Parses the test arguments and scenario profiles into the PHERF_* variables.
## @return       Returns 0, or exits 2 on an unknown option, a missing value, no scenario, a Pherf
##               -label option, or a profile outside the scenario directory.
pherf_args() {
  local option profile
  PHERF_SCENARIO=""
  PHERF_SCHEMAS=()
  PHERF_PROFILES=()
  PHERF_PROPERTIES=""
  PHERF_SCENARIO_NAME=""
  PHERF_READ_SKEW=0
  PHERF_OPTIONS=()
  while [ $# -gt 0 ]; do
    case "$1" in
    --read-skew)
      PHERF_READ_SKEW=1
      shift
      ;;
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
  for option in ${PHERF_OPTIONS[@]+"${PHERF_OPTIONS[@]}"}; do
    case "$option" in
    -label | --label | -label=* | --label=*)
      px_die "$PX_EXIT_USAGE" "the driver sets the Pherf -label of each verb, remove '$option'"
      ;;
    esac
  done
  if [ -z "$PHERF_PROPERTIES" ] && [ -n "$PX_TREE_ROOT" ]; then
    PHERF_PROPERTIES="$PX_TREE_ROOT/phoenix-pherf/config/pherf.properties"
  fi
  [ -f "$PHERF_SCENARIO" ] && [ -r "$PHERF_SCENARIO" ] || return 0
  # Because the staging layout contains only the scenario directory, every profile path must be a
  # relative path beneath that directory.
  while IFS= read -r profile; do
    case "/$profile/" in
    //) continue ;;
    //*) px_die "$PX_EXIT_USAGE" "the querySet profile '$profile' of ${PHERF_SCENARIO##*/} is" \
      "an absolute path: name it relative to the scenario file" ;;
    */../*) px_die "$PX_EXIT_USAGE" "the querySet profile '$profile' of" \
      "${PHERF_SCENARIO##*/} leaves the directory of the scenario file" ;;
    esac
    PHERF_PROFILES+=("$profile")
  done <<EOF
$(pherf_profile_names "$PHERF_SCENARIO")
EOF
}

# Extracts the profile attribute from each querySet element in a scenario file (file), emitting
# each unique name once with whitespace trimmed in the same manner as Pherf while ignoring any
# querySet inside an XML comment.
## @description  Extracts each unique, trimmed querySet profile name from a scenario file (file).
## @return       Writes one profile name per line to stdout and exits 0.
pherf_profile_names() {
  awk -v q="'" '
    { text = text $0 " " }
    END {
      while ((i = index(text, "<!--")) > 0) {
        rest = substr(text, i + 4)
        j = index(rest, "-->")
        text = substr(text, 1, i - 1) (j ? substr(rest, j + 3) : "")
      }
      attr = "[[:space:]]profile[[:space:]]*=[[:space:]]*(\"[^\"]*\"|" q "[^" q "]*" q ")"
      while (match(text, /<querySet[[:space:]][^>]*>/)) {
        tag = substr(text, RSTART, RLENGTH)
        text = substr(text, RSTART + RLENGTH)
        if (!match(tag, attr)) continue
        v = substr(tag, RSTART, RLENGTH)
        sub(/^[^=]*=[[:space:]]*/, "", v)
        v = substr(v, 2, length(v) - 2)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
        if (!(v in seen)) {
          seen[v] = 1
          print v
        }
      }
    }' "$1"
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

# Queries the environment of client 0 for the RegionServer info server endpoints and formats them
# as a single comma-separated list.
## @description  Reports the RegionServer info server URLs from client 0 as a comma-separated list.
## @return       Writes the list to stdout and returns 0, or the exec driver code (1, 2, or 3);
##               also returns 3 when client 0 names no RegionServer.
pherf_server_jmx() {
  local urls status=0
  urls="$(sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- \
    bash -c "$PHERF_JMX_SCRIPT" </dev/null || exit "$?")" || status=$?
  if [ "$status" != 0 ]; then
    px_error "cannot read the RegionServer info servers in client 0 (status $status)"
    return "$(dv_code "$status")"
  fi
  urls="$(printf '%s\n' "$urls" | sed -n '1p')"
  if [ -z "$urls" ]; then
    px_error "client 0 names no RegionServer info server: PX_RS_COUNT or a" \
      "PX_PORT_REGIONSERVER_HTTP port is missing"
    return "$PX_EXIT_ANOMALY"
  fi
  printf '%s\n' "$urls"
}

## @description  Populates the staging directory under PX_DRIVER_DIR for the given remote work
##               directory and copies it to client 0 as stage.
## @return       Returns 0, or the driver code from the copy or pherf_server_jmx (1, 2, or 3).
pherf_stage() {
  local remote=$1 stage="$PX_DRIVER_DIR/stage" file noisy=false jmx=""
  if [ ! -f "$PHERF_PROPERTIES" ]; then
    px_error "no pherf.properties to stage: '$PHERF_PROPERTIES'"
    return "$PX_EXIT_FAIL"
  fi
  # On a contaminated host, Pherf demotes a failed duration check to ADVISORY, matching the
  # behavior of px-report. Because the read-skew check queries the RegionServers and no host
  # condition demotes a share check, the RegionServers are resolved only when --read-skew is set.
  [ -z "${PX_CONTAMINATION:-}" ] || noisy=true
  if [ "$PHERF_READ_SKEW" = 1 ]; then
    jmx="$(pherf_server_jmx || exit "$?")" || return $?
  fi
  rm -rf "$stage"
  mkdir -p "$stage/scenario" "$stage/datamodel" || return "$PX_EXIT_FAIL"
  cp "$PHERF_SCENARIO" "$stage/scenario/" || return "$PX_EXIT_FAIL"
  for file in ${PHERF_SCHEMAS[@]+"${PHERF_SCHEMAS[@]}"}; do
    cp "$file" "$stage/datamodel/" || return "$PX_EXIT_FAIL"
  done
  for file in ${PHERF_PROFILES[@]+"${PHERF_PROFILES[@]}"}; do
    mkdir -p "$(dirname "$stage/scenario/$file")" || return "$PX_EXIT_FAIL"
    cp "${PHERF_SCENARIO%/*}/$file" "$stage/scenario/$file" || return "$PX_EXIT_FAIL"
  done
  # Because the last definition of a property key determines its effective value, the keys managed
  # by the driver are appended last. An empty serverJmx value preserves the Pherf default of having
  # no JMX source.
  {
    grep -v -E -e '^[[:space:]]*pherf\.default\.(results\.dir|summary\.file)([[:space:]:=]|$)' \
      -e '^[[:space:]]*pherf\.verdict\.(noisyHost|serverJmx)([[:space:]:=]|$)' \
      "$PHERF_PROPERTIES" || true
    printf 'pherf.default.results.dir=%s/results\n' "$remote"
    printf 'pherf.default.summary.file=%s/results/summary.html\n' "$remote"
    printf 'pherf.verdict.noisyHost=%s\n' "$noisy"
    printf 'pherf.verdict.serverJmx=%s\n' "$jmx"
  } >"$stage/pherf.properties" || return "$PX_EXIT_FAIL"
  dv_put "$stage" stage
}

# Executes Pherf on client 0 using the given URL, label, and Pherf phase options alongside the
# staged scenario and test options, stripping run-override flags when the label is load.
## @description  Executes Pherf on client 0 with the given URL, label, and Pherf phase options.
## @return       Returns 0, or the driver exit code of the execution (1, 2, or 3).
pherf_exec() {
  local url=$1 label=$2 scenario option drop=0
  local options=()
  shift 2
  # Pherf rejects -runDurationMs, -threadCount, and -targetRate when invoked without -q, so these
  # options and their values are omitted during the load invocation.
  for option in ${PHERF_OPTIONS[@]+"${PHERF_OPTIONS[@]}"}; do
    if [ "$drop" = 1 ]; then
      drop=0
      continue
    fi
    if [ "$label" = "$PHERF_LABEL_LOAD" ]; then
      case "$option" in
      -runDurationMs | --runDurationMs | -threadCount | --threadCount | -targetRate | \
        --targetRate)
        drop=1
        continue
        ;;
      # Options passed in the "=" form carry their value within the same argument token.
      -runDurationMs=* | --runDurationMs=* | -threadCount=* | --threadCount=* | \
        -targetRate=* | --targetRate=*)
        continue
        ;;
      esac
    fi
    options+=("$option")
  done
  scenario="$(pherf_pattern scenario "${PHERF_SCENARIO##*/}")"
  set -- -z "${url#jdbc:phoenix:}" -scenarioFile "$scenario" "$@" -label "$label"
  if [ -n "$PHERF_SCENARIO_NAME" ]; then
    set -- "$@" -scenarioName "$PHERF_SCENARIO_NAME"
  fi
  # Bound the workload by PX_TIMEOUT_RUN, the timeout bound for prepare and run; px-run stops the
  # verb first when its shared deadline is reached.
  dv_java "${PX_TIMEOUT_RUN:-3600}" --cp stage "$PHERF_CLASS" "$@" \
    ${options[@]+"${options[@]}"}
}

# Records a FAIL verdict for a failed Pherf execution from the given verb, driver code, check name,
# and detail text. When DV_STATUS is 65, Pherf's pre-run gate refused the scenario files and the
# failure is reported under the pherf.gate check instead.
## @description  Emits the FAIL verdict row for a failed Pherf execution.
## @return       Returns 0, or the exit status of sx_verdict.
pherf_failed() {
  local verb=$1 status=$2 check=$3
  shift 3
  if [ "$DV_STATUS" = 65 ]; then
    sx_verdict pherf.gate FAIL "the Pherf pre-run gate refused the scenario files (exit 65)," \
      "the findings are in logs/$verb.log"
  else
    sx_verdict "$check" FAIL "$@" "driver code $status"
  fi
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

# Parses and validates a version 1 Pherf JSON latency report file via pb_json_flat, emitting window
# rows ("w start end hlog"), histogram rows ("h operation phase count min max mean p50 p90 p99
# p999"), and schedule rows ("s ...").
## @description  Extracts validated rows from a version 1 Pherf JSON latency report file.
## @return       Writes rows to stdout and returns 0, or returns 1 with one "x reason" row on a
##               refused report.
pherf_json_rows() {
  local rows
  if ! rows="$(pb_json_flat "$1" || exit "$?")"; then
    printf 'x\tthe file is not one JSON document, see the log of report\n'
    return "$PX_EXIT_FAIL"
  fi
  # Each extracted field is preserved as its raw JSON token so that microsecond measurements retain
  # full integer precision. Schedule rows follow the format "s scenario querySet start end
  # scheduled completed late dropped aborted maxQueueDepth maxDriftUs".
  printf '%s\n' "$rows" | awk -F '\t' '
    { type[$1] = $2; value[$1] = $3 }
    function refuse(why) { printf "x\t%s\n", why; exit 1 }
    function need(path, kind, pattern) {
      if (type[path] != kind || value[path] !~ pattern)
        refuse("the report has no valid " path)
      return value[path]
    }
    END {
      whole = "^[0-9]+$"
      num = "^-?[0-9]"
      if (type["/format"] != "string" || value["/format"] != "pherf-latency")
        refuse("the format is not pherf-latency")
      if (type["/version"] != "number" || value["/version"] != "1")
        refuse("the version is " value["/version"] ", not 1")
      if (value["/recorder/lowestUs"] != "1" || value["/recorder/highestUs"] != "600000000" ||
        value["/recorder/significantDigits"] != "3")
        refuse("the recorder is not 1 us to 600000000 us with 3 significant digits")
      line = "w\t" need("/startMs", "number", whole) "\t" need("/endMs", "number", whole)
      hlog = need("/hlog", "string", "^[^/]+$")
      if (hlog == "." || hlog == "..") refuse("the report names no valid hlog")
      out[++n] = line "\t" hlog
      need("/histograms", "array", whole)
      split("count minUs maxUs meanUs p50Us p90Us p99Us p999Us", hf, " ")
      for (i = 0; i < value["/histograms"]; i++) {
        p = "/histograms/" i
        line = "h\t" need(p "/operation", "string", "") "\t" need(p "/phase", "string", "")
        for (k = 1; k <= 8; k++)
          line = line "\t" need(p "/" hf[k], "number", (hf[k] == "meanUs") ? num : whole)
        out[++n] = line
      }
      if (("/schedules" in type) && type["/schedules"] != "array")
        refuse("the report has no valid /schedules")
      split("startMs endMs scheduled completed late dropped aborted maxQueueDepth maxDriftUs",
        sf, " ")
      for (i = 0; i < value["/schedules"] + 0; i++) {
        p = "/schedules/" i
        line = "s\t" need(p "/scenario", "string", "") "\t" need(p "/querySet", "number", whole)
        for (k = 1; k <= 9; k++) line = line "\t" need(p "/" sf[k], "number", whole)
        out[++n] = line
      }
      for (i = 1; i <= n; i++) print out[i]
    }'
}

# Transforms the output of pherf_json_rows for the given steady-state start and end timestamps into
# metric rows ("m anchor key class value unit start end") and a trailing summary row ("t keys
# operations p99 operation").
## @description  Converts pherf_json_rows output across the steady window into metric and summary
##               rows.
## @return       Writes the rows to stdout and exits 0.
pherf_latency_metrics() {
  awk -F '\t' -v ws="$1" -v we="$2" '
    function anchor(kind, name) {
      gsub(/[^A-Za-z0-9_.-]/, "_", name)
      if (name == "") name = "_"
      return kind "@" name
    }
    # Clamp each measurement interval to the steady-state window, matching pherf_groups.
    function window(s, e) {
      cs = (s < ws) ? ws : s
      if (cs > we) cs = we
      ce = (e > we) ? we : e
      if (ce < cs) ce = cs
    }
    function put(a, key, class, v, unit) {
      printf "m\t%s\t%s\t%s\t%s\t%s\t%.0f\t%.0f\n", a, key, class, v, unit, cs, ce
    }
    $1 == "w" { rs = $2; re = $3 }
    # Only steady-phase histograms represent measured work; warmup keys produce no metric records.
    $1 == "h" && $3 == "steady" {
      kind = $2
      sub(/:.*/, "", kind)
      window(rs, re)
      a = anchor("op", $2)
      put(a, "pherf.operations", "count", $4, "ops")
      split("min_us max_us mean_us p50_us p90_us p99_us p999_us", f, " ")
      for (k = 1; k <= 7; k++) put(a, "pherf.latency." f[k], "duration", $(k + 4), "us")
      # Response histograms duplicate the operation count of their corresponding query key, and
      # load histograms count data-load batches; neither contributes to the total operation count.
      if (kind ~ /^(query|scan|delete|operation)$/) ops += $4
      # Aggregate latency percentiles are derived from the operation kinds graded by the Pherf tail
      # check.
      if (kind ~ /^(query|scan|delete|response|operation)$/) {
        if (!keys || $8 + 0 > p50) p50 = $8 + 0
        if (!keys || $10 + 0 > p99) { p99 = $10 + 0; at = $2 }
        keys++
      }
    }
    $1 == "s" {
      window($4, $5)
      a = anchor("schedule", $2 "." $3)
      split("scheduled completed late dropped aborted max_queue_depth", f, " ")
      for (k = 1; k <= 6; k++) {
        put(a, "pherf.schedule." f[k], "count", $(k + 5), "ops")
        if (k <= 5) sum[k] += $(k + 5)
      }
      put(a, "pherf.schedule.max_drift_us", "duration", $12, "us")
      if (!schedules || $11 + 0 > depth) depth = $11 + 0
      if (!schedules || $12 + 0 > drift) drift = $12 + 0
      schedules++
    }
    END {
      cs = ws
      ce = we
      split("scheduled completed late dropped aborted", f, " ")
      put("steady@pherf", "pherf.operations", "count", sprintf("%.0f", ops), "ops")
      if (schedules) {
        for (k = 1; k <= 5; k++)
          put("steady@pherf", "pherf.schedule." f[k], "count", sprintf("%.0f", sum[k]), "ops")
        put("steady@pherf", "pherf.schedule.max_queue_depth", "count", depth, "ops")
        put("steady@pherf", "pherf.schedule.max_drift_us", "duration", drift, "us")
      }
      if (keys) {
        put("steady@pherf", "query_latency.p50_ms", "duration", sprintf("%.3f", p50 / 1000), "ms")
        put("steady@pherf", "query_latency.p99_ms", "duration", sprintf("%.3f", p99 / 1000), "ms")
      }
      printf "t\t%d\t%.0f\t%s\t%s\n", keys, ops, (keys ? p99 : ""), at
    }'
}

## @description  Emits the capability description document for the pherf driver.
## @return       Writes the document to stdout and exits 0.
verb_describe() {
  local hdr="drivers/$PHERF_NAME/results_HDR"
  dv_describe "$PHERF_NAME" \
    "Runs a Pherf scenario and records its latency report, its health verdict and one aggregate\
 interval per query group." \
    "setup@load|prepare
steady@pherf|run" \
    "pherf.run_count
pherf.result_rows
pherf.operations
pherf.schedule.scheduled
pherf.schedule.completed
pherf.schedule.late
pherf.schedule.dropped
pherf.schedule.aborted
pherf.schedule.max_queue_depth" \
    "" \
    "pherf.avg_time_ms
pherf.min_time_ms
pherf.max_time_ms
achieved_qps
query_latency.p50_ms
query_latency.p99_ms
pherf.latency.min_us
pherf.latency.max_us
pherf.latency.mean_us
pherf.latency.p50_us
pherf.latency.p90_us
pherf.latency.p99_us
pherf.latency.p999_us
pherf.schedule.max_drift_us" \
    "offered_rate|Pherf grades its open-loop attainment in pherf.health.attainment
query_errors|the report counts no failed closed-loop operation
driver.storage_scans|request metrics are off
driver.rpc_calls|request metrics are off" \
    "oplog/|no operation log" \
    "drivers/$PHERF_NAME/results/
drivers/$PHERF_NAME/groups.tsv
$hdr/
$hdr/LATENCY_$PHERF_LABEL_RUN.json
$hdr/LATENCY_$PHERF_LABEL_RUN.hlog
$hdr/LATENCY_${PHERF_LABEL_RUN}_intervals.hlog
$hdr/VERDICT_$PHERF_LABEL_RUN.tsv"
}

# Verifies that the workload files specified by the test arguments (argument...) and all profiles
# referenced by the scenario exist and are readable, and ensures that no two staged schema file
# names collide.
## @description  Validates that all test files (argument...) and scenario profiles are readable and
##               that no two staged schema names collide.
## @return       Returns 0 when the definition is valid, 1 otherwise, or exits 2 on bad arguments.
verb_validate() {
  local file kind problem failed=0 names=" " profiles=()
  pherf_args "$@"
  for file in ${PHERF_PROFILES[@]+"${PHERF_PROFILES[@]}"}; do
    profiles+=("profile:${PHERF_SCENARIO%/*}/$file")
  done
  for file in "scenario:$PHERF_SCENARIO" "properties:$PHERF_PROPERTIES" \
    ${PHERF_SCHEMAS[@]+"${PHERF_SCHEMAS[@]/#/schema:}"} ${profiles[@]+"${profiles[@]}"}; do
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
    "${#PHERF_SCHEMAS[@]} schema files, ${#PHERF_PROFILES[@]} profile files"
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
  pherf_exec "$url" "$PHERF_LABEL_LOAD" "$@" || status=$?
  pb_stage end setup@load || return
  if [ "$status" != 0 ]; then
    pherf_failed prepare "$status" pherf.load "Pherf failed to apply the schema or load the data,"
    return "$status"
  fi
  dv_tables_record "$PX_DRIVER_DIR/tables.list" "${PX_TIMEOUT_DEFAULT:-300}" "$url"
}

## @description  Executes the query sets within steady@pherf, then copies the results into results/
##               and the latency and verdict files into results_HDR/.
## @return       Returns 0, or the driver code (1, 2, or 3) on failure; exits 2 on bad arguments.
verb_run() {
  local remote url status=0 start end code=0 hdr=0
  pherf_args "$@"
  dv_cluster
  remote="$(dv_remote || exit "$?")" || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  pherf_stage "$remote" || return $?
  # A Pherf build prior to round P4 does not create results_HDR. The driver pre-creates the remote
  # directory so that copying results from an older Pherf build yields an empty directory rather
  # than failing.
  sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- mkdir -p "$remote/results_HDR" \
    >&2 || hdr=$?
  if [ "$hdr" != 0 ]; then
    px_error "cannot create $remote/results_HDR in client 0 (status $hdr)"
    return "$(dv_code "$hdr")"
  fi
  rm -f "$PX_DRIVER_DIR/run.tsv"
  pb_stage begin steady@pherf || return
  start="$(px_epoch_ms)"
  pherf_exec "$url" "$PHERF_LABEL_RUN" -disableSchemaApply -q || status=$?
  end="$(px_epoch_ms)"
  pb_stage end steady@pherf || return
  pb_header "$PX_DRIVER_DIR/run.tsv" "start_ms end_ms status" || return
  px_tsv_row "$start" "$end" "$status" >>"$PX_DRIVER_DIR/run.tsv" || return "$PX_EXIT_FAIL"
  dv_get results "$PX_DRIVER_DIR/results" || code=$?
  dv_get results_HDR "$PX_DRIVER_DIR/results_HDR" || hdr=$?
  [ "$code" != 0 ] || code=$hdr
  if [ "$status" != 0 ]; then
    pherf_failed run "$status" pherf.execution "Pherf failed to run the query sets,"
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

# Validates that the Pherf verdict file from the run contains five pherf.health rows with a known
# severity, a known class, and source pherf, and appends those rows unchanged to verdicts.tsv.
## @description  Validates and appends the Pherf health verdict rows from the run to verdicts.tsv.
## @return       Returns 0, or 1 or 2 when the verdict stream cannot be written.
pherf_verdict_copy() {
  local file="$PX_DRIVER_DIR/results_HDR/VERDICT_$PHERF_LABEL_RUN.tsv" bad stream
  if [ ! -f "$file" ]; then
    sx_verdict pherf.verdict SKIP "Pherf wrote no verdict file, a Pherf before P12"
    return 0
  fi
  # Because px-report escalates a malformed verdict row to ERROR and px-bundle validate rejects the
  # bundle, a verdict file containing any invalid row is not appended at all; instead, the driver
  # records a FAIL verdict attributing the fault to Pherf.
  bad="$(awk -F '\t' -v severities="$SX_SEVERITIES" -v classes="$SX_CLASSES" '
    BEGIN {
      n = split(severities, s, " "); for (i = 1; i <= n; i++) sev[s[i]] = 1
      n = split(classes, c, " "); for (i = 1; i <= n; i++) cls[c[i]] = 1
    }
    {
      why = ""
      if (NF != 5) why = NF " fields, not 5"
      else if ($1 !~ /^pherf\.health\.[a-z_]+$/) why = "check \"" $1 "\""
      else if (!($2 in sev)) why = "severity \"" $2 "\""
      else if (!($3 in cls)) why = "class \"" $3 "\""
      else if ($4 != "pherf") why = "source \"" $4 "\""
      if (why != "") { first = "line " NR ": " why; exit }
    }
    END {
      if (first != "") print first
      else if (NR != 5) print NR " rows, not 5"
    }' "$file")"
  if [ -n "$bad" ]; then
    sx_verdict pherf.verdict FAIL "${file##*/}: $bad"
    return 0
  fi
  stream="$(sx_verdict_file)" || return $?
  # Copy each row verbatim through awk so that a trailing newline (LF) is guaranteed even if the
  # last row of the input file lacks one.
  awk '{ print }' "$file" >>"$stream" || return "$PX_EXIT_FAIL"
  sx_verdict pherf.verdict PASS "appended the $(awk 'END { print NR }' "$file") rows of" \
    "${file##*/}, with the source pherf"
}

# Validates the JSON latency report and companion histogram log for the run across the given
# steady-state start and end window, records the derived metrics, emits the pherf.latency verdict,
# and populates PHERF_JSON_OPS when the report is valid.
## @description  Records metrics from the run latency report and emits the pherf.latency verdict.
## @return       Returns 0, or 1 when a record cannot be written.
pherf_latency() {
  local dir="$PX_DRIVER_DIR/results_HDR" json rows metrics hlog first
  local kind anchor key class value unit s e keys ops p99 at
  PHERF_JSON_OPS=""
  json="$dir/LATENCY_$PHERF_LABEL_RUN.json"
  if [ ! -f "$json" ]; then
    sx_verdict pherf.latency SKIP "run left no ${json##*/}, a Pherf before P4"
    return 0
  fi
  if ! rows="$(pherf_json_rows "$json" || exit "$?")"; then
    sx_verdict pherf.latency FAIL "${json##*/}: ${rows##*"$PX_TAB"}"
    return 0
  fi
  # The driver reads summary values directly from the JSON latency report rather than decoding the
  # raw histogram log. Because the same histogram recorders write both files, the run histogram log
  # is verified to carry the expected recorder configuration header matching the report.
  hlog="$(printf '%s\n' "$rows" | awk -F '\t' '$1 == "w" { print $4 }')"
  first=""
  [ ! -f "$dir/$hlog" ] || IFS= read -r first <"$dir/$hlog" || true
  if [ "$first" != "$PHERF_HLOG_HEADER" ]; then
    sx_verdict pherf.latency FAIL "${json##*/} names the run log $hlog, which is absent or" \
      "does not start with the header of LATENCY.md"
    return 0
  fi
  metrics="$(printf '%s\n' "$rows" | pherf_latency_metrics "$1" "$2")" || return "$PX_EXIT_FAIL"
  while IFS="$PX_TAB" read -r kind anchor key class value unit s e; do
    [ "$kind" != m ] || pb_metric "$key" "$class" "$value" "$unit" "$anchor" "$s" "$e" || return
  done <<EOF
$metrics
EOF
  # Extract the summary row containing the steady key count, total operation count, worst p99
  # latency, and the key at which that p99 occurred.
  IFS="$PX_TAB" read -r kind keys ops p99 at <<EOF
$(printf '%s\n' "$metrics" | awk -F '\t' '$1 == "t"')
EOF
  PHERF_JSON_OPS=$ops
  if [ "$keys" = 0 ]; then
    sx_verdict pherf.latency PASS "${json##*/} holds no steady key"
  else
    sx_verdict pherf.latency PASS "$keys steady keys, $ops operations, worst p99 $p99 us at $at"
  fi
}

# Processes Pherf aggregate CSV files for the given steady-state start and end timestamps (and a
# flag fixed to "yes" when the JSON latency report is valid), writing groups.tsv, metric records,
# and the pherf.results verdict while setting PHERF_CSV_RUNS.
## @description  Converts the aggregate CSV output into groups.tsv, metric records, and
##               pherf.results.
## @return       Returns 0, or 1 when a record cannot be written.
pherf_csv() {
  local start=$1 end=$2 json=$3 file rows groups group anchor gs ge runs result avg min max
  local total=0 count=0 table="$PX_DRIVER_DIR/groups.tsv"
  local csv=()
  PHERF_CSV_RUNS=""
  while IFS= read -r file; do
    [ -z "$file" ] || csv+=("$file")
  done <<EOF
$(find "$PX_DRIVER_DIR/results" -type f -name 'RESULT_*_aggregate.csv' 2>/dev/null | LC_ALL=C sort)
EOF
  if [ "${#csv[@]}" = 0 ] && [ "$json" = yes ]; then
    sx_verdict pherf.results SKIP "run left no RESULT_*_aggregate.csv, and the latency report" \
      "holds the operations"
    return 0
  fi
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
  PHERF_CSV_RUNS=$total
  if [ "$count" = 0 ]; then
    sx_verdict pherf.results FAIL "the aggregate CSV holds no query group"
  else
    sx_verdict pherf.results PASS "$count query groups, $total query runs"
  fi
}

## @description  Appends the Pherf verdict rows, then records the latency report and aggregate CSV.
## @return       Returns 0 when the report is written or run left nothing, 1 or 2 when it cannot be
##               written, or exits 2 on bad arguments.
verb_report() {
  local start end total json=no
  pherf_args "$@"
  dv_window pherf.results steady@pherf "no query set ran" || return 0
  start=$DV_START end=$DV_END
  # Pherf emits its health verdict file even when the query run itself fails.
  pherf_verdict_copy || return
  pherf_latency "$start" "$end" || return
  [ -z "$PHERF_JSON_OPS" ] || json=yes
  # The aggregate CSV serves as the fallback operation source when Pherf did not produce a JSON
  # latency report, and is the sole source of result row counts.
  pherf_csv "$start" "$end" "$json" || return
  # Compute achieved QPS from the valid latency report operation count, falling back to the
  # aggregate CSV run count.
  total=${PHERF_JSON_OPS:-$PHERF_CSV_RUNS}
  if [ -n "$total" ] && [ "$end" -gt "$start" ]; then
    pb_metric achieved_qps duration \
      "$(awk -v n="$total" -v ms="$((end - start))" 'BEGIN { printf "%.3f", n * 1000 / ms }')" \
      ops/s steady@pherf "$start" "$end" || return
  fi
}

dv_main "$@"
