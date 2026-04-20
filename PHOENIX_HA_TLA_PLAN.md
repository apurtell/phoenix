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
| `antiFlapTimer` | Per-cluster in-memory | Countdown timer (Lamport CHARME 2005); gate opens at 0 |

### 2.3 Communication Channels

| Channel | Direction | Mechanism |
|---------|-----------|-----------|
| State updates | Cluster → own ZK | `setData().withVersion()` (optimistic locking) |
| Peer detection | Peer ZK → local cluster | Curator `PathChildrenCache` watchers (⚠ delivery is conditional) |
| Replication data | Active Writer → Standby HDFS `/IN` | Direct HDFS write (SYNC mode) |
| Buffered data | Active Writer → Local HDFS `/OUT` | Local HDFS write (S&F mode) |
| Forwarded data | Local `/OUT` → Peer `/IN` | `ReplicationLogDiscoveryForwarder` background copy; triggers `S&F→S&FWD` on throughput check and `ANIS→AIS`/`ANISTS→ATS` on drain complete |
| Replay | Standby Reader ← Standby HDFS `/IN` | Round-based log consumption |
| Admin commands | Operator → `PhoenixHAAdminTool` | CLI RPC to `HAGroupStoreManager` |

### 2.4 ZooKeeper as Coordination Substrate

The protocol operates on top of ZooKeeper. The TLA+ model treats the following ZK properties as core environment assumptions.

**ZK properties that the protocol depends on:**

| Property | Guarantee | Source |
|----------|-----------|--------|
| Linearizable writes | `setData().withVersion()` provides CAS semantics | ZK spec §Consistency Guarantees |
| Ordered delivery | Watcher events delivered in zxid order per session | `zookeeperProgrammers.md` L322-335 |
| Happens-before | Client sees watch notification before seeing new data | `zookeeperProgrammers.md` L342-350 |
| At-most-once watches | Standard watches fire at most once per registration | `zookeeperProgrammers.md` L355-368 |

**ZK properties that the protocol must tolerate:**

| Property | Behavior | Impact on Protocol |
|----------|----------|--------------------|
| Conditional watcher delivery | Notifications require active session + TCP connection; not delivered during disconnection; permanently lost on session expiry | Peer-reactive transitions may be delayed (disconnect) or permanently lost (session expiry) |
| Session expiry | Server evicts session after timeout; all watches lost; client must establish new session | All peer-reactive transitions disabled until session recovery |
| Non-atomic cross-ensemble writes | No atomic multi-op across two independent ZK quorums | Final failover step is two separate writes with an interleaving window |
| Bounded retries with permanent loss | Application-level retry exhaustion (2 retries in `FailoverManagementListener`) + at-most-once delivery = transition permanently lost | Peer-reactive transition fails silently; no polling fallback exists |
| Server-side silent failures | Unchecked exceptions in `WatchManager`, NIO/Netty serialization failures can silently drop notifications | Watcher delivery is best-effort, not guaranteed |

**Curator PathChildrenCache mitigation:** Phoenix uses Curator's `PathChildrenCache`, which provides eventual delivery on reconnection by re-querying ZK and generating synthetic `CHILD_UPDATED` events. This is the primary reliability backstop for transient disconnections. It does NOT protect against session expiry or permanent network partition.

**Modeling approach:** The TLA+ model includes ZK session state (`zkPeerConnected`, `zkPeerSessionAlive`) and retry exhaustion as first-class protocol variables. ZK failures are always in the model's state space. Safety is proven under arbitrary ZK failure combinations. Liveness is explicitly conditioned on ZK session survival and eventual reconnection.

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

**ZooKeeper Watcher Delivery Dependency.** All peer-reactive transitions depend on a ZK watcher notification chain for delivery. ZooKeeper does not formally guarantee unconditional watcher deliver. Delivery is conditional on: (1) the ZK session being alive, (2) a TCP connection being established or re-established, and (3) no server-side exception during notification. The TLA+ model must verify that safety holds regardless of notification delay or permanent loss, and that liveness is conditioned on ZK session survival and eventual reconnection.

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

**Reactive transition retry exhaustion**: The `FailoverManagementListener` (`HAGroupStoreManager.java` L653-704) retries each reactive transition exactly 2 times. After exhaustion, the transition is permanently lost — the method returns silently with only a log error. Events are not requeued (`notifySubscribers()` at `HAGroupStoreClient.java` L1141-1150 catches and swallows exceptions). Same-state ZK re-writes do not re-trigger because `handleStateChange()` (L1104-1110) suppresses notifications when `oldState.equals(newState)` and `lastKnownPeerState` is already advanced. There is no periodic reconciliation — the sync job (`syncZKToSystemTable()` L735-784) only syncs ZK to system table, not failover state. Recovery requires a different peer state change, a ZK session reconnect (which may cause `PathChildrenCache` to re-deliver via `CHILD_ADDED`), or manual intervention. The `isStateAlreadyUpdated()` check (L739-753) provides a safety net for concurrent success. Retry exhaustion is a direct consequence of ZK's at-most-once watcher delivery combined with the application's bounded retry policy — the TLA+ model includes it as a core part of the ZK watcher delivery model (see §2.4).

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

9. **Failover Trigger Correctness** (✅ Iteration 11): `STA → AIS` requires replay-completeness conditions. Modeled as an action constraint (not a state invariant) because it references primed variables.
   - `(clusterState[c] = STA ∧ clusterState'[c] = AIS) ⇒
      (failoverPending[c] ∧ inProgressDirEmpty[c] ∧
       replayState[c] = SYNC)`
   - The originally planned `lastRoundProcessed[c] ≥ lastRoundInSync[c]` guard was found tautological (no model transition violates it) and replaced with `replayState[c] = SYNC`, which is genuinely constraining. The team accepted adding `replicationReplayState == SYNC` to `shouldTriggerFailover()` in the implementation. A fourth guard, `hdfsAvailable[c] = TRUE`, is on `TriggerFailover` but excluded from the action constraint (environmental/liveness guard, not a replay-completeness condition).

10. **OFFLINE Sink State**: Once a cluster enters OFFLINE, it cannot transition out via the normal state machine.
    - `clusterState[c] = OFFLINE ⇒ clusterState'[c] = OFFLINE`
    (unless `UseForceQuirk = TRUE` to model manual ZK manipulation; not in current plan)

### 4.2 Liveness Properties

All liveness properties in this specification include explicit ZK session assumptions as formal preconditions. ZooKeeper watcher delivery is conditional (see §2.4): it requires an active session and an established TCP connection. These are not implicit assumptions — they are part of the formal liveness specification. If a ZK session expires permanently and is not recovered, the protocol stalls. This is a defined boundary of the protocol's operational envelope, not a bug.

**ZK Liveness Assumption (ZLA):** `∀ c ∈ Cluster: □◇ (zkPeerSessionAlive[c] ∧ zkPeerConnected[c])`  
This states that for every cluster, ZK sessions are eventually alive and connected. All liveness properties below are predicated on ZLA. Without ZLA, peer-reactive transitions are permanently disabled and the protocol requires manual intervention.

1. **Failover Completion**: If failover is initiated and not aborted, it eventually completes.
   - `ZLA ⇒ □(failoverInitiated ∧ ¬aborted ⇒ ◇ failoverComplete)` (under fairness)
   - Requires: ZK sessions alive and eventually connected (ZLA), HDFS available on standby (for replay and trigger checks), forwarder drains successfully (for ANISTS→ATS path), and reactive transitions eventually succeed (which follows from ZLA + weak fairness on `PeerReact*` actions). If forwarder is permanently stuck (`UseForwarderStuckQuirk = TRUE`), liveness requires admin abort.

2. **Degradation Recovery**: If HDFS connectivity recovers permanently, the cluster eventually returns to AIS.
   - `ZLA ⇒ □(clusterState[c] = ANIS ∧ ◇□ hdfsAvailable[peer] ⇒ ◇ clusterState[c] = AIS)`

3. **Abort Completion**: If abort is initiated, the system eventually returns to `(A*, S)`.
   - `ZLA ⇒ □(abortInitiated ⇒ ◇ (clusterState[active] ∈ {AIS, ANIS} ∧ clusterState[standby] = S))`
   - Requires: ZK sessions alive and eventually connected (ZLA), so that the active cluster detects peer AbTS and completes `ATS → AbTAIS → AIS`. Under retry exhaustion (always modeled), abort may require a subsequent ZK reconnect to re-deliver the missed event.

4. **Anti-Flapping Bound**: ANIS/AIS oscillation is bounded (modeled via the timing gate).

### 4.3 Properties Specific to Interesting Scenarios

1. **Non-atomic failover window**: New active writes AIS while old active is still ATS. Verify mutual exclusion during this window.
2. **ANIS failover with stuck forwarder**: Failover initiated from ANIS; OUT dir must drain before standby can begin replay. The forwarder has no timeout — `FileUtil.copy()` is blocking, retries are indefinite (every 10s), and the admin tool's 120s timeout is advisory only (does not abort). If the forwarder is stuck, ANISTS persists indefinitely. Recovery requires manual `abort-failover` or `--force`. The model verifies that `FailoverCompletion` requires either drain completion or admin abort. Source: `ReplicationLogDiscoveryForwarder.java` L133-184; `PhoenixHAAdminTool.java` L509-605.
3. **RS crash during S&F**: Writer in S&F mode encounters write error, RS aborts (`StoreAndForwardModeImpl.onFailure()` → `logGroup.abort()`). OUT directory shards are time-based (not per-RS), so surviving RS forwarders can drain a crashed RS's fully-written files from the shared HDFS shard directories. However, mid-write files have unclosed HDFS leases and the forwarder has no lease recovery in its read path (`RecoverLeaseFSUtils` is only used on the IN side). These files are orphaned until HDFS lease expiry (~10 min). RS restart resumes draining via `initializeLastRoundProcessed()`. Source: `ReplicationShardDirectoryManager.java` L116-136; `StoreAndForwardModeImpl.java` L116-123.
4. **Concurrent RS mode changes**: Multiple RS race to update ZK state (optimistic locking). Verify only valid transitions succeed.
5. **Replay rewind after degradation**: SYNCED_RECOVERY rewinds `lastRoundProcessed` to `lastRoundInSync`. Verify no data loss.
6. **ZK watcher delivery failure and retry exhaustion**: The entire peer-reactive transition mechanism depends on ZK watcher notification delivery. Three distinct failure modes can prevent a peer-reactive transition from completing: (a) ZK disconnection (transient), (b) ZK session expiry (permanent until recovery), (c) retry exhaustion (application-level, consequence of at-most-once delivery + bounded retries). The TLA+ model verifies safety under all three failure modes and verifies that liveness requires ZK session survival and eventual reconnection.
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
HDFS.tla                        (HDFS availability incident actions: HDFSDown, HDFSUp)
Clock.tla                       (countdown timer: Tick — Lamport CHARME 2005)
RS.tla                          (RegionServer lifecycle: RSCrash, RSRestart)
ZK.tla                          (ZooKeeper coordination: ZKPeerDisconnect, ZKPeerReconnect, ZKPeerSessionExpiry, ZKPeerSessionRecover, ZKLocalDisconnect, ZKLocalReconnect)
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
| `Writer.tla` | Iteration 5 | Writer mode actions (`WriterInit`, `WriterToStoreFwd`, etc.) |
| `HDFS.tla` | Iteration 6 | `HDFSDown`, `HDFSUp` |
| `Clock.tla` | Iteration 8 | `Tick` (countdown timer) |
| `RS.tla` | Iteration 9 | `RSRestart` (Iteration 9); `RSCrash`, `RSAbortOnLocalHDFSFailure` (Iteration 12); `rsAlive` eliminated — `writerMode = "DEAD"` is the sole death indicator |
| `ZK.tla` | Iteration 13 | `ZKPeerDisconnect`, `ZKPeerReconnect`, `ZKPeerSessionExpiry`, `ZKPeerSessionRecover`, `ZKLocalDisconnect`, `ZKLocalReconnect` |
| `Reader.tla` | Iteration 10 | Replay state machine actions |

