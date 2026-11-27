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

# This script implements preflight verification for the Kubernetes backend. It exposes the same two
# verification phases, command-line options, and exit codes as scripts/preflight.sh, and shares the
# common preflight library in lib/preflight.sh. Before running either phase, it rejects any HBase
# release that falls outside the version range supported by the active profile.
#
# During the fast phase, the script delegates target cluster checks to the Kubernetes backend
# preflight entrypoint, sb_preflight of k8s, which verifies kubectl and its version, the API server
# and its version, client authorization rights, the absence of any other harness deployment on the
# target, the StorageClass, and the pull secret. The script emits those rows unchanged. When
# bin/px-k3d created the cluster for the active context, the fast phase also emits the k3d rows
# from px-k3d check covering k3d and its version. Any k3d context whose cluster was not created by
# px-k3d is refused because every node in such a cluster reports the full memory of the container
# engine, meaning only the px-k3d budget can hold the deployment within the available host memory.
#
# During the memory phase, the script sums the resource requests of every workload in the rendered
# deployment, including on-demand groups, directly from requests.tsv in the rendered output so that
# it does not need to parse manifests. It then compares those totals against the target figures:
#   - total memory and CPU requests against the allocatable memory and CPU of the nodes selected by
#     PX_K8S_NODE_SELECTOR;
#   - the largest pod request against the largest selected node;
#   - the demand for each resource against every ResourceQuota in the namespace. A full preflight
#     compares demand against the hard limit minus the used share, whereas a --phase memory recheck
#     runs while the deployment is already up and counted in the used share, and therefore compares
#     demand directly against the hard limit;
#   - on a local cluster created by px-k3d, the memory requests against the container engine memory
#     minus the 4 GiB host headroom reservation and minus the k3s allowance PX_K3S_ALLOWANCE_MIB.
# Because pod CPU requests are explicit, PX_PREFLIGHT_MIN_CPUS does not apply. Available disk space
# under PX_OUT is verified in the same way as for the Compose backend.
#
# This script binds the Kubernetes backend. Because a single process can bind only one backend,
# container engine figures are retrieved from a child process, px-k3d check, which binds the
# Compose backend.

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

# These variables cache the exit status and output rows from a single run of px-k3d check.
PFK_K3D_DONE=0
PFK_K3D_STATUS=0
PFK_K3D_ROWS=""

px_cli_spec <<'EOF'
about Checks that the target cluster of PX_K8S_CONTEXT can hold the rendered
+ deployment, by default $PX_OUT/deploy-k8s/<cluster>.
about The fast phase checks the client and its version, the API server and its
+ version, the rights of the client, no other harness deployment, the
+ StorageClass, the pull secret, the Istio injector of a meshed deployment,
+ and the rows of px-k3d check for a cluster of px-k3d; a k3d-* context that
+ px-k3d did not create fails. The memory phase checks the requests of the
+ deployment against the allocatable memory and CPU of the selected nodes, the
+ largest pod against the largest node, the ResourceQuotas of the namespace, the
+ engine memory of a local cluster less 4 GiB and the k3s allowance, and the
+ free disk under PX_OUT. A dry run asks no backend and checks only the
+ deployment files.
exits 1 also means that the API server did not answer or a figure could not be
+ measured, 2 an HBase release outside the range of the profile or a local
+ cluster without PX_K3S_ALLOWANCE_MIB, 3 a check refused the deployment, 5 the
+ API server refused the client. The message names the check and its measured
+ value.
example preflight-k8s.sh --phase fast
EOF

