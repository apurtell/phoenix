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

# How to validate a latency SLO with an open loop, and hold it in a soak

## The question

At a given offered load rate, does a workload's response time remain within its service level
objective (SLO), such as "p99 at most 50 ms and p99.9 at most 200 ms at 50 point lookups per
second"? Moreover, does it continue to meet that objective over several hours rather than just a
single minute? This HOWTO explains why a closed-loop benchmark cannot answer the first question. It
compares the same query under closed-loop and open-loop execution, showing how open-loop response
time grows once the offered rate exceeds system capacity. It then grades an SLO using both the
Pherf tail check and the harness p99 check, inspects tail latency over time from the interval log,
verifies that the warmup period is sufficient, and outlines an extended soak test.

## Before you start

**Audience.** This guide is written for developers and committers who author or review latency
claims, as well as users who plan an SLO for a Phoenix workload.

**Prerequisites.**

- Complete [How to run a Pherf scenario through the harness](04-pherf-through-the-harness.md) so
  that the harness image is built and you understand how the pherf driver stages files.
- Provide a JDK 11 or later `java` runtime on the host for steps 13 and 15; those commands parse a
  local histogram log and do not communicate with the cluster.
- Ensure `$PX_OUT/build/phoenix/build.tsv` is present from building the image, because step 13
  reads the path to the Pherf jar from that file.
- Provide `curl` on the host for step 4 of the soak, which queries the JMX servlet of the
  RegionServer, along with `jq` for step 12 and step 4 of the soak, which parse JSON output.

| Cost | Value |
|---|---|
| Wall time | Allow about 25 minutes for the hands-on steps; a soak runs as long as you choose. |
| Docker memory | The validation class with 1 RegionServer uses 8704 MiB of container limits. |
| Disk | Each bundle uses a few MiB, and a one-hour soak with interval export adds a few MiB more. |
| Files written | `$HOWTO/slo_schema.sql`, `slo_closed.xml`, `slo_open.xml`, `slo_poisson.xml`, `slo.properties`, `slo-response`, `slo-response.hgrm`, `slo-service`, `slo-service.hgrm`, 5 bundles |

