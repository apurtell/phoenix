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

# This script is the manifest generator for the Kubernetes backend. It renders the deployment
# directory consumed by sb_provision in lib/backend/k8s.sh, following the directory specification
# in the "Kubernetes backend" section of docs/backends.md.
#
# It shares the common deployment kit in lib/deploy.sh with the Compose generator, including the
# role table, all configuration value checks, the plug-in render hooks, the topology record, the
# staging workflow, and the fail-closed validator. Because both generators rely on the same shared
# pipeline, any configuration rejected by one is rejected by the other with an identical diagnostic
# message, and both generators produce identical service tables for the same configuration.
#
# Six design rules govern this generator:
#   1. It only writes local files and never invokes kubectl.
#   2. It fails closed: pxg_require rejects unset variable names, pxg_expand rejects template
#      placeholders that lack a value, and pxg_validate rejects any rendered file in which an
#      unexpanded placeholder survives. Every rejection aborts before the render replaces any
#      existing target file, and the error message explicitly names the offending variable.
#   3. Every manifest document and every repeated YAML fragment is defined as a template under
#      k8s/templates. This script only selects, repeats, indents, and concatenates those expanded
#      templates, rendering any list with no items as [].
#   4. The container image renders its own runtime configuration inside each pod from the same
#      templates used on Compose: docker/image/conf is mounted from the px-conf ConfigMap, the
#      topology record and port map are provided via the px-env ConfigMap, and plug-in overlays
#      are provided via the px-conf-overlay ConfigMap. The container entrypoint therefore contains
#      no backend-specific branching.
#   5. Every pod binds to the base port of each port family. Because every pod receives its own
#      network address, no port offset is required to keep multiple instances apart.
#   6. A target configuration file and the run class modify bound configuration values only; the
#      templates and overlays are strictly read-only and are never written.
#
# Enabling PX_PHERF=1 renders the Distributed Pherf tier as the on-demand role group pherf. The
# controller runs as a single-replica Deployment fronted by a ClusterIP Service with a persistent
# volume claim mounted at its run directory, while the agents run as a Deployment resized via
# kubectl scale in which each agent derives its operational identity from its pod name. The agents
# read their ZooKeeper quorum exclusively from the pherf-target ConfigMap, which resolves to the
# harness ZooKeeper service for the harness target or the operator-supplied PX_PHERF_ZOOKEEPER for
# the external target (whose render contains the tier alone).

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/deploy.sh
. "$SELF/../lib/deploy.sh"

set -euo pipefail

# This header line is written at the top of topology.env and every generated manifest. A target
# directory whose topology.env begins with this marker is recognized as a rendered deployment that
# the generator is permitted to overwrite.
PXG_MARKER="# Rendered by manifests.gen.sh. Render again instead of editing."

# These directories hold the YAML templates for manifest documents, their repeated items, and the
# optional manifest overlays.
PXK_TEMPLATES="$SELF/templates"
PXK_OVERLAYS="$SELF/overlays"
# This directory holds the container image configuration templates packaged into the px-conf
# ConfigMap.
PXK_CONF_DIR="$PX_HOME/$PX_DEPLOY_SUBDIR/image/conf"

# These mount paths define the filesystem layout inside each pod for the base configuration
# templates rendered by the entrypoint, the plug-in overlay templates, the daemon log directory,
# and the persistent volume claim mount point.
PXK_CONF_MOUNT=/opt/px/conf
PXK_OVERLAY_MOUNT=/opt/px/conf-overlay
PXK_LOG_MOUNT=/var/lib/px/logs
PXK_DATA_MOUNT=/data
# This path is the mount point for the tmp persistent volume claim inside each harness pod, serving
# as the remote working directory for workload drivers (DV_REMOTE_ROOT) and collectors (CK_REMOTE).
# Because a recovery restart replaces the pod, mounting a persistent claim at this location
# preserves staged workload inputs and collector outputs across pod replacements during a run.
PXK_TMP_MOUNT=/tmp/px
# Mount path inside the pherf image where the Distributed Pherf controller mounts its run-directory
# persistent volume claim.
PXK_PHERF_RUN_MOUNT=/var/lib/pherf/run

# Kubernetes attaches this label to each StatefulSet pod to record its ordinal index.
PXK_POD_INDEX_LABEL=apps.kubernetes.io/pod-index
# Each daemon role's headless Service publishes pod addresses prior to readiness so peer instances
# can resolve one another during startup, whereas the Distributed Pherf tier controller is fronted
# by a ClusterIP Service instead.
PXK_HEADLESS="  clusterIP: None${PX_NL}  publishNotReadyAddresses: true"

# These non-daemon roles run without a long-lived daemon or a Kubernetes Service; alongside the
# setup Job, they are rendered as the standalone client and tooling Pods.
PXK_PODS="client tooling"

# These regular expressions enforce the Kubernetes DNS naming grammar for a namespace label, a
# StorageClass name, and the cluster domain.
PXK_LABEL_RE='^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'
PXK_SUBDOMAIN_RE='^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$'
# These regular expressions enforce the grammar for a node label key, a node label value, and a
# container image digest, while sb_registry_check enforces the grammar for an image registry.
PXK_LABEL_KEY_RE='^([a-z0-9]([-a-z0-9.]*[a-z0-9])?/)?[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$'
PXK_LABEL_VALUE_RE='^[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$'
PXK_DIGEST_RE='^sha256:[0-9a-f]{64}$'

# These variables hold the computed state accumulated during a single render pass.
PXK_NAMESPACE=""
PXK_UID=""
PXK_GID=""
PXK_SEARCHES=""
PXK_PORTS=""
PXK_EAGER_FILES=""
PXK_GROUP_FILES=""
PXK_CHECKED=()
# These variables store the container image reference and pull policy for every pod alongside the
# target placement constraints, each remaining an empty string when the target configures none.
PXK_IMAGE=""
PXK_PHERF_IMAGE=""
PXK_PULL_POLICY=""
PXK_NODE_SELECTOR=""
PXK_TOLERATIONS=""
PXK_PULL_SECRETS=""

px_cli_spec <<'EOF'
about Renders the Kubernetes deployment directory of one cluster under $PX_OUT, by
+ default $PX_OUT/deploy-k8s/<cluster>.
about It writes files only and never calls the Kubernetes API.
about The run class is required, as for the Compose generator. The configuration loads
+ k8s/env/k8s.env. PX_K8S_STORAGE_CLASS has no default: k8s/env/k3d.env or a
+ target file names it. The scaled roles are datanode, nodemanager and
+ regionserver.
exits 1 also means that the directory cannot be written or that a placeholder
+ survived, 2 an unset or invalid value or a target directory that is not a
+ rendered deployment, 6 a --config file is absent.
example manifests.gen.sh --class validation --config k8s/env/k3d.env
EOF

