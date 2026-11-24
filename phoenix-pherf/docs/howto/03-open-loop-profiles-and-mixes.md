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

# How to drive an open-loop workload with profiles and operation mixes

## The question

How does a cluster respond when subjected to the arrival rate and operation mix of a production
workload? A closed-loop benchmark cannot answer this question. In a closed-loop execution model,
each worker thread waits for its current operation to complete before issuing the next request; as a
result, a slow cluster automatically throttles the offered load, and the client never experiences
the queueing delays that real users encounter. By contrast, an open-loop model dispatches operations
at a declared arrival rate regardless of how the cluster responds. This HOWTO explains how to define
the arrival process and operation mix for a workload, how to execute that workload with Pherf, and
how to interpret the resulting schedule metrics to verify that the client actually delivered the
declared load.

## Before you start

**Audience.** This guide is intended for prospective Pherf users who are modeling production
workloads and for Phoenix developers who need to generate load at a known, controlled rate.

**Prerequisites.**

* You need a Phoenix cluster whose ZooKeeper quorum is reachable from your host, along with the
  compiled build artifacts and the `$PX_OUT/howto/pherf-env.sh` environment script prepared in
  [P01](01-first-pherf-run.md), step 3, and JDK 17 configured in `JAVA_HOME`.
* If you do not have an existing cluster, you can provision a local harness cluster as shown in
  [H02](../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md) by running
  `px-cluster --class validation up` and selecting option B in step 2. Option B invokes Pherf
  directly inside the client container so that every step executes as written with its own
  command-line options. Note that option B has been verified against the harness implementation
  code, although it was not executed against a live cluster. While the harness Pherf driver also
  supports scenarios that reference an external profile ([H04][h04]), the driver manages the
  `-label`, `-l`, and `-q` flags for each verb internally, so this guide uses option B instead.
* Review [P02](02-scenario-authoring-and-validation.md) for background on the scenario XML format
  and the offline validator.
* Ensure that the tables `PHERF.REF_RELATIONAL`, `PHERF.HOWTO_MIX`, and `PHERF.HOWTO_LAG` do not
  contain data you need to preserve, because the steps in this guide create and modify them.

**Cost.**

* Wall time: Approximately 15 min of benchmark executions, plus roughly 5 min if bringing up a
  local harness cluster.
* Docker memory: None when targeting an external cluster; a local harness validation cluster with
  1 RegionServer reserves 8704 MiB of container memory limits.
* Disk: Less than 50 MB in the working directory.
* Files written: `$HOWTO` stores the scenario and profile definitions created during the steps,
  while `$HOWTO/work` holds the generated `RESULTS/` and `RESULTS_HDR/` directories.
* Tables written: `PHERF.REF_RELATIONAL` (up to 20,000 rows), `PHERF.HOWTO_MIX` (10,000 rows), and
  `PHERF.HOWTO_LAG` (5,000 rows).

## Method

**Characterize the workload first.** Before authoring a workload profile, establish four properties
of the target production workload:

1. The operation mix, which defines the relative proportions of point reads, range scans, deletes,
   and writes.
2. The arrival process, including whether the workload follows a steady rate, stepped plateaus,
   linear ramps, or quiet periods, and whether arrivals occur at regular intervals (`UNIFORM`) or
   follow a random memoryless distribution (`POISSON`).
3. The peak arrival rate and the duration of the peak busy period.
4. The latency objective, expressed as a target percentile and upper bound, such as keeping p99
   below 50 ms.

A workload profile encodes properties 1 through 3, while the health verdict and latency report
evaluate property 4.

**Hypothesis.** Under the declared arrival rate and operation mix, the client dispatches and
completes every scheduled arrival on time while the cluster satisfies the latency objective. Two
experimental outcomes refute this hypothesis: either the schedule metrics record late, dropped, or
aborted operations, or the measured `response` latency exceeds the target bound.

**Controls.** Hold constant the scenario definition, the workload profile, the random seeds
(`coGeneration seed` and the `POISSON` seed), the dataset row count, the Pherf build, and the
cluster configuration. Vary only one control variable at a time between runs.

**Noise.** Competing processes on the client host and concurrent traffic from other clients on the
cluster introduce measurement noise. Running against a local harness validation cluster on a laptop
produces latency timings that are ADVISORY only, whereas operation and schedule counts remain exact
on any host.

**What to measure and why.**

* Inspect `scheduled`, `completed`, `late`, `dropped`, and `aborted` in each `schedules` entry to
  verify whether the client actually delivered the declared load. A benchmark run that fails to
  deliver its intended load does not produce a valid measurement of cluster capacity.
* Examine the `query`, `scan`, and `delete` latency keys to measure service time, which spans from
  the moment a worker begins executing an operation until the final row is consumed.
* Examine the `response` latency keys to measure end-to-end response time, which spans from the
  intended schedule dispatch timestamp until the final row is consumed. This metric reflects the
  latency experienced by an external user, and the difference between `response` time and service
  time represents the queueing delay inside the client.
* Check the completed operation counts for each operation kind to confirm that the workload mix
  executed in the declared proportions.

**What invalidates the result.**

