# Extracted Text: HAGroup.docx


# PHOENIX-7566 HAGroupStoreManager and HAGroupStoreClient for Consistent Failover Design

Author: Ritesh Garg


## Overview

This document provides the design for HAGroupStoreClient/Manager. These components are part of Phoenix HA Rearchitecture for Consistent Failover, and have the following broad responsibilities:-

HA Group Store Client (on servers), which runs on the server side inside the HBase Regionservers, listens to the HA Group Store for updates on the HA group records, and also updates the HA group records.

HA Group Store Manager, coordinating server-side behavior with the state store’s record of who is active/standby.

We first go into the details of requirements from these components followed by some background on the work already completed. Further, we provide design approaches and rationale for our choices followed by edge cases. Since this component will be used by other components in this design, we provide specifications on methods and APIs. At the end, we provide configurations and metrics.


## Background

As part of PHOENIX-7493, we added HAGroupStoreManager and HAGroupStoreClient. These components provide the ability for Graceful failover by introducing a new state(ACTIVE_TO_STANDBY) for blocking all mutations and also setting up local cache for HA Group records in current cluster’s ZK in each RegionServer endpoint. We will build on top of these changes to serve the requirements we will be detailing below.


## Requirements

Phoenix servers (embedded in HBase Region Servers) connect to the HA Group store, maintain a local cache of HA Group records and listen to the HA Group record changes for both Zookeeper quorums(current cluster and its peer).

Provide an API to get the current list of HAGroups.

Operator configures the HA Group details via System Table(new table will be created for storing HAGroup details) which can be used to initialize the HAGroup information including peerZK, clusterUrl and peerUrl etc.

(P1) If an operator updates the list of HAGroups during runtime, update HAGroupStoreClient attributes which are loaded from the table when HAGroupStoreClient instance initializes for a given haGroupName.

HAGroup roles for local and peer cluster are stored in the System table and provide a way to initialize HAGroup cache in Phoenix servers and recover in case of ZK loss.

Provide an RegionServerEndpoint API to read theClusterRoleRecord(CRR) from HA Group Store (current ZK quorum and its peer ZK quorum). This will be the same as existing CRR fetched from ZK so that external clients can consume it via RPC.

Provide an interface to atomically update the cluster state of local cluster in HA Group Store.

See State Transition Diagram here, also see combined state transition diagram here.

Throw a custom exception in case the input state transition is not allowed as per allowed transitions.

(Special Case) Allow for wait times when the ACTIVE role cluster wants to transition from Store & Forward mode to Sync mode. This will eliminate flapping between Store&Forward and Sync modes.

Provide a subscription mechanism for state transition from state X to Y and also provide a mechanism to subscribe to state transition from any state to Z state.

(P1 )Provide APIs for operator to bypass any state transition requirements and force update HAGroup store in current cluster and peer.

Any Reads and Writes in above requests need to be access controlled and audited.

Provide metrics on:-

Current State of cluster

Health of components per RS

Metrics for operation counts (success/failure/latency)


## Proposed Components

Based on components in Phoenix HA Rearchitecture for Consistent Failover relevant to this document.


### HA Group Store (on ZooKeeper)

ZooKeeper is used as a strongly consistent, linearizable state store. Server side processes have direct access to ZooKeeper resources and can set watches for notification should cluster states change. Cluster state transitions may be triggered by system operators, in order to initiate or cancel a failover, or driven by internal components once a failover has been initiated. ZooKeeper ensures strong consistency and atomic transactional updates, preventing concurrency issues in failover. It is the single source of truth for state changes. Split brain scenarios are eliminated.


### HA Group Store Client (on the servers)

The HA Group Store is composed of ZK nodes from two ZK clusters, one from each HBase cluster. The HA Group Store Client component runs on region servers as part of the PhoenixRegionServerEndPoint coprocessor. It uses the  ZK Client library to listen and update HA group records on ZK clusters. This component handles execution of atomic and transactional updates.


### HA Group Store Manager

The primary responsibility of the HA Group Store Manager is tracking the state of the active and standby clusters. A closely related key responsibility is transitioning clusters from one state to another while enforcing invariants on the state transitions:

Two clusters can never become active at the same time.

A standby cluster cannot become active without first confirming that all logged changes committed on the source cluster have been locally replayed.

Each transition checks that the expected state is still correct before finalizing, preventing concurrent conflicting updates.

The HA Group Store Manager will be  implemented as a Phoenix coprocessor Endpoint. (Client side processes can depend on new server side RPC endpoints built as a facade for ZooKeeper-based resources as required.)

ZooKeeper’s multi-operation transactions should be employed for final failover steps so that the new cluster becomes active only in the same atomic operation that the old cluster becomes standby.

System operators trigger failover or abort failovers in progress by using HA Group Store functionality to transition cluster states either from ActiveInSync → ActiveInSyncToStandby, ActiveNotInSync → ActiveNotInSyncToStandby , or from ActiveInSyncToStandby → AbortToActiveInSync(same for ActiveNotInSync).


