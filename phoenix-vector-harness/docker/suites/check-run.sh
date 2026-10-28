#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script screens one harness run for gross deficits.
# It reads the run directory of the harness, the table records of the load and build phases,
# and the docker records of the wrapper. It does not need Docker, so it also works on a copy
# of the output tree.
# Each check prints one line: PASS, WARN or FAIL. A WARN line marks a result that needs a look,
# but does not fail the run. The thresholds come from thresholds.properties.
# On a noisy host, small latency and QPS values are not evidence. Thus, with host.noise=noisy, a
# check that uses only latency or QPS prints ADVISORY in place of FAIL or WARN. An ADVISORY line
# shows the value and the limit, but does not fail the run. With host.noise=quiet, these checks
# work as before. A SKIP line shows that the run records do not have the data for a check.

# shellcheck disable=SC2016 # The single-quoted arguments are awk and jq programs. The shell must not expand them.
set -euo pipefail

SUITES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib/common.sh
. "$SUITES_DIR/lib/common.sh"

PROG="$(basename "$0")"
THRESHOLDS="$SUITES_DIR/thresholds.properties"
FAILS=0
WARNS=0
ADVISORIES=0
SKIPS=0
HOST_NOISE=noisy

usage() {
  cat <<EOF
usage: $PROG [-t THRESHOLDS] RUN_DIR CHECK...

  RUN_DIR   out/<suite>/runs/<run-id> of a harness run
  CHECK     One of the checks below. A check with an argument takes the form name:argument.

  Run status   wrapper simd no-simd errors freshness[:validity]
  Recall       recall-exact recall-ivf-all recall-hnsw-ef256 recall:<sweep-regex>:<min>
  Index        fsck uncovered restriction probe-rise offheap rebuild-queue overflow statistics
  Resources    gc saturation client-bound tail warnings read-skew
  Disruption   mttr recall-stable errors-after-recovery recall-samples
  Lifecycle    drift-recovery[:<family-regex>] families uncovered-path no-rebuild-on-open
  Comparison   qps-vs-exact:<exact-run-id> simd-ab:<simd-run-id> published build-rate
               rows-vs-exact:<exact-run-id> rpcs-vs-exact:<exact-run-id>

Exit codes: 0 when no check fails, 1 when a check fails, 2 for a usage error.
EOF
}

die_usage() {
  printf '%s: ERROR: %s\n' "$PROG" "$*" >&2
  exit 2
}

pass() { printf 'PASS  %-22s %s\n' "$CHECK" "$*"; }
warn() {
  printf 'WARN  %-22s %s\n' "$CHECK" "$*"
  WARNS=$((WARNS + 1))
}
fail() {
  printf 'FAIL  %-22s %s\n' "$CHECK" "$*"
  FAILS=$((FAILS + 1))
}
advisory() {
  printf 'ADVISORY %-22s %s\n' "$CHECK" "$*"
  ADVISORIES=$((ADVISORIES + 1))
}
skip() {
  printf 'SKIP  %-22s %s\n' "$CHECK" "$*"
  SKIPS=$((SKIPS + 1))
}

noisy() { [ "$HOST_NOISE" = noisy ]; }

# These helpers report a verdict that uses only latency or QPS. On a noisy host, the verdict is
# advisory.
lat_fail() { if noisy; then advisory "$@"; else fail "$@"; fi; }
lat_warn() { if noisy; then advisory "$@"; else warn "$@"; fi; }

# Prints a threshold. A missing key is a usage error, because a check without its limit is useless.
th() {
  local v
  v="$(vhs_prop "$THRESHOLDS" "$1")"
  [ -n "$v" ] || die_usage "$THRESHOLDS has no value for $1"
  printf '%s' "$v"
}

# Runs an awk program on a CSV file of the harness. The program sees the columns by name in col[].
# The arguments after the program are awk variable assignments such as k=10.
qcsv() {
  local f="$1" p="$2"
  shift 2
  [ -f "$f" ] || return 0
  vhs_csv "$f" | awk -F '\t' "$VHS_HDR $p" "$@" -
}

# Prints a value of the run manifest with a jq filter. Options such as --arg go before the filter.
mf() { jq -r "$@" "$MANIFEST"; }

# Floating point comparison: cmp A OP B, for example cmp 0.98 '<' 0.99.
cmp() { awk -v a="$1" -v b="$3" "BEGIN { exit !(a + 0 $2 b + 0) }"; }

# The frontier rows of the recall family at the recall k, one line per row:
# layout, family, sweep, sweep_value, recall, p50_ms, throughput_qps, errors, queries.
frontier_rows() {
  local dir="${1:-$RUN_DIR}"
  qcsv "$dir/frontier.csv" '
    $col["family"] ~ fam && $col["k"] == k {
      print $col["layout"] "\t" $col["family"] "\t" $col["sweep"] "\t" $col["sweep_value"] "\t" \
        $col["recall"] "\t" $col["p50_ms"] "\t" $col["throughput_qps"] "\t" $col["errors"] "\t" \
        $col["queries"]
    }' fam="$FAM" k="$RK"
}

# Prints the total increment of a counter in metrics.csv or cluster.csv during the matching stages.
# A drop of the value is a counter reset after a restart, so the new value counts as the increment.
incr() {
  local file="$1" stage_re="$2" attr="$3"
  qcsv "$file" '
    $col["stage"] ~ st && $col["attribute"] == a {
      key = $col["server"]
      x = $col["value"] + 0
      if (key in last) sum += (x >= last[key]) ? x - last[key] : x
      last[key] = x
    }
    END { printf "%.0f\n", sum + 0 }' st="$stage_re" a="$attr"
}

# Prints the largest value of an attribute during the matching stages, or nothing without samples.
maxval() {
  local file="$1" stage_re="$2" attr="$3"
  qcsv "$file" '
    $col["stage"] ~ st && $col["attribute"] == a {
      x = $col["value"] + 0
      if (!seen || x > m) m = x
      seen = 1
    }
    END { if (seen) print m }' st="$stage_re" a="$attr"
}

# Prints the wall time in ms of the first event of a kind, or nothing.
first_event_ms() {
  qcsv "$RUN_DIR/events.csv" '$col["kind"] == kd { print $col["wall_ms"]; exit }' kd="$1"
}

# Prints the steady windows as lines of: stage, start ms, end ms.
steady_windows() {
  qcsv "$RUN_DIR/events.csv" '
    $col["kind"] == "stage" {
      if (open != "") { print open "\t" t0 "\t" $col["wall_ms"]; open = "" }
      if ($col["detail"] ~ /^steady@[0-9.]+$/) { open = $col["detail"]; t0 = $col["wall_ms"] }
    }
    END { if (open != "") print open "\t" t0 "\t" 9999999999999 }'
}

wenv() { vhs_prop "$WRAPPER_ENV" "$1"; }

# ---- Run status ----

chk_wrapper() {
  if [ ! -f "$WRAPPER_ENV" ]; then
    fail "no wrapper record in $DOCKER_DIR"
    return
  fi
  local rc an
  rc="$(wenv exit)"
  an="$(wenv anomalies)"
  if [ "$rc" != 0 ]; then
    fail "the harness exited with $rc"
  elif [ -n "$an" ]; then
    fail "container anomalies: $an"
  else
    pass "exit 0; restarts match the schedule ($(wenv expected_rs_restarts) region server restarts)"
  fi
}

chk_simd() {
  local v
  v="$(mf '.run.simd_vector_api')"
  if [ "$v" = true ]; then pass "the harness used the vector API"; else fail "run.simd_vector_api=$v"; fi
}

chk_no_simd() {
  local v
  v="$(mf '.run.simd_vector_api')"
  if [ "$v" = false ]; then pass "the harness ran without the vector API"; else fail "run.simd_vector_api=$v"; fi
}

# A failed commit loses a write that the workload planned, so the failed steady mutations count
# here with the query errors.
chk_errors() {
  local max mmax fe se me
  max="$(th query.errors.max)"
  mmax="$(th mutation.errors.max)"
  fe="$(qcsv "$RUN_DIR/frontier.csv" '{ s += $col["errors"] } END { print s + 0 }')"
  se="$(mf '[.stages.steady[]?.query_errors // 0] | add // 0')"
  me="$(mf '[.stages.steady[]?.mutation_errors // 0] | add // 0')"
  fe="${fe:-0}"
  if [ "$((fe + se))" -gt "$max" ] || [ "$me" -gt "$mmax" ]; then
    fail "query errors: frontier $fe, steady $se (max $max); failed steady mutations: $me (max $mmax)"
  else
    pass "query errors: frontier $fe, steady $se; failed steady mutations: $me"
  fi
}

