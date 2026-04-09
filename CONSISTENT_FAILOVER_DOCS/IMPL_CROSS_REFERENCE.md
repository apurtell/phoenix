# Implementation Cross-Reference for TLA+ Specification

Comprehensive cross-reference between the Phoenix Consistent Failover design
documents and the implementation sources. Organized for developing a TLA+
formal specification of the failover protocol.

**Generated**: April 2026
**Branch**: `PHOENIX-7562-feature-new` (HEAD: `5a9e2d50c9`)

---

## Table of Contents

1. [Implementation Inventory Summary](#1-implementation-inventory-summary)
2. [State Machine Cross-Reference](#2-state-machine-cross-reference)
3. [Design-to-Implementation Mapping](#3-design-to-implementation-mapping)
4. [Concurrency Control Cross-Reference](#4-concurrency-control-cross-reference)
5. [Anti-Flapping Protocol Cross-Reference](#5-anti-flapping-protocol-cross-reference)
6. [Failover Orchestration Cross-Reference](#6-failover-orchestration-cross-reference)
7. [Replication Log Writer Cross-Reference](#7-replication-log-writer-cross-reference)
8. [Replication Log Reader Cross-Reference](#8-replication-log-reader-cross-reference)
9. [HDFS Directory Management Cross-Reference](#9-hdfs-directory-management-cross-reference)
10. [Client-Side HA Cross-Reference](#10-client-side-ha-cross-reference)
11. [Divergences and Clarifications](#11-divergences-and-clarifications)
12. [Commit History Index](#12-commit-history-index)
13. [Implementation File Index](#13-implementation-file-index)
14. [TLA+ Modeling Implications](#14-tla-modeling-implications)

---

## 1. Implementation Inventory Summary

### Scale

| Category | Source Files | Test Files | Source LOC | Test LOC |
|----------|-------------|------------|-----------|---------|
| HAGroupStore / HA Core | 21 | 20 | ~7,957 | ~10,686 |
| Parallel/Failover Connection | 10 | 11 | ~3,845 | ~3,126 |
| Replication Core (Writer) | 8 | 6 | ~2,873 | ~6,605 |
| Replication Log Format | 17 | 6 | ~2,744 | ~2,068 |
| Replication Reader/Replay | 5 | 4 | ~1,926 | ~5,172 |
| Replication Metrics | 20 | 0 | ~1,386 | 0 |
| Coprocessor HA Hooks | 2 + hooks | 4 | ~372+ | ~1,245 |
| Admin Tools | 3 | 4 | ~2,389 | ~1,607 |
| **Total** | **~90** | **~55** | **~23,500** | **~30,500** |

### Primary Source Locations

```
phoenix-core-client/src/main/java/org/apache/phoenix/jdbc/
  HAGroupStoreRecord.java        # State enum, transition table, record model
  HAGroupStoreManager.java       # State machine orchestrator, listener dispatch
  HAGroupStoreClient.java        # ZK interaction, caching, peer watchers
  HAGroupStateListener.java      # Observer interface
  PhoenixHAAdmin.java            # ZK CRUD operations
  PhoenixHAAdminTool.java        # CLI admin tool
  ClusterRoleRecord.java         # Client-facing role model
  HighAvailabilityGroup.java     # HA group lifecycle
  FailoverPhoenixConnection.java # Client failover wrapper

phoenix-core-server/src/main/java/org/apache/phoenix/replication/
  ReplicationLogGroup.java       # Writer state machine (LMAX Disruptor)
  SyncModeImpl.java              # SYNC mode
  StoreAndForwardModeImpl.java   # S&F mode + heartbeat
  SyncAndForwardModeImpl.java    # SYNC&FWD mode
  ReplicationLog.java            # Log file lifecycle
  ReplicationLogTracker.java     # File state tracking

phoenix-core-server/src/main/java/org/apache/phoenix/replication/reader/
  ReplicationLogProcessor.java       # Mutation replay engine
  ReplicationLogReplay.java          # Per-group replay orchestrator
  ReplicationLogReplayService.java   # Service lifecycle
  ReplicationLogDiscoveryReplay.java # State-aware round-based discovery

phoenix-core-server/src/main/java/org/apache/phoenix/replication/log/
  LogFile.java                   # Format interfaces
  LogFileWriter.java             # Block-structured writer
  LogFileReader.java             # Block-structured reader
  LogFileCodec.java              # Mutation encoding

phoenix-core-server/src/main/java/org/apache/phoenix/coprocessor/
  PhoenixRegionServerEndpoint.java  # RS coprocessor endpoint
phoenix-core-server/src/main/java/org/apache/phoenix/hbase/index/
  IndexRegionObserver.java          # Mutation capture hooks
```

---

## 2. State Machine Cross-Reference

### 2.1 HAGroupState Enum (SM2 + SM3 combined)

| Design State | Impl Enum Value | Role Mapping | Source |
|---|---|---|---|
| AIS | `ACTIVE_IN_SYNC` | `ACTIVE` | HAGroupStoreRecord.java:55 |
| ANIS | `ACTIVE_NOT_IN_SYNC` | `ACTIVE` | HAGroupStoreRecord.java:56 |
| ATS (from AIS) | `ACTIVE_IN_SYNC_TO_STANDBY` | `ACTIVE_TO_STANDBY` | HAGroupStoreRecord.java:59 |
| ANISTS | `ACTIVE_NOT_IN_SYNC_TO_STANDBY` | `ACTIVE_TO_STANDBY` | HAGroupStoreRecord.java:57 |
| AbTAIS | `ABORT_TO_ACTIVE_IN_SYNC` | `ACTIVE` | HAGroupStoreRecord.java:52 |
| AbTANIS | `ABORT_TO_ACTIVE_NOT_IN_SYNC` | `ACTIVE` | HAGroupStoreRecord.java:53 |
| AWOP | `ACTIVE_WITH_OFFLINE_PEER` | `ACTIVE` | HAGroupStoreRecord.java:60 |
| ANISWOP | `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER` | `ACTIVE` | HAGroupStoreRecord.java:58 |
| S (Standby) | `STANDBY` | `STANDBY` | HAGroupStoreRecord.java:63 |
| STA | `STANDBY_TO_ACTIVE` | `STANDBY_TO_ACTIVE` | HAGroupStoreRecord.java:64 |
| DS | `DEGRADED_STANDBY` | `STANDBY` | HAGroupStoreRecord.java:61 |
| AbTS | `ABORT_TO_STANDBY` | `STANDBY` | HAGroupStoreRecord.java:54 |
| OFFLINE | `OFFLINE` | `OFFLINE` | HAGroupStoreRecord.java:62 |
| UNKNOWN | `UNKNOWN` | `UNKNOWN` | HAGroupStoreRecord.java:65 |

**Design vs Implementation**: The design docs define separate DSFW (Degraded
Standby For Writer) and DSFR (Degraded Standby For Reader) sub-states. The
implementation **collapses** these into a single `DEGRADED_STANDBY` state.
See [Section 11.1](#111-collapsed-degraded-standby-sub-states).

### 2.2 Implemented Transition Table

From `HAGroupStoreRecord.java` static initializer (lines 99-123):

| From | Allowed Targets |
|------|----------------|
| `ACTIVE_NOT_IN_SYNC` | `ACTIVE_NOT_IN_SYNC`, `ACTIVE_IN_SYNC`, `ACTIVE_NOT_IN_SYNC_TO_STANDBY`, `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER` |
| `ACTIVE_IN_SYNC` | `ACTIVE_NOT_IN_SYNC`, `ACTIVE_WITH_OFFLINE_PEER`, `ACTIVE_IN_SYNC_TO_STANDBY` |
| `STANDBY` | `STANDBY_TO_ACTIVE`, `DEGRADED_STANDBY` |
| `ACTIVE_NOT_IN_SYNC_TO_STANDBY` | `ABORT_TO_ACTIVE_NOT_IN_SYNC`, `ACTIVE_IN_SYNC_TO_STANDBY` |
| `ACTIVE_IN_SYNC_TO_STANDBY` | `ABORT_TO_ACTIVE_IN_SYNC`, `STANDBY` |
| `STANDBY_TO_ACTIVE` | `ABORT_TO_STANDBY`, `ACTIVE_IN_SYNC` |
| `DEGRADED_STANDBY` | `STANDBY` |
| `ACTIVE_WITH_OFFLINE_PEER` | `ACTIVE_NOT_IN_SYNC` |
| `ABORT_TO_ACTIVE_IN_SYNC` | `ACTIVE_IN_SYNC` |
| `ABORT_TO_ACTIVE_NOT_IN_SYNC` | `ACTIVE_NOT_IN_SYNC` |
| `ABORT_TO_STANDBY` | `STANDBY` |
| `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER` | `ACTIVE_NOT_IN_SYNC` |
| `OFFLINE` | *(none — operator must manually recover)* |
| `UNKNOWN` | *(none — operator must manually recover)* |

**Key difference from design**: The design allows `STANDBY -> OFFLINE` and
`OFFLINE -> STANDBY` (operator-controlled). The implementation makes `OFFLINE`
a **sink state** with no allowed outbound transitions. See [Section 11.2](#112-offline-as-sink-state).

Also note: `ANIS -> ANIS` (self-transition) is allowed in implementation to
support the periodic heartbeat for Store & Forward mode.

### 2.3 Replication Writer State Machine (SM4)

From `ReplicationLogGroup.java` (line 288-291):

| Design State | Impl Enum Value | Valid Transitions |
|---|---|---|
| *(init)* | `INIT` | → `SYNC`, `STORE_AND_FORWARD` |
| SYNC | `SYNC` | → `STORE_AND_FORWARD`, `SYNC_AND_FORWARD` |
| S&F | `STORE_AND_FORWARD` | → `SYNC_AND_FORWARD` |
| SYNC&FWD | `SYNC_AND_FORWARD` | → `SYNC`, `STORE_AND_FORWARD` |

**Match with design**: The implementation adds an `INIT` state not present in
the design. Also, the design allows `SYNC -> S&F` but implementation adds
`SYNC -> SYNC_AND_FORWARD` (for cases where a forwarder is started while still
in sync). The design allows `SYNC&FWD -> S&F` for degradation during drain,
which is also implemented.

### 2.4 Replication Replay State Machine (new in implementation)

From `ReplicationLogDiscoveryReplay.java` (lines 550-555):

| State | Description |
|---|---|
| `NOT_INITIALIZED` | Pre-init state |
| `SYNC` | Fully in sync, standby replaying current logs |
| `DEGRADED` | Writer degraded (peer in ACTIVE_NOT_IN_SYNC) |
| `SYNCED_RECOVERY` | Transitioning from degraded back to sync, needs rewind |

This state machine is **not described in the design docs**. It is an
implementation-specific mechanism for managing the replay consistency point
during ANIS/AIS transitions. This is a candidate for explicit TLA+ modeling.

### 2.5 Reactive State Transitions (HAGroupStoreManager)

From `HAGroupStoreManager.java` (lines 104-150), the manager defines
**automated peer-reactive transitions** and **local auto-completion
transitions**:

#### Peer State Reactions

| Peer Transitions To | Local Current | Local Moves To |
|---------------------|---------------|----------------|
| `ACTIVE_IN_SYNC_TO_STANDBY` | *(any)* | `STANDBY_TO_ACTIVE` |
| `ACTIVE_IN_SYNC` | `ACTIVE_IN_SYNC_TO_STANDBY` | `STANDBY` |
| `ACTIVE_IN_SYNC` | `DEGRADED_STANDBY` | `STANDBY` |
| `ACTIVE_NOT_IN_SYNC` | `ACTIVE_IN_SYNC_TO_STANDBY` | `STANDBY` |
| `ACTIVE_NOT_IN_SYNC` | `STANDBY` | `DEGRADED_STANDBY` |
| `ABORT_TO_STANDBY` | `ACTIVE_IN_SYNC_TO_STANDBY` | `ABORT_TO_ACTIVE_IN_SYNC` |

#### Local Auto-Completion Transitions

| Local State | Auto-Completes To |
|---|---|
| `ABORT_TO_STANDBY` | `STANDBY` |
| `ABORT_TO_ACTIVE_IN_SYNC` | `ACTIVE_IN_SYNC` |
| `ABORT_TO_ACTIVE_NOT_IN_SYNC` | `ACTIVE_NOT_IN_SYNC` |

These are implemented via `FailoverManagementListener` (lines 633-706), which
subscribes to HAGroupStoreClient's ZK watcher events and drives the state
machine forward automatically. The listener uses up to 2 retries with
`isStateAlreadyUpdated()` to handle concurrent updates from other RS.

---

## 3. Design-to-Implementation Mapping

### 3.1 Component Mapping

| Design Component | Implementation Class(es) | JIRA |
|---|---|---|
| HA Group Store (ZK) | `PhoenixHAAdmin` (ZK CRUD), ZK znodes at `/phoenix/consistentHA/<group>` | PHOENIX-7493 |
| HA Group Store Manager | `HAGroupStoreManager` (facade/orchestrator) | PHOENIX-7566 |
| HA Group Store Client | `HAGroupStoreClient` (ZK interaction, caching, watchers) | PHOENIX-7566 |
| Dual Cluster Client | `FailoverPhoenixConnection`, `ParallelPhoenixConnection`, `HighAvailabilityGroup` | PHOENIX-7569 |
| Replication Log Writer | `ReplicationLogGroup` + `ReplicationModeImpl` subclasses | PHOENIX-7567, PHOENIX-7602 |
| Replication Log Reader | `ReplicationLogProcessor`, `ReplicationLogReplay`, `ReplicationLogDiscoveryReplay` | PHOENIX-7568, PHOENIX-7672 |
| Replication Log Format | `LogFile*` classes in `replication.log` package | PHOENIX-7565 |
| Phoenix Compaction | Not yet implemented in the feature branch | — |
| Admin Tool | `PhoenixHAAdminTool` | PHOENIX-7721 |
| Mutation Capture | `IndexRegionObserver.preBatchMutate()` → `ReplicationLogGroup.append()` | PHOENIX-7601 |

### 3.2 Design Record Structure vs Implementation

| Design Field | Impl Field | Notes |
|---|---|---|
| `protocolVersion` | `protocolVersion` | Default `"1.0"` |
| `haGroupName` | `haGroupName` | — |
| `haGroupState` | `haGroupState` | `HAGroupState` enum |
| `lastSyncTimeInMs` | `lastSyncStateTimeInMs` | Name differs slightly |
| *(not in design)* | `policy` | HA policy (FAILOVER/PARALLEL) |
| *(not in design)* | `peerZKUrl` | Peer ZK quorum URL |
| *(not in design)* | `clusterUrl` | Local cluster URL |
| *(not in design)* | `peerClusterUrl` | Peer cluster URL |
| *(not in design)* | `adminCRRVersion` | Application-level version for admin ops |
| *(not in design)* | `hdfsUrl` | Local HDFS URL for replication |
| *(not in design)* | `peerHdfsUrl` | Peer HDFS URL for replication |

The implementation record has significantly more fields than the design
specified, adding connectivity metadata needed for runtime operations.

### 3.3 Actor-to-Implementation Mapping

| Design Actor | Impl Component | How Actions Are Triggered |
|---|---|---|
| Admin (A) | `PhoenixHAAdminTool` CLI | Operator runs CLI commands |
| ReplicationLogWriter (W) | `ReplicationLogGroup` + mode impls | `IndexRegionObserver.replicateMutations()` → Disruptor → mode impl |
| ReplicationLogReader (R) | `ReplicationLogDiscoveryReplay` + `ReplicationLogProcessor` | Scheduled periodic replay rounds |
| HAGroupStoreManager (M1, M2) | `HAGroupStoreManager.FailoverManagementListener` | ZK watcher events via `PathChildrenCache` |

### 3.4 Event-to-Implementation Mapping

| Design Event | Impl Mechanism | Source |
|---|---|---|
| ac1 (StartFailover) | `HAGroupStoreManager.initiateFailoverOnActiveCluster()` | PhoenixHAAdminTool |
| ac2 (AbortFailover) | `HAGroupStoreManager.setHAGroupStatusToAbortToStandby()` | PhoenixHAAdminTool |
| b (DetectPeerATS) | `FailoverManagementListener` subscribed to peer `ACTIVE_IN_SYNC_TO_STANDBY` | ZK watcher |
| c (ReplayComplete) | `ReplicationLogDiscoveryReplay.triggerFailover()` → `setHAGroupStatusToSync()` | Replay round completion check |
| d (DetectPeerActive) | `FailoverManagementListener` subscribed to peer `ACTIVE_IN_SYNC` | ZK watcher |
| f (DetectAbort) | `FailoverManagementListener` auto-completion from `ABORT_TO_*` states | Local ZK watcher |
| g (PeerDetectsAbort) | `FailoverManagementListener` subscribed to peer `ABORT_TO_STANDBY` | ZK watcher |
| i (WriterToS&F) | `SyncModeImpl.onFailure()` / `SyncAndForwardModeImpl.onFailure()` | IOException during HDFS write |
| j (OutDirEmpty) | `ReplicationLogDiscoveryForwarder` drains OUT → triggers mode change | Forwarder completion |
| k (DetectPeerANIS) | `FailoverManagementListener` subscribed to peer `ACTIVE_NOT_IN_SYNC` | ZK watcher |
| l (DetectPeerAIS) | `FailoverManagementListener` subscribed to peer `ACTIVE_IN_SYNC` | ZK watcher |
| m (ReaderLagHigh) | `HAGroupStoreManager.setReaderToDegraded()` | Reader component call |
| n (ReaderLagLow) | `HAGroupStoreManager.setReaderToHealthy()` | Reader component call |

---

## 4. Concurrency Control Cross-Reference

### 4.1 ZK Optimistic Locking

| Design Concept | Implementation |
|---|---|
| Read current state + version | `PathChildrenCache.getCurrentData()` → cached `Stat.getVersion()` |
| Compute new state | `validateTransitionAndGetWaitTime()` in `HAGroupStoreClient` |
| Write with version check | `Curator.setData().withVersion(readVersion)` in `PhoenixHAAdmin` |
| BadVersionException → retry | Throws `StaleHAGroupStoreRecordVersionException`; callers retry |

**Source**: `HAGroupStoreClient.setHAGroupStatusIfNeeded()` (lines 332-392),
`PhoenixHAAdmin.updateHAGroupStoreRecordInZooKeeper()` (lines 506-524).

### 4.2 Design Rule: Each Cluster Manages Only Its Own State

**Implementation**: Confirmed. Each `HAGroupStoreClient` writes only to its
local ZK quorum's `/phoenix/consistentHA/<group>` znode. Peer state is read
via a separate `peerPathChildrenCache` connected to the peer ZK quorum. No
cross-quorum writes occur for regular state transitions.

**Exception**: The design states "No cross-cluster atomic writes EXCEPT the
final failover step." In the implementation, the final failover step
(`STA -> AIS`) is implemented as a **local-only** write to the standby's ZK.
The old active's transition (`ATS -> S`) is then triggered **reactively** via
the `FailoverManagementListener` when it detects the peer moved to
`ACTIVE_IN_SYNC`. See [Section 11.3](#113-non-atomic-final-failover-step).

### 4.3 Concurrency Mechanisms Summary

| Mechanism | Design | Implementation |
|---|---|---|
| ZK versioned setData | §7: "If BadVersionException: re-read and retry" | `setData().withVersion()`, `StaleHAGroupStoreRecordVersionException` |
| ZK version 32-bit overflow | §7: "comparison must handle overflow" | Noted in code comments, relies on ZK native comparison |
| ZK version -1 wildcard | §7: "must not be used" | Code comment references ZOOKEEPER-4743 |
| PathChildrenCache | *(not in design)* | Curator recipe for local ZK state cache |
| Singleton managers | *(not in design)* | `ConcurrentHashMap.computeIfAbsent()` for `HAGroupStoreManager` |
| Failover setup once | *(not in design)* | `ConcurrentHashMap.newKeySet().add()` atomicity |
| Listener retry | *(not in design)* | `FailoverManagementListener`: up to 2 retries |
| LMAX Disruptor | Design: "high-throughput, low-latency mechanism" | `ProducerType.MULTI`, `YieldingWaitStrategy`, single consumer |

---

## 5. Anti-Flapping Protocol Cross-Reference

### 5.1 Design vs Implementation Parameters

| Design Parameter | Design Value | Impl Constant | Impl Value |
|---|---|---|---|
| N (ZK_Session_Timeout) | `60s × 1.1 = 66s` | `ZK_SESSION_TIMEOUT_MULTIPLIER = 1.1` | `conf.getLong(ZK_SESSION_TIMEOUT) × 1.1` |
| Writer heartbeat | N/2 | `HA_GROUP_STORE_UPDATE_MULTIPLIER = 0.7` | `ZK_SESSION_TIMEOUT × 0.7` |
| Wait for SYNC transition | N seconds | `waitTimeForSyncModeInMs` | `ZK_SESSION_TIMEOUT × 1.1` |

**Note**: The design specifies the heartbeat interval as N/2 (~33s with 60s
ZK timeout). The implementation uses `0.7 × ZK_SESSION_TIMEOUT` (~63s with
90s ZK timeout). The default ZK session timeout also differs: design assumes
60s, implementation uses 90s. The ratios are different: design uses 0.5×,
implementation uses 0.7×.

### 5.2 Anti-Flapping Protocol Rules Cross-Reference

| Design Rule | Implementation | Source |
|---|---|---|
| RS periodically (N/2) writes mode to ZK | `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()`: scheduled at 0.7× ZK timeout | StoreAndForwardModeImpl.java:65-87 |
| ANIS→AIS requires OUT empty for ≥N seconds | `HAGroupStoreClient.validateTransitionAndGetWaitTime()`: checks `mtime + waitTime ≤ now` | HAGroupStoreClient.java:1027-1046 |
| RS SYNC update rejected if N not elapsed | Same `validateTransitionAndGetWaitTime()`: returns positive `remainingTime` | HAGroupStoreClient.java:1044 |
| RS aborts if ZK DISCONNECTED | `PathChildrenCache` listener sets `isHealthy = false` on CONNECTION_LOST | HAGroupStoreClient.java:885 |
| RS blocks writes if cached record > N old | *(not explicitly implemented — relies on ZK session timeout for staleness bound)* | — |

### 5.3 Anti-Flapping Mechanism Detail

The implementation's anti-flapping works through the interaction of two timers:

1. **Heartbeat** (in `StoreAndForwardModeImpl`): Every `0.7 × ZK_SESSION_TIMEOUT`
   ms, re-writes `ACTIVE_NOT_IN_SYNC` to ZK, refreshing the znode's `mtime`.

2. **Wait gate** (in `HAGroupStoreClient`): The `ANIS → AIS` transition requires
   `(mtime + 1.1 × ZK_SESSION_TIMEOUT) ≤ current_time`. Since the heartbeat
   keeps refreshing `mtime` while in S&F mode, the wait gate is never satisfied
   until the heartbeat stops (i.e., the mode exits `STORE_AND_FORWARD`). After
   the heartbeat stops, the cluster must wait the full `1.1 × ZK_SESSION_TIMEOUT`
   before the SYNC transition is allowed.

**For TLA+**: This can be modeled as:
- A clock variable tracking time
- A `lastStoreAndForwardHeartbeat[c]` variable updated every heartbeat interval
- Guard on ANIS→AIS: `clock - lastStoreAndForwardHeartbeat[c] >= WaitTimeForSync`

---

## 6. Failover Orchestration Cross-Reference

### 6.1 Failover Sequence: Design vs Implementation

Design combined state transitions (from `state-machines.md` §5):

```
(AIS, S)     --[A, ac1]-->    (ATS, S)          [start failover]
(ATS, S)     --[M2, b]-->     (ATS, STA)        [standby detects failover]
(ATS, STA)   --[R, c]-->      (ATS, A*)         [replay complete]
(ATS, A*)    --[M1, d]-->     (S, AIS)          [failover complete: ATOMIC ZK OP]
```

Implementation sequence:

| Step | Design | Implementation | Trigger |
|---|---|---|---|
| 1 | `(AIS, S) → (ATS, S)` | Admin calls `initiateFailoverOnActiveCluster()` on active cluster | `PhoenixHAAdminTool --initiate-failover` |
| 2 | `(ATS, S) → (ATS, STA)` | `FailoverManagementListener` on standby detects peer `ACTIVE_IN_SYNC_TO_STANDBY`, sets local to `STANDBY_TO_ACTIVE` | Automatic (ZK watcher) |
| 3 | `(ATS, STA) → (ATS, AIS)` | `ReplicationLogDiscoveryReplay.triggerFailover()` calls `setHAGroupStatusToSync()` when replay complete + IN dir empty | Automatic (replay completion) |
| 4 | `(ATS, AIS) → (S, AIS)` | `FailoverManagementListener` on old active detects peer `ACTIVE_IN_SYNC`, sets local to `STANDBY` | Automatic (ZK watcher) |

**Critical difference**: Step 4 in the design is described as a "single atomic
ZK multi-operation." In the implementation, it is **two separate ZK writes** —
the new active writes `ACTIVE_IN_SYNC` to its own ZK, then the old active
reactively writes `STANDBY` to its own ZK. See [Section 11.3](#113-non-atomic-final-failover-step).

### 6.2 ANIS Failover Path

```
(ANIS, S)    --[A, ac1]-->    (ANISTS, S)       [start failover from ANIS]
(ANISTS, S)  --[W, j]-->      (ATS, S)          [OUT dir now empty]
```

Implementation: `initiateFailoverOnActiveCluster()` (HAGroupStoreManager.java:
375-400) checks current state. If `ACTIVE_NOT_IN_SYNC`, transitions to
`ACTIVE_NOT_IN_SYNC_TO_STANDBY`. The writer's forwarder drains the OUT
directory, and when complete triggers a mode change to SYNC, which calls
`setHAGroupStatusToSync()`, transitioning from `ANISTS` to `ATS`
(= `ACTIVE_IN_SYNC_TO_STANDBY`).

### 6.3 Abort Sequence

Design:
```
(ATS, STA)   --[A, ac2]-->    (AbTA, STA)
(AbTA, STA)  --[M2, g]-->     (AbTA, AbTS)
(AbTA, AbTS) --[M2, h]-->     (A*, AbTS)
(A*, AbTS)   --[W, f]-->      (A*, S)
```

Implementation: The admin tool calls `setHAGroupStatusToAbortToStandby()` on
the **standby** cluster (the one in STA state). This sets the local state to
`ABORT_TO_STANDBY`. The `FailoverManagementListener` on the standby then
auto-completes `ABORT_TO_STANDBY → STANDBY`. The active cluster's listener
detects peer `ABORT_TO_STANDBY` and transitions from `ATS` to
`ABORT_TO_ACTIVE_IN_SYNC`, which auto-completes to `ACTIVE_IN_SYNC`.

**Design vs implementation**: The design says abort from ATS side first, then
STA reacts. The implementation recommends aborting from the STA side (the
standby cluster), consistent with the design's safety note. The admin tool
validates that the local state is `STANDBY_TO_ACTIVE` before allowing abort.

### 6.4 Forced Failover

Design: Allows `DegradedStandby → STA → AIS` even when backlog not drained.

Implementation: `PhoenixHAAdminTool` supports `--force --state` via the
`update` command, but there is **no dedicated force-failover command**. Forced
failover requires manual state manipulation using the `update --force` flag,
which bypasses the `haGroupState` auto-management restriction. The tool
does not validate whether the state transition is semantically valid when
force is used.

---

## 7. Replication Log Writer Cross-Reference

### 7.1 Mutation Capture Pipeline

Design: "Mutation capture will typically occur within the pre-hooks such as
prePut, preDelete, and preBatchMutate."

Implementation:

1. `IndexRegionObserver.preBatchMutate()` resolves `ReplicationLogGroup` for the
   mutation's HA group.
2. After successful local commit, `postBatchMutateIndispensably()` calls
   `replicateMutations()`.
3. `replicateMutations()` iterates all mutations (data + index) and calls
   `logGroup.append(tableName, commitId, mutation)` for each.
4. After all appends, the coprocessor calls `logGroup.sync()` which blocks
   until the Disruptor consumer has flushed all appended records to HDFS.

**Design vs implementation note**: The design says capture happens in pre-hooks
"before any local processing occurs." The implementation actually calls
`append()` in the **post**-batch-mutate phase (after local WAL commit succeeds).
This ensures that only successfully committed mutations are replicated, avoiding
phantom writes. This ordering is consistent with the design's stated goal that
"mutations are replicated only after they are persisted on the active cluster."

### 7.2 LMAX Disruptor Architecture

| Design Concept | Implementation |
|---|---|
| "ring buffer implementation" | `Disruptor<LogEvent>` with `ProducerType.MULTI` |
| "dedicated writer thread" | Single `LogEventHandler` consumer thread |
| Ring buffer size | Configurable, default 32K (`1024 * 32`) |
| Wait strategy | `YieldingWaitStrategy` (low latency, CPU cost) |
| Backpressure | `ringBuffer.next()` blocks when full |
| Error handling | `LogExceptionHandler` → `closeOnError()` (fail-stop) |

### 7.3 Log File Rotation

Design: "configurable interval, expected to be one minute by default"

Implementation: `ReplicationLog.java` — rotation by time (`REPLICATION_LOG_ROTATION_TIME_MS_KEY`,
default 60,000ms) or size (95% of max file size). Generation numbers track rotation.

### 7.4 Store-and-Forward Failure Handling

Design: "If the target cluster's HDFS filesystem becomes unavailable, the writer
switches to store-and-forward mode."

Implementation: `SyncModeImpl.onFailure()` → calls
`setHAGroupStatusToStoreAndForward()` → transitions to `STORE_AND_FORWARD` mode.
If the HAGroupStore update itself fails, the region server aborts (fail-stop).
In `STORE_AND_FORWARD` mode, any `IOException` during write is **fatal** — the
region server aborts. There is no further fallback.

---

## 8. Replication Log Reader Cross-Reference

### 8.1 Round-Based Processing

Design: "replication log files are replayed in rounds... will not start the next
round before completing the current round."

Implementation: `ReplicationLogDiscoveryReplay.replay()` processes rounds
sequentially. Each round is a `ReplicationRound` (start time, end time). The
method `getFirstRoundToProcess()` computes the first round from
`lastRoundInSync.getEndTime()`, with a buffer to avoid processing rounds
too close to current time.

### 8.2 Consistency Point Calculation

**Not in design docs** — this is an implementation-specific mechanism.

From `ReplicationLogDiscoveryReplay.getConsistencyPoint()`:

| Replay State | Consistency Point |
|---|---|
| `SYNC` | Minimum timestamp from in-progress files, or `lastRoundInSync.endTime` |
| `DEGRADED` | `lastRoundInSync.endTime` (frozen at last known good sync) |
| `SYNCED_RECOVERY` | `lastRoundInSync.endTime` (still frozen, rewinding) |

**TLA+ relevance**: This is a key safety mechanism. The consistency point
defines the boundary before which all mutations are guaranteed to have been
replicated and applied. Clients querying the standby should not see data
newer than the consistency point.

### 8.3 Failover Trigger Conditions

From `ReplicationLogDiscoveryReplay.shouldTriggerFailover()` (lines 500-533):

Three conditions must be true before the standby transitions to `ACTIVE_IN_SYNC`:

1. `failoverPending == true` (set by `STANDBY_TO_ACTIVE` listener)
2. In-progress directory is empty
3. No new files exist between the next expected round and current time

When all three are satisfied, `triggerFailover()` calls
`HAGroupStoreManager.setHAGroupStatusToSync(haGroupName)` to transition
from `STANDBY_TO_ACTIVE` to `ACTIVE_IN_SYNC`.

### 8.4 HDFS Lease Recovery

Implementation includes `RecoverLeaseFSUtils` for handling unclosed files
(PHOENIX-7669, PHOENIX-7672). When the writer crashes without closing a log
file, the reader performs HDFS lease recovery before processing. This is
**not covered in the design docs** but is essential for crash recovery.

---

## 9. HDFS Directory Management Cross-Reference

### 9.1 Directory Structure

Design: `/phoenix-replication/IN` and `/phoenix-replication/OUT`

Implementation: `ReplicationShardDirectoryManager` manages a **sharded**
directory structure:

```
/<hdfs-base>/<ha-group-name>/in/shard/<shard-id>/  — receives logs from peer
/<hdfs-base>/<ha-group-name>/out/shard/<shard-id>/ — buffers outbound logs
```

The sharding by shard-id (typically derived from region server name) is an
implementation optimization not present in the design.

### 9.2 Writer Mode vs Directory

| Writer Mode | Writes To | Design Match |
|---|---|---|
| `SYNC` | Peer's `in/shard/` via standby HDFS | Matches: "Writer writes to peer's /IN" |
| `STORE_AND_FORWARD` | Local `out/shard/` via fallback HDFS | Matches: "Writer writes to local /OUT" |
| `SYNC_AND_FORWARD` | Peer's `in/shard/` + forwarder drains local `out/shard/` | Matches: "Writer writes to peer's /IN AND drains local /OUT" |

### 9.3 Forwarder

`ReplicationLogDiscoveryForwarder` handles copying from local `out/` to peer
`in/` with configurable throughput throttling
(`REPLICATION_LOG_COPY_THROUGHPUT_BYTES_PER_MS_KEY`). This is the "background
process to transfer all the logs" mentioned in the design.

---

## 10. Client-Side HA Cross-Reference

### 10.1 Dual Cluster Client

Design: "Routes mutations exclusively to Active cluster"

Implementation:
- `FailoverPhoenixConnection`: Wraps `PhoenixConnection`, detects stale cluster
  roles via `StaleClusterRoleRecordException`, re-creates connection on failover
- `HighAvailabilityGroup`: Manages connections to both clusters, ZK-based CRR
  registry, Curator watchers for role changes
- `HighAvailabilityPolicy.FAILOVER`: Only active cluster serves mutations;
  `PARALLEL`: both clusters serve

### 10.2 State Detection

Design: "HA-specific exceptions from server"

Implementation:
- `StaleClusterRoleRecordException`: Thrown by `IndexRegionObserver` when client
  sends mutation to non-active cluster
- `MutationBlockedIOException`: Thrown when cluster is in `ACTIVE_TO_STANDBY`
  role and mutation blocking is enabled

### 10.3 Client Cache TTL

Design: "Non-Active records are short lived records and their life span (TTL)
is configurable."

Implementation: Configurable via HA group or cluster-level settings. Non-active
records in the client cache expire, forcing re-fetch from server.

---

## 11. Divergences and Clarifications

### 11.1 Collapsed Degraded Standby Sub-States

**Design**: Three separate states — DSFW (Degraded Standby For Writer),
DSFR (Degraded Standby For Reader), DS (both degraded).

**Implementation**: Single `DEGRADED_STANDBY` state. The reader and writer
degradation status is not distinguished at the ZK/HA-group-record level.
The `setReaderToDegraded()`/`setReaderToHealthy()` methods transition between
`STANDBY` and `DEGRADED_STANDBY` without distinguishing the cause.

**TLA+ implication**: The TLA+ model should decide whether to model the design's
three-state degradation or the implementation's single-state. For safety
verification, the single state is sufficient since the mutual exclusion property
does not depend on degradation sub-types. For liveness (recovery from
degradation), the three-state model may reveal more interesting behaviors.

### 11.2 OFFLINE as Sink State

**Design**: `STANDBY → OFFLINE` and `OFFLINE → STANDBY` are defined transitions
(operator-controlled).

**Implementation**: `OFFLINE` has no allowed outbound transitions in the
`HAGroupState.allowedTransitions` table. Once a cluster enters `OFFLINE`, it
cannot transition out via the normal state machine. Recovery requires manual
intervention (direct ZK manipulation or `update --force`).

**TLA+ implication**: If modeling OFFLINE, it should be modeled as a sink state
in the TLA+ spec, matching the implementation. The design's OFFLINE→S transition
is not implemented.

### 11.3 Non-Atomic Final Failover Step

**Design**: "(ATS, A*) → (S, AIS)" is described as "a single atomic ZooKeeper
multi-operation" (`state-machines.md` §5, item 3).

**Implementation**: The final failover is **two separate ZK writes**, each to
a different ZK quorum:
1. New active writes `ACTIVE_IN_SYNC` to its own ZK
2. Old active's `FailoverManagementListener` detects peer `ACTIVE_IN_SYNC`,
   then writes `STANDBY` to its own ZK

This is consistent with the implementation's principle that "each cluster stores
its own state in its own ZK quorum." A true atomic multi-op across two
independent ZK quorums is not possible without a distributed transaction
protocol, which the implementation avoids.

**TLA+ implication**: This is a critical modeling point. The TLA+ spec should
model this as two **separate steps**, not one atomic step. The safety argument
must account for the window between step 1 and step 2 where both clusters
could appear to be Active (new active is `ACTIVE_IN_SYNC`, old active hasn't
yet transitioned to `STANDBY`). The safety is maintained because:
- The old active was already in `ACTIVE_IN_SYNC_TO_STANDBY` (ATS), which has
  `ClusterRole = ACTIVE_TO_STANDBY` — clients don't send mutations to it
- The transition `ATS → STANDBY` is the only allowed next step
- Even if the old active's listener is delayed, no writes reach it

### 11.4 ANIS Self-Transition

**Design**: Not explicitly documented.

**Implementation**: `ACTIVE_NOT_IN_SYNC → ACTIVE_NOT_IN_SYNC` is an allowed
transition (self-transition). This supports the periodic heartbeat in
`StoreAndForwardModeImpl` that re-writes the same state to refresh the ZK
znode's `mtime`. Without this self-transition, the heartbeat would throw
`InvalidClusterRoleTransitionException`.

**TLA+ implication**: The TLA+ model should include a stuttering step for
ANIS state that refreshes the anti-flapping timer without changing the state.

### 11.5 STA → AIS Transition (Design vs Implementation)

**Design**: `STA → AIS` requires "all replication logs replayed" (event c).

**Implementation**: `ReplicationLogDiscoveryReplay.shouldTriggerFailover()`
checks three conditions:
1. `failoverPending == true`
2. In-progress directory is empty
3. No new files between next round and current time

The replay completeness check is more nuanced than a simple boolean. The
in-progress directory emptiness and the time-window check provide additional
safety margins. This should be reflected in the TLA+ model.

### 11.6 Default Initial State

**Design**: Not explicitly stated for initial cluster state.

**Implementation**: When an HA group is first initialized:
- ACTIVE role defaults to `ACTIVE_NOT_IN_SYNC` (ANIS), not `ACTIVE_IN_SYNC`
- STANDBY role defaults to `DEGRADED_STANDBY` initially, then transitions
  to `STANDBY` once the writer achieves sync

This conservative initialization ensures that failover is not attempted before
the first successful synchronous replication is confirmed.

### 11.7 Replay State Machine (Implementation-Only)

The `ReplicationReplayState` enum (`NOT_INITIALIZED`, `SYNC`, `DEGRADED`,
`SYNCED_RECOVERY`) is entirely an implementation artifact not present in
the design docs. It manages the consistency point tracking during
ANIS↔AIS transitions of the active peer:

- `SYNC`: Normal standby replay, both `lastRoundProcessed` and
  `lastRoundInSync` advance together
- `DEGRADED`: Active is in ANIS; `lastRoundProcessed` advances but
  `lastRoundInSync` is frozen
- `SYNCED_RECOVERY`: Active returned to AIS; replay rewinds to
  `lastRoundInSync` position, then transitions to `SYNC`

**TLA+ implication**: This state machine should be modeled as part of the
reader's behavior, particularly for verifying the NoDataLoss property. The
rewind behavior during `SYNCED_RECOVERY` is essential for ensuring that all
mutations from the degraded period are properly replayed before advancing
the consistency point.

---

## 12. Commit History Index

Chronological list of implementation commits on the `PHOENIX-7562-feature-new`
branch, mapped to design components:

| Date | Commit | JIRA | Component | Design Section |
|---|---|---|---|---|
| 4 months ago | `7a4e32d6` | PHOENIX-7565 | Replication log file format (writer + reader) | CF §Replication Log Format |
| 4 months ago | `a0b89942` | PHOENIX-7567 | Replication Log Writer (Synchronous mode) | CF §Replication Log Writer |
| 4 months ago | `389ff74a` | PHOENIX-7640 | Refactor ReplicationLog for HA Groups | Architecture §Components |
| 4 months ago | `491ecfec` | PHOENIX-7601 | Synchronous replication in Phoenix coprocs | CF §Mutation Capture |
| 4 months ago | `66d9077b` | PHOENIX-7632 | ReplicationLogProcessor component | CF §Replication Log Reader |
| 3 months ago | `ffc116155` | PHOENIX-7566 [1/n] | HAGroupStoreClient/Manager for Consistent HA | HG §Overall Design |
| 3 months ago | `1e1b9e52` | PHOENIX-7566 | HAGroupState subscription + ReplicationLogReader state mgmt | HG §Requirements, HG §Avoiding flapping |
| 3 months ago | `755a47a7` | PHOENIX-7669 | Header/Trailer validation for unclosed files | *(crash recovery)* |
| 3 months ago | `aad16f60` | PHOENIX-7672 | HDFS Lease Recovery in ReplicationLogReplay | *(crash recovery)* |
| 3 months ago | `68b137d2` | PHOENIX-7569 | Enhanced Dual Cluster Client | CF §Dual Cluster Client |
| 3 months ago | `b6f3a16b` | PHOENIX-7566 | ZK-to-SystemTable Sync + Event Reactor | HG §System Table, HG §Failing flapping |
| 3 months ago | `efc3d6f7` | PHOENIX-7568 | Replication Log Replay Implementation | CF §Replication Log Reader |
| 3 months ago | `bf908606` | PHOENIX-7566 | Fix: copy lastUpdatedTimeInMs from ACTIVE to STANDBY | HG §Maintaining last sync timestamp |
| 3 months ago | `2fb17d8b` | PHOENIX-7566 | Returning waitTime in state transition response | HG §Anti-flapping |
| 3 months ago | `37de6ecb` | PHOENIX-7672 | Failover via replay (Addendum) | CF §Failover Process |
| 2 months ago | `b55c2996` | PHOENIX-7602 | Replication Log Writer (Store and Forward mode) | CF §Store-and-Forward |
| 2 months ago | `30aaabb4` | PHOENIX-7672 | API for new files within range of rounds | *(replay round mgmt)* |
| 2 months ago | `c3d5ec09` | PHOENIX-7721 | Admin Tool for Consistent Failover | CF §HA Group Store Manager |
| 10 weeks ago | `10a694ba` | PHOENIX-7719 | Prewarm HAGroupStore Client | HG §HA Group Store Client |
| 8 weeks ago | `69378c6c` | — | Handling Unknown role results from Server | *(error handling)* |
| 8 weeks ago | `3045165e` | PHOENIX-7755 | Consistency Point calculation in replay | *(impl-specific)* |
| 8 weeks ago | `2adeaeae` | PHOENIX-7602 | Replication Log Writer S&F (Addendum) | CF §Store-and-Forward |
| 6 weeks ago | `95bc49ae` | PHOENIX-7763 | HAGroupName in URLs for backward compat | HG §Support for Current CRR |
| 6 weeks ago | `2c9bb747` | PHOENIX-7775 | ReplicationLogGroup init fixes | *(bug fix)* |
| 5 weeks ago | `ce85eeca` | PHOENIX-7767/68 | Default states for ACTIVE/STANDBY + HDFS URLs | HG §System Table |
| 2 weeks ago | `5a9e2d50` | PHOENIX-7786 | Empty files handling in Replication Log Processor | *(bug fix)* |

**Key**: CF = Consistent_Failover.docx, HG = HAGroup.docx

---

## 13. Implementation File Index

### 13.1 Core State Machine Files

| File | LOC | Role in TLA+ |
|------|-----|--------------|
| `HAGroupStoreRecord.java` | 306 | State enum + transition table (canonical) |
| `HAGroupStoreManager.java` | 755 | Peer-reactive transitions + auto-completion |
| `HAGroupStoreClient.java` | 1163 | ZK optimistic locking + anti-flapping gate |
| `ReplicationLogGroup.java` | 1119 | Writer mode state machine (INIT/SYNC/S&F/S&FWD) |
| `ReplicationLogDiscoveryReplay.java` | 607 | Reader replay state machine (SYNC/DEGRADED/RECOVERY) |

### 13.2 Mode Implementation Files

| File | LOC | Mode |
|------|-----|------|
| `SyncModeImpl.java` | 80 | SYNC: writes to standby HDFS |
| `StoreAndForwardModeImpl.java` | 129 | S&F: writes locally + periodic heartbeat |
| `SyncAndForwardModeImpl.java` | 84 | S&FWD: writes to standby + drains local |
| `ReplicationModeImpl.java` | 102 | Abstract base for all modes |

### 13.3 Log Format Files

| File | LOC | Purpose |
|------|-----|---------|
| `LogFile.java` | 448 | Format interface hierarchy |
| `LogFileWriter.java` | 131 | Block-structured writer |
| `LogFileReader.java` | 148 | Block-structured reader |
| `LogFileFormatWriter.java` | 219 | Low-level format writing |
| `LogFileFormatReader.java` | 379 | Low-level format reading |
| `LogFileHeader.java` | 111 | File header (PLOG magic) |
| `LogBlockHeader.java` | 122 | Block header (PBLK magic) |
| `LogFileTrailer.java` | 187 | Optional file trailer |
| `LogFileRecord.java` | 179 | Change record within block |
| `LogFileCodec.java` | 278 | Mutation encoding/decoding |
| `CRC64.java` | 76 | Checksum |

### 13.4 Replay Pipeline Files

| File | LOC | Purpose |
|------|-----|---------|
| `ReplicationLogProcessor.java` | 592 | Mutation replay engine |
| `ReplicationLogReplay.java` | 167 | Per-group replay orchestrator |
| `ReplicationLogReplayService.java` | 240 | Service lifecycle |
| `ReplicationLogDiscoveryReplay.java` | 607 | State-aware round discovery |
| `RecoverLeaseFSUtils.java` | 320 | HDFS lease recovery |

### 13.5 Infrastructure Files

| File | LOC | Purpose |
|------|-----|---------|
| `PhoenixHAAdmin.java` | 575 | ZK znode CRUD |
| `PhoenixHAAdminTool.java` | 1622 | CLI admin tool |
| `HAGroupStateListener.java` | 70 | Observer interface |
| `PhoenixRegionServerEndpoint.java` | 313 | RS coprocessor lifecycle |
| `IndexRegionObserver.java` | large | Mutation capture hooks |
| `ReplicationShardDirectoryManager.java` | 258 | HDFS directory mgmt |
| `ReplicationLogTracker.java` | 514 | File state tracking |

---

## 14. TLA+ Modeling Implications

### 14.1 Updated Variables (incorporating implementation details)

```tla
VARIABLES
  \* Per-cluster HA state (from HAGroupStoreRecord.HAGroupState)
  clusterState,         \* [c \in Cluster |-> HAGroupState]

  \* Per-cluster, per-RS writer mode (from ReplicationLogGroup.ReplicationMode)
  writerMode,           \* [c \in Cluster |-> [rs \in RS |-> WriterMode]]

  \* Per-cluster replay state (from ReplicationLogDiscoveryReplay.ReplicationReplayState)
  replayState,          \* [c \in Cluster |-> ReplayState]

  \* HDFS directory predicates
  outDirEmpty,          \* [c \in Cluster |-> BOOLEAN]
  inProgressDirEmpty,   \* [c \in Cluster |-> BOOLEAN]

  \* Consistency tracking
  lastRoundInSync,      \* [c \in Cluster |-> Nat]
  lastRoundProcessed,   \* [c \in Cluster |-> Nat]

  \* ZooKeeper state
  zkRecord,             \* [c \in Cluster |-> HAGroupStoreRecord]
  zkMtime,              \* [c \in Cluster |-> Nat]  (for anti-flapping)
  zkVersion,            \* [c \in Cluster |-> Nat]

  \* Timing
  clock,                \* Nat: logical clock
  lastHeartbeat,        \* [c \in Cluster |-> [rs \in RS |-> Nat]]

  \* Admin / environment
  adminAction,          \* {None, StartFailover, AbortFailover, ForceFailover}
  failoverPending,      \* [c \in Cluster |-> BOOLEAN]
  hdfsAvailable         \* [c \in Cluster |-> BOOLEAN]
```

### 14.2 Updated State Enumerations

```tla
\* From HAGroupStoreRecord.HAGroupState (14 states)
HAGroupState == {
  ACTIVE_IN_SYNC, ACTIVE_NOT_IN_SYNC,
  ACTIVE_IN_SYNC_TO_STANDBY, ACTIVE_NOT_IN_SYNC_TO_STANDBY,
  ABORT_TO_ACTIVE_IN_SYNC, ABORT_TO_ACTIVE_NOT_IN_SYNC,
  ACTIVE_WITH_OFFLINE_PEER, ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER,
  STANDBY, STANDBY_TO_ACTIVE,
  DEGRADED_STANDBY,
  ABORT_TO_STANDBY,
  OFFLINE, UNKNOWN
}

\* From ReplicationLogGroup.ReplicationMode (4 modes)
WriterMode == { INIT, SYNC, STORE_AND_FORWARD, SYNC_AND_FORWARD }

\* From ReplicationLogDiscoveryReplay.ReplicationReplayState (4 states)
ReplayState == { NOT_INITIALIZED, SYNC_REPLAY, DEGRADED_REPLAY, SYNCED_RECOVERY }
```

### 14.3 Corrected Safety Properties

Based on implementation, the mutual exclusion invariant should use the
`ClusterRole` mapping, not raw states:

```tla
\* Active roles: AIS, ANIS, AbTAIS, AbTANIS, AWOP, ANISWOP
ActiveStates == {
  ACTIVE_IN_SYNC, ACTIVE_NOT_IN_SYNC,
  ABORT_TO_ACTIVE_IN_SYNC, ABORT_TO_ACTIVE_NOT_IN_SYNC,
  ACTIVE_WITH_OFFLINE_PEER, ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER
}

MutualExclusion ==
  ~(clusterState[C1] \in ActiveStates /\ clusterState[C2] \in ActiveStates)
```

The non-atomic failover window means mutual exclusion must account for the
brief period where the new active is `ACTIVE_IN_SYNC` and the old active is
still `ACTIVE_IN_SYNC_TO_STANDBY` (which maps to `ACTIVE_TO_STANDBY` role,
NOT an active role). Since `ACTIVE_IN_SYNC_TO_STANDBY` is NOT in
`ActiveStates`, mutual exclusion holds even during this window.

### 14.4 New Safety Property: Failover Trigger Correctness

```tla
FailoverTriggerCorrectness ==
  (clusterState[c] = STANDBY_TO_ACTIVE /\ clusterState'[c] = ACTIVE_IN_SYNC)
  => ( failoverPending[c]
     /\ inProgressDirEmpty[c]
     /\ lastRoundProcessed[c] >= lastRoundInSync[c] )
```

### 14.5 Implementation-Specific Behaviors to Model

1. **ANIS self-transition** (heartbeat): A stuttering step that refreshes
   `zkMtime[c]` without changing `clusterState[c]`.

2. **Anti-flapping gate**: Guard on `ANIS → AIS` transition:
   `clock - zkMtime[c] >= WaitTimeForSync`

3. **Non-atomic failover**: Two separate steps instead of one atomic step.
   Safety relies on the old active being in ATS (a non-Active role) during
   the window.

4. **Replay rewind**: When `replayState` transitions from `DEGRADED_REPLAY`
   to `SYNCED_RECOVERY`, `lastRoundProcessed` is reset to `lastRoundInSync`.
   This must be modeled to verify NoDataLoss.

5. **Writer fail-stop**: If `STORE_AND_FORWARD` mode encounters an error,
   the region server aborts. Model as a crash/halt action.

6. **Optimistic locking races**: Multiple RS can race to update the same
   ZK znode. Only one succeeds per version; others retry. Model as
   non-deterministic choice among concurrent RS.

### 14.6 Recommended Simplifications for Initial TLA+ Model

| Aspect | Simplification | Justification |
|--------|---------------|---------------|
| Number of RS | Fix at 2 per cluster | Sufficient to model races |
| HDFS failures | Non-deterministic boolean | Captures failure/recovery |
| ZK versions | Monotonically increasing Nat | Avoid overflow modeling initially |
| Timing | Logical clock with threshold guards | Avoid real-time modeling |
| Replay rounds | Abstract as monotonic counter | Details not needed for safety |
| Log format | Omit entirely | Not relevant to protocol safety |
| Metrics | Omit entirely | Not relevant to correctness |
| Client connections | Omit entirely | Focus on server-side protocol |
