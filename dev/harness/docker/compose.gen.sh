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
#
# Setting PX_PHERF=1 adds the Distributed Pherf tier as the on-demand role group pherf, comprising
# one controller and PX_PHERF_AGENT_COUNT agents running on the PX_PHERF_IMAGE_TAG image. When
# PX_PHERF_TARGET=harness, the tier joins the harness deployment and shares its network and its
# ZooKeeper service. When PX_PHERF_TARGET=external, the rendered deployment contains the tier
# alone, and the agents read the target ZooKeeper quorum exclusively from PX_PHERF_ZOOKEEPER; no
# other source supplies a quorum.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"
# The backend contract defines the cluster name grammar; sourcing it neither binds a backend nor
# contacts a container runtime.
# shellcheck source=SCRIPTDIR/../lib/backend/contract.sh
. "$SELF/../lib/backend/contract.sh"
# The plug-in loader executes the render hooks, including conf-overlay and jvm-opts, without
# contacting any container runtime.
# shellcheck source=SCRIPTDIR/../lib/plugin.sh
. "$SELF/../lib/plugin.sh"
# The shared generator kit provides the role table, the configuration value checks, the plug-in
# render hooks, the topology record, the staging step, and the fail-closed validator. The manifest
# generator for the Kubernetes backend uses this same kit.
# shellcheck source=SCRIPTDIR/../lib/deploy.sh
. "$SELF/../lib/deploy.sh"

set -euo pipefail

# Constants shared between the container image and the deployment generator. Every service attaches
# to the harness bridge network with <role>-<index> as both its hostname and its service name. Each
# daemon binds to every interface within its own container, while the deployment publishes each port
# from the port map exclusively on the host loopback address using identical container and host port
# numbers. The image executes its healthcheck at this path, and stateful roles persist their data at
# the volume mount point.
PXG_PUBLISH_HOST=127.0.0.1
PXG_HEALTHCHECK=/opt/px/healthcheck.sh
PXG_DATA_DIR=/data
# Read-only container mount point where the setup role mounts the host state directory so the
# bootstrap probe can inspect the health monitor heartbeat.
PXG_STATE_MOUNT=/var/lib/px/state
# Every service instance mounts the merged plug-in overlay templates at this read-only container
# path.
PXG_OVERLAY_MOUNT=/opt/px/conf-overlay

# Constants for the Distributed Pherf tier defined by the docker/pherf image, covering the health
# probe script, the controller run directory, and the agent communication channel port. Because
# agents reach the channel exclusively over the internal bridge network, the port map omits it just
# as it omits internal MapReduce task ports. Each JVM claims a fixed percentage of its container
# memory limit: the controller takes half (50 percent), and each agent takes 35 percent because the
# agent daemon and one Pherf child process share the container memory limit.
PXG_PHERF_HEALTHCHECK=/opt/pherf/healthcheck.sh
PXG_PHERF_RUN_DIR=/var/lib/pherf/run
PXG_PHERF_AGENT_PORT=8790
PXG_PHERF_CONTROLLER_PCT=50
PXG_PHERF_AGENT_PCT=35
# Maximum number of Distributed Pherf agents permitted in the validation run class.
PXG_PHERF_VALIDATION_AGENTS=2

# Header marker written as the first line of topology.env. A target directory whose topology.env
# begins with this marker is recognized as a rendered deployment that the generator may safely
# replace.
PXG_MARKER="# Rendered by compose.gen.sh. Render again instead of editing."

# Validation grammar for the ZooKeeper quorum string when the tier targets an external cluster.
PXG_QUORUM_RE='^[A-Za-z0-9._:,/-]+$'

# Per-render output buffers and state accumulators populated during a single rendering pass.
PXG_YAML=""
PXG_VOLUMES=""
PXG_PORTS=""
PXG_PUBLISHED=""
PXG_QUORUM=""
PXG_STATE_HOST=""
PXG_HEARTBEAT=""

