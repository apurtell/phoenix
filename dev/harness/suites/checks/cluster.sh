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

# This script implements the built-in cluster health checks, which suites/check-cluster.sh invokes
# once per run after merging the active threshold configuration. Each of the seven check subjects
# emits a single verdict row keyed as cluster.<subject> with the source identifier check:cluster:
#
#   wrapper        Evaluates driver verbs that px-run stopped at a time limit or that a signal
#                  terminated.
#   restart        Tracks cluster instances that restarted, exited, or vanished without a planned
#                  action.
#   oom            Audits out-of-memory kills and compares peak memory usage against the configured
#                  limit.
#   log            Scans the instance logs of the run for ERROR, FATAL, and WARN lines.
#   gc             Measures the longest garbage-collection pause across each JVM instance.
#   recovery       Evaluates the mttr of each disruption against the two-minute recovery gate.
#   post_recovery  Verifies instance readiness and runs the bootstrap probe after a disruption.
#
# Four architectural rules govern this implementation:
#   1. All evidence is gathered exclusively from the run bundle and the substrate contract. Because
#      this file references no container runtime directly, it runs unchanged on every backend.
#   2. Every threshold is loaded dynamically from PX_THRESHOLDS_FILE through sx_threshold, and the
#      file contains no hardcoded threshold literals.
#   3. Any subject that lacks sufficient evidence records a SKIP verdict explaining the reason, and
#      no subject ever prevents subsequent subjects from running.
#   4. No check spawns a program inside a daemon instance, because starting an auxiliary JVM next
#      to a tightly sized daemon can push it over its memory limit. Checks inspect daemon state
#      strictly through sb_logs, sb_copy_out, and the instance inventory, while any active helper
#      programs execute in client 0.
#
# The authoritative specification is in docs/bundle-format.md, section "The cluster checks".

CK_HOME="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck source=SCRIPTDIR/../../lib/bundle.sh
. "$CK_HOME/lib/bundle.sh"
# shellcheck source=SCRIPTDIR/../../lib/backend/contract.sh
. "$CK_HOME/lib/backend/contract.sh"

set -euo pipefail
px_trap_enable

CK_RUN=""
CK_REC=""
CK_START_MS=""
# Holds the scope inventory as one row per instance containing role, index, state, and ready.
CK_INV=""
# Remains empty when ck_inventory reads the inventory, or records the reason why it could not.
CK_INV_GAP=""
CK_VALUE=""

px_cli_spec <<'EOF'
about Runs the seven built-in cluster subjects against the run that PX_RUN_DIR
+ names, with the thresholds of PX_THRESHOLDS_FILE. suites/check-cluster.sh calls
+ it; a direct call needs both names.
exits 2 also means an unset PX_RUN_DIR or PX_THRESHOLDS_FILE.
EOF

## @description  Resolves one threshold (subject, key) into CK_VALUE, or records an ERROR row for
##               the subject when that key is absent.
## @return       Returns exit 0 when the key is present, or exit 1 after recording the ERROR row.
ck_threshold() {
  CK_VALUE="$(sx_threshold "$2" || exit "$?")" && return 0
  sx_verdict "cluster.$1" ERROR "the threshold $2 is absent from $PX_THRESHOLDS_FILE"
  return "$PX_EXIT_FAIL"
}

## @description  Evaluates whether a numeric value (value, limit) strictly exceeds the given limit.
## @return       Returns exit 0 when the value is above the limit, or exit 1 otherwise.
ck_over() { awk -v v="$1" -v m="$2" 'BEGIN { exit !(v + 0 > m + 0) }'; }

## @description  Records a SKIP row for a subject requiring a live cluster when none is available.
## @return       Returns exit 0 when the inventory is available, or exit 1 after the SKIP row.
ck_live() {
  [ -n "$CK_INV_GAP" ] || return 0
  sx_verdict "cluster.$1" SKIP "$CK_INV_GAP"
  return "$PX_EXIT_FAIL"
}

