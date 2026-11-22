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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LatencyRecordersTest {
  private static final String LABEL = "t";
  private static final long T0 = 1_700_000_000_000L;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private ManualEdge edge;

  @Before
  public void setUp() {
    edge = new ManualEdge(T0);
    EnvironmentEdgeManager.injectEdge(edge);
  }

  @After
  public void tearDown() throws Exception {
    try {
      LatencyRecorders.finish();
    } finally {
      EnvironmentEdgeManager.reset();
    }
  }

  private File dir() {
    return folder.getRoot();
  }

  private File json() {
    return new File(dir(), "LATENCY_" + LABEL + ".json");
  }

  private File hlog() {
    return new File(dir(), "LATENCY_" + LABEL + ".hlog");
  }

  private File intervals() {
    return new File(dir(), "LATENCY_" + LABEL + "_intervals.hlog");
  }

  @Test
  public void testPinnedConfiguration() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 3_600_000);
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, 10);
    LatencyRecorders.current().roll();
    HdrFiles.assertPinned(LatencyRecorders.current().total("query:s:q", LatencyRecorders.STEADY));
    LatencyRecorders.finish();

    HdrFiles.assertPinned(HdrFiles.hlog(hlog()).get(0));
    HdrFiles.assertPinned(HdrFiles.hlog(intervals()).get(0));
    JsonObject recorder = HdrFiles.json(json()).getAsJsonObject("recorder");
    assertEquals(1, recorder.get("lowestUs").getAsLong());
    assertEquals(600_000_000L, recorder.get("highestUs").getAsLong());
    assertEquals(3, recorder.get("significantDigits").getAsInt());
  }

  @Test
  public void testKeyedByOperationAndPhase() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 0);
    String[] operations = { "query:s:a", "query:s:b" };
    String[] phases = { LatencyRecorders.STEADY, "warmup" };
    int n = 0;
    for (String operation : operations) {
      for (String phase : phases) {
        n++;
        for (int i = 0; i < n; i++) {
          LatencyRecorders.record(operation, phase, 100);
        }
      }
    }
    LatencyRecorders.finish();

    Map<String, Long> counts = HdrFiles.countsByTag(HdrFiles.json(json()));
    assertEquals(4, counts.size());
    n = 0;
    for (String operation : operations) {
      for (String phase : phases) {
        n++;
        assertEquals(Long.valueOf(n), counts.get(LatencyReport.tag(operation, phase)));
      }
    }
    assertEquals(counts, HdrFiles.countsByTag(HdrFiles.hlog(hlog())));
  }

  @Test
  public void testSubMillisecondIsNonZero() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 0);
    edge.micros = 0;
    long start = Micros.now();
    edge.micros = 300;
    long elapsed = Micros.now() - start;
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, elapsed);
    LatencyRecorders.finish();

    assertEquals(0, Micros.toMillis(elapsed));
    JsonObject entry = HdrFiles.histograms(HdrFiles.json(json()))
      .get(LatencyReport.tag("query:s:q", LatencyRecorders.STEADY));
    assertEquals(300, entry.get("p50Us").getAsLong());
    assertEquals(300, entry.get("minUs").getAsLong());
  }

  @Test
  public void testMicrosKeptInJsonAndHlog() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 0);
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, 1_234);
    LatencyRecorders.finish();

    JsonObject entry = HdrFiles.histograms(HdrFiles.json(json()))
      .get(LatencyReport.tag("query:s:q", LatencyRecorders.STEADY));
    assertEquals(1, entry.get("count").getAsLong());
    assertEquals(1_234, entry.get("minUs").getAsLong());
    assertEquals(1_234, entry.get("maxUs").getAsLong());
    assertEquals(1_234, entry.get("p50Us").getAsLong());
    assertEquals(1_234.0, entry.get("meanUs").getAsDouble(), 0.0);

    List<Histogram> reloaded = HdrFiles.hlog(hlog());
    assertEquals(1, reloaded.size());
    Histogram histogram = reloaded.get(0);
    assertArrayEquals(new String[] { "query:s:q", LatencyRecorders.STEADY },
      LatencyReport.parseTag(histogram.getTag()));
    long p50 = histogram.getValueAtPercentile(50);
    assertTrue(histogram.valuesAreEquivalent(p50, 1_234));
  }

  @Test
  public void testTagSurvivesLogReader() throws Exception {
    String operation = "query:my scenario,1%:q\r\n|x";
    LatencyRecorders.begin(dir(), LABEL, 0);
    LatencyRecorders.record(operation, LatencyRecorders.STEADY, 10);
    LatencyRecorders.finish();

    Histogram histogram = HdrFiles.hlog(hlog()).get(0);
    assertEquals(1, histogram.getTotalCount());
    assertArrayEquals(new String[] { operation, LatencyRecorders.STEADY },
      LatencyReport.parseTag(histogram.getTag()));
    assertEquals(operation, HdrFiles.json(json()).getAsJsonArray("histograms").get(0)
      .getAsJsonObject().get("operation").getAsString());
  }

  @Test
  public void testIntervalExport() throws Exception {
    String operation = "operation:s:g";
    LatencyRecorders.begin(dir(), LABEL, 3_600_000);
    record(operation, 2);
    edge.millis += 1_000;
    LatencyRecorders.current().roll();
    record(operation, 3);
    edge.millis += 1_000;
    LatencyRecorders.current().roll();
    record(operation, 1);
    edge.millis += 1_000;
    LatencyRecorders.finish();

    JsonObject report = HdrFiles.json(json());
    assertEquals(intervals().getName(), report.get("intervals").getAsString());
    List<Histogram> exported = HdrFiles.hlog(intervals());
    assertEquals(3, exported.size());
    long[] expected = { 2, 3, 1 };
    long sum = 0;
    for (int i = 0; i < exported.size(); i++) {
      Histogram interval = exported.get(i);
      HdrFiles.assertPinned(interval);
      assertEquals(LatencyReport.tag(operation, LatencyRecorders.STEADY), interval.getTag());
      assertEquals(expected[i], interval.getTotalCount());
      assertEquals(T0 + 1_000 * i, interval.getStartTimeStamp());
      assertEquals(T0 + 1_000 * (i + 1), interval.getEndTimeStamp());
      sum += interval.getTotalCount();
    }
    assertEquals(T0, report.get("startMs").getAsLong());
    assertEquals(T0 + 3_000, report.get("endMs").getAsLong());
    assertEquals(6, sum);
    assertEquals(Long.valueOf(sum),
      HdrFiles.countsByTag(report).get(LatencyReport.tag(operation, LatencyRecorders.STEADY)));
    assertEquals(sum, HdrFiles.hlog(hlog()).get(0).getTotalCount());
  }

  @Test
  public void testPeriodicIntervalExport() throws Exception {
    // Restore the default environment edge so that the interval roller observes the real clock.
    EnvironmentEdgeManager.reset();
    String[] operations = { "operation:s:a", "operation:s:b" };
    LongAdder[] recorded = { new LongAdder(), new LongAdder() };
    AtomicBoolean stop = new AtomicBoolean();
    LatencyRecorders.begin(dir(), LABEL, 20);
    Thread[] threads = new Thread[4];
    for (int t = 0; t < threads.length; t++) {
      int k = t % operations.length;
      threads[t] = new Thread(() -> {
        while (!stop.get()) {
          LatencyRecorders.record(operations[k], LatencyRecorders.STEADY, 100);
          recorded[k].increment();
        }
      });
      threads[t].start();
    }
    // Run the workload for at least 200 ms and wait until the interval roller has written at least
    // three intervals for each key.
    long minEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
    long maxEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (
      System.nanoTime() < maxEnd
        && (System.nanoTime() < minEnd || intervalLines() < 3 * operations.length)
    ) {
      Thread.sleep(10);
    }
    stop.set(true);
    for (Thread thread : threads) {
      thread.join();
    }
    // Verify that the periodic roller itself, rather than only the final roll, wrote these
    // intervals.
    assertTrue(intervalLines() >= 3 * operations.length);
    LatencyRecorders.finish();

    JsonObject report = HdrFiles.json(json());
    Map<String, Long> counts = HdrFiles.countsByTag(report);
    List<Histogram> exported = HdrFiles.hlog(intervals());
    // Parse the exact timestamp bounds directly from the log text, because the bounds computed by
    // HistogramLogReader can be up to 1 ms too low due to floating-point truncation.
    List<long[]> bounds = HdrFiles.boundsMs(intervals());
    assertEquals(exported.size(), bounds.size());
    for (int k = 0; k < operations.length; k++) {
      String tag = LatencyReport.tag(operations[k], LatencyRecorders.STEADY);
      long previousEnd = report.get("startMs").getAsLong();
      int n = 0;
      long sum = 0;
      for (int i = 0; i < exported.size(); i++) {
        Histogram interval = exported.get(i);
        if (tag.equals(interval.getTag())) {
          long startMs = bounds.get(i)[0];
          long endMs = bounds.get(i)[1];
          assertTrue(tag + " interval " + n + " starts at " + startMs + " before " + previousEnd,
            startMs >= previousEnd);
          assertTrue(tag + " interval " + n + " ends before its start", endMs >= startMs);
          previousEnd = endMs;
          n++;
          sum += interval.getTotalCount();
        }
      }
      assertTrue(tag + " has " + n + " intervals", n >= 3);
      assertTrue(previousEnd <= report.get("endMs").getAsLong());
      assertEquals(recorded[k].sum(), sum);
      assertEquals(Long.valueOf(sum), counts.get(tag));
    }
  }

  // Count the histogram data lines in the interval log, which Pherf writes out at once.
  private int intervalLines() throws Exception {
    int n = 0;
    for (String line : Files.readAllLines(intervals().toPath(), StandardCharsets.UTF_8)) {
      if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("\"")) {
        n++;
      }
    }
    return n;
  }

  @Test
  public void testBeginRemovesStaleOutputs() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 3_600_000);
    record("operation:s:g", 1);
    LatencyRecorders.finish();
    assertEquals(3, dir().list().length);

    // Ensure that a JSON report from an earlier run cannot remain and pair with the logs of a new
    // run.
    LatencyRecorders.begin(dir(), LABEL, 0);
    assertEquals(0, dir().list().length);
  }

  @Test
  public void testNoIntervalsWhenDisabled() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 0);
    record("operation:s:g", 4);
    LatencyRecorders.finish();

    String[] names = dir().list();
    Arrays.sort(names);
    assertArrayEquals(new String[] { hlog().getName(), json().getName() }, names);
    assertFalse(HdrFiles.json(json()).has("intervals"));
  }

  @Test
  public void testClampAboveHighest() throws Exception {
    LatencyRecorders.begin(dir(), LABEL, 0);
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, 700_000_000L);
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, -5);
    LatencyRecorders.finish();

    Histogram histogram = HdrFiles.hlog(hlog()).get(0);
    assertEquals(2, histogram.getTotalCount());
    assertEquals(0, histogram.getMinValue());
    assertTrue(histogram.valuesAreEquivalent(600_000_000L, histogram.getMaxValue()));
  }

  @Test
  public void testRecordWithoutRunIsDropped() throws Exception {
    LatencyRecorders.record("query:s:q", LatencyRecorders.STEADY, 10);
    assertNull(LatencyRecorders.current());
    LatencyRecorders.begin(dir(), LABEL, 0);
    LatencyRecorders.finish();

    assertEquals(0, HdrFiles.json(json()).getAsJsonArray("histograms").size());
    assertTrue(HdrFiles.hlog(hlog()).isEmpty());
  }

  @Test
  public void testBadIntervalPropertyFails() throws Exception {
    // Calling begin(Properties) resolves the output directory from the Pherf properties rather
    // than from its argument.
    File hdr = new File(PherfConstants.create().getProperty("pherf.default.results.dir") + "_HDR");
    boolean existed = hdr.exists();
    for (String value : new String[] { "-5", "abc" }) {
      Properties properties = new Properties();
      properties.setProperty(LatencyRecorders.INTERVAL_PROPERTY, value);
      try {
        LatencyRecorders.begin(properties);
        fail("Accepted " + value);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(LatencyRecorders.INTERVAL_PROPERTY));
      }
      assertNull(LatencyRecorders.current());
    }
    assertEquals(existed, hdr.exists());
  }

  private static void record(String operation, int count) {
    for (int i = 0; i < count; i++) {
      LatencyRecorders.record(operation, LatencyRecorders.STEADY, 100 + i);
    }
  }

  /** An environment edge whose millisecond and microsecond values are set directly by the test. */
  private static final class ManualEdge extends EnvironmentEdge implements Micros.Source {
    volatile long millis;
    volatile long micros;

    ManualEdge(long millis) {
      this.millis = millis;
    }

    @Override
    public long currentTime() {
      return millis;
    }

    @Override
    public long currentTimeMicros() {
      return micros;
    }
  }
}
