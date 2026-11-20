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

# Performs pinned retrieval of the third-party distributions required by the image.
#
# Each artifact is defined as an entry in the version definitions. The entry name uses lowercase
# characters, such as hbase or async-profiler. The configuration key (<KEY>) for an entry is derived
# by converting the entry name to uppercase and replacing every hyphen with an underscore, and the
# following configuration variables describe the entry:
#   PX_DIST_ARTIFACTS              Specifies the default entries retrieved when the script is
#                                  invoked without explicit artifact names.
#   PX_DIST_<KEY>_VERSION          Defines the release version, which every entry must specify.
#   PX_DIST_<KEY>_PATH             Provides a relative path beneath the Apache distribution root.
#   PX_DIST_<KEY>_URL              Supplies a full HTTPS address for an artifact that is not hosted
#                                  on the Apache mirrors.
#   PX_DIST_<KEY>_BASE_URL and     Expresses the same external address in two parts that the script
#   PX_DIST_<KEY>_FILE             joins with a slash, accommodating an address that would otherwise
#                                  exceed the maximum length of a single line in a file.
#   PX_DIST_<KEY>_<REL>_SHA512_<ARCH>
#                                  Defines the cryptographic pin: the 128-digit hexadecimal SHA-512
#                                  digest of the artifact file for one release and one architecture.
# Here <ARCH> is ARM64 or AMD64, and <REL> is the release string converted to uppercase with every
# dot and hyphen replaced by an underscore. A path, an address, a base address, and a file name also
# support variant forms suffixed with _<ARCH>, infixed with _<REL> immediately after <KEY>, or
# combining both modifiers. Resolution precedence favors the release-qualified form first and the
# architecture-qualified form second, allowing a single release to reference a file layout that
# other releases do not share. Within any attribute value, the {version} token expands to the
# release string. A pin value that is shorter than a full SHA-512 digest continues across <PIN>_2,
# <PIN>_3, and subsequent numbered variables because a full SHA-512 digest is longer than a single
# line of a configuration file may be. Each entry defines either a relative path or an explicit
# address, but never both.
#
# Artifact retrieval follows one of two transport flows. An Apache entry defines a relative path;
# its primary source is the preferred mirror returned by the Apache mirror resolution service, and
# its fallback source is the Apache archive, which retains historical releases after active mirrors
# drop them. An entry configured with an explicit address, such as async-profiler or jHiccup, uses
# its dedicated origin directly without a mirror resolution step. Both retrieval flows enforce HTTPS
# exclusively: an address using any other scheme is rejected before issuing a request, and curl is
# configured to refuse redirects to any non-HTTPS scheme.
#
# Cryptographic pin verification determines whether a retrieved file is accepted. A declared pin is
# supplied by the loaded configuration. An entry without a declared pin operates under trust on
# first use: the initial retrieval records the observed digest in $PX_OUT/dist/pins.env, and every
# subsequent retrieval verifies the artifact against that recorded digest. Because the pin record is
# generated as runtime output, it never resides in the source working tree. Any digest mismatch
# immediately aborts the retrieval and removes the unverified file.
#
# Downloaded distributions are cached in $PX_OUT/dist. Any existing cached file is re-verified on
# every invocation, ensuring that a modified cache entry is never trusted and is instead discarded
# and fetched again.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/common.sh
. "$SELF/../lib/common.sh"

set -euo pipefail

# Do not enable px_trap_enable because command substitutions in this script intentionally return
# non-zero exit codes during normal control flow.

# Upstream endpoints for Apache distribution retrieval. The mirror resolution service returns the
# preferred mirror for a requested path, while the archive retains every published release.
FD_CLOSER_URL="https://www.apache.org/dyn/closer.lua"
FD_ARCHIVE_ROOT="https://archive.apache.org/dist/"

# Validation regular expressions defining the lexical grammars for an entry name, a release version,
# a cached file name, and a SHA-512 pin, along with the FD_TOKEN placeholder that expands to the
# release string within attribute values.
FD_NAME_RE='^[a-z0-9][a-z0-9-]*$'
FD_RELEASE_RE='^[A-Za-z0-9][A-Za-z0-9.-]*$'
FD_FILE_RE='^[A-Za-z0-9][A-Za-z0-9._+-]*$'
FD_PIN_RE='^[0-9a-f]{128}$'
FD_TOKEN='{version}'

# Maximum duration in seconds allowed for a single connection attempt. This value is a fixed
# retrieval constant rather than a configurable parameter, whereas the overall transfer duration is
# bounded by PX_TIMEOUT_FETCH.
FD_CONNECT_TIMEOUT_S=30

FD_NAMES=()
FD_ARCH=""
FD_ARCH_KEY=""
FD_DIST_DIR=""
FD_PINS=""
FD_CURL=()

