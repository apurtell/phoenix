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

# How to read Pherf results, grade a run and compare replicates

## The question

When evaluating performance, you must determine whether a benchmark run actually measured what you
intended to measure, and whether an observed difference between two builds or two configuration
settings reflects a genuine change. A single run provides only one sample from an inherently noisy
process, and its latency numbers can fluctuate from run to run on the exact same build by more than
the magnitude of the effect you are investigating. This HOWTO explains how to interpret the outputs
of an individual Pherf run, how Pherf evaluates run health, and how the offline analysis aggregates
three or more replicate runs into medians, confidence intervals, a count parity verification, and a
regression verdict.

## Before you start

**Audience.** This guide is designed for committers who review performance claims and developers
who prepare them.

**Prerequisites.**

* You need the completed build and the `$PX_OUT/howto/pherf-env.sh` environment file created in
  step 3 of [P01](01-first-pherf-run.md), along with JDK 17 configured in `JAVA_HOME`.
* Part 1 requires either a Phoenix cluster reachable from your host, as described in
  [P03](03-open-loop-profiles-and-mixes.md), or a local harness cluster from
  [H02](../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md) configured using option B of
  P03 step 2. You can skip part 1 if you already have the `RESULTS` and `RESULTS_HDR` directories
  from completed runs, or if you already have harness run bundles.
* Part 2 requires no cluster and no network connection because it executes Pherf directly on the
  local host.
* Review [P03](03-open-loop-profiles-and-mixes.md) to understand the open-loop execution model and
  the reference scenario.
* Install `jq` to inspect and query the JSON analysis reports.

**Cost.**

* Wall time: Part 1 takes approximately 10 min to execute 9 runs; part 2 takes approximately 5 min.
* Docker memory: Required for part 1 only, reserving 8704 MiB of container memory limits for a
  harness validation cluster with 1 RegionServer; part 2 requires none.
* Disk: The workflow consumes under 50 MB of disk space.
* Files written: `$HOWTO` stores the scenario definition, `conf/`, the replicate sets under
  `sets/`, and the bundle sets under `bsets/`, while `$HOWTO/work` stores the generated `RESULTS/`
  and `RESULTS_HDR/` directories.

## Method

**Hypothesis.** Applying a candidate configuration setting alters the latency or throughput of the
reference workload by more than 10%, and that shift exceeds the natural run-to-run variation.

**Controls.** Every run uses a single scenario, a fixed random seed, the same cluster, the same
Pherf build, and an identical arrival curve. Each replicate reloads the dataset (`-l`) so that
every run begins from the exact same table state. The sole variable differentiating the two
replicate sets is the candidate setting under evaluation.

**Noise.**

* Run-to-run variation arises from JIT compilation, garbage collection, HBase compaction, and
  region placement, which replicate runs quantify.
* Concurrent activity on the client host or the cluster hosts introduces external interference; on
  a laptop, timing verdicts are therefore graded as ADVISORY.
* Coordinated omission in a closed-loop benchmark occurs when a slow operation delays subsequent
  requests and obscures queueing delay; the open-loop `response` latency keys capture this queueing
  time, whereas closed-loop keys do not.

**What to measure and why.**

* You measure the latency percentiles of each `steady` key in microseconds from HDR histograms,
  because percentiles preserve the tail latency behavior that arithmetic averages obscure.
* You inspect the `schedules` execution counts to verify that the load generator actually offered
  the declared workload.
* You evaluate the five health checks in the run verdict to establish whether the run is valid as
  performance evidence.
* Across replicates, you compute the median and a bootstrap confidence interval for each metric,
  the difference between the set medians, and the exact equality of operation counts.

**What invalidates the result.**

* Providing fewer than 3 replicates in a set prevents authoritative failure grading, causing any
  detected regression to be reported as ADVISORY rather than FAIL.
* Mixing replicates from different run classes invalidates the comparison, causing the analysis
  tool to reject the inputs with exit 66.
* A count parity FAIL within a replicate set indicates that the replicates did not execute identical
  workloads, meaning their latency timings cannot be meaningfully compared.
* A verdict row reporting FAIL for `attainment` or `saturation` indicates that the client failed to
  deliver the declared load, meaning the resulting latency measurements represent a different
  workload.
* Running on a contended host compromises timing isolation, rendering the timing evidence
  non-authoritative.

## Steps

### Set up

