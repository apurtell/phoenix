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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.phoenix.pherf.exception.AnalysisException;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.QueryResult;
import org.apache.phoenix.pherf.result.QuerySetResult;
import org.apache.phoenix.pherf.result.Result;
import org.apache.phoenix.pherf.result.RunTime;
import org.apache.phoenix.pherf.result.ScenarioResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.apache.phoenix.pherf.result.impl.XMLResultHandler;
import org.apache.phoenix.pherf.workload.MultiThreadedRunner;

/**
 * Represents a single replicate for offline analysis, backed by a directory containing the outputs
 * of one Pherf run. Two directory layouts are supported:
 * <ul>
 * <li>A harness run bundle (dev/harness/docs/bundle-format.md), where manifest.json defines the run
 * id, the run class, the result, and the contamination tags. In this layout, the latency report is
 * located at {@link #BUNDLE_JSON}, and the legacy XML results are stored as RESULT_*_run.xml files
 * inside {@link #BUNDLE_RESULTS}.</li>
 * <li>A bare Pherf results_HDR directory containing exactly one LATENCY_label.json file along with
 * any RESULT_*_label.xml files that the operator copied into the directory. For this layout, the
 * run class is recorded as {@link #UNDECLARED}.</li>
 * </ul>
 * The loader retains only the data required for the analysis. Any validation refusal throws an
 * {@link AnalysisException} with exit code 1 and a diagnostic message that names the offending
 * directory.
 */
public final class Replicate {
  public static final String BUNDLE = "bundle";
  public static final String BARE = "bare";
  public static final String VALIDATION = "validation";
  public static final String MEASUREMENT = "measurement";
  public static final String UNDECLARED = "undeclared";
  public static final String BUNDLE_JSON = "drivers/pherf/results_HDR/LATENCY_run.json";
  public static final String BUNDLE_RESULTS = "drivers/pherf/results";
  /**
   * Defines the synthetic operation name used to index run-level metrics. Because this identifier
   * omits the ':' separator that appears in every operation recorded by the latency report, the
   * resulting names are guaranteed not to collide.
   */
  public static final String RUN = "run";
  public static final String OPS_PER_SEC = "opsPerSec";
  public static final String COUNT = "count";
  public static final String ROWS = "rows";
  /**
   * Lists the tracked latency metric names, which correspond to the percentile fields present on
   * each histogram entry in the latency report.
   */
  public static final List<String> PERCENTILES =
    Collections.unmodifiableList(Arrays.asList("p50Us", "p90Us", "p99Us", "p999Us"));
  // Specifies the minimum sample count required so that a given percentile q resolves to a distinct
  // observation rather than the single slowest sample, calculated as 1 / (1 - q).
  private static final long[] PERCENTILE_MIN_COUNTS = { 2, 10, 100, 1000 };
  private static final int BUNDLE_FORMAT_VERSION = 1;
  // Enumerates the operation kinds included in aggregate throughput calculations; "response" is
  // excluded because it duplicates "query", and "load" is excluded because it counts batches.
  private static final List<String> THROUGHPUT_KINDS =
    Arrays.asList("query", "scan", "delete", "operation");

  private final String name;
  private final String layout;
  private final String runId;
  private final String runClass;
  private final List<String> contamination;
  private final String result;
  private final String latencyReport;
  private final List<String> xmlResults = new ArrayList<>();
  private final Set<String> coGenerated = new TreeSet<>();
  // The fields declared above are serialized into the analysis report, whereas the transient state
  // declared below is retained strictly for in-memory analysis and omitted from serialized output.
  private final transient Map<Key, Double> metrics = new TreeMap<>();
  private final transient Map<Key, Long> counts = new TreeMap<>();
  private final transient Set<String> exactOperations = new TreeSet<>();
  private final transient Set<String> timedOperations = new TreeSet<>();

  private Replicate(String name, String layout, String runId, String runClass,
    List<String> contamination, String result, String latencyReport) {
    this.name = name;
    this.layout = layout;
    this.runId = runId;
    this.runClass = runClass;
    this.contamination = contamination;
    this.result = result;
    this.latencyReport = latencyReport;
  }

  /**
   * Represents a composite lookup key for a metric or count, combining the operation and phase
   * identifiers from the latency report with the specific metric name.
   */
  public static final class Key implements Comparable<Key> {
    private static final List<String> ORDER = new ArrayList<>(PERCENTILES);

