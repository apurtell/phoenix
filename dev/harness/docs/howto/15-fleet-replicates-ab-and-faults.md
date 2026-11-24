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

# How to compare and stress a fleet: replicates, A/B with -analyze, and faults under fleet load

## The question

When a distributed fleet of Pherf agents drives a unified workload across multiple clients, three
fundamental design questions arise:

1. Are replicates of a single fleet execution sufficiently consistent for a replicate set to serve
   as a reliable baseline?
2. Does an isolated configuration change, specifically the table property `UPDATE_CACHE_FREQUENCY`,
   shift the merged fleet latency beyond the inherent variance of the replicates?
3. How does the client fleet experience a RegionServer pause under active fleet load, and does the
   harness accurately distinguish a successful recovery from a failed recovery?

This HOWTO executes the replicates defined in `fleet.suite`, runs the A/B comparison suite
`fleet-ab.suite`, and injects two planned faults while the fleet is under load. It then evaluates
the resulting replicate sets offline using `Pherf -analyze`, which inspects the controller bundle
nested within each harness bundle.

## Before you start

**Audience.** Developers who need to demonstrate a multi-client performance effect, and committers
who evaluate fleet A/B comparisons or resilience claims.

**Prerequisites.**

* [H14: How to run Pherf on a fleet through the harness][h14]: the distributed tier image has been
  built (`px-cluster -e PX_PHERF=1 build`), and `px-pherf run` operates as expected.
* [H12: How to detect a regression and compare builds][h12]: covers the offline analysis tool, its
  required class path, and its evaluation rules.
* [H09: How to inject faults and measure recovery][h09]: defines the disruption grammar and the
  recovery gate mechanics.
* The host must have `jq` 1.7 or newer installed, because older `jq` releases round the 64-bit slot
  seeds inspected in step 4. Offline analysis executes using the JDK and class path recorded in
  `build.tsv` during the build (step 6).

**Cost.**

* Wall time is approximately 40 min in total, comprising a cluster bring-up of about 1.5 min, 3
  replicates taking about 2 min each, 6 A/B replicates taking about 2 min each, an initial fault
  run of 2 min, a second fault run of about 17 min (due to the known defect examined in step 22),
  and a teardown of about 1 min. Part 3 is optional and excluded from this baseline total: running
  it adds an initial teardown, a measurement class bring-up, 3 replicates and a teardown totaling
  about 9 min, plus a second validation bring-up of about 1.5 min in step 20.
* Docker memory requires 15360 MiB of aggregate container limits: the validation class with 1
  RegionServer consumes 8704 MiB, adding a second RegionServer requires 2560, and the distributed
  tier adds 4096 (a controller of 1024 and 2 agents of 1536). Because the preflight check admits
  the engine memory less 4096 MiB, the engine needs at least 19456 MiB, which fits comfortably
  within a 24 GiB engine allocation.
* Disk usage remains under 1 GiB within `$PX_OUT`, consisting of 11 bundles that each occupy a few
  MiB.
* Files written on disk include `$PX_OUT/howto/15/`, 11 bundles in `$PX_OUT/runs/`, and 4 sessions
  in `$PX_OUT/sessions/`, while Part 3 writes 3 additional bundles and a fifth session.

