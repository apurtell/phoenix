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

# The variables are for the daemons and scripts that run after this file, so none is read here.
# shellcheck disable=SC2034

# This file sets the process environment of one container role.
# The entrypoint sources ports.sh first and this file second.
# It selects the JDK, the heap, the JVM options, and the log and pid directories.
# It also exports the cluster settings that the configuration templates use.
# The file only sets variables. The entrypoint creates the directories in VH_ROLE_DIRS.
#
# Hadoop daemons and ZooKeeper run on JDK 17. HBase daemons, the tools and the harness
# run on JDK 21 with the incubating vector module.
# Daemon-specific flags such as the GC log go only into the per-daemon option variables.
# A command line tool in the same container therefore never writes to the daemon GC log.

case "$-" in
  *a*) vh_roles_had_a=1 ;;
  *) vh_roles_had_a=0 ;;
esac
set -a

: "${VH_ROLE:?roles.sh needs VH_ROLE}"
case "$VH_ROLE" in
  dn|nm|rs) VH_INST="${VH_ROLE}${VH_INDEX:?roles.sh needs VH_INDEX for role $VH_ROLE}" ;;
  *) VH_INST="$VH_ROLE" ;;
esac

# Install locations in the image.
VH_JAVA17_HOME=/opt/jdk17
VH_JAVA21_HOME=/opt/jdk21
VH_HADOOP_HOME=/opt/hadoop
VH_HBASE_HOME=/opt/hbase
VH_HARNESS_HOME=/opt/vector-harness
VH_ASPROF_HOME=/opt/async-profiler
VH_HOME=/opt/vh
VH_HADOOP_CONF=/etc/vh/hadoop
VH_HBASE_CONF=/etc/vh/hbase
VH_CLIENT_CONF=/etc/vh/client
VH_USER=vh

# Per-instance runtime directories.
VH_LOG_DIR="/var/log/vh/$VH_INST"
VH_PID_DIR=/run/vh
VH_PROF_DIR="$VH_LOG_DIR/prof"
VH_JFR_DIR="$VH_LOG_DIR/jfr"

# Daemon data directories. Each one is the mount point of a named volume.
VH_DATA_ROOT=/vh/data
VH_ZK_DIR=/vh/data/zk
VH_NN_DIR=/vh/data/nn
VH_SNN_DIR=/vh/data/snn
VH_DN_DIR=/vh/data/dn
VH_NM_DIR=/vh/data/nm
VH_NM_LOCAL_DIRS=/vh/data/nm/local
VH_NM_LOG_DIRS=/vh/data/nm/logs

# Cluster settings for the templates. The compose file can override each one.
VH_DFS_REPLICATION="${VH_DFS_REPLICATION:-3}"
VH_BALANCER_PERIOD="${VH_BALANCER_PERIOD:-60000}"
VH_HNSW_OFFHEAP_MAX_BYTES="${VH_HNSW_OFFHEAP_MAX_BYTES:-1073741824}"
VH_NM_MEMORY_MB="${VH_NM_MEMORY_MB:-2048}"
VH_NM_VCORES="${VH_NM_VCORES:-2}"
VH_YARN_MIN_ALLOC_MB="${VH_YARN_MIN_ALLOC_MB:-256}"
VH_YARN_MAX_ALLOC_MB="${VH_YARN_MAX_ALLOC_MB:-$VH_NM_MEMORY_MB}"
VH_ZK_MAX_CLIENT_CNXNS="${VH_ZK_MAX_CLIENT_CNXNS:-0}"
VH_RPC_TIMEOUT_MS="${VH_RPC_TIMEOUT_MS:-120000}"
VH_SCANNER_TIMEOUT_MS="${VH_SCANNER_TIMEOUT_MS:-120000}"
VH_TASK_INTERVAL_MS="${VH_TASK_INTERVAL_MS:-10000}"
VH_CLUSTER_ID="${VH_CLUSTER_ID:-CID-vector-harness}"

