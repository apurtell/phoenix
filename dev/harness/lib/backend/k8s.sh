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

# This module provides the Kubernetes backend implementation for the substrate contract. It
# fulfills all 32 sb_* contract functions using kubectl and serves as the sole module in this
# repository permitted to invoke kubectl.
#
# Five fundamental Kubernetes characteristics govern the design of this backend:
#   1. Because a deployment scope corresponds to a namespace on a target cluster, the scope
#      identifier takes the form <context>/<namespace>. This identifier alone specifies both the
#      target cluster and the namespace, allowing a command running in a fresh shell to determine
#      which cluster it operates on without consulting a local state record. The cluster context is
#      drawn exclusively from PX_K8S_CONTEXT; the backend never falls back to the active context in
#      the kubeconfig file.
#   2. Every kubectl invocation explicitly passes the context and the namespace, and every query
#      that selects harness resources includes the cluster label selector. Only cluster-wide target
#      properties (such as server version, caller permissions, and node inventory) are queried
#      without a label selector.
#   3. Kubernetes controllers automatically restart containers and recreate pods. A StatefulSet
#      recreates any pod that is deleted, while the kubelet restarts any container that exits.
#      Consequently, restart evidence is derived from both the container restart count and the pod
#      uid, while the previous container termination state remains accessible for inspection.
#   4. Rendered deployment manifests are completely static. The backend applies rendered manifest
#      files only after validating each file with a client-side dry run, and it mutates cluster
#      resources solely via apply and delete operations or by invoking kubectl scale on the
#      Distributed Pherf tier agent Deployment.
#   5. Instance readiness is determined by the pod Ready condition, which is driven by the
#      readiness probe configured in the container image. Any expected pod that a controller has
#      not yet created is treated as pending, ensuring that a readiness barrier invoked immediately
#      after an apply operation cannot succeed prematurely when no pods exist yet.
#
# All kubectl executions flow through a single entry point, px_k8s_call. In dry-run mode, no state
# is modified: any function that creates, deletes, stops, or copies resources logs the intended
# operation and returns 0.

# This file is a library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/backend/k8s.sh is a library. Source it with sb_load.\n' >&2
  exit 2
fi

if [ -n "${PX_K8S_LOADED:-}" ]; then
  return 0
fi
PX_K8S_LOADED=1

PX_K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/contract.sh
. "$PX_K8S_DIR/contract.sh"

# ---------------------------------------------------------------------------
# 1. Backend identity and constants
# ---------------------------------------------------------------------------
# The minimum supported Kubernetes API version is 1.28: pods must be able to resolve bare peer
# names across more than six DNS search domains, and StatefulSet pods must expose their ordinal via
# the apps.kubernetes.io/pod-index label. Both are enabled by default and stable from 1.28 on.
PX_K8S_API_FLOOR=1.28

# A Kubernetes namespace must conform to the DNS label grammar, and every scope identifier must
# terminate with a valid namespace name.
PX_K8S_NAMESPACE_RE='^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'

# Container waiting reasons that indicate a fatal configuration or image fault that cannot recover
# without modifying the deployment. The readiness barrier aborts immediately upon observing any of
# these states, with the exception of the transient CreateContainerConfigError identified by
# PX_K8S_CONFIG_LAG.
PX_K8S_BAD_WAITING=" CrashLoopBackOff ErrImageNeverPull ErrImagePull ImagePullBackOff"
PX_K8S_BAD_WAITING="$PX_K8S_BAD_WAITING InvalidImageName CreateContainerConfigError "

# One specific CreateContainerConfigError condition is transient: immediately after a pod is bound
# to a node, the node authorizer may temporarily deny the kubelet access to a referenced ConfigMap,
# prompting the kubelet to retry container startup. That transient condition produces a waiting
# message containing this substring, and the readiness barrier treats the pod as pending.
PX_K8S_CONFIG_LAG='failed to sync configmap cache'

# Remote shell scripts used to deliver an immediate SIGKILL or a graceful SIGTERM inside a
# container. Because the Linux kernel ignores SIGKILL sent to PID 1 from within the same PID
# namespace, a hard kill must target all child processes of PID 1, causing tini to exit with status
# 137. Because tini forwards SIGTERM to its child processes, a graceful termination sends SIGTERM
# directly to PID 1. The script exits with status 6 when no child process is running under PID 1.
# Assigning the script via read prevents any shell expansion in the host environment.
PX_K8S_KILL_SCRIPT=""
read -r -d '' PX_K8S_KILL_SCRIPT <<'SCRIPT_EOF' || true
pids="$(pgrep -P 1)" || exit 6; for pid in $pids; do kill -s KILL "$pid" || exit 1; done
SCRIPT_EOF
PX_K8S_TERM_SCRIPT='kill -s TERM 1'

## @description  Outputs the canonical backend identifier recorded in the bundle manifest.
## @return       Writes k8s to stdout and exits with 0.
sb_backend_id() { printf 'k8s\n'; }

# ---------------------------------------------------------------------------
# 2. kubectl resolution and invocation
# ---------------------------------------------------------------------------
# The resolved kubectl executable is stored as an absolute path in a module-private variable. That
# variable is never exported, preventing child processes such as workload drivers from discovering
# or invoking kubectl through it. Storing an absolute path serves a second purpose: lib/common.sh
# defines a guard shell function named kubectl to trap unauthorized direct calls, and executing an
# explicit file path bypasses shell function lookup.

_PXK_KUBECTL=""

# Any operator-supplied PX_KUBECTL_BIN value is retained as a local shell variable in this process
# but unexported from the environment of child processes. This unexport happens when the module is
# sourced because binary resolution frequently runs inside a command substitution subshell.
if [ -n "${PX_KUBECTL_BIN+set}" ]; then
  export -n PX_KUBECTL_BIN
fi

