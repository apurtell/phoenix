-------------------- MODULE ConsistentFailover --------------------------------
(*
 * TLA+ specification of the Phoenix Consistent Failover protocol.
 *
 * Root orchestrator module: declares variables, defines Init, Next,
 * Spec, invariants, and action constraints. Composes actor-driven
 * actions from sub-modules via INSTANCE.
 *
 * Models the HA group state machine for two paired Phoenix/HBase
 * clusters. Each cluster maintains an HA group state in ZooKeeper.
 * State transitions are driven by admin actions, peer-reactive
 * listeners, writer/reader state changes, and HDFS availability
 * incidents.
 *
 * ZK WATCHER DELIVERY: Peer-reactive transitions and auto-
 * completion transitions depend on ZooKeeper watcher notification
 * chains (peerPathChildrenCache for peer detection, pathChildrenCache
 * for local auto-completion). ZK does NOT formally guarantee
 * unconditional delivery. In this model, peer-reactive actions fire
 * whenever their guard is satisfied. TLC's interleaving semantics
 * cover arbitrary delivery delay (safety verification is sound).
 *
 * Sub-modules:
 *   - Admin.tla: operator-initiated failover/abort
 *   - Clock.tla: anti-flapping countdown timer (Tick action)
 *   - HAGroupStore.tla: peer-reactive transitions, auto-completion
 *   - HDFS.tla: HDFS availability incident actions
 *   - Reader.tla: standby-side replication replay state machine
 *   - RS.tla: RS lifecycle (restart after abort)
 *   - Writer.tla: per-RS replication writer mode state machine
 *
 * Implementation traceability:
 *
 *   Modeled concept         | Java class / field
 *   ------------------------+---------------------------------------------
 *   clusterState            | HAGroupStoreRecord per-cluster ZK znode
 *   PeerReact* actions      | FailoverManagementListener
 *                           |   (HAGroupStoreManager.java L633-706)
 *                           |   Delivered via peerPathChildrenCache
 *                           |   (ZK watcher — conditional delivery)
 *   TriggerFailover          | Reader.TriggerFailover — guarded
 *                           |   STA→AIS via shouldTriggerFailover()
 *                           |   L500-533 + triggerFailover() L535-548
 *   AutoComplete            | createLocalStateTransitions() L140-150
 *                           |   Delivered via local pathChildrenCache
 *                           |   (ZK watcher — conditional delivery)
 *   AdminStartFailover      | initiateFailoverOnActiveCluster() L375-400
 *   AdminAbortFailover      | setHAGroupStatusToAbortToStandby() L419-425
 *   Init (AIS, S)           | Default initial states per team confirmation
 *                           |   (see PHOENIX_HA_TLA_PLAN.md Appendix A.6)
 *   MutualExclusion         | Architecture safety argument: at most one
 *                           |   cluster in ACTIVE role at any time
 *   NonAtomicFailoverSafe   | Safety during (ATS, AIS) window;
 *                           |   isMutationBlocked()=true for ATS
 *   AbortSafety             | Abort originates from STA side; AbTAIS
 *                           |   only reachable via peer AbTS detection
 *   AllowedTransitions      | HAGroupStoreRecord.java L99-123
 *   writerMode              | ReplicationLogGroup per-RS mode
 *                           |   (SYNC/STORE_AND_FWD/SYNC_AND_FWD)
 *   outDirEmpty             | ReplicationLogDiscoveryForwarder
 *                           |   .processNoMoreRoundsLeft() L155-184
 *                           |   Boolean: OUT dir empty/non-empty
 *   hdfsAvailable           | Abstract: NameNode availability per cluster
 *                           |   (no explicit field in implementation;
 *                           |   detected via IOException)
 *   HDFSDown/HDFSUp         | NameNode crash/recovery incidents;
 *                           |   SyncModeImpl.onFailure() L61-74
 *   antiFlapTimer           | Countdown timer (Lamport CHARME 2005);
 *                           |   models validateTransitionAndGetWait-
 *                           |   Time() L1027-1046 anti-flapping gate
 *   Tick                    | Passage of wall-clock time
 *   ANISHeartbeat           | StoreAndForwardModeImpl
 *                           |   .startHAGroupStoreUpdateTask() L71-87
 *   replayState             | ReplicationLogDiscoveryReplay replay state
 *                           |   (NOT_INITIALIZED/SYNC/DEGRADED/
 *                           |   SYNCED_RECOVERY)
 *   lastRoundInSync         | ReplicationLogDiscoveryReplay L336-343
 *   lastRoundProcessed      | ReplicationLogDiscoveryReplay L336-351
 *   failoverPending         | ReplicationLogDiscoveryReplay L159-171
 *   inProgressDirEmpty      | ReplicationLogDiscoveryReplay L500-533
 *   ReplayAdvance           | replay() L336-343 (SYNC round processing)
 *   ReplayDetectDegraded    | degradedListener L136-145
 *   ReplayDetectRecovery    | recoveryListener L147-157
 *   ReplayRewind            | replay() L323-333 (CAS to SYNC)
 *   TriggerFailover         | shouldTriggerFailover() L500-533 +
 *                           |   triggerFailover() L535-548
 *   FailoverTriggerCorrectness | Action constraint: STA→AIS requires
 *                           |   failoverPending ∧ inProgressDirEmpty
 *                           |   ∧ replayState = SYNC
 *   NoDataLoss              | Action constraint: zero RPO property
 *                           |   (logically equivalent to Failover-
 *                           |   TriggerCorrectness in Iteration 11)
 *
 * failoverPending lifecycle:
 *   Set TRUE:  PeerReactToATS (HAGroupStore.tla)
 *   Set FALSE: TriggerFailover (Reader.tla)
 *   Set FALSE: AdminAbortFailover (Admin.tla)
 *)
