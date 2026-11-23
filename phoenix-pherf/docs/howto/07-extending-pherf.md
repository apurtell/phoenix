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

# How to extend Pherf with providers, report sections, metrics sinks and generators

## The question

How can you customize how Pherf establishes connections, inject custom metrics into its report,
forward its runtime metrics to an external monitoring system, and synthesize column values beyond
what its built-in rules support, all without modifying Pherf? This HOWTO develops one
implementation for each extension point, packages them into one jar, verifies their service
registration offline, and executes a scenario using them. It also outlines the required test bar
for contributing changes to Pherf itself.

## Before you start

**Audience.** This guide is intended for users integrating Pherf with their own infrastructure and
for contributors developing Pherf.

**Prerequisites.**

- Complete steps 2 and 3 of [HOWTO 01](01-first-pherf-run.md) to produce the build and the
  environment script `$PX_OUT/howto/pherf-env.sh`, and ensure that `javac` and `jar` from the same
  JDK 17 installation are available.
- For the cluster execution step only, provide a running cluster and its ZooKeeper quorum as
  described in HOWTO 01.

| Cost | Value |
|---|---|
| Wall time | Offline steps take about 5 minutes; the run step takes about 1 minute on a cluster. |
| Memory | The Pherf JVM on the host uses a heap of up to 3 GiB. |
| Disk | The artifacts require under 1 MiB of disk space. |
| Files written | `$HOWTO/ext/` (sources, classes, `howto-pherf-ext.jar`), `$HOWTO/codes_scenario.xml`, `$HOWTO/work/RESULTS*`, the table `PHERF.HOWTO_CODES` |

The offline steps—compiling, packaging, verifying service registration, validating the scenario,
and probing the generator—were executed directly, and their output excerpts are captured below.
The cluster run step was verified against the codebase rather than executed on a live cluster for
this HOWTO, so its output excerpts are marked as "expected".

## Method

**The extension points.** Pherf discovers its extensions through `java.util.ServiceLoader`. An
extension jar registers an implementation by bundling a descriptor file under `META-INF/services/`
named after the service interface and containing the fully qualified implementation class name.
Extension discovery requires no command-line option and no system property.

| Interface (`org.apache.phoenix.pherf.spi`) | Count | Called | What it gives |
|---|---|---|---|
| `ConnectionProvider` | at most 1 | for each connection that Pherf opens, with the URL and the final properties | the connection: a pool, an authentication wrapper, a tracing wrapper |
| `ReportSection` | any | On data model XML report write. | Adds `NAME.KEY` entries to `sections`. |
| `MetricsSink` | any | Runs on each record Pherf reports. | Sends values to an external backend. |

Data generation uses explicit scenario configuration rather than service discovery: a column rule
specifies a custom generator class via `<generatorClass>`. The class must implement
`org.apache.phoenix.pherf.rules.RuleBasedDataGenerator` and provide a public constructor accepting
one `Column` argument. Under co-generation, a generator class that also implements
`org.apache.phoenix.pherf.rules.IndexedDataGenerator` implements `valueAt(index, seed)` so that a
reader can deterministically compute the exact value that a writer produced
([HOWTO 04](04-co-generation.md)). A generator class that does not implement this interface
maintains its own value sequence, which is therefore not a function of the row index.

**The metrics.** Each reported record emits two metrics, `<kind>.elapsed_ms` and `<kind>.rows`:

| Kind | One record | Tags |
|---|---|---|
| `query` | one execution of a query, closed or open loop (the service time) | `scenario`, `query`, `tenant`, `outcome` (`ok`, `timeout` or `error`) |
| `load` | Records one row of the data load summary for a load or a scenario DDL. | `table` |
| `operation` | one `-mt` operation | `scenario`, `operation`, `type`, `tenant`, `tenant_group`, `status` |

Any tag without an assigned value is omitted from the tag map. Warmup executions do not emit
metrics.

**Failure rules.**

- Registering more than one `ConnectionProvider` results in an error when Pherf opens its first
  connection.
- Any exception thrown by the connection provider propagates directly to the caller, causing that
  operation to fail.
- A `RuntimeException` thrown by a report section or a metrics sink logs a WARN message, and the
  benchmark run continues.
