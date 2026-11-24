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

# The pherf-distributed driver executes a Pherf scenario across the agents in the Distributed Pherf
# tier via the controller REST API, and copies the resulting controller run bundle into the harness
# bundle. It records the merged latency report, the fleet verdict, and the workload share assigned
# to each agent. The authoritative specification is in docs/driver-contract.md under section
# "pherf-distributed".
#
# Supported test arguments follow this syntax:
#
#   --scenario FILE [--schema FILE] [--properties FILE] [--agents N]
#   [--weights W,...] [--seed N] [--rows N] [--rate N] [--duration-ms N]
#   [--threads N] [-- PHERF_OPTION...]
#
# Each verb that loads data or measures performance submits a single execution plan as one
# controller test. The prepare verb submits the data load plan (-l) and runs it inside the
# setup@load stage, while the run verb submits the measured query sets (-q) and runs them inside
# the steady@pherf stage. Because both plans share the same seed, agents, weights, and rows, they
# assign each agent an identical data-plane identity so that every agent reads the exact range that
# it loaded. The documents bundled into a plan consist of the scenario, the profile files that it
# references, and the schema files, each registered under its base file name.
#
# The driver itself does not launch a JVM; instead, lib/fleet.sh invokes curl inside
# pherf-controller 0 for each API call. During the load phase, the single schema file is applied
# through the agents: each agent runs Pherf from its working directory where plan documents are
# materialized under slice/, and Pherf reads the schema file path directly from the local file
# system. On a harness target, client 0 records the created tables after the load completes and
# drops them during restore, matching the behavior of the pherf driver. An external target has no
# client 0.

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/../lib/driver.sh
. "$SELF/../lib/driver.sh"
# shellcheck source=SCRIPTDIR/../lib/pherf.sh
. "$SELF/../lib/pherf.sh"
# shellcheck source=SCRIPTDIR/../../lib/fleet.sh
. "$SELF/../../lib/fleet.sh"

set -euo pipefail
px_trap_enable

PD_NAME=pherf-distributed

# Define the default random seed for an execution plan. Using a fixed seed ensures that every
# replicate assigns the same data-plane identities across agents.
PD_DEFAULT_SEED=8070

# These regular expressions enforce the controller's identifier grammar for document names and
# controller IDs, which requires [A-Za-z0-9._-]{1,128} with no leading dot. A valid bundle path
# consists of such names joined by forward slashes.
PD_NAME_RE='^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$'
PD_PATH_RE='^[A-Za-z0-9._/-]+$'

# Hold the parsed test arguments. PD_DOCS stores the local file paths for every plan document,
# comprising the scenario, its referenced profiles, and the optional schema file.
PD_SCENARIO=""
PD_SCHEMA=""
PD_PROFILES=()
PD_DOCS=()
PD_PROPERTIES=""
PD_AGENTS=""
PD_WEIGHTS=""
PD_SEED=""
PD_ROWS=""
PD_RATE=""
PD_DURATION=""
PD_THREADS=""
PD_TARGET=""
PD_OPTIONS=()

# Track the active controller test ID and run ID for the current verb so the TERM signal handler can
# cancel an in-flight execution, along with the terminal state and reason of the most recent run and
# the set of idle agents observed before it started.
PD_TEST=""
PD_RUN=""
PD_STATE=""
PD_REASON=""
PD_AGENT_IDS=""

# The run verb sets PD_COPIED once it has recorded the pherf.bundle verdict row, signaling the TERM
# handler to preserve the existing bundle copy and verdict row rather than downloading it again.
PD_COPIED=""

# Because px-run escalates to KILL 10 s after delivering TERM, the TERM handler bounds the combined
# controller cancellation and bundle retrieval to this budget, recording the active deadline in
# epoch seconds in PD_TERM_END while the handler executes.
PD_TERM_BUDGET_S=8
PD_TERM_END=""

