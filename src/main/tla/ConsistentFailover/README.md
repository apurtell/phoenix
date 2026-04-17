# Phoenix Consistent Failover -- TLA+ Specification

Formal specification of the Phoenix Consistent Failover protocol using TLA+ and the TLC model checker.  The spec verifies safety properties (mutual exclusion, zero RPO, abort correctness) under arbitrary interleavings of admin actions, HDFS failures, RS crashes, ZK connection/session failures, watcher retry exhaustion, and the anti-flapping timer.

## Solution Design

Phoenix clusters are deployed in pairs across distinct failure domains. The Consistent Failover protocol provides zero-RPO failover between a Primary (active) and Standby cluster using Phoenix Synchronous Replication. Every committed mutation on the active cluster is synchronously written to a replication log file on the standby cluster's HDFS before the mutation is acknowledged. A set of replay threads on the standby asynchronously consumes these log files round-by-round, applying changes to local HBase tables so the standby remains close to in-sync with the active.

```
 ┌──────────────────────────────────────────────────────────────────┐
 │                      Active Cluster (FD 1)                       │
 │                                                                  │
 │  Admin ──► HAGroupStoreManager ─ setData (CAS) ─► ZK Quorum 1    │
 │                                                        ▲         │
 │            HAGroupStoreClient ── watch (local) ────────┘         │
 │                 ·                                                │
 │                 · watch (peer) ···············► ZK Quorum 2      │
 │                                                 (remote)         │
 │                                                                  │
 │  ReplicationLogWriter (per RS)                                   │
 │    ├── SYNC ─────────────────────────► Standby HDFS / IN         │
 │    └── STORE_AND_FORWARD ──► HDFS / OUT                          │
 │                                   │                              │
 │                              Forwarder ──► Standby HDFS / IN     │
 └──────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────┐
 │                     Standby Cluster (FD 2)                       │
 │                                                                  │
 │            HAGroupStoreManager ─ setData (CAS) ─► ZK Quorum 2    │
 │                                                        ▲         │
 │            HAGroupStoreClient ── watch (local) ────────┘         │
 │                 ·                                                │
 │                 · watch (peer) ···············► ZK Quorum 1      │
 │                                                 (remote)         │
 │                                                                  │
 │  HDFS / IN ──► ReplicationLogReader ──► HBase Tables             │
 │                  (round-by-round replay)                         │
 └──────────────────────────────────────────────────────────────────┘
```

### Actors

| Actor | Role |
|-------|------|
| **Admin** | Human operator; initiates or aborts failover via CLI |
| **HAGroupStoreManager** | Per-cluster coprocessor endpoint; automates peer-reactive state transitions via `FailoverManagementListener` with up to 2 retries |
| **ReplicationLogWriter** | Per-RegionServer on the active cluster; captures mutations post-WAL-commit and writes them to standby HDFS (`SYNC` mode) or local HDFS (`STORE_AND_FORWARD` mode) |
| **ReplicationLogReader** | On the standby cluster; replays replication logs round-by-round, manages the consistency point, and triggers the final STA-to-AIS transition |
| **HAGroupStoreClient** | Per-RS ZK interaction layer; caches state, enforces anti-flapping, validates transitions |

### State Machines

The protocol is governed by six interrelated state machines, all modeled in this specification:

**HA Group State (14 states).**
Each cluster's lifecycle: `ACTIVE_IN_SYNC`, `ACTIVE_NOT_IN_SYNC`, `ACTIVE_IN_SYNC_TO_STANDBY`, `ACTIVE_NOT_IN_SYNC_TO_STANDBY`, `STANDBY`, `STANDBY_TO_ACTIVE`, `DEGRADED_STANDBY`, `ABORT_TO_ACTIVE_IN_SYNC`, `ABORT_TO_ACTIVE_NOT_IN_SYNC`, `ABORT_TO_STANDBY`, `ACTIVE_WITH_OFFLINE_PEER`, `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER`, `OFFLINE`, `UNKNOWN`. States map to roles visible to clients: ACTIVE (serves reads/writes), ACTIVE_TO_STANDBY (mutations blocked), `STANDBY`, `STANDBY_TO_ACTIVE`, `OFFLINE`, `UNKNOWN`.