- A service registration that fails to load—whether due to a missing class, the absence of a
  public no-argument constructor, or a constructor that throws an exception—is latched as a load
  failure. Every subsequent attempt to use that interface throws an `IllegalStateException` naming
  the interface, causing the run to fail immediately upon first use.

**The connection cache.** When `-connectionCache` is enabled, the cache sits in front of the
registered connection provider. Consequently, the provider is invoked only to open connections
that are not already present in the cache. Because a cache lease is a proxy wrapping a cached
connection, a provider that wraps its connections sees those wrappers reused across leases. A
provider that counts its invocations therefore counts actual physical connection opens, directly
measuring cache effectiveness.

**Cost in the measured path.** A metrics sink executes on the operation thread immediately after
the operation finishes. In closed-loop mode, the sink executes inside the lock that serializes all
query executions ([HOWTO 01](01-first-pherf-run.md), Method), so a slow sink directly reduces the
throughput of the entire run. In open-loop mode, a slow sink delays the worker thread and
introduces scheduling lag. A sink must therefore never block; it should transmit packets over UDP
or enqueue values for asynchronous draining by a background thread. A report section executes each
time the report is written—which occurs approximately once a second in closed-loop mode—so its
evaluation must also be lightweight.

**Hypothesis of the run step.** With the extension jar on the class path, the generated XML report
includes the custom section entries, the metrics sink receives one `query.elapsed_ms` metric and
one `query.rows` metric for each measured execution, and the connection provider records fewer
connection opens when `-connectionCache` is enabled than when it is omitted.

## Steps

1. Execute the setup commands from the root of the repository to initialize the working
   directories.

   ```sh
   export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
   HOWTO="$PX_OUT/howto/p07"; mkdir -p "$HOWTO/work"
   . "$PX_OUT/howto/pherf-env.sh"
   mkdir -p "$HOWTO/ext/src/howto/pherf" "$HOWTO/ext/classes/META-INF/services"
   ```

2. Implement a custom connection provider that counts the connections it opens and delegates
   connection creation to `ConnectionProvider.DEFAULT`, which uses `DriverManager`.

   ```sh
   cat > "$HOWTO/ext/src/howto/pherf/CountingProvider.java" <<'EOF'
   package howto.pherf;

   import java.sql.Connection;
   import java.sql.SQLException;
   import java.util.Properties;
   import java.util.concurrent.atomic.AtomicLong;
   import org.apache.phoenix.pherf.spi.ConnectionProvider;

   /** Opens each Pherf connection through DriverManager and counts the opens. */
   public class CountingProvider implements ConnectionProvider {
     static final AtomicLong OPENS = new AtomicLong();

     @Override
     public Connection getConnection(String url, Properties props) throws SQLException {
       OPENS.incrementAndGet();
       return ConnectionProvider.DEFAULT.getConnection(url, props);
     }
   }
   EOF
   ```

3. Implement a custom report section whose entries are written to the XML report as
   `howto.connectionOpens` and `howto.scenarios`.

   ```sh
   cat > "$HOWTO/ext/src/howto/pherf/OpensSection.java" <<'EOF'
   package howto.pherf;

   import java.util.LinkedHashMap;
   import java.util.Map;
   import org.apache.phoenix.pherf.result.DataModelResult;
   import org.apache.phoenix.pherf.spi.ReportSection;

   /** Adds the connection open count to the XML report as howto.connectionOpens. */
   public class OpensSection implements ReportSection {
     @Override
     public String getName() {
       return "howto";
     }

     @Override
     public Map<String, String> getEntries(DataModelResult result) {
       Map<String, String> entries = new LinkedHashMap<>();
       entries.put("connectionOpens", Long.toString(CountingProvider.OPENS.get()));
       entries.put("scenarios", Integer.toString(result.getScenarioResult().size()));
       return entries;
     }
   }
   EOF
   ```

