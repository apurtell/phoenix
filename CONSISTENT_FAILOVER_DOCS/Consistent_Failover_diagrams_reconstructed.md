# Reconstructed Diagrams: Consistent_Failover.docx

These diagrams were extracted from DrawingML vector shapes embedded in the document.
Shapes with text labels represent states/nodes; connectors represent transitions.

### Background

**States/Nodes:**
  - Application Server
  - HBase  Cluster 1
  - Application [white]
  - Phoenix Client [white]
  - HBase Client [white]
  - SQL [white]
  - HBase Client [white]
  - Scans/ Mutations [white]
  - Region Server [white]
  - HMaster [white]
  - Zookeeper [white]
  - Region Server [white]
  - HMaster [white]
  - Zookeeper [white]
  - Phoenix Dual Cluster Client [white]
  - HA Group [white]
  - HBase  Cluster 2
  - Update and Monitor Records
  - Asynchronous Replication

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - HA Group Store

### HA Architecture

**States/Nodes:**
  - Application Server
  - HBase  Cluster 1
  - Application [white]
  - Phoenix Client [white]
  - HBase Client [white]
  - SQL [white]
  - HBase Client [white]
  - Scans/ Mutations [white]
  - Region Server [white]
  - HMaster [white]
  - Zookeeper [white]
  - HMaster [white]
  - Zookeeper [white]
  - Phoenix Dual Cluster Client [white]
  - HA Group [white]
  - Update and Monitor
  - HBase  Cluster 2
  - Phoenix Server [white]
  - Region Server [white]
  - Phoenix Server [white]
  - Synchronous Replication
  - Pull
  - Push

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - HA Group Store

### Phoenix Synchronous Replication

**States/Nodes:**
  - Application [white]
  - Phoenix Client [white]
  - HBase Client [white]
  - HBase Region Server [white]
  - Phoenix Server [white]
  - HDFS Client [white]
  - HDFS Data Node [white]
  - HBase Client [white]
  - HDFS Data Node [white]
  - HBase Client [white]
  - Primary Cluster
  - Standby Cluster
  - Synchronous Replication
  - HDFS Client [white]
  - Store and Forward  Replication
  - Phoenix Server [white]
  - HDFS Client [white]
  - Asynchronous Log Replay
  - Secondary Index Update
  - WAL Write

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? ⇢ (dashed) Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - Phoenix Table
  - HBase Table
  - HDFS File
  - EBS
  - EBS
  - HDFS File
  - HDFS File
  - HBase Table
  - 1
  - 1
  - 2
  - 1
  - HBase Table
  - 3

### HBase on EBS and HBase on S3 Pair

**States/Nodes:**
  - HBase [white]
  - HDFS [white]
  - Phoenix [white]
  - HBase on EBS Cluster
  - HBase [white]
  - HDFS [white]
  - Phoenix [white]
  - Phoenix Synchronous Replication
  - HBase on S3 Cluster

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - WAL
  - HFile
  - WAL

### HBase on EBS Pair

**States/Nodes:**
  - HBase [white]
  - HDFS [white]
  - Phoenix [white]
  - HBase on EBS Cluster
  - HBase [white]
  - HDFS [white]
  - Phoenix [white]
  - Phoenix Synchronous Replication
  - HBase on EBS Cluster

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - WAL
  - HFile
  - WAL
  - HFile

### Diagram at paragraph 341

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - Offline
  - Standby
  - DegradedStandby
  - Active
  - ActiveToStandby
  - AbortToActive
  - StandbyToActive
  - AbortToStandby

### High Level Components

**States/Nodes:**
  - Phoenix Server [white]
  - Phoenix Dual Cluster Client [white]
  - HDFS Data Node [white]
  - HBase Client [white]
  - Primary Cluster
  - Standby Cluster
  - Phoenix Server [white]
  - HDFS Client [white]
  - Asynchronous Log Replay
  - Store and Forward Log Writer [white]
  - Synchronous Log Writer [white]
  - Replication Log Writer [white]
  - HA Group Store Manager [white]
  - HA Group Store Client [white]
  - Phoenix Coproc [white]
  - Zookeeper Quorum [white]
  - Zookeeper Quorum [white]
  - HA Group Store Manager [white]
  - HA Group Store Client [white]
  - Replication Log Reader [white]
  - Phoenix Compaction [white]
  - HDFS Data Node [white]
  - HDFS Client [white]
  - HDFS Client [white]
  - HA Group Store

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? ⇢ (dashed) Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - EBS
  - HDFS File
  - HBase Table
  - EBS
  - HDFS File
  - HDFS File

### State Tracking

**States/Nodes:**
  - Degraded
  - All stored logs forwarded
  - Degraded
  - Recovery Detected

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

**Labels/Annotations:**
  - SYNC
  - SYNC & Forward
  - Store & Forward
