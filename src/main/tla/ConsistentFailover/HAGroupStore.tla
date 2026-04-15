-------------------- MODULE HAGroupStore ----------------------------------------
(*
 * Peer-reactive transitions and auto-completion actions for the
 * Phoenix Consistent Failover protocol.
 *
 * Actions model the FailoverManagementListener (HAGroupStoreManager.java
 * L633-706) which reacts to peer ZK state changes via PathChildrenCache
 * watchers, and the local auto-completion resolvers from
 * createLocalStateTransitions() (L140-150).
 *
 * Implementation traceability:
 *
 *   TLA+ action          | Java source
 *   ---------------------+--------------------------------------------------
 *   PeerReactToATS(c)    | createPeerStateTransitions() L109
 *   PeerReactToANIS(c)   | createPeerStateTransitions() L123, L126
 *   PeerReactToAbTS(c)   | createPeerStateTransitions() L132
 *   AutoComplete(c)      | createLocalStateTransitions() L144, L145, L147
 *)
EXTENDS Types

VARIABLE clusterState

---------------------------------------------------------------------------

(*
 * Peer transitions to ATS (ACTIVE_IN_SYNC_TO_STANDBY).
 *
 * When the standby detects its peer has entered ATS, it begins the
 * failover process by transitioning to STA (STANDBY_TO_ACTIVE).
 * This fires from either S or DS — the DS case supports the ANIS
 * failover path where the standby is in DEGRADED_STANDBY when
 * failover proceeds.
 *
 * Source: createPeerStateTransitions() L109 — resolver is
 *         unconditional: currentLocal -> STANDBY_TO_ACTIVE.
 *)
PeerReactToATS(c) ==
    /\ clusterState[Peer(c)] = "ATS"
    /\ clusterState[c] \in {"S", "DS"}
    /\ clusterState' = [clusterState EXCEPT ![c] = "STA"]

---------------------------------------------------------------------------

(*
 * Peer transitions to ANIS (ACTIVE_NOT_IN_SYNC).
 *
 * Two reactive transitions triggered by peer entering ANIS:
 *   1. Local S -> DS: standby degrades because peer's replication is
 *      degraded. Source: L126.
 *   2. Local ATS -> S: old active (in failover) completes transition
 *      to standby when peer is ANIS. Source: L123.
 *)
PeerReactToANIS(c) ==
    \/ /\ clusterState[Peer(c)] = "ANIS"
       /\ clusterState[c] = "S"
       /\ clusterState' = [clusterState EXCEPT ![c] = "DS"]
    \/ /\ clusterState[Peer(c)] = "ANIS"
       /\ clusterState[c] = "ATS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]

---------------------------------------------------------------------------

(*
 * Peer transitions to AbTS (ABORT_TO_STANDBY).
 *
 * When the active cluster (in ATS during failover) detects its peer
 * has entered AbTS (abort initiated from the standby side), the
 * active transitions to AbTAIS (ABORT_TO_ACTIVE_IN_SYNC).
 *
 * Source: createPeerStateTransitions() L132.
 *)
PeerReactToAbTS(c) ==
    /\ clusterState[Peer(c)] = "AbTS"
    /\ clusterState[c] = "ATS"
    /\ clusterState' = [clusterState EXCEPT ![c] = "AbTAIS"]

---------------------------------------------------------------------------

(*
 * Auto-completion transitions (local, no peer trigger).
 *
 * These transitions fire automatically once the cluster enters the
 * corresponding abort state. They return the cluster to its pre-
 * failover state.
 *
 * Source: createLocalStateTransitions() L140-150
 *   AbTS   -> S    (L144)
 *   AbTAIS -> AIS  (L145)
 *   AbTANIS -> ANIS (L147)
 *)
AutoComplete(c) ==
    \/ /\ clusterState[c] = "AbTS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]
    \/ /\ clusterState[c] = "AbTAIS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "AIS"]
    \/ /\ clusterState[c] = "AbTANIS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "ANIS"]

============================================================================
