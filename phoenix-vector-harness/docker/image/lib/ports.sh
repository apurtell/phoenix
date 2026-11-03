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

# This file is the single port table of the cluster.
# All containers share the host network, so every daemon instance needs its own ports.
# A port is a function of the role and the instance index.
# Instance i of an indexed role adds (i-1) times the step of the port.
#
# When sourced, the file exports every VH_* port variable for VH_ROLE and VH_INDEX.
# Every role gets every variable, so a template never renders an empty value.
# For a role that is not indexed, the indexed variables hold the values of instance 1.
#
# When executed, the file prints the table or checks it:
#   ports.sh table               all instances as tab-separated rows
#   ports.sh env [ROLE [INDEX]]  the exported variables of one instance
#   ports.sh urls                the web UI address of each instance
#   ports.sh check               fails if two instances share a port
#
# The file runs on bash 3.2 and on bash 5, so host scripts can also execute it.

# The specification. Columns: role, name, base port, step per index, bind address, key.
# Rows with role "-" list the debug port of each instance; their step is 1.
vh_ports_spec() {
  cat <<'EOF'
zk      ZK_CLIENT      2181  0    0.0.0.0    hbase.zookeeper.property.clientPort
nn      NN_RPC         8020  0    localhost  dfs.namenode.rpc-address
nn      NN_HTTP        9870  0    0.0.0.0    dfs.namenode.http-address
nn      NN_HTTPS       9871  0    0.0.0.0    dfs.namenode.https-address
snn     SNN_HTTP       9868  0    0.0.0.0    dfs.namenode.secondary.http-address
snn     SNN_HTTPS      9869  0    0.0.0.0    dfs.namenode.secondary.https-address
dn      DN_XFER        9866  100  0.0.0.0    dfs.datanode.address
dn      DN_HTTP        9864  100  0.0.0.0    dfs.datanode.http.address
dn      DN_IPC         9867  100  0.0.0.0    dfs.datanode.ipc.address
dn      DN_HTTPS       9865  100  0.0.0.0    dfs.datanode.https.address
rm      RM_SCHEDULER   8030  0    localhost  yarn.resourcemanager.scheduler.address
rm      RM_TRACKER     8031  0    localhost  yarn.resourcemanager.resource-tracker.address
rm      RM_RPC         8032  0    localhost  yarn.resourcemanager.address
rm      RM_ADMIN       8033  0    localhost  yarn.resourcemanager.admin.address
rm      RM_WEB         8088  0    0.0.0.0    yarn.resourcemanager.webapp.address
rm      RM_WEB_HTTPS   8090  0    0.0.0.0    yarn.resourcemanager.webapp.https.address
nm      NM_RPC         8041  100  localhost  yarn.nodemanager.address
nm      NM_LOCALIZER   8040  100  localhost  yarn.nodemanager.localizer.address
nm      NM_WEB         8042  100  0.0.0.0    yarn.nodemanager.webapp.address
nm      NM_WEB_HTTPS   8044  100  0.0.0.0    yarn.nodemanager.webapp.https.address
nm      NM_COLLECTOR   8048  100  localhost  yarn.nodemanager.collector-service.address
nm      NM_AMRM        8049  100  localhost  yarn.nodemanager.amrmproxy.address
nm      NM_SHUFFLE     13562 100  0.0.0.0    mapreduce.shuffle.port
jhs     JHS_RPC        10020 0    localhost  mapreduce.jobhistory.address
jhs     JHS_ADMIN      10033 0    localhost  mapreduce.jobhistory.admin.address
jhs     JHS_WEB        19888 0    0.0.0.0    mapreduce.jobhistory.webapp.address
jhs     JHS_WEB_HTTPS  19890 0    0.0.0.0    mapreduce.jobhistory.webapp.https.address
master  MASTER_RPC     16000 0    localhost  hbase.master.port
master  MASTER_INFO    16010 0    0.0.0.0    hbase.master.info.port
rs      RS_RPC         16020 100  localhost  hbase.regionserver.port
rs      RS_INFO        16030 100  0.0.0.0    hbase.regionserver.info.port
zk      DEBUG          15001 0    localhost  jdwp
nn      DEBUG          15002 0    localhost  jdwp
snn     DEBUG          15003 0    localhost  jdwp
rm      DEBUG          15004 0    localhost  jdwp
jhs     DEBUG          15005 0    localhost  jdwp
master  DEBUG          15006 0    localhost  jdwp
rs      DEBUG          15011 1    localhost  jdwp
dn      DEBUG          15021 1    localhost  jdwp
nm      DEBUG          15031 1    localhost  jdwp
harness DEBUG          15040 0    localhost  jdwp
tools   DEBUG          15041 0    localhost  jdwp
EOF
}