## @description  Expands a template file (path, columns, NAME=VALUE...) and indents each output
##               line by the specified column count.
## @return       Writes the text to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_tf() {
  local file=$1 pad text
  printf -v pad '%*s' "$2" ''
  shift 2
  text="$(pxg_expand "$file" "$@")" || exit $?
  printf '%s\n' "$text" | sed "s/^/$pad/"
}

## @description  Expands a named template from k8s/templates (name, columns, NAME=VALUE...) by
##               delegating to pxk_tf.
## @return       Writes the text to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_t() { pxk_tf "$PXK_TEMPLATES/$1.yaml.tmpl" "${@:2}"; }

## @description  Formats a YAML collection (columns, items, empty form) indented by the column
##               count, or emits the empty form ([] by default) when no items are present.
## @return       Writes the formatted text without its trailing newline to stdout and exits 0.
pxk_list() {
  local pad
  if [ -z "${2:-}" ]; then
    printf -v pad '%*s' "$1" ''
    printf '%s%s' "$pad" "${3:-[]}"
    return 0
  fi
  printf '%s' "${2%"$PX_NL"}"
}

## @description  Renders key-value pairs (columns, KEY=VALUE...) in order through the pair template.
## @return       Writes the lines to stdout and exits 0, or exits 2 on an empty key or value.
pxk_pairs() {
  local columns=$1 pair
  shift
  for pair in "$@"; do
    pxk_t pair "$columns" "PX_M_KEY=${pair%%=*}" "PX_M_VALUE=${pair#*=}"
  done
}

## @description  Renders Kubernetes object labels (columns, role, extra KEY=VALUE...) starting with
##               the cluster label, followed by the role label when set and any extra pairs.
## @return       Writes the rendered label lines to stdout and exits 0.
pxk_labels() {
  local columns=$1 role=$2 prefix=$PX_LABEL_PREFIX
  shift 2
  set -- "$prefix.cluster=$PX_CLUSTER_ID" ${role:+"$prefix.role=$role"} "$@"
  pxk_pairs "$columns" "$@"
}

## @description  Computes a container CPU allocation in millicores from its memory limit in MiB at
##               a rate of PX_K8S_CPU_MILLI_PER_GIB per GiB, rounded up.
## @return       Writes the computed millicore value to stdout and exits 0.
pxk_cpu() {
  printf '%s\n' "$((($1 * PX_K8S_CPU_MILLI_PER_GIB + 1023) / 1024))"
}

## @description  Resolves the application protocol for a port family, returning http for web,
##               shuffle, or REST families and tcp for all others.
## @return       Writes http or tcp to stdout and exits 0.
pxk_port_protocol() {
  case "${1:-}" in
  http | shuffle | rest) printf 'http\n' ;;
  *) printf 'tcp\n' ;;
  esac
}

