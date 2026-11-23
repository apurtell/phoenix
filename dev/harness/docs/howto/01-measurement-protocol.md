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

# How to plan a trustworthy measurement

## The question

Can a given measurement credibly support the performance claim you intend to make? A raw metric
from a load test serves as valid empirical evidence only when four foundational elements are
established: the specific question the test answers, the experimental variables held constant, the
sources of environmental noise that were controlled, and the boundary conditions that would
invalidate the result. This document defines the core measurement protocol followed across all
other HOWTO guides. It details the architecture of run classes, the verdict authority rule, the
quiet-host admission gate, the contamination tagging model, and the container memory capacity
arithmetic. Furthermore, it outlines the statistical replication strategy and the provenance inputs
required to guarantee experimental reproducibility, concluding with the formal verification
checklist that committers use when reviewing performance claims.

## Before you start

**Audience.** This guide is intended for every reader of the HOWTO documentation set. Committers
evaluating a performance claim should consult the review checklist at the end of this document.

**Prerequisites.** You need a local checkout of the Phoenix source tree and `bash`. Every step in
this guide executes entirely offline: the exercises require no container runtime, no network
connectivity, no Maven build, and no active cluster.

| Cost | Value |
|---|---|
| Wall time | Approximately 10 minutes. |
| Docker memory | None, because no cluster is started. |
| Disk | Under 1 MiB. |
| Files written | `$PX_OUT/deploy/default/` stores a rendered deployment replaced on each render. |

