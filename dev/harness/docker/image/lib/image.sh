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

# Shared runtime library for the container image scripts, sourced by entrypoint.sh, healthcheck.sh,
# render.sh, and bin/px-bootstrap-probe.
#
# Because the container image packages only the docker/image directory, this library does not
# source lib/common.sh from the harness. Furthermore, because the self-test suite sources this
# library directly on the host using a fake curl and a fake hdfs, the code maintains compatibility
# with the Bash 3.2 baseline and avoids flags that exist only in GNU userland tools.
#
# The environment contract for a single instance consists solely of the variables below, which the
# generator injects and beyond which the image reads no other external inputs:
#   identity     Identifies the instance through PX_ROLE, PX_INSTANCE_INDEX, PX_HOSTNAME, PX_JDK,
#                and PX_CLUSTER_ID.
#   sizing       Bounds container and JVM memory through PX_HEAP_MIB, PX_DIRECT_MIB, and
#                PX_MEM_MIB.
#   topology     Describes cluster structure through PX_BIND_HOST, PX_DN_COUNT, PX_NM_COUNT,
#                PX_RS_COUNT, PX_HDFS_REPLICATION, and PX_YARN.
#   hbase tuning Configures HBase memory regions through PX_HBASE_BLOCKCACHE_SIZE and
#                PX_HBASE_MEMSTORE_SIZE.
#   yarn sizing  Configures YARN and MapReduce containers through PX_NM_RESOURCE_MIB,
#                PX_NM_VCORES, PX_YARN_MIN_ALLOC_MIB, PX_YARN_MAX_ALLOC_MIB, PX_MR_AM_MIB, and
#                PX_MR_TASK_MIB, which a deployment without YARN omits.
#   ports        Publishes every PX_PORT_* variable from ports.generated.env, from which the image
#                reads the port names client, rpc, http, data, ipc, scheduler, tracker, admin,
#                localizer, shuffle, and debug.
#   layout       Defines container paths through PX_HADOOP_HOME, PX_HBASE_HOME, PX_PHOENIX_HOME,
#                PX_JDK_ROOT, PX_TOOLS_DIR, PX_DATA_DIR, PX_LOG_DIR, and PX_CONF_DIR, which the
#                Dockerfile sets.
#   hooks        Controls diagnostics and lifecycle hooks through PX_JVM_DEBUG, PX_JVM_OPTS_EXTRA,
#                PX_CONF_OVERLAY_DIR, PX_BOOTSTRAP_SETUP_DIR, PX_MONITOR_HEARTBEATS,
#                PX_MONITOR_STALE_S, PX_TIMEOUT_READY, PX_POLL_INTERVAL_S, and PX_VERBOSE.
# The image scripts themselves derive and export the PX_RENDER_* variables, which hold synthesized
# values that configuration templates require but no single raw input provides, such as the clamped
# replication factor.
#
# Every container instance attaches to the harness network with the hostname <role>-<index>.
# Configuration templates address peer instances by that hostname, while each service daemon binds
# to PX_BIND_HOST so that it listens across every interface of its own container.

# This file is a shell library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'image.sh is a library. Source it from an image script.\n' >&2
  exit 2
fi

# Sourcing this library more than once preserves the state initialized during the initial load.
if [ -n "${IMG_LIB_LOADED:-}" ]; then
  return 0
fi
IMG_LIB_LOADED=1

IMG_PROG="${0##*/}"

# Standard exit status codes from the harness vocabulary used across the image scripts.
IMG_EXIT_FAIL=1
IMG_EXIT_USAGE=2
IMG_EXIT_TIMEOUT=4
IMG_EXIT_NO_OBJECT=6

# The complete set of service roles supported by this image. Each role name also serves as the
# value of the container role label, and its uppercase form forms the role segment of PX_PORT_*
# environment variable names.
IMG_ROLES="zookeeper namenode secondarynamenode datanode resourcemanager nodemanager"
IMG_ROLES="$IMG_ROLES jobhistory hbase-master regionserver client tooling setup"

# The maximum instance count allowed for any scaled role. This bound is determined by the allocated
# debug port ranges and enforced across both orchestration backends.
IMG_COUNT_MAX=9

# The JDK feature releases available for role execution. JDK 17 is the default release because the
# project builds and runs its test suites on JDK 17.
IMG_JDKS="11 17 21"
IMG_JDK_DEFAULT=17

# The maximum duration, in seconds, that a single probe request may wait for a response. Allowing a
# probe to block longer than the healthcheck interval would cause consecutive probes to overlap.
IMG_HTTP_TIMEOUT_S=10

# The relative path under PX_DATA_DIR of the marker file created by the setup role once NameNode
# metadata formatting succeeds. The NameNode waits for this marker because Hadoop writes
# current/VERSION before it finishes writing the namespace image.
IMG_FORMATTED=namenode/.px-formatted

IMG_RE_INT='^[0-9]+$'
IMG_RE_PX_NAME='^PX_[A-Z0-9_]+$'

# Global accumulators holding the diagnostic reason from the most recent check and the response
# body from the most recent management HTTP request. Check functions populate these variables
# directly so that callers can inspect both the output and the function return status without
# losing state across a command substitution subshell.
IMG_REASON=""
IMG_BODY=""

# ---------------------------------------------------------------------------
# Structured logging and diagnostic message emission produce role-prefixed output.
# ---------------------------------------------------------------------------

# Formats a timestamped log entry containing the script name, role, and instance index so that
# interleaved container logs can distinguish between service instances.
## @description  Writes a single formatted log line (level, message...) to standard error.
## @return       Returns exit code 0.
img_log() {
  local level=$1 ts
  shift
  ts="$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || printf 'unknown-time')"
  printf '%s %-5s %s[%s/%s]: %s\n' "$ts" "$level" "$IMG_PROG" \
    "${PX_ROLE:-?}" "${PX_INSTANCE_INDEX:-?}" "$*" >&2
}

