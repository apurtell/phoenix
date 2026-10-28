<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Vector harness on a local Docker cluster

This directory runs the vector harness against a complete cluster on one Mac.
The cluster has ZooKeeper, HDFS, YARN, an HBase master and three region servers, all with
Phoenix built from this tree. Every daemon runs in its own container on the host network.

The host scripts are in `bin`. They run on the macOS `/bin/bash` 3.2.

| Script | Purpose |
| --- | --- |
| `build.sh` | Builds Phoenix and the harness, then builds the image |
| `bin/vh-cluster` | Starts, stops, resets, checks and disrupts the cluster |
| `bin/vh-harness` | Runs one harness phase in a new harness container and observes the cluster |
| `bin/vh-suite` | Runs a suite file step by step, with resume |
| `bin/vh-tool` | Runs IndexFsckTool, IndexTool, the HBase shell or a Hadoop command |
| `bin/vh-profile` | Runs async-profiler in a container |
| `bin/vh-jfr` | Controls Java Flight Recorder in a container |
| `bin/vh-trace-count` | Counts the calls of each traced method in an async-profiler recording |
| `bin/vh-collect` | Writes a diagnostic bundle |
| `suites/check-run.sh` | Screens one harness run for gross deficits |

Each script prints its options with `--help`.

## Prerequisites

- Docker Desktop with at least 48 GB of memory, 16 CPUs and 150 GB of disk for the
  performance suite. The smoke, scale and functional suites need 40 GB of disk.
- Host networking in Docker Desktop: Settings, Resources, Network, "Enable host networking".
  The daemons bind fixed ports on localhost, so no other process can use these ports.
- `jq` on the host. The run checks need it.
- JDK 21 for the build. Set `VH_JDK21` if it is not at the Homebrew location.
- A private Maven repository for the build, so that other builds do not change it.
  Set `VH_M2` to its path. You can make one from the shared repository with
  `ditto --clone ~/.m2/repository <path>`.
- The Docker CLI on the `PATH`, for example `export PATH=$HOME/.docker/bin:$PATH`.

Put local settings in `env/local.env`, one `NAME=value` per line. The values in
`env/defaults.env` apply to all settings that `env/local.env` does not set.

## Build and start

```
./build.sh
bin/vh-cluster up
bin/vh-cluster verify
bin/vh-cluster status
```

`build.sh` runs the host preflight checks, builds Phoenix with Maven, downloads the Hadoop,
HBase and async-profiler releases into a cache, and builds the image. `--skip-maven` reuses the
current Phoenix artifacts.

`vh-cluster up` returns when HBase and Phoenix serve requests. `vh-cluster verify` runs the
host checks, then reads the web endpoints of the daemons to check the configuration that the
harness expects. `vh-cluster verify --crash-check` also kills one region server and waits for
it to rejoin. `vh-cluster verify --guideposts SCHEMA` also checks the statistics rows of the
tables in `SCHEMA`. It starts one HBase shell, so use it after a build.

### Statistics mode

`VH_STATISTICS` in `env/defaults.env` selects the statistics mode of the cluster. The value is
`off`, the default and the production mode, or `on`. The region servers, the master and the
harness clients get `phoenix.stats.collection.enabled=false` or `true` from it. `vh-harness`
passes the mode to each phase as `table.statistics`. A profile or an override with the other
mode is a usage error. The phases also compare the mode with the live configuration of the
region servers. See the Statistics Modes section of the harness README for the details.

To select `on`, set `VH_STATISTICS=on` in `env/local.env`, or export it before `bin/vh-suite`
or `bin/vh-cluster up`. A suite step can also deploy it: `cluster VH_STATISTICS=on up`. Later
`harness` steps must then get the same value, for example `harness VH_STATISTICS=on load ...`.
`vh-cluster up` starts the daemons again when the mode changes. Then load the tables again,
because the load fixes the statistics properties of the tables.

