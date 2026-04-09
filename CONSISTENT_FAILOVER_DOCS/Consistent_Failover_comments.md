# Comments from Consistent_Failover.docx

### Comment 55 (Kadir Ozdemir, 2025-03-25T04:29:48Z)

A block should correspond to a mini batch given to a coproc for tables without indexes. For tables with indexes, the block will include the mini batch for the data table as well as the corresponding mutations for indexes. A mini batch can include both puts and delete mutations. So we need to update the format here accordingly, that is, a mini batch can include both put and delete mutations for one or more rows and one or more tables (one data table and multiple index tables). Can we eliminate the transaction/SCN or commit ID as Phoenix do not have the corresponding concepts on the server side. I suggest not to include old values for the changes at least in the first version as we do not have any need for it even for the CDC use cases.

### Comment 56 (Kadir Ozdemir, 2025-03-25T04:35:42Z)

I see now that a block can have multiple records. I missed this. So, that aspect does not need to be updated.

### Comment 57 (Kadir Ozdemir, 2025-03-25T05:47:37Z)

I also see that you wanted to include multiple mini batches in a single block when possible. That makes sense too. Now, I agree with everything you have designed except commit ID and old value.

### Comment 58 (Kadir Ozdemir, 2025-03-27T18:53:16Z)

@apurtell@salesforce.com, I think the commit ID is a good idea too as I can see that it can be use for debugging and tracing. However, I am planning to remove the old value as it suggests a different CDC approach than what is already implemented and will be released as part of 5.3. Let me know if you are fine with that.

### Comment 35 (Ritesh Garg, 2025-05-18T07:00:59Z)

What if Standby cluster can receive but not replay?

### Comment 9 (David Manning, 2025-04-03T00:10:32Z)

There is a third option here, which is we continue to use HBase asynchronous replication, use the Phoenix coprocessor concept of a global barrier to writes, and have external validation that we have fully replicated all HBase WALs. It seems important to include here because this is the current plan for FKP migration.

So then there are two points:
1. If it's good enough for FKP migration, can it be good enough for consistent cluster failover in general? We benefit from leveraging existing known replication paths and hardened code. It simplifies the work significantly.
2. If it's NOT good enough for consistent cluster failover, should it really be considered acceptable for FKP migration?

### Comment 10 (Kadir Ozdemir, 2025-04-03T03:18:13Z)

FKP requires graceful (that is planned) failover. The design change proposed here is for unplanned failover (failover when the primary cluster becomes unhealthy/unavailable).

### Comment 11 (David Manning, 2025-04-03T18:35:44Z)

Ahhh yes. Okay, this is the part I was missing, thank you.

### Comment 38 (David Manning, 2025-04-03T00:07:51Z)

I assume the endpoint will run an algorithm that determines whether it is safe to transition from ActiveToStandby -> Standby + StandbyToActive -> Active. Does this mean it will run on only one Phoenix server at a time? And a system table region assignment will determine who is the "leader" for the purposes of orchestrating these changes? Or is there some other coordination for who is monitoring whether we have safely replicated all changes, and how they update ZooKeeper?
1 total reaction
Ritesh Garg reacted with 👍 at 2025-05-18 22:06 PM

### Comment 39 (Kadir Ozdemir, 2025-04-03T01:53:14Z)

I updated the text to align with the diagram, that is HA group Store vs HA Group Store Manager. A separate instance of the HA Group Store Manager will be running on every region server as part of the Phoenix Server instance (which is a set of coprocessors). State transitions will not be based on some algorithm, they will be based on events (and possibly some condition if needed). The events can happen on any region server and that will trigger state transitions.  There will be two types of events, state changes initiated by the admin or the peer cluster, and replication status changes, that is sync vs async. The state transition will be persisted in HA group records on Zookeepers. HA Group Store Manager will have a copy of these records and thus the current state/role for each cluster.

### Comment 40 (David Manning, 2025-04-03T02:10:43Z)

Maybe I missed the section which describes those events? In particular I'm wondering which component determines whether all regionservers have reached the global barrier of preventing writes, and therefore ensure the peer cluster has access to all necessary replicated edits before moving to StandbyToActive. Or is that transition not validated for each regionserver?

### Comment 41 (Kadir Ozdemir, 2025-04-03T16:57:49Z)

