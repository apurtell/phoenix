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

# How to detect a regression and compare builds, configurations and versions

## The question

Does build B perform more slowly than build A, or does a configuration modification or version
change degrade workload performance beyond the bounds of normal run-to-run variance? A single
execution on each side cannot resolve this question, because even two identical runs of the same
build produce different measurements. This HOWTO executes multiple replicates of a single workload
on each side, aggregates the resulting bundles into a baseline set and a candidate set, and
evaluates them offline using `Pherf -analyze` through set medians, seeded bootstrap confidence
intervals, a fixed 10% regression threshold, and an exact count-parity verification. It then
demonstrates how to isolate a single configuration parameter change, how to re-pin component
versions, and how to execute a full version matrix.

All procedures in this HOWTO have been verified directly against the codebase. They were not
executed live to generate this document, because compiling the two builds and running the version
matrix require approximately 40 minutes of build time.

## Before you start

**Audience.** This guide is intended for developers who must demonstrate the performance impact of a
change, as well as committers who evaluate and review such claims.

**Prerequisites.**

- Complete [How to run a Pherf scenario through the harness](04-pherf-through-the-harness.md) and
  [How to prove read-what-you-wrote correctness under load](07-read-what-you-wrote.md).
- Complete [How to bring up a cluster and smoke-test a build](02-first-cluster-and-smoke.md) so that
  at least one full `px-cluster build` has executed and staged the distributions and jHiccup under
  `$PX_OUT`.
- Verify that `git` and `jq` are installed on the host. The offline analyzer executes using the JDK
  and classpath entries recorded by `px-cluster build` in `$PX_OUT/build/phoenix/build.tsv`.

| Cost | Value |
|---|---|
| Wall time | Approximately 30 minutes for 3 rounds of A, B, and A, plus 8 minutes per build. |
| Docker memory | 8704 MiB for validation; about 19 GiB for measurement (`override-24gib.env`). |
| Disk | Two container images of about 5 GB each, plus a few MiB per validation bundle. |
| Files written | Writes 10 entries under `$HOWTO` (enumerated below) and a git worktree. |

Each individual replicate requires approximately 3 minutes, including cluster bring-up, while a
version matrix of two sets completed in 23 minutes during round A8. The artifacts written under
`$HOWTO` are `ab_schema.sql`, `ab.xml`, `ab.suite`, `set-*.env`, `bad.env`, `baseline/`,
`candidate/`, `baseline2/`, `RESULTS/ANALYSIS_ab.json`, and `RESULTS/ANALYSIS_aa.json`, alongside a
git worktree at `$HOME/phoenix-baseline`.