1. Execute the environment initialization commands from the root of the Phoenix repository, and
   then change into the working directory:

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p06"; mkdir -p "$HOWTO/work" "$HOWTO/conf"
   . "$PX_OUT/howto/pherf-env.sh"
   cd "$HOWTO/work"
   ```

2. Locate the Pherf JAR archive, which also packages the HdrHistogram utility classes required in
   step 12:

   ```sh
   PHERF_JAR="${PHERF_CP%%:*}"; ls "$PHERF_JAR"
   ```

   The `pherf-env.sh` script places the Pherf JAR at the beginning of `PHERF_CP`. Because the
   offline `-validate` and `-analyze` modes of the `pherf` shell function do not open database
   connections, they execute directly on the local host without requiring the `-z` option.

### Part 1: make replicate runs

You can skip this section if you already have completed run outputs. Each replicate executes a full
`-l -q` pass of the relational reference scenario from [P03](03-open-loop-profiles-and-mixes.md),
which generates 1,000 co-generated rows and drives 100 arrivals at 50 operations per second across
one worker using a 70/20/10 operation mix.

3. Select the target execution environment for Pherf as shown in step 2 of
   [P03](03-open-loop-profiles-and-mixes.md): either set `ZK` to point to an external cluster
   (option A), or define both `ZK` and the client 0 `pherf` wrapper function for a harness cluster
   (option B). Next, copy the reference scenario and its smoke profile into your workspace:

   ```sh
   cp "$PHX_ROOT"/phoenix-pherf/config/reference/relational_{scenario,smoke_profile}.xml "$HOWTO/"
   ```

4. Enable periodic interval histogram export at 500 ms intervals. Because Pherf loads a single
   `pherf.properties` file from its class path, you must copy the full baseline configuration file
   so that all default property keys remain present:

   ```sh
   cp "$PHX_ROOT/phoenix-pherf/config/pherf.properties" "$HOWTO/conf/"
   echo 'pherf.default.hdr.intervalMs=500' >>"$HOWTO/conf/pherf.properties"
   export PHERF_CONF="$HOWTO/conf"
   ```

   Exporting `PHERF_CONF` prepends `$HOWTO/conf` to the class path under both execution options.

5. Understand the candidate configuration flag under test: `-connectionCache`. When this flag is
   enabled, each query reuses a cached JDBC connection, meaning individual query latency samples no
   longer include connection establishment overhead. Every run must reload all 1,000 rows because
   the workload deletes 500 rows during execution.

6. Execute three interleaved rounds of replicate runs. Running one baseline replicate followed
   immediately by one candidate replicate in each round distributes any gradual host performance
   drift evenly across both replicate sets:

   ```sh
   for i in 1 2 3; do
     pherf -z "$ZK" -l -q -disableSchemaApply -label "base$i" \
       -scenarioFile "$HOWTO/relational_scenario.xml"
     pherf -z "$ZK" -l -q -disableSchemaApply -label "cand$i" \
       -scenarioFile "$HOWTO/relational_scenario.xml" -connectionCache
   done
   ```

7. Execute one non-identical replicate by extending the run duration of the same scenario to
   2,100 ms, which produces 105 arrivals:

   ```sh
   pherf -z "$ZK" -l -q -disableSchemaApply -label odd1 \
     -scenarioFile "$HOWTO/relational_scenario.xml" -runDurationMs 2100
   ```

8. Execute one run configured with an artificially strict tail latency bound that every run
   violates, followed by another run that adds the noisy-host property:

   ```sh
   cp "$HOWTO/conf/pherf.properties" "$HOWTO/conf/pherf.properties.keep"
   echo 'pherf.verdict.tail.p99MaxMs=0.001' >>"$HOWTO/conf/pherf.properties"
   pherf -z "$ZK" -l -q -disableSchemaApply -label tight \
     -scenarioFile "$HOWTO/relational_scenario.xml"
   echo "exit $?"
   echo 'pherf.verdict.noisyHost=true' >>"$HOWTO/conf/pherf.properties"
   pherf -z "$ZK" -l -q -disableSchemaApply -label noisy \
     -scenarioFile "$HOWTO/relational_scenario.xml"
   mv "$HOWTO/conf/pherf.properties.keep" "$HOWTO/conf/pherf.properties"
   unset PHERF_CONF
   ```

   Expected output (illustrative, not captured from a live cluster):

   ```text
   exit 0
   ```

   Health verdicts never alter the Pherf process exit code. You will inspect both generated verdict
   files in step 15.

9. If you executed the runs against a local harness cluster, record its container image tag (see
   "Reproduce it"), tear the cluster down, and re-source `pherf-env.sh` to restore the host-local
   `pherf` shell function, because part 2 operates without a cluster. The final log line emitted by
   the down command confirms that teardown succeeded:

   ```sh
   px-cluster down
   . "$PX_OUT/howto/pherf-env.sh"
   ```

   Expected output:

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

### Part 2: read one run

10. List the output artifacts produced for a single replicate:

    ```sh
    ls RESULTS_HDR | grep base1; ls RESULTS | grep base1
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    LATENCY_base1.hlog
    LATENCY_base1.json
    LATENCY_base1_intervals.hlog
    VERDICT_base1.tsv
    RESULT_COMBINED_base1_detail.csv
    RESULT_Data_Load_Summary_base1.csv
    RESULT_reference_relational_base1.xml
    RESULT_reference_relational_base1_aggregate.csv
    RESULT_reference_relational_base1_detail.csv
    ```

    The `RESULTS_HDR` directory contains the microsecond-resolution outputs: the JSON summary
    report (which is published last via an atomic rename), the full run histogram log, the interval
    histogram log, and the health verdict file. The `RESULTS` directory contains the legacy
    millisecond-resolution reports. Whenever a new run starts with an existing label, Pherf deletes
    the three latency files and the verdict file associated with that label beforehand so that
    stale and current artifacts never interleave.

11. Extract the microsecond latency percentiles for each operation key from the JSON report:

    ```sh
    awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"count":/ {c=$2} /"p50Us":/ {a=$2}
      /"p99Us":/ {b=$2} /"p999Us":/ {print o, p, c, a, b, $2}' RESULTS_HDR/LATENCY_base1.json |
      tr -d '",'
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    delete:referenceRelational:rangeDelete steady 10 6111 9671 9671
    load:referenceRelational:PHERF.REF_RELATIONAL steady 20 35487 61311 61311
    query:referenceRelational:lookup steady 70 2803 7511 7511
    response:referenceRelational:lookup steady 70 3115 7999 7999
    response:referenceRelational:rangeDelete steady 10 6403 9983 9983
    response:referenceRelational:rangeScan steady 20 3519 6847 6847
    scan:referenceRelational:rangeScan steady 20 3247 6527 6527
    ```

    The output columns represent the operation identifier, the execution phase, the sample count,
    p50, p99, and p99.9. These timing values are illustrative. Each operation key follows the
    `<kind>:<scenario>:<name>` naming structure:

    - `query`: Measures a single query execution from connection acquisition through retrieval of
      the final row; in an open-loop workload, this represents the server-side service time.
    - `scan`, `delete`: Measures a single `SCAN` or `DELETE` execution, where a `DELETE`
      measurement concludes once its commit completes.
    - `response`: Recorded for open-loop workloads only, measuring from the intended dispatch
      timestamp through retrieval of the final row; subtracting `query` from `response` yields the
      client-side queueing delay.
    - `load`: Measures a single committed batch during the data load phase.
    - `operation`: Measures a single operation executed within a `-mt` load profile.

    The phase field is `steady` for measured workload executions and `warmup` for the initial
    `warmupExecutions` of a query set. When a key has only 10 samples, p99 and p99.9 simply reflect
    the slowest single observation, because any quantile q requires at least 1 / (1 - q) samples to
    distinguish the percentile from the sample maximum. Failed operations do not record latency
    samples. A query that exceeds its configured `timeoutDuration` terminates and records the
    elapsed duration up to cancellation; consequently, a cluster of observations near the timeout
    threshold indicates a tail of timed-out queries, which the detail CSV flags as `TIMED_OUT`.

12. Inspect the run histogram log using the standard HdrHistogram command-line tools. The header
    line records the fixed histogram recorder configuration, which spans from 1 us to 600 s with
    3 significant digits of precision:

    ```sh
    head -1 RESULTS_HDR/LATENCY_base1.hlog
    "$JAVA_HOME/bin/java" -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
      -i RESULTS_HDR/LATENCY_base1.hlog -listtags
    ```

    Expected output, not captured from a live cluster (with the first line wrapped here for
    readability):

    ```text
    #[Pherf latency log 1: microseconds, lowest 1, highest 600000000, 3 significant digits,
    Tag=OPERATION|PHASE]
    Tags found in input file:
    delete:referenceRelational:rangeDelete|steady
    load:referenceRelational:PHERF.REF_RELATIONAL|steady
    query:referenceRelational:lookup|steady
    ...
    ```

    Each histogram tag concatenates the operation key, a `|` delimiter, and the execution phase.
    Within the operation string, any `%`, space, comma, CR, or LF characters are percent-encoded as
    `%25`, `%20`, `%2C`, `%0D`, and `%0A`.

13. Format the complete percentile distribution for a single tag in milliseconds. Because the
    underlying histogram values are stored in microseconds, pass an output unit scaling ratio of
    1000:

    ```sh
    "$JAVA_HOME/bin/java" -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
      -i RESULTS_HDR/LATENCY_base1.hlog -tag 'response:referenceRelational:lookup|steady' \
      -outputValueUnitRatio 1000
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
           Value     Percentile TotalCount 1/(1-Percentile)

           1.987 0.000000000000          1           1.00
    ...
    #[Mean    =        3.402, StdDeviation   =        1.204]
    #[Max     =        7.999, Total count    =           70]
    ```

    Do not pass `-correctLogWithKnownCoordinatedOmission` when processing open-loop logs. Because
    the open-loop driver already measures response latency from the scheduled arrival time, the
    recorded distribution contains no coordinated omission to synthesize.

14. Extract the time-series interval percentiles for the same tag. Providing the `-o` option writes
    the per-interval summary lines to the named output file and writes the aggregated percentile
    distribution to a companion file suffixed with `.hgrm`:

    ```sh
    "$JAVA_HOME/bin/java" -cp "$PHERF_JAR" org.HdrHistogram.HistogramLogProcessor \
      -i RESULTS_HDR/LATENCY_base1_intervals.hlog \
      -tag 'response:referenceRelational:lookup|steady' -outputValueUnitRatio 1000 -o lookup
    cat lookup; tail -2 lookup.hgrm
    ```

    Expected output, not captured from a live cluster (with the column header line wrapped here for
    readability):

    ```text
    #[Interval percentile log between 0.000 and <Infinite> seconds (relative to StartTime)]
    ...
    Time: IntervalPercentiles:count ( 50% 90% Max ) TotalPercentiles:count ( 50% 90% 99%
    99.9% 99.99% Max )
    2.812: I:17 (   3.103   4.915   7.999 ) T:17 (   3.103   4.915   7.999   7.999   7.999   7.999 )
    3.312: I:18 (   3.007   4.371   5.103 ) T:35 (   3.055   4.711   7.999   7.999   7.999   7.999 )
    #[Max     =        7.999, Total count    =           70]
    #[Buckets =           20, SubBuckets     =         2048]
    ```

    Each time slice begins immediately where the preceding slice ended, and the individual interval
    counts for a given key sum to the total sample count recorded in the JSON report. The figures
    shown here are illustrative, reflecting 25 total arrivals per 500 ms window with 70% allocated
    to lookups. When verified against a captured log, the interval summary output from the processor
    omitted the final interval line even though the `.hgrm` aggregate summary included every sample;
    always rely on the JSON report for authoritative operation counts.

15. Compare the health verdict files from a normal baseline run, the run configured with the tight
    tail latency bound, and the run configured with the noisy-host flag:

    ```sh
    for l in base1 tight noisy; do echo "== $l"; cut -f1-2 "RESULTS_HDR/VERDICT_$l.tsv"; done
    grep tail_latency RESULTS_HDR/VERDICT_noisy.tsv | cut -f5
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    == base1
    pherf.health.attainment	PASS
    pherf.health.saturation	PASS
    pherf.health.client_pool	PASS
    pherf.health.tail_latency	PASS
    pherf.health.read_skew	SKIP
    == tight
    ...
    pherf.health.tail_latency	FAIL
    ...
    == noisy
    ...
    pherf.health.tail_latency	ADVISORY
    ...
    noisy host (pherf.verdict.noisyHost): FAIL reads as ADVISORY; 6 of 6 keys over the bounds ...
    ```

    Every verdict file contains exactly five rows in a fixed order, formatted as five TAB-separated
    columns: `check severity class source detail`. Severity levels follow the ordering `PASS` <
    `SKIP` < `ADVISORY` < `FAIL` < `ERROR`, where only `FAIL` and `ERROR` constitute health check
    failures.

    - `pherf.health.attainment` (class duration): Reports FAIL when the lowest rate attainment
      across any open-loop schedule falls below `attainment.minPct` (90), and reports SKIP when no
      open-loop schedule executed during the run.
    - `pherf.health.saturation` (class duration): Reports FAIL when the highest peak dispatch lag
      across any open-loop schedule exceeds `lag.maxMs` (1000), and reports SKIP when no open-loop
      schedule executed during the run.
    - `pherf.health.client_pool` (class count): Reports FAIL when one or more tasks had to wait for
      an available worker thread in the Pherf executor pool, and reports SKIP when no tasks
      executed on the pool.
    - `pherf.health.tail_latency` (class duration): Reports FAIL when the p99 or p99.9 latency of
      any measured key exceeds `tail.p99MaxMs` (1000) or `tail.p999MaxMs` (5000), and reports SKIP
      when no latency keys were measured.
    - `pherf.health.read_skew` (class share): Reports FAIL when the ratio of read requests on the
      busiest server to the cluster mean exceeds `readSkew.max` (1.5), and reports SKIP when no JMX
      source is configured, the measurement window is shorter than 60 s, fewer than 2 servers
      participate, no reads occur, or a counter moves backward.

    Each threshold is configured via `pherf.verdict.<name>` in `pherf.properties`, and an observed
    value that exactly equals its configured bound passes the check. Setting
    `pherf.verdict.noisyHost=true` downgrades any `FAIL` verdict of class `duration` to `ADVISORY`
    and records the reason in the detail column. Verdicts of class `count` or `share`, as well as
    any `ERROR` row, are never downgraded. Supplying an invalid threshold value halts Pherf with
    exit 1 before any connection is opened, reporting an error such as
    `pherf.verdict.readSkew.max=0.5 is less than 1`.

    **Read skew on your cluster.** To evaluate read distribution across your cluster, set
    `pherf.verdict.serverJmx` to a comma-separated list of RegionServer info server base URLs, such
    as `pherf.verdict.serverJmx=http://rs1.example.com:16030,http://rs2.example.com:16030`. Pherf
    polls the JMX servlet on each server at the start of measured work and again at the end of the
    run to capture the `readRequestCount` for user tables. Because each RegionServer updates its
    metrics every 5 s and the JMX servlet caches responses for an additional 10 s, a counter sample
    can be up to 15 s stale; consequently, Pherf grades any measurement window shorter than 60 s as
    SKIP. A single-region table deployed across 3 servers will fail the read skew check, as expected
    when all reads target one server. See
    [H11](../../../dev/harness/docs/howto/11-scale-out-capacity-and-skew.md) for instructions on
    enabling this check through the harness via `--read-skew`.

