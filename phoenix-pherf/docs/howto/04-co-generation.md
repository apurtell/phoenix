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

# How to generate verifiable data with co-generation

## The question

How can a load test guarantee that every read targets a row previously written by the test, and
that operation and row counts remain identical across runs? Traditional rule-based data generators
draw random keys; because a point read with a randomly selected key rarely matches an existing row,
the workload predominantly measures cache and storage misses while producing non-deterministic row
counts from run to run. Co-generation resolves this by computing every column value as a pure,
deterministic function of the row index, the column definition, and a seed. Consequently, any
writer and reader sharing a scenario definition will independently derive the exact same key space
without coordinating state—whether they execute across threads, in separate JVMs, or in distinct
OS processes. This HOWTO explains how to write, read, scan, and delete co-generated rows, how to
partition multiple Pherf processes across disjoint key ranges, how to synthesize an intentional
key-prefix hotspot, and why the resulting execution counts are exact.

## Before you start

**Audience.** This guide is intended for Pherf users who require deterministic correctness evidence
under load, and for Phoenix developers verifying that code changes preserve read-what-you-wrote
consistency.

**Prerequisites.**

* You need network connectivity from your host to the ZooKeeper quorum of a Phoenix cluster, along
  with the artifacts built in step 3 of [P01](01-first-pherf-run.md) (including
  `$PX_OUT/howto/pherf-env.sh`) and JDK 17 configured in `JAVA_HOME`.
* If you do not have an external cluster, provision a local harness cluster as described in
  [H02](../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md) using
  `px-cluster --class validation up`, and follow option B in step 2 of
  [P03](03-open-loop-profiles-and-mixes.md). Note that option B has been verified against the
  harness source code, though it has not been executed against a live cluster.
* Review [P02](02-scenario-authoring-and-validation.md) for scenario authoring and validation, and
  consult [P03](03-open-loop-profiles-and-mixes.md) for open-loop execution semantics.
* Ensure that the tables `PHERF.HOWTO_COGEN` and `PHERF.HOWTO_CLUSTER` do not contain any data
  that must be preserved.

**Cost.**

* Wall time: Approximately 10 min of benchmark runs, plus roughly 5 min if bringing up a local
  harness cluster.
* Docker memory: None when running against an external cluster; a local harness validation cluster
  with 1 RegionServer reserves 8704 MiB of container memory limits.
* Disk: Less than 50 MB in the working directory.
* Files written: `$HOWTO` stores the scenario XML files, while `$HOWTO/work`, `$HOWTO/work-a`, and
  `$HOWTO/work-b` store the generated `RESULTS/` and `RESULTS_HDR/` directories.
* Tables written: `PHERF.HOWTO_COGEN` (10,000 rows) and `PHERF.HOWTO_CLUSTER` (5,000 rows).

## Method

**Hypothesis.** Under a co-generated scenario, every point read returns exactly one row, every
`SCAN` retrieves one full block, every `DELETE` removes one full block, and concurrent processes
configured with disjoint row offsets access strictly non-overlapping row sets.

**Controls.** The deterministic parameters governing key and value generation are the seed, offset,
row count, block size, cluster limit, and column rules. A reader must be configured with the exact
same parameter values as the writer; otherwise, it will compute and query a different set of keys.

**Noise.** Operation and row counts experience zero noise and are exact on any host, whereas
latency timings on a laptop remain ADVISORY.

**What to measure and why.** Inspect the `resultRowCount` attribute of each query execution in the
XML result report, which must equal 1 for a point read and equal the block size for a `SCAN` or
`DELETE` operation. Verify the `count` recorded for each latency key, and assert total table
population using an aggregate count query configured with `expectedAggregateRowCount`, which causes
the query execution to fail if the actual count diverges from the expected value.

**What invalidates the result.**

* Executing a query-only run without a preceding data load (`-q` alone) after a run that deleted
  rows, or after a load performed with different generation parameters, invalidates the test
  because readers will probe keys that do not exist in the table and silently return zero rows
  without raising an error.
