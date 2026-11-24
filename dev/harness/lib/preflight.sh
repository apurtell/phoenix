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

# Shared host preflight library sourced by both preflight entrypoints: scripts/preflight.sh for the
# Compose backend and scripts/preflight-k8s.sh for the Kubernetes backend. Centralizing these
# routines ensures that both backends share one check-row emitter, one backend preflight call, one
# capacity-figure reader, and one exit-code policy so that preflight rules change in a single place.
#
# This module encapsulates the backend-independent stages of a preflight check: emitting check rows
# and tracking refusal codes, resolving the deployment directory, invoking sb_preflight once and
# evaluating its fast phase, extracting numeric figures from backend rows, verifying free disk
# space under PX_OUT, parsing shared command-line options, and terminating with the resolved exit
# status. It never directly names or invokes a container runtime or Kubernetes CLI tool.

# This file is a shell library and must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/preflight.sh is a library. Source it from a preflight.\n' >&2
  exit 2
fi

if [ -n "${PF_KIT_LOADED:-}" ]; then
  return 0
fi
PF_KIT_LOADED=1

PF_KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/common.sh
. "$PF_KIT_DIR/common.sh"
# shellcheck source=SCRIPTDIR/backend/contract.sh
. "$PF_KIT_DIR/backend/contract.sh"

# Fixed host memory headroom reserved by the container engine outside all container memory limits.
# This reservation is an architectural constant rather than a configurable parameter.
PF_HEADROOM_BYTES=4294967296
PF_MIB=1048576
PF_GIB=1073741824

PF_PHASE=""
PF_DIR=""
PF_REFUSED=0
PF_REASON=""
PF_UNMEASURED=0
PF_SB_DONE=0
PF_SB_ROWS=""
PF_SB_STATUS=0

## @description  Emits a preflight check row (name, status, detail...), recording exit code 3 on
##               the first fail row to refuse the deployment.
## @return       Writes one tab-separated check row to stdout and exits 0.
pf_check() {
  local name=$1 status=$2
  shift 2
  if [ "$status" = fail ] && [ "$PF_REFUSED" = 0 ]; then
    PF_REFUSED=$PX_EXIT_ANOMALY
    PF_REASON="$name: $*"
  fi
  px_tsv_row "$name" "$status" "$(px_tsv_clean "$*")"
}

## @description  Emits a fail check row (name, detail...) for a capacity figure that could not be
##               measured, marking the preflight to exit 1 rather than refusal code 3.
## @return       Writes one tab-separated check row to stdout and exits 0.
pf_unmeasured() {
  local name=$1
  shift
  PF_UNMEASURED=1
  px_tsv_row "$name" fail "$(px_tsv_clean "$*")"
}

## @description  Formats a byte count in MiB, rounded down to the nearest whole mebibyte.
## @return       Writes the formatted MiB figure to stdout and exits 0.
pf_mib() { printf '%s MiB\n' "$(($1 / PF_MIB))"; }

## @description  Formats a byte count in MiB (rounded down) together with its GiB equivalent
##               rounded to two decimal places.
## @return       Writes the formatted size figure to stdout and exits 0.
pf_size() {
  local hundredths=$((($1 * 100 + PF_GIB / 2) / PF_GIB))
  printf '%s MiB (%s.%02d GiB)\n' "$(($1 / PF_MIB))" "$((hundredths / 100))" \
    "$((hundredths % 100))"
}

# Precedence order: --deployment (PF_DIR), PX_DEPLOYMENT_DIR, the active deployment record from
# sb_deployment_record, or $PX_OUT/<SUBDIR>/<cluster> (where SUBDIR defaults to deploy).
## @description  Resolves the target deployment directory for the preflight check.
## @return       Writes the deployment directory path to stdout and exits 0; exits 2 if PX_OUT is
##               refused by the output policy, or 1 on resolution failure.
pf_deploy_dir() {
  local root record recorded=""
  if [ -n "${PF_DIR:-${PX_DEPLOYMENT_DIR:-}}" ]; then
    printf '%s\n' "${PF_DIR:-$PX_DEPLOYMENT_DIR}"
    return 0
  fi
  record="$(sb_deployment_record 2>/dev/null)" && [ -f "$record" ] &&
    { IFS= read -r recorded <"$record" || true; }
  if [ -n "$recorded" ]; then
    printf '%s\n' "$recorded"
    return 0
  fi
  root="$(px_out_root)" || return $?
  printf '%s/%s/%s\n' "$root" "${1:-deploy}" "${PX_CLUSTER_ID:-default}"
}

