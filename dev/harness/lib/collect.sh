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

# Collector kit library sourced exclusively by bin/px-collect. This module implements the session
# collectors (profile, jfr, and trace), their ck_start and ck_stop dispatchers, and the common
# lifecycle primitives shared across all collectors, while the periodic samplers (hiccup, host, and
# container) and the CLI entry point reside in bin/px-collect. The artifact layout and schemas
# produced by these collectors are defined in the "The collectors" section of docs/bundle-format.md.
#
# Five core design invariants govern this library:
#   1. Collectors interact with cluster instances exclusively through sb_exec. Remote artifact
#      files are retrieved over the standard output of cat, avoiding the need for a dedicated file
#      transfer function in the substrate contract. Each remote file is removed only after its
#      bundle copy is in place, preserving the file on the target as evidence if retrieval fails.
#   2. Target instances and the JDK path for each target are resolved from cluster/services.tsv
#      within the bundle so that collectors always inspect the exact deployment topology used by
#      the run. Across all collectors, the target specification all expands to every service role
#      except setup and the pherf group.
#   3. Remote collection scripts exit with status 0, 1, 5 when the required tool is absent, or 6
#      when the instance is not running a JVM, and never return an sb_exec framing code. All
#      diagnostic output from remote collection scripts is appended to logs/collect.log inside the
#      bundle.
#   4. Each target receives at most one entry in the collector index, and the collector.<name>
#      verdict is derived directly from those rows. Because telemetry loss is reported as
#      ADVISORY, a collector never fails a run.
#   5. Each collector executes at most once per bundle. Every start is recorded in
#      collect/started.tsv with the collector, targets, pid, duration_s, and start_ms columns. A
#      collector remains live from its started row until the collector itself writes the end row
#      for collector@<name> with source collector:<name>. That end row acts as the commit point of
#      the stop operation and is written only after the index rows and verdict, ensuring that a
#      second stop invocation never writes duplicate records.

# Reject direct execution as an operator error because this file is a library that must be sourced.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/collect.sh is a library. Source it after lib/common.sh.\n' >&2
  exit 2
fi

# Guard against multiple inclusion so sourcing the library a second time does not redefine its
# symbols.
if [ -n "${CK_LOADED:-}" ]; then
  return 0
fi
CK_LOADED=1

CK_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=SCRIPTDIR/bundle.sh
. "$CK_LIB_DIR/bundle.sh"
# shellcheck source=SCRIPTDIR/backend/contract.sh
. "$CK_LIB_DIR/backend/contract.sh"

# Preamble prepended to every remote script executed by ck_jvm_exec to locate the service daemon,
# which is always the oldest java process running inside the target instance.
CK_JVM_PID="pid=\"\$(pgrep -o -x java)\" || exit 6"

# Remote command script that streams a collected artifact file to standard output.
CK_FETCH_SCRIPT="cat -- \"\$1\""

# Remote command script that removes a copied artifact file (file, directory...) and then prunes
# each listed directory that is empty. Any directory that still contains other files, such as the
# driver working directory on client 0 or an artifact from another collector, is left intact.
CK_DELETE_SCRIPT="rm -f -- \"\$1\" || exit 1; shift; rmdir -- \"\$@\" 2>/dev/null || true"

# Remote scripts for the session collectors. Each script accepts the target instance's JDK name,
# the remote recording file path, the collection duration bound in seconds, and any collector-
# specific arguments. The profile collector runs async-profiler to capture cpu and alloc events in
# a single session at a 7919 us sampling interval with no skipped frames. The jfr collector uses
# the target JDK's jcmd binary to run Java Flight Recorder with settings=profile, skipping any
# target whose JDK installation does not provide jcmd.
CK_PROFILE_START="$(
  cat <<'SCRIPT_EOF'
file=$2
seconds=$3
shift 3
asprof="$PX_TOOLS_DIR/async-profiler/bin/asprof"
[ -x "$asprof" ] || exit 5
mkdir -p "${file%/*}" || exit 1
"$asprof" start "$@" --timeout "$seconds" -o jfr -f "$file" "$pid" || exit 1
SCRIPT_EOF
)"
CK_PROFILE_STOP="$(
  cat <<'SCRIPT_EOF'
