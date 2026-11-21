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
  private HdrFiles() {
  }

  /** Parses a JSON latency report file into an object representation. */
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

  /** Aggregates the total sample counts across the provided histograms, grouped by tag. */
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
