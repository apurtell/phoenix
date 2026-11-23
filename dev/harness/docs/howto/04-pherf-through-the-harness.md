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

# How to run a Pherf scenario through the harness

## The question

This guide addresses how to verify that a Pherf scenario loads and queries a cluster running the
target build as expected, while ensuring that the harness captures the complete set of Pherf
diagnostic evidence within a single result bundle. This HOWTO executes the functional suite
scenario through the harness `pherf` driver. It explains how the driver handles the scenario, the
schema, and `pherf.properties`; identifies where the Pherf latency report, histogram log, and
health verdict are stored inside the bundle; demonstrates how the Pherf pre-run gate rejects a
broken scenario; and illustrates how warmup executions and the connection cache isolate measured
samples from initial start-up overhead. Subsequent HOWTOs build upon the workflow established in
these steps.

## Before you start

**Audience.** This guide is intended for Phoenix developers and committers who validate changes
using Pherf, as well as prospective users evaluating Pherf against a live cluster for the first
time.

**Prerequisites.**

- Complete [How to bring up a cluster and smoke-test a build](02-first-cluster-and-smoke.md) so
  that the harness image for your build has been built (`px-cluster build`) and a validation
  cluster can be provisioned.
- Ensure that no other harness cluster is active on the host, because the harness permits only
  one cluster per host at a time.
- Provide a Docker engine configured with at least 12.5 GiB of memory, which covers the container
  limits of the cluster plus the 4 GiB headroom that the preflight check reserves.
- Install `jq`, which steps 4, 12, and 13 use to query the JSON latency report; the harness itself
  does not require it.

| Cost | Value |
|---|---|
| Wall time | Roughly 12 minutes total: under 1 minute for bring-up and about 1.5 minutes per run. |
| Docker memory | 8704 MiB in container limits for the validation class with 1 RegionServer. |
| Disk | A few MiB per bundle stored under `$PX_OUT/runs/`. |
| Files written | `$HOWTO/broken.xml`, `warm.xml`, `pherf.properties`, 5 bundles, and 1 session. |