asprof="$PX_TOOLS_DIR/async-profiler/bin/asprof"
[ -x "$asprof" ] || exit 5
"$asprof" stop -o jfr -f "$2" "$pid" || exit 1
SCRIPT_EOF
)"
CK_JFR_START="$(
  cat <<'SCRIPT_EOF'
jcmd="$PX_JDK_ROOT/$1/bin/jcmd"
[ -x "$jcmd" ] || exit 5
mkdir -p "${2%/*}" || exit 1
"$jcmd" -J-Xmx16m "$pid" JFR.start "name=$4" settings=profile "duration=${3}s" \
  "filename=$2" || exit 1
SCRIPT_EOF
)"
CK_JFR_STOP="$(
  cat <<'SCRIPT_EOF'
jcmd="$PX_JDK_ROOT/$1/bin/jcmd"
[ -x "$jcmd" ] || exit 5
"$jcmd" -J-Xmx16m "$pid" JFR.stop "name=$4" "filename=$2" && [ -s "$2" ] || exit 1
SCRIPT_EOF
)"

# Per-collection context variables initialized by ck_bind and ck_scope, together with the target
# list and duration bound configured for the collector being started or stopped.
CK_SCOPE=""
CK_RUN_ID=""
CK_REMOTE=""
CK_TARGETS=""
CK_DURATION=""