Begin every shell session for this guide by running these three setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h05"; mkdir -p "$HOWTO"
```

## Method

**Service time and response time.** An operation's service time begins when a worker thread starts
executing it and ends when its final row arrives. Its response time begins at the timestamp when
the workload schedule intended to send it. The difference between the two is the client-side
queueing delay—the time the operation spent waiting in the client before execution—and an end user
experiences the full response time.

**Coordinated omission.** A closed loop dispatches the next operation only after the preceding one
completes. When the cluster stalls, a closed loop stops sending requests, meaning the operations
that an independent client would have submitted during the stall are never generated. As a result,
the stall produces only a single slow sample instead of penalizing every delayed arrival. A closed
loop also never offers more load than the cluster can accept, making its throughput an output
rather than a controlled input; consequently, closed-loop latency reflects service time at a rate
chosen by the system. Because an SLO specifies response time at an offered rate that you choose,
validating an SLO requires an open loop. A Pherf open loop enqueues each operation at its intended
arrival time and records two metric keys per query, both defined in LATENCY.md:
`query:<scenario>:<id>` for service time and `response:<scenario>:<id>` for response time.

**Hypothesis.** At an offered rate of 50 operations per second, the point lookup maintains a
response p99 of at most 50 ms and a response p99.9 of at most 200 ms.

**Controls.** Each run queries a single 10,000-row table populated by co-generation with a fixed
seed so that every lookup targets a key that exists. All runs use the same query statement, the
same cluster, and the same warmup configuration, first testing UNIFORM arrivals and then testing
POISSON arrivals with a fixed seed. Each run reloads the table at the start and drops it at the
end.

**Noise.** Because the validation class carries the `quiet-host` contamination tag while running on
a desktop host, any failing time-based verdict is downgraded to ADVISORY. Cold-start overhead slows
down the initial executions while connections open, table metadata resolves, and the region
location cache populates. To isolate steady-state latency, each query set first executes 50 warmup
operations per worker; those samples are routed to the `warmup` phase, which no verdict check
grades. Because the JVM JIT compiler takes longer to reach steady state, step 15 verifies whether
the warmup period was long enough.

**What to measure and why.** The procedure measures response p99 and p99.9 at the offered rate,
service p99 for comparison, schedule attainment (whether the cluster kept pace with arrivals), and
tail latency progression over time. Under the offline analysis rule, a quantile q represents a true
percentile only when computed from at least 1 / (1 - q) samples—namely 100 samples for p99 and
1000 samples for p99.9—because with fewer observations the reported value is simply the slowest
sample. Achieving a stable tail estimate requires roughly ten samples beyond the target percentile,
so plan to collect 1,000 samples for a p99 claim and 10,000 samples for a p99.9 claim.

**What invalidates the result.** A run is invalidated if schedule attainment falls below 90%,
because the cluster failed to absorb the offered rate and the run therefore measured an overload
condition instead of the target SLO operating point. Any anomaly (exit 3) likewise invalidates the
run. Finally, an authoritative timing claim cannot be based on a validation-class run or on any
run carrying a contamination tag: its SLO verdict is downgraded to ADVISORY, making it a rehearsal
rather than evidence. To gather authoritative evidence, run the measurement class on a dedicated
quiet host as described in [How to plan a trustworthy measurement](01-measurement-protocol.md).

## Steps

### Prepare the workload

1. Start a validation cluster.

   ```sh
   px-cluster --class validation up
   ```

2. Write the table schema file. Because the pherf driver drops this table during the `restore`
   phase, each run begins from an empty table.

   ```sh
   cat >"$HOWTO/slo_schema.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.SLO (
     K1 BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   )
   EOF
   ```

3. Write the closed-loop scenario configuration, specifying 1 worker thread and 1500 point lookups
   following 50 warmup executions.

   ```sh
   cat >"$HOWTO/slo_closed.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_slo">
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
         <length>32</length>
         <name>V</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.SLO" rowCount="10000" name="slo_closed">
         <coGeneration seed="5"/>
         <querySet concurrency="1" executionType="SERIAL" executionDurationInMs="30000"
                   numberOfExecutions="1500" warmupExecutions="50">
           <query id="get" statement="SELECT V FROM PX_HOWTO.SLO WHERE K1 = [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   When `<coGeneration>` is configured, every column value is derived deterministically from the
   row index and the seed, and point lookups sample only rows that were committed during the load
   phase. See
   [How to generate verifiable data with co-generation](../../../../phoenix-pherf/docs/howto/04-co-generation.md).

