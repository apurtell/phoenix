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

# This Dockerfile defines the unified multi-role container image for the harness. Every service
# role across every backend runs from this single image; at container startup, the entrypoint
# script selects the active role and target JDK from the instance environment.
#
# The build context is always the staged directory under $PX_OUT/images/ and never the source
# working tree. The scripts/stage-artifacts.sh script populates the context layout as follows:
#   - The context root holds every file from the image definition directory, including this
#     Dockerfile, the lifecycle scripts, and the lib/, bin/, and conf/ subdirectories.
#   - The dist/ directory holds the upstream distribution release archives after fetch-dist.sh
#     has verified them against their pins.
#   - The phoenix/ directory holds the JAR artifacts produced by the local Phoenix build.
# Each staged file is passed into the build via a PX_STAGED_* build argument naming its relative
# path in the build context, so this file contains no hardcoded artifact version numbers or file
# names.
#
# The build proceeds across four stages. Two JDK stages supply JDK 11 and JDK 21. The unpack stage
# extracts the distribution archives and validates the staged artifact set so that no raw archive
# enters the final runtime image. The runtime stage starts from the JDK 17 base image—the default
# JDK—and incorporates the other two JDK installations so that a single image can serve a
# deployment that mixes JDK versions across roles.
#
# Every base image is pinned by SHA-256 digest. The digest build arguments have no default values,
# ensuring that any build invocation that omits a digest fails immediately at its FROM line.

ARG PX_IMAGE_BASE_JDK11
ARG PX_IMAGE_BASE_JDK11_SHA256
ARG PX_IMAGE_BASE_JDK17
ARG PX_IMAGE_BASE_JDK17_SHA256
ARG PX_IMAGE_BASE_JDK21
ARG PX_IMAGE_BASE_JDK21_SHA256

FROM ${PX_IMAGE_BASE_JDK11}@sha256:${PX_IMAGE_BASE_JDK11_SHA256} AS jdk11
FROM ${PX_IMAGE_BASE_JDK21}@sha256:${PX_IMAGE_BASE_JDK21_SHA256} AS jdk21

FROM ${PX_IMAGE_BASE_JDK17}@sha256:${PX_IMAGE_BASE_JDK17_SHA256} AS unpack

# Declare the build arguments that specify each staged artifact as a path relative to the build
# context. The profiling tool arguments are optional, while all remaining artifact arguments are
# required.
ARG PX_STAGED_HADOOP
ARG PX_STAGED_HBASE
ARG PX_STAGED_ASYNC_PROFILER=
ARG PX_STAGED_PHOENIX_SERVER
ARG PX_STAGED_PHOENIX_CLIENT
ARG PX_STAGED_PHOENIX_PHERF
ARG PX_STAGED_PHOENIX_DISTRIBUTED=
# Declare the expected component versions for the active version set, covering the standalone
# Hadoop and HBase distributions as well as the ZooKeeper and Hadoop client JARs bundled under
# lib/ inside the HBase distribution archive, which the staging step inspects directly from the
# archive. The verification step below compares the extracted distributions against these version
# coordinates so that a stale or mismatched staging directory cannot produce a valid image.
ARG PX_HADOOP_VERSION
ARG PX_HBASE_VERSION
ARG PX_ZOOKEEPER_VERSION
ARG PX_HBASE_HADOOP_VERSION

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

COPY dist/ /stage/dist/
COPY phoenix/ /stage/phoenix/

