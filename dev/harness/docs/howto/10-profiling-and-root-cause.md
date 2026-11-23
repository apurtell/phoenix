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

# How to profile a run and find the root cause

## The question

A shift in a latency or throughput metric indicates that performance has changed, but it does not
explain the underlying reason. You must determine whether the shift stems from server-side
execution work, a runtime pause, or a host-level stall. This HOWTO enables all six harness
collectors for a single run: async-profiler, Java Flight Recorder, jHiccup, the host and container
samplers, and the Phoenix trace counters. It demonstrates how to inspect each generated artifact
using tools on the host, and then traces a p99 regression to its root cause through a worked
example.

## Before you start

**Audience.** This guide is designed for Phoenix developers who need to explain the performance
impact of a code change, as well as committers who require root-cause evidence to substantiate a
performance claim.

**Prerequisites.**

* The container image from your build is available and a validation cluster starts cleanly, as
  described in [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md).
* You are familiar with operating the pherf driver, as covered in
  [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md).
* JDK 17 is available on the host `PATH` to provide the `jfr` and `java` executables. The container
  image already bundles async-profiler 4.1 and jHiccup 2.0.10, so neither tool needs to be
  installed on the host.
* The build manifest at `$PX_OUT/build/phoenix/build.tsv` is present from your build to identify
  the Pherf jar that bundles the HdrHistogram log reader needed for step 8.

| Cost | Value |
|---|---|
| Wall time | About 15 min total: 3 Pherf runs of 3 min, artifact reads, bring-up, and teardown. |
| Docker memory | 8704 MiB for a validation cluster with 1 RegionServer. |
| Disk | About 5 MiB per bundle with all collectors on (1 RegionServer, 60 s), under 50 MiB total. |
| Files written | `$PX_OUT/howto/h10/` (scenario, schemas, reads), 3 bundles, and 1 collection. |

Initialize every shell session for this guide with the following three environment commands:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h10"; mkdir -p "$HOWTO"
```

## Method

**Hypothesis of the worked example.** A table configured with the default `UPDATE_CACHE_FREQUENCY`
fetches its metadata from `SYSTEM.CATALOG` on every statement execution. Compared against a table
configured with `UPDATE_CACHE_FREQUENCY=60000`, this behavior increases point-lookup latency and
introduces additional metadata processing on the RegionServer hosting `SYSTEM.CATALOG`, while
leaving JVM pause behavior and host utilization unchanged.

**Controls.** The experiment uses a single cluster, a single co-generated table containing 20000
rows, and a single open-loop workload of point lookups driven by a fixed random seed. The baseline
and candidate configurations differ in only one schema attribute. Both runs enable the exact same
set of collectors so that both incur identical instrumentation overhead.

**Noise.** The validation run class produces `ADVISORY` timings, meaning that a single run per side
indicates only the direction of an effect. The profiler captures CPU samples every 7919 us—a prime
interval chosen so that sampling does not synchronize with periodic background tasks. Because a
short measurement window yields a modest sample count, you should evaluate the relative share of
stack frames rather than small differences in raw sample counts.

**What to measure and why.** Evaluate the telemetry in the following sequence, as each stage
eliminates an entire category of potential causes before the next stage narrows the investigation:

1. The symptom: inspect the `query` and `response` latency percentiles in the latency report.
2. Stalls outside the work: compare the jHiccup log of each target JVM against the idle control JVM
   alongside the GC log. A pause that appears simultaneously in both the target and control JVMs
   reflects a host or virtual machine stall rather than Phoenix execution.
3. Resource limits: examine the host load alongside container CPU and memory samples, and compare
   client resource utilization against server utilization.
4. The work: analyze the CPU and allocation profiles captured from the RegionServer.
5. The counters: inspect the Phoenix trace counter deltas, which record the exact number of
   server-side operations executed.

**What invalidates the result.** An analysis is invalidated if any collector entry in
`metrics/index.tsv` does not report `ok` for the inspected target, if a trace counter delta comes
from a RegionServer that restarted mid-run, if the candidate configuration alters more than a
single variable, or if a host stall occurs in the control JVM during only one of the runs.

## Steps

### Bring up the cluster and write the workload

1. Bring up a validation cluster:

   ```sh
   px-cluster --class validation up
   ```

2. Define the two table schemas, which differ only in their `UPDATE_CACHE_FREQUENCY` configuration
   attribute:

   ```sh
   cat >"$HOWTO/walk-a.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.WALK (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000
   EOF
   cat >"$HOWTO/walk-b.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.WALK (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) SALT_BUCKETS=4
   EOF
   ```

3. Write the workload scenario definition to execute co-generated point lookups over 60 s at a
   rate of 100 operations per second:

   ```sh
   cat >"$HOWTO/walk.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_walk">
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
       <scenario tableName="PX_HOWTO.WALK" rowCount="20000" name="howtoWalk">
         <coGeneration seed="10" blockSize="1000"/>
         <querySet concurrency="8" warmupExecutions="5">
           <openLoop arrival="POISSON" seed="10" lagPolicy="LATE">
             <stage durationMs="60000" rate="100"/>
           </openLoop>
           <query id="get" statement="SELECT V FROM PX_HOWTO.WALK WHERE K = [K]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

