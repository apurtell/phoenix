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

Pherf is a performance test framework that exercises HBase through Apache Phoenix, a SQL layer interface.

## Build
mvn clean package -DskipTests

## Important arguments:

- -h _Help_ <br />
- -l _Apply schema and load data_<br/>
- -q _Executes Multi-threaded query sets and write results_<br/>
- -z [quorum] _Zookeeper quorum_</br>
- -m _Enable monitor for statistics_<br/>
- -monitorFrequency [frequency in Ms] _Frequency at which the monitor will snopshot stats to log file. <br/>
- -drop _Regex drop all tables with schema name as PHERF. Example drop Event tables: -drop .*(EVENT).* Drop all: -drop .* or -drop all_<br/>
- -scenarioFile _Regex or file name of a specific scenario file to run._ <br />
- -schemaFile _Regex or file name of a specific schema file to run._ <br />
- -export Exports query results to CSV files in CSV_EXPORT directory <br />
- -diff Compares results with previously exported results <br />
- -hint _Executes all queries with specified hint. Example SMALL_ <br />
- -rowCountOverride
- -rowCountOverride [number of rows] _Specify number of rows to be upserted rather than using row count specified in schema_ </br>
- -connectionCache _Reuses connections through a cache holding at most pherf.default.threadpool
  idle connections. Each connection serves only operations with the same URL and connection
  properties (and therefore the same tenant and connection properties), allowing query sets and DDL
  statements with matching properties to share connections. The cache does not reset session state
  modified by SQL (such as ALTER SESSION), which persists for the lifetime of the cached
  connection._ <br />

## Running from IDE
Ex. Load data and execute queries. Specify the following as your IDE debug arguments:<br/>
`-drop -l -q -z localhost`

## Running from command line
Ex. Drop existing tables, load data, and execute queries:<br/>
`java -jar pherf-1.0-SNAPSHOT-jar-with-dependencies.jar -drop -l -q -z localhost`

## Adding Rules for Data Creation
Review [test_scenario.xml](/src/test/resources/scenario/test_scenario.xml)
for syntax examples.<br />

* Rules are defined as `<columns />` and are applied in the order they appear in file.
* Rules of the same type override the values of a prior rule of the same type. If `<userDefined>true</userDefined>` is
set, rule will only
apply override when type and name match the column name in Phoenix.
* `<prefix>` tag is set at the column level. It can be used to define a constant string appended to the beginning of
CHAR and VARCHAR data type values.
* **Required field** Supported Phoenix types: VARCHAR, CHAR, DATE, DECIMAL, INTEGER
    * denoted by the `<type>` tag
* User defined true changes rule matching to use both name and type fields to determine equivalence.
    * Default is false if not specified and equivalence will be determined by type only. **An important note here is that you can still override rules without the user defined flag, but they will change the rule globally and not just for a specified column.**
* **Required field** Supported Data Sequences
    * RANDOM:       Random value which can be bound by other fields such as length.
    * SEQUENTIAL:   Monotonically increasing long prepended to random strings.
        * Only supported on VARCHAR and CHAR types
    * LIST:         Means pick values from predefined list of values
* **Required field** Length defines boundary for random values for CHAR and VARCHAR types.
    * denoted by the `<length>` tag
* Column level Min/Max value defines boundaries for numerical values. For DATES, these values supply a range between
which values are generated. At the column level the granularity is a year. At a specific data value level, the
granularity is down to the Ms.
    * denoted by the `<minValue>` tag
    * denoted by the `<maxValue>` tag
* Null chance denotes the probability of generating a null value. From \[0-100\]. The higher the number, the more likely
the value will be null.
    * denoted by `<nullChance>`
* Name can either be any text or the actual column name in the Phoenix table.
    * denoted by the `<name>`
* Value List is used in conjunction with LIST data sequences. Each entry is a DataValue with a specified value to be
used when generating data.
    * Denoted by the `<valueList><datavalue><value/></datavalue></valueList>` tags
    * If the distribution attribute on the datavalue is set, values will be created according to
that probability.
    * When distribution is used, values must add up to 100%.
    * If distribution is not used, values will be randomly picked from the list with equal distribution.

## Defining Scenario
Scenario can have multiple querySets. Consider following example, concurrency of 1-4 means that each query will be
executed starting with concurrency level of 1 and reach up to maximum concurrency of 4. Per thread, query would be
executed to a minimum of 10 times or 10 seconds (whichever comes first). QuerySet by defult is executed serially but you
 can change executionType to PARALLEL so queries are executed concurrently. Scenarios are defined in XMLs stored
 in the resource directory. The optional warmupExecutions attribute specifies how many warmup
 executions of each query each thread runs prior to the measured executions. Warmup executions are
 recorded exclusively under the "warmup" latency phase (see [LATENCY.md](LATENCY.md)) and are
 excluded from the CSV and XML results. When this attribute is omitted or set to 0, no warmup is
 performed.

