# Phoenix Consistent Failover — TLA+ Specification Reference

Reference material extracted from the Phoenix HA design documents, organized for
developing a TLA+ formal specification of the failover protocol.

## Source Documents

| Document | Description | Authors |
|----------|-------------|---------|
| `Consistent_Failover.docx` | Top-level HA rearchitecture design; defines cluster role states, synchronous replication, replication log format, failover process, components | Kadir Ozdemir, Andrew Purtell |
| `HAGroup.docx` | Detailed design for HAGroupStoreManager/Client; defines fine-grained state machines, combined state transitions, concurrency control, anti-flapping protocol | Ritesh Garg |

## File Index

### Primary Reference (start here)

- **[state-machines.md](state-machines.md)** — All state machines extracted from both documents:
  1. Cluster HA role state machine (top-level)
  2. Active cluster sub-state machine (AIS/ANIS/ATS/ANISTS/AbTAIS/AbTANIS/AWOP/ANISWOP)
  3. Standby cluster sub-state machine (S/STA/AIS/AbTS/DS/DSFW/DSFR/OFFLINE)
  4. Replication log writer state machine (SYNC/S&F/SYNC&FWD)
  5. Combined (product) state transition diagram with actors and events
  6. Anti-flapping protocol with timing constraints
  7. ZK optimistic locking concurrency control
  8. Forced failover semantics

- **[architecture.md](architecture.md)** — Component descriptions, HDFS directory layout,
  replication log format, HA group record structure, safety arguments

### Full Document Text

- **[Consistent_Failover_text.md](Consistent_Failover_text.md)** — Complete text extraction
- **[HAGroup_text.md](HAGroup_text.md)** — Complete text extraction

### Diagrams

- **[HAGroup_diagrams_reconstructed.md](HAGroup_diagrams_reconstructed.md)** — 9 diagrams from HAGroup.docx
  reconstructed from DrawingML vector shapes (state nodes, labels, colors)
- **[Consistent_Failover_diagrams_reconstructed.md](Consistent_Failover_diagrams_reconstructed.md)** — 8 diagrams
  from Consistent_Failover.docx reconstructed from DrawingML vector shapes
- **[HAGroup_diagrams.json](HAGroup_diagrams.json)** — Raw structured diagram data (JSON) with
  shape geometry, positions, fill colors, connector endpoints
- **[Consistent_Failover_diagrams.json](Consistent_Failover_diagrams.json)** — Raw structured diagram data (JSON)

### Implementation Cross-Reference (start here for TLA+ after reading state-machines.md)

- **[IMPL_CROSS_REFERENCE.md](IMPL_CROSS_REFERENCE.md)** — Comprehensive cross-reference between
  design documents and implementation sources on the `PHOENIX-7562-feature-new` branch:
  1. Implementation inventory (~135 files, ~54K LOC)
  2. State machine cross-reference (design states vs `HAGroupState` enum)
  3. Design-to-implementation mapping (actors, events, components)
  4. Concurrency control cross-reference (ZK optimistic locking details)
  5. Anti-flapping protocol cross-reference (timing parameters, implementation details)
  6. Failover orchestration cross-reference (step-by-step design vs impl)
  7. Replication writer and reader cross-reference
  8. HDFS directory management cross-reference
  9. Divergences and clarifications (11 documented differences)
  10. Commit history index (30 commits mapped to design sections)
  11. Updated TLA+ modeling implications (corrected variables, properties, simplifications)

### Supplementary

- **[HAGroup_tables.md](HAGroup_tables.md)** — Comparison table: centralized vs decentralized ZK approaches
- **[Consistent_Failover_comments.md](Consistent_Failover_comments.md)** — 63 review comments (design discussions)
- **[HAGroup_comments.md](HAGroup_comments.md)** — 3 review comments

### Diagram Context Map

- **[HAGroup_diagram_contexts.json](HAGroup_diagram_contexts.json)** — Maps each diagram to its
  location in the document and surrounding heading context
- **[Consistent_Failover_diagram_contexts.json](Consistent_Failover_diagram_contexts.json)** — Same for CF doc

## Key Concepts for TLA+ Modeling

### Variables to Model

