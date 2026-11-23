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
active agent registry, test definitions, and execution runs. Exposes the REST management API and
listens for inbound agent connections, while never initiating outbound connections to any agent. |
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

Upon receiving SIGTERM, an agent cancels any active workload, transmits a `goodbye` message, and
terminates within 5 seconds. When the controller receives SIGTERM, it shuts down its network
listeners without finalizing active state, so a SIGTERM signal and an unexpected crash have the
same effect on any in-flight run (see Persistence and restart).

## Environment

This environment contract governs the D6 container images and the test harness (H3, K5). The
controller and agent JVM processes consume their respective process variables directly, while
`pherf-distributed.sh` consumes the script-level variables.

| Variable | Used by | Default | Meaning |
|---|---|---|---|
| `PHERF_RUN_DIR` | controller | required | The controller data directory housing run bundles,
`tests/`, `controller.lock`, and `controller.pid`. The controller creates this directory
automatically if it does not already exist. On Kubernetes deployments, this path corresponds to the
PersistentVolumeClaim mount point. |
| `PHERF_REST_PORT` | controller | 8780 | The TCP port for the REST listener. Setting `0` binds an
ephemeral free port, which is reported in the `REST API listening on port N` log entry. |
| `PHERF_AGENT_PORT` | controller | 8790 | The TCP port for the agent channel listener. Setting `0`
binds an ephemeral free port, which is reported in the `agent channel listening on port N` log
entry. |
| `PHERF_BIND_ADDRESS` | controller | all interfaces | The network bind address for both listeners,
specified as an IP literal or a host name that is resolved once at startup. |
| `PHERF_CONTROLLER` | agent | required | The base URL of the controller's agent channel listener,
such as `http://host:8790`. |
| `PHERF_ZOOKEEPER` | agent | required | The ZooKeeper quorum string of the target cluster, passed
through to Pherf via `-z`. In H3, the harness injects an external quorum through this variable
(RQ-252). |
| `PHERF_AGENT_ID` | agent | `HOSTNAME`, then the host name | The agent's operational identifier
matching `[A-Za-z0-9._-]{1,63}`. On Kubernetes deployments, this value is the pod name (RQ-318). |
| `PHERF_CLASSPATH` | script | `<bin>/../lib/*` | The JVM class path containing the module jar,
phoenix-pherf, the Phoenix client and its transitive dependencies, and an slf4j logging binding. |
| `PHERF_JAVA_OPTS` | script | empty | Additional JVM command-line options. The value is split on
whitespace without quote or glob processing, so individual options cannot contain spaces. Because
these options are appended after `-Djava.io.tmpdir=${TMPDIR:-/tmp}`, they can override the
temporary directory property. Since an agent passes these same options to its Pherf child JVMs,
memory sizing for D6 and H3 must account for both the agent process and one concurrent child JVM. |
| `TMPDIR` | script | `/tmp` | The directory passed as the JVM `java.io.tmpdir` property, which
determines where the agent creates its working directory and stores its pid file. |
| `JAVA_HOME` | script | `java` on `PATH` | The installation directory of the JDK or JRE used to
launch the process. |

Any invalid configuration value causes the process to terminate with exit status 2; termination is
immediate except when contending for a held run-directory lock, which aborts only after the bounded
wait described below. For the controller, fatal configuration errors include an unset
`PHERF_RUN_DIR`, a run directory that cannot be created, a port outside the range 0 to 65535, an
unresolvable bind address, or a run directory that continues to be locked by another controller
instance. If a starting controller discovers that `controller.lock` is held, it logs a single
informational message and retries lock acquisition every 500 ms for up to 150 s, accommodating
Kubernetes pod replacements where a new pod starts while the pod it replaces is still shutting
down. This timeout encompasses the default 120 s harness stop grace period (`PX_TIMEOUT_STOP`) plus
30 s for forced termination and kernel lock release. If the lock remains held for the full 150 s,
the process exits with status 2 and logs `another controller uses PHERF_RUN_DIR <dir>`. For an
agent, fatal errors include a missing
`PHERF_CONTROLLER`, a missing `PHERF_ZOOKEEPER`, or the absence of `pherf.properties` on the class
path (normally provided by the phoenix-pherf jar).

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
| `FAILED` | The run failed after at least one step received `launch`, or the run was still in
flight when the controller stopped. |
| `CANCELLED` | An operator explicitly cancelled the run or ended its enclosing test. This state
alone does not indicate whether database traffic was sent; the individual slot states, dispatch
timestamps, and retained telemetry frames record how far execution progressed. |