`vh-verify` prints the mode. It compares the site files of its own container and the live value
of the master and of each region server with the mode. With `--guideposts SCHEMA` and statistics
`on`, each IVF index table (`*_VIDX`) in `SCHEMA` must have real guideposts, not only empty
markers. An HNSW index shares the HBase table of its data table, so `vh-verify` only reports the
rows of the data tables. With statistics `off`, a statistics row for the schema gives a warning.

`vh-cluster down` removes the containers and keeps the data.
`vh-cluster reset --yes` also removes the data volumes. It moves the table records of the
harness aside, because those records describe tables that no longer exist.

## Datasets

The synthetic profiles need no files. The SIFT and GloVe profiles read files from `VH_DATA`
(default `~/vh-data`), which the containers see as `/data`.

- `~/vh-data/ann-benchmarks/sift-128-euclidean.hdf5`: SIFT1M from ann-benchmarks. It holds the
  texmex base vectors, queries and ground truth in the same order.
- `~/vh-data/ann-benchmarks/glove-100-angular.hdf5`: GloVe-100 from ann-benchmarks.

`scripts/fetch-data.sh` downloads the files and verifies them against pinned SHA-256 digests.
It does not download a file again when the file is already in place and its digest matches.
`--offline` only verifies the files. `bin/vh-harness` stops before it starts a container when
the corpus of a profile is not in `VH_DATA`.

```
scripts/fetch-data.sh            # all corpora
scripts/fetch-data.sh sift       # SIFT1M only
scripts/fetch-data.sh --offline  # verify only
```

`fetch-data.sh` merges the digests into `SHA256SUMS`. It replaces only the lines of the files
that it fetched or verified, and it keeps the other lines in their order.

A smaller `corpus.rows` uses a prefix of the train rows. A prefix has no published ground truth,
so the harness computes the truth. A run that writes new vectors (inserts or moves) holds out a
tail of the train rows and loads at most the rows before the tail. Each new vector is a
distinct, unseen tail row, and the harness computes the truth. The steady window and the
sequence number of the operation select the row, not thread timing, so paired runs (IVF and
HNSW, SIMD on and off) write the same rows in the same operations. `corpus.holdout.rows`
sets the tail (default `auto`). With a prefix, the rows after the prefix supply the new vectors.
The harness stops at configuration time when the tail is smaller than the planned volume of new
vectors. With `auto`, a read-only run loads all rows and checks the published truth. The SIFT
profiles set `corpus.holdout.rows=100000`, so P1 and full-length runs load the same 900,000 rows.
A read-only SIFT run must also set `corpus.holdout.rows=auto`. The texmex files remain an option:
`-Dcorpus.source=texmex -Dcorpus.path=/data/texmex/sift`. The fingerprint includes the source,
so runs from the two sources do not compare and do not share the computed truth.

## One harness phase

```
bin/vh-harness load smoke --variant hnsw
bin/vh-harness build-index smoke --variant hnsw
bin/vh-harness truth smoke --variant hnsw
bin/vh-harness run smoke --variant hnsw --run-id my-first-run
bin/vh-harness report - --run-id my-first-run
```

The profile is a name from `phoenix-vector-harness/profiles`, or a file in `$VH_WORK/profiles`
given as `/work/profiles/NAME.properties`. The wrapper always supplies the JDBC URL of the local
cluster, the output directory, the HTTP metrics mode and the schema of the suite.

Change settings with `--overrides FILE`, one `-Dkey=value` per line, or with `-Dkey=value`
after `--`. A bare file name refers to `suites/overrides`. The phases of one table must get
the same overrides.

The wrapper sets the harness heap from the profile: 12g for `rag-1m-768`, 10g for
`streaming-hnsw` and `drift-ivf`, 8g for the SIFT and GloVe profiles, and `VH_HARNESS_HEAP`
for the others. `--heap` sets it for one phase. The container limit is the heap, the direct
memory and 1 GiB more, unless you set `VH_HARNESS_MEM`.

Timed actions of the wrapper add disruptions that the harness itself cannot make:

```
bin/vh-harness run smoke --variant hnsw --at 6m kill rs2
```

