------------------------------ MODULE RS ----------------------------------------
(*
 * RegionServer lifecycle actions for the Phoenix Consistent
 * Failover specification.
 *
 * Models the process supervisor's response to RS abort: when an RS
 * dies (writer mode DEAD), the process supervisor (Kubernetes/YARN)
 * detects the dead pod and creates a new one. HBase assigns regions
 * and the writer re-initializes in INIT mode, ready to follow the
 * normal startup path (WriterInit or WriterInitToStoreFwd).
 *
 * Implementation traceability:
 *
 *   TLA+ action        | Java source
 *   -------------------+----------------------------------------------
 *   RSRestart(c, rs)   | Kubernetes/YARN process supervision →
 *                      |   HBase RS startup →
 *                      |   ReplicationLogGroup.initializeReplication-
 *                      |   Mode() → setMode(SYNC) or
 *                      |   setMode(STORE_AND_FORWARD)
 *)
EXTENDS Types

VARIABLE clusterState, writerMode, outDirEmpty, hdfsAvailable, antiFlapTimer

---------------------------------------------------------------------------

(*
 * Process supervisor restarts a dead RS: DEAD → INIT.
 *
 * The restarted RS enters INIT mode. Subsequent writer actions
 * (WriterInit or WriterInitToStoreFwd) handle the actual mode
 * initialization based on HDFS availability and cluster state.
 *
 * Pre:  writerMode[c][rs] = "DEAD".
 * Post: writerMode[c][rs] = "INIT".
 *
 * Source: Kubernetes/YARN pod restart → HBase RS startup →
 *         ReplicationLogGroup.initializeReplicationMode()
 *)
RSRestart(c, rs) ==
    /\ writerMode[c][rs] = "DEAD"
    /\ writerMode' = [writerMode EXCEPT ![c][rs] = "INIT"]
    /\ UNCHANGED <<clusterState, outDirEmpty, hdfsAvailable, antiFlapTimer>>

============================================================================
