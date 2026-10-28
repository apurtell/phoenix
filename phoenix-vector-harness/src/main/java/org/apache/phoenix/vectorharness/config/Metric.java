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

import java.util.Locale;

/**
 * Vector distance metrics supported by the harness and Phoenix vector indexes: L2 (Euclidean
 * distance), COSINE (1 - cosine similarity), and INNER_PRODUCT (negated dot product).
 */
public enum Metric {
  L2("L2_DISTANCE", "<->"),
  COSINE("COSINE_DISTANCE", "<=>"),
  INNER_PRODUCT("INNER_PRODUCT", "<#>");

  private final String sqlFunction;
  private final String sqlOperator;

  Metric(String sqlFunction, String sqlOperator) {
    this.sqlFunction = sqlFunction;
    this.sqlOperator = sqlOperator;
  }

  /** Returns the corresponding Phoenix SQL distance function name (e.g. L2_DISTANCE). */
  public String sqlFunction() {
    return sqlFunction;
  }

  /** Returns the SQL distance operator (e.g. {@code <->}). */
  public String sqlOperator() {
    return sqlOperator;
  }

  /** Returns the index DDL metric option value. */
  public String indexOption() {
    return name();
  }

  /** Returns true if vectors should be normalized by default for this metric. */
  public boolean normalizesByDefault() {
    return this == COSINE;
  }

  /**
   * Computes the distance between two vectors in double precision for client side ground truth.
   */
  public double distance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("Dimension mismatch: " + a.length + " and " + b.length);
    }
    switch (this) {
      case L2: {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
          double d = (double) a[i] - (double) b[i];
          sum += d * d;
        }
        return Math.sqrt(sum);
      }
      case COSINE: {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
          double x = a[i];
          double y = b[i];
          dot += x * y;
          na += x * x;
          nb += y * y;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        if (denom == 0.0) {
          return 1.0;
        }
        return 1.0 - Math.max(-1.0, Math.min(1.0, dot / denom));
      }
      case INNER_PRODUCT: {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
          dot += (double) a[i] * (double) b[i];
        }
        return -dot;
      }
      default:
        throw new IllegalStateException("Unknown metric " + this);
    }
  }

  /** Parses a metric name or alias case insensitively. */
  public static Metric parse(String text) {
    String t = text.trim().toUpperCase(Locale.ROOT);
    switch (t) {
      case "L2":
      case "EUCLIDEAN":
        return L2;
      case "COSINE":
      case "ANGULAR":
        return COSINE;
      case "INNER_PRODUCT":
      case "IP":
      case "DOT":
        return INNER_PRODUCT;
      default:
        throw new IllegalArgumentException("Unknown metric: " + text);
    }
  }
}