# Heaps. Each daemon uses the same value for Xms and Xmx.
VH_HEAP_ZK="${VH_HEAP_ZK:-1g}"
VH_HEAP_NN="${VH_HEAP_NN:-2g}"
VH_HEAP_SNN="${VH_HEAP_SNN:-2g}"
VH_HEAP_DN="${VH_HEAP_DN:-1g}"
VH_HEAP_RM="${VH_HEAP_RM:-1g}"
VH_HEAP_NM="${VH_HEAP_NM:-1g}"
VH_HEAP_JHS="${VH_HEAP_JHS:-1g}"
VH_HEAP_MASTER="${VH_HEAP_MASTER:-1g}"
VH_HEAP_RS="${VH_HEAP_RS:-4g}"
VH_DIRECT_RS="${VH_DIRECT_RS:-2g}"
VH_HEAP_TOOLS="${VH_HEAP_TOOLS:-2g}"
VH_DIRECT_TOOLS="${VH_DIRECT_TOOLS:-2g}"
VH_HEAP_CLI="${VH_HEAP_CLI:-1g}"
VH_HARNESS_HEAP="${VH_HARNESS_HEAP:-6g}"
VH_HARNESS_DIRECT="${VH_HARNESS_DIRECT:-2g}"
VH_HARNESS_SIMD="${VH_HARNESS_SIMD:-true}"
VH_HARNESS_EXTRA_OPTS="${VH_HARNESS_EXTRA_OPTS:-}"
VH_RUN_DIR="${VH_RUN_DIR:-}"

# Diagnostic switches.
VH_RS_SIMD="${VH_RS_SIMD:-1}"
VH_JVECTOR_FORCE_PANAMA="${VH_JVECTOR_FORCE_PANAMA:-0}"
VH_JFR="${VH_JFR:-0}"
VH_DEBUG="${VH_DEBUG:-0}"

# Shared homes and tool settings for every role.
HADOOP_HOME="$VH_HADOOP_HOME"
HADOOP_COMMON_HOME="$VH_HADOOP_HOME"
HADOOP_HDFS_HOME="$VH_HADOOP_HOME"
HADOOP_YARN_HOME="$VH_HADOOP_HOME"
HADOOP_MAPRED_HOME="$VH_HADOOP_HOME"
HADOOP_CONF_DIR="$VH_HADOOP_CONF"
HADOOP_LOG_DIR="$VH_LOG_DIR"
HADOOP_PID_DIR="$VH_PID_DIR"
HADOOP_IDENT_STRING="$VH_INST"
HADOOP_ROOT_LOGGER=INFO,console
HADOOP_OPTS="-Djava.net.preferIPv4Stack=true -XX:+UseG1GC"
HBASE_HOME="$VH_HBASE_HOME"
HBASE_CONF_DIR="$VH_HBASE_CONF"
HBASE_CLASSPATH="$VH_HADOOP_CONF"
HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true
HBASE_LIBRARY_PATH="$VH_HADOOP_HOME/lib/native"
HBASE_LOG_DIR="$VH_LOG_DIR"
HBASE_PID_DIR="$VH_PID_DIR"
HBASE_IDENT_STRING="$VH_INST"
HBASE_LOGFILE="hbase-$VH_INST.log"
HBASE_ROOT_LOGGER=INFO,console
HBASE_MANAGES_ZK=false
HBASE_HEAPSIZE=""
HBASE_OFFHEAPSIZE=""

# Option groups. No option contains a space, because the launch scripts split on spaces.
vh_opt_diag="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
vh_opt_gc="-Xlog:gc*,safepoint:file=$VH_LOG_DIR/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m"
vh_opt_dump="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$VH_LOG_DIR"
vh_opt_jfr=""
if [ "$VH_JFR" = "1" ]; then
  vh_opt_jfr="-XX:StartFlightRecording=name=vh,settings=default,disk=true,maxage=30m,maxsize=512m,dumponexit=true,filename=$VH_JFR_DIR/"
fi
vh_opt_debug=""
if [ "$VH_DEBUG" = "1" ] && [ -n "${VH_DEBUG_PORT:-}" ]; then
  vh_opt_debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:$VH_DEBUG_PORT"