## @description  Emits an informational progress message to standard error.
## @return       Returns exit code 0.
img_info() { img_log INFO "$@"; }

## @description  Emits a warning message for an anomalous condition that does not halt execution.
## @return       Returns exit code 0.
img_warn() { img_log WARN "$@"; }

## @description  Emits an error message to report a failure while leaving recovery to the caller.
## @return       Returns exit code 0.
img_error() { img_log ERROR "$@"; }

## @description  Emits detailed diagnostic output when PX_VERBOSE is enabled.
## @return       Returns exit code 0.
img_debug() {
  img_is_true "${PX_VERBOSE:-0}" || return 0
  img_log DEBUG "$@"
}

## @description  Logs a fatal error message (exit code, message...) and terminates the process.
## @return       Terminates the process with the caller-supplied exit code.
img_die() {
  local code=$1
  shift
  img_error "$* (exit $code)"
  exit "$code"
}

# ---------------------------------------------------------------------------
# Environment variable lookup, role validation, and network endpoint resolution enforce the
# instance configuration contract.
# ---------------------------------------------------------------------------

## @description  Evaluates whether a boolean configuration flag is enabled.
## @return       Returns exit code 0 for 1, true, yes, or on in any letter case, or 1 otherwise.
img_is_true() {
  case "${1:-}" in
  1 | [Tt][Rr][Uu][Ee] | [Yy][Ee][Ss] | [Oo][Nn]) return 0 ;;
  *) return 1 ;;
  esac
}

# Validates the variable identifier against the PX_* naming pattern before performing indirect
# expansion so that untrusted text cannot execute arbitrary shell code.
## @description  Resolves the value of a PX_* environment variable by name, or empty when unset.
## @return       Writes the value and returns exit code 0, or returns exit code 2 for non-PX_*
##               variable names.
img_var() {
  local name=$1 value=""
  if ! [[ $name =~ $IMG_RE_PX_NAME ]]; then
    img_error "not a PX_* variable name: '$name'"
    return "$IMG_EXIT_USAGE"
  fi
  eval "value=\${$name-}"
  printf '%s\n' "$value"
}

## @description  Normalizes a role or port identifier into its PX_PORT_* token form by converting
##               letters to uppercase and replacing hyphens and dots with underscores.
## @return       Returns exit code 0.
img_upper() {
  printf '%s\n' "$1" | tr 'abcdefghijklmnopqrstuvwxyz.-' 'ABCDEFGHIJKLMNOPQRSTUVWXYZ__'
}

## @description  Determines whether a given identifier names a role supported by the image.
## @return       Returns exit code 0 when the image serves the role, or exit code 1 otherwise.
img_role_valid() {
  local role
  for role in $IMG_ROLES; do
    [ "$role" = "${1:-}" ] && return 0
  done
  return 1
}

## @description  Identifies the instance-count environment variable associated with a scaled role.
## @return       Writes the variable name to standard output and returns exit code 0 for a scaled
##               role, or returns exit code 1 for a singleton role.
img_role_count_var() {
  case "$1" in
  datanode) printf 'PX_DN_COUNT\n' ;;
  nodemanager) printf 'PX_NM_COUNT\n' ;;
  regionserver) printf 'PX_RS_COUNT\n' ;;
  *) return 1 ;;
  esac
}

## @description  Reads an instance count (variable name) and validates the range from 1 to 9.
## @return       Writes the count to stdout and exits with 0, exits with 2 if not a whole number
##               from 1 to 9, or exits with 6 when the variable is unset.
img_count() {
  local name=$1 value
  value="$(img_var "$name")" || return "$IMG_EXIT_USAGE"
  if [ -z "$value" ]; then
    img_error "$name is unset: the instance count of a scaled role is required"
    return "$IMG_EXIT_NO_OBJECT"
  fi
  if ! [[ $value =~ $IMG_RE_INT ]] || [ "$value" -lt 1 ] ||
    [ "$value" -gt "$IMG_COUNT_MAX" ]; then
    img_error "$name=$value is outside the range 1 to $IMG_COUNT_MAX"
    return "$IMG_EXIT_USAGE"
  fi
  printf '%s\n' "$((10#$value))"
}

