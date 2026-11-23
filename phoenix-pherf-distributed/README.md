<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Distributed Pherf

Distributed Pherf coordinates a fleet of Pherf agents to drive concurrent load against a single
target cluster. A central controller distributes workload assignments across the agent fleet, and
each agent streams its recorded interval histograms back to the controller for aggregation. The
communication layer is built exclusively on the standard JDK HTTP server and `java.net` using JSON
request and response bodies, avoiding any external RPC framework or servlet web container.

## Processes

| Process | Main class | Role |
|---|---|---|
| Controller | `org.apache.phoenix.pherf.distributed.controller.ControllerMain` | Maintains the
active agent table and orchestrates test runs. Passively listens for inbound agent connections and
never initiates outbound connections to an agent. |
| Agent | `org.apache.phoenix.pherf.distributed.agent.AgentMain` | Establishes two outbound
connections to the controller to receive and execute assigned workloads, without exposing any
network listener of its own. |

An agent process remains resident across consecutive test runs and terminates only when it receives
a SIGTERM signal or a `shutdown` command from the controller.

Both processes report their termination cause through a shared set of exit status codes:

| Status | Cause |
|---|---|
| 0 | The process completed a requested stop triggered by SIGTERM or the `shutdown` command. |
| 1 | The process terminated due to an unexpected internal error. |
| 2 | The process halted due to a bad configuration or because the controller rejected the agent. |

When an agent receives SIGTERM, it cancels any in-flight workload, sends a `goodbye` message to the
controller, and terminates within 5 seconds. When the controller receives SIGTERM, it stops its
agent listener.

## Environment

| Variable | Process | Meaning |
|---|---|---|
| `PHERF_AGENT_PORT` | controller | Specifies the TCP port on which the controller's agent listener
binds, defaulting to 8790. Setting this value to `0` binds to an ephemeral free port, which is
reported in the log line `agent channel listening on port N`. |
| `PHERF_CONTROLLER` | agent | Required setting that specifies the base URI of the controller's
agent listener, such as `http://pherf-controller:8790`. |
| `PHERF_AGENT_ID` | agent | Defines the operational id of the agent; when omitted, the agent falls
back first to `HOSTNAME` and then to the local host name. The id must match
`[A-Za-z0-9._-]{1,63}`. |

The agent populates the diagnostic logging context key `pherf.agent` with its operational id so
that a log4j2 pattern layout can include it via `%X{pherf.agent}`.

## Identities

The architecture separates agent identity into two distinct concepts so that cluster membership is
decoupled from deterministic workload generation.

- The **operational identity** pairs the agent's configured id with a fresh incarnation token
  generated each time the process starts. This identity is used for logging, request tracing, and
  tracking membership in the controller's agent table, and it never influences generated test data.
  When a new incarnation registers under an existing id, it supersedes the previous registration in
  the controller; the displaced process subsequently receives `rejected` and exits with status 2.
- The **data-plane identity** is bound to the logical slot of an assignment rather than to the
  physical agent executing it. The controller deterministically derives each slot's parameters from
  the run seed, the zero-based slot number, and the step stride so that repeating a run yields
  identical data-plane identities across the fleet:
  - seed: `IndexHash.mix64(runSeed + (slot + 1) * IndexHash.GAMMA)`
  - offset: `slot * stride`
  - prefix: `p000` to `p999`

During preparation, the agent substitutes the following placeholder tokens across every workload
document and property value in its assignment:

| Token | Value |
|---|---|
| `${pherf.slot.seed}` | The deterministic random seed derived for the slot. |
| `${pherf.slot.offset}` | The starting row offset computed for the slot. |
| `${pherf.slot.prefix}` | The key prefix assigned to the slot. |

Encountering any unrecognized `${pherf.slot.*}` token causes preparation to fail with an error,
while all other text passes through unchanged. For example:

```xml
<coGeneration seed="${pherf.slot.seed}" offset="${pherf.slot.offset}"/>
<openLoop seed="${pherf.slot.seed}" ... />
<prefix>${pherf.slot.prefix}</prefix>
```

Because rule-based data generators maintain their own internal seed, key partitioning across slots
on that code path relies strictly on the substituted prefix and row offset.

## Agent channel

The controller's agent listener exposes two HTTP endpoints, each accepting `POST` requests with a
JSON payload.

| Path | Stream |
|---|---|
| `/v1/channel/control` | Carries registration requests, long-polled heartbeats, and commands. |
| `/v1/channel/telemetry` | Carries interval latency histograms and their acknowledgments. |