## @description  Resolves the promoted digest for a local image reference (ref) from the
##               comma-separated ref=sha256:<hex> pairs in PX_K8S_IMAGE_DIGESTS.
## @return       Writes the digest and exits 0, exits 6 if absent, or exits 2 on a malformed pair.
pxk_digest_of() {
  local pair pairs=()
  IFS=, read -r -a pairs <<<"${PX_K8S_IMAGE_DIGESTS:-}"
  for pair in ${pairs[@]+"${pairs[@]}"}; do
    if [ "${pair%=*}" = "$pair" ] || ! [[ ${pair##*=} =~ $PXK_DIGEST_RE ]]; then
      px_die "$PX_EXIT_USAGE" "PX_K8S_IMAGE_DIGESTS holds '$pair', not ref=sha256:<64 hex>"
    fi
    if [ "${pair%=*}" = "$1" ]; then
      printf '%s\n' "${pair##*=}"
      return 0
    fi
  done
  return "$PX_EXIT_NO_OBJECT"
}

# Because a mutable registry tag can be reassigned whereas a content digest is immutable, remote
# pod manifests pin the exact digest recorded in a passing validation bundle.
## @description  Resolves the digest-pinned remote image reference for a local tag (tag) in
##               PX_K8S_REGISTRY.
## @return       Writes the remote reference to stdout and exits 0, or exits 2 if no digest exists.
pxk_remote_ref() {
  local digest
  if ! digest="$(pxk_digest_of "$1")"; then
    px_die "$PX_EXIT_USAGE" "a remote render references images by digest, and" \
      "PX_K8S_IMAGE_DIGESTS names none for $1: render it with" \
      "px-cluster push BUNDLE"
  fi
  printf '%s\n' "$PX_K8S_REGISTRY/${1%%:*}@$digest"
}

# Local renders reference local image tags with a Never pull policy, whereas remote targets resolve
# each image by immutable digest in PX_K8S_REGISTRY and pull on demand whenever a node lacks the
# image. The harness image serves the harness cluster roles, while the pherf image serves the tier.
## @description  Configures the container image references and pull policy applied to every pod.
## @return       Exits 0, or exits 2 on a rejected registry or a remote render missing a digest.
pxk_image_source() {
  PXK_IMAGE="" PXK_PHERF_IMAGE="" PXK_PULL_POLICY=Never
  if [ -z "${PX_K8S_REGISTRY:-}" ]; then
    [ "$PX_PHERF_TARGET" != harness ] || PXK_IMAGE=$PX_IMAGE_TAG
    [ "$PX_PHERF" != 1 ] || PXK_PHERF_IMAGE=$PX_PHERF_IMAGE_TAG
    return 0
  fi
  if ! sb_registry_check "$PX_K8S_REGISTRY"; then
    px_die "$PX_EXIT_USAGE" "PX_K8S_REGISTRY='$PX_K8S_REGISTRY' must be host[:port][/path] in" \
      "lower case, its host with a dot or a port, or localhost"
  fi
  [ "$PX_PHERF_TARGET" != harness ] || PXK_IMAGE="$(pxk_remote_ref "$PX_IMAGE_TAG")" || exit $?
  [ "$PX_PHERF" != 1 ] || PXK_PHERF_IMAGE="$(pxk_remote_ref "$PX_PHERF_IMAGE_TAG")" || exit $?
  PXK_PULL_POLICY=IfNotPresent
}

## @description  Configures the target placement settings for every pod, including the node
##               selector, tolerations, and image pull secret.
## @return       Exits 0, or exits 2 if any value violates its grammar, naming the offending key.
pxk_placement() {
  local item key value effect items=()
  PXK_NODE_SELECTOR="" PXK_TOLERATIONS="" PXK_PULL_SECRETS=""
  IFS=, read -r -a items <<<"${PX_K8S_NODE_SELECTOR:-}"
  for item in ${items[@]+"${items[@]}"}; do
    key=${item%%=*} value=${item#*=}
    if [ "$key" = "$item" ] || ! [[ $key =~ $PXK_LABEL_KEY_RE ]] ||
      ! [[ $value =~ $PXK_LABEL_VALUE_RE ]]; then
      px_die "$PX_EXIT_USAGE" "PX_K8S_NODE_SELECTOR holds '$item', not key=value of a label"
    fi
    PXK_NODE_SELECTOR="$PXK_NODE_SELECTOR$(pxk_pairs 2 "$item")$PX_NL"
  done
  items=()
  IFS=, read -r -a items <<<"${PX_K8S_TOLERATIONS:-}"
  for item in ${items[@]+"${items[@]}"}; do
    effect=${item##*:} key=${item%:*} value=""
    if [ "$key" != "${key%%=*}" ]; then
      value=${key#*=} key=${key%%=*}
    fi
    case "$effect" in
    NoSchedule | PreferNoSchedule | NoExecute) ;;
    *) effect="" ;;
    esac
    if [ "$item" = "${item%:*}" ] || [ -z "$effect" ] || ! [[ $key =~ $PXK_LABEL_KEY_RE ]] ||
      { [ -n "$value" ] && ! [[ $value =~ $PXK_LABEL_VALUE_RE ]]; }; then
      px_die "$PX_EXIT_USAGE" "PX_K8S_TOLERATIONS holds '$item', not key[=value]:Effect with" \
        "the effect NoSchedule, PreferNoSchedule or NoExecute"
    fi
    if [ -n "$value" ]; then
      item="$(pxk_t toleration-equal 2 "PX_M_KEY=$key" "PX_M_VALUE=$value" \
        "PX_M_EFFECT=$effect")" || exit $?
    else
      item="$(pxk_t toleration-exists 2 "PX_M_KEY=$key" "PX_M_EFFECT=$effect")" || exit $?
    fi
    PXK_TOLERATIONS="$PXK_TOLERATIONS$item$PX_NL"
  done
  if [ -n "${PX_K8S_PULL_SECRET:-}" ]; then
    if ! [[ $PX_K8S_PULL_SECRET =~ $PXK_SUBDOMAIN_RE ]]; then
      px_die "$PX_EXIT_USAGE" "PX_K8S_PULL_SECRET='$PX_K8S_PULL_SECRET' must be a DNS name"
    fi
    PXK_PULL_SECRETS="$(pxk_t pull-secret 2 "PX_M_NAME=$PX_K8S_PULL_SECRET")$PX_NL" || exit $?
  fi
}

## @description  Validates the Kubernetes backend configuration variables and initializes the
##               derived PXK_* state variables.
## @return       Exits 0, or exits 2 on an unset or refused value or when the host user is root.
pxk_configure() {
  local name
  pxg_require PX_K8S_STORAGE_CLASS PX_K8S_VOLUME_GIB PX_K8S_CPU_MILLI_PER_GIB \
    PX_K8S_MAX_PAUSE_S PX_K8S_CLUSTER_DOMAIN PX_PROJECT_PREFIX PX_TIMEOUT_STOP
  for name in PX_K8S_VOLUME_GIB PX_K8S_CPU_MILLI_PER_GIB PX_K8S_MAX_PAUSE_S PX_TIMEOUT_STOP; do
    pxg_check_mib "$name"
  done
  for name in PX_K8S_STORAGE_CLASS PX_K8S_CLUSTER_DOMAIN; do
    if ! [[ ${!name} =~ $PXK_SUBDOMAIN_RE ]]; then
      px_die "$PX_EXIT_USAGE" "$name='${!name}' must be a DNS name: lower case letters," \
        "digits, - and ."
    fi
  done
  # Because the Kubernetes namespace scopes the entire deployment on the cluster, the combined
  # project prefix and cluster name must conform to the DNS label grammar of a namespace.
  PXK_NAMESPACE="$PX_PROJECT_PREFIX-$PX_CLUSTER_ID"
  if ! [[ $PXK_NAMESPACE =~ $PXK_LABEL_RE ]] || [ "${#PXK_NAMESPACE}" -gt 63 ]; then
    px_die "$PX_EXIT_USAGE" "the namespace '$PXK_NAMESPACE' of cluster $PX_CLUSTER_ID is not" \
      "a DNS label of at most 63 characters: on Kubernetes a cluster name holds lower" \
      "case letters, digits and - only"
  fi
  # The container image executes as the host user that built it, because scripts/stage-artifacts.sh
  # embeds the host user and group IDs into the image build as PX_UID and PX_GID.
  PXK_UID="$(id -u)"
  PXK_GID="$(id -g)"
  if ! [[ $PXK_UID =~ ^[1-9][0-9]*$ ]] || ! [[ $PXK_GID =~ ^[0-9]+$ ]]; then
    px_die "$PX_EXIT_USAGE" "the image runs as the user that builds it, and that user is" \
      "root or has no number here (uid '$PXK_UID'): build and render as another user"
  fi
  pxg_check_switch PX_K8S_MESH
  pxg_check_switch PX_K8S_MESH_CORE
  pxk_image_source
  pxk_placement
}

## @description  Determines whether a role requires a headless Service, which applies to every
##               rendered daemon role.
## @return       Exits 0 for a daemon role, or 1 for the setup, client, tooling, and tier roles.
pxk_has_service() {
  case " setup $PXK_PODS pherf-controller pherf-agent " in
  *" $1 "*) return 1 ;;
  esac
  return 0
}

## @description  Collects the pod DNS search domains and assembles the port map containing the base
##               port of every port family for each rendered role.
## @return       Returns exit code 0.
pxk_collect() {
  local role family
  PXK_SEARCHES="" PXK_PORTS=""
  for role in $PXG_RENDERED; do
    for family in $(pxg_role_families "$role"); do
      PXK_PORTS="$PXK_PORTS$(sb_port_var "$role" "${family%%:*}")=$(pxg_port "$role" \
        "${family%%:*}" 0)$PX_NL"
    done
    pxk_has_service "$role" || continue
    PXK_SEARCHES="$PXK_SEARCHES$(pxk_t search 4 \
      "PX_M_SEARCH=$role.$PXK_NAMESPACE.svc.$PX_K8S_CLUSTER_DOMAIN")$PX_NL"
  done
}

## @description  Renders the port entries (columns, template, role) for a Service or container,
##               prefixing each port name with the protocol of its family.
## @return       Writes the rendered port items to stdout and exits 0.
pxk_ports() {
  local family protocol
  for family in $(pxg_role_families "$3"); do
    protocol="$(pxk_port_protocol "${family%%:*}")"
    pxk_t "$2" "$1" "PX_M_PORT_NAME=$protocol-${family%%:*}" "PX_M_PROTOCOL=$protocol" \
      "PX_M_PORT=$(pxg_port "$3" "${family%%:*}" 0)"
  done
}

# A StatefulSet pod reads its pod name and ordinal index from Kubernetes metadata fields, whereas
# other workload kinds use fixed index-zero identifiers.
## @description  Renders the container environment variables for a role (role, kind) to match the
##               environment of the corresponding Compose service.
## @return       Writes the rendered environment items to stdout and exits 0.
pxk_env() {
  local role=$1 kind=$2 key var opts
  key="$(pxg_role "$role" key)"
  opts="$(pxg_jvm_opts_of "$key")"
  # Assemble the environment variable assignments in the exact order used by the Compose service.
  set --
  [ -z "$PXG_OVERLAY_HOST" ] || set -- "$@" "PX_CONF_OVERLAY_DIR=$PXK_OVERLAY_MOUNT"
  [ -z "$(pxg_volume_of "$role" 0)" ] || set -- "$@" "PX_DATA_DIR=$PXK_DATA_MOUNT"
  var="PX_${key}_DIRECT_MIB" && set -- "$@" "PX_DIRECT_MIB=${!var}"
  var="PX_${key}_HEAP_MIB" && set -- "$@" "PX_HEAP_MIB=${!var}"
  if [ "$kind" = statefulset ]; then
    set -- "$@" "PX_HOSTNAME=@metadata.name"
    set -- "$@" "PX_INSTANCE_INDEX=@metadata.labels['$PXK_POD_INDEX_LABEL']"
  else
    set -- "$@" "PX_HOSTNAME=$role-0" "PX_INSTANCE_INDEX=0"
  fi
  var="PX_${key}_JDK" && set -- "$@" "PX_JDK=${!var}"
  [ -z "$opts" ] || set -- "$@" "PX_JVM_OPTS_EXTRA=$opts"
  var="PX_${key}_MEM_MIB" && set -- "$@" "PX_MEM_MIB=${!var}"
  set -- "$@" "PX_ROLE=$role"
  for var in "$@"; do
    case "${var#*=}" in
    @*) pxk_t env-field 6 "PX_M_ENV_NAME=${var%%=*}" "PX_M_FIELD=${var#*=@}" ;;
    *) pxk_t env-value 6 "PX_M_ENV_NAME=${var%%=*}" "PX_M_ENV_VALUE=${var#*=}" ;;
    esac
  done
}

# Co-locates the setup Job on the node holding the NameNode persistent volume claim and spreads
# DataNode and RegionServer pods across distinct cluster nodes.
## @description  Renders the pod affinity block for a role and workload kind (role, kind).
## @return       Writes the text to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_affinity() {
  if [ "$2" = job ]; then
    pxk_t affinity-namenode 0 "PX_M_SELECTOR=$(pxk_labels 12 namenode)"
    return 0
  fi
  case "$1" in
  datanode | regionserver | pherf-agent)
    pxk_t affinity-spread 0 "PX_M_SELECTOR=$(pxk_labels 14 "$1")"
    ;;
  *) pxk_t affinity-none 0 ;;
  esac
}

