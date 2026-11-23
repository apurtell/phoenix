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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.RunBundle;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.controller.TestTable;
import org.apache.phoenix.pherf.distributed.controller.TestView;
import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves as the HTTP handler for the REST API mounted on the "/" context of the REST listener. Both
 * request payloads and response bodies are encoded as UTF-8 JSON.
 * <ul>
 * <li>GET /v1/agents returns the agent views sorted in id order.</li>
 * <li>POST /v1/tests accepts a plan in the request body and returns 201 with the test view.</li>
 * <li>POST /v1/tests/{id}/runs initiates a run and returns 201 with the view of the new run.</li>
 * <li>GET /v1/tests/{id}/runs/{runId}/status returns the run view.</li>
 * <li>GET /v1/tests/{id}/runs/{runId}/report streams the raw bytes of the frozen fleet report.</li>
 * <li>POST /v1/tests/{id}/runs/{runId}/cancel cancels the run and returns the run view in the
 * CANCELLED state.</li>
 * <li>POST /v1/agents/{agentId}/reset resets the agent and returns the agent view.</li>
 * <li>POST /v1/tests/{id}/end terminates the test and returns the test view in the ENDED
 * state.</li>
 * <li>GET /v1/tests/{id}/runs/{runId}/bundle and .../bundle/{path} return the file list of the run
 * directory and the contents of a single file within it, respectively.</li>
 * </ul>
 * Error responses carry a {"error": code, "message": text} payload using the status codes
 * bad_request (400), not_found (404), method_not_allowed (405, accompanied by an Allow header),
 * conflict (409), too_large (413), and internal (500). Any identifier that fails to match its
 * expected pattern produces a 404 response so that no filesystem path is ever constructed from
 * untrusted input. Query strings are ignored, and every state-mutating request records a single
 * INFO log entry.
 */