### 5.2 Model Verification

TLC runs are executed locally. In the early phases, exhaustive model checking is used exclusively — it provides complete coverage and the state spaces are small enough for timely completion. Simulation mode will be introduced later once state spaces grow too large for exhaustive search within a reasonable time budget.

| Config | Spec | Mode | Symmetry | Model | Depth | Role | Time |
|--------|------|------|----------|-------|-------|------|------|
| `ConsistentFailover.cfg` | `SafetySpec` | Exhaustive BFS | Yes | 2c/2rs, WFS=2 | BFS (75) | Safety, every iteration | target ≤1 hr |
| `ConsistentFailover-sim.cfg` | `SafetySpec` | Simulation | No | 2c/9rs, WFS=5 | 10000 | Safety, deep random | 8 hr |
| `ConsistentFailover-sim-liveness-ac.cfg` | `SpecAC` | Simulation | No | 2c/2rs, WFS=2 | 10000 | `AbortCompletion`, random traces | 8 hr |
| `ConsistentFailover-sim-liveness-fc.cfg` | `SpecFC` | Simulation | No | 2c/2rs, WFS=2 | 10000 | `FailoverCompletion`, random traces | 8 hr |
| `ConsistentFailover-sim-liveness-dr.cfg` | `SpecDR` | Simulation | No | 2c/2rs, WFS=2 | 10000 | `DegradationRecovery`, random traces | 8 hr |

where `c` = clusters, `rs` = region servers per cluster, `WFS` = `WaitTimeForSync`.

**Symmetry reduction:** The primary exhaustive config (`ConsistentFailover.cfg`) uses `SYMMETRY` to exploit the interchangeability of region servers within a cluster, dramatically reducing the state space. Simulation configs omit symmetry because it provides no benefit for random trace sampling.

**Liveness checking approach:** Liveness properties are checked via simulation, one property at a time, each with a per-property fairness formula containing only the temporal clauses on its critical path. Exhaustive liveness checking is infeasible at this state-space scale: TLC's Buchi automaton construction and Tarjan SCC algorithm require the full product graph (behavior graph × automaton) in memory, which exceeds heap capacity with 100M+ distinct states. The full `Fairness` formula (43 clauses: 25 WF + 18 SF) causes exponential blowup in automaton construction even with simulation; per-property formulas reduce this to 5 instantiated clauses (`FairnessAC`), 13 (`FairnessFC`), or 25 (`FairnessDR` with |RS|=2), keeping the automaton tractable. Each per-property config uses its own `SpecXX = Init /\ [][Next]_vars /\ FairnessXX` and checks a single `PROPERTY`. The full `Fairness` and `Spec` remain in the specification for documentation and THEOREM declarations.

**Simulation config details:** The safety sim (`-sim.cfg`) uses a 9-RS model to exercise per-RS writer interleaving at production scale (40 action schemas × 9 RS create a high branching factor). `WaitTimeForSync=5` creates a richer anti-flapping window where HDFS failures, ZK disruptions, and RS crashes can interleave while the gate is closed. Depth 10000 allows traces covering ~100 complete failover cycles. The liveness sim configs (`-sim-liveness-*.cfg`) use 2 RS and depth 10000. All simulation configs use an 8-hour stopAfter budget.

SANY syntax checking is performed locally in the Cursor environment before running TLC (see §6 for the full procedure).

### 5.3 Abstraction Decisions

| Aspect | Modeling Decision | Rationale |
|--------|-------------------|-----------|
| Cluster HA state machine | **Concrete** | Core of the model; exact states and transitions from `HAGroupStoreRecord.java` |
| Combined product state machine | **Concrete** | The heart of the failover protocol; all (C1, C2) state pairs |
| Peer-reactive transitions | **Concrete** | `FailoverManagementListener` auto-transitions are critical for safety |
| Auto-completion transitions | **Concrete** | AbTS→S, AbTAIS→AIS, AbTANIS→ANIS are part of the protocol |
| Writer mode state machine | **Concrete** | SYNC/S&F/SYNC&FWD mode changes drive cluster state transitions |
| Replay state machine (SM6) | **Concrete** | Implementation-only but critical for NoDataLoss verification |
| Anti-flapping protocol | **Concrete** (abstract timing) | Modeled via Lamport countdown timer (CHARME 2005); no absolute clock or real-time |
| ZK session lifecycle | **Core Protocol** | ZK sessions can disconnect, expire, and recover. Session state (`zkPeerConnected`, `zkPeerSessionAlive`) is always part of the model. Peer-reactive transitions are guarded on session liveness. See §2.4. |
| ZK watcher delivery | **Core Protocol** | Conditional delivery modeled via TLC interleaving (arbitrary delay) plus three permanent failure modes always in the model: (1) retry exhaustion (2 retries, then lost), (2) session expiry (all watches lost), (3) disconnection (transient). No polling fallback exists. Curator PathChildrenCache provides eventual delivery on reconnection only. |
| ZK optimistic locking | **Core Protocol** | Version-based CAS (`setData().withVersion()`) modeled as non-deterministic choice among concurrent updaters; version numbers introduced in Iteration 9. |
| ZK cross-ensemble non-atomicity | **Core Protocol** | No atomic multi-op across two independent ZK quorums. The final failover step is two separate writes with an interleaving window. This is a ZK property, not an implementation defect. |
| HDFS availability | **Abstract** | Non-deterministic boolean per cluster |
| OUT/IN directory state | **Abstract** | Boolean predicates (empty/non-empty); no file-level modeling. Forwarder drain has no timeout (see `UseForwarderStuckQuirk`). Shards are time-based/shared across RS. |
| Replication log format | **Omitted** | Not relevant to protocol safety |
| Replication log content | **Omitted** | Mutations are abstract; only round/sync metadata matters |
| LMAX Disruptor | **Omitted** | Internal writer buffering; not relevant to protocol safety |
| HDFS lease recovery | **Abstract** | Forwarder (OUT side) has no lease recovery — only the reader (IN side) uses `RecoverLeaseFSUtils`. Mid-write files from crashed RS are orphaned until HDFS lease expiry (~10 min). Modeled as transient delay on `outDirEmpty` after RS crash. |
| Client-side connections | **Omitted** | Focus on server-side protocol; client role detection is a consequence |
| Metrics | **Omitted** | Observability, not correctness |
| Reader lag (DSFR) | **Deferred** (Iteration 8+) | Design sub-state collapsed in implementation; add if needed |
| Forced failover / `--force` recovery | **Deferred** | Operator escape hatch for stuck transitional states (ATS, ANISTS, OFFLINE); not in current plan |
| OFFLINE state | **Deferred** (Iteration 18) | Intentional sink state; OFFLINE entry modeled in Iteration 18 for AWOP/ANISWOP reachability; `--force` bypass not in current plan |
| RS crash/abort | **Concrete** (Phase 3) | Writer fail-stop in S&F is a protocol-relevant failure mode |
| Number of RS per cluster | **Parameterized** | 2 for exhaustive (with RS symmetry reduction), 3 for simulation (no symmetry); races between RS matter |
| Degraded standby sub-states | **Implementation** (single DS) | Follow implementation's collapsed `DEGRADED_STANDBY`; design's DSFW/DSFR/DS intentionally removed for simplicity |
| Non-atomic failover | **Concrete** | Critical modeling point: two separate ZK writes, not one atomic step |
| ANIS self-transition | **Concrete** | Heartbeat that resets `antiFlapTimer` to `StartAntiFlapWait`; essential for anti-flapping |
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
5. ZK coordination properties (§2.4) are documented as core protocol assumptions.
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

### Tool Locations

TLA+ tool jars are available in the Cursor extension directory:

```
~/.cursor/extensions/tlaplus.vscode-ide-2026.4.61936-universal/tools/tla2tools.jar
~/.cursor/extensions/tlaplus.vscode-ide-2026.4.61936-universal/tools/CommunityModules-deps.jar
```

### Execution Environment

All TLC model checking is performed locally on the Cursor host (macOS). Use `-workers auto` to take advantage of all available cores.

### Local: SANY Syntax Check

Use SANY for fast syntax checking during iterative development (Step 3 of the per-iteration workflow). SANY parses the spec and reports errors without running the model checker.

```bash
cd /Users/apurtell/src/phoenix
JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
$JAVA17 -cp tla2tools.jar tla2sany.SANY ConsistentFailover.tla
```

This completes in under a second and catches all parse errors before running TLC.

### Local: TLC Execution

All TLC runs (exhaustive, simulation, liveness) are executed locally. Output is captured to a log file for later analysis.

**Exhaustive check** (with symmetry reduction, per-iteration — run to completion):

```bash
cd /Users/apurtell/src/phoenix
JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
ITER="iter01"
mkdir -p results/$ITER

$JAVA17 -XX:+UseParallelGC -XX:MaxDirectMemorySize=8g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover.cfg \
  -workers auto -cleanup \
  2>&1 | tee results/$ITER/tlc-exhaustive.log
```

The `ConsistentFailover.cfg` includes a `SYMMETRY` declaration for RS permutations, which significantly reduces the state space for exhaustive BFS. The `OffHeapDiskFPSet` with 8 GB of direct buffers (`-XX:MaxDirectMemorySize=8g`) keeps fingerprints off-heap, allowing larger state spaces without exhausting JVM heap memory.

