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

# Shared deployment generator toolkit sourced by both backend generators: docker/compose.gen.sh for
# the Compose backend and k8s/manifests.gen.sh for the Kubernetes backend. Centralizing these
# facilities ensures that one role table, one suite of value checks, one set of plug-in render
# hooks, one topology record format, and one fail-closed validator govern both rendering pipelines,
# so that adding or modifying a role or validation rule requires changes in only one place.
#
# This library encapsulates the backend-neutral components of a deployment render:
#   - command-line parsing, configuration loading, and target directory resolution,
#   - the master role table, the set of scalable roles, the on-demand role groups, and the service
#     table rows together with their aggregated memory totals,
#   - validation checks for every configuration parameter, which abort the render with exit code 2
#     and identify the offending variable by name,
#   - execution of the render hooks for all enabled plug-ins,
#   - generation of the deployment topology record, topology.env,
#   - management of the temporary staging directory, which preserves any prior render at the target
#     path until the new render finishes,
#   - fail-closed validation: pxg_require rejects any unset variable name, pxg_expand rejects any
#     template placeholder lacking a value, and pxg_validate rejects any rendered file in which an
#     unexpanded placeholder survived. Because the Compose generator emits its output directly
#     without templates, only the Kubernetes manifest generator invokes pxg_expand.
# The backend-specific emitters remain in each individual generator script. Before starting a
# render, the calling generator sets PXG_MARKER, which is written as the first line of its topology
# record.
#
# Sourcing this library writes no files by itself and invokes no external runtime.

# This file is a shell library and must be sourced rather than executed directly; direct execution
# is rejected as an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/deploy.sh is a library. Source it from a generator.\n' >&2
  exit 2
fi

if [ -n "${PXG_KIT_LOADED:-}" ]; then
  return 0
fi
PXG_KIT_LOADED=1

PXG_KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/common.sh
. "$PXG_KIT_DIR/common.sh"
# The backend contract defines the validation grammars for cluster names and local image tags, and
# sourcing it does not bind the process to a specific backend implementation.
# shellcheck source=SCRIPTDIR/backend/contract.sh
. "$PXG_KIT_DIR/backend/contract.sh"
# shellcheck source=SCRIPTDIR/plugin.sh
. "$PXG_KIT_DIR/plugin.sh"

# Master role table listed in the exact order in which roles appear in the rendered output files.
# Each row defines a role name, the uppercase key used in its sizing variables (such as RS in
# PX_RS_HEAP_MIB), and its space-separated port families. Because row ordering is preserved in the
# generated artifacts, reordering this table changes every rendered file. Each port family is
# specified as name:base:step, where instance i receives port base + step * i. The base ports keep
# the upstream default values at instance 0 except for two families shifted so that a step of 10
# cannot collide with a neighboring port: the RegionServer web UI moves to 16025 because 16030 is
# the RPC port of instance 1, and the NodeManager family starts at 8140 because 8040 + 40 would
# collide with 8080. The debug ports for singleton roles occupy 15001-15009.
PXG_TABLE="zookeeper ZK client:2181:0 debug:15006:0
namenode NN rpc:8020:0 http:9870:0 debug:15002:0
secondarynamenode SNN http:9868:0 debug:15003:0
datanode DN data:9866:10 ipc:9867:10 http:9864:10 debug:15021:1
resourcemanager RM scheduler:8030:0 tracker:8031:0 rpc:8032:0 admin:8033:0 http:8088:0 \
debug:15004:0
nodemanager NM localizer:8140:10 rpc:8141:10 http:8142:10 shuffle:13562:10 debug:15031:1
jobhistory JHS rpc:10020:0 http:19888:0 admin:10033:0 debug:15005:0
hbase-master MASTER rpc:16000:0 http:16010:0 debug:15001:0
regionserver RS rpc:16020:10 http:16025:10 debug:15011:1
client CLIENT debug:15007:0
tooling TOOLING debug:15008:0
setup SETUP debug:15009:0
pherf-controller PHERF_CONTROLLER rest:8780:0
pherf-agent PHERF_AGENT"
PXG_ROLES="$(printf '%s\n' "$PXG_TABLE" | cut -d' ' -f1)"

# Roles that support horizontal scaling across multiple instances. Every scalable role is capped at
# nine instances because the debug ports for each scaled role occupy a single decade above its base
# port (15011-15019, 15021-15029, and 15031-15039); a tenth instance would claim the decade's guard
# port, and an eleventh would collide with the base port of the adjacent role. A Distributed Pherf
# tier agent exposes no port of its own, but it enforces the same nine-instance ceiling.
PXG_SCALED="datanode nodemanager regionserver pherf-agent"

# Every daemon binds to all network interfaces within its own container instance, and the topology
# record exports this wildcard address to every service as PX_BIND_HOST.
PXG_BIND_HOST=0.0.0.0

# Regular-expression grammars governing all configuration values emitted by the generator. Because
# rendered values are written inside double quotes in YAML manifests and unquoted in environment
# files, and because Compose interpolates dollar signs in both contexts, no grammar permits a
# dollar sign, a quote, a backslash, or a hash character. Every value written during a render must
# either satisfy one of these grammars or be a numeric value computed by the generator itself.
PXG_MIB_RE='^[1-9][0-9]*$'
PXG_FRACTION_RE='^0\.[0-9][0-9]?$'
PXG_VERSION_RE='^[A-Za-z0-9][A-Za-z0-9._-]*$'
PXG_LABEL_RE='^[a-z0-9][a-z0-9.-]*$'
PXG_PATH_RE='^/[A-Za-z0-9 ._/+@-]*$'
# Regular-expression grammar that validates the ZooKeeper quorum string when the tier targets an
# external cluster.
PXG_QUORUM_RE='^[A-Za-z0-9._:,/-]+$'

# Architectural constants for the Distributed Pherf tier defined by the docker/pherf image and
# shared across both backend generators. Because agents connect to the controller's agent channel
# port exclusively over the internal deployment network, the external port map omits that port just
# as it omits internal MapReduce task ports. Each JVM sizes its heap as a fixed percentage of its
# container memory limit: the controller allocates 50 percent (half of its container limit), whereas
# each agent allocates 35 percent because the resident agent daemon and a concurrent Pherf child
# process share the same container memory budget.
PXG_PHERF_AGENT_PORT=8790
PXG_PHERF_CONTROLLER_PCT=50
PXG_PHERF_AGENT_PCT=35
# Upper bound on the number of Distributed Pherf agents allowed under the validation run class.
PXG_PHERF_VALIDATION_AGENTS=2

