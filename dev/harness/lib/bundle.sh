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

# Result bundle library for the harness. A result bundle is the run directory managed by px-run at
# $PX_OUT/runs/<run-id>; docs/bundle-format.md defines every path inside that directory, and
# docs/driver-contract.md defines the contract for drivers that populate it. This library is
# sourced by px-run, px-bundle, px-report, drivers, cluster checks, and plug-ins so that every
# bundle record shares a single canonical writer and reader implementation.
#
# Five core invariants govern this library:
#   1. All records are emitted through the writers defined here or through the verdict writer in
#      suites/lib/common.sh, which this library sources. That keeps the severity lattice defined
#      in a single place within suites/lib/common.sh.
#   2. Every metric record embeds the active run class and contamination state at write time. This
#      prevents downstream consumers from treating timing metrics from a validation run or a noisy
#      host as authoritative evidence, even if the consumer overlooks the run-class policy.
#   3. Append-only streams (run-record.tsv, stages.tsv, verdicts.tsv, and the collector index
#      metrics/index.tsv) omit header lines, whereas structured exports (metric exports, image and
#      table records, and report tables) begin with a single header line.
#   4. The harness operates without a jq dependency. JSON output is produced using the writers in
#      lib/common.sh and parsed via pb_json_flat, an awk-based parser that flattens a document
#      into one row per value.
#   5. Invoking a writer with invalid arguments terminates the process immediately with usage exit
#      code 2, while operational failures return their respective status codes for the caller to
#      handle.

# This file is a shell library and must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/bundle.sh is a library. Source it after lib/common.sh.\n' >&2
  exit 2
fi

# Guard against redundant sourcing so definitions are not evaluated more than once.
if [ -n "${PB_LOADED:-}" ]; then
  return 0
fi
PB_LOADED=1

PB_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=SCRIPTDIR/common.sh
. "$PB_LIB_DIR/common.sh"
# shellcheck source=SCRIPTDIR/../suites/lib/common.sh
. "$PB_LIB_DIR/../suites/lib/common.sh"

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
# Both version numbers are fixed harness constants rather than configurable parameters. Modifying a
# path, column, or key in docs/bundle-format.md increments the format version. Modifying a verb,
# exit code, or describe key in docs/driver-contract.md, or a plugin.env key in docs/PLUGINS.md,
# increments the contract version.

PB_FORMAT_VERSION=1
PB_CONTRACT_VERSION=1
readonly PB_FORMAT_VERSION PB_CONTRACT_VERSION

# The six driver lifecycle verbs, listed in the order in which px-run invokes them.
PB_VERBS="describe validate prepare run restore report"

# The five driver-reported steady-state metrics included in the manifest. The sixth steady-state
# metric, disruptions[].mttr_seconds, is computed directly by px-run rather than by a driver.
PB_STEADY_KEYS="query_latency.p50_ms query_latency.p99_ms query_errors offered_rate achieved_qps"

# The supported metric classes. The verdict classes of suites/lib/common.sh (SX_CLASSES) are these
# three plus one more class, none, which no metric record may carry.
PB_METRIC_CLASSES="count share duration"

# Regular expression grammars validating stage anchors, metric or check keys, units, and run IDs.
PB_ANCHOR_RE='^[a-z]+@[A-Za-z0-9_.-]+$'
PB_KEY_RE='^[A-Za-z0-9_][A-Za-z0-9_.:@-]*$'
PB_UNIT_RE='^[A-Za-z0-9_%/.-]+$'
PB_RUN_ID_RE='^[0-9]{8}T[0-9]{6}Z-[0-9a-z]{4}$'

# Schema definitions for export header lines, expressed as space-separated column names.
PB_METRIC_FIELDS="start_ms end_ms source anchor key class value unit run_class contamination"
PB_IMAGE_FIELDS="role index image_ref image_id"
PB_TABLE_FIELDS="schema table update_cache_frequency"

# Expected artifact paths within a bundle. docs/bundle-format.md documents these same lists along
# with the producing component and content schema for each entry. A trailing slash denotes a
# directory artifact, which is considered present only when it contains at least one file.
PB_REQUIRED="run-record.tsv stages.tsv verdicts.tsv metrics/timeline.tsv
metrics/aggregate.tsv cluster/images.tsv report/run-summary.tsv
report/cluster-summary.tsv report/verdicts.tsv"
PB_REQUIRED_CLUSTER="cluster/topology.env cluster/services.tsv"
# Instance daemon log directory, which is required whenever the cluster retains at least one
# instance after the run finishes.
PB_REQUIRED_DAEMON="logs/instance/"
PB_OPTIONAL="breach.tsv tables.tsv thresholds.properties cluster/plugins/ logs/gc/
metrics/index.tsv collect/started.tsv profiles/ jfr/ traces/ oplog/ meta/"

# Telemetry collectors started by px-run around the run verb, listed in their startup order. They
# are implemented by lib/collect.sh and bin/px-collect, and the "The collectors" section of
# docs/bundle-format.md specifies the underlying tool and output files for each collector.
PB_COLLECTORS="profile jfr hiccup host container trace"
# The sampler collectors share a single background process for each invocation of px-collect start,
# allowing them to start and stop as a single step, whereas each remaining collector is a session
# collector that executes as its own individual step.
PB_SAMPLERS="hiccup host container"

# Top-level subdirectories created when initializing a new bundle directory.
PB_DIRS="drivers metrics logs cluster plugins report"

# ASCII Unit Separator used as an internal field delimiter. Because it is not a whitespace
# character, adjacent delimiters preserve empty intermediate fields during shell read operations,
# unlike tab characters.
PB_US=$'\037'

# Cached pb_json_flat row representation of the most recently validated driver describe document
# loaded by pb_describe_load.
PB_DESCRIBE=""

# ---------------------------------------------------------------------------
# Bundle directory management
# ---------------------------------------------------------------------------

## @description  Creates and initializes a new run bundle directory under $PX_OUT/runs.
## @return       Writes the bundle path to stdout and exits 0; returns the px_run_dir exit status,
##               or 1 if the directory name violates the run ID grammar or layout creation fails.
pb_bundle_create() {
  local dir
  dir="$(px_run_dir runs)" || return $?
  if ! [[ ${dir##*/} =~ $PB_RUN_ID_RE ]]; then
    px_error "the run directory $dir does not follow the run id grammar"
    return "$PX_EXIT_FAIL"
  fi
  pb_bundle_layout "$dir" || return $?
  printf '%s\n' "$dir"
}

# ---------------------------------------------------------------------------
# Bundle operand resolution
# ---------------------------------------------------------------------------
# All BUNDLE operands follow a single resolution policy. A slash-free operand matching the run ID
# grammar resolves to $PX_OUT/runs/<id>, whereas any other operand is interpreted as a filesystem
# path (with relative paths resolved against the current working directory), and a bare word that is
# neither a run ID nor a path does not name a bundle. Every bundle must already exist under PX_OUT
# and outside the working tree; the resolver never creates directories. When a reader is invoked
# without an operand, it selects the newest finished bundle: the latest run ID under runs/ whose
# directory contains manifest.json, using the newer manifest.json timestamp to break ties between
# two run IDs created in the same second. Collections under collect/ and copies under copies/ are
# never chosen as the default bundle.

## @description  Checks whether an operand (operand) syntactically identifies a bundle, either as a
##               valid run ID or as a path containing a slash.
## @return       Returns 0 if the operand matches a bundle form, or 1 otherwise.
pb_bundle_operand() {
  case "${1:-}" in
  */*) return 0 ;;
  esac
  [[ ${1:-} =~ $PB_RUN_ID_RE ]]
}

## @description  Resolves a BUNDLE operand (operand) to the canonical physical path of the bundle.
## @return       Writes the path to stdout and exits 0; exits 1 if resolution fails, 2 if the path
##               lies outside PX_OUT or inside the working tree, or 6 if the bundle does not exist.
pb_bundle_resolve() {
  local root path=${1:-} dir
  root="$(px_out_root)" || exit "$?"
  [ -n "$path" ] || px_die "$PX_EXIT_USAGE" "name a bundle: a path or a run id"
  case "$path" in
  */*) ;;
  *)
    if [[ $path =~ $PB_RUN_ID_RE ]]; then
      path="$root/runs/$path"
    elif [ ! -e "$path" ]; then
      px_die "$PX_EXIT_NO_OBJECT" "$path is no bundle: neither a run id nor a path"
    fi
    ;;
  esac
  case "$path" in
  /*) ;;
  *) path="$PWD/$path" ;;
  esac
  dir="$(px_realpath "$path")" || px_die "$PX_EXIT_FAIL" "cannot resolve the bundle $1"
  if px_path_within "$dir" "$PX_TREE_ROOT"; then
    px_die "$PX_EXIT_USAGE" "the bundle $dir lies inside the working tree $PX_TREE_ROOT"
  fi
  if [ "$dir" -ef "$root" ] || ! px_path_within "$dir" "$root"; then
    px_die "$PX_EXIT_USAGE" "the bundle $dir lies outside PX_OUT ($root)"
  fi
  [ -d "$dir" ] || px_die "$PX_EXIT_NO_OBJECT" "no such bundle: $dir"
  printf '%s\n' "$dir"
}

# Identifies the newest finished bundle by selecting the latest run ID under $PX_OUT/runs whose
# directory contains manifest.json.
## @description  Locates the newest finished bundle under $PX_OUT/runs.
## @return       Writes the path to stdout and exits 0; exits 6 when no finished bundle exists, or
##               exits 2 or 1 when PX_OUT validation fails.
pb_bundle_newest() {
  local root dir name newest=""
  root="$(px_out_root)" || exit "$?"
  # Because a run ID prefix has one-second resolution and its random suffix does not establish
  # chronological order within the same second, the newer manifest.json timestamp breaks ties.
  for dir in "$root"/runs/*; do
    name=${dir##*/}
    [[ $name =~ $PB_RUN_ID_RE ]] && [ -f "$dir/manifest.json" ] || continue
    if [ -z "$newest" ] || [[ ${name%%-*} > ${newest%%-*} ]] ||
      { [ "${name%%-*}" = "${newest%%-*}" ] &&
        [ "$dir/manifest.json" -nt "$root/runs/$newest/manifest.json" ]; }; then
      newest=$name
    fi
  done
  [ -n "$newest" ] ||
    px_die "$PX_EXIT_NO_OBJECT" "no finished bundle under $root/runs: name one"
  printf '%s\n' "$root/runs/$newest"
}

