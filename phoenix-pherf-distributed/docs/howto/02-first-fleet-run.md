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

# How to run a plan on a fleet: work split, slot tokens, start sync and the fleet report (D02)

## The question

Can N agents collectively drive a single unified workload, where each agent operates on a disjoint
and reproducible partition of the dataset, while producing a single aggregated fleet result that is
structurally identical to the output of a standalone Pherf process? This HOWTO populates a
co-generated table across 2 agents configured with relative weights of 1 and 2, queries the dataset
back using an open-loop workload across the same 2 agents, and traces the execution lifecycle
through the REST API: work partitioning, slot token substitution, two-phase start synchronization
and start skew, telemetry streaming, the aggregated fleet report, the run bundle, and the fleet
verdict. Executing a second replicate demonstrates that all data-plane identities are derived
deterministically from the test plan.

## Before you start

**Audience.** Prospective users preparing to execute their first distributed fleet run, as well as
developers validating modifications to this module or to Pherf across multiple concurrent clients.

**Prerequisites.**

- [D01][d01]: You have built the pherf container image from your working tree and are familiar with
  the controller REST API.
- You have built the harness container image from your working tree
  (`px-cluster -e PX_PHERF=1 build` builds both images; see [H02][h02]).
- Your Docker engine is configured with at least 16896 MiB of memory, which covers the 12800 MiB
  allocated to the validation cluster and the distributed tier plus the 4096 MiB headroom that the
  preflight check reserves as free memory. A 16 GiB Docker engine falls 512 MiB short of this
  requirement and can only run the tier with a single agent.
- No other harness cluster is running on the host, and both `curl` and `jq` 1.7 or later are
  installed. Earlier versions of `jq` round the 64-bit slot seeds emitted in steps 13 and 14.

| Cost | Value |
|---|---|
| Wall time | About 10 minutes total: roughly 1 minute for bring-up and under 1 minute per run. |
| Docker memory | 12800 MiB of container limits: validation class, 1 RegionServer, and 2 agents. |
| Disk | A few MiB for the controller run volume and the downloaded bundle copies in `$HOWTO`. |
| Files written | `$HOWTO/d02_*.xml`, `d02_schema.sql`, `plan-*.json`, `bundle/`, and `*.json`. |

