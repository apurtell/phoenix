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

# How to scale out the load generator and read fleet latency correctly (D04)

## The question

Evaluating a latency or throughput figure produced by a distributed fleet of load generators
requires answering two fundamental architectural questions first. Does the capacity bound reside in
the client tier or in the database cluster? Furthermore, what does the aggregate fleet number
actually represent: is it a single true percentile across all operations, and did every agent apply
its workload simultaneously? This HOWTO addresses both questions through four structured experiments
conducted on a 2-agent fleet:

1. Client bound versus cluster bound: driving the same aggregate target rate from 1 agent and from
   2 agents.
2. Merged histograms versus averaged percentiles: comparing the fleet-wide p99 against the
   arithmetic mean of the individual slot p99s.
3. Coordinated omission across agents: analyzing the response key, the worst-performing schedule,
   and the arrival process seeds.
4. Per-agent skew: evaluating the dispatch start lag of each slot alongside the upper skew bound.

**Laptop caveat.** Within the single-host test harness, all load agents share the underlying host
processors with the database cluster. Consequently, executing this HOWTO on a laptop demonstrates
the diagnostic methodology rather than a true scale-out capacity result, because running a second
agent on the same machine adds a JVM rather than additional processors. A genuine scale-out
deployment places the agents on dedicated hosts (using the standalone deployment model of
[D01][d01] on each host against your target cluster). Review [H06][h06] to locate the saturation
knee of a single client and [H11][h11] to scale the cluster before proceeding.

## Before you start

**Audience.** This guide is written for committers who review performance claims generated with a
fleet and performance engineers who size load generation infrastructure.

**Prerequisites.**

- Completion of [D02][d02], steps 1 through 9, against a cluster that remains running: the agent
  tier is active with 2 agents, the table `PX_HOWTO.D02` holds 6000 rows, and
  `$PX_OUT/howto/d02/plan-run.json` exists.
- A baseline single-client knee rate on this host from [H06][h06]. This HOWTO uses a target rate of
  2000 operations per second with 8 workers, near the knee that H06 identified for 1 RegionServer;
  substitute your own measured value.
- JDK 17 available on `PATH` to run `HistogramLogProcessor`, as in [H05][h05] and [H10][h10], along
  with `curl` and `jq`.

| Cost | Value |
|---|---|
| Wall time | About 15 minutes: 5 runs of 30 to 50 s (about 4 minutes in all) plus the analysis. |
| Docker memory | 12800 MiB of aggregate container limits, matching D02. |
| Disk | A few MiB to store 5 bundle copies in `$HOWTO`. |
| Files written | `$HOWTO/e*.json`, `e*/` (bundles), `e*.cpu`, `slot-*.csv`, and `e23-0-0-ms`. |

**Agent heap.** Each agent container in the tier is provisioned with 1536 MiB of memory, and its
Pherf child process can allocate 35% of that budget as JVM heap. Because Pherf constructs its legacy
detail report in memory at roughly 1 to 1.5 KB per operation, keep the total operations executed by
a single slot (r_i x D / 1000) well under 200,000 in any run.

