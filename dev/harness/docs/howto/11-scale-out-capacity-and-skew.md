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

# How to plan capacity across 1, 3 and 5 RegionServers and detect hotspots

## The question

How much load can one RegionServer sustain, how much can 3 and 5 sustain, and is the workload
distributed evenly across them? Effective capacity planning requires determining the maximum
sustainable throughput at each cluster size and evaluating the scaling efficiency across those
topologies. A hotspot undermines horizontal scale-out: when a single server absorbs most of the
read traffic, adding more servers yields no throughput gain. This HOWTO measures the sustainable
rate across 1, 3, and 5 RegionServers, dynamically resizes a running cluster, and grades the read
skew of a workload.

Every command in this HOWTO has been validated against the harness and Pherf source code. The
sizing checks in steps 1 and 19 were captured from a live execution in round U5, whereas the
remaining steps were not executed during the W1 live run and therefore show illustrative output
excerpts derived from the code.

## Before you start

**Audience.** Phoenix developers modifying region placement, salting, the client scan execution
path, or parallel scan logic, as well as operators and prospective users sizing a cluster.

**Prerequisites.**

* A container image for your build exists and a validation cluster starts cleanly:
  [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md).
* You know how to locate the saturation knee of a single cluster:
  [06-saturation-knee.md](06-saturation-knee.md). This HOWTO applies that methodology at each
  cluster size.
* A container engine configured with 24 GiB of memory is required for 5 RegionServers, whereas a
  16 GiB allocation accommodates only 1 and 2 RegionServers.
* The `curl` and `jq` command-line tools must be installed on the host to execute the JMX queries
  in step 14.

| Cost | Value |
|---|---|
| Wall time | Roughly 80 to 100 min: 18 rate runs of 2 to 3 min, 4 skew runs of 3 min, 2 scales |
| Docker memory | Validation sizing requires 8704 (1 RS), 13824 (3 RS), or 18944 MiB (5 RS) |
| Docker memory | Measurement-shaped (24 GiB) uses 12288 (1 RS), 19456 (3 RS), or 19328 MiB (5 RS) |
| Disk | Less than 2 GiB of free space under `$PX_OUT` |
| Files written | Working files under `$PX_OUT/howto/h11/` and about 22 bundles in `$PX_OUT/runs/` |

**The sizing arithmetic.** The validation class assigns a fixed memory limit to each service
instance: ZooKeeper 512, NameNode 1024, DataNode 1024, master 1024, client 1536, tooling 512, and
setup 512 MiB, totaling 6144 MiB, plus 2560 MiB per RegionServer. Consequently, topologies with 1,
3, and 5 RegionServers total 8704, 13824, and 18944 MiB (8.50, 13.50, and 18.50 GiB),
respectively. The preflight check admits deployments up to the engine memory minus 4096 MiB of
headroom, leaving a budget of 12288 MiB on a 16 GiB engine and 19905 MiB on the 24001 MiB engine
of the reference host. As a result, 5 RegionServers fit within a 24 GiB engine with 961 MiB to
spare, whereas 3 RegionServers do not fit within a 16 GiB engine.