# The instance count of each indexed role.
VH_GROUP_SIZE=3

# Prints the role names in start order.
vh_ports_roles() {
  echo "zk nn snn dn rm nm jhs master rs hdfs-init cluster-ready harness tools"
}

# Returns success if the role has numbered instances.
vh_ports_indexed() {
  case "$1" in
    dn|nm|rs) return 0 ;;
    *) return 1 ;;
  esac
}

# Prints the instance names of all port-owning daemons in start order.
vh_ports_instances() {
  local r i out=""
  for r in zk nn snn dn rm nm jhs master rs; do
    if vh_ports_indexed "$r"; then
      i=1
      while [ "$i" -le "$VH_GROUP_SIZE" ]; do
        out="$out $r$i"
        i=$((i + 1))
      done
    else
      out="$out $r"
    fi
  done
  echo "${out# }"
}

# Prints the port of NAME for instance INDEX of the role that owns NAME.
# For DEBUG, the third argument names the role.
vh_port() {
  local name="$1" idx="${2:-1}" want="${3:-}" role n base step bind key
  while read -r role n base step bind key; do
    [ "$n" = "$name" ] || continue
    if [ "$name" = "DEBUG" ] && [ "$role" != "$want" ]; then
      continue
    fi
    echo $((base + step * (idx - 1)))
    return 0
  done <<EOF
$(vh_ports_spec)
EOF
  echo "ports.sh: unknown port name '$name'" >&2
  return 1
}

# Prints the ports of NAME for all instances, separated by spaces.
vh_port_all() {
  local name="$1" i=1 out=""
  while [ "$i" -le "$VH_GROUP_SIZE" ]; do
    out="$out $(vh_port "$name" "$i")"
    i=$((i + 1))
  done
  echo "${out# }"
}

# Sets and exports every port variable for one role and index.
vh_ports_export() {
  local role="$1" idx="${2:-1}" r n base step bind key use
  if [ -n "$role" ]; then
    case " $(vh_ports_roles) " in
      *" $role "*) ;;
      *) echo "ports.sh: unknown VH_ROLE '$role'" >&2; return 2 ;;
    esac
  fi
  if vh_ports_indexed "$role"; then
    case "$idx" in
      [1-9]) ;;
      *) echo "ports.sh: VH_INDEX must be 1..$VH_GROUP_SIZE for role $role" >&2; return 2 ;;
    esac
    if [ "$idx" -gt "$VH_GROUP_SIZE" ]; then
      echo "ports.sh: VH_INDEX must be 1..$VH_GROUP_SIZE for role $role" >&2
      return 2
    fi
  fi
  VH_DEBUG_PORT=""
  while read -r r n base step bind key; do
    use=1
    if [ "$r" = "$role" ] && vh_ports_indexed "$role"; then
      use="$idx"
    fi
    if [ "$n" = "DEBUG" ]; then
      if [ "$r" = "$role" ]; then
        VH_DEBUG_PORT=$((base + step * (use - 1)))
      fi
      continue
    fi
    eval "VH_$n=$((base + step * (use - 1)))"
    eval "export VH_$n"
  done <<EOF
