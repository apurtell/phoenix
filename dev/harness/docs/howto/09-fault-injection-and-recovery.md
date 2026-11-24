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

# How to inject faults and measure recovery

## The question

When a RegionServer terminates or restarts while serving active traffic, how long does it take for
every region to return to service, what latency behavior does the client experience while waiting,
and is any committed data lost? This guide demonstrates how to inject planned faults into a live
workload using `px-run --disrupt`, measure the resulting recovery time, evaluate the impact on the
tail response latency of an open-loop workload, and verify row counts after recovery completes. It
also explains how the harness distinguishes an unplanned restart from a planned disruption, which
separates a valid resilience test from an unintended cluster failure.

## Before you start

**Audience.** This guide is intended for Phoenix developers working on changes that affect region
assignment, the client retry path, the indexer, or the WAL, as well as committers reviewing
resilience claims.

**Prerequisites.**

* You have built a container image and verified that a validation cluster starts cleanly, as
  covered in [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md).
* You are familiar with the pherf driver and open-loop workloads, as described in
  [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md) and
  [05-latency-slo-and-soak.md](05-latency-slo-and-soak.md).
* You have a container engine configured with at least 16 GiB of memory.
* For step 16 only, the `docker` CLI must be installed on the host.
* The `jq` utility must be available on the host to parse `manifest.json`.

| Cost | Value |
|---|---|
| Wall time | About 30 min for 6 Pherf runs of 3 to 6 min, the shipped suite, bring-up, teardown. |
| Docker memory | 11264 MiB for the validation class with 2 RegionServers (8704 + 2560 MiB). |
| Disk | Less than 1 GiB under `$PX_OUT`. |
| Files written | `$PX_OUT/howto/h09/`, 7 bundles under `$PX_OUT/runs/`, 1 session in `sessions/`. |

The memory requirement follows directly from the validation class budget: a baseline topology with
1 RegionServer consumes 8704 MiB, and each additional RegionServer adds a 2560 MiB limit. Because
the preflight check admits the total engine memory minus a 4096 MiB reserve, a 16 GiB engine
provides 12288 MiB of usable capacity and accommodates 2 RegionServers with 1024 MiB to spare.

