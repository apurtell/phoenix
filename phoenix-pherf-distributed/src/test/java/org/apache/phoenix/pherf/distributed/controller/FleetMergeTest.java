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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.phoenix.pherf.distributed.Bundles;
import org.apache.phoenix.pherf.distributed.FakeWorkload;
import org.apache.phoenix.pherf.distributed.Plans;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.protocol.HistogramBlob;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.verdict.HealthVerdict;
import org.apache.phoenix.pherf.verdict.VerdictSettings;
import org.junit.Test;

/**
 * Verifies RQ-235 (G10): merging the interval histograms collected across multiple agents produces
 * the exact same distribution as a single recorder that recorded all samples directly, yielding
 * identical values for total count, p50, p99, p999, and max.
 */
public class FleetMergeTest {
  private static final int VALUES = 200_000;

  private static Histogram pinned() {
    return new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
  }

  private static void assertSameDistribution(String what, Histogram expected, long count, long p50,
    long p99, long p999, long max) {
    assertEquals(what + " count", expected.getTotalCount(), count);
    assertEquals(what + " p50", expected.getValueAtPercentile(50), p50);
    assertEquals(what + " p99", expected.getValueAtPercentile(99), p99);
    assertEquals(what + " p999", expected.getValueAtPercentile(99.9), p999);
    assertEquals(what + " max", expected.getMaxValue(), max);
  }

  private static void assertSameDistribution(String what, Histogram expected, Histogram actual) {
    assertSameDistribution(what, expected, actual.getTotalCount(), actual.getValueAtPercentile(50),
      actual.getValueAtPercentile(99), actual.getValueAtPercentile(99.9), actual.getMaxValue());
  }

  /**
   * Verifies that when a single sample set is partitioned across 2 to 5 recorders and sliced into
   * intervals at random boundaries as agents transmit them, encoding each interval through the wire
   * format and merging it in the controller reproduces the distribution of a single recorder.
   */
  @Test
  public void mergeEqualsOneRecorder() throws Exception {
    for (long seed = 1; seed <= 20; seed++) {
      Random random = new Random(seed);
      RunBundle bundle = new RunBundle(new File(Simulation.runDir(), "merge-" + seed));
      Histogram reference = pinned();
      Recorder[] recorders = new Recorder[2 + random.nextInt(4)];
      for (int i = 0; i < recorders.length; i++) {
        recorders[i] = new Recorder(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
          LatencyRecorders.DIGITS);
      }
      for (int i = 0; i < VALUES; i++) {
        long value = i == 0 ? 0
          : i == 1 ? 1
          : i == 2 ? LatencyRecorders.HIGHEST_US
          : FakeWorkload.logUniform(random);
        Recorder recorder = recorders[random.nextInt(recorders.length)];
        recorder.recordValue(value);
        reference.recordValue(value);
        if (random.nextInt(5000) == 0) {
          send(bundle, recorders[random.nextInt(recorders.length)]);
        }
      }
      for (Recorder recorder : recorders) {
        send(bundle, recorder);
      }
      List<Histogram> totals = bundle.totals();
      assertEquals(1, totals.size());
      assertEquals(LatencyReport.tag(FakeWorkload.OPERATION, LatencyRecorders.STEADY),
        totals.get(0).getTag());
      assertSameDistribution("seed " + seed, reference, totals.get(0));
    }
  }

  private static void send(RunBundle bundle, Recorder recorder) throws Exception {
    Histogram interval = recorder.getIntervalHistogram();
    bundle.merge(FakeWorkload.OPERATION, LatencyRecorders.STEADY,
      HistogramBlob.decode(HistogramBlob.encode(interval)));
  }

  /**
   * Verifies that when three agents emit slices of a single seeded sample set over the protocol,
   * the resulting fleet report, merged run log, and tail latency row in the fleet verdict all match
   * a single reference recorder that recorded every value.
   */
  @Test
  public void fleetBundleEqualsOneRecorder() throws Exception {
    Simulation simulation = new Simulation();
    for (String id : new String[] { "a1", "a2", "a3" }) {
      simulation.agent(id);
    }
    simulation.runFor(1000);
    String runId = simulation.controller.submit(Plans.plan(5).runClass("measurement")
      .step(Plans.step(3).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
        .property("fake.seed", "8070").property("fake.samples", "2000")
        .property("fake.intervals", "4").property("fake.intervals.1", "6")
        .property("fake.warmup", "1"))
      .spec());
    simulation.runFor(20000);
    assertEquals(RunView.State.COMPLETE, simulation.run(runId).getState());

    // Build the reference distribution using a single recorder per key that records every value
    // across all slots.
    Map<String, Histogram> reference = new TreeMap<>();
    for (int slot = 0; slot < 3; slot++) {
      long steady = slot == 1 ? 6 : 4;
      for (String phase : new String[] { LatencyRecorders.WARMUP, LatencyRecorders.STEADY }) {
        long intervals = phase.equals(LatencyRecorders.WARMUP) ? 1 : steady;
        Histogram histogram = reference
          .computeIfAbsent(LatencyReport.tag(FakeWorkload.OPERATION, phase), k -> pinned());
        histogram.setTag(LatencyReport.tag(FakeWorkload.OPERATION, phase));
        for (long n = 1; n <= intervals; n++) {
          for (long value : FakeWorkload.values(8070, slot, phase, n, 2000)) {
            histogram.recordValue(value);
          }
        }
      }
    }
    File bundle = simulation.bundle(runId);
    JsonObject report = Bundles.report(bundle);
    List<String> keys = new ArrayList<>();
    for (JsonElement element : report.getAsJsonArray("histograms")) {
      JsonObject entry = element.getAsJsonObject();
      String tag =
        LatencyReport.tag(entry.get("operation").getAsString(), entry.get("phase").getAsString());
      keys.add(tag);
      assertSameDistribution(tag, reference.get(tag), entry.get("count").getAsLong(),
        entry.get("p50Us").getAsLong(), entry.get("p99Us").getAsLong(),
        entry.get("p999Us").getAsLong(), entry.get("maxUs").getAsLong());
    }
    // Histograms in the report are ordered by operation and then by phase.
    assertEquals(new ArrayList<>(reference.keySet()), keys);
    List<Histogram> merged = Bundles.hlog(new File(Bundles.results(bundle), "LATENCY_run.hlog"));
    assertEquals(2, merged.size());
    for (Histogram histogram : merged) {
      assertSameDistribution(histogram.getTag(), reference.get(histogram.getTag()), histogram);
      assertEquals(reference.get(histogram.getTag()), histogram);
    }
    // The tail latency verdict row is computed by the Pherf verdict engine from the merged data.
    List<Histogram> totals = new ArrayList<>(reference.values());
    VerdictSettings defaults = VerdictSettings.of(new Properties());
    assertEquals(
      HealthVerdict.tailLatency(totals, defaults.getP99MaxUs(), defaults.getP999MaxUs()).toString(),
      String.join("\t", Bundles.row(bundle, HealthVerdict.TAIL_LATENCY)));
  }
}