# The workload kind (statefulset, job, or pod) governs the restart policy, liveness probes, volume
# bindings, and placement rules of the rendered pod specification.
## @description  Renders the pod specification for a role (role, kind, columns), including its
##               container, resources, probes, volume mounts, volumes, and placement constraints.
## @return       Writes the text to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_pod_spec() {
  local role=$1 kind=$2 columns=$3 key var mem restart=Never probes="" env ports affinity
  local mounts="" volumes=""
  key="$(pxg_role "$role" key)"
  var="PX_${key}_MEM_MIB"
  mem=${!var}
  [ "$kind" != statefulset ] || restart=Always
  env="$(pxk_env "$role" "$kind")" || exit $?
  ports="$(pxk_list 6 "$(pxk_ports 6 container-port "$role")")" || exit $?
  if [ "$kind" = statefulset ]; then
    # A daemon is considered live as long as its management port responds. The liveness failure
    # threshold is sized beyond the longest pause permitted by the configuration so that a long
    # garbage-collection pause does not trigger a container restart.
    probes="$(pxk_t probes-daemon 4 \
      "PX_M_LIVENESS_THRESHOLD=$((PX_K8S_MAX_PAUSE_S / 10 + 2))")$PX_NL" || exit $?
  fi
  probes="$probes$(pxk_t probe-ready 4)" || exit $?
  mounts="$mounts$(pxk_t mount 6 PX_M_VOLUME=conf "PX_M_PATH=$PXK_CONF_MOUNT" \
    PX_M_READONLY=true)$PX_NL"
  volumes="$volumes$(pxk_t volume-configmap 2 PX_M_VOLUME=conf PX_M_NAME=px-conf)$PX_NL"
  if [ -n "$PXG_OVERLAY_HOST" ]; then
    mounts="$mounts$(pxk_t mount 6 PX_M_VOLUME=conf-overlay "PX_M_PATH=$PXK_OVERLAY_MOUNT" \
      PX_M_READONLY=true)$PX_NL"
    volumes="$volumes$(pxk_t volume-configmap 2 PX_M_VOLUME=conf-overlay \
      PX_M_NAME=px-conf-overlay)$PX_NL"
  fi
  # Backing the log directory with an emptyDir volume ensures that logs survive a container
  # restart, allowing post-run cluster checks to inspect the GC log of any daemon restarted by the
  # kubelet.
  mounts="$mounts$(pxk_t mount 6 PX_M_VOLUME=logs "PX_M_PATH=$PXK_LOG_MOUNT" \
    PX_M_READONLY=false)$PX_NL"
  volumes="$volumes$(pxk_t volume-emptydir 2 PX_M_VOLUME=logs)$PX_NL"
  if [ -n "$(pxg_volume_of "$role" 0)" ]; then
    mounts="$mounts$(pxk_t mount 6 PX_M_VOLUME=data "PX_M_PATH=$PXK_DATA_MOUNT" \
      PX_M_READONLY=false)$PX_NL"
    # A StatefulSet obtains its data volume from its volumeClaimTemplates entry, whereas the setup
    # Job explicitly mounts the NameNode persistent volume claim so it can format the filesystem
    # metadata.
    if [ "$kind" = job ]; then
      volumes="$volumes$(pxk_t volume-claim 2 PX_M_VOLUME=data \
        PX_M_CLAIM=data-namenode-0)$PX_NL"
    fi
  fi
  # Because the setup Job maintains no per-run working state, only StatefulSet pods and standalone
  # Pods mount a tmp persistent volume claim: a StatefulSet pod binds the claim generated by its
  # volumeClaimTemplates entry, whereas a standalone Pod binds the claim document rendered in the
  # same manifest file.
  if [ "$kind" != job ]; then
    mounts="$mounts$(pxk_t mount 6 PX_M_VOLUME=tmp "PX_M_PATH=$PXK_TMP_MOUNT" \
      PX_M_READONLY=false)$PX_NL"
    [ "$kind" != pod ] ||
      volumes="$volumes$(pxk_t volume-claim 2 PX_M_VOLUME=tmp "PX_M_CLAIM=tmp-$role-0")$PX_NL"
  fi
  affinity="$(pxk_affinity "$role" "$kind")" || exit $?
  pxk_t pod-spec "$columns" "PX_M_RESTART=$restart" "PX_M_GRACE=$PX_TIMEOUT_STOP" \
    "PX_M_AFFINITY=$affinity" "PX_M_NODE_SELECTOR=$(pxk_list 2 "$PXK_NODE_SELECTOR" '{}')" \
    "PX_M_TOLERATIONS=$(pxk_list 2 "$PXK_TOLERATIONS")" \
    "PX_M_PULL_SECRETS=$(pxk_list 2 "$PXK_PULL_SECRETS")" \
    "PX_M_UID=$PXK_UID" "PX_M_GID=$PXK_GID" "PX_M_SEARCHES=$(pxk_list 4 "$PXK_SEARCHES")" \
    "PX_M_IMAGE=$PXK_IMAGE" "PX_M_PULL_POLICY=$PXK_PULL_POLICY" "PX_M_ENV=$env" \
    "PX_M_PORTS=$ports" "PX_M_MEM=$mem" "PX_M_CPU=$(pxk_cpu "$mem")" "PX_M_PROBES=$probes" \
    "PX_M_MOUNTS=$(pxk_list 6 "$mounts")" "PX_M_VOLUMES=$(pxk_list 2 "$volumes")"
}

