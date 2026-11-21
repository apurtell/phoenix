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

# This script serves as the cluster-check runner for a single run, invoked by px-run after the
# report verb while the cluster is up. It performs three stages in sequence:
#   1. It merges the threshold definitions in precedence order: suites/thresholds.properties, any
#      PX_THRESHOLD_<KEY> values defined by a run class file or a configuration file, the key=value
#      lines emitted by the thresholds hook of each enabled plug-in in the order of PX_PLUGINS, and
#      finally any PX_THRESHOLD_<KEY> values supplied by the operator via the environment or the
#      command line. Each later assignment replaces the matching key while preserving all other
#      keys, so a plug-in overrides the run class just as in the configuration loader. The merged
#      threshold set is written to thresholds.properties inside the run bundle, and
#      PX_THRESHOLDS_FILE is exported to name that file.
#   2. It runs the built-in check subjects in suites/checks/cluster.sh, followed by the
#      steady-state checks in suites/checks/steady.sh.
#   3. It runs the executable files under checks/ for each enabled plug-in in name order, bounding
#      each execution by PX_TIMEOUT_VERB. Each script runs as a hook call named check so that
#      hooks.tsv of the plug-in records the invocation.
#
# Every check writes its own verdict rows. This runner writes a verdict row only when a check
# cannot run: it records an ERROR row under cluster.thresholds when the threshold set is invalid,
# and an ERROR row under cluster.checks, steady.checks, or plugin.<id>.<name> when a check script
# exits with a non-zero status. Because evaluating a check against an invalid threshold proves
# nothing, any bad threshold line stops all checks from running. See docs/bundle-format.md, section
# "The cluster checks", for the full specification.

CC_HOME="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# shellcheck source=SCRIPTDIR/../lib/plugin.sh
. "$CC_HOME/lib/plugin.sh"

set -euo pipefail
px_trap_enable

# A threshold key consists of lowercase words joined by dots, matching the format of the base file.
CC_KEY_RE='^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$'

px_cli_spec <<'EOF'
about Runs the cluster checks of the run that PX_RUN_DIR names: the built-in
+ subjects of suites/checks/cluster.sh and steady.sh, then the checks/ scripts of
+ each enabled plug-in. Each check writes verdict rows to verdicts.tsv of the run.
about Environment: PX_RUN_DIR (required), PX_SCOPE_ID (empty skips the live
+ subjects), PX_BACKEND (default compose), PX_PLUGINS (the plug-ins whose
+ thresholds and checks apply). px-run passes its configuration files with
+ --config.
exits 1 also means that a check could not run; 2 also an unset PX_RUN_DIR or a
+ threshold line that is not key=number.
EOF

# Merges threshold definitions into FILE by layering the base file, the run configuration, each
# plug-in threshold line, and the operator values in order so that the last value of a key wins.
## @description  Merges all threshold layers and writes the resulting threshold set to FILE.
## @return       Exits 0 on success, 1 if the base file is absent or a hook failed, or 2 if a line
##               is not in key=number format.
cc_thresholds_merge() {
  local target=$1 base="$CC_HOME/suites/thresholds.properties" lines="" plugin="" line id kv
  local status=0 out
  CC_REASON=""
  if [ ! -f "$base" ]; then
    CC_REASON="the base threshold file $base is absent"
    return "$PX_EXIT_FAIL"
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
    '' | '#'*) continue ;;
    esac
    cc_threshold_line "$line" "suites/thresholds.properties" || return
    lines="$lines-$PX_TAB$line$PX_NL"
  done <"$base"
  out="$(pl_hook_run thresholds "$PX_RUN_DIR" || exit "$?")" || status=$?
  if [ "$status" != 0 ]; then
    CC_REASON="the thresholds hook failed with exit $status"
    return "$PX_EXIT_FAIL"
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    id=${line%%"$PX_TAB"*}
    kv=${line#*"$PX_TAB"}
    cc_threshold_line "$kv" "the thresholds hook of plug-in $id" || return
    plugin="${plugin}plugin:$id$PX_TAB$kv$PX_NL"
  done <<EOF
$out
EOF
  cc_thresholds_config "$lines$plugin" || return
  lines="$lines$CC_CONFIG$plugin$CC_OPERATOR"
  printf '%s' "$lines" | awk -F '\t' '
    BEGIN {
      print "# The cluster-check thresholds of this run: suites/thresholds.properties"
      print "# with the plug-in overlays. suites/check-cluster.sh writes this file."
    }
    {
      key = substr($2, 1, index($2, "=") - 1)
      if (!(key in value)) order[++n] = key
      value[key] = $2
      origin[key] = $1
    }
    END {
      for (i = 1; i <= n; i++) {
        if (origin[order[i]] != "-") print "# " origin[order[i]]
        print value[order[i]]
      }
    }' >"$target.tmp" && mv -f "$target.tmp" "$target" && return 0
  rm -f "$target.tmp"
  CC_REASON="cannot write $target"
  return "$PX_EXIT_FAIL"
}