Initialize the session by exporting the required environment variables and preparing the working
directory:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/d02"; mkdir -p "$HOWTO"
PF=http://127.0.0.1:8780/v1
```

## Method

**Hypothesis.** When partitioning 6000 rows across weights [1, 2], slot 0 loads the row range
[0, 2000) while slot 1 loads the row range [2000, 6000). When partitioning an aggregate rate of 240
operations per second across 3 threads, slot 0 receives 80 per second on 1 thread while slot 1
receives 160 per second on 2 threads. Every read query locates its target row. Both slots release
their start gates within a synchronization skew bound well below 50 ms. The merged histogram count
for each operation key equals the exact sum of the individual slot counts. Executing a second run
of the same test produces identical slot seeds and row offsets.

**Controls.** The experiment holds constant a single plan seed (8070), a uniform agent count and
weight vector across both the load and read phases, a fixed co-generation seed, a validation class
cluster with 1 RegionServer, and identical scenario, profile, and schema documents across every run.

**Noise.** Because all agents and the target cluster share a single host, all latency and timing
measurements are treated as ADVISORY: both the validation run class and
`pherf.verdict.noisyHost=true` downgrade any duration FAIL into an ADVISORY outcome. Conversely,
operation counts, workload shares, slot identities, and lifecycle states are authoritative.
Although JVM startup latency for each Pherf child process varies by several seconds, the two-phase
start protocol excludes startup variance from the measured execution window.

**What to measure and why.** Inspecting the workload shares and row offsets verifies the work split.
Comparing the scheduled counts, completed counts, and merged histogram totals confirms that no
operation was dropped or counted twice. Evaluating the start skew bound verifies that all slots
applied their workload concurrently. Finally, the fleet verdict rows grade the overall run.

**What invalidates the result.** Embedding a slot token inside a primary key column, configuring a
`writeParams` block that writes beyond a slot's assigned row share, or altering the agent count or
weight vector between the load and read steps when the scenario specifies a co-generation
`clusterLimit` will invalidate the result. Each misconfiguration causes slots to query rows that
they never wrote.

## Concepts

**Target.** The fleet agents require network connectivity to the target cluster. Two deployment
targets are supported:

- **Option A, your own cluster.** Deploy the standalone `compose.yaml` from [D01][d01] with
  `PHERF_ZOOKEEPER` pointed at your cluster's ZooKeeper quorum. Every agent must be able to resolve
  and reach the ZooKeeper hosts, the HBase master, and every RegionServer using the exact host
  names published by the cluster, because the HBase client establishes direct connections to each
  RegionServer hosting a target region. This HOWTO does not include a live walkthrough of option A.
  To run against your own cluster, export the same `PF` endpoint, define the shell helpers in step
  3, and continue from step 4. Drop the table `PX_HOWTO.D02` manually when finished.
- **Option B, the harness (this HOWTO).** A validation cluster rendered with `PX_PHERF=1` includes
  the distributed tier as the on-demand service group `pherf`. Within this topology, agents connect
  to `zookeeper-0` over the internal harness network, and the controller publishes port 8780 on
  `127.0.0.1` of the host (`PX_PORT_PHERF_CONTROLLER_REST`), allowing the host to query the REST
  API directly with `curl`.

**The work split.** A single deterministic rule partitions each aggregate step quantity according
to the weight vector: the largest remainder method computed in exact integer arithmetic. The slot
shares sum to the aggregate total, each share equals either the floor or the ceiling of
`total * w / W`, and any remainder tie is awarded to the lower-indexed slot. The `rate`, `threads`,
and `rows` parameters are partitioned across slots, whereas `durationMs` is not split. Each agent
translates its assigned share into command-line launch options for its Pherf child process:

| Step field | Slot i gets | Pherf option |
|---|---|---|
| `rate` R | Share r_i, summing to R | `-targetRate r_i` |
| `threads` T | Share t_i, summing to T | `-threadCount t_i` |
| `rows` N | Share n_i, summing to N | `-rowCountOverride n_i` |
| keyspace | Row interval [o_i, o_i + n_i) | `${pherf.slot.offset}` = o_i |
| `durationMs` D | Full duration D | `-runDurationMs D` |

**The slot tokens.** Each agent substitutes three tokens across every document and property value
in its assignment. Token values are bound to the logical slot rather than the physical agent, and
the controller derives them deterministically from the plan seed and the slot index:

| Token | Value |
|---|---|
| `${pherf.slot.seed}` | `IndexHash.mix64(runSeed + (slot + 1) * IndexHash.GAMMA)` |
| `${pherf.slot.offset}` | Offset o_i, equal to the sum of the row shares of all lower slots. |
| `${pherf.slot.prefix}` | Zero-padded slot prefix ranging from `p000` to `p999`. |

**Properties.** Each agent merges Pherf configuration properties in ascending order of precedence
(lowest precedence first): the baseline `pherf.properties` on its classpath, the step-level
`properties`, the run-level `properties`, and finally the internal keys enforced by the agent
(`pherf.default.results.dir` and `pherf.default.hdr.intervalMs`). Only the run-level
`pherf.verdict.*` properties configure the fleet verdict evaluation.

## Steps

### Bring up the cluster with the tier

1. Provision a validation cluster with the distributed Pherf tier enabled, and wait for every agent
   to register in the `IDLE` state.

   ```sh
   px-pherf --class validation start
   ```

   ```text
   ... INFO  compose.gen.sh: rendered 11 services for cluster default, class validation: 12800
   MiB of container limits, 4096 MiB of them on demand, into .../deploy/default
   ... INFO  px-cluster: rendered the validation deployment of cluster default into
   .../deploy/default
   ...
   ... INFO  px-cluster: provisioning cluster default in scope px-default from .../deploy/default
   ...
   ... INFO  px-pherf: the tier runs: one controller and 2 IDLE agents
   pherf-agent-0	IDLE	148ca4e9-0af4-4613-b032-e1109ca85e53
   pherf-agent-1	IDLE	86d54f82-e288-4939-8def-c3dbda197a9e
   ```

   Because no active cluster is recorded yet, `px-pherf start` provisions the cluster with the
   distributed tier enabled (`PX_PHERF=1`) and leaves it running until `px-cluster down` tears it
   down at the conclusion of this HOWTO. Provisioning the cluster took roughly 50 s here. Every
   log message above is emitted as a single line of output and wrapped here for readability.

   Because the `pherf` service group is configured as on-demand, its containers are rendered and
   created during cluster provisioning without starting with the base cluster; `px-pherf start`
   subsequently starts the service group and waits until every agent reports `IDLE`.

2. Query the status of the distributed tier, which outputs the state row for the `pherf` service
   group followed by one status row per registered agent.

   ```sh
   px-pherf status
   ```

   ```text
   pherf	3	3	up
   pherf-agent-0	IDLE	148ca4e9-0af4-4613-b032-e1109ca85e53
   pherf-agent-1	IDLE	86d54f82-e288-4939-8def-c3dbda197a9e
   ```

   Within the harness environment, each agent's identifier matches its container service name,
   `pherf-agent-<i>`, and each reported incarnation matches the value emitted during step 1.

3. Verify that the controller REST API is reachable from the host and define two shell helpers.

   ```sh
   curl -fsS "$PF/agents" | jq -r '.agents[] | "\(.id) \(.state)"'
   create() { curl -fsS -X POST -H 'Content-Type: application/json; charset=utf-8' \
     --data-binary "@$1" "$PF/tests" | jq -r .id; }
   pf_wait() {
     local last="" now
     while :; do
       now="$(curl -fsS "$PF/tests/$1/runs/$2/status" |
         jq -r '[.state] + [.steps[0].slots[].state] | join(" ")')" || return 1
       [ "$now" = "$last" ] || { echo "$now"; last=$now; }
       case "$now" in RUNNING*) sleep 0.5 ;; *) return 0 ;; esac
     done
   }
   ```

   ```text
   pherf-agent-0 IDLE
   pherf-agent-1 IDLE
   ```

   The `create PLAN` helper posts a JSON plan file and prints the resulting test identifier. The
   `pf_wait TEST RUN` helper polls and prints the overall run state alongside the slot states
   whenever a state transition occurs, returning once the run is no longer `RUNNING`.

### Author the documents and the plans

4. Author the Pherf scenario file. Because the co-generation seed is fixed and the primary key
   column `K1` contains no slot token, each generated key depends solely on its global row index.
   The `offset` attribute binds to the slot offset token, and the open-loop query set references
   its workload profile document.

   ```sh
   cat >"$HOWTO/d02_scenario.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_d02">
     <datamapping>
       <column>
         <type>BIGINT</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <minValue>0</minValue>
         <maxValue>1000000000000</maxValue>
         <name>K1</name>
       </column>
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <length>12</length>
         <prefix>n</prefix>
         <name>NAME</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.D02" rowCount="1000" name="howto_d02">
         <coGeneration seed="8070" offset="${pherf.slot.offset}" blockSize="50"/>
         <querySet concurrency="1" profile="d02_profile.xml">
           <query id="lookup" expectedAggregateRowCount="1"
                  statement="SELECT COUNT(*) FROM PX_HOWTO.D02 WHERE K1 = [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   Each lookup query counts the rows matching a single committed key and asserts an expected count
   of 1, ensuring that any missing row fails the operation and records no latency sample. Quoting
   the `'EOF'` heredoc delimiter prevents the shell from expanding the slot tokens.

