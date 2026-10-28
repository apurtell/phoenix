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
package org.apache.phoenix.vectorharness.observe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource.HnswMetric;
import org.junit.Test;

public class VectorIndexMetricsObserverTest {

  private static final String INDEX = "VH.DOC_CORPUS_X_VIDX";
  private static final MetricCatalog.Def REBUILD_TIME = MetricCatalog.hnsw(HnswMetric.REBUILD_TIME);
  private static final MetricCatalog.Def PROBE_ROWS =
    MetricCatalog.hnsw(HnswMetric.PROBE_ROWS_READ);
  private static final MetricCatalog.Def STALLED =
    MetricCatalog.hnsw(HnswMetric.MAINTENANCE_STALLED);

  /** Test JmxReader returning pre-scripted server responses per sample. */
  private static final class ScriptedReader implements JmxReader {
    final Deque<Map<String, Map<String, Number>>> reads = new ArrayDeque<>();

    @Override
    public String mode() {
      return "stub";
    }

    @Override
    public Map<String, Map<String, Number>> read(String objectName) {
      return reads.isEmpty() ? Collections.emptyMap() : reads.poll();
    }

    @Override
    public void close() {
    }

    /** Enqueues sample response mapping server names to attributes, or null if unreachable. */
    ScriptedReader then(Object... serverAndAttrs) {
      Map<String, Map<String, Number>> servers = new LinkedHashMap<>();
      for (int i = 0; i < serverAndAttrs.length; i += 2) {
        @SuppressWarnings("unchecked")
        Map<String, Number> attrs = (Map<String, Number>) serverAndAttrs[i + 1];
        servers.put((String) serverAndAttrs[i], attrs);
      }
      reads.add(servers);
      return this;
    }
  }

  private static Map<String, Number> attrs(Object... kv) {
    Map<String, Number> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], (Number) kv[i + 1]);
    }
    return m;
  }

  /** Formats rebuild time histogram attributes for server scope. */
  private static Object[] rebuilds(double count, double mean, double max) {
    return new Object[] { REBUILD_TIME.countAttribute(null), count,
      REBUILD_TIME.statAttribute(null, "mean"), mean, REBUILD_TIME.statAttribute(null, "max"),
      max };
  }

  private static VectorIndexMetricsObserver observer(JmxReader reader) {
    return new VectorIndexMetricsObserver(reader, Collections.singletonList(INDEX), null, null);
  }

  @Test
  public void testHistogramStatisticsAreWeightedByTheOpsOfTheInterval() {
    String mean = REBUILD_TIME.name() + "#mean";
    String max = REBUILD_TIME.name() + "#max";
    ScriptedReader reader = new ScriptedReader()
      // Initial sample for both servers: interval deltas are unknown
      .then("a", attrs(rebuilds(1e6, 500, 900)), "b", attrs(rebuilds(1e6, 100, 100)))
      // Server a recorded 0 rebuild ops; server b recorded 10 ops
      .then("a", attrs(rebuilds(1e6, 0, 0)), "b", attrs(rebuilds(1e6 + 10, 100, 120)))
      // Neither server recorded rebuild ops during interval
      .then("a", attrs(rebuilds(1e6, 0, 0)), "b", attrs(rebuilds(1e6 + 10, 0, 0)))
      // Server b restarted (unknown interval); server a recorded 4 ops
      .then("a", attrs(rebuilds(1e6 + 4, 200, 300)), "b", attrs(rebuilds(5, 50, 60)))
      // Both servers recorded rebuild ops during interval
      .then("a", attrs(rebuilds(1e6 + 5, 400, 400)), "b", attrs(rebuilds(8, 100, 150)));
    try (VectorIndexMetricsObserver o = observer(reader)) {
      MetricSnapshot first = o.sample(null);
      assertNull(first.get(mean));
      assertNull(first.get(max));
      assertEquals(2e6, first.get(REBUILD_TIME.name()), 0.0);
      MetricSnapshot second = o.sample(null);
      assertEquals(100.0, second.get(mean), 0.0);
      assertEquals(120.0, second.get(max), 0.0);
      MetricSnapshot quiet = o.sample(null);
      assertNull("an interval without ops is unknown, not zero", quiet.get(mean));
      assertNull(quiet.get(max));
      MetricSnapshot restart = o.sample(null);
      assertEquals(200.0, restart.get(mean), 0.0);
      assertEquals(300.0, restart.get(max), 0.0);
      MetricSnapshot both = o.sample(null);
      // One op at 400 and three at 100.
      assertEquals((400.0 + 3 * 100.0) / 4, both.get(mean), 1e-12);
      assertEquals(400.0, both.get(max), 0.0);
    }
  }

  @Test
  public void testDeltasSumTheChangesOfEachServer() {
    String probe = PROBE_ROWS.scopedName(INDEX);
    String stalled = STALLED.name();
    ScriptedReader reader = new ScriptedReader()
      .then("a", attrs(PROBE_ROWS.countAttribute(INDEX), 1000, stalled, 0), "b",
        attrs(PROBE_ROWS.countAttribute(INDEX), 89000, stalled, 2))
      // Server b is unreachable
      .then("a", attrs(PROBE_ROWS.countAttribute(INDEX), 1200, stalled, 0), "b", null)
      // Server b restarted and reset counters to zero
      .then("a", attrs(PROBE_ROWS.countAttribute(INDEX), 1500, stalled, 0), "b",
        attrs(PROBE_ROWS.countAttribute(INDEX), 10, stalled, 0))
      // Server reports newly registered index metric for the first time
      .then("a", attrs(PROBE_ROWS.countAttribute(INDEX), 1500, stalled, 0), "b", attrs(
        PROBE_ROWS.countAttribute(INDEX), 10, stalled, 0, "vectorCentroidAssignments." + INDEX, 7));
    try (VectorIndexMetricsObserver o = observer(reader)) {
      MetricSnapshot start = o.sample(null);
      MetricSnapshot partial = o.sample(null);
      assertEquals(1, partial.serversMissing());
      assertNull("a server that reported one snapshot only", partial.delta(start, probe));
      assertNull(partial.delta(start, stalled));
      assertFalse(partial.deltas(start).containsKey(probe));
      MetricSnapshot restarted = o.sample(null);
      assertEquals(500.0 + 10.0, restarted.delta(start, probe), 0.0);
      assertEquals("no stall rose", 0.0, restarted.delta(start, stalled), 0.0);
      assertEquals(510.0, restarted.deltas(start).get(probe), 0.0);
      MetricSnapshot registered = o.sample(null);
      assertEquals(7.0, registered.delta(restarted, "vectorCentroidAssignments." + INDEX), 0.0);
      assertNull("absent now", restarted.delta(registered, "vectorCentroidAssignments." + INDEX));
    }
  }
}