## @description  Resolves _PXK_KUBECTL once from PX_KUBECTL_BIN, PATH, or standard install paths.
## @return       Exits with 0 when _PXK_KUBECTL holds an absolute path, or 1 when kubectl is not
##               found.
px_k8s_resolve() {
  local named candidate found=""
  [ -z "$_PXK_KUBECTL" ] || return 0
  named="${PX_KUBECTL_BIN:-}"
  if [ -n "$named" ]; then
    case "$named" in
    */*) [ -x "$named" ] && [ ! -d "$named" ] && found="$named" ;;
    *) found="$(type -P "$named" 2>/dev/null)" || found="" ;;
    esac
    if [ -z "$found" ]; then
      px_error "PX_KUBECTL_BIN names no executable: $named"
      return "$SB_EXIT_FAIL"
    fi
  else
    # Using type -P restricts lookup to PATH entries and ignores the guard shell function.
    found="$(type -P kubectl 2>/dev/null)" || found=""
    if [ -z "$found" ]; then
      for candidate in /usr/local/bin/kubectl /opt/homebrew/bin/kubectl /usr/bin/kubectl; do
        if [ -x "$candidate" ]; then
          found="$candidate"
          break
        fi
      done
    fi
    if [ -z "$found" ]; then
      px_error "no kubectl found. Install it, or set PX_KUBECTL_BIN in env/local.env."
      return "$SB_EXIT_FAIL"
    fi
  fi
  case "$found" in
  /*) ;;
  *) found="$PWD/$found" ;;
  esac
  _PXK_KUBECTL="$found"
  px_debug "kubectl: $_PXK_KUBECTL"
}

# Serves as the single kubectl invocation point; passing bg as the timeout replaces the calling
# subshell with kubectl without running inherited exit hooks.
## @description  Executes kubectl (TIMEOUT_S, - for no bound, or bg, ARG...) on the loaded target.
## @return       Returns the kubectl exit status, 124 on timeout expiry, 137 on SIGKILL, or 1 if
##               kubectl cannot be resolved or no target is loaded.
px_k8s_call() {
  local timeout=${1:-} status=0
  shift || true
  px_k8s_resolve || return "$SB_EXIT_FAIL"
  if [ -z "$PXK_CONTEXT" ] || [ -z "$PXK_NAMESPACE" ]; then
    px_error "px_k8s_call: no context and namespace are loaded"
    return "$SB_EXIT_FAIL"
  fi
  px_debug "kubectl call: $*"
  set -- "$_PXK_KUBECTL" --context "$PXK_CONTEXT" --namespace "$PXK_NAMESPACE" "$@"
  if [ "$timeout" = bg ]; then
    # Because a tunnel outlives the command that opens it, the subshell running this mode replaces
    # itself with kubectl so that its PID becomes the tunnel PID and no exit hooks inherited from
    # the caller execute within it.
    trap - EXIT INT TERM
    exec "$@"
  fi
  if [ "$timeout" = "-" ]; then
    command "$@" || status=$?
  else
    px_timeout "$timeout" "$@" || status=$?
  fi
  return "$status"
}

## @description  Converts a Kubernetes memory quantity (such as 512Ki, 3Gi, or 1000M) into bytes.
## @return       Writes the byte count to stdout (or 0 if no numeric prefix is present); exits 0.
px_k8s_bytes() {
  local text=${1:-} number unit scale=1
  number="${text%%[!0-9]*}"
  unit="${text#"$number"}"
  [ -n "$number" ] || number=0
  case "$unit" in
  Ki) scale=1024 ;;
  Mi) scale=1048576 ;;
  Gi) scale=1073741824 ;;
  Ti) scale=1099511627776 ;;
  k) scale=1000 ;;
  M) scale=1000000 ;;
  G) scale=1000000000 ;;
  T) scale=1000000000000 ;;
  esac
  printf '%s\n' "$((10#$number * scale))"
}

## @description  Converts a Kubernetes CPU quantity (such as 250m or 4) into millicores.
## @return       Writes the millicore count to stdout (or 0 if no number is present); exits 0.
px_k8s_millicores() {
  local text=${1:-} number
  number="${text%%[!0-9]*}"
  [ -n "$number" ] || number=0
  case "$text" in
  *m) printf '%s\n' "$((10#$number))" ;;
  *) printf '%s\n' "$((10#$number * 1000))" ;;
  esac
}

# ---------------------------------------------------------------------------
# 3. Scope management: context, namespace, and local state records
# ---------------------------------------------------------------------------
# A scope identifier is formatted as <context>/<namespace>, where the namespace is constructed as
# <PX_PROJECT_PREFIX>-<cluster>. Because a Kubernetes namespace never contains a slash, the context
# consists of everything preceding the final slash, allowing context names that contain slashes
# (such as cloud cluster ARNs) to parse unambiguously.
#
# The local state record under PX_OUT persists metadata that cannot be represented in cluster
# labels, specifically the rendered deployment directory path and the list of on-demand groups that
# test steps have started. The per-scope pod restart tracking table is stored alongside this record.

PXK_SCOPE=""
PXK_CONTEXT=""
PXK_NAMESPACE=""
PXK_CLUSTER=""
PXK_DEPLOY_DIR=""
PXK_STARTED=""

# The scope identifier alone encodes both the target context and the namespace.
## @description  Parses SCOPE and loads the corresponding PXK_* target and state variables.
## @return       Exits with 0, or 2 when the identifier is empty or not a valid k8s scope id.
px_k8s_scope_load() {
  local scope=${1:-} prefix namespace context
  prefix="$(sb_project_prefix)"
  if [ -z "$scope" ]; then
    px_error "a scope id is required"
    return "$SB_EXIT_USAGE"
  fi
  namespace=${scope##*/}
  context=${scope%/*}
  if [ "$namespace" = "$scope" ] || [ -z "$context" ] || [[ $context == *[[:space:]\|]* ]] ||
    [ "${#namespace}" -gt 63 ] || ! [[ $namespace =~ $PX_K8S_NAMESPACE_RE ]] ||
    [ "${namespace#"$prefix"-}" = "$namespace" ]; then
    px_error "'$scope' is not a scope id of this backend, expected <context>/$prefix-<cluster>"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$PXK_SCOPE" = "$scope" ]; then
    return 0
  fi
  PXK_SCOPE=$scope
  PXK_CONTEXT=$context
  PXK_NAMESPACE=$namespace
  PXK_CLUSTER=${namespace#"$prefix"-}
  # A scope that does not yet have a local state record is valid, so any missing-record status is
  # absorbed inside the command substitution to avoid triggering the caller's error trap.
  PXK_DEPLOY_DIR="$(sb_record_get k8s "$scope" deployment_dir || true)"
  PXK_STARTED=" $(sb_record_get k8s "$scope" started_groups || true) "
  return 0
}

## @description  Loads a raw target (context, namespace) for operations that run without a scope.
## @return       Exits with 0 on success, or 2 when the context argument is empty.
px_k8s_target_load() {
  if [ -z "${1:-}" ]; then
    px_error "PX_K8S_CONTEXT is unset: name the kubeconfig context of the target cluster," \
      "for example with --config k8s/env/k3d.env. The backend never uses the current context."
    return "$SB_EXIT_USAGE"
  fi
  PXK_SCOPE=""
  PXK_CONTEXT=$1
  PXK_NAMESPACE=$2
  PXK_DEPLOY_DIR=""
  PXK_STARTED=" "
}

## @description  Builds the cluster label selector for the loaded scope, appending any TERM... args.
## @return       Writes the comma-separated label selector to stdout and exits with 0.
px_k8s_selector() {
  local selector term
  selector="$(sb_label_prefix).cluster=$PXK_CLUSTER"
  for term in "$@"; do
    selector="$selector,$term"
  done
  printf '%s\n' "$selector"
}

## @description  Updates the started-groups record for SCOPE (SCOPE GROUP add|remove).
## @return       Exits with 0 on success, or 1 when the state record cannot be written.
px_k8s_started_mark() {
  local word out=""
  for word in $PXK_STARTED; do
    [ "$word" != "${2:-}" ] || continue
    out="$out $word"
  done
  [ "${3:-}" != add ] || out="$out ${2:-}"
  PXK_STARTED=" ${out# } "
  sb_record_set k8s "${1:-}" started_groups "${out# }"
}

# ---------------------------------------------------------------------------
# 4. Pod inventory and instance identity
# ---------------------------------------------------------------------------
# A single pod listing satisfies all inventory queries. The jsonpath format produces one
# pipe-delimited line per pod and explicitly selects the container named px so that injected service
# mesh sidecars do not perturb status reporting. The mock kubectl binary used by the self-test suite
# emits pod records in this exact field order.

PXK_P_NAME=""
PXK_P_UID=""
PXK_P_ROLE=""
PXK_P_INDEX=""
PXK_P_DRAIN=""
PXK_P_DELETING=""
PXK_P_PHASE=""
PXK_P_REASON=""
PXK_P_READY_COND=""
PXK_P_STARTED_AT=""
PXK_P_WAITING=""
PXK_P_CODE=""
PXK_P_TERM_REASON=""
PXK_P_FINISHED=""
PXK_P_LAST_CODE=""
PXK_P_LAST_REASON=""
PXK_P_LAST_FINISHED=""
PXK_P_RESTARTS=""
PXK_P_IMAGE=""
PXK_P_IMAGE_ID=""
PXK_P_NODE=""
PXK_P_LIMIT=""
PXK_P_STATE=""
PXK_P_READY=""
PXK_P_EXIT=""

## @description  Emits the pod inventory jsonpath template containing 23 pipe-delimited fields.
## @return       Writes the jsonpath format string to stdout and exits with 0.
px_k8s_pod_format() {
  local p c fmt
  p="$(sb_label_prefix)"
  p="${p//./\\.}"
  c='[?(@.name=="px")]'
  fmt='{range .items[*]}{.metadata.name}|{.metadata.uid}'
  fmt="$fmt|{.metadata.labels.$p\\.role}|{.metadata.labels.$p\\.index}"
  fmt="$fmt|{.metadata.labels.apps\\.kubernetes\\.io/pod-index}"
  fmt="$fmt|{.metadata.labels.$p\\.drain}|{.metadata.deletionTimestamp}"
  fmt="$fmt|{.status.phase}|{.status.reason}|{.status.conditions[?(@.type==\"Ready\")].status}"
  fmt="$fmt|{.status.containerStatuses$c.state.running.startedAt}"
  fmt="$fmt|{.status.containerStatuses$c.state.waiting.reason}"
  fmt="$fmt|{.status.containerStatuses$c.state.terminated.exitCode}"
  fmt="$fmt|{.status.containerStatuses$c.state.terminated.reason}"
  fmt="$fmt|{.status.containerStatuses$c.state.terminated.finishedAt}"
  fmt="$fmt|{.status.containerStatuses$c.lastState.terminated.exitCode}"
  fmt="$fmt|{.status.containerStatuses$c.lastState.terminated.reason}"
  fmt="$fmt|{.status.containerStatuses$c.lastState.terminated.finishedAt}"
  fmt="$fmt|{.status.containerStatuses$c.restartCount}|{.spec.containers$c.image}"
  fmt="$fmt|{.status.containerStatuses$c.imageID}|{.spec.nodeName}"
  fmt="$fmt|{.spec.containers$c.resources.limits.memory}"
  printf '%s\n' "$fmt{\"\\n\"}{end}"
}

# Pods managed by a Deployment carry neither an explicit index label nor a pod-index label, and
# their names end with a random suffix. Consequently, the inventory query assigns replica indices
# for each such role directly: active pods sorted by name receive indices 0 through k-1, followed by
# Terminating and Failed pods sorted by name. Writing this computed ordinal into the line's index
# field provides every inventory consumer with a uniform instance index. Under this ordering, the
# active pod occupies index 0 during a replacement, pods removed by a scale-down occupy indices at
# or above the new replica count until termination completes, and an evicted pod that remains listed
# alongside its ReplicaSet replacement likewise occupies an index at or above the replica count.
## @description  Queries all pods in the loaded scope, filtered by any extra selector TERM... args.
## @return       Writes one raw inventory line per pod to stdout; exits with 0, or 1 if kubectl
##               fails to respond.
px_k8s_pods() {
  local rows
  if ! rows="$(px_k8s_call 60 get pods -l "$(px_k8s_selector "$@")" \
    -o "jsonpath=$(px_k8s_pod_format)")"; then
    px_error "kubectl did not list the pods of scope $PXK_SCOPE"
    return "$SB_EXIT_FAIL"
  fi
  [ -n "$rows" ] || return 0
  printf '%s\n' "$rows" | awk -F '|' -v OFS='|' '
    { line[NR] = $0 }
    $1 != "" && $4 == "" && $5 == "" {
      rep[NR] = 1; role[NR] = $3; name[NR] = $1
      gone[NR] = ($7 != "" || $8 == "Failed" || $8 == "Succeeded")
    }
    END {
      for (i = 1; i <= NR; i++) {
        if (!(i in rep)) { print line[i]; continue }
        r = 0
        for (j in rep) {
          if (role[j] == role[i] && (gone[j] < gone[i] ||
            (gone[j] == gone[i] && name[j] < name[i]))) r++
        }
        $0 = line[i]
        $4 = r
        print
      }
    }'
}

## @description  Parses a raw inventory LINE into the PXK_P_* variables, including contract state.
## @return       Exits with 0 on success, or 1 when the input is not a valid inventory line.
px_k8s_pod_split() {
  local line="${1:-}|" bars=${1//[^|]/} index pod_index
  [ "${#bars}" -ge 22 ] || return 1
  IFS='|' read -r PXK_P_NAME PXK_P_UID PXK_P_ROLE index pod_index \
    PXK_P_DRAIN PXK_P_DELETING PXK_P_PHASE PXK_P_REASON PXK_P_READY_COND PXK_P_STARTED_AT \
    PXK_P_WAITING PXK_P_CODE PXK_P_TERM_REASON PXK_P_FINISHED PXK_P_LAST_CODE \
    PXK_P_LAST_REASON PXK_P_LAST_FINISHED PXK_P_RESTARTS PXK_P_IMAGE PXK_P_IMAGE_ID \
    PXK_P_NODE PXK_P_LIMIT _ <<<"$line"
  [ -n "$PXK_P_NAME" ] || return 1
  # Standalone Pods and Jobs carry an explicit index label, whereas StatefulSet pods provide their
  # ordinal via the pod-index label or pod name suffix.
  if [[ $index =~ $PX_RE_INT ]]; then
    PXK_P_INDEX=$((10#$index))
  elif [[ $pod_index =~ $PX_RE_INT ]]; then
    PXK_P_INDEX=$((10#$pod_index))
  elif [[ ${PXK_P_NAME##*-} =~ $PX_RE_INT ]]; then
    PXK_P_INDEX=$((10#${PXK_P_NAME##*-}))
  else
    PXK_P_INDEX=0
  fi
  [[ $PXK_P_RESTARTS =~ $PX_RE_INT ]] || PXK_P_RESTARTS=0
  PXK_P_EXIT=""
  if [ -n "$PXK_P_DELETING" ]; then
    PXK_P_STATE=stopped
  elif [ -n "$PXK_P_STARTED_AT" ]; then
    PXK_P_STATE=running
  elif [ -n "$PXK_P_CODE" ]; then
    PXK_P_STATE=exited
    PXK_P_EXIT=$PXK_P_CODE
  elif [ -n "$PXK_P_WAITING" ] && [ -n "$PXK_P_LAST_CODE" ]; then
    # A container held in a kubelet restart back-off has already terminated and is awaiting its
    # next start attempt, so it is reported as exited with its most recent exit code.
    PXK_P_STATE=exited
    PXK_P_EXIT=$PXK_P_LAST_CODE
  elif [ "$PXK_P_PHASE" = Succeeded ] || [ "$PXK_P_PHASE" = Failed ]; then
    PXK_P_STATE=exited
    PXK_P_EXIT=${PXK_P_LAST_CODE:-1}
    [ "$PXK_P_PHASE" != Succeeded ] || PXK_P_EXIT=0
  elif [ "$PXK_P_PHASE" = Pending ] || [ -n "$PXK_P_WAITING" ]; then
    PXK_P_STATE=pending
  else
    PXK_P_STATE=unknown
  fi
  PXK_P_READY=unready
  if [ "$PXK_P_READY_COND" = True ] && [ "$PXK_P_STATE" = running ]; then
    PXK_P_READY=ready
  elif [ "$PXK_P_PHASE" = Succeeded ] && [ "$PXK_P_EXIT" = 0 ]; then
    # A one-shot setup Job exits with status 0 upon completion and is treated as ready so that
    # readiness barriers do not wait for it to execute again.
    PXK_P_READY=ready
  fi
  return 0
}

# When a controller is replacing a pod, a terminating pod yields to its active replacement.
## @description  Selects the inventory line for ROLE and INDEX from ROWS (ROWS ROLE INDEX).
## @return       Writes the matching line to stdout, populates PXK_P_*, and exits with 0 (or 6 if no
##               pod matches).
px_k8s_pick() {
  local rows=${1:-} line found=""
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    [ "$PXK_P_ROLE" = "${2:-}" ] && [ "$PXK_P_INDEX" = "${3:-}" ] || continue
    if [ -z "$found" ] || [ -z "$PXK_P_DELETING" ]; then
      found=$line
    fi
  done <<EOF
$rows
EOF
  [ -n "$found" ] || return "$SB_EXIT_NO_OBJECT"
  px_k8s_pod_split "$found"
  printf '%s\n' "$found"
}

## @description  Queries and loads the pod metadata for SCOPE ROLE INDEX into the PXK_P_* variables.
## @return       Exits with 0 on success, 6 if the instance is absent, 2 on usage error, or 1 if
##               kubectl fails to respond.
px_k8s_instance() {
  local scope=${1:-} role=${2:-} index=${3:-} rows
  if [ -z "$role" ] || ! [[ $index =~ $PX_RE_INT ]]; then
    px_error "an instance needs a role and a whole-number index, got '$role' '$index'"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_scope_load "$scope" || return $?
  rows="$(px_k8s_pods "$(sb_label_prefix).role=$role")" || return "$SB_EXIT_FAIL"
  if px_k8s_pick "$rows" "$role" "$((10#$index))" >/dev/null; then
    return 0
  fi
  px_error "no instance $role/$index in scope $scope"
  return "$SB_EXIT_NO_OBJECT"
}

# Supported query modes are: expected (eager instances plus instances in started on-demand groups),
# parked (instances in unstarted on-demand groups), and group GROUP (all instances in GROUP).
## @description  Lists rendered instances from services.tsv matching the requested MODE [GROUP].
## @return       Writes "role index" pairs to stdout and exits with 0 (including when no render is
##               recorded).
px_k8s_rows() {
  local file="$PXK_DEPLOY_DIR/services.tsv" service role index group start rest state
  [ -n "$PXK_DEPLOY_DIR" ] && [ -f "$file" ] || return 0
  while IFS="$PX_TAB" read -r service role index group start rest; do
    case "$service" in
    '' | '#'*) continue ;;
    esac
    state=expected
    if [ "$start" = on-demand ]; then
      case "$PXK_STARTED" in
      *" $group "*) ;;
      *) state=parked ;;
      esac
    fi
    if [ "${1:-}" = group ]; then
      [ "$group" = "${2:-}" ] || continue
    elif [ "${1:-}" != "$state" ]; then
      continue
    fi
    printf '%s %s\n' "$role" "$index"
  done <"$file"
}

# Lists instances sorted by role and index, reporting the Kubernetes node assigned to each pod.
## @description  Reports the instance inventory for a scope, optionally filtered by role and index.
## @param        scope_id: the scope identifier returned by sb_scope_create (<context>/<namespace>)
## @param        role: the role name to filter by; defaults to empty (all roles)
## @param        index: the zero-based instance index (requires role); defaults to empty (all)
## @return       Writes instance TSV rows to stdout; exits with 0, 6 if a specified role or index
##               does not exist, 1 if kubectl fails to respond, or 2 on invalid arguments.
sb_list() {
  local scope=${1:-} role=${2:-} index=${3:-} rows line out=""
  if [ $# -gt 3 ]; then
    px_error "sb_list takes a scope, an optional role and an optional index"
    return "$SB_EXIT_USAGE"
  fi
  if [ -n "$index" ] && ! [[ $index =~ $PX_RE_INT ]]; then
    px_error "an index is a whole number, got '$index'"
    return "$SB_EXIT_USAGE"
  fi
  if [ -n "$index" ] && [ -z "$role" ]; then
    px_error "an index selects one instance of a role, so name the role"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_scope_load "$scope" || return $?
  rows="$(px_k8s_pods)" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    [ -z "$role" ] || [ "$PXK_P_ROLE" = "$role" ] || continue
    [ -z "$index" ] || [ "$PXK_P_INDEX" = "$((10#$index))" ] || continue
    out="$out$(px_tsv_row "$PXK_P_ROLE" "$PXK_P_INDEX" "$PXK_P_NAME" "$PXK_P_STATE" \
      "$PXK_P_READY" "$PXK_P_IMAGE" "$PXK_P_NODE")$PX_NL"
  done <<EOF
$rows
EOF
  if [ -z "$out" ]; then
    if [ -n "$role" ]; then
      px_error "no instance matches role '$role' index '$index' in scope $scope"
      return "$SB_EXIT_NO_OBJECT"
    fi
    return 0
  fi
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n -k3,3
}

# ---------------------------------------------------------------------------
# 5. Version reporting and preflight validation
# ---------------------------------------------------------------------------

## @description  Extracts the gitVersion field for PART (clientVersion or serverVersion) from TEXT.
## @return       Writes the version string to stdout (or empty if absent) and exits with 0.
px_k8s_version_of() {
  printf '%s\n' "${2:-}" | tr -d '\n' |
    sed -n "s/.*\"$1\"[^}]*\"gitVersion\": *\"\([^\"]*\)\".*/\1/p"
}

# Uses PX_SCOPE_ID when set, or falls back to PX_K8S_CONTEXT and the namespace for PX_CLUSTER_ID.
## @description  Loads the default target cluster and namespace for calls that take no scope id.
## @return       Exits with 0 on success, or 2 when neither source specifies a valid target.
px_k8s_default_target() {
  if [ -n "${PX_SCOPE_ID:-}" ] && px_k8s_scope_load "$PX_SCOPE_ID" 2>/dev/null; then
    return 0
  fi
  px_k8s_target_load "${PX_K8S_CONTEXT:-}" "$(sb_project_prefix)-${PX_CLUSTER_ID:-default}"
}

## @description  Reports the kubectl client version, the Kubernetes API server version, and context.
## @return       Writes key-value TSV rows to stdout; exits with 0, 1 if the client or API server
##               fails to respond, or 2 if no kubeconfig context is configured.
sb_version_info() {
  local text client server status=0
  px_k8s_default_target || return $?
  text="$(px_k8s_call 30 version -o json 2>/dev/null)" || status=$?
  client="$(px_k8s_version_of clientVersion "$text")"
  server="$(px_k8s_version_of serverVersion "$text")"
  px_tsv_row kubectl_client "$client"
  px_tsv_row api_server "$server"
  px_tsv_row context "$PXK_CONTEXT"
  if [ -z "$client" ] || [ -z "$server" ]; then
    px_error "the API server of context $PXK_CONTEXT did not report its version (status $status)"
    return "$SB_EXIT_FAIL"
  fi
  return 0
}

# Fails if another harness namespace exists on the target or if the scope's own namespace already
# contains workloads, while accepting an empty labeled namespace as one prepared by the operator.
## @description  Evaluates and reports the target deployment preflight check row.
## @return       Writes check TSV rows to stdout; exits with 0 when no check fails, or 3 if a check
##               fails.
px_k8s_preflight_deployment() {
  local others label status=0 left
  others="$(px_k8s_other_namespaces)" || {
    px_tsv_row deployment_absent fail "kubectl did not list the namespaces of the harness"
    return "$SB_EXIT_ANOMALY"
  }
  if [ -n "$others" ]; then
    px_tsv_row deployment_absent fail \
      "a harness deployment is already on context $PXK_CONTEXT, namespace:$others"
    return "$SB_EXIT_ANOMALY"
  fi
  status=0
  label="$(px_k8s_namespace_label)" || status=$?
  case "$status" in
  "$SB_EXIT_NO_OBJECT")
    px_tsv_row deployment_absent ok "no harness deployment on context $PXK_CONTEXT"
    return 0
    ;;
  0) ;;
  *)
    px_tsv_row deployment_absent fail "kubectl did not read the namespace $PXK_NAMESPACE"
    return "$SB_EXIT_ANOMALY"
    ;;
  esac
  if [ "$label" != "$PXK_CLUSTER" ]; then
    px_tsv_row deployment_absent fail "the namespace $PXK_NAMESPACE carries no label\
 $(sb_label_prefix).cluster=$PXK_CLUSTER, so another owner holds it"
    return "$SB_EXIT_ANOMALY"
  fi
  left="$(px_k8s_call 30 get statefulsets,jobs,pods -l "$(px_k8s_selector)" -o name)" || {
    px_tsv_row deployment_absent fail "kubectl did not list the workloads of $PXK_NAMESPACE"
    return "$SB_EXIT_ANOMALY"
  }
  if [ -n "$left" ]; then
    px_tsv_row deployment_absent fail \
      "the namespace $PXK_NAMESPACE holds a harness deployment: $(px_tsv_clean "$left")"
    return "$SB_EXIT_ANOMALY"
  fi
  px_tsv_row deployment_absent ok \
    "the namespace $PXK_NAMESPACE holds no workload: the operator prepared it"
}