# Executes px-k3d check at most once for the target context and caches the exit status and output
# rows in PFK_K3D_STATUS and PFK_K3D_ROWS. Status 6 indicates that px-k3d did not create that
# cluster, as px-k3d persists a state record for each cluster it provisions and check itself can
# execute prior to cluster creation.
## @description  Invokes px-k3d check once for the target context and caches its status and rows.
## @return       Exits with status 0.
pfk_k3d() {
  local err record
  [ "$PFK_K3D_DONE" = 0 ] || return 0
  PFK_K3D_DONE=1
  record="$(sb_record_path px-k3d scope "${PX_K8S_CONTEXT:-}")" || record=""
  if [ -z "$record" ] || [ ! -f "$record" ]; then
    PFK_K3D_STATUS=$PX_EXIT_NO_OBJECT
    return 0
  fi
  err="$(mktemp "${TMPDIR:-/tmp}/px-k3d.XXXXXX")" || err=/dev/null
  PFK_K3D_ROWS="$(px_with_private "$PX_HOME/bin/px-k3d" -e "PX_K8S_CONTEXT=${PX_K8S_CONTEXT:-}" \
    check 2>"$err")" || PFK_K3D_STATUS=$?
  # A context whose cluster was not created by px-k3d is the normal case for a remote target, so a
  # status 6 response suppresses diagnostic output.
  [ "$PFK_K3D_STATUS" = "$PX_EXIT_NO_OBJECT" ] || cat "$err" >&2
  [ "$err" = /dev/null ] || rm -f "$err"
}

## @description  Reports whether px-k3d created the target context cluster (check status 0 or 3).
## @return       Exits with 0 when px-k3d created the cluster, or 1 otherwise.
pfk_k3d_local() { [ "$PFK_K3D_STATUS" = 0 ] || [ "$PFK_K3D_STATUS" = "$PX_EXIT_ANOMALY" ]; }

# Evaluates a single capacity check (name, demand, limit, unit, detail) and marks the row as fail
# when demand exceeds the limit. A unit of bytes formats both figures as human-readable sizes,
# whereas any other unit is appended directly after each figure.
## @description  Emits one capacity fit row comparing resource demand against an available limit.
## @return       Writes one check row to stdout and exits with status 0.
pfk_fit() {
  local status=ok demand="$2 $4" limit="$3 $4"
  [ "$2" -le "$3" ] || status=fail
  if [ "$4" = bytes ]; then
    demand="$(pf_size "$2")" limit="$(pf_size "$3")"
  fi
  pf_check "$1" "$status" "$demand against $limit: $5"
}

## @description  Extracts a numeric figure by row name from the cached px-k3d check output rows.
## @return       Writes the number to stdout and exits with 0, or exits with 6 when no ok row
##               reports that figure.
pfk_k3d_figure() {
  PF_SB_ROWS=$PFK_K3D_ROWS pf_engine_figure "$1"
}

# Executes the fast preflight phase by emitting the backend preflight rows followed by the k3d
# check rows for a local cluster created by px-k3d, or a failed check row when a k3d context was
# not created by px-k3d.
## @description  Executes the fast preflight phase for the Kubernetes target and any k3d cluster.
## @return       Writes check rows to stdout and exits with 0, or exits with 2 or 1 as pf_backend
##               does.
pfk_phase_fast() {
  local name status detail text
  pf_phase_fast
  if [ "$PF_REFUSED" != 0 ] || px_is_dry_run; then
    return 0
  fi
  pfk_k3d
  if ! pfk_k3d_local; then
    case "$PFK_K3D_STATUS" in
    "$PX_EXIT_NO_OBJECT")
      case "${PX_K8S_CONTEXT:-}" in
      k3d-*)
        pf_check k3d_cluster fail "px-k3d did not create the cluster of the k3d-* context" \
          "$PX_K8S_CONTEXT, so no budget holds the deployment to the memory of the engine:" \
          "create the cluster with px-k3d create"
        ;;
      esac
      ;;
    "$PX_EXIT_FAIL")
      text="px-k3d check did not answer for context ${PX_K8S_CONTEXT:-}"
      pf_check k3d_binary warn "$text"
      PF_REFUSED=$PX_EXIT_FAIL PF_REASON=$text
      ;;
    *) px_die "$PFK_K3D_STATUS" "px-k3d check refused its arguments" ;;
    esac
    return 0
  fi
  while IFS="$PX_TAB" read -r name status detail; do
    case "$name" in
    k3d_binary | k3d_version) pf_check "$name" "$status" "$detail" ;;
    esac
  done <<EOF