Initialize every shell session for this guide with the following three environment commands:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/h12"; mkdir -p "$HOWTO"
```

## Method

**The replicate protocol.**

- Execute at least 3 replicates on each side, or 5 replicates when enforcing a release or CI gate.
  The offline analysis emits a FAIL verdict only when both sets contain at least 3 values for a
  given metric; with fewer values, any detected regression is downgraded to ADVISORY.
- Execute the exact same scenario with identical seeds on both sides. Because the comparison pairs
  metrics by operation name, both sets must share the same scenario name and query ids.
- Modify only one independent variable at a time. Combining a new build with a configuration change
  in a single comparison makes it impossible to attribute a performance shift to either factor.
- Never mix run classes across or within sets. The analysis rejects any comparison that mixes
  validation and measurement runs, or any set containing mixed classes, terminating with exit 66
  and producing no report.

**The replication unit.** Define the scope of what a single replicate repeats, and state that choice
explicitly in your performance claim:

| Unit | What it captures | Cost |
|---|---|---|
| a run on one kept cluster | Captures workload and host execution noise. | Requires one run. |
| a fresh cluster per run | Adds bring-up: region placement and cold JVMs. | A bring-up per run. |
| alternating sides, A B A B A B | Also accounts for host drift over time. | A bring-up per run. |

This HOWTO provisions a fresh cluster for every replicate and interleaves the sides in each round:
A, B, and then a second A set used for the A/A calibration check. Consequently, the variance within
each set incorporates cluster bring-up variation, and any temporal drift on the host distributes
evenly across all sets. Every run reloads its target table from scratch and drops it upon
completion, ensuring that no replicate reads state left behind by another. If resource constraints
prohibit bringing up a fresh cluster per replicate, you can execute each set against a single kept
cluster; however, because that replication unit excludes bring-up variance, the resulting
confidence interval will be artificially narrow, and any FAIL verdict serves only as an
investigative lead rather than definitive proof.

**The statistics.** For every latency percentile and throughput measurement, each replicate
contributes a single summary value extracted from its JSON latency report. The analysis computes the
set median along with a 95% percentile bootstrap confidence interval of that median using 10,000
resamples and the fixed seed 8070, ensuring that identical input bundles always yield the exact
same interval. To compare two sets, the tool evaluates the difference between their medians and
constructs the corresponding bootstrap interval for that difference. A metric is classified as a
regression when the median difference exceeds 10% in the degraded direction and the confidence
interval excludes zero on that side. A latency percentile q is emitted for a replicate only when its
operation key records at least 1 / (1 - q) samples. When running 3 replicates per side, an interval
that excludes zero signifies that the measurements from the two sets do not overlap; two sets drawn
from the same distribution will fail to overlap in one specific direction by chance with a
probability of 1/20. Because each key yields up to five distinct metrics and the analysis applies no
statistical correction for multiple comparisons, an isolated regression on a secondary key must be
interpreted with care.

**False alarms and power.** For a single metric, two sets of n replicates drawn from the same
distribution fail to overlap in a given direction with a probability of 1 / C(2n, n), which equates
to 1/20 at 3 against 3, 1/70 at 4 against 4, and 1/252 at 5 against 5. A typical comparison
evaluates approximately 10 metrics, and a single regressed metric suffices to trigger a FAIL verdict
with exit 67. When run-to-run noise approaches 10%, the magnitude threshold no longer filters out
random variation. Under those conditions, a comparison with no true underlying change fails by
chance with a probability of approximately 1 - (19/20)^10, or 40%, at 3 against 3, dropping to
about 4% at 5 against 5. Consequently, you should run 5 replicates per side when operating an
automated CI gate. The A/A calibration check in step 11 measures the empirical noise floor of your
experimental design. Because the regression threshold is fixed at 10%, `-analyze` cannot
substantiate any claim of a performance effect smaller than 10%.

**Count parity.** Because the replicates within each set execute identical configurations, a
deterministic co-generated scenario must produce identical operation and row counts across every
replicate. Any discrepancy in these counts triggers an immediate FAIL verdict. Always inspect count
parity first, because a candidate build that silently performs less work can artificially appear
faster.

**Validity.** A detected regression produces a hard FAIL verdict only on measurement-class runs that
carry no contamination tags. On validation-class runs, or on any run marked with a contamination tag
(such as `quiet-host` or `sizing`), regressions are reported as ADVISORY and `timingAuthoritative`
is set to false. A development laptop equipped with a 24 GiB container engine can execute the
measurement class only by applying `override-24gib.env`, which attaches the `sizing` contamination
tag and keeps any detected regressions ADVISORY. An authoritative A/B comparison requires both a
quiet host and a container engine capable of accommodating the full measurement sizing table, which
demands approximately 48 GiB of container limits plus 4 GiB of headroom.

**Hypothesis.** Our working hypothesis is that the candidate build does not increase the workload
response p99 latency by more than 10% while completing the exact same amount of work. Establishing
this requires proving statistical equivalence: the claim holds only when the response p99 outcome is
`unchanged` and its upper confidence bound `ciHighPct` remains strictly below +10%, placing the
entire confidence interval beneath the regression threshold. Any interval that straddles +10%
renders the test inconclusive.

## Steps

### Prepare the workload

1. Create the table schema and Pherf scenario definition to drive co-generated point lookups at a
   rate of 100 operations per second for 30 s across 4 concurrent workers. Specifying the `LATE` lag
   policy without a lag limit ensures that every scheduled arrival executes, yielding exact
   operation counts.

   ```sh
   cat >"$HOWTO/ab_schema.sql" <<'EOF'
   CREATE TABLE IF NOT EXISTS PX_HOWTO.AB (
     K1 BIGINT NOT NULL PRIMARY KEY,
     V VARCHAR
   )
   EOF
   cat >"$HOWTO/ab.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <datamodel name="howto_ab">
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
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <dataSequence>RANDOM</dataSequence>
         <length>32</length>
         <name>V</name>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PX_HOWTO.AB" rowCount="10000" name="ab">
         <coGeneration seed="12"/>
         <querySet concurrency="4" warmupExecutions="50">
           <openLoop arrival="UNIFORM" lagPolicy="LATE">
             <stage durationMs="30000" rate="100"/>
           </openLoop>
           <query id="get" statement="SELECT V FROM PX_HOWTO.AB WHERE K1 = [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