**Simulation** (9 RS, deep random traces, 8-hour run):

```bash
$JAVA17 -XX:+UseParallelGC -XX:MaxDirectMemorySize=8g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -Dtlc2.TLC.stopAfter=28800 \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover-sim.cfg \
  -simulate -depth 10000 -workers auto \
  2>&1 | tee results/$ITER/tlc-sim-8h.log
```

The simulation config (`ConsistentFailover-sim.cfg`) uses 2 clusters, 9 RS, and `WaitTimeForSync=5`. The `-depth 10000` flag allows traces deep enough for ~100 complete failover cycles with 9 RS (each cycle requires ~100 steps for per-RS writer init, degradation, recovery, anti-flap timer, cluster transitions, replay, and ZK disruptions). The `-Dtlc2.TLC.stopAfter=28800` flag limits the run to 8 hours. No `SYMMETRY` — it provides no benefit for random trace sampling. No state constraint — counters grow organically along each trace.

Adjust `-Dtlc2.TLC.stopAfter` for shorter feedback loops: 300 (5 min quick check), 900 (15 min validation), 3600 (1 hour standard).

**Liveness check** (no symmetry — incompatible with `SYMMETRY` in TLC):

```bash
$JAVA17 -XX:+UseParallelGC -XX:MaxDirectMemorySize=8g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar:CommunityModules-deps.jar \
  tlc2.TLC ConsistentFailover.tla -config ConsistentFailover-liveness.cfg \
  -workers auto -cleanup \
  2>&1 | tee results/$ITER/tlc-liveness.log
```

### Analyze Results

Check the log for the outcome:

```bash
# Clean run — look for "Model checking completed. No error has been found."
grep -E "(No error|Invariant .* is violated|PROPERTY .* is violated|Error:)" results/$ITER/tlc-*.log

# State space summary
grep -E "(distinct states|states generated|depth)" results/$ITER/tlc-*.log
```

If an invariant violation was found, the counterexample trace in the log (or in `MC*.out`) contains the exact sequence of states leading to the violation. See §10.3 for triage classification.

### Recommended TLC Durations

In the early phases, exhaustive model checking runs to completion (no `-Dtlc2.TLC.stopAfter` needed). The following simulation durations apply only once simulation mode is adopted for larger state spaces:

| Tier | Duration | `-Dtlc2.TLC.stopAfter=` | Use Case |
|------|----------|-------------------------|----------|
| Quick | 300s (5 min) | `300` | Fast feedback during development |
| Standard | 900s (15 min) | `900` | Validation after completing an iteration |
| Deep | 28800s (8 hr) | `28800` | Milestone verification |
| Overnight | 86400s (24 hr) | `86400` | High-confidence sweep for rare interleavings |

All simulation durations are wall-clock time. `-workers auto` will use all available cores on the local machine.

---

## 7. Iterative Development Plan

Each iteration introduces exactly one new concept, produces a spec that TLC can verify, and is small enough to review and debug in isolation. Iterations are grouped into phases for readability, but the unit of work is the individual iteration.

### Process: Handling design/implementation bugs found by TLC

When TLC finds an invariant violation that traces to a design or implementation bug (as opposed to a modeling error), the following process applies:

1. **Do not fix the model first.** The model's purpose is to faithfully represent the design/implementation. Silently "fixing" the model to pass invariants conflates modeling with design work and obscures the finding.

2. **Produce a bug report** as a standalone markdown file in the project root directory, named `PHOENIX_HA_BUG_<SHORT_DESCRIPTION>.md`. The report must include:
   - TLC counterexample trace (the exact state sequence)
   - Root cause analysis citing implementation source lines
   - Impact assessment (safety, correctness, liveness, operational)
   - Recommended fix with proposed code changes
   - Statement of how the TLA+ model will treat the fix (assumed-fixed, quirk flag, etc.)

3. **Update the model assuming the recommended fix.** The model represents the *intended* design, not the current buggy implementation. Each modeling change that assumes a fix must cite the bug report in a comment (e.g., "per recommended fix in `PHOENIX_HA_BUG_FOO.md`").

4. **Add an entry to Appendix A** (Design-vs-Implementation Divergences) documenting the divergence and its resolution.

5. **Reference the bug report** in the iteration's completion notes.

This process was established during Iteration 7 when TLC found the AbTAIS + HDFS failure gap (see `PHOENIX_HA_BUG_ABTAIS_HDFS_FAILURE.md`).

---

### Phase 1: Cluster State Foundation

#### ~~Iteration 1 — Cluster states and valid transitions~~ ✅ COMPLETE

Created `Types.tla` (14-state `HAGroupState` set, `ActiveStates`/`StandbyStates`/`TransitionalActiveStates` subsets, 22-pair `AllowedTransitions` table from `HAGroupStoreRecord.java` L99-123 including the `ANIS` self-transition, `ClusterRole` set, `RoleOf` operator, `Peer` helper), `ConsistentFailover.tla` (single `clusterState` variable, `Init` with one cluster `AIS` and the other `S`, `Transition(c)` action with a coordination guard preventing entry to `ACTIVE` role from non-`ACTIVE` when peer is `ACTIVE`, `TypeOK`/`MutualExclusion` invariants, `TransitionValid` action constraint, empty `Symmetry`), and `ConsistentFailover.cfg` (`Cluster = {c1, c2}`).

#### ~~Iteration 2 — Role mapping and Active-role mutual exclusion~~ ✅ COMPLETE

`ClusterRole` (6-value enum including `UNKNOWN`), `RoleOf` operator, and the `RoleOf`-based `MutualExclusion` invariant were pulled forward into Iteration 1. This iteration added `ActiveRoles == {"ACTIVE"}` role-level subset to `Types.tla`. The `ActiveToStandbyNotActive` static sanity invariant (asserts `RoleOf("ATS") \notin ActiveRoles /\ RoleOf("ANISTS") \notin ActiveRoles`) was initially added but later dropped as a constant-level tautology — `RoleOf` is defined purely over constants, so TLC correctly flags it as redundant. The safety argument it encodes (ATS/ANISTS map to ACTIVE_TO_STANDBY, not ACTIVE) is already exercised by `MutualExclusion` and `NonAtomicFailoverSafe` over reachable states.

#### ~~Iteration 3 — Peer-reactive transitions (FailoverManagementListener)~~ ✅ COMPLETE

Created `HAGroupStore.tla` (peer-reactive actions for ATS/ANIS/AbTS plus auto-complete actions AbTS→S, AbTAIS→AIS, AbTANIS→ANIS) and `Admin.tla` (AIS→ATS, STA→AbTS with appropriate guards). Refactored `ConsistentFailover.tla` to use actor-driven disjuncts via `INSTANCE` instead of non-deterministic `Transition(c)`; added `AbortSafety` invariant.

#### ~~Iteration 4 — Non-atomic failover: two-step final transition~~ ✅ COMPLETE

Decompose failover into local `StandbyBecomesActive(c)` (STA→AIS, non-deterministic placeholder; full reader guards deferred to Iteration 11) and peer-reactive `PeerReactToAIS(c)` (ATS→S, DS→S). Add `NonAtomicFailoverSafe` invariant: during the AIS/ATS window, `RoleOf(ATS) ∉ ActiveRoles`. TLC found a bug: admin can re-failover during the window producing irrecoverable (ATS,ATS); fix is a peer-state guard on `AdminStartFailover` (see Appendix A.15).

---

### Phase 2: Replication Writer and HDFS

#### ~~Iteration 5 — Writer mode state machine (per-RS)~~ ✅ COMPLETE

Created `Writer.tla` (7 per-RS actions for the 4-mode writer state machine from §3.2; all leave `clusterState` unchanged — coupling deferred to Iterations 6-7). Added `RS` constant and `WriterMode` to `Types.tla`. Wired into `ConsistentFailover.tla` (`writerMode` variable, `writer == INSTANCE Writer`, `WriterTypeOK`/`WriterTransitionValid`, `Symmetry == Permutations(RS)`). Added `UNCHANGED writerMode` to all `HAGroupStore.tla`/`Admin.tla` actions.

#### ~~Iteration 6 — HDFS directory predicates and writer-cluster coupling~~ ✅ COMPLETE

Created `HDFS.tla` (`HDFSDown`/`HDFSUp`; atomic RS degradation via `CanDegradeToStoreFwd`). Added `outDirEmpty`/`hdfsAvailable` variables, `AIStoATSPrecondition` action constraint. TLC: 3,337 states, depth 24, all pass.

#### ~~Iteration 7 — Writer triggers cluster state change~~ ✅ COMPLETE

Active-cluster guards on all writer actions, AIS→ANIS coupling on `HDFSDown`/`WriterInitToStoreFwd`, `ANISToAIS(c)` recovery (anti-flapping deferred to Iter 8), conditional `AutoComplete(AbTAIS)`. New invariants: `AISImpliesInSync`, `NoAISWithSFWriter`, `WriterClusterConsistency`. Found implementation bug: missing `AbTAIS→ANIS` transition (Appendix A.17); model assumes fix.

---

### Phase 3: Anti-Flapping and Timing

#### ~~Iteration 8 — Anti-flapping gate (countdown timer)~~ ✅ COMPLETE

Per-cluster `antiFlapTimer` countdown variable (Lamport, "Real Time is Really Simple", CHARME 2005 §2) with `WaitTimeForSync` constant and four named helpers in `Types.tla`. Created `Clock.tla` (`Tick`). `ANISHeartbeat(c)` resets timer while any RS in S&F; `ANISToAIS(c)` guarded by `AntiFlapGateOpen`, writer guard relaxed to `{"SYNC","SYNC_AND_FWD"}` with atomic SYNC_AND_FWD→SYNC. Timer initialized on every ANIS entry (`AutoComplete`, `WriterInitToStoreFwd`, `HDFSDown`). New `AntiFlapTimerTypeOK` invariant, `AntiFlapGate` action constraint.

#### ~~Iteration 9 — RS-level ZK CAS races (observable failure modeling)~~ ✅ COMPLETE

Decomposed atomic `HDFSDown` into per-RS degradation: `HDFSDown(c)` now only sets `hdfsAvailable[c] = FALSE`; `WriterToStoreFwd`/`WriterSyncFwdToStoreFwd` wired into `Next` as individual disjuncts with CAS writes; AIS→ANIS coupling moved to `WriterToStoreFwd`. Added `DEAD` to `WriterMode` with three CAS failure actions (→ `DEAD`) modeling RS abort on `BadVersionException`; `WriterToStoreFwdFail`/`WriterInitToStoreFwdFail` guard on `ActiveStates \ {"AIS"}`; `WriterSyncFwdToStoreFwdFail` omits AIS exclusion per `AISImpliesInSync`. Created `RS.tla` (`RSRestart`: `DEAD → INIT`). Updated `AllowedWriterTransitions` and `WriterClusterConsistency` for `DEAD`.

