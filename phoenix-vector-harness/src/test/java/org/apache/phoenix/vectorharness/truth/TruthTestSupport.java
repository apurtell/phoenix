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
package org.apache.phoenix.vectorharness.truth;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.CorpusFactory;

/**
 * Shared fixtures and test utilities for truth package unit tests, providing reference nearest
 * neighbor calculation and synthetic test corpora.
 */
final class TruthTestSupport {

  private TruthTestSupport() {
  }

  /** Constructs a HarnessConfig from alternating key-value pairs. */
  static HarnessConfig config(String... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("keys and values must pair up");
    }
    Properties p = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      p.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return HarnessConfig.of("unit", p);
  }

  /**
   * Generates a synthetic configuration profile with 8 clusters, 4 tenants, and 40 terms per
   * vocabulary.
   */
  static HarnessConfig syntheticConfig(long rows, int dim, Metric metric, String... overrides) {
    Properties p = new Properties();
    p.setProperty("corpus.source", "synthetic");
    p.setProperty("corpus.seed", "20261028");
    p.setProperty("corpus.rows", Long.toString(rows));
    p.setProperty("corpus.dim", Integer.toString(dim));
    p.setProperty("corpus.clusters", "8");
    p.setProperty("corpus.tenants", "4");
    p.setProperty("corpus.filter.categories", "40");
    p.setProperty("corpus.filter.sources", "40");
    p.setProperty("corpus.filter.acl_tags", "4");
    p.setProperty("corpus.body.bytes", "0");
    p.setProperty("index.metric", metric.name());
    for (int i = 0; i + 1 < overrides.length; i += 2) {
      p.setProperty(overrides[i], overrides[i + 1]);
    }
    return HarnessConfig.of("unit", p);
  }

  /** Opens Corpus defined by configuration profile. */
  static Corpus open(HarnessConfig config) {
    try {
      return CorpusFactory.open(config);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Opens synthetic test corpus. */
  static Corpus synthetic(long rows, int dim, Metric metric, String... overrides) {
    return open(syntheticConfig(rows, dim, metric, overrides));
  }

  /**
   * Naive exact nearest neighbor calculation scanning all rows and sorting by distance then
   * ordinal.
   */
  static NeighborList naive(Metric metric, float[] query, QueryCondition condition, RowSource rows,
    int capacity) {
    List<double[]> passing = new ArrayList<>();
    rows.scan(0, rows.ordinalLimit(), (ordinal, vector, attributes) -> {
      if (!condition.test(attributes)) {
        return;
      }
      double d = metric.distance(query, vector);
      if (condition.hasRadius() && !(d < condition.radius())) {
        return;
      }
      passing.add(new double[] { d, ordinal });
    });
    passing
      .sort(Comparator.<double[]> comparingDouble(e -> e[0]).thenComparingLong(e -> (long) e[1]));
    int n = Math.min(capacity, passing.size());
    long[] o = new long[n];
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      d[i] = passing.get(i)[0];
      o[i] = (long) passing.get(i)[1];
    }
    return new NeighborList(o, d, passing.size() <= capacity);
  }

  /** Asserts equality of neighbor ordinals, distances, and completeness flags. */
  static void assertListEquals(String message, NeighborList expected, NeighborList actual) {
    assertNeighborsEqual(message, expected, actual);
    assertEquals(message + ": complete", expected.complete(), actual.complete());
  }

  /** Asserts equality of neighbor ordinals and distances. */
  static void assertNeighborsEqual(String message, NeighborList expected, NeighborList actual) {
    assertEquals(message + ": size of " + actual + " vs " + expected, expected.size(),
      actual.size());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(message + ": ordinal at rank " + i, expected.ordinal(i), actual.ordinal(i));
      assertEquals(message + ": distance at rank " + i, expected.distance(i), actual.distance(i),
        0.0);
    }
  }

  /** Returns top k prefix of neighbor list, preserving completeness only if fully covered. */
  static NeighborList prefix(NeighborList list, int k) {
    int n = Math.min(k, list.size());
    long[] o = new long[n];
    double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      o[i] = list.ordinal(i);
      d[i] = list.distance(i);
    }
    return new NeighborList(o, d, list.complete() && list.size() <= k);
  }
}