# Parse the test arguments and run topology into the PD_* variables, validating option values,
# reserved driver and agent flags, and controller document naming rules.
## @description  Parse the test arguments and the run topology configuration into PD_* variables.
## @return       Returns 0 on success. Exits 2 on an unknown option, bad value, missing scenario,
##               option owned by the driver or agent, or document name outside controller grammar.
pd_args() {
  local option name names=" " file topology="$PX_RUN_DIR/cluster/topology.env"
  PD_SCENARIO="" PD_SCHEMA="" PD_PROFILES=() PD_DOCS=() PD_PROPERTIES="" PD_AGENTS=""
  PD_WEIGHTS=""
  PD_SEED=$PD_DEFAULT_SEED PD_ROWS="" PD_RATE="" PD_DURATION="" PD_THREADS="" PD_OPTIONS=()
  while [ $# -gt 0 ]; do
    case "$1" in
    --scenario | --schema | --properties | --agents | --weights | --seed | --rows | --rate | \
      --duration-ms | --threads)
      [ $# -ge 2 ] || px_die "$PX_EXIT_USAGE" "$1 needs a value"
      case "$1" in
      --scenario) PD_SCENARIO="$(dv_path "$2")" ;;
      --schema)
        [ -z "$PD_SCHEMA" ] || px_die "$PX_EXIT_USAGE" "the load applies one schema file" \
          "through the agents, got a second: $2"
        PD_SCHEMA="$(dv_path "$2")"
        ;;
      --properties) PD_PROPERTIES="$(dv_path "$2")" ;;
      --agents) PD_AGENTS=$2 ;;
      --weights) PD_WEIGHTS=$2 ;;
      --seed) PD_SEED=$2 ;;
      --rows) PD_ROWS=$2 ;;
      --rate) PD_RATE=$2 ;;
      --duration-ms) PD_DURATION=$2 ;;
      *) PD_THREADS=$2 ;;
      esac
      shift 2
      ;;
    --)
      shift
      PD_OPTIONS=("$@")
      break
      ;;
    *) px_die "$PX_EXIT_USAGE" "unknown argument '$1', see the head of $SELF/driver.sh" ;;
    esac
  done
  [ -n "$PD_SCENARIO" ] || px_die "$PX_EXIT_USAGE" "$PD_NAME needs --scenario FILE"
  PD_TARGET="$(pb_env_value PX_PHERF_TARGET "$topology")"
  PD_TARGET=${PD_TARGET:-harness}
  [ -n "$PD_AGENTS" ] || PD_AGENTS="$(pb_env_value PX_PHERF_AGENT_COUNT "$topology")"
  [[ $PD_AGENTS =~ ^[1-9][0-9]*$ ]] || px_die "$PX_EXIT_USAGE" "the agent count is" \
    "'$PD_AGENTS': give --agents N, or render the cluster with PX_PHERF=1"
  for name in PD_ROWS PD_RATE PD_DURATION PD_THREADS; do
    [ -z "${!name}" ] || [[ ${!name} =~ ^[1-9][0-9]*$ ]] ||
      px_die "$PX_EXIT_USAGE" "a count is a whole number above zero, got '${!name}'"
  done
  [[ $PD_SEED =~ ^-?[0-9]{1,18}$ ]] || px_die "$PX_EXIT_USAGE" "the seed '$PD_SEED' is not" \
    "an integer of at most 18 digits"
  if [ -n "$PD_WEIGHTS" ]; then
    [[ $PD_WEIGHTS =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] &&
      [ "$(printf '%s\n' "$PD_WEIGHTS" | awk -F, '{ print NF }')" = "$PD_AGENTS" ] ||
      px_die "$PX_EXIT_USAGE" "--weights needs $PD_AGENTS whole numbers above zero, got" \
        "'$PD_WEIGHTS'"
  fi
  # The driver manages the execution phase, the run label, the workload documents, the schema
  # application policy, and the four workload-share options. The controller likewise rejects any
  # pass-through options that are managed by the agent, reporting the offending JSON path for each.
  for option in ${PD_OPTIONS[@]+"${PD_OPTIONS[@]}"}; do
    name=${option#-}
    name=${name#-}
    case "${option:0:1}:${name%%=*}" in
    -:l | -:q | -:label | -:scenarioFile | -:schemaFile | -:disableSchemaApply | \
      -:runDurationMs | -:threadCount | -:targetRate | -:rowCountOverride)
      px_die "$PX_EXIT_USAGE" "the driver sets the Pherf option '$option', remove it"
      ;;
    esac
  done
  if [ -f "$PD_SCENARIO" ] && [ -r "$PD_SCENARIO" ]; then
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      case "$name" in
      */* | ..) px_die "$PX_EXIT_USAGE" "the querySet profile '$name' of" \
        "${PD_SCENARIO##*/} is a path: the controller takes flat document names" ;;
      esac
      PD_PROFILES+=("${PD_SCENARIO%/*}/$name")
    done <<EOF
$(pherf_profile_names "$PD_SCENARIO")
EOF
  fi
  PD_DOCS=("$PD_SCENARIO" ${PD_PROFILES[@]+"${PD_PROFILES[@]}"} ${PD_SCHEMA:+"$PD_SCHEMA"})
  for file in "${PD_DOCS[@]}"; do
    name=${file##*/}
    [[ $name =~ $PD_NAME_RE ]] || px_die "$PX_EXIT_USAGE" "the document name '$name' is" \
      "outside the grammar of the controller, [A-Za-z0-9._-] with no leading dot"
    case "$names" in
    *" $name "*) px_die "$PX_EXIT_USAGE" "two documents have the name '$name'" ;;
    esac
    names="$names$name "
  done
}

