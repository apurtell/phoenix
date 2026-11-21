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

# Shared driver kit providing common helper functions for built-in drivers, which plug-in drivers
# may also source. The formal specification lives in docs/driver-contract.md under section
# "The driver kit".
#
# Four design principles govern this library:
#   1. A driver interacts with the cluster exclusively through the substrate contract by invoking
#      sb_exec, sb_copy_in, and sb_copy_out on client 0. Because it never references a specific
#      runtime, the driver executes unmodified across every backend.
#   2. Every client program runs inside client 0 rather than in a daemon instance, applying the JVM
#      options configured for the client role so that the deployment resource sizing is preserved.
#   3. All local driver outputs are written beneath PX_DRIVER_DIR, while staged inputs on the
#      cluster reside in the remote working directory /tmp/px/<run-id> inside client 0.
#   4. Misuse of a helper function terminates the process with exit code 2. Substrate exit statuses
#      are translated into driver exit codes, mapping 124 to 1 and mapping 125 through 127 and 6
#      to 3. Because the exit status of a Java class represents the outcome of the workload itself,
#      dv_java maps only statuses 125 through 127 to 3 and treats every other failure as 1.

# This file is a shell library that must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: drivers/lib/driver.sh is a library. Source it from a driver.\n' >&2
  exit 2
fi

# Guard against repeated sourcing so that the library does not redefine its names a second time.
if [ -n "${DV_LOADED:-}" ]; then
  return 0
fi
DV_LOADED=1

DV_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=SCRIPTDIR/../../lib/bundle.sh
. "$DV_LIB_DIR/../../lib/bundle.sh"

# Tag diagnostic messages with the active driver name rather than the shared library file name.
PX_PROG="${PX_DRIVER_NAME:-$PX_PROG}"

DV_REMOTE_ROOT=/tmp/px
DV_RUNTIME_CLASS=org.apache.phoenix.util.PhoenixRuntime

# Track the background child process that dv_java awaits and that the TERM signal handler stops,
# the active remote working directory of the running Java process, and the raw exit status of the
# most recent remote execution.
DV_CHILD=""
DV_JAVA_REMOTE=""
DV_STATUS=0

# Store the start and end timestamps of the anchor window most recently loaded by dv_window.
DV_START=""
DV_END=""

# Remote shell script that launches a Java main class inside client 0. The remote child shell
# expands the instance environment variables for the JDK, the client configuration directory, and
# the JAR directories. It accepts positional arguments for the remote working directory, the extra
# classpath prefix, the count of additional JVM options, those JVM options, and finally the main
# class followed by its arguments. Before replacing itself via exec, the shell writes its PID to
# java.pid so that the Java process inherits that PID and DV_STOP_SCRIPT can stop it. Consistent
# with "hbase classpath", the classpath concludes with HBase's lib/jdk11 directory and
# commons-codec JAR: JDK 11 removed JAXB, which Pherf requires to read its scenario files, and the
# commons-csv library bundled in the Pherf JAR depends on commons-codec, which the client JARs
# package only under a relocated package name.
DV_JAVA_SCRIPT="$(
  cat <<'SCRIPT_EOF'
cd "$1" || exit 1
prefix=$2
count=$3
shift 3
jars="client-facing-thirdparty/*:$PX_HBASE_HOME/lib/jdk11/*"
codec=("$PX_HBASE_HOME"/lib/commons-codec-*.jar)
opts=()
while [ "$count" -gt 0 ]; do
  opts+=("$1")
  shift
  count=$((count - 1))
done
printf '%s\n' "$$" >java.pid
exec "$PX_JDK_ROOT/$PX_JDK/bin/java" $(cat "$PX_CONF_DIR/jvm.opts") "${opts[@]}" \
  -cp "$prefix$PX_CONF_DIR:$PX_PHOENIX_HOME/client/*:$PX_HBASE_HOME/lib/$jars:$codec" "$@"
SCRIPT_EOF
)"

