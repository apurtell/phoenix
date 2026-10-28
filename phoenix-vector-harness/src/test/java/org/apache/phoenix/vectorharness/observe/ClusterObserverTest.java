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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ClusterObserverTest {

  /** A reader of the server bean of two servers, whose values a test sets before each sample. */
  private static final class ServerBeanReader implements JmxReader {
    final Map<String, Map<String, Number>> servers = new LinkedHashMap<>();

    @Override
    public String mode() {
      return "stub";
    }

    @Override
    public Map<String, Map<String, Number>> read(String objectName) {
      return objectName.equals(SERVER_BEAN) ? new LinkedHashMap<>(servers) : Collections.emptyMap();
    }

    @Override
    public void close() {
    }

    void set(String server, long hits, long misses, long regions) {
      if (hits < 0) {
        servers.put(server, null);
        return;
      }
      Map<String, Number> attrs = new HashMap<>();
      attrs.put("blockCacheHitCount", hits);
      attrs.put("blockCacheMissCount", misses);
      attrs.put("regionCount", regions);
      servers.put(server, attrs);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRestartedServersAddWhatTheyCounted() {
    ServerBeanReader reader = new ServerBeanReader();
    try (ClusterObserver o = new ClusterObserver(reader, null, null)) {
      reader.set("a", 1000, 100, 4);
      reader.set("b", 5_000_000, 50_000, 4);
      o.sample();
      reader.set("a", 1500, 150, 5);
      // b is down for a sample, then restarts with fresh counters.
      reader.set("b", -1, 0, 0);
      o.sample();
      reader.set("a", 2000, 200, 5);
      reader.set("b", 1000, 900, 3);
      o.sample();
      reader.set("b", 1500, 1000, 3);
      o.sample();
      Map<String, Object> s = o.summary();
      Map<String, Object> deltas = (Map<String, Object>) s.get("deltas");
      assertEquals(1000.0 + 1500.0, (Double) deltas.get("Server.blockCacheHitCount"), 0.0);
      assertEquals(100.0 + 1000.0, (Double) deltas.get("Server.blockCacheMissCount"), 0.0);
      assertEquals(2500.0 / 3600.0, (Double) s.get("block_cache_hit_ratio"), 1e-12);
      Map<String, Object> levels = (Map<String, Object>) s.get("levels");
      assertEquals(4.0, (Double) levels.get("Server.regionCount"), 0.0);
    }
  }
}
