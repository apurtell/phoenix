# Failover Protocol State Machines

Extracted and synthesized from `Consistent_Failover.docx` and `HAGroup.docx`.
This file is the primary reference for the TLA+ specification.

> **Implementation cross-reference**: See `IMPL_CROSS_REFERENCE.md` for a
> comprehensive mapping of these design state machines to the implementation
> on the `PHOENIX-7562-feature-new` branch. Key divergences from design are
> annotated with `⚠ IMPL` markers throughout this file.

---

## 1. Cluster HA Role State Machine (Consistent_Failover.docx, "State Transitions" section)

This is the top-level state machine for a single cluster's HA role.

### States

| State | Abbreviation | Description |
|-------|-------------|-------------|
| Active | A / AIS / ANIS | Cluster serves reads and writes; replicates to standby |
| Standby | S | Cluster does not serve clients; receives and applies replication logs |
| ActiveToStandby | ATS | Transitional: active cluster relinquishing role; rejects new writes |
| StandbyToActive | STA | Transitional: standby replaying outstanding logs before becoming active |
| DegradedStandby | DS | Standby cannot receive synchronous replication logs |
| AbortToActive | AbTA / AbTAIS / AbTANIS | Failover being canceled; old active reverting to Active |
| AbortToStandby | AbTS | Peer side of abort: standby reverting from STA |
| Offline | OFFLINE | Cluster is offline (operator-controlled) |

### Transitions (from Consistent_Failover.docx text)

```
Active → ActiveToStandby          [operator initiates failover]
Standby → StandbyToActive         [peer transitions to ATS]
Standby → DegradedStandby         [cannot keep up with sync replication]
ActiveToStandby → Standby         [all logs transferred, peer ready as Active]
ActiveToStandby → AbortToActive   [failover canceled]
StandbyToActive → Active          [⚠ IMPL: TWO separate ZK writes, NOT one atomic op — see §5 note]
DegradedStandby → Standby         [backlog drained, peer in Active state]
DegradedStandby → StandbyToActive [backlog drained, peer in ATS state, failover proceeding]
DegradedStandby ⇢ StandbyToActive [FORCED: operator override, dotted line in diagram]
AbortToActive → Active            [old Active resumes normal operation]
```

---

## 2. Active Cluster State Machine (HAGroup.docx, "State Transition Diagram for Active Cluster")

The Active cluster has sub-states that distinguish sync vs store-and-forward replication.

### States

| State | Abbreviation | Description |
|-------|-------------|-------------|
| Active In Sync | AIS | Active; all region servers replicating synchronously |
| Active Not In Sync | ANIS | Active; at least one RS in store-and-forward mode |
| Active To Standby | ATS | Initiated failover; was in AIS (OUT dir empty) |
| Active Not In Sync To Standby | ANISTS | Initiated failover; was in ANIS (OUT dir not yet empty) |
| Standby | S | Final standby state after failover completes |
| Abort To Active In Sync | AbTAIS | Abort from ATS, reverting to AIS |
| Abort To Active Not In Sync | AbTANIS | Abort from ATS/ANISTS, reverting to ANIS |
| Active With Offline Peer | AWOP | Active but peer is OFFLINE |
| Active Not In Sync With Offline Peer | ANISWOP | ANIS but peer is OFFLINE |

### Transitions

```
AIS → ANIS                [W changes to Store & Forward mode]
ANIS → AIS                [OUT directory empty AND ZK_Session_Timeout elapsed]
ANIS → ANIS               [⚠ IMPL: self-transition for S&F heartbeat, refreshes ZK mtime]
AIS → ATS                 [/Operator initiates failover from AIS]
ANIS → ANISTS             [/Operator initiates failover from ANIS]
ANISTS → ATS              [OUT directory becomes empty]
ATS → S                   [Peer transitions from STA to AIS/ANIS]
ATS → AbTAIS              [/Operator aborts failover]
ATS → AbTANIS             [/Operator aborts failover when was ANIS]
AbTAIS → AIS              [Detect transition to AbTAIS complete]
AbTANIS → ANIS            [Detect transition to AbTANIS complete]
AIS → AWOP                [Peer changes to OFFLINE mode]
ANIS → ANISWOP            [Peer changes to OFFLINE mode]
AWOP → AIS                [Peer not in OFFLINE mode]
ANISWOP → ANIS            [Peer not in OFFLINE mode]
```