Begin every shell session for this guide by running these three setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h09"; mkdir -p "$HOWTO"
```

## Method

**Hypotheses.**

1. Following a hard kill of a RegionServer, every region returns online within the two-minute
   recovery gate (`recovery.seconds.max=120`), and no residual fault persists afterward
   (`post_recovery.faults.max=0`).
2. During the outage, the open-loop response time rises substantially above the raw service time
   and throughput attainment drops, because request arrivals continue on schedule while worker
   threads block waiting for region recovery.
3. No committed row is lost during the disruption, meaning the post-fault row count matches the
   initial loaded row count.
4. A drain-aware rolling restart across each RegionServer in turn imposes lower client-visible
   latency than an abrupt kill.

**Controls.** Every run uses a single cluster with 2 RegionServers and a single co-generated table
containing 20000 rows, salted across 4 buckets so that both RegionServers host active regions. All
runs execute an identical open-loop workload configured as a Poisson arrival process at 40
operations per second with a fixed random seed, 16 worker threads, and the `LATE` lag policy. A
fault-free baseline run is executed first for comparison.

**Noise.** Because the validation class produces `ADVISORY` timing verdicts, a single run per fault
mode demonstrates only the directional magnitude of the effect. Recovery duration varies with
ZooKeeper session timeouts, region count, and host contention; reference measurements recorded
17.6 s for a hard kill in A7 and 40 to 70 s across 3 RegionServers in A4.

**What to measure and why.**

* Record `mttr_seconds` for each entry in the `disruptions[]` array of `manifest.json`. This timer
  spans from immediately before the substrate invocation until the terminated instance is running
  again, all instances report ready, and the online region count returns to its pre-fault level;
  subsequent region rebalancing is excluded.
* Inspect the `cluster.recovery` verdict (which uses class `none` and is never demoted) along with
  the `cluster.post_recovery` verdict (which uses class `count`).
* Compare the open-loop `response` and `query` latency keys, where the difference between response
  time and service time captures the client-side queueing delay introduced by the fault, and check
  `pherf.health.attainment` and `pherf.health.saturation`.
* Verify the row count returned by the closed-loop query set that executes after the open-loop
  phase completes.

**What invalidates the result.** Any disruption entry whose state is not `ok` invalidates the run,
because an entry marked `skip`, `fail`, or `missed` did not inject a fault that completed recovery.
An unplanned restart of another cluster instance (exit 3) also invalidates the test, as does any
discrepancy in the final row count, which indicates a primary data-correctness failure. Finally, a
fault that triggers before the workload enters its steady state is invalid and must be verified by
checking the injection timestamp against the `steady@pherf` window.

**Out of scope.** The harness supports three disruption actions: `kill`, `signal`, and `restart`.
Network partitions, artificial latency injection, packet loss, disk hardware faults, and disk-full
conditions are outside the scope of this mechanism.

## Steps

### Bring up the cluster and write the workload

1. Start a validation cluster configured with 2 RegionServers:

   ```sh
   px-cluster --class validation -e PX_RS_COUNT=2 up
   ```

   ```text
   ... INFO  px-cluster: provisioning cluster default in scope px-default from .../deploy/default
   ```

2. Write the table schema file. Because Pherf submits the file as a single statement, omit any
   trailing semicolon:

   ```sh
   cat >"$HOWTO/fault.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.FAULT (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000
   EOF
   ```

3. Author the Pherf scenario definition. Co-generation computes each primary key deterministically
   from the row index, ensuring that every point lookup targets a row committed during the data
   load phase. The first query set drives the timed open-loop workload, and the second query set
   verifies the total row count afterward:

   ```sh
   cat >"$HOWTO/fault.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_fault">
     <datamapping>
       <column>
         <type>BIGINT</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <minValue>0</minValue>
         <maxValue>1000000000</maxValue>
         <name>K</name>
       </column>
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <length>32</length>
         <name>V</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.FAULT" rowCount="20000" name="howtoFault">
         <coGeneration seed="9" blockSize="1000"/>
         <querySet concurrency="16" warmupExecutions="2">
           <openLoop arrival="POISSON" seed="9" lagPolicy="LATE">
             <stage durationMs="120000" rate="40"/>
           </openLoop>
           <query id="get" statement="SELECT V FROM PX_HOWTO.FAULT WHERE K = [K]"/>
         </querySet>
         <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="1"
                   executionDurationInMs="60000">
           <query id="count" queryGroup="count"
                  statement="SELECT COUNT(*) FROM PX_HOWTO.FAULT"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   The open-loop schedule is declared inline so that the scenario remains a single self-contained
   file, although the harness also stages any external `profile="..."` file placed next to the
   scenario.

### Measure the baseline

4. Run the workload with no injected faults to establish a clean baseline:

   ```sh
   RUN_BASE="$(px-run pherf -- --scenario "$HOWTO/fault.xml" --schema "$HOWTO/fault.sql")"
   ```

   ```text
   ... INFO  px-run: run 20261127T103600Z-p31g ends at ... (exit 0, ok)
   ```

5. Define a shell helper function to summarize the primary metrics throughout the rest of this
   guide. The function outputs the p99 service latency (`query`) and p99 response latency
   (`response`) in microseconds, together with the effective attainment and saturation verdicts
   and the row count produced by the verification query:

   ```sh
   read_run() {
     px-bundle metrics "$1" 'op@*_howtoFault_get' pherf.latency.p99_us
     awk -F '\t' '$1 ~ /^pherf\.health\.(attainment|saturation)$/ { print $1, $2, $5 }' \
       "$1/report/verdicts.tsv"
     awk -F '\t' '$1 == "count" { print "count result_rows", $6 }' "$1/drivers/pherf/groups.tsv"
   }
   read_run "$RUN_BASE"
   ```

   ```text
   op@query_howtoFault_get	pherf.latency.p99_us	7639	us
   op@response_howtoFault_get	pherf.latency.p99_us	12567	us
   pherf.health.attainment PASS lowest attainment 100.0% (...: 4946 of 4946 completed, ...);
   minimum 90%
   pherf.health.saturation PASS peak lag 11.764 ms (...), within 1000 ms; queue peak 4 of 16,
   below its bound; in-flight peak 4 of 16; late 0, dropped 0
   count result_rows 20000
   ```

   These latency values are illustrative. On a healthy, uncontended cluster, the response p99
   closely tracks the service p99. Because a validation run is considered host-contaminated
   (`quiet-host`), the driver sets `pherf.verdict.noisyHost=true`, causing any failing duration
   threshold check to report `ADVISORY`.

### Kill a RegionServer under load