### Run the baseline with every collector

5. Execute the baseline run. Because a validation run enables no collectors by default, `--collect`
   explicitly specifies all six. The `profile`, `jfr`, and `hiccup` collectors target the service
   roles listed in `PX_COLLECT_TARGETS`, while the periodic samplers record one sample every
   `PX_COLLECT_INTERVAL_S` seconds:

   ```sh
   RUN_A="$(px-run --keep -c validation -d pherf -t 900 \
     --collect profile,jfr,hiccup,host,container,trace \
     -e PX_COLLECT_TARGETS=regionserver,client -e PX_COLLECT_INTERVAL_S=2 -- \
     --scenario "$HOWTO/walk.xml" --schema "$HOWTO/walk-a.sql")"; echo "exit $? $RUN_A"
   ```

   Setting `PX_COLLECTORS=profile,jfr,hiccup,host,container,trace` in the environment has the same
   effect, whereas `--collect` overrides that setting for a single run. The measurement run class
   enables all six collectors by default. `px-run` starts the collectors immediately before
   invoking the `run` verb and stops them immediately afterward, including when the run fails or
   receives an interrupt. Every start and stop operation is bounded by `PX_TIMEOUT_VERB`, and each
   remote collection session automatically self-terminates one verb timeout bound after the `run`
   verb deadline expires.

6. Inspect the collector index and the collector verdicts, where each target row records the
   artifact produced by the corresponding collector:

   ```sh
   cat "$RUN_A/metrics/index.tsv"
   awk -F '\t' '$1 ~ /^collector\./ { print $1, $2, $5 }' "$RUN_A/report/verdicts.tsv"
   ```

   ```text
   profile    client-0        skip      no JVM or no such instance in client-0
   jfr        client-0        skip      no JVM or no such instance in client-0
   hiccup     client-0        skip      no JVM or no such instance in client-0
   trace      start           ok    traces/start.tsv                    160 counters
   trace      stop            ok    traces/stop.tsv                     160 counters
   trace      counters        ok    traces/counters.tsv                 the change of each ...
   hiccup     control         ok    metrics/hiccup/control.hlog         the idle control JVM
   hiccup     regionserver-0  ok    metrics/hiccup/regionserver-0.hlog  the jHiccup log
   host       host            ok    metrics/host.tsv                    34 samples
   container  client-0        ok    metrics/container/client-0.tsv      34 samples
   container  regionserver-0  ok    metrics/container/regionserver-0.tsv  34 samples
   jfr        regionserver-0  ok    jfr/regionserver-0.jfr              the jfr stop
   profile    regionserver-0  ok    profiles/regionserver-0.jfr         the profile stop
   collector.trace PASS 3 artifacts, see metrics/index.tsv
   ...
   collector.profile PASS 1 artifacts, see metrics/index.tsv
   ```

   The columns in the excerpt above are visually aligned for readability, whereas the actual file
   separates fields with a single TAB character. Because Client 0 does not run a persistent daemon
   JVM when collection begins—the Pherf JVM launches only inside the `run` verb—the JVM-level
   collectors skip it, leaving container resource samples as the only telemetry on the client side.
   Each entry records a status of `ok`, `skip`, or `fail`. A collector that records a `fail` row
   yields an `ADVISORY` verdict rather than `FAIL`, ensuring that lost telemetry never causes a run
   to fail. However, the artifact path recorded for any `ok` row becomes a required file in the run
   bundle, meaning that `px-bundle validate` will reject a bundle if that file is missing.

