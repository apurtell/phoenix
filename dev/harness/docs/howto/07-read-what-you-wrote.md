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

# How to prove read-what-you-wrote correctness under load

## The question

When a cluster serves a mixed workload of point lookups, range scans, and range deletes, does
every read operation return the exact set of rows that the workload wrote and that no delete has
removed, and does the table contain the exact expected set of rows when the run finishes? A
performance benchmark that fails to validate its query results can report a fast run that read
nothing. This HOWTO employs deterministic co-generation, in which each generated value is a
function of the row index and a seed, so that every expected count for the run is established
before execution starts. It first proves these counts under an open-loop schedule with one
worker by inspecting both the Pherf results and an independent `COUNT(*)` query. It then repeats
the verification under concurrent load using 16 workers for 60 s with no delete operations.

## Before you start

**Audience.** This guide is intended for Phoenix developers who modify the read path, the write
path, the delete path, the region layout, or the client implementation, as well as committers
who review those changes.

**Prerequisites.**

- [How to run a Pherf scenario through the harness](04-pherf-through-the-harness.md): The
  container image for the harness must already exist, and you must know where the driver places
  the Pherf result files.

| Cost | Value |
|---|---|
| Wall time | About 10 minutes: bring-up is under 1 minute, and each run takes 1.5 to 3 minutes. |
| Docker memory | The validation class with 1 RegionServer uses 8704 MiB of container limits. |
| Disk | Each output bundle requires a few MiB of disk space. |
| Files written | `$HOWTO/rwyw.xml`, `count.sql`, `rwyl.xml`, `count-load.sql`, and 4 run bundles. |

