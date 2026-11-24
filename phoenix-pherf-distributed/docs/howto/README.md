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

# Distributed Pherf HOWTOs

Distributed Pherf drives a single target cluster concurrently from multiple Pherf agents. A central
controller partitions a unified workload plan across the agents, coordinates a synchronized start,
merges their interval histograms, and produces a single fleet report, run bundle, and fleet verdict.
These HOWTO guides teach the system through a structured performance engineering methodology: each
hands-on guide defines the experimental question, the hypothesis, the controls and sources of noise,
which metrics to capture and why, how to interpret the resulting data, what conditions invalidate
the run, and how to reproduce the experiment.

**What the D series adds.** The single-node Pherf HOWTOs ([P series][pindex]) execute within a
single Pherf process, which is inherently bounded by the capacity of its own dispatcher, worker
threads, and host machine. The D series introduces the distributed coordination primitives required
to operate a fleet of load generators: workload partitioning (the work split), slot tokens that keep
each agent's dataset disjoint, a synchronized start protocol with an explicit skew bound, interval
histogram merging, a management REST API, and fault-handling and restart rules. The companion
harness HOWTOs [H14][h14] and [H15][h15] operate the same fleet as an on-demand tier within a
harness cluster using the `pherf-distributed` driver, predefined fleet suites, and replicate
analysis.

## The HOWTOs

The resource costs listed below are approximate; consult the "Before you start" section of each
individual HOWTO for a complete breakdown. The reported memory footprint represents the sum of the
container memory limits. The "Live" column indicates the level of verification: "yes" means the
procedure was executed end-to-end against a live environment, whereas "no" means the steps were
verified against the source code only. All four guides were executed live on a 24 GiB Docker Desktop
engine (24001 MiB): D01 using the standalone `compose.yaml` deployment, and D02 through D04 against
a harness validation cluster with the distributed Pherf tier enabled. Option A of D02, which targets
an external cluster outside the harness, was not executed live.

| # | Purpose | Cost | Live |
|---|---|---|---|
| [D01][d01] | Deploy a standalone controller and agents; use the REST API | 5 min, 5.5 GiB | yes |
| [D02][d02] | Run a fleet plan: work split, tokens, start, fleet report | 10 min, 12.5 GiB | yes |
| [D03][d03] | Cancel active runs, handle agent faults, restart controller | 5 min, 12.5 GiB | yes |
| [D04][d04] | Scale out the load generator and interpret fleet latency | 15 min, 12.5 GiB | yes |

The companion harness HOWTOs for fleet execution cover the following topics:

- [H14: run Pherf on a fleet through the harness][h14]: Covers the `px-pherf` CLI, the
  `pherf-distributed` driver, container sizing arithmetic, and the built-in suites `fleet.suite`,
  `fleet-rww.suite`, and `fleet-rules.suite`.
- [H15: compare and stress a fleet][h15]: Covers multi-replicate execution, A/B comparison using
  `fleet-ab.suite` with `-analyze`, and fault injection under fleet load with the recovery gate.

Recommended reading order from foundational to advanced topics: D01, D02, H14, D03, D04, H15.

## Reading paths

**(a) A prospective user.** Start with [D01][d01] to deploy a standalone fleet and inspect its
endpoints, proceed to [D02][d02] to execute your first distributed workload, follow [H14][h14] to
run the same workflow through the test harness, and finish with [D04][d04] to understand how to
interpret fleet-wide latency metrics.

**(b) A developer who validates a change.** Run [H14][h14] (specifically `fleet-rww.suite`, which
verifies read-what-you-wrote data consistency across agents), consult [D02][d02] to inspect the
workload split and run bundle artifacts directly, and work through [D03][d03] to verify the fault
and recovery paths whenever your changes modify the controller or the agent.

**(c) A committer who reviews a fleet performance claim.**

- [D04][d04]: Explains why percentiles must be computed from merged histograms rather than averaged
  across agents, how to evaluate the start skew bound, and how to detect client-side bottlenecks.
- [H15][h15]: Covers multi-replicate runs, comparative A/B analysis with `-analyze`, and the formal
  measurement class.
