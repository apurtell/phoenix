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
 *                            |   Also clears failoverPending (models
 *                            |   abortFailoverListener L173-185)
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
         replayState, lastRoundInSync, lastRoundProcessed,
         failoverPending, inProgressDirEmpty

---------------------------------------------------------------------------

(*
 * Admin initiates failover on the active cluster.
 *
 * Pre:  Cluster c is in AIS (ACTIVE_IN_SYNC) and peer is in a
 *       stable standby state (S or DS).
 *       The OUT directory must be empty and all live RS must be
 *       in SYNC mode. DEAD RSes are allowed — an RS can crash
 *       while the cluster is AIS without changing the HA group
 *       state. The implementation checks clusterState = AIS, not
 *       per-RS modes; a DEAD RS is not writing, so the remaining
 *       SYNC RSes and empty OUT dir ensure safety.
 * Post: Cluster c transitions to ATS (ACTIVE_IN_SYNC_TO_STANDBY),
 *       blocking mutations (isMutationBlocked()=true for the
 *       ACTIVE_TO_STANDBY role).
 *
 * The peer-state guard prevents initiating a new failover during
 * the non-atomic window of a previous failover (where the peer
 * may still be in ATS). Without this guard, the admin could
 * produce an irrecoverable (ATS, ATS) deadlock.
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
    /\ \A rs \in RS : writerMode[c][rs] \in {"SYNC", "DEAD"}
    /\ clusterState' = [clusterState EXCEPT ![c] = "ATS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
                   replayState, lastRoundInSync, lastRoundProcessed,
                   failoverPending, inProgressDirEmpty>>

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
 * Also clears failoverPending[c], modeling the abortFailoverListener
 * (ReplicationLogDiscoveryReplay.java L173-185) which fires on LOCAL
 * ABORT_TO_STANDBY, calling failoverPending.set(false).
 *
 * Source: setHAGroupStatusToAbortToStandby() L419-425.
 *)
AdminAbortFailover(c) ==
    /\ clusterState[c] = "STA"
    /\ clusterState' = [clusterState EXCEPT ![c] = "AbTS"]
    /\ failoverPending' = [failoverPending EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
                   replayState, lastRoundInSync, lastRoundProcessed,
                   inProgressDirEmpty>>

============================================================================
