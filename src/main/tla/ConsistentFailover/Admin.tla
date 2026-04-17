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
         failoverPending, inProgressDirEmpty,
         zkPeerConnected, zkPeerSessionAlive, zkLocalConnected

---------------------------------------------------------------------------

(*
 * Admin initiates failover on the active cluster.
 *
 * Two paths depending on current state:
 *
 *   AIS path:  AIS -> ATS   (in-sync, ready to hand off immediately)
 *   ANIS path: ANIS -> ANISTS (not-in-sync, forwarder must drain OUT
 *              before ANISTS can advance to ATS)
 *
 * AIS path guards:
 *   The OUT directory must be empty and all live RS must be in SYNC
 *   mode. DEAD RSes are allowed -- an RS can crash while the cluster
 *   is AIS without changing the HA group state. The implementation
 *   checks clusterState = AIS, not per-RS modes; a DEAD RS is not
 *   writing, so the remaining SYNC RSes and empty OUT dir ensure
 *   safety.
 *
 * ANIS path guards:
 *   The implementation only validates the current state (ANIS) and
 *   peer state. No outDirEmpty or writer-mode guards -- the
 *   forwarder will drain OUT after the transition. The ANISTS ->
 *   ATS transition (ANISTSToATS in HAGroupStore.tla) guards on
 *   outDirEmpty and the anti-flapping gate.
 *
 * Both paths:
 *   Peer must be in a stable standby state (S or DS) to prevent
 *   initiating a new failover during the non-atomic window of a
 *   previous failover (where the peer may still be in ATS).
 *   Without this guard, the admin could produce an irrecoverable
 *   (ATS, ATS) or (ANISTS, ATS) deadlock.
 *
 * Post: Cluster c transitions to ATS or ANISTS, both of which
 *       map to the ACTIVE_TO_STANDBY role, blocking mutations
 *       (isMutationBlocked()=true).
 *
 * Source: initiateFailoverOnActiveCluster() L375-400 checks current
 *         state and selects AIS -> ATS or ANIS -> ANISTS.
 *         Peer-state guard: getHAGroupStoreRecordFromPeer()
 *         (HAGroupStoreClient L421).
 *)
AdminStartFailover(c) ==
    /\ clusterState[Peer(c)] \in {"S", "DS"}
    /\ \/ /\ clusterState[c] = "AIS"
          /\ outDirEmpty[c]
          /\ \A rs \in RS : writerMode[c][rs] \in {"SYNC", "DEAD"}
          /\ clusterState' = [clusterState EXCEPT ![c] = "ATS"]
       \/ /\ clusterState[c] = "ANIS"
          /\ clusterState' = [clusterState EXCEPT ![c] = "ANISTS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer,
                   replayState, lastRoundInSync, lastRoundProcessed,
                   failoverPending, inProgressDirEmpty,
                   zkPeerConnected, zkPeerSessionAlive, zkLocalConnected>>

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
 * races -- this is the AbortSafety property.
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
                   inProgressDirEmpty,
                   zkPeerConnected, zkPeerSessionAlive, zkLocalConnected>>

============================================================================