**Replication Writer Mode (4 modes per RS).**
`INIT` (pre-initialization), `SYNC` (writing directly to standby HDFS), `STORE_AND_FORWARD` (writing locally when standby is unavailable), `SYNC_AND_FORWARD` (draining local queue while also writing synchronously). A write error in `STORE_AND_FORWARD` mode triggers RS abort (fail-stop).

**Replication Replay State (4 states per cluster).**
`NOT_INITIALIZED`, `SYNC` (fully in sync), `DEGRADED` (active peer in `ANIS`; `lastRoundInSync` frozen), `SYNCED_RECOVERY` (active returned to `AIS`; replay rewinds to `lastRoundInSync`).

**Combined Product State.**
The (ActiveClusterState, StandbyClusterState) pair progresses through a well-defined sequence during failover: `(AIS,S)` -> `(ATS,S)` -> `(ATS,STA)` -> `(ATS,AIS)` -> `(S,AIS)`.

**Anti-Flapping Timer.**
A countdown timer (Lamport CHARME 2005) gates the `ANIS` -> `AIS` transition: the OUT directory must remain empty and all RS must be in SYNC for a configurable number of ticks before the cluster may return to `ACTIVE_IN_SYNC`.

**ZK Connection/Session Lifecycle.**
Three per-cluster booleans model ZK health: peer connection state, peer session liveness, and local connection state. Peer-reactive transitions are guarded on peer connectivity and session liveness. Auto-completion, heartbeat, and writer ZK writes are guarded on local connectivity. Watcher-driven transitions may be permanently lost after retry exhaustion.

### Coordination via ZooKeeper

Each cluster stores its own HA group state as a ZooKeeper znode.  Peer clusters observe each other's state changes via Curator watchers connected to the remote ZK quorum.  State updates use ZK's versioned `setData` via optimistic CAS locking. A writer reads the current version, computes a new state, and writes with the read version. A `BadVersionException` triggers re-read and retry.

The final failover step is two independent ZK writes. The new active writes `ACTIVE_IN_SYNC` to its own ZK, then the old active's `FailoverManagementListener` reactively writes `STANDBY` to its own ZK. Safety during this window is maintained because the old active is in `ACTIVE_IN_SYNC_TO_STANDBY`, a role that blocks all client mutations.

### Failover Sequence

1. **Initiation.** Admin transitions the active cluster from `AIS` to `ATS` (or `ANIS` to `ANISTS`). The `ACTIVE_TO_STANDBY` role blocks all mutations.

2. **Standby detection.** The standby's `FailoverManagementListener` detects the peer's ATS via a ZK watcher and reactively transitions the standby from `S` to `STA`. This sets `failoverPending = true`.

3. **Log replay.** The standby replays all outstanding replication logs. The failover trigger requires: `failoverPending`, in-progress directory empty, and no new files in the time window (`lastRoundProcessed >= lastRoundInSync`).

4. **Activation.** The standby writes `ACTIVE_IN_SYNC` to its own ZK.

5. **Completion.** The old active's listener detects the peer's AIS and reactively writes `STANDBY` to its own ZK.

### Why Formal Verification

Exhaustive model checking with TLC explores every reachable state under all possible interleavings of these actors and failure modes, proving that the crucial safety properties of mutual exclusion, zero RPO, and abort correctness hold universally.

The exhaustive model check verifies the following over the full reachable state space:

**State invariants** (hold in every reachable state):

| Invariant | Property |
|-----------|----------|
| `TypeOK` | All 13 variables have valid types |
| `MutualExclusion` | At most one cluster in the ACTIVE role at any time |
| `AbortSafety` | AbTAIS reachable only via peer AbTS (abort from correct side) |
| `NonAtomicFailoverSafe` | ATS maps to ACTIVE_TO_STANDBY (mutations blocked) during failover window |
| `AISImpliesInSync` | AIS implies outDirEmpty and all RS in SYNC/INIT/DEAD |
| `NoAISWithSFWriter` | No STORE_AND_FWD writers when cluster is AIS |
| `WriterClusterConsistency` | Degraded writer modes only on non-AIS active states |
| `ZKSessionConsistency` | Peer session expiry implies peer disconnection |

