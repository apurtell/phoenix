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

# This script puts the release tarballs of the image into the host cache and verifies them.
# The cache directory holds only downloads. The script never downloads a cached file again.
# Each tarball must match the digest that versions.env pins.
#
# A pin can be empty. For Hadoop and HBase, the script then compares the digest with the
# published checksum file. async-profiler publishes no checksum file, so the script trusts
# the first download. In both cases the script records the digest in versions.local.env,
# which git ignores, and prints it so that the user can pin it in versions.env.
#
# Usage: fetch-dist.sh [--offline]
#   --offline  do not download; fail if a tarball is not in the cache
#
# Environment:
#   VH_DIST_CACHE  the cache directory; the default is in env/defaults.env

set -euo pipefail

VH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VH_REPO="$(cd "$VH_ROOT/../.." && pwd)"
export VH_ROOT VH_REPO
VH_LOCAL_VERSIONS="$VH_ROOT/versions.local.env"

# Loads the settings. env/local.env comes first, so its values override the defaults.
vh_load_env() {
  local f
  set -a
  for f in "$VH_ROOT/env/local.env" "$VH_ROOT/env/defaults.env" "$VH_ROOT/versions.env" \
    "$VH_LOCAL_VERSIONS"; do
    if [ -f "$f" ]; then
      # shellcheck source=/dev/null
      . "$f"
    fi
  done
  set +a
}

log() { echo "fetch-dist: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

# The artifacts. Columns: name, tarball variable, URL variable, checksum URL variable
# ("-" if there is none), digest bits, pin variable.
items() {
  cat <<'EOF'
hadoop  VH_HADOOP_TARBALL  VH_HADOOP_URL  VH_HADOOP_SHA512_URL  512  VH_HADOOP_SHA512
hbase   VH_HBASE_TARBALL   VH_HBASE_URL   VH_HBASE_SHA512_URL   512  VH_HBASE_SHA512
asprof  VH_ASPROF_TARBALL  VH_ASPROF_URL  -                     256  VH_ASPROF_SHA256
EOF
}

lower() { tr '[:upper:]' '[:lower:]'; }

# Prints the value that a file assigns to a variable, or nothing.
value_in_file() {
  [ -f "$1" ] || return 0
  sed -n "s/^$2=//p" "$1" | tail -n 1
}

# Prints the hex digest of a file.
digest() {
  shasum -a "$1" "$2" | awk '{ print $1 }' | lower
}

# Prints a normalized digest from a published checksum file.
# The file has either the "<hex>  <name>" form or the grouped gpg form "<name>: HEX HEX ...".
published_digest() {
  local file="$1" bits="$2" hex
  if grep -q ':' "$file"; then
    hex="$(sed 's/^[^:]*://' "$file" | tr -d ' \t\r\n' | lower)"
  else
    hex="$(awk 'NR == 1 { print $1 }' "$file" | lower)"
  fi
  case "$bits" in
    512) echo "$hex" | grep -Eq '^[0-9a-f]{128}$' || return 1 ;;
    256) echo "$hex" | grep -Eq '^[0-9a-f]{64}$' || return 1 ;;
  esac
  echo "$hex"
}

# Downloads one URL to a file. Only HTTPS is allowed, also for redirects.
download() {
  local url="$1" out="$2"
  log "downloading $url"
  rm -f "$out"
  local meter=-sS
  if [ -t 2 ]; then
    meter=-#
  fi
  curl -fL "$meter" --proto '=https' --proto-redir '=https' --retry 3 --retry-delay 5 \
    --connect-timeout 30 -o "$out" "$url"
}

# Writes or replaces one assignment in versions.local.env.
record_pin() {
  local var="$1" value="$2" tmp
  if [ ! -f "$VH_LOCAL_VERSIONS" ]; then
    awk '/^#/ { print; next } { exit }' "$VH_ROOT/versions.env" >"$VH_LOCAL_VERSIONS"
    {
      echo
      echo "# Digests that fetch-dist.sh recorded for pins that are empty in versions.env."
      echo "# Git ignores this file. Copy each value into versions.env to pin it."
    } >>"$VH_LOCAL_VERSIONS"
  fi
  tmp="$(mktemp "$VH_LOCAL_VERSIONS.XXXXXX")"
  grep -v "^$var=" "$VH_LOCAL_VERSIONS" >"$tmp" || true
  echo "$var=$value" >>"$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "$VH_LOCAL_VERSIONS"
}

notice_unpinned() {
  local var="$1" bits="$2" hex="$3" why="$4"
  log "=================================================================="
  log "NOTICE: $var has no pin in versions.env."
  log "$why"
  log "sha$bits: $hex"
  log "The value is recorded in $VH_LOCAL_VERSIONS."
  log "Check the value, then pin it in versions.env:"
  log "  $var=$hex"
  log "=================================================================="
}