6. Execute the workload again while scheduling a hard kill of RegionServer 0 at 30 s after the `run`
   verb begins. The disruption specification uses the format `WHEN,ACTION,ROLE,INDEX[,ARGUMENT]`. A
   kill action must target a RegionServer—because only the drain-aware restart procedure restarts a
   terminated instance—and uses the `hard` mode:

   ```sh
   RUN_KILL="$(px-run pherf --disrupt '+30,kill,regionserver,0,hard' -- \
     --scenario "$HOWTO/fault.xml" --schema "$HOWTO/fault.sql")"
   ```

   ```text
   ... INFO  px-run: disruption 1: kill regionserver/0 hard
   ... INFO  px-run: run 20261127T103914Z-d914 ends at ... (exit 0, ok)
   ```

   The `+SECONDS` trigger form measures elapsed time from the start of the `run` verb, whereas the
   `phase:NAME` form triggers at a named lifecycle anchor. For example, `phase:steady@pherf`
   triggers as soon as the driver opens its steady-state window, before the Pherf JVM issues its
   first query. Using a 30 s offset ensures that the fault occurs while the open-loop query phase
   is actively running.

7. Examine the recorded disruption metadata. Every disruption entry reports its injection state,
   recovery state, and measured `mttr_seconds`:

   ```sh
   jq -c '.disruptions[] | [.ordinal, .action, .role, .index, .argument, .when, .state,
     .recovery, .injected_ms, .recovered_ms, .mttr_seconds]' "$RUN_KILL/manifest.json"
   ```

   ```text
   [1,"kill","regionserver",0,"hard","+30","ok","ok",1795776000359,1795776018040,17.681]
   ```

   The recovery duration shown here is illustrative. The `recovery` field is marked `ok` only after
   the terminated instance is running again, every cluster instance reports ready, and the online
   region count returns to its pre-fault value.

8. Review the cluster-level health verdicts produced by the fault run:

   ```sh
   awk -F '\t' '$1 ~ /^cluster\.(recovery|post_recovery|restart|log)$/ { print $1, $2, $5 }' \
     "$RUN_KILL/report/verdicts.tsv"
   ```

   ```text
   cluster.restart PASS 0 instances changed with no planned action; restart.unplanned.max=0
   cluster.log PASS 0 ERROR or FATAL lines, 2 WARN lines in 8 logs; ...; planned action, kept
   and not counted: regionserver/0; 3 lines in a recovery window, not counted
   cluster.recovery PASS 1 disruptions recovered; 1 kill regionserver/0: mttr 17.681 s;
   recovery.seconds.max=120
   cluster.post_recovery PASS 0 faults after the recovery; px-bootstrap-probe in client/0
   passed; post_recovery.faults.max=0
   ```

   Because `px-run` registers the kill as a planned action before executing it, the restart of
   RegionServer 0 is not flagged as an anomaly. In addition, the log analyzer excludes `ERROR`
   lines emitted by any instance between fault injection and recovery—padded by 2 s of slack on
   each end—because a planned disruption predictably causes peer nodes to log transient errors. Any
   error logged after the recovery window closes is still counted. If `cluster.log` fails, inspect
   the log files under `logs/instance/` before adjusting `log.errors.max`.

9. Contrast the client-side latency and saturation metrics with those of the baseline run:

   ```sh
   read_run "$RUN_KILL"
   ```

   ```text
   op@query_howtoFault_get	pherf.latency.p99_us	9567	us
   op@response_howtoFault_get	pherf.latency.p99_us	6680575	us
   pherf.health.attainment PASS lowest attainment 100.0% (...: 4946 of 4946 completed, taken
   in 119968 ms of a 120000 ms window); minimum 90%
   pherf.health.saturation ADVISORY noisy host (pherf.verdict.noisyHost): FAIL reads as
   ADVISORY; peak lag 7763.439 ms (...), above 1000 ms; queue peak 16 of 16, at its bound;
   in-flight peak 16 of 16: the workers could not keep up; late 0, dropped 0
   count result_rows 20000
   ```

   These figures are illustrative. Service latency by itself hides the outage because each worker
   thread measures only its own active JDBC execution. Response latency, by contrast, starts at the
   scheduled arrival timestamp and therefore records the queueing delay accumulated behind blocked
   workers, reaching a 7 s p99 and an 8 s peak lag versus 13 ms in the baseline run. Note
   that the longest client wait remains shorter than the full 17.7 s mttr because the Master
   reassigned the failed server's regions to the surviving RegionServer before the terminated
   instance completed its restart, whereas mttr encompasses that instance restart. Attainment stays
   at 100% because 16 workers drained the resulting backlog before the measurement window ended;
   triggering a fault near the end of the window or running with fewer workers lowers attainment.
   Always inspect the `response` key rather than the `query` key when evaluating fault impact. See
   [05-latency-slo-and-soak.md](05-latency-slo-and-soak.md) for a fuller treatment of coordinated
   omission.