EXTENDS Types

---------------------------------------------------------------------------

(* Variables *)

\* clusterState[c] is the current HA group state of cluster c.
\* Each cluster maintains its state as a ZK znode, updated via
\* setData().withVersion() (optimistic locking).
\*
\* Source: HAGroupStoreRecord per-cluster ZK znode at
\*         phoenix/consistentHA/<group>
VARIABLE clusterState

\* writerMode[c][rs] is the current replication writer mode of
\* region server rs on cluster c.
\*
\* Source: ReplicationLogGroup per-RS mode (SyncModeImpl,
\*         StoreAndForwardModeImpl, SyncAndForwardModeImpl)
VARIABLE writerMode

\* outDirEmpty[c] is TRUE when the OUT directory on cluster c
\* contains no buffered replication log files (all forwarded or
\* never written). FALSE when writes are accumulating locally.
\*
\* Source: ReplicationLogDiscoveryForwarder.processNoMoreRoundsLeft()
\*         L155-184 checks getInProgressFiles().isEmpty() &&
\*         getNewFilesForRound(nextRound).isEmpty()
VARIABLE outDirEmpty

\* hdfsAvailable[c] is TRUE when cluster c's HDFS (NameNode) is
\* accessible to its peer cluster's writers. FALSE after a NameNode
\* crash. Not explicitly tracked in the implementation — detected
\* reactively via IOException from HDFS write operations.
VARIABLE hdfsAvailable

\* antiFlapTimer[c] is the per-cluster anti-flapping countdown timer.
\* Counts down from WaitTimeForSync toward 0. The ANIS -> AIS
\* transition is blocked while the timer is positive (gate closed).
\* The S&F heartbeat resets the timer to WaitTimeForSync; the Tick
\* action decrements it. See Types.tla for helper operator docs.
\*
\* Modeled via Lamport's countdown timer pattern from "Real Time
\* is Really Simple" (CHARME 2005).
\*
\* Source: HAGroupStoreClient.validateTransitionAndGetWaitTime()
\*         L1027-1046
VARIABLE antiFlapTimer

