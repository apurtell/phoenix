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

# This script builds the jHiccup latency measurement tool from its official source release.
#
# Because jHiccup is distributed as a source release, it requires a dedicated build workflow. A
# build that completes without producing the expected JAR artifact is treated as a failure so that
# a later round cannot attempt to stage a nonexistent tool into the image.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"

set -euo pipefail

# This script intentionally omits px_trap_enable because its command substitutions can return
# expected non-zero exit codes that are handled explicitly.

# BJ_PRODUCT specifies the artifact path produced by the jHiccup Maven build relative to the
# unpacked source root.
BJ_PRODUCT=target/jHiccup.jar


px_cli_spec <<'EOF'
about Retrieves the pinned jHiccup source release with fetch-dist.sh, builds it with
+ Maven and the JDK PX_BUILD_JDK (11, 17 or 21, default 17), and records the tool
+ at $PX_OUT/build/jhiccup/jHiccup.jar.
output one key and value row: jar and the path of the tool.
exits 1 also means that the build made no build product, 4 the build expired after
+ PX_TIMEOUT_BUILD, 5 Maven or a JDK is absent. Any status of fetch-dist.sh
+ passes through.
example build-jhiccup.sh -e PX_BUILD_JDK=17
EOF

px_cli_parse "$@"
px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}
px_build_jdk || exit $?

if px_is_dry_run; then
  px_plan fetch "$SELF/fetch-dist.sh" jhiccup
  px_plan maven "JAVA_HOME=$PX_BUILD_HOME" mvn -B -DskipTests package
  px_plan record "\$PX_OUT/build/jhiccup/jHiccup.jar"
  exit 0
fi

BJ_STATUS=0
BJ_ROW="$("$SELF/fetch-dist.sh" jhiccup)" || BJ_STATUS=$?
if [ "$BJ_STATUS" != 0 ]; then
  px_die "$BJ_STATUS" "the jHiccup source release could not be retrieved and verified"
fi
BJ_ARCHIVE="$(px_tsv_field 4 "$BJ_ROW")"

BJ_OUT="$(px_out_subdir build/jhiccup)" || exit $?
BJ_SRC="$BJ_OUT/src"
BJ_LOG="$BJ_OUT/maven.log"
BJ_JAR="$BJ_OUT/jHiccup.jar"
# Remove any existing source directory and output JAR before building so that a failed build cannot
# leave a stale tool artifact from an earlier build in place.
rm -rf "$BJ_SRC" "$BJ_JAR"
mkdir -p "$BJ_SRC" || px_die "$PX_EXIT_FAIL" "cannot create $BJ_SRC"
tar -xzf "$BJ_ARCHIVE" -C "$BJ_SRC" --strip-components 1 ||
  px_die "$PX_EXIT_FAIL" "the verified archive $BJ_ARCHIVE does not unpack"
[ -f "$BJ_SRC/pom.xml" ] ||
  px_die "$PX_EXIT_FAIL" "the jHiccup source release holds no pom.xml"

px_info "building jHiccup with JDK $PX_BUILD_JDK_SEL ($PX_BUILD_HOME), log $BJ_LOG"
px_maven_run "$BJ_LOG" -B -f "$BJ_SRC/pom.xml" -DskipTests package
if [ ! -s "$BJ_SRC/$BJ_PRODUCT" ]; then
  px_die "$PX_EXIT_FAIL" "the build reported success but made no $BJ_PRODUCT, see $BJ_LOG"
fi
cp "$BJ_SRC/$BJ_PRODUCT" "$BJ_JAR" || px_die "$PX_EXIT_FAIL" "cannot copy the tool to $BJ_JAR"
px_info "jHiccup is $BJ_JAR"
px_tsv_row jar "$BJ_JAR"