| Slot state | Meaning |
|---|---|
| `ASSIGNED` | The agent has the slot and prepares it. |
| `READY` | The prepare passed. The slot waits for the other slots of the step. |
| `LAUNCHED` | The step got `launch`. The run child warms up. |
| `ARMED` | The run child holds at its start gate. |
| `RUNNING` | The slot has its start time. |
| `DONE` | The controller holds every frame of the slot and its final report. |
| `FAILED` | The slot failed. Its error is `agent <id>: <error>`. |
| `CANCELLED` | Another slot caused the run to fail, or an operator cancelled the run, and this
slot consequently received a `cancel` or `release` command. |

One failing slot fails the run: a prepare error (for example a validation finding), a start
failure, an eviction, a `goodbye`, a new incarnation, a misconfigured recorder or a telemetry count
that does not match. The run reason is the first slot error.
The other agents of the step get `cancel` (while they prepare, wait or run) or `release` (when
done), and all go idle. Their slots that are not `DONE` become `CANCELLED`. A cancelled slot that
subsequently reports its own failure transitions to `FAILED` and records its error so that the live
run view surfaces every failure. Because a committed run bundle is immutable, its on-disk
`status.json` preserves the snapshot captured at commit time. Failed slots are never retried and
subsequent steps are skipped. All participating agents return to idle, except that any agent
observed by the controller to have failed a launched slot due to an agent-side fault is marked
`FAULTED` and excluded from future slot assignments until reset (see Cancel, faults and reset).

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
- A controller restart terminates any runs in flight: each transitions to `FAILED` with reason `the
  controller stopped during the run`, and its directory has no `manifest.json`. Connected agents
  receive `reregister`, cancel their active workloads, and discard any buffered frames, while the
  restarted controller instance acknowledges stale frames without retaining them. Consequently, no
  telemetry is double-counted and no interrupted run is ever reported as complete. Previously
  completed runs and their committed bundles remain intact (see Persistence and restart).
- The memory of an active run does not grow with its length: one histogram of about 172 KB for each
  key, the counters of each slot, and one buffered raw log for each slot. When the bundle is
  written, the controller drops the histograms and the final reports. Decode and merge run under
  the controller monitor, at about 100 us for each frame, so the controller holds about 1000 agents
  with 12 keys each.

## Run bundle

`submit` makes the directory `$PHERF_RUN_DIR/<runId>/` (a failure refuses the submit). Each held
frame is appended immediately to the raw interval log of its slot. When a run reaches any terminal
state (`COMPLETE`, `FAILED`, `ABORTED`, or `CANCELLED`), the controller writes the remaining bundle
artifacts atomically with the state transition, writing `status.json` followed by `manifest.json`
last. Consequently, a run reaches `COMPLETE` only after its entire bundle is on disk, and any run
directory lacking a manifest represents an interrupted run. The directory structure conforms to the
standard Pherf offline analysis run-bundle layout (ANALYSIS.md), allowing `-analyze` to consume a
bundle directly without conversion.

| Path | Content |
|---|---|
| `status.json` | The committed final run view, containing the same JSON payload returned by
`GET .../status` at commit time. This file is never modified after the manifest is written: if a
cancelled slot subsequently reports its own failure, that late error appears only in live
`GET .../status` responses and the controller log until the next controller restart. Upon restart,
the controller reconstructs the run view from this file. |
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

The controller and all agents in a fleet must be deployed from the same build. Message parsers
ignore unrecognized JSON fields so that additive changes remain backward-compatible, whereas any
incompatible change that an existing parser cannot read must increment the protocol version `v` and
advance the HTTP path prefix to `/v2/`. Any request carrying a different `v` value is rejected with
HTTP status 400 and a `rejected` response.

## REST API

The REST listener (`PHERF_REST_PORT`, defaulting to 8780) services external clients such as the
`pherf-distributed` harness driver. It shares a single request executor, agent registry, and run
execution engine with the agent channel listener (port 8790), ensuring that both interfaces observe
a consistent view of system state. Each listener strictly serves only its designated routes:
requesting a REST path on port 8790 returns the standard JDK HTTP server 404 response, whereas
requesting an agent channel path on port 8780 returns a JSON 404 error response.