# A freshness probe commits a row and then queries the index for it at once. The validity probes
# run before the workload, and the steady probes run in the steady stages. A failed probe is a
# failed commit or query. A freshness of NaN means that no probe gave an answer. With the argument
# validity, the check reads only the validity probes. Use this form for a run with disruptions,
# because a disruption can fail a steady probe. The full check also fails for a run with no steady
# stage, and for a steady stage in which no probe gave an answer.
chk_freshness() {
  case "$ARG" in
    '' | validity) ;;
    *) die_usage "freshness takes no argument or validity, not '$ARG'" ;;
  esac
  local vmin smin emax v ve low se n bad=""
  vmin="$(th validity.freshness.min)"
  smin="$(th steady.freshness.min)"
  emax="$(th freshness.errors.max)"
  if [ "$(mf 'has("validity")')" != true ]; then
    skip "the run has no validity record"
    return
  fi
  v="$(mf '.validity.freshness // "NaN"')"
  ve="$(mf '.validity.freshness_errors // 0')"
  if [ "$v" = NaN ]; then
    bad="validity freshness NaN, no probe gave an answer"
  elif cmp "$v" '<' "$vmin"; then
    bad="validity freshness $v (min $vmin)"
  fi
  if [ "$ve" -gt "$emax" ]; then
    bad="${bad:+$bad; }$ve validity probes failed (max $emax)"
  fi
  local msg="validity freshness $v, $ve probes failed"
  if [ -z "$ARG" ]; then
    local zero
    n="$(mf '[.stages.steady[]? | select((.freshness_probes // 0) > 0)] | length')"
    zero="$(mf '[.stages.steady[]? | select((.freshness_probes // 0) == 0) | .stage] | join(", ")')"
    low="$(mf --argjson m "$smin" '[.stages.steady[]? | select((.freshness_probes // 0) > 0 and .freshness < $m) | "\(.stage) \(.freshness)"] | join(", ")')"
    se="$(mf '[.stages.steady[]?.freshness_errors // 0] | add // 0')"
    if [ "$n" -eq 0 ] && [ -z "$zero" ]; then
      bad="${bad:+$bad; }the run has no steady stage"
    fi
    if [ -n "$zero" ]; then
      bad="${bad:+$bad; }no steady probe gave an answer in $zero"
    fi
    if [ -n "$low" ]; then
      bad="${bad:+$bad; }steady freshness below $smin: $low"
    fi
    if [ "$se" -gt "$emax" ]; then
      bad="${bad:+$bad; }$se steady probes failed (max $emax)"
    fi
    msg="$msg; steady freshness $smin or more in $n stages with probes, $se probes failed"
  fi
  if [ -n "$bad" ]; then
    fail "$bad"
  else
    pass "$msg"
  fi
}

# ---- Recall ----

recall_min() {
  local re="$1" min="$2" rows bad
  rows="$(frontier_rows | awk -F '\t' -v re="$re" '$3 ~ re')"
  if [ -z "$rows" ]; then
    fail "no frontier point matches $re for $FAM at k=$RK"
    return
  fi
  bad="$(printf '%s\n' "$rows" | awk -F '\t' -v m="$min" '$5 == "" || $5 + 0 < m + 0 { printf "%s %s %s recall=%s; ", $1, $2, $3, $5 }')"
  if [ -n "$bad" ]; then
    fail "below $min: $bad"
  else
    pass "$(printf '%s\n' "$rows" | awk -F '\t' '{ printf "%s %s=%.4f ", $1, $3, $5 }')(min $min)"
  fi
}

chk_recall_exact() { recall_min '^exact$' "$(th smoke.recall.exact.min)"; }
chk_recall_ivf_all() { recall_min '^probes=all\\(' "$(th smoke.recall.ivf_all.min)"; }
chk_recall_hnsw_ef256() { recall_min '^ef=256$' "$(th smoke.recall.hnsw_ef256.min)"; }

chk_recall() {
  local re="${ARG%:*}" min="${ARG##*:}"
  [ -n "$ARG" ] && [ "$re" != "$ARG" ] || die_usage "recall needs recall:<sweep-regex>:<min>"
  recall_min "$re" "$min"
}

# ---- Index health ----

chk_fsck() {
  local f n=0 bad=""
  for f in "$RUN_DIR"/fsck-*.json "$TABLE_DIR"/fsck-*.json; do
    [ -f "$f" ] || continue
    n=$((n + 1))
    if [ "$(jq -r '.errors' "$f")" != false ]; then
      bad="$bad $(basename "$f")"
    fi
  done
  if [ -n "$bad" ]; then
    fail "fsck reports with errors:$bad"
  elif [ "$n" = 0 ]; then
    if [ "$VARIANT" = hnsw ]; then fail "no fsck reports"; else warn "no fsck reports for $VARIANT"; fi
  else
    pass "$n fsck reports are clean"
  fi
}

chk_uncovered() {
  local max v t
  max="$(th hnsw.regions_uncovered.max)"
  v="$(mf '.build.build.metrics_end.values.vectorHnswRegionsUncovered // empty')"
  t="$(mf '.build.build.coverage_timed_out // false')"
  if [ -z "$v" ] && [ -f "$TABLE_DIR/build.json" ]; then
    v="$(jq -r '.build.metrics_end.values.vectorHnswRegionsUncovered // empty' "$TABLE_DIR/build.json")"
    t="$(jq -r '.build.coverage_timed_out // false' "$TABLE_DIR/build.json")"
  fi
  if [ "$t" = true ]; then
    fail "the build timed out before every HNSW region had a segment"
  elif [ -z "$v" ]; then
    warn "the build metrics have no vectorHnswRegionsUncovered value"
  elif cmp "$v" '>' "$max"; then
    fail "vectorHnswRegionsUncovered=$v after the build (max $max)"
  else
    pass "vectorHnswRegionsUncovered=$v after the build"
  fi
}

chk_restriction() {
  local max a v bad="" flat note=""
  max="$(th hnsw.restriction.max)"
  for a in vectorHnswRegionsUnrestricted vectorHnswRegionsUncovered vectorHnswRegionsOverflowed; do
    v="$(maxval "$RUN_DIR/metrics.csv" '^frontier$' "$a")"
    if [ -n "$v" ] && cmp "$v" '>' "$max"; then
      bad="$bad $a=$v"
    fi
  done
  for a in vectorHnswUnrestrictedUncovered vectorHnswUnrestrictedOverflow vectorHnswUnrestrictedLimit; do
    v="$(incr "$RUN_DIR/metrics.csv" '^frontier$' "$a")"
    [ "$v" = 0 ] || note="$note $a+$v"
  done
  # The latency at the largest ef over the latency at the smallest ef, for each layout.
  flat="$(frontier_rows | awk -F '\t' -v rise="$(th hnsw.ef_latency.min_rise)" '
    $3 ~ /^ef=[0-9]+$/ && $6 != "" {
      v = $4 + 0
      if (!($1 in lo) || v < lo[$1]) { lo[$1] = v; plo[$1] = $6 }
      if (!($1 in hi) || v > hi[$1]) { hi[$1] = v; phi[$1] = $6 }
    }
    END {
      for (l in lo) if (hi[l] > lo[l] && plo[l] > 0 && phi[l] / plo[l] < rise)
        printf "%s p50 %.2f ms at ef=%d, %.2f ms at ef=%d; ", l, plo[l], lo[l], phi[l], hi[l]
    }')"
  if [ -n "$bad" ]; then
    fail "during the frontier:$bad (max $max)"
  elif [ -n "$note" ] || { [ -n "$flat" ] && ! noisy; }; then
    warn "${flat:+latency stays flat as ef grows: $flat}${note:+unrestricted searches:$note}"
  elif [ -n "$flat" ]; then
    # The rise of the latency is the only signal, so a noisy host makes it advisory.
    advisory "latency stays flat as ef grows: $flat"
  else
    pass "no unrestricted, uncovered or overflowed region during the frontier"
  fi
}

