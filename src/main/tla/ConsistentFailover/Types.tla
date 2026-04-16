------------------------ MODULE Types -----------------------------------------
(*
 * Pure-definition module: constants, type sets, state definitions,
 * valid transition table, role mapping, and helper operators for the
 * Phoenix Consistent Failover specification.
 *
 * No variables are declared in this module. All definitions are
 * pure (stateless) and imported by the root module and sub-modules
 * via EXTENDS.
 *
 * Definitions provided:
 *   HAGroupState      — the 14 HA group states
 *   ActiveStates      — states that map to the ACTIVE cluster role
 *   StandbyStates     — states that map to the STANDBY cluster role
 *   TransitionalActiveStates — ATS, ANISTS (ACTIVE_TO_STANDBY role)
 *   AllowedTransitions — set of valid (from, to) state pairs
 *   ClusterRole       — the 6 cluster roles visible to clients
 *   RoleOf(state)     — maps an HAGroupState to its ClusterRole
 *   ActiveRoles       — the set of roles considered "active" (role-level)
 *   Peer(c)           — returns the other cluster in a 2-cluster model
 *   WriterMode        — the 5 replication writer modes (per-RS)
 *   AntiFlapGateOpen  — countdown timer helper: wait elapsed
 *   AntiFlapGateClosed — countdown timer helper: wait in progress
 *   DecrementTimer    — countdown timer helper: advance one tick
 *   StartAntiFlapWait — countdown timer helper: initial/reset value
 *
 * Implementation traceability:
 *
 *   Modeled concept        | Java class / field
 *   -----------------------+---------------------------------------------
 *   HAGroupState           | HAGroupStoreRecord.HAGroupState enum (L51-65)
 *   AllowedTransitions     | HAGroupStoreRecord static init (L99-123)
 *   ClusterRole            | ClusterRoleRecord.ClusterRole enum (L59-107)
 *   RoleOf                 | HAGroupState.getClusterRole() (L73-97)
 *   ANIS self-transition   | HAGroupStoreRecord L101 (heartbeat support)
 *   WriterMode             | ReplicationLogGroup mode (SYNC/S&F/S&FWD)
 *)
EXTENDS Naturals, FiniteSets, TLC

---------------------------------------------------------------------------

(* Constants *)

\* The finite set of cluster identifiers participating in the model.
\* Exactly two clusters form an HA pair.
CONSTANTS Cluster

\* Cluster set must be non-empty.
ASSUME Cluster # {}

\* Exactly two clusters in the HA pair.
ASSUME Cardinality(Cluster) = 2

\* The finite set of region server identifiers per cluster.
\* Each cluster runs the same set of RS; writer mode is tracked per (cluster, RS).
CONSTANTS RS

\* RS set must be non-empty.
ASSUME RS # {}

\* The anti-flapping wait threshold in logical time ticks.
\* Models 1.1 × ZK_SESSION_TIMEOUT (see Appendix A.10).
\* The specific multiplier does not affect protocol safety — only the
\* relationship between the heartbeat interval and the wait threshold
\* matters.
\*
\* Source: HAGroupStoreClient.java L98 — ZK_SESSION_TIMEOUT_MULTIPLIER = 1.1
CONSTANTS WaitTimeForSync

\* WaitTimeForSync must be a positive natural number.
ASSUME WaitTimeForSync \in Nat
ASSUME WaitTimeForSync > 0

---------------------------------------------------------------------------

(* HA Group State definitions *)

\* The 14 HA group states from HAGroupStoreRecord.HAGroupState enum.
\*
\* Source: HAGroupStoreRecord.java L51-65
\*
\*   Modeled value   | Enum constant
\*   ----------------+----------------------------------------------
\*   "AIS"           | ACTIVE_IN_SYNC
\*   "ANIS"          | ACTIVE_NOT_IN_SYNC
\*   "ATS"           | ACTIVE_IN_SYNC_TO_STANDBY
\*   "ANISTS"        | ACTIVE_NOT_IN_SYNC_TO_STANDBY
\*   "AbTAIS"        | ABORT_TO_ACTIVE_IN_SYNC
\*   "AbTANIS"       | ABORT_TO_ACTIVE_NOT_IN_SYNC
\*   "AWOP"          | ACTIVE_WITH_OFFLINE_PEER
\*   "ANISWOP"       | ACTIVE_NOT_IN_SYNC_WITH_OFFLINE_PEER
\*   "S"             | STANDBY
\*   "STA"           | STANDBY_TO_ACTIVE
\*   "DS"            | DEGRADED_STANDBY
\*   "AbTS"          | ABORT_TO_STANDBY
\*   "OFFLINE"       | OFFLINE
\*   "UNKNOWN"       | UNKNOWN
HAGroupState ==
    { "AIS", "ANIS", "ATS", "ANISTS",
      "AbTAIS", "AbTANIS", "AWOP", "ANISWOP",
      "S", "STA", "DS", "AbTS",
      "OFFLINE", "UNKNOWN" }