# Populates CC_CONFIG with the run configuration lines and CC_OPERATOR with the operator lines by
# matching each PX_THRESHOLD_<KEY> variable against the merged lines, with dots in each key mapped
# to underscores.
## @description  Populates CC_CONFIG and CC_OPERATOR from the PX_THRESHOLD_<KEY> variables.
## @return       Exits 0 on success, or 2 with CC_REASON set when a value is not a number or a name
##               matches no key.
cc_thresholds_config() {
  local keys name key line found origin
  CC_CONFIG=""
  CC_OPERATOR=""
  keys="$(printf '%s' "$1" | awk -F '\t' '{ k = substr($2, 1, index($2, "=") - 1)
    if (!(k in s)) { s[k] = 1; print k } }')"
  # A variable name that fails to match any known key indicates a configuration typo and must be
  # rejected so that the target threshold does not silently remain at its default value.
  for name in ${!PX_THRESHOLD_@}; do
    [ -n "${!name:-}" ] || continue
    found=""
    while IFS= read -r key; do
      [ -n "$key" ] || continue
      line="$(px_upper "$key")"
      [ "PX_THRESHOLD_$line" = "$name" ] || continue
      found=$key
      break
    done <<EOF
$keys
EOF
    if [ -z "$found" ]; then
      CC_REASON="$name names no threshold key of the base file or of a plug-in"
      return "$PX_EXIT_USAGE"
    fi
    cc_threshold_line "$found=${!name}" "$name" || return
    line="config:$name$PX_TAB$found=${!name}$PX_NL"
    # Values supplied via the environment or the command line represent explicit operator overrides
    # that take precedence over plug-ins, whereas values from a run class file or a configuration
    # file do not.
    origin="$(px_config_origin "$name" || true)"
    case "$origin" in
    environment | cli) CC_OPERATOR="$CC_OPERATOR$line" ;;
    *) CC_CONFIG="$CC_CONFIG$line" ;;
    esac
  done
}