# Validates PX_ROLE and PX_INSTANCE_INDEX against the topology contract. Singleton roles default to
# index 0, whereas scaled roles must carry a valid index strictly below their configured count.
## @description  Validates PX_ROLE and PX_INSTANCE_INDEX and exports their canonical values.
## @return       Returns exit code 0 on success, or exit code 2 when the role is unknown or the
##               instance index is absent, invalid, or too large.
img_identity() {
  local role=${PX_ROLE:-} index=${PX_INSTANCE_INDEX:-} count_var count
  if [ -z "$role" ]; then
    img_error "PX_ROLE is unset: the deployment must name the role of every instance"
    return "$IMG_EXIT_USAGE"
  fi
  if ! img_role_valid "$role"; then
    img_error "PX_ROLE=$role is not a role of this image. Roles: $IMG_ROLES"
    return "$IMG_EXIT_USAGE"
  fi
  if count_var="$(img_role_count_var "$role")"; then
    if [ -z "$index" ]; then
      img_error "PX_INSTANCE_INDEX is unset: the scaled role $role needs its index"
      return "$IMG_EXIT_USAGE"
    fi
  else
    count_var=""
    index=${index:-0}
  fi
  if ! [[ $index =~ $IMG_RE_INT ]]; then
    img_error "PX_INSTANCE_INDEX=$index is not a whole number"
    return "$IMG_EXIT_USAGE"
  fi
  index=$((10#$index))
  if [ -n "$count_var" ]; then
    count="$(img_count "$count_var")" || return "$IMG_EXIT_USAGE"
    if [ "$index" -ge "$count" ]; then
      img_error "PX_INSTANCE_INDEX=$index is not below $count_var=$count"
      return "$IMG_EXIT_USAGE"
    fi
  elif [ "$index" -ne 0 ]; then
    img_error "PX_INSTANCE_INDEX=$index: the role $role has one instance, index 0"
    return "$IMG_EXIT_USAGE"
  fi
  PX_ROLE=$role
  PX_INSTANCE_INDEX=$index
  export PX_ROLE PX_INSTANCE_INDEX
}

# Resolves a service port from the generated port environment map without ever computing port
# numbers dynamically; an instance-indexed variable takes precedence over an unindexed variable.
## @description  Looks up a published port (role, port name, optional index) in the port map.
## @return       Writes the port to stdout and exits with 0, exits with 2 when the value is not a
##               valid port number, or exits with 6 when the port variable is absent.
img_port() {
  local role=$1 port=$2 index=${3:-} base value="" tried
  base="PX_PORT_$(img_upper "$role")_$(img_upper "$port")"
  tried=$base
  if [ -n "$index" ]; then
    value="$(img_var "${base}_$index")" || return "$IMG_EXIT_USAGE"
    tried="${base}_$index or $base"
  fi
  if [ -z "$value" ]; then
    value="$(img_var "$base")" || return "$IMG_EXIT_USAGE"
  fi
  if [ -z "$value" ]; then
    img_error "the port map holds no $tried: the deployment must publish this port"
    return "$IMG_EXIT_NO_OBJECT"
  fi
  if ! [[ $value =~ $IMG_RE_INT ]] || [ "$value" -lt 1 ] || [ "$value" -gt 65535 ]; then
    img_error "$tried holds '$value', which is not a port number"
    return "$IMG_EXIT_USAGE"
  fi
  printf '%s\n' "$((10#$value))"
}

## @description  Resolves the probe target address for a role instance (role, index), returning the
##               loopback address for the local instance or the peer hostname <role>-<index>.
## @return       Writes the target address to stdout and returns exit code 0.
img_host() {
  if [ "$1" = "${PX_ROLE:-}" ] && [ "$2" = "${PX_INSTANCE_INDEX:-0}" ]; then
    printf '127.0.0.1\n'
  else
    printf '%s-%s\n' "$1" "$2"
  fi
}

# ---------------------------------------------------------------------------
# Java runtime selection and JVM argument construction configure module access, memory bounds,
# garbage collection logging, and remote debugging.
# ---------------------------------------------------------------------------

## @description  Reads the JDK feature release for this role from PX_JDK, defaulting to 17.
## @return       Writes 11, 17, or 21 to stdout and returns exit code 0, or returns exit code 2 for
##               any other value.
img_jdk() {
  local jdk=${PX_JDK:-$IMG_JDK_DEFAULT} known
  for known in $IMG_JDKS; do
    if [ "$jdk" = "$known" ]; then
      printf '%s\n' "$jdk"
      return 0
    fi
  done
  img_error "PX_JDK=$jdk is not supported. A role runs on JDK 11, 17 or 21"
  return "$IMG_EXIT_USAGE"
}

# Emits each flag as a single whitespace-free token because upstream service launch scripts split
# their JVM option variables on spaces.
## @description  Generates the project's JPMS module and reflection flags for a given JDK release.
## @return       Writes the space-separated options to stdout and returns exit code 0.
img_jdk_module_opts() {
  local jdk=$1 opts pkg
  opts="-Dorg.apache.hbase.thirdparty.io.netty.tryReflectionSetAccessible=true"
  opts="$opts --add-modules=jdk.unsupported"
  for pkg in java.nio sun.nio.ch java.lang jdk.internal.ref java.lang.reflect java.util \
    java.util.concurrent; do
    opts="$opts --add-opens=java.base/$pkg=ALL-UNNAMED"
  done
  opts="$opts --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
  opts="$opts --add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
  if [ "$jdk" -ge 17 ]; then
    opts="$opts --add-opens=java.base/jdk.internal.util.random=ALL-UNNAMED"
  fi
  if [ "$jdk" -ge 21 ]; then
    opts="$opts -Djava.security.manager=allow"
  fi
  printf '%s\n' "$opts"
}

# Combines a fixed-size G1 heap, rotated garbage collection logs, exit on out-of-memory
# (ExitOnOutOfMemoryError), optional JDWP agent binding when debugging is enabled, and trailing
# overrides from PX_JVM_OPTS_EXTRA.
## @description  Assembles the complete JVM command-line options for a role instance (role, index).
## @return       Writes the options to stdout and returns exit code 0, or returns exit code 2 when
##               the JDK version, memory sizing, or debug port configuration is invalid.
img_jvm_opts() {
  local role=$1 index=$2 jdk heap direct mem opts host port
  jdk="$(img_jdk)" || return "$IMG_EXIT_USAGE"
  heap=${PX_HEAP_MIB:-}
  direct=${PX_DIRECT_MIB:-0}
  mem=${PX_MEM_MIB:-}
  if ! [[ $heap =~ $IMG_RE_INT ]] || [ "$heap" -lt 32 ]; then
    img_error "PX_HEAP_MIB='$heap' for $role: the heap must be a whole number of MiB, 32 or more"
    return "$IMG_EXIT_USAGE"
  fi
  if ! [[ $direct =~ $IMG_RE_INT ]]; then
    img_error "PX_DIRECT_MIB='$direct' for $role is not a whole number of MiB"
    return "$IMG_EXIT_USAGE"
  fi
  if [ -n "$mem" ]; then
    if ! [[ $mem =~ $IMG_RE_INT ]]; then
      img_error "PX_MEM_MIB='$mem' for $role is not a whole number of MiB"
      return "$IMG_EXIT_USAGE"
    fi
    if [ $((heap + direct)) -ge "$mem" ]; then
      img_error "$role: heap $heap MiB plus direct $direct MiB does not fit" \
        "below the container limit of $mem MiB"
      return "$IMG_EXIT_USAGE"
    fi
  fi
  opts="-Xms${heap}m -Xmx${heap}m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
  if [ "$direct" -gt 0 ]; then
    opts="$opts -XX:MaxDirectMemorySize=${direct}m"
  fi
  # Unified logging selectors intentionally omit wildcard asterisks because upstream launch scripts
  # expand JVM option variables unquoted, where a glob character could match files in the working
  # directory.
  opts="$opts -Xlog:gc,gc+cpu,safepoint:file=$PX_LOG_DIR/gc-$role-$index.log"
  opts="$opts:time,uptime,level,tags:filecount=5,filesize=20m"
  opts="$opts -XX:ErrorFile=$PX_LOG_DIR/hs_err-$role-$index-pid%p.log"
  opts="$opts -Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=$PX_DATA_DIR/tmp"
  opts="$opts $(img_jdk_module_opts "$jdk")"
  if img_is_true "${PX_JVM_DEBUG:-0}"; then
    host=${PX_BIND_HOST:-}
    if [ -z "$host" ]; then
      img_error "PX_BIND_HOST is unset: the deployment must name the bind address"
      return "$IMG_EXIT_USAGE"
    fi
    port="$(img_port "$role" debug "$index")" || return "$IMG_EXIT_USAGE"
    opts="$opts -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$host:$port"
  fi
  if [ -n "${PX_JVM_OPTS_EXTRA:-}" ]; then
    opts="$opts $PX_JVM_OPTS_EXTRA"
  fi
  printf '%s\n' "$opts"
}

# ---------------------------------------------------------------------------
# Configuration template selection and derived rendering variables prepare role-specific service
# configurations.
# ---------------------------------------------------------------------------

# Restricts rendering to the configuration files that a role's processes actually load so that
# deployments running without YARN never require YARN port definitions.
## @description  Lists the configuration template output files required by a given role.
## @return       Writes one output filename per line and exits with 0, or exits with 2 for an
##               unknown role.
img_role_templates() {
  local hadoop="core-site.xml hdfs-site.xml log4j.properties"
  local hbase="core-site.xml hdfs-site.xml hbase-site.xml log4j2.properties"
  local yarn="yarn-site.xml mapred-site.xml"
  local list
  case "$1" in
  zookeeper) list="zoo.cfg log4j2.properties" ;;
  namenode | secondarynamenode | datanode) list=$hadoop ;;
  resourcemanager | nodemanager | jobhistory) list="$hadoop $yarn" ;;
  hbase-master | regionserver) list=$hbase ;;
  client | tooling | setup)
    list="$hbase log4j.properties phoenix-client.properties"
    if img_is_true "${PX_YARN:-0}"; then
      list="$list $yarn"
    fi
    ;;
  *)
    img_error "no template set for the role '$1'"
    return "$IMG_EXIT_USAGE"
    ;;
  esac
  printf '%s\n' "$list" | tr ' ' '\n'
}