## @description  Loads the instance inventory of the active scope into CK_INV, or records the
##               reason it is unavailable in CK_INV_GAP.
## @return       Returns exit 0.
ck_inventory() {
  local rows status=0 role index state ready
  if [ -z "${PX_SCOPE_ID:-}" ]; then
    CK_INV_GAP="no cluster: the run names no scope"
    return 0
  fi
  sb_load "${PX_BACKEND:-compose}"
  rows="$(sb_list "$PX_SCOPE_ID" || exit "$?")" || status=$?
  if [ "$status" != 0 ]; then
    CK_INV_GAP="the inventory of scope $PX_SCOPE_ID cannot be read: exit $status"
    return 0
  fi
  role="$(sb_schema_index instance role)"
  index="$(sb_schema_index instance index)"
  state="$(sb_schema_index instance state)"
  ready="$(sb_schema_index instance ready)"
  CK_INV="$(printf '%s\n' "$rows" | awk -F '\t' -v r="$role" -v i="$index" -v s="$state" \
    -v y="$ready" 'NF { print $r "\t" $i "\t" $s "\t" $y }')"
  [ -n "$CK_INV" ] || CK_INV_GAP="scope $PX_SCOPE_ID holds no instance"
}

## @description  Evaluates the wrapper subject by counting verb rows terminated by a time limit or
##               a signal.
## @return       Returns exit 0.
ck_wrapper() {
  local rows result count total list
  rows="$(pb_record_rows verb "$CK_REC")"
  if [ -z "$rows" ]; then
    sx_verdict cluster.wrapper SKIP "no verb row: no driver verb ran"
    return 0
  fi
  ck_threshold wrapper wrapper.terminations.max || return 0
  # Each verb record row contains the columns verb, exit_code, elapsed_ms, and timed_out.
  result="$(printf '%s\n' "$rows" | awk -F '\t' 'NF {
      total++
      if ($4 == 1 || $2 + 0 > 128) {
        n++
        list = list " " $1 ":" $2 ($4 == 1 ? ":timed-out" : "")
      }
    } END { printf "%d\t%d\t%s\n", n, total, list }')"
  count="$(px_tsv_field 1 "$result")"
  total="$(px_tsv_field 2 "$result")"
  list="$(px_tsv_field 3 "$result")"
  if ck_over "$count" "$CK_VALUE"; then
    sx_verdict cluster.wrapper FAIL "$count of $total verbs ended by a time limit or a" \
      "signal:$list; wrapper.terminations.max=$CK_VALUE"
  else
    sx_verdict cluster.wrapper PASS "$count of $total verbs ended by a time limit or a" \
      "signal; wrapper.terminations.max=$CK_VALUE"
  fi
}

## @description  Evaluates the restart subject from abort rows and diff rows marked unplanned or
##               vanished.
## @return       Returns exit 0.
ck_restart() {
  local diffs aborts result count list known unknown sev
  diffs="$(pb_record_rows diff "$CK_REC")"
  aborts="$(pb_record_rows abort "$CK_REC")"
  # Each diff row contains role, index, before, after, verdict, pre_state, and post_state. An
  # unknown verdict indicates that px-run could not read the post-run inventory, so that row does
  # not constitute evidence.
  result="$(printf '%s\n' "$diffs" | awk -F '\t' '$5 == "unknown" { u++ }
    $5 != "" && $5 != "unknown" { k++ } END { printf "%d\t%d\n", k, u }')"
  known="$(px_tsv_field 1 "$result")"
  unknown="$(px_tsv_field 2 "$result")"
  if [ -z "$aborts" ] && [ "$known" = 0 ]; then
    sx_verdict_row cluster.restart SKIP count "no abort row and no readable diff row: px-run" \
      "recorded no post-run snapshot, or $unknown instances were unreadable"
    return 0
  fi
  ck_threshold restart restart.unplanned.max || return 0
  # Each affected instance is counted only once regardless of how many rows reference it. The
  # pb_anomaly_rows helper aggregates each anomalous instance along with its state change, restart
  # counts, termination reason, and out-of-memory evidence recorded by px-run.
  result="$(pb_anomaly_rows "$CK_REC" | awk -F "$PB_US" 'NR > 1 {
      n++
      list = list " " $1 "/" $2 " (" $3 "; restarts " ($4 == "" ? "?" : $4) " to " \
        ($5 == "" ? "?" : $5) "; reason " ($6 == "" ? "unknown" : $6) \
        ($7 == "" ? "" : ", exit " $7) "; oom " ($8 == "" ? "unknown" : $8) ")"
    } END { printf "%d\t%s\n", n, list }')"
  count="$(px_tsv_field 1 "$result")"
  list="$(px_tsv_field 2 "$result")"
  [ -z "$list" ] || list=":$list"
  [ "$unknown" = 0 ] || list="$list; $unknown instances unreadable after the run"
  sev=PASS
  ck_over "$count" "$CK_VALUE" && sev=ERROR
  sx_verdict_row cluster.restart "$sev" count "$count instances changed with no planned" \
    "action$list; restart.unplanned.max=$CK_VALUE"
}