## Overall Design

Taken from Phoenix HA Rearchitecture for Consistent Failover

The above design shows the arrangement of individual components mentioned above. Highlighted only components relevant to this design in the above diagram. Arrows show the direction of data flow. Bidirectional arrow shows data is both read and written between components.


## High Level Flows

For External Client

Clients read the current ClusterRoleRecord for an HA group name from HAGroupStore. Clients interact with a random regionserver provided by HMaster and periodically query for ClusterRoleRecord for a given HA group. As part of this RPC handling, the incoming request gets routed to HAGroupStoreManager and then subsequently HAGroupStoreClient. Then based on responses from both ZK quorums, HAGroupStoreClient responds with the final ClusterRoleRecords to HAGroupStoreManager and response is sent back to client.

When a client sends a mutation, based on the HA Group in mutation attribute and current HAGroupState, block mutation if the cluster is in specific failover states.

For Reader/Writer

Reader/Writer writers can query the current HAGroupRecords from local HAGroupStoreManager which will provide the response from HAGroupStoreClient based on the latest cached value in HAGroupStoreClient’s PathChildrenCache instance.

Reader/Writer can atomically update the HAGroupRecords in a serializable way. HAGroupStoreManager provides a way to serialize the updates coming from any client that needs to update the HAGroupRecords so that values are not overwritten.

Reader/Writer can query the HAGroupRecords from HAGroupStoreManager. This will follow the same path as the query for external clients.

Set callbacks for specific transitions, readers/writers or any clients can use Observer pattern to register/de-register for specific state transitions for local and peer clusters. Entry point for this would be in HAGroupStoreManager and incoming events from ZK can be checked against all subscriptions and observers can be executed.

For read path and getting updates, we have a somewhat defined path to build on existing work in PHOENIX-7493. In the following section, we expand on two problems related to updating state in HAGroupStore, specifically:-

Problem 1 : Ensuring Atomicity of Updates within the cluster(Single ZK Quorum)

Problem 2 : Handling ZK Updates to HAGroupStore between peers(Both Quorums)


## Problem 1 : Ensuring Atomicity of Updates within the cluster(Single ZK Quorum)

For a single ZK Quorum, HAGroupStoreManager instances across regionservers can receive multiple concurrent updates to HAGroupRecords.

Here we enumerate solutions to handle this issue:-


### Proposed Solutions


#### Global Scheduled Procedure(Single process in each cluster to update ZNode) - Centralized

We create a scheduled procedure which periodically queries the state in HDFS and handles sending updates to HAGroupStoreManager. This procedure can be separate for each cluster and sends updates to its local HAGroupStoreManager for updating the state in the HAGroup record. We will still need to handle concurrent updates to multiple clusters but the number of clients sending the updates to HAGroupStoreManager is unique. Also, we will need to ensure single execution across all RS for a cluster per frequency. This also increases the risk of a single point of failure. In the next execution, a different server can pick up the execution. Also, in future cases where HDFS is not the source of truth, this procedure can query individual RS and make an aggregated decision about the final status to propagate to HAGroupStoreManager. This process can also react to state changes from peer and update state for local cluster.

Pros:-

Single decision making entity per cluster

Cons:-

Single point of failure for each execution but recovery is possible by using ephemeral znode.

Updates will not be near realtime, we will update only after periodic frequency.

Only the states which can be inferred at a global level(e.g. Whether a given HDFS directory is empty or not) can be handled.


#### ZK Optimistic Locking(Recommended) to ensure serializability of updates - Decentralized

Update every time and use atomic updates using ZK Stat Version and Optimistic Locking

Here is a pseudocode explanation of the concept:-

// Assume 'client' is an initialized CuratorFramework instance

// Assume 'Stat' is org.apache.zookeeper.data.Stat

// --- Part 1: Create a Node ---

String path = "/my/optimistic_node";

byte[] initialData = "Version 1 Data".getBytes();

client.create().creatingParentsIfNeeded().forPath(path, initialData);

// --- Part 2: Successful Update using Correct Version ---

// 2a. Read the node's current state (data and version)

Stat stat_Read1 = new Stat();

byte[] data_Read1 = client.getData().storingStatIn(stat_Read1).forPath(path);

int version_Read1 = stat_Read1.getVersion();

// 2b. Prepare new data and attempt an update using the version we just read

byte[] newData_ForUpdate1 = "Version 2 Data".getBytes();

// This setData operation will succeed because version_Read1 is current.

// The version of the node on the server will be incremented.

client.setData().withVersion(version_Read1).forPath(path, newData_ForUpdate1);

// --- Part 3: Attempted Update (Illustrating Stale Version Scenario) ---

// 3a. Read the node's current state again. This will be the state after Update 1.

Stat stat_Read2 = new Stat();

