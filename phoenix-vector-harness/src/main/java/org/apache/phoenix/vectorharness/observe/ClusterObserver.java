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
import java.util.Arrays;
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
 * Samples RegionServer resource telemetry at a fixed interval: CPU load, garbage collection, heap
 * and direct memory, block cache hits and misses, memstore size and flush queue, compaction queue,
 * request counts, and RPC handler use. The samples go to a long-format CSV time series, and the
 * observer keeps the last value of each attribute and the summed increments of each cumulative one,
 * so that a report can compute window deltas and averages.
 */
public final class ClusterObserver implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterObserver.class);

  /** The beans and attributes the observer reads. */
  static final Map<String, List<String>> ATTRIBUTES = new LinkedHashMap<>();

  static {
    ATTRIBUTES.put(JmxReader.SERVER_BEAN,
      Arrays.asList("regionCount", "storeFileCount", "storeFileSize", "memStoreSize",
        "flushQueueLength", "compactionQueueLength", "blockCacheHitCount", "blockCacheMissCount",
        "blockCacheSize", "blockCacheFreeSize", "totalRequestCount", "readRequestCount",
        "writeRequestCount", "flushedCellsCount", "majorCompactedCellsCount", "mobFlushCount",
        "mobFileCacheCount", "mobFileCacheHitPercent"));
    ATTRIBUTES.put(JmxReader.IPC_BEAN,
      Arrays.asList("numActiveHandler", "numCallsInGeneralQueue", "queueSize", "numOpenConnections",
        "numActiveReadHandler", "numActiveScanHandler", "numActiveWriteHandler"));
    ATTRIBUTES.put(JmxReader.JVM_BEAN, Arrays.asList("MemHeapUsedM", "MemHeapMaxM",
      "MemNonHeapUsedM", "GcCount", "GcTimeMillis", "ThreadsRunnable", "ThreadsBlocked"));
    ATTRIBUTES.put(JmxReader.OS_BEAN,
      Arrays.asList("ProcessCpuLoad", "SystemCpuLoad", "CpuLoad", "ProcessCpuTime"));
    ATTRIBUTES.put(JmxReader.DIRECT_BUFFER_BEAN,
      Arrays.asList("MemoryUsed", "Count", "TotalCapacity"));
  }

  private final JmxReader reader;
  private final CsvTable csv;
  private final EventLog events;
  /** The sum of the increments of each cumulative attribute of each server, sample by sample. */
  private final Map<String, Double> increments = new LinkedHashMap<>();
  private final Map<String, Double> last = new LinkedHashMap<>();
  private final Object sampleLock = new Object();
  private ScheduledExecutorService scheduler;
  private long samples;

  public ClusterObserver(JmxReader reader, CsvTable csv, EventLog events) {
    this.reader = reader;
    this.csv = csv;
    this.events = events;
  }

  public static String[] csvColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "server", "bean", "attribute", "value" };
  }

  public synchronized void start(Duration interval) {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "vh-cluster");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(() -> {
      try {
        sample();
      } catch (RuntimeException e) {
        LOG.warn("Cluster sample failed", e);
      }
    }, 0, Math.max(100, interval.toMillis()), TimeUnit.MILLISECONDS);
  }

  /** Reads every bean of every server once. */
  public void sample() {
    synchronized (sampleLock) {
      long now = System.currentTimeMillis();
      String stage = events == null ? "" : events.stage();
      long offset = events == null ? 0 : now - events.startMillis();
      for (Map.Entry<String, List<String>> bean : ATTRIBUTES.entrySet()) {
        for (Map.Entry<String, Map<String, Number>> server : reader.read(bean.getKey())
          .entrySet()) {
          Map<String, Number> attrs = server.getValue();
          if (attrs == null) {
            continue;
          }
          for (String a : bean.getValue()) {
            Number v = attrs.get(a);
            if (v == null) {
              continue;
            }
            double x = v.doubleValue();
            if (csv != null) {
              csv.row(now, offset, stage, server.getKey(), shortBean(bean.getKey()), a, x);
            }
            String key = server.getKey() + "|" + shortBean(bean.getKey()) + "|" + a;
            Double previous = last.put(key, x);
            if (cumulative(a)) {
              // A value that fell belongs to a restarted server, which counted it since.
              double increment = previous == null ? 0.0 : x >= previous ? x - previous : x;
              increments.merge(key, increment, Double::sum);
            }
          }
        }
      }
      samples++;
      if (csv != null) {
        csv.flush();
      }
    }
  }

  static String shortBean(String bean) {
    int sub = bean.indexOf("sub=");
    if (sub >= 0) {
      return bean.substring(sub + 4);
    }
    int name = bean.indexOf("name=");
    if (name >= 0) {
      return bean.substring(name + 5);
    }
    int type = bean.indexOf("type=");
    return type >= 0 ? bean.substring(type + 5) : bean;
  }

  /**
   * Returns a summary of the observed window: the sum over servers of the change of each cumulative
   * attribute and the mean over servers of the last value of each level attribute. The change adds
   * the increments between samples, so that a server that restarts, whose counters start again from
   * zero, adds what it counted before and after the restart.
   */
  public Map<String, Object> summary() {
    synchronized (sampleLock) {
      Map<String, Double> deltas = new LinkedHashMap<>();
      Map<String, double[]> levels = new LinkedHashMap<>();
      for (Map.Entry<String, Double> e : last.entrySet()) {
        String[] parts = e.getKey().split("\\|", 3);
        String attr = parts[1] + "." + parts[2];
        if (cumulative(parts[2])) {
          deltas.merge(attr, increments.get(e.getKey()), Double::sum);
        } else {
          double[] acc = levels.computeIfAbsent(attr, k -> new double[2]);
          acc[0] += e.getValue();
          acc[1]++;
        }
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("samples", samples);
      out.put("mode", reader.mode());
      Map<String, Object> d = new LinkedHashMap<>(deltas);
      out.put("deltas", d);
      Map<String, Object> l = new LinkedHashMap<>();
      for (Map.Entry<String, double[]> e : levels.entrySet()) {
        l.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
      }
      out.put("levels", l);
      Double hits = deltas.get("Server.blockCacheHitCount");
      Double misses = deltas.get("Server.blockCacheMissCount");
      if (hits != null && misses != null && hits + misses > 0) {
        out.put("block_cache_hit_ratio", hits / (hits + misses));
      }
      return out;
    }
  }

  static boolean cumulative(String attribute) {
    return attribute.endsWith("Count") && !attribute.equals("Count")
      && !attribute.equals("regionCount") && !attribute.equals("storeFileCount")
      && !attribute.equals("mobFileCacheCount") || attribute.equals("GcTimeMillis")
      || attribute.equals("ProcessCpuTime");
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