# Evaluates the oom subject by combining recorded oom rows with the live out-of-memory status of
# each instance to count kills, and by analyzing sample rows to determine the peak memory usage of
# each role relative to its limit.
## @description  Evaluates the oom subject for out-of-memory kills and role peak memory usage.
## @return       Returns exit 0.
ck_oom() {
  local kills rows samples role index state ready out status column c_role c_index c_mem
  local c_limit peaks count kill_list peak_who peak_pct kill_max peak_max
  rows="$(pb_record_rows oom "$CK_REC")"
  # Each oom row in the run record consists of the role, index, oom, and evidence fields.
  kills="$(printf '%s\n' "$rows" | awk -F '\t' '$3 == "yes" { print $1 "/" $2 }')"
  if [ -z "$CK_INV_GAP" ]; then
    column="$(sb_schema_index oom oom)"
    while IFS="$PX_TAB" read -r role index state ready; do
      [ -n "$role" ] || continue
      status=0
      out="$(sb_oom_killed "$PX_SCOPE_ID" "$role" "$index" || exit "$?")" || status=$?
      [ "$status" = 0 ] || continue
      if [ "$(px_tsv_field "$column" "$out" || true)" = yes ]; then
        kills="$kills$PX_NL$role/$index"
      fi
    done <<EOF
$CK_INV
EOF
  fi
  samples="$(pb_record_rows sample "$CK_REC")"
  if [ -z "$rows" ] && [ -n "$CK_INV_GAP" ] && [ -z "$samples" ]; then
    sx_verdict_row cluster.oom SKIP count "no oom row, no sample row and $CK_INV_GAP"
    return 0
  fi
  ck_threshold oom oom.kills.max || return 0
  kill_max=$CK_VALUE
  ck_threshold oom oom.peak_pct.max || return 0
  peak_max=$CK_VALUE
  kills="$(printf '%s\n' "$kills" | awk 'NF && !($0 in s) { s[$0] = 1; n++; l = l " " $0 }
    END { printf "%d\t%s\n", n, l }')"
  count="$(px_tsv_field 1 "$kills")"
  kill_list="$(px_tsv_field 2 "$kills")"
  # Each sample row in the run record prepends the collection phase to the standard sample schema.
  # The peak for a role is defined as the highest percentage of its memory limit reached by any of
  # its instances in a single sample, whereas samples without a configured limit produce no peak.
  c_role=$(($(sb_schema_index sample role) + 1))
  c_index=$(($(sb_schema_index sample index) + 1))
  c_mem=$(($(sb_schema_index sample mem_bytes) + 1))
  c_limit=$(($(sb_schema_index sample mem_limit_bytes) + 1))
  peaks="$(printf '%s\n' "$samples" | awk -F '\t' -v r="$c_role" -v i="$c_index" \
    -v m="$c_mem" -v l="$c_limit" '$l + 0 > 0 && $m != "" {
      pct = 100 * $m / $l
      if (!($r in best)) order[++n] = $r
      if (!($r in best) || pct > best[$r]) {
        best[$r] = pct; who[$r] = $r "/" $i; cap[$r] = $l / 1048576
      }
    } END {
      top = -1
      for (k = 1; k <= n; k++) {
        x = order[k]
        list = list sprintf(" %s %.1f%% of %d MiB,", who[x], best[x], cap[x])
        if (best[x] > top) { top = best[x]; name = who[x] }
      }
      sub(/,$/, "", list)
      if (n) printf "%s\t%.1f\t%s\n", name, top, list
    }')"
  peak_who="$(px_tsv_field 1 "$peaks" || true)"
  peak_pct="$(px_tsv_field 2 "$peaks" || true)"
  peaks="$(px_tsv_field 3 "$peaks" || true)"
  [ -n "$peaks" ] || peaks=" no sample with a limit"
  # Continuous monitor samples collected during the run supplement the two boundary snapshots.
  case "$PX_NL$samples" in
  *"${PX_NL}monitor$PX_TAB"*) ;;
  *) peaks="$peaks, from the snapshots only" ;;
  esac
  if ck_over "$count" "$kill_max"; then
    sx_verdict_row cluster.oom ERROR count "$count out-of-memory kills:$kill_list;" \
      "peaks:$peaks; oom.kills.max=$kill_max"
  elif [ -n "$peak_pct" ] && ck_over "$peak_pct" "$peak_max"; then
    sx_verdict_row cluster.oom FAIL count "$peak_who peaked at $peak_pct% of its limit;" \
      "peaks:$peaks; oom.peak_pct.max=$peak_max"
  else
    sx_verdict_row cluster.oom PASS count "$count out-of-memory kills; peaks:$peaks;" \
      "oom.kills.max=$kill_max oom.peak_pct.max=$peak_max"
  fi
}

