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

# This script builds the Phoenix artifacts that go into the image.
# It runs one Maven command on the host with JDK 21 and a private local repository.
# The command builds the shaded phoenix-server jar and the harness distribution.
# It does not set the patch-process flag, because that flag turns off the shaded server build.
# It does not run the formatter, because the formatter needs JDK 17.
#
# Usage: build-phoenix.sh [--dry-run]
#   --dry-run  print the Maven command and do not run it
#
# Environment:
#   VH_JDK21             JDK 21 home; the default is in env/defaults.env
#   VH_M2                private Maven repository; the script clones ~/.m2/repository if it is missing
#   VH_MVN               Maven executable; the default is mvn on PATH
#   VH_MAVEN_TRUSTSTORE  optional trust store that the script adds to MAVEN_OPTS

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

log() { echo "build-phoenix: $*" >&2; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }
die() { log "ERROR: $*"; exit 1; }

# Prints the JDK 21 home. The configured path comes first, then the macOS java_home tool.
find_jdk21() {
  if [ -n "${VH_JDK21:-}" ] && [ -x "$VH_JDK21/bin/java" ]; then
    echo "$VH_JDK21"
    return 0
  fi
  if [ -x /usr/libexec/java_home ]; then
    /usr/libexec/java_home -v 21 2>/dev/null && return 0
  fi
  return 1
}

# Prints the major version of the java executable in the given home.
java_major() {
  local v
  v="$("$1/bin/java" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
  echo "${v%%.*}"
}

# Creates the private repository as an APFS clone of the shared one.
# The clone drops the Phoenix snapshot artifacts, so the reactor build supplies them.
make_private_m2() {
  local src="$HOME/.m2/repository" d
  [ -d "$src" ] || die "VH_M2 $VH_M2 is missing and $src does not exist to clone from"
  log "creating the private Maven repository $VH_M2 from $src"
  mkdir -p "$(dirname "$VH_M2")"
  if ditto --help 2>&1 | grep -q -- '--clone'; then
    ditto --clone "$src" "$VH_M2"
  else
    ditto "$src" "$VH_M2"
  fi
  if [ -d "$VH_M2/org/apache/phoenix" ]; then
    for d in "$VH_M2"/org/apache/phoenix/*/"$VH_PHOENIX_VERSION"; do
      if [ -d "$d" ]; then
        rm -rf "$d"
      fi
    done
  fi
  return 0
}

main() {
  local dry=0 jdk major mvn
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) dry=1 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument '$1'" ;;
    esac
    shift
  done

  vh_load_env
  : "${VH_M2:?}" "${VH_PHOENIX_HBASE_PROFILE:?}" "${VH_PHOENIX_VERSION:?}"

  jdk="$(find_jdk21)" || die "no JDK 21 found; set VH_JDK21"
  major="$(java_major "$jdk")"
  [ "$major" = "21" ] || die "$jdk is JDK $major, not JDK 21"

  mvn="${VH_MVN:-$(command -v mvn || true)}"
  [ -n "$mvn" ] && [ -x "$mvn" ] || die "mvn is not on PATH; set VH_MVN"

  if [ -n "${VH_MAVEN_TRUSTSTORE:-}" ]; then
    [ -f "$VH_MAVEN_TRUSTSTORE" ] || die "VH_MAVEN_TRUSTSTORE $VH_MAVEN_TRUSTSTORE does not exist"
    MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=$VH_MAVEN_TRUSTSTORE"
    export MAVEN_OPTS
  fi

  set -- -B "-Dmaven.repo.local=$VH_M2" clean package \
    "-Dhbase.profile=$VH_PHOENIX_HBASE_PROFILE" -Pjava21,harness-dist -DskipTests \
    -pl phoenix-server,phoenix-vector-harness -am

  log "repository: $VH_REPO"
  log "command: JAVA_HOME=$jdk $mvn $*"
  if [ "$dry" = 1 ]; then
    [ -d "$VH_M2" ] || log "the private Maven repository $VH_M2 does not exist yet"
    return 0
  fi
  [ -d "$VH_M2" ] || make_private_m2

  cd "$VH_REPO"
  JAVA_HOME="$jdk" "$mvn" "$@"
}

main "$@"