* Launching two processes whose configured offsets are separated by fewer rows than each process
  writes causes their generated key ranges to overlap.
* Defining a primary key that lacks at least one column producing a unique value per row index
  causes subsequent rows to overwrite earlier ones.
* Allowing a closed-loop query set to terminate via its time bound (`executionDurationInMs`) before
  completing its configured `numberOfExecutions` makes the final execution counts vary with cluster
  throughput.

### The co-generation model

Co-generation is enabled solely by including an optional element in a scenario definition; there is
no separate command-line option or configuration property:

```xml
<coGeneration seed="7" offset="0" blockSize="100" clusterLimit="4" watermark="COMMIT"
              walk="MULTIPLICATIVE"/>
```

The supported configuration attributes and their default values are as follows:

* `seed` (default 0): Seeds both deterministic column value generation and the reader traversal
  sequence.
* `offset` (default 0): Specifies the starting row index for this process and must be at least 0.
  The global index of any generated row equals this offset plus its local row index.
* `blockSize` (default 1000): Defines the number of rows in each writer block and must be at least
  1. A block never straddles an integer multiple of `blockSize`, and each block is committed as a
  single batch.
* `clusterLimit` (absent by default, disabling clustering): Must be at least 1 when set, and caps
  the maximum number of distinct leading primary key values generated within each block.
* `watermark` (default `COMMIT`): Controls reader visibility during data loading. Under `COMMIT`,
  a block becomes eligible for reading as soon as its commit completes; under `LOAD`, blocks become
  visible to readers only after all data-load writer threads finish.
* `walk` (default `MULTIPLICATIVE`): Determines the order in which readers traverse committed rows.
  Under `MULTIPLICATIVE`, readers step through the key space in a hashed sequence using a stride
  coprime to the row count so that a complete pass visits every row exactly once; under
  `SEQUENTIAL`, readers traverse rows in ascending index order.

If an attribute value cannot be parsed—such as `walk="SEQUENTAIL"`—the run fails immediately with
an error message that identifies the scenario, the attribute name, and the invalid value.

**Values.** The value generated for a column at global index g is defined by `valueAt(g)`, which is
a pure function of g, the column rule, and the scenario seed. To prevent two columns that share the
same rule from generating identical values, the per-column seed mixes the scenario seed with a hash
of the column name. Standard data-generation rules retain their semantics under co-generation, with
the following refinements:

* `SEQUENTIAL` maps the row index directly to its corresponding position in the rule's sequence,
  and extends support to `BIGINT` and `UNSIGNED_LONG`. For `SEQUENTIAL` `DATE` and `TIMESTAMP`
  columns, values begin at 1970-01-01 00:00:00.000 and advance by one second per row index.
* `LIST` selects values according to their declared relative weights, `RANDOM` honors the
  configured numeric bounds, string length, and prefix of the rule, and `nullChance` preserves its
  configured null probability.
* `useCurrentDate` is rejected whenever it appears in a rule or list entry because wall-clock time
  is not a deterministic function of the row index.
* `<generatorClass>` specifies a custom generator implementation. If that class implements
  `org.apache.phoenix.pherf.rules.IndexedDataGenerator`, Pherf invokes `valueAt(index, seed)` to
  produce index-deterministic values; otherwise, the class uses its own stateful generation logic,
  whose output is not a pure function of the row index. See [P07](07-extending-pherf.md) for an
  example implementation.

**Writers.** A data load (`-l`) generates `rowCount` rows starting at local index 0, or
`-rowCountOverride` rows when overridden on the command line. Writer threads claim work in whole
blocks so that each block forms a single commit batch, meaning `blockSize` supersedes the batch
size used by standard rule-based loads. Consequently, the `load` latency metric records one timing
sample per committed block.

**Readers.** Every placeholder within a single query statement is bound from the same row index,
ensuring that a point lookup receives a self-consistent composite primary key for an existing row.
A statement advances the reader cursor and draws a row index only if it contains at least one
placeholder backed by a generation rule.

