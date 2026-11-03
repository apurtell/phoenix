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

# This script builds the cluster image on the host.
# The steps are: preflight, Phoenix build, artifact staging, tarball fetch, context assembly,
# and the image build. Each step is a separate script, and each script can also run alone.
# The image build context is a separate directory that holds only the image inputs.
# The repository tree therefore never goes into the context.
#
# Context layout:
#   <context>/image/                        a copy of image/ with the Dockerfile
#   <context>/<tarball>                     the Hadoop, HBase and async-profiler tarballs
#   <context>/<server jar>, vector-harness/ the Phoenix artifacts from stage-artifacts.sh
#   <context>/build-args.env                the build arguments of the last build
#
# Usage: build.sh [--skip-maven] [--skip-fetch] [--no-cache] [--tag TAG] [--skip-preflight] [--dry-run]
#   --skip-maven      reuse the current Phoenix artifacts and do not run Maven
#   --skip-fetch      do not download; the cache must hold every tarball
#   --no-cache        build the image without the Docker layer cache
#   --tag TAG         tag the image as VH_IMAGE:TAG instead of VH_IMAGE:VH_IMAGE_TAG
#   --skip-preflight  do not run the host and VM checks
#   --dry-run         run every step except the image build and print the build command
#
# The image also gets a tag with the short git revision, with "-dirty" for local changes.

set -euo pipefail

VH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