px_cli_spec <<'EOF'
about Renders the Compose deployment directory of one cluster under $PX_OUT, by
+ default $PX_OUT/deploy/<cluster>.
about It writes files only and never calls a container runtime.
about The run class is required: --class, PX_RUN_CLASS in the environment, or
+ PX_RUN_CLASS in env/local.env beside this command. PX_PHERF=1 adds the
+ Distributed Pherf tier; PX_PHERF_TARGET=external renders the tier alone, for the
+ ZooKeeper quorum of PX_PHERF_ZOOKEEPER. env/override-24gib.env beside this
+ command fits a measurement-shaped run into a 24 GiB engine. The scaled roles are
+ datanode, nodemanager, regionserver and pherf-agent.
exits 1 also means that the directory cannot be written, 2 an unset or invalid value
+ or a target directory that is not a rendered deployment, 6 a --config file is
+ absent.
example compose.gen.sh --class measurement --config env/override-24gib.env
EOF

## @description  Resolves the health monitor heartbeat path in the state directory mounted by the
##               setup service for the bootstrap probe.
## @return       Exits 0, 2 on a refused value, or 1 when the state directory cannot be resolved.
pxg_configure_state() {
  local name
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

## @description  Validates the Distributed Pherf tier configuration, sets PXG_QUORUM, and appends
##               the two tier roles to PXG_RENDERED.
## @return       Exits 0 when valid, or exits 2 on any rejected value.
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
  # The ZooKeeper quorum for an external target cluster must be supplied explicitly by the
  # operator, whereas the harness target resolves the deployment's own ZooKeeper instance and
  # client port.
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
  # The staging record of the Pherf image supplies the ZooKeeper version for an external
  # deployment, whereas the staging record of the harness image supplies it for a harness
  # deployment.
  pxg_staged PX_PHERF_IMAGE_TAG hbase_profile:PX_PHOENIX_HBASE_PROFILE \
    hbase_version:PX_DIST_HBASE_VERSION
  if [ "$PX_PHERF_TARGET" = harness ]; then
    PXG_ZOOKEEPER=$ver_zk
    PXG_HBASE_HADOOP=$ver_hadoop
  fi
  PXG_RENDERED="$PXG_RENDERED pherf-controller pherf-agent"
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
  for family in $(pxg_role_families "$role"); do
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
  local role=$1 index=$2 key service mem heap direct jdk group
  local prefix=$PX_LABEL_PREFIX var volume port opts
  key="$(pxg_role "$role" key)"
  service="$role-$index"
  var="PX_${key}_MEM_MIB" && mem=${!var}
  var="PX_${key}_HEAP_MIB" && heap=${!var}
  var="PX_${key}_DIRECT_MIB" && direct=${!var}
  var="PX_${key}_JDK" && jdk=${!var}
  group="$(pxg_role_group "$role")"
  volume="$(pxg_volume_of "$role" "$index")"
  opts="$(pxg_jvm_opts_of "$key")"

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
  if [ -n "$group" ]; then
    pxg_y "      $prefix.start: \"on-demand\""
  fi
  pxg_y "    env_file:"
  pxg_y "      - ports.generated.env"
  pxg_y "      - topology.env"
  pxg_y "    environment:"
  if [ -n "$PXG_OVERLAY_HOST" ]; then
    pxg_y "      PX_CONF_OVERLAY_DIR: \"$PXG_OVERLAY_MOUNT\""
  fi
  if [ -n "$volume" ]; then
    pxg_y "      PX_DATA_DIR: \"$PXG_DATA_DIR\""
  fi
  pxg_y "      PX_DIRECT_MIB: \"$direct\""
  pxg_y "      PX_HEAP_MIB: \"$heap\""
  pxg_y "      PX_HOSTNAME: \"$service\""
  pxg_y "      PX_INSTANCE_INDEX: \"$index\""
  pxg_y "      PX_JDK: \"$jdk\""
  # Role-specific JVM options and jvm-opts hook flags are appended last so that they follow the
  # baseline flags derived from the memory sizing parameters by the container image.
  if [ -n "$opts" ]; then
    pxg_y "      PX_JVM_OPTS_EXTRA: \"$opts\""
  fi
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
  if [ -n "$volume" ] || [ -n "$PXG_OVERLAY_HOST" ]; then
    pxg_y "    volumes:"
  fi
  if [ -n "$volume" ]; then
    pxg_y "      - $volume:$PXG_DATA_DIR"
    if [ "$role" = setup ]; then
      pxg_y "      - \"$PXG_STATE_HOST:$PXG_STATE_MOUNT:ro\""
    fi
  fi
  if [ -n "$PXG_OVERLAY_HOST" ]; then
    pxg_y "      - \"$PXG_OVERLAY_HOST:$PXG_OVERLAY_MOUNT:ro\""
  fi
  # Each persistent volume is declared only once, by the service instance that owns it.
  if [ "$volume" = "$service-data" ]; then
    PXG_VOLUMES="$PXG_VOLUMES  $volume:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES    labels:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES      $prefix.cluster: \"$PX_CLUSTER_ID\"$PX_NL"
  fi
  pxg_service_row "$role" "$index"
}

