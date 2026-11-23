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
| `PHERF_AGENT_PORT` | controller | The port of the agent listener. Default 8790. `0` takes a free port; the log line `agent channel listening on port N` gives it. |
| `PHERF_RUN_DIR` | controller | Required. The directory of the run bundles (see Run bundle). The controller makes it when it does not exist. Without it, or when it cannot be made, the controller stops with status 2. |
| `PHERF_CONTROLLER` | agent | Required. The agent listener of the controller, for example `http://pherf-controller:8790`. |
| `PHERF_ZOOKEEPER` | agent | Required. The ZooKeeper quorum of the target cluster, given to Pherf as `-z`. |
| `PHERF_AGENT_ID` | agent | The operational id. If it is not set, the agent uses `HOSTNAME`, then the local host name. The id matches `[A-Za-z0-9._-]{1,63}`. |

The class path of an agent must hold `pherf.properties`, as the phoenix-pherf jar does. An agent
without `PHERF_CONTROLLER`, `PHERF_ZOOKEEPER` or `pherf.properties` stops with status 2.

The logging context key `pherf.agent` holds the operational id of the agent. A log4j2 pattern
shows it with `%X{pherf.agent}`.

## Identities

The architecture separates agent identity into two distinct concepts so that cluster membership is
decoupled from deterministic workload generation.

- The **operational identity** is the id and a new incarnation for each process start. Logs, traces
  and the controller agent table use it. It never changes generated data. A new incarnation of a
  known id replaces the old record, and the old process gets `rejected` and stops with status 2.
- The **data-plane identity** belongs to the slot of an assignment, not to the agent. The
  controller derives it from the run seed, the slot number and the row shares of the step, so the
  same plan gives the same identities:
  - seed: `IndexHash.mix64(runSeed + (slot + 1) * IndexHash.GAMMA)`
  - offset: the sum of the row shares of the lower slots, or 0 when the step gives no `rows`
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

## Plans

An operator gives a run as a JSON plan. The controller reads it with `RunSpec.parse`, which is
strict: an unknown key, a repeated key, a number that is not an integer and a value of the wrong
type are errors, and each error names its JSON path, for example
`steps[1].weights: 3 weights for 2 agents`. The steps run in order.

```json
{"seed": 8070, "runClass": "measurement",
 "properties": {"pherf.verdict.noisyHost": "false"},
 "steps": [{
   "agents": 3, "weights": [1, 2, 1],
   "scenario": "relational_scenario.xml",
   "documents": {"relational_scenario.xml": "<datamodel...>",
                 "relational_smoke_profile.xml": "<workloadProfile...>"},
   "args": ["-q", "-disableSchemaApply"],
   "rate": 300, "durationMs": 60000, "threads": 12, "rows": 30000,
   "properties": {"pherf.default.threadpool": "16"}}]}
```

| Field | Required | Meaning |
|---|---|---|
| `seed` | yes | Any 64-bit integer. It derives the data-plane identities. |
| `runClass` | yes | `validation` or `measurement`. In a validation run, a FAIL of class duration of the fleet verdict reads as ADVISORY. |
| `properties` | no | Run-level Pherf properties. They win over the step properties. The `pherf.verdict.*` keys of this level (VERDICT.md) also set the fleet verdict; the step properties do not. |
| `steps` | yes | At least one step. |
| `steps[].agents` | yes | 1 to 1000. |
| `steps[].weights` | no | One integer of at least 1 for each agent. Absent means all 1. |
| `steps[].scenario` | yes | The name of the scenario document. |
| `steps[].documents` | yes | File name to text. It holds the scenario, its profiles and its `.sql` files. A name matches `[A-Za-z0-9._-]{1,128}` and does not start with `.`. |
| `steps[].args` | no | Pherf options, given to Pherf as written. |
| `steps[].rate` | no | The fleet peak rate in operations per second, at least 1. |
| `steps[].durationMs` | no | The run duration of each agent, at least 1. |
| `steps[].threads` | no | The fleet concurrency, at least 1. |
| `steps[].rows` | no | The fleet row count, at least 1. |
| `steps[].properties` | no | Step Pherf properties. |

A step pairs its scenario with a workload profile through the `profile` attribute of the query
set, and Pherf finds the profile among the documents. The rate schedule is the `<openLoop>` curve
of the profile, scaled to `rate` and `durationMs`, so a plan has no second schedule format.