Begin every shell session for this guide by running these three setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h07"; mkdir -p "$HOWTO"
```

## Method

**Co-generation.** When a scenario includes a `<coGeneration>` element, every generated column
value is a pure function of the row index, the column generation rule, and the seed. Writers claim
disjoint blocks of row indexes where each block corresponds to a single commit, and a reader draws
only row indexes that a writer has already committed. A SCAN or DELETE operation targets one
aligned block and binds its two range bounds to the leading-key values of the first and last rows
of that block, ensuring that the predicate matches the exact rows belonging to that block. Because
DELETE operations consume blocks in sequential index order and read operations skip blocks that
have already been deleted, every operation and row count for the run is deterministically fixed by
the scenario definition. See
[How to generate verifiable data with co-generation][p04]
for the configuration settings.

**The expected counts.** The scenario loads N = 1000 rows in blocks of B = 50 rows. The open-loop
schedule offers 25 operations per second for 4 s, producing A = 100 total arrivals. The
operation mix specifies weights of 70, 20, and 10; because their greatest common divisor is 10,
a single mix cycle spans W = 10 arrivals. Because W divides A evenly, the operation counts are
exact: 70 lookups, 20 scans, and 10 deletes.

| Value | Expected | Why |
|---|---|---|
| lookups | 70 runs, 70 rows | Each lookup binds the full primary key of one committed row. |
| scans | 20 runs, 1000 rows | Each scan returns one complete block of 50 rows. |
| deletes | 10 runs, 500 rows | Each delete removes the next complete block in index order. |
| rows left | 500 | The remaining row count equals N minus 10 x B. |

**Hypothesis.** Every expected value listed in the table holds during the run, and an
independent `COUNT(*)` query executed after the run returns 500.

**Controls.** The scheduled proof runs with a concurrency of 1 so that no read operation
executes concurrently with a DELETE targeting the same rows. The concurrent load proof omits
DELETE operations altogether, allowing it to run with 16 workers. The schedule uses the `LATE`
lag policy with no lag limit so that no arrival is dropped. Warmup executions are disabled
because warmup runs execute the queries of the query set sequentially, including the DELETE
statement, which would remove blocks outside the measured operation counts. Finally, the table
is created by the `preScenarioDdls` element of the scenario rather than through a `--schema`
file, ensuring that the `restore` verb of the pherf driver does not drop the table and that the
data survives for the independent `COUNT(*)` query in step 6.

**Noise.** Host timing noise never alters an operation or row count. Because this execution is a
validation run carrying the `quiet-host` contamination tag, a failed duration threshold produces
an ADVISORY verdict, whereas count metrics do not depend on host timing. Consequently, count
verdicts remain authoritative on any host and across every run class.

**What invalidates the proof.**

- Executing a run without the `-l` load flag after a prior run that included DELETE operations
  invalidates the proof. Such a run assumes that the earlier data load is still complete and
  observes the previously deleted rows as missing. While the pherf driver always loads data
  during its `prepare` phase so that every harness execution rewrites the rows, a standalone
  Pherf invocation must explicitly reload the table.
- Configuring a concurrency greater than 1 while DELETE operations are active invalidates the
  row-count expectation. A read can then execute concurrently with the DELETE of its target
  rows, causing observed row counts to vary between runs even though the operation counts do
  not change.
- Using the `DROP` lag policy or encountering a schedule halt invalidates the proof. Dropped and
  aborted arrivals alter the executed counts, causing the offline analysis to report a
  count-parity FAIL for affected replicates.
- Specifying a total arrival count that is not evenly divisible by the mix cycle invalidates
  exact operation counts. When the mix cycle does not divide the arrival count, the count for
  each operation kind is only guaranteed to fall within 1 of its proportional share rather than
  matching an exact value.
- Enabling a warmup phase, for the reason explained in the controls above, or allowing another
  concurrent writer to modify the same table also invalidates the proof.

## Steps

1. Start a validation cluster.

   ```sh
   px-cluster --class validation up
   ```

2. Create the scenario definition file. In this data model, `K1` is a SEQUENTIAL BIGINT that
   serves as both the leading primary-key column and the range-predicate column. Setting
   `clusterLimit="10"` constrains each block of 50 rows to at most 10 distinct values of `K1`,
   modeling a tenant identifier or a time bucket, while `K2` is a SEQUENTIAL INTEGER that keeps
   every primary key unique. The `SCORES` column is defined as a DOUBLE ARRAY.

   ```sh
   cat >"$HOWTO/rwyw.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_rwyw">
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
         <prefix>n</prefix>
         <name>NAME</name>
       </column>
       <column>
         <type>DOUBLE_ARRAY</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <length>4</length>
         <minValue>0</minValue>
         <maxValue>100</maxValue>
         <name>SCORES</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.RWYW" rowCount="1000" name="rwyw">
         <preScenarioDdls>
           <ddl tableName="PX_HOWTO.RWYW"
                statement="CREATE TABLE IF NOT EXISTS ? (K1 BIGINT NOT NULL,
                           K2 INTEGER NOT NULL, NAME VARCHAR, SCORES DOUBLE ARRAY
                           CONSTRAINT PK PRIMARY KEY (K1, K2))"/>
         </preScenarioDdls>
         <coGeneration seed="8070" offset="0" blockSize="50" clusterLimit="10"
                       watermark="COMMIT" walk="MULTIPLICATIVE"/>
         <querySet concurrency="1">
           <openLoop arrival="UNIFORM" lagPolicy="LATE">
             <stage durationMs="4000" rate="25"/>
           </openLoop>
           <mix>
             <operation kind="SELECT" weight="70"/>
             <operation kind="SCAN" weight="20"/>
             <operation kind="DELETE" weight="10"/>
           </mix>
           <query id="lookup" queryGroup="lookup"
                  statement="SELECT NAME, SCORES FROM PX_HOWTO.RWYW
                           WHERE K1 = [K1] AND K2 = [K2]"/>
           <query id="rangeScan" queryGroup="rangeScan" kind="SCAN"
                  statement="SELECT K2, NAME FROM PX_HOWTO.RWYW
                           WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
           <query id="rangeDelete" queryGroup="rangeDelete" kind="DELETE"
                  statement="DELETE FROM PX_HOWTO.RWYW
                           WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   The operation mix is declared inline alongside the inline `<openLoop>` element so that the
   entire scenario resides in a single file; an external `profile="..."` file placed next to the
   scenario also works because the pherf driver stages it automatically. Both the SCAN and DELETE
   statements reference the `[K1]` range placeholder twice, binding the first occurrence to the
   lower bound and the second to the upper bound. Setting `watermark="COMMIT"` ensures that a
   reader observes a block only after its commit completes, while `walk="MULTIPLICATIVE"` traverses
   the committed rows in a hashed order that visits each row once per pass. To validate this
   scenario file offline before executing a run, follow
   [How to author a scenario and validate it offline][p02];
   the pre-run validation gate of Pherf enforces the same rules during `prepare`.