The server responds with HTTP status 200 containing the reply body on success, 400 with a
`rejected` message when the payload is not a valid request, 405 for any other HTTP method, 413 when
a request body exceeds 16 MiB, and 404 for any unrecognized path. On the client side, an agent
retries failed requests after a 1 second backoff for all transient failures, but immediately exits
with status 2 if it receives a 400 status, a 404 status, or a reply payload that it cannot decode.

Every protocol message carries a common envelope consisting of `v` (currently 1), `type`, and
`trace`. Messages originating from an agent additionally carry `agent` (the operational id) and
`incarnation`. The trace identifier is formatted as `<agent>/<control|telemetry>/<n>`, and the
controller echoes the matching trace both in the JSON reply body and in the `X-Pherf-Trace`
response header.

### Control stream

Control communication follows a level-triggered state reconciliation model in which each request
reports the complete current state of the agent and each reply carries a single command. Because
the controller derives each command by comparing its desired state against the state reported by
the agent, repeating the same request is idempotent and has no additional effect. When no command
is ready, the controller holds the long-poll request for at most 1 second and then replies with
`wait`.

| Request | Fields | Meaning |
|---|---|---|
| `register` | | Requests admission of the agent into the controller's active fleet. |
| `heartbeat` | `seq, status{phase, runId, step, finalSeq, error}` | Issues a long-polled status
report in which `phase` is `IDLE`, `PREPARING`, `READY`, `RUNNING`, `DONE`, or `FAILED`. |
| `goodbye` | | Notifies the controller that the agent is leaving the fleet immediately. |

| Reply | Fields | Meaning |
|---|---|---|
| `registered` | | Confirms that the agent is registered in the fleet. |
| `wait` | | Indicates that the long-poll hold expired with no command to issue. |
| `assign` | `assignment{runId, step, identity{slot, seed, offset, prefix}, documents, properties}`|
Instructs the agent to validate and prepare the described workload slot. |
| `start` | `runId, step, startAtMs` | Directs the agent to start the prepared slot at this time. |
| `cancel` | `runId` | Directs the agent to stop all active work belonging to the specified run. |
| `release` | `runId` | Directs the agent to discard this finished or failed run and become idle. |
| `shutdown` | | Commands the agent to terminate cleanly and exit with status 0. |
| `reregister` | | The controller has no record of the agent and requires it to register again. |
| `rejected` | `reason` | The controller refused the agent, requiring it to exit with status 2. |

The `seq` field numbers each control request sequentially within an agent incarnation, starting
from 1. If the controller receives a heartbeat whose `seq` is not greater than the last sequence
number it applied for that incarnation, it immediately replies with `wait` so that a late duplicate
copy produces no side effects.

The controller evicts an agent if more than 10 seconds of monotonic time elapse without receiving a
request from it. When an agent is evicted, its assigned slot is marked as failed, the owning run
fails as a whole, and all other agents participating in that run receive a `cancel` command.

To guarantee lossless telemetry delivery before completion, an agent reports the `DONE` phase only
after its final telemetry frame has been acknowledged, and the controller marks a slot complete
only after it holds every frame up to `finalSeq`.

### Telemetry stream

| Message | Fields |
|---|---|
| `intervals` | `runId, step, frames[{seq, operation, phase, startMs, endMs, recorder{lowestUs, highestUs, significantDigits}, histogram}]` |
| `ack` | `runId, step, heldSeq` |

- `seq` numbers frames sequentially starting at 1 for each run and step, and a single batch may
  contain at most 256 frames.
- `recorder` captures the actual precision and range parameters of the underlying histogram; Pherf
  recorders use 1 us, 600,000,000 us, and 3 significant digits.
- `histogram` carries the recorded latency distribution encoded in the compressed base64 format of
  an HdrHistogram log line.
- `heldSeq` reports the highest contiguous sequence number that the controller currently holds with
  no missing frames below it, and the agent retransmits every frame whose sequence number is above
  `heldSeq`. When a batch pertains to a run that the controller does not recognize, `heldSeq` is
  set to the highest sequence number in the batch so that the agent discards those stale frames.
- The controller may reply with `rejected` in place of `ack`, upon which the agent terminates with
  status 2.

### Version rule

The controller and all agents in a deployment must be provisioned from the same build. Message
parsers ignore unrecognized JSON fields to allow additive evolution, whereas any breaking change
that an existing reader cannot parse must advance both the protocol version `v` and the HTTP path
prefix to `/v2/`. Any request bearing a mismatched `v` value is refused with HTTP status 400 and a
`rejected` reply.