The time counts from the first steady stage. `kill` sends SIGKILL to the region server JVM,
and the restart policy brings the container back. `restart` stops and starts the container.
For the master and the region servers, `bin/vh-cluster restart` is a rolling restart, as in
production. The master restarts first. Then each region server in turn moves its regions to the
other servers with the HBase region mover, stops cleanly, and starts. When the master has the
first load report of the new instance, the region mover moves the regions back. `VH_READY_TIMEOUT`
(default 900 seconds) limits this wait.
The balancer is off during these steps. `VH_RS_MOVE_TIMEOUT` (default 600 seconds) limits each
drain and each load, and `VH_RS_MOVE_THREADS` (default 1) sets the parallel moves.
The logs of the region mover are in `$VH_WORK/logs/region-mover`, and the region lists are in
`$VH_WORK/out/region-mover`. A region server that is stopped only starts, without a drain,
and each wait for live region servers counts only the region servers that run.
`restart --full` stops all members first and then starts them.
A `kill` is a hard crash, so the crash tests check the recovery after a crash.

### Output

All output is below `$VH_WORK/out/<suite>` (default `work/out/adhoc`):

| Path | Content |
| --- | --- |
| `runs/<run-id>/` | Manifest, CSV series, fsck reports and the report of one run |
| `tables/<suffix>/` | Load, build and fsck records of one table |
| `docker/<id>/` | Records of the wrapper: `wrapper.env`, `harness.log`, `docker-stats.csv`, `actions.csv`, region server log warnings, container state before and after, the harness GC log |
| `checks/`, `tools/` | Output of the check and tool steps of a suite |
| `suite-<session>.log` | Log of one suite session |

The daemon logs and GC logs are in `$VH_WORK/logs/<instance>`.

The wrapper exits with the harness exit code. It exits with 3 when the harness passed but a
container restarted without a reason in the schedule, or the kernel killed a container for
memory. After a failure it writes a `vh-collect` bundle. `--collect always|never` changes that.

## Suites

```
bin/vh-suite smoke
bin/vh-suite functional
bin/vh-suite perf --only p1
```

| Suite | Schema | Time | Content |
| --- | --- | --- | --- |
| `smoke` | `VH_SMOKE` | about 1 hour | exact, IVF and HNSW on the small synthetic profile |
| `scale` | `VH_SCALE` | about 2 hours | exact, IVF and HNSW on the small synthetic profile with 100 000 rows in each region, near the break-even point of the HNSW filter probe |
| `functional` | `VH_FUNC` | about 6 hours | disruptions, crash, streaming, drift, multi-tenant RAG, reopen, index checker, MapReduce, index damage and repair |
| `perf` | `VH_PERF` | more than 1 day | SIFT1M, SIMD A/B, GloVe, streaming and RAG at full scale |

Each suite starts with `cluster reset --yes`, so each suite starts from an empty cluster.
Skip the reset with `--from` when the cluster already has the tables you need.

The runner prints a session id and the statistics mode at the start. A resume of a session
with another `VH_STATISTICS` value is a usage error, so one session holds runs of one mode.
Run ids in the suite file take the form `@name`,
and the runner replaces them with `<session>-name`. When a step fails, the runner stops and
prints the command that resumes the session at that step:

```
bin/vh-suite functional --session 20261101t090000 --from f4-ivf-run
```

A selector selects the step with the same id and all steps whose ids start with the selector
and a dash. `--only f9-hnsw` runs the HNSW damage steps. `--to` stops after a step.
`--list` prints the steps and the steps that each step needs, and `--dry-run` prints the
commands. `--keep-going` runs the remaining steps after a failure.

A step needs the steps that make its input. When a needed step failed, the runner does not run
the step, records it as `NOT_RUN` and goes on to the next step. Thus the failures in the summary
are the root causes. The summary counts `PASS`, `FAIL` and `NOT_RUN` separately. A `NOT_RUN`
step does not stop the pass, but the runner exits with 1. The runner finds the result of a
needed step in this invocation or in the session log. Thus a resumed session with `--from` or
`--only` sees the results of earlier invocations. A needed step without a result in the session
does not stop the step. Run the failed step again to clear its `NOT_RUN` steps.