# Recall at the probe points must rise with the probe count.
chk_probe_rise() {
  local tol sat re="" v rows out
  tol="$(th ivf.probe.recall.tolerance)"
  sat="$(th ivf.probe.recall.saturated)"
  for v in $(th ivf.probe.values | tr ',' ' '); do
    # awk -v removes one level of backslashes, so the regex carries two.
    if [ "$v" = all ]; then re="$re|"'^probes=all\\('; else re="$re|^probes=$v\$"; fi
  done
  re="${re#|}"
  rows="$(frontier_rows | awk -F '\t' -v re="$re" '$3 ~ re')"
  if [ "$(printf '%s\n' "$rows" | awk -F '\t' 'NF { n[$1]++ } END { m = 0; for (l in n) if (n[l] > m) m = n[l]; print m }')" -lt 2 ]; then
    # The profile does not sweep two of the named points, so the check uses every probe point.
    rows="$(frontier_rows | awk -F '\t' '$3 ~ /^probes=([0-9]+|all\()/')"
  fi
  if [ -z "$rows" ]; then
    fail "no probe points in the frontier"
    return
  fi
  out="$(printf '%s\n' "$rows" | sort -t "$(printf '\t')" -k1,1 -k4,4n |
    awk -F '\t' -v tol="$tol" -v sat="$sat" '
      $1 != layout {
        if (layout != "") finish()
        layout = $1; n = 0; lo = 2; hi = -1; drop = ""; path = ""
      }
      {
        r = $5 + 0
        if (n > 0 && r < prev - tol) drop = drop sprintf(" %s %.4f -> %s %.4f", pl, prev, $3, r)
        if (r < lo) lo = r
        if (r > hi) hi = r
        path = path sprintf(" %s=%.4f", $3, r)
        prev = r; pl = $3; n++
      }
      function finish() {
        if (n < 2) { print "WARN " layout ": one probe point only:" path; return }
        if (drop != "") { print "FAIL " layout ": recall falls with more probes:" drop; return }
        if (hi - lo < 1e-9 && lo < sat) { print "FAIL " layout ": identical recall at every probe point, a probe defect:" path; return }
        print "PASS " layout ":" path
      }
      END { if (layout != "") finish() }')"
  if printf '%s\n' "$out" | grep -q '^FAIL'; then
    fail "$(printf '%s\n' "$out" | grep -v '^PASS' | tr '\n' ';')"
  elif printf '%s\n' "$out" | grep -q '^WARN'; then
    warn "$(printf '%s\n' "$out" | tr '\n' ';')"
  else
    pass "$(printf '%s\n' "$out" | sed 's/^PASS //' | tr '\n' ';')"
  fi
}

chk_offheap() {
  local ref ev sl logs
  ref="$(incr "$RUN_DIR/metrics.csv" '.' vectorHnswOffheapRefusals)"
  ev="$(incr "$RUN_DIR/metrics.csv" '^steady@' vectorHnswOffheapEvictions)"
  sl="$(incr "$RUN_DIR/metrics.csv" '^steady@' vectorHnswSegmentLoads)"
  logs="$(cat "$DOCKER_DIR"/log-rs*.txt 2>/dev/null | grep -c 'MaxDirectMemorySize' || true)"
  if cmp "$ref" '>' "$(th hnsw.offheap.refusals.max)" || [ "${logs:-0}" -gt 0 ]; then
    fail "off-heap refusals $ref, direct memory warnings in the region server logs ${logs:-0}"
  elif cmp "$ev" '>' "$(th hnsw.offheap.evictions.steady.max)" ||
    cmp "$sl" '>' "$(th hnsw.segment_loads.steady.max)"; then
    warn "in steady state: off-heap evictions $ev, segment loads $sl"
  else
    pass "no refusals, no steady-state evictions or segment loads"
  fi
}

chk_rebuild_queue() {
  local q t ratio max
  max="$(th hnsw.rebuild.wait_over_time.max)"
  q="$(qcsv "$RUN_DIR/metrics.csv" '$col["attribute"] == "vectorHnswRebuildQueue" { last[$col["server"]] = $col["value"] + 0 }
    END { for (k in last) { s += last[k]; n++ } if (n) print s + 0 }')"
  t="$(mf '.build.build.coverage_timed_out // false')"
  ratio="$(qcsv "$RUN_DIR/metrics.csv" '
    $col["value"] + 0 > 0 && $col["attribute"] == "VectorHnswRebuildWaitTime_mean" { w += $col["value"]; nw++ }
    $col["value"] + 0 > 0 && $col["attribute"] == "VectorHnswRebuildTime_mean" { r += $col["value"]; nr++ }
    END { if (nw && nr && r > 0) printf "%.3f\n", (w / nw) / (r / nr) }')"
  if [ "$t" = true ]; then
    fail "the rebuild queue did not drain within the build timeout"
  elif [ -z "$q" ]; then
    warn "metrics.csv has no rebuild queue samples"
  elif cmp "$q" '>' 0; then
    fail "the rebuild queue holds $q regions at the end of the run"
  elif [ -n "$ratio" ] && cmp "$ratio" '>' "$max"; then
    warn "the queue wait dominates: mean wait over mean rebuild time is $ratio (max $max)"
  else
    pass "the rebuild queue drained${ratio:+; wait over rebuild time $ratio}"
  fi
}

chk_overflow() {
  local g e
  g="$(maxval "$RUN_DIR/metrics.csv" '.' vectorHnswRegionsOverflowed)"
  e="$(incr "$RUN_DIR/metrics.csv" '.' vectorHnswOverflowEntries)"
  pass "most overflowed regions ${g:-n/a}, overflow entries $e"
}

# ---- Resources ----

chk_gc() {
  local max out
  max="$(th gc.time.fraction.max)"
  out="$(qcsv "$RUN_DIR/cluster.csv" '
    $col["attribute"] == "GcTimeMillis" {
      s = $col["server"]; x = $col["value"] + 0; w = $col["wall_ms"] + 0
      if (s in last) gc[s] += (x >= last[s]) ? x - last[s] : x
      else t0[s] = w
      last[s] = x; t1[s] = w
    }
    END {
      for (s in gc) if (t1[s] > t0[s]) {
        f = gc[s] / (t1[s] - t0[s])
        if (f > worst) { worst = f; ws = s }
      }
      if (ws != "") printf "%.4f\t%s\n", worst, ws
    }')"
  if [ -z "$out" ]; then
    warn "cluster.csv has no GcTimeMillis samples"
  elif cmp "${out%%	*}" '>' "$max"; then
    fail "GC time is ${out%%	*} of wall time on ${out#*	} (max $max)"
  else
    pass "highest GC time fraction ${out%%	*} on ${out#*	}"
  fi
}

# Prints, for each steady window: stage, mean cluster CPU in cores, mean harness CPU in cores.
window_cpu() {
  local windows
  windows="$(steady_windows)"
  [ -n "$windows" ] && [ -f "$DOCKER_DIR/docker-stats.csv" ] || return 0
  printf '%s\n' "$windows" | while IFS="$(printf '\t')" read -r st t0 t1; do
    qcsv "$DOCKER_DIR/docker-stats.csv" '
      $col["wall_ms"] + 0 >= t0 && $col["wall_ms"] + 0 <= t1 {
        w = $col["wall_ms"]; c = $col["cpu_pct"] / 100
        if ($col["service"] == "harness") h[w] += c; else cl[w] += c
        seen[w] = 1
      }
      END {
        for (w in seen) { n++; sc += cl[w]; sh += h[w] }
        if (n) printf "%s\t%.2f\t%.2f\n", st, sc / n, sh / n
      }' st="$st" t0="$t0" t1="$t1"
  done
}