# Pods managed by a Job or standalone Pod carry an explicit index label, whereas StatefulSet pods
# rely on the ordinal label attached by the StatefulSet controller and Deployment replicas are
# indexed by the backend in pod-name order.
## @description  Renders the pod metadata labels for a role (columns, role, kind).
## @return       Writes the rendered label lines to stdout and exits 0.
pxk_pod_labels() {
  local columns=$1 role=$2 kind=$3 group prefix=$PX_LABEL_PREFIX
  group="$(pxg_role_group "$role")"
  set --
  [ "$role" != regionserver ] || set -- "$@" "$prefix.drain=regionmover"
  [ -z "$group" ] || set -- "$@" "$prefix.group=$group"
  case "$kind" in
  statefulset | deployment) ;;
  *) set -- "$@" "$prefix.index=0" ;;
  esac
  [ -z "$group" ] || set -- "$@" "$prefix.start=on-demand"
  pxk_labels "$columns" "$role" "$@"
}

## @description  Renders the Service and StatefulSet manifest documents for one cluster role
##               (role, count).
## @return       Writes to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_statefulset() {
  local role=$1 labels ports spec claims="" claim wanted=tmp
  labels="$(pxk_pod_labels 4 "$role" statefulset)"
  ports="$(pxk_list 4 "$(pxk_ports 4 service-port "$role")")" || exit $?
  pxk_t service 0 "PX_M_ROLE=$role" "PX_M_NAMESPACE=$PXK_NAMESPACE" "PX_M_LABELS=$labels" \
    "PX_M_SERVICE_SPEC=$PXK_HEADLESS" "PX_M_SELECTOR=$(pxk_labels 4 "$role")" "PX_M_PORTS=$ports"
  printf -- '---\n'
  spec="$(pxk_pod_spec "$role" statefulset 6)" || exit $?
  # Every StatefulSet pod provisions a tmp claim, while stateful roles also provision their data
  # claim first to preserve the claim ordering.
  [ -z "$(pxg_volume_of "$role" 0)" ] || wanted="data tmp"
  for claim in $wanted; do
    claims="$claims$(pxk_t claim 4 "PX_M_CLAIM=$claim" "PX_M_LABELS=$(pxk_labels 6 "$role")" \
      "PX_M_STORAGE_CLASS=$PX_K8S_STORAGE_CLASS" "PX_M_VOLUME_GIB=$PX_K8S_VOLUME_GIB")$PX_NL" ||
      exit $?
  done
  pxk_t statefulset 0 "PX_M_ROLE=$role" "PX_M_REPLICAS=$2" "PX_M_NAMESPACE=$PXK_NAMESPACE" \
    "PX_M_LABELS=$labels" "PX_M_SELECTOR=$(pxk_labels 6 "$role")" \
    "PX_M_POD_LABELS=$(pxk_pod_labels 8 "$role" statefulset)" "PX_M_POD_SPEC=$spec" \
    "PX_M_CLAIMS=$(pxk_list 4 "$claims")"
}

# Tier pods share the standard pod template used across all workloads: because the pherf image
# consumes only PHERF_* variables, the imported px-env harness ConfigMap has no effect. The
# controller pod mounts the persistent volume claim for its run directory, whereas each agent
# derives its operational identity from its pod name and reads its ZooKeeper quorum from the
# pherf-target ConfigMap. The template probe slot carries both the role verb and the probe block.
## @description  Renders the pod specification for a Distributed Pherf tier role (role).
## @return       Writes the specification to stdout and exits 0, or exits 2 on an unbound variable.
pxk_tier_spec() {
  local role=$1 verb=agent var mem env="" line item ports="" mounts="" volumes="" probes
  var="PX_$(pxg_role "$role" key)_MEM_MIB"
  mem=${!var}
  if [ "$role" = pherf-controller ]; then
    verb=controller
    ports="$(pxk_ports 6 container-port "$role")" || exit $?
    item="$(pxk_t container-port 6 PX_M_PORT_NAME=http-agent \
      "PX_M_PORT=$PXG_PHERF_AGENT_PORT")" || exit $?
    ports="$ports$PX_NL$item"
    mounts="$(pxk_t mount 6 PX_M_VOLUME=run "PX_M_PATH=$PXK_PHERF_RUN_MOUNT" \
      PX_M_READONLY=false)" || exit $?
    volumes="$(pxk_t volume-claim 2 PX_M_VOLUME=run PX_M_CLAIM=data-pherf-controller-0)" ||
      exit $?
  else
    env="$(pxk_t env-field 6 PX_M_ENV_NAME=PHERF_AGENT_ID PX_M_FIELD=metadata.name)" || exit $?
    env="$env$PX_NL"
  fi
  while IFS= read -r line; do
    case "$line" in
    PHERF_ZOOKEEPER=*)
      item="$(pxk_t env-config 6 PX_M_ENV_NAME=PHERF_ZOOKEEPER PX_M_NAME=pherf-target \
        PX_M_KEY=PHERF_ZOOKEEPER)" || exit $?
      ;;
    *)
      item="$(pxk_t env-value 6 "PX_M_ENV_NAME=${line%%=*}" "PX_M_ENV_VALUE=${line#*=}")" ||
        exit $?
      ;;
    esac
    env="$env$item$PX_NL"
  done <<EOF
$(pxg_tier_env "$role" pherf-controller)
EOF
  env=${env%"$PX_NL"}
  probes="$(pxk_t tier-container 4 "PX_M_VERB=$verb")" || exit $?
  pxk_t pod-spec 6 PX_M_RESTART=Always "PX_M_GRACE=$PX_TIMEOUT_STOP" \
    "PX_M_AFFINITY=$(pxk_affinity "$role" deployment)" \
    "PX_M_NODE_SELECTOR=$(pxk_list 2 "$PXK_NODE_SELECTOR" '{}')" \
    "PX_M_TOLERATIONS=$(pxk_list 2 "$PXK_TOLERATIONS")" \
    "PX_M_PULL_SECRETS=$(pxk_list 2 "$PXK_PULL_SECRETS")" \
    "PX_M_UID=$PXK_UID" "PX_M_GID=$PXK_GID" "PX_M_SEARCHES=$(pxk_list 4 "$PXK_SEARCHES")" \
    "PX_M_IMAGE=$PXK_PHERF_IMAGE" "PX_M_PULL_POLICY=$PXK_PULL_POLICY" "PX_M_ENV=$env" \
    "PX_M_PORTS=$(pxk_list 6 "$ports")" "PX_M_MEM=$mem" "PX_M_CPU=$(pxk_cpu "$mem")" \
    "PX_M_PROBES=$probes" "PX_M_MOUNTS=$(pxk_list 6 "$mounts")" \
    "PX_M_VOLUMES=$(pxk_list 2 "$volumes")"
}

