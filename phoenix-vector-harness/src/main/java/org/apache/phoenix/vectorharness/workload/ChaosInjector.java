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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.Disruption;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies administrative disruptions during steady state testing: region moves, splits, merges,
 * major compactions, and RegionServer restarts. A restart is graceful, as in a production rolling
 * restart: the region mover of HBase moves the regions off the server before it stops, and back
 * after it starts. The {@link ClusterControl} stops and starts the server on standalone clusters
 * and in minicluster tests.
 */
public final class ChaosInjector {

  private static final Logger LOG = LoggerFactory.getLogger(ChaosInjector.class);
  static final long FUTURE_TIMEOUT_SECONDS = 300;

  /** Strategy interface for restarting RegionServers. */
  public interface ClusterControl {
    /**
     * Stops a drained RegionServer cleanly and waits until it serves again.
     * @return the name of the new instance
     */
    ServerName restart(Admin admin, ServerName server) throws Exception;
  }

  /**
   * The default control. It stops the RegionServer through the Admin API, and then waits until the
   * supervisor of the server starts it again.
   */
  public static final ClusterControl ADMIN_STOP = (admin, server) -> GracefulRestart
    .stopAndAwaitRestart(admin, server, GracefulRestart.STEP_TIMEOUT_MS, GracefulRestart.POLL_MS);

  private static volatile ClusterControl control = ADMIN_STOP;

  private ChaosInjector() {
  }

  /** Sets custom cluster control implementation, typically for minicluster tests. */
  public static void setClusterControl(ClusterControl c) {
    control = c == null ? ADMIN_STOP : c;
  }

  /**
   * Applies a scheduled disruption to a data table.
   * @param ordinal sequence number used to deterministically select the target region
   * @return description of the applied action
   */
  public static String apply(Disruption d, int ordinal, Connection conn, String fullTable,
    EventLog events) throws Exception {
    return apply(d, ordinal, conn, fullTable, events, null);
  }

  /**
   * Applies a scheduled disruption to a data table and adds its measured values to a record.
   * @param ordinal sequence number used to deterministically select the target region
   * @param record  the disruption record that gets the time of each restart step, or null
   * @return description of the applied action
   */
  public static String apply(Disruption d, int ordinal, Connection conn, String fullTable,
    EventLog events, Map<String, Object> record) throws Exception {
    TableName tn = StorageFootprint.physicalName(conn, fullTable);
    try (Admin admin = HarnessContext.admin(conn)) {
      List<RegionInfo> regions = new ArrayList<>(admin.getRegions(tn));
      regions.sort(Comparator.comparing(RegionInfo::getStartKey, Bytes.BYTES_COMPARATOR));
      if (regions.isEmpty()) {
        throw new IllegalStateException("Table " + tn + " has no regions");
      }
      RegionInfo target = regions.get(Math.floorMod(ordinal, regions.size()));
      String detail;
      switch (d.kind()) {
        case MOVE: {
          ServerName from = serverOf(admin, tn, target);
          List<ServerName> servers =
            new ArrayList<>(admin.getClusterMetrics().getLiveServerMetrics().keySet());
          servers.remove(from);
          Collections.sort(servers);
          if (servers.isEmpty()) {
            detail = "no other server to move " + target.getEncodedName() + " to";
            break;
          }
          ServerName to = servers.get(Math.floorMod(ordinal, servers.size()));
          admin.move(target.getEncodedNameAsBytes(), to);
          detail = "moved " + target.getEncodedName() + " from " + from + " to " + to;
          break;
        }
        case SPLIT:
          admin.splitRegionAsync(target.getRegionName()).get(FUTURE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS);
          detail = "split " + target.getEncodedName();
          break;
        case MERGE: {
          if (regions.size() < 2) {
            detail = "one region, nothing to merge";
            break;
          }
          int i = Math.floorMod(ordinal, regions.size() - 1);
          RegionInfo a = regions.get(i);
          RegionInfo b = regions.get(i + 1);
          admin
            .mergeRegionsAsync(
              new byte[][] { a.getEncodedNameAsBytes(), b.getEncodedNameAsBytes() }, false)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          detail = "merged " + a.getEncodedName() + " and " + b.getEncodedName();
          break;
        }
        case COMPACT:
          admin.flush(tn);
          admin.majorCompact(tn);
          detail = "flushed and major compacted " + tn;
          break;
        case RESTART: {
          ServerName server = serverOf(admin, tn, target);
          if (server == null) {
            detail = "no server hosts " + target.getEncodedName();
            break;
          }
          GracefulRestart.Mover mover =
            GracefulRestart.regionMover(admin.getConfiguration(), GracefulRestart.STEP_TIMEOUT_MS);
          GracefulRestart.Cycle cycle = new GracefulRestart(admin, mover,
            GracefulRestart.STEP_TIMEOUT_MS, GracefulRestart.POLL_MS).restart(server, control);
          if (record != null) {
            cycle.record(record);
          }
          detail = cycle.detail();
          break;
        }
        default:
          throw new IllegalArgumentException("Unknown disruption " + d.kind());
      }
      if (events != null) {
        events.event("disruption", d + " " + detail);
      }
      LOG.info("Disruption {}: {}", d, detail);
      return detail;
    }
  }

  static ServerName serverOf(Admin admin, TableName tn, RegionInfo region) throws Exception {
    try (RegionLocator locator = admin.getConnection().getRegionLocator(tn)) {
      HRegionLocation loc = locator.getRegionLocation(region.getStartKey(), true);
      return loc == null ? null : loc.getServerName();
    }
  }
}