The submit check refuses a plan before any agent gets work:

- an option that the agent sets, in any spelling (`-x`, `--x`, `-x=v`): `scenarioFile`, `label`,
  `z` and `zookeeper`, `runDurationMs`, `threadCount`, `targetRate`, `rowCountOverride` and
  `validate`;
- a mode that skips the validation or uses the query server: `analyze`, `compare`, `listFiles`,
  `h` and `help`, `t` and `thin`, `s` and `server`;
- a property that the agent sets: `pherf.default.results.dir` and `pherf.default.hdr.intervalMs`;
- `${pherf.slot.offset}` in a step with no `rows`;
- a slot with a share of 0 of `rate`, `threads` or `rows`, and a row share over 2147483647;
- an `assign` of slot 0 of a step that does not fit 1 KiB below the 16 MiB body limit. In step 0
  it holds the later slices that slot 0 validates (see Agent workload);
- a run-level `pherf.verdict.*` value that Pherf refuses, for example
  `properties: pherf.verdict.noisyHost=maybe is not true or false`.

A step with more agents than are free fails the submit, or for a later step, the run.

Each agent gets the Pherf properties in this order, lowest first: the `pherf.properties` of its
class path, the step properties, the run-level properties, and the agent keys.

## Work split

One rule splits each total of a step: the largest remainder method in exact integer arithmetic.
The shares sum to the total, each share is the floor or the ceiling of `total * w / W`, and a tie
goes to the lower slot.

| Quantity | Slot i gets | Pherf option |
|---|---|---|
| `rate` R | r_i, with sum R | `-targetRate r_i` |
| `threads` T | t_i, with sum T | `-threadCount t_i` |
| `rows` N | n_i, with sum N | `-rowCountOverride n_i` |
| keyspace | rows `[o_i, o_i + n_i)`, with o_i the sum of the lower n_j | `${pherf.slot.offset}` |
| `durationMs` D | D, not split | `-runDurationMs D` |

A quantity that the step does not give is not split: each agent runs the value of its documents.

- Open loop: `-targetRate` makes a flat stage exactly r_i, so each agent gets r_i * D / 1000
  arrivals, and the fleet R * D / 1000, when D * r_i is a multiple of 1000 (D in whole seconds is
  enough). On other curves each agent is less than 1 arrival from its exact part. The count of
  one operation kind is exact only when the total weight of the mix divides the arrivals.
- Closed loop and `-mt`: `numberOfExecutions` and `numOperations` are not split, because Pherf
  has no option for them. Each agent runs the document value.

## Keyspace

The offsets are contiguous, so a later step with another agent count or other weights covers the
same `[0, N)`. With a co-generation `clusterLimit`, the leading key comes from the local row index
of the slot, so a step that reads the rows of another step needs the same agent count and weights.

- Co-generation: put `${pherf.slot.offset}` in `<coGeneration offset>` and give the step `rows`.
  Keep the co-generation seed fixed and keep slot tokens out of the key columns, so that a key
  depends only on its global row index.
- Rule-based generators: divide the keys with `${pherf.slot.prefix}`.
- Poisson arrivals: `${pherf.slot.seed}` on the profile gives each agent its own arrivals.
- `writeParams` can write past the row share of a slot. Do not use it with a split.
- Place any `-l` data load in a dedicated step so that the subsequent measured `-q` query step
  starts simultaneously across every agent (see Start synchronization).

## Agent workload

An agent runs Pherf in a child JVM, never in its own JVM: one `-validate` child in each prepare
and one run child at each launch, so Pherf static state never carries over between runs, a Pherf
error cannot stop the agent, a cancel can stop the work, and the workload cannot starve the
heartbeat.

Each agent has one work directory, made at start in the temporary directory. Each prepare empties
it and writes:

| Path | Content |
|---|---|
| `slice/` | The documents, with the slot tokens replaced. It is on the class path of the child. |
| `overlay/pherf.properties` | The properties of the assignment and the agent keys. It is first on the class path of the child. |
| `RESULTS/`, `RESULTS_HDR/` | The Pherf results. The agent sets `pherf.default.results.dir` here. |
| `later/<step>-<slot>/` | In step 0: a later slice that the prepare validates, with its own `slice/`, `overlay/` and `RESULTS/`. |

