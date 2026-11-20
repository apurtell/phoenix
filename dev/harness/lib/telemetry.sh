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

# This library provides the shared front end for the px-profile, px-jfr, and px-trace-count
# telemetry wrappers. The current round implements the command-line surface, target resolution, and
# artifact naming conventions, whereas Round A6 attaches each underlying tool to the target roles.
# Each wrapper script configures the TM_* contract constants, defines its usage function, and
# delegates execution to tm_main.
#
# Because telemetry collection provides diagnostic evidence rather than acting as an execution gate,
# a host without the requested tool records a skip entry for every target and exits successfully.
#
# Artifact naming follows a two-level layout under the output root. The index file at
# <out>/<kind>/index.tsv records one row per target with the tool, target, status, artifact, and
# detail columns. Individual artifacts are written to
# <out>/<kind>/<role>-<index>/<name>-<session>.<ext>, where the session tag is the run identifier so
# that every artifact from a single run sorts together and separate runs cannot overwrite each
# other.
#
# A wrapper script customizes this front end by defining the TM_* constants before calling tm_main.
# TM_KIND names the artifact directory (profiles, jfr, or traces), and TM_INDEX_TOOL sets the tool
# column written to the index. TM_TOOL gives the program name for a PATH lookup, TM_STAGED gives the
# relative path of the staged tool beneath PX_TOOLKIT_DIR, TM_TEST selects the file test (-x for a
# program or -f for an agent file), and TM_PATH_FALLBACK is set to 1 when a copy found on PATH is
# acceptable. TM_LIST_CMD names the subcommand that prints TM_LIST, TM_OPT names the option that
# selects one word from TM_LIST, TM_LIST enumerates the accepted words, and TM_DEFAULT sets the
# default word. TM_EXT defines the artifact file extension unless the tool supports selectable
# output formats, in which case TM_FORMATS and tm_extension replace it. Finally, TM_ENV_TARGETS and
# TM_ENV_DURATION name the configuration variables for the default targets and default duration,
# TM_SKIP and TM_WARN define the skip-row detail and warning message, and TM_A6 defines the message
# reported for the unsupported record path.

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/telemetry.sh is a library. Source it.\n' >&2
  exit 2
fi

TM_ROWS=""
TM_TOOL_PATH=""
TM_FORMATS="${TM_FORMATS:-}"

## @description  Populates TM_ROWS with the role and index rows parsed from a target specification
##               delimited by commas or spaces, defaulting an omitted target index to 0.
## @return       Exits 0 on success, or exits 2 if a target is malformed or no target is given.
tm_parse_targets() {
  local item role index
  TM_ROWS=""
  for item in ${1//,/ }; do
    role=${item%%/*}
    index=${item#*/}
    [ "$index" != "$item" ] || index=0
    if [ -z "$role" ] || ! [[ $index =~ $PX_RE_INT ]]; then
      px_usage_error "a target is ROLE or ROLE/INDEX, got '$item'"
    fi
    TM_ROWS="$TM_ROWS$role$PX_TAB$index$PX_NL"
  done
  [ -n "$TM_ROWS" ] || px_usage_error "name a target with --target, or set $TM_ENV_TARGETS"
}

## @description  Validates that the given value for an option (option, value, words) matches one of
##               the allowed words and rejects any value outside that set.
## @return       Returns 0 when the value is valid, or exits 2 on any other value.
tm_check() {
  case " $3 " in
  *" $2 "*) ;;
  *) px_usage_error "$1 accepts $3, got '$2'" ;;
  esac
}

# Because a run must execute the exact tool version that it recorded, the staged toolkit copy
# always takes precedence over any installation on PATH.
## @description  Resolves TM_TOOL_PATH to the staged tool, or falls back to a copy on PATH when the
##               wrapper allows a PATH fallback.
## @return       Returns 0 when the tool is resolved, or returns 6 when the tool is absent.
tm_find_tool() {
  local staged="${PX_TOOLKIT_DIR:-}/$TM_STAGED"
  TM_TOOL_PATH=""
  if [ -n "${PX_TOOLKIT_DIR:-}" ] && test "$TM_TEST" "$staged"; then
    TM_TOOL_PATH="$staged"
    return 0
  fi
  if [ "$TM_PATH_FALLBACK" = 1 ] && command -v "$TM_TOOL" >/dev/null 2>&1; then
    TM_TOOL_PATH="$TM_TOOL"
    return 0
  fi
  return "$PX_EXIT_NO_OBJECT"
}