5. Author the workload profile and the SQL schema. The profile defines the shape of the arrival
   rate curve, which the execution plan scales to match each slot's assigned target rate and run
   duration. Using `POISSON` arrivals seeded with `${pherf.slot.seed}` provides each slot with an
   independent, deterministic arrival stream.

   ```sh
   cat >"$HOWTO/d02_profile.xml" <<'EOF'
   <workloadProfile>
     <openLoop arrival="POISSON" seed="${pherf.slot.seed}" lagPolicy="LATE">
       <stage durationMs="10000" rate="20"/>
     </openLoop>
   </workloadProfile>
   EOF
   cat >"$HOWTO/d02_schema.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.D02 (K1 BIGINT NOT NULL PRIMARY KEY, NAME VARCHAR)
   EOF
   ```

6. Construct the data-load test plan. Because supporting documents are embedded in the plan under
   their respective filenames, `jq
   --rawfile` reads each file as one string. The load gets its own test, with `-l` and the schema
   file: each agent writes the embedded documents into the `slice/` subdirectory of its working
   directory, and Pherf reads the SQL schema from that relative path.

   ```sh
   jq -n --rawfile sc "$HOWTO/d02_scenario.xml" --rawfile pr "$HOWTO/d02_profile.xml" \
     --rawfile sq "$HOWTO/d02_schema.sql" '
     {seed: 8070, runClass: "validation", properties: {"pherf.verdict.noisyHost": "true"},
      steps: [{agents: 2, weights: [1, 2], scenario: "d02_scenario.xml",
               documents: {"d02_scenario.xml": $sc, "d02_profile.xml": $pr,
                           "d02_schema.sql": $sq},
               args: ["-schemaFile", "slice/d02_schema.sql", "-l"], rows: 6000}]}' \
     >"$HOWTO/plan-load.json"
   ```

