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

import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.assertNeighborsEqual;
import static org.apache.phoenix.vectorharness.truth.TruthTestSupport.prefix;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.Embedding;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.corpus.SplitMix64;
import org.apache.phoenix.vectorharness.truth.QueryCondition.Attribute;
import org.junit.Test;

public class LiveGroundTruthTest {

  private static final int ROWS = 2000;
  private static final int DIM = 8;
  private static final int TRACKED = 6;
  /** The window capacity: k = 10 with a small margin, so that deletes force recomputations. */
  private static final int CAPACITY = 12;
  private static final int[] KS = { 1, 3, 5, 10, CAPACITY };
  /** The position of the condition that no row passes. */
  private static final int EMPTY_CONDITION = 9;

  /** A corpus under mutation, its live truth, and the mutations that keep both in step. */
  private static final class Scenario {
    final Metric metric;
    final Corpus corpus;
    final LiveCorpus live;
    final List<PanelQuery> tracked;
    final List<float[]> vectors = new ArrayList<>();
    final List<List<QueryCondition>> conditions = new ArrayList<>();
    final LiveGroundTruth truth;
    final BruteForceKnn brute;
    final SplitMix64 rnd;
    final int missing;
    long seq;
    long applied;
    int checks;
    int windowDeletes;

    /**
     * Builds live truth over a fresh synthetic corpus from exact lists of its initial state.
     * @param loaded      the base rows present at the start
     * @param initialSize the length of the initial lists, which may exceed the window capacity
     * @param skipLookups true to leave some initial lists to the truth's own scan
     */
    Scenario(Metric metric, long loaded, int initialSize, boolean skipLookups, long seed) {
      this.metric = metric;
      this.corpus = TruthTestSupport.synthetic(ROWS, DIM, metric);
      this.live = new LiveCorpus(corpus, loaded);
      this.tracked = corpus.panel(TRACKED);
      this.brute = new BruteForceKnn(metric, 2);
      this.rnd = new SplitMix64(seed);
      QueryCondition unfiltered = QueryCondition.builder(QueryShape.UNFILTERED).build();
      for (PanelQuery q : tracked) {
        vectors.add(q.vector());
        NeighborList near = brute.single(q.vector(), unfiltered, live, 41);
        conditions.add(conditions(q, near));
      }
      List<Map<String, NeighborList>> initial =
        brute.compute(vectors, conditions, live, initialSize, null);
      Map<Integer, Map<String, NeighborList>> byPanelIndex = new HashMap<>();
      for (int i = 0; i < tracked.size(); i++) {
        byPanelIndex.put(tracked.get(i).index(), initial.get(i));
      }
      Set<String> skipped = new HashSet<>();
      if (skipLookups) {
        for (List<QueryCondition> cs : conditions) {
          skipped.add(cs.get(1).key());
          skipped.add(cs.get(7).key());
        }
      }
      int[] misses = new int[1];
      this.truth = new LiveGroundTruth(metric, tracked, conditions, (panelIndex, key) -> {
        if (skipped.contains(key)) {
          misses[0]++;
          return null;
        }
        return byPanelIndex.get(panelIndex).get(key);
      }, CAPACITY, live, 1);
      this.missing = misses[0];
    }

    /**
     * The conditions of a tracked query: each relational predicate, combinations, a condition that
     * about as many rows pass as the window holds, two distance bounds, and one that no row passes.
     */
    private static List<QueryCondition> conditions(PanelQuery q, NeighborList near) {
      int i = q.index();
      int t = q.tenant();
      List<QueryCondition> out = new ArrayList<>();
      out.add(QueryCondition.builder(QueryShape.UNFILTERED).build());
      out.add(QueryCondition.builder(QueryShape.COVERED_FILTER)
        .range(Attribute.CATEGORY, 4 * (i % 5), 4 * (i % 5) + 10).build());
      out.add(
        QueryCondition.builder(QueryShape.UNCOVERED_FILTER).range(Attribute.SOURCE, 0, 4).build());
      out.add(QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(t).build());
      double start = 0.1 * (i % 5);
      out.add(QueryCondition.builder(QueryShape.PK_RANGE)
        .docIds(AttributeModel.docIdBound(start), AttributeModel.docIdBound(start + 0.3)).build());
      out.add(QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(t)
        .range(Attribute.CATEGORY, 0, 20).build());
      // About 2000 / 40 / 4 = 12 rows pass, so the window is complete at times and not at others.
      out.add(QueryCondition.builder(QueryShape.COVERED_FILTER).tenant(i % 4)
        .range(Attribute.CATEGORY, 7, 8).build());
      out.add(QueryCondition.builder(QueryShape.DISTANCE_BOUND)
        .radius(0.5 * (near.distance(5) + near.distance(6)), 10).build());
      out.add(QueryCondition.builder(QueryShape.DISTANCE_BOUND).tenant(t)
        .radius(0.5 * (near.distance(30) + near.distance(31)), 40).build());
      out.add(QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(99).build());
      return out;
    }