px_cli_spec <<'EOF'
about Retrieves pinned third-party distributions into $PX_OUT/dist and verifies the
+ SHA-512 of each file for the host architecture.
about With no NAME it retrieves every entry of PX_DIST_ARTIFACTS.
operands [NAME...]
output one TSV row per artifact: name, arch, version, path, sha512 and pin. pin
+ is pinned (declared), recorded (by an earlier call) or tofu (by this call).
exits 1 also means that a hash did not match, 2 an address that is not HTTPS or a
+ malformed entry, 5 an unsupported host, 6 no such entry.
example fetch-dist.sh -n hbase hadoop
EOF

## @description  Translates an entry name into an uppercase key with hyphens converted to
##               underscores.
## @return       Writes the key to stdout and exits 0, or exits 2 if the name violates the grammar.
fd_key() {
  local name=${1:-}
  if ! [[ $name =~ $FD_NAME_RE ]]; then
    px_die "$PX_EXIT_USAGE" "invalid artifact name '$name': use lower case, digits and -"
  fi
  px_upper "$name"
}

## @description  Populates FD_ARCH and FD_ARCH_KEY from the host architecture to select the pin.
## @return       Returns 0 on supported hosts, or exits with 5 on any architecture other than arm64
##               or amd64.
fd_arch() {
  local machine
  machine="$(uname -m)"
  case "$machine" in
  arm64 | aarch64) FD_ARCH=arm64 FD_ARCH_KEY=ARM64 ;;
  x86_64 | amd64) FD_ARCH=amd64 FD_ARCH_KEY=AMD64 ;;
  *) px_die "$PX_EXIT_UNSUPPORTED" "the architecture $machine is not supported" ;;
  esac
}

# Resolves an entry attribute (key, release, attribute) by checking candidate variable forms in
# precedence order, evaluating release-qualified forms before architecture-qualified forms and
# expanding any {version} placeholder to the active release string.
## @description  Resolves the highest-precedence configured value for an entry attribute.
## @return       Writes the resolved value to stdout and returns 0, or returns 6 when no candidate
##               form defines a value.
fd_value() {
  local key=$1 release=$2 attr=$3 rel name value
  rel="$(px_upper "$release")"
  for name in "PX_DIST_${key}_${rel}_${attr}_$FD_ARCH_KEY" "PX_DIST_${key}_${rel}_$attr" \
    "PX_DIST_${key}_${attr}_$FD_ARCH_KEY" "PX_DIST_${key}_$attr"; do
    value=${!name:-}
    if [ -n "$value" ]; then
      printf '%s\n' "${value//"$FD_TOKEN"/$release}"
      return 0
    fi
  done
  return "$PX_EXIT_NO_OBJECT"
}

# Reconstructs a declared SHA-512 pin from its base variable (name) by appending sequential
# continuation variables (<name>_2, <name>_3, and so on) while the accumulated string remains
# shorter than a 128-character SHA-512 digest, ensuring that a complete value supplied by any
# configuration stage remains intact without further concatenation.
## @description  Assembles and normalizes the declared SHA-512 pin for a pin variable name.
## @return       Writes the lowercase pin to stdout (or nothing if undeclared) and returns 0.
fd_declared_pin() {
  local name=$1 part=2 out=${!1:-} next
  next="${name}_$part"
  while [ -n "$out" ] && [ "${#out}" -lt 128 ] && [ -n "${!next:-}" ]; do
    out="$out${!next}"
    part=$((part + 1))
    next="${name}_$part"
  done
  printf '%s\n' "$out" | tr 'A-F' 'a-f'
}

## @description  Looks up the recorded pin for a variable name in the runtime pin file, which the
##               configuration loader never reads directly.
## @return       Writes the pin to stdout and returns 0, or returns 6 when no pin is recorded.
fd_recorded_pin() {
  local name=$1 line value=""
  [ -f "$FD_PINS" ] || return "$PX_EXIT_NO_OBJECT"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
    "$name="*) value=${line#*=} ;;
    esac
  done <"$FD_PINS"
  [ -n "$value" ] || return "$PX_EXIT_NO_OBJECT"
  printf '%s\n' "$value"
}

## @description  Appends a trust-on-first-use pin (name, sha512) as a KEY=VALUE entry to the
##               runtime pin file.
## @return       Returns 0 on success, or exits with status 1 when the pin file cannot be written.
fd_record_pin() {
  printf '%s=%s\n' "$1" "$2" >>"$FD_PINS" ||
    px_die "$PX_EXIT_FAIL" "cannot write the pin file $FD_PINS"
}