16. Verify the half-up millisecond rounding used in the legacy reports. The XML and CSV result
    files report timings in milliseconds by converting each microsecond duration using half-up
    integer rounding, `(us + 500) / 1000`:

    ```sh
    grep -o 'elapsedDurationInMs="[0-9]*"' RESULTS/RESULT_*_base1.xml | sort | uniq -c |
      sort -rn | head -3
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
     41 elapsedDurationInMs="3"
     27 elapsedDurationInMs="2"
     14 elapsedDurationInMs="4"
    ```

    Under this rounding rule, 2,600 us rounds to 3 ms and 300 us rounds to 0 ms. Because any
    operation completing in under 500 us appears as 0 ms in the legacy files, you must always read
    sub-millisecond workloads from the HDR outputs. Note that the `AVG_*` columns in the aggregate
    CSV report continue to use truncating integer division, and the data load summary report
    remains measured on the millisecond clock.

### Part 2: compare replicates

17. Assemble the replicate set directories. A replicate set is a parent directory in which each
    immediate subdirectory represents one replicate run. A bare replicate directory must contain
    exactly one `LATENCY_<label>.json` file. Copy the corresponding XML result file alongside it so
    that the analyzer can identify the scenario as co-generated, enforce exact count parity, and
    extract per-query row counts:

    ```sh
    for s in base cand; do
      for i in 1 2 3; do
        mkdir -p "$HOWTO/sets/$s/r$i"
        cp RESULTS_HDR/*_"$s$i"[._]* RESULTS/RESULT_*_"$s$i".xml "$HOWTO/sets/$s/r$i/"
      done
    done
    mkdir -p "$HOWTO"/sets/mixed/r{1,2,3}
    cp "$HOWTO"/sets/base/r1/* "$HOWTO/sets/mixed/r1/"
    cp "$HOWTO"/sets/base/r2/* "$HOWTO/sets/mixed/r2/"
    cp RESULTS_HDR/*_odd1[._]* RESULTS/RESULT_*_odd1.xml "$HOWTO/sets/mixed/r3/"
    ls "$HOWTO/sets/base/r1"
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    LATENCY_base1.hlog
    LATENCY_base1.json
    LATENCY_base1_intervals.hlog
    RESULT_reference_relational_base1.xml
    VERDICT_base1.tsv
    ```

    When processing a bare replicate directory, the analyzer reads only the JSON latency report and
    the XML result files. Because a harness run bundle is also recognized as a valid replicate
    layout, you can organize bundle directories into replicate sets using the exact same directory
    structure.