### Read each collector

6. Analyze the CPU and allocation profile. The async-profiler collector records both `cpu` and
   `alloc` events in a single session at a 7919 us sampling interval and writes the output in JFR
   format. Because Docker Desktop restricts access to `perf_events`, async-profiler automatically
   falls back to its `ctimer` sampling engine in that environment, whereas on a native Linux host
   it can use `perf_events`. Verify the sampling engine and interval recorded in the file, list the
   captured event types, and aggregate the most frequent top-of-stack frames across the CPU
   samples:

   ```sh
   jfr print --events jdk.ActiveSetting "$RUN_A/profiles/regionserver-0.jfr" |
     grep -A1 -E 'name = "(engine|interval)"'
   jfr summary "$RUN_A/profiles/regionserver-0.jfr"
   jfr print --events jdk.ExecutionSample "$RUN_A/profiles/regionserver-0.jfr" |
     awk '/stackTrace = \[/ { getline; sub(/^ +/, ""); sub(/ line: .*/, ""); n[$0]++ }
       END { for (f in n) print n[f], f }' | sort -rn | head -10
   ```

   ```text
     name = "engine"
     value = "ctimer"
   --
     name = "interval"
     value = "7919000"
   ...
    Event Type                          Count  Size (bytes)
   =========================================================
    jdk.ExecutionSample                  1690         24854
    jdk.ObjectAllocationInNewTLAB         462          8888
   ...
   120 ./usr/lib/aarch64-linux-gnu/libc.so.6()
   27 libjvm.so.PhaseChaitin::elide_copy()
   ...
   ```

   The sample counts shown above are illustrative, and the sampling interval is reported in
   nanoseconds. Because the RegionServer was largely idle during this baseline run, the dominant
   frames belong to the standard C library and the JVM JIT compiler. Refer to the event types
   listed by `jfr summary`: async-profiler records heap allocation samples under
   `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB`. Running the same `awk`
   aggregation over those event types tallies the top allocation sites. Passing `--stack-depth 64`
   prints deeper call stacks, which is required when searching for a specific frame anywhere within
   the call stack, as demonstrated in step 15.

7. Examine the JFR recording. Because the `jfr` collector manages recording sessions through `jcmd`
   with the `profile` configuration, the resulting file records JVM garbage collection, lock
   contention, I/O activity, and thread scheduling events:

   ```sh
   jfr summary "$RUN_A/jfr/regionserver-0.jfr" |
     grep -E 'GarbageCollection|JavaMonitorEnter|ThreadPark|SocketRead'
   jfr print --events jdk.GarbageCollection "$RUN_A/jfr/regionserver-0.jfr" |
     grep -E 'name|cause|longestPause' | tail -6
   ```

   ```text
   jdk.GarbageCollection {
     ...
     name = "G1New"
     cause = "G1 Evacuation Pause"
     longestPause = 42.1 ms
   ```

   A long-duration `jdk.JavaMonitorEnter` or `jdk.ThreadPark` event identifies a thread that spent
   significant time blocked on a lock—a wait state that CPU execution sampling does not reveal.

8. Separate internal JVM pauses from host-level stalls with jHiccup. The harness attaches jHiccup
   as an agent inside each target JVM alongside a dedicated, idle control JVM in tooling 0. A pause
   that registers in both logs indicates a stall in the underlying host or container virtual
   machine, whereas a pause isolated to the target log indicates an internal stall within that
   specific JVM, such as a garbage collection pause or another safepoint operation. Decode both
   logs using the HdrHistogram log processor packaged inside the Pherf jar:

   ```sh
   PHERF_JAR="$(awk -F '\t' '$1 == "pherf_jar" { print $2 }' \
     "$PX_OUT/build/phoenix/build.tsv")"
   for h in regionserver-0 control; do
     java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
       -i "$RUN_A/metrics/hiccup/$h.hlog" -o "$HOWTO/hiccup-a-$h" >/dev/null
     printf '%s ' "$h"; grep '#\[Max' "$HOWTO/hiccup-a-$h.hgrm"
   done
   ```

   ```text
   regionserver-0 #[Max     =        42.47, Total count    =        58987]
   control #[Max     =         8.45, Total count    =        57346]
   ```

   The reported numbers are illustrative. Because jHiccup records delays in nanoseconds and the log
   processor divides values by its default scaling factor of 1000000, the output figures are
   expressed in milliseconds. The output file `hiccup-a-<h>` contains one CSV-formatted summary
   line per reporting interval, while `hiccup-a-<h>.hgrm` contains the aggregate percentile
   distribution across the entire log.