# The ZooKeeper service runs using the ZooKeeper JAR bundled within the HBase distribution. That
# bundled release version may differ from the ZooKeeper client version against which Phoenix is
# compiled.
RUN set -eu; \
    for name in PX_STAGED_HADOOP PX_STAGED_HBASE PX_STAGED_PHOENIX_SERVER \
      PX_STAGED_PHOENIX_CLIENT PX_STAGED_PHOENIX_PHERF PX_HADOOP_VERSION PX_HBASE_VERSION \
      PX_ZOOKEEPER_VERSION PX_HBASE_HADOOP_VERSION; do \
      if [ -z "${!name:-}" ]; then echo "the build argument $name is required" >&2; exit 1; fi; \
    done; \
    mkdir -p /out/hadoop /out/hbase /out/phoenix/server /out/phoenix/client /out/px-tools; \
    tar -xzf "/stage/$PX_STAGED_HADOOP" -C /out/hadoop --strip-components=1; \
    tar -xzf "/stage/$PX_STAGED_HBASE" -C /out/hbase --strip-components=1; \
    cp "/stage/$PX_STAGED_PHOENIX_SERVER" /out/phoenix/server/; \
    cp "/stage/$PX_STAGED_PHOENIX_CLIENT" "/stage/$PX_STAGED_PHOENIX_PHERF" /out/phoenix/client/; \
    if [ -n "$PX_STAGED_PHOENIX_DISTRIBUTED" ]; then \
      cp "/stage/$PX_STAGED_PHOENIX_DISTRIBUTED" /out/phoenix/client/; fi; \
    if [ -n "$PX_STAGED_ASYNC_PROFILER" ]; then \
      mkdir -p /out/px-tools/async-profiler; \
      tar -xzf "/stage/$PX_STAGED_ASYNC_PROFILER" -C /out/px-tools/async-profiler \
        --strip-components=1; fi; \
    test -x /out/hadoop/bin/hdfs; \
    test -f "/out/hadoop/share/hadoop/common/hadoop-common-$PX_HADOOP_VERSION.jar" || \
      { echo "the staged Hadoop is not $PX_HADOOP_VERSION" >&2; exit 1; }; \
    test -x /out/hbase/bin/hbase; \
    hbase="${PX_HBASE_VERSION%-hadoop3}"; \
    test -f "/out/hbase/lib/hbase-common-$hbase-hadoop3.jar" || \
      { echo "the staged HBase is not $hbase-hadoop3" >&2; exit 1; }; \
    test -f "/out/hbase/lib/zookeeper-$PX_ZOOKEEPER_VERSION.jar" || \
      { echo "the staged HBase does not ship ZooKeeper $PX_ZOOKEEPER_VERSION" >&2; exit 1; }; \
    test -f "/out/hbase/lib/hadoop-common-$PX_HBASE_HADOOP_VERSION.jar" || \
      { echo "the staged HBase does not ship Hadoop $PX_HBASE_HADOOP_VERSION" >&2; exit 1; }

FROM ${PX_IMAGE_BASE_JDK17}@sha256:${PX_IMAGE_BASE_JDK17_SHA256} AS runtime

# Specify the target CPU architecture for the build, taking PX_ARCH from the staging step when
# provided and otherwise falling back to the TARGETARCH value supplied by the container build tool.
ARG TARGETARCH
ARG PX_ARCH=
# Specify the numeric user and group identifiers of the host operator. The non-root runtime user
# inside the image adopts these identifiers so that any files written to host bind mounts remain
# owned by the operator.
ARG PX_UID
ARG PX_GID
ARG PX_HADOOP_VERSION
ARG PX_HBASE_VERSION
ARG PX_ZOOKEEPER_VERSION
ARG PX_HBASE_HADOOP_VERSION
ARG PX_PHOENIX_VERSION
ARG PX_PHOENIX_HBASE_PROFILE

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# Validate required identity and version arguments and verify the target platform. The arm64
# architecture is the primary supported target, whereas amd64 builds and executes on a best-effort
# basis and emits an explicit warning in the build output.
RUN set -eu; \
    : "${PX_UID:?the PX_UID build argument is required}"; \
    : "${PX_GID:?the PX_GID build argument is required}"; \
    : "${PX_PHOENIX_VERSION:?the PX_PHOENIX_VERSION build argument is required}"; \
    if [ "$PX_UID" = 0 ]; then echo "PX_UID must not be 0: the image runs as non-root" >&2; \
      exit 1; fi; \
    arch="${PX_ARCH:-${TARGETARCH:-unknown}}"; \
    case "$arch" in \
    arm64) echo "building for arm64, the primary architecture" ;; \
    amd64) echo "WARNING: amd64 is best effort; arm64 is the primary architecture" >&2 ;; \
    *) echo "WARNING: the architecture $arch is not supported" >&2 ;; \
    esac