# Each cluster tracks its collection directories that may contain active collectors, recording one
# directory path per line so that px-collect stop can locate the current collection and px-cluster
# down can terminate all remaining collectors.
## @description  Outputs the collection tracking file path for the PX_CLUSTER_ID cluster.
## @return       Writes the path to stdout and exits 0, or returns 2 or 1 if the output policy
##               rejects the state directory.
pb_collect_record() {
  local dir
  dir="$(px_state_dir)" || return $?
  printf '%s/collect-%s.tsv\n' "$dir" "${PX_CLUSTER_ID:-default}"
}

# Accepts either the token none or a comma- or space-separated list of collector names and emits the
# selected names in canonical PB_COLLECTORS order, separated by spaces.
## @description  Parses and normalizes a collector specification (value) into PB_COLLECTORS order.
## @return       Writes the space-separated names to stdout and exits 0, or exits 2 if an unknown
##               collector name is given or if none is combined with another name.
pb_collectors_parse() {
  local value=${1:-none} name given="" out="" label
  label="$(px_setting_label PX_COLLECTORS)"
  # Both validation and canonical ordering split the input on commas and whitespace identically,
  # ensuring that whitespace following a comma neither masks nor fabricates a collector name.
  for name in ${value//,/ }; do
    case " $PB_COLLECTORS none " in
    *" $name "*) given="$given $name " ;;
    *) px_die "$PX_EXIT_USAGE" "$label: '$name' is no collector; give none or names of" \
      "$PB_COLLECTORS" ;;
    esac
  done
  [ "$given" != " none " ] || return 0
  case "$given" in
  *" none "*) px_die "$PX_EXIT_USAGE" "$label: none stands alone, got '$value'" ;;
  esac
  for name in $PB_COLLECTORS; do
    case "$given" in
    *" $name "*) out="${out:+$out }$name" ;;
    esac
  done
  printf '%s\n' "$out"
}

# The timeout bound of a px-collect invocation allocates one verb timeout bound per synchronous
# step: one for each session collector plus one shared step for the sampler background process
# during start.
## @description  Computes the number of synchronous steps required for a px-collect invocation over
##               a space-separated collector list (names).
## @return       Writes the step count to stdout and exits 0.
pb_collect_steps() {
  local name steps=0 sampler=0
  for name in ${1:-}; do
    case " $PB_SAMPLERS " in
    *" $name "*) sampler=1 ;;
    *) steps=$((steps + 1)) ;;
    esac
  done
  printf '%s\n' "$((steps + sampler))"
}

## @description  Initializes bundle subdirectories, empty streams, and export headers in place,
##               preserving the contents of any existing paths.
## @return       Exits 0 on success, 1 if a path write fails, or 6 if the directory is absent.
pb_bundle_layout() {
  local dir=${1:-} sub
  [ -d "$dir" ] || return "$PX_EXIT_NO_OBJECT"
  for sub in $PB_DIRS; do
    mkdir -p "$dir/$sub" || return "$PX_EXIT_FAIL"
  done
  : >>"$dir/stages.tsv" || return "$PX_EXIT_FAIL"
  : >>"$dir/verdicts.tsv" || return "$PX_EXIT_FAIL"
  pb_header "$dir/metrics/timeline.tsv" "$PB_METRIC_FIELDS" || return
  pb_header "$dir/metrics/aggregate.tsv" "$PB_METRIC_FIELDS" || return
  pb_header "$dir/cluster/images.tsv" "$PB_IMAGE_FIELDS"
}

## @description  Writes a tab-separated header line to an export file (file, space-separated field
##               names) whenever the target file is absent or empty.
## @return       Exits 0 on success, or 1 if the file cannot be created or written.
pb_header() {
  [ ! -s "$1" ] || return 0
  mkdir -p "$(dirname "$1")" || return "$PX_EXIT_FAIL"
  printf '%s\n' "$2" | tr ' ' '\t' >"$1" || return "$PX_EXIT_FAIL"
}

## @description  Checks whether the given token is one of the six driver contract lifecycle verbs.
## @return       Exits 0 if the token is a recognized verb, or 1 otherwise.
pb_verb_known() {
  case " $PB_VERBS " in
  *" ${1:-} "*) [ -n "${1:-}" ] ;;
  *) return 1 ;;
  esac
}

## @description  Resolves the active record source from PX_RECORD_SOURCE, defaulting to action.
## @return       Writes the sanitized TSV-safe source identifier to stdout and exits 0.
pb_source() { px_tsv_clean "${PX_RECORD_SOURCE:-action}"; }

# ---------------------------------------------------------------------------
# Stages
# ---------------------------------------------------------------------------
# Each stage record contains five tab-separated columns: ts_ms, event, anchor, source, and detail.
# The event must be either begin or end, and the anchor follows the <kind>@<name> format. While
# drivers may emit arbitrary anchor kinds, the steady kind carries special semantics for the
# harness: the interval between its begin and end events defines the steady-state window within
# which all steady-state metrics must fall.

## @description  Appends a timestamped stage transition row (file, event, anchor, detail...) to the
##               specified stage stream file.
## @return       Exits 0 on success, 1 on write failure, or 2 if the file, event, or anchor is bad.
pb_stage_to() {
  local file=${1:-} event=${2:-} anchor=${3:-} detail="${*:4}"
  case "$event" in
  begin | end) ;;
  *) px_die "$PX_EXIT_USAGE" "a stage event is begin or end, got '$event'" ;;
  esac
  [[ $anchor =~ $PB_ANCHOR_RE ]] ||
    px_die "$PX_EXIT_USAGE" "an anchor is <kind>@<name>, got '$anchor'"
  [ -n "$file" ] || px_die "$PX_EXIT_USAGE" "a stage row needs a file"
  px_tsv_row "$(px_epoch_ms)" "$event" "$anchor" "$(pb_source)" \
    "$(px_tsv_clean "$detail")" >>"$file" || return "$PX_EXIT_FAIL"
}

## @description  Appends a stage row (event, anchor, detail...) to stages.tsv under PX_RUN_DIR.
## @return       Exits 0 on success, 1 if the row cannot be written, or 2 if the event or anchor is
##               invalid or PX_RUN_DIR is unset or rejected.
pb_stage() {
  local dir
  dir="$(sx_run_dir)" || return $?
  pb_stage_to "$dir/stages.tsv" "$@"
}