### Build two images

2. Check out the baseline revision in a separate git worktree, replacing `BASELINE` with the target
   commit, branch, or tag.

   ```sh
   git worktree add "$HOME/phoenix-baseline" BASELINE
   ```

3. Build the baseline Phoenix jars from that worktree and package them into a dedicated container
   image tag. `px-cluster build` assembles the container image using the artifact paths that the
   Phoenix compilation records in `$PX_OUT/build/phoenix/build.tsv`.

   ```sh
   px-cluster -e PX_IMAGE_TAG=px-harness:ab-baseline build --tree "$HOME/phoenix-baseline"
   ```

   ```text
   ... INFO  px-cluster: built the image px-harness:ab-baseline
   ```

   Invoking `px-cluster build` from the active working tree for both builds ensures that the two
   images differ only in their compiled Phoenix jars. The baseline pom must map the active HBase
   profile to a compatibility module recognized by the harness; otherwise, the build fails with
   exit 1 and reports both values.

4. Build the candidate jars from the current working tree and package them into a container image
   under a second unique tag.

   ```sh
   px-cluster -e PX_IMAGE_TAG=px-harness:ab-candidate build
   ```

   Assigning an explicit tag to each image is essential because the default tag `PX_IMAGE_TAG` is
   derived solely from the HBase profile and the HBase and Hadoop release versions; consequently,
   two builds on the same version set share the default tag, so building the second side without one
   would overwrite the first image.

### Run the sets, alternating the sides

5. Define a shell helper function that executes a single replicate for one side on a freshly
   provisioned cluster: it brings up a cluster using the specified image, runs the workload, copies
   the resulting bundle into the target set directory, and tears the cluster down. The function
   accepts the set directory name, the image tag, and any optional `-e NAME=VALUE` environment
   overrides for cluster bring-up.

   ```sh
   side() {
     set_dir="$HOWTO/$1"; image="$2"; shift 2
     px-cluster --class validation -e PX_IMAGE_TAG="$image" "$@" up || return
     RUN="$(px-run pherf -- --scenario "$HOWTO/ab.xml" --schema "$HOWTO/ab_schema.sql")" &&
       mkdir -p "$set_dir" && cp -R "$RUN" "$set_dir/"
     px-cluster down
   }
   ```

   This function copies a bundle into the set directory only when `px-run` returns exit 0, because
   the offline analysis rejects any bundle with an `ERROR` result. If a run exits 1 due to a
   `cluster.log` check failure (H03, step 3), repeat that replicate.

