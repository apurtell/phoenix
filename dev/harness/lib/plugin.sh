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

# This library implements the plug-in loader specified in docs/PLUGINS.md. Every call site that
# interacts with a plug-in uses this file, including the configuration loader, the Compose
# generator, px-suite, px-run, and suites/check-cluster.sh, ensuring that discovery, enablement,
# and hook execution share a single implementation.
#
# Four design rules govern this module:
#   1. A manifest is treated strictly as text. The loader reads plugin.env line by line and never
#      sources it, ensuring that a manifest cannot execute a command.
#   2. Discovery enables nothing on its own. Only PX_PLUGINS enables a plug-in, so a directory that
#      merely happens to exist cannot alter a measurement.
#   3. A refusal reports the directory and the cause and returns exit 2, because a plug-in that
#      fails to load represents a defect in the setup rather than a test failure.
#   4. A hook execution is time-bounded, runs inside its plug-in directory, and writes only into
#      PX_HOOK_OUT, appending one row to hooks.tsv in that directory for each invocation.

# This file is a library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/plugin.sh is a library. Source it after lib/common.sh.\n' >&2
  exit 2
fi

# Guard against multiple inclusion so sourcing this file again does not redefine its symbols.
if [ -n "${PL_LOADED:-}" ]; then
  return 0
fi
PL_LOADED=1

PL_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# Load the bundle library, which defines the contract version and transitively sources
# lib/common.sh along with the verdict writer.
# shellcheck source=SCRIPTDIR/bundle.sh
. "$PL_LIB_DIR/bundle.sh"

# Regular expression defining the valid syntax for a plug-in identifier.
PL_ID_RE='^[a-z][a-z0-9-]{0,31}$'

# The nine supported lifecycle hooks, listed in the order specified by docs/PLUGINS.md.
PL_HOOKS="conf-overlay jvm-opts image-check fetch-data pre-workload post-workload suites"
PL_HOOKS="$PL_HOOKS thresholds collect"

# The sizing table role keys and the four variable name suffixes a manifest may set for each role.
PL_ROLE_KEYS=$PX_SIZING_KEYS
PL_ROLE_NAMES="HEAP_MIB MEM_MIB DIRECT_MIB JVM_OPTS"

## @description  Determines the directory containing the built-in drivers from PX_DRIVERS_DIR,
##               resolving relative paths against PX_HOME and defaulting to drivers.
## @return       Writes the resolved directory path to stdout and returns exit code 0.
pl_drivers_dir() {
  local dir=${PX_DRIVERS_DIR:-drivers}
  case "$dir" in
  /*) ;;
  *) dir="$PX_HOME/$dir" ;;
  esac
  printf '%s\n' "$dir"
}

# A driver specification (spec) is resolved first as an explicit file path when given as one, next
# as a built-in driver at <dir>/<spec>/driver.sh, and finally as the driver of the enabled plug-in.
## @description  Resolves a driver specification (spec) to an absolute executable program path.
## @return       Writes the path to stdout and exits 0; exits 2 if no executable program exists, or
##               exits 1 if the real path cannot be resolved.
pl_driver_resolve() {
  local spec=${1:-} dir path
  case "$spec" in
  */*) path=$spec ;;
  *)
    dir="$(pl_drivers_dir)"
    path="$dir/$spec/driver.sh"
    [ -f "$path" ] || path="$(pl_driver "$spec" 2>/dev/null || true)"
    ;;
  esac
  if [ -z "$path" ] || [ ! -f "$path" ] || [ ! -x "$path" ]; then
    case "$spec" in
    */*) px_error "no driver '$spec': no executable program at $spec" ;;
    *)
      px_error "no driver '$spec': no executable $dir/$spec/driver.sh and no enabled" \
        "plug-in with that id"
      ;;
    esac
    return "$PX_EXIT_USAGE"
  fi
  px_realpath "$path" || return "$PX_EXIT_FAIL"
}

# When the driver specification is not a path, the specification itself becomes the name; otherwise
# the parent directory name is used for a driver.sh entry point and the file name without its
# extension is used for any other executable, sanitized for use as a single path component.
## @description  Derives a single-segment-safe driver name from the given (spec, path) pair.
## @return       Writes the sanitized driver name to stdout and returns exit code 0.
pl_driver_name() {
  local name=${1:-} base
  case "$name" in
  */*)
    base=${2##*/}
    if [ "$base" = driver.sh ]; then
      name=${2%/*}
      name=${name##*/}
    else
      name=${base%.*}
    fi
    ;;
  esac
  printf '%s\n' "${name//[!A-Za-z0-9_.-]/-}"
}

