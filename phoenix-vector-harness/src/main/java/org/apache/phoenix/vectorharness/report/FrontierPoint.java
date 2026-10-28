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

import java.util.Map;

/**
 * One measured point of an efficiency frontier: a group of panel queries of one layout, condition
 * family, and k, run at one sweep point of query breadth. Read from a row of frontier.csv.
 */
public final class FrontierPoint {

  private final Map<String, String> row;
  private final String layout;
  private final String family;
  private final int k;
  private final String sweep;
  private final double sweepValue;
  private final double recall;
  private final double recallLow;
  private final double recallHigh;
  private final double p50;
  private final double p99;
  private final double qps;
  private final double readAmplification;
  private final double rpcsPerQuery;

  private FrontierPoint(Map<String, String> row) {
    this.row = row;
    this.layout = row.getOrDefault("layout", "");
    this.family = row.getOrDefault("family", "");
    this.k = (int) number(row.get("k"));
    this.sweep = row.getOrDefault("sweep", "");
    this.sweepValue = number(row.get("sweep_value"));
    this.recall = number(row.get("recall"));
    this.recallLow = number(row.get("recall_low"));
    this.recallHigh = number(row.get("recall_high"));
    this.p50 = number(row.get("p50_ms"));
    this.p99 = number(row.get("p99_ms"));
    this.qps = number(row.get("throughput_qps"));
    this.readAmplification = number(row.get("read_amplification"));
    this.rpcsPerQuery = number(row.get("rpcs_per_query"));
  }

  public static FrontierPoint of(Map<String, String> row) {
    return new FrontierPoint(row);
  }

  /** Parses a CSV number; empty fields and text are NaN. */
  static double number(String s) {
    if (s == null || s.isEmpty()) {
      return Double.NaN;
    }
    if (s.equals("inf")) {
      return Double.POSITIVE_INFINITY;
    }
    if (s.equals("-inf")) {
      return Double.NEGATIVE_INFINITY;
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** Returns the key of the group this point belongs to: layout, family, and k. */
  public String group() {
    return layout + " " + family + " k=" + k;
  }

  public String layout() {
    return layout;
  }

  public String family() {
    return family;
  }

  public int k() {
    return k;
  }

  public String sweep() {
    return sweep;
  }

  public double sweepValue() {
    return sweepValue;
  }

  public double recall() {
    return recall;
  }

  public double recallLow() {
    return recallLow;
  }

  public double recallHigh() {
    return recallHigh;
  }

  public double p50() {
    return p50;
  }

  public double p99() {
    return p99;
  }

  public double qps() {
    return qps;
  }

  /** Returns the rows read per returned row, counting HNSW probe reads. */
  public double readAmplification() {
    return readAmplification;
  }

  public double rpcsPerQuery() {
    return rpcsPerQuery;
  }

  public String planClass() {
    return row.getOrDefault("plan_class", "");
  }

  public boolean planDiscrepancy() {
    return "true".equalsIgnoreCase(row.get("plan_discrepancy"));
  }

  /** Returns a column of the source row. */
  public String column(String name) {
    return row.get(name);
  }

  /** Returns the source row. */
  public Map<String, String> row() {
    return row;
  }
}
