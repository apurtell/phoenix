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

# This script implements the host preflight verification in two distinct phases. Before executing
# either phase, it rejects any HBase release that falls outside the version range supported by the
# compatibility module of the active profile.
#
# During the fast phase, the script delegates all engine-level queries to the backend preflight
# entrypoint, sb_preflight, which verifies that the engine is present and responsive, that the
# client is authorized, that component versions meet their minimum floors, and that every port
# published by the deployment, meaning every port in the rendered port map, is free on 127.0.0.1 of
# the host. This script emits those result rows unchanged and adds no engine logic of its own, so
# it never names a container runtime. The backend stops at the first engine fault, and the first
# failed row determines the exit code: 1 when the engine does not answer, 5 when it refuses the
# client, and 3 for any other refusal. Any refusal during the fast phase terminates the preflight
# before the memory phase begins.
#
# During the memory phase, the script sums the container memory limits declared across the rendered
# deployment and compares that total against the memory reported by the engine minus a host
# headroom reservation of 4 GiB. The engine memory figure comes from the engine_memory row emitted
# by sb_preflight, because a desktop engine runs inside a virtual machine with its own allocation
# limit, making host memory the wrong figure to consult. A total above the budget refuses the
# deployment with a diagnostic showing the budget arithmetic and the memory share of each role. The
# phase also refuses any service whose heap is not below its container limit, refuses processor
# counts below PX_PREFLIGHT_MIN_CPUS on either the host or the engine (taken from the engine_cpus
# row), and refuses free disk space below PX_PREFLIGHT_MIN_DISK_MIB under the output root on the
# host. That disk check covers only the bundles and logs stored under PX_OUT rather than the
# internal disk of the engine, and the run class sets both minimum requirements.
#
# The memory sum includes every service in the deployment, including on-demand groups such as the
# YARN tier and the Distributed Pherf tier, because a group started later still adds its container
# limits to the same engine. A deployment that cannot accommodate its on-demand groups is therefore
# refused upfront before its first container starts. Because engine conditions can change between
# initial startup and a later group activation, px-cluster runs the memory phase again before each
# group start through px_memory_recheck.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"
# shellcheck source=SCRIPTDIR/../lib/backend/contract.sh
. "$SELF/../lib/backend/contract.sh"
# shellcheck source=SCRIPTDIR/../lib/preflight.sh
. "$SELF/../lib/preflight.sh"

set -euo pipefail

# Do not enable px_trap_enable here because command substitutions in this script intentionally
# return non-zero exit codes during normal evaluation.

# Parsed service attributes from the scanned deployment are stored in parallel indexed arrays to
# maintain compatibility with bash 3.2, which does not support associative arrays. The count array
# records the number of instances for each service.
PF_S_NAME=()
PF_S_ROLE=()
PF_S_GROUP=()
PF_S_START=()
PF_S_MEM=()
PF_S_HEAP=()
PF_S_COUNT=()

px_cli_spec <<'EOF'
about Checks that the host can hold the rendered deployment, by default
+ $PX_OUT/deploy/<cluster>.
about The fast phase checks that the engine answers, the client is authorised, the
+ versions, and the loopback ports of the port map, from the backend preflight; it
+ stops at the first engine fault. The memory phase checks the sum of the
+ container memory limits against the engine memory less 4 GiB, the heap of each
+ service against its limit, the free disk under PX_OUT against
+ PX_PREFLIGHT_MIN_DISK_MIB and the processors against PX_PREFLIGHT_MIN_CPUS. A
+ dry run asks no backend and checks only the deployment files.
exits 1 also means that the engine did not answer or a figure could not be measured,
+ 2 an HBase release outside the range of the profile, 3 a check refused the
+ deployment, 5 the engine refused the client. The message names the check and
+ its measured value.
example preflight.sh --phase fast
EOF