7. Derive the query execution plan from the load plan, retaining the exact same seed, agent count,
   weights, row count, and embedded documents while replacing the arguments with
   `-disableSchemaApply -q` and specifying the aggregate rate, duration, and thread count.

   ```sh
   jq '.steps[0].args = ["-disableSchemaApply", "-q"]
     | .steps[0] += {rate: 240, durationMs: 30000, threads: 3}' \
     "$HOWTO/plan-load.json" >"$HOWTO/plan-run.json"
   ```

   Keep `-l` out of the measured query step. Because a `-l` data load has no start synchronization
   gate, its slots begin writing as soon as their child JVMs initialize; isolating the `-q` step in
   its own test ensures that all participating agents start their measured workload together.

### Load the table

8. Submit the load plan, initiate a single run, and track its progress.

   ```sh
   T_LOAD="$(create "$HOWTO/plan-load.json")"
   R_LOAD="$(curl -fsS -X POST -d '' "$PF/tests/$T_LOAD/runs" | jq -r .runId)"
   pf_wait "$T_LOAD" "$R_LOAD"
   ```

   ```text
   RUNNING ASSIGNED ASSIGNED
   RUNNING LAUNCHED LAUNCHED
   COMPLETE DONE DONE
   ```

   The data load completed in roughly 20 s. Any state transition that persists for less than the
   0.5 s polling interval can be absent from the trace—here `READY`, as well as the brief interval
   when one slot had reached `DONE` before the other. Each agent first executed the Pherf preflight
   validator against its assigned share (`ASSIGNED`, then `READY`). Once every slot reported
   `READY`, the controller issued `launch` to each agent. Because a `-l` load has no start gate,
   its slots transition directly from `LAUNCHED` to `DONE` without entering `ARMED`.

9. Inspect the per-slot load counters and then terminate the load test.

   ```sh
   curl -fsS "$PF/tests/$T_LOAD/runs/$R_LOAD/status" | jq -c '.steps[0] | {skewMs},
     (.slots[] | {slot: .identity.slot, offset: .identity.offset, rows: .share.rows, counters})'
   curl -fsS -X POST -d '' "$PF/tests/$T_LOAD/end" | jq -c '{id, state}'
   ```

   ```text
   {"skewMs":null}
   {"slot":0,"offset":0,"rows":2000,"counters":{"load:howto_d02:PX_HOWTO.D02|steady":40}}
   {"slot":1,"offset":2000,"rows":4000,"counters":{"load:howto_d02:PX_HOWTO.D02|steady":80}}
   {"id":"t-20261123T235826307Z-1","state":"ENDED"}
   ```

   Data-load telemetry uses the key format `load:<scenario>:<table>`.
   Each recorded `load` sample corresponds to one committed batch of 50 rows: 40 x 50 = 2000 for
   slot 0 and 80 x 50 = 4000 for slot 1. The step reports no start skew because a data load has no
   start synchronization gate.

### Run the read and follow the start

10. Submit the query run plan, start the first replicate, and immediately request both the report
    and a concurrent second run.

    ```sh
    T_RUN="$(create "$HOWTO/plan-run.json")"
    R1="$(curl -fsS -X POST -d '' "$PF/tests/$T_RUN/runs" | jq -r .runId)"
    curl -sS -w ' HTTP %{http_code}\n' "$PF/tests/$T_RUN/runs/$R1/report"
    curl -sS -w ' HTTP %{http_code}\n' -X POST -d '' "$PF/tests/$T_RUN/runs"
    ```

    ```text
    {"error":"conflict","message":"run 20261123T235855769Z-2 is RUNNING; its report exists when
    the run ends"} HTTP 409
    {"error":"conflict","message":"test t-20261123T235855753Z-2 has an active run
    20261123T235855769Z-2"} HTTP 409
    ```

    Each JSON response above is emitted as a single line of output, wrapped here for readability.

    Both the fleet report and the run bundle are generated only after a run terminates. In addition,
    a test allows at most one `RUNNING` run at a time, guaranteeing that two runs of the same plan
    never access or mutate the same keyspace concurrently.

11. Follow the slot state transitions through completion.

    ```sh
    pf_wait "$T_RUN" "$R1"
    ```

    ```text
    RUNNING ASSIGNED ASSIGNED
    RUNNING LAUNCHED LAUNCHED
    RUNNING RUNNING RUNNING
    COMPLETE DONE DONE
    ```

    The polling loop did not capture `READY` or `ARMED` here because each state lasted less than
    0.5 s. The overall run took approximately 36 s, comprising roughly 6 s of preparation and start
    coordination followed by the 30 s measurement window.

    Start synchronization proceeds in two phases because the initialization time required by a
    Pherf child process—covering JVM startup, JDBC driver loading, ZooKeeper and metadata handshake,
    the validation gate, and warmup execution—has no upper bound:

    1. Once every slot reports `READY`, the controller sends `launch` to each agent. Each child
       process completes any configured warmup and holds at the Pherf start gate, placing the slot
       in the `ARMED` state.
    2. Once no slot remains in `LAUNCHED`, the controller schedules the start epoch on its local
       clock as now + 2000 ms + the largest round-trip delay observed across the armed agents. Each
       agent then receives a `start` command carrying `startAtMs`, calculated as the controller
       epoch plus that agent's clock offset, ensuring that all agents target the exact same physical
       instant on their respective local clocks.
    3. Each child process opens its start gate at `startAtMs` and initiates its first steady-state
       dispatch (`RUNNING`). A slot transitions to `DONE` only after the controller has ingested
       every telemetry frame from that slot along with its final report.

