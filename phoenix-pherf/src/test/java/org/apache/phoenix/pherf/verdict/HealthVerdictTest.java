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
package org.apache.phoenix.pherf.verdict;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.HdrHistogram.Histogram;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.apache.phoenix.pherf.latency.LatencyReport;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Pool;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Reads;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Row;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Schedule;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Severity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies requirements RQ-201 through RQ-206 by exercising the five health verdict checks against
 * synthetic run data, along with the noisy-host evaluation rule and verdict file generation.
 * Because each check is implemented as a pure function, every test input can be specified with
 * exact values.
 */
public class HealthVerdictTest {
  private static final VerdictSettings DEFAULTS = VerdictSettings.of(new Properties());
  private static final long MS = 1000;
  private static final Set<String> CLASSES =
    new HashSet<>(Arrays.asList("count", "share", "duration", "none"));

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /**
   * Builds a synthetic schedule metric snapshot whose peak in-flight operation count reached the
   * configured concurrency limit.
   */
  private static Schedule schedule(int set, long durationMs, long elapsedMs, long scheduled,
    long completed, long late, long dropped, int concurrency, int maxQueue, long maxDriftUs) {
    return new Schedule("scenario s query set " + set, concurrency, durationMs, elapsedMs,
      scheduled, completed, late, dropped, maxQueue, concurrency, maxDriftUs);
  }

  /**
   * Returns a healthy steady-state schedule that completes 1000 of 1000 scheduled operations in 10
   * s, with a peak lag of 300 us and a peak queue depth of 1 out of 4.
   */
  private static Schedule steady() {
    return schedule(1, 10_000, 10_000, 1000, 1000, 0, 0, 4, 1, 300);
  }

  /**
   * Creates a histogram initialized with the pinned recorder configuration used by the latency
   * report and populated with the supplied count-and-value pairs.
   */
  private static Histogram histogram(long[]... countAndValue) {
    Histogram histogram = new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
    for (long[] pair : countAndValue) {
      histogram.recordValueWithCount(pair[1], pair[0]);
    }
    return histogram;
  }

  private static long[] n(long count, long micros) {
    return new long[] { count, micros };
  }

  /**
   * Assembles the run totals of the latency report as a list of histograms in which each histogram
   * carries its metric key as its tag.
   */
  private static List<Histogram> totals(Object... keyAndHistogram) {
    List<Histogram> totals = new ArrayList<>();
    for (int i = 0; i < keyAndHistogram.length; i += 2) {
      totals.add(tagged((String) keyAndHistogram[i], (Histogram) keyAndHistogram[i + 1]));
    }
    return totals;
  }

  private static Histogram tagged(String key, Histogram histogram) {
    histogram.setTag(key);
    return histogram;
  }

  private static String steadyKey(String operation) {
    return LatencyReport.tag(operation, LatencyRecorders.STEADY);
  }

  private static Map<String, Long> reads(long... counts) {
    Map<String, Long> reads = new LinkedHashMap<>();
    for (int i = 0; i < counts.length; i++) {
      reads.put("http://rs" + i + ":16030", counts[i]);
    }
    return reads;
  }

  private static VerdictSettings settings(String... keyAndValue) {
    Properties properties = new Properties();
    for (int i = 0; i < keyAndValue.length; i += 2) {
      properties.setProperty(keyAndValue[i], keyAndValue[i + 1]);
    }
    return VerdictSettings.of(properties);
  }

  private static Row row(List<Row> rows, String check) {
    for (Row row : rows) {
      if (row.getCheck().equals(check)) {
        return row;
      }
    }
    throw new AssertionError("No row " + check + " in " + rows);
  }

  private static void assertSeverity(Severity expected, Row row) {
    assertEquals(row.toString(), expected, row.getSeverity());
  }

  @Test
  public void testSeverityLatticeOrder() {
    assertArrayEquals(new Severity[] { Severity.PASS, Severity.SKIP, Severity.ADVISORY,
      Severity.FAIL, Severity.ERROR }, Severity.values());
  }