Initialize every shell session for this guide with the following three environment commands:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h11"; mkdir -p "$HOWTO"
```

## Method

**Hypotheses.**

1. Sustainable throughput increases with the RegionServer count, while scaling efficiency `E(N) =
   X(N) / (N x X(1))` is below 1, where `X(N)` is the highest offered rate that `N` RegionServers
   sustain.
2. A single-region table routes every read to a single server, producing a read skew equal to the
   server count that fails the 1.5 upper bound. A table salted into a multiple of the server count
   distributes reads across the cluster and passes the check.

**Controls.** Every execution uses a single scenario and a fixed seed. The table is partitioned
into 15 salt buckets so that deployments of 1, 3, and 5 RegionServers each host an equal number of
regions. Each run creates the table during `prepare` and drops it during `restore`, ensuring that
its regions are distributed across whichever RegionServers are active at that time. Identical 60 s
rate steps are executed at each cluster size using a single client container.

**A workload that the cluster bounds.** Horizontal scale-out is observable only when the servers
saturate before the client does. H06 demonstrated that a point-lookup workload on a single host is
bottlenecked by the client and the host: client 0 consumed 328% of a processor compared to 98% for
the RegionServer. Under such a workload, every cluster size encounters the same client ceiling, so
`E(N)` is approximately 1/N by construction and reveals nothing about Phoenix scaling behavior.
Therefore, this HOWTO uses a server-side aggregation over a block of 10,000 rows, requiring the
RegionServers to scan many rows per operation while returning only a single row to the client.

**Noise and the limits of one host.** Because all containers share the processors and disk of a
single host, adding RegionServers adds JVMs, heaps, and request handlers rather than physical
processors. Accordingly, this experiment evaluates how Phoenix and HBase distribute work and where
the client or an individual server limits throughput; it does not measure the hardware capacity of
a multi-machine cluster. The validation class produces `ADVISORY` timing verdicts. The
measurement-shaped profile on a 24 GiB engine is tagged with `contamination sizing`, making its
timing verdicts `ADVISORY` as well. Authoritative timing verdicts require the measurement class on
an engine of approximately 48 GiB, and apply only to topologies that fit within that budget.

**What to measure and why.**

* At each cluster size and target rate, record `pherf.health.attainment` (the completed share of
  the offered workload) and the p99 latency of the `response` metric, which captures queueing
  delay. As detailed in [06-saturation-knee.md](06-saturation-knee.md), the saturation knee is the
  highest step whose attainment reaches at least 90% (the default threshold of
  `pherf.verdict.attainment.minPct`) and whose response p99 remains within your latency objective;
  `X(N)` is the achieved rate at that step, computed as the offered rate multiplied by attainment.
* To identify the bottleneck, inspect `pherf.health.saturation`, which distinguishes saturated
  workers (the cluster) from a lagging dispatcher (the client), alongside
  `pherf.health.client_pool`, which flags an undersized client connection pool.
* To evaluate load distribution, inspect `pherf.health.read_skew`, which computes the ratio of
  reads on the busiest RegionServer to the cluster mean using per-table read counters from each
  RegionServer.

**What invalidates the result.** A run in which attainment fails due to client saturation is
invalid because the client, rather than the cluster, determined `X(N)`. If the saturation verdict
or processor utilization split indicates a client bottleneck at N = 1, stop immediately because
scale-out cannot be evaluated on this host (step 5). When all cluster topologies sustain the peak
step, `X(N)` is right-censored and must be reported as "at least" that rate rather than as a
capacity ceiling. A region migration during a run, such as one initiated by the balancer, causes
the read skew check to report `SKIP` with "a counter went back". A measurement window shorter than
60 s, which the read skew check never grades, or a change in row count or seed across cluster
sizes also invalidates the comparison.

## Steps

### Check the sizing before you start

1. Execute a read-only preflight check across each of the three target cluster topologies. Running
   `px-cluster up --dry-run` renders the deployment into a temporary scratch directory, validates
   the host environment, container image, engine memory budget, and host ports, and outputs the
   deployment plan and verification rows without starting any containers:

   ```sh
   for n in 1 3 5; do
     px-cluster --class validation -e PX_RS_COUNT="$n" --dry-run up |
       awk -F '\t' '$1 == "memory:regionserver" || $1 == "memory_fit" { print $1, $3 }'
   done
   ```

   ```text
   memory:regionserver 1 x 2560 MiB = 2560 MiB
   memory_fit the limits 8704 MiB (8.50 GiB) fit the budget with 11201 MiB (10.94 GiB) ...
   memory:regionserver 3 x 2560 MiB = 7680 MiB
   memory_fit the limits 13824 MiB (13.50 GiB) fit the budget with 6081 MiB (5.94 GiB) ...
   memory:regionserver 5 x 2560 MiB = 12800 MiB
   memory_fit the limits 18944 MiB (18.50 GiB) fit the budget with 961 MiB (0.94 GiB) ...
   ```

   Whenever a topology exceeds the container engine's memory budget, `up --dry-run` reports the
   sizing breakdown and terminates with exit code 3, matching the behavior of an actual bring-up.
   `px-cluster up` performs this identical validation before creating any containers.

### Measure one RegionServer

2. Bring up a validation cluster with a single RegionServer:

   ```sh
   px-cluster --class validation up
   ```

3. Write the table DDL and workload scenario files. The open-loop arrival schedule specifies a
   single 60 s flat stage whose rate is scaled on each run by `-targetRate`. The query performs a
   `SCAN` over one co-generated block of 10,000 rows and computes a server-side aggregation on the
   RegionServers. Applying the `DROP` policy with a lag limit of 1000 ms confines an overloaded
   step to its scheduled window, while a generous queue bound (`concurrency`) prevents artificial
   client queueing:

   ```sh
   cat >"$HOWTO/cap.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.CAP (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) SALT_BUCKETS=15, UPDATE_CACHE_FREQUENCY=60000
   EOF
   cat >"$HOWTO/cap.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_cap">
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
       <scenario tableName="PX_HOWTO.CAP" rowCount="200000" name="howtoCap">
         <coGeneration seed="11" blockSize="10000"/>
         <querySet concurrency="32" warmupExecutions="5">
           <openLoop arrival="POISSON" seed="11" lagPolicy="DROP" maxLagMs="1000">
             <stage durationMs="60000" rate="100"/>
           </openLoop>
           <query id="agg" kind="SCAN"
                  statement="SELECT COUNT(*), MAX(V) FROM PX_HOWTO.CAP
                             WHERE K &gt;= [K] AND K &lt;= [K]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

   Under co-generation, a `SCAN` predicate references its range column twice so that each
   statement selects one committed block whose bounds span exactly 10,000 rows. Because the table
   is salted, scanning a single block touches every salt bucket, ensuring that every RegionServer
   executes a share of each operation. See
   [04-co-generation.md](../../../../phoenix-pherf/docs/howto/04-co-generation.md) for the range
   column specification rules. If you enable `-connectionCache`, set `pherf.default.threadpool` in
   a `--properties` file to at least the concurrency level;
   [06-saturation-knee.md](06-saturation-knee.md) covers the client configuration knobs.

