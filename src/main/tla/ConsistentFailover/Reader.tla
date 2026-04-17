-------------------------- MODULE Reader ----------------------------------------
(*
 * Replication replay state machine for the Phoenix Consistent
 * Failover specification.
 *
 * The standby cluster's reader replays replication logs round-by-round,
 * tracking two counters (lastRoundProcessed, lastRoundInSync) and a
 * replay state that determines how the counters advance. Transitions
 * are driven by local HA group state changes (S -> DS triggers
 * degradation, DS -> S triggers recovery) and by the replay() loop
 * itself (SYNCED_RECOVERY -> SYNC after rewind).
 *
 * REPLAY STATE SEMANTICS:
 *   SYNC:             Both counters advance together (in-sync replay).
 *   DEGRADED:         Only lastRoundProcessed advances; lastRoundInSync
 *                     is frozen (degraded replay).
 *   SYNCED_RECOVERY:  Rewinds lastRoundProcessed to lastRoundInSync,
 *                     then CAS-transitions to SYNC.
 *   NOT_INITIALIZED:  Pre-init; transitions to SYNCED_RECOVERY or
 *                     DEGRADED on first local state observation.
 *
 * TRANSITION TRIGGERS: Replay state transitions are driven by *local*
 * HA group state changes, not direct peer detection. Both the
 * degradedListener and recoveryListener use unconditional .set()
 * (not .compareAndSet()), so they can overwrite any prior replay state.
 *
 * CAS SEMANTICS: The SYNCED_RECOVERY -> SYNC transition uses
 * compareAndSet(SYNCED_RECOVERY, SYNC) at L332-333. The CAS can
 * only fail if a concurrent set(DEGRADED) fires first. TLC's
 * interleaving semantics model this race: either ReplayRewind fires
 * first (CAS succeeds) or ReplayDetectDegraded fires first (state
 * becomes DEGRADED, ReplayRewind is no longer enabled).
 *
 * Implementation traceability:
 *
 *   TLA+ action                | Java source
 *   --------------------------+--------------------------------------------
 *   ReplayAdvance(c)          | replay() L336-343 (SYNC) and L345-351
 *                             |   (DEGRADED) — round processing loop
 *   ReplayDetectDegraded(c)   | degradedListener L136-145 —
 *                             |   replicationReplayState.set(DEGRADED)
 *   ReplayDetectRecovery(c)   | recoveryListener L147-157 —
 *                             |   replicationReplayState.set(
 *                             |   SYNCED_RECOVERY)
 *   ReplayRewind(c)           | replay() L323-333 —
 *                             |   compareAndSet(SYNCED_RECOVERY, SYNC);
 *                             |   getFirstRoundToProcess() rewinds to
 *                             |   lastRoundInSync (L389)
 *   ReplayBeginProcessing(c)  | replay() round processing start —
 *                             |   in-progress files created when a
 *                             |   round is picked up for processing
 *   ReplayFinishProcessing(c) | replay() round processing end —
 *                             |   in-progress files cleaned up after
 *                             |   round is fully processed
 *   TriggerFailover(c)        | shouldTriggerFailover() L500-533 (guards);
 *                             |   triggerFailover() L535-548 (effect);
 *                             |   setHAGroupStatusToSync() L341-355
 *                             |   (ZK write)
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
         replayState, lastRoundInSync, lastRoundProcessed,
         failoverPending, inProgressDirEmpty

---------------------------------------------------------------------------

(*
 * Replay advance in SYNC state: both counters advance together.
 *
 * The reader processes the next round of replication logs. In SYNC
 * state, both lastRoundProcessed and lastRoundInSync advance,
 * maintaining the invariant that they are equal.
 *
 * Guard: cluster is in a standby state or STA (replay continues
 * during failover pending) and replay is in SYNC.
 *
 * Source: replay() L336-343
 *)
ReplayAdvance(c) ==
    \* STA included: replay() loop continues during failover pending.
    \* Without STA, TriggerFailover's inProgressDirEmpty guard is
    \* over-approximated.
    /\ clusterState[c] \in StandbyStates \union {"STA"}
    /\ replayState[c] = "SYNC"
    /\ lastRoundProcessed' = [lastRoundProcessed EXCEPT ![c] = @ + 1]
    /\ lastRoundInSync' = [lastRoundInSync EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, replayState, failoverPending,
                   inProgressDirEmpty>>

---------------------------------------------------------------------------

(*
 * Detect degradation: transition to DEGRADED when local state is DS.
 *
 * When the cluster enters DEGRADED_STANDBY (reacting to peer ANIS),
 * the degradedListener fires replicationReplayState.set(DEGRADED).
 * This is an unconditional set() — it overwrites any prior replay
 * state, including SYNCED_RECOVERY (modeling the re-degradation
 * interleaving at L141).
 *
 * In DEGRADED state, only lastRoundProcessed advances; lastRoundInSync
 * is frozen. This action combines the state transition with one round
 * of degraded replay.
 *
 * Guard: cluster is in DS and replay is in a state that can degrade.
 *
 * Source: degradedListener L136-145 —
 *         replicationReplayState.set(DEGRADED)
 *)
ReplayDetectDegraded(c) ==
    /\ clusterState[c] = "DS"
    /\ replayState[c] \in {"NOT_INITIALIZED", "SYNC", "SYNCED_RECOVERY"}
    /\ replayState' = [replayState EXCEPT ![c] = "DEGRADED"]
    /\ lastRoundProcessed' = [lastRoundProcessed EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, lastRoundInSync, failoverPending,
                   inProgressDirEmpty>>

---------------------------------------------------------------------------

(*
 * Detect recovery: transition to SYNCED_RECOVERY when local state is S.
 *
 * When the cluster returns to STANDBY (peer recovered to AIS), the
 * recoveryListener fires replicationReplayState.set(SYNCED_RECOVERY).
 * This is an unconditional set() — it overwrites any prior state.
 *
 * No counter changes occur at this point — the rewind happens in
 * ReplayRewind when replay() processes the SYNCED_RECOVERY state.
 *
 * Guard: cluster is in S and replay is in a state that can recover.
 *
 * Source: recoveryListener L147-157 —
 *         replicationReplayState.set(SYNCED_RECOVERY)
 *)
ReplayDetectRecovery(c) ==
    /\ clusterState[c] = "S"
    /\ replayState[c] \in {"NOT_INITIALIZED", "DEGRADED"}
    /\ replayState' = [replayState EXCEPT ![c] = "SYNCED_RECOVERY"]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, lastRoundProcessed, lastRoundInSync,
                   failoverPending, inProgressDirEmpty>>

---------------------------------------------------------------------------

(*
 * Replay rewind and CAS to SYNC from SYNCED_RECOVERY.
 *
 * In SYNCED_RECOVERY, replay() rewinds lastRoundProcessed to
 * lastRoundInSync (via getFirstRoundToProcess() at L389), then
 * attempts compareAndSet(SYNCED_RECOVERY, SYNC) at L332-333.
 *
 * The CAS can only fail if a concurrent set(DEGRADED) fires first
 * (the cluster re-degrades before replay() can CAS). TLC's
 * interleaving semantics model this race naturally: either this
 * action fires (CAS succeeds, state becomes SYNC) or
 * ReplayDetectDegraded fires first (state becomes DEGRADED,
 * this action is no longer enabled).
 *
 * Source: replay() L323-333 — compareAndSet(SYNCED_RECOVERY, SYNC);
 *         getFirstRoundToProcess() L389 — rewinds to lastRoundInSync
 *)
ReplayRewind(c) ==
    /\ replayState[c] = "SYNCED_RECOVERY"
    /\ replayState' = [replayState EXCEPT ![c] = "SYNC"]
    /\ lastRoundProcessed' = [lastRoundProcessed EXCEPT ![c] = lastRoundInSync[c]]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, lastRoundInSync, failoverPending,
                   inProgressDirEmpty>>

---------------------------------------------------------------------------

(*
 * Begin round processing: in-progress directory becomes non-empty.
 *
 * When the reader picks up a new round for processing, it creates
 * in-progress files in the IN-PROGRESS directory. This makes the
 * directory non-empty, blocking the failover trigger until
 * processing completes.
 *
 * Guard: cluster is in a standby state or STA (replay continues
 * during failover pending — the replay() loop does not stop when
 * the cluster enters STA) and the in-progress directory is
 * currently empty.
 *
 * Source: replay() L307-310 — getFirstRoundToProcess() returns a
 *         round; processing begins, creating in-progress files.
 *)
ReplayBeginProcessing(c) ==
    \* STA included: replay() loop continues during failover pending,
    \* so new rounds can begin processing in STA, temporarily setting
    \* inProgressDirEmpty = FALSE and blocking TriggerFailover.
    /\ clusterState[c] \in StandbyStates \union {"STA"}
    /\ inProgressDirEmpty[c] = TRUE
    /\ inProgressDirEmpty' = [inProgressDirEmpty EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, replayState, lastRoundInSync,
                   lastRoundProcessed, failoverPending>>

---------------------------------------------------------------------------

(*
 * Finish round processing: in-progress directory becomes empty.
 *
 * When the reader finishes processing a round, it cleans up
 * in-progress files. The directory becomes empty, allowing the
 * failover trigger to proceed (if other guards are satisfied).
 *
 * Guard: in-progress directory is currently non-empty.
 *
 * Source: replay() L336-351 — round processing completes,
 *         in-progress files are cleaned up.
 *)
ReplayFinishProcessing(c) ==
    /\ inProgressDirEmpty[c] = FALSE
    /\ inProgressDirEmpty' = [inProgressDirEmpty EXCEPT ![c] = TRUE]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, hdfsAvailable,
                   antiFlapTimer, replayState, lastRoundInSync,
                   lastRoundProcessed, failoverPending>>

---------------------------------------------------------------------------

(*
 * Failover trigger: STA → AIS when replay is complete.
 *
 * The standby cluster writes ACTIVE_IN_SYNC to its own ZK znode
 * after the replication log reader determines replay is complete.
 * This is driven by the reader component, not a peer-reactive
 * transition.
 *
 * Four guards model the conditions under which failover is safe:
 *   1. failoverPending[c] — set by triggerFailoverListener (L159-171)
 *      when the local cluster enters STA.
 *   2. inProgressDirEmpty[c] — no partially-processed replication
 *      log files (getInProgressFiles().isEmpty() at L508).
 *   3. replayState[c] = "SYNC" — the SYNCED_RECOVERY rewind must
 *      have completed. Without this guard, failover could proceed
 *      with degraded rounds not re-processed from the sync point.
 *   4. hdfsAvailable[c] = TRUE — the standby's own HDFS must be
 *      accessible; shouldTriggerFailover() performs HDFS reads
 *      (getInProgressFiles, getNewFiles) that throw IOException
 *      if HDFS is unavailable, blocking the trigger.
 *
 * The effect also clears failoverPending, modeling triggerFailover()
 * L538 (failoverPending.set(false)).
 *
 * Source: shouldTriggerFailover() L500-533 (guards);
 *         triggerFailover() L535-548 (effect);
 *         setHAGroupStatusToSync() L341-355 (ZK write)
 *)
TriggerFailover(c) ==
    /\ clusterState[c] = "STA"
    /\ failoverPending[c]
    /\ inProgressDirEmpty[c]
    /\ replayState[c] = "SYNC"
    /\ hdfsAvailable[c] = TRUE
    /\ clusterState' = [clusterState EXCEPT ![c] = "AIS"]
    /\ failoverPending' = [failoverPending EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
                   replayState, lastRoundInSync, lastRoundProcessed,
                   inProgressDirEmpty>>

============================================================================
