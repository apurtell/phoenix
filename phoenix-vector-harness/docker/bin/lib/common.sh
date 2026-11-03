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
# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR/../..

# This file is the shared library of the host scripts in bin/.
# It loads the settings, finds the Docker CLI and wraps docker compose.
# It also maps a compose service to its role, its container and its JVM.
# The file runs on macOS bash 3.2, so it uses no associative arrays.
# The port table and the role table come from image/lib, so the host and the image share one source.

if [ -n "${VH_COMMON_LOADED:-}" ]; then
  return 0
fi
VH_COMMON_LOADED=1

VH_PROG="$(basename "$0")"

vh_log() { printf '%s: %s\n' "$VH_PROG" "$*" >&2; }
vh_warn() { printf '%s: WARN: %s\n' "$VH_PROG" "$*" >&2; }
vh_die() {
  printf '%s: ERROR: %s\n' "$VH_PROG" "$*" >&2
  exit 1
}

# The framework root is two levels above this file. The repository root is two levels above that.
VH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VH_REPO="$(cd "$VH_ROOT/../.." && pwd)"
export VH_ROOT VH_REPO

# Settings. env/local.env comes first, so its values win over the defaults.
# Every value is exported, because docker compose reads the values from its environment.
set -a
if [ -f "$VH_ROOT/env/local.env" ]; then
  # shellcheck source=/dev/null
  . "$VH_ROOT/env/local.env"
fi
# shellcheck source=env/defaults.env
. "$VH_ROOT/env/defaults.env"
set +a

# The port table. On the host no role is active, so the variables hold the ports of instance 1.
unset VH_ROLE VH_INDEX
# shellcheck source=image/lib/ports.sh
. "$VH_ROOT/image/lib/ports.sh"

# The number of region servers. It is a copy, because a subshell sources the port table again.
VH_RS_COUNT="$VH_GROUP_SIZE"

# The user that owns every daemon JVM in the image. An attach must use the same user.
VH_EXEC_USER="${VH_EXEC_USER:-vh}"

# Service names. The daemon list is in start order.
VH_DAEMON_SERVICES="$(vh_ports_instances)"
VH_ONESHOT_SERVICES="hdfs-init cluster-ready"
VH_CLIENT_SERVICES="harness tools"
VH_ALL_SERVICES="$VH_DAEMON_SERVICES $VH_ONESHOT_SERVICES $VH_CLIENT_SERVICES"

# Docker CLI. Docker Desktop does not always put its CLI on the PATH.
# The compose plugin and the credential helpers live in the same directory, so it goes on the PATH.
vh_find_docker() {
  if [ -n "${VH_DOCKER:-}" ] && [ -x "$VH_DOCKER" ]; then
    :
  elif command -v docker >/dev/null 2>&1; then
    VH_DOCKER="$(command -v docker)"
  elif [ -x "$HOME/.docker/bin/docker" ]; then
    VH_DOCKER="$HOME/.docker/bin/docker"
  else
    vh_die "the docker CLI is not on the PATH and not at $HOME/.docker/bin/docker"
  fi
  PATH="$(dirname "$VH_DOCKER"):$PATH"
  export VH_DOCKER PATH
}
vh_find_docker

vh_docker() { "$VH_DOCKER" "$@"; }

# Every compose call uses the same file, project directory and project name.
vh_compose() {
  "$VH_DOCKER" compose --project-directory "$VH_ROOT" -f "$VH_ROOT/compose.yaml" \
    -p "$VH_PROJECT" "$@"
}

# Prints "-T" when stdin or stdout is not a terminal, so compose run allocates no TTY.
vh_tty_flag() {
  if [ -t 0 ] && [ -t 1 ]; then
    echo ""
  else
    echo "-T"
  fi
}

# Runs one command in a new tools container. The cluster must already be up.
vh_run_tools() {
  local tty
  tty="$(vh_tty_flag)"
  if [ -n "$tty" ]; then
    vh_compose run --rm --no-deps "$tty" tools "$@"
  else
    vh_compose run --rm --no-deps tools "$@"
  fi
}

vh_ts() { date -u +%Y%m%dT%H%M%SZ; }

# Creates the host directories of the bind mounts, so Docker does not create them.
vh_prepare_dirs() {
  mkdir -p "$VH_WORK/logs" "$VH_WORK/out" "$VH_WORK/prof" "$VH_WORK/profiles" "$VH_DATA"
}

