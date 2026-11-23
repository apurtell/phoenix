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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.pherf.distributed.clock.SystemClock;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.http.ControllerServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the controller process. The process is configured via the PHERF_AGENT_PORT
 * environment variable (which defaults to 8790, or selects an available free port when set to 0)
 * and the required PHERF_RUN_DIR environment variable (which specifies the directory for run
 * bundles, creating it if it does not exist). Receiving SIGTERM stops the listeners and exits with
 * status 0. The process exits with status 2 on a bad configuration and with status 1 on an
 * unexpected error.
 */
public final class ControllerMain {
  private static final Logger LOG = LoggerFactory.getLogger(ControllerMain.class);

  private ControllerMain() {
  }

  public static void main(String[] args) throws InterruptedException {
    int port;
    File runDir;
    try {
      port = port(System.getenv("PHERF_AGENT_PORT"));
      runDir = runDir(System.getenv("PHERF_RUN_DIR"));
    } catch (IllegalArgumentException e) {
      LOG.error("bad configuration: {}", e.getMessage());
      System.exit(2);
      return;
    }
    SystemClock clock = new SystemClock("pherf-controller-clock");
    Controller controller = new Controller(clock, runDir);
    ControllerServer server;
    try {
      server = new ControllerServer(controller, port);
    } catch (IOException e) {
      LOG.error("cannot listen on port {}: {}", port, e.toString());
      System.exit(1);
      return;
    }
    // On SIGTERM, stop the active listeners and explicitly halt the runtime with exit code 0;
    // otherwise, the JVM terminates with the default signal exit code 143.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      LOG.info("controller stopping");
      controller.close();
      server.close();
      clock.close();
      Runtime.getRuntime().halt(0);
    }, "pherf-controller-stop"));
    server.start();
    controller.start();
    LOG.info("agent channel listening on port {}", server.agentPort());
    new CountDownLatch(1).await();
  }

  /** Resolves and returns the run directory, creating it if it does not exist. */
  static File runDir(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new IllegalArgumentException("PHERF_RUN_DIR is not set");
    }
    File dir = new File(text.trim()).getAbsoluteFile();
    try {
      Files.createDirectories(dir.toPath());
    } catch (IOException e) {
      throw new IllegalArgumentException("PHERF_RUN_DIR " + dir + " is not a directory: " + e, e);
    }
    return dir;
  }

  static int port(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Protocol.AGENT_PORT;
    }
    try {
      int port = Integer.parseInt(text.trim());
      if (port >= 0 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException e) {
      // Fall through to throw the diagnostic exception below, whose message names the invalid
      // value.
    }
    throw new IllegalArgumentException("PHERF_AGENT_PORT is not a port: " + text);
  }
}