// data_Read2 will be newData_ForUpdate1 ("Version 2 Data")

// version_Read2 will be version_Read1 + 1

byte[] data_Read2 = client.getData().storingStatIn(stat_Read2).forPath(path);

int version_Read2 = stat_Read2.getVersion();

// 3b. **Simulated External Modification:**

//     At this point, assume another process or thread updates the ZNode at 'path'.

//     For example, if another client executed:

//     // client.setData().withVersion(version_Read2).forPath(path, "Data from other client".getBytes());

//     The actual version of the node on the server would now be 'version_Read2 + 1'.

// 3c. Attempt an update using the now *stale* version (version_Read2).

//     The server's actual version is higher due to the simulated external modification.

byte[] newData_ForUpdate2_StaleAttempt = "Version 3 Data (stale attempt)".getBytes();

// The following setData operation is intended to demonstrate a scenario that

// would lead to a BadVersionException. In a real implementation, this call

// would be wrapped in a try-catch block to handle KeeperException.BadVersionException.

client.setData().withVersion(version_Read2).forPath(path, newData_ForUpdate2_StaleAttempt);

// If executed after the simulated external modification, this call would fail,

// and no change would be made to the ZNode by this operation.

// The data on the server would remain what the "other client" set.

Important points to consider:-

ZK Version is 32 bit integer, Overflow can occur, hence comparison must be avoided.

Expected Version = -1 is a wildcard and should be used only if needed. In normal ZK we will not get -1 as a version from reading a znode (ZOOKEEPER-4743). This needs to be backported to our zk lightfork.


#### ZK Group Membership(ZK Curator Recipe) - Decenteralized Option 2 - Not Recommended

Zookeeper Curator Framework provides Group Membership recipe. The group is represented by a znode. Members of the group create ephemeral nodes under the group node. Nodes of the members that fail abnormally will be removed automatically when ZooKeeper detects the failure. We can use this if each RS adds itself to the group in case its functionality is degraded. For example, if a source RS is not able to send replication logs to target, it can add itself as a member and once it is able to send logs again, it can remove itself. HAGroupStoreManager can set a watcher on the group and set state accordingly. Due to the ephemeral nature of nodes created for group membership, any RS that crashes/dies will be automatically kicked out of the group.

Pros:-

Existing framework present which can be used directly

Handles RS that crashes/dies

Cons:-

Updates will have to flow via ZK, HAGroupStoreManager will get notified async once group membership changes.

For every use case, new groups and watchers need to be added.

Each HAGroupStoreManager can have different delays in getting group membership updates and ultimately we’ll have a race condition again.


### Comparison for Approaches


## Problem 2 : Handling ZK Updates to HAGroupStore between peers(Both Quorums)


### Description of race condition during updates

As can be seen in the above section for Overall design, multiple components interact with HAGroupStoreManager. In fact, multiple regionservers across peers can try to send updates at the same time. This can be seen in the diagram below. ReplicationLogWriter and Reader can query HDFS at different times and can see different states of IN/OUT directories. This can cause them to send their own local state to HAGroupStore causing a race condition.

When clients are trying to HAGroupRecord via HAGroupStoreManager, a copy of state is stored in the peer ZK quorum, so we also need to update the peer ZK quorum as well. As HAGroupStoreManager is connected to both ZK quorums, this essentially boils down to the problem of keeping two ZK quorums in sync with each other while serving concurrent update requests. 
Below are two of the possible approaches to handle this:-

Each HAGroupStoreManager updates its own local ZK quorum for any update request and marks the update as successful. During updates we ensure atomicity/serializability only against local quorum only. This can cause conflicting updates across quorums and HAGroupStoreManager will contain additional logic to resolve the final status based on combination of rules, precedence based on higher version number. Logic can be added to sync the HAGroupRecord in both quorums but those rules will need to be defined.
Pros:-
i. Doesn’t rely on the availability of peer ZK to ensure successful update operations

Cons:-
i. Adds more logic and edge cases to handle in HAGroupStoreManager.

ii. Need to explicitly define recovery scenarios in case of split brain or manual intervention will be needed.

Each HAGroupStoreManager ensures the atomic/serialized update against the designated ZK in the list of quorums it is listening to(from configuration). This ZK quorum can be the ACTIVE quorum or can be decided by some heuristic(like ordering of states in some priority). For the remaining quorum(s), it can assume that designated ZK is the source of truth and override remaining quorums during updates. In case of network partition and subsequent recovery, the last known designated ZK quorum’s ZNode values are considered the source of truth.
Pros:-
i. Ensures atomic and serialized updates for both clusters. 
ii. No logic for arbitrating conflicting updates needed and sync 
Cons:-
i. Introduces a hard dependency on designated ZK quorum’s availability.

ii. Handling network partitions is tricky. Need to compromise on availability to avoid split brain scenarios.

