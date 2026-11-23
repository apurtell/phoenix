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

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.phoenix.pherf.distributed.clock.SystemClock;
import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Assignment;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.http.HttpAgentTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the distributed Pherf agent process. Configuration is read from the environment:
 * PHERF_CONTROLLER is required (for example, http://host:8790), while PHERF_AGENT_ID is optional
 * and falls back first to HOSTNAME and then to the resolved local host name. The process exits with
 * status 0 on SIGTERM or a requested stop, status 2 when rejected by the controller or given a bad
 * configuration, and status 1 on an unexpected error.
 */
public final class AgentMain {
  private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);
  /**
   * Diagnostic error reported for every assignment until a build wires in the actual Pherf workload
   * execution engine.
   */
  static final String NO_WORKLOAD = "this build runs no Pherf workload";

  private AgentMain() {
  }

  public static void main(String[] args) {
    Map<String, String> env = System.getenv();
    URI controller;
    OperationalIdentity identity;
    try {
      controller = controllerUri(env.get("PHERF_CONTROLLER"));
      identity = OperationalIdentity.create(OperationalIdentity.resolveId(env));
    } catch (IllegalArgumentException e) {
      LOG.error("bad configuration: {}", e.getMessage());
      System.exit(2);
      return;
    }
    AtomicBoolean exiting = new AtomicBoolean();
    try {
      SystemClock clock = new SystemClock("pherf-agent-clock");
      AgentRuntime runtime = new AgentRuntime(identity, clock,
        new HttpAgentTransport(controller, identity.getId()), new NoWorkload());
      // On SIGTERM, perform an orderly runtime shutdown and then explicitly halt with status 0;
      // otherwise the JVM exits with the default signal status of 143.
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        if (exiting.compareAndSet(false, true)) {
          runtime.stop();
          try {
            runtime.awaitStopped(Protocol.STOP_GRACE_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          Runtime.getRuntime().halt(0);
        }
      }, "pherf-agent-stop"));
      runtime.start();
      runtime.awaitStopped(Long.MAX_VALUE);
      if (exiting.compareAndSet(false, true)) {
        System.exit(runtime.exitStatus());
      }
    } catch (Throwable t) {
      LOG.error("agent {} failed", identity.getId(), t);
      if (exiting.compareAndSet(false, true)) {
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

  /**
   * Placeholder workload implementation for builds without Pherf execution wired in, which fails
   * every assignment during the prepare phase.
   */
  private static final class NoWorkload implements Workload {
    @Override
    public void prepare(Assignment assignment, Listener listener) {
      listener.prepared(Collections.singletonList(NO_WORKLOAD));
    }

    @Override
    public void start(Assignment assignment, long startAtMs, Listener listener) {
      listener.failed(NO_WORKLOAD);
    }

    @Override
    public void cancel(String runId) {
    }
  }
}
