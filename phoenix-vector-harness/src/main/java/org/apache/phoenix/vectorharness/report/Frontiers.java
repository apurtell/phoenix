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
package org.apache.phoenix.vectorharness.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Analysis of efficiency frontiers: the Pareto-efficient points of recall against a cost, the
 * cheapest point that reaches a recall target, and the best recall within a latency budget.
 */
public final class Frontiers {

  /** Tolerance of recall comparisons against a target, for rounding in the CSV. */
  static final double RECALL_TOLERANCE = 1e-9;

  /** Ascending costs; a higher throughput is a lower cost. */
  public enum Cost {
    P50("p50_ms", FrontierPoint::p50),
    P99("p99_ms", FrontierPoint::p99),
    THROUGHPUT("qps", p -> -p.qps()),
    READ_AMPLIFICATION("rows_per_result", FrontierPoint::readAmplification),
    RPCS("rpcs_per_query", FrontierPoint::rpcsPerQuery);

    private final String id;
    private final ToDoubleFunction<FrontierPoint> fn;

    Cost(String id, ToDoubleFunction<FrontierPoint> fn) {
      this.id = id;
      this.fn = fn;
    }

    public String id() {
      return id;
    }

    /** Returns the cost of a point, lower being better. */
    public double of(FrontierPoint p) {
      return fn.applyAsDouble(p);
    }

    /** Returns the displayed value of a point: the cost, or the throughput itself. */
    public double value(FrontierPoint p) {
      return this == THROUGHPUT ? p.qps() : of(p);
    }
  }

  private Frontiers() {
  }

  /** Groups points by layout, family, and k, in order of first appearance. */
  public static Map<String, List<FrontierPoint>> byGroup(List<FrontierPoint> points) {
    Map<String, List<FrontierPoint>> out = new LinkedHashMap<>();
    for (FrontierPoint p : points) {
      out.computeIfAbsent(p.group(), g -> new ArrayList<>()).add(p);
    }
    return out;
  }

  private static boolean usable(FrontierPoint p, Cost cost) {
    return !Double.isNaN(p.recall()) && !Double.isNaN(cost.of(p));
  }

  /**
   * Returns the Pareto-efficient points of a group: those that no other point beats on both recall
   * and cost, ordered by ascending cost and so by ascending recall.
   */
  public static List<FrontierPoint> pareto(List<FrontierPoint> group, Cost cost) {
    List<FrontierPoint> sorted = new ArrayList<>();
    for (FrontierPoint p : group) {
      if (usable(p, cost)) {
        sorted.add(p);
      }
    }
    sorted.sort(Comparator.comparingDouble(cost::of)
      .thenComparing(Comparator.comparingDouble(FrontierPoint::recall).reversed()));
    List<FrontierPoint> out = new ArrayList<>();
    double best = Double.NEGATIVE_INFINITY;
    for (FrontierPoint p : sorted) {
      if (p.recall() > best + RECALL_TOLERANCE) {
        out.add(p);
        best = p.recall();
      }
    }
    return out;
  }

  /** Returns the cheapest point that reaches a recall target, or null if none does. */
  public static FrontierPoint cheapestAtRecall(List<FrontierPoint> group, double target,
    Cost cost) {
    FrontierPoint best = null;
    for (FrontierPoint p : group) {
      if (!usable(p, cost) || p.recall() < target - RECALL_TOLERANCE) {
        continue;
      }
      if (best == null || cost.of(p) < cost.of(best)) {
        best = p;
      }
    }
    return best;
  }

  /**
   * Returns the point of highest recall whose p99 latency is within a budget, or null if none is.
   * Ties go to the lower p99.
   */
  public static FrontierPoint bestWithinBudget(List<FrontierPoint> group, double p99Millis) {
    FrontierPoint best = null;
    for (FrontierPoint p : group) {
      if (Double.isNaN(p.recall()) || Double.isNaN(p.p99()) || p.p99() > p99Millis) {
        continue;
      }
      if (
        best == null || p.recall() > best.recall() + RECALL_TOLERANCE
          || (Math.abs(p.recall() - best.recall()) <= RECALL_TOLERANCE && p.p99() < best.p99())
      ) {
        best = p;
      }
    }
    return best;
  }

  /** Returns the point of highest recall of a group; ties go to the lower p50. */
  public static FrontierPoint mostAccurate(List<FrontierPoint> group) {
    FrontierPoint best = null;
    for (FrontierPoint p : group) {
      if (Double.isNaN(p.recall())) {
        continue;
      }
      if (
        best == null || p.recall() > best.recall() + RECALL_TOLERANCE
          || (Math.abs(p.recall() - best.recall()) <= RECALL_TOLERANCE && p.p50() < best.p50())
      ) {
        best = p;
      }
    }
    return best;
  }

  /**
   * Returns the operating point of a group for a recall target: the point of lowest p50 that
   * reaches the target, or the most accurate point if none does.
   */
  public static FrontierPoint operatingPoint(List<FrontierPoint> group, double target) {
    FrontierPoint p = cheapestAtRecall(group, target, Cost.P50);
    return p != null ? p : mostAccurate(group);
  }
}
