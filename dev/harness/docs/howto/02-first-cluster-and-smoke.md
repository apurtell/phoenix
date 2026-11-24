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

# How to bring up a cluster and smoke-test a build

## The question

Can this Phoenix build deploy onto a real multi-role cluster and execute SQL queries accurately? A
smoke test resolves that question before any performance evaluation begins. It acts as a functional
correctness gate rather than a latency benchmark, because timing measurements gathered from an
unhealthy cluster produce meaningless results. This HOWTO compiles Phoenix and packages the harness
image from your working tree, launches a cluster with one RegionServer, executes the smoke suite,
and then provisions the larger topologies required by later HOWTOs.

## Before you start

**Audience.** This guide is written for Phoenix developers validating a code change and prospective
users exploring the harness for the first time.

**Prerequisites.**

* Provide a container engine accessible via `docker`, such as Docker Desktop, configured with at
  least 16 GiB of memory and 2 processors. The 3-RegionServer and measurement-class exercises
  require approximately 24 GiB of memory and 8 processors.
* Install JDK 17, which the build selects automatically from the platform JDK registry. Set
  `PX_BUILD_JAVA_HOME` to the JDK home directory if the build cannot locate it automatically.
* Provide Maven along with outbound network access during the initial build to reach Maven Central,
  the Apache mirrors (for HBase and Hadoop), and GitHub (for async-profiler and jHiccup). Subsequent
  builds reuse the verified distribution copies under `$PX_OUT/dist`.
* Reserve approximately 20 GiB of free disk space for the container image, the downloaded
  distributions, the local Maven repository, and `$PX_OUT`.
* Install `jq` so the walkthrough steps can parse JSON output; the harness itself does not need it.

| Cost | Value |
|---|---|
| Wall time, first time | roughly 45 min (self-test 24 min, build 8 min, image 90 s, smoke 3 min) |
| Wall time, later | roughly 5 min per cluster shape |
| Docker memory | 8704 MiB (1 RS), 13824 MiB (3 RS), and 19200 MiB (measurement, 24 GiB override) |
| Disk | roughly 5 GB for the image, roughly 1.3 GB for distributions, plus the Maven repository |
| Files written | created under `$PX_OUT`: `build dist images deploy state monitor runs sessions` |

These baseline figures were collected on an Apple Silicon host running a 24 GiB Docker Desktop
engine with 10 processors. Timings on your host will vary.

