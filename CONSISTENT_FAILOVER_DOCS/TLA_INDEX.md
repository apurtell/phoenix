# TLA+ Modeling Index for Phoenix Consistent Failover

Comprehensive reference index for building TLA+ formal specifications of the
Phoenix HA failover protocol. Organized by modeling concern.

**Source documents**: `Consistent_Failover.docx` (Kadir Ozdemir, Andrew Purtell)
and `HAGroup.docx` (Ritesh Garg).

---

## Table of Contents

1. [State Machines Summary](#1-state-machines-summary)
2. [State Enumerations](#2-state-enumerations)
3. [Transition Relations](#3-transition-relations)
4. [Variables for TLA+ Specification](#4-variables-for-tla-specification)
5. [Actors / Processes](#5-actors--processes)
6. [Events and Actions](#6-events-and-actions)
7. [Safety Properties (Invariants)](#7-safety-properties-invariants)
8. [Liveness Properties (Temporal)](#8-liveness-properties-temporal)
9. [Timing and Anti-Flapping Constraints](#9-timing-and-anti-flapping-constraints)
10. [Concurrency Control Model](#10-concurrency-control-model)
11. [HDFS Directory Predicates](#11-hdfs-directory-predicates)
12. [Forced Failover Semantics](#12-forced-failover-semantics)
13. [Combined Product State Space](#13-combined-product-state-space)
14. [Consistency Proof Obligations](#14-consistency-proof-obligations)
15. [Open Questions and Edge Cases](#15-open-questions-and-edge-cases)
16. [Document Cross-Reference Map](#16-document-cross-reference-map)

---

## 1. State Machines Summary

The protocol involves six interrelated state machines. Each should be modeled
as a separate TLA+ module or a distinct component within a single specification.

| # | State Machine | Scope | Source | Index File |
|---|---------------|-------|--------|------------|
| SM1 | Cluster HA Role | Top-level per-cluster role | CF §State Transitions | `state-machines.md` §1 |
| SM2 | Active Cluster Sub-States | Active cluster sync/async sub-states | HG §Active Cluster STD | `state-machines.md` §2 |
| SM3 | Standby Cluster Sub-States | Standby cluster degraded sub-states | HG §Standby Cluster STD | `state-machines.md` §3 |
| SM4 | Replication Log Writer | Per-RS SYNC/S&F/SYNC&FWD | CF §State Tracking | `state-machines.md` §4 |
| SM5 | Combined (Product) | (C1_State, C2_State) pairs | HG §For both cluster states | `state-machines.md` §5 |
| SM6 | Replication Replay | Per-cluster SYNC/DEGRADED/RECOVERY | *(impl only)* | `IMPL_CROSS_REFERENCE.md` §2.4 |

**Key**: CF = `Consistent_Failover_text.md`, HG = `HAGroup_text.md`

> **Implementation note**: SM6 (Replication Replay State Machine) is an
> implementation-specific state machine not present in the design docs. It
> manages consistency point tracking during ANIS↔AIS transitions. See
> `IMPL_CROSS_REFERENCE.md` §2.4 and §8.2 for details.

---

## 2. State Enumerations

### 2.1 Top-Level Cluster Role States (SM1)

```
ClusterRole == { Active, Standby, ActiveToStandby, StandbyToActive,
                 DegradedStandby, AbortToActive, AbortToStandby, Offline }
```

**Source**: `Consistent_Failover_text.md` §State Transitions;
`state-machines.md` §1 table.

### 2.2 Active Cluster Sub-States (SM2)

```
ActiveSubState == { AIS, ANIS, ATS, ANISTS, AbTAIS, AbTANIS,
                    AWOP, ANISWOP, S }
```

| Abbreviation | Full Name | Description |
|---|---|---|
| AIS | Active In Sync | All RS replicating synchronously |
| ANIS | Active Not In Sync | ≥1 RS in store-and-forward |
| ATS | Active To Standby | Failover initiated from AIS (OUT empty) |
| ANISTS | Active Not In Sync To Standby | Failover initiated from ANIS (OUT not empty) |
| AbTAIS | Abort To Active In Sync | Abort reverting to AIS |
| AbTANIS | Abort To Active Not In Sync | Abort reverting to ANIS |
| AWOP | Active With Offline Peer | AIS but peer is OFFLINE |
| ANISWOP | Active Not In Sync With Offline Peer | ANIS but peer is OFFLINE |
| S | Standby | Terminal state after failover completes |

**Source**: `HAGroup_text.md` §State Transition Diagram for Active Cluster;
`state-machines.md` §2; `HAGroup_diagrams_reconstructed.md` §Active Cluster STD.

### 2.3 Standby Cluster Sub-States (SM3)

```
StandbySubState == { S, STA, AIS, AbTS, DS, DSFW, DSFR, OFFLINE }
```

| Abbreviation | Full Name | Description |
|---|---|---|
| S | Standby | Normal standby, receiving/replaying logs |
| STA | Standby To Active | Preparing to become active, replaying logs |
| AIS | Active In Sync | Fully active after failover |
| AbTS | Abort To Standby | Reverting from STA during abort |
| DS | Degraded Standby | Both writer and reader degraded |
| DSFW | Degraded Standby For Writer | Peer in ANIS (writer can't sync) |
| DSFR | Degraded Standby For Reader | Local reader lag exceeds threshold |
| OFFLINE | Offline | Operator-controlled offline |

**Source**: `HAGroup_text.md` §State Transition Diagram for Standby Cluster;
`state-machines.md` §3.

> **Implementation note**: The implementation collapses DSFW, DSFR, and DS
> into a single `DEGRADED_STANDBY` state. Additionally, `OFFLINE` is a
> sink state in the implementation (no outbound transitions allowed).
> See `IMPL_CROSS_REFERENCE.md` §11.1 and §11.2.

### 2.4 Replication Writer States (SM4, per-RegionServer)

```
WriterMode == { INIT, SYNC, StoreAndForward, SyncAndForward }
```

| State | Description |
|---|---|
| INIT | Pre-initialization (implementation-only) |
| SYNC | Writing directly to standby HDFS |
| StoreAndForward (S&F) | Writing locally, standby HDFS unavailable |
| SyncAndForward (SYNC&FWD) | Draining local queue + writing synchronously |

> **Implementation note**: The implementation adds an `INIT` state with
> transitions `INIT → SYNC` and `INIT → STORE_AND_FORWARD`. It also adds
> `SYNC → SYNC_AND_FORWARD` (not in design) for cases where a forwarder is
> started while still in sync. Write failure in S&F mode is **fail-stop**
> (RS aborts). See `IMPL_CROSS_REFERENCE.md` §2.3 and §7.4.

**Source**: `Consistent_Failover_text.md` §State Tracking;
`state-machines.md` §4.

### 2.5 State-to-Role Mapping

Each internal state maps to exactly one externally visible role. Clients see
roles, not internal states.

```
RoleOf(AIS)     = ACTIVE              RoleOf(ANIS)    = ACTIVE
RoleOf(ATS)     = ACTIVE_TO_STANDBY   RoleOf(ANISTS)  = ACTIVE_TO_STANDBY
RoleOf(AbTAIS)  = ACTIVE              RoleOf(AbTANIS) = ACTIVE
RoleOf(AWOP)    = ACTIVE              RoleOf(ANISWOP) = ACTIVE
RoleOf(S)       = STANDBY             RoleOf(STA)     = STANDBY_TO_ACTIVE
RoleOf(DS)      = STANDBY             RoleOf(AbTS)    = STANDBY
RoleOf(OFFLINE) = OFFLINE             RoleOf(UNKNOWN) = UNKNOWN
```

> **Implementation note**: The implementation role names are
> `ClusterRole.ACTIVE`, `STANDBY`, `ACTIVE_TO_STANDBY`, `STANDBY_TO_ACTIVE`,
> `OFFLINE`, `UNKNOWN`. Notably, `DEGRADED_STANDBY` maps to role `STANDBY`
> (not a separate DEGRADED role), and ATS/ANISTS map to `ACTIVE_TO_STANDBY`
> (not just "ATS"). The `ANIS → ANIS` self-transition is allowed to support
> the anti-flapping heartbeat. See `IMPL_CROSS_REFERENCE.md` §2.1.

Default state for ACTIVE role: `ANIS` (Active Not In Sync).
Default state for STANDBY role: `DEGRADED_STANDBY`.

**Source**: `HAGroup_text.md` §System Table for HAGroup and differentiating
between State vs Role.

---

## 3. Transition Relations

### 3.1 Active Cluster Transitions (SM2)

```tla
ActiveNext ==
  \/ AIS    -> ANIS      \* W changes to Store & Forward
  \/ ANIS   -> AIS       \* OUT empty AND ZK_Session_Timeout elapsed
  \/ AIS    -> ATS       \* Operator initiates failover from AIS
  \/ ANIS   -> ANISTS    \* Operator initiates failover from ANIS
  \/ ANISTS -> ATS       \* OUT directory becomes empty
  \/ ATS    -> S         \* Peer transitions STA -> AIS/ANIS
  \/ ATS    -> AbTAIS    \* Operator aborts failover
  \/ ATS    -> AbTANIS   \* Operator aborts failover (was ANIS)
  \/ AbTAIS -> AIS       \* Detect abort complete
  \/ AbTANIS-> ANIS      \* Detect abort complete
  \/ AIS    -> AWOP      \* Peer changes to OFFLINE
  \/ ANIS   -> ANISWOP   \* Peer changes to OFFLINE
  \/ AWOP   -> AIS       \* Peer leaves OFFLINE
  \/ ANISWOP-> ANIS      \* Peer leaves OFFLINE
```

**Precondition for AIS -> ATS**: OUT directory MUST be empty, all RS in sync.

**Source**: `state-machines.md` §2; `HAGroup_diagrams_reconstructed.md`
§Active Cluster STD.

### 3.2 Standby Cluster Transitions (SM3)

```tla
StandbyNext ==
  \/ S      -> STA       \* Detect peer AIS -> ATS
  \/ S      -> DSFW      \* Detect peer AIS -> ANIS
  \/ STA    -> AIS       \* Replay complete
  \/ STA    -> AbTS      \* Operator aborts failover
  \/ AbTS   -> S         \* Abort complete
  \/ DSFW   -> S         \* Detect peer ANIS -> AIS
  \/ DSFW   -> DS        \* Reader also becomes degraded
  \/ DSFR   -> S         \* Reader lag below threshold
  \/ DSFR   -> DS        \* Writer also becomes degraded
  \/ DS     -> DSFW      \* Reader recovers
  \/ DS     -> DSFR      \* Writer recovers (peer -> AIS)
  \/ S      -> OFFLINE   \* Operator takes offline
  \/ OFFLINE-> S         \* Operator brings back
```

**Source**: `state-machines.md` §3; `HAGroup_diagrams_reconstructed.md`
§Standby Cluster STD.

### 3.3 Replication Writer Transitions (SM4)

```tla
WriterNext ==
  \/ INIT       -> SYNC      \* Normal startup (implementation-specific)
  \/ INIT       -> S&F       \* Startup with peer unavailable (implementation-specific)
  \/ SYNC       -> S&F       \* Standby HDFS unavailable
  \/ SYNC       -> SYNC&FWD  \* Forwarder started while in sync (implementation-specific)
  \/ S&F        -> SYNC&FWD  \* Recovery detected, standby available
  \/ SYNC&FWD   -> SYNC      \* All stored logs forwarded, queue empty
  \/ SYNC&FWD   -> S&F       \* Degraded again during drain
  \* S&F write error => RS ABORT (fail-stop, no further fallback)
```

**Source**: `state-machines.md` §4;
`Consistent_Failover_diagrams_reconstructed.md` §State Tracking;
`IMPL_CROSS_REFERENCE.md` §2.3.

### 3.4 Combined State Transitions (SM5)

Notation: `(C1_State, C2_State) --[Actor, Event]--> (C1_State', C2_State')`

> **Implementation note**: In the implementation, degraded standby sub-states
> (DSFW, DSFR, DS) are collapsed into a single `DEGRADED_STANDBY`. Combined
> states involving DSFW/DSFR/DS become `(*, DEGRADED_STANDBY)`. The default
> initial combined state is `(ANIS, DEGRADED_STANDBY)`, not `(AIS, S)`.
> See `IMPL_CROSS_REFERENCE.md` §11.1 and §11.6.

#### Normal degradation/recovery cycle

```
(AIS, S)     --[W, i]-->      (ANIS, S)
(ANIS, S)    --[M2, k]-->     (ANIS, DSFW)
(AIS, S)     --[R, m]-->      (AIS, DSFR)
(ANIS, DSFW) --[M2, k]-->     (ANIS, DS)
(AIS, DSFR)  --[R, m]-->      (AIS, DS)
(ANIS, S)    --[W, j]-->      (AIS, S)         [requires ZK_Session_Timeout]
(ANIS, DSFW) --[W, j]-->      (AIS, DSFW)
(ANIS, DS)   --[W, j]-->      (AIS, DS)
(AIS, DSFR)  --[R, n]-->      (AIS, S)
(AIS, DS)    --[R, n]-->      (AIS, DSFW)
(ANIS, DSFR) --[R, n]-->      (ANIS, S)
(ANIS, DS)   --[R, n]-->      (ANIS, DSFW)
(AIS, DSFW)  --[M2, l]-->     (AIS, S)
(AIS, DS)    --[M2, l]-->     (AIS, DSFR)
```

#### Failover sequence

```
(AIS, S)     --[A, ac1]-->    (ATS, S)          [start failover]
(ANIS, S)    --[A, ac1]-->    (ANISTS, S)       [start failover from ANIS]
(ANISTS, S)  --[W, j]-->      (ATS, S)          [OUT dir now empty]
(ATS, S)     --[M2, b]-->     (ATS, STA)        [standby detects failover]
(ATS, STA)   --[R, c]-->      (ATS, A*)         [replay complete]
(ATS, A*)    --[M1, d]-->     (S, AIS)          [⚠ IMPL: TWO separate ZK writes, NOT atomic]
```

> **CRITICAL implementation divergence (step d)**: The design says this is "a
> single atomic ZK multi-operation." The implementation uses two separate ZK
> writes: (1) New active writes `ACTIVE_IN_SYNC` to its own ZK, then (2) old
> active's `FailoverManagementListener` reactively writes `STANDBY` to its
> own ZK. During the window between these writes, both clusters appear in
> non-conflicting roles: new active is `ACTIVE_IN_SYNC` (role `ACTIVE`), old
> active is `ACTIVE_IN_SYNC_TO_STANDBY` (role `ACTIVE_TO_STANDBY` — NOT an
> Active role). See `IMPL_CROSS_REFERENCE.md` §11.3.

#### Abort sequence

```
(ATS, S)     --[A, ac2]-->    (AbTA, S)         [abort before STA]
(ATS, STA)   --[A, ac2]-->    (AbTA, STA)       [abort during STA]
(AbTA, STA)  --[M2, g]-->     (AbTA, AbTS)      [peer detects abort]
(AbTA, S)    --[M2, h]-->     (A*, S)           [abort complete, no STA]
(AbTA, AbTS) --[M2, h]-->     (A*, AbTS)        [intermediate]
(A*, AbTS)   --[W, f]-->      (A*, S)           [abort fully complete]
(A*, STA)    --[W, f]-->      (A*, S)           [abort fully complete]
```

**Source**: `state-machines.md` §5; `HAGroup_diagrams_reconstructed.md`
§Appendix (latest version).

---

## 4. Variables for TLA+ Specification

### 4.1 Per-Cluster Variables

```tla
VARIABLES
  clusterState,   \* [c \in Cluster |-> ActiveSubState \cup StandbySubState]
  writerMode,     \* [c \in Cluster |-> [rs \in RS |-> WriterMode]]
  outDirEmpty,    \* [c \in Cluster |-> BOOLEAN]
  replayComplete, \* [c \in Cluster |-> BOOLEAN]
  lastSyncTime,   \* [c \in Cluster |-> Nat]  (logical clock)
  readerLag       \* [c \in Cluster |-> {Normal, Degraded}]
```

### 4.2 ZooKeeper State Variables

```tla
VARIABLES
  zkRecord,       \* [c \in Cluster |-> HAGroupStoreRecord]
  zkVersion,      \* [c \in Cluster |-> Nat]  (32-bit, wraps)
  zkConnected     \* [c \in Cluster |-> [rs \in RS |-> BOOLEAN]]
```

### 4.3 Per-Cluster Replay Variables (from implementation)

```tla
VARIABLES
  replayState,        \* [c \in Cluster |-> {NotInit, SyncReplay, DegradedReplay, SyncedRecovery}]
  lastRoundInSync,    \* [c \in Cluster |-> Nat]  (frozen during degraded periods)
  lastRoundProcessed, \* [c \in Cluster |-> Nat]  (advances continuously)
  failoverPending,    \* [c \in Cluster |-> BOOLEAN]
  inProgressDirEmpty  \* [c \in Cluster |-> BOOLEAN]
```

> **Note**: These variables are derived from the implementation's
> `ReplicationLogDiscoveryReplay` class, which is not described in the
> design docs. See `IMPL_CROSS_REFERENCE.md` §2.4 and §8.

### 4.4 Global/Environment Variables

```tla
VARIABLES
  clock,          \* Nat: logical clock for timing constraints
  hdfsAvailable,  \* [c \in Cluster |-> BOOLEAN]: peer HDFS reachable
  adminAction     \* {None, StartFailover, AbortFailover, ForceFailover,
                  \*  GoOffline, GoOnline}
```

### 4.5 HAGroupStoreRecord Structure

```tla
HAGroupStoreRecord == [
  protocolVersion : STRING,
  haGroupName     : STRING,
  haGroupState    : ActiveSubState \cup StandbySubState,
  lastSyncTimeInMs: Nat
]
```

**Source**: `HAGroup_text.md` §Specs - Class HAGroupStoreRecord;
`architecture.md` §HA Group Record Structure.

---

## 5. Actors / Processes

Each actor should be modeled as a separate PlusCal process or TLA+ action group.

| Actor | ID | Description | Actions |
|-------|-----|-------------|---------|
| Admin | A | Human operator or automation | ac1 (start failover), ac2 (abort), force failover, go offline/online |
| ReplicationLogWriter | W | Per-RS, on active cluster | Events i (S&F mode), j (OUT empty / sync mode), f (detect AbTA) |
| ReplicationLogReader | R | On standby cluster | Events c (replay complete), m (lag exceeds threshold), n (lag below threshold) |
| HAGroupStoreManager (C1) | M1 | Cluster1's coprocessor endpoint | Events d (detect peer STA -> AIS), f (detect AbTA -> AIS/ANIS) |
| HAGroupStoreManager (C2) | M2 | Cluster2's coprocessor endpoint | Events b (detect peer ATS), g (detect peer AbTA), h (local AbTS), k (detect peer ANIS), l (detect peer AIS) |

> **Implementation mapping**:
>
> | Actor | Implementation Class | Trigger Mechanism |
> |-------|---------------------|-------------------|
> | A | `PhoenixHAAdminTool` CLI | Operator runs CLI commands |
> | W | `ReplicationLogGroup` + mode impls | `IndexRegionObserver.replicateMutations()` → Disruptor → mode impl |
> | R | `ReplicationLogDiscoveryReplay` + `ReplicationLogProcessor` | Scheduled periodic replay rounds |
> | M1, M2 | `HAGroupStoreManager.FailoverManagementListener` | ZK watcher events via `PathChildrenCache` |
>
> See `IMPL_CROSS_REFERENCE.md` §3.3 and §3.4.

**Source**: `state-machines.md` §5 Actor table; `HAGroup_text.md` §For both
cluster states.

---

## 6. Events and Actions

### 6.1 Actions (Operator-Initiated)

| ID | Name | Description | Guard |
|----|------|-------------|-------|
| ac1 | StartFailover | Admin initiates failover | clusterState[active] ∈ {AIS, ANIS} |
| ac2 | AbortFailover | Admin cancels in-progress failover | clusterState[active] ∈ {ATS} OR clusterState[standby] = STA (abort from STA side recommended) |
| force | ForcedFailover | Admin forces standby to active | clusterState[standby] ∈ {DS, DSFW, DSFR} |

### 6.2 Events (System-Generated)

| ID | Name | Description | Guard |
|----|------|-------------|-------|
| b | DetectPeerATS | M2 detects peer transitioned AIS -> ATS | zkRecord[peer].state = ATS |
| c | ReplayComplete | R finishes replaying all logs | ⚠ IMPL: `failoverPending ∧ inProgressDirEmpty ∧ noNewFilesInWindow` |
| d | DetectPeerActive | M1 detects peer STA -> AIS/ANIS | zkRecord[peer].state ∈ {AIS, ANIS} ∧ local state = ATS |
| f | DetectAbort | W/M1 detects AbTA, reverts to AIS/ANIS | zkRecord[local].state = AbTA |
| g | PeerDetectsAbort | M2 detects peer ATS -> AbTA | zkRecord[peer].state = AbTA |
| h | LocalAbTS | Peer state changed to AbTS | zkRecord[local].state = AbTS |
| i | WriterToS&F | W switches to store-and-forward | hdfsAvailable[peer] = FALSE |
| j | OutDirEmpty | OUT directory empty, all W in sync | outDirEmpty[active] = TRUE ∧ ∀ rs: writerMode[active][rs] = SYNC |
| k | DetectPeerANIS | M2 detects peer AIS -> ANIS | zkRecord[peer].state = ANIS |
| l | DetectPeerAIS | M2 detects peer ANIS -> AIS | zkRecord[peer].state = AIS |
| m | ReaderLagHigh | R lag exceeds threshold | readerLag[standby] = Degraded |
| n | ReaderLagLow | R lag below threshold | readerLag[standby] = Normal |

**Source**: `state-machines.md` §5; `HAGroup_text.md` §For both cluster states.

---

## 7. Safety Properties (Invariants)

### 7.1 Mutual Exclusion (No Dual-Active)

```tla
MutualExclusion ==
  ~( clusterState[C1] \in {AIS, ANIS, AWOP, ANISWOP}
   /\ clusterState[C2] \in {AIS, ANIS, AWOP, ANISWOP} )
```

Two clusters must never both be in any Active-role state simultaneously.

**Source**: `state-machines.md` §5 "Key Safety Properties" item 1;
`HAGroup_text.md` §HAGroupStoreManager responsibilities;
`architecture.md` §Key Safety Arguments.

### 7.2 No Data Loss (Zero RPO)

```tla
NoDataLoss ==
  (clusterState[c] = STA /\ clusterState[c]' = AIS)
    => replayComplete[c]
```

A standby must replay ALL replication logs before transitioning STA -> AIS.

**Source**: `state-machines.md` §5 item 2; `architecture.md` §Consistent
Failover Proof Sketch step 3.

### 7.3 Atomic Failover Step

```tla
AtomicFailover ==
  \A c1, c2 \in Cluster :
    (clusterState[c1] = ATS /\ clusterState[c2] \in {AIS, ANIS})
    => \* (c1 -> S) and (c2 remains AIS/ANIS) happen in single ZK multi-op
       clusterState'[c1] = S
```

The final failover step `(ATS, A*) -> (S, AIS)` is described in the design as
a single atomic ZooKeeper multi-operation.

> **CRITICAL implementation divergence**: The implementation does NOT use an
> atomic ZK multi-op for this step. Instead, it is two separate ZK writes:
> (1) New active writes `ACTIVE_IN_SYNC` to its own ZK, then
> (2) Old active's FailoverManagementListener reactively writes `STANDBY`
> to its own ZK. Safety is maintained because the old active is already in
> `ACTIVE_IN_SYNC_TO_STANDBY` (role = `ACTIVE_TO_STANDBY`), which blocks
> mutations. The TLA+ model should verify this two-step approach preserves
> mutual exclusion during the window between steps.
> See `IMPL_CROSS_REFERENCE.md` §11.3 for full analysis.

**Source**: `state-machines.md` §5 item 3;
`Consistent_Failover_text.md` §HA Group Store Manager.

### 7.4 AIS-to-ATS Precondition

```tla
AIStoATSPrecondition ==
  (clusterState[c] = AIS /\ clusterState'[c] = ATS)
    => ( outDirEmpty[c]
       /\ \A rs \in RS : writerMode[c][rs] = SYNC )
```

Failover can only begin from AIS when OUT dir is empty and all RS are syncing.

**Source**: `architecture.md` §Key Safety Arguments step 2;
`state-machines.md` §2 "Key Invariant".

### 7.5 Abort Safety

```tla
AbortSafety ==
  \* Abort must originate from STA side to prevent dual-active
  (adminAction = AbortFailover)
    => ( clusterState[standby] = STA
       \/ clusterState[active] \in {ATS, ANISTS} )
```

Abort must be initiated from the STA (standby-to-active) side. If aborted from
ATS side directly, the STA cluster could simultaneously become Active.

**Source**: `HAGroup_text.md` §Combined State Transition Diagram note;
`state-machines.md` §5 item 4.

### 7.6 State Transition Validity

```tla
ValidTransition ==
  \A c \in Cluster :
    clusterState'[c] # clusterState[c]
    => <clusterState[c], clusterState'[c]> \in AllowedTransitions
```

Every state change must follow the defined transition relation.

**Source**: `HAGroup_text.md` §Requirements item: "Throw a custom exception in
case the input state transition is not allowed."

---

## 8. Liveness Properties (Temporal)

### 8.1 Failover Completion

```tla
FailoverProgress ==
  (adminAction = StartFailover /\ ~(adminAction = AbortFailover))
    ~> (clusterState[C1] = S /\ clusterState[C2] = AIS)
       \/ (clusterState[C1] = AIS /\ clusterState[C2] = S)
```

If failover is initiated and not aborted, it eventually completes.

### 8.2 Degradation Recovery

```tla
DegradationRecovery ==
  (clusterState[c] = ANIS /\ <>[]hdfsAvailable[peer])
    ~> clusterState[c] = AIS
```

If HDFS connectivity recovers permanently, the cluster eventually returns to
AIS.

### 8.3 Abort Completion

```tla
AbortCompletion ==
  (adminAction = AbortFailover)
    ~> ( clusterState[active] \in {AIS, ANIS}
       /\ clusterState[standby] = S )
```

If abort is initiated, the system eventually returns to (A*, S).

### 8.4 Anti-Flapping Bound

```tla
AntiFlapBound ==
  \* ANIS/AIS oscillation is bounded by ZK_Session_Timeout
  \* (model as fairness + timing constraint)
```

The number of ANIS <-> AIS transitions within any window of N seconds is
bounded.

**Source**: `state-machines.md` §6; README.md §Liveness Properties.

---

## 9. Timing and Anti-Flapping Constraints

### 9.1 Key Timing Parameters

| Parameter | Symbol | Design Default | ⚠ Impl Default | Description |
|-----------|--------|---------------|----------------|-------------|
| ZK Session Timeout | N | 60s × 1.1 = 66s | 90s × 1.1 = 99s | Max staleness of cached state |
| Writer Heartbeat | N/2 or N×0.7 | 33s (N/2) | 63s (N×0.7) | RS periodically confirms mode to ZK |
| Log File Rotation | configurable | 1 minute | 60,000ms (or 95% size) | Duration of one replication log file |
| Non-Active Record TTL | configurable | varies | varies | Client cache expiry for non-active records |

> **⚠ IMPL**: Design uses multiplier 0.5 (N/2) for heartbeat; implementation
> uses 0.7 (`HA_GROUP_STORE_UPDATE_MULTIPLIER`). Design assumes 60s ZK timeout;
> implementation defaults to 90s. See `IMPL_CROSS_REFERENCE.md` §5.1.

### 9.2 Anti-Flapping Protocol Rules

1. RS periodically (every N/2 seconds) writes current mode to ZK.
2. ANIS -> AIS requires OUT directory to remain empty for ≥ N seconds.
3. RS attempt to update state to SYNC is rejected if:
   - Other RS might still be in Store&Forward, OR
   - N seconds have not elapsed since last S&F activity.
4. RS aborts itself if ZK client receives DISCONNECTED event.
5. RS blocks writes and updates ZK if in S&F mode and cached record > N seconds old.

### 9.3 TLA+ Modeling Approach for Timing

Option A: Use a logical clock variable `clock` incremented non-deterministically,
with guards like `clock - lastSyncTime[c] >= N`.

Option B: Abstract timing away — model the N-second wait as a single
non-deterministic "timeout elapsed" action enabled by a boolean flag.

### 9.4 Anti-Flapping Timeline Example

```
t0: (AIS, STANDBY)       — all RS in sync
t1: (ANIS, STANDBY)      — RS1/RS2 switch to S&F
t2: (ANIS, DSFW)          — Peer reacts to ANIS
t3: (ANIS, DSFW)          — RS1 can sync, RS2 still S&F
t4: (ANIS, DSFW)          — RS1 sync rejected (other RS may be S&F, or
                              ZK_Session_Timeout not elapsed)
t5: (AIS, Standby)        — ZK_Session_Timeout elapsed, all RS sync
```

**Source**: `state-machines.md` §6; `HAGroup_text.md` §Avoiding flapping;
`HAGroup_diagrams_reconstructed.md` §Avoiding flapping.

---

## 10. Concurrency Control Model

### 10.1 ZK Optimistic Locking Protocol

Each state update uses ZooKeeper versioned `setData`:

```tla
ZKUpdate(cluster, newState) ==
  LET ver == zkVersion[cluster]
      curState == zkRecord[cluster].haGroupState
  IN
    /\ <curState, newState> \in AllowedTransitions
    /\ zkRecord' = [zkRecord EXCEPT ![cluster].haGroupState = newState]
    /\ zkVersion' = [zkVersion EXCEPT ![cluster] = ver + 1]
    \* If BadVersionException: re-read and retry
```

### 10.2 Key Rules

- Each cluster stores ONLY its own state in its own ZK quorum.
- Peer clusters listen to each other's ZK via watchers (reactive).
- A cluster reacts to peer state changes by updating its OWN state.
- ~~No cross-cluster atomic writes EXCEPT the final failover step.~~
  **⚠ IMPL**: No cross-cluster atomic writes **at all** — the final failover
  step is also two separate ZK writes to two independent quorums. See §7.3.
- ZK version is 32-bit integer; comparison must handle overflow.
- Version -1 is a wildcard (must not be used in normal operation).
- **⚠ IMPL**: ZK state at `/phoenix/consistentHA/<group>`. Local cache via
  Curator `PathChildrenCache`. Peer cache via separate `peerPathChildrenCache`.
  Singleton managers via `ConcurrentHashMap.computeIfAbsent()`.
  `FailoverManagementListener` auto-drives reactive transitions with up to
  2 retries.

### 10.3 Race Condition Scenarios

Multiple RS on the same cluster may concurrently attempt to update state
(e.g., RS1 sees OUT empty, RS2 sees OUT not empty). ZK optimistic locking
ensures only one update succeeds; losers re-read and retry.

**Source**: `HAGroup_text.md` §Problem 1, §Problem 2;
`state-machines.md` §7; `HAGroup_diagrams_reconstructed.md` §Problem 1,
§Description of race condition.

---

## 11. HDFS Directory Predicates

### 11.1 Directory Layout

```
/phoenix-replication/IN    — Receives replication logs from peer
/phoenix-replication/OUT   — Buffers outbound logs when peer is degraded
```

> **⚠ IMPL**: Sharded layout: `/<hdfs-base>/<ha-group-name>/in/shard/<shard-id>/`
> and `/<hdfs-base>/<ha-group-name>/out/shard/<shard-id>/` (shard-id from RS
> name). See `IMPL_CROSS_REFERENCE.md` §9.

### 11.2 Predicates for State Guards

| Predicate | Used In | Description |
|-----------|---------|-------------|
| `outDirEmpty[c]` | ANIS -> AIS, ANISTS -> ATS | OUT directory has no pending logs |
| `inDirHasLogs[c]` | STA -> AIS guard | IN directory has unreplayed logs |
| `allLogsReplayed[c]` | STA -> AIS | All logs in IN replayed and committed |

### 11.3 HDFS State Machine Interaction

```
SYNC mode:       Writer writes to peer's /IN
S&F mode:        Writer writes to local /OUT
SYNC&FWD mode:   Writer writes to peer's /IN AND drains local /OUT
```

Transition ANIS -> AIS requires:
1. `outDirEmpty[c] = TRUE`
2. `clock - lastSyncTime[c] >= N` (ZK_Session_Timeout elapsed)
3. `∀ rs : writerMode[c][rs] = SYNC`

**Source**: `architecture.md` §HDFS Directory Layout;
`Consistent_Failover_text.md` §Handling Synchronous Replication Failures.

---

## 12. Forced Failover Semantics

Forced failover relaxes normal transition constraints:

```tla
ForcedFailover ==
  /\ adminAction = ForceFailover
  /\ clusterState[standby] \in {DS, DSFW, DSFR}
  /\ clusterState'[standby] = STA
  \* Does NOT require outDirEmpty or allLogsReplayed
  \* May introduce application-level inconsistencies
```

Properties:
- Operator-triggered only.
- Allows `DegradedStandby -> STA -> AIS` even when backlog not drained.
- May cause missing commits from S&F queue (non-zero RPO).
- Must be logged: user identity, timestamp, old/new cluster roles.
- Represented by dotted line in original state diagram.

> **⚠ IMPL**: No dedicated force-failover command exists. Forced failover
> requires manual state manipulation via `PhoenixHAAdminTool update --force
> --state <state>`, bypassing validation. The TLA+ model should capture that
> forced failover may use `DEGRADED_STANDBY` (collapsed) rather than
> `DS/DSFW/DSFR`. See `IMPL_CROSS_REFERENCE.md` §6.4.

**Source**: `Consistent_Failover_text.md` §Forced Failover in Degraded
Standby Conditions; `state-machines.md` §8.

---

## 13. Combined Product State Space

### 13.1 Reachable State Pairs

Based on the combined state transition diagram, the reachable product states
(C1, C2) when C1 starts as Active and C2 as Standby:

> **⚠ IMPL**: In the implementation, degraded states are collapsed: DSFW, DSFR,
> and DS become `DEGRADED_STANDBY`. The actual initial state is
> `(ANIS, DEGRADED_STANDBY)`, not `(AIS, S)`.

**Normal operations**:
```
(AIS, S), (ANIS, S), (ANIS, DSFW), (AIS, DSFR),
(ANIS, DSFR), (ANIS, DS), (AIS, DS), (AIS, DSFW)
```

**Failover in progress**:
```
(ATS, S), (ANISTS, S), (ATS, STA), (ATS, A*)
```

**Failover complete** (roles swapped):
```
(S, AIS)
```

**Abort sequence**:
```
(AbTA, S), (AbTA, STA), (AbTA, AbTS),
(A*, AbTS), (A*, STA), (A*, S)
```

**Offline variants** (from SM2):
```
(AIS, OFFLINE), (ANIS, OFFLINE),
(AWOP, OFFLINE), (ANISWOP, OFFLINE)
```

### 13.2 Unreachable/Invalid State Pairs

```
INVALID: (AIS, AIS)   — dual-active, violates mutual exclusion
INVALID: (ANIS, AIS)  — dual-active
INVALID: (AIS, ANIS)  — dual-active
INVALID: (ANIS, ANIS) — dual-active
INVALID: (STA, STA)   — both trying to become active
```

---

## 14. Consistency Proof Obligations

The design documents contain an informal proof sketch that should be formalized
in TLA+.

### 14.1 Proof Sketch (from HAGroup.docx)

1. Admin can only transition `AIS -> ATS` (not from ANIS directly to ATS).
2. When AIS, OUT directory is empty and writes to OUT are blocked.
3. Standby moves to ACTIVE only after:
   a. Detecting active moved to ATS.
   b. ALL replication logs replayed.
   c. **⚠ IMPL**: Plus in-progress directory empty AND no new files in window.
4. ~~Active moves `ATS -> S` only after peer moves to ACTIVE.~~
   **⚠ IMPL**: Active moves `ATS -> S` **reactively** via
   `FailoverManagementListener` after detecting peer `ACTIVE_IN_SYNC`. This
   is NOT atomic with step 3 — two separate ZK writes with a brief window.
   Safety holds because ATS role is `ACTIVE_TO_STANDBY`, which blocks mutations.
5. **Therefore**: no committed mutations are lost during failover.

### 14.2 Anti-Flapping Proof Sketch

- N = `zookeeper.session.timeout * 1.1`
- RS aborts if ZK DISCONNECTED received.
- Cached state guaranteed no more than N seconds stale.
- RS blocks writes and updates ZK if in S&F mode and cached record > N old.
- ANIS -> AIS requires OUT empty for N seconds.
- **Therefore**: no stale cached state can cause missed S&F -> SYNC transition.

### 14.3 Formal Proof Goals for TLA+

| Goal | Property Type | TLA+ Encoding |
|------|---------------|---------------|
| No dual-active | Safety (invariant) | `INVARIANT MutualExclusion` |
| No data loss | Safety (invariant) | `INVARIANT NoDataLoss` |
| ~~Atomic failover~~ Non-atomic failover safety | Safety (invariant) | ⚠ IMPL: Verify mutual exclusion during 2-step window (ATS role blocks mutations) |
| AIS->ATS precondition | Safety (invariant) | `INVARIANT AIStoATSPrecondition` |
| Abort safety | Safety (invariant) | `INVARIANT AbortSafety` |
| Failover trigger correctness | Safety (invariant) | ⚠ IMPL: `INVARIANT FailoverTriggerCorrectness` (3-condition check) |
| Failover progress | Liveness (temporal) | `PROPERTY FailoverProgress` |
| Degradation recovery | Liveness (temporal) | `PROPERTY DegradationRecovery` |
| Abort completion | Liveness (temporal) | `PROPERTY AbortCompletion` |

**Source**: `architecture.md` §Key Safety Arguments;
`HAGroup_text.md` §Ensuring Consistency Despite Concurrent HA Store Record
Updates; `README.md` §Key Concepts for TLA+ Modeling.

---

## 15. Open Questions and Edge Cases

These items from the design documents represent areas where the TLA+ model
should explore edge cases or may need design clarification.

### 15.1 From HAGroup.docx §Open Changes

1. **Different RS views of the world**: What if different RS have different
   views? Any single RS can be in S&F mode. Resolved via anti-flapping protocol
   but worth verifying in TLA+.

2. **Missed events**: One RS can keep thinking it is async while rest of cluster
   is in Sync mode. ZK session timeout bounds staleness.

3. **Standby can't communicate with active ZK during failover**: Standby should
   NOT become ACTIVE by itself. Must detect peer ATS first.

4. **Abort from ATS vs STA side**: Document says abort from STA side to avoid
   dual-active race. TLA+ should verify this is necessary.

5. **ANIS during failover**: When operator starts failover from ANIS, the
   cluster goes to ANISTS first, then to ATS once OUT is empty. What if OUT
   never empties? Admin timeout and abort.

### 15.2 From Design Review Comments

6. **Continue replaying after transitioning to Active** (Comment 42):
   If we replay logs after becoming Active, there is a brief inconsistency
   window. Design decided to abort RS that can't communicate with ZK.

7. **Standby can receive but not replay** (Comment 35): What if standby HDFS
   is fine but replay is stuck? Modeled via DSFR state.

8. **Single RS network partition** (Comments 16-21): One RS unable to replicate
   transitions entire cluster to ANIS. Each RS decides locally but the global
   state reflects worst case.

9. **Compaction window vs replication round** (Comments 14-15): Max lookback
   window must be larger than replication round duration. Static minimum
   considered sufficient.

### 15.3 From Implementation Analysis

10. **Non-atomic final failover step** (`IMPL_CROSS_REFERENCE.md` §11.3):
    The design specifies an atomic ZK multi-op for the final `(ATS, A*) → (S, AIS)`
    step, but the implementation uses two separate ZK writes. The TLA+ model
    should verify that mutual exclusion holds during the window between
    the new active writing `ACTIVE_IN_SYNC` and the old active writing `STANDBY`.

11. **Replay state machine not in design** (`IMPL_CROSS_REFERENCE.md` §2.4):
    The `SYNCED_RECOVERY` rewind behavior (resetting `lastRoundProcessed` to
    `lastRoundInSync` after recovering from DEGRADED) is an implementation
    mechanism for ensuring NoDataLoss. The TLA+ model should include this to
    verify the consistency point calculation is correct.

12. **ANIS self-transition heartbeat** (`IMPL_CROSS_REFERENCE.md` §11.4):
    The periodic re-assertion of `ACTIVE_NOT_IN_SYNC` in ZK refreshes `mtime`,
    preventing premature SYNC transitions. This is essential for anti-flapping
    correctness and should be modeled as a stuttering action.

13. **Writer fail-stop in S&F mode** (`IMPL_CROSS_REFERENCE.md` §7.4):
    If `STORE_AND_FORWARD` mode encounters a write error, the RS aborts (there
    is no further fallback). This crash behavior should be modeled to verify
    liveness properties under RS failures.

14. **Collapsed degraded sub-states** (`IMPL_CROSS_REFERENCE.md` §11.1):
    The implementation uses a single `DEGRADED_STANDBY` instead of the design's
    DSFW/DSFR/DS. Decide whether the TLA+ model should use the design's richer
    model (for completeness) or the implementation's simplified model (for
    verification of actual code).

15. **Default initial state** (`IMPL_CROSS_REFERENCE.md` §11.6):
    Active defaults to `ACTIVE_NOT_IN_SYNC`, Standby defaults to
    `DEGRADED_STANDBY`. The TLA+ Init predicate should reflect this.

### 15.4 Modeling Decisions Needed

| Decision | Options | Impact on TLA+ |
|----------|---------|----------------|
| Timing model | Logical clock vs abstract boolean | Complexity vs precision |
| Number of RS | Fixed (e.g., 2-3) vs parameterized | State space size |
| HDFS failures | Non-deterministic vs scheduled | Fault injection coverage |
| ZK partitions | Model or abstract away | Split-brain verification |
| Reader lag | Continuous vs threshold-based | Simplicity |
| Degraded sub-states | Design (3 states) vs impl (1 state) | Precision vs state space |
| Failover atomicity | Design (atomic) vs impl (2-step) | Safety verification focus |
| Replay state machine | Include or abstract | NoDataLoss verification |
| RS crash model | Include or exclude | Liveness under failures |

---

## 16. Document Cross-Reference Map

### 16.1 By TLA+ Modeling Concern

| Concern | Primary Source | Secondary Source |
|---------|---------------|------------------|
| State definitions | `state-machines.md` §1-3 | `HAGroup_text.md` §State Transition Diagrams |
| Transition relations | `state-machines.md` §2-5 | `HAGroup_diagrams_reconstructed.md` §Active/Standby/Combined |
| Combined state machine | `state-machines.md` §5 | `HAGroup_diagrams_reconstructed.md` §Appendix |
| Safety properties | `architecture.md` §Key Safety Arguments | `HAGroup_text.md` §Ensuring Consistency |
| Anti-flapping | `state-machines.md` §6 | `HAGroup_text.md` §Avoiding flapping |
| Concurrency control | `state-machines.md` §7 | `HAGroup_text.md` §Problem 1, §Problem 2 |
| Forced failover | `state-machines.md` §8 | `Consistent_Failover_text.md` §Forced Failover |
| Writer state machine | `state-machines.md` §4 | `Consistent_Failover_text.md` §State Tracking |
| Component architecture | `architecture.md` §Components | `Consistent_Failover_text.md` §High Level Components |
| HDFS layout | `architecture.md` §HDFS Directory Layout | `Consistent_Failover_text.md` §Phoenix Synchronous Replication |
| Record structure | `architecture.md` §HA Group Record | `HAGroup_text.md` §Specs |
| API signatures | `HAGroup_text.md` §Specs | — |
| Design trade-offs | `HAGroup_tables.md` | `HAGroup_text.md` §Proposed Solutions |
| Design discussions | `Consistent_Failover_comments.md` | `HAGroup_comments.md` |
| Diagram raw data | `*_diagrams.json` | `*_diagram_contexts.json` |

### 16.2 Key Diagram Locations

| Diagram | File | Section |
|---------|------|---------|
| Top-level role state machine | `Consistent_Failover_diagrams_reconstructed.md` §paragraph 341 | Shows all 8 roles with transitions |
| Active cluster sub-state machine | `HAGroup_diagrams_reconstructed.md` §Active Cluster STD | 10 states with all transitions |
| Standby cluster sub-state machine | `HAGroup_diagrams_reconstructed.md` §Standby Cluster STD | 7 states with all transitions |
| Combined product state machine | `HAGroup_diagrams_reconstructed.md` §Appendix | Latest version with all events |
| Writer SYNC/S&F/SYNC&FWD | `Consistent_Failover_diagrams_reconstructed.md` §State Tracking | 3-state writer machine |
| Race condition illustration | `HAGroup_diagrams_reconstructed.md` §Problem 1 | Multiple RS concurrent updates |
| Anti-flapping timeline | `HAGroup_diagrams_reconstructed.md` §Avoiding flapping | t0-t5 timeline with rejections |
| Architecture overview | `HAGroup_diagrams_reconstructed.md` §Overall Design | All components with data flow |
| High-level components | `Consistent_Failover_diagrams_reconstructed.md` §High Level Components | Writer/Reader/Manager/Client |

### 16.3 Implementation Cross-References

For detailed cross-referencing between design concepts and implementation
source code, see **[IMPL_CROSS_REFERENCE.md](IMPL_CROSS_REFERENCE.md)**.
Key sections:

| Concern | IMPL_CROSS_REFERENCE Section |
|---------|------|
| State enum definition vs design | §2.1 |
| Implemented transition table | §2.2 |
| Reactive transitions (automated) | §2.5 |
| Event-to-implementation mapping | §3.4 |
| Anti-flapping implementation | §5 |
| Failover orchestration steps | §6 |
| Writer mutation capture pipeline | §7.1 |
| Reader round-based processing | §8 |
| Design-implementation divergences | §11 |
| Commit history by component | §12 |
| Updated TLA+ variables/properties | §14 |

### 16.4 JIRA References

| Issue | Description | Relevance |
|-------|-------------|-----------|
| PHOENIX-7562 | Feature branch: Consistent Failover | Umbrella feature branch |
| PHOENIX-7565 | Replication log file format | Log writer/reader classes |
| PHOENIX-7566 | HAGroupStoreManager and HAGroupStoreClient | Primary HA state management |
| PHOENIX-7567 | Replication Log Writer (Synchronous mode) | SYNC mode implementation |
| PHOENIX-7568 | Replication Log Replay Implementation | Reader/replay pipeline |
| PHOENIX-7569 | Enhanced Dual Cluster Client | Client-side HA |
| PHOENIX-7601 | Synchronous replication in Phoenix coprocs | Mutation capture hooks |
| PHOENIX-7602 | Replication Log Writer (Store and Forward) | S&F mode + heartbeat |
| PHOENIX-7632 | ReplicationLogProcessor | Mutation replay engine |
| PHOENIX-7640 | Refactor ReplicationLog for HA Groups | HA group awareness |
| PHOENIX-7669 | Unclosed file handling | Crash recovery |
| PHOENIX-7672 | Failover via replay + Lease Recovery | Failover trigger + HDFS recovery |
| PHOENIX-7719 | Prewarm HAGroupStore Client | Startup optimization |
| PHOENIX-7721 | Admin Tool for Consistent Failover | CLI failover/abort commands |
| PHOENIX-7755 | Consistency Point in Replay | Consistency point calculation |
| PHOENIX-7763 | HAGroupName in URLs | Backward compatibility |
| PHOENIX-7767/68 | Default states for ACTIVE/STANDBY + HDFS URLs | Initial state + config |
| PHOENIX-7775 | ReplicationLogGroup init fixes | Bug fix |
| PHOENIX-7786 | Empty files in Replication Log Processor | Bug fix |
| PHOENIX-7493 | Initial HAGroupStoreManager implementation | Base implementation |
| ZOOKEEPER-4743 | ZK version -1 wildcard issue | Must be backported; affects concurrency model |
| HBASE-24304 | hbase-asyncfs module | Single-block file optimization for log writer |