3. Run the workload scenario.

   ```sh
   RUN="$(px-run pherf -- --scenario "$HOWTO/rwyw.xml")"
   ```

   When no `--schema` option is provided, the `prepare` phase invokes Pherf with
   `-disableSchemaApply -l`, and the `preScenarioDdls` statements create the target table prior
   to the data load.

4. Query the per-key operation counts and the open-loop schedule counters by name.

   ```sh
   px-bundle metrics "$RUN" 'op@*' pherf.operations | cut -f 1-3
   for k in scheduled completed dropped aborted; do
     px-bundle metrics "$RUN" 'schedule@*' "pherf.schedule.$k" | cut -f 1-3
   done
   ```

   ```text
   op@delete_rwyw_rangeDelete	pherf.operations	10
   op@query_rwyw_lookup	pherf.operations	70
   op@response_rwyw_lookup	pherf.operations	70
   op@response_rwyw_rangeDelete	pherf.operations	10
   op@response_rwyw_rangeScan	pherf.operations	20
   op@scan_rwyw_rangeScan	pherf.operations	20
   schedule@rwyw.1	pherf.schedule.scheduled	100
   schedule@rwyw.1	pherf.schedule.completed	100
   schedule@rwyw.1	pherf.schedule.dropped	0
   schedule@rwyw.1	pherf.schedule.aborted	0
   ```

   These observed counts are exact. The service metric key is formatted as
   `scan:<scenario>:<id>` for a SCAN operation and `delete:<scenario>:<id>` for a DELETE
   operation, and each operation kind also emits a corresponding `response:` key carrying the
   identical count.

5. Extract the row counts for each query from the XML result file. Every measured execution is
   recorded as a `runTimesInMs` element with a `resultRowCount` attribute; for a DELETE
   operation, this value represents the number of rows removed.

   ```sh
   awk '
     /<queryResults / { match($0, / id="[^"]*"/); q = substr($0, RSTART + 5, RLENGTH - 6) }
     /<runTimesInMs / { match($0, /resultRowCount="[0-9]+"/)
       n[q]++; r[q] += substr($0, RSTART + 16, RLENGTH - 17) }
     END { for (q in n) printf "%s runs %d rows %d\n", q, n[q], r[q] }
   ' "$RUN"/drivers/pherf/results/RESULT_*_run.xml | sort
   px-bundle metrics "$RUN" 'group@*' pherf.result_rows | cut -f 1,3
   ```

   ```text
   lookup runs 70 rows 70
   rangeDelete runs 10 rows 500
   rangeScan runs 20 rows 1000
   group@lookup	1
   group@rangeScan	50
   group@rangeDelete	50
   ```

   The first three lines verify the cumulative row totals across every read and delete
   execution. The `group@` records correspond to the `pherf.result_rows` metric, which reflects
   the `RESULT_ROW_COUNT` column of the aggregate CSV with one entry for each `queryGroup`. That
   column reports the row count of a single execution whenever every execution of the query
   returned the same row count—here, 1 row for each lookup and one block of 50 rows for each
   scan and each delete—and reports -1 whenever the counts differ across executions. Because
   that metric is not a cumulative total, always read cumulative totals from the XML result
   file, which records every measured execution and serves as the source for offline row-count
   analysis.

