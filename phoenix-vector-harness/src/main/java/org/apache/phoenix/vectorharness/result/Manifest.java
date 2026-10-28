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
package org.apache.phoenix.vectorharness.result;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured record of phase or run metadata, organized in named sections and serialized as JSON.
 */
public final class Manifest {

  private final Map<String, Object> root;

  public Manifest() {
    this.root = new LinkedHashMap<>();
  }

  private Manifest(Map<String, Object> root) {
    this.root = root;
  }

  public static Manifest read(Path file) throws IOException {
    return new Manifest(Json.readMap(file));
  }

  public void write(Path file) throws IOException {
    Json.write(file, root);
  }

  /** Returns the section map for the given name, creating it if absent. */
  @SuppressWarnings("unchecked")
  public synchronized Map<String, Object> section(String name) {
    Object s = root.get(name);
    if (!(s instanceof Map)) {
      s = new LinkedHashMap<String, Object>();
      root.put(name, s);
    }
    return (Map<String, Object>) s;
  }

  public synchronized Manifest put(String section, String key, Object value) {
    section(section).put(key, value);
    return this;
  }

  /** Replaces the contents of a section with the provided map. */
  public synchronized Manifest putSection(String section, Map<String, ?> values) {
    root.put(section, new LinkedHashMap<String, Object>(values));
    return this;
  }

  /** Appends a value to the specified list within a section. */
  @SuppressWarnings("unchecked")
  public synchronized Manifest append(String section, String key, Object value) {
    Map<String, Object> s = section(section);
    Object l = s.get(key);
    if (!(l instanceof List)) {
      l = new ArrayList<Object>();
      s.put(key, l);
    }
    ((List<Object>) l).add(value);
    return this;
  }

  /** Records an execution warning or invalidation flag. */
  public Manifest flag(String flag) {
    return append("flags", "flags", flag);
  }

  public synchronized List<String> flags() {
    return strings("flags.flags");
  }

  /**
   * Records a defect that makes the results of the run unreliable. The run still writes all its
   * outputs. The run command then exits with a failure code.
   */
  public synchronized Manifest degrade(String reason) {
    flag(reason);
    return append("run", "degraded", reason);
  }

  /** Returns the defects that make the results of the run unreliable. */
  public synchronized List<String> degraded() {
    return strings("run.degraded");
  }

  @SuppressWarnings("unchecked")
  private List<String> strings(String dotted) {
    Object l = Json.path(root, dotted);
    List<String> out = new ArrayList<>();
    if (l instanceof List) {
      for (Object o : (List<Object>) l) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }

  public synchronized Object get(String dotted) {
    return Json.path(root, dotted);
  }

  public synchronized String getString(String dotted) {
    Object v = get(dotted);
    return v == null ? null : v.toString();
  }

  public synchronized Map<String, Object> asMap() {
    return root;
  }
}