> **⚠ IMPL: ANIS self-transition**: The implementation allows `ANIS → ANIS`
> as a self-transition to support the periodic heartbeat in
> `StoreAndForwardModeImpl`. This refreshes the ZK znode `mtime` without
> changing state, which is essential for the anti-flapping mechanism. The
> heartbeat fires at `0.7 × ZK_SESSION_TIMEOUT`, keeping `mtime` fresh and
> preventing premature `ANIS → AIS` transitions.
> See `IMPL_CROSS_REFERENCE.md` §11.4.

### Key Invariant: AIS → ATS transition

When transitioning from AIS to ATS:
- OUT directory MUST be empty
- All region servers MUST be in sync mode
- No new mutations are accepted (writes blocked)

---

## 3. Standby Cluster State Machine (HAGroup.docx, "State Transition Diagram for Standby Cluster")

### States

| State | Abbreviation | Description |
|-------|-------------|-------------|
| Standby | S | Normal standby; receiving and replaying replication logs |
| Standby To Active | STA | Preparing to become active; replaying outstanding logs |
| Active In Sync | AIS | Fully active and in sync (after failover complete) |
| Abort To Standby | AbTS | Failover being aborted; reverting to Standby |
| Degraded Standby | DS | Composite of DSFW, DSFR, or both |
| Degraded Standby For Writer | DSFW | Peer writer cannot replicate synchronously |
| Degraded Standby For Reader | DSFR | Local reader has lag exceeding threshold |
| Offline | OFFLINE | Cluster taken offline by operator |

### Transitions

```
S → STA                   [Detect peer transition to ATS]
S → DS (DSFW)             [Detect peer transition to ANIS]
STA → AIS                 [Replay complete for replication logs]
STA → AbTS                [/Operator aborts failover]
AbTS → S                  [Abort complete]
DS → S                    [Detect peer transition to AIS (back in sync)]
S → OFFLINE               [/Operator changes state to OFFLINE]
OFFLINE → S               [/Operator changes state to STANDBY]
```

### Degraded Standby Sub-States

The Degraded Standby state has sub-states based on which component is degraded:

```
S → DSFW                  [Detect peer ANIS: peer writer in store-and-forward]
S → DSFR                  [Local reader lag exceeds threshold]
DSFW → DS                 [Reader also becomes degraded (both degraded)]
DSFR → DS                 [Writer also becomes degraded (both degraded)]
DSFW → S                  [Detect peer back to AIS]
DSFR → S                  [Reader lag now below threshold]
DS → DSFW                 [Reader recovers]
DS → DSFR                 [Writer recovers / peer back to AIS]
```

> **⚠ IMPL: Collapsed sub-states**: The implementation uses a single
> `DEGRADED_STANDBY` state instead of the three sub-states (DSFW, DSFR, DS)
> defined here. The reader and writer degradation status is not distinguished
> at the ZK/HA-group-record level. See `IMPL_CROSS_REFERENCE.md` §11.1.

> **⚠ IMPL: OFFLINE is a sink state**: The design shows `S → OFFLINE` and
> `OFFLINE → S` transitions. In the implementation, `OFFLINE` has NO allowed
> outbound transitions. Recovery from OFFLINE requires manual ZK manipulation
> via `update --force`. See `IMPL_CROSS_REFERENCE.md` §11.2.

---

## 4. Replication Log Writer State Machine (Consistent_Failover.docx, "State Tracking")

Per-region-server state machine for the replication log writer component.

### States

| State | Abbreviation | Description |
|-------|-------------|-------------|
| Synchronous Replication | SYNC | Writing directly to standby cluster's HDFS |
| Store & Forward | S&F | Writing locally; standby HDFS unavailable |
| Sync & Forward | SYNC&FWD | Draining local queue while also writing synchronously |

### Transitions

```
SYNC → S&F                [Standby HDFS unavailable or degraded]
SYNC → SYNC&FWD           [⚠ IMPL: forwarder started while still in sync]
S&F → SYNC&FWD            [Recovery detected; standby HDFS available again]
SYNC&FWD → SYNC           [All stored logs forwarded; local queue empty]
SYNC&FWD → S&F            [Degraded again during drain]
```