# Remote shell script that terminates the Java process associated with a remote working directory
# inside client 0. It sends SIGTERM, polls for up to 5 s for the process to exit, and escalates to
# SIGKILL if it remains alive; if no process is running, it exits cleanly without action.
DV_STOP_SCRIPT="$(
  cat <<'SCRIPT_EOF'
pid=$(cat "$1/java.pid" 2>/dev/null) || exit 0
rm -f "$1/java.pid"
kill -0 "$pid" 2>/dev/null || exit 0
kill -TERM "$pid" 2>/dev/null || exit 0
for second in 1 2 3 4 5; do
  kill -0 "$pid" 2>/dev/null || exit 0
  sleep 1
done
kill -KILL "$pid" 2>/dev/null || true
exit 0
SCRIPT_EOF
)"

# Remote shell script that extracts the Phoenix JDBC URL from the client configuration properties.
DV_URL_SCRIPT="$(
  cat <<'SCRIPT_EOF'
sed -n 's/^phoenix\.jdbc\.url=//p' "$PX_CONF_DIR/phoenix-client.properties"
SCRIPT_EOF
)"

# Remote shell script that removes a target remote path and ensures its parent directory exists.
DV_CLEAR_SCRIPT="$(
  cat <<'SCRIPT_EOF'
rm -rf "$1" && mkdir -p "${1%/*}"
SCRIPT_EOF
)"

# ---------------------------------------------------------------------------
# Verb dispatch and driver lifecycle management
# ---------------------------------------------------------------------------

# Dispatches a single driver command by validating the requested verb and its execution environment
# before invoking the driver's verb_<verb> implementation.
## @description  Dispatches a driver verb (verb, argument...) through the verb_<verb> function.
## @return       Returns the verb status (0 to 3), or 1 for any other code. Exits 2 on a missing or
##               unknown verb, arguments to describe, or a refused environment.
dv_main() {
  local verb=${1:-} status=0 rows=0
  # Disable the ERR trap because dv_main propagates the verb's exit status as the driver's own
  # exit code, and a non-zero verb outcome is not an unexpected shell failure.
  trap - ERR
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "a verb is required, one of: $PB_VERBS"
  shift
  pb_verb_known "$verb" ||
    px_die "$PX_EXIT_USAGE" "unknown verb '$verb', expected one of: $PB_VERBS"
  declare -F "verb_$verb" >/dev/null || px_die "$PX_EXIT_USAGE" "this driver has no verb $verb"
  if [ "$verb" = describe ]; then
    [ $# -eq 0 ] || px_die "$PX_EXIT_USAGE" "describe takes no argument"
  else
    dv_env_check
  fi
  trap dv_term TERM
  [ "$verb" != run ] || rows="$(dv_verdict_count)"
  "verb_$verb" "$@" || status=$?
  case "$status" in
  0 | 1 | 2 | 3) ;;
  *)
    px_error "verb $verb ended with status $status, which the contract reports as 1"
    status=$PX_EXIT_FAIL
    ;;
  esac
  # Every failed run must record at least one verdict row identifying the failure cause; synthesize
  # a fallback row if the run terminated before emitting one.
  if [ "$verb" = run ] && [ "$status" != 0 ] && [ "$(dv_verdict_count)" = "$rows" ]; then
    sx_verdict "${PX_DRIVER_NAME:-driver}.run" "$(sx_severity_of_code "$status")" \
      "run ended with code $status and recorded no cause, see logs/run.log"
  fi
  return "$status"
}

## @description  Counts the existing rows in the verdict stream located under PX_RUN_DIR.
## @return       Writes the row count to stdout, or 0 if the verdict stream is absent. Exits 0.
dv_verdict_count() {
  awk 'END { print NR }' "$PX_RUN_DIR/verdicts.tsv" 2>/dev/null || printf '0\n'
}