# Matches each stage begin event with the first subsequent end event for the same anchor, emitting
# tab-separated rows of anchor, start_ms, end_ms, and source; unclosed anchors have an empty end_ms.
## @description  Pairs each begin event in a stage file with its corresponding end event.
## @return       Writes paired rows to stdout in begin order and exits 0, even if the file is
##               absent.
pb_stage_pairs() {
  [ -f "${1:-}" ] || return 0
  awk -F '\t' '
    $2 == "begin" { n++; anchor[n] = $3; start[n] = $1; src[n] = $4; open[$3] = n; next }
    $2 == "end" && ($3 in open) { stop[open[$3]] = $1; delete open[$3] }
    END { for (i = 1; i <= n; i++) print anchor[i] "\t" start[i] "\t" stop[i] "\t" src[i] }
  ' "$1"
}

## @description  Outputs the most recent closed time window (anchor, [file]) as tab-separated
##               start_ms and end_ms values, defaulting to stages.tsv in PX_RUN_DIR.
## @return       Writes the window to stdout and exits 0, or returns 6 if no closed window exists.
pb_stage_window() {
  local anchor=${1:-} file=${2:-} window
  if [ -z "$file" ]; then
    file="$(sx_run_dir)/stages.tsv" || return $?
  fi
  window="$(pb_stage_pairs "$file" |
    awk -F '\t' -v a="$anchor" '$1 == a && $3 != "" { w = $2 "\t" $3 } END { print w }')"
  [ -n "$window" ] || return "$PX_EXIT_NO_OBJECT"
  printf '%s\n' "$window"
}

## @description  Appends an end event for every unclosed anchor in a stage file (file, detail...)
##               so that verbs terminated early by px-run still leave closed stage windows.
## @return       Exits 0 on success, or 1 if any closing row cannot be written.
pb_stage_close_open() {
  local file=${1:-} detail="${*:2}" anchor
  while IFS= read -r anchor; do
    [ -n "$anchor" ] || continue
    pb_stage_to "$file" end "$anchor" "$detail" || return
  done <<EOF
$(pb_stage_pairs "$file" | awk -F '\t' '$3 == "" { print $1 }' | sort -u)
EOF
}

# ---------------------------------------------------------------------------
# Metric records
# ---------------------------------------------------------------------------
# Both metric exports share a ten-column schema: start_ms, end_ms, source, anchor, key, class,
# value, unit, run_class, and contamination. The anchor identifies the stage to which the metric
# belongs, or uses run for whole-run measurements. The aggregate export stores one value per key
# and anchor pair, where subsequent records supersede earlier ones. The timeline export stores
# point-in-time samples whose start_ms and end_ms timestamps both equal the sample timestamp.

## @description  Records an aggregate metric (key, class, value, unit, [anchor, start_ms, end_ms]),
##               defaulting anchor to run and timestamps to the current epoch time.
## @return       Exits 0 on success, or propagates the status returned by pb_metric_to.
pb_metric() { pb_metric_to aggregate "$@"; }

## @description  Records a timeline metric sample (key, class, value, unit, [anchor, ts_ms]), with
##               anchor defaulting to run and ts_ms defaulting to the current epoch time.
## @return       Exits 0 on success, or propagates the status returned by pb_metric_to.
pb_sample() {
  local ts=${6:-}
  [ -n "$ts" ] || ts="$(px_epoch_ms)"
  pb_metric_to timeline "${1:-}" "${2:-}" "${3:-}" "${4:-}" "${5:-run}" "$ts" "$ts"
}

## @description  Validates and appends a run-class-tagged metric record (export, key, class, value,
##               unit, [anchor, start, end]) to metrics/<export>.tsv in PX_RUN_DIR.
## @return       Exits 0 on success, 1 on write failure, or 2 if any field or PX_RUN_CLASS is bad.
pb_metric_to() {
  local export=${1:-} key=${2:-} class=${3:-} value=${4:-} unit=${5:-} anchor=${6:-run}
  local start=${7:-} end=${8:-} dir file contamination
  case "$export" in
  aggregate | timeline) ;;
  *) px_die "$PX_EXIT_USAGE" "a metric export is aggregate or timeline, got '$export'" ;;
  esac
  [[ $key =~ $PB_KEY_RE ]] || px_die "$PX_EXIT_USAGE" "invalid metric key '$key'"
  case " $PB_METRIC_CLASSES " in
  *" $class "*) ;;
  *) px_die "$PX_EXIT_USAGE" "metric $key: class '$class' is not one of $PB_METRIC_CLASSES" ;;
  esac
  [[ $value =~ $PX_RE_NUMBER ]] || px_die "$PX_EXIT_USAGE" "metric $key: '$value' is no number"
  [[ $unit =~ $PB_UNIT_RE ]] || px_die "$PX_EXIT_USAGE" "metric $key: invalid unit '$unit'"
  if [ "$anchor" != run ] && ! [[ $anchor =~ $PB_ANCHOR_RE ]]; then
    px_die "$PX_EXIT_USAGE" "metric $key: the anchor is run or <kind>@<name>, got '$anchor'"
  fi
  [ -n "$start" ] || start="$(px_epoch_ms)"
  [ -n "$end" ] || end=$start
  if ! [[ $start =~ $PX_RE_INT ]] || ! [[ $end =~ $PX_RE_INT ]] || [ "$end" -lt "$start" ]; then
    px_die "$PX_EXIT_USAGE" "metric $key: the window $start to $end is not a window"
  fi
  case "${PX_RUN_CLASS:-}" in
  validation | measurement) ;;
  *) px_die "$PX_EXIT_USAGE" "metric $key: PX_RUN_CLASS is '${PX_RUN_CLASS:-}', a record" \
    "needs validation or measurement" ;;
  esac
  contamination="$(px_tsv_clean "${PX_CONTAMINATION:-}")"
  dir="$(sx_run_dir)" || return $?
  file="$dir/metrics/$export.tsv"
  pb_header "$file" "$PB_METRIC_FIELDS" || return
  px_tsv_row "$start" "$end" "$(pb_source)" "$anchor" "$key" "$class" "$value" "$unit" \
    "$PX_RUN_CLASS" "${contamination:-none}" >>"$file" || return "$PX_EXIT_FAIL"
}

## @description  Records the UPDATE_CACHE_FREQUENCY setting for a table (schema, table, value) in
##               tables.tsv under PX_RUN_DIR, substituting default when schema or value is empty.
## @return       Exits 0 on success, 1 on write failure, or 2 if the table name is empty.
pb_table() {
  local schema=${1:-} table=${2:-} value=${3:-} dir file
  [ -n "$table" ] || px_die "$PX_EXIT_USAGE" "a table record needs a table name"
  dir="$(sx_run_dir)" || return $?
  file="$dir/tables.tsv"
  pb_header "$file" "$PB_TABLE_FIELDS" || return
  px_tsv_row "$(px_tsv_clean "${schema:-default}")" "$(px_tsv_clean "$table")" \
    "$(px_tsv_clean "${value:-default}")" >>"$file" || return "$PX_EXIT_FAIL"
}

