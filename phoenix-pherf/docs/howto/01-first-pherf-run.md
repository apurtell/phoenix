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

# How to run your first Pherf workload on a cluster

## The question

What latency do three representative queries exhibit on your cluster when a single client issues
each subsequent query only after the preceding one completes, and how repeatable is that latency
across independent runs? This guide walks through compiling Pherf from the source tree, authoring a
schema and a workload scenario, loading 100,000 rows, and executing three labeled replicates of a
closed-loop query set configured with warmup iterations and a connection cache. Finally, it details
where the resulting output artifacts are written and how to interpret them, serving as the
foundational baseline for the remaining Pherf HOWTO guides.

## Before you start

**Audience.** This guide is intended for prospective Pherf users and Phoenix developers who are
new to running Pherf workloads.

**Prerequisites.**

- You need an active HBase cluster running the Phoenix server built from this source tree, with its
  ZooKeeper quorum accessible from your client host. Pherf operates as a JDBC client against that
  cluster and creates and drops tables within the `PHERF` schema.
- You must have a local git checkout of Phoenix, Maven 3.9, and JDK 17. Configure `JAVA_HOME` to
  point to JDK 17 for both building the project and running Pherf.
- Install `jq` so that you can inspect and extract metrics from the generated JSON reports.
- If you do not have an external cluster available, the test harness can provision a containerized
  environment in Docker
  ([harness HOWTO 02](../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md)). Because the
  harness architecture does not support connecting a host-based JDBC client directly to the
  container network, workloads targeting a harness cluster must be executed either through the
  harness pherf driver
  ([harness HOWTO 04](../../../dev/harness/docs/howto/04-pherf-through-the-harness.md)) or inside
  client 0 by following option B in step 2 of [HOWTO 03](03-open-loop-profiles-and-mixes.md). Note
  that option B has been verified against the source code but was not executed against a live
  cluster.

**Cost.**

- Wall time: compiling the required modules takes approximately 8 min with a warm Maven cache (and
  longer with a cold cache), while loading the dataset and executing the four runs takes a few
  additional minutes depending on cluster capacity.
- Memory: the Pherf JVM process on the host allocates a maximum heap of up to 3 GiB (`-Xmx3072m`).
- Disk: build artifacts consume approximately 175 MiB of jars in `target/`, and the generated run
  results require less than 10 MiB.
- Files written: the procedure writes `$PX_OUT/howto/pherf-env.sh`, configuration files under
  `$HOWTO/conf/` (the schema, scenario, and `pherf.properties`), result artifacts in
  `$HOWTO/work/RESULTS`, `$HOWTO/work/RESULTS_HDR`, and `$HOWTO/sets/`, and the cluster table
  `PHERF.HOWTO_EVENTS`.

The workflow steps that require a live cluster were verified against `Pherf.java` and the unit test
golden result files rather than executed on a running cluster for this HOWTO; their output samples
are labeled "expected". Conversely, all offline steps—including command help, file discovery, and
scenario validation—were executed directly, and their actual outputs are labeled "captured".

## Method

**Hypothesis.** Following an initial warmup phase, the p50 and p99 latencies for every query in the
scenario vary by less than 5% between the fastest and slowest of three replicates, while recording
identical execution counts. Because all three replicates execute the identical build under identical
configuration settings, this experiment functions as an A/A test. If the observed variance exceeds
5%, the hypothesis is refuted, and that observed spread establishes the baseline noise floor of your
cluster and client host. Record this threshold carefully, because no subsequent A/B comparison can
credibly claim an effect smaller than this noise floor
([HOWTO 06](06-results-verdict-and-analysis.md)).

**The closed loop.** Any query set configured without `openLoop` operates as a closed-loop feedback
system. Each worker thread executes a single query, blocks until the final row is returned, and only
then initiates the next execution. Consequently, the client adapts its request rate to the server:
whenever the cluster slows down, the client proportionally reduces its request throughput. A
closed-loop benchmark therefore measures the pure service time experienced by a self-paced client.
It cannot capture the queueing delay experienced by external users under load, because a delayed
response postpones the submission of the next request (a phenomenon known as coordinated omission).
To drive a fixed arrival rate and measure true response time including queueing delay, configure an
open-loop workload ([HOWTO 03](03-open-loop-profiles-and-mixes.md)).