Initialize every shell session for this guide with the following three environment setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h02"; mkdir -p "$HOWTO"
```

The `PX_OUT` variable must specify an absolute path located outside the repository working tree.
Every byte that the harness writes to disk is stored beneath that root directory.

## Method

**Hypothesis.** The compiled build deploys cleanly: every service instance reaches the ready state,
and the smoke suite passes with the exact row counts defined in
`dev/harness/suites/sql/smoke-schema.sql`.

**Controls.** The procedure holds constant the validation run class, the default component version
set (HBase 2.6.7, Hadoop 3.4.3, profile 2.6, and JDK 17 across every role), and the container image
that `px-cluster build` produces from your working tree.

**Noise.** Environmental variance does not affect the outcome because the smoke suite gates strictly
on row counts and functional assertions, which are authoritative on any host, while its timing
metrics are recorded as `ADVISORY`.

**What to measure.** The procedure records the readiness status of every deployed instance, the
verdict of each test in the suite, and the exit code returned by each command.

**What invalidates the result.** The run is invalidated by an image that does not contain the JARs
built from your working tree (verify the `tree` row in `$PX_OUT/build/phoenix/build.tsv`), an
unplanned container restart or exit (which causes `px-run` to exit 3), or an `ERROR` line in a
daemon log (which causes the `cluster.log` check to fail).

## Steps

### Prepare the host

1. Inspect the total memory capacity and processor count reported by the container engine:

   ```sh
   docker info --format '{{.MemTotal}} {{.NCPU}}'
   ```

   ```text
   25167863808 10
   ```

   The preflight check reads these same figures and computes the cluster memory budget as total
   engine memory minus 4096 MiB.

2. Run the harness self-test suite, which executes without a container runtime, network access,
   or Maven:

   ```sh
   dev/harness/test/run-tests.sh
   ```

   ```text
   ok 1 - ...
   ...
   # 1243 assertions, all passed
   ```

   The total assertion count grows as the working tree evolves. An exit code of 1 indicates that an
   assertion failed, with the trailing output lines identifying the failure. The self-test required
   24 min on the reference host.

3. Confirm that every harness command responds to `--help` with exit status 0:

   ```sh
   for c in dev/harness/bin/px-*; do "$c" --help >/dev/null || echo "${c##*/} $?"; done
   ```

   The loop produces no output when every command succeeds.

   Invoking `px-cluster SUBCOMMAND --help` prints the flags accepted by a specific subcommand.
   Every tool rejects an unrecognized option by logging `unknown option X` and terminating with
   `(exit 2, usage)`.

### Build the image

4. Compile the Phoenix JARs from your local working tree and assemble the harness container image:

   ```sh
   px-cluster build
   ```

   ```text
   ... INFO  build-phoenix.sh: building Phoenix ... with JDK 17 (...), log .../maven.log
   ...
   ... INFO  build-phoenix.sh: build record .../phoenix-harness/build/phoenix/build.tsv
   ...
   ```

   This step compiles the working-tree Phoenix JARs and jHiccup, downloads the HBase and Hadoop
   archives once, validates their SHA-512 checksums against the declared pins, and builds the
   `PX_IMAGE_TAG` image in the local engine. Use `px-cluster build --tree DIR` to build another
   source tree. The harness never pulls or pushes a prebuilt harness image. Running
   `px-cluster -e PX_PHERF=1 build` additionally compiles `phoenix-pherf-distributed` and builds
   the pherf image for the Distributed Pherf tier; see [H14](14-fleet-through-the-harness.md).

5. Verify that the generated build record reflects your working tree and target build profile:

   ```sh
   grep -E '^(tree|phoenix_version|hbase_profile|jdk)' "$PX_OUT/build/phoenix/build.tsv"
   ```

   ```text
   tree	...
   phoenix_version	5.4.0-SNAPSHOT
   hbase_profile	2.6
   jdk	17
   ```

6. Inspect the derived container image tag and the configuration origin of its value:

   ```sh
   px-cluster config PX_IMAGE_TAG
   ```

   ```text
   PX_IMAGE_TAG	px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6	derived
   ```

### Bring up one RegionServer

7. Start the validation-class cluster:

   ```sh
   px-cluster --class validation up
   ```

   ```text
   ... rendered 8 services for cluster default, class validation: 8704 MiB of container limits,
   0 MiB of them on demand, into .../phoenix-harness/deploy/default
   ... INFO  px-cluster: rendered the validation deployment of cluster default into
   .../phoenix-harness/deploy/default
   memory:regionserver	ok	1 x 2560 MiB = 2560 MiB
   ...
   memory_fit	ok	the limits 8704 MiB (8.50 GiB) fit the budget with 11201 MiB (10.94 GiB) to
   spare; the budget 19905 MiB (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the headroom
   4096 MiB (4.00 GiB)
   ...
   role         index  ready  elapsed_ms
   ...
   ```

   When no deployment directory is present, `up` first renders the target class configuration.
   Prior to starting any containers, it confirms that the target image is present (exiting with 6
   and printing the `px-cluster build` command if absent) and executes the host capacity preflight
   (exiting with 3 and reporting the memory arithmetic if the container limits exceed the budget).
   Running `px-cluster --class validation --dry-run up` performs these same validations without
   altering host state. Next, `up` persists the cluster record `$PX_OUT/state/cluster-default.env`,
   starts the host sleep inhibitor and background health monitor, creates the containers, and waits
   for all services to report ready. Because subsequent cluster commands read the active run class
   and parameters from that state record, later invocations do not need to repeat
   `--class validation`. Bring-up completed in 29 to 40 s on the reference host.

8. Examine the runtime guard and state files written to the state directory during bring-up:

   ```sh
   ls "$PX_OUT/state"
   ```

   ```text
   cluster-default.env
   compose
   deployment-compose-default
   inhibit-cluster-px-default
   monitor-px-default
   monitor-px-default.heartbeat
   scope-compose-default
   ...
   ```

   The `cluster-default.env` file stores the active cluster record and is removed by
   `px-cluster down`. The sleep inhibitor keeps the host awake while the cluster is running, and
   the health monitor polls container memory utilization every 30 s, appending each sample row to
   `$PX_OUT/monitor/<run-id>/samples.tsv`. If free host memory drops below 1024 MiB, the monitor
   writes `breach.tsv`, tears the cluster down, and exits 3.

9. Display the running cluster service instances and their readiness states:

   ```sh
   px-cluster status
   ```

   ```text
   role          index  instance_id  state    ready  image_ref                     node
   client        0      ...          running  ready  px-harness:hbase-2.6.7-...    ...
   datanode      0      ...          running  ready  ...
   hbase-master  0      ...          running  ready  ...
   namenode      0      ...          running  ready  ...
   regionserver  0      ...          running  ready  ...
   ...
   ```

10. Query the local loopback port bound to the HBase Master HTTP interface:

    ```sh
    px-cluster endpoint hbase-master 0 http
    ```

    ```text
    127.0.0.1	16010
    ```

    Every service port is published exclusively on `127.0.0.1`. You can open
    `http://127.0.0.1:16010` in a web browser or query the live RegionServer counts directly from
    the JMX endpoint:

    ```sh
    curl -s 'http://127.0.0.1:16010/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server' |
      jq -c '.beans[0] | {numRegionServers, numDeadRegionServers}'
    ```

    ```text
    {"numRegionServers":1,"numDeadRegionServers":0}
    ```

11. Examine the Phoenix client properties file inside the `client-0` container instance:

    ```sh
    px-cluster exec client 0 -- bash -c 'cat "$PX_CONF_DIR/phoenix-client.properties"'
    ```

    ```text
    ...
    phoenix.jdbc.url=jdbc:phoenix:zookeeper-0:2181:/hbase
    hbase.zookeeper.quorum=zookeeper-0
    ...
    ```

    The single quotes preserve `$PX_CONF_DIR` so that the shell inside the container expands it.
    Running a JDBC or HBase client directly on the host is not supported because HBase returns
    internal container hostnames that the host cannot resolve. Always run client processes inside
    `client-0` or `tooling-0`. To open an interactive shell, run
    `px-cluster exec client 0 -- /bin/bash` from a terminal.

12. Attempt to provision a second cluster while the current deployment remains active:

    ```sh
    px-cluster --class validation up
    ```

    ```text
    ... ERROR px-cluster: cluster default is up already in scope px-default, recorded in
    .../phoenix-harness/state/cluster-default.env: run px-cluster down first
    ... ERROR px-cluster: up ends (exit 2, usage)
    ```

    The harness permits only one active cluster per host. Running two clusters simultaneously on the
    same host causes contention for memory and loopback ports, rendering measurements from either
    run meaningless.

### Run the smoke suite

13. Display the smoke suite execution plan. A dry-run check neither contacts a container backend
    nor writes any files:

    ```sh
    px-suite --dry-run smoke
    ```

    ```text
    1	sql	Create, load, index and query one table	driver	psql	validation		600
    2	client	Extract the DDL of a user table with a Phoenix client class	driver	java-main	validation		600
    ```

    The output columns list the test position, the test id, the descriptive name, the execution
    mode, the driver, the target run class, any required role groups, and the time limit in
    seconds. The suite definition resides in `dev/harness/suites/smoke.suite`.

14. Run the smoke suite and capture its result stream:

    ```sh
    px-suite smoke | tee "$HOWTO/smoke.tsv"
    ```

    ```text
    ... INFO  px-suite: session 20261127T090450Z-fb1x in .../phoenix-harness/sessions/...
    1	sql	SKIP	0	65092	the verdicts of this test reach SKIP	.../runs/...
    2	client	SKIP	0	43182	the verdicts of this test reach SKIP	.../runs/...
    result	SKIP	0	.../phoenix-harness/sessions/20261127T090450Z-fb1x
    ... INFO  px-suite: session 20261127T090450Z-fb1x ends at SKIP: 0 pass, 2 skip, 0 advisory,
    0 fail, 0 error (exit 0, ok)
    ```

    Each result row reports the test position, the test id, the aggregate severity, the exit code,
    the elapsed duration in milliseconds, a summary explanation, and the path to the test's result
    bundle. The final `result` row records the session severity, its exit code, and the session
    directory. A healthy smoke run resolves to `SKIP` rather than `PASS` because verdict aggregation
    retains the highest observed severity, and `SKIP` outranks `PASS`. Because the psql and
    java-main drivers capture neither per-operation latency nor a pacing schedule, timing checks
    such as `query_latency.p50_ms` and `offered_rate` emit documented `SKIP` rows. All row-count and
    functional assertions evaluate to `PASS`, and an aggregate `SKIP` verdict exits 0. In the `sql`
    test, the `prepare` phase creates a table and a covered index, while the `run` phase performs a
    row count, an indexed point lookup, an upsert write, a delete, and a range count. The `client`
    test runs `SchemaTool` inside `client-0` to verify the runtime classpath and client settings.
    On the reference host, the suite completed in 109 s. The session keeps the cluster running; step
    17 tears it down.

15. Examine the generated session summary record:

    ```sh
    SESSION="$(awk -F '\t' '$1 == "result" { print $4 }' "$HOWTO/smoke.tsv")"
    grep -E '^(hbase_version|hadoop_version|jdks|severity|result|exit_code)' \
      "$SESSION/summary.tsv"
    ```

    ```text
    hbase_version	2.6.7
    hadoop_version	3.4.3
    jdks	PX_BUILD_JDK=17 PX_CLIENT_JDK=17 PX_DN_JDK=17 ... PX_ZK_JDK=17
    severity	SKIP
    result	SKIP
    exit_code	0
    ```

    The `jdks` field records all `PX_*_JDK` settings in the active configuration, covering the
    build JDK and each role's JDK defined by the run class; every cluster role defaults to JDK 17.
    The session directory also persists `plan.tsv`, `results.tsv`, `suite.log`, `groups.tsv` (when
    a test activates an on-demand role group), and one subdirectory per test under `tests/<id>/`
    containing `stdout.txt` (the result bundle path written by `px-run`), `stderr.log`, and
    `verdicts.effective.tsv`. `results.tsv` stores the stdout test rows, with the bundle column.

16. Extract the result bundle path from the `sql` test row and validate the bundle structure:

    ```sh
    RUN="$(awk -F '\t' '$2 == "sql" { print $7 }' "$HOWTO/smoke.tsv")"
    px-bundle validate "$RUN" | awk -F '\t' '$2 != "ok" || $1 == "result"'
    ```

    ```text
    path	skip	breach.tsv is absent
    ...
    result	ok	0	.../phoenix-harness/runs/...
    ```

    Each output row reports a validation check name, its status, and an explanatory detail, while
    the trailing `result` row summarizes the overall status, the exit code, and the bundle path.
    Every `skip` entry denotes an optional artifact path that this specific run did not generate.
    Consult H03 for a detailed walkthrough of the result bundle directory structure.

17. Tear down the active cluster so that the next exercise can provision a different topology:

    ```sh
    px-cluster down
    ```

    ```text
    ...
    ... INFO  px-cluster: cluster default is down
    ```

    The `down` subcommand removes the cluster containers, the runtime scope, and the state record.
    Running `down` a second time logs `no cluster default is recorded` and exits with status 0.

### Bring up three RegionServers

18. Provision a validation-class cluster scaled out to three RegionServers:

    ```sh
    px-cluster --class validation -e PX_RS_COUNT=3 up
    ```

    ```text
    ... rendered 10 services ... 13824 MiB of container limits ...
    memory:regionserver	ok	3 x 2560 MiB = 7680 MiB
    memory_fit	ok	the limits 13824 MiB (13.50 GiB) fit the budget with 6081 MiB ...
    ```

    On a 16 GiB container engine, the available cluster budget is 12288 MiB, so the preflight check
    rejects this deployment topology, and the up command terminates with `(exit 3, anomaly)`:

    ```text
    memory_fit	fail	the limits 13824 MiB (13.50 GiB) exceed the budget by 1536 MiB ...
    ```

    Each scalable worker role (DataNode, NodeManager, and RegionServer) accepts between 1 and 9
    instances.

19. Confirm that all three active RegionServers have registered with the HBase Master:

    ```sh
    curl -s 'http://127.0.0.1:16010/jmx?qry=Hadoop:service=HBase,name=Master,sub=Server' |
      jq -c '.beans[0] | {numRegionServers, numDeadRegionServers}'
    ```

    ```text
    {"numRegionServers":3,"numDeadRegionServers":0}
    ```

20. Run the smoke suite against the three-RegionServer cluster and then tear the deployment down:

    ```sh
    px-suite smoke
    px-cluster down
    ```

    ```text
    1	sql	SKIP	0	69312	the verdicts of this test reach SKIP	.../runs/...
    2	client	SKIP	0	45660	the verdicts of this test reach SKIP	.../runs/...
    result	SKIP	0	.../phoenix-harness/sessions/...
    ...
    ... INFO  px-cluster: cluster default is down
    ```

    On the reference host, the suite completed in 115 s, after which `down` tore down the cluster.

### Bring up the measurement class

21. Attempt to start the measurement class using its committed default sizing. On any container
    engine with less than roughly 48 GiB of memory, the preflight check rejects the deployment and
    no container instances start:

    ```sh
    px-cluster --class measurement up
    ```

    ```text
    memory_fit	fail	the limits 41984 MiB (41.00 GiB) exceed the budget by 22078 MiB
    (21.56 GiB); the budget 19905 MiB (19.44 GiB) is the engine 24001 MiB (23.44 GiB) less the
    headroom 4096 MiB (4.00 GiB)
    ... ERROR preflight.sh: the deployment ... does not fit the engine. ... The limits per role:
    ...
    ... ERROR px-cluster: up ends (exit 3, anomaly)
    ```

    For the measurement class, the preflight check also requires at least 8 processors and
    20480 MiB of available disk space.

22. Provision the measurement class using the sizing override profile designed for a 24 GiB engine:

    ```sh
    px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env up
    ```

    ```text
    memory:regionserver	ok	2 x 3584 MiB = 7168 MiB
    memory:resourcemanager	ok	1 x 768 MiB = 768 MiB, group yarn starts on demand
    ...
    memory_fit	ok	the limits 19200 MiB (18.75 GiB) fit the budget with 705 MiB ...
    ```

    Steps 22 and 23 record a live `up` deployment of this topology on the reference host. The
    calculated memory total includes the YARN service tier even though those instances remain
    stopped until requested by a test. Any measurement run on this downsized cluster receives the
    `contamination sizing` tag, which demotes duration verdicts to `ADVISORY` while preserving the
    authority of row-count and functional checks (H01). Passing `-e PX_RS_COUNT=1` or
    `-e PX_RS_COUNT=3 -e PX_YARN=0` selects the alternative shapes supported by the override.

23. Query the status of the on-demand role groups configured for the deployment:

    ```sh
    px-cluster group status yarn
    ```

    ```text
    group  running  desired  state
    yarn   0        3        down
    ```

    Running `px-cluster group start yarn` starts the YARN group after performing a memory check, and
    `px-cluster group stop yarn` stops it. Within a suite, a test instead declares `requires: yarn`
    to manage the group automatically (H08).

## Read the result

The build is validated when the self-test passes, cluster bring-up exits 0, and the smoke suite
exits 0 with every test reporting `PASS` or `SKIP`. A `FAIL` verdict indicates that a row count or
functional assertion did not hold; inspect the `detail` column of `report/verdicts.tsv` inside that
test's result bundle. An `ERROR` verdict indicates a harness or cluster fault, such as an unplanned
container restart; inspect the `anomaly` field in `manifest.json` along with the daemon logs under
`logs/instance/`. Do not run performance measurements on any build that fails to pass this HOWTO.

## Reproduce it

To reproduce the run, record the `tree` and `phoenix_version` rows from
`$PX_OUT/build/phoenix/build.tsv`, the container image tag from step 6, and the version entries in
`summary.tsv`, and retain the result bundles produced by the session (H03).

## Clean up

1. Tear down the running cluster:

   ```sh
   px-cluster down
   ```

   ```text
   ...
   ... INFO  px-cluster: cluster default is down
   ```

## See also

* [01-measurement-protocol.md](01-measurement-protocol.md): Explains run classes and the memory
  budget arithmetic.
* [03-result-bundles-and-automation.md](03-result-bundles-and-automation.md): Documents result
  bundles, verdicts, and exit codes.
* [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md): Walks through running the
  first Pherf workload.
* [11-scale-out-capacity-and-skew.md](11-scale-out-capacity-and-skew.md): Covers scaling across 1,
  3, and 5 RegionServers.
* [14-fleet-through-the-harness.md](14-fleet-through-the-harness.md): Introduces the Distributed
  Pherf tier and the `pherf-distributed` workload driver.
* [../../README.md](../../README.md): Summarizes the CLI commands, operational policy, and host
  safety rules.
* [../backends.md](../backends.md): Details the container network, the port map, and scope
  resolution.
