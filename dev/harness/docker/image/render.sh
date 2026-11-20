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

# This fail-closed template renderer transforms conf/*.tmpl templates into the active
# configuration for a single instance before any daemon is allowed to start.
#
# The validation rules are intentionally strict: a misconfigured instance must never start,
# because a cluster running with unintended default settings produces meaningless measurements.
#   1. Placeholders must take the form ${NAME} where NAME carries the PX_* prefix, and values
#      are sourced exclusively from the exported environment.
#   2. Referencing an unset or empty variable immediately aborts rendering and reports the
#      offending variable name, the template path, and the line number.
#   3. Any residual ${ or $PX_ token remaining in the rendered text aborts the operation, which
#      also catches environment values that inject unexpanded placeholders of their own.
#   4. A failed rendering pass writes no destination files: every output file is staged in a
#      temporary directory first and moved into place only after all templates render cleanly.
#
# Configuration overlays are applied through --overlay DIR, which renders every DIR/*.tmpl
# template after the base set under the same strict rules. Each rendered overlay file either
# replaces the base output file of the same name or introduces a new configuration file. The
# round A5 plug-in contract uses this mechanism to merge plug-in configuration overlays, and
# the entrypoint passes PX_CONF_OVERLAY_DIR to this flag whenever that variable is set.

IMG_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/lib/image.sh
. "$IMG_HOME/lib/image.sh"

set -euo pipefail

RENDER_OUT=""
RENDER_OVERLAY=""
RENDER_TEMPLATES=()
RENDER_TMP=""
# These globals hold the expanded text produced by render_line or the diagnostic reason if the
# line was rejected.
RENDER_LINE=""
RENDER_ERR=""
# The opening delimiter of a placeholder is stored in a variable so that pattern matching quotes
# it consistently across every Bash release since 3.2.
RENDER_OPEN="\${"

