# Tables from HAGroup.docx


## Table 1

| Characteristic | Global Scheduled Procedure (Centralized) | ZK Optimistic Locking (Decentralized - 1) | ZK Group Membership (Decentralized - 2) |
| --- | --- | --- | --- |
| ZK Operations | 1 write per operation | Worst case all RS write an update but only 1 succeeds | 1 write per RS and when state changes(between sync and store&forward) all RS will try to write and only 1 will succeed if we use optimistic locking |
| Delay | Frequency of Procedure | Local operation within RS JVM | ZK propagation delay |
| Extensibility to new use cases | Polling/Handling needs to be added for each use case in procedure. Need to add logic to aggregate different states from different RS | From HAGroupStore perspective, clients can add new states and send updates for their use case | Needs optimistic locking anyway as all RS will try to update state in parallel once group membership changes. |
| Single Point of Failure | Yes, crashed procedure can lead to delay in update | Decentralized approach, any RS can update | Decentralized approach, any RS can update |
| Handling dead/crashed RS | Procedure needs to poll all RS and if any RS crashes during the poll, need to handle it separately. | Any dead/crashed RS will not be able to communicate to ZK | Any dead/crashed RS will not be able to communicate to ZK. Ephemeral nodes will be deleted automatically in group |


## Table 2

| Name | Description | Required | Default Value |
| --- | --- | --- | --- |
|  |  |  |  |

