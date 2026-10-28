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
package org.apache.phoenix.vectorharness.observe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies EXPLAIN output of nearest neighbor queries. The markers are the plan lines that the
 * vector code paths print: {@code CLIENT PROBING p OF c CENTROIDS (METRIC)} for IVF probing,
 * {@code INDEX <name> VECTOR GLOBAL} for any IVF index scan, {@code SERVER HNSW SEARCH <index>
 * (n CANDIDATES)} for HNSW, the {@code selective filter first} and {@code point lookup} rule
 * comments for filter-first plans, and {@code SERVER TOP-k BY <distance>} for any vector top-N. A
 * plan with a semi-join (deferred projection) is classified by its probing sub-plan.
 */
public final class PlanClassifier {

  private static final Pattern PROBING =
    Pattern.compile("CLIENT PROBING (\\d+) OF (\\d+) CENTROIDS \\((\\w+)\\)"
      + "( ACROSS ACTIVE AND BUILDING GENERATIONS)?( EXPANDING UP TO (\\d+) BATCHES)?");
  private static final Pattern HNSW =
    Pattern.compile("SERVER HNSW SEARCH (\\S+) \\((\\d+) CANDIDATES\\)");
  private static final Pattern VECTOR_GLOBAL = Pattern.compile("INDEX \\S+ VECTOR GLOBAL");
  private static final Pattern TOP_N = Pattern.compile("SERVER TOP-(\\d+) BY ");

  /** The classification of one plan and the vector parameters it shows. */
  public static final class Classification {
    private final PlanClass planClass;
    private final int probes;
    private final int centroids;
    private final int batches;
    private final boolean dualGeneration;
    private final int candidates;
    private final boolean deferredProjection;
    private final boolean serverMerge;

    Classification(PlanClass planClass, int probes, int centroids, int batches,
      boolean dualGeneration, int candidates, boolean deferredProjection, boolean serverMerge) {
      this.planClass = planClass;
      this.probes = probes;
      this.centroids = centroids;
      this.batches = batches;
      this.dualGeneration = dualGeneration;
      this.candidates = candidates;
      this.deferredProjection = deferredProjection;
      this.serverMerge = serverMerge;
    }

    public PlanClass planClass() {
      return planClass;
    }

    /** Returns the IVF probe count per generation, or 0. */
    public int probes() {
      return probes;
    }

    /** Returns the IVF centroid count, or 0. */
    public int centroids() {
      return centroids;
    }

    /** Returns the adaptive probe batch limit, 1 when not adaptive, or 0 when not IVF. */
    public int batches() {
      return batches;
    }

    /** Returns true if an IVF plan probes the active and the building generation. */
    public boolean dualGeneration() {
      return dualGeneration;
    }

    /** Returns the HNSW candidates per region, or 0. */
    public int candidates() {
      return candidates;
    }

    /** Returns true if uncovered projected columns are read by a semi-join on the top keys. */
    public boolean deferredProjection() {
      return deferredProjection;
    }

    /** Returns true if uncovered filter columns are merged from the data rows. */
    public boolean serverMerge() {
      return serverMerge;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(planClass.name());
      if (planClass == PlanClass.IVF_PROBE) {
        sb.append('(').append(probes).append('/').append(centroids);
        if (batches > 1) {
          sb.append(", ").append(batches).append(" batches");
        }
        if (dualGeneration) {
          sb.append(", dual generation");
        }
        sb.append(')');
      } else if (planClass == PlanClass.HNSW_SEARCH) {
        sb.append('(').append(candidates).append(" candidates)");
      }
      if (deferredProjection) {
        sb.append(" +semi-join");
      }
      if (serverMerge) {
        sb.append(" +server-merge");
      }
      return sb.toString();
    }
  }

  private PlanClassifier() {
  }

  public static Classification classify(String explain) {
    boolean deferred = explain.contains("SKIP-SCAN-JOIN");
    boolean merge = explain.contains("SERVER MERGE");
    Matcher hnsw = HNSW.matcher(explain);
    if (hnsw.find()) {
      return new Classification(PlanClass.HNSW_SEARCH, 0, 0, 0, false,
        Integer.parseInt(hnsw.group(2)), deferred, merge);
    }
    Matcher probing = PROBING.matcher(explain);
    if (probing.find()) {
      int batches = probing.group(6) != null ? Integer.parseInt(probing.group(6)) : 1;
      return new Classification(PlanClass.IVF_PROBE, Integer.parseInt(probing.group(1)),
        Integer.parseInt(probing.group(2)), batches, probing.group(4) != null, 0, deferred, merge);
    }
    if (VECTOR_GLOBAL.matcher(explain).find()) {
      return new Classification(PlanClass.IVF_FULL_SCAN, 0, 0, 0, false, 0, deferred, merge);
    }
    boolean topN = TOP_N.matcher(explain).find();
    if (
      explain.contains("/* selective filter first */") || explain.contains("/* point lookup */")
    ) {
      return new Classification(topN ? PlanClass.FILTER_FIRST : PlanClass.OTHER, 0, 0, 0, false, 0,
        deferred, merge);
    }
    if (topN) {
      return new Classification(PlanClass.EXACT, 0, 0, 0, false, 0, deferred, merge);
    }
    return new Classification(PlanClass.OTHER, 0, 0, 0, false, 0, deferred, merge);
  }
}
