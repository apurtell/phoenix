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

# How to measure ingest throughput, check query plans and cost a secondary index

## The question

What ingestion throughput can a Phoenix build sustain, how much overhead does a covered global
index impose on each write operation, and does the query optimizer select the expected index for a
given predicate? Ingestion throughput, execution plan selection, and secondary index maintenance
overhead govern the evaluation of most write-path modifications in Phoenix. This HOWTO demonstrates
how to evaluate the Pherf bulk load pathway (`-l`), the single-row commit pathway driven by the
psql driver, query execution plans generated via `EXPLAIN`, and server-side index maintenance work
captured through Phoenix trace counters. It concludes by demonstrating how to manage the YARN
service group, which remains stopped by default and starts only on demand when required by a test.

Every command presented in this HOWTO has been verified against the harness and Pherf source
code. Because these procedures were not executed during the W1 live validation run, all output
excerpts—including query plan lines, counter names, and the contents of
`RESULT_Data_Load_Summary_load.csv`—serve as illustrative examples.

## Before you start

**Audience.** This guide is intended for Phoenix developers whose changes modify the write path,
the secondary indexer, the query optimizer, or the client commit pipeline, as well as committers
who review performance and overhead claims for data ingestion or secondary indexing.

**Prerequisites.**

* You have built a container image for your Phoenix tree and verified that a validation cluster
  starts cleanly, as detailed in [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md).
* You understand how to execute a Pherf scenario through the harness, as covered in
  [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md).
* Your container engine provides at least 16 GiB of total memory, allowing the preflight check to
  admit up to 12288 MiB of aggregate container limits.

| Cost | Value |
|---|---|
| Wall time | ~25 min: 4 Pherf loads (3 to 4 min), 2 psql runs, YARN, up/down; +20 min sustained |
| Docker memory | 12032 MiB in validation: 1 RegionServer (8704 MiB) and rendered YARN (3328 MiB) |
| Disk | Under 1 GiB of disk space within `$PX_OUT` |
| Files written | `$PX_OUT/howto/08/`, 7 run bundles in `$PX_OUT/runs/`, 1 session in `sessions/` |

Although the YARN group remains stopped until step 21, the preflight check still includes its
configured memory limits in the total allocation budget.

## Method

**Hypotheses.**

1. Increasing the commit batch size improves the Pherf ingestion rate because each commit amortizes
   the network round trip across a larger number of rows.
2. Maintaining a covered global index reduces overall ingestion throughput and increases the
   commit latency of each batch because every base table mutation must also generate and write
   corresponding index rows.
3. When the covered index is present, the query optimizer compiles a `WHERE KIND = 'close'`
   predicate into a range scan over the index table, and queries routed through the index return
   the exact same row counts as scans over the base data table.

**Controls.** All experiments execute against a single cluster instance using an identical row
count, identical data generation rules, and a fixed writer thread pool size
(`pherf.default.dataloader.threadpool=4`), ensuring that client concurrency does not vary with the
processor count of the host machine. Every test table is partitioned into 4 salt buckets and
configured with `UPDATE_CACHE_FREQUENCY=60000` so that individual statements do not repeatedly
query the server for table metadata. Each test run creates its target table during the `prepare`
phase and drops it during the `restore` phase, guaranteeing that no run observes residual data from
a previous execution.

**Burst and sustained ingest.** The primary workloads in this HOWTO insert 100,000 rows into a
freshly created, empty table. Because the HBase memstores absorb this volume entirely in memory,
these runs measure burst ingestion throughput. Sustained ingestion throughput in HBase is governed
by memstore flushes, background compactions, and blocking store-file thresholds, which a 100,000-row
load never triggers. Evaluating sustained ingestion requires writing a data volume several times
larger than the global memstore capacity—calculated as `PX_HBASE_MEMSTORE_SIZE` multiplied by the
RegionServer heap size—over a duration sufficient for flushes and compactions to take effect. In
the validation cluster class, the global memstore capacity is 0.35 x 1792 MiB, or approximately
630 MiB; the optional procedure after step 11 exercises this sustained write regime.

