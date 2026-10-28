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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.ClusterMetrics;
import org.apache.hadoop.hbase.ServerMetrics;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;

public class GracefulRestartTest {

  private static final TableName TABLE = TableName.valueOf("T");
  private static final ServerName A = ServerName.valueOf("localhost", 16020, 1L);
  private static final ServerName B = ServerName.valueOf("localhost", 16120, 1L);
  private static final ServerName C = ServerName.valueOf("localhost", 16220, 1L);
  private static final ServerName A2 = ServerName.valueOf("localhost", 16020, 2L);

  /** A small cluster model behind a proxy of the Admin interface. */
  private static final class FakeCluster {
    final Map<String, RegionInfo> regions = new LinkedHashMap<>();
    final Map<String, ServerName> location = new HashMap<>();
    final List<ServerName> live = new ArrayList<>();
    /** Live servers that have not sent a load report yet. */
    final Set<ServerName> unreported = new HashSet<>();
    /** The number of live server queries after which each server has reported. */
    int reportAt;
    final List<String> stops = new ArrayList<>();
    boolean balancer = true;
    int liveQueries;

    RegionInfo add(String start, ServerName on) {
      RegionInfo r = start == null
        ? RegionInfoBuilder.FIRST_META_REGIONINFO
        : RegionInfoBuilder.newBuilder(TABLE).setStartKey(Bytes.toBytes(start)).build();
      regions.put(r.getEncodedName(), r);
      location.put(r.getEncodedName(), on);
      return r;
    }

    List<RegionInfo> on(ServerName s) {
      List<RegionInfo> out = new ArrayList<>();
      for (RegionInfo r : regions.values()) {
        if (s.equals(location.get(r.getEncodedName()))) {
          out.add(r);
        }
      }
      return out;
    }

    /** Replaces a server by a new instance at the same address. */
    void rejoin(ServerName old, ServerName fresh) {
      live.remove(old);
      live.add(fresh);
    }

    Admin admin() {
      return (Admin) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] { Admin.class }, (proxy, method, args) -> {
          switch (method.getName()) {
            case "balancerSwitch": {
              boolean before = balancer;
              balancer = (Boolean) args[0];
              return before;
            }
            case "getRegions":
              return on((ServerName) args[0]);
            case "getClusterMetrics":
              liveQueries++;
              if (liveQueries >= reportAt) {
                unreported.clear();
              }
              return metrics(live, unreported);
            case "stopRegionServer":
              stops.add((String) args[0]);
              return null;
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
    }
  }

  /**
   * Moves regions in the fake cluster as the region mover does. The unload spreads the regions over
   * the other live servers and writes their names to the list file.
   */
  private static class FakeMover implements GracefulRestart.Mover {
    private final FakeCluster c;
    boolean unloadResult = true;
    boolean loadResult = true;
    int leaveBehind;
    final List<Path> files = new ArrayList<>();

    FakeMover(FakeCluster c) {
      this.c = c;
    }

    @Override
    public boolean unload(ServerName server, Path file) throws Exception {
      files.add(file);
      List<ServerName> targets = new ArrayList<>(c.live);
      targets.remove(server);
      List<String> moved = new ArrayList<>();
      List<RegionInfo> hosted = c.on(server);
      for (int i = leaveBehind; i < hosted.size(); i++) {
        RegionInfo r = hosted.get(i);
        c.location.put(r.getEncodedName(), targets.get(i % targets.size()));
        moved.add(r.getEncodedName());
      }
      Files.write(file, moved, StandardCharsets.UTF_8);
      return unloadResult;
    }

    @Override
    public boolean load(ServerName server, Path file) throws Exception {
      files.add(file);
      for (String name : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (c.regions.containsKey(name)) {
          c.location.put(name, server);
        }
      }
      return loadResult;
    }
  }