# Exports PX_RENDER_<TARGET>_<PORT>_PORT variables for a target role (target role, current role,
# current index, port names...), resolving the current instance index when the target role matches
# the current role and defaulting to instance 0 when referencing another role.
## @description  Exports rendered port variables for the requested port names of a target role.
## @return       Returns exit code 0 on success, exit code 2 when a value is not a valid port
##               number, or exit code 6 when a requested port is absent from the port map.
img_render_ports() {
  local target=$1 own=0 port value
  if [ "$2" = "$1" ]; then own=$3; fi
  shift 3
  for port in "$@"; do
    value="$(img_port "$target" "$port" "$own")" || return $?
    export "PX_RENDER_$(img_upper "$target")_$(img_upper "$port")_PORT=$value"
  done
}

# Computes and exports the template-specific PX_RENDER_* environment variables, including instance
# ports, the clamped HDFS replication factor, MapReduce container settings, and the log file path.
## @description  Derives the PX_RENDER_* variables for one template (file name, role, index).
## @return       Returns exit code 0 on success, or reports any absent or invalid input on stderr
##               and returns exit code 2.
img_derive_for() {
  local name=$1 role=$2 index=$3 dn repl mr opts
  case "$name" in
  hdfs-site.xml)
    dn="$(img_count PX_DN_COUNT)" || return "$IMG_EXIT_USAGE"
    repl=${PX_HDFS_REPLICATION:-}
    if ! [[ $repl =~ $IMG_RE_INT ]] || [ "$repl" -lt 1 ]; then
      img_error "PX_HDFS_REPLICATION='$repl': hdfs-site.xml needs a replication of 1 or more"
      return "$IMG_EXIT_USAGE"
    fi
    repl=$((10#$repl))
    # HDFS block placement cannot satisfy a replication factor that exceeds the total number of
    # configured DataNodes.
    if [ "$repl" -gt "$dn" ]; then
      img_error "PX_HDFS_REPLICATION=$repl exceeds PX_DN_COUNT=$dn: the generator clamps it"
      return "$IMG_EXIT_USAGE"
    fi
    export PX_RENDER_HDFS_REPLICATION="$repl"
    img_render_ports datanode "$role" "$index" data ipc http || return "$IMG_EXIT_USAGE"
    # Only the Secondary NameNode binds the checkpoint HTTP address directly; when a deployment
    # omits the Secondary NameNode role, all other roles render port 0 as a placeholder.
    if [ "$role" = secondarynamenode ] ||
      [ -n "$(img_var PX_PORT_SECONDARYNAMENODE_HTTP)" ]; then
      img_render_ports secondarynamenode "$role" "" http || return "$IMG_EXIT_USAGE"
    else
      export PX_RENDER_SECONDARYNAMENODE_HTTP_PORT=0
    fi
    ;;
  hbase-site.xml)
    img_count PX_RS_COUNT >/dev/null || return "$IMG_EXIT_USAGE"
    img_render_ports regionserver "$role" "$index" rpc http || return "$IMG_EXIT_USAGE"
    ;;
  yarn-site.xml | mapred-site.xml)
    img_render_ports nodemanager "$role" "$index" localizer rpc http shuffle ||
      return "$IMG_EXIT_USAGE"
    if [ "$name" = mapred-site.xml ]; then
      img_derive_task_heap MR_AM PX_MR_AM_MIB || return "$IMG_EXIT_USAGE"
      img_derive_task_heap MR_TASK PX_MR_TASK_MIB || return "$IMG_EXIT_USAGE"
      mr="$PX_HADOOP_HOME/share/hadoop/mapreduce"
      export PX_RENDER_MR_CLASSPATH="$mr/*,$mr/lib/*"
      # The JDK 11 module flag set forms the common subset accepted by JDK 11, JDK 17, and JDK 21.
      opts="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError $(img_jdk_module_opts 11)"
      export PX_RENDER_MR_JAVA_OPTS="$opts"
    fi
    ;;
  log4j.properties | log4j2.properties)
    export PX_RENDER_LOG_FILE="$PX_LOG_DIR/$role-$index.log"
    ;;
  esac
  return 0
}

