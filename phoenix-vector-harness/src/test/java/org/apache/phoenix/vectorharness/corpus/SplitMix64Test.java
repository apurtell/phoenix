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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.Test;

public class SplitMix64Test {

  @Test
  public void testReferenceOutput() {
    // The first outputs of SplitMix64 seeded with 0, from the reference implementation.
    SplitMix64 r = new SplitMix64(0L);
    assertEquals(0xe220a8397b1dcdafL, r.nextLong());
    assertEquals(0x6e789e6aa1b965f4L, r.nextLong());
    assertEquals(0x06c45d188009454fL, r.nextLong());
  }

  @Test
  public void testMatchesSplittableRandomAlgorithm() {
    // SplittableRandom uses the same gamma and finalizer for nextLong, nextDouble, and
    // nextLong(bound), so the two generators agree for any seed.
    for (long seed : new long[] { 0L, 1L, -1L, 42L, 0x123456789abcdefL, Long.MIN_VALUE }) {
      SplitMix64 a = new SplitMix64(seed);
      SplittableRandom b = new SplittableRandom(seed);
      for (int i = 0; i < 1000; i++) {
        switch (i % 3) {
          case 0:
            assertEquals(b.nextLong(), a.nextLong());
            break;
          case 1:
            assertEquals(b.nextDouble(), a.nextDouble(), 0.0);
            break;
          default:
            long bound = 1L + (i * 7919L) % 1_000_003L;
            assertEquals(b.nextLong(bound), a.nextLong(bound));
            break;
        }
      }
    }
  }

  @Test
  public void testDeterminism() {
    SplitMix64 a = new SplitMix64(12345L);
    SplitMix64 b = new SplitMix64(12345L);
    for (int i = 0; i < 1000; i++) {
      assertEquals(a.nextLong(), b.nextLong());
      assertEquals(a.nextInt(97), b.nextInt(97));
      assertEquals(a.nextGaussian(), b.nextGaussian(), 0.0);
      assertEquals(a.nextOpenDouble(), b.nextOpenDouble(), 0.0);
    }
  }

  @Test
  public void testDifferentSeedsDiffer() {
    Set<Long> firsts = new HashSet<>();
    for (long seed = 0; seed < 1000; seed++) {
      assertTrue(firsts.add(new SplitMix64(seed).nextLong()));
    }
    SplitMix64 a = new SplitMix64(1L);
    SplitMix64 b = new SplitMix64(2L);
    int same = 0;
    for (int i = 0; i < 1000; i++) {
      if (a.nextLong() == b.nextLong()) {
        same++;
      }
    }
    assertEquals(0, same);
  }

  @Test
  public void testMix64() {
    assertEquals(0L, SplitMix64.mix64(0L));
    Set<Long> seen = new HashSet<>();
    for (long z = 0; z < 10000; z++) {
      assertTrue(seen.add(SplitMix64.mix64(z)));
    }
    // Avalanche: flipping one input bit flips about half of the output bits.
    long totalFlips = 0;
    int trials = 0;
    SplitMix64 rnd = new SplitMix64(99L);
    for (int t = 0; t < 200; t++) {
      long x = rnd.nextLong();
      for (int bit = 0; bit < 64; bit++) {
        totalFlips += Long.bitCount(SplitMix64.mix64(x) ^ SplitMix64.mix64(x ^ (1L << bit)));
        trials++;
      }
    }
    double mean = (double) totalFlips / trials;
    assertEquals(32.0, mean, 0.5);
  }

  @Test
  public void testCombineIsOrderSensitive() {
    assertNotEquals(SplitMix64.combine(1L, 2L), SplitMix64.combine(2L, 1L));
    assertEquals(SplitMix64.combine(7L, 9L), SplitMix64.combine(7L, 9L));
    Set<Long> seen = new HashSet<>();
    for (long a = 0; a < 100; a++) {
      for (long b = 0; b < 100; b++) {
        assertTrue(seen.add(SplitMix64.combine(a, b)));
      }
    }
  }

  @Test
  public void testNextIntBounds() {
    int[] bounds = { 1, 2, 3, 7, 10, 1000, 1 << 30, (1 << 30) + 1, Integer.MAX_VALUE };
    SplitMix64 r = new SplitMix64(5L);
    for (int bound : bounds) {
      for (int i = 0; i < 20000; i++) {
        int x = r.nextInt(bound);
        assertTrue(bound + ": " + x, x >= 0 && x < bound);
      }
    }
    SplitMix64 one = new SplitMix64(6L);
    for (int i = 0; i < 100; i++) {
      assertEquals(0, one.nextInt(1));
    }
  }

