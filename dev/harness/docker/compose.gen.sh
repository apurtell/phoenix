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

# Deployment generator for the Compose backend. This script renders the deployment directory
# consumed by sb_provision, as specified in docs/backends.md under section "The deployment
# directory".
#
# Five design principles govern this generator:
#   1. File-only emission: it writes files exclusively and never invokes a container runtime,
#      remaining outside the runtime boundary verified by the self-test.
#   2. Fail-closed validation: it aborts before writing any file when encountering an unset
#      variable name, an out-of-range value, an unrecognized role or run class, or a value that
#      violates its expected grammar, and the resulting error message names the variable.
#   3. Deterministic rendering: identical configurations and image staging records produce
#      identical output bytes because the role ordering is fixed and no timestamps, process IDs,
#      or local hostnames are embedded in the generated files.
#   4. Centralized port allocation: ports.generated.env serves as the single source of truth for
#      every port number, computed as the family base plus a fixed step multiplied by the
#      zero-based instance index. The backend and every service read their port assignments from
#      this file, and compose.yaml publishes those exact ports exclusively on 127.0.0.1.
#   5. Atomic output under $PX_OUT: all files are written under $PX_OUT, defaulting to
#      $PX_OUT/deploy/<cluster>. Rendering populates a sibling staging directory beside the target
#      first so that a failure leaves any prior deployment directory untouched.
#
# The rendered directory contains compose.yaml, ports.generated.env, topology.env, and
# services.tsv. Each service sets its swap limit equal to its memory limit so that out-of-memory
# kills remain immediately visible rather than being masked by swap activity.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"
# The backend contract defines the cluster name grammar; sourcing it neither binds a backend nor
# contacts a container runtime.
# shellcheck source=SCRIPTDIR/../lib/backend/contract.sh
. "$SELF/../lib/backend/contract.sh"

set -euo pipefail

# Role catalog ordered as emitted in the generated files, with one row per role specifying the
# role name, its sizing variable key (for example, RS in PX_RS_HEAP_MIB), and its port families.
# Because this sequence is part of the rendered output, reordering rows alters every generated
# file. Each port family is encoded as name:base:step, assigning port base + step * i to instance
# i. Base ports retain upstream defaults at instance 0 except for two families shifted to prevent
# a step of 10 from colliding with adjacent allocations: the RegionServer web UI starts at 16025
# because 16030 is the RPC port of instance 1, and the NodeManager family starts at 8140 because
# 8040 + 40 would reach 8080. Singleton role debug ports occupy 15001-15009.
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
setup SETUP debug:15009:0"
PXG_ROLES="$(printf '%s\n' "$PXG_TABLE" | cut -d' ' -f1)"

# Horizontally scalable roles. Scaling is capped at nine instances per role because each scaled
# role allocates debug ports within a single decade from its base (15011-15019, 15021-15029, and
# 15031-15039); a tenth instance would occupy the decade guard port, and an eleventh would collide
# with the base port of the subsequent role.
PXG_SCALED="datanode nodemanager regionserver"

# Constants shared between the container image and the deployment generator. Every service attaches
# to the harness bridge network with <role>-<index> as both its hostname and service name. Daemons
# bind to all interfaces within their container, while the deployment publishes each mapped port
# exclusively on the host loopback address using matching host and container port numbers. The
# image invokes its healthcheck script at PXG_HEALTHCHECK, and stateful roles persist their data at
# the PXG_DATA_DIR volume mount point.
PXG_BIND_HOST=0.0.0.0
PXG_PUBLISH_HOST=127.0.0.1
PXG_HEALTHCHECK=/opt/px/healthcheck.sh
PXG_DATA_DIR=/data
# Read-only container mount point where the setup role mounts the host state directory so the
# bootstrap probe can inspect the health monitor heartbeat.
PXG_STATE_MOUNT=/var/lib/px/state

# Header marker written as the first line of topology.env. A target directory whose topology.env
# begins with this marker is recognized as a rendered deployment that the generator may safely
# replace.
PXG_MARKER="# Rendered by compose.gen.sh. Render again instead of editing."