**Noise.** The validation cluster class produces `ADVISORY` timing evaluations because the host is
not verified to be quiet and container resource allocations are scaled down. Consequently, a single
run per variant establishes the directional effect of a change rather than its exact magnitude.
Because the `setup@load` stage window includes JVM startup, the pre-run readiness gate, and schema
DDL application, short loads exhibit lower apparent throughput across that window. In addition, the
first load executed after cluster bring-up incurs initial warm-up overhead on the RegionServer.

**What to measure and why.**

* Ingestion throughput in rows per second across the active write phase, calculated from
  `RESULT_Data_Load_Summary_load.csv` (`ROW_COUNT` divided by `LOAD_DURATION_IN_MS`), as well as
  across the entire `setup@load` window recorded in `stages.tsv`. The former measures the raw
  throughput of the writer threads, whereas the latter reflects the end-to-end rate observed by a
  user.
* Committed batch counts and batch commit latencies, captured under the `load:<scenario>:<table>`
  key in `drivers/pherf/results_HDR/LATENCY_load.json`, which records one observation per committed
  batch.
* Single-row commit latency in the psql driver, captured in the `time_ms` column for each executed
  statement in `drivers/psql/statements.tsv`.
* Server-side processing overhead, quantified by the counter deltas in `traces/counters.tsv` that
  the `trace` collector snapshots around the `run` verb.
* Deterministic row counts, verified via `pherf.result_rows` and explicit `COUNT(*)` queries. Any
  count mismatch constitutes a functional correctness failure, and all timing measurements must be
  disregarded until the defect is resolved.

**What invalidates the result.** A run is invalidated if a verification query returns a value
other than the expected row count, if a `contamination` record appears for any condition other than
the expected `quiet-host` exemption of a validation run, or if an unplanned service restart occurs
(exit 3). Furthermore, trace counter deltas are invalid if a RegionServer JVM restarted during the
run, because its counters reset to zero upon restart, and comparative A/B conclusions are invalid if
a variant alters two parameters simultaneously.

## Steps

### Bring up the cluster

1. Start a validation cluster with the YARN service group rendered into the deployment manifest.
   Although the YARN group remains stopped initially, the preflight memory check still includes its
   container limits in the total reservation because starting the group later will allocate those
   containers on the same engine:

   ```sh
   px-cluster --class validation -e PX_YARN=1 up
   ```

   ```text
   ... INFO  px-cluster: provisioning cluster default in scope px-default from .../deploy/default
   ```

   If the preflight check rejects the deployment with exit 3, the diagnostic message reports the
   exact memory arithmetic: the required container limits total 12032 MiB, while the available
   budget equals the total container engine memory minus 4096 MiB.

### Measure the Pherf bulk load

2. Write the DDL schema file for the base data table. Because Pherf sends the entire contents of
   each schema file as a single JDBC statement, the file must contain exactly one statement without
   a trailing semicolon:

   ```sh
   cat >"$HOWTO/a-ingest.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.INGEST (
     ID BIGINT NOT NULL PRIMARY KEY,
     KIND VARCHAR,
     AMOUNT INTEGER
   ) SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000
   EOF
   ```

   Specifying `SALT_BUCKETS=4` distributes monotonically increasing sequential primary keys evenly
   across 4 regions. Without salting, a sequential key directs all incoming writes to the final
   region, creating a write hotspot; see
   [11-scale-out-capacity-and-skew.md](11-scale-out-capacity-and-skew.md) for instructions on
   detecting region hotspots.