18. Run the offline analyzer against the baseline replicate set by itself:

    ```sh
    pherf -analyze "$HOWTO/sets/base" -label base >base.out; echo "exit $?"
    grep -E '^(Set|  r|pherf|Analysis)' base.out
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    ... INFO  [main] pherf.Pherf: Run completed. Shutting down thread pool.
    exit 0
    Set set $HOWTO/sets/base: run class undeclared
      r1 bare class undeclared contamination none
      r2 bare class undeclared contamination none
      r3 bare class undeclared contamination none
    pherf.analysis.count_parity	PASS	count	pherf	10 count key(s) equal in 1 set(s)
    Analysis: PASS. Report: RESULTS/ANALYSIS_base.json
    ```

    Pherf writes the human-readable analysis summary to standard output, emits log messages to
    standard error, and saves the structured JSON report to `RESULTS/ANALYSIS_<label>.json`. The 10
    verified count keys consist of the execution `count` across each of the 7 latency keys plus the
    returned `rows` across the 3 query definitions. Because a bare replicate directory lacks a
    harness manifest, its run class defaults to `undeclared`; consequently, its timing measurements
    are never treated as authoritative, and it can only be compared against other bare replicate
    directories.

19. Inspect the replicate median and the 95% confidence interval for each latency and throughput
    metric:

    ```sh
    awk -F': ' '/"operation":/ {o=$2} /"metric":/ {m=$2} /"n":/ {n=$2} /"median":/ {d=$2}
      /"ciLow":/ {l=$2} /"ciHigh":/ {print o, m, n, d, l, $2}' RESULTS/ANALYSIS_base.json |
      tr -d '",' | grep -E '^(query|response):.*lookup|^run'
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    query:referenceRelational:lookup p50Us 3 2803.0 2711.0 2911.0
    query:referenceRelational:lookup p90Us 3 4415.0 4207.0 4799.0
    response:referenceRelational:lookup p50Us 3 3115.0 3007.0 3203.0
    response:referenceRelational:lookup p90Us 3 4895.0 4511.0 5215.0
    run opsPerSec 3 21.53 21.12 21.87
    ```

    The output columns list the operation key, the metric name, the replicate count n, the median
    value, and the lower and upper bounds of the confidence interval (illustrative values). Because
    the lookup key accumulated 70 samples per run, the analyzer includes only `p50Us` and `p90Us`;
    computing `p99Us` requires at least 100 samples, while `p999Us` requires at least 1,000. Each
    confidence interval is calculated via a percentile bootstrap of the sample median using 10,000
    resamples at a 95% confidence level with a fixed random seed of 8070 per metric, ensuring
    identical intervals across any JVM for the same inputs. For a set of 3 replicates, the 95%
    bootstrap confidence interval of the median always spans from the minimum observed value to the
    maximum observed value.