Initialize every shell session for this guide with the following three environment setup lines:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h01"; mkdir -p "$HOWTO"
```

## Method

### State the question and the hypothesis

Formulate the experimental question before executing the first run. A well-formed question
identifies a single workload, a single target metric, a single comparison baseline, and a single
decision threshold. For example: "With the point-lookup workload at 2,000 operations per second,
does build B raise the p99 service time of the reads by more than 10% over build A?" The
corresponding null hypothesis states the expected outcome: "B does not raise the p99 by more than
10%." The experiment is designed to refute that hypothesis.

A question that lacks an explicit numerical threshold cannot yield a decisive answer. Similarly, a
question that alters two independent variables simultaneously, such as introducing both a new code
build and a modified configuration at the same time, confounds the evaluation and cannot isolate
which variable produced the observed effect.

### Controls

Hold every experimental input constant except for the specific variable under evaluation. Because
the harness automatically captures these environmental and configuration parameters in the output
bundle, you can audit the artifacts after execution to verify that all control variables remained
invariant:

| Control | Where the bundle records it |
|---|---|
| Version set: HBase profile, HBase, Hadoop, ZooKeeper, JDKs | `cluster/topology.env` |
| The image of each instance | `cluster/images.tsv`, `manifest.json` |
| The run class and the sizing of each role | `run-record.tsv` (`run` row), `cluster/services.tsv` |
| Role counts, HDFS replication, YARN | `cluster/topology.env` |
| The collectors | `metrics/index.tsv` (in the `collector.<name>` rows) |
| The thresholds of the checks | `thresholds.properties` |
| Scenario, rates, durations, seeds | The test files, which should be stored under `$HOWTO`. |

Maintain an identical set of active telemetry collectors across both sides of any comparative
study. Active collectors impose CPU and memory overhead within the target JVM. By default, the
measurement run class enables all six collectors; to perform a comparison without collector
overhead, pass `-e PX_COLLECTORS=none` to both sides of the test.

### Noise sources

Several systemic and environmental factors can perturb latency and throughput measurements even
when the code under test remains unchanged:

* Concurrent host activity: competing processes such as web browsers, compilation jobs, background
  file indexers, or a secondary container engine.
* Container runtime memory management: environments like Docker Desktop may reclaim virtual machine
  memory after the last running container terminates, forcing the subsequent cluster startup to
  absorb memory reallocation overhead.
* Host power management and thermal regulation: system sleep states, battery power-saving modes,
  and CPU thermal throttling. Although the harness actively inhibits system sleep while a cluster
  remains online, it cannot prevent hardware thermal throttling.
* Runtime and cache warmup transients: JVM just-in-time (JIT) compilation, dynamic class loading,
  initial connection establishment, HBase block cache population, and client-side region location
  caching. Guide H04 demonstrates how to configure the Pherf warmup phase and the client connection
  cache.
* Asynchronous cluster maintenance: background HBase and HDFS activities including memstore
  flushes, storefile compactions, region splits, and region balancer migrations.
* JVM garbage collection pauses occurring in either the client driver or the server daemons.
* Load generator artifacts: a client driver that exhausts its worker thread pool, or a closed-loop
  generator that blocks on each response before issuing the next request, masks the true queueing
  latency experienced by an external user. Guide H05 examines this architectural distinction
  between service time and response time.

The harness mitigates concurrent host contention through the quiet-host gate and exposes garbage
collection pauses and host scheduling stalls through its telemetry collectors (H10). Managing the
remaining variance sources is the experimenter's responsibility: you must design an adequate warmup
phase, provision a steady-state measurement window long enough to encompass background maintenance
cycles, and execute multiple independent replicates.

### The run classes

Every harness execution declares a single run class. Architecturally, a run class is a curated
profile of configuration defaults rather than a distinct code execution path. These profiles are
defined in `dev/harness/docker/env/class-validation.env` and
`dev/harness/docker/env/class-measurement.env`.

| | validation | measurement |
|---|---|---|
| Purpose | Verify build and workload correctness. | Produce authoritative timing evidence. |
| Engine | Approximately 16 GiB. | Approximately 48 GiB. |
| RegionServers, DataNodes | 1 and 1 (replication factor 1). | 3 and 3 (replication factor 3). |
| YARN, Secondary NameNode | Not rendered. | YARN started on demand; SNN rendered. |
| Quiet-host gate | Disabled (off). | Waits up to 300 s and requires a quiet host to proceed. |
| Collectors | Off by default; `px-run --collect LIST` opts in. | All six enabled by default. |
| Authoritative | Counts, shares, and functional checks. | Also duration metrics (see below). |

Because both run classes capture full High Dynamic Range (HDR) latency histograms from the workload
driver, the resulting artifact bundles share an identical structural schema. Within the measurement
class, however, duration verdicts carry authoritative status only when executed on a verified quiet
host using the unoverridden measurement resource sizing.

### The authority rule

Every evaluated verdict row is assigned a metric classification: `count`, `share`, `duration`, or
`none` (denoting a functional assertion). Verdicts for count, share, and functional checks are
authoritative across every run regardless of class. By contrast, a failing duration verdict is
downgraded to `ADVISORY` unless the execution satisfies three strict preconditions: it runs under
the measurement class, executes on a verified quiet host, and uses the standard measurement sizing.
The `px-report` utility enforces this authority rule automatically, and every emitted metric record
embeds both the active run class and any contamination flags present at write time, ensuring that
downstream consumers cannot mistake a validation timing for an authoritative performance baseline.

Consequently, a validation run will fail hard if a data row is lost, but it will never fail a gate
due to a slow query execution. Similarly, running the measurement class on a contended host or with
reduced container sizing still produces diagnostic timing distributions that you can inspect, but
those timings are prevented from acting as automated pass/fail gates.

### The quiet-host gate

During the run lifecycle, immediately after the `validate` phase and prior to `prepare`, `px-run`
invokes `px-tool quiet-host`. This gate evaluates the host's one-minute system load average,
normalized as a percentage of total logical processors, against an upper threshold.

| Setting | Default (measurement) | Meaning |
|---|---|---|
| `PX_QUIET_HOST_WAIT_S` | 0 (300) | Seconds to wait for load to drop; 0 takes a single sample. |
| `PX_REQUIRE_QUIET_HOST` | 0 (1) | If 1, the run aborts with exit 4 when the host stays busy. |
| `PX_QUIET_HOST_LOAD_PCT` | 25 (25) | The host is classified as quiet at or below this percent. |

The gate evaluation resolves to one of five terminal states: `quiet`, `contaminated` (the host was
busy, but quietude was not mandatory), `refused` (the host remained busy when quietude was
required, terminating with exit code 4), `unmeasured`, or `off` (configured with wait 0 and not
required, so no load sample is taken). Any state other than `quiet` that permits execution to
proceed attaches the `quiet-host` contamination tag to the run record. Because the gate is disabled
by default in the validation profile, every validation run carries this tag.

Note that the quiet-host gate samples host utilization only once, prior to the `prepare` phase.
Transient host load that begins while the test is already underway is neither intercepted nor
tagged by the admission gate. To verify that the host remained uncontended throughout execution,
activate host telemetry with `--collect host` and examine `metrics/host.tsv` across the steady
measurement window.

### The contamination tags

A contamination tag formally marks a run's timing data as unsuitable for authoritative latency or
throughput claims. When contamination occurs, `px-run` records one `contamination` row per distinct
cause in `run-record.tsv`, and the bundle manifest aggregates the active causes within the
`quiet_host.tags` array. Two root causes trigger contamination tagging:

* `quiet-host`: Either the quiet-host admission gate was disabled, or the host exceeded the load
  threshold when sampled.
* `sizing`: A measurement-class run was executed against resource allocations that deviate from the
  committed measurement sizing table. Three conditions trigger this tag: overriding any
  `PX_<KEY>_MEM_MIB`, `PX_<KEY>_HEAP_MIB`, or `PX_<KEY>_DIRECT_MIB` parameter in the run
  configuration; running against a rendered cluster whose topology record declares active sizing
  overrides; or running a measurement test against a cluster originally rendered for the validation
  class.

The provided `dev/harness/docker/env/override-24gib.env` profile scales down a measurement-shaped
topology so that it fits within a 24 GiB container engine. Specifically, it provisions 2
RegionServers with a 2.25 GiB heap (rather than 3 RegionServers with a 3 GiB heap), 2 DataNodes
configured with replication factor 2, a single NodeManager, and proportionally reduced JVM heaps
across all remaining roles. Under these reduced allocations, the garbage collection dynamics and
block cache residency of each RegionServer diverge from the committed baseline topology, and
aggregate cluster throughput is correspondingly reduced. Consequently, every run executed with this
override profile is automatically tagged with `sizing`, rendering all duration verdicts `ADVISORY`.
Record count verdicts remain fully authoritative, however, because deterministic row counts are
invariant with respect to JVM heap capacity.

### The memory arithmetic

Before instantiating any containers, `px-cluster up` executes a host capacity preflight check. This
preflight sums the configured container memory limits across all rendered services and compares the
aggregate demand against the container engine's total memory minus a mandatory 4096 MiB host
headroom reservation. Any deployment whose aggregate limit exceeds this available budget is
rejected immediately with exit code 3 and an error message detailing the capacity arithmetic. The
YARN service tier is always included in this total, even when configured to start on demand.

For the three standard container engine allocations, the resulting usable memory budgets are
12288 MiB (for a 16384 MiB engine), 19905 MiB (for a 24001 MiB engine), and 45056 MiB (for a
49152 MiB engine). All memory figures in the table below are expressed in MiB.

| Shape | Limits | 16 GiB | 24 GiB | 48 GiB |
|---|---|---|---|---|
| validation, 1 RegionServer | 8704 | 3584 spare | fits | fits |
| validation, `PX_YARN=1` | 12032 | 256 spare | fits | fits |
| validation, `PX_RS_COUNT=3` | 13824 | 1536 over | 6081 spare | fits |
| measurement | 41984 | over | 22078 over | 3072 spare |
| measurement, `override-24gib.env` | 19200 | over | 705 spare | fits |
| the same, `PX_RS_COUNT=3`, `PX_YARN=0` | 19456 | over | 449 spare | fits |
| the 5-RegionServer variant in the override | 19328 | over | 577 spare | fits |

The 5-RegionServer configuration listed in the final row corresponds to the block of eight
commented lines at the top of `override-24gib.env`. To activate that topology, copy the file into
`$HOWTO` and strip the leading `# ` comment prefix from those eight lines.

