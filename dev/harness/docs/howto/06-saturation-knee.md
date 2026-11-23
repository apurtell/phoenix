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

- [How to validate a latency SLO with an open loop, and hold it in a soak](05-latency-slo-and-soak.md):
  this guide introduces the service and response latency keys and the attainment check.
- A JDK 11 or later `java` binary installed on the host for step 12, together with the build
  manifest at `$PX_OUT/build/phoenix/build.tsv`.

| Cost | Value |
|---|---|
| Wall time | The procedure takes about 30 minutes across 10 runs of about 2 to 3 minutes each. |
| Docker memory | The validation class with 1 RegionServer sets 8704 MiB of container limits. |
| Disk | Legacy detail reports hold one row per operation, using a few hundred MiB at most. |
| Files written | `$HOWTO/knee_schema.sql`, `step.xml`, `ramp.xml`, `knee.xml`, `pool.xml`, `knee.properties`, `pool.properties`, `ladder.tsv`, `ramp`, `ramp.hgrm`, 10 bundles |

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

| Lag policy | What happens to a late operation | Counts | Response tail |
|---|---|---|---|
| `LATE` (default) | it runs, and counts as late when `maxLagMs` is set and exceeded | completed = scheduled | complete: the backlog shows as response time |
| `DROP` (needs `maxLagMs`) | it does not run, and counts as dropped | completed + dropped = scheduled | truncated near `maxLagMs` plus the service time |

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

7. Execute the linear ramp scenario using the same collection parameters.

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

**The excerpts of steps 9 to 14.** The illustrative output excerpts shown in steps 9 through 14 were
captured from an earlier single-run formulation of this ladder that executed a 2.5 s query set per
step on a build prior to 63c9dd0ec2. Consequently, the operation counts reflect 2.5 s windows rather
than 60 s runs, and the schedule anchors identify individual query set positions within that
combined scenario. The qualitative saturation pattern remains identical, and the next live refresh
of these HOWTOs will recapture the excerpts using full 60 s steps.

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
   schedule@ladder.1 scheduled 625 completed 625 dropped 0 queue 1 drift_us 3575
   schedule@ladder.2 scheduled 1250 completed 1250 dropped 0 queue 2 drift_us 3396
   schedule@ladder.3 scheduled 2500 completed 2500 dropped 0 queue 2 drift_us 2557
   schedule@ladder.4 scheduled 5000 completed 5000 dropped 0 queue 8 drift_us 46776
   schedule@ladder.5 scheduled 10000 completed 10000 dropped 0 queue 8 drift_us 205019
   schedule@ladder.6 scheduled 10000 completed 10000 dropped 0 queue 8 drift_us 246657
   ```

   This excerpt comes from the earlier ladder of 2.5 s steps, where row 6 corresponds to the ramp.
   The schedule records also expose `pherf.schedule.late` and `pherf.schedule.aborted`, and the
   accounting identity `scheduled = completed + dropped + aborted` holds across every step. At
   offered rates of 250, 500, and 1000 operations per second, the workers kept pace easily with a
   peak queue depth of at most 2. Starting at 2000 operations per second, the dispatch queue
   saturated at its ceiling of 8, and the maximum drift—representing the worst-case scheduling
   lag—climbed to 205 ms, showing that the worker threads had become the bottleneck. Because no
   operation waited longer than the 500 ms cutoff, no requests were dropped, placing the raw system
   capacity near 4000 operations per second. By contrast, in a separate trial using 3 s steps, the
   4000 operations per second step completed only 10892 of 12000 operations and dropped 1108,
   illustrating that a single ladder represents only one sample on a noisy host.

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
    r0250 service_p99_us 8695 response_p99_us 10207
    r0500 service_p99_us 8967 response_p99_us 10015
    r1000 service_p99_us 5747 response_p99_us 6379
    r2000 service_p99_us 9511 response_p99_us 47967
    r4000 service_p99_us 5547 response_p99_us 203391
    ramp service_p99_us 4519 response_p99_us 247167
    ```

    In this excerpt from the earlier ladder, where each rate step used a distinct query identifier,
    the service p99 latency remains between 4 and 10 ms across all steps. Meanwhile, the response
    p99 latency increases fivefold at 2000 operations per second and twentyfold at 4000 operations
    per second, proving that the additional delay accumulates inside the client dispatch queue
    rather than inside the cluster. Evaluated against the 50 ms SLO threshold established in H05,
    the 2000 operations per second step is the highest rate that remains compliant. Whenever an
    overloaded step drops operations, its response p99 latency settles near the 500 ms lag limit
    plus the service time because the `DROP` policy truncates the upper tail of the distribution.

