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
 * Applies the timed disruptions of the steady state to a data table through administrative APIs:
 * region moves, region splits, merges of adjacent regions, major compactions, and RegionServer
 * restarts. The administrative API can stop a RegionServer but not start one, so restarts go
 * through a {@link ClusterControl}, which minicluster tests replace with one that restarts servers
 * of the minicluster; the default stops the server and relies on the cluster's supervisor to start
 * it again. Targets are chosen deterministically from the region order.
 */
public final class ChaosInjector {

  private static final Logger LOG = LoggerFactory.getLogger(ChaosInjector.class);
  static final long FUTURE_TIMEOUT_SECONDS = 300;

  /** Restarts RegionServers. */
  public interface ClusterControl {
    void restart(Admin admin, ServerName server) throws Exception;
  }

  /** Stops the server; a supervisor outside the harness must start it again. */
  public static final ClusterControl ADMIN_STOP =
    (admin, server) -> admin.stopRegionServer(server.getHostname() + ":" + server.getPort());

  private static volatile ClusterControl control = ADMIN_STOP;

  private ChaosInjector() {
  }

  /** Replaces the cluster control, for minicluster tests. */
  public static void setClusterControl(ClusterControl c) {
    control = c == null ? ADMIN_STOP : c;
  }

  /**
   * Applies one disruption to a data table.
   * @param ordinal the number of the disruption in the schedule, which rotates the target
   * @return a description of what was done
   */
  public static String apply(Disruption d, int ordinal, Connection conn, String fullTable,
    EventLog events) throws Exception {
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
          control.restart(admin, server);
          detail = "restarted " + server;
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