vh_require_image() {
  vh_docker image inspect "$VH_IMAGE:$VH_IMAGE_TAG" >/dev/null 2>&1 ||
    vh_die "image $VH_IMAGE:$VH_IMAGE_TAG not found; run $VH_ROOT/build.sh first"
}

# Returns success if the word is in the space-separated list.
vh_in_list() {
  case " $2 " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

vh_is_daemon() { vh_in_list "$1" "$VH_DAEMON_SERVICES"; }
vh_is_service() { vh_in_list "$1" "$VH_ALL_SERVICES"; }

# Prints the role of a service name.
vh_svc_role() {
  case "$1" in
    dn[1-9] | nm[1-9] | rs[1-9]) echo "${1%?}" ;;
    *) echo "$1" ;;
  esac
}

# Prints the instance index of a service name. A role without instances has index 1.
vh_svc_index() {
  case "$1" in
    dn[1-9] | nm[1-9] | rs[1-9]) echo "${1#??}" ;;
    *) echo 1 ;;
  esac
}

# Prints one variable of the role environment, as the entrypoint of that role sees it.
# The role files are sourced in a subshell, so the host environment does not change.
vh_role_value() {
  local role="$1" idx="$2" var="$3"
  (
    VH_ROLE="$role"
    VH_INDEX="$idx"
    # shellcheck source=image/lib/ports.sh
    . "$VH_ROOT/image/lib/ports.sh" >/dev/null
    # shellcheck source=image/lib/roles.sh
    . "$VH_ROOT/image/lib/roles.sh" >/dev/null
    eval "printf '%s' \"\${$var:-}\""
  )
}

# Prints the services of a group, or the service itself. Fails for an unknown name.
vh_group_members() {
  case "$1" in
    all | cluster) echo "$VH_DAEMON_SERVICES" ;;
    hdfs) echo "nn snn dn1 dn2 dn3" ;;
    yarn) echo "rm nm1 nm2 nm3 jhs" ;;
    hbase) echo "master rs1 rs2 rs3" ;;
    dn) echo "dn1 dn2 dn3" ;;
    nm) echo "nm1 nm2 nm3" ;;
    rs) echo "rs1 rs2 rs3" ;;
    *)
      if vh_is_daemon "$1"; then
        echo "$1"
      else
        return 1
      fi
      ;;
  esac
}

# Prints the ids of the containers of a service. With "-a", stopped containers are included.
# One-off containers of compose run carry the same labels, so a harness run is found too.
vh_svc_cids() {
  local all="" svc="$1"
  if [ "$svc" = "-a" ]; then
    all="-a"
    svc="$2"
  fi
  if [ -n "$all" ]; then
    vh_docker ps -a -q --filter "label=com.docker.compose.project=$VH_PROJECT" \
      --filter "label=com.docker.compose.service=$svc"
  else
    vh_docker ps -q --filter "label=com.docker.compose.project=$VH_PROJECT" \
      --filter "label=com.docker.compose.service=$svc"
  fi
}

# Prints the id of the one running container of a target.
# A target is a compose service name, or a container name or id.
vh_cid() {
  local target="$1" cids n
  if vh_is_service "$target"; then
    cids="$(vh_svc_cids "$target")"
  else
    cids="$(vh_docker ps -q --filter "id=$target" 2>/dev/null)"
    if [ -z "$cids" ]; then
      cids="$(vh_docker ps -q --filter "name=^/?${target}\$" 2>/dev/null)"
    fi
  fi
  n="$(printf '%s\n' "$cids" | awk 'NF' | wc -l | tr -d ' ')"
  if [ "$n" = 0 ]; then
    vh_warn "no running container for '$target'"
    return 1
  fi
  if [ "$n" != 1 ]; then
    vh_warn "'$target' matches $n running containers; name one container instead:"
    vh_docker ps --format '  {{.Names}}' --filter "label=com.docker.compose.service=$target" >&2
    return 1
  fi
  printf '%s\n' "$cids"
}

# Prints "role index" of a container from the labels that compose.yaml sets.
vh_cid_role() {
  vh_docker inspect -f \
    '{{index .Config.Labels "org.apache.phoenix.vh.role"}} {{index .Config.Labels "org.apache.phoenix.vh.index"}}' \
    "$1"
}

# Prints the compose service name of a container.
vh_cid_service() {
  vh_docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$1"
}

# Prints the container name without the leading slash.
vh_cid_name() {
  vh_docker inspect -f '{{.Name}}' "$1" | sed 's#^/##'
}