* A schedule entry with `late`, `dropped`, or `aborted` greater than 0 invalidates any claim that
  the cluster handled the declared arrival rate. Always set `maxLagMs` to your acceptable lag
  budget; without it, the `late` counter remains at 0 and lag is evaluated only against
  `pherf.verdict.lag.maxMs` in the saturation verdict check.
* A `maxQueueDepth` equal to the configured concurrency indicates that the worker pool was saturated
  and acted as the bottleneck. To distinguish a cluster bottleneck from a client bottleneck, compare
  service time trends against host processor utilization: if service time increases with the arrival
  rate while server nodes are heavily utilized, the cluster is the limiting factor; conversely, if
  workers are active only a fraction of the time (meaning the arrival rate multiplied by the mean
  service time, divided by concurrency, is well below 1) while the client host is saturated, the
  client itself is the bottleneck.
* Any `stopReason` value other than `duration` indicates an abnormal or premature termination.
* Using a profile other than the one intended invalidates comparisons. The generated XML result file
  echoes the effective profile path, rate curve, and operation mix that actually executed, so
  inspect those echoed elements after any run that uses command-line launch overrides.

### The open-loop model in brief

* Adding an `<openLoop>` element to a query set switches it to open-loop execution. In this mode,
  `concurrency` must be specified as a single integer that defines both the number of worker threads
  and the maximum capacity of the internal dispatch queue; there are no additional concurrency
  tunables.
* Each `<stage durationMs="D" rate="R"/>` element defines a constant-rate stage. Supplying `endRate`
  converts the stage into a linear ramp from `rate` to `endRate`, while setting a rate of 0
  introduces a quiet pause. Stages execute sequentially in the order they appear.
* Setting `arrival="UNIFORM"` (the default) spaces arrivals at equal deterministic intervals.
  Setting `arrival="POISSON"` models a Poisson arrival process over the same rate curve and requires
  a `seed` attribute; supplying the same seed reproduces the exact same sequence of arrival times
  across runs.
* The `maxLagMs` attribute defines the maximum tolerated dispatch lag, measured from an operation's
  intended dispatch timestamp until a worker thread dequeues it. Under `lagPolicy="LATE"` (the
  default), an operation that exceeds this threshold still executes and increments the `late`
  counter. Under `lagPolicy="DROP"`, an operation that exceeds this threshold is discarded without
  running and increments the `dropped` counter; using `DROP` requires `maxLagMs` to be set.
* Legacy closed-loop attributes (`executionType`, `numberOfExecutions`, and `executionDurationInMs`)
  have no effect on an open-loop query set because the total execution duration is determined
  entirely by its stages.
* A `<mix>` element specifies the relative share of each query kind using
  `<operation kind="SELECT|SCAN|DELETE" weight="w"/>` entries. Pherf divides all weights by their
  greatest common divisor to form a repeating cycle of W arrivals and schedules operations within
  each cycle using smooth weighted round-robin selection. After N total arrivals, the execution
  count for an operation kind with reduced weight w is guaranteed to be within 1 of N x w / W, and
  equals N x w / W exactly whenever W divides N. Defining a mix requires an open-loop query set.
* An external `<workloadProfile>` XML file encapsulates a single `<openLoop>` definition along with
  an optional `<mix>`. A query set references the profile via `profile="file.xml"`, using either an
  absolute path or a path relative to the scenario file. When referenced, the profile serves as the
  exclusive source for both the arrival curve and the operation mix; if a query set specifies a
  profile alongside an inline `openLoop` or `mix`, validation fails and no merging takes place.
* The command-line launch overrides `-runDurationMs`, `-threadCount`, and `-targetRate` transform
  every query set after XML parsing completes. These flags take precedence over both profile and
  inline query set settings, and they require `-q` to be enabled.

For comprehensive specification details, consult [README.md](../../README.md) under the sections
"Open loop query sets", "Workload profiles", and "Launch overrides". For metric naming and schedule
report structure, consult [LATENCY.md](../../LATENCY.md).

### Other ways to mix reads and writes

While a `mix` schedules point reads, range scans, and deletes at a controlled open-loop arrival
rate, Pherf also retains two older mechanisms for incorporating write operations. Because neither
mechanism enforces a declared arrival rate, use them only when their specific execution model is
required.

* **`writeParams` (read/write mixed).** Including `<writeParams>` in a scenario launches a
  background write workload with `writerThreadCount` concurrent writer threads for every measured
  query execution, running alongside the query sets. Consequently, total write volume scales with
  the number of query executions rather than following a declared arrival rate. See
  [P04](04-co-generation.md) for details on how background writers populate rows under
  co-generation.
* **The load profile path (`-mt`).** A scenario configured with `<loadProfile>` executes a fixed
  total of `numOperations` operations, selecting each operation according to the `weight` defined in
  its `<opDistribution>` across upserts, point queries, `SCAN` and `DELETE` queries, idle intervals,
  and user-defined operations over a configured tenant distribution. This execution path operates as
  a closed loop: it supports no arrival rate, no `openLoop`, and no `mix`, and it rejects
  `coGeneration`. It records latency metrics under keys of the form `operation:<scenario>:<group>`,
  and is demonstrated in [P05](05-types-and-multi-tenant.md).

## Steps

### Set up