# Validation grammars governing values emitted by the generator. Rendered values appear inside
# double quotes in YAML and unquoted in environment files, and Compose interpolates dollar signs in
# both contexts. Consequently, no grammar permits dollar signs, quotes, backslashes, or hashes, and
# every emitted value either matches one of these patterns or is a number computed during rendering.
PXG_MIB_RE='^[1-9][0-9]*$'
PXG_FRACTION_RE='^0\.[0-9][0-9]?$'
PXG_VERSION_RE='^[A-Za-z0-9][A-Za-z0-9._-]*$'
PXG_LABEL_RE='^[a-z0-9][a-z0-9.-]*$'
PXG_PATH_RE='^/[A-Za-z0-9 ._/+@-]*$'

# Memory totals recorded in topology.env, aggregated from the limits of the rendered services:
# PX_TOPOLOGY_MEM_MIB covers all services, PX_TOPOLOGY_MEM_EAGER_MIB covers the services started by
# sb_provision, and PX_GROUP_YARN_MEM_MIB covers the on-demand YARN tier. These figures are
# reconciled with the design budget in docker/env/class-measurement.env. The setup service is
# counted in full because it can remain resident while the remaining roles start.

# Per-render output and accounting accumulators.
PXG_YAML=""
PXG_VOLUMES=""
PXG_PORTS=""
PXG_PUBLISHED=""
PXG_TSV=""
PXG_RENDERED=""
PXG_YARN_ROLES=""
PXG_STATE_HOST=""
PXG_HEARTBEAT=""
PXG_SERVICES=0
PXG_MEM_TOTAL=0
PXG_MEM_EAGER=0
PXG_MEM_YARN=0
PXG_REPLICATION=0
PXG_ZOOKEEPER=""
PXG_HBASE_HADOOP=""

## @description  Prints command-line usage instructions and exit code documentation.
## @return       Writes the usage text to stdout and exits with status 0.
usage() {
  cat <<'EOF'
Usage: compose.gen.sh [option...]

Renders the Compose deployment directory of one cluster under $PX_OUT. It
writes files only and never calls a container runtime. It writes the path of
the directory to stdout.

Options.
  -h, --help             print this text and exit 0
  -v, --verbose          report debug detail on stderr
  -n, --dry-run          validate and report, but write no file
      --strict           refuse a name that lib/varnames.txt does not declare
      --class NAME       run class: validation or measurement
      --cluster ID       cluster name, default PX_CLUSTER_ID or default
      --out DIR          deployment directory under $PX_OUT,
                         default $PX_OUT/deploy/<cluster>
      --config FILE      extra configuration file, strongest file stage,
                         repeatable
  -e, --set NAME=VALUE   set one configuration value, repeatable, for example
                         PX_RS_COUNT=5 or PX_CLIENT_JDK=21
      --check            validate and write the service table to stdout, but
                         write no file

The run class is required: --class, PX_RUN_CLASS in the environment, or
PX_RUN_CLASS in env/local.env beside this command. env/override-24gib.env
beside this command fits a measurement-shaped run into a 24 GiB engine:
  compose.gen.sh --class measurement --config <its path>

The service table has one row for each service, tab separated:
  service role index group start mem_limit_mib heap_mib direct_mib jdk

Exit codes: 0 rendered; 1 the directory cannot be written; 2 usage, an unset
or invalid value, an unknown run class, or a target directory that is not a
rendered deployment; 6 a --config file is absent.
EOF
}

## @description  Looks up a role in the catalog and prints either its sizing variable key (key) or
##               its port family definitions (ports).
## @return       Writes the requested column to stdout and exits 0, or exits 1 for an unknown role.
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

## @description  Determines whether the specified role supports scaling to multiple instances.
## @return       Exits 0 if the role is scalable, or 1 otherwise.
pxg_is_scaled() {
  case " $PXG_SCALED " in
  *" ${1:-} "*) return 0 ;;
  esac
  return 1
}

# Only the YARN tier forms a role group because it is the only tier that starts and stops on demand
# while the cluster is running.
## @description  Reports the lifecycle group associated with a role.
## @return       Writes the group name to stdout (or nothing if ungrouped) and exits 0.
pxg_role_group() {
  case "${1:-}" in
  resourcemanager | nodemanager | jobhistory) printf 'yarn\n' ;;
  esac
}

