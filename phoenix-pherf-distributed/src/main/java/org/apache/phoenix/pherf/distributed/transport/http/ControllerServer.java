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
package org.apache.phoenix.pherf.distributed.transport.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;

/**
 * Manages the controller listeners built on the JDK HTTP server. The agent listener exclusively
 * serves the {@link Protocol#CHANNEL_CONTEXT} context across all network addresses, while all
 * listeners share a single cached executor and a single controller instance.
 */
public final class ControllerServer {
  private final ExecutorService executor;
  private final HttpServer agentListener;

  /** Binds the agent listener to the configured port, where port 0 selects a free port. */
  public ControllerServer(Controller controller, int agentPort) throws IOException {
    AtomicInteger threads = new AtomicInteger();
    this.executor = Executors.newCachedThreadPool(action -> {
      Thread thread = new Thread(action, "pherf-controller-http-" + threads.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    this.agentListener = HttpServer.create(new InetSocketAddress(agentPort), 0);
    agentListener.createContext(Protocol.CHANNEL_CONTEXT, new ChannelHandler(controller));
    agentListener.setExecutor(executor);
  }

  public void start() {
    agentListener.start();
  }

  /** Returns the local port to which the agent listener is bound. */
  public int agentPort() {
    return agentListener.getAddress().getPort();
  }

  /** Stops all listeners. The controller must be closed first so that no poll remains held. */
  public void close() {
    agentListener.stop(1);
    executor.shutdownNow();
  }
}