Beyond memory capacity, the host preflight enforces processor and storage minimums: the measurement
class requires at least 8 logical processors and 20480 MiB of available disk space under `$PX_OUT`,
whereas the validation class requires at least 2 logical processors and 4096 MiB of free disk
space.

### Warmup and the connection cache

The initial operations executed against a fresh cluster incur cold-start initialization penalties,
including JVM class loading, network connection establishment, system catalog metadata lookups, and
client-side region location discovery. These transient costs must be excluded from the measured
evaluation window. In a Pherf workload definition, configuring `warmupExecutions="K"` on a query
set instructs each worker thread to execute K unmeasured warmup iterations of each query before
recording timed executions. Additionally, enabling the `-connectionCache` flag retains idle JDBC
connections—bounded at a maximum of one cached connection per worker thread—and reuses them across
subsequent operations so that measured latency samples do not include connection establishment
overhead. At the harness level, workload drivers emit their primary measured operations strictly
within a demarcated `steady@<name>` phase window. Guide H04 illustrates the usage of both
mechanisms, while LATENCY.md specifies how `warmup` metric keys are segregated from `steady` metric
keys in the generated report.

While a small number of warmup iterations eliminates one-time cold-start initialization, it is
insufficient to drive the JVM JIT compilers on the client and servers into full steady-state code
generation, which requires sustained execution over a longer duration. Determine the required
warmup duration empirically: inspect the periodic interval log from an exploratory run and identify
the elapsed time after which the interval p50 and p90 latencies stabilize and cease trending
downward (see H05, step 15). Once that stabilization horizon is known, either scale
`warmupExecutions` accordingly or prepend a dedicated warmup query set assigned a distinct query
identifier that is excluded from performance claims. Do not use a leading stage of the same query
set for warmup, because its latency samples are aggregated directly into the same `steady` metric
keys as the measured phase.

