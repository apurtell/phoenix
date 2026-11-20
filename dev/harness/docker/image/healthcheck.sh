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

# Health probe for a single role instance. Every Docker Compose service healthcheck executes this
# script, and the Compose backend reads service readiness directly from that healthcheck result.
# The Kubernetes backend runs the exact same script for its pod probes so that every role is
# judged ready by identical criteria on both backends.
#
# The probe operates in two modes:
#   - ready: verifies that the instance is able to perform its role. In particular, the HBase
#     Master is considered ready only when it is the active master, every configured RegionServer
#     has checked in, and master initialization has completed.
#   - live: verifies only that the process responds on its management port. Liveness probes use
#     this mode so that a busy instance is not evicted for slow request processing.
#
# Each invocation issues a single request with a bounded wait and never retries internally. The
# deployment configures the probe interval, retry budget, and start period with deliberately large
# values so that a long garbage collection pause does not mark an instance dead.
#
# Management endpoints are queried through curl: JMX for HDFS and HBase, REST for YARN, and the
# four-letter ruok command for ZooKeeper. The self-test suite places a fake curl first on PATH to
# exercise the probe without a running cluster.

IMG_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=SCRIPTDIR/lib/image.sh
. "$IMG_HOME/lib/image.sh"

set -euo pipefail

## @description  Prints the command-line usage and exit code documentation.
## @return       Writes the usage message to standard output and returns exit code 0.
usage() {
  cat <<'EOF'
Usage: healthcheck.sh [ready|live]

Probes the instance that PX_ROLE and PX_INSTANCE_INDEX name. The default
mode is ready. One line on stdout gives the reason.

Exit codes: 0 healthy, 1 unhealthy, 2 usage, an unknown role, or a port
or a count that the environment does not configure.
EOF
}

HC_MODE=ready
case "${1:-}" in
-h | --help)
  usage
  exit 0
  ;;
'' | ready) HC_MODE=ready ;;
live) HC_MODE=live ;;
*)
  img_error "unknown mode: $1"
  usage >&2
  exit "$IMG_EXIT_USAGE"
  ;;
esac
if [ "$#" -gt 1 ]; then
  img_error "unexpected argument: $2"
  exit "$IMG_EXIT_USAGE"
fi

img_identity || exit "$IMG_EXIT_USAGE"
HC_STATUS=0
HC_REASON="$(img_check "$PX_ROLE" "$PX_INSTANCE_INDEX" "$HC_MODE")" || HC_STATUS=$?
case "$HC_STATUS" in
0) printf '%s %s %s: %s\n' "$HC_MODE" "$PX_ROLE" "$PX_INSTANCE_INDEX" "$HC_REASON" ;;
1) printf 'not %s %s %s: %s\n' "$HC_MODE" "$PX_ROLE" "$PX_INSTANCE_INDEX" "$HC_REASON" ;;
*)
  printf 'cannot probe %s %s: %s\n' "$PX_ROLE" "$PX_INSTANCE_INDEX" "$HC_REASON"
  HC_STATUS=$IMG_EXIT_USAGE
  ;;
esac
exit "$HC_STATUS"