Each cluster manages its own state and peers listen to each other’s ZK transitions and update their own state. - Recommended
Currently, there are multiple issues with structure of HA Group Record:-

State for a cluster is stored in multiple ZK ensembles. For e.g. zk1’s state can be present in zk1 and also in its peer zk2. This is arbitrated via version number(Highest Version Wins), we have hard dependency on both peer and local ZK availability to get the correct state for a ZK.

During any updates, the user has to ideally update local and all peer quorums to ensure that update is effectively persistent. Sometimes these updates need to be atomic.

Why current framework won’t work for Consistent Failover

In consistent failover, a local cluster also changes the peer cluster’s state. Combine this with Highest Version Wins approach above and race conditions explained above, it is hard to keep versions and znode updates consistent and atomic across clusters.

To overcome these challenges, we introduce a new version of HA Group Record storage based on following principles:-

Each cluster stores its own state and is responsible for updating its own state only

Cluster is not dependent on other quorum for maintaining regular operations(i.e. Non failover operations).

Each cluster can listen to and react to znode transitions from peer zk quorums.


### Combined State Transition Diagram

This diagram below explains combined state transitions for both Active and Standby clusters. We can see below that in each action only 1 cluster’s state changes at a time and the other cluster reacts to the change.


### For both cluster states


## (empty heading)

Note:

During failover, ATS/ANISTS can’t change from Sync to Store&Forward mode as no new mutations will be coming in.

When an operator decides to abort, they need to abort from the STA cluster because if we abort from a cluster in ATS state, the STA cluster can also become ACTIVE at same time and we can have 2 ACTIVE clusters. So aborting from the STA cluster will avoid it.


### State Transition Diagram for Active Cluster


## (empty heading)


## (empty heading)


### State Transition Diagram for Standby Cluster


## (empty heading)


## System Table for HAGroup and differentiating between State vs Role

Currently we store the ClusterRoleRecord which contains roles for each local and peer cluster(See existing values). In sections above, we have enlisted new states which are relevant to Sync vs Store&Forward mode or intermediate stages during transition etc. All these new states are not useful for external clients and we don’t need to expose this information externally. So we propose to differentiate between state vs role on the basis that each state will have a role associated with it and each role will also have a default value of state associated with it. A role can have multiple states associated with it but each state can have only 1 role value associated. For e.g. role ACTIVE can be associated with state ACTIVE_IN_SYNC and ACTIVE_NOT_IN_SYNC both but default state for ACTIVE role will be set to ACTIVE_NOT_IN_SYNC.

Benefits of differentiating Role vs State:-

External clients need to handle roles and changes between roles.

Each entry in SYSTEM.HA_GROUP will act as a seed for loading the HAGroup configuration like peer url, initial roles etc. Any changes not in role can be updated via operator in the System table and the update will be broadcasted to all HAGroupStoreClient instances for further operations.

By using the SYSTEM.HA_GROUP table, we will store role information in the table. This will act as a backup in case of HAGroupStore(ZK) failure. We can rebuild the state in HAGroupStore(ZK) from the table by using the default state.


## Aggregation of HA Group Record during HA Group Record Read

Do we provide separate APIs for querying each local ZK quorum vs aggregated state across peers?

Currently coreapp clients individually query HA Group Record from each ZK Quorum and use the higher version.

Recommendation is to provide the aggregated state to phoenix external clients with version so that parity can be maintained between current and proposed functionality.


## Support for Current Cluster State Record Handling vs New


### Problem Description

As mentioned above, currently we maintain a role for both the clusters in current CRRs. We have 2 problems here:-

Currently if we transition the role for local cluster in local CRR to Active_To_Standby mode, we only expect the mutations to be blocked. We don’t expect the failover process to get started. In the proposed flow, the standby cluster will detect this state transition and start preparing for becoming Active immediately.

In current CRRs, since we can specify the ZK quorum to connect to, we can add a third quorum in the mix. We plan to use this for FKP migration where we’ll introduce FKP clusters in CRRs for EKS clusters so that the clients can rely on EKS ZK quorums for bootstrap but for actual R/W, the clients use the FKP cluster in the contents of CRRs. If we implement this proposal without any additional changes, we lose that ability to specify 3rd cluster in CRR.


### Current Working

There is no current reaction(as proposed) in current CRRs on peer cluster when the cluster state is updated. All the CRRs are updated by the operator.

On the server side, we have added an implementation in PHOENIX-7493 to block mutations when the state for the local cluster in local CRR is in Active_To_Standby.

On the client side:-

Whenever there is transition to Active_To_Standby, the HConnection is closed.

Whenever the URL is changed in the CRR received on the client side without the connection being closed, the old connections are kept in CQSI Cache.


### Proposed Working

We will not support backward compatibility in consistent failover with CRR. In order to use the newer Consistent HA enabled version of phoenix, customers will have to configure the HAGroup using the SYSTEM.HA_GROUP table. If the mutation attribute for HAGroup is not present in mutation, consistent HA components will ignore the mutation and let it proceed as normal.