# Memory totals recorded in topology.env are summed from the memory limits of rendered services:
# PX_TOPOLOGY_MEM_MIB aggregates every rendered service, PX_TOPOLOGY_MEM_EAGER_MIB aggregates the
# services started eagerly by sb_provision, and PX_GROUP_<GROUP>_MEM_MIB aggregates each on-demand
# role group (YARN and the Distributed Pherf tier). The file docker/env/class-measurement.env
# reconciles these sums with the target design budgets. The one-shot setup service is counted in
# full because it may still be resident while the remaining roles start.

# Per-render state accumulators populated and read by the generator toolkit during a render pass.
PXG_RENDERED=""
PXG_YARN_ROLES=""
PXG_PHERF_ROLES=""
PXG_HOOK_OPTS=""
PXG_OVERLAY_HOST=""
PXG_STAGING=""
PXG_SERVICES=0
PXG_MEM_TOTAL=0
PXG_MEM_EAGER=0
PXG_MEM_YARN=0
PXG_MEM_PHERF=0
PXG_REPLICATION=0
PXG_ZOOKEEPER=""
PXG_HBASE_HADOOP=""
PXG_TSV=""
PXG_QUORUM=""

# Command-line option state populated by pxg_cli, together with the resolved target deployment
# directory populated by pxg_prepare.
PXG_CHECK=0
PXG_NO_RECORD=0
PXG_OUT=""
PXG_SCALES=()
PXG_TARGET=""

## @description  Extracts a requested column from a role's table row: either key (the sizing
##               variable key) or ports (the space-separated port family definitions).
## @return       Writes the selected column to stdout. Exits 0 on match, or 1 for an unknown role.
pxg_role() {
  local role key ports
  while read -r role key ports; do
    [ "$role" = "${1:-}" ] || continue
    case "${2:-}" in
    key) printf '%s\n' "$key" ;;
    *) printf '%s\n' "$ports" ;;
    esac
    return 0
  done <<EOF
$PXG_TABLE
EOF
  return 1
}

## @description  Lists the active port families (name:base:step) for a role, including the debug
##               family only when PX_JVM_DEBUG=1.
## @return       Writes one port family per line to stdout and exits 0.
pxg_role_families() {
  local family
  for family in $(pxg_role "${1:-}" ports); do
    if [ "${family%%:*}" = debug ] && [ "$PX_JVM_DEBUG" != 1 ]; then
      continue
    fi
    printf '%s\n' "$family"
  done
}

## @description  Determines the configured instance count for a role, returning PX_<KEY>_COUNT for
##               a scalable role and 1 for a singleton role.
## @return       Writes the instance count to stdout and exits 0.
pxg_role_count() {
  local var
  if pxg_is_scaled "${1:-}"; then
    var="PX_$(pxg_role "$1" key)_COUNT"
    printf '%s\n' "${!var}"
    return 0
  fi
  printf '1\n'
}

# Both deployment generators emit an identical row for the same role instance.
## @description  Appends a service table row for one instance (role, index) to PXG_TSV and adds its
##               container memory limit to the deployment memory accumulators.
## @return       Exits 0.
pxg_service_row() {
  local role=$1 index=$2 key var mem heap direct jdk group start=""
  key="$(pxg_role "$role" key)"
  var="PX_${key}_MEM_MIB" && mem=${!var}
  var="PX_${key}_HEAP_MIB" && heap=${!var}
  var="PX_${key}_DIRECT_MIB" && direct=${!var}
  var="PX_${key}_JDK" && jdk=${!var}
  group="$(pxg_role_group "$role")"
  [ -z "$group" ] || start=on-demand
  PXG_TSV="$PXG_TSV$(px_tsv_row "$role-$index" "$role" "$index" "$group" "$start" \
    "$mem" "$heap" "$direct" "$jdk")$PX_NL"
  PXG_SERVICES=$((PXG_SERVICES + 1))
  PXG_MEM_TOTAL=$((PXG_MEM_TOTAL + mem))
  if [ -n "$group" ]; then
    PXG_MEM_YARN=$((PXG_MEM_YARN + mem))
  else
    PXG_MEM_EAGER=$((PXG_MEM_EAGER + mem))
  fi
}

# Because the Distributed Pherf tier image configures JVM memory as a percentage of the container
# limit, the service table row records that percentage share as the heap size and reports zero
# direct memory.
## @description  Appends a service table row for a Distributed Pherf tier instance (role, index) to
##               PXG_TSV and adds its container memory limit to the tier memory accumulators.
## @return       Exits with 0.
pxg_tier_row() {
  local role=$1 index=$2 var mem pct=$PXG_PHERF_AGENT_PCT
  var="PX_$(pxg_role "$role" key)_MEM_MIB" && mem=${!var}
  [ "$role" != pherf-controller ] || pct=$PXG_PHERF_CONTROLLER_PCT
  PXG_TSV="$PXG_TSV$(px_tsv_row "$role-$index" "$role" "$index" pherf on-demand "$mem" \
    "$((mem * pct / 100))" 0 17)$PX_NL"
  PXG_SERVICES=$((PXG_SERVICES + 1))
  PXG_MEM_TOTAL=$((PXG_MEM_TOTAL + mem))
  PXG_MEM_PHERF=$((PXG_MEM_PHERF + mem))
}

# Both backend generators assign each tier instance the same PHERF_* environment variables in an
# identical order, except that the Kubernetes generator supplies the ZooKeeper quorum via the
# pherf-target ConfigMap rather than an inline value.
## @description  Emits the environment variables for a tier role (role, controller host name) as
##               NAME=VALUE lines.
## @return       Writes the environment lines to stdout and exits with 0.
pxg_tier_env() {
  if [ "$1" = pherf-controller ]; then
    printf 'PHERF_JAVA_OPTS=-XX:MaxRAMPercentage=%s\n' "$PXG_PHERF_CONTROLLER_PCT"
    printf 'PHERF_REST_PORT=%s\n' "$(pxg_port pherf-controller rest 0)"
    return 0
  fi
  printf 'PHERF_CONTROLLER=http://%s:%s\n' "$2" "$PXG_PHERF_AGENT_PORT"
  printf 'PHERF_JAVA_OPTS=-XX:MaxRAMPercentage=%s\n' "$PXG_PHERF_AGENT_PCT"
  printf 'PHERF_ZOOKEEPER=%s\n' "$PXG_QUORUM"
}

## @description  Determines whether the given role supports scaling across multiple instances.
## @return       Exits 0 if the role is scalable, or 1 otherwise.
pxg_is_scaled() {
  case " $PXG_SCALED " in
  *" ${1:-} "*) return 0 ;;
  esac
  return 1
}