# Serializes the execution plan for a single verb (load or run) as a RunSpec JSON payload containing
# one step across PD_AGENTS agents, bundling the workload documents, Pherf options, and share
# allocations.
## @description  Write the RunSpec JSON execution plan for the given verb (load or run) to stdout.
## @return       Writes the JSON document to stdout and returns 0, or returns 1 when a document
##               cannot be read.
pd_plan() {
  local verb=$1 name noisy=false key value text weight file
  [ -z "${PX_CONTAMINATION:-}" ] || noisy=true
  px_json_open
  px_json_num seed "$PD_SEED"
  px_json_str runClass "$PX_RUN_CLASS"
  px_json_open properties
  # Emit the run-level properties loaded from --properties. The noisy-host contamination flag is
  # written as the final key so that no setting in the properties file can override it.
  if [ -n "$PD_PROPERTIES" ]; then
    while IFS="$PX_TAB" read -r key value; do
      [ -n "$key" ] && [ "$key" != pherf.verdict.noisyHost ] || continue
      px_json_str "$key" "$value"
    done <<EOF
$(awk '{ sub(/\r$/, ""); sub(/^[ \t]+/, "") } /^([#!]|$)/ { next } {
    if (!match($0, /[=: \t]/)) next
    k = substr($0, 1, RSTART - 1); v = substr($0, RSTART)
    sub(/^[ \t]*[=:]?[ \t]*/, "", v); sub(/[ \t]+$/, "", v)
    if (k != "") print k "\t" v }' "$PD_PROPERTIES")
EOF
  fi
  px_json_str pherf.verdict.noisyHost "$noisy"
  px_json_close
  px_json_arr steps
  px_json_open
  px_json_num agents "$PD_AGENTS"
  if [ -n "$PD_WEIGHTS" ]; then
    px_json_arr weights
    for weight in ${PD_WEIGHTS//,/ }; do
      px_json_num "$weight"
    done
    px_json_close
  fi
  px_json_str scenario "${PD_SCENARIO##*/}"
  px_json_open documents
  for file in "${PD_DOCS[@]}"; do
    text="$(cat "$file")" || return "$PX_EXIT_FAIL"
    px_json_str "${file##*/}" "$text"
  done
  px_json_close
  px_json_arr args
  if [ "$verb" = load ] && [ -n "$PD_SCHEMA" ]; then
    px_json_str -schemaFile
    px_json_str "slice/${PD_SCHEMA##*/}"
  else
    px_json_str -disableSchemaApply
  fi
  if [ "$verb" = load ]; then px_json_str -l; else px_json_str -q; fi
  for name in ${PD_OPTIONS[@]+"${PD_OPTIONS[@]}"}; do
    px_json_str "$name"
  done
  px_json_close
  if [ "$verb" = run ]; then
    [ -z "$PD_RATE" ] || px_json_num rate "$PD_RATE"
    [ -z "$PD_DURATION" ] || px_json_num durationMs "$PD_DURATION"
    [ -z "$PD_THREADS" ] || px_json_num threads "$PD_THREADS"
  fi
  [ -z "$PD_ROWS" ] || px_json_num rows "$PD_ROWS"
  px_json_close
  px_json_close
  px_json_close
}

## @description  Extract a single value from a JSON document given its file path and JSON pointer.
## @return       Writes the resolved value to stdout and returns 0, returns 1 if the input is not
##               valid JSON, or returns 6 if the requested pointer is absent.
pd_json() {
  local rows
  rows="$(pb_json_flat "$1" || exit "$?")" || return "$PX_EXIT_FAIL"
  pb_json_get "$2" "$rows"
}

# Invokes the controller REST API with the request payload on standard input, constraining each
# request to the remaining TERM time budget when executing inside the TERM signal handler.
## @description  Invoke the controller REST API (method, path, output file) with the body on stdin.
## @return       Returns 0 on success, 22 on an HTTP error (with the error document in the output
##               file), 3 if the controller does not answer, or 1 on any other failure.
pd_api() {
  local status=0
  # Because an API invocation can consume both its timeout bound and the timeout grace period,
  # reserve the grace period so that every call made by the handler finishes before the budget
  # expires.
  if [ -n "$PD_TERM_END" ]; then
    PF_API_BOUND=$((PD_TERM_END - $(px_epoch_s) - PX_TIMEOUT_GRACE_S))
    if [ "$PF_API_BOUND" -lt 1 ]; then
      px_error "the TERM budget of ${PD_TERM_BUDGET_S}s has no time left for $1 $2"
      return "$PX_EXIT_FAIL"
    fi
  fi
  pf_api "$PX_SCOPE_ID" "$1" "$2" >"$3" || status=$?
  case "$status" in
  0 | 22) return "$status" ;;
  "$PX_EXIT_ANOMALY")
    px_error "the controller in pherf-controller 0 does not answer $1 $2"
    return "$PX_EXIT_ANOMALY"
    ;;
  esac
  px_error "$1 $2 failed (status $status)"
  return "$PX_EXIT_FAIL"
}

## @description  Append a tracking row (verb, test id, run id, state) to tests.tsv for use by the
##               restore verb.
## @return       Returns 0 on success, or 1 when the file cannot be written.
pd_test_row() {
  local file="$PX_DRIVER_DIR/tests.tsv"
  [ -f "$file" ] || pb_header "$file" "verb test_id run_id state" || return
  px_tsv_row "$@" >>"$file" || return "$PX_EXIT_FAIL"
}