## @description  Validates the environment for verbs other than describe and creates PX_DRIVER_DIR.
## @return       Exits 0 on success, 2 if a required variable is unset or resolves outside PX_OUT,
##               or 1 when mkdir fails.
dv_env_check() {
  local name
  for name in PX_RUN_DIR PX_DRIVER_DIR; do
    [ -n "${!name:-}" ] ||
      px_die "$PX_EXIT_USAGE" "$name is unset. px-run sets it for every verb except describe."
  done
  px_out_resolve "$PX_DRIVER_DIR" >/dev/null || exit "$?"
  mkdir -p "$PX_DRIVER_DIR" || px_die "$PX_EXIT_FAIL" "cannot create $PX_DRIVER_DIR"
}

## @description  Handles SIGTERM from px-run by terminating the local exec client and stopping the
##               remote Java process launched by dv_java in client 0 before ending the verb.
## @return       Exits 1.
dv_term() {
  px_warn "the verb received TERM, so it stops its exec client and the remote Java process"
  if [ -n "$DV_CHILD" ]; then
    px_kill_tree "$DV_CHILD" || true
  fi
  [ -z "$DV_JAVA_REMOTE" ] || dv_java_stop || true
  exit "$PX_EXIT_FAIL"
}

## @description  Translates a substrate access exit status (status) into a normalized driver code.
## @return       Writes 0, 1, 2, or 3 to stdout, mapping 124 to 1 and 125 to 127 or 6 to 3.
##               Exits 0.
dv_code() {
  case "${1:-}" in
  0) printf '0\n' ;;
  2) printf '2\n' ;;
  125 | 126 | 127 | 6) printf '3\n' ;;
  *) printf '1\n' ;;
  esac
}