A failed `run` step is different, because the run still writes all its outputs. It stops only
the steps of the `load`, `build`, `truth`, `run` and `corrupt` phases that need it. The checks,
reports and other readers of the outputs still run, because they explain the failure.

A run step that runs again in the same session moves the files of the earlier attempt to
`out/<suite>/replaced`, so that the checks see only the new files.

### Suite file format

A suite file has one step on each line: an id, an optional `needs=STEP[,STEP]...` word, an
action and its arguments. Words are separated by whitespace. The runner does not interpret
quotes or shell syntax.

The chain of a step is its id without the last dash and the phase after it. For example, the
chain of `f6-async-run` is `f6-async`, and its phase is `run`. A step needs these earlier steps
of its chain:

| Phase | Needs |
| --- | --- |
| `load` | nothing |
| `build`, `truth` | `load` |
| `corrupt` | `load`, `build` |
| `run` | `load`, `build`, `truth` |
| any other phase | `load`, `build`, `truth`, `run`, `corrupt` |

The `truth` phase reads only the table, so it does not need the index build. `needs=` adds
steps outside the chain, for example a check of a table that an earlier group built. Each step in
`needs=` must come earlier in the file.

| Action | Meaning |
| --- | --- |
| `cluster [K=V]... ARGS` | `vh-cluster ARGS` with the given environment |
| `harness [K=V]... PHASE PROFILE ARGS` | `vh-harness PHASE PROFILE --suite <suite> ARGS` |
| `check RUN CHECK...` | `check-run.sh` on the run |
| `fsck EXPECT ARGS` | `vh-tool indexfsck ARGS`; the exit code must be in EXPECT, for example `0`, `0,1` or `any` |
| `corrupt ivf\|hnsw SCHEMA TABLE` | Removes one data row (IVF) or one embedding (HNSW) with a raw HBase delete |
| `tool ARGS` | `vh-tool ARGS` |
| `tool-grep REGEX ARGS` | `vh-tool ARGS`; the output must match REGEX |
| `sleep DURATION` | Waits, for example `30s` |
| `note TEXT` | Writes TEXT to the log |

### Index damage test

The `f9` steps of the functional suite load a separate table, check that it is clean, damage it,
and then expect `verify` to find the damage, `repair -confirm` to fix it, and `verify` and
`fsck` to pass again.

The damage is a raw HBase delete under the index, so Phoenix does not update the index.
The delete marker has the timestamp of the cell that it removes. For IVF the delete removes a
whole data row, and the index row of that row becomes an orphan. For HNSW the delete removes
the embedding of one row, and the segment entry of that row becomes an orphan.

The test does not remove an index row. With max lookback off, which is the default, the
checker reports a missing index row as information and not as an error.

## Run checks

```
suites/check-run.sh work/out/smoke/runs/<run-id> wrapper errors recall-hnsw-ef256 fsck
```

Each check prints `PASS`, `WARN` or `FAIL` and a short message. The script exits with 1 when a
check fails. `--help` lists the checks. The limits are in `suites/thresholds.properties`.
They find gross deficits only, and they are not performance targets. Calibrate them on the
first full-scale baseline, and then change them only on purpose. `-t FILE` uses a different
file.

The `errors` check counts the query errors and the failed steady mutations. A failed commit
loses a planned write, so one failed mutation fails the run by default. The `freshness` check
reads the freshness probes. A probe commits a row and then queries the index for it at once. The
check fails when a probe fails, when the freshness is below the limit, or when no validity probe
gave an answer, which the manifest records as a null freshness. It also fails when the run has
no steady stage, or when no probe of a steady stage gave an answer. `freshness:validity` reads only
the validity probes, which run before the workload. The suites use this form for runs with
disruptions, because a disruption can fail a steady probe.