10. Confirm data integrity from the final row count. Observing `count result_rows 20000` post-fault
    confirms that every row from the initial load remained intact after the terminated server's WAL
    was split and replayed. Note that this workload verifies only that pre-existing loaded writes
    were preserved. To verify that writes in flight during a fault also survive, run the
    co-generated read/write workload from [07-read-what-you-wrote.md](07-read-what-you-wrote.md)
    using the same `--disrupt` specification.

### Restart each RegionServer in turn

11. Perform a rolling restart preceded by a thread-dump signal. The `restart` action invokes the
    region mover to drain regions from the target instance, restarts the container, waits for
    readiness, and triggers a cluster rebalance. The `signal` action delivers a single POSIX
    signal; sending `QUIT` instructs the JVM to write a thread dump to its standard output log.
    Because `px-run` waits for each disruption entry to finish recovery before firing the next one,
    this run uses an extended workload window:

    ```sh
    RUN_ROLL="$(px-run pherf --disrupt 'phase:steady@pherf,signal,regionserver,1,QUIT' \
      --disrupt '+40,restart,regionserver,0' --disrupt '+120,restart,regionserver,1' -- \
      --scenario "$HOWTO/fault.xml" --schema "$HOWTO/fault.sql" -- -runDurationMs 240000)"
    ```

    Passing `-runDurationMs` after the second `--` extends the open-loop stage duration to 240 s
    without altering its arrival profile, and the driver forwards that argument exclusively to the
    `run` verb. You can also combine multiple disruption specifications into a single
    `--disrupt` argument delimited by semicolons.

12. Examine the three disruption records, verify the thread dump in the log, and check the client
    metrics:

    ```sh
    jq -c '.disruptions[] | [.ordinal, .action, .index, .state, .recovery, .mttr_seconds]' \
      "$RUN_ROLL/manifest.json"
    grep -c 'Full thread dump' "$RUN_ROLL/logs/instance/regionserver-1.log"
    read_run "$RUN_ROLL"
    ```

    ```text
    [1,"signal",1,"ok","ok",0.767]
    [2,"restart",0,"ok","ok",76.982]
    [3,"restart",1,"ok","ok",77.513]
    1
    op@query_howtoFault_get	pherf.latency.p99_us	8083	us
    op@response_howtoFault_get	pherf.latency.p99_us	13031	us
    pherf.health.attainment PASS lowest attainment 100.0% (...); minimum 90%
    pherf.health.saturation PASS peak lag 14.82 ms (...), within 1000 ms; queue peak 4 of 16,
    below its bound; in-flight peak 11 of 16; late 0, dropped 0
    count result_rows 20000
    ```

    These durations are illustrative. Within `run-record.tsv`, the `phase` rows decompose each
    restart cycle into drain (29 to 30 s), stop, start, ready (17 s), and rebalance (29 to 30 s)
    sub-phases. Even though each graceful restart reports roughly 80 s of total mttr, the client
    experienced only a 15 ms peak lag and a 13 ms response p99, tracking the baseline almost
    exactly. Because region draining moves regions off the server before it stops, clients see only
    brief region handoffs rather than a sudden outage and WAL split, confirming hypothesis 4.

    Immediately after a rebalance, a RegionServer can respond to JMX queries before publishing
    `regionCount`. When the pre-disruption region count is not yet available, `px-run` retries the
    read within the readiness timeout bound so that the third disruption entry triggers on
    schedule. Builds prior to this fix logged `regionserver 1
    publishes no regionCount` and `disruption 3 not injected` in 1 of 2 runs.

    **`STOP` and `CONT` pause the JVM.** Any signal other than `KILL` and `TERM` is delivered
    directly to the JVM rather than to `tini`, the initial process of the container. Under
    `--disrupt 'phase:steady@pherf,signal,regionserver,1,STOP'` paired with
    `--disrupt '+20,signal,regionserver,1,CONT'`, the `STOP` entry reports state `ok` only once the
    JVM has halted, and its recovery phase waits for the `CONT` signal so that `mttr_seconds`
    spans both the roughly 19 s pause and the ensuing recovery. Any `STOP` action not resumed by
    the schedule automatically receives a `CONT` signal from `px-run` when the run verb completes.
    Earlier builds sent the signal to `tini`, leaving the RegionServer running while recording a
    0.5 s pause.

    Keep any pause shorter than 25 s. Because the container image runs ZooKeeper with
    `tickTime=2000`, a session lasts at most 40 s, and the paused server may have transmitted its
    last heartbeat ping up to one-third of that interval before receiving the `STOP` signal. A
    longer pause causes the session to expire: the Master processes the server as dead, the server
    aborts upon resuming execution, and recovery from the pause fails. When this occurs, the
    recovery gate detects that the instance has exited and fails immediately without waiting for
    the remainder of `PX_TIMEOUT_READY`; a 30 s pause triggered this failure in 1 of 1 runs (run
    `20261123T064007Z-ro1h`). Under a known defect, however, if the readiness check observes the
    resumed instance as ready before it aborts and another role subsequently becomes unready
    (because the Master of a 2-RegionServer cluster waits for both servers), the gate waits for the
    entire `PX_TIMEOUT_READY` interval and terminates with a timeout; see [H15, step 22][h15].

    The recovery gate also fails immediately if an instance becomes absent, such as when a
    container is deleted during recovery. Because the gate verifies instance presence before each
    region-count query and again immediately after a query returns the full region count, a read
    that started just before the instance disappeared cannot erroneously satisfy the gate. The
    session standard error stream identifies the missing instance:

    ```text
    ... ERROR disruption 2: regionserver/0 is absent, so it cannot recover
    ```

    When this happens, the `recover` row in `run-record.tsv` is recorded as `fail` and the
    `cluster.recovery` verdict reports FAIL. The Distributed Pherf end-to-end validation
    demonstrated this behavior live, failing within 1.5 to 2.5 s after the `CONT` signal against a
    `PX_TIMEOUT_READY` bound of 600 s. Any instance exit that occurs after recovery has already
    completed is handled by the background anomaly watch rather than the recovery gate.