These details should be in the detailed design docs for individual components including HA Group Store Manager and Replication Log Reader.  Assume that the max broadcast delay for state transition changes from Zookeeper to region servers is x seconds. This means Replication Log Reader needs to wait for x seconds after replaying all logs in the StandbyToActive state to see if there were more logs from the peer. If there are more logs, they should be replayed before transitioning to the Active state. I think we may continue to replay logs even after transitioning to Active for a short amount of time. However, you are raising an interesting point about if a state change should be also propagated internally among region servers or we should rely on Zookeeper only. We may follow the cache invalidate pattern to make sure that all region servers get the state changes synchronously.

### Comment 42 (David Manning, 2025-04-03T18:32:23Z)

If we continue to replay logs after transitioning to Active for a short amount of time, then it is an inconsistent failover for a short amount of time.

### Comment 43 (Kadir Ozdemir, 2025-04-03T18:46:55Z)

Yes, that is correct. We will add more details on that. I think we may have to abort region servers that cannot communicate with Zookeeper.

### Comment 5 (Kevin Bradicich, 2025-02-05T20:24:23Z)

Can you clarify our target expectations? In the "Higher Availability" section above, it suggests the failover RPO is "in minutes," while here it states zero.

1. What’s our consistency target before the org goes live on the secondary cluster? e.g. Should 90%, 95%, or 99% of data be synced before the org goes live on the secondary?

2. What’s our time target to reach that threshold? Are we aiming for <10 minutes, <5 minutes, or zero minutes?

3. Point #2 constitutes the RPO, correct?

### Comment 6 (Kadir Ozdemir, 2025-03-03T21:46:40Z)

100% consistency, less than two minutes of RTO, and zero RPO

### Comment 0 (Ritesh Garg, 2025-03-25T22:33:55Z)

Should we update this to accommodate master registry? @lokesh.khurana@salesforce.com

### Comment 1 (Lokesh Khurana, 2025-03-31T17:05:29Z)

Yes @kozdemir@salesforce.com now HA group record will include registryType as well after PHOENIX-7495 and it can have master endpoints as well.

### Comment 2 (Kadir Ozdemir, 2025-03-31T18:44:34Z)

👍

### Comment 59 (Kadir Ozdemir, 2025-01-22T18:34:20Z)

This should not be an issue. We can simply generate multiple files for a given round of replication when needed. In other words, we close a given replication log file based on size or time. I suggested initially that the replication log files can be named as <source region server name><timestamp>. Actually, the replication sink/target cluster does not need to know which region server is the source of a given log file. We can simply use a UUID instead of a region server name. In fact, it will be simpler to process/ debug log files if they are named as <timestamp><UUID>.

### Comment 60 (Andrew Purtell, 2025-01-22T18:35:00Z)

It matters when considering hbase-asyncfs, the topic of this section, because hbase-asyncfs only supports files of one hdfs block. It is that simplification that allows for it to avoid pipeline recovery. So if we decide hbase-asyncfs, or its techniques/advantages, are desirable for this project, then we must consider this question to determine if we can use it/them. I can try to clarify earlier in the section if the line through is not clear.

### Comment 61 (Kadir Ozdemir, 2025-01-22T18:38:55Z)

What I meant is that this should not be an issue for the Phoenix replication we suggested here. We can generate multiple one-block log files for a given round of replication for a given source region server.

### Comment 62 (Andrew Purtell, 2025-01-22T18:39:57Z)

Got it. Thanks! 👍

### Comment 44 (Kadir Ozdemir, 2025-01-22T20:05:22Z)

I agree that most WAL details are unnecessary however we can use "WAL edit" as it is. Even if we use a more optimized format like the one suggested in https://docs.google.com/document/d/1I-xDJXEms71vacoB8oclFvAAkwD8Ef9JGh9z-2ax5fs/edit?tab=t.0#heading=h.n4aaltoqvonk, we still need to convert it back to the cell format on the target to apply the changes to HBase directly. So, I suggest that we continue use "WAL edit" but replace "WAL key". In order to reduce the cell format overhead, Phoenix can encode the column names for mutable tables and or encode entire row in one cell per column family for indexes and immutable tables. Using the HBase cell format (that is "WAL edit")still allows us to leverage these encoding schemes.

### Comment 45 (Andrew Purtell, 2025-01-23T00:43:36Z)