### Replicates

In statistical terms, a single test execution constitutes only a single data sample. Execute each
side of a comparative evaluation across at least 3 independent replicates. The Pherf comparative
analysis engine (`-analyze`, covered in H12 and P06) reduces each metric to one summary statistic
per replicate, computes the median across replicates, and derives a bootstrap confidence interval
using the deterministic random seed 8070. The analyzer flags a detected regression as an
authoritative `FAIL` only when both sides of the comparison provide at least 3 replicates and carry
authoritative timing status; otherwise, the regression finding is downgraded to `ADVISORY`.

Select the physical unit of replication deliberately based on the scope of the change:

* **Same cluster, several runs** (one `px-run` call per replicate, each leaving the cluster up,
  then `px-cluster down`): This approach isolates run-to-run execution variance in a fixed cluster
  instance. It is computationally inexpensive and appropriate when evaluating workload parameter
  adjustments or client-side configuration changes.
* **A fresh cluster for each run**: This design captures full cluster lifecycle variance, including
  initial region assignment and cold server cache state. Comparing two distinct code builds
  requires fresh cluster provisioning regardless, because each build is packaged into its own
  container image.

Whenever practical, interleave replicates across the two candidates (A, B, A, B, ...) rather than
running all replicates of A before B. Interleaving distributes gradual environmental drift on the
host evenly across both candidate sets rather than biasing one side.

**False alarms and power.** Given n independent replicates per side drawn from an identical
underlying distribution, the probability that the two sample sets exhibit complete non-overlap in a
specific direction is 1 / C(2n, n). This one-sided non-overlap probability equals 1/20 for 3 versus
3 replicates, 1/70 for 4 versus 4, and 1/252 for 5 versus 5. Because a standard analysis evaluates
approximately 10 latency and throughput metrics simultaneously without applying a
multiple-hypothesis testing correction, family-wise false-positive rates accumulate when run-to-run
noise approaches the 10% effect threshold (where the magnitude threshold itself no longer filters
out random variation). Under those noise conditions, comparing two identical builds yields at least
one spurious regression with a probability of approximately 1 - (19/20)^10, or 40%, at 3 versus 3
replicates, dropping to approximately 4% at 5 versus 5 replicates. Consequently, you should use 5
replicates per side when enforcing an automated regression gate. Always conduct an A/A baseline
comparison first: comparing two replicate sets of the exact same build must produce zero
regressions, and the observed deltas establish the empirical noise floor of your environment (H12).
Finally, because the effect-size threshold in `-analyze` is fixed at 10%, this protocol cannot
statistically substantiate performance claims for effects smaller than 10%.

### Reproducibility inputs

An experiment is reproducible only when an independent evaluator can reconstruct and repeat the run
from an unambiguous record of its inputs:

* Component version sets and cryptographic pins: `dev/harness/docker/versions.env` specifies the
  committed SHA-512 checksum pins for the default HBase and Hadoop binary distributions. Any
  distribution version lacking a pre-declared pin follows a trust-on-first-use model, where the
  initial download records its verified digest in `$PX_OUT/dist/pins.env`. Container base image
  digests are persisted identically in `$PX_OUT/images/pins.env`, while
  `dev/harness/docker/versions.local.env` allows overriding cryptographic pins on an individual
  host.
* Container image identity: the composite image tag `PX_IMAGE_TAG` together with the exact image
  digest recorded for every service instance in `cluster/images.tsv`.