# Emits one quota:<class> row for each ResourceQuota and resource defined in the scope's own
# namespace, normalizing the hard and used figures into bytes, millicores, or items.
## @description  Reports namespace ResourceQuota limits and current usage as preflight check rows.
## @return       Writes check TSV rows to stdout and exits with 0.
px_k8s_preflight_quotas() {
  local fmt key out line name field hard used class value i
  local -a resources=(requests.memory limits.memory memory requests.cpu limits.cpu cpu pods
    persistentvolumeclaims requests.storage)
  local -a fields=()
  # Format one output line per quota containing the quota name followed by the hard and used values
  # for each resource in the order listed in resources.
  fmt='{range .items[*]}{.metadata.name}'
  for key in "${resources[@]}"; do
    fmt="$fmt|{.status.hard.${key//./\\.}}|{.status.used.${key//./\\.}}"
  done
  out="$(px_k8s_call 30 get resourcequota -o "jsonpath=$fmt{\"\\n\"}{end}")" || out=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    IFS='|' read -r -a fields <<<"$line|"
    name=${fields[0]}
    i=0
    for key in "${resources[@]}"; do
      hard=${fields[$((2 * i + 1))]:-}
      used=${fields[$((2 * i + 2))]:-}
      i=$((i + 1))
      [ -n "$hard" ] || continue
      case "$key" in
      requests.storage) class=storage value=px_k8s_bytes ;;
      *memory) class=memory value=px_k8s_bytes ;;
      *cpu) class=cpu value=px_k8s_millicores ;;
      pods) class=pods value="" ;;
      *) class=claims value="" ;;
      esac
      field=$hard
      if [ -n "$value" ]; then
        hard="$("$value" "$hard")"
        used="$("$value" "${used:-0}")"
      fi
      [[ $hard =~ $PX_RE_INT ]] || hard=0
      [[ ${used:-0} =~ $PX_RE_INT ]] || used=0
      px_tsv_row "quota:$class" ok "$hard hard, ${used:-0} used, quota $name $key ($field)"
    done
  done <<EOF
$out
EOF
}

# Because the service mesh overlay applies the istio-injection=enabled label to the namespace,
# sidecar injection occurs only when the default-revision Istio mutating webhook
# namespace.sidecar-injector.istio.io is present on the cluster to act on that label.
## @description  Verifies and reports the mesh_injector preflight check row for the target cluster.
## @return       Writes one check TSV row to stdout; exits with 0 when the Istio sidecar injector is
##               present, or 3 if it is absent or kubectl cannot list the mutating webhooks.
px_k8s_preflight_mesh() {
  local hooks
  hooks="$(px_k8s_call 30 get mutatingwebhookconfigurations \
    -o 'jsonpath={range .items[*]}{range .webhooks[*]}{.name}{"\n"}{end}{end}' 2>/dev/null)" || {
    px_tsv_row mesh_injector fail "kubectl did not list the mutating webhook configurations of\
 context $PXK_CONTEXT, so PX_K8S_MESH=1 has no proof of a mesh: grant the list, or drop\
 PX_K8S_MESH=1"
    return "$SB_EXIT_ANOMALY"
  }
  if ! printf '%s\n' "$hooks" | grep -qFx namespace.sidecar-injector.istio.io; then
    px_tsv_row mesh_injector fail "context $PXK_CONTEXT has no Istio sidecar injector, so no\
 pod joins the mesh of PX_K8S_MESH=1: install the mesh, or drop PX_K8S_MESH=1"
    return "$SB_EXIT_ANOMALY"
  fi
  px_tsv_row mesh_injector ok "the Istio sidecar injector namespace.sidecar-injector.istio.io\
 serves context $PXK_CONTEXT"
}

# Executes the target portion of preflight validation: the kubectl binary and its version, API
# server reachability and version, caller permissions, target deployment state, the configured
# StorageClass, the image pull secret, the Istio sidecar injector required by PX_K8S_MESH=1, the
# nodes matching PX_K8S_NODE_SELECTOR, and namespace ResourceQuotas.
## @description  Runs target preflight checks against the cluster and namespace of a scope.
## @param        scope_id: the scope identifier whose target the checks inspect
## @return       Writes check TSV rows to stdout; exits with 0 when no check fails, 3 if any check
##               fails, or 2 on usage error.
sb_preflight() {
  local scope=${1:-} text server client failed=0 verb nodes memory=0 cpus=0 largest=0 mem cpu
  local status=0 found bytes
  local -a select=()
  px_k8s_scope_load "$scope" || return $?
  if px_k8s_resolve; then
    px_tsv_row engine_binary ok "$_PXK_KUBECTL"
  else
    px_tsv_row engine_binary fail "no kubectl, set PX_KUBECTL_BIN"
    px_tsv_row engine_responsive fail "no kubectl to ask"
    return "$SB_EXIT_ANOMALY"
  fi
  # Preflight evaluation halts at the first API server communication failure because all subsequent
  # checks depend on API server access. Inspecting the error output distinguishes an authentication
  # or authorization rejection from an unreachable server.
  text="$(px_k8s_call 30 version -o json 2>&1)" || status=$?
  server="$(px_k8s_version_of serverVersion "$text")"
  client="$(px_k8s_version_of clientVersion "$text")"
  if [ -z "$server" ]; then
    text="$(printf '%s\n' "$text" | grep -v '^[[:space:]{}"]' | sed -n '/./{p;q;}')"
    case "$text" in
    *[Uu]nauthori[sz]ed* | *[Ff]orbidden*)
      px_tsv_row engine_responsive warn "not judged, the API server refused the client"
      px_tsv_row engine_credentials fail \
        "the client is not authorised on context $PXK_CONTEXT: $(px_tsv_clean "$text")"
      ;;
    *)
      px_tsv_row engine_responsive fail "the API server of context $PXK_CONTEXT did not\
 answer a version request: $(px_tsv_clean "${text:-no text}")"
      ;;
    esac
    return "$SB_EXIT_ANOMALY"
  fi
  px_tsv_row engine_responsive ok "API server $server on context $PXK_CONTEXT"
  if sb_version_floor "$server" PX_K8S_API_FLOOR; then
    px_tsv_row engine_version ok "$server"
  else
    px_tsv_row engine_version fail "API server $server is below the floor $PX_K8S_API_FLOOR"
    failed=1
  fi
  if sb_version_floor "${client:-none}" PX_KUBECTL_MIN_VERSION; then
    px_tsv_row client_version ok "kubectl $client, floor $PX_KUBECTL_MIN_VERSION"
  else
    px_tsv_row client_version fail \
      "kubectl ${client:-unknown} is below the floor ${PX_KUBECTL_MIN_VERSION:-unset}"
    failed=1
  fi
  for verb in statefulsets.apps pods/exec; do
    text="$(px_k8s_call 30 auth can-i create "$verb" 2>/dev/null)" || text=""
    if [ "$text" != yes ]; then
      px_tsv_row engine_credentials fail "the client may not create $verb in $PXK_NAMESPACE"
      failed=1
      break
    fi
  done
  [ "$failed" = 1 ] || px_tsv_row engine_credentials ok "the client may create the workloads"
  px_k8s_preflight_deployment || failed=1
  if [ -z "${PX_K8S_STORAGE_CLASS:-}" ]; then
    px_tsv_row storage_class fail "PX_K8S_STORAGE_CLASS is unset: a target file names it"
    failed=1
  else
    found="$(px_k8s_call 30 get storageclass "$PX_K8S_STORAGE_CLASS" --ignore-not-found \
      -o name 2>/dev/null)" || found=""
    if [ -n "$found" ]; then
      px_tsv_row storage_class ok "the StorageClass $PX_K8S_STORAGE_CLASS exists"
    else
      px_tsv_row storage_class fail \
        "context $PXK_CONTEXT has no StorageClass $PX_K8S_STORAGE_CLASS"
      failed=1
    fi
  fi
  if [ -n "${PX_K8S_PULL_SECRET:-}" ]; then
    # Query only the secret name so that the harness never reads secret payload data.
    found="$(px_k8s_call 30 get secret "$PX_K8S_PULL_SECRET" --ignore-not-found -o name \
      2>/dev/null)" || found=""
    if [ -n "$found" ]; then
      px_tsv_row pull_secret ok "the pull secret $PX_K8S_PULL_SECRET is in $PXK_NAMESPACE"
    else
      px_tsv_row pull_secret fail "no pull secret $PX_K8S_PULL_SECRET in $PXK_NAMESPACE:\
 prepare the namespace with the secret"
      failed=1
    fi
  fi
  [ "${PX_K8S_MESH:-0}" = 0 ] || px_k8s_preflight_mesh || failed=1
  [ -z "${PX_K8S_NODE_SELECTOR:-}" ] || select=(-l "$PX_K8S_NODE_SELECTOR")
  nodes='jsonpath={range .items[*]}{.status.allocatable.memory}|'
  nodes="$nodes"'{.status.allocatable.cpu}{"\n"}{end}'
  nodes="$(px_k8s_call 30 get nodes ${select[@]+"${select[@]}"} -o "$nodes")" || nodes=""
  while IFS='|' read -r mem cpu; do
    [ -n "$mem" ] || continue
    bytes="$(px_k8s_bytes "$mem")"
    memory=$((memory + bytes))
    [ "$bytes" -le "$largest" ] || largest=$bytes
    cpus=$((cpus + $(px_k8s_millicores "$cpu")))
  done <<EOF
$nodes
EOF
  if [ "$memory" -gt 0 ]; then
    px_tsv_row engine_memory ok "$memory bytes allocatable on the selected nodes"
    px_tsv_row engine_cpus ok \
      "$cpus millicores ($((cpus / 1000)) processors) allocatable on the selected nodes"
    px_tsv_row node_memory ok "$largest bytes allocatable on the largest selected node"
  else
    px_tsv_row engine_memory warn "the selected nodes reported no allocatable memory"
    px_tsv_row engine_cpus warn "the selected nodes reported no allocatable processors"
    px_tsv_row node_memory warn "the selected nodes reported no allocatable memory"
  fi
  px_k8s_preflight_quotas
  px_tsv_row port_availability ok "no pod publishes a port on a host"
  [ "$failed" = 0 ] || return "$SB_EXIT_ANOMALY"
}

# ---------------------------------------------------------------------------
# 6. Scope creation, existence checks, and deletion
# ---------------------------------------------------------------------------
# A target Kubernetes cluster hosts at most one active harness deployment at a time, mirroring how
# a local host runs one Compose cluster. The scope admission gate rejects creation if another
# harness cluster namespace already exists on the target cluster, or if a local state record exists
# for another scope on this host (unless PX_FORCE=1 declares that local record stale). Because
# sb_provision applies the Namespace manifest first—and kubectl apply would otherwise adopt a
# pre-existing namespace—the gate also rejects any existing namespace matching the scope name that
# lacks this scope's cluster label. Similarly, scope deletion removes a namespace only if it bears
# the expected cluster label, ensuring the harness never adopts or destroys a namespace belonging to
# another owner.
#
# A namespace that already exists with the expected cluster label before the admission gate runs is
# treated as one prepared by the operator, for example to pre-install a pull secret for a remote
# registry. The local scope record persists that ownership. Because the harness deletes only
# namespaces that it created itself, scope deletion preserves an operator-prepared namespace and its
# secret. Furthermore, because the cluster label alone does not identify who created a namespace,
# scope deletion also retains the namespace whenever no local record names the harness as its owner.
# During bring-up, provisioning skips applying the Namespace manifest to an operator-owned namespace
# so that the harness does not alter any of its labels.

# Any namespace carrying the harness cluster label represents a harness deployment on the target.
## @description  Lists all harness namespaces on the target other than the loaded scope's namespace.
## @return       Writes each namespace name preceded by a space (or nothing if none); exits with 0,
##               or 1 if kubectl fails to respond.
px_k8s_other_namespaces() {
  local names name
  names="$(px_k8s_call 30 get namespaces -l "$(sb_label_prefix).cluster" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{"\n"}{end}')" || return "$SB_EXIT_FAIL"
  while IFS= read -r name; do
    [ -z "$name" ] || [ "$name" = "$PXK_NAMESPACE" ] || printf ' %s' "$name"
  done <<EOF
$names
EOF
}

## @description  Reads the harness cluster label value from the namespace of the loaded scope.
## @return       Writes the label value (or empty if unlabeled) to stdout; exits with 0 if the
##               namespace exists, 6 if it does not exist, or 1 if kubectl fails to respond.
px_k8s_namespace_label() {
  local p out
  p="$(sb_label_prefix)"
  p="${p//./\\.}"
  out="$(px_k8s_call 30 get namespace "$PXK_NAMESPACE" --ignore-not-found \
    -o "jsonpath={.metadata.name}|{.metadata.labels.$p\\.cluster}")" || return "$SB_EXIT_FAIL"
  [ -n "$out" ] || return "$SB_EXIT_NO_OBJECT"
  printf '%s\n' "${out#*|}"
}

# Rejects concurrent harness namespaces on the target, pre-existing namespaces owned by others, and
# conflicting local scope records before recording SCOPE and its namespace owner.
## @description  Enforces the single-deployment admission gate and records SCOPE and its owner.
## @return       Exits with 0 when the scope record is written, or 1 if the gate rejects or a step
##               fails.
px_k8s_scope_gate() {
  local scope=$1 others="" label="" status=0 owner=operator previous
  others="$(px_k8s_other_namespaces)" || status=$?
  [ "$status" != 0 ] || label="$(px_k8s_namespace_label)" || status=$?
  case "$status" in
  0) ;;
  "$SB_EXIT_NO_OBJECT") label=$PXK_CLUSTER owner=harness ;;
  *)
    px_error "the API server of context $PXK_CONTEXT did not answer, so the gate cannot run"
    return "$SB_EXIT_FAIL"
    ;;
  esac
  if [ -n "$others" ]; then
    px_error "a harness deployment is already on context $PXK_CONTEXT, namespace:$others." \
      "Tear it down first, because the harness runs one deployment per target."
    return "$SB_EXIT_FAIL"
  fi
  if [ "$label" != "$PXK_CLUSTER" ]; then
    px_error "the namespace $PXK_NAMESPACE on context $PXK_CONTEXT does not carry the label" \
      "$(sb_label_prefix).cluster=$PXK_CLUSTER (it carries '$label'), so the harness does" \
      "not take it over. Name another cluster, or set PX_PROJECT_PREFIX."
    return "$SB_EXIT_FAIL"
  fi
  # When a forced gate replaces the state record for a namespace that the harness originally
  # created, preserve the prior ownership so the namespace remains recorded as harness-owned.
  previous="$(sb_record_get k8s "$scope" namespace_owner || true)"
  [ -z "$previous" ] || owner=$previous
  sb_record_gate k8s "$scope" || return "$SB_EXIT_FAIL"
  sb_record_set k8s "$scope" namespace_owner "$owner" || return "$SB_EXIT_FAIL"
  sb_record_drop k8s "$scope" pods
  PXK_DEPLOY_DIR="" PXK_STARTED=" "
}