final class RestHandler implements HttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(RestHandler.class);
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  /**
   * Matches a valid path segment for a bundle file, while a separate validation step rejects "."
   * and "..".
   */
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final int COPY_BYTES = 64 * 1024;

  private final Controller controller;
  private final TestTable tests;

  RestHandler(Controller controller, TestTable tests) {
    this.controller = controller;
    this.tests = tests;
  }

  /**
   * Represents a failed request, encapsulating the HTTP status, the stable error code, and the
   * descriptive error message text.
   */
  private static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;
    final String error;
    final String allow;

    Failure(int status, String error, String message, String allow) {
      super(message);
      this.status = status;
      this.error = error;
      this.allow = allow;
    }
  }

  private static Failure badRequest(String message) {
    return new Failure(400, "bad_request", message, null);
  }

  private static Failure notFound(String message) {
    return new Failure(404, "not_found", message, null);
  }

  private static Failure conflict(String message) {
    return new Failure(409, "conflict", message, null);
  }

  /**
   * Encapsulates an outgoing HTTP response containing the status code and either an in-memory JSON
   * body or a file path paired with its content type.
   */
  private static final class Reply {
    final int status;
    final String type;
    final byte[] body;
    final Path file;
    /** Stores the identifier created or modified by the request for inclusion in the audit log. */
    String id;

    Reply(int status, String type, byte[] body, Path file) {
      this.status = status;
      this.type = type;
      this.body = body;
      this.file = file;
    }
  }

  private static Reply json(int status, Object value) {
    return new Reply(status, ChannelHandler.JSON,
      GSON.toJson(value).getBytes(StandardCharsets.UTF_8), null);
  }

  private static Reply json(int status, Object value, String id) {
    Reply reply = json(status, value);
    reply.id = id;
    return reply;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getRawPath();
    Reply reply;
    try {
      reply = route(exchange, method, path);
    } catch (Failure e) {
      if (e.allow != null) {
        exchange.getResponseHeaders().set("Allow", e.allow);
      }
      reply = error(e.status, e.error, e.getMessage());
    } catch (IOException | UncheckedIOException e) {
      LOG.warn("{} {}: {}", method, path, e.toString());
      reply = error(500, "internal", e.getMessage() == null ? e.toString() : e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("{} {} failed", method, path, e);
      reply = error(500, "internal", e.toString());
    }
    try {
      send(exchange, reply);
    } finally {
      exchange.close();
    }
    if (!GET.equals(method)) {
      LOG.info("{} {} {}{}", method, path, reply.status, reply.id == null ? "" : " " + reply.id);
    }
  }

  private static Reply error(int status, String error, String message) {
    JsonObject body = new JsonObject();
    body.addProperty("error", error);
    body.addProperty("message", message);
    return json(status, body);
  }

  private Reply route(HttpExchange exchange, String method, String path)
    throws Failure, IOException {
    String[] parts = path.split("/", -1);
    if (parts.length < 3 || !parts[0].isEmpty() || !"v1".equals(parts[1])) {
      throw notFound("no resource " + path);
    }
    List<String> segments = Arrays.asList(parts).subList(2, parts.length);
    int size = segments.size();
    String resource = segments.get(0);
    if ("agents".equals(resource)) {
      if (size == 1) {
        allow(method, GET);
        JsonObject body = new JsonObject();
        body.add("agents", GSON.toJsonTree(controller.agents()));
        return json(200, body);
      }
      if (size == 3 && "reset".equals(segments.get(2))) {
        allow(method, POST);
        emptyBody(exchange);
        return reset(segments.get(1));
      }
    } else if ("tests".equals(resource)) {
      if (size == 1) {
        allow(method, POST);
        return create(exchange);
      }
      if (size == 3 && "runs".equals(segments.get(2))) {
        allow(method, POST);
        emptyBody(exchange);
        return submit(segments.get(1));
      }
      if (size == 3 && "end".equals(segments.get(2))) {
        allow(method, POST);
        emptyBody(exchange);
        return end(segments.get(1));
      }
      if (size >= 5 && "runs".equals(segments.get(2))) {
        String testId = segments.get(1);
        String runId = segments.get(3);
        String action = segments.get(4);
        if (size == 5 && "status".equals(action)) {
          allow(method, GET);
          return json(200, run(testId, runId));
        }
        if (size == 5 && "report".equals(action)) {
          allow(method, GET);
          File dir = committed(run(testId, runId), "report");
          Path report = new File(dir, RunBundle.REPORT).toPath();
          if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("run " + runId + " has no report");
          }
          return file(report);
        }
        if (size == 5 && "cancel".equals(action)) {
          allow(method, POST);
          emptyBody(exchange);
          return cancel(testId, runId);
        }
        if ("bundle".equals(action)) {
          allow(method, GET);
          File dir = committed(run(testId, runId), "bundle");
          return size == 5
            ? listing(dir.toPath())
            : bundleFile(runId, dir.toPath(), segments.subList(5, size));
        }
      }
    }
    throw notFound("no resource " + path);
  }

  private static void allow(String method, String allowed) throws Failure {
    if (!allowed.equals(method)) {
      throw new Failure(405, "method_not_allowed", method + " is not allowed; use " + allowed,
        allowed);
    }
  }

  /**
   * Rejects any request payload other than an empty body or an empty JSON object ({}) on endpoints
   * that accept no input parameters.
   */
  private static void emptyBody(HttpExchange exchange) throws Failure, IOException {
    String body = body(exchange).trim();
    if (body.isEmpty()) {
      return;
    }
    try {
      JsonElement json = JsonParser.parseString(body);
      if (json.isJsonObject() && json.getAsJsonObject().size() == 0) {
        return;
      }
    } catch (JsonParseException e) {
      // Fall through so the badRequest exception below reports the invalid payload error.
    }
    throw badRequest("the body of this request must be empty or {}");
  }

  private static String body(HttpExchange exchange) throws Failure, IOException {
    String body = ChannelHandler.read(exchange);
    if (body == null) {
      throw new Failure(413, "too_large",
        "the body is larger than the limit of " + Protocol.MAX_BODY_BYTES + " bytes", null);
    }
    return body;
  }

  private Reply create(HttpExchange exchange) throws Failure, IOException {
    String plan = body(exchange);
    TestView test;
    try {
      test = tests.create(plan);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    return json(201, test, test.getId());
  }

  private TestView test(String testId) throws Failure {
    TestView test = TestTable.ID.matcher(testId).matches() ? tests.test(testId) : null;
    if (test == null) {
      throw notFound("no test " + testId);
    }
    return test;
  }

  /**
   * Resolves the run view belonging to a specific test, treating runs associated with a different
   * test or lacking a view as not found.
   */
  private RunView run(String testId, String runId) throws Failure {
    TestView test = test(testId);
    RunView run = Controller.RUN_ID.matcher(runId).matches() && test.getRuns().contains(runId)
      ? controller.run(runId)
      : null;
    if (run == null) {
      throw notFound("no run " + runId + " in test " + testId);
    }
    return run;
  }

  private Reply submit(String testId) throws Failure, IOException {
    // The test table never removes entries, ensuring that a validated test remains present.
    test(testId);
    RunView run;
    try {
      run = tests.submit(testId);
    } catch (IllegalStateException e) {
      throw conflict(e.getMessage());
    }
    return json(201, run, run.getRunId());
  }

  private Reply cancel(String testId, String runId) throws Failure {
    run(testId, runId);
    RunView run;
    try {
      run = controller.cancel(runId, "cancelled");
    } catch (IllegalStateException e) {
      throw conflict(e.getMessage());
    }
    return json(200, run, runId);
  }

  private Reply end(String testId) throws Failure, IOException {
    test(testId);
    return json(200, tests.end(testId), testId);
  }

  private Reply reset(String agentId) throws Failure {
    AgentView agent;
    try {
      agent =
        OperationalIdentity.ID.matcher(agentId).matches() ? controller.resetAgent(agentId) : null;
    } catch (IllegalStateException e) {
      throw conflict(e.getMessage());
    }
    if (agent == null) {
      throw notFound("no agent " + agentId);
    }
    return json(200, agent, agentId);
  }

  /**
   * Resolves the bundle directory for a run whose completion has been committed, as indicated by
   * the presence of its manifest file. A run still in the RUNNING state raises a conflict, whereas
   * an ended run without a manifest (because it was interrupted or its bundle write failed) has no
   * bundle and is reported as not found.
   */
  private File committed(RunView run, String what) throws Failure {
    if (run.getState() == RunView.State.RUNNING) {
      throw conflict(
        "run " + run.getRunId() + " is RUNNING; its " + what + " exists when the run ends");
    }
    File dir = new File(controller.runDirectory(), run.getRunId());
    if (!new File(dir, RunBundle.MANIFEST).isFile()) {
      throw notFound("run " + run.getRunId() + " has no committed bundle");
    }
    return dir;
  }

  /**
   * Builds a response listing every regular file under the run directory sorted in lexicographical
   * path order, excluding any temporary .tmp files.
   */
  private static Reply listing(Path root) throws IOException {
    List<String> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).forEach(file -> {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(file)) {
          names.add(name.toString());
        }
        String path = String.join("/", names);
        if (!path.endsWith(RunBundle.TMP)) {
          paths.add(path);
        }
      });
    }
    Collections.sort(paths);
    JsonArray files = new JsonArray();
    for (String path : paths) {
      JsonObject file = new JsonObject();
      file.addProperty("path", path);
      file.addProperty("bytes", Files.size(root.resolve(path)));
      files.add(file);
    }
    JsonObject body = new JsonObject();
    body.add("files", files);
    return json(200, body);
  }

  /**
   * Resolves and serves a single file from the run directory. Every path segment must match the
   * allowed segment pattern and must not be "." or "..", and the resolved target must be a regular
   * file rather than a symbolic link, with a canonical real path contained within the run
   * directory.
   */
  private static Reply bundleFile(String runId, Path root, List<String> segments) throws Failure {
    String path = String.join("/", segments);
    Failure missing = notFound("no file " + path + " in the bundle of run " + runId);
    for (String segment : segments) {
      if (!SEGMENT.matcher(segment).matches() || ".".equals(segment) || "..".equals(segment)) {
        throw missing;
      }
    }
    Path target = root.resolve(path);
    try {
      if (
        path.endsWith(RunBundle.TMP) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || !target.toRealPath().startsWith(root.toRealPath())
      ) {
        throw missing;
      }
    } catch (IOException e) {
      throw missing;
    }
    return file(target);
  }

  private static Reply file(Path file) {
    String name = file.getFileName().toString();
    String type = name.endsWith(".json") ? ChannelHandler.JSON
      : name.endsWith(".tsv") ? "text/tab-separated-values"
      : name.endsWith(".hlog") ? "text/plain"
      : "application/octet-stream";
    return new Reply(200, type, null, file);
  }

  private static void send(HttpExchange exchange, Reply reply) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", reply.type);
    ChannelHandler.closeIfAsked(exchange);
    if (reply.file == null) {
      exchange.sendResponseHeaders(reply.status, reply.body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(reply.body);
      }
      return;
    }
    // Retrieve both the file size and content stream from a single open descriptor so they remain
    // consistent even if an atomic move replaces the file concurrently.
    try (FileChannel channel = FileChannel.open(reply.file, StandardOpenOption.READ);
      InputStream in = Channels.newInputStream(channel)) {
      long size = channel.size();
      // On the JDK HTTP server, a response length of 0 selects chunked transfer encoding, whereas
      // -1 indicates an empty response body.
      exchange.sendResponseHeaders(reply.status, size == 0 ? -1 : size);
      try (OutputStream out = exchange.getResponseBody()) {
        byte[] buffer = new byte[COPY_BYTES];
        int n;
        while ((n = in.read(buffer)) >= 0) {
          out.write(buffer, 0, n);
        }
      }
    }
  }
}