**One stream at a time.** In this implementation of Pherf, closed-loop execution serializes every
query invocation behind a single run-wide lock (`MultiThreadedRunner` synchronizes each execution on
the shared workload executor). Setting `concurrency="4"` therefore spawns four threads that take
turns acquiring the lock rather than issuing four concurrent queries in parallel. You should always
interpret a closed-loop measurement as a single-stream latency profile. To generate true concurrent
load, use either an open-loop query set or the `-mt` load profile
([HOWTO 05](05-types-and-multi-tenant.md)).

**Controls.**

- Maintain the exact same schema, scenario definition, row count, and client host across all
  replicates.
- Perform a single data load and run all replicates against that unchanged dataset. Because the
  rule-based data generators derive their random seed from the system clock, repeating the load step
  would populate different values. When deterministic, seed-fixed values are required across loads,
  use co-generation ([HOWTO 04](04-co-generation.md)).
- Configure `warmupExecutions="20"` so that each thread executes every query 20 times prior to
  recording steady-state measurements. These initial executions are recorded exclusively under the
  `warmup` latency phase and are omitted from the legacy CSV and XML outputs. The warmup phase
  absorbs cold-start overhead—populating region location caches, table metadata, and initial block
  reads—though 20 iterations are insufficient to drive the client or server JIT compilers to a fully
  steady state. To size the warmup duration rigorously for formal timing claims, inspect the
  interval percentiles of an exploratory run ([HOWTO 06](06-results-verdict-and-analysis.md),
  step 14): the warmup is sufficient once the p50 and p90 latencies across the earliest steady-state
  intervals stop trending downward.
- Enable `-connectionCache` so that each worker thread reuses an established JDBC connection across
  executions. Without connection caching, every query execution establishes a fresh connection, and
  every recorded latency sample includes connection setup overhead.
- Control the underlying HBase storage state of the target table. Immediately after a data load,
  rows reside across memstores and multiple uncompacted store files, whereas a flush or major
  compaction significantly alters the read path cost. Select a consistent storage state—for
  instance, "after a major compaction"—and enforce it before running the replicates.

**Noise.** External cluster clients, background compactions, post-load region splits and region
migrations, JVM garbage collection pauses on the client or RegionServers, and competing processes on
the client host all introduce measurement variance. Execute the workload on an otherwise idle
cluster from a dedicated, quiet client host, and inspect the cluster logs afterward to verify that
no region splits or moves occurred during the measurement window.

**What to measure and why.**

- Extract the `steady` phase latency percentiles for each query from the latency report, expressed
  in microseconds. Each recorded sample spans from connection acquisition through consumption of the
  final result row, meaning it encompasses client-side work including placeholder value generation,
  statement preparation, and connection retrieval (mitigated by `-connectionCache` as noted above).
  Because tail percentiles are statistically meaningful only when backed by sufficient sample sizes—
  computing p99 requires at least 100 samples, and p99.9 requires at least 1000 samples (see
  [ANALYSIS.md](../../ANALYSIS.md))—the query set executes each query 1000 times.
- Verify the sample count recorded for each operation key, which must remain identical across all
  replicates.
- The `count` query validates its aggregate output against `expectedAggregateRowCount`. A mismatched
  count causes that execution to fail as an error, and failed executions do not record latency
  samples, ensuring that data corruption or incomplete loads cannot masquerade as artificially fast
  queries.
- Review the automated health verdict generated for each run ([VERDICT.md](../../VERDICT.md)).

**What invalidates the result.** Any query error logged during execution, a sample count discrepancy
across replicates, a `pherf.health.client_pool` check reporting `FAIL` (which indicates that the
client thread pool became the bottleneck), a region split or move during the measurement window, or
an anomalous replicate whose divergence cannot be traced to a known cause all invalidate the
experiment. You must diagnose and resolve the underlying cause before treating the comparison as
valid.

## Steps

