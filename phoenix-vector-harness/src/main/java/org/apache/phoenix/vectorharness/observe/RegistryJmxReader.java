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

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.hbase.index.metrics.MetricsIndexerSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the vector index metrics source of this JVM directly from its metrics registry, which is
 * exact and uncached: no JMX cache period applies and no histogram snapshot resets. It serves
 * minicluster tests, where every RegionServer shares the source of the test JVM. Values are
 * exported under JMX attribute names: counters and gauges keep their names, and a histogram
 * {@code n} exports {@code N_num_ops} and {@code N_max}, capitalized as metrics2 does. Other beans
 * are read from the platform MBean server.
 */
public final class RegistryJmxReader implements JmxReader {

  private static final Logger LOG = LoggerFactory.getLogger(RegistryJmxReader.class);

  private final PlatformJmxReader platform = new PlatformJmxReader();

  @Override
  public String mode() {
    return "registry";
  }

  @Override
  public Map<String, Map<String, Number>> read(String objectName) {
    if (!VECTOR_INDEX_BEAN.equals(objectName)) {
      return platform.read(objectName);
    }
    Map<String, Map<String, Number>> out = new LinkedHashMap<>();
    try {
      Object source = MetricsIndexerSourceFactory.getInstance().getMetricsVectorIndexSource();
      Object registry = source.getClass().getMethod("getMetricsRegistry").invoke(source);
      Map<?, ?> metrics =
        (Map<?, ?>) registry.getClass().getMethod("getMetricsMap").invoke(registry);
      Map<String, Number> attrs = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : metrics.entrySet()) {
        String name = String.valueOf(e.getKey());
        Object m = e.getValue();
        Method getCount = method(m, "getCount");
        if (getCount != null) {
          String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
          attrs.put(cap + "_num_ops", (Number) getCount.invoke(m));
          Method getMax = method(m, "getMax");
          if (getMax != null) {
            attrs.put(cap + "_max", (Number) getMax.invoke(m));
          }
          continue;
        }
        Method value = method(m, "value");
        if (value != null) {
          Object v = value.invoke(m);
          if (v instanceof Number) {
            attrs.put(name, (Number) v);
          }
        }
      }
      out.put(PlatformJmxReader.LOCAL, attrs);
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.debug("Reading the vector index metrics registry failed", e);
      out.put(PlatformJmxReader.LOCAL, null);
    }
    return out;
  }

  private static Method method(Object o, String name) {
    try {
      Method m = o.getClass().getMethod(name);
      return m.getParameterCount() == 0 ? m : null;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  @Override
  public void close() {
    platform.close();
  }
}
