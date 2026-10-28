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

# This script puts the published corpora of the profiles into VH_DATA and verifies them.
# The corpora are the ANN-Benchmarks HDF5 files of SIFT1M and GloVe-100. The containers see
# VH_DATA as /data, so the files appear as /data/ann-benchmarks/<file>.
# Each file must match the SHA-256 digest that this script pins. A download goes into a new,
# empty temporary directory, and moves into place only after its digest matches. A file that
# is already in place gets the same check and is never downloaded again.
# The script also merges the digests into SHA256SUMS next to the files, so
# "shasum -a 256 -c SHA256SUMS" in that directory repeats the check. The merge replaces only
# the lines of the files that this run verifies, and it keeps all other lines in their order.
#
# Usage: fetch-data.sh [--offline] [CORPUS...]
#   --offline  do not download; fail if a file is not in VH_DATA
#   CORPUS     sift or glove; the default is all of them
#
# Environment:
#   VH_DATA  the data directory; the default is in env/defaults.env

set -euo pipefail

VH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VH_REPO="$(cd "$VH_ROOT/../.." && pwd)"
export VH_ROOT VH_REPO

# Loads the settings. env/local.env comes first, so its values override the defaults.
vh_load_env() {
  local f
  set -a
  for f in "$VH_ROOT/env/local.env" "$VH_ROOT/env/defaults.env"; do
    if [ -f "$f" ]; then
      # shellcheck source=/dev/null
      . "$f"
    fi
  done
  set +a
}

log() { echo "fetch-data: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }
usage() { sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; }

# The corpora. Columns: name, file name, URL, SHA-256 digest.
items() {
  cat <<'EOF'
sift   sift-128-euclidean.hdf5  https://ann-benchmarks.com/sift-128-euclidean.hdf5  dd6f0a6ed6b7ebb8934680f861a33ed01ff33991eaee4fd60914d854a0ca5984
glove  glove-100-angular.hdf5   https://ann-benchmarks.com/glove-100-angular.hdf5   544af1d5e84e112cd4749571dcfd8ca109818a572f850af75a3a09e093a953c4
EOF
}

# Prints the hex digest of a file.
digest() {
  shasum -a 256 "$1" | awk '{ print $1 }' | tr '[:upper:]' '[:lower:]'
}

# Downloads one URL to a file. Only HTTPS is allowed, also for redirects.
download() {
  local url="$1" out="$2"
  log "downloading $url"
  local meter=-sS
  if [ -t 2 ]; then
    meter=-#
  fi
  curl -fL "$meter" --proto '=https' --proto-redir '=https' --retry 3 --retry-delay 5 \
    --connect-timeout 30 -o "$out" "$url"
}

# The temporary directory of the current download. The exit trap removes it.
STAGE=""
cleanup() {
  if [ -n "$STAGE" ]; then
    rm -rf "$STAGE"
  fi
}

# Puts one corpus file into the data directory and verifies it.
fetch_item() {
  local name="$1" file="$2" url="$3" expected="$4" offline="$5" dest actual
  dest="$DATA_DIR/$file"
  if [ -f "$dest" ]; then
    actual="$(digest "$dest")"
    [ "$actual" = "$expected" ] ||
      die "$name: $dest does not match the pinned digest; delete the file to fetch it again"
    log "$name: ok $dest"
    return 0
  fi
  [ ! -e "$dest" ] || die "$name: $dest exists but is not a regular file"
  [ "$offline" = 0 ] || die "$name: $dest is missing and downloads are off"
  # The temporary directory is on the same file system as the target, so the move is a rename.
  STAGE="$(mktemp -d "$DATA_DIR/.fetch-$name.XXXXXX")"
  download "$url" "$STAGE/$file"
  actual="$(digest "$STAGE/$file")"
  [ "$actual" = "$expected" ] || die "$name: the download from $url does not match the pinned digest"
  chmod 644 "$STAGE/$file"
  mv "$STAGE/$file" "$dest"
  rm -rf "$STAGE"
  STAGE=""
  log "$name: ok $dest"
}

# Merges digest lines from standard input into a SHA256SUMS file. Each input line is
# "<digest>  <file>". An input line replaces the existing line of the same file in its place.
# The other existing lines stay in their order, and new files come last in input order.
# The new file replaces the old one only after it is complete.
merge_sums() {
  local sums="$1" dir fresh tmp old=/dev/null
  dir="$(dirname "$sums")"
  fresh="$(mktemp "$dir/.SHA256SUMS.new.XXXXXX")"
  tmp="$(mktemp "$dir/.SHA256SUMS.XXXXXX")"
  cat >"$fresh"
  if [ -f "$sums" ]; then
    old="$sums"
  fi
  if ! awk -v fresh="$fresh" '
    function name_of(line) { sub(/^[^ ]+ [ *]/, "", line); return line }
    BEGIN {
      while ((getline line < fresh) > 0) {
        if (line == "") continue
        f = name_of(line)
        if (!(f in entry)) order[++n] = f
        entry[f] = line
      }
      close(fresh)
    }
    {
      f = name_of($0)
      if (!(f in entry)) print
      else if (!(f in done)) { print entry[f]; done[f] = 1 }
    }
    END { for (i = 1; i <= n; i++) if (!(order[i] in done)) print entry[order[i]] }
  ' "$old" >"$tmp"; then
    rm -f "$fresh" "$tmp"
    return 1
  fi
  rm -f "$fresh"
  chmod 644 "$tmp"
  mv "$tmp" "$sums"
}

main() {
  local offline=0 name file url sha want n sums
  local -a wanted=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --offline) offline=1 ;;
      -h | --help) usage; exit 0 ;;
      -*) die "unknown argument '$1'" ;;
      *)
        items | awk -v n="$1" '$1 == n { found = 1 } END { exit !found }' ||
          die "unknown corpus '$1'; use one of: $(items | awk '{ printf "%s%s", s, $1; s = " " }')"
        wanted+=("$1")
        ;;
    esac
    shift
  done

  vh_load_env
  : "${VH_DATA:?}"
  command -v shasum >/dev/null || die "shasum is not on PATH"
  [ "$offline" = 1 ] || command -v curl >/dev/null || die "curl is not on PATH"
  DATA_DIR="$VH_DATA/ann-benchmarks"
  mkdir -p "$DATA_DIR"
  trap cleanup EXIT

  n=0
  sums=""
  # The loop reads its rows from descriptor 3, so no command in the loop can consume them.
  while read -r name file url sha <&3; do
    if [ ${#wanted[@]} -gt 0 ]; then
      for want in "${wanted[@]}"; do
        [ "$want" != "$name" ] || break
      done
      [ "$want" = "$name" ] || continue
    fi
    fetch_item "$name" "$file" "$url" "$sha" "$offline"
    sums="$sums$sha  $file
"
    n=$((n + 1))
  done 3<<EOF
$(items)
EOF
  printf '%s' "$sums" | merge_sums "$DATA_DIR/SHA256SUMS"
  log "verified $n files in $DATA_DIR"
}

# The guard lets a test source this file and call its functions.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