## Scenario Walkthroughs


### Degraded Reader/Writer Mode Walkthrough


#### Sync vs Store and Forward

ACTIVE cluster is in ACTIVE_IN_SYNC mode

Somehow even 1 RS is not able to replicate synchronously to STANDBY side, ReplicationLogWriter updates the state in HAGroupStore locally by using HAGroupStoreManager

ACTIVE cluster state is updated from ACTIVE_IN_SYNC to ACTIVE_NOT_IN_SYNC.

lastSyncTimeInMs is updated in local HAGroupStoreRecord on ACTIVE side

STANDBY cluster reacts to this state change by changing its state from STANDBY to DEGRADED_STANDBY_FOR_WRITER (or DEGRADED_STANDBY_FOR_READER to DEGRADED_STANDBY).

As long as the current ReplicationLogWriter is not able to write synchronously or the OUT directory is not empty, the ReplicationLogWriter keeps on periodically setting the state to ACTIVE_NOT_IN_SYNC by using HAGroupStoreManager.

Once the local ReplicationLogWriter is able to write sync AND OUT directory is empty AND the HAGroupState is not in sync mode, the ReplicationLogWriter starts periodically setting the state to ACTIVE_IN_SYNC by using HAGroupStoreManager.

Once the state is updated from ACTIVE_NOT_IN_SYNC to ACTIVE_IN_SYNC. STANDBY cluster updates its state in reverse as in step #3 above.


#### ReplicationLogReader is slow

If the reader is degraded, it sets the local state from STANDBY to DEGRADED_STANDBY_FOR_READER(or DEGRADED_STANDBY_FOR_WRITER to DEGRADED_STANDBY)

Once the reader recovers, the state change is reverted accordingly as per state change in Step #1.


### Failover Process Walkthrough


#### Graceful Failover when Active cluster is in ACTIVE_IN_SYNC state:

Start by changing Active cluster state on active cluster ZK from ACTIVE_IN_SYNC to ACTIVE_TO_STANDBY

Standby cluster listens to this transition and changes its state from STANDBY to STANDBY_TO_ACTIVE.

Replayer completes replaying all the files and changes its state to ACTIVE_IN_SYNC.

The previous ACTIVE cluster changes its state from ACTIVE_TO_STANDBY to STANDBY.


#### When Active role cluster is in ACTIVE_NOT_IN_SYNC state:

Start by changing ACTIVE_NOT_IN_SYNC state from ACTIVE_NOT_IN_SYNC to ACTIVE_NOT_IN_SYNC_TO_STANDBY

The writer will eventually be able to replicate synchronously and finish the backlog in the OUT directory and then update the state to ACTIVE_TO_STANDBY.

Continue the same process as above.


#### Abort Scenario

The operator can abort the failover from STANDBY cluster when it is in STANDBY_TO_ACTIVE state. It is recommended to abort from the STANDBY_TO_ACTIVE side to avoid 2 clusters from becoming ACTIVE at the same time due to possible race condition.

In case of ACTIVE_NOT_IN_SYNC_TO_STANDBY state in ACTIVE, the operator can abort from ACTIVE cluster itself as STANDBY cluster doesn’t react to this state.


## Ensuring Consistency Despite Concurrent HA Store Record Updates


#### Avoiding flapping between Store&Forward and Sync mode in case of different modes across RegionServers

Phoenix HA is required to provide consistent failover. This means the clients do not experience data loss temporarily or permanently after a failover, that is, changes done by the active cluster for an HA group must be replicated to the standby before the failover event.

Synchronous replication ensures this as the mutations are replicated to the standby cluster before the client receives the commit acknowledgement. Also the mutations are replicated only after they are persisted on the active cluster which prevents phantom writes.

This means synchronous replication failures can be potentially the source of consistency issues. If a region server starts logging mutations locally by switching to the store-and-forward replication mode after a synchronous replication failure but fails to update the global HA store with the state ACTIVE_NOT_IN_SYNC, this can cause a consistency issue. For example, just after acknowledging the write commit in the store-and-forward replication mode, the HA group may failover before detecting this mode switch.

Note: For below section, assume N = “zookeeper.session.timeout” * 1.1(multiplier). For our current implementation, “zookeeper.session.timeout” is set to 60 seconds. This means that if a RS is not able to send a heartbeat to ZK for 60 seconds, HMaster will abort that regionserver. See experiment details here.

HA store records will be updated independently by region servers. Each region server using CuratorFramework maintains a cache of HA store records. Ensuring consistency depends on ensuring that the cached state reflects the snapshot of the global state that is not older than N seconds and region servers make sure that their local state is not older than N seconds. This is ensured as follows:

Zookeeper session timeout is set to N seconds. This means that global state update will be broadcasted to a region server in N seconds or the region server will receive a DISCONNECTED event.

