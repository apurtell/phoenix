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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates computed ground truth against published benchmark ground truth files. Verifies neighbor
 * distances by rank within tolerance to account for equidistant tie permutations.
 */
public final class PublishedTruthValidator {

  private static final Logger LOG = LoggerFactory.getLogger(PublishedTruthValidator.class);

  /** Validation result details. */
  public static final class Result {
    private final int queries;
    private final int ranks;
    private final List<String> mismatches;

    Result(int queries, int ranks, List<String> mismatches) {
      this.queries = queries;
      this.ranks = ranks;
      this.mismatches = mismatches;
    }

    public int queries() {
      return queries;
    }

    public int ranks() {
      return ranks;
    }

    public boolean agrees() {
      return mismatches.isEmpty();
    }

    /** Returns descriptions of mismatched queries. */
    public List<String> mismatches() {
      return mismatches;
    }
  }

  private final Metric metric;
  private final double epsilon;

  public PublishedTruthValidator(Metric metric, double epsilon) {
    this.metric = metric;
    this.epsilon = epsilon;
  }

  /**
   * Validates computed unfiltered panel truth against published benchmark neighbors.
   * @param corpus source corpus
   * @param panel  panel queries
   * @param truth  unfiltered neighbor lists in panel order
   * @param ranks  number of top ranks to validate
   */
  public Result validate(Corpus corpus, List<PanelQuery> panel, List<NeighborList> truth,
    int ranks) {
    List<String> mismatches = new ArrayList<>();
    Metric defined = corpus.definedMetric();
    if (defined != null && defined != metric) {
      LOG.info("Published ground truth of {} is for {}, but the run uses {}; it is not compared",
        corpus.sourceName(), defined, metric);
      return new Result(0, 0, mismatches);
    }
    int[][] published = corpus.publishedNeighbors();
    if (published == null) {
      return new Result(0, 0, mismatches);
    }
    int n = Math.min(panel.size(), Math.min(published.length, truth.size()));
    int compared = 0;
    int bad = 0;
    for (int q = 0; q < n; q++) {
      int r = Math.min(ranks, Math.min(published[q].length, truth.get(q).size()));
      compared = Math.max(compared, r);
      float[] qv = panel.get(q).vector();
      for (int i = 0; i < r; i++) {
        int p = published[q][i];
        if (p < 0 || p >= corpus.rows()) {
          bad++;
          if (mismatches.size() < 20) {
            mismatches.add(String.format(Locale.ROOT,
              "query %d rank %d: published neighbor %d is" + " outside the corpus", q, i, p));
          }
          break;
        }
        double dp = metric.distance(qv, corpus.vector(p));
        double dt = truth.get(q).distance(i);
        double tol = Math.max(epsilon * Math.abs(dt), 1e-9);
        if (Math.abs(dp - dt) > tol) {
          bad++;
          if (mismatches.size() < 20) {
            mismatches.add(String.format(Locale.ROOT,
              "query %d rank %d: published neighbor %d at %.9g, computed %d at %.9g", q, i, p, dp,
              truth.get(q).ordinal(i), dt));
          }
          break;
        }
      }
    }
    if (bad > 0 && mismatches.size() == 20) {
      mismatches.add(bad + " queries disagree in total");
    }
    return new Result(n, compared, mismatches);
  }
}