# The Distributed Pherf container image consumes PHERF_* environment variables exclusively.
## @description  Renders the Compose definition for one Distributed Pherf tier instance (role,
##               index), appends its service table row, and records its memory allocation.
## @return       Exits 0.
pxg_render_tier_service() {
  local role=$1 index=$2 service="$1-$2" prefix=$PX_LABEL_PREFIX var mem pct verb port
  var="PX_$(pxg_role "$role" key)_MEM_MIB" && mem=${!var}
  if [ "$role" = pherf-controller ]; then
    verb=controller pct=$PXG_PHERF_CONTROLLER_PCT
  else
    verb=agent pct=$PXG_PHERF_AGENT_PCT
  fi
  pxg_y "  $service:"
  pxg_y "    image: \"$PX_PHERF_IMAGE_TAG\""
  pxg_y "    pull_policy: never"
  pxg_y "    hostname: \"$service\""
  pxg_y "    networks:"
  pxg_y "      - default"
  pxg_y "    restart: \"no\""
  pxg_y "    mem_limit: ${mem}m"
  pxg_y "    memswap_limit: ${mem}m"
  pxg_y "    labels:"
  pxg_y "      $prefix.cluster: \"$PX_CLUSTER_ID\""
  pxg_y "      $prefix.group: \"pherf\""
  pxg_y "      $prefix.index: \"$index\""
  pxg_y "      $prefix.role: \"$role\""
  pxg_y "      $prefix.start: \"on-demand\""
  pxg_y "    command: [\"$verb\", \"start\"]"
  pxg_y "    environment:"
  pxg_render_ports "$role" "$index"
  if [ "$verb" = controller ]; then
    pxg_y "      PHERF_JAVA_OPTS: \"-XX:MaxRAMPercentage=$pct\""
    pxg_y "      PHERF_REST_PORT: \"${PXG_PUBLISHED# }\""
  else
    pxg_y "      PHERF_CONTROLLER: \"http://pherf-controller-0:$PXG_PHERF_AGENT_PORT\""
    pxg_y "      PHERF_JAVA_OPTS: \"-XX:MaxRAMPercentage=$pct\""
    pxg_y "      PHERF_ZOOKEEPER: \"$PXG_QUORUM\""
  fi
  # Use the same healthcheck probe command and timing thresholds defined in
  # docker/pherf/compose.yaml.
  pxg_y "    healthcheck:"
  pxg_y "      test: [\"CMD\", \"$PXG_PHERF_HEALTHCHECK\", \"$verb\"]"
  pxg_y "      interval: 5s"
  pxg_y "      timeout: 6s"
  pxg_y "      retries: 3"
  pxg_y "      start_period: 60s"
  if [ -n "$PXG_PUBLISHED" ]; then
    pxg_y "    ports:"
    for port in $PXG_PUBLISHED; do
      pxg_y "      - \"$PXG_PUBLISH_HOST:$port:$port\""
    done
  fi
  # The controller persists its test definitions and run bundles on a dedicated volume.
  if [ "$verb" = controller ]; then
    pxg_y "    volumes:"
    pxg_y "      - $service-data:$PXG_PHERF_RUN_DIR"
    PXG_VOLUMES="$PXG_VOLUMES  $service-data:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES    labels:$PX_NL"
    PXG_VOLUMES="$PXG_VOLUMES      $prefix.cluster: \"$PX_CLUSTER_ID\"$PX_NL"
  fi
  PXG_TSV="$PXG_TSV$(px_tsv_row "$service" "$role" "$index" pherf on-demand "$mem" \
    "$((mem * pct / 100))" 0 17)$PX_NL"
  PXG_SERVICES=$((PXG_SERVICES + 1))
  PXG_MEM_TOTAL=$((PXG_MEM_TOTAL + mem))
  PXG_MEM_PHERF=$((PXG_MEM_PHERF + mem))
}

