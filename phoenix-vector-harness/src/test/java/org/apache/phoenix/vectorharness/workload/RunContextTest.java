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
package org.apache.phoenix.vectorharness.workload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.LiveCorpus;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.junit.Test;

public class RunContextTest {

  @Test
  public void testDistanceBoundTruthFollowsTheUnfilteredTruthOfOtherRows() throws Exception {
    Properties p = new Properties();
    p.setProperty("corpus.rows", "100");
    p.setProperty("corpus.dim", "4");
    p.setProperty("corpus.clusters", "2");
    try (HarnessContext ctx = HarnessContext.open(HarnessConfig.of("t", p))) {
      LiveCorpus live = new LiveCorpus(ctx.corpus(), 100);
      try (RunContext rc = new RunContext(ctx, null, EnumSet.of(TableLayout.CORPUS), null, null,
        null, live, Collections.emptyList(), Collections.emptyList(), null, null)) {
        NeighborList unfiltered =
          new NeighborList(new long[] { 5, 7, 9, 11 }, new double[] { 0.1, 0.2, 0.3, 0.4 }, true);
        // A condition planned on the truth of other rows, such as the prefix before a stream.
        QueryCondition planned =
          QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(0.25, 10).build();
        QueryCondition known =
          QueryCondition.builder(QueryShape.DISTANCE_BOUND).radius(0.35, 10).build();
        NeighborList knownList = new NeighborList(new long[] { 5 }, new double[] { 0.1 }, true);
        Map<String, NeighborList> lists = new LinkedHashMap<>();
        lists.put(TruthManager.UNFILTERED_KEY, unfiltered);
        lists.put(known.key(), knownList);
        rc.staticTruth(
          new GroundTruth("fp", "panel", 100, "L2", 10, Collections.singletonList(lists)));
        NeighborList t = rc.truth(0, planned, 10);
        assertArrayEquals(new long[] { 5, 7 }, ordinals(t));
        assertEquals(0.2, t.distance(1), 0.0);
        // A key that the truth holds is taken as it is.
        assertSame(knownList, rc.truth(0, known, 10));
        // Truth over other rows than the live ones is not exact.
        rc.staticTruth(
          new GroundTruth("fp", "panel", 50, "L2", 10, Collections.singletonList(lists)));
        assertNull(rc.truth(0, planned, 10));
      }
    }
  }

  private static long[] ordinals(NeighborList l) {
    long[] o = new long[l.size()];
    for (int i = 0; i < o.length; i++) {
      o[i] = l.ordinal(i);
    }
    return o;
  }
}