6. Count the remaining rows in the table using an independent client by executing a `COUNT(*)`
   query through the psql driver on the same cluster.

   ```sh
   cat >"$HOWTO/count.sql" <<'EOF'
   SELECT COUNT(*) FROM PX_HOWTO.RWYW;
   EOF
   RUN_COUNT="$(px-run psql -- "$HOWTO/count.sql")"
   cat "$RUN_COUNT/drivers/psql/out/run-1.out"
   ```

   ```text
                                   COUNT(1)
   ----------------------------------------
                                        500
   Time: 0.021 sec(s)
   ```

   The elapsed query time is illustrative, while the count of 500 matches N minus 10 x B.
   Because the psql driver drops only tables explicitly named by a `CREATE TABLE` statement in
   its own input files, this verification run leaves the table in place.

7. Inspect the health verdict of the Pherf run. The count-based checks remain authoritative on
   this validation run.

   ```sh
   cut -f 1-3 "$RUN/drivers/pherf/results_HDR/VERDICT_run.tsv"
   ```

   ```text
   pherf.health.attainment     PASS      duration
   pherf.health.saturation     ADVISORY  duration
   pherf.health.client_pool    PASS      count
   pherf.health.tail_latency   ADVISORY  duration
   pherf.health.read_skew      SKIP      share
   ```

   Because the scenario omits a warmup phase by design (see the controls section), the first
   lookup absorbs the cold-start cost of 1.53 s on the reference host, forcing subsequent
   arrivals to wait behind the single worker: the peak lag reaches 1.5 s, and the response p99
   for each operation kind falls between 1.4 and 1.5 s. As a result, the saturation and
   tail-latency rows exceed their 1000 ms bounds, and the noisy-host rule classifies them as
   ADVISORY. Because both are `duration` checks, they do not affect the correctness proof, and
   the counts established in steps 4 through 6 remain authoritative.

### Under concurrent load

Read-your-writes consistency defects typically surface under multi-worker concurrency and sustained
load pressure. Whereas the scheduled proof above runs a single worker at 25 operations per second,
this concurrent proof drives 16 workers at 1000 operations per second for 60 s using only point
lookups and range scans so that no read can race against a concurrent delete.

To determine the expected counts, the scenario loads N = 10,000 rows in blocks of B = 50 rows,
and the open-loop schedule generates A = 60,000 arrivals. Because the 70/30 mix has a cycle
length of W = 10 arrivals and W divides A evenly, the run executes an exact total of 42,000
lookups and 18,000 scans. Every lookup returns 1 row and every scan returns 50 rows, yielding
42,000 lookup rows and 900,000 scan rows in total, while the table retains all 10,000 rows at
the end of the run.