6. Execute 3 interleaved rounds, where each round runs the baseline, the candidate, and a second
   baseline set used for the A/A noise-floor check. When running an automated gate in CI, set
   `N=5`.

   ```sh
   N=3
   for i in $(seq 1 "$N"); do
     side baseline px-harness:ab-baseline
     side candidate px-harness:ab-candidate
     side baseline2 px-harness:ab-baseline
   done
   ```

   ```text
   ... INFO  px-run: run 20261123T101501Z-a11x ends at ... (exit 0, ok)
   ... INFO  px-run: run 20261123T101812Z-b7q2 ends at ... (exit 0, ok)
   ... INFO  px-run: run 20261123T102120Z-c3m9 ends at ... (exit 0, ok)
   ...
   ```

   The run identifiers above are illustrative. Each round executes baseline, candidate, and
   baseline2 in sequence. Inside each set directory, every subdirectory corresponds to a single
   replicate sorted lexicographically by name, which naturally orders the run identifiers
   chronologically. Because the analyzer follows symbolic links, a symlink to a bundle directory is
   also accepted as a replicate, although copying the full bundle is preferable for archival.

7. Verify the container image recorded for each set. Within each bundle, `cluster/images.tsv`
   identifies the image used by each service instance, and its `image_id` column records the local
   image digest.

   ```sh
   for d in baseline candidate baseline2; do
     echo "$d $(awk -F '\t' 'FNR > 1 { print $3 }' "$HOWTO/$d"/*/cluster/images.tsv | sort -u)"
   done
   ```

   ```text
   baseline px-harness:ab-baseline
   candidate px-harness:ab-candidate
   baseline2 px-harness:ab-baseline
   ```

### Compare the sets

8. Extract the JDK path and runtime classpath for the offline analyzer from the build manifest.
   `px-cluster build` records `java_home` (the JDK used to compile Phoenix) along with
   `pherf_classpath`, which bundles the Pherf jar together with the client, bson, JAXB, activation,
   and Log4j dependencies required by the analyzer.

   ```sh
   B="$PX_OUT/build/phoenix/build.tsv"
   JAVA="$(awk -F '\t' '$1 == "java_home" { print $2 }' "$B")/bin/java"
   CP="$(awk -F '\t' '$1 == "pherf_classpath" { print $2 }' "$B")"
   ```

   The offline analysis parses the XML result files in each bundle via JAXB, which is no longer
   shipped with the JDK, while Pherf reports refusal diagnostics such as exit 66 strictly through
   Log4j; because the recorded classpath includes both dependency sets, refusals are never silent.
   The analyzer reads local bundle files exclusively and establishes no database connections, so no
   active cluster is needed. Always invoke a single Pherf jar to evaluate both sets, since the
   analyzer acts as an independent grading tool rather than a subject of the test.

9. Run the offline comparison, specifying the baseline set directory first and the candidate set
   directory second. Because Pherf writes the output report under `RESULTS/` relative to the active
   working directory, execute the command from within `$HOWTO`.

   ```sh
   (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf \
     -analyze "$HOWTO/baseline" "$HOWTO/candidate" -label ab)
   ```

   ```text
   Set baseline .../howto/h12/baseline: run class validation
     20261123T101501Z-a11x bundle 20261123T101501Z-a11x class validation contamination
     [quiet-host] result PASS
     ...
   Set candidate .../howto/h12/candidate: run class validation
     ...
   Regression response:ab:get steady p99Us +18.4% [+11.2%, +26.9%]
   pherf.analysis.count_parity	PASS	count	pherf	4 count key(s) equal in 2 set(s)
   pherf.analysis.regression	ADVISORY	duration	pherf	1 regression(s) of 9 metric(s); worst:
   response:ab:get steady p99Us +18.4% [+11.2%, +26.9%]; timing is not authoritative: run class
   validation
   Analysis: ADVISORY. Report: RESULTS/ANALYSIS_ab.json
   ```

   While the numerical figures above are illustrative, the output structure matches the exact
   format produced by the analyzer (with long lines wrapped here for legibility). The comparison
   summary is written to stdout, whereas Pherf directs INFO log messages and any refusal
   diagnostics to stderr. The analyzer exits with one of the following status codes:

   - 0: The analysis report was generated and contains no FAIL verdict rows.
   - 1: An invalid command invocation or unusable input bundle (for instance, a bundle missing its
     latency report) prevented analysis; no report file is produced.
   - 66: The input replicates mix different run classes; no report file is produced.
   - 67: The analysis report was generated and includes at least one FAIL verdict caused by a
     metric regression or a count-parity mismatch.

