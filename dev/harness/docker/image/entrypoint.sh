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

# Container entrypoint for the unified multi-role image. A single image serves every cluster role,
# and each running instance determines its assigned role and numeric index from the PX_ROLE and
# PX_INSTANCE_INDEX environment variables injected by the deployment.
#
# Startup executes in five sequential stages, and a failure in any stage halts the instance before
# the role daemon starts:
#   1. Resolve the instance identity and select the target JDK.
#   2. Compute the derived values required by the configuration templates, such as the clamped
#      replication factor and the port numbers assigned to this instance index.
#   3. Render the configuration template set for the role using render.sh, which rejects any unset
#      variable or any unexpanded placeholder that remains in the output.
#   4. Wait, up to a finite deadline, for all prerequisite roles required by this role at startup.
#      Because the deployment launches all services concurrently, startup ordering is enforced here.
#   5. Replace this shell with the role daemon running in the foreground. The daemon receives the
#      stop signal directly, and its exit status becomes the exit status of the instance. Because
#      the deployment disables automatic restarts, an instance that exits remains stopped so that
#      cluster checks can observe the failure.
#
# Role categories:
#   - Daemon roles (zookeeper, namenode, secondarynamenode, datanode, resourcemanager,
#     nodemanager, jobhistory, hbase-master, and regionserver) run their respective cluster
#     service processes.
#   - Long-lived roles (client and tooling) render the client configuration, write the ready
#     marker, and wait for the stop signal so that drivers and collectors can execute inside them
#     through the substrate contract.
#   - The one-shot role (setup) formats the NameNode metadata directory when no previous format
#     has completed, and then executes the bootstrap probe. The deployment mounts the NameNode
#     volume into this role for formatting. Its healthcheck remains unready until the probe writes
#     the ready marker, and the role exits with status 0 on success.
#
# See lib/image.sh for the complete environment contract.

IMG_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/lib/image.sh
. "$IMG_HOME/lib/image.sh"

set -euo pipefail

EP_RENDER_ONLY=""

## @description  Prints the command-line usage and exit code documentation.
## @return       Writes the usage message to standard output and returns exit code 0.
usage() {
  cat <<'EOF'
Usage: entrypoint.sh [--render-only DIR]

Starts the role that PX_ROLE names, at the index PX_INSTANCE_INDEX. With
--render-only it renders the configuration and the JVM options of the role
into DIR and exits, which is how the self-test and a diagnosis read what an
instance would run.

Exit codes: the status of the daemon once it runs; before that 0 rendered,
1 a launch failure, 2 an invalid environment or an incomplete configuration,
4 a role it needs was not ready before PX_TIMEOUT_READY.
EOF
}

# Computes the derived values required by each template in the role's template set and renders all
# templates into the target directory, applying PX_CONF_OVERLAY_DIR last when configured.
## @description  Renders the role configuration template set into the specified output directory.
## @return       Returns 0 on success, 1 if a file cannot be written, or 2 if a value is missing
##               or an unexpanded placeholder remains.
ep_render() {
  local out=$1 name
  local -a templates=()
  local -a overlay=()
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    img_derive_for "$name" "$PX_ROLE" "$PX_INSTANCE_INDEX" || return "$IMG_EXIT_USAGE"
    templates+=("$IMG_HOME/conf/$name.tmpl")
  done <<EOF
$(img_role_templates "$PX_ROLE")
EOF
  if [ -n "${PX_CONF_OVERLAY_DIR:-}" ]; then
    overlay=(--overlay "$PX_CONF_OVERLAY_DIR")
  fi
  "$IMG_HOME/render.sh" -o "$out" ${overlay[@]+"${overlay[@]}"} "${templates[@]}"
}

# Writes each JVM flag to jvm.opts in the target directory, one option per line, so that drivers
# launching a JVM inside a client role can read the file to apply the role's sizing configuration.
## @description  Writes the resolved JVM options (target directory, options string) to jvm.opts.
## @return       Returns exit code 0 on success, or exit code 1 when the file cannot be written.
ep_write_jvm_opts() {
  local opt status=0
  : >"$1/jvm.opts" || return "$IMG_EXIT_FAIL"
  # Split the options on whitespace with globbing disabled so arguments never match filenames.
  set -f
  for opt in $2; do
    printf '%s\n' "$opt" >>"$1/jvm.opts" || status=$IMG_EXIT_FAIL
  done
  set +f
  return "$status"
}

## @description  Verifies that a required launcher binary is present and executable in the image.
## @return       Returns 0 when the path is executable, or terminates the process with exit code 1.
ep_need() {
  [ -x "$1" ] || img_die "$IMG_EXIT_FAIL" "$1 is missing: the image was built without it"
}

