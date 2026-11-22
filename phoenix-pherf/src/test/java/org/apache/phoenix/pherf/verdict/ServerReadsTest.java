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
package org.apache.phoenix.pherf.verdict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Reads;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Row;
import org.apache.phoenix.pherf.verdict.HealthVerdict.Severity;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies RQ-205 by testing collection of per-server read counts from RegionServer JMX endpoints.
 * An embedded JDK HTTP server serves the table metrics bean, returning an initial snapshot for the
 * begin sample and a subsequent snapshot for the finish sample.
 */
public class ServerReadsTest {
  private final List<HttpServer> servers = new ArrayList<>();

  /**
   * Constructs a table metrics bean payload containing read counts for a user table and for system
   * tables.
   */
  private static String bean(long user, long otherUser, long system) {
    String prefix = "Namespace_";
    return "{\"beans\":[{\"name\":\"Hadoop:service=HBase,name=JvmMetrics\",\"Namespace_default"
      + "_table_JVM_metric_readRequestCount\":999},{\"name\":\"" + ServerReads.BEAN + "\","
      + "\"modelerType\":\"RegionServer,sub=Tables\",\"tag.Hostname\":\"rs\"," + "\"" + prefix
      + "default_table_PHERF.T_metric_readRequestCount\":" + user + ",\"" + prefix
      + "default_table_PHERF.T_metric_writeRequestCount\":12345,\"" + prefix
      + "app_table_ORDERS_metric_readRequestCount\":" + otherUser + ",\"" + prefix
      + "hbase_table_meta_metric_readRequestCount\":" + system + ",\"" + prefix
      + "default_table_SYSTEM.CATALOG_metric_readRequestCount\":" + system + ",\"" + prefix
      + "SYSTEM_table_CATALOG_metric_readRequestCount\":" + system + "}]}";
  }

  @After
  public void stop() {
    EnvironmentEdgeManager.reset();
    for (HttpServer server : servers) {
      server.stop(0);
    }
  }

  /** Provides a deterministic wall clock that advances only when a test explicitly updates it. */
  private static final class ManualEdge extends EnvironmentEdge {
    private volatile long now = 1_000_000;

    @Override
    public long currentTime() {
      return now;
    }
  }

  private static Row skew(Reads reads) {
    return HealthVerdict.readSkew(reads, 1.5);
  }

  /**
   * Starts a local endpoint that serves the provided snapshots sequentially, repeating the final
   * snapshot for any further requests while verifying the query of each request.
   */
  private String serve(String... snapshots) throws Exception {
    HttpServer server =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    AtomicInteger requests = new AtomicInteger();
    server.createContext("/jmx", exchange -> {
      String body;
      if (!ServerReads.QUERY.equals("?" + exchange.getRequestURI().getRawQuery())) {
        body = "{\"beans\":[]}";
      } else {
        body = snapshots[Math.min(requests.getAndIncrement(), snapshots.length - 1)];
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    servers.add(server);
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Test
  public void testUserTableReads() {
    assertEquals(10 + 20, ServerReads.userTableReads(bean(10, 20, 1000)));
    // A server payload lacking a table metrics bean reports zero user table reads.
    assertEquals(0, ServerReads.userTableReads("{\"beans\":[]}"));
  }

  /**
   * Verifies that read deltas of 907 and 50 reads attribute 907 out of 957 total reads to the first
   * server.
   */
  @Test
  public void testDeltasOfTwoServers() throws Exception {
    ManualEdge clock = new ManualEdge();
    EnvironmentEdgeManager.injectEdge(clock);
    String first = serve(bean(100, 0, 50), bean(1000, 7, 5000));
    String second = serve(bean(40, 0, 0), bean(90, 0, 70));
    ServerReads reads = new ServerReads(Arrays.asList(first, second + "/"));
    reads.begin();
    clock.now += HealthVerdict.MIN_READ_WINDOW_MS;
    Row row = skew(reads.finish());
    assertEquals(row.toString(), Severity.FAIL, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains(first + " has 907 of 957 reads on 2"));
  }

  /**
   * Verifies that the same samples collected 1 ms short of the minimum window duration are not
   * graded.
   */
  @Test
  public void testShortWindowSkips() throws Exception {
    ManualEdge clock = new ManualEdge();
    EnvironmentEdgeManager.injectEdge(clock);
    String first = serve(bean(100, 0, 50), bean(1000, 7, 5000));
    String second = serve(bean(40, 0, 0), bean(90, 0, 70));
    ServerReads reads = new ServerReads(Arrays.asList(first, second));
    reads.begin();
    clock.now += HealthVerdict.MIN_READ_WINDOW_MS - 1;
    Row row = skew(reads.finish());
    assertEquals(row.toString(), Severity.SKIP, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains("read window"));
  }

  /**
   * Verifies that a run stopping before the measured work begins has no first sample and is
   * skipped.
   */
  @Test
  public void testNoFirstSampleSkips() throws Exception {
    ServerReads reads = new ServerReads(Collections.singletonList(serve(bean(1, 0, 0))));
    Row row = skew(reads.finish());
    assertEquals(row.toString(), Severity.SKIP, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains("before the measured work"));
  }

  @Test
  public void testUnreadableSourceIsAnError() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    String closed = "http://127.0.0.1:" + port;
    String open = serve(bean(1, 0, 0));
    ServerReads reads = new ServerReads(Arrays.asList(open, closed));
    reads.begin();
    Row row = skew(reads.finish());
    assertEquals(row.toString(), Severity.ERROR, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains(closed));
  }

  @Test
  public void testNoSourceSkips() {
    ServerReads reads = new ServerReads(Collections.<String> emptyList());
    reads.begin();
    Row row = skew(reads.finish());
    assertEquals(row.toString(), Severity.SKIP, row.getSeverity());
    assertTrue(row.getDetail(), row.getDetail().contains(VerdictSettings.SERVER_JMX));
  }
}