# Allocates four fifths of the YARN container memory budget to the Java heap so that off-heap JVM
# overhead fits comfortably within the container memory limit.
## @description  Exports PX_RENDER_<PREFIX>_HEAP_MIB from a container size (prefix, variable name).
## @return       Returns exit code 0, or returns exit code 2 when the container size is absent or
##               below 128 MiB.
img_derive_task_heap() {
  local value
  value="$(img_var "$2")" || return "$IMG_EXIT_USAGE"
  if ! [[ $value =~ $IMG_RE_INT ]] || [ "$value" -lt 128 ]; then
    img_error "$2='$value': mapred-site.xml needs a container size of 128 MiB or more"
    return "$IMG_EXIT_USAGE"
  fi
  export "PX_RENDER_$1_HEAP_MIB=$((10#$value * 4 / 5))"
}

# Non-daemon roles without a persistent server endpoint—specifically client, tooling, and setup—use
# a readiness marker file in PX_CONF_DIR to signal startup completion to the container healthcheck.
## @description  Prints the path to the startup readiness marker file for serverless roles.
## @return       Writes the path to stdout and returns exit code 0, or returns exit code 2 when
##               PX_CONF_DIR is unset.
img_marker() {
  if [ -z "${PX_CONF_DIR:-}" ]; then
    img_error "PX_CONF_DIR is unset: the image defines it"
    return "$IMG_EXIT_USAGE"
  fi
  printf '%s\n' "$PX_CONF_DIR/.px-ready"
}

# ---------------------------------------------------------------------------
# Health and readiness probes query service management endpoints, JMX beans, and marker files.
# ---------------------------------------------------------------------------

# Management endpoints reside on the loopback address or the private harness network, so every HTTP
# request bypasses all ambient proxy environment variables.
## @description  Fetches the response body from a role management HTTP URL.
## @return       Writes the response body to stdout and returns exit code 0 on HTTP success, or
##               propagates the curl exit status on failure.
img_http_get() {
  curl -fsS --noproxy '*' --max-time "$IMG_HTTP_TIMEOUT_S" "$1" 2>/dev/null
}

# Hadoop and HBase management servers emit each property key at most once per queried JMX bean, so
# the first regular-expression match yields the authoritative value.
## @description  Extracts the scalar value for a property key from a JSON payload (body, key).
## @return       Writes the unquoted value and returns exit code 0, or returns exit code 1 when the
##               key is absent.
img_json_value() {
  local body=$1 key=$2 re
  re="\"$key\"[[:space:]]*:[[:space:]]*(\"([^\"]*)\"|[^,}[:space:]]+)"
  if [[ $body =~ $re ]]; then
    case "${BASH_REMATCH[1]}" in
    \"*) printf '%s\n' "${BASH_REMATCH[2]}" ;;
    *) printf '%s\n' "${BASH_REMATCH[1]}" ;;
    esac
    return 0
  fi
  return 1
}

## @description  Sends a four-letter command (host, port, word) to a ZooKeeper client TCP port.
## @return       Writes the first response line and exits with 0, or exits with 1 when the port
##               refuses the connection.
img_tcp_ask() {
  local host=$1 port=$2 word=$3
  (
    exec 3<>"/dev/tcp/$host/$port" || exit 1
    printf '%s' "$word" >&3
    reply=""
    IFS= read -r -t "$IMG_HTTP_TIMEOUT_S" reply <&3 || true
    printf '%s\n' "$reply"
  ) 2>/dev/null
}