\* replayState[c] is the current replication replay state of
\* cluster c's reader. Tracks the standby-side replay progress
\* through four states: NOT_INITIALIZED, SYNC, DEGRADED,
\* SYNCED_RECOVERY.
\*
\* Source: ReplicationLogDiscoveryReplay.java L550-555
VARIABLE replayState

\* lastRoundInSync[c] is the last replication round number that
\* was processed while the reader was in SYNC state. Frozen during
\* DEGRADED periods; used as the rewind target during recovery.
\*
\* Source: ReplicationLogDiscoveryReplay.java L336-343 (advance),
\*         L389 (rewind target via getFirstRoundToProcess())
VARIABLE lastRoundInSync

\* lastRoundProcessed[c] is the last replication round number
\* processed by the reader, regardless of replay state. Advances
\* in both SYNC and DEGRADED states; rewinds to lastRoundInSync
\* during SYNCED_RECOVERY.
\*
\* Source: ReplicationLogDiscoveryReplay.java L336-351
VARIABLE lastRoundProcessed

\* failoverPending[c] is TRUE when the standby cluster has received
\* a STANDBY_TO_ACTIVE notification and is waiting for replay to
\* complete before triggering failover. Set by the STA listener;
\* cleared by ABORT_TO_STANDBY listener.
\*
\* Source: ReplicationLogDiscoveryReplay.java L159-171 (set true),
\*         L173-185 (set false)
VARIABLE failoverPending

\* inProgressDirEmpty[c] is TRUE when the IN-PROGRESS directory on
\* cluster c contains no partially-processed replication log files.
\* Required for failover trigger completeness.
\*
\* Source: ReplicationLogDiscoveryReplay.shouldTriggerFailover()
\*         L500-533
VARIABLE inProgressDirEmpty

\* Tuple of all variables for use in temporal formulas.
vars == <<clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
          replayState, lastRoundInSync, lastRoundProcessed,
          failoverPending, inProgressDirEmpty>>

---------------------------------------------------------------------------

(* Sub-module instances *)

\* Peer-reactive transitions and auto-completion.
haGroupStore == INSTANCE HAGroupStore

\* Operator-initiated failover and abort.
admin == INSTANCE Admin

\* Per-RS replication writer mode state machine.
writer == INSTANCE Writer

\* HDFS availability incident actions.
hdfs == INSTANCE HDFS

\* RS lifecycle (restart after abort).
rs == INSTANCE RS

\* Anti-flapping countdown timer.
clk == INSTANCE Clock

\* Replication replay state machine (standby-side reader).
reader == INSTANCE Reader

---------------------------------------------------------------------------

(* Initial state *)

\* The system starts with one cluster active and in sync (AIS)
\* and the other in standby (S). The choice of which cluster is
\* active is deterministic: CHOOSE picks an arbitrary but fixed
\* element of Cluster as the initial active.
Init ==
    \* Deterministically assign one cluster to AIS and the other to S.
    \* CHOOSE x \in Cluster : TRUE picks an arbitrary fixed cluster.
    LET active == CHOOSE x \in Cluster : TRUE
    IN /\ clusterState = [c \in Cluster |->
                            IF c = active THEN "AIS" ELSE "S"]
       /\ writerMode = [c \in Cluster |-> [r \in RS |-> "INIT"]]
       /\ outDirEmpty = [c \in Cluster |-> TRUE]
       /\ hdfsAvailable = [c \in Cluster |-> TRUE]
       /\ antiFlapTimer = [c \in Cluster |-> 0]
       /\ replayState = [c \in Cluster |-> "NOT_INITIALIZED"]
       /\ lastRoundInSync = [c \in Cluster |-> 0]
       /\ lastRoundProcessed = [c \in Cluster |-> 0]
       /\ failoverPending = [c \in Cluster |-> FALSE]
       /\ inProgressDirEmpty = [c \in Cluster |-> TRUE]

---------------------------------------------------------------------------

(* Next-state relation *)

