# TLA+ Model of Phoenix Consistent Failover

## 1. Summary

This document presents a detailed analysis of the Phoenix Consistent Failover system and a step-by-step plan for modeling it in TLA+. The system provides zero-RPO failover between paired Phoenix/HBase clusters using synchronous replication, with asynchronous replay on the standby and a multi-state-machine coordination protocol managed via ZooKeeper.

The design involves two clusters (Primary and Standby) in distinct failure domains, each running multiple RegionServers with per-RS replication writers. The HA group state is managed through ZooKeeper with optimistic locking. Multiple concurrent actors (Admin, Writers, Readers, HAGroupStoreManagers) drive state transitions both explicitly and reactively, creating complex interleaving possibilities — making this an excellent candidate for formal specification in TLA+.

The plan is organized as an iterative series of increasingly detailed TLA+ modules, starting with the core cluster role state machine and building outward to encompass replication writer modes, replay state, crash recovery, anti-flapping, and liveness properties.

---

## 2. Architecture Overview

### 2.1 Actors

| Actor | Role | Implementation |
|-------|------|----------------|
| **Admin (A)** | Human operator; initiates/aborts failover via CLI | `PhoenixHAAdminTool.java` (`initiate-failover` L509, `abort-failover` L648) → `HAGroupStoreManager` |
| **HAGroupStoreManager (M1, M2)** | Per-cluster coprocessor endpoint; reacts to peer ZK state changes via `FailoverManagementListener` | `HAGroupStoreManager.java` L633-706; singleton per ZK URL via `getInstance()` L158 |
| **ReplicationLogWriter (W)** | Per-RegionServer on active cluster; captures mutations, writes to standby HDFS or local OUT dir | `ReplicationLogGroup.java` + `SyncModeImpl`/`StoreAndForwardModeImpl`/`SyncAndForwardModeImpl`; mutation capture in `IndexRegionObserver.replicateMutations()` L2626-2674 |
| **ReplicationLogReader (R)** | On standby cluster; replays replication logs round-by-round, manages consistency point | `ReplicationLogDiscoveryReplay.java` (discovery + state) + `ReplicationLogProcessor.java` (mutation replay) |
| **ReplicationLogForwarder (F)** | On active cluster; copies buffered logs from local OUT to peer IN; drives writer mode transitions | `ReplicationLogDiscoveryForwarder.java`; triggers `S&F→S&FWD` and `setHAGroupStatusToSync()` |
| **HAGroupStoreClient** | Per-RS ZK interaction layer; caches state, enforces anti-flapping, validates transitions | `HAGroupStoreClient.java`; `setHAGroupStatusIfNeeded()` L325-394, `validateTransitionAndGetWaitTime()` L1027-1046 |

### 2.2 Key Data Structures

| Structure | Location | Description |
|-----------|----------|-------------|
| `HAGroupStoreRecord` | ZooKeeper znode (per cluster, per HA group) | State, lastSyncTime, policy, URLs |
| `ClusterRoleRecord` | Client-side (aggregated) | Roles (not internal states) for both clusters |
| `writerMode` | RS in-memory | Per-RS: INIT, SYNC, STORE_AND_FORWARD, SYNC_AND_FORWARD |
| `replayState` | Standby in-memory | Per-group: NOT_INITIALIZED, SYNC, DEGRADED, SYNCED_RECOVERY |
| `lastRoundInSync` | Standby in-memory | Frozen during degraded periods |
| `lastRoundProcessed` | Standby in-memory | Advances continuously during replay |
| `failoverPending` | Standby in-memory | Set by `STANDBY_TO_ACTIVE` listener |
| `zkMtime` | ZK znode metadata | Last modification time; used for anti-flapping gate |

### 2.3 Communication Channels

| Channel | Direction | Mechanism |
|---------|-----------|-----------|
| State updates | Cluster → own ZK | `setData().withVersion()` (optimistic locking) |
| Peer detection | Peer ZK → local cluster | Curator `PathChildrenCache` watchers |
| Replication data | Active Writer → Standby HDFS `/IN` | Direct HDFS write (SYNC mode) |
| Buffered data | Active Writer → Local HDFS `/OUT` | Local HDFS write (S&F mode) |
| Forwarded data | Local `/OUT` → Peer `/IN` | `ReplicationLogDiscoveryForwarder` background copy; triggers `S&F→S&FWD` on throughput check and `ANIS→AIS`/`ANISTS→ATS` on drain complete |
| Replay | Standby Reader ← Standby HDFS `/IN` | Round-based log consumption |
| Admin commands | Operator → `PhoenixHAAdminTool` | CLI RPC to `HAGroupStoreManager` |

---

## 3. State Machines in the Design and Implementation

Six interrelated state machines govern the protocol. The TLA+ specification models all six.

### 3.1 HA Group State (HAGroupState enum — SM2 + SM3 combined)

14 states from `HAGroupStoreRecord.java`:

```
ACTIVE_IN_SYNC (AIS)                    — All RS replicating synchronously
ACTIVE_NOT_IN_SYNC (ANIS)               — ≥1 RS in store-and-forward
ACTIVE_IN_SYNC_TO_STANDBY (ATS)         — Failover initiated from AIS (OUT empty)
ACTIVE_NOT_IN_SYNC_TO_STANDBY (ANISTS)  — Failover initiated from ANIS (OUT not empty)
ABORT_TO_ACTIVE_IN_SYNC (AbTAIS)        — Abort reverting to AIS
ABORT_TO_ACTIVE_NOT_IN_SYNC (AbTANIS)   — Abort reverting to ANIS
ACTIVE_WITH_OFFLINE_PEER (AWOP)         — AIS but peer is OFFLINE
ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER (ANISWOP) — ANIS but peer is OFFLINE
STANDBY (S)                             — Normal standby; receiving/replaying logs
STANDBY_TO_ACTIVE (STA)                 — Replaying outstanding logs before becoming active
DEGRADED_STANDBY (DS)                   — Standby with degraded replication
ABORT_TO_STANDBY (AbTS)                 — Reverting from STA during abort
OFFLINE                                 — Operator-controlled offline (sink state in impl)
UNKNOWN                                 — Error/uninitialized
```

**State-to-Role mapping** (from `HAGroupState.getClusterRole()` L73-97; clients see roles via `ClusterRoleRecord.ClusterRole` enum in `ClusterRoleRecord.java` L59-107):

```
ACTIVE role:               AIS, ANIS, AbTAIS, AbTANIS, AWOP, ANISWOP
ACTIVE_TO_STANDBY role:    ATS, ANISTS
STANDBY role:              S, DS, AbTS
STANDBY_TO_ACTIVE role:    STA
OFFLINE:                   OFFLINE
UNKNOWN:                   UNKNOWN (default)
```

Note: `ACTIVE_TO_STANDBY` role has `isMutationBlocked() = true` (`ClusterRoleRecord.java` L84), which is how safety is maintained during the non-atomic failover window — the old active rejects mutations while in ATS.

Implemented transition table (from `HAGroupStoreRecord.java` L99-123, verified against `PHOENIX-7562-feature-new` branch HEAD `5a9e2d50c9`):

```
ANIS    → {ANIS, AIS, ANISTS, ANISWOP}
AIS     → {ANIS, AWOP, ATS}
S       → {STA, DS}
ANISTS  → {AbTANIS, ATS}
ATS     → {AbTAIS, S}
STA     → {AbTS, AIS}
DS      → {S, STA}
AWOP    → {ANIS}
AbTAIS  → {AIS}
AbTANIS → {ANIS}
AbTS    → {S}
ANISWOP → {ANIS}
OFFLINE → {} (sink state — no outbound transitions)
UNKNOWN → {} (sink state)
```

### 3.2 Replication Writer State Machine (SM4, per-RegionServer)

4 modes from `ReplicationLogGroup.java`:

```
INIT               — Pre-initialization (impl-only)
SYNC               — Writing directly to standby HDFS
STORE_AND_FORWARD  — Writing locally; standby HDFS unavailable
SYNC_AND_FORWARD   — Draining local queue while also writing synchronously
```

**Transitions:**

```
INIT     → SYNC             [Normal startup]
INIT     → STORE_AND_FORWARD [Startup with peer unavailable]
SYNC     → STORE_AND_FORWARD [Standby HDFS unavailable]
SYNC     → SYNC_AND_FORWARD  [Forwarder started while in sync (impl-specific)]
S&F      → SYNC_AND_FORWARD  [Recovery detected; standby available again]
SYNC&FWD → SYNC              [All stored logs forwarded; queue empty]
SYNC&FWD → STORE_AND_FORWARD [Degraded again during drain]
```

**Fail-stop**: Write error in STORE_AND_FORWARD → RS aborts. No further fallback. Source: `StoreAndForwardModeImpl.onFailure()` L116-123 calls `logGroup.abort()`.

**Forwarder-driven transitions**: The `ReplicationLogDiscoveryForwarder` (in `phoenix-core-server`) drives several writer mode transitions. As it copies files from the OUT directory to IN, it monitors throughput; once throughput exceeds a threshold it transitions the writer from S&F to SYNC_AND_FORWARD (L133-152 `processFile()`). When the OUT directory is fully drained with no more rounds to forward, the same S&F → SYNC_AND_FORWARD transition fires (L167), after which `setHAGroupStatusToSync()` is called, potentially triggering the cluster-level transitions ANIS → AIS or ANISTS → ATS (L171). The forwarder also subscribes to cluster-level events: on an `ACTIVE_NOT_IN_SYNC` event (L98-108), region servers that are currently in SYNC learn that a peer has entered S&F and transition themselves to SYNC_AND_FORWARD; conversely, on an `ACTIVE_IN_SYNC` event (L113-123), region servers in SYNC_AND_FORWARD transition back to SYNC once the cluster has returned to AIS. Together, these forwarder-driven transitions are the mechanism by which OUT directory draining triggers cluster-level state changes.

### 3.3 Replication Replay State Machine (SM6, implementation-only)

4 states from `ReplicationLogDiscoveryReplay.java` (L550-555):

```
NOT_INITIALIZED  — Pre-init
SYNC             — Fully in sync; lastRoundProcessed and lastRoundInSync advance together
DEGRADED         — Active peer in ANIS; lastRoundProcessed advances, lastRoundInSync frozen
SYNCED_RECOVERY  — Active returned to AIS; replay rewinds to lastRoundInSync
```

**Transitions** (driven by `HAGroupStateListener` subscriptions in `init()` L131-206):

```
NOT_INITIALIZED → SYNCED_RECOVERY [Local initially STANDBY; recoveryListener fires (L147-157)]
NOT_INITIALIZED → DEGRADED        [Local initially DEGRADED_STANDBY; degradedListener fires (L136-145)]
SYNC            → DEGRADED        [Local state changes to DEGRADED_STANDBY (L136-145)]
DEGRADED        → SYNCED_RECOVERY [Local state changes to STANDBY (L147-157)]
SYNCED_RECOVERY → SYNC            [replay() rewinds lastRoundProcessed, CAS to SYNC (L323-333)]
SYNCED_RECOVERY → DEGRADED        [Local re-degrades before replay CAS; set() overwrites (L141)]
```

Note: `NOT_INITIALIZED → SYNC` does not occur directly. On first init the `recoveryListener` fires `set(SYNCED_RECOVERY)`, and `replay()` immediately CAS-transitions to `SYNC` (the rewind is a no-op when there is nothing to replay). The two-step path `NOT_INITIALIZED → SYNCED_RECOVERY → SYNC` is the actual code sequence. For TLA+ modeling, the `NOT_INITIALIZED` state can be collapsed into the `Init` predicate, starting the model in `SYNC` or `DEGRADED` depending on whether the peer is `AIS` or `ANIS` at startup.

**Transition triggers**: The replay state transitions are driven by *local* HA group state changes, not direct peer detection. Both the `degradedListener` and `recoveryListener` use unconditional `.set()` — not `.compareAndSet()` — so they can overwrite any prior replay state:
- `DEGRADED_STANDBY` → `replicationReplayState.set(DEGRADED)` (listener L136-145)
- `STANDBY` → `replicationReplayState.set(SYNCED_RECOVERY)` (listener L147-157)
- `STANDBY_TO_ACTIVE` → `failoverPending.set(true)` (listener L159-171)
- `ABORT_TO_STANDBY` → `failoverPending.set(false)` (listener L173-185)

The `SYNCED_RECOVERY → DEGRADED` interleaving matters for the TLA+ model: if the cluster oscillates `S → DS → S → DS` faster than `replay()` can process the `SYNCED_RECOVERY` CAS, the CAS fails and the state remains `DEGRADED`. The `compareAndSet(SYNCED_RECOVERY, SYNC)` at L332-333 is the linearization point that makes this safe — it only succeeds if no re-degradation occurred since the recovery event.

**Replay behavior by state** (from `replay()` L292-380):
- `SYNC`: advances both `lastRoundProcessed` and `lastRoundInSync` (L336-343)
- `DEGRADED`: advances only `lastRoundProcessed`; `lastRoundInSync` frozen (L345-351)
- `SYNCED_RECOVERY`: rewinds `lastRoundProcessed` to `lastRoundInSync`, then CAS-transitions to `SYNC` (L323-333). Uses `getFirstRoundToProcess()` (which starts from `lastRoundInSync` L389) to rewind.

### 3.4 Combined Product State Machine (SM5)

Notation: `(ActiveClusterState, StandbyClusterState)`

**Normal degradation/recovery:**

```
(AIS, S)     --[W, i]-->      (ANIS, S)
(ANIS, S)    --[M2, k]-->     (ANIS, DS)         [impl: single DEGRADED_STANDBY]
(ANIS, S)    --[W, j]-->      (AIS, S)           [requires ZK_Session_Timeout]
(AIS, DS)    --[M2, l]-->     (AIS, S)           [impl: single DEGRADED_STANDBY]
```