# Queries an HTTP resource path—such as /jmx?qry=<bean> for a JMX MBean—on the web server of the
# specified role instance and stores the response payload in IMG_BODY.
## @description  Fetches a management HTTP path (role, index, path) into IMG_BODY.
## @return       Populates IMG_REASON on failure and returns exit code 0 on success, exit code 1
##               when the endpoint does not answer, or exit code 2 when the HTTP port is unmapped.
img_fetch() {
  local host port
  host="$(img_host "$1" "$2")"
  port="$(img_port "$1" http "$2")" || {
    IMG_REASON="the port map holds no http port for $1 $2"
    return "$IMG_EXIT_USAGE"
  }
  if ! IMG_BODY="$(img_http_get "http://$host:$port$3")"; then
    IMG_REASON="$1 $2 does not answer on $host:$port"
    return "$IMG_EXIT_FAIL"
  fi
  return 0
}

## @description  Verifies that a JSON key in IMG_BODY matches an expected value (key, value,
##               failure reason).
## @return       Updates IMG_REASON and returns exit code 0 on a match, or exit code 1 otherwise.
img_body_expect() {
  local value
  value="$(img_json_value "$IMG_BODY" "$1")" || value="<absent>"
  if [ "$value" = "$2" ]; then
    IMG_REASON="$1 is $2"
    return 0
  fi
  IMG_REASON="$3 ($1 is $value)"
  return "$IMG_EXIT_FAIL"
}

## @description  Verifies that a JSON key in IMG_BODY contains a non-empty value (key, reason).
## @return       Returns exit code 0 when a non-empty value is present, or sets IMG_REASON and
##               returns exit code 1 otherwise.
img_body_has() {
  [ -z "$(img_json_value "$IMG_BODY" "$1" || true)" ] || return 0
  IMG_REASON=$2
  return "$IMG_EXIT_FAIL"
}

# Formats a status summary of the form "<count> of <wanted> <noun>" in IMG_REASON and compares the
# integer metric extracted from IMG_BODY against the required minimum count.
## @description  Checks that a count key in IMG_BODY meets a minimum threshold (key, wanted, noun).
## @return       Sets IMG_REASON and returns exit code 0 when the count is reached, or returns exit
##               code 1 otherwise.
img_body_atleast() {
  local have
  have="$(img_json_value "$IMG_BODY" "$1")" || have=""
  IMG_REASON="${have:-no} of $2 $3"
  [[ $have =~ $IMG_RE_INT ]] && [ "$have" -ge "$2" ] && return 0
  return "$IMG_EXIT_FAIL"
}

# Evaluates HBase Master health in either live mode (process responds over HTTP) or ready mode
# (master is active, every configured RegionServer has checked in, and master initialization has
# completed).
## @description  Probes the HBase Master state for the requested readiness mode (live or ready).
## @return       Sets IMG_REASON and returns exit code 0 when healthy, exit code 1 when not yet
##               ready, or exit code 2 when the HTTP port or PX_RS_COUNT is invalid.
img_check_master() {
  local want init
  img_fetch hbase-master 0 '/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server' ||
    return $?
  if [ "$1" = live ]; then
    IMG_REASON="the master answers"
    return 0
  fi
  want="$(img_count PX_RS_COUNT)" || {
    IMG_REASON="PX_RS_COUNT is not a valid RegionServer count"
    return "$IMG_EXIT_USAGE"
  }
  img_body_expect tag.isActiveMaster true "the master is not active" || return $?
  img_body_atleast numRegionServers "$want" "RegionServers checked in" || return $?
  # The HBase Master reports itself as active before waiting for RegionServer registrations and
  # assigning hbase:meta. Its masterFinishedInitializationTime gauge remains 0 until initialization
  # completes, and the master rejects DDL requests until that timestamp becomes positive.
  init="$(img_json_value "$IMG_BODY" masterFinishedInitializationTime)" || init=""
  if ! [[ $init =~ ^[1-9][0-9]*$ ]]; then
    IMG_REASON="the master has not finished its initialization ($IMG_REASON)"
    return "$IMG_EXIT_FAIL"
  fi
  IMG_REASON="the master is initialized and $IMG_REASON"
}

# Verifies cluster-wide HDFS readiness for the bootstrap probe by confirming that the NameNode has
# exited safe mode and that all configured DataNodes are registered and live.
## @description  Checks whether HDFS has left safe mode with all configured DataNodes live.
## @return       Sets IMG_REASON and returns exit code 0 when ready, exit code 1 when not ready, or
##               exit code 2 when the NameNode HTTP port or PX_DN_COUNT is invalid.
img_check_hdfs() {
  local want
  img_fetch namenode 0 '/jmx?qry=Hadoop:service=NameNode,name=FSNamesystemState' ||
    return $?
  want="$(img_count PX_DN_COUNT)" || {
    IMG_REASON="PX_DN_COUNT is not a valid DataNode count"
    return "$IMG_EXIT_USAGE"
  }
  img_body_expect FSState Operational "the NameNode is in safe mode" || return $?
  img_body_atleast NumLiveDataNodes "$want" "DataNodes are live"
}

# Verifies cluster-wide YARN readiness for the bootstrap probe by confirming that the
# ResourceManager has started, every configured NodeManager is active, and the JobHistoryServer
# answers requests.
## @description  Checks YARN readiness across ResourceManager, NodeManagers, and JobHistoryServer.
## @return       Sets IMG_REASON and returns exit code 0 when ready, exit code 1 when not ready, or
##               exit code 2 when a required HTTP port or PX_NM_COUNT is invalid.
img_check_yarn() {
  local want nodes
  want="$(img_count PX_NM_COUNT)" || {
    IMG_REASON="PX_NM_COUNT is not a valid NodeManager count"
    return "$IMG_EXIT_USAGE"
  }
  img_check resourcemanager 0 ready >/dev/null || return $?
  img_fetch resourcemanager 0 /ws/v1/cluster/metrics || return $?
  img_body_atleast activeNodes "$want" "NodeManagers are active" || return $?
  nodes=$IMG_REASON
  img_check jobhistory 0 ready >/dev/null || return $?
  IMG_REASON="$nodes, and the JobHistoryServer answers"
}

