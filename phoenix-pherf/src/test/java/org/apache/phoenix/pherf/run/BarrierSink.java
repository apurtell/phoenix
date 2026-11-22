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
package org.apache.phoenix.pherf.run;

import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.pherf.spi.MetricsSink;

/**
 * A metrics sink that blocks the first {@link #PARTIES} invocations for the query.elapsed_ms metric
 * at a shared synchronization barrier. Because Pherf invokes the sink from within an active query
 * operation, the barrier trips only when that many query operations are in flight concurrently.
 * Upon release or failure, each participating thread records either "tripped" or "broken" in
 * {@link #RECORD}.
 */
public class BarrierSink implements MetricsSink {
  public static final String RECORD = "barrier.log";
  public static final String TRIPPED = "tripped";
  public static final String BROKEN = "broken";
  public static final int PARTIES = 4;
  private static final long TIMEOUT_SECONDS = 20;
  private static final CyclicBarrier BARRIER = new CyclicBarrier(PARTIES);
  private static final AtomicInteger HELD = new AtomicInteger();

  @Override
  public void record(String name, Map<String, String> tags, long value) {
    if (!"query.elapsed_ms".equals(name) || HELD.incrementAndGet() > PARTIES) {
      return;
    }
    String outcome;
    try {
      BARRIER.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      outcome = TRIPPED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      outcome = BROKEN;
    } catch (BrokenBarrierException | TimeoutException e) {
      outcome = BROKEN;
    }
    Records.append(RECORD, outcome);
  }
}