# Places the resolved JVM options into HADOOP_OPTS and unsets all Hadoop heap size variables so
# that the upstream launch scripts do not inject additional heap options.
## @description  Exports the environment variables required by the Hadoop launch scripts.
## @return       Returns exit code 0.
ep_env_hadoop() {
  export HADOOP_HOME="$PX_HADOOP_HOME"
  export HADOOP_MAPRED_HOME="$PX_HADOOP_HOME"
  export HADOOP_CONF_DIR="$PX_CONF_DIR"
  export YARN_CONF_DIR="$PX_CONF_DIR"
  export HADOOP_LOG_DIR="$PX_LOG_DIR"
  export HADOOP_PID_DIR="$PX_DATA_DIR/pid"
  export HADOOP_OPTS="$1"
  unset HADOOP_HEAPSIZE_MAX HADOOP_HEAPSIZE_MIN HADOOP_HEAPSIZE
}

# Because the HBase distribution bundles its own Hadoop client JARs, external Hadoop classpath
# discovery is disabled while the Phoenix server JARs are added to HBASE_CLASSPATH.
## @description  Exports the HBase launch script environment configured with Phoenix server JARs.
## @return       Returns exit code 0.
ep_env_hbase() {
  local jar cp=""
  for jar in "$PX_PHOENIX_HOME"/server/*.jar; do
    [ -f "$jar" ] || continue
    cp="${cp:+$cp:}$jar"
  done
  export HBASE_HOME="$PX_HBASE_HOME"
  export HBASE_CONF_DIR="$PX_CONF_DIR"
  export HBASE_LOG_DIR="$PX_LOG_DIR"
  export HBASE_PID_DIR="$PX_DATA_DIR/pid"
  export HBASE_CLASSPATH="$cp"
  export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true
  export HBASE_MANAGES_ZK=false
  export HBASE_OPTS="$1 -Dlog4j2.configurationFile=file:$PX_CONF_DIR/log4j2.properties"
  unset HBASE_HEAPSIZE HBASE_OFFHEAPSIZE
}

## @description  Creates the readiness marker file checked by health probes for server-less roles.
## @return       Returns exit code 0 on success, or exit code 1 when the marker cannot be written.
ep_mark_ready() {
  local marker
  marker="$(img_marker)" || return "$IMG_EXIT_FAIL"
  : >"$marker" || return "$IMG_EXIT_FAIL"
}

# Formats the NameNode metadata directory on the mounted NameNode volume unless the format marker
# indicates a prior successful format, removing any partial state from an earlier attempt first.
## @description  Initializes and formats the NameNode metadata directory when not yet formatted.
## @return       Returns 0 when formatted now or previously, 1 if formatting fails, or 2 when
##               PX_CLUSTER_ID is unset.
ep_hdfs_init() {
  local cluster=${PX_CLUSTER_ID:-}
  if [ -f "$PX_DATA_DIR/$IMG_FORMATTED" ]; then
    img_info "the NameNode metadata directory is formatted already"
    return 0
  fi
  if [ -z "$cluster" ]; then
    img_error "PX_CLUSTER_ID is unset: the HDFS cluster id is derived from it"
    return "$IMG_EXIT_USAGE"
  fi
  # If the completion marker is absent, no previous format succeeded; any existing directory
  # contents do not represent a valid namespace and would cause a non-interactive format to fail.
  rm -rf "$PX_DATA_DIR/namenode" || return "$IMG_EXIT_FAIL"
  img_info "formatting the NameNode metadata directory for cluster $cluster"
  "$PX_HADOOP_HOME/bin/hdfs" namenode -format -nonInteractive -clusterId "px-$cluster" ||
    return "$IMG_EXIT_FAIL"
  : >"$PX_DATA_DIR/$IMG_FORMATTED" || return "$IMG_EXIT_FAIL"
}

# Blocks under a single shared deadline of PX_TIMEOUT_READY seconds until all prerequisite roles
# required at startup become ready; daemons that retry connections autonomously require no gate.
## @description  Waits for the prerequisite dependencies of the current role before launch.
## @return       Returns 0 when ready, or exits the process with 2 for an incomplete environment
##               or 4 when the PX_TIMEOUT_READY deadline expires.
ep_gate() {
  local subjects="" subject deadline
  case "$PX_ROLE" in
  namenode) subjects="namenode-format" ;;
  secondarynamenode) subjects="namenode" ;;
  hbase-master | regionserver) subjects="zookeeper hdfs" ;;
  jobhistory) subjects="hdfs" ;;
  nodemanager) subjects="resourcemanager" ;;
  esac
  [ -n "$subjects" ] || return 0
  deadline="$(img_deadline)" || exit "$IMG_EXIT_USAGE"
  for subject in $subjects; do
    img_info "waiting for $subject before the $PX_ROLE daemon starts"
    img_wait_for "$subject" "$deadline" ||
      img_die "$?" "$PX_ROLE does not start: $subject: $IMG_REASON"
    img_info "$subject: $IMG_REASON"
  done
}

# Configures the role runtime environment with the supplied JVM options and execs the target
# role program in place of the current shell process.
## @description  Replaces this shell with the daemon or program of the role (JVM options).
## @return       Never returns; execs the role program, or exits with 1 (missing launcher or
##               failed format) or 2 (unset PX_CLUSTER_ID).
ep_launch() {
  local opts=$1 bin="$PX_HADOOP_HOME/bin" env=hadoop
  case "$PX_ROLE" in
  client | tooling)
    ep_env_hadoop "$opts"
    ep_env_hbase "$opts"
    ep_mark_ready || img_die "$IMG_EXIT_FAIL" "cannot write the ready marker"
    img_info "the $PX_ROLE role is ready and waits for the stop signal"
    exec tail -f /dev/null
    ;;
  setup)
    # Format the NameNode metadata directory first because the NameNode startup gate waits for it,
    # then run the bootstrap probe to wait for every other role and write the readiness marker.
    ep_need "$bin/hdfs"
    ep_env_hadoop "$opts"
    ep_env_hbase "$opts"
    ep_hdfs_init || img_die "$?" "the NameNode metadata directory could not be formatted"
    exec "$IMG_HOME/bin/px-bootstrap-probe"
    ;;
  # Launch ZooKeeper using the JARs bundled with the HBase distribution so that the server and
  # every HBase client run the exact same ZooKeeper release.
  zookeeper)
    env=hbase
    set -- "$PX_HBASE_HOME/bin/hbase" org.apache.zookeeper.server.quorum.QuorumPeerMain \
      "$PX_CONF_DIR/zoo.cfg"
    ;;
  namenode | secondarynamenode | datanode) set -- "$bin/hdfs" "$PX_ROLE" ;;
  resourcemanager | nodemanager) set -- "$bin/yarn" "$PX_ROLE" ;;
  jobhistory) set -- "$bin/mapred" historyserver ;;
  hbase-master) env=hbase && set -- "$PX_HBASE_HOME/bin/hbase" master start ;;
  regionserver) env=hbase && set -- "$PX_HBASE_HOME/bin/hbase" regionserver start ;;
  esac
  ep_need "$1"
  "ep_env_$env" "$opts"
  exec "$@"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
  -h | --help)
    usage
    exit 0
    ;;
  --render-only)
    [ "$#" -ge 2 ] || img_die "$IMG_EXIT_USAGE" "--render-only needs a directory"
    EP_RENDER_ONLY=$2
    shift
    ;;
  *) img_die "$IMG_EXIT_USAGE" "unknown argument: $1" ;;
  esac
  shift
done

img_identity || exit "$IMG_EXIT_USAGE"
PX_JDK="$(img_jdk)" || exit "$IMG_EXIT_USAGE"
export PX_JDK
EP_REQUIRED="PX_DATA_DIR PX_LOG_DIR PX_HADOOP_HOME"
if [ -z "$EP_RENDER_ONLY" ]; then
  EP_REQUIRED="$EP_REQUIRED PX_CONF_DIR PX_JDK_ROOT PX_HBASE_HOME PX_PHOENIX_HOME"
fi
for required in $EP_REQUIRED; do
  if [ -z "$(img_var "$required")" ]; then
    img_die "$IMG_EXIT_USAGE" "$required is unset: the image defines it"
  fi
done
EP_OPTS="$(img_jvm_opts "$PX_ROLE" "$PX_INSTANCE_INDEX")" || exit "$IMG_EXIT_USAGE"

EP_CONF_OUT=${EP_RENDER_ONLY:-$PX_CONF_DIR}
ep_render "$EP_CONF_OUT" || img_die "$?" "the configuration of $PX_ROLE $PX_INSTANCE_INDEX" \
  "did not render, so the instance does not start"
ep_write_jvm_opts "$EP_CONF_OUT" "$EP_OPTS" ||
  img_die "$IMG_EXIT_FAIL" "cannot write $EP_CONF_OUT/jvm.opts"
img_info "rendered the configuration of $PX_ROLE $PX_INSTANCE_INDEX on JDK $PX_JDK" \
  "into $EP_CONF_OUT"
if [ -n "$EP_RENDER_ONLY" ]; then
  exit 0
fi

export JAVA_HOME="$PX_JDK_ROOT/$PX_JDK"
ep_need "$JAVA_HOME/bin/java"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$PX_DATA_DIR/tmp" "$PX_DATA_DIR/pid" "$PX_LOG_DIR" ||
  img_die "$IMG_EXIT_FAIL" "cannot make the data and log directories"
ep_gate
ep_launch "$EP_OPTS"