**Action constraints** (hold on every state transition):

| Constraint | Property |
|------------|----------|
| `TransitionValid` | Every cluster state change follows `AllowedTransitions` |
| `WriterTransitionValid` | Every writer mode change follows `AllowedWriterTransitions` |
| `ReplayTransitionValid` | Every replay state change follows `AllowedReplayTransitions` |
| `AIStoATSPrecondition` | AIS->ATS requires outDirEmpty and all RS in SYNC/DEAD |
| `AntiFlapGate` | ANIS->AIS blocked while anti-flapping timer is positive |
| `FailoverTriggerCorrectness` | STA->AIS requires failoverPending, inProgressDirEmpty, replayState=SYNC |
| `NoDataLoss` | Zero RPO: STA->AIS only when replay is complete |

## Module Architecture

```
ConsistentFailover.tla          (root orchestrator: variables, Init, Next, Spec, invariants)
  |
  +-- Types.tla                 (pure definitions: states, transitions, roles, helpers)
  |
  +-- HAGroupStore.tla          (peer-reactive transitions, auto-completion, retry exhaustion)
  +-- Admin.tla                 (operator-initiated failover and abort)
  +-- Writer.tla                (per-RS replication writer mode state machine)
  +-- Reader.tla                (standby-side replication replay state machine)
  +-- HDFS.tla                  (HDFS NameNode crash/recovery)
  +-- RS.tla                    (RS crash, local HDFS abort, process supervisor restart)
  +-- Clock.tla                 (anti-flapping countdown timer)
  +-- ZK.tla                    (ZK connection/session lifecycle)
```

All sub-modules extend `Types.tla` for shared definitions. `ConsistentFailover.tla` composes them via `INSTANCE`.

## Modules

| Module | Description |
|--------|-------------|
| `Types.tla` | Pure definitions: 14 HA group states, allowed transitions, cluster roles, writer modes, replay states, anti-flapping timer helpers. No variables. |
| `ConsistentFailover.tla` | Root orchestrator. Declares 13 variables, defines Init/Next/Spec, instances sub-modules, defines all invariants and action constraints. |
| `HAGroupStore.tla` | Peer-reactive transitions (`PeerReactToATS`, `PeerReactToANIS`, `PeerReactToAbTS`, `PeerReactToAIS`), local auto-completion (`AutoComplete`), STORE_AND_FORWARD heartbeat (`ANISHeartbeat`), ANIS recovery (`ANISToAIS`), retry exhaustion (`ReactiveTransitionFail`). All peer-reactive actions guarded on `zkPeerConnected` and `zkPeerSessionAlive`. Auto-completion, heartbeat, and recovery guarded on `zkLocalConnected`. |
| `Admin.tla` | `AdminStartFailover` (AIS->ATS with peer-state guard) and `AdminAbortFailover` (STA->AbTS, clears failoverPending). |
| `Writer.tla` | Per-RS writer mode transitions: startup (`WriterInit`, `WriterInitToStoreFwd`, `WriterInitToStoreFwdFail`), degradation (`WriterToStoreFwd`, `WriterToStoreFwdFail`, `WriterSyncFwdToStoreFwd`, `WriterSyncFwdToStoreFwdFail`), recovery (`WriterSyncToSyncFwd`, `WriterStoreFwdToSyncFwd`), drain complete (`WriterSyncFwdToSync`). ZK-writing actions guarded on `zkLocalConnected`. |
| `Reader.tla` | Replay advance, degradation/recovery detection, rewind, in-progress directory dynamics, failover trigger (`TriggerFailover` guarded on `zkLocalConnected`). |
| `HDFS.tla` | `HDFSDown` and `HDFSUp` -- environment actions for NameNode crash/recovery. |
| `RS.tla` | `RSCrash` (any mode->DEAD), `RSAbortOnLocalHDFSFailure` (STORE_AND_FORWARD->DEAD when own HDFS down), `RSRestart` (DEAD->INIT via process supervisor). |
| `Clock.tla` | `Tick` -- advances all per-cluster anti-flapping countdown timers by one tick toward zero. Guarded: only fires when at least one timer is positive. |
| `ZK.tla` | Peer ZK lifecycle (`ZKPeerDisconnect`, `ZKPeerReconnect`, `ZKPeerSessionExpiry`, `ZKPeerSessionRecover`) and local ZK lifecycle (`ZKLocalDisconnect`, `ZKLocalReconnect`). |