# Combines PX_K8S_CONTEXT with the derived namespace name and initializes the scope state record
# behind the single-deployment-per-target admission gate. The cluster_id must match SB_CLUSTER_RE
# and contain no underscores, which Kubernetes namespace names do not allow.
## @description  Creates and records a new deployment scope for the target cluster context.
## @param        cluster_id: cluster name (SB_CLUSTER_RE, no _); defaults to PX_CLUSTER_ID
## @return       Writes the scope id to stdout; exits with 0 on creation, 1 on gate rejection, API
##               failure, or record write error, or 2 on usage error or unset PX_K8S_CONTEXT.
sb_scope_create() {
  local cluster=${1:-${PX_CLUSTER_ID:-}} scope
  if [ -z "$cluster" ]; then
    px_error "sb_scope_create needs a cluster id"
    return "$SB_EXIT_USAGE"
  fi
  if ! [[ $cluster =~ $SB_CLUSTER_RE ]] || [[ $cluster == *_* ]]; then
    px_error "on Kubernetes a cluster id is a part of a namespace: lower-case letters," \
      "digits and - only, starting with a letter or a digit, got '$cluster'"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_target_load "${PX_K8S_CONTEXT:-}" x || return $?
  scope="$PX_K8S_CONTEXT/$(sb_project_prefix)-$cluster"
  PXK_SCOPE=""
  px_k8s_scope_load "$scope" || return $?
  if sb_dry_run "create the scope $scope"; then
    printf '%s\n' "$scope"
    return 0
  fi
  sb_scope_gated k8s px_k8s_scope_gate "$scope" || return $?
  px_config_set PX_SCOPE_ID "$scope" backend
  printf '%s\n' "$scope"
}

# Checks whether the target cluster holds the scope namespace with the matching cluster label, or
# whether a local scope state record exists on disk.
## @description  Tests whether the specified deployment scope currently exists.
## @param        scope_id: the scope identifier returned by sb_scope_create (<context>/<namespace>)
## @return       Exits with 0 if present, 6 if absent, 1 if kubectl fails and no record exists, or
##               2 on invalid arguments.
sb_scope_exists() {
  local scope=${1:-} label path status=0
  px_k8s_scope_load "$scope" || return $?
  label="$(px_k8s_namespace_label)" || status=$?
  if [ "$status" = 0 ] && [ "$label" = "$PXK_CLUSTER" ]; then
    return 0
  fi
  path="$(sb_record_path k8s scope "$scope")" || return "$SB_EXIT_FAIL"
  if [ -f "$path" ]; then
    px_debug "scope $scope has a record but no namespace with its label"
    return 0
  fi
  [ "$status" != "$SB_EXIT_FAIL" ] || return "$SB_EXIT_FAIL"
  return "$SB_EXIT_NO_OBJECT"
}

# Deletes the scope's ConfigMaps and Services when the namespace was not created by the harness or
# has no recorded owner, leaving the namespace itself intact.
## @description  Removes harness-owned ConfigMaps and Services from a retained scope namespace.
## @return       Exits with 0 when no matching objects remain, or 1 otherwise.
px_k8s_delete_owned() {
  local timeout=${1:-} selector left
  selector="$(px_k8s_selector)"
  px_k8s_call "$timeout" delete configmaps,services -l "$selector" --ignore-not-found \
    --wait=true "--timeout=${timeout}s" >&2 || return "$SB_EXIT_FAIL"
  left="$(px_k8s_call 60 get configmaps,services -l "$selector" -o name)" ||
    return "$SB_EXIT_FAIL"
  if [ -n "$left" ]; then
    px_error "objects of $PXK_SCOPE remain: $(px_tsv_clean "$left")"
    return "$SB_EXIT_FAIL"
  fi
  px_info "the namespace $PXK_NAMESPACE stays: no record shows that the harness made it"
}

# Deletes the namespace and all objects within it when the local record names the harness as its
# creator, or deletes only the scope's objects when the namespace was prepared by the operator or
# has no recorded owner. Preserves the entire namespace when PX_KEEP is set or when the namespace
# lacks the scope's cluster label, and always removes the local state records.
## @description  Deletes a deployment scope or its in-namespace objects and removes local records.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @return       Exits with 0, 1 if the namespace or a scope object remains, the namespace carries
##               another label, or kubectl fails to respond, or 2 on usage error.
sb_scope_delete() {
  local scope=${1:-} timeout label failed=0 status=0 owner
  px_k8s_scope_load "$scope" || return $?
  if sb_dry_run "delete the scope $scope"; then
    return 0
  fi
  owner="$(sb_record_get k8s "$scope" namespace_owner || true)"
  if px_is_keep; then
    px_info "PX_KEEP is set, the namespace $PXK_NAMESPACE and its claims stay"
  else
    label="$(px_k8s_namespace_label)" || status=$?
    timeout="$(sb_seconds PX_TIMEOUT_TEARDOWN 300)"
    if [ "$status" = 0 ] && [ "$label" != "$PXK_CLUSTER" ]; then
      px_error "the namespace $PXK_NAMESPACE does not carry the label" \
        "$(sb_label_prefix).cluster=$PXK_CLUSTER (it carries '$label'), so it stays"
      failed=1
    elif [ "$status" = 0 ] && [ "$owner" != harness ]; then
      px_k8s_delete_owned "$timeout" || failed=1
    elif [ "$status" = 0 ]; then
      px_k8s_call "$timeout" delete namespace "$PXK_NAMESPACE" --ignore-not-found \
        --wait=true "--timeout=${timeout}s" >&2 || failed=1
      status=0
      px_k8s_namespace_label >/dev/null || status=$?
      if [ "$status" != "$SB_EXIT_NO_OBJECT" ]; then
        px_error "the namespace $PXK_NAMESPACE of $scope remains, or kubectl did not answer"
        failed=1
      fi
    elif [ "$status" != "$SB_EXIT_NO_OBJECT" ]; then
      px_error "kubectl did not answer, so the namespace $PXK_NAMESPACE of $scope stays"
      failed=1
    fi
  fi
  sb_record_drop k8s "$scope" scope pods
  PXK_SCOPE=""
  [ "$failed" = 0 ] || return "$SB_EXIT_FAIL"
}

# ---------------------------------------------------------------------------
# 7. Readiness barrier
# ---------------------------------------------------------------------------
# The readiness barrier polls the Ready condition across pods in the scope. Because it waits for
# every instance declared as expected by the rendered topology, a pod that a controller has not yet
# created is treated as pending. Any instance that exits with a non-zero code, enters a Failed
# phase, is evicted, enters a fatal startup waiting state, or restarts while the barrier is polling
# is flagged as an anomaly, causing the barrier to abort immediately. Conversely, any existing pod
# that is no longer expected by the current render (such as an ordinal removed during a scale-down)
# drops out of the wait set as soon as its controller deletes it.

## @description  Tests whether the current PXK_P_* pod state is an anomaly relative to BASE.
## @return       Writes the anomaly reason to stdout and exits with 0 if anomalous, or 1 otherwise.
px_k8s_anomaly() {
  local first
  if [ "$PXK_P_STATE" = exited ] && [ "$PXK_P_EXIT" != 0 ]; then
    printf 'exited %s\n' "$PXK_P_EXIT"
    return 0
  fi
  if [ "$PXK_P_PHASE" = Failed ] || [ "$PXK_P_REASON" = Evicted ]; then
    printf '%s\n' "${PXK_P_REASON:-$PXK_P_PHASE}"
    return 0
  fi
  case "$PX_K8S_BAD_WAITING" in
  *" ${PXK_P_WAITING:-none} "*)
    printf '%s\n' "$PXK_P_WAITING"
    return 0
    ;;
  esac
  first="$(sb_row_get "$PXK_P_UID" "${1:-}" || exit "$?")" || return 1
  if [ "$PXK_P_RESTARTS" -gt "$first" ]; then
    printf 'restarted %s times\n' "$((PXK_P_RESTARTS - first))"
    return 0
  fi
  return 1
}

# If the pod has disappeared since the inventory listing was taken, the query yields an empty
# waiting message.
## @description  Retrieves the waiting status message for the px container of pod PXK_P_NAME.
## @return       Writes the waiting message to stdout; exits with 0, or 1 if kubectl fails to
##               respond.
px_k8s_waiting_message() {
  px_k8s_call 30 get pod "$PXK_P_NAME" --ignore-not-found \
    -o 'jsonpath={.status.containerStatuses[?(@.name=="px")].state.waiting.message}' || {
    px_error "kubectl did not read the waiting message of pod $PXK_P_NAME"
    return "$SB_EXIT_FAIL"
  }
}

# Polls until selected instances report ready. A whole-scope wait skips on-demand groups that have
# not been started by any step.
## @description  Waits until all selected instances in the scope reach the ready state.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        timeout_s: positive integer timeout in seconds; defaults to PX_TIMEOUT_READY (600)
## @param        role...: optional role names to wait for; when omitted, waits for the entire scope
## @return       Writes ready TSV rows to stdout; exits with 0 when all are ready, 3 on instance
##               anomaly, 4 on timeout, 6 if no instance matches, 1 on kubectl error, or 2 on usage.
sb_wait_ready() {
  local scope=${1:-} timeout=${2:-} roles start deadline interval rows line keys="" key
  local base="" seen="" anomaly="" pending elapsed status=0 role index cause roles_all=" "
  local expected="" parked="" gone="" render=0 out="" row lag="" message
  if [ $# -lt 1 ]; then
    px_error "sb_wait_ready needs a scope and a timeout"
    return "$SB_EXIT_USAGE"
  fi
  shift || true
  shift || true
  roles="$*"
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_READY 600)" || return $?
  px_k8s_scope_load "$scope" || return $?
  interval="$(sb_poll_interval)"
  start="$(px_epoch_ms)"
  deadline=$(($(px_epoch_s) + timeout))
  rows="$(px_k8s_pods)" || return "$SB_EXIT_FAIL"
  # Build the candidate set from all currently expected rendered instances plus all existing pods.
  # Any instance belonging to an unstarted on-demand group is classified as parked.
  [ -z "$PXK_DEPLOY_DIR" ] || [ ! -f "$PXK_DEPLOY_DIR/services.tsv" ] || render=1
  while read -r role index; do
    [ -n "$role" ] || continue
    expected="$expected$role/$index$PX_NL"
  done <<EOF
$(px_k8s_rows expected)
EOF
  keys=$expected
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    base="$base$PXK_P_UID$PX_TAB$PXK_P_RESTARTS$PX_NL"
    case "$PX_NL$keys" in
    *"$PX_NL$PXK_P_ROLE/$PXK_P_INDEX$PX_NL"*) ;;
    *) keys="$keys$PXK_P_ROLE/$PXK_P_INDEX$PX_NL" ;;
    esac
  done <<EOF
$rows
EOF
  if [ -z "$roles" ]; then
    while read -r role index; do
      [ -n "$role" ] || continue
      parked="$parked$role/$index$PX_NL"
    done <<EOF
$(px_k8s_rows parked)
EOF
    keys="$keys$parked"
  fi
  keys="$(printf '%s' "$keys" | LC_ALL=C sort -u -t/ -k1,1 -k2,2n)"
  for key in $keys; do
    roles_all="$roles_all${key%/*} "
  done
  if [ "$roles_all" = " " ]; then
    px_error "scope $scope holds no instance and its render expects none"
    return "$SB_EXIT_NO_OBJECT"
  fi
  sb_selection_check "$scope" "$roles_all" "$roles" || return $?
  while :; do
    pending=0
    lag=""
    for key in $keys; do
      sb_role_selected "${key%/*}" "$roles" || continue
      case "$PX_NL$parked$gone" in
      *"$PX_NL$key$PX_NL"*) continue ;;
      esac
      case "$PX_NL$seen" in
      *"$PX_NL$key$PX_TAB"*) continue ;;
      esac
      if ! px_k8s_pick "$rows" "${key%/*}" "${key#*/}" >/dev/null; then
        PXK_P_NAME="" PXK_P_DELETING=""
      fi
      # Any pod that is not expected by the current rendered deployment is considered complete once
      # its controller has deleted it or marked it for deletion, or once it has reached a terminal
      # phase such as an evicted Deployment pod that remains listed alongside its replacement.
      if [ "$render" = 1 ] && { [ -z "$PXK_P_NAME" ] || [ -n "$PXK_P_DELETING" ] ||
        [ "$PXK_P_PHASE" = Failed ] || [ "$PXK_P_PHASE" = Succeeded ]; }; then
        case "$PX_NL$expected" in
        *"$PX_NL$key$PX_NL"*) ;;
        *)
          gone="$gone$key$PX_NL"
          continue
          ;;
        esac
      fi
      # Anomaly detection runs before the readiness check because a container that crashed and
      # restarted between polling intervals may already report ready again on the next listing.
      # A CreateContainerConfigError carrying the transient ConfigMap sync message remains pending.
      # An empty message indicates that the container transitioned out of the waiting state or that
      # the pod disappeared after the inventory listing, leaving the outcome to the next polling
      # cycle. Because each lookup is a single bounded kubectl call and any failed call aborts the
      # barrier immediately, these reads can extend a poll past the deadline by at most their own
      # call latency.
      if [ -n "$PXK_P_NAME" ]; then
        if cause="$(px_k8s_anomaly "$base" || exit "$?")"; then
          if [ "$cause" != CreateContainerConfigError ]; then
            anomaly="$anomaly $key($cause)"
          else
            message="$(px_k8s_waiting_message)" || return "$SB_EXIT_FAIL"
            case "$message" in
            "") ;;
            *"$PX_K8S_CONFIG_LAG"*) lag="$lag $key($message)" ;;
            *) anomaly="$anomaly $key($cause: $message)" ;;
            esac
          fi
        elif [ "$PXK_P_READY" = ready ]; then
          seen="$seen$key$PX_TAB$(($(px_epoch_ms) - start))$PX_NL"
          continue
        fi
      fi
      pending=$((pending + 1))
    done
    if [ -n "$anomaly" ]; then
      px_error "an instance terminated while the barrier waited:$(px_tsv_clean "$anomaly")"
      status=$SB_EXIT_ANOMALY
      break
    fi
    [ "$pending" != 0 ] || break
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      px_error "the readiness barrier expired after ${timeout}s in scope $scope"
      [ -z "$lag" ] ||
        px_error "the kubelet had not yet synced a ConfigMap:$(px_tsv_clean "$lag")"
      status=$SB_EXIT_TIMEOUT
      break
    fi
    sleep "$interval"
    rows="$(px_k8s_pods)" || return "$SB_EXIT_FAIL"
  done
  elapsed=$(($(px_epoch_ms) - start))
  for key in $keys; do
    sb_role_selected "${key%/*}" "$roles" || continue
    case "$PX_NL$gone" in
    *"$PX_NL$key$PX_NL"*) continue ;;
    esac
    if row="$(sb_row_get "$key" "$seen" || exit "$?")"; then
      out="$out$(px_tsv_row "${key%/*}" "${key#*/}" ready "$row")$PX_NL"
    else
      case "$PX_NL$parked" in
      *"$PX_NL$key$PX_NL"*) row=stopped ;;
      *) row=unready ;;
      esac
      out="$out$(px_tsv_row "${key%/*}" "${key#*/}" "$row" "$elapsed")$PX_NL"
    fi
  done
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  return "$status"
}

