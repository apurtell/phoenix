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

# This script copies the Phoenix artifacts of this branch into the image build context.
# It copies exact file names from versions.env and never a glob.
# The target directories also hold source, test and unshaded jars, and a glob would match them.
# Before the copy, the script checks that the artifacts are the correct build.
# One failed check stops the image build.
#
# Context layout that this script writes:
#   <context>/<server jar>                  the shaded phoenix-server jar
#   <context>/vector-harness/bin, conf, profiles
#   <context>/vector-harness/target/<harness jar> and target/lib/
#   <context>/phoenix-artifacts.txt         the git revision and the sha256 of each staged jar
#
# Usage: stage-artifacts.sh
#
# Environment:
#   VH_CONTEXT  the build context directory; the default is in env/defaults.env

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

log() { echo "stage-artifacts: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

FAILED=0
check() {
  local what="$1"
  shift
  if "$@"; then
    log "ok: $what"
  else
    log "FAILED: $what"
    FAILED=1
  fi
}

# Copies a file or a tree and keeps the times. The copy uses an APFS clone where possible.
copy() {
  cp -cpR "$@" 2>/dev/null || cp -pR "$@"
}

# Prints the license header of versions.env. Generated text files carry it for the license audit.
license_header() { awk '/^#/ { print; next } { exit }' "$VH_ROOT/versions.env"; }

# Prints the modification time of a file in seconds.
mtime() {
  stat -f %m "$1" 2>/dev/null || stat -c %Y "$1"
}

# Makes sure the context directory belongs to this framework before the script changes it.
prepare_context() {
  local ctx="$1"
  case "$ctx" in
    ""|/|"$HOME"|"$HOME/") die "VH_CONTEXT '$ctx' is not a safe build context" ;;
  esac
  if [ -d "$ctx" ] && [ -n "$(ls -A "$ctx")" ] && [ ! -f "$ctx/.vh-context" ]; then
    die "$ctx is not empty and has no .vh-context marker; refusing to change it"
  fi
  mkdir -p "$ctx"
  {
    license_header
    echo "# This marker shows that build.sh owns this directory and can replace its content."
  } >"$ctx/.vh-context"
}

multi_release() { unzip -p "$1" META-INF/MANIFEST.MF | tr -d '\r' | grep -qx 'Multi-Release: true'; }
no_relocated_jvector() { ! grep -Eq '^org/apache/phoenix/shaded/io/github/jbellis/' "$1"; }
# A jar of the HBase 2.5 line has "hbase-2.5" in its name or an HBase artifact version 2.5.x.
no_hbase25_jar() {
  ! find "$1" -name '*.jar' | sed 's|.*/||' |
    grep -Eq '(hbase-2\.5|^hbase-[a-z0-9-]+-2\.5\.|hbase-compat-2\.5)'
}
# The Hadoop metrics system needs the commons-logging API. The harness lib must supply it.
has_jcl_bridge() { compgen -G "$1/jcl-over-slf4j-[0-9]*.jar" >/dev/null; }
# DriverManager finds the Phoenix driver only through its service file.
# An IDE build that cleans target/classes during the Maven build can remove the resources
# from the jar. The classes stay, so only this check finds the defect.
registers_phoenix_driver() {
  unzip -p "$1" META-INF/services/java.sql.Driver 2>/dev/null |
    grep -qx 'org.apache.phoenix.jdbc.PhoenixDriver'
}

# Checks the shaded server jar. The second argument is the file list of the jar.
check_server_jar() {
  local jar="$1" listing="$2"
  check "server jar registers the Phoenix JDBC driver" registers_phoenix_driver "$jar"
  check "server jar manifest has Multi-Release: true" multi_release "$jar"
  check "server jar has the JDK 21 Panama distance kernel" \
    grep -Eq '^META-INF/versions/21/org/apache/phoenix/.*PanamaDistanceKernel[^/]*\.class$' "$listing"
  check "server jar has JVector at its original package" \
    grep -q '^io/github/jbellis/jvector/' "$listing"
  check "server jar has the JVector Panama provider for JDK 20 and later" \
    grep -Eq '^META-INF/versions/20/.*PanamaVectorizationProvider[^/]*$' "$listing"
  check "server jar has no relocated JVector" no_relocated_jvector "$listing"
}