Row and column change record formats are defined in detail below in a more optimal way than WAL edit.

### Comment 46 (Kadir Ozdemir, 2025-01-23T03:04:42Z)

Yes, I agree that it is more optimal for the static columns (that are defined in the table schema). We also need to support dynamic columns that are not defined in the schema. For those, we need to include column family and qualifier. We also need to decide if we transfer cell tags for delete markers.

### Comment 47 (Kadir Ozdemir, 2025-01-23T03:19:34Z)

Another complication is that at the coproc level, we do not have the table schema currently. In order to generate the optimal format, we need to retrieve the table schema from syscat. Also, the mutations generated by MR jobs using directly HBase clients would be an issue. These may pass multiple cells for a given column. For example, org migration jobs.

### Comment 48 (Andrew Purtell, 2025-01-23T20:49:53Z)

Even with a predefined schema there could still be ALTER that would change ordinals if they weren't tracked, and for dynamic columns I have been debating the same kind of tracking, and have been meaning to come back to this. Let me make some updates. I still don't want to take WALEdit as such but, sure, we will end up with something functionally equivalent.

### Comment 49 (Kadir Ozdemir, 2025-01-24T18:00:55Z)

I agree we can address all these concerns and still come up with something more optimized than WALEdit. We need a version of WALEDit where row key and timestamp are stripped off.

### Comment 50 (Andrew Purtell, 2025-01-24T18:32:45Z)

I think we have that with the current design of the change record in the log format.

### Comment 51 (Kadir Ozdemir, 2025-01-24T19:00:21Z)

_Marked as resolved_

### Comment 52 (Kadir Ozdemir, 2025-01-24T19:03:21Z)

_Re-opened_
I did not mean to mark this as resolved. What we need to do is to have a self-describing format. So instead of using column position, we need to have column family name and qualifier in the format to address all the above issues but still come up with a more optimized format.

### Comment 30 (Andrew Purtell, 2025-01-14T16:48:45Z)

@kozdemir@salesforce.com clarifying server side watches and implementation strategy for plumbing ZK resources to the client (as new endpoints)

### Comment 16 (Ritesh Garg, 2025-05-18T06:36:21Z)

There is an operational consideration needed here for a case where a single RS on source side can have network issues and will be unable to replicate to HDFS on target cluster while rest of the RS can. In such a case will impact to single RS change all RS to use store and forward instead of their normal sync flow or do we let each RS decide its own strategy?

If all source RS change from sync to store and forward based on 1 RS being unable to communicate to target HDFS , should we try to differentiate between a case where STANDBY is actually in degraded state vs one of the RS is just not able to talk to target HDFS?

@david.manning@salesforce.com  correct me if I am wrong here, as per my understanding, we sometimes get replication lag alert where suggested remediation is to restart the RS which has abnormally high lag(as compared to other ones). The alert can be due to such a case where either source is partitioned or a single target RS is unreachable.

### Comment 17 (Kadir Ozdemir, 2025-05-19T06:48:54Z)

Even if one RS cannot replicate, the HA state for the standby cluster becomes Degraded-Standby.

### Comment 18 (Ritesh Garg, 2025-05-19T06:52:08Z)

My assumption is that ReplicationLogWriter(s) will not switch to store and forward based on another RS's inability to use sync and will make decision to use sync vs store and forward locally based on their ability to synchronously replicate. Is that correct?

### Comment 19 (Kadir Ozdemir, 2025-05-19T06:52:37Z)

The replication mode becomes store-and-forward. When the replication recovers and moves to the synchronous mode, the primary cluster switches to the sync mode and the standby cluster state becomes Standby again.

### Comment 20 (David Manning, 2025-05-20T16:26:32Z)

The most common cases for replication lag alerts that affect one source RS are:
1. hotspot writes to the source RS (replication cannot keep up with incoming writes to that RS)
2. bugs where replication just gets stuck somehow

It's fairly uncommon to have a network partition be the cause for a single source RS to not be able to replicate. In cases where a single source RS can't talk to a single target RS, this is fixed by selecting a new sink RS. 

Obviously in cases where the target table keyrange is inconsistent or offline or throttling, replication will also lag, but this is not related to the source RS.

### Comment 21 (David Manning, 2025-05-20T16:28:30Z)