\* States that map to the ACTIVE cluster role.
\* A cluster in any of these states is considered active and serves
\* mutations. Mutual exclusion requires at most one cluster in an
\* ActiveState at any time.
\*
\* Source: HAGroupState.getClusterRole() L73-97 — these states
\*         return ClusterRole.ACTIVE.
ActiveStates == { "AIS", "ANIS", "AbTAIS", "AbTANIS", "AWOP", "ANISWOP" }

\* States that map to the STANDBY cluster role.
\* A cluster in any of these states is receiving and replaying
\* replication logs from the active peer.
\*
\* Source: HAGroupState.getClusterRole() L73-97 — these states
\*         return ClusterRole.STANDBY.
StandbyStates == { "S", "DS", "AbTS" }

\* States that map to the ACTIVE_TO_STANDBY cluster role.
\* A cluster in these states is transitioning from active to standby
\* during a failover. Mutations are blocked (isMutationBlocked()=true).
\*
\* Source: ClusterRoleRecord.java L84 — ACTIVE_TO_STANDBY role
\*         has isMutationBlocked() = true.
TransitionalActiveStates == { "ATS", "ANISTS" }

\* The set of cluster roles considered "active" for role-level predicates.
\* Distinguished from ActiveStates (which is the set of HA group *states*
\* that map to ACTIVE): ActiveRoles operates at the role abstraction layer.
\*
\* Source: ClusterRoleRecord.java L59-67 — ACTIVE role has
\*         isMutationBlocked()=false.
ActiveRoles == {"ACTIVE"}

---------------------------------------------------------------------------

(* Replication writer mode definitions *)

\* The 5 replication writer modes from ReplicationLogGroup.java.
\* Each RegionServer on the active cluster maintains one of these modes.
\*
\*   Modeled value      | Java class
\*   -------------------+----------------------------------------------
\*   "INIT"             | Pre-initialization
\*   "SYNC"             | SyncModeImpl — writing directly to standby HDFS
\*   "STORE_AND_FWD"    | StoreAndForwardModeImpl — writing locally
\*   "SYNC_AND_FWD"     | SyncAndForwardModeImpl — draining local queue
\*                      |   while also writing synchronously
\*   "DEAD"             | RS aborted (logGroup.abort()) — writer halted,
\*                      |   awaiting process supervisor restart
\*
\* Source: ReplicationLogGroup.java mode classes;
\*         SyncModeImpl.onFailure() L61-74 (CAS failure → abort)
WriterMode == {"INIT", "SYNC", "STORE_AND_FWD", "SYNC_AND_FWD", "DEAD"}

---------------------------------------------------------------------------

(* Allowed transitions *)

\* The set of valid (from, to) state transition pairs.
\* Derived from the allowedTransitions static initializer in
\* HAGroupStoreRecord.java (L99-123).
\*
\* Each entry maps to one line of the static initializer block.
\* The ANIS self-transition ("ANIS" -> "ANIS") supports the
\* periodic heartbeat in StoreAndForwardModeImpl (L71-87) that
\* refreshes zkMtime without changing the state value.
\*
\* Source: HAGroupStoreRecord.java L99-123
AllowedTransitions ==
    {
      \* ANIS can stay in ANIS (heartbeat), return to AIS (recovery),
      \* begin failover (ANISTS), or detect offline peer (ANISWOP).
      \* Source: L101
      <<"ANIS", "ANIS">>,
      <<"ANIS", "AIS">>,
      <<"ANIS", "ANISTS">>,
      <<"ANIS", "ANISWOP">>,
      \* AIS can degrade to ANIS (writer failure), detect offline
      \* peer (AWOP), or begin failover (ATS).
      \* Source: L103
      <<"AIS", "ANIS">>,
      <<"AIS", "AWOP">>,
      <<"AIS", "ATS">>,
      \* S (standby) can begin failover (STA) or degrade (DS).
      \* Source: L105
      <<"S", "STA">>,
      <<"S", "DS">>,
      \* ANISTS can abort (AbTANIS) or advance to ATS once OUT
      \* dir is drained (subject to anti-flapping gate).
      \* Source: L107
      <<"ANISTS", "AbTANIS">>,
      <<"ANISTS", "ATS">>,
      \* ATS can abort (AbTAIS) or complete failover (become S).
      \* Source: L109
      <<"ATS", "AbTAIS">>,
      <<"ATS", "S">>,
      \* STA can abort (AbTS) or complete failover (become AIS).
      \* Source: L111
      <<"STA", "AbTS">>,
      <<"STA", "AIS">>,
      \* DS can recover to S or begin failover (STA).
      \* DS -> STA was added to support ANIS failover path where
      \* standby is in DEGRADED_STANDBY when failover proceeds.
      \* Source: L117 (updated per Appendix A.11)
      <<"DS", "S">>,
      <<"DS", "STA">>,
      \* AWOP returns to ANIS when peer comes back.
      \* Source: L113
      <<"AWOP", "ANIS">>,
      \* Abort auto-completion transitions.
      \* Source: L115, L119, L121
      <<"AbTAIS", "AIS">>,
      \* AbTAIS -> ANIS: needed so HDFS failure during abort can
      \* route to ANIS (S&F writers cannot self-correct while in
      \* AbTAIS without this transition).
      <<"AbTAIS", "ANIS">>,
      <<"AbTANIS", "ANIS">>,
      <<"AbTS", "S">>,
      \* ANISWOP returns to ANIS when peer comes back.
      \* Source: L123
      <<"ANISWOP", "ANIS">>
    }