1. Initialize the workspace environment variables and create the working directory from the root of
   the repository.

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p01"; mkdir -p "$HOWTO/work"
   ```

2. Compile the Phoenix embedded client and the Pherf module. Specify the HBase build profile that
   corresponds to your target cluster by choosing one of the `hbase.profile` values defined in the
   root `pom.xml`, such as `2.5` or `2.6`. Because the packaged Pherf jar does not bundle the
   Phoenix JDBC driver classes, the Maven invocation must also build the embedded client jar.

   ```sh
   mvn -B -pl phoenix-client-parent/phoenix-client-embedded,phoenix-pherf -am \
     -Dhbase.profile=2.6 -DskipTests clean package
   ```

   Expected:

   ```text
   [INFO] BUILD SUCCESS
   ```

3. Generate the `pherf-env.sh` helper script, which defines the `pherf` shell function. This script
   constructs a runtime classpath combining the newly built project jars with dependency jars cached
   in your local Maven repository. The dependency versions correspond to the properties defined in
   the root `pom.xml` (`bson.version`, `log4j2.version`, and `slf4j.version`) and in
   `phoenix-pherf/pom.xml` (`activation.version`, `jaxb-api.version`, and `jaxb-impl.version`). The
   embedded client does not bundle the OpenTelemetry API; instead, it declares the versions aligned
   with the selected HBase release profile, which the build writes into
   `phoenix-client-parent/phoenix-client-embedded/target/pom.xml` (version 1.49.0 and semconv
   1.29.0-alpha for profile 2.6). It also requires a commons-logging API implementation, provided by
   `jcl-over-slf4j`. Without `jcl-over-slf4j` and the OpenTelemetry jars on the classpath,
   `-validate` still succeeds, but every JDBC connection attempt fails with `NoClassDefFoundError:
   org/apache/commons/logging/Log` or `io/opentelemetry/api/trace/Span`
   even though the run exits with code 0. If your build version or HBase profile differs from
   `5.4.0-SNAPSHOT` and `2.6`, export `PHX_VERSION` and `PHX_HBASE_PROFILE` before sourcing the
   script. Even Pherf's offline modes require the remaining jars on this classpath: omitting the
   activation jar causes `-validate` to exit with code 1; omitting the JAXB jars aborts execution
   with `NoClassDefFoundError: javax/xml/bind/JAXBException`; and omitting the Log4j jars leaves
   SLF4J without a logging backend, causing validation refusals to produce no diagnostic message.

   ```sh
   mkdir -p "$PX_OUT/howto"
   cat > "$PX_OUT/howto/pherf-env.sh" <<EOF
   PHX_ROOT="$PWD"
   EOF
   cat >> "$PX_OUT/howto/pherf-env.sh" <<'EOF'
   # Defines the function pherf for the Pherf HOWTOs. Source this file.
   : "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
   V="${PHX_VERSION:-5.4.0-SNAPSHOT}"
   P="${PHX_HBASE_PROFILE:-2.6}"
   M2="$HOME/.m2/repository"
   C="$PHX_ROOT/phoenix-client-parent/phoenix-client-embedded/target"
   L="$M2/org/apache/logging/log4j"
   PHERF_CP="$PHX_ROOT/phoenix-pherf/target/phoenix-pherf-$V.jar"
   PHERF_CP="$PHERF_CP:$C/phoenix-client-embedded-hbase-$P-$V.jar"
   PHERF_CP="$PHERF_CP:$M2/org/mongodb/bson/5.3.1/bson-5.3.1.jar"
   PHERF_CP="$PHERF_CP:$M2/javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1.jar"
   PHERF_CP="$PHERF_CP:$M2/com/sun/xml/bind/jaxb-impl/2.3.9/jaxb-impl-2.3.9.jar"
   PHERF_CP="$PHERF_CP:$M2/javax/activation/activation/1.1/activation-1.1.jar"
   PHERF_CP="$PHERF_CP:$L/log4j-api/2.25.5/log4j-api-2.25.5.jar"
   PHERF_CP="$PHERF_CP:$L/log4j-core/2.25.5/log4j-core-2.25.5.jar"
   PHERF_CP="$PHERF_CP:$L/log4j-slf4j-impl/2.25.5/log4j-slf4j-impl-2.25.5.jar"
   PHERF_CP="$PHERF_CP:$M2/org/slf4j/jcl-over-slf4j/1.7.36/jcl-over-slf4j-1.7.36.jar"
   O="$M2/io/opentelemetry"
   PHERF_CP="$PHERF_CP:$O/opentelemetry-api/1.49.0/opentelemetry-api-1.49.0.jar"
   PHERF_CP="$PHERF_CP:$O/opentelemetry-context/1.49.0/opentelemetry-context-1.49.0.jar"
   S="$O/semconv/opentelemetry-semconv/1.29.0-alpha"
   PHERF_CP="$PHERF_CP:$S/opentelemetry-semconv-1.29.0-alpha.jar"
   for f in $(echo "$PHERF_CP" | tr ':' ' '); do
     [ -f "$f" ] || echo "pherf-env.sh: missing $f" >&2
   done
   # PHERF_CONF: a directory with pherf.properties, scenario/ or datamodel/. It comes first.
   # PHERF_EXT: extension jars, separated by ':' (HOWTO 07). They come last.
   pherf() {
     "$JAVA_HOME/bin/java" -Xms512m -Xmx3072m \
       -Dorg.apache.hbase.thirdparty.io.netty.tryReflectionSetAccessible=true \
       --add-modules jdk.unsupported \
       --add-opens java.base/java.nio=ALL-UNNAMED \
       --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
       --add-opens java.base/java.lang=ALL-UNNAMED \
       --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
       --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
       --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
       --add-exports java.security.jgss/sun.security.krb5=ALL-UNNAMED \
       --add-exports java.base/sun.net.dns=ALL-UNNAMED \
       --add-exports java.base/sun.net.util=ALL-UNNAMED \
       -cp "${PHERF_CONF:+$PHERF_CONF:}$PHERF_CP${PHERF_EXT:+:$PHERF_EXT}" \
       org.apache.phoenix.pherf.Pherf "$@"
   }
   EOF
   . "$PX_OUT/howto/pherf-env.sh"
   ```

   The JVM module and reflective access flags mirror those configured in `bin/phoenix_utils.py` for
   JDK 11 and newer runtimes. If sourcing the script produces no `missing` diagnostic lines on
   standard error, every required jar has been resolved. If you compiled Phoenix through the test
   harness (`px-cluster build`), `$PX_OUT/build/phoenix/build.tsv` records the exact file paths to
   those same Pherf, embedded client, and bson jars, along with the complete Pherf runtime
   classpath (`pherf_classpath`) and the JDK home directory used for the build (`java_home`).

4. Change into the working directory and verify the CLI by printing the command-line options. Note
   that passing `-h` prints the usage summary and exits with code 1.

   ```sh
   cd "$HOWTO/work"
   pherf -h 2>/dev/null | head -2
   ```

   Captured:

   ```text
   usage: Pherf
    -allowUnknownTags,--allowUnknownTags             Let the scenario
   ```

5. Define the ZooKeeper quorum address for your target cluster. Pherf prepends `jdbc:phoenix:` to
   this quorum string when constructing the JDBC connection URL.

   ```sh
   ZK="zk1.example.com,zk2.example.com,zk3.example.com:2181"
   ```

6. Create the SQL schema definition file. Each Pherf schema file contains a single DDL statement.

   ```sh
   mkdir -p "$HOWTO/conf/datamodel" "$HOWTO/conf/scenario"
   cat > "$HOWTO/conf/datamodel/howto_events.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PHERF.HOWTO_EVENTS (
       TENANT CHAR(3) NOT NULL,
       EVENT_ID VARCHAR NOT NULL,
       CREATED DATE,
       KIND VARCHAR,
       AMOUNT INTEGER,
       NOTE VARCHAR
       CONSTRAINT PK PRIMARY KEY (TENANT, EVENT_ID))
   EOF
   ```

7. Author the workload scenario XML file. The `datamapping` section defines data generation rules
   for the table columns, while the `scenario` element specifies the target table, the number of
   rows to load, and a single closed-loop query set.

   ```sh
   cat > "$HOWTO/conf/scenario/howto_events_scenario.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8"?>
   <datamodel name="howto_events">
     <datamapping>
       <!-- A general rule: every VARCHAR column without its own rule. -->
       <column>
         <type>VARCHAR</type>
         <dataSequence>RANDOM</dataSequence>
         <length>12</length>
         <name>GENERAL_VARCHAR</name>
       </column>
       <!-- userDefined rules apply to the column with the same name and type only. -->
       <column>
         <type>CHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>LIST</dataSequence>
         <length>3</length>
         <name>TENANT</name>
         <valuelist>
           <datavalue distribution="50"><value>AAA</value></datavalue>
           <datavalue distribution="30"><value>BBB</value></datavalue>
           <datavalue distribution="20"><value>CCC</value></datavalue>
         </valuelist>
       </column>
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <length>16</length>
         <prefix>ev</prefix>
         <name>EVENT_ID</name>
       </column>
       <column>
         <type>DATE</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <minValue>2020</minValue>
         <maxValue>2025</maxValue>
         <name>CREATED</name>
       </column>
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>LIST</dataSequence>
         <name>KIND</name>
         <valuelist>
           <datavalue><value>click</value></datavalue>
           <datavalue><value>view</value></datavalue>
           <datavalue><value>buy</value></datavalue>
         </valuelist>
       </column>
       <column>
         <type>INTEGER</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <minValue>1</minValue>
         <maxValue>10000</maxValue>
         <nullChance>10</nullChance>
         <name>AMOUNT</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PHERF.HOWTO_EVENTS" rowCount="100000" name="events">
         <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="1000"
                   executionDurationInMs="300000" warmupExecutions="20">
           <query id="count" expectedAggregateRowCount="100000"
                  statement="SELECT COUNT(*) FROM PHERF.HOWTO_EVENTS"/>
           <query id="perTenant"
                  statement="SELECT KIND, SUM(AMOUNT) FROM PHERF.HOWTO_EVENTS
                    WHERE TENANT = [TENANT] GROUP BY KIND"/>
           <query id="recent"
                  statement="SELECT EVENT_ID, AMOUNT FROM PHERF.HOWTO_EVENTS
                    WHERE TENANT = 'AAA' ORDER BY EVENT_ID DESC LIMIT 10"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   Data generation rule semantics:

   - `RANDOM` synthesizes uniformly distributed random values constrained by the rule bounds:
     `length` determines character count for text columns, `minValue` and `maxValue` bound numeric
     columns, and `minValue` through `maxValue` specify a year range for `DATE` columns.
   - `SEQUENTIAL` produces monotonically increasing values. For VARCHAR and CHAR columns, it appends
     an incrementing counter to the configured `prefix`, guaranteeing that `EVENT_ID` is unique
     across every generated row.
   - `LIST` selects values from the nested `datavalue` entries. When `distribution` weights are
     present (whose percentages must sum to 100), selection probability follows those weights;
     when omitted, every entry is chosen with uniform probability.
   - `nullChance` specifies the percentage probability of generating a SQL NULL; setting it to 10
     produces approximately 10,000 NULL amount values across 100,000 rows.
   - A rule without `userDefined` acts as a type-wide fallback for any column matching its data
     type. A rule marked with `<userDefined>true</userDefined>` overrides the fallback rules and
     applies exclusively to the column matching both its name and type. If any table column lacks a
     matching rule, the data load aborts with an error identifying that column.
   - Placing `[TENANT]` inside a query SQL statement defines a dynamic placeholder. On each
     execution, Pherf substitutes a value generated by the `TENANT` column rule, automatically
     enclosing CHAR and VARCHAR values in single quotes.

   Query set configuration semantics:

   - `executionType="SERIAL"` executes each query definition in sequence after the preceding query
     completes its full run, whereas `PARALLEL` launches worker threads for all queries
     simultaneously (though closed-loop executions still serialize on the shared executor lock, as
     described in Method).
   - Each worker thread terminates as soon as it reaches either `numberOfExecutions` or
     `executionDurationInMs`, whichever threshold is met first. When unspecified, these attributes
     default to 10 executions and 10,000 ms, respectively.
   - `concurrency` accepts either a fixed thread count or an inclusive range such as `1-4`. When a
     range is provided, Pherf repeats the query execution across each concurrency level from the
     lower bound to the upper bound.
   - `expectedAggregateRowCount` instructs Pherf to validate the first column of every returned row
     against the specified expected number on each execution.