## @description  Appends a target row (collector, target, status, file, detail...) to the collector
##               index metrics/index.tsv in PX_RUN_DIR, requiring a bundle path when status is ok.
## @return       Exits 0 on success, 1 on write failure, or 2 on a bad collector, status, or path.
pb_collector() {
  local name=${1:-} target=${2:-} status=${3:-} artifact=${4:-} detail="${*:5}" dir
  case " $PB_COLLECTORS " in
  *" $name "*) [ -n "$name" ] || px_die "$PX_EXIT_USAGE" "an index row needs a collector" ;;
  *) px_die "$PX_EXIT_USAGE" "'$name' is not a collector of $PB_COLLECTORS" ;;
  esac
  case "$status" in
  ok | skip | fail) ;;
  *) px_die "$PX_EXIT_USAGE" "collector $name: the status is ok, skip or fail, got '$status'" ;;
  esac
  case "$artifact" in
  /* | *..* | *"$PX_TAB"* | *"$PX_NL"*)
    px_die "$PX_EXIT_USAGE" "collector $name: '$artifact' is not a path inside the bundle"
    ;;
  esac
  if [ -z "$target" ] || { [ "$status" = ok ] && [ -z "$artifact" ]; }; then
    px_die "$PX_EXIT_USAGE" "collector $name: a row needs a target, and an ok row a file"
  fi
  dir="$(sx_run_dir)" || return $?
  mkdir -p "$dir/metrics" || return "$PX_EXIT_FAIL"
  px_tsv_row "$name" "$(px_tsv_clean "$target")" "$status" "$artifact" \
    "$(px_tsv_clean "$detail")" >>"$dir/metrics/index.tsv" || return "$PX_EXIT_FAIL"
}

# ---------------------------------------------------------------------------
# Authority of time evidence
# ---------------------------------------------------------------------------
# Duration metrics constitute authoritative pass/fail evidence only when collected on an
# uncontaminated quiet host during a measurement-class run. Count and share metrics remain
# authoritative across all run classes and host conditions. px-report evaluates every verdict row
# against this policy, which is defined canonically here.

## @description  Determines whether timing evidence for a run (run class, contamination) is
##               authoritative, requiring the measurement run class with no host contamination.
## @return       Exits 0 when timing evidence is authoritative, or 1 otherwise.
pb_time_authoritative() {
  [ "${1:-}" = measurement ] || return 1
  case "${2:-}" in
  '' | none) return 0 ;;
  esac
  return 1
}

## @description  Computes effective severity (severity, class, run class, contamination),
##               downgrading a duration FAIL to ADVISORY when time evidence is not authoritative.
## @return       Writes the resolved severity token to stdout and exits 0.
pb_effective_severity() {
  if [ "${1:-}" = FAIL ] && [ "${2:-}" = duration ] &&
    ! pb_time_authoritative "${3:-}" "${4:-}"; then
    printf 'ADVISORY\n'
  else
    printf '%s\n' "${1:-}"
  fi
}

# ---------------------------------------------------------------------------
# JSON reader
# ---------------------------------------------------------------------------
# pb_json_flat parses a JSON document and emits one tab-separated row per value containing its
# path, type, and scalar value. Paths follow RFC 6901 JSON Pointer syntax: / denotes the root,
# /run/id an object member, and /steady/0 an array element, with ~ encoded as ~0 and / encoded as
# ~1 inside member names. Each type is one of object, array, string, number, boolean, or null.
# Container rows record their element or member count as the value so that empty arrays and objects
# remain observable. Escape sequences for tabs, newlines, and other control characters within
# strings are normalized to spaces to preserve single-field TSV integrity, while non-control \u
# escapes are retained verbatim. Duplicate member names within an object are rejected as errors
# because harness writers never emit duplicate keys and readers cannot disambiguate them.

## @description  Parses a JSON document (file) into tab-separated rows of path, type, and value.
## @return       Writes rows to stdout and exits 0; exits 1 with the failure reason and byte offset
##               on stderr if the input is not a valid single JSON document, or 6 if absent.
pb_json_flat() {
  local file=${1:-}
  [ -f "$file" ] || return "$PX_EXIT_NO_OBJECT"
  # Buffer the entire file into a single string and parse it with a recursive-descent parser.
  LC_ALL=C awk -v name="$file" '
    BEGIN {
      for (i = 1; i < 32; i++) ctrl = ctrl sprintf("%c", i)
    }
    { text = text $0 "\n" }
    END {
      n = length(text)
      pos = 1
      ws()
      if (pos > n) fail("the document is empty")
      value("")
      ws()
      if (pos <= n) fail("text follows the document")
    }
    function fail(msg) {
      printf "%s: %s at byte %d\n", name, msg, pos > "/dev/stderr"
      exit 1
    }
    function ws(   c) {
      while (pos <= n) {
        c = substr(text, pos, 1)
        if (c != " " && c != "\t" && c != "\n" && c != "\r") return
        pos++
      }
    }
    function put(path, type, v) {
      if (path == "") path = "/"
      if (path in seen) fail("a member name repeats")
      seen[path] = 1
      print path "\t" type "\t" v
    }
    function pointer(key) {
      gsub(/~/, "~0", key)
      gsub(/\//, "~1", key)
      return key
    }
    function value(path,   c, v) {
      ws()
      if (pos > n) fail("the document ends early")
      c = substr(text, pos, 1)
      if (c == "{") { object(path); return }
      if (c == "[") { array(path); return }
      if (c == "\"") { v = string(); put(path, "string", v); return }
      if (substr(text, pos, 4) == "true") { pos += 4; put(path, "boolean", "true"); return }
      if (substr(text, pos, 5) == "false") { pos += 5; put(path, "boolean", "false"); return }
      if (substr(text, pos, 4) == "null") { pos += 4; put(path, "null", ""); return }
      if (match(substr(text, pos, 64), /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?/)) {
        v = substr(text, pos, RLENGTH)
        pos += RLENGTH
        put(path, "number", v)
        return
      }
      fail("a value cannot start here")
    }
    function object(path,   count, key, c) {
      pos++
      ws()
      if (substr(text, pos, 1) == "}") { pos++; put(path, "object", 0); return }
      count = 0
      while (1) {
        ws()
        if (substr(text, pos, 1) != "\"") fail("a member needs a quoted name")
        key = string()
        ws()
        if (substr(text, pos, 1) != ":") fail("a member name needs a colon")
        pos++
        value(path "/" pointer(key))
        count++
        ws()
        c = substr(text, pos, 1)
        if (c == ",") { pos++; continue }
        if (c == "}") { pos++; break }
        fail("an object needs a comma or a closing brace")
      }
      put(path, "object", count)
    }
    function array(path,   count, c) {
      pos++
      ws()
      if (substr(text, pos, 1) == "]") { pos++; put(path, "array", 0); return }
      count = 0
      while (1) {
        value(path "/" count)
        count++
        ws()
        c = substr(text, pos, 1)
        if (c == ",") { pos++; continue }
        if (c == "]") { pos++; break }
        fail("an array needs a comma or a closing bracket")
      }
      put(path, "array", count)
    }
    function string(   out, c, e, hex) {
      pos++
      out = ""
      while (pos <= n) {
        c = substr(text, pos, 1)
        if (c == "\"") { pos++; return out }
        if (c == "\\") {
          e = substr(text, pos + 1, 1)
          if (e == "\"" || e == "\\" || e == "/") { out = out e; pos += 2; continue }
          if (e == "b" || e == "f" || e == "n" || e == "r" || e == "t") {
            out = out " "
            pos += 2
            continue
          }
          if (e == "u") {
            hex = substr(text, pos + 2, 4)
            if (hex !~ /^[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]$/)
              fail("a bad unicode escape")
            if (hex ~ /^00[01]/) out = out " "
            else out = out "\\u" hex
            pos += 6
            continue
          }
          fail("an unknown escape")
        }
        if (index(ctrl, c) > 0) fail("a control character inside a string")
        out = out c
        pos++
      }
      fail("a string does not end")
    }
  ' "$file" || return "$PX_EXIT_FAIL"
}

## @description  Extracts the value for a JSON Pointer path (path, pb_json_flat rows), returning
##               the member count for containers and an empty string for null values.
## @return       Writes the value to stdout and exits 0, or returns 6 if the path is absent.
pb_json_get() {
  local rest="$PX_NL${2:-}"
  case "$rest" in
  *"$PX_NL${1:-}$PX_TAB"*) rest=${rest#*"$PX_NL${1:-}$PX_TAB"} ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
  rest=${rest%%"$PX_NL"*}
  printf '%s\n' "${rest#*"$PX_TAB"}"
}

## @description  Extracts the JSON value type for a JSON Pointer path (path, pb_json_flat rows).
## @return       Writes object, array, string, number, boolean, or null and exits 0, or 6 if absent.
pb_json_type() {
  local rest="$PX_NL${2:-}"
  case "$rest" in
  *"$PX_NL${1:-}$PX_TAB"*) rest=${rest#*"$PX_NL${1:-}$PX_TAB"} ;;
  *) return "$PX_EXIT_NO_OBJECT" ;;
  esac
  printf '%s\n' "${rest%%"$PX_TAB"*}"
}

## @description  Projects an array (pb_json_flat rows, array path, [field...]) into TSV rows of
##               named object member values, or scalar element values when no fields are given.
## @return       Writes one tab-separated row per array element in index order to stdout; exits 0.
pb_json_rows() {
  local rows=${1:-} base=${2:-}
  shift 2
  printf '%s\n' "$rows" | awk -F '\t' -v base="$base" -v fields="$*" '
    BEGIN { nf = split(fields, f, " ") }
    index($1, base "/") == 1 {
      rest = substr($1, length(base) + 2)
      if (rest !~ /^[0-9]+(\/|$)/) next
      i = rest
      sub(/\/.*/, "", i)
      sub(/^[0-9]+/, "", rest)
      if (i + 1 > m) m = i + 1
      if (nf == 0) { if (rest == "") cell[i, 0] = $3; next }
      for (k = 1; k <= nf; k++) if (rest == "/" f[k]) cell[i, k] = $3
    }
    END {
      for (i = 0; i < m; i++) {
        if (nf == 0) { print cell[i, 0]; continue }
        line = cell[i, 1]
        for (k = 2; k <= nf; k++) line = line "\t" cell[i, k]
        print line
      }
    }'
}

