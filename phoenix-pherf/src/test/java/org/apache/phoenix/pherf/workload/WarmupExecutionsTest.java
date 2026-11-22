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
package org.apache.phoenix.pherf.workload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.junit.Test;

/**
 * Verifies the behavior of the querySet configuration attribute warmupExecutions, ensuring that an
 * absent attribute defaults to 0 and that a negative value in any query set of the data model
 * raises a validation error before any query set starts.
 */
public class WarmupExecutionsTest {
  private static QuerySet querySet(Long warmup) {
    QuerySet querySet = new QuerySet();
    querySet.setWarmupExecutions(warmup);
    return querySet;
  }

  @Test
  public void testAbsentGivesZeroElseValue() {
    assertEquals(0, QueryExecutor.warmupExecutions(querySet(null)));
    assertEquals(5, QueryExecutor.warmupExecutions(querySet(5L)));
  }

  @Test
  public void testNegativeInLaterScenarioNamesAttribute() {
    Scenario good = new Scenario();
    good.setName("good");
    good.setQuerySet(Arrays.asList(querySet(null), querySet(2L)));
    Scenario bad = new Scenario();
    bad.setName("bad");
    bad.setQuerySet(Arrays.asList(querySet(0L), querySet(-1L)));
    DataModel dataModel = new DataModel();
    dataModel.setScenarios(Collections.singletonList(good));
    QueryExecutor.checkWarmupExecutions(dataModel);
    dataModel.setScenarios(Arrays.asList(good, bad));
    try {
      QueryExecutor.checkWarmupExecutions(dataModel);
      fail("A negative warmup value was accepted");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(),
        e.getMessage().contains("warmupExecutions") && e.getMessage().contains("bad"));
    }
  }
}