> **⚠ IMPL: INIT state and additional transition**: The implementation adds an
> `INIT` state (pre-initialization) with transitions `INIT → SYNC` and
> `INIT → S&F`. It also adds `SYNC → SYNC&FWD` (not in design) for cases
> where the forwarder is started while still in sync mode. On failure in S&F
> mode, the RS **aborts** (fail-stop, no further fallback).
> See `IMPL_CROSS_REFERENCE.md` §2.3 and §7.4.

---

## 5. Combined State Transition Diagram (HAGroup.docx, "For both cluster states")

This is the product state machine showing (Cluster1_State, Cluster2_State) pairs.
Notation: `(C1_State, C2_State)` with actor and action/event labels.

### Actors

| Actor | Description |
|-------|-------------|
| A | Admin (operator) |
| R | ReplicationLogReader |
| W | ReplicationLogWriter |
| M1 | HAGroupStoreManager for Cluster1 |
| M2 | HAGroupStoreManager for Cluster2 |

### Actions and Events

| ID | Type | Description |
|----|------|-------------|
| ac1 | Action | Start failover |
| ac2 | Action | Admin decides to abort failover |
| b | Event | Detect transition from AIS to ATS state for peer cluster |
| c | Event | Logs completely replayed and ready to transition |
| d | Event | Detect transition from STA to AIS/ANIS state for peer cluster |
| e | Action | Operator aborts failover |
| f | Event | Detect transition from ATS to AbTA / change local state to AIS/ANIS based on local state of W |
| g | Event | Detect transition from ATS to AbTA state for peer cluster |
| h | Event | Local state is changed to AbTS |
| i | Event | W changes to Store and Forward Mode |
| j | Event | OUT directory empty (every W is in sync mode) |
| k | Event | Detect transition from AIS to ANIS for peer cluster |
| l | Event | Detect transition from ANIS to AIS for peer cluster |
| m | Event | R has lag exceeding threshold |
| n | Event | R lag is now below threshold |

### Combined State Transitions (latest version from Appendix)

```
(AIS, S)     --[W, i]-->      (ANIS, S)
(ANIS, S)    --[M2, k]-->     (ANIS, DSFW)
(AIS, S)     --[R, m]-->      (AIS, DSFR)
(ANIS, S)    --[W, i]-->      (ANIS, DSFR)     [note: if reader also degraded]
(ANIS, DSFW) --[M2, k]-->     (ANIS, DS)
(AIS, DSFR)  --[R, m]-->      (AIS, DS)         [note: if writer also degraded]

(ANIS, S)    --[W, j]-->      (AIS, S)          [note: requires ZK_Session_Timeout]
(ANIS, DSFW) --[W, j]-->      (AIS, DSFW)
(ANIS, DS)   --[W, j]-->      (AIS, DS)
(AIS, DSFR)  --[R, n]-->      (AIS, S)
(AIS, DS)    --[R, n]-->      (AIS, DSFW)
(ANIS, DSFR) --[R, n]-->      (ANIS, S)
(ANIS, DS)   --[R, n]-->      (ANIS, DSFW)
(AIS, DSFW)  --[M2, l]-->     (AIS, S)
(AIS, DS)    --[M2, l]-->     (AIS, DSFR)
(ANIS, DSFW) --[M2, l]-->     ... [not applicable: ANIS means peer stays DSFW]

(AIS, S)     --[A, ac1]-->    (ATS, S)          [start failover]
(ANIS, S)    --[A, ac1]-->    (ANISTS, S)       [start failover from ANIS]
(ANISTS, S)  --[W, j]-->      (ATS, S)          [OUT dir now empty]

(ATS, S)     --[M2, b]-->     (ATS, STA)        [standby detects failover]
(ATS, STA)   --[R, c]-->      (ATS, A*)         [replay complete]
(ATS, A*)    --[M1, d]-->     (S, AIS)          [⚠ IMPL: TWO separate ZK writes, NOT atomic]

(ATS, S)     --[A, ac2]-->    (AbTA, S)         [abort failover before STA]
(ATS, STA)   --[A, ac2]-->    (AbTA, STA)       [abort failover during STA]
(AbTA, STA)  --[M2, g]-->     (AbTA, AbTS)      [peer detects abort]
(AbTA, S)    --[M2, h]-->     (A*, S)           [abort complete, no STA was started]
(AbTA, AbTS) --[M2, h]-->     (A*, AbTS)        [intermediate]
(A*, AbTS)   --[W, f]-->      (A*, S)           [abort fully complete]
(A*, STA)    --[W, f]-->      (A*, S)           [abort fully complete]
```