## @description  Queries the Apache mirror service for the preferred mirror of a distribution path.
## @return       Writes the HTTPS root URL with a trailing slash and returns 0, or returns 1 when
##               the service provides no usable answer.
fd_mirror_root() {
  local path=$1 out="" status=0 root re
  re='"preferred"[[:space:]]*:[[:space:]]*"([^"]*)"'
  out="$("${FD_CURL[@]}" --max-time "${PX_TIMEOUT_DEFAULT:-300}" \
    "$FD_CLOSER_URL?path=$path&asjson=1" 2>/dev/null)" || status=$?
  if [ "$status" != 0 ] || ! [[ $out =~ $re ]]; then
    px_debug "the mirror service gave no answer for $path"
    return "$PX_EXIT_FAIL"
  fi
  root=${BASH_REMATCH[1]}
  case "$root" in
  https://?*) ;;
  *)
    px_warn "the mirror service named a source that is not HTTPS, it is ignored: $root"
    return "$PX_EXIT_FAIL"
    ;;
  esac
  printf '%s\n' "${root%/}/"
}

## @description  Downloads an artifact from a single URL into a target file (address, destination).
## @return       Returns 0 when the transfer completes successfully, or returns the exit status of
##               curl on failure.
fd_download() {
  local url=$1 dest=$2 status=0
  px_info "fetch $url"
  "${FD_CURL[@]}" --max-time "${PX_TIMEOUT_FETCH:-1800}" -o "$dest" "$url" || status=$?
  if [ "$status" != 0 ]; then
    px_warn "the fetch of $url failed with curl status $status"
    rm -f "$dest"
  fi
  return "$status"
}