# ---------------------------------------------------------------------------
# Driver describe document validation and querying
# ---------------------------------------------------------------------------
# The schema for driver describe documents is specified in the "describe" section of
# docs/driver-contract.md. Both px-run (before invoking any other driver verb) and px-bundle (when
# verifying a stored bundle) validate the document through these shared routines.

## @description  Validates flattened describe document rows (pb_json_flat rows) for an unsupported
##               contract version, missing required keys, or malformed anchors, metrics, or entries.
## @return       Writes one diagnostic line per problem to stdout (empty when valid) and exits 0.
pb_describe_problems() {
  local rows=${1:-} contract kind count index value seen="" class steady=0 field reason
  contract="$(pb_json_get /contract "$rows" || true)"
  if [ "$(pb_json_type /contract "$rows" || true)" != number ]; then
    printf 'contract: a number is required\n'
  elif [ "$contract" != "$PB_CONTRACT_VERSION" ]; then
    printf 'contract: version %s is unsupported, this harness speaks %s\n' "$contract" \
      "$PB_CONTRACT_VERSION"
  fi
  value="$(pb_json_get /driver "$rows" || true)"
  if [ "$(pb_json_type /driver "$rows" || true)" != string ] || [ -z "$value" ]; then
    printf 'driver: a non-empty string is required\n'
  fi
  for kind in /phases /metrics /unsupported /metrics/count /metrics/share /metrics/duration \
    /unsupported/checks /unsupported/artifacts; do
    case "$kind" in
    /metrics | /unsupported) value=object ;;
    *) value=array ;;
    esac
    if [ "$(pb_json_type "$kind" "$rows" || true)" != "$value" ]; then
      printf '%s: an %s is required\n' "${kind#/}" "$value"
    fi
  done
  count=0
  if [ "$(pb_json_type /phases "$rows" || true)" = array ]; then
    count="$(pb_json_get /phases "$rows" || true)"
  fi
  [[ $count =~ $PX_RE_INT ]] || count=0
  index=0
  while [ "$index" -lt "$count" ]; do
    value="$(pb_json_get "/phases/$index/anchor" "$rows" || true)"
    if ! [[ $value =~ $PB_ANCHOR_RE ]]; then
      printf 'phases/%s/anchor: <kind>@<name> is required, got "%s"\n' "$index" "$value"
    fi
    case "$value" in steady@*) steady=1 ;; esac
    value="$(pb_json_get "/phases/$index/verb" "$rows" || true)"
    case "$value" in
    validate | prepare | run | restore) ;;
    *) printf 'phases/%s/verb: "%s" is not a verb that owns a phase\n' "$index" "$value" ;;
    esac
    index=$((index + 1))
  done
  [ "$steady" = 1 ] || printf 'phases: no phase carries a steady@ anchor\n'
  for class in $PB_METRIC_CLASSES; do
    while IFS= read -r value; do
      [ -n "$value" ] || continue
      [[ $value =~ $PB_KEY_RE ]] || printf 'metrics/%s: invalid key "%s"\n' "$class" "$value"
      case "$seen" in
      *" $value "*) printf 'metrics: key "%s" is in two classes\n' "$value" ;;
      esac
      seen="$seen $value "
    done <<EOF
$(pb_json_rows "$rows" "/metrics/$class")
EOF
  done
  for kind in checks:key artifacts:path; do
    field=${kind#*:}
    kind=${kind%%:*}
    while IFS="$PB_US" read -r value reason; do
      [ -n "$value$reason" ] || continue
      if [ -z "$value" ] || [ -z "$reason" ]; then
        printf 'unsupported/%s: every entry needs a %s and a reason\n' "$kind" "$field"
      fi
      case "$kind:$value" in
      artifacts:/* | artifacts:*..*)
        printf 'unsupported/artifacts: "%s" is not a path inside the bundle\n' "$value"
        ;;
      esac
    done <<EOF
$(pb_json_rows "$rows" "/unsupported/$kind" "$field" reason | tr '\t' "$PB_US")
EOF
  done
  case "$(pb_json_type /artifacts "$rows" || true)" in
  '' | array) ;;
  *) printf 'artifacts: an array is required when present\n' ;;
  esac
  # Neither an omitted artifacts member nor an empty array declares any artifact paths.
  [ -n "$(pb_json_type /artifacts/0 "$rows" || true)" ] || return 0
  while IFS= read -r value; do
    case "$value" in
    '' | /* | *..*) printf 'artifacts: "%s" is not a path inside the bundle\n' "$value" ;;
    esac
  done <<EOF
$(pb_json_rows "$rows" /artifacts path)
EOF
}

## @description  Parses and validates a driver describe document (file), caching it in PB_DESCRIBE.
## @return       Exits 0 if valid, 2 if malformed JSON or invalid (logging each issue to stderr),
##               or 6 if the file is absent.
pb_describe_load() {
  local file=${1:-} rows problems line
  PB_DESCRIBE=""
  if [ ! -f "$file" ]; then
    px_error "no describe document at $file"
    return "$PX_EXIT_NO_OBJECT"
  fi
  rows="$(pb_json_flat "$file" || exit "$?")" || {
    px_error "the describe document $file is not one JSON document"
    return "$PX_EXIT_USAGE"
  }
  problems="$(pb_describe_problems "$rows")"
  if [ -n "$problems" ]; then
    while IFS= read -r line; do
      px_error "describe document $file: $line"
    done <<EOF
$problems
EOF
    return "$PX_EXIT_USAGE"
  fi
  PB_DESCRIBE=$rows
}

# Extracts a section from PB_DESCRIBE: phases (anchor, verb), metrics (key, class), checks (key,
# reason), unsupported-artifacts (path, reason), or artifacts (path).
## @description  Queries a named section of the cached PB_DESCRIBE document as TSV rows.
## @return       Writes TSV rows to stdout and exits 0, or exits 2 if the section name is unknown.
pb_describe_rows() {
  local class
  case "${1:-}" in
  phases) pb_json_rows "$PB_DESCRIBE" /phases anchor verb ;;
  metrics)
    for class in $PB_METRIC_CLASSES; do
      pb_json_rows "$PB_DESCRIBE" "/metrics/$class" | awk -v c="$class" 'NF { print $0 "\t" c }'
    done
    ;;
  checks) pb_json_rows "$PB_DESCRIBE" /unsupported/checks key reason ;;
  unsupported-artifacts) pb_json_rows "$PB_DESCRIBE" /unsupported/artifacts path reason ;;
  artifacts) pb_json_rows "$PB_DESCRIBE" /artifacts path ;;
  *) px_die "$PX_EXIT_USAGE" "pb_describe_rows: unknown part '${1:-}'" ;;
  esac
}

# ---------------------------------------------------------------------------
# Bundle component readers
# ---------------------------------------------------------------------------

## @description  Extracts rows of a given record type (type, file) from run-record.tsv, stripping
##               the leading type column.
## @return       Writes matching rows to stdout in file order and exits 0, even if file is absent.
pb_record_rows() {
  [ -f "${2:-}" ] || return 0
  awk -F '\t' -v t="${1:-}" '$1 == t { print substr($0, length(t) + 2) }' "$2"
}

# Because px-run logs an initial disrupt row when recording the plan and appends updated rows as
# state transitions occur, the last row for each ordinal reflects its final state.
## @description  Extracts the final disrupt row for each disruption ordinal from run-record.tsv
##               (file), omitting the leading record type column.
## @return       Writes the last row per ordinal in first-seen order to stdout and exits 0.
pb_disrupt_rows() {
  pb_record_rows disrupt "${1:-}" | awk -F '\t' '{ last[$1] = $0
    if (!($1 in seen)) { seen[$1] = 1; order[++n] = $1 } }
    END { for (i = 1; i <= n; i++) print last[order[i]] }'
}

## @description  Extracts a named column (file, column name) from a headered TSV export file.
## @return       Writes column values to stdout, one per row, and exits 0 even if file is absent.
pb_export_column() {
  [ -f "${1:-}" ] || return 0
  awk -F '\t' -v name="${2:-}" '
    NR == 1 { for (i = 1; i <= NF; i++) if ($i == name) col = i; next }
    col { print $col }' "$1"
}

## @description  Looks up the last value assigned to a key (key, file) in a KEY=VALUE env file.
## @return       Writes the value (or nothing) to stdout and exits 0, even if the file is absent.
pb_env_value() {
  [ -f "${2:-}" ] || return 0
  awk -v k="${1:-}" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { print v }' \
    "$2"
}

## @description  Checks whether a bundle artifact path (bundle, path) is present as an existing
##               regular file or, when given a trailing slash, a non-empty directory.
## @return       Exits 0 if the artifact is present, or 1 otherwise.
pb_present() {
  local path="${1:-}/${2:-}" entry
  case "$path" in
  */)
    [ -d "$path" ] || return 1
    for entry in "$path"* "$path".[!.]*; do
      [ -e "$entry" ] && return 0
    done
    return 1
    ;;
  esac
  [ -f "$path" ]
}