* In a closed-loop query set with concurrency C, thread i accesses only row indexes congruent to
  i mod C. Because threads traverse mutually disjoint index partitions, C threads executing N
  queries apiece collectively perform C x N reads; when C x N equals the total row count, a single
  pass reads every row in the dataset exactly once.
* In an open-loop query set, all worker threads share a single coordinated read sequence. A full
  pass still visits each row once, though which specific worker thread reads a given row depends on
  runtime arrival and scheduling order.
* Executing a query-only workload (`-q` without `-l`) assumes that all `rowCount` rows defined by
  the scenario have already been populated. Because Pherf assumes rather than inspects this table
  state, the preceding data load must have run to completion using the exact same co-generation
  parameters.

**Statement literals.** Query placeholders are substituted with SQL literals matching the exact
values bound during writing. `CHAR` and `VARCHAR` values are enclosed in single quotes. `DATE` and
`TIMESTAMP` values are rendered as typed UTC literals with millisecond precision, such as
`TIMESTAMP '2000-01-01 00:00:00.123'`. `VARBINARY` and `VARBINARY_ENCODED` values are formatted as
hexadecimal literals, such as `X'0aff'`. Numeric types are emitted unquoted, while `DECIMAL` and
array element values use a canonical decimal string representation that is independent of the JDK
version.

**SCAN and DELETE.** A query declared with `kind="SCAN"` or `kind="DELETE"` references its range
column twice to form a closed interval, such as `K1 >= [K1] AND K1 <= [K1]`. Under co-generation,
each execution selects a single block and binds the lower and upper placeholders to the column
values of the first and last rows in that block. This guarantees that the predicate spans the exact
set of rows belonging to that block, even when `clusterLimit` is active. To ensure well-formed
block ranges, the range column must satisfy three constraints:

* It must be the leading primary key column so that the predicate translates directly into a
  contiguous key-range scan.
* It must be the sole rule-backed placeholder in the statement.
* Its generated values must be monotonically non-decreasing with respect to the row index: a
  `SEQUENTIAL` `TINYINT`, `INTEGER`, `BIGINT`, or `UNSIGNED_LONG` rule that does not wrap across
  the written row range, or a `SEQUENTIAL` `DATE` or `TIMESTAMP` rule, configured with no
  `nullChance`. A `SEQUENTIAL` `VARCHAR` column is rejected because lexicographical text ordering
  does not match numeric row-index order.

Each `SCAN` execution selects a complete committed block according to its configured walk strategy.
By contrast, each `DELETE` execution claims the next uncommitted deletion block in ascending index
order across all deleter threads. Because deletions always consume blocks from index 0 upward, the
deleted region forms a contiguous prefix tracked as the delete watermark; concurrent and subsequent
point reads and scans automatically restrict their draws to rows above this watermark. If a
`DELETE` executes when no committed blocks remain above the delete watermark, the operation fails.

**Several processes.** Multiple Pherf processes generate mutually disjoint key spaces whenever
their configured offsets are separated by at least the total number of rows written by a single
process (including any rows appended by `writeParams` writers) and the table's primary key contains
at least one column whose value is unique per row index. Although the offline validator verifies
that co-generated scenarios targeting the same table within a single invocation have non-overlapping
offsets, it has no visibility into separate OS processes; operators must therefore partition
process offsets manually.

## Steps

### Set up