A region server will abort if its Zookeeper client receives a DISCONNECTED event.

HA Group Store record changes are done conditionally such that the record change only happens if the current cached record version is the same as the current global record version.

A region server blocks the writes and updates the global HA Group Store if it is in the store-and-forward mode (not in sync-and-forward mode) and the HA Group Store cached record is older than N seconds.

This means that ReplicationLogWriter periodically(suggested N/2 seconds) sets the state to Store&Forward mode even if the HAGroupState is in Store&Forward mode. This will be used to signify that a writer on that specific RS is not able to write logs to peer synchronously at that timestamp.

A region server moves the global state from ACTIVE_NOT_IN_SYNC to ACTIVE_IN_SYNC only after the OUT directory stays empty for N seconds.

This will be implemented in a similar way as above, if the state has now changed from Store&Forward to Sync mode AND the state in HAGroupStore is Store&Forward, the writer keeps on sending update periodically(suggested N/2 seconds) to HAGroupStore until the cluster state is in sync with the writer state.

Now, we will prove that if the admin changes the active cluster state from ACTIVE_IN_SYNC to ACTIVE_TO_STANDY, consistent failover is achieved for an HA group.

The admin will only be able to change the state ACTIVE_TO_STANDY only if the state before the change was ACTIVE_IN_SYNC.

When the active cluster state is ACTIVE_IN_SYNC, the OUT directory of this cluster will be empty and writes to the OUT directory will be blocked as long as the state of this cluster stays ACTIVE_IN_SYNC in HA Group Store.

A standby cluster will move to ACTIVE only after detecting the active cluster moves to ACTIVE_TO_STANDY and all the replication logs are replayed.

The cluster will move to ACTIVE_TO_STANDY to STANBY only after the peer moves to ACTIVE.


## Maintaining last sync timestamp for reader

The Standby cluster needs to adjust compaction/lookback time based on the last time the ACTIVE cluster was able to replicate logs synchronously to its peer. To support this requirement, we maintain this attribute in HAGroupStore called lastSyncTimeInMs.This attribute is updated as follows:-

When the HAGroupStoreRecord initializes for an ACTIVE cluster, the default state will be ACTIVE_NOT_IN_SYNC, the first instance of HAGroupStoreClient will initialize the ZNode and use its own System timestamp as the lastSyncTimeInMs.

If the state is not associated with an ACTIVE role, e.g. STANDBY (roles where ReplicationLogWriter should be running), the value is stored as null.

Once the cluster changes from ACTIVE_NOT_IN_SYNC to ACTIVE_IN_SYNC (i.e. Store&Forward to Sync mode). The timestamp is cleared.

Additionally state updates for local and peer cluster can be subscribed by any component for maintaining its own values. This subscription is within JVM and not available over the network.


## HA Group Store/Manager/Client Health and Recovery

Can we have missed events?


## Specs


### Class : HAGroupStoreRecord

In this every record only contains its own haGroupState

Variables

String protocolVersion //Protocol Version

String haGroupName

HAGroupState haGroupState

long lastSyncTimeInMs


### Class : HAGroupStoreManager

Methods

getHAGroupNames

```

/**

* Returns the list of all HA group names.

* @return list of all HA group names.

* @throws SQLException if there is an error with querying the table.

*/

public List<String> getHAGroupNames() throws SQLException

```

isMutationBlocked

```

/**

* Checks whether mutation is blocked or not for a specific HA group.

*

* @param haGroupName name of the HA group, null for default HA group which tracks

*                   all HA groups.

* @return true if mutation is blocked, false otherwise.

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public boolean isMutationBlocked(String haGroupName) throws IOException

```

invalidateHAGroupStoreClient

```

/**

* Force rebuilds the HAGroupStoreClient instance for all HA groups.

* If any HAGroupStoreClient instance is not created, it will be created.

* @param broadcastUpdate if true, the update will be broadcasted to all

*                       regionserver endpoints.

* @throws Exception in case of an error with dependencies or table.

*/

public void invalidateHAGroupStoreClient(boolean broadcastUpdate) throws Exception

```

invalidateHAGroupStoreClient

```

/**

* Force rebuilds the HAGroupStoreClient for a specific HA group.

*

* @param haGroupName name of the HA group, null for default HA group and tracks all HA groups.

* @param broadcastUpdate if true, the update will be broadcasted to all

*                       regionserver endpoints.

* @throws Exception in case of an error with dependencies or table.

*/

public void invalidateHAGroupStoreClient(final String haGroupName, boolean broadcastUpdate) throws Exception

```

getHAGroupStoreRecord