### Prove that the gate fails

13. Run a shorter hard-kill test with the recovery bound `recovery.seconds.max` lowered to 1 s.
    Passing `-e PX_THRESHOLD_<KEY>=VALUE` overrides the corresponding threshold entry defined in
    `suites/thresholds.properties` for a single execution:

    ```sh
    RUN_GATE="$(px-run pherf -e PX_THRESHOLD_RECOVERY_SECONDS_MAX=1 \
      --disrupt '+30,kill,regionserver,1,hard' -- \
      --scenario "$HOWTO/fault.xml" --schema "$HOWTO/fault.sql" -- -runDurationMs 60000)"
    awk -F '\t' '$1 == "cluster.recovery" { print $2, $3, $5 }' "$RUN_GATE/report/verdicts.tsv"
    ```

    ```text
    ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
    FAIL none 1 of 1 disruptions failed, missed their anchor or did not recover in time; 1 kill
    regionserver/1: mttr 17.678 s; recovery.seconds.max=1
    ```

    Because `cluster.recovery` belongs to verdict class `none`, `px-report` never demotes it to
    an advisory warning—even under the validation class—because recovery completion functions as a
    strict liveness gate rather than a noise-sensitive latency benchmark. Passing a
    `-e PX_THRESHOLD_*` override that does not match any recognized threshold key triggers an
    immediate exit 2, preventing a misspelled key from silently reverting to the default limit. The
    effective threshold settings are saved in the bundle's `thresholds.properties` file.

### Act by hand, as planned actions

14. Terminate and restart a RegionServer manually via `px-cluster`. Because each operation is
    recorded in the planned-action ledger, any `px-run` invocation active at that time treats the
    state change as intentional:

    ```sh
    px-cluster kill regionserver 1
    px-cluster status regionserver
    px-cluster restart regionserver 1
    px-cluster ready
    ```

    ```text
    role          index  instance_id  state    ready  image_ref  node
    regionserver  0      ...          running  ready    ...
    regionserver  1      ...          exited   unready  ...
    phase      status  elapsed_ms
    drain      skip    5
    stop       skip    11
    start      ok      141
    ready      ok      16608
    rebalance  skip    5
    role          index  ready  elapsed_ms
    ...
    ```

    Running `restart` on an already exited instance starts it directly without performing a drain,
    stop, or rebalance phase. Executing `px-cluster signal regionserver 0 QUIT` delivers a single
    signal in the same manner. Manual interventions do not trigger a recovery gate or record
    `mttr_seconds`; those mechanisms apply exclusively to `--disrupt` entries.

