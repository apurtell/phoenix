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

# Container image definition for Distributed Pherf, shared by both the controller and every agent
# instance. The container command selects the runtime role: "controller start" (the default) or
# "agent start".
#
# The build context is always a staged directory beneath $PX_OUT/images/ and never the source
# working tree. Running scripts/stage-artifacts.sh --image pherf populates the context with:
#   the root      Every file from this directory, including this file, healthcheck.sh, and the
#                 standalone compose.yaml.
#   dist/         The HBase release archive verified against its declared pin by fetch-dist.sh.
#   phoenix/      The client, Pherf, and distributed JARs from the local Phoenix build, together
#                 with the bson JAR required by the client.
#   module/       The bin/ and conf/ directories of phoenix-pherf-distributed, providing the
#                 launch script and the logging configuration.
# Each staged file is supplied through a PX_STAGED_* build argument naming its relative path in the
# context, keeping this Dockerfile free of hardcoded artifact versions or filenames.
#
# The build uses two stages. The unpack stage extracts the client JARs from the HBase archive and
# validates the staged artifact set so that no archive reaches the final runtime image, while the
# runtime stage is built from the JRE 17 base image.
#
# Every base image is pinned by digest. Because the digest build arguments declare no default
# values, any build that omits a digest fails immediately at its FROM instruction.

ARG PX_IMAGE_BASE_JRE17
ARG PX_IMAGE_BASE_JRE17_SHA256

FROM ${PX_IMAGE_BASE_JRE17}@sha256:${PX_IMAGE_BASE_JRE17_SHA256} AS unpack

# Build arguments specifying the context-relative path of each staged file; all of them are
# required.
ARG PX_STAGED_HBASE
ARG PX_STAGED_PHOENIX_CLIENT
ARG PX_STAGED_PHOENIX_PHERF
ARG PX_STAGED_PHOENIX_BSON
ARG PX_STAGED_PHOENIX_DISTRIBUTED

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

COPY dist/ /stage/dist/
COPY phoenix/ /stage/phoenix/

# Assemble the runtime classpath to match the harness pherf driver: the four staged Phoenix JARs,
# plus lib/client-facing-thirdparty/ (providing logging, its SLF4J binding, and OpenTelemetry),
# lib/jdk11/ (providing JAXB, which Pherf requires to parse scenario definitions), and
# lib/commons-codec-*.jar (required by Pherf's commons-csv dependency) from the HBase distribution.
# All JARs are copied into a single flat directory.
RUN set -eu; \
    for name in PX_STAGED_HBASE PX_STAGED_PHOENIX_CLIENT PX_STAGED_PHOENIX_PHERF \
      PX_STAGED_PHOENIX_BSON PX_STAGED_PHOENIX_DISTRIBUTED; do \
      if [ -z "${!name:-}" ]; then echo "the build argument $name is required" >&2; exit 1; fi; \
    done; \
    mkdir -p /stage/hbase /out/lib; \
    tar -xzf "/stage/$PX_STAGED_HBASE" -C /stage/hbase --strip-components=1 --wildcards \
      '*/lib/client-facing-thirdparty/*' '*/lib/jdk11/*' '*/lib/commons-codec-*.jar'; \
    shopt -s nullglob; \
    for group in 'client-facing-thirdparty/*.jar' 'jdk11/*.jar' 'commons-codec-*.jar'; do \
      jars=(/stage/hbase/lib/$group); \
      if [ "${#jars[@]}" = 0 ]; then \
        echo "the staged HBase has no lib/$group" >&2; exit 1; fi; \
      cp "${jars[@]}" /out/lib/; \
    done; \
    for name in PX_STAGED_PHOENIX_CLIENT PX_STAGED_PHOENIX_PHERF PX_STAGED_PHOENIX_BSON \
      PX_STAGED_PHOENIX_DISTRIBUTED; do \
      test -s "/stage/${!name}" || { echo "the staged ${!name} is empty" >&2; exit 1; }; \
      cp "/stage/${!name}" /out/lib/; \
    done

FROM ${PX_IMAGE_BASE_JRE17}@sha256:${PX_IMAGE_BASE_JRE17_SHA256} AS runtime

