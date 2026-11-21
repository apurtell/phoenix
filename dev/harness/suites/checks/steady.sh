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

# Built-in steady-state checks executed once per run by suites/check-cluster.sh after
# suites/checks/cluster.sh. Each of the two check subjects writes one verdict row carrying the
# source check:steady:
#
#   steady.query_latency.p99_ms  The highest p99 latency recorded across any steady window.
#   steady.attainment            The lowest achieved rate recorded across any steady window,
#                                expressed as a percentage of its offered rate.
#
# Three design rules govern this module:
#   1. Evidence is read exclusively from the bundle, taking the last aggregate record for each key
#      in each steady@ anchor, which are the same values carried by the manifest.
#   2. Every threshold is loaded from PX_THRESHOLDS_FILE through sx_threshold; this file contains
#      no hardcoded threshold literals.
#   3. Because both subjects evaluate time evidence, each verdict row carries the duration class.
#      px-report downgrades a FAIL on either subject to ADVISORY unless the run belongs to the
#      measurement class with no contamination. A subject without evidence writes a SKIP verdict
#      with the reason.
# See docs/bundle-format.md, section "The cluster checks", for the specification.

SC_HOME="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck source=SCRIPTDIR/../../lib/bundle.sh
. "$SC_HOME/lib/bundle.sh"

set -euo pipefail
px_trap_enable

SC_AGGREGATE=""
SC_VALUE=""

px_cli_spec <<'EOF'
about Runs the two steady-state checks against the run that PX_RUN_DIR names, with
+ the thresholds of PX_THRESHOLDS_FILE. suites/check-cluster.sh calls it; a direct
+ call needs both names.
exits 2 also means an unset PX_RUN_DIR or PX_THRESHOLDS_FILE.
EOF

## @description  Reads a threshold (check, key) into SC_VALUE, recording an ERROR verdict row for
##               the check when the key is absent.
## @return       Exits 0 when the key is present, or exits 1 after recording the ERROR row.
sc_threshold() {
  SC_VALUE="$(sx_threshold "$2" || exit "$?")" && return 0
  sx_verdict_row "$1" ERROR duration "the threshold $2 is absent from $PX_THRESHOLDS_FILE"
  return "$PX_EXIT_FAIL"
}

# Extracts the final aggregate value of each requested metric key (key...) for every steady@ anchor,
# emitting one tab-separated "anchor value..." row per anchor that holds at least one matching key.
## @description  Reports the last aggregate value per steady@ anchor for the given keys (key...).
## @return       Writes one "anchor value..." row per matching anchor to stdout and exits 0.
sc_values() {
  awk -F '\t' -v keys="$*" '
    BEGIN { n = split(keys, k, " "); for (i = 1; i <= n; i++) want[k[i]] = i }
    NR > 1 && $4 ~ /^steady@/ && ($5 in want) {
      if (!($4 in seen)) { seen[$4] = 1; order[++m] = $4 }
      v[$4, want[$5]] = $7
    }
    END {
      for (j = 1; j <= m; j++) {
        line = order[j]
        for (i = 1; i <= n; i++) line = line "\t" v[order[j], i]
        print line
      }
    }' "$SC_AGGREGATE"
}

## @description  Evaluates the p99 subject against the highest query_latency.p99_ms recorded across
##               all steady windows.
## @return       Exits 0.
sc_p99() {
  local check=steady.query_latency.p99_ms rows worst
  rows="$(sc_values query_latency.p99_ms | awk -F '\t' '$2 != ""')"
  if [ -z "$rows" ]; then
    sx_verdict_row "$check" SKIP duration "no steady window records query_latency.p99_ms"
    return 0
  fi
  sc_threshold "$check" steady.p99_ms.max || return 0
  worst="$(printf '%s\n' "$rows" | awk -F '\t' 'NR == 1 || $2 + 0 > m + 0 { m = $2; a = $1 }
    END { print a "\t" m }')"
  if awk -v v="${worst#*"$PX_TAB"}" -v m="$SC_VALUE" 'BEGIN { exit !(v + 0 > m + 0) }'; then
    sx_verdict_row "$check" FAIL duration "${worst%%"$PX_TAB"*} has a p99 of" \
      "${worst#*"$PX_TAB"} ms; steady.p99_ms.max=$SC_VALUE"
  else
    sx_verdict_row "$check" PASS duration "the highest p99 is ${worst#*"$PX_TAB"} ms in" \
      "${worst%%"$PX_TAB"*}; steady.p99_ms.max=$SC_VALUE"
  fi
}

# Evaluates the attainment subject by computing achieved_qps as a percentage of offered_rate across
# every steady window that records both metrics with an offered rate greater than zero.
## @description  Evaluates the attainment subject against the lowest rate attainment across all
##               eligible steady windows.
## @return       Exits 0.
sc_attainment() {
  local check=steady.attainment rows worst
  rows="$(sc_values offered_rate achieved_qps |
    awk -F '\t' '$2 + 0 > 0 && $3 != "" { printf "%s\t%.1f\n", $1, 100 * $3 / $2 }')"
  if [ -z "$rows" ]; then
    sx_verdict_row "$check" SKIP duration "no steady window records offered_rate and" \
      "achieved_qps"
    return 0
  fi
  sc_threshold "$check" steady.attainment_pct.min || return 0
  worst="$(printf '%s\n' "$rows" | awk -F '\t' 'NR == 1 || $2 + 0 < m + 0 { m = $2; a = $1 }
    END { print a "\t" m }')"
  if awk -v v="${worst#*"$PX_TAB"}" -v m="$SC_VALUE" 'BEGIN { exit !(v + 0 < m + 0) }'; then
    sx_verdict_row "$check" FAIL duration "${worst%%"$PX_TAB"*} attains ${worst#*"$PX_TAB"}%" \
      "of its offered rate; steady.attainment_pct.min=$SC_VALUE"
  else
    sx_verdict_row "$check" PASS duration "the lowest attainment is ${worst#*"$PX_TAB"}% in" \
      "${worst%%"$PX_TAB"*}; steady.attainment_pct.min=$SC_VALUE"
  fi
}

px_cli_parse "$@"
# Because suites/check-cluster.sh exports its loaded configuration through the process environment,
# reload configuration files here only when the caller explicitly passes them on the command line.
[ "${#PX_CLI_FILES[@]}" = 0 ] || px_config_load -- "${PX_CLI_FILES[@]}"

[ -n "${PX_RUN_DIR:-}" ] ||
  px_die "$PX_EXIT_USAGE" "PX_RUN_DIR is unset: name the bundle of the run"
[ -n "${PX_THRESHOLDS_FILE:-}" ] ||
  px_die "$PX_EXIT_USAGE" "PX_THRESHOLDS_FILE is unset: suites/check-cluster.sh merges and names it"
PX_RUN_DIR="$(px_out_resolve "$PX_RUN_DIR")" || exit $?
[ -d "$PX_RUN_DIR" ] || px_die "$PX_EXIT_USAGE" "PX_RUN_DIR names no directory: $PX_RUN_DIR"
px_plan check steady.query_latency.p99_ms steady.attainment && exit "$PX_EXIT_OK"
export PX_RUN_DIR PX_RECORD_SOURCE=check:steady
SC_AGGREGATE="$PX_RUN_DIR/metrics/aggregate.tsv"
[ -f "$SC_AGGREGATE" ] || SC_AGGREGATE=/dev/null

sc_p99
sc_attainment
exit 0