# The controller manifest bundles its persistent volume claim, its ClusterIP Service (exposing both
# the REST port and the agent channel), and its Deployment in the order in which they are applied.
## @description  Renders the Kubernetes manifest documents for a Distributed Pherf tier role (role,
##               count).
## @return       Writes the documents to stdout and exits 0, or exits 2 on an unbound placeholder.
pxk_tier() {
  local role=$1 spec ports item
  if [ "$role" = pherf-controller ]; then
    pxk_t persistent-volume-claim 0 PX_M_NAME=data-pherf-controller-0 \
      "PX_M_NAMESPACE=$PXK_NAMESPACE" \
      "PX_M_LABELS=$(pxk_labels 4 "$role" "$PX_LABEL_PREFIX.group=pherf")" \
      "PX_M_STORAGE_CLASS=$PX_K8S_STORAGE_CLASS" "PX_M_VOLUME_GIB=$PX_K8S_VOLUME_GIB"
    printf -- '---\n'
    ports="$(pxk_ports 4 service-port "$role")" || exit $?
    item="$(pxk_t service-port 4 PX_M_PORT_NAME=http-agent PX_M_PROTOCOL=http \
      "PX_M_PORT=$PXG_PHERF_AGENT_PORT")" || exit $?
    pxk_t service 0 "PX_M_ROLE=$role" "PX_M_NAMESPACE=$PXK_NAMESPACE" \
      "PX_M_LABELS=$(pxk_labels 4 "$role")" "PX_M_SERVICE_SPEC=  type: ClusterIP" \
      "PX_M_SELECTOR=$(pxk_labels 4 "$role")" "PX_M_PORTS=$ports$PX_NL$item"
    printf -- '---\n'
  fi
  spec="$(pxk_tier_spec "$role")" || exit $?
  pxk_t deployment 0 "PX_M_NAME=$role" "PX_M_NAMESPACE=$PXK_NAMESPACE" "PX_M_REPLICAS=$2" \
    "PX_M_LABELS=$(pxk_pod_labels 4 "$role" deployment)" \
    "PX_M_SELECTOR=$(pxk_labels 6 "$role")" \
    "PX_M_POD_LABELS=$(pxk_pod_labels 8 "$role" deployment)" "PX_M_POD_SPEC=$spec"
}

## @description  Renders the setup Job document as a single non-restarting pod co-located on the
##               node that holds the NameNode persistent volume claim.
## @return       Writes to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_job() {
  local spec
  spec="$(pxk_pod_spec setup job 6)" || exit $?
  pxk_t job 0 PX_M_NAME=setup-0 "PX_M_NAMESPACE=$PXK_NAMESPACE" PX_M_HOSTNAME=setup-0 \
    "PX_M_LABELS=$(pxk_pod_labels 4 setup job)" \
    "PX_M_POD_LABELS=$(pxk_pod_labels 8 setup job)" "PX_M_POD_SPEC=$spec"
}

# Emits the persistent volume claim document ahead of the Pod specification within the manifest file
# so that a single manifest apply provisions the tmp claim before creating the Pod that mounts it.
## @description  Renders the tmp persistent volume claim and standalone Pod documents for a
##               non-daemon role (role), such as client or tooling.
## @return       Writes to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_pod() {
  local spec
  spec="$(pxk_pod_spec "$1" pod 2)" || exit $?
  pxk_t persistent-volume-claim 0 "PX_M_NAME=tmp-$1-0" "PX_M_NAMESPACE=$PXK_NAMESPACE" \
    "PX_M_LABELS=$(pxk_labels 4 "$1")" "PX_M_STORAGE_CLASS=$PX_K8S_STORAGE_CLASS" \
    "PX_M_VOLUME_GIB=$PX_K8S_VOLUME_GIB"
  printf -- '---\n'
  pxk_t pod 0 "PX_M_NAME=$1-0" "PX_M_NAMESPACE=$PXK_NAMESPACE" "PX_M_HOSTNAME=$1-0" \
    "PX_M_POD_LABELS=$(pxk_pod_labels 4 "$1" pod)" "PX_M_POD_SPEC=$spec"
}

## @description  Populates the service table, memory totals, port map, and workload manifest file
##               lists in the order defined by the role table.
## @return       Returns exit code 0.
pxk_build() {
  local role count index group position=0 file
  PXG_TSV="" PXK_EAGER_FILES="" PXK_GROUP_FILES="" PXG_YARN_ROLES="" PXG_PHERF_ROLES=""
  PXG_SERVICES=0 PXG_MEM_TOTAL=0 PXG_MEM_EAGER=0 PXG_MEM_YARN=0 PXG_MEM_PHERF=0
  pxk_collect
  for role in $PXG_RENDERED; do
    count="$(pxg_role_count "$role")"
    group="$(pxg_role_group "$role")"
    index=0
    while [ "$index" -lt "$count" ]; do
      if [ "$group" = pherf ]; then
        pxg_tier_row "$role" "$index"
      else
        pxg_service_row "$role" "$index"
      fi
      index=$((index + 1))
    done
    case "$group" in
    yarn) PXG_YARN_ROLES="${PXG_YARN_ROLES:+$PXG_YARN_ROLES }$role" ;;
    pherf) PXG_PHERF_ROLES="${PXG_PHERF_ROLES:+$PXG_PHERF_ROLES }$role" ;;
    esac
    case "$role" in
    setup) file="30-setup.yaml" ;;
    client) file="40-client.yaml" ;;
    tooling) file="41-tooling.yaml" ;;
    pherf-controller) file="51-pherf-controller.yaml" ;;
    pherf-agent) file="52-pherf-agent.yaml" ;;
    *)
      position="$(printf '%s\n' "$PXG_ROLES" | awk -v r="$role" '$1 == r { print NR - 1 }')"
      file="2$position-$role.yaml"
      ;;
    esac
    if [ -n "$group" ]; then
      PXK_GROUP_FILES="$PXK_GROUP_FILES$group/$file $role $count$PX_NL"
    else
      PXK_EAGER_FILES="$PXK_EAGER_FILES$file $role $count$PX_NL"
    fi
  done
}