The files stay until the next prepare. A child runs
`java <the agent JVM options, no jdwp> -cp overlay:slice:<agent class path>
org.apache.phoenix.pherf.distributed.agent.PherfChild <args> -scenarioFile slice/<scenario>
-label validate|run -z $PHERF_ZOOKEEPER [share options] [-validate]`
in the work directory, with the environment of the agent. The exit status is the status of Pherf.
A child stops at once when its stdin ends, so it never outlives its agent. Its output goes to the
agent log, with the exception of the two start gate marker lines (see Start synchronization).

- **Prepare** runs Pherf's own offline validation on the slice with the same options as the run.
  The share options change the scenario before the rules, as in the run, so the rules check the
  share of the slot. Exit 0 makes the agent READY. Exit 65 gives one error for each finding of
  `RESULTS/VALIDATION_validate.json`. Another exit gives the last 5 output lines.
- In step 0, slot s also validates slot k of each later step when k mod n = s, n being the agent
  count of step 0, one child at a time after its own slice. An error of a later slice starts with
  `step <step> slot <slot>: `. The prepare reports the errors of all its slices, so a bad slice of
  any step aborts the run before the first `start`. Each later step validates its own slices again
  in its prepare.
- **Launch** starts the run child at once. The child connects, applies the schema, warms up and
  then holds at its start gate (the agent reports `ARMED`). Every 1000 ms the agent reads the new
  complete lines of `RESULTS_HDR/LATENCY_run_intervals.hlog` and sends each one as a frame, for
  every operation and phase, from the launch on, so the warmup frames come before the gate. The
  time bounds come from the decimal text of the line, so they are exact to the ms. After the exit,
  a last read sends the rest. Exit 0 then completes the slot with its final report: the window and
  the count of each key of `RESULTS_HDR/LATENCY_run.json`, its `schedules` entries as they are, and
  the lines of `RESULTS_HDR/VERDICT_run.tsv` (none when the file is absent). Exit 0 with no valid
  JSON report fails the slot with `pherf wrote no valid LATENCY_run.json: ...`. Another exit or a
  bad line fails it.
- **Start** writes `startAtMs` to the stdin of the armed child, which opens its gate at that time.
- **Cancel** sends SIGTERM to a live child, also to a child at its gate, and SIGKILL after 5
  seconds. Each stage reports its end only after its child has exited.

## Run states

| State | Meaning |
|---|---|
| `RUNNING` | The run has a step that is not complete. |
| `COMPLETE` | Every step is complete, and the run bundle is written. A run whose bundle cannot be written is `FAILED` with the reason `cannot write the run bundle: ...`. |
| `ABORTED` | The run failed before any step got `launch`, so it sent no database traffic. |
| `FAILED` | The run failed after a step got `launch`. |

| Slot state | Meaning |
|---|---|
| `ASSIGNED` | The agent has the slot and prepares it. |
| `READY` | The prepare passed. The slot waits for the other slots of the step. |
| `LAUNCHED` | The step got `launch`. The run child warms up. |
| `ARMED` | The run child holds at its start gate. |
| `RUNNING` | The slot has its start time. |
| `DONE` | The controller holds every frame of the slot and its final report. |
| `FAILED` | The slot failed. Its error is `agent <id>: <error>`. |
| `CANCELLED` | Another slot failed the run, and this slot got `cancel` or `release`. |

One failing slot fails the run: a prepare error (for example a validation finding), a start
failure, an eviction, a `goodbye`, a new incarnation, a misconfigured recorder or a telemetry count
that does not match. The run reason is the first slot error.
The other agents of the step get `cancel` (while they prepare, wait or run) or `release` (when
done), and all go idle. Their slots that are not `DONE` become `CANCELLED`. A cancelled slot that
then reports its own failure becomes `FAILED` with its error, so the run shows every error. No slot
is tried again, and the later steps do not run.

## Agent channel

The controller's agent listener exposes two HTTP endpoints, each accepting `POST` requests with a
JSON payload.

| Path | Stream |
|---|---|
| `/v1/channel/control` | Carries registration requests, long-polled heartbeats, and commands. |
| `/v1/channel/telemetry` | Carries interval latency histograms and their acknowledgments. |

The server responds with HTTP status 200 containing the reply payload on success, 400 with a
`rejected` message when the body is not a valid request, 405 for any other HTTP method, 413 when a
request body exceeds 16 MiB, and 404 for any unrecognized path. Each agent request uses a 5 second
connect and read timeout. On the client side, an agent retries failed requests after a 1 second
backoff for every failure except HTTP 400, HTTP 404, or a reply that it cannot decode, any of which
causes the agent to terminate with status 2.

