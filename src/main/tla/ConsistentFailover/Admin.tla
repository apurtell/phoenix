-------------------------- MODULE Admin ----------------------------------------
(*
 * Operator-initiated actions for the Phoenix Consistent Failover
 * protocol: failover initiation and abort.
 *
 * These actions model the human operator (Admin actor) who drives
 * failover and abort via the PhoenixHAAdminTool CLI, which delegates
 * to HAGroupStoreManager coprocessor endpoints.
 *
 * Implementation traceability:
 *
 *   TLA+ action              | Java source
 *   -------------------------+----------------------------------------------
 *   AdminStartFailover(c)    | HAGroupStoreManager
 *                            |   .initiateFailoverOnActiveCluster() L375-400
 *   AdminAbortFailover(c)    | HAGroupStoreManager
 *                            |   .setHAGroupStatusToAbortToStandby() L419-425
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer

---------------------------------------------------------------------------

(*
 * Admin initiates failover on the active cluster.
 *
 * Pre:  Cluster c is in AIS (ACTIVE_IN_SYNC) and peer is in a
 *       stable standby state (S or DS).
 *       The OUT directory must be empty and all RS must be in
 *       SYNC mode — this precondition is implicit in the
 *       implementation because AIS implies all RS are in SYNC
 *       (enforced by the ANIS→AIS transition requiring outDirEmpty
 *       and anti-flapping timeout).
 * Post: Cluster c transitions to ATS (ACTIVE_IN_SYNC_TO_STANDBY),
 *       blocking mutations (isMutationBlocked()=true for the
 *       ACTIVE_TO_STANDBY role).
 *
 * The peer-state guard prevents initiating a new failover during
 * the non-atomic window of a previous failover (where the peer
 * may still be in ATS). Without this guard, the admin could
 * produce an irrecoverable (ATS, ATS) deadlock. See
 * PHOENIX_HA_BUG_DUAL_ATS_DEADLOCK.md and Appendix A.15.
 *
 * Source: initiateFailoverOnActiveCluster() L375-400 checks current
 *         state and selects AIS -> ATS or ANIS -> ANISTS.
 *         Peer-state guard: getHAGroupStoreRecordFromPeer()
 *         (HAGroupStoreClient L421).
 *)
AdminStartFailover(c) ==
    /\ clusterState[c] = "AIS"
    /\ clusterState[Peer(c)] \in {"S", "DS"}
    /\ outDirEmpty[c]
    /\ \A rs \in RS : writerMode[c][rs] = "SYNC"
    /\ clusterState' = [clusterState EXCEPT ![c] = "ATS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer>>

---------------------------------------------------------------------------

(*
 * Admin aborts an in-progress failover from the standby side.
 *
 * Pre:  Cluster c is in STA (STANDBY_TO_ACTIVE).
 * Post: Cluster c transitions to AbTS (ABORT_TO_STANDBY).
 *       The peer (in ATS) will react via PeerReactToAbTS,
 *       transitioning to AbTAIS. Both then auto-complete back
 *       to their pre-failover states.
 *
 * Abort must originate from the STA side to prevent dual-active
 * races — this is the AbortSafety property.
 *
 * Source: setHAGroupStatusToAbortToStandby() L419-425.
 *)
AdminAbortFailover(c) ==
    /\ clusterState[c] = "STA"
    /\ clusterState' = [clusterState EXCEPT ![c] = "AbTS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer>>

============================================================================