# Host user and group identifiers assigned to the image's non-root user so that any files written
# to a bind-mounted host directory remain owned by the operator.
ARG PX_UID
ARG PX_GID
ARG PX_PHOENIX_VERSION
ARG PX_HBASE_VERSION
ARG PX_PHOENIX_HBASE_PROFILE

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# Both the controller REST listener and the agent listener rely on
# com.sun.net.httpserver.HttpServer, so the base JRE must include the jdk.httpserver module. The
# Java launcher loads the target class prior to searching for a main method, and class-load logging
# reports the origin of every loaded class. Passing -Xshare:off disables the class data sharing
# archive so that the reported source is the module path jrt:/jdk.httpserver itself, causing any
# JRE that lacks the module to fail immediately before subsequent build steps run.
RUN set -eu; \
    log="$(java -Xshare:off -Xlog:class+load=info -cp /nonexistent \
      com.sun.net.httpserver.HttpServer 2>&1 || true)"; \
    if ! grep -qF 'com.sun.net.httpserver.HttpServer source: jrt:/jdk.httpserver' <<<"$log"; then \
      grep -v '\[class,load\]' <<<"$log" >&2 || true; \
      echo "the JRE cannot load com.sun.net.httpserver.HttpServer:" \
        "it lacks the jdk.httpserver module" >&2; \
      exit 1; \
    fi; \
    echo "the JRE loads com.sun.net.httpserver.HttpServer from jdk.httpserver"

RUN set -eu; \
    : "${PX_UID:?the PX_UID build argument is required}"; \
    : "${PX_GID:?the PX_GID build argument is required}"; \
    : "${PX_PHOENIX_VERSION:?the PX_PHOENIX_VERSION build argument is required}"; \
    if [ "$PX_UID" = 0 ]; then echo "PX_UID must not be 0: the image runs as non-root" >&2; \
      exit 1; fi

# Install runtime OS utilities: curl executes the HTTP health probes, procps supplies ps (the
# primary mechanism the launch script uses to inspect process command lines) along with standard
# diagnostic tools, and tini runs as PID 1 to forward termination signals to the JVM and reap
# orphaned child processes reparented to PID 1, such as health probes terminated by a Docker
# timeout.
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl procps tini; \
    rm -rf /var/lib/apt/lists/*

# Create the non-root runtime user, removing any pre-existing base-image account that occupies the
# target host UID so that the container user and group IDs always match the host operator.
RUN set -eu; \
    holder="$(getent passwd "$PX_UID" | cut -d: -f1 || true)"; \
    if [ -n "$holder" ]; then userdel "$holder"; fi; \
    if ! getent group "$PX_GID" >/dev/null; then groupadd -g "$PX_GID" pherf; fi; \
    useradd -u "$PX_UID" -g "$PX_GID" -d /var/lib/pherf -M -s /bin/bash pherf

# The launch script locates conf/ and lib/ relative to its own installation directory and places
# conf/ first on the classpath, allowing a deployment to mount configuration files such as
# hbase-site.xml into conf/ where the agent and its child Pherf processes will read them.
COPY --from=unpack /out/lib/ /opt/pherf/lib/
COPY module/bin/ /opt/pherf/bin/
COPY module/conf/ /opt/pherf/conf/
COPY healthcheck.sh /opt/pherf/

# Because a named volume inherits the ownership of its mount point from the image on first use,
# pre-create the run directory here and assign ownership to the non-root runtime user.
RUN set -eu; \
    chmod 0755 /opt/pherf/healthcheck.sh /opt/pherf/bin/*; \
    mkdir -p /var/lib/pherf/run; \
    chown -R "$PX_UID:$PX_GID" /var/lib/pherf

ENV PHERF_RUN_DIR=/var/lib/pherf/run \
    PATH=/opt/pherf/bin:$PATH

LABEL org.opencontainers.image.title="Apache Phoenix Distributed Pherf" \
      org.opencontainers.image.description="The controller and agent image of Distributed Pherf" \
      org.apache.phoenix.harness.phoenix.version="${PX_PHOENIX_VERSION}" \
      org.apache.phoenix.harness.phoenix.hbase.profile="${PX_PHOENIX_HBASE_PROFILE}" \
      org.apache.phoenix.harness.hbase.version="${PX_HBASE_VERSION}"

USER ${PX_UID}:${PX_GID}
WORKDIR /var/lib/pherf
EXPOSE 8780 8790

# No HEALTHCHECK here: the deployment defines a role-specific probe that invokes healthcheck.sh
# for each service role.
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/pherf/bin/pherf-distributed.sh"]
CMD ["controller", "start"]
