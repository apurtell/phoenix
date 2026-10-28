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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.vectorharness.result.CsvTable;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.result.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HNSW supplementary observer. Segment stack depth, split requests, overflow episodes, and the
 * share of regions serving unconstrained scans all come from the metrics observer; this observer
 * adds the state that metrics do not carry. It tracks the region assignments of each data table,
 * recording splits, merges, and moves as events, and it runs the read-only indexfsck inspect
 * commands list, coverage, and delta at stage boundaries, or at a fixed interval when one is set,
 * writing each report as JSON beside the run.
 */
public final class HnswRegionObserver implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HnswRegionObserver.class);
  static final List<String> INSPECT_COMMANDS = Arrays.asList("list", "coverage", "delta");

  private final IvfCentroidObserver.ConnectionSupplier connections;
  private final Map<String, String> indexToTable;
  private final Path reportDir;
  private final CsvTable csv;
  private final EventLog events;
  private final Map<String, Map<String, String>> assignments = new HashMap<>();
  private final Map<String, Object> inspections = new LinkedHashMap<>();
  private final Object sampleLock = new Object();
  private ScheduledExecutorService scheduler;
  private long lastFsckMillis;

  /**
   * @param indexToTable the full names of the indexes under test, mapped to their data tables
   * @param reportDir    the directory for the inspect reports, or null
   */
  public HnswRegionObserver(IvfCentroidObserver.ConnectionSupplier connections,
    Map<String, String> indexToTable, Path reportDir, CsvTable csv, EventLog events) {
    this.connections = connections;
    this.indexToTable = indexToTable;
    this.reportDir = reportDir;
    this.csv = csv;
    this.events = events;
  }

  public static String[] csvColumns() {
    return new String[] { "wall_ms", "offset_ms", "stage", "table", "regions", "servers",
      "max_regions_per_server" };
  }

  /**
   * Starts tracking regions at an interval.
   * @param fsckInterval the interval of the inspect commands, or null for stage boundaries only
   */
  public synchronized void start(Duration interval, Duration fsckInterval) {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "vh-hnsw");
      t.setDaemon(true);
      return t;
    });
    lastFsckMillis = System.currentTimeMillis();
    scheduler.scheduleAtFixedRate(() -> {
      try {
        sampleRegions();
        if (
          fsckInterval != null && !fsckInterval.isZero()
            && System.currentTimeMillis() - lastFsckMillis >= fsckInterval.toMillis()
        ) {
          lastFsckMillis = System.currentTimeMillis();
          inspect("interval-" + (events == null ? 0 : events.offset() / 1000) + "s");
        }
      } catch (Exception e) {
        LOG.warn("HNSW observer sample failed", e);
      }
    }, 0, Math.max(100, interval.toMillis()), TimeUnit.MILLISECONDS);
  }

  /** Records the region assignment of every data table and reports changes as events. */
  public void sampleRegions() throws SQLException {
    synchronized (sampleLock) {
      try (Connection conn = connections.get()) {
        PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
        for (String table : new java.util.TreeSet<>(indexToTable.values())) {
          byte[] physical = pconn.getTableNoCache(table).getPhysicalName().getBytes();
          Map<String, String> now = new TreeMap<>();
          try (org.apache.hadoop.hbase.client.Admin admin = pconn.getQueryServices().getAdmin();
            RegionLocator locator =
              admin.getConnection().getRegionLocator(TableName.valueOf(physical))) {
            for (HRegionLocation loc : locator.getAllRegionLocations()) {
              now.put(loc.getRegion().getEncodedName(),
                loc.getServerName() == null ? "" : loc.getServerName().getServerName());
            }
          } catch (java.io.IOException e) {
            LOG.debug("Listing regions of {} failed", table, e);
            continue;
          }
          Map<String, String> before = assignments.put(table, now);
          record(table, now);
          if (before != null) {
            report(table, before, now);
          }
        }
      }
    }
  }

  private void record(String table, Map<String, String> regions) {
    if (csv == null) {
      return;
    }
    Map<String, Integer> perServer = new HashMap<>();
    for (String s : regions.values()) {
      perServer.merge(s, 1, Integer::sum);
    }
    long now = System.currentTimeMillis();
    csv.row(now, events == null ? 0 : now - events.startMillis(),
      events == null ? "" : events.stage(), table, regions.size(), perServer.size(),
      perServer.values().stream().mapToInt(Integer::intValue).max().orElse(0));
    csv.flush();
  }

  private void report(String table, Map<String, String> before, Map<String, String> now) {
    if (events == null) {
      return;
    }
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    int moved = 0;
    for (Map.Entry<String, String> e : now.entrySet()) {
      String prev = before.get(e.getKey());
      if (prev == null) {
        added.add(e.getKey());
      } else if (!prev.equals(e.getValue())) {
        moved++;
      }
    }
    for (String r : before.keySet()) {
      if (!now.containsKey(r)) {
        removed.add(r);
      }
    }
    if (!added.isEmpty() || !removed.isEmpty()) {
      String kind = now.size() > before.size() ? "region-split"
        : now.size() < before.size() ? "region-merge"
        : "region-change";
      events.event(kind, table + " regions " + before.size() + " -> " + now.size());
    }
    if (moved > 0) {
      events.event("region-move", table + " " + moved + " region(s) moved");
    }
  }

  /**
   * Runs the inspect commands on every index and returns their summaries. Reports are written to
   * {@code fsck-<label>-<index>-<command>.json}.
   */
  public Map<String, Object> inspect(String label) {
    Map<String, Object> out = new LinkedHashMap<>();
    try (Connection conn = connections.get()) {
      for (Map.Entry<String, String> e : indexToTable.entrySet()) {
        Map<String, Object> perIndex = new LinkedHashMap<>();
        for (String cmd : INSPECT_COMMANDS) {
          try {
            Map<String, Object> report =
              IndexFsck.inspect(conn, e.getValue(), e.getKey(), cmd, null);
            if (reportDir != null) {
              Json.write(
                reportDir.resolve("fsck-" + label + "-" + e.getKey() + "-" + cmd + ".json"),
                report);
            }
            switch (cmd) {
              case "list":
                perIndex.put(cmd, IndexFsck.summarizeList(report));
                break;
              case "coverage":
                perIndex.put(cmd, IndexFsck.summarizeCoverage(report));
                break;
              default:
                perIndex.put(cmd, IndexFsck.summarizeDelta(report));
                break;
            }
          } catch (Exception ex) {
            LOG.warn("indexfsck inspect {} of {} failed", cmd, e.getKey(), ex);
            perIndex.put(cmd, "failed: " + ex.getMessage());
          }
        }
        out.put(e.getKey(), perIndex);
      }
    } catch (SQLException ex) {
      LOG.warn("indexfsck inspect failed", ex);
      out.put("error", ex.getMessage());
    }
    synchronized (inspections) {
      inspections.put(label, out);
    }
    if (events != null) {
      events.event("fsck-inspect", label + " " + Json.toString(out).replaceAll("\\s+", " "));
    }
    return out;
  }

  public Map<String, Object> inspections() {
    synchronized (inspections) {
      return new LinkedHashMap<>(inspections);
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