# Archives the log output generated by each instance since the run started under logs/instance/,
# and tallies ERROR, FATAL, and WARN lines while excluding instances targeted by a planned action
# from the counts.
## @description  Evaluates the log subject across instance logs stored under logs/instance/.
## @return       Returns exit 0, or exits 1 when logs/instance/ cannot be created.
ck_log() {
  local role index state ready since="-" file status counts errors=0 warnings=0 seen=0
  local error_list="" skipped="" gaps="" first="" e w line err_max warn_max
  ck_live log || return 0
  ck_threshold log log.errors.max || return 0
  err_max=$CK_VALUE
  ck_threshold log log.warnings.max || return 0
  warn_max=$CK_VALUE
  if [[ $CK_START_MS =~ $PX_RE_INT ]]; then
    since=$((($(px_epoch_ms) - CK_START_MS) / 1000 + 2))
  fi
  mkdir -p "$CK_RUN/logs/instance" || px_die "$PX_EXIT_FAIL" "cannot create $CK_RUN/logs/instance"
  while IFS="$PX_TAB" read -r role index state ready; do
    [ -n "$role" ] || continue
    file="$CK_RUN/logs/instance/$role-$index.log"
    status=0
    sb_logs "$PX_SCOPE_ID" "$role" "$index" "$since" >"$file" || status=$?
    if [ "$status" != 0 ]; then
      gaps="$gaps $role/$index:exit-$status"
      continue
    fi
    # The bundle still archives the log of an instance covered by a planned action, but its log
    # lines are excluded from the tally because a planned kill intentionally induces error output.
    if [[ $CK_START_MS =~ $PX_RE_INT ]] &&
      sb_planned_contains "$PX_SCOPE_ID" "$role" "$index" "$CK_START_MS"; then
      skipped="$skipped $role/$index"
      continue
    fi
    seen=$((seen + 1))
    # Both the log4j layouts and the container image scripts emit the severity level as a
    # standalone token within the first four whitespace-delimited fields, ensuring that stack
    # trace frames or log messages that merely quote a level name are not counted.
    counts="$(awk '{
        for (i = 1; i <= 4 && i <= NF; i++) {
          if ($i == "ERROR" || $i == "FATAL") { e++; if (f == "") f = $0; break }
          if ($i == "WARN") { w++; break }
        }
      } END { printf "%d\t%d\t%s\n", e, w, substr(f, 1, 160) }' "$file")"
    e="$(px_tsv_field 1 "$counts")"
    w="$(px_tsv_field 2 "$counts")"
    line="$(px_tsv_field 3 "$counts")"
    errors=$((errors + e))
    warnings=$((warnings + w))
    [ "$e" = 0 ] || error_list="$error_list $role/$index:$e"
    [ -n "$first" ] || first=$line
  done <<EOF
