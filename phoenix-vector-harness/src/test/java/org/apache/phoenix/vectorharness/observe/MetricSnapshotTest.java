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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class MetricSnapshotTest {

  private static final String ASSIGN = "vectorCentroidAssignments";
  private static final String REGIONS = "vectorHnswRegions";
  private static final String INDEX = "VH.DOC_CORPUS_X_VIDX";

  private static MetricSnapshot snapshot(int reporting, int missing, Object... kv) {
    Map<String, Double> values = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      values.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
    }
    return new MetricSnapshot(0L, null, values, reporting, missing);
  }

  @Test
  public void testDeltasOfKnownValues() {
    MetricSnapshot before = snapshot(1, 0, ASSIGN, 10, ASSIGN + "." + INDEX, 4, REGIONS, 2);
    MetricSnapshot after = snapshot(1, 0, ASSIGN, 15, ASSIGN + "." + INDEX, 9, REGIONS, 3);
    Map<String, Double> d = after.deltas(before);
    assertEquals(5.0, d.get(ASSIGN), 0.0);
    assertEquals(5.0, d.get(ASSIGN + "." + INDEX), 0.0);
    // Gauges have no deltas.
    assertFalse(d.containsKey(REGIONS));
    assertTrue(after.deltas(null).isEmpty());
    assertNull(after.delta(null, ASSIGN));
  }

  @Test
  public void testIndexValuesRegisteredInBetweenCountFromZero() {
    // An index created after the earlier snapshot, as during a build, has no values before.
    MetricSnapshot before = snapshot(1, 0, ASSIGN, 10);
    MetricSnapshot after = snapshot(1, 0, ASSIGN, 12, ASSIGN + "." + INDEX, 7,
      "vectorHnswRebuildTime." + INDEX, 2, "vectorHnswRebuildTime." + INDEX + "#max", 30);
    Map<String, Double> d = after.deltas(before);
    assertEquals(2.0, d.get(ASSIGN), 0.0);
    assertEquals(7.0, d.get(ASSIGN + "." + INDEX), 0.0);
    assertEquals(2.0, d.get("vectorHnswRebuildTime." + INDEX), 0.0);
    assertEquals(7.0, after.delta(before, ASSIGN + "." + INDEX), 0.0);
  }

  @Test
  public void testMissingValuesStayUnknown() {
    // A server value absent before is unknown, for example during a rolling upgrade.
    MetricSnapshot before = snapshot(1, 0);
    MetricSnapshot after = snapshot(1, 0, ASSIGN, 12, ASSIGN + "." + INDEX, 7);
    assertNull(after.delta(before, ASSIGN));
    assertFalse(after.deltas(before).containsKey(ASSIGN));
    // A server that did not report the earlier snapshot makes every absent value unknown.
    MetricSnapshot partial = snapshot(1, 1);
    assertNull(after.delta(partial, ASSIGN + "." + INDEX));
    MetricSnapshot none = snapshot(0, 0);
    assertNull(after.delta(none, ASSIGN + "." + INDEX));
    // A value absent now is unknown whatever the earlier snapshot holds.
    assertNull(before.delta(after, ASSIGN + "." + INDEX));
  }

  @Test
  public void testIsIndexValue() {
    assertTrue(MetricSnapshot.isIndexValue(ASSIGN + "." + INDEX));
    assertTrue(MetricSnapshot.isIndexValue("vectorHnswSearchTime." + INDEX + "#max"));
    assertFalse(MetricSnapshot.isIndexValue(ASSIGN));
    assertFalse(MetricSnapshot.isIndexValue("vectorHnswSearchTime#99th.percentile"));
  }
}