    void commit(long ordinal, float[] vector, DocAttributes attrs, int cluster) {
      live.put(ordinal, vector, attrs, cluster);
      truth.apply(ordinal, vector, attrs);
      applied++;
    }

    void delete(long ordinal) {
      live.delete(ordinal);
      truth.apply(ordinal, null, null);
      applied++;
    }

    long randomLive() {
      long o = live.randomLive(rnd, null);
      assertTrue("no live row was found", o >= 0);
      return o;
    }

    void insert() {
      long o = live.allocateOrdinal();
      Embedding e = corpus.mutations().insert(o, 0.0);
      commit(o, e.vector(), corpus.attributes(o), e.cluster());
    }

    /** Inserts a row near a tracked query, so that it enters some windows. */
    void insertNear() {
      float[] q = vectors.get(rnd.nextInt(vectors.size()));
      double norm = 0.0;
      for (float x : q) {
        norm += (double) x * x;
      }
      double scale = 0.2 * rnd.nextDouble() * Math.sqrt(norm / q.length);
      float[] v = new float[q.length];
      for (int j = 0; j < v.length; j++) {
        v[j] = (float) (q[j] + scale * rnd.nextGaussian());
      }
      long o = live.allocateOrdinal();
      commit(o, v, corpus.attributes(o), -1);
    }

    /**
     * Writes a copy of a window member, which ties with it in distance: as an insert, whose ordinal
     * orders after the member, or as an update of a random row, whose ordinal may order before it.
     */
    void writeTie() {
      int qi = rnd.nextInt(tracked.size());
      NeighborList w = truth.window(qi, rnd.nextInt(truth.conditionCount(qi)));
      if (w.size() == 0) {
        insert();
        return;
      }
      long member = w.ordinal(rnd.nextInt(w.size()));
      LiveCorpus.RowState s = live.get(member);
      long o = rnd.nextDouble() < 0.5 ? live.allocateOrdinal() : randomLive();
      if (o == member) {
        return;
      }
      commit(o, s.vector().clone(), s.attributes(), s.cluster());
    }

    void updateLocal() {
      long o = randomLive();
      LiveCorpus.RowState s = live.get(o);
      Embedding e = corpus.mutations().reembedLocal(o, s.cluster(), s.vector(), ++seq, 0.0);
      commit(o, e.vector(), s.attributes(), e.cluster());
    }

    void move(long o) {
      LiveCorpus.RowState s = live.get(o);
      Embedding e = corpus.mutations().move(o, s.cluster(), ++seq, 0.0);
      commit(o, e.vector(), s.attributes(), e.cluster());
    }

    void updateAttributes(long o) {
      LiveCorpus.RowState s = live.get(o);
      DocAttributes a = corpus.attributeModel().updatedTerms(s.attributes(), ++seq);
      commit(o, s.vector(), a, s.cluster());
    }

    /** Deletes, moves, or re-attributes a member of a random window. */
    void hitWindow() {
      int qi = rnd.nextInt(tracked.size());
      NeighborList w = truth.window(qi, rnd.nextInt(truth.conditionCount(qi)));
      if (w.size() == 0) {
        return;
      }
      long o = w.ordinal(rnd.nextInt(w.size()));
      double u = rnd.nextDouble();
      if (u < 0.6) {
        delete(o);
        windowDeletes++;
      } else if (u < 0.8) {
        move(o);
      } else {
        updateAttributes(o);
      }
    }

    void step() {
      double u = rnd.nextDouble();
      if (u < 0.17) {
        insert();
      } else if (u < 0.27) {
        insertNear();
      } else if (u < 0.34) {
        writeTie();
      } else if (u < 0.45) {
        updateLocal();
      } else if (u < 0.52) {
        move(randomLive());
      } else if (u < 0.62) {
        updateAttributes(randomLive());
      } else if (u < 0.75) {
        delete(randomLive());
      } else {
        hitWindow();
      }
    }

