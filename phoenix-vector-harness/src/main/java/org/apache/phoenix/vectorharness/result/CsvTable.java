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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An append-only CSV file with a fixed header, safe for concurrent writers. Values are written with
 * RFC 4180 quoting; doubles use a locale independent format, and NaN and missing values are written
 * as empty fields.
 */
public final class CsvTable implements Closeable {

  private final Path path;
  private final List<String> columns;
  private final BufferedWriter out;

  private CsvTable(Path path, List<String> columns, BufferedWriter out) {
    this.path = path;
    this.columns = columns;
    this.out = out;
  }

  /** Creates or truncates a CSV file and writes its header. */
  public static CsvTable create(Path path, String... columns) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    CsvTable t = new CsvTable(path, Arrays.asList(columns), w);
    t.writeLine(Arrays.asList((Object[]) columns));
    return t;
  }

  public Path path() {
    return path;
  }

  public List<String> columns() {
    return columns;
  }

  /** Appends a row; the values must match the header in number. */
  public void row(Object... values) {
    if (values.length != columns.size()) {
      throw new IllegalArgumentException(
        "Row of " + values.length + " values for " + columns.size() + " columns of " + path);
    }
    writeLine(Arrays.asList(values));
  }

  private synchronized void writeLine(List<Object> values) {
    try {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(format(values.get(i)));
      }
      out.write(sb.toString());
      out.write('\n');
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public synchronized void flush() {
    try {
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      out.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Formats one value as a CSV field. */
  public static String format(Object v) {
    if (v == null) {
      return "";
    }
    if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return Double.isNaN(d) ? "" : (d > 0 ? "inf" : "-inf");
      }
      if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        return Long.toString((long) d);
      }
      return String.format(Locale.ROOT, "%.6g", d);
    }
    String s = v.toString();
    if (
      s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
    ) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  /** Reads a CSV file written by this class into rows keyed by column name. */
  public static List<Map<String, String>> read(Path path) throws IOException {
    List<Map<String, String>> rows = new ArrayList<>();
    forEach(path, rows::add);
    return rows;
  }

  /**
   * Streams the rows of a CSV file written by this class, keyed by column name, without holding the
   * file in memory.
   */
  public static void forEach(Path path, Consumer<Map<String, String>> visitor) throws IOException {
    try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      List<String> header = null;
      StringBuilder pending = null;
      String line;
      while ((line = r.readLine()) != null) {
        if (pending != null) {
          pending.append('\n').append(line);
          line = pending.toString();
        }
        if (countQuotes(line) % 2 != 0) {
          pending = new StringBuilder(line);
          continue;
        }
        pending = null;
        List<String> fields = parse(line);
        if (header == null) {
          header = fields;
          continue;
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
          m.put(header.get(i), i < fields.size() ? fields.get(i) : "");
        }
        visitor.accept(m);
      }
    }
  }

  private static int countQuotes(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '"') {
        n++;
      }
    }
    return n;
  }

  static List<String> parse(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }
}