The doc does make it sound like each regionserver is making decisions locally, and then surfacing that decision in a way that affects the state of the standby cluster. Presumably, that means that there is a coordination mechanism via ZooKeeper or otherwise, to determine that all source RS are in sync mode, before transitioning out of degraded mode... including a way to "clean up" and "take ownership" of expired source RS.

### Comment 7 (Kevin Bradicich, 2025-02-05T20:30:43Z)

Does this project affect our ability to separate cells by Phoenix/HBase cluster, such that a subset of cells communicate to one cluster, and another subset of cells communicate to another cluster (thereby increasing scale, reducing blast radius, and shrinking each cluster for CTS)?

### Comment 8 (Kadir Ozdemir, 2025-03-03T01:26:42Z)

No, it does not. I think the CTS gain you expect is because of the claims in  https://salesforce.quip.com/Y0IjATl3QuOf. Please see my related comments there.

### Comment 33 (Ritesh Garg, 2025-05-18T06:59:25Z)

Is there a component(possibly ReplicationLogReader) which needs to monitor that all logs are now replicated and then updates state to Active? or do we let the operator decide this transition?
If automated in ReplicationLogReader do we impose some timeout in which it should make this transition else fail go to DegradedStandby?

### Comment 34 (Kadir Ozdemir, 2025-05-19T06:45:36Z)

Please see https://docs.google.com/document/d/1usap8PCYFU0Z4orznUPvk0tnSv0X-vnbgD_QZejrnv0/edit?tab=t.0
1 total reaction
Ritesh Garg reacted with 👍 at 2025-05-19 06:47 AM

### Comment 3 (David Manning, 2024-11-19T21:26:00Z)

I think it's good to mention the Vagabond Parallel Phoenix Connection specifically, to mention a goal that its availability should not decrease. I can see ways to implement the design that could decrease availability, although perhaps only slightly (i.e., if RPC handlers are all actively processing writes for failover connections, waiting on a timeout for detecting a network partition before marking the peer cluster as standby, blocking Vagabond from making progress until that timeout finishes.)

### Comment 4 (Kadir Ozdemir, 2024-12-10T19:24:37Z)

👍

### Comment 53 (Kadir Ozdemir, 2025-01-23T03:31:10Z)

I am trying to see if there is a valid use case where before image is logged. I think before image would be useful if we decide not to transfer index mutations but generate them on the destination. In that case, we can use the before image and metadata from syscat on the target to generate index mutations. For this case, the before image includes only indexed columns. Do you have any other use case for that?

### Comment 54 (Andrew Purtell, 2025-01-24T18:43:55Z)

We'd want to think through the performance implications for sourcing 'old images' and then conditional updates on the target side but having the old image on hand for a conditional update would guarantee idempotency... a change would not be applied if the expected old value is not actually the current value. Consuming the replication log to produce a CDC feed would be another use case. I know we have another solution in mind for CDC but this could be useful as an alternative?

### Comment 29 (Rushabh Shah, 2024-11-20T18:29:08Z)

Someone at the release planning meeting had a valid point and I am just documenting it. 
For service upgrade and patching purposes, we take down 1 rack and if we have 2 replicas for a given block, then we will have a missing block if just one volume fails from the other 2 AZs.

### Comment 25 (Andrew Purtell, 2024-11-19T17:26:40Z)

We definitely assume uncorrelated volume failures in various aspects of the current design, but this is not borne out by experiences on EBS and is maybe a serious design problem. Volume failures in the EBS service are actually more likely to be correlated than in a first party datacenter. The disks are not individual devices. Infrastructure losses or bugs in the resources backing the EBS service lose many volumes at once. Even if a relatively small proportion of EBS volumes overall are affected, multiple volumes of ours can be victims. We experience intra AZ volume performance degradations and losses that correlate.

I think we can still claim uncorrelated failures but only cross AZ. By that I mean the failure of volumes in one AZ should not be correlated with the failures of volumes in another AZ. I think this works physically too because the AZs are usually separate physical resources separated by a few miles of distance.

### Comment 26 (Kadir Ozdemir, 2024-11-19T18:24:57Z)

Here the text refers to the volumes of a given block which comes from different AZs and so their failures are uncorrelated.
1 total reaction
Andrew Purtell reacted with 👍 at 2024-11-19 19:57 PM