chk_saturation() {
  local cpus frac tgt cpu out="" bad="" st c h off ach
  cpus="$(wenv vm_cpus)"
  frac="$(th cpu.saturation.fraction)"
  tgt="$(th qps.target.fraction)"
  cpu="$(window_cpu)"
  if [ -z "$cpu" ] || [ -z "$cpus" ] || [ "$cpus" = 0 ]; then
    warn "no docker stats or no VM CPU count for the steady windows"
    return
  fi
  while IFS="$(printf '\t')" read -r st c h; do
    off="$(jq -r --arg s "$st" '.stages.steady[]? | select(.stage == $s) | .offered_rate // empty' "$MANIFEST")"
    ach="$(jq -r --arg s "$st" '.stages.steady[]? | select(.stage == $s) | .achieved_qps // empty' "$MANIFEST")"
    out="$out $st: cluster ${c} of $cpus cores, qps ${ach:-n/a} of ${off:-closed};"
    if [ -n "$off" ] && [ -n "$ach" ] && cmp "$ach" '<' "$(awk -v o="$off" -v f="$tgt" 'BEGIN { print o * f }')" &&
      cmp "$c" '>=' "$(awk -v n="$cpus" -v f="$frac" 'BEGIN { print n * f }')"; then
      bad="$bad $st"
    fi
  done <<EOF
$cpu
EOF
  # An IPC call queue that never empties in a window shows a server that cannot keep up.
  local q
  q="$(printf '%s\n' "$(steady_windows)" | while IFS="$(printf '\t')" read -r st t0 t1; do
    qcsv "$RUN_DIR/cluster.csv" '
      $col["attribute"] == "numCallsInGeneralQueue" && $col["wall_ms"] + 0 >= t0 && $col["wall_ms"] + 0 <= t1 {
        s = $col["server"]; x = $col["value"] + 0
        if (!(s in m) || x < m[s]) m[s] = x
      }
      END { for (s in m) if (m[s] > 0) printf "%s %s; ", st, s }' st="$st" t0="$t0" t1="$t1"
  done)"
  # The target rate depends on the QPS, so on a noisy host it is advisory. The IPC queue is
  # server state, so it stays a warning.
  if [ -n "$bad" ] && ! noisy; then
    warn "the VM CPU is saturated and the target rate is not met in:$bad;$out"
  elif [ -n "$q" ]; then
    warn "the IPC queue never empties in: $q${bad:+the VM CPU is saturated and the target rate is not met in:$bad}"
  elif [ -n "$bad" ]; then
    advisory "the VM CPU is saturated and the target rate is not met in:$bad;$out"
  else
    pass "${out# }"
  fi
}

chk_client_bound() {
  local cap frac cpu bad="" st c h
  cap="$(wenv harness_cpus)"
  frac="$(th harness.cpu.fraction)"
  cpu="$(window_cpu)"
  if [ -z "$cpu" ] || [ -z "$cap" ]; then
    warn "no docker stats for the steady windows"
    return
  fi
  while IFS="$(printf '\t')" read -r st c h; do
    if cmp "$h" '>=' "$(awk -v n="$cap" -v f="$frac" 'BEGIN { print n * f }')"; then
      bad="$bad $st=${h}"
    fi
  done <<EOF
$cpu
EOF
  if [ -n "$bad" ]; then
    warn "the harness is at its CPU limit of $cap cores in:$bad. The result shows the client."
  else
    pass "the harness stays below $frac of its $cap cores"
  fi
}