## @description  Dispatches rendering of a single workload manifest document (role, count) for its
##               target output file.
## @return       Writes to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_workload() {
  case "$1" in
  setup) pxk_job ;;
  client | tooling) pxk_pod "$1" ;;
  pherf-controller | pherf-agent) pxk_tier "$1" "$2" ;;
  *) pxk_statefulset "$1" "$2" ;;
  esac
}

## @description  Renders the header skeleton of a ConfigMap document (name) to which the caller
##               subsequently appends data entries.
## @return       Writes the text to stdout and exits 0, or exits 2 on a placeholder with no value.
pxk_configmap() {
  pxk_t configmap 0 "PX_M_NAME=$1" "PX_M_NAMESPACE=$PXK_NAMESPACE" \
    "PX_M_LABELS=$(pxk_labels 4 "")"
}

## @description  Extracts non-empty KEY=VALUE entries from the topology record and port map (files)
##               as data items for the px-env ConfigMap, omitting any key with an empty value.
## @return       Writes the items to stdout and exits 0, or exits 1 when a file cannot be read.
pxk_env_data() {
  local line text pairs=()
  text="$(cat "$@")" || px_die "$PX_EXIT_FAIL" "cannot read $*"
  while IFS= read -r line; do
    case "$line" in
    '' | '#'*) continue ;;
    esac
    [ -n "${line#*=}" ] || continue
    pairs+=("$line")
  done <<EOF
$text
EOF
  [ "${#pairs[@]}" = 0 ] || pxk_pairs 2 "${pairs[@]}"
}

# This step runs after placeholder validation so that raw template files are embedded verbatim as
# YAML literal blocks without altering their byte content.
## @description  Appends the specified files (directory, file...) to a ConfigMap document (target)
##               as literal-block data items.
## @return       Returns exit code 0, or exits 1 when the target file cannot be written.
pxk_copy_data() {
  local target=$1 dir=$2 file chomp
  shift 2
  for file in "$@"; do
    chomp=+
    # Use keep chomping (+) to preserve all trailing newlines, or switch to strip chomping (-) when
    # the source file does not end with a newline so that YAML parsing adds none.
    if [ -s "$dir/$file" ] && [ -n "$(tail -c 1 "$dir/$file")" ]; then
      chomp=-
    fi
    { pxk_t data-block 2 "PX_M_KEY=$file" "PX_M_CHOMP=$chomp" &&
      sed 's/^/    /' "$dir/$file"; } >>"$target" ||
      px_die "$PX_EXIT_FAIL" "cannot write $target"
    [ "$chomp" = + ] || printf '\n' >>"$target" ||
      px_die "$PX_EXIT_FAIL" "cannot write $target"
  done
}

## @description  Appends every template in a directory (target, directory) to a ConfigMap document
##               in the sorted order of their file names.
## @return       Returns exit code 0, or exits 1 when the target file cannot be written.
pxk_copy_dir() {
  local file names=()
  for file in "$2"/*.tmpl; do
    [ -f "$file" ] || continue
    names+=("${file##*/}")
  done
  [ "${#names[@]}" -gt 0 ] || return 0
  pxk_copy_data "$1" "$2" "${names[@]}"
}

# Emits one tab-separated row per workload containing its kind, name, replica count, per-pod CPU in
# millicores, per-pod memory in MiB, per-pod persistent volume claim count, and claim size in GiB.
# Because every persistent volume claim mounted by a pod shares the same storage allocation size,
# provisioning the tmp claim simply increments the per-pod claim count by one.
## @description  Renders the resource request summary rows for every generated workload document.
## @return       Writes the rows to stdout and exits 0.
pxk_requests() {
  local file role count key var mem kind name claims gib
  while read -r file role count; do
    [ -n "$file" ] || continue
    key="$(pxg_role "$role" key)"
    var="PX_${key}_MEM_MIB"
    mem=${!var}
    claims=0 gib=0
    case "$role" in
    setup) kind=Job name=setup-0 count=1 ;;
    client | tooling) kind=Pod name="$role-0" count=1 claims=1 gib=$PX_K8S_VOLUME_GIB ;;
    pherf-controller) kind=Deployment name=$role claims=1 gib=$PX_K8S_VOLUME_GIB ;;
    pherf-agent) kind=Deployment name=$role ;;
    *)
      kind=StatefulSet name=$role claims=1 gib=$PX_K8S_VOLUME_GIB
      [ -z "$(pxg_volume_of "$role" 0)" ] || claims=2
      ;;
    esac
    px_tsv_row "$kind" "$name" "$count" "$(pxk_cpu "$mem")" "$mem" "$claims" "$gib"
  done <<EOF
$PXK_EAGER_FILES$PXK_GROUP_FILES
EOF
}

## @description  Writes the topology record, the port map, the service table, and the resource
##               request table into the staging directory.
## @return       Exits 0, or exits 1 when a file cannot be written.
pxk_write_records() {
  local staging=$1
  pxg_write_file "$staging/topology.env" "$(pxg_topology_text)$PX_NL"
  {
    printf '%s\n' "$PXG_MARKER"
    printf '%s\n' "# The port map of a Kubernetes deployment. Every pod has an address of its"
    printf '%s\n' "# own, so every instance of a role binds the base port of each family, and"
    printf '%s\n' "# no name carries an index. The ConfigMap px-env gives the map to each pod."
    printf '%s' "$PXK_PORTS"
  } >"$staging/ports.generated.env" || px_die "$PX_EXIT_FAIL" "cannot write the port map"
  pxg_write_file "$staging/services.tsv" \
    "# service role index group start mem_limit_mib heap_mib direct_mib jdk$PX_NL$PXG_TSV"
  # Because scripts/preflight-k8s.sh aggregates cluster resource requests directly from this table,
  # it does not need to parse any rendered YAML manifest.
  pxg_write_file "$staging/requests.tsv" \
    "# kind name replicas cpu_milli mem_mib claims claim_gib$PX_NL$(pxk_requests)$PX_NL"
}

# Matches documents by kind and inserts the block immediately after the matching target line key.
## @description  Inserts a text block into a manifest file (path, kind, text, key) after each line
##               matching the specified document kind and line key.
## @return       Exits 0, or exits 1 when the file cannot be written.
pxk_insert() {
  if ! PXK_BLOCK="$3" awk -v kind="$2" -v key="$4" '
    /^---$/ { k = "" } /^kind: / { k = $2 }
    { print } k == kind && $0 == key { print ENVIRON["PXK_BLOCK"] }' "$1" >"$1.new" ||
    ! mv -f "$1.new" "$1"; then
    px_die "$PX_EXIT_FAIL" "cannot write $1"
  fi
}