```

<scenarios>
    <!--Minimum of executionDurationInMs or numberOfExecutions. Which ever is reached first -->
    <querySet concurrency="1-4" executionType="PARALLEL" executionDurationInMs="10000" numberOfExecutions="10">
        <query id="q1" verifyRowCount="false" statement="select count(*) from PHERF.TEST_TABLE"/>
        <query id="q2" tenantId="1234567890" ddl="create view if not exists
        myview(mypk varchar not null primary key, mycol varchar)" statement="upsert select ..."/>
    </querySet>
    <querySet concurrency="3" executionType="SERIAL" executionDurationInMs="20000" numberOfExecutions="100"
              warmupExecutions="10">
        <query id="q3" verifyRowCount="false" statement="select count(*) from PHERF.TEST_TABLE"/>
        <query id="q4" statement="select count(*) from PHERF.TEST_TABLE WHERE TENANT_ID='00D000000000062'"/>
    </querySet>
</scenario>

```

### Open loop query sets
Configuring a query set with an `openLoop` element dispatches its queries according to a target
arrival rate schedule rather than running them in a closed loop. The scheduler enqueues each
operation at its intended dispatch timestamp, while a pool of `concurrency` worker threads pulls
operations from the queue for execution. The configured concurrency must be a single integer value
(rather than a range) and also defines the maximum capacity of the dispatch queue. Successive
arrivals cycle through the queries of the query set in their declared order. Whenever worker threads
cannot keep pace with the arrival rate, queued operations accumulate scheduling lag; the latency
report separately captures both the database service time (under key `query`) and the end-to-end
response time measured from the intended dispatch timestamp (under key `response`), preventing a
slow cluster from masking client-side queueing delay. Refer to [LATENCY.md](LATENCY.md) for full
details on latency keys and the `schedules` report entry generated for each open-loop query set.

```

<querySet concurrency="8" warmupExecutions="2">
    <openLoop arrival="POISSON" seed="7" maxLagMs="50" lagPolicy="DROP">
        <stage durationMs="10000" rate="100"/>
        <stage durationMs="10000" rate="100" endRate="400"/>
        <stage durationMs="10000" rate="400"/>
    </openLoop>
    <query id="q5" statement="select count(*) from PHERF.TEST_TABLE"/>
</querySet>

```

* `stage`: defines an interval using `durationMs` and an initial target `rate` (in operations per
  second at the beginning of the stage). Specifying `endRate` causes the arrival rate to interpolate
  linearly to `endRate` across the stage duration, whereas omitting it maintains a flat rate.
  Setting the rate to 0 introduces a pause. A single flat stage produces a constant arrival rate,
  multiple flat stages produce a stepped profile, and configuring `endRate` produces a linear ramp.
* `arrival`: selects the arrival distribution. `UNIFORM` (the default) spaces arrivals at equal
  intervals under a constant rate, while `POISSON` generates a Poisson arrival process over the
  configured rate curve and requires `seed` so that a given seed reproduces identical arrival
  timestamps across runs.
* `maxLagMs`: specifies the maximum tolerated scheduling lag, where omitting the attribute imposes
  no limit. `lagPolicy`: controls how overdue operations are handled; `LATE` (the default) executes
  an overdue operation and increments the late counter, whereas `DROP` skips execution, counts the
  operation as dropped, and requires `maxLagMs` to be configured.
* `warmupExecutions` causes each worker thread to execute the specified number of warmup runs of
  every query in the set before the open-loop schedule begins.
* `executionType`, `numberOfExecutions`, and `executionDurationInMs` have no effect on open-loop
  execution because the configured stages determine the overall schedule duration. Once the schedule
  window concludes, the remaining queue drains and all in-flight operations complete.
* Only standard performance runs honor `openLoop`, whereas `-diff` and `-export` invocations ignore
  it. Every executed operation contributes a single row recording its service time to the legacy CSV
  and XML result files.
* When running with `-connectionCache`, configure `pherf.default.threadpool` to be at least as large
  as the open-loop concurrency, since the connection cache retains at most that many idle
  connections.
* Because multiple worker threads draw dynamic placeholder values (`[COL]`) concurrently, the exact
  sequence of substituted values varies across runs.

### Co-generation
Configuring a scenario with a `coGeneration` element computes every column value as a deterministic
pure function of the row index, the column rule, and the seed. Writer threads claim disjoint blocks
of row indexes while readers sample exclusively from committed index ranges, enabling queries to
read keys produced by writers running in another thread, JVM, or process without maintaining shared
key state. Scenarios that omit this element continue to use the standard rule-based generators
unchanged; no other configuration switch is required.