# ---------------------------------------------------------------------------
# 8. Workload lifecycle: provisioning, teardown, and scaling
# ---------------------------------------------------------------------------
# The rendered deployment directory forms the sole interface between the manifest generator and this
# backend: it contains manifests/ ordered for sequential application, groups/<group>/ for on-demand
# role groups, services.tsv, and ports.generated.env, as specified in docs/backends.md. Every apply
# operation validates all target files via a client-side dry run first; if any dry run fails, no
# manifest is applied to the cluster.

## @description  Validates each file via a client-side dry run and applies it (TIMEOUT_S FILE...).
## @return       Exits with 0 on success, 1 if a dry run, apply, or kubectl call fails, or 4 on
##               timeout expiry.
px_k8s_apply() {
  local timeout=$1 file status=0
  shift
  for file in "$@"; do
    if ! px_k8s_call 60 apply --dry-run=client -f "$file" >&2; then
      px_error "the client dry run refused $file, so no file is applied"
      return "$SB_EXIT_FAIL"
    fi
  done
  for file in "$@"; do
    px_k8s_call "$timeout" apply -f "$file" >&2 || status=$?
    sb_outcome "$status" "$timeout" "the apply of $file" "kubectl could not apply $file" ||
      return $?
  done
}

## @description  Polls until each expected rendered instance has a pod (TIMEOUT_S, ROLE...).
## @return       Exits with 0 when all pods exist, 1 if kubectl fails to respond, or 4 on timeout.
px_k8s_wait_pods() {
  local timeout=$1 deadline interval rows role index missing
  shift
  deadline=$(($(px_epoch_s) + timeout))
  interval="$(sb_poll_interval)"
  while :; do
    rows="$(px_k8s_pods)" || return "$SB_EXIT_FAIL"
    missing=""
    while read -r role index; do
      [ -n "$role" ] || continue
      sb_role_selected "$role" "$*" || continue
      px_k8s_pick "$rows" "$role" "$index" >/dev/null || missing="$missing $role/$index"
    done <<EOF
$(px_k8s_rows expected)
EOF
    [ -n "$missing" ] || return 0
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      px_error "no pod exists after ${timeout}s for:$missing"
      return "$SB_EXIT_TIMEOUT"
    fi
    sleep "$interval"
  done
}

# Applies all eager deployment manifests in order and waits until every eager instance has a pod.
# Manifests for on-demand role groups remain unapplied until sb_group_start is invoked.
## @description  Provisions a rendered deployment into the scope and waits for pod creation.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        deployment_dir: rendered deployment directory containing manifests/
## @param        timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_PROVISION (900)
## @return       Writes instance TSV rows; exits with 0, 1 on dry-run, apply, or kubectl failure, 2
##               on usage or invalid directory, 3 if an instance terminated, or 4 on timeout.
sb_provision() {
  local scope=${1:-} dir=${2:-} timeout=${3:-} cluster="" file rows line bad="" status=0 owner
  local -a files=()
  if [ -z "$dir" ]; then
    px_error "sb_provision needs a scope and a rendered deployment directory"
    return "$SB_EXIT_USAGE"
  fi
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_PROVISION 900)" || return $?
  px_k8s_scope_load "$scope" || return $?
  dir="$(px_realpath "$dir")" || dir=""
  if [ -z "$dir" ] || [ ! -d "$dir/manifests" ] || [ ! -f "$dir/services.tsv" ]; then
    px_error "no rendered Kubernetes deployment in ${2:-}: it needs manifests/ and services.tsv"
    return "$SB_EXIT_USAGE"
  fi
  cluster="$(sed -n 's/^PX_CLUSTER_ID=//p' "$dir/topology.env" 2>/dev/null)"
  if [ "$cluster" != "$PXK_CLUSTER" ]; then
    px_error "the deployment in $dir renders cluster '$cluster', and the scope $scope is" \
      "cluster $PXK_CLUSTER: render it for this cluster"
    return "$SB_EXIT_USAGE"
  fi
  owner="$(sb_record_get k8s "$scope" namespace_owner || true)"
  for file in "$dir"/manifests/*.yaml; do
    [ -f "$file" ] || continue
    # Because the admission gate already verified the cluster label on an operator-prepared
    # namespace, skip the Namespace manifest so the harness does not modify an object owned by
    # another party.
    [ "$owner" != operator ] || [ "${file##*/}" != 00-namespace.yaml ] || continue
    files+=("$file")
  done
  if [ "${#files[@]}" = 0 ]; then
    px_error "the deployment in $dir holds no manifest"
    return "$SB_EXIT_USAGE"
  fi
  sb_record_set k8s "$scope" deployment_dir "$dir" || return "$SB_EXIT_FAIL"
  PXK_DEPLOY_DIR=$dir
  px_config_set PX_DEPLOYMENT_DIR "$dir" backend
  if sb_dry_run "provision $scope from $dir"; then
    return 0
  fi
  px_k8s_apply "$timeout" "${files[@]}" || return $?
  px_k8s_wait_pods "$timeout" || return $?
  rows="$(sb_list "$scope")" || return "$SB_EXIT_FAIL"
  printf '%s\n' "$rows"
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    if [ "$PXK_P_STATE" = exited ] && [ "$PXK_P_EXIT" != 0 ]; then
      bad="$bad $PXK_P_ROLE/$PXK_P_INDEX"
    elif [ "$PXK_P_PHASE" = Failed ]; then
      bad="$bad $PXK_P_ROLE/$PXK_P_INDEX"
    fi
  done <<EOF
$(px_k8s_pods)
EOF
  if [ -n "$bad" ]; then
    px_error "an instance terminated during creation:$bad"
    status=$SB_EXIT_ANOMALY
  fi
  return "$status"
}

# A deployment rendered with the Distributed Pherf tier includes Deployments alongside its
# StatefulSets, requiring workload deletion and listing queries to target both controller kinds;
# requests.tsv in the recorded deployment directory identifies the rendered kinds.
## @description  Determines the workload controller kinds present in the recorded deployment.
## @return       Writes statefulsets or statefulsets,deployments to stdout and exits with 0.
px_k8s_kinds() {
  if [ -n "$PXK_DEPLOY_DIR" ] && grep -q '^Deployment' "$PXK_DEPLOY_DIR/requests.tsv" 2>/dev/null
  then
    printf 'statefulsets,deployments\n'
  else
    printf 'statefulsets\n'
  fi
}

# Deletes all StatefulSets, Jobs, and Pods in the scope. When keep_data is 0, PersistentVolumeClaims
# are also deleted; when keep_data is 1, claims, ConfigMaps, Services, and the namespace are
# retained to support post-run diagnosis.
## @description  Tears down all workloads in the scope and optionally deletes volume claims.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        keep_data: 0 to delete PersistentVolumeClaims, or 1 to retain them (no default)
## @return       Exits with 0 when clean, 1 if objects remain, kubectl fails, or teardown times out,
##               or 2 on invalid arguments.
sb_teardown() {
  local scope=${1:-} keep=${2:-} timeout selector kinds left status=0
  sb_flag sb_teardown keep_data "$keep" || return $?
  px_k8s_scope_load "$scope" || return $?
  if sb_dry_run "tear down $scope with keep_data=$keep"; then
    return 0
  fi
  timeout="$(sb_seconds PX_TIMEOUT_TEARDOWN 300)"
  selector="$(px_k8s_selector)"
  kinds="$(px_k8s_kinds),jobs,pods"
  px_k8s_call "$timeout" delete "$kinds" -l "$selector" --ignore-not-found \
    --cascade=foreground --wait=true "--timeout=${timeout}s" >&2 || status=$?
  if [ "$keep" = 0 ] && [ "$status" = 0 ]; then
    kinds="$kinds,pvc"
    px_k8s_call "$timeout" delete pvc -l "$selector" --ignore-not-found --wait=true \
      "--timeout=${timeout}s" >&2 || status=$?
  fi
  if sb_expired "$status"; then
    px_error "teardown of $scope expired after ${timeout}s"
    return "$SB_EXIT_FAIL"
  fi
  left="$(px_k8s_call 60 get "$kinds" -l "$selector" -o name)" || {
    px_error "kubectl did not answer, so the teardown of $scope is not proved"
    return "$SB_EXIT_FAIL"
  }
  if [ -n "$left" ]; then
    px_error "objects of $scope remain: $(px_tsv_clean "$left")"
    return "$SB_EXIT_FAIL"
  fi
  sb_record_set k8s "$scope" started_groups "" || return "$SB_EXIT_FAIL"
  [ "$status" = 0 ] || return "$SB_EXIT_FAIL"
}

## @description  Locates the rendered workload manifest file for ROLE in the recorded deployment.
## @return       Writes the manifest path to stdout and exits with 0, or 6 if no manifest exists.
px_k8s_role_file() {
  local file
  for file in "$PXK_DEPLOY_DIR"/manifests/2?-"$1".yaml "$PXK_DEPLOY_DIR"/groups/*/2?-"$1".yaml \
    "$PXK_DEPLOY_DIR"/groups/*/5?-"$1".yaml; do
    if [ -f "$file" ]; then
      printf '%s\n' "$file"
      return 0
    fi
  done
  return "$SB_EXIT_NO_OBJECT"
}

# Re-applies the environment and workload manifests for a role after the caller has re-rendered the
# deployment with an updated replica count. The StatefulSet controller creates or terminates only
# the ordinals affected by the change. By contrast, a Distributed Pherf tier Deployment updates its
# replica count via kubectl scale, and any pods removed during a scale-down retain indices at or
# above the new target count until they finish terminating.
## @description  Scales a role to the new instance count rendered in the recorded deployment.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the role name in the rendered deployment to scale
## @param        count: target instance count (1 to 9), which must match the re-rendered deployment
## @param        timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_SCALE (600)
## @return       Writes role instance TSV rows to stdout; exits with 0, 1 on apply or kubectl error,
##               2 if count is not 1..9 or mismatches the render, or 4 on timeout.
sb_scale() {
  local scope=${1:-} role=${2:-} count=${3:-} timeout=${4:-} file rendered group rows
  local deadline interval left status=0
  if [ -z "$role" ]; then
    px_error "sb_scale needs a scope, a role and a count"
    return "$SB_EXIT_USAGE"
  fi
  case "$count" in
  [1-9]) ;;
  *)
    px_error "an instance count is 1 to 9, got '$count'"
    return "$SB_EXIT_USAGE"
    ;;
  esac
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_SCALE 600)" || return $?
  px_k8s_scope_load "$scope" || return $?
  rendered="$(awk -F '\t' -v r="$role" '!/^#/ && $2 == r { n++ } END { print n + 0 }' \
    "$PXK_DEPLOY_DIR/services.tsv" 2>/dev/null)" || rendered=0
  if ! file="$(px_k8s_role_file "$role")" || [ "${rendered:-0}" = 0 ]; then
    px_error "the deployment of $scope renders no instance of role '$role'"
    return "$SB_EXIT_USAGE"
  fi
  if [ "$rendered" != "$count" ]; then
    px_error "the deployment of $scope renders a different count of $role than $count:" \
      "render it with the new count first"
    return "$SB_EXIT_USAGE"
  fi
  group="$(awk -F '\t' -v r="$role" '!/^#/ && $2 == r { print $4; exit }' \
    "$PXK_DEPLOY_DIR/services.tsv")"
  if sb_dry_run "scale $role to $count in $scope"; then
    return 0
  fi
  # A role belonging to an on-demand group that has not yet been started has no active StatefulSet
  # on the cluster; its updated manifest will be applied when the group is next started.
  if [ -n "$group" ]; then
    case "$PXK_STARTED" in
    *" $group "*) ;;
    *)
      px_info "the group $group of $role is not started: its next start applies $count"
      sb_list "$scope" "$role" 2>/dev/null || true
      return 0
      ;;
    esac
  fi
  if grep -qx 'kind: Deployment' "$file"; then
    px_k8s_apply "$timeout" "$PXK_DEPLOY_DIR/manifests/10-env.yaml" || return $?
    px_k8s_call "$timeout" scale deployment "$role" "--replicas=$count" >&2 || status=$?
    sb_outcome "$status" "$timeout" "the scale of $role" "kubectl could not scale $role" ||
      return $?
  else
    px_k8s_apply "$timeout" "$PXK_DEPLOY_DIR/manifests/10-env.yaml" "$file" || return $?
  fi
  px_k8s_wait_pods "$timeout" "$role" || status=$?
  if [ "$status" = 0 ]; then
    sb_wait_ready "$scope" "$timeout" "$role" >/dev/null || status=$?
  fi
  # The StatefulSet persistent volume claim retention policy automatically removes claims belonging
  # to scaled-down ordinals.
  deadline=$(($(px_epoch_s) + timeout))
  interval="$(sb_poll_interval)"
  while [ "$status" = 0 ]; do
    rows="$(px_k8s_pods "$(sb_label_prefix).role=$role")" || return "$SB_EXIT_FAIL"
    left="$(printf '%s\n' "$rows" | while IFS= read -r line; do
      px_k8s_pod_split "$line" || continue
      [ "$PXK_P_INDEX" -lt "$count" ] || printf '%s ' "$PXK_P_NAME"
    done)"
    [ -n "$left" ] || break
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      px_error "the pods $left of $role are still there after ${timeout}s"
      status=$SB_EXIT_TIMEOUT
      break
    fi
    sleep "$interval"
  done
  sb_list "$scope" "$role"
  case "$status" in
  0) return 0 ;;
  "$SB_EXIT_TIMEOUT")
    px_error "the scale of $role to $count did not finish in ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
    ;;
  esac
  return "$SB_EXIT_FAIL"
}

# ---------------------------------------------------------------------------
# 9. Command execution, log streaming, and file transfer
# ---------------------------------------------------------------------------
# All workload drivers, health checks, region-mover invocations, and in-cluster diagnostic utilities
# interact with cluster instances through these functions, targeting the container named px.

# Executes a command inside the px container of a running instance, forwarding stdout verbatim.
## @description  Runs a command inside a target instance and streams its output byte for byte.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_EXEC (600)
## @param        --: required literal separator before the command
## @param        cmd [arg...]: the command to execute and its arguments (cmd is required)
## @return       Returns the command status, 124 on timeout, 125 on start or substrate failure, 126
##               if not executable, 127 if not found, 2 on usage error, or 6 if no such instance.
sb_exec() {
  local scope=${1:-} role=${2:-} index=${3:-} timeout=${4:-} tty="" status=0
  sb_exec_args "$@" || return $?
  shift 5
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_EXEC 600)" || return $?
  px_k8s_instance "$scope" "$role" "$index" || status=$?
  case "$status" in
  0) ;;
  "$SB_EXIT_USAGE" | "$SB_EXIT_NO_OBJECT") return "$status" ;;
  *)
    px_error "the substrate did not answer, so the exec in $role/$index cannot start"
    return "$SB_EXEC_NOSTART"
    ;;
  esac
  # Resolve the kubectl binary in the current shell first so the subsequent exec call cannot fail on
  # binary lookup.
  px_k8s_resolve || return "$SB_EXEC_NOSTART"
  if [ "$PXK_P_STATE" != running ]; then
    px_error "instance $role/$index is $PXK_P_STATE, so the exec cannot start"
    return "$SB_EXEC_NOSTART"
  fi
  # Allocate a remote TTY whenever standard input is attached to an interactive operator terminal.
  [ ! -t 0 ] || tty=-t
  # Wrapping the target command in sh -c 'exec "$@"' ensures that a missing executable yields exit
  # code 127 and a non-executable file yields 126, matching the Compose backend behavior.
  px_k8s_call "$timeout" exec -i ${tty:+"$tty"} "$PXK_P_NAME" -c px -- \
    sh -c 'exec "$@"' px-exec "$@" || status=$?
  return "$status"
}

