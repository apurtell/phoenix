# Reconstructed Diagrams: HAGroup.docx

These diagrams were extracted from DrawingML vector shapes embedded in the document.
Shapes with text labels represent states/nodes; connectors represent transitions.

### Overall Design

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
  - HA Group Store Client [white]
  - Replication Log Reader [white]
  - Phoenix Compaction [white]
  - HDFS Data Node [white]
  - HDFS Client [white]
  - HDFS Client [white]
  - HA Group Store
  - RPC [light-blue]
  - HA Group Store Manager [white]
  - RPC [light-blue]
  - Green - Core HAGroup related ComponentsOrange - ClientsBlue - Dependencies
  - HA Group Store Components in HA Re-architecture for Consistent Failover

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

### Problem 1 : Ensuring Atomicity of Updates within the cluster(Single ZK Quorum)

**States/Nodes:**
  - HAGroup Store [light-blue]
  - RegionServer1 [light-blue]
  - HDFS [light-blue]
  - Out directory NOT empty
  - Check OutDirectory
  - RegionServer2 [light-blue]
  - Out directory empty
  - Check OutDirectory
  - RegionServer3 [light-blue]
  - Out directory NOT empty
  - Check OutDirectory
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - Update status to ActiveNotInSync
  - Update status to ActiveInSync
  - Update status to ActiveNotInSync
  - ReplicationLogWriter [light-blue]
  - ReplicationLogWriter [light-blue]
  - ReplicationLogWriter [light-blue]
  - Active
  - Standby

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
  - Shape_? ⇢ (dashed) Shape_?

**Labels/Annotations:**
  - ZK1
  - Z2

### Description of race condition during updates

**States/Nodes:**
  - HAGroup Store [light-blue]
  - RegionServer1 [light-blue]
  - HDFS [light-blue]
  - Out directory NOT empty
  - Check OutDirectory
  - RegionServer2 [light-blue]
  - Out directory empty
  - Check OutDirectory
  - RegionServer3 [light-blue]
  - Out directory NOT empty
  - Check OutDirectory
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - Update status to ActiveNotInSync
  - Update status to Active
  - Update status to ActiveNotInSync
  - ReplicationLogWriter [light-blue]
  - ReplicationLogWriter [light-blue]
  - ReplicationLogWriter [light-blue]
  - RegionServer1’ [light-blue]
  - HDFS [light-blue]
  - Replay NOTHealthy
  - Check ReplayHealth
  - RegionServer2’ [light-blue]
  - Replay Healthy
  - Check ReplayHealth
  - RegionServer3’ [light-blue]
  - Replay NOTHealthy
  - Check ReplayHealth
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - HAGroupStoreManager [light-blue]
  - Update status to DegradedStandbyForReader
  - Update status to Standby
  - Update status to DegradedStandbyForReader
  - ReplicationLogReader [light-blue]
  - ReplicationLogReader [light-blue]
  - ReplicationLogReader [light-blue]
  - Primary
  - Standby

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
  - Shape_? ⇢ (dashed) Shape_?

**Labels/Annotations:**
  - ZK1
  - ZK2

### For both cluster states

**States/Nodes:**
  - How to read combined State Transition Diagram
  - AIS,S [A4C2F4]
  - STA,S [blue]
  - A, ac1
  - Initial Cluster1 Role
  - Initial Cluster2 Role
  - Actor
  - Action orEvent
  - Final Cluster1 Role
  - Final Cluster2 Role

**Transitions/Connections:**
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

### For both cluster states

**States/Nodes:**
  - a. Wait for specific transition from A -> ATS or DSFR -> ATS and then only move S -> STA. For any other state transition, M2 can ignore.b. Admin can impose a timeout and abort if no progress.
  - AIS,S [green]
  - W, i
  - ANIS,S [blue]
  - ANIS,DS [blue]
  - M2, k
  - W, j
  - AISTS,S [blue]
  - A, ac1
  - AISTS,STA [blue]
  - M2, b
  - AISTS,A* [blue]
  - R, c
  - S,AIS [blue]
  - M1, d
  - AbTA,AbTS [blue]
  - AbTA,S [blue]
  - M2, h
  - AIS?,AbTS [blue]
  - M1, f
  - M2, h
  - A, e
  - W, f
  - ATS,AbTS [blue]
  - A, ac2
  - M1, g
  - ActorsA   : AdminR   : ReplicationLogReaderW  : ReplicationLogWriterM1: HAGroupStoreManager for Cluster1M2: HAGroupStoreManager for Cluster2
  - StatesATS: Active To StandbyS: StandbyAIS: Active In SyncANIS: Active Not In SyncANISTS: Active Not In Sync To StandbyDS: Degraded StandbyAbTA: Abort To ActiveAbTS: Abort To StandbyA* : Either of AIS or ANIS
  - Actionac1: Start Failoverac2: Admin decides to abort failoverEventsb: Detect transition to ATS state for peer clusterc: Logs completely replayed and ready to transitiond: Detect transition to AIS/ANIS state for peer cluster and local state is ATS.f:  Detect transition to AbTA  /change local state to AIS/ANIS based on local state of Wg: Detect transition to AbTS state for peer clusterh: Peer state is changed to AbTAi: W changes to Store and Forward Mode j: OUT directory empty (Every W is in sync mode)k: Detect transition to ANIS for peer clusterl:  Detect transition to AIS for peer cluster
  - AIS,DS [blue]
  - W, j
  - M2, l
  - ANISTS,S [blue]
  - A, ac1
  - W, j

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
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?
  - Shape_? → Shape_?