## @description  Translates a --scale ROLE=COUNT argument into the corresponding count variable
##               assignment for that role.
## @return       Writes PX_<KEY>_COUNT=COUNT to stdout and exits 0; exits 2 for an unscaled role.
pxg_scale_set() {
  local role=${1%%=*}
  if [ "$role" = "$1" ] || ! pxg_is_scaled "$role"; then
    px_die "$PX_EXIT_USAGE" "--scale needs ROLE=COUNT with a scaled role of: $PXG_SCALED," \
      "got '$1'"
  fi
  printf 'PX_%s_COUNT=%s\n' "$(pxg_role "$role" key)" "${1#*=}"
}

# Role groups (the YARN tier and the Distributed Pherf tier) can be started and stopped on demand
# while the rest of the cluster remains running.
## @description  Identifies the on-demand role group to which a role belongs, if any.
## @return       Writes the group name to stdout (or nothing if ungrouped) and exits 0.
pxg_role_group() {
  case "${1:-}" in
  resourcemanager | nodemanager | jobhistory) printf 'yarn\n' ;;
  pherf-controller | pherf-agent) printf 'pherf\n' ;;
  esac
}

## @description  Computes the port number for a specific role instance (role, family, index) from
##               the master role table.
## @return       Writes the port to stdout and exits 0, or exits 1 if the role lacks that family.
pxg_port() {
  local family
  for family in $(pxg_role "$1" ports); do
    [ "${family%%:*}" = "$2" ] || continue
    family=${family#*:}
    printf '%s\n' "$((${family%%:*} + ${family#*:} * $3))"
    return 0
  done
  return 1
}

# The one-shot setup role mounts the NameNode data volume so that it can format the HDFS metadata.
## @description  Returns the persistent volume name mounted at the data directory for a given role
##               instance (role, index).
## @return       Writes the volume name to stdout (or nothing if stateless) and exits 0.
pxg_volume_of() {
  case "${1:-}" in
  zookeeper | namenode | secondarynamenode | datanode) printf '%s\n' "$1-$2-data" ;;
  setup) printf '%s\n' namenode-0-data ;;
  esac
}

## @description  Aborts the render if any required configuration variable is unset or empty,
##               reporting all missing variable names in a single diagnostic message.
## @return       Exits 0 when every variable has a value, or exits 2 if any variable is missing.
pxg_require() {
  local name missing=""
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      missing="$missing $name"
    fi
  done
  if [ -n "$missing" ]; then
    px_die "$PX_EXIT_USAGE" "the deployment cannot render, these names are unset:$missing." \
      "Set them in the run class, in env/local.env or with --set"
  fi
}

## @description  Validates that the named variable holds a positive integer value in MiB, aborting
##               the render otherwise.
## @return       Exits 0 when valid, or exits 2 on an invalid value.
pxg_check_mib() {
  local name=$1
  if ! [[ ${!name:-} =~ $PXG_MIB_RE ]]; then
    px_die "$PX_EXIT_USAGE" "$name='${!name:-}' must be a whole number above zero"
  fi
}

## @description  Validates that the named boolean switch variable is set to either 0 or 1, aborting
##               the render otherwise.
## @return       Exits 0 when valid, or exits 2 on any other value.
pxg_check_switch() {
  local name=$1
  case "${!name:-}" in
  0 | 1) ;;
  *) px_die "$PX_EXIT_USAGE" "$name='${!name:-}' must be 0 or 1" ;;
  esac
}

# If a staging record exists for the image, each key:variable pair passed by the caller must match
# the value recorded when the image was staged.
## @description  Populates PXG_ZOOKEEPER and PXG_HBASE_HADOOP from the staging record of an image
##               (tag variable name, record key:variable...).
## @return       Exits 0 (even when no record exists), or exits 2 on a mismatched version set.
pxg_staged() {
  local var=$1 tag=${!1} root record text key name value
  shift
  PXG_ZOOKEEPER=unstaged
  PXG_HBASE_HADOOP=unstaged
  root="$(px_out_root 2>/dev/null)" || return 0
  record="$root/images/$(px_image_stage_name "$tag").staged.tsv"
  [ -f "$record" ] || return 0
  text="$(cat "$record")"
  for key in "$@"; do
    name=${key#*:}
    value="$(px_tsv_get "${key%%:*}" "$text")" || value=""
    if [ "$value" != "${!name}" ]; then
      px_die "$PX_EXIT_USAGE" "the image $tag was staged with ${key%%:*} '$value'," \
        "and $name is '${!name}': stage the image again, or select another $var"
    fi
  done
  PXG_ZOOKEEPER="$(px_tsv_get zookeeper_version "$text")" || PXG_ZOOKEEPER=""
  PXG_HBASE_HADOOP="$(px_tsv_get hbase_hadoop_version "$text")" || PXG_HBASE_HADOOP=""
  for name in PXG_ZOOKEEPER PXG_HBASE_HADOOP; do
    if ! [[ ${!name} =~ $PXG_VERSION_RE ]]; then
      px_die "$PX_EXIT_USAGE" "the staging record $record holds no release for $name"
    fi
  done
}

## @description  Validates that a scaled role's instance count lies between 1 and 9 inclusive and
##               normalizes the variable in place by stripping any leading zeros.
## @return       Exits 0 when valid, or exits 2 if the count lies outside the 1 to 9 range.
pxg_check_count() {
  local name count
  name="PX_$(pxg_role "$1" key)_COUNT"
  count=${!name:-}
  [[ $count =~ ^0*[1-9]$ ]] ||
    px_die "$PX_EXIT_USAGE" "$name='$count' is outside the range 1 to 9 for $1. Nine" \
      "instances is the ceiling of every scaled role on both backends, because the debug" \
      "ports of a role fill one decade"
  printf -v "$name" '%s' "$((10#$count))"
}

# Every rendered role requires positive integer MiB values for container memory limit, JVM heap,
# and direct memory, a supported JDK version, valid JVM options, and a total of heap plus direct
# memory (plus YARN container memory for a NodeManager) strictly below the container memory limit.
## @description  Validates the memory sizing, JDK selection, and JVM options for a role, aborting
##               the render on any violation.
## @return       Exits 0 when valid, or exits 2 on any invalid sizing or configuration value.
pxg_check_role() {
  local role=$1 key mem heap direct jdk need detail name
  key="$(pxg_role "$role" key)"
  mem="PX_${key}_MEM_MIB" heap="PX_${key}_HEAP_MIB" direct="PX_${key}_DIRECT_MIB"
  jdk="PX_${key}_JDK"
  for name in "$mem" "$heap" "$direct"; do
    pxg_check_mib "$name"
  done
  case " $PX_JDKS " in
  *" ${!jdk} "*) ;;
  *) px_die "$PX_EXIT_USAGE" "$jdk='${!jdk}': role $role runs on JDK 11, 17 or 21" ;;
  esac
  need=$((${!heap} + ${!direct}))
  detail="heap ${!heap} + direct ${!direct}"
  if [ "$role" = nodemanager ]; then
    need=$((need + PX_NM_RESOURCE_MIB))
    detail="$detail + YARN memory $PX_NM_RESOURCE_MIB"
  fi
  [ "$need" -lt "${!mem}" ] || px_die "$PX_EXIT_USAGE" "role $role: $detail = $need MiB" \
    "must stay below the container limit $mem=${!mem} MiB"
  name="PX_${key}_JVM_OPTS"
  pxg_check_jvm_opts "$name" "${!name:-}"
}