## @description  Streams raw container logs for an instance with stderr merged into stdout.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        since_s: relative lookback window in whole seconds; - or empty (default) for all
## @param        previous: 0 (default) for the current container, or 1 for the prior terminated one
## @param        follow: 0 (default) for a snapshot, or 1 to stream until the reader closes the log
## @return       Writes raw log bytes to stdout; exits with 0, 1 if kubectl cannot read the logs or
##               fails to respond, 2 on usage error, or 6 if the instance or previous log is absent.
sb_logs() {
  local scope=${1:-} role=${2:-} index=${3:-} since=${4:--} previous=${5:-0}
  local follow=${6:-0} timeout=- status=0
  local -a args=()
  since="$(sb_logs_args "$since" "$previous" "$follow")" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  if [ "$previous" = 1 ]; then
    if [ -z "$PXK_P_LAST_CODE" ]; then
      px_error "$role/$index has no previous container"
      return "$SB_EXIT_NO_OBJECT"
    fi
    args+=(--previous)
  fi
  [ -z "$since" ] || args+=("--since=${since}s")
  if [ "$follow" = 1 ]; then
    args+=(--follow)
  else
    timeout="$(sb_seconds PX_TIMEOUT_DEFAULT 300)"
  fi
  px_k8s_call "$timeout" logs "$PXK_P_NAME" -c px ${args[@]+"${args[@]}"} || status=$?
  if [ "$status" != 0 ]; then
    px_error "kubectl could not read the log of $role/$index"
    return "$SB_EXIT_FAIL"
  fi
}

## @description  Copies SRC to DST for the loaded PXK_P_* pod (WHAT SRC DST [PARENT]).
## @return       Exits with 0 (including in dry-run mode), or 1 if the pod is not running, PARENT
##               cannot be created, or the kubectl cp invocation fails.
px_k8s_cp() {
  local parent=${4:-}
  if sb_dry_run "copy $1"; then
    return 0
  fi
  # Because kubectl cp streams an archive by invoking tar inside the container, the target pod must
  # currently be in the running state.
  if [ "$PXK_P_STATE" != running ]; then
    px_error "the copy of $1 needs a running pod, and $PXK_P_NAME is $PXK_P_STATE"
    return "$SB_EXIT_FAIL"
  fi
  if [ -n "$parent" ] && [ ! -d "$parent" ] && ! mkdir -p "$parent"; then
    px_error "cannot create the destination directory $parent"
    return "$SB_EXIT_FAIL"
  fi
  if ! px_k8s_call "$(sb_seconds PX_TIMEOUT_DEFAULT 300)" cp "$2" "$3" -c px >&2; then
    px_error "the copy of $1 failed"
    return "$SB_EXIT_FAIL"
  fi
}

## @description  Copies a file or directory from the local host into a running instance.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        local_path: existing file or directory path on the host
## @param        remote_path: destination path inside the target container
## @return       Exits with 0 on success, 1 if the copy fails, the pod is not running, or kubectl
##               fails to respond, 2 on usage error or missing local_path, or 6 if no such instance.
sb_copy_in() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-}
  source="$(sb_copy_args in "$source" "$target")" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  px_k8s_cp "$source into $role/$index at $target" "$source" "$PXK_P_NAME:$target"
}

# The destination local_path must be relative to PX_OUT (or an absolute path beneath PX_OUT) and
# must reside outside the repository working tree.
## @description  Retrieves a file or directory from an instance to an allowed host output path.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        remote_path: source path inside the target container
## @param        local_path: destination path relative to or under PX_OUT, outside the source tree
## @return       Exits with 0, 1 on copy failure, non-running pod, kubectl error, or path resolution
##               failure, 2 on usage error or policy-refused destination, or 6 if no such instance.
sb_copy_out() {
  local scope=${1:-} role=${2:-} index=${3:-} source=${4:-} target=${5:-} resolved
  resolved="$(sb_copy_args out "$source" "$target")" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  px_k8s_cp "$source out of $role/$index to $resolved" "$PXK_P_NAME:$source" "$resolved" \
    "${resolved%/*}"
}

# ---------------------------------------------------------------------------
# 10. On-demand role group lifecycle
# ---------------------------------------------------------------------------
# An on-demand role group is represented both by a subdirectory in the rendered deployment
# (groups/<group>/) and by a group label on its Kubernetes objects. Starting a group applies the
# manifests in its group directory and waits for readiness. Stopping a group deletes its
# StatefulSets and Deployments while retaining their PersistentVolumeClaims, so a later start
# within the same run reattaches to the preserved volume state. StatefulSets therefore maintain
# their configured replica counts whenever they exist on the cluster, and group lifecycle
# transitions are driven entirely by manifest apply and workload delete operations.

## @description  Lists the distinct roles belonging to GROUP in the render (CALLER SCOPE GROUP).
## @return       Writes one role name per line to stdout; exits with 0, or 2 if GROUP is empty or
##               absent from the rendered deployment.
px_k8s_group_roles() {
  local roles
  if [ -z "${3:-}" ]; then
    px_error "$1 needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_scope_load "${2:-}" || return $?
  roles="$(px_k8s_rows group "$3" | awk '{ print $1 }' | LC_ALL=C sort -u)"
  if [ -z "$roles" ]; then
    px_error "the deployment of scope $2 renders no role of the group '$3'"
    return "$SB_EXIT_USAGE"
  fi
  printf '%s\n' "$roles"
}

## @description  Lists running instances in the loaded scope matching any extra TERM... filters.
## @return       Writes one role/index line per running pod to stdout; exits with 0, or 1 if kubectl
##               fails to respond.
px_k8s_running() {
  local rows line
  rows="$(px_k8s_pods "$@")" || return "$SB_EXIT_FAIL"
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    [ "$PXK_P_STATE" != running ] || printf '%s/%s\n' "$PXK_P_ROLE" "$PXK_P_INDEX"
  done <<EOF
$rows
EOF
}

## @description  Stops all instances in a group by deleting its StatefulSets and keeping claims.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        group: the name of a rendered role group (required)
## @param        timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_GROUP (600)
## @return       Writes stopped TSV rows to stdout; exits with 0, 1 if any instance remains running
##               or kubectl fails, 2 on usage error or unknown group, or 4 on timeout.
sb_group_stop() {
  local scope=${1:-} group=${2:-} timeout=${3:-} term before role index out="" still=0
  local key status=0
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  px_k8s_group_roles sb_group_stop "$scope" "$group" >/dev/null || return $?
  term="$(sb_label_prefix).group=$group"
  before="$(px_k8s_running "$term")" || return "$SB_EXIT_FAIL"
  if sb_dry_run "stop the group $group in $scope"; then
    return 0
  fi
  px_k8s_started_mark "$scope" "$group" remove || return "$SB_EXIT_FAIL"
  px_k8s_call "$timeout" delete "$(px_k8s_kinds)" -l "$(px_k8s_selector "$term")" \
    --ignore-not-found --cascade=foreground --wait=true "--timeout=${timeout}s" >&2 || status=$?
  if sb_expired "$status"; then
    px_error "the stop of group $group expired after ${timeout}s"
    return "$SB_EXIT_TIMEOUT"
  fi
  key="$(px_k8s_running "$term")" || return "$SB_EXIT_FAIL"
  for key in $key; do
    still=$((still + 1))
  done
  while read -r role index; do
    [ -n "$role" ] || continue
    case "$PX_NL$before$PX_NL" in
    *"$PX_NL$role/$index$PX_NL"*) out="$out$(px_tsv_row "$role" "$index" stopped)$PX_NL" ;;
    *) out="$out$(px_tsv_row "$role" "$index" already_stopped)$PX_NL" ;;
    esac
  done <<EOF
$(px_k8s_rows group "$group")
EOF
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
  if [ "$still" != 0 ]; then
    px_error "$still instance of group $group still runs"
    return "$SB_EXIT_FAIL"
  fi
  if [ "$status" != 0 ]; then
    px_error "kubectl reported status $status for the stop of group $group"
    return "$SB_EXIT_FAIL"
  fi
}