```

<scenario tableName="PHERF.COGEN" rowCount="100000" name="lookups">
    <coGeneration seed="7" offset="0" blockSize="1000" clusterLimit="4" watermark="COMMIT" walk="MULTIPLICATIVE"/>
    <querySet concurrency="4" executionType="SERIAL" numberOfExecutions="1000">
        <query id="get" statement="SELECT V FROM PHERF.COGEN WHERE K1 = [K1] AND K2 = [K2]"/>
    </querySet>
</scenario>

```

Every attribute on the element is optional. Supplying a value that fails to parse, such as
`walk="SEQUENTAIL"`, aborts the run with an error message identifying the scenario, the attribute,
and the invalid value:

* `seed` (default 0): specifies the seed governing both column value generation and the read
  traversal order.
* `offset` (default 0, minimum 0): defines the starting row index for the current process. The
  effective row index for any generated value equals this offset plus the process-local index,
  which begins at 0 in each process. Separate processes generate disjoint keys only when their
  offsets are separated by at least the total number of rows written per process (including rows
  written by `writeParams` writers) and the primary key includes a column that yields distinct
  values for every row index across the two ranges. Pherf does not enforce or validate this
  requirement; for example, a SEQUENTIAL VARCHAR column wraps and repeats after 10 to the power of
  its unprefixed length (its length without the prefix).
* `blockSize` (default 1000, minimum 1): specifies the number of rows in a single writer block.
  Each block constitutes a single commit batch and never crosses an integer multiple of
  `blockSize`. The execution duration limit configured on `writeParams` is evaluated at block
  boundaries.
* `clusterLimit` (omitted for no clustering, otherwise at least 1): caps the maximum number of
  distinct values generated for the leading primary key column within each block. Pherf determines
  the leading column from the primary key metadata. Specifying a value smaller than `blockSize` on
  a single-column primary key fails validation. In addition, the primary key must contain a
  subsequent column that produces a unique value for each row index (such as a SEQUENTIAL column);
  otherwise, generated rows will collide.
* `watermark`: controls when written rows become visible to readers. `COMMIT` (the default) exposes
  each block immediately after it is committed, whereas `LOAD` defers visibility until all writers
  in the data load phase have completed.
* `walk`: selects the order in which readers traverse committed rows. `MULTIPLICATIVE` (the
  default) visits committed rows in a hashed sequence using a stride coprime to the row count so
  that each pass visits every row exactly once before repeating any row, whereas `SEQUENTIAL`
  traverses rows in ascending index order.

Column rules retain their standard semantics, subject to the following differences from the
traditional rule-based generators:

* SEQUENTIAL derives each value directly from the row index within the rule's sequence and is
  additionally supported for BIGINT and UNSIGNED_LONG columns. SEQUENTIAL DATE and TIMESTAMP rules
  begin at 1970-01-01 00:00:00.000 and advance by one second per row.
* LIST selects entries according to their declared distribution weights, RANDOM honors the
  configured numeric bounds, length, and prefix of the rule, and `nullChance` preserves its
  configured null probability.
* `useCurrentDate` cannot be expressed as a pure function of the row index, so any column rule or
  list entry that enables it is rejected.
* A column rule may specify a custom generator class via `<generatorClass>`, referencing a class
  that implements `org.apache.phoenix.pherf.rules.RuleBasedDataGenerator` and provides a public
  single-argument constructor accepting a `Column`. Both the traditional and co-generation paths
  instantiate this class. Under co-generation, if the class also implements
  `org.apache.phoenix.pherf.rules.IndexedDataGenerator`, values are computed deterministically via
  `valueAt(index, seed)`; otherwise, the generator falls back to its standard value generation,
  whose results are not a pure function of the row index.

Read and write coordination behavior:

* A data load (`-l`) writes rows starting at index 0. An execution invoked without a data load
  (such as `-q` by itself) reads across the `rowCount` rows that a data load of the same scenario
  would produce. Because this range is assumed rather than observed from live commits, the prior
  load must have run to completion with identical settings and `rowCount`; otherwise, point lookups
  return no matching rows without reporting an error, and the validator must be used to confirm the
  results. Configuring `rowCount="0"` in such a run leaves no rows available to read, causing every
  read to fail. Concurrent writers configured via `writeParams` append new rows at indexes above
  that initial range.
* All placeholders within a single statement are resolved from the same row index, ensuring that a
  point query receives a complete, consistent primary key for one row. A statement draws a row
  index only when it contains at least one placeholder associated with a column rule.
* Under closed-loop execution, thread i at concurrency level C reads exclusively from row indexes
  matching i mod C, so concurrent reader threads access disjoint sets of rows. Under open-loop
  execution, worker threads share a single traversal sequence across the query set: a complete pass
  still visits each row once, but the specific row bound to a given arrival depends on worker
  scheduling order.