3. Author the Pherf workload scenario file. Its data mapping rules produce a unique sequential
   primary key for every generated record so that the resulting table row count is exact, and the
   sole query set verifies the total row count after the load completes:

   ```sh
   cat >"$HOWTO/ingest.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_ingest">
     <datamapping>
       <column>
         <type>BIGINT</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <minValue>1</minValue>
         <maxValue>1000000000</maxValue>
         <name>ID</name>
       </column>
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>LIST</dataSequence>
         <name>KIND</name>
         <valueList>
           <datavalue><value>open</value></datavalue>
           <datavalue><value>close</value></datavalue>
           <datavalue><value>move</value></datavalue>
         </valueList>
       </column>
       <column>
         <type>INTEGER</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <minValue>1</minValue>
         <maxValue>1000000</maxValue>
         <name>AMOUNT</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.INGEST" rowCount="100000" name="howtoIngest">
         <querySet concurrency="1" executionType="SERIAL" numberOfExecutions="1"
                   executionDurationInMs="60000">
           <query id="count" queryGroup="count"
                  statement="SELECT COUNT(*) FROM PX_HOWTO.INGEST"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

4. Create two Pherf configuration property files. Both files begin from the default Pherf
   properties from the source tree and pin the writer thread count, while the second file also
   reduces the commit batch size from 1000 rows to 100 rows. When duplicate keys appear in a
   properties file, the last occurrence takes precedence:

   ```sh
   { cat phoenix-pherf/config/pherf.properties; cat <<'EOF'
   pherf.default.dataloader.threadpool=4
   EOF
   } >"$HOWTO/b1000.properties"
   { cat "$HOWTO/b1000.properties"; cat <<'EOF'
   pherf.default.dataloader.batchsize=100
   EOF
   } >"$HOWTO/b100.properties"
   ```

   Passing `--properties FILE` completely replaces the driver's default configuration file, so the
   supplied file must contain every property key that Pherf requires. The harness driver appends
   its own four override keys at the end of the file: the result output directory, the summary file
   path, `pherf.verdict.noisyHost`, and `pherf.verdict.serverJmx`.

5. Run the baseline bulk load workload with 1000-row commit batches and no secondary index. The
   cluster remains running across every step in this HOWTO until the final `px-cluster down`, and
   the shared driver arguments for the bulk loads are stored once in `W`:

   ```sh
   W=(--scenario "$HOWTO/ingest.xml" --schema "$HOWTO/a-ingest.sql")
   RUN_B1000="$(px-run pherf -- "${W[@]}" --properties "$HOWTO/b1000.properties")"
   ```

6. Compute the writer ingestion throughput from the Pherf data load summary file, which records
   one CSV row per loaded table:

   ```sh
   awk -F ',' 'NR > 1 { printf "%s rows in %s ms: %.0f rows/s\n", $3, $4, $3 * 1000 / $4 }' \
     "$RUN_B1000/drivers/pherf/results/RESULT_Data_Load_Summary_load.csv"
   ```

   ```text
   100000 rows in 9412 ms: 10625 rows/s
   ```

   These numbers are illustrative; actual throughput values will vary depending on your host
   hardware.

7. Measure the total elapsed duration of the `setup@load` stage window, which includes JVM startup,
   the pre-run readiness gate, and schema DDL application in addition to data ingestion:

   ```sh
   awk -F '\t' '$3 == "setup@load" { t[$2] = $1 } END { print t["end"] - t["begin"], "ms" }' \
     "$RUN_B1000/stages.tsv"
   ```

   ```text
   15873 ms
   ```

   Dividing 100000 rows by this stage duration yields the end-to-end ingestion rate observed by a
   caller invoking `pherf -l`. The difference between this duration and the active write duration
   from step 6 reflects the fixed setup overhead of a load invocation. Either choose a row count
   large enough to amortize this fixed cost or report both throughput rates side by side.

8. Examine the batch commit statistics in the HDR latency report. Each sample recorded under the
   `load:<scenario>:<table>` operation key corresponds to one committed batch, meaning the count
   reports the total number of committed batches and the percentiles report the batch commit
   latency distribution:

   ```sh
   jq '.histograms[] | select(.operation | startswith("load:"))' \
     "$RUN_B1000/drivers/pherf/results_HDR/LATENCY_load.json"
   ```

   ```text
   {
     "operation": "load:howtoIngest:PX_HOWTO.INGEST",
     "phase": "steady",
     "count": 104,
     "minUs": 41216,
     "maxUs": 612351,
     "meanUs": 371845.3,
     "p50Us": 362495,
     "p90Us": 470015,
     "p99Us": 589823,
     "p999Us": 612351
   }
   ```

   The total batch count equals the row count divided by the batch size, plus at most one final
   partial batch per writer thread, and dividing this count by `LOAD_DURATION_IN_MS` yields the
   batch commit rate in batches per second. Because the harness metric exporter processes only the
   `*_run` files, these load-phase statistics do not appear in any metric export and must be read
   directly from this report.

9. Confirm the ingested row count. The validation count query executed during the run verb yields a
    single aggregate value, which the Pherf driver records as the result rows of that query group:

    ```sh
    cat "$RUN_B1000/drivers/pherf/groups.tsv"
    ```

    ```text
    group  anchor       start_ms  end_ms   run_count  result_rows  avg_time_ms ...
    count  group@count  1795...   1795...  1          100000       58 ...
    ```

    Although the file is tab-separated, the excerpt above aligns the columns for readability. The
    reported `result_rows` value must match the configured scenario row count. Passing
    `-rowCountOverride N` after a second `--` delimiter overrides the scenario's target row count,
    and the harness forwards that flag to both lifecycle verbs.

10. Run the second workload variant using a 100-row commit batch size while changing only the
    properties file argument:

    ```sh
    RUN_B100="$(px-run pherf -- "${W[@]}" --properties "$HOWTO/b100.properties")"
    ```

11. Run the third workload variant through the JDBC batch API. Supplying `-b` after the second
    `--` delimiter forwards the flag to both lifecycle verbs because the driver strips only
    `-runDurationMs`, `-threadCount`, and `-targetRate` when constructing the `prepare` command.
    With this mode enabled, Pherf buffers the rows of a batch via `addBatch` and transmits them
    together via `executeBatch`:

    ```sh
    RUN_BAPI="$(px-run pherf -- "${W[@]}" --properties "$HOWTO/b1000.properties" -- -b)"
    ```

    You can also pass `-writerThreadSize N` after the second `--` delimiter to override the writer
    thread pool size in place of `pherf.default.dataloader.threadpool`. Note that in a
    co-generated scenario, Pherf commits one `blockSize` unit per batch, so the effective commit
    batch size is controlled by the `blockSize` attribute of `coGeneration` rather than by
    `pherf.default.dataloader.batchsize`.

### Optional: sustained ingest

This optional variant ingests 10,000,000 rows, generating a data volume several times larger than
the global memstore capacity of the validation cluster class. At the step 6 ingestion rate of about
10,000 rows per second, the load requires approximately 17 minutes to complete. Because Pherf
records one latency sample per committed batch rather than per individual row, the client JVM heap
readily accommodates the run. This load completes well within the default 3600 s execution bound
(`PX_TIMEOUT_RUN`) shared by the `prepare` and `run` phases, which likewise bounds the Pherf
process in client 0, so the command requires no `--timeout` option.

Capture the RegionServer flush and compaction counters before starting the load. The `px-cluster
endpoint` names the host port of the info server; it needs `curl` on the host:

```sh
port="$(px-cluster endpoint regionserver 0 http | cut -f 2)"
B='Hadoop:service=HBase,name=RegionServer,sub=Server'
rs_counters() { curl -s "http://127.0.0.1:$port/jmx?qry=$B" | jq -c '.beans[0] |
  {flushedCellsCount, compactedCellsCount, storeFileCount, updatesBlockedTime}'; }
rs_counters >"$HOWTO/rs-before.txt"
```

Execute the load with the row count override, and then snapshot the RegionServer counters a second
time:

```sh
RUN_SUST="$(px-run pherf -- "${W[@]}" --properties "$HOWTO/b1000.properties" \
  -- -rowCountOverride 10000000)"
rs_counters >"$HOWTO/rs-after.txt"
paste "$HOWTO/rs-before.txt" "$HOWTO/rs-after.txt"
```

Compare the throughput reported in the load summary of `$RUN_SUST` against the burst throughput
measured in step 6. A lower sustained rate accompanied by flushes and compactions across the two
snapshots reflects the steady-state cost of the storage write path. An increase in
`updatesBlockedTime` above 0 indicates that incoming writes waited for memstore flushes to
complete, showing that either the blocking store-file threshold or the memstore capacity limit
governed the ingestion rate. Because these JMX counters accumulate over the lifetime of the
RegionServer process, always evaluate the difference between the two snapshots. This optional
variant was not executed during the live validation run.

### Cost the index on the bulk load

12. Declare the covered global index in a second DDL schema file. Because Pherf applies schema files
    in lexicographical order by filename, prefixing the index file with `b-` ensures that it runs
    after `a-` and that the base data table already exists when the index is created:

    ```sh
    cat >"$HOWTO/b-ingest-index.sql" <<'EOF'
    CREATE INDEX IF NOT EXISTS INGEST_KIND ON PX_HOWTO.INGEST (KIND) INCLUDE (AMOUNT)
    EOF
    ```

13. Run the indexed bulk load by appending the index schema file to the baseline configuration
    from step 5:

    ```sh
    RUN_IDX="$(px-run pherf -- "${W[@]}" --schema "$HOWTO/b-ingest-index.sql" \
      --properties "$HOWTO/b1000.properties")"
    ```

    During cleanup, the `restore` stage drops `PX_HOWTO.INGEST` with `CASCADE`, which automatically
    removes the dependent secondary index as well.

14. Collect and compare the throughput and batch latency metrics across all four bulk load runs
    in a single table:

    ```sh
    for r in "$RUN_B1000" "$RUN_B100" "$RUN_BAPI" "$RUN_IDX"; do
      rate="$(awk -F ',' 'NR > 1 { printf "%.0f", $3 * 1000 / $4 }' \
        "$r/drivers/pherf/results/RESULT_Data_Load_Summary_load.csv")"
      batch="$(jq -r '.histograms[] | select(.operation | startswith("load:")) |
        " \(.count) \(.p50Us) \(.p99Us)"' "$r/drivers/pherf/results_HDR/LATENCY_load.json")"
      echo "${r##*/} rows/s $rate batches,p50Us,p99Us$batch"
    done
    ```

    ```text
    20261122T101501Z-a1b2 rows/s 10625 batches,p50Us,p99Us 104 362495 589823
    20261122T101902Z-c3d4 rows/s 6211 batches,p50Us,p99Us 1004 51199 120319
    ...
    ```

    These figures are illustrative, though you should expect both the 100-row batch size and the
    secondary index to reduce overall ingestion throughput. A single run per variant on a
    validation cluster establishes only a directional trend rather than a definitive benchmark
    result; refer to [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md) for the
    replication counts, medians, and confidence intervals required to support a formal performance
    claim.

### Cost the index per row, with plans and trace counters

The `trace` collector snapshots the Phoenix and HBase counters on every RegionServer immediately
before and immediately after the `run` verb. Because a Pherf data load executes during the
`prepare` verb, its server-side counter deltas fall outside the collector's observation window. By
contrast, the psql driver executes all non-`--prepare` SQL scripts during the `run` verb, allowing
this section to capture per-row write activity directly within the traced window.

15. Write the two schema files—one without the covered index and one with it—together with the SQL
    scripts executed by the run verb. Because PhoenixRuntime commits automatically after each SQL
    statement in a script, every `UPSERT` statement executes as an independent single-row commit:

    ```sh
    cat >"$HOWTO/events-a.sql" <<'EOF'
    CREATE TABLE IF NOT EXISTS PX_HOWTO.EVENTS (
      ID BIGINT NOT NULL PRIMARY KEY,
      KIND VARCHAR,
      AMOUNT INTEGER
    ) SALT_BUCKETS=4, UPDATE_CACHE_FREQUENCY=60000;
    EOF
    { cat "$HOWTO/events-a.sql"; cat <<'EOF'
    CREATE INDEX IF NOT EXISTS EVENTS_KIND ON PX_HOWTO.EVENTS (KIND) INCLUDE (AMOUNT);
    EOF
    } >"$HOWTO/events-b.sql"
    awk 'BEGIN { for (i = 1; i <= 2000; i++)
      printf "UPSERT INTO PX_HOWTO.EVENTS VALUES (%d, \047%s\047, %d);\n", i,
        (i % 3 == 0) ? "close" : "open", i }' >"$HOWTO/ingest.sql"
    cat >"$HOWTO/plan.sql" <<'EOF'
    EXPLAIN SELECT ID, AMOUNT FROM PX_HOWTO.EVENTS WHERE KIND = 'close';
    SELECT COUNT(*) FROM PX_HOWTO.EVENTS;
    SELECT COUNT(*) FROM PX_HOWTO.EVENTS WHERE KIND = 'close';
    EOF
    ```

    The ingestion script writes 2000 rows in total, exactly 666 of which satisfy `KIND = 'close'`.
    These two deterministic row counts serve as the authoritative correctness checks for each run.

16. Run both the unindexed and indexed variants with the trace collector enabled. Because
    validation-class runs do not enable any collectors by default, passing `--collect trace`
    activates this collector explicitly:

    ```sh
    RUN_A="$(px-run psql --collect trace -- \
      --prepare "$HOWTO/events-a.sql" "$HOWTO/ingest.sql" "$HOWTO/plan.sql")"
    RUN_B="$(px-run psql --collect trace -- \
      --prepare "$HOWTO/events-b.sql" "$HOWTO/ingest.sql" "$HOWTO/plan.sql")"
    ```

17. Confirm the returned row counts and review the query execution plans. The formatted plan output
    and query results for the second run script are saved in `drivers/psql/out/run-2.out`, where
    the first line immediately following each dashed separator line contains the first row of a
    statement's result set:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      echo "== ${r##*/}"; awk '/^-+ / { getline; print }' "$r/drivers/psql/out/run-2.out"
    done
    ```

    ```text
    == 20261122T103010Z-e5f6
    CLIENT 4-CHUNK PARALLEL 4-WAY ROUND ROBIN FULL SCAN OVER PX_HOWTO.EVENTS ...
    2000
    666
    == 20261122T103140Z-g7h8
    CLIENT 4-CHUNK PARALLEL 4-WAY ROUND ROBIN RANGE SCAN OVER PX_HOWTO.EVENTS_KIND ...
    2000
    666
    ```

    The plan strings shown above are illustrative, as the exact phrasing depends on the Phoenix
    version under test. The essential verification criteria are the scan strategy and the target
    table: `FULL SCAN OVER PX_HOWTO.EVENTS` when no index exists, versus
    `RANGE SCAN OVER PX_HOWTO.EVENTS_KIND` when the covered index is available. Codify this
    assertion as an executable shell command so that any unexpected plan regression fails an
    automated check:

    ```sh
    grep -q 'RANGE SCAN OVER PX_HOWTO.EVENTS_KIND' "$RUN_B/drivers/psql/out/run-2.out" &&
      echo "index plan ok"
    ```

    Note that `drivers/psql/statements.tsv` records only the number of returned rows for each
    statement (where the plan statement reports one row per line of plan output) rather than the
    plan text itself. Pherf runs also capture query execution plans in the `EXPLAIN_PLAN` column
    of `drivers/pherf/results/RESULT_*_aggregate.csv`.