## @description  Aborts the render if a JVM option string (name, value) contains characters outside
##               the value grammar or memory size flags reserved for the sizing variables.
## @return       Exits 0 when valid, or exits 2 on a rejected option string.
pxg_check_jvm_opts() {
  local name=$1 value=${2:-} word
  if [[ $value == *[\$\"\'\\#]* ]] || [[ $value == *$'\t'* ]]; then
    px_die "$PX_EXIT_USAGE" "$name holds a dollar sign, a quote, a backslash, a hash or a" \
      "tab, which the value grammar refuses: $value"
  fi
  for word in $value; do
    case "$word" in
    -Xms* | -Xmx* | -Xmn* | -XX:MaxDirectMemorySize* | -XX:MaxRAM* | -XX:MaxHeapSize* | \
      -XX:InitialHeapSize* | -XX:MinHeapSize* | -XX:InitialRAM* | -XX:MinRAM*)
      px_die "$PX_EXIT_USAGE" "$name sets the size option $word. Only the sizing names" \
        "PX_<KEY>_HEAP_MIB, PX_<KEY>_DIRECT_MIB and PX_<KEY>_MEM_MIB set a size"
      ;;
    esac
  done
}

## @description  Assembles the effective JVM options for a role key by combining PX_<KEY>_JVM_OPTS
##               with any options contributed for that key by the plug-in jvm-opts hook.
## @return       Writes the combined options (or an empty line if none) to stdout and exits 0.
pxg_jvm_opts_of() {
  local key=$1 var="PX_${1}_JVM_OPTS" opts row_key row
  opts=${!var:-}
  while IFS="$PX_TAB" read -r row_key row; do
    [ "$row_key" = "$key" ] || continue
    opts="${opts:+$opts }$row"
  done <<EOF
$PXG_HOOK_OPTS
EOF
  printf '%s\n' "$opts"
}

# Runs the image-check hook, collects per-role JVM options from the jvm-opts hook, and copies any
# conf-overlay templates emitted by the conf-overlay hook into conf-overlay/.
## @description  Executes the render hooks of all enabled plug-ins (staging directory, target).
## @return       Exits 0, or 2 on a failed hook, rejected line, or name collision, or 1 on copy
##               failure.
pxg_plugins() {
  local staging=$1 target=$2 rows out line id key opts dir file name keys
  PXG_HOOK_OPTS="" PXG_OVERLAY_HOST=""
  [ -n "${PX_PLUGINS:-}" ] || return 0
  rows="$(pl_enabled || exit "$?")" || px_die "$PX_EXIT_USAGE" "the plug-ins do not load"
  # If any enabled plug-in is incompatible with the configured image, abort the render immediately
  # so that the backend never provisions a deployment that the plug-in rejects.
  pl_hook_run image-check "$staging" "$PX_IMAGE_TAG" >/dev/null ||
    px_die "$PX_EXIT_USAGE" "the image-check hook of a plug-in refused the image" \
      "$PX_IMAGE_TAG, so the render stops"
  out="$(pl_hook_run jvm-opts "$staging" || exit "$?")" ||
    px_die "$PX_EXIT_USAGE" "the jvm-opts hook of a plug-in failed, so the render stops"
  keys=" $(printf '%s\n' "$PXG_TABLE" | awk '{ printf "%s ", $2 }') "
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    id=${line%%"$PX_TAB"*}
    line=${line#*"$PX_TAB"}
    key=${line%% *}
    opts=${line#"$key"}
    px_trim_var opts
    case "$key" in
    PHERF_*)
      px_die "$PX_EXIT_USAGE" "the jvm-opts hook of plug-in $id names $key, a key of the" \
        "Distributed Pherf tier, which takes no JVM option of a plug-in: $line"
      ;;
    esac
    case "$keys" in
    *" $key "*) ;;
    *) px_die "$PX_EXIT_USAGE" "the jvm-opts hook of plug-in $id names no role key: $line" ;;
    esac
    pxg_check_jvm_opts "the jvm-opts hook of plug-in $id for $key" "$opts"
    [ -z "$opts" ] || PXG_HOOK_OPTS="$PXG_HOOK_OPTS$key$PX_TAB$opts$PX_NL"
  done <<EOF
$out
EOF
  pl_hook_run conf-overlay "$staging" >/dev/null ||
    px_die "$PX_EXIT_USAGE" "the conf-overlay hook of a plug-in failed, so the render stops"
  while IFS="$PX_TAB" read -r id dir; do
    [ -n "$id" ] || continue
    for file in "$staging/plugins/$id/conf-overlay"/*.tmpl; do
      [ -f "$file" ] || continue
      name=${file##*/}
      if [ -e "$staging/conf-overlay/$name" ]; then
        px_die "$PX_EXIT_USAGE" "two plug-ins write the overlay template $name; plug-in $id" \
          "in $dir is the second"
      fi
      { mkdir -p "$staging/conf-overlay" && cp "$file" "$staging/conf-overlay/$name"; } ||
        px_die "$PX_EXIT_FAIL" "cannot copy the overlay template $file"
      PXG_OVERLAY_HOST="$target/conf-overlay"
    done
  done <<EOF
$rows
EOF
  if [ -n "$PXG_OVERLAY_HOST" ] && ! [[ $PXG_OVERLAY_HOST =~ $PXG_PATH_RE ]]; then
    px_die "$PX_EXIT_USAGE" "the deployment directory '$target' must hold letters, digits," \
      "spaces and . _ / + @ - only, because every instance mounts its conf-overlay/"
  fi
}