(*
 * In each step, exactly one cluster performs one actor-driven action.
 * Actions are factored by actor:
 *   - haGroupStore: peer-reactive transitions and auto-completion
 *     (FailoverManagementListener + local resolvers)
 *     ALL of these depend on ZK watcher notification chains for
 *     delivery. See HAGroupStore.tla module header for details.
 *   - admin: operator-initiated failover and abort
 *     These are direct ZK writes (not watcher-dependent).
 *   - hdfs: HDFS NameNode crash/recovery incidents
 *     HDFSDown sets the availability flag; per-RS degradation
 *     is handled by writer actions with CAS success/failure.
 *   - writer: per-RS writer mode transitions (startup, recovery,
 *     drain complete, HDFS failure degradation, CAS failure)
 *   - rs: RS lifecycle (restart after abort)
 *
 * Each action encodes the precise guard (peer state or local state)
 * under which the transition fires, modeling the implementation's
 * actual trigger conditions.
 *)
Next ==
    \* [Timer] Anti-flapping countdown timer tick (global).
    \/ clk!Tick
    \/ \E c \in Cluster :
        \* [ZK watcher] Peer-reactive: standby detects peer ATS.
        \/ haGroupStore!PeerReactToATS(c)
        \* [ZK watcher] Peer-reactive: cluster detects peer ANIS.
        \/ haGroupStore!PeerReactToANIS(c)
        \* [ZK watcher] Peer-reactive: active detects peer AbTS.
        \/ haGroupStore!PeerReactToAbTS(c)
        \* [ZK watcher] Local auto-completion: AbTS->S, etc.
        \/ haGroupStore!AutoComplete(c)
        \* [Reader-driven] Standby completes failover: STA->AIS (guarded).
        \/ reader!TriggerFailover(c)
        \* [ZK watcher] Peer-reactive: cluster detects peer AIS.
        \/ haGroupStore!PeerReactToAIS(c)
        \* [S&F heartbeat] ANIS self-transition: resets anti-flap timer.
        \/ haGroupStore!ANISHeartbeat(c)
        \* [Writer-driven] All RS synced + OUT empty + gate open: ANIS->AIS.
        \/ haGroupStore!ANISToAIS(c)
        \* [Direct ZK write] Admin initiates failover: AIS->ATS.
        \/ admin!AdminStartFailover(c)
        \* [Direct ZK write] Admin aborts failover: STA->AbTS.
        \/ admin!AdminAbortFailover(c)
        \* HDFS NameNode crash/recovery incidents.
        \/ hdfs!HDFSDown(c)
        \/ hdfs!HDFSUp(c)
        \* Standby-side replay state machine (reader).
        \/ reader!ReplayAdvance(c)
        \/ reader!ReplayDetectDegraded(c)
        \/ reader!ReplayDetectRecovery(c)
        \/ reader!ReplayRewind(c)
        \* In-progress directory dynamics (reader round processing).
        \/ reader!ReplayBeginProcessing(c)
        \/ reader!ReplayFinishProcessing(c)
        \* Per-RS writer mode transitions and RS lifecycle.
        \/ \E r \in RS :
            \* Writer startup.
            \/ writer!WriterInit(c, r)
            \/ writer!WriterInitToStoreFwd(c, r)
            \/ writer!WriterInitToStoreFwdFail(c, r)
            \* Writer mode transitions (recovery, drain, forwarder).
            \/ writer!WriterSyncToSyncFwd(c, r)
            \/ writer!WriterStoreFwdToSyncFwd(c, r)
            \/ writer!WriterSyncFwdToSync(c, r)
            \* Per-RS HDFS failure degradation (CAS success).
            \/ writer!WriterToStoreFwd(c, r)
            \/ writer!WriterSyncFwdToStoreFwd(c, r)
            \* Per-RS HDFS failure degradation (CAS failure → DEAD).
            \/ writer!WriterToStoreFwdFail(c, r)
            \/ writer!WriterSyncFwdToStoreFwdFail(c, r)
            \* RS lifecycle: process supervisor restarts dead RS.
            \/ rs!RSRestart(c, r)