Every protocol message carries a common envelope consisting of `v` (currently 1), `type`, and
`trace`. Messages originating from an agent additionally carry `agent` (the operational id) and
`incarnation`. The trace identifier is formatted as `<agent>/<control|telemetry>/<n>`, and the
controller echoes the matching trace both in the JSON reply body and in the `X-Pherf-Trace`
response header.

### Control stream

Control communication follows a state reconciliation model in which each request reports the full
state of the agent and each reply carries a single command. Because the controller computes the
command from its desired state and the state reported by the agent, repeating the same request has
no additional effect. When no command is pending, the controller holds the poll for at most 1
second and then replies with `wait`, emitting the same command at most once per hold. Because only
one control request from a given agent is in flight at a time, a newer heartbeat that still
requires the previous command indicates that the earlier reply was lost, causing the controller to
retransmit that command immediately one time. The sole exception is `cancel`, because the agent
continues to report its active phase until its workload stops. Each reply also carries `rxMs`, the
controller wall-clock time when the request arrived, and `txMs`, the time when the reply was sent.

| Request | Fields | Meaning |
|---|---|---|
| `register` | | Join the fleet |
| `heartbeat` | `seq, status{phase, runId, step, finalSeq, error, startedMs, report}, clock{sentMs, rxMs, txMs, receivedMs}` | The long poll. `phase` is `IDLE`, `PREPARING`, `READY`, `LAUNCHED`, `ARMED`, `RUNNING`, `DONE` or `FAILED`. `startedMs` is the agent time when the start gate opened, else 0. `report` is present only with `DONE`: `{startMs, endMs, counts{tag: n}, schedules[], verdict[]}`, the final report of the slot (see Agent workload). `clock` holds the times of the last completed control exchange; it is absent before the first one |
| `goodbye` | | Leave the fleet now |

| Reply | Fields | Meaning |
|---|---|---|
| `registered` | | The agent is in the fleet |
| `wait` | | The hold ended with no command |
| `assign` | `assignment{runId, step, identity{slot, seed, offset, prefix}, scenario, documents, args, share{durationMs, threads, rate, rows}, properties, validations}` | Prepare this slot. An absent share field means no override. `validations` holds the later slices that the prepare validates, each an `assignment` with no `validations`; it is absent when empty |
| `launch` | `runId, step` | Immediately launches the run child process for the prepared slot. |
| `start` | `runId, step, startAtMs` | Opens the armed slot start gate at this agent clock time. |
| `cancel` | `runId` | Stop the work of this run |
| `release` | `runId` | Forget this finished or failed run and go idle |
| `shutdown` | | Stop with status 0 |
| `reregister` | | The controller does not know the agent; register again |
| `rejected` | `reason` | The controller refuses the agent; stop with status 2 |

The `seq` field numbers each control request sequentially within an agent incarnation, starting
from 1. If the controller receives a heartbeat whose `seq` is not greater than the last sequence
number it applied for that incarnation, it immediately replies with `wait` so that a late duplicate
copy produces no side effects.

The controller evicts an agent when it gets no request from it for more than 10 seconds of
monotonic time. The slot of an evicted agent fails, and so does its run (see Run states). An agent
that gets no reply on either stream for more than 10 seconds cancels its work, so an evicted agent
does not keep its Pherf child running against the database.

An agent reports `DONE` only when the ack holds the last frame, and the controller completes a slot
only when it holds every frame up to `finalSeq`. A `DONE` with no `report` fails the slot.

### Start synchronization

Step execution begins in two phases because the initialization time required for a run child
process to become ready (spanning JVM startup, JDBC driver initialization, ZooKeeper and metadata
discovery, the validation gate, schema application, and warmup) has no fixed upper bound.

1. Once every slot in the step reaches `READY`, the controller issues a `launch` command to each
   participating agent. The run child process executes its warmup phase and pauses at the Pherf
   start gate, after which the agent reports `ARMED`.