8. Create the scenario file for the concurrent load proof, specifying a new table name, 10,000
   rows, 16 workers, and an operation mix of lookups and scans.

   ```sh
   sed -e 's/PX_HOWTO\.RWYW/PX_HOWTO.RWYL/g' -e 's/name="howto_rwyw"/name="howto_rwyl"/' \
     -e 's/rowCount="1000" name="rwyw"/rowCount="10000" name="rwyl"/' \
     -e 's/<querySet concurrency="1">/<querySet concurrency="16">/' \
     -e 's/durationMs="4000" rate="25"/durationMs="60000" rate="1000"/' \
     -e 's/kind="SCAN" weight="20"/kind="SCAN" weight="30"/' \
     -e '/kind="DELETE" weight="10"/d' "$HOWTO/rwyw.xml" |
     awk '/<query id="rangeDelete"/ { skip = 1 } skip && /\/>/ { skip = 0; next } !skip' \
     >"$HOWTO/rwyl.xml"
   grep -E 'concurrency|<stage|<operation|<query ' "$HOWTO/rwyl.xml"
   ```

   ```text
         <querySet concurrency="16">
             <stage durationMs="60000" rate="1000"/>
             <operation kind="SELECT" weight="70"/>
             <operation kind="SCAN" weight="30"/>
           <query id="lookup" queryGroup="lookup"
           <query id="rangeScan" queryGroup="rangeScan" kind="SCAN"
   ```

   This arrival rate operates near the single-host point-lookup saturation knee identified in
   [How to find the maximum sustainable throughput and the saturation knee](06-saturation-knee.md).
   Under the `LATE` lag policy, every scheduled arrival executes, ensuring that the total counts
   remain exact even when the cluster falls behind the arrival schedule. Because Pherf retains
   each operation record in memory until the run finishes, at roughly 1.5 KB per operation,
   60,000 operations consume approximately 90 MB of the client heap.

9. Execute the concurrent load proof.

   ```sh
   RUN_L="$(px-run pherf -- --scenario "$HOWTO/rwyl.xml")"
   ```

10. Extract the minimum, maximum, and cumulative row counts for each query from the XML results.

    ```sh
    awk '
      /<queryResults / { match($0, / id="[^"]*"/); q = substr($0, RSTART + 5, RLENGTH - 6) }
      /<runTimesInMs / { match($0, /resultRowCount="[0-9]+"/)
        v = substr($0, RSTART + 16, RLENGTH - 17) + 0; n[q]++; r[q] += v
        if (!(q in lo) || v < lo[q]) lo[q] = v; if (v > hi[q]) hi[q] = v }
      END { for (q in n) printf "%s runs %d rows %d min %d max %d\n", q, n[q], r[q], lo[q], hi[q] }
    ' "$RUN_L"/drivers/pherf/results/RESULT_*_run.xml | sort
    ```

    ```text
    lookup runs 42000 rows 42000 min 1 max 1
    rangeScan runs 18000 rows 900000 min 50 max 50
    ```

    The observed counts match the expected values exactly. A minimum row count of 0 for the point
    lookup indicates a read that missed a committed row under load, whereas a scan returning fewer
    than 50 rows indicates a key range that lost rows. Inspecting the operation counts and schedule
    metrics by running the command from step 4 against `$RUN_L` confirms 60,000 scheduled and
    completed arrivals with 0 dropped and 0 aborted operations:

    ```text
    op@query_rwyl_lookup	pherf.operations	42000
    op@response_rwyl_lookup	pherf.operations	42000
    op@response_rwyl_rangeScan	pherf.operations	18000
    op@scan_rwyl_rangeScan	pherf.operations	18000
    schedule@rwyl.1	pherf.schedule.scheduled	60000
    schedule@rwyl.1	pherf.schedule.completed	60000
    schedule@rwyl.1	pherf.schedule.dropped	0
    schedule@rwyl.1	pherf.schedule.aborted	0
    ```

    As in step 7, the scenario omits a warmup phase, so initial cold-start overhead produces a peak
    scheduling lag of 1.4 s and a response p99 latency of 1.4 s, causing the saturation and
    tail-latency verdict rows to report ADVISORY. Because both checks belong to the `duration`
    class, they do not affect the correctness proof.

11. Verify the total row count of the table using the psql driver.

    ```sh
    echo 'SELECT COUNT(*) FROM PX_HOWTO.RWYL;' >"$HOWTO/count-load.sql"
    RUN_LC="$(px-run psql -- "$HOWTO/count-load.sql")"
    cat "$RUN_LC/drivers/psql/out/run-1.out"
    ```

    ```text
                                    COUNT(1)
    ----------------------------------------
                                       10000
    Time: 0.064 sec(s)
    ```

    The resulting row count must equal 10,000. To verify the same correctness guarantee across a
    server fault, add the kill disruption specification from
    [How to inject faults and measure recovery](09-fault-injection-and-recovery.md) to step 9 on
    the cluster topology defined in that HOWTO.