# Install minimal runtime utilities required by container roles. The tini init process reaps
# orphaned child processes left behind by service daemons—such as NodeManager task JVMs—and
# forwards termination signals, while curl supports container health probes and procps provides
# process diagnostic tools.
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl procps tini; \
    rm -rf /var/lib/apt/lists/*

# Provision the non-root runtime user and group. If a pre-existing account in the base image
# already occupies the target host UID, it is removed first so that the runtime user and group
# identifiers always match the host operator.
RUN set -eu; \
    holder="$(getent passwd "$PX_UID" | cut -d: -f1 || true)"; \
    if [ -n "$holder" ]; then userdel "$holder"; fi; \
    if ! getent group "$PX_GID" >/dev/null; then groupadd -g "$PX_GID" px; fi; \
    useradd -u "$PX_UID" -g "$PX_GID" -d /var/lib/px -M -s /bin/bash px

COPY --from=jdk11 /opt/java/openjdk /opt/jdk/11
COPY --from=jdk21 /opt/java/openjdk /opt/jdk/21
COPY --from=unpack /out/ /opt/

# Verify that every installed JDK starts cleanly and retains the jdk.httpserver module, which the
# distributed Pherf controller requires to serve its REST API and agent communication channel.
RUN set -eu; \
    ln -s /opt/java/openjdk /opt/jdk/17; \
    for v in 11 17 21; do \
      "/opt/jdk/$v/bin/java" -version; \
      "/opt/jdk/$v/bin/java" --list-modules | grep -q '^jdk.httpserver@' || \
        { echo "JDK $v lacks the jdk.httpserver module" >&2; exit 1; }; \
    done

COPY entrypoint.sh healthcheck.sh render.sh /opt/px/
COPY lib/ /opt/px/lib/
COPY bin/ /opt/px/bin/
COPY conf/ /opt/px/conf/

# When a named volume is mounted for the first time, the container runtime copies the ownership of
# the mount point from the image. Pre-creating every mount point here and assigning ownership to
# the non-root runtime user ensures that newly initialized volumes belong to that user.
RUN set -eu; \
    chmod 0755 /opt/px/entrypoint.sh /opt/px/healthcheck.sh /opt/px/render.sh \
      /opt/px/bin/*; \
    mkdir -p /data /var/lib/px/data /var/lib/px/logs /var/lib/px/conf; \
    chown -R "$PX_UID:$PX_GID" /data /var/lib/px

# Define the directory layout consumed by the image scripts. Any deployment that mounts an external
# data volume overrides PX_DATA_DIR to point to its mount location.
ENV PX_HADOOP_HOME=/opt/hadoop \
    PX_HBASE_HOME=/opt/hbase \
    PX_PHOENIX_HOME=/opt/phoenix \
    PX_JDK_ROOT=/opt/jdk \
    PX_TOOLS_DIR=/opt/px-tools \
    PX_DATA_DIR=/var/lib/px/data \
    PX_LOG_DIR=/var/lib/px/logs \
    PX_CONF_DIR=/var/lib/px/conf \
    PATH=/opt/px/bin:/opt/hadoop/bin:/opt/hbase/bin:$PATH

# Commands executed directly inside a running container instance by the orchestration substrate—
# such as the region mover, a workload driver, or an interactive px-tool shell—do not inherit the
# dynamic environment exported by the container entrypoint. Exporting these configuration directory
# variables in the image environment ensures that the launch scripts for such commands use the
# rendered cluster configuration and address each peer service by hostname; without them, the
# launch scripts fall back to the default distribution configuration, which points ZooKeeper at
# localhost and the default filesystem at file:///.
ENV HADOOP_CONF_DIR=${PX_CONF_DIR} \
    YARN_CONF_DIR=${PX_CONF_DIR} \
    HBASE_CONF_DIR=${PX_CONF_DIR}

LABEL org.opencontainers.image.title="Apache Phoenix harness" \
      org.opencontainers.image.description="The multi-role image of the Phoenix harness" \
      org.apache.phoenix.harness.arch="${PX_ARCH}" \
      org.apache.phoenix.harness.hadoop.version="${PX_HADOOP_VERSION}" \
      org.apache.phoenix.harness.hbase.version="${PX_HBASE_VERSION}" \
      org.apache.phoenix.harness.zookeeper.version="${PX_ZOOKEEPER_VERSION}" \
      org.apache.phoenix.harness.hbase.hadoop.version="${PX_HBASE_HADOOP_VERSION}" \
      org.apache.phoenix.harness.phoenix.version="${PX_PHOENIX_VERSION}" \
      org.apache.phoenix.harness.phoenix.hbase.profile="${PX_PHOENIX_HBASE_PROFILE}" \
      org.apache.phoenix.harness.jdks="11 17 21"

USER ${PX_UID}:${PX_GID}
WORKDIR /var/lib/px

# Omit an image-level HEALTHCHECK instruction because the deployment configuration declares a
# dedicated health check per service using the thresholds appropriate for its role.
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/px/entrypoint.sh"]
