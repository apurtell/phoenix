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

# This script checks the host and the Docker VM before a build or a cluster start.
# The cluster runs all daemons in one arm64 VM with host networking.
# The VM must have the memory, the processors and the disk space of the full topology.
# The script runs every check, prints each result, and exits with 1 if one check fails.
# A warning does not change the exit code.
#
# Usage: preflight.sh [--maven] [--perf]
#   --maven  also check the host build inputs: JDK 21, Maven and the private repository
#   --perf   require the larger free VM disk of the performance suite
#
# Environment:
#   VH_MIN_MEM_GIB, VH_MIN_CPUS, VH_MIN_DISK_GIB, VH_MIN_DISK_GIB_PERF  the limits
#   VH_MIN_HOST_DISK_GIB  free host disk for the build context and the cache; default 10
#   VH_PROBE_IMAGE        image of the probe containers; default the Ubuntu base image

set -euo pipefail

VH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VH_REPO="$(cd "$VH_ROOT/../.." && pwd)"
export VH_ROOT VH_REPO

# Loads the settings. env/local.env comes first, so its values override the defaults.
vh_load_env() {
  local f
  set -a
  for f in "$VH_ROOT/env/local.env" "$VH_ROOT/env/defaults.env" "$VH_ROOT/versions.env" \
    "$VH_ROOT/versions.local.env"; do
    if [ -f "$f" ]; then
      # shellcheck source=/dev/null
      . "$f"
    fi
  done
  set +a
}

log() { echo "preflight: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

FAILED=0
pass() { log "ok:   $*"; }
fail() { log "FAIL: $*"; FAILED=1; }
warn() { log "warn: $*"; }

# Finds the Docker CLI. Docker Desktop can install it outside PATH.
# Its directory also holds the credential helper, so the directory goes on PATH.
find_docker() {
  local d
  d="$(command -v docker 2>/dev/null || true)"
  if [ -z "$d" ] && [ -x "$HOME/.docker/bin/docker" ]; then
    d="$HOME/.docker/bin/docker"
  fi
  [ -n "$d" ] || return 1
  PATH="$(dirname "$d"):$PATH"
  export PATH
  DOCKER="$d"
}

# Prints the image reference of the probe containers. A pin adds the digest.
probe_image() {
  if [ -n "${VH_PROBE_IMAGE:-}" ]; then
    echo "$VH_PROBE_IMAGE"
  elif [ -n "${VH_BASE_UBUNTU_PIN:-}" ]; then
    echo "$VH_BASE_UBUNTU_IMAGE@$VH_BASE_UBUNTU_PIN"
  else
    echo "$VH_BASE_UBUNTU_IMAGE"
  fi
}

# Runs one short command in a probe container on the host network.
probe() {
  "$DOCKER" run --rm --platform "$VH_PLATFORM" --net=host --entrypoint /bin/sh "$PROBE" -c "$1"
}

check_engine() {
  local arch os
  if ! "$DOCKER" info >/dev/null 2>&1; then
    fail "the Docker engine does not answer; start Docker Desktop"
    return 1
  fi
  pass "Docker engine $("$DOCKER" info --format '{{.ServerVersion}}')"
  if "$DOCKER" compose version >/dev/null 2>&1; then
    pass "$("$DOCKER" compose version)"
  else
    fail "the docker compose plugin is missing"
  fi
  arch="$("$DOCKER" info --format '{{.Architecture}}')"
  os="$("$DOCKER" info --format '{{.OSType}}')"
  case "$os/$arch" in
    linux/aarch64|linux/arm64) pass "VM platform $os/$arch" ;;
    *) fail "VM platform is $os/$arch; the image needs linux/arm64" ;;
  esac
}

check_resources() {
  local mem_mib cpus need_mib
  mem_mib=$(( $("$DOCKER" info --format '{{.MemTotal}}') / 1048576 ))
  need_mib=$((VH_MIN_MEM_GIB * 1024))
  if [ "$mem_mib" -ge "$need_mib" ]; then
    pass "VM memory ${mem_mib} MiB (minimum ${VH_MIN_MEM_GIB} GiB)"
  else
    fail "VM memory ${mem_mib} MiB is below ${VH_MIN_MEM_GIB} GiB; raise it in Docker Desktop"
  fi
  cpus="$("$DOCKER" info --format '{{.NCPU}}')"
  if [ "$cpus" -ge "$VH_MIN_CPUS" ]; then
    pass "VM processors $cpus (minimum $VH_MIN_CPUS)"
  else
    fail "VM processors $cpus is below $VH_MIN_CPUS; raise them in Docker Desktop"
  fi
}