log() { echo "build: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
step() { log "---- $* ----"; }
usage() { sed -n '/^# Usage:/,/^# The image also/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

# Finds the Docker CLI. Docker Desktop can install it outside PATH.
# Its directory also holds the credential helper, so the directory goes on PATH.
find_docker() {
  local d
  d="$(command -v docker 2>/dev/null || true)"
  if [ -z "$d" ] && [ -x "$HOME/.docker/bin/docker" ]; then
    d="$HOME/.docker/bin/docker"
  fi
  [ -n "$d" ] || die "no Docker CLI on PATH or at ~/.docker/bin/docker"
  PATH="$(dirname "$d"):$PATH"
  export PATH
  DOCKER="$d"
}

# Prints an image reference. A pin adds the digest.
image_ref() {
  if [ -n "$2" ]; then
    echo "$1@$2"
  else
    echo "$1"
  fi
}

# Prints the top-level directory of a tarball.
# The function reads only the first entry, so the pipe can stop early.
tar_top_dir() {
  local first
  first="$(set +o pipefail; tar tzf "$1" 2>/dev/null | head -n 1)"
  first="${first#./}"
  first="${first%%/*}"
  [ -n "$first" ] && [ "$first" != "." ] || die "cannot read the top-level directory of $1"
  echo "$first"
}

# Links a cached tarball into the context. A hard link costs no space and no copy time.
link_tarball() {
  local src="$VH_DIST_CACHE/$1" dest="$VH_CONTEXT/$1"
  [ -f "$src" ] || die "$src is missing; run scripts/fetch-dist.sh"
  rm -f "$dest"
  ln "$src" "$dest" 2>/dev/null || cp -p "$src" "$dest"
}

# Prints the license header of versions.env. Generated text files carry it for the license audit.
license_header() { awk '/^#/ { print; next } { exit }' "$VH_ROOT/versions.env"; }

# Prints the git revision for the image tag.
git_tag() {
  local rev
  rev="$(git -C "$VH_REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  if [ -n "$(git -C "$VH_REPO" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
    rev="$rev-dirty"
  fi
  echo "$rev"
}

# Copies the image sources and the tarballs into the context and writes the build arguments.
# A dry run continues without a Dockerfile, so the other steps can still be checked.
assemble_context() {
  local ctx="$VH_CONTEXT" dry="$1"
  [ -f "$ctx/.vh-context" ] || die "$ctx has no .vh-context marker; staging did not run"
  if [ ! -f "$VH_ROOT/image/Dockerfile" ]; then
    [ "$dry" = 1 ] || die "$VH_ROOT/image/Dockerfile is missing"
    log "WARNING: $VH_ROOT/image/Dockerfile is missing"
  fi
  rm -rf "$ctx/image"
  cp -pR "$VH_ROOT/image" "$ctx/image"
  find "$ctx" -maxdepth 1 -name '*.tar.gz' -exec rm -f {} +
  link_tarball "$VH_HADOOP_TARBALL"
  link_tarball "$VH_HBASE_TARBALL"
  link_tarball "$VH_ASPROF_TARBALL"

  {
    license_header
    echo "VH_BASE_JDK17=$(image_ref "$VH_BASE_JDK17_IMAGE" "$VH_BASE_JDK17_PIN")"
    echo "VH_BASE_JDK21=$(image_ref "$VH_BASE_JDK21_IMAGE" "$VH_BASE_JDK21_PIN")"
    echo "VH_BASE_UBUNTU=$(image_ref "$VH_BASE_UBUNTU_IMAGE" "$VH_BASE_UBUNTU_PIN")"
    echo "VH_HADOOP_TARBALL=$VH_HADOOP_TARBALL"
    echo "VH_HADOOP_DIR=$(tar_top_dir "$ctx/$VH_HADOOP_TARBALL")"
    echo "VH_HBASE_TARBALL=$VH_HBASE_TARBALL"
    echo "VH_HBASE_DIR=$(tar_top_dir "$ctx/$VH_HBASE_TARBALL")"
    echo "VH_ASPROF_TARBALL=$VH_ASPROF_TARBALL"
    echo "VH_ASPROF_DIR=$(tar_top_dir "$ctx/$VH_ASPROF_TARBALL")"
    # The digests let the image build check the tarballs a second time.
    echo "VH_HADOOP_SHA512=$VH_HADOOP_SHA512"
    echo "VH_HBASE_SHA512=$VH_HBASE_SHA512"
    echo "VH_ASPROF_SHA256=$VH_ASPROF_SHA256"
    echo "VH_PHOENIX_SERVER_JAR=$VH_PHOENIX_SERVER_JAR"
    echo "VH_HARNESS_JAR=$VH_HARNESS_JAR"
    echo "VH_UID=$VH_UID"
    echo "VH_GID=$VH_GID"
    echo "VH_USER=$VH_USER"
    echo "VH_GIT_REVISION=$GIT_TAG"
  } >"$ctx/build-args.env"
  log "context assembled in $ctx"
  grep -v '^#' "$ctx/build-args.env" | sed 's/^/  /' >&2
}

# Runs or prints the image build.
image_build() {
  local tag="$1" no_cache="$2" dry="$3" line
  set -- build --platform "$VH_PLATFORM" -f "$VH_CONTEXT/image/Dockerfile" \
    -t "$VH_IMAGE:$tag" -t "$VH_IMAGE:$GIT_TAG" \
    --label "org.opencontainers.image.revision=$GIT_TAG" \
    --label "org.apache.phoenix.version=$VH_PHOENIX_VERSION" \
    --label "org.apache.hadoop.version=$VH_HADOOP_VERSION" \
    --label "org.apache.hbase.version=$VH_HBASE_VERSION"
  if [ "$no_cache" = 1 ]; then
    set -- "$@" --no-cache
  fi
  while IFS= read -r line; do
    case "$line" in
      ""|"#"*) ;;
      *) set -- "$@" --build-arg "$line" ;;
    esac
  done <"$VH_CONTEXT/build-args.env"
  set -- "$@" "$VH_CONTEXT"

  log "command: $DOCKER $*"
  if [ "$dry" = 1 ]; then
    log "dry run; the image build does not run"
    return 0
  fi
  "$DOCKER" "$@"
  log "built $VH_IMAGE:$tag and $VH_IMAGE:$GIT_TAG"
  if [ "$tag" != "$VH_IMAGE_TAG" ]; then
    log "the cluster uses VH_IMAGE_TAG=$VH_IMAGE_TAG; set VH_IMAGE_TAG=$tag in env/local.env to use this image"
  fi
}

main() {
  local skip_maven=0 skip_fetch=0 no_cache=0 skip_preflight=0 dry=0 tag=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --skip-maven) skip_maven=1 ;;
      --skip-fetch) skip_fetch=1 ;;
      --no-cache) no_cache=1 ;;
      --tag)
        [ $# -ge 2 ] || die "--tag needs a value"
        tag="$2"
        shift
        ;;
      --tag=*) tag="${1#--tag=}" ;;
      --skip-preflight) skip_preflight=1 ;;
      --dry-run) dry=1 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument '$1'" ;;
    esac
    shift
  done

  vh_load_env
  tag="${tag:-$VH_IMAGE_TAG}"
  echo "$tag" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$' || die "'$tag' is not a valid image tag"
  find_docker
  GIT_TAG="$(git_tag)"

  if [ "$skip_preflight" = 0 ]; then
    step "preflight"
    if [ "$skip_maven" = 0 ]; then
      "$VH_ROOT/scripts/preflight.sh" --maven
    else
      "$VH_ROOT/scripts/preflight.sh"
    fi
  fi

  if [ "$skip_maven" = 0 ]; then
    step "Phoenix build"
    if [ "$dry" = 1 ]; then
      "$VH_ROOT/scripts/build-phoenix.sh" --dry-run
    else
      "$VH_ROOT/scripts/build-phoenix.sh"
    fi
  fi

  step "artifact staging"
  "$VH_ROOT/scripts/stage-artifacts.sh"

  step "tarball fetch"
  if [ "$skip_fetch" = 1 ]; then
    "$VH_ROOT/scripts/fetch-dist.sh" --offline
  else
    "$VH_ROOT/scripts/fetch-dist.sh"
  fi
  # The fetch can record a new pin in versions.local.env, so the settings load again.
  vh_load_env

  step "context assembly"
  assemble_context "$dry"

  step "image build"
  image_build "$tag" "$no_cache" "$dry"
}

main "$@"
