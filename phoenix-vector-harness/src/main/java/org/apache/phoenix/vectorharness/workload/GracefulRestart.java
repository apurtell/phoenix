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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterMetrics;
import org.apache.hadoop.hbase.ServerMetrics;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.util.RegionMover;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restarts one region server as a graceful rolling restart in production does. The balancer stops
 * first, so it does not undo the drain. The region mover of HBase moves each region to another
 * server and waits until that server serves it. Then the server stops cleanly, and its supervisor
 * starts it again. When the new instance reports to the master, the region mover moves the drained
 * regions back to it. Each step has a timeout, and a step that does not finish fails the restart.
 */
final class GracefulRestart {

  private static final Logger LOG = LoggerFactory.getLogger(GracefulRestart.class);

  /**
   * The time limit of each wait. A step has up to three waits, so one step can take up to about
   * three limits.
   */
  static final long STEP_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
  static final long POLL_MS = 500;

  /** Moves the regions of one server. The region list file connects the unload and the load. */
  interface Mover {
    /**
     * Moves all regions off a server and writes their list to the file.
     * @return false when some region did not move
     */
    boolean unload(ServerName server, Path file) throws Exception;

    /**
     * Moves the regions of the list file to a server. A region that no longer exists does not move.
     * @return false when some region did not move
     */
    boolean load(ServerName server, Path file) throws Exception;
  }

  /**
   * Returns a mover that runs the region mover of HBase in ack mode. Each unload and each load
   * stops at the timeout.
   */
  static Mover regionMover(Configuration conf, long timeoutMs) {
    int seconds = (int) Math.max(1, TimeUnit.MILLISECONDS.toSeconds(timeoutMs));
    return new Mover() {
      @Override
      public boolean unload(ServerName server, Path file) throws Exception {
        try (RegionMover rm = builder(server, file).build()) {
          return rm.unload();
        }
      }

      @Override
      public boolean load(ServerName server, Path file) throws Exception {
        try (RegionMover rm = builder(server, file).build()) {
          return rm.load();
        }
      }

      private RegionMover.RegionMoverBuilder builder(ServerName server, Path file) {
        return new RegionMover.RegionMoverBuilder(hostAndPort(server), conf).ack(true)
          .timeout(seconds).filename(file.toString());
      }
    };
  }

  private final Admin admin;
  private final Mover mover;
  private final long stepTimeoutMs;
  private final long pollMs;

  GracefulRestart(Admin admin, Mover mover, long stepTimeoutMs, long pollMs) {
    this.admin = admin;
    this.mover = mover;
    this.stepTimeoutMs = stepTimeoutMs;
    this.pollMs = pollMs;
  }

  /** The result of one restart: the time of each step and a description. */
  static final class Cycle {
    private final double drainSeconds;
    private final double restartSeconds;
    private final double loadSeconds;
    private final String detail;

    Cycle(double drainSeconds, double restartSeconds, double loadSeconds, String detail) {
      this.drainSeconds = drainSeconds;
      this.restartSeconds = restartSeconds;
      this.loadSeconds = loadSeconds;
      this.detail = detail;
    }

    /** Returns a description that starts with "restarted" and gives the time of each step. */
    String detail() {
      return detail;
    }

    /**
     * Adds the time of each step to a disruption record. The recovery time starts after the load,
     * so these numbers show a slow restart.
     */
    void record(Map<String, Object> record) {
      record.put("drain_seconds", drainSeconds);
      record.put("restart_seconds", restartSeconds);
      record.put("load_seconds", loadSeconds);
      record.put("cycle_seconds", drainSeconds + restartSeconds + loadSeconds);
    }

    @Override
    public String toString() {
      return detail;
    }
  }

  /**
   * Drains, restarts and loads one region server. The balancer gets its earlier state back, also
   * when a step fails.
   */
  Cycle restart(ServerName server, ChaosInjector.ClusterControl control) throws Exception {
    boolean balancer = admin.balancerSwitch(false, true);
    Cycle detail;
    try {
      detail = cycle(server, control);
    } catch (Exception e) {
      if (balancer) {
        try {
          admin.balancerSwitch(true, false);
        } catch (IOException suppressed) {
          e.addSuppressed(suppressed);
        }
      }
      throw e;
    }
    if (balancer) {
      admin.balancerSwitch(true, false);
    }
    return detail;
  }