## @description  Reads the driver name for a bundle (bundle) from the context rows of
##               run-record.tsv.
## @return       Writes the driver name (or nothing if unset) to stdout and exits 0.
pb_bundle_driver() {
  pb_record_rows context "${1:-}/run-record.tsv" |
    awk -F '\t' '$1 == "driver" { d = $2 } END { print d }'
}

## @description  Formats an artifact inventory row (bundle, path, required yes|no, reason), marking
##               it present, missing when required, or skipped with the supplied reason.
## @return       Writes a single tab-separated inventory row to stdout and exits 0.
pb_artifact_row() {
  if pb_present "$1" "$2"; then
    px_tsv_row "$2" present "$3" ""
  elif [ "$3" = yes ]; then
    px_tsv_row "$2" missing yes "required by $4"
  else
    px_tsv_row "$2" skipped no "$4"
  fi
}

## @description  Builds the artifact inventory for a bundle (bundle): path, status (present,
##               missing, skipped, or unavailable), required (yes or no), and reason.
## @return       Writes tab-separated artifact inventory rows to stdout and exits 0.
pb_artifact_rows() {
  local dir=${1:-} path driver scope verb declared
  for path in $PB_REQUIRED; do
    pb_artifact_row "$dir" "$path" yes "every bundle"
  done
  scope="$(pb_record_rows run "$dir/run-record.tsv" | awk -F '\t' '{ s = $4 } END { print s }')"
  if [ -n "$scope" ]; then
    for path in $PB_REQUIRED_CLUSTER; do
      pb_artifact_row "$dir" "$path" yes "a run with a cluster"
    done
    # Vanished instances leave no readable container logs, so daemon logs are only required when at
    # least one cluster instance remains after the run completes.
    if pb_record_rows instance "$dir/run-record.tsv" | awk -F '\t' '$1 == "post" { f = 1 }
      END { exit !f }'; then
      pb_artifact_row "$dir" "$PB_REQUIRED_DAEMON" yes "a run with an instance after the run"
    else
      pb_artifact_row "$dir" "$PB_REQUIRED_DAEMON" no "no instance after the run"
    fi
  fi
  driver="$(pb_bundle_driver "$dir")"
  if [ -n "$driver" ]; then
    pb_artifact_row "$dir" "drivers/$driver/describe.json" yes "a run with a driver"
  fi
  while IFS= read -r verb; do
    [ -n "$verb" ] || continue
    pb_artifact_row "$dir" "logs/$verb.log" yes "the verb $verb"
  done <<EOF
$(pb_record_rows verb "$dir/run-record.tsv" | cut -f1 | awk '!seen[$0]++')
EOF
  # When a driver or plug-in explicitly declares one of the standard optional paths, emit only the
  # declaration row so its specific reason is preserved without duplicating the path.
  declared="$(pb_declared_rows "$dir")"
  for path in $PB_OPTIONAL; do
    case "$PX_NL$declared" in
    *"$PX_NL$path$PX_TAB"*) continue ;;
    esac
    pb_artifact_row "$dir" "$path" no absent
  done
  [ -z "$declared" ] || printf '%s\n' "$declared"
  # Emit an inventory row for every file listed in the collector index. Files recorded under an ok
  # status are marked required so that any missing collector artifact fails bundle validation.
  while IFS="$PB_US" read -r path status name target; do
    [ -n "$path" ] || continue
    if [ "$status" = ok ]; then
      pb_artifact_row "$dir" "$path" yes "collector $name of $target"
    else
      pb_artifact_row "$dir" "$path" no "collector $name of $target: $status"
    fi
  done <<EOF
$(awk -F '\t' -v us="$PB_US" '$4 != "" { print $4 us $3 us $1 us $2 }' \
    "$dir/metrics/index.tsv" 2>/dev/null || true)
EOF
}

## @description  Enumerates optional artifacts declared by the bundle driver or enabled plug-ins
##               (bundle) as pb_artifact_rows entries with status present, skipped, or unavailable.
## @return       Writes tab-separated artifact inventory rows to stdout and exits 0.
pb_declared_rows() {
  local dir=${1:-} path driver reason unavailable="" declared="" id file
  driver="$(pb_bundle_driver "$dir")"
  # Any artifact path declared unsupported by the driver takes precedence over other declarations
  # and is emitted once as unavailable along with the driver's stated reason.
  if [ -n "$driver" ] && pb_describe_load "$dir/drivers/$driver/describe.json" 2>/dev/null; then
    unavailable="$(pb_describe_rows unsupported-artifacts)"
    declared="$(pb_describe_rows artifacts)"
  fi
  for path in $declared; do
    case "$PX_NL$unavailable" in
    *"$PX_NL$path$PX_TAB"*) continue ;;
    esac
    pb_artifact_row "$dir" "$path" no "declared by $driver, not written"
  done
  while IFS="$PB_US" read -r path reason; do
    [ -n "$path" ] || continue
    px_tsv_row "$path" unavailable no "$(px_tsv_clean "$reason")"
  done <<EOF
