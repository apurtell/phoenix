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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Point-in-time snapshot of vector index metrics aggregated across RegionServers. Counters, gauges,
 * and histogram counts are summed, histogram means are weighted by interval operations, and
 * percentiles/maxima record maximum observed values. Missing metrics evaluate to null rather than
 * zero.
 */
public final class MetricSnapshot {

  private final long wallMillis;
  private final String label;
  private final Map<String, Double> values;
  private final int serversReporting;
  private final int serversMissing;
  private final Map<String, Map<String, Double>> servers;

  public MetricSnapshot(long wallMillis, String label, Map<String, Double> values,
    int serversReporting, int serversMissing) {
    this(wallMillis, label, values, serversReporting, serversMissing, null);
  }

  public MetricSnapshot(long wallMillis, String label, Map<String, Double> values,
    int serversReporting, int serversMissing, Map<String, Map<String, Double>> servers) {
    this.wallMillis = wallMillis;
    this.label = label;
    this.values = Collections.unmodifiableMap(new TreeMap<>(values));
    this.serversReporting = serversReporting;
    this.serversMissing = serversMissing;
    if (servers == null) {
      this.servers = null;
    } else {
      Map<String, Map<String, Double>> copy = new TreeMap<>();
      for (Map.Entry<String, Map<String, Double>> e : servers.entrySet()) {
        copy.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
      }
      this.servers = Collections.unmodifiableMap(copy);
    }
  }

  public long wallMillis() {
    return wallMillis;
  }

  public String label() {
    return label;
  }

  public int serversReporting() {
    return serversReporting;
  }

  public int serversMissing() {
    return serversMissing;
  }

  /** Returns the metric value, or null if unknown. */
  public Double get(String key) {
    return values.get(key);
  }

  /** Returns the scoped metric value for an index or server, or null if unknown. */
  public Double get(MetricCatalog.Def def, String index) {
    return values.get(def.scopedName(index));
  }

  public Map<String, Double> values() {
    return values;
  }

  /**
   * Returns the delta of a counter or histogram count relative to an earlier snapshot, or null if
   * either value is unknown or cannot be accurately determined.
   */
  public Double delta(MetricSnapshot earlier, String key) {
    if (earlier == null) {
      return null;
    }
    if (servers != null && earlier.servers != null) {
      return serverDelta(earlier, key);
    }
    Double now = values.get(key);
    if (now == null) {
      return null;
    }
    Double then = earlier.values.get(key);
    if (
      then == null && isIndexValue(key) && earlier.serversReporting > 0
        && earlier.serversMissing == 0
    ) {
      then = 0.0;
    }
    if (then == null) {
      return null;
    }
    return now - then;
  }

  private Double serverDelta(MetricSnapshot earlier, String key) {
    if (!values.containsKey(key) || !servers.keySet().equals(earlier.servers.keySet())) {
      return null;
    }
    boolean index = isIndexValue(key);
    MetricCatalog.Def def = MetricCatalog.get(baseName(key));
    boolean cumulative = def != null && def.kind() != MetricCatalog.Kind.GAUGE;
    double sum = 0.0;
    boolean known = false;
    for (Map.Entry<String, Map<String, Double>> e : servers.entrySet()) {
      Double now = e.getValue().get(key);
      Double then = earlier.servers.get(e.getKey()).get(key);
      if (now == null && then == null) {
        continue;
      }
      if (index) {
        now = now == null ? 0.0 : now;
        then = then == null ? 0.0 : then;
      } else if (now == null || then == null) {
        return null;
      }
      sum += cumulative && now < then ? now : now - then;
      known = true;
    }
    return known ? sum : null;
  }

  /** Returns true if the key represents an index-scoped metric. */
  static boolean isIndexValue(String key) {
    int hash = key.indexOf('#');
    return (hash >= 0 ? key.substring(0, hash) : key).indexOf('.') >= 0;
  }

  /** Returns the ratio of two metric values, or null if either is unknown or divisor is zero. */
  public Double ratio(String numerator, String denominator) {
    Double n = values.get(numerator);
    Double d = values.get(denominator);
    if (n == null || d == null || d == 0.0) {
      return null;
    }
    return n / d;
  }

  /** Computes deltas for all counters and histogram counts relative to an earlier snapshot. */
  public Map<String, Double> deltas(MetricSnapshot earlier) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (Map.Entry<String, Double> e : values.entrySet()) {
      String key = e.getKey();
      if (key.indexOf('#') >= 0) {
        continue;
      }
      MetricCatalog.Def def = MetricCatalog.get(baseName(key));
      if (def == null || def.kind() == MetricCatalog.Kind.GAUGE) {
        continue;
      }
      Double d = delta(earlier, key);
      if (d != null) {
        out.put(key, d);
      }
    }
    return out;
  }

  /** Returns the unqualified base metric name, stripping index scope and statistic suffix. */
  public static String baseName(String key) {
    int hash = key.indexOf('#');
    String k = hash >= 0 ? key.substring(0, hash) : key;
    int dot = k.indexOf('.');
    return dot >= 0 ? k.substring(0, dot) : k;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", label);
    m.put("wall_ms", wallMillis);
    m.put("servers_reporting", serversReporting);
    m.put("servers_missing", serversMissing);
    m.put("values", new LinkedHashMap<String, Object>(values));
    return m;
  }
}
