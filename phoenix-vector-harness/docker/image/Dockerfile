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

# This file builds the one image that every container of the cluster uses.
# build.sh assembles the build context and passes the build arguments. The context holds:
#   image/                  the entrypoint, the scripts and the configuration templates
#   the tarballs            Hadoop, HBase and async-profiler, with the names in versions.env
#   the server jar          the shaded Phoenix server jar
#   vector-harness/         the harness launcher, configuration, profiles, jar and lib directory
# Hadoop, HBase, Phoenix and the profiler come from the build context, not from the network.
# Only the base images and the system packages come from the network.
# The JDKs come from the Temurin images. Hadoop daemons use JDK 17, and HBase uses JDK 21.
# One user, vh, runs every process. It owns every mount target, so named volumes start writable.

ARG VH_BASE_JDK17=eclipse-temurin:17-jdk-noble
ARG VH_BASE_JDK21=eclipse-temurin:21-jdk-noble
ARG VH_BASE_UBUNTU=ubuntu:24.04

FROM ${VH_BASE_JDK17} AS jdk17
FROM ${VH_BASE_JDK21} AS jdk21

# This stage unpacks the tarballs. Each tarball must have exactly one top-level directory.
# The Hadoop tarball has macOS metadata files ("._" names), and the stage removes them.
# Such a file next to a jar is not a valid jar, but the classpath wildcards include it.
FROM ${VH_BASE_UBUNTU} AS unpack
ARG VH_HADOOP_TARBALL=hadoop-3.4.2-aarch64-lean.tar.gz
ARG VH_HADOOP_DIR=hadoop-3.4.2
ARG VH_HBASE_TARBALL=hbase-2.6.7-hadoop3-bin.tar.gz
ARG VH_HBASE_DIR=hbase-2.6.7-hadoop3
ARG VH_ASPROF_TARBALL=async-profiler-4.4-linux-arm64.tar.gz
ARG VH_ASPROF_DIR=async-profiler-4.4-linux-arm64
ARG VH_HADOOP_SHA512=
ARG VH_HBASE_SHA512=
ARG VH_ASPROF_SHA256=
COPY ${VH_HADOOP_TARBALL} ${VH_HBASE_TARBALL} ${VH_ASPROF_TARBALL} /tmp/dist/
# The stage checks each tarball against its digest a second time. An empty digest skips the check.
RUN <<'SH'
set -eu
verify() {
  if [ -n "$3" ]; then
    echo "$3  /tmp/dist/$2" | "$1" -c -
  fi
}
verify sha512sum "$VH_HADOOP_TARBALL" "$VH_HADOOP_SHA512"
verify sha512sum "$VH_HBASE_TARBALL" "$VH_HBASE_SHA512"
verify sha256sum "$VH_ASPROF_TARBALL" "$VH_ASPROF_SHA256"
unpack() {
  tarball="/tmp/dist/$1"; top="$2"; dest="$3"
  work="$(mktemp -d)"
  tar -xzf "$tarball" -C "$work" --no-same-owner --warning=no-unknown-keyword
  find "$work" -name '._*' -exec rm -rf {} +
  entries="$(ls -A "$work")"
  if [ "$entries" != "$top" ]; then
    echo "$1: expected the single top-level directory '$top', found: $entries" >&2
    exit 1
  fi
  mv "$work/$top" "$dest"
  rm -rf "$work" "$tarball"
}
unpack "$VH_HADOOP_TARBALL" "$VH_HADOOP_DIR" /opt/hadoop
unpack "$VH_HBASE_TARBALL" "$VH_HBASE_DIR" /opt/hbase
unpack "$VH_ASPROF_TARBALL" "$VH_ASPROF_DIR" /opt/async-profiler
rm -rf /opt/hadoop/share/doc /opt/hbase/docs
find /opt/hadoop/share/hadoop -type d -name sources -prune -exec rm -rf {} +
SH

FROM ${VH_BASE_UBUNTU}
ARG VH_UID=1001
ARG VH_GID=1001
ARG VH_USER=vh
ARG VH_PHOENIX_SERVER_JAR=phoenix-server-hbase-2.6-5.4.0-SNAPSHOT.jar
ARG VH_HARNESS_JAR=phoenix-vector-harness-5.4.0-SNAPSHOT.jar
ARG VH_GIT_REVISION=unknown

# Runtime packages. The scripts use curl, jq, nc and envsubst. HBase and Hadoop load the
# compression and crypto libraries. procps gives pkill for the crash tests.
RUN <<'SH'
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  bash ca-certificates curl gettext-base iproute2 jq less libsnappy1v5 libssl3t64 libzstd1 \
  lsof netcat-openbsd procps zlib1g