### Run the shipped example

15. Run the `recover` test declared in `functional.suite`. This test executes the functional SQL
    verification script through the psql driver and injects a hard kill of RegionServer 0 when the
    driver enters the `steady@script` phase
    (`disrupt: phase:steady@script,kill,regionserver,0,hard`). The suite session leaves the cluster
    running for the final step:

    ```sh
    px-suite functional --only schema,recover -e PX_THRESHOLD_LOG_ERRORS_MAX=1
    ```

    ```text
    1	schema	SKIP	0	44646	the verdicts of this test reach SKIP	.../runs/...
    2	recover	SKIP	0	64691	the verdicts of this test reach SKIP	.../runs/...
    result	SKIP	0	.../phoenix-harness/sessions/...
    ... INFO  px-suite: session ... ends at SKIP: 0 pass, 2 skip, 0 advisory, 0 fail, 0 error
    (exit 0, ok)
    ```

    Tests driven by psql report an overall verdict of `SKIP` rather than `PASS` for the reason
    detailed in [H02, step 14](02-first-cluster-and-smoke.md): the psql driver does not collect
    per-operation latency metrics.

    Passing `-e PX_THRESHOLD_LOG_ERRORS_MAX=1` allows a single known server `ERROR` log message
    triggered by the functional query script (`ByteUtil.getLargestPossibleRowKeyInRange` with an
    empty end key, documented in A7). Remove this override once the underlying server defect is
    resolved.

### Detect an unplanned restart

Because every lifecycle action executed through the harness is recorded as planned, the harness
itself provides no command to simulate an unplanned crash. To demonstrate anomaly detection, this
step invokes `docker` directly—the only command across the entire HOWTO series that manipulates a
cluster container outside the harness.

16. Start a workload run in the background, wait until its steady-state window opens, kill
    RegionServer 1 directly via Docker, and wait for the background run to exit. The cluster
    remains up with RegionServer 1 terminated until the final `px-cluster down`:

    ```sh
    : >"$HOWTO/unplanned.path"
    px-run pherf -- --scenario "$HOWTO/fault.xml" --schema "$HOWTO/fault.sql" \
      >"$HOWTO/unplanned.path" &
    PXRUN=$!
    until [ -s "$HOWTO/unplanned.path" ]; do sleep 1; done
    RUN_UNPL="$(cat "$HOWTO/unplanned.path")"
    until awk -F '\t' '$2 == "begin" && $3 == "steady@pherf" { f = 1 } END { exit !f }' \
      "$RUN_UNPL/stages.tsv" 2>/dev/null; do sleep 2; done
    sleep 20
    docker kill "$(docker ps -q --filter label=px.harness.cluster=default \
      --filter label=px.harness.role=regionserver --filter label=px.harness.index=1)"
    wait "$PXRUN"
    ```

    ```text
    ... ERROR px-run: ...
    ... INFO  px-run: run ... ends at ERROR (exit 3, anomaly)
    ```

    Because `px-run` writes the bundle path to standard output before invoking `prepare`, the
    initial polling loop waits only for that file to become non-empty. The container labels
    `px.harness.cluster`, `px.harness.role`, and `px.harness.index` uniquely select the target
    RegionServer container. Across the `prepare` and `run` phases, `px-run` samples the cluster
    state every `PX_POLL_INTERVAL_S` and compares it against the pre-run baseline snapshot. Upon
    detecting an unplanned container termination, it aborts the run immediately with exit 3
    regardless of the driver verb's exit status, because measurements gathered from a compromised
    cluster are invalid.