12. Inspect the synchronized start epoch, the per-agent clock estimates, and the start skew bound.

    ```sh
    curl -fsS "$PF/tests/$T_RUN/runs/$R1/status" >"$HOWTO/status-r1.json"
    jq -c '.steps[0] | {startAtMs, skewMs}, (.slots[] | {slot: .identity.slot, agent,
      offsetMs, delayMs, startAtMs, dispatchMs})' "$HOWTO/status-r1.json"
    ```

    ```text
    {"startAtMs":1795478339953,"skewMs":12}
    {"slot":0,"agent":"pherf-agent-0","offsetMs":-2,"delayMs":3,"startAtMs":1795478339951,
    "dispatchMs":1795478339958}
    {"slot":1,"agent":"pherf-agent-1","offsetMs":-1,"delayMs":4,"startAtMs":1795478339952,
    "dispatchMs":1795478339953}
    ```

    Each slot object above is a single line of output, wrapped here for readability. Interpret the
    fields as follows:

    - The step-level `startAtMs` is the target start epoch on the controller clock. Each slot-level
      `startAtMs` equals that epoch plus the estimated clock offset of its assigned agent (defined
      as agent clock minus controller clock): -2 ms and -1 ms in this run.
    - The controller estimates each agent's clock offset in NTP style from control-plane message
      exchanges, retaining the sample with the minimum round-trip delay out of the last 8 samples.
      The uncertainty bound of the clock offset estimate is at most half the round-trip delay plus
      1 ms.
    - `dispatchMs` records the instant when the child process opened its start gate, translated to
      the controller clock. The error bound on an individual dispatch timestamp is half the
      round-trip delay plus 2 ms.
    - `skewMs` represents a conservative upper bound on the true start skew across slots, computed
      as the latest minus the earliest dispatch timestamp plus the two largest per-slot error
      bounds: 5 + 3 + 4 = 12 ms here, well below the 50 ms threshold enforced by the fleet verdict.
      [D04][d04] examines start skew in detail.

### Read the work split and the telemetry

13. Inspect the data-plane identity and allocated workload share of each slot.

    ```sh
    jq -c '.steps[0].slots[] | {slot: .identity.slot, seed: .identity.seed,
      offset: .identity.offset, prefix: .identity.prefix, share}' "$HOWTO/status-r1.json"
    ```

    ```text
    {"slot":0,"seed":7126372246263655726,"offset":0,"prefix":"p000",
    "share":{"durationMs":30000,"threads":1,"rate":80,"rows":2000}}
    {"slot":1,"seed":2268977538148885555,"offset":2000,"prefix":"p001",
    "share":{"durationMs":30000,"threads":2,"rate":160,"rows":4000}}
    ```

    Partitioning 6000 rows via the largest remainder method across weights [1, 2] yields shares of
    2000 + 4000 = 6000, establishing starting row offsets of 0 and 2000. The aggregate rate of 240
    splits into 80 + 160, and the 3 execution threads split into 1 + 2. Each agent launched its
    child process with `-targetRate`, `-threadCount`, `-rowCountOverride`, and
    `-runDurationMs 30000`. Because slot seeds derive solely from the plan seed 8070 and the slot
    index, the load run in step 8 received the exact same slot identities.