9. Review the garbage collection log. During every run, whether or not telemetry collectors are
   active, the `cluster.gc` check archives the unified GC log from each JVM instance under
   `logs/gc/` and compares the longest observed pause against the `gc.pause_ms.max` limit (default
   1000):

   ```sh
   grep 'Pause' "$RUN_A/logs/gc/regionserver-0.log" | tail -3
   awk -F '\t' '$1 == "cluster.gc" { print $2, $3, $5 }' "$RUN_A/report/verdicts.tsv"
   ```

   ```text
   [...][info][gc       ] GC(8) Pause Cleanup 41M->41M(1792M) 0.273ms
   [...][info][safepoint] Safepoint "G1PauseCleanup", Time since last: 1944459 ns, ...
   [...][info][gc       ] GC(9) Pause Young (Normal) (G1 Evacuation Pause) 1152M->188M(1792M)
   42.113ms
   PASS duration the longest pause is 42.113 ms in regionserver/0; ...; gc.pause_ms.max=1000
   ```

   The maximum pause reported in the GC log (42 ms) aligns with the peak hiccup measured in step 8,
   verifying that the delay stemmed from RegionServer garbage collection rather than a host stall.

   Because `cluster.gc` is categorized under the `duration` check class, `px-report` downgrades any
   `FAIL` verdict on this check to `ADVISORY` unless the workload ran as an uncontaminated
   measurement execution.

10. Examine the host and container utilization samples. Each TSV line records an individual sampling
    pass, and the final two columns record `run_class` and `contamination` so that every sample
    permanently retains its classification context:

    ```sh
    head -2 "$RUN_A/metrics/host.tsv"
    awk -F '\t' 'NR > 1 && $2 > m { m = $2 } END { print "host load_pct max", m }' \
      "$RUN_A/metrics/host.tsv"
    for f in "$RUN_A"/metrics/container/*.tsv; do
      awk -F '\t' -v f="${f##*/}" 'NR > 1 { if ($4 > c) c = $4; if ($5 > m) m = $5; l = $6 }
        END { printf "%s cpu_pct max %s, mem %d of %d MiB\n", f, c, m / 1048576,
          l / 1048576 }' "$f"
    done
    ```

    ```text
    ts_ms	load_pct	mem_available_bytes	mem_total_bytes	run_class	contamination
    1795407332225	45	8136736768	34359738368	validation	quiet-host
    host load_pct max 45
    client-0.tsv cpu_pct max 316.47, mem 826 of 1536 MiB
    regionserver-0.tsv cpu_pct max 71.95, mem 1589 of 2560 MiB
    ```

    The shown values are illustrative. The container columns are `ts_ms role index cpu_pct mem_bytes
    mem_limit_bytes rx_bytes tx_bytes blk_read_bytes blk_write_bytes`. `cpu_pct` reports the
    percentage of one processor core, as reported by `docker stats`, so any value above 100
    indicates that the container is using more than one core. A client whose CPU utilization
    remains high while the RegionServer still has spare capacity identifies the client as the
    bottleneck; [06-saturation-knee.md](06-saturation-knee.md) uses this comparison.

11. Review the Phoenix trace counters. Before and after the `run` verb executes, the `trace`
    collector polls every RegionServer via client 0 to capture all integer `*Count` and `*_num_ops`
    attributes published by the `Server`, `PhoenixIndexer`, `GlobalIndexChecker`,
    `PhoenixMetadata`, and `MetadataCaching` JMX beans:

    ```sh
    awk -F '\t' 'NR > 1 && $7 != 0 { print $1 "-" $2, $3, $4, $7 }' \
      "$RUN_A/traces/counters.tsv" | sort -k4 -rn | head -8
    ```

    ```text
    regionserver-0 Server totalRequestCount 6046
    regionserver-0 Server rpcScanRequestCount 6017
    ...
    regionserver-0 PhoenixMetadata metadataCacheHitCount 24
    ```

    A counter delta is valid only if the RegionServer maintained the same JVM process across the
    entire run, because a restarted JVM resets all of its counters to 0. Verify that the `diff` row
    for the instance in `run-record.tsv` is recorded as `unchanged`.