17. Examine the recorded anomaly metadata and resulting verdict rows:

    ```sh
    jq -c '.anomaly | [.aborted, .abort_ms], (.instances[] | [.role, .index, .change,
      .restarts_before, .restarts_after, .termination_reason, .exit_code, .oom])' \
      "$RUN_UNPL/manifest.json"
    awk -F '\t' '$1 == "driver.restore" || $1 == "cluster.restart" { print $1, $2, $5 }' \
      "$RUN_UNPL/report/verdicts.tsv"
    ```

    ```text
    [true,1795202950117]
    ["regionserver",1,"went from running to exited",3,3,"killed",137,false]
    driver.restore SKIP anomaly abort: the cluster cannot be trusted, so restore does not run
    cluster.restart ERROR 1 instances changed with no planned action ...
    ```

    The `cluster.restart` check reports `ERROR`—the severity reserved for infrastructure failures—so
    an unexpected node crash is never misinterpreted as a slow workload. The `oom` boolean
    distinguishes an out-of-memory kill from other termination causes. When a container is restarted
    in place via Docker (`docker restart`), the change description instead reads `restarted 0
    to 1`: the harness also counts each change of the start time. `restore` never executes after an
    anomaly abort because the cluster state can no longer be trusted.

## Read the result

* **Recovery.** An `mttr_seconds` value below 120 paired with `cluster.recovery PASS` and
  `cluster.post_recovery PASS` confirms hypothesis 1. An mttr approaching the timeout bound points
  to a bottleneck in either the restarted server's readiness probe or the restoration of the online
  region count.
* **Client effect.** The ratio of the fault run's response p99 to the baseline response p99
  quantifies the outage from the client's perspective, whereas the service p99 barely changes. A
  saturation verdict noting that "the workers could not keep up", together with an attainment
  verdict below the minimum threshold when the request backlog fails to drain before the window
  closes, indicates that worker threads were stalled behind the cluster fault rather than a slow
  client. Both verdicts report as `ADVISORY` in the validation class.
* **Data.** Every fault run must conclude with `result_rows 20000`. Any lower row count indicates a
  lost write, which constitutes a data-correctness defect regardless of latency or recovery timings.
* **Planned and unplanned.** A planned disruption produces `cluster.restart PASS` and exit 0,
  whereas an unplanned crash produces `cluster.restart ERROR`, populates the `anomaly` manifest
  object, and terminates with exit 3. Exit 3 always denotes an infrastructure anomaly rather than a
  slow workload.

## Reproduce it

* **Inputs.** Preserve `fault.xml` and `fault.sql` in `$PX_OUT/howto/h09/` alongside the disruption
  specifications used in this guide. The fixed seed `9` deterministically controls key generation,
  read order, and Poisson arrival timestamps.
* **Version set and image.** Record the component versions and container image digests from the run
  bundle:

  ```sh
  jq '.versions' "$RUN_KILL/manifest.json"
  awk -F '\t' 'NR > 1 { print $1, $2, $4 }' "$RUN_KILL/cluster/images.tsv"
  ```

* **Bundles.** Package the baseline bundle together with all fault run bundles into a single
  archive:

  ```sh
  tar -C "$PX_OUT/runs" -czf "$HOWTO/bundles.tgz" "${RUN_BASE##*/}" "${RUN_KILL##*/}" \
    "${RUN_ROLL##*/}" "${RUN_GATE##*/}" "${RUN_UNPL##*/}"
  ```

* **Replicates.** A single fault injection yields only one mttr observation. To substantiate a
  claim that a code change reduces recovery time, collect at least 3 replicates for both the
  baseline and candidate builds and compare their distributions as shown in
  [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md).

## Clean up

Shut down the cluster, which remains running after step 16 with RegionServer 1 terminated:

```sh
px-cluster down
```

```text
... INFO  px-cluster: cluster default is down
```

## See also

* [07-read-what-you-wrote.md](07-read-what-you-wrote.md): explains how to verify the correctness of
  writes across a fault.
* [03-result-bundles-and-automation.md](03-result-bundles-and-automation.md): details the exit
  codes and verdict-merging rules consumed by automated CI jobs.
* [10-profiling-and-root-cause.md](10-profiling-and-root-cause.md): demonstrates how to inspect
  what the cluster was doing internally while recovering.
* [../bundle-format.md](../bundle-format.md): specifies the schema of `run-record.tsv`, the
  `anomaly` and `disruptions` manifest fields, and the cluster health checks.
* [../backends.md](../backends.md): documents the planned-action record and the substrate
  implementations for `kill`, `signal`, and `restart`.
* [../../README.md](../../README.md): summarizes the harness exit codes and teardown policy.
* [15-fleet-replicates-ab-and-faults.md](15-fleet-replicates-ab-and-faults.md): covers fault
  injection under fleet load and operation of the recovery gate with the Distributed Pherf tier.

[h15]: 15-fleet-replicates-ab-and-faults.md