---

### Phase 4: Replication Reader and Replay

#### ~~Iteration 10 — Replay state machine~~ ✅ COMPLETE

Created `Reader.tla` (4 per-cluster replay actions: `ReplayAdvance`, `ReplayDetectDegraded`, `ReplayDetectRecovery`, `ReplayRewind`; standby-side guards on `StandbyStates`/`"S"`/`"DS"`; CAS semantics for `SYNCED_RECOVERY→SYNC` modeled via TLC interleaving with concurrent `ReplayDetectDegraded`). Added `ReplayStateSet` to `Types.tla`. Added 5 variables to `ConsistentFailover.tla` (`replayState`, `lastRoundInSync`, `lastRoundProcessed`, `failoverPending`, `inProgressDirEmpty`), `reader == INSTANCE Reader`, `ReplayTypeOK` invariant, `AllowedReplayTransitions`/`ReplayTransitionValid` action constraint, `ReplayCounterBound` state constraint (counters ≤ 3).

#### ~~Iteration 11 — Failover trigger and replay-cluster coupling~~ ✅ COMPLETE

`replayState == SYNC` guard is added to `shouldTriggerFailover()` (team-accepted fix). `PeerReactToATS` now also sets `failoverPending` (triggerFailoverListener L159-171), superseding `StandbyBecomesActive`; `AdminAbortFailover` clears it (abortFailoverListener L173-185). `TriggerFailover(c)` in `Reader.tla` gates STA→AIS on `failoverPending`, `inProgressDirEmpty`, `replayState = "SYNC"` (replaces the tautological `lastRoundProcessed >= lastRoundInSync`), and `hdfsAvailable` (implicit IOException dep, §4.3 Scenario 8). `ReplayBegin/FinishProcessing` toggle `inProgressDirEmpty`. `FailoverTriggerCorrectness` and `NoDataLoss` action constraints (with theorems) verify all four guards on every STA→AIS step.

---

### Phase 5: Crash and Recovery

#### ~~Iteration 12 — RS crash (writer fail-stop)~~ ✅ COMPLETE

Added `RSCrash(c, rs)` (any non-DEAD mode → DEAD, no mode guard) and `RSAbortOnLocalHDFSFailure(c, rs)` (STORE_AND_FWD → DEAD when own HDFS down; source: `StoreAndForwardModeImpl.onFailure()` L115-123) to `RS.tla`. Relaxed `HDFSDown(c)` guard in `HDFS.tla`: removed `clusterState[Peer(c)] ∈ ActiveStates` so any cluster's HDFS can fail, enabling `RSAbortOnLocalHDFSFailure` for S&F writers on the active cluster. Wired both into `Next`; added `<<"STORE_AND_FWD","DEAD">>` to `AllowedWriterTransitions`. Relaxed `AISImpliesInSync` (allow DEAD), `WriterClusterConsistency` (removed DEAD from degraded-mode check — RSCrash can fire on any cluster state), `AIStoATSPrecondition` and `AdminStartFailover` guard (allow DEAD RSes alongside SYNC). `rsAlive` variable eliminated — `writerMode = "DEAD"` is the sole death indicator; all three RS death paths (CAS failure, JVM crash, local HDFS abort) end identically in the implementation (`abort()` → `RuntimeException` → JVM exit → supervisor restart).

#### ~~Iteration 13 — ZK coordination model (session lifecycle, watcher delivery, retry exhaustion)~~ ✅ COMPLETE

Created `ZK.tla`. Added `zkPeerConnected`, `zkPeerSessionAlive`, `zkLocalConnected` (`[Cluster → BOOLEAN]`, init TRUE). Dropped per-RS `ZKDisconnectRS` — `isHealthy` is per-cluster; added `zkLocalConnected` for `isHealthy`-dependent paths. Guarded `PeerReact*` on `zkPeerConnected ∧ zkPeerSessionAlive`; guarded all ZK-write actions (`AutoComplete`, `ANISHeartbeat`, `ANISToAIS`, `TriggerFailover`, writer S&F/Sync writes and CAS failures) on `zkLocalConnected`. Added `ReactiveTransitionFail(c)` stuttering action — models listener retry exhaustion (L653-704); non-deterministic with `PeerReact*` success paths. `ZKSessionConsistency` (`¬zkPeerSessionAlive ⇒ ¬zkPeerConnected`) invariants.

---

### Phase 6: Liveness and Refinement

#### ~~Iteration 14 — ANISTS failover path~~ ✅ COMPLETE

Second disjunct in `AdminStartFailover` (ANIS→ANISTS). `ANISTSToATS(c)` action in `HAGroupStore.tla` with `outDirEmpty` and `AntiFlapGateOpen` guards, wired into `Next`. Writer guards expanded: `WriterStoreFwdToSyncFwd`, `WriterSyncFwdToSync`, `WriterSyncFwdToStoreFwd`, `WriterSyncFwdToStoreFwdFail` include `TransitionalActiveStates`. `WriterSyncFwdToSync` tightened: per-cluster `\A rs2 : writerMode[c][rs2] ∉ {"STORE_AND_FWD"}` guard and `hdfsAvailable[Peer(c)]` guard added. `ANISTStoATSPrecondition` action constraint. Writer lifecycle reset on ATS→S: `PeerReactToAIS` and `PeerReactToANIS` ATS→S disjuncts reset live writers to INIT and `outDirEmpty` to TRUE (DEAD preserved for `RSRestart`); models replication subsystem restart on standby entry. `WriterClusterConsistency` expanded to include ATS (S&FWD writers persist through ANISTS→ATS). `AllowedWriterTransitions` expanded with SYNC→INIT, S&F→INIT, S&FWD→INIT (lifecycle reset transitions).

#### ~~Iteration 15 — Fairness and liveness properties~~ ✅ COMPLETE

`Fairness` formula: 43 temporal clauses (25 WF + 18 SF) in four tiers. Tier 1 (WF): protocol-internal — `Tick`, `ANISHeartbeat`, replay machine, `WriterInit`, `WriterSyncToSyncFwd`. Tier 2 (WF): ZK recovery — `ZKPeerReconnect`, `ZKPeerSessionRecover`, `ZKLocalReconnect`. Tier 3 (SF): actions guarded on oscillating env vars — peer-reactive, local transitions, writer degradation/recovery, RS lifecycle, `HDFSUp`; grouped into 6 mutually-exclusive disjunctions under single `SF_vars` calls. Tier 4 (none): faults, operator actions, CAS failures. Liveness properties: `FailoverCompletion` (`{STA, AbTAIS, AbTANIS, AbTS} ~> {AIS, ANIS, S}`), `DegradationRecovery` (`(ANIS /\ hdfsAvailable[Peer]) ~> ~ANIS`), `AbortCompletion` (`{AbTS, AbTAIS, AbTANIS} ~> {AIS, ANIS, S}`). Listener effects folded into atomic HAGroupStore cluster state transitions (S-entry sets `replayState = SYNCED_RECOVERY`, DS-entry sets `DEGRADED`). Per-property fairness formulas contain only critical-path clauses: `FairnessAC` (5 instantiated), `FairnessFC` (15), `FairnessDR` (25 with |RS|=2). `FairnessFC` originally omitted `WF_vars(reader!ReplayRewind(c))`, causing a spurious liveness violation. Fixed by adding the missing WF clause.

---

### Phase 7: Post-Abort Recovery, Forced Failover, and Edge Cases

#### ~~Iteration 16 — Post-Abort ATS Reconciliation~~ ✅

Post-abort stuck ATS after partition (Appendix A.21). Reconciliation is folded into `ZKPeerReconnect`/`ZKPeerSessionRecover` in `ZK.tla`: when local = ATS and peer ∈ {S, DS} at reconnect, `clusterState[c]` is atomically set to AbTAIS → AIS via `AutoComplete`. Same listener-effect folding pattern as `recoveryListener`/`degradedListener`. Race-safe: reconnect requires `zkPeerConnected = FALSE`, so it cannot fire during the happy-path transient (ATS, S). No new variable, no new action in `Next`. `AbortSafety` relaxed to allow peer = DS; new `ATSReconcileSafety` invariant.

#### ~~Iteration 17 — Replay rewind verification~~ ✅

Added `ReplayRewindCorrectness` action constraint to `ConsistentFailover.tla`: every SYNCED_RECOVERY→SYNC transition (ReplayRewind CAS) equalizes replay counters (`lastRoundProcessed'[c] = lastRoundInSync'[c]`). Wired into all 5 cfg files. `Reader.tla` unchanged — existing `ReplayRewind(c)` already correctly models the counter reset and CAS semantics. Exhaustive TLC run confirms `ReplayRewindCorrectness` holds across all 95,613,696 distinct states, and `NoDataLoss` continues to hold — the rewind mechanism preserves zero RPO through the ANIS→AIS→failover sequence.

#### Iteration 18 — AWOP/ANISWOP peer-reactive modeling (OFFLINE peer detection)

The `ACTIVE_WITH_OFFLINE_PEER` (AWOP) and `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER` (ANISWOP) states exist in the implementation at three levels:

1. **Enum declaration:** Both are declared in `HAGroupStoreRecord.HAGroupState` (L51-65) and map to `ClusterRole.ACTIVE` via `getClusterRole()` (L73-97), meaning `isMutationBlocked()=false` — the active cluster continues serving mutations while its peer is OFFLINE.

2. **Transition table:** The following transitions are in `allowedTransitions` (`HAGroupStoreRecord.java` L99-123):
   - `AIS → AWOP` (L104: `ACTIVE_IN_SYNC.allowedTransitions` includes `ACTIVE_WITH_OFFLINE_PEER`)
   - `ANIS → ANISWOP` (L101: `ACTIVE_NOT_IN_SYNC.allowedTransitions` includes `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER`)
   - `AWOP → ANIS` (L118: `ACTIVE_WITH_OFFLINE_PEER.allowedTransitions = ImmutableSet.of(ACTIVE_NOT_IN_SYNC)`)
   - `ANISWOP → ANIS` (L122: `ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER.allowedTransitions = ImmutableSet.of(ACTIVE_NOT_IN_SYNC)`)