2. Once no slot in the step remains in `LAUNCHED` and at least one slot is `ARMED`, the controller
   computes the target start epoch on its own clock as now + 2000 ms + the largest round-trip
   delay among the armed agents. Each armed agent receives a `start` command carrying this epoch
   adjusted by the agent's estimated clock offset, ensuring that every agent targets the same
   physical instant on its local clock. The 2000 ms lead time accommodates delivery jitter as well
   as a single reset request followed by its 1 second retry backoff.
3. The child process waits until its local wall clock reaches `startAtMs`, opens the start gate to
   begin steady-state workload dispatch, and reports the actual opening timestamp in `startedMs`.

The controller estimates each agent's clock offset from its control exchanges using an NTP-style
calculation with the controller serving as the reference clock. For a single control exchange, let
t1 and t4 denote the agent timestamps when the request was sent and the reply was received, and
let t2 and t3 denote the controller timestamps (`rxMs` and `txMs`). The clock offset (defined as
agent minus controller) is ((t1 - t2) + (t4 - t3)) / 2, and the round-trip network delay is
(t4 - t1) - (t3 - t2), which excludes the controller's long-poll hold duration. A sample is valid
whenever the computed delay is non-negative and t3 is not earlier than t2. The controller retains
the last 8 valid samples for each agent and selects the sample with the smallest delay, breaking
ties in favor of the most recent sample. The estimation error of the offset is bounded by at most
half of that minimum delay plus 1 ms. Both controller and agent use wall clocks, and the child
process shares the wall clock of its agent host.

The run view exposes the step epoch along with each slot's agent clock offset and delay at the
epoch, its scheduled `startAtMs`, and its controller-normalized dispatch time (`startedMs` minus
the offset). The error bound on a slot's dispatch time is half the round-trip delay plus 2 ms to
account for millisecond clock resolution. Because the estimated offset cancels out when computing
the spread between a slot's scheduled and actual dispatch times, the offset estimation error
appears only in the error bounds. Once every slot that received a `start` command has reported its
dispatch time, the controller computes and logs the step skew, which represents a rigorous upper
bound on the true start skew across the fleet: the latest dispatch time minus the earliest
dispatch time, plus the two largest individual error bounds. Any slot whose error bound exceeds
25 ms (corresponding to a round-trip delay above approximately 46 ms) triggers a warning indicating
that a 50 ms synchronization bound cannot be proven for that slot. If any slot in a step lacks a
valid clock sample, no skew value is computed for the step.

- If a `start` command arrives after its target epoch or a sleeping thread wakes up late, the
  child opens its start gate immediately. The slot dispatches late and the resulting skew reflects
  the delay, without aborting or retrying the run. In particular, a `start` reply lost to the 5
  second read timeout arrives approximately 4 seconds after its scheduled epoch.
- Open-loop workloads gate the dispatch schedule after all worker threads have completed warmup,
  whereas closed-loop workloads gate each runner thread individually after its own warmup so that
  the first thread on every agent starts in sync.
- Data loads (`-l`) and multi-tenant (`-mt`) workloads do not use a start gate; their slots
  transition directly from `LAUNCHED` to `DONE` without a `start` command, and the step reports no
  skew.
- The synchronization error bound assumes that no host wall clock steps during startup. A step
  containing two slots whose round-trip delays each exceed approximately 46 ms cannot prove a
  50 ms skew bound.

### Telemetry stream

| Message | Fields |
|---|---|
| `intervals` | `runId, step, frames[{seq, operation, phase, startMs, endMs, total, recorder{lowestUs, highestUs, significantDigits}, histogram}]` |
| `ack` | `runId, step, heldSeq` |

- `seq` starts at 1 for each run and step. A batch has at most 256 frames, from the lowest unacked
  sequence, and one batch is in flight.
- `total` is the cumulative count of the key (operation and phase) on the agent, through this frame.
- `recorder` holds the actual parameters of the histogram. Pherf recorders use 1 us, 600,000,000 us
  and 3 digits.
- `histogram` is the histogram in the compressed base64 form of an HdrHistogram log line.
- `heldSeq` is the highest sequence that the controller holds with no gap below it. The agent sends
  each frame above `heldSeq` again. For a run that the controller does not know, an unbound slot
  or an old incarnation, `heldSeq` is the highest sequence of the batch, and the controller holds
  nothing of it.
- The controller holds a frame only when its sequence is `heldSeq + 1`. It skips a sequence that it
  holds, and stops at a gap. It decodes each held frame once and adds its count to the samples and
  to the counter of its key of the slot. The run view shows these cumulative counters
  (`SlotView.getCounters`). A frame that does not decode fails the slot. The ack holds no more
  frames, the agent gets `cancel`, and it stays in the fleet.
