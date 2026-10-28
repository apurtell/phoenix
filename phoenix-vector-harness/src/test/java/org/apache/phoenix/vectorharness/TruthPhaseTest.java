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
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TruthPhaseTest {

  private static Map<String, Object> layout(double minRecall, long failures) {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("unfiltered", minRecall);
    l.put("failures", failures);
    l.put("min_recall", minRecall);
    return l;
  }

  @Test
  public void testExactCheckFailsOnDisagreementAndOnFailedQueries() {
    Map<String, Object> check = new LinkedHashMap<>();
    check.put("corpus", layout(1.0, 0));
    assertTrue(TruthPhase.exactProblems(check).isEmpty());
    check.put("profile", layout(0.85, 0));
    // A layout whose queries all failed scores no family, so its recall reads 1.
    check.put("other", layout(1.0, 20));
    List<String> problems = TruthPhase.exactProblems(check);
    assertEquals(problems.toString(), 2, problems.size());
    assertTrue(problems.get(0), problems.get(0).startsWith("profile: min recall 0.85"));
    assertTrue(problems.get(1), problems.get(1).contains("20 failed queries"));
    check.clear();
    check.put("corpus", layout(Double.NaN, 0));
    assertEquals(1, TruthPhase.exactProblems(check).size());
  }

  @Test
  public void testExactCheckFailsOnPredicateViolations() {
    Map<String, Object> check = new LinkedHashMap<>();
    Map<String, Object> l = layout(1.0, 0);
    l.put("violations", 2L);
    check.put("corpus", l);
    List<String> problems = TruthPhase.exactProblems(check);
    assertEquals(problems.toString(), 1, problems.size());
    assertTrue(problems.get(0), problems.get(0).contains("2 rows that fail"));
  }
}