3. **No peer-reactive trigger in FailoverManagementListener:** The `createPeerStateTransitions()` method (`HAGroupStoreManager.java` L104-138) has peer state entries for ATS, AIS, ANIS, and AbTS — but **no entry for OFFLINE**. There is no automatic reactive transition that fires when the active cluster detects its peer has entered OFFLINE. The transitions `AIS → AWOP` and `ANIS → ANISWOP` are in the `allowedTransitions` table but are not triggered by any code path in the current implementation.

AWOP and ANISWOP are currently unreachable states in the implementation. The enum values and transition table entries exist as infrastructure for a future feature (detecting and reacting to peer OFFLINE), but no mechanism currently drives the transition. The `HAGroupStoreClient.setHAGroupStatusIfNeeded()` method (L325-394) would accept these transitions (they pass `isTransitionAllowed()`), but nothing calls it with AWOP or ANISWOP as the target state in response to peer OFFLINE detection.

**What to add:**

`HAGroupStore.tla`:
- `PeerReactToOFFLINE(c)` action: when `clusterState[Peer(c)] = "OFFLINE"`, the active cluster transitions to AWOP or ANISWOP depending on its current state. Two sub-cases:
  - `clusterState[c] = "AIS"` → `clusterState'[c] = "AWOP"` (peer went OFFLINE while active is in sync).
  - `clusterState[c] = "ANIS"` → `clusterState'[c] = "ANISWOP"` (peer went OFFLINE while active is not in sync).
  - Guard: `clusterState[c] \in {"AIS", "ANIS"}` — only fires from stable active states.
- `PeerRecoverFromOFFLINE(c)` action: when `clusterState[Peer(c)] \notin {"OFFLINE"}` (peer re-enters a non-OFFLINE state via manual recovery), the active cluster returns from AWOP/ANISWOP. Two sub-cases:
  - `clusterState[c] = "AWOP"` → `clusterState'[c] = "ANIS"` (per `AWOP.allowedTransitions = {ANIS}`; peer recovery is treated as a new peer entering sync — the active must first synchronize, so it enters ANIS not AIS).
  - `clusterState[c] = "ANISWOP"` → `clusterState'[c] = "ANIS"` (per `ANISWOP.allowedTransitions = {ANIS}`).
  - Resets `antiFlapTimer[c]` to `StartAntiFlapWait` on ANIS entry.
- Note: Both actions model the *intended* protocol behavior since no FailoverManagementListener entry currently implements peer OFFLINE detection. The TLA+ model documents this as a design-ahead verification — verifying that the AWOP/ANISWOP states preserve mutual exclusion and writer-cluster consistency if/when the implementation adds the trigger. This follows the plan's precedent for assumed fixes (A.11, A.17) where the model verifies the intended design rather than the current incomplete implementation.

`ConsistentFailover.tla`:
- Wire `PeerReactToOFFLINE(c)` and `PeerRecoverFromOFFLINE(c)` into `Next`.
- Verify `MutualExclusion` holds with AWOP/ANISWOP reachable: both states are in `ActiveStates` (map to `ACTIVE` role), so only one cluster can be in an active state including AWOP/ANISWOP. The peer is in OFFLINE (maps to `OFFLINE` role, not `ACTIVE`), so no dual-active.
- Verify `WriterClusterConsistency` holds: AWOP and ANISWOP are already in the allowed set (added in Iteration 7). AWOP with degraded writers should be impossible (AIS→AWOP requires AIS which implies all writers SYNC); ANISWOP with degraded writers is expected.
- Verify `AISImpliesInSync` is not violated: AWOP is not AIS, so the invariant is not directly affected.

AWOP and ANISWOP become reachable states for the first time. All existing invariants hold with these states in the reachable state space. AWOP/ANISWOP do not introduce dual-active because the peer is in OFFLINE. The transitions AWOP→ANIS and ANISWOP→ANIS correctly return the cluster to its degraded-active state when the peer recovers.

---

## 8. Mapping from Design/Code to TLA+ Actions

| Design Event / Code Path | TLA+ Action | Module | Iter | Status |
|--------------------------|-------------|--------|------|--------|
| `initiateFailoverOnActiveCluster()` | `AdminStartFailover(c)` | `Admin.tla` | 3 | ✅ |
| `setHAGroupStatusToAbortToStandby()` | `AdminAbortFailover(c)` | `Admin.tla` | 3 | ✅ |
| `FailoverManagementListener` peer ATS detected | `PeerReactToATS(c)` | `HAGroupStore.tla` | 3 | ✅ |
| `FailoverManagementListener` peer AIS detected (old active → S) | `PeerReactToAIS(c)` | `HAGroupStore.tla` | 4 | ✅ |
| `FailoverManagementListener` peer ANIS detected (standby → DS) | `PeerReactToANIS(c)` | `HAGroupStore.tla` | 3 | ✅ |
| `FailoverManagementListener` peer AbTS detected (active → AbTAIS) | `PeerReactToAbTS(c)` | `HAGroupStore.tla` | 3 | ✅ |
| Auto-completion: AbTS → S | `AutoComplete(c)` | `HAGroupStore.tla` | 3 | ✅ |
| Auto-completion: AbTAIS → AIS/ANIS | `AutoComplete(c)` | `HAGroupStore.tla` | 3/7 | ✅ |
| Auto-completion: AbTANIS → ANIS | `AutoComplete(c)` | `HAGroupStore.tla` | 3 | ✅ |
| `SyncModeImpl.onFailure()` (L61-77) → `setHAGroupStatusToStoreAndForward()` | `WriterToStoreFwd(c, rs)` | `Writer.tla` | 6-7 | ✅ |
| `SyncAndForwardModeImpl.onFailure()` (L66-82) → `STORE_AND_FORWARD` | `WriterSyncFwdToStoreFwd(c, rs)` | `Writer.tla` | 9 | ✅ |
| `ReplicationLogDiscoveryForwarder.processFile()` (L133-152) throughput check → `S&F→S&FWD` | `WriterStoreFwdToSyncFwd(c, rs)` | `Writer.tla` | 5 | ✅ |
| `ReplicationLogDiscoveryForwarder.processNoMoreRoundsLeft()` (L155-184) → `setHAGroupStatusToSync()` | `WriterSyncFwdToSync(c, rs)` | `Writer.tla` | 5 | ✅ |
| `ReplicationLogDiscoveryForwarder.init()` ANIS listener (L98-108) → `SYNC→S&FWD` on other RS | `WriterSyncToSyncFwd(c, rs)` | `Writer.tla` | 5 | ✅ |
| Normal startup: INIT → SYNC | `WriterInit(c, rs)` | `Writer.tla` | 5 | ✅ |
| Startup with peer unavailable: INIT → S&F (CAS success) | `WriterInitToStoreFwd(c, rs)` | `Writer.tla` | 7 | ✅ |
| Startup CAS failure: INIT → DEAD | `WriterInitToStoreFwdFail(c, rs)` | `Writer.tla` | 9 | ✅ |
| CAS failure: SYNC → DEAD | `WriterToStoreFwdFail(c, rs)` | `Writer.tla` | 9 | ✅ |
| CAS failure: SYNC_AND_FWD → DEAD | `WriterSyncFwdToStoreFwdFail(c, rs)` | `Writer.tla` | 9 | ✅ |
| `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()` (L71-87) | `ANISHeartbeat(c)` | `HAGroupStore.tla` | 8 | ✅ |
| `HAGroupStoreClient.validateTransitionAndGetWaitTime()` (L1027-1046) | Guard on `ANIS → AIS` and `ANISTS → ATS` | `HAGroupStore.tla` | 8 | ✅ |
| `HAGroupStoreManager.setHAGroupStatusToSync()` (L341-355) — ANIS→AIS path | `ANISToAIS(c)` | `HAGroupStore.tla` | 8 | ✅ |
| HDFS NameNode crash | `HDFSDown(c)` | `HDFS.tla` | 6 | ✅ |
| HDFS NameNode recovery | `HDFSUp(c)` | `HDFS.tla` | 6 | ✅ |
| RS restart (process supervisor) | `RSRestart(c, rs)` | `RS.tla` | 9 | ✅ |
| ~~STA → AIS (non-deterministic placeholder)~~ Superseded by `TriggerFailover` in Iteration 11 | ~~`StandbyBecomesActive(c)`~~ | `HAGroupStore.tla` | 4→11 | ✅ |
| Peer AIS detected (DS → S recovery) | `PeerReactToAIS(c)` (DS disjunct) | `HAGroupStore.tla` | 4 | ✅ |
| `HAGroupStoreManager.setHAGroupStatusToSync()` (L341-355) — dual target: `ANISTS→ATS` if current is ANISTS | `ANISTSToATS(c)` | `HAGroupStore.tla` | 14 | ✅ |
| `ReplicationLogDiscoveryReplay.shouldTriggerFailover()` + `triggerFailover()` | `TriggerFailover(c)` | `Reader.tla` | 11 | ✅ |
| In-progress dir non-empty during round processing | `ReplayBeginProcessing(c)` | `Reader.tla` | 11 | ✅ |
| In-progress dir empty after round processing | `ReplayFinishProcessing(c)` | `Reader.tla` | 11 | ✅ |
| `ReplicationLogDiscoveryReplay.replay()` | `ReplayAdvance(c)` | `Reader.tla` | 10 | ✅ |
| Replay SYNC → DEGRADED (peer ANIS detected) | `ReplayDetectDegraded(c)` | `Reader.tla` | 10 | ✅ |
| Replay DEGRADED → SYNCED_RECOVERY (peer AIS detected) | `ReplayDetectRecovery(c)` | `Reader.tla` | 10 | ✅ |
| SYNCED_RECOVERY rewind | `ReplayRewind(c)` | `Reader.tla` | 10 | ✅ |
| RS abort (fail-stop in S&F) | `RSCrash(c, rs)` | `RS.tla` | 12 | ✅ |
| Peer ZK connection lost (peerPathChildrenCache) | `ZKPeerDisconnect(c)` | `ZK.tla` | 13 | ✅ |
| Peer ZK connection re-established | `ZKPeerReconnect(c)` | `ZK.tla` | 13 | ✅ |
| Peer ZK session expiry (all watches lost) | `ZKPeerSessionExpiry(c)` | `ZK.tla` | 13 | ✅ |
| Peer ZK session re-established | `ZKPeerSessionRecover(c)` | `ZK.tla` | 13 | ✅ |
| Local ZK connection lost (pathChildrenCache; `isHealthy = false`) | `ZKLocalDisconnect(c)` | `ZK.tla` | 13 | ✅ |
| Local ZK connection re-established (`isHealthy = true`) | `ZKLocalReconnect(c)` | `ZK.tla` | 13 | ✅ |
| Post-abort ATS reconciliation on peer cache reconnect (Test 5 fix) | `ATSReconcileOnReconnect(c)` | `HAGroupStore.tla` | 16 | ⏳ |
| Peer cache rebuilt after ZK reconnect/session recovery | `peerCacheRebuilt[c]` variable | `ZK.tla` | 16 | ⏳ |
| Peer goes OFFLINE | `AdminGoOffline(c)` | `Admin.tla` | 18 | ⏳ |
| Active detects peer OFFLINE → AWOP/ANISWOP | `PeerReactToOFFLINE(c)` | `HAGroupStore.tla` | 18 | ⏳ |
| Peer recovers from OFFLINE → AWOP/ANISWOP → ANIS | `PeerRecoverFromOFFLINE(c)` | `HAGroupStore.tla` | 18 | ⏳ |
| `setData().withVersion()` (ZK optimistic locking) | CAS semantics in Writer/HAGroupStore actions | various | 9 | ✅ |
| Forwarder permanently stuck (no timeout on `FileUtil.copy()`) | `ForwarderStuck(c)` | `Writer.tla` | — | deferred |
| `FailoverManagementListener` retry exhaustion (2 retries, then lost) | `ReactiveTransitionFail(c)` | `HAGroupStore.tla` | 13 | ✅ |

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