- A frame whose `recorder` is not 1 us, 600,000,000 us and 3 digits, or whose histogram has other
  parameters than its `recorder`, is never held. A merge equals one recorder only for one
  configuration. The controller removes the agent and fails its slot, and so the run, with a reason
  that names each parameter, for example `agent a2 is misconfigured: frame 4 has highestUs
  3600000000, not 600000000`. The reply is `rejected`, and the agent stops with status 2.
- A frame whose `total` is not the counter of its key plus its own count shows a lost or a
  duplicated frame: the slot fails with `lost or duplicated telemetry: ...`. The ack holds no more
  frames, and the agent stays in the fleet.
- A batch that the listener cannot decode gets 400 `rejected`; the agent then stops with status 2.
- When the telemetry requests fail for more than 10 seconds from the first failure after an ack,
  and frames are pending, the agent stops its run child and then reports `FAILED` with the error
  `telemetry stream failed for more than 10000 ms`. Thus the agent holds frames for at most about
  15 seconds.
- An ack below the acked sequence means that the controller lost frames that the agent no longer
  has. The agent fails its work in the same way.

### Keepalives

The long-polled control request serves as a bidirectional keepalive for the control stream: the
agent receives a reply within 1 second, immediately issues the next poll, and obtains a fresh clock
sample from every completed exchange. The telemetry stream transmits approximately one batch per
second while frames are pending and remains silent when the agent is idle, as an idle telemetry
stream holds no state. Because the 5 second socket read timeout is longer than the 1 second poll
hold duration and shorter than the 10 second liveness deadline, a single lost reply is retried
after 6 seconds and recovers before either side evicts or cancels the other.

| Side | Rule |
|---|---|
| Controller | Evicts any agent with no request on either stream for more than 10 seconds. |
| Agent | Cancels active work after no reply arrives on either stream for more than 10 seconds. |
| Agent | Fails active work if telemetry fails for more than 10 seconds with frames pending. |
| Agent | Fails active work if an acknowledgment is below the previously acknowledged sequence. |

### Guarantees

- A disconnect, a duplicate or a late copy of a request: the controller holds each sequence of a
  slot exactly once, and the counter of each key equals the count that the agent recorded. The
  fleet verdict compares the counters of each completed slot with its final report.
  A fault that loses frames fails the run, so a run with lost frames is never `COMPLETE`.
- An agent restart during a run is not supported: the run child stops with its agent. The new
  incarnation replaces the record, its slot fails and the run is `FAILED`. The frames of the old
  incarnation get an ack and are never held, so nothing is counted twice.
- A controller restart loses the active runs. The agents get `reregister`, cancel their work and
  drop their frames, and the new controller acks old frames without holding them. Nothing is
  counted twice, and no lost run is reported complete. The directory of a lost run has no
  `manifest.json`.
- The memory of an active run does not grow with its length: one histogram of about 172 KB for each
  key, the counters of each slot, and one buffered raw log for each slot. When the bundle is
  written, the controller drops the histograms and the final reports. Decode and merge run under
  the controller monitor, at about 100 us for each frame, so the controller holds about 1000 agents
  with 12 keys each.

## Run bundle

`submit` makes the directory `$PHERF_RUN_DIR/<runId>/` (a failure refuses the submit). Each held
frame goes to the raw log of its slot at once. At each end of the run (`COMPLETE`, `FAILED` or
`ABORTED`), in the same step that sets the state, the controller writes the rest of the bundle, with
`manifest.json` last. So a run is `COMPLETE` only when its bundle is in place, and a directory with
no manifest is an interrupted run. The layout is the run-bundle layout of the Pherf offline
analysis (ANALYSIS.md), so `-analyze` reads a bundle as it is.