$(printf '%s\n' "$unavailable" | tr '\t' "$PB_US")
EOF
  # The plugins context row in run-record.tsv lists all enabled plug-ins. Each plug-in enumerates
  # its expected output files, relative to plugins/<id>/, in lines following the header of its
  # manifest.tsv file.
  for id in $(pb_record_rows context "$dir/run-record.tsv" |
    awk -F '\t' '$1 == "plugins" { p = $2 } END { print p }'); do
    path="plugins/$id/manifest.tsv"
    pb_artifact_row "$dir" "$path" no "plug-in $id wrote no manifest.tsv"
    [ -f "$dir/$path" ] || continue
    while IFS="$PX_TAB" read -r file _; do
      case "$file" in
      '' | /* | *..*) continue ;;
      esac
      pb_artifact_row "$dir" "plugins/$id/$file" no "$path lists it, the plug-in wrote none"
    done <<EOF
$(awk 'NR > 1' "$dir/$path")
EOF
  done
}

# ---------------------------------------------------------------------------
# Bundle manifest generation
# ---------------------------------------------------------------------------
# px-bundle write invokes pb_manifest_write once per run after px-report completes. Because the
# manifest is synthesized purely from the underlying bundle files and never modified manually, it
# can always be deterministically regenerated. The "manifest.json" section of
# docs/bundle-format.md defines its schema.

## @description  Emits a JSON numeric member (key, value), or a JSON null literal if the value does
##               not match the numeric grammar.
## @return       Exits 0.
pb_json_num_or_null() {
  if [[ ${2:-} =~ $PX_RE_NUMBER ]]; then
    px_json_num "$1" "$2"
  else
    px_json_lit "$1" null
  fi
}

## @description  Converts an integer duration in milliseconds to seconds formatted with three
##               decimal places.
## @return       Writes the formatted seconds to stdout (or nothing if not an integer) and exits 0.
pb_ms_to_s() {
  [[ ${1:-} =~ $PX_RE_INT ]] || return 0
  printf '%d.%03d\n' $((10#$1 / 1000)) $((10#$1 % 1000))
}

## @description  Atomically generates manifest.json for a bundle (bundle) via a temporary file so
##               readers never observe a partially written manifest.
## @return       Exits 0 on success, 1 if writing fails, or 6 if the bundle directory is absent.
pb_manifest_write() {
  local dir=${1:-} tmp
  [ -d "$dir" ] || return "$PX_EXIT_NO_OBJECT"
  tmp="$dir/.manifest.json.tmp"
  pb_manifest_json "$dir" >"$tmp" || return "$PX_EXIT_FAIL"
  mv -f "$tmp" "$dir/manifest.json" || return "$PX_EXIT_FAIL"
}

## @description  Serializes the complete manifest.json document for a bundle (bundle) to stdout.
## @return       Exits 0.
pb_manifest_json() {
  local dir=$1 rec="$1/run-record.tsv" run id class backend scope context started result
  run="$(pb_record_rows run "$rec" | tail -n 1 | tr '\t' "$PB_US")"
  IFS="$PB_US" read -r id class backend scope _ <<EOF
$run
EOF
  context="$(pb_record_rows context "$rec")"
  started="$(awk -F '\t' '$1 == "stage" { print $3; exit }' "$rec" 2>/dev/null || true)"
  result="$(pb_export_column "$dir/report/run-summary.tsv" result | tail -n 1)"
  px_json_open
  px_json_num bundle_format_version "$PB_FORMAT_VERSION"
  px_json_open run
  px_json_str id "${id:-${dir##*/}}"
  px_json_str class "${class:-}"
  px_json_str backend "${backend:-}"
  px_json_str scope "${scope:-}"
  px_json_str driver "$(px_tsv_get driver "$context" || true)"
  px_json_str suite "$(px_tsv_get suite "$context" || true)"
  px_json_str test "$(px_tsv_get test "$context" || true)"
  px_json_str session "$(px_tsv_get session "$context" || true)"
  pb_json_num_or_null started_ms "$started"
  px_json_num ended_ms "$(px_epoch_ms)"
  px_json_str result "$result"
  px_json_close
  pb_manifest_versions "$dir"
  pb_manifest_images "$dir"
  pb_manifest_tables "$dir"
  pb_manifest_quiet "$dir"
  pb_manifest_anomaly "$dir"
  pb_manifest_stages "$dir"
  pb_manifest_disruptions "$dir"
  pb_manifest_artifacts "$dir"
  # Only a Kubernetes run includes this member so that a Compose manifest retains its exact bytes.
  [ "$backend" != k8s ] || pb_manifest_kubernetes "$dir"
  px_json_close
}

# The mesh member records the Istio injector identified by the mesh_injector ok preflight row from
# cluster bring-up; runs without that row omit the mesh member.
## @description  Emits the kubernetes member for a bundle (bundle), recording the API server
##               version, the mesh injector, pod node placement, and node-resolved image digests.
## @return       Exits 0.
pb_manifest_kubernetes() {
  local rec="$1/run-record.tsv" role index pod node ref digest mesh
  px_json_open kubernetes
  px_json_str api_version "$(pb_record_rows version "$rec" |
    awk -F '\t' '$1 == "pre" && $2 == "api_server" { v = $3 } END { print v }')"
  mesh="$(pb_record_rows preflight "$rec" | awk -F '\t' '$1 == "mesh_injector" && $2 == "ok" &&
    match($3, /[^ ]*\.sidecar-injector\.istio\.io/) { v = substr($3, RSTART, RLENGTH) }
    END { print v }')"
  [ -z "$mesh" ] || px_json_str mesh "$mesh"
  px_json_arr placement
  while IFS="$PB_US" read -r role index pod node; do
    [ -n "$role" ] || continue
    px_json_open
    px_json_str role "$role"
    pb_json_num_or_null index "$index"
    px_json_str pod "$pod"
    px_json_str node "$node"
    px_json_close
  done <<EOF
$(pb_record_rows instance "$rec" | awk -F '\t' -v us="$PB_US" '$1 == "pre" {
    print $2 us $3 us $4 us $8 }')
EOF
  px_json_close
  px_json_arr registry_digests
  while IFS="$PB_US" read -r ref digest; do
    [ -n "$ref" ] || continue
    px_json_open
    px_json_str image_ref "$ref"
    px_json_str digest "$digest"
    px_json_close
  done <<EOF
$(awk -F '\t' -v us="$PB_US" 'NR > 1 && $3 != "" {
    d = $4; if (match(d, /sha256:[0-9a-f]+$/)) d = substr(d, RSTART); else d = ""
    if (!seen[$3 us d]++) print $3 us d }' "$1/cluster/images.tsv" 2>/dev/null || true)
EOF
  px_json_close
  px_json_close
}

## @description  Emits the manifest versions object for a bundle (bundle), combining component
##               versions from cluster/topology.env with per-role JDK versions.
## @return       Exits 0.
pb_manifest_versions() {
  local topo="$1/cluster/topology.env" role jdk
  px_json_open versions
  px_json_str phoenix_hbase_profile "$(pb_env_value PX_PHOENIX_HBASE_PROFILE "$topo")"
  px_json_str hbase "$(pb_env_value PX_DIST_HBASE_VERSION "$topo")"
  px_json_str hadoop "$(pb_env_value PX_DIST_HADOOP_VERSION "$topo")"
  px_json_str zookeeper "$(pb_env_value PX_ZOOKEEPER_VERSION "$topo")"
  px_json_str hbase_hadoop_client "$(pb_env_value PX_HBASE_HADOOP_VERSION "$topo")"
  px_json_open jdk
  # cluster/services.tsv begins with a comment line documenting its nine columns:
  # service role index group start mem_limit_mib heap_mib direct_mib jdk.
  while IFS="$PX_TAB" read -r role jdk; do
    [ -n "$role" ] || continue
    px_json_str "$role" "$jdk"
  done <<EOF
$(awk -F '\t' '!/^#/ && NF >= 9 && !seen[$2]++ { print $2 "\t" $9 }' \
    "$1/cluster/services.tsv" 2>/dev/null || true)
EOF
  px_json_close
  px_json_close
}

## @description  Emits the manifest images array for a bundle (bundle) from cluster/images.tsv.
## @return       Exits 0.
pb_manifest_images() {
  local role index ref image
  px_json_arr images
  while IFS="$PB_US" read -r role index ref image; do
    [ -n "$role" ] || continue
    px_json_open
    px_json_str role "$role"
    pb_json_num_or_null index "$index"
    px_json_str image_ref "$ref"
    px_json_str image_id "$image"
    px_json_close
  done <<EOF
$(awk 'NR > 1' "$1/cluster/images.tsv" 2>/dev/null | tr '\t' "$PB_US" || true)
EOF
  px_json_close
}

## @description  Emits the manifest tables array for a bundle (bundle) from tables.tsv.
## @return       Exits 0.
pb_manifest_tables() {
  local schema table value
  px_json_arr tables
  while IFS="$PB_US" read -r schema table value; do
    [ -n "$table" ] || continue
    px_json_open
    px_json_str schema "$schema"
    px_json_str table "$table"
    px_json_str update_cache_frequency "$value"
    px_json_close
  done <<EOF
$(awk 'NR > 1' "$1/tables.tsv" 2>/dev/null | tr '\t' "$PB_US" || true)
EOF
  px_json_close
}

## @description  Emits the quiet_host and breach manifest members for a bundle (bundle), recording
##               the quiet-host check result, unique contamination tags, and any breach.tsv path.
## @return       Exits 0.
pb_manifest_quiet() {
  local rec="$1/run-record.tsv" state tag
  state="$(pb_record_rows quiet "$rec" |
    awk -F '\t' '$1 == "quiet_host" { s = $2 } END { print s }')"
  px_json_open quiet_host
  px_json_str state "${state:-unknown}"
  px_json_arr tags
  while IFS= read -r tag; do
    [ -n "$tag" ] || continue
    px_json_str "$tag"
  done <<EOF