# Must be invoked directly in the current shell rather than inside a subshell or command
# substitution so that exported and global variables persist. The remote staging path is scoped by
# the collection directory's run ID so that concurrent or separate collections never collide on
# remote paths.
## @description  Binds a collection directory (directory) as PX_RUN_DIR and initializes its
##               required subdirectories.
## @return       Exits 0 on success, or exits 1 if any subdirectory cannot be created.
ck_bind() {
  PX_RUN_DIR=$1
  CK_RUN_ID=${1##*/}
  CK_REMOTE="/tmp/px/$CK_RUN_ID/collect"
  export PX_RUN_DIR
  mkdir -p "$PX_RUN_DIR/logs" "$PX_RUN_DIR/metrics" "$PX_RUN_DIR/collect" ||
    px_die "$PX_EXIT_FAIL" "cannot create the parts of the collection $PX_RUN_DIR"
}

## @description  Loads the active backend and binds the cluster scope identifier into CK_SCOPE.
## @return       Exits 0 on success, exits 6 if no cluster scope is recorded, or exits 2 or 1 as
##               propagated from sb_scope_require.
ck_scope() {
  sb_load
  CK_SCOPE="$(sb_scope_require)" || exit "$?"
}

# Selects target instances from cluster/services.tsv matching a comma-separated role list or all
# (the PX_COLLECT_TARGETS default), where all includes every service role except setup and the
# pherf group.
## @description  Lists the target instances from the bundle's cluster/services.tsv.
## @return       Writes TSV rows to stdout and exits 0, or exits 6 when the bundle has no
##               services.tsv.
ck_targets() {
  local list=${1:-${PX_COLLECT_TARGETS:-all}} file="$PX_RUN_DIR/cluster/services.tsv"
  [ -f "$file" ] || px_die "$PX_EXIT_NO_OBJECT" "the bundle holds no cluster/services.tsv"
  awk -F '\t' -v list=",$list," '!/^#/ && NF >= 9 &&
    ((list == ",all," && $2 != "setup" && $4 != "pherf") || index(list, "," $2 ",")) {
      print $2 "\t" $3 "\t" $9 }' "$file"
}

# Executes a remote script for (role, index, script, argument...) inside the target instance via
# sb_exec and appends the script's standard output to logs/collect.log within the bundle.
## @description  Runs a remote collection script in a target instance through sb_exec.
## @return       Returns the sb_exec exit status: the script status, a framing code from 124 to
##               127, or 6.
ck_exec() {
  local role=$1 index=$2 script=$3
  shift 3
  # Because sb_exec forwards its standard input to the remote instance while callers iterate over
  # target rows on standard input, redirect standard input from /dev/null so that the remote call
  # does not consume the remaining rows.
  sb_exec "$CK_SCOPE" "$role" "$index" "" -- bash -c "$script" px-collect "$@" </dev/null \
    >>"$PX_RUN_DIR/logs/collect.log"
}

## @description  Runs a remote script for (role, index, script, argument...) like ck_exec after
##               resolving the daemon JVM process ID into $pid.
## @return       Returns the sb_exec exit status, or 6 when the target instance is running no JVM.
ck_jvm_exec() {
  local role=$1 index=$2 script=$3
  shift 3
  ck_exec "$role" "$index" "$CK_JVM_PID$PX_NL$script" "$@"
}

# Streams a remote file identified by (role, index, <collector>/<file> below CK_REMOTE, path in the
# bundle) over cat into a local temporary file and atomically moves it into its destination. Only
# after the local move succeeds does it delete the remote file and prune any empty parent
# directories up to /tmp/px/<run-id>. A deletion failure does not affect the return status because
# the artifact is already stored in the bundle.
## @description  Transfers a remote artifact file into the bundle and deletes the remote file.
## @return       Returns 0 on success, the non-zero status of sb_exec, or 1 when the local file
##               cannot be written.
ck_fetch() {
  local status=0 file="$PX_RUN_DIR/$4"
  mkdir -p "${file%/*}" || return "$PX_EXIT_FAIL"
  sb_exec "$CK_SCOPE" "$1" "$2" "" -- bash -c "$CK_FETCH_SCRIPT" px-fetch "$CK_REMOTE/$3" \
    </dev/null >"$file.tmp" || status=$?
  if [ "$status" = 0 ]; then
    mv -f "$file.tmp" "$file" || status=$PX_EXIT_FAIL
  fi
  rm -f "$file.tmp"
  if [ "$status" = 0 ]; then
    ck_exec "$1" "$2" "$CK_DELETE_SCRIPT" "$CK_REMOTE/$3" "$CK_REMOTE/${3%/*}" "$CK_REMOTE" \
      "${CK_REMOTE%/*}" || true
  fi
  return "$status"
}

# Maps a remote execution status for (collector, target, status, file, what) to a collector index
# row: status 0 records ok, status 6 or 125 records skip, status 5 records skip for jfr and fail
# for other collectors, and any other status records fail.
## @description  Records the collector index row for a single target based on its execution status.
## @return       Returns 0 for ok or skip rows, or 1 when a fail row is recorded.
ck_outcome() {
  local name=$1 target=$2 status=$3 file=$4 what=$5
  case "$status:$name" in
  0:*) pb_collector "$name" "$target" ok "$file" "$what" ;;
  6:*) pb_collector "$name" "$target" skip "" "no JVM or no such instance in $target" ;;
  125:*) pb_collector "$name" "$target" skip "" "the instance $target is not running" ;;
  5:jfr) pb_collector "$name" "$target" skip "" "the tool is absent in $target" ;;
  5:*)
    pb_collector "$name" "$target" fail "$file" "the tool is absent in $target"
    return "$PX_EXIT_FAIL"
    ;;
  *)
    pb_collector "$name" "$target" fail "$file" "$what in $target ended with exit $status"
    return "$PX_EXIT_FAIL"
    ;;
  esac
}

## @description  Checks whether the collector index already contains an entry for (collector,
##               target).
## @return       Returns 0 when a matching row exists, or 1 otherwise.
ck_done() {
  awk -F '\t' -v c="$1" -v t="$2" '$1 == c && $2 == t { f = 1 } END { exit !f }' \
    "$PX_RUN_DIR/metrics/index.tsv" 2>/dev/null
}