---------------------------------------------------------------------------

(* Specification *)

\* Safety specification: initial state, followed by zero or more
\* Next steps (or stuttering). Safety-only model (no fairness).
Spec == Init /\ [][Next]_vars

---------------------------------------------------------------------------

(* Type invariants *)

\* Every cluster's state is a valid HAGroupState.
TypeOK ==
    clusterState \in [Cluster -> HAGroupState]

\* Every RS on every cluster has a valid WriterMode.
WriterTypeOK ==
    writerMode \in [Cluster -> [RS -> WriterMode]]

\* OUT directory emptiness is a boolean per cluster.
OutDirTypeOK ==
    outDirEmpty \in [Cluster -> BOOLEAN]

\* HDFS availability is a boolean per cluster.
HDFSTypeOK ==
    hdfsAvailable \in [Cluster -> BOOLEAN]

\* Anti-flapping countdown timer is in 0..WaitTimeForSync per cluster.
\* Bounded by construction: StartAntiFlapWait sets the max value,
\* DecrementTimer floors at 0.
AntiFlapTimerTypeOK ==
    antiFlapTimer \in [Cluster -> 0..WaitTimeForSync]

\* Replay state, round counters, and failover/IN-progress flags are
\* well-typed per cluster.
ReplayTypeOK ==
    /\ replayState \in [Cluster -> ReplayStateSet]
    /\ lastRoundInSync \in [Cluster -> Nat]
    /\ lastRoundProcessed \in [Cluster -> Nat]
    /\ failoverPending \in [Cluster -> BOOLEAN]
    /\ inProgressDirEmpty \in [Cluster -> BOOLEAN]

---------------------------------------------------------------------------

(* Safety invariants *)

\* Mutual exclusion: two clusters never both in the ACTIVE role
\* simultaneously. This is the primary safety property of the
\* failover protocol.
\*
\* The ACTIVE role includes: AIS, ANIS, AbTAIS, AbTANIS, AWOP,
\* ANISWOP. Transitional states ATS and ANISTS map to the
\* ACTIVE_TO_STANDBY role (not ACTIVE), which is the mechanism
\* by which safety is maintained during the non-atomic failover
\* window — isMutationBlocked()=true for ACTIVE_TO_STANDBY.
\*
\* Source: Architecture safety argument; ClusterRoleRecord.java
\*         L84 — ACTIVE_TO_STANDBY has isMutationBlocked()=true.
MutualExclusion ==
    ~(\E c1, c2 \in Cluster :
        \* Two distinct clusters ...
        /\ c1 # c2
        \* ... both in the ACTIVE role.
        /\ RoleOf(clusterState[c1]) = "ACTIVE"
        /\ RoleOf(clusterState[c2]) = "ACTIVE")

---------------------------------------------------------------------------

(*
 * Abort safety: if a cluster is in AbTAIS (ABORT_TO_ACTIVE_IN_SYNC),
 * the peer must be in AbTS (the abort originator) or S (the peer
 * already auto-completed AbTS->S). This ensures abort was properly
 * initiated from the STA side, preventing dual-active races where
 * both clusters could independently transition toward ACTIVE.
 *
 * The abort protocol is:
 *   (ATS, STA) --[Admin]--> (ATS, AbTS) --[PeerReact]--> (AbTAIS, AbTS)
 *   then auto-complete both sides back to (AIS, S).
 *
 * AbTAIS can only be reached via PeerReactToAbTS, which requires
 * the peer to be in AbTS. The peer can then auto-complete to S
 * before the local AbTAIS auto-completes to AIS, yielding (AbTAIS, S).
 *
 * Source: Architecture safety argument; abort originates from
 *         setHAGroupStatusToAbortToStandby() (L419-425) on the
 *         STA side; active detects via FailoverManagementListener
 *         peer AbTS resolver (L132).
 *)