10. Query the metric comparison rows and top-level metadata from the generated JSON analysis report.

    ```sh
    jq -r '.comparisons[] | [.operation, .metric, .deltaPct, .ciLowPct, .ciHighPct, .outcome] |
      @tsv' "$HOWTO/RESULTS/ANALYSIS_ab.json"
    jq -c '{timingAuthoritative, regressionThresholdPct, seed: .bootstrap.seed, result}' \
      "$HOWTO/RESULTS/ANALYSIS_ab.json"
    ```

    ```text
    query:ab:get	p50Us	3.0864197530864197	-1.9230769230769231	6.024096385542169	unchanged
    query:ab:get	p99Us	7.6923076923076925	2.0408163265306123	12.359550561797752	unchanged
    response:ab:get	p99Us	18.421052631578947	11.214953271028037	26.881720430107528	regression
    run	opsPerSec	-0.08333333333333333	-0.4166666666666667	0.20833333333333334	unchanged
    {"timingAuthoritative":false,"regressionThresholdPct":10.0,"seed":8070,"result":"ADVISORY"}
    ```

    The figures shown above are illustrative, and each percentage is formatted at full double
    precision. A median shift greater than 10% whose confidence interval crosses zero is graded as
    `unchanged`, signifying that run-to-run variance is too wide to establish a statistically
    significant difference. Operation keys that appear in only one set are recorded under
    `unpaired`, while each entry under `sets` lists the per-replicate values, the set median, and
    the bootstrap confidence bounds. In an open-loop workload, throughput matches the offered rate
    whenever the cluster keeps pace, so regressions surface in latency before affecting throughput;
    observing a throughput regression requires driving load beyond the saturation knee, as covered
    in
    [How to find the maximum sustainable throughput and the saturation knee](06-saturation-knee.md).

11. Execute the A/A calibration check by comparing the primary baseline set against the second
    baseline set. Because both sets executed the exact same container image, the analysis must
    report no regressions.

    ```sh
    (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf \
      -analyze "$HOWTO/baseline" "$HOWTO/baseline2" -label aa)
    ```

    ```text
    ...
    Analysis: ADVISORY. Report: RESULTS/ANALYSIS_aa.json
    ```

    The output excerpt illustrates only the format, as these steps were not run live. Inspect the
    comparison entries in `RESULTS/ANALYSIS_aa.json` with the `jq` query from step 10 and
    confirm that every outcome is `unchanged`. The largest observed `deltaPct` and the widest
    confidence interval define the empirical noise floor of this experimental design; no A/B
    performance effect falling within those bounds can be credibly claimed. If a regression appears
    in the A/A comparison, the experimental setup is too noisy to support valid conclusions. In that
    case, increase the replicate count, extend the run duration, or quiet the host environment
    before repeating the A/A comparison.

    A fleet-wide A/B comparison evaluates merged latency histograms that aggregate each operation
    key across all participating agents. The `fleet-ab.suite` definition interleaves baseline and
    candidate runs using this same alternating protocol, and `-analyze` consumes the controller
    bundle generated for each replicate; see [H15][h15] for complete details.

### Compare two configurations of one build