## @description  Determines whether the specified collector (bundle, collector) has a recorded start
##               entry in collect/started.tsv within the bundle.
## @return       Returns 0 if a started entry exists, or 1 otherwise.
ck_has_started() {
  awk -F '\t' -v c="$2" '$1 == c { f = 1 } END { exit !f }' "$1/collect/started.tsv" \
    2>/dev/null
}

## @description  Determines whether a collector (bundle, collector) has opened its collection window
##               by recording a begin event for collector@<name> in stages.tsv.
## @return       Returns 0 if the begin event is present, or 1 otherwise.
ck_begun() {
  awk -F '\t' -v a="collector@$2" '$2 == "begin" && $3 == a { f = 1 } END { exit !f }' \
    "$1/stages.tsv" 2>/dev/null
}

## @description  Determines whether a collector (bundle, collector) was ever initiated in the
##               bundle, as evidenced by either a started row or a stage window begin row.
## @return       Returns 0 if the collector started or opened its stage window, or 1 otherwise.
ck_ran() { ck_has_started "$1" "$2" || ck_begun "$1" "$2"; }

## @description  Determines whether a collector (bundle, collector) is currently live, meaning it
##               has a started row but has not yet written its own collector@<name> stage end row.
## @return       Returns 0 if the collector is still active, or 1 otherwise.
ck_live() {
  ck_has_started "$1" "$2" || return 1
  ! awk -F '\t' -v a="collector@$2" -v s="collector:$2" \
    '$2 == "end" && $3 == a && $4 == s { f = 1 } END { exit !f }' "$1/stages.tsv" 2>/dev/null
}

## @description  Extracts a specific column (collector, field number) from the collector's entry in
##               collect/started.tsv within the currently bound bundle.
## @return       Writes the field value to stdout (or nothing if no entry exists) and exits 0.
ck_started() {
  awk -F '\t' -v c="$1" -v f="$2" '$1 == c { v = $f } END { print v }' \
    "$PX_RUN_DIR/collect/started.tsv" 2>/dev/null || true
}

# Summarizes the index rows for a collector (name) into the collector.<name> verdict: records
# ADVISORY when any fail row exists, PASS when at least one ok row exists without failures, or SKIP
# with the first skip reason otherwise.
## @description  Computes and records the collector.<name> verdict from the collector index rows.
## @return       Returns 0 when no target failed, or 1 when at least one fail row exists.
ck_verdict() {
  local name=$1 ok skip fail first reason
  IFS="$PB_US" read -r ok skip fail first reason <<EOF
$(awk -F '\t' -v c="$name" -v us="$PB_US" '$1 == c { n[$3]++
    if ($3 == "fail" && f == "") f = $2 ": " $5
    if ($3 == "skip" && s == "") s = $2 ": " $5 }
  END { print n["ok"] + 0 us n["skip"] + 0 us n["fail"] + 0 us f us s }' \
    "$PX_RUN_DIR/metrics/index.tsv" 2>/dev/null || true)
EOF
  if [ "${fail:-0}" -gt 0 ]; then
    sx_verdict "collector.$name" ADVISORY "$fail of $((ok + skip + fail)) targets failed," \
      "the first $first"
    return "$PX_EXIT_FAIL"
  fi
  if [ "${ok:-0}" -gt 0 ]; then
    sx_verdict "collector.$name" PASS "$ok artifacts, see metrics/index.tsv"
  else
    sx_verdict "collector.$name" SKIP "nothing to collect, ${reason:-no target}"
  fi
}

# Launches a remote collection session for (collector, script, label, argument...) across all
# selected target instances, supplying each target's JDK path, remote recording path, CK_DURATION,
# and collector arguments to the remote script before recording the stage begin event.
## @description  Starts a remote collection session on each selected target and opens the stage.
## @return       Returns 0 on success, returns 1 when any target fails, or exits 6 when the bundle
##               has no services.tsv.
ck_session_start() {
  local name=$1 script=$2 label=$3 rows role index jdk status failed=0
  shift 3
  rows="$(ck_targets "$CK_TARGETS")" || exit "$?"
  while IFS="$PX_TAB" read -r role index jdk; do
    [ -n "$role" ] || continue
    status=0
    ck_jvm_exec "$role" "$index" "$script" "$jdk" "$CK_REMOTE/$name/$role-$index.jfr" \
      "$CK_DURATION" "$@" || status=$?
    [ "$status" = 0 ] || ck_outcome "$name" "$role-$index" "$status" "" "the $name start" ||
      failed=1
  done <<EOF
$rows
EOF
  pb_stage begin "collector@$name" "$label"
  return "$failed"
}