### Key Safety Properties

1. **Mutual Exclusion**: Two clusters can never both be in Active (AIS/ANIS) at the same time.
2. **No Data Loss (Zero RPO)**: The standby must replay all replication logs before becoming Active.
   - STA → AIS requires "logs completely replayed" (event c).
3. ~~**Atomicity**: The final failover step `(ATS, A*) → (S, AIS)` must be an atomic ZooKeeper multi-operation.~~
   **⚠ IMPL: Non-atomic in implementation.** The final failover is two separate
   ZK writes: (1) new active writes `ACTIVE_IN_SYNC` to its own ZK, then
   (2) old active's `FailoverManagementListener` reactively writes `STANDBY`
   to its own ZK. Safety relies on the old active being in `ACTIVE_IN_SYNC_TO_STANDBY`
   (role `ACTIVE_TO_STANDBY`), which is NOT an Active role — clients do not
   send mutations to it. See `IMPL_CROSS_REFERENCE.md` §11.3.
4. **Abort Safety**: Abort is always initiated from the STA (standby-to-active) side to prevent two clusters from simultaneously becoming Active.
5. **⚠ IMPL: Failover Trigger Correctness** (implementation-specific): `STA → AIS`
   requires three conditions: (a) `failoverPending = true`, (b) in-progress
   directory is empty, (c) no new files between next expected round and current
   time. See `IMPL_CROSS_REFERENCE.md` §8.3.

### Key Liveness Properties

1. **Failover Completion**: If failover is initiated (ac1) and not aborted, it eventually completes: `(AIS,S) → ... → (S,AIS)`.
2. **Degradation Recovery**: If sync replication recovers, the state eventually returns to `(AIS, S)`.
3. **Abort Completion**: If abort is initiated (ac2), the system eventually returns to `(A*, S)`.

> **⚠ IMPL: Reactive transitions**: Transitions `(ATS,S) → (ATS,STA)`,
> `(ATS,AIS) → (S,AIS)`, and abort propagation are implemented as **automated
> reactive transitions** via `FailoverManagementListener` (HAGroupStoreManager.java:
> 633-706). This listener subscribes to ZK watcher events and drives the state
> machine forward with up to 2 retries on concurrent update conflicts.
> See `IMPL_CROSS_REFERENCE.md` §2.5.

> **⚠ IMPL: Default initial states**: Active defaults to `ACTIVE_NOT_IN_SYNC`
> (ANIS, not AIS). Standby defaults to `DEGRADED_STANDBY` (not STANDBY). This
> ensures failover is not attempted before the first successful sync replication.
> The initial combined state is `(ANIS, DEGRADED_STANDBY)`, not `(AIS, S)`.
> See `IMPL_CROSS_REFERENCE.md` §11.6.

> **⚠ IMPL: Replay State Machine (SM6)**: The implementation introduces a
> separate replay state machine (`ReplicationReplayState`: `NOT_INITIALIZED`,
> `SYNC`, `DEGRADED`, `SYNCED_RECOVERY`) not present in the design docs. It
> manages consistency point tracking during ANIS↔AIS transitions. The
> `SYNCED_RECOVERY` state rewinds `lastRoundProcessed` to `lastRoundInSync`
> to ensure all mutations from degraded periods are re-replayed.
> See `IMPL_CROSS_REFERENCE.md` §2.4 and §8.2.

---

## 6. Anti-Flapping Protocol (HAGroup.docx, "Avoiding flapping between Store&Forward and Sync mode")

To prevent rapid oscillation between ANIS and AIS when different region servers have different views:

### Protocol Rules