**Failover sequence (the critical path):**

```
(AIS, S)     --[A, ac1]-->    (ATS, S)           [start failover]
(ANIS, S)    --[A, ac1]-->    (ANISTS, S)        [start failover from ANIS]
(ANISTS, S)  --[W, j]-->      (ATS, S)           [OUT dir now empty; ⚠ subject to anti-flapping gate]
(ATS, S)     --[M2, b]-->     (ATS, STA)         [standby detects failover]
(ATS, STA)   --[R, c]-->      (ATS, AIS)         [replay complete + IN dir empty]
(ATS, AIS)   --[M1, d]-->     (S, AIS)           [⚠ IMPL: TWO separate ZK writes, NOT atomic]
```

**ANIS failover with DS standby:**

```
(ANIS, DS)   --[A, ac1]-->    (ANISTS, DS)       [admin initiates failover]
(ANISTS, DS) --[W, j]-->      (ATS, DS)          [OUT dir empty; subject to anti-flapping gate]
(ATS, DS)    --[M2, b]-->     (ATS, STA)         [DS → STA now in allowedTransitions]
(ATS, STA)   --[R, c]-->      (ATS, AIS)         [replay complete + IN dir empty]
(ATS, AIS)   --[M1, d]-->     (S, AIS)           [two separate ZK writes]
```

The standby may be in `DEGRADED_STANDBY` when failover is initiated from `ANIS` because the standby reacts to peer `ANIS` by entering `DS`. The `DEGRADED_STANDBY → STANDBY_TO_ACTIVE` transition was added to the `allowedTransitions` table to ensure this path completes successfully.

**Abort sequence:**

```
(ATS, STA)   --[A, ac2]-->    (ATS, AbTS)        [abort from STA side]
(ATS, AbTS)  --[M1, g]-->     (AbTAIS, AbTS)     [active detects abort]
(AbTAIS, AbTS)                                    [auto-completion: AbTAIS→AIS, AbTS→S]
```

### 3.5 Peer-Reactive Transitions (FailoverManagementListener)

From `HAGroupStoreManager.java` lines 104-150 (`createPeerStateTransitions()` and `createLocalStateTransitions()`):

| Peer Transitions To | Local Current | Local Moves To | Source |
|---------------------|---------------|----------------|--------|
| `ATS` | `S` or `DS` | `STA` | L109 |
| `AIS` | `ATS` | `S` | L113 |
| `AIS` | `DS` | `S` | L116 |
| `ANIS` | `ATS` | `S` | L123 |
| `ANIS` | `S` | `DS` | L126 |
| `AbTS` | `ATS` | `AbTAIS` | L132 |

**Guard on peer ATS → local STA**: The resolver at line 109 is *unconditional* (`currentLocal -> STANDBY_TO_ACTIVE`). Both `STANDBY → STANDBY_TO_ACTIVE` and `DEGRADED_STANDBY → STANDBY_TO_ACTIVE` are in the `allowedTransitions` table, so the standby can enter `STA` regardless of whether it is in `S` or `DS` when it detects peer `ATS`. This ensures the ANIS failover path completes even when the standby is in `DEGRADED_STANDBY` (the expected state when the peer is `ANIS`).

**No peer reaction for ANISTS**: The peer transitions map has no entry for `ACTIVE_NOT_IN_SYNC_TO_STANDBY`. When the active transitions `ANIS → ANISTS`, the standby does not react and remains in its current state (e.g., `DEGRADED_STANDBY`). The standby only reacts when the active subsequently transitions `ANISTS → ATS` (= `ACTIVE_IN_SYNC_TO_STANDBY`).

**Reactive transition retry exhaustion**: The `FailoverManagementListener` (`HAGroupStoreManager.java` L653-704) retries each reactive transition exactly 2 times. After exhaustion, the transition is permanently lost — the method returns silently with only a log error. Events are not requeued (`notifySubscribers()` at `HAGroupStoreClient.java` L1141-1150 catches and swallows exceptions). Same-state ZK re-writes do not re-trigger because `handleStateChange()` (L1104-1110) suppresses notifications when `oldState.equals(newState)` and `lastKnownPeerState` is already advanced. There is no periodic reconciliation — the sync job (`syncZKToSystemTable()` L735-784) only syncs ZK to system table, not failover state. Recovery requires a different peer state change, a ZK session reconnect (which may cause `PathChildrenCache` to re-deliver via `CHILD_ADDED`), or manual intervention. The `isStateAlreadyUpdated()` check (L739-753) provides a safety net for concurrent success. The TLA+ model includes `UseRetryExhaustionQuirk` to toggle this behavior.

**Auto-completion transitions** (local, no peer trigger):

From `createLocalStateTransitions()` (lines 140-150):

| Local State | Auto-Completes To | Source |
|-------------|-------------------|--------|
| `AbTS` | `S` | L144 |
| `AbTAIS` | `AIS` | L145 |
| `AbTANIS` | `ANIS` | L147 |

### 3.6 Anti-Flapping Protocol

Prevents rapid oscillation between ANIS and AIS:

1. RS in S&F mode periodically re-writes `ANIS` to ZK, refreshing `mtime` (heartbeat interval: `0.7 × ZK_SESSION_TIMEOUT`, ~63s with 90s timeout) Source: `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()` L71-87
2. `ANIS → AIS` requires: `(mtime + 1.1 × ZK_SESSION_TIMEOUT) ≤ current_time` Source: `HAGroupStoreClient.validateTransitionAndGetWaitTime()` L1027-1046
3. While heartbeat keeps refreshing `mtime`, the gate is never satisfied
4. Only after heartbeat stops (mode exits S&F) does the countdown begin
5. RS aborts if ZK client receives DISCONNECTED event

**Gate also applies to ANISTS → ATS**: The `validateTransitionAndGetWaitTime()` method (L1032-1036) applies the wait time not only to `ANIS → AIS` but also to `ANISTS → ATS` (= `ACTIVE_NOT_IN_SYNC_TO_STANDBY → ACTIVE_IN_SYNC_TO_STANDBY`). This is intentional: both transitions require the wait to ensure all region servers have consistent state and to prevent flapping due to ZK state propagation delay. The guard is:
```
(currentState == ACTIVE_NOT_IN_SYNC && newState == ACTIVE_IN_SYNC)
  || (currentState == ACTIVE_NOT_IN_SYNC_TO_STANDBY
      && newState == ACTIVE_IN_SYNC_TO_STANDBY)
```
This delay must be modeled in the TLA+ spec for the ANISTS failover path.