fi
vh_opt_daemon="$vh_opt_gc $vh_opt_dump $vh_opt_jfr $vh_opt_debug"
vh_opt_vector="--add-modules jdk.incubator.vector"
if [ "$VH_ROLE" = "rs" ] && [ "$VH_RS_SIMD" = "0" ]; then
  vh_opt_vector=""
fi
vh_opt_panama=""
if [ "$VH_JVECTOR_FORCE_PANAMA" = "1" ] && [ -n "$vh_opt_vector" ]; then
  vh_opt_panama="-Djvector.vectorization_provider=panama"
fi
vh_opt_hbase21="-XX:+UseG1GC -Djava.net.preferIPv4Stack=true -Djava.security.manager=allow $vh_opt_vector --add-opens java.base/jdk.internal.util.random=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED $vh_opt_diag $vh_opt_panama"

# Small daemons see two processors, so their GC and pool threads stay small.
vh_opt_small="-XX:ActiveProcessorCount=2 $vh_opt_diag"

VH_ROLE_KIND=daemon
VH_ROLE_HEAP=""
VH_ROLE_DIRECT=""
VH_PROC_PATTERN=""
VH_ROLE_DIRS="$VH_LOG_DIR $VH_PROF_DIR $VH_JFR_DIR $VH_PID_DIR"