Initialize the session by configuring the environment variables and shell helper functions:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/d04"; mkdir -p "$HOWTO"
D02="$PX_OUT/howto/d02"
PF=http://127.0.0.1:8780/v1
pf_run() {
  local t r
  t="$(curl -fsS -X POST -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary "@$1" "$PF/tests" | jq -r .id)" || return 1
  r="$(curl -fsS -X POST -d '' "$PF/tests/$t/runs" | jq -r .runId)" || return 1
  while [ "$(curl -fsS "$PF/tests/$t/runs/$r/status" | jq -r .state)" = RUNNING ]; do
    sleep 1
  done
  curl -fsS "$PF/tests/$t/runs/$r/status" >"$HOWTO/$2.status.json"
  curl -fsS "$PF/tests/$t/runs/$r/report" >"$HOWTO/$2.report.json"
  curl -fsS "$PF/tests/$t/runs/$r/bundle" | jq -r '.files[].path' | while read -r p; do
    mkdir -p "$(dirname "$HOWTO/$2/$p")"
    curl -fsS -o "$HOWTO/$2/$p" "$PF/tests/$t/runs/$r/bundle/$p"
  done
  curl -fsS -X POST -d '' "$PF/tests/$t/end" >/dev/null
  echo "$2 $(jq -r .state "$HOWTO/$2.status.json")"
}
cpu() {
  sleep 20
  for i in 1 2 3 4 5 6; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' | grep -E 'pherf-agent|regionserver'
    sleep 3
  done >"$HOWTO/$1.cpu"
}
plan() { jq ".steps[0] |= (del(.weights) + $1)" "$D02/plan-run.json" >"$HOWTO/$2.json"; }
V=drivers/pherf/results_HDR
```

- `pf_run PLAN NAME` executes a single plan as an isolated test, saves `NAME.status.json`,
  `NAME.report.json`, and the diagnostic bundle under `NAME/`, ends the test, and prints the
  terminal run state.
- `cpu NAME` samples the processor utilization of the agents and the RegionServer 6 times, starting
  20 s after invocation, and writes the output to `NAME.cpu`. Note that `docker stats` reports the
  full utilization of one processor core as 100%.
- `plan JSON NAME` writes `NAME.json` by taking the base D02 run plan and applying the step
  configuration fields provided in `JSON`.

## Method

**Hypothesis.** (1) When a single agent operating at 2000 operations per second is the bottleneck,
splitting the load across two agents at 1000 operations per second each achieves higher schedule
attainment while maintaining the same server service time. (2) The arithmetic mean of the per-slot
percentiles does not equal the true fleet percentile, whereas the merged operation count equals the
exact sum of the individual slot counts. (3) With 4 total threads partitioned at weights 1 and 3,
the slot assigned one worker experiences deeper queueing than the slot assigned three workers at the
same arrival rate per worker, and only the response key and the worst-slot schedule diagnostics
expose this difference. Configuring a literal `POISSON` seed causes every slot to generate identical
arrival timestamps. (4) Every start skew bound remains within the 50 ms threshold and is negligible
relative to the measurement window.

**Controls.** All runs share the D02 table (6000 rows, which is never modified in this HOWTO), a
single RegionServer, the base D02 scenario and profile documents, plan seed 8070, and an identical
total thread count across the two runs of each experimental pair. Only a single factor varies within
each pair: the active agent count in experiment 1, or the profile arrival seed in experiment 3.

**Noise.** Because the experiments execute on a single host using a validation cluster where load
agents compete directly with the cluster for processors, every latency figure is graded as ADVISORY.
The diagnostic decisions in this HOWTO interpret the direction of a metric shift and the shape of a
latency distribution rather than producing absolute values for citation. Establishing a rigorous
performance comparison requires a measurement-class environment, multiple replicates, and a formal
A/B statistical analysis ([H15][h15], [H12][h12]).

**What to measure and why.**

- The schedule attainment and saturation verdict rows, because a load generator that fails to
  deliver its target arrival rate renders every latency measurement from that run artificially
  optimistic.
- Both the service key (`query`) and the response key (`response`). Because response time is
  measured from the intended open-loop dispatch timestamp, it captures client-side queueing delay,
  whereas service time measures only active execution duration.
- The processor utilization of each agent and of the RegionServer, which identifies which tier
  reaches hardware saturation.
- The merged histogram for each key alongside the raw interval log of each slot, allowing direct
  verification of the controller's histogram aggregation.
- The step skew bound and the per-slot dispatch start lag, because a workload that starts at
  misaligned times across agents does not apply the concurrent offered rate specified by the plan.

**What invalidates the result.** Refer to [What invalidates a fleet result][invalid] below.

## Steps

### Experiment 1: client bound or cluster bound

1. Generate two test plans configured with the same aggregate rate and the same total thread count:
   one using a single agent, and the other using two agents weighted at 1 and 1.

   ```sh
   plan '{agents: 1, rate: 2000, threads: 8, durationMs: 45000}' e1a
   plan '{agents: 2, weights: [1, 1], rate: 2000, threads: 8, durationMs: 45000}' e1b
   ```

   Both plans read from the row domain `[0, 6000)` that D02 populated using weights 1 and 2. This
   remains valid because the scenario defines no co-generation `clusterLimit`: row offsets are
   allocated contiguously, so any agent count and weight distribution spans the exact same `[0, N)`
   key range.

2. Execute both plans while running the background processor utilization sampler.

   ```sh
   cpu e1a & pf_run "$HOWTO/e1a.json" e1a; wait
   cpu e1b & pf_run "$HOWTO/e1b.json" e1b; wait
   ```

   ```text
   e1a COMPLETE
   e1b COMPLETE
   ```

3. Compare the resulting schedule attainment, saturation causes, steady-state latencies, and
   processor utilization across the two runs.

   ```sh
   for n in e1a e1b; do
     echo "== $n"
     grep -E 'attainment|saturation' "$HOWTO/$n/$V/VERDICT_run.tsv" | cut -f 1,2,5 | cut -c 1-160
     jq -r '.histograms[] | select(.phase == "steady") |
       "\(.operation) p50Us \(.p50Us) p99Us \(.p99Us)"' "$HOWTO/$n.report.json"
     awk '{ sub(/%/, "", $2); t[$1] += $2; c[$1]++ }
       END { for (k in t) printf "%s cpu mean %.0f%%\n", k, t[k] / c[k] }' "$HOWTO/$n.cpu"
   done
   docker info --format 'engine processors {{.NCPU}}'
   ```

   ```text
   == e1a
   pherf.health.attainment	PASS	lowest attainment 100.0% (step 0 slot 0 (agent pherf-agent-0) ...
   pherf.health.saturation	ADVISORY	validation run: FAIL reads as ADVISORY; peak lag 1389.387 ...
   query:howto_d02:lookup p50Us 740 p99Us 5031
   response:howto_d02:lookup p50Us 850 p99Us 1378303
   px-default-regionserver-0-1 cpu mean 41%
   px-default-pherf-agent-0-1 cpu mean 150%
   px-default-pherf-agent-1-1 cpu mean 0%
   == e1b
   pherf.health.attainment	PASS	lowest attainment 100.0% (step 0 slot 0 (agent pherf-agent-0) ...
   pherf.health.saturation	ADVISORY	validation run: FAIL reads as ADVISORY; peak lag 1545.088 ...
   query:howto_d02:lookup p50Us 874 p99Us 5639
   response:howto_d02:lookup p50Us 992 p99Us 1516543
   px-default-regionserver-0-1 cpu mean 48%
   px-default-pherf-agent-0-1 cpu mean 124%
   px-default-pherf-agent-1-1 cpu mean 126%
   engine processors 10
   ```

   The command truncates the verdict lines at 160 columns, indicated by `...` in the excerpt above.
   The complete saturation detail string for e1a ends with
   `queue peak 8 of 8, at its bound; in-flight peak 8 of 8: the workers could not keep
   up; late 0, dropped 0`. Interpret the saturation cause reported in the verdict row detail using
   the classification from [H06][h06]:

   | Saturation cause | Bound |
   |---|---|
   | the dispatcher was late (a lag above the bound with a worker free) | The client. |
   | the queue is full while the service time is short (ρ well below 1) | The client. |
   | the workers were busy and the service time grew | The cluster. |
   | agents and RegionServer moderate, the sum near the engine processors x 100% | The host. |

   **Decision rule.** If schedule attainment recovers when scaling to 2 agents while the service
   time (the `query` p50 and p99) remains unchanged, the single client was the bottleneck—whether in
   its dispatch thread, its worker pool, or its processor allocation. If neither attainment nor
   service time changes, the database cluster or the shared host is the bottleneck, and adding more
   load agents will not increase throughput. If attainment recovers but service time grows, adding
   the second agent shifted queueing pressure from the client into the cluster, indicating that the
   cluster is operating near its capacity bound. On a laptop, hitting the host bound is common:
   always compare the combined processor utilization across all containers against the available
   engine processors before attributing a limit to the client or the cluster.

   Evaluate the worker thread pool using Little's law before interpreting a full dispatch queue as a
   client bottleneck. The throughput capacity of a thread pool equals its thread count divided by
   the mean service time. Here, using the `query` p50 of 0.74 ms as an estimate of the mean, 8
   threads can sustain roughly 8 / 0.74 ms, or about 10,800 operations per second. At a target rate
   of 2000 operations per second, the worker pool is not the bottleneck; instead, the
   `queue peak 8 of 8` diagnostic in the verdict detail stems from the initial cold start analyzed
   in step 7. A thread-pool limit does not change when the total thread count remains fixed at 8
   across 2 agents, whereas a single-process limit (in the dispatcher or per-process processor
   capacity) recedes when a second agent takes over half of the arrival rate.

   **The captured runs.** Both runs achieve 100% attainment across 89312 and 2 x 44448 operations
   respectively, while the service p50 and p99 shift by less than 1 ms. Combined processor
   utilization totals roughly 2 and 3 cores out of the 10 engine processors. Consequently, at 2000
   operations per second, neither the client, the cluster, nor the host is saturated: this machine
   has a saturation knee above 2000 operations per second for this read workload. The saturation
   verdict rows and the elevated response p99 of roughly 1.4 s are not caused by steady-state rate
   overload, but rather by a cold start at the beginning of the measurement window, as demonstrated
   in step 7.

   Even below saturation, these measurements reveal which tier will reach its capacity bound first
   when you divide processor utilization by the operation rate:

   - Client processor time per operation: 1.50 processors / 2000 per second = 0.75 ms.
   - RegionServer processor time per operation: 0.41 / 2000 = roughly 0.2 ms, meaning the client
     consumes about 3.7 times as much processor time as the server for this point lookup.
   - Running a second agent at the same total throughput raises aggregate client processor use from
     1.50 to 2.50 processors, demonstrating that a second JVM introduces roughly 1 processor core of
     fixed runtime overhead in this environment.
   - Because the harness tier sets no container processor limit on an agent, a single agent will
     saturate near its available share of host processors divided by 0.75 ms. Consequently, a fleet
     provisioned with one agent per RegionServer becomes client-bound first for this read workload.

   To drive the system to an actual bound, execute the next experimental pair at 4000 operations per
   second with `durationMs` set to 30000. Under those parameters, a single agent executes 120,000
   operations, staying safely within the heap budget noted in [Before you start][before]. Repeat the
   paired runs at progressively higher rates until single-agent attainment drops below 100%, and
   then apply the decision rule.

### Experiment 2: merged histograms or averaged percentiles

4. Execute a plan configured with unequal slot operation counts: weights 1 and 3, 4 total threads,
   and an aggregate rate of 1200 operations per second.

   ```sh
   plan '{agents: 2, weights: [1, 3], rate: 1200, threads: 4, durationMs: 30000}' e23
   pf_run "$HOWTO/e23.json" e23
   jq -c '.steps[0].slots[] | {slot: .identity.slot, share}' "$HOWTO/e23.status.json"
   ```

   ```text
   e23 COMPLETE
   {"slot":0,"share":{"durationMs":30000,"threads":1,"rate":300,"rows":1500}}
   {"slot":1,"share":{"durationMs":30000,"threads":3,"rate":900,"rows":4500}}
   ```

   Slot 0 runs 1 worker thread at 300 operations per second while slot 1 runs 3 worker threads at
   900 operations per second, maintaining an identical arrival rate per worker across both slots.

5. Compute the p99 latency of the response key for each individual slot from its raw interval log.

   ```sh
   PHERF_JAR="$(awk -F '\t' '$1 == "pherf_jar" { print $2 }' "$PX_OUT/build/phoenix/build.tsv")"
   KEY='response:howto_d02:lookup|steady'
   for s in 0-0 0-1; do
     java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
       -i "$HOWTO/e23/$V/slots/$s.hlog" -tag "$KEY" -outputValueUnitRatio 1 -csv \
       -o "$HOWTO/slot-$s.csv"
     tail -n 1 "$HOWTO/slot-$s.csv" |
       awk -F , -v s="$s" '{ printf "slot %s count %s p99Us %s\n", s, $6, $9 }'
   done
   ```

   ```text
   slot 0-0 count 8976 p99Us 1482751.000
   slot 0-1 count 26922 p99Us 1338367.000
   ```

   The final line of each CSV file contains the cumulative totals across the entire log, where
   column 6 reports the total operation count and column 9 reports the cumulative p99. All values
   are expressed in microseconds (unit ratio 1).

6. Compare the arithmetic mean of the per-slot p99 values against the merged fleet p99, and verify
   the histogram merge.

   ```sh
   jq -r --arg op response:howto_d02:lookup '.histograms[] | select(.operation == $op and
     .phase == "steady") | "merged count \(.count) p50Us \(.p50Us) p99Us \(.p99Us)"' \
     "$HOWTO/e23.report.json"
   jq -r '[.fleet.steps[0].slots[].counts["response:howto_d02:lookup|steady"]] |
     "sum of slot counts \(add)"' "$HOWTO/e23.report.json"
   ```

   ```text
   merged count 35898 p50Us ... p99Us 1440767
   sum of slot counts 35898
   ```

   Because the live capture did not record the merged p50 value, the output excerpt displays `...`
   in its place.

   The merged sample count matches the sum of the per-slot counts in the report as well as the sum
   of column 6 from step 5: 8976 + 26922 = 35898. The arithmetic mean of the two slot p99s is
   (1482751 + 1338367) / 2 = 1410559 us, whereas the merged fleet p99 is 1440767 us. The two values
   differ by only 2% in this run because an initial cold start in both slots dominates both latency
   tails (as shown in step 7), illustrating a special case where the slot tails happen to agree.

   Examining the p50 exposes the mathematical error of averaging percentiles. As measured in step 7,
   the response p50 is 1580 us for slot 0 and 1020 us for slot 1. Their unweighted mean is 1300 us,
   and their count-weighted mean is (8976 x 1580 + 26922 x 1020) / 35898 = 1160 us. Compare both
   averages against the true merged p50 from your own run. The merged p50 corresponds to rank 17949
   within the 35898 combined samples, and because slot 1 contributes 3 of every 4 samples, the fleet
   median sits much closer to slot 1. Weighting by sample count does not fix an average of
   percentiles because a percentile is a rank rather than a mean. For example, if every sample in
   slot 1 fell below every sample in slot 0, the true merged p50 would equal the p67 of slot 1,
   regardless of what the count-weighted mean produces.
   To compute accurate aggregate metrics, the controller merges every retained interval frame from
   every slot into a single unified histogram per key under a uniform recorder configuration (1 us,
   600 s, 3 significant digits). Consequently, the merged histogram is identical to what a single
   recorder would produce had it ingested all samples directly, preserving the exact combined count,
   p50, p99, p99.9, and max.

   Averaging the per-slot p99 values is invalid for three reasons:

   - A percentile represents a rank within a single probability distribution. The arithmetic mean of
     two p99 values does not correspond to a rank in any distribution, and it assigns equal weight
     to a slot of 9,000 operations and a slot of 27,000 operations.
   - The fleet-wide p99 is determined by the slowest 1% of all operations combined. When one slot
     exhibits a worse tail, the vast majority of the slowest 1% of aggregate operations originate
     from that slot, causing the true fleet p99 to sit near a higher percentile of the degraded slot
     rather than halfway between the two slot p99s.
   - The magnitude of the error increases with the divergence between slots. When all slots perform
     uniformly, the mean of their percentiles can appear accurate, masking the methodological error
     until asymmetric slot behavior occurs.

   Always read a fleet percentile exclusively from the merged histogram—either from the fleet report
   or from `LATENCY_run.hlog` in the bundle. To compare agents against one another, inspect each
   individual slot log and compare the slots directly, never comparing an average of slot
   percentiles against the fleet aggregate.

### Experiment 3: coordinated omission across agents

7. Inspect the service and response latency keys for each slot of `e23`.

   ```sh
   for k in query response; do
     for s in 0-0 0-1; do
       java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
         -i "$HOWTO/e23/$V/slots/$s.hlog" -tag "$k:howto_d02:lookup|steady" \
         -outputValueUnitRatio 1 -csv -o "$HOWTO/slot-$k-$s.csv"
       tail -n 1 "$HOWTO/slot-$k-$s.csv" |
         awk -F , -v n="$k $s" '{ printf "%s p50Us %s p99Us %s\n", n, $7, $9 }'
     done
   done
   grep attainment "$HOWTO/e23/$V/VERDICT_run.tsv" | cut -f 2,5 | cut -c 1-120
   ```

   ```text
   query 0-0 p50Us 1185.000 p99Us 4895.000
   query 0-1 p50Us 881.000 p99Us 5695.000
   response 0-0 p50Us 1580.000 p99Us 1482751.000
   response 0-1 p50Us 1020.000 p99Us 1338367.000
   PASS	lowest attainment 100.0% (step 0 slot 1 (agent pherf-agent-1) scenario howto_d02 query ...
   ```

   Evaluate client-side queue wait at the median by subtracting the query p50 from the response p50.
   On slot 0, the median queue wait is roughly 1580 - 1185 = 395 us; on slot 1, it is roughly
   1020 - 881 = 139 us. Although both slots operate at the exact same arrival rate per worker, a
   queue served by a single worker waits longer than a queue served by three workers at the same
   utilization because three workers pool their idle capacity—the classic M/M/1 versus M/M/3
   queueing effect. Because part of the raw response p50 gap reflects underlying service time
   differences (the query p50 is 1185 us versus 881 us), evaluate the difference between response
   and query time rather than the response p50 in isolation. Neither slot's p99 exposes this
   steady-state queueing effect: the service p99 is roughly 5 ms on both slots, while the response
   p99 of roughly 1.4 s is dominated by the cold start examined below.

   **Coordinated omission across agents.** An open-loop schedule dispatches operations according to
   the plan timeline regardless of completion speed, ensuring that queueing delay appears in
   response time rather than being hidden as it would be in a closed loop. Across a distributed
   fleet, three design rules ensure that delayed operations remain represented in the result:

   - Each agent records every operation relative to its own scheduled dispatch timestamp, so the
     response key of each slot captures the full queueing delay of that slot. Use the response key
     to support latency claims, and use the service key to isolate where execution time was spent.
   - The attainment and saturation verdict rows evaluate and report the worst-performing slot,
     ensuring that a starved agent cannot be hidden inside a fleet average.
   - A lost agent or a `DROP` lag policy discards the operations that were delayed the most. Always
     verify that `pherf.fleet.agents` and `pherf.fleet.telemetry` report PASS, and confirm that the
     saturation verdict detail reports `dropped 0`.

   **A cold start in the window.** Locate the exact intervals during which the response time of
   slot 0 spiked by printing the per-second interval operation count (column 2) and interval maximum
   latency in milliseconds (column 5):

   ```sh
   java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
     -i "$HOWTO/e23/$V/slots/0-0.hlog" -tag 'response:howto_d02:lookup|steady' \
     -outputValueUnitRatio 1000 -csv -o "$HOWTO/e23-0-0-ms" >/dev/null 2>&1
   awk -F , 'NR > 3 && NR < 10 { printf "t %s count %s maxMs %s\n", $1, $2, $5 }' \
     "$HOWTO/e23-0-0-ms"
   ```

   ```text
   t 1.000 count 67 maxMs 1366.015
   t 2.000 count 255 maxMs 1511.423
   t 3.000 count 354 maxMs 1488.895
   t 4.000 count 400 maxMs 1277.951
   t 5.000 count 506 maxMs 1059.839
   t 6.000 count 402 maxMs 346.623
   ```

   Although the schedule targets 300 operations per second, the first second completed only 67. The
   Pherf child process was initially slow coming out of the start gate due to an unwarmed JIT
   compiler, cold database connections, and an empty region location cache. The resulting backlog
   drained over roughly 5 s, and those delayed startup operations dictate the response p99 of every
   run in this guide. Because the base D02 scenario specifies no `warmupExecutions`, this cold start
   falls directly inside the measured steady-state window, causing every run in this HOWTO to
   exhibit a peak lag between 1.1 and 1.5 s.

8. Create a profile containing a hardcoded literal seed, and run it against a matched control plan
   that uses per-slot seeds.

   ```sh
   sed 's/seed="\${pherf.slot.seed}"/seed="42"/' "$D02/d02_profile.xml" \
     >"$HOWTO/locked_profile.xml"
   grep -o '<openLoop[^>]*>' "$HOWTO/locked_profile.xml"
   plan '{agents: 2, weights: [1, 1], rate: 400, threads: 4, durationMs: 30000}' e3c
   jq --rawfile p "$HOWTO/locked_profile.xml" '.steps[0].documents["d02_profile.xml"] = $p' \
     "$HOWTO/e3c.json" >"$HOWTO/e3b.json"
   pf_run "$HOWTO/e3b.json" e3b
   pf_run "$HOWTO/e3c.json" e3c
   for n in e3b e3c; do
     jq -c --arg n "$n" '{run: $n, scheduled: [.schedules[].scheduled]}' "$HOWTO/$n.report.json"
   done
   ```

   ```text
   <openLoop arrival="POISSON" seed="42" lagPolicy="LATE">
   e3b COMPLETE
   e3c COMPLETE
   {"run":"e3b","scheduled":[6000,6000]}
   {"run":"e3c","scheduled":[5984,5960]}
   ```

   When configured with a literal seed, every slot draws the exact same sequence of arrival
   timestamps and schedules an identical operation count, causing fleet arrivals to occur in
   phase-locked pairs. The evidence of phase-locking is that both slots schedule an identical count,
   rather than the specific value 6000: 6000 is the theoretical Poisson mean for 200 operations per
   second over 30 s, and seed 42 happens to hit that exact mean by chance. Under a locked seed, the
   fleet offers a Poisson arrival process at half the target rate with every arrival event doubled,
   which is substantially burstier than a true Poisson process operating at the full aggregate rate.
   When the profile uses `${pherf.slot.seed}`, each slot receives its own distinct seed and the
   scheduled counts vary naturally (5984 and 5960).

   The queueing hypothesis is that a phase-locked workload induces deeper queueing at the same mean
   rate, though a single paired execution cannot confirm it statistically. In this capture, the
   locked run e3b produced a response p50 of 1891 us and a p99 of 1116159 us, compared to 1649 us
   and 1079295 us for the unlocked control e3c (+15% at the p50 and +3% at the p99). Those
   differences remain within the background host drift of roughly 6% at the median and 25% in the
   tail that [H15 step 14][h15-aa] measured on this machine for non-alternating sets, and e3b ran
   first while the cold start from step 7 dominated both p99 values. To test the hypothesis
   rigorously, alternate the execution order (e3b e3c e3c e3b) with 3 or more replicates per side
   and compare the merged response histograms using `-analyze` ([H15][h15]). Always specify
   `${pherf.slot.seed}` in every open-loop profile used by a fleet; plans that require identical
   arrival schedules across replicates still achieve full determinism because the controller derives
   each slot seed from the plan seed.

### Experiment 4: per-agent skew

9. Inspect the reported start skew for each run, and recompute the skew bound directly from the slot
   timing records.

   ```sh
   for n in e1a e1b e23 e3b e3c; do
     jq -r --arg n "$n" '.steps[0] |
       [.slots[] | {d: .dispatchMs, e: ((.delayMs / 2 | floor) + 2)}] as $s |
       ($s | map(.d) | max - min) as $spread |
       (if ($s | length) > 1 then ($s | map(.e) | sort | .[-2:] | add) else 0 end) as $err |
       "\($n) spread \($spread) + errors \($err) = \($spread + $err); skewMs \(.skewMs)"' \
       "$HOWTO/$n.status.json"
   done
   ```

   ```text
   e1a spread 0 + errors 0 = 0; skewMs 0
   e1b spread 1 + errors 7 = 8; skewMs 8
   e23 spread 2 + errors 6 = 8; skewMs 8
   e3b spread 0 + errors 8 = 8; skewMs 8
   e3c spread 0 + errors 4 = 4; skewMs 4
   ```

   The controller computes this exact bound: the latest slot dispatch timestamp minus the earliest
   slot dispatch timestamp, plus the two largest per-slot clock error bounds, where each error bound
   equals half the round-trip clock-sync delay of the slot (rounded down) plus 2 ms. A step executed
   by a single slot has zero spread and no second slot, so its skew bound is 0. Because the
   estimated clock offset cancels out of the spread calculation, only the error bounds carry clock
   estimation uncertainty.

10. Inspect the start lag of each individual slot alongside the skew row of the run verdict.

    ```sh
    jq -c '.fleet.steps[0].slots[] | {slot, agent, offsetMs, delayMs, startLagMs}' \
      "$HOWTO/e23.report.json"
    grep start_skew "$HOWTO/e23/$V/VERDICT_run.tsv" | cut -f 2,5
    ```

    ```text
    {"slot":0,"agent":"pherf-agent-0","offsetMs":-1,"delayMs":3,"startLagMs":2}
    {"slot":1,"agent":"pherf-agent-1","offsetMs":-1,"delayMs":3,"startLagMs":0}
    PASS	highest start skew at most 8 ms (step 0), within the 50 ms bound
    ```

    The `startLagMs` field records the dispatch timestamp of the slot minus the earliest dispatch
    timestamp in that step, giving the observed start skew of each agent. The
    `pherf.fleet.start_skew` verdict row follows three grading rules:

    - It grades as FAIL when the skew bound of any step exceeds 50 ms. In a validation run, or in a
      run tagged with `pherf.verdict.noisyHost=true`, this FAIL is downgraded to ADVISORY; it grades
      as a hard FAIL only during a measurement run on a quiet host.
    - It grades as SKIP when a gated step lacks a valid skew calculation (because a slot has no
      clock sample or no recorded dispatch) or when no step in the plan uses a start gate. Because a
      data-load (`-l`) step uses no start gate, running a load step alone produces SKIP ([D02][d02],
      step 9, shows `skewMs` as null).
    - A slot whose clock error bound exceeds 25 ms cannot mathematically prove the 50 ms skew bound.
      Because the error bound equals floor(delay / 2) + 2 ms, this occurs whenever the round-trip
      clock delay reaches 48 ms or higher, causing the controller to log a warning.

    Always evaluate start skew relative to the duration of the measurement window. The runs in this
    guide produced skew bounds of 4 to 8 ms, while the live captures of D7 and H3 produced 6 to
    14 ms. Against a 30 s window, that represents less than 0.05% of the step duration, confirming
    that the slots applied their workloads concurrently. Conversely, a start skew approaching the
    duration of a short step in a step-ladder plan indicates that the step executed with fewer
    active agents than planned for a significant portion of its runtime.

## What invalidates a fleet result

- **Host-bound agents.** When load agents compete with the database cluster or with one another for
  host processors, combined processor utilization approaches the total processor count of the
  container engine, meaning neither the schedule attainment nor the latency distribution reflects
  Phoenix.
- **Skew comparable to the window.** A start skew bound that spans a significant fraction of the
  measurement window or step duration means the fleet failed to apply the planned aggregate rate
  concurrently during that interval.
- **Unequal shares read as equal.** Under weights 1 and 3, slot 1 offers three times the arrival
  rate of slot 0. Any per-agent comparison must normalize metrics by the assigned workload share;
  read `share` directly from the run status.
- **Per-agent percentiles averaged.** Averaging slot percentiles is invalid; only the merged
  histogram provides a true fleet percentile.
- **A validation class or contamination.** Any validation run or run carrying the noisy-host
  contamination tag (`pherf.verdict.noisyHost=true`) produces ADVISORY latency numbers. A valid
  performance claim requires the measurement class on a quiet host, multiple replicates, and a
  formal A/B analysis ([H15][h15]).
- **A cold start inside the window.** The initial seconds immediately after the start gate run on an
  unwarmed client, and the resulting startup backlog dictates the response tail of the entire run
  (step 7). Either configure `warmupExecutions` on the query set or slice the interval log and
  report only the steady-state sub-window alongside its time bounds.
- **A literal arrival seed in a fleet profile.** Sharing a literal seed phase-locks arrivals across
  agents, so the offered arrival process no longer matches the statistical process specified by the
  plan.
- **An agent lost, faulted or with a telemetry gap.** Whenever `pherf.fleet.agents` or
  `pherf.fleet.telemetry` does not report PASS, the merged sample counts fall below the plan and the
  actual offered rate was lower than claimed. Verify that the merged scheduled count equals the sum
  of the slot share rates multiplied by `durationMs` / 1000.
- **Dropped operations.** When using `lagPolicy="DROP"` or `maxLagMs`, a non-zero `dropped` count in
  the saturation verdict detail indicates that the response key omits the operations that were most
  severely delayed. Always evaluate schedule attainment together with the dropped operation count.

## How to reproduce it

Use the D02 table and base plan, the target rates and weights specified for each step, and plan seed
8070. The computed slot shares, slot identities, and scheduled operation counts reproduce
deterministically. Observed latencies and processor utilization depend on the host hardware; a
publishable claim requires measurement-class runs and replicates.

## Clean up

1. Tear down the harness cluster and clean up the output directories. Note that `pf_run` already
   ended each test created in this HOWTO.

   ```sh
   px-cluster down
   rm -rf "$HOWTO" "$D02" "$PX_OUT/howto/d03"
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [H15: replicates, A/B and faults under fleet load][h15]: the statistical measurement methodology
  required for a fleet performance claim.
- [H06: the saturation knee][h06] and [H11: scale-out, capacity and skew][h11].
- [P03: open-loop profiles][p03] (coordinated omission within a single client) and [P06: results,
  verdict and analysis][p06].
- The module README sections on [Start synchronization][start], [Fleet report][report], and
  [Fleet verdict][fverdict], as well as [LATENCY.md][latency] for latency key definitions.

[invalid]: #what-invalidates-a-fleet-result
[before]: #before-you-start
[d01]: 01-standalone-fleet-and-rest-api.md
[d02]: 02-first-fleet-run.md
[h05]: ../../../dev/harness/docs/howto/05-latency-slo-and-soak.md
[h06]: ../../../dev/harness/docs/howto/06-saturation-knee.md
[h10]: ../../../dev/harness/docs/howto/10-profiling-and-root-cause.md
[h11]: ../../../dev/harness/docs/howto/11-scale-out-capacity-and-skew.md
[h12]: ../../../dev/harness/docs/howto/12-regression-ab-and-matrices.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
[h15-aa]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md#part-2-the-ab-suite
[p03]: ../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md
[p06]: ../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md
[latency]: ../../../phoenix-pherf/LATENCY.md
[start]: ../../README.md#start-synchronization
[report]: ../../README.md#fleet-report
[fverdict]: ../../README.md#fleet-verdict
