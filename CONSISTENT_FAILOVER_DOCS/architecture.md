# System Architecture Reference

Extracted from `Consistent_Failover.docx` and `HAGroup.docx`.
Component descriptions and interaction patterns relevant to the failover protocol.

> **Implementation cross-reference**: See `IMPL_CROSS_REFERENCE.md` for a
> comprehensive mapping of these design components to the implementation
> on the `PHOENIX-7562-feature-new` branch (~90 source files, ~23.5K LOC).
> Key divergences from design are annotated with `⚠ IMPL` markers throughout.

---

## System Overview

Phoenix clusters are deployed in pairs (Primary + Standby) in distinct failure domains.
The design provides consistent (zero-RPO) failover between paired clusters using
synchronous replication of data changes to HDFS files on the standby, with asynchronous
replay of those changes into the standby's Phoenix/HBase tables.

### Design Goals

| Goal | Target |
|------|--------|
| Consistency | 100% — zero data loss, zero data integrity issues |
| RTO | < 2 minutes |
| RPO | Zero |
| Availability | >= 99.95% |

---

## Components

### HA Group Store (ZooKeeper)

- Strongly consistent, atomic, linearizable state store
- Holds HA group records as ZooKeeper ZNodes
- Single source of truth for cluster role/state
- Eliminates split-brain scenarios
- Each cluster's state stored in its own ZK quorum
- Peer clusters listen to each other's ZK via watchers
- ~~Multi-operation transactions used for atomic failover steps~~

> **⚠ IMPL**: ZK state is stored at `/phoenix/consistentHA/<group>`. The
> implementation does NOT use ZK multi-operation transactions for failover.
> The final failover step is two separate ZK writes to two independent ZK
> quorums. Local state is cached via Curator `PathChildrenCache`.
> Impl: `PhoenixHAAdmin.java` (CRUD), `HAGroupStoreClient.java` (caching).
> See `IMPL_CROSS_REFERENCE.md` §4 and §11.3.

### HA Group Store Manager

- Implemented as Phoenix coprocessor Endpoint
- Tracks state of active and standby clusters
- Enforces state transition invariants
- Serializes concurrent state updates via ZK optimistic locking
- Key enforced invariants:
  - Two clusters never both Active simultaneously
  - Standby cannot become Active without confirming all logs replayed
  - Each transition checks expected state before finalizing

> **⚠ IMPL**: `HAGroupStoreManager.java` (755 LOC) orchestrates state
> transitions and includes `FailoverManagementListener` (lines 633-706), which
> automates peer-reactive transitions (e.g., detecting peer ATS triggers local
> STA) and local auto-completion transitions (e.g., `ABORT_TO_STANDBY →
> STANDBY`). The listener uses up to 2 retries to handle concurrent updates.
> Singleton per HA group via `ConcurrentHashMap.computeIfAbsent()`.
> See `IMPL_CROSS_REFERENCE.md` §2.5 and §3.1.

### HA Group Store Client

- Runs on region servers as part of PhoenixRegionServerEndPoint coprocessor
- Connects to both local and peer ZK quorums
- Maintains local cache of HA group records (PathChildrenCache)
- Listens and updates HA group records

### Dual Cluster Client

- Client-side component managing connections to both clusters
- Routes mutations exclusively to Active cluster
- Routes lookback queries to either cluster based on metrics
- Retrieves HA records from PhoenixRegionServerEndPoint
- Caches records; non-Active records have configurable TTL
- Detects state transitions via HA-specific exceptions from server

### Replication Log Writer

- Runs in Phoenix/HBase RegionObserver coprocessor hooks (preBatchMutate)
- ~~Captures mutations before local processing~~
- Writes synchronously to standby cluster's HDFS (normal mode)
- Falls back to local HDFS store-and-forward when standby unavailable
- Manages SYNC / Store&Forward / Sync&Forward state machine
- Uses LMAX Disruptor ring buffer for high-throughput queuing
- Rotates log files at configurable interval (default: 1 minute) or size threshold
- Assigns server-side timestamps to cells before replication

> **⚠ IMPL: Post-batch capture, not pre-batch**: The design says capture in
> pre-hooks "before any local processing." The implementation captures in
> `IndexRegionObserver.postBatchMutateIndispensably()` — **after** local WAL
> commit succeeds. This ensures only successfully committed mutations are
> replicated. Impl: `ReplicationLogGroup.java` (1119 LOC), `SyncModeImpl.java`,
> `StoreAndForwardModeImpl.java`, `SyncAndForwardModeImpl.java`. LMAX Disruptor
> uses `ProducerType.MULTI`, `YieldingWaitStrategy`, ring buffer size 32K.
> **Fail-stop**: S&F write error → RS aborts (no further fallback).
> See `IMPL_CROSS_REFERENCE.md` §7.