# Stops each active collection session for (collector, bundle dir, script, argument...) using the
# same target arguments as ck_session_start, fetches each recording to <dir>/<target>.jfr, writes
# the collector index rows and the overall collector verdict, and finally closes the stage window.
## @description  Stops open target sessions, retrieves the recordings, and records the verdict.
## @return       Returns 0 on success, returns 1 when any target fails, or exits 6 when the bundle
##               has no services.tsv.
ck_session_stop() {
  local name=$1 dir=$2 script=$3 rows role index jdk status fetch verdict=0
  shift 3
  rows="$(ck_targets "$CK_TARGETS")" || exit "$?"
  while IFS="$PX_TAB" read -r role index jdk; do
    [ -n "$role" ] || continue
    ck_done "$name" "$role-$index" && continue
    status=0
    ck_jvm_exec "$role" "$index" "$script" "$jdk" "$CK_REMOTE/$name/$role-$index.jfr" \
      "$CK_DURATION" "$@" || status=$?
    # A session that terminated early or whose host JVM restarted will fail the stop command yet
    # may still have produced a recording on disk; fetch any partial file while still recording the
    # target outcome as a failure.
    fetch=0
    ck_fetch "$role" "$index" "$name/$role-$index.jfr" "$dir/$role-$index.jfr" || fetch=$?
    [ "$status" != 0 ] || status=$fetch
    ck_outcome "$name" "$role-$index" "$status" "$dir/$role-$index.jfr" "the $name stop" || true
  done <<EOF
$rows
EOF
  ck_verdict "$name" || verdict=$?
  pb_stage end "collector@$name"
  return "$verdict"
}

# ---------------------------------------------------------------------------
# Trace collector
# ---------------------------------------------------------------------------
# Both the start and stop phases of the trace collector capture a cluster-wide snapshot of Phoenix
# counters across every RegionServer by executing the px-trace-counters image reader inside the
# client 0 instance. During the stop phase, the collector joins the initial and final snapshots
# into traces/counters.tsv to compute the delta for each counter over the run; these deltas are
# valid only for RegionServers whose JVM process persisted without restarting throughout the run.
# If client 0 lacks the px-trace-counters binary, the collector records a skip row in the collector
# index and allows the run to proceed. Together with jfr, trace is one of the two collectors that
# degrade gracefully to SKIP when their underlying diagnostic tool is unavailable.

## @description  Captures a Phoenix counter snapshot (which: start or stop) to traces/<which>.tsv
##               and records its index row, or logs a cluster skip row if the reader is absent.
## @return       Returns 0 on a successful or skipped snapshot, or 1 if the snapshot command fails.
ck_trace_snapshot() {
  local which=$1 file="traces/$1.tsv" status=0
  mkdir -p "$PX_RUN_DIR/traces" || return "$PX_EXIT_FAIL"
  printf 'role\tindex\tbean\tcounter\tvalue\n' >"$PX_RUN_DIR/$file.tmp"
  sb_exec "$CK_SCOPE" client 0 "" -- px-trace-counters </dev/null \
    >>"$PX_RUN_DIR/$file.tmp" || status=$?
  if [ "$status" = 0 ]; then
    mv -f "$PX_RUN_DIR/$file.tmp" "$PX_RUN_DIR/$file"
    pb_collector trace "$which" ok "$file" "$(($(wc -l <"$PX_RUN_DIR/$file") - 1)) counters"
    return 0
  fi
  rm -f "$PX_RUN_DIR/$file.tmp"
  case "$status" in
  127)
    pb_collector trace cluster skip "" "client 0 has no px-trace-counters"
    return 0
    ;;
  6 | 125)
    pb_collector trace cluster skip "" "client 0 is absent or not running, exit $status"
    return 0
    ;;
  esac
  pb_collector trace "$which" fail "$file" "px-trace-counters in client 0 ended with exit $status"
  return "$PX_EXIT_FAIL"
}