1. **CODE ANALYSIS** — Before writing TLA+, analyze the relevant implementation code paths for this iteration's scope. Ground the model in the actual implementation behavior. At the end of each iteration, compare the model against the implementation to identify gaps where the code diverges from the correct protocol. These gaps are the findings.
2. **WRITE / EDIT** — Add or modify spec per the iteration's scope (see Section 7 for iteration descriptions). All editing is done locally in Cursor.
3. **SYNTAX CHECK** — Parse with SANY on the local machine. Fix all parse errors before running TLC.
   ```
   cd /Users/apurtell/src/phoenix
   JAVA17=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java
   $JAVA17 -cp tla2tools.jar tla2sany.SANY ConsistentFailover.tla
   ```
   This completes in under a second. Repeat steps 2–3 until clean.
4. **RUN TLC** — Run TLC locally. Follow the procedure in §6 "Local: TLC Execution". Output is captured to `results/$ITER/` via `tee`. In early phases, run exhaustive only (`ConsistentFailover.cfg`, 2c/2rs). Switch to simulation (`ConsistentFailover-sim.cfg`) once state spaces grow too large for exhaustive search to complete within ~1 hour.
5. **TRIAGE** — If TLC reports violations, examine the counterexample trace in the log file, classify per §10.3. Repeat from step 2 or 4 as needed.
6. **REGRESSION CHECK** — Re-verify all invariants and properties from prior iterations. A fix in iteration N must not break any invariant proven in iterations 1 through N-1. The primary and simulation configs provide this coverage automatically at every iteration.
7. **RECORD** — Document the TLC result, configuration, state count, and any findings. Include the log file path for traceability.
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

The team confirmed this was intentionally removed to make the state machine simpler. The single `DEGRADED_STANDBY` state is the permanent design, not a deferred implementation. For safety verification the single state is sufficient because mutual exclusion does not depend on degradation sub-types. The TLA+ model follows the implementation's collapsed model throughout. A future iteration may introduce an optional `UseDesignDegradedStates` quirk flag for exploratory analysis, but this is low priority.

### A.3 OFFLINE as Sink State (Intentional)

The design shows bidirectional transitions `S → OFFLINE` and `OFFLINE → S` (`state-machines.md` §3), allowing an operator to take a cluster offline and bring it back. The implementation makes `OFFLINE` a terminal sink state with no allowed outbound transitions (`HAGroupStoreRecord.java` L109: `OFFLINE.allowedTransitions = ImmutableSet.of()`). Recovery from `OFFLINE` requires `PhoenixHAAdminTool update --force`, which bypasses the transition table validation.

The team confirmed this is intentional, not an oversight. The admin decides when to place a cluster offline and when to bring it back via `--force`. The `--force` recovery procedure should be documented as a standard operational runbook step.

The TLA+ model uses the implementation's sink behavior. Iteration 18 models OFFLINE entry via `AdminGoOffline` for AWOP/ANISWOP reachability. The `--force` bypass (`AdminForceRecover`) is not in the current plan.

### A.4 Replay State Machine (Implementation-Only)

The design documents do not describe any replay state machine. The implementation introduces `ReplicationReplayState` (`NOT_INITIALIZED`, `SYNC`, `DEGRADED`, `SYNCED_RECOVERY`) in `ReplicationLogDiscoveryReplay.java` (L550-555) to manage the consistency point — the timestamp before which all mutations are guaranteed replicated — during `ANIS ↔ AIS` transitions of the active peer.

The critical behavior is during `SYNCED_RECOVERY`: when the active returns from `ANIS` to `AIS`, the standby's `replay()` method (L323-333) rewinds `lastRoundProcessed` back to `lastRoundInSync` using `getFirstRoundToProcess()` (which reads from `lastRoundInSync`, L389), then re-processes rounds from the last known good sync point. This ensures mutations received during the degraded period — which may include out-of-order or duplicated log files from the store-and-forward pipeline — are re-replayed against the authoritative sync boundary.

This state machine is the primary mechanism for enforcing the `NoDataLoss` invariant and is modeled concretely starting in Iteration 10. Without it, a failover triggered after a degraded period could miss mutations that arrived via the forwarding pipeline between `lastRoundInSync` and `lastRoundProcessed`.

The `SYNCED_RECOVERY` rewind re-processes rounds that were already applied during the degraded period. Idempotency is guaranteed by two cooperating mechanisms: (1) every mutation in the replication log carries its original commit timestamp, so replaying the same cell at the same timestamp is a storage-level no-op; and (2) Phoenix compaction (`CompactionScanner`) retains all cells and delete markers within the max lookback window, ensuring tombstones are not compacted away before all mutations within the rewind window have been applied in their partial order. The design states: "Phoenix compaction in this design must already ensure we do not compact away tombstones too soon." Phoenix compaction is implemented and active in production with a 72-hour max lookback window. The design proposes a dynamic integration where the replication pipeline would extend the max lookback via `CompactionScanner.overrideMaxLookback()` to account for replication delays; this integration is not yet implemented on the `PHOENIX-7562-feature-new` branch, but is not strictly necessary when the globally configured max lookback (72 hours) vastly exceeds the maximum expected rewind span (minutes to low hours).

### A.5 ANIS Self-Transition (Heartbeat)

The design does not document any self-transition for the `ACTIVE_NOT_IN_SYNC` state. The implementation allows `ANIS → ANIS` in the transition table (`HAGroupStoreRecord.java` L101: `ACTIVE_NOT_IN_SYNC.allowedTransitions = ImmutableSet.of(ACTIVE_NOT_IN_SYNC, ...)`). This self-transition supports the periodic heartbeat in `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()` (L71-87), which re-writes `ACTIVE_NOT_IN_SYNC` to the ZK znode every `0.7 × ZK_SESSION_TIMEOUT` milliseconds. The write refreshes the znode's `mtime` without changing the state value. Without this self-transition, the heartbeat would throw `InvalidClusterRoleTransitionException`.

The heartbeat is essential for the anti-flapping mechanism (§3.6): while the heartbeat keeps refreshing `mtime`, the `ANIS → AIS` gate (`mtime + 1.1 × ZK_SESSION_TIMEOUT ≤ current_time`) is never satisfied. The gate only opens after the heartbeat stops, i.e., after the RS exits `STORE_AND_FORWARD` mode.

The TLA+ model includes this as a stuttering action `ANISHeartbeat(c)` in Iteration 8 that resets `antiFlapTimer[c]` to `StartAntiFlapWait` without changing `clusterState[c]`.

### A.6 Default Initial States (Updated)

The default initial states have been updated to `ACTIVE_IN_SYNC` (AIS) for the active cluster and `STANDBY` (S) for the standby cluster, per team confirmation from recent syncups. The previous defaults were `ACTIVE_NOT_IN_SYNC` (ANIS) and `DEGRADED_STANDBY` (DS).

With the new defaults, failover can be initiated immediately after HA group initialization since `AIS` is the start state for normal failover (`AIS → ATS`) and `STANDBY` is the state from which the standby can enter `STA`.

The TLA+ `Init` predicate uses these updated defaults from Iteration 1: `clusterState = [C1 ↦ AIS, C2 ↦ S]`.

### A.7 Failover Trigger Conditions (Updated — Iteration 11)

The design states that `STA → AIS` requires "all replication logs replayed" (`state-machines.md` §5, event c). The implementation decomposes this into three explicit conditions checked by `ReplicationLogDiscoveryReplay.shouldTriggerFailover()` (L500-533):

1. `failoverPending == true` — set when the local state changes to `STANDBY_TO_ACTIVE` (listener at L159-171).
2. The in-progress directory is empty — checked via `replicationLogTracker.getInProgressFiles().isEmpty()` (L508).
3. No new files exist between the next expected round and the current timestamp round — checked via `replicationLogTracker.getNewFiles()` (L522-523).

The third condition provides a time-window safety margin: even after round processing completes, the system waits to confirm that no new replication log files have appeared in the expected time window before declaring replay complete. All three conditions must be satisfied simultaneously before `triggerFailover()` calls `setHAGroupStatusToSync()` to transition from `STANDBY_TO_ACTIVE` to `ACTIVE_IN_SYNC`.

Additionally, both `getInProgressFiles()` and `getNewFiles()` perform HDFS reads that throw `IOException` if HDFS is unavailable, implicitly blocking the failover trigger.


The TLA+ model encodes four guards on the `TriggerFailover(c)` action:

| TLA+ guard | Implementation source | Mechanism |
|---|---|---|
| `failoverPending[c]` | `shouldTriggerFailover()` L503 | Direct: `failoverPending.get()` |
| `inProgressDirEmpty[c]` | `shouldTriggerFailover()` L508 | `replicationLogTracker.getInProgressFiles().isEmpty()` |
| `replayState[c] = "SYNC"` | `shouldTriggerFailover()` (accepted fix) | Guard being added to implementation |
| `hdfsAvailable[c] = TRUE` | `shouldTriggerFailover()` L508, L523 | Implicit: HDFS reads throw IOException if unavailable |

### A.8 Writer Fail-Stop in Store-and-Forward Mode

The design does not describe what happens when a write fails in store-and-forward mode. The implementation treats any `IOException` during a local HDFS write in `STORE_AND_FORWARD` mode as fatal: `StoreAndForwardModeImpl.onFailure()` (L116-123) calls `logGroup.abort()`, which triggers a region server abort. There is no further fallback — the RS terminates. This is deliberate: losing locally buffered mutations would violate the zero-RPO guarantee, so a fail-stop is safer than continuing with potentially lost data.