1. Execute the environment initialization commands from the root of the Phoenix repository and
   change into the working directory:

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p03"; mkdir -p "$HOWTO/work"
   . "$PX_OUT/howto/pherf-env.sh"
   cd "$HOWTO/work"
   ```

   Sourcing `pherf-env.sh` defines the `pherf` shell function and sets `PHX_ROOT` to the repository
   root directory. Because Pherf writes its `RESULTS` and `RESULTS_HDR` output directories relative
   to the current working directory, every Pherf command in this guide executes from `$HOWTO/work`.

2. Select the target execution environment for Pherf by choosing one of the two options below.

   **Option A: your cluster.** Set `ZK` to the ZooKeeper quorum address of your cluster:

   ```sh
   ZK="zk1.example.com,zk2.example.com,zk3.example.com:2181"
   ```

   **Option B: a harness cluster.** This configuration has been verified against the harness source
   code, but it was not executed against a live cluster during the verification of these guides. If
   any step fails under option B, run that step against your own cluster and report the issue.
   Because a JDBC client running on the host machine cannot directly route to an internal harness
   cluster, Pherf must run inside container client 0. The `pherf` wrapper function defined below
   replaces the function loaded from `pherf-env.sh`: it archives and copies `$HOWTO` into client 0,
   rewrites any `$HOWTO` paths in its command-line arguments and in `PHERF_CONF` to point to the
   container copy, invokes Pherf using the cluster's JDBC URL in place of the `-z` argument, and
   copies the generated `RESULTS` and `RESULTS_HDR` directories back to the host's current working
   directory while preserving Pherf's exit code:

   ```sh
   export PATH="$PHX_ROOT/dev/harness/bin:$PATH"
   ZK=client-0
   PHERF_CLIENT_SCRIPT='h=$1 c=$2 w=$3
   shift 3
   d=/tmp/pherf-work/${h##*/}-${w##*/}
   [ "${1:-}" = -z ] && shift 2
   args=("${@//"$h"/$d}")
   url=$(sed -n "s/^phoenix\.jdbc\.url=jdbc:phoenix://p" "$PX_CONF_DIR/phoenix-client.properties")
   lib=$PX_HBASE_HOME/lib
   codec=("$lib"/commons-codec-*.jar)
   cp="$PX_CONF_DIR:$PX_PHOENIX_HOME/client/*:$lib/client-facing-thirdparty/*:$lib/jdk11/*:$codec"
   [ -z "$c" ] || cp="${c/"$h"/$d}:$cp"
   cd "${w/"$h"/$d}" || exit 1
   exec "$PX_JDK_ROOT/$PX_JDK/bin/java" $(cat "$PX_CONF_DIR/jvm.opts") -cp "$cp" \
     org.apache.phoenix.pherf.Pherf -z "$url" "${args[@]}"'
   pherf() {
     local n="${HOWTO##*/}-${PWD##*/}" rc=0
     COPYFILE_DISABLE=1 tar --no-xattrs --exclude RESULTS --exclude RESULTS_HDR -C "$HOWTO" \
       -cf - . | px-cluster exec client 0 -- bash -c 'd="/tmp/pherf-work/$1"
         rm -rf "$d" && mkdir -p "$d" && tar -xf - -C "$d"' put "$n" || return
     px-cluster exec client 0 -- bash -c "$PHERF_CLIENT_SCRIPT" pherf "$HOWTO" \
       "${PHERF_CONF:-}" "$PWD" "$@" </dev/null || rc=$?
     px-cluster exec client 0 -- bash -c 'cd "/tmp/pherf-work/$1" && tar -cf - $(ls -d \
       RESULTS RESULTS_HDR 2>/dev/null) 2>/dev/null' get "$n${PWD#"$HOWTO"}" </dev/null |
       tar -xf - 2>/dev/null
     return "$rc"
   }
   ```

   This wrapper constructs the exact same Java classpath as the harness Pherf driver. Both your
   current working directory and `PHERF_CONF` (when set) must reside under `$HOWTO`. Because each
   working directory is staged to a distinct subdirectory inside client 0, two working directories
   can execute concurrently without colliding. Note that `px-cluster exec` enforces a command
   timeout of `PX_TIMEOUT_EXEC` seconds, which defaults to 600 and exceeds the runtime of every step
   in this guide.

### Run the reference workload

3. Copy the relational reference scenario and its accompanying smoke profile into `$HOWTO`. Both
   files must reside in the same directory because the `profile` attribute in the scenario resolves
   relative to the scenario file's location:

   ```sh
   cp "$PHX_ROOT"/phoenix-pherf/config/reference/relational_{scenario,smoke_profile}.xml "$HOWTO/"
   ```

   The scenario file [relational_scenario.xml](../../config/reference/relational_scenario.xml) loads
   1,000 co-generated rows and executes a single open-loop query set at concurrency 1 containing
   three queries: a point lookup, a range `SCAN`, and a range `DELETE`. The profile file
   [relational_smoke_profile.xml](../../config/reference/relational_smoke_profile.xml) configures a
   2,000 ms stage at 50 operations per second with a 70/20/10 operation mix.

4. Validate the scenario and its referenced profile offline without opening any cluster connections:

   ```sh
   pherf -z "$ZK" -validate -disableSchemaApply -label ref \
     -scenarioFile "$HOWTO/relational_scenario.xml"
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 2 file(s). Report: RESULTS/VALIDATION_ref.json
   ```

   The validator inspects both the scenario file and the referenced profile file. Every live Pherf
   run executes this identical validation check as a pre-run gate and exits immediately with status
   code 65 if any validation rule fails.

5. Load the dataset and execute the open-loop query set:

   ```sh
   pherf -z "$ZK" -l -q -disableSchemaApply -label smoke \
     -scenarioFile "$HOWTO/relational_scenario.xml"
   ```

   Because the pre-scenario DDL inside the scenario file creates the target table directly, no
   external schema file is required (`-disableSchemaApply`).

6. Inspect the completed operation counts recorded for each latency key:

   ```sh
   awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"count":/ {print o, p, $2}' \
     RESULTS_HDR/LATENCY_smoke.json | tr -d '",'
   ```

   Expected output (not captured from a live cluster):

   ```text
   delete:referenceRelational:rangeDelete steady 10
   load:referenceRelational:PHERF.REF_RELATIONAL steady 20
   query:referenceRelational:lookup steady 70
   response:referenceRelational:lookup steady 70
   response:referenceRelational:rangeDelete steady 10
   response:referenceRelational:rangeScan steady 20
   scan:referenceRelational:rangeScan steady 20
   ```

   With 100 total arrivals and mix weights of 70/20/10 (giving a reduced cycle length of W = 10),
   Pherf dispatches exactly 70 lookups, 20 scans, and 10 deletes. The `load` latency key counts
   committed write batches: loading 1,000 rows in co-generated blocks of 50 rows produces 20 batch
   commits. Every open-loop service time key is paired with a corresponding `response` key that
   records the exact same operation count.

7. Inspect the schedule summary for the query set:

   ```sh
   keys='querySet|concurrency|stopReason|durationMs|scheduled|completed|late|dropped|aborted'
   grep -E "\"($keys|maxQueueDepth|maxDriftUs)\":" RESULTS_HDR/LATENCY_smoke.json | tr -d ' ,'
   ```

   Expected output (not captured from a live cluster):

   ```text
   "querySet":1
   "concurrency":1
   "stopReason":"duration"
   "durationMs":2000
   "scheduled":100
   "completed":100
   "late":0
   "dropped":0
   "aborted":0
   "maxQueueDepth":1
   "maxDriftUs":4874
   ```

   The `maxDriftUs` metric measures scheduling jitter in microseconds and varies across hosts (the
   value shown here is illustrative). The accounting identity
   `scheduled = completed + dropped + aborted + pending` holds at all times, and `pending` has
   drained to 0 by the time the final report is written.

### Scale the reference workload at launch

8. Execute the same scenario for ten times the duration and at twice the peak arrival rate without
   modifying either XML file:

   ```sh
   pherf -z "$ZK" -l -q -disableSchemaApply -label scaled \
     -scenarioFile "$HOWTO/relational_scenario.xml" \
     -runDurationMs 20000 -targetRate 100 -rowCountOverride 20000
   ```

   Passing `-runDurationMs 20000` proportionally stretches the stage durations to a total of
   20,000 ms while preserving the relative shape of the curve. Passing `-targetRate 100` scales
   every stage rate by a factor of 100 / 50 (the ratio of the new peak rate to the original peak
   rate). Together, the total number of scheduled arrivals increases by a factor of 10 x 2 = 20,
   reaching 2,000 operations.

9. Verify the scaled operation counts:

   ```sh
   awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"count":/ {print o, p, $2}' \
     RESULTS_HDR/LATENCY_scaled.json | tr -d '",' | grep -v '^response'
   ```

   Expected output (not captured from a live cluster):

   ```text
   delete:referenceRelational:rangeDelete steady 200
   load:referenceRelational:PHERF.REF_RELATIONAL steady 400
   query:referenceRelational:lookup steady 1400
   scan:referenceRelational:rangeScan steady 200
   ```

   This result illustrates the dataset scaling rule for the reference scenarios. Because each
   `DELETE` operation removes an entire block of rows and subsequent reads fail if the table is
   exhausted, the initial row count must always exceed the number of deletes multiplied by the block
   size. Here, 200 deletes remove 200 x 50 = 10,000 rows, meaning `-rowCountOverride` must exceed
   10,000 and should scale by at least the same factor as the total arrivals. Loading 20,000 initial
   rows leaves 10,000 rows intact at the end of the run.

The table below summarizes the three reference scenarios and their default smoke profiles:

| Scenario | Smoke profile | Arrivals | SELECT / SCAN / DELETE | Block | Rows left of 1,000 |
|---|---|---|---|---|---|
| [relational][ref-rel] | (a) | 100 | 70 / 20 / 10 | 50 | 500 |
| [json][ref-json] | (b) | 60 | 30 / 18 / 12 | 40 | 520 |
| [bson][ref-bson] | (c) | 60 | 36 / 12 / 12 | 25 | 700 |

The corresponding smoke profiles configure the following schedules: (a) runs for 2000 ms at a
steady rate of 50/s with a 70/20/10 operation mix; (b) runs for 1000 ms at 20/s followed by a
1000 ms linear ramp from 20/s to 60/s with a 50/30/20 operation mix; and (c) runs for 1500 ms at a
steady rate of 40/s with a 60/20/20 operation mix.

Because a `DELETE` permanently removes rows from the table, the next run with `-l` reloads those
rows, so pass `-l` to every run that needs to start from a fully populated table. See
[P05](05-types-and-multi-tenant.md) for walkthroughs of the JSON and BSON reference scenarios.

10. Confirm that specifying a launch override without `-q` is rejected before Pherf opens any
    cluster connection:

    ```sh
    pherf -z "$ZK" -l -targetRate 100 -disableSchemaApply \
      -scenarioFile "$HOWTO/relational_scenario.xml"
    echo "exit $?"
    ```

    Captured output (using option A):

    ```text
    java.lang.IllegalArgumentException: -targetRate needs -q, because the overrides change only ...
    ...
    exit 1
    ```

    Valid parameter ranges are 1 to 9223372036854775 ms for `-runDurationMs`, 1 to 999999999 for
    `-threadCount`, and any finite number greater than 0 for `-targetRate`. Supplying `-targetRate`
    for a scenario that contains no open-loop query set also fails immediately because the override
    would have no effect.

### Describe your own workload

11. Author a custom scenario modeling a workload composed of 80% point reads, 15% range scans, and
    5% range deletes, in which the query set references an external workload profile:

    ```sh
    cat >"$HOWTO/mix_scenario.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="howto_mix">
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
                <type>INTEGER</type>
                <userDefined>true</userDefined>
                <dataSequence>SEQUENTIAL</dataSequence>
                <minValue>0</minValue>
                <maxValue>2000000000</maxValue>
                <name>K2</name>
            </column>
            <column>
                <type>VARCHAR</type>
                <userDefined>true</userDefined>
                <dataSequence>RANDOM</dataSequence>
                <length>16</length>
                <prefix>v</prefix>
                <name>V</name>
            </column>
        </datamapping>
        <scenarios>
            <scenario tableName="PHERF.HOWTO_MIX" rowCount="10000" name="howtoMix">
                <preScenarioDdls>
                    <ddl tableName="PHERF.HOWTO_MIX"
                         statement="CREATE TABLE IF NOT EXISTS ? (K1 BIGINT NOT NULL,
                             K2 INTEGER NOT NULL, V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2))"/>
                </preScenarioDdls>
                <coGeneration seed="42" blockSize="20"/>
                <querySet concurrency="4" profile="steps_profile.xml">
                    <query id="get" statement="SELECT V FROM PHERF.HOWTO_MIX
                        WHERE K1 = [K1] AND K2 = [K2]"/>
                    <query id="scan" kind="SCAN" statement="SELECT K2, V FROM PHERF.HOWTO_MIX
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                    <query id="del" kind="DELETE" statement="DELETE FROM PHERF.HOWTO_MIX
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                </querySet>
            </scenario>
        </scenarios>
    </datamodel>
    EOF
    ```

    The scenario file retains the SQL query statements, their operation kinds, and the worker
    concurrency, while delegating both the arrival curve and the operation mix entirely to the
    external profile. Enabling `coGeneration` ensures that every point read targets a key written
    during the data load phase and that every `SCAN` and `DELETE` predicate brackets an exact block
    of 20 rows; see [P04](04-co-generation.md) for details. Do not configure `warmupExecutions`
    here, because a warmup execution of a `DELETE` removes a data block before the measured schedule
    begins.

12. Create the workload profile specifying Poisson arrivals stepped across 50, 100, and 200
    operations per second, followed by a 2 s quiet pause and a linear ramp from 50 to 250 operations
    per second:

    ```sh
    cat >"$HOWTO/steps_profile.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <workloadProfile>
        <openLoop arrival="POISSON" seed="8070" maxLagMs="250" lagPolicy="LATE">
            <stage durationMs="5000" rate="50"/>
            <stage durationMs="5000" rate="100"/>
            <stage durationMs="5000" rate="200"/>
            <stage durationMs="2000" rate="0"/>
            <stage durationMs="5000" rate="50" endRate="250"/>
        </openLoop>
        <mix>
            <operation kind="SELECT" weight="80"/>
            <operation kind="SCAN" weight="15"/>
            <operation kind="DELETE" weight="5"/>
        </mix>
    </workloadProfile>
    EOF
    ```

    Across the five stages, the expected mean arrival count is 250 + 500 + 1,000 + 0 + 750 = 2,500
    operations. Because a Poisson process draws random inter-arrival intervals around this mean, the
    actual arrival count varies slightly from 2,500, while the fixed seed makes that exact count
    deterministic across runs. The declared operation weights of 80/15/5 divide by their greatest
    common divisor of 5 to yield reduced weights of 16/3/1 over a repeating cycle of W = 20
    arrivals.

13. Validate the new scenario and its profile:

    ```sh
    pherf -z "$ZK" -validate -disableSchemaApply -label mix -scenarioFile "$HOWTO/mix_scenario.xml"
    ```

    Captured output:

    ```text
    Validation: 0 error(s) in 2 file(s). Report: RESULTS/VALIDATION_mix.json
    ```

14. Verify that Pherf rejects a query set that combines an external profile with an inline rate
    curve rather than merging them. Inject an inline `openLoop` element alongside the profile
    attribute in a temporary copy and run the validator:

    ```sh
    sed 's|steps_profile.xml">|&<openLoop><stage durationMs="1000" rate="10"/></openLoop>|' \
      "$HOWTO/mix_scenario.xml" >"$HOWTO/both_scenario.xml"
    pherf -z "$ZK" -validate -disableSchemaApply -label both \
      -scenarioFile "$HOWTO/both_scenario.xml"
    echo "exit $?"
    ```

    Captured output:

    ```text
    xml.profile $HOWTO/both_scenario.xml scenario howtoMix querySet 1: profile
    $HOWTO/steps_profile.xml of querySet 1 of scenario howtoMix: the querySet also has its own
    openLoop; the profile is the only source of the openLoop
    Validation: 1 error(s) in 2 file(s). Report: RESULTS/VALIDATION_both.json
    exit 65
    ```

    The validator outputs this diagnostic as a single line (wrapped here for readability); under
    option B, the reported file paths reflect the staged directory inside client 0. Exit status 65
    denotes a validation failure, and attempting to execute this file directly will halt at the
    pre-run validation gate with the exact same error before opening any cluster connection.

15. Load the 10,000 rows and execute the custom workload:

    ```sh
    pherf -z "$ZK" -l -q -disableSchemaApply -label mix -scenarioFile "$HOWTO/mix_scenario.xml"
    ```

16. Verify the executed operation mix by comparing each operation kind's completed count against
    N x w / W, where N is the value of `scheduled`:

    ```sh
    grep -E '"(scheduled|completed|late|dropped|aborted)":' RESULTS_HDR/LATENCY_mix.json |
      tr -d ' ,'
    awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"count":/ {print o, p, $2}' \
      RESULTS_HDR/LATENCY_mix.json | tr -d '",' | grep -E '^(query|scan|delete):'
    ```

    Expected output (not captured from a live cluster):

    ```text
    "scheduled":2463
    "completed":2463
    "late":0
    "dropped":0
    "aborted":0
    delete:howtoMix:del steady 123
    query:howtoMix:get steady 1971
    scan:howtoMix:scan steady 369
    ```

    These specific totals are illustrative; the configured Poisson seed deterministically fixes N,
    and your run output will display the exact value generated for that seed. For any total N, the
    `get` count will lie within 1 of N x 16 / 20, the `scan` count within 1 of N x 3 / 20, and the
    `del` count within 1 of N x 1 / 20. Because Pherf assigns each operation kind deterministically
    from the arrival index before dispatch, the resulting operation counts are completely
    independent of worker thread scheduling.

    When running with 4 concurrent workers, a read query can execute concurrently with a `DELETE`
    targeting the same block of rows, so the number of rows returned by individual scans can vary
    from run to run even though the operation execution counts remain invariant.

### Find the queue bound

17. Create a scenario designed to offer a higher arrival rate than a single worker thread can
    service. This scenario defines two query sets that share the same arrival curve of 600
    operations per second for 3 s and an inline mix of 9 point reads to 1 range scan, where the
    first query set uses the `LATE` lag policy and the second uses `DROP`:

    ```sh
    cat >"$HOWTO/lag_scenario.xml" <<'EOF'
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <datamodel name="howto_lag">
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
                <type>INTEGER</type>
                <userDefined>true</userDefined>
                <dataSequence>SEQUENTIAL</dataSequence>
                <minValue>0</minValue>
                <maxValue>2000000000</maxValue>
                <name>K2</name>
            </column>
            <column>
                <type>VARCHAR</type>
                <userDefined>true</userDefined>
                <dataSequence>RANDOM</dataSequence>
                <length>16</length>
                <name>V</name>
            </column>
        </datamapping>
        <scenarios>
            <scenario tableName="PHERF.HOWTO_LAG" rowCount="5000" name="howtoLag">
                <preScenarioDdls>
                    <ddl tableName="PHERF.HOWTO_LAG"
                         statement="CREATE TABLE IF NOT EXISTS ? (K1 BIGINT NOT NULL,
                             K2 INTEGER NOT NULL, V VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2))"/>
                </preScenarioDdls>
                <coGeneration seed="5" blockSize="100"/>
                <!-- Query set 1: one worker, LATE. Every arrival runs, late or not. -->
                <querySet concurrency="1">
                    <openLoop arrival="UNIFORM" maxLagMs="100" lagPolicy="LATE">
                        <stage durationMs="3000" rate="600"/>
                    </openLoop>
                    <mix>
                        <operation kind="SELECT" weight="9"/>
                        <operation kind="SCAN" weight="1"/>
                    </mix>
                    <query id="get" statement="SELECT V FROM PHERF.HOWTO_LAG
                        WHERE K1 = [K1] AND K2 = [K2]"/>
                    <query id="scan" kind="SCAN" statement="SELECT K2, V FROM PHERF.HOWTO_LAG
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                </querySet>
                <!-- Query set 2: the same curve, DROP. An arrival more than 100 ms late
                     does not run. -->
                <querySet concurrency="1">
                    <openLoop arrival="UNIFORM" maxLagMs="100" lagPolicy="DROP">
                        <stage durationMs="3000" rate="600"/>
                    </openLoop>
                    <mix>
                        <operation kind="SELECT" weight="9"/>
                        <operation kind="SCAN" weight="1"/>
                    </mix>
                    <query id="getDrop" statement="SELECT V FROM PHERF.HOWTO_LAG
                        WHERE K1 = [K1] AND K2 = [K2]"/>
                    <query id="scanDrop" kind="SCAN" statement="SELECT K2, V FROM PHERF.HOWTO_LAG
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                </querySet>
            </scenario>
        </scenarios>
    </datamodel>
    EOF
    ```

    Declaring an inline `mix` alongside an inline `openLoop` element is valid. The harness Pherf
    driver supports both inline definitions and external profiles by staging each referenced
    `querySet profile="..."` file into the container at the same relative path alongside the
    scenario file. Specifying a profile with an absolute path or a `..` path segment is rejected as
    a command usage error (exit 2), whereas referencing a profile that is not a readable file
    records the verdict row `pherf.profile FAIL`. See [H04][h04] for details.

18. Load the 5,000 rows and execute both query sets:

    ```sh
    pherf -z "$ZK" -l -q -disableSchemaApply -label lag -scenarioFile "$HOWTO/lag_scenario.xml"
    ```

    Under the `LATE` policy, Pherf continues executing after the 3 s arrival window closes until the
    accumulated backlog drains completely, so the overall command takes more than 6 s to finish.

19. Inspect the schedule entries for both query sets:

    ```sh
    keys='querySet|stopReason|scheduled|completed|late|dropped|maxQueueDepth|maxInFlight'
    grep -E "\"($keys|maxDriftUs)\":" RESULTS_HDR/LATENCY_lag.json | tr -d ' ,'
    ```

    Expected output (not captured from a live cluster):

    ```text
    "querySet":1
    "stopReason":"duration"
    "scheduled":1800
    "completed":1800
    "late":1650
    "dropped":0
    "maxQueueDepth":1
    "maxInFlight":1
    "maxDriftUs":9140000
    "querySet":2
    "stopReason":"duration"
    "scheduled":1800
    "completed":410
    "late":0
    "dropped":1390
    "maxQueueDepth":1
    "maxInFlight":1
    "maxDriftUs":3010000
    ```

    The exact counts for `late`, `completed`, `dropped`, and `maxDriftUs` are illustrative because
    they depend on the actual query service time of your cluster, whereas `scheduled` is always
    1,800 for both query sets. In both cases, the peak queue depth reaches the configured
    concurrency of 1, demonstrating that the dispatch queue stayed full because a single worker
    could not keep up with the arrival rate.

20. Compare the service time percentiles against the end-to-end response time percentiles for the
    `LATE` query set:

    ```sh
    awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"p50Us":/ {a=$2}
      /"p99Us":/ {print o, p, a, $2}' RESULTS_HDR/LATENCY_lag.json | tr -d '",' |
      grep -E ':(get|scan) '
    ```

    Expected output (not captured from a live cluster):

    ```text
    query:howtoLag:get steady 1520 3950
    response:howtoLag:get steady 4410000 8870000
    response:howtoLag:scan steady 4390000 8860000
    scan:howtoLag:scan steady 2310 5120
    ```

    The two numeric columns report the p50 and p99 latencies in microseconds (illustrative values).
    While the database service time remains in the low millisecond range, the end-to-end response
    time climbs into several seconds because every arriving operation has to wait in the queue
    behind the growing backlog. This divergence illustrates steady-state queueing above capacity,
    which any open-loop generator exposes. Note that coordinated omission is a distinct phenomenon
    that occurs even below capacity: when a cluster stalls briefly, a closed-loop client stops
    sending requests for the duration of the stall and records only a single slow sample, whereas an
    open-loop client continues scheduling arrivals throughout the stall and records the delay on
    every affected operation.

21. Inspect the automated health verdict generated for the run:

    ```sh
    cut -f1-3 RESULTS_HDR/VERDICT_lag.tsv
    ```

    Expected output (not captured from a live cluster):

    ```text
    pherf.health.attainment	FAIL	duration
    pherf.health.saturation	FAIL	duration
    pherf.health.client_pool	PASS	count
    pherf.health.tail_latency	FAIL	duration
    pherf.health.read_skew	SKIP	share
    ```

    The attainment check evaluates what fraction of the declared workload completed within the
    scheduled time window, while the saturation check evaluates peak dispatch lag against
    `pherf.verdict.lag.maxMs` (which defaults to 1000 ms). Even when health checks fail, Pherf still
    returns an exit code of 0 because the health verdict never alters the process exit status. See
    [P06](06-results-verdict-and-analysis.md) for a detailed explanation of each verdict row.

22. Re-run the same scenario with a larger worker pool. Passing `-threadCount` overrides the
    concurrency—and therefore the queue capacity bound—of both query sets:

    ```sh
    pherf -z "$ZK" -l -q -disableSchemaApply -label lag8 \
      -scenarioFile "$HOWTO/lag_scenario.xml" -threadCount 8
    grep -E '"(querySet|late|dropped|maxQueueDepth|maxInFlight|maxDriftUs)":' \
      RESULTS_HDR/LATENCY_lag8.json | tr -d ' ,'
    ```

    If dispatch lag drops significantly when worker threads are added, the previous run was
    constrained by client worker concurrency. If dispatch lag persists while `maxInFlight` reaches
    the new concurrency limit, the cluster itself is the bottleneck. Finally, if worker threads
    remain idle while lag continues to grow, the client's internal dispatcher is falling behind; the
    saturation verdict detail message explicitly distinguishes among these three root causes.
    [H06](../../../dev/harness/docs/howto/06-saturation-knee.md) applies this diagnostic technique
    to locate the cluster saturation knee.

## Read the result

* **Offered load.** Within each `schedules` entry, `completed` equals `scheduled` and `late`,
  `dropped`, and `aborted` all remain at 0 whenever the client successfully delivered the declared
  workload on schedule. Under these conditions, both `pherf.health.attainment` and
  `pherf.health.saturation` report PASS.
* **Late work.** A `late` count greater than 0 in `LATE` mode indicates that operations executed
  after exceeding their `maxLagMs` dispatch budget. Because the resulting queueing delay is captured
  in the response time metrics, always evaluate the `response` latency keys—rather than the service
  time keys—against your latency objective.
* **Dropped work.** A `dropped` count greater than 0 in `DROP` mode indicates that the client shed
  excess load, behaving like an overloaded service enforcing a request deadline. Because the latency
  histograms record only the operations that actually executed, a low p99 latency accompanied by
  dropped operations does not represent a passing run.
* **Aborted work.** An `aborted` count greater than 0 means either that the schedule terminated
  early (with a `stopReason` other than `duration`) or that an operation threw an execution error.
  Inspect the run logs to identify the underlying failure.
* **Mix.** The execution count for each operation kind should fall within 1 of N x w / W. Any
  larger discrepancy indicates either that a query belonging to the mix failed during execution or
  that you are inspecting output from a different scenario run.
* **Queue.** A `maxQueueDepth` equal to `concurrency` indicates that the internal dispatch queue
  saturated. Increase the worker concurrency or reduce the target arrival rate before attributing
  the bottleneck to the cluster.
* **Echo.** The XML result summary at `RESULTS/RESULT_<datamodel>_<label>.xml` echoes the effective
  `profile` attribute, `openLoop` schedule, and `mix` configuration that actually executed after
  applying any command-line launch overrides.

## Reproduce it

* **Seeds.** The `coGeneration seed` deterministically fixes all generated primary keys and column
  values, while the `POISSON` seed fixes the sequence of arrival timestamps and therefore fixes the
  total arrival count N. Meanwhile, `UNIFORM` arrivals naturally produce the exact same N on every
  run. Consequently, in `LATE` mode without premature termination, the resulting operation counts
  are identical across repeated runs.
* **Versions.** Record the exact Git commit of the Phoenix client build (`git rev-parse HEAD` inside
  `$PHX_ROOT`) as well as the HBase and Phoenix versions deployed on the target cluster. When
  running against a harness cluster, record the container image tag:

  ```sh
  px-cluster config PX_IMAGE_TAG
  ```

* **Files.** Archive the entire `$HOWTO` directory so that the scenarios, workload profiles,
  `RESULTS`, and `RESULTS_HDR` outputs are preserved together:

  ```sh
  tar -czf "$PX_OUT/howto/p03.tar.gz" -C "$PX_OUT/howto" p03
  ```

## Clean up

1. Drop the benchmark tables created during this guide once you no longer need them. Pherf drops all
   tables in the `PHERF` schema whose names match the supplied regular expression:

   ```sh
   pherf -z "$ZK" -disableSchemaApply -drop 'PHERF\.(REF_RELATIONAL|HOWTO_MIX|HOWTO_LAG)'
   ```

   Pherf converts the regular expression to uppercase, replaces every occurrence of `ALL` with `.*`,
   and matches the resulting pattern against each `PHERF.<table>` name. Always include
   `-disableSchemaApply` when dropping tables so Pherf does not re-apply schema files discovered on
   its classpath.

2. If you started a local harness cluster, tear it down. The final log line emitted by down
   confirms that the cluster teardown completed:

   ```sh
   px-cluster down
   ```

   Expected output:

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

* [README.md](../../README.md): Reference documentation covering open-loop query sets, workload
  profiles, launch overrides, and the bundled reference scenarios.
* [LATENCY.md](../../LATENCY.md): Specification of the latency histogram keys and schedule report
  entries.
* [VERDICT.md](../../VERDICT.md): Specification of the attainment and saturation health checks.
* [P04](04-co-generation.md): Explanation of how co-generation guarantees that every point read
  locates an existing row and binds `SCAN` and `DELETE` predicates to exact row blocks.
* [P06](06-results-verdict-and-analysis.md): Guide to interpreting result artifacts, health
  verdicts, and multi-replicate analysis.
* [H05](../../../dev/harness/docs/howto/05-latency-slo-and-soak.md): Comparison of service time
  against end-to-end response time during sustained soak testing.
* [H06](../../../dev/harness/docs/howto/06-saturation-knee.md): Methodology for driving rate ramps
  to locate the cluster saturation knee.
* [D04][d04]: Guide to scaling open-loop workloads across multiple distributed agents. Each slot in
  the fleet receives a distinct POISSON seed via `${pherf.slot.seed}` so that agents do not
  dispatch phase-locked arrivals, and the fleet verdict grades attainment against the worst
  individual schedule rather than the fleet average.

[h04]: ../../../dev/harness/docs/howto/04-pherf-through-the-harness.md
[d04]: ../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[ref-rel]: ../../config/reference/relational_scenario.xml
[ref-json]: ../../config/reference/json_scenario.xml
[ref-bson]: ../../config/reference/bson_scenario.xml
