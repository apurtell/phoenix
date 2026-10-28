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
package org.apache.phoenix.vectorharness.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Schema layouts supported by the harness: {@code CORPUS} (relational layout with native VECTOR
 * column) and {@code PROFILE} (semi-structured layout storing BSON metadata and embedded vector).
 */
public enum TableLayout {
  CORPUS("DOC_CORPUS"),
  PROFILE("DOC_PROFILE");

  private final String baseName;

  TableLayout(String baseName) {
    this.baseName = baseName;
  }

  /** Returns the base table name without suffix (e.g. {@code DOC_CORPUS}). */
  public String baseName() {
    return baseName;
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Set<TableLayout> parseList(String text) {
    EnumSet<TableLayout> layouts = EnumSet.noneOf(TableLayout.class);
    for (String part : text.split(",")) {
      String p = part.trim().toUpperCase(Locale.ROOT);
      if (p.isEmpty()) {
        continue;
      }
      if (p.equals("DOC_CORPUS")) {
        p = "CORPUS";
      } else if (p.equals("DOC_PROFILE")) {
        p = "PROFILE";
      }
      try {
        layouts.add(valueOf(p));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Unknown table layout: " + part);
      }
    }
    return layouts;
  }
}
