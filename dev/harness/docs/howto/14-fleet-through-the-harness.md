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

# How to run Pherf on a fleet through the harness: px-pherf and the pherf-distributed driver

## The question

Does a fleet of Pherf agents partition a single workload accurately across all participating
agents, and does the harness capture the entire fleet execution within a single result bundle? This
HOWTO provisions the Distributed Pherf tier in a harness cluster, sizes the deployment against the
container engine, and executes the fleet suites using `px-pherf` and the `pherf-distributed`
driver. It demonstrates that the agents populate disjoint key ranges, that every agent queries the
specific rows it inserted, and that the merged histogram for each key aggregates the samples
collected from every agent.

The tier consists of a single controller and its associated agents, which are the processes
described in [the module README][module]. While the [Distributed Pherf HOWTOs][d-index] cover the
REST API and the controller architecture in detail, this HOWTO focuses on the harness integration,
covering the configuration switch, memory sizing, CLI commands, driver behavior, bundle layout, and
the included fleet suites.

## Before you start

**Audience.** This guide is intended for developers who validate changes under multi-client load
and operators who plan a fleet of load generators against a Phoenix cluster.

**Prerequisites.**

- Complete [How to run a Pherf scenario through the harness][h04], which introduces the `pherf`
  driver, its metric anchors, and the Pherf health verdict.
- Complete [How to prove read-what-you-wrote correctness under load][h07], which covers
  co-generation and the query counts that verify read correctness.
- Ensure that no other harness cluster is currently running on the host.
- Install `jq` 1.7 or newer, which step 23 uses to inspect the JSON plans and controller status.
  Older versions of `jq` round the 64-bit slot seeds printed in step 23, whereas the harness itself
  does not require it.
- Provide a Docker engine with at least 19456 MiB of memory to accommodate the validation class
  with 2 RegionServers and the tier; this total comprises 15360 MiB of container limits plus the
  4096 MiB of free headroom enforced by preflight checks. A 24 GiB engine provides sufficient
  capacity.

| Cost | Value |
|---|---|
| Wall time | Takes about 15 minutes, plus about 10 minutes for the first build of the tier. |
| Docker memory | Uses 12800 to 15360 MiB of container limits (validation class with the tier). |
| Disk | Uses several hundred MB for the pherf image and a few MiB for each bundle. |
| Files written | Creates `m1.tsv` and 2 suite row files in `$HOWTO`, 7 bundles, and 3 sessions. |