---------------------------------------------------------------------------

(* Cluster role definitions *)

\* The 6 cluster roles visible to clients.
\*
\* Source: ClusterRoleRecord.ClusterRole enum (L59-107)
ClusterRole ==
    { "ACTIVE", "ACTIVE_TO_STANDBY", "STANDBY",
      "STANDBY_TO_ACTIVE", "OFFLINE", "UNKNOWN" }

\* Maps an HAGroupState to its ClusterRole.
\*
\* Source: HAGroupState.getClusterRole() L73-97
RoleOf(state) ==
    \* Active states map to ACTIVE role.
    IF state \in ActiveStates THEN "ACTIVE"
    \* Transitional states map to ACTIVE_TO_STANDBY role.
    ELSE IF state \in TransitionalActiveStates THEN "ACTIVE_TO_STANDBY"
    \* Standby states map to STANDBY role.
    ELSE IF state \in StandbyStates THEN "STANDBY"
    \* STANDBY_TO_ACTIVE is its own role.
    ELSE IF state = "STA" THEN "STANDBY_TO_ACTIVE"
    \* OFFLINE maps to OFFLINE role.
    ELSE IF state = "OFFLINE" THEN "OFFLINE"
    \* Everything else (UNKNOWN) maps to UNKNOWN role.
    ELSE "UNKNOWN"

---------------------------------------------------------------------------

(* Helpers *)

\* Returns the peer cluster in a 2-cluster model.
\* Precondition: c \in Cluster and |Cluster| = 2.
Peer(c) == CHOOSE p \in Cluster : p # c

---------------------------------------------------------------------------

(* Anti-flapping countdown timer helpers *)

\* The anti-flapping mechanism uses a per-cluster countdown timer
\* following the pattern from Lamport, "Real Time is Really Simple"
\* (CHARME 2005, Section 2). Each cluster's timer counts DOWN from
\* WaitTimeForSync toward 0. The timer does NOT represent a clock
\* running backwards — it represents a waiting period expiring:
\*
\*   WaitTimeForSync ... 2 ... 1 ... 0
\*   |---- gate closed (waiting) ----|  gate open (transition allowed)
\*
\* The S&F heartbeat resets the timer to WaitTimeForSync, keeping the
\* gate closed. When the heartbeat stops (all RS exit STORE_AND_FWD),
\* the Tick action counts the timer down to 0, opening the gate and
\* allowing ANIS -> AIS.
\*
\* Source: HAGroupStoreClient.validateTransitionAndGetWaitTime()
\*         L1027-1046; StoreAndForwardModeImpl.startHAGroupStoreUpdate-
\*         Task() L71-87.

\* TRUE when the anti-flapping wait period has fully elapsed.
\* The guarded transition (ANIS -> AIS) may proceed.
AntiFlapGateOpen(t) == t = 0

\* TRUE when the anti-flapping wait is still in progress.
\* The guarded transition is blocked; time must elapse before
\* the gate opens.
AntiFlapGateClosed(t) == t > 0

\* Advance the countdown timer one tick toward 0 (floor at 0).
\* Used by the Tick action to model the passage of time.
DecrementTimer(t) == IF t > 0 THEN t - 1 ELSE 0

\* The value that starts (or restarts) the anti-flapping wait.
\* Used when a cluster enters ANIS or when the S&F heartbeat fires.
StartAntiFlapWait == WaitTimeForSync

============================================================================