$PFK_K3D_ROWS
EOF
}

# Parses a quota row detail string formatted as "H hard, U used, quota ..." into two whitespace-
# separated integers representing the hard limit and the used share.
## @description  Extracts the hard limit and used share from a backend quota row detail string.
## @return       Writes "H U" to stdout and exits with 0, or exits with 1 when the detail contains
##               no valid figures.
pfk_quota_figures() {
  local hard used
  hard=${1%% hard,*}
  used=${1#* hard, }
  used=${used%% used*}
  [[ $hard =~ $PX_RE_INT ]] && [[ $used =~ $PX_RE_INT ]] || return "$PX_EXIT_FAIL"
  printf '%s %s\n' "$hard" "$used"
}

# Compares each quota row reported by the backend against the deployment demand for its resource
# class (memory bytes, cpu millicores, pods, claims, or storage bytes), reading DEMAND_ROWS lines
# formatted as "class demand".
## @description  Verifies every backend quota row against the deployment demand for its class.
## @return       Writes check rows to stdout and exits with status 0.
pfk_quotas() {
  local demands=$1 name status detail class demand figures hard used limit what unit
  while IFS="$PX_TAB" read -r name status detail; do
    case "$name" in
    quota:*) ;;
    *) continue ;;
    esac
    class=${name#quota:}
    demand="$(printf '%s\n' "$demands" | awk -v c="$class" '$1 == c { print $2 }')"
    [ -n "$demand" ] || continue
    if ! figures="$(pfk_quota_figures "$detail")"; then
      pf_unmeasured "quota_fit:$class" "the quota row holds no figures: $detail"
      continue
    fi
    read -r hard used <<<"$figures"
    what=${detail#* used, }
    if [ "$PF_PHASE" = memory ]; then
      # Because the running deployment is already counted in the used share, a memory recheck
      # during a group start or scale operation compares total demand directly against the hard
      # limit.
      limit=$hard
      detail="the hard limit of $what"
    else
      limit=$((hard - used))
      detail="the hard limit less the used share $used of $what"
    fi
    case "$class" in
    memory | storage) unit=bytes ;;
    cpu) unit=millicores ;;
    *) unit=$class ;;
    esac
    pfk_fit "quota_fit:$class" "$demand" "$limit" "$unit" "the demand of the deployment and $detail"
  done <<EOF
$PF_SB_ROWS
EOF
}

