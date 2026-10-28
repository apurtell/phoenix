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

# This script tests how fetch-data.sh merges digests into SHA256SUMS. It needs no network and
# no corpus files. It replaces the pinned corpora with small local files, and it runs the
# offline path of the script against a temporary data directory.
#
# Usage: test-fetch-data.sh

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=fetch-data.sh
. "$HERE/fetch-data.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-fetch-data.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAILS=0

check() {
  local what="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    echo "PASS $what"
  else
    echo "FAIL $what"
    printf -- '--- want\n%s\n--- got\n%s\n' "$want" "$got"
    FAILS=$((FAILS + 1))
  fi
}

# A new SHA256SUMS gets the input lines in input order.
sums="$WORK/new/SHA256SUMS"
mkdir -p "$WORK/new"
printf 'aa  b.hdf5\nbb  a.hdf5\n' | merge_sums "$sums"
check "new file keeps the input order" "$(printf 'aa  b.hdf5\nbb  a.hdf5')" "$(cat "$sums")"

# An input line replaces the line of its file in place. Other lines stay, new files come last.
sums="$WORK/merge/SHA256SUMS"
mkdir -p "$WORK/merge"
printf '11  other.bin\n22  sift.hdf5\n33 *binary.bin\n22  sift.hdf5\n44  last.bin\n' >"$sums"
printf '99  sift.hdf5\n88  glove.hdf5\n' | merge_sums "$sums"
check "merge replaces in place and appends new files" \
  "$(printf '11  other.bin\n99  sift.hdf5\n33 *binary.bin\n44  last.bin\n88  glove.hdf5')" \
  "$(cat "$sums")"

# A second identical merge changes nothing.
before="$(cat "$sums")"
printf '99  sift.hdf5\n88  glove.hdf5\n' | merge_sums "$sums"
check "merge is idempotent" "$before" "$(cat "$sums")"

# Empty input keeps the file as it is.
printf '' | merge_sums "$sums"
check "empty input keeps all lines" "$before" "$(cat "$sums")"

# No temporary files remain.
check "no temporary files remain" "" "$(find "$WORK/merge" -name '.SHA256SUMS*' | head -1)"

# The offline path verifies only the selected corpus and keeps the lines of other files.
VH_DATA="$WORK/data"
DATA_DIR="$VH_DATA/ann-benchmarks"
mkdir -p "$DATA_DIR"
printf 'one\n' >"$DATA_DIR/one.hdf5"
printf 'two\n' >"$DATA_DIR/two.hdf5"
one_sha="$(digest "$DATA_DIR/one.hdf5")"
two_sha="$(digest "$DATA_DIR/two.hdf5")"
printf '00  foreign.hdf5\nff  one.hdf5\n' >"$DATA_DIR/SHA256SUMS"
vh_load_env() { :; }
items() {
  printf 'one one.hdf5 https://example.invalid/one.hdf5 %s\n' "$one_sha"
  printf 'two two.hdf5 https://example.invalid/two.hdf5 %s\n' "$two_sha"
}
# Each run is in a subshell, because main sets its own exit trap.
(main --offline one) 2>/dev/null
check "offline run replaces only the line of the selected corpus" \
  "$(printf '00  foreign.hdf5\n%s  one.hdf5' "$one_sha")" "$(cat "$DATA_DIR/SHA256SUMS")"
(main --offline) 2>/dev/null
check "offline run of all corpora appends the missing line" \
  "$(printf '00  foreign.hdf5\n%s  one.hdf5\n%s  two.hdf5' "$one_sha" "$two_sha")" \
  "$(cat "$DATA_DIR/SHA256SUMS")"
rc=0
(cd "$DATA_DIR" && grep -v foreign SHA256SUMS | shasum -a 256 -c - >/dev/null) || rc=$?
check "shasum accepts the merged lines" "0" "$rc"

if [ "$FAILS" -gt 0 ]; then
  echo "$FAILS checks failed"
  exit 1
fi
echo "all checks passed"
