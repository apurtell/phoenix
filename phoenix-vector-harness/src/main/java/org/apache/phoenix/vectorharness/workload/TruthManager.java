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
package org.apache.phoenix.vectorharness.workload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.Digests;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.truth.BruteForceKnn;
import org.apache.phoenix.vectorharness.truth.ConditionPlanner;
import org.apache.phoenix.vectorharness.truth.GroundTruth;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.PublishedTruthValidator;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.RowSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and caches the static and filtered ground truth of the panel over a prefix of the base
 * rows, entirely client side by a double precision brute force scan. The cache key covers the
 * corpus fingerprint, the panel, the row count, the metric, and the conditions, so a cached file is
 * reused only for the exact question it answers. Distance-bounded conditions are derived from the
 * unfiltered truth and their truth is a prefix of it, so a cached file serves any k values and
 * gains the bounds it lacks when it is read.
 */
public final class TruthManager {

  private static final Logger LOG = LoggerFactory.getLogger(TruthManager.class);
  /** The key of the internal unfiltered condition, kept when UNFILTERED is not a run shape. */
  static final String UNFILTERED_KEY = QueryShape.UNFILTERED.id();

  private final HarnessContext ctx;
  private final ConditionPlanner planner;
  private final List<PanelQuery> panel;
  private final List<List<QueryCondition>> relational;
  private final int capacity;

  public TruthManager(HarnessContext ctx) {
    this.ctx = ctx;
    this.planner = new ConditionPlanner(ctx.config(), ctx.corpus().attributeModel());
    this.panel = ctx.corpus().panel(ctx.config().panelSize());
    this.capacity = ConditionPlanner.truthCapacity(ctx.config());
    this.relational = new ArrayList<>(panel.size());
    for (PanelQuery q : panel) {
      List<QueryCondition> cs = new ArrayList<>(planner.relational(q));
      boolean hasUnfiltered = false;
      for (QueryCondition c : cs) {
        if (c.key().equals(UNFILTERED_KEY)) {
          hasUnfiltered = true;
        }
      }
      if (!hasUnfiltered) {
        cs.add(0, QueryCondition.builder(QueryShape.UNFILTERED).build());
      }
      relational.add(cs);
    }
  }

  public ConditionPlanner planner() {
    return planner;
  }

  public List<PanelQuery> panel() {
    return panel;
  }

  public int capacity() {
    return capacity;
  }

  /** Returns a digest of the conditions and the truth capacity, part of the cache key. */
  public String conditionsDigest() {
    MessageDigest md = Digests.sha256();
    Digests.update(md, capacity);
    Digests.update(md, Double.toString(ctx.config().distanceBoundRank()));
    for (List<QueryCondition> cs : relational) {
      Digests.update(md, cs.size());
      for (QueryCondition c : cs) {
        Digests.update(md, c.key());
      }
    }
    return Digests.hex(md.digest());
  }

  /**
   * Returns the truth over base rows [0, rows), from the cache when it holds it, else computed and
   * cached.
   */
  public GroundTruth truth(long rows) throws IOException {
    String fp = ctx.corpus().fingerprint();
    String pd = ctx.corpus().panelDigest(panel.size());
    String metric = ctx.metric().name();
    Path file = ctx.store().truthFile(fp, pd, rows, metric, conditionsDigest());
    if (Files.exists(file)) {
      try {
        GroundTruth t = GroundTruth.read(file);
        if (t.matches(fp, pd, rows, metric, capacity)) {
          LOG.info("Using cached ground truth {}", file);
          return withBounds(t);
        }
      } catch (IOException e) {
        LOG.warn("Ignoring unreadable ground truth cache {}: {}", file, e.getMessage());
      }
    }
    GroundTruth t = compute(rows, fp, pd, metric);
    t.write(file);
    return t;
  }