## Read the result

The scheduled proof holds when every condition in the following table is satisfied:

| Check | Expected | Source |
|---|---|---|
| arrivals | scheduled 100, completed 100, dropped 0, aborted 0 | `schedule@rwyw.1`, step 4 |
| operations by kind | 70, 20, 10 | `op@` records, step 4 |
| lookup rows | 70 | XML result, step 5 |
| scan rows | 1000 | XML result, step 5 |
| deleted rows | 500 | XML result, step 5 |
| rows left | 500 | `COUNT(*)`, step 6 |

A point lookup returning 0 rows indicates that a read failed to observe a committed row due to a
lost write, a stale region location, or an incorrect key encoding. A range scan returning fewer
than 50 rows indicates either a key range that lost committed rows or a delete operation that
removed too many rows. Conversely, a range scan returning more than 50 rows indicates either a
delete operation that removed too few rows or a scan key range that crossed a block boundary. A
`COUNT(*)` result other than 500 when the per-query row counts are otherwise valid indicates a
disagreement between the persisted table state and prior read visibility, such as a delete that
a subsequent read failed to observe. Every one of these outcomes represents a deterministic
correctness failure in the build, regardless of the host environment.

To scale up the workload with deletes enabled, increase the arrival rate and stage duration
together with the initial row count: because delete operations remove 10 x B rows for every 100
arrivals, the total row count must remain strictly above deletes x B. When driving a single table
from multiple load-generator processes, assign each process a distinct `offset`; see
[How to generate verifiable data with co-generation][p04]. The Distributed Pherf tier automatically
assigns each agent a contiguous, disjoint key range: `fleet-rww.suite` in [H14][h14] executes this
proof across 2 agents configured with offsets 0 and 2000.

## Reproduce it

- The co-generation seed 8070, offset, block size, cluster limit, row count, and column generation
  rules deterministically fix every generated value. Because `rwyw.xml` defines the complete
  workload, archive it alongside the run bundles.
- Combining UNIFORM arrivals at a fixed rate with an exact operation mix cycle deterministically
  fixes the execution order of the operations.
- Within each bundle, `cluster/topology.env` and `cluster/images.tsv` record the component version
  set and container image. Archive `$RUN`, `$RUN_COUNT`, `$RUN_L`, and `$RUN_LC`, and run
  `px-bundle validate "$RUN"` to confirm the integrity of any archived bundle copy.
- Replicates of each run must produce identical operation and row counts. The offline analysis
  pipeline verifies this exact parity for any co-generated scenario; see [How to detect a
  regression and compare builds, configurations and versions][h12].

## Clean up

1. Tear down the validation cluster. This step also removes the test tables, which were left in
   place because no `restore` operation dropped them.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [README of Pherf, sections "Co-generation" and "SCAN and DELETE operations"][pherf-readme]
- [How to cover Phoenix types and multi-tenant workloads][p05]
- [How to run Pherf on a fleet through the harness][h14], section "Read what you wrote across
  agents": executes the same correctness proof across 2 agents, each assigned its own slot offset.
- [How to inject faults and measure recovery](09-fault-injection-and-recovery.md): executes the
  same correctness proof across a RegionServer kill.
- [The driver contract, sections "psql" and "pherf"](../driver-contract.md)

[h12]: 12-regression-ab-and-matrices.md
[h14]: 14-fleet-through-the-harness.md
[p02]: ../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md
[p04]: ../../../../phoenix-pherf/docs/howto/04-co-generation.md
[p05]: ../../../../phoenix-pherf/docs/howto/05-types-and-multi-tenant.md
[pherf-readme]: ../../../../phoenix-pherf/README.md