* Report explain plan generation, `-diff`, and `-export` continue to use the rule-based generators.
  The load profile execution path (`-mt`) rejects scenarios configured with `coGeneration`.
* Each value substituted into a statement is rendered as a SQL literal matching the exact value
  bound by the writer. CHAR and VARCHAR values are enclosed in quotes, DATE and TIMESTAMP values
  are formatted as typed UTC literals with millisecond precision
  (`TIMESTAMP '2000-01-01 00:00:00.123'`), VARBINARY and VARBINARY_ENCODED values are formatted as
  hexadecimal literals (`X'0aff'`), and numeric values are emitted without quotes. DECIMAL values
  and array elements use a canonical decimal string representation that does not vary across JDK
  versions.
* Every scenario configured with `coGeneration` must have a unique scenario name across all data
  models included in the run.

### SCAN and DELETE operations
Configuring a query with `kind="SCAN"` reads the rows falling between two bounds, while configuring
`kind="DELETE"` removes the rows within those bounds. A query that omits this attribute or specifies
`kind="SELECT"` continues to execute as a point query. Supplying any other value aborts the run
before any query set starts, reporting the scenario, the query, and the invalid value in the error
message. The query statement must reference its range column placeholder twice: the first occurrence
specifies the lower bound and the second specifies the upper bound.

```

<querySet concurrency="1" executionType="SERIAL" numberOfExecutions="10">
    <query id="scan" kind="SCAN" statement="SELECT V FROM PHERF.T WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
    <query id="delete" kind="DELETE" statement="DELETE FROM PHERF.T WHERE K1 &gt;= [K1] AND K1 &lt;= [K1]"/>
</querySet>

```

* Under standard rule-based generation, both occurrences of the range placeholder receive the same
  generated value from the rule, so a SCAN reads and a DELETE removes the rows matching a single
  leading key value. Every other placeholder in the statement appears once.
* With `coGeneration`, each statement draws a single block representing an aligned window of
  `blockSize` row indexes, or the remaining rows in the final window. The lower bound is set to the
  value of the block's first row and the upper bound to the value of its last row, ensuring that the
  bounds enclose the exact rows of the block, including when `clusterLimit` is active. The range
  column must be the sole placeholder backed by a rule: any additional rule-backed placeholder would
  introduce a filter whose value varies within the block, causing the statement to fail before
  drawing a block. Furthermore, the range column must be the leading primary key column so that the
  bounds define a key-range scan; specifying any other column fails the statement with an error
  message naming the scenario and both columns. The column rule must also generate values that
  increase monotonically with the row index: either a SEQUENTIAL TINYINT, INTEGER, BIGINT, or
  UNSIGNED_LONG rule that does not wrap across the written rows (the row count, or the highest index
  claimed by `writeParams` writers when greater, plus the offset, must not exceed
  `maxValue - minValue + 1`), or a SEQUENTIAL DATE or TIMESTAMP rule, with no `nullChance`. Any
  other rule, such as SEQUENTIAL VARCHAR whose lexical text order diverges from the row index order,
  causes the statement to fail before drawing a block.
* A SCAN selects a complete committed block along its own traversal walk across the blocks. A DELETE
  claims the next committed block in ascending index order; deleter threads share this sequence, and
  a block is eligible for deletion only after all of its rows are committed. If no committed block
  remains, the DELETE fails. Because deleted rows always form a prefix of the index space, point
  reads and scans draw exclusively from rows above that prefix. A read that draws its target before
  a DELETE claims the corresponding block can execute concurrently with that DELETE, whereas any
  read that draws afterward never selects those rows. A failed DELETE leaves its rows in the table,
  and those rows are not read again during the run. Because a run containing DELETE operations
  removes rows from the underlying table, a subsequent run executed without a data load will find
  those rows missing; reload the data (`-l`) before the next run.
* A SCAN records its returned row count in the same manner as a standard query. A DELETE reports the
  number of rows it removes as the row count in the legacy reports and commits the transaction.
* The load profile execution path (`-mt`) runs SCAN and DELETE operations using rule-based
  generation, while query operations that omit the attribute continue to execute their statement as
  written.

## Results
Results are written real time in _results_ directory. Open the result that is saved in .jpg format for real time
visualization.

## Testing
Default quorum is localhost. If you want to override set the system variable.

Run unit tests: `mvn test -DZK_QUORUM=localhost`<br />
Run a specific method: `mvn -Dtest=ClassName#methodName test` <br />
To test: `./pherf-standalone.py -drop all -l -q -z localhost -schemaFile .*user_defined_schema.sql -scenarioFile .*user_defined_scenario.xml`

More to come...