# Resolves configuration for a single artifact entry, downloads or reuses its cached distribution,
# validates the SHA-512 digest, and writes one TSV row to stdout (a plan row on a dry run). Exits
# with status 1 on a failed fetch, hash mismatch, or write failure; status 2 on a malformed or
# non-HTTPS entry; status 5 when no SHA-512 program is available; or status 6 when no source is
# defined.
## @description  Retrieves and verifies one distribution entry, then writes its TSV manifest row.
## @return       Emits one TSV row (or dry-run plan row) and exits 0; exits 1 on fetch, hash, or
##               write failure, 2 on an invalid entry, 5 with no SHA-512 program, or 6 if absent.
fd_fetch() {
  local name=$1 key path url file pin_name expected="" state="" actual
  local cached tmp root sources=() source got=0 version="" base part rel
  key="$(fd_key "$name")"
  version="PX_DIST_${key}_VERSION"
  version=${!version:-}
  if [ -n "$version" ] && ! [[ $version =~ $FD_RELEASE_RE ]]; then
    px_die "$PX_EXIT_USAGE" "artifact $name: PX_DIST_${key}_VERSION='$version' is not a" \
      "release: letters, digits, dots and hyphens"
  fi
  rel="$(px_upper "$version")"
  path="$(fd_value "$key" "$version" PATH)" || path=""
  url="$(fd_value "$key" "$version" URL)" || url=""
  if [ -z "$url" ] && base="$(fd_value "$key" "$version" BASE_URL)"; then
    part="$(fd_value "$key" "$version" FILE)" ||
      px_die "$PX_EXIT_USAGE" "artifact $name has a BASE_URL and no FILE for $FD_ARCH"
    url="${base%/}/${part#/}"
  fi
  if [ -n "$path" ] && [ -n "$url" ]; then
    px_die "$PX_EXIT_USAGE" "artifact $name declares both a path and an address, keep one"
  fi
  if [ -z "$path" ] && [ -z "$url" ]; then
    px_die "$PX_EXIT_NO_OBJECT" "no such artifact '$name' ${version:+$version }for $FD_ARCH:" \
      "set PX_DIST_${key}_PATH or PX_DIST_${key}_URL in the version definitions, in a" \
      "form for the release (PX_DIST_${key}_${rel:-<REL>}_PATH) or the architecture" \
      "(PX_DIST_${key}_PATH_$FD_ARCH_KEY)"
  fi
  # Because a cryptographic pin binds the exact byte content of a specific release, the pin variable
  # name incorporates the release identifier.
  if [ -z "$version" ]; then
    px_die "$PX_EXIT_USAGE" "artifact $name has no PX_DIST_${key}_VERSION, which its pin needs"
  fi
  if [ -n "$url" ]; then
    # Enforce HTTPS exclusively; any other URI scheme, including plain HTTP, is rejected before
    # issuing a network request.
    case "$url" in
    https://?*) ;;
    *) px_die "$PX_EXIT_USAGE" "artifact $name: only HTTPS addresses are fetched, got '$url'" ;;
    esac
    file=${url%%\?*}
  else
    case "/$path/" in
    //* | *//* | */../* | */./* | *:*)
      px_die "$PX_EXIT_USAGE" "artifact $name: '$path' is not a path below the distribution root"
      ;;
    esac
    file=$path
  fi
  file=${file##*/}
  if ! [[ $file =~ $FD_FILE_RE ]]; then
    px_die "$PX_EXIT_USAGE" "artifact $name: '$file' is not a usable file name"
  fi
  pin_name="PX_DIST_${key}_${rel}_SHA512_$FD_ARCH_KEY"
  expected="$(fd_declared_pin "$pin_name")"
  if [ -n "$expected" ]; then
    state=pinned
  elif expected="$(fd_recorded_pin "$pin_name")"; then
    state=recorded
  else
    expected=""
  fi
  if [ -n "$expected" ] && ! [[ $expected =~ $FD_PIN_RE ]]; then
    px_die "$PX_EXIT_USAGE" "$pin_name is not a SHA-512 of 128 hexadecimal digits"
  fi
  cached="$FD_DIST_DIR/$file"
  if px_is_dry_run; then
    px_plan fetch "$name" "$FD_ARCH" "$cached" "${url:-$FD_ARCHIVE_ROOT$path}" "${state:-tofu}"
    return 0
  fi
  if [ -f "$cached" ]; then
    if [ -n "$expected" ]; then
      actual="$(px_sha512 "$cached")" || exit $?
      if [ "$actual" = "$expected" ]; then
        px_debug "$name: the cached $file matches its pin"
        px_tsv_row "$name" "$FD_ARCH" "$version" "$cached" "$actual" "$state"
        return 0
      fi
      px_warn "$name: the cached $file does not match its pin, it is fetched again"
    else
      px_warn "$name: the cached $file has no pin to verify, it is fetched again"
    fi
    rm -f "$cached"
  fi
  if [ -n "$url" ]; then
    sources=("$url")
  else
    if root="$(fd_mirror_root "$path")"; then
      sources=("$root$path")
    fi
    sources+=("$FD_ARCHIVE_ROOT$path")
  fi
  tmp="$FD_DIST_DIR/.partial.$file.$$"
  px_on_exit "rm -f $(printf '%q' "$tmp")"
  for source in "${sources[@]}"; do
    if fd_download "$source" "$tmp"; then
      got=1
      break
    fi
  done
  if [ "$got" = 0 ]; then
    px_die "$PX_EXIT_FAIL" "artifact $name: no source delivered $file"
  fi
  actual="$(px_sha512 "$tmp")" || exit $?
  if [ -n "$expected" ]; then
    if [ "$actual" != "$expected" ]; then
      rm -f "$tmp"
      px_die "$PX_EXIT_FAIL" "artifact $name ($FD_ARCH): the SHA-512 does not match." \
        "expected $expected, actual $actual. The file is removed."
    fi
  else
    fd_record_pin "$pin_name" "$actual"
    px_warn "trust on first use: $name ($FD_ARCH) has no declared pin." \
      "Its SHA-512 $actual is recorded in $FD_PINS and verified from now on."
    state=tofu
  fi
  mv -f "$tmp" "$cached" || px_die "$PX_EXIT_FAIL" "cannot move $file into $FD_DIST_DIR"
  px_tsv_row "$name" "$FD_ARCH" "$version" "$cached" "$actual" "$state"
}

px_cli_parse "$@"
FD_NAMES=(${PX_CLI_ARGS[@]+"${PX_CLI_ARGS[@]}"})
px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}

if [ "${#FD_NAMES[@]}" = 0 ]; then
  # PX_DIST_ARTIFACTS contains literal configuration text where artifact names are delimited by
  # commas or spaces and never contain glob characters, making unquoted word splitting safe.
  for FD_NAME in ${PX_DIST_ARTIFACTS//,/ }; do
    FD_NAMES+=("$FD_NAME")
  done
fi
if [ "${#FD_NAMES[@]}" = 0 ]; then
  px_die "$PX_EXIT_NO_OBJECT" "no artifact named, and PX_DIST_ARTIFACTS lists none"
fi
for FD_NAME in "${FD_NAMES[@]}"; do
  fd_key "$FD_NAME" >/dev/null
done

command -v curl >/dev/null 2>&1 ||
  px_die "$PX_EXIT_UNSUPPORTED" "curl is required to fetch distributions"
FD_CURL=(curl --fail --silent --show-error --location --proto "=https"
  --proto-redir "=https" --retry 2 --connect-timeout "$FD_CONNECT_TIMEOUT_S")

fd_arch
FD_DIST_DIR="$(px_out_subdir dist)" || exit $?
FD_PINS="$FD_DIST_DIR/pins.env"

for FD_NAME in "${FD_NAMES[@]}"; do
  fd_fetch "$FD_NAME"
done