## @description  Submit a plan (verb, plan file) to create a controller test and set PD_TEST.
## @return       Returns 0 on success, 1 if the controller refuses the plan (recording the
##               pherf.plan verdict row), or 3 if the controller does not answer.
pd_test_create() {
  local verb=$1 plan=$2 out="$PX_DRIVER_DIR/.response.json" status=0
  PD_TEST=""
  pd_api POST /v1/tests "$out" <"$plan" || status=$?
  if [ "$status" = 22 ]; then
    sx_verdict pherf.plan FAIL "the controller refused ${plan##*/}: $(pd_json "$out" /message ||
      cat "$out")"
    return "$PX_EXIT_FAIL"
  fi
  [ "$status" = 0 ] || return "$status"
  PD_TEST="$(pd_json "$out" /id)" || PD_TEST=""
  if ! [[ $PD_TEST =~ $PD_NAME_RE ]]; then
    px_error "the controller gave no valid test id: $(cat "$out")"
    PD_TEST=""
    return "$PX_EXIT_ANOMALY"
  fi
  pd_test_row "$verb" "$PD_TEST" "" OPEN
}

# Launches a new run for PD_TEST, polls the controller until the run leaves the RUNNING state, and
# populates PD_RUN, PD_STATE, and PD_REASON with the outcome.
## @description  Start a run of PD_TEST and block until its state is no longer RUNNING.
## @return       Returns 0 for any terminal run state, 3 on a conflict or no controller response
##               (recording the pherf.agents row), or 1 on any other failure.
pd_test_run() {
  local out="$PX_DRIVER_DIR/.response.json" status=0 message
  PD_RUN="" PD_STATE="" PD_REASON=""
  printf '{}' | pd_api POST "/v1/tests/$PD_TEST/runs" "$out" || status=$?
  if [ "$status" = 22 ]; then
    message="$(pd_json "$out" /message || cat "$out")"
    sx_verdict pherf.agents ERROR "the controller refused the run of test $PD_TEST: $message"
    return "$PX_EXIT_ANOMALY"
  fi
  if [ "$status" != 0 ]; then
    [ "$status" != "$PX_EXIT_ANOMALY" ] ||
      sx_verdict pherf.agents ERROR "the controller in pherf-controller 0 does not answer"
    return "$status"
  fi
  PD_RUN="$(pd_json "$out" /runId)" || PD_RUN=""
  if ! [[ $PD_RUN =~ $PD_NAME_RE ]]; then
    px_error "the controller gave no valid run id: $(cat "$out")"
    PD_RUN=""
    return "$PX_EXIT_ANOMALY"
  fi
  while :; do
    pd_api GET "/v1/tests/$PD_TEST/runs/$PD_RUN/status" "$out" </dev/null || status=$?
    if [ "$status" != 0 ]; then
      sx_verdict pherf.agents ERROR "the status of run $PD_RUN is unreadable (status $status)"
      return "$PX_EXIT_ANOMALY"
    fi
    PD_STATE="$(pd_json "$out" /state)" || PD_STATE=""
    [ "$PD_STATE" = RUNNING ] || break
    # Because the shell defers signal traps until a foreground command completes but dispatches
    # them immediately during a wait builtin, backgrounding sleep ensures that TERM does not wait
    # for the sleep interval to finish and that the TERM budget starts immediately upon receipt.
    sleep "${PX_POLL_INTERVAL_S:-2}" &
    wait "$!"
  done
  PD_REASON="$(pd_json "$out" /reason)" || PD_REASON=""
  rm -f "$out"
}

## @description  End a controller test by test ID; ending an already ended test returns HTTP 200.
## @return       Returns 0 on success, 22 on an HTTP error, 3 if the controller does not answer,
##               or 1 on any other failure.
pd_test_end() {
  local out="$PX_DRIVER_DIR/.response.json" status=0
  printf '{}' | pd_api POST "/v1/tests/$1/end" "$out" || status=$?
  [ "$status" = 0 ] || px_error "the end of test $1 failed: $(cat "$out" 2>/dev/null)"
  rm -f "$out"
  return "$status"
}

# Downloads every file listed in the bundle manifest for PD_RUN into the target directory, staging
# each download through a temporary .part file and verifying that its byte count matches the listed
# size. Populates PD_FILES and PD_BYTES on success, or sets PD_REASON on failure.
## @description  Copy the artifact bundle of PD_RUN into the specified target directory.
## @return       Returns 0 on success, 1 on a rejected path, truncated file, or failed request, or
##               3 if the controller does not answer.
pd_bundle_get() {
  local dir=$1 base="/v1/tests/$PD_TEST/runs/$PD_RUN/bundle" list rows path bytes got status=0
  PD_FILES=0 PD_BYTES=0
  rm -rf "$dir"
  if ! mkdir -p "$dir"; then
    PD_REASON="cannot make the directory of the copy of run $PD_RUN"
    return "$PX_EXIT_FAIL"
  fi
  list="$dir.listing.json"
  pd_api GET "$base" "$list" </dev/null || status=$?
  if [ "$status" != 0 ]; then
    PD_REASON="the bundle listing of run $PD_RUN failed (status $status)"
    rm -f "$list"
    return "$status"
  fi
  rows="$(pb_json_flat "$list")" || rows=""
  rm -f "$list"
  while IFS="$PX_TAB" read -r path bytes; do
    [ -n "$path$bytes" ] || continue
    # Ensure that every manifest path resolves strictly inside the destination directory and cannot
    # traverse outside it.
    case "/$path/" in
    //* | *//* | */./* | */../*) path="" ;;
    esac
    if ! [[ $path =~ $PD_PATH_RE ]] || ! [[ $bytes =~ $PX_RE_INT ]]; then
      PD_REASON="the listing of run $PD_RUN names a refused path or size"
      return "$PX_EXIT_FAIL"
    fi
    if ! mkdir -p "$(dirname "$dir/$path")"; then
      PD_REASON="cannot make the directory of $path of run $PD_RUN"
      return "$PX_EXIT_FAIL"
    fi
    pd_api GET "$base/$path" "$dir/$path.part" </dev/null || status=$?
    got="$(wc -c <"$dir/$path.part" 2>/dev/null | tr -d ' ')"
    if [ "$status" != 0 ] || [ "$got" != "$bytes" ]; then
      PD_REASON="$path of run $PD_RUN: status $status, ${got:-0} of $bytes bytes"
      return "$PX_EXIT_FAIL"
    fi
    if ! mv "$dir/$path.part" "$dir/$path"; then
      PD_REASON="cannot move $path.part of run $PD_RUN"
      return "$PX_EXIT_FAIL"
    fi
    PD_FILES=$((PD_FILES + 1))
    PD_BYTES=$((PD_BYTES + bytes))
  done <<EOF
$(pb_json_rows "$rows" /files path bytes)
EOF
  if [ "$PD_FILES" = 0 ]; then
    PD_REASON="the listing of run $PD_RUN names no file"
    return "$PX_EXIT_FAIL"
  fi
}

