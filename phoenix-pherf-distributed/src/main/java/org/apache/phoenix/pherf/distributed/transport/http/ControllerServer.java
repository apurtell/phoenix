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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.TestTable;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;

/**
 * Manages the two controller listeners built on the JDK HTTP server and bound to a single bind
 * address. The REST listener serves the "/" context so that every response it produces, including a
 * 404 error, is formatted as JSON, while the agent listener exclusively serves the
 * {@link Protocol#CHANNEL_CONTEXT} context. Binding the two listeners to separate ports isolates
 * them for network policy enforcement. Both listeners share a single cached executor, a single
 * controller instance, and a single test table.
 */
public final class ControllerServer {
  private final ExecutorService executor;
  private final HttpServer restListener;
  private final HttpServer agentListener;

  /**
   * Binds both listeners to their configured ports, where port 0 selects a free port.
   * @param bind the network address to which both listeners are bound, or null to bind to all
   *             interfaces.
   */
  public ControllerServer(Controller controller, TestTable tests, InetAddress bind, int restPort,
    int agentPort) throws IOException {
    AtomicInteger threads = new AtomicInteger();
    this.executor = Executors.newCachedThreadPool(action -> {
      Thread thread = new Thread(action, "pherf-controller-http-" + threads.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    this.restListener = HttpServer.create(new InetSocketAddress(bind, restPort), 0);
    try {
      this.agentListener = HttpServer.create(new InetSocketAddress(bind, agentPort), 0);
    } catch (IOException e) {
      restListener.stop(0);
      executor.shutdownNow();
      throw e;
    }
    restListener.createContext("/", new RestHandler(controller, tests));
    restListener.setExecutor(executor);
    agentListener.createContext(Protocol.CHANNEL_CONTEXT, new ChannelHandler(controller));
    agentListener.setExecutor(executor);
  }

  public void start() {
    restListener.start();
    agentListener.start();
  }

  /** Returns the local port to which the REST listener is bound. */
  public int restPort() {
    return restListener.getAddress().getPort();
  }

  /** Returns the local port to which the agent listener is bound. */
  public int agentPort() {
    return agentListener.getAddress().getPort();
  }

  /** Stops all listeners. The controller must be closed first so that no poll remains held. */
  public void close() {
    restListener.stop(1);
    agentListener.stop(1);
    executor.shutdownNow();
  }
}