Initialize every shell session for this guide with the following three environment setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h04"; mkdir -p "$HOWTO"
```

## Method

**Hypothesis.** The functional scenario loads 1000 rows and executes each of its two queries 20
times, where the count query returns 1000 and the scan query returns 10 rows. The resulting bundle
captures the Pherf latency report, the run log, and the five health verdict rows, and passes
validation under `px-bundle validate`.

**Controls.** The experiment uses the validation run class with 1 RegionServer, the in-tree
scenario and schema definitions, and the default `pherf.properties` configuration. Every run
reapplies the schema, reloads the dataset from scratch, and drops the table during teardown so that
no state or data leaks from one run into another.

**Noise.** Because the validation class disables the quiet-host gate, every run carries the
`quiet-host` contamination tag, which downgrades any failing timing verdict to ADVISORY while
leaving row and operation counts authoritative. In addition, the initial execution of a query
absorbs one-time setup overhead for establishing the connection, resolving table metadata, and
locating regions. That cold-start penalty introduces noise into steady-state latency measurements,
and steps 10 to 14 demonstrate how to isolate and remove it.

**What to measure and why.** The operation count recorded for each latency key and the row count
returned by each query verify the integrity of the data-load and query-execution paths. The
microsecond latency percentiles characterize the service-time distribution. However, a reported
percentile represents a true statistical percentile only when the latency key accumulates enough
samples: p99 requires at least 100 samples, and p99.9 requires at least 1000 samples. When fewer
samples exist, the reported value simply reflects the single slowest sample observed.

**What invalidates the result.** Any execution that exits with code 3 (signaling an anomaly where
the evidence cannot be trusted) or produces a bundle that fails `px-bundle validate` provides no
usable evidence. Any row or operation count that differs from the expected total indicates a genuine
functional defect. Do not treat the latency figures gathered in this HOWTO as authoritative
performance benchmarks, as each comes from a single validation-class execution.

### What the pherf driver does

The driver implements six lifecycle verbs, which are formally specified in the "pherf" section of
`docs/driver-contract.md`.

| Verb | Where | What it does |
|---|---|---|
| `describe` | host | Emits the describe document with the anchors, metric keys, and artifacts. |
| `validate` | host | Checks files exist: `.xml` scenario holds `<scenario>`; schemas are `.sql`. |
| `prepare` | client 0 | Stages files; runs Pherf with `-l -label load` in anchor `setup@load`. |
| `run` | client 0 | Runs `-disableSchemaApply -q -label run` in `steady@pherf`; bundles results. |
| `restore` | client 0 | Drops every table declared by `CREATE TABLE` in the `--schema` files. |
| `report` | host | Reads only the bundled copies and emits the metric records and verdict rows. |

During preparation, the driver stages two categories of workload files into the test working
directory on client 0: it places the `--scenario` file under `stage/scenario/` and each `--schema`
file under `stage/datamodel/`. It also generates `stage/pherf.properties` by taking the contents of
`--properties FILE` (or `phoenix-pherf/config/pherf.properties` when `--properties` is omitted),
stripping the four driver-managed keys, and appending its own authoritative bindings at the end:

| Key | Value that the driver writes last |
|---|---|
| `pherf.default.results.dir` | `<work directory>/results` |
| `pherf.default.summary.file` | `<work directory>/results/summary.html` |
| `pherf.verdict.noisyHost` | Set to `true` when the run is contaminated, and `false` otherwise. |
| `pherf.verdict.serverJmx` | RegionServer info servers with `--read-skew`, or empty otherwise. |

Four operational rules follow directly from this design:

- The driver manages execution labels internally; passing a `-label` option after `--` is rejected
  as a usage error with exit code 2.
- Arguments placed after `--` are forwarded to Pherf. The `-runDurationMs`, `-threadCount`, and
  `-targetRate` options are passed exclusively to the `run` verb because Pherf rejects them when
  `-q` is absent. Every other option, including `-allowUnknownTags`, is forwarded to both execution
  verbs because the Pherf pre-run validation gate executes during both phases.
- The driver stages the scenario file, the schema files, and every profile referenced by the
  scenario. A query set configured with `profile="..."` references a file located next to the
  scenario, and the driver stages it at that same relative path. Specifying a profile with an
  absolute path or a `..` path segment is rejected as a usage error with exit code 2. The
  standalone Pherf HOWTO guide
  [How to drive an open-loop workload with profiles and operation mixes](../../../../phoenix-pherf/docs/howto/03-open-loop-profiles-and-mixes.md)
  covers profiles in detail.
- Specify every input file as an absolute path on the command line, because the driver resolves
  relative paths only relative to a suite definition file.

## Steps

### Run the functional scenario

1. Start a validation-class cluster for this test session. Because the cluster state record persists
   the active run class, subsequent commands do not repeat `--class validation`.

   ```sh
   px-cluster --class validation up
   ```

   ```text
   ... INFO  px-cluster: provisioning cluster default in scope px-default from .../deploy/default
   ```

2. Run the functional scenario through the pherf workload driver, defining `SUITES` once so that
   every file argument is passed as a required absolute path.

   ```sh
   SUITES="$PWD/dev/harness/suites"
   RUN="$(px-run pherf -- --scenario "$SUITES/pherf/px_functional_scenario.xml" \
     --schema "$SUITES/pherf/px_functional_schema.sql")"
   echo "$RUN"
   ```

   ```text
   ... INFO  px-run: run 20261127T092236Z-mb19 ends at ADVISORY (exit 0, ok)
   .../phoenix-harness/runs/20261127T092236Z-mb19
   ```

   The `px-run` command prints the result bundle path to standard output while streaming execution
   progress to standard error, concluding with a line that reports the final outcome and exit code.
   An exit status of 0 confirms that the run finished at PASS, SKIP, or ADVISORY, and the cluster
   remains running for the steps that follow.

3. Inspect the Pherf output files that the driver collected into the bundle.

   ```sh
   ls "$RUN/drivers/pherf/results_HDR" "$RUN/drivers/pherf/results"
   ```

   ```text
   .../drivers/pherf/results:
   RESULT_COMBINED_run_detail.csv  RESULT_Data_Load_Summary_load.csv  RESULT_px_functional_run.xml
   RESULT_px_functional_run_aggregate.csv  RESULT_px_functional_run_detail.csv

   .../drivers/pherf/results_HDR:
   LATENCY_load.hlog  LATENCY_load.json  LATENCY_run.hlog  LATENCY_run.json
   VERDICT_load.tsv   VERDICT_run.tsv
   ```

   The `*_run` artifacts are produced during the `run` verb, and the reporting phase consumes only
   those files. The `*_load` artifacts are generated during the `prepare` verb and are preserved in
   the bundle to support ingest analysis, as covered in
   [How to measure ingest throughput, check query plans and cost a secondary index](08-ingest-plans-and-index-cost.md).
   The `LATENCY_run_intervals.hlog` file is not present because interval histogram export is
   disabled by default.

4. Inspect the operation keys in the JSON latency report, whose schema is defined in
   `phoenix-pherf/LATENCY.md`.

   ```sh
   jq -r '.histograms[] | [.operation, .phase, .count, .p50Us, .p99Us] | @tsv' \
     "$RUN/drivers/pherf/results_HDR/LATENCY_run.json"
   ```

   ```text
   query:px_functional:count	steady	20	4595	1035775
   query:px_functional:scan	steady	20	3673	12615
   ```

   The latency numbers shown above are illustrative. Because each operation key contains only 20
   samples, its reported p99 value equals its single slowest execution. In this sample run, that
   peak latency reflects the cold initial execution of the count query at 1.036 s.

5. Examine the normalized metric records that the driver emitted for each steady-state operation
   key.

   ```sh
   for k in pherf.operations pherf.latency.p50_us pherf.latency.p99_us; do
     px-bundle metrics "$RUN" 'op@*' "$k"
   done
   ```

   ```text
   op@query_px_functional_count	pherf.operations	20	ops
   op@query_px_functional_scan	pherf.operations	20	ops
   op@query_px_functional_count	pherf.latency.p50_us	4595	us
   op@query_px_functional_scan	pherf.latency.p50_us	3673	us
   op@query_px_functional_count	pherf.latency.p99_us	1035775	us
   op@query_px_functional_scan	pherf.latency.p99_us	12615	us
   ```

   Each output row displays the stage anchor, the metric key, the numeric value, and the unit.
   Per-operation stage anchors prefix the operation name with `op@` and normalize any character
   outside `[A-Za-z0-9_.-]` to `_`. The workload-wide `steady@pherf` anchor captures the overall
   summary metrics: aggregate `pherf.operations`, `achieved_qps`, and the worst-key
   `query_latency.p50_ms` and `query_latency.p99_ms` figures. The driver extracts these microsecond
   latency tokens directly from the JSON report without re-decoding the raw histogram logs.

6. Verify the row counts returned by each query from the XML result file.

   ```sh
   awk '
     /<queryResults / { match($0, / id="[^"]*"/); q = substr($0, RSTART + 5, RLENGTH - 6) }
     /<runTimesInMs / { match($0, /resultRowCount="[0-9]+"/)
       n[q]++; r[q] += substr($0, RSTART + 16, RLENGTH - 17) }
     END { for (q in n) printf "%s runs %d rows %d\n", q, n[q], r[q] }
   ' "$RUN"/drivers/pherf/results/RESULT_*_run.xml | sort
   ```

   ```text
   count runs 20 rows 20000
   scan runs 20 rows 200
   ```

   Every execution of the count query returns 1000, and every execution of the scan query returns
   10 rows. These result counts are completely deterministic and remain authoritative regardless of
   host contention. The driver also extracts `pherf.result_rows` for each `group@<queryGroup>`
   anchor from `RESULT_*_aggregate.csv`. However, the XML result file serves as the complete source
   of truth: during a closed-loop run, the per-scenario aggregate CSV can record fewer executions
   than the XML output, so correctness checks should always compare counts against the XML file.

7. Inspect the Pherf health verdict file, which evaluates the five health checks specified in
   `phoenix-pherf/VERDICT.md`.

   ```sh
   cut -f 1-3 "$RUN/drivers/pherf/results_HDR/VERDICT_run.tsv"
   ```

   ```text
   pherf.health.attainment     SKIP      duration
   pherf.health.saturation     SKIP      duration
   pherf.health.client_pool    PASS      count
   pherf.health.tail_latency   ADVISORY  duration
   pherf.health.read_skew      SKIP      share
   ```

   The attainment and saturation checks require an open-loop arrival schedule and therefore evaluate
   to SKIP under a closed-loop workload, while the read-skew check requires `--read-skew`. The
   tail-latency check breaches its 1000 ms p99 threshold due to the cold first query execution, and
   the noisy-host flag set on this contaminated run downgrades that FAIL outcome to ADVISORY:

   ```sh
   grep pherf.health.tail_latency "$RUN/report/verdicts.tsv" | cut -f 5
   ```

   ```text
   noisy host (pherf.verdict.noisyHost): FAIL reads as ADVISORY; 1 of 2 keys over the bounds
   p99 1000 ms, p99.9 5000 ms; worst query:px_functional:count p99 1035.775 ms, p99.9 1035.775 ms
   ```

   The verdict detail occupies a single line in the file and is wrapped above for readability. The
   driver appends all five Pherf health rows verbatim to `verdicts.tsv` under the `pherf` source
   identifier, and supplements them with its own driver-level verdict rows for `pherf.verdict`,
   `pherf.latency`, and `pherf.results`.

### Refuse a broken scenario at the gate

8. Create a scenario containing a misspelled XML attribute, `numberOfExecution`, and execute it
   through the harness.

   ```sh
   cat >"$HOWTO/broken.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="px_broken">
     <datamapping>
       <column>
         <type>VARCHAR</type>
         <dataSequence>RANDOM</dataSequence>
         <length>15</length>
         <name>GENERAL_VARCHAR</name>
       </column>
       <column>
         <type>INTEGER</type>
         <dataSequence>RANDOM</dataSequence>
         <minValue>1</minValue>
         <maxValue>1000000</maxValue>
         <name>GENERAL_INTEGER</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_PHERF.FUNCTIONAL" rowCount="1000" name="px_broken">
         <querySet concurrency="1" executionType="SERIAL" numberOfExecution="20">
           <query id="count" statement="select count(*) from PX_PHERF.FUNCTIONAL"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   RUN_BAD="$(px-run pherf -- --scenario "$HOWTO/broken.xml" \
     --schema "$SUITES/pherf/px_functional_schema.sql")"
   grep pherf.gate "$RUN_BAD/report/verdicts.tsv" | cut -f 1-5
   grep -h xml.unknown-attribute "$RUN_BAD/logs/prepare.log"
   ```

   ```text
   ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
   pherf.gate  FAIL  none  driver:pherf  the Pherf pre-run gate refused the scenario files
   (exit 65), the findings are in logs/prepare.log
   xml.unknown-attribute .../broken.xml:20 .../querySet[1]/@numberOfExecution: attribute
   numberOfExecution is not an attribute of <querySet> in the Pherf model, so the parser ignores it
   ```

   Each verdict row and diagnostic finding appears as a single line in the output logs and is
   wrapped above for display. Pherf terminates with exit code 65 during pre-run validation before
   opening any cluster connection, and the driver translates that failure into a `pherf.gate` row
   and an overall exit code of 1. Because the Pherf XML parser silently ignores unrecognized
   attributes, running without the pre-run gate would cause this typo to fall back to the default
   of 10 executions without any warning. Passing `-allowUnknownTags` permits unrecognized element
   or attribute names through the gate, which should be reserved strictly for running scenarios
   authored for a newer Pherf version rather than masking typos.

9. Attempt to forward a custom `-label` argument to Pherf and verify that the driver rejects it.

   ```sh
   RUN_LABEL="$(px-run pherf -- --scenario "$SUITES/pherf/px_functional_scenario.xml" \
     -- -label mine)"
   grep -h "remove '-label'" "$RUN_LABEL/logs/validate.log"
   ```

   ```text
   ... INFO  px-run: run ... ends at ERROR (exit 2, usage)
   ... ERROR pherf: the driver sets the Pherf -label of each verb, remove '-label' (exit 2, usage)
   ```

### Keep start-up cost out of the steady samples

10. Create a modified copy of the functional scenario configured to run 5 warmup executions per
    query on each worker thread.

    ```sh
    cat >"$HOWTO/warm.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="px_warm">
      <datamapping>
        <column>
          <type>VARCHAR</type>
          <dataSequence>RANDOM</dataSequence>
          <length>15</length>
          <name>GENERAL_VARCHAR</name>
        </column>
        <column>
          <type>INTEGER</type>
          <dataSequence>RANDOM</dataSequence>
          <minValue>1</minValue>
          <maxValue>1000000</maxValue>
          <name>GENERAL_INTEGER</name>
        </column>
      </datamapping>
      <scenarios>
        <scenario tableName="PX_PHERF.FUNCTIONAL" rowCount="1000" name="px_warm">
          <querySet concurrency="1" executionType="SERIAL" executionDurationInMs="10000"
                    numberOfExecutions="20" warmupExecutions="5">
            <query id="count" queryGroup="count" expectedAggregateRowCount="1000"
                   statement="select count(*) from PX_PHERF.FUNCTIONAL"/>
            <query id="scan" queryGroup="scan"
                   statement="select ID, AMOUNT from PX_PHERF.FUNCTIONAL
                              where AMOUNT &gt; 500000 limit 10"/>
          </querySet>
        </scenario>
      </scenarios>
    </datamodel>
    EOF
    ```

11. Prepare a custom `pherf.properties` file for the test by copying the default configuration and
    appending the property overrides at the end. When a key appears multiple times, the final line
    takes precedence, and the driver appends its four managed keys after your entries.

    ```sh
    cp phoenix-pherf/config/pherf.properties "$HOWTO/pherf.properties"
    cat >>"$HOWTO/pherf.properties" <<'EOF'
    pherf.default.threadpool=16
    EOF
    ```

    Enabling `-connectionCache` retains at most `pherf.default.threadpool` idle connections in the
    cache, so size this pool to at least match the concurrency of the largest query set. Because a
    file supplied via `--properties` completely replaces the default properties file, always start
    from a full copy of the default configuration.

12. Execute the warmup-enabled scenario without enabling the connection cache, isolating warmup as
    the single variable changed relative to the cold baseline run.

    ```sh
    RUN_WARM="$(px-run --keep -c validation -d pherf -- --scenario "$HOWTO/warm.xml" \
      --schema "$PWD/dev/harness/suites/pherf/px_functional_schema.sql" \
      --properties "$HOWTO/pherf.properties")"; echo "exit $?"
    grep -E '"(operation|phase|count|maxUs)"' \
      "$RUN_WARM/drivers/pherf/results_HDR/LATENCY_run.json"
    ```

    ```text
    exit 0
          "operation": "query:px_warm:count",
          "phase": "steady",
          "count": 20,
          "maxUs": ...,
          "operation": "query:px_warm:count",
          "phase": "warmup",
          "count": 5,
          "maxUs": ...,
    ...
    ```

    Because the live verification pass did not record numeric output for this intermediate run, the
    excerpt above illustrates the structure of the report. The `steady` phase keys record only the
    20 measured executions, whereas the `warmup` phase keys capture the 5 warmup executions,
    including the cold initial query. Because p99 equals the single slowest execution across 20
    samples, compare the `maxUs` field directly: the difference between the warmup maximum and the
    steady-state maximum reflects the effect of warmup alone.

13. Execute the same warmup-enabled scenario with the connection cache enabled, introducing
    connection caching as a second controlled change.

    ```sh
    RUN_CACHE="$(px-run --keep -c validation -d pherf -- --scenario "$HOWTO/warm.xml" \
      --schema "$PWD/dev/harness/suites/pherf/px_functional_schema.sql" \
      --properties "$HOWTO/pherf.properties" -- -connectionCache)"; echo "exit $?"
    grep -E '"(operation|phase|count|maxUs)"' \
      "$RUN_CACHE/drivers/pherf/results_HDR/LATENCY_run.json"
    ```

    ```text
    exit 0
          "operation": "query:px_warm:count",
          "phase": "steady",
          "count": 20,
          "maxUs": 9607,
          "operation": "query:px_warm:count",
          "phase": "warmup",
          "count": 5,
          "maxUs": 1049599,
    ...
    ```

    The numeric values shown above are illustrative. In this run, the maximum latency of the count
    query drops from roughly 1.1 s during the warmup phase to about 10 ms during the steady-state
    phase. Comparing the steady-state maximum of this run against the steady-state maximum from
    step 12 isolates the effect of the connection cache; report the warmup effect and the cache
    effect separately. Because each run constitutes only a single trial, variations of a few
    milliseconds between runs represent experimental noise.

    Running 5 warmup executions absorbs the initial cold-start overhead of establishing the
    connection, fetching table metadata, and caching region locations, but it is not sufficient to
    bring the client or RegionServer JIT compilers to steady state. To size warmup duration
    properly for a formal latency claim, inspect an interval histogram log as demonstrated in
    [H05, step 15](05-latency-slo-and-soak.md#grade-the-slo).

14. Verify that warmup phase keys are excluded from the normalized metric records and health
    verdict evaluation.

    ```sh
    px-bundle metrics "$RUN_CACHE" 'op@*' pherf.operations
    cut -f 1,2 "$RUN_CACHE/drivers/pherf/results_HDR/VERDICT_run.tsv" | grep tail_latency
    ```

    ```text
    op@query_px_warm_count	pherf.operations	20	ops
    op@query_px_warm_scan	pherf.operations	20	ops
    pherf.health.tail_latency  PASS
    ```

    The driver report generator, the tail-latency health check, and offline analysis tools consume
    only the `steady` phase keys.

### Run the same test through the suite

15. Execute only the `pherf` test entry from the functional suite definition.

    ```sh
    px-suite functional --only pherf
    ```

    ```text
    ... WARN  px-suite: test 'pherf' needs 'schema', which the selection excludes
    1  pherf  ADVISORY  0  38008  the verdicts of this test reach ADVISORY  .../runs/...
    result  ADVISORY  0  .../phoenix-harness/sessions/...
    ... INFO  px-suite: session ... ends at ADVISORY: 0 pass, 0 skip, 1 advisory, 0 fail, 0 error
    (exit 0, ok)
    ```

    The sequence index and elapsed time shown above are illustrative. The final column records the
    test's result bundle path, and the `result` row identifies the session directory. Passing
    `--only` limits execution to the named tests; when a filtered selection omits a declared
    prerequisite test, the runner emits a warning instead of skipping the test because you requested
    it explicitly. Here, the `pherf` test does not rely on tables from the `schema` test because its
    own `--schema` option creates the required table. Because `px-suite` resolves relative paths in
    a test's `args:` field against the suite file's directory, suite definitions do not need
    absolute paths. The session runs on the cluster from step 1 and leaves it active until the
    clean-up step tears it down.

## Read the result

- **Pass.** A passing run exits with code 0, produces the expected query counts in step 6 (20
  executions per query and 1000 rows returned per count query), records `pherf.latency PASS` and
  `pherf.verdict PASS` alongside the five Pherf health rows, and omits any `pherf.gate` row.
- **Fail.** A `pherf.gate FAIL` verdict indicates that the scenario violated a pre-run validation
  rule, whose diagnostics appear in `logs/prepare.log`. A `pherf.load` or `pherf.execution FAIL`
  verdict indicates that Pherf failed after passing the validation gate; inspect `logs/prepare.log`
  or `logs/run.log` for the failure details. Any operation or row count that diverges from the
  expected total indicates a functional correctness defect in either the build or the scenario.
- **The times.** On a validation run, any failing timing verdict is downgraded to ADVISORY. As
  shown in step 4, a single run with only 20 samples cannot substantiate a tail-latency claim
  because its p99 metric reflects a single sample. True steady-state measurement begins only after
  cold-start overhead has been absorbed through warmup executions.
- **Older Pherf.** When running against an older Pherf build that does not emit
  `LATENCY_run.json`, the driver falls back to extracting metrics from the aggregate CSV and marks
  `pherf.latency` as SKIP.

## Reproduce it

- Because the baseline scenario and schema reside in the repository tree, record the Git commit
  hash used for the test, and archive the generated `warm.xml`, `broken.xml`, and
  `pherf.properties` files from `$HOWTO` alongside the result bundles.
- Inside each bundle, `cluster/topology.env` and `cluster/images.tsv` record the component version
  set and container image digests, while `manifest.json` captures the run class and host
  contamination tags.
- The rule-based data generators in this scenario draw unseeded random values, so the generated row
  contents vary across runs while the row counts remain invariant. For workloads that require
  deterministic seeded data generation, see
  [How to prove read-what-you-wrote correctness under load](07-read-what-you-wrote.md).
- Archive the `$RUN` result bundle directory; you can verify the structural integrity of any copy
  restored under `PX_OUT` by running `px-bundle validate BUNDLE`.

## Clean up

1. Tear down the validation-class cluster provisioned in step 1:

   ```sh
   px-cluster down
   ```

   ```text
   ...
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [How to validate a latency SLO with an open loop, and hold it in a soak](05-latency-slo-and-soak.md)
- [How to read, validate, archive and automate on result bundles](03-result-bundles-and-automation.md)
- [The driver contract, section "pherf"](../driver-contract.md) and
  [the result bundle, section "The pherf driver files"](../bundle-format.md)
- [Pherf latency outputs](../../../../phoenix-pherf/LATENCY.md) and
  [the Pherf health verdict](../../../../phoenix-pherf/VERDICT.md)
- [How to author a scenario and validate it offline](../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md)