# Checks the harness dependency directory.
check_harness_lib() {
  local lib="$1"
  check "harness lib has $VH_HARNESS_COMPAT_JAR" test -f "$lib/$VH_HARNESS_COMPAT_JAR"
  check "harness lib has $VH_HARNESS_HBASE_CLIENT_JAR" test -f "$lib/$VH_HARNESS_HBASE_CLIENT_JAR"
  # The HNSW build of IndexTool links region server classes, so hbase-server must have the
  # same version as hbase-client.
  local server_jar="${VH_HARNESS_HBASE_CLIENT_JAR/#hbase-client-/hbase-server-}"
  check "harness lib has $server_jar" test -f "$lib/$server_jar"
  check "harness lib has no HBase 2.5 jar" no_hbase25_jar "$lib"
  check "harness lib has the commons-logging bridge" has_jcl_bridge "$lib"
  check "harness lib registers the Phoenix JDBC driver" \
    registers_phoenix_driver "$lib/phoenix-core-client-$VH_PHOENIX_VERSION.jar"
}

# Warns if a jar is older than the last commit. A build with --skip-maven can reuse old jars.
warn_if_stale() {
  local jar="$1" head_time
  head_time="$(git -C "$VH_REPO" log -1 --format=%ct 2>/dev/null || echo 0)"
  if [ "$(mtime "$jar")" -lt "$head_time" ]; then
    log "WARNING: $(basename "$jar") is older than the last commit; rebuild without --skip-maven"
  fi
}

stage_harness() {
  local mod="$1" dest="$2"
  rm -rf "$dest"
  mkdir -p "$dest/target"
  copy "$mod/bin" "$mod/conf" "$mod/profiles" "$dest/"
  copy "$mod/target/$VH_HARNESS_JAR" "$dest/target/"
  copy "$mod/target/lib" "$dest/target/"
}

write_manifest() {
  local ctx="$1" rev
  rev="$(git -C "$VH_REPO" rev-parse HEAD 2>/dev/null || echo unknown)"
  {
    license_header
    echo "git.revision=$rev"
    if [ -n "$(git -C "$VH_REPO" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
      echo "git.dirty=true"
    else
      echo "git.dirty=false"
    fi
    (cd "$ctx" && shasum -a 256 "$VH_PHOENIX_SERVER_JAR" "vector-harness/target/$VH_HARNESS_JAR")
  } >"$ctx/phoenix-artifacts.txt"
}

main() {
  local server mod ctx f n
  case "${1:-}" in
    "") ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac

  vh_load_env
  : "${VH_CONTEXT:?}" "${VH_PHOENIX_SERVER_JAR:?}" "${VH_HARNESS_JAR:?}"
  : "${VH_HARNESS_COMPAT_JAR:?}" "${VH_HARNESS_HBASE_CLIENT_JAR:?}" "${VH_PHOENIX_VERSION:?}"
  command -v unzip >/dev/null || die "unzip is not on PATH"

  server="$VH_REPO/phoenix-server/target/$VH_PHOENIX_SERVER_JAR"
  mod="$VH_REPO/phoenix-vector-harness"
  ctx="$VH_CONTEXT"
  for f in "$server" "$mod/target/$VH_HARNESS_JAR"; do
    [ -f "$f" ] || die "$f is missing; run scripts/build-phoenix.sh"
  done
  for f in "$mod/target/lib" "$mod/bin" "$mod/conf" "$mod/profiles"; do
    [ -d "$f" ] || die "$f is missing; run scripts/build-phoenix.sh"
  done

  VH_LISTING="$(mktemp)"
  trap 'rm -f "$VH_LISTING"' EXIT
  unzip -Z1 "$server" >"$VH_LISTING" || die "cannot list $server"
  check_server_jar "$server" "$VH_LISTING"
  check_harness_lib "$mod/target/lib"
  [ "$FAILED" = 0 ] || die "artifact checks failed; the image build stops"
  warn_if_stale "$server"
  warn_if_stale "$mod/target/$VH_HARNESS_JAR"

  log "MapReduce, YARN and HDFS client jars in the harness lib:"
  find "$mod/target/lib" -name '*.jar' -exec basename {} \; |
    grep -E 'mapreduce|yarn|hdfs-client' | sort | sed 's/^/  /' >&2 || true

  prepare_context "$ctx"
  # Old jars at the top level of the context would go into the image, so they are removed.
  find "$ctx" -maxdepth 1 -name '*.jar' -exec rm -f {} +
  copy "$server" "$ctx/"
  stage_harness "$mod" "$ctx/vector-harness"

  # The harness launcher uses the last jar that its glob matches, so exactly one may exist.
  n="$(find "$ctx/vector-harness/target" -maxdepth 1 -name 'phoenix-vector-harness-*.jar' | wc -l)"
  [ "$n" -eq 1 ] || die "the staged harness target has $n harness jars, not 1"
  write_manifest "$ctx"
  log "staged into $ctx"
}

main "$@"
