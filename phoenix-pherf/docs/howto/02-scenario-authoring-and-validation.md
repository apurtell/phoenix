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

# How to author a scenario and validate it offline

## The question

How can you verify that a benchmark scenario will measure what you intend before committing cluster
time to execute it? A defective scenario can still run to completion while producing invalid
metrics: an unresolved placeholder left in a SQL statement causes every execution to fail, an
unseeded Poisson arrival schedule generates different arrival timestamps across replicates, and two
writers operating on overlapping key ranges overwrite each other's data. This HOWTO authors a valid
baseline scenario, injects one structural or semantic defect at a time, and demonstrates how
`-validate` detects each issue offline without opening a database connection. It then demonstrates
how the same validation logic operates automatically as the pre-run gate for every execution, how
`-allowUnknownTags` waives unrecognized XML tags, and how automated pipelines consume the process
exit code and the structured JSON report.

## Before you start

**Audience.** Benchmark authors who design Pherf scenarios and contributors who maintain or extend
the scenario validator.

**Prerequisites.**

- The compiled build artifacts and the environment script `$PX_OUT/howto/pherf-env.sh` produced in
  [HOWTO 01](01-first-pherf-run.md), steps 2 and 3.
- No live cluster is required because every step in this HOWTO runs entirely offline. Any step that
  specifies a ZooKeeper quorum passes `nohost.invalid`, a hostname guaranteed not to resolve, to
  verify that Pherf never opens a network connection during validation.
- Install `jq` to parse and inspect the generated validation JSON reports.

| Cost | Value |
|---|---|
| Wall time | Roughly 5 minutes in total, with each validation pass taking about 1 s. |
| Memory | A single Pherf JVM instance running on the host at a time. |
| Disk | Less than 1 MiB of storage. |
| Files written | `$HOWTO/*.xml`, `$HOWTO/*.log`, `$HOWTO/work/RESULTS/VALIDATION_*.json` |

All command outputs excerpted in this HOWTO were captured on JDK 17 using the build from this
source tree.

## Method

**Hypothesis.** A scenario that passes `-validate` will not fail at runtime initialization due to a
structural configuration defect, and each injected defect produces a single deterministic finding
identified by a stable rule id.

**What the validator checks.** The validator ingests the target scenario files, their referenced
workload profiles, and the DDL statements participating in the run (evaluating the external schema
files first, followed by the `preScenarioDdls` defined within the scenarios). It parses every SQL
statement through the Phoenix SQL parser and validates the co-generation parameters against the
expected initial state of a benchmark run—namely, the dataset state immediately following a complete
data load. Because the validator operates without database connectivity, it cannot verify that a
prior load actually populated the rows required by a query-only run executed without `-l`.

**Why each rule protects a measurement.**

The following list describes each validation rule, the configuration defect it targets, and how that
defect corrupts a benchmark measurement:

- `xml.unknown-element`: Detects an XML element that this version of Pherf does not recognize.
  Because the parser silently ignores unrecognized elements, a configuration setting that appears to
  be enabled actually remains off.
- `xml.value`: Detects a misspelled enumeration value, such as `arrival="POISON"`. The parser
  treats the unrecognized value as absent and falls back to the default behavior.
- `predicate.casing`: Detects a placeholder such as `[k2]` whose corresponding column rule is
  defined as `K2`. At runtime, Pherf leaves `[k2]` unresolved in the SQL statement, causing every
  query execution to fail without recording a latency sample.
- `openloop.rate`: Detects an open-loop workload configured with a concurrency range such as
  `concurrency="1-2"`. An open-loop scheduler requires a single fixed worker count, which also
  defines the capacity bound of its internal dispatch queue.
- `openloop.seed`: Detects an open-loop schedule configured with `arrival="POISSON"` but no
  `seed`. Without a fixed seed, the generated arrival timestamps vary across replicate runs, making
  replicates incomparable.
- `key.coverage`: Detects a mismatched predicate such as `K2 = [K1]`. During execution, the read
  query binds the value of the wrong column, fails to match any existing row, and measures an empty
  lookup instead of a row retrieval.
- `cogen.offsets`: Detects two scenarios writing overlapping row ranges, such as `[0, 1000)` and
  `[500, 1500)`, into a single table. The concurrent writers overwrite each other's keys,
  invalidating both the table row counts and the subsequent read workload.