By contrast, `SyncModeImpl.onFailure()` (L61-77) gracefully degrades by transitioning to `STORE_AND_FORWARD` mode, and `SyncAndForwardModeImpl.onFailure()` (L66-82) also falls back to `STORE_AND_FORWARD`. Only the `STORE_AND_FORWARD` mode itself has no fallback.

The TLA+ model includes this as an `RSCrash(c, rs)` environment action in Iteration 12.

### A.9 Mutation Capture Timing

The design states that mutation capture occurs in pre-batch hooks "before any local processing" (`architecture.md` §Replication Log Writer). The implementation captures mutations in `IndexRegionObserver.postBatchMutateIndispensably()` (L2013-2068) — after the local WAL commit succeeds. This ensures only successfully committed mutations are replicated, avoiding phantom writes from failed local operations. This divergence does not affect protocol-level safety properties (mutual exclusion, no data loss) and is omitted from the TLA+ model.

### A.10 Anti-Flapping Timing Parameters

The design specifies a heartbeat interval of `N/2` (where `N = zookeeper.session.timeout × 1.1`) with a default ZK session timeout of 60 seconds, yielding a heartbeat of ~33 seconds and a wait gate of ~66 seconds (`state-machines.md` §6). The implementation uses different multipliers: the heartbeat interval is `0.7 × ZK_SESSION_TIMEOUT` (`StoreAndForwardModeImpl.java` L46: `HA_GROUP_STORE_UPDATE_MULTIPLIER = 0.7`) and the wait gate is `1.1 × ZK_SESSION_TIMEOUT` (`HAGroupStoreClient.java` L98: `ZK_SESSION_TIMEOUT_MULTIPLIER = 1.1`). The default ZK session timeout is also different: 90 seconds instead of 60 seconds. With the implementation defaults, the heartbeat fires every ~63 seconds and the wait gate requires ~99 seconds of silence.

The TLA+ model abstracts both variants behind a Lamport countdown timer ("Real Time is Really Simple", CHARME 2005) with a `WaitTimeForSync` constant, introduced in Iteration 8. The timer counts down from `WaitTimeForSync` to 0, and the anti-flapping gate opens when the timer reaches 0. The specific multiplier values do not affect protocol safety — only the relationship between the heartbeat interval and the wait threshold matters.

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

### A.15 Missing Peer-State Guard on Failover Initiation (TLC Finding — Iteration 4)

TLC exhaustive model checking (Iteration 4) discovered that `initiateFailoverOnActiveCluster()` (`HAGroupStoreManager.java` L375-400) does not validate the peer cluster's state before initiating failover. This allows an admin to initiate a new failover on the newly-active cluster during the non-atomic window of a prior failover, producing an irrecoverable `(ATS, ATS)` deadlock where both clusters are in `ACTIVE_IN_SYNC_TO_STANDBY` with mutations blocked and no action enabled. The admin starts a second failover on c2 (which just became `AIS`) before c1's `FailoverManagementListener` reacts to the peer `AIS` and completes `ATS → S`. The method only checks `currentState == AIS || ANIS` and does not query the peer's state via `getHAGroupStoreRecordFromPeer()` (available on `HAGroupStoreClient` L421-427).

Add a peer-state precondition to `initiateFailoverOnActiveCluster()` requiring the peer to be in `STANDBY` or `DEGRADED_STANDBY` before allowing `AIS → ATS` or `ANIS → ANISTS`. The TLA+ model (`Admin.tla`) encodes this as `clusterState[Peer(c)] ∈ {"S", "DS"}` on the `AdminStartFailover` action. With the guard, TLC verifies deadlock freedom for the full reachable state space.

### A.16 ZK Watcher Delivery Is Not Formally Guaranteed (Source Code Analysis)

Source code analysis of ZooKeeper confirms that watcher notification delivery is conditional, not unconditional. ZooKeeper guarantees ordering (events delivered in zxid order), happens-before (client sees watch before new data), and at-most-once (standard watches fire at most once). It does NOT guarantee: delivery during disconnection, session survival, unconditional delivery (server-side exceptions can silently drop notifications), cross-client simultaneity, or bounded delivery time.

Every peer-reactive transition in the protocol depends on the ZK watcher notification chain:

| TLA+ Action | ZK Watcher Chain | If Notification Lost |
|---|---|---|
| `PeerReactToATS(c)` | peerPathChildrenCache → handleStateChange → FailoverManagementListener | Standby never enters STA; failover stalls indefinitely |
| `PeerReactToAIS(c)` | peerPathChildrenCache → handleStateChange → FailoverManagementListener | Old active stays in ATS forever; mutations blocked |
| `PeerReactToANIS(c)` | peerPathChildrenCache → handleStateChange → FailoverManagementListener | Standby stays in S when it should be DS; consistency point tracking incorrect |
| `PeerReactToAbTS(c)` | peerPathChildrenCache → handleStateChange → FailoverManagementListener | Active stays in ATS; abort does not propagate |
| `AutoComplete(c)` | pathChildrenCache (local) → handleStateChange → FailoverManagementListener | Cluster stays in AbTS/AbTAIS/AbTANIS indefinitely |
| `TriggerFailover(c)` | pathChildrenCache (local) → ReplicationLogDiscoveryReplay listeners | `failoverPending` never set; STA→AIS never fires |

**Failure modes (5 identified in ZK source)**:
1. **Session expiry**: All watches permanently lost. Client receives `SESSION_EXPIRED` on reconnect. Recovery requires new session + fresh watch registration. (Source: `zookeeperProgrammers.md` L398-413)
2. **Server-side exception in WatchManager**: `triggerWatch()` iterates watchers with no try/catch — an unchecked exception in one watcher's `process()` skips all remaining watchers. (Source: `WatchManager.java` L140-217)
3. **NIO silent serialization failure**: `NIOServerCnxn.sendResponse()` catches all `Exception` types and logs a warning. The notification is silently dropped. (Source: `NIOServerCnxn.java` L690-702)
4. **Netty write failure**: The `onSendBufferDoneListener` silently ignores `writeAndFlush` failures. (Source: `NettyServerCnxn.java` L222-227)
5. **Disconnection**: No notifications delivered while disconnected. On reconnect, `primeConnection()` re-registers watches with `lastZxid` and the server fires any missed changes. Curator's `PathChildrenCache` also re-queries and generates synthetic events. (Source: `ClientCnxn.java` L1006-1082)

The `syncZKToSystemTable()` periodic job (every 900s) syncs ZK → System Table for observability only. It does NOT re-evaluate peer state, re-fire subscriber notifications, or detect missed watcher events. There is no application-level mechanism to recover from a permanently missed watcher notification without a ZK session reconnect or manual intervention.

Phoenix uses Curator's `PathChildrenCache` rather than raw ZK watches. PathChildrenCache provides eventual delivery on reconnection by re-querying ZK and generating synthetic `CHILD_UPDATED` events for any changes detected during disconnection. This is the primary reliability backstop. However, PathChildrenCache does NOT protect against session expiry (the ZK session is dead; Curator must establish a new one) or permanent network partition (no reconnection possible).

ZK's conditional watcher delivery is modeled as a core protocol property. For safety, TLC's interleaving semantics already cover the case where peer-reactive actions are enabled but not taken — TLC explores all orderings, including paths where another action fires first. Safety (mutual exclusion, no data loss) holds regardless of watcher delivery delay because ATS/ANISTS map to `ACTIVE_TO_STANDBY` with `isMutationBlocked()=true`. For liveness, ZK session lifecycle (disconnect, expiry, recovery) and retry exhaustion are always part of the model (Iteration 13). Liveness properties are explicitly predicated on the ZK Liveness Assumption (ZLA, §4.2): ZK sessions are eventually alive and connected. Without ZLA, peer-reactive transitions are permanently disabled and the protocol stalls — this is a defined boundary of the protocol's operational envelope.

### A.17 Missing AbTAIS→ANIS Transition (TLC Finding — Iteration 7)

TLC model checking (Iteration 7) discovered that HDFS failure during the abort window produces a transient `ACTIVE_IN_SYNC` state with degraded writers. When the active cluster is in `AbTAIS` and HDFS goes down, writers degrade to `STORE_AND_FWD`. The S&F heartbeat attempts `AbTAIS→ANIS` via `setHAGroupStatusToStoreAndForward()`, but `isTransitionAllowed()` rejects this because `AbTAIS→ANIS` is not in the `allowedTransitions` table (`HAGroupStoreRecord.java` L115). When `AbTAIS` auto-completes to `AIS` (`createLocalStateTransitions()` L145), the cluster is briefly `ACTIVE_IN_SYNC` with `STORE_AND_FWD` writers — misrepresenting its sync status for up to one heartbeat period (~63s) until the S&F heartbeat fires `AIS→ANIS`.

Low severity. No safety violation (failover guards independently check writer state). Transient correctness issue: cluster state misrepresents sync status. Self-corrects within one heartbeat period.

Add `ACTIVE_NOT_IN_SYNC` to `ABORT_TO_ACTIVE_IN_SYNC.allowedTransitions`. Optionally, make the auto-completion resolver conditional on writer state. Full analysis in [`PHOENIX_HA_BUG_ABTAIS_HDFS_FAILURE.md`](PHOENIX_HA_BUG_ABTAIS_HDFS_FAILURE.md).

The model assumes the fix: `<<"AbTAIS","ANIS">>` is added to `AllowedTransitions` and `AutoComplete(AbTAIS)` is conditional (→`AIS` when clean, →`ANIS` when degraded). This is a sound abstraction that collapses the implementation's two-step self-correction path into a single atomic step.

### A.18 Folded PeerReactToATS + failoverPending Atomicity (Intentional — Iteration 11)

The TLA+ model folds the ZK watcher notification chain that sets `failoverPending` into the `PeerReactToATS` action. In the implementation, these are two separate events:

1. `FailoverManagementListener.onStateChange()` writes STA to the local ZK znode.
2. The local `pathChildrenCache` watcher fires.
3. `triggerFailoverListener` (L159-171) calls `failoverPending.set(true)`.

There is a real (tiny) window where `clusterState` is STA but `failoverPending` is still FALSE. The model closes this window by making `PeerReactToATS` atomically set both `clusterState[c] = "STA"` and `failoverPending[c] = TRUE`.

`TriggerFailover` requires `failoverPending[c] = TRUE` as a guard. If the model allowed the split, `TriggerFailover` simply could not fire during the gap — the same behavior as the implementation. The folding does not introduce any false safety proofs.