# Handles TERM from px-run by canceling the active controller run, copying its run bundle during
# the run verb, and stopping the exec client and any Java process on client 0 via dv_term.
## @description  Handle TERM from px-run: cancel the controller run, copy its bundle in the run
##               verb, and stop the exec client and any Java process on client 0 (dv_term).
## @return       Always exits with status 1.
pd_term() {
  local out="$PX_DRIVER_DIR/.cancel.json" status=0 state
  # Because the Java process on client 0 launches only after the controller run has ended, at most
  # one of the controller cancellation or the Java shutdown has active work to perform. The Java
  # shutdown is bounded to 8 s, and the cancellation and bundle copy share the 8 s TERM budget, so
  # either path fits within the 10 s grace period that px-run waits after delivering TERM.
  if [ -n "$PD_TEST" ] && [ -n "$PD_RUN" ] && [ -z "$DV_JAVA_REMOTE" ]; then
    px_warn "the verb received TERM, so it cancels the controller run $PD_RUN"
    PD_TERM_END=$(($(px_epoch_s) + PD_TERM_BUDGET_S))
    printf '{}' | pd_api POST "/v1/tests/$PD_TEST/runs/$PD_RUN/cancel" "$out" || status=$?
    # Because the controller commits the bundle of an ended run before returning its HTTP response,
    # the run bundle is available both after an HTTP 200 cancellation and after an HTTP 409 when
    # the run had already ended in another terminal state. Retrieving the bundle preserves the
    # fleet report inside the harness bundle; any file truncated by the TERM budget remains as a
    # .part file and is noted in the pherf.bundle verdict row. If the run verb already recorded
    # pherf.bundle before TERM arrived, its completed copy is left intact.
    if [ "${PX_VERB:-}" = run ] && [ -z "$PD_COPIED" ] &&
      { [ "$status" = 0 ] || [ "$status" = 22 ]; }; then
      if pd_bundle_get "$PX_DRIVER_DIR/controller"; then
        sx_verdict pherf.bundle PASS "copied $PD_FILES files, $PD_BYTES bytes, of run" \
          "$PD_RUN after the cancel at TERM" || true
      else
        sx_verdict pherf.bundle FAIL "the TERM budget of ${PD_TERM_BUDGET_S}s or a failed" \
          "call ended the copy of run $PD_RUN after $PD_FILES files: $PD_REASON; a file in" \
          "transfer at the cut stays <path>.part" || true
      fi
      state="$(pd_json "$PX_DRIVER_DIR/controller/manifest.json" /run/state 2>/dev/null ||
        pd_json "$out" /state 2>/dev/null)" || state=unknown
      pd_test_row run "$PD_TEST" "$PD_RUN" "$state" || true
    fi
    rm -f "$out"
  fi
  dv_term
}

# Waits until the required number of agents are ready for the test and writes the final agent list
# snapshot returned by the controller to agents.json. Records a pherf.agents error verdict row if
# the fleet is not ready.
## @description  Wait for the required test agents to become idle and write agents.json.
## @return       Returns 0 when all agents are ready, or 3 if an agent is missing, busy, or FAULTED,
##               or if the controller does not answer.
pd_agents() {
  local rows status=0
  rows="$(pf_agents_wait "$PX_SCOPE_ID" "$PD_AGENTS" "${PX_TIMEOUT_VERB:-300}" \
    "$PX_DRIVER_DIR/agents.json")" || status=$?
  if [ "$status" != 0 ]; then
    sx_verdict pherf.agents ERROR "the fleet does not have $PD_AGENTS idle agents" \
      "(status $status), see logs/${PX_VERB:-run}.log"
    return "$PX_EXIT_ANOMALY"
  fi
  PD_AGENT_IDS="$(printf '%s\n' "$rows" | awk -F '\t' '$2 == "IDLE" { printf "%s%s", s, $1
    s = " " }')"
}

