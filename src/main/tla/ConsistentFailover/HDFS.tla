---------------------------- MODULE HDFS ------------------------------------------
(*
 * HDFS availability incident actions for the Phoenix Consistent
 * Failover specification.
 *
 * Models NameNode crash and recovery as environment incidents that
 * directly perturb writer state. HDFS failure is detected reactively
 * in the implementation.
 * When a NameNode crashes, writers on the peer cluster get IOExceptions
 * on their next HDFS write attempt; SyncModeImpl.onFailure() fires,
 * transitioning the writer to STORE_AND_FWD and calling
 * setHAGroupStatusToStoreAndForward().
 *
 * The model abstracts this as an atomic incident: HDFSDown(c)
 * simultaneously degrades all affected RS on the peer cluster.
 * In reality the first RS to write hits IOException and goes to
 * S&F; other RS learn via ZK ACTIVE_NOT_IN_SYNC event. The atomic
 * perturbation is a sound abstraction for safety checking.
 *
 * Recovery is asymmetric: HDFSUp(c) only sets the availability
 * flag. Per-RS recovery happens gradually via the forwarder path
 * (WriterStoreFwdToSyncFwd), which is guarded on hdfsAvailable.
 *
 * Implementation traceability:
 *
 *   TLA+ action     | Java source
 *   ----------------+------------------------------------------------
 *   HDFSDown(c)     | IOException from ReplicationLog.apply() after
 *                   |   retry exhaustion → SyncModeImpl.onFailure()
 *                   |   L61-74 → setHAGroupStatusToStoreAndForward()
 *                   |   → ReplicationLogGroup.java L835-842
 *   HDFSUp(c)       | NameNode recovery; forwarder detects via
 *                   |   successful FileUtil.copy() in processFile()
 *                   |   L132-152
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer

---------------------------------------------------------------------------

writer == INSTANCE Writer

---------------------------------------------------------------------------

(*
 * NameNode of cluster c crashes.
 *
 * All RS on the peer cluster that are writing to c's HDFS
 * (in SYNC or SYNC_AND_FWD mode) degrade to STORE_AND_FWD.
 * The OUT directory on the peer begins accumulating buffered
 * writes. If the peer cluster is AIS, it transitions to ANIS.
 *
 * Only fires when Peer(c) is an active cluster (has writers).
 * HDFSDown(c_standby): Peer = active — guard satisfied, correct.
 * HDFSDown(c_active): Peer = standby — guard fails, no effect.
 *
 * Pre:  c's HDFS is currently available.
 *       Peer(c) is in an active state.
 *       At least one RS on Peer(c) can degrade.
 * Post: hdfsAvailable[c] = FALSE.
 *       All degradable RS on Peer(c) move to STORE_AND_FWD.
 *       outDirEmpty[Peer(c)] = FALSE.
 *       clusterState[Peer(c)] transitions AIS → ANIS if applicable.
 *
 * Source: SyncModeImpl.onFailure() L61-74 →
 *         setHAGroupStatusToStoreAndForward()
 *)
HDFSDown(c) ==
    /\ hdfsAvailable[c] = TRUE
    /\ clusterState[Peer(c)] \in ActiveStates
    /\ \E rs \in RS : writer!CanDegradeToStoreFwd(Peer(c), rs)
    /\ hdfsAvailable' = [hdfsAvailable EXCEPT ![c] = FALSE]
    /\ writerMode' = [writerMode EXCEPT ![Peer(c)] =
            [rs \in RS |->
                IF writer!CanDegradeToStoreFwd(Peer(c), rs)
                THEN "STORE_AND_FWD"
                ELSE writerMode[Peer(c)][rs]]]
    /\ outDirEmpty' = [outDirEmpty EXCEPT ![Peer(c)] = FALSE]
    /\ clusterState' = IF clusterState[Peer(c)] = "AIS"
                        THEN [clusterState EXCEPT ![Peer(c)] = "ANIS"]
                        ELSE clusterState
    /\ antiFlapTimer' = IF clusterState[Peer(c)] = "AIS"
                         THEN [antiFlapTimer EXCEPT ![Peer(c)] = StartAntiFlapWait]
                         ELSE antiFlapTimer

---------------------------------------------------------------------------

(*
 * NameNode of cluster c recovers.
 *
 * Sets hdfsAvailable[c] = TRUE. No immediate writer effect —
 * recovery is per-RS via the forwarder path. The forwarder
 * detects connectivity by successfully copying a file from
 * OUT to the peer's IN directory; if throughput exceeds the
 * threshold, it transitions the writer S&F → SYNC_AND_FWD.
 *
 * Pre:  c's HDFS is currently unavailable.
 * Post: hdfsAvailable[c] = TRUE. Writer modes unchanged.
 *
 * Source: ReplicationLogDiscoveryForwarder.processFile() L132-152
 *)
HDFSUp(c) ==
    /\ hdfsAvailable[c] = FALSE
    /\ hdfsAvailable' = [hdfsAvailable EXCEPT ![c] = TRUE]
    /\ UNCHANGED <<clusterState, writerMode, outDirEmpty, antiFlapTimer>>

============================================================================