4. Implement a metrics sink that transmits StatsD lines over UDP. Using UDP avoids blocking the
   worker thread, and a dropped packet does not halt the benchmark run. The system properties
   `howto.statsd.host` and `howto.statsd.port` configure the target endpoint (defaulting to
   `127.0.0.1:8125`).

   ```sh
   cat > "$HOWTO/ext/src/howto/pherf/StatsdSink.java" <<'EOF'
   package howto.pherf;

   import java.io.IOException;
   import java.net.DatagramPacket;
   import java.net.DatagramSocket;
   import java.net.InetSocketAddress;
   import java.net.SocketException;
   import java.nio.charset.StandardCharsets;
   import java.util.Map;
   import org.apache.phoenix.pherf.spi.MetricsSink;

   /**
    * Sends each Pherf metric as one StatsD line over UDP: name,tag=value,...:value|ms for
    * elapsed_ms, |c for rows. UDP does not block the worker that records the metric.
    */
   public class StatsdSink implements MetricsSink {
     private final InetSocketAddress target = new InetSocketAddress(
       System.getProperty("howto.statsd.host", "127.0.0.1"),
       Integer.getInteger("howto.statsd.port", 8125));
     private final DatagramSocket socket;

     public StatsdSink() throws SocketException {
       socket = new DatagramSocket();
     }

     @Override
     public void record(String name, Map<String, String> tags, long value) {
       StringBuilder line = new StringBuilder("pherf.").append(name);
       for (Map.Entry<String, String> tag : tags.entrySet()) {
         line.append(',').append(tag.getKey()).append('=').append(tag.getValue());
       }
       line.append(':').append(value).append(name.endsWith(".rows") ? "|c" : "|ms");
       byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
       try {
         socket.send(new DatagramPacket(bytes, bytes.length, target));
       } catch (IOException e) {
         // A lost metric must not stop the run.
       }
     }
   }
   EOF
   ```

5. Implement a custom data generator class that formats order codes such as `ORD-0000000042`.
   Under co-generation, its `valueAt` method deterministically computes the order code for a given
   row index.

   ```sh
   cat > "$HOWTO/ext/src/howto/pherf/OrderCodeGenerator.java" <<'EOF'
   package howto.pherf;

   import java.util.concurrent.atomic.AtomicLong;
   import org.apache.phoenix.pherf.configuration.Column;
   import org.apache.phoenix.pherf.rules.DataValue;
   import org.apache.phoenix.pherf.rules.IndexedDataGenerator;
   import org.apache.phoenix.pherf.rules.RuleBasedDataGenerator;

   /**
    * Makes order codes such as ORD-0000000042. The rule based generators call getDataValue; under
    * co-generation valueAt gives the code of a row index, so a reader can compute what a writer
    * wrote.
    */
   public class OrderCodeGenerator implements RuleBasedDataGenerator, IndexedDataGenerator {
     private final AtomicLong next = new AtomicLong();
     private final Column rule;

     public OrderCodeGenerator(Column rule) {
       this.rule = rule;
     }

     @Override
     public DataValue getDataValue() {
       return code(next.getAndIncrement());
     }

     @Override
     public DataValue valueAt(long index, long seed) {
       return code(index);
     }

     private DataValue code(long index) {
       return new DataValue(rule.getType(), String.format("ORD-%010d", index));
     }
   }
   EOF
   ```

6. Write a small verification program that lists the service implementations discovered by
   `ServiceLoader` in the same manner that Pherf discovers them.

   ```sh
   cat > "$HOWTO/ext/src/howto/pherf/ListExtensions.java" <<'EOF'
   package howto.pherf;

   import java.util.ServiceLoader;
   import org.apache.phoenix.pherf.spi.ConnectionProvider;
   import org.apache.phoenix.pherf.spi.MetricsSink;
   import org.apache.phoenix.pherf.spi.ReportSection;

   /** Prints the extensions that ServiceLoader finds on the class path, as Pherf finds them. */
   public class ListExtensions {
     public static void main(String[] args) {
       for (Class<?> type : new Class<?>[] { ConnectionProvider.class, ReportSection.class,
         MetricsSink.class }) {
         for (Object found : ServiceLoader.load(type)) {
           System.out.println(type.getSimpleName() + " " + found.getClass().getName());
         }
       }
     }
   }
   EOF
   ```

