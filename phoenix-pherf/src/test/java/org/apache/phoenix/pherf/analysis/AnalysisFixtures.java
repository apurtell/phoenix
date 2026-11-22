/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.pherf.analysis;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import org.apache.phoenix.pherf.configuration.CoGeneration;
import org.apache.phoenix.pherf.configuration.OpenLoop;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.RunTime;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.result.ThreadTime;

/**
 * Generates test replicate fixtures on disk, supporting both full run bundles structured according
 * to the harness specification (dev/harness/docs/bundle-format.md) and standalone Pherf results_HDR
 * directories. Each generated fixture defines a single scenario {@link #SCENARIO} targeting the
 * table {@link #TABLE}.
 */
public final class AnalysisFixtures {
  public static final String SCENARIO = "S";
  public static final String TABLE = "PHERF.T";
  public static final String QUERY = "q";
  /** Identifies the steady-state metric key corresponding to the query {@link #QUERY}. */
  public static final String QUERY_KEY = "query:" + SCENARIO + ":" + QUERY;
  public static final String BUNDLE_JSON = "drivers/pherf/results_HDR/LATENCY_run.json";
  public static final String BUNDLE_XML = "drivers/pherf/results/RESULT_dm_run.xml";
  // Defines a 10 s recording window for each generated latency report.
  private static final long START_MS = 1_000_000L;
  private static final long END_MS = 1_010_000L;

  private AnalysisFixtures() {
  }

  public static Replica replica(String name) {
    return new Replica(name);
  }

  /**
   * Constructs the run identifier for a fixture replicate as a valid run id that ends with the
   * sanitized replicate name.
   */
  public static String runId(String name) {
    String tail = ("0000" + name.toLowerCase()).replaceAll("[^0-9a-z]", "0");
    return "20261122T120000Z-" + tail.substring(tail.length() - 4);
  }

  /**
   * Serializes a complete manifest.json file populated with every required member of the bundle
   * format specification.
   */
  public static void writeManifest(File dir, String runId, String runClass, String result,
    String... tags) throws IOException {
    JsonObject run = new JsonObject();
    run.addProperty("id", runId);
    run.addProperty("class", runClass);
    run.addProperty("backend", "compose");
    run.addProperty("scope", "px-default");
    run.addProperty("driver", "pherf");
    run.addProperty("suite", "perf");
    run.addProperty("test", "reference");
    run.addProperty("session", "");
    run.addProperty("started_ms", 1795194669000L);
    run.addProperty("ended_ms", 1795194690000L);
    run.addProperty("result", result);
    JsonObject versions = new JsonObject();
    versions.addProperty("phoenix_hbase_profile", "2.6");
    versions.addProperty("hbase", "2.6.7");
    versions.addProperty("hadoop", "3.4.3");
    versions.addProperty("zookeeper", "3.8.6");
    versions.addProperty("hbase_hadoop_client", "3.4.3");
    versions.add("jdk", new JsonObject());
    JsonObject quiet = new JsonObject();
    quiet.addProperty("state", tags.length == 0 ? "quiet" : "contaminated");
    JsonArray tagArray = new JsonArray();
    for (String tag : tags) {
      tagArray.add(tag);
    }
    quiet.add("tags", tagArray);
    JsonObject steady = new JsonObject();
    steady.addProperty("anchor", "steady@pherf");
    steady.addProperty("start_ms", 1795194680000L);
    steady.addProperty("end_ms", 1795194685000L);
    steady.add("metrics", new JsonObject());
    JsonArray steadyArray = new JsonArray();
    steadyArray.add(steady);
    JsonObject manifest = new JsonObject();
    manifest.addProperty("bundle_format_version", 1);
    manifest.add("run", run);
    manifest.add("versions", versions);
    manifest.add("images", new JsonArray());
    manifest.add("tables", new JsonArray());
    manifest.add("quiet_host", quiet);
    manifest.add("breach", JsonNull.INSTANCE);
    manifest.add("anomaly", JsonNull.INSTANCE);
    manifest.add("stages", new JsonArray());
    manifest.add("steady", steadyArray);
    manifest.add("disruptions", new JsonArray());
    manifest.add("artifacts", new JsonArray());
    write(new File(dir, "manifest.json"), manifest);
  }

  /**
   * Parses an existing JSON file from disk, applies the supplied mutation in place, and writes the
   * updated JSON object back to the file.
   */
  public static void editJson(File file, Consumer<JsonObject> change) throws IOException {
    JsonObject json =
      JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        .getAsJsonObject();
    change.accept(json);
    write(file, json);
  }

  private static void write(File file, JsonObject json) throws IOException {
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), new GsonBuilder().setPrettyPrinting().serializeNulls().create()
      .toJson(json).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builder representing a single test replicate, encapsulating its manifest metadata values, its
   * latency report histograms, and its legacy XML execution result.
   */
  public static final class Replica {
    private final String name;
    private String runClass = Replicate.MEASUREMENT;
    private String[] tags = new String[0];
    private String result = "PASS";
    private final List<JsonObject> histograms = new ArrayList<>();
    private boolean coGenerated;
    private Long executions;
    private boolean openLoop;
    private boolean xml = true;
    private final Map<String, String> kinds = new LinkedHashMap<>();
    private final Map<String, long[]> rows = new LinkedHashMap<>();

    private Replica(String name) {
      this.name = name;
    }

    public Replica runClass(String value) {
      this.runClass = value;
      return this;
    }