## @description  Emit the driver capability and contract description document.
## @return       Writes the description document to stdout and returns 0.
verb_describe() {
  local dir="drivers/$PD_NAME" hdr="drivers/$PD_NAME/controller/drivers/pherf/results_HDR"
  dv_describe "$PD_NAME" \
    "Runs a Pherf scenario on the Distributed Pherf fleet through its controller, and records\
 the merged latency report, the fleet verdict and the share of each agent." \
    "setup@load|prepare
steady@pherf|run" \
    "pherf.operations
pherf.schedule.scheduled
pherf.schedule.completed
pherf.schedule.late
pherf.schedule.dropped
pherf.schedule.aborted
pherf.schedule.max_queue_depth
pherf.slot.rate
pherf.slot.threads
pherf.slot.rows
pherf.slot.samples
pherf.fleet.agents" \
    "" \
    "achieved_qps
query_latency.p50_ms
query_latency.p99_ms
pherf.latency.min_us
pherf.latency.max_us
pherf.latency.mean_us
pherf.latency.p50_us
pherf.latency.p90_us
pherf.latency.p99_us
pherf.latency.p999_us
pherf.schedule.max_drift_us
pherf.slot.start_lag_ms
pherf.fleet.start_skew_ms" \
    "offered_rate|the fleet verdict grades the open-loop attainment in pherf.health.attainment
query_errors|the merged report counts no failed closed-loop operation
driver.storage_scans|request metrics are off
driver.rpc_calls|request metrics are off" \
    "oplog/|no operation log" \
    "$dir/agents.json
$dir/plan-load.json
$dir/plan-run.json
$dir/tests.tsv
$dir/run.tsv
$dir/report.json
$dir/load/
$dir/controller/
$dir/controller/manifest.json
$dir/controller/status.json
$hdr/LATENCY_$PHERF_LABEL_RUN.json
$hdr/LATENCY_$PHERF_LABEL_RUN.hlog
$hdr/VERDICT_$PHERF_LABEL_RUN.tsv
$hdr/slots/"
}

## @description  Validate the test files, document names, and options without a running cluster.
## @return       Returns 0 when the test definition is valid, returns 1 on validation failure, or
##               exits 2 on invalid arguments.
verb_validate() {
  local file problem failed=0 docs=()
  pd_args "$@"
  docs=("scenario:$PD_SCENARIO" ${PD_PROFILES[@]+"${PD_PROFILES[@]/#/profile:}"}
    ${PD_SCHEMA:+"schema:$PD_SCHEMA"})
  [ -z "$PD_PROPERTIES" ] || docs+=("properties:$PD_PROPERTIES")
  for file in "${docs[@]}"; do
    problem=""
    if [ ! -f "${file#*:}" ] || [ ! -r "${file#*:}" ]; then
      problem="no readable file"
    elif [ "${file%%:*}" = scenario ] && ! grep -q '<scenario' "${file#*:}"; then
      problem="the file holds no <scenario> element"
    fi
    if [ -n "$problem" ]; then
      failed=$((failed + 1))
      sx_verdict "pherf.${file%%:*}" FAIL "${file#*:}: $problem"
    fi
  done
  if [ "$failed" != 0 ]; then
    sx_verdict driver.workload_valid FAIL "$failed documents are refused"
    return "$PX_EXIT_FAIL"
  fi
  sx_verdict driver.workload_valid PASS "scenario ${PD_SCENARIO##*/} on $PD_AGENTS agents," \
    "${PD_SCHEMA:+1 schema file and }${#PD_PROFILES[@]} profile files"
}

# Applies the schema and loads the dataset across the agent fleet within the setup@load stage,
# retrieves the resulting load bundle from the controller, and on a harness target records the
# user tables through client 0.
## @description  Apply the schema, load data across the fleet in setup@load, and copy the bundle.
## @return       Returns 0 on success, or fails with driver exit code 1, 2, or 3 (exiting 2 on
##               invalid arguments).
verb_prepare() {
  local url reason status=0 list="$PX_DRIVER_DIR/tables.list"
  pd_args "$@"
  dv_cluster
  trap pd_term TERM
  pd_agents || return $?
  pd_plan load >"$PX_DRIVER_DIR/plan-load.json" || return "$PX_EXIT_FAIL"
  dv_tables_parse ${PD_SCHEMA:+"$PD_SCHEMA"} >"$list" || return "$PX_EXIT_FAIL"
  pb_stage begin setup@load || return
  pd_test_create prepare "$PX_DRIVER_DIR/plan-load.json" || status=$?
  [ "$status" != 0 ] || pd_test_run || status=$?
  pb_stage end setup@load || return
  [ "$status" = 0 ] || return "$status"
  # Save the controller run's termination reason before downloading the bundle, because the final
  # run state determines the primary verdict row while bundle retrieval may overwrite PD_REASON.
  reason=$PD_REASON
  pd_bundle_get "$PX_DRIVER_DIR/load" || status=$?
  pd_test_end "$PD_TEST" || true
  pd_test_row prepare "$PD_TEST" "$PD_RUN" "$PD_STATE" || return
  case "$PD_STATE" in
  COMPLETE) ;;
  ABORTED)
    sx_verdict pherf.gate FAIL "the agents refused the load before any launch: $reason," \
      "see drivers/$PD_NAME/load/status.json"
    return "$PX_EXIT_FAIL"
    ;;
  *)
    sx_verdict pherf.load FAIL "the load run $PD_RUN ended $PD_STATE: $reason"
    return "$PX_EXIT_FAIL"
    ;;
  esac
  if [ "$status" != 0 ]; then
    sx_verdict pherf.load FAIL "cannot copy the load bundle: $PD_REASON"
    return "$PX_EXIT_FAIL"
  fi
  [ "$PD_TARGET" = harness ] || return 0
  dv_remote >/dev/null || return $?
  url="$(dv_jdbc_url || exit "$?")" || return $?
  dv_tables_record "$list" "${PX_TIMEOUT_DEFAULT:-300}" "$url"
}