    /** Checks every tracked answer against a fresh scan of the live corpus. */
    void verify(String when) {
      List<Map<String, NeighborList>> fresh =
        brute.compute(vectors, conditions, live, CAPACITY, null);
      for (int i = 0; i < tracked.size(); i++) {
        for (int c = 0; c < conditions.get(i).size(); c++) {
          String key = conditions.get(i).get(c).key();
          NeighborList full = fresh.get(i).get(key);
          for (int k : KS) {
            String msg = metric + " " + when + ": query " + i + " " + key + " k=" + k;
            NeighborList expected = prefix(full, k);
            NeighborList actual = truth.topK(i, c, k);
            assertNeighborsEqual(msg, expected, actual);
            // A complete answer must really hold every passing row, and an answer shorter than k
            // must say that it is complete. A window that happens to hold every passing row may
            // not know it, so the converse is not required.
            if (actual.complete()) {
              assertTrue(msg + ": falsely complete", expected.complete());
            }
            if (expected.size() < k) {
              assertTrue(msg + ": short but not complete", actual.complete());
            }
          }
          NeighborList window = truth.window(i, c);
          assertTrue(window.size() <= CAPACITY);
          assertNeighborsEqual(metric + " " + when + ": window of query " + i + " " + key,
            prefix(full, window.size()), window);
        }
      }
      assertTrue(metric + " " + when + ": window counts", truth.countsMatchWindows());
      checks++;
    }

    void run(int steps, int verifyEvery) {
      for (int s = 1; s <= steps; s++) {
        step();
        if (s % verifyEvery == 0) {
          verify("step " + s);
        }
      }
      verify("end");
    }
  }

  private static void runMutationScenario(Metric metric, int steps, long seed) {
    Scenario s = new Scenario(metric, ROWS, 2 * CAPACITY, true, seed);
    assertEquals(2 * TRACKED, s.missing);
    assertEquals("missing initial lists are scanned", s.missing, s.truth.recomputes());
    s.verify("start");
    long before = s.truth.recomputes();
    s.run(steps, 40);
    assertEquals(steps / 40 + 2, s.checks);
    assertEquals(s.applied, s.truth.updates());
    assertTrue("the scenario should delete window members", s.windowDeletes > 50);
    assertTrue(metric + ": deletes of window members should force recomputations",
      s.truth.recomputes() > before);
    assertTrue(s.live.ordinalLimit() > ROWS);
  }

  @Test
  public void testFollowsMutationsL2() {
    runMutationScenario(Metric.L2, 2000, 1);
  }

  @Test
  public void testFollowsMutationsCosine() {
    runMutationScenario(Metric.COSINE, 1200, 2);
  }

  @Test
  public void testFollowsMutationsInnerProduct() {
    runMutationScenario(Metric.INNER_PRODUCT, 1200, 3);
  }

  @Test
  public void testFollowsStreamingIngest() {
    // Base rows that load after the build arrive as inserts, interleaved with mutations.
    Scenario s = new Scenario(Metric.L2, 1200, CAPACITY, false, 4);
    assertEquals(0, s.truth.recomputes());
    s.verify("start");
    long loaded = s.live.loaded();
    int round = 0;
    while (loaded < ROWS) {
      long upTo = Math.min(ROWS, loaded + 40);
      for (long o = loaded; o < upTo; o++) {
        s.truth.apply(o, s.corpus.vector(o), s.corpus.attributes(o));
        s.applied++;
      }
      s.live.markLoaded(loaded, upTo);
      loaded = upTo;
      for (int j = 0; j < 15; j++) {
        s.step();
      }
      if (++round % 4 == 0) {
        s.verify("ingest round " + round);
      }
    }
    s.verify("after ingest");
    assertEquals(s.applied, s.truth.updates());
    assertEquals(ROWS, s.live.loaded());
  }