Execute every command from the root of the repository, beginning with the three setup lines below:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/15"; mkdir -p "$HOWTO"
```

## Method

**Hypotheses.**

1. Replicates executed by `fleet.suite` produce distinct controller runs while preserving identical
   data-plane identities (slot seed, offset, and prefix), ensuring that every replicate drives the
   exact same workload.
2. Setting `UPDATE_CACHE_FREQUENCY=0` forces every statement execution to re-read table metadata
   from `SYSTEM.CATALOG`. Because this introduces an extra RPC on every lookup, the merged service
   time (tracked under the `query` key) increases, whereas aggregate throughput remains unchanged
   because the offered rate is fixed.
3. Pausing RegionServer 0 for a duration shorter than the ZooKeeper session timeout allows the node
   to recover and the fleet to finish executing across both RegionServers. Conversely, pausing the
   node longer than the session timeout causes the RegionServer to abort upon resuming, which fails
   the recovery gate while the fleet finishes its workload on RegionServer 1. (The live execution
   refuted the expectation that the gate fails "at once" for this topology, as detailed in step 22.)

**Controls.** A single cluster configuration is used across the experiments: the validation class
provisioned with 2 RegionServers and 2 agents. The suite definitions lock the workload parameters
at 20000 rows, an open-loop schedule of 300 operations per second over 60 s, 12 threads, and seed
8070. Between the two A/B variants, only the schema file changes. In addition, the A/B replicates
execute in an alternating sequence, a1 b1 a2 b2 a3 b3, ensuring that any gradual host drift affects
both variants equally.

**Noise.** Because a developer laptop host exhibits background contention and the validation class
caps all timing verdicts at ADVISORY, the offline analysis reports any detected latency regression
as ADVISORY rather than FAIL. Running three replicates per variant provides the minimum sample size
required for a statistically valid regression check (ANALYSIS.md). Similarly, a single fault
injection run demonstrates the directional behavior of a failure mode rather than its precise
magnitude.

**Metrics, and why.**

* Per replicate, the evaluation reads the merged steady-state histograms from the fleet report,
  specifically `query:px_fleet:lookup` (measuring service time) and `response:px_fleet:lookup`
  (measuring response time from the scheduled operation start). Because a single histogram merges
  the samples across all agents, its percentiles represent the exact distribution for the fleet.
* Per replicate set, the analysis computes the median of each metric across the replicates along
  with a seeded bootstrap confidence interval.
* Per fault injection run, the evaluation inspects `mttr_seconds` for each disruption, the
  `cluster.recovery` and `cluster.post_recovery` verdicts, and the 8 fleet rows (`pherf.health.*`,
  `pherf.fleet.*`).

**What invalidates the result.**

* Modifying any workload argument, seed, agent count, or weight between replicates alters the
  derived slot identities (which step 4 verifies) and invalidates the comparison.
* Combining multiple run classes within a single replicate set is invalid and causes the analysis
  tool to abort with exit 66.
* Observing a latency regression in the A/B comparison that also appears in the A/A baseline
  comparison indicates that the experimental design is too noisy to support a claim.
* Recording a FAIL verdict on either `pherf.fleet.agents` or `pherf.fleet.telemetry` indicates that
  the distributed fleet failed to execute the prescribed workload plan.

## Steps

### Part 1: replicates of the fleet

1. Run the three replicates declared in `fleet.suite`. Because no active cluster is recorded yet,
   `px-pherf run` provisions the cluster and starts the distributed tier automatically, while
   `--keep` leaves both the cluster and the tier running for the sections that follow. The output
   rows are tee'd into `rep.tsv` for step 2.

   ```sh
   px-pherf --class validation -e PX_RS_COUNT=2 --keep run fleet | tee "$HOWTO/rep.tsv"
   ```

   ```text
   ...
   ... INFO  px-pherf: the tier runs: one controller and 2 IDLE agents
   ... INFO  px-suite: session 20261127T100415Z-pd1y in .../phoenix-harness/sessions/...
   1	r1	ADVISORY	0	125403	the verdicts of this test reach ADVISORY	.../runs/20261127T100417Z-671c
   2	r2	ADVISORY	0	111130	the verdicts of this test reach ADVISORY	.../runs/...
   3	r3	ADVISORY	0	111132	the verdicts of this test reach ADVISORY	.../runs/...
   result	ADVISORY	0	.../phoenix-harness/sessions/20261127T100415Z-pd1y
   ... INFO  px-suite: session 20261127T100415Z-pd1y ends at ADVISORY: 0 pass, 0 skip, 3 advisory,
   0 fail, 0 error (exit 0, ok)
   ... INFO  px-pherf: what run brought up stays for a diagnosis: run px-pherf stop or px-cluster
   down
   ```

   In the summary output, the fifth column reports the elapsed duration of each test in
   milliseconds, and the entire step completes in roughly 6.5 min including cluster bring-up. The
   cluster and the distributed tier remain running because `--keep` was passed. Any validation
   run whose timing metrics exceed their thresholds resolves to ADVISORY because a validation run
   cannot issue an authoritative time verdict.

2. Extract the harness bundle path for each replicate. Every test result row records its bundle
   directory in the seventh column, whereas the final `result` row records the session directory.
   The `bundle` helper function below looks up a test's bundle path from a saved suite output file
   and is reused across the remaining suites in this guide.

   ```sh
   bundle() { awk -F '\t' -v t="$2" '$2 == t { print $7 }' "$1"; }
   awk -F '\t' '$1 != "result" { print $2, $7 }' "$HOWTO/rep.tsv"
   ```

   ```text
   r1 .../phoenix-harness/runs/20261127T100417Z-671c
   r2 .../phoenix-harness/runs/20261127T100622Z-jn1n
   r3 .../phoenix-harness/runs/20261127T100813Z-pn1s
   ```

3. Verify that each replicate executed as an independent controller run. Within each harness
   bundle, the archived controller bundle resides at `drivers/pherf-distributed/controller`.

   ```sh
   for t in r1 r2 r3; do
     b="$(bundle "$HOWTO/rep.tsv" "$t")/drivers/pherf-distributed/controller"
     jq -r '"\(.runId) \(.state) skew \(.steps[0].skewMs) ms"' "$b/status.json"
   done
   ```

   ```text
   20261127T100452120Z-2 COMPLETE skew 14 ms
   20261127T100643473Z-4 COMPLETE skew 9 ms
   20261127T100834554Z-6 COMPLETE skew 14 ms
   ```

   Your generated run ids will differ. The controller increments its run sequence across tests,
   with the data-loading run of each test consuming the odd numbers.

4. Confirm that all replicates share identical data-plane identities. Each slot derives its
   identity deterministically from the plan seed and the slot number, while its row offset is
   determined by the partitioned row shares.

   ```sh
   for t in r1 r2 r3; do
     b="$(bundle "$HOWTO/rep.tsv" "$t")/drivers/pherf-distributed/controller"
     jq -c '[.steps[0].slots[] | [.identity.slot, .identity.seed, .identity.offset,
       .identity.prefix, .share.rate, .share.rows]]' "$b/status.json"
   done
   ```

   ```text
   [[0,7126372246263655726,0,"p000",150,10000],[1,2268977538148885555,10000,"p001",150,10000]]
   [[0,7126372246263655726,0,"p000",150,10000],[1,2268977538148885555,10000,"p001",150,10000]]
   [[0,7126372246263655726,0,"p000",150,10000],[1,2268977538148885555,10000,"p001",150,10000]]
   ```

   These per-slot seeds match the values derived from seed 8070 across every D7 and H3 execution,
   while the aggregate rate of 300 and the 20000 rows divide into equal shares across the 2 agents.
   Observing three identical output lines supports hypothesis 1; any discrepancy indicates that the
   replicates did not execute the same workload and must not be grouped into a single set.

5. Copy each test's controller bundle into the replicate set directory `R/`. The offline analysis
   reads each replicate from its controller bundle rather than from the outer harness bundle root.

   ```sh
   mkdir -p "$HOWTO/R"
   for t in r1 r2 r3; do
     cp -R "$(bundle "$HOWTO/rep.tsv" "$t")/drivers/pherf-distributed/controller" "$HOWTO/R/$t"
   done
   ls "$HOWTO/R"
   ```

   ```text
   r1
   r2
   r3
   ```

6. Load the JDK path and the offline analyzer class path directly from the build record, following
   [H12 step 8][h12-compare]. Because the build persists both entries, the resolved class path
   matches the exact built JAR artifacts without requiring any platform-specific lookup.

   ```sh
   B="$PX_OUT/build/phoenix/build.tsv"
   JAVA="$(awk -F '\t' '$1 == "java_home" { print $2 }' "$B")/bin/java"
   CP="$(awk -F '\t' '$1 == "pherf_classpath" { print $2 }' "$B")"
   ```

7. Run the analyzer on the replicate set. Analyzing a single set verifies count parity across its
   replicates.

   ```sh
   (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf -analyze R -label rep) \
     2>/dev/null
   ```

   ```text
   Set set R: run class validation
     r1 bundle 20261127T100452120Z-2 class validation contamination [noisy-host] result ADVISORY
     r2 bundle 20261127T100643473Z-4 class validation contamination [noisy-host] result ADVISORY
     r3 bundle 20261127T100834554Z-6 class validation contamination [noisy-host] result ADVISORY
   pherf.analysis.count_parity	PASS	count	pherf	2 count key(s) equal in 1 set(s)
   Analysis: PASS. Report: RESULTS/ANALYSIS_rep.json
   ```

   The `contamination [noisy-host]` annotation reflects the `quiet_host` tag recorded in the
   controller manifest, where the driver sets `pherf.verdict.noisyHost` whenever the harness run is
   not quiet. Each fleet count key sums the completed operations across all agents, and all three
   replicates recorded identical counts.

8. Verify the boundary check by pointing the analyzer at the outer harness bundles instead; by
   design, the tool rejects them with exit 1.

   ```sh
   mkdir -p "$HOWTO/wrong"
   cp -R "$(bundle "$HOWTO/rep.tsv" r1)" "$HOWTO/wrong/r1"
   (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf -analyze wrong \
     -label wrong) 2>"$HOWTO/wrong.err"; echo "analyze exit $?"
   grep ERROR "$HOWTO/wrong.err"
   ```

   ```text
   analyze exit 1
   ... ERROR [main] pherf.Pherf: Cannot analyze wrong/r1: the latency report
   drivers/pherf/results_HDR/LATENCY_run.json is missing
   ```

   The error line above is wrapped for readability. While an outer harness bundle stores the merged
   fleet report at `drivers/pherf-distributed/report.json`, the nested controller bundle places the
   report at the path expected by the analyzer. When the analyzer aborts with exit 1, it writes no
   output report.

### Part 2: the A/B suite

9. Run `fleet-ab.suite` on the active cluster. Even though the suite file specifies the measurement
   class, the execution class recorded in the cluster record (validation, from step 1) takes
   precedence over the suite declaration for every test, so the invocation requires no `--class`
   flag; an explicit command-line `--class` option would override both.
   Because this invocation did not provision the cluster or the tier, it leaves both running when
   it finishes.

   ```sh
   px-pherf run fleet-ab | tee "$HOWTO/ab.tsv"
   ```

   ```text
   1	a1	ADVISORY	0	111306	the verdicts of this test reach ADVISORY	.../runs/...
   2	b1	ADVISORY	0	111245	the verdicts of this test reach ADVISORY	.../runs/...
   3	a2	ADVISORY	0	110110	the verdicts of this test reach ADVISORY	.../runs/...
   4	b2	ADVISORY	0	110904	the verdicts of this test reach ADVISORY	.../runs/...
   5	a3	ADVISORY	0	111022	the verdicts of this test reach ADVISORY	.../runs/...
   6	b3	ADVISORY	0	110996	the verdicts of this test reach ADVISORY	.../runs/...
   result	ADVISORY	0	.../phoenix-harness/sessions/...
   ```

   Suite execution finished in 11 min 6 s (versus 11 min 32 s in D7). Because `needs` dependencies
   chain the six tests together, they execute strictly in this alternating order.

10. Evaluate the contingency check on the initial pair by comparing the lookup p50 of b1 against
    a1. D7 established a ratio of 1.2 as the lower bound below which the experimental factor is too
    weak to resolve reliably with 3 replicates per side.

    ```sh
    p50() { jq -r '.histograms[] | select(.operation == "query:px_fleet:lookup" and
      .phase == "steady") | .p50Us' \
      "$(bundle "$HOWTO/ab.tsv" "$1")/drivers/pherf-distributed/report.json"; }
    a=$(p50 a1); b=$(p50 b1)
    awk -v a="$a" -v b="$b" 'BEGIN { printf "a1 %s us b1 %s us ratio %.3f\n", a, b, b / a }'
    ```

    ```text
    a1 1041 us b1 1375 us ratio 1.321
    ```

    In D7, the first pair measured 1347 us and 1706 us, yielding a ratio of 1.267. Because the
    observed ratio exceeds 1.2, running the full suite is worth its execution cost. During D7, this
    check ran concurrently in a second shell while the suite progressed so that px-suite could be
    stopped early if the ratio fell short; a single pilot pair takes roughly 4 min, whereas the
    complete suite takes 12 min.

11. Verify that the two sides differ solely in their schema definition by comparing the run plans
    with their embedded documents removed, and then comparing the document key names.

    ```sh
    A1="$(bundle "$HOWTO/ab.tsv" a1)/drivers/pherf-distributed"
    B1="$(bundle "$HOWTO/ab.tsv" b1)/drivers/pherf-distributed"
    diff <(jq -S 'del(.steps[0].documents)' "$A1/plan-run.json") \
      <(jq -S 'del(.steps[0].documents)' "$B1/plan-run.json"); echo "diff exit $?"
    jq -c '.steps[0].documents | keys' "$A1/plan-run.json" "$B1/plan-run.json"
    ```

    ```text
    diff exit 0
    ["px_fleet_profile.xml","px_fleet_scenario.xml","px_fleet_schema.sql"]
    ["px_fleet_always_schema.sql","px_fleet_profile.xml","px_fleet_scenario.xml"]
    ```

    All workload parameters, including the seed, agents, rate, rows, threads, duration, and
    connection properties, are identical. The two SQL schema texts differ only in their header
    comment and in setting `UPDATE_CACHE_FREQUENCY=60000` versus `=0`.

12. Confirm that the experimental factor took effect in the catalog. The driver records the
    configured table property of each table in `tables.tsv` within the harness bundle.

    ```sh
    for t in a1 b1 a2 b2 a3 b3; do
      echo "$t $(awk -F '\t' 'NR > 1 { print $3 }' "$(bundle "$HOWTO/ab.tsv" "$t")/tables.tsv")"
    done
    ```

    ```text
    a1 60000
    b1 0
    a2 60000
    b2 0
    a3 60000
    b3 0
    ```

13. Copy the controller bundles from the six runs into the replicate set directories `A/` and `B/`.

    ```sh
    mkdir -p "$HOWTO/A" "$HOWTO/B"
    for t in a1 b1 a2 b2 a3 b3; do
      side=$(printf '%s' "$t" | cut -c1 | tr ab AB)
      cp -R "$(bundle "$HOWTO/ab.tsv" "$t")/drivers/pherf-distributed/controller" \
        "$HOWTO/$side/$t"
    done
    ls "$HOWTO/A" "$HOWTO/B"
    ```

14. Run the A/A baseline comparison first by comparing the replicates from part 1 against side A.
    Because both sets executed the exact same schema and workload arguments on the same cluster,
    the analysis must report no regression.

    ```sh
    (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf -analyze R A -label aa) \
      >"$HOWTO/aa.out" 2>/dev/null
    tail -n 3 "$HOWTO/aa.out"
    ```

    ```text
    pherf.analysis.count_parity	PASS	count	pherf	4 count key(s) equal in 2 set(s)
    pherf.analysis.regression	PASS	duration	pherf	9 metric(s) compared, no regression, 3
    improvement(s)
    Analysis: PASS. Report: RESULTS/ANALYSIS_aa.json
    ```

    The regression row above is a single line wrapped for display. The largest percentage delta and
    the widest confidence interval in `RESULTS/ANALYSIS_aa.json` establish the empirical noise
    floor of the design, meaning that any A/B effect falling within those bounds cannot be claimed.
    Extract the per-metric comparison rows using the first `jq` query from
    [H12 step 10][h12-compare]. Each row emits tab-separated percentage values, with any line
    exceeding 100 columns wrapped below before its classification outcome:

    ```text
    query:px_fleet:lookup	p50Us	-5.617977528089888	-8.426966292134832	3.464419475655431	unchanged
    query:px_fleet:lookup	p90Us	-16.597077244258873	-22.860125260960334	0.15657620041753653
    unchanged
    query:px_fleet:lookup	p99Us	-24.49771911093856	-31.13656216635931	-10.016500048529554
    improvement
    query:px_fleet:lookup	p999Us	-25.707828230943086	-42.8682966920074	-0.32874460653379906
    improvement
    response:px_fleet:lookup	p50Us	-6.257309941520467	-25.67251461988304	19.64912280701754	unchanged
    response:px_fleet:lookup	p90Us	-7.074202229911572	-23.60630526720492	12.379853902345252
    unchanged
    response:px_fleet:lookup	p99Us	-12.054945851890192	-19.07428141121866	-9.053925576525122
    improvement
    response:px_fleet:lookup	p999Us	-6.8413440181950635	-12.736544714724852	-3.4934522646102453
    unchanged
    run	opsPerSec	0.02703046492399298	-0.028613668373752146	0.05248091685307942	unchanged
    ```

    Notice that this A/A comparison is not completely uniform: although it reports no regressions,
    the tail latency of side A is 12 to 26% faster than the tail of R. Because the identical schema
    ran on both sides, this shift represents temporal drift rather than a treatment effect: R ran
    first against a cold cluster, whereas A executed 7 to 18 minutes later. Because R and A were not
    interleaved, that drift acted on one side only, illustrating why the A/B suite alternates its
    replicates. Interpret these figures as an upper noise floor: on this host, a tail shift of
    roughly 25% between two non-alternating sets can arise from drift alone, whereas the median
    shifted by only 6% and therefore serves as the stable metric here.

15. Compare set A as the baseline against set B as the candidate.

    ```sh
    (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf -analyze A B -label ab) \
      >"$HOWTO/ab.out" 2>/dev/null
    grep -v '^  ' "$HOWTO/ab.out"
    ```

    ```text
    Set baseline A: run class validation
    Set candidate B: run class validation
    Regression query:px_fleet:lookup steady p50Us +36.4% [+31.2%, +43.8%]
    Regression query:px_fleet:lookup steady p90Us +44.1% [+35.5%, +47.9%]
    Regression query:px_fleet:lookup steady p99Us +34.5% [+25.9%, +41.8%]
    pherf.analysis.count_parity	PASS	count	pherf	4 count key(s) equal in 2 set(s)
    pherf.analysis.regression	ADVISORY	duration	pherf	3 regression(s) of 9 metric(s); worst:
    query:px_fleet:lookup steady p90Us +44.1% [+35.5%, +47.9%]; timing is not authoritative: run
    class validation
    Analysis: ADVISORY. Report: RESULTS/ANALYSIS_ab.json
    ```

    The long verdict line is wrapped here for readability, and `grep -v` omits the individual
    replicate lines. In D7, the analyzer identified one regression on the same key, specifically the
    p50 at +25.5% [+5.6%, +42.9%].

16. Run the comparison in the reverse direction, using set B as the baseline and set A as the
    candidate.

    ```sh
    (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf -analyze B A -label ba) \
      >"$HOWTO/ba.out" 2>/dev/null
    grep -v '^  ' "$HOWTO/ba.out"
    ```

    ```text
    Set baseline B: run class validation
    Set candidate A: run class validation
    Improvement query:px_fleet:lookup steady p50Us -26.7% [-32.1%, -22.8%]
    Improvement query:px_fleet:lookup steady p90Us -30.6% [-33.3%, -24.6%]
    Improvement query:px_fleet:lookup steady p99Us -25.7% [-31.0%, -19.3%]
    pherf.analysis.count_parity	PASS	count	pherf	4 count key(s) equal in 2 set(s)
    pherf.analysis.regression	PASS	duration	pherf	9 metric(s) compared, no regression, 3
    improvement(s)
    Analysis: PASS. Report: RESULTS/ANALYSIS_ba.json
    ```

17. Examine the full set of metric comparisons in the A/B report by running the first `jq` query
    from [H12 step 10][h12-compare] on `$HOWTO/RESULTS/ANALYSIS_ab.json` (again wrapping any line
    over 100 columns before its outcome, as in step 14):

    ```text
    query:px_fleet:lookup	p50Us	36.40873015873016	31.15079365079365	43.75	regression
    query:px_fleet:lookup	p90Us	44.11764705882353	35.48185231539424	47.93491864831039	regression
    query:px_fleet:lookup	p99Us	34.50314950507777	25.915927497107596	41.75343874534001	regression
    query:px_fleet:lookup	p999Us	10.266054538414735	-5.752530560318601	16.815089330162067	unchanged
    response:px_fleet:lookup	p50Us	25.077978789769183	-4.741110417966313	42.669993761696816
    unchanged
    response:px_fleet:lookup	p90Us	19.280099296648736	-2.896152254861398	32.02316921803889	unchanged
    response:px_fleet:lookup	p99Us	9.774447131704484	7.923664242861032	18.739176749539958	unchanged
    response:px_fleet:lookup	p999Us	3.437502622606371	2.7343770861641588	8.828131735329999	unchanged
    run	opsPerSec	-0.03179043743641812	-0.0731203389105039	0.0365549352491087	unchanged
    ```

    In the D7 execution of this same suite, only the p50 metric registered as a regression at
    +25.5% [+5.6%, +42.9%], because its a2 replicate recorded a `query` p90 spike of 4767 us
    against 2637 and 1996 us in the remaining replicates, causing the p90 confidence interval to
    cross 0. Similarly, although the response p50 here shifted by +25.1%, its confidence interval
    crosses 0 and the metric is therefore classified as `unchanged`.

### How to read the A/B

* **One key, one interval.** To qualify as a regression, a metric must exhibit both a median change
  exceeding 10% and a bootstrap confidence interval that excludes 0. Here the service-time p50,
  p90, and p99 all satisfy both conditions, whereas D7 isolated only the p50; every other key
  remains `unchanged`, even where its point change exceeds 10%. The extra catalog RPC adds roughly
  330 us to the median of each lookup (1041 versus 1375 us on the first pair). Because upper-tail
  latency is governed by host noise that can exceed that fixed overhead, the tail effect appears and
  disappears across suite runs: the A/A noise floor from step 14 is roughly 25% in the tail versus
  6% at the median.
* **Apply the A/A floor per key.** Although the analyzer flags three keys, our methodology rejects
  any shift that the A/A comparison also exhibits. Both the p50 (+36.4%; A/A -5.6%, `unchanged`)
  and the p90 (+44.1%; A/A `unchanged`) clear that floor and can be claimed as ADVISORY findings.
  By contrast, the p99 regression is not proven: that same key shifted by roughly 25% in the A/A
  comparison (-24.5% [-31.1%, -10.0%]), and the lower bound of the A/B interval (+25.9%) does not
  meaningfully clear that shift.
* **This A/A overstates the floor of the A/B.** Because sets R and A did not alternate, the A/A
  comparison above captures drift plus random noise, whereas the A/B suite alternates its sides and
  therefore operates over a lower intrinsic noise floor. Establishing a matched floor for an
  interleaved design requires running an interleaved A/A comparison, such as a copy of
  `fleet-ab.suite` configured with the same schema on both sides, before making any claim on p99.
* **The throughput does not move.** Because the offered rate is fixed at 300 per second and the
  cluster keeps pace under both variants, achieved throughput remains unchanged. Observing a
  throughput divergence requires driving a rate above the saturation knee ([H06][h06]).
* **The two directions agree.** Comparing A B yields three regressions, whereas comparing B A
  reports those same keys as improvements of -26.7%, -30.6%, and -25.7%. The percentage magnitudes
  differ because each direction normalizes against a different baseline median.
* **ADVISORY, not FAIL.** A detected regression resolves to FAIL (exit 67) only when timing is
  authoritative, requiring the measurement class and an empty `quiet_host.tags` list on every
  replicate. Because a validation run cannot issue an authoritative time verdict, identical evidence
  produces ADVISORY with exit 0; making a formal claim requires running the suite in the
  measurement class on a quiet host.
* **`count_parity` PASS.** Within each replicate set, the count keys for the co-generated lookup
  metrics (`query` and `response`) match across all replicates, accounting for 2 keys per set and 4
  total in the verdict row across 2 sets. Count parity never applies across distinct sets.

### Part 3: the measurement class on a 24 GiB engine

This section is optional and excluded from the default live subset of this HOWTO because it requires
a dedicated bring-up of the measurement class; the output excerpts below come from the H3 window 2
live run.

Because `px-pherf run` provisions a cluster only when none is currently recorded, leaving the
validation cluster from Part 2 running via `--keep` would cause step 18 to reuse it rather than
rendering a measurement cluster. Tear that validation cluster down before proceeding to step 18:

```sh
px-cluster down
```

Step 18 then provisions its own measurement cluster and tears it down automatically upon
completion, after which the final command in Part 3 starts a fresh validation cluster with
`PX_RS_COUNT=2` for Part 4. If you skip Part 3, Part 4 reuses the cluster kept running from Part 2.

18. Provisioning the measurement class with YARN and the distributed tier requires 24832 MiB of
    container limits, whereas the usable budget of a 24 GiB engine is 19905 MiB (24001 less the
    4096 headroom reservation). Disabling YARN and running with 1 RegionServer reduces the footprint
    to 17920 MiB. Execute the replicates with those overrides:

    ```sh
    px-pherf --class measurement --config dev/harness/docker/env/override-24gib.env \
      -e PX_YARN=0 -e PX_RS_COUNT=1 run fleet | tee "$HOWTO/m.tsv"
    ```

    ```text
    memory_fit	ok	the limits 17920 MiB (17.50 GiB) fit the budget with 1985 MiB (1.94 GiB)
    to spare; the budget 19905 MiB (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the
    headroom 4096 MiB (4.00 GiB)
    ... INFO  px-pherf: the tier runs: one controller and 3 IDLE agents
    1	r1	ADVISORY	0	151445	the verdicts of this test reach ADVISORY	.../runs/...
    2	r2	ADVISORY	0	143499	the verdicts of this test reach ADVISORY	.../runs/...
    3	r3	ADVISORY	0	142748	the verdicts of this test reach ADVISORY	.../runs/...
    result	ADVISORY	0	.../phoenix-harness/sessions/...
    ... INFO  px-pherf: run ends, so the cluster that it brought up comes down
    ... INFO  px-cluster: cluster default is down
    ```

    Running two RegionServers in the measurement class alongside the distributed tier requires an
    engine allocation of at least 25600 MiB (21504 + 4096). Because `--keep` was omitted, the
    command tears the cluster down automatically at the end of the run.

19. Inspect the contamination tags of each harness bundle alongside the merged fleet latencies.

    ```sh
    for t in r1 r2 r3; do
      b="$(bundle "$HOWTO/m.tsv" "$t")"
      echo "$t $(jq -c .quiet_host.tags "$b/manifest.json") $(jq -r '[.histograms[] |
        "\(.operation) p50 \(.p50Us) p99 \(.p99Us)"] | join(" ")' \
        "$b/drivers/pherf-distributed/report.json")"
    done
    ```

    ```text
    r1 ["quiet-host","sizing"] query:px_fleet:lookup p50 1432 p99 16703 response:px_fleet:lookup
    p50 1665 p99 1793023
    r2 ["quiet-host","sizing"] query:px_fleet:lookup p50 1475 p99 12263 response:px_fleet:lookup
    p50 1653 p99 1304575
    r3 ["quiet-host","sizing"] query:px_fleet:lookup p50 1341 p99 10391 response:px_fleet:lookup
    p50 1578 p99 1314815
    ```

    The output lines above are wrapped for readability. The `quiet-host` tag indicates that the
    quiet-host check was disabled, while `sizing` indicates that the active topology diverges from
    the canonical measurement sizing table (1 RegionServer and no YARN). Either tag marks the run
    as contaminated, downgrading every timing verdict to ADVISORY and causing `-analyze` over these
    replicates to report any regression as ADVISORY. Notice that the response p99 reaches 1.3 to
    1.8 s while the service p99 remains 10 to 17 ms: client operations queued waiting for service,
    and response time measures elapsed duration from each operation's scheduled start.

Provision the validation cluster and start the distributed tier again before proceeding to Part 4:

```sh
px-pherf --class validation -e PX_RS_COUNT=2 start >/dev/null
```

### Part 4: faults under fleet load

Because `px-suite` does not provide a dedicated `--disrupt` option, the fault schedule is passed via
the `PX_DISRUPT_SPEC` variable: passing `-e` to `px-pherf` forwards the setting through px-suite to
each `px-run` invocation. Setting `PX_RS_COUNT=2` keeps one RegionServer available while the peer
instance is disrupted. The container image configures ZooKeeper with a maximum session timeout
of 40 s; [H09][h09] explains why a process pause must remain under 25 s to recover cleanly.

20. Pre-create the fleet table prior to the run and identify the RegionServer hosting its region.
    Because each fleet test's prepare verb creates `PX_PHERF.FLEET` using
    `CREATE TABLE IF NOT EXISTS` and its restore verb drops the table afterward, the master
    reassigns the table's single region on every run to either RegionServer. Consequently, a
    process pause impacts the fleet workload only when it targets the specific RegionServer
    hosting that region. The first command below executes the DDL statement from the suite schema
    file via client 0, while the second queries the HBase shell to resolve the region location and
    stores the hosting RegionServer index in `RS`. When step 21 runs, its prepare verb reuses the
    existing table, and its restore verb drops it upon completion.

    ```sh
    px-cluster exec client 0 -- bash -c 'printf "%s;\n" "$1" >/tmp/h15-fleet.sql
      url="$(sed -n "s/^phoenix\.jdbc\.url=//p" "$PX_CONF_DIR/phoenix-client.properties")"
      cp="$PX_CONF_DIR:$PX_PHOENIX_HOME/client/*:$PX_HBASE_HOME/lib/client-facing-thirdparty/*"
      "$PX_JDK_ROOT/$PX_JDK/bin/java" -cp "$cp" org.apache.phoenix.util.PhoenixRuntime "$url" \
        /tmp/h15-fleet.sql' _ "$(cat dev/harness/suites/pherf/px_fleet_schema.sql)" >/dev/null
    RS="$(px-cluster exec client 0 -- bash -c 'printf "%s\n" "$1" |
      JAVA_HOME="$PX_JDK_ROOT/$PX_JDK" hbase shell -n 2>/dev/null' _ \
      "locate_region 'PX_PHERF.FLEET', ''" | sed -n 's/^ *regionserver-\([0-9]*\):.*/\1/p')"
    echo "the fleet table is on regionserver/$RS"
    ```

    ```text
    ...
    ... INFO  [main] client.HBaseAdmin: Operation: CREATE, Table Name: default:PX_PHERF.FLEET,
    procId: 118 completed
    the fleet table is on regionserver/0
    ```

    The Phoenix client invoked by the first command emits its log output to standard error; its
    final log line confirms table creation and is wrapped above for readability. The final output
    line identifies either regionserver/0 or regionserver/1. An empty index indicates that the
    table is missing or that the HBase shell invocation failed; re-run the second command without
    the `sed` filter to inspect the raw shell output.

21. Pause the RegionServer hosting the fleet table for 20 s, starting 15 s after the run verb
    begins.

    ```sh
    D="+15,signal,regionserver,$RS,STOP;+35,signal,regionserver,$RS,CONT"
    px-pherf -e "PX_DISRUPT_SPEC=$D" run fleet --only r1 | tee "$HOWTO/f1.tsv"
    F1="$(bundle "$HOWTO/f1.tsv" r1)"
    jq -c '.disruptions[] | [.ordinal, .argument, .state, .recovery, .mttr_seconds]' \
      "$F1/manifest.json"
    awk -F '\t' '$1 ~ /^(cluster\.recovery|cluster\.post_recovery|pherf\.fleet\.agents)$/ {
      print $1, $6 }' "$F1/report/verdicts.tsv"
    ```

    ```text
    1	r1	ADVISORY	0	113138	the verdicts of this test reach ADVISORY	.../runs/...
    result	ADVISORY	0	.../phoenix-harness/sessions/...
    [1,"STOP","ok","ok",20.539]
    [2,"CONT","ok","ok",1.325]
    pherf.fleet.agents PASS
    cluster.recovery PASS
    cluster.post_recovery PASS
    ```

    The verdict rows appear in the order they are listed in `report/verdicts.tsv`. Because recovery
    from the `STOP` signal cannot complete until the `CONT` signal arrives, its mttr encompasses
    both the ~19 s remaining pause window and the subsequent recovery verification. The sixth column
    of `report/verdicts.tsv` records the effective severity after applying the run-class rule, so
    the run resolves to ADVISORY rather than FAIL because timing rows that exceed their bounds are
    downgraded to ADVISORY in a validation run. Under the same schedule, D7 measured 20.369 and
    1.249 s.

22. Inspect the health verdicts to evaluate what the fleet experienced during the pause.

    ```sh
    awk -F '\t' '$1 ~ /^pherf\.health\.(attainment|saturation|tail_latency)$/ {
      print $1, $6; print "  " substr($5, 1, 150) }' "$F1/report/verdicts.tsv"
    ```

    ```text
    pherf.health.attainment PASS
      lowest attainment 100.0% (step 0 slot 0 (agent pherf-agent-0) scenario px_fleet query set 1
    pherf.health.saturation ADVISORY
      validation run: FAIL reads as ADVISORY; peak lag 19162.907 ms (step 0 slot 1 (agent pherf-a
    pherf.health.tail_latency ADVISORY
      validation run: FAIL reads as ADVISORY; 1 of 2 keys over the bounds p99 1000 ms, p99.9 5000
    ```

    In this execution, step 20 located the fleet table on regionserver/0 and step 21 paused it.
    While regions on the paused RegionServer fail to respond, the open-loop generator continues
    advancing its schedule. The resulting backlog of delayed operations manifests as schedule lag,
    peaking at 19.2 s to match the pause duration, while the response time of blocked operations
    includes the stall and drives the response p99 to 18.6 s. Even so, schedule attainment did not
    drop: all 9000 of 9000 operations completed within 59995 ms of the 60000 ms window. Operating
    with `LATE` catch-up semantics and 6 worker threads driving 150 operations per second, each
    slot drained its accumulated backlog before the window closed, allowing the attainment row to
    pass. Accordingly, inspect peak lag and response tail latency rather than attainment alone to
    evaluate the client-side cost of a pause. Note that the fleet verdict grades the worst schedule
    across all agents rather than an average across agents.

    The measurements above apply only when the pause targets the RegionServer hosting the fleet
    table. Because the restore verb closes the table's region on its hosting RegionServer, the
    instance log containing the region-close message identifies that server and must match
    `regionserver-$RS.log`:

    ```sh
    grep -l 'Closing region PX_PHERF.FLEET' "$F1"/logs/instance/regionserver-*.log
    ```

    ```text
    .../phoenix-harness/runs/20261127T115936Z-p919/logs/instance/regionserver-0.log
    ```

    The matching log file confirms regionserver/0, the same RegionServer identified in step 20. By
    contrast, in an earlier execution of step 21 that paused regionserver/0 without first running
    step 20, the region-close entry appeared in `regionserver-1.log` and the fleet workload was
    largely unaffected by the pause: `pherf.health.saturation` reported ADVISORY with a peak lag of
    1455.552 ms, while `pherf.health.tail_latency` reported PASS with a response p99 of 984.063 ms.

23. Pause RegionServer 0 for 50 s to exceed the ZooKeeper session timeout.

    ```sh
    px-pherf --class validation -e PX_RS_COUNT=2 --keep run fleet.suite -- --only r1 \
      -e 'PX_DISRUPT_SPEC=+15,signal,regionserver,0,STOP;+65,signal,regionserver,0,CONT'
    echo "exit $?"
    S2="$(ls -d "$PX_OUT"/sessions/2* | tail -n 1)"; F2="$(sed -n 1p "$S2/tests/r1/stdout.txt")"
    grep -E 'disruption [0-9]|terminated' "$S2/tests/r1/stderr.log" | cut -c1-120
    awk -F '\t' '$1 ~ /^(cluster\.recovery|pherf\.fleet\.agents)$/ { print $1, $6 }' \
      "$F2/report/verdicts.tsv"
    ```

    ```text
    1	r1	FAIL	1	1014243	exit 1 (failure), messages in .../tests/r1/stderr.log
    exit 1
    ... INFO  px: disruption 1: signal regionserver/0 STOP
    ... INFO  px: disruption 1: regionserver/0 is paused; its recovery waits for a CONT
    ... INFO  px: disruption 2: signal regionserver/0 CONT
    ... ERROR px: disruption 2: no recovery, timeout, exit 4
    pherf.fleet.agents PASS
    cluster.recovery FAIL
    ```

    This test ran for 16 min 54 s. RegionServer 0's ZooKeeper session expired during the pause,
    causing the master to process the server as dead and reassign its regions, including
    `hbase:meta`, to RegionServer 1. Roughly 3.5 s after receiving `CONT`, RegionServer 0 aborted
    with `YouAreDeadException` and exit code 1. The Pherf fleet completed its workload against
    RegionServer 1, so `pherf.fleet.agents` resolves to PASS.

    **Known defect: with 2 RegionServers the gate does not fail at once.** Instead, the recovery
    gate waited for the full `PX_TIMEOUT_READY` duration of 600 s. Inspect the gate log lines:

    ```sh
    grep -E 'barrier|no recovery' "$S2/tests/r1/stderr.log"
    ```

    ```text
    ... ERROR px: the readiness barrier expired after 600s in scope px-default
    ... ERROR px: disruption 2: no recovery, timeout, exit 4
    ```

    The initial readiness poll immediately following `CONT` observed RegionServer 0 still ready
    before the process aborted. Once an instance passes its check, the readiness barrier continues
    waiting only for instances not yet seen ready—here the master, whose readiness probe reports
    `1 of 2 RegionServers checked in`. That probe can never pass with one RegionServer gone, and
    the barrier does not re-check the exited RegionServer. Because the gate's container-exit check
    runs only after the barrier returns, the gate blocks until the timeout bound expires. By
    contrast, in the H3 window 1 run where the sole RegionServer was paused, that RegionServer was
    still in the readiness wait when it exited, allowing the barrier to fail immediately:

    ```text
    ... ERROR px: an instance terminated while the barrier waited: regionserver/0(Exited (1) ...
    ... ERROR px: disruption 2: no recovery, fail, exit 3
    ```

    Thus, the invariant "an exited instance fails the gate at once" holds when the process exits
    while still in the readiness wait, or during the region availability poll (`disruption 2: the
    backend reports regionserver/0 as exited, so it cannot recover`). It does not hold when the
    exited instance was observed ready first and thereby prevents another role from becoming ready.
    Both paths ultimately record `cluster.recovery` as FAIL. Because the planned signal accounts for
    the exit, the anomaly watch reports no anomaly, and px-run exits with code 1 rather than 3.

24. Review the post-count absent-instance check. This verification is drawn from D7 evidence and is
    not executed interactively here because it requires a second shell that deletes RegionServer 0
    at the `CONT` step while a region availability read is in progress. Because a region read can
    begin while the instance is running and finish just after the container disappears, the recovery
    gate re-verifies the instance once the region count is full. The D7 session log shows this path:

    ```text
    21:41:48Z INFO  px: disruption 1: signal regionserver/0 STOP
    21:42:08Z INFO  px: disruption 2: signal regionserver/0 CONT
    21:42:10Z ERROR px: disruption 2: regionserver/0 is absent, so it cannot recover
    21:42:10Z ERROR px: disruption 2: no recovery, fail, exit 1
    ```

    In that bundle, `run-record.tsv` contains `recover 2 1795470128342 <empty> fail`, and
    `cluster.recovery` is FAIL with `2 of 2 disruptions failed ...; the gate ended fail`, failing
    the gate 1.5 to 2.5 s after the `CONT` signal. Prior to adding this check, a region read that
    started while the instance was still alive could pass after the container was removed, yielding
    a false PASS with an mttr of 2.965 s.

### The run bound

A distributed controller run enforces no internal time limit of its own (decision (e) of D7). When
a cluster stops responding, the first failed operation halts the open-loop generator on each agent,
and the run concludes only when all in-flight operations hit their Phoenix client timeouts. The
outer execution limit of a harness run is the verb bound, set by the `timeout` parameter of the
suite test (1200 s for the fleet suites), which px-run shares across the prepare and run verbs.
When the verb bound expires, px-run sends TERM to the driver. The driver catches the signal in
`pd_term`, which posts `cancel` for the active controller run with a timeout bound of 8 s, fitting
inside the 10 s grace period that px-run waits after sending TERM. The controller run transitions to
`CANCELLED`, and the agents return to `IDLE`.

In the H3 window 1 execution, pausing the sole RegionServer for 50 s exercised this path, as shown
in the px-run log:

```text
14:48:00Z ERROR px-run: disruption 2: no recovery, fail, exit 3
14:51:45Z ERROR px-run: the run verb passed its bound
14:51:47Z INFO  px-run: verb run ended with exit 1, code 4
14:56:51Z ERROR px-run: the restore verb passed its bound
```

Following termination, `px-pherf status` confirmed that both agents were `IDLE`. The restore verb
also reached its timeout bound because client 0 cannot drop a table when no RegionServer is
available. Fault scenarios that need to terminate more rapidly should configure shorter client
timeouts inside the `phoenixProperties` block of their query set.

**Known limit (D8 item).** Along this timeout path, `pd_term` cancels the active controller run but
does not copy back the resulting `CANCELLED` controller bundle. That bundle remains inside
pherf-controller 0 until teardown, leaving the outer harness bundle without
`drivers/pherf-distributed/controller/`, a fleet report, or slot logs, so `-analyze` cannot read
such a replicate.

**Known limit (D7).** Following a planned container delete, each poll of the anomaly watch logs a
noisy diagnostic line such as `ERROR px: command failed with status 6 at px-run line 743` (triggered
by the ERR trap of the snapshot lookup). Because the watch still classifies the state change as
planned, only the log output is affected.

## How to read the result

* **Replicates.** Identical slot identities combined with distinct controller runs define a valid
  replicate set. Its count parity must resolve to PASS; any count discrepancy within a set means
  the replicates did not perform identical work.
* **A/B.** Trust a latency shift on a key only when the change exceeds 10%, its confidence interval
  excludes 0, the A/A comparison is clean, and both comparison directions agree. A validation or
  contaminated run yields at most ADVISORY. Always report the metric key, the percentage change,
  the confidence interval, and the run class.
* **Faults.** Recording `cluster.recovery` PASS with an mttr under 120 s, `cluster.post_recovery`
  PASS, and `pherf.fleet.agents` PASS confirms that the cluster recovered and the fleet executed
  its plan. In a fault run, the `pherf.health.*` rows quantify the client-side cost of the fault
  rather than a functional defect.
* **A failed gate.** An exited or absent container fails the recovery gate immediately when the
  gate observes it during the readiness wait or the region poll. A gate that blocks until
  `PX_TIMEOUT_READY` indicates either a genuinely slow recovery or the known defect analyzed in
  step 22; inspect the live instance states with `px-cluster status` before concluding that recovery
  was slow.

## Performance engineering notes

* **Medians of merged histograms.** Each replicate produces one scalar value per metric, read from a
  single histogram that merges the raw samples across all agents, and the analyzer takes the median
  of those values across the replicates. It never averages per-agent percentiles, because the mean
  of two p99 values is not the true p99 of the fleet; [D04][d04] demonstrates why.
* **A/A before A/B.** An A/A comparison measures the baseline noise of the experimental design
  using the exact same tools, cluster, and host. Without it, an apparent regression on one metric
  out of nine can easily occur by chance: with 3 values per set, two independent samples drawn from
  the same distribution fail to overlap on one side with a probability of 1/20.
* **Fixed rate, fixed identities.** Driving an open loop at a fixed rate keeps the offered load
  identical across both sides, ensuring that any latency shift reflects a change in service rather
  than offered load. Likewise, a fixed seed keeps the key space assigned to each agent identical on
  both sides.
* **Resilience under distributed load.** While a fault observed by a single client represents a
  single sample, a distributed fleet experiences the fault across every agent simultaneously, and
  grading worst-schedule attainment highlights the client that suffered most. [D03][d03] covers
  agent faults alongside controller run cancellation and restart.

## How to reproduce it

* Preserve the suite files, the seed 8070, the run class, `PX_RS_COUNT=2`, and the agent count.
* Archive `$HOWTO/R`, `$HOWTO/A`, `$HOWTO/B`, and `$HOWTO/RESULTS/ANALYSIS_*.json` alongside the
  harness bundles. Because both the analysis seed (8070) and the bootstrap iteration count (10000)
  are fixed, re-analyzing the same replicate sets produces identical confidence intervals.
* To support a formal performance claim, execute `fleet-ab.suite` in the measurement class on a
  quiet host configured with the measurement sizing, and run the corresponding A/A comparison on
  that same host.

## Clean up

1. Tear down the active cluster that `--keep` preserved across the preceding sections. The `down`
   command verifies cleanup and exits with code 1 if any cluster resource remains behind.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

2. Delete the temporary working files created for this guide once you no longer need them via
   `rm -rf "$HOWTO"`. The archived run bundles under `$PX_OUT/runs/` remain in place.

## See also

* [H14: the fleet through the harness][h14] covers px-pherf, the distributed driver, and the fleet
  suites.
* [H12: regression, A/B and matrices][h12] explains offline replicate analysis for single-client
  runs.
* [H09: fault injection and recovery][h09] details the recovery gate and the ZooKeeper pause rule.
* [D02: the first fleet run][d02] describes the merged fleet report, the run bundle, and the fleet
  verdict.
* [D03: faults, cancel and restart][d03] and [D04: load-generator scale-out][d04] cover distributed
  tier fault handling and multi-agent scaling.
* [Distributed Pherf HOWTOs][d-index] and the [module README][d-readme] provide the distributed
  tier documentation index and module overview.
* [ANALYSIS.md][analysis] defines the statistical rules, verdicts, and exit codes of `-analyze`.

[h06]: 06-saturation-knee.md
[h09]: 09-fault-injection-and-recovery.md
[h12]: 12-regression-ab-and-matrices.md
[h12-compare]: 12-regression-ab-and-matrices.md#compare-the-sets
[h14]: 14-fleet-through-the-harness.md
[d-index]: ../../../../phoenix-pherf-distributed/docs/howto/README.md
[d02]: ../../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[d03]: ../../../../phoenix-pherf-distributed/docs/howto/03-faults-cancel-and-restart.md
[d04]: ../../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[d-readme]: ../../../../phoenix-pherf-distributed/README.md
[analysis]: ../../../../phoenix-pherf/ANALYSIS.md