# Executes the measured query sets across the agent fleet within the steady@pherf stage, then
# downloads both the merged report and the complete controller run bundle into the driver directory.
## @description  Run the measured query sets on the fleet in steady@pherf and copy the run bundle.
## @return       Returns 0 on success, or fails with driver exit code 1, 2, or 3 (exiting 2 on
##               invalid arguments).
verb_run() {
  local status=0 start end code=0 reason report="$PX_DRIVER_DIR/report.json"
  local merged="$PX_DRIVER_DIR/controller/drivers/pherf/results_HDR/LATENCY_$PHERF_LABEL_RUN.json"
  pd_args "$@"
  dv_cluster
  trap pd_term TERM
  pd_agents || return $?
  pd_plan run >"$PX_DRIVER_DIR/plan-run.json" || return "$PX_EXIT_FAIL"
  pd_test_create run "$PX_DRIVER_DIR/plan-run.json" || return $?
  rm -f "$PX_DRIVER_DIR/run.tsv"
  pb_stage begin steady@pherf || return
  start="$(px_epoch_ms)"
  pd_test_run || status=$?
  end="$(px_epoch_ms)"
  pb_stage end steady@pherf || return
  pb_header "$PX_DRIVER_DIR/run.tsv" "start_ms end_ms test_id run_id state" || return
  px_tsv_row "$start" "$end" "$PD_TEST" "$PD_RUN" "${PD_STATE:-none}" \
    >>"$PX_DRIVER_DIR/run.tsv" || return "$PX_EXIT_FAIL"
  [ "$status" = 0 ] || return "$status"
  # Preserve the run termination reason before bundle retrieval, which may overwrite PD_REASON.
  reason=$PD_REASON
  sx_verdict pherf.agents PASS "$PD_AGENTS idle agents: $PD_AGENT_IDS"
  # The controller produces a bundle for every terminal state so that failed runs still preserve
  # their diagnostic evidence.
  pd_api GET "/v1/tests/$PD_TEST/runs/$PD_RUN/report" "$report" </dev/null || code=$?
  if [ "$code" != 0 ]; then
    sx_verdict pherf.bundle FAIL "the report of run $PD_RUN is unreadable (status $code)"
  elif ! pd_bundle_get "$PX_DRIVER_DIR/controller"; then
    sx_verdict pherf.bundle FAIL "$PD_REASON"
    code=$PX_EXIT_FAIL
  elif ! cmp -s "$report" "$merged"; then
    sx_verdict pherf.bundle FAIL "report.json differs from the fleet report of the bundle"
    code=$PX_EXIT_FAIL
  else
    sx_verdict pherf.bundle PASS "copied $PD_FILES files, $PD_BYTES bytes, of run $PD_RUN"
  fi
  PD_COPIED=1
  pd_test_end "$PD_TEST" || true
  pd_test_row run "$PD_TEST" "$PD_RUN" "$PD_STATE" || return
  if [ "$PD_STATE" != COMPLETE ]; then
    sx_verdict pherf.execution FAIL "the controller run $PD_RUN ended $PD_STATE: $reason"
    return "$PX_EXIT_FAIL"
  fi
  sx_verdict pherf.execution PASS "the controller run $PD_RUN is COMPLETE"
  [ "$code" = 0 ] || return "$PX_EXIT_FAIL"
}

# Ends every controller test recorded during the run and drops the user tables on a harness target.
# Repeated invocations are idempotent and find no remaining work to perform.
## @description  End all controller tests for the run and drop tables on the harness target.
## @return       Returns 0 on success, or the first failing driver exit code (1, 2, or 3); exits 2
##               on invalid arguments.
verb_restore() {
  local test status=0 code=0
  pd_args "$@"
  dv_cluster
  while IFS= read -r test; do
    [[ $test =~ $PD_NAME_RE ]] || continue
    code=0
    pd_test_end "$test" || code=$?
    # Map an HTTP error from the controller to a standard failure code; all other status codes
    # returned by pd_api already conform to the driver exit code contract.
    [ "$code" != 22 ] || code=$PX_EXIT_FAIL
    [ "$status" != 0 ] || status=$code
  done <<EOF
$(awk -F '\t' 'NR > 1 && !seen[$2]++ { print $2 }' "$PX_DRIVER_DIR/tests.tsv" 2>/dev/null)
EOF
  if [ "$PD_TARGET" = external ]; then
    sx_verdict pherf.tables SKIP "the external target keeps its tables: the harness has no" \
      "client in that cluster"
  else
    code=0
    dv_tables_restore || code=$?
    [ "$status" != 0 ] || status=$code
  fi
  return "$status"
}