AbortSafety ==
    \A c \in Cluster :
        clusterState[c] = "AbTAIS" =>
            clusterState[Peer(c)] \in {"AbTS", "S"}

---------------------------------------------------------------------------

(*
 * Non-atomic failover safety: during the window between the new
 * active writing AIS and the old active writing S, mutual exclusion
 * is maintained because ATS maps to role ACTIVE_TO_STANDBY, which
 * is not an active role (isMutationBlocked()=true).
 *
 * This invariant is subsumed by MutualExclusion (which verifies
 * role-level mutual exclusion over all reachable states) but is
 * retained for explicit documentation of the non-atomic window
 * safety argument.
 *
 * Source: ClusterRoleRecord.java L84 —
 *         ACTIVE_TO_STANDBY has isMutationBlocked()=true.
 *)
NonAtomicFailoverSafe ==
    \A c1, c2 \in Cluster :
        /\ c1 # c2
        /\ clusterState[c1] = "ATS"
        /\ clusterState[c2] = "AIS"
        => RoleOf(clusterState[c1]) \notin ActiveRoles

---------------------------------------------------------------------------

(* Action constraints *)

\* Every state change in every step follows the AllowedTransitions
\* table. This is an action constraint checked by TLC: it verifies
\* that the Next relation only produces transitions that are in the
\* implementation's allowedTransitions set.
\*
\* Source: HAGroupStoreRecord.java L99-123, isTransitionAllowed() L130.
TransitionValid ==
    \A c \in Cluster :
        \* If the state changed for this cluster ...
        clusterState'[c] # clusterState[c] =>
            \* ... then the (old, new) pair must be allowed.
            <<clusterState[c], clusterState'[c]>> \in AllowedTransitions

---------------------------------------------------------------------------

(*
 * Every writer mode change follows the allowed writer transitions.
 * Action constraint checked by TLC analogous to TransitionValid.
 *
 * Source: ReplicationLogGroup.java mode transitions.
 *)
AllowedWriterTransitions ==
    {
      <<"INIT", "SYNC">>,
      <<"INIT", "STORE_AND_FWD">>,
      <<"INIT", "DEAD">>,
      <<"SYNC", "STORE_AND_FWD">>,
      <<"SYNC", "SYNC_AND_FWD">>,
      <<"SYNC", "DEAD">>,
      <<"STORE_AND_FWD", "SYNC_AND_FWD">>,
      <<"SYNC_AND_FWD", "SYNC">>,
      <<"SYNC_AND_FWD", "STORE_AND_FWD">>,
      <<"SYNC_AND_FWD", "DEAD">>,
      <<"DEAD", "INIT">>
    }

WriterTransitionValid ==
    \A c \in Cluster :
        \A r \in RS :
            writerMode'[c][r] # writerMode[c][r] =>
                <<writerMode[c][r], writerMode'[c][r]>> \in AllowedWriterTransitions

---------------------------------------------------------------------------

(*
 * AIS-to-ATS precondition: failover can only begin from AIS when
 * the OUT directory is empty and all RS are in SYNC mode.
 *
 * This precondition is implicit in the implementation because AIS
 * implies all RS are in SYNC (enforced by the ANIS→AIS transition
 * requiring outDirEmpty and the anti-flapping timeout). It is
 * enforced here as a guard on AdminStartFailover and verified as
 * an action constraint.
 *
 * Source: initiateFailoverOnActiveCluster() L375-400 (validates
 *         current state is AIS or ANIS); the precondition holds
 *         because AIS is only reachable when OUT dir is empty and
 *         all writers have returned to SYNC.
 *)
AIStoATSPrecondition ==
    \A c \in Cluster :
        clusterState[c] = "AIS" /\ clusterState'[c] = "ATS"
        => outDirEmpty[c] /\ \A r \in RS : writerMode[c][r] = "SYNC"

---------------------------------------------------------------------------