# The setup role mounts the NameNode data volume so that it can format the HDFS metadata.
## @description  Resolves the persistent volume name mounted at the data directory for a given role
##               and instance index.
## @return       Writes the volume name to stdout (or nothing for stateless roles) and exits 0.
pxg_volume_of() {
  case "${1:-}" in
  zookeeper | namenode | secondarynamenode | datanode) printf '%s\n' "$1-$2-data" ;;
  setup) printf '%s\n' namenode-0-data ;;
  esac
}

## @description  Verifies that all specified configuration variables are non-empty, reporting every
##               missing variable name in a single diagnostic message if any are unset.
## @return       Exits 0 when every variable has a value, or aborts with exit code 2 otherwise.
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

## @description  Validates that the named configuration variable holds a positive integer.
## @return       Exits 0 if the value is a whole number greater than zero, or exits 2 otherwise.
pxg_check_mib() {
  local name=$1
  if ! [[ ${!name:-} =~ $PXG_MIB_RE ]]; then
    px_die "$PX_EXIT_USAGE" "$name='${!name:-}' must be a whole number above zero"
  fi
}

## @description  Validates that the named boolean switch variable is set to either 0 or 1.
## @return       Exits 0 for a valid switch value, or exits 2 on any other value.
pxg_check_switch() {
  local name=$1
  case "${!name:-}" in
  0 | 1) ;;
  *) px_die "$PX_EXIT_USAGE" "$name='${!name:-}' must be 0 or 1" ;;
  esac
}

# The image staging record, when present, must match the configured HBase profile and component
# releases for the active version set.
## @description  Populates PXG_ZOOKEEPER and PXG_HBASE_HADOOP from the image staging record.
## @return       Exits 0 if the record matches or is absent, or exits 2 if the record belongs to
##               another version set or lacks a required release version.
pxg_staged() {
  local root record text key name value
  PXG_ZOOKEEPER=unstaged
  PXG_HBASE_HADOOP=unstaged
  root="$(px_out_root 2>/dev/null)" || return 0
  record="$root/images/$(px_image_stage_name "$PX_IMAGE_TAG").staged.tsv"
  [ -f "$record" ] || return 0
  text="$(cat "$record")"
  for key in hbase_profile:PX_PHOENIX_HBASE_PROFILE hbase_version:PX_DIST_HBASE_VERSION \
    hadoop_version:PX_DIST_HADOOP_VERSION; do
    name=${key#*:}
    value="$(px_tsv_get "${key%%:*}" "$text")" || value=""
    if [ "$value" != "${!name}" ]; then
      px_die "$PX_EXIT_USAGE" "the image $PX_IMAGE_TAG was staged with ${key%%:*} '$value'," \
        "and $name is '${!name}': stage the image again, or select another PX_IMAGE_TAG"
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

## @description  Validates that the instance count of a scalable role falls between 1 and 9
##               inclusive, normalizing the variable in place to strip any leading zeros.
## @return       Exits 0 when the count is within range, or exits 2 otherwise.
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

# A role's sizing is valid only when its container memory limit, JVM heap, and direct memory are
# positive integers in MiB, its selected JDK is supported, and the sum of heap and direct memory
# (plus YARN container memory for a NodeManager) remains strictly below the container memory limit.
## @description  Validates the memory sizing parameters and JDK selection for a role.
## @return       Exits 0 when all sizing constraints hold, or exits 2 on an invalid value.
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
}

## @description  Converts a two-decimal-place fraction variable (0.d or 0.dd) into hundredths.
## @return       Writes the integer hundredths to stdout and exits 0, or exits 2 if the value is not
##               formatted as 0.d or 0.dd.
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

## @description  Validates all inputs read during rendering and populates the derived PXG_* values.
## @return       Exits 0 when valid, 2 on any rejected value, or 1 when the state directory cannot
##               be resolved.
pxg_configure() {
  local role key cache store name
  local required=()
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

  pxg_require PX_YARN PX_SNN PX_JVM_DEBUG PX_DN_COUNT PX_NM_COUNT PX_RS_COUNT \
    PX_HDFS_REPLICATION PX_HBASE_BLOCKCACHE_SIZE PX_HBASE_MEMSTORE_SIZE \
    PX_IMAGE_TAG PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION \
    PX_LABEL_PREFIX
  pxg_check_switch PX_YARN
  pxg_check_switch PX_SNN
  pxg_check_switch PX_JVM_DEBUG
  for role in $PXG_SCALED; do
    pxg_check_count "$role"
  done

  # Every role is included in the deployment unless disabled by its feature switch. Enabling the
  # YARN switch includes the YARN tier in the rendered deployment definition without starting it
  # automatically at provision time.
  PXG_RENDERED=""
  for role in $PXG_ROLES; do
    case "$role" in
    secondarynamenode) [ "$PX_SNN" = 1 ] || continue ;;
    resourcemanager | nodemanager | jobhistory) [ "$PX_YARN" = 1 ] || continue ;;
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

  # HBase refuses to start if the combined block cache and global memstore fractions exceed 0.8 of
  # the JVM heap, so the generator rejects such configurations upfront.
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
  for name in PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION; do
    if ! [[ ${!name} =~ $PXG_VERSION_RE ]]; then
      px_die "$PX_EXIT_USAGE" "$name='${!name}' must be a version: letters, digits, . _ -"
    fi
  done
  pxg_staged
  if ! [[ $PX_LABEL_PREFIX =~ $PXG_LABEL_RE ]]; then
    px_die "$PX_EXIT_USAGE" "PX_LABEL_PREFIX='$PX_LABEL_PREFIX' must hold lower case" \
      "letters, digits, dots and hyphens"
  fi

  # The bootstrap probe verifies the health monitor heartbeat. Because the monitor refreshes its
  # heartbeat after every sampling pass, a live heartbeat is at most one interval plus the duration
  # of one sample old, and the probe accepts a maximum heartbeat age of three intervals.
  pxg_require PX_MONITOR_INTERVAL_S PX_PROJECT_PREFIX
  pxg_check_mib PX_MONITOR_INTERVAL_S
  PXG_STATE_HOST="$(px_out_resolve "${PX_STATE_DIR:-state}")" || exit $?
  if ! [[ $PXG_STATE_HOST =~ $PXG_PATH_RE ]]; then
    px_die "$PX_EXIT_USAGE" "the state directory '$PXG_STATE_HOST' must hold letters," \
      "digits, spaces and . _ / + @ - only, because the setup role mounts it"
  fi
  name="$(px_guard_name monitor "$PX_PROJECT_PREFIX-$PX_CLUSTER_ID")"
  PXG_HEARTBEAT="$PXG_STATE_MOUNT/$name.heartbeat"
}