11. Examine the attainment and saturation verdict rows for each step in the ladder as well as the
    ramp run.

    ```sh
    for r in $(awk '{ print $3 }' "$HOWTO/ladder.tsv") "$RUN_RAMP"; do
      echo "== ${r##*/}"
      grep -E 'attainment|saturation' "$r/drivers/pherf/results_HDR/VERDICT_run.tsv" | cut -f 1,2,5
    done
    ```

    ```text
    pherf.health.attainment  PASS  lowest attainment 91.0% (scenario ladder query set 6: 10000
    of 10000 completed, taken in 2746 ms of a 2500 ms window); minimum 90%
    pherf.health.saturation  PASS  peak lag 246.657 ms (scenario ladder query set 6), within
    1000 ms; queue peak 8 of 8, at its bound; in-flight peak 8 of 8: the workers could not keep
    up; late 0, dropped 0
    ```

    This excerpt shows the ramp stage from the earlier ladder, with each tab-separated row wrapped
    for readability. Although both health checks report PASS, the ramp is right on the edge of
    failure, requiring 2746 ms to drain a 2500 ms schedule window. This illustrates why overly short
    steps are deceptive: a 91% attainment score passes the threshold while masking a 246 ms backlog
    that would continue to grow over a longer duration. Furthermore, the saturation check evaluates
    peak scheduling lag against `pherf.verdict.lag.maxMs` (which defaults to 1000 ms). When the
    `DROP` policy enforces a tighter lag cutoff such as 500 ms, no surviving operation can ever
    exceed 1000 ms of lag, allowing the saturation verdict to pass even while operations are being
    discarded; you must therefore always inspect the `dropped` count and schedule records alongside
    the verdict. For example, in the earlier 3 s step trial, the attainment check reported
    `ADVISORY; lowest attainment 82.3% (scenario ladder query set 5: 10892 of 12000 completed, ...)`
    while the saturation row still passed despite recording `dropped 1108`. The diagnostic detail
    column of the saturation verdict identifies the immediate mechanism of overload:

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
    15.005 s  completed/s 844  p90 1.910 ms  max 6.759 ms
    16.005 s  completed/s 3622  p90 1.665 ms  max 11.055 ms
    17.005 s  completed/s 4730  p90 218.239 ms  max 230.911 ms
    18.005 s  completed/s 804  p90 247.295 ms  max 247.935 ms
    ```

    This excerpt reflects the 2.5 s ramp from the earlier ladder, where the opening row covers a
    partial second and the final row captures the post-window queue drain. The achieved completion
    rate tracks the rising offered load up to roughly 4700 operations per second, at which point the
    p90 response latency abruptly spikes from 2 ms to 218 ms within the same one-second slice. That
    inflection second marks the saturation knee of the ramp, cross-checking the coarse step ladder
    with much finer rate resolution. Running the full 60 s ramp yields roughly 60 one-second
    intervals, making the exact second of saturation unmistakable.

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
      grep -E '"(maxQueueDepth|maxInFlight)"' "$r/drivers/pherf/results_HDR/LATENCY_run.json"
    done <"$HOWTO/ladder.tsv"
    ```

    ```text
    r0250 mean in flight 0.99
    r0500 mean in flight 1.40
    r1000 mean in flight 1.95
    r2000 mean in flight 4.57
    r4000 mean in flight 5.41
          "maxQueueDepth": 1,
          "maxInFlight": 4,
    ...
    ```

    This excerpt comes from the earlier ladder run, which calculated λ over a 2.5 s window and did
    not print ρ. The mean number of in-flight operations grows with the offered rate because the
    product λ x W increases whenever requests arrive faster or individual queries take longer to
    execute. At the lowest step, the mean in-flight count is roughly 1 while the peak reaches 4,
    demonstrating that an isolated peak above the mean reflects a short burst rather than steady
    saturation.

    Applying the Little's law diagnostic criteria from the method section to the 4000 operations per
    second step reveals a mean of 5.41 operations in flight across 8 workers, corresponding to a
    worker utilization ρ of only about 0.68. Despite that spare service capacity, step 9 showed that
    the dispatch queue was pinned at its maximum depth of 8. This discrepancy proves that the worker
    threads were spending substantial time outside the measured JDBC service window inside client
    bookkeeping, making the client container the true bottleneck—a conclusion confirmed by the CPU
    utilization data in step 14.

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
    client-0 cpu mean 328% over 4 samples
    regionserver-0 cpu mean 98% over 4 samples
    host load peak 114%
    ```

    Interpret these resource metrics by combining the saturation cause from step 11 with the
    following diagnostic matrix:

    | Saturation cause | Client 0 | RegionServers | Bound |
    |---|---|---|---|
    | the workers were busy, or the queue is full, with ρ near 1 | moderate | high, or GC pauses in `logs/gc/` | the cluster |
    | the queue is full with ρ well below 1, or the dispatcher was late | near its share of the host | moderate | the client |
    | either | Moderate | Moderate; host load nears 100% | The host: client and cluster contend |

    In this excerpt from the earlier ladder, which aggregated a single window across its final three
    stages, client 0 consumed more than three full processor cores while the RegionServer used just
    under one core, and peak host load exceeded 100%. Consequently, the saturation knee observed in
    this ladder reflects exhaustion of the client and the shared host rather than the capacity limit
    of Phoenix.

    Because the Docker containers run without CPU quota limits, any container can schedule threads
    across every logical core available to the engine, and a `host load peak` approaching or
    exceeding 100% indicates that the physical host itself is saturated. When the load generator and
    the cluster share a single workstation or laptop, client-bound and host-bound knees are routine;
    isolating the load generator on dedicated machines eliminates this contention, which upcoming
    distributed Pherf support (W2) is designed to provide.

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
    workers 8 exit 0 pherf.health.attainment	PASS response_p99_us 691711
    <stage durationMs="3000" rate="3000.0"/>
    ```

    This excerpt comes from an earlier 3 s refinement trial. Because the XML result file records the
    effective stage configuration after applying command-line overrides, a full 60 s run displays
    `durationMs="60000"`.