  /**
   * Verifies RQ-201: when tasks wait for an available client pool thread even though the workload
   * schedule keeps pace, the client pool check fails while the saturation check passes.
   */
  @Test
  public void testClientExhaustionWithoutLagGrowth() {
    Pool pool = new Pool(4, 10, 3, 7);
    Row client = HealthVerdict.clientPool(pool);
    assertSeverity(Severity.FAIL, client);
    assertEquals("count", client.getEvidence());
    assertTrue(client.getDetail(), client.getDetail().contains("3 of 10 tasks waited"));
    assertTrue(client.getDetail(), client.getDetail().contains("pherf.default.threadpool"));
    assertTrue(client.getDetail(), client.getDetail().contains("7"));
    Row saturation =
      HealthVerdict.saturation(Collections.singletonList(steady()), DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.PASS, saturation);

    List<Row> rows = HealthVerdict.evaluate(Collections.singletonList(steady()), pool, totals(),
      Reads.skip("no source"), DEFAULTS);
    assertSeverity(Severity.FAIL, row(rows, HealthVerdict.CLIENT_POOL));
    assertSeverity(Severity.PASS, row(rows, HealthVerdict.SATURATION));
  }

  /**
   * Verifies RQ-201: the inverse scenario, where schedule lag grows while no task waits on the
   * client thread pool, produces the opposite outcome across the saturation and client pool checks.
   */
  @Test
  public void testLagGrowthWithoutClientExhaustion() {
    Pool pool = new Pool(4, 10, 0, 4);
    Schedule lagged = schedule(1, 10_000, 14_000, 1000, 1000, 0, 0, 4, 4, 4_000 * MS);
    Row saturation =
      HealthVerdict.saturation(Collections.singletonList(lagged), DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.FAIL, saturation);
    assertEquals("duration", saturation.getEvidence());
    assertTrue(saturation.getDetail(), saturation.getDetail().contains("4 of 4"));
    assertTrue(saturation.getDetail(), saturation.getDetail().contains("at its bound"));
    Row client = HealthVerdict.clientPool(pool);
    assertSeverity(Severity.PASS, client);

    List<Row> rows = HealthVerdict.evaluate(Collections.singletonList(lagged), pool, totals(),
      Reads.skip("no source"), DEFAULTS);
    assertSeverity(Severity.PASS, row(rows, HealthVerdict.CLIENT_POOL));
    assertSeverity(Severity.FAIL, row(rows, HealthVerdict.SATURATION));
  }

  @Test
  public void testClientPoolSkipsWithoutTasks() {
    assertSeverity(Severity.SKIP, HealthVerdict.clientPool(new Pool(10, 0, 0, 0)));
  }

  /**
   * Verifies RQ-202: completing 1000 of 1000 operations in 10,050 ms of a 10,000 ms window yields
   * an attainment rate of 99.5 % and satisfies the target threshold.
   */
  @Test
  public void testAttainmentMeetsTarget() {
    Row row = HealthVerdict.attainment(
      Collections.singletonList(schedule(1, 10_000, 10_050, 1000, 1000, 0, 0, 4, 1, 300)),
      DEFAULTS.getAttainmentMinPct());
    assertSeverity(Severity.PASS, row);
    assertEquals("duration", row.getEvidence());
    assertTrue(row.getDetail(), row.getDetail().contains("99.5%"));
  }