## @description  Converts a named decimal fraction with up to two decimal places into an integer
##               number of hundredths.
## @return       Writes the hundredths value to stdout and exits 0, or exits 2 if not 0.d or 0.dd.
pxg_hundredths() {
  local name=$1 digits
  if ! [[ ${!name:-} =~ $PXG_FRACTION_RE ]]; then
    px_die "$PX_EXIT_USAGE" "$name='${!name:-}' must be a fraction 0.d or 0.dd"
  fi
  digits=${!name#0.}
  if [ "${#digits}" = 1 ]; then
    digits="${digits}0"
  fi
  printf '%s\n' "$((10#$digits))"
}

## @description  Validates all configuration parameters consumed by the render and populates the
##               derived PXG_* state variables used by the emitters.
## @return       Exits 0, or 2 on any invalid value, or 1 if the state directory fails to resolve.
pxg_configure() {
  local name
  case "${PX_RUN_CLASS:-}" in
  validation | measurement) ;;
  '')
    px_die "$PX_EXIT_USAGE" "name the run class: --class validation|measurement," \
      "or PX_RUN_CLASS in the environment or in env/local.env"
    ;;
  *)
    px_die "$PX_EXIT_USAGE" "unknown run class '$PX_RUN_CLASS'." \
      "The run classes are validation and measurement"
    ;;
  esac
  if [ -z "${PX_CLUSTER_ID:-}" ]; then
    px_config_set PX_CLUSTER_ID default derived
  fi
  if ! [[ $PX_CLUSTER_ID =~ $SB_CLUSTER_RE ]]; then
    px_die "$PX_EXIT_USAGE" "invalid cluster name '$PX_CLUSTER_ID': lower case" \
      "letters, digits, underscore and hyphen, starting with a letter or a digit"
  fi
  pxg_require PX_PHERF PX_PHERF_TARGET PX_JVM_DEBUG PX_PHOENIX_HBASE_PROFILE \
    PX_DIST_HBASE_VERSION PX_LABEL_PREFIX
  pxg_check_switch PX_PHERF
  pxg_check_switch PX_JVM_DEBUG
  PXG_RENDERED=""
  case "$PX_PHERF_TARGET" in
  harness) pxg_configure_harness ;;
  external)
    [ "$PX_PHERF" = 1 ] || px_die "$PX_EXIT_USAGE" "PX_PHERF_TARGET=external renders the" \
      "Distributed Pherf tier alone, so it needs PX_PHERF=1"
    ;;
  *) px_die "$PX_EXIT_USAGE" "PX_PHERF_TARGET='$PX_PHERF_TARGET' must be harness or external" ;;
  esac
  for name in PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION; do
    if ! [[ ${!name} =~ $PXG_VERSION_RE ]]; then
      px_die "$PX_EXIT_USAGE" "$name='${!name}' must be a version: letters, digits, . _ -"
    fi
  done
  if ! [[ $PX_LABEL_PREFIX =~ $PXG_LABEL_RE ]]; then
    px_die "$PX_EXIT_USAGE" "PX_LABEL_PREFIX='$PX_LABEL_PREFIX' must hold lower case" \
      "letters, digits, dots and hyphens"
  fi
  if [ "$PX_PHERF" = 1 ]; then
    pxg_configure_tier
  fi
}

## @description  Validates the Distributed Pherf tier configuration, resolves PXG_QUORUM, and
##               appends both tier roles to PXG_RENDERED.
## @return       Exits with 0 when valid, or 2 if any configuration value is rejected.
pxg_configure_tier() {
  local name ver_zk=$PXG_ZOOKEEPER ver_hadoop=$PXG_HBASE_HADOOP
  pxg_require PX_PHERF_AGENT_COUNT PX_PHERF_CONTROLLER_MEM_MIB PX_PHERF_AGENT_MEM_MIB \
    PX_PHERF_IMAGE_TAG
  pxg_check_count pherf-agent
  if [ "$PX_RUN_CLASS" = validation ] &&
    [ "$PX_PHERF_AGENT_COUNT" -gt "$PXG_PHERF_VALIDATION_AGENTS" ]; then
    px_die "$PX_EXIT_USAGE" "PX_PHERF_AGENT_COUNT=$PX_PHERF_AGENT_COUNT: the validation class" \
      "runs 1 to $PXG_PHERF_VALIDATION_AGENTS agents"
  fi
  for name in PX_PHERF_CONTROLLER_MEM_MIB PX_PHERF_AGENT_MEM_MIB; do
    pxg_check_mib "$name"
  done
  if ! sb_image_tag_local "$PX_PHERF_IMAGE_TAG"; then
    px_die "$PX_EXIT_USAGE" "PX_PHERF_IMAGE_TAG='$PX_PHERF_IMAGE_TAG' must be a local image" \
      "reference repository:tag, with no registry host and no digest"
  fi
  # Targeting an external cluster requires an explicit operator-supplied ZooKeeper quorum, whereas
  # targeting the harness cluster derives the quorum directly from the deployment's own ZooKeeper
  # service and client port.
  if [ "$PX_PHERF_TARGET" = external ]; then
    [ -n "${PX_PHERF_ZOOKEEPER:-}" ] || px_die "$PX_EXIT_USAGE" "the external target needs" \
      "PX_PHERF_ZOOKEEPER, the ZooKeeper quorum of the target cluster: host:port,..."
    [[ $PX_PHERF_ZOOKEEPER =~ $PXG_QUORUM_RE ]] || px_die "$PX_EXIT_USAGE" \
      "PX_PHERF_ZOOKEEPER='$PX_PHERF_ZOOKEEPER' must hold letters, digits and . _ : , / - only"
    PXG_QUORUM=$PX_PHERF_ZOOKEEPER
  else
    [ -z "${PX_PHERF_ZOOKEEPER:-}" ] || px_die "$PX_EXIT_USAGE" "PX_PHERF_ZOOKEEPER is for" \
      "PX_PHERF_TARGET=external. The harness target reads the ZooKeeper of the deployment"
    PXG_QUORUM="zookeeper-0:$(pxg_port zookeeper client 0)"
  fi
  # For an external deployment, the ZooKeeper version is drawn from the staging record of the Pherf
  # image, whereas a harness deployment retains the version recorded in the staging record of the
  # harness image.
  pxg_staged PX_PHERF_IMAGE_TAG hbase_profile:PX_PHOENIX_HBASE_PROFILE \
    hbase_version:PX_DIST_HBASE_VERSION
  if [ "$PX_PHERF_TARGET" = harness ]; then
    PXG_ZOOKEEPER=$ver_zk
    PXG_HBASE_HADOOP=$ver_hadoop
  fi
  PXG_RENDERED="$PXG_RENDERED pherf-controller pherf-agent"
}