7. Register the three services, compile the sources against the Pherf class path, and package the
   compiled classes into a jar.

   ```sh
   cd "$HOWTO/ext"
   S=classes/META-INF/services
   echo howto.pherf.CountingProvider > "$S/org.apache.phoenix.pherf.spi.ConnectionProvider"
   echo howto.pherf.OpensSection > "$S/org.apache.phoenix.pherf.spi.ReportSection"
   echo howto.pherf.StatsdSink > "$S/org.apache.phoenix.pherf.spi.MetricsSink"
   "$JAVA_HOME/bin/javac" --release 11 -cp "$PHERF_CP" -d classes src/howto/pherf/*.java
   "$JAVA_HOME/bin/jar" --create --file howto-pherf-ext.jar -C classes .
   "$JAVA_HOME/bin/jar" --list --file howto-pherf-ext.jar | grep -v '/$'
   ```

   Captured:

   ```text
   META-INF/MANIFEST.MF
   META-INF/services/org.apache.phoenix.pherf.spi.ConnectionProvider
   META-INF/services/org.apache.phoenix.pherf.spi.MetricsSink
   META-INF/services/org.apache.phoenix.pherf.spi.ReportSection
   howto/pherf/CountingProvider.class
   howto/pherf/ListExtensions.class
   howto/pherf/OpensSection.class
   howto/pherf/OrderCodeGenerator.class
   howto/pherf/StatsdSink.class
   ```

   Passing `--release 11` produces class files compatible with JDK 11 and later versions; select
   the release level matching the oldest JDK used to run your Pherf deployment. Classes contributed
   to Pherf itself must compile at the Java 8 source level, as covered in the final step.

8. Verify the service registrations offline.

   ```sh
   "$JAVA_HOME/bin/java" -cp "$PHERF_CP:$HOWTO/ext/howto-pherf-ext.jar" \
     howto.pherf.ListExtensions 2>/dev/null
   ```

   Captured:

   ```text
   ConnectionProvider howto.pherf.CountingProvider
   ReportSection howto.pherf.OpensSection
   MetricsSink howto.pherf.StatsdSink
   ```

9. Create a co-generated scenario file in which the `CODE` column uses the custom generator class,
   and validate the scenario offline. Because the validator does not load generator classes during
   validation, this step does not require the extension jar on the class path.

   ```sh
   cat > "$HOWTO/codes_scenario.xml" <<'EOF'
   <?xml version="1.0" encoding="UTF-8"?>
   <datamodel name="howto_codes">
     <datamapping>
       <column>
         <type>BIGINT</type>
         <userDefined>true</userDefined>
         <dataSequence>SEQUENTIAL</dataSequence>
         <minValue>0</minValue>
         <maxValue>1000000000</maxValue>
         <name>K</name>
       </column>
       <!-- The generator class makes every value of this column. -->
       <column>
         <type>VARCHAR</type>
         <userDefined>true</userDefined>
         <name>CODE</name>
         <generatorClass>howto.pherf.OrderCodeGenerator</generatorClass>
       </column>
     </datamapping>
     <scenarios>
       <scenario tableName="PHERF.HOWTO_CODES" rowCount="10000" name="codes">
         <preScenarioDdls>
           <ddl tableName="PHERF.HOWTO_CODES"
                statement="CREATE TABLE IF NOT EXISTS ? (K BIGINT NOT NULL PRIMARY KEY,
                  CODE VARCHAR)"/>
         </preScenarioDdls>
         <coGeneration seed="5" blockSize="500"/>
         <querySet concurrency="2" warmupExecutions="10">
           <openLoop>
             <stage durationMs="30000" rate="50"/>
           </openLoop>
           <query id="byKey" statement="SELECT CODE FROM PHERF.HOWTO_CODES WHERE K = [K]"/>
         </querySet>
       </scenario>
     </scenarios>
   </datamodel>
   EOF
   cd "$HOWTO/work"
   pherf -validate -disableSchemaApply -label codes -scenarioFile "$HOWTO/codes_scenario.xml" \
     2>"$HOWTO/codes-validate.log"
   ```

   Captured:

   ```text
   Validation: 0 error(s) in 1 file(s). Report: RESULTS/VALIDATION_codes.json
   ```