The `statistics` check reads the statistics mode of the run. The mode must match the mode that
the wrapper recorded, and the harness must have read the switch of the region servers. In the
`off` mode, a statistics row in the build record or at the end of the run gives a warning. In the
`on` mode, each IVF index must have real guideposts after the build and after each update during
the run, and each update must succeed. An HNSW index has no index table, so it has no guidepost
check. A run from before the modes gives `SKIP`.

The recall checks use the `unfiltered` family at k=10. `recall:REGEX:MIN` gates any sweep
point, for example `recall:^ef=128$:0.9`.

`host.noise` in the thresholds file tells how much to trust time measures. The value is `noisy`
or `quiet`, and a file without the key gets `noisy`. On a noisy host, small latency and QPS
values change from run to run. Thus, with `noisy`, a check that uses only latency or QPS prints
`ADVISORY` in place of `FAIL` or `WARN`. The line shows the value and the limit, but it does not
fail the run. These checks are `tail`, the QPS ratio of `qps-vs-exact`, the flat latency curve of
`restriction`, the SIMD gain of `simd-ab` and the target rate of `saturation`. The other results
of these checks do not change. For example, `qps-vs-exact` still fails when no index point
reaches the recall level. With `quiet`, all checks work and print as before. The summary line
adds the advisory and skipped counts when they are not zero.

`rows-vs-exact:RUN` and `rpcs-vs-exact:RUN` compare the work per query of the index with the
exact run `RUN`. They read `rows_scanned` and `rpcs_per_query` from `frontier.csv`. These values
are counts, so the checks fail on any host. For each layout, the index point with the least work
at the recall `index.work_vs_exact.recall` must stay at or below
`index.rows_vs_exact.max_ratio` or `index.rpcs_vs_exact.max_ratio` of the exact work. A run
without these columns, such as an older run, gives `SKIP`. A count of zero also gives `SKIP`,
because it shows that the run did not collect the metric. A `SKIP` line does not fail the run.
A run without `frontier.csv` fails. The scale and perf suites run these checks in their `-work`
steps, after each index run that also compares its QPS with exact search.

## Profiling

```
bin/vh-profile rs1 record -e itimer -d 60 -f html
bin/vh-profile harness record -e alloc -d 120
bin/vh-jfr rs2 start -d 10m
bin/vh-jfr rs2 dump
```

The output goes to `$VH_WORK/prof`. The `cpu` event needs perf events, which the Docker VM does
not allow by default, so use `itimer`. The `itimer`, `wall` and `cpu` events sample every
`VH_ASPROF_INTERVAL` (default `7919us`). `-i` sets another interval for one call. An interval
or an event after `--` (`-i`, `--interval`, `--wall`, `-e`, `--event` or `--all`) turns the
default off, so asprof gets the same arguments as without the default. Use a prime interval. At
20 queries per second, the throttle starts a query every 50 ms. A round interval, such as 10 ms
or 50 ms, can stay in phase with those slots and sample the same part of each query. An empty
`VH_ASPROF_INTERVAL` uses the asprof default. The `alloc` and `lock` events keep their asprof
defaults. `vh-harness --jvm-profile itimer:60` profiles the region servers and the harness at the
first steady stage of a run. With `VH_JFR=1` in
`env/local.env`, every daemon keeps a continuous recording, and `vh-jfr <svc> dump` writes it.

GC logs are in `$VH_WORK/logs/<instance>` for the daemons, and in the `docker/<id>` directory of
the phase for the harness.

`vh-collect --since 30m` writes the console logs, thread dumps, and the HDFS, YARN and HBase
reports to `$VH_WORK/collect/<timestamp>`.

### Method call counts

On a noisy host, a call count is better evidence than a small time. The `--trace` option of
async-profiler records one event for each call of a method. `vh-trace-count` counts these events:

```
bin/vh-profile rs1 record -d 60 -f jfr -- --trace org.apache.hadoop.hbase.regionserver.StoreScanner.next
bin/vh-profile rs1 metrics
bin/vh-trace-count -q 12000 work/logs/rs1/prof/rs1-itimer-<timestamp>.jfr
```