- Follow up with [P06][p06] (for Pherf health verdicts and statistical analysis) and harness
  [H01][h01] (for the measurement protocol and review checklist).
- Require the following evidence for any performance claim:
  - execution in the measurement class on a quiet host, with at least 3 replicates per side;
  - a fleet verdict in which both `pherf.fleet.agents` and `pherf.fleet.telemetry` report PASS;
  - a synchronization skew bound that is small relative to the measurement window;
  - the merged fleet report used as the sole source for every cited percentile;
  - latency claims based on the response-time key, using the service-time key only to localize where
    execution time is spent;
  - configured `warmupExecutions`, or an interval log confirming that no cold-start backlog bled
    into the measured window;
  - an interleaved A/A baseline demonstrating no spurious shift on the claimed operation key;
  - `dropped 0` and 100% target rate attainment on the worst-performing slot;
  - proof that the agents themselves were not the bottleneck, provided either by measuring agent CPU
    cost per operation and remaining headroom, or by comparing a 1-agent run against a 2-agent run
    at the claimed aggregate rate with unchanged service time (see D04 experiment 1).

**(d) A contributor.**

- Review [D01][d01] for the environment variable contract, launch scripts, and REST error handling,
  together with [D03][d03] for fault-handling rules and controller state persistence.
- Read the module README sections covering the [Agent channel][channel], [Run bundle][bundle], and
  [Version rule][version].
- Consult the harness [driver contract, section "pherf-distributed"][driver].

## Conventions

- Execute every command from the root directory of the repository; the individual HOWTO guides do
  not repeat this requirement. Each D-series HOWTO begins its "Before you start" section with the
  following environment setup commands:

  ```sh
  export PATH="$PWD/dev/harness/bin:$PATH"
  export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
  HOWTO="$PX_OUT/howto/dNN"; mkdir -p "$HOWTO"
  PF=http://127.0.0.1:8780/v1
  ```

- `PF` defines the base URL of the controller REST API. Both the standalone Docker Compose file and
  the harness tier bind port 8780 exclusively to `127.0.0.1` on the host, while the internal agent
  channel on port 8790 is never published to the host.
- `jq` version 1.6 or later is used to parse responses and construct JSON payloads, using
  `jq --rawfile` to embed external document files into a workload plan. Note that [D02][d02]
  outputs 64-bit slot seeds and therefore requires `jq` 1.7 or later, because earlier versions of
  `jq` round 64-bit integers.
- Resolve the container image tag from the harness configuration:

  ```sh
  px-cluster config PX_PHERF_IMAGE_TAG | cut -f 2
  ```

- Output excerpts display only the relevant lines. An ellipsis (`...`) denotes either a dynamic
  value that varies across runs or omitted lines, and any excerpt captured from a separate run is
  explicitly noted.

## Command style

The D-series HOWTOs adhere to the command conventions established by the [harness HOWTOs][hindex]:

- **No exit echoes.** Whenever a px command fails, its log concludes with a single `(exit N, name)`
  diagnostic line; any step designed to demonstrate a specific exit status includes that line in its
  output transcript. For the direct docker and curl invocations in D01, the accompanying step prose
  explicitly states the resulting status code.
- **One preamble.** The three environment setup lines shown above appear at column 0 within the
  "Before you start" block and initialize the working directory `howto/dNN`. Any additional
  variables reused throughout a guide, such as `PF`, immediately follow those lines.
- **Explicit lifecycle.** Each HOWTO that relies on the test harness provisions its cluster once and
  concludes with `px-cluster down`, relying on that command's final log message as confirmation of
  teardown. Guide D02 launches the distributed tier via `px-pherf start`, which automatically brings
  up the cluster with the tier enabled if no active cluster is recorded. Guides D03 and D04 reuse
  that running cluster, and whichever guide you execute last is responsible for tearing it down. The
  standalone Compose deployment in D01 operates independently of the harness and performs its own
  docker cleanup.
- **No repeated bring-up values.** The cluster class and any `-e` environment overrides are
  specified only once during initial cluster bring-up, as subsequent commands inherit them from the
  persisted cluster record. The override `-e PX_PHERF=1` is passed exclusively to
  `px-cluster build`.