8. Verify that Pherf can discover your configuration files on its classpath. Setting `PHERF_CONF`
   prepends `$HOWTO/conf` to the classpath, and `-listFiles` scans the classpath to report all
   `.sql` files located under `datamodel/` along with all `.xml` files under `scenario/` whose
   filenames contain the substring `scenario`.

   ```sh
   PHERF_CONF="$HOWTO/conf" pherf -listFiles 2>/dev/null
   ```

   Captured:

   ```text
   Schema Files:
   $HOWTO/conf/datamodel/howto_events.sql
   Scenario Files:
   $HOWTO/conf/scenario/howto_events_scenario.xml
   ```

   Once these directories reside on the classpath, you can also resolve the scenario by regular
   expression using `-scenarioFile '.*howto_events_scenario\.xml'`. The remaining steps in this
   HOWTO pass explicit absolute file paths instead.

9. Validate the schema and scenario definitions offline before connecting to the cluster. This mode
   performs static validation without opening a JDBC connection; see
   [HOWTO 02](02-scenario-authoring-and-validation.md) for a detailed explanation of the validation
   checks and report structure.

   ```sh
   pherf -validate -label first \
     -schemaFile "$HOWTO/conf/datamodel/howto_events.sql" \
     -scenarioFile "$HOWTO/conf/scenario/howto_events_scenario.xml" 2>/dev/null
   ```

   Captured:

   ```text
   Trying to match resource pattern: $HOWTO/conf/datamodel/howto_events.sql
   File resourceList Loaded: [$HOWTO/conf/datamodel/howto_events.sql]
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_first.json
   ```

