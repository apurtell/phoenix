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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.phoenix.pherf.distributed.clock.SystemClock;
import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.http.HttpAgentTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the distributed Pherf agent process. The process reads its configuration from
 * environment variables: PHERF_CONTROLLER (required, such as http://host:8790), PHERF_ZOOKEEPER
 * (required, passed to Pherf as the -z value), and PHERF_AGENT_ID (falling back to HOSTNAME and
 * then to the local host name). The runtime class path must also provide pherf.properties, as
 * packaged in the phoenix-pherf jar. The process terminates with exit status 0 on a requested stop
 * or SIGTERM, status 2 when the agent is rejected or receives a bad configuration, and status 1 on
 * an unexpected error.
 */
public final class AgentMain {
  private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);

  private AgentMain() {
  }

  public static void main(String[] args) {
    Map<String, String> env = System.getenv();
    URI controller;
    OperationalIdentity identity;
    String zookeeper;
    Properties shipped;
    try {
      controller = controllerUri(env.get("PHERF_CONTROLLER"));
      identity = OperationalIdentity.create(OperationalIdentity.resolveId(env));
      zookeeper = zookeeper(env.get("PHERF_ZOOKEEPER"));
      shipped = shippedProperties();
    } catch (IllegalArgumentException | IOException e) {
      LOG.error("bad configuration: {}", e.getMessage());
      System.exit(2);
      return;
    }
    AtomicBoolean exiting = new AtomicBoolean();
    PherfWorkload workload = null;
    try {
      SystemClock clock = new SystemClock("pherf-agent-clock");
      File workDir = Files.createTempDirectory("pherf-agent-" + identity.getId() + "-").toFile();
      LOG.info("agent {} works in {}", identity.getId(), workDir);
      workload = new PherfWorkload(workDir, zookeeper, shipped,
        new ChildJvmLauncher(identity.getId(), System.getProperty("java.class.path")), clock);
      AgentRuntime runtime = new AgentRuntime(identity, clock,
        new HttpAgentTransport(controller, identity.getId()), workload);
      PherfWorkload stoppable = workload;
      // On SIGTERM, perform an orderly shutdown, terminate any child process that is still alive,
      // and explicitly halt with status 0; otherwise the JVM exits with status 143.
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        if (exiting.compareAndSet(false, true)) {
          runtime.stop();
          try {
            runtime.awaitStopped(Protocol.STOP_GRACE_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          stoppable.close();
          Runtime.getRuntime().halt(0);
        }
      }, "pherf-agent-stop"));
      runtime.start();
      runtime.awaitStopped(Long.MAX_VALUE);
      if (exiting.compareAndSet(false, true)) {
        workload.close();
        System.exit(runtime.exitStatus());
      }
    } catch (Throwable t) {
      LOG.error("agent {} failed", identity.getId(), t);
      if (exiting.compareAndSet(false, true)) {
        if (workload != null) {
          workload.close();
        }
        System.exit(1);
      }
    }
  }

  static URI controllerUri(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new IllegalArgumentException("PHERF_CONTROLLER is not set; use http://host:8790");
    }
    URI uri;
    try {
      uri = URI.create(text.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("PHERF_CONTROLLER is not a URI: " + text, e);
    }
    if (!"http".equals(uri.getScheme()) || uri.getHost() == null) {
      throw new IllegalArgumentException("PHERF_CONTROLLER is not http://host:port: " + text);
    }
    return uri;
  }

  static String zookeeper(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new IllegalArgumentException(
        "PHERF_ZOOKEEPER is not set; use the ZooKeeper quorum of the cluster, host:port");
    }
    return text.trim();
  }

  /**
   * Loads the baseline pherf.properties resource from the class path so that each child process
   * inherits these defaults beneath its own assigned property values.
   */
  static Properties shippedProperties() throws IOException {
    Properties properties = new Properties();
    try (
      InputStream in = AgentMain.class.getClassLoader().getResourceAsStream("pherf.properties")) {
      if (in == null) {
        throw new IOException("the class path has no pherf.properties; add the phoenix-pherf jar");
      }
      properties.load(in);
    }
    return properties;
  }
}