  /**
   * Verifies RQ-202: dropped operations, a late-finishing run, and an early halt each achieve only
   * half of the target throughput rate and therefore fail the attainment check.
   */
  @Test
  public void testAttainmentAtHalfTargetFails() {
    double minPct = DEFAULTS.getAttainmentMinPct();
    // Under the DROP policy, only 500 of 1000 scheduled operations execute within roughly the
    // target window.
    Row drop = HealthVerdict.attainment(
      Collections.singletonList(schedule(1, 10_000, 10_010, 1000, 500, 0, 500, 4, 4, 3000 * MS)),
      minPct);
    assertSeverity(Severity.FAIL, drop);
    // Under the LATE policy, all 1000 scheduled operations execute, but they require 20 s to
    // finish.
    Row late = HealthVerdict.attainment(
      Collections.singletonList(schedule(1, 10_000, 20_000, 1000, 1000, 900, 0, 4, 4, 9000 * MS)),
      minPct);
    assertSeverity(Severity.FAIL, late);
    assertTrue(late.getDetail(), late.getDetail().contains("50.0%"));
    // When execution halts after 5 s, only 250 of the 500 operations due by that point have
    // completed.
    Row halt = HealthVerdict.attainment(
      Collections.singletonList(schedule(1, 10_000, 5_000, 500, 250, 0, 0, 4, 4, 2000 * MS)),
      minPct);
    assertSeverity(Severity.FAIL, halt);
    assertTrue(halt.getDetail(), halt.getDetail().contains("50.0%"));

    // The resulting verdict row identifies the worst-performing schedule across the run.
    Row worst = HealthVerdict.attainment(
      Arrays.asList(steady(), schedule(2, 10_000, 10_000, 1000, 500, 0, 500, 4, 4, 3000 * MS)),
      minPct);
    assertSeverity(Severity.FAIL, worst);
    assertTrue(worst.getDetail(), worst.getDetail().contains("scenario s query set 2"));
    // Attainment exactly at the configured minimum threshold evaluates to PASS.
    assertSeverity(Severity.PASS, HealthVerdict.attainment(
      Collections.singletonList(schedule(1, 10_000, 10_000, 1000, 900, 0, 100, 4, 4, 0)), minPct));
  }

  /**
   * Verifies RQ-202: a closed-loop execution has no pacing schedule, causing the verdict file to
   * record a SKIP status for the schedule-dependent checks.
   */
  @Test
  public void testClosedLoopAttainmentSkipsInTheFile() throws Exception {
    List<Row> rows = HealthVerdict.evaluate(Collections.<Schedule> emptyList(),
      new Pool(10, 3, 0, 2), totals(), Reads.skip("no source"), DEFAULTS);
    File dir = folder.newFolder("closed");
    Path file = HealthVerdict.write(dir, "c", rows);
    assertEquals(new File(dir, "VERDICT_c.tsv").toPath(), file);
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertTrue(lines.get(0),
      lines.get(0).startsWith("pherf.health.attainment\tSKIP\tduration\tpherf\t"));
    assertTrue(lines.get(1),
      lines.get(1).startsWith("pherf.health.saturation\tSKIP\tduration\tpherf\t"));

    // A schedule that never started because execution halted during warmup is treated as not
    // having run.
    Schedule warmupOnly = schedule(1, 10_000, 0, 0, 0, 0, 0, 4, 0, 0);
    assertSeverity(Severity.SKIP,
      HealthVerdict.attainment(Collections.singletonList(warmupOnly), 90));
    assertSeverity(Severity.SKIP,
      HealthVerdict.saturation(Collections.singletonList(warmupOnly), DEFAULTS.getLagMaxUs()));
  }

  /**
   * Verifies RQ-203: a schedule whose lag grows to 2.5 s while the dispatch queue reaches capacity
   * fails the saturation check.
   */
  @Test
  public void testGrowingLagAndQueueFail() {
    Row row = HealthVerdict.saturation(
      Collections.singletonList(schedule(1, 10_000, 12_500, 1000, 1000, 400, 0, 8, 8, 2_500 * MS)),
      DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.FAIL, row);
    assertTrue(row.getDetail(), row.getDetail().contains("2500 ms"));
    assertTrue(row.getDetail(), row.getDetail().contains("8 of 8"));
    assertTrue(row.getDetail(), row.getDetail().contains("workers could not keep up"));
    assertTrue(row.getDetail(), row.getDetail().contains("late 400"));

    // Even when schedule lag grows while the queue remains below its capacity bound, having all
    // 8 workers busy at peak concurrency attributes the bottleneck to the cluster.
    Row busy =
      HealthVerdict.saturation(Collections.singletonList(new Schedule("scenario s query set 1", 8,
        10_000, 12_500, 1000, 1000, 0, 0, 2, 8, 2_500 * MS)), DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.FAIL, busy);
    assertTrue(busy.getDetail(), busy.getDetail().contains("in-flight peak 8 of 8"));
    assertTrue(busy.getDetail(), busy.getDetail().contains("the workers were busy"));
    assertFalse(busy.getDetail(), busy.getDetail().contains("dispatcher"));

    // When at least one worker remains free throughout the run, the bottleneck is attributed to
    // the client because the dispatcher itself fell behind schedule.
    Row dispatcher =
      HealthVerdict.saturation(Collections.singletonList(new Schedule("scenario s query set 1", 8,
        10_000, 12_500, 1000, 1000, 0, 0, 2, 3, 2_500 * MS)), DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.FAIL, dispatcher);
    assertTrue(dispatcher.getDetail(), dispatcher.getDetail().contains("in-flight peak 3 of 8"));
    assertTrue(dispatcher.getDetail(), dispatcher.getDetail().contains("the dispatcher was late"));
    assertFalse(dispatcher.getDetail(), dispatcher.getDetail().contains("workers were busy"));
  }

