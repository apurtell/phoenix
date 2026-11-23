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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.phoenix.pherf.distributed.protocol.Ack;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Intervals;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.protocol.ProtocolException;
import org.apache.phoenix.pherf.distributed.protocol.Rejected;
import org.apache.phoenix.pherf.distributed.transport.AgentTransport;
import org.apache.phoenix.pherf.distributed.transport.Callback;
import org.apache.phoenix.pherf.distributed.transport.RejectedException;
import org.slf4j.MDC;

/**
 * Implements the agent transport layer over HTTP/1.1. Each stream uses a single thread that sends
 * one POST request at a time with a fixed-length body over a kept-alive connection. Because every
 * request can be re-sent with no additional effect, retries performed by the JDK client are safe.
 * An HTTP 400, an HTTP 404, or a body that fails to decode indicates a bad request or a controller
 * from another build; the call fails with a RejectedException and is not retried.
 */
public final class HttpAgentTransport implements AgentTransport {
  private final URL control;
  private final URL telemetry;
  private final ExecutorService controlStream;
  private final ExecutorService telemetryStream;

  public HttpAgentTransport(URI controller, String agentId) throws MalformedURLException {
    this.control = controller.resolve(Protocol.CONTROL_PATH).toURL();
    this.telemetry = controller.resolve(Protocol.TELEMETRY_PATH).toURL();
    this.controlStream = stream("pherf-agent-control", agentId);
    this.telemetryStream = stream("pherf-agent-telemetry", agentId);
  }

  private static ExecutorService stream(String name, String agentId) {
    return Executors.newSingleThreadExecutor(action -> {
      Thread thread = new Thread(() -> {
        MDC.put(Protocol.MDC_KEY, agentId);
        action.run();
      }, name);
      thread.setDaemon(true);
      return thread;
    });
  }

  @Override
  public void control(Message request, Callback<Message> callback) {
    controlStream.execute(() -> {
      Message reply;
      try {
        reply = post(control, request);
      } catch (IOException e) {
        callback.failed(e);
        return;
      }
      callback.completed(reply);
    });
  }

  @Override
  public void telemetry(Intervals batch, Callback<Ack> callback) {
    telemetryStream.execute(() -> {
      Message reply;
      try {
        reply = post(telemetry, batch);
      } catch (IOException e) {
        callback.failed(e);
        return;
      }
      if (reply instanceof Ack) {
        callback.completed((Ack) reply);
      } else if (reply instanceof Rejected) {
        callback.failed(new RejectedException(((Rejected) reply).getReason()));
      } else {
        callback.failed(new RejectedException("telemetry reply is " + reply.type().wire()));
      }
    });
  }

  @Override
  public void close() {
    controlStream.shutdownNow();
    telemetryStream.shutdownNow();
  }

  private static Message post(URL url, Message request) throws IOException {
    byte[] body = Codec.encode(request).getBytes(StandardCharsets.UTF_8);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(Protocol.IO_TIMEOUT_MS);
    connection.setReadTimeout(Protocol.IO_TIMEOUT_MS);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(body.length);
    connection.setRequestProperty("Content-Type", ChannelHandler.JSON);
    connection.setRequestProperty(Protocol.TRACE_HEADER, request.getTrace());
    try (OutputStream out = connection.getOutputStream()) {
      out.write(body);
    }
    int code = connection.getResponseCode();
    if (code == 200) {
      return decode(read(connection.getInputStream()));
    }
    InputStream error = connection.getErrorStream();
    String text = error == null ? "" : read(error);
    if (code == 400) {
      throw new RejectedException(reason(text));
    }
    if (code == 404) {
      throw new RejectedException("HTTP 404 for " + url.getPath()
        + "; the controller and the agents must come from one build");
    }
    throw new IOException("HTTP " + code + " for " + request.getTrace());
  }

  /** Reads the entire response body so that the underlying connection can be kept alive. */
  private static String read(InputStream in) throws IOException {
    try (InputStream body = in) {
      String text = ChannelHandler.readBounded(body);
      if (text == null) {
        throw new IOException("reply is larger than " + Protocol.MAX_BODY_BYTES + " bytes");
      }
      return text;
    }
  }

  /**
   * Decodes a reply message. A reply that fails to decode indicates another build, so the agent
   * does not retry the request.
   */
  private static Message decode(String text) throws RejectedException {
    try {
      return Codec.decode(text);
    } catch (ProtocolException e) {
      throw new RejectedException("bad reply: " + e.getMessage());
    }
  }

  /**
   * Extracts the failure reason from a 400 reply, returning the rejected message reason when
   * available or falling back to the raw response body.
   */
  private static String reason(String text) {
    try {
      Message reply = Codec.decode(text);
      if (reply instanceof Rejected) {
        return ((Rejected) reply).getReason();
      }
    } catch (ProtocolException e) {
      // Another build may respond in a different format, so use the raw body as the reason.
    }
    return "HTTP 400: " + text;
  }
}
