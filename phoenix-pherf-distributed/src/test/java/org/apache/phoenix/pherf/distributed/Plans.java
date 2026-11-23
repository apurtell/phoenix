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
package org.apache.phoenix.pherf.distributed;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.RunSpec;

/**
 * Constructs JSON execution plans programmatically for the test suite. Tests assemble these plans
 * in Java rather than storing JSON files in the source tree because a JSON file cannot carry the
 * license header required by RAT.
 */
public final class Plans {
  private final JsonObject plan = new JsonObject();
  private final JsonArray steps = new JsonArray();

  private Plans(long seed) {
    plan.addProperty("seed", seed);
    plan.addProperty("runClass", "validation");
  }

  public static Plans plan(long seed) {
    return new Plans(seed);
  }

  /**
   * Configures the execution run class on the plan, such as validation (the default for tests) or
   * measurement.
   */
  public Plans runClass(String runClass) {
    plan.addProperty("runClass", runClass);
    return this;
  }

  /** Registers a run-level configuration property on the plan. */
  public Plans property(String key, String value) {
    object(plan, "properties").addProperty(key, value);
    return this;
  }

  public Plans step(Step step) {
    steps.add(step.step);
    return this;
  }

  /**
   * Assigns an arbitrary JSON value to a top-level plan field to support plan validation refusal
   * tests.
   */
  public Plans set(String key, JsonElement value) {
    plan.add(key, value);
    return this;
  }

  /** Removes a top-level field from the plan to support plan validation refusal tests. */
  public Plans remove(String key) {
    plan.remove(key);
    return this;
  }

  public String json() {
    JsonObject copy = plan.deepCopy();
    if (!copy.has("steps")) {
      copy.add("steps", steps.deepCopy());
    }
    return new GsonBuilder().disableHtmlEscaping().create().toJson(copy);
  }

  public RunSpec spec() {
    return RunSpec.parse(json());
  }

  public static Step step(int agents) {
    return new Step(agents);
  }

  private static JsonObject object(JsonObject parent, String key) {
    if (!parent.has(key)) {
      parent.add(key, new JsonObject());
    }
    return parent.getAsJsonObject(key);
  }

  /** Reads and returns the text content of a file from the phoenix-pherf reference scenarios. */
  public static String reference(String name) throws IOException {
    return new String(Files.readAllBytes(new File(referenceDir(), name).toPath()),
      StandardCharsets.UTF_8);
  }

  /** Loads and returns the text content of a test resource packaged within this module. */
  public static String resource(String path) throws IOException {
    try (InputStream in = Plans.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("no resource " + path);
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        bytes.write(buffer, 0, n);
      }
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /** Resolves the source directory containing the phoenix-pherf reference scenarios. */
  public static File referenceDir() {
    try {
      File classes =
        new File(Controller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      File root = classes.getParentFile().getParentFile().getParentFile();
      return new File(root, "phoenix-pherf/config/reference");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Represents the configuration builder for a single execution step within a plan. */
  public static final class Step {
    private final JsonObject step = new JsonObject();

    private Step(int agents) {
      step.addProperty("agents", agents);
    }

    public Step weights(long... weights) {
      JsonArray array = new JsonArray();
      for (long weight : weights) {
        array.add(weight);
      }
      step.add("weights", array);
      return this;
    }

    /** Sets the scenario name on the step and registers the scenario text as a document. */
    public Step scenario(String name, String text) {
      step.addProperty("scenario", name);
      return document(name, text);
    }

    public Step document(String name, String text) {
      object(step, "documents").addProperty(name, text);
      return this;
    }

    public Step args(String... args) {
      JsonArray array = new JsonArray();
      for (String arg : args) {
        array.add(arg);
      }
      step.add("args", array);
      return this;
    }

    public Step rate(long rate) {
      step.addProperty("rate", rate);
      return this;
    }

    public Step durationMs(long durationMs) {
      step.addProperty("durationMs", durationMs);
      return this;
    }

    public Step threads(long threads) {
      step.addProperty("threads", threads);
      return this;
    }

    public Step rows(long rows) {
      step.addProperty("rows", rows);
      return this;
    }

    public Step property(String key, String value) {
      object(step, "properties").addProperty(key, value);
      return this;
    }

    public Step properties(Map<String, String> properties) {
      for (Map.Entry<String, String> entry : properties.entrySet()) {
        property(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /**
     * Assigns an arbitrary JSON value to a step field to support plan validation refusal tests.
     */
    public Step set(String key, JsonElement value) {
      step.add(key, value);
      return this;
    }
  }
}