The output is a TSV with the columns `method`, `count` and `per_query`, highest count first.
`-q` gives the number of queries in the trace window, for example the sum of the `queries`
column of `throughput.csv` over the same time. Without `-q`, `per_query` is `-`. The counts of
several files add up, so one file for each region server gives the count for the cluster.
A region server also does work for writes, recall samples, flushes and compactions. That work is
in the count too, so `per_query` is an upper bound of the work for one query.
Repeat `--trace` for more methods. A target can end with `*`, such as `CLASS.*`. The overloads of
one method share one row, so trace one overload with its signature, such as `CLASS.METHOD(I)J`.

The helper runs in a new tools container, so it needs the image but no running cluster. It
counts the events in the stacks of the async-profiler converter and checks the total against the
event count of `jfr summary`. It never uses the `--total` option of the converter, because that
option adds the call durations. A target with a latency threshold, such as `CLASS.METHOD:5ms`,
records only the calls at or above it, and the helper writes a warning for it. `vh-profile`
always samples a CPU event too, so async-profiler records every call of a traced method.
async-profiler skips an event when too many threads record at the same time, and the recording
does not keep the number of skipped events. Run `vh-profile <svc> metrics` after the recording and
before the next one. A `samples_skipped_total` of 0 shows that the counts are exact.

## Ports

`image/lib/ports.sh table` prints every port of every instance. `image/lib/ports.sh urls`
prints the web UI addresses. `vh-cluster status` also prints them. The main ones:

| Instance | Web UI |
| --- | --- |
| NameNode | http://localhost:9870/ |
| ResourceManager | http://localhost:8088/ |
| JobHistory | http://localhost:19888/ |
| HBase master | http://localhost:16010/ |
| Region servers | http://localhost:16030/, http://localhost:16130/, http://localhost:16230/ |

ZooKeeper listens on port 2181. The JDBC URL is `jdbc:phoenix:localhost:2181:/hbase`.

## Troubleshooting

- **Port in use.** The preflight check of `vh-cluster up` prints a warning that names each
  host process on a cluster port. Stop that process, or stop another cluster on the same host.
- **MapReduce task fails with `NoSuchMethodError`.** A Hadoop jar and a Phoenix dependency
  clash on the task classpath. Set `mapreduce.job.classloader` to `true` in the client
  `mapred-site.xml` template in `image/conf`, then rebuild the image.
- **A YARN job fails.** Find the application id with
  `bin/vh-tool yarn application -list -appStates ALL`, then read its logs with
  `bin/vh-tool yarn logs -applicationId <id>`.
- **Exit code 3 from the wrapper.** A container restarted without a reason, or the kernel
  killed it for memory. `inspect-after.txt` and `log-<svc>.txt` in the `docker/<id>`
  directory show which one. Increase `VH_MEM_RS`, or decrease `VH_HEAP_RS` and
  `VH_HNSW_OFFHEAP_MAX_BYTES`, so that the heap, the direct memory and the off-heap index
  fit in the container limit.
- **The harness container runs out of memory.** Give a larger `--heap`. The full-scale RAG
  profile needs about 12g.
- **A disruption run takes longer than its schedule.** After each disruption, the harness
  waits up to 600 seconds for the cluster to recover. A slow recovery, usually after a
  region server restart, delays all later disruptions. A `restart` disruption also moves all
  regions of the server away and back. Each wait of the harness stops after 5 minutes, and
  the drain, the restart and the load each have up to three waits. Thus one of these steps can
  take up to about 15 minutes. The disruption record in the manifest gives the time of each step.
  A disruption that is in flight at the end of a steady window extends the queries of the window
  until its recovery wait ends, but no mutation that is due after the planned end runs. A
  disruption that cannot start before the end of the window is skipped. The wrapper does not
  expect a restart that the manifest gives in `skipped_disruptions`.
- **A rolling restart fails.** The error names the step: the drain, the stop, the start or the
  load. The region mover log of the step is in `$VH_WORK/logs/region-mover`. The balancer is
  on again after the failure, if it was on before.
- **A phase does not find its table after a reset.** A reset removes all HBase data. Run
  `load`, `build-index` and `truth` again before `run`.
