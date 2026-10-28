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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class VocabularyTest {

  private static final double[] EXPONENTS = { 0.0, 0.5, 1.0, 1.5, 2.0 };

  @Test
  public void testRejectsEmptyVocabulary() {
    for (int size : new int[] { 0, -1 }) {
      try {
        new Vocabulary("c", size, 0.0);
        fail("Expected an exception for size " + size);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  @Test
  public void testAccessors() {
    Vocabulary v = new Vocabulary("c", 1000, 1.1);
    assertEquals("c", v.prefix());
    assertEquals(1000, v.size());
    assertEquals(1.1, v.exponent(), 0.0);
    assertTrue(v.toString().contains("1000 terms"));
    assertTrue(v.toString().contains("zipf=1.1"));
  }

  @Test
  public void testProbabilitiesSumToOne() {
    for (double s : EXPONENTS) {
      for (int size : new int[] { 1, 2, 16, 1000, 100_000 }) {
        Vocabulary v = new Vocabulary("x", size, s);
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
          assertTrue(v.probability(i) > 0.0);
          sum += v.probability(i);
        }
        assertEquals(s + "/" + size, 1.0, sum, 1e-9);
        assertEquals(1.0, v.rangeProbability(0, size), 0.0);
      }
    }
  }

  @Test
  public void testUniformProbabilities() {
    Vocabulary v = new Vocabulary("c", 40, 0.0);
    for (int i = 0; i < 40; i++) {
      assertEquals(1.0 / 40, v.probability(i), 1e-15);
    }
  }

  @Test
  public void testZipfProbabilities() {
    for (double s : new double[] { 0.5, 1.0, 2.0 }) {
      Vocabulary v = new Vocabulary("c", 500, s);
      double h = 0.0;
      for (int i = 1; i <= 500; i++) {
        h += 1.0 / Math.pow(i, s);
      }
      for (int i = 0; i < 500; i++) {
        assertEquals(1.0 / Math.pow(i + 1, s) / h, v.probability(i), 1e-12);
        if (i > 0) {
          assertTrue("Not decreasing at " + i, v.probability(i) < v.probability(i - 1));
        }
      }
      assertEquals(Math.pow(2, s), v.probability(0) / v.probability(1), 1e-9);
    }
  }

  @Test
  public void testTermNames() {
    Vocabulary v = new Vocabulary("c", 1000, 0.0);
    assertEquals("c0000", v.term(0));
    assertEquals("c0042", v.term(42));
    assertEquals("c0999", v.term(999));
    // Width is at least four digits
    assertEquals("acl0003", new Vocabulary("acl", 16, 0.0).term(3));
    // and grows with the size
    assertEquals("t9999", new Vocabulary("t", 10000, 0.0).term(9999));
    assertEquals("t00042", new Vocabulary("t", 10001, 0.0).term(42));
    assertEquals("s000042", new Vocabulary("s", 1_000_000, 0.0).term(42));
  }

  @Test
  public void testOrdinalOfRoundTrip() {
    for (int size : new int[] { 1, 16, 1000, 10001 }) {
      Vocabulary v = new Vocabulary("k", size, 1.0);
      for (int i = 0; i < size; i++) {
        assertEquals(i, v.ordinalOf(v.term(i)));
      }
    }
  }

  @Test
  public void testOrdinalOfRejectsNonTerms() {
    Vocabulary v = new Vocabulary("c", 1000, 0.0);
    String[] bad = { null, "", "c", "c42", "c00042", "d0042", "C0042", "cc0042", "c1000", "c9999",
      "cabcd", "c-001", "c+001", "c 001", "c004 ", "c\u0660\u0660\u0661\u0662" };
    for (String t : bad) {
      assertEquals(String.valueOf(t), -1, v.ordinalOf(t));
    }
  }

  @Test
  public void testLexicographicOrderEqualsOrdinalOrder() {
    for (int size : new int[] { 16, 1000, 12345 }) {
      Vocabulary v = new Vocabulary("c", size, 0.0);
      List<String> terms = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        terms.add(v.term(i));
      }
      List<String> sorted = new ArrayList<>(terms);
      Collections.shuffle(sorted, new Random(3));
      Collections.sort(sorted);
      assertEquals(terms, sorted);
    }
  }

  @Test
  public void testRangeProbability() {
    Vocabulary v = new Vocabulary("c", 100, 1.0);
    assertEquals(0.0, v.rangeProbability(5, 5), 0.0);
    assertEquals(0.0, v.rangeProbability(7, 3), 0.0);
    assertEquals(v.probability(0), v.rangeProbability(0, 1), 1e-15);
    assertEquals(v.probability(99), v.rangeProbability(99, 100), 1e-12);
    for (int a = 0; a < 100; a += 7) {
      for (int b = a; b <= 100; b += 11) {
        double sum = 0.0;
        for (int i = a; i < b; i++) {
          sum += v.probability(i);
        }
        assertEquals(sum, v.rangeProbability(a, b), 1e-12);
        for (int c = b; c <= 100; c += 13) {
          assertEquals(v.rangeProbability(a, c),
            v.rangeProbability(a, b) + v.rangeProbability(b, c), 1e-12);
        }
      }
    }
  }

  @Test
  public void testSampleBoundaries() {
    for (double s : EXPONENTS) {
      Vocabulary v = new Vocabulary("c", 50, s);
      assertEquals(0, v.sample(0.0));
      assertEquals(49, v.sample(Math.nextDown(1.0)));
      if (s > 0) {
        // A cumulative boundary belongs to the next term
        for (int k = 1; k < 50; k++) {
          double boundary = v.rangeProbability(0, k);
          assertEquals(k, v.sample(boundary));
          assertEquals(k - 1, v.sample(Math.nextDown(boundary)));
        }
      }
    }
  }

  @Test
  public void testSampleIsMonotone() {
    for (double s : EXPONENTS) {
      Vocabulary v = new Vocabulary("c", 64, s);
      int prev = 0;
      for (int i = 0; i < 10000; i++) {
        int t = v.sample(i / 10000.0);
        assertTrue(t >= prev);
        assertTrue(t < 64);
        prev = t;
      }
    }
  }

  @Test
  public void testSampleDistribution() {
    for (double s : new double[] { 0.0, 1.0, 1.5 }) {
      int size = 20;
      Vocabulary v = new Vocabulary("c", size, s);
      SplitMix64 rnd = new SplitMix64(1000 + (long) (s * 10));
      int n = 200_000;
      long[] counts = new long[size];
      for (int i = 0; i < n; i++) {
        counts[v.sample(rnd)]++;
      }
      double chi2 = 0.0;
      for (int i = 0; i < size; i++) {
        double expected = n * v.probability(i);
        chi2 += (counts[i] - expected) * (counts[i] - expected) / expected;
      }
      // 19 degrees of freedom; the 0.9999 quantile is about 50.
      assertTrue("zipf " + s + " chi-square " + chi2, chi2 < 50.0);
    }
  }

  @Test
  public void testRangeForFullSelectivity() {
    for (double s : EXPONENTS) {
      Vocabulary v = new Vocabulary("c", 1000, s);
      assertArrayEquals(new int[] { 0, 1000 }, v.rangeForSelectivity(1.0, 17));
      assertArrayEquals(new int[] { 0, 1000 }, v.rangeForSelectivity(1.5, 0));
    }
  }

  @Test
  public void testRangeForSelectivityUniform() {
    int size = 1000;
    Vocabulary v = new Vocabulary("c", size, 0.0);
    for (double target : new double[] { 0.001, 0.01, 0.1, 0.25, 1.0 }) {
      Set<Integer> starts = new HashSet<>();
      for (int offset : new int[] { 0, 1, 7, 999, 7919, 123456, -5 }) {
        int[] r = v.rangeForSelectivity(target, offset);
        checkRange(v, r);
        double achieved = v.rangeProbability(r[0], r[1]);
        assertEquals(target + "@" + offset, target, achieved, 0.5 / size);
        starts.add(r[0]);
      }
      if (target < 1.0) {
        assertTrue("Offsets should move the range: " + starts, starts.size() > 3);
      }
    }
  }

  @Test
  public void testRangeForSelectivityZipf() {
    int size = 1000;
    Vocabulary v = new Vocabulary("c", size, 1.0);
    for (double target : new double[] { 0.001, 0.01, 0.1, 1.0 }) {
      Set<Integer> starts = new HashSet<>();
      for (int offset : new int[] { 0, 1, 7, 500, 999, 7919, 123456 }) {
        int[] r = v.rangeForSelectivity(target, offset);
        checkRange(v, r);
        double achieved = v.rangeProbability(r[0], r[1]);
        assertEquals(target + "@" + offset, target, achieved, target * 0.01 + 1e-12);
        starts.add(r[0]);
      }
      if (target < 1.0) {
        assertTrue("Offsets should move the range: " + starts, starts.size() > 1);
      }
    }
  }

  @Test
  public void testRangeForSelectivityIsBestOrWithinOnePercent() {
    // The search stops at the first range within 1% of the target, else returns the best range.
    for (double s : new double[] { 0.5, 1.0, 2.0 }) {
      int size = 50;
      Vocabulary v = new Vocabulary("c", size, s);
      for (double target : new double[] { 0.003, 0.05, 0.3, 0.7, 0.95 }) {
        double best = Double.MAX_VALUE;
        for (int a = 0; a < size; a++) {
          for (int b = a + 1; b <= size; b++) {
            best = Math.min(best, Math.abs(v.rangeProbability(a, b) - target));
          }
        }
        for (int offset : new int[] { 0, 7, 49, 123 }) {
          int[] r = v.rangeForSelectivity(target, offset);
          checkRange(v, r);
          double err = Math.abs(v.rangeProbability(r[0], r[1]) - target);
          assertTrue(s + "/" + target + "@" + offset + ": " + err + " vs best " + best,
            err <= Math.max(best + 1e-12, target * 0.01));
        }
      }
    }
  }

  @Test
  public void testRangeForSelectivitySmallVocabulary() {
    // The smallest reachable selectivity is one term.
    Vocabulary acl = new Vocabulary("acl", 16, 0.0);
    int[] r = acl.rangeForSelectivity(0.001, 3);
    checkRange(acl, r);
    assertEquals(1, r[1] - r[0]);
    Vocabulary one = new Vocabulary("t", 1, 1.0);
    assertArrayEquals(new int[] { 0, 1 }, one.rangeForSelectivity(0.5, 9));
  }

  @Test
  public void testRangeForSelectivityIsDeterministic() {
    Vocabulary a = new Vocabulary("c", 1000, 1.0);
    Vocabulary b = new Vocabulary("c", 1000, 1.0);
    for (int offset = 0; offset < 50; offset++) {
      assertArrayEquals(a.rangeForSelectivity(0.01, offset), b.rangeForSelectivity(0.01, offset));
    }
    assertNotEquals(a.rangeForSelectivity(0.01, 0)[0], a.rangeForSelectivity(0.01, 400)[0]);
  }

  private static void checkRange(Vocabulary v, int[] r) {
    assertEquals(2, r.length);
    assertTrue(r[0] >= 0);
    assertTrue(r[0] < r[1]);
    assertTrue(r[1] <= v.size());
  }
}