- `clusterState[c]` — state of each cluster (c in {C1, C2})
- `writerMode[c][rs]` — replication writer mode per region server
- `readerLag[c]` — replication reader lag status
- `outDirEmpty[c]` — whether OUT directory is empty
- `replayComplete[c]` — whether all logs replayed on standby
- `lastSyncTime[c]` — timestamp of last sync replication
- `replayState[c]` — ⚠ IMPL: replay state machine (`SYNC`/`DEGRADED`/`SYNCED_RECOVERY`)
- `lastRoundInSync[c]` — ⚠ IMPL: frozen during degraded periods
- `lastRoundProcessed[c]` — ⚠ IMPL: advances continuously
- `failoverPending[c]` — ⚠ IMPL: set by `STANDBY_TO_ACTIVE` listener
- `inProgressDirEmpty[c]` — ⚠ IMPL: guard for failover trigger
- `zkMtime[c]` — ⚠ IMPL: ZK znode mtime for anti-flapping gate

### Safety Properties to Verify

1. **Mutual Exclusion**: `~(clusterState[C1] in ActiveStates /\ clusterState[C2] in ActiveStates)`
   where ActiveStates = {AIS, ANIS, AbTAIS, AbTANIS, AWOP, ANISWOP}
2. **No Data Loss**: Before `STA -> AIS`, all logs replayed
3. ~~**Atomic Failover**: `(ATS, A*) -> (S, AIS)` is a single atomic step~~
   **Non-atomic failover safety** (⚠ IMPL): Two separate ZK writes — verify
   mutual exclusion during window (ATS role = `ACTIVE_TO_STANDBY`, not Active)
4. **Abort Safety**: Abort from STA side prevents dual-active
5. **Failover Trigger Correctness** (⚠ IMPL): `failoverPending ∧ inProgressDirEmpty
   ∧ lastRoundProcessed ≥ lastRoundInSync`

### Liveness Properties to Verify

1. **Failover Progress**: Initiated failover eventually completes (or is aborted)
2. **Recovery**: Degraded state eventually recovers to normal (assuming faults heal)
3. **Anti-Flapping**: ANIS/AIS oscillation is bounded

### Actors to Model

- **Admin (A)**: Initiates/aborts failover
- **ReplicationLogWriter (W)**: Detects sync/async mode per RS
- **ReplicationLogReader (R)**: Replays logs, reports completion/lag
- **HAGroupStoreManager (M1, M2)**: Reacts to peer state changes via ZK watchers

### Key Timing Parameters

- `ZK_Session_Timeout` (N): Design 60s × 1.1 = 66s; ⚠ IMPL: 90s × 1.1 = 99s
- Writer heartbeat interval: Design N/2 = 33s; ⚠ IMPL: N × 0.7 = 63s
- Log file rotation: configurable, default 1 minute (or 95% of max file size)
- Non-active record TTL: configurable

## Critical Implementation Divergences (⚠ IMPL)

These are the most significant differences between the design documents and the
actual implementation on `PHOENIX-7562-feature-new`. All TLA+ modeling should
account for these. Full details in `IMPL_CROSS_REFERENCE.md` §11.

1. **Non-atomic final failover step** (§11.3): Design says atomic ZK multi-op;
   implementation uses two separate ZK writes. Safety relies on ATS role
   blocking mutations.
2. **Collapsed degraded sub-states** (§11.1): Design has DSFW/DSFR/DS;
   implementation uses single `DEGRADED_STANDBY`.
3. **OFFLINE is a sink state** (§11.2): Design allows `OFFLINE → STANDBY`;
   implementation has no outbound transitions from OFFLINE.
4. **Replay state machine (SM6)** (§2.4): Implementation-only state machine
   (`SYNC`/`DEGRADED`/`SYNCED_RECOVERY`) managing consistency points.
5. **ANIS self-transition** (§11.4): Implementation heartbeat refreshes ZK mtime
   without changing state (essential for anti-flapping).
6. **Default initial states** (§11.6): Active starts as `ANIS` (not AIS);
   Standby starts as `DEGRADED_STANDBY` (not S).
7. **Three-condition failover trigger** (§8.3): `STA → AIS` requires
   `failoverPending ∧ inProgressDirEmpty ∧ noNewFilesInWindow`.
8. **Writer fail-stop** (§7.4): S&F write error → RS abort (no further fallback).
9. **Post-batch mutation capture** (§7.1): Captures after WAL commit, not before.
10. **Timing parameter differences** (§5): Heartbeat 0.7× (not 0.5×), ZK timeout
    90s (not 60s).

## Design Discussion Threads

The `Consistent_Failover_comments.md` file contains 63 review comments from the design
review process. Notable threads:

- **RTO/RPO targets**: "100% consistency, less than two minutes of RTO, and zero RPO"
- **Forced failover semantics**: Discussion of degraded standby failover trade-offs
- **Store-and-forward mode**: How RS-level mode changes propagate to cluster state
- **Anti-flapping**: ZK session timeout as the stability window
- **Abort protocol**: Why abort must originate from STA side