(*
 * Anti-flapping gate: ANIS → AIS never fires while the countdown
 * timer is still running. This is a cross-check on the ANISToAIS
 * action's AntiFlapGateOpen guard, analogous to how AIStoATS-
 * Precondition cross-checks AdminStartFailover.
 *
 * Source: HAGroupStoreClient.validateTransitionAndGetWaitTime()
 *         L1027-1046
 *)
AntiFlapGate ==
    \A c \in Cluster :
        clusterState[c] = "ANIS" /\ clusterState'[c] = "AIS"
        => AntiFlapGateOpen(antiFlapTimer[c])

---------------------------------------------------------------------------

(*
 * Failover trigger correctness: STA → AIS requires replay-
 * completeness conditions. Cross-checks the TriggerFailover
 * action's guards — if TLC finds a step where STA→AIS happens
 * without the required conditions, the action constraint fires.
 *
 * hdfsAvailable is excluded: it is an environmental/liveness
 * guard (without HDFS, the action cannot fire), not a replay-
 * completeness condition.
 *
 * Source: shouldTriggerFailover() L500-533 (implementation guards)
 *)
FailoverTriggerCorrectness ==
    \A c \in Cluster :
        clusterState[c] = "STA" /\ clusterState'[c] = "AIS"
        => /\ failoverPending[c]
           /\ inProgressDirEmpty[c]
           /\ replayState[c] = "SYNC"

---------------------------------------------------------------------------

(*
 * No data loss (zero RPO): the high-level safety property for
 * failover. When the standby completes STA → AIS, replay must
 * have been in SYNC (no pending SYNCED_RECOVERY rewind), the
 * in-progress directory must be empty, and the failover must
 * have been properly initiated.
 *
 * Logically equivalent to FailoverTriggerCorrectness in this
 * iteration but serves a different documentary purpose: this
 * states the safety property; FailoverTriggerCorrectness validates
 * the implementation mechanism. They will diverge in Iteration 18
 * (forced failover) where NoDataLoss is expected to fail when
 * UseForceFailover = TRUE.
 *)
NoDataLoss ==
    \A c \in Cluster :
        clusterState[c] = "STA" /\ clusterState'[c] = "AIS"
        => /\ failoverPending[c]
           /\ inProgressDirEmpty[c]
           /\ replayState[c] = "SYNC"

---------------------------------------------------------------------------

(*
 * Every replay state change follows the allowed replay transitions.
 * Action constraint checked by TLC analogous to TransitionValid
 * and WriterTransitionValid.
 *
 * Source: ReplicationLogDiscoveryReplay.java L131-206 (listeners),
 *         L323-333 (CAS), L336-351 (replay loop)
 *)
AllowedReplayTransitions ==
    {
      <<"NOT_INITIALIZED", "SYNCED_RECOVERY">>,
      <<"NOT_INITIALIZED", "DEGRADED">>,
      <<"SYNC", "DEGRADED">>,
      <<"DEGRADED", "SYNCED_RECOVERY">>,
      <<"SYNCED_RECOVERY", "SYNC">>,
      <<"SYNCED_RECOVERY", "DEGRADED">>
    }

ReplayTransitionValid ==
    \A c \in Cluster :
        replayState'[c] # replayState[c] =>
            <<replayState[c], replayState'[c]>> \in AllowedReplayTransitions

---------------------------------------------------------------------------

(*
 * AIS implies in-sync: whenever a cluster is in AIS, the OUT
 * directory must be empty and all RS must be in SYNC or INIT.
 *
 * Holds by construction: every action that degrades writers or
 * sets outDirEmpty=FALSE also transitions AIS → ANIS; every
 * path back to AIS (ANISToAIS) requires outDirEmpty and all SYNC.
 *)
AISImpliesInSync ==
    \A c \in Cluster :
        clusterState[c] = "AIS" =>
            /\ outDirEmpty[c]
            /\ \A r \in RS : writerMode[c][r] \in {"INIT", "SYNC"}

---------------------------------------------------------------------------