## @description  Resolves a test argument path (path), anchoring relative paths to the directory of
##               PX_SUITE_FILE when set or to the current working directory otherwise.
## @return       Writes the normalized absolute path to stdout. Exits 0.
dv_path() {
  local path=${1:-}
  case "$path" in
  /*) ;;
  *)
    if [ -n "${PX_SUITE_FILE:-}" ]; then
      path="$(dirname "$PX_SUITE_FILE")/$path"
    fi
    ;;
  esac
  px_path_normalize "$path"
}

# ---------------------------------------------------------------------------
# Construction of the driver describe document
# ---------------------------------------------------------------------------

# Formats a complete driver capability description as JSON from newline-delimited list arguments,
# where two-field entries separate their fields with a pipe character (|).
## @description  Emits a JSON describe document (driver, summary, phases, count, share, duration,
##               unsupported checks, unsupported artifacts, artifacts).
## @return       Writes the formatted describe document to stdout. Exits 0.
dv_describe() {
  px_json_open
  px_json_num contract "$PB_CONTRACT_VERSION"
  px_json_str driver "${1:-}"
  px_json_str summary "${2:-}"
  dv_describe_list phases anchor verb "${3:-}"
  px_json_open metrics
  dv_describe_list count "" "" "${4:-}"
  dv_describe_list share "" "" "${5:-}"
  dv_describe_list duration "" "" "${6:-}"
  px_json_close
  px_json_open unsupported
  dv_describe_list checks key reason "${7:-}"
  dv_describe_list artifacts path reason "${8:-}"
  px_json_close
  dv_describe_list artifacts path "" "${9:-}"
  px_json_close
}

## @description  Serializes a JSON array member (key, first field, second field, entries) for a
##               describe document as plain strings when field names are omitted or objects if set.
## @return       Writes the JSON array member to stdout. Exits 0.
dv_describe_list() {
  local key=$1 first=$2 second=$3 entries=$4 entry
  px_json_arr "$key"
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    if [ -z "$first" ]; then
      px_json_str "$entry"
      continue
    fi
    px_json_open
    px_json_str "$first" "${entry%%|*}"
    [ -z "$second" ] || px_json_str "$second" "${entry#*|}"
    px_json_close
  done <<EOF
$entries
EOF
  px_json_close
}

# If run.tsv is absent or the requested anchor has no closed stage window, emits a SKIP verdict
# row for check explaining what could not be evaluated.
## @description  Loads the closed stage window for an anchor (check, anchor, what) during report
##               generation into DV_START and DV_END.
## @return       Exits 0 when the window is loaded, or 1 after recording the SKIP verdict row.
dv_window() {
  local check=$1 anchor=$2 what=$3 window
  if [ ! -f "$PX_DRIVER_DIR/run.tsv" ]; then
    sx_verdict "$check" SKIP "run left no record, so $what"
    return 1
  fi
  window="$(pb_stage_window "$anchor" || true)"
  if [ -z "$window" ]; then
    sx_verdict "$check" SKIP "the bundle has no closed $anchor window"
    return 1
  fi
  DV_START=${window%%"$PX_TAB"*}
  DV_END=${window#*"$PX_TAB"}
  px_debug "report reads the $anchor window from $DV_START to $DV_END"
}

# ---------------------------------------------------------------------------
# Cluster access and remote execution on client 0
# ---------------------------------------------------------------------------

## @description  Loads the active backend through the substrate contract and verifies the cluster
##               scope identifier.
## @return       Exits 0 on success, or 2 if PX_SCOPE_ID is empty or the backend name is refused.
dv_cluster() {
  # shellcheck source=SCRIPTDIR/../../lib/backend/contract.sh
  . "$DV_LIB_DIR/../../lib/backend/contract.sh"
  sb_load "${PX_BACKEND:-}"
  [ -n "${PX_SCOPE_ID:-}" ] ||
    px_die "$PX_EXIT_USAGE" "PX_SCOPE_ID is empty, and the verb ${PX_VERB:-} needs a cluster"
}

## @description  Computes the path of the run's remote working directory (/tmp/px/<run-id>).
## @return       Writes the remote path to stdout and exits 0, or exits 2 if PX_RUN_ID is invalid.
dv_remote_path() {
  [[ ${PX_RUN_ID:-} =~ $PB_RUN_ID_RE ]] ||
    px_die "$PX_EXIT_USAGE" "PX_RUN_ID '${PX_RUN_ID:-}' is not a run id"
  printf '%s/%s\n' "$DV_REMOTE_ROOT" "$PX_RUN_ID"
}

## @description  Creates the remote working directory for the current run inside client 0.
## @return       Writes the directory path to stdout and exits 0, or returns the mapped driver exit
##               code of the remote exec (1 or 3).
dv_remote() {
  local dir status=0
  dir="$(dv_remote_path)" || return $?
  sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- mkdir -p "$dir" >&2 || status=$?
  if [ "$status" != 0 ]; then
    px_error "cannot create the remote work directory $dir in client 0 (status $status)"
    return "$(dv_code "$status")"
  fi
  printf '%s\n' "$dir"
}

## @description  Stages a local file or directory (local path, name) into the remote working
##               directory in client 0, replacing any existing content at that target name.
## @return       Exits 0, or returns the driver code (1, 2, or 3); exits 2 on an invalid argument.
dv_put() {
  local source=${1:-} name=${2:-} target status=0
  [ -e "$source" ] || px_die "$PX_EXIT_USAGE" "dv_put needs an existing local path, got '$source'"
  case "$name" in
  '' | /* | *..*) px_die "$PX_EXIT_USAGE" "dv_put needs a relative name, got '$name'" ;;
  esac
  target="$(dv_remote_path)/$name" || return $?
  sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- \
    bash -c "$DV_CLEAR_SCRIPT" px-clear "$target" >&2 || status=$?
  if [ "$status" = 0 ]; then
    sb_copy_in "$PX_SCOPE_ID" client 0 "$source" "$target" || status=$?
  fi
  if [ "$status" != 0 ]; then
    px_error "cannot stage $source into client 0 at $target (status $status)"
  fi
  return "$(dv_code "$status")"
}

## @description  Retrieves a file or directory (name, local path) from the remote working directory
##               to a path beneath PX_DRIVER_DIR, replacing any existing local content.
## @return       Exits 0, or driver code 1, 2, or 3; exits 2 on a path outside PX_DRIVER_DIR.
dv_get() {
  local name=${1:-} target=${2:-} driver resolved status=0
  case "$name" in
  '' | /* | *..*) px_die "$PX_EXIT_USAGE" "dv_get needs a relative name, got '$name'" ;;
  esac
  [ -n "$target" ] || px_die "$PX_EXIT_USAGE" "dv_get needs a local path"
  driver="$(px_out_resolve "$PX_DRIVER_DIR" || exit "$?")" || return $?
  resolved="$(px_out_resolve "$target" || exit "$?")" || return $?
  case "$resolved" in
  "$driver"/?*) ;;
  *) px_die "$PX_EXIT_USAGE" "dv_get writes below $driver only, got '$target'" ;;
  esac
  rm -rf "$resolved"
  sb_copy_out "$PX_SCOPE_ID" client 0 "$(dv_remote_path)/$name" "$resolved" || status=$?
  if [ "$status" != 0 ]; then
    px_error "cannot copy $name out of client 0 to $resolved (status $status)"
  fi
  return "$(dv_code "$status")"
}

# Executes a Java workload inside client 0. Each classpath entry passed via --cp is resolved
# relative to the remote working directory.
## @description  Runs a Java main class in client 0 (timeout, [--cp ENTRY]... [--opt OPTION]...,
##               class, argument...).
## @return       Exits 0, 3 if client 0 cannot start it (125 to 127), or 1; 2 on a bad argument.
dv_java() {
  local timeout=${1:-} prefix="" remote status=0
  local opts=()
  shift || true
  [[ $timeout =~ $PX_RE_INT ]] || px_die "$PX_EXIT_USAGE" "dv_java needs a timeout in seconds"
  remote="$(dv_remote_path)" || return $?
  while [ $# -gt 0 ]; do
    case "$1" in
    --cp | --opt)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "dv_java: $1 needs a value"
      if [ "$1" = --cp ]; then
        prefix="$prefix$remote/$2:"
      else
        opts+=("$2")
      fi
      shift 2
      ;;
    *) break ;;
    esac
  done
  [ $# -gt 0 ] || px_die "$PX_EXIT_USAGE" "dv_java needs a main class"
  # Launch the remote execution in the background and wait on it so that the SIGTERM trap can
  # interrupt the child process while this shell is waiting.
  DV_JAVA_REMOTE=$remote
  sb_exec "$PX_SCOPE_ID" client 0 "$timeout" -- bash -c "$DV_JAVA_SCRIPT" px-java \
    "$remote" "$prefix" "${#opts[@]}" ${opts[@]+"${opts[@]}"} "$@" &
  DV_CHILD=$!
  wait "$DV_CHILD" || status=$?
  DV_CHILD=""
  # Explicitly stop the remote JVM when the exec times out (status 124), as an expired client call
  # can leave the Java process running inside client 0.
  [ "$status" != 124 ] || dv_java_stop || true
  DV_JAVA_REMOTE=""
  DV_STATUS=$status
  if [ "$DV_STATUS" != 0 ]; then
    px_error "the Java class $1 in client 0 ended with status $DV_STATUS"
  fi
  # Treat the exit status of the Java class as a workload outcome; only a failure to launch the
  # program inside client 0 (statuses 125 through 127) is reported as a cluster anomaly.
  case "$status" in
  0) return 0 ;;
  125 | 126 | 127) return "$PX_EXIT_ANOMALY" ;;
  esac
  return "$PX_EXIT_FAIL"
}

## @description  Terminates the Java process running in DV_JAVA_REMOTE on client 0 using
##               DV_STOP_SCRIPT.
## @return       Exits 0 on success, or returns the exit status of the remote exec.
dv_java_stop() {
  # Bound the remote stop command to 8 s so that it completes within the 10 s grace period that
  # px-run allows after sending SIGTERM.
  sb_exec "$PX_SCOPE_ID" client 0 8 -- bash -c "$DV_STOP_SCRIPT" px-stop "$DV_JAVA_REMOTE" >&2
}

## @description  Reads the Phoenix JDBC URL from the client configuration inside client 0.
## @return       Writes the JDBC URL to stdout and exits 0; returns 1 if the exec times out, or 3
##               if client 0 provides no valid URL.
dv_jdbc_url() {
  local url status=0
  url="$(sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- \
    bash -c "$DV_URL_SCRIPT" || exit "$?")" || status=$?
  url="$(printf '%s\n' "$url" | sed -n '1p')"
  case "$status:$url" in
  0:jdbc:phoenix:*) ;;
  124:*) return "$PX_EXIT_FAIL" ;;
  *)
    px_error "client 0 gives no phoenix.jdbc.url (status $status, value '$url')"
    return "$PX_EXIT_ANOMALY"
    ;;
  esac
  printf '%s\n' "$url"
}

# ---------------------------------------------------------------------------
# Table discovery, catalog metadata recording, and cleanup
# ---------------------------------------------------------------------------
# The prepare verb records the UPDATE_CACHE_FREQUENCY attribute of every table it creates, while
# the restore verb drops every table that the workload may create. Both operations derive their
# table list from the CREATE TABLE statements in the test's SQL files. Table and schema identifiers
# are normalized using Phoenix rules: unquoted identifiers are converted to upper case, whereas
# double-quoted identifiers preserve their exact case.

## @description  Extracts table names from CREATE TABLE statements in SQL files (file...).
## @return       Writes one "schema<TAB>table" row per table to stdout, with an empty schema when
##               unqualified. Exits 0.
dv_tables_parse() {
  [ $# -gt 0 ] || return 0
  awk '
    function norm(part) {
      if (part ~ /^"/) return substr(part, 2, length(part) - 2)
      return toupper(part)
    }
    { sub(/--.*/, ""); text = text " " $0 }
    END {
      ident = "(\"[^\"]+\"|[A-Za-z0-9_]+)"
      head = "CREATE[ \t]+TABLE[ \t]+(IF[ \t]+NOT[ \t]+EXISTS[ \t]+)?"
      while (match(toupper(text), head)) {
        text = substr(text, RSTART + RLENGTH)
        if (!match(text, "^" ident "([.]" ident ")?")) continue
        name = substr(text, 1, RLENGTH)
        schema = ""
        if (match(name, "^" ident "[.]")) {
          schema = norm(substr(name, 1, RLENGTH - 1))
          name = substr(name, RLENGTH + 1)
        }
        row = schema "\t" norm(name)
        if (!(row in seen)) { seen[row] = 1; print row }
      }
    }' "$@"
}