  @Test
  public void testFollowsConcurrentStreamingIngest() throws Exception {
    // Loader threads apply their batches at once, as the streaming phase does.
    Scenario s = new Scenario(Metric.L2, 1200, CAPACITY, false, 8);
    int threads = 4;
    long from = s.live.loaded();
    long per = (ROWS - from) / threads;
    List<Thread> workers = new ArrayList<>();
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    for (int t = 0; t < threads; t++) {
      long start = from + t * per;
      long end = t == threads - 1 ? ROWS : start + per;
      Thread w = new Thread(() -> {
        try {
          for (long a = start; a < end; a += 25) {
            long b = Math.min(end, a + 25);
            s.live.markLoaded(a, b);
            for (long o = a; o < b; o++) {
              s.truth.apply(o, s.corpus.vector(o), s.corpus.attributes(o));
            }
          }
        } catch (Throwable e) {
          failures.add(e);
        }
      });
      workers.add(w);
      w.start();
    }
    for (Thread w : workers) {
      w.join();
    }
    assertTrue(failures.toString(), failures.isEmpty());
    assertEquals(ROWS - from, s.truth.updates());
    assertEquals(ROWS, s.live.loaded());
    s.verify("after concurrent ingest");
    s.run(400, 100);
  }

  @Test
  public void testDeletingWindowMembersForcesARecompute() {
    Scenario s = new Scenario(Metric.L2, ROWS, CAPACITY, false, 5);
    assertEquals(0, s.truth.recomputes());
    NeighborList window = s.truth.window(0, 0);
    assertEquals(CAPACITY, window.size());
    assertFalse(window.complete());
    // Delete ten of the twelve window members; the other two remain the exact first two.
    for (int i = 0; i < 10; i++) {
      s.delete(window.ordinal(i));
    }
    assertEquals(2, s.truth.window(0, 0).size());
    assertNeighborsEqual("top 2",
      prefix(s.brute.single(s.vectors.get(0), s.conditions.get(0).get(0), s.live, CAPACITY), 2),
      s.truth.topK(0, 0, 2));
    assertEquals("two rows answer k = 2 without a scan", 0, s.truth.recomputes());
    NeighborList top10 = s.truth.topK(0, 0, 10);
    assertEquals(1, s.truth.recomputes());
    NeighborList exact =
      s.brute.single(s.vectors.get(0), s.conditions.get(0).get(0), s.live, CAPACITY);
    assertNeighborsEqual("top 10 after the recompute", prefix(exact, 10), top10);
    for (int i = 0; i < 10; i++) {
      assertFalse(s.live.isLive(window.ordinal(i)));
      for (int r = 0; r < top10.size(); r++) {
        assertTrue(window.ordinal(i) != top10.ordinal(r));
      }
    }
    assertEquals(CAPACITY, s.truth.window(0, 0).size());
    s.truth.topK(0, 0, 10);
    assertEquals("a refilled window needs no scan", 1, s.truth.recomputes());

    // Emptying every window of a query recomputes each one that has rows outside it.
    for (int c = 0; c < s.truth.conditionCount(1); c++) {
      NeighborList w = s.truth.window(1, c);
      for (int i = 0; i < w.size(); i++) {
        if (s.live.isLive(w.ordinal(i))) {
          s.delete(w.ordinal(i));
        }
      }
    }
    s.verify("after emptying the windows of query 1");
    assertTrue(s.truth.recomputes() > 1);
  }

  @Test
  public void testCompleteWindowsAnswerWithoutScans() {
    Scenario s = new Scenario(Metric.L2, ROWS, CAPACITY, false, 6);
    NeighborList none = s.truth.topK(0, EMPTY_CONDITION, 10);
    assertEquals(0, none.size());
    assertTrue(none.complete());
    for (int i = 0; i < 50; i++) {
      s.insert();
    }
    assertEquals(0, s.truth.topK(0, EMPTY_CONDITION, 10).size());
    assertEquals(0, s.truth.recomputes());

    // A row that passes a complete window's predicate enters it without a scan.
    QueryCondition tenant99 = s.truth.condition(0, EMPTY_CONDITION);
    long o = s.live.allocateOrdinal();
    DocAttributes a = new DocAttributes(o, 99, "t99", AttributeModel.docId(o), 0, 0, 0, 0L, "");
    assertTrue(tenant99.test(a));
    s.commit(o, s.vectors.get(0).clone(), a, -1);
    NeighborList one = s.truth.topK(0, EMPTY_CONDITION, 10);
    assertEquals(1, one.size());
    assertEquals(o, one.ordinal(0));
    assertTrue(one.complete());
    assertEquals(0, s.truth.recomputes());
    s.delete(o);
    assertEquals(0, s.truth.topK(0, EMPTY_CONDITION, 10).size());
    assertTrue(s.truth.topK(0, EMPTY_CONDITION, 10).complete());
    assertEquals(0, s.truth.recomputes());
    s.verify("end");
  }