  /**
   * Verifies RQ-203: a schedule whose lag stays within the allowed bound passes the saturation
   * check, even if the dispatch queue reaches its capacity limit.
   */
  @Test
  public void testSteadyScheduleRuns() {
    Row row = HealthVerdict.saturation(
      Collections.singletonList(schedule(1, 10_000, 10_000, 1000, 1000, 0, 0, 8, 1, 500)),
      DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.PASS, row);
    assertTrue(row.getDetail(), row.getDetail().contains("1 of 8"));
    Row full = HealthVerdict.saturation(
      Collections.singletonList(schedule(1, 10_000, 10_000, 1000, 1000, 0, 0, 8, 8, 500)),
      DEFAULTS.getLagMaxUs());
    assertSeverity(Severity.PASS, full);
    assertTrue(full.getDetail(), full.getDetail().contains("at its bound"));
  }

  @Test
  public void testLagAtTheBoundPasses() {
    long bound = DEFAULTS.getLagMaxUs();
    assertSeverity(Severity.PASS,
      HealthVerdict.saturation(
        Collections.singletonList(schedule(1, 10_000, 10_000, 1000, 1000, 0, 0, 4, 4, bound)),
        bound));
    assertSeverity(Severity.FAIL,
      HealthVerdict.saturation(
        Collections.singletonList(schedule(1, 10_000, 10_000, 1000, 1000, 0, 0, 4, 4, bound + 1)),
        bound));
  }

  /**
   * Verifies RQ-204: recording 20 out of 1000 latency samples at 2 s pushes the p99 latency above
   * the 1 s threshold and causes the tail latency check to fail.
   */
  @Test
  public void testTailP99OverFails() {
    Histogram q = histogram(n(980, 1000), n(20, 2_000_000));
    assertTrue(q.getValueAtPercentile(99) > DEFAULTS.getP99MaxUs());
    assertTrue(q.getValueAtPercentile(99.9) <= DEFAULTS.getP999MaxUs());
    Row row = HealthVerdict.tailLatency(
      totals(steadyKey("query:s:q"), q, steadyKey("query:s:fast"), histogram(n(1000, 1000))),
      DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs());
    assertSeverity(Severity.FAIL, row);
    assertEquals("duration", row.getEvidence());
    assertTrue(row.getDetail(), row.getDetail().contains("query:s:q"));
    assertTrue(row.getDetail(), row.getDetail().contains("1 of 2 keys"));
  }

  /**
   * Verifies RQ-204: recording 5 out of 1000 latency samples at 6 s leaves p99 within bounds but
   * pushes p99.9 above the 5 s threshold, failing the tail latency check.
   */
  @Test
  public void testTailP999OverFails() {
    Histogram q = histogram(n(995, 1000), n(5, 6_000_000));
    assertTrue(q.getValueAtPercentile(99) <= DEFAULTS.getP99MaxUs());
    assertTrue(q.getValueAtPercentile(99.9) > DEFAULTS.getP999MaxUs());
    assertSeverity(Severity.FAIL, HealthVerdict.tailLatency(totals(steadyKey("query:s:q"), q),
      DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs()));
  }

