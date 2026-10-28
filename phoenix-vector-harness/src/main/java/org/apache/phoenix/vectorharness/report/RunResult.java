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
package org.apache.phoenix.vectorharness.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.Json;
import org.apache.phoenix.vectorharness.result.RunStore;

/**
 * Encapsulates the output artifacts of a test run loaded from its directory, including the
 * manifest, frontier measurements, steady state windows, and event logs.
 */
public final class RunResult {

  /** Statistics mode label of runs from before the statistics modes. */
  public static final String LEGACY_STATISTICS = "legacy";

  private final Path dir;
  private final Map<String, Object> manifest;
  private final List<FrontierPoint> frontier;
  private final List<Map<String, String>> recallSamples;
  private final List<Map<String, String>> events;

  private RunResult(Path dir, Map<String, Object> manifest, List<FrontierPoint> frontier,
    List<Map<String, String>> recallSamples, List<Map<String, String>> events) {
    this.dir = dir;
    this.manifest = manifest;
    this.frontier = frontier;
    this.recallSamples = recallSamples;
    this.events = events;
  }

  /** Loads run results from the specified directory. */
  public static RunResult read(Path dir) throws IOException {
    Path m = dir.resolve(RunStore.MANIFEST);
    if (!Files.exists(m)) {
      throw new IOException("No manifest in " + dir);
    }
    Map<String, Object> manifest = Json.readMap(m);
    List<FrontierPoint> frontier = new ArrayList<>();
    Path f = dir.resolve("frontier.csv");
    if (Files.exists(f)) {
      for (Map<String, String> row : CsvTable.read(f)) {
        frontier.add(FrontierPoint.of(row));
      }
    }
    return new RunResult(dir, manifest, frontier, readIfExists(dir.resolve("recall.csv")),
      readIfExists(dir.resolve("events.csv")));
  }

  private static List<Map<String, String>> readIfExists(Path p) throws IOException {
    return Files.exists(p) ? CsvTable.read(p) : Collections.<Map<String, String>> emptyList();
  }

  public Path dir() {
    return dir;
  }

  public Map<String, Object> manifest() {
    return manifest;
  }

  /** Resolves a manifest value by dotted path navigation, or returns null. */
  public Object get(String dotted) {
    return Json.path(manifest, dotted);
  }

  public String getString(String dotted) {
    Object v = get(dotted);
    return v == null ? null : v.toString();
  }

  /** Resolves a numeric manifest value, or returns NaN if absent or not a number. */
  public double getDouble(String dotted) {
    Object v = get(dotted);
    return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
  }

  public String id() {
    String id = getString("run.id");
    return id != null ? id : dir.getFileName().toString();
  }

  public String variant() {
    return getString("run.variant");
  }

  public String profile() {
    return getString("run.profile");
  }

  /**
   * Returns the statistics mode of the run. Runs from before the statistics modes lack the setting.
   * They updated statistics with the default guidepost width, so they report as legacy.
   */
  public String statistics() {
    String v = settings().get(Setting.TABLE_STATISTICS.key());
    return v == null || v.trim().isEmpty() ? LEGACY_STATISTICS : v.trim().toLowerCase(Locale.ROOT);
  }

  public String fingerprint() {
    return getString("corpus.fingerprint");
  }

  public String panelDigest() {
    return getString("corpus.panel_digest");
  }

  /** Returns the effective settings of the run. */
  @SuppressWarnings("unchecked")
  public Map<String, String> settings() {
    Object s = manifest.get("settings");
    Map<String, String> out = new LinkedHashMap<>();
    if (s instanceof Map) {
      for (Map.Entry<String, Object> e : ((Map<String, Object>) s).entrySet()) {
        out.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
      }
    }
    return out;
  }

  public List<FrontierPoint> frontier() {
    return frontier;
  }

  /** Returns recall time series samples from recall.csv. */
  public List<Map<String, String>> recallSamples() {
    return recallSamples;
  }

  /** Returns recorded lifecycle events from events.csv. */
  public List<Map<String, String>> events() {
    return events;
  }

  /** Returns the steady-state window summaries. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> steadyWindows() {
    Object w = get("stages.steady");
    List<Map<String, Object>> out = new ArrayList<>();
    if (w instanceof List) {
      for (Object o : (List<Object>) w) {
        if (o instanceof Map) {
          out.add((Map<String, Object>) o);
        }
      }
    }
    return out;
  }

  /** Returns the flags raised during the run. */
  @SuppressWarnings("unchecked")
  public List<String> flags() {
    Object l = get("flags.flags");
    List<String> out = new ArrayList<>();
    if (l instanceof List) {
      for (Object o : (List<Object>) l) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }

  /** Returns aggregated storage footprint bytes for the specified section, or NaN if missing. */
  @SuppressWarnings("unchecked")
  public double footprintBytes(String dotted) {
    Object f = get(dotted);
    if (!(f instanceof Map)) {
      return Double.NaN;
    }
    double sum = 0;
    boolean any = false;
    for (Object v : ((Map<String, Object>) f).values()) {
      if (v instanceof Map) {
        Object t = ((Map<String, Object>) v).get("total_bytes");
        if (!(t instanceof Number)) {
          // A table that could not be measured leaves the sum unknown.
          return Double.NaN;
        }
        sum += ((Number) t).doubleValue();
        any = true;
      }
    }
    return any ? sum : Double.NaN;
  }

  /** Returns the path to per_query.csv if present, or null. */
  public Path perQueryFile() {
    Path p = dir.resolve("per_query.csv");
    return Files.exists(p) ? p : null;
  }
}