    static {
      ORDER.addAll(Arrays.asList(OPS_PER_SEC, COUNT, ROWS));
    }

    private final String operation;
    private final String phase;
    private final String metric;

    public Key(String operation, String phase, String metric) {
      this.operation = operation;
      this.phase = phase;
      this.metric = metric;
    }

    public String getOperation() {
      return operation;
    }

    public String getPhase() {
      return phase;
    }

    public String getMetric() {
      return metric;
    }

    @Override
    public int compareTo(Key other) {
      int order = operation.compareTo(other.operation);
      if (order == 0) {
        order = phase.compareTo(other.phase);
      }
      return order != 0 ? order : ORDER.indexOf(metric) - ORDER.indexOf(other.metric);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key && compareTo((Key) other) == 0;
    }

    @Override
    public int hashCode() {
      return (operation + '|' + phase + '|' + metric).hashCode();
    }

    /**
     * Formats this key as a textual identifier consisting of the operation, phase, and metric
     * joined by the '|' delimiter.
     */
    @Override
    public String toString() {
      return operation + '|' + phase + '|' + metric;
    }
  }

  /**
   * Loads and validates the replicate data contained within the specified directory.
   */
  public static Replicate load(File dir) throws AnalysisException {
    File manifest = new File(dir, "manifest.json");
    Replicate replicate;
    try {
      replicate = manifest.isFile() ? bundle(dir, manifest) : bare(dir);
      replicate.readLatency(dir);
    } catch (RuntimeException e) {
      // Gson throws an unchecked runtime exception whenever a JSON element does not match the
      // expected value type during extraction.
      throw new AnalysisException(1, "Cannot analyze " + dir + ": " + e, e);
    }
    for (String xml : replicate.xmlResults) {
      replicate.readXml(dir, xml);
    }
    return replicate;
  }

  private static Replicate bundle(File dir, File manifest) throws AnalysisException {
    JsonObject json = parse(dir, manifest);
    JsonElement version = json.get("bundle_format_version");
    if (
      version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
        || version.getAsDouble() != BUNDLE_FORMAT_VERSION
    ) {
      throw refuse(dir,
        "the bundle_format_version " + version + " is not " + BUNDLE_FORMAT_VERSION);
    }
    JsonObject run = object(dir, json, "run");
    String runClass = string(dir, run, "run.class");
    if (!VALIDATION.equals(runClass) && !MEASUREMENT.equals(runClass)) {
      throw refuse(dir,
        "the run.class " + runClass + " is not " + VALIDATION + " or " + MEASUREMENT);
    }
    String result = string(dir, run, "run.result");
    // G6: An ERROR result indicates a failure in the harness or underlying infrastructure, meaning
    // the run provides no valid experimental evidence and proves nothing.
    if ("ERROR".equals(result)) {
      throw refuse(dir, "the run.result is ERROR, so the run proves nothing");
    }
    JsonElement tags = object(dir, json, "quiet_host").get("tags");
    if (tags == null || !tags.isJsonArray()) {
      throw refuse(dir, "the manifest has no array quiet_host.tags");
    }
    List<String> contamination = new ArrayList<>();
    for (JsonElement tag : tags.getAsJsonArray()) {
      contamination.add(tag.getAsString());
    }
    Replicate replicate = new Replicate(dir.getName(), BUNDLE, string(dir, run, "run.id"), runClass,
      contamination, result, BUNDLE_JSON);
    replicate.xmlResults
      .addAll(names(new File(dir, BUNDLE_RESULTS), "RESULT_", "_run.xml", BUNDLE_RESULTS + "/"));
    return replicate;
  }

  private static Replicate bare(File dir) throws AnalysisException {
    List<String> reports = names(dir, "LATENCY_", ".json", "");
    if (reports.isEmpty()) {
      throw refuse(dir, "the directory is not a replicate: it has no manifest.json and no "
        + "LATENCY_<label>.json");
    }
    if (reports.size() > 1) {
      throw refuse(dir, "a bare results directory must hold one latency report, not " + reports);
    }
    String report = reports.get(0);
    String label = report.substring("LATENCY_".length(), report.length() - ".json".length());
    Replicate replicate = new Replicate(dir.getName(), BARE, null, UNDECLARED,
      Collections.<String> emptyList(), null, report);
    replicate.xmlResults.addAll(names(dir, "RESULT_", "_" + label + ".xml", ""));
    return replicate;
  }