# Invokes sb_preflight at most once against the recorded cluster scope (falling back to a dry-run
# scope when no active scope is recorded) and caches the output rows in PF_SB_ROWS.
## @description  Executes the substrate backend preflight once and caches its check rows.
## @return       Exits 0 on success, 2 or 1 if no backend binds or no scope can be named, or 2 on
##               an invalid backend preflight invocation.
pf_backend() {
  local scope="" status=0
  [ "$PF_SB_DONE" = 0 ] || return 0
  PF_SB_DONE=1
  sb_load "${PX_BACKEND:-compose}"
  scope="$(sb_scope_resolve)" || status=$?
  if [ "$status" = "$PX_EXIT_NO_OBJECT" ]; then
    status=0
    scope="$(PX_DRY_RUN=1 sb_scope_create "${PX_CLUSTER_ID:-default}" 2>/dev/null)" ||
      status=$?
  fi
  if [ "$status" = 0 ] && [ -z "$scope" ]; then
    status=$PX_EXIT_FAIL
  fi
  if [ "$status" != 0 ]; then
    px_die "$status" "cannot name a scope for cluster ${PX_CLUSTER_ID:-default} to ask the backend"
  fi
  PF_SB_ROWS="$(sb_preflight "$scope")" || PF_SB_STATUS=$?
  if [ "$PF_SB_STATUS" = "$PX_EXIT_USAGE" ]; then
    px_die "$PX_EXIT_USAGE" "the backend preflight refused its arguments"
  fi
}

# Emits the cached backend preflight rows unchanged and derives the refusal status from the first
# failed row: 1 when the engine binary is missing or unresponsive, 5 when the engine refuses client
# credentials, or 3 for any other host refusal.
## @description  Executes the fast preflight phase by replaying the backend preflight check rows.
## @return       Writes check rows to stdout and exits 0, or exits 2 or 1 if pf_backend fails.
pf_phase_fast() {
  local row status_col name_col detail_col status name counted=0
  if px_is_dry_run; then
    pf_check preflight warn "dry run, the backend preflight was not called"
    return 0
  fi
  pf_backend
  status_col="$(sb_schema_index check status)"
  name_col="$(sb_schema_index check check)"
  detail_col="$(sb_schema_index check detail)"
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    printf '%s\n' "$row"
    status="$(px_tsv_field "$status_col" "$row")" || status=""
    [ "$status" = fail ] || continue
    counted=1
    [ "$PF_REFUSED" = 0 ] || continue
    name="$(px_tsv_field "$name_col" "$row")" || name=""
    # Callers branch on the resulting exit code: an unresponsive or missing engine (1) may recover,
    # rejected credentials (5) require operator intervention, and a host resource refusal (3)
    # requires a smaller deployment footprint or freeing a contended port.
    case "$name" in
    engine_binary | engine_responsive) PF_REFUSED=$PX_EXIT_FAIL ;;
    engine_credentials) PF_REFUSED=$PX_EXIT_UNSUPPORTED ;;
    *) PF_REFUSED=$PX_EXIT_ANOMALY ;;
    esac
    PF_REASON="$name: $(px_tsv_field "$detail_col" "$row" || true)"
  done <<EOF
$PF_SB_ROWS
EOF
  if [ "$PF_SB_STATUS" != 0 ] && [ "$counted" = 0 ]; then
    pf_check preflight fail \
      "the backend preflight reported $(sb_exit_name "$PF_SB_STATUS") and no failed row"
  fi
}