## @description  Lists the names of all built-in drivers by scanning the drivers directory for
##               subdirectories that contain a driver.sh entry point.
## @return       Writes one driver name per line to stdout and returns exit code 0.
pl_builtin_drivers() {
  local dir path
  dir="$(pl_drivers_dir)"
  for path in "$dir"/*/driver.sh; do
    [ -f "$path" ] || continue
    path=${path%/driver.sh}
    printf '%s\n' "${path##*/}"
  done
}

## @description  Determines whether a manifest key corresponds to a per-role sizing or JVM setting.
## @return       Returns exit code 0 when the key matches a role setting, or exit code 1 otherwise.
pl_is_role_key() {
  local key=${1:-} role name
  for role in $PL_ROLE_KEYS; do
    for name in $PL_ROLE_NAMES; do
      [ "$key" != "PX_${role}_$name" ] || return 0
    done
  done
  return 1
}

## @description  Parses and validates the manifest of a single plug-in directory (absolute dir).
## @return       Writes "key<TAB>value" rows to stdout and exits 0, or exits 2 after reporting the
##               validation failure on stderr.
pl_manifest() {
  local dir=$1 file="$1/plugin.env" line key value lineno=0 rows="" seen=" " hooks="" id
  local contract="" driver="" hook
  if [ ! -f "$file" ]; then
    px_error "$dir holds no plugin.env"
    return "$PX_EXIT_USAGE"
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    line=${line%$'\r'}
    px_trim_var line
    case "$line" in
    '' | '#'*) continue ;;
    esac
    key=${line%%=*}
    if [ "$key" = "$line" ]; then
      px_error "$file line $lineno: not a KEY=VALUE line: $line"
      return "$PX_EXIT_USAGE"
    fi
    value=${line#*=}
    px_trim_var key
    px_trim_var value
    case "$value" in
    \"*\") value=${value#\"} && value=${value%\"} ;;
    \'*\') value=${value#\'} && value=${value%\'} ;;
    esac
    case "$key" in
    PX_PLUGIN_ID | PX_PLUGIN_CONTRACT | PX_PLUGIN_DRIVER | PX_PLUGIN_HOOKS) ;;
    *)
      if ! pl_is_role_key "$key"; then
        px_error "$file line $lineno: $key is not a plug-in key. docs/PLUGINS.md lists them"
        return "$PX_EXIT_USAGE"
      fi
      ;;
    esac
    # Allow the hook list to span multiple lines so that a manifest file can remain within the
    # column limit, whereas every other manifest key must be defined at most once.
    if [ "$key" = PX_PLUGIN_HOOKS ]; then
      hooks="${hooks:+$hooks }$value"
      continue
    fi
    case "$seen" in
    *" $key "*)
      px_error "$file line $lineno: $key is set twice"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    seen="$seen$key "
    case "$value" in
    *$'\t'*)
      px_error "$file line $lineno: the value of $key holds a tab"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    case "$key" in
    PX_PLUGIN_ID) id=$value ;;
    PX_PLUGIN_CONTRACT) contract=$value ;;
    PX_PLUGIN_DRIVER) driver=$value ;;
    *_MIB)
      if ! [[ $value =~ ^[1-9][0-9]*$ ]]; then
        px_error "$file line $lineno: $key needs a whole number of MiB, got '$value'"
        return "$PX_EXIT_USAGE"
      fi
      ;;
    esac
    rows="$rows$key$PX_TAB$value$PX_NL"
  done <"$file"
  for key in PX_PLUGIN_ID PX_PLUGIN_CONTRACT; do
    case "$seen" in
    *" $key "*) ;;
    *)
      px_error "$file lacks $key"
      return "$PX_EXIT_USAGE"
      ;;
    esac
  done
  if ! [[ $id =~ $PL_ID_RE ]]; then
    px_error "$file: the plug-in id '$id' does not match $PL_ID_RE"
    return "$PX_EXIT_USAGE"
  fi
  if [ "$id" != "${dir##*/}" ]; then
    px_error "$file: the plug-in id '$id' differs from the directory name ${dir##*/}"
    return "$PX_EXIT_USAGE"
  fi
  if [ "$contract" != "$PB_CONTRACT_VERSION" ]; then
    px_error "plug-in $id implements contract $contract," \
      "this harness implements $PB_CONTRACT_VERSION"
    return "$PX_EXIT_USAGE"
  fi
  if [ -n "$driver" ]; then
    case "/$driver/" in
    //* | */../*)
      px_error "$file: PX_PLUGIN_DRIVER '$driver' is not a path inside the plug-in"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    if [ ! -f "$dir/$driver" ] || [ ! -x "$dir/$driver" ]; then
      px_error "$file: PX_PLUGIN_DRIVER names no executable file $dir/$driver"
      return "$PX_EXIT_USAGE"
    fi
  fi
  for hook in $hooks; do
    case " $PL_HOOKS " in
    *" $hook "*) ;;
    *)
      px_error "$file: '$hook' is not a hook. The hooks are: $PL_HOOKS"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    if [ ! -f "$dir/hooks/$hook" ] || [ ! -x "$dir/hooks/$hook" ]; then
      px_error "$file registers the hook $hook, but $dir/hooks/$hook is not executable"
      return "$PX_EXIT_USAGE"
    fi
  done
  [ -z "$hooks" ] || rows="${rows}PX_PLUGIN_HOOKS$PX_TAB$hooks$PX_NL"
  printf '%s' "$rows"
}