  private static ClusterMetrics metrics(List<ServerName> live, Set<ServerName> unreported) {
    Map<ServerName, ServerMetrics> m = new LinkedHashMap<>();
    for (ServerName s : live) {
      long lastReport = unreported.contains(s) ? 0 : 1;
      m.put(s, (ServerMetrics) Proxy.newProxyInstance(GracefulRestartTest.class.getClassLoader(),
        new Class<?>[] { ServerMetrics.class }, (proxy, method, args) -> {
          if (method.getName().equals("getLastReportTimestamp")) {
            return lastReport;
          }
          throw new UnsupportedOperationException(method.getName());
        }));
    }
    return (ClusterMetrics) Proxy.newProxyInstance(GracefulRestartTest.class.getClassLoader(),
      new Class<?>[] { ClusterMetrics.class }, (proxy, method, args) -> {
        if (method.getName().equals("getLiveServerMetrics")) {
          return m;
        }
        throw new UnsupportedOperationException(method.getName());
      });
  }

  private static FakeCluster threeServers() {
    FakeCluster c = new FakeCluster();
    c.live.add(A);
    c.live.add(B);
    c.live.add(C);
    return c;
  }

  private static GracefulRestart restarter(FakeCluster c, FakeMover m) {
    return new GracefulRestart(c.admin(), m, 50, 1);
  }

  private static ChaosInjector.ClusterControl rejoining(FakeCluster c) {
    return (admin, server) -> {
      c.rejoin(server, A2);
      return A2;
    };
  }

  @Test
  public void testRestartDrainsTheServerThenLoadsItAgain() throws Exception {
    FakeCluster c = threeServers();
    RegionInfo meta = c.add(null, A);
    c.add("a", A);
    c.add("b", A);
    c.add("c", A);
    RegionInfo other = c.add("d", B);
    FakeMover m = new FakeMover(c);
    GracefulRestart.Cycle cycle = restarter(c, m).restart(A, (admin, server) -> {
      assertEquals(A, server);
      assertFalse("the balancer is off during the restart", c.balancer);
      assertEquals("the server is empty when it stops", 0, c.on(A).size());
      c.rejoin(A, A2);
      return A2;
    });
    assertEquals(4, c.on(A2).size());
    assertEquals(B, c.location.get(other.getEncodedName()));
    assertEquals(A2, c.location.get(meta.getEncodedName()));
    assertTrue("the balancer is on again", c.balancer);
    String detail = cycle.detail();
    assertTrue(detail, detail.startsWith("restarted " + A + " as " + A2));
    assertTrue(detail, detail.contains("unloaded 4 regions"));
    assertTrue(detail, detail.contains("loaded 4 regions"));
    Map<String, Object> record = new LinkedHashMap<>();
    cycle.record(record);
    double total = 0;
    for (String step : new String[] { "drain_seconds", "restart_seconds", "load_seconds" }) {
      double seconds = (Double) record.get(step);
      assertTrue(step + " " + seconds, seconds >= 0);
      total += seconds;
    }
    assertEquals(total, (Double) record.get("cycle_seconds"), 1e-9);
  }

  @Test
  public void testUnloadAndLoadShareOneListFile() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    FakeMover m = new FakeMover(c);
    restarter(c, m).restart(A, rejoining(c));
    assertEquals(2, m.files.size());
    Path file = m.files.get(0);
    assertNotNull(file);
    assertEquals("the load reads the list of the unload", file, m.files.get(1));
    assertFalse("the restart removes the list file", Files.exists(file));
  }