17. Re-run the exact same 3000 operations per second offered rate while expanding the client pool to
    32 workers.

    ```sh
    knee 32
    ```

    ```text
    workers 32 exit 0 pherf.health.attainment	PASS response_p99_us 138751
    <stage durationMs="3000" rate="3000.0"/>
    ```

    In this excerpt from the same 3 s refinement trial, both the 8-worker and 32-worker runs
    maintained attainment above the 90% minimum (reaching 95.5% and 100%, respectively), yet both
    violated the 50 ms response p99 SLO bound, confirming that 3000 operations per second lies above
    the saturation knee. Quadrupling the worker count to 32 cut the response p99 latency from 692 ms
    to 139 ms while doubling the service p99 latency from 12 ms to 25 ms, demonstrating that adding
    client concurrency shifts queueing pressure out of the client dispatcher and into the cluster.
    In general, if increasing the worker count from 8 to 32 causes an overloaded rate to pass, the
    client thread count was the limiting bottleneck; if both worker configurations fail at the same
    rate, the cluster or host is the bottleneck. Always repeat the decisive knee step at least three
    times and evaluate the replicates using offline comparative analysis; see
    [How to detect a regression and compare builds, configurations and versions](12-regression-ab-and-matrices.md).

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
  saturation health check, and keeps its response p99 latency within the target SLO bound. In the
  illustrative ladder, 2000 operations per second is the last compliant step, while the refinement
  runs in steps 16 and 17 confirm that 3000 operations per second already exceeds the knee. The raw
  system capacity is the throughput plateau where completed operations level off—roughly 4000
  operations per second in this trial—but operating near that plateau is unsustainable because tail
  response latency is dominated by client queueing or truncated by the lag cutoff.
- **The bound** is identified by correlating the saturation verdict detail with container and host
  CPU utilization. Whenever the limiting factor is the client container or the shared physical host,
  it reflects a constraint of the test environment and must be reported as such rather than as the
  throughput capacity of Phoenix.
- **Validity.** Because the validation cluster class runs on a shared host, any failed timing
  verdict is downgraded to ADVISORY. A saturation knee located during a validation run should be
  treated strictly as a starting hypothesis to be confirmed by a formal measurement-class run with
  replicates.

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

- [How to plan capacity across 1, 3 and 5 RegionServers and detect hotspots](11-scale-out-capacity-and-skew.md):
  this guide executes the same rate ladder across larger RegionServer topologies.
- [How to profile a run and find the root cause](10-profiling-and-root-cause.md), which explains how
  to inspect what the cluster was doing internally at the saturation knee.
- [Pherf latency outputs, section "Schedule entries"](../../../../phoenix-pherf/LATENCY.md), which
  documents the schedule metrics emitted by open-loop runs.
- [The Pherf health verdict](../../../../phoenix-pherf/VERDICT.md), which specifies the attainment,
  saturation, and client pool health rules.
- [How to drive an open-loop workload with profiles and operation mixes](../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md):
  this guide details the available open-loop launch overrides.
