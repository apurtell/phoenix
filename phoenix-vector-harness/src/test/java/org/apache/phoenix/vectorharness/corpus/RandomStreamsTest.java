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
package org.apache.phoenix.vectorharness.corpus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.Test;

public class RandomStreamsTest {

  private static final String[] NAMES = { RandomStreams.CLUSTERS, RandomStreams.DOCS,
    RandomStreams.ATTRIBUTES, RandomStreams.QUERIES, RandomStreams.MUTATIONS, RandomStreams.DRIFT };

  /** Draws the values of one stream element. */
  private static long[] element(RandomStreams s, String stream, long ordinal) {
    SplitMix64 r = s.at(stream, ordinal);
    long[] out = new long[8];
    for (int i = 0; i < out.length; i++) {
      out[i] = r.nextLong();
    }
    return out;
  }

  @Test
  public void testFnv1a64ReferenceValues() {
    assertEquals(0xcbf29ce484222325L, RandomStreams.fnv1a64(""));
    assertEquals(0xaf63dc4c8601ec8cL, RandomStreams.fnv1a64("a"));
    assertEquals(0x85944171f73967e8L, RandomStreams.fnv1a64("foobar"));
  }

  @Test
  public void testMasterSeed() {
    assertEquals(42L, new RandomStreams(42L).masterSeed());
  }

  @Test
  public void testStreamSeedsDifferByName() {
    RandomStreams s = new RandomStreams(42L);
    Set<Long> seeds = new HashSet<>();
    for (String name : NAMES) {
      assertTrue(name, seeds.add(s.streamSeed(name)));
      assertEquals(s.streamSeed(name), new RandomStreams(42L).streamSeed(name));
    }
    assertNotEquals(s.streamSeed(RandomStreams.DOCS),
      new RandomStreams(43L).streamSeed(RandomStreams.DOCS));
    assertNotEquals(s.streamSeed("docs"), s.streamSeed("Docs"));
  }

  @Test
  public void testElementsDifferByOrdinalAndStream() {
    RandomStreams s = new RandomStreams(7L);
    Set<Long> firsts = new HashSet<>();
    for (String name : NAMES) {
      for (long ordinal = 0; ordinal < 2000; ordinal++) {
        assertTrue(firsts.add(s.at(name, ordinal).nextLong()));
      }
    }
  }

  @Test
  public void testSubStreams() {
    RandomStreams s = new RandomStreams(7L);
    Set<Long> firsts = new HashSet<>();
    for (long sub = 0; sub < 20; sub++) {
      for (long ordinal = 0; ordinal < 200; ordinal++) {
        long v = s.at(RandomStreams.MUTATIONS, sub, ordinal).nextLong();
        assertTrue(firsts.add(v));
        assertEquals(v, s.at(RandomStreams.MUTATIONS, sub, ordinal).nextLong());
      }
    }
    for (long ordinal = 0; ordinal < 200; ordinal++) {
      assertTrue(firsts.add(s.at(RandomStreams.MUTATIONS, ordinal).nextLong()));
    }
  }

  @Test
  public void testAtSeedMatchesAt() {
    RandomStreams s = new RandomStreams(99L);
    for (String name : NAMES) {
      long seed = s.streamSeed(name);
      for (long ordinal = 0; ordinal < 50; ordinal++) {
        assertEquals(s.at(name, ordinal).nextLong(),
          RandomStreams.atSeed(seed, ordinal).nextLong());
      }
    }
  }

  @Test
  public void testOrderIndependence() {
    int n = 300;
    RandomStreams forward = new RandomStreams(5L);
    long[][] inOrder = new long[n][];
    for (int i = 0; i < n; i++) {
      inOrder[i] = element(forward, RandomStreams.DOCS, i);
    }
    // In reverse, interleaved with another stream, on a fresh instance
    RandomStreams reverse = new RandomStreams(5L);
    for (int i = n - 1; i >= 0; i--) {
      element(reverse, RandomStreams.QUERIES, i);
      assertArrayEquals(inOrder[i], element(reverse, RandomStreams.DOCS, i));
    }
    // In a random order
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      order.add(i);
    }
    Collections.shuffle(order, new Random(1));
    for (int i : order) {
      assertArrayEquals(inOrder[i], element(forward, RandomStreams.DOCS, i));
    }
    // Element 123 alone
    assertArrayEquals(inOrder[123], element(new RandomStreams(5L), RandomStreams.DOCS, 123));
    // In parallel
    RandomStreams shared = new RandomStreams(5L);
    long[][] parallel = new long[n][];
    IntStream.range(0, n).parallel()
      .forEach(i -> parallel[i] = element(shared, RandomStreams.DOCS, i));
    for (int i = 0; i < n; i++) {
      assertArrayEquals(inOrder[i], parallel[i]);
    }
  }

  @Test
  public void testAdjacentOrdinalsAreUncorrelated() {
    RandomStreams s = new RandomStreams(11L);
    int n = 50_000;
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = s.at(RandomStreams.DOCS, i).nextDouble();
      y[i] = s.at(RandomStreams.ATTRIBUTES, i).nextDouble();
    }
    // Standard error of a correlation over n pairs is 1 / sqrt(n), about 0.0045.
    assertEquals(0.0, correlation(x, 0, x, 1, n - 1), 0.02);
    assertEquals(0.0, correlation(x, 0, y, 0, n), 0.02);
    double mean = 0.0;
    for (double v : x) {
      mean += v;
    }
    assertEquals(0.5, mean / n, 0.005);
  }

  private static double correlation(double[] a, int offA, double[] b, int offB, int n) {
    double ma = 0.0;
    double mb = 0.0;
    for (int i = 0; i < n; i++) {
      ma += a[offA + i];
      mb += b[offB + i];
    }
    ma /= n;
    mb /= n;
    double sab = 0.0;
    double saa = 0.0;
    double sbb = 0.0;
    for (int i = 0; i < n; i++) {
      double da = a[offA + i] - ma;
      double db = b[offB + i] - mb;
      sab += da * db;
      saa += da * da;
      sbb += db * db;
    }
    return sab / Math.sqrt(saa * sbb);
  }
}
