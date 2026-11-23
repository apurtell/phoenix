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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.result.DataModelResult;
import org.apache.phoenix.pherf.result.ThreadTime;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Verifies the start gate synchronization behavior for closed-loop execution, ensuring that a
 * runner invokes the start gate immediately after completing its warmup executions.
 */
public class StartGateRunnerTest {
  @After
  public void clearGate() {
    StartGate.set(null);
  }

  private static List<String> run(long warmup, long executions) throws Exception {
    List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    Query query = Mockito.mock(Query.class);
    Mockito.when(query.kind()).thenReturn(Query.Kind.SELECT);
    Mockito.when(query.getId()).thenReturn("q");
    Scenario scenario = Mockito.mock(Scenario.class);
    Mockito.when(scenario.getName()).thenReturn("s");
    MultiThreadedRunner runner = Mockito.spy(new MultiThreadedRunner("t", query,
      Mockito.mock(DataModelResult.class), Mockito.mock(ThreadTime.class), executions,
      Long.MAX_VALUE, false, Mockito.mock(RulesApplier.class), scenario,
      Mockito.mock(WorkloadExecutor.class), Mockito.mock(XMLConfigParser.class),
      Collections.<String, String> emptyMap(), warmup, null));
    Mockito.doAnswer(invocation -> {
      calls.add(((Boolean) invocation.getArgument(1) ? "w" : "s") + invocation.getArgument(0));
      return 0L;
    }).when(runner).timedQuery(Mockito.anyLong(), Mockito.anyBoolean());
    StartGate.set(() -> calls.add("gate"));
    runner.call();
    return calls;
  }

  @Test
  public void gateComesAfterTheWarmupAndBeforeTheFirstSteadyExecution() throws Exception {
    assertEquals(Arrays.asList("w1", "w2", "gate", "s1", "s2", "s3"), run(2, 3));
    assertEquals(Arrays.asList("gate", "s1"), run(0, 1));
  }
}