  /**
   * Verifies RQ-204: when every sample across each measured operation kind completes in 1 ms, all
   * percentiles remain within both the p99 and p99.9 latency bounds.
   */
  @Test
  public void testTailWithinBothPasses() {
    List<Histogram> totals = totals();
    List<Histogram> slow = totals();
    for (String kind : new String[] { "query", "scan", "delete", "response", "operation" }) {
      totals.add(tagged(steadyKey(kind + ":s:q"), histogram(n(1000, 1000))));
      slow.add(tagged(steadyKey(kind + ":s:q"), histogram(n(1000, 3_000_000))));
    }
    Row row = HealthVerdict.tailLatency(totals, DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs());
    assertSeverity(Severity.PASS, row);
    assertTrue(row.getDetail(), row.getDetail().contains("5 keys"));

    // When those same operation kinds exceed the latency bounds, every one of them is counted
    // toward the failure total.
    Row over = HealthVerdict.tailLatency(slow, DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs());
    assertSeverity(Severity.FAIL, over);
    assertTrue(over.getDetail(), over.getDetail().contains("5 of 5 keys"));
  }

  /**
   * Verifies RQ-204: a percentile latency exactly equal to the configured bound passes, whereas
   * tightening the bound to 1 us below that value causes the check to fail.
   */
  @Test
  public void testTailAtTheBoundPasses() {
    Histogram q = histogram(n(1000, 2_000_000));
    long p99 = q.getValueAtPercentile(99);
    long p999 = q.getValueAtPercentile(99.9);
    VerdictSettings exact =
      settings(VerdictSettings.P99_MAX_MS, BigDecimal.valueOf(p99, 3).toPlainString(),
        VerdictSettings.P999_MAX_MS, BigDecimal.valueOf(p999, 3).toPlainString());
    assertEquals(p99, exact.getP99MaxUs());
    List<Histogram> totals = totals(steadyKey("query:s:q"), q);
    assertSeverity(Severity.PASS,
      HealthVerdict.tailLatency(totals, exact.getP99MaxUs(), exact.getP999MaxUs()));
    assertSeverity(Severity.FAIL,
      HealthVerdict.tailLatency(totals, exact.getP99MaxUs() - 1, exact.getP999MaxUs()));
    assertSeverity(Severity.FAIL,
      HealthVerdict.tailLatency(totals, exact.getP99MaxUs(), exact.getP999MaxUs() - 1));
  }

  /**
   * Verifies RQ-204: data-load and warmup metric keys are excluded from tail latency evaluation,
   * and the check returns SKIP when no eligible steady-state keys are present.
   */
  @Test
  public void testTailIgnoresLoadAndWarmup() {
    Histogram slow = histogram(n(1000, 60_000_000));
    List<Histogram> totals = totals(steadyKey("load:s:T"), slow,
      LatencyReport.tag("query:s:q", LatencyRecorders.WARMUP), slow.copy());
    assertSeverity(Severity.SKIP,
      HealthVerdict.tailLatency(totals, DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs()));
    assertSeverity(Severity.SKIP,
      HealthVerdict.tailLatency(totals(), DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs()));
    totals.add(tagged(steadyKey("query:s:q"), histogram(n(1000, 1000))));
    assertSeverity(Severity.PASS,
      HealthVerdict.tailLatency(totals, DEFAULTS.getP99MaxUs(), DEFAULTS.getP999MaxUs()));
  }

  /**
   * Verifies RQ-205: an unevenly distributed read workload fails the read skew check, whereas a
   * balanced distribution across servers passes.
   */
  @Test
  public void testReadSkew() {
    double max = DEFAULTS.getReadSkewMax();
    Row skewed = HealthVerdict.readSkew(Reads.of(reads(900, 50, 50)), max);
    assertSeverity(Severity.FAIL, skewed);
    assertEquals("share", skewed.getEvidence());
    assertTrue(skewed.getDetail(), skewed.getDetail().contains("http://rs0:16030"));
    assertTrue(skewed.getDetail(), skewed.getDetail().contains("2.70"));
    assertSeverity(Severity.PASS, HealthVerdict.readSkew(Reads.of(reads(300, 310, 290)), max));
    // A server that served zero reads is still included when computing the cluster-wide mean.
    assertSeverity(Severity.FAIL, HealthVerdict.readSkew(Reads.of(reads(100, 0)), max));
  }

