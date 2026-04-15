-------------------- MODULE ConsistentFailover --------------------------------
(*
 * TLA+ specification of the Phoenix Consistent Failover protocol.
 *
 * Root orchestrator module: declares variables, defines Init, Next,
 * Spec, invariants, and action constraints.
 *
 * Models the HA group state machine for two paired Phoenix/HBase
 * clusters. Each cluster maintains an HA group state in ZooKeeper.
 * State transitions are driven by admin actions, peer-reactive
 * listeners, and writer/reader state changes.
 *
 * Iteration 1 scope:
 *   - Cluster state variable (clusterState)
 *   - Non-deterministic valid transitions (Transition action)
 *   - Safety invariants: TypeOK, MutualExclusion
 *   - Action constraint: TransitionValid
 *
 * Iteration 2 scope (additive):
 *   - ActiveRoles role-level subset (Types.tla)
 *   - ActiveToStandbyNotActive static sanity invariant
 *
 * Action logic is initially defined directly in this module.
 * Future iterations (3+) will factor actions into sub-modules:
 *   - HAGroupStore.tla: peer-reactive transitions, auto-complete
 *   - Admin.tla: operator-initiated failover/abort
 *   - Writer.tla: replication writer mode state machine (per-RS)
 *   - Reader.tla: replication reader/replay state machine
 *   - Environment.tla: HDFS availability, clock tick
 *
 * Implementation traceability:
 *
 *   Modeled concept         | Java class / field
 *   ------------------------+---------------------------------------------
 *   clusterState            | HAGroupStoreRecord per-cluster ZK znode
 *   Transition              | HAGroupStoreClient.setHAGroupStatusIfNeeded()
 *                           |   (L325-394); isTransitionAllowed() (L130)
 *   Init (AIS, S)           | Default initial states per team confirmation
 *                           |   (see PHOENIX_HA_TLA_PLAN.md Appendix A.6)
 *   MutualExclusion         | Architecture safety argument: at most one
 *                           |   cluster in ACTIVE role at any time
 *   AllowedTransitions      | HAGroupStoreRecord.java L99-123
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

\* Tuple of all variables for use in temporal formulas.
vars == <<clusterState>>

---------------------------------------------------------------------------

(* Initial state *)

\* The system starts with one cluster active and in sync (AIS)
\* and the other in standby (S). The choice of which cluster is
\* active is deterministic: CHOOSE picks an arbitrary but fixed
\* element of Cluster as the initial active.
\*
\* Source: Default initial states updated per team confirmation
\*         (PHOENIX_HA_TLA_PLAN.md Appendix A.6).
Init ==
    \* Deterministically assign one cluster to AIS and the other to S.
    \* CHOOSE x \in Cluster : TRUE picks an arbitrary fixed cluster.
    LET active == CHOOSE x \in Cluster : TRUE
    IN clusterState = [c \in Cluster |->
                         IF c = active THEN "AIS" ELSE "S"]

---------------------------------------------------------------------------

(* Actions *)

(*
 * Non-deterministic state transition for a single cluster.
 *
 * Pre:  The current state of cluster c has at least one allowed
 *       outgoing transition in the AllowedTransitions table.
 *       Additionally, if the transition would cause the cluster
 *       to enter the ACTIVE role from a non-ACTIVE role, the
 *       peer must not currently be in the ACTIVE role (mutual
 *       exclusion coordination).
 * Post: clusterState[c] is updated to newState where
 *       <<clusterState[c], newState>> is in AllowedTransitions.
 *       All other cluster states are unchanged.
 *
 * Source: HAGroupStoreClient.setHAGroupStatusIfNeeded() L325-394
 *         validates transitions via isTransitionAllowed() (L130)
 *         before writing to ZK. The coordination guard models the
 *         FailoverManagementListener (HAGroupStoreManager.java
 *         L633-706) which only triggers STA->AIS after detecting
 *         the peer in ATS (a non-ACTIVE role). Detailed
 *         peer-reactive transition guards are added in Iteration 3.
 *)
Transition(c) ==
    \* Non-deterministically choose a valid target state.
    \E newState \in HAGroupState :
        \* The transition must be in the allowed set.
        /\ <<clusterState[c], newState>> \in AllowedTransitions
        \* Coordination guard: a cluster entering the ACTIVE role
        \* from a non-ACTIVE role (i.e., STA->AIS) requires the
        \* peer to not be in the ACTIVE role. This prevents
        \* dual-active states. In the implementation, this is
        \* enforced by the peer-reactive protocol: STA->AIS only
        \* fires after the peer has entered ATS (ACTIVE_TO_STANDBY
        \* role), and ATS->AbTAIS only fires when the peer is AbTS.
        \* Source: FailoverManagementListener L633-706; peer ATS
        \*         detection triggers S/DS->STA; replay completion
        \*         triggers STA->AIS only when peer is in ATS.
        /\ (RoleOf(clusterState[c]) # "ACTIVE" /\ RoleOf(newState) = "ACTIVE")
              => RoleOf(clusterState[Peer(c)]) # "ACTIVE"
        \* Update this cluster's state; leave the other unchanged.
        /\ clusterState' = [clusterState EXCEPT ![c] = newState]

---------------------------------------------------------------------------

(* Next-state relation *)

\* In each step, exactly one cluster performs a valid transition.
Next ==
    \E c \in Cluster : Transition(c)

---------------------------------------------------------------------------

(* Specification *)

\* Safety specification: initial state, followed by zero or more
\* Next steps (or stuttering). No fairness in Iteration 1 —
\* this is a safety-only model.
Spec == Init /\ [][Next]_vars

---------------------------------------------------------------------------

(* Type invariant *)

\* Every cluster's state is a valid HAGroupState.
TypeOK ==
    clusterState \in [Cluster -> HAGroupState]

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
 * Static sanity invariant: the transitional states ATS and ANISTS
 * must not map to the ACTIVE role. This codifies the safety assumption
 * that isMutationBlocked()=true for ACTIVE_TO_STANDBY, which is the
 * mechanism preventing dual-active during the non-atomic failover window.
 *
 * This is trivially true given the current RoleOf definition (both map
 * to ACTIVE_TO_STANDBY), but it guards against future regressions in
 * the role-mapping table.
 *
 * Source: ClusterRoleRecord.java L84 — ACTIVE_TO_STANDBY role has
 *         isMutationBlocked()=true.
 *)
ActiveToStandbyNotActive ==
    /\ RoleOf("ATS") \notin ActiveRoles
    /\ RoleOf("ANISTS") \notin ActiveRoles

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

(* Symmetry *)

\* Placeholder for symmetry reduction. No RS introduced yet;
\* Cluster members have asymmetric initial roles (AIS vs S) so
\* they are not interchangeable. Will become Permutations(RS) in
\* Iteration 5 when per-RS writer mode is added.
Symmetry == {}

---------------------------------------------------------------------------

(* Theorems *)

\* Safety: every step preserves the type invariant.
THEOREM Spec => []TypeOK

\* Safety: mutual exclusion holds in every reachable state.
THEOREM Spec => []MutualExclusion

============================================================================