# The timeout_s bound applies both to the manifest application step and to the readiness barrier.
## @description  Starts a role group by applying its manifests and waiting for instance readiness.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        group: the name of a rendered role group (required)
## @param        timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_GROUP (600)
## @return       Writes ready TSV rows; exits with 0, 1 on dry-run, apply, or kubectl failure, 2 on
##               usage or unknown group, 4 on apply timeout, or the exit status of sb_wait_ready.
sb_group_start() {
  local scope=${1:-} group=${2:-} timeout=${3:-} roles file
  local -a names=() files=()
  timeout="$(sb_timeout_arg "$timeout" PX_TIMEOUT_GROUP 600)" || return $?
  roles="$(px_k8s_group_roles sb_group_start "$scope" "$group")" || return $?
  px_k8s_scope_load "$scope" || return $?
  for file in "$PXK_DEPLOY_DIR/groups/$group"/*.yaml; do
    [ -f "$file" ] && files+=("$file")
  done
  if [ "${#files[@]}" = 0 ]; then
    px_error "the deployment of scope $scope holds no file of the group $group"
    return "$SB_EXIT_FAIL"
  fi
  if sb_dry_run "start the group $group in $scope"; then
    return 0
  fi
  px_k8s_apply "$timeout" "${files[@]}" || return $?
  px_k8s_started_mark "$scope" "$group" add || return "$SB_EXIT_FAIL"
  IFS="$PX_NL" read -r -d '' -a names <<EOF || true
$roles
EOF
  sb_wait_ready "$scope" "$timeout" "${names[@]}"
}

## @description  Reports whether a group is active so steps can skip intentionally stopped groups.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        group: the name of a rendered role group (required)
## @return       Writes one group TSV row to stdout; exits with 0, 1 if kubectl fails to respond, or
##               2 on usage error or unknown group.
sb_group_status() {
  local scope=${1:-} group=${2:-} key running=0 desired state
  if [ -z "$group" ]; then
    px_error "sb_group_status needs a scope and a group"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_scope_load "$scope" || return $?
  desired="$(px_k8s_rows group "$group" | grep -c .)" || desired=0
  if [ "$desired" = 0 ]; then
    px_error "the deployment of scope $scope renders no instance of the group '$group'"
    return "$SB_EXIT_USAGE"
  fi
  key="$(px_k8s_running "$(sb_label_prefix).group=$group")" || return "$SB_EXIT_FAIL"
  for key in $key; do
    running=$((running + 1))
  done
  if [ "$running" = 0 ]; then
    state=down
  elif [ "$running" -ge "$desired" ]; then
    state=up
  else
    state=partial
  fi
  px_tsv_row "$group" "$running" "$desired" "$state"
}

# ---------------------------------------------------------------------------
# 11. Fault injection: abrupt kill, signal delivery, and drain-aware restart
# ---------------------------------------------------------------------------
# A hard kill terminates the container processes while preserving the pod object; for a StatefulSet
# pod, the kubelet restarts the container in place, recording the event in the container restart
# count and the previous termination state. A delete mode kill removes the pod object itself,
# causing the StatefulSet controller to provision a replacement pod with a new uid. Because a
# standard pod deletion sends SIGTERM first—which would allow the JVM to execute its graceful
# shutdown hooks—the delete mode first kills the container processes abruptly via SIGKILL before
# deleting the pod. A standalone Pod is never restarted by a controller, so a killed client pod
# remains terminated on Kubernetes just as on Compose. The caller is responsible for recording the
# fault injection as a planned event.

## @description  Runs a remote fault script inside an instance (SCOPE ROLE INDEX NAME SCRIPT).
## @return       Exits with 0, or 1 if the script fails, the pod is not running, or no process runs
##               beneath PID 1.
px_k8s_fault() {
  local status=0
  sb_exec "$1" "$2" "$3" "$(sb_seconds PX_TIMEOUT_STOP 120)" -- sh -c "$5" "$4" </dev/null \
    >/dev/null || status=$?
  case "$status" in
  0) return 0 ;;
  "$SB_EXIT_NO_OBJECT")
    px_error "$2/$3 runs no process below its init"
    return "$SB_EXIT_FAIL"
    ;;
  esac
  px_error "the fault $4 in $2/$3 failed with status $status"
  return "$SB_EXIT_FAIL"
}

# Because the kubelet reports container kills asynchronously, waiting for the updated pod status
# prevents a subsequent restart from reading stale readiness state and draining the newly started
# container. Failed pod listings are retried until the timeout bound expires.
## @description  Polls until pod status reflects a kill (SCOPE ROLE INDEX OLD_UID OLD_RESTARTS).
## @return       Exits with 0 once the status reflects the kill or the instance is absent, or 1 if
##               the timeout bound expires before the kill is observed.
px_k8s_await_kill() {
  local bound deadline interval status
  bound="$(sb_seconds PX_TIMEOUT_STOP 120)"
  deadline=$(($(px_epoch_s) + bound))
  interval="$(sb_poll_interval)"
  while :; do
    status=0
    px_k8s_instance "$1" "$2" "$3" 2>/dev/null || status=$?
    case "$status" in
    0)
      if [ "$PXK_P_UID" != "$4" ] || [ "$PXK_P_RESTARTS" -gt "$5" ] ||
        [ "$PXK_P_STATE" != running ]; then
        return 0
      fi
      ;;
    "$SB_EXIT_NO_OBJECT") return 0 ;;
    esac
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      if [ "$status" = 0 ]; then
        px_error "the pod status did not show the kill of $2/$3 in ${bound}s"
      else
        px_error "kubectl could not read the pod status of $2/$3 (status $status) in ${bound}s"
      fi
      return "$SB_EXIT_FAIL"
    fi
    sleep "$interval"
  done
}

## @description  Abruptly terminates a single workload instance using the specified kill mode.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        mode: hard (kills processes, keeps the pod, and waits for pod status to reflect
##               the kill) or delete (also deletes the pod and waits for deletion to complete)
## @return       Exits with 0, 1 if termination fails, pod status never reflects it, or kubectl
##               fails to respond, 2 on usage error or unknown mode, or 6 if the instance is absent.
sb_kill() {
  local scope=${1:-} role=${2:-} index=${3:-} mode=${4:-} pod timeout uid restarts
  sb_value_arg sb_kill "a mode" kill_mode "$mode" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  if sb_dry_run "$mode kill of $role/$index in $scope"; then
    return 0
  fi
  pod=$PXK_P_NAME
  uid=$PXK_P_UID
  restarts=$PXK_P_RESTARTS
  if [ "$PXK_P_STATE" = running ] || [ "$mode" = hard ]; then
    px_k8s_fault "$scope" "$role" "$index" px-kill "$PX_K8S_KILL_SCRIPT" || return $?
  fi
  [ "$mode" = delete ] ||
    { px_k8s_await_kill "$scope" "$role" "$index" "$uid" "$restarts"; return; }
  # Because the kubelet may have restarted the container immediately after the SIGKILL, and that
  # new container requires up to its termination grace period to stop, the deletion timeout adds an
  # extra buffer beyond PX_TIMEOUT_STOP.
  timeout=$(($(sb_seconds PX_TIMEOUT_STOP 120) + 30))
  if ! px_k8s_call "$timeout" delete pod "$pod" --wait=true "--timeout=${timeout}s" >&2; then
    px_error "the delete of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
}

# Sends a signal to an instance (for example, QUIT to trigger a thread dump). KILL and TERM target
# the container process hierarchy to terminate the container, whereas all other signals are directed
# to the daemon JVM process inside the container. Supported signals are HUP, INT, QUIT, ABRT, KILL,
# ALRM, TERM, USR1, USR2, STOP, CONT, and TSTP (case-insensitive, with or without a SIG prefix).
## @description  Delivers a named POSIX signal to a container or its daemon JVM process.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        signal_name: HUP INT QUIT ABRT KILL ALRM TERM USR1 USR2 STOP CONT or TSTP
## @return       Exits with 0, 1 if delivery or STOP/TSTP/CONT verification fails or kubectl fails,
##               2 on usage error or unknown signal, or 6 if the instance or JVM is absent.
sb_signal() {
  local scope=${1:-} role=${2:-} index=${3:-} name
  name="$(sb_signal_name "${4:-}")" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  if sb_dry_run "signal $name to $role/$index in $scope"; then
    return 0
  fi
  case "$name" in
  KILL) px_k8s_fault "$scope" "$role" "$index" px-kill "$PX_K8S_KILL_SCRIPT" ;;
  TERM) px_k8s_fault "$scope" "$role" "$index" px-term "$PX_K8S_TERM_SCRIPT" ;;
  *) sb_signal_jvm "$scope" "$role" "$index" "$(sb_seconds PX_TIMEOUT_STOP 120)" "$name" ;;
  esac
}

# Arguments: SCOPE ROLE INDEX OLD_UID TIMEOUT_S START_MS.
## @description  Waits for a replacement pod with a new uid to start and reach readiness.
## @return       Writes start and ready phase TSV rows; exits with 0, 1 if the pod exits, or 4 on
##               timeout expiry.
px_k8s_await_new() {
  local scope=$1 role=$2 index=$3 old=$4 timeout=$5 start=$6 deadline interval phase=start
  local status
  deadline=$(($(px_epoch_s) + timeout))
  interval="$(sb_poll_interval)"
  while :; do
    status=0
    px_k8s_instance "$scope" "$role" "$index" 2>/dev/null || status=$?
    if [ "$phase" = start ] && [ "$status" = 0 ] && [ "$PXK_P_UID" != "$old" ] &&
      [ -z "$PXK_P_DELETING" ]; then
      sb_phase_row start ok "$start"
      phase=ready
      start="$(px_epoch_ms)"
    fi
    if [ "$phase" = ready ]; then
      if [ "$status" != 0 ] || [ "$PXK_P_STATE" = exited ]; then
        sb_phase_fail ready "$start"
        px_error "$role/$index stopped while the restart waited for it to become ready"
        return "$SB_EXIT_FAIL"
      fi
      if [ "$PXK_P_READY" = ready ]; then
        sb_phase_row ready ok "$start"
        return 0
      fi
    fi
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      sb_phase_fail "$phase" "$start"
      px_error "$role/$index did not become ready in ${timeout}s"
      return "$SB_EXIT_TIMEOUT"
    fi
    sleep "$interval"
  done
}

# Performs a graceful drain-aware restart of a regionmover instance: unloads regions via tooling/0,
# deletes the pod, waits for the replacement pod to start and become ready, and reloads the regions.
# If the target instance is not ready initially, the drain and rebalance phases are skipped, but the
# pod is still deleted and recreated. The drain_timeout_s bound governs the drain, stop, and
# rebalance phases, while ready_timeout_s governs the replacement start and readiness wait.
## @description  Performs a drain-aware restart of a regionmover instance via tooling/0.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: a role carrying the regionmover drain label
## @param        index: the zero-based instance index
## @param        drain_timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_DRAIN (600)
## @param        ready_timeout_s: timeout in whole seconds (>0); defaults to PX_TIMEOUT_READY (600)
## @return       Writes phase TSV rows; exits with 0, 1 on phase or kubectl failure, 2 on usage or
##               missing drain label, rpc port, or tooling/0, 4 on phase timeout, or 6 if absent.
sb_restart_drain() {
  local scope=${1:-} role=${2:-} index=${3:-} drain=${4:-} ready=${5:-}
  local host port file cmd pod uid start state phase=stop drained=1
  drain="$(sb_timeout_arg "$drain" PX_TIMEOUT_DRAIN 600)" || return $?
  ready="$(sb_timeout_arg "$ready" PX_TIMEOUT_READY 600)" || return $?
  px_k8s_instance "$scope" "$role" "$index" || return $?
  if [ "$PXK_P_DRAIN" != regionmover ]; then
    px_error "role '$role' has no drain semantics: the deployment gives it no" \
      "$(sb_label_prefix).drain=regionmover label"
    return "$SB_EXIT_USAGE"
  fi
  pod=$PXK_P_NAME
  uid=$PXK_P_UID
  [ "$PXK_P_READY" = ready ] || drained=0
  # Because the saved region list file must survive the deletion of the target pod, the region
  # mover executes inside tooling/0. The target host passed to the mover is the pod name, which is
  # the hostname under which the RegionServer registered.
  host=$pod
  file="/tmp/px-regions-$role-$index"
  port="$(px_k8s_port_value "$role" rpc)" || port=""
  cmd="$(sb_mover_cmd unload "$host" "$port" "$drain" "$file")" || cmd=""
  if [ -z "$cmd" ]; then
    px_error "the port map holds no rpc port for $role, so the mover cannot name $role/$index"
    return "$SB_EXIT_USAGE"
  fi
  if ! px_k8s_instance "$scope" tooling 0 2>/dev/null; then
    px_error "the region mover runs in tooling 0, and scope $scope has no tooling 0"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_instance "$scope" "$role" "$index" || return $?
  state=$PXK_P_STATE
  if sb_dry_run "drain-aware restart of $role/$index in $scope"; then
    return 0
  fi
  start="$(px_epoch_ms)"
  if [ "$drained" = 1 ]; then
    sb_mover_phase drain "$start" "$role/$index" "$drain" "$cmd" "$scope" tooling 0 ||
      return $?
  else
    # An instance that is not currently ready cannot be drained, so no region list is generated.
    sb_phase_row drain skip "$start"
  fi
  start="$(px_epoch_ms)"
  if [ "$state" != running ]; then
    # A pod with no running container has nothing to stop; deleting the pod directly triggers the
    # creation of its replacement.
    sb_phase_row stop skip "$start"
    phase=start
  fi
  # Because the StatefulSet controller recreates a pod only when the existing pod is deleted,
  # deleting the pod also serves to apply the latest rendered pod template during a restart.
  if ! px_k8s_call "$drain" delete pod "$pod" --wait=true "--timeout=${drain}s" >&2; then
    sb_phase_fail "$phase" "$start"
    px_error "the delete of $role/$index failed"
    return "$SB_EXIT_FAIL"
  fi
  if [ "$phase" = stop ]; then
    sb_phase_row stop ok "$start"
    start="$(px_epoch_ms)"
  fi
  px_k8s_await_new "$scope" "$role" "$index" "$uid" "$ready" "$start" || return $?
  start="$(px_epoch_ms)"
  if [ "$drained" = 0 ]; then
    sb_phase_row rebalance skip "$start"
    return 0
  fi
  cmd="$(sb_mover_cmd load "$host" "$port" "$drain" "$file")"
  sb_mover_phase rebalance "$start" "$role/$index" "$drain" "$cmd" "$scope" tooling 0
}

# ---------------------------------------------------------------------------
# 12. Diagnostic evidence: restarts, termination reasons, OOM, and resource sampling
# ---------------------------------------------------------------------------
# The kubelet tracks container restarts only within the lifetime of a single pod object, resetting
# restartCount to 0 whenever a replacement pod is created. To maintain cumulative restart counts
# across pod recreations, the per-scope restart table records each pod's uid alongside its carried
# and observed restart counts; when a new pod uid appears for the same role and index, the prior
# pod's final restart count plus one additional restart for the pod replacement is carried forward.

## @description  Reports cumulative instance restart counts and updates the scope restart table.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @return       Writes one restart TSV row per instance to stdout; exits with 0, 1 if kubectl fails
##               to respond or the restart table cannot be written, or 2 on invalid arguments.
sb_restart_count() {
  local scope=${1:-} rows line path tmp seen="" table="" row key carried old_uid old_last
  local out="" keys=" "
  px_k8s_scope_load "$scope" || return $?
  rows="$(px_k8s_pods)" || return "$SB_EXIT_FAIL"
  path="$(sb_record_path k8s pods "$scope")" || return "$SB_EXIT_FAIL"
  [ ! -f "$path" ] || seen="$(cat "$path")"
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    key="$PXK_P_ROLE/$PXK_P_INDEX"
    case "$keys" in
    *" $key "*) continue ;;
    esac
    px_k8s_pick "$rows" "$PXK_P_ROLE" "$PXK_P_INDEX" >/dev/null || continue
    keys="$keys$key "
    carried=0
    # Encountering an instance key that is not yet present in the restart table is expected on first
    # sight; exiting inside the command substitution prevents triggering the caller's error trap.
    if row="$(sb_row_get "$key" "$seen" || exit "$?")"; then
      IFS="$PX_TAB" read -r old_uid carried old_last <<<"$row"
      [[ $carried =~ $PX_RE_INT ]] || carried=0
      [[ $old_last =~ $PX_RE_INT ]] || old_last=0
      if [ "$old_uid" != "$PXK_P_UID" ]; then
        carried=$((carried + old_last + 1))
      fi
    fi
    table="$table$(px_tsv_row "$key" "$PXK_P_UID" "$carried" "$PXK_P_RESTARTS")$PX_NL"
    out="$out$(px_tsv_row "$PXK_P_ROLE" "$PXK_P_INDEX" "$((carried + PXK_P_RESTARTS))")$PX_NL"
  done <<EOF
$rows
EOF
  # Retain table entries for pods that are temporarily absent so that their eventual replacement pod
  # is still counted as a restart.
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    case "$keys" in
    *" ${row%%"$PX_TAB"*} "*) continue ;;
    esac
    table="$table$row$PX_NL"
  done <<EOF
$seen
EOF
  # Write to a temporary file and atomically rename it into place so concurrent readers never
  # observe a partially written restart table.
  tmp="$path.$$.$RANDOM"
  if ! printf '%s' "$table" >"$tmp" || ! mv -f "$tmp" "$path"; then
    rm -f "$tmp"
    px_error "cannot write the restart table $path"
    return "$SB_EXIT_FAIL"
  fi
  printf '%s' "$out" | LC_ALL=C sort -t"$PX_TAB" -k1,1 -k2,2n
}

## @description  Normalizes a container exit code and reason (CODE REASON FINISHED) into a term row.
## @return       Writes one normalized termination TSV row to stdout and exits with 0.
px_k8s_term_row() {
  local code=${1:-0} reason signal=""
  [[ $code =~ $PX_RE_INT ]] || code=0
  if [ "$code" -gt 128 ] && [ "$code" -lt 256 ]; then
    signal=$((code - 128))
  fi
  if [ "${2:-}" = OOMKilled ]; then
    reason=oom
  elif [ "$code" = 0 ]; then
    reason=completed
  elif [ -n "$signal" ]; then
    reason=killed
  else
    reason=error
  fi
  px_tsv_row "$reason" "$code" "$signal" "$(px_tsv_clean "${3:-}")"
}

## @description  Reports normalized termination evidence for an instance in the contract vocabulary.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @param        which: current (default) or previous (the container instance that terminated prior)
## @return       Writes one term TSV row to stdout; exits with 0, 1 if kubectl fails to respond, 2
##               on invalid arguments, or 6 if the instance or previous container does not exist.
sb_termination_reason() {
  local which=${4:-current}
  sb_value_arg sb_termination_reason which term_which "$which" || return $?
  px_k8s_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  if [ "$which" = previous ]; then
    if [ -z "$PXK_P_LAST_CODE" ]; then
      px_error "${2:-}/${3:-} has no previous container"
      return "$SB_EXIT_NO_OBJECT"
    fi
    px_k8s_term_row "$PXK_P_LAST_CODE" "$PXK_P_LAST_REASON" "$PXK_P_LAST_FINISHED"
    return 0
  fi
  if [ "$PXK_P_REASON" = Evicted ]; then
    px_tsv_row evicted "${PXK_P_CODE:-0}" "" "$(px_tsv_clean "$PXK_P_FINISHED")"
  elif [ "$PXK_P_STATE" = running ]; then
    px_tsv_row running 0 "" ""
  elif [ -n "$PXK_P_CODE" ]; then
    px_k8s_term_row "$PXK_P_CODE" "$PXK_P_TERM_REASON" "$PXK_P_FINISHED"
  elif [ -n "$PXK_P_LAST_CODE" ]; then
    px_k8s_term_row "$PXK_P_LAST_CODE" "$PXK_P_LAST_REASON" "$PXK_P_LAST_FINISHED"
  else
    px_tsv_row unknown 0 "" ""
  fi
}

# Determines whether an instance suffered an out-of-memory kill by checking its current and prior
# container termination states first, and then inspecting namespace events for the pod carrying an
# OOMKilling or OOMKilled reason or a message containing OOMKilled.
## @description  Reports whether an instance was OOM-killed from termination state or pod events.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @return       Writes one oom TSV row to stdout; exits with 0, 1 if kubectl fails to respond, 2 on
##               usage error, or 6 if the instance does not exist.
sb_oom_killed() {
  local events reason message
  px_k8s_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  if [ "$PXK_P_TERM_REASON" = OOMKilled ]; then
    px_tsv_row yes "the container terminated OOMKilled, exit code ${PXK_P_CODE:-137}"
    return 0
  elif [ "$PXK_P_LAST_REASON" = OOMKilled ]; then
    px_tsv_row yes "the last container terminated OOMKilled, exit code ${PXK_P_LAST_CODE:-137}"
    return 0
  fi
  # Container termination state serves as the primary evidence because a cgroup OOM kill terminates
  # the entire container even when a descendant process rather than PID 1 exhausted the memory
  # limit, and k3s emits no OOM event in that case. Querying events for the individual pod via a
  # field selector provides a fallback for clusters that record a process-level OOM kill without
  # terminating the container.
  events="$(px_k8s_call 30 get events --field-selector "involvedObject.name=$PXK_P_NAME" \
    -o 'jsonpath={range .items[*]}{.reason}|{.message}{"\n"}{end}')" || {
    px_error "kubectl did not list the events of $PXK_P_NAME"
    return "$SB_EXIT_FAIL"
  }
  while IFS='|' read -r reason message; do
    case "$reason|$message" in
    OOMKilling\|* | OOMKilled\|* | *\|*OOMKilled*)
      px_tsv_row yes "a namespace event $reason names pod $PXK_P_NAME:" \
        "$(px_tsv_clean "$message")"
      return 0
      ;;
    esac
  done <<EOF
$events
EOF
  px_tsv_row no "no termination reason or event is OOMKilled, restart count $PXK_P_RESTARTS"
}

# Queries the Kubernetes metrics API for current CPU and memory usage across all running instances.
# Because the metrics API does not expose network or block I/O counters, those TSV columns are left
# empty rather than filled with 0.
## @description  Captures a point-in-time resource snapshot of running instances via metrics API.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @return       Writes sample TSV rows to stdout; exits with 0, 2 on invalid arguments, or 5 if
##               the cluster metrics API does not respond.
sb_sample_resources() {
  local scope=${1:-} rows line top ts pod container cpu mem limits="" milli key
  px_k8s_scope_load "$scope" || return $?
  rows="$(px_k8s_pods)" || return "$SB_EXIT_UNSUPPORTED"
  top="$(px_k8s_call 60 top pods -l "$(px_k8s_selector)" --no-headers --containers \
    2>/dev/null)" || {
    px_error "the metrics API of context $PXK_CONTEXT did not answer, so there is no sample"
    return "$SB_EXIT_UNSUPPORTED"
  }
  while IFS= read -r line; do
    px_k8s_pod_split "$line" || continue
    [ "$PXK_P_STATE" = running ] || continue
    limits="$limits$PXK_P_NAME$PX_TAB$PXK_P_ROLE/$PXK_P_INDEX/$(px_k8s_bytes \
      "$PXK_P_LIMIT")$PX_NL"
  done <<EOF
$rows
EOF
  ts="$(px_epoch_ms)"
  while read -r pod container cpu mem; do
    [ "$container" = px ] || continue
    key="$(sb_row_get "$pod" "$limits")" || continue
    milli="$(px_k8s_millicores "$cpu")"
    px_tsv_row "$ts" "${key%%/*}" "$(printf '%s' "$key" | cut -d/ -f2)" \
      "$((milli / 10)).$((milli % 10))" "$(px_k8s_bytes "$mem")" "${key##*/}" "" "" "" ""
  done <<EOF