- **Long flags only.** Command-line invocations use long option names exclusively, which are the
  only supported forms apart from the short flags `-h -v -n -e`.
- **Front doors only.** Command lines invoke only top-level entry points and never reference
  internal `dev/harness/scripts/` paths directly.
- **Captured handles, not discovery.** Subsequent steps locate artifacts using the explicit handles
  emitted by the tools, such as the bundle path reported in the bundle column of a px-suite result
  row ([H14][h14], [H15][h15]).
- **One JSON reader.** All JSON parsing and inspection is performed using jq.
- **Transcripts.** Shell commands and their outputs are presented in separate `sh` and `text` fenced
  blocks so that every command block can be copied and pasted directly. Log line prefixes are
  abbreviated as `... INFO  px-x:`.

## Coverage

The tables below map every capability delivered across development rounds D1 through D7 and H3,
along with core load-generator performance engineering tasks, to the HOWTO guides that cover them.
An entry marked "(text)" indicates that the guide explains the feature conceptually without
executing it in a hands-on step, referencing the underlying verification evidence in a live round
record or a unit test instead. Guide P02 belongs to the [Pherf HOWTOs][pindex], while H01 through
H15 belong to the [harness HOWTOs][hindex].

### D1: protocol and identities

| Feature | HOWTOs |
|---|---|
| Controller and agent processes, exit codes 0/1/2, SIGTERM and `goodbye` | D01 |
| Operational identity: id, incarnation, a new record on registration | D01, D03 |
| Data-plane identity: slot seed, offset and prefix from the plan seed | D02, H14 |
| `${pherf.slot.seed}`: POISSON seed per slot, shared against per-slot seed | D02, D04 |
| `${pherf.slot.offset}` and `${pherf.slot.prefix}` | D02, H14 |
| Agent channel 8790, long poll, outbound-only agents, agents resident across runs | D01 |
| Version rule | D01 (text) |

### D2: plans and split

| Feature | HOWTOs |
|---|---|
| Strict `RunSpec` parse, errors with JSON paths | D01 |
| Submit check: agent-owned options in any spelling, refused modes, agent properties | D01 |
| Submit check: offset needs rows, zero share, int bound, 16 MiB; the `-zhost` limit | D01 |
| Largest-remainder split of rate, threads and rows; `durationMs` not split | D02, D04 |
| Contiguous keyspace offsets | D02, H14 |
| Properties precedence: shipped, step, run-level, agent keys | D02 |
| Per-agent fleet validation and the `ABORTED` run | D01, H14 |
| `-validate` both ways with the launch overrides | P02 step 11, D01 |

### D3: start and telemetry

| Feature | HOWTOs |
|---|---|
| Clock offset and delay (the clock filter) | D02, D04 |
| Two-phase start: READY, LAUNCHED, ARMED; epoch now + 2 s + max delay; `startAtMs` | D02 |
| Skew bound and the 50 ms rule | D04, H14 |
| Telemetry sequences, resume and held totals | D02, D03 |
| Keepalives and the reaper eviction | D03 |

### D4: merge and bundle

| Feature | HOWTOs |
|---|---|
| One merged histogram per key | D04 |
| Fleet report: the v1 report plus the `fleet` member, `startLagMs` | D02, D04 |
| Run bundle: manifest commit point, `status.json`, merged hlog, `slots/*.hlog` | D02 |
| The 8-row fleet verdict | D02, H14 |
| The `pherf.fleet.telemetry` and `pherf.fleet.agents` rows | D02, D03 |
| `-analyze` over the controller bundle | H15 |

### D5: REST and operations

| Feature | HOWTOs |
|---|---|
| REST: agents, tests, end, reset, the error contract and codes | D01 |
| REST: runs, status, report, the bundle listing and files | D02 |
| REST: cancel, reset with a slot | D03 |
| Persistence: tests restored, numbers continue, `controller.lock` | D01 |
| Persistence: an in-flight run `FAILED` at restart, byte-equal reports | D03 |
| `FAULTED` rules, reset, 409 `faulted:` | D03 (text; reset contract live) |
| `pherf-distributed.sh` verbs, pid rules and exit codes | D01 |
| `PHERF_*` environment contract | D01 |
| Security and the bind address | D01 |