  // Collects the sorted names of all regular files in the directory that match the required prefix
  // and suffix, placing each matched file name after the supplied relative path prefix.
  private static List<String> names(File dir, String prefix, String suffix, String path) {
    List<String> names = new ArrayList<>();
    String[] list = dir.list();
    if (list != null) {
      Arrays.sort(list);
      for (String file : list) {
        if (
          file.startsWith(prefix) && file.endsWith(suffix) && new File(dir, file).isFile()
            && file.length() > prefix.length() + suffix.length()
        ) {
          names.add(path + file);
        }
      }
    }
    return names;
  }

  private void readLatency(File dir) throws AnalysisException {
    File file = new File(dir, latencyReport);
    if (!file.isFile()) {
      throw refuse(dir, "the latency report " + latencyReport + " is missing");
    }
    JsonObject json = parse(dir, file);
    // G10: Latency values across two runs are directly comparable only when both runs share the
    // exact same recorder configuration.
    String format = optString(json, "format");
    long version = optLong(json, "version");
    JsonElement recorder = json.get("recorder");
    if (!LatencyReport.FORMAT.equals(format) || version != LatencyReport.VERSION) {
      throw refuse(dir, latencyReport + " has the format " + format + " version " + version
        + ", not " + LatencyReport.FORMAT + " version " + LatencyReport.VERSION);
    }
    if (
      recorder == null || !recorder.isJsonObject()
        || optLong(recorder.getAsJsonObject(), "lowestUs") != LatencyRecorders.LOWEST_US
        || optLong(recorder.getAsJsonObject(), "highestUs") != LatencyRecorders.HIGHEST_US
        || optLong(recorder.getAsJsonObject(), "significantDigits") != LatencyRecorders.DIGITS
    ) {
      throw refuse(dir,
        latencyReport + " has the recorder " + recorder + ", not lowestUs "
          + LatencyRecorders.LOWEST_US + ", highestUs " + LatencyRecorders.HIGHEST_US
          + ", significantDigits " + LatencyRecorders.DIGITS);
    }
    JsonElement histograms = json.get("histograms");
    if (histograms == null || !histograms.isJsonArray()) {
      throw refuse(dir, latencyReport + " has no histograms array");
    }
    long startMs = number(dir, json, "startMs");
    long endMs = number(dir, json, "endMs");
    long operations = 0;
    for (JsonElement element : histograms.getAsJsonArray()) {
      JsonObject entry = element.getAsJsonObject();
      String operation = string(dir, entry, "operation");
      String phase = string(dir, entry, "phase");
      long count = number(dir, entry, COUNT);
      counts.put(new Key(operation, phase, COUNT), count);
      if (!LatencyRecorders.STEADY.equals(phase)) {
        continue;
      }
      for (int i = 0; i < PERCENTILES.size(); i++) {
        long value = number(dir, entry, PERCENTILES.get(i));
        if (count >= PERCENTILE_MIN_COUNTS[i]) {
          metrics.put(new Key(operation, phase, PERCENTILES.get(i)), (double) value);
        }
      }
      if (THROUGHPUT_KINDS.contains(operation.substring(0, Math.max(0, operation.indexOf(':'))))) {
        operations += count;
      }
    }
    if (endMs > startMs) {
      metrics.put(new Key(RUN, LatencyRecorders.STEADY, OPS_PER_SEC),
        operations * 1000.0 / (endMs - startMs));
    }
  }

  // Extracts the total row count for each query and records the co-generated scenarios from a
  // legacy XML result file.
  private void readXml(File dir, String xml) throws AnalysisException {
    try {
      for (Result result : new XMLResultHandler().readFromResultFile(new File(dir, xml))) {
        DataModelResult model = (DataModelResult) result.getResultValues().get(0).getResultValue();
        for (ScenarioResult scenario : model.getScenarioResult()) {
          String name = scenario.getName();
          boolean exact = scenario.getCoGeneration() != null;
          if (exact) {
            coGenerated.add(name);
            exactOperations.add("load:" + name + ":" + scenario.getTableName());
          }
          for (QuerySetResult querySet : scenario.getQuerySetResult()) {
            for (QueryResult query : querySet.getQueryResults()) {
              String operation =
                MultiThreadedRunner.latencyOperation(query.kind(), name, query.getId());
              String response = "response:" + name + ":" + query.getId();
              Key key = new Key(operation, LatencyRecorders.STEADY, ROWS);
              long rows = counts.containsKey(key) ? counts.get(key) : 0;
              boolean timed = false;
              for (ThreadTime thread : query.getThreadTimes()) {
                // A closed-loop thread that recorded fewer executions than the total configured on
                // the query set was terminated by its duration limit and therefore stopped on time.
                timed |= querySet.getOpenLoop() == null
                  && thread.getRunTimesInMs().size() < querySet.getNumberOfExecutions();
                for (RunTime runTime : thread.getRunTimesInMs()) {
                  rows += runTime.getResultRowCount() == null ? 0 : runTime.getResultRowCount();
                }
              }
              counts.put(key, rows);
              if (exact) {
                (timed ? timedOperations : exactOperations)
                  .addAll(Arrays.asList(operation, response));
              }
            }
          }
        }
      }
    } catch (Exception e) {
      throw new AnalysisException(1, "Cannot analyze " + dir + ": cannot read " + xml + ": " + e,
        e);
    }
  }