18. Contrast the single-row commit latency distributions. `statements.tsv` has columns `file index
    statement operation rows time_ms`, and an `UPSERT` row has the operation `upserted`:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      awk -F '\t' '$4 == "upserted" { print $6 }' "$r/drivers/psql/statements.tsv" | sort -n |
        awk -v r="${r##*/}" '{ a[NR] = $1; s += $1 }
          END { printf "%s n=%d mean=%.2f p50=%d p99=%d ms\n", r, NR, s / NR,
            a[int(NR * 0.5)], a[int(NR * 0.99)] }'
    done
    ```

    ```text
    20261122T103010Z-e5f6 n=2000 mean=2.31 p50=2 p99=5 ms
    20261122T103140Z-g7h8 n=2000 mean=4.87 p50=5 p99=9 ms
    ```

    These latency figures are illustrative. Because PhoenixRuntime formats statement execution
    times in seconds to 3 decimal places, individual statement timings have a resolution of 1 ms.
    The aggregate execution time for each script file is also exported as the
    `psql.file_duration_ms` metric under the `script@<n>` anchor in `metrics/aggregate.tsv`.

19. Contrast the server-side execution work between both runs. The file `traces/counters.tsv` uses
    the schema `role index bean counter start stop delta`. Filter the output to display the
    read and write request counters from the Server bean along with every non-zero counter delta
    from the indexer beans:

    ```sh
    for r in "$RUN_A" "$RUN_B"; do
      echo "== ${r##*/}"
      awk -F '\t' 'NR > 1 && $7 != 0 && ($4 ~ /^(write|read)RequestCount$/ ||
        $3 == "PhoenixIndexer" || $3 == "GlobalIndexChecker") {
        print $1 "-" $2, $3, $4, $7 }' "$r/traces/counters.tsv"
    done
    ```

    ```text
    == 20261122T103010Z-e5f6
    regionserver-0 Server writeRequestCount 2004
    regionserver-0 Server readRequestCount 2012
    == 20261122T103140Z-g7h8
    regionserver-0 Server writeRequestCount 6004
    regionserver-0 PhoenixIndexer preIndexUpdateTime_num_ops 2000
    regionserver-0 PhoenixIndexer postIndexUpdateTime_num_ops 2000
    ...
    ```

    Both the numeric values and specific counter names above are illustrative: the trace collector
    captures every integer JMX attribute whose name ends in `Count` or `_num_ops`, and any bean
    not published by a given server simply produces no rows. Comparing the indexed run against the
    unindexed baseline reveals additional server write requests per ingested row alongside indexer
    operation counters that increment once per data batch. A non-zero delta on a
    `GlobalIndexChecker` repair counter indicates that a read encountered unverified index rows and
    triggered read-time index repair, which a healthy single-writer run does not require. Remember
    that counter deltas are valid only if the RegionServer JVM remained up continuously throughout
    the run; confirm that its `diff` entry in `run-record.tsv` is `unchanged`.

    In addition, each bundle's `tables.tsv` file records the effective `UPDATE_CACHE_FREQUENCY`
    value queried back from `SYSTEM.CATALOG` after the `prepare` phase:

    ```sh
    cat "$RUN_B/tables.tsv"
    ```

    ```text
    schema    table   update_cache_frequency
    PX_HOWTO  EVENTS  60000
    ```

    When a table uses the default setting (recorded as `default` in the file), the client re-reads
    table metadata from the catalog on every statement execution. Always keep this setting equal
    across both variants of an A/B comparison so that the experiment does not measure two changes
    at once.

### Start YARN only when a test needs it

The YARN service group—comprising the ResourceManager, NodeManager, and JobHistoryServer—is
rendered into the cluster deployment manifest in a stopped state. It starts only on demand, and
every start invocation re-runs the host memory preflight check before launching any containers.

20. Launch the YARN service group manually and check its runtime status:

    ```sh
    px-cluster group start yarn
    px-cluster group status yarn
    ```

    ```text
    role             index  ready  elapsed_ms
    resourcemanager  0      yes    21877
    ...
    group  running  desired  state
    yarn   3        3        up
    ```

    The start command outputs a readiness table for every service instance in the group. An exit
    code of 3 indicates that the host memory check rejected the request; in that case, no
    containers are started and the error message reports the memory figures.

21. Stop the YARN group to return its container memory to the container engine while retaining its
    persisted state:

    ```sh
    px-cluster group stop yarn; px-cluster group status yarn
    ```

    ```text
    ...
    group  running  desired  state
    yarn   0        3        stopped
    ```

22. Alternatively, express the YARN requirement declaratively inside a suite definition. Specifying
    `requires: yarn` instructs `px-suite` to run the memory preflight check, start the YARN group
    before the test begins, and stop the group once the test completes—even if the test itself
    fails. Relative file paths in `args:` resolve relative to the directory containing the suite
    file:

    ```sh
    cat >"$HOWTO/yarn.suite" <<'EOF'
    suite: howto-08-yarn
    description: One psql test that runs with the YARN group up
    class: validation
    timeout: 600

    test: count
      name: Load and count rows while YARN is up
      requires: yarn
      driver: psql
      args: --prepare events-a.sql ingest.sql plan.sql
    EOF
    px-suite "$HOWTO/yarn.suite" | tee "$HOWTO/yarn.tsv"
    ```

    ```text
    1	count	PASS	0	61873		.../runs/...
    result	PASS	0	.../sessions/...
    ```

    The suite session directory, whose path appears at the end of the `result` row, contains a
    `groups.tsv` log tracking the start and stop transitions of the service group. A `px-suite`
    session stops any role groups that it started—here YARN—while leaving the cluster running
    until the final `px-cluster down` tears it down.

### Not covered: MapReduce bulk load

Executing `CsvBulkLoadTool` requires a MapReduce job client inside client 0 configured with the
cluster's YARN site settings and the Phoenix client JAR on the MapReduce job classpath. Because the
W1 validation effort did not verify that classpath and client configuration inside the container
image, this HOWTO omits a command for it. Milestone A4 verified only that the YARN service group
executes a standard MapReduce job (specifically, running the `pi` example to a `SUCCEEDED` state).

## Read the result

* **Rate.** The rows-per-second figure derived from the Pherf load summary represents the active
  writer throughput. Compare variants only when they share the exact same row count, thread count,
  and cluster topology. Observing a higher ingestion rate at a batch size of 1000 than at a batch
  size of 100 confirms hypothesis 1. Meanwhile, the rate computed across the `setup@load` window
  reflects the end-to-end user rate, where the difference between the two rates accounts for the
  fixed overhead of JVM startup, the pre-run gate, and schema DDL application.
* **Commit latency.** Because the `load:` latency key records one sample per committed batch, its
  p50 latency increases with larger batch sizes even as overall row throughput rises. Always report
  the configured batch size alongside any batch commit latency figure.
* **Index cost.** The ratio of the indexed bulk load rate to the unindexed bulk load rate measures
  the throughput cost of maintaining the secondary index, whereas the ratio of the mean single-row
  commit latencies measures its per-commit latency cost. The server-side trace counter deltas
  explain the underlying mechanism: additional HBase write requests and indexer hook invocations
  for every ingested row.
* **Plans.** Verification depends on matching both the scan strategy and the target table in the
  plan output. Any change in query plan selection between two builds is a significant finding in
  its own right, regardless of how execution timings compare.
* **Counts.** The `100000` loaded result rows and the psql query counts of `2000` and `666` are
  authoritative invariants across all hosts. Any discrepancy between a query count served by the
  index and one served by the base data table indicates an index consistency defect.
* **Severity.** In the validation cluster class, all timing evaluations carry an `ADVISORY`
  severity, whereas row count mismatches and functional checks retain a `FAIL` severity whenever
  they fail.

## Reproduce it

* **Inputs.** Retain the generated artifacts under `$PX_OUT/howto/h08/` alongside the procedure in
  this HOWTO. Because Pherf's rule-based generators sample `AMOUNT` and `KIND` values randomly
  without a fixed seed, only the sequential primary keys and the aggregate row counts reproduce
  identically across runs. When you need deterministic non-key column values, use co-generation as
  described in [07-read-what-you-wrote.md](07-read-what-you-wrote.md).
* **Version set and image.** Every run bundle records the exact component versions and container
  image digests used for the test:

  ```sh
  jq '.versions' "$RUN_IDX/manifest.json"
  awk -F '\t' 'NR > 1 && $1 == "regionserver" { print $1, $2, $4 }' \
    "$RUN_IDX/cluster/images.tsv"
  ```

* **Bundles.** Archive the six run bundles generated across steps 5 through 16 into a single file:

  ```sh
  tar -C "$PX_OUT/runs" -czf "$HOWTO/bundles.tgz" "${RUN_B1000##*/}" "${RUN_B100##*/}" \
    "${RUN_BAPI##*/}" "${RUN_IDX##*/}" "${RUN_A##*/}" "${RUN_B##*/}"
  ```

  The suite session produced in step 22 is stored under `$PX_OUT/sessions/`, and the path to its
  underlying test run bundle is recorded in the seventh column of its row in both `$HOWTO/yarn.tsv`
  and the session's `results.tsv`.

## Clean up

The `px-suite` session from step 22 stopped the YARN group it started and left the cluster running.

1. Shut down and remove the cluster:

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

* [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md): explains the pherf driver,
  its fixed metric labels, and its run bundle files.
* [10-profiling-and-root-cause.md](10-profiling-and-root-cause.md): covers the remaining
  collectors and demonstrates how to attribute performance costs using a profile.
* [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md): details replication
  methodology and statistical comparisons required to turn an index cost measurement into a
  definitive result.
* [../driver-contract.md](../driver-contract.md): defines the execution contract for the psql and
  pherf drivers.
* [../bundle-format.md](../bundle-format.md): specifies the layout and schema of `stages.tsv`,
  `tables.tsv`, `traces/`, and the pherf driver output files.
* [LATENCY.md](../../../../phoenix-pherf/LATENCY.md): documents the `load` operation key and the
  JSON latency report structure.
* [P02](../../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md),
  Pherf scenario authoring: covers data mapping rules, schema files, and the scenario validator.
