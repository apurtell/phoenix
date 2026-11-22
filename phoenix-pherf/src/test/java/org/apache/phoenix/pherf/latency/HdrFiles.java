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
package org.apache.phoenix.pherf.latency;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;

/** Parses latency output artifacts using the same approach as an external consumer. */
public final class HdrFiles {
  private static final String BASE_TIME = "#[BaseTime: ";

  private HdrFiles() {
  }

  /**
   * Initializes a new latency recording run that writes its output artifacts to the specified
   * directory with periodic interval export disabled.
   */
  public static void begin(File dir, String label) throws IOException {
    LatencyRecorders.begin(dir, label, 0);
  }

  /** Reads and parses a JSON latency report file into a structured JSON object. */
  public static JsonObject json(File file) throws IOException {
    return JsonParser
      .parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
      .getAsJsonObject();
  }

  /** Indexes the histogram entries contained within a JSON report by their composite tag. */
  public static Map<String, JsonObject> histograms(JsonObject report) {
    Map<String, JsonObject> entries = new HashMap<>();
    for (JsonElement element : report.getAsJsonArray("histograms")) {
      JsonObject entry = element.getAsJsonObject();
      entries.put(
        LatencyReport.tag(entry.get("operation").getAsString(), entry.get("phase").getAsString()),
        entry);
    }
    return entries;
  }

  /** Extracts all interval histograms from a histogram log file in sequential file order. */
  public static List<Histogram> hlog(File file) throws IOException {
    List<Histogram> histograms = new ArrayList<>();
    HistogramLogReader reader = new HistogramLogReader(file);
    try {
      EncodableHistogram histogram;
      while ((histogram = reader.nextIntervalHistogram()) != null) {
        histograms.add((Histogram) histogram);
      }
    } finally {
      reader.close();
    }
    return histograms;
  }

  /**
   * Extracts the exact [start, end] millisecond timestamps for each interval histogram in a log
   * file, preserving file order. Because HistogramLogReader sums the logged second offsets using
   * double-precision arithmetic before truncating to milliseconds, its computed timestamps can fall
   * 1 ms below the values actually written. This method parses the decimal text directly from the
   * log to recover the exact millisecond bounds.
   */
  public static List<long[]> boundsMs(File file) throws IOException {
    List<long[]> bounds = new ArrayList<>();
    BigDecimal baseSec = BigDecimal.ZERO;
    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
      if (line.startsWith(BASE_TIME)) {
        int end = line.indexOf(' ', BASE_TIME.length());
        baseSec = new BigDecimal(line.substring(BASE_TIME.length(), end));
      } else if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("\"")) {
        // Each data line follows the format [Tag=<tag>,]<start s>,<length s>,<max>,<histogram>,
        // where a tag never contains a comma.
        String[] fields = line.split(",");
        int i = fields[0].startsWith("Tag=") ? 1 : 0;
        long startMs = baseSec.add(new BigDecimal(fields[i])).movePointRight(3).longValueExact();
        long lengthMs = new BigDecimal(fields[i + 1]).movePointRight(3).longValueExact();
        bounds.add(new long[] { startMs, startMs + lengthMs });
      }
    }
    return bounds;
  }

  /** Aggregates the total sample counts across all provided histograms, grouped by tag. */
  public static Map<String, Long> countsByTag(List<Histogram> histograms) {
    Map<String, Long> counts = new HashMap<>();
    for (Histogram histogram : histograms) {
      Long count = counts.get(histogram.getTag());
      counts.put(histogram.getTag(), (count == null ? 0 : count) + histogram.getTotalCount());
    }
    return counts;
  }

  /** Extracts the sample count for each histogram entry in a JSON report, indexed by tag. */
  public static Map<String, Long> countsByTag(JsonObject report) {
    Map<String, Long> counts = new HashMap<>();
    for (Map.Entry<String, JsonObject> entry : histograms(report).entrySet()) {
      counts.put(entry.getKey(), entry.getValue().get("count").getAsLong());
    }
    return counts;
  }

  /** Verifies that a histogram matches the pinned recorder configuration parameters. */
  public static void assertPinned(Histogram histogram) {
    assertEquals(1, histogram.getLowestDiscernibleValue());
    assertEquals(600_000_000L, histogram.getHighestTrackableValue());
    assertEquals(3, histogram.getNumberOfSignificantValueDigits());
  }
}