## @description  Validates configuration parameters for the harness cluster roles and appends each
##               enabled role to PXG_RENDERED.
## @return       Exits 0, or 2 on any invalid value, or 1 if the state directory fails to resolve.
pxg_configure_harness() {
  local role key cache store name
  local required=()
  pxg_require PX_YARN PX_SNN PX_DN_COUNT PX_NM_COUNT PX_RS_COUNT \
    PX_HDFS_REPLICATION PX_HBASE_BLOCKCACHE_SIZE PX_HBASE_MEMSTORE_SIZE \
    PX_IMAGE_TAG PX_DIST_HADOOP_VERSION
  pxg_check_switch PX_YARN
  pxg_check_switch PX_SNN
  for role in $PXG_SCALED; do
    [ "$role" = pherf-agent ] || pxg_check_count "$role"
  done

  # Each role is included in the render unless its feature switch is disabled. Enabling the YARN
  # switch includes the YARN tier in the rendered deployment artifacts but does not start it at
  # provisioning time.
  for role in $PXG_ROLES; do
    case "$role" in
    secondarynamenode) [ "$PX_SNN" = 1 ] || continue ;;
    resourcemanager | nodemanager | jobhistory) [ "$PX_YARN" = 1 ] || continue ;;
    pherf-*) continue ;;
    esac
    PXG_RENDERED="$PXG_RENDERED $role"
    key="$(pxg_role "$role" key)"
    required+=("PX_${key}_MEM_MIB" "PX_${key}_HEAP_MIB" "PX_${key}_DIRECT_MIB")
    required+=("PX_${key}_JDK")
  done
  if [ "$PX_YARN" = 1 ]; then
    required+=(PX_NM_RESOURCE_MIB PX_NM_VCORES PX_YARN_MIN_ALLOC_MIB PX_YARN_MAX_ALLOC_MIB)
    required+=(PX_MR_AM_MIB PX_MR_TASK_MIB)
  fi
  pxg_require ${required[@]+"${required[@]}"}

  if [ "$PX_YARN" = 1 ]; then
    for name in PX_NM_RESOURCE_MIB PX_NM_VCORES PX_YARN_MIN_ALLOC_MIB \
      PX_YARN_MAX_ALLOC_MIB PX_MR_AM_MIB PX_MR_TASK_MIB; do
      pxg_check_mib "$name"
    done
    if [ "$PX_YARN_MIN_ALLOC_MIB" -gt "$PX_YARN_MAX_ALLOC_MIB" ] ||
      [ "$PX_YARN_MAX_ALLOC_MIB" -gt "$PX_NM_RESOURCE_MIB" ]; then
      px_die "$PX_EXIT_USAGE" "YARN allocations must satisfy PX_YARN_MIN_ALLOC_MIB" \
        "<= PX_YARN_MAX_ALLOC_MIB <= PX_NM_RESOURCE_MIB, got" \
        "$PX_YARN_MIN_ALLOC_MIB, $PX_YARN_MAX_ALLOC_MIB, $PX_NM_RESOURCE_MIB"
    fi
    for name in PX_MR_AM_MIB PX_MR_TASK_MIB; do
      if [ "${!name}" -gt "$PX_YARN_MAX_ALLOC_MIB" ]; then
        px_die "$PX_EXIT_USAGE" "$name=${!name} exceeds the largest YARN" \
          "allocation PX_YARN_MAX_ALLOC_MIB=$PX_YARN_MAX_ALLOC_MIB"
      fi
    done
  fi
  for role in $PXG_RENDERED; do
    pxg_check_role "$role"
  done

  # HBase refuses to start if the block cache and global memstore fractions together exceed 0.8 of
  # the JVM heap, so the generator rejects that configuration during rendering.
  cache="$(pxg_hundredths PX_HBASE_BLOCKCACHE_SIZE)" || exit $?
  store="$(pxg_hundredths PX_HBASE_MEMSTORE_SIZE)" || exit $?
  if [ $((cache + store)) -gt 80 ]; then
    px_die "$PX_EXIT_USAGE" "PX_HBASE_BLOCKCACHE_SIZE + PX_HBASE_MEMSTORE_SIZE =" \
      "$PX_HBASE_BLOCKCACHE_SIZE + $PX_HBASE_MEMSTORE_SIZE exceeds 0.8 of the heap"
  fi

  pxg_check_mib PX_HDFS_REPLICATION
  PXG_REPLICATION=$PX_HDFS_REPLICATION
  if [ "$PXG_REPLICATION" -gt "$PX_DN_COUNT" ]; then
    px_warn "PX_HDFS_REPLICATION=$PX_HDFS_REPLICATION exceeds the $PX_DN_COUNT" \
      "DataNode(s): the deployment uses replication $PX_DN_COUNT"
    PXG_REPLICATION=$PX_DN_COUNT
  fi

  if ! sb_image_tag_local "$PX_IMAGE_TAG"; then
    px_die "$PX_EXIT_USAGE" "PX_IMAGE_TAG='$PX_IMAGE_TAG' must be a local image" \
      "reference repository:tag, with no registry host and no digest"
  fi
  if ! [[ $PX_DIST_HADOOP_VERSION =~ $PXG_VERSION_RE ]]; then
    px_die "$PX_EXIT_USAGE" "PX_DIST_HADOOP_VERSION='$PX_DIST_HADOOP_VERSION' must be a" \
      "version: letters, digits, . _ -"
  fi
  pxg_staged PX_IMAGE_TAG hbase_profile:PX_PHOENIX_HBASE_PROFILE \
    hbase_version:PX_DIST_HBASE_VERSION hadoop_version:PX_DIST_HADOOP_VERSION
}

## @description  Emits the topology record entries for the Distributed Pherf tier, or outputs
##               nothing when PX_PHERF is 0.
## @return       Writes the key-value lines to stdout and exits 0.
pxg_tier_lines() {
  [ "$PX_PHERF" = 1 ] || return 0
  cat <<EOF
PX_PHERF=1
PX_PHERF_TARGET=$PX_PHERF_TARGET
PX_PHERF_AGENT_COUNT=$PX_PHERF_AGENT_COUNT
PX_PHERF_IMAGE_TAG=$PX_PHERF_IMAGE_TAG
PX_GROUP_PHERF=$PXG_PHERF_ROLES
PX_GROUP_PHERF_MEM_MIB=$PXG_MEM_PHERF
EOF
}