# Prints "status health restarts oomkilled exitcode" of a container.
vh_cid_state() {
  vh_docker inspect -f \
    '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}} {{.RestartCount}} {{.State.OOMKilled}} {{.State.ExitCode}}' \
    "$1"
}

# Prints the pid of the role JVM in a container. The lowest match is the daemon itself.
# The pattern names the JVM, so a shell or a tool in the same container does not match.
# The init process has pid 1 and is never the JVM.
vh_jvm_pid() {
  local cid="$1" pattern="$2" pid
  pid="$(vh_docker exec -u "$VH_EXEC_USER" "$cid" pgrep -f "java.*$pattern" 2>/dev/null |
    awk '$1 != 1' | head -n 1 || true)"
  if [ -z "$pid" ]; then
    vh_warn "no JVM that matches '$pattern' in container $(vh_cid_name "$cid")"
    return 1
  fi
  printf '%s\n' "$pid"
}

# Resolves a target into the globals VH_T_CID, VH_T_ROLE, VH_T_INDEX, VH_T_INST and VH_T_PID.
# The calling scripts read these globals, so shellcheck cannot see their use here.
# shellcheck disable=SC2034
vh_resolve_jvm() {
  local target="$1" ri
  VH_T_CID="$(vh_cid "$target")" || return 1
  ri="$(vh_cid_role "$VH_T_CID")"
  VH_T_ROLE="${ri% *}"
  VH_T_INDEX="${ri#* }"
  if [ -z "$VH_T_ROLE" ]; then
    vh_warn "container '$target' has no vector harness role label"
    return 1
  fi
  VH_T_INST="$(vh_role_value "$VH_T_ROLE" "$VH_T_INDEX" VH_INST)"
  local pattern
  pattern="$(vh_role_value "$VH_T_ROLE" "$VH_T_INDEX" VH_PROC_PATTERN)"
  if [ -z "$pattern" ]; then
    vh_warn "role $VH_T_ROLE has no long-running JVM"
    return 1
  fi
  VH_T_PID="$(vh_jvm_pid "$VH_T_CID" "$pattern")" || return 1
}

# Maps a container path on a bind mount to the host path. Other paths stay as they are.
vh_host_path() {
  case "$1" in
    /var/log/vh/*) echo "$VH_WORK/logs/${1#/var/log/vh/}" ;;
    /work/out/*) echo "$VH_WORK/out/${1#/work/out/}" ;;
    /work/prof/*) echo "$VH_WORK/prof/${1#/work/prof/}" ;;
    *) echo "container:$1" ;;
  esac
}

# Waits until every listed service has a running and healthy container.
# Fails at the timeout, or as soon as a container exits and has no restart policy.
vh_wait_healthy() {
  local timeout="$1" svc cid st pending policy
  shift
  local deadline=$((SECONDS + timeout))
  while :; do
    pending=""
    for svc in "$@"; do
      cid="$(vh_svc_cids -a "$svc" | head -n 1)"
      if [ -z "$cid" ]; then
        pending="$pending $svc"
        continue
      fi
      st="$(vh_cid_state "$cid")"
      case "$st" in
        "running healthy "*) ;;
        exited* | dead*)
          policy="$(vh_docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$cid")"
          if [ "$policy" != always ]; then
            vh_warn "$svc stopped: $st"
            return 1
          fi
          pending="$pending $svc"
          ;;
        *) pending="$pending $svc" ;;
      esac
    done
    if [ -z "$pending" ]; then
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      vh_warn "not healthy after ${timeout}s:$pending"
      return 1
    fi
    sleep 2
  done
}

# Prints the number of live region servers that the active master reports, or -1.
vh_live_rs() {
  local cid out
  cid="$(vh_svc_cids master | head -n 1)"
  if [ -z "$cid" ]; then
    echo -1
    return 0
  fi
  out="$(vh_docker exec "$cid" curl -fsS --max-time 10 \
    "http://localhost:$VH_MASTER_INFO/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server" \
    2>/dev/null || true)"
  out="$(printf '%s\n' "$out" | sed -n 's/.*"numRegionServers"[^0-9]*\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  echo "${out:--1}"
}

# Waits until the master reports the given number of region servers, by default the full set.
vh_wait_live_rs() {
  local timeout="$1" want="${2:-$VH_RS_COUNT}" n
  local deadline=$((SECONDS + timeout))
  while :; do
    n="$(vh_live_rs)"
    if [ "$n" = "$want" ]; then
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      vh_warn "the master reports $n live region servers after ${timeout}s, expected $want"
      return 1
    fi
    sleep 2
  done
}