  private Cycle cycle(ServerName server, ChaosInjector.ClusterControl control) throws Exception {
    Path file = Files.createTempFile("vh-region-mover-", ".regions");
    try {
      long t0 = System.nanoTime();
      int hosted = admin.getRegions(server).size();
      if (!mover.unload(server, file)) {
        throw new IllegalStateException("The region mover could not move all regions off " + server
          + "; the server keeps running");
      }
      awaitEmpty(server);
      long t1 = System.nanoTime();
      LOG.info("Unloaded {} regions from {}", hosted, server);
      ServerName restarted = control.restart(admin, server);
      if (restarted == null) {
        throw new IllegalStateException("The restart of " + server + " gave no new server name");
      }
      awaitReported(restarted);
      long t2 = System.nanoTime();
      LOG.info("Restarted {} as {}", server, restarted);
      if (!mover.load(restarted, file)) {
        throw new IllegalStateException(
          "The region mover could not move all regions back to " + restarted);
      }
      int loaded = admin.getRegions(restarted).size();
      long t3 = System.nanoTime();
      double drain = seconds(t0, t1);
      double restart = seconds(t1, t2);
      double load = seconds(t2, t3);
      return new Cycle(drain, restart, load,
        String.format(Locale.ROOT,
          "restarted %s as %s: unloaded %d regions in %.1f s, restart took %.1f s,"
            + " loaded %d regions in %.1f s",
          server, restarted, hosted, drain, restart, loaded, load));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /**
   * Waits until the server reports no online region. A server with a region must not stop, because
   * the stop makes that region unavailable.
   */
  private void awaitEmpty(ServerName server) throws Exception {
    long start = System.currentTimeMillis();
    while (true) {
      List<RegionInfo> left = admin.getRegions(server);
      if (left.isEmpty()) {
        return;
      }
      if (System.currentTimeMillis() - start >= stepTimeoutMs) {
        throw new TimeoutException(server + " still hosts " + left.size()
          + " regions after the drain; the server keeps running");
      }
      Thread.sleep(pollMs);
    }
  }

  /**
   * Waits until the new instance sends its first load report to the master. Until then, the master
   * does not select the server as a destination. A move to the server in that period can open the
   * region on any other server. The region mover can count that move as done or as failed.
   */
  private void awaitReported(ServerName server) throws Exception {
    long start = System.currentTimeMillis();
    while (true) {
      try {
        ServerMetrics m = admin.getClusterMetrics(EnumSet.of(ClusterMetrics.Option.LIVE_SERVERS))
          .getLiveServerMetrics().get(server);
        if (m != null && m.getLastReportTimestamp() > 0) {
          return;
        }
      } catch (IOException e) {
        LOG.debug("Listing the live servers failed", e);
      }
      if (System.currentTimeMillis() - start >= stepTimeoutMs) {
        throw new TimeoutException(server + " sent no load report to the master within "
          + elapsed(start) + " s; its regions stay on the other servers");
      }
      Thread.sleep(pollMs);
    }
  }

  /**
   * Stops a region server cleanly through the master and waits for a new instance at the same
   * address. A supervisor, such as a container restart policy, must start the stopped server.
   * @return the name of the new instance
   */
  static ServerName stopAndAwaitRestart(Admin admin, ServerName server, long timeoutMs, long pollMs)
    throws Exception {
    admin.stopRegionServer(hostAndPort(server));
    long start = System.currentTimeMillis();
    while (true) {
      Set<ServerName> live = pollLiveServers(admin);
      if (live != null && !live.contains(server)) {
        break;
      }
      if (System.currentTimeMillis() - start >= timeoutMs) {
        throw new TimeoutException(server + " did not stop within " + elapsed(start) + " s");
      }
      Thread.sleep(pollMs);
    }
    LOG.info("{} stopped after {} s", server, elapsed(start));
    start = System.currentTimeMillis();
    while (true) {
      Set<ServerName> live = pollLiveServers(admin);
      for (ServerName s : live == null ? Collections.<ServerName> emptySet() : live) {
        if (
          s.getAddress().equals(server.getAddress()) && s.getStartcode() != server.getStartcode()
        ) {
          return s;
        }
      }
      if (System.currentTimeMillis() - start >= timeoutMs) {
        throw new TimeoutException("No new instance of " + server.getAddress()
          + " registered within " + elapsed(start) + " s; a supervisor must start the server");
      }
      Thread.sleep(pollMs);
    }
  }

  /** Returns the live servers, or null when the master does not answer. The caller polls again. */
  private static Set<ServerName> pollLiveServers(Admin admin) {
    try {
      ClusterMetrics m = admin.getClusterMetrics(EnumSet.of(ClusterMetrics.Option.LIVE_SERVERS));
      return new HashSet<>(m.getLiveServerMetrics().keySet());
    } catch (IOException e) {
      LOG.debug("Listing the live servers failed", e);
      return null;
    }
  }

  private static String hostAndPort(ServerName server) {
    return server.getHostname() + ":" + server.getPort();
  }

  private static double seconds(long startNanos, long endNanos) {
    return (endNanos - startNanos) / 1e9;
  }

  private static String elapsed(long startMillis) {
    return String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - startMillis) / 1000.0);
  }
}