Initialize every shell session for this guide with the following three lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h14"; mkdir -p "$HOWTO"
```

## Method

**The question, restated.** A multi-client load test is valid only when every client executes its
assigned portion of the plan: no key is inserted more than once, no key is queried before its
writing agent commits it, and the aggregate fleet workload equals the sum of the individual agent
shares. When a fleet overlaps its keyspace, it artificially reduces write cost and inflates cache
hit rates compared to the target workload. Conversely, when a fleet loses an agent, it delivers
less offered load than claimed.

**Hypotheses.**

1. The tier fits within the container engine budget in the validation class (requiring 12800 MiB
   with 1 RegionServer and 15360 MiB with 2), whereas the measurement class with the tier fits a
   24 GiB engine only when configured with 1 RegionServer and no YARN.
2. The driver partitions the operation rate, thread count, and row count proportionally by weight
   with exact sums, assigning each agent a contiguous key range. Given weights of 1,2 and 6000
   total rows, the resulting slot offsets are 0 and 2000.
3. Every agent queries all keys that it inserted and no others: zero lookup misses occur, and every
   count query in `fleet-rww.suite` returns its expected row count.
4. The merged operation count for each key equals the sum of the per-slot counts.

**Controls.** The experiments hold constant the validation class, 2 RegionServers, 2 agents, the
scenarios and schemas under `dev/harness/suites/pherf/`, the default plan seed of 8070, and
identical arguments across replicates. Every test reloads its target table from scratch and drops
the table upon completion.

**Noise.** Because the validation class disables the quiet-host check, all timing values report as
ADVISORY. The agents, the controller, and the cluster services share processors on a single
container engine, meaning the agents contend with the RegionServers for CPU time. Operation counts
and data-plane identities are independent of execution timing and remain authoritative on any host.

**What to measure and why.**

- Measure the plan seed, slot offsets, and per-slot shares because they verify that the workload
  partitioning is exact.
- Measure the scheduled, completed, and aborted operation counts for each slot schedule because
  they confirm that every agent completed its assigned share without missed reads.
- Compare the merged sample count for each key against the sum of the individual slot counts
  because this proves that histogram aggregation neither dropped nor double-counted any sample.
- Measure `pherf.fleet.start_skew_ms` and `pherf.slot.start_lag_ms` to determine how far apart the
  agents started. A start skew exceeding 50 ms causes `pherf.fleet.start_skew` to fail.
- Evaluate the 8 rows of the fleet verdict, which grade the distributed execution using the same
  criteria applied to a single Pherf run.

**How to read the result.** The validation proof holds when all operation counts are exact and the
fleet verdict contains no FAIL rows. Timing rows that report ADVISORY are expected during a
validation run.

**What invalidates it.** A run is invalid if it terminates with exit code 3 (signaling an anomaly
such as a cluster fault or a missing or `FAULTED` agent), if the controller run does not reach the
`COMPLETE` state, if any slot offset is not an exact multiple of the scenario block size, or if
`pherf.bundle` reports FAIL (which indicates that the harness bundle does not contain the exact
controller bundle it reports).

**How to reproduce it.** The plan seed, agent count, weight distribution, and row count
deterministically govern every data-plane identity, including the slot seed, row offset, and key
prefix. Executing two runs with identical arguments produces identical identities and, for POISSON
arrival schedules seeded from `${pherf.slot.seed}`, identical arrival timestamps.

### The tier in the harness

- Setting `PX_PHERF=1` serves as the single activation switch. Running
  `px-cluster -e PX_PHERF=1 build` also compiles `phoenix-pherf-distributed` and builds the pherf
  container image tagged with `PX_PHERF_IMAGE_TAG`. Rendering a deployment with this switch defines
  the tier as the on-demand role group `pherf`, containing the `pherf-controller` and `pherf-agent`
  roles.
- The role group starts only on demand via `px-pherf start`, `px-pherf run`, or
  `px-cluster group start pherf`. Preflight checks account for its memory limits starting from
  initial cluster bring-up.
- `PX_PHERF_AGENT_COUNT` defaults to 2 in the validation class (which permits a range of 1 to 2)
  and 3 in the measurement class (which permits a range of 1 to 9). The controller is allocated a
  memory limit of 1024 MiB, and each agent is allocated 1536 MiB to house both the agent JVM and
  one Pherf child JVM.
- `PX_PHERF_TARGET` accepts either `harness` (the default) or `external`. The harness target
  attaches the tier to the deployment and its internal ZooKeeper ensemble, whereas the external
  target renders only the tier containers against the ZooKeeper quorum specified in
  `PX_PHERF_ZOOKEEPER`.
- The harness communicates with the controller REST API exclusively through instance 0 of
  `pherf-controller` by invoking curl inside that container. The controller also publishes REST
  port 8780 on `127.0.0.1` of the host for interactive operator access, as demonstrated with curl
  in the [D02][d02] HOWTO.

## Steps

### Build the tier

1. Build the Phoenix JAR artifacts, the harness image, and the pherf image.

   ```sh
   px-cluster -e PX_PHERF=1 build
   ```

   ```text
   ... INFO  build-phoenix.sh: building Phoenix ... with JDK 17 (...), log .../maven.log
   ...
   ... INFO  px-cluster: built the image px-harness:...
   ...
   ... INFO  px-cluster: built the image px-pherf:...
   ```

   On the reference host, the initial build completed in about 9.5 minutes. Because the distributed
   JAR is packaged inside the pherf image rather than the base harness image, and the tier runs the
   pherf image, you must build with the switch enabled prior to the first bring-up that uses it.
   Rebuild with the switch after any modification to the source tree, just as you do for the base
   harness image.

### Size the fleet before you bring it up

Preflight validation admits container limits up to the total engine memory minus 4096 MiB of
reserved headroom. On the reference 24 GiB engine, the engine reports 24001 MiB of available
memory, leaving an effective budget of 19905 MiB. Steps 2 through 5 invoke `px-cluster up --dry-run`
to execute the read-only preflight sequence for cluster bring-up: the command renders each
candidate topology into a temporary scratch directory, validates the cluster record, host state,
container image, engine memory budget, and host ports, emits one summary row per check, and returns
the exit status that an actual bring-up would produce. No containers are started, and filtering
with grep isolates the memory evaluation rows.

2. Evaluate the memory footprint of the validation class when the distributed tier is enabled.

   ```sh
   px-cluster --class validation -e PX_PHERF=1 --dry-run up | grep -E '^memory(:pherf|_fit)'
   ```

   ```text
   memory:pherf-controller	ok	1 x 1024 MiB = 1024 MiB, group pherf starts on demand
   memory:pherf-agent	ok	2 x 1536 MiB = 3072 MiB, group pherf starts on demand
   memory_fit	ok	the limits 12800 MiB (12.50 GiB) fit the budget with 7105 MiB (6.94 GiB) to
   spare; the budget 19905 MiB (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the headroom
   4096 MiB (4.00 GiB)
   ```

   Each output record is a single line, wrapped here for readability. Combining the 8704 MiB base
   validation class with the tier allocation of 1024 + 2 x 1536 = 4096 MiB produces a total limit
   of 12800 MiB. On a 16 GiB engine (which provides a 12288 MiB budget), this topology is refused;
   on that engine size, the tier can run with a single agent via `-e PX_PHERF_AGENT_COUNT=1` at
   11264 MiB.

3. Evaluate the topology used for the rest of this guide: the validation class with 2 RegionServers.

   ```sh
   px-cluster --class validation -e PX_PHERF=1 -e PX_RS_COUNT=2 --dry-run up | grep '^memory_fit'
   ```

   ```text
   memory_fit	ok	the limits 15360 MiB (15.00 GiB) fit the budget with 4545 MiB (4.44 GiB) ...
   ```

   Adding the second RegionServer increases the memory limit by 2560 MiB. A fleet workload that
   verifies read-what-you-wrote behavior requires more than one RegionServer to exercise region
   routing, because a single RegionServer masks stale region locations.

4. Evaluate the measurement class on a 24 GiB engine with both the tier and YARN enabled.

   ```sh
   px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env \
     -e PX_PHERF=1 --dry-run up >"$HOWTO/m1.tsv"
   grep '^memory_fit' "$HOWTO/m1.tsv"
   ```

   ```text
   ... ERROR preflight.sh: the deployment ... does not fit the engine. ...
   ... ERROR px-cluster: up ends (exit 3, anomaly)
   memory_fit	fail	the limits 24832 MiB (24.25 GiB) exceed the budget by 4926 MiB (4.81 GiB);
   the budget 19905 MiB (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the headroom 4096
   MiB (4.00 GiB)
   ```

   Even when `up --dry-run` rejects a topology, it still emits every preflight check row to
   standard output; consequently, `m1.tsv` captures the per-role memory allocations alongside the
   failing `memory_fit` row while the command returns exit code 3, matching a live bring-up.

   In the measurement class, the tier requires 1024 + 3 x 1536 = 5632 MiB. Even without YARN
   (which saves 768 + 2048 + 512 MiB), the total footprint is 21504 MiB, which remains 1599 MiB
   over budget when running 2 RegionServers of 3584 MiB each.

5. Evaluate the measurement class when configured with 1 RegionServer and YARN disabled.

   ```sh
   px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env \
     -e PX_PHERF=1 -e PX_YARN=0 -e PX_RS_COUNT=1 --dry-run up | grep '^memory_fit'
   ```

   ```text
   memory_fit	ok	the limits 17920 MiB (17.50 GiB) fit the budget with 1985 MiB (1.94 GiB) ...
   ```

   This configuration is the only measurement topology with the tier enabled that fits within a 24
   GiB engine. Running 2 RegionServers in the measurement class alongside the tier requires a
   container engine with at least 21504 + 4096 = 25600 MiB of memory. Because every run using the
   override file is tagged with `contamination sizing`, its timing verdicts are marked ADVISORY
   (H01). [H15][h15] executes workloads on this topology.

6. Request 3 agents in the validation class to verify role scaling bounds.

   ```sh
   px-cluster --class validation -e PX_PHERF=1 -e PX_PHERF_AGENT_COUNT=3 --dry-run render \
     >/dev/null
   ```

   ```text
   ... ERROR compose.gen.sh: PX_PHERF_AGENT_COUNT=3: the validation class runs 1 to 2 agents
   ... ERROR px-cluster: render ends (exit 2, usage)
   ```

   The measurement class permits between 1 and 9 agents, which is the upper bound for every
   scalable role.

### Choose the target

7. Render the tier standalone for an external cluster outside the harness by supplying that
   cluster's ZooKeeper quorum.

   ```sh
   px-cluster --class validation -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
     -e PX_PHERF_ZOOKEEPER=zk1.example.com:2181 render --check | cut -f 1-4
   ```

   ```text
   pherf-controller-0  pherf-controller  0  pherf
   pherf-agent-0       pherf-agent       0  pherf
   pherf-agent-1       pherf-agent       1  pherf
   ```

   An external deployment contains only the tier services within its own scope and container
   network, omitting all standard harness roles. Running `px-pherf start` brings the deployment up
   when no active scope is recorded, and `px-pherf stop` tears it down. The agent containers must
   be able to resolve and reach the ZooKeeper quorum, the HBase Master, and every RegionServer
   hostname of that external cluster. Because the external target does not include client 0, the
   driver preserves the test tables upon completion (`pherf.tables` reports SKIP). This step
   performs a render check only, as the reference host does not run a second cluster.

8. Pass `PX_PHERF_ZOOKEEPER` to the harness target, and then pass a quorum string containing a
   disallowed character to the external target.

   ```sh
   px-cluster --class validation -e PX_PHERF=1 -e PX_PHERF_ZOOKEEPER=zk1:2181 render --check \
     >/dev/null; echo "exit $?"
   px-cluster --class validation -e PX_PHERF=1 -e PX_PHERF_TARGET=external \
     -e 'PX_PHERF_ZOOKEEPER=zk1:2181;x' render --check >/dev/null; echo "exit $?"
   ```

   ```text
   ... ERROR ... PX_PHERF_ZOOKEEPER is for PX_PHERF_TARGET=external. The harness target reads
   the ZooKeeper of the deployment
   exit 2
   ... ERROR ... PX_PHERF_ZOOKEEPER='zk1:2181;x' must hold letters, digits and . _ : , / - only
   exit 2
   ```

   The harness target always derives its ZooKeeper ensemble from its own deployment. No file in the
   repository stores a default external quorum; the operator must supply one explicitly.

### Bring up the cluster and start the tier

9. Start the 2-RegionServer validation cluster from step 3 along with the distributed tier, waiting
   until every agent transitions to the `IDLE` state.

   ```sh
   px-pherf --class validation -e PX_RS_COUNT=2 start
   ```

   ```text
   ... rendered 12 services for cluster default, class validation: 15360 MiB of container
   limits, 4096 MiB of them on demand, into .../phoenix-harness/deploy/default
   ...
   ... INFO  px-pherf: the tier runs: one controller and 2 IDLE agents
   pherf-agent-0	IDLE	0445ed9e-244a-4c46-8dac-2602cfa22f20
   pherf-agent-1	IDLE	a5c3a6eb-c2e2-403b-87a5-ecb4e5f179cb
   ```

   When no active cluster is recorded, `start` provisions the cluster with `PX_PHERF=1` enabled and
   leaves it running until `px-cluster down` tears it down. Initial cluster bring-up launches the
   core harness roles while leaving the on-demand `pherf` role group stopped, after which `start`
   activates that role group. Because the persisted cluster record stores both the run class and
   `PX_RS_COUNT=2`, subsequent commands do not need to specify them again. Each status line reports
   an agent identifier, its current lifecycle state, and its incarnation UUID, which is freshly
   generated each time the process starts. Tier startup completed in roughly 7 s here (compared to
   10.7 s in H3), with the preflight checks and readiness table emitted to standard error. If
   invoked against a cluster rendered without the tier enabled, `start` fails with exit code 6 and
   prints the remediation sequence: run `px-cluster down` followed by `px-pherf start`.

10. Inspect the current status of the distributed tier.

    ```sh
    px-pherf status
    ```

    ```text
    pherf	3	3	up
    pherf-agent-0	IDLE	0445ed9e-244a-4c46-8dac-2602cfa22f20
    pherf-agent-1	IDLE	a5c3a6eb-c2e2-403b-87a5-ecb4e5f179cb
    ```

    The first row summarizes the role group: group name, running count, desired count, and group
    state (`up`, `partial`, or `down`). The `px-pherf` utility defines the following exit codes: 0
    on success, 1 on general failure, 2 on usage errors, 3 when the tier is stopped or a container
    instance has exited, 4 when a readiness wait times out, and 6 when the target cluster does not
    exist or was rendered without the tier. The `run` subcommand propagates the exit status of
    `px-suite`.

11. Scale the active agent pool down to 1 instance; the persisted cluster record automatically
    supplies the run class and bring-up settings.

    ```sh
    px-pherf scale 1
    ```

    ```text
    ... INFO  compose.gen.sh: rendered 11 services for cluster default, class validation: 13824
    MiB of container limits, 2560 MiB of them on demand, into .../deploy/default
    ...
    ... INFO  px-cluster: recorded planned scale pherf-agent in .../planned-px-default.tsv
    role         index  instance_id   state    ready  image_ref                         node
    pherf-agent  0      eaf907c114ca  running  ready  px-pherf:hbase-2.6.7-profile-2.6  localhost
    ... INFO  px-pherf: the tier runs: one controller and 1 IDLE agents
    pherf-agent-0	IDLE	0445ed9e-244a-4c46-8dac-2602cfa22f20
    ```

12. Scale the agent pool back up to 2 instances.

    ```sh
    px-pherf scale 2
    ```

    ```text
    ...
    pherf-agent  0      eaf907c114ca  running  ready  px-pherf:hbase-2.6.7-profile-2.6  localhost
    pherf-agent  1      e830f7c63b49  running  ready  px-pherf:hbase-2.6.7-profile-2.6  localhost
    ... INFO  px-pherf: the tier runs: one controller and 2 IDLE agents
    pherf-agent-0	IDLE	0445ed9e-244a-4c46-8dac-2602cfa22f20
    pherf-agent-1	IDLE	2a3f2d41-90d5-4ee9-8cf1-b4bd5571edb3
    ```

    Because `pherf-agent` is designated as a scalable role, `scale` re-renders the deployment from
    the saved cluster record, validates the updated memory footprint via preflight checks, starts
    or stops only the agent instances affected by the count delta, and updates the recorded count.
    Any re-render that would modify an instance belonging to another role is rejected with exit
    code 2. Because `px-pherf scale` blocks until all active agents report `IDLE`, a separate
    `start` call is unnecessary. Across both scaling operations, agent 0 kept its running container
    instance while only agent 1 was stopped and recreated.

### Run one test with the pherf-distributed driver

The driver accepts the following arguments after the `--` separator of `px-run`:

- `--scenario FILE` is required, and `--schema FILE` may be supplied at most once.
- `--properties FILE` provides run-level Pherf properties. The driver appends
  `pherf.verdict.noisyHost` at the end, setting it to `true` on a contaminated run.
- `--agents N` defaults to the cluster's `PX_PHERF_AGENT_COUNT`, and `--weights W,...` takes one
  positive integer weight for each agent.
- `--seed N` defaults to 8070 and sets the plan seed shared by both plans of the test.
- `--rows N`, `--rate N`, `--duration-ms N`, and `--threads N` specify the fleet totals that the
  controller partitions across agents. `--duration-ms` is not divided; every agent runs for that
  full duration.
- `-- PHERF_OPTION...` forwards additional Pherf options to each agent.

13. Pass the driver a Pherf option that the driver itself manages.

    ```sh
    SUITES="$PWD/dev/harness/suites"
    RUN_OWN="$(px-run pherf-distributed -- \
      --scenario "$SUITES/pherf/px_fleet_scenario.xml" -- -runDurationMs 1000)"
    grep -h 'remove it' "$RUN_OWN/logs/validate.log"
    ```

    ```text
    ... INFO  px-run: run ... ends at ERROR (exit 2, usage)
    ... ERROR pherf-distributed: the driver sets the Pherf option '-runDurationMs', remove it (exit 2, usage)
    ```

    The driver reserves ownership of `-l`, `-q`, `-label`, `-scenarioFile`, `-schemaFile`,
    `-disableSchemaApply`, `-runDurationMs`, `-threadCount`, `-targetRate`, and
    `-rowCountOverride`, in any short or long spelling. Supply workload share parameters using
    `--rate`, `--threads`, `--rows`, and `--duration-ms`. Passing a second `--schema` option or a
    `--weights` list whose length does not match the agent count also exits with code 2.

14. Pass the driver a Pherf option that is owned by the agent environment.

    ```sh
    FLEET=(--scenario "$SUITES/pherf/px_fleet_scenario.xml"
      --schema "$SUITES/pherf/px_fleet_schema.sql")
    RUN_PLAN="$(px-run pherf-distributed -- "${FLEET[@]}" --rows 2000 -- -zookeeper zk1:2181)"
    grep pherf.plan "$RUN_PLAN/report/verdicts.tsv" | cut -f 1,2,5
    ```

    ```text
    ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
    pherf.plan  FAIL  the controller refused plan-load.json: steps[0].args[3]: -z belongs to the
    agent; the agent takes it from PHERF_ZOOKEEPER
    ```

    The output row is a single line, wrapped here for readability. The controller's plan submission
    check rejects the plan before any agent receives work and reports the exact JSON path of the
    violation. Here, `args[3]` is the fourth Pherf option in the load plan: `-schemaFile`,
    `slice/px_fleet_schema.sql`, `-l`, followed by `-zookeeper`. The [D01][d01] HOWTO documents
    every validation rule enforced by the submission check. A plan that the agents reject during
    pre-launch validation—such as a scenario referencing an unknown column—terminates as `ABORTED`
    and records FAIL for `pherf.gate`, also exiting with code 1.

15. Execute the fleet scenario across the 2 agents with fleet totals of 6000 rows, 100 operations
    per second, a 30 s duration, and 4 worker threads.

    ```sh
    RUN="$(px-run pherf-distributed -- "${FLEET[@]}" \
      --rows 6000 --rate 100 --duration-ms 30000 --threads 4)"
    ```

    ```text
    ... INFO  px-run: run ... ends at ADVISORY (exit 0, ok)
    ```

    The `prepare` phase submits the load plan inside the `setup@load` window, where each agent
    applies the schema and inserts its assigned share of rows. The `run` phase submits the measured
    plan inside `steady@pherf`. Because both plans share the same seed, agent count, weights, and
    row count, each agent queries the exact key range that it loaded. Finally, `restore` terminates
    each controller test and drops the table via client 0.

16. List the files recorded by the driver.

    ```sh
    ls "$RUN/drivers/pherf-distributed" "$RUN/drivers/pherf-distributed/controller"
    ```

    ```text
    .../drivers/pherf-distributed:
    agents.json  catalog.out  catalog.sql  controller  describe.json  load  plan-load.json
    plan-run.json  report.json  restore.out  restore.sql  run.tsv  tables.list  tests.tsv

    .../drivers/pherf-distributed/controller:
    drivers  manifest.json  status.json
    ```

    - `agents.json` records the controller's agent roster captured by the final readiness wait.
    - `plan-load.json` and `plan-run.json` contain the two plans submitted by the driver.
    - `tests.tsv` records the phase verb, test id, run id, and terminal state of each controller
      test, while `run.tsv` records the steady window, the test id, the run id, and its final
      state.
    - `report.json` is the consolidated fleet report. It must be byte-for-byte identical to
      `controller/drivers/pherf/results_HDR/LATENCY_run.json`, or `pherf.bundle` reports FAIL.
    - `controller/` holds the controller bundle for the measured run, and `load/` holds the
      controller bundle for the load phase, each copied file by file without modification.
    - `controller/drivers/pherf/results_HDR/` contains the fleet report, the merged interval log
      `LATENCY_run.hlog`, the fleet verdict `VERDICT_run.tsv`, and `slots/<step>-<slot>.hlog`,
      which stores the raw interval log produced by each agent.

    The `restore.sql` and `restore.out` files capture the table drop executed by client 0 during
    `restore`. On the harness target, `catalog.sql`, `catalog.out`, and `tables.list` record the
    table metadata queried by client 0—specifically the `UPDATE_CACHE_FREQUENCY` property of each
    table in the schema file, which is also written to `tables.tsv` in the bundle root.
    `controller/manifest.json` is the controller's own manifest with its own
    `bundle_format_version`, rather than a harness manifest.

17. Inspect the per-slot shares assigned to the agents.

    ```sh
    awk -F '\t' '$4 ~ /^slot@/ { print $4, $5, $7 }' "$RUN/metrics/aggregate.tsv"
    ```

    ```text
    slot@0.0 pherf.slot.rate 50
    slot@0.0 pherf.slot.threads 2
    slot@0.0 pherf.slot.rows 3000
    slot@0.0 pherf.slot.samples 3000
    slot@0.0 pherf.slot.start_lag_ms 0
    slot@0.1 pherf.slot.rate 50
    slot@0.1 pherf.slot.threads 2
    slot@0.1 pherf.slot.rows 3000
    slot@0.1 pherf.slot.samples 3000
    slot@0.1 pherf.slot.start_lag_ms 0
    ```

    The metric anchor follows the pattern `slot@<step>.<slot>`. The individual shares are computed
    using the largest-remainder rule with equal weights: 100 / 2, 4 / 2, and 6000 / 2.
    `start_lag_ms` measures the dispatch timestamp of the slot minus the earliest dispatch
    timestamp in the step, so the earliest agent always reads 0; here, both agents opened their
    gates within the same millisecond. `samples` totals the two histogram keys recorded for the
    slot (`query` and `response`), yielding 2 x 1500.

18. Inspect the schedule counters for each slot alongside the merged count for the query key.

    ```sh
    awk -F '\t' '$4 ~ /^schedule@/ && $5 ~ /\.(scheduled|completed|aborted)$/ { print $4, $5, $7 }
      $4 == "op@query_px_fleet_lookup" && $5 == "pherf.operations" { print $4, $5, $7 }' \
      "$RUN/metrics/aggregate.tsv"
    ```

    ```text
    op@query_px_fleet_lookup pherf.operations 3000
    schedule@px_fleet.1.0.0 pherf.schedule.scheduled 1500
    schedule@px_fleet.1.0.0 pherf.schedule.completed 1500
    schedule@px_fleet.1.0.0 pherf.schedule.aborted 0
    schedule@px_fleet.1.0.1 pherf.schedule.scheduled 1500
    schedule@px_fleet.1.0.1 pherf.schedule.completed 1500
    schedule@px_fleet.1.0.1 pherf.schedule.aborted 0
    ```

    The row order in the output may vary. Because each schedule belongs to a single slot, its
    anchor is `schedule@<scenario>.<querySet>.<step>.<slot>`. A flat UNIFORM arrival stage at 50
    operations per second over 30 s produces exactly 1500 scheduled arrivals per agent, and the
    merged key aggregates 1500 + 1500 = 3000 operations. The merged histogram is mathematically
    identical to a single recorder capturing every sample directly, yielding the same count, p50,
    p99, p99.9, and max.

19. Inspect the fleet totals and the computed achieved throughput.

    ```sh
    awk -F '\t' '$4 == "steady@pherf" && $5 ~ /^(pherf\.fleet\.|achieved_qps)/ {
      print $5, $7 }' "$RUN/metrics/aggregate.tsv"
    ```

    ```text
    pherf.fleet.agents 2
    pherf.fleet.start_skew_ms 6
    achieved_qps 91.847
    ```

    `pherf.fleet.agents` reports the slot count of the widest step in the plan.
    `pherf.fleet.start_skew_ms` reports the maximum start skew bound across all steps; the
    reference host measured between 6 and 14 ms. `achieved_qps` divides the steady-state operation
    count by the duration of the fleet report window (3000 operations over 32.663 s here). Because
    the fleet report window is the union of the individual report windows recorded by each slot's
    Pherf child process on the controller clock, it spans slightly longer than the planned 30 s
    duration, causing `achieved_qps` to read below the offered rate of 100 operations per second.
    The driver clamps each window to the steady-state window of the host.

    Do not interpret `achieved_qps` as the schedule attainment rate. Whether the fleet kept pace
    with its schedule is graded by the `pherf.health.attainment` verdict row, which compares
    scheduled against completed operations on each slot and reports the worst slot. Here, the union
    window extends 2.7 s beyond the plan duration while the start skew is only 6 ms, showing that
    the difference stems from the child report window boundaries rather than a late agent. For
    throughput claims, divide the scheduled and completed counts from step 18 by `durationMs`.

20. Inspect the fleet verdict rows.

    ```sh
    grep -E '^pherf\.' "$RUN/report/verdicts.tsv" | cut -f 1-4
    ```

    ```text
    pherf.agents               PASS      none      driver:pherf-distributed
    pherf.bundle               PASS      none      driver:pherf-distributed
    pherf.execution            PASS      none      driver:pherf-distributed
    pherf.health.attainment    PASS      duration  pherf
    pherf.health.saturation    ADVISORY  duration  pherf
    pherf.health.client_pool   PASS      count     pherf
    pherf.health.tail_latency  ADVISORY  duration  pherf
    pherf.health.read_skew     SKIP      share     pherf
    pherf.fleet.start_skew     PASS      duration  pherf
    pherf.fleet.telemetry      PASS      count     pherf
    pherf.fleet.agents         PASS      count     pherf
    pherf.verdict              PASS      none      driver:pherf-distributed
    pherf.latency              PASS      none      driver:pherf-distributed
    ```

    The columns are tab-separated in the file and aligned here for readability. The saturation and
    tail latency rows report ADVISORY due to a cold start when the execution gate opens:
    `pherf.schedule.max_drift_us` of `steady@pherf` is 1306712 (1.3 s), and the query p99 latency
    is 1.1 s ([D04][d04], step 7). The driver appends the 8 rows of the fleet verdict without
    modification: five `pherf.health.*` rows evaluated across all slots (where the attainment row
    reflects the worst schedule) and three `pherf.fleet.*` rows. `pherf.fleet.start_skew` fails
    whenever a step skew exceeds 50 ms. `pherf.fleet.telemetry` fails if any telemetry records are
    lost or duplicated. `pherf.fleet.agents` fails unless the run state is `COMPLETE` and every
    slot state is `DONE`. The driver-specific rows—`pherf.plan` (the controller refused the plan),
    `pherf.gate` (the agents refused the plan), `pherf.agents` (a missing, busy, or `FAULTED`
    agent, exiting with code 3), and `pherf.bundle` (the bundle copy verification)—identify the
    specific stage that failed. In a validation run, any FAIL verdict of class duration is
    downgraded to ADVISORY.

### Run the shipped fleet suites

21. Preview the execution plan of the read-what-you-wrote suite with a dry run, which starts no
    containers.

    ```sh
    px-pherf -n run fleet-rww
    ```

    ```text
    plan	check	px-suite --dry-run fleet-rww
    plan	start	px-cluster group start pherf
    plan	wait	GET /v1/agents
    plan	suite	px-suite fleet-rww
    ```

    Each `plan` entry identifies one orchestration phase and the command it would invoke; the
    `check` and `suite` rows display the exact argument vectors of the live invocations, so the
    `suite` row omits `-n`.
    During a live invocation without `-n`, `run` first validates the suite definition via
    `px-suite --dry-run` so that any syntax error or invalid option aborts execution before any
    container is started. If no active cluster is recorded, it provisions the cluster with
    `PX_PHERF=1`, starts the distributed tier, and invokes `px-suite`. Once the session terminates—
    whether by passing, failing, or catching a signal—`run` tears down only the resources it
    started itself (either the entire cluster or just the tier if the cluster was already running),
    unless `--keep` is passed. Because both the cluster and the tier were started in step 9, the
    invocations below do not take ownership of either resource and leave both running.

#### Read what you wrote across agents

`fleet-rww.suite` executes the co-generated read-what-you-wrote proof across 2 agents weighted at
1,2, with fleet totals of 6000 rows, 240 operations per second, and a 30 s duration. Each agent
loads its assigned slot in 50-row blocks starting from `${pherf.slot.offset}`. During the measured
run, each agent queries every committed key in its slot using an open loop of
`COUNT(*) ... WHERE K1 = [K1]` lookups that each expect a count of 1, deletes the first 5 blocks of
its slot, and counts both the deleted range (expecting 0) and the subsequent 1750 rows (expecting
1750). Any query whose count does not match the expected value fails that execution, which records
no latency sample.

22. Run the suite against the active cluster.

    ```sh
    px-pherf run fleet-rww | tee "$HOWTO/rww.tsv"
    ```

    ```text
    1	r1	ADVISORY	0	84756	the verdicts of this test reach ADVISORY	.../runs/...
    2	r2	ADVISORY	0	80078	the verdicts of this test reach ADVISORY	.../runs/...
    result	ADVISORY	0	.../phoenix-harness/sessions/...
    ```

    In the summary rows, the fifth column records the elapsed duration of each test in milliseconds
    and the seventh column records its result bundle path. Executing the suite on the running
    cluster required 2 min 51 s here, versus 3 min 48 s in H3 when including an initial cluster
    bring-up. Assign the bundle path of r1 to `RWW`:

    ```sh
    RWW="$(awk -F '\t' '$2 == "r1" { print $7 }' "$HOWTO/rww.tsv")"
    ```

23. Inspect the plan seed and the derived data-plane identity of each slot.

    ```sh
    jq -c '{seed} + (.steps[0] | {agents, rows, rate, durationMs})' \
      "$RWW/drivers/pherf-distributed/plan-run.json"
    jq -c '.steps[0].slots[].identity' "$RWW/drivers/pherf-distributed/controller/status.json"
    ```

    ```text
    {"seed":8070,"agents":2,"rows":6000,"rate":240,"durationMs":30000}
    {"slot":0,"seed":7126372246263655726,"offset":0,"prefix":"p000"}
    {"slot":1,"seed":2268977538148885555,"offset":2000,"prefix":"p001"}
    ```

    The controller deterministically derives each slot seed from the plan seed and the slot index,
    so identical plans produce identical slot seeds on any host. The 1,2 weights partition the rows
    into shares of 2000 and 4000, producing slot offsets of 0 and 0 + 2000. Both offsets are exact
    multiples of the block size of 50, ensuring that the literal key ranges in the scenario align
    with block boundaries. The target operation rates are partitioned into 80 and 160 operations
    per second.

24. Inspect the lookup, miss, and delete counts.

    ```sh
    awk -F '\t' '$4 ~ /^schedule@px_rww\./ && $5 ~ /\.(scheduled|completed|aborted)$/ {
        print $4, $5, $7 }
      $4 ~ /^op@(query|delete)_px_rww_/ && $5 == "pherf.operations" { print $4, $7 }' \
      "$RWW/metrics/aggregate.tsv"
    ```

    ```text
    op@query_px_rww_lookup 7177
    op@delete_px_rww_delete 10
    op@query_px_rww_deleted 2
    op@query_px_rww_rest 2
    schedule@px_rww.1.0.0 pherf.schedule.scheduled 2364
    schedule@px_rww.1.0.0 pherf.schedule.completed 2364
    schedule@px_rww.1.0.0 pherf.schedule.aborted 0
    schedule@px_rww.1.0.1 pherf.schedule.scheduled 4813
    schedule@px_rww.1.0.1 pherf.schedule.completed 4813
    schedule@px_rww.1.0.1 pherf.schedule.aborted 0
    ```

    Evaluate these results in the following order:

    - The merged lookup count of 7177 equals the exact sum of the slot counts (2364 + 4813).
    - Both slots report 0 aborted operations, and completed operations match scheduled arrivals.
      Because every lookup expects 1 row, any missing key would abort the operation; therefore, no
      read missed a committed key.
    - Each slot executed at least as many lookups as its row share: 2364 >= 2000 and 4813 >= 4000.
      POISSON arrivals seeded from `${pherf.slot.seed}` produce arrival counts close to, rather
      than exactly equal to, the nominal products of 80 x 30 = 2400 and 160 x 30 = 4800. Because r1
      and r2 share identical slot seeds, they produce identical arrival counts.
    - Exactly 10 delete executions ran (5 on each slot). The 2 `deleted` and 2 `rest` query
      executions all recorded samples, confirming that each slot counted 0 remaining rows in its
      deleted range and 1750 rows immediately following it.

25. Distinguish what the bundle proves directly from what follows from the generator code.

    The bundle proves that each slot loaded its assigned blocks, that zero lookups missed, that the
    merged operation counts equal the sum of the per-slot counts, and that the deletes removed the
    target rows without affecting any other row among the first 2000 rows of each slot. By itself,
    however, the bundle does not prove that every distinct key in `[0, 6000)` was read. That
    property follows from the implementation: the lookup walk window equals the slot's row share
    because `-rowCountOverride` is passed to both the load and the run of each agent, and a single
    walk pass visits every committed row once. Because the bundle does not record the walk window
    size M, all bundle checks above would also pass under a smaller window. A self-contained
    bundle-level proof would require recording M in the schedule entry, which the report does not
    currently include.

    Similarly, the delete metric counts statement executions rather than deleted rows. Because the
    number of rows removed by each `DELETE` statement is written only to each agent's legacy result
    files, the `rest` query count serves as the bundle evidence that the deletes removed no
    unintended rows.

#### A rule-based closed loop across agents

`fleet-rules.suite` executes a traditional rule-based Pherf scenario without co-generation across 2
agents. The key column uses `SEQUENTIAL` generation with the prefix `${pherf.slot.prefix}` (`p000`
on agent 0 and `p001` on agent 1), ensuring that each agent loads keys disjoint from every other
agent. The suite specifies only the row count (4000 total rows, or 2000 per agent); a closed-loop
workload takes no target rate, and the scenario document defines the thread concurrency (1 thread,
then 2 threads), 300 executions per thread, and the stage duration ceiling.

26. Execute the rule-based suite.

    ```sh
    px-pherf run fleet-rules | tee "$HOWTO/rules.tsv"
    RULES="$(awk -F '\t' '$2 == "rules" { print $7 }' "$HOWTO/rules.tsv")"
    awk -F '\t' '$4 ~ /^op@query_px_rules_/ && $5 ~ /^pherf\.(operations|latency\.p50_us)$/ {
      print $4, $5, $7 }' "$RULES/metrics/aggregate.tsv"
    ```

    ```text
    1	rules	SKIP	0	82039	the verdicts of this test reach SKIP	.../runs/...
    result	SKIP	0	.../phoenix-harness/sessions/...
    op@query_px_rules_agg pherf.operations 1800
    op@query_px_rules_agg pherf.latency.p50_us 13511
    op@query_px_rules_count pherf.operations 1800
    op@query_px_rules_count pherf.latency.p50_us 7019
    op@query_px_rules_point pherf.operations 1800
    op@query_px_rules_point pherf.latency.p50_us 1366
    op@query_px_rules_range pherf.operations 1800
    op@query_px_rules_range pherf.latency.p50_us 9551
    ```

    Execution finished in 1 min 22 s with an overall SKIP verdict: the attainment, saturation,
    and read-skew verdict rows have no schedule input in a closed loop, and no row reported FAIL or
    ADVISORY. Each query key recorded 900 samples per agent (300 executions across 1 + 2 threads)
    and 1800 samples in the merged histogram. Because the `count` query expects 4000 rows and each
    agent scans the entire table, recording 900 samples per agent proves that the two loads
    together inserted 4000 distinct rows.

    Only the `count` query specifies an expected row count. Because a Pherf run whose read queries
    return zero rows still exits with code 0, a `point` lookup that finds nothing passes silently
    unless the scenario or the verdict validates it. Configure `expectedAggregateRowCount` on every
    query intended to prove that rows were read.

    Note that the aggregate query places `COUNT(*)` first in the select list: whenever a statement
    contains `COUNT(`, Pherf parses column 1 of the result set as a long integer, so writing
    `SELECT KIND, COUNT(*) ...` would fail on every execution when reading the `CHAR` column.

27. Execute the first replicate of the shipped fleet benchmark suite.

    ```sh
    px-pherf run fleet --only r1
    ```

    ```text
    1	r1	ADVISORY	0	110551	the verdicts of this test reach ADVISORY	.../runs/...
    result	ADVISORY	0	.../phoenix-harness/sessions/...
    ```

    `fleet.suite` defines three replicates (r1 through r3), each configured with 20000 rows, 300
    operations per second, a 60 s duration, and 12 worker threads. Across 2 agents, each slot
    receives 150 operations per second, 6 worker threads, and 10000 rows, so the fleet executes
    a combined 18000 lookups. Running this replicate on the active cluster took 1 min 57 s here,
    versus 3 min 50 s in H3 when cluster bring-up was included. [H15][h15] executes all three
    replicates and evaluates them offline. Note that `-analyze` reads each replicate from
    `<bundle>/drivers/pherf-distributed/controller` and exits with code 1 if pointed directly at
    the outer bundle root. Although `fleet.suite` declares the measurement class, the execution
    class persisted in the cluster record (validation, from step 9) takes precedence over any
    suite-level class declaration, so this invocation requires no `--class` flag; an explicit
    command-line `--class` option still overrides both.

### The tier and the cluster checks

28. Inspect how the cluster log health check treats the tier.

    ```sh
    grep '^cluster.log' "$RUN/report/verdicts.tsv" | cut -f 2,5
    ```

    ```text
    PASS  0 ERROR or FATAL lines, 2 WARN lines in 9 logs; log.errors.max=0 log.warnings.max=100;
    load generator, kept and not counted: pherf-agent/0 pherf-agent/1 pherf-controller/0
    ```

    The output row is a single line, wrapped here for readability. Because the tier acts as the
    load generator rather than the system under test, the bundle archives its logs without counting
    any of their lines toward the cluster log thresholds. However, the container restart, OOM, and
    liveness checks, as well as the anomaly watch of `px-run`, still monitor the tier. The
    collector target `all` also excludes the `pherf` role group because the pherf image does not
    include a profiler, JFR tools, or jHiccup (see [H10][h10]).

29. Stop the tier and verify its status.

    ```sh
    px-pherf stop
    px-pherf status
    ```

    ```text
    pherf	0	3	down
    ```

    Running `stop` halts the `pherf` role group on a harness target and tears down an external
    deployment. The controller retains its run directory on the `pherf-controller-0-data` volume
    until the cluster is torn down.

## Read the result

- **The split is correct** when the per-slot shares sum to the fleet totals (step 17), the slot
  offsets are contiguous and block-aligned (step 23), each slot schedule completes all of its
  arrivals with 0 aborted operations (steps 18 and 24), and each merged key count equals the sum of
  the individual slots.
- **The proof failed** if any lookup aborted, if a count query recorded fewer samples than its
  execution count, or if a merged count differs from the sum of the slots. Each of these outcomes
  represents a deterministic correctness failure in the build or in the workload split, regardless
  of host conditions.
- **The run is invalid** if any slot is not `DONE`, if the controller run is not `COMPLETE`, if
  `pherf.bundle` reports FAIL, or if the harness run exited with code 3.
- **The times** recorded during a validation run are ADVISORY. Before making a timing claim from a
  fleet run, consult [How to scale out the load generator and read fleet latency correctly][d04].

## Performance engineering notes

- **Correctness of the split.** A fleet that overlaps its key ranges benchmarks a smaller dataset
  than claimed. Because slot offsets are computed as the prefix sum of the row shares, the resulting
  slot ranges are disjoint and their union spans `[0, N)`. Keep slot-specific tokens out of the key
  columns of a co-generated table so that each primary key depends solely on the global row index.
- **Fleet sizing.** On a single container engine, every agent reserves 1536 MiB of memory that the
  cluster cannot use, and the agent processes compete directly with the RegionServers for CPU
  cores. Size the fleet using the preflight memory arithmetic first (steps 2 through 5). Running a
  multi-agent fleet on a single host removes the bottleneck of a single client JVM, not the
  resource ceiling of the host itself.
- **The same identities in each replicate.** Every replicate in a comparison series must use the
  same seed, agent count, weights, and row count. Changing any of these parameters produces
  different slot seeds and row offsets, defining a different workload.
- **The run bound.** The distributed tier does not impose an independent run timeout on a Pherf
  child process. Against a stopped cluster, the first failed operation halts the open arrival loop,
  and the run finishes once the client timeouts for in-flight operations expire. [H15][h15]
  demonstrates the harness verb bound and its known limitations.

## Reproduce it

- All suites, scenarios, configuration profiles, and SQL schemas are versioned files in the
  repository tree; record the Git commit hash.
- The plan seed (defaulting to 8070), the agent count, the weights, and the row count
  deterministically fix every data-plane identity. Within each bundle, `plan-load.json` and
  `plan-run.json` capture the complete submitted plans, including the embedded document texts.
- `cluster/topology.env` records `PX_PHERF`, `PX_PHERF_TARGET`, `PX_PHERF_AGENT_COUNT`, and
  `PX_PHERF_IMAGE_TAG`, while `cluster/images.tsv` records the image digest of each container
  instance.
- Preserve the `$RUN`, `$RWW`, and `$RULES` bundle directories; invoking `px-bundle validate "$RUN"`
  verifies the integrity of an archived copy.

## Clean up

1. Tear down the active cluster, removing both the distributed tier containers and their associated
   data volume. The `down` command verifies cleanup and exits with code 1 if any cluster resource
   remains behind.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

2. Delete the temporary working directory holding the preflight output and captured suite rows.

   ```sh
   rm -rf "$HOWTO"
   ```

## See also

- [How to compare and stress a fleet: replicates, A/B with -analyze, and faults under fleet
  load][h15]
- [The Distributed Pherf HOWTOs][d-index]: [D01][d01] covers the controller, the agents, and the
  REST API; [D02][d02] covers a plan, the workload split, and the fleet report; [D03][d03] covers
  faults, cancellation, and restarts; and [D04][d04] covers load-generator scale-out.
- [The module README][module], covering the execution plans, the workload split, the keyspace, the
  run bundle, and the fleet verdict.
- [The driver contract, section "pherf-distributed"][driver] and [the result bundle, section
  "The other exports"][bundle].
- [The backends, section "The Distributed Pherf tier"][backends].

[backends]: ../backends.md
[bundle]: ../bundle-format.md
[driver]: ../driver-contract.md
[h04]: 04-pherf-through-the-harness.md
[h07]: 07-read-what-you-wrote.md
[h10]: 10-profiling-and-root-cause.md
[h15]: 15-fleet-replicates-ab-and-faults.md
[module]: ../../../../phoenix-pherf-distributed/README.md
[d-index]: ../../../../phoenix-pherf-distributed/docs/howto/README.md
[d01]: ../../../../phoenix-pherf-distributed/docs/howto/01-standalone-fleet-and-rest-api.md
[d02]: ../../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[d03]: ../../../../phoenix-pherf-distributed/docs/howto/03-faults-cancel-and-restart.md
[d04]: ../../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