| Path | Content |
|---|---|
| `manifest.json` | `bundle_format_version` 1; `run{id, class, result, state, reason, seed, started_ms, ended_ms}`, where `result` is the highest severity of the fleet verdict rows; `quiet_host{tags}`, which is `["noisy-host"]` when the run-level `pherf.verdict.noisyHost` is true, else `[]` |
| `drivers/pherf/results_HDR/LATENCY_run.json` | The fleet report |
| `drivers/pherf/results_HDR/LATENCY_run.hlog` | The merged run log: the header of LATENCY.md, base time `startMs`, one histogram for each key over `[startMs, endMs]` |
| `drivers/pherf/results_HDR/VERDICT_run.tsv` | The fleet verdict |
| `drivers/pherf/results_HDR/slots/<step>-<slot>.hlog` | The raw interval log of a slot: the header of LATENCY.md, the comment `#[pherf-distributed run R step S slot I agent A, agent clock]`, base time the start of the first frame, and one line for each held frame. Absent for a slot with no frame |

### Fleet report

The fleet report is the JSON report of version 1 of LATENCY.md, so a reader of a Pherf report reads
it with no change: label `run`, the pinned recorder, `hlog` `LATENCY_run.hlog`, and no `intervals`
(the interval series is in the raw logs). Each key (operation and phase) has one histogram that
merges every held frame of every slot of every step, sorted by operation and then phase. The merge
equals one recorder that took all samples: the same count, p50, p99, p99.9 and max.

Each time is on the controller clock: the agent time minus the clock offset of its slot. A slot with
no start gate takes the offset of its agent at `DONE`. `startMs` and `endMs` cover the recording
windows of all slots, each from its final report, else from its first and last held frame. With no
held frame, both are the end time of the run. `schedules` holds the entries of each slot, in step
and slot order, with `startMs`, `endMs` and `lastTakeMs` on the controller clock and the added
fields `step`, `slot` and `agent`.

The added member `fleet`, which a LATENCY.md reader ignores:

| Field | Meaning |
|---|---|
| `runId`, `seed`, `runClass`, `contaminated`, `state`, `reason` | The run |
| `steps[]` | `step, state, startAtMs, skewMs, startMs, endMs, slots[]`: the epoch and the skew bound of the step, and the window of its slots |
| `steps[].slots[]` | `slot, agent, incarnation, seed, offset, prefix, share, state, error, offsetMs, delayMs, startAtMs` (agent clock), `dispatchMs`, `startLagMs` (the dispatch time minus the earliest one of the step: the skew of each agent), `frames` (the held sequence), `samples`, `counts{tag: n}` and `intervals` (the raw log path) |

### Fleet verdict

`VERDICT_run.tsv` has the row format of VERDICT.md, source `pherf`, and 8 rows in this order:

| Check | Class | Rule |
|---|---|---|
| `pherf.health.attainment` | duration | The Pherf check over the schedules of all slots, so the worst schedule counts |
| `pherf.health.saturation` | duration | The Pherf check over the schedules of all slots |
| `pherf.health.client_pool` | count | The row of the agent with the highest severity, the lowest step and slot on a tie, after `step S slot I (agent A): `. The evidence exists only on each agent host. SKIP when no slot reported a verdict |
| `pherf.health.tail_latency` | duration | The Pherf check over the merged histograms |
| `pherf.health.read_skew` | share | As `client_pool` |
| `pherf.fleet.start_skew` | duration | FAIL when the skew bound of a step is over 50 ms. Else SKIP when a gated step has no skew (a slot with no clock sample, or no dispatch), or when no step has a start gate |
| `pherf.fleet.telemetry` | count | FAIL when a frame total failed a slot (lost or duplicated telemetry), or when the counter of a key of a `DONE` slot differs from the count of its final report. SKIP when no slot is `DONE` |
| `pherf.fleet.agents` | count | PASS when the run is `COMPLETE` and each slot is `DONE`, else FAIL with the state, the reason and each failed slot |

The settings are the run-level `pherf.verdict.*` properties. A FAIL of class duration reads as
ADVISORY, with the prefix `validation run: ` in a validation run or the noisy host prefix of
VERDICT.md in a contaminated run. A row of class count or share never changes.

The harness `px-bundle validate` validates a run directory of the harness, not a controller
bundle: the controller has no backend, no cluster facts and no steady window. In the harness, the
`pherf-distributed` driver (round H3) copies the controller bundle into its driver directory and
adds the 8 rows to the verdict stream of the harness run.


### Version rule

The controller and all agents in a deployment must be provisioned from the same build. Message
parsers ignore unrecognized JSON fields to allow additive evolution, whereas any breaking change
that an existing reader cannot parse must advance both the protocol version `v` and the HTTP path
prefix to `/v2/`. Any request bearing a mismatched `v` value is refused with HTTP status 400 and a
`rejected` reply.