### Walk from a regression to its cause

12. Run the candidate workload, changing only the schema definition file relative to the baseline:

    ```sh
    RUN_B="$(px-run pherf --collect profile,jfr,hiccup,host,container,trace \
      --targets regionserver,client -e PX_COLLECT_INTERVAL_S=2 -- \
      --scenario "$HOWTO/walk.xml" --schema "$HOWTO/walk-b.sql")"
    ```

13. Evaluate the latency shift by extracting the p50 and p99 percentiles for both service time and
    response time:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      awk -F '\t' -v r="${r##*/}" '$4 ~ /^op@(query|response)_howtoWalk_get$/ &&
        $5 ~ /^pherf\.latency\.p(50|99)_us$/ { print r, $4, $5, $7 }' "$r/metrics/aggregate.tsv"
    done
    ```

    ```text
    20261123T041445Z-gd1j op@query_howtoWalk_get pherf.latency.p50_us 1367
    20261123T041445Z-gd1j op@query_howtoWalk_get pherf.latency.p99_us 4591
    20261123T041445Z-gd1j op@response_howtoWalk_get pherf.latency.p50_us 1800
    20261123T041445Z-gd1j op@response_howtoWalk_get pherf.latency.p99_us 8351
    20261123T041706Z-1i1r op@query_howtoWalk_get pherf.latency.p50_us 1561
    20261123T041706Z-1i1r op@query_howtoWalk_get pherf.latency.p99_us 4751
    20261123T041706Z-1i1r op@response_howtoWalk_get pherf.latency.p50_us 1954
    20261123T041706Z-1i1r op@response_howtoWalk_get pherf.latency.p99_us 8303
    ```

    The numbers shown are illustrative. An increase in both p50 and p99 indicates additional work
    performed on every operation, whereas an increase confined to p99 points to an intermittent
    stall or queueing delay. In this example, the service-time p50 increased by 14% while the p99
    values remained flat, reflecting a small per-operation overhead that is masked at the tail of
    the distribution.

14. Rule out runtime stalls as the root cause. Read the upper-tail hiccup percentiles for both the
    RegionServer and the control JVM across both runs from their `.hgrm` distributions, and check
    the GC verdict for the candidate run:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      for h in regionserver-0 control; do
        java -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
          -i "$r/metrics/hiccup/$h.hlog" -o "$HOWTO/hiccup-${r##*/}-$h" >/dev/null
        awk -v n="${r##*/} $h" '$2 + 0 >= 0.99 && a == "" { a = $1 }
          $2 + 0 >= 0.999 && b == "" { b = $1 }
          END { printf "%s p99 %s ms p99.9 %s ms\n", n, a, b }' "$HOWTO/hiccup-${r##*/}-$h.hgrm"
      done
    done
    awk -F '\t' '$1 == "cluster.gc" { print $5 }' "$RUN_B/report/verdicts.tsv"
    ```

    ```text
    20261123T041445Z-gd1j regionserver-0 p99 ... ms p99.9 ... ms
    20261123T041445Z-gd1j control p99 ... ms p99.9 ... ms
    20261123T041706Z-1i1r regionserver-0 p99 ... ms p99.9 ... ms
    20261123T041706Z-1i1r control p99 ... ms p99.9 ... ms
    the longest pause is 68.254 ms in regionserver/0; ...
    ```

    The output excerpt illustrates the expected format, as the live verification run recorded only
    the maximum values. Always evaluate the p99 and p99.9 percentiles rather than the absolute
    maximum, because a single isolated pause dictates the maximum and represents only one sample. A
    stall accounts for a regression only when two conditions are met. First, the target JVM's
    hiccup tail must exceed that of the control JVM; a tail elevation shared by the control JVM
    originates from the host. Second, the stall must align with the percentile range where latency
    rose. Because the degradation identified in step 13 appears at p50 rather than in the tail,
    infrequent stalls cannot explain it.

