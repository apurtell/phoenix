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

# This script builds the jHiccup agent jar from the cached source tarball of the release.
# The upstream build file needs JDK 6 tools, so the script writes a small Maven project.
# The shade step relocates HdrHistogram into the agent package. The agent then cannot clash
# with another HdrHistogram copy on the class path of the measured JVM.
# The script keeps the jar in the build cache and builds it again only with --force.
#
# Usage: build-jhiccup.sh [--offline] [--force]
#   --offline  run Maven in offline mode
#   --force    build the jar again, also if the build cache has it
#
# Environment:
#   VH_BUILD_CACHE  the directory for the jar; the default is in env/defaults.env
#   VH_M2           the Maven repository for the plugins and HdrHistogram
#   VH_JDK21        the JDK for the build
#   VH_MVN          Maven executable; the default is mvn on PATH

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

log() { echo "build-jhiccup: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

# Writes the Maven project. The manifest makes the jar an agent and a main jar.
write_pom() {
  cat >"$1/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.jhiccup</groupId>
  <artifactId>jhiccup-agent</artifactId>
  <version>$VH_JHICCUP_VERSION</version>
  <packaging>jar</packaging>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.hdrhistogram</groupId>
      <artifactId>HdrHistogram</artifactId>
      <version>$VH_JHICCUP_HDRHISTOGRAM_VERSION</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration><release>11</release></configuration>
      </plugin>
      <plugin>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.3.0</version>
        <configuration>
          <archive>
            <manifestEntries>
              <Main-Class>org.jhiccup.HiccupMeter</Main-Class>
              <Premain-Class>org.jhiccup.HiccupMeter</Premain-Class>
              <Agent-Class>org.jhiccup.HiccupMeter</Agent-Class>
            </manifestEntries>
          </archive>
        </configuration>
      </plugin>
      <plugin>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <relocations>
                <relocation>
                  <pattern>org.HdrHistogram</pattern>
                  <shadedPattern>org.jhiccup.internal.hdrhistogram</shadedPattern>
                </relocation>
              </relocations>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF
}

main() {
  local offline=0 force=0 tarball out jdk mvn work src actual jar
  while [ $# -gt 0 ]; do
    case "$1" in
      --offline) offline=1 ;;
      --force) force=1 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument '$1'" ;;
    esac
    shift
  done

  vh_load_env
  : "${VH_DIST_CACHE:?}" "${VH_BUILD_CACHE:?}" "${VH_M2:?}" "${VH_JHICCUP_TARBALL:?}" \
    "${VH_JHICCUP_SHA256:?}" "${VH_JHICCUP_DIR:?}" "${VH_JHICCUP_JAR:?}"
  out="$VH_BUILD_CACHE/$VH_JHICCUP_JAR"
  if [ -f "$out" ] && [ "$force" = 0 ]; then
    log "ok $out"
    return 0
  fi

  # The fetch step checked the tarball. This second check protects the build from a stale file.
  tarball="$VH_DIST_CACHE/$VH_JHICCUP_TARBALL"
  [ -f "$tarball" ] || die "$tarball is missing; run scripts/fetch-dist.sh"
  actual="$(shasum -a 256 "$tarball" | awk '{ print $1 }')"
  [ "$actual" = "$VH_JHICCUP_SHA256" ] || die "$tarball does not match VH_JHICCUP_SHA256"

  jdk="${VH_JDK21:-}"
  [ -n "$jdk" ] && [ -x "$jdk/bin/java" ] || jdk="$(/usr/libexec/java_home -v 21 2>/dev/null)" \
    || die "no JDK 21 found; set VH_JDK21"
  mvn="${VH_MVN:-$(command -v mvn || true)}"
  [ -n "$mvn" ] && [ -x "$mvn" ] || die "mvn is not on PATH; set VH_MVN"
  [ -d "$VH_M2" ] || die "the Maven repository $VH_M2 does not exist; set VH_M2"
  if [ -n "${VH_MAVEN_TRUSTSTORE:-}" ]; then
    MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=$VH_MAVEN_TRUSTSTORE"
    export MAVEN_OPTS
  fi

  # The trap runs after main returns, so it reads a global copy of the work directory.
  work="$(mktemp -d "${TMPDIR:-/tmp}/vh-jhiccup.XXXXXX")"
  VH_JHICCUP_WORK="$work"
  trap 'rm -rf "$VH_JHICCUP_WORK"' EXIT
  tar -xzf "$tarball" -C "$work"
  src="$work/$VH_JHICCUP_DIR/src/main/java/org/jhiccup"
  [ -f "$src/HiccupMeter.java" ] || die "$tarball has no $VH_JHICCUP_DIR/src/main/java/org/jhiccup"
  mkdir -p "$work/build/src/main/java/org"
  cp -R "$src" "$work/build/src/main/java/org/"
  # The upstream build fills the version template. This build does the same with fixed values.
  sed -e "s/\\\$VERSION\\\$/$VH_JHICCUP_VERSION/" -e "s/\\\$BUILD_TIME\\\$/vh-build/" \
    "$src/Version.java.template" >"$work/build/src/main/java/org/jhiccup/Version.java"
  rm -f "$work/build/src/main/java/org/jhiccup/Version.java.template"
  write_pom "$work/build"

  set -- -B -q "-Dmaven.repo.local=$VH_M2" -f "$work/build/pom.xml" package
  [ "$offline" = 0 ] || set -- -o "$@"
  log "command: JAVA_HOME=$jdk $mvn $*"
  JAVA_HOME="$jdk" "$mvn" "$@"

  # A jar without relocation or without the agent entry fails here and not in the cluster.
  jar="$work/build/target/jhiccup-agent-$VH_JHICCUP_VERSION.jar"
  unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Premain-Class: org.jhiccup.HiccupMeter' \
    || die "$jar has no Premain-Class entry"
  if unzip -l "$jar" | grep -q ' org/HdrHistogram/'; then
    die "$jar holds HdrHistogram classes that are not relocated"
  fi
  mkdir -p "$VH_BUILD_CACHE"
  cp "$jar" "$out.part"
  mv "$out.part" "$out"
  log "built $out"
}

main "$@"
