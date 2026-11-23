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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.phoenix.pherf.distributed.protocol.AgentMessage;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.transport.AgentChannel;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.apache.phoenix.pherf.distributed.transport.ChannelRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Serves as the HTTP request handler for the agent communication channel. An HTTP 200 response
 * carries the protocol reply, while a 400 response carries a rejected message when the request body
 * is not a valid request. The handler returns 405 for any HTTP method other than POST, 404 for any
 * unrecognized path, and 413 when the request body exceeds {@link Protocol#MAX_BODY_BYTES}. A held
 * poll blocks its dedicated handler thread until the controller answers.
 */
final class ChannelHandler implements HttpHandler {
  static final String JSON = "application/json; charset=utf-8";
  private static final Logger LOG = LoggerFactory.getLogger(ChannelHandler.class);

  private final AgentChannel channel;

  ChannelHandler(AgentChannel channel) {
    this.channel = channel;
  }

  /**
   * Echoes the "Connection: close" header in the reply whenever the request specifies "Connection:
   * close". The JDK HTTP server closes the connection after servicing such a request but omits the
   * header from the reply; without it, a client may retain the closed connection for a subsequent
   * request (RFC 7230 section 6.6).
   */
  static void closeIfAsked(HttpExchange exchange) {
    if ("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection"))) {
      exchange.getResponseHeaders().set("Connection", "close");
    }
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      closeIfAsked(exchange);
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      boolean control = Protocol.CONTROL_PATH.equals(path);
      if (!control && !Protocol.TELEMETRY_PATH.equals(path)) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      String body = read(exchange);
      if (body == null) {
        exchange.sendResponseHeaders(413, -1);
        return;
      }
      AgentMessage request;
      try {
        request = control ? ChannelRequests.control(body) : ChannelRequests.telemetry(body);
      } catch (ProtocolException e) {
        LOG.warn("rejected a request to {}: {}", path, e.getMessage());
        send(exchange, 400, new Rejected(e.getMessage()), null);
        return;
      }
      MDC.put(Protocol.MDC_KEY, request.getAgent());
      try {
        Message reply = control ? await(request) : channel.telemetry((Intervals) request);
        if (reply == null) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        send(exchange, 200, reply, request.getTrace());
      } finally {
        MDC.remove(Protocol.MDC_KEY);
      }
    } finally {
      exchange.close();
    }
  }

  /**
   * Waits for and returns the controller reply to a control request, or returns null if no reply
   * arrives before the timeout expires.
   */
  private Message await(AgentMessage request) throws IOException {
    CompletableFuture<Message> reply = new CompletableFuture<>();
    channel.control(request, new Callback<Message>() {
      @Override
      public void completed(Message value) {
        reply.complete(value);
      }

      @Override
      public void failed(IOException e) {
        reply.completeExceptionally(e);
      }
    });
    try {
      // The normal poll hold is much shorter; this timeout bound frees the handler thread only
      // when no reply comes.
      return reply.get(Protocol.IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while " + request.getTrace() + " was held", e);
    } catch (ExecutionException e) {
      throw new IOException(e.getCause());
    }
  }

  /**
   * Reads and returns the HTTP request body as text, or returns null when the payload exceeds the
   * maximum permitted size bound.
   */
  static String read(HttpExchange exchange) throws IOException {
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length.trim()) > Protocol.MAX_BODY_BYTES) {
          return null;
        }
      } catch (NumberFormatException e) {
        return null;
      }
    }
    try (InputStream in = exchange.getRequestBody()) {
      return readBounded(in);
    }
  }

  /**
   * Reads the body of an HTTP request or reply stream as UTF-8 text, or returns null if the content
   * size is larger than the configured maximum bound.
   */
  static String readBounded(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) > 0) {
      if (bytes.size() + n > Protocol.MAX_BODY_BYTES) {
        return null;
      }
      bytes.write(buffer, 0, n);
    }
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  private static void send(HttpExchange exchange, int code, Message reply, String trace)
    throws IOException {
    byte[] body = Codec.encode(reply).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", JSON);
    if (trace != null) {
      exchange.getResponseHeaders().set(Protocol.TRACE_HEADER, trace);
    }
    exchange.sendResponseHeaders(code, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