  private GroundTruth compute(long rows, String fp, String pd, String metric) {
    long start = System.nanoTime();
    List<float[]> vectors = new ArrayList<>(panel.size());
    for (PanelQuery q : panel) {
      vectors.add(q.vector());
    }
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
    BruteForceKnn knn = new BruteForceKnn(ctx.metric(), threads);
    final long total = Math.min(rows, ctx.corpus().rows());
    final long[] lastLog = { System.nanoTime() };
    List<Map<String, NeighborList>> lists =
      knn.compute(vectors, relational, RowSource.prefix(ctx.corpus(), rows), capacity, scanned -> {
        long now = System.nanoTime();
        synchronized (lastLog) {
          if (now - lastLog[0] > 30_000_000_000L) {
            lastLog[0] = now;
            LOG.info("Ground truth: scanned {} of {} rows", scanned, total);
          }
        }
      });
    List<Map<String, NeighborList>> out = new ArrayList<>(lists.size());
    for (Map<String, NeighborList> m : lists) {
      Map<String, NeighborList> withBounds = new LinkedHashMap<>(m);
      addBounds(withBounds);
      out.add(withBounds);
    }
    LOG.info("Computed ground truth of {} queries over {} rows in {} s", panel.size(), total,
      String.format("%.1f", (System.nanoTime() - start) / 1e9));
    return new GroundTruth(fp, pd, rows, metric, capacity, out);
  }

  /**
   * Adds the truth of the distance-bounded conditions of one query, a prefix of its unfiltered
   * truth, for each bound that the lists lack. Returns true if it added any.
   */
  private boolean addBounds(Map<String, NeighborList> lists) {
    NeighborList unfiltered = lists.get(UNFILTERED_KEY);
    if (unfiltered == null) {
      return false;
    }
    boolean added = false;
    for (QueryCondition b : planner.distanceBound(unfiltered)) {
      if (!lists.containsKey(b.key())) {
        lists.put(b.key(), ConditionPlanner.boundedPrefix(unfiltered, b));
        added = true;
      }
    }
    return added;
  }

  /**
   * Returns a cached truth with the truth of every distance-bounded condition of the profile. The
   * bounds depend on the k values and on whether the profile runs distance-bounded queries, which
   * the cache key leaves out, since their truth is exact to derive from the cached unfiltered
   * truth: the radius lies between two of its ranks.
   */
  private GroundTruth withBounds(GroundTruth t) {
    List<Map<String, NeighborList>> out = new ArrayList<>(t.queries());
    boolean added = false;
    for (int i = 0; i < t.queries(); i++) {
      Map<String, NeighborList> m = new LinkedHashMap<>(t.forQuery(i));
      added |= addBounds(m);
      out.add(m);
    }
    return added
      ? new GroundTruth(t.fingerprint(), t.panelDigest(), t.rows(), t.metric(), t.capacity(), out)
      : t;
  }

  /**
   * Returns the conditions of each panel query that the run measures: the relational conditions of
   * the active shapes and the distance-bounded conditions derived from a truth. The internal
   * unfiltered condition is left out when UNFILTERED is not an active shape.
   */
  public List<List<QueryCondition>> conditions(GroundTruth truth) {
    boolean unfilteredActive = planner.activeShapes().contains(QueryShape.UNFILTERED);
    List<List<QueryCondition>> out = new ArrayList<>(panel.size());
    for (int i = 0; i < panel.size(); i++) {
      List<QueryCondition> cs = new ArrayList<>();
      for (QueryCondition c : relational.get(i)) {
        if (unfilteredActive || !c.key().equals(UNFILTERED_KEY)) {
          cs.add(c);
        }
      }
      NeighborList unfiltered = truth.get(i, UNFILTERED_KEY);
      if (unfiltered != null) {
        cs.addAll(planner.distanceBound(unfiltered));
      }
      out.add(cs);
    }
    return out;
  }

  /** Returns the relational conditions of each panel query, with the internal unfiltered one. */
  public List<List<QueryCondition>> relationalConditions() {
    return relational;
  }

  /** Checks the unfiltered truth against the corpus's published ground truth, if it has one. */
  public PublishedTruthValidator.Result validatePublished(GroundTruth truth) {
    List<NeighborList> unfiltered = new ArrayList<>();
    for (int i = 0; i < panel.size(); i++) {
      unfiltered.add(truth.get(i, UNFILTERED_KEY));
    }
    int[][] published = ctx.corpus().publishedNeighbors();
    int ranks =
      published == null || published.length == 0 ? 0 : Math.min(published[0].length, capacity);
    return new PublishedTruthValidator(ctx.metric(), ctx.config().tieEpsilon())
      .validate(ctx.corpus(), panel, unfiltered, ranks);
  }
}