  /**
   * Verifies RQ-205: the read skew check returns SKIP when skew analysis is inapplicable and
   * returns ERROR when a RegionServer metric source cannot be read.
   */
  @Test
  public void testReadSkewSkipAndError() {
    double max = DEFAULTS.getReadSkewMax();
    assertSeverity(Severity.SKIP, HealthVerdict.readSkew(Reads.skip("no source"), max));
    assertSeverity(Severity.SKIP, HealthVerdict.readSkew(Reads.of(reads(500)), max));
    assertSeverity(Severity.SKIP, HealthVerdict.readSkew(Reads.of(reads(0, 0, 0)), max));
    long window = HealthVerdict.MIN_READ_WINDOW_MS;
    Row back = HealthVerdict.readSkew(Reads.between(reads(100, 100), reads(50, 400), window), max);
    assertSeverity(Severity.SKIP, back);
    assertTrue(back.getDetail(), back.getDetail().contains("http://rs0:16030"));
    Row error = HealthVerdict.readSkew(Reads.error("cannot read http://rs0:16030"), max);
    assertSeverity(Severity.ERROR, error);
    assertTrue(error.getDetail(), error.getDetail().contains("http://rs0:16030"));

    // Calling Reads.between computes the per-server read count deltas across the observation
    // window.
    Row deltas = HealthVerdict
      .readSkew(Reads.between(reads(100, 100, 100), reads(1000, 150, 150), window), max);
    assertSeverity(Severity.FAIL, deltas);
    assertTrue(deltas.getDetail(),
      deltas.getDetail().contains("http://rs0:16030 has 900 of 1000 reads on 3 servers"));
  }

  /**
   * Because a RegionServer JMX counter snapshot can lag by roughly 15 s, any observation window
   * shorter than 60 s is skipped rather than graded, even when the underlying read distribution is
   * heavily skewed.
   */
  @Test
  public void testShortReadWindowSkips() {
    double max = DEFAULTS.getReadSkewMax();
    long window = HealthVerdict.MIN_READ_WINDOW_MS;
    assertEquals(60_000, window);
    Row skewed =
      HealthVerdict.readSkew(Reads.between(reads(0, 0, 0), reads(900, 50, 50), window - 1), max);
    assertSeverity(Severity.SKIP, skewed);
    assertTrue(skewed.getDetail(), skewed.getDetail().contains("59999 ms"));
    assertSeverity(Severity.FAIL,
      HealthVerdict.readSkew(Reads.between(reads(0, 0, 0), reads(900, 50, 50), window), max));
  }