## @description  Stages a local SQL file (timeout, URL, file, output file) by its basename and runs
##               it through PhoenixRuntime in client 0, capturing stdout in the output file.
## @return       Exits 0 on success, or returns the mapped driver code (1, 2, or 3).
dv_sql() {
  local timeout=$1 url=$2 file=$3 out=$4 name status=0
  name="sql/${file##*/}"
  dv_put "$file" "$name" || return $?
  dv_java "$timeout" "$DV_RUNTIME_CLASS" "$url" "$(dv_remote_path)/$name" >"$out" || status=$?
  return "$status"
}

# Because the catalog query reads one row per table, drivers bound it by PX_TIMEOUT_DEFAULT rather
# than by the workload run bound.
## @description  Queries SYSTEM.CATALOG for the tables in a list file (list file of dv_tables_parse
##               rows, timeout, URL) and records each table's UPDATE_CACHE_FREQUENCY via pb_table.
## @return       Exits 0 on success, or returns the mapped driver code of the query (1, 2, or 3).
dv_tables_record() {
  local list=$1 timeout=$2 url=$3 names sql out schema table value status=0
  names="$(awk -F '\t' 'NF {
      gsub(/\047/, "\047\047", $2); printf "%s\047%s\047", s, $2; s = ","
    }' "$list" 2>/dev/null || true)"
  [ -n "$names" ] || return 0
  sql="$PX_DRIVER_DIR/catalog.sql"
  out="$PX_DRIVER_DIR/catalog.out"
  printf '%s %s %s;\n' \
    'SELECT TABLE_SCHEM, TABLE_NAME, UPDATE_CACHE_FREQUENCY FROM SYSTEM.CATALOG' \
    'WHERE TENANT_ID IS NULL AND COLUMN_NAME IS NULL AND COLUMN_FAMILY IS NULL' \
    "AND TABLE_NAME IN ($names)" >"$sql" || return "$PX_EXIT_FAIL"
  dv_sql "$timeout" "$url" "$sql" "$out" || return $?
  # Result rows appear after the dashed separator line beneath the column headers, and
  # PhoenixRuntime renders SQL NULL values as the literal token <null>.
  while IFS="$PX_TAB" read -r schema table value; do
    [ -n "$table" ] || continue
    [ "$schema" != "<null>" ] || schema=""
    [ "$value" != "<null>" ] || value=""
    if awk -F '\t' -v s="$schema" -v t="$table" '$1 == s && $2 == t { f = 1 } END { exit !f }' \
      "$list"; then
      pb_table "$schema" "$table" "$value" || status=$?
    fi
  done <<EOF