chk_tail() {
  local max bad
  max="$(th tail.p99_over_p50.max)"
  bad="$(mf --argjson m "$max" '
    [.stages.steady[]? | select(((.disruptions // []) | length) == 0)
      | select((.query_latency.p50_ms // 0) > 0)
      | select(.query_latency.p99_ms / .query_latency.p50_ms > $m)
      | "\(.stage) p99/p50=\(.query_latency.p99_ms / .query_latency.p50_ms)"] | join("; ")')"
  if [ -n "$bad" ]; then
    lat_fail "$bad (max $max)"
  else
    pass "steady p99/p50 at or below $max in every window without a disruption"
  fi
}

# Checks the read requests of each region server in the steady windows. The skew is the
# largest share over the mean share. An even spread gives 1. One server that serves all reads
# gives the server count. The counter of a server is the sum of the counters of its online
# regions. A region move, split, or merge lowers the sum, and a restart resets it. Thus only
# increases count, each window starts its own baseline, and the result reports the drops. A drop
# loses the reads of the interval, so the skew can be a little low. The counter covers all tables.
# Data table reads usually spread evenly and dilute the skew, but system tables sit on one server
# and raise it.
chk_read_skew() {
  local wmax fmax out skew servers top drops counts note
  wmax="$(th read.skew.warn)"
  fmax="$(th read.skew.fail)"
  out="$(qcsv "$RUN_DIR/cluster.csv" '
    $col["stage"] ~ /^steady@/ && $col["attribute"] == "readRequestCount" {
      s = $col["server"]; k = $col["stage"] SUBSEP s; x = $col["value"] + 0
      if (!(s in inc)) inc[s] = 0
      if (k in last) {
        if (x >= last[k]) inc[s] += x - last[k]
        else drops++
      }
      last[k] = x
    }
    END {
      for (s in inc) { n++; t += inc[s]; if (inc[s] >= m) { m = inc[s]; ms = s } }
      if (!n) exit
      printf "%.3f\t%d\t%s\t%d\t", (t > 0 ? m / (t / n) : 0), n, (t > 0 ? ms : "-"), drops
      for (s in inc) printf "%s=%.0f ", s, inc[s]
      printf "\n"
    }')"
  if [ -z "$out" ]; then
    warn "cluster.csv has no readRequestCount samples in the steady windows"
    return
  fi
  IFS="$(printf '\t')" read -r skew servers top drops counts <<EOF
$out
EOF
  counts="${counts% }"
  note=""
  if [ "$drops" -gt 0 ]; then
    note="; $drops counter drops from region moves, splits, or restarts not counted"
  fi
  if [ "$servers" -lt 2 ]; then
    pass "one region server; no read skew"
  elif [ "$top" = - ]; then
    warn "no read requests in the steady windows"
  elif cmp "$skew" '>=' "$fmax"; then
    fail "the busiest server $top serves $skew times the mean share of the reads (max $fmax): $counts$note"
  elif cmp "$skew" '>=' "$wmax"; then
    warn "the busiest server $top serves $skew times the mean share of the reads (warn at $wmax): $counts$note"
  else
    pass "the busiest server $top serves $skew times the mean share of the reads: $counts$note"
  fi
}

chk_warnings() {
  local fp wp f c n out="" bad=""
  fp="$(th log.fail.patterns)"
  wp="$(th log.warn.patterns)"
  for f in "$DOCKER_DIR"/log-*.txt; do
    [ -f "$f" ] || continue
    c="$(grep -cE -- "$fp" "$f" || true)"
    n="$(grep -cE -- "$wp" "$f" || true)"
    [ "${c:-0}" = 0 ] || bad="$bad $(basename "$f" .txt)=$c"
    [ "${n:-0}" = 0 ] || out="$out $(basename "$f" .txt)=$n"
  done
  if [ -n "$bad" ]; then
    fail "log lines that match the failure patterns:$bad"
  elif [ -n "$out" ]; then
    warn "log lines that match the warning patterns:$out"
  else
    pass "no matching log lines"
  fi
}

# ---- Disruptions ----

chk_mttr() {
  local max n errs nulls slow unrec skipped
  max="$(th recovery.mttr.max_seconds)"
  n="$(mf '[.stages.steady[]?.disruptions[]?] | length')"
  errs="$(mf '[.stages.steady[]?.disruptions[]? | select(.error != null) | "\(.disruption): \(.error)"] | join("; ")')"
  nulls="$(mf '[.stages.steady[]?.disruptions[]? | select(.error == null and .mttr_seconds == null) | .disruption] | join(" ")')"
  slow="$(mf --argjson m "$max" '[.stages.steady[]?.disruptions[]? | select((.mttr_seconds // 0) > $m) | "\(.disruption)=\(.mttr_seconds)s"] | join(" ")')"
  unrec="$(qcsv "$RUN_DIR/events.csv" '$col["kind"] == "disruption-unrecovered" { n++ } END { print n + 0 }')"
  # A skipped disruption means that the run does not test its schedule.
  skipped="$(mf '[.stages.steady[]?.skipped_disruptions[]?] | join(" ")')"
  if [ "$n" = 0 ]; then
    fail "the run recorded no disruption${skipped:+; skipped: $skipped}"
  elif [ -n "$errs" ] || [ -n "$slow" ] || [ "${unrec:-0}" != 0 ] || [ -n "$skipped" ]; then
    fail "errors: ${errs:-none}; slower than ${max}s: ${slow:-none}; unrecovered: $unrec; skipped: ${skipped:-none}"
  elif [ -n "$nulls" ]; then
    # IVF has no recovery signal, and a changed server set hides the metric deltas.
    warn "no MTTR for: $nulls; $(mf '[.stages.steady[]?.disruptions[]? | select(.mttr_seconds != null) | "\(.disruption)=\(.mttr_seconds)s"] | join(" ")')"
  else
    pass "$(mf '[.stages.steady[]?.disruptions[]? | "\(.disruption)=\(.mttr_seconds)s"] | join(" ")')"
  fi
}

# Prints the wall time in ms of the first disruption, from the harness or from the wrapper.
first_disruption_ms() {
  local a b
  a="$(first_event_ms disruption)"
  b="$(qcsv "$DOCKER_DIR/actions.csv" '$col["result"] ~ /^rc=/ { print $col["wall_ms"]; exit }')"
  if [ -n "$a" ] && [ -n "$b" ]; then
    if [ "$a" -lt "$b" ]; then echo "$a"; else echo "$b"; fi
  else
    echo "${a:-$b}"
  fi
}

chk_recall_stable() {
  local max t out
  max="$(th disruption.recall.drop.max)"
  t="$(first_disruption_ms)"
  if [ -z "$t" ]; then
    fail "no disruption in the run"
    return
  fi
  out="$(qcsv "$RUN_DIR/recall.csv" '
    $col["stage"] ~ /^steady@/ && $col["family"] ~ fam && $col["k"] == k && $col["recall"] != "" {
      l = $col["layout"]; r = $col["recall"] + 0
      if ($col["wall_ms"] + 0 < t) { pre[l] += r; npre[l]++ }
      n[l]++; v[l, n[l]] = r
    }
    END {
      for (l in n) {
        m = n[l] < 3 ? n[l] : 3; s = 0
        for (i = n[l] - m + 1; i <= n[l]; i++) s += v[l, i]
        if (npre[l]) printf "%s\t%.4f\t%.4f\n", l, pre[l] / npre[l], s / m
        else printf "%s\t\t%.4f\n", l, s / m
      }
    }' fam="$FAM" k="$RK" t="$t")"
  if [ -z "$out" ]; then
    fail "recall.csv has no steady samples for $FAM at k=$RK"
    return
  fi
  local msg bad nopre
  msg="$(printf '%s\n' "$out" | awk -F '\t' '{ printf "%s before %s end %s; ", $1, ($2 == "" ? "n/a" : $2), $3 }')"
  bad="$(printf '%s\n' "$out" | awk -F '\t' -v m="$max" '$2 != "" && $2 - $3 > m { print $1 }')"
  nopre="$(printf '%s\n' "$out" | awk -F '\t' '$2 == "" { print $1 }')"
  if [ -n "$bad" ]; then
    fail "recall drops more than $max: $msg"
  elif [ -n "$nopre" ]; then
    warn "no recall sample before the first disruption: $msg"
  else
    pass "$msg"
  fi
}

# A skipped disruption does not start, so it does not move the time of the last recovery.
chk_errors_after_recovery() {
  local grace mttr_max t a out ext
  grace="$(th recovery.error.grace_seconds)"
  mttr_max="$(th recovery.mttr.max_seconds)"
  t="$(qcsv "$RUN_DIR/events.csv" '$col["kind"] ~ /^disruption(-recovered|-unrecovered)?$/ { t = $col["wall_ms"] } END { print t + 0 }')"
  # A crash from the wrapper has no recovery event, so the check allows the longest recovery time.
  a="$(qcsv "$DOCKER_DIR/actions.csv" '$col["result"] ~ /^rc=/ { t = $col["wall_ms"] + m * 1000 } END { print t + 0 }' m="$mttr_max")"
  a="${a:-0}"
  t="${t:-0}"
  [ "$a" -le "$t" ] || t="$a"
  if [ "$t" = 0 ]; then
    fail "no disruption in the run"
    return
  fi
  out="$(qcsv "$RUN_DIR/throughput.csv" '
    $col["wall_ms"] + 0 > t + g * 1000 { n++; e += $col["query_errors"] }
    END { print n + 0, e + 0 }' t="$t" g="$grace")"
  out="${out:-0 0}"
  if [ "${out% *}" = 0 ]; then
    # The reads of a window can run past its end until a disruption and its recovery end.
    ext="$(mf '[.stages.steady[]? | select((.extension_seconds // 0) > 0) | "\(.stage) reads \(.extension_seconds | floor) s past its end until \(.extended_by) ends"] | last // empty')"
    warn "no throughput sample after the last recovery plus ${grace}s${ext:+; $ext}"
  elif [ "${out#* }" != 0 ]; then
    fail "${out#* } query errors in ${out% *} samples after the last recovery plus ${grace}s"
  else
    pass "no query errors in ${out% *} samples after the last recovery plus ${grace}s"
  fi
}

chk_recall_samples() {
  local min n
  min="$(th recall.samples.min)"
  n="$(qcsv "$RUN_DIR/recall.csv" '$col["stage"] ~ /^(steady@|stream)/ && $col["family"] ~ fam && $col["recall"] != "" { n++ } END { print n + 0 }' fam="$FAM")"
  if [ "${n:-0}" -lt "$min" ]; then
    fail "${n:-0} online recall samples (min $min)"
  else
    pass "$n online recall samples"
  fi
}

# ---- Lifecycle ----

# The drift baseline is the frontier recall of the tracked queries at the sweep point of the
# steady sampler. The frontier runs before the steady window, so it has no drift. It uses the same
# queries, the same k and the same sweep point as the steady samples. Therefore, the difference
# shows the effect of the drift. The first steady samples are not a good baseline, because the
# drift starts with the steady window. A sample that comes early in the drift can be lower than
# the frontier recall, and then the check sees only part of the drop.
# The check finds the frontier point by its sweep value, not by its label. The frontier can give
# the default point the label of an explicit point with the same value. A group is one layout and
# one family. The baseline is the mean of the frontier recall of each group. The frontier must have
# a result for each tracked query in each group of the steady samples. If it does not, the
# baseline is the mean of the first two steady samples, and the check gives a warning.
# A sample is the mean recall of all groups at one sample time. A sample without a recall for each
# group does not count. The recall before the rebuild is the mean of the last two samples before
# its start. With the sample baseline, the two windows must not overlap, so the check needs four
# samples before the rebuild. The drop between the baseline and the recall before the rebuild must
# reach a minimum, or the run does not show drift. The recall at the end is the mean of the last
# samples at or after the end of the rebuild, up to three of them. It must win back a set fraction
# of the drop. A recall that stays at the drifted level does not pass. Samples from the rebuild
# window do not count toward the end. If no sample comes after the rebuild, the check fails.
# The argument is a regex that selects one query family. Without it, the check uses
# recall.families. Unfiltered recall can stay at its maximum under drift, because writes and
# probes use the same nearest centroid rule. A selective filter shows stale centroids better.
chk_drift_recovery() {
  local frac drop s e gens out nb ng base before end need value sweeps tracked anchor src minb
  local q ag fam="${ARG:-$FAM}" problems=()
  frac="$(th drift.recall.recovery.min)"
  drop="$(th drift.recall.drop.min)"
  if [ -n "$(first_event_ms explicit-rebuild-failed)" ]; then
    fail "the explicit rebuild failed: $(qcsv "$RUN_DIR/events.csv" '$col["kind"] == "explicit-rebuild-failed" { print $col["detail"]; exit }')"
    return
  fi
  s="$(first_event_ms explicit-rebuild-start)"
  e="$(first_event_ms explicit-rebuild-end)"
  if [ -z "$s" ]; then
    fail "the run has no completed explicit rebuild"
    return
  fi
  gens="$(qcsv "$RUN_DIR/lifecycle.csv" '$col["role"] == "ACTIVE" { if (first == "") first = $col["generation"]; last = $col["generation"] }
    END { print first " " last }')"
  # The rows of one sample have the same wall time, one row for each group.
  out="$(qcsv "$RUN_DIR/recall.csv" '
    $col["family"] ~ fam && $col["k"] == k && $col["stage"] ~ /^steady@/ {
      g = $col["layout"] SUBSEP $col["family"]
      if (!(g in groups)) { groups[g] = 1; ng++ }
      if ($col["recall"] == "") next
      w = $col["wall_ms"]
      if (!(w in at)) { n++; at[w] = n; when[n] = w + 0 }
      i = at[w]; sum[i] += $col["recall"]; cnt[i]++
    }
    END {
      for (i = 1; i <= n; i++) {
        if (cnt[i] < ng) continue
        c++; v[c] = sum[i] / cnt[i]
        if (when[i] < s) nb++
        if (e != "" && when[i] >= e + 0) { na++; a[na] = v[c] }
      }
      if (nb == 0) exit
      base = nb > 1 ? (v[1] + v[2]) / 2 : v[1]
      before = nb > 1 ? (v[nb - 1] + v[nb]) / 2 : v[nb]
      m = na < 3 ? na : 3; t = 0
      for (i = na - m + 1; i <= na; i++) t += a[i]
      printf "%d %.4f %.4f %s %d\n", nb, base, before, (m > 0 ? sprintf("%.4f", t / m) : "-"), ng
    }' fam="$fam" k="$RK" s="$s" e="$e")"
  if [ -z "$out" ]; then
    fail "no recall samples of family $fam before the rebuild"
    return
  fi
  read -r nb base before end ng <<<"$out"
  # The steady sampler uses the first tracked_queries queries of the panel at one sweep point.
  value="$(qcsv "$RUN_DIR/steady.csv" '$col["stage"] ~ /^steady@/ && $col["family"] ~ fam && $col["k"] == k {
    print $col["sweep_value"]; exit }' fam="$fam" k="$RK")"
  tracked="$(mf '.run.tracked_queries // 0')"
  sweeps=""
  anchor=""
  if [ -n "$value" ] && [ "${tracked:-0}" -gt 0 ]; then
    sweeps="$(qcsv "$RUN_DIR/frontier.csv" '$col["stage"] == "frontier" && $col["family"] ~ fam && $col["k"] == k &&
      $col["sweep_value"] != "" && $col["sweep_value"] + 0 == value + 0 && !($col["sweep"] in seen) {
        seen[$col["sweep"]] = 1; out = out (out == "" ? "" : "|") $col["sweep"] }
      END { print out }' fam="$fam" k="$RK" value="$value")"
  fi
  if [ -n "$sweeps" ]; then
    anchor="$(qcsv "$RUN_DIR/per_query.csv" '
      !ready { split(sweeps, a, "|"); for (j in a) want[a[j]] = 1; ready = 1 }
      $col["stage"] == "frontier" && $col["family"] ~ fam && $col["k"] == k && ($col["sweep"] in want) &&
        $col["query"] + 0 < tracked && $col["recall"] != "" && $col["error"] == "" &&
        $col["excluded"] + 0 == 0 {
        g = $col["layout"] SUBSEP $col["family"]
        if (!(g in gs)) { gs[g] = 1; ng++ }
        t[g] += $col["recall"]; c[g]++
        if (!((g, $col["query"]) in seen)) { seen[g, $col["query"]] = 1; q[g]++ }
      }
      END {
        if (ng == 0) exit
        low = -1
        for (g in gs) { r += t[g] / c[g]; if (low < 0 || q[g] < low) low = q[g] }
        printf "%.4f %d %d\n", r / ng, low, ng
      }' fam="$fam" k="$RK" sweeps="$sweeps" tracked="$tracked")"
  fi
  q=0
  ag=0
  if [ -n "$anchor" ]; then
    read -r value q ag <<<"$anchor"
  fi
  if [ -n "$anchor" ] && [ "$q" -eq "$tracked" ] && [ "$ag" -eq "$ng" ]; then
    base="$value"
    src="frontier, $q queries in $ag group(s) at $sweeps"
    minb=2
  else
    src="first two steady samples"
    minb=4
    if [ "${tracked:-0}" -le 0 ]; then
      warn "family $fam: the manifest has no tracked_queries, so the baseline is the first two steady samples"
    elif [ -z "$value" ]; then
      warn "family $fam: steady.csv has no sweep value, so the baseline is the first two steady samples"
    elif [ -z "$sweeps" ]; then
      warn "family $fam: no frontier point at the steady sweep value '${value}', so the baseline is the first two steady samples"
    else
      warn "family $fam: the frontier at $sweeps has $q of $tracked tracked queries in $ag of $ng group(s), so the baseline is the first two steady samples"
    fi
  fi
  [ -n "$e" ] || problems+=("the run has no completed explicit rebuild")
  if [ "$nb" -lt "$minb" ]; then
    problems+=("the run did not show drift: $nb recall samples before the rebuild, and the check needs $minb")
  elif cmp "$(awk -v a="$base" -v b="$before" 'BEGIN { print a - b }')" '<' "$drop"; then
    problems+=("the run did not show drift: recall at the baseline $base ($src), before the rebuild $before, a drop less than drift.recall.drop.min=$drop")
  fi
  if [ "${gens% *}" = "${gens#* }" ]; then
    problems+=("no new active generation after the rebuild (generation ${gens% *})")
  fi
  need="$(awk -v a="$base" -v b="$before" -v f="$frac" 'BEGIN { printf "%.4f", b + (a - b) * f }')"
  if [ "$end" = "-" ]; then
    [ -z "$e" ] || problems+=("no recall samples of family $fam after the end of the rebuild")
  elif cmp "$end" '<' "$need"; then
    problems+=("recall did not recover: at the baseline $base, before the rebuild $before, at the end $end, less than $need (drift.recall.recovery.min=$frac of the drop)")
  fi
  if [ ${#problems[@]} -gt 0 ]; then
    fail "$(printf '%s; ' "${problems[@]}")generation ${gens% *} -> ${gens#* }"
  else
    pass "family $fam: recall at the baseline $base ($src), before the rebuild $before, at the end $end (min $need); generation ${gens% *} -> ${gens#* }"
  fi
}

chk_families() {
  local min n list
  min="$(th functional.families.min)"
  list="$(qcsv "$RUN_DIR/frontier.csv" '$col["queries"] + 0 > 0 { f[$col["family"]] = 1 } END { for (x in f) print x }' | sort)"
  n="$(printf '%s\n' "$list" | awk 'NF { n++ } END { print n + 0 }')"
  if [ "$n" -lt "$min" ]; then
    fail "$n query families with results (min $min): $(printf '%s\n' "$list" | tr '\n' ' ')"
  else
    pass "$n query families with results"
  fi
}

# The coverage column tells which filters the index does not include, for the real index of each
# layout. Older runs have no coverage column, so the check then selects by the shape name.
chk_uncovered_path() {
  local n q e
  read -r n q e <<EOF
$(qcsv "$RUN_DIR/frontier.csv" '
    ("coverage" in col) ? $col["coverage"] == "uncovered" : $col["family"] ~ /^uncovered_filter/ {
      n++; q += $col["queries"]; e += $col["errors"]
    }
    END { print n + 0, q + 0, e + 0 }')
EOF
  if [ "${n:-0}" = 0 ] || [ "${q:-0}" = 0 ]; then
    fail "no queries with an uncovered index filter in the frontier"
  elif [ "$e" != 0 ]; then
    fail "$e errors in $q queries with an uncovered index filter"
  else
    pass "$q queries with an uncovered index filter in $n frontier points without errors"
  fi
}

# Segments must load on region open without a full rebuild. Each region server restarted before
# the run, so its rebuild counter counts the rebuilds since the restart.
chk_no_rebuild_on_open() {
  local v
  v="$(qcsv "$RUN_DIR/metrics.csv" '
    $col["stage"] ~ /^(truth|validity|frontier)$/ && $col["attribute"] ~ /^vectorHnswRebuilds(\.|$)/ {
      x = $col["value"] + 0; seen = 1; if (x > m) m = x
    }
    END { if (seen) print m + 0 }')"
  if [ -z "$v" ]; then
    fail "metrics.csv has no vectorHnswRebuilds samples"
  elif [ "$v" != 0 ]; then
    fail "vectorHnswRebuilds reaches $v before the steady state; the reopen rebuilt segments"
  else
    pass "no HNSW rebuild after the reopen"
  fi
}

# ---- Comparison ----

ref_dir() {
  local d="$OUT_ROOT/runs/$1"
  [ -f "$d/manifest.json" ] || d="$OUT_ROOT/runs/$1-r1"
  [ -f "$d/manifest.json" ] || die_usage "no run $1 in $OUT_ROOT/runs"
  echo "$d"
}

chk_qps_vs_exact() {
  [ -n "$ARG" ] || die_usage "qps-vs-exact needs qps-vs-exact:<exact-run-id>"
  local ref ratio rec out slow=FAIL
  ref="$(ref_dir "$ARG")"
  ratio="$(th index.qps_vs_exact.min_ratio)"
  rec="$(th index.qps_vs_exact.recall)"
  # A low QPS ratio is advisory on a noisy host. An index that does not reach the recall level
  # still fails, because recall does not depend on time.
  if noisy; then slow=ADVISORY; fi
  out="$({
    frontier_rows "$ref" | awk -F '\t' '$3 == "exact" { print "E\t" $1 "\t" $7 }'
    frontier_rows | awk -F '\t' -v r="$rec" '$5 + 0 >= r + 0 { print "I\t" $1 "\t" $7 }'
  } | awk -F '\t' -v m="$ratio" -v slow="$slow" '
    $1 == "E" { if ($3 + 0 > e[$2]) e[$2] = $3 + 0; next }
    { if ($3 + 0 > ix[$2]) ix[$2] = $3 + 0; seen[$2] = 1 }
    END {
      for (l in e) {
        if (!(l in seen)) { print "FAIL " l ": no index point reaches the recall level"; continue }
        q = e[l] > 0 ? ix[l] / e[l] : 0
        printf "%s %s: %.1f qps vs exact %.1f qps, %.1fx\n", (q < m ? slow : "PASS"), l, ix[l], e[l], q
      }
    }')"
  if [ -z "$out" ]; then
    fail "no comparable exact frontier in $ARG"
  elif printf '%s\n' "$out" | grep -q '^FAIL'; then
    fail "below ${ratio}x at recall $rec: $(printf '%s\n' "$out" | tr '\n' ';')"
  elif printf '%s\n' "$out" | grep -q '^ADVISORY'; then
    advisory "below ${ratio}x at recall $rec: $(printf '%s\n' "$out" | tr '\n' ';')"
  else
    pass "$(printf '%s\n' "$out" | sed 's/^PASS //' | tr '\n' ';')"
  fi
}

# Succeeds when the header of a harness CSV file has the column.
has_col() {
  [ -f "$1" ] || return 1
  qcsv "$1" 'END { exit !(c in col) }' c="$2"
}

# Prints the work per query of the frontier rows of the recall family at the recall k, one line
# per row: layout, sweep, recall, work. The measure "rows" gives the rows scanned per successful
# query. The measure "rpcs" gives the RPCs per successful query. The harness counts the RPCs of
# successful queries only, but it divides them by all queries. Thus both measures use the same
# divisor.
work_rows() {
  qcsv "$1/frontier.csv" '
    $col["family"] ~ fam && $col["k"] == k {
      ok = $col["queries"] - $col["errors"]
      if (ok <= 0) next
      if (m == "rows") {
        if ($col["rows_scanned"] !~ /^[0-9]+$/) next
        w = $col["rows_scanned"] / ok
      } else {
        if ($col["rpcs_per_query"] !~ /^[0-9]+(\.[0-9]*)?([eE][-+]?[0-9]+)?$/) next
        w = $col["rpcs_per_query"] * $col["queries"] / ok
      }
      print $col["layout"] "\t" $col["sweep"] "\t" $col["recall"] "\t" w
    }' fam="$FAM" k="$RK" m="$2"
}

# Compares the work per query of the index with the work of brute force. Rows and RPCs are
# counts, so host noise does not change them, and these checks fail on any host. The check
# selects, for each layout, the index point with the least work at the recall level. A run
# without the column, such as an older run, gives SKIP. A missing frontier file fails, as in the
# QPS comparison. A zero count shows that the run did not collect the metric, so it gives SKIP.
work_vs_exact() {
  local name="$1" measure="$2" column="$3" key="$4" unit="$5" ref max rec out f
  [ -n "$ARG" ] || die_usage "$name needs $name:<exact-run-id>"
  ref="$(ref_dir "$ARG")"
  max="$(th "$key")"
  rec="$(th index.work_vs_exact.recall)"
  for f in "$ref/frontier.csv" "$RUN_DIR/frontier.csv"; do
    if [ -f "$f" ] && ! has_col "$f" "$column"; then
      skip "n/a: the frontier of $ARG or of $RUN_ID has no $column column"
      return
    fi
  done
  out="$({
    work_rows "$ref" "$measure" | awk -F '\t' '$2 == "exact" { print "E\t" $1 "\t" $4 }'
    work_rows "$RUN_DIR" "$measure" | awk -F '\t' -v r="$rec" '
      $2 != "exact" && $3 != "" && $3 + 0 >= r + 0 { print "I\t" $1 "\t" $4 "\t" $2 }'
  } | awk -F '\t' -v m="$max" -v u="$unit" '
    $1 == "E" { if (!($2 in e) || $3 + 0 < e[$2]) e[$2] = $3 + 0; next }
    { if (!($2 in ix) || $3 + 0 < ix[$2]) { ix[$2] = $3 + 0; sw[$2] = $4 } }
    END {
      for (l in e) {
        if (!(l in ix)) { print "FAIL " l ": no index point reaches the recall level"; continue }
        if (e[l] <= 0) { print "SKIP " l ": the exact run reports no " u; continue }
        if (ix[l] <= 0) { print "SKIP " l ": the index run reports no " u; continue }
        q = ix[l] / e[l]
        printf "%s %s: %s %.1f %s per query vs exact %.1f, %.3fx\n", (q > m ? "FAIL" : "PASS"), l, sw[l], ix[l], u, e[l], q
      }
    }')"
  if [ -z "$out" ]; then
    fail "no comparable exact frontier in $ARG"
  elif printf '%s\n' "$out" | grep -q '^FAIL'; then
    fail "above ${max}x of exact at recall $rec: $(printf '%s\n' "$out" | tr '\n' ';')"
  elif ! printf '%s\n' "$out" | grep -q '^PASS'; then
    skip "n/a: $(printf '%s\n' "$out" | sed 's/^SKIP //' | tr '\n' ';')"
  else
    pass "$(printf '%s\n' "$out" | sed 's/^PASS //' | tr '\n' ';') (max ${max}x)"
  fi
}

chk_rows_vs_exact() {
  work_vs_exact rows-vs-exact rows rows_scanned index.rows_vs_exact.max_ratio rows
}

chk_rpcs_vs_exact() {
  work_vs_exact rpcs-vs-exact rpcs rpcs_per_query index.rpcs_vs_exact.max_ratio RPCs
}

chk_published() {
  local load agrees gap rows bad used total what
  load="$TABLE_DIR/load.json"
  gap="$(th published.recall.gap.max)"
  # The jq operator "//" also replaces false, so the program tests for null.
  agrees="$( [ -f "$load" ] && jq -r '.truth.published_agrees | if . == null then empty else tostring end' "$load" || true)"
  what="truth agrees"
  if [ -z "$agrees" ]; then
    # The published truth covers all rows of the corpus. A run that loads fewer rows, as a prefix
    # or because it holds out a tail for new vectors, has only the computed truth. The recall gap
    # then applies to that truth.
    used="$( [ -f "$load" ] && jq -r '.corpus.rows // empty' "$load" || true)"
    total="$( [ -f "$load" ] && jq -r '.corpus["train.count"] // .corpus["base.count"] // empty' "$load" || true)"
    case "$used" in
      '' | *[!0-9]*) used=0 ;;
    esac
    case "$total" in
      '' | *[!0-9]*) total=0 ;;
    esac
    if [ "$used" = 0 ] || [ "$used" -ge "$total" ]; then
      warn "the corpus has no published ground truth"
      return
    fi
    what="the run loads $used of $total rows (a prefix or a held-out tail), so the computed truth applies"
  elif [ "$agrees" != true ]; then
    fail "the computed ground truth disagrees with the published ground truth"
    return
  fi
  # The highest sweep point of each layout must come within the gap of full recall.
  rows="$(frontier_rows | sort -t "$(printf '\t')" -k1,1 -k4,4n | awk -F '\t' '{ r[$1] = $5; s[$1] = $3 } END { for (l in r) print l "\t" s[l] "\t" r[l] }')"
  if [ -z "$rows" ]; then
    fail "no frontier point for $FAM at k=$RK"
    return
  fi
  bad="$(printf '%s\n' "$rows" | awk -F '\t' -v g="$gap" 'NF && $3 + 0 < 1 - g { printf "%s %s recall=%s; ", $1, $2, $3 }')"
  if [ -n "$bad" ]; then
    fail "more than $gap below full recall: $bad"
  else
    pass "$what; $(printf '%s\n' "$rows" | awk -F '\t' 'NF { printf "%s %s=%s ", $1, $2, $3 }')"
  fi
}

# Checks the statistics mode of the run. The mode must match the wrapper, and the harness must
# read the switch of the region servers. The off mode must give no statistics rows. The on mode
# must give real guideposts for each IVF index, and each update during the run must succeed. An
# HNSW index shares the HBase table of its data table, so it has no rows of its own.
chk_statistics() {
  local mode b="$TABLE_DIR/build.json" built rows failed zero stray fails0="$FAILS"
  mode="$(mf '.settings["table.statistics"] // empty | ascii_downcase')"
  if [ -z "$mode" ]; then
    skip "the run is from before the statistics modes"
    return
  fi
  if [ -f "$WRAPPER_ENV" ]; then
    built="$(vhs_prop "$WRAPPER_ENV" statistics)"
    if [ -n "$built" ] && [ "$built" != "$mode" ]; then
      fail "the run has table.statistics=$mode, but the wrapper recorded statistics=$built"
      return
    fi
    [ "$(mf '.statistics.servers_checked // 0')" != 0 ] ||
      fail "the harness read the statistics switch of no region server"
  fi
  if [ "$mode" = off ]; then
    stray="$( (
      [ ! -f "$b" ] || jq -r '.guideposts // {} | to_entries[] | select((.value | type) == "object")
        | select((.value.rows // 0) > 0) | "build:\(.key)=\(.value.rows)"' "$b"
      mf '.statistics.guideposts_end // {} | to_entries[] | select((.value | type) == "object")
        | select((.value.rows // 0) > 0) | "end:\(.key)=\(.value.rows)"'
    ) | tr '\n' ' ')"
    stray="${stray% }"
    if [ -n "$stray" ]; then
      warn "statistics off, but SYSTEM.STATS has rows: $stray"
    elif [ "$FAILS" = "$fails0" ]; then
      pass "statistics off; no statistics rows"
    fi
    return
  fi
  failed="$(mf '[.statistics.updates // [] | .[] | select(.outcome == "failed")] | length')"
  [ "$failed" = 0 ] || fail "$failed statistics updates failed during the run"
  zero="$(mf '[.statistics.updates // [] | .[] | select(.index_guideposts != null)
    | select((.index_guideposts.guideposts // 0) == 0) | .table] | join(" ")')"
  [ -z "$zero" ] || fail "a statistics update during the run left no guideposts: $zero"
  if [ "$VARIANT" = exact ] || [ "$VARIANT" = hnsw ]; then
    [ "$FAILS" != "$fails0" ] || pass "statistics on; the $VARIANT variant has no index table"
    return
  fi
  if [ ! -f "$b" ]; then
    warn "statistics on, but no build record in $TABLE_DIR"
    return
  fi
  rows="$(jq -r '.guideposts // {} | to_entries[] | select(.key | endswith("_VIDX"))
    | "\(.key)=\(.value.guideposts)"' "$b" | tr '\n' ' ')"
  rows="${rows% }"
  if [ -z "$rows" ]; then
    fail "statistics on, but the build record has no guideposts for an index"
  elif printf '%s' "$rows" | grep -qE '=(0|null)( |$)'; then
    fail "an index has no guideposts after the build: $rows"
  elif [ "$FAILS" = "$fails0" ]; then
    pass "statistics on; build guideposts $rows"
  fi
}

chk_build_rate() {
  local b="$TABLE_DIR/build.json" rows secs rs
  if [ ! -f "$b" ]; then
    warn "no build record in $TABLE_DIR"
    return
  fi
  rows="$(jq -r '.build.rows_indexed // empty' "$b")"
  secs="$(jq -r '.build.seconds // empty' "$b")"
  rs="$(qcsv "$RUN_DIR/cluster.csv" '{ s[$col["server"]] = 1 } END { for (x in s) n++; print n + 0 }')"
  [ "$rs" != 0 ] || rs=3
  if [ -z "$rows" ] || [ -z "$secs" ] || ! cmp "$secs" '>' 0; then
    warn "the build record has no row count or time"
  else
    pass "$(awk -v r="$rows" -v s="$secs" -v n="$rs" 'BEGIN { printf "%d rows in %.1f s: %.0f rows/s, %.0f rows/s per region server", r, s, r / s, r / s / n }')"
  fi
}

chk_simd_ab() {
  [ -n "$ARG" ] || die_usage "simd-ab needs simd-ab:<simd-run-id>"
  local ref out
  ref="$(ref_dir "$ARG")"
  if [ "$(jq -r '.run.simd_vector_api' "$ref/manifest.json")" != true ]; then
    fail "$ARG did not use the vector API"
    return
  fi
  # The p50 latency at the highest sweep point of each layout, with and without SIMD.
  out="$({
    frontier_rows "$ref" | awk -F '\t' '{ print "S\t" $0 }'
    frontier_rows | awk -F '\t' '{ print "N\t" $0 }'
  } | sort -t "$(printf '\t')" -k1,1 -k2,2 -k5,5n | awk -F '\t' '
    { p[$1, $2] = $7; seen[$2] = 1 }
    END {
      for (l in seen) if ((("S", l) in p) && (("N", l) in p) && p["S", l] > 0)
        printf "%s\t%.3f\t%.3f\t%.3f\n", l, p["S", l], p["N", l], p["N", l] / p["S", l]
    }')"
  if [ -z "$out" ]; then
    warn "no common layout in the two frontiers"
  elif printf '%s\n' "$out" | awk -F '\t' '$4 < 1.02 { found = 1 } END { exit !found }'; then
    lat_warn "no SIMD gain at the highest sweep point, the SIMD path may be off: $(printf '%s\n' "$out" | awk -F '\t' '{ printf "%s simd %s ms, scalar %s ms; ", $1, $2, $3 }')"
  else
    pass "$(printf '%s\n' "$out" | awk -F '\t' '{ printf "%s simd %s ms, scalar %s ms (%.2fx); ", $1, $2, $3, $4 }')"
  fi
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -t)
        THRESHOLDS="${2:?-t needs a file}"
        shift 2
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *) break ;;
    esac
  done
  [ $# -ge 2 ] || {
    usage >&2
    exit 2
  }
  command -v jq >/dev/null 2>&1 || die_usage "jq is not on the PATH"
  [ -f "$THRESHOLDS" ] || die_usage "no thresholds file $THRESHOLDS"
  # A thresholds file without the key gets the safe value, noisy.
  HOST_NOISE="$(vhs_prop "$THRESHOLDS" host.noise)"
  HOST_NOISE="${HOST_NOISE:-noisy}"
  case "$HOST_NOISE" in
    noisy | quiet) ;;
    *) die_usage "host.noise must be noisy or quiet, not '$HOST_NOISE'" ;;
  esac
  RUN_DIR="${1%/}"
  shift
  MANIFEST="$RUN_DIR/manifest.json"
  [ -f "$MANIFEST" ] || die_usage "no manifest in $RUN_DIR"
  OUT_ROOT="$(cd "$RUN_DIR/../.." && pwd)"
  RUN_ID="$(basename "$RUN_DIR")"
  # A repeated run has the suffix -rN. The wrapper records belong to the id without the suffix.
  DOCKER_DIR="$OUT_ROOT/docker/$RUN_ID"
  if [ ! -d "$DOCKER_DIR" ]; then
    DOCKER_DIR="$OUT_ROOT/docker/$(printf '%s' "$RUN_ID" | sed -E 's/-r[0-9]+$//')"
  fi
  WRAPPER_ENV="$DOCKER_DIR/wrapper.env"
  VARIANT="$(mf '.run.variant')"
  local suffix
  suffix="$(mf '.settings["table.suffix"] // empty')"
  [ -n "$suffix" ] || suffix="${VARIANT}_$(mf '.run.profile')"
  TABLE_DIR="$OUT_ROOT/tables/$(vhs_ident "$suffix")"
  FAM="$(th recall.families)"
  RK="$(th recall.k)"

  echo "run $RUN_ID ($VARIANT, $(mf '.run.profile'))"
  if noisy; then
    echo "host.noise=noisy: the latency and QPS verdicts are advisory"
  fi
  local c fn
  for c in "$@"; do
    CHECK="$c"
    ARG=""
    case "$c" in
      *:*)
        ARG="${c#*:}"
        c="${c%%:*}"
        ;;
    esac
    fn="chk_$(printf '%s' "$c" | tr '-' '_')"
    if ! declare -F "$fn" >/dev/null; then
      die_usage "unknown check '$c'"
    fi
    "$fn"
  done
  # The extra counts appear only when they are not zero, so a quiet run prints the same summary.
  local extra=""
  [ "$ADVISORIES" = 0 ] || extra="$extra, $ADVISORIES advisory"
  [ "$SKIPS" = 0 ] || extra="$extra, $SKIPS skipped"
  echo "summary: $FAILS failed, $WARNS warnings$extra"
  [ "$FAILS" = 0 ]
}

main "$@"