## @description  Appends a single line to the accumulated Compose YAML buffer.
## @return       Exits 0.
pxg_y() {
  PXG_YAML="$PXG_YAML$1$PX_NL"
}

# Debug ports are emitted into the port map only when PX_JVM_DEBUG=1.
## @description  Appends the port assignments for a service instance (role, index) to the port map
##               and records the resulting port list in PXG_PUBLISHED.
## @return       Exits 0.
pxg_render_ports() {
  local role=$1 index=$2 suffix="" family base step var port
  PXG_PUBLISHED=""
  if pxg_is_scaled "$role"; then suffix=$index; fi
  for family in $(pxg_role "$role" ports); do
    if [ "${family%%:*}" = debug ] && [ "$PX_JVM_DEBUG" != 1 ]; then
      continue
    fi
    base=${family#*:}
    step=${base#*:}
    var="$(sb_port_var "$role" "${family%%:*}" "$suffix")"
    port=$((${base%%:*} + step * index))
    PXG_PORTS="$PXG_PORTS$var=$port$PX_NL"
    PXG_PUBLISHED="$PXG_PUBLISHED $port"
  done
}

## @description  Renders the Compose definition for one service instance (role, index), appends its
##               entry to the service table, and updates the topology memory totals.
## @return       Exits 0.
pxg_render_service() {
  local role=$1 index=$2 key service mem heap direct jdk group start
  local prefix=$PX_LABEL_PREFIX var volume port
  key="$(pxg_role "$role" key)"
  service="$role-$index"
  var="PX_${key}_MEM_MIB" && mem=${!var}
  var="PX_${key}_HEAP_MIB" && heap=${!var}
  var="PX_${key}_DIRECT_MIB" && direct=${!var}
  var="PX_${key}_JDK" && jdk=${!var}
  group="$(pxg_role_group "$role")"
  volume="$(pxg_volume_of "$role" "$index")"
  start=""
  if [ -n "$group" ]; then
    start=on-demand
  fi

  # A single container image serves every role and bundles all supported JDKs, with PX_JDK selecting
  # the active runtime when the instance starts. The image entrypoint translates the memory sizing
  # variables into JVM flags using G1GC across all roles. Setting the container hostname to the
  # service name allows peers to resolve the instance directly on the harness network.
  pxg_y "  $service:"
  pxg_y "    image: \"$PX_IMAGE_TAG\""
  pxg_y "    pull_policy: never"
  pxg_y "    hostname: \"$service\""
  pxg_y "    networks:"
  pxg_y "      - default"
  pxg_y "    restart: \"no\""
  pxg_y "    mem_limit: ${mem}m"
  pxg_y "    memswap_limit: ${mem}m"
  pxg_y "    labels:"
  pxg_y "      $prefix.cluster: \"$PX_CLUSTER_ID\""
  if [ "$role" = regionserver ]; then
    pxg_y "      $prefix.drain: \"regionmover\""
  fi
  if [ -n "$group" ]; then
    pxg_y "      $prefix.group: \"$group\""
  fi
  pxg_y "      $prefix.index: \"$index\""
  pxg_y "      $prefix.role: \"$role\""
  if [ -n "$start" ]; then
    pxg_y "      $prefix.start: \"$start\""
  fi
  pxg_y "    env_file:"
  pxg_y "      - ports.generated.env"
  pxg_y "      - topology.env"
  pxg_y "    environment:"
  if [ -n "$volume" ]; then
    pxg_y "      PX_DATA_DIR: \"$PXG_DATA_DIR\""
  fi
  pxg_y "      PX_DIRECT_MIB: \"$direct\""
  pxg_y "      PX_HEAP_MIB: \"$heap\""
  pxg_y "      PX_HOSTNAME: \"$service\""
  pxg_y "      PX_INSTANCE_INDEX: \"$index\""
  pxg_y "      PX_JDK: \"$jdk\""
  pxg_y "      PX_MEM_MIB: \"$mem\""
  if [ "$role" = setup ]; then
    pxg_y "      PX_MONITOR_HEARTBEATS: \"$PXG_HEARTBEAT\""
    pxg_y "      PX_MONITOR_STALE_S: \"$((3 * PX_MONITOR_INTERVAL_S))\""
  fi
  pxg_y "      PX_ROLE: \"$role\""
  # The one-shot setup service also defines a healthcheck so that it remains unready until the
  # bootstrap probe succeeds, ensuring the cluster readiness barrier waits for completion while any
  # probe failure surfaces as a non-zero exit status.
  pxg_y "    healthcheck:"
  pxg_y "      test: [\"CMD\", \"$PXG_HEALTHCHECK\"]"
  pxg_y "      interval: 5s"
  pxg_y "      timeout: 15s"
  pxg_y "      retries: 3"
  pxg_y "      start_period: 900s"
  # Every port assigned in the port map is published exclusively on the host loopback address and
  # mapped to the identical port number inside the container.
  pxg_render_ports "$role" "$index"
  if [ -n "$PXG_PUBLISHED" ]; then
    pxg_y "    ports:"
    for port in $PXG_PUBLISHED; do
      pxg_y "      - \"$PXG_PUBLISH_HOST:$port:$port\""
    done
  fi
  if [ -n "$volume" ]; then
    pxg_y "    volumes:"
    pxg_y "      - $volume:$PXG_DATA_DIR"
    if [ "$role" = setup ]; then
      pxg_y "      - \"$PXG_STATE_HOST:$PXG_STATE_MOUNT:ro\""
    fi
  fi
  # Each persistent volume is declared once in the top-level volumes section by its owning instance.
  if [ "$volume" = "$service-data" ]; then
    PXG_VOLUMES="$PXG_VOLUMES  $volume:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES    labels:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES      $prefix.cluster: \"$PX_CLUSTER_ID\"$PX_NL"
  fi

  PXG_TSV="$PXG_TSV$(px_tsv_row "$service" "$role" "$index" "$group" "$start" \
    "$mem" "$heap" "$direct" "$jdk")$PX_NL"
  PXG_SERVICES=$((PXG_SERVICES + 1))
  PXG_MEM_TOTAL=$((PXG_MEM_TOTAL + mem))
  if [ -n "$group" ]; then
    PXG_MEM_YARN=$((PXG_MEM_YARN + mem))
  else
    PXG_MEM_EAGER=$((PXG_MEM_EAGER + mem))
  fi
}

# The self-test verifies that the maximum port map (nine instances of every scalable role) produces
# no port collisions.
## @description  Iterates over all active roles and renders every configured service instance.
## @return       Populates the render accumulators and exits 0.
pxg_build() {
  local role count index var
  PXG_YAML="" PXG_VOLUMES="" PXG_PORTS="" PXG_TSV="" PXG_YARN_ROLES=""
  PXG_SERVICES=0 PXG_MEM_TOTAL=0 PXG_MEM_EAGER=0 PXG_MEM_YARN=0
  for role in $PXG_RENDERED; do
    count=1
    if pxg_is_scaled "$role"; then
      var="PX_$(pxg_role "$role" key)_COUNT"
      count=${!var}
    fi
    if [ -n "$(pxg_role_group "$role")" ]; then
      PXG_YARN_ROLES="${PXG_YARN_ROLES:+$PXG_YARN_ROLES }$role"
    fi
    index=0
    while [ "$index" -lt "$count" ]; do
      pxg_render_service "$role" "$index"
      index=$((index + 1))
    done
  done
}

## @description  Formats the topology.env content consumed as an environment file by every service
##               and archived in the results bundle.
## @return       Writes the rendered topology file text to stdout and exits 0.
pxg_topology_text() {
  local groups="" yarn_lines=""
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
PX_CLUSTER_ID=$PX_CLUSTER_ID
PX_RUN_CLASS=$PX_RUN_CLASS
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
${yarn_lines}PX_GROUPS=$groups
PX_TOPOLOGY_SERVICE_COUNT=$PXG_SERVICES
PX_TOPOLOGY_MEM_MIB=$PXG_MEM_TOTAL
PX_TOPOLOGY_MEM_EAGER_MIB=$PXG_MEM_EAGER
EOF
}

## @description  Assembles the complete compose.yaml document for the deployment.
## @return       Writes the Compose file text to stdout and exits 0.
pxg_compose_text() {
  printf '%s\n' "$PXG_MARKER"
  printf '%s\n' "# Cluster $PX_CLUSTER_ID, run class $PX_RUN_CLASS. The backend names the"
  printf '%s\n' "# project, so this file names none. Every port is from ports.generated.env."
  printf 'services:\n%s' "$PXG_YAML"
  # The cluster uses the Compose project's default bridge network so the backend can locate it as
  # <project>_default, and tags the network with the cluster label.
  printf 'networks:\n  default:\n    driver: bridge\n    labels:\n'
  printf '      %s.cluster: "%s"\n' "$PX_LABEL_PREFIX" "$PX_CLUSTER_ID"
  if [ -n "$PXG_VOLUMES" ]; then
    printf 'volumes:\n%s' "$PXG_VOLUMES"
  fi
}

## @description  Assembles the complete ports.generated.env port mapping document.
## @return       Writes the port map file text to stdout and exits 0.
pxg_ports_text() {
  printf '%s\n' "$PXG_MARKER"
  printf '%s\n' "# The port map, stage 8 of the configuration loader. A port is the base of"
  printf '%s\n' "# its family plus a fixed step times the zero-based instance index. The"
  printf '%s\n' "# deployment publishes every port of this file on $PXG_PUBLISH_HOST of the host,"
  printf '%s\n' "# with the same number inside the container, and no other port."
  printf '%s' "$PXG_PORTS"
}

## @description  Writes the given text content to a file path inside the staging directory.
## @return       Exits 0 on success, or exits 1 if the file cannot be written.
pxg_write_file() {
  printf '%s' "$2" >"$1" || px_die "$PX_EXIT_FAIL" "cannot write $1"
}

## @description  Atomically writes the target deployment directory through a sibling staging path.
## @return       Exits 0 on success, exits 1 if a write fails or a dollar sign survives rendering,
##               or exits 2 if the target exists and is neither empty nor a rendered deployment.
pxg_write() {
  local target=$1 parent name staging file hit first=""
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
  staging="$parent/.$name.render.$$"
  rm -rf "$staging"
  mkdir "$staging" || px_die "$PX_EXIT_FAIL" "cannot create $staging"
  px_on_exit "rm -rf $(printf '%q' "$staging")"
  pxg_write_file "$staging/compose.yaml" "$(pxg_compose_text)$PX_NL"
  pxg_write_file "$staging/ports.generated.env" "$(pxg_ports_text)$PX_NL"
  pxg_write_file "$staging/topology.env" "$(pxg_topology_text)$PX_NL"
  pxg_write_file "$staging/services.tsv" \
    "# service role index group start mem_limit_mib heap_mib direct_mib jdk$PX_NL$PXG_TSV"
  # Because Compose interpolates dollar signs, any unexpanded placeholder would silently evaluate to
  # an empty string at provision time. This post-write check inspects the rendered files on disk
  # after all values have passed grammar validation.
  for file in compose.yaml ports.generated.env topology.env services.tsv; do
    hit="$(grep -n '\$' "$staging/$file" | head -n 1)" || true
    if [ -n "$hit" ]; then
      px_die "$PX_EXIT_FAIL" "a placeholder survived the render in $file: $hit"
    fi
  done
  rm -rf "$target"
  mv "$staging" "$target" || px_die "$PX_EXIT_FAIL" "cannot move the render into $target"
}

## @description  Parses command-line flags, loads staged configuration, and renders the deployment.
## @return       Writes the deployment directory path (or the service table under --check) to stdout
##               and exits 0, or exits 1, 2, or 6 as documented in the usage text.
pxg_main() {
  local strict="" check=0 out="" target files=() sets=()
  while [ $# -gt 0 ]; do
    px_cli_common "$1" || case "$1" in
    --strict) strict=--strict ;;
    --class) px_cli_need_value "$@" && sets+=("PX_RUN_CLASS=$2") && shift ;;
    --cluster) px_cli_need_value "$@" && sets+=("PX_CLUSTER_ID=$2") && shift ;;
    --out) px_cli_need_value "$@" && out=$2 && shift ;;
    --config) px_cli_need_value "$@" && files+=("$2") && shift ;;
    -e | --set) px_cli_need_value "$@" && sets+=("$2") && shift ;;
    --check) check=1 ;;
    *) px_usage_error "unknown argument $1" ;;
    esac
    shift
  done
  # Command-line overrides occupy the highest-precedence configuration stage and are applied before
  # the file loader runs.
  px_config_set_cli ${strict:+"$strict"} -- ${sets[@]+"${sets[@]}"}
  # Because the run class selects a configuration file path, any value outside the two supported
  # classes is rejected before the loader constructs a path from it.
  case "${PX_RUN_CLASS:-}" in
  '' | validation | measurement) ;;
  *)
    px_die "$PX_EXIT_USAGE" "unknown run class '$PX_RUN_CLASS'." \
      "The run classes are validation and measurement"
    ;;
  esac
  px_config_load ${strict:+"$strict"} -- ${files[@]+"${files[@]}"}
  pxg_configure
  pxg_build

  if [ "$check" = 1 ]; then
    printf '%s' "$PXG_TSV"
    return 0
  fi
  px_out_root >/dev/null
  target="$(px_out_resolve "${out:-deploy/$PX_CLUSTER_ID}")" || exit $?
  if [ "$target" = "$PX_OUT" ]; then
    px_die "$PX_EXIT_USAGE" "the deployment directory must be below PX_OUT, not PX_OUT itself"
  fi
  if px_is_dry_run; then
    px_info "dry run: $PXG_SERVICES services, $PXG_MEM_TOTAL MiB, not written to $target"
    printf '%s\n' "$target"
    return 0
  fi
  pxg_write "$target"
  px_info "rendered $PXG_SERVICES services for cluster $PX_CLUSTER_ID, class" \
    "$PX_RUN_CLASS: $PXG_MEM_TOTAL MiB of container limits, $PXG_MEM_YARN MiB" \
    "of them on demand, into $target"
  printf '%s\n' "$target"
}

pxg_main "$@"