case "$VH_ROLE" in
  zk)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_ZK"
    VH_PROC_PATTERN=proc_zookeeper
    HBASE_HEAPSIZE="$VH_HEAP_ZK"
    HBASE_OPTS="-XX:+UseG1GC -Djava.net.preferIPv4Stack=true $vh_opt_small"
    HBASE_ZOOKEEPER_OPTS="-Xms$VH_HEAP_ZK -Dzookeeper.admin.enableServer=false $vh_opt_daemon"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_ZK_DIR"
    ;;
  nn)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_NN"
    VH_PROC_PATTERN=proc_namenode
    HDFS_NAMENODE_OPTS="-Xms$VH_HEAP_NN -Xmx$VH_HEAP_NN $vh_opt_small $vh_opt_daemon"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_NN_DIR"
    ;;
  snn)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_SNN"
    VH_PROC_PATTERN=proc_secondarynamenode
    HDFS_SECONDARYNAMENODE_OPTS="-Xms$VH_HEAP_SNN -Xmx$VH_HEAP_SNN $vh_opt_small $vh_opt_daemon"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_SNN_DIR"
    ;;
  dn)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_DN"
    VH_PROC_PATTERN=proc_datanode
    HDFS_DATANODE_OPTS="-Xms$VH_HEAP_DN -Xmx$VH_HEAP_DN $vh_opt_small $vh_opt_daemon"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_DN_DIR"
    ;;
  rm)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_RM"
    VH_PROC_PATTERN=proc_resourcemanager
    YARN_RESOURCEMANAGER_OPTS="-Xms$VH_HEAP_RM -Xmx$VH_HEAP_RM $vh_opt_small $vh_opt_daemon"
    ;;
  nm)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_NM"
    VH_PROC_PATTERN=proc_nodemanager
    YARN_NODEMANAGER_OPTS="-Xms$VH_HEAP_NM -Xmx$VH_HEAP_NM $vh_opt_small $vh_opt_daemon"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_NM_DIR $VH_NM_LOCAL_DIRS $VH_NM_LOG_DIRS"
    ;;
  jhs)
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_JHS"
    VH_PROC_PATTERN=proc_historyserver
    MAPRED_HISTORYSERVER_OPTS="-Xms$VH_HEAP_JHS -Xmx$VH_HEAP_JHS $vh_opt_small $vh_opt_daemon"
    ;;
  master)
    JAVA_HOME="$VH_JAVA21_HOME"
    VH_ROLE_HEAP="$VH_HEAP_MASTER"
    VH_PROC_PATTERN=proc_master
    HBASE_HEAPSIZE="$VH_HEAP_MASTER"
    HBASE_OPTS="$vh_opt_hbase21"
    HBASE_MASTER_OPTS="-Xms$VH_HEAP_MASTER -XX:ActiveProcessorCount=4 $vh_opt_daemon"
    ;;
  rs)
    JAVA_HOME="$VH_JAVA21_HOME"
    VH_ROLE_HEAP="$VH_HEAP_RS"
    VH_ROLE_DIRECT="$VH_DIRECT_RS"
    VH_PROC_PATTERN=proc_regionserver
    HBASE_HEAPSIZE="$VH_HEAP_RS"
    HBASE_OFFHEAPSIZE="$VH_DIRECT_RS"
    HBASE_OPTS="$vh_opt_hbase21"
    HBASE_REGIONSERVER_OPTS="-Xms$VH_HEAP_RS -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 $vh_opt_daemon"
    ;;
  hdfs-init)
    VH_ROLE_KIND=oneshot
    JAVA_HOME="$VH_JAVA17_HOME"
    VH_ROLE_HEAP="$VH_HEAP_CLI"
    HADOOP_CLIENT_OPTS="-Xmx$VH_HEAP_CLI"
    ;;
  cluster-ready)
    VH_ROLE_KIND=oneshot
    JAVA_HOME="$VH_JAVA21_HOME"
    VH_ROLE_HEAP="$VH_HEAP_CLI"
    HBASE_HEAPSIZE="$VH_HEAP_CLI"
    HBASE_OPTS="$vh_opt_hbase21"
    ;;
  tools)
    VH_ROLE_KIND=client
    JAVA_HOME="$VH_JAVA21_HOME"
    VH_ROLE_HEAP="$VH_HEAP_TOOLS"
    VH_ROLE_DIRECT="$VH_DIRECT_TOOLS"
    HBASE_HEAPSIZE="$VH_HEAP_TOOLS"
    HBASE_OFFHEAPSIZE="$VH_DIRECT_TOOLS"
    HBASE_OPTS="$vh_opt_hbase21 $vh_opt_debug"
    HADOOP_CLIENT_OPTS="-Xmx$VH_HEAP_TOOLS"
    ;;
  harness)
    VH_ROLE_KIND=client
    JAVA_HOME="$VH_JAVA21_HOME"
    VH_ROLE_HEAP="$VH_HARNESS_HEAP"
    VH_ROLE_DIRECT="$VH_HARNESS_DIRECT"
    VH_PROC_PATTERN=org.apache.phoenix.vectorharness.VectorHarness
    HBASE_CONF_DIR="$VH_CLIENT_CONF"
    HARNESS_SIMD="$VH_HARNESS_SIMD"
    # The run directory holds the GC log, so the log stays with the run artifacts.
    VH_HARNESS_GC_DIR="${VH_RUN_DIR:-$VH_LOG_DIR}"
    VH_ROLE_DIRS="$VH_ROLE_DIRS $VH_HARNESS_GC_DIR /work/prof"
    HARNESS_OPTS="-Xms$VH_HARNESS_HEAP -Xmx$VH_HARNESS_HEAP -XX:MaxDirectMemorySize=$VH_HARNESS_DIRECT -XX:+UseG1GC -Djava.net.preferIPv4Stack=true $vh_opt_diag -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/work/prof -Xlog:gc*:file=$VH_HARNESS_GC_DIR/harness-gc.log:time,uptime,level,tags $vh_opt_jfr $vh_opt_debug $VH_HARNESS_EXTRA_OPTS"
    ;;
  *)
    echo "roles.sh: unknown VH_ROLE '$VH_ROLE'" >&2
    [ "$vh_roles_had_a" = 1 ] || set +a
    return 2
    ;;
esac
PATH="$JAVA_HOME/bin:$VH_HOME/bin:$VH_HADOOP_HOME/bin:$VH_HBASE_HOME/bin:$VH_ASPROF_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

unset vh_opt_diag vh_opt_gc vh_opt_dump vh_opt_jfr vh_opt_debug vh_opt_daemon vh_opt_vector \
  vh_opt_panama vh_opt_hbase21 vh_opt_small
[ "$vh_roles_had_a" = 1 ] || set +a
unset vh_roles_had_a
