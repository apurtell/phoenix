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

VARIABLE clusterState

---------------------------------------------------------------------------

(*
 * Admin initiates failover on the active cluster.
 *
 * Pre:  Cluster c is in AIS (ACTIVE_IN_SYNC).
 * Post: Cluster c transitions to ATS (ACTIVE_IN_SYNC_TO_STANDBY),
 *       blocking mutations (isMutationBlocked()=true for the
 *       ACTIVE_TO_STANDBY role).
 *
 * Source: initiateFailoverOnActiveCluster() L375-400 checks current
 *         state and selects AIS -> ATS or ANIS -> ANISTS.
 *)
AdminStartFailover(c) ==
    /\ clusterState[c] = "AIS"
    /\ clusterState' = [clusterState EXCEPT ![c] = "ATS"]

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

============================================================================