20. Examine the count parity entries recorded for the replicate set:

    ```sh
    awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"metric":/ {m=$2} /"exact":/ {x=$2}
      /"equal":/ {print o, p, m, x, $2}' RESULTS/ANALYSIS_base.json | tr -d '",' | head -4
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    delete:referenceRelational:rangeDelete steady count true true
    delete:referenceRelational:rangeDelete steady rows true true
    load:referenceRelational:PHERF.REF_RELATIONAL steady count true true
    query:referenceRelational:lookup steady count true true
    ```

    The columns report the operation key, the phase, the count metric name, `exact`, and `equal`.

    The `exact` flag is true because the accompanying XML result file identifies the scenario as
    co-generated, meaning every operation count and row count is a deterministic function of the
    scenario definition and any discrepancy represents a defect. If the XML result file is omitted,
    count parity checks become advisory only and no `rows` metrics are evaluated.

21. Run the analyzer on the mixed replicate set containing the mismatched third replicate:

    ```sh
    pherf -analyze "$HOWTO/sets/mixed" -label mixed >mixed.out; echo "exit $?"
    grep -E '^(pherf|Analysis)' mixed.out
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    ...
    exit 67
    pherf.analysis.count_parity	FAIL	count	pherf	6 co-generated key(s) differ, 0 rule-based; ...
    Analysis: FAIL. Report: RESULTS/ANALYSIS_mixed.json
    ```

    Because the third replicate ran for 105 arrivals rather than 100, the workload mix dispatched
    74 lookups and 21 scans instead of 70 and 20; consequently, the `count` values for the `query`,
    `scan`, and two `response` keys, along with the `rows` counts for both the lookup and the scan,
    diverge from the first two replicates. Exit code 67 indicates that the analyzer wrote the JSON
    report and recorded at least one `FAIL` verdict row. Count parity failures are never downgraded
    to advisory status, because no host environment condition can excuse an inconsistent operation
    count.

22. Perform a two-set comparison between the candidate and the baseline by passing the baseline set
    directory first:

    ```sh
    pherf -analyze "$HOWTO/sets/base" "$HOWTO/sets/cand" -label ab >ab.out; echo "exit $?"
    grep -E '^(Regression|Improvement|pherf|Analysis)' ab.out
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    ...
    exit 0
    Improvement query:referenceRelational:lookup steady p50Us -38.2% [-41.0%, -35.1%]
    ...
    pherf.analysis.count_parity	PASS	count	pherf	20 count key(s) equal in 2 set(s)
    pherf.analysis.regression	PASS	duration	pherf	15 metric(s) compared, no regression, ...
    Analysis: PASS. Report: RESULTS/ANALYSIS_ab.json
    ```

    The 15 compared metrics comprise `p50Us` and `p90Us` across all 7 latency keys, plus overall
    run `opsPerSec`.

    The percentage changes shown above are illustrative and depend on the cost of opening a fresh
    connection against your specific cluster. For every metric present in both sets, `delta` is
    computed as the candidate median minus the baseline median, accompanied by a two-sample
    bootstrap confidence interval. A metric comparison receives the outcome `regression` when the
    median shifts by more than 10% in the degraded direction and the confidence interval completely
    excludes 0 on that side, or `improvement` when the shift exceeds 10% in the favorable direction
    with 0 excluded. Higher values indicate degradation for latency metrics, whereas lower values
    indicate degradation for `opsPerSec`.

23. Extract the full table of metric comparisons from the A/B analysis report:

    ```sh
    awk -F': ' '/"operation":/ {o=$2} /"metric":/ {m=$2} /"deltaPct":/ {d=$2} /"ciLowPct":/ {l=$2}
      /"ciHighPct":/ {h=$2} /"outcome":/ {print o, m, d, l, h, $2}' RESULTS/ANALYSIS_ab.json |
      tr -d '",'
    ```

    Any regression detected across bare replicate directories produces an ADVISORY verdict rather
    than FAIL because bare replicates have an `undeclared` run class. Emitting an authoritative
    FAIL verdict requires every replicate to belong to the `measurement` run class, have zero
    contamination tags, and provide at least 3 observations of the compared metric in each set.
    Harness result bundles generated under the measurement class record these class and
    contamination attributes automatically; see
    [H12](../../../dev/harness/docs/howto/12-regression-ab-and-matrices.md) for instructions on
    producing them.