## @description  Prints the command-line usage and help documentation for the renderer.
## @return       Writes the help text to standard output and exits with status 0.
usage() {
  cat <<'EOF'
Usage: render.sh -o DIR [--overlay DIR] TEMPLATE...

Renders each TEMPLATE (a NAME.tmpl file) into DIR/NAME. A placeholder is
${PX_NAME}; an unset or empty variable, a placeholder outside the PX_*
namespace, or a ${ left in the output aborts the render, and then no file
is written.

Options:
  -o, --output DIR   the directory that receives the rendered files
  --overlay DIR      render DIR/*.tmpl last; a file of the same name replaces
                     the base file
  -h, --help         show this text

Exit codes: 0 rendered, 1 a file could not be written, 2 usage, an unset
variable or a placeholder that remains.
EOF
}

## @description  Expands placeholders on a line from left to right without rescanning any value.
## @return       Sets RENDER_LINE, or RENDER_ERR on rejection. Returns 0, or 2 if a placeholder
##               is not in PX_*, is unclosed, or references an unset or empty variable.
render_line() {
  local rest=$1 out="" before name value
  RENDER_ERR=""
  while :; do
    case "$rest" in
    *"$RENDER_OPEN"*) ;;
    *)
      RENDER_LINE="$out$rest"
      return 0
      ;;
    esac
    before=${rest%%"$RENDER_OPEN"*}
    rest=${rest#*"$RENDER_OPEN"}
    out=$out$before
    case "$rest" in
    *'}'*) ;;
    *)
      RENDER_ERR="a placeholder is not closed"
      return "$IMG_EXIT_USAGE"
      ;;
    esac
    name=${rest%%'}'*}
    rest=${rest#*'}'}
    if ! [[ $name =~ $IMG_RE_PX_NAME ]]; then
      RENDER_ERR="\${$name} is not a PX_* placeholder"
      return "$IMG_EXIT_USAGE"
    fi
    value="$(img_var "$name")"
    if [ -z "$value" ]; then
      RENDER_ERR="$name is unset or empty"
      return "$IMG_EXIT_USAGE"
    fi
    out="$out$value"
  done
}

## @description  Renders a single template file from the given template path to the output path.
## @return       Returns 0 on success, 1 if the output file cannot be written, or 2 if a
##               placeholder is rejected or remains unexpanded in the output.
render_file() {
  local template=$1 out=$2 line lineno=0 hit
  : >"$out" || return "$IMG_EXIT_FAIL"
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    if ! render_line "$line"; then
      img_error "$template:$lineno: $RENDER_ERR. Nothing was rendered"
      return "$IMG_EXIT_USAGE"
    fi
    printf '%s\n' "$RENDER_LINE" >>"$out" || return "$IMG_EXIT_FAIL"
  done <"$template"
  hit="$(grep -n -F -e "$RENDER_OPEN" -e "\$PX_" "$out" | head -n 1 || true)"
  if [ -n "$hit" ]; then
    img_error "$template: a placeholder remains in the output at line ${hit%%:*}:" \
      "${hit#*:}. Nothing was rendered"
    return "$IMG_EXIT_USAGE"
  fi
  return 0
}

while [ "$#" -gt 0 ]; do
  case "$1" in
  -h | --help)
    usage
    exit 0
    ;;
  -o | --output)
    [ "$#" -ge 2 ] || img_die "$IMG_EXIT_USAGE" "$1 needs a directory"
    RENDER_OUT=$2
    shift
    ;;
  --overlay)
    [ "$#" -ge 2 ] || img_die "$IMG_EXIT_USAGE" "$1 needs a directory"
    RENDER_OVERLAY=$2
    shift
    ;;
  -*) img_die "$IMG_EXIT_USAGE" "unknown option: $1" ;;
  *) RENDER_TEMPLATES+=("$1") ;;
  esac
  shift
done

[ -n "$RENDER_OUT" ] || img_die "$IMG_EXIT_USAGE" "name the output directory with -o"
if [ -n "$RENDER_OVERLAY" ]; then
  [ -d "$RENDER_OVERLAY" ] ||
    img_die "$IMG_EXIT_USAGE" "the overlay directory $RENDER_OVERLAY does not exist"
  for overlay in "$RENDER_OVERLAY"/*.tmpl; do
    if [ -f "$overlay" ]; then
      RENDER_TEMPLATES+=("$overlay")
    fi
  done
fi
[ "${#RENDER_TEMPLATES[@]}" -gt 0 ] || img_die "$IMG_EXIT_USAGE" "name one template or more"
for template in "${RENDER_TEMPLATES[@]}"; do
  case "$template" in
  *.tmpl) ;;
  *) img_die "$IMG_EXIT_USAGE" "$template is not a .tmpl file" ;;
  esac
  [ -f "$template" ] || img_die "$IMG_EXIT_USAGE" "the template $template does not exist"
done

# Remove the temporary staging directory on any script exit so a failed render leaves no partial
# output behind.
trap '[ -z "$RENDER_TMP" ] || rm -rf "$RENDER_TMP"' EXIT
RENDER_TMP="$(mktemp -d "${TMPDIR:-/tmp}/px-render.XXXXXX")" ||
  img_die "$IMG_EXIT_FAIL" "cannot make a temporary directory"

# Templates are rendered in sequence so that any overlay output overwrites the matching base file
# inside the temporary staging directory before the final files are moved into place.
for template in "${RENDER_TEMPLATES[@]}"; do
  name=${template##*/}
  name=${name%.tmpl}
  render_file "$template" "$RENDER_TMP/$name" || exit $?
done

mkdir -p "$RENDER_OUT" || img_die "$IMG_EXIT_FAIL" "cannot make $RENDER_OUT"
for rendered in "$RENDER_TMP"/*; do
  mv -f "$rendered" "$RENDER_OUT/" ||
    img_die "$IMG_EXIT_FAIL" "cannot move ${rendered##*/} into $RENDER_OUT"
  img_debug "rendered $RENDER_OUT/${rendered##*/}"
done
exit 0