$CK_INV
EOF
  [ -z "$skipped" ] || skipped="; planned action, kept and not counted:$skipped"
  [ -z "$gaps" ] || gaps="; unreadable:$gaps"
  if [ "$seen" = 0 ]; then
    sx_verdict_row cluster.log SKIP count "no instance log was counted$skipped$gaps"
    return 0
  fi
  line="$errors ERROR or FATAL lines${error_list:+ (${error_list# })}, $warnings WARN lines"
  line="$line in $seen logs; log.errors.max=$err_max log.warnings.max=$warn_max$skipped$gaps"
  if ck_over "$errors" "$err_max"; then
    sx_verdict_row cluster.log FAIL count "$line; first: $first"
  elif ck_over "$warnings" "$warn_max"; then
    sx_verdict_row cluster.log ADVISORY count "$line"
  else
    sx_verdict_row cluster.log PASS count "$line"
  fi
}

# Copies the unified GC log written by the image for each JVM instance into logs/gc/ using
# sb_copy_out, and identifies the longest garbage-collection pause across the cluster.
## @description  Evaluates the gc subject from the unified JVM GC logs copied into logs/gc/.
## @return       Returns exit 0, or exits 1 when logs/gc/ cannot be created and 2 on a bad
##               PX_TIMEOUT_VERB.
ck_gc() {
  local role index state ready bound logdir file status pause max="" who="" list="" found=0
  local absent="" sev
  ck_live gc || return 0
  ck_threshold gc gc.pause_ms.max || return 0
  bound="$(px_int_setting PX_TIMEOUT_VERB 300 1)" || exit $?
  # Because every role runs from the same container image, the log directory reported by client 0
  # applies to every instance in the cluster. Extracting logs from that path avoids spawning a
  # process inside any daemon instance and still retrieves logs from instances that have exited.
  status=0
  logdir="$(sb_exec "$PX_SCOPE_ID" client 0 "$bound" -- printenv PX_LOG_DIR </dev/null ||
    exit "$?")" || status=$?
  if [ "$status" != 0 ] || [ -z "$logdir" ]; then
    sx_verdict_row cluster.gc SKIP duration "client/0 reports no PX_LOG_DIR: exit $status"
    return 0
  fi
  mkdir -p "$CK_RUN/logs/gc" || px_die "$PX_EXIT_FAIL" "cannot create $CK_RUN/logs/gc"
  while IFS="$PX_TAB" read -r role index state ready; do
    [ -n "$role" ] || continue
    file="$CK_RUN/logs/gc/$role-$index.log"
    status=0
    sb_copy_out "$PX_SCOPE_ID" "$role" "$index" "$logdir/gc-$role-$index.log" "$file" \
      2>/dev/null || status=$?
    if [ "$status" != 0 ] || [ ! -f "$file" ]; then
      # Instances that do not run a JVM, such as setup, produce no GC log file.
      rm -f "$file"
      absent="$absent $role/$index"
      continue
    fi
    found=$((found + 1))
    # Each pause entry carries the gc tag, includes the word Pause, and places the duration in the
    # final field. The JDK pads the tag block on each line to match the widest tag combination it
    # has emitted so far, as in:
    # [..][info][gc       ] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 51M->12M(256M) 4.567ms
    pause="$(awk 'match($0, /\[gc *\]/) && index($0, " Pause ") && $NF ~ /^[0-9.]+ms$/ {
        v = substr($NF, 1, length($NF) - 2) + 0; if (v > m) m = v
      } END { printf "%.3f\n", m }' "$file")"
    list="$list $role/$index:${pause}ms"
    if [ -z "$max" ] || ck_over "$pause" "$max"; then
      max=$pause
      who="$role/$index"
    fi
  done <<EOF
$CK_INV
EOF
  [ -z "$absent" ] || absent="; no GC log:$absent"
  if [ "$found" = 0 ]; then
    sx_verdict_row cluster.gc SKIP duration "no instance holds $logdir/gc-<role>-<index>.log"
    return 0
  fi
  sev=PASS
  ck_over "$max" "$CK_VALUE" && sev=FAIL
  sx_verdict_row cluster.gc "$sev" duration "the longest pause is $max ms in $who;$list$absent;" \
    "gc.pause_ms.max=$CK_VALUE"
}

