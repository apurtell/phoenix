-------------------------- MODULE Writer ----------------------------------------
(*
 * Replication writer mode state machine for the Phoenix Consistent
 * Failover specification.
 *
 * Each RegionServer on the active cluster maintains a writer mode
 * that determines how mutations are replicated: directly to standby
 * HDFS (SYNC), locally buffered (STORE_AND_FWD), or draining local
 * queue while also writing synchronously (SYNC_AND_FWD).
 *
 * HDFS-failure-driven degradation (SYNC → S&F, SYNC_AND_FWD → S&F)
 * is modeled as an atomic incident in HDFS.tla rather than as
 * individual per-RS actions in Next. The per-RS action definitions
 * (WriterToStoreFwd, WriterSyncFwdToStoreFwd) are retained here as
 * documentation of individual transition semantics and share the
 * CanDegradeToStoreFwd predicate with HDFS.tla.
 *
 * Implementation traceability:
 *
 *   TLA+ action                    | Java source
 *   -------------------------------+------------------------------------------
 *   WriterInit(c, rs)              | Normal startup → SyncModeImpl
 *   WriterInitToStoreFwd(c, rs)    | Startup with peer unavailable →
 *                                  |   StoreAndForwardModeImpl
 *   WriterToStoreFwd(c, rs)        | SyncModeImpl.onFailure() L61-77 →
 *                                  |   setHAGroupStatusToStoreAndForward()
 *   WriterSyncToSyncFwd(c, rs)     | Forwarder ACTIVE_NOT_IN_SYNC event
 *                                  |   L98-108 while RS in SYNC
 *   WriterStoreFwdToSyncFwd(c, rs) | Forwarder processFile() L133-152
 *                                  |   throughput threshold or drain start
 *   WriterSyncFwdToSync(c, rs)     | Forwarder drain complete; queue empty
 *                                  |   → setHAGroupStatusToSync() L171
 *   WriterSyncFwdToStoreFwd(c, rs) | Re-degradation during drain
 *   CanDegradeToStoreFwd(c, rs)    | Guard predicate: RS is in a mode that
 *                                  |   writes to standby HDFS (SYNC or
 *                                  |   SYNC_AND_FWD)
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable

---------------------------------------------------------------------------

(* Predicates *)

(*
 * Guard predicate: RS is in a mode that writes to standby HDFS
 * and would degrade to STORE_AND_FWD on an HDFS failure.
 *
 * Used by HDFS.tla (HDFSDown) to determine which RS on a cluster
 * are affected by a NameNode crash, and by the documented per-RS
 * action definitions below.
 *)
CanDegradeToStoreFwd(c, rs) ==
    writerMode[c][rs] \in {"SYNC", "SYNC_AND_FWD"}

---------------------------------------------------------------------------

(* Actions wired into Next *)

(*
 * Normal startup: INIT → SYNC.
 *
 * RS initializes and begins writing directly to standby HDFS.
 * Writers only run on the active cluster.
 *
 * Source: Normal startup → SyncModeImpl
 *)
WriterInit(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "INIT"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC"]
    /\ UNCHANGED <<clusterState, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Startup with peer unavailable: INIT → STORE_AND_FWD.
 *
 * RS initializes but standby HDFS is unreachable; begins
 * buffering locally in the OUT directory. Also transitions
 * cluster AIS → ANIS (setHAGroupStatusToStoreAndForward).
 * Writers only run on the active cluster.
 *
 * Source: StoreAndForwardModeImpl.onEnter() L54-64
 *)
WriterInitToStoreFwd(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "INIT"
    /\ hdfsAvailable[Peer(c)] = FALSE
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ outDirEmpty' = [outDirEmpty EXCEPT ![c] = FALSE]
    /\ clusterState' = IF clusterState[c] = "AIS"
                        THEN [clusterState EXCEPT ![c] = "ANIS"]
                        ELSE clusterState
    /\ UNCHANGED hdfsAvailable

---------------------------------------------------------------------------

(*
 * Forwarder started while in sync: SYNC → SYNC_AND_FWD.
 *
 * On an ACTIVE_NOT_IN_SYNC event (L98-108), region servers
 * currently in SYNC learn that a peer has entered S&F and
 * transition to SYNC_AND_FWD. This event only fires when
 * the cluster enters ANIS.
 *
 * Source: ReplicationLogDiscoveryForwarder.init() L98-108
 *)
WriterSyncToSyncFwd(c, rs) ==
    /\ clusterState[c] = "ANIS"
    /\ writerMode[c][rs] = "SYNC"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC_AND_FWD"]
    /\ UNCHANGED <<clusterState, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * Recovery detected; standby available again:
 * STORE_AND_FWD → SYNC_AND_FWD.
 *
 * The forwarder successfully copies a file from the OUT directory
 * to the standby's IN directory. If throughput exceeds the
 * threshold, the writer transitions to SYNC_AND_FWD to begin
 * draining the queue while also writing synchronously.
 * The forwarder only runs on the active cluster.
 *
 * Source: ReplicationLogDiscoveryForwarder.processFile() L133-152
 *         throughput threshold or drain start.
 *)
WriterStoreFwdToSyncFwd(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "STORE_AND_FWD"
    /\ hdfsAvailable[Peer(c)] = TRUE
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC_AND_FWD"]
    /\ UNCHANGED <<clusterState, outDirEmpty, hdfsAvailable>>

---------------------------------------------------------------------------

(*
 * All stored logs forwarded; queue empty:
 * SYNC_AND_FWD → SYNC.
 *
 * The forwarder has drained all buffered files from the OUT
 * directory. The OUT directory is now empty.
 * The forwarder only runs on the active cluster.
 *
 * Source: ReplicationLogDiscoveryForwarder.processNoMoreRoundsLeft()
 *         L155-184 → setHAGroupStatusToSync() L171
 *)
WriterSyncFwdToSync(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "SYNC_AND_FWD"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC"]
    /\ outDirEmpty' = [outDirEmpty EXCEPT ![c] = TRUE]
    /\ UNCHANGED <<clusterState, hdfsAvailable>>

---------------------------------------------------------------------------

(* Documented action definitions — not wired into Next *)

(*
 * Per-RS HDFS failure degradation: SYNC → STORE_AND_FWD.
 *
 * Models a single RS detecting standby HDFS unavailability via
 * IOException and transitioning to local buffering. In the
 * composite model, this transition is driven atomically for all
 * affected RS by HDFSDown in HDFS.tla. Also transitions
 * cluster AIS → ANIS (setHAGroupStatusToStoreAndForward).
 * Writers only run on the active cluster.
 *
 * Source: SyncModeImpl.onFailure() L61-77 →
 *         setHAGroupStatusToStoreAndForward()
 *)
WriterToStoreFwd(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "SYNC"
    /\ hdfsAvailable[Peer(c)] = FALSE
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ outDirEmpty' = [outDirEmpty EXCEPT ![c] = FALSE]
    /\ clusterState' = IF clusterState[c] = "AIS"
                        THEN [clusterState EXCEPT ![c] = "ANIS"]
                        ELSE clusterState
    /\ UNCHANGED hdfsAvailable

---------------------------------------------------------------------------

(*
 * Re-degradation during drain: SYNC_AND_FWD → STORE_AND_FWD.
 *
 * Models standby HDFS becoming unavailable again while the
 * forwarder is draining the local queue. The RS falls back to
 * pure local buffering. In the composite model, this transition
 * is driven atomically for all affected RS by HDFSDown in
 * HDFS.tla. Writers only run on the active cluster. No AIS → ANIS
 * coupling needed: if RS is in SYNC_AND_FWD, cluster is already
 * ANIS (cannot be AIS).
 *
 * Source: SyncAndForwardModeImpl.onFailure() L66-82
 *)
WriterSyncFwdToStoreFwd(c, rs) ==
    /\ clusterState[c] \in ActiveStates
    /\ writerMode[c][rs] = "SYNC_AND_FWD"
    /\ hdfsAvailable[Peer(c)] = FALSE
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ outDirEmpty' = [outDirEmpty EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<clusterState, hdfsAvailable>>

============================================================================
