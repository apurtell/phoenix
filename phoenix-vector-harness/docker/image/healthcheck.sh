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

# This script is the container health check. Docker runs it inside the container.
# It never starts a JVM, so it is fast and uses little memory.
# Each check tests only its own daemon, through its own ports.
# A check never depends on a peer daemon. The one-shot roles hdfs-init and cluster-ready
# gate readiness across services.
# A check passes only when the daemon is ready to serve, not only when the process exists.
# One-shot and client roles always pass, because they have no service.

set -euo pipefail

# shellcheck source=SCRIPTDIR/lib/ports.sh
. /opt/vh/lib/ports.sh

# Gets JSON from a local URL and applies a jq test. The test must give true.
vh_json_test() {
  local url="$1" test="$2"
  curl -fsS --max-time 4 "$url" | jq -e "$test" >/dev/null
}

# Tests one bean of the JMX servlet of a daemon.
vh_jmx_test() {
  local port="$1" query="$2" test="$3"
  vh_json_test "http://localhost:$port/jmx?qry=$query" "(.beans | length > 0) and ($test)"
}

# Passes when a local TCP port accepts a connection.
# The RPC servers listen on the IPv4 loopback address, so the probe uses that address
# and not a name lookup.
vh_port_open() {
  timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null
}

case "${VH_ROLE:?healthcheck.sh needs VH_ROLE}" in
  zk)
    # grep reads all input, so nc never gets a broken pipe.
    printf 'srvr\n' | nc -q 1 -w 3 localhost "$VH_ZK_CLIENT" | grep '^Mode: standalone' >/dev/null
    ;;
  nn)
    # The check does not wait for data nodes or for the end of safe mode.
    # The hdfs-init and cluster-ready roles wait for those conditions.
    vh_jmx_test "$VH_NN_HTTP" 'Hadoop:service=NameNode,name=NameNodeStatus' '.beans[0].State == "active"'
    vh_port_open "$VH_NN_RPC"
    ;;
  snn)
    vh_jmx_test "$VH_SNN_HTTP" 'Hadoop:service=SecondaryNameNode,name=SecondaryNameNodeInfo' 'true'
    ;;
  dn)
    # The data node is ready when it has registered with the name node.
    # The actor list is a JSON string inside the bean. An older format gives only the
    # name node address map, so the check also accepts a map that is not empty.
    vh_jmx_test "$VH_DN_HTTP" 'Hadoop:service=DataNode,name=DataNodeInfo' \
      '(.beans[0].BPServiceActorInfo // "[]" | fromjson | any(.[]; .ActorState == "RUNNING"))
       or (.beans[0].NamenodeAddresses // "{}" | fromjson | length > 0)'
    ;;
  rm)
    vh_json_test "http://localhost:$VH_RM_WEB/ws/v1/cluster/info" '.clusterInfo.state == "STARTED"'
    ;;
  nm)
    vh_json_test "http://localhost:$VH_NM_WEB/ws/v1/node/info" '.nodeInfo.nodeHealthy == true'
    ;;
  jhs)
    vh_json_test "http://localhost:$VH_JHS_WEB/ws/v1/history/info" '.historyInfo != null'
    ;;
  master)
    # The check does not require the end of master initialization.
    # Initialization needs a region server to open the meta table, and the region servers
    # start only after the master is healthy. That requirement causes a deadlock.
    # The cluster-ready role proves initialization with a Phoenix query.
    vh_jmx_test "$VH_MASTER_INFO" 'Hadoop:service=HBase,name=Master,sub=Server' \
      '.beans[0]["tag.isActiveMaster"] == "true"'
    vh_port_open "$VH_MASTER_RPC"
    ;;
  rs)
    # The region server is ready when it has registered with the master.
    # The server name and the start time get their values at registration.
    vh_jmx_test "$VH_RS_INFO" 'Hadoop:service=HBase,name=RegionServer,sub=Server' \
      '(.beans[0].regionServerStartTime // 0) > 0 and ((.beans[0]["tag.serverName"] // "") != "")'
    ;;
  hdfs-init|cluster-ready|harness|tools)
    exit 0
    ;;
  *)
    echo "healthcheck.sh: unknown VH_ROLE '$VH_ROLE'" >&2
    exit 2
    ;;
esac
