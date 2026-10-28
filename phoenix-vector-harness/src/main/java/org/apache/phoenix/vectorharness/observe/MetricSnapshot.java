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
 * The vector index metrics at one instant, aggregated over the RegionServers that reported:
 * counters, gauges, and histogram counts are summed; histogram means are weighted by the ops of the
 * interval since the previous sample; percentiles and maxima take the largest server value. A
 * metric that no server reported is absent, which reads as unknown (null), never as zero. Keys are
 * the registry names, such as {@code vectorHnswRegions} or {@code vectorHnswRegions.SCHEMA.INDEX};
 * histogram statistics have keys such as {@code vectorHnswSearchTime.SCHEMA.INDEX#99th_percentile},
 * and histogram counts use the plain key.
 * <p>
 * Histogram statistics describe the last metrics period of each server, the ops since its previous
 * metrics2 snapshot, not the stage that a label names: the engine's histograms reset their
 * statistics at every snapshot, and only the counts are cumulative. A statistic is absent when no
 * server recorded an op in the interval. The changes of counts over a stage come from
 * {@link #delta}, which works per server when the snapshot keeps the server values.
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

  /**
   * @param servers the count values of each server that reported, by server and key, or null if
   *                only the sums are known
   */
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

  /** Returns a value, or null if unknown. */
  public Double get(String key) {
    return values.get(key);
  }

  /** Returns the server value or the index value of a metric, or null if unknown. */
  public Double get(MetricCatalog.Def def, String index) {
    return values.get(def.scopedName(index));
  }

  public Map<String, Double> values() {
    return values;
  }

  /**
   * Returns the change of a counter or histogram count since an earlier snapshot, or null if either
   * value is unknown. The engine registers the values of an index lazily, at zero, when the index
   * first uses them, so an index value that the earlier snapshot lacks counts from zero if every
   * server reported that snapshot; a server value that it lacks stays unknown.
   * <p>
   * When both snapshots keep their server values, the change is the sum of the changes of each
   * server. It is unknown if a server reported only one of the snapshots, since its whole value
   * would enter or leave the sum, and a count that fell is taken as a counter that restarted with
   * its server, which counted its present value since.
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

  /** Returns true if a key names the value of one index, such as metric.SCHEMA.INDEX. */
  static boolean isIndexValue(String key) {
    int hash = key.indexOf('#');
    return (hash >= 0 ? key.substring(0, hash) : key).indexOf('.') >= 0;
  }

  /** Returns a ratio of two values, or null if either is unknown or the divisor is zero. */
  public Double ratio(String numerator, String denominator) {
    Double n = values.get(numerator);
    Double d = values.get(denominator);
    if (n == null || d == null || d == 0.0) {
      return null;
    }
    return n / d;
  }

  /** Returns the changes of every counter and histogram count since an earlier snapshot. */
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

  /** Returns the metric name of a key, without the index and statistic. */
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