# With host networking, a container has the host name of the VM.
# The Docker Desktop setting also forwards the VM ports to macOS.
check_host_network() {
  local vm got settings
  vm="$("$DOCKER" info --format '{{.Name}}')"
  if ! got="$(probe 'cat /proc/sys/kernel/hostname' 2>/dev/null)"; then
    fail "cannot run a probe container from $PROBE"
    return 1
  fi
  if [ "$got" = "$vm" ]; then
    pass "host networking: a probe container has the VM host name $vm"
  else
    fail "host networking: a probe container has host name '$got', not '$vm'"
  fi
  settings="$HOME/Library/Group Containers/group.com.docker/settings-store.json"
  if [ ! -f "$settings" ]; then
    warn "cannot read the Docker Desktop settings; make sure host networking is on"
  elif grep -Eq '"HostNetworkingEnabled"[[:space:]]*:[[:space:]]*true' "$settings"; then
    pass "Docker Desktop host networking is on"
  else
    fail "Docker Desktop host networking is off; turn it on under Settings > Resources > Network"
  fi
}

check_vm_disk() {
  local perf="$1" need avail_kib avail_gib
  need="$VH_MIN_DISK_GIB"
  [ "$perf" = 0 ] || need="$VH_MIN_DISK_GIB_PERF"
  if ! avail_kib="$(probe "df -Pk / | awk 'NR == 2 { print \$4 }'" 2>/dev/null)"; then
    fail "cannot read the free VM disk"
    return 1
  fi
  avail_gib=$((avail_kib / 1048576))
  if [ "$avail_gib" -ge "$need" ]; then
    pass "free VM disk ${avail_gib} GiB (minimum $need GiB)"
  else
    fail "free VM disk ${avail_gib} GiB is below $need GiB; prune images or volumes, or grow the disk"
  fi
}

check_host_disk() {
  local need="${VH_MIN_HOST_DISK_GIB:-10}" dir avail_gib
  for dir in "$VH_ROOT" "$(dirname "$VH_DIST_CACHE")"; do
    [ -d "$dir" ] || dir="$HOME"
    avail_gib=$(($(df -Pk "$dir" | awk 'NR == 2 { print $4 }') / 1048576))
    if [ "$avail_gib" -ge "$need" ]; then
      pass "free host disk at $dir ${avail_gib} GiB (minimum $need GiB)"
    else
      fail "free host disk at $dir ${avail_gib} GiB is below $need GiB"
    fi
  done
}

# Host processes on cluster ports collide with the Docker Desktop port forwarding.
# Docker Desktop itself listens on these ports while the cluster runs, so it is not a conflict.
check_host_ports() {
  local ports listeners hits=""
  if ! command -v lsof >/dev/null; then
    warn "lsof is missing; the host port check is skipped"
    return 0
  fi
  ports=" $(bash "$VH_ROOT/image/lib/ports.sh" table | awk -F '\t' 'NR > 1 { print $4 }' |
    sort -un | tr '\n' ' ')"
  listeners="$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null |
    awk 'NR > 1 && $1 !~ /^com\.docke|^vpnkit|^docker/ { n = split($9, a, ":"); print $1 ":" a[n] }' |
    sort -u)" || true
  for l in $listeners; do
    case "$ports" in
      *" ${l##*:} "*) hits="$hits $l" ;;
    esac
  done
  if [ -n "$hits" ]; then
    warn "host processes listen on cluster ports (process:port):$hits"
  else
    pass "no host process listens on a cluster port"
  fi
}

check_tools() {
  local t
  for t in curl shasum unzip tar git; do
    if command -v "$t" >/dev/null; then
      pass "host tool $t"
    else
      fail "host tool $t is missing"
    fi
  done
}

check_maven() {
  local jdk="" v
  if [ -n "${VH_JDK21:-}" ] && [ -x "$VH_JDK21/bin/java" ]; then
    jdk="$VH_JDK21"
  elif [ -x /usr/libexec/java_home ]; then
    jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [ -n "$jdk" ]; then
    v="$("$jdk/bin/java" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
    case "$v" in
      21|21.*) pass "host JDK $v at $jdk" ;;
      *) fail "host JDK at $jdk is $v, not 21" ;;
    esac
  else
    fail "no host JDK 21; set VH_JDK21"
  fi
  if command -v "${VH_MVN:-mvn}" >/dev/null; then
    pass "Maven $(command -v "${VH_MVN:-mvn}")"
  else
    fail "Maven is not on PATH; set VH_MVN"
  fi
  if [ -d "$VH_M2" ]; then
    pass "private Maven repository $VH_M2"
  elif [ -d "$HOME/.m2/repository" ]; then
    warn "private Maven repository $VH_M2 is missing; the build clones ~/.m2/repository"
  else
    fail "neither $VH_M2 nor ~/.m2/repository exists"
  fi
}

main() {
  local maven=0 perf=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --maven) maven=1 ;;
      --perf) perf=1 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument '$1'" ;;
    esac
    shift
  done
  vh_load_env

  check_tools
  [ "$maven" = 0 ] || check_maven
  check_host_disk
  if find_docker; then
    pass "Docker CLI $DOCKER"
    PROBE="$(probe_image)"
    if check_engine; then
      check_resources
      check_host_network && check_vm_disk "$perf"
    fi
  else
    fail "no Docker CLI on PATH or at ~/.docker/bin/docker"
  fi
  check_host_ports

  if [ "$FAILED" = 0 ]; then
    log "all checks passed"
  else
    log "one or more checks failed"
  fi
  return "$FAILED"
}

main "$@"