  private static JsonObject parse(File dir, File file) throws AnalysisException {
    try {
      return JsonParser
        .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        .getAsJsonObject();
    } catch (Exception e) {
      throw new AnalysisException(1,
        "Cannot analyze " + dir + ": cannot read " + file.getName() + ": " + e, e);
    }
  }

  private static JsonObject object(File dir, JsonObject json, String member)
    throws AnalysisException {
    JsonElement value = json.get(member);
    if (value == null || !value.isJsonObject()) {
      throw refuse(dir, "the manifest has no object " + member);
    }
    return value.getAsJsonObject();
  }

  // Extracts a required string member from the JSON object, using the supplied dotted path (for
  // example, run.id) to identify the member in diagnostic error messages.
  private static String string(File dir, JsonObject json, String path) throws AnalysisException {
    JsonElement value = json.get(path.substring(path.lastIndexOf('.') + 1));
    if (value == null || !value.isJsonPrimitive()) {
      throw refuse(dir, "no string " + path);
    }
    return value.getAsString();
  }

  private static long number(File dir, JsonObject json, String member) throws AnalysisException {
    JsonElement value = json.get(member);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw refuse(dir, "no number " + member + " in " + json);
    }
    return value.getAsLong();
  }

  private static String optString(JsonObject json, String member) {
    JsonElement value = json.get(member);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }

  private static long optLong(JsonObject json, String member) {
    JsonElement value = json.get(member);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
      ? value.getAsLong()
      : -1;
  }

  private static AnalysisException refuse(File dir, String reason) {
    return new AnalysisException(1, "Cannot analyze " + dir + ": " + reason);
  }

  public String getName() {
    return name;
  }

  public String getLayout() {
    return layout;
  }

  /**
   * Returns the run id declared in a bundle manifest, or null when loaded from a bare directory.
   */
  public String getRunId() {
    return runId;
  }

  public String getRunClass() {
    return runClass;
  }

  /**
   * Returns the quiet_host.tags list from a bundle manifest, which is empty when the host time
   * evidence is clean.
   */
  public List<String> getContamination() {
    return contamination;
  }

  /**
   * Returns the run.result status from a bundle manifest, or null when loaded from a bare
   * directory.
   */
  public String getResult() {
    return result;
  }

  /**
   * Returns the relative path to the latency report file within the replicate directory.
   */
  public String getLatencyReport() {
    return latencyReport;
  }

  /**
   * Returns the relative paths to the legacy XML result files discovered in the replicate
   * directory.
   */
  public List<String> getXmlResults() {
    return xmlResults;
  }

  /**
   * Returns the names of the scenarios that the legacy XML results mark as co-generated.
   */
  public List<String> getCoGenerated() {
    return new ArrayList<>(coGenerated);
  }

  /**
   * Returns the extracted latency metrics for all steady-phase keys together with the overall run
   * throughput.
   */
  public Map<Key, Double> getMetrics() {
    return metrics;
  }

  /**
   * Returns the recorded operation count for each key alongside the aggregated row counts for each
   * query extracted from the XML results.
   */
  public Map<Key, Long> getCounts() {
    return counts;
  }

  /**
   * Returns the set of operations belonging to co-generated scenarios whose execution counts must
   * remain equal.
   */
  public Set<String> getExactOperations() {
    return exactOperations;
  }

  /**
   * Returns the set of operations belonging to co-generated scenarios whose closed-loop threads
   * stopped on time.
   */
  public Set<String> getTimedOperations() {
    return timedOperations;
  }
}
