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

# This script compiles the Phoenix artifacts required for the container image directly from the
# local source tree.
#
# Because the container image executes exclusively Phoenix binaries compiled from this local source
# tree, this workflow never downloads pre-built Phoenix releases. Maven compiles three core modules
# along with all of their required reactor dependencies: phoenix-server produces the shaded server
# JAR deployed on the HBase master and RegionServers, phoenix-client-parent/phoenix-client-embedded
# produces the shaded client JAR used by the workload client and command-line tools, and
# phoenix-pherf builds the load-generation engine. Setting PX_PHERF=1 additionally compiles the
# phoenix-pherf-distributed module when present in the source tree. Furthermore, the resulting
# build record captures the bson JAR resolved during the build; because the shaded client JAR from
# the source tree does not bundle org.bson, the runtime client requires that external JAR on its
# classpath. In addition, the build record stores the JDK home used for compilation and the host
# execution classpath for Pherf, which combines the built pherf JAR with the dependency classpath
# generated for phoenix-pherf by the root POM with all test JARs excluded.
#
# The PX_PHOENIX_HBASE_PROFILE setting governs the active HBase build profile passed to Maven via
# -Dhbase.profile. That profile determines the compatibility module as well as the specific HBase
# and Hadoop versions against which Phoenix compiles. This script extracts those three values from
# the corresponding profile definition in the root pom.xml file and persists them alongside the
# profile name in the build metadata. At runtime, the cluster deploys its own HBase release chosen
# from within the version range supported by that compatibility module, and the subsequent staging
# step rejects any build record produced under a different profile.
#
# The build environment enforces explicit JDK selection and never falls back to whatever java
# binary appears on the PATH, because system package managers may resolve PATH to a JDK version
# that the project does not support. Instead, PX_BUILD_JAVA_HOME specifies an explicit JDK path, or
# the platform JDK registry resolves an installation matching the requested major version.
#
# Each build execution begins with the Maven clean phase. Without an initial clean, stale JARs left
# in target/ directories from an earlier build are swept into the shaded artifacts and cause the
# shading step to fail; cleaning also guarantees that building the same source tree always produces
# identical JARs.
#
# All Maven console output is captured in maven.log alongside the generated build record.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"

set -euo pipefail

# Error trapping via px_trap_enable is intentionally omitted because command substitutions in this
# script routinely return expected non-zero exit codes.

# Define the default Maven modules to build, specified as paths relative to the source tree root.
BP_MODULES="phoenix-server,phoenix-client-parent/phoenix-client-embedded,phoenix-pherf"
BP_DISTRIBUTED_MODULE=phoenix-pherf-distributed

BP_JDK=""
BP_TREE=""
BP_CONFIG_FILES=()
BP_PROFILE=""
BP_POM_VERSION=""
BP_POM_MODULE=""
BP_POM_HBASE=""
BP_POM_HADOOP=""

## @description  Prints the command-line usage documentation and option reference.
## @return       Writes usage information to stdout and exits with status 0.
usage() {
  cat <<'EOF'
Usage: build-phoenix.sh [option...]

Cleans and builds phoenix-server, phoenix-client-embedded and phoenix-pherf
with their dependencies from the local tree, with the HBase profile
PX_PHOENIX_HBASE_PROFILE and no tests, and records the jars, the bson jar
that the client needs, the profile, its compatibility module and the HBase
and Hadoop that Phoenix compiles against in $PX_OUT/build/phoenix/build.tsv.
The clean empties target/ of every module that the build selects, the
dependencies included.

Options:
  --jdk N             the JDK major version of the build: 11, 17 or 21,
                      default PX_BUILD_JDK, else 17
  --tree DIR          the source tree, default the tree that holds the harness
  --distributed       also build phoenix-pherf-distributed, default
                      PX_BUILD_DISTRIBUTED
  -n, --dry-run       print the Maven command and run nothing
  --config FILE       an extra configuration file, repeatable
  -v, --verbose       report detail
  -h, --help          show this text

stdout: the key and value rows of the build record (the tree, the versions,
the JDK and the path of every built jar), or the command under --dry-run.

Exit codes: 0 ok, 1 the build failed, a jar is missing or the pom does not
map the profile to its module, 2 usage or an unknown profile, 4 the build
expired after PX_TIMEOUT_BUILD, 5 Maven, a JDK or a module is absent,
6 no source tree.
EOF
}

