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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides an in-memory HTTP exchange implementation for testing request handlers without starting
 * an HTTP server or establishing a network connection.
 */
final class FakeExchange extends HttpExchange {
  private final String method;
  private final URI uri;
  private final Headers requestHeaders = new Headers();
  private final Headers responseHeaders = new Headers();
  private final Map<String, Object> attributes = new HashMap<>();
  private InputStream requestBody;
  private OutputStream responseBody = new ByteArrayOutputStream();
  private final ByteArrayOutputStream response = (ByteArrayOutputStream) responseBody;
  private int responseCode = -1;
  private long responseLength;
  private boolean closed;

  FakeExchange(String method, String path, byte[] body) {
    this.method = method;
    this.uri = URI.create(path);
    this.requestBody = new ByteArrayInputStream(body);
    requestHeaders.add("Content-Length", Integer.toString(body.length));
  }

  FakeExchange(String method, String path, String body) {
    this(method, path, body.getBytes(StandardCharsets.UTF_8));
  }

  String responseText() {
    return new String(response.toByteArray(), StandardCharsets.UTF_8);
  }

  long responseLength() {
    return responseLength;
  }

  boolean closed() {
    return closed;
  }

  @Override
  public Headers getRequestHeaders() {
    return requestHeaders;
  }

  @Override
  public Headers getResponseHeaders() {
    return responseHeaders;
  }

  @Override
  public URI getRequestURI() {
    return uri;
  }

  @Override
  public String getRequestMethod() {
    return method;
  }

  @Override
  public HttpContext getHttpContext() {
    return null;
  }

  @Override
  public void close() {
    closed = true;
  }

  @Override
  public InputStream getRequestBody() {
    return requestBody;
  }

  @Override
  public OutputStream getResponseBody() {
    return responseBody;
  }

  @Override
  public void sendResponseHeaders(int code, long length) {
    if (responseCode != -1) {
      throw new IllegalStateException("headers already sent");
    }
    responseCode = code;
    responseLength = length;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return null;
  }

  @Override
  public int getResponseCode() {
    return responseCode;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return null;
  }

  @Override
  public String getProtocol() {
    return "HTTP/1.1";
  }

  @Override
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  @Override
  public void setAttribute(String name, Object value) {
    attributes.put(name, value);
  }

  @Override
  public void setStreams(InputStream in, OutputStream out) {
    if (in != null) {
      requestBody = in;
    }
    if (out != null) {
      responseBody = out;
    }
  }

  @Override
  public HttpPrincipal getPrincipal() {
    return null;
  }
}