    public Replica tags(String... values) {
      this.tags = values;
      return this;
    }

    public Replica result(String value) {
      this.result = value;
      return this;
    }

    /** Appends a single latency histogram entry to the replicate report. */
    public Replica latency(String operation, String phase, long count, long p50, long p90, long p99,
      long p999) {
      JsonObject entry = new JsonObject();
      entry.addProperty("operation", operation);
      entry.addProperty("phase", phase);
      entry.addProperty("count", count);
      entry.addProperty("minUs", Math.min(p50, 1));
      entry.addProperty("maxUs", p999 + 1);
      entry.addProperty("meanUs", p50 + 0.5);
      entry.addProperty("p50Us", p50);
      entry.addProperty("p90Us", p90);
      entry.addProperty("p99Us", p99);
      entry.addProperty("p999Us", p999);
      histograms.add(entry);
      return this;
    }

    /**
     * Appends a single steady-phase histogram entry in which all four latency percentiles are set
     * to the same microsecond value.
     */
    public Replica latency(String operation, long count, long us) {
      return latency(operation, "steady", count, us, us, us, us);
    }

    /** Configures the scenario in the generated XML result as co-generated. */
    public Replica coGenerated() {
      this.coGenerated = true;
      return this;
    }

    /**
     * Overrides the numberOfExecutions configured on the query set. When unset, this defaults to
     * the maximum number of recorded run times across all queries so that no thread is treated as
     * having stopped early due to a timeout.
     */
    public Replica executions(long value) {
      this.executions = value;
      return this;
    }

    /** Configures the query set in the generated XML result as an open-loop query set. */
    public Replica openLoop() {
      this.openLoop = true;
      return this;
    }

    /** Omits writing the legacy XML result file, emulating the output of a -mt execution run. */
    public Replica noXml() {
      this.xml = false;
      return this;
    }

    /**
     * Registers a query entry in the generated XML result, recording one execution run time for
     * each supplied row count.
     * @param kind the query kind, where null represents a SELECT query, or SCAN or DELETE specifies
     *             the corresponding operation.
     */
    public Replica query(String id, String kind, long... rowCounts) {
      kinds.put(id, kind);
      rows.put(id, rowCounts);
      return this;
    }

    /**
     * Materializes a complete run bundle under setDir/name and returns the resulting bundle
     * directory.
     */
    public File writeBundle(File setDir) throws Exception {
      File dir = new File(setDir, name);
      writeManifest(dir, runId(name), runClass, result, tags);
      write(new File(dir, BUNDLE_JSON), latencyJson("run"));
      if (xml) {
        writeXml(new File(dir, BUNDLE_XML));
      }
      return dir;
    }

    /**
     * Materializes a standalone bare results directory under setDir/name using the given label and
     * returns the resulting directory.
     */
    public File writeBare(File setDir, String label) throws Exception {
      File dir = new File(setDir, name);
      write(new File(dir, "LATENCY_" + label + ".json"), latencyJson(label));
      if (xml) {
        writeXml(new File(dir, "RESULT_dm_" + label + ".xml"));
      }
      return dir;
    }

    private JsonObject latencyJson(String label) {
      JsonObject recorder = new JsonObject();
      recorder.addProperty("lowestUs", 1);
      recorder.addProperty("highestUs", 600000000L);
      recorder.addProperty("significantDigits", 3);
      JsonArray entries = new JsonArray();
      for (JsonObject entry : histograms) {
        entries.add(entry);
      }
      JsonObject json = new JsonObject();
      json.addProperty("format", "pherf-latency");
      json.addProperty("version", 1);
      json.addProperty("label", label);
      json.addProperty("startMs", START_MS);
      json.addProperty("endMs", END_MS);
      json.add("recorder", recorder);
      json.addProperty("hlog", "LATENCY_" + label + ".hlog");
      json.add("histograms", entries);
      return json;
    }

    private void writeXml(File file) throws Exception {
      ScenarioResult scenario = new ScenarioResult();
      scenario.setName(SCENARIO);
      scenario.setTableName(TABLE);
      scenario.setRowCount(1000);
      if (coGenerated) {
        scenario.setCoGeneration(new CoGeneration());
      }
      QuerySetResult querySet = new QuerySetResult();
      long most = 0;
      for (long[] runTimes : rows.values()) {
        most = Math.max(most, runTimes.length);
      }
      querySet.setNumberOfExecutions(executions == null ? most : executions);
      querySet.setOpenLoop(openLoop ? new OpenLoop() : null);
      for (Map.Entry<String, long[]> entry : rows.entrySet()) {
        QueryResult query = new QueryResult();
        query.setId(entry.getKey());
        query.setKind(kinds.get(entry.getKey()));
        query.setStatement("SELECT 1");
        ThreadTime thread = new ThreadTime();
        thread.setThreadName("1,1");
        for (long count : entry.getValue()) {
          thread.getRunTimesInMs().add(new RunTime(count, 1L));
        }
        query.getThreadTimes().add(thread);
        querySet.getQueryResults().add(query);
      }
      scenario.getQuerySetResult().add(querySet);
      DataModelResult model = new DataModelResult();
      model.setName("dm");
      model.getScenarioResult().add(scenario);
      Files.createDirectories(file.getParentFile().toPath());
      Marshaller marshaller = JAXBContext.newInstance(DataModelResult.class).createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
      try (OutputStream out = new FileOutputStream(file)) {
        marshaller.marshal(model, out);
      }
    }
  }
}