10. Drop any pre-existing copy of the target table, apply the SQL schema, and load the generated
    rows into the cluster. Because Pherf executes `-drop` prior to applying the schema file,
    re-running this command cleanly recreates an empty table before loading data. Redirect standard
    error to preserve the execution log.

    ```sh
    pherf -z "$ZK" -drop 'PHERF\.HOWTO_EVENTS' -l -label load \
      -schemaFile "$HOWTO/conf/datamodel/howto_events.sql" \
      -scenarioFile "$HOWTO/conf/scenario/howto_events_scenario.xml" 2>"$HOWTO/load.log"
    echo "exit=$?"
    ```

    Expected (not captured from a live cluster):

    ```text
    exit=0
    ```

    The summary file `RESULTS/RESULT_Data_Load_Summary_load.csv` contains the header row
    `ZK,TABLE_NAME,ROW_COUNT,LOAD_DURATION_IN_MS` followed by a single record summarizing the load
    operation. In parallel, `RESULTS_HDR/LATENCY_load.json` records a histogram under the operation
    key `load:events:PHERF.HOWTO_EVENTS`, capturing one latency sample per committed batch of
    `pherf.default.dataloader.batchsize` rows (which defaults to 1000 rows).

11. Transition the loaded table into the consistent storage state established in your experimental
    controls (see Method)—for example, by triggering a major compaction from the cluster's HBase
    shell—and wait for that operation to complete before starting the query runs.