15. Pinpoint the server-side execution path driving the shift. Tally the CPU execution samples whose
    call stacks contain a metadata endpoint frame across both runs:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      jfr print --stack-depth 64 --events jdk.ExecutionSample "$r/profiles/regionserver-0.jfr" |
        awk -v r="${r##*/}" '/^jdk.ExecutionSample/ { n++; hit = 0 }
          /MetaDataEndpointImpl/ && !hit { m++; hit = 1 }
          END { printf "%s %d of %d samples (%.1f%%)\n", r, m, n, n ? 100 * m / n : 0 }'
    done
    ```

    ```text
    20261123T041445Z-gd1j 0 of 1690 samples (0.0%)
    20261123T041706Z-1i1r 18 of 1121 samples (1.6%)
    ```

    The figures shown are illustrative. A stack frame whose sample share increases alongside the
    latency regression pinpoints the responsible code path. When investigating an unknown cause,
    contrast the top-frame rankings from step 6 between the baseline and candidate runs to determine
    which frames gained relative share.

16. Cross-check the profiling evidence against the trace counters and table metadata. Normalize
    each counter delta by the total operations executed in that run so that runs of different
    durations can be compared directly:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      ops="$(awk -F '\t' '$4 == "steady@pherf" && $5 == "pherf.operations" { print $7 }' \
        "$r/metrics/aggregate.tsv")"
      awk -F '\t' -v r="${r##*/}" -v ops="$ops" 'NR > 1 && $3 == "Server" &&
        $4 ~ /RequestCount$/ { printf "%s %s %.2f per op\n", r, $4, $7 / ops }' \
        "$r/traces/counters.tsv"
      tail -n +2 "$r/tables.tsv"
    done
    ```

    ```text
    20261123T041445Z-gd1j totalRequestCount 1.01 per op
    ...
    20261123T041445Z-gd1j rpcScanRequestCount 1.01 per op
    ...
    PX_HOWTO	WALK	60000
    20261123T041706Z-1i1r totalRequestCount 1.80 per op
    ...
    20261123T041706Z-1i1r rpcScanRequestCount 0.90 per op
    ...
    PX_HOWTO	WALK	0
    ```

    The values shown are illustrative. The `tables.tsv` artifact records the
    `UPDATE_CACHE_FREQUENCY` setting that the `prepare` phase queried back from `SYSTEM.CATALOG`,
    confirming that the default value of `0` versus `60000` is the sole configuration difference
    between the two runs. In the candidate run, 0.9 of the 1.80 server requests per operation were
    not table scans, representing the extra metadata lookup requests. This increase in requests per
    operation aligns with the elevated profiler frame share and confirms the hypothesis that each
    candidate lookup also queries table metadata.

    Overall, the candidate issues approximately 0.8 additional requests per operation, incurring an
    extra network and RPC round trip on most lookups. That additional round trip accounts for the
    p50 latency shift measured in step 13. The RegionServer CPU sample proportion in step 15 (18 of
    1121 samples) serves as corroborating evidence that identifies the server code path, even
    though that modest CPU percentage alone is too small to account for the latency increase by
    itself.

17. Confirm the observation statistically before asserting a formal performance conclusion. One
    run per configuration on a validation cluster establishes only a directional trend. Execute at
    least 3 replicates for each side under the measurement run class and compare the distributions
    using `-analyze`, as detailed in
    [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md).

### Collect by hand

You can also invoke `px-collect` directly outside of px-run. Running `px-collect start` without a
bundle argument initializes a collection directory at `$PX_OUT/collect/<run-id>`, outputs its path,
and logs the active collectors, targets, and processes in `collect/started.tsv`; `px-collect stop`
then terminates all entries recorded in that file.

18. Initiate a manual collection session covering the JVM profilers and the trace counter snapshot,
    capturing the new collection directory path printed by `px-collect start`:

    ```sh
    MAN="$(px-collect start --collect profile,jfr,trace --targets regionserver --duration 300)"
    ```

    The `--duration` flag places an upper time bound on each remote recording session so that an
    unterminated session still exits cleanly. Standard output from asprof and jcmd is redirected to
    `logs/collect.log` inside the collection directory instead of being emitted to the terminal;
    only the `Profiling started` line emitted by asprof to standard error appears on the terminal.
    Naming the directory after a unique run id ensures that its remote staging paths never conflict
    with any other collection session.