rm -rf /var/lib/apt/lists/*
SH

# The base image has a user with uid 1000. The vh user replaces it, with fixed ids.
RUN <<'SH'
set -eu
userdel -r ubuntu 2>/dev/null || true
groupadd -g "$VH_GID" "$VH_USER"
useradd -u "$VH_UID" -g "$VH_GID" -m -d "/home/$VH_USER" -s /bin/bash "$VH_USER"
SH

COPY --from=jdk17 /opt/java/openjdk /opt/jdk17
COPY --from=jdk21 /opt/java/openjdk /opt/jdk21
COPY --from=unpack /opt/hadoop /opt/hadoop
COPY --from=unpack /opt/hbase /opt/hbase
COPY --from=unpack /opt/async-profiler /opt/async-profiler
COPY ${VH_PHOENIX_SERVER_JAR} /opt/hbase/lib/
COPY --chown=${VH_UID}:${VH_GID} vector-harness/ /opt/vector-harness/
COPY image/entrypoint.sh image/healthcheck.sh /opt/vh/
COPY image/lib/ /opt/vh/lib/
COPY image/bin/ /opt/vh/bin/
COPY image/conf/ /opt/vh/conf/

# The mount targets and the runtime directories. Docker copies the owner of an image
# directory into a new named volume, so each volume starts writable by vh.
RUN <<'SH'
set -eu
chmod 0755 /opt/vh/entrypoint.sh /opt/vh/healthcheck.sh /opt/vh/lib/*.sh /opt/vh/bin/*
mkdir -p /vh/data/zk /vh/data/nn /vh/data/snn /vh/data/dn /vh/data/nm \
  /var/log/vh /run/vh /work/out /work/logs /work/prof /work/profiles \
  /data/texmex /data/ann-benchmarks /etc/vh/hadoop /etc/vh/hbase /etc/vh/client
chown -R "$VH_UID:$VH_GID" /vh /var/log/vh /run/vh /work /data /etc/vh /opt/vector-harness
SH

# Build checks. The image fails to build if a component is missing or does not start.
# The heredocs are quoted, so the shell reads the build arguments from its environment.
RUN <<'SH'
set -eu
# Each check names itself and shows the command output when it fails.
out="$(mktemp)"
check() {
  what="$1"
  shift
  if ! "$@" >"$out" 2>&1; then
    echo "build check failed: $what" >&2
    tail -n 40 "$out" >&2
    exit 1
  fi
  echo "build check ok: $what"
}
# The command output goes into the check output, and the pattern must match it.
has() {
  pattern="$1"
  shift
  "$@" >"$out.cmd" 2>&1 || true
  cat "$out.cmd"
  grep -q -- "$pattern" "$out.cmd"
}
check "JDK 17" has '"17\.' /opt/jdk17/bin/java -version
check "JDK 21" has '"21\.' /opt/jdk21/bin/java -version
check "Hadoop 3.4.2" has '^Hadoop 3\.4\.2' env JAVA_HOME=/opt/jdk17 HADOOP_LOG_DIR=/tmp \
  /opt/hadoop/bin/hadoop version
# The version command uses the internal classpath, as the daemons do.
# The shaded client jar lists a relocated JDK name resolver that it does not contain,
# so on JDK 21 a command with that jar fails at the first host name lookup.
check "HBase 2.6.7" has 'HBase 2\.6\.7' env JAVA_HOME=/opt/jdk21 \
  HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true HBASE_LOG_DIR=/tmp \
  /opt/hbase/bin/hbase --internal-classpath version
check "MapReduce job client in the HBase lib" sh -c 'ls /opt/hbase/lib/hadoop-mapreduce-client-jobclient-*.jar'
check "exactly one Phoenix server jar" sh -c \
  'ls /opt/hbase/lib/phoenix-server-*.jar; [ "$(ls /opt/hbase/lib/phoenix-server-*.jar | wc -l)" -eq 1 ]'
check "Phoenix server jar name" test -f "/opt/hbase/lib/$VH_PHOENIX_SERVER_JAR"
check "harness jar" test -f "/opt/vector-harness/target/$VH_HARNESS_JAR"
check "harness launcher" test -x /opt/vector-harness/bin/vector-harness
check "harness lib" test -d /opt/vector-harness/target/lib
check "async-profiler launcher" test -x /opt/async-profiler/bin/asprof
check "async-profiler library" test -f /opt/async-profiler/lib/libasyncProfiler.so
check "no macOS metadata files" sh -c '[ -z "$(find /opt/hadoop /opt/hbase -name "._*" -print -quit)" ]'
rm -f "$out" "$out.cmd"
SH

# The image check renders the configuration for one role, then removes the output again.
USER ${VH_USER}
RUN VH_ROLE=rs VH_INDEX=1 bash -c '. /opt/vh/lib/ports.sh && . /opt/vh/lib/roles.sh \
  && . /opt/vh/lib/render.sh && vh_render && /opt/vh/bin/vh-mr-classpath --list' \
  && rm -rf /etc/vh/hadoop/* /etc/vh/hbase/* /etc/vh/client/*

# These settings make "docker exec" usable. The entrypoint replaces them with the role settings.
ENV JAVA_HOME=/opt/jdk21 \
    HADOOP_HOME=/opt/hadoop \
    HBASE_HOME=/opt/hbase \
    HADOOP_CONF_DIR=/etc/vh/hadoop \
    HBASE_CONF_DIR=/etc/vh/hbase \
    HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true \
    LANG=C.UTF-8 \
    PATH=/opt/jdk21/bin:/opt/vh/bin:/opt/hadoop/bin:/opt/hbase/bin:/opt/async-profiler/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

LABEL org.opencontainers.image.title="phoenix-vector-harness cluster" \
      org.opencontainers.image.description="All-localhost Hadoop, HBase and Phoenix cluster for the vector index harness" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.apache.phoenix.vectorharness.revision="${VH_GIT_REVISION}"

WORKDIR /work
# The compose file can set its own timing. This check suits every role.
HEALTHCHECK --interval=10s --timeout=8s --start-period=120s --retries=6 CMD ["/opt/vh/healthcheck.sh"]
ENTRYPOINT ["/opt/vh/entrypoint.sh"]