14. Inspect the per-slot telemetry sequence counters and sample totals.

    ```sh
    jq -c '.steps[0].slots[] | {slot: .identity.slot, heldSeq, finalSeq, samples, counters}' \
      "$HOWTO/status-r1.json"
    ```

    ```text
    {"slot":0,"heldSeq":60,"finalSeq":60,"samples":4728,
    "counters":{"query:howto_d02:lookup|steady":2364,"response:howto_d02:lookup|steady":2364}}
    {"slot":1,"heldSeq":60,"finalSeq":60,"samples":9626,
    "counters":{"query:howto_d02:lookup|steady":4813,"response:howto_d02:lookup|steady":4813}}
    ```

    Each record above is a single line of output, wrapped here for readability. The 60 ingested
    frames correspond to 2 operation keys across 30 reporting intervals of 1 s each.

    Every 1000 ms, each agent transmits any newly appended lines from its child process's interval
    log, sending one frame per operation key numbered sequentially from 1. The controller accepts a
    frame only when its sequence number equals `heldSeq + 1`, ensuring that every sequence number is
    ingested exactly once even across retries. `finalSeq` records the last frame sequence number
    reported by the agent at `DONE`, and a slot reaches `DONE` only after `heldSeq` catches up to
    `finalSeq`. `samples` is the sum of all ingested sample counts for the slot, while
    `counters` breaks down the sample total for each individual operation key. Because every frame
    also carries the running cumulative total for its key on that agent, any lost or duplicated
    frame is immediately detected and fails the slot.

### Read the fleet report and the run bundle

