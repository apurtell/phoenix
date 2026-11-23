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

# Manages the lifecycle of a single Distributed Pherf process using the command syntax:
#   pherf-distributed.sh controller|agent start|stop|restart|status
#
# The start command executes in the foreground by recording its own process ID in the PID file
# before replacing the shell process with the JVM. As a result, JVM log output streams directly to
# standard output, operating system signals reach the JVM directly, and the exit status reflects
# the JVM exit status (0, 1, or 2, as specified in the module README). The JVM validates the
# remaining environment configuration. Because the script resolves conf/ and lib/ exclusively
# relative to its own directory, a standalone deployment image can contain only bin/, conf/, and
# lib/ without requiring a source tree.
#
# The controller stores its PID file at $PHERF_RUN_DIR/controller.pid, while an agent stores its
# PID file at $TMPDIR/pherf-agent-<id>.pid. The JVM receives $TMPDIR (defaulting to /tmp) as
# java.io.tmpdir, which places the agent work directory in that same location. The JVM command line
# includes -Dpherf.role.id=<id> for an agent or -Dpherf.role.id=<run dir> for the controller. A
# process is considered live when its PID responds to kill -0, does not match the current shell,
# and has a command line naming both the main class for the role and the corresponding role ID.
#
# The script returns exit status 0 on success, 1 when start detects an existing live process for
# the role or when stop must resort to KILL, 3 when status finds no live process, and 2 on a usage
# or configuration error.

set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTROLLER_MAIN=org.apache.phoenix.pherf.distributed.controller.ControllerMain
AGENT_MAIN=org.apache.phoenix.pherf.distributed.agent.AgentMain
# Defines the stop timeout in tenths of a second, accounting for the 5 s agent stop grace period,
# termination of its Pherf child process, and a safety margin.
STOP_WAIT_TENTHS=150
ID_RE='^[A-Za-z0-9._-]{1,63}$'

## @description  Writes the command usage synopsis to standard error.
## @return       Exits with status 2.
usage() {
  echo "usage: pherf-distributed.sh controller|agent start|stop|restart|status" >&2
  exit 2
}

## @description  Writes a configuration error message to standard error and terminates execution.
## @return       Exits with status 2.
die() {
  echo "pherf-distributed.sh: $*" >&2
  exit 2
}

# For an agent role, exports the resolved PHERF_AGENT_ID environment variable so that the JVM
# identifier matches the identifier used in the PID file path.
## @description  Resolves MAIN, ROLE_ID, TMP, and PID_FILE for the selected ROLE.
## @return       Returns 0 on success, or exits with status 2 if PHERF_RUN_DIR is unset or the
##               agent identifier is invalid.
resolve_role() {
  TMP="${TMPDIR:-/tmp}"
  if [[ $ROLE == controller ]]; then
    MAIN=$CONTROLLER_MAIN
    [[ -n ${PHERF_RUN_DIR:-} ]] || die "PHERF_RUN_DIR is not set"
    ROLE_ID="${PHERF_RUN_DIR%/}"
    PID_FILE="$PHERF_RUN_DIR/controller.pid"
  else
    MAIN=$AGENT_MAIN
    PHERF_AGENT_ID="${PHERF_AGENT_ID:-${HOSTNAME:-$(hostname)}}"
    [[ $PHERF_AGENT_ID =~ $ID_RE ]] || die "PHERF_AGENT_ID is not an id: $PHERF_AGENT_ID"
    export PHERF_AGENT_ID
    ROLE_ID=$PHERF_AGENT_ID
    PID_FILE="${TMP%/}/pherf-agent-$PHERF_AGENT_ID.pid"
  fi
}

## @description  Writes the process ID of the active process for the role to standard output.
## @return       Returns 0 when the role process is live, or 1 when it is not running.
live_pid() {
  local pid args
  [[ -f $PID_FILE ]] || return 1
  pid="$(head -n 1 "$PID_FILE" 2>/dev/null || true)"
  [[ $pid =~ ^[0-9]+$ && $pid != "$$" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  args="$(ps -ww -o args= -p "$pid" 2>/dev/null ||
    tr '\0' ' ' 2>/dev/null <"/proc/$pid/cmdline" || true)"
  [[ $args == *"$MAIN"* && $args == *" -Dpherf.role.id=$ROLE_ID -cp "* ]] || return 1
  echo "$pid"
}

## @description  Launches the configured role in the foreground by replacing the current shell.
## @return       Does not return because the JVM replaces the shell; exits with status 1 if a live
##               process for the role already exists, or 2 if java or required jars are missing.
start() {
  local pid java classpath jar found=""
  if pid="$(live_pid)"; then
    echo "pherf-distributed.sh: $ROLE already running as pid $pid" >&2
    exit 1
  fi
  if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    java="$JAVA_HOME/bin/java"
  elif ! java="$(command -v java)"; then
    die "no java: set JAVA_HOME or put java on PATH"
  fi
  if [[ -n ${PHERF_CLASSPATH:-} ]]; then
    classpath="$SELF/../conf:$PHERF_CLASSPATH"
  else
    for jar in "$SELF"/../lib/*.jar; do
      if [[ -f $jar ]]; then
        found=1
        break
      fi
    done
    [[ -n $found ]] || die "no jar in $SELF/../lib, and PHERF_CLASSPATH is not set"
    classpath="$SELF/../conf:$SELF/../lib/*"
  fi
  mkdir -p "$(dirname "$PID_FILE")"
  echo "$$" >"$PID_FILE"
  # Splits PHERF_JAVA_OPTS on whitespace with file globbing disabled so that later options override
  # earlier ones.
  set -f
  # shellcheck disable=SC2086 # PHERF_JAVA_OPTS holds several options.
  exec "$java" "-Djava.io.tmpdir=$TMP" ${PHERF_JAVA_OPTS:-} "-Dpherf.role.id=$ROLE_ID" \
    -cp "$classpath" "$MAIN" 2>&1
}

# Sends TERM to the live process for the role and waits for it to exit, escalating to KILL if the
# process outlives the wait period, and removes the PID file.
## @description  Stops the live process for the role and removes its PID file.
## @return       Returns 0 when the process stops or no live process is running (including when a
##               stale PID file is removed), or 1 if KILL was required.
stop() {
  local pid i
  if ! pid="$(live_pid)"; then
    rm -f "$PID_FILE"
    echo "$ROLE not running"
    return 0
  fi
  kill -TERM "$pid" 2>/dev/null || true
  for ((i = 0; i < STOP_WAIT_TENTHS; i++)); do
    if ! live_pid >/dev/null; then
      rm -f "$PID_FILE"
      echo "$ROLE pid $pid stopped"
      return 0
    fi
    sleep 0.1
  done
  kill -KILL "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "pherf-distributed.sh: $ROLE pid $pid outlived TERM; sent KILL" >&2
  return 1
}

## @description  Writes the current execution status of the role to standard output.
## @return       Returns 0 when a process for the role is live, or 3 when no process is running.
status() {
  local pid
  if pid="$(live_pid)"; then
    echo "$ROLE running as pid $pid"
    return 0
  fi
  echo "$ROLE not running"
  return 3
}

[[ $# -eq 2 ]] || usage
ROLE=$1
VERB=$2
case $ROLE in
  controller | agent) ;;
  *) usage ;;
esac
case $VERB in
  start | stop | restart | status) ;;
  *) usage ;;
esac
resolve_role
case $VERB in
  start) start ;;
  stop) stop ;;
  restart)
    stop || exit 1
    start
    ;;
  status) status ;;
esac