  @Test
  public void testFailedDrainDoesNotStopTheServer() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    FakeMover m = new FakeMover(c);
    m.unloadResult = false;
    try {
      restarter(c, m).restart(A, (admin, server) -> {
        fail("a failed drain must not stop the server");
        return null;
      });
      fail("the drain failed");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("could not move all regions off"));
    }
    assertTrue("the balancer is on again after the failure", c.balancer);
  }

  @Test
  public void testDrainThatLeavesARegionFailsAtTheTimeout() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    c.add("b", A);
    FakeMover m = new FakeMover(c);
    m.leaveBehind = 1;
    try {
      restarter(c, m).restart(A, (admin, server) -> {
        fail("a server that still hosts regions must not stop");
        return null;
      });
      fail("the drain left a region");
    } catch (TimeoutException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("still hosts 1 regions"));
    }
    assertTrue(c.balancer);
  }

  @Test
  public void testFailedLoadFailsTheRestart() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    FakeMover m = new FakeMover(c);
    m.loadResult = false;
    try {
      restarter(c, m).restart(A, rejoining(c));
      fail("the load failed");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("back to " + A2));
    }
    assertTrue(c.balancer);
  }

  @Test
  public void testLoadWaitsForTheFirstReportOfTheNewInstance() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    FakeMover m = new FakeMover(c) {
      @Override
      public boolean load(ServerName server, Path file) throws Exception {
        assertFalse("the new instance reports before the load", c.unreported.contains(server));
        return super.load(server, file);
      }
    };
    restarter(c, m).restart(A, (admin, server) -> {
      c.rejoin(server, A2);
      c.unreported.add(A2);
      c.reportAt = c.liveQueries + 3;
      return A2;
    });
    assertEquals(1, c.on(A2).size());
  }

  @Test
  public void testRestartFailsWhenTheNewInstanceSendsNoReport() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    c.reportAt = Integer.MAX_VALUE;
    FakeMover m = new FakeMover(c);
    try {
      restarter(c, m).restart(A, (admin, server) -> {
        c.rejoin(server, A2);
        c.unreported.add(A2);
        return A2;
      });
      fail("the new instance sent no report");
    } catch (TimeoutException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no load report"));
    }
    assertEquals("only the unload ran", 1, m.files.size());
    assertTrue(c.balancer);
  }

  @Test
  public void testRestartWithoutNewServerNameFails() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    try {
      restarter(c, new FakeMover(c)).restart(A, (admin, server) -> null);
      fail("the restart gave no server name");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no new server name"));
    }
    assertTrue(c.balancer);
  }

  @Test
  public void testRestartKeepsAnOffBalancerOff() throws Exception {
    FakeCluster c = threeServers();
    c.add("a", A);
    c.balancer = false;
    restarter(c, new FakeMover(c)).restart(A, rejoining(c));
    assertFalse(c.balancer);
  }

  @Test
  public void testStopWaitsForTheNewInstanceOfTheServer() throws Exception {
    FakeCluster c = new FakeCluster();
    c.live.add(A);
    c.live.add(B);
    Admin admin = new FakeClusterAdmin(c, 3, 6).admin();
    ServerName next = GracefulRestart.stopAndAwaitRestart(admin, A, 1000, 1);
    assertEquals(A2, next);
    assertEquals("[localhost:16020]", c.stops.toString());
  }

  @Test
  public void testStopFailsWhenNoNewInstanceRegisters() throws Exception {
    FakeCluster c = new FakeCluster();
    c.live.add(A);
    c.live.add(B);
    Admin admin = new FakeClusterAdmin(c, 1, Integer.MAX_VALUE).admin();
    try {
      GracefulRestart.stopAndAwaitRestart(admin, A, 50, 1);
      fail("no new instance registered");
    } catch (TimeoutException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("supervisor"));
    }
  }

  @Test
  public void testStopFailsWhenTheServerKeepsRunning() throws Exception {
    FakeCluster c = new FakeCluster();
    c.live.add(A);
    c.live.add(B);
    Admin admin = new FakeClusterAdmin(c, Integer.MAX_VALUE, Integer.MAX_VALUE).admin();
    try {
      GracefulRestart.stopAndAwaitRestart(admin, A, 50, 1);
      fail("the server did not stop");
    } catch (TimeoutException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("did not stop"));
    }
  }

  /**
   * Drives the server set of a fake cluster through its live server queries. The stopped server
   * leaves the set at one query, and its new instance joins at a later query.
   */
  private static final class FakeClusterAdmin {
    private final FakeCluster c;
    private final int leaveAt;
    private final int joinAt;

    FakeClusterAdmin(FakeCluster c, int leaveAt, int joinAt) {
      this.c = c;
      this.leaveAt = leaveAt;
      this.joinAt = joinAt;
    }

    Admin admin() {
      Admin inner = c.admin();
      return (Admin) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] { Admin.class }, (proxy, method, args) -> {
          if (method.getName().equals("getClusterMetrics")) {
            int n = c.liveQueries + 1;
            if (n == leaveAt) {
              c.live.remove(A);
            }
            if (n == joinAt) {
              c.live.add(A2);
            }
          }
          return method.invoke(inner, args);
        });
    }
  }
}
