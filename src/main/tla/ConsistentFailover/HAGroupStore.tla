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
 * ZK WATCHER DELIVERY DEPENDENCY: All PeerReact* actions and
 * AutoComplete actions depend on ZooKeeper watcher notification
 * chains for delivery. ZK does NOT formally guarantee unconditional
 * delivery — delivery requires an active ZK session and an
 * established TCP connection (see ZOOKEEPER_WATCHER_DELIVERY_ANALYSIS.md
 * and PHOENIX_HA_TLA_PLAN.md Appendix A.16).
 *
 * PeerReact* and AutoComplete actions fire whenever their guard is
 * satisfied. TLC's interleaving semantics already cover arbitrary
 * delivery delay (another action can always fire first), so safety
 * verification is sound.
 *
 * Notification chain (peer-reactive transitions):
 *   Peer ZK znode change
 *     → Curator peerPathChildrenCache
 *       → HAGroupStoreClient.handleStateChange() [L1088-1110]
 *         → notifySubscribers() [L1119-1151]
 *           → FailoverManagementListener.onStateChange() [L653-705]
 *             → setHAGroupStatusIfNeeded() (2-retry limit)
 *
 * Notification chain (auto-completion transitions):
 *   Local ZK znode change
 *     → Curator pathChildrenCache (local)
 *       → HAGroupStoreClient.handleStateChange()
 *         → notifySubscribers()
 *           → FailoverManagementListener.onStateChange()
 *
 * Implementation traceability:
 *
 *   TLA+ action          | Java source
 *   ---------------------+--------------------------------------------------
 *   PeerReactToATS(c)    | createPeerStateTransitions() L109
 *   PeerReactToANIS(c)   | createPeerStateTransitions() L123, L126
 *   PeerReactToAbTS(c)   | createPeerStateTransitions() L132
 *   PeerReactToAIS(c)    | createPeerStateTransitions() L112-120
 *   AutoComplete(c)      | createLocalStateTransitions() L144, L145, L147
 *   StandbyBecomesActive | triggerFailover() L535 → setHAGroupStatusToSync()
 *                        |   L341-355
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable

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
 * ZK watcher dependency: Delivered via peerPathChildrenCache.
 * If the peer ZK session expires or the notification is lost, the
 * standby never learns of the failover. The active cluster remains
 * in ATS with mutations blocked indefinitely. No polling fallback.
 *
 * Source: createPeerStateTransitions() L109 — resolver is
 *         unconditional: currentLocal -> STANDBY_TO_ACTIVE.
 *)
PeerReactToATS(c) ==
    /\ clusterState[Peer(c)] = "ATS"
    /\ clusterState[c] \in {"S", "DS"}
    /\ clusterState' = [clusterState EXCEPT ![c] = "STA"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Peer transitions to ANIS (ACTIVE_NOT_IN_SYNC).
 *
 * Two reactive transitions triggered by peer entering ANIS:
 *   1. Local S -> DS: standby degrades because peer's replication is
 *      degraded. Source: L126.
 *   2. Local ATS -> S: old active (in failover) completes transition
 *      to standby when peer is ANIS. Source: L123.
 *
 * ZK watcher dependency: Delivered via peerPathChildrenCache.
 * If lost: (1) standby stays in S when it should be DS — consistency
 * point tracking is incorrect; (2) old active stays in ATS with
 * mutations blocked. No polling fallback.
 *)
PeerReactToANIS(c) ==
    \/ /\ clusterState[Peer(c)] = "ANIS"
       /\ clusterState[c] = "S"
       /\ clusterState' = [clusterState EXCEPT ![c] = "DS"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>
    \/ /\ clusterState[Peer(c)] = "ANIS"
       /\ clusterState[c] = "ATS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Peer transitions to AbTS (ABORT_TO_STANDBY).
 *
 * When the active cluster (in ATS during failover) detects its peer
 * has entered AbTS (abort initiated from the standby side), the
 * active transitions to AbTAIS (ABORT_TO_ACTIVE_IN_SYNC).
 *
 * ZK watcher dependency: Delivered via peerPathChildrenCache.
 * If lost: active stays in ATS with mutations blocked; abort does
 * not propagate. No polling fallback.
 *
 * Source: createPeerStateTransitions() L132.
 *)