  @Test
  public void testNextIntRejectsNonPositiveBound() {
    SplitMix64 r = new SplitMix64(1L);
    for (int bound : new int[] { 0, -1, Integer.MIN_VALUE }) {
      try {
        r.nextInt(bound);
        fail("Expected an exception for " + bound);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  @Test
  public void testNextIntUniformity() {
    int bound = 10;
    int n = 200_000;
    long[] counts = new long[bound];
    SplitMix64 r = new SplitMix64(2024L);
    for (int i = 0; i < n; i++) {
      counts[r.nextInt(bound)]++;
    }
    double expected = (double) n / bound;
    double chi2 = 0.0;
    for (long c : counts) {
      assertEquals(expected, c, expected * 0.03);
      chi2 += (c - expected) * (c - expected) / expected;
    }
    // 9 degrees of freedom; the 0.9999 quantile is about 33.7.
    assertTrue("chi-square " + chi2, chi2 < 33.7);
  }

  @Test
  public void testNextIntLargeBoundIsUnbiased() {
    // For a bound of 3 * 2^29, 2^32 / bound = 8 / 3, so a multiply-shift reduction without
    // rejection maps three 32-bit values to outputs j with j mod 3 of 0 or 1, but only two to
    // outputs with j mod 3 of 2, which would get a share of 1/4 instead of 1/3.
    int bound = 3 << 29;
    int n = 300_000;
    int[] residues = new int[3];
    SplitMix64 r = new SplitMix64(77L);
    for (int i = 0; i < n; i++) {
      residues[r.nextInt(bound) % 3]++;
    }
    // Standard deviation of each share is about 0.00086.
    for (int c : residues) {
      assertEquals(1.0 / 3.0, (double) c / n, 0.005);
    }
  }

  @Test
  public void testNextLongBound() {
    SplitMix64 r = new SplitMix64(3L);
    long[] bounds = { 1L, 2L, 3L, 1L << 40, (1L << 40) + 3, Long.MAX_VALUE };
    for (long bound : bounds) {
      for (int i = 0; i < 10000; i++) {
        long x = r.nextLong(bound);
        assertTrue(bound + ": " + x, x >= 0 && x < bound);
      }
    }
    for (long bound : new long[] { 0L, -5L }) {
      try {
        r.nextLong(bound);
        fail("Expected an exception for " + bound);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
    long[] counts = new long[6];
    for (int i = 0; i < 60000; i++) {
      counts[(int) r.nextLong(6)]++;
    }
    for (long c : counts) {
      assertEquals(10000.0, c, 400.0);
    }
  }

  @Test
  public void testDoubles() {
    SplitMix64 r = new SplitMix64(8L);
    double sum = 0.0;
    int n = 100_000;
    for (int i = 0; i < n; i++) {
      double d = r.nextDouble();
      assertTrue(d >= 0.0 && d < 1.0);
      double o = r.nextOpenDouble();
      assertTrue(o > 0.0 && o <= 1.0);
      sum += d;
    }
    assertEquals(0.5, sum / n, 0.005);
  }

  @Test
  public void testOpenDoubleIsShiftedDouble() {
    SplitMix64 a = new SplitMix64(11L);
    SplitMix64 b = new SplitMix64(11L);
    for (int i = 0; i < 1000; i++) {
      assertEquals(a.nextDouble() + 0x1.0p-53, b.nextOpenDouble(), 0.0);
    }
  }

  @Test
  public void testGaussianMoments() {
    SplitMix64 r = new SplitMix64(31337L);
    int n = 200_000;
    double sum = 0.0;
    double sum2 = 0.0;
    double sum3 = 0.0;
    double sum4 = 0.0;
    int withinOne = 0;
    for (int i = 0; i < n; i++) {
      double g = r.nextGaussian();
      assertTrue(Double.isFinite(g));
      sum += g;
      sum2 += g * g;
      sum3 += g * g * g;
      sum4 += g * g * g * g;
      if (Math.abs(g) < 1.0) {
        withinOne++;
      }
    }
    double mean = sum / n;
    double var = sum2 / n - mean * mean;
    // Standard errors: mean 0.0022, variance 0.0032, skewness 0.0055, kurtosis 0.011
    assertEquals(0.0, mean, 0.01);
    assertEquals(1.0, var, 0.015);
    assertEquals(0.0, sum3 / n, 0.03);
    assertEquals(3.0, sum4 / n, 0.06);
    assertEquals(0.6827, (double) withinOne / n, 0.005);
  }

  @Test
  public void testGaussianPairsAreUncorrelated() {
    // The polar method returns two deviates per draw; the spare must not correlate with the first.
    SplitMix64 r = new SplitMix64(4L);
    int n = 100_000;
    double sxy = 0.0;
    for (int i = 0; i < n; i++) {
      sxy += r.nextGaussian() * r.nextGaussian();
    }
    assertEquals(0.0, sxy / n, 0.015);
  }
}