## @description  Generates the topology record for a standalone Distributed Pherf tier deployment,
##               recording the run metadata, the Pherf image version set, and the tier parameters.
## @return       Writes the complete topology.env text to stdout and exits 0.
pxg_topology_external_text() {
  cat <<EOF
$PXG_MARKER
# The topology record of a deployment that holds the Distributed Pherf tier
# alone, for an external cluster. Memory figures are MiB. The version set is
# that of the pherf image: the Phoenix HBase profile, the HBase release and
# the ZooKeeper client that the staging record of the image names. The
# ZooKeeper quorum of the cluster is not recorded here: only the agents read
# it, from PX_PHERF_ZOOKEEPER of the operator.
PX_CLUSTER_ID=$PX_CLUSTER_ID
PX_RUN_CLASS=$PX_RUN_CLASS
PX_SIZING_OVERRIDES=$(px_sizing_overrides)
PX_PHOENIX_HBASE_PROFILE=$PX_PHOENIX_HBASE_PROFILE
PX_DIST_HBASE_VERSION=$PX_DIST_HBASE_VERSION
PX_ZOOKEEPER_VERSION=$PXG_ZOOKEEPER
PX_HBASE_HADOOP_VERSION=$PXG_HBASE_HADOOP
$(pxg_tier_lines)
PX_GROUPS=pherf
PX_TOPOLOGY_SERVICE_COUNT=$PXG_SERVICES
PX_TOPOLOGY_MEM_MIB=$PXG_MEM_TOTAL
PX_TOPOLOGY_MEM_EAGER_MIB=$PXG_MEM_EAGER
EOF
}

## @description  Generates the topology.env record, which is loaded as an environment file by every
##               service and archived with the run bundle.
## @return       Writes the complete topology.env text to stdout and exits 0.
pxg_topology_text() {
  local groups="" yarn_lines="" tier_lines
  if [ "$PX_PHERF_TARGET" = external ]; then
    pxg_topology_external_text
    return 0
  fi
  if [ "$PX_YARN" = 1 ]; then
    groups=yarn
    yarn_lines="PX_NM_RESOURCE_MIB=$PX_NM_RESOURCE_MIB
PX_NM_VCORES=$PX_NM_VCORES
PX_YARN_MIN_ALLOC_MIB=$PX_YARN_MIN_ALLOC_MIB
PX_YARN_MAX_ALLOC_MIB=$PX_YARN_MAX_ALLOC_MIB
PX_MR_AM_MIB=$PX_MR_AM_MIB
PX_MR_TASK_MIB=$PX_MR_TASK_MIB
PX_GROUP_YARN=$PXG_YARN_ROLES
PX_GROUP_YARN_MEM_MIB=$PXG_MEM_YARN
"
  fi
  # When the Distributed Pherf tier is disabled, omit its lines entirely so the rendered output
  # remains byte-for-byte identical to a render produced before the tier was introduced.
  tier_lines="$(pxg_tier_lines)"
  if [ -n "$tier_lines" ]; then
    tier_lines="$tier_lines$PX_NL"
    groups="${groups:+$groups }pherf"
  fi
  cat <<EOF
$PXG_MARKER
# The topology record of one deployment. The bundle keeps it, and every
# service reads it as an environment file. Memory figures are MiB:
# PX_TOPOLOGY_MEM_MIB sums the limits of every service, and
# PX_TOPOLOGY_MEM_EAGER_MIB those that sb_provision starts. A group is a set of
# roles that start and stop together; PX_GROUP_<GROUP> lists its roles.
# PX_HDFS_REPLICATION is the replication the deployment uses, and
# PX_HDFS_REPLICATION_REQUESTED the value the configuration asked for.
# The version set: Phoenix is built with the HBase profile
# PX_PHOENIX_HBASE_PROFILE, the HBase daemons run PX_DIST_HBASE_VERSION, and
# the HDFS and YARN daemons run PX_DIST_HADOOP_VERSION. The ZooKeeper server
# and the HBase daemons run the jars of HBase lib/: ZooKeeper
# PX_ZOOKEEPER_VERSION and the Hadoop client PX_HBASE_HADOOP_VERSION, which
# the staging record of the image names. Before the image is staged, the two
# read unstaged. Every daemon binds PX_BIND_HOST, every interface of its own
# container. PX_JVM_DEBUG=1 adds the debug agent and publishes its port.
# PX_SIZING_OVERRIDES names each sizing value that differs from the file of
# PX_RUN_CLASS, so a measurement run on this cluster is tagged contaminated.
PX_CLUSTER_ID=$PX_CLUSTER_ID
PX_RUN_CLASS=$PX_RUN_CLASS
PX_SIZING_OVERRIDES=$(px_sizing_overrides)
PX_IMAGE_TAG=$PX_IMAGE_TAG
PX_BIND_HOST=$PXG_BIND_HOST
PX_JVM_DEBUG=$PX_JVM_DEBUG
PX_PHOENIX_HBASE_PROFILE=$PX_PHOENIX_HBASE_PROFILE
PX_DIST_HBASE_VERSION=$PX_DIST_HBASE_VERSION
PX_DIST_HADOOP_VERSION=$PX_DIST_HADOOP_VERSION
PX_ZOOKEEPER_VERSION=$PXG_ZOOKEEPER
PX_HBASE_HADOOP_VERSION=$PXG_HBASE_HADOOP
PX_YARN=$PX_YARN
PX_SNN=$PX_SNN
PX_DN_COUNT=$PX_DN_COUNT
PX_NM_COUNT=$PX_NM_COUNT
PX_RS_COUNT=$PX_RS_COUNT
PX_HDFS_REPLICATION=$PXG_REPLICATION
PX_HDFS_REPLICATION_REQUESTED=$PX_HDFS_REPLICATION
PX_HBASE_BLOCKCACHE_SIZE=$PX_HBASE_BLOCKCACHE_SIZE
PX_HBASE_MEMSTORE_SIZE=$PX_HBASE_MEMSTORE_SIZE
${yarn_lines}${tier_lines}PX_GROUPS=$groups
PX_TOPOLOGY_SERVICE_COUNT=$PXG_SERVICES
PX_TOPOLOGY_MEM_MIB=$PXG_MEM_TOTAL
PX_TOPOLOGY_MEM_EAGER_MIB=$PXG_MEM_EAGER
EOF
}