24. Verify how the analyzer handles invalid invocations and input refusals. Passing three set
    directories on the command line is rejected as an invalid invocation:

    ```sh
    pherf -analyze "$HOWTO"/sets/{base,cand,mixed} -label bad >bad.out 2>&1
    echo "exit $?"; grep ERROR bad.out; ls RESULTS | grep -c ANALYSIS_bad
    ```

    Expected output (illustrative, not captured from a live cluster):

    ```text
    exit 1
    ... ERROR [main] pherf.Pherf: -analyze takes one set directory, or a baseline and a ...
    0
    ```

    Whenever the analyzer refuses an invocation, it writes no new report and deletes any existing
    report bearing the same label. Similarly, if you place a harness run bundle into a set
    alongside bare replicate directories, the run classes conflict (`validation` or `measurement`
    versus `undeclared`), causing the analyzer to abort with exit 66 before computing any
    statistics. Step 30 demonstrates this run-class refusal.

### Part 2: compare harness bundles

The steps in this section were executed against live harness run bundles produced under the
validation run class. Because a harness bundle records its run class, host contamination tags, and
run verdict in its manifest, the offline analyzer automatically applies the harness authority rules
when grading regressions. The input dataset consists of 6 open-loop bundles generated using the
workload from step 12 of [H05](../../../dev/harness/docs/howto/05-latency-slo-and-soak.md): 3
baseline runs executed at the declared rate of 50 operations per second, and 3 candidate runs
executed at 100 operations per second.

25. Produce the two bundle sets against the H05 validation cluster, which remains running after
    step 12 of that guide until its concluding `px-cluster down`. The loop copies a generated bundle
    into its replicate set only when `px-run` exits with status 0, and the final log line of each
    `px-run` invocation reports its result and exit code. If a run exits with status 1 due to a
    background match in `cluster.log` (as described in H03, step 3), repeat that replicate. Record
    the cluster image tag (see "Reproduce it") before running the final `px-cluster down`:

    ```sh
    export PATH="$PHX_ROOT/dev/harness/bin:$PATH"
    H05="$PX_OUT/howto/h05"; mkdir -p "$HOWTO/bsets/base" "$HOWTO/bsets/cand"
    W=(--scenario "$H05/slo_open.xml" --schema "$H05/slo_schema.sql" \
      --properties "$H05/slo.properties")
    for i in 1 2 3; do
      RUN="$(px-run pherf -e PX_THRESHOLD_STEADY_P99_MS_MAX=50 -- "${W[@]}")" &&
        cp -R "$RUN" "$HOWTO/bsets/base/"
      RUN="$(px-run pherf -e PX_THRESHOLD_STEADY_P99_MS_MAX=50 -- "${W[@]}" -- -targetRate 100)" &&
        cp -R "$RUN" "$HOWTO/bsets/cand/"
    done
    px-cluster config PX_IMAGE_TAG
    px-cluster down
    ```

    Each run completes in approximately 1 min. The loop alternates between baseline and candidate
    runs so that any gradual host drift affects both sets equally. Although the live verification
    session executed the three baseline runs followed by the three candidate runs, the execution
    order does not alter the report format. Because each copied bundle retains its timestamped run
    ID as its directory name, the subdirectories within each set remain naturally sorted in
    chronological order.

26. Analyze the baseline bundle set on its own:

    ```sh
    pherf -analyze "$HOWTO/bsets/base" -label hbase >hbase.out; echo "exit $?"; cat hbase.out
    ```

    Captured output:

    ```text
    exit 0
    Set set $HOWTO/bsets/base: run class validation
      20261123T030953Z-bs1r bundle 20261123T030953Z-bs1r class validation contamination
      [quiet-host] result SKIP
      ...
    pherf.analysis.count_parity	PASS	count	pherf	4 count key(s) equal in 1 set(s)
    Analysis: PASS. Report: RESULTS/ANALYSIS_hbase.json
    ```

    Each replicate summary line is emitted as a single line on standard output, though it is
    wrapped here for readability. Pherf also emits three INFO log lines to standard error, which
    are omitted here and in subsequent steps. For each bundle, the analyzer extracts the run class,
    contamination tags, and overall result from `manifest.json`, and locates the JSON latency
    report and XML result files under `drivers/pherf/`. The 4 count parity keys correspond to the
    steady and warmup `count` of the query key, the query `rows` count, and the `count` of the
    open-loop response key. Because a harness bundle preserves the latency report from the `run`
    phase only, no `load` key is included in the comparison.

27. Inspect the replicate metadata alongside the median and confidence interval for each metric:

    ```sh
    jq -r '.sets[0].metrics[] | [.operation, .metric, .n, .median, .ciLow, .ciHigh] | @tsv' \
      RESULTS/ANALYSIS_hbase.json
    jq -c '.sets[0].replicates[0]' RESULTS/ANALYSIS_hbase.json
    ```

    Captured output (the timing figures are illustrative):

    ```text
    query:slo_open:get	p50Us	3	2513.0	2493.0	2535.0
    ...
    response:slo_open:get	p99Us	3	10295.0	10095.0	10335.0
    response:slo_open:get	p999Us	3	11863.0	11343.0	13559.0
    run	opsPerSec	3	47.42333227948151	47.421833011918686	47.490897577964226
    {"name":"20261123T030953Z-bs1r","layout":"bundle","runId":"20261123T030953Z-bs1r",
    "runClass":"validation","contamination":["quiet-host"],"result":"SKIP",
    "latencyReport":"drivers/pherf/results_HDR/LATENCY_run.json",
    "xmlResults":["drivers/pherf/results/RESULT_howto_slo_run.xml"],"coGenerated":["slo_open"]}
    ```

    Because each run collected 1500 steady-state samples, the analyzer computes all four latency
    percentiles. The reported `opsPerSec` is approximately 47.4 rather than 50 because a replicate's
    elapsed duration extends until its final in-flight operation completes after the dispatch window
    closes. At the top level, the JSON analysis report also records `format`, `version`, `label`,
    `bootstrap` (configured at 10000 resamples, 95% confidence, and seed 8070),
    `regressionThresholdPct` (10.0), `timingAuthoritative`, `result`, `verdicts`, `comparisons`,
    and `unpaired`.