12. Execute three independent replicates of the query set against the loaded dataset. Passing `-q`
    executes the scenario query sets, while `-disableSchemaApply` skips DDL execution so existing
    tables are untouched. Assigning a distinct label to each replicate ensures that its output
    files do not overwrite the artifacts produced by earlier runs.

    ```sh
    for r in 1 2 3; do
      pherf -z "$ZK" -q -disableSchemaApply -connectionCache -label "run$r" \
        -scenarioFile "$HOWTO/conf/scenario/howto_events_scenario.xml" \
        2>"$HOWTO/run$r.log" || { echo "run$r failed"; break; }
    done
    ls RESULTS RESULTS_HDR
    ```

    Expected (not captured from a live cluster; the filenames match the golden result files from the
    unit test suite):

    ```text
    RESULTS:
    RESULT_COMBINED_run1_detail.csv
    RESULT_howto_events_run1.xml
    RESULT_howto_events_run1_aggregate.csv
    RESULT_howto_events_run1_detail.csv
    ...
    RESULTS_HDR:
    LATENCY_run1.hlog
    LATENCY_run1.json
    VERDICT_run1.tsv
    ...
    ```

13. Extract the steady-state latency percentiles for each replicate from the JSON latency reports.

    ```sh
    for r in 1 2 3; do
      jq -r --arg r "run$r" '.histograms[] | select(.phase == "steady")
        | [$r, .operation, .count, .p50Us, .p99Us, .p999Us] | @tsv' \
        "RESULTS_HDR/LATENCY_run$r.json"
    done
    ```

    Expected shape (not captured from a live cluster; the latency figures shown are illustrative):

    ```text
    run1  query:events:count      1000  41983  60127  88191
    run1  query:events:perTenant  1000  28031  39903  51199
    run1  query:events:recent     1000  1503   3001   9871
    ```

    The corresponding `warmup` phase entries each record a sample count of 20, which you can verify
    by filtering with `select(.phase == "warmup")`.

