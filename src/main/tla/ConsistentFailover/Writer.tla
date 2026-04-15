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
 * In this iteration (5), writer mode transitions are independent of
 * cluster state (no coupling yet). All actions leave clusterState
 * unchanged. Coupling to HDFS availability and cluster state is
 * added in Iterations 6-7.
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
 *)
EXTENDS Types

VARIABLE clusterState, writerMode

---------------------------------------------------------------------------

(*
 * Normal startup: INIT → SYNC.
 *
 * RS initializes and begins writing directly to standby HDFS.
 *)
WriterInit(c, rs) ==
    /\ writerMode[c][rs] = "INIT"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * Startup with peer unavailable: INIT → STORE_AND_FWD.
 *
 * RS initializes but standby HDFS is unreachable; begins
 * buffering locally.
 *)
WriterInitToStoreFwd(c, rs) ==
    /\ writerMode[c][rs] = "INIT"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * Standby HDFS becomes unavailable: SYNC → STORE_AND_FWD.
 *
 * Source: SyncModeImpl.onFailure() L61-77 →
 *         setHAGroupStatusToStoreAndForward()
 *)
WriterToStoreFwd(c, rs) ==
    /\ writerMode[c][rs] = "SYNC"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * Forwarder started while in sync: SYNC → SYNC_AND_FWD.
 *
 * On an ACTIVE_NOT_IN_SYNC event (L98-108), region servers
 * currently in SYNC learn that a peer has entered S&F and
 * transition to SYNC_AND_FWD.
 *)
WriterSyncToSyncFwd(c, rs) ==
    /\ writerMode[c][rs] = "SYNC"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC_AND_FWD"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * Recovery detected; standby available again:
 * STORE_AND_FWD → SYNC_AND_FWD.
 *
 * Source: ReplicationLogDiscoveryForwarder.processFile() L133-152
 *         throughput threshold or drain start.
 *)
WriterStoreFwdToSyncFwd(c, rs) ==
    /\ writerMode[c][rs] = "STORE_AND_FWD"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC_AND_FWD"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * All stored logs forwarded; queue empty:
 * SYNC_AND_FWD → SYNC.
 *
 * Source: ReplicationLogDiscoveryForwarder L167-171 →
 *         setHAGroupStatusToSync()
 *)
WriterSyncFwdToSync(c, rs) ==
    /\ writerMode[c][rs] = "SYNC_AND_FWD"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "SYNC"]
    /\ UNCHANGED clusterState

---------------------------------------------------------------------------

(*
 * Degraded again during drain: SYNC_AND_FWD → STORE_AND_FWD.
 *
 * Standby HDFS becomes unavailable while draining the local queue.
 *)
WriterSyncFwdToStoreFwd(c, rs) ==
    /\ writerMode[c][rs] = "SYNC_AND_FWD"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "STORE_AND_FWD"]
    /\ UNCHANGED clusterState

============================================================================
