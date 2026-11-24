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

# How to find the maximum sustainable throughput and the saturation knee

## The question

This guide addresses two central capacity-planning questions: what is the maximum offered rate that
the system can sustain while keeping both attainment and tail response latency within their target
bounds, and is that ceiling imposed by the cluster itself or by the load-generating client? When the
workload operates below the saturation knee, the achieved throughput tracks the offered rate and
response latency remains flat. Once the offered load crosses the knee, the dispatch queue fills,
scheduling lag accumulates, response time grows without bound, and achieved throughput plateaus at
the system capacity. To locate this boundary, this HOWTO drives a stepped rate ladder—executing a
dedicated 60 s run at each step—alongside a continuous linear ramp. It then inspects the schedule
metrics for every step, validates the concurrency behavior using Little's law, distinguishes
client-side bottlenecks from cluster-side limits using host and container resource samplers, and
narrows down the exact saturation knee using launch overrides.

## Before you start

**Audience.** This guide is intended for developers who are sizing a code change or investigating a
throughput regression, as well as operators who are planning the capacity of a Phoenix workload.

**Prerequisites.**

- [How to validate a latency SLO with an open loop, and hold it in a soak][h05]: this guide
  covers the service and response latency keys and the attainment check.
- A JDK 11 or later `java` binary installed on the host for step 12, along with the build manifest
  at `$PX_OUT/build/phoenix/build.tsv`.
- A `jq` binary available on the host for step 13, which parses the JSON latency report.

| Cost | Value |
|---|---|
| Wall time | The procedure takes about 30 minutes across 10 runs of about 2 to 3 minutes each. |
| Docker memory | The validation class with 1 RegionServer enforces 8704 MiB of container limits. |
| Disk | Legacy detail reports hold one row for each operation, using a few hundred MiB at most. |
| Files written | The procedure writes 10 files under `$HOWTO` (listed below) and 10 run bundles. |

The generated files under `$HOWTO` are `knee_schema.sql`, `step.xml`, `ramp.xml`, `knee.xml`,
`pool.xml`, `knee.properties`, `pool.properties`, `ladder.tsv`, `ramp`, and `ramp.hgrm`.