# Evaluates the recovery subject by checking each disruption mttr against recovery.seconds.max and
# flagging any recovery gate that did not pass, any failed injection, or any missed anchor. This
# check uses class none so its verdict is never demoted.
## @description  Evaluates the recovery subject across all scheduled disruptions and recovery gates.
## @return       Returns exit 0.
ck_recovery() {
  local disrupts recovers ordinal action role index argument state rec gate code
  local injected recovered mttr list="" bad=0 count=0
  disrupts="$(pb_disrupt_rows "$CK_REC")"
  recovers="$(pb_record_rows recover "$CK_REC")"
  if [ -z "$disrupts" ] && [ -z "$recovers" ]; then
    sx_verdict cluster.recovery SKIP "no disruption: the run scheduled none"
    return 0
  fi
  ck_threshold recovery recovery.seconds.max || return 0
  # Fields are delimited with the ASCII unit separator because standard IFS whitespace splitting
  # would collapse consecutive tabs and shift the column positions whenever an argument is empty.
  while IFS="$PB_US" read -r ordinal _ _ action role index argument state; do
    case "$state" in
    ok) count=$((count + 1)) ;;
    fail)
      # Each action row records ts_ms, action, role, index, argument, state, and exit_code for a
      # disruption that px-run attempted to inject.
      code="$(pb_record_rows action "$CK_REC" | awk -F '\t' -v a="$action" -v r="$role" \
        -v i="$index" -v g="$argument" '$2 == a && $3 == r && $4 == i && $5 == g &&
        $6 == "fail" { c = $7 } END { print c }')"
      count=$((count + 1))
      bad=$((bad + 1))
      list="$list; $ordinal $action $role/$index: injection failed, exit ${code:-unknown}"
      continue
      ;;
    missed)
      count=$((count + 1))
      bad=$((bad + 1))
      list="$list; $ordinal $action $role/$index: missed its anchor"
      continue
      ;;
    *) continue ;;
    esac
    # Each recover row contains the ordinal, injected_ms, recovered_ms, and state fields.
    rec="$(printf '%s\n' "$recovers" | awk -F '\t' -v o="$ordinal" '$1 == o { r = $0 }
      END { print r }')"
    gate="$(px_tsv_field 4 "$rec" || true)"
    injected="$(px_tsv_field 2 "$rec" || true)"
    recovered="$(px_tsv_field 3 "$rec" || true)"
    if [ -z "$rec" ]; then
      bad=$((bad + 1))
      list="$list; $ordinal $action $role/$index: no recover row"
    elif [ "$gate" != ok ] || ! [[ $injected =~ $PX_RE_INT ]] ||
      ! [[ $recovered =~ $PX_RE_INT ]]; then
      bad=$((bad + 1))
      list="$list; $ordinal $action $role/$index: the gate ended ${gate:-unknown}"
    else
      mttr="$(pb_ms_to_s $((recovered - injected)))"
      list="$list; $ordinal $action $role/$index: mttr $mttr s"
      if ck_over "$mttr" "$CK_VALUE"; then
        bad=$((bad + 1))
      fi
    fi
  done <<EOF
$(printf '%s\n' "$disrupts" | tr '\t' "$PB_US")
EOF
  if [ "$count" = 0 ]; then
    sx_verdict cluster.recovery SKIP "no disruption was injected"
  elif [ "$bad" != 0 ]; then
    sx_verdict cluster.recovery FAIL "$bad of $count disruptions failed, missed their" \
      "anchor or did not recover in time$list; recovery.seconds.max=$CK_VALUE"
  else
    sx_verdict cluster.recovery PASS "$count disruptions recovered$list;" \
      "recovery.seconds.max=$CK_VALUE"
  fi
}