## @description  Discovers every plug-in by scanning the default directory and then each entry in
##               PX_PLUGIN_DIRS.
## @return       Writes "id<TAB>dir" rows to stdout and exits 0, or exits 2 on a refused plug-in.
pl_discover() {
  local dir entry rows="" id manifest builtin
  local -a dirs=() entries=()
  for dir in "$PX_HOME/plugins"/*; do
    [ -f "$dir/plugin.env" ] || continue
    dirs+=("$dir")
  done
  IFS=: read -r -a entries <<<"${PX_PLUGIN_DIRS:-}" || true
  for entry in ${entries[@]+"${entries[@]}"}; do
    [ -n "$entry" ] || continue
    if [ ! -f "$entry/plugin.env" ]; then
      px_error "PX_PLUGIN_DIRS names $entry, which holds no plugin.env"
      return "$PX_EXIT_USAGE"
    fi
    dirs+=("$entry")
  done
  builtin=" $(pl_builtin_drivers | tr '\n' ' ') "
  for dir in ${dirs[@]+"${dirs[@]}"}; do
    dir="$(cd -P "$dir" && pwd -P)" || {
      px_error "cannot read the plug-in directory $dir"
      return "$PX_EXIT_USAGE"
    }
    manifest="$(pl_manifest "$dir" || exit "$?")" || return "$PX_EXIT_USAGE"
    id="$(px_tsv_get PX_PLUGIN_ID "$manifest" || true)"
    case "$builtin" in
    *" $id "*)
      px_error "plug-in $id in $dir has the name of a built-in driver"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    case "$PX_NL$rows" in
    *"$PX_NL$id$PX_TAB"*)
      px_error "two plug-ins have the id $id: $dir and" \
        "$(printf '%s' "$rows" | awk -F '\t' -v i="$id" '$1 == i { print $2 }')"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    rows="$rows$id$PX_TAB$dir$PX_NL"
  done
  printf '%s' "$rows"
}

## @description  Resolves the enabled plug-ins in the order their identifiers appear in PX_PLUGINS.
## @return       Writes "id<TAB>dir" rows to stdout and exits 0, or exits 2 on an unknown or
##               repeated identifier or a refused plug-in.
pl_enabled() {
  local found id dir seen=" "
  found="$(pl_discover || exit "$?")" || return "$PX_EXIT_USAGE"
  for id in ${PX_PLUGINS:-}; do
    case "$seen" in
    *" $id "*)
      px_error "PX_PLUGINS names the plug-in $id twice"
      return "$PX_EXIT_USAGE"
      ;;
    esac
    seen="$seen$id "
    dir="$(px_tsv_get "$id" "$found" || true)"
    if [ -z "$dir" ]; then
      px_error "PX_PLUGINS names the plug-in $id, which discovery did not find in" \
        "$PX_HOME/plugins or PX_PLUGIN_DIRS"
      return "$PX_EXIT_USAGE"
    fi
    printf '%s\t%s\n' "$id" "$dir"
  done
}

## @description  Retrieves a single manifest value (id, key) for a discovered plug-in.
## @return       Writes the value to stdout and exits 0; exits 6 if the plug-in or key does not
##               exist, or exits 2 if a plug-in is refused.
pl_value() {
  local id=${1:-} key=${2:-} found dir manifest
  found="$(pl_discover || exit "$?")" || return "$PX_EXIT_USAGE"
  dir="$(px_tsv_get "$id" "$found" || true)"
  [ -n "$dir" ] || return "$PX_EXIT_NO_OBJECT"
  manifest="$(pl_manifest "$dir" || exit "$?")" || return "$PX_EXIT_USAGE"
  px_tsv_get "$key" "$manifest"
}

## @description  Resolves the driver executable for the enabled plug-in identified by NAME.
## @return       Writes the absolute path to stdout and exits 0; exits 6 if no such enabled plug-in
##               or driver exists, or exits 2 on a refused plug-in set.
pl_driver() {
  local name=${1:-} rows dir driver
  rows="$(pl_enabled || exit "$?")" || return "$PX_EXIT_USAGE"
  dir="$(px_tsv_get "$name" "$rows" || true)"
  [ -n "$dir" ] || return "$PX_EXIT_NO_OBJECT"
  driver="$(pl_value "$name" PX_PLUGIN_DRIVER || true)"
  [ -n "$driver" ] || return "$PX_EXIT_NO_OBJECT"
  printf '%s/%s\n' "$dir" "$driver"
}

## @description  Checks whether the specified plug-in (id) registers the given lifecycle hook.
## @return       Returns exit code 0 when the hook is registered, or exit code 1 otherwise.
pl_registers() {
  local hooks
  hooks="$(pl_value "${1:-}" PX_PLUGIN_HOOKS || true)"
  case " $hooks " in
  *" ${2:-} "*) return 0 ;;
  esac
  return 1
}

# Executes the target program from dir with PX_HOOK_OUT set to base/plugins/<id> and appends an
# execution record row to hooks.tsv.
## @description  Invokes a plug-in hook program (id, dir, hook, base, timeout_s, program, arg...).
## @return       Returns the program exit status, exit 4 if it exceeds its time bound, or exit 1
##               when PX_HOOK_OUT cannot be created.
pl_hook_call() {
  local id=$1 dir=$2 hook=$3 base=$4 timeout=$5 program=$6 out started status=0
  shift 6
  out="$base/plugins/$id"
  mkdir -p "$out" || {
    px_error "cannot create $out for the $hook hook of plug-in $id"
    return "$PX_EXIT_FAIL"
  }
  started="$(px_epoch_ms)"
  (
    cd "$dir" || exit "$PX_EXIT_FAIL"
    px_timeout "$timeout" env "PX_PLUGIN_ID=$id" "PX_PLUGIN_DIR=$dir" "PX_HOOK=$hook" \
      "PX_HOOK_OUT=$out" "PX_RECORD_SOURCE=plugin:$id" "PX_HOME=$PX_HOME" "$program" "$@"
  ) || status=$?
  # A hook that exceeds its time bound and terminates via a TERM or KILL signal is recorded as a
  # failure with the timeout exit code.
  case "$status" in
  124 | 137) status=$PX_EXIT_TIMEOUT ;;
  esac
  printf '%s\t%s\t%s\t%s\n' "$started" "$hook" "$status" "$(($(px_epoch_ms) - started))" \
    >>"$out/hooks.tsv" || px_warn "cannot append to $out/hooks.tsv"
  [ "$status" = 0 ] || px_warn "the $hook hook of plug-in $id ended with exit $status"
  return "$status"
}

# Invokes the hook across enabled plug-ins in PX_PLUGINS order and prefixes each stdout line with
# the plug-in id followed by a tab character.
## @description  Runs a lifecycle hook (hook, base, arg...) for each registering enabled plug-in.
## @return       Exits 0, returns the first non-zero status after all calls complete, or exits 2 on
##               a refused plug-in set.
pl_hook_run() {
  local hook=${1:-} base=${2:-} rows id dir timeout out status first=0
  shift 2
  case " $PL_HOOKS " in
  *" $hook "*) ;;
  *) px_die "$PX_EXIT_USAGE" "pl_hook_run: '$hook' is not a hook" ;;
  esac
  [ -n "$base" ] || px_die "$PX_EXIT_USAGE" "pl_hook_run: no output base for the $hook hook"
  timeout=${PX_TIMEOUT_VERB:-300}
  [ "$hook" != fetch-data ] || timeout=${PX_TIMEOUT_FETCH:-1800}
  rows="$(pl_enabled || exit "$?")" || return "$PX_EXIT_USAGE"
  while IFS="$PX_TAB" read -r id dir; do
    [ -n "$id" ] || continue
    pl_registers "$id" "$hook" || continue
    status=0
    out="$(pl_hook_call "$id" "$dir" "$hook" "$base" "$timeout" "$dir/hooks/$hook" "$@" ||
      exit "$?")" || status=$?
    [ -z "$out" ] || printf '%s\n' "$out" | awk -v i="$id" '{ print i "\t" $0 }'
    [ "$status" = 0 ] || [ "$first" != 0 ] || first=$status
  done <<EOF
$rows
EOF
  return "$first"
}

# Applies the sizing and JVM settings from each enabled plug-in with the origin recorded as
# plugin:<id>, giving precedence to plug-ins listed later.
## @description  Executes the plug-in stage of the configuration loader across enabled plug-ins.
## @return       Returns exit code 0 on success, or exits with code 2 on a refused plug-in set.
pl_config_stage() {
  local rows line id dir manifest key value
  local -a enabled=()
  [ -n "${PX_PLUGINS:-}" ] || return 0
  rows="$(pl_enabled || exit "$?")" ||
    px_die "$PX_EXIT_USAGE" "the plug-ins of PX_PLUGINS do not load"
  while IFS= read -r line; do
    [ -z "$line" ] || enabled=("$line" ${enabled[@]+"${enabled[@]}"})
  done <<EOF
$rows
EOF
  # Because the configuration loader retains the first value assigned to each key, iterate in
  # reverse so that the last plug-in listed in PX_PLUGINS is applied first and takes precedence.
  for line in ${enabled[@]+"${enabled[@]}"}; do
    id=${line%%"$PX_TAB"*}
    dir=${line#*"$PX_TAB"}
    manifest="$(pl_manifest "$dir" || exit "$?")" ||
      px_die "$PX_EXIT_USAGE" "the manifest of plug-in $id does not load"
    while IFS="$PX_TAB" read -r key value; do
      [ -n "$key" ] || continue
      pl_is_role_key "$key" || continue
      px_config_set "$key" "$value" "plugin:$id"
    done <<EOF
$manifest
EOF
  done
}