10. Verify the generator class offline. Pherf instantiates custom generators via
    `RulesApplier.newGenerator(column)`; this probe program invokes that factory method for every
    column rule configured with a generator class.

    ```sh
    mkdir -p "$HOWTO/ext/probe"
    cat > "$HOWTO/ext/probe/Probe.java" <<'EOF'
    import org.apache.phoenix.pherf.configuration.Column;
    import org.apache.phoenix.pherf.configuration.XMLConfigParser;
    import org.apache.phoenix.pherf.rules.IndexedDataGenerator;
    import org.apache.phoenix.pherf.rules.RuleBasedDataGenerator;
    import org.apache.phoenix.pherf.rules.RulesApplier;

    public class Probe {
      public static void main(String[] args) throws Exception {
        XMLConfigParser parser = new XMLConfigParser(args[0]);
        for (Column c : parser.getDataModels().get(0).getDataMappingColumns()) {
          if (c.getGeneratorClass() != null) {
            RuleBasedDataGenerator g = RulesApplier.newGenerator(c);
            System.out.println(c.getName() + " " + g.getDataValue().getValue() + " "
              + g.getDataValue().getValue() + " "
              + ((IndexedDataGenerator) g).valueAt(42, 5).getValue());
          }
        }
      }
    }
    EOF
    "$JAVA_HOME/bin/javac" -cp "$PHERF_CP" -d "$HOWTO/ext/probe" "$HOWTO/ext/probe/Probe.java"
    "$JAVA_HOME/bin/java" -cp "$PHERF_CP:$HOWTO/ext/howto-pherf-ext.jar:$HOWTO/ext/probe" \
      Probe "$HOWTO/codes_scenario.xml" 2>/dev/null | grep '^CODE'
    ```

    Captured:

    ```text
    CODE ORD-0000000000 ORD-0000000001 ORD-0000000042
    ```

    The first two values are produced by `getDataValue`, which the standard rule-based generators
    invoke. The third value is produced by `valueAt(42, 5)`, which co-generation invokes for row
    index 42.

11. Run the scenario on a cluster with the extension jar on the class path. Setting `PHERF_EXT`
    appends the jar after the standard Pherf class path. First start a UDP listener for the metrics
    sink in a separate terminal, for example `nc -lu 8125`. Execute the scenario once without the
    connection cache and once with the connection cache enabled.

    ```sh
    ZK="zk1.example.com,zk2.example.com,zk3.example.com:2181"
    export PHERF_EXT="$HOWTO/ext/howto-pherf-ext.jar"
    pherf -z "$ZK" -drop 'PHERF\.HOWTO_CODES' -l -q -disableSchemaApply -label nocache \
      -scenarioFile "$HOWTO/codes_scenario.xml" 2>"$HOWTO/nocache.log"
    pherf -z "$ZK" -q -disableSchemaApply -connectionCache -label cache \
      -scenarioFile "$HOWTO/codes_scenario.xml" 2>"$HOWTO/cache.log"
    unset PHERF_EXT
    grep -h -A1 'howto.connectionOpens' RESULTS/RESULT_howto_codes_nocache.xml \
      RESULTS/RESULT_howto_codes_cache.xml | tr -d ' '
    ```

    Expected (not captured on a cluster; the counts are illustrative): the custom section entries
    in each XML report show far fewer connection opens during the cached run.

    ```text
    <key>howto.connectionOpens</key>
    <value>1512</value>
    --
    <key>howto.connectionOpens</key>
    <value>2</value>
    ```

    The StatsD listener receives metric lines such as
    `pherf.query.elapsed_ms,scenario=codes,query=byKey,outcome=ok:1|ms`. Each `query` timing metric
    reports the execution service time in ms, rounded half up. To inspect open-loop response times
    and exact microsecond measurements, consult the latency report
    ([HOWTO 06](06-results-verdict-and-analysis.md)).