The complete catalog of rule ids appears in the Validation section of [README.md](../../README.md).
Because rule ids remain stable across releases, automated tooling can match against them reliably.

**What invalidates the result.** A validation pass is invalid if it does not inspect the exact set
of files used by the benchmark execution; always verify the `files` and `schemaFiles` arrays in the
JSON report. Validation is also invalidated if it runs with different `-rowCountOverride`,
`-schemaFile`, or `-allowUnknownTags` flags than the benchmark invocation, because the validator
applies those options using the exact same resolution logic as a live run.

## Steps

1. Execute the environment setup commands from the root of the repository and change into the
   working directory.

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p02"; mkdir -p "$HOWTO/work"
   . "$PX_OUT/howto/pherf-env.sh"
   cd "$HOWTO/work"
   ```

2. Author a valid baseline scenario file. This scenario co-generates 1000 rows
   ([HOWTO 04](04-co-generation.md)), provisions its target table via a scenario-level DDL
   statement, and drives an open-loop workload with Poisson arrivals across a weighted mix of point
   lookups and range scans ([HOWTO 03](03-open-loop-profiles-and-mixes.md)).

   ```sh
   cat > "$HOWTO/orders.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8"?>
   <datamodel name="howto_orders">
     <datamapping>
       <column>
         <type>BIGINT</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <minValue>0</minValue>
         <maxValue>1000000000</maxValue>
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
         <dataSequence>LIST</dataSequence>
         <name>STATUS</name>
         <valuelist>
           <datavalue distribution="80"><value>OPEN</value></datavalue>
           <datavalue distribution="20"><value>SHIPPED</value></datavalue>
         </valuelist>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PHERF.HOWTO_ORDERS" rowCount="1000" name="orders">
         <preScenarioDdls>
           <ddl tableName="PHERF.HOWTO_ORDERS"
                statement="CREATE TABLE IF NOT EXISTS ? (K1 BIGINT NOT NULL, K2 INTEGER NOT NULL,
                  STATUS VARCHAR CONSTRAINT PK PRIMARY KEY (K1, K2))"/>
         </preScenarioDdls>
         <coGeneration seed="7" blockSize="100"/>
         <querySet concurrency="2" warmupExecutions="5">
           <openLoop arrival="POISSON" seed="11" maxLagMs="100" lagPolicy="DROP">
             <stage durationMs="10000" rate="20"/>
           </openLoop>
           <mix>
             <operation kind="SELECT" weight="80"/>
             <operation kind="SCAN" weight="20"/>
           </mix>
           <query id="get"
                  statement="SELECT STATUS FROM PHERF.HOWTO_ORDERS WHERE K1 = [K1] AND K2 = [K2]"/>
           <query id="scan" kind="SCAN"
                  statement="SELECT K2 FROM PHERF.HOWTO_ORDERS
                    WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   ```

3. Validate the baseline scenario. Passing `-disableSchemaApply` indicates that the run does not use
   external schema files because the DDL is embedded directly within the scenario, while `-label`
   assigns the identifier used to name the output report.

   ```sh
   pherf -validate -disableSchemaApply -label orders -scenarioFile "$HOWTO/orders.xml" \
     2>"$HOWTO/orders.log"
   echo "exit=$?"
   cat RESULTS/VALIDATION_orders.json
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_orders.json
   exit=0
   {
     "format": "pherf-validation",
     "version": 1,
     "label": "orders",
     "files": [
       "$HOWTO/orders.xml"
     ],
     "schemaFiles": [],
     "errors": 0,
     "findings": []
   }
   ```

   Diagnostic log entries (INFO, WARN, and ERROR) are written to standard error, whereas validation
   findings and the final summary line are emitted to standard output.

4. Create seven defective copies of the baseline scenario, injecting one distinct defect into each
   file.

   ```sh
   cd "$HOWTO"
   sed 's#<coGeneration #<thinkTime ms="5"/><coGeneration #' orders.xml > d-unknown.xml
   sed 's#arrival="POISSON"#arrival="POISON"#' orders.xml > d-enum.xml
   sed 's#K2 = \[K2\]#K2 = [k2]#' orders.xml > d-casing.xml
   sed 's#concurrency="2"#concurrency="1-2"#' orders.xml > d-range.xml
   sed 's# seed="11"##' orders.xml > d-seed.xml
   sed 's#K2 = \[K2\]#K2 = [K1]#' orders.xml > d-key.xml
   sed 's#</scenarios>#<scenario tableName="PHERF.HOWTO_ORDERS" rowCount="1000" name="ordersB">'\
   '<coGeneration seed="7" offset="500"/></scenario></scenarios>#' orders.xml > d-offsets.xml
   cd "$HOWTO/work"
   ```

5. Run the validator against each defective copy.

   ```sh
   for d in unknown enum casing range seed key offsets; do
     pherf -validate -disableSchemaApply -label "d-$d" -scenarioFile "$HOWTO/d-$d.xml" \
       2>>"$HOWTO/defects.log" | grep -v '^Validation:'
   done
   ```

   Captured output (with long lines truncated at `...`):

   ```text
   xml.unknown-element $HOWTO/d-unknown.xml:38 datamodel/scenarios/scenario[1]/thinkTime: ...
   xml.value $HOWTO/d-enum.xml:40 datamodel/scenarios/scenario[1]/querySet[1]/openLoop/@arrival: ...
   predicate.casing $HOWTO/d-casing.xml scenario orders querySet 1 query 1: placeholder [k2] ...
   openloop.rate $HOWTO/d-range.xml scenario orders querySet 1: openLoop of querySet 1 of ...
   openloop.seed $HOWTO/d-seed.xml scenario orders querySet 1: openLoop of querySet 1 of ...
   key.coverage $HOWTO/d-key.xml scenario orders querySet 1 query 1: column K2 is compared ...
   cogen.offsets $HOWTO/d-offsets.xml scenario ordersB: rows [500, 1500) overlap rows [0, ...
   ```

   Each finding line follows the format `<rule> <file>[:<line>] <where>: <message>`. Structural
   validation rules (`xml.*`) report the source line number of the element alongside its XML element
   path. Semantic model rules report the scenario name, the query set index, and the query index,
   using 1-based numbering. The complete diagnostic messages emitted for each rule are as follows:

   - `xml.unknown-element`: element `<thinkTime>` is not a child of `<scenario>` in the Pherf
     model, so the parser ignores it and its content
   - `xml.value`: the value 'POISON' is not one of UNIFORM, POISSON, so the parser reads it as
     absent
   - `predicate.casing`: placeholder [k2] has no rule, and rule K2 differs only in case, so the
     run leaves [k2] in the statement
   - `openloop.rate`: openLoop of querySet 1 of scenario orders: concurrency is not one integer
     of at least 1: 1-2
   - `openloop.seed`: openLoop of querySet 1 of scenario orders: arrival POISSON requires a seed
   - `key.coverage`: column K2 is compared with placeholder [K1], but the decoder binds [K1] to
     the value of K1 of the drawn row
   - `cogen.offsets`: rows [500, 1500) overlap rows [0, 1000) of scenario orders on table
     PHERF.HOWTO_ORDERS, so the writers write the same keys

6. Inspect the process exit code returned by Pherf when a defect is present. An exit code of 65
   (EX_DATAERR) indicates that at least one validation rule failed.

   ```sh
   pherf -validate -disableSchemaApply -label d-seed -scenarioFile "$HOWTO/d-seed.xml" \
     >/dev/null 2>&1
   echo "exit=$?"
   ```

   Captured output:

   ```text
   exit=65
   ```

   The `-validate` command returns exit code 0 when no findings are detected, 65 when one or more
   validation rules fail, and 1 when the command invocation is invalid or no scenario or schema
   files match the supplied patterns.

7. Inject three distinct defects into a single scenario file. A single validation pass reports all
   non-dependent findings in a deterministic order.

   ```sh
   sed -e 's#<coGeneration #<thinkTime ms="5"/><coGeneration #' -e 's# seed="11"##' \
     -e 's#K2 = \[K2\]#K2 = [k2]#' "$HOWTO/orders.xml" > "$HOWTO/d-three.xml"
   pherf -validate -disableSchemaApply -label three -scenarioFile "$HOWTO/d-three.xml" \
     2>"$HOWTO/three.log" | tail -1
   jq -r '.errors, (.findings[] | [.rule, (.line // "-"), .where] | @tsv)' \
     RESULTS/VALIDATION_three.json
   ```

   Captured output:

   ```text
   Validation: 3 error(s) in 1 file(s). Report: RESULTS/VALIDATION_three.json
   3
   xml.unknown-element	38	datamodel/scenarios/scenario[1]/thinkTime
   openloop.seed	-	scenario orders querySet 1
   predicate.casing	-	scenario orders querySet 1 query 1
   ```

   Each finding object in the JSON report contains `rule`, `file`, `line` (populated for structural
   rules only), `where`, and `message`. The top-level report specifies `format` "pherf-validation"
   and `version` 1; this schema version number increments only when an incompatible change would
   prevent an older consumer from parsing the report.

8. Waive unrecognized XML element and attribute names using `-allowUnknownTags`. This flag enables
   backward-compatible validation of scenarios authored for a newer version of Pherf. Each waived
   name is recorded in the diagnostic log as a WARN entry and omitted from the validation report,
   while all other validation rules remain strictly enforced.

   ```sh
   pherf -validate -allowUnknownTags -disableSchemaApply -label allow \
     -scenarioFile "$HOWTO/d-unknown.xml" 2>"$HOWTO/allow.log"
   echo "exit=$?"
   grep -c 'WARN .*-allowUnknownTags lets this pass: xml.unknown-element' "$HOWTO/allow.log"
   pherf -validate -allowUnknownTags -disableSchemaApply -label allow3 \
     -scenarioFile "$HOWTO/d-three.xml" 2>"$HOWTO/allow3.log" | tail -1
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_allow.json
   exit=0
   1
   Validation: 2 error(s) in 1 file(s). Report: RESULTS/VALIDATION_allow3.json
   ```

   Because the underlying XML parser still ignores the unrecognized element, a waived scenario
   executes without the functionality associated with that tag.

9. Validate a scenario authored for an older release of Pherf. Legacy Pherf configurations included
   a `release` attribute on the `<datamodel>` element; the current parser explicitly recognizes and
   ignores this attribute, so it is not flagged as an unknown name.

   ```sh
   sed 's#<datamodel name#<datamodel release="4.2" name#' "$HOWTO/orders.xml" \
     > "$HOWTO/old-release.xml"
   pherf -validate -disableSchemaApply -label release -scenarioFile "$HOWTO/old-release.xml" \
     2>"$HOWTO/release.log"
   ```

   Captured output:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_release.json
   ```

10. Observe the automatic pre-run validation gate. Every execution invoked with `-scenarioFile`
    (whether running `-l`, `-q`, `-mt`, or `-diff`) performs the full validation pass before taking
    any action. When a rule fails, the process terminates immediately with exit code 65 prior to
    dropping tables, applying schemas, loading data, or opening any database connection. The
    following command requests a table drop, data load, and query run against a nonexistent host,
    yet aborts cleanly in about 1 s.

    ```sh
    pherf -drop all -l -q -z nohost.invalid -disableSchemaApply -label gate \
      -scenarioFile "$HOWTO/d-seed.xml" 2>"$HOWTO/gate.log"
    echo "exit=$?"
    grep -c 'Validation failed with 1 error' "$HOWTO/gate.log"
    ```

    Captured output:

    ```text
    openloop.seed $HOWTO/d-seed.xml scenario orders querySet 1: openLoop of querySet 1 of ...
    Validation: 1 error(s) in 1 file(s). Report: RESULTS/VALIDATION_gate.json
    exit=65
    1
    ```

    When the pre-run gate succeeds, it writes nothing to standard output and generates no JSON
    report; instead, it records a single INFO log entry (`The scenario files pass the validation`),
    and the benchmark execution proceeds directly using the data models parsed during validation.

11. Verify that the pre-run gate validates the scenario files exactly as written on disk, whereas
    invoking `-validate` with launch overrides evaluates both the unmodified files and the
    overridden models. Because runtime launch overrides (`-threadCount`, `-runDurationMs`,
    `-targetRate`) are applied only after the pre-run gate completes, passing `-threadCount 2`
    does not repair an invalid concurrency range declared on an open loop, and the execution
    rejects the file:

    ```sh
    pherf -q -z nohost.invalid -threadCount 2 -disableSchemaApply -label range \
      -scenarioFile "$HOWTO/d-range.xml" >"$HOWTO/range.out" 2>"$HOWTO/range.log"
    echo "exit=$?"
    tail -1 "$HOWTO/range.out"
    ```

    Captured output:

    ```text
    exit=65
    Validation: 1 error(s) in 1 file(s). Report: RESULTS/VALIDATION_range.json
    ```

    Next, run offline validation with the same launch overrides. When launch overrides are
    supplied, `-validate` first evaluates all rules against the scenario files as written—matching
    the behavior of the pre-run gate—and then evaluates the rules a second time against the data
    models after applying the overrides. Any finding produced by both passes is deduplicated and
    reported once, ensuring that a clean `-validate` pass guarantees a clean pre-run gate.

    ```sh
    pherf -validate -q -threadCount 2 -disableSchemaApply -label range-v \
      -scenarioFile "$HOWTO/d-range.xml" 2>"$HOWTO/range-v.log"
    echo "exit=$?"
    ```

    Captured output (with the long line truncated at `...`):

    ```text
    openloop.rate $HOWTO/d-range.xml scenario orders querySet 1: openLoop of querySet 1 of ...
    Validation: 1 error(s) in 1 file(s). Report: RESULTS/VALIDATION_range-v.json
    exit=65
    ```

    This finding originates from the first pass against the unmodified file on disk. Once
    `-threadCount 2` is applied, the in-memory data models have a single integer worker count, so
    the second validation pass does not flag the rule.

    Conversely, a launch override can introduce a semantic defect that is not present in the static
    scenario files. Create a closed-loop variant of the valid baseline scenario by stripping its
    `openLoop` and `mix` elements, and then override its concurrency to 2000 threads against its
    1000-row dataset:

    ```sh
    sed -e '/<openLoop/,/<\/openLoop>/d' -e '/<mix>/,/<\/mix>/d' "$HOWTO/orders.xml" \
      > "$HOWTO/closed.xml"
    pherf -validate -disableSchemaApply -label closed -scenarioFile "$HOWTO/closed.xml" \
      2>/dev/null
    pherf -validate -q -threadCount 2000 -disableSchemaApply -label walk \
      -scenarioFile "$HOWTO/closed.xml" 2>/dev/null
    echo "exit=$?"
    ```

    Captured output (with long lines truncated at `...`):

    ```text
    Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_closed.json
    cogen.walk $HOWTO/closed.xml scenario orders querySet 1: SELECT: worker 1999 of 2000 has ...
    cogen.walk $HOWTO/closed.xml scenario orders querySet 1: SCAN: worker 1999 of 2000 has ...
    Validation: 2 error(s) in 1 file(s). Report: RESULTS/VALIDATION_walk.json
    exit=65
    ```

    Because the scenario file is valid as written, both standalone validation and the pre-run gate
    accept it; the pre-run gate cannot detect a defect that arises only after launch overrides are
    applied. Only running `-validate` with the exact launch overrides intended for execution exposes
    this error, so you should always validate using the full set of runtime overrides. Distributed
    Pherf enforces this automatically across a fleet: every agent validates its assigned share of
    the execution plan, and a validation finding on any agent terminates the entire fleet run with
    status `ABORTED` before any workload launches ([D01][d01]).

12. Verify that the validator applies `-rowCountOverride` using the same resolution logic as a live
    run. When overridden to 400 rows, scenario `orders` populates the range `[0, 400)` while
    `ordersB` populates `[500, 900)`, eliminating the key-range overlap between the two scenarios.

    ```sh
    pherf -validate -rowCountOverride 400 -disableSchemaApply -label over \
      -scenarioFile "$HOWTO/d-offsets.xml" 2>"$HOWTO/over.log"
    ```

    Captured output:

    ```text
    Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_over.json
    ```

13. Validate the bundled reference scenarios located under `config/reference`. Because each
    reference scenario references an external smoke profile in the same directory, each validation
    pass reads and checks 2 files.

    ```sh
    for s in relational json bson; do
      pherf -validate -disableSchemaApply -label "ref-$s" \
        -scenarioFile "$PHX_ROOT/phoenix-pherf/config/reference/${s}_scenario.xml" \
        2>>"$HOWTO/ref.log"
    done
    ```

    Captured output:

    ```text
    Validation: 0 error(s) in 2 file(s). Report: RESULTS/VALIDATION_ref-relational.json
    Validation: 0 error(s) in 2 file(s). Report: RESULTS/VALIDATION_ref-json.json
    Validation: 0 error(s) in 2 file(s). Report: RESULTS/VALIDATION_ref-bson.json
    ```

14. Supply DDL via an external schema file instead of disabling schema application. When neither
    `-disableSchemaApply` nor `-schemaFile` is specified, Pherf searches for resources matching
    `.*.sql` under `datamodel/` on its classpath. Because the `pherf` shell helper does not include
    such a classpath directory by default, the lookup fails with exit code 1.

    ```sh
    pherf -validate -label noschema -scenarioFile "$HOWTO/orders.xml" 2>"$HOWTO/noschema.log"
    echo "exit=$?"
    grep -m1 -o 'FileLoaderException: Could not load Schema Files' "$HOWTO/noschema.log"
    ```

    Captured output:

    ```text
    Trying to match resource pattern: .*.sql
    File resourceList Loaded: []
    exit=1
    FileLoaderException: Could not load Schema Files
    ```

    To provide a schema file, either specify its absolute path via `-schemaFile "$HOWTO/x.sql"` or
    place a directory containing `datamodel/` at the front of the classpath (`PHERF_CONF`) and pass
    a regular expression pattern. Each schema file holds a single SQL statement; only CREATE TABLE
    statements register a table schema for validation, and when multiple definitions exist, the
    first definition of a table takes precedence. Any file pattern that matches no files causes
    Pherf to terminate with exit code 1:

    ```sh
    pherf -validate -disableSchemaApply -label nomatch -scenarioFile '.*/nothing_.*\.xml' \
      2>"$HOWTO/nomatch.log"
    echo "exit=$?"
    ```

    Captured output:

    ```text
    exit=1
    ```

15. Integrate scenario validation into an automated pipeline check. The process exit code determines
    pass or fail status, while the JSON report provides structured findings for diagnostic
    reporting.

    ```sh
    if pherf -validate -disableSchemaApply -label ci -scenarioFile "$HOWTO/d-key.xml" \
        >/dev/null 2>"$HOWTO/ci.log"; then
      echo "scenario ok"
    else
      jq -r '.findings[] | "\(.rule): \(.where)"' RESULTS/VALIDATION_ci.json
    fi
    ```

    Captured output:

    ```text
    key.coverage: scenario orders querySet 1 query 1
    ```

## Read the result

- `0 error(s)` with exit code 0: The scenario satisfies every validation rule against the expected
  initial run state. A live execution can still fail due to runtime conditions observable only
  against the database, such as a missing table when no DDL is provided, or missing target rows when
  a query-only run without `-l` expects data from an earlier load.
- Exit code 65: Inspect each reported finding. Because downstream rules that depend on the output of
  an upstream rule are skipped when that prerequisite rule fails, a single root defect produces a
  single finding rather than cascading errors. Resolve the first finding reported for a file before
  re-running validation.
- Exit code 1: The command-line invocation is invalid, or a file pattern matched no scenario or
  schema files. The failure reason is recorded in the error log rather than in a validation report.
- A WARN log entry containing `-allowUnknownTags lets this pass`: The unrecognized XML element or
  attribute is ignored by the parser. Verify that the benchmark scenario does not rely on that
  setting for measurement validity.

## Reproduce it

Offline validation is strictly deterministic: supplying the same input files and command-line
options always produces the exact same findings in the same order. Archive the scenario files, the
command-line options, and the generated `VALIDATION_<label>.json` report alongside the benchmark
results of the run that consumed them. The JSON report explicitly records the full list of scenario
and schema files evaluated during the pass.

## Clean up

Because all steps ran offline, no cluster processes are running. Remove the temporary directory
created for this HOWTO when you no longer need the generated files:

```sh
rm -rf "$HOWTO"
```

## See also

- [README.md](../../README.md), Validation section: Reference documentation covering every
  validation rule id and the pre-run gate behavior.
- [HOWTO 03](03-open-loop-profiles-and-mixes.md): Detailed guide to open-loop workloads, external
  profiles, and operation mixes validated by the `openloop.*` and `xml.profile` rules.
- [HOWTO 04](04-co-generation.md): Detailed guide to deterministic co-generation validated by the
  `cogen.*`, `key.coverage`, and `predicate.*` rules.
- [Harness HOWTO 04](../../../dev/harness/docs/howto/04-pherf-through-the-harness.md): Guide to
  executing the pre-run validation gate through the test harness, where a gate failure produces the
  verdict row `pherf.gate FAIL`.

[d01]: ../../../phoenix-pherf-distributed/docs/howto/01-standalone-fleet-and-rest-api.md
