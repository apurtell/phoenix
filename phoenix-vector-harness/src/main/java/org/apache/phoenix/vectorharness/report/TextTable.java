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
package org.apache.phoenix.vectorharness.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A table of aligned text columns for console reports. Numbers are right aligned. */
final class TextTable {

  private final List<String> header;
  private final List<String[]> rows = new ArrayList<>();
  private final List<boolean[]> numeric = new ArrayList<>();

  TextTable(String... header) {
    this.header = Arrays.asList(header);
  }

  /** Formats a number for display: NaN and null as a dash, others to a sensible precision. */
  static String num(Object v) {
    if (v == null) {
      return "-";
    }
    if (!(v instanceof Number)) {
      return v.toString();
    }
    double d = ((Number) v).doubleValue();
    if (Double.isNaN(d)) {
      return "-";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "inf" : "-inf";
    }
    if (v instanceof Long || v instanceof Integer) {
      return Long.toString(((Number) v).longValue());
    }
    double a = Math.abs(d);
    if (a >= 1000) {
      return String.format(Locale.ROOT, "%.0f", d);
    }
    if (a >= 100) {
      return String.format(Locale.ROOT, "%.1f", d);
    }
    return String.format(Locale.ROOT, "%.3f", d);
  }

  /** Formats a byte count with a binary unit. */
  static String bytes(double b) {
    if (Double.isNaN(b)) {
      return "-";
    }
    String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };
    int u = 0;
    double v = b;
    while (Math.abs(v) >= 1024 && u < units.length - 1) {
      v /= 1024;
      u++;
    }
    return u == 0
      ? String.format(Locale.ROOT, "%.0f %s", v, units[u])
      : String.format(Locale.ROOT, "%.1f %s", v, units[u]);
  }

  void row(Object... values) {
    String[] r = new String[header.size()];
    boolean[] n = new boolean[header.size()];
    for (int i = 0; i < r.length; i++) {
      Object v = i < values.length ? values[i] : null;
      r[i] = num(v);
      n[i] = v instanceof Number;
    }
    rows.add(r);
    numeric.add(n);
  }

  boolean isEmpty() {
    return rows.isEmpty();
  }

  /** Renders the table, each line prefixed with an indent. */
  String render(String indent) {
    int[] width = new int[header.size()];
    for (int i = 0; i < width.length; i++) {
      width[i] = header.get(i).length();
    }
    for (String[] r : rows) {
      for (int i = 0; i < width.length; i++) {
        width[i] = Math.max(width[i], r[i].length());
      }
    }
    StringBuilder sb = new StringBuilder();
    line(sb, indent, header.toArray(new String[0]), new boolean[width.length], width);
    for (int i = 0; i < rows.size(); i++) {
      line(sb, indent, rows.get(i), numeric.get(i), width);
    }
    return sb.toString();
  }

  private static void line(StringBuilder sb, String indent, String[] cells, boolean[] right,
    int[] width) {
    StringBuilder l = new StringBuilder(indent);
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        l.append("  ");
      }
      String c = cells[i];
      int pad = width[i] - c.length();
      if (right[i]) {
        for (int p = 0; p < pad; p++) {
          l.append(' ');
        }
        l.append(c);
      } else {
        l.append(c);
        if (i < cells.length - 1) {
          for (int p = 0; p < pad; p++) {
            l.append(' ');
          }
        }
      }
    }
    sb.append(l.toString().replaceAll("\\s+$", "")).append('\n');
  }
}