### Resources

- An **agent** represents a registered agent process indexed by its operational identifier. The
  controller does not persist the agent registry to disk; following a controller restart, live
  agents automatically re-register within approximately 6 seconds.
- A **test** encapsulates a validated execution plan (see Plans). Each **run** under a test
  represents a distinct execution of that plan—such as an individual replicate—with its own unique
  run identifier and output bundle. A test is in either the `OPEN` or `ENDED` state and permits at
  most one `RUNNING` run at a time, preventing concurrent executions of the same plan from writing
  overlapping keys simultaneously.
- Test identifiers follow the format `t-<UTC time>-<n>`, run identifiers follow `<UTC time>-<n>`
  (where the timestamp uses `yyyyMMdd'T'HHmmssSSS'Z'`), and agent identifiers match
  `[A-Za-z0-9._-]{1,63}`. Sequence numbers are strictly monotonic and never repeat within a given
  run directory. Any URL path segment that fails to match its resource identifier pattern receives
  an HTTP 404 response.

### Endpoints

Request and response payloads use UTF-8 JSON with `Content-Type: application/json; charset=utf-8`
and a maximum body size of 16 MiB. URL query strings are ignored. Resource creation endpoints return
HTTP 201 with the assigned identifiers in the response body and omit the `Location` header.
Endpoints that take no input parameters accept either an empty body or `{}` and return HTTP 400 for
any other payload.