### State Transition Diagram for Active Cluster

**States/Nodes:**
  - AIS(Active In Sync) [green]
  - ATS(Active To Standby) [blue]
  - S(Standby) [blue]
  - AbTAIS(Abort To ActiveInSync) [blue]
  - ANIS(Active Not In Sync) [blue]
  - /Operator changes state from AIS to ATS to initiate failover
  - Peer transitions from STA to AIS/AINS
  - Detect transition from STA to AbTS in peer
  - Detect transition to AbTAIS
  - W changes to Store and Forward Mode
  - OUT directory empty
  - AWOP(Active With Offline Peer) [blue]
  - Peer changes to OFFLINEMode
  - Peer not in OFFLINEMode
  - ANISTS(Active Not In Sync To Standby) [blue]
  - /Operator changes state from ANIS to ANISTS to initiate failover
  - OUT directory empty
  - ANISWOP(Active Not In Sync With Offline Peer) [blue]
  - Peer not in OFFLINEMode
  - Peer changes to OFFLINEMode
  - /Operator aborts failover
  - AbTANIS(Abort To ActiveNotInSync) [blue]
  - Detect transition to AbTANIS

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
  - Shape_? → Shape_?

### State Transition Diagram for Standby Cluster

**States/Nodes:**
  - Standby [green]
  - STA(Standby To Active) [blue]
  - AIS(Active In Sync) [blue]
  - AbTS(Abort To Standby) [blue]
  - DS(Degraded Standby) [blue]
  - Replay completefor Replication Logs
  - Detect peer transition to ANIS(Active Not In Sync)
  - Detect peertransition to AIS(Active In Sync)
  - OFFLINE [blue]
  - /Operator changes state to OFFLINE
  - /Operator changes state to STANDBY
  - /Operator abortsfailover
  - Detect peertransition to AIS(Active In Sync)
  - Detect peer transition to ANIS(Active Not In Sync)
  - Detect peer transition to ATS(Active To Standby)

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

### Avoiding flapping between Store&Forward and Sync mode in case of different modes across RegionServers

**States/Nodes:**
  - Time
  - t0Local: AISPeer: STANDBY
  - t1Local: ANISPeer: STANDBY
  - RS1/RS2 switches from Sync to Store&Forward Mode
  - t2Local: ANISPeer: DSFW
  - Peer reacts to change from AIS to ANIS
  - RS1 can now write logs in Sync modeRS2 still in Store&Forward mode
  - t3Local: ANISPeer: DSFW
  - RS1/RS2 can now write logs in Sync mode
  - t4Local: ANISPeer: DSFW
  - RS1 tries to update the state to Sync mode but update is rejected as other RS can still be in Store&Forward Mode
  - RS1 tries to update the state to Sync mode but update is rejected as ZK_Session_Timeouttime has not yet elapsed
  - t5Local: AISPeer: Standby
  - Local now in Sync modeas ZK_Session_Timeout time has elapsed
  - ZK_Session_Timeout

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

### Appendix

**States/Nodes:**
  - a. Wait for specific transition from A -> ATS or DSFR -> ATS and then only move S -> STA. For any other state transition, M2 can ignore.b. Admin can impose a timeout and abort if no progress.
  - AIS,S [green]
  - W, i
  - ANIS,S [blue]
  - ANIS,DSFW [blue]
  - M2, k
  - AIS,DSFR [blue]
  - R, m
  - ANIS,DSFR [blue]
  - W, i
  - ANIS,DS [blue]
  - M2, k
  - R, m
  - R, n
  - W, j
  - W, j
  - R, n
  - ATS,S [blue]
  - A, ac1
  - ATS,STA [blue]
  - M2, b
  - ATS,A* [blue]
  - R, c
  - S,AIS [blue]
  - M1, d
  - AbTA,AbTS [blue]
  - AbTA,S [blue]
  - M2, h
  - A*,AbTS [blue]
  - W, f
  - M2, h
  - A, e
  - W, f
  - AbTA,STA [blue]
  - A, ac2
  - M2, g
  - A*,STA [blue]
  - W, f
  - M2, g
  - ActorsA   : AdminR   : ReplicationLogReaderW  : ReplicationLogWriterM1: HAGroupStoreManager for Cluster1M2: HAGroupStoreManager for Cluster2
  - StatesATS: Active To StandbyS: StandbyAIS: Active In SyncANIS: Active Not In SyncANISTS: ANIS: Active Not In Sync To StandbyDS: Degraded StandbyDSFR: Degraded Standby For ReaderDSFW: Degraded Standby For WriterAbTA: Abort To ActiveAbTS: Abort To StandbyA* : Either of AIS or ANIS
  - Actionac1: Start Failoverac2: Admin decides to abort failoverEventsb: Detect transition from AIS to ATS state for peer clusterc: Logs completely replayed and ready to transitiond: Detect transition from STA to AIS/ANIS state for peer clusterf:  Detect transition from ATS to AbTA  /change local state to AIS/ANIS based on local state of Wg: Detect transition from ATS to AbTA state for peer clusterh: Local state is changed to AbTSi: W changes to Store and Forward Mode j: OUT directory empty (Every W is in sync mode)k: Detect transition from AIS to ANIS for peer clusterl:  Detect transition from ANIS to AIS for peer clusterm: R has lag exceeding threshold n: R lag is now below threshold
  - AIS,DSFW [blue]
  - W, j
  - M2, l
  - AIS,DS [blue]
  - W, j
  - M2, l
  - ANISTS,S [blue]
  - A, ac1
  - W, j

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
