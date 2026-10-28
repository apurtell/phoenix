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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource;
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource.HnswMetric;

/**
 * The vector index metrics of the shared RegionServer source, with their kinds, scopes, and the
 * families the harness reports them in. Names come from {@link MetricsVectorIndexSource}, so the
 * catalog follows the engine. The JMX attribute names follow metrics2: counters and gauges export
 * their name, and histograms export {@code N_num_ops} and summary statistics with the first letter
 * of the name capitalized. Each metric that is not server only also has a value per index, named
 * {@code <metric>.<index>}.
 */
public final class MetricCatalog {

  /** The kinds of metrics. */
  public enum Kind {
    COUNTER,
    GAUGE,
    HISTOGRAM
  }

  /** One metric of the catalog. */
  public static final class Def {
    private final String name;
    private final Kind kind;
    private final boolean serverOnly;
    private final String family;

    Def(String name, Kind kind, boolean serverOnly, String family) {
      this.name = name;
      this.kind = kind;
      this.serverOnly = serverOnly;
      this.family = family;
    }

    public String name() {
      return name;
    }

    public Kind kind() {
      return kind;
    }

    public boolean serverOnly() {
      return serverOnly;
    }

    public String family() {
      return family;
    }

    /** Returns the registry name of the server value or of an index value. */
    public String scopedName(String index) {
      return index == null ? name : name + "." + index;
    }

    /** Returns the JMX attribute of the count of a histogram, or of a counter or gauge value. */
    public String countAttribute(String index) {
      if (kind != Kind.HISTOGRAM) {
        return scopedName(index);
      }
      return capitalized(scopedName(index)) + "_num_ops";
    }

    /** Returns the JMX attribute of a histogram statistic, such as mean or 99th_percentile. */
    public String statAttribute(String index, String stat) {
      return capitalized(scopedName(index)) + "_" + stat;
    }
  }

  public static final String FAMILY_IVF = "ivf";
  public static final String FAMILY_ADMISSION = "hnsw admission and overflow";
  public static final String FAMILY_COVERAGE = "hnsw coverage and rebuilds";
  public static final String FAMILY_SEGMENTS = "hnsw segments";
  public static final String FAMILY_OFFHEAP = "hnsw off-heap memory";
  public static final String FAMILY_SEARCH = "hnsw search";
  public static final String FAMILY_FAILURES = "hnsw failures and repairs";

  /** The histogram statistics that metrics2 exports, besides the count. */
  public static final String[] HISTOGRAM_STATS = { "mean", "median", "99th_percentile", "max" };

  private static final List<Def> ALL;
  private static final Map<String, Def> BY_NAME = new LinkedHashMap<>();

  static {
    List<Def> defs = new ArrayList<>();
    defs.add(new Def(MetricsVectorIndexSource.VECTOR_CENTROID_ASSIGNMENTS, Kind.COUNTER, false,
      FAMILY_IVF));
    defs.add(new Def(MetricsVectorIndexSource.VECTOR_CENTROID_REASSIGNMENTS, Kind.COUNTER, false,
      FAMILY_IVF));
    defs.add(new Def(MetricsVectorIndexSource.VECTOR_SCORECARD_FLUSH_TIME, Kind.HISTOGRAM, false,
      FAMILY_IVF));
    defs.add(new Def(MetricsVectorIndexSource.VECTOR_SCORECARD_RECONCILE_TIME, Kind.HISTOGRAM,
      false, FAMILY_IVF));
    for (HnswMetric m : HnswMetric.values()) {
      Kind kind;
      switch (m.kind()) {
        case COUNTER:
          kind = Kind.COUNTER;
          break;
        case GAUGE:
          kind = Kind.GAUGE;
          break;
        default:
          kind = Kind.HISTOGRAM;
          break;
      }
      defs.add(new Def(m.metricName(), kind, m.serverOnly(), family(m)));
    }
    ALL = Collections.unmodifiableList(defs);
    for (Def d : ALL) {
      BY_NAME.put(d.name, d);
    }
  }

  private MetricCatalog() {
  }

  static String family(HnswMetric m) {
    switch (m) {
      case CHANGES_APPLIED:
      case CHANGES_DROPPED:
      case MUTABLE_ENTRIES:
      case REGIONS_OVERFLOWED:
      case OVERFLOW_ENTRIES:
      case OVERFLOW_TIME:
      case SPLIT_REQUESTS:
        return FAMILY_ADMISSION;
      case REGIONS:
      case REGIONS_UNCOVERED:
      case REGIONS_UNRESTRICTED:
      case REBUILD_QUEUE:
      case REBUILDS:
      case REBUILD_TIME:
      case REBUILD_WAIT_TIME:
      case OPEN_TIME:
      case REPLAYED_ROWS:
        return FAMILY_COVERAGE;
      case SEGMENTS:
      case DELTA_FLUSHES:
      case DELTA_FLUSH_TIME:
      case SEGMENT_BYTES_WRITTEN:
      case STORE_FLUSH_WAIT_TIME:
        return FAMILY_SEGMENTS;
      case OFFHEAP_MAX_BYTES:
      case OFFHEAP_ALLOCATED_BYTES:
      case OFFHEAP_RESIDENT_SEGMENTS:
      case OFFHEAP_EVICTIONS:
      case OFFHEAP_EVICTED_BYTES:
      case SEGMENT_LOADS:
      case SEGMENT_LOAD_TIME:
      case OFFHEAP_REFUSALS:
        return FAMILY_OFFHEAP;
      case SEARCHES:
      case SEARCH_TIME:
      case NODES_VISITED:
      case PROBE_ROUNDS:
      case PROBE_ROWS_READ:
      case UNRESTRICTED_UNCOVERED:
      case UNRESTRICTED_OVERFLOW:
      case UNRESTRICTED_LIMIT:
        return FAMILY_SEARCH;
      default:
        return FAMILY_FAILURES;
    }
  }

  public static List<Def> all() {
    return ALL;
  }

  public static Def get(String name) {
    return BY_NAME.get(name);
  }

  public static Def hnsw(HnswMetric m) {
    return BY_NAME.get(m.metricName());
  }

  static String capitalized(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