| Call | Success | Errors |
|---|---|---|
| `GET /v1/agents` | 200 with `{"agents": [AgentView...]}` sorted in ascending id order. | |
| `POST /v1/tests`, body: the plan | 201 with the created TestView. | 400 with the plan validation
failure message naming the offending JSON path; 413 if the body exceeds the size limit; 500 if
`plan.json` or `test.json` cannot be persisted. |
| `POST /v1/tests/{id}/runs` | 201 with the RunView of the newly created run in state `RUNNING`
with slots `ASSIGNED`. | 404 if the test does not exist; 409 if the test has already ended, already
has an active `RUNNING` run, or lacks sufficient idle agents
(`step 0 needs N agents but M are free`, appending `; faulted: <ids>` when any agents are faulted);
500 if the run directory cannot be created or if `test.json` cannot be updated to record the run
(in which case the run is transitioned to `CANCELLED` and omitted from the test's run list). |
| `GET /v1/tests/{id}/runs/{runId}/status` | 200 with the current RunView. | 404 if the run is
unknown or does not belong to the specified test. |
| `GET /v1/tests/{id}/runs/{runId}/report` | 200 returning the exact byte contents of
`drivers/pherf/results_HDR/LATENCY_run.json`, the immutable fleet report. | 409 while the run is
still `RUNNING`; 404 if the run terminated without a committed bundle. |
| `POST /v1/tests/{id}/runs/{runId}/cancel` | 200 with the RunView in state `CANCELLED`
(idempotent, returning 200 if already cancelled). | 404 if the run is not found; 409 if the run has
already terminated in a different state. |
| `POST /v1/agents/{agentId}/reset` | 200 with the updated AgentView (a no-op if the agent is not
currently faulted). | 404 if the agent is unknown; 409 if the agent currently holds an active slot
or is in the process of leaving. |
| `POST /v1/tests/{id}/end` | 200 with the TestView in state `ENDED` (idempotent, returning 200 if
already ended). | 404 if the test is unknown. |
| `GET /v1/tests/{id}/runs/{runId}/bundle` | 200 with `{"files": [{"path", "bytes"}...]}` listing
every regular file in the run directory in sorted order, excluding any `.tmp` files. | 409 while
the run is `RUNNING`; 404 if the run has no committed bundle. |
| `GET /v1/tests/{id}/runs/{runId}/bundle/{path}` | 200 returning the raw bytes of the requested
file, where `.json` is served as `application/json`, `.tsv` as `text/tab-separated-values`, `.hlog`
as `text/plain`, and any other extension as `application/octet-stream`. | Same run-state errors as
the bundle listing endpoint, plus 404 if the requested path is not among the listed bundle files. |

Both the fleet report and the run bundle become available only after `manifest.json` has been
written as the terminal commit point of the run. Rather than streaming a single archive, the bundle
API exposes a JSON file manifest paired with individual file download requests because the standard
JDK lacks a built-in tar archive writer and this design allows clients to retrieve artifacts using
only a basic HTTP client and JSON parser.

- **TestView**: Contains `id`, `state` (`OPEN` or `ENDED`), `createdMs`, `endedMs` (included only
  when `ENDED`), and `runs` (the list of run identifiers in submission order).
- **RunView**: Contains `runId`, `state`, `reason`, `step`, and
  `steps[{index, state, startAtMs, skewMs,
  slots[{identity, agent, state, error, share, heldSeq, finalSeq, samples, counters, offsetMs,
  delayMs, startAtMs, dispatchMs}]}]`. Any field without a value is omitted from the JSON object.
- **AgentView**: Contains `id`, `incarnation`, `state` (`IDLE`, `ASSIGNED`, `READY`, `LAUNCHED`,
  `ARMED`, `RUNNING`, `DONE`, `LEAVING`, or `FAULTED`), `phase`, `runId`, `step`, `registeredMs`,
  `lastSeenMs`, `offsetMs`, `delayMs`, and `fault`.

A typical client workflow first registers the execution plan via `POST /v1/tests`, launches each
replicate execution by calling `POST .../runs`, and then polls `GET
.../status` until the state is not `RUNNING`, `GET .../report` and the bundle files, then `POST
.../end` to close the test.

### Errors

Every non-2xx response returned by the REST listener carries a JSON payload formatted as
`{"error": "<code>", "message": "<text>"}`. The error codes form the programmatic contract, whereas
the message strings provide human-readable diagnostics.

| Status | Code |
|---|---|
| 400 | `bad_request` |
| 404 | `not_found` |
| 405 | `method_not_allowed`, accompanied by an `Allow` response header |
| 409 | `conflict` |
| 413 | `too_large` |
| 500 | `internal` |

Every state-mutating request logs a single INFO entry recording the HTTP method, request path,
response status code, and resource identifier. Read-only `GET` requests emit no access log entries
so that frequent status polling does not flood the controller log.

### REST version rule

Additive JSON fields may be introduced in any build, and clients must ignore unrecognized fields.
Any breaking change that an existing client cannot parse advances the REST endpoint prefix to
`/v2/`. This versioning policy operates independently of the agent channel protocol version `v`.

## Cancel, faults and reset

- **Cancel**: Cancelling an active `RUNNING` run transitions its state to `CANCELLED`, setting
  the termination reason to `cancelled`, or to `cancelled: the
  test ended` when `end` cancels it. The slots that are not `DONE` or `FAILED` become `CANCELLED`,
  and the controller commits the run bundle so that the fleet report of a cancelled run remains
  inspectable. In the committed bundle, the manifest state is `CANCELLED` and the
  `pherf.fleet.agents` verdict row is recorded as `FAIL`. Participating agents receive `cancel` or
  `release` commands and return to idle; any live Pherf child process is sent SIGTERM followed by
  SIGKILL after 5 seconds. Cancelling a run never marks an agent as faulted.
- **Faulted agent**: When a slot fails after receiving `launch` and the controller determines that
  the agent process itself violated the protocol or internal invariants (by sending a corrupt
  histogram frame, exhibiting lost or duplicated telemetry sequence counts, reporting `DONE` without
  a final report, or reporting idle while assigned to an active slot), the controller transitions
  that agent to `FAULTED` and records the diagnostic reason in `fault`. The agent receives `release`
  and its process returns to idle, but the controller excludes it from future slot assignments until
  it is explicitly reset. By contrast, an execution error reported by the agent itself (such as a
  non-zero Pherf child exit or a prolonged telemetry stream failure) fails the current run without
  faulting the agent: external database or network disruptions—such as those induced during
  fault-injection testing—commonly trigger such errors, and subsequent replicates require the full
  agent fleet to remain available. Similarly, any failure occurring prior to the launch (such as an
  offline validation finding or slice preparation error) reflects a plan issue and does not fault
  the agent. Finally, a heartbeat timeout eviction, a `goodbye` message, a new incarnation, or a
  misconfigured histogram recorder removes the agent entry entirely, allowing a healthy process to
  rejoin under a fresh registration record.
- **Reset**: Resetting a faulted agent clears its fault state, returning the agent to the available
  pool so that subsequent runs can be scheduled. A reset operation only updates controller state and
  does not terminate runaway processes on the agent host; an agent whose child workload fails to
  stop must have its OS process restarted. Because agent fault state is held exclusively in memory,
  a controller restart allows all agents to re-register cleanly without faults. An automated
  replicate driver can inspect the `faulted: <ids>` suffix in an HTTP 409 response from
  `POST .../runs` to identify and reset or replace faulted agents before retrying.

## Persistence and restart

All persistent controller state resides under `PHERF_RUN_DIR`:

| Path | Content |
|---|---|
| `controller.lock` | An empty lock file on which the controller acquires and holds an exclusive
file lock for its entire lifetime. Any concurrent controller targeting the same run directory
polls for up to 150 s to acquire this lock before terminating with exit status 2 and reporting
`another controller uses PHERF_RUN_DIR`. |
| `controller.pid` | The process identifier of the running controller, written by
`pherf-distributed.sh`. |
| `tests/<testId>/plan.json` | The raw test plan payload as submitted, written once upon creation. |
| `tests/<testId>/test.json` | The serialized TestView. It is persisted after `plan.json` and prior
to returning HTTP 201—serving as the creation commit point for the test—and is rewritten after each
run submission and when the test ends. |
| `<runId>/` | The run bundle directory (see Run bundle), finalized by writing `status.json`
followed by `manifest.json` last. |

Every JSON metadata file and the fleet verdict file are updated using the same crash-safe sequence:
the controller writes the content to `<file>.tmp`, forces the file data to disk, atomically renames
the temporary file over the destination path, and forces the parent directory entry to disk. An
unexpected crash therefore leaves either the complete previous version or the complete new version.
Similarly, each per-slot raw interval log and the merged latency log are flushed and synced to disk
when closed, and every newly created directory (`<runId>/`, `tests/<testId>/`, and the nested bundle
subdirectories) is synced into its parent directory. Because all of these durability barriers
complete before `manifest.json` is committed, the presence of a manifest after a process or host
crash guarantees that the entire run bundle is intact, and any test creation request that received
HTTP 201 is guaranteed to have its test directory on disk. Once the manifest is written, no file
within the run bundle is ever modified. The controller intentionally does not persist the active
agent table, agent fault states, clock offset samples, pending long-poll connections, or in-memory
histogram aggregation state for in-flight runs.

During startup, the controller restores the state of the directory:

- A run directory containing `manifest.json` is treated as a completed terminal run, and its
  RunView is loaded directly from `status.json`.
- A run directory without a manifest that contains a `status.json` recording `ABORTED`, `FAILED`, or
  `CANCELLED` retains that terminal state, indicating that the failure was recorded before the
  bundle commit could finish.
- Any other run directory represents a run that was in flight when the previous controller stopped.
  The controller transitions it to `FAILED` with the recorded reason `the controller
  stopped during the run` in `status.json`, and leaves the directory without a manifest.
- A test directory containing `test.json` is restored and its plan is re-parsed. A test directory
  lacking `test.json` is ignored because its creation call never completed. If a restored plan fails
  validation under the current build, the test is forced to `ENDED` so that its historical runs
  remain accessible for inspection.
- Leftover `.tmp` files are ignored. Any unreadable or corrupt metadata file causes the controller
  to skip the affected run or test, emit a WARN log entry naming the file, and continue starting up.
- Monotonic sequence counters for new test and run identifiers resume strictly above the highest
  sequence numbers discovered in the directory.

Runs that are in flight when the controller stops are always marked as failed rather than resumed.
Their partially merged fleet histograms existed only in controller memory, re-registering agents
abort their active workloads and discard unacknowledged telemetry frames, an interrupted open-loop
arrival schedule incurs a timing gap that cannot be repaired by resumption, and a synchronized start
epoch cannot be re-issued mid-step. Such runs are classified as `FAILED` rather than `ABORTED`
because the restarting controller cannot prove that no step had received a `launch` command prior to
the outage. Following restart, agents re-register through the standard control loop and any `OPEN`
test can immediately accept a new run. Graceful termination via SIGTERM follows this exact same
recovery path as an abrupt crash.

## Scripts

The launcher script `src/main/bin/pherf-distributed.sh ROLE VERB` manages a single process instance,
where `ROLE` is `controller` or `agent` and `VERB` is `start`, `stop`, `restart`, or `status`. The
script resolves `../conf` and `../lib` relative to its own installation directory, so a runtime
image requires only the `bin/`, `conf/`, and `lib/` directories. It places `conf/log4j2.properties`
(sourced from `src/main/conf`) at the head of the class path, configuring single-line standard
output logging that includes the operational agent identifier on every event.

| Verb | Effect | Exit status |
|---|---|---|
| `start` | Runs in the foreground by recording its pid in the pid file and replacing the shell
process with the JVM, ensuring that logs stream to standard output, signals are delivered directly
to the JVM, and the exit status reflects the JVM exit code. | The JVM exit status (see Processes);
1 if a live process for the same role is already running; 2 if no Java executable is found, or if
`lib/` contains no jars and `PHERF_CLASSPATH` is unset. |
| `stop` | Sends TERM and waits up to 15 seconds for termination before sending KILL, then removes
the pid file (including any stale pid file). | 0 if the process stopped cleanly or no live process
existed; 1 after KILL. |
| `restart` | Executes `stop` followed immediately by `start` in the caller's shell, intended
solely for host-managed processes. | 1 if the stop operation fails; otherwise returns the exit
status of `start`. |
| `status` | Prints either `running as pid N` or `not running` to standard output. | 0 when
running; 3 when not running. |

Any command-line usage error exits with status 2. Because the controller pid file is stored at
`$PHERF_RUN_DIR/controller.pid`, every controller verb requires `PHERF_RUN_DIR` to be set. The agent
pid file is stored at `${TMPDIR:-/tmp}/pherf-agent-<id>.pid`; the script resolves the operational
agent identifier using the same fallback rules as the agent JVM and exports it to the environment.
The launcher passes `-Djava.io.tmpdir=${TMPDIR:-/tmp}` ahead of `PHERF_JAVA_OPTS`, ensuring that the
agent's temporary working directory (`pherf-agent-<id>-*`, holding its workload slices, property
overlays, and Pherf child output) is also created under `TMPDIR`. In addition, the JVM command line
includes `-Dpherf.role.id=<id>` for an agent or `-Dpherf.role.id=<run dir>` for the controller. A
recorded pid is considered live only if it responds to `kill -0`, is not the current launcher script
process itself, and has an OS command line containing both the expected main class name and the role
identifier. Consequently, an OS pid recycled for a different agent instance or unrelated process on
the same host is never misidentified as live. Because all files are confined to the run directory
and `TMPDIR`, the script operates without root privileges.