### Comment 27 (David Manning, 2024-11-19T21:21:05Z)

I think the general feel is correct... but the math is probably different because we can't simplify down to one AZ for AFR. At its limit, having 1 million blocks across 2 million EBS volumes spread across 2 AZs... I can't imagine the probabilities are identical that we lose a block. It must be higher chance of losing a block with more volumes. Losing X% of blocks is probably identical, but not the % of losing any block?

FWIW, I would not vote for 2 AZ, just because we tend to lose blocks from our own activities and mismanagement, rather than volume failure. But, if we determined we must do it for cost reasons, I also agree that it's a possible solution. It just has extra operational overhead in block recovery from the primary cluster, where we don't have tooling. (During release planning, we mentioned also the problem of patching/upgrades taking out one replica. Again, solvable... even via HDFS maintenance mode... but requires work to implement.)

### Comment 28 (Kadir Ozdemir, 2024-11-20T20:54:37Z)

Please note the math is for the durability of a given block or the probability of a loosing a given block. The probability of a loosing a block in a cluster needs a different computation. However, comparing durability between a dual 2AZ clusters and a single 3AZ cluster using the durability of a block is still valid.

### Comment 31 (Kadir Ozdemir, 2025-05-19T06:53:47Z)

@ritesh.garg@salesforce.com, please see the open source version and add your comments there. Thanks!

### Comment 32 (Ritesh Garg, 2025-05-19T06:55:34Z)

Sure, will do it there. Thanks!

### Comment 22 (Andrew Purtell, 2024-11-18T16:35:14Z)

Durability should also be considered. Data durability is impacted when we lower the replication factor. Rob Chansler on HDFS-2535 calculated a rate of data loss events for a single HDFS volume when replication is set to 3 as 0.021 events per century. When replication is set to 2 the data loss probability is going to be higher. How much higher, we should evaluate it. When we lose a block we will have customers coming to us with persistent read errors that are currently labor intensive to diagnose. The attachment LosingBlocks.xlsx on that JIRA can be used to calculate the difference, but it also embeds assumptions and experiences operating at Yahoo circa 2010 so it may also be claimed we no longer have a good model for estimating HDFS data loss probability.

### Comment 23 (Kadir Ozdemir, 2024-11-19T17:14:55Z)

Yes. HBase on EBS from different AZs has a much simpler model and durability calculation. I added these to the doc.

### Comment 24 (Andrew Purtell, 2024-11-19T17:29:22Z)

Thank you!

### Comment 14 (David Manning, 2024-11-26T20:52:18Z)

Does this mean lookback will be static based on the duration of a round, or that we will ensure the round is fully replicated before allowing the lookback window to move forward? Will we help the data consistency problem with a new design, or just continue a best effort to be consistent in replication? hbase-team private thread: https://salesforce-internal.slack.com/archives/G023K9QNNF6/p1732654332389459

### Comment 15 (Kadir Ozdemir, 2024-11-26T21:13:56Z)

There are two ways to approach this. One is to use relatively large static minimum max lookback in hours (as different max lookback age can be configured at the table level) and replication round in seconds. The other approach is to make it dynamic like backup coproc does. I think the former should be sufficient with an alert for stuck replication round.
2 total reactions
David Manning reacted with 👍 at 2024-11-26 21:20 PM
Andrew Purtell reacted with 👍 at 2024-12-09 21:50 PM

### Comment 36 (Ritesh Garg, 2025-03-26T21:36:06Z)

Is this state for informational purposes only? Is there an impact of this state on any functionality of any component?

### Comment 37 (Kadir Ozdemir, 2025-03-26T22:00:13Z)

For graceful failover, it indicates that the standby cluster has noticed that the failover process was initiated. However, for unplanned failover, it can be used to signal the standby cluster to become Active, for example, the admin may set the state for the active cluster to Offline and the standby cluster to StandbyToActive.

### Comment 12 (Rushabh Shah, 2024-11-19T17:10:12Z)

What happens if step 1 succeeds but either step 2 or step 3 fails in primary cluster? 
We need to rollback step1, correct?

### Comment 13 (Kadir Ozdemir, 2024-11-19T17:20:18Z)

No, no rollback is necessary. This is the same failure model HBase synchronous replication uses. It is assumed that failed writes will be retried.