28. Run the two-set comparison in both directions:

    ```sh
    pherf -analyze "$HOWTO/bsets/base" "$HOWTO/bsets/cand" -label hab >hab.out; echo "exit $?"
    grep -E '^(Improvement|Regression|pherf|Analysis)' hab.out | tail -4
    pherf -analyze "$HOWTO/bsets/cand" "$HOWTO/bsets/base" -label hba >hba.out; echo "exit $?"
    grep -E '^(Improvement|Regression|pherf|Analysis)' hba.out | tail -4
    jq -c '.comparisons[0]' RESULTS/ANALYSIS_hab.json
    ```

    Captured output:

    ```text
    exit 0
    Improvement run steady opsPerSec +99.1% [+98.8%, +99.3%]
    pherf.analysis.count_parity	PASS	count	pherf	8 count key(s) equal in 2 set(s)
    pherf.analysis.regression	PASS	duration	pherf	9 metric(s) compared, no regression, 9
    improvement(s)
    Analysis: PASS. Report: RESULTS/ANALYSIS_hab.json
    exit 0
    Regression run steady opsPerSec -49.8% [-49.9%, -49.6%]
    pherf.analysis.count_parity	PASS	count	pherf	8 count key(s) equal in 2 set(s)
    pherf.analysis.regression	ADVISORY	duration	pherf	9 regression(s) of 9 metric(s); worst:
    query:slo_open:get steady p999Us +124.7% [+7.2%, +161.9%]; timing is not authoritative: run
    class validation
    Analysis: ADVISORY. Report: RESULTS/ANALYSIS_hba.json
    {"operation":"query:slo_open:get","phase":"steady","metric":"p50Us","baselineMedian":2513.0,
    "candidateMedian":1711.0,"delta":-802.0,"deltaPct":-31.914046955829686,
    "ciLowPct":-34.73935535216872,"ciHighPct":-30.16315161161958,"outcome":"improvement"}
    ```

    Each output row is printed as a single line, wrapped here for display. Because count parity is
    evaluated independently within each replicate set, the differing total operation counts between
    the 50 and 100 operations-per-second sets do not trigger a parity failure. Notice that every
    latency metric was actually lower at 100 operations per second than at 50 operations per second.
    On a laptop, a lightly loaded CPU often operates at a lower clock frequency and enters deeper
    power-saving sleep states between arrivals, causing a lighter request rate to exhibit higher
    per-operation latency than a heavier rate. This behavior illustrates why the offered arrival
    rate must be held constant as an experimental control whenever you evaluate a latency claim.
    When the comparison order is reversed, the resulting regression is graded as ADVISORY and the
    analyzer exits 0 because the replicates belong to the validation run class and carry the
    `quiet-host` contamination tag.

29. Combine one candidate bundle with two baseline bundles in a single replicate set to verify count
    parity failure detection on bundles:

    ```sh
    mkdir -p "$HOWTO/bsets/mixed"
    set -- "$HOWTO"/bsets/base/*; cp -R "$1" "$2" "$HOWTO/bsets/mixed/"
    set -- "$HOWTO"/bsets/cand/*; cp -R "$1" "$HOWTO/bsets/mixed/"
    pherf -analyze "$HOWTO/bsets/mixed" -label hmixed >hmixed.out; echo "exit $?"
    grep -E '^(pherf|Analysis)' hmixed.out
    ```

    Captured output:

    ```text
    exit 67
    pherf.analysis.count_parity	FAIL	count	pherf	3 co-generated key(s) differ, 0 rule-based;
    first: set query:slo_open:get steady count: 20261123T030953Z-bs1r=1500
    20261123T031231Z-r41y=1500 20261123T031550Z-2c1d=3000
    Analysis: FAIL. Report: RESULTS/ANALYSIS_hmixed.json
    ```

    On macOS, avoid appending a trailing `/` to a bundle path when running `cp -R`; BSD `cp` copies
    the internal contents of the source directory rather than the bundle directory itself when a
    trailing slash is present, which causes the analyzer to fail with exit 1 because subdirectories
    such as `cluster/` are not valid replicates.

30. Compare the full harness bundles against bare directory copies extracted from those exact same
    runs. Each bare copy contains only the files from `drivers/pherf/results_HDR/` along with the
    XML result file:

    ```sh
    i=0
    for b in "$HOWTO"/bsets/base/*; do
      i=$((i + 1)); mkdir -p "$HOWTO/bsets/bare/r$i"
      cp "$b"/drivers/pherf/results_HDR/*_run[._]* "$b"/drivers/pherf/results/RESULT_*_run.xml \
        "$HOWTO/bsets/bare/r$i/"
    done
    pherf -analyze "$HOWTO/bsets/bare" -label hbare >/dev/null 2>&1; echo "exit $?"
    pherf -analyze "$HOWTO/bsets/base" "$HOWTO/bsets/bare" -label hclass >/dev/null 2>hclass.err
    echo "exit $?"; grep -o 'Refused: .*' hclass.err | cut -c1-120
    ```

    Captured output:

    ```text
    exit 0
    exit 66
    Refused: the analysis uses replicates of one run class only, and these have the classes [un
    ```

    When analyzed by itself, the bare set succeeds with the `undeclared` run class. When compared
    against the harness bundle set, however, the run classes mismatch, causing the analyzer to
    refuse the comparison with exit 66 without writing a report. Mixing both directory layouts
    inside a single replicate set fails with exit 66 in the exact same manner.

31. Construct a replicate set using symbolic links pointing to the bundle directories, and run the
    analyzer against it:

    ```sh
    mkdir -p "$HOWTO/bsets/links"
    for b in "$HOWTO"/bsets/base/*; do ln -s "$b" "$HOWTO/bsets/links/${b##*/}"; done
    pherf -analyze "$HOWTO/bsets/links" -label hlinks >hlinks.out; echo "exit $?"
    diff <(jq -c '.sets[0].metrics' RESULTS/ANALYSIS_hlinks.json) \
      <(jq -c '.sets[0].metrics' RESULTS/ANALYSIS_hbase.json) && echo "same metrics"
    ```

    Captured output:

    ```text
    exit 0
    same metrics
    ```

    The analyzer transparently follows symbolic links to replicate directories. Although a
    symlink-based set avoids duplicating disk usage during local analysis, the links will break if
    the underlying bundles are relocated, so always archive full directory copies for long-term
    retention.