/**

* Returns the HAGroupStoreRecord for a specific HA group.

*

* @param haGroupName name of the HA group

* @return Optional HAGroupStoreRecord for the HA group, can be empty if the HA group

*        is not found.

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public Optional<HAGroupStoreRecord> getHAGroupStoreRecord(final String haGroupName) throws IOException

getPeerHAGroupStoreRecord

/**

* Returns the HAGroupStoreRecord for a specific HA group from peer cluster.

*

* @param haGroupName name of the HA group

* @return Optional HAGroupStoreRecord for the HA group from peer cluster, can be empty if the HA group

*        is not found or peer cluster is not available.

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public Optional<HAGroupStoreRecord> getPeerHAGroupStoreRecord(final String haGroupName)

throws IOException

setHAGroupStatusToStoreAndForward

```

/**

* Sets the HAGroupStoreRecord to StoreAndForward mode in local cluster.

*

* @param haGroupName name of the HA group

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public void setHAGroupStatusToStoreAndForward(final String haGroupName)

throws IOException, StaleHAGroupStoreRecordVersionException, InvalidClusterRoleTransitionException

```

setHAGroupStatusToSync

```

/**

* Sets the HAGroupStoreRecord to Sync mode in local cluster.

*

* @param haGroupName name of the HA group

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public void setHAGroupStatusToSync(final String haGroupName)

throws IOException, StaleHAGroupStoreRecordVersionException, InvalidClusterRoleTransitionException

```

setReaderToDegraded

```

/**

* Sets the HAGroupStoreRecord to degrade reader functionality in local cluster.

* Transitions from STANDBY to DEGRADED_STANDBY_FOR_READER or from

* DEGRADED_STANDBY_FOR_WRITER to DEGRADED_STANDBY.

*

* @param haGroupName name of the HA group

* @throws IOException when HAGroupStoreClient is not healthy.

* @throws InvalidClusterRoleTransitionException when the current state

*   cannot transition to a degraded reader state

*/

public void setReaderToDegraded(final String haGroupName)

throws IOException, StaleHAGroupStoreRecordVersionException, InvalidClusterRoleTransitionException

```

setReaderToHealthy

```

/**

* Sets the HAGroupStoreRecord to restore reader functionality in local cluster.

* Transitions from DEGRADED_STANDBY_FOR_READER to STANDBY or from

* DEGRADED_STANDBY to DEGRADED_STANDBY_FOR_WRITER.

*

* @param haGroupName name of the HA group

* @throws IOException when HAGroupStoreClient is not healthy.

* @throws InvalidClusterRoleTransitionException when the current state

*   cannot transition to a healthy reader state

*/

public void setReaderToHealthy(final String haGroupName)

throws IOException, StaleHAGroupStoreRecordVersionException, InvalidClusterRoleTransitionException

```

getClusterRoleRecord

```

/**

* Returns the ClusterRoleRecord for the cluster pair.

* If the peer cluster is not connected or peer cluster is not configured, it will

* return UNKNOWN for peer cluster.

* Only implemented by HAGroupStoreManagerImpl.

*

* @return ClusterRoleRecord for the cluster pair

* @throws IOException when HAGroupStoreClient is not healthy.

*/

public ClusterRoleRecord getClusterRoleRecord(String haGroupName) throws IOException

```

subscribeToTransition

```

/**

* Subscribe to be notified when a specific state transition occurs.

*

* @param haGroupName the name of the HA group to monitor

* @param fromState the state to transition from

* @param toState the state to transition to

* @param clusterType whether to monitor local or peer cluster

* @param listener the listener to notify when the transition occurs

* @throws IOException if unable to get HAGroupStoreClient instance

*/

public void subscribeToTransition(String haGroupName,

HAGroupStoreRecord.HAGroupState fromState,

HAGroupStoreRecord.HAGroupState toState,

ClusterType clusterType,

HAGroupStateListener listener) throws IOException

```

unsubscribeFromTransition

```

/**

* Unsubscribe from specific state transition notifications.

*

* @param haGroupName the name of the HA group

* @param fromState the state to transition from

* @param toState the state to transition to

* @param clusterType whether monitoring local or peer cluster

* @param listener the listener to remove

*/

public void unsubscribeFromTransition(String haGroupName,

HAGroupStoreRecord.HAGroupState fromState,

HAGroupStoreRecord.HAGroupState toState,

ClusterType clusterType,

HAGroupStateListener listener)

```

subscribeToTargetState

```

/**

* Subscribe to be notified when any transition to a target state occurs.

*

* @param haGroupName the name of the HA group to monitor

* @param targetState the target state to watch for

* @param clusterType whether to monitor local or peer cluster

* @param listener the listener to notify when any transition to the target state occurs

* @throws IOException if unable to get HAGroupStoreClient instance

*/

public void subscribeToTargetState(String haGroupName,

HAGroupStoreRecord.HAGroupState targetState,

ClusterType clusterType,

HAGroupStateListener listener) throws IOException

```

unsubscribeFromTargetState

```

/**

* Unsubscribe from target state notifications.

*

* @param haGroupName the name of the HA group

* @param targetState the target state

* @param clusterType whether monitoring local or peer cluster

* @param listener the listener to remove

*/

