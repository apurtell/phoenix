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
 * The distance metrics of the harness. Each metric names the SQL distance function that ranks by it
 * and the index metric option that builds an index for it. Distances are ascending, nearest first,
 * with the definitions that Phoenix computes: Euclidean distance for L2, one minus the cosine
 * similarity for COSINE (1.0 when an operand has zero magnitude), and the negated dot product for
 * INNER_PRODUCT.
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

  /** Returns the name of the SQL distance function, such as L2_DISTANCE. */
  public String sqlFunction() {
    return sqlFunction;
  }

  /** Returns the SQL distance operator, such as {@code <->}. */
  public String sqlOperator() {
    return sqlOperator;
  }

  /** Returns the value of the metric option of CREATE VECTOR INDEX. */
  public String indexOption() {
    return name();
  }

  /** Returns true if vectors should lie on the unit sphere by default for this metric. */
  public boolean normalizesByDefault() {
    return this == COSINE;
  }

  /**
   * Computes the distance between two vectors in double precision. The harness computes ground
   * truth with this method, independent of the engine under test.
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

  /** Parses a metric name, ignoring case. */
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