4. Define a helper script for the rate sweep so that every cluster topology runs the identical
   progression of target rates. The cluster remains running between iterations, and each run
   appends a summary row containing the RegionServer count, offered rate, exit code, and bundle
   directory path to `capacity.tsv`:

   ```sh
   cat >"$HOWTO/steps.sh" <<'EOF'
   n=$1
   for rate in 100 200 400 800 1600 3200; do
     run="$(px-run pherf --collect container -- --scenario "$HOWTO/cap.xml" \
       --schema "$HOWTO/cap.sql" -- -targetRate "$rate")"
     printf '%s\t%s\t%s\t%s\n' "$n" "$rate" "$?" "$run" >>"$HOWTO/capacity.tsv"
   done
   EOF
   HOWTO="$HOWTO" sh "$HOWTO/steps.sh" 1
   ```

   The driver forwards `-targetRate` exclusively to the `run` verb, leaving the data load phase
   unaffected. The highest rate step must exceed the saturation knee of the largest cluster size;
   if the top step keeps pace at 5 RegionServers, append a higher step and run it across all sizes.

5. Confirm the bottleneck on the single-RegionServer topology first. Review the saturation verdict
   and compare the processor utilization of client 0 against that of the RegionServer during the
   highest rate step, using the metrics recorded by the container sampler in `steps.sh`:

   ```sh
   run="$(awk -F '\t' '$1 == 1 { r = $4 } END { print r }' "$HOWTO/capacity.tsv")"
   awk -F '\t' '$1 == "pherf.health.saturation" { print $2, $5 }' "$run/report/verdicts.tsv"
   read -r S E <<<"$(awk -F '\t' '$4 ~ /^schedule@/ { s = $1; e = $2 } END { print s, e }' \
     "$run/metrics/aggregate.tsv")"
   for f in client-0 regionserver-0; do
     awk -F '\t' -v n="$f" -v s="$S" -v e="$E" 'NR > 1 && $1 >= s && $1 <= e { t += $4; c++ }
       END { printf "%s cpu mean %.0f%% over %d samples\n", n, t / c, c }' \
       "$run/metrics/container/$f.tsv"
   done
   ```

   ```text
   PASS peak lag ... ms (scenario howtoCap query set 1), within 1000 ms; ...
   client-0 cpu mean ...% over ... samples
   regionserver-0 cpu mean ...% over ... samples
   ```

   Bounding the averaging window to the open-loop schedule interval excludes the initial data load
   phase. The excerpt above illustrates the output format. For this experiment to be valid, the
   RegionServer must be the limiting bottleneck, exhibiting high processor utilization while client
   0 retains spare headroom. If client 0 approaches its share of the host processors, or if the
   saturation verdict diagnostic identifies a lagging dispatcher, stop here because this host
   cannot demonstrate horizontal scale-out for this workload. Either select a workload that
   performs more server-side work per operation, or drive the workload from a fleet of Distributed
   Pherf agents; see [How to run Pherf on a fleet through the
   harness](14-fleet-through-the-harness.md) and [How to scale out the load generator and read
   fleet latency correctly][d04]. Note that on a single machine the fleet agents still share the
   container engine's processors with the RegionServers, so deploying a local fleet removes the
   bottleneck of a single client JVM without removing the capacity bound of the host.