(*
 * No AIS with S&F writer: a cluster in AIS cannot have any RS in
 * STORE_AND_FWD mode. Subsumed by AISImpliesInSync but retained
 * for independent documentary value as the minimal statement of
 * the critical safety property.
 *
 * Holds by construction: the only paths that create S&F writers
 * (WriterToStoreFwd, WriterInitToStoreFwd) atomically transition
 * AIS → ANIS.
 *)
NoAISWithSFWriter ==
    \A c \in Cluster :
        (\E r \in RS : writerMode[c][r] = "STORE_AND_FWD") =>
            clusterState[c] # "AIS"

---------------------------------------------------------------------------

(*
 * Writer-cluster consistency: degraded writer modes (S&F,
 * SYNC_AND_FWD, DEAD) can only appear on active clusters that
 * are NOT in AIS, or on the ANISTS transitional state.
 * Specifically, this excludes AIS (prevented by AIS→ANIS
 * coupling and CAS failure guard clusterState /= "AIS") and
 * all standby/transitional states (prevented by active-cluster
 * guards).
 *
 * The allowed set includes AbTAIS and AWOP because HDFS can go
 * down while the cluster is in these states; the AIS→ANIS
 * coupling only fires for AIS, so other active states retain
 * their state while writers degrade. DEAD writers arise from
 * CAS failure during degradation, which is only possible when
 * the cluster is already not in AIS.
 *)
WriterClusterConsistency ==
    \A c \in Cluster :
        (\E r \in RS : writerMode[c][r] \in {"STORE_AND_FWD", "SYNC_AND_FWD", "DEAD"}) =>
            clusterState[c] \in {"ANIS", "ANISTS", "ANISWOP", "AbTANIS", "AbTAIS", "AWOP"}

---------------------------------------------------------------------------

(* State constraint *)

\* Bound replay counters for exhaustive search tractability.
\* The abstract counter values only matter relationally
\* (lastRoundProcessed >= lastRoundInSync), so small bounds suffice.
ReplayCounterBound ==
    \A c \in Cluster : lastRoundProcessed[c] <= 3

---------------------------------------------------------------------------

(* Symmetry *)

\* RS identifiers are interchangeable (all start in INIT, identical
\* action sets). Cluster identifiers remain asymmetric (AIS vs S).
Symmetry == Permutations(RS)

---------------------------------------------------------------------------

(* Theorems *)

\* Safety: every step preserves the cluster type invariant.
THEOREM Spec => []TypeOK

\* Safety: every step preserves the writer type invariant.
THEOREM Spec => []WriterTypeOK

\* Safety: every step preserves the OUT dir type invariant.
THEOREM Spec => []OutDirTypeOK

\* Safety: every step preserves the HDFS type invariant.
THEOREM Spec => []HDFSTypeOK

\* Safety: mutual exclusion holds in every reachable state.
THEOREM Spec => []MutualExclusion

\* Safety: abort is always initiated from the correct side.
THEOREM Spec => []AbortSafety

\* Safety: non-atomic failover window preserves mutual exclusion.
THEOREM Spec => []NonAtomicFailoverSafe

\* Safety: AIS implies in-sync (derived invariant).
THEOREM Spec => []AISImpliesInSync

\* Safety: AIS clusters have no S&F writers.
THEOREM Spec => []NoAISWithSFWriter

\* Safety: degraded writer modes only on degraded-active clusters.
THEOREM Spec => []WriterClusterConsistency

\* Safety: anti-flapping timer is bounded by WaitTimeForSync.
THEOREM Spec => []AntiFlapTimerTypeOK

\* Safety: replay state, counters, and flags are well-typed.
THEOREM Spec => []ReplayTypeOK

\* Safety: STA→AIS requires replay-completeness conditions (action property).
THEOREM Spec => [][FailoverTriggerCorrectness]_vars

\* Safety: zero RPO — no data loss on failover (action property).
THEOREM Spec => [][NoDataLoss]_vars

============================================================================