1. A region server periodically (every N/2 seconds, where N = `zookeeper.session.timeout * 1.1`) confirms its current mode by writing to ZK.
2. Transition from ANIS → AIS requires the OUT directory to remain empty for at least N seconds (ZK_Session_Timeout).
3. A region server's attempt to update state to SYNC mode is rejected if:
   - Other region servers might still be in Store&Forward mode, OR
   - ZK_Session_Timeout has not yet elapsed since last store-and-forward activity.
4. A region server aborts if its ZK client receives a DISCONNECTED event.

> **⚠ IMPL: Timing parameter differences**: The implementation uses different
> timing multipliers than the design:
>
> | Parameter | Design | Implementation |
> |-----------|--------|----------------|
> | Heartbeat interval | N/2 (0.5×) = ~33s | 0.7 × ZK_SESSION_TIMEOUT = ~63s |
> | Wait for SYNC | N (1.1× ZK timeout) = ~66s | 1.1 × ZK_SESSION_TIMEOUT = ~99s |
> | ZK session timeout default | 60s | 90s |
>
> The heartbeat fires at `0.7 × ZK_SESSION_TIMEOUT` via
> `StoreAndForwardModeImpl.startHAGroupStoreUpdateTask()`. The ANIS→AIS gate
> checks `(mtime + 1.1 × ZK_SESSION_TIMEOUT) ≤ current_time` in
> `HAGroupStoreClient.validateTransitionAndGetWaitTime()`.
> See `IMPL_CROSS_REFERENCE.md` §5.

### Timeline Example

```
t0: Local=AIS, Peer=STANDBY      (all in sync)
t1: Local=ANIS, Peer=STANDBY     (RS1/RS2 switches to Store&Forward)
t2: Local=ANIS, Peer=DSFW        (Peer reacts to ANIS)
t3: Local=ANIS, Peer=DSFW        (RS1 can now sync, RS2 still S&F)
t4: Local=ANIS, Peer=DSFW        (RS1 update rejected: other RS may be S&F)
t5: Local=AIS, Peer=Standby      (ZK_Session_Timeout elapsed, all RS in sync)
```

---

## 7. Concurrency Control via ZK Optimistic Locking (HAGroup.docx)

State updates use ZooKeeper's versioned `setData` with optimistic locking:

1. Read current state + version from ZNode
2. Compute new state
3. Write new state using `setData().withVersion(readVersion)`
4. If BadVersionException: re-read and retry
5. ZK version is 32-bit integer; comparison must handle overflow
6. Version `-1` is a wildcard (must not be used in normal operation)

### Each Cluster Manages Its Own State

- Each cluster stores only its own state in its own ZK quorum
- Peer clusters listen to each other's ZK transitions via watchers
- A cluster reacts to peer state changes by updating its own state
- ~~No cross-cluster atomic writes (except the final failover step)~~
  **⚠ IMPL**: No cross-cluster atomic writes at all — including the final
  failover step. Each cluster writes only to its own ZK quorum. The final
  failover is two independent writes. See `IMPL_CROSS_REFERENCE.md` §4.2 and §11.3.

> **⚠ IMPL: ZK znode path**: State is stored at
> `/phoenix/consistentHA/<group>` in each cluster's ZK quorum. Peer state
> is read via a separate `peerPathChildrenCache` (Curator recipe) connected
> to the peer ZK quorum. See `IMPL_CROSS_REFERENCE.md` §4.

---

## 8. Forced Failover (Consistent_Failover.docx)

Forced failover relaxes the transition criteria:
- Allows `DegradedStandby → StandbyToActive → Active` even when backlog is not fully drained
- Operator-triggered only
- May introduce application-level inconsistencies due to missing commits in S&F queue
- Represented by dotted line in state transition diagram
- Must be logged with: user identity, timestamp, old and new cluster roles

> **⚠ IMPL: No dedicated force-failover command**: The implementation does NOT
> have a dedicated force-failover operation. Forced failover requires manual
> state manipulation via `PhoenixHAAdminTool update --force --state <state>`,
> which bypasses `haGroupState` validation. The tool does not validate semantic
> correctness of state transitions when `--force` is used.
> See `IMPL_CROSS_REFERENCE.md` §6.4.
