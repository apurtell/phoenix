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

# Shared Pherf output-reader library used by both the pherf driver and the pherf-distributed
# driver. The output file formats are specified in phoenix-pherf/LATENCY.md and
# phoenix-pherf/VERDICT.md. A driver sources this file after drivers/lib/driver.sh; its readers
# execute on the host against the bundle copies without making any substrate calls.

# Reject direct execution as an operator error because this library must be sourced.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: drivers/lib/pherf.sh is a library. Source it from a driver.\n' >&2
  exit 2
fi

# Guard against multiple inclusion so sourcing this library a second time does not redefine names.
if [ -n "${PH_LOADED:-}" ]; then
  return 0
fi
PH_LOADED=1

# Defines the Pherf label for the measured run. The latency files and the verdict file for a label
# are written to <results>_HDR with the label embedded in their file names.
PHERF_LABEL_RUN=run

# Holds the expected first line of a Pherf histogram log as specified in phoenix-pherf/LATENCY.md.
# It states the recorder configuration, which the JSON latency report also records.
PHERF_HLOG_HEADER="#[Pherf latency log 1: microseconds, lowest 1, highest 600000000,"
PHERF_HLOG_HEADER="$PHERF_HLOG_HEADER 3 significant digits, Tag=OPERATION|PHASE]"

# Holds the total operation count across the steady keys in the latency report. It remains empty
# when pherf_latency did not read a valid report.
PHERF_JSON_OPS=""

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

# Parses and validates a version 1 Pherf JSON latency report (file) via pb_json_flat, emitting
# window rows ("w start end hlog"), histogram rows ("h operation phase count min max mean p50 p90
# p99 p999"), and schedule rows ("s ...").
## @description  Extracts validated rows from a version 1 Pherf JSON latency report (file).
## @return       Writes the rows to stdout and exits 0, or returns 1 with one "x reason" row on a
##               refused report.
pherf_json_rows() {
  local rows
  if ! rows="$(pb_json_flat "$1" || exit "$?")"; then
    printf 'x\tthe file is not one JSON document, see the log of report\n'
    return "$PX_EXIT_FAIL"
  fi
  # Each extracted value is preserved as its raw JSON token so that microsecond measurements retain
  # all of their digits. Schedule rows follow the format "s scenario querySet start end scheduled
  # completed late dropped aborted maxQueueDepth maxDriftUs".
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
        qs = need(p "/querySet", "number", whole)
        # Because a Distributed Pherf fleet report records a separate schedule for each slot of
        # each step, the step and slot indices are appended to qualify the schedule identifier.
        if ((p "/slot") in type)
          qs = qs "." need(p "/step", "number", whole) "." need(p "/slot", "number", whole)
        line = "s\t" need(p "/scenario", "string", "") "\t" qs
        for (k = 1; k <= 9; k++) line = line "\t" need(p "/" sf[k], "number", whole)
        out[++n] = line
      }
      for (i = 1; i <= n; i++) print out[i]
    }'
}

