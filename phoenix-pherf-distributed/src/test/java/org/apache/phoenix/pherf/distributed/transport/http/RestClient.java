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

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.apache.phoenix.pherf.distributed.Simulation;

/**
 * Executes real HTTP/1.1 requests against the REST listener of a ControllerServer bound to
 * 127.0.0.1 on ephemeral ports, operating over the controller and test table of a
 * {@link Simulation}.
 * <p>
 * Because the server handler thread mutates the controller, the virtual clock, and the in-process
 * network exclusively while holding the controller monitor, each request acquires and releases that
 * monitor before and after execution so that the test thread and the handler thread observe each
 * other's state transitions. Because each request blocks the calling test thread until completion,
 * tests remain completely deterministic without relying on sleep intervals.
 */
public final class RestClient implements AutoCloseable {
  private final Simulation simulation;
  private ControllerServer server;

  public RestClient(Simulation simulation) throws IOException {
    this.simulation = simulation;
    serve();
  }

  /** Binds a new server instance to the current controller and test table of the simulation. */
  public void serve() throws IOException {
    if (server != null) {
      server.close();
    }
    server = new ControllerServer(simulation.controller, simulation.tests,
      InetAddress.getLoopbackAddress(), 0, 0);
    server.start();
  }

  public int restPort() {
    return server.restPort();
  }

  public int agentPort() {
    return server.agentPort();
  }

  public Response get(String path) throws IOException {
    return call("GET", path, null);
  }

  public Response post(String path, String body) throws IOException {
    return call("POST", path, body);
  }

  /** Dispatches an HTTP request to the REST listener within the controller monitor fence. */
  public Response call(String method, String path, String body) throws IOException {
    return call(restPort(), method, path, body);
  }

  /**
   * Dispatches an HTTP request to either listener of the server within the controller monitor
   * fence.
   */
  public Response call(int port, String method, String path, String body) throws IOException {
    synchronized (simulation.controller) {
      // Establish a memory synchronization fence on the controller monitor before the request.
    }
    try {
      return call("http://127.0.0.1:" + port, method, path, body);
    } finally {
      synchronized (simulation.controller) {
        // Establish a memory synchronization fence on the controller monitor after the request.
      }
    }
  }

  /**
   * Executes a direct HTTP request without a monitor synchronization fence, intended for targeting
   * a controller running in another process.
   */
  public static Response call(String base, String method, String path, String body)
    throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
    connection.setRequestMethod(method);
    // Use a dedicated connection for each call by disabling keep-alive, which prevents the HTTP
    // client from silently retrying a POST request on a reused connection.
    connection.setRequestProperty("Connection", "close");
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(30000);
    if (body != null) {
      connection.setDoOutput(true);
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(bytes);
      }
    }
    int code = connection.getResponseCode();
    InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    if (in != null) {
      try (InputStream stream = in) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) > 0) {
          bytes.write(buffer, 0, n);
        }
      }
    }
    Response response =
      new Response(code, connection.getContentType(), connection.getHeaderField("Allow"),
        connection.getHeaderField("Connection"), bytes.toByteArray());
    connection.disconnect();
    return response;
  }

  @Override
  public void close() {
    server.close();
  }

  /** Captures the status, headers, and payload of a single HTTP response. */
  public static final class Response {
    public final int code;
    public final String contentType;
    public final String allow;
    /** Stores the value of the Connection header returned in the HTTP reply. */
    public final String connection;
    public final byte[] body;

    Response(int code, String contentType, String allow, String connection, byte[] body) {
      this.code = code;
      this.contentType = contentType;
      this.allow = allow;
      this.connection = connection;
      this.body = body;
    }

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public JsonObject json() {
      return JsonParser.parseString(text()).getAsJsonObject();
    }

    /**
     * Asserts that the response status code matches the expected value and returns the parsed JSON
     * body.
     */
    public JsonObject json(int expected) {
      assertEquals(text(), expected, code);
      return json();
    }

    /**
     * Asserts that the reply conforms to the stable error response contract by verifying the status
     * code, the JSON content type, and a payload containing exclusively the error and message
     * members, then returns the message string.
     */
    public String error(int expected, String error) {
      JsonObject json = json(expected);
      assertEquals(text(), ChannelHandler.JSON, contentType);
      assertEquals(text(), 2, json.size());
      assertEquals(text(), error, json.get("error").getAsString());
      return json.get("message").getAsString();
    }
  }
}
