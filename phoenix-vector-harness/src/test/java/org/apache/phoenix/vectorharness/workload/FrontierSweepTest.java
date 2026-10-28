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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.vectorharness.observe.JmxReader;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.junit.Test;

public class FrontierSweepTest {

  /** A reader whose counter grows by one per read, as a cached JMX value refreshing would. */
  private static final class CountingReader implements JmxReader {
    final AtomicInteger reads = new AtomicInteger();

    @Override
    public String mode() {
      return "http";
    }

    @Override
    public Map<String, Map<String, Number>> read(String objectName) {
      Map<String, Number> attrs =
        Collections.singletonMap("vectorCentroidAssignments", reads.incrementAndGet());
      return Collections.singletonMap("rs1", attrs);
    }

    @Override
    public void close() {
    }
  }

  @Test
  public void testQuietSamplerWaitsOutTheCacheAndChainsMeasurements() throws Exception {
    CountingReader reader = new CountingReader();
    long ttl = 40;
    try (VectorIndexMetricsObserver metrics =
      new VectorIndexMetricsObserver(reader, Collections.emptyList(), null, null)) {
      FrontierSweep.QuietSampler quiet = new FrontierSweep.QuietSampler(metrics, ttl);
      long t0 = System.nanoTime();
      MetricSnapshot before = quiet.before();
      assertTrue("the first sample waits for the cache", System.nanoTime() - t0 >= (ttl - 5) * 1e6);
      assertSame("no new sample until a measurement ends", before, quiet.before());
      long t1 = System.nanoTime();
      MetricSnapshot after = quiet.after();
      assertTrue("the end of a measurement waits for the cache",
        System.nanoTime() - t1 >= (ttl - 5) * 1e6);
      assertNotSame(before, after);
      assertSame("the end of one measurement starts the next", after, quiet.before());
      assertEquals(2, reader.reads.get());
      quiet.reset();
      assertNotSame(after, quiet.before());
      assertEquals(3, reader.reads.get());
    }
  }
}
