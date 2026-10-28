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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlanClassifierTest {

  private static final String SCAN =
    "CLIENT 4-CHUNK PARALLEL 4-WAY RANGE SCAN OVER VH.DOC_CORPUS\n";

  /** The classifier identifies an HNSW search with or without a distance bound. */
  @Test
  public void testHnswSearch() {
    PlanClassifier.Classification plain =
      PlanClassifier.classify(SCAN + "    SERVER HNSW SEARCH VH.DOC_CORPUS_VIDX (64 CANDIDATES)\n");
    assertEquals(PlanClass.HNSW_SEARCH, plain.planClass());
    assertEquals(64, plain.candidates());
    assertFalse(plain.distanceBound());
    assertEquals("HNSW_SEARCH(64 candidates)", plain.toString());

    PlanClassifier.Classification bounded = PlanClassifier.classify(
      SCAN + "    SERVER HNSW SEARCH VH.DOC_CORPUS_VIDX (16 CANDIDATES, DISTANCE BOUND 0.42)\n");
    assertEquals(PlanClass.HNSW_SEARCH, bounded.planClass());
    assertEquals(16, bounded.candidates());
    assertTrue(bounded.distanceBound());
    assertEquals("HNSW_SEARCH(16 candidates, distance bound)", bounded.toString());
  }
}
