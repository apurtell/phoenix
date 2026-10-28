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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.SplittableRandom;
import org.junit.Test;

public class MetricTest {

  private static final double EPS = 1e-12;

  private static float[] v(float... x) {
    return x;
  }

  @Test
  public void testL2() {
    assertEquals(5.0, Metric.L2.distance(v(1, 2, 3), v(4, 6, 3)), EPS);
    assertEquals(0.0, Metric.L2.distance(v(1, 2, 3), v(1, 2, 3)), 0.0);
    assertEquals(Math.sqrt(2.0), Metric.L2.distance(v(1, 0), v(0, 1)), EPS);
    assertEquals(Math.sqrt(14.0), Metric.L2.distance(v(1, 2, 3), v(0, 0, 0)), EPS);
    // Symmetric
    assertEquals(Metric.L2.distance(v(0.5f, -2, 7), v(3, 1, -1)),
      Metric.L2.distance(v(3, 1, -1), v(0.5f, -2, 7)), 0.0);
  }

  @Test
  public void testL2UsesDoublePrecision() {
    // In float arithmetic the sum of squares 1e8 + 1 rounds to 1e8; in double it is exact.
    assertEquals(Math.sqrt(100_000_001.0), Metric.L2.distance(v(1e4f, 1f), v(0f, 0f)), 1e-9);
    assertTrue(Metric.L2.distance(v(1e4f, 1f), v(0f, 0f)) > 10000.0);
    assertEquals(-100_000_001.0, Metric.INNER_PRODUCT.distance(v(1e4f, 1f), v(1e4f, 1f)), 0.0);
  }

  @Test
  public void testCosine() {
    assertEquals(1.0, Metric.COSINE.distance(v(1, 0), v(0, 1)), EPS);
    assertEquals(0.0, Metric.COSINE.distance(v(1, 0), v(3, 0)), EPS);
    assertEquals(2.0, Metric.COSINE.distance(v(1, 0), v(-1, 0)), EPS);
    // dot = 8, norms 3 and 3
    assertEquals(1.0 - 8.0 / 9.0, Metric.COSINE.distance(v(1, 2, 2), v(2, 1, 2)), EPS);
    // dot = 1*4 + 2*-5 + 3*6 = 12, norms sqrt(14) and sqrt(77)
    assertEquals(1.0 - 12.0 / (Math.sqrt(14) * Math.sqrt(77)),
      Metric.COSINE.distance(v(1, 2, 3), v(4, -5, 6)), EPS);
    // Scale invariant
    assertEquals(Metric.COSINE.distance(v(1, 2, 3), v(4, -5, 6)),
      Metric.COSINE.distance(v(10, 20, 30), v(0.4f, -0.5f, 0.6f)), 1e-7);
  }

  @Test
  public void testCosineZeroVector() {
    assertEquals(1.0, Metric.COSINE.distance(v(0, 0, 0), v(1, 2, 3)), 0.0);
    assertEquals(1.0, Metric.COSINE.distance(v(1, 2, 3), v(0, 0, 0)), 0.0);
    assertEquals(1.0, Metric.COSINE.distance(v(0, 0, 0), v(0, 0, 0)), 0.0);
  }

  @Test
  public void testCosineClampsToRange() {
    SplittableRandom rnd = new SplittableRandom(17);
    boolean overOne = false;
    boolean underMinusOne = false;
    for (int attempt = 0; attempt < 20000 && !(overOne && underMinusOne); attempt++) {
      int dim = 3 + rnd.nextInt(30);
      float[] a = new float[dim];
      float[] neg = new float[dim];
      for (int i = 0; i < dim; i++) {
        a[i] = (float) (rnd.nextDouble() * 2 - 1);
        neg[i] = -a[i];
      }
      double raw = rawCosine(a, a);
      double same = Metric.COSINE.distance(a, a);
      double opposite = Metric.COSINE.distance(a, neg);
      assertTrue(same >= 0.0);
      assertTrue(same < 1e-12);
      assertTrue(opposite <= 2.0);
      assertTrue(opposite > 2.0 - 1e-12);
      if (raw > 1.0) {
        overOne = true;
        assertEquals(0.0, same, 0.0);
      }
      if (rawCosine(a, neg) < -1.0) {
        underMinusOne = true;
        assertEquals(2.0, opposite, 0.0);
      }
    }
    // The clamp matters: rounding pushes the cosine of a vector with itself past 1
    assertTrue(overOne);
    assertTrue(underMinusOne);
  }