## @description  Validates one threshold line (line, origin) against the key=number format, setting
##               CC_REASON when validation fails.
## @return       Exits 0 for a valid line, or 2 otherwise.
cc_threshold_line() {
  local key=${1%%=*} value=${1#*=}
  if [ "$key" != "$1" ] && [[ $key =~ $CC_KEY_RE ]] && [[ $value =~ $PX_RE_NUMBER ]]; then
    return 0
  fi
  CC_REASON="$2 holds '$1', which is not key=number"
  return "$PX_EXIT_USAGE"
}

## @description  Executes one plug-in check script (id, directory, script) as a check hook call
##               bounded by PX_TIMEOUT_VERB, recording an error verdict if the script cannot run.
## @return       Exits 0 when the script exits with status 0, or 1 otherwise.
cc_plugin_check() {
  local id=$1 dir=$2 script=$3 name=${3##*/} bound status=0
  bound="$(px_int_setting PX_TIMEOUT_VERB 300 1)" || exit $?
  pl_hook_call "$id" "$dir" check "$PX_RUN_DIR" "$bound" "$script" </dev/null >&2 || status=$?
  [ "$status" != 0 ] || return 0
  sx_verdict "plugin.$id.$name" ERROR "the check script $name of plug-in $id could not run:" \
    "exit $status"
  return "$PX_EXIT_FAIL"
}

px_cli_parse "$@"
# Load configuration files before validating PX_RUN_DIR so that --strict rejects any undeclared
# configuration variable name first.
px_config_load -- ${PX_CLI_FILES[@]+"${PX_CLI_FILES[@]}"}
[ -n "${PX_RUN_DIR:-}" ] ||
  px_die "$PX_EXIT_USAGE" "PX_RUN_DIR is unset: name the bundle of the run"
PX_RUN_DIR="$(px_out_resolve "$PX_RUN_DIR")" || exit $?
[ -d "$PX_RUN_DIR" ] || px_die "$PX_EXIT_USAGE" "PX_RUN_DIR names no directory: $PX_RUN_DIR"
export PX_RUN_DIR PX_RUN_CLASS="${PX_RUN_CLASS:-}" PX_CONTAMINATION="${PX_CONTAMINATION:-}"
export PX_SCOPE_ID="${PX_SCOPE_ID:-}" PX_BACKEND="${PX_BACKEND:-compose}"
export PX_RECORD_SOURCE=check:cluster
export PX_TIMEOUT_VERB

# Resolve the enabled plug-in list once up front so that threshold merging and check execution
# operate on an identical set. An invalid or refused plug-in set is treated as a run usage error.
CC_STATUS=0
CC_PLUGINS="$(pl_enabled || exit "$?")" || CC_STATUS=$?
if px_is_dry_run; then
  [ "$CC_STATUS" = 0 ] || exit "$CC_STATUS"
  px_plan thresholds "$PX_RUN_DIR/thresholds.properties"
  px_plan check suites/checks/cluster.sh
  px_plan check suites/checks/steady.sh
  while IFS="$PX_TAB" read -r CC_ID CC_DIR; do
    [ -n "$CC_ID" ] || continue
    for CC_SCRIPT in "$CC_DIR"/checks/*; do
      [ -f "$CC_SCRIPT" ] && [ -x "$CC_SCRIPT" ] || continue
      px_plan check "$CC_SCRIPT"
    done
  done <<EOF
$CC_PLUGINS
EOF
  exit "$PX_EXIT_OK"
fi
if [ "$CC_STATUS" != 0 ]; then
  sx_verdict cluster.thresholds ERROR "the enabled plug-ins cannot be read: exit $CC_STATUS," \
    "so no check runs"
  exit "$PX_EXIT_USAGE"
fi

PX_THRESHOLDS_FILE="$PX_RUN_DIR/thresholds.properties"
export PX_THRESHOLDS_FILE
cc_thresholds_merge "$PX_THRESHOLDS_FILE" || CC_STATUS=$?
if [ "$CC_STATUS" != 0 ]; then
  sx_verdict cluster.thresholds ERROR "$CC_REASON, so no check runs"
  exit "$CC_STATUS"
fi

CC_RESULT=0
CC_STATUS=0
for CC_NAME in cluster steady; do
  CC_STATUS=0
  "${BASH:-bash}" "$CC_HOME/suites/checks/$CC_NAME.sh" </dev/null >&2 || CC_STATUS=$?
  if [ "$CC_STATUS" != 0 ]; then
    sx_verdict "$CC_NAME.checks" ERROR "suites/checks/$CC_NAME.sh could not run: exit $CC_STATUS"
    CC_RESULT=$PX_EXIT_FAIL
  fi
done

while IFS="$PX_TAB" read -r CC_ID CC_DIR; do
  [ -n "$CC_ID" ] || continue
  for CC_SCRIPT in "$CC_DIR"/checks/*; do
    [ -f "$CC_SCRIPT" ] && [ -x "$CC_SCRIPT" ] || continue
    cc_plugin_check "$CC_ID" "$CC_DIR" "$CC_SCRIPT" || CC_RESULT=$PX_EXIT_FAIL
  done
done <<EOF
$CC_PLUGINS
EOF
exit "$CC_RESULT"