**Total: 39 action schemas** (some parameterized over cluster and RS).

## Variables

| Variable | Type | Source |
|----------|------|--------|
| `clusterState[c]` | `[Cluster -> HAGroupState]` | HAGroupStoreRecord per-cluster ZK znode |
| `writerMode[c][rs]` | `[Cluster -> [RS -> WriterMode]]` | ReplicationLogGroup per-RS mode |
| `outDirEmpty[c]` | `[Cluster -> BOOLEAN]` | Replication OUT directory state |
| `hdfsAvailable[c]` | `[Cluster -> BOOLEAN]` | NameNode availability (detected via IOException) |
| `antiFlapTimer[c]` | `[Cluster -> 0..WaitTimeForSync]` | Countdown timer (Lamport CHARME 2005) |
| `replayState[c]` | `[Cluster -> ReplayStateSet]` | Standby replay state (NOT_INITIALIZED/SYNC/DEGRADED/SYNCED_RECOVERY) |
| `lastRoundInSync[c]` | `[Cluster -> Nat]` | Last replay round processed while in SYNC |
| `lastRoundProcessed[c]` | `[Cluster -> Nat]` | Last replay round processed (any state) |
| `failoverPending[c]` | `[Cluster -> BOOLEAN]` | STA notification received, waiting for replay completion |
| `inProgressDirEmpty[c]` | `[Cluster -> BOOLEAN]` | No partially-processed replication log files |
| `zkPeerConnected[c]` | `[Cluster -> BOOLEAN]` | peerPathChildrenCache TCP connection state |
| `zkPeerSessionAlive[c]` | `[Cluster -> BOOLEAN]` | Peer ZK session liveness |
| `zkLocalConnected[c]` | `[Cluster -> BOOLEAN]` | pathChildrenCache (local) connection; maps to `isHealthy` |

## Configuration

The model is configured via `ConsistentFailover.cfg`:

| Parameter | Value | Notes |
|-----------|-------|-------|
| `Cluster` | `{c1, c2}` | Exactly 2 clusters forming the HA pair |
| `RS` | `{rs1, rs2}` | 2 region servers per cluster |
| `WaitTimeForSync` | `2` | Anti-flapping timer ticks (small value sufficient for verification) |
| Symmetry | `Permutations(RS)` | RS identifiers are interchangeable; clusters are asymmetric (AIS vs S at Init) |
| State constraint | `lastRoundProcessed[c] <= 3` | Bounds replay counters for tractability |

The initial state is deterministic: one cluster starts in AIS, the other in S.  All writers start in INIT, all HDFS available, all ZK connections alive, anti-flapping timers at zero, replay in NOT_INITIALIZED.

## Running

Requires JDK 11+ (JDK 17 recommended).  The `tla2tools.jar` must be on the classpath.

**Syntax check (SANY):**

```
java -cp tla2tools.jar tla2sany.SANY ConsistentFailover.tla
```

**Exhaustive model check (TLC):**

```
java -XX:+UseParallelGC  -cp tla2tools.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover.cfg \
  -workers auto -cleanup
```
## Latest Results

| Metric | Value |
|--------|-------|
| Configuration | 2 clusters, 2 RS, WaitTimeForSync=2 |
| Workers | 16 |
| States generated | 1,975,197,169 |
| Distinct states | 122,644,800 |
| Depth | 79 |
| Duration | 17 min 21 sec |
| Result | Success |

All 8 state invariants and 7 action constraints verified.  No violations.