$(pb_record_rows contamination "$rec" | cut -f1 | awk '!seen[$0]++')
EOF
  px_json_close
  px_json_close
  if [ -f "$1/breach.tsv" ]; then
    px_json_str breach breach.tsv
  else
    px_json_lit breach null
  fi
}

# Summarizes unplanned instance state changes from run-record.tsv: emits abort_ms on the first line
# followed by one PB_US-delimited row per affected instance (role, index, change, before, after,
# reason, exit_code, and oom).
## @description  Extracts unplanned instance change records and abort timestamp from run-record.tsv
##               (file).
## @return       Writes PB_US-separated rows to stdout and exits 0, even if the file is absent.
pb_anomaly_rows() {
  [ -f "${1:-}" ] || return 0
  # px-run raises its anomaly flag on this exact subset of records, ensuring that the manifest
  # anomaly member is null if and only if the run result row reports no anomaly.
  awk -F '\t' -v us="$PB_US" '
    function add(k, r, i) {
      if (!(k in seen)) { seen[k] = 1; order[++n] = k; role[k] = r; idx[k] = i }
    }
    $1 == "abort" {
      k = $3 SUBSEP $4; add(k, $3, $4)
      if (!(k in ach)) ach[k] = $5
      if (abort == "") abort = $2
    }
    $1 == "diff" && ($6 == "unplanned" || $6 == "vanished") {
      k = $2 SUBSEP $3; add(k, $2, $3); before[k] = $4; after[k] = $5
      if ($6 == "vanished") c = "vanished"
      else {
        c = ""
        if ($4 ~ /^[0-9]+$/ && $5 ~ /^[0-9]+$/ && $5 + 0 > $4 + 0) c = "restarted " $4 " to " $5
        if ($7 == "running" && ($8 == "exited" || $8 == "dead" || $8 == "unknown"))
          c = c (c == "" ? "" : " and ") "went from running to " $8
        if (c == "") c = $6
      }
      dch[k] = c
    }
    $1 == "term" { reason[$2 SUBSEP $3] = $4; code[$2 SUBSEP $3] = $5 }
    $1 == "oom" { oom[$2 SUBSEP $3] = $4 }
    END {
      if (n == 0) exit
      print abort
      for (j = 1; j <= n; j++) {
        k = order[j]
        print role[k] us idx[k] us ((k in ach) ? ach[k] : dch[k]) us before[k] us after[k] \
          us reason[k] us code[k] us oom[k]
      }
    }' "$1"
}

# Emits the anomaly member as null when no unplanned changes occurred, or as an object recording
# whether the run aborted, the abort timestamp, and each affected instance's restart counts and
# termination evidence.
## @description  Emits the manifest anomaly member for a bundle (bundle).
## @return       Exits 0.
pb_manifest_anomaly() {
  local rows abort role index change before after reason code oom
  rows="$(pb_anomaly_rows "$1/run-record.tsv")"
  if [ -z "$rows" ]; then
    px_json_lit anomaly null
    return 0
  fi
  abort=${rows%%"$PX_NL"*}
  px_json_open anomaly
  if [ -n "$abort" ]; then
    px_json_lit aborted true
  else
    px_json_lit aborted false
  fi
  pb_json_num_or_null abort_ms "$abort"
  px_json_arr instances
  while IFS="$PB_US" read -r role index change before after reason code oom; do
    [ -n "$role" ] || continue
    px_json_open
    px_json_str role "$role"
    pb_json_num_or_null index "$index"
    px_json_str change "$change"
    pb_json_num_or_null restarts_before "$before"
    pb_json_num_or_null restarts_after "$after"
    px_json_str termination_reason "$reason"
    pb_json_num_or_null exit_code "$code"
    case "$oom" in
    yes) px_json_lit oom true ;;
    no) px_json_lit oom false ;;
    *) px_json_lit oom null ;;
    esac
    px_json_close
  done <<EOF
${rows#*"$PX_NL"}
EOF
  px_json_close
  px_json_close
}

## @description  Emits the stages and steady manifest arrays for a bundle (bundle), pairing each
##               stage anchor with its window and each steady@ anchor with the five steady metrics.
## @return       Exits 0.
pb_manifest_stages() {
  local pairs anchor start end source key row
  pairs="$(pb_stage_pairs "$1/stages.tsv" | tr '\t' "$PB_US")"
  px_json_arr stages
  while IFS="$PB_US" read -r anchor start end source; do
    [ -n "$anchor" ] || continue
    px_json_open
    px_json_str anchor "$anchor"
    px_json_str source "$source"
    pb_json_num_or_null start_ms "$start"
    pb_json_num_or_null end_ms "$end"
    px_json_close
  done <<EOF
$pairs
EOF
  px_json_close
  px_json_arr steady
  while IFS="$PB_US" read -r anchor start end source; do
    case "$anchor" in steady@*) ;; *) continue ;; esac
    px_json_open
    px_json_str anchor "$anchor"
    pb_json_num_or_null start_ms "$start"
    pb_json_num_or_null end_ms "$end"
    px_json_open metrics
    for key in $PB_STEADY_KEYS; do
      # Select the final aggregate record for this metric key and anchor (value, start_ms, end_ms).
      row="$(awk -F '\t' -v a="$anchor" -v k="$key" \
        'NR > 1 && $4 == a && $5 == k { r = $7 "\t" $1 "\t" $2 } END { print r }' \
        "$1/metrics/aggregate.tsv" 2>/dev/null || true)"
      if [ -z "$row" ]; then
        px_json_lit "$key" null
        continue
      fi
      px_json_open "$key"
      pb_json_num_or_null value "$(px_tsv_field 1 "$row")"
      pb_json_num_or_null start_ms "$(px_tsv_field 2 "$row")"
      pb_json_num_or_null end_ms "$(px_tsv_field 3 "$row")"
      px_json_close
    done
    px_json_close
    px_json_close
  done <<EOF
$pairs
EOF
  px_json_close
}

## @description  Emits the manifest disruptions array for a bundle (bundle), recording each
##               scheduled disruption's final state, injection and recovery timestamps, and MTTR.
## @return       Exits 0.
pb_manifest_disruptions() {
  local rec="$1/run-record.tsv" ordinal kind when action role index argument state recover
  local injected recovered gate
  px_json_arr disruptions
  while IFS="$PB_US" read -r ordinal kind when action role index argument state; do
    [ -n "$ordinal" ] || continue
    recover="$(pb_record_rows recover "$rec" |
      awk -F '\t' -v o="$ordinal" '$1 == o { r = $2 "\t" $3 "\t" $4 } END { print r }')"
    injected="$(px_tsv_field 1 "$recover" || true)"
    recovered="$(px_tsv_field 2 "$recover" || true)"
    gate="$(px_tsv_field 3 "$recover" || true)"
    px_json_open
    pb_json_num_or_null ordinal "$ordinal"
    px_json_str action "$action"
    px_json_str role "$role"
    pb_json_num_or_null index "$index"
    px_json_str argument "$argument"
    if [ "$kind" = offset ]; then
      px_json_str when "+$when"
    else
      px_json_str when "phase:$when"
    fi
    px_json_str state "$state"
    px_json_str recovery "${gate:-none}"
    pb_json_num_or_null injected_ms "$injected"
    pb_json_num_or_null recovered_ms "$recovered"
    if [[ $injected =~ $PX_RE_INT ]] && [[ $recovered =~ $PX_RE_INT ]] &&
      [ "$recovered" -ge "$injected" ]; then
      px_json_num mttr_seconds "$(pb_ms_to_s $((recovered - injected)))"
    else
      px_json_lit mttr_seconds null
    fi
    px_json_close
  done <<EOF
$(pb_disrupt_rows "$rec" | tr '\t' "$PB_US")
EOF
  px_json_close
}

## @description  Emits the manifest artifacts array for a bundle (bundle) from the inventory rows
##               produced by pb_artifact_rows.
## @return       Exits 0.
pb_manifest_artifacts() {
  local path status required reason
  px_json_arr artifacts
  while IFS="$PB_US" read -r path status required reason; do
    [ -n "$path" ] || continue
    px_json_open
    px_json_str path "$path"
    px_json_str status "$status"
    if [ "$required" = yes ]; then
      px_json_lit required true
    else
      px_json_lit required false
    fi
    px_json_str reason "$reason"
    px_json_close
  done <<EOF
$(pb_artifact_rows "$1" | tr '\t' "$PB_US")
EOF
  px_json_close
}