* Workload random seeds: the deterministic PRNG seeds governing both POISSON open-loop arrival
  schedules and co-generated synthetic data sets.
* The complete result bundle: archive the entire `$PX_OUT/runs/<run-id>` directory structure (H03).

### What invalidates a result

Any of the following conditions disqualifies an experimental run from supporting a performance
claim:

* The presence of any contamination tag when the claim concerns latency, duration, or throughput.
* Executing under the validation run class when the claim concerns timing or performance.
* An unplanned container restart, process exit, or out-of-memory (OOM) termination during the test:
  `px-run` aborts with exit code 3, and the `anomaly` field in the bundle manifest identifies the
  affected service instance.
* Discrepant record counts across replicates of a deterministic workload.
* Asymmetrical control parameters between the two comparison sides, including differences in
  component version sets, container image IDs, memory sizing, cluster topology, or active telemetry
  collectors.
* Inclusion of cold-start warmup activity inside the measured window, or a steady window too brief
  to encompass background memstore flushes and storefile compactions.
* An open-loop rate step whose duration is not many multiples of the client queue drain time. An
  overly brief step measures only the transient burst capacity absorbed by the internal submission
  queue rather than a sustainable throughput rate. Always evaluate rate attainment in conjunction
  with step duration: at 90% attainment, a 2.5 s step can mask a 10% system overload as a seemingly
  modest 250 ms queue backlog. Configure rate steps to run for at least 60 s.
* Using closed-loop measurements to support claims about end-user response time: a closed-loop
  driver suffers from coordinated omission because it fails to account for the queueing delay that
  unissued requests would have experienced while worker threads were blocked. Always use an
  open-loop arrival model for user latency claims (H05).
* Client-side driver saturation, as surfaced by failures in the `pherf.health.client_pool` and
  `pherf.health.saturation` diagnostic verdicts (P06).
* Providing fewer than 3 replicates per comparison side, or observing a bootstrap confidence
  interval for the percentage difference that spans zero.

## Steps

1. Execute the three environment initialization commands shown in the prerequisites section above.

2. Inspect the quiet-host configuration parameters for the validation run class along with their
   resolution origins:

   ```sh
   px-cluster --class validation config PX_RUN_CLASS 'PX_QUIET_HOST*' 'PX_REQUIRE_QUIET*'
   ```

   ```text
   PX_QUIET_HOST_LOAD_PCT	25	defaults
   PX_QUIET_HOST_WAIT_S	0	class:validation
   PX_REQUIRE_QUIET_HOST	0	class:validation
   PX_RUN_CLASS	validation	cli
   ```

   Each output row displays the variable name, its resolved value, and the configuration source
   that supplied it. Parameter resolution follows a strict precedence hierarchy in which the first
   stage that defines a variable wins: command-line flags (`cli`), process environment variables
   (`environment`), an explicit `--config` file (identified by its file path), the cluster target
   definition (`target`), the active cluster state record written by `px-cluster up` (`cluster`),
   `docker/env/local.env` (`local`), registered plug-ins (`plugin:<id>`), the selected run-class
   profile (`class:<name>`), the backend default settings (`backend:<name>`), `defaults.env`
   (`defaults`), and finally the component version files. Because no cluster is running during this
   HOWTO, the `cluster` origin does not appear in any output row. Consult the "Configuration
   precedence" section of the harness README for the full specification of this resolution order.

3. Inspect the corresponding quiet-host and telemetry collector settings for the measurement run
   class:

   ```sh
   px-cluster --class measurement config PX_COLLECTORS 'PX_QUIET_HOST*' 'PX_REQUIRE_QUIET*'
   ```

   ```text
   PX_COLLECTORS	profile,jfr,hiccup,host,container,trace	class:measurement
   PX_QUIET_HOST_LOAD_PCT	25	defaults
   PX_QUIET_HOST_WAIT_S	300	class:measurement
   PX_REQUIRE_QUIET_HOST	1	class:measurement
   ```

4. Verify that an explicit configuration override file takes precedence over the run-class profile:

   ```sh
   px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env \
     config PX_RS_COUNT PX_RS_HEAP_MIB PX_RS_MEM_MIB
   ```

   ```text
   PX_RS_COUNT	2	dev/harness/docker/env/override-24gib.env
   PX_RS_HEAP_MIB	2304	dev/harness/docker/env/override-24gib.env
   PX_RS_MEM_MIB	3584	dev/harness/docker/env/override-24gib.env
   ```