# Dispatches a health probe for a single role instance (role, index, mode live or ready). In live
# mode the daemon need only answer its management port, whereas in ready mode it must be actively
# serving its cluster function; serverless roles instead require their startup marker file.
## @description  Probes the liveness or readiness of a single role instance (role, index, mode).
## @return       Writes the diagnostic reason to stdout and returns exit code 0 when healthy, exit
##               code 1 when unhealthy, or exit code 2 when a required port or count is missing.
img_check() {
  local role=$1 index=$2 mode=$3 status=0 marker host port reply
  IMG_REASON=""
  case "$role" in
  zookeeper)
    host="$(img_host zookeeper 0)"
    if ! port="$(img_port zookeeper client)"; then
      IMG_REASON="the port map holds no ZooKeeper client port"
      status=$IMG_EXIT_USAGE
    else
      reply="$(img_tcp_ask "$host" "$port" ruok)" || reply=""
      if [ "$reply" = imok ]; then
        IMG_REASON="ZooKeeper answers imok"
      else
        IMG_REASON="ZooKeeper does not answer ruok on $host:$port"
        status=$IMG_EXIT_FAIL
      fi
    fi
    ;;
  namenode)
    img_fetch namenode 0 '/jmx?qry=Hadoop:service=NameNode,name=NameNodeStatus' &&
      img_body_expect State active "the NameNode is not active" || status=$?
    ;;
  secondarynamenode)
    img_fetch secondarynamenode 0 \
      '/jmx?qry=Hadoop:service=SecondaryNameNode,name=SecondaryNameNodeInfo' &&
      img_body_has HostAndPort "the Secondary NameNode reports no address" || status=$?
    ;;
  datanode)
    if img_fetch datanode "$index" '/jmx?qry=Hadoop:service=DataNode,name=DataNodeInfo'; then
      if [ "$mode" = ready ]; then
        case "$IMG_BODY" in
        *BP-*) IMG_REASON="the DataNode serves a block pool" ;;
        *)
          IMG_REASON="the DataNode has not registered with the NameNode"
          status=$IMG_EXIT_FAIL
          ;;
        esac
      fi
    else
      status=$?
    fi
    ;;
  resourcemanager)
    img_fetch resourcemanager 0 /ws/v1/cluster/info &&
      img_body_expect state STARTED "the ResourceManager is not started" || status=$?
    ;;
  nodemanager)
    img_fetch nodemanager "$index" /ws/v1/node/info &&
      img_body_expect nodeHealthy true "the NodeManager is not healthy" || status=$?
    ;;
  jobhistory)
    img_fetch jobhistory 0 /ws/v1/history/info &&
      img_body_has startedOn "the JobHistoryServer reports no start time" || status=$?
    ;;
  hbase-master)
    img_check_master "$mode" || status=$?
    ;;
  regionserver)
    img_fetch regionserver "$index" '/jmx?qry=Hadoop:service=HBase,name=RegionServer,sub=Server' &&
      { [ "$mode" = live ] ||
        img_body_has tag.clusterId "the RegionServer has not joined the cluster"; } ||
      status=$?
    ;;
  client | tooling | setup)
    if ! marker="$(img_marker)"; then
      IMG_REASON="PX_CONF_DIR is unset"
      status=$IMG_EXIT_USAGE
    elif [ ! -f "$marker" ]; then
      IMG_REASON="the $role role has not finished its start"
      status=$IMG_EXIT_FAIL
    fi
    ;;
  esac
  if [ "$status" -eq 0 ] && [ -z "$IMG_REASON" ]; then
    IMG_REASON="$role $index answers"
  fi
  printf '%s\n' "$IMG_REASON"
  return "$status"
}

# ---------------------------------------------------------------------------
# Cross-platform filesystem helpers abstract differences between Linux containers and macOS hosts.
# ---------------------------------------------------------------------------

# Queries file modification time using GNU stat inside Linux containers and falls back to BSD stat
# when the self-test suite runs on macOS.
## @description  Reads the last-modified timestamp of a file in epoch seconds.
## @return       Writes epoch seconds to stdout and exits with 0, or returns exit code 1 when the
##               file is absent.
img_mtime() {
  stat -c %Y "$1" 2>/dev/null || stat -f %m "$1" 2>/dev/null
}

# ---------------------------------------------------------------------------
# Cluster-level dependency checks and bounded polling loops synchronize service startup.
# ---------------------------------------------------------------------------
# All services in a deployment launch concurrently because the container orchestration layer does
# not enforce a static startup dependency graph. Any role that depends on an upstream service
# blocks here under a single finite deadline before starting its own daemon, and the external
# bootstrap probe reuses the exact same verification routines.

# Checks for the marker file that the setup role writes after successfully formatting the NameNode
# metadata directory.
## @description  Checks whether the setup role has formatted the NameNode metadata directory.
## @return       Sets IMG_REASON and returns exit code 0 when formatted, exit code 1 when not
##               formatted, or exit code 2 when PX_DATA_DIR is unset.
img_check_formatted() {
  if [ -z "${PX_DATA_DIR:-}" ]; then
    IMG_REASON="PX_DATA_DIR is unset"
    return "$IMG_EXIT_USAGE"
  fi
  if [ -f "$PX_DATA_DIR/$IMG_FORMATTED" ]; then
    IMG_REASON="the NameNode metadata directory holds a namespace"
    return 0
  fi
  IMG_REASON="the NameNode metadata directory is not formatted; the setup role formats it"
  return "$IMG_EXIT_FAIL"
}