Inside a container image, the JVM runs as PID 1 via the `exec` in `start`, streaming its output
directly to the container logging driver. In containerized deployments, restarting a process must be
performed by restarting the container or pod (`docker restart` or a Kubernetes pod restart) rather
than invoking the `restart` verb: a `stop` of PID 1 terminates the container before `start` can
run, and launching a replacement process via `docker exec` would attach its output to that
interactive exec session instead of the container log. The `restart` verb is intended exclusively
for standalone host deployments.

## Security

The D5 module implements neither authentication nor TLS, as the design specifies plain HTTP over the
standard JDK HTTP server. Any client able to reach port 8780 can initiate database load and read run
bundles, and any process able to reach port 8790 can join the agent fleet and receive workload
plans. Network exposure must therefore be restricted through `PHERF_BIND_ADDRESS` and deployment
topology: Docker Compose binds published ports exclusively to `127.0.0.1`, while Kubernetes
deployments expose the controller through a ClusterIP Service accessed via loopback port-forwarding.
For input hardening, the REST listener caps request bodies at 16 MiB, validates every resource
identifier against its strict regular expression pattern, and serves bundle downloads exclusively
from regular files (rejecting symbolic links) confined within the target run directory. Both HTTP
listeners close any connection that fails to transmit its complete request (request line, headers,
and body) within 60 seconds, enforced via the `sun.net.httpserver.maxReqTime` system property
configured by `ControllerMain`. Because this timer applies only until the request body has been
fully read, long-polled agent control requests are unaffected during their server-side hold window.
The HTTP server places no cap on concurrent connections—spawning a handler thread per request—so an
untrusted client with access to port 8780 could exhaust controller heap memory by submitting many
large request bodies concurrently. Network isolation via the bind address and deployment environment
serves as the primary access control boundary.