5. Render the deployment manifests for the validation run class. Rendering is a purely local file
   generation step that makes no calls to the container runtime:

   ```sh
   px-cluster --class validation render
   ```

   ```text
   ... INFO  compose.gen.sh: rendered 8 services for cluster default, class validation: 8704 MiB
   of container limits, 0 MiB of them on demand, into .../phoenix-harness/deploy/default
   ```

   To protect active deployments, the renderer refuses with exit code 2 if asked to overwrite the
   deployment directory of a currently running cluster. Always execute the render step prior to
   cluster bring-up or after tearing down an existing cluster with `px-cluster down`.

6. Render the standard measurement deployment topology:

   ```sh
   px-cluster --class measurement render
   ```

   ```text
   ... rendered 18 services for cluster default, class measurement: 41984 MiB of container
   limits, 10240 MiB of them on demand, ...
   ```

7. Render the measurement deployment topology with the 24 GiB resource overrides applied:

   ```sh
   px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env render
   ```

   ```text
   ... rendered 13 services for cluster default, class measurement: 19200 MiB of container
   limits, 3328 MiB of them on demand, ...
   ```

8. Inspect the computed per-service memory sizing table without writing any files to disk:

   ```sh
   px-cluster --class measurement --config dev/harness/docker/env/override-24gib.env \
     render --dry-run | cut -f1,6-9
   ```

   ```text
   zookeeper-0	512	256	64	17
   ...
   regionserver-0	3584	2304	384	17
   regionserver-1	3584	2304	384	17
   client-0	3072	2304	256	17
   ...
   ```

   The selected output columns report the service instance name, container memory limit, JVM heap
   allocation, JVM direct memory limit, and target JDK major version.

9. Examine the sizing override list persisted in the rendered topology record. Any
   measurement-class run executed against this cluster receives a `sizing` contamination tag
   corresponding to each overridden parameter name:

   ```sh
   grep -E '^PX_(RUN_CLASS|SIZING_OVERRIDES)=' "$PX_OUT/deploy/default/topology.env"
   ```

   ```text
   PX_RUN_CLASS=measurement
   PX_SIZING_OVERRIDES=PX_ZK_MEM_MIB PX_ZK_HEAP_MIB ... PX_RS_MEM_MIB PX_RS_HEAP_MIB ...
   ```

10. Execute a single immediate quiet-host sample enforcing the measurement-class requirement, and
    inspect the resulting exit code:

    ```sh
    px-tool -e PX_QUIET_HOST_WAIT_S=0 -e PX_REQUIRE_QUIET_HOST=1 quiet-host
    ```

    ```text
    sample	1795769636757	16	25
    quiet_host	quiet	1	0	16
    result	ok	0	host
    ... INFO  px-tool: the host is quiet: load 16% of its processors, threshold 25%
    ```

    The processor load percentage shown above is illustrative. If the host is busy when sampled,
    the `quiet_host` row records `refused` and execution terminates with `(exit 4, timeout)`,
    immediately aborting a measurement-class run.

11. Display the derived component version set and container image tag used during image building
    and test execution:

    ```sh
    px-cluster config PX_PHOENIX_HBASE_PROFILE PX_DIST_HBASE_VERSION PX_DIST_HADOOP_VERSION \
      PX_IMAGE_TAG
    ```

    ```text
    PX_DIST_HADOOP_VERSION	3.4.3	derived
    PX_DIST_HBASE_VERSION	2.6.7	derived
    PX_IMAGE_TAG	px-harness:hbase-2.6.7-hadoop-3.4.3-profile-2.6	derived
    PX_PHOENIX_HBASE_PROFILE	2.6	derived
    ```

    Because every distinct version combination derives its own unique image tag, different
    component version sets never collide or share a container image. Following the initial image
    build, `$PX_OUT/dist/pins.env` and `$PX_OUT/images/pins.env` record the trust-on-first-use
    cryptographic digests.

## Read the result

Compare the aggregate container memory limits reported during rendering against your container
engine's usable budget in the memory arithmetic table above. That usable budget equals the total
engine memory allocation minus the 4096 MiB host headroom reservation. Because any topology
exceeding this budget is rejected during preflight before any containers start, the table directly
determines which HOWTO exercises a given host can execute. A workstation provisioned with a 24 GiB
container engine can run the validation class natively as well as the measurement class when paired
with `override-24gib.env`. However, a 24 GiB host can never produce authoritative timing verdicts,
because applying the override file attaches the `sizing` contamination tag to every measurement
run. Its record count verdicts remain fully authoritative.