14. Inspect the automated health verdict summary generated for the first replicate.

    ```sh
    cut -f1,2,5 RESULTS_HDR/VERDICT_run1.tsv
    ```

    Expected shape (not captured from a live cluster):

    ```text
    pherf.health.attainment    SKIP  ...
    pherf.health.saturation    SKIP  ...
    pherf.health.client_pool   PASS  ...
    pherf.health.tail_latency  PASS  ...
    pherf.health.read_skew     SKIP  ...
    ```

    Because a closed-loop workload has no target arrival schedule, the attainment and saturation
    checks both evaluate to SKIP. Similarly, the read skew check evaluates to SKIP because no
    `pherf.verdict.serverJmx` endpoint is configured.

15. Customize Pherf runtime parameters by supplying your own `pherf.properties` file. Because Pherf
    loads only the first `pherf.properties` resource discovered on its classpath without merging
    multiple files, always begin by copying the complete default configuration file. The following
    override enables periodic interval histogram logging at a 1 s cadence and sets the tail latency
    verdict threshold for p99 to 100 ms.

    ```sh
    cp "$PHX_ROOT/phoenix-pherf/config/pherf.properties" "$HOWTO/conf/"
    cat >> "$HOWTO/conf/pherf.properties" <<'EOF'
    pherf.default.hdr.intervalMs=1000
    pherf.verdict.tail.p99MaxMs=100
    EOF
    PHERF_CONF="$HOWTO/conf" pherf -z "$ZK" -q -disableSchemaApply -connectionCache \
      -label run4 -scenarioFile "$HOWTO/conf/scenario/howto_events_scenario.xml" \
      2>"$HOWTO/run4.log"
    ls RESULTS_HDR | grep run4
    ```

    Expected (not captured from a live cluster):

    ```text
    LATENCY_run4.hlog
    LATENCY_run4.json
    LATENCY_run4_intervals.hlog
    VERDICT_run4.tsv
    ```

    The configuration properties in `pherf.properties` that most directly govern workload
    measurements are summarized below:

    - `pherf.default.threadpool` (default 10): sets the size of the closed-loop execution thread
      pool as well as the capacity of the connection cache.
    - `pherf.default.dataloader.threadpool` (default 0): controls the number of concurrent writer
      threads used during `-l` data loading, where 0 allocates one thread per available CPU core.
    - `pherf.default.dataloader.batchsize` (default 1000): defines the number of rows buffered into
      each committed write batch during data loading.
    - `pherf.default.results.dir` (default `RESULTS`): specifies the output directory for legacy
      result files relative to the working directory. The HDR histogram outputs are written to a
      sibling directory with `_HDR` appended to this name.
    - `pherf.default.hdr.intervalMs` (absent and off by default): configures the reporting interval
      for the periodic interval histogram log.
    - `pherf.verdict.*`: defines the evaluation thresholds and bounds used to compute the run health
      verdict; see [VERDICT.md](../../VERDICT.md).

16. Organize the replicate artifacts into a structured directory set for offline statistical
    analysis. Each replicate subdirectory stores the HDR latency report alongside the legacy XML
    result file bearing the matching run label.

    ```sh
    for r in 1 2 3; do
      mkdir -p "$HOWTO/sets/base/run$r"
      cp RESULTS_HDR/LATENCY_run$r.* RESULTS/RESULT_*_run$r.xml "$HOWTO/sets/base/run$r/"
    done
    ```

    In [HOWTO 06](06-results-verdict-and-analysis.md), running `pherf -analyze "$HOWTO/sets/base"`
    against this directory set computes replicate medians, bootstrap confidence intervals, and
    sample count parity across the runs.

## Read the result