Begin every shell session for this guide by running these three setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h06"; mkdir -p "$HOWTO"
```

## Method

**Offered and achieved rate.** An open-loop workload submits requests at a target offered rate that
you select, while the achieved rate reflects how many operations actually complete. Because a
closed-loop workload has no independent offered rate, it cannot locate a saturation knee; instead,
it reports only a single operating point corresponding to the rate that the system permitted. Refer
to the coordinated-omission demonstration in H05 for background.

**The knee.** For the purposes of this HOWTO, the saturation knee is defined as the highest rate
step that achieves at least 90% attainment (the default threshold of
`pherf.verdict.attainment.minPct`) while keeping its response p99 latency within the SLO bound. The
maximum sustainable throughput is the achieved rate measured at that knee step. Any offered rate
above the knee is unsustainable because it continually accumulates an ever-growing backlog for as
long as the step runs.

**Little's law.** In a stable queueing system, the mean number of operations concurrently in flight
equals the arrival rate multiplied by the mean service time: L = λ x W. From those mean values, the
utilization across c workers is given by ρ = λ x W / c. As ρ approaches 1, the dispatch queue grows,
indicating that either the worker pool or the cluster is the limiting bottleneck. Conversely, if ρ
remains well below 1 while the queue saturates or scheduling lag accumulates, the workers are
spending time outside the instrumented service window or the dispatcher itself is falling behind,
which means the client is the limiting bottleneck. A peak in-flight count that exceeds the mean
reflects a transient burst rather than a sustained trend.

**Step length.** Each rate step must run for many times the duration required for the client queue
to drain and must be long enough to encompass a storage flush. An overly brief step only measures
how much transient burst the queue can absorb rather than whether the rate is sustainable. You must
always interpret attainment alongside the step duration: at 90% attainment, a short 2.5 s step can
disguise a 10% overload as a modest 250 ms backlog. To prevent that distortion, every step in this
HOWTO runs for a full 60 s.

**Hypothesis.** Point-lookup throughput scales linearly with the offered rate up to a saturation
knee, beyond which response p99 latency increases by orders of magnitude while service p99 latency
grows much more modestly.

**Controls.** Every run operates against a single table of 10,000 rows populated with a fixed
co-generation seed, driven by 8 workers executing the exact same query and warmup sequence. Because
each rate step executes as an independent run, it produces its own isolated bundle and latency keys
and reloads the table from scratch before measurement begins.

**Noise.** When the client container and the cluster share the same physical host processors, an
intensive client steals CPU cycles from the RegionServer and vice versa. The validation cluster
class attaches the `quiet-host` tag, which downgrades any failed time verdict to ADVISORY. Because a
single ladder represents only one sample of a noisy execution environment, you should always repeat
the candidate knee step, as shown in steps 16 and 17.

**What to measure and why.** At every rate step, you record the scheduled, completed, dropped, and
late operation counts to evaluate attainment; the peak queue depth and peak lag to evaluate
saturation; the service and response p99 latencies to evaluate the tail behavior; and the CPU
utilization of client 0 and each RegionServer to identify the limiting bound.

**What invalidates the result.** A saturation knee caused by client exhaustion characterizes the
load generator rather than Phoenix. Similarly, when a step drops operations, the lag limit truncates
its response latency distribution, meaning the reported p99 does not reflect the true p99 of the
offered workload. Finally, any knee derived from a single run or from a validation-class cluster
serves only as an initial hypothesis for a formal measurement run, never as a definitive capacity
figure.

### LATE and DROP

The scheduling lag of an operation is the elapsed time between its intended schedule timestamp and
the moment a worker thread picks it up for execution.

- `LATE` (the default). A late operation still executes, and it is counted as late whenever
  `maxLagMs` is configured and exceeded. For the operation counts, completed = scheduled. The
  response latency tail remains complete because the accumulated backlog surfaces directly as
  response time.
- `DROP` (which requires `maxLagMs`). A late operation is not executed and is counted as dropped
  instead. For the operation counts, completed + dropped = scheduled. The response latency tail is
  truncated near `maxLagMs` plus the service time.

The `DROP` policy bounds the total execution time of an overloaded step, making it ideal for
sweeping a wide rate ladder. The `LATE` policy exposes the full latency penalty of an overload,
making it suitable for fine-grained knee refinement. Because `DROP` makes completion counts
dependent on runtime scheduling jitter, replicate runs of a co-generated scenario will not execute
identical operation counts, causing offline analysis to report a count-parity FAIL across them.

## Steps

### Offer a ladder and a ramp

1. Start a validation-class cluster.

   ```sh
   px-cluster --class validation up
   ```

2. Create the target table schema file.

   ```sh
   cat >"$HOWTO/knee_schema.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.KNEE (
     K1 BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   )
   EOF
   ```

3. Create the scenario definition for a single ladder step, configured with one flat 60 s stage, 8
    workers, and the `DROP` lag policy with a 500 ms lag limit. The `-targetRate` flag overrides the
    offered rate for each individual step at launch time.

    ```sh
    cat >"$HOWTO/step.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="howto_knee">
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
        <scenario tableName="PX_HOWTO.KNEE" rowCount="10000" name="step">
          <coGeneration seed="6"/>
          <querySet concurrency="8" warmupExecutions="50">
            <openLoop lagPolicy="DROP" maxLagMs="500">
              <stage durationMs="60000" rate="250"/>
            </openLoop>
            <query id="get" statement="SELECT V FROM PX_HOWTO.KNEE WHERE K1 = [K1]"/>
          </querySet>
        </scenario>
      </scenarios>
    </datamodel>
    EOF
    ```

    Each rate step is executed as an independent run rather than as a query set within a single
    multi-step run. Pherf retains an in-memory record of roughly 1.5 KB for every operation until
    the run terminates. A single 60 s step running at 4000 operations per second accumulates 240,000
    operations, consuming about 360 MB of the 1 GiB client heap, so combining five such steps into a
    single run would exhaust the available memory.

4. Derive the ramp scenario from the step definition, preserving the same query set while replacing
    the flat stage with a linear ramp from 500 to 7500 operations per second over 60 s.

    ```sh
    sed -e 's/name="step"/name="ramp"/' \
      -e 's|rate="250"/>|rate="500" endRate="7500"/>|' \
      "$HOWTO/step.xml" >"$HOWTO/ramp.xml"
    grep '<stage' "$HOWTO/ramp.xml"
    ```

    ```text
              <stage durationMs="60000" rate="500" endRate="7500"/>
    ```

    Execute the ramp without any launch rate override, because passing `-targetRate` would rescale
    the peak rate and alter the slope of the ramp.

5. Create a custom `pherf.properties` file that enables 1 s histogram interval exports.

   ```sh
   cp phoenix-pherf/config/pherf.properties "$HOWTO/knee.properties"
   cat >>"$HOWTO/knee.properties" <<'EOF'
   pherf.default.hdr.intervalMs=1000
   EOF
   ```

6. Execute the rate ladder by launching an independent run for each target rate of 250, 500, 1000,
    2000, and 4000 operations per second, collecting host and container resource samples every 2 s.
    Each line appended to `ladder.tsv` records the target rate, the command exit code, and the
    resulting bundle path.

    ```sh
    for rate in 250 500 1000 2000 4000; do
      R="$(px-run pherf --collect host,container -e PX_COLLECT_INTERVAL_S=2 -- \
        --scenario "$HOWTO/step.xml" --schema "$HOWTO/knee_schema.sql" \
        --properties "$HOWTO/knee.properties" -- -targetRate "$rate")"
      echo "$rate $? $R" | tee -a "$HOWTO/ladder.tsv"
    done
    ```

    Because `tee -a` appends to its target file, delete `$HOWTO/ladder.tsv` before re-running the
    rate ladder.

7. Execute the linear ramp scenario using the same collection configuration.

    ```sh
    RUN_RAMP="$(px-run pherf --collect host,container -e PX_COLLECT_INTERVAL_S=2 -- \
      --scenario "$HOWTO/ramp.xml" --schema "$HOWTO/knee_schema.sql" \
      --properties "$HOWTO/knee.properties")"
    ```

8. Define a shell helper function to retrieve an individual metric value from a bundle by name,
    given the bundle path, anchor, and metric key.

    ```sh
    metric() { px-bundle metrics "$1" "$2" "$3" | cut -f 3; }
    ```

### Read each step

9. Inspect the schedule summary metrics for each step in the ladder. Because the schedule anchor
   follows the pattern `schedule@<scenario>.<query set position>`, each individual step bundle
   records its metrics under `schedule@step.1`.

   ```sh
   while read -r rate rc r; do
     a=schedule@step.1
     echo "rate $rate exit $rc scheduled $(metric "$r" "$a" pherf.schedule.scheduled)" \
       "completed $(metric "$r" "$a" pherf.schedule.completed)" \
       "dropped $(metric "$r" "$a" pherf.schedule.dropped)" \
       "queue $(metric "$r" "$a" pherf.schedule.max_queue_depth)" \
       "drift_us $(metric "$r" "$a" pherf.schedule.max_drift_us)"
   done <"$HOWTO/ladder.tsv"
   ```

   ```text
   rate 250 exit 0 scheduled 15000 completed 15000 dropped 0 queue 5 drift_us 25987
   rate 500 exit 0 scheduled 30000 completed 30000 dropped 0 queue 8 drift_us 34905
   rate 1000 exit 0 scheduled 60000 completed 60000 dropped 0 queue 8 drift_us 15766
   rate 2000 exit 0 scheduled 120000 completed 120000 dropped 0 queue 8 drift_us 27360
   rate 4000 exit 0 scheduled 240000 completed 239792 dropped 208 queue 8 drift_us 506456
   ```

   The schedule records also expose `pherf.schedule.late` and `pherf.schedule.aborted`, and the
   accounting identity `scheduled = completed + dropped + aborted` holds across every step. Every
   step up to 2000 operations per second completed all of its scheduled arrivals with a maximum
   drift—the worst-case scheduling lag—below 35 ms. Although the dispatch queue reached its
   capacity bound of 8 starting at 500 operations per second, that peak reflects a transient burst
   rather than a sustained backlog trend (see step 13). At 4000 operations per second, the maximum
   drift climbed to 506 ms, exceeding the 500 ms lag limit and causing 208 of the 240,000
   operations to be dropped. Consequently, the throughput capacity of this client lies between 2000
   and 4000 operations per second, keeping in mind that a single rate ladder represents only one
   sample on a noisy host.

10. Compare the service p99 latency against the response p99 latency across every ladder step and
    the ramp.

    ```sh
    while read -r rate rc r; do
      echo "rate $rate service_p99_us $(metric "$r" op@query_step_get pherf.latency.p99_us)" \
        "response_p99_us $(metric "$r" op@response_step_get pherf.latency.p99_us)"
    done <"$HOWTO/ladder.tsv"
    echo "ramp service_p99_us $(metric "$RUN_RAMP" op@query_ramp_get pherf.latency.p99_us)" \
      "response_p99_us $(metric "$RUN_RAMP" op@response_ramp_get pherf.latency.p99_us)"
    ```

    ```text
    rate 250 service_p99_us 8059 response_p99_us 11743
    rate 500 service_p99_us 2711 response_p99_us 2915
    rate 1000 service_p99_us 2603 response_p99_us 2923
    rate 2000 service_p99_us 3145 response_p99_us 4791
    rate 4000 service_p99_us 3267 response_p99_us 467199
    ramp service_p99_us 1908 response_p99_us 14911
    ```

    Across every run, the service p99 latency remains between 1.9 and 8 ms; because the 250
    operations per second step executes first against a freshly started cluster, its upper tail
    includes initial cold-cache misses. Up to 2000 operations per second, the response p99 latency
    tracks within 2 ms of the service p99 latency, whereas at 4000 operations per second it jumps
    to 467 ms—roughly 140 times the service p99. That additional delay accumulates inside the
    client dispatch queue rather than inside the cluster. Evaluated against the 50 ms SLO threshold
    established in H05, the 2000 operations per second step is the highest compliant rate. Whenever
    an overloaded step drops operations, its response p99 latency settles near the 500 ms lag limit
    because the `DROP` policy truncates the upper tail of the distribution. Finally, the continuous
    ramp reports a response p99 of only 15 ms because it spends roughly one second at each rate
    level (see step 12).

11. Examine the attainment and saturation verdict rows for each step in the ladder as well as the
    ramp run.

    ```sh
    for r in $(awk '{ print $3 }' "$HOWTO/ladder.tsv") "$RUN_RAMP"; do
      echo "== ${r##*/}"
      grep -E 'attainment|saturation' "$r/drivers/pherf/results_HDR/VERDICT_run.tsv" | cut -f 1,2,5
    done
    ```

    ```text
    == 20261124T013630Z-bi1g
    pherf.health.attainment  PASS  lowest attainment 99.9% (scenario step query set 1: 239792
    of 240000 completed, taken in 60000 ms of a 60000 ms window); minimum 90%
    pherf.health.saturation  PASS  peak lag 506.456 ms (scenario step query set 1), within 1000
    ms; queue peak 8 of 8, at its bound; in-flight peak 8 of 8: the workers could not keep up;
    late 0, dropped 208
    ```

    This excerpt shows the 4000 operations per second step, with each tab-separated verdict row
    wrapped here for readability. Every verdict row across the ladder and the ramp reports PASS—even
    for this step, which dropped 208 operations. Because the saturation check evaluates peak
    scheduling lag against `pherf.verdict.lag.maxMs` (1000 ms), configuring the `DROP` policy with
    a lower lag limit (500 ms here) keeps the lag of every executed operation near or below that
    threshold. As a result, the saturation check can pass even while a step is actively dropping
    requests, so you must always inspect the `dropped` count and the schedule metrics alongside the
    verdict. For the same reason, the 99.9% attainment score easily clears its 90% minimum. An
    overly brief step conceals a growing backlog in an analogous way, which is why each step must
    run long enough for a queue buildup to surface. The diagnostic detail column of the saturation
    row identifies the immediate mechanism of overload:

    - When the dispatch queue reaches its capacity bound, the worker threads could not keep pace
      with arrivals.
    - When scheduling lag exceeds the bound while peak in-flight operations equal the configured
      concurrency, all workers were fully occupied; this implicates the cluster only if the measured
      service latency accounts for the busy time, which step 13 verifies.
    - When scheduling lag exceeds the bound even though at least one worker thread remained idle,
      the dispatcher itself fell behind, pointing directly to a client-side bottleneck.

    Because the harness does not track an offered rate for Pherf scenarios directly, the generic
    `steady.attainment` rule evaluates to SKIP, leaving `pherf.health.attainment` as the
    authoritative attainment verdict.

12. Extract the time-series progression of the ramp from the HdrHistogram interval log, where the
    operation count recorded in each 1 s slice of the response latency tag represents the completed
    throughput during that second.

    ```sh
    PHERF_JAR="$(awk -F '\t' '$1 == "pherf_jar" { print $2 }' "$PX_OUT/build/phoenix/build.tsv")"
    java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
      -i "$RUN_RAMP/drivers/pherf/results_HDR/LATENCY_run_intervals.hlog" \
      -tag 'response:ramp:get|steady' -outputValueUnitRatio 1000 -csv -o "$HOWTO/ramp"
    awk -F , 'NR > 3 { printf "%s s  completed/s %s  p90 %s ms  max %s ms\n", $1, $2, $4, $5 }' \
      "$HOWTO/ramp"
    ```

    ```text
    2.005 s  completed/s 210  p90 3.053 ms  max 4.479 ms
    3.005 s  completed/s 601  p90 3.387 ms  max 10.775 ms
    ...
    30.005 s  completed/s 3754  p90 0.984 ms  max 27.007 ms
    ...
    51.011 s  completed/s 6187  p90 1.049 ms  max 9.767 ms
    52.004 s  completed/s 6339  p90 11.175 ms  max 19.647 ms
    53.005 s  completed/s 6339  p90 7.015 ms  max 17.631 ms
    54.005 s  completed/s 6653  p90 19.023 ms  max 28.031 ms
    55.005 s  completed/s 6669  p90 1.066 ms  max 9.591 ms
    ...
    61.005 s  completed/s 7369  p90 3.249 ms  max 14.527 ms
    62.005 s  completed/s 4525  p90 13.959 ms  max 20.143 ms
    ```

    In this interval log, the opening row covers a partial second at startup and the final row
    captures the queue drain after the schedule window closes. The completed throughput tracks the
    rising offered ramp up to roughly 7400 operations per second, while the p90 response latency
    remains near 1 ms up to about 6200 operations per second. Starting at 52 s—corresponding to an
    offered rate of roughly 6300 operations per second—individual one-second slices exhibit p90
    latencies of 7 to 19 ms as the dispatch queue begins to build, marking the saturation knee of
    the ramp.

    Although the ramp knee at roughly 6300 operations per second lies well above the 4000
    operations per second flat step that dropped requests, the two measurements do not contradict
    each other. Because the ramp sweeps through each rate level for only about one second, it
    identifies the instantaneous rate at which queueing first begins to form. By contrast, a flat
    60 s step sustains a constant rate long enough to encounter a transient runtime stall—here a
    506 ms scheduling lag spike—and test whether the client can drain the resulting backlog. Always
    establish your sustainable throughput claim from the flat steps, and use the continuous ramp to
    guide where you place the next step.

### Check the result and find the bound

13. Apply Little's law across the flat ladder steps by multiplying the achieved completion rate by
    the mean service latency to obtain the mean number of operations in flight, and dividing by the
    8 worker threads to compute worker utilization ρ. Compare those mean figures against the peak
    concurrency and queue depths recorded in the JSON latency report.

    ```sh
    while read -r rate rc r; do
      awk -v q="$rate" -v c="$(metric "$r" schedule@step.1 pherf.schedule.completed)" \
        -v w="$(metric "$r" op@query_step_get pherf.latency.mean_us)" \
        'BEGIN { l = c / 60 * w / 1000000
          printf "%s mean in flight %.2f rho %.2f\n", q, l, l / 8 }'
      jq -r '.schedules[] | "maxQueueDepth \(.maxQueueDepth) maxInFlight \(.maxInFlight)"' \
        "$r/drivers/pherf/results_HDR/LATENCY_run.json"
    done <"$HOWTO/ladder.tsv"
    ```

    ```text
    250 mean in flight 0.45 rho 0.06
    maxQueueDepth 5 maxInFlight 8
    500 mean in flight 0.49 rho 0.06
    maxQueueDepth 8 maxInFlight 8
    ...
    4000 mean in flight 3.50 rho 0.44
    maxQueueDepth 8 maxInFlight 8
    ```

    The intermediate steps at 1000 and 2000 operations per second record a mean of 0.81 and 1.58
    operations in flight, corresponding to worker utilizations ρ of 0.10 and 0.20. The mean
    in-flight concurrency rises with the offered rate because the product λ x W increases whenever
    requests arrive faster or individual queries take longer to complete. At the 250 operations per
    second step, the mean in-flight count is only 0.45 while the peak reaches 8, confirming that an
    isolated peak above the mean reflects a short burst rather than steady saturation.

    Applying the Little's law diagnostic rule from the method section to the 4000 operations per
    second step shows a mean of 3.50 operations in flight across 8 workers, yielding a worker
    utilization ρ of about 0.44. Even with that spare service capacity, step 9 showed that the
    dispatch queue reached its capacity bound of 8 and scheduling lag climbed to 506 ms. This
    discrepancy indicates that the worker threads spent substantial time outside the instrumented
    service window inside the client, proving that the client is the limiting bottleneck—a
    conclusion confirmed by the processor utilization measurements in step 14.

14. Compare the CPU consumption of client 0 against that of the RegionServer during the 2000 and
    4000 operations per second steps and the ramp, noting that a `cpu_pct` value of 100 corresponds
    to one full processor core and that each averaging window spans the active schedule interval of
    that run.

    ```sh
    for r in $(awk '$1 >= 2000 { print $3 }' "$HOWTO/ladder.tsv") "$RUN_RAMP"; do
      read -r S E <<<"$(awk -F '\t' '$4 ~ /^schedule@/ { s = $1; e = $2 } END { print s, e }' \
        "$r/metrics/aggregate.tsv")"
      for f in client-0 regionserver-0; do
        awk -F '\t' -v n="${r##*/} $f" -v s="$S" -v e="$E" \
          'NR > 1 && $1 >= s && $1 <= e { t += $4; c++ }
          END { printf "%s cpu mean %.0f%% over %d samples\n", n, t / c, c }' \
          "$r/metrics/container/$f.tsv"
      done
      awk -F '\t' 'NR > 1 && $2 + 0 > m { m = $2 + 0 } END { print "host load peak " m "%" }' \
        "$r/metrics/host.tsv"
    done
    ```

    ```text
    20261124T013447Z-8d1p client-0 cpu mean 179% over 30 samples
    20261124T013447Z-8d1p regionserver-0 cpu mean 43% over 30 samples
    host load peak 55%
    20261124T013630Z-bi1g client-0 cpu mean 272% over 30 samples
    20261124T013630Z-bi1g regionserver-0 cpu mean 82% over 30 samples
    host load peak 84%
    20261124T013818Z-ai1v client-0 cpu mean 264% over 30 samples
    20261124T013818Z-ai1v regionserver-0 cpu mean 77% over 30 samples
    host load peak 72%
    ```

    The three output groups correspond to the 2000 and 4000 operations per second steps followed by
    the continuous ramp.

    Evaluate these utilization figures alongside the saturation cause from step 11 using the
    following criteria:

    - The bottleneck is **the cluster** when all workers are busy or the dispatch queue is full
      with ρ approaching 1, client 0 CPU usage remains moderate, and the RegionServers exhibit high
      CPU utilization or garbage-collection pauses in `logs/gc/`.
    - The bottleneck is **the client** when the dispatch queue saturates with ρ remaining well
      below 1 or the dispatcher falls behind schedule, client 0 consumes near its full share of the
      host, and RegionServer CPU utilization remains moderate.
    - The bottleneck is **the host**—where the client and the cluster contend for shared
      processors—under either saturation cause when both client 0 and the RegionServers show
      moderate utilization while peak host load approaches 100%.

    At 4000 operations per second, client 0 consumed 2.7 processor cores while the RegionServer
    used only 0.8 cores—more than three times less—and host load peaked at 84%. Consequently, the
    saturation knee observed in this ladder reflects a client bottleneck approaching the host
    capacity limit rather than the throughput ceiling of Phoenix.

    Because the containers run without CPU quota limits, each container can schedule threads across
    every processor available to the container engine, and a `host load peak` approaching 100%
    indicates that the physical host itself is saturated. When the client and cluster share a
    single machine, hitting a client-bound or host-bound knee is common on a laptop; running the
    load generator on dedicated hosts eliminates that contention. Distributed Pherf partitions the
    offered arrival rate across multiple agent processes and merges their latency histograms; see
    [How to scale out the load generator and read fleet latency correctly][d04] for distinguishing
    client-bound and cluster-bound knees with a fleet, and [How to run Pherf on a fleet through the
    harness][h14] for deploying the fleet tier. When all containers run on a single host, however,
    the fleet agents still share physical processors with the cluster, meaning that a local fleet
    removes the bottleneck of a single client JVM without removing the capacity limit of the host.

### Refine the knee

15. Create a refinement scenario containing a single flat open-loop stage configured with the `LATE`
    lag policy, which will be scaled dynamically at launch through command-line overrides.

    ```sh
    cat >"$HOWTO/knee.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="howto_knee">
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
        <scenario tableName="PX_HOWTO.KNEE" rowCount="10000" name="knee">
          <coGeneration seed="6"/>
          <querySet concurrency="8" warmupExecutions="50">
            <openLoop lagPolicy="LATE"><stage durationMs="20000" rate="100"/></openLoop>
            <query id="get" statement="SELECT V FROM PX_HOWTO.KNEE WHERE K1 = [K1]"/>
          </querySet>
        </scenario>
      </scenarios>
    </datamodel>
    EOF
    ```

16. Test an intermediate rate of 3000 operations per second—halfway between the last passing step
    and the first failing step—over a 60 s window with 8 workers. Under the `LATE` policy every
    scheduled arrival eventually executes, while `-targetRate` sets the offered peak rate,
    `-runDurationMs` overrides the stage duration, and `-threadCount` sets the worker concurrency.
    The harness driver forwards these override flags exclusively to the `run` verb because Pherf
    rejects them unless `-q` is active.

    ```sh
    knee() {
      R="$(px-run pherf -- --scenario "$HOWTO/knee.xml" --schema "$HOWTO/knee_schema.sql" \
        -- -targetRate 3000 -runDurationMs 60000 -threadCount "$1")"; s=$?
      echo "workers $1 exit $s" \
        "$(cut -f 1,2 "$R/drivers/pherf/results_HDR/VERDICT_run.tsv" | grep attainment)" \
        "response_p99_us $(metric "$R" op@response_knee_get pherf.latency.p99_us)"
      grep -o '<stage[^>]*>' "$R"/drivers/pherf/results/RESULT_*_run.xml
    }
    knee 8
    ```

    ```text
    workers 8 exit 0 pherf.health.attainment	PASS response_p99_us 299263
    <stage durationMs="60000" rate="3000.0"/>
    ```

    The XML result file records the effective stage configuration after applying the command-line
    overrides, confirming a 60 s run at 3000 operations per second.

17. Re-run the exact same 3000 operations per second offered rate while expanding the client pool to
    32 workers.

    ```sh
    knee 32
    ```

    ```text
    workers 32 exit 0 pherf.health.attainment	PASS response_p99_us 115583
    <stage durationMs="60000" rate="3000.0"/>
    ```

    Both runs completed all 180,000 of the 180,000 scheduled operations for 100% attainment, yet
    both violated the 50 ms SLO bound, confirming that 3000 operations per second exceeds the
    saturation knee for this SLO. Increasing the worker pool to 32 threads reduced the response p99
    latency from 299 ms to 116 ms and cut the peak scheduling lag from 384 ms to 176 ms, while the
    service p99 latency doubled from 5.0 ms to 11.2 ms and the service p50 remained flat at
    0.77 ms, demonstrating that higher client concurrency shifts queueing pressure out of the
    client and into the cluster. In general, if 32 workers pass at a rate where 8 workers fail, the
    client worker thread count was the limiting bottleneck rather than the cluster; if both
    configurations fail at the same rate, the cluster is the bottleneck unless the client itself is
    processor-bound. Here both configurations fail, but step 14 showed client 0 consuming nearly
    2.7 processors while the RegionServer used under 1 processor: adding worker threads inside a
    single client JVM cannot relieve a client CPU bottleneck, whereas scaling out to a fleet of
    clients can ([D04][d04]). Always repeat the decisive knee step at least three times and compare
    the replicates using offline analysis; see
    [How to detect a regression and compare builds, configurations and versions][h12].

### Detect an exhausted Pherf pool

18. Closed-loop query sets schedule their worker tasks on the shared Pherf executor sized by
    `pherf.default.threadpool` (which defaults to 10 threads). Request 16 closed-loop worker threads
    against the default pool to observe how the client pool health check detects thread starvation.

    ```sh
    cat >"$HOWTO/pool.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="howto_pool">
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
        <scenario tableName="PX_HOWTO.KNEE" rowCount="10000" name="pool">
          <coGeneration seed="6"/>
          <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="100">
            <query id="get" statement="SELECT V FROM PX_HOWTO.KNEE WHERE K1 = [K1]"/>
          </querySet>
        </scenario>
      </scenarios>
    </datamodel>
    EOF
    RUN_POOL="$(px-run pherf -- --scenario "$HOWTO/pool.xml" \
      --schema "$HOWTO/knee_schema.sql" -- -threadCount 16)"
    grep client_pool "$RUN_POOL/drivers/pherf/results_HDR/VERDICT_run.tsv" | cut -f 2,5
    ```

    ```text
    ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
    FAIL  7 of 17 tasks waited for a thread of the Pherf pool of 10 threads, with a peak demand
    of 17 tasks: the declared concurrency was not reached; set pherf.default.threadpool to at
    least 17
    ```

    The exact task counts are illustrative, and the single tab-separated verdict line is wrapped
    here for readability. Because `client_pool` relies on deterministic `count` evidence rather than
    timing, a violation triggers a hard FAIL on any host environment and causes the run to exit with
    code 1. Open-loop worker threads execute on a dedicated pool outside this shared executor, so an
    open-loop workload will never fail this check.

19. Increase `pherf.default.threadpool` so that it meets or exceeds the peak concurrent task demand
    and re-run the scenario to verify that the check passes.

    ```sh
    cp phoenix-pherf/config/pherf.properties "$HOWTO/pool.properties"
    cat >>"$HOWTO/pool.properties" <<'EOF'
    pherf.default.threadpool=32
    EOF
    RUN_POOL="$(px-run pherf -- --scenario "$HOWTO/pool.xml" \
      --schema "$HOWTO/knee_schema.sql" --properties "$HOWTO/pool.properties" \
      -- -threadCount 16)"
    grep client_pool "$RUN_POOL/drivers/pherf/results_HDR/VERDICT_run.tsv" | cut -f 2,5
    ```

    ```text
    PASS  no task waited: tasks 17, peak demand 17 of 32 threads
    ```

## Read the result

- **The knee** is the highest offered rate step that achieves at least 90% attainment, passes the
  saturation health check, and maintains a response p99 latency within the target SLO bound. In the
  captured ladder, 2000 operations per second is the highest compliant step, while the refinement
  runs in steps 16 and 17 confirm that 3000 operations per second already lies above the knee. The
  raw throughput capacity is the plateau of the completed operation rate—roughly 4000 operations
  per second here, where the run completed all but 208 of 240,000 requests—but operating near that
  ceiling is unsustainable because the response latency tail is dominated by client queueing or
  truncated by the lag limit.
- **The bound** is determined by correlating the saturation verdict detail with container and host
  processor utilization. Whenever the limiting bottleneck is the client or the shared host, it
  reflects a constraint of the test setup and must be reported as such, never as the capacity of
  Phoenix.
- **Validity.** Any failed timing verdict in a validation run is downgraded to ADVISORY, so a
  saturation knee identified here serves strictly as an initial hypothesis to be confirmed by a
  formal measurement run with replicates.

## Reproduce it

- Retain all input artifacts under `$HOWTO`, including the SQL schema, XML scenario definitions, and
  properties files. Co-generation seed 6 guarantees deterministic table data, while UNIFORM arrival
  spacing guarantees deterministic request schedule timestamps.
- Record the command-line overrides supplied to every run, which are also persisted inside the XML
  result files and the `schedules` array of the JSON latency report.
- Inspect `cluster/topology.env`, `cluster/services.tsv`, and `cluster/images.tsv` inside each run
  bundle to verify the component versions, container sizing, and image digests. Because the
  saturation knee depends directly on cluster resource allocation, only compare knees across runs
  that share an identical `services.tsv`.
- Preserve the complete run bundles for later comparison, and run `px-bundle validate "$RUN_RAMP"`
  to verify the integrity of any archived copy.

## Clean up

1. Tear down the validation cluster when testing is complete.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [How to plan capacity across 1, 3 and 5 RegionServers and detect hotspots][h11]: executes the
  same rate ladder across larger RegionServer topologies.
- [How to profile a run and find the root cause](10-profiling-and-root-cause.md): explains how to
  inspect what the cluster was doing internally at the saturation knee.
- [Pherf latency outputs, section "Schedule entries"](../../../../phoenix-pherf/LATENCY.md)
- [The Pherf health verdict](../../../../phoenix-pherf/VERDICT.md): documents the attainment,
  saturation, and client pool health checks.
- [How to drive an open-loop workload with profiles and operation mixes][p03]: details the
  command-line launch overrides for open-loop stages.
- [How to scale out the load generator and read fleet latency correctly][d04]

[d04]: ../../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[h05]: 05-latency-slo-and-soak.md
[h11]: 11-scale-out-capacity-and-skew.md
[h12]: 12-regression-ab-and-matrices.md
[h14]: 14-fleet-through-the-harness.md
[p03]: ../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md