# The self-test verifies that the maximum port map (nine instances of every scalable role) produces
# no port collisions.
## @description  Iterates over all active roles and renders every configured service instance.
## @return       Populates the render accumulators and exits 0.
pxg_build() {
  local role count index group
  PXG_YAML="" PXG_VOLUMES="" PXG_PORTS="" PXG_TSV="" PXG_YARN_ROLES="" PXG_PHERF_ROLES=""
  PXG_SERVICES=0 PXG_MEM_TOTAL=0 PXG_MEM_EAGER=0 PXG_MEM_YARN=0 PXG_MEM_PHERF=0
  for role in $PXG_RENDERED; do
    count="$(pxg_role_count "$role")"
    group="$(pxg_role_group "$role")"
    case "$group" in
    yarn) PXG_YARN_ROLES="${PXG_YARN_ROLES:+$PXG_YARN_ROLES }$role" ;;
    pherf) PXG_PHERF_ROLES="${PXG_PHERF_ROLES:+$PXG_PHERF_ROLES }$role" ;;
    esac
    index=0
    while [ "$index" -lt "$count" ]; do
      if [ "$group" = pherf ]; then
        pxg_render_tier_service "$role" "$index"
      else
        pxg_render_service "$role" "$index"
      fi
      index=$((index + 1))
    done
  done
}

## @description  Assembles the complete Compose file text for the rendered deployment.
## @return       Writes the Compose file text to stdout and exits with 0.
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

## @description  Writes the rendered deployment directory (target) via the staging directory.
## @return       Exits 0 on success, or exits 1 if a file write fails or a dollar sign survives.
pxg_write() {
  local target=$1 staging=$PXG_STAGING
  pxg_write_file "$staging/compose.yaml" "$(pxg_compose_text)$PX_NL"
  pxg_write_file "$staging/ports.generated.env" "$(pxg_ports_text)$PX_NL"
  pxg_write_file "$staging/topology.env" "$(pxg_topology_text)$PX_NL"
  pxg_write_file "$staging/services.tsv" \
    "# service role index group start mem_limit_mib heap_mib direct_mib jdk$PX_NL$PXG_TSV"
  # Because Compose interpolates dollar signs, any unexpanded placeholder would evaluate to an empty
  # value at provision time. This post-write check inspects the files as written on disk after every
  # configuration value has passed the allowed character set checks.
  pxg_validate "$staging" compose.yaml ports.generated.env topology.env services.tsv
  rm -rf "$target"
  mv "$staging" "$target" || px_die "$PX_EXIT_FAIL" "cannot move the render into $target"
}

## @description  Parses command-line flags, loads staged configuration, and renders the deployment.
## @return       Writes the deployment directory path (or the service table under --check) to stdout
##               and exits 0, or exits 1, 2, or 6 as documented in the usage text.
pxg_main() {
  local target
  pxg_cli "$@"
  pxg_load
  pxg_configure
  if [ "$PX_PHERF_TARGET" = harness ]; then
    pxg_configure_state
  fi
  pxg_prepare pxg_build deploy services
  target=$PXG_TARGET
  # Plug-in render hooks write their outputs directly into the staging directory so that their
  # generated records move atomically into the deployment directory alongside the rendered files.
  pxg_stage "$target"
  pxg_plugins "$PXG_STAGING" "$target"
  pxg_build
  pxg_write "$target"
  px_info "rendered $PXG_SERVICES services for cluster $PX_CLUSTER_ID, class" \
    "$PX_RUN_CLASS: $PXG_MEM_TOTAL MiB of container limits, $((PXG_MEM_YARN + PXG_MEM_PHERF))" \
    "MiB of them on demand, into $target"
  printf '%s\n' "$target"
}

pxg_main "$@"