12. A configuration A/B comparison runs one container image across both sets while varying a single
    configuration parameter at cluster bring-up. Interleave the sides using the helper function from
    step 5, passing the parameter override exclusively to the candidate side. For example, to test a
    block cache sized to 40% of the RegionServer heap rather than the default 30%:

    ```sh
    px-cluster --class validation -e PX_HBASE_BLOCKCACHE_SIZE=0.40 \
      config PX_HBASE_BLOCKCACHE_SIZE >"$HOWTO/candidate-config.txt"
    for i in $(seq 1 "$N"); do
      side cfg-base px-harness:ab-candidate
      side cfg-cand px-harness:ab-candidate -e PX_HBASE_BLOCKCACHE_SIZE=0.40
    done
    ```

    Evaluate `cfg-base` against `cfg-cand` with the analysis command from step 9, and retain the
    `config` command output alongside the bundle sets to preserve a record of the effective
    parameter value and its source.

    Always design a workload that actively exercises the parameter under test. Because the 10,000
    rows defined in `ab.xml` occupy roughly 40 bytes each, the entire dataset fits comfortably
    inside either cache size, meaning a block-cache A/B test using `ab.xml` will produce no
    measurable difference by design. Such a null result reflects an inadequate experimental design
    rather than proof of equivalence. To evaluate a block-cache sizing change meaningfully, the
    workload working set must fall between the two cache capacities. Under the validation class, the
    RegionServer heap is 1792 MiB, so a 30% allocation provides roughly 540 MiB whereas 40% provides
    about 720 MiB. A table containing approximately 1,000,000 rows with a `VARCHAR` column of length
    512 produces a dataset near that range. Furthermore, the read workload must traverse those rows
    multiple times to warm the cache, requiring at least a few million point lookups driven by the
    co-generation `MULTIPLICATIVE` traversal walk. By contrast, client-side or table-level
    properties such as `UPDATE_CACHE_FREQUENCY` are directly exercised even by small point-lookup
    workloads; see
    [How to profile a run and find the root cause](10-profiling-and-root-cause.md).

    Overriding a property that is not exposed through a `PX_*` variable requires a plug-in
    configuration overlay, as described in
    [How to extend the harness with plug-ins, checks and drivers](13-extending-the-harness.md).
    Modifying any memory-sizing variable (`PX_<ROLE>_MEM_MIB`, `_HEAP_MIB`, or `_DIRECT_MIB`) during
    a measurement-class run attaches the `sizing` contamination tag, which downgrades the timing
    authority of that set to advisory.

### Compare versions

13. Override a component version set either persistently for your workstation in
    `dev/harness/docker/versions.local.env` (which is ignored by git) or per command invocation via
    `--config FILE`. A version set governs the Phoenix build's HBase profile, the HBase and Hadoop
    distribution releases, and the runtime JDK version assigned to each service role:

    ```sh
    cat >"$HOWTO/set-2.6.env" <<'EOF'
    PX_PHOENIX_HBASE_PROFILE=2.6
    EOF
    cat >"$HOWTO/set-2.5.env" <<'EOF'
    PX_PHOENIX_HBASE_PROFILE=2.5
    EOF
    cat >"$HOWTO/set-jdk21.env" <<'EOF'
    PX_MASTER_JDK=21
    PX_RS_JDK=21
    EOF
    px-cluster --class validation --config "$HOWTO/set-2.5.env" \
      config PX_PHOENIX_HBASE_PROFILE 'PX_DIST_H*_VERSION' PX_IMAGE_TAG
    ```

    ```text
    PX_DIST_HADOOP_VERSION	3.4.3	derived
    PX_DIST_HBASE_VERSION	2.5.16	derived
    PX_IMAGE_TAG	px-harness:hbase-2.5.16-hadoop-3.4.3-profile-2.5	derived
    PX_PHOENIX_HBASE_PROFILE	2.5	.../howto/h12/set-2.5.env
    ```

    The line profiles `2.5` and `2.6` automatically resolve to the latest pinned patch release on
    their respective branches. Because the derived image tag incorporates the version set
    parameters, distinct version sets never collide on the same container image tag. The runtime JDK
    version is omitted from the image tag because the container image bundles JDK 11, 17, and 21
    simultaneously, allowing each service instance to select its configured JDK at startup. Never
    configure a JDK version higher than 21.

14. Confirm that the harness compatibility validation rejects an HBase distribution version falling
    outside the supported range of the selected profile.

    ```sh
    cat >"$HOWTO/bad.env" <<'EOF'
    PX_PHOENIX_HBASE_PROFILE=2.6.0
    PX_DIST_HBASE_VERSION=2.6.7
    EOF
    px-cluster --config "$HOWTO/bad.env" --dry-run build
    ```

    ```text
    ... ERROR px-cluster: HBase '2.6.7' (PX_DIST_HBASE_VERSION) is outside the range of profile
    2.6.0: its compatibility module 2.6.0 admits HBase 2.6.0 to 2.6.3
    ... ERROR px-cluster: build ends (exit 2, usage)
    ```

    Both `px-cluster build` (whether invoked with or without `--dry-run`) and the preflight checks
    of `px-cluster up` apply this same compatibility check, so an invalid version combination is
    rejected immediately before any image build or container startup begins.