15. Inspect the merged latency histograms, the `fleet` metadata section, and the schedule summaries
    in the fleet report.

    ```sh
    curl -fsS "$PF/tests/$T_RUN/runs/$R1/report" >"$HOWTO/report-r1.json"
    jq -r '.label, .hlog, (.histograms[] | "\(.operation)|\(.phase) count \(.count)" +
      " p50Us \(.p50Us) p99Us \(.p99Us)")' "$HOWTO/report-r1.json"
    jq -c '.fleet.steps[0].slots[] | {slot, agent, startLagMs, frames, samples, counts}' \
      "$HOWTO/report-r1.json"
    jq -c '.schedules[] | {step, slot, agent, scheduled, completed, aborted, late, stopReason}' \
      "$HOWTO/report-r1.json"
    ```

    ```text
    run
    LATENCY_run.hlog
    query:howto_d02:lookup|steady count 7177 p50Us 1815 p99Us 7351
    response:howto_d02:lookup|steady count 7177 p50Us 2285 p99Us 1212415
    {"slot":0,"agent":"pherf-agent-0","startLagMs":5,"frames":60,"samples":4728,"counts":{...}}
    {"slot":1,"agent":"pherf-agent-1","startLagMs":0,"frames":60,"samples":9626,"counts":{...}}
    {"step":0,"slot":0,"agent":"pherf-agent-0","scheduled":2364,"completed":2364,"aborted":0,
    "late":0,"stopReason":"duration"}
    {"step":0,"slot":1,"agent":"pherf-agent-1","scheduled":4813,"completed":4813,"aborted":0,
    "late":0,"stopReason":"duration"}
    ```

    The per-slot `counts` maps are abbreviated at `{...}` above and hold the exact counter values
    shown in step 14. Observing a `response` p99 of 1.2 s alongside a service-time p99 of roughly
    7 ms indicates client-side queuing rather than slow database reads, because the `response`
    measurement begins at the scheduled dispatch instant. This transient queue backlog results from
    a cold start at the synchronization gate because the scenario omits `warmupExecutions`
    ([D04][d04], step 7).

    The fleet report follows the version 1 JSON report format defined in [LATENCY.md][latency] with
    label `run`, allowing any standard Pherf report consumer to parse it without changes. Each
    operation key has a single histogram formed by merging every ingested frame across all slots,
    producing the exact same count, p50, p99, p99.9, and max values as a single recorder that had
    captured every sample directly. Verify the histogram merge against the slot totals:

    ```sh
    jq -r '[.fleet.steps[0].slots[].counts["query:howto_d02:lookup|steady"]] | add' \
      "$HOWTO/report-r1.json"
    jq -r '.histograms[] | select(.operation == "query:howto_d02:lookup" and
      .phase == "steady") | .count' "$HOWTO/report-r1.json"
    ```

    ```text
    7177
    7177
    ```

    Both totals match identically. The `fleet` member supplements the report with distributed
    execution metadata for each slot: its identity, workload share, clock estimates, `startLagMs`
    (the slot's dispatch timestamp minus the earliest dispatch timestamp in the step), `frames`,
    and `samples`. The `schedules` array collects the open-loop schedule summaries from each slot,
    annotated with `step`, `slot`, and `agent` on the controller clock. Under `POISSON` arrivals
    with deterministic slot seeds, `scheduled` is close to—rather than equal to—the nominal values
    of 2400 and 4800: 2364 and 4813 here, matching the exact pair produced in the D7 run of this
    profile shape. For every slot, `completed` equals `scheduled` and `aborted` is 0, confirming
    that no read missed its target row.

16. List the run bundle inventory and download every file.

    ```sh
    B="$PF/tests/$T_RUN/runs/$R1/bundle"
    curl -fsS "$B" | jq -r '.files[] | "\(.bytes)\t\(.path)"'
    curl -fsS "$B" | jq -r '.files[].path' | while read -r p; do
      mkdir -p "$(dirname "$HOWTO/bundle/$p")"
      curl -fsS -o "$HOWTO/bundle/$p" "$B/$p"
    done
    jq -c .run "$HOWTO/bundle/manifest.json"
    ```

    ```text
    4709	drivers/pherf/results_HDR/LATENCY_run.hlog
    4293	drivers/pherf/results_HDR/LATENCY_run.json
    1276	drivers/pherf/results_HDR/VERDICT_run.tsv
    16612	drivers/pherf/results_HDR/slots/0-0.hlog
    22341	drivers/pherf/results_HDR/slots/0-1.hlog
    305	manifest.json
    1684	status.json
    {"id":"20261123T235855769Z-2","class":"validation","result":"ADVISORY","state":"COMPLETE",
    "seed":8070,"started_ms":1795478335769,"ended_ms":1795478371419}
    ```

    Exact byte sizes vary slightly between runs. Rather than serving a single archive, the bundle
    endpoint provides a JSON file listing paired with one HTTP retrieval call per file. The
    controller persists `status.json` and then writes `manifest.json` last: `manifest.json` acts as
    the atomic commit marker, so any run directory lacking a manifest indicates an interrupted run.

    | Path | Content |
    |---|---|
    | `manifest.json` | Run metadata: id, class, `result` (the worst verdict row), state, seed. |
    | `status.json` | Snapshot of the run view at commit time, matching `GET .../status` JSON. |
    | `.../LATENCY_run.json` | Aggregated fleet report, byte-for-byte equal to `GET .../report`. |
    | `.../LATENCY_run.hlog` | One merged histogram per key across the entire reporting window. |
    | `.../slots/<step>-<slot>.hlog` | Raw interval log for each slot, persisted as held. |
    | `.../VERDICT_run.tsv` | Tab-separated fleet verdict evaluation table. |

    This directory structure conforms directly to the run-bundle layout expected by Pherf's offline
    analysis mode, allowing `-analyze` to consume a controller bundle unmodified ([H15][h15]).

17. Inspect the fleet verdict table.

    ```sh
    cut -f 1-3 "$HOWTO/bundle/drivers/pherf/results_HDR/VERDICT_run.tsv"
    ```

    ```text
    pherf.health.attainment	PASS	duration
    pherf.health.saturation	ADVISORY	duration
    pherf.health.client_pool	PASS	count
    pherf.health.tail_latency	ADVISORY	duration
    pherf.health.read_skew	SKIP	share
    pherf.fleet.start_skew	PASS	duration
    pherf.fleet.telemetry	PASS	count
    pherf.fleet.agents	PASS	count
    ```

    The fleet verdict always contains 8 rows emitted in this fixed order:

    - The five `pherf.health.*` rules defined in [VERDICT.md][verdict]. Attainment and saturation
      are evaluated across the schedules of all slots so that the worst-performing schedule governs
      the outcome. Tail latency is evaluated over the merged fleet histograms. Client pool and read
      skew adopt the row of the worst-performing agent because the underlying evidence exists only
      on each individual agent host.
    - `pherf.fleet.start_skew`: Evaluates to FAIL whenever the start skew bound of any step exceeds
      50 ms.
    - `pherf.fleet.telemetry`: Evaluates to FAIL if a cumulative frame total check failed any slot,
      or if the held sample count for any key of a `DONE` slot differs from the count in its final
      report.
    - `pherf.fleet.agents`: Evaluates to PASS only when the overall run is `COMPLETE` and every slot
      is `DONE`.

    In this validation run, any FAIL on a duration rule is downgraded to ADVISORY, whereas a count
    rule is never downgraded.

### Run a replicate

18. Launch a second run of the same test and compare the resulting slot identities and scheduled
    operation counts.

    ```sh
    R2="$(curl -fsS -X POST -d '' "$PF/tests/$T_RUN/runs" | jq -r .runId)"
    pf_wait "$T_RUN" "$R2" | tail -n 1
    for r in "$R1" "$R2"; do
      curl -fsS "$PF/tests/$T_RUN/runs/$r/status" |
        jq -c '[.steps[0].slots[].identity | [.seed, .offset, .prefix]]'
      curl -fsS "$PF/tests/$T_RUN/runs/$r/report" | jq -c '[.schedules[].scheduled]'
    done
    ```

    ```text
    COMPLETE DONE DONE
    [[7126372246263655726,0,"p000"],[2268977538148885555,2000,"p001"]]
    [2364,4813]
    [[7126372246263655726,0,"p000"],[2268977538148885555,2000,"p001"]]
    [2364,4813]
    ```

    Both replicates produce the exact same pair of scheduled counts, matching the pair from the D7
    run configured with the same profile shape, rates, and slot seeds. Because slot identities are
    derived from the plan rather than from the physical agents, every replicate assigns each slot
    the exact same seed, row offset, and prefix, generating the exact same arrival timestamps and
    target keys regardless of which agent executes which slot. Each replicate is a distinct run of
    the same test with its own run identifier and bundle; [H15][h15] demonstrates how to compare
    replicates.

## How to read the result

- **Counts are authoritative.** The workload shares, row offsets, load batch counts, scheduled and
  completed counts, merged histogram counts, and `pherf.fleet.*` count verdict rows hold on any
  host.
- **Timings of a validation run are ADVISORY.** The p50 and p99 latency values from this single-host
  run illustrate the structure of the fleet report rather than true Phoenix performance; [D04][d04]
  demonstrates how to measure fleet latency accurately.
- Any consumer of a standalone Pherf report can read the fleet report unchanged. The `fleet` member
  and the per-slot interval logs address questions specific to distributed execution: the workload
  share, start lag, and telemetry integrity of each agent.

## What invalidates it

- **A slot token in a key column.** Embedding a slot token in a key column couples each key to a
  specific slot index, causing any subsequent step with a different slot count or weight vector to
  query keys that were never written. Keep slot tokens out of key columns and keep the
  co-generation seed fixed.
- **`writeParams` past a share.** Configuring `writeParams` on a partitioned step can write rows
  outside a slot's assigned range `[o_i, o_i + n_i)`, encroaching on the keyspace of another slot.
  Do not combine `writeParams` with a work split.
- **Another agent count or other weights between the load and the read, with `clusterLimit`.**
  When co-generation specifies `clusterLimit`, the leading key component is derived from the local
  row index within each slot, requiring the read step to match the exact agent count and weights of
  the load step. Without `clusterLimit`, keys depend only on the global row index, so a subsequent
  step with a different agent count still covers the same `[0, N)` keyspace ([D04][d04] relies on
  this property).
- Because a rule-based scenario uses its own internal generator seed, only `${pherf.slot.prefix}`
  (or the slot offset embedded in SQL) partitions its keyspace across slots ([H14][h14],
  fleet-rules).

## How to reproduce it

Use the same repository tree, the scenario, profile, and schema documents from steps 4 and 5, the
test plans from steps 6 and 7, and the plan seed 8070. The slot identities and scheduled operation
counts will repeat identically, whereas execution durations and latencies will vary across runs.
[H14][h14] executes this same workload pattern as `fleet-rww.suite`, capturing the run bundle
inside a harness result directory.

## Clean up

1. Terminate the query test on the controller. Calling end on an already ended test is idempotent
   and returns HTTP 200.

   ```sh
   curl -fsS -X POST -d '' "$PF/tests/$T_RUN/end" | jq -c '{id, state}'
   ```

2. Tear down the validation cluster and delete the working files generated during this HOWTO. Skip
   this step—including the `rm` command—if you are continuing directly to [D03][d03] or
   [D04][d04], because both guides reuse the active cluster and `$HOWTO/plan-run.json`.

   ```sh
   px-cluster down
   rm -rf "$HOWTO"
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [H14: run Pherf on a fleet through the harness][h14]: executes this same run as an automated
  harness suite.
- [D03: cancel, faults and restart][d03] and [D04: scale out the load generator][d04] for fault
  injection and multi-agent scaling.
- [P04: co-generation][p04] and [P03: open-loop profiles][p03] for single-process workload design.
- The module README sections covering [Work split][split], [Keyspace][keyspace],
  [Start synchronization][start], [Run bundle][bundle], [Fleet report][report], and
  [Fleet verdict][fverdict].

[d01]: 01-standalone-fleet-and-rest-api.md
[d03]: 03-faults-cancel-and-restart.md
[d04]: 04-load-generator-scale-out.md
[h02]: ../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md
[h14]: ../../../dev/harness/docs/howto/14-fleet-through-the-harness.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
[p03]: ../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md
[p04]: ../../../phoenix-pherf/docs/howto/04-co-generation.md
[latency]: ../../../phoenix-pherf/LATENCY.md
[verdict]: ../../../phoenix-pherf/VERDICT.md
[split]: ../../README.md#work-split
[keyspace]: ../../README.md#keyspace
[start]: ../../README.md#start-synchronization
[bundle]: ../../README.md#run-bundle
[report]: ../../README.md#fleet-report
[fverdict]: ../../README.md#fleet-verdict