## @description  Emits one plan row per target to stdout or appends one skip row per target to the
##               index file for the given arguments (base directory, plan or skip).
## @return       Returns 0 after writing the target rows.
tm_rows() {
  local base=$1 row role index
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    role="$(px_tsv_field 1 "$row")"
    index="$(px_tsv_field 2 "$row")"
    if [ "$2" = plan ]; then
      px_tsv_row "$role" "$index" "$TM_NAME" "$TM_DURATION" \
        "$base/$role-$index/$TM_NAME-$TM_SESSION.$TM_EXT"
    else
      px_tsv_row "$TM_INDEX_TOOL" "$role/$index" skip "" "$TM_SKIP" >>"$base/index.tsv"
    fi
  done <<EOF
$TM_ROWS
EOF
}

## @description  Parses command-line arguments and executes the plan, record, or list subcommand.
## @return       Exits 0 when completed or skipped, 1 on failure, 2 on usage error, or 5 while
##               round A6 owns the record subcommand.
tm_main() {
  local action="" spec="" name="" format="" duration="" out="" base dir word
  local -a configs=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    -v | --verbose) px_config_set PX_VERBOSE 1 cli ;;
    --target) px_cli_need_value "$@" && spec="$spec $2" && shift ;;
    "$TM_OPT") px_cli_need_value "$@" && name="$2" && shift ;;
    --duration) px_cli_need_value "$@" && duration="$2" && shift ;;
    --out) px_cli_need_value "$@" && out="$2" && shift ;;
    --config) px_cli_need_value "$@" && configs+=("$2") && shift ;;
    --format)
      [ -n "$TM_FORMATS" ] || px_usage_error "unknown option: $1"
      px_cli_need_value "$@" && format="$2" && shift
      ;;
    -*) px_usage_error "unknown option: $1" ;;
    *)
      [ -z "$action" ] || px_usage_error "unexpected argument: $1"
      action=$1
      ;;
    esac
    shift
  done
  case "${action:=plan}" in
  plan | record) ;;
  "$TM_LIST_CMD")
    for word in $TM_LIST; do
      printf '%s\n' "$word"
    done
    exit 0
    ;;
  *) px_usage_error "unknown subcommand: $action" ;;
  esac
  px_config_load ${configs[@]+"${configs[@]}"}
  TM_NAME="${name:-$TM_DEFAULT}"
  TM_DURATION="${duration:-${!TM_ENV_DURATION:-60}}"
  tm_check "$TM_OPT" "$TM_NAME" "$TM_LIST"
  if [ -n "$TM_FORMATS" ]; then
    format="${format:-${TM_FORMATS%% *}}"
    tm_check --format "$format" "$TM_FORMATS"
    tm_extension "$format"
  fi
  if ! [[ $TM_DURATION =~ $PX_RE_INT ]] || [ "$TM_DURATION" -lt 1 ]; then
    px_usage_error "--duration needs a whole number of seconds, got '$TM_DURATION'"
  fi
  tm_parse_targets "${spec:-${!TM_ENV_TARGETS:-}}"
  # Reuse an existing run identifier when one is present in the environment so that generated
  # artifacts and the run manifest record the same session identifier.
  TM_SESSION="${PX_RUN_ID:-$(px_run_id)}"
  # Evaluate each step of the output directory policy in the current shell rather than inside a
  # command argument so that a policy refusal terminates the process instead of only a subshell.
  if [ -n "$out" ]; then
    base="$out/$TM_KIND"
  elif [ -n "${PX_RUN_DIR:-}" ]; then
    base="$PX_RUN_DIR/$TM_KIND"
  elif [ "$action" = record ]; then
    base="$(px_run_dir)" || exit $?
    base="$base/$TM_KIND"
  else
    base="$(px_out_root)" || exit $?
    base="$base/$TM_KIND"
  fi
  if [ "$action" = plan ]; then
    dir="$(px_out_resolve "$base")" || exit $?
    tm_rows "$dir" plan
    exit 0
  fi
  dir="$(px_out_subdir "$base")" || exit $?
  if ! tm_find_tool; then
    tm_rows "$dir" skip
    px_warn "$TM_WARN"
    exit 0
  fi
  px_debug "the tool is $TM_TOOL_PATH"
  px_die "$PX_EXIT_UNSUPPORTED" "$TM_A6"
}