## @description  Captures the baseline counter snapshot and opens the collector@trace stage window.
## @return       Returns 0 on success, or 1 if capturing the baseline snapshot fails.
ck_trace_start() {
  local status=0
  ck_trace_snapshot start || status=$?
  pb_stage begin collector@trace "Phoenix counters of every RegionServer"
  return "$status"
}

## @description  Captures the final counter snapshot, joins the start and stop snapshots into
##               traces/counters.tsv, records the trace verdict, and closes the stage window.
## @return       Returns 0 on success, or 1 if the snapshot or verdict check reports a failure.
ck_trace_stop() {
  local status=0 file=traces/counters.tsv
  if ! ck_done trace cluster && ! ck_done trace stop &&
    [ -f "$PX_RUN_DIR/traces/start.tsv" ]; then
    ck_trace_snapshot stop || status=$?
  fi
  if [ "$status" = 0 ] && ! ck_done trace counters && [ -f "$PX_RUN_DIR/traces/stop.tsv" ]; then
    awk -F '\t' -v OFS='\t' 'FNR == 1 { next }
      NR == FNR { first[$1 OFS $2 OFS $3 OFS $4] = $5; next }
      { k = $1 OFS $2 OFS $3 OFS $4
        if (k in first) print k, first[k], $5, $5 - first[k]; else print k, "", $5, "" }' \
      "$PX_RUN_DIR/traces/start.tsv" "$PX_RUN_DIR/traces/stop.tsv" >"$PX_RUN_DIR/$file.tmp"
    { printf 'role\tindex\tbean\tcounter\tstart\tstop\tdelta\n'; cat "$PX_RUN_DIR/$file.tmp"; } \
      >"$PX_RUN_DIR/$file"
    rm -f "$PX_RUN_DIR/$file.tmp"
    pb_collector trace counters ok "$file" "the change of each counter over the run"
  fi
  ck_verdict trace || status=$?
  pb_stage end collector@trace
  return "$status"
}

# ---------------------------------------------------------------------------
# Session collector dispatch
# ---------------------------------------------------------------------------

# Callers must configure CK_TARGETS and CK_DURATION before invoking the dispatcher. Every emitted
# record is tagged with the collector's PX_RECORD_SOURCE so that its stage end row serves as the
# stop commit point required by invariant 5.
## @description  Starts a session collector (name: profile, jfr, or trace) in the bound bundle.
## @return       Returns 0 on success, returns 1 if any target fails, or exits 6 when services.tsv
##               is absent.
ck_start() {
  PX_RECORD_SOURCE="collector:$1"
  export PX_RECORD_SOURCE
  case "$1" in
  profile)
    ck_session_start profile "$CK_PROFILE_START" "async-profiler cpu and alloc" -e cpu,alloc \
      -i 7919us
    ;;
  jfr)
    ck_session_start jfr "$CK_JFR_START" "Java Flight Recorder, settings profile" "px-$CK_RUN_ID"
    ;;
  trace) ck_trace_start ;;
  esac
}

## @description  Stops a session collector (name: profile, jfr, or trace) in the bound bundle.
## @return       Returns 0 on success, returns 1 if any target fails, or exits 6 when services.tsv
##               is absent.
ck_stop() {
  PX_RECORD_SOURCE="collector:$1"
  export PX_RECORD_SOURCE
  case "$1" in
  profile) ck_session_stop profile profiles "$CK_PROFILE_STOP" ;;
  jfr) ck_session_stop jfr jfr "$CK_JFR_STOP" "px-$CK_RUN_ID" ;;
  trace) ck_trace_stop ;;
  esac
}