# Prints the expected digest of an item and checks that the two pin files agree.
pinned_value() {
  local var="$1" committed local_pin
  committed="$(value_in_file "$VH_ROOT/versions.env" "$var")"
  local_pin="$(value_in_file "$VH_LOCAL_VERSIONS" "$var")"
  if [ -n "$committed" ] && [ -n "$local_pin" ] && [ "$committed" != "$local_pin" ]; then
    die "$var differs between versions.env and versions.local.env; remove the line from versions.local.env"
  fi
  echo "${committed:-$local_pin}" | lower
}

# Puts one checksum file into the cache, unless it is already there.
fetch_sum_file() {
  local url="$1" dest="$2" offline="$3"
  [ -f "$dest" ] && return 0
  [ "$offline" = 0 ] || die "$(basename "$dest") is not in the cache and downloads are off"
  download "$url" "$dest.part"
  mv "$dest.part" "$dest"
}

# Verifies a file whose pin is empty and records the digest.
verify_unpinned() {
  local name="$1" file="$2" sumurl="$3" bits="$4" pinvar="$5" actual="$6" offline="$7"
  local sumfile published committed
  committed="$(value_in_file "$VH_ROOT/versions.env" "$pinvar")"
  if [ -n "$(value_in_file "$VH_LOCAL_VERSIONS" "$pinvar")" ] && [ -z "$committed" ]; then
    # An earlier run recorded the value. The caller already compared the file with it.
    log "WARNING: $pinvar comes from versions.local.env; pin it in versions.env"
    return 0
  fi
  if [ "$sumurl" != "-" ]; then
    sumfile="$VH_DIST_CACHE/$(basename "$file" .part).sha$bits"
    fetch_sum_file "${!sumurl}" "$sumfile" "$offline"
    published="$(published_digest "$sumfile" "$bits")" || die "cannot parse $sumfile"
    [ "$published" = "$actual" ] || die "$name: digest does not match $sumfile"
    record_pin "$pinvar" "$actual"
    notice_unpinned "$pinvar" "$bits" "$actual" "The digest matches the published checksum file."
  else
    record_pin "$pinvar" "$actual"
    notice_unpinned "$pinvar" "$bits" "$actual" \
      "The project publishes no checksum, so this is trust on first download."
  fi
}

# Puts one tarball into the cache and verifies it.
fetch_item() {
  local name="$1" tarvar="$2" urlvar="$3" sumurl="$4" bits="$5" pinvar="$6" offline="$7"
  local tarball="${!tarvar}" url="${!urlvar}" dest file expected actual sumfile published
  dest="$VH_DIST_CACHE/$tarball"
  file="$dest"
  if [ ! -f "$dest" ]; then
    [ "$offline" = 0 ] || die "$tarball is not in $VH_DIST_CACHE and downloads are off"
    file="$dest.part"
    download "$url" "$file"
  fi

  expected="$(pinned_value "$pinvar")"
  actual="$(digest "$bits" "$file")"
  if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
    if [ "$file" != "$dest" ]; then
      rm -f "$file"
      die "$name: the download does not match $pinvar"
    fi
    die "$name: cached $dest does not match $pinvar; delete the file to fetch it again"
  fi
  if [ -z "$expected" ] || [ -z "$(value_in_file "$VH_ROOT/versions.env" "$pinvar")" ]; then
    verify_unpinned "$name" "$file" "$sumurl" "$bits" "$pinvar" "$actual" "$offline"
  elif [ "$sumurl" != "-" ]; then
    # A cached checksum file is a second, independent check. The script does not fetch it.
    sumfile="$VH_DIST_CACHE/$tarball.sha$bits"
    if [ -f "$sumfile" ]; then
      published="$(published_digest "$sumfile" "$bits")" || die "cannot parse $sumfile"
      [ "$published" = "$actual" ] || die "$name: $sumfile does not match $pinvar"
    fi
  fi
  if [ "$file" != "$dest" ]; then
    mv "$file" "$dest"
  fi
  log "$name: ok $dest"
}

main() {
  local offline=0 name tarvar urlvar sumurl bits pinvar
  while [ $# -gt 0 ]; do
    case "$1" in
      --offline) offline=1 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument '$1'" ;;
    esac
    shift
  done

  vh_load_env
  : "${VH_DIST_CACHE:?}"
  command -v shasum >/dev/null || die "shasum is not on PATH"
  [ "$offline" = 1 ] || command -v curl >/dev/null || die "curl is not on PATH"
  mkdir -p "$VH_DIST_CACHE"

  # The loop reads its rows from descriptor 3, so no command in the loop can consume them.
  while read -r name tarvar urlvar sumurl bits pinvar <&3; do
    fetch_item "$name" "$tarvar" "$urlvar" "$sumurl" "$bits" "$pinvar" "$offline"
  done 3<<EOF
$(items)
EOF
}

main "$@"