$(awk '/^Time: / { data = 0 } data && NF >= 3 { print $1 "\t" $2 "\t" $3 }
  /^(-+ )+$/ { data = 1 }' "$out")
EOF
  return "$status"
}

## @description  Drops every table in a list file (list file of dv_tables_parse rows, timeout, URL)
##               using DROP TABLE IF EXISTS ... CASCADE so repeated invocations are idempotent.
## @return       Exits 0 on success, or returns the mapped driver code of the SQL run (1, 2, or 3).
dv_tables_drop() {
  local list=$1 timeout=$2 url=$3 sql
  [ -s "$list" ] || return 0
  sql="$PX_DRIVER_DIR/restore.sql"
  awk -F '\t' 'NF {
      name = "\"" $2 "\""
      if ($1 != "") name = "\"" $1 "\"." name
      print "DROP TABLE IF EXISTS " name " CASCADE;"
    }' "$list" >"$sql" || return "$PX_EXIT_FAIL"
  dv_sql "$timeout" "$url" "$sql" "$PX_DRIVER_DIR/restore.out"
}

## @description  Drops all tables listed in PX_DRIVER_DIR/tables.list within PX_TIMEOUT_VERB and
##               removes the remote working directory, operating idempotently on repeat calls.
## @return       Exits 0 on success, or returns the first non-zero driver code (1, 2, or 3).
dv_tables_restore() {
  local list="$PX_DRIVER_DIR/tables.list" url status=0 code=0
  if [ -s "$list" ]; then
    if dv_remote >/dev/null && url="$(dv_jdbc_url || exit "$?")"; then
      dv_tables_drop "$list" "${PX_TIMEOUT_VERB:-300}" "$url" || status=$?
    else
      status=$?
    fi
  fi
  dv_remote_remove || code=$?
  [ "$status" != 0 ] || status=$code
  return "$status"
}

## @description  Deletes the run's remote working directory from client 0.
## @return       Exits 0 on success, or returns the mapped driver code of the remote exec (1 or 3).
dv_remote_remove() {
  local dir status=0
  dir="$(dv_remote_path)" || return $?
  sb_exec "$PX_SCOPE_ID" client 0 "${PX_TIMEOUT_VERB:-300}" -- rm -rf "$dir" >&2 || status=$?
  [ "$status" = 0 ] || px_error "cannot remove $dir from client 0 (status $status)"
  return "$(dv_code "$status")"
}