  /** The cosine similarity computed the same way as Metric, without the clamp. */
  private static double rawCosine(float[] a, float[] b) {
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
    return dot / (Math.sqrt(na) * Math.sqrt(nb));
  }

  @Test
  public void testInnerProduct() {
    // dot = 4 - 10 + 18 = 12
    assertEquals(-12.0, Metric.INNER_PRODUCT.distance(v(1, 2, 3), v(4, -5, 6)), EPS);
    assertEquals(0.0, Metric.INNER_PRODUCT.distance(v(1, 0), v(0, 1)), 0.0);
    assertEquals(1.0, Metric.INNER_PRODUCT.distance(v(1, 0), v(-1, 0)), EPS);
    assertEquals(-0.0, Metric.INNER_PRODUCT.distance(v(0, 0), v(0, 0)), 0.0);
    // Larger dot product ranks nearer
    assertTrue(Metric.INNER_PRODUCT.distance(v(1, 1), v(2, 2))
        < Metric.INNER_PRODUCT.distance(v(1, 1), v(1, 1)));
  }

  @Test
  public void testDimensionMismatch() {
    for (Metric m : Metric.values()) {
      try {
        m.distance(v(1, 2), v(1, 2, 3));
        fail("Expected an exception for " + m);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains("2"));
        assertTrue(e.getMessage().contains("3"));
      }
    }
  }

  @Test
  public void testEmptyVectors() {
    assertEquals(0.0, Metric.L2.distance(new float[0], new float[0]), 0.0);
    assertEquals(1.0, Metric.COSINE.distance(new float[0], new float[0]), 0.0);
    assertEquals(0.0, Metric.INNER_PRODUCT.distance(new float[0], new float[0]), 0.0);
  }

  @Test
  public void testParse() {
    assertEquals(Metric.L2, Metric.parse("L2"));
    assertEquals(Metric.L2, Metric.parse(" l2 "));
    assertEquals(Metric.L2, Metric.parse("euclidean"));
    assertEquals(Metric.COSINE, Metric.parse("Cosine"));
    assertEquals(Metric.COSINE, Metric.parse("angular"));
    assertEquals(Metric.INNER_PRODUCT, Metric.parse("inner_product"));
    assertEquals(Metric.INNER_PRODUCT, Metric.parse("IP"));
    assertEquals(Metric.INNER_PRODUCT, Metric.parse("dot"));
    for (Metric m : Metric.values()) {
      assertEquals(m, Metric.parse(m.name()));
      assertEquals(m, Metric.parse(m.indexOption()));
    }
    try {
      Metric.parse("hamming");
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("hamming"));
    }
  }

  @Test
  public void testSqlNames() {
    assertEquals("L2_DISTANCE", Metric.L2.sqlFunction());
    assertEquals("COSINE_DISTANCE", Metric.COSINE.sqlFunction());
    assertEquals("INNER_PRODUCT", Metric.INNER_PRODUCT.sqlFunction());
    assertEquals("<->", Metric.L2.sqlOperator());
    assertEquals("<=>", Metric.COSINE.sqlOperator());
    assertEquals("<#>", Metric.INNER_PRODUCT.sqlOperator());
    assertEquals("L2", Metric.L2.indexOption());
    assertEquals("COSINE", Metric.COSINE.indexOption());
    assertEquals("INNER_PRODUCT", Metric.INNER_PRODUCT.indexOption());
  }

  @Test
  public void testNormalizesByDefault() {
    assertTrue(Metric.COSINE.normalizesByDefault());
    assertFalse(Metric.L2.normalizesByDefault());
    assertFalse(Metric.INNER_PRODUCT.normalizesByDefault());
  }
}