12. Before contributing a change to Pherf itself, ensure that your work satisfies the module's
    verification standards. Execute the following commands from the root of the repository.

    ```sh
    mvn -pl phoenix-pherf -Dskip.code-coverage -DPhoenixPatchProcess test
    mvn -pl phoenix-pherf -DskipTests clean verify
    mvn -pl phoenix-pherf spotless:apply && mvn -N spotless:apply
    mvn -pl phoenix-pherf spotless:check && mvn -N spotless:check
    mvn -pl phoenix-pherf apache-rat:check
    ```

    Because an integration test class starts an HBase mini-cluster, running one takes about
    4 minutes. Execute a single integration test class using the command below, and target a single
    test method whenever possible:

    ```sh
    mvn -pl phoenix-pherf verify -DPhoenixPatchProcess -Dskip.code-coverage \
      -DskipParallelStatsEnabledTests=true -DskipParallelStatsDisabledTests=true \
      -Dit.test=NativeTypesIT -Dtest=NoSuchUnitTest
    ```

    The rules:

    - **Unit tests first.** Every new behavior must be accompanied by a test that fails before the
      change and passes after it. Do not run the unit test suite with `-o`.
    - **Child JVM tests.** Tests covering a complete Pherf run should use the child JVM test
      utilities under `src/test/java/org/apache/phoenix/pherf/run` (`ChildJvm`,
      `FakePhoenixDriver`, and `Records`), which launch the real Pherf main class in a separate JVM
      against a fake Phoenix driver that records every connection and statement without needing a
      cluster.
    - **Integration tests sparingly.** Prove functional behavior through unit tests and child JVM
      tests. Add an integration test (IT) only for behavior that genuinely requires a live cluster,
      such as a data type round-trip, and run that test class in isolation.
    - **Compatibility.** Every new capability must be opt-in so that existing scenario files,
      command lines, and output parsers continue to work without modification. The golden reference
      files under `src/test/resources/golden` (covering the CLI option set and the default CSV and
      XML outputs) must not change.
    - **Java 8 source level.** Maintain Java 8 source compatibility: do not use `var`, `List.of`,
      `Map.of`, switch expressions, or text blocks.
    - **Banned imports.** The Maven Enforcer rules prohibit `com.google.common`,
      `org.apache.commons.cli`, `commons-logging`, `commons.lang`, and `commons.collections`. Use
      the shaded equivalents under `org.apache.phoenix.thirdparty`.
    - **No new dependency** may be added without prior discussion, because the build's dependency
      analysis fails on any warning.
    - **The ASF header** must be present on every new file, and all lines must wrap at 100 columns
      or fewer.

## Read the result

- `ListExtensions` prints one line for each discovered registration. If no line appears for an
  interface, no implementation was registered: verify the descriptor file name under
  `META-INF/services` (which must match the full interface name) and confirm that the jar is on the
  class path.
- A run that fails upon opening its first connection or recording its first metric with
  `Cannot load the registered ...` indicates a registered implementation that failed to load; the
  underlying cause is included in the exception stack trace.
- The error `More than one org.apache.phoenix.pherf.spi.ConnectionProvider is registered` lists all
  conflicting provider classes discovered on the class path; keep only one.
- Custom report section entries appear under the `sections` element in each generated XML report.
  When no report section is registered, the XML report omits the `sections` element entirely,
  matching the behavior prior to the introduction of these extension points.
- The ratio of connection opens recorded with and without `-connectionCache` demonstrates how much
  connection establishment overhead the cache eliminates from the measured samples.

## Reproduce it

Archive the extension jar, its source files, and its `META-INF/services` descriptor files alongside
the benchmark results, because extensions alter what the run measures (a connection provider can
add work to each connection open, and a metrics sink adds work to each recorded operation). Record
the repository commit, the JDK version, and the run labels. Because the custom generator in this
HOWTO is a pure function of the row index, co-generated values reproduce identically whenever the
same seed and row count are used.

## Clean up

Drop the table created during the cluster run step. Passing `-disableSchemaApply` prevents Pherf
from re-applying schemas and recreating tables during the drop invocation.

```sh
pherf -z "$ZK" -disableSchemaApply -drop 'PHERF\.HOWTO_CODES' 2>"$HOWTO/drop.log"
echo "exit=$?"
```

Expected (not captured): `exit=0`.

## See also

- The service provider interface sources under
  `phoenix-pherf/src/main/java/org/apache/phoenix/pherf/spi`.
- [README.md](../../README.md), section Co-generation, for documentation on `generatorClass` and
  `IndexedDataGenerator`.
- [HOWTO 05](05-types-and-multi-tenant.md) for the `operation` metric records emitted by `-mt`
  multi-tenant workloads and their tenant tags.
- [Harness HOWTO 13](../../../dev/harness/docs/howto/13-extending-the-harness.md) for extending the
  test harness with custom plug-ins, checks, and drivers.