$top
EOF
  return 0
}

# ---------------------------------------------------------------------------
# 13. Service endpoints and container images
# ---------------------------------------------------------------------------
# Every pod binds the base port of each port family directly on its own pod address, and no Service
# exposes ports outside the cluster. Consequently, the host can reach an instance port only through
# a local tunnel established by running kubectl port-forward on 127.0.0.1 to the base port of the
# target pod. A tunnel starts when a caller queries an endpoint, outlives that invocation, and
# terminates when the endpoint is released or the scope is torn down. Its state record under the
# state directory persists the tunnel PID, the allocated local port, and the pod UID, ensuring that
# an active tunnel to the same pod is reused while a tunnel to a pod replaced by its controller is
# restarted.

## @description  Looks up the base port for ROLE and port family NAME from the rendered port map.
## @return       Writes the port number to stdout and exits with 0, or 6 if the port is not defined.
px_k8s_port_value() {
  local name value=""
  name="$(sb_port_var "${1:-}" "${2:-}")"
  if [ -n "$PXK_DEPLOY_DIR" ] && [ -f "$PXK_DEPLOY_DIR/ports.generated.env" ]; then
    value="$(sed -n "s/^$name=//p" "$PXK_DEPLOY_DIR/ports.generated.env" | sed -n 1p)"
  fi
  [ -n "$value" ] || value="${!name:-}"
  case "$value" in
  '' | *[!0-9]*) return "$SB_EXIT_NO_OBJECT" ;;
  esac
  printf '%s\n' "$value"
}

# Verifies the process command line because a recorded PID may be reassigned to an unrelated
# process after the tunnel exits, such as after a host restart.
## @description  Tests whether the specified process ID (PID) is running a kubectl port-forward.
## @return       Exits with 0 when the process is a live port-forward, or 1 otherwise.
px_k8s_tunnel_live() {
  local args
  [[ ${1:-} =~ $PX_RE_INT ]] || return "$SB_EXIT_FAIL"
  args="$(ps -p "$1" -o args= 2>/dev/null)" || return "$SB_EXIT_FAIL"
  case "$args" in
  *port-forward*) return 0 ;;
  esac
  return "$SB_EXIT_FAIL"
}

# Signals the recorded PID only if its command line still belongs to a port-forward process.
## @description  Terminates the tunnel for a record file (PATH) and removes its record and log.
## @return       Exits with 0.
px_k8s_tunnel_stop() {
  local record=$1 pid=""
  [ ! -f "$record" ] || IFS="$PX_TAB" read -r pid _ <"$record" || true
  if px_k8s_tunnel_live "$pid"; then
    px_kill_tree "$pid" || px_warn "the tunnel $pid did not stop"
  fi
  rm -f "$record" "$record.log"
}

## @description  Tests whether a new tunnel (PID, LOG) has logged its local port or terminated.
## @return       Exits with 0 once the tunnel has settled, or 1 while startup is still in progress.
px_k8s_tunnel_settled() {
  grep -q '^Forwarding from 127\.0\.0\.1:[0-9]' "$2" 2>/dev/null || ! kill -s 0 "$1" 2>/dev/null
}

# Reuses an active loopback tunnel when one already targets the current pod UID, or starts a new
# background tunnel that persists until released or torn down and outputs 127.0.0.1 and the local
# port. Exits with status 6 for an unknown instance, an unknown port, or a role without a Service.
## @description  Opens or reuses a local loopback tunnel to a named port of a running instance.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: a role backed by a headless Service
## @param        index: the zero-based instance index
## @param        port_name: a port family name from the port map (for example, rpc)
## @return       Writes one endpoint TSV row; exits with 0, 1 if kubectl fails, the pod is not
##               running, or the tunnel ends, 2 on usage, 4 on 30 s timeout, or 6 if not found.
sb_endpoint() {
  local scope=${1:-} role=${2:-} index=${3:-} port=${4:-} value record pid="" lport="" uid="" rc=0
  if [ -z "$port" ]; then
    px_error "sb_endpoint needs a scope, a role, an index and a port name"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_instance "$scope" "$role" "$index" || return $?
  case "$role" in
  setup | client | tooling)
    px_error "the role $role has no Service, so it has no port to forward"
    return "$SB_EXIT_NO_OBJECT"
    ;;
  esac
  if ! value="$(px_k8s_port_value "$role" "$port")"; then
    px_error "the port map of the render holds no port $port for $role"
    return "$SB_EXIT_NO_OBJECT"
  fi
  if [ "$PXK_P_STATE" != running ]; then
    px_error "instance $role/$index is $PXK_P_STATE, so no tunnel can reach it"
    return "$SB_EXIT_FAIL"
  fi
  record="$(sb_record_path k8s tunnel "$scope|$role|$index|$port")" || return "$SB_EXIT_FAIL"
  if [ -f "$record" ]; then
    IFS="$PX_TAB" read -r pid lport uid _ <"$record" || true
    # Reusing a tunnel requires that the recorded pod UID matches the current pod, the recorded PID
    # is still an active port-forward process, and the tunnel log confirms the recorded local port.
    if [ "$uid" = "$PXK_P_UID" ] && px_k8s_tunnel_live "$pid" &&
      grep -q "^Forwarding from 127\.0\.0\.1:$lport " "$record.log" 2>/dev/null; then
      px_tsv_row 127.0.0.1 "$lport"
      return 0
    fi
    px_k8s_tunnel_stop "$record"
  fi
  # Detach all standard streams so that invoking this function inside a command substitution returns
  # without waiting for the background tunnel to close, while letting kubectl allocate a free local
  # port.
  (px_k8s_call bg port-forward --address 127.0.0.1 "pod/$PXK_P_NAME" ":$value") \
    </dev/null >"$record.log" 2>&1 &
  pid=$!
  px_wait_until 30 0.2 px_k8s_tunnel_settled "$pid" "$record.log" || true
  lport="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "$record.log" |
    sed -n 1p)"
  if ! kill -s 0 "$pid" 2>/dev/null; then
    wait "$pid" 2>/dev/null || true
    px_error "the tunnel to $role/$index port $port ended:" \
      "$(px_tsv_clean "$(cat "$record.log" 2>/dev/null)")"
    rc=$SB_EXIT_FAIL
  elif [ -z "$lport" ]; then
    px_kill_tree "$pid" || true
    px_error "the tunnel to $role/$index port $port did not open in 30s"
    rc=$SB_EXIT_TIMEOUT
  fi
  if [ "$rc" != 0 ]; then
    rm -f "$record.log"
    return "$rc"
  fi
  if ! px_tsv_row "$pid" "$lport" "$PXK_P_UID" "$scope" >"$record"; then
    px_k8s_tunnel_stop "$record"
    return "$SB_EXIT_FAIL"
  fi
  px_tsv_row 127.0.0.1 "$lport"
}

## @description  Stops the tunnel for a single endpoint, or all tunnels in the scope when given all.
## @param        scope_id: a valid scope identifier for this backend
## @param        role: the target role name (ignored when port_name is all)
## @param        index: the zero-based instance index (ignored when port_name is all)
## @param        port_name: a port name or all (required)
## @return       Exits with 0 for a valid scope id (even if no tunnel exists), 1 if the state
##               directory cannot be accessed, or 2 on usage error.
sb_endpoint_release() {
  local scope=${1:-} port=${4:-} record dir owner
  if [ -z "$port" ]; then
    px_error "sb_endpoint_release needs a scope, a role, an index and a port name or all"
    return "$SB_EXIT_USAGE"
  fi
  px_k8s_scope_load "$scope" || return $?
  if [ "$port" != all ]; then
    record="$(sb_record_path k8s tunnel "$scope|${2:-}|${3:-}|$port")" ||
      return "$SB_EXIT_FAIL"
    px_k8s_tunnel_stop "$record"
    return 0
  fi
  dir="$(sb_state_dir k8s)" || return "$SB_EXIT_FAIL"
  for record in "$dir"/tunnel-*.tsv; do
    [ -f "$record" ] || continue
    owner=""
    IFS="$PX_TAB" read -r _ _ _ owner _ <"$record" || true
    [ "$owner" = "$scope" ] || continue
    px_k8s_tunnel_stop "$record"
  done
  return 0
}

## @description  Reports the configured image reference and resolved image ID for one instance.
## @param        scope_id: the scope identifier returned by sb_scope_create
## @param        role: the target role name
## @param        index: the zero-based instance index
## @return       Writes one image TSV row to stdout; exits with 0, 1 if kubectl fails to respond, 2
##               on invalid arguments, or 6 if the instance does not exist.
sb_image_ref() {
  px_k8s_instance "${1:-}" "${2:-}" "${3:-}" || return $?
  px_tsv_row "$PXK_P_IMAGE" "$PXK_P_IMAGE_ID"
}

# The Kubernetes backend does not build container images directly; images are built via the Compose
# backend and imported into the Kubernetes cluster by a separate step.
## @description  Rejects image build requests as unsupported on the Kubernetes backend.
## @param        context_dir: the staged build context directory (unused)
## @param        tag: the target local repository:tag (unused)
## @param        NAME=VALUE...: build arguments (unused)
## @return       Exits with 5.
sb_image_build() {
  px_error "the Kubernetes backend builds no image: build it with the compose backend"
  return "$SB_EXIT_UNSUPPORTED"
}

# Verifies that every local image referenced by the rendered manifests is cached on every node of
# the PX_K8S_CONTEXT cluster, which is required because local images use imagePullPolicy Never. Any
# image referenced by digest counts as present with its digest as the image ID because the kubelet
# pulls it on demand and a pull failure aborts the readiness barrier. Writes one image TSV row per
# image, leaving the image ID column empty when an image is missing from any node.
## @description  Verifies that all images in a rendered deployment are available to cluster nodes.
## @param        deployment_dir: the rendered deployment directory containing manifests/
## @return       Writes one image TSV row per image; exits with 0 if all are present, 1 if kubectl
##               fails, 2 on usage or missing manifests or context, or 6 if any image is absent.
sb_image_present() {
  local dir=${1:-} ref refs nodes="" node names found missing=0 cluster fmt
  if [ -z "$dir" ] || [ ! -d "$dir/manifests" ]; then
    px_error "sb_image_present needs a deployment directory that holds manifests/"
    return "$SB_EXIT_USAGE"
  fi
  cluster="$(sed -n 's/^PX_CLUSTER_ID=//p' "$dir/topology.env" 2>/dev/null)"
  px_k8s_target_load "${PX_K8S_CONTEXT:-}" "$(sb_project_prefix)-${cluster:-default}" ||
    return $?
  refs="$(cat "$dir"/manifests/*.yaml "$dir"/groups/*/*.yaml 2>/dev/null |
    sed -n 's/^[[:space:]]*image:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' |
    LC_ALL=C sort -u)"
  if printf '%s\n' "$refs" | grep -v '@sha256:' | grep -q .; then
    fmt='jsonpath={range .items[*]}{.metadata.name}|'
    fmt="$fmt"'{range .status.images[*]}{.names[*]} {end}{"\n"}{end}'
    nodes="$(px_k8s_call 30 get nodes -o "$fmt")" || {
      px_error "the API server of context $PXK_CONTEXT did not list its nodes"
      return "$SB_EXIT_FAIL"
    }
  fi
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    case "$ref" in
    *@sha256:*)
      px_tsv_row "$ref" "${ref##*@}"
      continue
      ;;
    esac
    # An image is considered present only if every cluster node holds it; found retains the matched
    # image name from the final node inspected so it can be reported in the output row.
    found=""
    while IFS='|' read -r node names; do
      [ -n "$node" ] || continue
      case " $names " in
      *" $ref "*) found=$ref ;;
      *"/$ref "*)
        found=" $names "
        found=${found%%"/$ref "*}
        found="${found##* }/$ref"
        ;;
      *)
        found=""
        break
        ;;
      esac
    done <<EOF
$nodes
EOF
    [ -n "$found" ] || missing=1
    px_tsv_row "$ref" "$found"
  done <<LIST
$refs
LIST
  [ "$missing" = 0 ] || return "$SB_EXIT_NO_OBJECT"
}

# Container images are pushed from the local engine via the Compose backend rather than by the
# Kubernetes backend.
## @description  Rejects image push requests as unsupported on the Kubernetes backend.
## @param        image_id: local image digest sha256:<64 hex> (unused)
## @param        target_ref: destination image reference <registry>/<repository>:<tag> (unused)
## @return       Exits with 5.
sb_image_push() {
  px_error "the Kubernetes backend pushes no image: px-cluster push pushes it with the" \
    "compose backend"
  return "$SB_EXIT_UNSUPPORTED"
}

# Base image digests are resolved by the Compose backend during image builds rather than by the
# Kubernetes backend.
## @description  Rejects base image digest resolution as unsupported on the Kubernetes backend.
## @param        ref: the base image reference (unused)
## @param        arch: target architecture, arm64 or amd64 (unused)
## @return       Exits with 5.
sb_image_base_digest() {
  px_error "the Kubernetes backend builds no image, so it resolves no base image digest"
  return "$SB_EXIT_UNSUPPORTED"
}