### Change the size of the live cluster

6. Resize the running cluster to 3 RegionServers. Because `scale` re-renders the deployment from
   the persisted cluster record with the updated instance count and writes that count back to the
   record, you do not need to repeat any bring-up flags; any render that modifies another service
   instance is rejected with exit code 2. The preflight memory check verifies the updated topology
   before launching containers and aborts with exit code 3 if the deployment exceeds the budget:

   ```sh
   px-cluster scale regionserver 3
   px-cluster status regionserver
   ```

   ```text
   role          index  instance_id  state    ready  image_ref  node
   regionserver  0      ...          running  yes    ...
   regionserver  1      ...          running  yes    ...
   regionserver  2      ...          running  yes    ...
   ```

   Similarly, `px-pherf scale COUNT` resizes the Distributed Pherf agent pool using the persisted
   cluster record and blocks until every agent reports IDLE; see [H14][h14].

   During a scale-out operation, only the newly added instances are started. Existing regions
   remain on their current hosts until the HBase balancer migrates them, meaning that a newly
   added RegionServer initially hosts no regions. Because each run in this HOWTO creates its target
   table after the scaling step completes, HBase assigns the new table's regions across all active
   servers immediately, so the measurement does not depend on waiting for the balancer. If the
   balancer relocates a region while a run is in progress, that run reports its read skew verdict
   as `SKIP`.

7. Run the identical rate sweep against the 3-RegionServer topology:

   ```sh
   HOWTO="$HOWTO" sh "$HOWTO/steps.sh" 3
   ```

### Detect a read hotspot

Pherf evaluates read skew by sampling the per-table read request counts from each RegionServer JMX
servlet at the beginning and end of the measured workload phase. The pherf driver supplies the
RegionServer info-server addresses to Pherf only when `--read-skew` is enabled. The evaluation
requires at least 2 RegionServers and a measured duration of at least 60 s, because RegionServer
JMX counters may lag by roughly 15 s.

8. Define three DDL variants for the target table—unsalted single-region, hash-salted, and split on
   primary key boundaries:

   ```sh
   cat >"$HOWTO/skew-one.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.SKEW (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) UPDATE_CACHE_FREQUENCY=60000
   EOF
   cat >"$HOWTO/skew-salted.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.SKEW (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) SALT_BUCKETS=6, UPDATE_CACHE_FREQUENCY=60000
   EOF
   cat >"$HOWTO/skew-split.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.SKEW (
     K BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   ) UPDATE_CACHE_FREQUENCY=60000 SPLIT ON (5000, 10000, 15000, 20000, 25000)
   EOF
   ```

   Because the primary keys are sequential from 0 to 29999, the 5 split points divide the table
   into 6 equal-sized key ranges.