15. Run a test suite sequentially across multiple version sets with `px-cluster matrix`. For each
    version set in the list, the matrix runner executes `px-cluster build`, provisions the cluster,
    runs the suite, and tears the deployment down. Defining three test blocks with identical
    parameters yields three replicates for each version set.

    ```sh
    cat >"$HOWTO/ab.suite" <<'EOF'
    suite: ab
    description: Three replicates of the A/B workload of HOWTO 12
    class: validation
    timeout: 900

    test: r1
      name: Co-generated point lookups, replicate 1
      driver: pherf
      args: --scenario ab.xml --schema ab_schema.sql

    test: r2
      name: Co-generated point lookups, replicate 2
      driver: pherf
      args: --scenario ab.xml --schema ab_schema.sql

    test: r3
      name: Co-generated point lookups, replicate 3
      driver: pherf
      args: --scenario ab.xml --schema ab_schema.sql
    EOF
    px-suite --dry-run "$HOWTO/ab.suite"
    px-cluster --class validation matrix "$HOWTO/ab.suite" "$HOWTO/set-2.6.env" \
      "$HOWTO/set-jdk21.env" | tee "$HOWTO/matrix.tsv"
    ```

    ```text
    1  .../set-2.6.env    2.6  2.6.7  3.4.3  ... PX_RS_JDK=17 ...  20261123T120001Z-mx1a-1  down  0
    2  .../set-jdk21.env  2.6  2.6.7  3.4.3  ... PX_RS_JDK=21 ...  20261123T120001Z-mx1a-2  down  0
    result  ...  0  .../phoenix-harness/matrix/...
    ```

    The session ids shown above are illustrative. Each output row reports the set index, the
    configuration file path, the HBase profile, the HBase and Hadoop releases, the role JDK
    versions, the session identifier, the final lifecycle stage completed, and the exit status. The
    trailing `result` row summarizes the worst verdict across all sets, the overall exit code, and
    the matrix output directory. The runner also writes these rows to
    `$PX_OUT/matrix/<id>/matrix.tsv`, while each suite session logs its effective version set in
    `summary.tsv`. Any relative file paths inside the suite definition resolve against the suite
    file's parent directory. A failure in one version set does not halt the remaining sets, and the
    command exits with the status code of the first set that did not pass. Note that the matrix
    runner executes a full Phoenix build for every set (taking about 8 minutes per build) even when
    the source inputs are unchanged, overwriting the working tree's `target/` directories on each
    pass.

16. Collect the run bundles produced for each version set and compare them using the procedure in
    steps 8 through 10. Within each session directory, column 7 of `results.tsv` records the bundle
    path for each executed test.

    ```sh
    for n in 1 2; do
      s="$(awk -F '\t' -v n="$n" '$1 == n { print $7 }' "$HOWTO/matrix.tsv")"
      mkdir -p "$HOWTO/set$n"
      cut -f 7 "$PX_OUT/sessions/$s/results.tsv" |
        while read -r b; do cp -R "$b" "$HOWTO/set$n/"; done
    done
    (cd "$HOWTO" && "$JAVA" -cp "$CP" org.apache.phoenix.pherf.Pherf \
      -analyze "$HOWTO/set1" "$HOWTO/set2" -label jdk21)
    ```

## Read the result

- **Count parity first.** The `pherf.analysis.count_parity` check must evaluate to PASS. A FAIL
  verdict indicates that the replicates within a set performed unequal amounts of work—due to a lost
  row, a dropped arrival, or a prematurely halted worker—which invalidates any latency comparison
  involving that set.