public void unsubscribeFromTargetState(String haGroupName,

HAGroupStoreRecord.HAGroupState targetState,

ClusterType clusterType,

HAGroupStateListener listener)

TODO: @Ritesh Add methods for when replay is complete and standby can transition to ACTIVE_IN_SYNC




### Configurations


### Metrics


## Appendix

Old Combined State Transition Diagram


## Open Changes

Support getting list of HAGroup PHOENIX-7566 HAGroupStoreManager and HAGroupStoreClient for Consistent Failover Design

Timeline for flip flop for HDFS state in writer (Correctness check)

Bitset approach

HAGroupStoreManager to handle store and forward mode vs sync. The store and forward mode;

What if different RS have different view of the world?

Any single RS can be into Store and Forward mode.

Time Buffer for OUT Q to be empty such that we can change to SYNC mode

We can have In transition to sync state

There needs to be no I/O to OUT directory for sometime. Need to leave enough time for RS to get sync

Wait time equals heartbeat time for RS to be alive.

Principles of correctness

Consistent diagram for getting the values

When we don't have control over the other state, do we make some assumptions before changing the current state about the peer state.

There is no coordination

Decentralized vs Centralized

Centralized vs Decentralized for single scheduled procedure vs decentralized

Pros and Cons of each approach not helpful, comparison of each approach

Missed Events (One RS can keep on thinking that it is async while rest of the cluster is in Sync mode)

If Standby can't communicate with the ZK active cluster, it should not become ACTIVE by itself during failover.

Implementation

Check and Put / Optimistic Locking

Each cluster maintains its own state

Listener for other peer

Discovery for peer zk per HA Group

Making HAGroupStoreClient per HAGroup level

Separating out State vs Role

HAGroupStoreManager checks the System table for the current list of HAGroupNames. The table also contains info on current cluster name and role(not state) and also peer url and role(not state).

Need to check how replication is setup

If a new HAGroup name is received in HAGroupStoreManager getInstance and it is not in cache, we check system table and if there is entry available, we initialize the HAGroupStoreRecord in ZK with initial state as ANIS(for active role) and DS(for standby role).

HAGSM checks for new nodes added on the peer side and checks its own system table and initializes ZNodes based on above logic.

Each reader/writer then handles bringing that state back to AIS or Standby

(For coprocs) API to check current state for HAGroupName - Tanuj Khurana

Writer - setSyncMode(String haGroupName)

Writer - setStoreAndForwardMode(String haGroupName)

What if peerZKUrl is changed in the table during runtime?

Can the operator update ZNode and also update the table?

Should we allow AIS and ANIS both  from AbTA?

New state when in ANIS for getting to sync state and user wants to start failover.

External Client HAGroupStoreRecord Discussion

Client will be calling for 1 HAGroup at a time via RegionServerEndpoint

We want to keep the current CRR structure in response.

We want to add registry type in HAGroupStoreRecord

Need to discuss this more with Lokesh Khurana as we are not maintaining current URL in HAGroupStoreRecord

What if there is an exception in connecting to peer ZK for getting consistent HA?

UNKNOWN we can provide if unable to connect

Client to check on peer side if we can get more information(no server change needed).

API signature -> ClusterRoleRecord getHAGroupStoreRecord(String haGroupName)

Client will send version and HAgroupName in each mutation request.

Prebatchmutate to check version against local cached version

If bad version, then throw StaleVersionException (extends SQLException with a specific code).

Version can be determined by sorting the url hash and using convention like <version1>_<version2> where   version1 is for alphabetically lower hash. That way it will be consistent across both local and peer and also on client.

PR Changes

For Pheonix-7493

Single cluster version of ClusterRoleRecord  -> HAGroupStoreRecord

HAGroupStoreManager -> HAGroupStoreClient -> HAGroupStoreManagerV1Impl, HAGroupStoreClientV1 (these rely on CRR to maintain backward compatibility)

Do we need backward compatibility?

Open Questions:-

Peer URL ->

to  get it from config

System Table for peer URL(and other info)

ZNode

Every RS has its own cache for metadata cache and we can use something similar to maintain peer URL.

Check info in HAGroupStoreManager against metadata cache and if its inconsistent  take action.

Requirements for ZKLess Client(RegistryType) and URL in response to external client(coreapp etc.)

Jul 16, 2025 Discussion

Client Friendly States

ACTIVE only for ANIS/AIS. Client is only interested in specific states(like ACTIVE/STANDBY/ATS) and not all states

Or if the state is not available, client can poll?

Poll only when state changes to ATS which will be detected on client side via exception.

Mutation Attribute

Each HA Group will have its own replication context/state

Registry Type and URLs will be provided to client in CRR

Client should be able to handle all registry types?

How do we HAGroupStoreClient for any changes in the System table when registry type changes from X to Y?

CDC type of information which can be used to refresh cache

Invalidate the cache on System Table Update