## @description  Parses generator arguments (argument...) against its specification and the shared
##               generator options, recording each --scale override at the cli origin.
## @return       Exits 0 on success, or exits 2 on a usage error or an unscalable --scale target.
pxg_cli() {
  local pair scale
  px_cli_spec <<'EOF'
shared --cluster --class
opt --out DIR PXG_OUT | the deployment directory under $PX_OUT
opt --scale ROLE=COUNT PXG_SCALES+ | set the count of one scaled role (repeatable);
+ px-cluster scale uses it
opt --check - PXG_CHECK=1 | validate and write the service table to stdout, and
+ write no file
opt --no-record - PXG_NO_RECORD=1 | read no cluster record, for px-cluster push
output the path of the deployment directory, or the service table under
+ --check: service role index group start mem_limit_mib heap_mib direct_mib jdk.
EOF
  px_cli_parse "$@"
  for pair in ${PXG_SCALES[@]+"${PXG_SCALES[@]}"}; do
    scale="$(pxg_scale_set "$pair")" || exit "$?"
    px_config_set "${scale%%=*}" "${scale#*=}" cli
  done
}

## @description  Validates that the run class is one of the two supported classes and loads the run
##               class and extra configuration files after command-line parsing.
## @return       Exits 0, or 2 on an unknown run class or rejected value, or 6 on a missing file.
pxg_load() {
  local record=()
  # Because the run class selects an environment file path, reject any value outside the two known
  # classes before the configuration loader constructs a file path from it.
  case "${PX_RUN_CLASS:-}" in
  '' | validation | measurement) ;;
  *)
    px_die "$PX_EXIT_USAGE" "unknown run class '$PX_RUN_CLASS'." \
      "The run classes are validation and measurement"
    ;;
  esac
  [ "$PXG_NO_RECORD" = 0 ] || record=(--no-record)
  px_config_load ${record[@]+"${record[@]}"} -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}
}

# Otherwise resolves PXG_TARGET in the generator's shell to either --out or SUBDIR/<cluster> under
# PX_OUT, without running BUILD.
## @description  On --check or a dry run, runs BUILD (BUILD SUBDIR NOUN) and exits 0; otherwise
##               resolves the target output directory PXG_TARGET.
## @return       Exits 0 (terminating on --check or dry-run), or 2 for PX_OUT or a rejected path.
pxg_prepare() {
  if [ "$PXG_CHECK" = 1 ]; then
    "$1"
    printf '%s' "$PXG_TSV"
    exit 0
  fi
  px_out_root >/dev/null
  PXG_TARGET="$(px_out_resolve "${PXG_OUT:-$2/$PX_CLUSTER_ID}")" || exit $?
  if [ "$PXG_TARGET" = "$PX_OUT" ]; then
    px_die "$PX_EXIT_USAGE" "the deployment directory must be below PX_OUT, not PX_OUT itself"
  fi
  if px_is_dry_run; then
    "$1"
    px_plan write "$PXG_TARGET" "$PXG_SERVICES $3, $PXG_MEM_TOTAL MiB"
    exit 0
  fi
}

## @description  Writes the rendered text content to a target file path (path, text) inside the
##               staging directory.
## @return       Exits 0 on success, or exits 1 if the file cannot be written.
pxg_write_file() {
  printf '%s' "$2" >"$1" || px_die "$PX_EXIT_FAIL" "cannot write $1"
}

# If the target path already exists, it must be either an empty directory or a previous deployment
# directory marked with PXG_MARKER.
## @description  Creates the temporary PXG_STAGING directory alongside the target directory where
##               the generator and its plug-in hooks stage their output before promotion.
## @return       Exits 0, or 1 if staging creation fails, or 2 if the existing target is invalid.
pxg_stage() {
  local target=$1 parent name first=""
  parent=${target%/*}
  name=${target##*/}
  if [ -e "$target" ]; then
    if [ -f "$target/topology.env" ]; then
      IFS= read -r first <"$target/topology.env" || true
    fi
    if [ ! -d "$target" ] ||
      { [ "$first" != "$PXG_MARKER" ] && [ -n "$(ls -A "$target")" ]; }; then
      px_die "$PX_EXIT_USAGE" "$target exists and is not a rendered deployment" \
        "directory. Remove it or name another directory with --out"
    fi
  fi
  mkdir -p "$parent" || px_die "$PX_EXIT_FAIL" "cannot create $parent"
  PXG_STAGING="$parent/.$name.render.$$"
  rm -rf "$PXG_STAGING"
  mkdir "$PXG_STAGING" || px_die "$PX_EXIT_FAIL" "cannot create $PXG_STAGING"
  px_on_exit "rm -rf $(printf '%q' "$PXG_STAGING")"
}

## @description  Scans rendered files (directory, file name...) and aborts the render if an
##               unexpanded placeholder, indicated by a dollar sign on any line, survived.
## @return       Exits 0, or exits 1 on the first file with a dollar sign and reports the line.
pxg_validate() {
  local dir=$1 file hit
  shift
  for file in "$@"; do
    hit="$(grep -n '\$' "$dir/$file" | head -n 1)" || true
    if [ -n "$hit" ]; then
      px_die "$PX_EXIT_FAIL" "a placeholder survived the render in $file: $hit"
    fi
  done
}

# Strips the leading comment block and expands each ${PX_NAME} placeholder in a single pass using
# only the explicitly supplied NAME=VALUE pairs.
## @description  Renders a template file (path, NAME=VALUE...) with strict placeholder expansion.
## @return       Writes the expanded text to stdout and exits 0. Exits 2 on an unset or empty
##               variable name or a malformed ${ sequence, or 1 when the template file is missing.
pxg_expand() {
  local file=$1 line rest name value lineno=0 head=1 out i pair
  local -a names=() values=()
  shift
  for pair in "$@"; do
    names+=("${pair%%=*}")
    values+=("${pair#*=}")
  done
  [ -f "$file" ] || px_die "$PX_EXIT_FAIL" "no such template: $file"
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    if [ "$head" = 1 ]; then
      case "$line" in
      '#'* | '') continue ;;
      esac
      head=0
    fi
    out=""
    rest=$line
    while [[ $rest == *"\${"* ]]; do
      out="$out${rest%%\$\{*}"
      rest=${rest#*\$\{}
      name=${rest%%\}*}
      if [ "$name" = "$rest" ] || ! [[ $name =~ ^PX_[A-Z0-9_]+$ ]]; then
        px_die "$PX_EXIT_USAGE" "the template ${file##*/} line $lineno holds \${$name, which" \
          "is not a \${PX_NAME} placeholder"
      fi
      value=""
      i=0
      while [ "$i" -lt "${#names[@]}" ]; do
        if [ "${names[$i]}" = "$name" ]; then
          value=${values[$i]}
          break
        fi
        i=$((i + 1))
      done
      if [ -z "$value" ]; then
        px_die "$PX_EXIT_USAGE" "the template ${file##*/} line $lineno needs $name, which is" \
          "unbound or empty"
      fi
      out="$out$value"
      rest=${rest#*\}}
    done
    printf '%s\n' "$out$rest"
  done <"$file"
}