Whenever a configuration parameter resolves to an unexpected value, inspect the origin column of
`px-cluster config` first to trace which precedence layer supplied the setting.

## Reproduce it

All rendering and configuration steps in this protocol are purely deterministic: given an identical
source tree and configuration state, the harness produces byte-for-byte identical rendered
deployment files. Always include the version and image tag output from step 11 alongside any
performance results you publish.

## Committer review checklist

Use this verification checklist when evaluating a performance claim submitted in a JIRA issue or
pull request. Always request the complete result bundles for inspection rather than relying on a
written summary.

1. Is the declared run class set to `measurement` on both sides of the comparison? (Verify in
   `manifest.json` under `run.class`.)
2. Was the host verified to be quiet, with zero contamination tags recorded? (Confirm that
   `quiet_host.state` is `quiet` and `quiet_host.tags` is empty.)
3. Are at least 3 independent replicates provided for each side of the comparison?
4. Does the bootstrap confidence interval of the percentage difference exclude zero in the
   direction of the performance claim? (Check the `-analyze` output fields `comparisons[].ciLowPct`
   and `ciHighPct`.)
5. Are the record counts identical across all replicates for deterministic workloads? (Verify the
   count parity rows in the comparative analysis output.)
6. Are the component version set, container image IDs (excluding the specific build under test),
   memory sizing, active telemetry collectors, and random seeds held identical across both sides?
7. Is every replicate bundle archived, and does `px-bundle validate` pass cleanly on each bundle?
8. Was a well-formed experimental question, including an explicit numerical threshold, stated prior
   to executing the runs?
9. Do both rate attainment and client saturation checks PASS across every replicate? (Verify
   `pherf.health.attainment` and `pherf.health.saturation` to confirm that the intended load was
   actually offered.)
10. If the claim concerns end-user latency, is it evaluated using the `response:` metric keys from
    an open-loop workload? (See H05.)
11. Does every claimed latency percentile q contain at least 10 / (1 - q) recorded samples (for
    example, at least 1,000 samples for a p99 claim)?
12. Was the load generator client confirmed not to be the throughput bottleneck? (Inspect the
    saturation cause alongside the processor utilization of client 0 relative to the RegionServers,
    as detailed in H06.)
13. Is the measured steady evaluation window at least 60 s in duration?
14. Did a preliminary A/A comparison of the identical build produce zero false regressions? (See
    H12.)
15. Is the magnitude of the claimed performance effect at least 10%, matching the minimum detection
    threshold of `-analyze`?

Any performance claim that fails item 1 or item 2 must be treated as an exploratory hypothesis
rather than an authoritative result. Such preliminary evidence can still justify scheduling a
formal measurement run on a quiet host configured with full measurement sizing.

## See also

* [02-first-cluster-and-smoke.md](02-first-cluster-and-smoke.md): Guides you through the initial
  cluster bring-up and smoke verification.
* [03-result-bundles-and-automation.md](03-result-bundles-and-automation.md): Explains the result
  bundle directory layout and automated verdict evaluation.
* [04-pherf-through-the-harness.md](04-pherf-through-the-harness.md): Details workload warmup
  configuration and the client connection cache.
* [05-latency-slo-and-soak.md](05-latency-slo-and-soak.md): Contrasts service time against response
  time for latency SLO evaluation.
* [12-regression-ab-and-matrices.md](12-regression-ab-and-matrices.md): Covers multi-replicate
  statistical comparisons and A/B test matrices.
* [../../README.md](../../README.md): Documents configuration precedence rules, host safety
  guardrails, and verdict severity levels.
* [../bundle-format.md](../bundle-format.md): Specifies the bundle manifest schema and the metric
  merge lifecycle.
* [../../../../phoenix-pherf/ANALYSIS.md](../../../../phoenix-pherf/ANALYSIS.md): Describes the
  statistical methodology used by the comparative analyzer.
* [../../../../phoenix-pherf/VERDICT.md](../../../../phoenix-pherf/VERDICT.md): Explains the
  noisy-host contamination flag within Pherf verdict evaluation.
