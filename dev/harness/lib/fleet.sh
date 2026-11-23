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

# This library implements the REST client for the Distributed Pherf controller. Both bin/px-pherf
# and the pherf-distributed driver source this library so that the harness shares a single client
# implementation. The API specification is documented in phoenix-pherf-distributed/README.md,
# section "REST API".
#
# Three architectural rules shape this file:
#   1. Every request reaches the controller exclusively through sb_exec in pherf-controller 0. The
#      curl binary inside the pherf image connects to 127.0.0.1 at PHERF_REST_PORT within that
#      container, matching the health probe that the image executes every 5 s. Because no harness
#      code reads a port on the host, a single execution path serves the harness target, the
#      external target, and a Kubernetes backend, leaving the port on 127.0.0.1 of the host for
#      direct operator access.
#   2. Every call reads its request body from stdin and writes the response body to stdout. An
#      HTTP error preserves its response body, which contains the {"error", "message"} error
#      document returned by the controller.
#   3. The caller must bind the backend before making the first call.

# This file is a library and must be sourced; executing it directly is an operator error.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf 'px: lib/fleet.sh is a library. Source it after lib/common.sh.\n' >&2
  exit 2
fi

# Guard against sourcing the library more than once so its definitions are not evaluated again.
if [ -n "${PF_LOADED:-}" ]; then
  return 0
fi
PF_LOADED=1

PF_LIB_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=SCRIPTDIR/bundle.sh
. "$PF_LIB_DIR/bundle.sh"

# Maximum duration of a single API call in seconds, accounting for the 60 s curl timeout plus
# the startup overhead of the container exec.
PF_API_BOUND=75

# Inline shell script executed inside pherf-controller 0 for a single API request. It accepts the
# HTTP method and request path as arguments, and for POST requests forwards stdin as the JSON body.
PF_CURL_SCRIPT="$(
  cat <<'SCRIPT_EOF'
method=$1
url="http://127.0.0.1:${PHERF_REST_PORT:-8780}$2"
set -- -sS --fail-with-body --noproxy '*' --max-time 60 -X "$method"
if [ "$method" = POST ]; then
  set -- "$@" -H 'Content-Type: application/json; charset=utf-8' --data-binary @-
fi
exec curl "$@" "$url"
SCRIPT_EOF
)"

# Invokes the controller REST API for the given scope, HTTP method, and path, reading the request
# body from stdin and writing the response body to stdout. An HTTP error (exit 22) still writes the
# controller's error document; when the tier is not running or does not answer (exit 3) there is no
# response body.
## @description  Calls the controller REST API (scope, method, path) with the body on stdin.
## @return       Writes the response body to stdout. Exits 0, 22 on an HTTP error with the error
##               document, 3 if the tier is down or does not answer, 4 if expired, or 1 otherwise.
pf_api() {
  local status=0
  sb_exec "$1" pherf-controller 0 "$PF_API_BOUND" -- bash -c "$PF_CURL_SCRIPT" px-api "$2" \
    "$3" || status=$?
  case "$status" in
  0 | 22) return "$status" ;;
  # Treat a curl connection failure (7), a curl operation timeout (28), or a failure to start the
  # exec inside the container instance as a cluster anomaly.
  7 | 28 | 6 | 125 | 126 | 127) return "$PX_EXIT_ANOMALY" ;;
  124) return "$PX_EXIT_TIMEOUT" ;;
  esac
  return "$PX_EXIT_FAIL"
}

# Parses a saved GET /v1/agents response file and emits the agents as id, state, and incarnation
# rows in the order returned by the controller.
## @description  Emits id, state, and incarnation rows from a GET /v1/agents response (file) in
##               controller order.
## @return       Writes the rows to stdout. Exits 0, or 1 when the file is not one JSON document.
pf_agent_rows() {
  local rows
  rows="$(pb_json_flat "$1" || exit "$?")" || return "$PX_EXIT_FAIL"
  pb_json_rows "$rows" /agents id state incarnation
}

# Polls the controller until pherf-agent-0 through pherf-agent-<count - 1> reach the IDLE state
# (taking the scope, agent count, timeout bound in seconds, and an optional file path for the last
# agent list or none), and writes the resulting agent rows to stdout.
## @description  Waits until pherf-agent-0 to pherf-agent-<count - 1> are IDLE in the given scope.
## @return       Writes the agent rows to stdout. Exits 0, 3 on no answer or FAULTED, 4 if still
##               missing or expired, or 1 when no work directory can be created.
pf_agents_wait() {
  local scope=$1 count=$2 bound=$3 keep=${4:-} deadline dir rows missing faulted state index
  local id fault listed status=0
  dir="$(px_mktemp_dir px-fleet)" || return "$PX_EXIT_FAIL"
  deadline=$(($(px_epoch_s) + bound))
  while :; do
    listed=0
    pf_api "$scope" GET /v1/agents </dev/null >"$dir/agents.json" || status=$?
    if [ "$status" = 0 ]; then
      rows="$(pf_agent_rows "$dir/agents.json")" || status=$PX_EXIT_ANOMALY
    fi
    if [ "$status" != 0 ]; then
      px_error "the controller in pherf-controller 0 gives no agent list (status $status)"
      [ "$status" = "$PX_EXIT_TIMEOUT" ] || status=$PX_EXIT_ANOMALY
      break
    fi
    listed=1
    missing="" faulted=""
    index=0
    while [ "$index" -lt "$count" ]; do
      state="$(printf '%s\n' "$rows" | awk -F '\t' -v id="pherf-agent-$index" \
        '$1 == id { print $2 }')"
      case "$state" in
      IDLE) ;;
      FAULTED) faulted="$faulted pherf-agent-$index" ;;
      *) missing="$missing pherf-agent-$index:${state:-absent}" ;;
      esac
      index=$((index + 1))
    done
    # Because only POST /v1/agents/{id}/reset clears a fault and the harness never issues a reset,
    # encountering any FAULTED agent terminates the wait immediately.
    if [ -n "$faulted" ]; then
      for id in $faulted; do
        fault="$(pb_json_rows "$(pb_json_flat "$dir/agents.json")" /agents id fault |
          awk -F '\t' -v id="$id" '$1 == id { print $2 }')" || fault=""
        px_error "$id is FAULTED, and only a reset clears it: ${fault:-no fault text}"
      done
      status=$PX_EXIT_ANOMALY
      break
    fi
    [ -n "$missing" ] || break
    if [ "$(px_epoch_s)" -ge "$deadline" ]; then
      px_error "the agents are not idle after ${bound}s:$missing"
      status=$PX_EXIT_TIMEOUT
      break
    fi
    sleep "${PX_POLL_INTERVAL_S:-2}"
  done
  # Persist the exact agent list document from which the final wait decision was made.
  if [ -n "$keep" ] && [ "$listed" = 1 ]; then
    mv -f "$dir/agents.json" "$keep" || status=$PX_EXIT_FAIL
  fi
  rm -rf "$dir"
  [ "$status" = 0 ] || return "$status"
  printf '%s\n' "$rows"
}