- **Units.** The legacy CSV and XML outputs report latencies in milliseconds by rounding half up
  from the underlying microsecond measurements, meaning that 2,600 us rounds to 3 ms and 300 us
  rounds to 0 ms. Furthermore, the `AVG_*` columns in the aggregate CSV file truncate fractional
  values using integer division. Always rely on the HDR latency report when analyzing operations
  that complete within a few milliseconds.
- **Keys.** Every histogram entry in the latency report is indexed by an operation identifier and an
  execution phase. The operation identifier follows the pattern `query:<scenario>:<query id>`, and
  the phase is either `steady` or `warmup`. See [LATENCY.md](../../LATENCY.md) for the full schema.
- **Replicates.** Begin by comparing `p50Us` across the three replicates for each query; the median
  converges reliably even at modest sample sizes, whereas p99 exhibits greater natural variability.
  For each query, calculate the replicate spread by subtracting the fastest replicate from the
  slowest and expressing that difference as a percentage of the fastest replicate. Your
  repeatability hypothesis holds if the spread for both p50 and p99 remains below 5%. Regardless of
  whether the threshold is met, record the observed spread as your baseline noise floor: in future
  comparisons, any shift exceeding this noise floor represents a candidate performance effect,
  whereas smaller shifts are indistinguishable from noise. HOWTO 06 demonstrates how to quantify
  this judgment with formal confidence intervals.
- **Counts.** Every replicate must record exactly 1000 `steady` samples and 20 `warmup` samples per
  query. Any shortfall in sample count indicates that query executions failed during the run;
  inspect the corresponding replicate log file to diagnose the errors.
- **What a closed loop number means.** A closed-loop latency measurement reflects the isolated
  execution time of a single query when no other query from this client is in flight. It does not
  represent response latency under concurrent load; to measure latency at a sustained arrival rate,
  run an open-loop workload.

## Reproduce it

Archive the following provenance details alongside your experimental results so the run can be
reproduced:

- The exact git commit hash of the source tree (`git rev-parse HEAD`), the HBase profile selected
  for the Maven build, and the client JDK version (`"$JAVA_HOME/bin/java" -version`).
- The HBase, Hadoop, and Phoenix versions deployed on the cluster, the number of active
  RegionServers, and the cluster configuration files.
- The complete set of workload configuration files under `$HOWTO/conf/` (including the SQL schema,
  the XML scenario, and `pherf.properties`).
- The physical storage state of the target table prior to each replicate (for example, "major
  compaction done").
- The run labels together with the output directories `RESULTS`, `RESULTS_HDR`, and
  `$HOWTO/sets/base`, packaged in a single archive.

Because the rule-based data generator does not use a fixed seed, repeating the data load will
generate different row values drawn from the same statistical distributions. The HdrHistogram
recorder parameters are fixed across all runs (tracking latencies from 1 us to 600 s at 3
significant digits of precision), ensuring that histograms produced by different runs remain
directly comparable.

## Clean up

Drop the benchmark table from the cluster. Passing `-disableSchemaApply` prevents Pherf from
re-applying the schema file, which would otherwise immediately recreate the dropped table.

```sh
pherf -z "$ZK" -disableSchemaApply -drop 'PHERF\.HOWTO_EVENTS' 2>"$HOWTO/drop.log"
echo "exit=$?"
```

Expected (not captured from a live cluster): `exit=0`. Retain the working directory on disk so its
artifacts remain available for HOWTO 06.

## See also

- [HOWTO 02](02-scenario-authoring-and-validation.md): Explains every static validation rule and how
  to use validation as a pre-run gate.
- [HOWTO 03](03-open-loop-profiles-and-mixes.md): Covers open-loop workloads, arrival rate profiles,
  and multi-operation workload mixes.
- [HOWTO 06](06-results-verdict-and-analysis.md): Details how to interpret the latency report, the
  automated health verdict, and comparative analysis with `-analyze`.
- [README.md](../../README.md): Provides a comprehensive reference for all command-line options and
  scenario XML elements.
- [Harness HOWTO 01](../../../dev/harness/docs/howto/01-measurement-protocol.md): Defines the
  rigorous measurement protocol, including run classes, quiet host isolation, and contamination
  checks.
- [Distributed Pherf HOWTOs](../../../phoenix-pherf-distributed/docs/howto/README.md): Explains how
  to execute the same scenario across multiple agents coordinated by a single controller to produce
  one merged latency report.