### Replication Log Reader

- Runs on standby cluster
- Consumes replication log files from source cluster
- Applies mutations via HBase async client API
- Processes logs in rounds to maintain ordering
- Deletes log files only after all mutations confirmed committed
- Coordinates with Phoenix Compaction for max lookback window

> **⚠ IMPL: Replay State Machine (SM6)**: The implementation introduces a
> separate replay state machine (`ReplicationReplayState`: `NOT_INITIALIZED`,
> `SYNC`, `DEGRADED`, `SYNCED_RECOVERY`) not present in the design docs. It
> manages the consistency point — the timestamp before which all mutations are
> guaranteed replicated. During `DEGRADED`, `lastRoundInSync` is frozen;
> during `SYNCED_RECOVERY`, replay rewinds to `lastRoundInSync` before
> transitioning to `SYNC`. Failover trigger requires: (1) `failoverPending`,
> (2) in-progress dir empty, (3) no new files in time window. HDFS lease
> recovery (`RecoverLeaseFSUtils`) handles unclosed files after crashes.
> Impl: `ReplicationLogDiscoveryReplay.java` (607 LOC),
> `ReplicationLogProcessor.java` (592 LOC).
> See `IMPL_CROSS_REFERENCE.md` §2.4, §8.

### Phoenix Compaction

- Manages max lookback window to prevent premature data removal
- Extended to account for replication delays
- Prevents compaction of tombstones and older data within replication window
- Window span expected to be on the order of one minute

> **⚠ IMPL**: Phoenix compaction itself is implemented and active in
> production with a 72-hour max lookback window. What is not yet implemented
> on the `PHOENIX-7562-feature-new` branch is the **dynamic integration**
> proposed in the design, where the replication pipeline would call
> `CompactionScanner.overrideMaxLookback()` to extend the window per
> table/column-family based on observed replication delays. This integration
> is not strictly necessary when the globally configured max lookback
> (72 hours) vastly exceeds the maximum expected rewind span of the replay
> state machine's `SYNCED_RECOVERY` phase (SM6, above), which is bounded by
> the degraded period duration (typically minutes to low hours). The dynamic
> integration would become relevant only if degraded periods approached the
> max lookback duration.

---

## HDFS Directory Layout

Each cluster has two directories for replication:

```
/phoenix-replication/IN    — Receives replication logs from peer cluster
/phoenix-replication/OUT   — Buffers outbound logs when peer is degraded
```

### Normal (SYNC) operation
- Writer writes directly to peer's `/phoenix-replication/IN`

### Store & Forward operation
- Writer writes to local `/phoenix-replication/OUT`
- Background process copies from local OUT to peer IN
- State transitions:
  - SYNC → S&F: start writing to OUT
  - S&F → SYNC&FWD: resume writing to peer IN + drain OUT
  - SYNC&FWD → SYNC: OUT fully drained

> **⚠ IMPL: Sharded directory structure**: The implementation uses a sharded
> layout `/<hdfs-base>/<ha-group-name>/in/shard/<shard-id>/` and
> `/<hdfs-base>/<ha-group-name>/out/shard/<shard-id>/` (shard-id derived from
> RS name). The forwarder (`ReplicationLogDiscoveryForwarder`) copies from
> local `out/` to peer `in/` with configurable throughput throttling.
> See `IMPL_CROSS_REFERENCE.md` §9.

### Transition from ANIS → AIS (Store&Forward → Sync)
Requires:
1. OUT directory empty (all logs forwarded)
2. ZK_Session_Timeout has elapsed since last S&F activity
3. All region servers in sync mode

---

## Replication Log Format

Custom binary format (not reusing HBase WAL):

```
File Structure:
  [MAGIC & VERSION header]
  [BLOCK 1: header (uncompressed) + payload (compressed)]
  [BLOCK 2: header + payload]
  ...
  [BLOCK N: header + payload]
  [OPTIONAL FILE TRAILER]
```

### Block Header (fixed size, uncompressed)
- BLOCK MAGIC: 4 bytes ("PBLK")
- BLOCK HEADER VERSION: 1 byte
- COMPRESSION CODEC: 1 byte (0=none, or LZ4/Snappy/ZStandard ordinal)
- UNCOMPRESSED SIZE: 4 bytes
- COMPRESSED SIZE: 4 bytes