# Extracts per-agent workload shares and fleet start skew from the fleet section of the merged
# report, clamping timestamps to the steady window (steady start, steady end) and emitting metrics
# anchored at slot@<step>.<slot> and steady@pherf.
## @description  Record per-agent share metrics (slot@<step>.<slot>) and fleet start skew
##               (steady@pherf) from the report within the given steady window.
## @return       Returns 0 on success, or 1 when a metric record cannot be written.
pd_fleet_metrics() {
  local ws=$1 we=$2 rows kind anchor key class value unit s e
  rows="$(pb_json_flat "$PX_DRIVER_DIR/report.json")" || return 0
  while IFS="$PX_TAB" read -r kind anchor key class value unit s e; do
    [ "$kind" = m ] || continue
    pb_metric "$key" "$class" "$value" "$unit" "$anchor" "$s" "$e" || return
  done <<EOF
$(printf '%s\n' "$rows" | awk -F '\t' -v ws="$ws" -v we="$we" '
  { v[$1] = $3; t[$1] = $2 }
  function clamp(x) { return (x < ws) ? ws : ((x > we) ? we : x) }
  function put(a, k, c, val, u, s, e) {
    if (val !~ /^-?[0-9]+(\.[0-9]+)?$/) return
    printf "m\t%s\t%s\t%s\t%s\t%s\t%d\t%d\n", a, k, c, val, u, s, e
  }
  END {
    agents = 0; skew = ""
    for (i = 0; ("/fleet/steps/" i) in t; i++) {
      p = "/fleet/steps/" i
      s = clamp(v[p "/startMs"] + 0); e = clamp(v[p "/endMs"] + 0); if (e < s) e = s
      if ((p "/skewMs") in v && (skew == "" || v[p "/skewMs"] + 0 > skew)) skew = v[p "/skewMs"]
      n = 0
      for (j = 0; (p "/slots/" j) in t; j++) {
        q = p "/slots/" j
        a = "slot@" v[p "/step"] "." v[q "/slot"]
        put(a, "pherf.slot.rate", "count", v[q "/share/rate"], "ops/s", s, e)
        put(a, "pherf.slot.threads", "count", v[q "/share/threads"], "threads", s, e)
        put(a, "pherf.slot.rows", "count", v[q "/share/rows"], "rows", s, e)
        put(a, "pherf.slot.samples", "count", v[q "/samples"], "ops", s, e)
        put(a, "pherf.slot.start_lag_ms", "duration", v[q "/startLagMs"], "ms", s, e)
        n++
      }
      if (n > agents) agents = n
    }
    put("steady@pherf", "pherf.fleet.agents", "count", agents, "agents", ws, we)
    if (skew != "") put("steady@pherf", "pherf.fleet.start_skew_ms", "duration", skew, "ms", ws, we)
  }')
EOF
}

# Copies the eight fleet verdict rows into the harness verdict table, then records the merged
# latency metrics, each agent's workload share, and the overall achieved query rate across the fleet
# report time window.
## @description  Append the eight fleet verdict rows and record merged, share, and rate metrics.
## @return       Returns 0 when written or if no run executed, or returns 1 or 2 on write failure
##               or invalid arguments.
verb_report() {
  local start end hdr="$PX_DRIVER_DIR/controller/drivers/pherf/results_HDR" rs re
  pd_args "$@"
  dv_window pherf.latency steady@pherf "no run ran" || return 0
  start=$DV_START end=$DV_END
  pherf_verdict_copy "$hdr/VERDICT_$PHERF_LABEL_RUN.tsv" 8 '^pherf\.(health|fleet)\.[a-z_]+$' ||
    return
  pherf_latency "$hdr" "$start" "$end" || return
  pd_fleet_metrics "$start" "$end" || return
  # Because the fleet report timestamps are measured on the controller clock, clamp the report
  # interval to the steady-state window recorded on the harness host.
  rs="$(pd_json "$PX_DRIVER_DIR/report.json" /startMs 2>/dev/null)" || rs=$start
  re="$(pd_json "$PX_DRIVER_DIR/report.json" /endMs 2>/dev/null)" || re=$end
  [[ $rs =~ $PX_RE_INT ]] && [ "$rs" -ge "$start" ] || rs=$start
  [[ $re =~ $PX_RE_INT ]] && [ "$re" -le "$end" ] || re=$end
  if [ -n "$PHERF_JSON_OPS" ] && [ "$re" -gt "$rs" ]; then
    pb_metric achieved_qps duration \
      "$(awk -v n="$PHERF_JSON_OPS" -v ms="$((re - rs))" \
        'BEGIN { printf "%.3f", n * 1000 / ms }')" \
      ops/s steady@pherf "$rs" "$re" || return
  fi
}

dv_main "$@"