9. Create the workload scenario file for 30000 rows and a 90 s point-lookup stage so that the
   measurement window exceeds the 60 s minimum threshold. The co-generation multiplicative walk
   traverses every row once per cycle in a pseudo-random hashed order, distributing read requests
   uniformly across all key ranges:

   ```sh
   cat >"$HOWTO/skew.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_skew">
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
       <scenario tableName="PX_HOWTO.SKEW" rowCount="30000" name="howtoSkew">
         <coGeneration seed="12" blockSize="1000" walk="MULTIPLICATIVE"/>
         <querySet concurrency="8" warmupExecutions="2">
           <openLoop arrival="POISSON" seed="12" lagPolicy="LATE">
             <stage durationMs="90000" rate="50"/>
           </openLoop>
           <query id="get" statement="SELECT V FROM PX_HOWTO.SKEW WHERE K = [K]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

10. Execute the workload against the single-region table definition with `--read-skew` enabled:

    ```sh
    RUN_ONE="$(px-run pherf -- --read-skew --scenario "$HOWTO/skew.xml" \
      --schema "$HOWTO/skew-one.sql")"
    awk -F '\t' '$1 == "pherf.health.read_skew" { print $2, $3, $5 }' \
      "$RUN_ONE/report/verdicts.tsv"
    ```

    ```text
    ... INFO  px-run: run ... ends at FAIL (exit 1, failure)
    FAIL share skew 3.00 (busiest / mean), above the maximum 1.5: http://regionserver-1:16035
    has 4519 of 4519 reads on 3 servers
    ```

    The counter values are illustrative, and the specific server hosting the single region varies
    across runs. Across 3 RegionServers, routing all traffic to one region yields a read skew of
    exactly 3. Because this check belongs to the `share` verdict class, which `px-report` never
    demotes, the run fails with exit 1 even under the validation class or on a contaminated host.

11. Repeat the workload execution for both the salted and pre-split table schemas:

    ```sh
    RUN_SALT="$(px-run pherf -- --read-skew --scenario "$HOWTO/skew.xml" \
      --schema "$HOWTO/skew-salted.sql")"
    RUN_SPLIT="$(px-run pherf -- --read-skew --scenario "$HOWTO/skew.xml" \
      --schema "$HOWTO/skew-split.sql")"
    for r in "$RUN_SALT" "$RUN_SPLIT"; do
      awk -F '\t' '$1 == "pherf.health.read_skew" { print $2, $5 }' "$r/report/verdicts.tsv"
    done
    ```

    ```text
    ... INFO  px-run: run ... ends at ... (exit 0, ok)
    ... INFO  px-run: run ... ends at ... (exit 0, ok)
    PASS skew 1.04 (busiest / mean), maximum 1.5: ... has 1563 of 4502 reads on 3 servers
    PASS skew 1.07 (busiest / mean), maximum 1.5: ... has 1607 of 4510 reads on 3 servers
    ```

    The values shown are illustrative. `SALT_BUCKETS` distributes any key across buckets via
    hashing, thereby balancing monotonically increasing keys as well. By contrast, `SPLIT ON`
    balances read traffic only when the explicit split points align with the key distribution
    accessed by the workload.

12. Tighten the threshold to enforce a stricter read-skew limit. This ceiling is controlled by the
    `pherf.properties` key `pherf.verdict.readSkew.max` (defaulting to 1.5, with a minimum of 1).
    Write the stricter setting into a dedicated properties file for the test:

    ```sh
    { cat phoenix-pherf/config/pherf.properties; cat <<'EOF'
    pherf.verdict.readSkew.max=1.2
    EOF
    } >"$HOWTO/skew-strict.properties"
    RUN_STRICT="$(px-run pherf -- --read-skew --scenario "$HOWTO/skew.xml" \
      --schema "$HOWTO/skew-salted.sql" --properties "$HOWTO/skew-strict.properties")"
    ```

    ```text
    ... INFO  px-run: run ... ends at ... (exit 0, ok)
    ```

    An exit status of 1 indicates that the read skew of the salted table exceeded 1.2; inspect its
    verdict row using the command in step 10.

    Because the driver appends `pherf.verdict.serverJmx` last, any value defined in the properties
    file cannot override the active topology servers. Running a test without `--read-skew` leaves
    `serverJmx` empty, causing the check to report `SKIP`.

13. Simulate leading-key clustering where appropriate. Production composite keys often start with a
    low-cardinality column such as a tenant identifier or date, causing writes for a given value
    to land together. Setting the `clusterLimit` attribute on `coGeneration` restricts each write
    block to at most that many distinct values of the leading primary key column. The primary key
    must include a subsequent column that is unique per row, for example:

    ```xml
    <coGeneration seed="12" blockSize="1000" clusterLimit="2"/>
    ```

    on a table defined with `PRIMARY KEY (TENANT, K)`, where `K` is SEQUENTIAL. The validator
    rejects a `clusterLimit` smaller than `blockSize` on a single-column primary key (rule
    `cogen.writer-blocks`). Refer to
    [04-co-generation.md](../../../../phoenix-pherf/docs/howto/04-co-generation.md) for the
    complete rules, offsets, and walk strategies.

14. Inspect the per-RegionServer read counters directly. `px-cluster endpoint` looks up the mapped
    port of each RegionServer info server, which is bound exclusively to `127.0.0.1`. The
    RegionServer table metrics report the read count for each table, while the Server bean exposes
    the hosted region count:

    ```sh
    Q='Hadoop:service=HBase,name=RegionServer,sub'
    for i in 0 1 2; do
      port="$(px-cluster endpoint regionserver "$i" http | cut -f 2)"
      echo "== regionserver-$i 127.0.0.1:$port"
      curl -s "http://127.0.0.1:$port/jmx?qry=$Q=Server" |
        jq -r '.beans[0] | "regionCount \(.regionCount)", "readRequestCount \(.readRequestCount)"'
      curl -s "http://127.0.0.1:$port/jmx?qry=$Q=Tables" | jq -r '.beans[0] | to_entries[] |
        select(.key | endswith("PX_HOWTO.SKEW_metric_readRequestCount")) | "\(.key) \(.value)"'
    done
    ```

    ```text
    == regionserver-0 127.0.0.1:16025
    regionCount 9
    readRequestCount 18342
    Namespace_default_table_PX_HOWTO.SKEW_metric_readRequestCount 1563
    ...
    ```

    The values are illustrative, and the per-table metric line is present only while the table
    exists because each run drops the table during its restore phase. Query the table counts from
    a second shell while a run is active. These are the exact attributes that Pherf aggregates;
    `phoenix-pherf/VERDICT.md`, section "Server reads", documents which system namespaces are
    excluded.

### Measure five RegionServers

15. Expand the cluster to 5 RegionServers. This topology requires a 24 GiB container engine
    because the container memory limits sum to 18944 MiB:

    ```sh
    px-cluster scale regionserver 5
    HOWTO="$HOWTO" sh "$HOWTO/steps.sh" 5
    ```

16. Tabulate the rate sweep results by extracting the attainment and response p99 latency for each
    cluster size and target rate:

    ```sh
    while IFS="$(printf '\t')" read -r n rate code run; do
      att="$(awk -F '\t' '$1 == "pherf.health.attainment" { print $5 }' \
        "$run/report/verdicts.tsv" |
        sed 's/^lowest attainment \([0-9.]*%\).*/\1/')"
      p99="$(awk -F '\t' '$4 == "op@response_howtoCap_agg" && $5 == "pherf.latency.p99_us" {
        print $7 }' "$run/metrics/aggregate.tsv")"
      printf '%s RS %5s ops/s exit %s attainment %s response p99 %s us\n' \
        "$n" "$rate" "$code" "$att" "$p99"
    done <"$HOWTO/capacity.tsv"
    ```

    ```text
    1 RS   100 ops/s exit 0 attainment 100.0% response p99 4095 us
    1 RS   200 ops/s exit 0 attainment 100.0% response p99 5119 us
    1 RS   400 ops/s exit 0 attainment 99.9% response p99 9215 us
    1 RS   800 ops/s exit 0 attainment 71.2% response p99 1015807 us
    ...
    ```

    The figures shown are illustrative. For each cluster size, the saturation knee is the highest
    step achieving at least 90% attainment with a response p99 within your latency objective, and
    `X(N)` is the offered rate multiplied by the attainment at that step. Above the knee, response
    p99 escalates by orders of magnitude while service p99 changes very little because the request
    queue grows faster than worker threads can drain it. Under the `DROP` policy, the response p99
    of an overloaded step settles near the 1000 ms lag limit plus the service time. Whenever the
    highest tested step at a given size keeps pace, record `X(N)` as "at least" that rate because
    the value is right-censored rather than a true capacity limit. Validation-class runs exit with
    status 0 even when a timing check fails because timing verdicts are `ADVISORY`.

17. Compute the scaling efficiency from the sustainable rates `X(N)` determined in step 16. For
    example, given `X(1) = 400`, `X(3) = 1000`, and `X(5) = 1400`:

    ```text
    E(3) = 1000 / (3 x 400) = 0.83    throughput per RegionServer 333 ops/s
    E(5) = 1400 / (5 x 400) = 0.70    throughput per RegionServer 280 ops/s
    ```

    These numbers illustrate the arithmetic rather than actual benchmark results. Before
    interpreting `E(N)` as an intrinsic property of Phoenix, inspect `pherf.health.saturation` and
    `pherf.health.client_pool` for the runs at each knee: if they implicate the client, the client
    container capped `X(N)` and prevented a larger cluster from delivering higher throughput. A
    right-censored `X(N)` yields only a bound on `E(N)` rather than an exact value.

### Use the measurement-shaped sizing on a 24 GiB engine

The default measurement class sizes each role for a container engine of approximately 48 GiB: with
3 RegionServers, its memory limits sum to 41984 MiB with YARN enabled and 31744 MiB without YARN.
Consequently, the preflight check rejects the default measurement profile on a 24 GiB engine.
Applying `override-24gib.env` scales the memory allocations to fit a measurement-shaped deployment
within 24 GiB and tags the run with `contamination sizing`, rendering its timing verdicts
`ADVISORY` while its count verdicts remain authoritative.

18. Shut down the validation cluster first. The harness allows only one active cluster per host,
    and any bring-up attempt while another cluster is present is rejected prior to preflight:

    ```sh
    px-cluster down
    ```

19. Verify that preflight rejects the default measurement sizing profile on a 24 GiB engine,
    where no containers are started:

    ```sh
    px-cluster --class measurement up
    ```

    ```text
    ... INFO  compose.gen.sh: rendered 18 services for cluster default, class measurement: 41984 MiB
    of container limits, ...
    ...
    ... ERROR preflight.sh: the deployment .../deploy/default does not fit the engine. The limits
    41984 MiB (41.00 GiB) exceed the budget by 22078 MiB (21.56 GiB); the budget 19905 MiB
    (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the headroom 4096 MiB (4.00 GiB). ...
    ...
    ... ERROR px-cluster: up ends (exit 3, anomaly)
    ```

    The figures shown above come from the reference host.

20. Start a 1- or 3-RegionServer cluster with the 24 GiB sizing override. A 3-RegionServer topology
    fits within the budget only when YARN is disabled (consuming 19456 MiB out of 19905):

    ```sh
    px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env \
      -e PX_RS_COUNT=3 -e PX_YARN=0 up
    ```

    Pass `-e PX_RS_COUNT=1` to deploy 1 RegionServer (requiring 12288 MiB without YARN). The
    measurement class additionally requires at least 8 processors and 20480 MiB of free disk space
    under `$PX_OUT`. Because the invocations in the step 4 script inherit the measurement class
    from the cluster record, the script executes without modification; each run waits up to 300 s
    for a quiet host and aborts with exit code 4 if background activity persists. Test one cluster
    size at a time and run `px-cluster down` before provisioning the next topology.

21. Deploy 5 RegionServers by applying the commented 5-server configuration profile provided in
    `override-24gib.env`. Write those eight configuration lines to a dedicated file for this HOWTO
    and pass it on the command line before the override file; because the configuration loader
    retains the first value assigned to each variable, the earlier file takes precedence:

    ```sh
    cat >"$HOWTO/rs5.env" <<'EOF'
    PX_RS_COUNT=5
    PX_YARN=0
    PX_RS_MEM_MIB=2432
    PX_RS_HEAP_MIB=1664
    PX_RS_DIRECT_MIB=256
    PX_CLIENT_MEM_MIB=1536
    PX_CLIENT_HEAP_MIB=1024
    PX_CLIENT_DIRECT_MIB=128
    EOF
    px-cluster --class measurement --config "$HOWTO/rs5.env" \
      --config dev/harness/docker/env/override-24gib.env up
    ```

    The resulting memory limits sum to 19328 MiB (18.88 GiB)—5 x 2432 for the RegionServers, 1536
    for the client, 512 for ZooKeeper, 1024 for the NameNode, 2 x 1024 for the DataNodes, 1024 for
    the master, 512 for tooling, and 512 for setup—leaving 577 MiB below the 19905 MiB budget. A4
    verified that this topology starts cleanly and maintains readiness.

## Read the result

* **Capacity.** The primary outputs are the sustainable rate `X(N)` at each cluster size, the
  per-RegionServer throughput `X(N) / N`, and the scaling efficiency `E(N)`. On a single host,
  these metrics characterize the software rather than physical hardware scale-out, as a decline in
  `E(N)` can stem from contention over shared processors.
* **Bottleneck.** Inspect the saturation and client pool verdict rows at the knee of each cluster
  size. A client bottleneck restricts every cluster topology to the same maximum throughput rate.
* **Hotspot.** A `read_skew FAIL` verdict whose skew ratio equals the server count indicates that a
  single region or key range absorbed all read operations. A skew ratio between 1.5 and the server
  count reflects a partial hotspot; check the per-table read counters in step 14 to pinpoint the
  overloaded server.
* **Severity.** Timing verdicts are `ADVISORY` in the validation class and under sizing
  contamination, whereas the read skew check belongs to the `share` class and remains
  authoritative.

## Reproduce it

* **Inputs.** The scenario and helper files `cap.xml`, `cap.sql`, `skew.xml`, the skew SQL
  files, `steps.sh`, and `rs5.env` are stored under `$PX_OUT/howto/h11/`. The fixed seeds `11` and
  `12` determine the generated keys, read traversal sequences, and arrival timestamps.
* **Version set and image.** Each run bundle records component versions and container images in
  `manifest.json` and `cluster/images.tsv`, while `cluster/topology.env` records the RegionServer
  count and any sizing overrides (`PX_SIZING_OVERRIDES`) applied to the deployment.
* **Bundles.** The `capacity.tsv` file lists every bundle produced during the rate sweeps. Archive
  them together with the read-skew bundles:

  ```sh
  tar -C "$PX_OUT/runs" -czf "$HOWTO/bundles.tgz" \
    $(cut -f 4 "$HOWTO/capacity.tsv" | sed 's|.*/||') \
    "${RUN_ONE##*/}" "${RUN_SALT##*/}" "${RUN_SPLIT##*/}" "${RUN_STRICT##*/}"
  ```

## Clean up

Shut down the active cluster deployment:

```sh
px-cluster down
```

```text
... INFO  px-cluster: cluster default is down
```

## See also

* [06-saturation-knee.md](06-saturation-knee.md): explains how to locate the saturation knee of a
  single cluster using rate ramps and steps, and how to distinguish client from cluster
  bottlenecks.
* [01-measurement-protocol.md](01-measurement-protocol.md): defines the run classes, sizing
  contamination rules, and the 24 GiB memory override profile.
* [08-ingest-plans-and-index-cost.md](08-ingest-plans-and-index-cost.md): covers table salting for
  ingestion workloads.
* [VERDICT.md](../../../../phoenix-pherf/VERDICT.md): specifies the read skew health check and its
  operational bounds.
* [../backends.md](../backends.md): documents host port mappings and live deployment scaling.
* [../../README.md](../../README.md): describes the preflight checks and host memory budget rules.

[d04]: ../../../../phoenix-pherf-distributed/docs/howto/04-load-generator-scale-out.md
[h14]: 14-fleet-through-the-harness.md