# Adds the service-mesh injection label to the namespace and annotates each core workload pod to opt
# out of the mesh unless PX_K8S_MESH_CORE is set to 1. Because the Distributed Pherf tier pods
# constitute the primary mesh group, they are enrolled in the mesh automatically without extra
# opt-in configuration.
## @description  Applies the mesh overlay to the staged manifest documents (staging directory).
## @return       Exits 0, exits 1 if a file cannot be written, or exits 2 on a placeholder with no
##               value.
pxk_mesh_overlay() {
  local staging=$1 file inject=false label pod set
  [ "$PX_K8S_MESH_CORE" = 0 ] || inject=true
  label="$(pxk_tf "$PXK_OVERLAYS/mesh/namespace.yaml.tmpl" 4)" || exit $?
  pxk_insert "$staging/manifests/00-namespace.yaml" Namespace "$label" '  labels:'
  set="$(pxk_tf "$PXK_OVERLAYS/mesh/pod.yaml.tmpl" 6 "PX_M_INJECT=$inject")" || exit $?
  pod="$(pxk_tf "$PXK_OVERLAYS/mesh/pod.yaml.tmpl" 2 "PX_M_INJECT=$inject")" || exit $?
  for file in "$staging"/manifests/2?-*.yaml "$staging"/groups/*/2?-*.yaml \
    "$staging"/manifests/4?-*.yaml; do
    [ -f "$file" ] || continue
    pxk_insert "$file" StatefulSet "$set" '    metadata:'
    pxk_insert "$file" Pod "$pod" 'metadata:'
  done
  set="$(pxk_tf "$PXK_OVERLAYS/mesh/pod.yaml.tmpl" 6 PX_M_INJECT=true)" || exit $?
  for file in "$staging"/groups/*/5?-*.yaml; do
    [ -f "$file" ] || continue
    pxk_insert "$file" Deployment "$set" '    metadata:'
  done
}

## @description  Writes a rendered document (path below the staging directory, text) after the
##               marker line and records the path among the files checked by the validator.
## @return       Returns 0, or exits 1 when the file or its directory cannot be written.
pxk_emit() {
  mkdir -p "$PXG_STAGING/${1%/*}" || px_die "$PX_EXIT_FAIL" "cannot create $PXG_STAGING/${1%/*}"
  pxg_write_file "$PXG_STAGING/$1" "$PXG_MARKER$PX_NL$2$PX_NL"
  PXK_CHECKED+=("$1")
}

## @description  Renders and writes the deployment directory (target) through the staging directory.
## @return       Exits 0, exits 1 if a file cannot be written or a placeholder survived, or exits 2
##               on a placeholder with no value.
pxk_write() {
  local target=$1 staging=$PXG_STAGING file role count text dir list
  PXK_CHECKED=()
  pxk_write_records "$staging"
  text="$(pxk_t namespace 0 "PX_M_NAMESPACE=$PXK_NAMESPACE" \
    "PX_M_LABELS=$(pxk_labels 4 "")")" || exit $?
  pxk_emit manifests/00-namespace.yaml "$text"
  text="$(pxk_configmap px-env)" || exit $?
  text="$text$PX_NL$(pxk_env_data "$staging/topology.env" "$staging/ports.generated.env")" ||
    exit $?
  pxk_emit manifests/10-env.yaml "$text"
  text="$(pxk_configmap px-conf)" || exit $?
  pxk_emit manifests/11-conf.yaml "$text"
  if [ -n "$PXG_OVERLAY_HOST" ]; then
    text="$(pxk_configmap px-conf-overlay)" || exit $?
    pxk_emit manifests/12-conf-overlay.yaml "$text"
  fi
  # Store the agents' ZooKeeper quorum exclusively in this ConfigMap so that it is applied first
  # when starting the tier role group.
  if [ "$PX_PHERF" = 1 ]; then
    text="$(pxk_configmap pherf-target)" || exit $?
    text="$text$PX_NL$(pxk_pairs 2 "PHERF_ZOOKEEPER=$PXG_QUORUM")" || exit $?
    pxk_emit groups/pherf/50-pherf-target.yaml "$text"
  fi
  for dir in manifests groups; do
    list=$PXK_EAGER_FILES
    [ "$dir" = manifests ] || list=$PXK_GROUP_FILES
    while read -r file role count; do
      [ -n "$file" ] || continue
      text="$(pxk_workload "$role" "$count")" || exit $?
      pxk_emit "$dir/$file" "$text"
    done <<EOF
$list
EOF
  done
  [ "$PX_K8S_MESH" = 0 ] || pxk_mesh_overlay "$staging"
  pxg_validate "$staging" topology.env ports.generated.env services.tsv requests.tsv \
    "${PXK_CHECKED[@]}"
  # The runtime configuration templates intentionally retain ${PX_*} placeholders for in-pod
  # expansion. Because each pod's render.sh performs fail-closed validation on those templates at
  # container startup, they are copied verbatim byte for byte after host-side validation completes.
  pxk_copy_dir "$staging/manifests/11-conf.yaml" "$PXK_CONF_DIR"
  if [ -n "$PXG_OVERLAY_HOST" ]; then
    pxk_copy_dir "$staging/manifests/12-conf-overlay.yaml" "$staging/conf-overlay"
  fi
  rm -rf "$target"
  mv "$staging" "$target" || px_die "$PX_EXIT_FAIL" "cannot move the render into $target"
}

# Writes the rendered target directory path to stdout, or writes the service table when invoked
# with --check.
## @description  Parses command-line options, loads the configuration, and renders the deployment
##               directory.
## @return       Exits 0 on success, or exits 1, 2, or 6 as documented in the usage text.
pxk_main() {
  local target
  # Because the Kubernetes backend is intrinsic to this generator, it is pinned at CLI precedence so
  # that the configuration loader always reads k8s/env/k8s.env even when invoked directly.
  px_config_set PX_BACKEND k8s cli
  pxg_cli "$@"
  pxg_load
  pxg_configure
  pxk_configure
  pxg_prepare pxk_build deploy-k8s instances
  target=$PXG_TARGET
  pxg_stage "$target"
  pxg_plugins "$PXG_STAGING" "$target"
  pxk_build
  pxk_write "$target"
  px_info "rendered $PXG_SERVICES instances for cluster $PX_CLUSTER_ID in namespace" \
    "$PXK_NAMESPACE, class $PX_RUN_CLASS: $PXG_MEM_TOTAL MiB of container limits," \
    "$((PXG_MEM_YARN + PXG_MEM_PHERF)) MiB of them on demand, into $target"
  printf '%s\n' "$target"
}

pxk_main "$@"