# Evaluates the post_recovery subject after a disruption by verifying that every instance outside an
# intentionally stopped group is ready and that the bootstrap probe succeeds in client 0, passing
# --yarn whenever the YARN group is up.
## @description  Evaluates the post_recovery subject for cluster readiness and bootstrap health.
## @return       Returns exit 0.
ck_post_recovery() {
  local rows status=0 faults=0 list="" groups group state column yarn="" bound sev
  if [ -z "$(pb_record_rows recover "$CK_REC")" ]; then
    sx_verdict_row cluster.post_recovery SKIP count "no disruption was injected"
    return 0
  fi
  ck_live post_recovery || return 0
  ck_threshold post_recovery post_recovery.faults.max || return 0
  bound="$(px_int_setting PX_TIMEOUT_VERB 300 1)" || exit $?
  # Invoking the readiness barrier with a one-second timeout polls the cluster state once. The
  # backend reports any intentionally stopped group as stopped, while each unready instance counts
  # as one fault.
  rows="$(sb_wait_ready "$PX_SCOPE_ID" 1 || exit "$?")" || status=$?
  case "$status" in
  0 | "$PX_EXIT_ANOMALY" | "$PX_EXIT_TIMEOUT")
    rows="$(printf '%s\n' "$rows" | awk -F '\t' '$3 == "unready" { n++; l = l " " $1 "/" $2 }
      END { printf "%d\t%s\n", n, l }')"
    if [ "$(px_tsv_field 1 "$rows")" != 0 ]; then
      faults=$((faults + $(px_tsv_field 1 "$rows")))
      list="$list; not ready:$(px_tsv_field 2 "$rows")"
    elif [ "$status" != 0 ]; then
      faults=$((faults + 1))
      list="$list; the readiness barrier ended with exit $status"
    fi
    ;;
  *)
    faults=$((faults + 1))
    list="$list; the readiness cannot be read: exit $status"
    ;;
  esac
  groups="$(pb_env_value PX_GROUPS "$CK_RUN/cluster/topology.env")"
  column="$(sb_schema_index group state)"
  for group in $groups; do
    status=0
    state="$(sb_group_status "$PX_SCOPE_ID" "$group" || exit "$?")" || status=$?
    state="$(px_tsv_field "$column" "$state" || true)"
    if [ "$status" != 0 ] || [ "$state" = partial ]; then
      faults=$((faults + 1))
      list="$list; group $group is ${state:-unreadable}"
    elif [ "$state" = up ] && [ "$group" = yarn ]; then
      yarn=--yarn
    fi
  done
  status=0
  sb_exec "$PX_SCOPE_ID" client 0 "$bound" -- px-bootstrap-probe ${yarn:+"$yarn"} \
    </dev/null >&2 || status=$?
  if [ "$status" != 0 ]; then
    faults=$((faults + 1))
    list="$list; px-bootstrap-probe ${yarn:+$yarn }in client/0 ended with exit $status"
  else
    list="$list; px-bootstrap-probe ${yarn:+$yarn }in client/0 passed"
  fi
  sev=PASS
  ck_over "$faults" "$CK_VALUE" && sev=FAIL
  sx_verdict_row cluster.post_recovery "$sev" count "$faults faults after the recovery$list;" \
    "post_recovery.faults.max=$CK_VALUE"
}

px_cli_parse "$@"
# Because suites/check-cluster.sh exports its loaded configuration through the process environment,
# reload configuration files here only when the caller explicitly passes them on the command line.
[ "${#PX_CLI_FILES[@]}" = 0 ] || px_config_load -- "${PX_CLI_FILES[@]}"

[ -n "${PX_RUN_DIR:-}" ] ||
  px_die "$PX_EXIT_USAGE" "PX_RUN_DIR is unset: name the bundle of the run"
[ -n "${PX_THRESHOLDS_FILE:-}" ] ||
  px_die "$PX_EXIT_USAGE" "PX_THRESHOLDS_FILE is unset: suites/check-cluster.sh merges and names it"
CK_RUN="$(px_out_resolve "$PX_RUN_DIR")" || exit $?
[ -d "$CK_RUN" ] || px_die "$PX_EXIT_USAGE" "PX_RUN_DIR names no directory: $CK_RUN"
px_plan check wrapper restart oom log gc recovery post_recovery && exit "$PX_EXIT_OK"
CK_REC="$CK_RUN/run-record.tsv"
export PX_RUN_DIR="$CK_RUN" PX_RECORD_SOURCE=check:cluster
# As recorded in the run manifest, the run start timestamp corresponds to the first milestone
# emitted by px-run.
CK_START_MS="$(pb_record_rows stage "$CK_REC" | awk -F '\t' 'NR == 1 { print $2 }')"

ck_inventory
ck_wrapper
ck_restart
ck_oom
ck_log
ck_gc
ck_recovery
ck_post_recovery
exit 0