PeerReactToAbTS(c) ==
    /\ clusterState[Peer(c)] = "AbTS"
    /\ clusterState[c] = "ATS"
    /\ clusterState' = [clusterState EXCEPT ![c] = "AbTAIS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Auto-completion transitions (local, no peer trigger).
 *
 * These transitions fire automatically once the cluster enters the
 * corresponding abort state. They return the cluster to its pre-
 * failover state.
 *
 * ZK watcher dependency: Despite being "local" (no peer trigger),
 * these transitions are driven by the local pathChildrenCache
 * watcher chain, not an in-process event bus. The chain is:
 *   local ZK znode change → pathChildrenCache → handleStateChange()
 *   → notifySubscribers("LOCAL:<state>") → FailoverManagementListener
 * If the local ZK watcher notification is lost (e.g., local ZK
 * session expiry), the cluster remains in the AbTS/AbTAIS/AbTANIS
 * state indefinitely.
 *
 * AbTAIS auto-completion: the current implementation (L145) is
 * unconditional (always → AIS). This creates a transient window
 * where AIS coexists with degraded writers if HDFS failed during
 * the abort window. The model assumes the recommended fix from
 * PHOENIX_HA_BUG_ABTAIS_HDFS_FAILURE.md: conditional completion
 * to AIS (clean) or ANIS (degraded).
 *
 * Source: createLocalStateTransitions() L140-150
 *   AbTS   -> S    (L144)
 *   AbTAIS -> AIS  (L145) — conditional per bug report fix
 *   AbTANIS -> ANIS (L147)
 *)
AutoComplete(c) ==
    \/ /\ clusterState[c] = "AbTS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>
    \/ /\ clusterState[c] = "AbTAIS"
       /\ clusterState' = [clusterState EXCEPT ![c] =
              IF outDirEmpty[c] /\ \A rs \in RS : writerMode[c][rs] \in {"INIT", "SYNC"}
              THEN "AIS"
              ELSE "ANIS"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>
    \/ /\ clusterState[c] = "AbTANIS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "ANIS"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Standby completes failover: STA → AIS (local, reader-driven).
 *
 * The standby cluster writes ACTIVE_IN_SYNC to its own ZK after the
 * replication log reader determines replay is complete. This is NOT
 * a peer-reactive transition — it is driven by the reader component.
 *
 * Modeled as a non-deterministic action with the sole guard that
 * the cluster is in STA.
 *
 * Source: ReplicationLogDiscoveryReplay.triggerFailover() L535-548
 *         → setHAGroupStatusToSync() L341-355
 *)
StandbyBecomesActive(c) ==
    /\ clusterState[c] = "STA"
    /\ clusterState' = [clusterState EXCEPT ![c] = "AIS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Peer transitions to AIS (ACTIVE_IN_SYNC).
 *
 * Two reactive transitions triggered by peer entering AIS:
 *   1. Local ATS → S: old active completes failover to standby
 *      when peer (the new active) enters AIS.
 *   2. Local DS → S: standby recovers from degraded when peer
 *      returns to AIS.
 *
 * ZK watcher dependency: Delivered via peerPathChildrenCache.
 * This is the critical transition that resolves the non-atomic
 * failover window. If lost: old active stays in ATS with mutations
 * blocked indefinitely (the (ATS, AIS) state persists). Safety
 * holds (ATS maps to ACTIVE_TO_STANDBY, isMutationBlocked()=true)
 * but liveness requires eventual watcher delivery. No polling
 * fallback. Curator PathChildrenCache re-queries on reconnect,
 * providing eventual delivery if the ZK session survives.
 *
 * Source: createPeerStateTransitions() L112-120 — conditional
 *         resolver for peer ACTIVE_IN_SYNC.
 *)
PeerReactToAIS(c) ==
    \/ /\ clusterState[Peer(c)] = "AIS"
       /\ clusterState[c] = "ATS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>
    \/ /\ clusterState[Peer(c)] = "AIS"
       /\ clusterState[c] = "DS"
       /\ clusterState' = [clusterState EXCEPT ![c] = "S"]
       /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Recovery: ANIS → AIS.
 *
 * When all RS on the cluster have returned to SYNC and the OUT
 * directory is empty, the cluster recovers from ANIS to AIS.
 *
 * The guard `\A rs \in RS : writerMode[c][rs] = "SYNC"` is a sound
 * over-approximation for Iteration 7. In the implementation, the
 * anti-flapping gate (Iteration 8) implicitly ensures all RS have
 * left S&F. When the anti-flapping gate is added, the guard may
 * be relaxed to include SYNC_AND_FWD with an atomic transition of
 * remaining SYNC_AND_FWD RS to SYNC (modeling the ACTIVE_IN_SYNC
 * ZK event).
 *
 * Source: setHAGroupStatusToSync() L341-355, after forwarder drain.
 *)
ANISToAIS(c) ==
    /\ clusterState[c] = "ANIS"
    /\ \A rs \in RS : writerMode[c][rs] = "SYNC"
    /\ outDirEmpty[c]
    /\ clusterState' = [clusterState EXCEPT ![c] = "AIS"]
    /\ UNCHANGED <<writerMode, outDirEmpty, hdfsAvailable>>

============================================================================