## Read the result

* **One run.** Begin by inspecting the health verdict file. PASS verdicts on both `attainment` and
  `saturation` confirm that the load generator actually delivered the declared arrival schedule.
  Next, evaluate the open-loop `response` latency percentiles against your service-level objective.
  The operation service keys isolate execution time spent inside the cluster, whereas the
  difference between `response` latency and service latency quantifies queueing delay inside the
  client.
* **A set.** A PASS verdict on `count_parity` across exact keys proves that all replicates executed
  identical workloads. Once parity is established, the replicate median provides the point estimate
  for each metric and the confidence interval measures its run-to-run dispersion. A wide confidence
  interval indicates the need for additional replicates, a quieter host environment, or a longer
  measurement window.
* **Two sets.** A FAIL verdict on `regression` is authoritative only when the runs use the
  measurement run class on a quiet host with at least 3 replicates in each set. An ADVISORY verdict
  indicates that the empirical evidence points toward a regression exceeding the threshold but
  lacks the experimental authority to prove it, with the specific reason recorded in the verdict
  detail column.
* **Small samples.** With 3 replicates in each set, a bootstrap confidence interval that excludes 0
  means that the observations from the two sets do not overlap. Under the null hypothesis where two
  independent sets are drawn from a single continuous distribution, complete one-sided non-overlap
  occurs purely by chance with a probability of 1/20 for 3 observations per set, and 1/6 for 2
  observations per set. Because the analysis does not apply a multiple-comparisons correction—where
  each operation key produces up to five metrics and a single metric regression marks the verdict
  row FAIL—you should treat an isolated regression on one metric among many as an investigative
  lead and confirm it with additional replicates.
* **False alarms and power.** For any single metric, one-sided non-overlap between two sets of n
  replicates drawn from the same distribution occurs with probability 1 / C(2n, n): 1/20 at 3
  against 3, 1/70 at 4 against 4, and 1/252 at 5 against 5. When evaluating roughly 10 metrics
  under run-to-run noise close to the 10% threshold (where the magnitude threshold does not filter
  out random swings), an unchanged build produces a false regression alarm with a probability of
  approximately 1 - (19/20)^10, or 40%, at 3 against 3 replicates. Increasing the sample size to 5
  against 5 reduces this false-alarm probability to roughly 4%, so automated regression gates
  should use 5 replicates per set. Always compare two independent baseline sets first (an A/A
  comparison): it must produce no regression, and its observed deltas establish the natural noise
  floor. Because the regression threshold is fixed at 10%, `-analyze` cannot substantiate claims for
  effects smaller than 10%.
* **Fleet reports.** A Distributed Pherf fleet report conforms to the version 1 latency report
  schema of LATENCY.md with one additional top-level member, `fleet`, which standard consumers of
  this format ignore. Consequently, both the analysis commands in this HOWTO and `-analyze` ingest
  a controller run bundle without modification. The fleet verdict appends three fleet-level checks
  to the five single-node checks covered in this guide: `pherf.fleet.start_skew`,
  `pherf.fleet.telemetry`, and `pherf.fleet.agents` ([D02][d02], [H15][h15]). Each metric key in a
  fleet report is computed from a single merged histogram that combines the raw samples from all
  participating agents, ensuring that reported percentiles reflect the true fleet-wide distribution.
  Never average percentile values across agents, because the arithmetic mean of two p99 values is
  not a p99 ([D04][d04]).
* **Automation.** The `-analyze` mode exits 0 when no verdict row reports `FAIL`, exits 1 on an
  invalid command-line invocation or unusable input, exits 66 when replicate run classes differ,
  and exits 67 when the report contains at least one `FAIL` row. It never exits 65, which is
  reserved for the scenario validator. Continuous integration jobs can gate directly on the exit
  code and archive the JSON report; because the report omits timestamps, identical inputs and
  labels always produce byte-for-byte identical output.

## Reproduce it

* Archive the replicate set directories together with the generated analysis report so that every
  raw input to the statistical comparison is preserved:

  ```sh
  tar -czf "$PX_OUT/howto/p06.tar.gz" -C "$PX_OUT/howto" p06
  ```

* The offline analysis is completely deterministic: given the same replicate sets and label, it
  produces the exact same report across any JVM implementation.
* To make the benchmark runs themselves reproducible, record the scenario file and its random seed,
  the Phoenix repository commit (`git rev-parse HEAD` inside `$PHX_ROOT`), the component versions
  deployed on the target cluster, and the container image tag when using a harness cluster
  (queried via `px-cluster config PX_IMAGE_TAG` while the cluster remains running).

## Clean up

Part 2 does not start or use a cluster. If you started a harness cluster in part 1, step 9 already
terminated it. If you ran part 1 against an external cluster, drop the reference table once you have
finished your analysis:

```sh
pherf -z "$ZK" -disableSchemaApply -drop 'PHERF\.REF_RELATIONAL'
```

## See also

* [LATENCY.md](../../LATENCY.md): Reference documentation for the latency output artifacts, metric
  keys, histogram logs, and legacy millisecond conversions.
* [VERDICT.md](../../VERDICT.md): Reference documentation for the five run health checks, their
  configurable thresholds, and the noisy-host demotion rule.
* [ANALYSIS.md](../../ANALYSIS.md): Reference documentation for the offline analysis inputs,
  bootstrap statistics, count parity verification, and exit codes.
* [P04](04-co-generation.md): Explanation of why co-generated workload counts are deterministic and
  exact.
* [H03](../../../dev/harness/docs/howto/03-result-bundles-and-automation.md): Guide to harness
  result bundle structure and exit codes in automated workflows.
* [H12](../../../dev/harness/docs/howto/12-regression-ab-and-matrices.md): Guide to executing A/B
  comparisons across two builds using measurement-class harness bundles.

[d02]: ../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[d04]: ../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
