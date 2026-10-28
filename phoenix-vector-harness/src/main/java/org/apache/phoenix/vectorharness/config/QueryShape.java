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
 * The shapes of the nearest neighbor queries that query workers and the frontier sweep run. Every
 * shape orders by the distance to a constant query vector and has a LIMIT.
 */
public enum QueryShape {
  /** No predicate. */
  UNFILTERED(false),
  /** A predicate on CATEGORY, which IVF indexes cover. */
  COVERED_FILTER(true),
  /** A predicate on SOURCE, which no vector index covers. */
  UNCOVERED_FILTER(true),
  /** A TENANT_ID equality predicate, or a tenant connection on a multi-tenant table. */
  TENANT_SCOPED(false),
  /** No tenant predicate on a multi-tenant table, through a global connection. */
  GLOBAL(false),
  /** A DOC_ID range predicate, which bounds the scan by primary key. */
  PK_RANGE(false),
  /** A distance range predicate in the WHERE clause. */
  DISTANCE_BOUND(false);

  private final boolean selective;

  QueryShape(boolean selective) {
    this.selective = selective;
  }

  /** Returns true if the shape sweeps the configured filter selectivities. */
  public boolean sweepsSelectivity() {
    return selective;
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static QueryShape parse(String text) {
    try {
      return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown query shape: " + text);
    }
  }

  /** Parses a comma separated list of shapes, or all. */
  public static Set<QueryShape> parseList(String text) {
    String t = text.trim();
    if (t.isEmpty() || t.equalsIgnoreCase("all")) {
      return EnumSet.allOf(QueryShape.class);
    }
    EnumSet<QueryShape> shapes = EnumSet.noneOf(QueryShape.class);
    for (String part : t.split(",")) {
      if (!part.trim().isEmpty()) {
        shapes.add(parse(part));
      }
    }
    return shapes;
  }
}
