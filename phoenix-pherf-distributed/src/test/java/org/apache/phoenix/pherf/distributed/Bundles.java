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
package org.apache.phoenix.pherf.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;

/** Reads and parses the files of a controller run bundle for test verification. */
public final class Bundles {
  /**
   * Defines the expected first header line of a Pherf latency log as specified by LATENCY.md and
   * enforced by the H2 driver.
   */
  public static final String HLOG_HEADER = "#[Pherf latency log 1: microseconds, lowest 1, highest"
    + " 600000000, 3 significant digits, Tag=OPERATION|PHASE]";

  private Bundles() {
  }

  public static JsonObject json(File file) throws IOException {
    return JsonParser
      .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }

  public static File results(File bundle) {
    return new File(bundle, RunBundle.RESULTS);
  }

  public static JsonObject manifest(File bundle) throws IOException {
    return json(new File(bundle, RunBundle.MANIFEST));
  }

  public static JsonObject report(File bundle) throws IOException {
    return json(new File(results(bundle), "LATENCY_run.json"));
  }

  /** Reads and returns the rows of VERDICT_run.tsv, with each line split on tab characters. */
  public static List<String[]> verdict(File bundle) throws IOException {
    List<String[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(new File(results(bundle), "VERDICT_run.tsv").toPath(),
      StandardCharsets.UTF_8)) {
      rows.add(line.split("\t", -1));
    }
    return rows;
  }

  /** Looks up and returns the verdict row corresponding to the specified check name. */
  public static String[] row(File bundle, String check) throws IOException {
    for (String[] row : verdict(bundle)) {
      if (row[0].equals(check)) {
        return row;
      }
    }
    throw new AssertionError("no row " + check);
  }

  /** Reads and returns the interval histograms from a log in file order, preserving each tag. */
  public static List<Histogram> hlog(File file) throws FileNotFoundException {
    List<Histogram> histograms = new ArrayList<>();
    HistogramLogReader reader = new HistogramLogReader(file);
    try {
      EncodableHistogram next;
      while ((next = reader.nextIntervalHistogram()) != null) {
        histograms.add((Histogram) next);
      }
    } finally {
      reader.close();
    }
    return histograms;
  }

  /** Aggregates and returns the total sample count for each tag across a log's histograms. */
  public static Map<String, Long> counts(List<Histogram> histograms) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Histogram histogram : histograms) {
      counts.merge(histogram.getTag(), histogram.getTotalCount(), Long::sum);
    }
    return counts;
  }

  /** Extracts the sample count for each histogram entry in a JSON report, keyed by log tag. */
  public static Map<String, Long> counts(JsonObject report) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (JsonElement element : report.getAsJsonArray("histograms")) {
      JsonObject entry = element.getAsJsonObject();
      counts.put(entry.get("operation").getAsString() + "|" + entry.get("phase").getAsString(),
        entry.get("count").getAsLong());
    }
    return counts;
  }

  /**
   * Verifies that the serialized value of a JSON member is a whole number written without a sign,
   * fractional part, or exponent.
   */
  private static void whole(JsonObject object, String member) {
    JsonElement value = object.get(member);
    assertTrue(member + " = " + value, value instanceof JsonPrimitive
      && value.getAsJsonPrimitive().isNumber() && value.toString().matches("[0-9]+"));
  }

  /**
   * Enforces the validation rules of pherf_json_rows in the H2 driver
   * (dev/harness/drivers/pherf/driver.sh), along with its requirement that the referenced run log
   * begins with the header defined in LATENCY.md.
   */
  public static void assertH2Rules(File results) throws IOException {
    JsonObject report = json(new File(results, "LATENCY_run.json"));
    assertEquals("pherf-latency", report.get("format").getAsString());
    assertEquals("1", report.get("version").toString());
    JsonObject recorder = report.getAsJsonObject("recorder");
    assertEquals("1", recorder.get("lowestUs").toString());
    assertEquals("600000000", recorder.get("highestUs").toString());
    assertEquals("3", recorder.get("significantDigits").toString());
    whole(report, "startMs");
    whole(report, "endMs");
    String hlog = report.get("hlog").getAsString();
    assertTrue(hlog, hlog.matches("[^/]+") && !hlog.equals(".") && !hlog.equals(".."));
    for (JsonElement element : report.getAsJsonArray("histograms")) {
      JsonObject entry = element.getAsJsonObject();
      assertTrue(entry.get("operation").getAsJsonPrimitive().isString());
      assertTrue(entry.get("phase").getAsJsonPrimitive().isString());
      for (String field : new String[] { "count", "minUs", "maxUs", "p50Us", "p90Us", "p99Us",
        "p999Us" }) {
        whole(entry, field);
      }
      assertTrue(entry.get("meanUs").toString(), entry.get("meanUs").getAsJsonPrimitive().isNumber()
        && entry.get("meanUs").toString().matches("-?[0-9].*"));
    }
    if (report.has("schedules")) {
      for (JsonElement element : report.getAsJsonArray("schedules")) {
        JsonObject schedule = element.getAsJsonObject();
        assertTrue(schedule.get("scenario").getAsJsonPrimitive().isString());
        whole(schedule, "querySet");
        for (String field : new String[] { "startMs", "endMs", "scheduled", "completed", "late",
          "dropped", "aborted", "maxQueueDepth", "maxDriftUs" }) {
          whole(schedule, field);
        }
      }
    }
    List<String> lines = Files.readAllLines(new File(results, hlog).toPath());
    assertEquals(HLOG_HEADER, lines.get(0));
  }
}