**Failover time measurement**: The failover time (downtime from the client's perspective) is measured from when I/O stops — i.e., when the primary cluster goes down — not from when the admin issues the failover command. The anti-flapping wait on the `ANISTS → ATS` path does not add to client-visible downtime because I/O is already blocked by the time the admin initiates failover.

---

## 4. Key Invariants and Properties to Verify

### 4.1 Safety Properties

1. **Mutual Exclusion (No Dual-Active)**: Two clusters never both in Active role simultaneously.
   - `~(clusterState[C1] ∈ ActiveStates ∧ clusterState[C2] ∈ ActiveStates)`
   - where `ActiveStates == {AIS, ANIS, AbTAIS, AbTANIS, AWOP, ANISWOP}`

2. **No Data Loss (Zero RPO)**: The standby must replay all replication logs before becoming Active.
   - `(clusterState[c] = STA ∧ clusterState'[c] = AIS) ⇒ replayComplete[c]`
   - ⚠ IMPL: `replayComplete` requires `failoverPending ∧ inProgressDirEmpty ∧
     lastRoundProcessed ≥ lastRoundInSync`

3. **AIS-to-ATS Precondition**: Failover can only begin from AIS when OUT dir is empty and all RS are in SYNC mode.
   - `(clusterState[c] = AIS ∧ clusterState'[c] = ATS) ⇒
      (outDirEmpty[c] ∧ ∀ rs ∈ RS: writerMode[c][rs] = SYNC)`
   - This precondition is implicit in `initiateFailoverOnActiveCluster()` (L375-400). The method only validates that the current state is AIS or ANIS. The precondition holds because AIS implies all RS are in SYNC (enforced by the `ANIS → AIS` transition requiring `outDirEmpty ∧ anti-flapping timeout`). The TLA+ model should encode this as a derived invariant, not as an explicit guard on the admin action.

4. **State Transition Validity**: Every state change follows the `allowedTransitions` table.
   - `∀ c: clusterState'[c] ≠ clusterState[c] ⇒
      ⟨clusterState[c], clusterState'[c]⟩ ∈ AllowedTransitions`

5. **Non-Atomic Failover Safety**: During the window between the new active writing `ACTIVE_IN_SYNC` and the old active writing `STANDBY`, mutual exclusion is maintained because `ATS` maps to role `ACTIVE_TO_STANDBY` (not an Active role).
   - `(clusterState[c1] = ATS ∧ clusterState[c2] = AIS) ⇒
      RoleOf(ATS) ∉ ActiveRoles`

6. **Abort Safety**: Abort must be initiated from the STA side (standby cluster) to prevent dual-active races.
   - Action constraint: abort action only fires when local state is STA or
     peer state is ATS/ANISTS.

7. **Writer-Cluster Consistency**: Writer mode and cluster state must be consistent.
   - `(∃ rs: writerMode[c][rs] ∈ {S&F, SYNC&FWD}) ⇒
      clusterState[c] ∈ {ANIS, ANISTS, ANISWOP, AbTANIS}`
   - `(∀ rs: writerMode[c][rs] = SYNC ∧ outDirEmpty[c]) ⇒
      clusterState[c] ∉ {ANIS, ANISTS}` (eventually; modulo anti-flapping delay)

8. **Replay State Consistency**: Replay state and peer cluster state must be consistent.
   - `(replayState[c] = SYNC ∧ c is standby) ⇒ peerState ∈ {AIS, ATS, ...}`

9. **Failover Trigger Correctness** (⚠ IMPL): `STA → AIS` requires three conditions.
   - `(clusterState[c] = STA ∧ clusterState'[c] = AIS) ⇒
      (failoverPending[c] ∧ inProgressDirEmpty[c] ∧
       lastRoundProcessed[c] ≥ lastRoundInSync[c])`

10. **OFFLINE Sink State**: Once a cluster enters OFFLINE, it cannot transition out via the normal state machine.
    - `clusterState[c] = OFFLINE ⇒ clusterState'[c] = OFFLINE`
    (unless `UseForceQuirk = TRUE` to model manual ZK manipulation)

### 4.2 Liveness Properties

1. **Failover Completion**: If failover is initiated and not aborted, it eventually completes.
   - `□(failoverInitiated ∧ ¬aborted ⇒ ◇ failoverComplete)` (under fairness)
   - Requires: HDFS available on standby (for replay and trigger checks), forwarder drains successfully (for ANISTS→ATS path), and reactive transitions succeed (peer detects state changes). If any of these fail, liveness requires admin abort. The quirk flags `UseForwarderStuckQuirk` and `UseRetryExhaustionQuirk` model these failure modes explicitly.

2. **Degradation Recovery**: If HDFS connectivity recovers permanently, the cluster eventually returns to AIS.
   - `□(clusterState[c] = ANIS ∧ ◇□ hdfsAvailable[peer] ⇒ ◇ clusterState[c] = AIS)`

3. **Abort Completion**: If abort is initiated, the system eventually returns to `(A*, S)`.
   - `□(abortInitiated ⇒ ◇ (clusterState[active] ∈ {AIS, ANIS} ∧ clusterState[standby] = S))`
   - Requires reactive transitions to succeed (peer detects abort). If `UseRetryExhaustionQuirk = TRUE`, abort completion may require admin intervention on the peer side.

4. **Anti-Flapping Bound**: ANIS/AIS oscillation is bounded (modeled via the timing gate).

### 4.3 Properties Specific to Interesting Scenarios

1. **Non-atomic failover window**: New active writes AIS while old active is still ATS. Verify mutual exclusion during this window.
2. **ANIS failover with stuck forwarder**: Failover initiated from ANIS; OUT dir must drain before standby can begin replay. The forwarder has no timeout — `FileUtil.copy()` is blocking, retries are indefinite (every 10s), and the admin tool's 120s timeout is advisory only (does not abort). If the forwarder is stuck, ANISTS persists indefinitely. Recovery requires manual `abort-failover` or `--force`. The model verifies that `FailoverCompletion` requires either drain completion or admin abort. Source: `ReplicationLogDiscoveryForwarder.java` L133-184; `PhoenixHAAdminTool.java` L509-605.
3. **RS crash during S&F**: Writer in S&F mode encounters write error, RS aborts (`StoreAndForwardModeImpl.onFailure()` → `logGroup.abort()`). OUT directory shards are time-based (not per-RS), so surviving RS forwarders can drain a crashed RS's fully-written files from the shared HDFS shard directories. However, mid-write files have unclosed HDFS leases and the forwarder has no lease recovery in its read path (`RecoverLeaseFSUtils` is only used on the IN side). These files are orphaned until HDFS lease expiry (~10 min). RS restart resumes draining via `initializeLastRoundProcessed()`. Source: `ReplicationShardDirectoryManager.java` L116-136; `StoreAndForwardModeImpl.java` L116-123.
4. **Concurrent RS mode changes**: Multiple RS race to update ZK state (optimistic locking). Verify only valid transitions succeed.
5. **Replay rewind after degradation**: SYNCED_RECOVERY rewinds `lastRoundProcessed` to `lastRoundInSync`. Verify no data loss.
6. **ZK watcher delay and retry exhaustion**: Peer state change not immediately detected. Verify safety during detection lag. Additionally, `FailoverManagementListener` retries exactly 2 times (`HAGroupStoreManager.java` L659). After exhaustion, the transition is permanently lost — no event requeue, no periodic reconciliation, and same-state ZK re-writes are suppressed by `handleStateChange()` (L1104-1110) because `lastKnownPeerState` is already advanced. Recovery requires a different peer state change, ZK session reconnect, or manual intervention. The model includes a  `ReactiveTransitionFail` action to verify liveness under retry exhaustion.
7. **ANIS failover with standby in DEGRADED_STANDBY**: When failover is initiated from ANIS, the standby is typically in `DEGRADED_STANDBY` (because it reacted to peer `ANIS` by entering `DS`). The sequence is: `(ANIS, DS) → (ANISTS, DS) → (ATS, DS) → (ATS, STA) → (ATS, AIS) → (S, AIS)`. The `DEGRADED_STANDBY → STANDBY_TO_ACTIVE` transition has been added to the `allowedTransitions` table, so the standby can enter `STA` from `DS` when it detects peer `ATS`. The TLA+ model should verify that this path completes successfully end-to-end, including replay completeness before `STA → AIS`.
8. **HDFS failure during failover `(ATS, STA)`**: Mutations are blocked during ATS (`isMutationBlocked()=true`, `ClusterRoleRecord.java` L84), so no new data enters the pipeline. HDFS failure during STA stalls replay (retries every 60s, `ReplicationLogDiscoveryReplay` L309-317) and blocks `shouldTriggerFailover()` (L500-533) because its HDFS reads throw `IOException`. The standby stays in STA indefinitely — no automatic abort path exists. Safety is preserved (no dual-active, no data loss) but liveness requires HDFS recovery or manual abort.

---

## 5. TLA+ Model Design

### 5.1 Module Structure

**Pattern:**

- **`Types.tla`** — `EXTENDS Naturals, FiniteSets, TLC`. Declares all `CONSTANTS`, `ASSUME` checks, state/type set definitions, valid transition tables, role mappings, and helper operators. No variables.
- **Sub-modules** (e.g., `HAGroupStore.tla`, `Admin.tla`) — Each does `EXTENDS Types` and declares all shared variables as `VARIABLE` (same names as the root module). Defines actions grouped by actor or concern.
- **Root module** (`ConsistentFailover.tla`) — `EXTENDS Types`, declares all variables, uses `INSTANCE` (no `WITH` clause — TLA+ matches identifiers by name) to import sub-modules as namespaced prefixes (e.g., `haGroupStore == INSTANCE HAGroupStore`). Defines `Init`, `Next` (composing `haGroupStore!Action(...)`, `admin!Action(...)`, etc.), `Fairness`, `Spec`, all invariants, and `Symmetry`.
- **`.cfg` files** reference `Spec`, `SYMMETRY Symmetry`, `INVARIANT`, `ACTION_CONSTRAINT`, etc. from the root module.

**Planned modules:**

```
Types.tla                       (constants, state sets, transition table, role mapping, helpers)
HAGroupStore.tla                (cluster state transitions: peer-reactive, auto-complete, ZK locking)
Admin.tla                       (operator-initiated actions: start/abort failover)
Writer.tla                      (replication writer mode state machine, per-RS)
Reader.tla                      (replication reader/replay state machine)
Environment.tla                 (external non-determinism: HDFS availability, clock tick)
ConsistentFailover.tla          (root orchestrator: variables, Init, Next, Fairness, invariants, Symmetry)
ConsistentFailover.cfg          (primary TLC config — exhaustive BFS, symmetry reduction, every iteration)
ConsistentFailover-sim.cfg      (simulation TLC config — no symmetry, deep random traces)
ConsistentFailover-liveness.cfg (liveness TLC config — no symmetry, ad hoc)
```

**Module introduction schedule:**

| Module | First Appears | Content at Introduction |
|--------|---------------|------------------------|
| `Types.tla` | Iteration 1 | `HAGroupState`, `ActiveStates`, `StandbyStates`, `AllowedTransitions` |
| `ConsistentFailover.tla` | Iteration 1 | Variables, `Init`, `Next` (direct actions), invariants, `Symmetry` |
| `HAGroupStore.tla` | Iteration 3 | `PeerReact`, `AutoComplete` actions |
| `Admin.tla` | Iteration 3 | `AdminStartFailover`, `AdminAbortFailover` |
| `Writer.tla` | Iteration 5 | Writer mode actions (`WriterInit`, `WriterToSF`, etc.) |
| `Environment.tla` | Iteration 6 | `HDFSFail`, `HDFSRecover` |
| `Reader.tla` | Iteration 10 | Replay state machine actions |

### 5.2 Model Verification

TLC runs are executed on `buildbox.aws` (128 cores). In the early phases, exhaustive model checking is used exclusively — it provides complete coverage and the state spaces are small enough for timely completion. Simulation mode will be introduced later once state spaces grow too large for exhaustive search within a reasonable time budget.

| Config | Mode | Symmetry | Model | Where | Role | Time |
|--------|------|----------|-------|-------|------|------|
| `ConsistentFailover.cfg` | Exhaustive BFS | Yes | 2c/2rs | `buildbox.aws` | Every iteration | target ≤1 hr |
| `ConsistentFailover-liveness.cfg` | Exhaustive BFS | No | 2c/2rs | `buildbox.aws` | Ad hoc | 1 hr |
| `ConsistentFailover-sim.cfg` | Simulation | No | 2c/3rs | `buildbox.aws` | When needed | varies |

where `c` = clusters, `rs` = region servers per cluster.

**Symmetry reduction** is the key distinction between the three configs. The primary exhaustive config (`ConsistentFailover.cfg`) uses `SYMMETRY` to exploit the interchangeability of region servers within a cluster, dramatically reducing the state space and keeping exhaustive runs tractable. However, symmetry reduction is incompatible with liveness checking (TLC limitation), so `ConsistentFailover-liveness.cfg` omits it. Simulation mode (`ConsistentFailover-sim.cfg`) also omits symmetry because it samples random traces rather than exhaustively enumerating states, so symmetry reduction provides no benefit.

The simulation config is reserved for later phases when exhaustive search becomes intractable. It will not be used in the early phases unless exhaustive runs exceed the 1-hour time budget.

SANY syntax checking is performed locally in the Cursor environment before staging files to the remote server (see §6 for the full procedure).

### 5.3 Abstraction Decisions

| Aspect | Modeling Decision | Rationale |
|--------|-------------------|-----------|
| Cluster HA state machine | **Concrete** | Core of the model; exact states and transitions from `HAGroupStoreRecord.java` |
| Combined product state machine | **Concrete** | The heart of the failover protocol; all (C1, C2) state pairs |
| Peer-reactive transitions | **Concrete** | `FailoverManagementListener` auto-transitions are critical for safety |
| Auto-completion transitions | **Concrete** | AbTS→S, AbTAIS→AIS, AbTANIS→ANIS are part of the protocol |
| Writer mode state machine | **Concrete** | SYNC/S&F/SYNC&FWD mode changes drive cluster state transitions |
| Replay state machine (SM6) | **Concrete** | Implementation-only but critical for NoDataLoss verification |
| Anti-flapping protocol | **Concrete** (abstract timing) | Modeled via logical clock + threshold guard; no real-time |
| ZK optimistic locking | **Abstract** | Modeled as non-deterministic choice among concurrent updaters; version numbers omitted initially |
| ZK watcher delay | **Abstract** | Modeled as non-deterministic delay (peer state change visible after 0-N steps); reactive transitions can permanently fail after 2 retries (see `UseRetryExhaustionQuirk`) |
| HDFS availability | **Abstract** | Non-deterministic boolean per cluster |
| OUT/IN directory state | **Abstract** | Boolean predicates (empty/non-empty); no file-level modeling. Forwarder drain has no timeout (see `UseForwarderStuckQuirk`). Shards are time-based/shared across RS. |
| Replication log format | **Omitted** | Not relevant to protocol safety |
| Replication log content | **Omitted** | Mutations are abstract; only round/sync metadata matters |
| LMAX Disruptor | **Omitted** | Internal writer buffering; not relevant to protocol safety |
| HDFS lease recovery | **Abstract** | Forwarder (OUT side) has no lease recovery — only the reader (IN side) uses `RecoverLeaseFSUtils`. Mid-write files from crashed RS are orphaned until HDFS lease expiry (~10 min). Modeled as transient delay on `outDirEmpty` after RS crash. |
| Client-side connections | **Omitted** | Focus on server-side protocol; client role detection is a consequence |
| Metrics | **Omitted** | Observability, not correctness |
| Reader lag (DSFR) | **Deferred** (Iteration 8+) | Design sub-state collapsed in implementation; add if needed |
| Forced failover | **Deferred** (Iteration 10+) | Operator escape hatch; model after normal path verified |
| OFFLINE state | **Deferred** (Iteration 18) | Intentional sink state; `--force` bypass modeled as separate action |
| RS crash/abort | **Concrete** (Phase 3) | Writer fail-stop in S&F is a protocol-relevant failure mode |
| Number of RS per cluster | **Parameterized** | 2 for exhaustive (with RS symmetry reduction), 3 for simulation (no symmetry); races between RS matter |
| Degraded standby sub-states | **Implementation** (single DS) | Follow implementation's collapsed `DEGRADED_STANDBY`; design's DSFW/DSFR/DS intentionally removed for simplicity |
| Non-atomic failover | **Concrete** | Critical modeling point: two separate ZK writes, not one atomic step |
| ANIS self-transition | **Concrete** | Heartbeat that refreshes `zkMtime`; essential for anti-flapping |
| Failover time measurement | **Assumption** | Failover time = client I/O loss to standby active, not admin command to completion. Anti-flapping waits on ANISTS→ATS do not add to client downtime. |

### 5.4 TLA+ Style Guide

All TLA+ modules in this project follow the style described below, prioritizing traceability between the formal specification and the Java implementation.

#### Module-Level Documentation

Every `.tla` file begins with a block comment (`(* ... *)`) that:

1. States the module's purpose in one sentence.
2. Lists the actions defined in the module.
3. Provides an implementation traceability table mapping modeled concepts to their Java/protobuf counterparts (enum values, class names, method names with line numbers).

#### Action Documentation

Every action operator has a docstring comment block immediately above its definition containing:

1. **One-line summary** of what the action does.
2. **Pre:** — preconditions (guards) in natural language.
3. **Post:** — postconditions (effects) in natural language.
4. **Source:** — implementation traceability: Java class name, method name, and line numbers where the modeled behavior originates.

#### Line-Level Comments

Every line (or short group of lines) of TLA+ has a natural-language comment directly above it explaining what the line does and why. The comment uses `\*` prefix and is aligned with the TLA+ code below it.

#### Implementation Cross-References

When a guard, state update, or design choice corresponds to a specific implementation behavior, the comment includes a `Source:` annotation with the Java class, method, and line numbers.

#### Variable Grouping

Sub-modules declare shorthand tuples for variable groups used in `UNCHANGED` clauses, with a comment explaining the group.

#### Summary of Style Rules

1. Every module has a header docstring with purpose, action list, and implementation traceability table.
2. Every action has a docstring with summary, Pre/Post conditions, and Source traceability.
3. Every line of TLA+ has a natural-language comment directly above it.
4. Implementation cross-references use `Source:` annotations with class names, method names, and line numbers.
5. Quirk flags and design divergences are explained inline at the point where the behavior differs.
6. Variable groups are defined as shorthand tuples with comments.
7. `UNCHANGED` clauses use the shorthand tuples and have a summary comment listing what categories of state are unchanged.

---

## 6. Getting Started

### Prerequisites

- Cursor/VS Code with the TLA+ extension (`tlaplus.vscode-ide`)
- Java 11+ (the TLA+ tools jar requires class file version 55.0)
- Familiarity with TLA+ syntax (no PlusCal translation in this project)

### Local Java Configuration

The default `java` on the local macOS host is Java 8, which is too old for `tla2tools.jar`. Java 17 is installed at the standard macOS location:

```
/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
```

All local `java` commands in this document (SANY syntax checks, etc.) must use this path explicitly. A shell alias is convenient:

```bash
JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
```

The remote server (`buildbox.aws`) has Java 17 as its default `java`, so no path override is needed there.

### Tool Locations

TLA+ tool jars are available in the workspace root:

```
/Users/apurtell/src/phoenix/tla2tools.jar
/Users/apurtell/src/phoenix/CommunityModules-deps.jar
/Users/apurtell/src/phoenix/tlaplus-formatter.jar
```

Canonical copies also exist in the Cursor extension directory:

```
~/.cursor/extensions/tlaplus.vscode-ide-2026.4.61936-universal/tools/tla2tools.jar
~/.cursor/extensions/tlaplus.vscode-ide-2026.4.61936-universal/tools/CommunityModules-deps.jar
```

### Execution Environments

Two execution environments are available:

| Environment | Machine | Cores | Use For |
|-------------|---------|-------|---------|
| **Local** | Cursor host (macOS) | varies | SANY syntax checking, small exhaustive runs (Phase 1) |
| **Remote** | `buildbox.aws` | 128 | All exhaustive and simulation TLC runs |

The `apurtell` principal has passwordless SSH access to `buildbox.aws`. All TLC model checking beyond trivial syntax checks should be executed on the remote server to take advantage of 128-core parallelism.

### Local: SANY Syntax Check Only

Use the local environment for fast syntax checking during iterative development (Step 3 of the per-iteration workflow). SANY parses the spec and reports errors without running the model checker.

```bash
cd /Users/apurtell/src/phoenix
JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
$JAVA17 -cp tla2tools.jar tla2sany.SANY ConsistentFailover.tla
```

This completes in under a second and catches all parse errors before committing to a remote TLC run.

### Remote: TLC Execution on buildbox.aws

All TLC runs (exhaustive, simulation, liveness) are executed on the 128-core remote server. The workflow is: stage files → run TLC → retrieve results.

#### Step 1: Stage spec and tools to remote server

Create a per-iteration working directory on the remote server and copy everything needed:

```bash
ITER="iter01"
REMOTE_DIR="/tmp/tla-phoenix/$ITER"

ssh buildbox.aws "mkdir -p $REMOTE_DIR"

rsync -az *.tla *.cfg \
  tla2tools.jar CommunityModules-deps.jar \
  buildbox.aws:$REMOTE_DIR/
```

The wildcard `*.tla` picks up all modules (`Types.tla`, `ConsistentFailover.tla`, and any sub-modules like `HAGroupStore.tla`, `Admin.tla`, etc.).

#### Step 2: Run TLC on the remote server

Launch TLC via SSH. Output is captured to a log file on the remote server. Use `nohup` to survive SSH disconnects on long runs.

**Exhaustive check** (with symmetry reduction, per-iteration — run to completion):

```bash
ssh buildbox.aws "cd $REMOTE_DIR && nohup java -XX:+UseParallelGC \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover.cfg \
  -workers auto -cleanup \
  > tlc-exhaustive.log 2>&1 &"
```

The `ConsistentFailover.cfg` includes a `SYMMETRY` declaration for RS permutations, which significantly reduces the state space for exhaustive BFS.

**Simulation** (no symmetry, for later phases when exhaustive is intractable):

```bash
ssh buildbox.aws "cd $REMOTE_DIR && nohup java -XX:+UseParallelGC \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  -Dtlc2.TLC.stopAfter=300 \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover-sim.cfg \
  -simulate -workers auto \
  > tlc-sim-300s.log 2>&1 &"
```

Adjust `-Dtlc2.TLC.stopAfter` for longer runs (900s, 14400s, 86400s). No `SYMMETRY` in the simulation config — it provides no benefit for random trace sampling.

**Liveness check** (no symmetry — incompatible with `SYMMETRY` in TLC):

```bash
ssh buildbox.aws "cd $REMOTE_DIR && nohup java -XX:+UseParallelGC \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover-liveness.cfg \
  -workers auto -cleanup \
  > tlc-liveness.log 2>&1 &"
```

#### Step 3: Monitor progress

Check whether TLC is still running and tail the log:

```bash
ssh buildbox.aws "ps aux | grep '[t]lc2.TLC' | head -5"
ssh buildbox.aws "tail -20 $REMOTE_DIR/tlc-exhaustive.log"
```

For long-running checks, look for the completion summary:

```bash
ssh buildbox.aws "grep -E '(No error|Invariant .* is violated|distinct states)' $REMOTE_DIR/tlc-exhaustive.log"
```

#### Step 4: Retrieve results

After TLC completes, retrieve the log and any counterexample traces:

```bash
mkdir -p results/$ITER
scp "buildbox.aws:$REMOTE_DIR/tlc-*.log" results/$ITER/
scp "buildbox.aws:$REMOTE_DIR/MC*.out" results/$ITER/ 2>/dev/null
scp "buildbox.aws:$REMOTE_DIR/*.dump" results/$ITER/ 2>/dev/null
```

TLC writes counterexample traces to files named `MC.out` or `MC_TE_*.out` in the working directory. These are plain-text files containing the step-by-step state trace that violated an invariant.

#### Step 5: Analyze results

Check the log for the outcome:

```bash
# Clean run — look for "Model checking completed. No error has been found."
grep -E "(No error|Invariant .* is violated|PROPERTY .* is violated|Error:)" results/$ITER/tlc-*.log

# State space summary
grep -E "(distinct states|states generated|depth)" results/$ITER/tlc-*.log
```

If an invariant violation was found, the counterexample trace in the log (or in `MC*.out`) contains the exact sequence of states leading to the violation. See §10.3 for triage classification.

### Per-Cycle Validation Summary

Each iteration's Step 4 (RUN TLC) from §10.2 follows this sequence:

```
┌───────────────────────────────────────────────────────────────────────┐
│ LOCAL: Edit spec in Cursor                                            │
├───────────────────────────────────────────────────────────────────────┤
│ LOCAL: SANY syntax check ($JAVA17 -cp tla2tools.jar tla2sany.SANY ...)│
│        Fix parse errors. Repeat until clean.                          │
├───────────────────────────────────────────────────────────────────────┤
│ REMOTE: rsync spec + tools to buildbox.aws:/tmp/tla-phoenix/$ITER     │
├───────────────────────────────────────────────────────────────────────┤
│ REMOTE: ssh buildbox.aws — run TLC (nohup, -workers auto, log file)   │
│         Exhaustive in early phases; simulation when needed            │
├───────────────────────────────────────────────────────────────────────┤
│ REMOTE: Monitor progress (tail log, check process)                    │
├───────────────────────────────────────────────────────────────────────┤
│ LOCAL: scp log + counterexample traces back to results/$ITER/         │
├───────────────────────────────────────────────────────────────────────┤
│ LOCAL: Analyze results. If violation → triage → fix → repeat.         │
│        If clean → record stats → update plan → git commit.            │
└───────────────────────────────────────────────────────────────────────┘
```

### Recommended TLC Durations

In the early phases, exhaustive model checking runs to completion (no `-Dtlc2.TLC.stopAfter` needed). The following simulation durations apply only once simulation mode is adopted for larger state spaces:

| Tier | Duration | `-Dtlc2.TLC.stopAfter=` | Use Case |
|------|----------|-------------------------|----------|
| Quick | 300s (5 min) | `300` | Fast feedback during development |
| Standard | 900s (15 min) | `900` | Validation after completing an iteration |
| Deep | 14400s (4 hr) | `14400` | Milestone verification |
| Overnight | 86400s (24 hr) | `86400` | High-confidence sweep for rare interleavings |

All simulation durations are wall-clock time. On the 128-core remote server, `-workers auto` will use all available cores, providing ~64× the throughput of a typical developer machine.

---

## 7. Iterative Development Plan

Each iteration introduces exactly one new concept, produces a spec that TLC can verify, and is small enough to review and debug in isolation. Iterations are grouped into phases for readability, but the unit of work is the individual iteration.

---

### Phase 1: Cluster State Foundation

#### ~~Iteration 1 — Cluster states and valid transitions~~ ✅ COMPLETE

Created `Types.tla` (14-state `HAGroupState` set, `ActiveStates`/`StandbyStates`/`TransitionalActiveStates` subsets, 22-pair `AllowedTransitions` table from `HAGroupStoreRecord.java` L99-123 including the `ANIS` self-transition, `ClusterRole` set, `RoleOf` operator, `Peer` helper), `ConsistentFailover.tla` (single `clusterState` variable, `Init` with one cluster `AIS` and the other `S`, `Transition(c)` action with a coordination guard preventing entry to `ACTIVE` role from non-`ACTIVE` when peer is `ACTIVE`, `TypeOK`/`MutualExclusion` invariants, `TransitionValid` action constraint, empty `Symmetry`), and `ConsistentFailover.cfg` (`Cluster = {c1, c2}`). Exhaustive TLC on `buildbox.aws` (128 workers): 108 distinct states, depth 11, all invariants pass, no errors. The coordination guard was required because the per-cluster transition table alone is insufficient for mutual exclusion — `STA→AIS` can fire while the peer is still `AIS` without it; the full peer-reactive guards are formalized in Iteration 3.

#### ~~Iteration 2 — Role mapping and Active-role mutual exclusion~~ ✅ COMPLETE

`ClusterRole` (6-value enum including `UNKNOWN`), `RoleOf` operator, and the `RoleOf`-based `MutualExclusion` invariant were pulled forward into Iteration 1. This iteration added `ActiveRoles == {"ACTIVE"}` role-level subset to `Types.tla` and `ActiveToStandbyNotActive` static sanity invariant to `ConsistentFailover.tla` (asserts `RoleOf("ATS") \notin ActiveRoles /\ RoleOf("ANISTS") \notin ActiveRoles`), registered in `ConsistentFailover.cfg`. SANY parse: clean. Expected TLC result: same 108 distinct states, depth 11, all invariants pass (static invariant adds no new states).

#### Iteration 3 — Peer-reactive transitions (FailoverManagementListener)

**Modules created:** `HAGroupStore.tla`, `Admin.tla`.
**Modules modified:** `ConsistentFailover.tla`.

**What to add:**

`HAGroupStore.tla`:
- `EXTENDS Types`.
- Declares all shared variables as `VARIABLE`.
- `PeerReact(c, peer)`: When `clusterState[peer]` matches a trigger condition, transition `clusterState[c]` per the reactive table (§3.5).
- `AutoComplete(c)`: AbTS→S, AbTAIS→AIS, AbTANIS→ANIS.

`Admin.tla`:
- `EXTENDS Types`.
- Declares all shared variables as `VARIABLE`.
- `AdminStartFailover(c)`, `AdminAbortFailover(c)` with appropriate guards.

`ConsistentFailover.tla`:
- Add `INSTANCE` declarations: `haGroupStore == INSTANCE HAGroupStore`, `admin == INSTANCE Admin`.
- Replace direct transition actions in `Next` with actor-driven disjuncts: `haGroupStore!PeerReact(...)`, `haGroupStore!AutoComplete(...)`, `admin!AdminStartFailover(...)`, `admin!AdminAbortFailover(...)`.
- Invariant: `AbortSafety`.

**Expected TLC result:** State space may grow significantly as interleavings between admin and reactive actions are explored. `MutualExclusion` and `AbortSafety` must pass.

#### Iteration 4 — Non-atomic failover: two-step final transition

**Modules modified:** `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`HAGroupStore.tla`:
- Decompose the final failover step `(ATS, AIS) → (S, AIS)` into two separate actions:
  1. `StandbyBecomesActive(c)`: `STA → AIS` (standby writes its own ZK).
  2. `OldActiveBecomesStandby(c)`: `ATS → S` (old active reacts to peer AIS).
- These are two separate `PeerReact` actions, not one atomic step.

`ConsistentFailover.tla`:
- `NonAtomicFailoverSafe` invariant: During the window where one cluster is AIS and the other is ATS, `RoleOf(ATS) ∉ ActiveRoles`.

**Expected TLC result:** TLC explores the interleaving between steps 1 and 2. `MutualExclusion` must pass, verifying the implementation's safety argument.

**Note:** The failover tool has a built-in timeout that bounds the duration of the non-atomic window. If the old active does not react within the timeout, admin intervention is required. The TLA+ model does not explicitly model this timeout but verifies that safety holds regardless of window duration.

---

### Phase 2: Replication Writer and HDFS

#### Iteration 5 — Writer mode state machine (per-RS)

**Modules created:** `Writer.tla`.
**Modules modified:** `Types.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- Constants: `RS` (set of region servers per cluster, e.g., `{rs1, rs2}`), `WriterMode == {INIT, SYNC, S_AND_F, SYNC_AND_FWD}`.

`Writer.tla`:
- `EXTENDS Types`.
- Declares all shared variables as `VARIABLE`.
- Actions: `WriterInit(c, rs)`, `WriterToSF(c, rs)`, `WriterSFToSyncFwd(c, rs)`, `WriterSyncFwdToSync(c, rs)`, `WriterSyncFwdToSF(c, rs)`, `WriterSyncToSyncFwd(c, rs)`.
- UNCHANGED for `clusterState` in all writer actions (no coupling yet).

`ConsistentFailover.tla`:
- Variable: `writerMode ∈ [Cluster → [RS → WriterMode]]`.
- Init: `∀ c, rs: writerMode[c][rs] = INIT`.
- `writer == INSTANCE Writer`.
- Add writer action disjuncts to `Next`.
- `Symmetry == Permutations(RS)` (RS are now introduced).
- Invariants: `WriterTypeOK`, `WriterTransitionValid`.

**Expected TLC result:** State space grows by `|WriterMode|^(|Cluster|×|RS|)`. Writer actions fire independently of cluster state.

#### Iteration 6 — HDFS directory predicates and writer-cluster coupling

**Modules created:** `Environment.tla`.
**Modules modified:** `Writer.tla`, `Admin.tla`, `ConsistentFailover.tla`.

**What to add:**

`Environment.tla`:
- `EXTENDS Types`.
- Declares all shared variables as `VARIABLE`.
- `HDFSFail(c)`, `HDFSRecover(c)` toggle `hdfsAvailable[c]`.

`Writer.tla`:
- Couple writer mode to HDFS availability:
  - `WriterToSF(c, rs)` guard: `hdfsAvailable[peer(c)] = FALSE`.
  - `WriterToSF` effect: sets `outDirEmpty[c] = FALSE` (writes accumulate).
  - `WriterSyncFwdToSync` effect: sets `outDirEmpty[c] = TRUE` (drain complete).

`Admin.tla`:
- `AIStoATSPrecondition` guard on `AdminStartFailover`: requires `outDirEmpty[c] ∧ ∀ rs: writerMode[c][rs] = SYNC`.

`ConsistentFailover.tla`:
- Variables: `outDirEmpty ∈ [Cluster → BOOLEAN]`, `hdfsAvailable ∈ [Cluster → BOOLEAN]`.
- Init: `∀ c: outDirEmpty[c] = TRUE, hdfsAvailable[c] = TRUE`.
- `environment == INSTANCE Environment`.
- Add environment action disjuncts to `Next`.
- Invariants: `AIStoATSPrecondition`, `WriterClusterConsistency`.

**Expected TLC result:** Writer and cluster state machines now interleave with HDFS failures. State space grows substantially.

#### Iteration 7 — Writer triggers cluster state change

**Modules modified:** `Writer.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Writer.tla`:
- When `WriterToSF(c, rs)` fires and cluster is AIS, atomically transition cluster to ANIS (modeling the implementation where the RS calls `setHAGroupStatusToStoreAndForward()` which updates cluster state).

`HAGroupStore.tla`:
- When `WriterSyncFwdToSync` fires (last RS returns to SYNC) and `outDirEmpty[c]`, mark cluster eligible for `ANIS → AIS` (subject to anti-flapping, modeled in Phase 3).

`ConsistentFailover.tla`:
- Invariant: `NoAISWithSFWriter` — if any RS is in S&F, cluster cannot be AIS.

**Expected TLC result:** Writer actions now drive cluster state transitions.

---

### Phase 3: Anti-Flapping and Timing

#### Iteration 8 — Logical clock and anti-flapping gate

**Modules modified:** `Types.tla`, `Environment.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- Constant: `WaitTimeForSync ∈ Nat` (models `1.1 × ZK_SESSION_TIMEOUT`).
- Constant: `MaxClock ∈ Nat` (bound for TLC tractability).

`Environment.tla`:
- `ClockTick` action: non-deterministically increments `clock`.

`HAGroupStore.tla`:
- ANIS self-transition (heartbeat): `ANISHeartbeat(c)` refreshes `zkMtime[c] = clock` without changing `clusterState[c]`.
- Guard on `ANIS → AIS`: `clock - zkMtime[c] ≥ WaitTimeForSync`.

`ConsistentFailover.tla`:
- Variables: `clock ∈ Nat`, `zkMtime ∈ [Cluster → Nat]`.
- `AntiFlapGate` action constraint: `ANIS → AIS` transition never fires when `clock - zkMtime[c] < WaitTimeForSync`.
- State constraint: `clock ≤ MaxClock`.

**Expected TLC result:** Anti-flapping mechanism prevents rapid ANIS↔AIS oscillation. State space grows with clock dimension.

#### Iteration 9 — RS-level ZK races (optimistic locking)

**Modules modified:** `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`HAGroupStore.tla`:
- Model multiple RS on the same cluster racing to update ZK state:
  - Two RS may both see cluster in ANIS and both attempt `ANIS → AIS`.
  - Only one succeeds (non-deterministic choice); the other sees `BadVersionException` and retries.
- `ZKUpdate(c, rs, newState)` action with version check.

`ConsistentFailover.tla`:
- Variable: `zkVersion ∈ [Cluster → Nat]` for optimistic locking.
- Invariant: `ZKVersionMonotonic` — versions only increase.

**Expected TLC result:** Optimistic locking ensures serialized updates. State space may grow; the `SYMMETRY` declaration on RS in the exhaustive config (`ConsistentFailover.cfg`) keeps this tractable.

---

### Phase 4: Replication Reader and Replay

#### Iteration 10 — Replay state machine

**Modules created:** `Reader.tla`.
**Modules modified:** `Types.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `ReplayStateSet == {NOT_INITIALIZED, SYNC, DEGRADED, SYNCED_RECOVERY}`.

`Reader.tla`:
- `EXTENDS Types`.
- Declares all shared variables as `VARIABLE`.
- Actions: `ReplayAdvance(c)`, `ReplayDetectDegraded(c)`, `ReplayDetectRecovery(c)`, `ReplayRewind(c)`.
- Key behavior: In SYNCED_RECOVERY, `lastRoundProcessed` resets to `lastRoundInSync` before transitioning to SYNC.

`ConsistentFailover.tla`:
- Variables: `replayState ∈ [Cluster → ReplayStateSet]`, `lastRoundInSync ∈ [Cluster → Nat]`, `lastRoundProcessed ∈ [Cluster → Nat]`, `failoverPending ∈ [Cluster → BOOLEAN]`, `inProgressDirEmpty ∈ [Cluster → BOOLEAN]`.
- Init: `∀ c: replayState[c] = NOT_INITIALIZED`.
- `reader == INSTANCE Reader`.
- Add reader action disjuncts to `Next`.

**Expected TLC result:** Replay state machine runs independently of cluster state (coupling added in next iteration).

#### Iteration 11 — Failover trigger and replay-cluster coupling

**Modules modified:** `HAGroupStore.tla`, `Reader.tla`, `ConsistentFailover.tla`.

**What to add:**

`HAGroupStore.tla`:
- `SetFailoverPending(c)`: triggered by `PeerReact` when standby detects peer ATS, setting `failoverPending[c] = TRUE`.

`Reader.tla`:
- `TriggerFailover(c)`: guard requires all three conditions: `failoverPending[c] ∧ inProgressDirEmpty[c] ∧ lastRoundProcessed[c] ≥ lastRoundInSync[c]`. Effect: `clusterState[c]' = AIS` (from STA).

`ConsistentFailover.tla`:
- `FailoverTriggerCorrectness` invariant (§4.1 property 9).
- `NoDataLoss` invariant: `STA → AIS` only when replay is complete.

**Expected TLC result:** The full failover sequence is now verifiable end-to-end, including replay completeness.

---

### Phase 5: Crash and Recovery

#### Iteration 12 — RS crash (writer fail-stop)

**Modules modified:** `Environment.tla`, `Writer.tla`, `ConsistentFailover.tla`.

**What to add:**

`Environment.tla`:
- `RSCrash(c, rs)` action: sets `rsAlive[c][rs] = FALSE`, models writer fail-stop in S&F mode (`StoreAndForwardModeImpl.onFailure()`
  → `logGroup.abort()`). Source: `StoreAndForwardModeImpl.java` L116-123.
- `RSRestart(c, rs)` action: sets `rsAlive[c][rs] = TRUE`, `writerMode[c][rs] = INIT`. Forwarder resumes draining via `initializeLastRoundProcessed()` which scans existing files.

`Writer.tla`:
- Guard all writer actions on `rsAlive[c][rs] = TRUE`.
- OUT directory shards are time-based (not per-RS), so surviving RS forwarders can drain a crashed RS's fully-written files from the shared shard directories. Mid-write files with unclosed HDFS leases are blocked until lease expiry (~10 min). Modeled as a transient delay on `outDirEmpty` (not a permanent block). Source: `ReplicationShardDirectoryManager.java` L116-136.

`ConsistentFailover.tla`:
- Variable: `rsAlive ∈ [Cluster → [RS → BOOLEAN]]`.
- Impact on cluster state: if all RS crash, cluster becomes unreachable.

**Expected TLC result:** RS failures inject non-deterministic disruptions into the protocol. Verify mutual exclusion and no data loss under failures.

#### Iteration 13 — ZK connectivity and session expiry

**Modules modified:** `Environment.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Environment.tla`:
- `ZKDisconnect(c, rs)`: RS loses ZK connection → RS aborts (modeling the implementation's fail-stop on DISCONNECTED).
- `ZKReconnect(c, rs)`: RS reconnects to ZK.

`HAGroupStore.tla`:
- Guard reactive transitions on ZK connectivity.
- Impact: delayed or missed peer state detection.

`ConsistentFailover.tla`:
- Variable: `zkConnected ∈ [Cluster → [RS → BOOLEAN]]`.

**Expected TLC result:** ZK connectivity failures may delay failover but should not violate safety properties.

---

### Phase 6: Liveness and Refinement

#### Iteration 14 — Fairness and liveness properties

**Modules modified:** `ConsistentFailover.tla`, `ConsistentFailover-liveness.cfg`.

**What to add:**

`ConsistentFailover.tla`:
- `Fairness` formula:
  - Weak fairness (WF) on all deterministic protocol steps (auto-completion, peer reactions, replay advance) — referencing sub-module actions via `haGroupStore!AutoComplete(c)`, `reader!ReplayAdvance(c)`, etc.
  - Strong fairness (SF) on environmental recovery actions (`environment!HDFSRecover(c)`, `environment!RSRestart(c, rs)`).
  - No fairness on non-deterministic environmental faults (HDFS failure, RS crash, ZK disconnect).
- Liveness properties:
  - `FailoverCompletion`: initiated failover eventually completes (or aborted).
  - `DegradationRecovery`: ANIS eventually returns to AIS if HDFS recovers.
  - `AbortCompletion`: initiated abort eventually completes.

`ConsistentFailover-liveness.cfg`:
- Created with `PROPERTY FailoverCompletion DegradationRecovery AbortCompletion`.
- No `SYMMETRY` (TLC does not support symmetry reduction with liveness).

**Expected TLC result:** Liveness properties pass under fairness assumptions with all quirk flags OFF (idealized model). Liveness config required (`ConsistentFailover-liveness.cfg`, no `SYMMETRY` — TLC does not support symmetry reduction with liveness checking). Iterations 21-22 re-check liveness with quirk flags ON to expose known implementation gaps.

#### Iteration 15 — ANISTS failover path

**Modules modified:** `Admin.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Admin.tla`:
- Refine `AdminStartFailover` to handle ANIS case: transitions to ANISTS (not ATS). Source: `HAGroupStoreManager.initiateFailoverOnActiveCluster()` L389-397 checks current state and selects `AIS → ATS` or `ANIS → ANISTS`.

`HAGroupStore.tla`:
- `ANISTSToATS(c)` action: when `outDirEmpty[c]` becomes TRUE, ANISTS → ATS. Source: `setHAGroupStatusToSync()` L341-355 — if current state is `ANISTS`, target is `ATS` (= `ACTIVE_IN_SYNC_TO_STANDBY`).
- Guard: `ANISTSToATS` is subject to anti-flapping wait gate (see §3.6).

`ConsistentFailover.tla`:
- Verify that the ANIS failover path also satisfies mutual exclusion and no data loss.
- **Key scenario**: Verify that `(ANIS, DS) → (ANISTS, DS) → (ATS, DS) → (ATS, STA) → (ATS, AIS) → (S, AIS)` completes successfully. With `DS → STA` in the `allowedTransitions` table, the standby can enter `STA` from `DEGRADED_STANDBY` when it detects peer `ATS`.

**Expected TLC result:** Both AIS and ANIS failover paths verified. ANIS failover from `(ANIS, DS)` completes end-to-end with mutual exclusion and no data loss preserved.

---

### Phase 7: Quirk Flags and Bug Modeling

#### Iteration 16 — UseNonAtomicFailoverQuirk

**Modules modified:** `Types.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseAtomicFailover ∈ BOOLEAN` constant with `ASSUME`.

`HAGroupStore.tla`:
- When `UseAtomicFailover = TRUE`: single action `(ATS, STA) → (S, AIS)`.
- When `UseAtomicFailover = FALSE` (default): two separate actions as modeled in Iteration 4.

`ConsistentFailover.tla`:
- Verify `MutualExclusion` passes in both modes.

**Expected TLC result:** Both modes pass safety. The quirk flag documents the design-vs-implementation divergence.

#### Iteration 17 — UseCollapsedDegradedQuirk (Low Priority)

**Note:** The team confirmed that the collapsed single `DEGRADED_STANDBY` state is an intentional simplification, not a deferred feature. This iteration is retained for optional exploratory analysis but is low priority.

**Modules modified:** `Types.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseDesignDegradedStates ∈ BOOLEAN` constant with `ASSUME`.

`HAGroupStore.tla`:
- When `UseDesignDegradedStates = TRUE`: use DSFW/DSFR/DS sub-states from the design.
- When `UseDesignDegradedStates = FALSE` (default): use single `DEGRADED_STANDBY` from implementation.

`ConsistentFailover.tla`:
- Verify safety properties hold in both modes.

**Expected TLC result:** Safety holds regardless of degraded sub-state granularity.

#### Iteration 18 — OFFLINE state and --force bypass

**Modules modified:** `Types.tla`, `Admin.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseForceQuirk ∈ BOOLEAN` constant with `ASSUME`.

`Admin.tla`:
- `AdminGoOffline(c)`: transition to OFFLINE (via normal transition table).
- `AdminForceRecoverFromOffline(c)`: OFFLINE → S (guarded by `UseForceQuirk = TRUE`). This models the `PhoenixHAAdminTool update
  --force --state STANDBY` command, which bypasses the transition table validation entirely. This is the intentional operational procedure for recovering from OFFLINE.

`ConsistentFailover.tla`:
- Verify safety properties hold with and without `--force` recovery.
- `OFFLINESink` invariant: when `UseForceQuirk = FALSE`, once a cluster enters OFFLINE it cannot leave.

**Expected TLC result:** With `UseForceQuirk = FALSE`, OFFLINE is a true sink state. With `UseForceQuirk = TRUE`, the `--force` bypass allows recovery to STANDBY. Safety (mutual exclusion) holds in both modes.

---

### Phase 8: Forced Failover and Edge Cases

#### Iteration 19 — Forced failover

**Modules modified:** `Types.tla`, `Admin.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseForceFailover ∈ BOOLEAN` constant (default FALSE) with `ASSUME`.

`Admin.tla`:
- `AdminForceFailover(c)`: allows DS → STA without requiring replay complete. Guarded by `UseForceFailover = TRUE`.

`ConsistentFailover.tla`:
- When `UseForceFailover = TRUE`, `NoDataLoss` invariant is expected to FAIL (forced failover may violate zero RPO by design).
- New `ForcedFailoverSafety` invariant: even under forced failover, `MutualExclusion` must hold.

**Expected TLC result:** `MutualExclusion` passes; `NoDataLoss` fails (expected, by design).

#### Iteration 20 — Replay rewind verification

**Modules modified:** `Reader.tla`, `ConsistentFailover.tla`.

**What to add:**

`Reader.tla`:
- Detailed modeling of the SYNCED_RECOVERY rewind: after DEGRADED → SYNCED_RECOVERY, `lastRoundProcessed` is reset to `lastRoundInSync`.

`ConsistentFailover.tla`:
- Invariant: `ReplayRewindCorrectness` — after rewind, `lastRoundProcessed[c] = lastRoundInSync[c]`.
- Verify that the rewind ensures no data loss during the ANIS→AIS→failover sequence.

**Expected TLC result:** Rewind mechanism preserves NoDataLoss.

---

### Phase 9: Implementation Liveness Gaps

These iterations model known implementation behaviors that can prevent liveness. Each uses a quirk flag: with the quirk ON, the model faithfully represents the implementation (liveness may not hold without admin intervention). With the quirk OFF, the behavior is idealized, allowing TLC to check for other unexpected liveness problems.

#### Iteration 21 — UseForwarderStuckQuirk

**Modules modified:** `Types.tla`, `Environment.tla`, `Writer.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseForwarderStuckQuirk ∈ BOOLEAN` constant with `ASSUME`.

`Environment.tla`:
- `ForwarderStuck(c)` action: when `UseForwarderStuckQuirk = TRUE`, non-deterministically sets `forwarderStuck[c] = TRUE`, permanently preventing the OUT directory from draining. Models the implementation's lack of timeout on `FileUtil.copy()` and the indefinite retry loop in `ReplicationLogDiscoveryForwarder`. Source: `processFile()` L133-152.
- `ForwarderUnstuck(c)` is NOT available — stuck is permanent (models the implementation where admin must abort).

`Writer.tla`:
- Guard `WriterSyncFwdToSync` (drain complete) on `¬forwarderStuck[c] ∨ ¬UseForwarderStuckQuirk`.

`ConsistentFailover.tla`:
- Variable: `forwarderStuck ∈ [Cluster → BOOLEAN]`.
- With quirk ON: `FailoverCompletion` requires `abortInitiated` when `forwarderStuck[c]` — liveness without admin is expected to FAIL.
- With quirk OFF: `FailoverCompletion` should pass under standard fairness (weak fairness on forwarder drain).

**Expected TLC result:** With quirk ON, TLC finds that failover stalls when forwarder is stuck and admin does not abort — this is a known implementation characteristic. With quirk OFF, verify no other liveness gaps exist on the ANISTS path.

#### Iteration 22 — UseRetryExhaustionQuirk

**Modules modified:** `Types.tla`, `HAGroupStore.tla`, `ConsistentFailover.tla`.

**What to add:**

`Types.tla`:
- `UseRetryExhaustionQuirk ∈ BOOLEAN` constant with `ASSUME`.

`HAGroupStore.tla`:
- `ReactiveTransitionFail(c)` action: when `UseRetryExhaustionQuirk = TRUE`, a `PeerReact` action non-deterministically fails (both retries exhausted). The transition is permanently lost — `lastKnownPeerState` is advanced but the local state is not updated. Models `FailoverManagementListener.onStateChange()` (`HAGroupStoreManager.java` L653-704) where 2 retries fail and the method returns silently.
- Recovery modeled via: (a) a subsequent different peer state change produces a new event; (b) `ZKReconnect` action can re-deliver initial state; (c) admin manual intervention.

`ConsistentFailover.tla`:
- With quirk ON: `FailoverCompletion` and `AbortCompletion` may require alternative recovery paths (subsequent peer state change, ZK reconnect, or admin action). Expected to reveal stall scenarios.
- With quirk OFF: reactive transitions always succeed (weak fairness), verifying liveness without this implementation gap.

**Expected TLC result:** With quirk ON, TLC may find that failover or abort stalls when a critical reactive transition is lost (e.g., standby fails to detect peer ATS, or old active fails to detect new active AIS). These are known implementation characteristics. With quirk OFF, verify no other liveness gaps in the reactive transition protocol.

---

## 8. Mapping from Design/Code to TLA+ Actions

| Design Event / Code Path | TLA+ Action | Module | Iter | Status |
|--------------------------|-------------|--------|------|--------|
| `initiateFailoverOnActiveCluster()` | `AdminStartFailover(c)` | `Admin.tla` | 3 | ⏳ |
| `setHAGroupStatusToAbortToStandby()` | `AdminAbortFailover(c)` | `Admin.tla` | 3 | ⏳ |
| `FailoverManagementListener` peer ATS detected | `PeerReactToATS(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| `FailoverManagementListener` peer AIS detected (old active → S) | `PeerReactToAIS(c)` | `HAGroupStore.tla` | 4 | ⏳ |
| `FailoverManagementListener` peer ANIS detected (standby → DS) | `PeerReactToANIS(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| `FailoverManagementListener` peer AbTS detected (active → AbTAIS) | `PeerReactToAbTS(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| Auto-completion: AbTS → S | `AutoComplete(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| Auto-completion: AbTAIS → AIS | `AutoComplete(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| Auto-completion: AbTANIS → ANIS | `AutoComplete(c)` | `HAGroupStore.tla` | 3 | ⏳ |
| `SyncModeImpl.onFailure()` (L61-77) → `setHAGroupStatusToStoreAndForward()` | `WriterToSF(c, rs)` | `Writer.tla` | 5 | ⏳ |
| `SyncAndForwardModeImpl.onFailure()` (L66-82) → `STORE_AND_FORWARD` | `WriterSyncFwdToSF(c, rs)` | `Writer.tla` | 5 | ⏳ |
| `ReplicationLogDiscoveryForwarder.processFile()` (L133-152) throughput check → `S&F→S&FWD` | `WriterSFToSyncFwd(c, rs)` | `Writer.tla` | 5 | ⏳ |
| `ReplicationLogDiscoveryForwarder.processNoMoreRoundsLeft()` (L155-184) → `setHAGroupStatusToSync()` | `WriterSyncFwdToSync(c, rs)` | `Writer.tla` | 5 | ⏳ |
| `ReplicationLogDiscoveryForwarder.init()` ANIS listener (L98-108) → `SYNC→S&FWD` on other RS | `WriterSyncToSyncFwd(c, rs)` | `Writer.tla` | 5 | ⏳ |
| `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()` (L71-87) | `ANISHeartbeat(c)` | `HAGroupStore.tla` | 8 | ⏳ |
| `HAGroupStoreClient.validateTransitionAndGetWaitTime()` (L1027-1046) | Guard on `ANIS → AIS` and `ANISTS → ATS` | `HAGroupStore.tla` | 8 | ⏳ |
| `HAGroupStoreManager.setHAGroupStatusToSync()` (L341-355) — dual target: `ANISTS→ATS` if current is ANISTS, else `→AIS` | `ANISTSToATS(c)` + `ANISToAIS(c)` | `HAGroupStore.tla` | 8/15 | ⏳ |
| `ReplicationLogDiscoveryReplay.shouldTriggerFailover()` | `TriggerFailover(c)` | `Reader.tla` | 11 | ⏳ |
| `ReplicationLogDiscoveryReplay.replay()` | `ReplayAdvance(c)` | `Reader.tla` | 10 | ⏳ |
| Replay SYNC → DEGRADED (peer ANIS detected) | `ReplayDetectDegraded(c)` | `Reader.tla` | 10 | ⏳ |
| Replay DEGRADED → SYNCED_RECOVERY (peer AIS detected) | `ReplayDetectRecovery(c)` | `Reader.tla` | 10 | ⏳ |
| SYNCED_RECOVERY rewind | `ReplayRewind(c)` | `Reader.tla` | 10 | ⏳ |
| RS abort (fail-stop in S&F) | `RSCrash(c, rs)` | `Environment.tla` | 12 | ⏳ |
| RS restart | `RSRestart(c, rs)` | `Environment.tla` | 12 | ⏳ |
| ZK DISCONNECTED event → RS abort | `ZKDisconnect(c, rs)` | `Environment.tla` | 13 | ⏳ |
| HDFS becomes unavailable | `HDFSFail(c)` | `Environment.tla` | 6 | ⏳ |
| HDFS recovers | `HDFSRecover(c)` | `Environment.tla` | 6 | ⏳ |
| `PhoenixHAAdminTool update --force` | `AdminForceFailover(c)` | `Admin.tla` | 19 | ⏳ |
| Peer goes OFFLINE | `AdminGoOffline(c)` | `Admin.tla` | 18 | ⏳ |
| `PhoenixHAAdminTool update --force --state STANDBY` (OFFLINE recovery) | `AdminForceRecoverFromOffline(c)` | `Admin.tla` | 18 | ⏳ |
| `setData().withVersion()` (ZK optimistic locking) | `ZKUpdate(c, rs, newState)` | `HAGroupStore.tla` | 9 | ⏳ |
| Forwarder permanently stuck (no timeout on `FileUtil.copy()`) | `ForwarderStuck(c)` | `Environment.tla` | 21 | ⏳ |
| `FailoverManagementListener` retry exhaustion (2 retries, then lost) | `ReactiveTransitionFail(c)` | `HAGroupStore.tla` | 22 | ⏳ |

---

## 9. Source Code Reference Map

### State Machine Core

| File | Module | Key Sections |
|------|--------|-------------|
| `HAGroupStoreRecord.java` | `phoenix-core-client` | `HAGroupState` enum (L51-65), `getClusterRole()` role mapping (L73-97), `allowedTransitions` static init (L99-123), `isTransitionAllowed()` (L130) |
| `HAGroupStoreManager.java` | `phoenix-core-client` | `TargetStateResolver` interface (L84-88), `createPeerStateTransitions()` (L104-138), `createLocalStateTransitions()` (L140-150), `FailoverManagementListener` (L633-706), `initiateFailoverOnActiveCluster()` (L375-400), `setHAGroupStatusToAbortToStandby()` (L419-425), `setHAGroupStatusToSync()` (L341-355), `setHAGroupStatusToStoreAndForward()` (L318-324) |
| `HAGroupStoreClient.java` | `phoenix-core-client` | `setHAGroupStatusIfNeeded()` (L325-394), `validateTransitionAndGetWaitTime()` (L1027-1046), `ZK_SESSION_TIMEOUT_MULTIPLIER = 1.1` (L98), ZK optimistic locking via `PhoenixHAAdmin.updateHAGroupStoreRecordInZooKeeper()`, `PathChildrenCache` lifecycle, `handleStateChange()` (L1088) |
| `PhoenixHAAdmin.java` | `phoenix-core-client` | `updateHAGroupStoreRecordInZooKeeper()` (L506-524), `createHAGroupStoreRecordInZooKeeper()` (L486), ZK path at `phoenix/consistentHA/<group>` (L477) |
| `PhoenixHAAdminTool.java` | `phoenix-core-client` | CLI commands: `initiate-failover` (L509), `abort-failover` (L648), `update` (L238) with `--force` flag, `--state` arg |
| `ClusterRoleRecord.java` | `phoenix-core-client` | `ClusterRole` enum (L59-107): `ACTIVE`, `STANDBY`, `ACTIVE_TO_STANDBY`, `STANDBY_TO_ACTIVE`, `OFFLINE`, `UNKNOWN`; `isMutationBlocked()` (L84), `getDefaultHAGroupState()` (L90) |

### Replication Writer

| File | Module | Key Sections |
|------|--------|-------------|
| `ReplicationLogGroup.java` | `phoenix-core-server` | `ReplicationMode` enum (L199-246): `INIT`, `SYNC`, `STORE_AND_FORWARD`, `SYNC_AND_FORWARD`; `VALID_TRANSITIONS` table (L288-291); `setMode()` (L691), `checkAndSetMode()` (L703), `initializeReplicationMode()` (L458), Disruptor ring buffer, `append()` (L516), `sync()` (L556) |
| `SyncModeImpl.java` | `phoenix-core-server` | `onFailure()` → calls `setHAGroupStatusToStoreAndForward()` then returns `STORE_AND_FORWARD` (L61-77) |
| `StoreAndForwardModeImpl.java` | `phoenix-core-server` | `HA_GROUP_STORE_UPDATE_MULTIPLIER = 0.7` (L46), `startHAGroupStoreUpdateTask()` heartbeat (L71-87), `onFailure()` → `logGroup.abort()` **fail-stop** (L116-123) |
| `SyncAndForwardModeImpl.java` | `phoenix-core-server` | `onEnter()` creates standby log + starts forwarder (L44), `onFailure()` → `STORE_AND_FORWARD` (L66-82) |
| `ReplicationModeImpl.java` | `phoenix-core-server` | Abstract base for all modes |

### Replication Reader / Replay

| File | Module | Key Sections |
|------|--------|-------------|
| `ReplicationLogDiscoveryReplay.java` | `phoenix-core-server` | `ReplicationReplayState` enum (L550-555); `init()` subscribes to `DEGRADED_STANDBY`, `STANDBY`, `STANDBY_TO_ACTIVE`, `ABORT_TO_STANDBY` (L131-206); `replay()` state-aware round processing (L292-380); `shouldTriggerFailover()` 3-condition check (L500-533); `triggerFailover()` calls `setHAGroupStatusToSync()` (L535-548); `getConsistencyPoint()` (L564) |
| `ReplicationLogProcessor.java` | `phoenix-core-server` | Mutation replay engine, batch size 6400 (L63), async HBase client (L442) |
| `ReplicationLogReplay.java` | `phoenix-core-server` | Per-group replay orchestrator |
| `RecoverLeaseFSUtils.java` | `phoenix-core-server` | HDFS lease recovery for unclosed files |

### Replication Forwarder

| File | Module | Key Sections |
|------|--------|-------------|
| `ReplicationLogDiscoveryForwarder.java` | `phoenix-core-server` | `init()` subscribes to `ACTIVE_NOT_IN_SYNC` (→ `SYNC→SYNC_AND_FORWARD`, L98-108) and `ACTIVE_IN_SYNC` (→ `SYNC_AND_FORWARD→SYNC`, L113-123); `processFile()` copies OUT→IN, triggers `S&F→S&FWD` on good throughput (L133-152); `processNoMoreRoundsLeft()` calls `setHAGroupStatusToSync()` (L155-184) |

### Infrastructure

| File | Module | Key Sections |
|------|--------|-------------|
| `IndexRegionObserver.java` | `phoenix-core-server` | `preBatchMutate()` mutation blocking check (L631-678), `postBatchMutateIndispensably()` calls `replicateMutations()` (L2013-2068), `replicateMutations()` appends + syncs (L2626-2674) |
| `PhoenixRegionServerEndpoint.java` | `phoenix-core-server` | RS coprocessor lifecycle |
| `ReplicationShardDirectoryManager.java` | `phoenix-core-server` | Sharded HDFS directory layout: `/<hdfs-base>/<group>/in|out/shard/<id>/` |

---

## 10. Iteration Process and Success Criteria

### 10.1 Terminal Outcomes

Every iteration ends in one of two states:

1. **Clean TLC run**: The model checker exhaustively explores the state space for the configured parameters and reports zero invariant violations and zero property violations. The spec faithfully models the implementation and no issues are found.

2. **Legitimate finding**: TLC produces a counterexample trace that, after triage, is confirmed to represent a genuine issue in the Phoenix implementation — a bug, a race condition, or a design gap that requires a code or architectural change. The finding is documented with full traceability and handed off for remediation.

There is no third "acceptable" terminal state. Spurious violations caused by modeling errors are intermediate conditions that must be resolved before the iteration is considered complete.

### 10.2 Per-Iteration Workflow

Each iteration follows a fixed loop:

1. **CODE ANALYSIS** — Before writing TLA+, analyze the relevant implementation code paths for this iteration's scope. Ground the model in the actual implementation behavior. The spec captures
   the correct protocol behavior — do NOT model known bugs (use quirk flags instead). At the end of each iteration, compare the model against the implementation to identify gaps where the code diverges from the correct protocol. These gaps are the findings.
2. **WRITE / EDIT** — Add or modify spec per the iteration's scope (see Section 7 for iteration descriptions). All editing is done locally in Cursor.
3. **SYNTAX CHECK (local)** — Parse with SANY on the local machine. Fix all parse errors before proceeding to remote execution.
   ```
   cd /Users/apurtell/src/phoenix
   JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
   $JAVA17 -cp tla2tools.jar tla2sany.SANY ConsistentFailover.tla
   ```
   This completes in under a second. Repeat steps 2–3 until clean.
4. **RUN TLC (remote on buildbox.aws)** — Stage files and run TLC on the 128-core remote server. Follow the procedure in §6 "Remote: TLC Execution on buildbox.aws":
   a. `rsync` spec files + tool jars to `buildbox.aws:/tmp/tla-phoenix/$ITER`.
   b. `ssh buildbox.aws` — launch TLC with `nohup`, output to log file.
   c. Monitor progress via `ssh` + `tail`.
   d. `scp` log files and any counterexample traces back to `results/$ITER/`. In early phases, run exhaustive only (`ConsistentFailover.cfg`, 2c/2rs). Switch to simulation (`ConsistentFailover-sim.cfg`) once state spaces grow too large for exhaustive search to complete within ~1 hour.
5. **TRIAGE** — If TLC reports violations, retrieve the counterexample trace from the remote log file, classify per §10.3. Repeat from step 2 or 4 as needed.
6. **REGRESSION CHECK** — Re-verify all invariants and properties from prior iterations. A fix in iteration N must not break any invariant proven in iterations 1 through N-1. The primary and simulation configs provide this coverage automatically at every iteration.
7. **RECORD** — Document the TLC result, configuration, state count, and any findings. Include the remote log file path for traceability.
8. **UPDATE PLAN** — Mark the iteration complete in this plan document (Section 7). Append `✅ COMPLETE` to the iteration heading, convert the "What to add" description to past tense ("What was added"), and add TLC result stats.
9. **GIT COMMIT** — Commit the successful spec files, configuration, updated plan document, and iteration record to version control. The commit message must identify the iteration number and summarize the outcome (clean pass or legitimate finding).

Steps 1–5 repeat until TLC either passes cleanly or produces a confirmed legitimate finding. Step 6 is mandatory — no iteration is complete without a regression check against all prior invariants. Steps 8–9 are the terminal actions — an iteration is not considered done until the plan document is updated and the results are committed.

### 10.3 Finding Classification

When TLC produces a counterexample trace, classify it as one of:

| Classification | Definition | Resolution |
|----------------|-----------|------------|
| **Spec bug** | The TLA+ model is wrong (missing guard, wrong transition) | Fix the spec; iterate |
| **Invariant too strict** | The invariant disallows legitimate behavior | Relax the invariant with documentation |
| **Legitimate finding** | The implementation has a real bug or race | Document with trace; file JIRA |
| **Design ambiguity** | The design docs are unclear or contradictory | Resolve with design authors |

### 10.4 Quirk Flag Methodology

When a legitimate finding is identified:

1. Add a `UseXxxQuirk ∈ BOOLEAN` constant to `Types.tla`.
2. Model the buggy behavior guarded by `UseXxxQuirk = TRUE`.
3. Verify: with quirk ON, the relevant invariant FAILS (reproducing the bug).
4. Verify: with quirk OFF (default), all invariants PASS (validating the fix).
5. Document the quirk flag in this plan and in the spec comments.

---

## Appendix A: Design-vs-Implementation Divergences

The following divergences between the design documents (`Consistent_Failover.docx`, `HAGroup.docx`) and the implementation on the `PHOENIX-7562-feature-new` branch (HEAD `5a9e2d50c9`) are described below with its impact on formal verification.

### A.1 Non-Atomic Final Failover Step

The design describes the final failover step `(ATS, A*) → (S, AIS)` as a single atomic ZooKeeper multi-operation (`state-machines.md` §5, item 3; `architecture.md` §Key Safety Arguments step 4). The implementation uses two separate ZK writes to two independent ZK quorums: first the new active writes `ACTIVE_IN_SYNC` to its own ZK (`ReplicationLogDiscoveryReplay.triggerFailover()` L535-548 → `setHAGroupStatusToSync()` L341-355), then the old active's `FailoverManagementListener` reactively writes `STANDBY` to its own ZK (L633-706) after detecting the peer's `ACTIVE_IN_SYNC` via a ZK watcher.

A true atomic multi-op across two independent ZK quorums is not possible without a distributed transaction protocol, which the implementation intentionally avoids. Safety during the window between the two writes holds because the old active is in `ACTIVE_IN_SYNC_TO_STANDBY`, which maps to role `ACTIVE_TO_STANDBY` — a role where `isMutationBlocked()` returns `true` (`ClusterRoleRecord.java` L84), so clients never send mutations to it.

The window between the two writes is bounded by the failover tool's built-in timeout. If the old active does not transition to `STANDBY` within the timeout period, the admin is required to take action. The TLA+ model can treat this as a finite bound on the non-atomic window.

The TLA+ model decomposes this into two separate actions in Iteration 4. The `NonAtomicFailoverSafe` invariant verifies mutual exclusion during the interleaving window.

### A.2 Collapsed Degraded Standby Sub-States (Intentional Simplification)

The design defines three degraded standby sub-states: `DSFW` (Degraded Standby For Writer — peer writer cannot replicate synchronously), `DSFR` (Degraded Standby For Reader — local reader lag exceeds threshold), and `DS` (both degraded). These sub-states have distinct transitions between them (`state-machines.md` §3). The implementation collapses all three into a single `DEGRADED_STANDBY` enum value (`HAGroupStoreRecord.java` L61). The reader and writer degradation status is not distinguished at the ZK or HA-group-record level; `setReaderToDegraded()` and `setReaderToHealthy()` in `HAGroupStoreManager` (L427-475) transition between `STANDBY` and `DEGRADED_STANDBY` without distinguishing the cause.

The team confirmed this was intentionally removed to make the state machine simpler. The single `DEGRADED_STANDBY` state is the permanent design, not a deferred implementation. For safety verification the single state is sufficient because mutual exclusion does not depend on degradation sub-types. The TLA+ model follows the implementation's collapsed model throughout. Iteration 17 retains an optional `UseDesignDegradedStates` quirk flag for exploratory analysis, but this is low priority.

### A.3 OFFLINE as Sink State (Intentional)

The design shows bidirectional transitions `S → OFFLINE` and `OFFLINE → S` (`state-machines.md` §3), allowing an operator to take a cluster offline and bring it back. The implementation makes `OFFLINE` a terminal sink state with no allowed outbound transitions (`HAGroupStoreRecord.java` L109: `OFFLINE.allowedTransitions = ImmutableSet.of()`). Recovery from `OFFLINE` requires `PhoenixHAAdminTool update --force`, which bypasses the transition table validation.

The team confirmed this is intentional, not an oversight. The admin decides when to place a cluster offline and when to bring it back via `--force`. The `--force` recovery procedure should be documented as a standard operational runbook step.

The TLA+ model uses the implementation's sink behavior. Iteration 18 models the `--force` bypass as a separate action that operates outside the normal transition table, reflecting the actual operational procedure.

### A.4 Replay State Machine (Implementation-Only)

The design documents do not describe any replay state machine. The implementation introduces `ReplicationReplayState` (`NOT_INITIALIZED`, `SYNC`, `DEGRADED`, `SYNCED_RECOVERY`) in `ReplicationLogDiscoveryReplay.java` (L550-555) to manage the consistency point — the timestamp before which all mutations are guaranteed replicated — during `ANIS ↔ AIS` transitions of the active peer.

The critical behavior is during `SYNCED_RECOVERY`: when the active returns from `ANIS` to `AIS`, the standby's `replay()` method (L323-333) rewinds `lastRoundProcessed` back to `lastRoundInSync` using `getFirstRoundToProcess()` (which reads from `lastRoundInSync`, L389), then re-processes rounds from the last known good sync point. This ensures mutations received during the degraded period — which may include out-of-order or duplicated log files from the store-and-forward pipeline — are re-replayed against the authoritative sync boundary.

This state machine is the primary mechanism for enforcing the `NoDataLoss` invariant and is modeled concretely starting in Iteration 10. Without it, a failover triggered after a degraded period could miss mutations that arrived via the forwarding pipeline between `lastRoundInSync` and `lastRoundProcessed`.

**Idempotency of rewound mutations.** The `SYNCED_RECOVERY` rewind re-processes rounds that were already applied during the degraded period. Idempotency is guaranteed by two cooperating mechanisms: (1) every mutation in the replication log carries its original commit timestamp, so replaying the same cell at the same timestamp is a storage-level no-op; and (2) Phoenix compaction (`CompactionScanner`) retains all cells and delete markers within the max lookback window, ensuring tombstones are not compacted away before all mutations within the rewind window have been applied in their partial order. The design states: "Phoenix compaction in this design must already ensure we do not compact away tombstones too soon." Phoenix compaction is implemented and active in production with a 72-hour max lookback window. The design proposes a dynamic integration where the replication pipeline would extend the max lookback via `CompactionScanner.overrideMaxLookback()` to account for replication delays; this integration is not yet implemented on the `PHOENIX-7562-feature-new` branch, but is not strictly necessary when the globally configured max lookback (72 hours) vastly exceeds the maximum expected rewind span (minutes to low hours).

### A.5 ANIS Self-Transition (Heartbeat)

The design does not document any self-transition for the `ACTIVE_NOT_IN_SYNC` state. The implementation allows `ANIS → ANIS` in the transition table (`HAGroupStoreRecord.java` L101: `ACTIVE_NOT_IN_SYNC.allowedTransitions = ImmutableSet.of(ACTIVE_NOT_IN_SYNC, ...)`). This self-transition supports the periodic heartbeat in `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()` (L71-87), which re-writes `ACTIVE_NOT_IN_SYNC` to the ZK znode every `0.7 × ZK_SESSION_TIMEOUT` milliseconds. The write refreshes the znode's `mtime` without changing the state value. Without this self-transition, the heartbeat would throw `InvalidClusterRoleTransitionException`.

The heartbeat is essential for the anti-flapping mechanism (§3.6): while the heartbeat keeps refreshing `mtime`, the `ANIS → AIS` gate (`mtime + 1.1 × ZK_SESSION_TIMEOUT ≤ current_time`) is never satisfied. The gate only opens after the heartbeat stops, i.e., after the RS exits `STORE_AND_FORWARD` mode.

The TLA+ model includes this as a stuttering action `ANISHeartbeat(c)` in Iteration 8 that updates `zkMtime[c]` without changing `clusterState[c]`.

### A.6 Default Initial States (Updated)

The default initial states have been updated to `ACTIVE_IN_SYNC` (AIS) for the active cluster and `STANDBY` (S) for the standby cluster, per team confirmation from recent syncups. The previous defaults were `ACTIVE_NOT_IN_SYNC` (ANIS) and `DEGRADED_STANDBY` (DS).

With the new defaults, failover can be initiated immediately after HA group initialization since `AIS` is the start state for normal failover (`AIS → ATS`) and `STANDBY` is the state from which the standby can enter `STA`.

The TLA+ `Init` predicate uses these updated defaults from Iteration 1: `clusterState = [C1 ↦ AIS, C2 ↦ S]`.

### A.7 Failover Trigger Conditions

The design states that `STA → AIS` requires "all replication logs replayed" (`state-machines.md` §5, event c). The implementation decomposes this into three explicit conditions checked by `ReplicationLogDiscoveryReplay.shouldTriggerFailover()` (L500-533):

1. `failoverPending == true` — set when the local state changes to `STANDBY_TO_ACTIVE` (listener at L159-171).
2. The in-progress directory is empty — checked via `replicationLogTracker.getInProgressFiles().isEmpty()` (L508).
3. No new files exist between the next expected round and the current timestamp round — checked via `replicationLogTracker.getNewFiles()` (L522-523).

The third condition provides a time-window safety margin: even after round processing completes, the system waits to confirm that no new replication log files have appeared in the expected time window before declaring replay complete. All three conditions must be satisfied simultaneously before `triggerFailover()` calls `setHAGroupStatusToSync()` to transition from `STANDBY_TO_ACTIVE` to `ACTIVE_IN_SYNC`.

The TLA+ model encodes all three as explicit guards on the `TriggerFailover(c)` action in Iteration 11.

### A.8 Writer Fail-Stop in Store-and-Forward Mode

The design does not describe what happens when a write fails in store-and-forward mode. The implementation treats any `IOException` during a local HDFS write in `STORE_AND_FORWARD` mode as fatal: `StoreAndForwardModeImpl.onFailure()` (L116-123) calls `logGroup.abort()`, which triggers a region server abort. There is no further fallback — the RS terminates. This is deliberate: losing locally buffered mutations would violate the zero-RPO guarantee, so a fail-stop is safer than continuing with potentially lost data.

By contrast, `SyncModeImpl.onFailure()` (L61-77) gracefully degrades by transitioning to `STORE_AND_FORWARD` mode, and `SyncAndForwardModeImpl.onFailure()` (L66-82) also falls back to `STORE_AND_FORWARD`. Only the `STORE_AND_FORWARD` mode itself has no fallback.

The TLA+ model includes this as an `RSCrash(c, rs)` environment action in Iteration 12.

### A.9 Mutation Capture Timing

The design states that mutation capture occurs in pre-batch hooks "before any local processing" (`architecture.md` §Replication Log Writer). The implementation captures mutations in `IndexRegionObserver.postBatchMutateIndispensably()` (L2013-2068) — after the local WAL commit succeeds. This ensures only successfully committed mutations are replicated, avoiding phantom writes from failed local operations. This divergence does not affect protocol-level safety properties (mutual exclusion, no data loss) and is omitted from the TLA+ model.

### A.10 Anti-Flapping Timing Parameters

The design specifies a heartbeat interval of `N/2` (where `N = zookeeper.session.timeout × 1.1`) with a default ZK session timeout of 60 seconds, yielding a heartbeat of ~33 seconds and a wait gate of ~66 seconds (`state-machines.md` §6). The implementation uses different multipliers: the heartbeat interval is `0.7 × ZK_SESSION_TIMEOUT` (`StoreAndForwardModeImpl.java` L46: `HA_GROUP_STORE_UPDATE_MULTIPLIER = 0.7`) and the wait gate is `1.1 × ZK_SESSION_TIMEOUT` (`HAGroupStoreClient.java` L98: `ZK_SESSION_TIMEOUT_MULTIPLIER = 1.1`). The default ZK session timeout is also different: 90 seconds instead of 60 seconds. With the implementation defaults, the heartbeat fires every ~63 seconds and the wait gate requires ~99 seconds of silence.

The TLA+ model abstracts both variants behind a logical clock with a `WaitTimeForSync` threshold constant, introduced in Iteration 8. The specific multiplier values do not affect protocol safety — only the relationship between the heartbeat interval and the wait threshold matters.

### A.11 DEGRADED_STANDBY → STANDBY_TO_ACTIVE (Resolved)

The design shows a `DegradedStandby → StandbyToActive` transition as a forced failover path (represented by a dotted line in the state transition diagram, `state-machines.md` §3). The original implementation's `allowedTransitions` table did not include `DEGRADED_STANDBY → STANDBY_TO_ACTIVE`, which caused a failover stall on the ANIS path: the standby would react to peer `ANIS` by entering `DS`, and then could not transition to `STA` when failover proceeded to `ATS`.

The team confirmed this was identified during testing and is planned for fix in the implementation. `STANDBY_TO_ACTIVE` will be added to `DEGRADED_STANDBY.allowedTransitions`. The TLA+ model assumes this fix is in place: the transition table entry is `DS → {S, STA}`.

With this fix, the ANIS failover path completes successfully even when the standby is in `DEGRADED_STANDBY`: `(ANIS, DS) → (ANISTS, DS) → (ATS, DS) → (ATS, STA) → (ATS, AIS) → (S, AIS)`.

### A.12 ANISTS → ATS Subject to Anti-Flapping Gate (Intentional)

The `validateTransitionAndGetWaitTime()` (L1032-1036) applies the same `mtime + 1.1 × ZK_SESSION_TIMEOUT ≤ current_time` wait gate to the `ANISTS → ATS` transition as it does to `ANIS → AIS`:

```java
if (currentHAGroupState == ACTIVE_NOT_IN_SYNC
      && newHAGroupState == ACTIVE_IN_SYNC
    || (currentHAGroupState == ACTIVE_NOT_IN_SYNC_TO_STANDBY
      && newHAGroupState == ACTIVE_IN_SYNC_TO_STANDBY))
```

The team confirmed this is intentional: there should be no difference between handling `ANIS → AIS` and `ANISTS → ATS`. In both cases the wait is needed to ensure all region servers have consistent state and to prevent flapping due to ZK state propagation delay.

The failover time (client-visible downtime) is measured from when I/O stops — when the primary cluster goes down — not from when the admin issues the failover command. The anti-flapping wait on the `ANISTS → ATS` path does not contribute to client-visible downtime because I/O is already blocked before the admin initiates failover.

The TLA+ model includes this gate in Iteration 8.

### A.13 Forwarder-Driven Mode Transitions

The design describes a "background process" that "transfers all the logs" from the local OUT directory to the peer IN directory (`architecture.md` §HDFS Directory Layout). The implementation realizes this as `ReplicationLogDiscoveryForwarder` (`phoenix-core-server`), which does substantially more than copy files: it actively drives writer mode transitions and cluster-level state changes.

The forwarder subscribes to two HA group state events during `init()` (L89-130):
- `ACTIVE_NOT_IN_SYNC` (L98-108): when any RS enters `STORE_AND_FORWARD`, other RS still in `SYNC` are notified and transition to `SYNC_AND_FORWARD` via `checkAndSetModeAndNotify(SYNC, SYNC_AND_FORWARD)`.
- `ACTIVE_IN_SYNC` (L113-123): when the cluster returns to `AIS`, RS in `SYNC_AND_FORWARD` transition back to `SYNC` via `checkAndSetModeAndNotify(SYNC_AND_FORWARD, SYNC)`.

During file processing, the forwarder also triggers mode transitions directly:
- In `processFile()` (L133-152): after copying a file from OUT to IN, if the current mode is `STORE_AND_FORWARD` and the copy throughput exceeds a configurable threshold, the forwarder transitions the RS to `SYNC_AND_FORWARD`.
- In `processNoMoreRoundsLeft()` (L155-184): when no more rounds remain to forward and the in-progress directory is empty, the forwarder first ensures the RS is in `SYNC_AND_FORWARD` (transitioning from `STORE_AND_FORWARD` if needed), then calls `setHAGroupStatusToSync()` (L171) to attempt the cluster-level `ANIS → AIS` or `ANISTS → ATS` transition. This call is subject to the anti-flapping wait gate and may return a non-zero wait time, in which case the forwarder records a future retry timestamp.

The forwarder is the sole mechanism by which OUT directory draining triggers cluster-level state changes. Without it, the system would remain in `ANIS` (or `ANISTS`) indefinitely even after all RS return to `SYNC` mode. The TLA+ model captures the forwarder's behavior as part of the writer actions in `Writer.tla`, introduced across Iterations 5-7.

### A.14 Replay State Re-Degradation During Recovery

The design describes a linear progression through the replay states: `NOT_INITIALIZED → SYNC ↔ DEGRADED`, with `SYNCED_RECOVERY` as a transient rewind phase between `DEGRADED` and `SYNC`. In the implementation, the listeners that drive replay state transitions use unconditional `.set()` assignments (`ReplicationLogDiscoveryReplay.java` L141, L153), not `.compareAndSet()`. This means the `degradedListener` can overwrite `SYNCED_RECOVERY` with `DEGRADED` if the cluster re-degrades (transitions back to `DEGRADED_STANDBY`) before the `replay()` method processes the recovery CAS at L332-333.

The resulting interleaving is: `DEGRADED → SYNCED_RECOVERY → DEGRADED`, which bypasses the `SYNCED_RECOVERY → SYNC` CAS entirely. The `replay()` method handles this correctly — `compareAndSet(SYNCED_RECOVERY, SYNC)` fails when the state has been overwritten to `DEGRADED`, and the replay continues in `DEGRADED` mode without advancing `lastRoundInSync`. No data is lost.

The safety implication is that `SYNCED_RECOVERY` is not guaranteed to reach `SYNC` if re-degradation occurs. The TLA+ model must include the `SYNCED_RECOVERY → DEGRADED` transition to avoid falsely proving that recovery always completes in one step. In `Reader.tla`, the `ReplayStateDegrade` action must be enabled from both `SYNC` and `SYNCED_RECOVERY` states. The CAS in `ReplayStateRecover` must be modeled as a conditional: it succeeds only if the state is still `SYNCED_RECOVERY` at the linearization point.

---

## Appendix B: Invariant Summary Table

| # | Invariant | Type | Added | Description |
|---|-----------|------|-------|-------------|
| 1 | `TypeOK` | Safety | Iter 1 | All variables have valid types |
| 2 | `TransitionValid` | Action constraint | Iter 1 | Every state change follows `AllowedTransitions` |
| 3 | `MutualExclusion` | Safety | Iter 1 | No dual-active (via `RoleOf`) |
| 4 | `AbortSafety` | Safety | Iter 3 | Abort from correct side only |
| 5 | `NonAtomicFailoverSafe` | Safety | Iter 4 | Safety during non-atomic failover window |
| 6 | `WriterTypeOK` | Safety | Iter 5 | Writer modes have valid types |
| 7 | `WriterTransitionValid` | Action constraint | Iter 5 | Writer transitions follow allowed set |
| 8 | `AIStoATSPrecondition` | Safety | Iter 6 | OUT empty + all SYNC before AIS→ATS |
| 9 | `WriterClusterConsistency` | Safety | Iter 6 | Writer mode ↔ cluster state consistency |
| 10 | `NoAISWithSFWriter` | Safety | Iter 7 | AIS ⇒ all writers in SYNC |
| 11 | `AntiFlapGate` | Action constraint | Iter 8 | ANIS→AIS and ANISTS→ATS only after timeout elapsed |
| 12 | `ZKVersionMonotonic` | Safety | Iter 9 | ZK versions only increase |
| 13 | `FailoverTriggerCorrectness` | Safety | Iter 11 | STA→AIS requires 3 conditions |
| 14 | `NoDataLoss` | Safety | Iter 11 | STA→AIS only when replay complete |
| 15 | `FailoverCompletion` | Liveness | Iter 14 | Initiated failover eventually completes |
| 16 | `DegradationRecovery` | Liveness | Iter 14 | ANIS eventually recovers to AIS |
| 17 | `AbortCompletion` | Liveness | Iter 14 | Initiated abort eventually completes |
| 18 | `OFFLINESink` | Safety | Iter 18 | OFFLINE is terminal unless `UseForceQuirk` |
| 19 | `ForcedFailoverSafety` | Safety | Iter 19 | Mutual exclusion under forced failover |
| 20 | `ReplayRewindCorrectness` | Safety | Iter 20 | Rewind resets `lastRoundProcessed` correctly |
| 21 | `FailoverCompletionWithStuck` | Liveness | Iter 21 | With `UseForwarderStuckQuirk`, failover requires admin abort when forwarder stuck |
| 22 | `FailoverCompletionWithRetryFail` | Liveness | Iter 22 | With `UseRetryExhaustionQuirk`, failover/abort may require alternative recovery |

Additional invariants will be discovered and added during the modeling process.

---

## Appendix C: Document Cross-References

| This Plan Section | Reference Document | Reference Section |
|-------------------|-------------------|-------------------|
| §2 Architecture | `architecture.md` | §Components |
| §3.1 HA Group States | `state-machines.md` §1-3; `IMPL_CROSS_REFERENCE.md` §2.1-2.2 | State enums + transition table |
| §3.2 Writer States | `state-machines.md` §4; `IMPL_CROSS_REFERENCE.md` §2.3 | SM4 |
| §3.3 Replay States | `IMPL_CROSS_REFERENCE.md` §2.4, §8.2 | SM6 (impl-only) |
| §3.4 Combined States | `state-machines.md` §5; `TLA_INDEX.md` §3.4 | SM5 product machine |
| §3.5 Reactive Transitions | `IMPL_CROSS_REFERENCE.md` §2.5; `HAGroupStoreManager.java` L104-150 | FailoverManagementListener + peer/local resolvers |
| §3.6 Anti-Flapping | `state-machines.md` §6; `IMPL_CROSS_REFERENCE.md` §5; `HAGroupStoreClient.java` L1027-1046 | Protocol rules + timing + ANISTS gate |
| §4.1 Safety Properties | `TLA_INDEX.md` §7; `architecture.md` §Key Safety Arguments | Invariants |
| §4.2 Liveness Properties | `TLA_INDEX.md` §8 | Temporal properties; quirk-conditioned liveness |
| §4.3 Scenario 2 | Source: `ReplicationLogDiscoveryForwarder.java` L133-184; `PhoenixHAAdminTool.java` L509-605 | Forwarder stuck (no timeout); see `UseForwarderStuckQuirk` |
| §4.3 Scenario 3 | Source: `ReplicationShardDirectoryManager.java` L116-136; `StoreAndForwardModeImpl.java` L116-123 | RS crash: shared shards, unclosed leases |
| §4.3 Scenario 6 | Source: `HAGroupStoreManager.java` L653-704; `HAGroupStoreClient.java` L1104-1110 | Retry exhaustion; see `UseRetryExhaustionQuirk` |
| §4.3 Scenario 7 | `HAGroupStoreRecord.java` L117; `HAGroupStoreManager.java` L109 | DS→STA resolved (transition added) |
| §4.3 Scenario 8 | Source: `ClusterRoleRecord.java` L84; `ReplicationLogDiscoveryReplay.java` L309-317, L500-533 | HDFS fail during (ATS,STA); mutation blocking |
| Phase 9 (Iter 21-22) | Source-verified implementation liveness gaps | `UseForwarderStuckQuirk`, `UseRetryExhaustionQuirk` |
| §9 Source Code | `IMPL_CROSS_REFERENCE.md` §13; verified against source | File index with line numbers |
| Appendix A | `IMPL_CROSS_REFERENCE.md` §11; source-verified | Divergences (14 items; A.2, A.3, A.6, A.11, A.12 resolved/updated) |