# Evaluates the resource requests from requests.tsv against the selected nodes, the largest
# selected node, the namespace quotas, and the container engine memory of a local cluster, then
# checks free disk space.
## @description  Executes the memory phase across node capacity, quotas, engine memory, and disk.
## @return       Writes check rows to stdout and exits with 0, with 2 when the k3s allowance is
##               missing, or with 2 or 1 as pf_disk does.
pfk_phase_memory() {
  local file="$PF_DEPLOY/requests.tsv" kind name count cpu mem claims gib total=0 milli=0
  local pods=0 pvcs=0 storage=0 largest=0 largest_name="" lines="" engine node budget line
  local allowance
  if [ ! -f "$file" ]; then
    pf_unmeasured memory_requests "no rendered deployment at $PF_DEPLOY: render it, or name" \
      "it with --deployment or PX_DEPLOYMENT_DIR"
    return 0
  fi
  while IFS="$PX_TAB" read -r kind name count cpu mem claims gib; do
    case "$kind" in
    '' | '#'*) continue ;;
    esac
    for line in "$count" "$cpu" "$mem" "$claims" "$gib"; do
      [[ $line =~ $PX_RE_INT ]] && continue
      pf_unmeasured memory_requests "the request row of $kind $name is unreadable in $file"
      return 0
    done
    total=$((total + count * mem * PF_MIB))
    milli=$((milli + count * cpu))
    pods=$((pods + count))
    pvcs=$((pvcs + count * claims))
    storage=$((storage + count * claims * gib * PF_GIB))
    if [ $((mem * PF_MIB)) -gt "$largest" ]; then
      largest=$((mem * PF_MIB)) largest_name=$name
    fi
    lines="$lines$name: $count x $mem MiB, $cpu millicores$PX_NL"
    pf_check "memory:$name" ok "$count x $mem MiB = $((count * mem)) MiB, $((count * cpu))" \
      "millicores"
  done <"$file"
  if [ "$pods" = 0 ]; then
    pf_unmeasured memory_requests "$file holds no workload"
    return 0
  fi
  if px_is_dry_run; then
    pf_check memory_fit warn "dry run: the requests sum to $(pf_size "$total") and" \
      "$milli millicores, and the target was not asked"
    pf_disk
    return 0
  fi
  pf_backend
  if ! engine="$(pf_engine_figure engine_memory)"; then
    pf_unmeasured memory_fit "the target reported no allocatable memory, so the requests of" \
      "$(pf_size "$total") cannot be checked"
  else
    pfk_fit memory_fit "$total" "$engine" bytes \
      "the memory requests and the allocatable memory of the selected nodes"
    if [ "$total" -gt "$engine" ]; then
      px_error "the deployment $PF_DEPLOY does not fit the nodes. The requests" \
        "$(pf_size "$total") exceed the allocatable memory $(pf_size "$engine") by" \
        "$(pf_size $((total - engine))). The requests per workload:"
      printf '%s' "$lines" | while IFS= read -r line; do px_error "  $line"; done
    fi
  fi
  if node="$(pf_engine_figure node_memory)"; then
    pfk_fit pod_fit "$largest" "$node" bytes \
      "the largest pod $largest_name and the largest selected node"
  fi
  if engine="$(pf_engine_figure engine_cpus)"; then
    pfk_fit cpu_fit "$milli" "$engine" millicores \
      "the CPU requests and the allocatable CPU of the selected nodes"
  fi
  line="memory $total${PX_NL}cpu $milli${PX_NL}pods $pods"
  pfk_quotas "$line${PX_NL}claims $pvcs${PX_NL}storage $storage"
  pfk_k3d
  if pfk_k3d_local && engine="$(pfk_k3d_figure engine_memory)"; then
    allowance=${PX_K3S_ALLOWANCE_MIB:-}
    if ! [[ $allowance =~ $PX_RE_INT ]]; then
      px_die "$PX_EXIT_USAGE" "a local cluster of px-k3d needs PX_K3S_ALLOWANCE_MIB, the" \
        "memory of k3s, which k8s/env/k3d.env sets"
    fi
    budget="$(pf_budget "$engine" $((allowance * PF_MIB)))"
    line="the memory requests and the budget, the engine $(pf_size "$engine") less the"
    line="$line headroom $(pf_size "$PF_HEADROOM_BYTES") less the k3s allowance $allowance MiB"
    pfk_fit k3d_memory_fit "$total" "$budget" bytes "$line of PX_K3S_ALLOWANCE_MIB"
  fi
  pf_disk
}

pf_cli "$@"

# Because this script is dedicated to the Kubernetes backend, it binds PX_BACKEND at the highest
# precedence tier so that the configuration loader reads k8s/env/k8s.env.
px_config_set PX_BACKEND k8s cli
px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}
px_versions_check

PF_DEPLOY="$(pf_deploy_dir deploy-k8s)" || exit $?
# The rendered deployment manifests—rather than the active configuration of this preflight
# process—determine whether the service mesh gate runs, because pods join the mesh solely when the
# mesh overlay applies its injection label to the namespace.
if [ -d "$PF_DEPLOY" ]; then
  px_config_load_ports "$PF_DEPLOY"
  PX_K8S_MESH=0
  if grep -qs '^    istio-injection: "enabled"$' "$PF_DEPLOY/manifests/00-namespace.yaml"; then
    PX_K8S_MESH=1
  fi
  export PX_K8S_MESH
fi

if pf_phase_wanted fast; then
  pfk_phase_fast
fi
if pf_phase_wanted memory; then
  pfk_phase_memory
fi

pf_finish
