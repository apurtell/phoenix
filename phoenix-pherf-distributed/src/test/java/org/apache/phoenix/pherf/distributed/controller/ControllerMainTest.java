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
package org.apache.phoenix.pherf.distributed.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.InetAddress;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.pherf.distributed.ChildProcess;
import org.apache.phoenix.pherf.distributed.Simulation;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.junit.Test;

/**
 * Verifies RQ-243 by testing the controller process configuration, including port parsing, bind
 * address resolution, and run directory locking.
 */
public class ControllerMainTest {
  private static void refused(String expected, Runnable parse) {
    try {
      parse.run();
      fail("accepted: " + expected);
    } catch (IllegalArgumentException e) {
      assertEquals(expected, e.getMessage());
    }
  }

  @Test
  public void portsDefaultTo8780And8790() {
    // Decision 6 fixes these default port numbers, and only this test pins their literal values.
    assertEquals(8780, Protocol.REST_PORT);
    assertEquals(8790, Protocol.AGENT_PORT);
    assertEquals(Protocol.REST_PORT,
      ControllerMain.port("PHERF_REST_PORT", null, Protocol.REST_PORT));
    assertEquals(Protocol.AGENT_PORT,
      ControllerMain.port("PHERF_AGENT_PORT", " ", Protocol.AGENT_PORT));
    assertEquals(0, ControllerMain.port("PHERF_REST_PORT", "0", Protocol.REST_PORT));
    assertEquals(65535, ControllerMain.port("PHERF_REST_PORT", " 65535 ", Protocol.REST_PORT));
    for (String bad : new String[] { "x", "-1", "65536", "80.5" }) {
      refused("PHERF_REST_PORT is not a port: " + bad,
        () -> ControllerMain.port("PHERF_REST_PORT", bad, Protocol.REST_PORT));
    }
  }

  @Test
  public void bindAddressIsAnIpOrAHostName() throws Exception {
    assertNull(ControllerMain.bindAddress(null));
    assertNull(ControllerMain.bindAddress(" "));
    assertEquals(InetAddress.getByName("127.0.0.1"), ControllerMain.bindAddress("127.0.0.1"));
    assertEquals(InetAddress.getByName("::1"), ControllerMain.bindAddress(" ::1 "));
    assertTrue(ControllerMain.bindAddress("localhost").isLoopbackAddress());
    // An open IPv6 bracket fails immediately without performing a name lookup.
    refused("PHERF_BIND_ADDRESS is not an address: [::1", () -> ControllerMain.bindAddress("[::1"));
  }

  @Test
  public void lockHeldPastTheBoundIsRefused() throws Exception {
    File runDir = Simulation.runDir();
    FileLock lock = ControllerMain.lock(runDir);
    try {
      long start = System.nanoTime();
      try {
        ControllerMain.lock(runDir, 300, 50);
        fail("acquired a held lock");
      } catch (IllegalArgumentException e) {
        assertEquals("another controller uses PHERF_RUN_DIR " + runDir.getAbsoluteFile(),
          e.getMessage());
      }
      long elapsedNs = System.nanoTime() - start;
      assertTrue("waited " + elapsedNs + " ns", elapsedNs >= TimeUnit.MILLISECONDS.toNanos(300));
    } finally {
      lock.channel().close();
    }
    assertTrue(new File(runDir, ControllerMain.LOCK).isFile());
    ControllerMain.lock(runDir).channel().close();
  }

  @Test
  public void lockReleasedWithinTheBoundIsAcquired() throws Exception {
    File runDir = Simulation.runDir();
    FileLock held = ControllerMain.lock(runDir);
    Thread release = new Thread(() -> {
      try {
        Thread.sleep(300);
        held.channel().close();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }, "release-lock");
    long start = System.nanoTime();
    release.start();
    FileLock lock = ControllerMain.lock(runDir, 30000, 50);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    release.join();
    try {
      assertTrue(lock.isValid());
      assertTrue("waited " + elapsedMs + " ms", elapsedMs >= 250 && elapsedMs < 30000);
    } finally {
      lock.channel().close();
    }
  }

  @Test
  public void badConfigurationExitsTwo() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put("PHERF_RUN_DIR", Simulation.runDir().getPath());
    env.put("PHERF_REST_PORT", "rest");
    try (ChildProcess controller = ChildProcess.start("controller", ControllerMain.class, env)) {
      assertEquals(controller.output(), 2, controller.waitFor(60000));
      controller.awaitLine("PHERF_REST_PORT is not a port: rest", 60000);
    }
  }
}