$(vh_ports_spec)
EOF
  export VH_DEBUG_PORT

  VH_HOST=localhost
  VH_ZK_QUORUM=localhost
  VH_ZK_ZNODE=/hbase
  VH_FS_DEFAULT="hdfs://localhost:$VH_NN_RPC"
  VH_HBASE_ROOTDIR="hdfs://localhost:$VH_NN_RPC/hbase"
  VH_JDBC_URL="jdbc:phoenix:localhost:$VH_ZK_CLIENT:/hbase"
  VH_DN_XFER_ALL="$(vh_port_all DN_XFER)"
  VH_DN_HTTP_ALL="$(vh_port_all DN_HTTP)"
  VH_NM_RPC_ALL="$(vh_port_all NM_RPC)"
  VH_NM_WEB_ALL="$(vh_port_all NM_WEB)"
  VH_RS_RPC_ALL="$(vh_port_all RS_RPC)"
  VH_RS_INFO_ALL="$(vh_port_all RS_INFO)"
  export VH_GROUP_SIZE VH_HOST VH_ZK_QUORUM VH_ZK_ZNODE VH_FS_DEFAULT VH_HBASE_ROOTDIR \
    VH_JDBC_URL VH_DN_XFER_ALL VH_DN_HTTP_ALL VH_NM_RPC_ALL VH_NM_WEB_ALL VH_RS_RPC_ALL \
    VH_RS_INFO_ALL
}

# Prints one row per instance and port.
vh_ports_table() {
  local r n base step bind key i inst
  printf 'instance\trole\tname\tport\tbind\tkey\n'
  while read -r r n base step bind key; do
    if vh_ports_indexed "$r"; then
      i=1
      while [ "$i" -le "$VH_GROUP_SIZE" ]; do
        inst="$r$i"
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$inst" "$r" "$n" \
          $((base + step * (i - 1))) "$bind" "$key"
        i=$((i + 1))
      done
    else
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$r" "$r" "$n" "$base" "$bind" "$key"
    fi
  done <<EOF
$(vh_ports_spec)
EOF
}

# Prints the web UI of each daemon instance.
vh_ports_urls() {
  vh_ports_table | awk -F '\t' 'NR > 1 && $3 ~ /_(HTTP|WEB|INFO)$/ {
    printf "%-8s http://localhost:%s/\n", $1, $4 }'
}

# Fails if two rows share a port, or if a port is outside 1024..54999.
# The upper bound keeps every port below the ephemeral range of the Docker VM.
vh_ports_check() {
  local dups bad p
  dups="$(vh_ports_table | awk -F '\t' 'NR > 1 { print $4 }' | sort -n | uniq -d)"
  bad="$(vh_ports_table | awk -F '\t' 'NR > 1 && ($4 < 1024 || $4 > 54999) { print $1 "/" $3 "=" $4 }')"
  if [ -n "$dups" ]; then
    echo "ports.sh: duplicate ports:" >&2
    for p in $dups; do
      vh_ports_table | awk -F '\t' -v p="$p" '$4 == p { print "  " $1 " " $3 " " $4 }' >&2
    done
  fi
  if [ -n "$bad" ]; then
    echo "ports.sh: ports outside 1024..54999: $bad" >&2
  fi
  [ -z "$dups" ] && [ -z "$bad" ]
}

vh_ports_main() {
  set -euo pipefail
  local cmd="${1:-table}"
  case "$cmd" in
    table) vh_ports_table ;;
    urls) vh_ports_urls ;;
    check)
      vh_ports_check
      echo "ports.sh: $(vh_ports_table | awk 'NR > 1' | wc -l | tr -d ' ') ports, no collisions"
      ;;
    env)
      vh_ports_export "${2:-${VH_ROLE:-}}" "${3:-${VH_INDEX:-1}}"
      env | grep -E '^VH_[A-Z0-9_]+=' | sort
      ;;
    -h|--help|help)
      echo "usage: ports.sh table | urls | check | env [ROLE [INDEX]]"
      ;;
    *)
      echo "usage: ports.sh table | urls | check | env [ROLE [INDEX]]" >&2
      return 2
      ;;
  esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  vh_ports_main "$@"
  exit $?
fi

# Sourced: export the variables of this container. The option "a" stays as the caller had it.
case "$-" in
  *a*) vh_ports_had_a=1 ;;
  *) vh_ports_had_a=0 ;;
esac
set -a
vh_ports_export "${VH_ROLE:-}" "${VH_INDEX:-1}" || {
  vh_ports_rc=$?
  [ "$vh_ports_had_a" = 1 ] || set +a
  unset vh_ports_had_a
  return "$vh_ports_rc"
}
[ "$vh_ports_had_a" = 1 ] || set +a
unset vh_ports_had_a
