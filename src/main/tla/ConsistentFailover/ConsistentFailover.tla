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
 * listeners, and writer/reader state changes.
 *
 * Sub-modules:
 *   - HAGroupStore.tla: peer-reactive transitions, auto-completion
 *   - Admin.tla: operator-initiated failover/abort
 *
 * Implementation traceability:
 *
 *   Modeled concept         | Java class / field
 *   ------------------------+---------------------------------------------
 *   clusterState            | HAGroupStoreRecord per-cluster ZK znode
 *   PeerReact* actions      | FailoverManagementListener
 *                           |   (HAGroupStoreManager.java L633-706)
 *   AutoComplete            | createLocalStateTransitions() L140-150
 *   AdminStartFailover      | initiateFailoverOnActiveCluster() L375-400
 *   AdminAbortFailover      | setHAGroupStatusToAbortToStandby() L419-425
 *   Init (AIS, S)           | Default initial states per team confirmation
 *                           |   (see PHOENIX_HA_TLA_PLAN.md Appendix A.6)
 *   MutualExclusion         | Architecture safety argument: at most one
 *                           |   cluster in ACTIVE role at any time
 *   AbortSafety             | Abort originates from STA side; AbTAIS
 *                           |   only reachable via peer AbTS detection
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

(* Sub-module instances *)

\* Peer-reactive transitions and auto-completion.
\* No WITH clause — TLA+ matches the clusterState variable by name.
haGroupStore == INSTANCE HAGroupStore

\* Operator-initiated failover and abort.
admin == INSTANCE Admin

---------------------------------------------------------------------------

(* Initial state *)

\* The system starts with one cluster active and in sync (AIS)
\* and the other in standby (S). The choice of which cluster is
\* active is deterministic: CHOOSE picks an arbitrary but fixed
\* element of Cluster as the initial active.
\*
\* Source: PHOENIX_HA_TLA_PLAN.md Appendix A.6.
Init ==
    \* Deterministically assign one cluster to AIS and the other to S.
    \* CHOOSE x \in Cluster : TRUE picks an arbitrary fixed cluster.
    LET active == CHOOSE x \in Cluster : TRUE
    IN clusterState = [c \in Cluster |->
                         IF c = active THEN "AIS" ELSE "S"]

---------------------------------------------------------------------------

(* Next-state relation *)

(*
 * In each step, exactly one cluster performs one actor-driven action.
 * Actions are factored by actor:
 *   - haGroupStore: peer-reactive transitions and auto-completion
 *     (FailoverManagementListener + local resolvers)
 *   - admin: operator-initiated failover and abort
 *
 * Each action encodes the precise guard (peer state or local state)
 * under which the transition fires, modeling the implementation's
 * actual trigger conditions.
 *)
Next ==
    \E c \in Cluster :
        \* Peer-reactive: standby detects peer entered ATS (failover).
        \/ haGroupStore!PeerReactToATS(c)
        \* Peer-reactive: cluster detects peer entered ANIS (degraded).
        \/ haGroupStore!PeerReactToANIS(c)
        \* Peer-reactive: active detects peer entered AbTS (abort).
        \/ haGroupStore!PeerReactToAbTS(c)
        \* Local auto-completion: AbTS->S, AbTAIS->AIS, AbTANIS->ANIS.
        \/ haGroupStore!AutoComplete(c)
        \* Admin initiates failover: AIS->ATS.
        \/ admin!AdminStartFailover(c)
        \* Admin aborts failover: STA->AbTS.
        \/ admin!AdminAbortFailover(c)

---------------------------------------------------------------------------

(* Specification *)

\* Safety specification: initial state, followed by zero or more
\* Next steps (or stuttering). No fairness yet — this is a
\* safety-only model.
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

\* Placeholder for symmetry reduction. Cluster members have
\* asymmetric initial roles (AIS vs S) so they are not
\* interchangeable.
Symmetry == {}

---------------------------------------------------------------------------

(* Theorems *)

\* Safety: every step preserves the type invariant.
THEOREM Spec => []TypeOK

\* Safety: mutual exclusion holds in every reachable state.
THEOREM Spec => []MutualExclusion

\* Safety: abort is always initiated from the correct side.
THEOREM Spec => []AbortSafety

============================================================================