- **A regression** is recorded for any metric whose set median degrades by more than 10% with a
  bootstrap confidence interval that excludes zero. On measurement-class runs free of contamination
  tags and backed by 3 or more replicates per side, a regression produces a FAIL verdict and returns
  exit 67. Otherwise, the verdict is downgraded to ADVISORY with exit 0, and the detail field
  explains the reason.
- **No regression** does not automatically prove the absence of a performance change: a wide
  confidence interval that spans zero despite a large median delta indicates that measurement noise
  is obscuring the effect. To resolve the ambiguity, add more replicates, extend the run duration to
  collect more samples, or quiet the host environment.
- **The hypothesis holds** only when the response p99 metric is marked `unchanged` and its upper
  confidence bound `ciHighPct` lies strictly below +10%. Any confidence interval that crosses +10%
  leaves the equivalence test inconclusive.
- **The noise floor.** The A/A calibration check in step 11 must produce zero regressions. Any
  observed A/B difference that falls within the deltas of the A/A comparison cannot be claimed as a
  real effect, and `-analyze` cannot substantiate any performance claim smaller than the fixed 10%
  threshold.
- **A FAIL is a lead** whenever each set was executed on a single kept cluster, because that
  experimental design omits cluster bring-up variance. Confirm any such finding by re-running with
  a fresh cluster per replicate while alternating between the sides.
- **Review checklist.** Verify that both sides executed the exact same scenario and seeds; that each
  side includes at least 3 replicates (or 5 for a CI gate) collected in alternating order; that the
  A/A comparison is clean; that all runs share a single run class; that count parity is PASS; that
  `timingAuthoritative` is true for any hard FAIL claim; that the container image and version set
  for each side are recorded; and that all input bundles are archived. The comprehensive checklist
  is available in
  [How to plan a trustworthy measurement](01-measurement-protocol.md).

## Reproduce it

- Record the git commit of each side, the container image tags and local image ids from
  `cluster/images.tsv`, the active version set (from `cluster/topology.env` or the `summary.tsv` of
  a matrix session), the `ab.xml` scenario definition with its seed 12, and the analysis label.
- Because the offline analysis is strictly deterministic, identical input bundles and labels always
  generate the exact same report byte for byte. Archive `$HOWTO/baseline`, `$HOWTO/candidate`, and
  `$HOWTO/baseline2` together with the generated reports `RESULTS/ANALYSIS_ab.json` and
  `RESULTS/ANALYSIS_aa.json`.
- For matrix comparisons, archive the `set-*.env` configuration files alongside the resulting
  `matrix.tsv`.

## Clean up

1. Shut down any remaining cluster deployment. Because the replicate loops in steps 6 and 12 and
   the matrix runner tear down their clusters after each run, no cluster should be active; the
   teardown operation is idempotent and returns exit code 0 when no cluster is present.

   ```sh
   px-cluster down
   ```

   ```text
   ... INFO  px-cluster: no cluster default is recorded
   ```

2. Delete the temporary baseline git worktree when your analysis is complete.

   ```sh
   git worktree remove "$HOME/phoenix-baseline"
   ```

## See also

- [Pherf offline analysis](../../../../phoenix-pherf/ANALYSIS.md) specifies the required analysis
  inputs, evaluation rules, and output report schema.
- [How to read Pherf results, grade a run and compare replicates][p06] details how to interpret
  run outputs, evaluate verdict rows, and compare replicate sets.
- [The README of the harness][readme], sections "Versions and the HBase profile" and "The
  version matrix", documents version pinning, profile resolution, and matrix execution.
- [How to compare and stress a fleet: replicates, A/B with -analyze, and faults under fleet
  load][h15] applies this same A/B methodology to the merged histograms of a distributed fleet
  using `fleet-ab.suite`.
- [The result bundle, section "manifest.json"](../bundle-format.md) defines the structure and
  metadata fields of the bundle manifest.

[h15]: 15-fleet-replicates-ab-and-faults.md
[p06]: ../../../../phoenix-pherf/docs/howto/06-results-verdict-and-analysis.md
[readme]: ../../README.md