4. Write the open-loop scenario configuration for the same table and query, offering 50 operations
   per second for 30 s across 4 workers with UNIFORM arrivals and the LATE lag policy with no lag
   limit so that every scheduled arrival executes and is counted.

   ```sh
   cat >"$HOWTO/slo_open.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_slo">
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
         <length>32</length>
         <name>V</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.SLO" rowCount="10000" name="slo_open">
         <coGeneration seed="5"/>
         <querySet concurrency="4" warmupExecutions="50">
           <openLoop arrival="UNIFORM" lagPolicy="LATE">
             <stage durationMs="30000" rate="50"/>
           </openLoop>
           <query id="get" statement="SELECT V FROM PX_HOWTO.SLO WHERE K1 = [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   In an open-loop query set, `concurrency` determines both the worker thread count and the capacity
   bound of the dispatch queue. Because the stages define the run duration, `numberOfExecutions`
   and `executionDurationInMs` have no effect here.

5. Define a shell helper function to look up an individual metric value from a bundle by name
   using the bundle path, anchor name, and metric key, and declare the shared driver arguments
   used across the open-loop runs.

   ```sh
   metric() { px-bundle metrics "$1" "$2" "$3" | cut -f 3; }
   O=(--scenario "$HOWTO/slo_open.xml" --schema "$HOWTO/slo_schema.sql")
   ```

### Compare the closed loop and the open loop

6. Execute the closed-loop scenario and display its recorded latency metrics by name.

   ```sh
   RUN_C="$(px-run pherf -- --scenario "$HOWTO/slo_closed.xml" --schema "$HOWTO/slo_schema.sql")"
   px-bundle metrics "$RUN_C" op@query_slo_closed_get 'pherf.latency.*_us'
   ```

   ```text
   op@query_slo_closed_get	pherf.latency.min_us	1339	us
   op@query_slo_closed_get	pherf.latency.max_us	5347	us
   op@query_slo_closed_get	pherf.latency.mean_us	2264.906	us
   op@query_slo_closed_get	pherf.latency.p50_us	2137	us
   op@query_slo_closed_get	pherf.latency.p90_us	3035	us
   op@query_slo_closed_get	pherf.latency.p99_us	3925	us
   op@query_slo_closed_get	pherf.latency.p999_us	5327	us
   ```

   These values are illustrative. A closed-loop run produces no `response:` metric key because its
   operations have no scheduled arrival timestamps.

   In Pherf's closed-loop implementation, each query execution acquires a lock on the workload
   executor (`MultiThreadedRunner.call`). Consequently, threads within a single closed-loop query
   set do not execute their queries concurrently, and adding more closed-loop threads does not
   increase concurrency. Use an open loop whenever you need to drive concurrent operations.

7. Estimate the single-worker throughput capacity from Little's law: with a single operation in
   flight, throughput equals the reciprocal of the mean service time.

   ```sh
   X="$(awk -v m="$(metric "$RUN_C" op@query_slo_closed_get pherf.latency.mean_us)" \
     'BEGIN { printf "%d", 1000000 / m }')"
   LOW=$((X / 2)); HIGH=$((X * 3)); echo "capacity $X/s, low $LOW/s, high $HIGH/s"
   ```

   ```text
   capacity 441/s, low 220/s, high 1323/s
   ```

   This capacity estimate is conservative because each closed-loop sample also includes the
   client-side harness overhead surrounding each query execution. On the reference host, the
   open-loop service time was roughly half the closed-loop mean, so offering 120% of `X` did not
   overload the worker and still achieved 100% attainment. For that reason, `HIGH` is set to three
   times `X`; if your high-rate run still reports 100% attainment, increase `HIGH`.

### Overload: the response time grows without bound above capacity

8. Run the open-loop scenario with a single worker at half of the estimated capacity. Both
    `-threadCount` and `-targetRate` are launch overrides that the pherf driver passes only to the
    `run` verb.

    ```sh
    RUN_L="$(px-run pherf -- "${O[@]}" -- -threadCount 1 -targetRate "$LOW")"
    ```

9. Run the open-loop scenario with a single worker at three times the estimated capacity for 10 s
    (`-runDurationMs` is also a launch override), and then print the service and response p50 and
    p99 latencies for both runs.

    ```sh
    RUN_H="$(px-run pherf -- "${O[@]}" -- -threadCount 1 -targetRate "$HIGH" \
      -runDurationMs 10000)"
    for r in "$RUN_L" "$RUN_H"; do
      for k in query response; do
        echo "${r##*/} $k p50 $(metric "$r" "op@${k}_slo_open_get" pherf.latency.p50_us)" \
          "p99 $(metric "$r" "op@${k}_slo_open_get" pherf.latency.p99_us)"
      done
    done
    ```

    ```text
    20261123T025348Z-351w query p50 1870 p99 4175
    20261123T025348Z-351w response p50 2715 p99 4759
    20261123T030845Z-4g11 query p50 1105 p99 3575
    20261123T030845Z-4g11 response p50 2953215 p99 3145727
    ```

    The numeric values are illustrative, whereas the divergence in distribution shape is the primary
    result. Below capacity, response time equals service time plus a brief queueing wait. Above
    capacity, service time does not increase, but each arriving operation waits behind an expanding
    backlog, causing response time to grow for as long as the overload persists. This behavior
    occurs in any open loop driven above capacity.

    The overloaded run uses a 10 s window rather than 30 s because offering three times capacity
    over a window of W seconds leaves a backlog that takes roughly 2W additional seconds to drain.
    In the step 10 output excerpt, draining took only 2.6 s because the capacity estimate from
    step 7 is conservative. A longer window exhibits the same unbounded growth over a longer period.
    Because this run measures an overload condition rather than system capacity, determine the
    sustainable throughput using steps of at least 60 s as shown in
    [How to find the maximum sustainable throughput and the saturation knee](06-saturation-knee.md).

    **Coordinated omission** is a distinct effect that occurs even at an offered rate below capacity
    when the system experiences a brief stall. Because a closed loop stops sending operations during
    a stall, it records only a single slow sample where an open loop records every delayed arrival,
    allowing the closed-loop p99 to hide the stall. This contrast is demonstrated in [H09, "Kill a
    RegionServer under load"](09-fault-injection-and-recovery.md#kill-a-regionserver-under-load):
    in the run with a RegionServer kill, the service p99 was 21 ms while the response p99 reached
    roughly 7 s, compared to 14 ms in the baseline. That service p99 is what a closed loop reports.

10. Inspect the health verdict of the overloaded run to see how it diagnoses the overload.

    ```sh
    grep -E 'attainment|saturation' "$RUN_H/drivers/pherf/results_HDR/VERDICT_run.tsv" |
      cut -f 1,2,5
    ```

    ```text
    pherf.health.attainment  ADVISORY  noisy host (pherf.verdict.noisyHost): FAIL reads as
    ADVISORY; lowest attainment 79.4% (scenario slo_open query set 1: 9060 of 9060 completed,
    taken in 12592 ms of a 10000 ms window); below the minimum 90%
    pherf.health.saturation  ADVISORY  noisy host (pherf.verdict.noisyHost): FAIL reads as
    ADVISORY; peak lag 3147.993 ms (scenario slo_open query set 1), above 1000 ms; queue peak 1 of
    1, at its bound; in-flight peak 1 of 1: the workers could not keep up; late 0, dropped 0
    ```

    Each row is a single line in the TSV file (wrapped here for readability), and the numbers are
    illustrative. Every arrival executed because the lag policy is LATE, but the worker picked up
    the final request 2.6 s after the scheduled window ended. Attainment is therefore calculated as
    the completed fraction multiplied by the scheduled window divided by the total elapsed time. A
    dispatch queue at its capacity bound combined with lag above the threshold indicates that the
    workers could not keep up. Notice that `late` remains 0 because the scenario does not configure
    `maxLagMs`. Because a single-worker query set has a queue bound of 1, the saturation detail for
    the low-rate run also includes the text "at its bound" and "the workers could not keep up", yet
    its peak lag is only 4.6 ms and its row severity is PASS. Always inspect the lag value and the
    verdict severity rather than relying on that phrase alone.

### Grade the SLO

11. Create a `pherf.properties` file containing the SLO bounds for the Pherf tail check and a 5 s
    histogram interval export period, starting from a copy of the default configuration file.

    ```sh
    cp phoenix-pherf/config/pherf.properties "$HOWTO/slo.properties"
    cat >>"$HOWTO/slo.properties" <<'EOF'
    pherf.verdict.tail.p99MaxMs=50
    pherf.verdict.tail.p999MaxMs=200
    pherf.default.hdr.intervalMs=5000
    EOF
    ```

12. Run the open-loop scenario at its configured rate of 50 operations per second across 4 workers
    for 200 s, setting the harness p99 check to the same 50 ms bound.

    ```sh
    RUN_S="$(px-run --keep -c validation -d pherf -e PX_THRESHOLD_STEADY_P99_MS_MAX=50 -- \
      --scenario "$HOWTO/slo_open.xml" --schema "$HOWTO/slo_schema.sql" \
      --properties "$HOWTO/slo.properties" -- -runDurationMs 200000)"; echo "exit $?"
    grep -E 'tail_latency|steady.query_latency' "$RUN_S/report/verdicts.tsv" | cut -f 1,2,5
    grep -E '"(operation|count|p50Us|p90Us|p99Us|p999Us)"' \
      "$RUN_S/drivers/pherf/results_HDR/LATENCY_run.json"
    ```

    ```text
    exit 0
    pherf.health.tail_latency    PASS  all 2 keys within the bounds p99 50 ms, p99.9 200 ms;
    highest response:slo_open:get p99 10.095 ms, p99.9 11.863 ms
    steady.query_latency.p99_ms  PASS  the highest p99 is 10.095 ms in steady@pherf;
    steady.p99_ms.max=50
          "operation": "query:slo_open:get",
          "count": 1500,
          "p50Us": 2535,
          "p90Us": 3471,
          "p99Us": 4723,
          "p999Us": 10559
          "operation": "query:slo_open:get",
          "count": 200,
          ...
          "operation": "response:slo_open:get",
          "count": 1500,
          "p50Us": 5271,
          "p90Us": 8171,
          "p99Us": 10095,
          "p999Us": 11863
    ```

    These values are illustrative. Both checks grade the worst key across the `query`, `scan`,
    `delete`, `response`, and `operation` categories, so the slower response key governs the
    result. The output excerpt above was captured from a 30 s run without the duration override:
    30 s at 50 operations per second yields 1500 samples, which is sufficient for p99 but leaves
    p99.9 dependent on only one or two observations. Passing `-runDurationMs 200000` extends the
    window to 200 s and collects 10,000 samples, providing the sample depth required for a p99.9
    claim. The second `query:` entry with 200 samples represents the `warmup` phase (50 warmup
    executions across each of the 4 workers). The JSON report exposes the same metric keys recorded
    in `metrics/aggregate.tsv` (`op@response_slo_open_get` and `pherf.latency.p999_us`).

    Both latency checks constitute `duration` evidence. On a validation-class run, exceeding a
    threshold produces ADVISORY rather than FAIL: Pherf downgrades its own verdict row because the
    driver sets `pherf.verdict.noisyHost=true` on a contaminated run, and `px-report` downgrades
    the harness row under the same rule. The `written` column of `report/verdicts.tsv` preserves
    the initial severity recorded by each check writer—FAIL for a downgraded harness row, and
    ADVISORY for a Pherf row that Pherf downgraded directly. Only a measurement-class run with no
    contamination tag produces an authoritative FAIL.

13. Inspect tail latency over time from the interval histogram log. The Pherf jar includes the
    HdrHistogram log processor; because raw histogram values are recorded in microseconds, divide by
    1000 to report milliseconds.

    ```sh
    PHERF_JAR="$(awk -F '\t' '$1 == "pherf_jar" { print $2 }' "$PX_OUT/build/phoenix/build.tsv")"
    HLOG="$RUN_S/drivers/pherf/results_HDR/LATENCY_run_intervals.hlog"
    java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor -i "$HLOG" -listtags
    java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor -i "$HLOG" \
      -tag 'response:slo_open:get|steady' -outputValueUnitRatio 1000 -csv -o "$HOWTO/slo-response"
    sed -n '3,6p' "$HOWTO/slo-response"
    ```

    ```text
    Tags found in input file:
    query:slo_open:get|steady
    query:slo_open:get|warmup
    response:slo_open:get|steady
    "Timestamp","Int_Count","Int_50%","Int_90%","Int_Max","Total_Count","Total_50%","Total_90%",...
    5.005,181,6.075,8.887,11.863,181,6.075,8.887,11.639,11.863,11.863,11.863
    10.006,250,5.475,8.671,10.559,431,5.739,8.719,10.703,11.863,11.863,11.863
    ```

    These values are illustrative. Each CSV row represents one export interval, listing its end
    time in seconds from the start of the log, its interval sample count, p50, p90, and maximum,
    followed by the cumulative totals up to that interval. Meanwhile, `slo-response.hgrm` contains
    the full percentile distribution across the entire run. An interval in which the sample count
    drops while p90 and the maximum spike indicates a transient stall; correlate that timestamp
    against `logs/gc/` and the daemon instance logs in the bundle. The per-interval counts for a
    key sum to its total count in the JSON report: in the 30 s run shown in the excerpt, an initial
    interval of 181, five intervals of 250, and a final interval of 69 sum to 1500.

14. Repeat the SLO run using POISSON arrivals at the same mean rate with a fixed seed.

    ```sh
    sed -e 's/name="slo_open"/name="slo_poisson"/' \
      -e 's/arrival="UNIFORM"/arrival="POISSON" seed="8070"/' \
      "$HOWTO/slo_open.xml" >"$HOWTO/slo_poisson.xml"
    RUN_P="$(px-run pherf -e PX_THRESHOLD_STEADY_P99_MS_MAX=50 -- \
      --scenario "$HOWTO/slo_poisson.xml" --schema "$HOWTO/slo_schema.sql" \
      --properties "$HOWTO/slo.properties" -- -runDurationMs 200000)"
    echo "uniform $(metric "$RUN_S" op@response_slo_open_get pherf.latency.p99_us)" \
      "poisson $(metric "$RUN_P" op@response_slo_poisson_get pherf.latency.p99_us)"
    ```

    ```text
    uniform 10095 poisson 13551
    ```

    These illustrative values come from 30 s runs. Because POISSON arrivals cluster in random bursts
    around the same mean rate—just as independent clients do—short transient queues form and can
    elevate the response-time tail at an identical average rate. A UNIFORM arrival schedule
    represents a gentler workload, so always specify the arrival process in every SLO claim. The
    fixed seed makes the arrival timestamps deterministic so that replicates reproduce the exact
    same bursts.

15. Verify that the warmup period was long enough by examining the service time across each interval
    of the steady phase of the SLO run. Inspect the service key rather than the response key, which
    also includes client queueing delay.

    ```sh
    java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor -i "$HLOG" \
      -tag 'query:slo_open:get|steady' -outputValueUnitRatio 1000 -csv -o "$HOWTO/slo-service"
    awk -F , 'NR > 3 { printf "%s s  p50 %s ms  p90 %s ms\n", $1, $3, $4 }' "$HOWTO/slo-service" |
      head -8
    ```

    ```text
    5.005 s  p50 ... ms  p90 ... ms
    10.006 s  p50 ... ms  p90 ... ms
    ...
    ```

    Because the live verification did not capture output for this step, the excerpt illustrates only
    the output format. The warmup duration was sufficient if the p50 and p90 latencies of the
    initial intervals are no higher than those of subsequent intervals. If they trend downward over
    the first N intervals, the JVM JIT compiler or an internal cache was still warming up during the
    measured window. In that case, either raise `warmupExecutions` until the warmup spans at least N
    intervals, or prepend a leading query set with its own distinct query id ahead of the measured
    query set and make no latency claims from that leading set.

## Read the result

- **The SLO holds** when response p99 and p99.9 remain within their bounds, schedule attainment is
  at least 90%, and each target percentile has a sufficient sample count. On a validation-class run,
  a passing result serves as a rehearsal; repeating the same procedure on a measurement-class run
  with no contamination tag provides authoritative evidence, and running three replicates produces a
  distribution as described in
  [How to detect a regression and compare builds, configurations and versions](12-regression-ab-and-matrices.md).
- **The SLO fails** when a latency bound is exceeded while schedule attainment is at least 90%.
  Locate the timestamp of the tail spike in the interval log and consult
  [How to profile a run and find the root cause](10-profiling-and-root-cause.md).
- **The run is invalid** when schedule attainment falls below 90% or the saturation verdict row
  fails, indicating that the run measured an overload condition. Either lower the offered rate or
  find the sustainable capacity using
  [How to find the maximum sustainable throughput and the saturation knee](06-saturation-knee.md).
- **A service time is not an SLO.** Both `query:` keys and all closed-loop latency figures represent
  service times; always report the `response:` keys from an open-loop run when evaluating an SLO.

## Soak and endurance

A soak test sustains the SLO workload over several hours to detect gradual changes over time, such
as memory growth, an upward trend in GC pause durations, compaction or flush stalls, unbounded log
growth, or host thermal throttling. The procedures in this section have been verified against the
harness code and the live runs from the steps above, although the multi-hour soak itself was not
executed live for this HOWTO.

1. Understand what this soak exercises. Because the workload is strictly read-only against a
   10,000-row table, this soak tests the read execution path, the heap and garbage collection
   behavior of each JVM, and daemon log output. It does not exercise data volume growth, memstore
   flush and compaction debt, region splits, or store-file accumulation, because a read-only
   workload triggers none of those mechanisms. To run an endurance test of the storage engine, the
   workload must write continuously throughout the soak at a rate that fills and flushes the
   memstores many times; see the sustained ingest workload in
   [How to measure ingest throughput, check query plans and cost a secondary index](08-ingest-plans-and-index-cost.md).
   Step 4 below monitors store-file and compaction trends in either case.

2. Plan the workload volume and timeout bounds for a single one-hour soak run. Passing the override
   `-runDurationMs 3600000` scales the stage in `slo_open.xml` to one hour, generating 180,000
   operations at 50 operations per second. Because Pherf retains each scheduled operation in memory
   until the run finishes—at roughly 1.5 KB per operation—180,000 operations consume about 270 MB of
   the 1 GiB client heap. Passing `px-run --timeout SECONDS` sets `PX_TIMEOUT_RUN`, the time bound
   shared across `prepare` and `run` that likewise governs the Pherf process executing in client 0.
   Because the default limit is 3600 s, pass `--timeout 4500` to accommodate the one-hour steady
   measurement window together with initial data loading and queue draining; any lifecycle verb
   that exceeds its allotted timeout terminates with exit 4.

3. Run the soak test with interval histogram export enabled alongside the container and host
   resource samplers.

   ```sh
   RUN_SOAK="$(px-run pherf --timeout 4500 --collect container,host \
     -e PX_THRESHOLD_STEADY_P99_MS_MAX=50 -- "${O[@]}" --properties "$HOWTO/slo.properties" \
     -- -runDurationMs 3600000)"
   ```

   The `-runDurationMs` override scales the open-loop stages to the new total duration while
   preserving their relative shape.

4. While the soak is running, poll the RegionServer store-file count and queue lengths every 5
   minutes from a second shell after running the initial environment setup lines there. The
   `px-cluster endpoint` command resolves the host port mapped to the RegionServer HTTP info server,
   which requires `curl` on the host. Stop the polling loop with Ctrl-C once the soak completes.

   ```sh
   port="$(px-cluster endpoint regionserver 0 http | cut -f 2)"
   B='Hadoop:service=HBase,name=RegionServer,sub=Server'
   while sleep 300; do
     echo "$(date +%s) $(curl -s "http://127.0.0.1:$port/jmx?qry=$B" | jq -r '.beans[0] |
       "\(.storeFileCount) \(.compactionQueueLength) \(.flushQueueLength) \(.memStoreSize)"')"
   done | tee "$HOWTO/soak-rs.txt"
   ```

   Each output line records the timestamp in epoch seconds followed by the four JMX values. A
   store-file count or compaction queue length that grows steadily across the soak indicates
   accumulating compaction debt, whereas a flush queue length that remains above 0 for an extended
   period indicates a flush stall. Under this read-only workload, all four metrics remain flat.

5. Check for tail latency drift over the soak. Point `HLOG` to `LATENCY_run_intervals.hlog` inside
   `$RUN_SOAK` and run the second `java` command from step 13. Compare the `Int_90%` and `Int_Max`
   columns from the first 10 minutes against those from the last 10 minutes; a latency increase that
   is not explained by changes in interval operation count indicates drift.

   ```sh
   HLOG="$RUN_SOAK/drivers/pherf/results_HDR/LATENCY_run_intervals.hlog"
   echo "response_p99_us $(metric "$RUN_SOAK" op@response_slo_open_get pherf.latency.p99_us)"
   ```

6. Inspect RegionServer container memory usage over the soak by printing the first and last samples
   from the container sampler, where column 5 holds `mem_bytes`.

   ```sh
   awk -F '\t' 'NR == 2 { print $1, $5 } NR > 1 { last = $1 " " $5 } END { print last }' \
     "$RUN_SOAK/metrics/container/regionserver-0.tsv"
   ```

   Each line prints the sample timestamp in epoch ms and memory usage in bytes. A container whose
   memory grows continuously toward its limit terminates with an out-of-memory kill, reported by
   `cluster.oom` and exit 3. The `oom.peak_pct.max` check (threshold 95) grades peak container
   memory utilization.

7. Inspect the RegionServer GC trend by comparing heap usage before and after the first and last
   young-generation GC pauses. A bundle's GC log records the full lifetime log of the JVM since it
   started.

   ```sh
   grep 'Pause Young' "$RUN_SOAK/logs/gc/regionserver-0.log" | awk '{ print $1, $(NF-1) }' |
     sed -n '1p;$p'
   ```

   ```text
   [2026-11-23T02:52:07.377+0000][1.094s][info][gc 96M->28M(1792M)
   [2026-11-23T03:18:43.874+0000][1597.583s][info][gc 1281M->166M(1792M)
   ```

   This excerpt comes from the final run bundle of this HOWTO, captured 27 minutes after the
   RegionServer started. A post-GC heap size that continues rising throughout the entire soak, even
   after the block cache and memstores have filled, points to a memory leak. Meanwhile, `cluster.gc`
   grades the single longest GC pause of the run against `gc.pause_ms.max` (1000 ms).

8. Review the daemon log check. The `cluster.log` check counts lines logged by cluster daemons
   after the run started, grading them against `log.errors.max` (0) and `log.warnings.max` (100).
   Because the run's `restore` phase drops the workload table, and dropping a table can cause the
   HBase Master to log a known spurious `ERROR` line (see
   [H03](03-result-bundles-and-automation.md), step 3, recorded in 2 of 59 live verification runs),
   use the detail column of `cluster.log` to distinguish that teardown artifact from a genuine
   regression, and inspect every reported line before raising a threshold.

9. Understand the host resource guards. Running `px-cluster up` starts a background health monitor
   that samples container memory and available host memory every 30 s. If free host memory falls
   below 1024 MiB, the monitor writes `breach.tsv` in `$PX_OUT/monitor/<run-id>/`, tears down the
   cluster, and exits with code 3; any active `px-run` copies `breach.tsv` into its result bundle
   and likewise exits with code 3. In addition, each run holds a system sleep inhibitor for its
   entire duration (`caffeinate -i -s` on macOS, or `systemd-inhibit` on Linux).

**A sequence of runs.** You can also execute a soak as a sequence of shorter runs against a single
persistent cluster that remains up, for instance to rotate result bundles over time. Under that
pattern, every run reloads the table and drops it during `restore`, resetting table state on each
iteration while the intervals between runs give cluster daemons time to recover. Moreover, each
table drop can emit the spurious Master error line noted in step 8 of the soak: at an incidence of
2 in 59 runs, a 20-run sequence has roughly a 50% probability of encountering that message at
least once. To retain the table across consecutive runs, define it via `preScenarioDdls` in the
scenario XML and omit `--schema`, following the [H07](07-read-what-you-wrote.md) design, so that
`restore` leaves the table intact.

**Laptop caveats.** On macOS, `caffeinate -s` prevents system sleep only while running on AC power,
so keep a laptop plugged in during a soak. Turn off Docker Desktop resource-saver features and any
OS energy-saving modes that throttle processor frequency, because CPU throttling alters service
times mid-run. Concurrent activity on the host—such as a software build, a video call, or an
indexer—introduces load that the validation class does not detect. A soak executed on a laptop is
still effective for uncovering memory growth, GC trends, and log accumulation, while its timing
verdicts remain ADVISORY.

## Reproduce it

- Preserve `$HOWTO`: the SQL schema, the scenario XML files, and `slo.properties` define the
  complete workload, where co-generation seed 5 fixes the dataset and POISSON seed 8070 fixes the
  arrival timestamps.
- Record the computed `X`, `LOW`, and `HIGH` rates from step 7 alongside the result bundles; the
  launch overrides that executed are also recorded in each bundle's XML result (`<openLoop>` with
  the scaled `<stage>`) and in the `schedules` entry of the JSON latency report.
- Within each bundle, `cluster/topology.env` and `cluster/images.tsv` record the exact component
  version set and container image. Archive the bundles for reference, and run
  `px-bundle validate "$RUN_S"` to verify the integrity of an archived copy.

## Clean up

1. Tear down the cluster.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [Pherf latency outputs](../../../../phoenix-pherf/LATENCY.md): documents the latency metric keys,
  schedule telemetry entries, and interval histogram export.
- [The Pherf health verdict](../../../../phoenix-pherf/VERDICT.md): documents the tail latency,
  schedule attainment, and worker saturation checks along with their thresholds.
- [How to drive an open-loop workload with profiles and operation mixes](../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md)
- [How to read Pherf results, grade a run and compare replicates](../../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md)
- [The result bundle, section "The cluster checks"](../bundle-format.md)