# Parses the target Maven POM file to extract the project version along with the compatibility
# module, HBase version, and Hadoop version configured for BP_PROFILE, resolving any indirect
# ${property} references defined within the POM.
## @description  Populates the BP_POM_* globals from the project and BP_PROFILE definitions in a
##               Maven POM file.
## @return       Exits 0 on success, or exits 1 if any required value is missing from the POM.
bp_pom_scan() {
  local pom=$1 line in_parent=0 in_profile=0 named=0 active="" value name props=""
  local module="" hbase="" hadoop="" top_hadoop=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
    *'<parent>'*) in_parent=1 ;;
    *'<profile>'*) in_profile=1 named=0 active="" module="" hbase="" hadoop="" ;;
    esac
    case "$line" in
    *'</parent>'*) in_parent=0; continue ;;
    *'</profile>'*)
      in_profile=0
      if [ "$active" = "$BP_PROFILE" ]; then
        BP_POM_MODULE=$module BP_POM_HBASE=$hbase BP_POM_HADOOP=$hadoop
      fi
      continue
      ;;
    esac
    [ "$in_parent" = 0 ] || continue
    case "$line" in
    *'<'*'>'*'</'*'>'*) ;;
    *) continue ;;
    esac
    name=${line#*<}
    name=${name%%>*}
    value=${line#*>}
    value=${value%%</*}
    # Consider a profile active for this build when its activation block matches the hbase.profile
    # property against the target profile value of the build.
    if [ "$in_profile" = 1 ]; then
      case "$name" in
      name) [ "$value" != hbase.profile ] || named=1 ;;
      value) [ "$named" = 0 ] || active=$value ;;
      hbase.compat.version) module=$value ;;
      hbase.version) hbase=$value ;;
      hadoop.version) hadoop=$value ;;
      esac
      continue
    fi
    case "$name" in
    version) [ -n "$BP_POM_VERSION" ] || BP_POM_VERSION=$value ;;
    hadoop.version) [ -n "$top_hadoop" ] || top_hadoop=$value ;;
    hbase-*.runtime.version) props="$props$name$PX_TAB$value$PX_NL" ;;
    esac
  done <"$pom"
  # A profile that does not declare its own Hadoop version inherits the top-level project Hadoop
  # version, whereas a profile specifies its HBase version through a project-level property.
  BP_POM_HADOOP=${BP_POM_HADOOP:-$top_hadoop}
  case "$BP_POM_HBASE" in
  \$\{*\})
    name=${BP_POM_HBASE#??}
    BP_POM_HBASE="$(px_tsv_get "${name%\}}" "$props")" || BP_POM_HBASE=""
    ;;
  esac
  for name in BP_POM_VERSION BP_POM_MODULE BP_POM_HBASE BP_POM_HADOOP; do
    if [ -z "${!name}" ]; then
      px_die "$PX_EXIT_FAIL" "$pom does not name the value of $name for the HBase profile" \
        "$BP_PROFILE"
    fi
  done
}

px_cli_parse "$@"
px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}

if [ -z "$BP_DISTRIBUTED" ] && px_is_true "${PX_BUILD_DISTRIBUTED:-0}"; then
  BP_DISTRIBUTED=1
fi

BP_TREE="${BP_TREE:-$PX_TREE_ROOT}"
if [ -z "$BP_TREE" ]; then
  px_die "$PX_EXIT_NO_OBJECT" "this harness is outside a source tree, name one with --tree"
fi
BP_TREE="$(px_realpath "$BP_TREE")" ||
  px_die "$PX_EXIT_NO_OBJECT" "no such source tree: $BP_TREE"
for BP_MODULE in . phoenix-server phoenix-client-parent/phoenix-client-embedded phoenix-pherf; do
  if [ ! -f "$BP_TREE/$BP_MODULE/pom.xml" ]; then
    px_die "$PX_EXIT_NO_OBJECT" "$BP_TREE is not a Phoenix source tree: no $BP_MODULE/pom.xml"
  fi
done
BP_LIST=$BP_MODULES
# When the Distributed Pherf tier is enabled via PX_PHERF, include its Maven module in the build.
if px_is_true "${PX_PHERF:-0}"; then
  if [ ! -f "$BP_TREE/$BP_DISTRIBUTED_MODULE/pom.xml" ]; then
    px_die "$PX_EXIT_UNSUPPORTED" "the tree holds no $BP_DISTRIBUTED_MODULE module, which" \
      "PX_PHERF=1 builds"
  fi
  BP_LIST="$BP_LIST,$BP_DISTRIBUTED_MODULE"
fi

# Verify that the requested HBase profile is recognized by the harness and that the project POM maps
# it to the exact compatibility module recorded in the harness profile table. Enforcing this
# alignment guarantees that the version range checks performed during the staging and preflight
# phases remain valid for the JAR artifacts produced by this build.
BP_PROFILE=${PX_PHOENIX_HBASE_PROFILE:-}
read -r BP_TABLE_MODULE _ <<<"$(px_hbase_profile "$BP_PROFILE" || true)"
if [ -z "$BP_TABLE_MODULE" ]; then
  px_versions_check
fi
bp_pom_scan "$BP_TREE/pom.xml"
if [ "$BP_POM_MODULE" != "$BP_TABLE_MODULE" ]; then
  px_die "$PX_EXIT_FAIL" "the pom maps the HBase profile $BP_PROFILE to the compatibility" \
    "module $BP_POM_MODULE, and PX_HBASE_PROFILES of lib/common.sh names $BP_TABLE_MODULE." \
    "Bring the table up to date with the modules of the tree."
fi
px_build_jdk || exit $?

BP_ARGS=(-B -f "$BP_TREE/pom.xml" -pl "$BP_LIST" -am "-Dhbase.profile=$BP_PROFILE"
  -DskipTests -Dskip.code-coverage clean package)

px_info "profile $BP_PROFILE (compatibility module $BP_POM_MODULE) compiles against HBase" \
  "$BP_POM_HBASE and Hadoop $BP_POM_HADOOP"
if px_is_dry_run; then
  printf -v BP_LINE 'JAVA_HOME=%q mvn' "$PX_BUILD_HOME"
  printf -v BP_LINE '%s%s' "$BP_LINE" "$(printf ' %q' "${BP_ARGS[@]}")"
  px_plan maven "$BP_LINE"
  exit 0
fi

BP_OUT="$(px_out_subdir build/phoenix)" || exit $?
BP_LOG="$BP_OUT/maven.log"
BP_RECORD="$BP_OUT/build.tsv"
# Remove any existing build record before invoking Maven so that a failed build cannot leave behind
# metadata from an earlier run, which would otherwise cause the staging step to stage stale JARs
# that this build did not produce.
rm -f "$BP_RECORD"

px_info "building Phoenix $BP_POM_VERSION with JDK $PX_BUILD_JDK_SEL ($PX_BUILD_HOME), log" \
  "$BP_LOG"
px_maven_run "$BP_LOG" "${BP_ARGS[@]}"

# Construct the expected artifact paths from the naming rules defined in each module POM. Both the
# server and client JARs embed the HBase profile identifier and the project version, while the
# Pherf JAR embeds the project version; running the clean phase ensures no JAR from an earlier
# build remains beside them.
BP_SERVER_JAR="$BP_TREE/phoenix-server/target/phoenix-server-hbase-$BP_PROFILE-$BP_POM_VERSION.jar"
BP_CLIENT_JAR="$BP_TREE/phoenix-client-parent/phoenix-client-embedded/target/"
BP_CLIENT_JAR="${BP_CLIENT_JAR}phoenix-client-embedded-hbase-$BP_PROFILE-$BP_POM_VERSION.jar"
BP_PHERF_JAR="$BP_TREE/phoenix-pherf/target/phoenix-pherf-$BP_POM_VERSION.jar"
BP_JARS=("$BP_SERVER_JAR" "$BP_CLIENT_JAR" "$BP_PHERF_JAR")
BP_DIST_JAR=""
if px_is_true "${PX_PHERF:-0}"; then
  BP_DIST_JAR="$BP_TREE/$BP_DISTRIBUTED_MODULE/target/$BP_DISTRIBUTED_MODULE-$BP_POM_VERSION.jar"
  BP_JARS+=("$BP_DIST_JAR")
fi
for BP_JAR in "${BP_JARS[@]}"; do
  if [ ! -s "$BP_JAR" ]; then
    px_die "$PX_EXIT_FAIL" "the build reported success but made no $BP_JAR"
  fi
done
# Read the resolved bson JAR path from the cached classpath written during the phoenix-core-client
# module build so that the script does not assume a hardcoded local Maven repository location.
BP_CLASSPATH="$BP_TREE/phoenix-core-client/target/cached_classpath.txt"
BP_BSON_JAR="$(tr ':' '\n' <"$BP_CLASSPATH" 2>/dev/null |
  grep -E '/org/mongodb/bson/[^/]+/bson-[^/]+\.jar$' || true)"
case "$BP_BSON_JAR" in
'' | *"$PX_NL"*)
  px_die "$PX_EXIT_FAIL" "the build class path $BP_CLASSPATH does not name exactly one bson jar"
  ;;
esac
if [ ! -s "$BP_BSON_JAR" ]; then
  px_die "$PX_EXIT_FAIL" "the build class path names $BP_BSON_JAR, which is absent or empty"
fi
# The create-mrapp-generated-classpath execution defined in the root POM writes each module's
# test-scoped classpath during the generate-test-resources lifecycle phase, which still executes
# when -DskipTests is passed. Pherf's runtime execution classpath is constructed by stripping all
# test JARs from that generated classpath: the phoenix-core test JAR bundles a log4j2-test
# configuration that takes precedence in Log4j over the pherf JAR's log4j2.properties file and
# emits Log4j status and DEBUG lines to stderr. A runtime-scoped classpath alone is insufficient
# because phoenix-pherf declares log4j-core at test scope.
BP_PHERF_CP_FILE="$BP_TREE/phoenix-pherf/target/classes/mrapp-generated-classpath"
BP_PHERF_CP=""
[ ! -f "$BP_PHERF_CP_FILE" ] || BP_PHERF_CP="$(tr ':' '\n' <"$BP_PHERF_CP_FILE" |
  { grep -v -e '-tests\.jar$' || true; } | paste -s -d : -)"
[ -n "$BP_PHERF_CP" ] ||
  px_die "$PX_EXIT_FAIL" "the build wrote no class path of phoenix-pherf: $BP_PHERF_CP_FILE"

BP_TMP="$BP_RECORD.tmp.$$"
px_on_exit "rm -f $(printf '%q' "$BP_TMP")"
{
  px_tsv_row tree "$BP_TREE"
  px_tsv_row phoenix_version "$BP_POM_VERSION"
  px_tsv_row hbase_profile "$BP_PROFILE"
  px_tsv_row compat_module "$BP_POM_MODULE"
  px_tsv_row hbase_version "$BP_POM_HBASE"
  px_tsv_row hadoop_version "$BP_POM_HADOOP"
  px_tsv_row jdk "$PX_BUILD_JDK_SEL"
  px_tsv_row built_at "$(px_timestamp)"
  px_tsv_row server_jar "$BP_SERVER_JAR"
  px_tsv_row client_jar "$BP_CLIENT_JAR"
  px_tsv_row pherf_jar "$BP_PHERF_JAR"
  px_tsv_row bson_jar "$BP_BSON_JAR"
  px_tsv_row java_home "$PX_BUILD_HOME"
  px_tsv_row pherf_classpath "$BP_PHERF_JAR:$BP_PHERF_CP"
  if [ -n "$BP_DIST_JAR" ]; then
    px_tsv_row distributed_jar "$BP_DIST_JAR"
  fi
} >"$BP_TMP" || px_die "$PX_EXIT_FAIL" "cannot write the build record $BP_RECORD"
mv -f "$BP_TMP" "$BP_RECORD" || px_die "$PX_EXIT_FAIL" "cannot write $BP_RECORD"
px_info "build record $BP_RECORD"
cat "$BP_RECORD"