## @description  Extracts the leading integer figure from the detail field of a passing backend
##               check row (NAME, such as engine_memory or engine_cpus).
## @return       Writes the number to stdout and exits 0, or returns 6 if no ok row is present.
pf_engine_figure() {
  local row name_col status_col detail_col detail
  name_col="$(sb_schema_index check check)"
  status_col="$(sb_schema_index check status)"
  detail_col="$(sb_schema_index check detail)"
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    [ "$(px_tsv_field "$name_col" "$row")" = "${1:-}" ] || continue
    [ "$(px_tsv_field "$status_col" "$row")" = ok ] || continue
    detail="$(px_tsv_field "$detail_col" "$row")" || continue
    detail=${detail%%[!0-9]*}
    [ -n "$detail" ] || continue
    printf '%s\n' "$detail"
    return 0
  done <<EOF
$PF_SB_ROWS
EOF
  return "$PX_EXIT_NO_OBJECT"
}

## @description  Verifies free host disk space under the output root (field 4 of line 2 from
##               df -Pk) against PX_PREFLIGHT_MIN_DISK_MIB.
## @return       Writes one check row to stdout and exits 0, or exits 2 or 1 if px_out_root fails.
pf_disk() {
  local root kib="" need
  root="$(px_out_root)" || exit $?
  need="$(px_int_setting PX_PREFLIGHT_MIN_DISK_MIB 4096 0)" || exit $?
  read -r _ _ _ kib _ <<<"$(df -Pk "$root" 2>/dev/null | sed -n 2p)" || kib=""
  if ! [[ $kib =~ $PX_RE_INT ]]; then
    pf_check disk_free warn "the free disk under $root on the host is unknown"
  elif [ $((kib / 1024)) -lt "$need" ]; then
    pf_check disk_free fail "disk: $(pf_size $((kib * 1024))) free under $root on the host," \
      "below the $need MiB that the deployment requires"
  else
    pf_check disk_free ok "$(pf_size $((kib * 1024))) free under $root on the host," \
      "$need MiB required"
  fi
}

## @description  Registers the command-line option declarations shared by both preflight scripts
##               in PX_CLI_SPEC.
## @return       Completes option registration and exits with status 0.
pf_cli_spec() {
  px_cli_spec <<'EOF'
opt --phase PHASE PF_PHASE | fast or memory; with no --phase both run, the fast
+ phase first, and a fast refusal skips the memory
+ phase
opt --deployment DIR PF_DIR | the rendered deployment directory, default
+ PX_DEPLOYMENT_DIR, else the directory that
+ px-cluster up recorded
output check rows: check, status (ok, fail or warn) and detail.
EOF
}

## @description  Parses the command-line arguments (argument...) of a preflight script and
##               populates PF_PHASE, PF_DIR, and PX_CLI_FILES.
## @return       Exits 0 on success, or exits 2 on a command-line usage error.
pf_cli() {
  pf_cli_spec
  px_cli_parse "$@"
  case "$PF_PHASE" in
  '' | fast | memory) ;;
  *) px_die "$PX_EXIT_USAGE" "--phase is fast or memory, got '$PF_PHASE'" ;;
  esac
}

## @description  Determines whether a preflight phase (fast or memory) should execute: all phases
##               run when --phase is omitted, and memory runs only if fast recorded no refusal.
## @return       Exits 0 when the phase should run, or returns 1 otherwise.
pf_phase_wanted() {
  [ -z "$PF_PHASE" ] || [ "$PF_PHASE" = "$1" ] || return 1
  [ "$1" = fast ] || [ "$PF_REFUSED" = 0 ]
}

## @description  Computes the allocatable engine memory budget (engine bytes, optional allowance in
##               bytes defaulting to 0) by subtracting the host headroom and allowance.
## @return       Writes the available budget in bytes to stdout and exits 0.
pf_budget() { printf '%s\n' "$((${1:-0} - PF_HEADROOM_BYTES - ${2:-0}))"; }

## @description  Terminates the preflight with the first recorded refusal exit code, or 1 if any
##               required capacity figure could not be measured, or 0 when all checks pass.
## @return       Exits 0, 1, 3, or 5.
pf_finish() {
  if [ "$PF_REFUSED" != 0 ]; then
    px_error "the preflight refused the deployment: $PF_REASON"
    exit "$PF_REFUSED"
  fi
  if [ "$PF_UNMEASURED" != 0 ]; then
    px_error "the preflight could not measure a figure, see the failed rows"
    exit "$PX_EXIT_FAIL"
  fi
  exit 0
}