### D6: image and Compose

| Feature | HOWTOs |
|---|---|
| Image build: `px-cluster -e PX_PHERF=1 build` | D01, H14 |
| `compose.yaml`, `--scale agent=N`, health checks, tini, non-root user | D01 |
| REST on `127.0.0.1:8780` only; sizing 1024 + 3 x 1536 = 5632 MiB | D01 |

### D7: end to end

| Feature | HOWTOs |
|---|---|
| `fleet-rww.suite` and `fleet-rules.suite` | H14 |
| `fleet-ab.suite` with `-analyze A B` and `B A` | H15 |
| Run bound, decision (e): LATE drain, DROP and `maxLagMs`, client timeouts | D03 (text), H15 |
| The verb bound, the cancel of `pd_term` and the copy of the `CANCELLED` bundle | H15 |
| Recovery gate on an absent instance after the full count | H09 step 12, H15 step 24 (text) |

### H3: the harness tier

| Feature | HOWTOs |
|---|---|
| `PX_PHERF`, `PX_PHERF_TARGET` harness or external, `PX_PHERF_ZOOKEEPER` rules | H14 |
| The on-demand group `pherf`, `px-pherf scale`, `px-cluster scale pherf-agent` | H14, H11 |
| `px-pherf start`, `stop`, `status`, `scale`, `run` and their exit codes | H14, D02 |
| 24 GiB sizing arithmetic: 19905 budget, 24832 refused, 17920 fits, 25600 for 2 RS | H14, H15 |
| `pherf-distributed` driver: options, plans, rows, anchors, bundle layout | H14 |
| `fleet.suite`: r1, then r1 to r3 as replicates | H14, H15 |
| P13 `-analyze` over `drivers/pherf-distributed/controller` (the root exits 1) | H15, H03 |
| Tier log and collector exemptions | H14, H10 |
| Recovery gate on an exited instance, also in the barrier with 2 RegionServers | H09, H15 |
| Schedule anchors per slot | H14 |

### Load-generator performance engineering tasks

| Task | HOWTOs |
|---|---|
| Client bottleneck against cluster bottleneck | D04 (method, no bound reached), H06, H11 |
| Coordinated omission across agents: response key, worst schedule, arrival seeds | D04, P03 |
| Merged histograms against averaged percentiles | D04, P06 |
| Per-agent skew: `startLagMs`, the skew bound, the clock error | D04, H14 |
| Distributed correctness: disjoint keyspace, read-what-you-wrote across agents | H14, D02, P04 |
| Fleet replicates and A/B regression | H15, H12 |
| Resilience under fleet load | H15, D03 |
| Fleet capacity sizing | H14 |
| A cold start inside the measured window: the interval log, `warmupExecutions` | D04, H04, H05 |

## Out of scope

- **Kubernetes.** The D-series HOWTO guides execute on Docker Compose. You can run the same
  distributed tier on a local px-k3d cluster using `px-pherf --backend k8s`; consult the
  [harness README][harness] for details.
- **Authentication and TLS.** The distributed module implements neither authentication nor TLS
  encryption; see [Security][security] in the module README and [D01][d01].

[d01]: 01-standalone-fleet-and-rest-api.md
[d02]: 02-first-fleet-run.md
[d03]: 03-faults-cancel-and-restart.md
[d04]: 04-load-generator-scale-out.md
[h01]: ../../../dev/harness/docs/howto/01-measurement-protocol.md
[h14]: ../../../dev/harness/docs/howto/14-fleet-through-the-harness.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
[hindex]: ../../../dev/harness/docs/howto/README.md
[harness]: ../../../dev/harness/README.md
[p06]: ../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md
[pindex]: ../../../phoenix-pherf/docs/howto/README.md
[channel]: ../../README.md#agent-channel
[bundle]: ../../README.md#run-bundle
[version]: ../../README.md#version-rule
[security]: ../../README.md#security
[driver]: ../../../dev/harness/docs/driver-contract.md#pherf-distributed