1. Execute the environment initialization commands from the root of the Phoenix repository and
   switch into the primary working directory:

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p04"; mkdir -p "$HOWTO/work" "$HOWTO/work-a" "$HOWTO/work-b"
   . "$PX_OUT/howto/pherf-env.sh"
   cd "$HOWTO/work"
   ```

   The directories `work-a` and `work-b` serve as isolated working directories for the two
   concurrent Pherf processes, A and B, while shared scenario definitions are stored in `$HOWTO`.

2. Select your target execution environment as outlined in step 2 of
   [P03](03-open-loop-profiles-and-mixes.md). When running against an external cluster (option A),
   export the target ZooKeeper quorum string:

   ```sh
   ZK="zk1.example.com,zk2.example.com,zk3.example.com:2181"
   ```

   When running against a local harness cluster (option B), define both `ZK` and the `pherf` shell
   wrapper function from step 2 of P03. That wrapper mirrors each host working directory into an
   isolated path inside the client 0 container, allowing processes A and B to execute concurrently
   without file collisions.

### Write the scenarios

3. Create the scenario definition for process A, which loads 5,000 rows starting at offset 0 and
   subsequently reads every row once across 4 closed-loop reader threads:

   ```sh
   cat >"$HOWTO/cogen_a.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_cogen_a">
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
               <length>12</length>
               <prefix>a</prefix>
               <name>V</name>
           </column>
           <column>
               <type>DATE</type>
               <userDefined>true</userDefined>
               <dataSequence>SEQUENTIAL</dataSequence>
               <name>D</name>
           </column>
       </datamapping>
       <scenarios>
           <scenario tableName="PHERF.HOWTO_COGEN" rowCount="5000" name="cogenA">
               <preScenarioDdls>
                   <ddl tableName="PHERF.HOWTO_COGEN"
                        statement="CREATE TABLE IF NOT EXISTS ? (K1 BIGINT NOT NULL,
                            K2 INTEGER NOT NULL, V VARCHAR, D DATE
                            CONSTRAINT PK PRIMARY KEY (K1, K2))"/>
               </preScenarioDdls>
               <coGeneration seed="7" offset="0" blockSize="100" watermark="COMMIT"
                             walk="MULTIPLICATIVE"/>
               <!-- 4 threads x 1250 executions = 5000 reads: one pass over the 5000 rows. -->
               <querySet concurrency="4" executionType="SERIAL" numberOfExecutions="1250"
                         executionDurationInMs="600000">
                   <query id="get" statement="SELECT V, D FROM PHERF.HOWTO_COGEN
                       WHERE K1 = [K1] AND K2 = [K2]"/>
               </querySet>
           </scenario>
       </scenarios>
   </datamodel>
   EOF
   ```

   Because both `K1` and `K2` use `SEQUENTIAL` rules, every row index maps to a distinct primary
   key, and the monotonically increasing leading column `K1` satisfies the ordering requirement for
   a `SCAN` range predicate. Setting a generous `executionDurationInMs` ensures that every reader
   thread completes its full quota of executions rather than timing out early, keeping the
   operation counts independent of cluster speed. Even though the closed-loop runner serializes
   individual executions in round-robin order across the 4 threads (for reasons detailed in
   [P01](01-first-pherf-run.md)), each thread remains strictly partitioned to its own subset of
   row indexes.

4. Derive the scenario definition for process B by retaining the same target table and seed while
   advancing the offset to 5,000 and assigning a distinct scenario name and payload prefix:

   ```sh
   sed -e 's/howto_cogen_a/howto_cogen_b/' -e 's/name="cogenA"/name="cogenB"/' \
     -e 's/offset="0"/offset="5000"/' -e 's|<prefix>a</prefix>|<prefix>b</prefix>|' \
     "$HOWTO/cogen_a.xml" >"$HOWTO/cogen_b.xml"
   ```

   Process B generates global row indexes 5,000 through 9,999. Because its starting offset is
   separated by 5,000 rows from process A's range, processes A and B operate on completely disjoint
   primary keys.

5. Run the offline validator against both scenario files to confirm that their configurations are
   well-formed:

   ```sh
   pherf -z "$ZK" -validate -disableSchemaApply -label a -scenarioFile "$HOWTO/cogen_a.xml"
   pherf -z "$ZK" -validate -disableSchemaApply -label b -scenarioFile "$HOWTO/cogen_b.xml"
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_a.json
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_b.json
   ```

6. Verify the validator's offset-overlap detection rule by combining both scenarios into a single
   datamodel file and then shifting the offset of scenario B so that it encroaches on the row range
   of scenario A:

   ```sh
   { sed -n '1,/<scenarios>/p' "$HOWTO/cogen_a.xml" | sed 's/howto_cogen_a/howto_cogen_ab/'
     sed -n '/<scenario /,/<\/scenario>/p' "$HOWTO/cogen_a.xml"
     sed -n '/<scenario /,/<\/scenario>/p' "$HOWTO/cogen_b.xml"
     printf '    </scenarios>\n</datamodel>\n'; } >"$HOWTO/both.xml"
   sed 's/offset="5000"/offset="4000"/' "$HOWTO/both.xml" >"$HOWTO/overlap.xml"
   pherf -z "$ZK" -validate -disableSchemaApply -label both -scenarioFile "$HOWTO/both.xml"
   pherf -z "$ZK" -validate -disableSchemaApply -label overlap -scenarioFile "$HOWTO/overlap.xml"
   echo "exit $?"
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_both.json
   cogen.offsets $HOWTO/overlap.xml scenario cogenB: rows [4000, 9000) overlap rows [0, 5000) of
   scenario cogenA on table PHERF.HOWTO_COGEN, so the writers write the same keys
   Validation: 1 error(s) in 1 file(s). Report: RESULTS/VALIDATION_overlap.json
   exit 65
   ```

   (The diagnostic message is emitted on a single line and is wrapped above for readability.)
   Because the validator only inspects scenarios loaded within the same Pherf invocation, two
   separate processes reading independent scenario files will pass validation regardless of their
   offsets, making it the operator's responsibility to space their offsets apart.

### Write and read your own rows

7. Execute process A to load its 5,000 rows and immediately read each row once:

   ```sh
   (cd "$HOWTO/work-a" &&
     pherf -z "$ZK" -l -q -disableSchemaApply -label a1 -scenarioFile "$HOWTO/cogen_a.xml")
   ```

8. Summarize the `resultRowCount` values recorded across all query executions:

   ```sh
   grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-a"/RESULTS/RESULT_*_a1.xml |
     sort | uniq -c
   ```

   Expected output (not captured on a live cluster):

   ```text
   5000 resultRowCount="1"
   ```

   Every one of the 5,000 point reads matched and returned its target row, whereas a workload using
   randomly generated keys would predominantly return zero rows.

9. Inspect the operation sample counts in the HDR latency report:

   ```sh
   awk -F': ' '/"operation":/ {o=$2} /"phase":/ {p=$2} /"count":/ {print o, p, $2}' \
     "$HOWTO/work-a/RESULTS_HDR/LATENCY_a1.json" | tr -d '",'
   ```

   Expected output (not captured on a live cluster):

   ```text
   load:cogenA:PHERF.HOWTO_COGEN steady 50
   query:cogenA:get steady 5000
   ```

   Loading 5,000 rows in blocks of 100 produces 50 committed batches, and because the workload runs
   in a closed loop, no open-loop `response` latency key is emitted.

### Run two processes on disjoint keys

10. Launch process A in query-only mode (`-q`) concurrently with process B performing both data
    loading and querying:

    ```sh
    (cd "$HOWTO/work-a" &&
      pherf -z "$ZK" -q -disableSchemaApply -label a2 -scenarioFile "$HOWTO/cogen_a.xml") &
    (cd "$HOWTO/work-b" &&
      pherf -z "$ZK" -l -q -disableSchemaApply -label b1 -scenarioFile "$HOWTO/cogen_b.xml") &
    wait
    ```

    Process A skips data loading and relies on the `-q` precondition that its 5,000 rows from the
    prior run remain intact in the table, while process B concurrently loads and queries its own
    disjoint range of 5,000 rows.

11. Verify the returned row counts for both processes:

    ```sh
    grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-a"/RESULTS/RESULT_*_a2.xml |
      sort | uniq -c
    grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-b"/RESULTS/RESULT_*_b1.xml |
      sort | uniq -c
    ```

    Expected output (not captured on a live cluster):

    ```text
    5000 resultRowCount="1"
    5000 resultRowCount="1"
    ```

    Each process accessed only its own partition of the key space, every point read located its
    expected row, and process B never overwrote any row belonging to process A. Because key
    derivation is a pure function of the scenario definition, processes A and B can run just as
    easily on separate physical hosts against a shared cluster.

### Scan and delete blocks

12. Construct a range-operation scenario for process A that scans 10 blocks, deletes 5 blocks,
    performs 100 point reads, and finishes by counting all remaining rows in the table. Because it
    retains the co-generation parameters and column rules of `cogen_a.xml`, it targets the exact
    same key space:

    ```sh
    { sed '/<!-- 4 threads/,$d' "$HOWTO/cogen_a.xml"
      cat <<'EOF'
                <!-- Each SCAN reads one committed block of 100 rows. -->
                <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="10"
                          executionDurationInMs="600000">
                    <query id="scan" kind="SCAN" statement="SELECT K2, V FROM PHERF.HOWTO_COGEN
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                </querySet>
                <!-- Each DELETE removes the next committed block: rows 0 to 499 in all. -->
                <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="5"
                          executionDurationInMs="600000">
                    <query id="del" kind="DELETE" statement="DELETE FROM PHERF.HOWTO_COGEN
                        WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
                </querySet>
                <!-- Point reads draw only rows above the deleted prefix. -->
                <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="100"
                          executionDurationInMs="600000">
                    <query id="get" statement="SELECT V, D FROM PHERF.HOWTO_COGEN
                        WHERE K1 = [K1] AND K2 = [K2]"/>
                </querySet>
                <!-- 5000 rows of A less 500 deleted, plus 5000 rows of B. -->
                <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="1"
                          executionDurationInMs="600000">
                    <query id="count" expectedAggregateRowCount="9500"
                           statement="SELECT COUNT(*) FROM PHERF.HOWTO_COGEN"/>
                </querySet>
            </scenario>
        </scenarios>
    </datamodel>
    EOF
    } >"$HOWTO/range_a.xml"
    ```

    The `sed` command preserves the header and table configuration of `cogen_a.xml` up to the
    original query set comment. Any SQL statement containing `COUNT(` records the evaluated
    aggregate value as its `resultRowCount`, and specifying `expectedAggregateRowCount` causes the
    execution to fail if the observed table count diverges from 9,500.

13. Execute the range scenario in query-only mode without reloading data:

    ```sh
    (cd "$HOWTO/work-a" &&
      pherf -z "$ZK" -q -disableSchemaApply -label a3 -scenarioFile "$HOWTO/range_a.xml")
    grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-a"/RESULTS/RESULT_*_a3.xml |
      sort | uniq -c
    ```

    Expected output (not captured on a live cluster):

    ```text
    100 resultRowCount="1"
     15 resultRowCount="100"
      1 resultRowCount="9500"
    ```

    Each of the 10 scans and 5 deletes operated on an exact block of 100 rows. The delete phase
    removed local rows 0 through 499 of process A, the subsequent 100 point reads automatically
    sampled above the delete watermark and found existing rows, and the final aggregate count
    confirmed that 9,500 rows remain across the table.

14. Re-run the original scenario for process A in query-only mode (`-q`):

    ```sh
    (cd "$HOWTO/work-a" &&
      pherf -z "$ZK" -q -disableSchemaApply -label a4 -scenarioFile "$HOWTO/cogen_a.xml")
    grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-a"/RESULTS/RESULT_*_a4.xml |
      sort | uniq -c
    ```

    Expected output (not captured on a live cluster):

    ```text
     500 resultRowCount="0"
    4500 resultRowCount="1"
    ```

    Because a fresh Pherf process initializes with an empty in-memory delete watermark, it attempts
    to read all 5,000 row indexes from the full load range; however, the preceding `DELETE` run
    permanently removed 500 of those rows from the table, causing 500 point reads to return zero
    rows without raising an error. Consequently, any workload following a `DELETE` run must
    re-execute the data load phase (`-l`) to restore the table state.

15. Run process A with data loading enabled (`-l -q`) to repopulate the table before querying:

    ```sh
    (cd "$HOWTO/work-a" &&
      pherf -z "$ZK" -l -q -disableSchemaApply -label a5 -scenarioFile "$HOWTO/cogen_a.xml")
    grep -ho 'resultRowCount="[0-9]*"' "$HOWTO/work-a"/RESULTS/RESULT_*_a5.xml |
      sort | uniq -c
    ```

    Expected output (not captured on a live cluster):

    ```text
    5000 resultRowCount="1"
    ```

    Because co-generation deterministically reproduces the exact same keys and column values, the
    load restores the 500 deleted rows and all 5,000 point reads succeed again.

### Make a hotspot on purpose

16. Author a scenario that constrains each block to a single leading primary key value. Configuring
    `clusterLimit="1"` alongside `blockSize="100"` forces all 100 rows within each block to share
    the same `K1` value, directing each commit batch to a single key prefix:

    ```sh
    { sed -e '/<!-- 4 threads/,$d' -e 's/PHERF.HOWTO_COGEN/PHERF.HOWTO_CLUSTER/' \
        -e 's/howto_cogen_a/howto_cluster/' -e 's/name="cogenA"/name="cluster1"/' \
        -e 's/offset="0"/offset="0" clusterLimit="1"/' "$HOWTO/cogen_a.xml"
      cat <<'EOF'
                <!-- clusterLimit 1: one K1 value for each block of 100 rows. -->
                <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="1"
                          executionDurationInMs="600000">
                    <query id="distinctK1" expectedAggregateRowCount="50"
                           statement="SELECT COUNT(DISTINCT K1) FROM PHERF.HOWTO_CLUSTER"/>
                    <query id="rows" expectedAggregateRowCount="5000"
                           statement="SELECT COUNT(*) FROM PHERF.HOWTO_CLUSTER"/>
                </querySet>
            </scenario>
        </scenarios>
    </datamodel>
    EOF
    } >"$HOWTO/cluster.xml"
    pherf -z "$ZK" -l -q -disableSchemaApply -label c1 -scenarioFile "$HOWTO/cluster.xml"
    grep -ho 'resultRowCount="[0-9]*"' RESULTS/RESULT_*_c1.xml | sort | uniq -c
    ```

    Expected output (not captured on a live cluster):

    ```text
    1 resultRowCount="50"
    1 resultRowCount="5000"
    ```

    Under leading-key clustering with block size B and cluster limit L, the leading primary key
    column is evaluated at clustered index c(i) = (i / B) x L + (i mod B) / ceil(B / L). When L = 1
    and B = 100, this expression reduces to i / 100, yielding exactly 50 distinct `K1` values
    across 5,000 rows. Meanwhile, the secondary key column `K2` is still evaluated at the
    unclustered row index i, preserving primary key uniqueness so that rows do not overwrite one
    another. Any scenario that sets a cluster limit smaller than the block size must define a
    composite primary key of at least two columns; otherwise, the validator rejects the scenario
    with rule violation `cogen.writer-blocks`.

    Use `clusterLimit` whenever you need to simulate leading-key skew: choosing a small L causes
    many rows to collapse onto each leading key value—mirroring a hot tenant ID or a single active
    date partition—and concentrates each commit batch onto a small number of key prefixes. See
    [H11](../../../dev/harness/docs/howto/11-scale-out-capacity-and-skew.md) for an experiment that
    measures the resulting RegionServer read skew across a multi-node cluster.

## Read the result

* **Point reads.** Every point read execution must report a `resultRowCount` of 1; a value of 0
  indicates that the target row was absent due to a skipped data load, an earlier deletion, or
  mismatched co-generation parameters between the writer and the reader.
* **Scans and deletes.** Every `SCAN` or `DELETE` execution reports a `resultRowCount` equal to the
  configured block size (or the remainder of the dataset if the final block is smaller than
  `blockSize`).
* **Latency counts.** In the HDR latency summary, the `count` recorded for each `query`, `scan`,
  and `delete` metric key equals the total number of executions completed by its query set, while
  the `count` for the `load` key equals the number of committed writer blocks.
* **Count parity across replicates.** Because every operation count and row count in a co-generated
  scenario is a deterministic function of the scenario definition, replicate runs must produce
  identical operation counts and row counts. The offline analysis pipeline treats any discrepancy
  between replicates as a functional defect (`FAIL`) rather than statistical noise. Only two
  time-dependent conditions can cause execution counts to vary across runs: a closed-loop thread
  that terminates early upon reaching `executionDurationInMs` downgrades its query keys to
  advisory status, and an open-loop workload configured to drop arrivals (`DROP`) or halt on
  saturation produces non-deterministic counts that will trigger a count-parity `FAIL` (so `DROP`
  should never be used in scenarios intended for replicate count comparison). Additionally, when
  `DELETE` operations run concurrently with readers at a concurrency greater than 1, returned row
  counts may vary due to thread scheduling races, although operation counts remain invariant. See
  [P06](06-results-verdict-and-analysis.md) for instructions on running automated count-parity
  verification.
* **`writeParams` writers.** When a scenario includes `writeParams` background writers, those
  threads allocate new row indexes sequentially above the initial data-load range without a hard
  upper bound; when running multiple processes, either place the next process's offset well beyond
  the maximum number of rows those runtime writers can append, or isolate the processes in separate
  tables.

## Reproduce it

* **Seeds.** Because the seed, offset, row count, block size, cluster limit, and column rules
  completely determine every generated key and value, archiving the scenario XML files alongside
  the test outputs is sufficient to reproduce the exact workload.
* **Versions.** Record the Phoenix commit SHA used to build the client (`git rev-parse HEAD` in
  `$PHX_ROOT`) together with the server versions deployed on the cluster. When testing against a
  local harness cluster, record the container image tag:

  ```sh
  px-cluster config PX_IMAGE_TAG
  ```

* **Files.** Package the `$HOWTO` directory and all generated result artifacts into a tarball:

  ```sh
  tar -czf "$PX_OUT/howto/p04.tar.gz" -C "$PX_OUT/howto" p04
  ```

## Clean up

1. Drop the Phoenix tables created during this HOWTO:

   ```sh
   pherf -z "$ZK" -disableSchemaApply -drop 'PHERF\.(HOWTO_COGEN|HOWTO_CLUSTER)'
   ```

2. If you provisioned a local harness cluster, tear it down. The final log line emitted by down
   confirms that the cluster teardown completed:

   ```sh
   px-cluster down
   ```

   Expected output:

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

* [README.md](../../README.md), sections "Co-generation" and "SCAN and DELETE operations": Complete
  reference specification for deterministic data generation and block-level range operations.
* [P02](02-scenario-authoring-and-validation.md): Details on the `cogen.*`, `key.coverage`, and
  `predicate.*` rules enforced by the offline validator.
* [P03](03-open-loop-profiles-and-mixes.md): Guide to driving co-generated read workloads at a
  controlled arrival rate with an operation mix.
* [P06](06-results-verdict-and-analysis.md): Guide to verifying exact count parity across
  replicate runs.
* [P07](07-extending-pherf.md): Walkthrough of implementing a custom generator class with
  `valueAt(index, seed)`.
* [H07](../../../dev/harness/docs/howto/07-read-what-you-wrote.md): End-to-end read-what-you-wrote
  verification under load using the test harness.
* [D02][d02] and [H14][h14]: Guides to partitioning co-generation across multiple distributed
  agents. Configuring `<coGeneration offset>` with `${pherf.slot.offset}` and specifying `rows` on
  the execution step assigns each slot i a disjoint row range [o_i, o_i + n_i) such that the union
  of all slot ranges spans [0, N). Keep the co-generation seed constant across the fleet and omit
  slot tokens from primary key columns so that every generated key remains a pure function of its
  global row index. Note that when `clusterLimit` is enabled, the leading primary key column is
  derived from the slot's local row index, meaning any subsequent step that reads rows written by an
  earlier step must execute with the same agent count and slot weights.

[d02]: ../../../phoenix-pherf-distributed/docs/howto/02-first-fleet-run.md
[h14]: ../../../dev/harness/docs/howto/14-fleet-through-the-harness.md