  /**
   * Verifies RQ-206: enabling the noisy-host flag downgrades any FAIL result on a timing-based
   * check to ADVISORY, while count-based and share-based checks retain FAIL, ERROR remains ERROR,
   * and PASS and SKIP results remain unchanged.
   */
  @Test
  public void testNoisyHostDowngradesTimingChecks() {
    VerdictSettings noisy = settings(VerdictSettings.NOISY_HOST, "true");
    List<Schedule> bad =
      Collections.singletonList(schedule(1, 10_000, 20_000, 1000, 500, 0, 500, 4, 4, 9000 * MS));
    Pool waited = new Pool(2, 10, 3, 5);
    List<Histogram> slow = totals(steadyKey("query:s:q"), histogram(n(1000, 9_000_000)));
    Reads skewed = Reads.of(reads(900, 50, 50));

    // When only the tail latency check is triggered, it evaluates to FAIL without the noisy-host
    // flag and is downgraded to ADVISORY when the flag is enabled.
    List<Schedule> none = Collections.emptyList();
    Pool idle = new Pool(2, 1, 0, 1);
    Reads noSource = Reads.skip("no source");
    assertSeverity(Severity.FAIL, row(HealthVerdict.evaluate(none, idle, slow, noSource, DEFAULTS),
      HealthVerdict.TAIL_LATENCY));
    Row advisory =
      row(HealthVerdict.evaluate(none, idle, slow, noSource, noisy), HealthVerdict.TAIL_LATENCY);
    assertSeverity(Severity.ADVISORY, advisory);
    assertTrue(advisory.getDetail(), advisory.getDetail().contains("noisy"));

    List<Row> quiet = HealthVerdict.evaluate(bad, waited, slow, skewed, DEFAULTS);
    for (Row row : quiet) {
      assertSeverity(Severity.FAIL, row);
    }
    List<Row> rows = HealthVerdict.evaluate(bad, waited, slow, skewed, noisy);
    assertSeverity(Severity.ADVISORY, row(rows, HealthVerdict.ATTAINMENT));
    assertSeverity(Severity.ADVISORY, row(rows, HealthVerdict.SATURATION));
    assertSeverity(Severity.ADVISORY, row(rows, HealthVerdict.TAIL_LATENCY));
    assertSeverity(Severity.FAIL, row(rows, HealthVerdict.CLIENT_POOL));
    assertSeverity(Severity.FAIL, row(rows, HealthVerdict.READ_SKEW));

    // Verdict rows with ERROR, PASS, or SKIP severities remain identical regardless of whether the
    // noisy-host flag is set.
    Reads broken = Reads.error("cannot read http://rs0:16030");
    List<Schedule> good = Collections.singletonList(steady());
    Pool fine = new Pool(4, 10, 0, 4);
    List<Histogram> fast = totals(steadyKey("query:s:q"), histogram(n(1000, 1000)));
    List<Row> plain = HealthVerdict.evaluate(good, fine, fast, broken, DEFAULTS);
    List<Row> flagged = HealthVerdict.evaluate(good, fine, fast, broken, noisy);
    assertSeverity(Severity.ERROR, row(flagged, HealthVerdict.READ_SKEW));
    assertEquals(plain.toString(), flagged.toString());
    List<Row> skips = HealthVerdict.evaluate(none, new Pool(2, 0, 0, 0), totals(), noSource, noisy);
    for (Row row : skips) {
      assertSeverity(Severity.SKIP, row);
    }
  }

  /**
   * Verifies that the written verdict file conforms to the harness verdict stream format, emitting
   * five check rows in a fixed canonical order with five tab-separated fields per row.
   */
  @Test
  public void testFileIsAHarnessVerdictStream() throws Exception {
    List<Row> rows = HealthVerdict.evaluate(Collections.singletonList(steady()),
      new Pool(4, 10, 3, 7), totals(steadyKey("query:s:q"), histogram(n(1000, 1000))),
      Reads.error("cannot read\thttp://rs0:16030\r\nsecond line"), DEFAULTS);
    File dir = folder.newFolder("stream");
    // Any pre-existing verdict file for the same run label is overwritten in place.
    Files.write(new File(dir, "VERDICT_v.tsv").toPath(), "old\n".getBytes(StandardCharsets.UTF_8));
    Path file = HealthVerdict.write(dir, "v", rows);
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(text.endsWith("\n"));
    assertFalse(text.contains("\r"));
    String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
    assertEquals(HealthVerdict.CHECKS.size(), lines.length);
    assertEquals(
      Arrays.asList("pherf.health.attainment", "pherf.health.saturation",
        "pherf.health.client_pool", "pherf.health.tail_latency", "pherf.health.read_skew"),
      HealthVerdict.CHECKS);
    Set<String> severities = new HashSet<>();
    for (Severity severity : Severity.values()) {
      severities.add(severity.name());
    }
    for (int i = 0; i < lines.length; i++) {
      String[] fields = lines[i].split("\t", -1);
      assertEquals(lines[i], 5, fields.length);
      assertEquals(HealthVerdict.CHECKS.get(i), fields[0]);
      assertTrue(lines[i], severities.contains(fields[1]));
      assertTrue(lines[i], CLASSES.contains(fields[2]));
      assertEquals(HealthVerdict.SOURCE, fields[3]);
      assertFalse(lines[i], fields[4].isEmpty());
    }
    assertTrue(lines[4], lines[4].startsWith("pherf.health.read_skew\tERROR\tshare\tpherf\t"));
    assertTrue(lines[4], lines[4].contains("cannot read http://rs0:16030  second line"));
    assertEquals(Collections.singletonList("VERDICT_v.tsv"), Arrays.asList(dir.list()));
  }
}