## @description  Converts a Compose memory size (number[.fraction][k|m|g|t][i][b], base 1024) into
##               a byte count.
## @return       Writes the byte count to stdout and exits with 0, or 1 when outside the grammar.
pf_bytes() {
  local value=$1 whole frac unit mult=1 bytes scale re
  re='^([0-9]+)(\.([0-9]{1,6}))?[[:space:]]?([kKmMgGtT]?)[iI]?[bB]?$'
  [[ $value =~ $re ]] || return "$PX_EXIT_FAIL"
  whole=${BASH_REMATCH[1]}
  frac=${BASH_REMATCH[3]}
  unit=${BASH_REMATCH[4]}
  case "$unit" in
  k | K) mult=1024 ;;
  m | M) mult=$PF_MIB ;;
  g | G) mult=$PF_GIB ;;
  t | T) mult=$((PF_GIB * 1024)) ;;
  esac
  bytes=$((10#$whole * mult))
  if [ -n "$frac" ]; then
    scale=1${frac//?/0}
    bytes=$((bytes + 10#$frac * mult / scale))
  fi
  printf '%s\n' "$bytes"
}

## @description  Strips enclosing quotes or any trailing comment from the YAML scalar stored in the
##               named variable.
## @return       Stores the result in that variable without writing to stdout, and exits with 0.
pf_scalar() {
  local __pf_value=${!1}
  case "$__pf_value" in
  \"*\") __pf_value=${__pf_value#\"}; __pf_value=${__pf_value%\"} ;;
  \'*\') __pf_value=${__pf_value#\'}; __pf_value=${__pf_value%\'} ;;
  *)
    __pf_value=${__pf_value%%[[:space:]]#*}
    px_trim_var __pf_value
    ;;
  esac
  printf -v "$1" '%s' "$__pf_value"
}

## @description  Records one parsed service property (position, key path joined with /, last key,
##               and value) into the parallel service arrays.
## @return       Exits with status 0.
pf_apply() {
  local at=$1 path=$2 key=$3 value=$4 prefix=${PX_LABEL_PREFIX:-px.harness}
  case "$path" in
  mem_limit | deploy/resources/limits/memory) PF_S_MEM[at]=$value ;;
  scale | deploy/replicas) PF_S_COUNT[at]=$value ;;
  labels/*)
    case "$key" in
    "$prefix.role") PF_S_ROLE[at]=$value ;;
    "$prefix.group") PF_S_GROUP[at]=$value ;;
    "$prefix.start") PF_S_START[at]=$value ;;
    esac
    ;;
  environment/PX_HEAP_MIB) PF_S_HEAP[at]=$value ;;
  esac
}

## @description  Populates the PF_S_* arrays by parsing a block-style compose.yaml file structured
##               by indentation depth.
## @return       Exits with 0, or terminates the process with 1 when the file cannot be read.
pf_scan() {
  local file=$1 line content indent key value depth=0 path at=-1 i item
  local inds=() keys=()
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    content=${line#"${line%%[! ]*}"}
    indent=$((${#line} - ${#content}))
    px_trim_var content
    case "$content" in
    '' | '#'*) continue ;;
    esac
    case "$content" in
    '- '* | -)
      # A list item attaches either to the nearest enclosing key with less indentation or to a key
      # at the same indentation level when formatted in compact list form.
      while [ "$depth" -gt 0 ] && [ "${inds[depth - 1]}" -gt "$indent" ]; do
        depth=$((depth - 1))
      done
      [ "$at" -ge 0 ] && [ "$depth" -ge 3 ] && [ "${keys[0]}" = services ] || continue
      item=${content#-}
      px_trim_var item
      pf_scalar item
      case "$item" in
      *=*) ;;
      *) continue ;;
      esac
      path=""
      for ((i = 2; i < depth; i++)); do
        path="$path${path:+/}${keys[i]}"
      done
      pf_apply "$at" "$path/${item%%=*}" "${item%%=*}" "${item#*=}"
      continue
      ;;
    *': '* | *:)
      key=${content%%': '*}
      key=${key%:}
      value=""
      case "$content" in
      *': '*) value=${content#*': '} ;;
      esac
      ;;
    *) continue ;;
    esac
    pf_scalar key
    pf_scalar value
    while [ "$depth" -gt 0 ] && [ "${inds[depth - 1]}" -ge "$indent" ]; do
      depth=$((depth - 1))
    done
    inds[depth]=$indent
    keys[depth]=$key
    depth=$((depth + 1))
    [ "${keys[0]}" = services ] || continue
    if [ "$depth" = 2 ]; then
      at=${#PF_S_NAME[@]}
      PF_S_NAME[at]=$key
      PF_S_COUNT[at]=1
      continue
    fi
    [ "$depth" -ge 3 ] && [ -n "$value" ] || continue
    path=""
    for ((i = 2; i < depth; i++)); do
      path="$path${path:+/}${keys[i]}"
    done
    pf_apply "$at" "$path" "$key" "$value"
  done <"$file"
}

# Evaluates container memory limits, service heap bounds, available disk space, and processor
# counts for the memory verification phase.
## @description  Executes the memory phase across limits, heap bounds, free disk, and processors.
## @return       Writes check rows to stdout and refusal messages to stderr. Exits with 0, or exits
##               with 2 or 1 when pf_scan, pf_backend, or pf_disk terminates the process.
pf_phase_memory() {
  local dir file at n mem bytes heap total=0 roles=() role_total=() role_count=()
  local role_each=() role_note=() role j found note engine budget line
  dir=$PF_DEPLOY
  file="$dir/compose.yaml"
  if [ ! -f "$file" ]; then
    pf_unmeasured memory_limits "no rendered deployment at $dir: render it, or name it" \
      "with --deployment or PX_DEPLOYMENT_DIR"
    return 0
  fi
  pf_scan "$file"
  n=${#PF_S_NAME[@]}
  if [ "$n" = 0 ]; then
    pf_unmeasured memory_limits "$file declares no service"
    return 0
  fi
  for ((at = 0; at < n; at++)); do
    role=${PF_S_ROLE[at]:-${PF_S_NAME[at]}}
    if [ -z "${PF_S_MEM[at]:-}" ]; then
      pf_unmeasured memory_limits "service ${PF_S_NAME[at]} ($role) has no memory limit"
      continue
    fi
    if ! mem="$(pf_bytes "${PF_S_MEM[at]}")"; then
      pf_unmeasured memory_limits \
        "service ${PF_S_NAME[at]} ($role) has an unreadable memory limit '${PF_S_MEM[at]}'"
      continue
    fi
    if ! [[ ${PF_S_COUNT[at]} =~ $PX_RE_INT ]]; then
      pf_unmeasured memory_limits \
        "service ${PF_S_NAME[at]} ($role) has an unreadable count '${PF_S_COUNT[at]}'"
      continue
    fi
    heap=${PF_S_HEAP[at]:-}
    if [ -n "$heap" ]; then
      if ! [[ $heap =~ $PX_RE_INT ]]; then
        pf_check heap_bound fail "$role: the heap '$heap' is not a whole number of MiB"
      elif [ $((10#$heap * PF_MIB)) -ge "$mem" ]; then
        pf_check heap_bound fail "$role: the heap of $heap MiB is not below the" \
          "container limit of $(pf_mib "$mem") (service ${PF_S_NAME[at]})"
      fi
    fi
    bytes=$((mem * 10#${PF_S_COUNT[at]}))
    total=$((total + bytes))
    note=""
    if [ "${PF_S_START[at]:-}" = on-demand ]; then
      note=", group ${PF_S_GROUP[at]:-none} starts on demand"
    fi
    found=-1
    for ((j = 0; j < ${#roles[@]}; j++)); do
      if [ "${roles[j]}" = "$role" ]; then
        found=$j
        break
      fi
    done
    if [ "$found" = -1 ]; then
      found=${#roles[@]}
      roles[found]=$role
      role_total[found]=0
      role_count[found]=0
      role_each[found]=$mem
      role_note[found]=$note
    fi
    role_total[found]=$((role_total[found] + bytes))
    role_count[found]=$((role_count[found] + 10#${PF_S_COUNT[at]}))
    if [ "${role_each[found]}" != "$mem" ]; then
      role_each[found]=""
    fi
  done
  if [ "$PF_UNMEASURED" != 0 ]; then
    return 0
  fi
  for ((j = 0; j < ${#roles[@]}; j++)); do
    if [ -n "${role_each[j]}" ]; then
      line="${role_count[j]} x $(pf_mib "${role_each[j]}") = $(pf_mib "${role_total[j]}")"
    else
      line="${role_count[j]} instances = $(pf_mib "${role_total[j]}")"
    fi
    pf_check "memory:${roles[j]}" ok "$line${role_note[j]}"
  done
  if px_is_dry_run; then
    pf_check memory_fit warn "dry run: the limits sum to $(pf_size "$total"), and" \
      "the engine was not asked for its memory"
  else
    pf_backend
    if ! engine="$(pf_engine_figure engine_memory)"; then
      pf_unmeasured memory_fit "the backend reported no engine memory total, so the" \
        "limits of $(pf_size "$total") cannot be checked"
    else
      budget="$(pf_budget "$engine")"
      line="the budget $(pf_size "$budget") is the engine $(pf_size "$engine")"
      line="$line less the headroom $(pf_size "$PF_HEADROOM_BYTES")"
      if [ "$total" -gt "$budget" ]; then
        pf_check memory_fit fail "the limits $(pf_size "$total") exceed the budget" \
          "by $(pf_size $((total - budget))); $line"
        px_error "the deployment $dir does not fit the engine. The limits" \
          "$(pf_size "$total") exceed the budget by $(pf_size $((total - budget)));" \
          "$line. The limits per role:"
        for ((j = 0; j < ${#roles[@]}; j++)); do
          px_error "  ${roles[j]}: ${role_count[j]} instance(s)," \
            "$(pf_mib "${role_total[j]}")${role_note[j]}"
        done
        px_error "use the reduced validation class or a per-role override, or give the" \
          "engine more memory"
      else
        pf_check memory_fit ok "the limits $(pf_size "$total") fit the budget with" \
          "$(pf_size $((budget - total))) to spare; $line"
      fi
    fi
  fi
  pf_disk
  pf_cpus
}

# Compares PX_PREFLIGHT_MIN_CPUS against the lesser of the host and engine processor counts, or
# against the host processor count alone when the engine reports no count.
## @description  Verifies that the available processor count satisfies PX_PREFLIGHT_MIN_CPUS.
## @return       Writes one check row to stdout and exits with 0, or exits with 2 when the
##               configured requirement is malformed.
pf_cpus() {
  local host engine have need detail
  host="$(px_nproc)"
  need="$(px_int_setting PX_PREFLIGHT_MIN_CPUS 2 0)" || exit $?
  have=$host
  if engine="$(pf_engine_figure engine_cpus)"; then
    [ "$engine" -ge "$host" ] || have=$engine
    detail="host $host, engine $engine"
  else
    detail="host $host, the engine reported none"
  fi
  if [ "$have" -lt "$need" ]; then
    pf_check cpus fail "processors: $have ($detail), below the $need that the deployment" \
      "requires"
  else
    pf_check cpus ok "processors: $have ($detail), $need required"
  fi
}

pf_cli "$@"

px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}
# Configuring a component version outside the range supported by its compatibility module is
# treated as a usage error regardless of the host resources available.
px_versions_check

# Load the port map from any deployment directory specified on the command line here, because the
# configuration loader only knew PX_DEPLOYMENT_DIR. Because the port map is the weakest precedence
# stage, loading it at this point preserves the configuration precedence order.
PF_DEPLOY="$(pf_deploy_dir deploy)" || exit $?
if [ -d "$PF_DEPLOY" ]; then
  px_config_load_ports "$PF_DEPLOY"
fi

if pf_phase_wanted fast; then
  pf_phase_fast
fi
# Because the memory phase is slower, any refusal during the fast phase terminates the preflight
# early so that an absent engine or a busy port is reported immediately.
if pf_phase_wanted memory; then
  pf_phase_memory
fi

pf_finish
