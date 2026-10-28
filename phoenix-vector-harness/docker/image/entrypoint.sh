#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script is the entrypoint of every container. VH_ROLE selects what the container runs.
# The script sets the role environment and creates the role directories.
# It renders the configuration at each start, so a restart always uses fresh files.
# A daemon replaces this script with exec and runs in the foreground.
# The daemon then gets the stop signal directly, and its exit code becomes the container exit code.
#
# Roles and their commands:
#   zk, nn, snn, dn, rm, nm, jhs, master, rs   the daemon of the role
#   hdfs-init, cluster-ready                   the one-shot setup and readiness scripts
#   harness                                    the harness launcher; see run_harness
#   tools                                      the given command, or a shell
# A daemon role with arguments runs the arguments instead of the daemon, for diagnosis.

set -euo pipefail
umask 022

# shellcheck source=SCRIPTDIR/lib/ports.sh
. /opt/vh/lib/ports.sh
# shellcheck source=SCRIPTDIR/lib/roles.sh
. /opt/vh/lib/roles.sh
# shellcheck source=SCRIPTDIR/lib/render.sh
. /opt/vh/lib/render.sh

log() { echo "entrypoint[$VH_INST]: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# Creates the role directories and makes sure that this user can write to them.
# A named volume that Docker creates without the image directory belongs to root.
prepare_dirs() {
  local d
  # VH_ROLE_DIRS is a list of paths with spaces between them.
  # shellcheck disable=SC2086
  mkdir -p $VH_ROLE_DIRS || die "cannot create the role directories: $VH_ROLE_DIRS"
  for d in $VH_ROLE_DIRS; do
    [ -w "$d" ] || die "$d is not writable by $(id -un) ($(id -u)); check the volume owner"
  done
}

# Removes the pid files of this instance from an earlier run.
# Only foreground daemons write them, and the previous process is gone after a restart.
clean_pids() {
  rm -f "$VH_PID_DIR"/hadoop-"$VH_INST"-*.pid "$VH_PID_DIR"/hbase-"$VH_INST"-*.pid
}

# Formats the name node only once. A VERSION file shows that a namespace exists.
format_namenode_once() {
  if [ -f "$VH_NN_DIR/current/VERSION" ]; then
    log "name node is already formatted"
    return 0
  fi
  log "formatting the name node with cluster ID $VH_CLUSTER_ID"
  hdfs namenode -format -nonInteractive -clusterid "$VH_CLUSTER_ID" \
    || die "name node format failed; if $VH_NN_DIR has old content, remove the volume"
  clean_pids
}

# Starts a daemon in the foreground. The log directory is the working directory,
# so JVM crash files go there too.
run_daemon() {
  cd "$VH_LOG_DIR"
  log "starting: $* (JAVA_HOME=$JAVA_HOME)"
  exec "$@"
}

# Runs the harness. The MapReduce client jars that the harness lib lacks go onto the classpath.
# A harness phase or an option goes to the launcher. Any other command runs as it is.
# With no arguments, the launcher prints its help.
run_harness() {
  local mr
  mr="$(vh-mr-classpath)" || die "cannot build the MapReduce client classpath"
  # The join leaves out an empty part, because Java reads an empty entry as the work directory.
  local cp="${HARNESS_CLASSPATH:-}"
  if [ -n "$mr" ]; then
    cp="${cp:+$cp:}$mr"
  fi
  HARNESS_CLASSPATH="$cp"
  export HARNESS_CLASSPATH
  if [ "$#" -eq 0 ]; then
    set -- --help
  fi
  case "$1" in
    load|build-index|truth|run|report|-*)
      exec "$VH_HARNESS_HOME/bin/vector-harness" "$@"
      ;;
    *)
      exec "$@"
      ;;
  esac
}

# Runs a command in the tools container. With no command, an interactive session gets a shell.
# A session without a terminal waits, so "docker exec" can run commands in it.
run_tools() {
  if [ "$#" -gt 0 ]; then
    exec "$@"
  fi
  if [ -t 0 ]; then
    exec bash -l
  fi
  log "no command; waiting for docker exec"
  exec sleep infinity
}

main() {
  log "role $VH_ROLE, kind $VH_ROLE_KIND"
  prepare_dirs
  clean_pids
  vh_render || die "configuration render failed"

  if [ "$VH_ROLE_KIND" = daemon ] && [ "$#" -gt 0 ]; then
    log "running the given command instead of the daemon"
    exec "$@"
  fi

  case "$VH_ROLE" in
    zk) run_daemon hbase zookeeper ;;
    nn)
      format_namenode_once
      run_daemon hdfs namenode
      ;;
    snn) run_daemon hdfs secondarynamenode ;;
    dn) run_daemon hdfs datanode ;;
    rm) run_daemon yarn resourcemanager ;;
    nm) run_daemon yarn nodemanager ;;
    jhs) run_daemon mapred historyserver ;;
    master) run_daemon hbase master start ;;
    rs) run_daemon hbase regionserver start ;;
    hdfs-init) exec vh-hdfs-init "$@" ;;
    cluster-ready) exec vh-cluster-ready "$@" ;;
    harness) run_harness "$@" ;;
    tools) run_tools "$@" ;;
    *) die "unknown VH_ROLE '$VH_ROLE'" ;;
  esac
}

main "$@"