# Transforms the output of pherf_json_rows for the given steady-state start and end timestamps into
# metric rows ("m anchor key class value unit start end") and one summary row ("t keys operations
# p99 operation").
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
    # Only steady-phase keys represent measured work; warmup keys produce no metric records.
    $1 == "h" && $3 == "steady" {
      kind = $2
      sub(/:.*/, "", kind)
      window(rs, re)
      a = anchor("op", $2)
      put(a, "pherf.operations", "count", $4, "ops")
      split("min_us max_us mean_us p50_us p90_us p99_us p999_us", f, " ")
      for (k = 1; k <= 7; k++) put(a, "pherf.latency." f[k], "duration", $(k + 4), "us")
      # Response keys duplicate the operation count of their corresponding query key, and load keys
      # count data-load batches; neither contributes to the total operation count.
      if (kind ~ /^(query|scan|delete|operation)$/) ops += $4
      # Aggregate latency percentiles are derived from the keys graded by the Pherf tail check.
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

# Validates that a Pherf verdict file (file, row count, check expression) contains the expected
# number of valid rows with source pherf and unique check names matching the expression, and appends
# those rows unchanged to verdicts.tsv.
## @description  Validates and appends the rows of a Pherf verdict file to verdicts.tsv.
## @return       Returns 0, or 1 or 2 when the verdict stream cannot be written.
pherf_verdict_copy() {
  local file=$1 count=$2 check=$3 bad stream
  if [ ! -f "$file" ]; then
    sx_verdict pherf.verdict SKIP "Pherf wrote no verdict file, a Pherf before P12"
    return 0
  fi
  # Because px-report escalates a malformed row to ERROR and px-bundle validate refuses it, a file
  # containing any invalid row adds no rows at all, and its FAIL verdict attributes the fault to
  # Pherf. The check expression is passed through the environment because awk -v processes escape
  # sequences in variable assignments, converting "\." into ".".
  bad="$(PHERF_CHECK_RE=$check awk -F '\t' -v severities="$SX_SEVERITIES" \
    -v classes="$SX_CLASSES" -v rows="$count" '
    BEGIN {
      n = split(severities, s, " "); for (i = 1; i <= n; i++) sev[s[i]] = 1
      n = split(classes, c, " "); for (i = 1; i <= n; i++) cls[c[i]] = 1
    }
    {
      why = ""
      if (NF != 5) why = NF " fields, not 5"
      else if ($1 !~ ENVIRON["PHERF_CHECK_RE"]) why = "check \"" $1 "\""
      else if (seen[$1]++) why = "check \"" $1 "\" again"
      else if (!($2 in sev)) why = "severity \"" $2 "\""
      else if (!($3 in cls)) why = "class \"" $3 "\""
      else if ($4 != "pherf") why = "source \"" $4 "\""
      if (why != "") { first = "line " NR ": " why; exit }
    }
    END {
      if (first != "") print first
      else if (NR != rows) print NR " rows, not " rows
    }' "$file")"
  if [ -n "$bad" ]; then
    sx_verdict pherf.verdict FAIL "${file##*/}: $bad"
    return 0
  fi
  stream="$(sx_verdict_file)" || return $?
  # Copy each row unchanged through awk so that a trailing LF is added if the last row lacks one.
  awk '{ print }' "$file" >>"$stream" || return "$PX_EXIT_FAIL"
  sx_verdict pherf.verdict PASS "appended the $(awk 'END { print NR }' "$file") rows of" \
    "${file##*/}, with the source pherf"
}

# Validates and records the run latency report in a results directory (directory, steady start,
# steady end), emits the pherf.latency verdict, and sets PHERF_JSON_OPS when the report is valid.
## @description  Records the run latency report from a results directory and writes pherf.latency.
## @return       Returns 0, or 1 when a record cannot be written.
pherf_latency() {
  local dir=$1 json rows metrics hlog first
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
  # The driver reads summary values from the JSON report rather than decoding a histogram log.
  # Because the same recorders write the run log, the run log must carry the header stating the
  # recorder configuration of the report.
  hlog="$(printf '%s\n' "$rows" | awk -F '\t' '$1 == "w" { print $4 }')"
  first=""
  [ ! -f "$dir/$hlog" ] || IFS= read -r first <"$dir/$hlog" || true
  if [ "$first" != "$PHERF_HLOG_HEADER" ]; then
    sx_verdict pherf.latency FAIL "${json##*/} names the run log $hlog, which is absent or" \
      "does not start with the header of LATENCY.md"
    return 0
  fi
  metrics="$(printf '%s\n' "$rows" | pherf_latency_metrics "$2" "$3")" || return "$PX_EXIT_FAIL"
  while IFS="$PX_TAB" read -r kind anchor key class value unit s e; do
    [ "$kind" != m ] || pb_metric "$key" "$class" "$value" "$unit" "$anchor" "$s" "$e" || return
  done <<EOF
$metrics
EOF
  # Extract the summary row holding the key count, operation count, worst p99 latency, and its key.
  IFS="$PX_TAB" read -r kind keys ops p99 at <<EOF
$(printf '%s\n' "$metrics" | awk -F '\t' '$1 == "t"')
EOF
  PHERF_JSON_OPS=$ops
  if [ "$keys" = 0 ]; then
    sx_verdict pherf.latency PASS "${json##*/} holds no steady key"
  else
    sx_verdict pherf.latency PASS "$keys steady keys, $PHERF_JSON_OPS operations, worst p99" \
      "$p99 us at $at"
  fi
}
