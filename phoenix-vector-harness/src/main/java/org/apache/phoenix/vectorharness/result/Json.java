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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON reading and writing of manifests and state files, as ordered maps. */
public final class Json {

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  private Json() {
  }

  /** Writes a value as JSON, atomically. */
  public static void write(Path file, Object value) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    MAPPER.writeValue(tmp.toFile(), sanitize(value));
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Reads a JSON object as an ordered map. */
  public static Map<String, Object> readMap(Path file) throws IOException {
    return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
    });
  }

  public static String toString(Object value) {
    try {
      return MAPPER.writeValueAsString(sanitize(value));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Replaces NaN and infinite doubles, which JSON cannot represent, by null. */
  @SuppressWarnings("unchecked")
  static Object sanitize(Object v) {
    if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      return Double.isNaN(d) || Double.isInfinite(d) ? null : v;
    }
    if (v instanceof Map) {
      Map<Object, Object> out = new LinkedHashMap<>();
      for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
        out.put(e.getKey(), sanitize(e.getValue()));
      }
      return out;
    }
    if (v instanceof Iterable && !(v instanceof Path)) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object o : (Iterable<Object>) v) {
        out.add(sanitize(o));
      }
      return out;
    }
    if (v instanceof double[]) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (double d : (double[]) v) {
        out.add(sanitize(d));
      }
      return out;
    }
    if (v instanceof Path) {
      return v.toString();
    }
    return v;
  }

  /** Returns a nested value of a map by a dotted path, or null. */
  @SuppressWarnings("unchecked")
  public static Object path(Map<String, Object> map, String dotted) {
    Object cur = map;
    for (String part : dotted.split("\\.")) {
      if (!(cur instanceof Map)) {
        return null;
      }
      cur = ((Map<String, Object>) cur).get(part);
    }
    return cur;
  }
}
