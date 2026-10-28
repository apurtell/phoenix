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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the vector index metrics of every RegionServer at a fixed interval, for both variants.
 * Each sample reads the server values of every catalog metric and the index values of the indexes
 * under test, appends them to a long-format CSV time series (one row per server and attribute), and
 * keeps the aggregate as a {@link MetricSnapshot}. Stage boundaries take labeled snapshots, so that
 * build-time and steady-state activity are reported separately.
 */
public final class VectorIndexMetricsObserver implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(VectorIndexMetricsObserver.class);

  private final JmxReader reader;
  private final List<String> indexes;
  private final CsvTable csv;
  private final EventLog events;
  private final Map<String, MetricSnapshot> labeled = new LinkedHashMap<>();
  private final List<MetricSnapshot> history = new ArrayList<>();
  private final Object sampleLock = new Object();
  /** The last histogram count of each server and key, under sampleLock. */
  private final Map<String, Double> previousCounts = new HashMap<>();
  private volatile MetricSnapshot latest;
  private ScheduledExecutorService scheduler;

  /**
   * @param indexes the full names of the indexes under test, whose index values are read
   * @param csv     the time series, or null
   */
  public VectorIndexMetricsObserver(JmxReader reader, Collection<String> indexes, CsvTable csv,
    EventLog events) {
    this.reader = reader;
    this.indexes = new ArrayList<>(indexes);
    this.csv = csv;
    this.events = events;
  }

  /** The CSV columns of the time series. */
  public static String[] csvColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "server", "attribute", "value" };
  }

  public String mode() {
    return reader.mode();
  }

  /** Starts periodic sampling. */
  public synchronized void start(Duration interval) {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "vh-metrics");
      t.setDaemon(true);
      return t;
    });
    long ms = Math.max(100, interval.toMillis());
    scheduler.scheduleAtFixedRate(() -> {
      try {
        sample(null);
      } catch (RuntimeException e) {
        LOG.warn("Metrics sample failed", e);
      }
    }, 0, ms, TimeUnit.MILLISECONDS);
  }

  /** Takes a sample now and records it under a label, such as build-start. */
  public MetricSnapshot snapshot(String label) {
    MetricSnapshot s = sample(label);
    synchronized (labeled) {
      labeled.put(label, s);
    }
    return s;
  }

  /** Returns a labeled snapshot, or null. */
  public MetricSnapshot labeled(String label) {
    synchronized (labeled) {
      return labeled.get(label);
    }
  }

  public Map<String, MetricSnapshot> labeledSnapshots() {
    synchronized (labeled) {
      return Collections.unmodifiableMap(new LinkedHashMap<>(labeled));
    }
  }

  /** Returns the most recent sample, or null before the first. */
  public MetricSnapshot latest() {
    return latest;
  }

  /** Returns every sample taken so far, in time order. */
  public List<MetricSnapshot> history() {
    synchronized (history) {
      return new ArrayList<>(history);
    }
  }

  /** Reads every server, records the series, and returns the aggregate. */
  public MetricSnapshot sample(String label) {
    synchronized (sampleLock) {
      return sampleLocked(label);
    }
  }

  private MetricSnapshot sampleLocked(String label) {
    long now = System.currentTimeMillis();
    Map<String, Map<String, Number>> servers = reader.read(JmxReader.VECTOR_INDEX_BEAN);
    Map<String, Double> sums = new HashMap<>();
    Map<String, Double> weighted = new HashMap<>();
    Map<String, Double> weights = new HashMap<>();
    Map<String, Double> maxima = new HashMap<>();
    Map<String, Map<String, Double>> serverCounts = new HashMap<>();
    int reporting = 0;
    int missing = 0;
    String stage = events == null ? "" : events.stage();
    long offset = events == null ? 0 : now - events.startMillis();
    for (Map.Entry<String, Map<String, Number>> server : servers.entrySet()) {
      Map<String, Number> attrs = server.getValue();
      if (attrs == null) {
        missing++;
        continue;
      }
      reporting++;
      Map<String, Double> counts = new HashMap<>();
      serverCounts.put(server.getKey(), counts);
      for (MetricCatalog.Def def : MetricCatalog.all()) {
        List<String> scopes = new ArrayList<>();
        scopes.add(null);
        if (!def.serverOnly()) {
          scopes.addAll(indexes);
        }
        for (String index : scopes) {
          String key = def.scopedName(index);
          Number count = attrs.get(def.countAttribute(index));
          if (count == null) {
            continue;
          }
          double c = count.doubleValue();
          sums.merge(key, c, Double::sum);
          counts.put(key, c);
          record(now, offset, stage, server.getKey(), def.countAttribute(index), c);
          if (def.kind() != MetricCatalog.Kind.HISTOGRAM) {
            continue;
          }
          // The statistics reset at every metrics2 snapshot of the server, while the count is
          // cumulative, so they describe the ops since the last refresh. Weight them by the ops
          // since the previous sample, and leave out a server with none, whose statistics read
          // zero for want of data, and one seen first or restarted, whose interval is unknown.
          Double previous = previousCounts.put(server.getKey() + "|" + key, c);
          double ops = previous == null || c < previous ? 0.0 : c - previous;
          for (String stat : MetricCatalog.HISTOGRAM_STATS) {
            Number v = attrs.get(def.statAttribute(index, stat));
            if (v == null) {
              continue;
            }
            double x = v.doubleValue();
            record(now, offset, stage, server.getKey(), def.statAttribute(index, stat), x);
            if (ops <= 0) {
              continue;
            }
            String sk = key + "#" + stat;
            if (stat.equals("mean")) {
              weighted.merge(sk, x * ops, Double::sum);
              weights.merge(sk, ops, Double::sum);
            } else {
              maxima.merge(sk, x, Math::max);
            }
          }
        }
      }
    }
    Map<String, Double> values = new HashMap<>(sums);
    values.putAll(maxima);
    for (Map.Entry<String, Double> e : weighted.entrySet()) {
      values.put(e.getKey(), e.getValue() / weights.get(e.getKey()));
    }
    if (csv != null) {
      csv.flush();
    }
    MetricSnapshot s = new MetricSnapshot(now, label, values, reporting, missing, serverCounts);
    latest = s;
    synchronized (history) {
      history.add(s);
    }
    return s;
  }

  private void record(long now, long offset, String stage, String server, String attribute,
    double value) {
    if (csv != null) {
      csv.row(now, offset, stage, server, attribute, value);
    }
  }

  @Override
  public synchronized void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      try {
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      scheduler = null;
    }
  }
}
