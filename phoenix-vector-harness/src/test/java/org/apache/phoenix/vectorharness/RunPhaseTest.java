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
package org.apache.phoenix.vectorharness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.phoenix.vectorharness.result.Manifest;
import org.junit.Test;

public class RunPhaseTest {

  @Test
  public void testHealthyValidityRaisesNoFlag() {
    assertTrue(RunPhase.validityFlags(1.0, 20, 20, 0, 0, 1.0, 0).isEmpty());
  }

  @Test
  public void testValidityWithoutScoredQueriesIsFlagged() {
    // Every exact query timed out: the recall is NaN, which is not a pass.
    List<String> flags = RunPhase.validityFlags(Double.NaN, 0, 20, 20, 0, 1.0, 0);
    assertEquals(flags.toString(), 2, flags.size());
    assertTrue(flags.get(0), flags.get(0).startsWith("the exact plan reached recall NaN"));
    assertTrue(flags.get(1), flags.get(1).startsWith("20 of 20 exact validity queries failed"));
    // A few answers decide nothing on their own.
    flags = RunPhase.validityFlags(1.0, 1, 20, 19, 0, 1.0, 0);
    assertEquals(flags.toString(), 1, flags.size());
    assertTrue(flags.get(0), flags.get(0).startsWith("19 of 20"));
  }

  @Test
  public void testFailedFreshnessProbesAreNotStaleRows() {
    // Four probes answered and found their rows; one failed.
    List<String> flags = RunPhase.validityFlags(1.0, 20, 20, 0, 0, 1.0, 1);
    assertEquals(flags.toString(), 1, flags.size());
    assertTrue(flags.get(0), flags.get(0).contains("validity freshness probes failed"));
    flags = RunPhase.validityFlags(1.0, 20, 20, 0, 0, 0.75, 0);
    assertEquals(flags.toString(), 1, flags.size());
    assertTrue(flags.get(0), flags.get(0).contains("not visible at once"));
  }

  @Test
  public void testPredicateViolationsOfTheExactPlanAreFlagged() {
    // Full recall does not excuse returned rows that fail their query's predicate.
    List<String> flags = RunPhase.validityFlags(1.0, 100, 100, 0, 0, 1.0, 0, 3);
    assertEquals(flags.toString(), 1, flags.size());
    assertTrue(flags.get(0), flags.get(0).contains("3 rows that fail their query's predicate"));
    assertTrue(RunPhase.validityFlags(1.0, 100, 100, 0, 0, 1.0, 0, 0).isEmpty());
  }

  private static Manifest state(String section, String fingerprint, Long completed) {
    Manifest m = new Manifest();
    m.put(section, "fingerprint", fingerprint);
    if (completed != null) {
      m.put(section, "completed_ms", completed);
    }
    return m;
  }

  @Test
  public void testBuildStateMustFollowTheLoad() {
    Manifest load = state("load", "fp", 1000L);
    assertTrue(RunPhase.describesLoad(state("build", "fp", 2000L), load));
    assertTrue(RunPhase.describesLoad(state("build", "fp", 1000L), load));
    assertFalse("built before a reload", RunPhase.describesLoad(state("build", "fp", 500L), load));
    assertFalse("another corpus", RunPhase.describesLoad(state("build", "other", 2000L), load));
    assertFalse("a build that did not complete",
      RunPhase.describesLoad(state("build", "fp", null), load));
  }
}
