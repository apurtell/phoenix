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

import java.util.Arrays;
import java.util.Locale;

/**
 * A bounded categorical domain with uniform or Zipf weights. Term i has weight proportional to
 * {@code 1 / (i + 1)^s}, so term 0 is the most frequent; s = 0 is uniform. Terms are named with a
 * prefix and a zero padded ordinal, so that lexicographic order equals ordinal order and a range of
 * ordinals is a range of names. Because the weights are known exactly, so is the selectivity of any
 * predicate over the terms.
 */
public final class Vocabulary {

  private final String prefix;
  private final int size;
  private final double exponent;
  private final double[] probabilities;
  private final double[] cumulative;
  private final int width;

  public Vocabulary(String prefix, int size, double exponent) {
    if (size <= 0) {
      throw new IllegalArgumentException("Vocabulary size must be positive");
    }
    this.prefix = prefix;
    this.size = size;
    this.exponent = exponent;
    this.width = Math.max(4, Integer.toString(size - 1).length());
    double[] w = new double[size];
    double total = 0.0;
    for (int i = 0; i < size; i++) {
      w[i] = exponent == 0.0 ? 1.0 : 1.0 / Math.pow(i + 1, exponent);
      total += w[i];
    }
    probabilities = new double[size];
    cumulative = new double[size];
    double c = 0.0;
    for (int i = 0; i < size; i++) {
      probabilities[i] = w[i] / total;
      c += probabilities[i];
      cumulative[i] = c;
    }
    cumulative[size - 1] = 1.0;
  }

  public int size() {
    return size;
  }

  public double exponent() {
    return exponent;
  }

  public String prefix() {
    return prefix;
  }

  /** Returns the name of a term, such as c0042. */
  public String term(int i) {
    StringBuilder sb = new StringBuilder(prefix.length() + width);
    sb.append(prefix);
    String digits = Integer.toString(i);
    for (int pad = digits.length(); pad < width; pad++) {
      sb.append('0');
    }
    return sb.append(digits).toString();
  }

  /** Returns the ordinal of a term name, or -1 if the name is not a term. */
  public int ordinalOf(String term) {
    if (term == null || !term.startsWith(prefix) || term.length() != prefix.length() + width) {
      return -1;
    }
    // Only ASCII digits, as term() writes them; Integer.parseInt would also accept a sign and
    // non-ASCII digits.
    long i = 0;
    for (int p = prefix.length(); p < term.length(); p++) {
      char c = term.charAt(p);
      if (c < '0' || c > '9') {
        return -1;
      }
      i = i * 10 + (c - '0');
      if (i >= size) {
        return -1;
      }
    }
    return (int) i;
  }

  public double probability(int i) {
    return probabilities[i];
  }

  /** Returns the probability mass of the ordinal range [from, to). */
  public double rangeProbability(int from, int to) {
    if (from >= to) {
      return 0.0;
    }
    double hi = cumulative[to - 1];
    double lo = from == 0 ? 0.0 : cumulative[from - 1];
    return hi - lo;
  }

  /** Draws a term ordinal by inverse transform sampling. */
  public int sample(SplitMix64 rnd) {
    return sample(rnd.nextDouble());
  }

  /** Returns the term ordinal for a uniform value in [0, 1). */
  public int sample(double u) {
    if (exponent == 0.0) {
      return Math.min(size - 1, (int) (u * size));
    }
    int idx = Arrays.binarySearch(cumulative, u);
    if (idx < 0) {
      idx = -idx - 1;
    } else {
      // u equals a cumulative boundary, which belongs to the next term
      idx = idx + 1;
    }
    return Math.min(idx, size - 1);
  }

  /**
   * Finds a contiguous ordinal range [from, to) whose probability mass is closest to a target
   * selectivity. The search prefers ranges in the tail, where terms are rare, so that small
   * selectivities are reachable under Zipf skew. The offset argument rotates the preferred start so
   * that different queries of the same selectivity use different ranges.
   * @return an array {from, to}
   */
  public int[] rangeForSelectivity(double target, int offset) {
    if (target >= 1.0) {
      return new int[] { 0, size };
    }
    int bestFrom = 0;
    int bestTo = 1;
    double bestErr = Double.MAX_VALUE;
    if (exponent == 0.0) {
      int len = Math.max(1, (int) Math.round(target * size));
      int from = Math.floorMod(offset, size - len + 1);
      return new int[] { from, from + len };
    }
    // Two pointer search over all start positions; mass is monotone in the end position.
    int start = Math.floorMod(offset, size);
    for (int n = 0; n < size; n++) {
      int from = (start + n) % size;
      int lo = from + 1;
      int hi = size;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (rangeProbability(from, mid) < target) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      for (int to = Math.max(from + 1, lo - 1); to <= Math.min(size, lo); to++) {
        double err = Math.abs(rangeProbability(from, to) - target);
        if (err < bestErr - 1e-15) {
          bestErr = err;
          bestFrom = from;
          bestTo = to;
        }
      }
      if (bestErr <= target * 0.01) {
        break;
      }
    }
    return new int[] { bestFrom, bestTo };
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "Vocabulary[%s, %d terms, zipf=%s]", prefix, size, exponent);
  }
}
