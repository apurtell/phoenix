# Comments from HAGroup.docx

### Comment 2 (Kadir Ozdemir, 2025-06-30T11:01:40Z)

We have renamed CRR to HA Group Store records or simply HA records. We also like to use the term state rather than the term role.

### Comment 0 (Jacob Isaac, 2025-06-27T20:28:36Z)

Although single updates can be achieved via - ZK Optimistic Locking. Since there are multiple resources that need to be updated atomically, It will need some kind of additional locking in place to ensure the atomicity of the transaction.

### Comment 1 (Ritesh Garg, 2025-08-08T01:02:54Z)

If by multiple resources you mean local and peer ZK quorum, the proposed HAGroupStore implementation with two states proceeds via back and forth between local and peer state updates ensuring that a specific order of states is maintained.