If `triggerFailoverListener` registration fails or the local watcher is lost, `failoverPending` is never set and failover is permanently stuck. The model cannot detect this because it assumes the listener fires deterministically. This liveness concern is covered by ZK session modeling in Iteration 13, where local pathChildrenCache failures are modeled explicitly.

### A.19 Reader Restart During STA (Deferred — Iteration 12)

`ReplicationLogDiscoveryReplay.initializeLastRoundProcessed()` (L269-273) sets `failoverPending` via `compareAndSet(false, true)` if the reader initializes while already in STA. This handles reader restart during an in-progress failover — the reader process may crash and restart while the cluster is in STA, and this CAS ensures `failoverPending` is set even without the original `triggerFailoverListener` firing.

Not modeled in Iteration 11 because `Init` does not produce the STA state — `Init` starts with one cluster in AIS and the other in S, so the reader-restart-during-STA scenario is not reachable. This will become relevant when reader/RS restart modeling is added (RS crash with `writerMode = "DEAD"` as the death indicator, per Iteration 12's elimination of `rsAlive`).

### A.20 LogRotationTask Failure Swallowing (Assumed Fix — Test 2C)

Real-cluster Test 2C (HDFS partition → ACTIVE_NOT_IN_SYNC) revealed that `ReplicationLog.java`'s `LogRotationTask` detects rotation failures (`Fail to recover lease`, `Error while syncing`) but swallows the exception instead of triggering `SyncModeImpl.onFailure()` → `STORE_AND_FORWARD`. The catch block contains a TODO comment: "More robust error handling goes here once the store-and-forward..." As a result, HDFS unavailability detected via the log rotation path does not trigger the expected `AIS → ANIS` transition, and the cluster incorrectly remains in `ACTIVE_IN_SYNC` while replication is silently degraded.

The fix: `LogRotationTask` must call `SyncModeImpl.onFailure()` (or an equivalent failure handler) when rotation fails due to HDFS errors, triggering the same `setHAGroupStatusToStoreAndForward()` path that `SyncModeImpl.onFailure()` L61-74 uses for write failures. This ensures that HDFS unavailability detected during log rotation produces the same writer degradation (SYNC → STORE_AND_FWD) and cluster state transition (AIS → ANIS) as HDFS unavailability detected during the write path.

The TLA+ model assumes this fix is applied. The existing `WriterToStoreFwd(c, rs)` action in `Writer.tla` already correctly models the post-fix behavior: any HDFS unavailability detected by an RS in SYNC mode triggers degradation to STORE_AND_FWD with the AIS → ANIS coupling. The LogRotationTask is one of several detection paths for HDFS unavailability (alongside the synchronous write path in `SyncModeImpl.onFailure()`); the spec abstracts over the detection mechanism. No spec change is required.

### A.21 Post-Abort ATS Convergence Failure (Test 5 — Iteration 16)

Real-cluster Test 5 (abort failover during inter-cluster partition) revealed that after an abort completes during a bidirectional partition, the active cluster (stuck in `ACTIVE_IN_SYNC_TO_STANDBY`) cannot self-recover when the partition is removed. The cluster remained stuck for 6+ minutes until manual `update --force` intervention.

**Sequence of events:**

1. Failover initiated: 5a → ATS, 5b → STA (via `PeerReactToATS`).
2. Bidirectional partition applied (both clusters' ZK caches disconnect).
3. Abort issued on 5b: STA → AbTS (`AdminAbortFailover`) → S (`AutoComplete`). This completes locally in 0s without peer coordination.
4. Partition removed. 5a's peer cache reconnects.
5. 5a's RS observes peer state change from `STANDBY_TO_ACTIVE` to `STANDBY` — the intermediate `ABORT_TO_STANDBY` was never observed because Curator `PathChildrenCache` rebuild on `CONNECTION_RECONNECTED` delivers only the current znode value, not missed intermediate transitions.
6. 5a remains in ATS indefinitely. No action fires because:
   - `PeerReactToAIS(c)` requires peer = AIS — not satisfied (peer is S).
   - `PeerReactToAbTS(c)` requires peer = AbTS — not satisfied (AbTS already auto-completed to S).
   - No other action handles (ATS, S).

**Root cause:** The `FailoverManagementListener`'s peer-reactive transitions are keyed on specific peer states (AIS, ANIS, ATS, AbTS). There is no entry for peer = STANDBY when local = ATS. In steady-state operation, (ATS, S) is a transient state — the peer will detect ATS via its own watcher and react. But when a partition hides the intermediate AbTS transition, the (ATS, S) state becomes permanent.

**Spec status:** The spec correctly models this as a stuck state. No action in `HAGroupStore.tla` handles (ATS, S). The `FailoverCompletion` liveness property explicitly excluded ATS for this reason (lines 537-543 of `ConsistentFailover.tla`). Test 5 confirms this is not just a theoretical boundary but an operationally encountered failure mode.

**Proposed fix (modeled in Iteration 16):** Add an `ATSReconcileOnReconnect(c)` action that fires when the peer cache is rebuilt after a reconnect event (`peerCacheRebuilt[c] = TRUE`), the local cluster is in ATS, and the peer is in S or DS. The action transitions the local cluster to AbTAIS, which auto-completes to AIS via the existing `AutoComplete` path. The `peerCacheRebuilt` guard prevents the reconciliation from racing with the normal failover happy path.

**Operational implication (until fix is implemented):** Operators must be prepared to use `PhoenixHAAdminTool update -s ACTIVE_IN_SYNC -av -F` to manually recover from stuck ATS after an abort during partition. Monitoring should alert on any HA group held in `ACTIVE_IN_SYNC_TO_STANDBY` for more than ~2× the expected failover duration.

---

## Appendix B: Invariant Summary Table

| # | Invariant | Type | Added | Description |
|---|-----------|------|-------|-------------|
| 1 | `TypeOK` | Safety | Iter 1 | All variables have valid types (consolidated: cluster state, writer mode, outDirEmpty, hdfsAvailable, antiFlapTimer, replay state/counters/flags, ZK variables) |
| 2 | `TransitionValid` | Action constraint | Iter 1 | Every state change follows `AllowedTransitions` |
| 3 | `MutualExclusion` | Safety | Iter 1 | No dual-active (via `RoleOf`) |
| 4 | `AbortSafety` | Safety | Iter 3 | Abort from correct side only |
| 5 | `NonAtomicFailoverSafe` | Safety | Iter 4 | Safety during non-atomic failover window |
| 6 | `WriterTransitionValid` | Action constraint | Iter 5 | Writer transitions follow allowed set |
| 7 | `AIStoATSPrecondition` | Action constraint | Iter 6 | OUT empty + all RS in SYNC or DEAD before AIS→ATS (relaxed Iter 12) |
| 8 | `WriterClusterConsistency` | Safety | Iter 7 | Degraded writer modes (S&F, SYNC&FWD) ⇒ cluster in `{ANIS, ANISTS, ANISWOP, AbTANIS}` (relaxed Iter 12: DEAD excluded from degraded check) |
| 9 | `NoAISWithSFWriter` | Safety | Iter 7 | AIS ⇒ no S&F writers |
| 10 | `AISImpliesInSync` | Safety | Iter 7 | AIS ⇒ outDirEmpty ∧ all RS in SYNC/INIT/DEAD (derived invariant, §4.1 property 3; relaxed Iter 12 to allow DEAD) |
| 11 | `AntiFlapGate` | Action constraint | Iter 8 | ANIS→AIS and ANISTS→ATS only after timeout elapsed |
| 12 | `ZKVersionMonotonic` | Safety | Iter 9 | ZK versions only increase |
| 13 | `FailoverTriggerCorrectness` | Action constraint | Iter 11 | STA→AIS requires `failoverPending ∧ inProgressDirEmpty ∧ replayState = SYNC` |
| 14 | `NoDataLoss` | Action constraint | Iter 11 | STA→AIS only when replay complete (equivalent to #13 in Iter 11) |
| 15 | `FailoverCompletion` | Liveness | Iter 15 | Initiated failover eventually completes |
| 16 | `DegradationRecovery` | Liveness | Iter 15 | ANIS eventually recovers to AIS |
| 17 | `AbortCompletion` | Liveness | Iter 15 | Initiated abort eventually completes |
| 18 | `ZKSessionConsistency` | Safety | Iter 13 | `zkPeerSessionAlive[c] = FALSE ⇒ zkPeerConnected[c] = FALSE` (session expiry implies disconnection) |
| 19 | `ATSReconcileSafety` | Safety | Iter 16 | `ATSReconcileOnReconnect` preserves `MutualExclusion` — reconciliation path through AbTAIS → AIS is safe |
| 20 | `ReplayRewindCorrectness` | Action constraint | Iter 17 | SYNCED_RECOVERY→SYNC equalizes replay counters (`lastRoundProcessed' = lastRoundInSync'`) |

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
| §4.2 Liveness Properties | `TLA_INDEX.md` §8 | Temporal properties |
| §4.3 Scenario 2 | Source: `ReplicationLogDiscoveryForwarder.java` L133-184; `PhoenixHAAdminTool.java` L509-605 | Forwarder stuck (no timeout); see `UseForwarderStuckQuirk` |
| §4.3 Scenario 3 | Source: `ReplicationShardDirectoryManager.java` L116-136; `StoreAndForwardModeImpl.java` L116-123 | RS crash: shared shards, unclosed leases |
| §4.3 Scenario 6 | Source: `HAGroupStoreManager.java` L653-704; `HAGroupStoreClient.java` L1104-1110 | Retry exhaustion; core ZK property modeled in Iteration 13 (see §2.4) |
| §4.3 Scenario 7 | `HAGroupStoreRecord.java` L117; `HAGroupStoreManager.java` L109 | DS→STA resolved (transition added) |
| §4.3 Scenario 8 | Source: `ClusterRoleRecord.java` L84; `ReplicationLogDiscoveryReplay.java` L309-317, L500-533 | HDFS fail during (ATS,STA); mutation blocking |
| Phase 7 (Iter 16-18) | Post-abort recovery, AWOP/ANISWOP, replay rewind | Remaining pending iterations |
| §9 Source Code | `IMPL_CROSS_REFERENCE.md` §13; verified against source | File index with line numbers |
| Appendix A | `IMPL_CROSS_REFERENCE.md` §11; source-verified | Divergences (21 items; A.2, A.3, A.6, A.11, A.12 resolved/updated; A.20, A.21 added from test findings) |
| Appendix A.16 | `ZOOKEEPER_WATCHER_DELIVERY_ANALYSIS.md`; ZK source code analysis | ZK watcher delivery is conditional, not guaranteed; 5 failure modes identified |