19. Launch the host, container, and jHiccup periodic samplers and execute a workload. This second
    start operation attaches to the active collection session and writes its samples into `$MAN`,
    tagging each emitted row with the run class read by `px-collect` from the cluster record:

    ```sh
    px-collect start --collect host,container,hiccup --targets regionserver,client \
      -e PX_COLLECT_INTERVAL_S=2
    px-run pherf -- --scenario "$HOWTO/walk.xml" --schema "$HOWTO/walk-a.sql" >/dev/null
    ```

    Each sampler records one final measurement upon stopping, ensuring that valid telemetry is
    retained even if the collection session terminates early.

20. Terminate the manual collection session. Running `px-collect stop` halts all processes listed
    in `collect/started.tsv` and outputs the resulting collector index entries:

    ```sh
    px-collect stop
    ```

    ```text
    ...
    jfr      regionserver-0  ok  jfr/regionserver-0.jfr       the jfr stop
    profile  regionserver-0  ok  profiles/regionserver-0.jfr  the profile stop
    result   ok              0   .../phoenix-harness/collect/20261127T093512Z-dw1v
    ```

    Because `stop` retrieves the active collectors, targets, and process identifiers for each start
    invocation from `collect/started.tsv`, you do not need to repeat the start options. The
    artifacts generated under `$MAN` can be analyzed with the commands in steps 6 through 11.
    `px-collect` returns exit code 6 if the target cluster or the collection specified by BUNDLE
    does not exist, and exits with code 2 when given an unrecognized collector name.

## Read the result

* **The order matters.** A pause recorded in the control JVM accounts for a tail latency spike
  before any Phoenix code path can be blamed. A difference in profiler stacks explains a regression
  only after you verify that runtime stalls and resource utilization samples are consistent across
  both runs.
* **Profiles.** Always compare relative frame shares rather than raw sample counts across runs of
  differing durations. A stack frame whose share increases alongside the latency regression is a
  candidate root cause that must then be confirmed against trace counters and configuration
  records.
* **Counters.** Counter deltas normalized per operation can be compared directly across runs,
  whereas any counter delta from a RegionServer that restarted during the run is invalid.
* **Severity.** Collector verdicts are at worst `ADVISORY`. Because `cluster.gc` is classified as a
  `duration` check, its failures are automatically demoted on validation or contaminated runs.
  Diagnostic telemetry explains a regression, but it never makes a timing measurement
  authoritative; refer to [01-measurement-protocol.md](01-measurement-protocol.md) for the
  requirements of an authoritative measurement.

## Reproduce it

* **Inputs.** The scenario and schema files `walk.xml`, `walk-a.sql`, and `walk-b.sql` under
  `$PX_OUT/howto/h10/` specify the workload parameters, and the fixed seed `10` governs key
  generation, read order, and request arrival times.
* **Version set and image.** Every run bundle records the software versions and container digests
  in `manifest.json` (under `versions` and `images`) as well as in `cluster/images.tsv`. The
  async-profiler and jHiccup versions correspond to those packaged inside the container image.
* **Bundles.** Archive both run bundles to preserve all captured profiles and recordings:

  ```sh
  tar -C "$PX_OUT/runs" -czf "$HOWTO/bundles.tgz" "${RUN_A##*/}" "${RUN_B##*/}"
  ```

## Clean up

Shut down the validation cluster deployment:

```sh
px-cluster down
```

```text
... INFO  px-cluster: cluster default is down
```

## See also

* [08-ingest-plans-and-index-cost.md](08-ingest-plans-and-index-cost.md): explains how to analyze
  the trace counters associated with the index write path.
* [05-latency-slo-and-soak.md](05-latency-slo-and-soak.md): covers service time versus response
  time and how to evaluate tail latency over a long soak run.
* [06-saturation-knee.md](06-saturation-knee.md): demonstrates how to distinguish a client-side
  bottleneck from a cluster-side bottleneck.
* [../bundle-format.md](../bundle-format.md), section "The collectors": documents every collector
  artifact path, the collector index schema, and collection timeout bounds.
* [LATENCY.md](../../../../phoenix-pherf/LATENCY.md): defines the metric keys reported in Pherf
  latency summaries.