### Change Record (within decompressed block payload)
- Record length (for skip/validate)
- Mutation type: PUT, DELETE, DELETECOLUMN, DELETEFAMILY, DELETEFAMILYVERSION
- Schema object name (table/view)
- Transaction/SCN or commit ID
- Row key (length-prefixed, varint)
- Commit timestamp (long, once per record)
- Number of columns changed (varint)
- Per-column: name, optional old value, new value (all length-prefixed)

### Size Constraints
- Individual file: single HDFS block (default 256 MB)
- Expected records per file: hundreds to hundreds of thousands
- Block compression ratio: ~2:1

---

## HA Group Record Structure

### HAGroupStoreRecord (per-cluster, per-HA-group)

```java
String protocolVersion;
String haGroupName;
HAGroupState haGroupState;   // The cluster's own state only
long lastSyncTimeInMs;       // Timestamp of last sync replication
```

> **⚠ IMPL: Additional fields**: The implementation record includes several
> fields not in the design: `policy` (HA policy FAILOVER/PARALLEL),
> `peerZKUrl`, `clusterUrl`, `peerClusterUrl`, `adminCRRVersion` (application-
> level version for admin ops), `hdfsUrl`, `peerHdfsUrl`. The `lastSyncTimeInMs`
> field is named `lastSyncStateTimeInMs` in the implementation.
> See `IMPL_CROSS_REFERENCE.md` §3.2.

### State vs Role Distinction

- **Role**: External-facing (ACTIVE, STANDBY, ATS, etc.) — exposed to clients
- **State**: Internal sub-state (AIS, ANIS, DSFW, DSFR, etc.) — not exposed to clients
- Each state maps to exactly one role; each role can have multiple states
- Default state for ACTIVE role: ACTIVE_NOT_IN_SYNC
- SYSTEM.HA_GROUP table stores roles as backup for ZK failure recovery

### ClusterRoleRecord (returned to external clients)

Aggregated from local + peer HAGroupStoreRecords.
Contains roles (not internal states) for both clusters.

---

## Key Safety Arguments from Design

### Consistent Failover Proof Sketch (from HAGroup.docx)

1. Admin can only transition ACTIVE_IN_SYNC → ACTIVE_TO_STANDBY (not from ANIS directly to ATS)
2. When AIS, OUT directory is empty and writes to OUT are blocked
3. Standby moves to ACTIVE only after:
   - Detecting active moved to ATS
   - ALL replication logs replayed
   - **⚠ IMPL**: Plus in-progress directory empty AND no new files in time window
4. ~~Active moves ATS → STANDBY only after peer moves to ACTIVE~~
   **⚠ IMPL**: Active moves ATS → STANDBY **reactively** when its
   `FailoverManagementListener` detects peer `ACTIVE_IN_SYNC`. This is NOT
   atomic with step 3 — there is a brief window between the new active writing
   `ACTIVE_IN_SYNC` and the old active writing `STANDBY`. Safety holds because
   the old active is in `ACTIVE_IN_SYNC_TO_STANDBY` (role `ACTIVE_TO_STANDBY`),
   which blocks mutations.
5. Therefore: no committed mutations are lost during failover

> **⚠ IMPL: Additional safety property — Failover Trigger Correctness**:
> `STA → AIS` requires `failoverPending ∧ inProgressDirEmpty ∧
> lastRoundProcessed ≥ lastRoundInSync`. This is enforced by
> `ReplicationLogDiscoveryReplay.shouldTriggerFailover()`.
> See `IMPL_CROSS_REFERENCE.md` §8.3 and §14.4.

### Anti-Flapping Argument

- N = `zookeeper.session.timeout * 1.1`
- RS aborts if ZK DISCONNECTED event received
- Cached state guaranteed no more than N seconds stale
- RS blocks writes and updates ZK if in S&F mode and cached record > N seconds old
- Transition ANIS → AIS requires OUT empty for N seconds

---

## External References

- [PHOENIX-7566](https://issues.apache.org/jira/browse/PHOENIX-7566) — HAGroupStoreManager and HAGroupStoreClient
- [PHOENIX-7493](https://issues.apache.org/jira/browse/PHOENIX-7493) — Initial HAGroupStoreManager implementation
- [ZOOKEEPER-4743](https://issues.apache.org/jira/browse/ZOOKEEPER-4743) — ZK version -1 wildcard issue
- [HBASE-24304](https://issues.apache.org/jira/browse/HBASE-24304) — hbase-asyncfs module
- [Curator Group Membership](https://curator.apache.org/docs/recipes-group-member)