# Iterates over every configured RegionServer instance individually so that any instance that has
# not yet joined the cluster is identified by its specific index in IMG_REASON.
## @description  Verifies that every configured RegionServer instance has joined the cluster.
## @return       Sets IMG_REASON and exits with 0 when all instances have joined, exit code 1 when
##               any instance has not joined, or exit code 2 when configuration is invalid.
img_check_regionservers() {
  local count index=0 status=0
  count="$(img_count PX_RS_COUNT)" || {
    IMG_REASON="PX_RS_COUNT is not a valid RegionServer count"
    return "$IMG_EXIT_USAGE"
  }
  while [ "$index" -lt "$count" ]; do
    img_check regionserver "$index" ready >/dev/null || status=$?
    if [ "$status" -ne 0 ]; then
      IMG_REASON="regionserver $index: $IMG_REASON"
      return "$status"
    fi
    index=$((index + 1))
  done
  IMG_REASON="$count of $count RegionServers joined the cluster"
  return 0
}

# Validates that every heartbeat file listed in PX_MONITOR_HEARTBEATS is younger than
# PX_MONITOR_STALE_S seconds, which defaults to 90 seconds (three intervals of a 30-second
# monitor).
## @description  Verifies that all heartbeat files in PX_MONITOR_HEARTBEATS are present and fresh.
## @return       Sets IMG_REASON and exits with 0 when all are fresh, exits with 1 when any file is
##               absent or stale, or exits with 2 for an invalid staleness limit.
img_check_monitors() {
  local stale=${PX_MONITOR_STALE_S:-90} file mtime now count=0 status=0
  if ! [[ $stale =~ $IMG_RE_INT ]]; then
    IMG_REASON="PX_MONITOR_STALE_S=$stale is not a whole number of seconds"
    return "$IMG_EXIT_USAGE"
  fi
  now="$(date +%s)"
  set -f
  for file in ${PX_MONITOR_HEARTBEATS:-}; do
    if ! mtime="$(img_mtime "$file")"; then
      IMG_REASON="the monitor heartbeat $file does not exist"
      status=$IMG_EXIT_FAIL
      break
    fi
    if [ $((now - mtime)) -gt "$stale" ]; then
      IMG_REASON="the monitor heartbeat $file is older than $stale seconds"
      status=$IMG_EXIT_FAIL
      break
    fi
    count=$((count + 1))
  done
  set +f
  if [ "$status" -eq 0 ]; then
    IMG_REASON="$count monitor heartbeats are fresh"
  fi
  return "$status"
}

# Performs a single non-blocking readiness check against a named wait target: zookeeper, namenode,
# namenode-format, hdfs, hbase-master, regionservers, resourcemanager, yarn, or monitors.
## @description  Evaluates readiness once for a named wait subject.
## @return       Sets IMG_REASON and returns exit code 0 when ready, exit code 1 when not ready, or
##               exit code 2 for an unknown subject or invalid environment configuration.
img_check_subject() {
  case "$1" in
  zookeeper | namenode | hbase-master | resourcemanager)
    img_check "$1" 0 ready >/dev/null
    ;;
  namenode-format) img_check_formatted ;;
  hdfs) img_check_hdfs ;;
  regionservers) img_check_regionservers ;;
  yarn) img_check_yarn ;;
  monitors) img_check_monitors ;;
  *)
    IMG_REASON="unknown wait subject $1"
    return "$IMG_EXIT_USAGE"
    ;;
  esac
}

# Polls a wait subject (subject, deadline expressed as a shell SECONDS value) at intervals of
# PX_POLL_INTERVAL_S seconds (defaulting to 2 seconds) until the check succeeds or the deadline is
# reached.
## @description  Polls a wait subject (subject, deadline in SECONDS) until ready or timed out.
## @return       Sets IMG_REASON and returns exit code 0 when ready, exit code 2 for an invalid
##               subject, environment, or poll interval, or exit code 4 when the deadline expires.
img_wait_for() {
  local subject=$1 deadline=$2 interval=${PX_POLL_INTERVAL_S:-2} status
  if ! [[ $interval =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    IMG_REASON="PX_POLL_INTERVAL_S=$interval is not a number of seconds"
    return "$IMG_EXIT_USAGE"
  fi
  while :; do
    status=0
    img_check_subject "$subject" || status=$?
    case "$status" in
    0) return 0 ;;
    "$IMG_EXIT_USAGE") return "$IMG_EXIT_USAGE" ;;
    esac
    if [ "$SECONDS" -ge "$deadline" ]; then
      IMG_REASON="not ready before the deadline: $IMG_REASON"
      return "$IMG_EXIT_TIMEOUT"
    fi
    sleep "$interval"
  done
}

# Converts the relative readiness timeout in PX_TIMEOUT_READY (defaulting to 600 seconds) into an
# absolute deadline measured against the shell's built-in SECONDS timer.
## @description  Computes the readiness deadline in SECONDS from PX_TIMEOUT_READY (default 600s).
## @return       Writes the deadline as a SECONDS value and returns exit code 0, or returns exit
##               code 2 when the timeout value is invalid.
img_deadline() {
  local timeout=${PX_TIMEOUT_READY:-600}
  if ! [[ $timeout =~ $IMG_RE_INT ]] || [ "$timeout" -lt 1 ]; then
    img_error "PX_TIMEOUT_READY=$timeout is not whole seconds above zero"
    return "$IMG_EXIT_USAGE"
  fi
  printf '%s\n' "$((SECONDS + timeout))"
}