  @Test
  public void testAccessors() {
    Scenario s = new Scenario(Metric.COSINE, ROWS, CAPACITY, false, 7);
    assertEquals(TRACKED, s.truth.trackedQueries());
    for (int i = 0; i < TRACKED; i++) {
      assertEquals(s.conditions.get(i).size(), s.truth.conditionCount(i));
      for (int c = 0; c < s.truth.conditionCount(i); c++) {
        assertSame(s.conditions.get(i).get(c), s.truth.condition(i, c));
        assertNotNull(s.truth.window(i, c));
      }
    }
    assertEquals(0, s.truth.updates());
    s.insert();
    s.delete(0);
    assertEquals(2, s.truth.updates());
  }

  private static NeighborList list(boolean complete, long[] o, double[] d) {
    return new NeighborList(o, d, complete);
  }

  private static long[] ordinals(NeighborList l) {
    return TopKTest.ordinals(l);
  }

  @Test
  public void testWindowKeepsItsBound() {
    LiveGroundTruth.Window w = new LiveGroundTruth.Window(
      list(false, new long[] { 1, 2, 3 }, new double[] { 1.0, 2.0, 3.0 }), 3);
    assertFalse(w.complete);
    assertEquals(3.0, w.boundDistance, 0.0);
    assertEquals(3, w.boundOrdinal);
    // An insert inside the bound evicts the last row, which becomes the bound.
    w.insert(4, 2.5);
    assertEquals(3, w.size);
    assertEquals(2.5, w.boundDistance, 0.0);
    assertEquals(4, w.boundOrdinal);
    assertEquals("[1, 2, 4]", Arrays.toString(ordinals(w.head(3))));
    // A row past the bound is outside and is ignored.
    w.insert(5, 2.7);
    assertEquals("[1, 2, 4]", Arrays.toString(ordinals(w.head(3))));
    // A removal shrinks the window and leaves the bound in place.
    w.remove(2);
    assertEquals(2, w.size);
    assertEquals(2.5, w.boundDistance, 0.0);
    w.remove(42);
    assertEquals(2, w.size);
    // A tie with the bound enters only with a smaller ordinal.
    w.insert(9, 2.5);
    assertEquals(2, w.size);
    w.insert(0, 2.5);
    assertEquals("[1, 0, 4]", Arrays.toString(ordinals(w.head(3))));
    assertFalse(w.head(3).complete());
    assertEquals(1, w.head(1).size());
  }

  @Test
  public void testCompleteWindowOverflows() {
    LiveGroundTruth.Window w =
      new LiveGroundTruth.Window(list(true, new long[] { 1, 2 }, new double[] { 1.0, 2.0 }), 3);
    assertTrue(w.complete);
    assertEquals(Double.POSITIVE_INFINITY, w.boundDistance, 0.0);
    assertTrue(w.head(5).complete());
    assertFalse("a head shorter than the window", w.head(1).complete());
    w.insert(7, 100.0);
    assertEquals(3, w.size);
    assertTrue(w.complete);
    w.insert(8, 0.5);
    assertFalse(w.complete);
    assertEquals(3, w.size);
    assertEquals(2.0, w.boundDistance, 0.0);
    assertEquals("[8, 1, 2]", Arrays.toString(ordinals(w.head(3))));
    assertFalse(w.head(3).complete());
  }

  @Test
  public void testInitialListsAreCutToTheCapacity() {
    NeighborList longComplete =
      list(true, new long[] { 1, 2, 3, 4 }, new double[] { 1.0, 2.0, 3.0, 4.0 });
    LiveGroundTruth.Window w = new LiveGroundTruth.Window(longComplete, 3);
    assertEquals(3, w.size);
    assertFalse("rows were cut off", w.complete);
    assertEquals(3.0, w.boundDistance, 0.0);
    w.insert(4, 4.0);
    assertEquals("[1, 2, 3]", Arrays.toString(ordinals(w.head(3))));

    LiveGroundTruth.Window empty = new LiveGroundTruth.Window(NeighborList.empty(), 3);
    assertTrue(empty.complete);
    empty.insert(5, 1.0);
    assertEquals(1, empty.size);

    // An empty list that is not complete knows nothing, so every row is outside it.
    LiveGroundTruth.Window unknown = new LiveGroundTruth.Window(longComplete.head(0), 3);
    assertFalse(unknown.complete);
    unknown.insert(5, -1e300);
    assertEquals(0, unknown.size);
  }
}
