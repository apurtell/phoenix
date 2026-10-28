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
package org.apache.phoenix.vectorharness.truth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class QueryScoreTest {

  @Test
  public void testAccessors() {
    QueryScore s = new QueryScore(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 11, 12, 13);
    assertEquals(0.1, s.recall(), 0.0);
    assertEquals(0.2, s.precision(), 0.0);
    assertEquals(0.3, s.completeness(), 0.0);
    assertEquals(0.4, s.recallAt1(), 0.0);
    assertEquals(0.5, s.reciprocalRank(), 0.0);
    assertEquals(0.6, s.ndcg(), 0.0);
    assertEquals(0.7, s.distanceRatio(), 0.0);
    assertEquals(0.8, s.rangePrecision(), 0.0);
    assertEquals(0.9, s.rangeRecall(), 0.0);
    assertEquals(11, s.returned());
    assertEquals(12, s.truthSize());
    assertEquals(13, s.excluded());
  }

  @Test
  public void testValuesFollowNames() {
    QueryScore s = new QueryScore(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 11, 12, 13);
    assertArrayEquals(new String[] { "recall", "precision", "completeness", "recall1_at_k", "mrr",
      "ndcg", "distance_ratio", "range_precision", "range_recall" }, QueryScore.NAMES);
    double[] v = s.values();
    assertEquals(QueryScore.NAMES.length, v.length);
    assertArrayEquals(new double[] { s.recall(), s.precision(), s.completeness(), s.recallAt1(),
      s.reciprocalRank(), s.ndcg(), s.distanceRatio(), s.rangePrecision(), s.rangeRecall() }, v,
      0.0);
    Set<String> names = new HashSet<>();
    for (String n : QueryScore.NAMES) {
      names.add(n);
    }
    assertEquals(QueryScore.NAMES.length, names.size());
  }

  @Test
  public void testValuesAreACopy() {
    QueryScore s = new QueryScore(1, 1, 1, 1, 1, 1, 1, Double.NaN, Double.NaN, 1, 1, 0);
    double[] v = s.values();
    v[0] = 0.0;
    assertNotSame(v, s.values());
    assertEquals(1.0, s.values()[0], 0.0);
    assertEquals(1.0, s.recall(), 0.0);
    assertEquals(Double.NaN, s.values()[7], 0.0);
  }
}
